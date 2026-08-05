package com.yinzi.crawler.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.yinzi.crawler.App
import com.yinzi.crawler.R
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.MediaType
import com.yinzi.crawler.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * 前台服务：用于在后台批量下载视频/图片，
 * 避免退到后台被系统杀掉。下载完成自动退出前台。
 */
class DownloadService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val urls = intent?.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        val types = intent?.getIntegerArrayListExtra(EXTRA_TYPES) ?: emptyList()
        if (urls.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, buildNotification(0, urls.size))
        lifecycleScope.launch {
            var done = 0
            for ((i, u) in urls.withIndex()) {
                val isVideo = types.getOrNull(i) == TYPE_VIDEO
                DownloadManager.download(
                    MediaItem(if (isVideo) MediaType.VIDEO else MediaType.IMAGE, u)
                )
                done++
                notifyProgress(done, urls.size)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(done: Int, total: Int): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_DOWNLOAD)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.download_running) + " ($done/$total)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, done == 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyProgress(done: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(done, total))
    }

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_TYPES = "types"
        const val NOTIF_ID = 1001
        const val TYPE_VIDEO = 1
        const val TYPE_IMAGE = 0
    }
}
