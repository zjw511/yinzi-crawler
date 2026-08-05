package com.yinzi.crawler.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用偏好：保存鱼吧 group_id、登录 Cookie 等配置。
 * Cookie 来自用户在浏览器登录斗鱼后复制的请求头，仅在本地使用。
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
        set(value) { sp.edit().putString(KEY_COOKIE, value).apply() }

    /** 已下载的媒体 URL 集合（去重用） */
    var downloaded: Set<String>
        get() = sp.getStringSet(KEY_DOWNLOADED, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_DOWNLOADED, value).apply() }

    fun markDownloaded(url: String) {
        downloaded = downloaded + url
    }

    fun isDownloaded(url: String): Boolean = downloaded.contains(url)

    private const val KEY_GROUP_ID = "group_id"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_DOWNLOADED = "downloaded"
}
