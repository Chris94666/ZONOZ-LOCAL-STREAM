package com.sonoscast.local.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoscast.local.core.CoverLoader
import com.sonoscast.local.core.MusicLibrary
import com.sonoscast.local.core.SortMode
import com.sonoscast.local.core.Str
import com.sonoscast.local.core.Track

/**
 * 曲库页。
 *
 * 列表里全是缓存的元信息（标题/艺术家/时长/大小），专辑封面按需加载并进 LruCache。
 * 点击某一行才真正打开音频文件并推给音箱。
 */
@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val scanning by vm.libraryScanning.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()
    val error by vm.libraryError.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val favoritesOnly by vm.favoritesOnly.collectAsStateWithLifecycle()

    // 播放清单里"下一个"是谁（列表循环到末尾时回绕到第一首），以及还剩几首
    val nextInQueueId = remember(queue, queueIndex) {
        if (queue.isEmpty()) null else queue[((queueIndex + 1) % queue.size + queue.size) % queue.size].id
    }
    val upcoming = (queue.size - queueIndex - 1).coerceAtLeast(0)

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var sortMenuOpen by remember { mutableStateOf(false) }

    // 先按"只看喜爱"过滤，再按搜索词过滤
    val visible = remember(tracks, query, favoritesOnly, favoriteIds) {
        tracks
            .filter { !favoritesOnly || it.id in favoriteIds }
            .filter { track ->
                query.isBlank() ||
                    track.title.contains(query, true) ||
                    track.artist.contains(query, true) ||
                    track.album.contains(query, true)
            }
    }

    // 长按「文件信息」打开的那首歌
    var infoTrack by remember { mutableStateOf<Track?>(null) }

    // A-Z 分组索引：只在按首字母排序时有意义
    val sections = remember(visible, sortMode) {
        if (sortMode == SortMode.TITLE) MusicLibrary.sectionIndex(visible) else linkedMapOf()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = str(Str.CLOSE),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    str(Str.LIBRARY),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    when {
                        tracks.isEmpty() -> str(Str.NO_SONGS)
                        upcoming > 0 ->
                            strf(Str.TOTAL_N, tracks.size.toString()) + " · " +
                                strf(Str.PENDING_N, upcoming.toString())
                        else ->
                            strf(Str.TOTAL_N, tracks.size.toString()) + " · " + str(Str.LONG_PRESS_HINT)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 排序
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        Icons.Rounded.Sort,
                        contentDescription = str(Str.SORT_TITLE),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    SortMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(str(mode.labelKey)) },
                            leadingIcon = {
                                if (mode == sortMode) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                sortMenuOpen = false
                                vm.setSortMode(mode)
                                scope.launch { listState.scrollToItem(0) }
                            }
                        )
                    }

                    // 收藏筛选：和排序放在同一个菜单里，用分割线隔开
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(
                        text = { Text(str(Str.FAVORITES)) },
                        leadingIcon = {
                            if (favoritesOnly) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            sortMenuOpen = false
                            vm.toggleFavoritesOnly()
                            scope.launch { listState.scrollToItem(0) }
                        }
                    )
                }
            }

            IconButton(onClick = { vm.scanLibrary() }, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = str(Str.RESCAN),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!hasPermission) {
            PermissionEmpty(onRequestPermission)
            return@Column
        }

        // 搜索
        TextField(
            value = query,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = {
                Text(str(Str.SEARCH_HINT), style = MaterialTheme.typography.bodySmall)
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        if (visible.isEmpty()) {
            EmptyState(
                scanning = scanning,
                loaded = loaded,
                error = error,
                query = query
            )
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = if (sections.size > 1) 2.dp else 10.dp,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visible, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = current?.id == track.id,
                        isNext = nextInQueueId == track.id,
                        isFavorite = track.id in favoriteIds,
                        // 点歌时把"当前可见列表"作为播放清单来源（搜索/排序后的顺序）
                        onClick = { vm.playTrack(track, visible) },
                        onPlayNext = { vm.playNext(track, visible) },
                        onShowInfo = { infoTrack = track },
                        onToggleFavorite = { vm.toggleFavorite(track) }
                    )
                }
            }

            if (sections.size > 1) {
                AlphaIndexBar(
                    letters = sections.keys.toList(),
                    onLetter = { ch ->
                        sections[ch]?.let { index ->
                            scope.launch { listState.scrollToItem(index) }
                        }
                    }
                )
            }
        }
    }

    infoTrack?.let { track ->
        TrackInfoDialog(track = track, onDismiss = { infoTrack = null })
    }
}

/**
 * 右侧 A-Z 索引条。
 *
 * 按下即可跳转，按住上下滑动连续跳转（Like iOS 通讯录）。
 * 用 awaitEachGesture 自己处理，是为了让"按下"和"拖动"走同一条路径 —— 
 * 分开用 detectTapGestures + detectVerticalDragGestures 会互相抢事件。
 */
@Composable
private fun AlphaIndexBar(
    letters: List<Char>,
    onLetter: (Char) -> Unit
) {
    var active by remember { mutableStateOf<Char?>(null) }
    // 索引条是自己处理指针事件的，不会走全局点击触感，这里单独给刻度反馈
    val view = LocalView.current

    Column(
        modifier = Modifier
            .width(24.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .pointerInput(letters) {
                awaitEachGesture {
                    fun pick(y: Float) {
                        val h = size.height.toFloat()
                        if (h <= 0f || letters.isEmpty()) return
                        val index = ((y / h) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.size - 1)
                        val ch = letters[index]
                        if (ch != active) {
                            active = ch
                            // 每跨过一个字母轻震一下，滑动时才有"一格一格"的手感
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onLetter(ch)
                        }
                    }

                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 按下的那一下也给一次反馈
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    pick(down.position.y)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        pick(change.position.y)
                        change.consume()
                    }
                    active = null
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { ch ->
            Text(
                text = ch.toString(),
                fontSize = 9.sp,
                color = if (ch == active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isNext: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onShowInfo: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    when {
                        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        isNext -> Color.White.copy(alpha = 0.05f)
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TrackThumb(track)
                if (isFavorite) {
                    // 白色实心爱心：比初版放大 2.5 倍（9dp → 22dp），
                    // 并且离右下边缘留 4dp 间距，不再和封面边缘贴在一起
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = str(Str.FAVORITES),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(13.dp),
                        tint = Color.White
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isNext) {
                Spacer(Modifier.width(8.dp))
                Text(
                    str(Str.NEXT_BADGE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(Modifier.width(10.dp))
            Text(
                track.durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 长按弹出的操作菜单
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(str(Str.PLAY_NEXT)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.PlaylistPlay,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    onPlayNext()
                }
            )
            DropdownMenuItem(
                text = { Text(str(Str.PLAY_NOW)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    onClick()
                }
            )
            DropdownMenuItem(
                text = { Text(if (isFavorite) str(Str.UNFAVORITE) else str(Str.FAVORITE)) },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggleFavorite()
                }
            )
            DropdownMenuItem(
                text = { Text(str(Str.FILE_INFO)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    onShowInfo()
                }
            )
        }
    }
}

@Composable
private fun TrackThumb(track: Track) {
    // 曲库与播放清单共用的封面实现，见 TrackCover.kt
    TrackCover(track = track, size = 46.dp, corner = 10.dp)
}

@Composable
private fun EmptyState(scanning: Boolean, loaded: Boolean, error: String?, query: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            scanning -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    str(Str.SCAN_LIBRARY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error != null -> Text(
                strf(Str.SCAN_FAILED, error.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            query.isNotBlank() -> Text(
                strf(Str.NO_MATCH, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            loaded -> {
                Icon(
                    Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = Color.White.copy(alpha = 0.25f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    str(Str.LIB_EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    str(Str.LIB_EMPTY_HINT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionEmpty(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Color.White.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            str(Str.NEED_PERMISSION),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            str(Str.PERMISSION_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(str(Str.GRANT))
        }
    }
}

// ------------------------------------------------------------------ 文件信息

@Composable
private fun TrackInfoDialog(track: Track, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17171A),
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(str(Str.FILE_INFO)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoLine(str(Str.FILE_NAME), track.fileName)
                InfoLine(
                    str(Str.LOCATION),
                    track.path.ifBlank { track.fileName },
                    mono = true
                )
                InfoLine(str(Str.SIZE), track.sizeLabel)
                InfoLine(str(Str.DURATION), track.durationLabel)
                InfoLine(str(Str.FORMAT), track.mimeType)
                if (track.bitrate > 0) InfoLine(str(Str.BITRATE), "${track.bitrate / 1000} kbps")
                if (track.sampleRate > 0) InfoLine(str(Str.SAMPLE_RATE), "${track.sampleRate} Hz")
                InfoLine(str(Str.ARTIST), track.artist.ifBlank { str(Str.UNKNOWN) })
                InfoLine(str(Str.ALBUM), track.album.ifBlank { str(Str.UNKNOWN) })
                if (track.dateModifiedSec > 0) {
                    InfoLine(str(Str.MODIFIED), formatModified(track.dateModifiedSec))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(str(Str.CLOSE)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(track.path.ifBlank { track.fileName }))
                },
                enabled = track.path.isNotBlank()
            ) { Text(str(Str.COPY_PATH)) }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

private fun formatModified(epochSec: Long): String = runCatching {
    java.time.Instant.ofEpochSecond(epochSec)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault("-")
