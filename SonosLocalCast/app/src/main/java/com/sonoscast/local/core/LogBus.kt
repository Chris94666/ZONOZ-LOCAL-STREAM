package com.sonoscast.local.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志级别。
 * [TX] / [RX] 专用于 SOAP 请求与响应的原始报文，便于在 S2 固件下排查错误码。
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, TX, RX
}

data class LogEntry(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val message: String
)

/**
 * 进程内环形日志总线。
 *
 * 设计取舍：
 * - 用 object 单例而不是通过 Binder 回传，避免 Activity 与 Service 之间的绑定生命周期问题，
 *   也让 Compose 可以直接 collectAsState 而无需关心 Service 是否已连接。
 * - 上限 [MAX_ENTRIES] 条，避免长时间播放把内存吃满。
 */
object LogBus {

    private const val MAX_ENTRIES = 500
    private const val TAG = "SonosCast"

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val counter = AtomicLong(0L)
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(
            id = counter.incrementAndGet(),
            time = LocalTime.now().format(timeFormatter),
            level = level,
            message = message
        )
        _entries.update { old ->
            if (old.size >= MAX_ENTRIES) {
                // 丢掉最旧的一条，保持写入开销恒定
                old.subList(old.size - MAX_ENTRIES + 1, old.size).toList() + entry
            } else {
                old + entry
            }
        }
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.TX -> Log.d(TAG, "TX $message")
            LogLevel.RX -> Log.d(TAG, "RX $message")
            else -> Log.i(TAG, message)
        }
    }

    fun d(message: String) = log(LogLevel.DEBUG, message)
    fun i(message: String) = log(LogLevel.INFO, message)
    fun w(message: String) = log(LogLevel.WARN, message)
    fun e(message: String) = log(LogLevel.ERROR, message)
    fun tx(message: String) = log(LogLevel.TX, message)
    fun rx(message: String) = log(LogLevel.RX, message)

    fun clear() {
        _entries.value = emptyList()
    }
}
