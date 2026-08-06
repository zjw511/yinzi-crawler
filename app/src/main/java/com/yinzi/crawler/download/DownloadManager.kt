package com.yinzi.crawler.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yinzi.crawler.App
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.network.Net
import com.yinzi.crawler.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 下载结果
 */
sealed class DownloadResult {
    data class Success(val uri: Uri, val path: String) : DownloadResult()
    data class Failure(val error: String) : DownloadResult()
}

data class DownloadProgress(val url: String, val percent: Int)

/**
 * 简易下载器：
 * - 图片存到 MediaStore.Images，视频存到 MediaStore.Video
 * - Android 10+ 用 MediaStore，旧版本直接写公共目录
 * - v1.2：加重试(3次)、加大超时(60s)、实时进度上报
 */
object DownloadManager {

    private const val MAX_RETRY = 3

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    /** 正在下载中的 URL 集合，防止重复下载 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 进度更新锁，防止并发写 _progress 丢数据 */
    private val progressLock = Any()

    /** 下载专用的 OkHttpClient：超时更长（大视频需要） */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun setProgress(url: String, percent: Int) {
        synchronized(progressLock) {
            _progress.value = _progress.value + (url to percent)
        }
    }

    private fun clearProgress(url: String) {
        synchronized(progressLock) {
            _progress.value = _progress.value - url
        }
    }

    suspend fun download(item: MediaItem): DownloadResult = withContext(Dispatchers.IO) {
        if (item.url.isBlank()) {
            return@withContext DownloadResult.Failure("URL 为空，无法下载")
        }
        if (Prefs.isDownloaded(item.url)) {
            return@withContext DownloadResult.Success(Uri.EMPTY, "already downloaded")
        }
        // 防止重复下载
        if (!inFlight.add(item.url)) {
            return@withContext DownloadResult.Success(Uri.EMPTY, "already downloading")
        }

        try {
            // m3u8 → 下载所有 ts 切片并合并
            if (item.url.contains(".m3u8")) {
                val result = downloadM3u8(item)
                return@withContext result
            }

            var lastError = ""
            for (attempt in 1..MAX_RETRY) {
                if (attempt > 1) {
                    setProgress(item.url, 0)
                }
                val result = downloadOnce(item, attempt)
                if (result is DownloadResult.Success) return@withContext result
                lastError = (result as DownloadResult.Failure).error
                clearProgress(item.url)
            }
            DownloadResult.Failure(lastError)
        } finally {
            inFlight.remove(item.url)
            clearProgress(item.url)
        }
    }

    /** 下载 m3u8：解析播放列表 → 下载所有 ts 切片 → 合并成单个 mp4 文件 */
    private suspend fun downloadM3u8(item: MediaItem): DownloadResult = withContext(Dispatchers.IO) {
        try {
            setProgress(item.url, 0)

            // 1) 下载 m3u8 播放列表
            val req = Request.Builder().url(item.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile")
                .header("Referer", "https://v.douyu.com/")
                .build()
            val resp = downloadClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                clearProgress(item.url)
                return@withContext DownloadResult.Failure("m3u8 HTTP ${resp.code}")
            }
            val m3u8Text = resp.body?.string() ?: ""
            if (m3u8Text.isBlank()) {
                clearProgress(item.url)
                return@withContext DownloadResult.Failure("m3u8 内容为空")
            }

            // 2) 解析 ts 切片 URL
            val baseUrl = item.url.substringBeforeLast('/')
            val tsUrls = m3u8Text.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    if (line.startsWith("http")) line
                    else if (line.startsWith("//")) "https:$line"
                    else "$baseUrl/$line"
                }

            if (tsUrls.isEmpty()) {
                clearProgress(item.url)
                return@withContext DownloadResult.Failure("m3u8 里没有 ts 切片")
            }

            // 3) 下载所有 ts 切片到临时文件
            val displayName = fileName(item)
            val tmpDir = File(App.instance.cacheDir, "m3u8_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val tsFiles = mutableListOf<File>()

            for ((i, tsUrl) in tsUrls.withIndex()) {
                val tsFile = File(tmpDir, "segment_${String.format("%05d", i)}.ts")
                val tsReq = Request.Builder().url(tsUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile")
                    .header("Referer", "https://v.douyu.com/")
                    .build()
                val tsResp = downloadClient.newCall(tsReq).execute()
                if (!tsResp.isSuccessful) {
                    clearProgress(item.url)
                    tmpDir.deleteRecursively()
                    return@withContext DownloadResult.Failure("ts[${i}] HTTP ${tsResp.code}")
                }
                tsResp.body?.byteStream()?.use { input ->
                    tsFile.outputStream().use { output -> input.copyTo(output) }
                }
                tsFiles.add(tsFile)
                val p = ((i + 1) * 100 / tsUrls.size).toInt().coerceIn(0, 99)
                setProgress(item.url, p)
            }

            // 4) 合并所有 ts → 单个 mp4 文件
            setProgress(item.url, 99)
            val mime = "video/mp4"
            val savedUri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/YinziCrawler/Videos")
                }
                val resolver = App.instance.contentResolver
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(collection, cv)
                    ?: return@withContext DownloadResult.Failure("MediaStore insert null")
                resolver.openOutputStream(uri)?.use { out ->
                    tsFiles.forEach { f -> f.inputStream().use { it.copyTo(out) } }
                    out.flush()
                } ?: return@withContext DownloadResult.Failure("openOutputStream null")
                savedUri = uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YinziCrawler")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                file.outputStream().use { out ->
                    tsFiles.forEach { f -> f.inputStream().use { it.copyTo(out) } }
                    out.flush()
                }
                savedUri = Uri.fromFile(file)
            }

            // 5) 清理临时文件
            tmpDir.deleteRecursively()

            setProgress(item.url, 100)
            Prefs.markDownloaded(item.url)
            kotlinx.coroutines.delay(500)
            clearProgress(item.url)
            DownloadResult.Success(savedUri, displayName)
        } catch (e: Exception) {
            clearProgress(item.url)
            DownloadResult.Failure("m3u8: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun downloadOnce(item: MediaItem, attempt: Int): DownloadResult = withContext(Dispatchers.IO) {
        try {
            setProgress(item.url, 0)

            val req = Request.Builder().url(item.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile")
                .header("Referer", "https://yuba.douyu.com/")
                .build()
            val resp = downloadClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                clearProgress(item.url)
                return@withContext DownloadResult.Failure("HTTP ${resp.code}")
            }
            val body = resp.body ?: return@withContext DownloadResult.Failure("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            val displayName = fileName(item)
            val mime = if (item.isVideo) "video/mp4" else "image/jpeg"

            val sink: OutputStream
            val savedUri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    val sub = if (item.isVideo) "YinziCrawler/Videos" else "YinziCrawler/Images"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$sub")
                }
                val resolver = App.instance.contentResolver
                val collection = if (item.isVideo)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(collection, cv)
                    ?: return@withContext DownloadResult.Failure("MediaStore insert null")
                sink = resolver.openOutputStream(uri)
                    ?: return@withContext DownloadResult.Failure("openOutputStream null")
                savedUri = uri
            } else {
                @Suppress("DEPRECATION")
                val dir = if (item.isVideo)
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YinziCrawler")
                else
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "YinziCrawler")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                sink = FileOutputStream(file)
                savedUri = Uri.fromFile(file)
            }

            sink.use { out ->
                val source = body.source()
                val buf = ByteArray(32 * 1024)
                var read: Int
                var done = 0L
                var lastReport = 0L
                while (true) {
                    read = source.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    done += read
                    // 每 100KB 上报一次进度，避免 flow 刷新太频繁
                    if (done - lastReport > 100 * 1024) {
                        lastReport = done
                        val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99)
                        else -1  // 未知大小 → indeterminate
                        setProgress(item.url, p)
                    }
                }
                out.flush()
            }
            setProgress(item.url, 100)
            Prefs.markDownloaded(item.url)
            kotlinx.coroutines.delay(500)
            clearProgress(item.url)
            DownloadResult.Success(savedUri, displayName)
        } catch (e: Exception) {
            clearProgress(item.url)
            DownloadResult.Failure("${e.message ?: e.javaClass.simpleName} (attempt $attempt/$MAX_RETRY)")
        }
    }

    private fun fileName(item: MediaItem): String {
        // 从 URL 取扩展名，没有就默认
        val ext = when {
            item.isVideo -> ".mp4"
            item.url.lowercase().let { e ->
                listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { e.contains(it) }
            } -> ""
            else -> ".jpg"
        }
        val base = "yinzi_${System.currentTimeMillis()}_${item.url.hashCode().toString().replace("-", "0")}"
        return base + ext
    }
}
