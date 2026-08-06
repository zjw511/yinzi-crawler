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

        // 3) 视频补全：needsDetail=true 的媒体进帖子详情页补直链；最多补前 5 个避免流量爆炸
        val toFix = list.mapNotNull { p ->
            p.media.firstOrNull { it.isVideo && (it.url.isBlank() || it.needsDetail) }
                ?.let { p to (it.postId ?: p.id) }
        }.take(5)
        if (toFix.isNotEmpty()) {
            val fixedMap = toFix.map { (p, pid) ->
                async(Dispatchers.IO) { p.id to fetchPostMedia(pid) }
            }.awaitAll().toMap()
            list = list.map { p ->
                fixedMap[p.id]?.let { extra ->
                    if (extra.isNotEmpty()) {
                        val merged = p.media.filter { !it.isVideo || it.url.isNotBlank() }.toMutableList()
                        merged.addAll(extra)
                        p.copy(media = merged)
                    } else p
                } ?: p
            }
        }

        FetchResult(posts = list, via = via, apiError = apiErr, parseError = parseErr)
    }

    /** 进入帖子详情拿视频直链 */
    suspend fun fetchPostMedia(postId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val json = runCatching { Net.api.postHead(postId) }
            .mapCatching { YubaParser.extractMediaFromDetail(it, postId) }
            .getOrDefault(emptyList())
        if (json.isNotEmpty()) return@withContext json
        // 兜底：WebView 抽 DOM
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                WebViewFetcher.fetchPostDetail(appCtx, postId)
            }
        }.getOrDefault(emptyList())
    }
}
