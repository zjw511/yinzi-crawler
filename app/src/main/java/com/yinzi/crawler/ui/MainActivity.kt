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
import com.yinzi.crawler.util.PermissionUtil
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                            adapter.submit(st.posts, clear = st.isRefresh)
                        }
                        is UiState.Error -> {
                            b.progressBar.visibility = View.GONE
                            b.tvEmpty.visibility = View.GONE
                            b.tvError.visibility = View.VISIBLE
                            b.tvError.text = getString(R.string.error_network, st.msg)
                        }
                    }
                }
            }
        }
        // 下载进度（简单：刷新已下载图标，进度细节先不画）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.progress.collect { _ ->
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, "progress")
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
        startDownloadService(post.media)
        Snackbar.make(b.root, "已加入下载队列：${post.media.size} 个", Snackbar.LENGTH_SHORT).show()
    }

    /** 点击单个媒体：图片预览，视频直接下载 */
    private fun onMediaClick(item: MediaItem, post: Post) {
        if (item.isVideo) {
            startDownloadService(listOf(item))
            Snackbar.make(b.root, "开始下载视频", Snackbar.LENGTH_SHORT).show()
        } else {
            // 用系统图片查看器打开（先把图片下载到临时文件）
            previewImage(item)
        }
    }

    private fun previewImage(item: MediaItem) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(item.url).build()
                    val resp = com.yinzi.crawler.network.Net.okHttp.newCall(req).execute()
                    if (!resp.isSuccessful) return@runCatching false
                    val file = File(filesDir, "preview_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { resp.body?.byteStream()?.copyTo(it) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "${packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }
            if (!ok) Snackbar.make(b.root, "图片打开失败", Snackbar.LENGTH_SHORT).show()
        }
    }

    /** 下载当前可见帖子里的全部媒体 */
    private fun downloadAllVisible() {
        val all = adapter.snapshot().flatMap { it.media }
        if (all.isEmpty()) {
            Snackbar.make(b.root, "当前没有可下载的内容", Snackbar.LENGTH_SHORT).show()
            return
        }
        startDownloadService(all)
        Snackbar.make(b.root, "已加入下载队列：${all.size} 个", Snackbar.LENGTH_LONG).show()
    }

    private fun startDownloadService(media: List<MediaItem>) {
        val urls = ArrayList(media.map { it.url })
        val types = ArrayList(media.map { if (it.isVideo) 1 else 0 })
        val intent = Intent(this, DownloadService::class.java).apply {
            putStringArrayListExtra(DownloadService.EXTRA_URLS, urls)
            putIntegerArrayListExtra(DownloadService.EXTRA_TYPES, types)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
