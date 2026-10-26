package com.sonoscast.local.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 曲库里的一个条目。
 *
 * 这里只保存 MediaStore 的**元信息**，不持有任何文件流 —— 点播时才通过 [uri] 打开。
 * 一首 5MB 的歌在列表里只占几百字节，扫几千首也不会有内存压力。
 */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val albumId: Long,
    /** 真实文件路径（MediaStore.DATA）。Android 10+ 虽已废弃，但本机媒体仍可取到，用于展示文件信息 */
    val path: String = "",
    /** 加入媒体库时间，epoch 秒 */
    val dateAddedSec: Long = 0L,
    /** 最后修改时间，epoch 秒 */
    val dateModifiedSec: Long = 0L,
    /** 比特率 bps，0 表示未知（API 31+ 才有该列） */
    val bitrate: Int = 0,
    /** 采样率 Hz，0 表示未知 */
    val sampleRate: Int = 0
) {
    val durationLabel: String
        get() = formatDuration(durationMs)

    /** 文件名（带扩展名）。MediaStore 没给路径时退回标题。 */
    val fileName: String
        get() = path.substringAfterLast('/').ifBlank { title }

    val sizeLabel: String
        get() = when {
            sizeBytes <= 0L -> I18n.t(Str.UNKNOWN)
            sizeBytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", sizeBytes / 1073741824.0)
            sizeBytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", sizeBytes / 1048576.0)
            else -> String.format(Locale.US, "%.0f KB", sizeBytes / 1024.0)
        }

    val subtitle: String
        get() = when {
            artist.isBlank() || artist.equals("<unknown>", true) -> album.ifBlank { I18n.t(Str.UNKNOWN_ALBUM) }
            album.isBlank() -> artist
            else -> "$artist · $album"
        }

    val isPlayable: Boolean
        get() = sizeBytes > 0L

    companion object {
        fun formatDuration(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }
    }
}

/**
 * 曲库排序方式。
 * 只有 [TITLE] 会启用右侧 A-Z 索引条 —— 其余排序下字母顺序没有意义。
 */
enum class SortMode(val labelKey: Str) {
    TITLE(Str.SORT_TITLE),
    DATE_ADDED(Str.SORT_ADDED),
    SIZE(Str.SORT_SIZE),
    DURATION(Str.SORT_DURATION)
}

/**
 * 系统媒体库扫描器。
 *
 * 为什么不自己遍历文件夹：
 * - Android 10+ 的分区存储下，直接读 /sdcard 需要 MANAGE_EXTERNAL_STORAGE（敏感权限）；
 * - MediaStore 是系统维护的索引，速度、增量更新、专辑封面都由它兜底。
 *
 * 用户所说的"音乐文件夹"，在 MediaStore 里就是 IS_MUSIC 的那些条目。
 */
object MusicLibrary {

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // ---------------------------------------------------------- 排序

    private val _sortMode = MutableStateFlow(SortMode.TITLE)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** 过滤后的全集（未排序），切换排序时基于它重排 */
    private var baseList: List<Track> = emptyList()

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value == mode) return
        _sortMode.value = mode
        _tracks.value = applySort(baseList)
    }

    private fun applySort(list: List<Track>): List<Track> = when (_sortMode.value) {
        // 先按"拼音首字母"分组，再在组内用 ICU 中文排序规则比较。
        // 只按码位排的话，所有汉字都会堆到 Z 后面（1.0 之前就是这样）。
        SortMode.TITLE -> {
            val collator = java.text.Collator.getInstance(Locale.CHINA)
            list.sortedWith(
                compareBy<Track> { letterRank(indexLetter(it.title)) }
                    .thenComparator { a, b -> collator.compare(a.title, b.title) }
            )
        }

        SortMode.DATE_ADDED -> list.sortedByDescending { it.dateAddedSec }
        SortMode.SIZE -> list.sortedByDescending { it.sizeBytes }
        SortMode.DURATION -> list.sortedByDescending { it.durationMs }
    }

    /** A→0 … Z→25，#→最后 */
    private fun letterRank(c: Char): Int = if (c == '#') 99 else (c - 'A')

    /**
     * 曲目归到哪个字母分组（A-Z / #）。
     *
     * 汉字按**拼音首字母**归组：「晴天」→ Q、「黄昏」→ H、「后来」→ H。
     * 实现不依赖拼音字典，也不需要 ICU：
     * GB2312 的**一级汉字区**（0xB0A1–0xD7F9，3755 个常用字）本身就按拼音顺序编码，
     * 把字转成 GBK 字节后落在哪个区间，就能反推首字母。
     * 生僻字（二级汉字 / 扩展区）推不出来时归到 #。
     */
    fun indexLetter(title: String): Char {
        val first = title.trimStart().firstOrNull() ?: return '#'
        if (first in 'A'..'Z') return first
        if (first in 'a'..'z') return first.uppercaseChar()
        return hanInitial(first) ?: '#'
    }

    private fun hanInitial(ch: Char): Char? {
        if (ch.code !in 0x4E00..0x9FFF) return null
        val bytes = runCatching { ch.toString().toByteArray(charset("GBK")) }.getOrNull() ?: return null
        if (bytes.size != 2) return null
        val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (code < 0xB0A1 || code > 0xD7F9) return null

        var letter = 'A'
        for ((candidate, start) in GB_PINYIN_BOUNDARIES) {
            if (code >= start) letter = candidate else break
        }
        return letter
    }

    /** 一级汉字区里各拼音首字母的起始编码（GB2312；拼音没有 I / U / V） */
    private val GB_PINYIN_BOUNDARIES = listOf(
        'A' to 0xB0A1, 'B' to 0xB0C5, 'C' to 0xB2C1, 'D' to 0xB4EE, 'E' to 0xB6EA,
        'F' to 0xB7A2, 'G' to 0xB8C1, 'H' to 0xB9FE, 'J' to 0xBBF7, 'K' to 0xBFA6,
        'L' to 0xC0AC, 'M' to 0xC2E8, 'N' to 0xC4C3, 'O' to 0xC5B6, 'P' to 0xC5BE,
        'Q' to 0xC6DA, 'R' to 0xC8BB, 'S' to 0xC8F6, 'T' to 0xCBFA, 'W' to 0xCDDA,
        'X' to 0xCEF4, 'Y' to 0xD1B9, 'Z' to 0xD4D1
    )

    /**
     * 当前列表的 A-Z 索引：字母 -> 该字母第一首在列表中的位置。
     * 中文按拼音归组，数字/符号归到 #（永远排最后）。
     */
    fun sectionIndex(list: List<Track>): LinkedHashMap<Char, Int> {
        val map = LinkedHashMap<Char, Int>()
        list.forEachIndexed { index, track ->
            val key = indexLetter(track.title)
            if (key !in map) map[key] = index
        }
        return LinkedHashMap(map).apply {
            val hash = remove('#')
            if (hash != null) put('#', hash)
        }
    }

    // ---------------------------------------------------------- 音乐文件夹

    private const val PREFS = "sonoscast.prefs"
    private const val KEY_FOLDER_PREFIX = "music_folder_prefix"
    private const val KEY_FOLDER_LABEL = "music_folder_label"
    private const val KEY_FOLDER_TREE = "music_folder_tree_uri"

    /** 外部存储的 DocumentsProvider authority */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private val _folderPrefix = MutableStateFlow<String?>(null)
    val folderPrefix: StateFlow<String?> = _folderPrefix.asStateFlow()

    private val _folderLabel = MutableStateFlow<String?>(null)
    val folderLabel: StateFlow<String?> = _folderLabel.asStateFlow()

    /** 用户授权过的 SAF 目录树。枚举目录必须走它 —— 见 [resolveFolderTree] 的说明 */
    private val _folderTree = MutableStateFlow<String?>(null)

    fun restoreFolder(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _folderPrefix.value = p.getString(KEY_FOLDER_PREFIX, null)
        _folderLabel.value = p.getString(KEY_FOLDER_LABEL, null)
        _folderTree.value = p.getString(KEY_FOLDER_TREE, null)
    }

    fun setFolder(context: Context, prefix: String?, label: String?, treeUri: String? = null) {
        _folderPrefix.value = prefix
        _folderLabel.value = label
        _folderTree.value = treeUri
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_PREFIX, prefix)
            .putString(KEY_FOLDER_LABEL, label)
            .putString(KEY_FOLDER_TREE, treeUri)
            .apply()
    }

    /**
     * 用来枚举目录的 SAF tree URI。
     *
     * 为什么必须用 SAF 而不是 `File.listFiles()`：
     * Android 11+ 的分区存储对 App 是"过滤视图"——没进 MediaStore 的文件，
     * 连目录列举都看不见（实测：新拷进来的 mp3 在 MediaStore 里没有，`listFiles()` 也返回不到）。
     * DocumentsProvider 跑在系统侧，能看到真实目录，所以它才是发现新歌的正确入口。
     *
     * 老版本安装没存过 tree URI，就用路径反推：ExternalStorageProvider 的 docId 规则是固定的，
     * `/storage/emulated/0/Music` → `primary:Music`。
     */
    private fun resolveFolderTree(): Uri? {
        _folderTree.value?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { return it }
        }
        val prefix = _folderPrefix.value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (!prefix.startsWith(base)) return@runCatching null
            val relative = prefix.removePrefix(base).trim('/')
            val docId = if (relative.isBlank()) "primary:" else "primary:$relative"
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        }.getOrNull()
    }

    /**
     * 把 SAF 的 tree URI 还原成真实文件路径前缀。
     *
     * tree URI 形如 `content://com.android.externalstorage.documents/tree/primary%3AMusic`，
     * `getTreeDocumentId` 会给出 `primary:Music`，映射到 `/storage/emulated/0/Music`。
     * 这样我们不用遍历 SAF，直接拿它去过滤 MediaStore 结果 —— 元信息、封面都还在。
     */
    @Suppress("DEPRECATION")
    fun treeUriToPath(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return@runCatching null
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return@runCatching null
        val volume = parts[0]
        val relative = parts[1].trim('/')
        val base = if (volume.equals("primary", true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        if (relative.isBlank()) base else "$base/$relative"
    }.getOrNull()

    fun labelOf(uri: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri).orEmpty()
        docId.split(":", limit = 2).getOrNull(1)?.trim('/')?.ifBlank { I18n.t(Str.ROOT_DIR) }
            ?: docId.ifBlank { I18n.t(Str.FOLDER_PICKED) }
    }.getOrDefault(I18n.t(Str.FOLDER_PICKED))

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        requiredPermission()
    ) == PackageManager.PERMISSION_GRANTED

    fun setQuery(text: String) {
        _query.value = text
    }

    /** 按当前搜索词过滤后的列表 */
    fun filteredTracks(): List<Track> {
        val q = _query.value.trim()
        if (q.isEmpty()) return _tracks.value
        return _tracks.value.filter { t ->
            t.title.contains(q, true) ||
                t.artist.contains(q, true) ||
                t.album.contains(q, true)
        }
    }

    suspend fun scan(context: Context): Int = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) {
            _error.value = I18n.t(Str.NO_AUDIO_PERMISSION)
            _loaded.value = true
            return@withContext 0
        }

        _scanning.value = true
        try {
            // ① 先自愈媒体库：把"文件夹里有、媒体库里没有"的文件找出来并补扫。
            //    枚举必须走 SAF —— 分区存储下 File.listFiles() 看不到未入库文件。
            val candidates = collectFolderAudio(context)
            val unindexed = rescanMissing(context, candidates)

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.IS_MUSIC,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            // BITRATE 是 Android 12（API 31）才加的列，低版本放进投影会直接抛异常
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                projection += MediaStore.Audio.Media.BITRATE
            }

            // 过滤掉提示音、录音碎片：IS_MUSIC 且时长 > 5 秒
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                "AND ${MediaStore.Audio.Media.DURATION} > 5000"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            val list = ArrayList<Track>(256)
            context.contentResolver.query(collection, projection.toTypedArray(), selection, null, sortOrder)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val albumIdIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val addedIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dateIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val bitrateIdx = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val title = c.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: I18n.t(Str.UNTITLED_TRACK)
                    val rawMime = c.getString(mimeIdx)
                    list += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = title,
                        artist = c.getString(artistIdx).orEmpty(),
                        album = c.getString(albumIdx).orEmpty(),
                        durationMs = c.getLong(durIdx),
                        sizeBytes = c.getLong(sizeIdx),
                        mimeType = MimeTypes.resolve(rawMime, title),
                        albumId = c.getLong(albumIdIdx),
                        path = if (dataIdx >= 0) c.getString(dataIdx).orEmpty() else "",
                        dateAddedSec = if (addedIdx >= 0) c.getLong(addedIdx) else 0L,
                        dateModifiedSec = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        bitrate = if (bitrateIdx >= 0) c.getInt(bitrateIdx) else 0
                    )
                }
            }

            // ② 兜底：补扫后仍未进媒体库的文件，用 SAF document URI 直接建条目。
            //    这类条目绕过 MediaStore，元信息与封面都从内嵌标签读。
            val extras = ArrayList<Track>()
            if (unindexed.isNotEmpty()) {
                val indexedPaths = list.asSequence()
                    .mapNotNull { it.path.takeIf { p -> p.isNotBlank() } }
                    .toHashSet()
                unindexed.forEach { file ->
                    if (file.path != null && file.path in indexedPaths) return@forEach
                    buildLocalTrack(context, file)?.let { extras += it }
                }
                if (extras.isNotEmpty()) {
                    LogBus.i("另有 ${extras.size} 首未进媒体库，已改用 SAF 直读（封面取内嵌图）")
                }
            }

            // 兜底条目本身就来自所选文件夹，直接并进去，不再过前缀过滤
            baseList = applyFolderFilter(list) + extras
            _tracks.value = applySort(baseList)
            _loaded.value = true
            _error.value = null
            LogBus.i("曲库已加载：${_tracks.value.size} 首（元信息已缓存，点播时才读文件）")
            // 索引自检：把分组结果和几个中文样例打进日志，方便排查拼音归组是否正常
            runCatching {
                val sections = sectionIndex(_tracks.value).keys.joinToString("")
                val samples = _tracks.value
                    .filter { t -> t.title.any { it.code in 0x4E00..0x9FFF } }
                    .take(6)
                    .joinToString("、") { t -> "《${t.title}》→${indexLetter(t.title)}" }
                LogBus.d("A-Z 分组：$sections；中文样例：$samples")
            }
            _tracks.value.size
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
            LogBus.e("曲库扫描失败：${t.javaClass.simpleName} ${t.message}")
            0
        } finally {
            _scanning.value = false
        }
    }

    // ------------------------------------------------------------ 媒体库自愈

    /** 递归深度上限：Music/专辑/子目录 足够，再深就不是曲库结构了 */
    private const val MAX_SCAN_DEPTH = 4

    /** 文件夹里的一个音频候选文件 */
    private data class LocalAudio(
        val name: String,
        /** 真实路径（由 SAF documentId 反推），拿不到时为 null */
        val path: String?,
        /** 读取内容用的 URI：SAF document URI 或 file:// */
        val uri: Uri,
        val sizeBytes: Long
    )

    /**
     * 枚举音乐文件夹下的音频文件。
     *
     * 为什么必须优先 SAF：Android 11+ 的分区存储对 App 是"过滤视图"——
     * 没进 MediaStore 的文件，连 `File.listFiles()` 都列不出来（实测确认）。
     * 只有 DocumentsProvider 能看到真实目录，所以它是发现新歌的唯一可靠入口。
     * 没有 SAF 授权时退回 File 列举（至少能自愈一部分）。
     */
    private fun collectFolderAudio(context: Context): List<LocalAudio> {
        resolveFolderTree()?.let { tree ->
            val viaSaf = runCatching { listViaSaf(context, tree) }.getOrDefault(emptyList())
            if (viaSaf.isNotEmpty()) {
                LogBus.d("SAF 枚举到 ${viaSaf.size} 个音频文件")
                return viaSaf
            }
            LogBus.w("SAF 枚举为空，退回文件系统列举")
        }
        val prefix = _folderPrefix.value?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { listViaFile(prefix) }.getOrDefault(emptyList())
    }

    private fun listViaSaf(context: Context, tree: Uri): List<LocalAudio> {
        val rootDocId = DocumentsContract.getTreeDocumentId(tree) ?: return emptyList()
        val out = ArrayList<LocalAudio>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        fun walk(parentDocId: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2).orEmpty()
                        val size = c.getLong(3)
                        when {
                            name.startsWith(".") -> Unit
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(docId, depth + 1)
                            MimeTypes.fromExtension(name) != null -> out += LocalAudio(
                                name = name,
                                path = documentIdToPath(docId),
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                                sizeBytes = size
                            )
                        }
                    }
                }
            }.onFailure { LogBus.w("SAF 遍历失败：${it.message}") }
        }

        walk(rootDocId, 0)
        return out
    }

    private fun listViaFile(rootPath: String): List<LocalAudio> {
        val out = ArrayList<LocalAudio>()
        fun walk(dirPath: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            val dir = File(dirPath)
            if (!dir.isDirectory || !dir.canRead()) return
            dir.listFiles()?.forEach { entry ->
                when {
                    entry.name.startsWith(".") -> Unit
                    entry.isDirectory -> walk(entry.absolutePath, depth + 1)
                    MimeTypes.fromExtension(entry.name) != null -> out += LocalAudio(
                        name = entry.name,
                        path = entry.absolutePath,
                        uri = Uri.fromFile(entry),
                        sizeBytes = entry.length()
                    )
                }
            }
        }
        walk(rootPath, 0)
        return out
    }

    /** `primary:Music/黄昏.mp3` → `/storage/emulated/0/Music/黄昏.mp3` */
    @Suppress("DEPRECATION")
    private fun documentIdToPath(docId: String): String? = runCatching {
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return@runCatching null
        val volume = parts[0]
        val relative = parts[1]
        val base = if (volume.equals("primary", true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        "$base/$relative"
    }.getOrNull()

    /**
     * 把"文件夹里有、媒体库里没有"的文件补扫进系统索引，返回**补扫前**的缺失清单。
     *
     * 为什么需要这一步：Android 的媒体库不是实时维护的。用 MTP 拷贝、某些文件管理器搬运、
     * 或在电脑上直接写文件，都可能只落了文件、没落库。App 直接查 MediaStore 就永远看不到。
     */
    private fun rescanMissing(context: Context, candidates: List<LocalAudio>): List<LocalAudio> {
        if (candidates.isEmpty()) return emptyList()

        val indexed = indexedAudioPaths(context)
        val missing = candidates.filter { it.path == null || it.path !in indexed }
        if (missing.isEmpty()) {
            LogBus.d("文件夹内 ${candidates.size} 个音频文件均已在媒体库中")
            return emptyList()
        }

        val scannable = missing.mapNotNull { it.path }
        if (scannable.isEmpty()) {
            LogBus.w("媒体库缺失 ${missing.size} 首，但拿不到真实路径 → 直接 SAF 直读")
            return missing
        }

        LogBus.w("媒体库缺失 ${missing.size} 首，触发系统扫描：${missing.joinToString("、") { it.name }}")
        val latch = CountDownLatch(scannable.size)
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                scannable.toTypedArray(),
                null
            ) { _, _ -> latch.countDown() }
        }.onFailure { LogBus.w("触发媒体扫描失败：${it.message}") }

        val finished = runCatching { latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
        LogBus.i("媒体扫描${if (finished) "已完成" else "超时（未落库的走 SAF 直读兜底）"}")
        return missing
    }

    private fun indexedAudioPaths(context: Context): Set<String> {
        val out = HashSet<String>(256)
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (idx >= 0) {
                    while (c.moveToNext()) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { out += it }
                    }
                }
            }
        }.onFailure { LogBus.w("读取媒体库路径失败：${it.message}") }
        return out
    }

    /**
     * 用 SAF/文件本身造一条 [Track]（完全不依赖 MediaStore）。
     *
     * 时长/艺术家/专辑从内嵌标签读，读不到就退回文件名。
     * 过滤口径与 MediaStore 分支一致（时长 ≤ 5s 视为提示音丢掉）。
     */
    private fun buildLocalTrack(context: Context, file: LocalAudio): Track? {
        val mime = MimeTypes.fromExtension(file.name) ?: return null

        var durationMs = 0L
        var artist = ""
        var album = ""
        var embeddedTitle = ""

        val retriever = MediaMetadataRetriever()
        try {
            if (file.uri.scheme == "content") {
                retriever.setDataSource(context, file.uri)
            } else {
                retriever.setDataSource(file.uri.path ?: file.path)
            }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
        } catch (t: Throwable) {
            LogBus.w("读取内嵌标签失败：${file.name} · ${t.message}")
        } finally {
            runCatching { retriever.release() }
        }

        if (durationMs in 1..5000L) return null

        val fallbackTitle = file.name.substringBeforeLast('.', file.name)
            .ifBlank { I18n.t(Str.UNTITLED_TRACK) }
        val seed = file.path ?: file.uri.toString()
        val modifiedSec = file.path?.let { File(it).lastModified() }?.div(1000L) ?: 0L
        // MediaStore 的 _ID 全是正数，这里用负数避免和真实条目撞 key
        val generatedId = -(seed.hashCode().toLong() * 31 + modifiedSec)

        return Track(
            id = if (generatedId == 0L) -1L else generatedId,
            uri = file.uri,
            title = embeddedTitle.ifBlank { fallbackTitle },
            artist = artist,
            album = album,
            durationMs = durationMs,
            sizeBytes = file.sizeBytes,
            mimeType = mime,
            albumId = 0L,
            path = file.path.orEmpty(),
            dateAddedSec = modifiedSec,
            dateModifiedSec = modifiedSec
        )
    }

    /**
     * 按用户选择的文件夹过滤。
     *
     * 若 MediaStore 没给出 DATA 路径（部分目录会被隐藏），就退回全量 ——
     * 宁可多显示，也不要因为拿不到路径而让曲库变空。
     */
    private fun applyFolderFilter(all: List<Track>): List<Track> {
        val prefix = _folderPrefix.value
        if (prefix.isNullOrBlank()) return all
        if (all.none { it.path.isNotBlank() }) {
            LogBus.w("系统未提供文件路径，文件夹筛选已跳过")
            return all
        }
        val filtered = all.filter { it.path.startsWith(prefix) }
        LogBus.i("文件夹筛选「${_folderLabel.value ?: prefix}」：${filtered.size}/${all.size} 首")
        return filtered
    }

    /** 定位当前播放曲目在列表中的位置，用于上一首/下一首 */
    fun indexOf(uri: Uri): Int = _tracks.value.indexOfFirst { it.uri == uri }

    fun first(): Track? = _tracks.value.firstOrNull()

    /** 夹紧边界的相邻曲目（旧行为，保留给"到末尾就停在最后一首"的场景） */
    fun neighbour(current: Track?, offset: Int): Track? {
        val list = _tracks.value
        if (list.isEmpty()) return null
        val idx = current?.let { c -> list.indexOfFirst { it.id == c.id } } ?: -1
        val target = if (idx < 0) {
            if (offset >= 0) 0 else list.lastIndex
        } else {
            (idx + offset).coerceIn(0, list.lastIndex)
        }
        return list[target]
    }

    /** 严格的相邻曲目：越界返回 null，用于"顺序播放到最后一首就停" */
    fun neighbourStrict(current: Track?, offset: Int): Track? {
        val list = _tracks.value
        if (list.isEmpty()) return null
        val idx = current?.let { c -> list.indexOfFirst { it.id == c.id } } ?: -1
        if (idx < 0) return if (offset >= 0) list.firstOrNull() else list.lastOrNull()
        val target = idx + offset
        return if (target in list.indices) list[target] else null
    }

    /** 环绕的相邻曲目，用于列表循环 */
    fun neighbourWrap(current: Track?, offset: Int): Track? {
        val list = _tracks.value
        if (list.isEmpty()) return null
        val idx = current?.let { c -> list.indexOfFirst { it.id == c.id } } ?: -1
        if (idx < 0) return list.firstOrNull()
        val size = list.size
        val target = ((idx + offset) % size + size) % size
        return list[target]
    }

    /** 随机挑一首（不等于当前），用于随机播放 */
    fun randomExcept(current: Track?): Track? {
        val list = _tracks.value
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        val pool = list.filter { it.id != current?.id }
        return pool.randomOrNull()
    }
}
