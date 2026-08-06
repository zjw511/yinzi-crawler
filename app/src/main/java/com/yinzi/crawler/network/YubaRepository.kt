package com.yinzi.crawler.network

import android.content.Context
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鱼吧数据仓库（v1.1 改走 WebViewFetcher）
 *
 * 抓取策略：
 *  1. 列表页：用 WebView 渲染鱼吧页面，渲染完用 evaluateJavascript 抽 DOM 里的帖子卡片和媒体
 *  2. 详情补全：如果某帖子内容里明显有视频却没抓到直链，再抓一次详情页
 *  3. 匿名 / 登录态 共用一套逻辑——WebViewFetcher 里的 CookieManager 已经把 Prefs.cookie 自动注入
 *
 * 下载媒体（图片、mp4）仍走 OkHttp（DownloadManager 不变）。
 */
object YubaRepository {

    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 拉取一页帖子。page 从 0（第一页），映射到鱼吧前端的 page=1-based */
    suspend fun fetchPosts(
        groupId: String = Prefs.groupId,
        page: Int = 0
    ): List<Post> = withContext(Dispatchers.Main.immediate) {
        val list = runCatching {
            WebViewFetcher.fetchPosts(appCtx, groupId, page = page + 1)
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return@withContext emptyList()
        // 补全：空媒体但疑似视频的帖子，用详情页再抓一次
        list.map { p ->
            if (p.content.contains("视频") && p.media.isEmpty() && !p.id.startsWith("dom_")) {
                val extra = runCatching { fetchPostMedia(p.id) }.getOrDefault(emptyList())
                if (extra.isNotEmpty()) p.copy(media = extra) else p
            } else {
                p
            }
        }
    }

    /** 抓某个帖子详情里的所有媒体（用于「点击卡片内容里的原图/视频） */
    suspend fun fetchPostMedia(postId: String): List<MediaItem> = runCatching {
        withContext(Dispatchers.Main.immediate) {
            WebViewFetcher.fetchPostDetail(appCtx, postId)
        }
    }.getOrDefault(emptyList())
}
