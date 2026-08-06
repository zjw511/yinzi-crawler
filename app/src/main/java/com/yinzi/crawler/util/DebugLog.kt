package com.yinzi.crawler.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一调试日志工具。
 *
 * 功能：
 *  - 同时输出到 Logcat 和内存环形缓冲区
 *  - 内存缓冲区用于 UI 层「调试信息」面板展示，方便用户不用连电脑看 log
 *  - 每条日志带时间戳 + TAG + 调用方位置（类名:行号）
 *  - 敏感字段（Cookie、Token）自动脱敏
 */
object DebugLog {

    private const val TAG = "YinziCrawler"
    private const val BUFFER_SIZE = 300  // 最多保留最近 300 条

    /** 环形缓冲区：最近的 N 条日志，给 UI 层看 */
    private val buffer = ArrayDeque<String>(BUFFER_SIZE)
    private val lock = Any()

    /** 日志等级枚举 */
    enum class Level { D, I, W, E }

    data class Entry(val time: String, val level: Level, val tag: String, val msg: String)

    /** 监听：新日志追加时通知 UI */
    @Volatile
    var onLogAppend: ((String) -> Unit)? = null

    // ============== 对外 API ==============

    fun d(tag: String, msg: String) { log(Level.D, tag, msg, null) }
    fun d(msg: String) { log(Level.D, TAG, msg, null) }
    fun i(tag: String, msg: String) { log(Level.I, tag, msg, null) }
    fun i(msg: String) { log(Level.I, TAG, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) { log(Level.W, tag, msg, t) }
    fun w(msg: String, t: Throwable? = null) { log(Level.W, TAG, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { log(Level.E, tag, msg, t) }
    fun e(msg: String, t: Throwable? = null) { log(Level.E, TAG, msg, t) }

    /** 把 Throwable 的堆栈转成字符串 */
    fun throwableToString(t: Throwable?): String {
        if (t == null) return ""
        return t.stackTraceToString().take(1500)
    }

    /** 截取长字符串前 N 字符，用于日志打印 JSON 响应体 */
    fun truncate(s: String?, max: Int = 800): String {
        if (s == null) return "(null)"
        return if (s.length > max) s.take(max) + "\n...(截断，共${s.length}字符)" else s
    }

    /** 敏感字段脱敏 */
    fun maskCookie(cookie: String?): String {
        if (cookie.isNullOrBlank()) return "(空)"
        val keysToMask = setOf("acf_yb_t", "acf_yb_auth", "acf_jwt_token", "acf_dmjwt_token", "dy_did", "m_did")
        return cookie.split(';').joinToString("; ") { part ->
            val p = part.trim()
            val idx = p.indexOf('=')
            if (idx < 0) return@joinToString p
            val k = p.substring(0, idx).trim()
            val v = p.substring(idx + 1).trim()
            val masked = if (k in keysToMask && v.length > 6) v.take(4) + "****" + v.takeLast(2) else v
            "$k=$masked"
        }
    }

    // ============== 环形缓冲区（给 UI 展示） ==============

    /** 获取所有日志，按时间从旧到新 */
    fun getAllLogs(): List<String> = synchronized(lock) { buffer.toList() }

    /** 清空内存日志 */
    fun clear() = synchronized(lock) { buffer.clear() }

    /** 导出为完整字符串（复制用） */
    fun dumpText(): String = getAllLogs().joinToString("\n")

    // ============== 内部：写日志 ==============

    private fun log(level: Level, tag: String, rawMsg: String, t: Throwable?) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        // 调用方位置：往上找 3 帧就是调用方（跳过 DebugLog 自身 2 层）
        val caller = runCatching {
            val stacks = Thread.currentThread().stackTrace
            val frame = stacks.getOrNull(4) ?: stacks.getOrNull(3)
            frame?.let { "${it.simpleClassName}:${it.lineNumber}" } ?: ""
        }.getOrDefault("")

        val stackStr = throwableToString(t)
        val msg = if (stackStr.isBlank()) rawMsg else "$rawMsg\n$stackStr"

        // 1) 写 Logcat
        when (level) {
            Level.D -> Log.d(tag, msg)
            Level.I -> Log.i(tag, msg)
            Level.W -> Log.w(tag, msg)
            Level.E -> Log.e(tag, msg)
        }

        // 2) 写内存环形缓冲区
        val levelCh = when (level) {
            Level.D -> 'D'
            Level.I -> 'I'
            Level.W -> 'W'
            Level.E -> 'E'
        }
        val line = buildString {
            append(time).append(' ').append(levelCh).append('/').append(tag)
            if (caller.isNotBlank()) append('(').append(caller).append(')')
            append(" ➜ ").append(msg)
        }
        synchronized(lock) {
            while (buffer.size >= BUFFER_SIZE) buffer.removeFirst()
            buffer.addLast(line)
        }
        onLogAppend?.invoke(line)
    }

    private val StackTraceElement.simpleClassName: String
        get() = className.substringAfterLast('.').take(20)
}
