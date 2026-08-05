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

/** 全局网络单例 */
object Net {
    private const val BASE = "https://yuba.douyu.com/"

    lateinit var okHttp: OkHttpClient
        private set
    lateinit var retrofit: Retrofit
        private set
    lateinit var api: YubaApi
        private set

    /** UA：模拟手机浏览器，部分页面要求移动 UA 才返回完整数据 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun init() {
        okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
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
            // 接口返回原始字符串，由 YubaParser 自己解析 JSON/HTML
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()

        api = retrofit.create()
    }

    /** 统一注入 UA 与 Cookie */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Referer", "https://yuba.douyu.com/")
                .header("Accept", "application/json, text/plain, */*")
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
