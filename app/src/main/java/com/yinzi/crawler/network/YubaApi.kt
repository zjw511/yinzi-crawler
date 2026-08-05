package com.yinzi.crawler.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YubaApi {

    /**
     * 鱼吧帖子列表（JSON 接口）
     * 实测：https://yuba.douyu.com/wb-api/group/{group_id}/post?offset=0&limit=20
     * 若接口字段或路径变动，YubaRepository 会自动兜底走 HTML 解析。
     */
    @GET("wb-api/group/{groupId}/post")
    suspend fun groupPosts(
        @Path("groupId") groupId: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20
    ): String

    /** 帖子详情，包含视频直链 */
    @GET("wb-api/post/{postId}")
    suspend fun postDetail(@Path("postId") postId: String): String

    /** 鱼吧帖子列表 HTML 页面（兜底用） */
    @GET("discussion/{groupId}/posts")
    suspend fun groupPostsHtml(@Path("groupId") groupId: String): String

    /** 帖子详情 HTML 页面（兜底用，用于解析视频直链） */
    @GET("post/{postId}")
    suspend fun postDetailHtml(@Path("postId") postId: String): String
}
