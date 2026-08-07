package com.yinzi.crawler.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.bumptech.glide.Glide
import com.yinzi.crawler.databinding.ActivityPreviewBinding
import com.yinzi.crawler.download.DownloadManager
import com.yinzi.crawler.download.DownloadService
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.network.YubaRepository
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏预览 Activity（v2.3）
 *  - 图片：Glide 加载原图（fitCenter），点击返回关闭
 *  - 视频：ExoPlayer 播放（原生支持 m3u8 HLS，斗鱼视频就是 m3u8）
 *  - 进入时如果视频 url 为空，自动用 postId 走 fetchPostMedia 补全 m3u8 直链
 *  - 保存：补全后的 url 直接传给 DownloadService 下载（m3u8 → ts 合并 mp4）
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityPreviewBinding
    private var mediaItem: MediaItem? = null
    private var exoPlayer: ExoPlayer? = null
    /** 进入预览页时补全后的真实播放/下载 url（可能与初始 mediaItem.url 不同） */
    private var resolvedUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val typeOrdinal = intent.getIntExtra(EXTRA_TYPE, MediaType.IMAGE.ordinal)
        val type = MediaType.values()[typeOrdinal]
        val thumb = intent.getStringExtra(EXTRA_THUMB)
        val postId = intent.getStringExtra(EXTRA_POST_ID)
        mediaItem = MediaItem(type = type, url = url, thumbUrl = thumb, postId = postId)
        resolvedUrl = url

        val item = mediaItem!!
        b.tvTitle.text = if (item.isVideo) "视频预览" else "图片预览"

        // 返回按钮
        b.btnClose.setOnClickListener { finish() }

        // 保存按钮
        b.btnSave.setOnClickListener { startDownload(currentUrl()) }

        // 图片 / 视频分别渲染
        if (item.isVideo) {
            setupVideo(item)
        } else {
            setupImage(item)
        }

        // 监听下载进度
        observeProgress()
    }

    private fun currentUrl(): String = resolvedUrl.ifBlank { mediaItem?.url ?: "" }

    private fun setupImage(item: MediaItem) {
        b.ivPreview.visibility = View.VISIBLE
        b.pvPreview.visibility = View.GONE
        Glide.with(this)
            .load(item.url)
            .fitCenter()
            .into(b.ivPreview)
        // 点击图片退出
        b.ivPreview.setOnClickListener { finish() }
        markIfDownloaded(item.url)
    }

    /**
     * 视频预览：v2.3 用 ExoPlayer 播放 m3u8
     * 如果 url 为空（列表没抓到直链），先用 postId 进详情补全
     */
    private fun setupVideo(item: MediaItem) {
        b.ivPreview.visibility = View.GONE
        b.pvPreview.visibility = View.VISIBLE

        // 先用封面图作为占位（ExoPlayer 还没准备好前显示）
        item.thumbUrl?.let { thumb ->
            b.ivPreview.visibility = View.VISIBLE
            Glide.with(this).load(thumb).centerCrop().into(b.ivPreview)
        }

        if (item.url.isBlank()) {
            // url 为空：进入预览页自动补全 m3u8
            resolveAndPlay(item)
        } else {
            playVideo(item.url)
        }
    }

    /** 用 postId 走 fetchPostMedia 补全视频直链，补全后播放 */
    private fun resolveAndPlay(item: MediaItem) {
        val pid = item.postId
        if (pid.isNullOrBlank()) {
            Toast.makeText(this, "无法解析视频直链（缺少帖子ID）", Toast.LENGTH_LONG).show()
            return
        }
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = "正在解析视频直链…"
        b.pbPreview.visibility = View.VISIBLE
        b.pbPreview.isIndeterminate = true
        b.btnSave.isEnabled = false

        lifecycleScope.launch {
            val extra = withContext(Dispatchers.IO) {
                runCatching { YubaRepository.fetchPostMedia(pid) }.getOrDefault(emptyList())
            }
            val videoUrl = extra.firstOrNull { it.isVideo && it.url.isNotBlank() }?.url
            b.pbPreview.visibility = View.GONE
            b.pbPreview.isIndeterminate = false
            b.btnSave.isEnabled = true
            if (videoUrl.isNullOrBlank()) {
                b.tvStatus.text = "✗ 视频直链解析失败"
                Toast.makeText(this@PreviewActivity, "视频直链解析失败", Toast.LENGTH_LONG).show()
            } else {
                resolvedUrl = videoUrl
                b.tvStatus.visibility = View.GONE
                playVideo(videoUrl)
                markIfDownloaded(videoUrl)
            }
        }
    }

    /** 用 ExoPlayer 播放视频（支持 m3u8 HLS 和 mp4） */
    private fun playVideo(url: String) {
        // 准备 ExoPlayer
        releasePlayer()
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        b.pvPreview.player = player

        // 构造 MediaSource：m3u8 用 HlsMediaSource，其他用默认
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile")
            .setDefaultRequestProperties(mapOf("Referer" to "https://yuba.douyu.com/"))
        val uri = Uri.parse(url)
        val mediaItem = Media3Item.fromUri(uri)
        val mediaSource: MediaSource = if (url.contains(".m3u8")) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(this).createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        b.tvStatus.visibility = View.VISIBLE
                        b.tvStatus.text = "缓冲中…"
                    }
                    Player.STATE_READY -> {
                        b.tvStatus.visibility = View.GONE
                        // 准备好就隐藏封面
                        b.ivPreview.visibility = View.GONE
                    }
                    Player.STATE_ENDED -> {
                        b.tvStatus.visibility = View.VISIBLE
                        b.tvStatus.text = "播放结束"
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = "✗ 播放失败：${error.errorCodeName}"
                Toast.makeText(
                    this@PreviewActivity,
                    "播放失败：${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun releasePlayer() {
        exoPlayer?.let {
            it.stop()
            it.release()
        }
        exoPlayer = null
    }

    private fun markIfDownloaded(url: String) {
        val item = mediaItem
        if (Prefs.isDownloaded(url, item?.postId, item?.isVideo == true)) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.text = "✓ 已保存"
        }
    }

    private fun startDownload(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "视频直链为空，无法下载", Toast.LENGTH_LONG).show()
            return
        }
        val item = mediaItem ?: return
        if (Prefs.isDownloaded(url, item.postId, item.isVideo)) {
            Toast.makeText(this, "已经保存过了", Toast.LENGTH_SHORT).show()
            return
        }
        val urls = arrayListOf(url)
        val types = arrayListOf(if (item.isVideo) 1 else 0)
        val postIds = arrayListOf(item.postId ?: "")
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
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = "已加入下载队列…"
        b.pbPreview.visibility = View.VISIBLE
        b.pbPreview.isIndeterminate = true
    }

    private fun observeProgress() {
        val item = mediaItem ?: return
        var wasDownloading = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.progress.collect { map ->
                    val url = currentUrl()
                    val p = map[url]
                    if (p != null) {
                        if (!wasDownloading) wasDownloading = true
                        b.pbPreview.visibility = View.VISIBLE
                        b.tvStatus.visibility = View.VISIBLE
                        if (p < 0) {
                            b.pbPreview.isIndeterminate = true
                            b.tvStatus.text = "下载中…"
                        } else {
                            b.pbPreview.isIndeterminate = false
                            b.pbPreview.progress = p
                            b.tvStatus.text = "下载中 $p%"
                        }
                    } else if (wasDownloading) {
                        wasDownloading = false
                        b.pbPreview.visibility = View.GONE
                        b.pbPreview.isIndeterminate = false
                        if (Prefs.isDownloaded(url, item.postId, item.isVideo)) {
                            b.tvStatus.visibility = View.VISIBLE
                            b.tvStatus.text = "✓ 下载完成"
                            Toast.makeText(this@PreviewActivity, "下载完成", Toast.LENGTH_SHORT).show()
                        } else {
                            b.tvStatus.visibility = View.VISIBLE
                            b.tvStatus.text = "✗ 下载失败"
                            Toast.makeText(this@PreviewActivity, "下载失败", Toast.LENGTH_SHORT).show()
                        }
                    } else if (Prefs.isDownloaded(url, item.postId, item.isVideo)) {
                        b.pbPreview.visibility = View.GONE
                        b.tvStatus.visibility = View.VISIBLE
                        b.tvStatus.text = "✓ 已保存"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TYPE = "type"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_POST_ID = "post_id"

        fun start(ctx: AppCompatActivity, item: MediaItem) {
            val i = Intent(ctx, PreviewActivity::class.java).apply {
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_TYPE, item.type.ordinal)
                putExtra(EXTRA_THUMB, item.thumbUrl)
                putExtra(EXTRA_POST_ID, item.postId)
            }
            ctx.startActivity(i)
        }
    }
}
