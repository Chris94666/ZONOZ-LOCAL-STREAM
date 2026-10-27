package com.sonoscast.local.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonoscast.local.core.AppState
import com.sonoscast.local.core.CoverLoader
import com.sonoscast.local.core.DeviceStatus
import com.sonoscast.local.core.Favorites
import com.sonoscast.local.core.Hint
import com.sonoscast.local.core.LogBus
import com.sonoscast.local.core.MediaRegistry
import com.sonoscast.local.core.RepeatMode
import com.sonoscast.local.core.MusicLibrary
import com.sonoscast.local.core.ServicePhase
import com.sonoscast.local.core.SortMode
import com.sonoscast.local.core.SonosDevice
import com.sonoscast.local.core.Str
import com.sonoscast.local.core.Track
import com.sonoscast.local.core.UiScale
import com.sonoscast.local.service.SonosCastService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel。
 *
 * 状态依旧是单向的：Service 写 [AppState]，这里只做转发和指令下发。
 * 曲库是独立的 [MusicLibrary] 单例，所以 Activity 重建不会重新扫库。
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ---------------------------------------------------------------- 服务态
    val phase: StateFlow<ServicePhase> = AppState.phase
    val localIp: StateFlow<String?> = AppState.localIp
    val serverPort: StateFlow<Int> = AppState.serverPort
    val devices: StateFlow<List<SonosDevice>> = AppState.devices
    val selectedKeys: StateFlow<Set<String>> = AppState.selectedKeys
    val scanning: StateFlow<Boolean> = AppState.scanning
    val busy: StateFlow<Boolean> = AppState.busy
    val volume: StateFlow<Int> = AppState.volume
    val deviceStatus: StateFlow<Map<String, DeviceStatus>> = AppState.deviceStatus
    /**
     * 待展示的提示。
     *
     * 存的是 [Hint]（文案键 + 参数）而不是成品字符串：切语言时提示会跟着变，
     * 由 UI 侧的 `str()` / `strf()` 在组合期解析。
     */
    val hint: StateFlow<Hint?> = AppState.hint
    val logs = LogBus.entries
    val positionSec = AppState.positionSec
    val durationSec = AppState.durationSec
    val streamUrl = AppState.streamUrl
    val nowPlaying: StateFlow<String?> = AppState.nowPlayingTitle

    // ---------------------------------------------------------------- 曲库
    val tracks: StateFlow<List<Track>> = MusicLibrary.tracks
    val libraryScanning: StateFlow<Boolean> = MusicLibrary.scanning
    val libraryLoaded: StateFlow<Boolean> = MusicLibrary.loaded
    val libraryError: StateFlow<String?> = MusicLibrary.error
    val query: StateFlow<String> = MusicLibrary.query

    // ---------------------------------------------------------------- 当前曲
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _cover = MutableStateFlow<Bitmap?>(null)
    val cover: StateFlow<Bitmap?> = _cover.asStateFlow()

    /** 任意一台主箱处于 PLAYING 就算"正在播" */
    val isPlaying: StateFlow<Boolean> = combine(AppState.deviceStatus, AppState.devices) { status, list ->
        list.any { status[it.key] == DeviceStatus.PLAYING }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 随机播放开关 */
    val shuffle: StateFlow<Boolean> = AppState.shuffle

    /** 循环方式：列表循环 / 单曲循环 */
    val repeatMode: StateFlow<RepeatMode> = AppState.repeatMode

    /** 播放清单（播放顺序；开启随机后即为打乱后的顺序） */
    val queue: StateFlow<List<Track>> = AppState.queue

    /** 清单里当前播放到第几首 */
    val queueIndex: StateFlow<Int> = AppState.queueIndex

    /** 当前曲之后还剩几首 */
    fun upcomingCount(): Int = AppState.upcomingCount()

    // ---------------------------------------------------------------- 收藏夹

    /** 已收藏的曲目 id 集合 */
    val favoriteIds: StateFlow<Set<Long>> = Favorites.ids

    private val _favoritesOnly = MutableStateFlow(false)

    /** 曲库是否处于"只看喜爱的音乐"筛选 */
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    fun toggleFavorite(track: Track): Boolean = Favorites.toggle(app, track.id)

    fun isFavorite(track: Track): Boolean = Favorites.isFavorite(track.id)

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    /** 当前限定的音乐文件夹（null = 全部音乐） */
    val folderLabel: StateFlow<String?> = MusicLibrary.folderLabel

    /** 音乐文件夹在安卓文件系统里的完整路径，如 /storage/emulated/0/Music */
    val folderPath: StateFlow<String?> = MusicLibrary.folderPrefix

    /** 曲库排序方式 */
    val sortMode: StateFlow<SortMode> = MusicLibrary.sortMode

    fun setSortMode(mode: SortMode) = MusicLibrary.setSortMode(mode)

    /** 切换整体文字大小（乘在 fontScale 上，sp 全量生效） */
    fun setFontScale(scale: Float) = UiScale.set(app, scale)

    /** 切换界面语言（持久化，下次启动仍生效） */
    fun setLang(lang: com.sonoscast.local.core.Lang) =
        com.sonoscast.local.core.I18n.set(app, lang)

    /** 拖动进度条跳转 */
    fun seekTo(seconds: Int) = SonosCastService.seek(app, seconds)

    /**
     * 选定音乐文件夹：把 SAF tree URI 还原成文件路径前缀，存起来并重扫。
     *
     * 不遍历 SAF 而是回头过滤 MediaStore —— 这样专辑封面、时长、比特率都还在，
     * 扫描速度也不受目录大小影响。
     */
    fun setMusicFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val prefix = MusicLibrary.treeUriToPath(uri)
            if (prefix == null) {
                LogBus.w("无法解析所选文件夹的路径，筛选未生效")
                AppState.hint.value = Hint(Str.ERR_FOLDER)
                return@launch
            }
            val label = MusicLibrary.labelOf(uri)
            LogBus.i("音乐文件夹 → $prefix")
            // tree URI 必须一起存下来：媒体库缺索引的新歌只能靠 SAF 枚举发现
            MusicLibrary.setFolder(app, prefix, label, uri.toString())
            MusicLibrary.scan(app)
        }
    }

    fun clearMusicFolder() {
        MusicLibrary.setFolder(app, null, null, null)
        viewModelScope.launch { MusicLibrary.scan(app) }
    }

    /**
     * 长按"下一首播放"。
     *
     * 当前有歌在播/暂停 → 插到播放清单里当前曲的后面；
     * 完全没在播 → 直接在所在列表里建清单并开播。
     */
    fun playNext(track: Track, source: List<Track> = MusicLibrary.tracks.value) {
        val active = AppState.deviceStatus.value.values.any {
            it == DeviceStatus.PLAYING || it == DeviceStatus.PAUSED
        }
        if (!active) {
            playTrack(track, source)
            return
        }
        if (AppState.insertNext(track)) {
            LogBus.i("已排到下一首：《${track.title}》")
            // 只改 App 的清单不够：音箱里那份队列也得跟上，否则两边顺序错开，
            // 放完当前曲后"声音"和"界面"会永久差一位（1.1.1 的真 bug）。
            // 移动已有曲目时音箱里还留着旧副本，只能重建；新曲目可以原生插队。
            SonosCastService.queueChanged(app, track.id, AppState.lastInsertWasMove)
        } else {
            LogBus.d("《${track.title}》已在播放清单里")
        }
    }

    private val app: Application
        get() = getApplication<Application>()

    init {
        // Service 侧切歌（下一首/自动续播）只更新 MediaRegistry，
        // 这里反向跟随，保证 UI 的封面和标题永远和真正在播的一致。
        viewModelScope.launch {
            MediaRegistry.current.collect { item ->
                if (item == null) return@collect
                if (item.uri == _currentTrack.value?.uri) return@collect
                val track = MusicLibrary.tracks.value.firstOrNull { it.uri == item.uri }
                    ?: return@collect
                _currentTrack.value = track
                _cover.value = CoverLoader.peek(track)
                if (_cover.value == null) {
                    _cover.value = CoverLoader.load(app, track)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 服务指令
    fun ensureService() = SonosCastService.start(app)

    fun scanDevices() = SonosCastService.scan(app)

    fun toggleSelection(key: String) = AppState.toggleSelection(key)

    fun selectOnly(key: String) = AppState.setSelection(setOf(key))

    fun addManualDevice(ip: String) {
        if (ip.isNotBlank()) SonosCastService.addManualDevice(app, ip.trim())
    }

    fun dismissHint() {
        AppState.hint.value = null
    }

    fun clearLogs() = LogBus.clear()

    fun shutdown() = SonosCastService.shutdown(app)

    // ---------------------------------------------------------------- 播放
    fun scanLibrary() {
        viewModelScope.launch { MusicLibrary.scan(app) }
    }

    fun setQuery(text: String) = MusicLibrary.setQuery(text)

    fun visibleTracks(): List<Track> = MusicLibrary.filteredTracks()

    /**
     * 点一首歌：以"用户点歌时所在的列表"建立播放清单，再推给音箱。
     *
     * [source] 传当前可见列表（曲库/搜索结果），这样播放清单就是用户眼前这份顺序；
     * 随机开启时列表会被打乱，当前曲置顶。
     */
    fun playTrack(track: Track, source: List<Track> = MusicLibrary.tracks.value) {
        _currentTrack.value = track
        _cover.value = CoverLoader.peek(track)
        AppState.startQueue(source.ifEmpty { listOf(track) }, track)
        MediaRegistry.registerTrack(track)
        SonosCastService.cast(app)
        viewModelScope.launch {
            if (_cover.value == null) {
                _cover.value = CoverLoader.load(app, track)
            }
        }
    }

    /** 播放清单里点某一首：直接把指针移过去再推 */
    fun playFromQueue(track: Track) {
        _currentTrack.value = track
        _cover.value = CoverLoader.peek(track)
        AppState.setQueueTrack(track)
        MediaRegistry.registerTrack(track)
        SonosCastService.cast(app)
        viewModelScope.launch {
            if (_cover.value == null) {
                _cover.value = CoverLoader.load(app, track)
            }
        }
    }

    fun next() = SonosCastService.next(app)

    fun previous() = SonosCastService.previous(app)

    fun toggleShuffle() = SonosCastService.toggleShuffle(app)

    fun cycleRepeat() = SonosCastService.cycleRepeat(app)

    fun togglePlay() {
        if (isPlaying.value) SonosCastService.pause(app) else SonosCastService.resume(app)
    }

    fun stop() = SonosCastService.stopPlayback(app)

    fun setVolume(value: Int) = SonosCastService.setVolume(app, value)

    fun clearCurrent() {
        _currentTrack.value = null
        _cover.value = null
    }
}
