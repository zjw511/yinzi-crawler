package com.yinzi.crawler.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 媒体类型 */
enum class MediaType { IMAGE, VIDEO }

/**
 * 单个媒体资源（图片或视频）
 * @param type 类型
 * @param url  资源直链（图片为图片 URL，视频为 mp4 直链）
 * @param thumbUrl 缩略图，视频封面用
 * @param postId 所属帖子 id
 */
data class MediaItem(
    val type: MediaType,
    val url: String,
    val thumbUrl: String? = null,
    val postId: String? = null
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO
}

/**
 * 鱼吧帖子
 * @param id 帖子 id
 * @param author 作者昵称
 * @param avatar 头像 URL
 * @param time 发布时间文本
 * @param content 正文（纯文本）
 * @param media 帖子里的图片和视频
 */
data class Post(
    val id: String,
    val author: String,
    val avatar: String?,
    val time: String,
    val content: String,
    val media: List<MediaItem>
) {
    val imageCount: Int get() = media.count { !it.isVideo }
    val videoCount: Int get() = media.count { it.isVideo }
}

/** ---------- 鱼吧 JSON 接口响应模型 ----------
 *  斗鱼接口字段经常变动，这里用宽松的 JsonElement 兜底，
 *  解析逻辑放在 YubaParser 里做容错。 */
@Serializable
data class YubaListResp(
    @SerialName("error") val error: Int = 0,
    @SerialName("msg") val msg: String? = null,
    @SerialName("data") val data: JsonElement? = null
)

@Serializable
data class YubaPostDetailResp(
    @SerialName("error") val error: Int = 0,
    @SerialName("msg") val msg: String? = null,
    @SerialName("data") val data: JsonElement? = null
)
