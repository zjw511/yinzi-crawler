package com.yinzi.crawler.network

import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.util.Prefs

/**
 * 鱼吧数据仓库：
 *  1. 优先调 wb-api JSON 接口
 *  2. 失败/空数据时回退到 HTML 页面解析（含 Next.js __NEXT_DATA__）
 *  3. 对只有缩略图但没拿到 mp4 直链的视频，再请求帖子详情补全
 */
object YubaRepository {

    private val api: YubaApi get() = Net.api

    /** 拉取一页帖子 */
    suspend fun fetchPosts(groupId: String = Prefs.groupId, page: Int = 0): List<Post> {
        val offset = page * Prefs.DEFAULT_PAGE_LIMIT
        // 1) JSON 接口
        val posts = runCatching {
            val body = api.groupPosts(groupId, offset, Prefs.DEFAULT_PAGE_LIMIT)
            YubaParser.parseListFromApi(body)
        }.getOrDefault(emptyList())
        if (posts.isNotEmpty()) return enrich(posts)
        // 2) HTML 兜底
        val html = runCatching { api.groupPostsHtml(groupId) }.getOrNull()
            ?: return emptyList()
        return YubaParser.parseListFromHtml(html)
    }

    /** 对含视频但缺直链的帖子，拉详情补全 */
    private suspend fun enrich(posts: List<Post>): List<Post> {
        return posts.map { post ->
            val hasVideoMissingUrl = post.media.any { it.isVideo }
            // 解析器一般已经能从列表里拿到 mp4，这里只在媒体为空时再拉详情
            if (post.media.isEmpty() && post.id != "dom") {
                val extra = runCatching {
                    YubaParser.extractMediaFromDetail(api.postDetail(post.id))
                }.getOrDefault(emptyList())
                if (extra.isNotEmpty()) post.copy(media = extra) else post
            } else {
                post
            }
        }
    }

    /** 单独抓某个帖子详情里的所有媒体（点进帖子时用） */
    suspend fun fetchPostMedia(postId: String): List<MediaItem> {
        val json = runCatching { api.postDetail(postId) }.getOrNull()
            ?: return emptyList()
        val media = YubaParser.extractMediaFromDetail(json)
        if (media.isNotEmpty()) return media
        // 兜底 HTML
        val html = runCatching { api.postDetailHtml(postId) }.getOrNull() ?: return emptyList()
        return YubaParser.extractMediaFromDetail(html)
    }
}
