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

        startForeground(NOTIF_ID, buildNotification(0, urls.size, 0))
        lifecycleScope.launch {
            var done = 0
            var failed = 0
            val errors = mutableListOf<String>()
            for ((i, u) in urls.withIndex()) {
                val isVideo = types.getOrNull(i) == TYPE_VIDEO
                val item = MediaItem(if (isVideo) MediaType.VIDEO else MediaType.IMAGE, u)
                val result = DownloadManager.download(item)
                done++
                if (result is DownloadResult.Failure) {
                    failed++
                    errors.add(u.substringAfterLast('/').take(20) + ": " + result.error)
                }
                notifyProgress(done, urls.size, failed)
            }
            // 完成通知
            val nm = getSystemService(NotificationManager::class.java)
            val doneNotif = NotificationCompat.Builder(this@DownloadService, App.CHANNEL_DOWNLOAD)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(
                    if (failed == 0) "下载完成：$done 个"
                    else "下载完成：${done - failed} 成功 / $failed 失败"
                )
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID + 1, doneNotif)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(done: Int, total: Int, failed: Int = 0): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (failed > 0)
            "下载中 $done/$total (失败 $failed)"
        else
            getString(R.string.download_running) + " ($done/$total)"
        return NotificationCompat.Builder(this, App.CHANNEL_DOWNLOAD)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, done == 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyProgress(done: Int, total: Int, failed: Int = 0) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(done, total, failed))
    }

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_TYPES = "types"
        const val NOTIF_ID = 1001
        const val TYPE_VIDEO = 1
        const val TYPE_IMAGE = 0
    }
}
