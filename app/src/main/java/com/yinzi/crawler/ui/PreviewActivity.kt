package com.yinzi.crawler.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.yinzi.crawler.databinding.ActivityPreviewBinding
import com.yinzi.crawler.download.DownloadManager
import com.yinzi.crawler.download.DownloadService
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 全屏预览 Activity
 *  - 图片：Glide 加载原图（fitCenter），点击返回关闭
 *  - 视频：VideoView 播放（支持 m3u8 HLS），带进度条和保存按钮
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityPreviewBinding
    private var mediaItem: MediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val typeOrdinal = intent.getIntExtra(EXTRA_TYPE, MediaType.IMAGE.ordinal)
        val type = MediaType.values()[typeOrdinal]
        val thumb = intent.getStringExtra(EXTRA_THUMB)
        mediaItem = MediaItem(type = type, url = url, thumbUrl = thumb)

        val item = mediaItem!!
        b.tvTitle.text = if (item.isVideo) "视频预览" else "图片预览"

        // 已下载标记
        val downloaded = Prefs.isDownloaded(item.url)
        if (downloaded) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.text = "✓ 已保存"
        }

        // 返回按钮
        b.btnClose.setOnClickListener { finish() }

        // 保存按钮
        b.btnSave.setOnClickListener {
            if (Prefs.isDownloaded(item.url)) {
                Toast.makeText(this, "已经保存过了", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startDownload(item)
        }

        // 图片 / 视频分别渲染
        if (item.isVideo) {
            setupVideo(item)
        } else {
            setupImage(item)
        }

        // 监听下载进度
        observeProgress()
    }

    private fun setupImage(item: MediaItem) {
        b.ivPreview.visibility = View.VISIBLE
        b.vvPreview.visibility = View.GONE
        Glide.with(this)
            .load(item.url)
            .fitCenter()
            .into(b.ivPreview)
        // 点击图片也能退出
        b.ivPreview.setOnClickListener { finish() }
    }

    private fun setupVideo(item: MediaItem) {
        b.ivPreview.visibility = View.GONE
        b.vvPreview.visibility = View.VISIBLE

        // 先用封面图作为占位
        item.thumbUrl?.let { thumb ->
            b.ivPreview.visibility = View.VISIBLE
            Glide.with(this).load(thumb).centerCrop().into(b.ivPreview)
        }

        val mc = MediaController(this)
        mc.setAnchorView(b.vvPreview)
        b.vvPreview.setMediaController(mc)
        b.vvPreview.setVideoURI(Uri.parse(item.url))
        b.vvPreview.setOnPreparedListener {
            // 准备好就隐藏封面
            b.ivPreview.visibility = View.GONE
            b.vvPreview.start()
        }
        b.vvPreview.setOnErrorListener { _, what, extra ->
            Toast.makeText(
                this@PreviewActivity,
                "视频播放失败($what/$extra)，试试点右上角保存到本地再看",
                Toast.LENGTH_LONG
            ).show()
            true
        }
        // 视频缓冲时显示状态
        b.vvPreview.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    b.tvStatus.visibility = View.VISIBLE
                    b.tvStatus.text = "缓冲中…"
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    b.tvStatus.visibility = View.GONE
                }
            }
            false
        }
    }

    private fun startDownload(item: MediaItem) {
        val urls = arrayListOf(item.url)
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
        Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
    }

    private fun observeProgress() {
        val item = mediaItem ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.progress.collect { map ->
                    val p = map[item.url]
                    if (p != null) {
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
                    } else if (Prefs.isDownloaded(item.url)) {
                        b.pbPreview.visibility = View.GONE
                        b.tvStatus.visibility = View.VISIBLE
                        b.tvStatus.text = "✓ 已保存"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { b.vvPreview.stopPlayback() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TYPE = "type"
        const val EXTRA_THUMB = "thumb"

        fun start(ctx: AppCompatActivity, item: MediaItem) {
            val i = Intent(ctx, PreviewActivity::class.java).apply {
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_TYPE, item.type.ordinal)
                putExtra(EXTRA_THUMB, item.thumbUrl)
            }
            ctx.startActivity(i)
        }
    }
}
