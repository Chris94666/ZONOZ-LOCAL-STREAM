package com.sonoscast.local.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "喜爱的音乐"收藏夹。
 *
 * 只存 `Track.id`，不复制整条曲目 —— 曲库重新扫描后 id 不变（MediaStore 的 _ID 稳定，
 * SAF 兜底条目用路径哈希也是稳定的），所以收藏不会因为刷新而丢失。
 *
 * 存 SharedPreferences 的字符串集合，进程重启后仍在。
 */
object Favorites {

    private const val PREFS = "sonoscast.prefs"
    private const val KEY_IDS = "favorite_track_ids"

    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    fun init(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet())
            .orEmpty()
        _ids.value = raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun isFavorite(trackId: Long): Boolean = trackId in _ids.value

    fun toggle(context: Context, trackId: Long): Boolean {
        val current = _ids.value
        val next = if (trackId in current) current - trackId else current + trackId
        _ids.value = next
        persist(context, next)
        return trackId in next
    }

    fun clear(context: Context) {
        _ids.value = emptySet()
        persist(context, emptySet())
    }

    private fun persist(context: Context, ids: Set<Long>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }
}
