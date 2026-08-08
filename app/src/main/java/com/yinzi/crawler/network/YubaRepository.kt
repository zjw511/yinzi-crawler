package com.yinzi.crawler.network

import android.content.Context
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.util.DebugLog
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 鱼吧数据仓库 v1.1.1 — 切回官方 wbapi 接口。
 *
 * 主链路：OkHttp + Retrofit 直接调 JSON（快、省流量、稳）
 *    GET https://yubam.douyu.com/wbapi/web/group/postlist?group_id=...&page=1&limit=30
 *
 * 兜底：如果 JSON 接口异常 → 用 WebView 渲染 yubam.douyu.com/group/{id} 抽 DOM
 *    (WebViewFetcher.fetchPosts)
 *
 * 下载：DownloadManager 不变
 */
object YubaRepository {

    private const val TAG = "Repo"

    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        DebugLog.i(TAG, "✅ YubaRepository 初始化完成，groupId=${Prefs.groupId}，匿名=${Prefs.isAnonymous}")
    }

    /** 拉取结果附带调试信息：走了哪条链路、抓到多少条 */
    data class FetchResult(
        val posts: List<Post>,
        val via: String,          // "json" / "webview" / "empty"
        val apiError: String? = null,
        val parseError: String? = null
    )

    /** 拉取一页帖子；page 从 0 开始（第一页=0） */
    suspend fun fetchPosts(
        groupId: String = Prefs.groupId,
        page: Int = 0
    ): List<Post> = fetchPostsDebug(groupId, page).posts

    /** 调试版：返回链路信息，UI 层可弹提示告诉用户到底走了哪条路、拿到多少数据 */
    suspend fun fetchPostsDebug(
        groupId: String = Prefs.groupId,
        page: Int = 0
    ): FetchResult = withContext(Dispatchers.IO) {
        val apiPage = page + 1   // API 是 1-based
        DebugLog.i(TAG, "═══════════════════════════════════")
        DebugLog.i(TAG, "🚀 开始拉取第 ${page+1} 页（API page=$apiPage），groupId=$groupId")

        // 1) 主路径：官方 JSON API
        DebugLog.d(TAG, "1️⃣  主路径：调用官方 JSON 接口 postList")
        val json = runCatching {
            Net.api.postList(groupId = groupId, page = apiPage, limit = 30, type = 0)
        }
        val jsonRaw = json.getOrNull()
        DebugLog.d(TAG, "   JSON接口结果：${if (json.isSuccess) "✅成功(${jsonRaw?.length ?: 0}字符)" else "❌失败：${json.exceptionOrNull()?.message ?: json.exceptionOrNull()?.javaClass?.simpleName}"}")

        val parsed = json.mapCatching { YubaParser.parseListFromApi(it) }
        var list = parsed.getOrDefault(emptyList())
        DebugLog.d(TAG, "   JSON解析结果：${if (parsed.isSuccess) "✅成功，${list.size} 条帖子" else "❌失败：${parsed.exceptionOrNull()?.message}"}")
        if (list.isNotEmpty()) {
            list.take(3).forEachIndexed { i, p ->
                val mediaSummary = p.media.groupBy { it.isVideo }.map { (isV, items) -> "${if (isV) "视频" else "图片"}:${items.size}" }.joinToString(",")
                DebugLog.d(TAG, "     帖子[$i] id=${p.id} 作者=${p.author} 媒体=${mediaSummary} 内容前50字=${DebugLog.truncate(p.content, 50)}")
            }
        }

        var via = if (list.isNotEmpty()) "json" else "empty"
        val apiErr: String? = json.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        val parseErr: String? = parsed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

        // 2) 兜底：WebView DOM 抽
        if (list.isEmpty()) {
            DebugLog.w(TAG, "2️⃣  JSON路径没拿到数据，走兜底：WebView 渲染鱼吧列表页抽 DOM")
            val htmlPosts = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    WebViewFetcher.fetchPosts(appCtx, groupId, apiPage)
                }
            }
            val got = htmlPosts.getOrDefault(emptyList())
            list = got
            if (got.isNotEmpty()) {
                via = "webview"
                DebugLog.i(TAG, "   ✅ WebView兜底成功，拿到 ${got.size} 条帖子")
                got.take(2).forEachIndexed { i, p ->
                    DebugLog.d(TAG, "     帖子[$i] id=${p.id} 作者=${p.author} 媒体数=${p.media.size}")
                }
            } else {
                DebugLog.e(TAG, "   ❌ WebView兜底也没拿到数据。异常=${htmlPosts.exceptionOrNull()?.message}")
            }
        }

        // 3) 详情补全：只对需要补全的帖子调详情 API
        //    - 视频帖（needsDetail=true，列表没抓到直链）
        //    - 列表媒体为空的帖子（可能有多图）
        //    已有图片的普通帖子直接用列表数据，不调详情（省 15 个请求）
        val toFix = list.mapNotNull { p ->
            val pid = p.media.firstOrNull()?.postId ?: p.id
            val needsFix = p.media.any { it.isVideo && it.url.isBlank() } || p.media.isEmpty()
            if (needsFix) p to pid else null
        }
        if (toFix.isNotEmpty()) {
            DebugLog.d(TAG, "3️⃣  只对 ${toFix.size} 个需要补全的帖子调详情API（其余直接用列表数据）")
            val fixedMap = toFix.map { (p, pid) ->
                async(Dispatchers.IO) {
                    val mediaBefore = p.media.size
                    val got = fetchPostMedia(pid)
                    DebugLog.d(TAG, "   帖子 id=$pid：详情补全前媒体=$mediaBefore，补到=${got.size}")
                    p.id to got
                }
            }.awaitAll().toMap()
            list = list.map { p ->
                fixedMap[p.id]?.let { extra ->
                    if (extra.isNotEmpty()) {
                        // 合并：列表原有媒体 + 详情补充媒体，按 URL 去重
                        val existingUrls = p.media.map { it.url }.toMutableSet()
                        val merged = p.media.toMutableList()
                        for (m in extra) {
                            if (m.url.isBlank()) continue
                            if (existingUrls.add(m.url)) merged.add(m)
                        }
                        // 如果详情补到了视频直链，移除列表里 url 为空的视频占位
                        val hasRealVideo = merged.any { it.isVideo && it.url.isNotBlank() }
                        if (hasRealVideo) {
                            val filtered = merged.filter { !it.isVideo || it.url.isNotBlank() }
                            p.copy(media = filtered)
                        } else {
                            p.copy(media = merged)
                        }
                    } else p
                } ?: p
            }
        }

        val totalMedia = list.sumOf { it.media.size }
        val videoCount = list.sumOf { p -> p.media.count { it.isVideo } }
        DebugLog.i(TAG, "🏁 拉取完成：链路=$via，帖子=${list.size}个，媒体总数=$totalMedia(视频=$videoCount)")
        DebugLog.i(TAG, "   apiError=${apiErr ?: "无"}，parseError=${parseErr ?: "无"}")
        DebugLog.i(TAG, "═══════════════════════════════════")

        FetchResult(posts = list, via = via, apiError = apiErr, parseError = parseErr)
    }

    /**
     * 进入帖子详情拿完整媒体。
     * v2.0 核心改动：
     *  - 帖子详情 API 不返回视频直链，但 content 里有 data-playurl 指向斗鱼视频分享页
     *  - 提取 data-playurl → WebView 加载分享页 → 拦截 m3u8 请求
     *  - 这是目前获取斗鱼视频直链最可靠的方式
     *
     * 流程：
     *  1) 详情 API 拿图片 + 提取 data-playurl
     *  2) 有 data-playurl → WebView 加载视频分享页 → 拦截 m3u8
     *  3) 没有 data-playurl → 兜底走 PC 版帖子页 WebView
     */
    suspend fun fetchPostMedia(postId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        DebugLog.d(TAG, "  📌 帖子详情补全 id=$postId")

        // 1) 调 API 拿详情
        val rawResult = runCatching { Net.api.postHead(postId) }
        val raw = rawResult.getOrNull()
        DebugLog.d(TAG, "     详情API：${if (rawResult.isSuccess) "✅成功(${raw?.length ?: 0}字符)" else "❌失败：${rawResult.exceptionOrNull()?.message}"}")

        val fromApi = raw?.let { YubaParser.extractMediaFromDetail(it, postId) } ?: emptyList()
        val hasRealVideo = fromApi.any { it.isVideo && it.url.isNotBlank() }
        DebugLog.d(TAG, "     从详情 API 抽到媒体=${fromApi.size}个(含直链视频=$hasRealVideo)：${fromApi.joinToString { (if(it.isVideo) "🎥" else "🖼️") + DebugLog.truncate(it.url, 60) }}")

        // 如果已经有视频直链（极少数情况），直接返回
        if (hasRealVideo) {
            DebugLog.d(TAG, "     ✅ 已有视频直链，直接返回")
            return@withContext fromApi
        }

        // 2) 从 content 里提取所有 data-playurl（多视频帖可能有多个）
        val playUrls = raw?.let { jsonStr ->
            runCatching {
                val content = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                    .let { (it as? kotlinx.serialization.json.JsonObject) }
                val dataObj = (content?.get("data") as? kotlinx.serialization.json.JsonObject) ?: content
                val c = dataObj?.get("content")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: dataObj?.get("describe")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: ""
                val extracted = YubaParser.extractAllVideoPlayUrls(c)
                DebugLog.d(TAG, "     content长度=${c.length}，含data-playurl=${c.contains("data-playurl", true)}，提取到${extracted.size}个分享页链接")
                extracted
            }.getOrElse { t ->
                DebugLog.w(TAG, "     提取 data-playurl 异常：${t.message}")
                emptyList()
            }
        } ?: emptyList()

        // 3) 有 data-playurl → 逐个加载视频分享页拦截 m3u8（支持多视频）
        if (playUrls.isNotEmpty()) {
            DebugLog.i(TAG, "   🎬 发现${playUrls.size}个data-playurl，逐个WebView加载分享页拦截m3u8")
            val allVideoMedia = mutableListOf<MediaItem>()
            for ((idx, playUrl) in playUrls.withIndex()) {
                if (playUrl.isBlank()) continue
                DebugLog.d(TAG, "   🎬 [$idx/${playUrls.size}] 加载分享页：$playUrl")
                val videoMedia = runCatching {
                    withContext(Dispatchers.Main.immediate) {
                        WebViewFetcher.fetchVideoFromSharePage(appCtx, playUrl, postId)
                    }
                }.getOrElse { t ->
                    DebugLog.e(TAG, "   ❌ 拦截m3u8异常[$idx]：${t.message}", t)
                    emptyList()
                }
                DebugLog.d(TAG, "     [$idx] 拦截到视频=${videoMedia.size}个：${videoMedia.joinToString { "🎥" + DebugLog.truncate(it.url, 80) }}")
                allVideoMedia.addAll(videoMedia)
            }
            if (allVideoMedia.isNotEmpty()) {
                // 合并：API 图片 + WebView 视频（去重）
                val urls = fromApi.map { it.url }.toMutableSet()
                val merged = fromApi.toMutableList()
                for (m in allVideoMedia) {
                    if (m.url.isNotBlank() && urls.add(m.url)) merged.add(m)
                }
                DebugLog.d(TAG, "     ✅ 合并后共 ${merged.size} 个媒体(图片+视频)，返回")
                return@withContext merged
            } else {
                DebugLog.w(TAG, "     ⚠️ 分享页也没拦截到 m3u8，继续走下一条路")
            }
        } else {
            DebugLog.d(TAG, "     没有 data-playurl，跳过分享页拦截")
        }

        // 4) 兜底：走 PC 版帖子页 WebView（可能能抓到 demand-video）
        // 优化：没有 data-playurl 时，只有当详情 API 里存在"待补全视频项"才跑 WebView。
        //      图片帖（有图）和纯文字帖（无图无视频）都不加载 WebView，省 15-30 秒。
        val hasPendingVideo = fromApi.any { it.isVideo && it.url.isBlank() }
        if (playUrls.isEmpty() && !hasPendingVideo) {
            val why = if (fromApi.isNotEmpty()) "图片帖(${fromApi.size}图)" else "纯文字帖(0媒体)"
            DebugLog.d(TAG, "     ✅ $why 且无data-playurl，跳过WebView兜底")
            return@withContext fromApi
        }
        DebugLog.w(TAG, "   🛡️ 兜底路径：加载 PC 版帖子页 WebView（待补全视频）")
        val fromWeb = runCatching {
            withContext(Dispatchers.Main.immediate) {
                WebViewFetcher.fetchPostDetail(appCtx, postId)
            }
        }.getOrElse { t ->
            DebugLog.e(TAG, "   ❌ WebView兜底异常：${t.message}", t)
            emptyList()
        }
        DebugLog.d(TAG, "     WebView兜底抽到媒体=${fromWeb.size}个：${fromWeb.joinToString { (if(it.isVideo) "🎥" else "🖼️") + DebugLog.truncate(it.url, 80) }}")

        val urls = fromApi.map { it.url }.toMutableSet()
        val merged = fromApi.toMutableList()
        for (m in fromWeb) {
            if (m.url.isNotBlank() && urls.add(m.url)) merged.add(m)
        }
        DebugLog.d(TAG, "     最终返回 ${merged.size} 个媒体")
        merged
    }
}
