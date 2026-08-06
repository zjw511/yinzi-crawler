package com.yinzi.crawler.network

import android.content.Context
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post
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

    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
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
        // 1) 主路径：官方 JSON API
        val json = runCatching {
            Net.api.postList(groupId = groupId, page = apiPage, limit = 30, type = 0)
        }
        val parsed = json.mapCatching { YubaParser.parseListFromApi(it) }
        var list = parsed.getOrDefault(emptyList())
        var via = if (list.isNotEmpty()) "json" else "empty"
        val apiErr: String? = json.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        val parseErr: String? = parsed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

        // 2) 兜底：WebView DOM 抽
        if (list.isEmpty()) {
            val htmlPosts = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    WebViewFetcher.fetchPosts(appCtx, groupId, apiPage)
                }
            }
            val got = htmlPosts.getOrDefault(emptyList())
            list = got
            if (got.isNotEmpty()) {
                via = "webview"
            }
        }

        // 3) 详情补全：v1.9 起，所有帖子都进详情页补全（列表 API 的 imglist 最多 3 张，
        //    多图帖的完整图片和视频直链只在详情 content 的 BBCode + 渲染后的 demand-video 里）。
        //    并发执行避免阻塞；前 15 个帖子补全（覆盖首屏可见的帖子），其余保持列表的 3 张
        val toFix = list.mapNotNull { p ->
            val pid = p.media.firstOrNull()?.postId ?: p.id
            p to pid
        }.take(15)
        if (toFix.isNotEmpty()) {
            val fixedMap = toFix.map { (p, pid) ->
                async(Dispatchers.IO) { p.id to fetchPostMedia(pid) }
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

        FetchResult(posts = list, via = via, apiError = apiErr, parseError = parseErr)
    }

    /**
     * 进入帖子详情拿完整媒体。
     * v1.9: API 拿 content 里的所有图片（多图帖补全）；
     *      如果帖子是视频帖（extension_type=8 或 content 有 [video] 标签），
     *      再走 WebView 拦截 m3u8 视频流（PC 版渲染 demand-video）。
     */
    suspend fun fetchPostMedia(postId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        // 1) 先调 API 拿图片（content 里的所有 [img] 标签）
        val apiResult = runCatching {
            val raw = Net.api.postHead(postId)
            val media = YubaParser.extractMediaFromDetail(raw, postId)
            // 解析 JSON 判断是否视频帖
            val isVideoPost = YubaParser.isVideoPost(raw)
            Triple(raw, media, isVideoPost)
        }.getOrNull()

        val fromApi = apiResult?.second ?: emptyList()
        val isVideoPost = apiResult?.third ?: false

        // 2) 如果没拿到视频直链，且帖子是视频帖，走 WebView 拦截 m3u8
        val hasRealVideo = fromApi.any { it.isVideo && it.url.isNotBlank() }
        if (!hasRealVideo && (isVideoPost || fromApi.isEmpty())) {
            val fromWeb = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    WebViewFetcher.fetchPostDetail(appCtx, postId)
                }
            }.getOrDefault(emptyList())
            // 合并：API 图片 + WebView 视频，按 URL 去重
            val urls = fromApi.map { it.url }.toMutableSet()
            val merged = fromApi.toMutableList()
            for (m in fromWeb) {
                if (m.url.isNotBlank() && urls.add(m.url)) merged.add(m)
            }
            return@withContext merged
        }
        fromApi
    }
}
