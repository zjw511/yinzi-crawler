package com.yinzi.crawler.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.util.DebugLog
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用 Android WebView 后台加载鱼吧页面，等 JS 渲染完成后再 evaluateJavascript
 * 从真实 DOM 中抽取帖子和媒体。
 *
 * 这是 v1.1 的主抓数方式：
 *  - 匿名用户（没填 Cookie）：直接走默认浏览器 UA，渲染出和你手机浏览器匿名打开完全一致的内容
 *  - 登录态用户：Prefs.syncToWebView() 已把 Cookie 注入 WebView，渲染出和浏览器登录后一致的内容
 *
 * 为什么不直接用 HTTP 拿 HTML？
 *  鱼吧现在是「客户端渲染（CSR）」：首屏只返回包含 40+ JS 引用的空壳 HTML，
 *  实际帖子内容靠浏览器异步 XHR 渲染。curl / OkHttp 无法执行 JS，拿到的永远是空壳。
 *  WebView 是唯一免依赖 Playwright、在手机本地就能跑的稳定办法。
 */
@SuppressLint("SetJavaScriptEnabled")
object WebViewFetcher {

    private const val TAG = "WV"

    private const val DESKTOP_UA = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    /** 初始化 WebView，设置和 PC 浏览器一致的参数 */
    fun createWebView(ctx: Context, forLogin: Boolean = false): WebView {
        val appCtx = ctx.applicationContext
        // WebView 必须在主线程创建
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "createWebView must run on main thread"
        }
        val cookieHint = if (Prefs.isAnonymous) "匿名(无Cookie)" else "已登录(${Prefs.cookie.split(';').size}个字段)"
        DebugLog.d(TAG, "🌐 createWebView：forLogin=$forLogin, cookie状态=$cookieHint")
        val wv = WebView(appCtx)
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = forLogin  // 抓列表时不加载图片更省流量
        s.blockNetworkImage = !forLogin
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = DESKTOP_UA
        s.allowContentAccess = true
        s.allowFileAccess = true

        // 同步 Cookie（匿名模式下会清空残留 Cookie；登录态注入用户自己的 Cookie）
        Prefs.syncToWebView()
        // 针对当前 WebView 实例开启第三方 Cookie（传 null 会 NPE，必须传实例）
        runCatching { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
        return wv
    }

    /**
     * 加载鱼吧列表页并抽取帖子。
     * @param page 从 1 开始（鱼吧前端分页是 1-based）
     */
    suspend fun fetchPosts(
        ctx: Context,
        groupId: String = Prefs.groupId,
        page: Int = 1
    ): List<Post> = withContext(Dispatchers.Main) {
        val url = "https://yuba.douyu.com/discussion/$groupId/posts?page=$page"
        DebugLog.i(TAG, "📚 fetchPosts 开始：url=$url")
        val html = loadAndGetOuterHtml(ctx, url, waitMs = 6000L)
        DebugLog.d(TAG, "   loadAndGetOuterHtml 结果：html.length=${html.length}")
        if (html.isBlank()) {
            DebugLog.e(TAG, "   ❌ 拿不到空 HTML，返回空")
            return@withContext emptyList()
        }

        // 直接 evaluateJavascript 拿 DOM
        val js = extractPostsJs()
        val jsonStr = evaluateJs(ctx, url, js, waitMs = 8000L, preloadedHtml = html)
        DebugLog.d(TAG, "   evaluateJs 抽帖结果：json.length=${jsonStr.length}, 前500字符=${DebugLog.truncate(jsonStr, 500)}")
        if (jsonStr.isBlank()) {
            DebugLog.e(TAG, "   ❌ JS抽帖返回空，返回空")
            return@withContext emptyList()
        }

        val result = runCatching { parsePostsJson(jsonStr) }.getOrDefault(emptyList())
        DebugLog.i(TAG, "   ✅ parsePostsJson 解析出 ${result.size} 条帖子")
        result
    }

    /**
     * 进入某个帖子详情页，把漏抓的视频直链和更多图片补全。
     * v1.8: 改用 PC 版 yuba.douyu.com/p/{id}，demand-video 组件只在 PC 版渲染。
     *      同时拦截 WebView 网络请求，直接抓 .m3u8/.mp4 URL（不依赖 JS 时机，最稳）。
     */
    suspend fun fetchPostDetail(
        ctx: Context,
        postId: String
    ): List<MediaItem> = withContext(Dispatchers.Main) {
        // PC 版帖子页，demand-video 组件会渲染 m3u8
        val url = "https://yuba.douyu.com/p/$postId"
        val js = extractMediaJs()
        DebugLog.d(TAG, "🔍 fetchPostDetail：url=$url")

        // 拦截到的视频直链（线程安全集合，shouldInterceptRequest 在后台线程回调）
        val interceptedVideos = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        val result = CompletableDeferred<String>()
        val wv = createWebView(ctx)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            DebugLog.w(TAG, "   ⏰ 30s超时触发")
            result.complete("")
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                // 记录所有视频流请求（m3u8/mp4/flv），不拦截让请求正常走
                val u = request?.url?.toString() ?: return null
                val lower = u.lowercase()
                if (lower.contains(".m3u8") || lower.contains(".mp4") ||
                    lower.contains(".flv") || lower.contains(".m4v")) {
                    DebugLog.d(TAG, "   🎯 拦截视频请求：${u.take(100)}")
                    interceptedVideos.add(u)
                }
                return null
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                DebugLog.d(TAG, "   ✅ onPageFinished：$u")
                main.removeCallbacks(timeout)
                // 等 15 秒让 demand-video 组件初始化并加载 player.src
                main.postDelayed({
                    DebugLog.d(TAG, "   ⏱️  15s 到，evaluateJavascript 抽媒体")
                    view?.evaluateJavascript(js) { s ->
                        val res = jsonStripQuote(s)
                        DebugLog.d(TAG, "   JS 抽媒体结果：${res.length}字符，已拦截视频=${interceptedVideos.size}个")
                        result.complete(res)
                        wv.destroySafely()
                    }
                }, 15000L)
            }

            override fun onReceivedError(
                view: WebView?, req: WebResourceRequest?, err: WebResourceError?
            ) {
                DebugLog.w(TAG, "   ⚠️  onReceivedError: url=${req?.url}, err=${err?.description}, code=${err?.errorCode}")
            }
        }
        wv.webChromeClient = WebChromeClient()

        main.postDelayed(timeout, 30000L)
        wv.loadUrl(url)
        DebugLog.d(TAG, "   🚀 开始加载url，30s超时")

        val jsonStr = try {
            withTimeout(33000L) { result.await() }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "   ❌ withTimeout 异常：${t.message}")
            ""
        }.also {
            main.removeCallbacks(timeout)
            wv.destroySafely()
        }

        // 合并：DOM 抽取 + 网络拦截
        val fromDom = if (jsonStr.isNotBlank())
            runCatching { parseMediaJson(jsonStr, postId) }.getOrDefault(emptyList())
        else emptyList()
        val fromNet = interceptedVideos.map { MediaItem(MediaType.VIDEO, it, postId = postId) }
        DebugLog.d(TAG, "   结果：DOM抽=${fromDom.size}, 网络拦截=${fromNet.size}")
        // 去重合并（优先 DOM 里有封面的，再补网络拦截的）
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<MediaItem>()
        for (m in fromDom + fromNet) {
            if (m.url.isBlank()) continue
            if (seen.add(m.url)) merged.add(m)
        }
        DebugLog.d(TAG, "   ✅ 去重合并后返回 ${merged.size} 个媒体")
        merged
    }

    /**
     * 加载斗鱼视频分享页（v.douyu.com/show/{vid}），拦截 m3u8 请求获取视频直链。
     * 这是获取斗鱼视频直链最可靠的方式：
     *  帖子详情 API 不返回视频直链，只返回 data-playurl 指向分享页；
     *  分享页由 demand-video 组件渲染，会发起 m3u8 请求，拦截即可。
     *
     * @param videoPageUrl 如 https://v.douyu.com/show/NbwE7ZxoGlBWn5Zz
     */
    suspend fun fetchVideoFromSharePage(
        ctx: Context,
        videoPageUrl: String,
        postId: String = ""
    ): List<MediaItem> = withContext(Dispatchers.Main) {
        val interceptedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        DebugLog.d(TAG, "🎬 fetchVideoFromSharePage：url=$videoPageUrl")

        val result = CompletableDeferred<String>()
        val wv = createWebView(ctx)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            DebugLog.w(TAG, "   ⏰ 25s超时触发，已拦截 ${interceptedUrls.size} 个链接")
            result.complete("")
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                val lower = u.lowercase()
                if (lower.contains(".m3u8") || lower.contains(".mp4") ||
                    lower.contains(".flv") || lower.contains(".m4v")) {
                    DebugLog.d(TAG, "   🎯 拦截到视频URL：${u.take(120)}")
                    interceptedUrls.add(u)
                }
                return null
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                DebugLog.d(TAG, "   ✅ 分享页onPageFinished：$u，当前已拦截=${interceptedUrls.size}个")
                main.removeCallbacks(timeout)
                // 等 12 秒让 demand-video 组件初始化并发起 m3u8 请求
                main.postDelayed({
                    DebugLog.d(TAG, "   ⏱️  12s 到，兜底 evaluateJavascript 取 player.src")
                    // 尝试从 JS 获取 player.src 作为兜底
                    view?.evaluateJavascript("""
                        (function(){
                            try {
                                var els = document.querySelectorAll('demand-video');
                                for(var i=0;i<els.length;i++){
                                    var el = els[i];
                                    var p = el.player || (el.shadowRoot ? el.shadowRoot.querySelector('video') : null);
                                    if(el.player && el.player.src) return el.player.src;
                                    if(p && p.src) return p.src;
                                    if(p && p.currentSrc) return p.currentSrc;
                                }
                                var v = document.querySelector('video');
                                if(v && v.src) return v.src;
                                return '';
                            } catch(e) { return 'ERROR:'+e.message; }
                        })();
                    """) { s ->
                        val jsUrl = jsonStripQuote(s)
                        DebugLog.d(TAG, "   JS兜底结果：${if(jsUrl.isBlank())"(空)" else DebugLog.truncate(jsUrl, 120)}")
                        if (jsUrl.isNotBlank() && jsUrl.startsWith("http")) interceptedUrls.add(jsUrl)
                        DebugLog.d(TAG, "   完成，共拦截到视频URL=${interceptedUrls.size}个")
                        result.complete("done")
                        wv.destroySafely()
                    }
                }, 12000L)
            }

            override fun onReceivedError(
                view: WebView?, req: WebResourceRequest?, err: WebResourceError?
            ) {
                DebugLog.w(TAG, "   ⚠️  分享页资源错误：url=${req?.url?.toString()?.take(80)}, err=${err?.description}")
            }
        }
        wv.webChromeClient = WebChromeClient()

        main.postDelayed(timeout, 25000L)
        wv.loadUrl(videoPageUrl)
        DebugLog.d(TAG, "   🚀 已loadUrl，25s超时")

        try {
            withTimeout(28000L) { result.await() }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "   ❌ withTimeout 异常：${t.message}")
            ""
        }.also {
            main.removeCallbacks(timeout)
            wv.destroySafely()
        }

        DebugLog.d(TAG, "   最终拦截视频URL=${interceptedUrls.size}个：${interceptedUrls.joinToString { "\n     - $it" }}")
        // 把拦截到的 URL 转成 MediaItem
        interceptedUrls.map { url -> MediaItem(MediaType.VIDEO, url, postId = postId) }
    }

    // ------------------------------------------------------------------
    //  内部：加载页面并跑一段 JS，用 CompletableDeferred 把回调 API 变成挂起函数
    // ------------------------------------------------------------------

    private suspend fun loadAndGetOuterHtml(
        ctx: Context,
        url: String,
        waitMs: Long
    ): String = withContext(Dispatchers.Main) {
        DebugLog.d(TAG, "   loadAndGetOuterHtml：url=$url, waitMs=$waitMs")
        val result = CompletableDeferred<String>()
        val wv = createWebView(ctx)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            DebugLog.w(TAG, "     ⏰ loadAndGetOuterHtml 超时(${waitMs + 10000}ms)")
            result.complete("")
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                DebugLog.d(TAG, "     onPageFinished=$u，延迟${waitMs}ms等渲染")
                // 延迟 waitMs，给 XHR / 渲染留时间
                main.removeCallbacks(timeout)
                main.postDelayed({
                    view?.evaluateJavascript("document.documentElement.outerHTML") { htmlRaw ->
                        val html = if (htmlRaw == null || htmlRaw == "null") "" else
                            jsonStripQuote(htmlRaw)
                        DebugLog.d(TAG, "     拿到outerHTML，长度=${html.length}")
                        result.complete(html)
                        wv.stopLoading()
                        wv.destroySafely()
                    }
                }, waitMs)
            }

            override fun onReceivedError(
                view: WebView?, req: WebResourceRequest?, err: WebResourceError?
            ) {
                DebugLog.w(TAG, "     ⚠️  onReceivedError: url=${req?.url?.toString()?.take(60)}, err=${err?.description}")
                /* 忽略子资源报错，等 onPageFinished 兜底 */
            }
        }
        wv.webChromeClient = WebChromeClient()

        main.postDelayed(timeout, waitMs + 10000L)
        wv.loadUrl(url)

        try {
            withTimeout(waitMs + 15000L) { result.await() }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "     ❌ withTimeout异常：${t.message}")
            ""
        }.also {
            main.removeCallbacks(timeout)
            wv.destroySafely()
        }
    }

    private suspend fun evaluateJs(
        ctx: Context,
        url: String,
        script: String,
        waitMs: Long,
        preloadedHtml: String?
    ): String = withContext(Dispatchers.Main) {
        DebugLog.d(TAG, "   evaluateJs：url=$url, waitMs=$waitMs, preloadedHtml=${!preloadedHtml.isNullOrBlank()}")
        val result = CompletableDeferred<String>()
        val wv = createWebView(ctx)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            DebugLog.w(TAG, "     ⏰ evaluateJs 超时")
            result.complete("")
        }

        // 如果 html 已经预加载了（同一个页面），直接用 dataUrl 写回去避免二次加载
        if (!preloadedHtml.isNullOrBlank()) {
            main.post {
                wv.loadDataWithBaseURL(url, preloadedHtml, "text/html", "UTF-8", url)
                // 等 DOM ready
                main.postDelayed({
                    DebugLog.d(TAG, "     preloadedHtml 800ms 后 evaluateJavascript")
                    wv.evaluateJavascript(script) { s ->
                        val r = jsonStripQuote(s)
                        DebugLog.d(TAG, "     JS返回，长度=${r.length}")
                        result.complete(r)
                        wv.destroySafely()
                    }
                }, 800L)
            }
        } else {
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    DebugLog.d(TAG, "     onPageFinished=$u, 延迟${waitMs}ms后跑JS")
                    main.removeCallbacks(timeout)
                    main.postDelayed({
                        view?.evaluateJavascript(script) { s ->
                            val r = jsonStripQuote(s)
                            DebugLog.d(TAG, "     JS返回，长度=${r.length}")
                            result.complete(r)
                            wv.destroySafely()
                        }
                    }, waitMs)
                }
            }
            main.postDelayed(timeout, waitMs + 12000L)
            wv.loadUrl(url)
        }

        try {
            withTimeout(waitMs + 18000L) { result.await() }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "     ❌ withTimeout异常：${t.message}")
            ""
        }.also {
            main.removeCallbacks(timeout)
            wv.destroySafely()
        }
    }

    private fun WebView.destroySafely() {
        runCatching {
            stopLoading()
            removeAllViews()
            destroy()
        }.onFailure { DebugLog.w(TAG, "   destroySafely 异常：${it.message}") }
    }

    /** WebView evaluateJavascript 会把字符串结果额外包一层 JSON 双引号 + 转义 */
    private fun jsonStripQuote(s: String?): String {
        if (s.isNullOrEmpty() || s == "null") return ""
        return runCatching { org.json.JSONTokener(s).nextValue() as? String ?: s }
            .getOrDefault(s)
    }

    // ------------------------------------------------------------------
    //  JS 脚本：在 WebView 里跑，把 DOM 变成 JSON 返回给 Kotlin
    // ------------------------------------------------------------------

    /** 返回帖子数组 JSON：[{id,author,avatar,time,content,media:[{type,url,thumb}]}] */
    private fun extractPostsJs(): String = """
(function(){
  var posts = [];
  var pushCard = function(card){
    if(!card) return;
    var text = (card.innerText || card.textContent || '').replace(/\s+/g,' ').trim();
    if(text.length < 8) return;
    // 作者
    var author = '';
    var avatar = '';
    var avs = card.querySelectorAll('img[class*="avatar"], img[class*="head"], img[class*="Avatar"]');
    if(avs.length){ avatar = avs[0].src || avs[0].getAttribute('data-src') || ''; }
    var anames = card.querySelectorAll('[class*="nickname"], [class*="name"], [class*="NickName"], [class*="user-name"]');
    if(anames.length){ author = (anames[0].innerText||'').trim(); }

    // 时间
    var time = '';
    var ts = card.querySelectorAll('[class*="time"], [class*="Time"], [datetime], [class*="date"]');
    if(ts.length){ time = (ts[0].getAttribute('datetime') || ts[0].innerText || '').trim(); }

    // 正文/标题
    var content = '';
    var cs = card.querySelectorAll('[class*="content"], [class*="desc"], [class*="body"], [class*="Content"], [class*="title"], [class*="Title"], article, p');
    for(var i=0;i<cs.length;i++){
      var t = (cs[i].innerText||'').trim();
      if(t.length > content.length) content = t;
    }
    if(!content) content = text.substring(0,120);

    // 找帖子链接，扣出 post id
    var postId = '';
    var links = card.querySelectorAll('a[href*="/post/"], a[href*="/p/"], a[href*="post_id"], a[href*="tid="]');
    for(var i=0;i<links.length;i++){
      var href = links[i].getAttribute('href')||'';
      var m = href.match(/\/(?:post|p)\/([A-Za-z0-9_-]+)/);
      if(m){ postId = m[1]; break; }
      m = href.match(/(?:post_id|tid)=([A-Za-z0-9_-]+)/);
      if(m){ postId = m[1]; break; }
    }
    if(!postId){
      // 兜底：自己生成一个稳定 id（取内容前 20 字符 hash）
      postId = 'dom_' + Math.abs(hashCode(text.substring(0,60) + '|' + card.getAttribute('class')));
    }

    // 媒体：img / video / source
    var media = [];
    var imgs = card.querySelectorAll('img');
    for(var i=0;i<imgs.length;i++){
      var src = imgs[i].src || imgs[i].getAttribute('data-src') || imgs[i].getAttribute('data-original') || '';
      if(!src || isBadUrl(src)) continue;
      media.push({type:'IMAGE', url:abs(src), thumb:null});
    }
    var vids = card.querySelectorAll('video, source[src$=".mp4"], source[src*=".mp4?"]');
    for(var i=0;i<vids.length;i++){
      var vsrc = vids[i].src || vids[i].getAttribute('data-src') || '';
      if(!vsrc || isBadUrl(vsrc)) continue;
      var poster = vids[i].parentElement ? (vids[i].parentElement.querySelector('img')||{}).src || '' : '';
      media.push({type:'VIDEO', url:abs(vsrc), thumb: poster ? abs(poster):null});
    }
    media = dedupeMedia(media);

    posts.push({
      id: postId, author: author, avatar: avatar ? abs(avatar):'',
      time: time, content: content, media: media
    });
  };

  // 候选选择器（Next.js/React 生成的 class 带 hash，这里只匹配关键字段）
  var selectors = [
    '[class*="PostItem"]', '[class*="post-item"]', '[class*="post-card"]',
    '[class*="postCard"]', '[class*="topic-item"]', '[class*="thread-item"]',
    '[class*="feed-item"]', '[class*="FeedItem"]', 'li[class*="list-item"]',
    '[class*="ListItem"]', '[data-testid*="post"]'
  ];
  var all = [];
  try{
    selectors.forEach(function(sel){
      try{
        var ns = document.querySelectorAll(sel);
        for(var i=0;i<ns.length;i++) if(all.indexOf(ns[i])<0) all.push(ns[i]);
      }catch(e){}
    });
    if(all.length === 0){
      // 兜底：取所有包含 img 的中等大小 div
      var divs = document.querySelectorAll('div, section, article, li');
      for(var i=0;i<divs.length;i++){
        var d = divs[i];
        var imgs = d.querySelectorAll('img[src*=".jpg"], img[src*=".png"], img[src*=".webp"], img[data-src]');
        if(imgs.length < 1) continue;
        var txt = (d.innerText||'').trim();
        if(txt.length > 30 && txt.length < 2000 && d.children.length < 80) all.push(d);
      }
    }
  }catch(e){}
  all.forEach(pushCard);

  // 去重（按 postId / content 前缀）
  var seen = {};
  var out = [];
  for(var i=0;i<posts.length;i++){
    var p = posts[i];
    var key = p.id + '|' + (p.content||'').substring(0,40);
    if(seen[key]) continue;
    seen[key] = true;
    out.push(p);
  }
  return JSON.stringify(out);

  // --- helpers ---
  function hashCode(s){var h=0;for(var i=0;i<s.length;i++){h=((h<<5)-h)+s.charCodeAt(i);h|=0;}return h;}
  function abs(u){if(!u) return ''; if(u.indexOf('//')===0) return location.protocol + u; if(u.indexOf('/')===0) return location.origin + u; return u;}
  function isBadUrl(u){return !u || /(avatar|head|emoji|favicon|logo|icon|sprite|placeholder|1x1|blank)/i.test(u);}
  function dedupeMedia(arr){var o={};arr.forEach(function(m){o[m.type+'|'+m.url]=m;});return Object.keys(o).map(function(k){return o[k];});}
})();
""".trimIndent()

    /** 只抓媒体数组（用于帖子详情补全） */
    private fun extractMediaJs(): String = """
(function(){
  var media=[];
  var bad = /(avatar|head|emoji|favicon|logo|icon|sprite|placeholder)/i;
  var seen={};
  function push(t,u,th){
    if(!u || bad.test(u)) return;
    if(u.indexOf('//')===0) u = location.protocol + u;
    else if(u.indexOf('/')===0) u = location.origin + u;
    var k = t+'|'+u; if(seen[k]) return; seen[k]=true;
    media.push({type:t, url:u, thumb:th||null});
  }
  document.querySelectorAll('img').forEach(function(el){
    var s = el.src || el.getAttribute('data-src') || el.getAttribute('data-original') || '';
    if(/\.(jpg|jpeg|png|webp|gif|bmp)(\?|$)/i.test(s)) push('IMAGE', s, null);
  });
  // 1) <video> 和 <source> 标签
  document.querySelectorAll('video, source').forEach(function(el){
    var s = el.src || el.getAttribute('data-src') || el.getAttribute('src') || '';
    if(s && /\.(mp4|m3u8|flv|m4v|webm)(\?|$)/i.test(s)){
      var p = el.getAttribute('poster') || (el.parentElement ? (el.parentElement.querySelector('img')||{}).src || '':'');
      push('VIDEO', s, p);
    }
  });
  // 2) video.js 播放器实例
  try {
    if (typeof videojs !== 'undefined' && videojs.players) {
      Object.keys(videojs.players).forEach(function(id){
        try {
          var p = videojs.players[id];
          var src = p.src ? p.src() : (p.cache_ && p.cache_.src ? p.cache_.src : '');
          if (src && /\.(mp4|m3u8|flv|m4v|webm)(\?|$)/i.test(src)) {
            var poster = p.poster ? p.poster() : '';
            push('VIDEO', src, poster);
          }
        } catch(e){}
      });
    }
  } catch(e){}
  // 3) <video> data-* 属性
  document.querySelectorAll('video').forEach(function(el){
    var attrs = ['src','data-src','data-video-src','data-video-url','data-url'];
    attrs.forEach(function(attr){
      var s = el.getAttribute(attr);
      if (s && s.indexOf('http') === 0) {
        push('VIDEO', s, el.getAttribute('poster') || '');
      }
    });
  });
  // 4) 斗鱼自研 demand-video 组件：player.src 是 m3u8 直链
  try {
    document.querySelectorAll('demand-video').forEach(function(el){
      var p = el.player || (el.shadowRoot ? el.shadowRoot.querySelector('video') : null);
      if (el.player && el.player.src) {
        push('VIDEO', el.player.src, el.player.poster || '');
      }
      // Shadow DOM 里的 video
      if (el.shadowRoot) {
        var sv = el.shadowRoot.querySelector('video');
        if (sv && sv.src) push('VIDEO', sv.src, '');
        if (sv && sv.currentSrc) push('VIDEO', sv.currentSrc, '');
      }
    });
  } catch(e){}
  return JSON.stringify(media);
})();
""".trimIndent()

    // ------------------------------------------------------------------
    //  JSON -> Kotlin data class
    // ------------------------------------------------------------------

    private fun parsePostsJson(s: String): List<Post> {
        val arr = JSONArray(s)
        val out = ArrayList<Post>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mediaArr = o.optJSONArray("media") ?: JSONArray()
            val media = ArrayList<MediaItem>(mediaArr.length())
            val postId = o.optString("id", "dom")
            for (j in 0 until mediaArr.length()) {
                val m = mediaArr.optJSONObject(j) ?: continue
                val url = m.optString("url", "").trim()
                if (url.isEmpty()) continue
                val type = if (m.optString("type", "IMAGE") == "VIDEO") MediaType.VIDEO else MediaType.IMAGE
                val thumb = m.optString("thumb").takeIf { it.isNotBlank() }
                media.add(MediaItem(type = type, url = url, thumbUrl = thumb, postId = postId))
            }
            out += Post(
                id = postId,
                author = o.optString("author", "").ifBlank { "鱼吧用户" },
                avatar = o.optString("avatar").ifBlank { null },
                time = o.optString("time", ""),
                content = o.optString("content", ""),
                media = media
            )
        }
        return out
    }

    private fun parseMediaJson(s: String, postId: String): List<MediaItem> {
        val arr = JSONArray(s)
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val url = m.optString("url", "").trim()
            if (url.isEmpty()) continue
            val type = if (m.optString("type") == "VIDEO") MediaType.VIDEO else MediaType.IMAGE
            val thumb = m.optString("thumb").takeIf { it.isNotBlank() }
            out += MediaItem(type, url, thumb, postId)
        }
        return out
    }
}
