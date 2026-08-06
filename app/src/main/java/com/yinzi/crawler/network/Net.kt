package com.yinzi.crawler.network

import com.yinzi.crawler.util.DebugLog
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
    private const val TAG_NET = "Net"

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
        DebugLog.i(TAG_NET, "初始化 OkHttp + Retrofit，BASE_URL=$BASE")
        okHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(HeaderInterceptor())
            .addInterceptor(
                HttpLoggingInterceptor { message ->
                    // 把 OkHttp 的 logging 也打到 DebugLog，方便 UI 层查看
                    if (message.startsWith("<--") || message.startsWith("-->") || message.contains("END")) {
                        DebugLog.d("OkHttp", DebugLog.truncate(message, 2000))
                    }
                }.apply {
                    // BODY 级别：请求行 + 头 + 响应体（鱼吧 JSON 不大，直接打完整）
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BASE)
            .client(okHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()

        api = retrofit.create()
        DebugLog.i(TAG_NET, "✅ OkHttp + Retrofit 初始化完成")
    }

    /** 统一注入 UA / Referer / Origin / Accept / Cookie */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val url = orig.url.toString()
            val method = orig.method
            val shortUrl = url.take(120)

            val referer = when {
                url.contains("/post/detail") || url.contains("/post/head") || url.contains("/postdetail") ->
                    "https://yubam.douyu.com/post/" + url.substringAfterLast('/').takeWhile { it.isDigit() || it == '_' || it.isLetter() }
                else -> "https://yubam.douyu.com/group/${Prefs.groupId}"
            }

            val rawCookie = Prefs.cookie.trim()
            val cookieStatus = when {
                rawCookie.isEmpty() -> "❌ 未设置 Cookie（匿名模式）"
                else -> "✅ Cookie 已设置（${rawCookie.split(';').size} 个字段：${DebugLog.maskCookie(rawCookie)}）"
            }

            DebugLog.d(TAG_NET, "➡️ 请求 $method $shortUrl\nReferer=$referer\nCookie状态：$cookieStatus")

            val req = orig.newBuilder()
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", referer)
                .header("Origin", "https://yubam.douyu.com")
                .apply {
                    if (rawCookie.isNotEmpty()) header("Cookie", rawCookie)
                }
                .build()

            val startNs = System.nanoTime()
            val response: Response
            try {
                response = chain.proceed(req)
            } catch (t: Throwable) {
                val cost = (System.nanoTime() - startNs) / 1_000_000
                DebugLog.e(TAG_NET, "❌ 请求异常 $method $shortUrl\n耗时 ${cost}ms 失败：${t.message}", t)
                throw t
            }

            val cost = (System.nanoTime() - startNs) / 1_000_000
            val code = response.code
            val msg = response.message
            val bodyLen = response.body?.contentLength()?.takeIf { it > 0 }
                ?: response.peekBody(1).string().length.toLong()

            // 读取一小段响应体给调试（不影响后续消费，因为是 peek）
            val bodyPeek = try { response.peekBody(800).string() } catch (_: Throwable) { "" }
            val statusEmoji = when {
                code in 200..299 -> "✅"
                code in 300..399 -> "🔄"
                code == 401 || code == 403 -> "🚫"
                code == 404 -> "❓"
                code >= 500 -> "💥"
                else -> "⚠️"
            }
            DebugLog.i(TAG_NET, "⬅️ 响应 $statusEmoji $code $msg 耗时 ${cost}ms 大小 ${bodyLen}字节\nURL=$shortUrl\n响应体前800字符：\n${DebugLog.truncate(bodyPeek, 800)}")
            return response
        }
    }
}
