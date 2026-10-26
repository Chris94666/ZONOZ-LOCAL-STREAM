package com.sonoscast.local.core

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 一首待推送的本地音频。
 *
 * [fileId] 是映射到 SAF Uri 的短十六进制串，HTTP 服务器只暴露 fileId，
 * 不会把 content:// 或真实路径泄露给音箱。
 */
data class MediaItem(
    val fileId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val urlSuffix: String
) {
    val sizeLabel: String
        get() = when {
            sizeBytes <= 0L -> "大小未知"
            sizeBytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", sizeBytes / 1073741824.0)
            sizeBytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", sizeBytes / 1048576.0)
            else -> String.format(Locale.US, "%.0f KB", sizeBytes / 1024.0)
        }

    val supportsSeek: Boolean
        get() = sizeBytes > 0L
}

/**
 * fileId -> MediaItem 的进程内注册表。
 *
 * Service（HTTP 服务器）与 UI 共享同一个进程，用单例最省事；
 * 若将来要做多进程，需要换成 ContentProvider 或 AIDL。
 */
object MediaRegistry {

    private val items = ConcurrentHashMap<String, MediaItem>()

    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current.asStateFlow()

    fun get(fileId: String): MediaItem? = items[fileId]

    fun setCurrent(item: MediaItem?) {
        _current.value = item
    }

    /**
     * 从曲库条目直接注册。
     *
     * 元信息（标题/MIME/大小）扫描时已经拿到了，这里完全不做 IO ——
     * 用户点一下就能立刻推给音箱。真正的文件读取发生在音箱回拉 HTTP 的那一刻。
     */
    fun registerTrack(track: Track): MediaItem = registerTrack(track, makeCurrent = true)

    /**
     * 批量建队列时用：只登记，**不改变"当前曲"**。
     *
     * 1.0.3 的 bug：建 72 首队列时调的是 registerTrack()，它会把 _current 改成
     * 最后一首；一旦队列路径失败退回单曲推送，推出去的就是那一首 ——
     * 用户点 A、出声是 C。批量场景必须用这个。
     */
    fun registerTrackSilently(track: Track): MediaItem = registerTrack(track, makeCurrent = false)

    private fun registerTrack(track: Track, makeCurrent: Boolean): MediaItem {
        val fileId = newFileId(track.uri)
        val item = MediaItem(
            fileId = fileId,
            uri = track.uri,
            displayName = track.title,
            mimeType = track.mimeType,
            sizeBytes = track.sizeBytes,
            urlSuffix = MimeTypes.urlSuffix(track.mimeType)
        )
        items[fileId] = item
        if (makeCurrent) _current.value = item
        return item
    }

    /**
     * 把用户选中的 SAF Uri 注册进来。
     * 返回 null 表示该 Uri 无法读取（已被删除 / 权限丢失）。
     */
    suspend fun register(context: Context, uri: Uri): MediaItem? = withContext(Dispatchers.IO) {
        // 尽量把读权限持久化，避免进程重启后失效
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = -1L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        if (size <= 0L) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd -> afd.length }
            }.getOrNull() ?: -1L
        }

        // 探一次能否真正打开，避免把坏 Uri 推给音箱后拿到难懂的 716
        val readable = runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) {
            LogBus.e("无法读取所选文件：$uri")
            return@withContext null
        }

        val mime = MimeTypes.resolve(context.contentResolver.getType(uri), displayName)
        val fileId = newFileId(uri)

        val item = MediaItem(
            fileId = fileId,
            uri = uri,
            displayName = displayName,
            mimeType = mime,
            sizeBytes = size,
            urlSuffix = MimeTypes.urlSuffix(mime)
        )
        items[fileId] = item
        _current.value = item
        LogBus.i("已载入音频：${item.displayName} · ${item.sizeLabel} · $mime")
        item
    }

    /**
     * 由 Uri 派生的**稳定**短 ID（12 位十六进制）。
     *
     * 1.0.3 的 bug：这里曾经混入 `System.nanoTime()`，
     * 于是**同一首歌每次注册都得到不同的 fileId**：
     * - 同一批入队里，"期望 ID"和"实际入队 ID"永远不相等 → 校验必然失败；
     * - 重建队列后旧 URL 全部 404；
     * - items 映射无限增长。
     * 现在改为对 Uri 做 MD5 取前 12 位：同一首歌永远是同一个 ID。
     */
    private fun newFileId(uri: Uri): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(12)
        for (i in 0 until 6) {
            sb.append(String.format(Locale.US, "%02x", digest[i]))
        }
        return sb.toString()
    }
}
