package com.yinzi.crawler.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 鱼吧手机版 (yubam.douyu.com) 真实 API
 * v1.1.1 起使用，此前使用的 yuba.douyu.com/wbapi/web/group/561/post 已下线返回 404。
 *
 * 匿名模式/登录模式均使用同一路径，唯一区别是 Header 里有没有 Cookie。
 */
interface YubaApi {

    /**
     * 帖子列表接口（已实测 2025/08 可用，匿名 1 页返回 33 条，含 imglist 图片）
     *   GET https://yubam.douyu.com/wbapi/web/group/postlist?group_id=561&page=1&limit=20
     *
     * 返回结构（节选）：
     * {
     *   "status_code": 200,  "total": 369201,  "page": 1,
     *   "data": [ { post_id, title, describe, nickname, avatar, created_at_std,
     *               imglist: [{url, thumb_url, size: {w,h}}],
     *               video, audio, is_anchor_post, is_recom_top, ... } ... ]
     * }
     */
    @GET("/wbapi/web/group/postlist")
    suspend fun postList(
        @Query("group_id") groupId: String,
        @Query("page") page: Int,              // 1-based
        @Query("limit") limit: Int = 30,
        @Query("type") type: Int = 0           // 0=全部；1=精华；2=图；3=视频
    ): String

    /** 帖子详情（用于补充列表里没展开的视频/原图）： GET https://yubam.douyu.com/wbapi/web/post/head/{post_id} */
    @GET("/wbapi/web/post/head/{postId}")
    suspend fun postHead(@Path("postId") postId: String): String

    /** 鱼吧头部信息：名称、头像、banner、粉丝数等，用于设置里的 group_id 有效性检查 */
    @GET("/wbapi/web/group/head")
    suspend fun groupHead(@Query("group_id") groupId: String): String
}
