package com.yinzi.crawler.network

import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.model.Post
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 斗鱼鱼吧解析器：
 * - 优先解析官方 wb-api 返回的 JSON
 * - 兜底解析 HTML 页面里的 Next.js `__NEXT_DATA__` 内嵌 JSON
 * - 最后兜底直接走 DOM 提取 img/video
 *
 * 字段名斗鱼经常变动，这里用「递归遍历 JSON 树 + 关键字匹配」做容错，
 * 只要值是图片/视频 URL 就抽出来，避免硬编码字段。
 */
object YubaParser {

    // ---------- URL 模式 ----------
    private val IMAGE_HOSTS = listOf(
        "aka.doubaocdn.com", "apic.douyucdn.cn", "cstatic.douyucdn.cn",
        "shark2.douyucdn.cn", "rpic.douyucdn.cn", "staticlive.douyutv.com",
        "sts.douyucdn.cn", "vimg.douyucdn.cn"
    )
    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    private val VIDEO_EXT = listOf(".mp4", ".flv", ".m4v")

    // JSON 里图片常见 key
    private val IMG_KEYS = setOf(
        "pic", "image", "images", "cover", "thumb", "thumbnail",
        "img", "src", "url", "avatar", "pic_url", "cover_url"
    )
    // JSON 里视频常见 key
    private val VIDEO_KEYS = setOf(
        "video_url", "video_url_alt", "play_url", "video_src",
        "video", "videos", "video_list", "mp4_url", "high", "normal", "real_url"
    )
    // 帖子常见 key
    private val POST_ID_KEYS = setOf("post_id", "id", "feed_id", "postId")
    private val AUTHOR_KEYS = setOf("nick_name", "nickname", "author", "uname", "name")
    private val AVATAR_KEYS = setOf("avatar", "avatar_mid", "user_avatar")
    private val TIME_KEYS = setOf("create_time", "time", "create_time_text", "pub_time", "date")
    private val CONTENT_KEYS = setOf("content", "text", "desc", "description", "title")

    // ============== 入口：从 wb-api JSON 解析帖子列表 ==============
    fun parseListFromApi(jsonStr: String): List<Post> {
        val root = runCatching { Json.parseToJsonElement(jsonStr) }.getOrNull() ?: return emptyList()
        val data = root.jsonObject["data"] ?: return emptyList()
        // 找到第一个「看起来是帖子数组」的 JsonArray
        val postsArray = findFirstPostArray(data) ?: return emptyList()
        return postsArray.mapNotNull { it.toPost() }
    }

    /** 递归找第一个元素是帖子对象（含 id + 至少一个媒体）的数组 */
    private fun findFirstPostArray(el: JsonElement): JsonArray? {
        if (el is JsonArray) {
            if (el.isNotEmpty() && el.first() is JsonObject) {
                val obj = el.first().jsonObject
                val hasId = obj.keys.any { it in POST_ID_KEYS }
                val hasMedia = obj.keys.any { it in IMG_KEYS || it in VIDEO_KEYS }
                if (hasId && (hasMedia || obj.keys.any { it in CONTENT_KEYS })) {
                    return el
                }
            }
            for (child in el) {
                findFirstPostArray(child)?.let { return it }
            }
        } else if (el is JsonObject) {
            for ((_, v) in el) {
                findFirstPostArray(v)?.let { return it }
            }
        }
        return null
    }

    private fun JsonElement.toPost(): Post? {
        val obj = (this as? JsonObject) ?: return null
        val id = obj.firstByKeys(POST_ID_KEYS)?.toStr().orEmpty()
        if (id.isEmpty()) return null
        val media = collectMediaFromNode(this)
        return Post(
            id = id,
            author = obj.firstByKeys(AUTHOR_KEYS)?.toStr().orEmpty(),
            avatar = obj.firstByKeys(AVATAR_KEYS)?.toStr(),
            time = obj.firstByKeys(TIME_KEYS)?.toStr().orEmpty(),
            content = obj.firstByKeys(CONTENT_KEYS)?.toStr().orEmpty(),
            media = media
        )
    }

    // ============== 兜底：从 HTML 页面解析 ==============
    fun parseListFromHtml(html: String): List<Post> {
        // 1) 优先抽 __NEXT_DATA__ 内嵌 JSON
        val nextData = extractNextData(html)
        if (nextData != null) {
            val list = parseListFromApi(nextData.toString())
            if (list.isNotEmpty()) return list
        }
        // 2) 直接 DOM 兜底
        return parseListFromDom(html)
    }

    private fun extractNextData(html: String): JsonElement? {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#__NEXT_DATA__") ?: return null
        val raw = script.data().trim()
        return runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun parseListFromDom(html: String): List<Post> {
        val doc = Jsoup.parse(html)
        // 鱼吧帖子列表在 DOM 里没有稳定 class，按 a[href*=/post/] 或 feed 容器粗略抓
        val posts = mutableListOf<Post>()
        // 抓所有图片/视频，聚合成一个伪帖子
        val imgs = doc.select("img[src]").map { it.absUrl("src") }
            .filter { isImageUrl(it) }
        val vids = doc.select("video[src], video > source[src]").map { it.absUrl("src") }
            .filter { isVideoUrl(it) }
        if (imgs.isEmpty() && vids.isEmpty()) return emptyList()
        val media = imgs.map { MediaItem(MediaType.IMAGE, it) } +
            vids.map { MediaItem(MediaType.VIDEO, it) }
        posts += Post(
            id = "dom",
            author = doc.title().ifEmpty { "寅子鱼吧" },
            avatar = null,
            time = "",
            content = doc.title(),
            media = media
        )
        return posts
    }

    // ============== 帖子详情：抽视频直链 ==============
    fun extractMediaFromDetail(jsonOrHtml: String): List<MediaItem> {
        // 先按 JSON 试
        Json.parseToJsonElement(jsonOrHtml).let { el ->
            val media = collectMediaFromNode(el)
            if (media.isNotEmpty()) return media
        }
        // 再按 HTML 试（含 __NEXT_DATA__）
        extractNextData(jsonOrHtml)?.let { next ->
            val media = collectMediaFromNode(next)
            if (media.isNotEmpty()) return media
        }
        // DOM
        val doc = Jsoup.parse(jsonOrHtml)
        val vids = doc.select("video[src], video > source[src]").map { it.absUrl("src") }
            .filter { isVideoUrl(it) }
        val covers = doc.select("video[poster]").map { it.absUrl("poster") }
        return vids.mapIndexed { i, u ->
            MediaItem(MediaType.VIDEO, u, thumbUrl = covers.getOrNull(i))
        }
    }

    // ============== 递归抽媒体 ==============
    private fun collectMediaFromNode(el: JsonElement): List<MediaItem> {
        val out = LinkedHashMap<String, MediaItem>() // url -> media，去重
        walk(el, out)
        return out.values.toList()
    }

    private fun walk(el: JsonElement, out: MutableMap<String, MediaItem>) {
        when (el) {
            is JsonObject -> {
                // 先按 key 语义抽（更准）
                for ((k, v) in el) {
                    when {
                        k in VIDEO_KEYS -> collectVideoValue(v, out)
                        k in IMG_KEYS -> collectImageValue(v, out)
                    }
                }
                // 再递归子节点
                for ((_, v) in el) walk(v, out)
            }
            is JsonArray -> el.forEach { walk(it, out) }
            else -> {}
        }
    }

    private fun collectVideoValue(v: JsonElement, out: MutableMap<String, MediaItem>) {
        when (v) {
            is JsonPrimitive -> {
                val s = v.toStr()
                if (isVideoUrl(s)) out.putIfAbsent(s, MediaItem(MediaType.VIDEO, s))
            }
            is JsonObject -> {
                // 形如 {"url":"...mp4","cover":"..."}
                val url = v.firstByKeys(setOf("url", "video_url", "play_url", "src"))?.toStr()
                val cover = v.firstByKeys(setOf("cover", "thumb", "poster"))?.toStr()
                if (url != null && isVideoUrl(url)) {
                    out.putIfAbsent(url, MediaItem(MediaType.VIDEO, url, thumbUrl = cover))
                } else {
                    // 继续递归
                    walk(v, out)
                }
            }
            is JsonArray -> v.forEach { collectVideoValue(it, out) }
            else -> {}
        }
    }

    private fun collectImageValue(v: JsonElement, out: MutableMap<String, MediaItem>) {
        when (v) {
            is JsonPrimitive -> {
                val s = v.toStr()
                if (isImageUrl(s)) out.putIfAbsent(s, MediaItem(MediaType.IMAGE, s))
            }
            is JsonArray -> v.forEach { collectImageValue(it, out) }
            is JsonObject -> {
                val s = v.firstByKeys(setOf("url", "src", "big", "origin", "pic"))?.toStr()
                if (s != null && isImageUrl(s)) out.putIfAbsent(s, MediaItem(MediaType.IMAGE, s))
                else walk(v, out)
            }
            else -> {}
        }
    }

    // ============== 工具 ==============
    private fun JsonObject.firstByKeys(keys: Set<String>): JsonElement? =
        keys.firstOrNull { containsKey(it) }?.let { this[it] }

    private fun JsonElement.toStr(): String =
        (this as? JsonPrimitive)?.content ?: this.toString()

    private fun isImageUrl(s: String): Boolean {
        if (s.isBlank() || !s.startsWith("http")) return false
        val lower = s.lowercase()
        if (IMAGE_EXT.any { lower.contains(it) }) return true
        // 短链图：aka.doubaocdn.com/s/xxxxxx
        if (IMAGE_HOSTS.any { lower.contains(it) }) {
            // 排除明显是 mp4 的
            if (VIDEO_EXT.any { lower.contains(it) }) return false
            return true
        }
        return false
    }

    private fun isVideoUrl(s: String): Boolean {
        if (s.isBlank() || !s.startsWith("http")) return false
        val lower = s.lowercase()
        return VIDEO_EXT.any { lower.contains(it) }
    }
}
