package com.yinzi.crawler.network

import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.util.DebugLog
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

    private const val TAG = "Parser"

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
        DebugLog.d(TAG, "📋 parseListFromApi：输入 ${jsonStr.length} 字符")
        val root = runCatching { Json.parseToJsonElement(jsonStr) }.getOrNull()
        if (root == null) {
            DebugLog.e(TAG, "❌ JSON 解析失败，返回空列表")
            return emptyList()
        }
        val obj = (root as? JsonObject) ?: run {
            DebugLog.e(TAG, "❌ root 不是 JsonObject，实际类型=${root::class.simpleName}")
            return emptyList()
        }
        // 打顶层 key 方便调试
        DebugLog.d(TAG, "   顶层keys：${obj.keys.take(15).joinToString()}")
        // 打印 status_code / error_msg 之类
        val sc = obj["status_code"]?.toStr() ?: obj["code"]?.toStr() ?: "(无)"
        val err = obj["msg"]?.toStr() ?: obj["message"]?.toStr() ?: obj["error_msg"]?.toStr() ?: "无"
        DebugLog.d(TAG, "   status_code=$sc, msg=$err")

        val arr = when {
            obj["data"] is JsonArray -> obj["data"] as JsonArray
            else -> findFirstPostArray(root) ?: run {
                DebugLog.w(TAG, "⚠️  没找到帖子数组，返回空")
                return emptyList()
            }
        }
        DebugLog.d(TAG, "   找到帖子数组，长度=${arr.size}")
        val result = arr.mapNotNull { toPost(it) }
        DebugLog.d(TAG, "   解析成功 ${result.size} 条（${arr.size - result.size} 条过滤掉了）")
        return result
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
        if (id.isEmpty()) {
            DebugLog.w(TAG, "   ⚠️  帖子无id，跳过。keys=${o.keys.take(10)}")
            return null
        }

        val media = ArrayList<MediaItem>()

        // 1) 精准字段：imglist
        val imglist = (o["imglist"] as? JsonArray)
        DebugLog.d(TAG, "     postId=$id: imglist.size=${imglist?.size ?: 0}")
        imglist?.forEach { img ->
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
        // 只有列表 API 返回了 video 字段（不为 null）时才标记为视频帖
        // extension_type=8 但 video=null 实际上是图片帖或外链帖
        val videoField = o["video"]
        val hasVideoField = videoField != null && videoField !is JsonNull
        DebugLog.d(TAG, "     postId=$id: hasVideoField=$hasVideoField, videoType=${videoField?.let { it::class.simpleName }}")

        if (hasVideoField) {
            when (videoField) {
                is JsonObject -> {
                    val vurl = videoField.firstByKeys(setOf("url", "video_url", "play_url", "src", "video_path"))?.toStr()
                    val poster = videoField.firstByKeys(setOf("cover", "thumb", "poster", "cover_url", "snapshot"))?.toStr()
                    DebugLog.d(TAG, "     postId=$id: video.url=$vurl, cover=$poster")
                    if (vurl != null && isVideoUrl(vurl)) {
                        media += MediaItem(MediaType.VIDEO, url = vurl, thumbUrl = poster, postId = id)
                    } else {
                        // video 对象存在但无直链（斗鱼视频帖常见，只有 hash_id），
                        // 需要进详情页解析 data-playurl 补全
                        val cover = poster?.ifBlank {
                            (o["imglist"] as? JsonArray)?.firstOrNull()?.let {
                                (it as? JsonObject)?.get("url")?.toStr()
                            }.orEmpty()
                        }
                        media += MediaItem(
                            MediaType.VIDEO, url = "",
                            thumbUrl = cover, postId = id, needsDetail = true
                        )
                        DebugLog.d(TAG, "     postId=$id: video对象无直链，插入占位(needsDetail=true)，后续补全")
                    }
                }
                is JsonPrimitive -> {
                    val vstr = videoField.toStr()
                    if (vstr.isNotEmpty() && isVideoUrl(vstr)) {
                        media += MediaItem(MediaType.VIDEO, url = vstr, postId = id)
                    } else if (vstr.isNotEmpty()) {
                        media += MediaItem(
                            MediaType.VIDEO, url = "",
                            thumbUrl = null, postId = id, needsDetail = true
                        )
                    }
                }
                else -> {
                    DebugLog.w(TAG, "     postId=$id: video字段类型异常(${videoField!!::class.simpleName})，跳过视频创建")
                }
            }
        } else {
            // 没有 video 字段，用 URL 字符串方式兜底
            val vstr = videoField?.toStr().orEmpty()
            if (vstr.isNotEmpty() && isVideoUrl(vstr)) {
                media += MediaItem(MediaType.VIDEO, url = vstr, postId = id)
            }
        }

        // 4) 再兜底：递归树里抓没被硬编码的字段
        val extra = collectMediaFromNode(el, id)
        for (e in extra) if (e.url !in media.map { it.url }.toSet()) media += e
        if (extra.isNotEmpty()) DebugLog.d(TAG, "     postId=$id: 递归树额外抓了 ${extra.size} 个媒体")

        // 合并 title + describe
        val title = o.firstByKeys(TITLE_KEYS)?.toStr().orEmpty()
        val desc = (o["describe"] ?: o["desc"] ?: o["content"])?.toStr().orEmpty()
        val content = listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n")

        DebugLog.d(TAG, "     postId=$id ✅：最终媒体=${media.size}(图${media.count{!it.isVideo}} 视${media.count{it.isVideo}})")
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
        DebugLog.d(TAG, "🌐 parseListFromHtml：html.length=${html.length}")
        val script = runCatching {
            Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()
        }.getOrNull()
        if (!script.isNullOrBlank()) {
            DebugLog.d(TAG, "   找到 __NEXT_DATA__ script，长度=${script.length}，尝试走 JSON 解析")
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
        DebugLog.d(TAG, "   HTML DOM 抽取：imgs=${imgs.size}, vids=${vids.size}")
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

    /** 判断帖子是否为视频帖：
     *  - video 字段不为 null（最可靠）
     *  - 或 content 含 data-playurl（斗鱼视频分享页链接）
     *  - 或 content 含 [video] BBCode 标签
     *  注意：extension_type=8 不再作为判断依据（大量图片帖也是 ext_type=8）
     */
    fun isVideoPost(jsonOrHtml: String): Boolean {
        runCatching { Json.parseToJsonElement(jsonOrHtml) }.getOrNull()?.let {
            val root = it as? JsonObject ?: return@let
            val postObj = (root["data"] as? JsonObject) ?: root
            // 1) video 字段不为 null
            val videoField = postObj["video"]
            if (videoField != null && videoField !is JsonNull) return true
            // 2) content/describe 里有 data-playurl 或 [video] 标签
            val content = postObj["content"]?.toStr() ?: postObj["describe"]?.toStr() ?: ""
            if (content.contains("data-playurl", ignoreCase = true)) return true
            if (content.contains("[video", ignoreCase = true)) return true
        }
        return false
    }

    fun extractMediaFromDetail(jsonOrHtml: String, postId: String = ""): List<MediaItem> {
        DebugLog.d(TAG, "🔬 extractMediaFromDetail：postId=$postId, 输入${jsonOrHtml.length}字符")
        runCatching { Json.parseToJsonElement(jsonOrHtml) }.getOrNull()?.let {
            val root = it as? JsonObject ?: return@let
            // 详情接口的 data 是帖子对象本身（不是数组）
            val postObj = (root["data"] as? JsonObject) ?: root
            val sc = root["status_code"]?.toStr() ?: root["code"]?.toStr() ?: "(无)"
            val err = root["msg"]?.toStr() ?: root["message"]?.toStr() ?: "无"
            DebugLog.d(TAG, "   详情 status_code=$sc, msg=$err, data.keys=${postObj.keys.take(20)}")
            val media = ArrayList<MediaItem>()

            // 1) video 字段：可能是对象 {url, cover} 或字符串
            val videoEl = postObj["video"]
            if (videoEl != null && videoEl !is JsonNull) {
                DebugLog.d(TAG, "   找到 video 字段：type=${videoEl::class.simpleName}")
                when (videoEl) {
                    is JsonObject -> {
                        val vurl = videoEl.firstByKeys(setOf("url","video_url","play_url","src","high","normal","mp4_url","real_url","video_path"))?.toStr()
                        val cover = videoEl.firstByKeys(setOf("cover","thumb","poster","snapshot","cover_url"))?.toStr()
                        DebugLog.d(TAG, "     video.url=$vurl, cover=$cover")
                        if (!vurl.isNullOrEmpty() && isVideoUrl(vurl)) {
                            media += MediaItem(MediaType.VIDEO, url = vurl, thumbUrl = cover, postId = postId)
                        }
                    }
                    is JsonPrimitive -> {
                        val vs = videoEl.toStr()
                        if (vs.isNotEmpty() && isVideoUrl(vs)) {
                            media += MediaItem(MediaType.VIDEO, url = vs, postId = postId)
                        } else if (vs.isNotEmpty()) {
                            // video=true（布尔值）等非直链情况，创建占位让后续走WebView补全
                            media += MediaItem(MediaType.VIDEO, url = "", postId = postId, needsDetail = true)
                            DebugLog.d(TAG, "     video=$vs(非直链)，插入占位(needsDetail=true)")
                        }
                    }
                    else -> {}
                }
            }

            // 2) 从 content 里提取所有 [img url="..."] 和 [video url="..."] 标签
            //    鱼吧列表 API 的 imglist 最多只返回 3 张，多图帖的完整图片只在详情 content 的 BBCode 里
            val content = postObj["content"]?.toStr() ?: postObj["describe"]?.toStr() ?: ""
            DebugLog.d(TAG, "   content.length=${content.length}，含[img=${content.count("[img", true)}，含[video=${content.count("[video", true)}，含data-playurl=${content.contains("data-playurl", true)}")
            // 提取所有图片（不只是第一张）
            val allImgs = extractAllImagesFromContent(content, postId)
            DebugLog.d(TAG, "   从 content 里抽出 ${allImgs.size} 张图片(BBCode)")
            for (img in allImgs) {
                if (img.url !in media.map { it.url }.toSet()) media += img
            }
            val vTags = extractVideoTags(content, postId)
            if (vTags.isNotEmpty()) {
                DebugLog.d(TAG, "   从 content 抽出 ${vTags.size} 个[video]标签：${vTags.joinToString { it.url.take(60) }}")
                media += vTags
            }
            // 如果没有视频，且 content 里有图片，不再生成空 url 的视频占位
            // （v1.9 之前这里只取第一张图当视频封面，导致多图帖丢失图片）

            // 3) 递归兜底
            val extra = collectMediaFromNode(postObj, postId)
            for (e in extra) if (e.url !in media.map { it.url }.toSet()) media += e
            if (extra.isNotEmpty()) DebugLog.d(TAG, "   递归树额外抓了 ${extra.size} 个媒体")

            DebugLog.d(TAG, "   ✅ 详情抽取总计 ${media.size} 个：图${media.count{!it.isVideo}} 视${media.count{it.isVideo}}")
            if (media.isNotEmpty()) return media
        }
        // HTML 兜底
        DebugLog.w(TAG, "   JSON 解析路径失败，走 HTML 兜底")
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

    private fun String.count(sub: String, ignoreCase: Boolean): Int {
        if (this.isEmpty() || sub.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (idx <= this.length - sub.length) {
            val found = this.indexOf(sub, idx, ignoreCase)
            if (found < 0) break
            count++
            idx = found + sub.length
        }
        return count
    }

    /** 从鱼吧 BBCode 格式 content 里提取第一个 [img url="..."] 的图片URL */
    private fun extractFirstImgFromContent(content: String): String {
        if (content.isEmpty()) return ""
        // [img src="" url="https://..."] 或 [img]url[/img]
        val regex = Regex("""\[img[^\]]*url="([^"]+)"""", RegexOption.IGNORE_CASE)
        return regex.find(content)?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.startsWith("http")) it else ""
        } ?: ""
    }

    /** 从 content 里提取所有 [img url="..."] 标签的图片URL（v1.9：多图帖完整补全） */
    private fun extractAllImagesFromContent(content: String, postId: String): List<MediaItem> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<MediaItem>()
        // 匹配 [img src="" url="https://..."] 或 [img]https://...[/img]
        val regex = Regex("""\[img[^\]]*url="([^"]+)"""", RegexOption.IGNORE_CASE)
        regex.findAll(content).forEach { m ->
            val url = m.groupValues.getOrNull(1)?.trim() ?: ""
            if (url.startsWith("http") && isImageUrl(url)) {
                out += MediaItem(MediaType.IMAGE, url = url, postId = postId)
            }
        }
        return out
    }

    /** 从 content 里提取 [video url="..."] 标签的视频直链 */
    private fun extractVideoTags(content: String, postId: String): List<MediaItem> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<MediaItem>()
        // [video src="" url="https://....mp4"] 或 [video]url[/video]
        val regex = Regex("""\[video[^\]]*url="([^"]+)"|\[video[^\]]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        regex.findAll(content).forEach { m ->
            val url = (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() })?.trim() ?: ""
            if (url.isNotEmpty() && isVideoUrl(url)) {
                out += MediaItem(MediaType.VIDEO, url = url, postId = postId)
            }
        }
        return out
    }

    /** 从详情 content 里提取 data-playurl（斗鱼视频分享页链接，如 https://v.douyu.com/show/xxx） */
    fun extractVideoPlayUrl(content: String): String? {
        if (content.isEmpty()) return null
        // data-playurl="https://v.douyu.com/show/xxx"
        val regex = Regex("""data-playurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val m = regex.find(content)
        val result = m?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.startsWith("http")) it else null
        }
        DebugLog.d(TAG, "     extractVideoPlayUrl：regex匹配次数=${regex.findAll(content).count()}，结果=$result")
        return result
    }

    /** 从详情 content 里提取所有 data-playurl（多视频帖可能有多个） */
    fun extractAllVideoPlayUrls(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val regex = Regex("""data-playurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val urls = regex.findAll(content).mapNotNull { m ->
            m.groupValues.getOrNull(1)?.trim()?.let { if (it.startsWith("http")) it else null }
        }.distinct().toList()
        DebugLog.d(TAG, "     extractAllVideoPlayUrls：匹配${regex.findAll(content).count()}次，去重后=${urls.size}个")
        return urls
    }

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
