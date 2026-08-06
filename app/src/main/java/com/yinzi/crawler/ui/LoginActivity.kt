package com.yinzi.crawler.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yinzi.crawler.databinding.ActivityLoginBinding
import com.yinzi.crawler.util.Prefs

/**
 * 内嵌浏览器登录斗鱼：扫码登录后点底部「提取 Cookie 并保存」即可，
 * 全程不用复制粘贴。
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        title = "登录斗鱼鱼吧"

        with(b.wvLogin.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            useWideViewPort = true
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccess = true
            userAgentString = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(b.wvLogin, true)
        }

        b.wvLogin.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                runOnUiThread { b.tvUrl.text = url.orEmpty() }
            }
        }
        b.wvLogin.webChromeClient = WebChromeClient()
        b.wvLogin.loadUrl("https://yuba.douyu.com")

        // 返回键回退 WebView
        b.btnBack.setOnClickListener {
            if (b.wvLogin.canGoBack()) b.wvLogin.goBack() else finish()
        }
        b.btnRefresh.setOnClickListener { b.wvLogin.reload() }

        b.btnExtract.setOnClickListener {
            val cookie = Prefs.syncFromWebView()
            if (cookie.isBlank()) {
                Toast.makeText(this, "没抓到 Cookie，请先完成登录再点这里", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Prefs.syncToWebView()  // 写回 WebView 让后续请求也带
            Toast.makeText(this, "已保存 ${cookie.split(';').size} 条 Cookie，回去就能用啦", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }

        b.btnClose.setOnClickListener { finish() }
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (b.wvLogin.canGoBack()) b.wvLogin.goBack() else super.onBackPressed()
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, LoginActivity::class.java))
        const val REQ = 9001
    }
}
