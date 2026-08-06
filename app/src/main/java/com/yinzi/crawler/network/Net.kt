package com.yinzi.crawler.network

import com.yinzi.crawler.util.Prefs
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * 全局网络单例
 *
 * v1.1.1 起 BASE_URL 改为：
 *   https://yubam.douyu.com/   ← 鱼吧手机版（匿名时桌面版会 307 跳到这里）
 *   真实 API 路径: /wbapi/web/group/postlist?group_id=X&page=N&limit=20
 * 详情接口: /wbapi/web/post/head/{post_id}    (鱼吧头部信息 + 帖子内容)
 */
object Net {
    private const val BASE = "https://yubam.douyu.com/"

    lateinit var okHttp: OkHttpClient
        private set
    lateinit var retrofit: Retrofit
        private set
    lateinit var api: YubaApi
        private set

    /** UA：模拟手机浏览器，鱼吧 307 跳到 m 站之后会用这个 UA 检查 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun init() {
        okHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(HeaderInterceptor())
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BASE)
            .client(okHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()

        api = retrofit.create()
    }

    /** 统一注入 UA / Referer / Origin / Accept / Cookie */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val url = orig.url.toString()
            val referer = when {
                url.contains("/post/head") || url.contains("/postdetail") ->
                    "https://yubam.douyu.com/post/" + url.substringAfterLast('/').takeWhile { it.isDigit() || it == '_' || it.isLetter() }
                else -> "https://yubam.douyu.com/group/${Prefs.groupId}"
            }
            val req = orig.newBuilder()
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", referer)
                .header("Origin", "https://yubam.douyu.com")
                .apply {
                    val cookie = Prefs.cookie.trim()
                    if (cookie.isNotEmpty()) {
                        header("Cookie", cookie)
                    }
                }
                .build()
            return chain.proceed(req)
        }
    }
}
