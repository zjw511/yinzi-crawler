package com.yinzi.crawler

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.yinzi.crawler.network.Net
import com.yinzi.crawler.util.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Net.init()
        createDownloadChannel()
    }

    private fun createDownloadChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_DOWNLOAD,
            getString(R.string.notification_channel_download),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "download"
        lateinit var instance: App
            private set
    }
}
