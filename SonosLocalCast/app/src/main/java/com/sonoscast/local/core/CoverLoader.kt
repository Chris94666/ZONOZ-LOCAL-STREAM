package com.sonoscast.local.core

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专辑封面加载器。
 *
 * Android 10+ 用 ContentResolver.loadThumbnail()，它会自己走缩略图缓存；
 * 10 以下退回老的 albumart content URI。结果再进一层 LruCache，
 * 避免列表快速滚动时反复解码。
 *
 * 媒体库没索引的文件（file:// 直读条目）没有 albumId，只能读内嵌封面。
 */
object CoverLoader {

    private val cache = LruCache<Long, Bitmap>(40)

    /** 同步取一次（可能为空），用于首帧就画上去，避免闪烁 */
    fun peek(track: Track?): Bitmap? = track?.let { cache.get(it.id) }

    suspend fun load(context: Context, track: Track): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(track.id)?.let { return@withContext it }

        val bitmap = runCatching {
            if (track.uri.scheme == "file") {
                embeddedPicture(track.uri.path)
            } else {
                // 媒体库条目优先用系统缩略图（带缓存）；SAF 直读条目没有 albumId，
                // loadThumbnail 通常拿不到，失败后退回内嵌封面。
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(track.uri, Size(640, 640), null)
                    } else {
                        val albumUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            track.albumId
                        )
                        context.contentResolver.openInputStream(albumUri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                }.getOrNull() ?: embeddedPicture(context, track.uri)
            }
        }.getOrNull()

        if (bitmap != null) cache.put(track.id, bitmap)
        bitmap
    }

    /** 从音频文件的内嵌标签里取封面（ID3 APIC / FLAC METADATA_BLOCK_PICTURE 都走这里） */
    private fun embeddedPicture(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 从 content:// URI 取内嵌封面（SAF 直读条目没有 albumId，只能走这里） */
    private fun embeddedPicture(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
