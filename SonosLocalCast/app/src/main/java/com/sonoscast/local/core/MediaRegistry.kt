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
    fun registerTrack(track: Track): MediaItem {
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
        _current.value = item
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

    private fun newFileId(uri: Uri): String {
        val raw = (uri.toString().hashCode().toLong() and 0xFFFFFFFFL) xor (System.nanoTime() shr 8)
        return java.lang.Long.toHexString(raw).padStart(8, '0').takeLast(12)
    }
}
