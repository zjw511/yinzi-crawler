package com.yinzi.crawler.util

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.net.HttpCookie

/**
 * 应用偏好：保存鱼吧 group_id、登录 Cookie 等配置。
 *
 * 2025-08 v1.1：Cookie 现在允许为空（匿名模式）。
 *  - 没设置 Cookie：用 WebView 匿名渲染鱼吧页面，直接从 DOM 里抽取帖子和媒体
 *  - 设置了 Cookie：把 Cookie 注入 WebView，解锁登录态可见内容、高清原图、更多帖子
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    const val DEFAULT_GROUP_ID = "561"   // 寅子鱼吧
    const val DEFAULT_PAGE_LIMIT = 20

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("yinzi", Context.MODE_PRIVATE)
    }

    var groupId: String
        get() = sp.getString(KEY_GROUP_ID, DEFAULT_GROUP_ID) ?: DEFAULT_GROUP_ID
        set(value) { sp.edit().putString(KEY_GROUP_ID, value).apply() }

    var cookie: String
        get() = sp.getString(KEY_COOKIE, "") ?: ""
        set(value) { sp.edit().putString(KEY_COOKIE, value.trim()).apply() }

    /** 未设置 Cookie → 使用匿名模式 */
    val isAnonymous: Boolean get() = cookie.isBlank()

    /** 已下载的媒体 URL 集合（会话内去重） */
    var downloaded: Set<String>
        get() = sp.getStringSet(KEY_DOWNLOADED, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_DOWNLOADED, value).apply() }

    /** 已下载的视频帖子 ID 集合（跨会话去重，因为 m3u8 URL 每次拦截都带不同 token） */
    var downloadedVideoPostIds: Set<String>
        get() = sp.getStringSet(KEY_DOWNLOADED_VIDEO_POSTS, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_DOWNLOADED_VIDEO_POSTS, value).apply() }

    fun markDownloaded(url: String, postId: String? = null, isVideo: Boolean = false) {
        val urls = downloaded + url
        downloaded = urls
        if (isVideo && !postId.isNullOrBlank()) {
            downloadedVideoPostIds = downloadedVideoPostIds + postId
        }
    }

    fun isDownloaded(url: String, postId: String? = null, isVideo: Boolean = false): Boolean {
        if (downloaded.contains(url)) return true
        if (isVideo && !postId.isNullOrBlank() && downloadedVideoPostIds.contains(postId)) return true
        return false
    }

    // ============ WebView Cookie 辅助 ============

    /**
     * 将 Prefs 中保存的 Cookie 注入 Android WebView 的 CookieManager。
     * 注入完成后 flush，确保后续 WebView 加载鱼吧页面时带上登录态。
     * 匿名模式下会清空鱼吧域名的 Cookie（避免残留过期 Cookie 干扰）。
     */
    fun syncToWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // 注意：setAcceptThirdPartyCookies 需要有效的 WebView 实例，传 null 在部分设备会
        // 触发底层 chromium 的 NPE（webView.getSettings() on null）。
        // 第三方 Cookie 接受策略改由 WebViewFetcher.createWebView 针对每个实例单独开启。
        val hosts = listOf("yuba.douyu.com", ".yuba.douyu.com",
            "douyu.com", ".douyu.com", "www.douyu.com", ".douyucdn.cn")
        if (isAnonymous) {
            for (h in hosts) runCatching {
                cm.setCookie(h, "")
            }
            cm.flush()
            return
        }
        // 解析 "k1=v1; k2=v2" 并逐对 setCookie
        val list = cookie.split(';').mapNotNull { s ->
            val pair = s.trim()
            if (pair.isEmpty() || '=' !in pair) return@mapNotNull null
            val (k, v) = pair.split('=', limit = 2).map { it.trim() }
            if (k.isEmpty()) null else k to v
        }
        for (h in hosts) {
            for ((k, v) in list) {
                runCatching {
                    // HttpOnly=false 让 WebView 自己写出来的 AJAX 请求也带
                    cm.setCookie(h, "$k=$v; Domain=$h; Path=/; SameSite=Lax")
                }
            }
        }
        cm.flush()
    }

    /**
     * 从 WebView CookieManager 中读取 yuba.douyu.com / douyu.com 域下的全部 Cookie，
     * 合并成 "k1=v1; k2=v2" 字符串并保存。
     * 用于「App 内登录」按钮：用户在 WebView 里扫码登斗鱼后，直接点「提取并保存」。
     */
    fun syncFromWebView(): String {
        val cm = CookieManager.getInstance()
        val cookieString = buildList {
            add(cm.getCookie("https://yuba.douyu.com").orEmpty())
            add(cm.getCookie("https://www.douyu.com").orEmpty())
            add(cm.getCookie("https://douyu.com").orEmpty())
        }.filter { it.isNotBlank() }
            .joinToString("; ") { it.trimEnd(';') }
            .trim()
        if (cookieString.isNotEmpty()) cookie = cookieString
        return cookieString
    }

    private const val KEY_GROUP_ID = "group_id"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_DOWNLOADED = "downloaded"
    private const val KEY_DOWNLOADED_VIDEO_POSTS = "downloaded_video_posts"
}
