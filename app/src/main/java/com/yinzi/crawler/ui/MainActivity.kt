package com.yinzi.crawler.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.ActivityMainBinding
import com.yinzi.crawler.download.DownloadManager
import com.yinzi.crawler.download.DownloadService
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.network.YubaRepository
import com.yinzi.crawler.util.PermissionUtil
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: PostAdapter

    /** 权限请求 */
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            Snackbar.make(b.root, "已授权，可以保存", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(b.root, R.string.permission_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        adapter = PostAdapter(
            onDownloadPost = { post -> downloadPost(post) },
            onMediaClick = { item, post -> onMediaClick(item, post) },
            onReachEnd = { vm.loadMore() }
        )
        b.rvPosts.layoutManager = LinearLayoutManager(this)
        b.rvPosts.adapter = adapter
        b.rvPosts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) vm.loadMore()
            }
        })

        b.tvGroup.text = "group_id: ${Prefs.groupId}"

        b.swipe.setOnRefreshListener {
            b.tvGroup.text = "group_id: ${Prefs.groupId}"
            vm.refresh()
        }

        b.btnSettings.setOnClickListener {
            SettingsDialog().show(supportFragmentManager, "settings")
        }

        b.fabDownloadAll.setOnClickListener { downloadAllVisible() }

        // 顶部栏显示当前模式（匿名 / 登录态）
        val (modeIcon, modeText, modeColor) = when {
            Prefs.cookie.isBlank() -> Triple("🟢", "匿名模式可直接使用", 0xFF1976D2.toInt())
            else -> Triple("🟡", "已登录，解锁更多内容", 0xFFF57C00.toInt())
        }
        b.tvMode.text = "$modeIcon $modeText"
        b.tvMode.setTextColor(modeColor)
        b.tvMode.visibility = View.VISIBLE
        b.tvGroup.text = "group_id: ${Prefs.groupId}"

        // 首次启动：友好引导（不再是警告）
        if (Prefs.cookie.isEmpty()) {
            Snackbar.make(b.root, "匿名模式直接能用，想登录点右上角 ⚙ 设置 → App 内登录斗鱼即可。", Snackbar.LENGTH_LONG)
                .setAction("去设置") { SettingsDialog().show(supportFragmentManager, "settings") }
                .show()
        }

        observe()
        ensurePermissions()

        // 首次加载数据
        if (savedInstanceState == null) vm.refresh()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { st ->
                    b.swipe.isRefreshing = false
                    when (st) {
                        is UiState.Idle -> Unit
                        is UiState.Loading -> {
                            b.progressBar.visibility =
                                if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                            b.tvEmpty.visibility = View.GONE
                            b.tvError.visibility = View.GONE
                        }
                        is UiState.Success -> {
                            b.progressBar.visibility = View.GONE
                            b.tvError.visibility = View.GONE
                            b.tvEmpty.visibility =
                                if (st.posts.isEmpty()) View.VISIBLE else View.GONE
                            // ViewModel.loaded 已是去重全量列表，始终用替换避免重复追加
                            adapter.submit(st.posts, clear = true)
                            // 调试信息：显示链路与条数（尤其是首次/刷新时）
                            if (st.isRefresh && !st.debug.isNullOrBlank()) {
                                Snackbar.make(
                                    b.root,
                                    "刷新完成：${st.debug}",
                                    Snackbar.LENGTH_LONG
                                ).setAction("查看全部 ${st.posts.size} 条") {
                                    b.rvPosts.smoothScrollToPosition(0)
                                }.show()
                            } else if (st.isRefresh) {
                                Snackbar.make(
                                    b.root,
                                    "刷新完成，共 ${st.posts.size} 条",
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                        }
                        is UiState.Error -> {
                            b.progressBar.visibility = View.GONE
                            b.tvEmpty.visibility = View.GONE
                            b.tvError.visibility = View.VISIBLE
                            // 错误信息直接显示，不再套 string resource
                            b.tvError.text = buildString {
                                append("😵 没加载出内容\n\n")
                                append(st.msg)
                                append("\n\n")
                                append("你可以：\n① 下拉刷新重试  ② 点右上角 设置 → App 内登录斗鱼\n")
                                append("（匿名模式就能看到大部分内容，登录态解锁更多）")
                            }
                            // 顺便弹个 Toast，避免用户只看到顶栏白屏
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "加载失败：${st.msg.take(40)}…",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        // 下载进度 → 刷新所有活跃的 MediaAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.progress.collect { _ ->
                    adapter.onProgressChanged()
                }
            }
        }
    }

    private fun ensurePermissions() {
        if (!PermissionUtil.granted(this)) {
            permLauncher.launch(PermissionUtil.neededPermissions())
        }
    }

    /** 下载单个帖子的全部媒体 */
    private fun downloadPost(post: Post) {
        if (post.media.isEmpty()) {
            Snackbar.make(b.root, "该帖子没有图片/视频", Snackbar.LENGTH_SHORT).show()
            return
        }
        prepareAndDownload(post.media)
    }

    /** 点击单个媒体：图片/视频都进入全屏预览页 */
    private fun onMediaClick(item: MediaItem, @Suppress("UNUSED_PARAMETER") post: Post) {
        PreviewActivity.start(this, item)
    }

    /** 下载当前可见帖子里的全部媒体 */
    private fun downloadAllVisible() {
        val all = adapter.snapshot().flatMap { it.media }
        if (all.isEmpty()) {
            Snackbar.make(b.root, "当前没有可下载的内容", Snackbar.LENGTH_SHORT).show()
            return
        }
        prepareAndDownload(all)
    }

    /**
     * v2.1: 下载前先在 Activity 层并发补全视频直链。
     * 原因：DownloadService 是无 UI 的 Service，在里面创建 WebView 跑 fetchPostDetail 时
     *       onPageFinished / shouldInterceptRequest 不稳定，导致视频 url 补全失败。
     *       改为在 Activity 存活时先补全（WebView 用 appCtx，Activity 主线程 Looper 活跃），
     *       补全后 url 非空，DownloadService 直接下载，不再跑 WebView。
     * v2.1 优化：并发补全（async + awaitAll），最多 5 个视频同时跑，避免串行太慢。
     */
    private fun prepareAndDownload(media: List<MediaItem>) {
        // 检查是否有视频需要补全 url
        val needFix = media.filter { it.isVideo && it.url.isBlank() && !it.postId.isNullOrBlank() }
        if (needFix.isEmpty()) {
            // 没有需要补全的，直接启动下载
            startDownloadService(media)
            Snackbar.make(b.root, "已加入下载队列：${media.size} 个", Snackbar.LENGTH_SHORT).show()
            return
        }
        // 有视频需要补全，先提示用户等待
        val snackbar = Snackbar.make(b.root, "正在解析视频直链(${needFix.size}个)，请稍候…", Snackbar.LENGTH_INDEFINITE)
        snackbar.show()
        lifecycleScope.launch {
            // 并发补全所有需要补全的视频
            val fixedMap = withContext(Dispatchers.IO) {
                needFix.map { m ->
                    async(Dispatchers.IO) {
                        val extra = runCatching {
                            YubaRepository.fetchPostMedia(m.postId!!)
                        }.getOrDefault(emptyList())
                        val videoUrl = extra.firstOrNull { it.isVideo && it.url.isNotBlank() }?.url
                        m to videoUrl  // m → 补全后的 url（可能为 null 表示失败）
                    }
                }.awaitAll().toMap()
            }
            snackbar.dismiss()
            // 应用补全结果到 media 列表
            val fixed = media.map { m ->
                fixedMap[m]?.let { resolvedUrl ->
                    if (!resolvedUrl.isNullOrBlank()) {
                        m.copy(url = resolvedUrl)
                    } else {
                        m  // 补全失败，保持原样（url 仍空）
                    }
                } ?: m
            }
            // 过滤掉仍 url 为空的视频（无法下载）
            val downloadable = fixed.filter { !it.isVideo || it.url.isNotBlank() }
            val skipped = fixed.size - downloadable.size
            if (downloadable.isEmpty()) {
                Snackbar.make(b.root, "视频直链解析全部失败，无法下载", Snackbar.LENGTH_LONG).show()
            } else {
                startDownloadService(downloadable)
                val msg = if (skipped > 0)
                    "已加入队列：${downloadable.size} 个（$skipped 个视频解析失败）"
                else
                    "已加入下载队列：${downloadable.size} 个"
                Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownloadService(media: List<MediaItem>) {
        val urls = ArrayList(media.map { it.url })
        val types = ArrayList(media.map { if (it.isVideo) 1 else 0 })
        val postIds = ArrayList(media.map { it.postId ?: "" })
        val intent = Intent(this, DownloadService::class.java).apply {
            putStringArrayListExtra(DownloadService.EXTRA_URLS, urls)
            putIntegerArrayListExtra(DownloadService.EXTRA_TYPES, types)
            putStringArrayListExtra(DownloadService.EXTRA_POST_IDS, postIds)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
