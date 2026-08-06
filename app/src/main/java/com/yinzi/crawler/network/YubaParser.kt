package com.yinzi.crawler.network

import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.model.Post
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * 鱼吧手机版 (yubam.douyu.com) 解析器（v1.1.1 重写）
 *
 * 已知稳定 JSON 结构（/wbapi/web/group/postlist）：
 * {
 *   "status_code":200, "page":1, "total":369201,
 *   "data": [
 *     { post_id, title, describe, nickname, avatar, created_at_std,
 *       imglist:[{url, thumb_url, size:{w,h}}],
 *       video (视频对象/null) },
 *     ...
 *   ]
 * }
 *  同时保留旧的「递归遍历 JSON 树 + 关键字匹配」兜底。
 */
object YubaParser {

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    private val VIDEO_EXT = listOf(".mp4", ".flv", ".m4v", ".webm", ".m3u8")

    private val POST_ID_KEYS = setOf("post_id", "id", "feed_id", "postId")
    private val AUTHOR_KEYS = setOf("nick_name", "nickname", "author", "uname", "name")
    private val AVATAR_KEYS = setOf("avatar", "avatar_mid", "user_avatar", "avatar_url")
    private val TIME_KEYS = setOf("created_at_std", "create_time", "time", "pub_time", "date", "last_reply_time")
    // 鱼吧里正文用 title + describe，title 是标题、describe 是正文
    private val CONTENT_KEYS = setOf("describe", "desc", "content", "text", "description", "title")
    private val TITLE_KEYS = setOf("title", "post_title")

    // ============== 入口 1：从 JSON 解析帖子列表 ==============
    fun parseListFromApi(jsonStr: String): List<Post> {
        val root = runCatching { Json.parseToJsonElement(jsonStr) }.getOrNull() ?: return emptyList()
        val obj = (root as? JsonObject) ?: return emptyList()
        val arr = when {
            obj["data"] is JsonArray -> obj["data"] as JsonArray
            else -> findFirstPostArray(root) ?: return emptyList()
        }
        return arr.mapNotNull { toPost(it) }
    }

    private fun findFirstPostArray(el: JsonElement): JsonArray? {
        if (el is JsonArray) {
            if (el.isNotEmpty() && el.first() is JsonObject) {
                val keys = (el.first() as JsonObject).keys
                if (keys.any { it in POST_ID_KEYS }
                    && keys.any { it in CONTENT_KEYS || "title" in it }) return el
            }
            for (c in el) findFirstPostArray(c)?.let { return it }
        } else if (el is JsonObject) {
            for ((_, v) in el) findFirstPostArray(v)?.let { return it }
        }
        return null
    }

    private fun toPost(el: JsonElement): Post? {
        val o = (el as? JsonObject) ?: return null
        val id = o.firstByKeys(POST_ID_KEYS)?.toStr().orEmpty()
        if (id.isEmpty()) return null

        val media = ArrayList<MediaItem>()

        // 1) 精准字段：imglist
        (o["imglist"] as? JsonArray)?.forEach { img ->
            val io = img as? JsonObject ?: return@forEach
            val url = io["url"]?.toStr().orEmpty()
            val thumb = io["thumb_url"]?.toStr().orEmpty()
            if (url.isNotEmpty() && isImageUrl(url)) {
                media += MediaItem(
                    type = MediaType.IMAGE,
                    url = url,
                    thumbUrl = thumb.ifEmpty { null },
                    postId = id
                )
            }
        }

        // 2) 精准字段：video 对象
        (o["video"] as? JsonObject)?.let { vo ->
            val vurl = vo.firstByKeys(setOf("url", "video_url", "play_url", "src", "video_path"))?.toStr()
            val poster = vo.firstByKeys(setOf("cover", "thumb", "poster", "cover_url", "snapshot"))?.toStr()
            if (vurl != null && isVideoUrl(vurl)) {
                media += MediaItem(MediaType.VIDEO, url = vurl, thumbUrl = poster, postId = id)
            }
        }

        // 3) 兜底：video 可能是字符串，或 extension_type 暗示视频
        if (media.none { it.type == MediaType.VIDEO }) {
            val vstr = o["video"]?.toStr().orEmpty()
            if (vstr.isNotEmpty() && isVideoUrl(vstr)) {
                media += MediaItem(MediaType.VIDEO, url = vstr, postId = id)
            }
            val extype = (o["extension_type"] as? JsonPrimitive)?.intOrNull ?: 0
            // extension_type=8 = 视频帖子，video_url 可能还得走详情页补
            if (extype == 8) {
                // placeholder，Repository 后面会用 fetchPostMedia 补
                if (o["cover_url"] != null) {
                    media += MediaItem(
                        MediaType.VIDEO, url = "", thumbUrl = o["cover_url"]?.toStr(), postId = id,
                        needsDetail = true
                    )
                }
            }
        }

        // 4) 再兜底：递归树里抓没被硬编码的字段
        val extra = collectMediaFromNode(el, id)
        for (e in extra) if (e.url !in media.map { it.url }.toSet()) media += e

        // 合并 title + describe
        val title = o.firstByKeys(TITLE_KEYS)?.toStr().orEmpty()
        val desc = (o["describe"] ?: o["desc"] ?: o["content"])?.toStr().orEmpty()
        val content = listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n")

        return Post(
            id = id,
            author = o.firstByKeys(AUTHOR_KEYS)?.toStr()?.ifBlank { "鱼吧用户" } ?: "鱼吧用户",
            avatar = o.firstByKeys(AVATAR_KEYS)?.toStr(),
            time = o.firstByKeys(TIME_KEYS)?.toStr().orEmpty(),
            content = content,
            media = media
        )
    }

    // ============== 入口 2：HTML 兜底（WebView 渲染失败时用） ==============
    fun parseListFromHtml(html: String): List<Post> {
        val script = runCatching {
            Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()
        }.getOrNull()
        if (!script.isNullOrBlank()) {
            runCatching { parseListFromApi(script) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Post>()
        val imgs = doc.select("img[src], img[data-src]").mapNotNull { e ->
            (e.attr("abs:src").ifBlank { e.attr("abs:data-src") })
                .takeIf { isImageUrl(it) }
        }.distinct()
        val vids = doc.select("video[src], video > source[src]").mapNotNull { e ->
            e.attr("abs:src").takeIf { isVideoUrl(it) }
        }.distinct()
        if (imgs.isEmpty() && vids.isEmpty()) return emptyList()
        val media = imgs.map { MediaItem(MediaType.IMAGE, it) } +
            vids.map { MediaItem(MediaType.VIDEO, it) }
        out += Post(
            id = "dom-fallback", author = doc.title().ifBlank { "寅子鱼吧" },
            avatar = null, time = "", content = doc.title(), media = media
        )
        return out
    }

    // ============== 入口 3：帖子详情抽媒体 ==============
    fun extractMediaFromDetail(jsonOrHtml: String, postId: String = ""): List<MediaItem> {
        runCatching { Json.parseToJsonElement(jsonOrHtml) }.getOrNull()?.let {
            val m = collectMediaFromNode(it, postId)
            if (m.isNotEmpty()) return m
        }
        return parseListFromHtml(jsonOrHtml).flatMap { it.media }
    }

    // ============== 树递归抽媒体兜底 ==============
    private fun collectMediaFromNode(el: JsonElement, postId: String): List<MediaItem> {
        val out = LinkedHashMap<String, MediaItem>()
        walk(el, out, postId)
        return out.values.toList()
    }

    private fun walk(el: JsonElement, out: MutableMap<String, MediaItem>, postId: String) {
        when (el) {
            is JsonObject -> {
                val keys = el.keys
                for (k in keys) {
                    val v = el[k] ?: continue
                    if ("video" in k.lowercase()) collectVideo(v, out, postId)
                    else if (setOf("imglist","pictures","images","pics","photos","screenshots").contains(k.lowercase())) {
                        collectImage(v, out, postId)
                    } else if (setOf("img","image","src","cover","pic","picture","thumb","thumbnail").contains(k.lowercase())) {
                        collectImage(v, out, postId)
                    }
                }
                for ((_, v) in el) walk(v, out, postId)
            }
            is JsonArray -> el.forEach { walk(it, out, postId) }
            else -> {}
        }
    }

    private fun collectImage(v: JsonElement, out: MutableMap<String, MediaItem>, postId: String) {
        when (v) {
            is JsonPrimitive -> {
                val s = v.toStr()
                if (isImageUrl(s)) out.putIfAbsent(s, MediaItem(MediaType.IMAGE, s, postId = postId))
            }
            is JsonObject -> {
                val url = v["url"]?.toStr() ?: v["src"]?.toStr() ?: v["big"]?.toStr()
                val thumb = v["thumb_url"]?.toStr() ?: v["thumb"]?.toStr() ?: v["thumbnail"]?.toStr()
                if (url != null && isImageUrl(url)) {
                    out.putIfAbsent(url, MediaItem(MediaType.IMAGE, url, thumbUrl = thumb, postId = postId))
                } else walk(v, out, postId)
            }
            is JsonArray -> v.forEach { collectImage(it, out, postId) }
            else -> {}
        }
    }

    private fun collectVideo(v: JsonElement, out: MutableMap<String, MediaItem>, postId: String) {
        when (v) {
            is JsonPrimitive -> {
                val s = v.toStr()
                if (isVideoUrl(s)) out.putIfAbsent(s, MediaItem(MediaType.VIDEO, s, postId = postId))
            }
            is JsonObject -> {
                val url = v.firstByKeys(setOf("url","video_url","play_url","src","high","normal","mp4_url","real_url"))?.toStr()
                val cover = v.firstByKeys(setOf("cover","thumb","poster","snapshot","cover_url"))?.toStr()
                if (url != null && isVideoUrl(url)) {
                    out.putIfAbsent(url, MediaItem(MediaType.VIDEO, url, thumbUrl = cover, postId = postId))
                } else walk(v, out, postId)
            }
            is JsonArray -> v.forEach { collectVideo(it, out, postId) }
            else -> {}
        }
    }

    // ============== 工具 ==============
    private fun JsonObject.firstByKeys(keys: Set<String>): JsonElement? =
        keys.firstNotNullOfOrNull { this[it]?.takeUnless { e -> e is JsonNull } }

    private fun JsonElement.toStr(): String =
        (this as? JsonPrimitive)?.content ?: this.toString().trim('"')

    private fun isImageUrl(s: String): Boolean {
        if (s.isBlank() || !s.startsWith("http")) return false
        val l = s.lowercase()
        if (l.contains(".mp4")) return false
        if (IMAGE_EXT.any { l.contains(it) }) return true
        // 斗鱼 CDN 的图片链接即使没有扩展名，只要域名对也大概率是图
        if (listOf("douyucdn.cn","doubaocdn.com","douyu.com").any { it in l } &&
            listOf("avatar","head","emoji","favicon","icon","logo").none { it in l }) {
            // 只要不是明确的头像/emoji，也没扩展名的话，谨慎起见先不收录
            return l.contains(".jpg") || l.contains(".png") || l.contains(".webp") || l.contains(".gif")
        }
        return false
    }

    private fun isVideoUrl(s: String): Boolean {
        if (s.isBlank() || !s.startsWith("http")) return false
        val l = s.lowercase()
        return VIDEO_EXT.any { l.contains(it) }
    }
}
