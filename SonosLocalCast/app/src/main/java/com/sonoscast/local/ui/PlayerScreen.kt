package com.sonoscast.local.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonoscast.local.core.DeviceStatus
import com.sonoscast.local.core.Hint
import com.sonoscast.local.core.RepeatMode
import com.sonoscast.local.core.SonosDevice
import com.sonoscast.local.core.Str
import com.sonoscast.local.core.Track
import kotlin.math.roundToInt

/**
 * 主播放界面。
 *
 * 布局意图：
 * - 顶栏单行：标题与右侧所有图标垂直居中；
 * - 输出设备收成右上角的小音箱图标；
 * - 传输控制以播放键为几何中心：[随机][上一曲] | 播放 | [下一曲][循环]；
 * - 底部一分为二：左「播放清单」、右「曲库」，各自显示数量。
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onOpenLibrary: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val cover by vm.cover.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val selectedKeys by vm.selectedKeys.collectAsStateWithLifecycle()
    val deviceStatus by vm.deviceStatus.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val position by vm.positionSec.collectAsStateWithLifecycle()
    val duration by vm.durationSec.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()
    val externalSource by com.sonoscast.local.core.AppState.externalSource.collectAsStateWithLifecycle()
    val connectionLost by com.sonoscast.local.core.AppState.connectionLost.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val shuffle by vm.shuffle.collectAsStateWithLifecycle()
    val repeatMode by vm.repeatMode.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

    var showDeviceSheet by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        PlayerBackdrop(cover)

        // 状态栏区域：叠一层从封面主色到透明的渐变（方案 C）。
        // 只影响播放页；其它页面仍是纯黑 + 白图标。
        StatusBarTint(cover)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            PlayerTopBar(
                onOpenDevices = { showDeviceSheet = true },
                onOpenSettings = onOpenSettings
            )

            Spacer(Modifier.weight(1f))

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CoverArt(cover = cover, playing = playing)

                Spacer(Modifier.height(26.dp))

                Text(
                    text = track?.title ?: nowPlaying?.takeIf { it.isNotBlank() } ?: str(Str.NOT_PLAYING),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track?.subtitle
                        ?: if (!nowPlaying.isNullOrBlank()) str(Str.PLAYING_ON_SPEAKER) else str(Str.PICK_SONG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                ProgressRow(
                    positionSec = position,
                    durationSec = if (duration > 0) duration else (track?.durationMs ?: 0L) / 1000,
                    onSeek = { vm.seekTo(it) }
                )

                Spacer(Modifier.height(4.dp))

                if (externalSource || connectionLost) {
                    Text(
                        if (connectionLost) str(Str.CONNECTION_LOST) else str(Str.EXTERNAL_SOURCE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                TransportRow(
                    playing = playing,
                    busy = busy,
                    enabled = devices.isNotEmpty(),
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    onToggleShuffle = { vm.toggleShuffle() },
                    onPrevious = vm::previous,
                    onToggle = vm::togglePlay,
                    onNext = vm::next,
                    onCycleRepeat = { vm.cycleRepeat() }
                )
            }

            Spacer(Modifier.weight(1f))

            VolumeRow(volume = volume, onCommit = { vm.setVolume(it) })

            Spacer(Modifier.height(10.dp))

            EntryRow(
                // 单曲循环时清单一栏按 1 显示，与清单页实际条目数保持一致
                queueCount = if (repeatMode == com.sonoscast.local.core.RepeatMode.ONE) 1 else queue.size,
                libraryCount = tracks.size,
                onOpenQueue = onOpenQueue,
                onOpenLibrary = onOpenLibrary
            )

            Spacer(Modifier.height(14.dp))
        }

        hint?.let { current ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 22.dp, end = 22.dp, bottom = 96.dp)
            ) {
                HintBar(text = hintText(current), onDismiss = { vm.dismissHint() })
            }
        }

        if (showDeviceSheet) {
            DeviceSheet(
                devices = devices,
                selectedKeys = selectedKeys,
                statuses = deviceStatus,
                scanning = scanning,
                onToggle = { vm.toggleSelection(it) },
                onAddManual = { vm.addManualDevice(it) },
                onScan = { vm.scanDevices() },
                onDismiss = { showDeviceSheet = false }
            )
        }
    }
}

// ------------------------------------------------------------------ 背景 / 顶栏

/**
 * 把 [Hint]（文案键 + 参数）解析成当前语言的成品字符串。
 *
 * 放在组合期解析而不是在 Service 里提前拼好，用户中途切语言时提示会跟着变。
 */
@Composable
private fun hintText(hint: Hint): String =
    if (hint.args.isEmpty()) str(hint.key) else strf(hint.key, *hint.args.toTypedArray())


/**
 * 播放页顶栏渐变：取封面主色，压 35% 透明度铺在状态栏高度上。
 * 纯黑背景 + 白色状态栏图标，有了这层色带后文字/图标的对比度明显提升。
 */
@Composable
private fun StatusBarTint(cover: Bitmap?) {
    val tint = remember(cover) { CoverPalette.of(cover) } ?: return
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(topInset + 44.dp)
            .background(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.38f),
                    0.55f to tint.copy(alpha = 0.16f),
                    1f to Color.Transparent
                )
            )
    )
}

@Composable
private fun PlayerBackdrop(cover: Bitmap?) {
    Box(Modifier.fillMaxSize()) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(72.dp)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.66f),
                        0.42f to Color.Black.copy(alpha = 0.86f),
                        1.0f to Color.Black
                    )
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标题用 AnnotatedString 拼：local stream 的字号是 ZONOZ 的 2/3，
        // 同一段文本才能保证基线自然对齐。
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)) {
                    append("ZONOZ")
                }
                append("  ")
                withStyle(SpanStyle(fontSize = 11.3.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)) {
                    append("local stream")
                }
            },
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )

        Spacer(Modifier.weight(1f))

        // 固定一个音箱入口：点开是输出设备的二级菜单
        IconButton(onClick = onOpenDevices, modifier = Modifier.size(42.dp)) {
            Icon(
                Icons.Rounded.Speaker,
                contentDescription = str(Str.OUTPUT_DEVICES),
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = str(Str.SETTINGS),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ 设备二级菜单

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    devices: List<SonosDevice>,
    selectedKeys: Set<String>,
    statuses: Map<String, DeviceStatus>,
    scanning: Boolean,
    onToggle: (String) -> Unit,
    onAddManual: (String) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111113),
        contentColor = MaterialTheme.colorScheme.onBackground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.25f)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp)
        ) {
            Text(
                str(Str.OUTPUT_DEVICES),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                str(Str.SELECT_OUTPUT_HINT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Text(
                    str(Str.NO_DEVICE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                devices.forEach { device ->
                    DeviceSheetRow(
                        device = device,
                        selected = device.key in selectedKeys,
                        status = statuses[device.key],
                        onToggle = { onToggle(device.key) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            ManualIpField(onAdd = onAddManual)

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(onClick = onScan, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (scanning) str(Str.SCANNING) else str(Str.RESCAN))
                }
            }
        }
    }
}

@Composable
private fun DeviceSheetRow(
    device: SonosDevice,
    selected: Boolean,
    status: DeviceStatus?,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Speaker,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(device.ip)
                    append(" · ")
                    append(deviceRoleLabel(device, status))
                    // 固件代次：S1 设备接进来时一眼能看出差异
                    val generation = device.generationLabel
                    if (generation.isNotBlank()) {
                        append(" · ")
                        append(generation)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun ManualIpField(onAdd: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            modifier = Modifier.weight(1f),
            label = { Text(str(Str.MANUAL_IP), style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                onAdd(ip.trim())
                ip = ""
            },
            enabled = ip.isNotBlank()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = str(Str.ADD))
        }
    }
}

@Composable
private fun deviceRoleLabel(device: SonosDevice, status: DeviceStatus?): String {
    val role = when {
        device.isSatellite && device.channelRole.isNotBlank() -> strf(Str.ROLE_SUB, device.channelRole)
        device.isSatellite -> str(Str.ROLE_SUB_DEVICE)
        device.channelRole.isNotBlank() -> strf(Str.ROLE_MAIN, device.channelRole)
        else -> null
    }
    val state = when (status) {
        DeviceStatus.PLAYING -> str(Str.DEV_PLAYING)
        DeviceStatus.PAUSED -> str(Str.DEV_PAUSED)
        DeviceStatus.PUSHING -> str(Str.DEV_PUSHING)
        DeviceStatus.ERROR -> str(Str.DEV_FAILED)
        DeviceStatus.FOLLOWING -> str(Str.DEV_FOLLOWING)
        else -> null
    }
    return listOfNotNull(role, state).joinToString(" · ").ifBlank { str(Str.DEV_IDLE) }
}

// ------------------------------------------------------------------ 封面 / 信息

@Composable
private fun CoverArt(cover: Bitmap?, playing: Boolean) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(1f)
            .shadow(26.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = if (playing) 0.42f else 0.22f)
            )
        }
    }
}

@Composable
private fun ProgressRow(
    positionSec: Long,
    durationSec: Long,
    onSeek: (Int) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    // 上一次触发触感的"进度刻度"，避免每一帧都震
    var lastStep by remember { mutableStateOf(-1) }

    val liveFraction = if (durationSec > 0L) {
        (positionSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = if (dragging) dragFraction else liveFraction
    val shownPosition = if (dragging) (dragFraction * durationSec).toLong() else positionSec

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(durationSec) {
                    if (durationSec <= 0L) return@pointerInput
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        view.hapticTick()
                        onSeek((f * durationSec).toInt())
                    }
                }
                .pointerInput(durationSec) {
                    if (durationSec <= 0L) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            lastStep = (dragFraction * SEEK_STEPS).toInt()
                            view.hapticTick()
                        },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            val step = (dragFraction * SEEK_STEPS).toInt()
                            if (step != lastStep) {
                                lastStep = step
                                view.hapticTick()
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            onSeek((dragFraction * durationSec).toInt())
                            dragging = false
                            view.hapticConfirm()
                        },
                        onDragCancel = { dragging = false }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (dragging) 4.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(if (dragging) 4.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            if (dragging) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                Track.formatDuration(shownPosition * 1000),
                style = MaterialTheme.typography.labelSmall,
                color = if (dragging) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                Track.formatDuration(durationSec * 1000),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ 传输控制

/** 循环键图标：单曲循环用 RepeatOne，列表循环用 Repeat */
private fun repeatIcon(mode: RepeatMode): ImageVector =
    if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat

@Composable
private fun repeatLabel(mode: RepeatMode): String = str(
    if (mode == RepeatMode.ONE) Str.MODE_REPEAT_ONE else Str.MODE_REPEAT_ALL
)

/**
 * 传输控制条。
 *
 * 布局：[随机][上一曲] —— [播放/暂停] —— [下一曲][循环]。
 *
 * 1.8.2 起两键严格互斥：随机亮 → 循环灭；随机灭 → 循环亮（列表/单曲二选一）。
 * 也就是说任何时刻必定"一亮一灭"，不存在两个都灭的状态。
 * 原来的停止键已移除（停止仍可从通知栏「退出」触发）。
 */
@Composable
private fun TransportRow(
    playing: Boolean,
    busy: Boolean,
    enabled: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    val activeTint = MaterialTheme.colorScheme.onBackground
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    // 左右各两个 48dp 按钮 + 等宽 Spacer，播放键就是严格居中
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 随机播放开关：点亮 = 白，熄灭 = 灰
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = str(Str.MODE_SHUFFLE),
                modifier = Modifier.size(22.dp),
                tint = if (shuffle) activeTint else idleTint
            )
        }
        IconButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = str(Str.PREV),
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.92f else 0.3f)
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Color.White
                    else Color.White.copy(alpha = 0.28f)
                )
                .clickable(enabled = enabled && !busy) { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.Black
                )
            } else {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) str(Str.PAUSE) else str(Str.PLAY),
                    modifier = Modifier.size(38.dp),
                    tint = Color.Black
                )
            }
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onNext, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = str(Str.NEXT),
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.92f else 0.3f)
            )
        }
        // 循环方式：关闭 = 灰，列表循环 / 单曲循环 = 白
        IconButton(onClick = onCycleRepeat, modifier = Modifier.size(48.dp)) {
            Icon(
                repeatIcon(repeatMode),
                contentDescription = repeatLabel(repeatMode),
                modifier = Modifier.size(22.dp),
                // 互斥：随机亮着的时候循环键必然熄灭
                tint = if (shuffle) idleTint else activeTint
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeRow(volume: Int, onCommit: (Int) -> Unit) {
    var value by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    val view = LocalView.current
    var lastTick by remember { mutableStateOf(-1) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左端音量图标与右端数字对称，图标随当前值在 静音/小/大 之间切换
        Icon(
            imageVector = when {
                value <= 0.5f -> Icons.Rounded.VolumeOff
                value < 50f -> Icons.Rounded.VolumeDown
                else -> Icons.Rounded.VolumeUp
            },
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))

        Slider(
            value = value,
            onValueChange = {
                value = it
                // 0~100 每一级震一次，天然起到节流作用
                val tick = it.roundToInt()
                if (tick != lastTick) {
                    lastTick = tick
                    view.hapticTick()
                }
            },
            onValueChangeFinished = {
                onCommit(value.roundToInt())
                view.hapticConfirm()
            },
            valueRange = 0f..100f,
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
            // M3 默认的滑块是"竖线游标 + 波浪轨道"，放在播放器里太像设置项。
            // 这里换成细轨道 + 小圆点，和上面的进度条保持同一套视觉。
            thumb = {
                Box(
                    Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            track = { state ->
                val fraction = (state.value / 100f).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value.roundToInt().toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.End
        )
    }
}

// ------------------------------------------------------------------ 底部双入口

/**
 * 底部一分为二：左「播放清单」、右「曲库」。
 *
 * 播放清单 = 真正会播的顺序（开随机后这里显示的就是打乱结果），
 * 和曲库（全部歌曲）是两个概念，所以拆开。
 */
@Composable
private fun EntryRow(
    queueCount: Int,
    libraryCount: Int,
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EntryCard(
            icon = Icons.Rounded.QueueMusic,
            label = str(Str.QUEUE),
            detail = if (queueCount > 0) {
                strf(Str.TRACKS_N, queueCount.toString())
            } else {
                str(Str.QUEUE_EMPTY)
            },
            modifier = Modifier.weight(1f),
            onClick = onOpenQueue
        )
        EntryCard(
            icon = Icons.Rounded.LibraryMusic,
            label = str(Str.LIBRARY),
            detail = if (libraryCount > 0) {
                strf(Str.TRACKS_N, libraryCount.toString())
            } else {
                str(Str.NOT_IMPORTED)
            },
            modifier = Modifier.weight(1f),
            onClick = onOpenLibrary
        )
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(17.dp)
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        // 标题行与数量都居中，两个入口对称
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                letterSpacing = 0.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun HintBar(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF17171A).copy(alpha = 0.97f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = str(Str.CLOSE),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
    ) {
        content()
    }
}

// ------------------------------------------------------------------ 触觉反馈

/**
 * 进度条均分成 100 格，每跨一格震一次。
 *
 * 为什么不按"每秒一次"：一首 15 分钟的曲子会有 900 次，手感变成蜂鸣器；
 * 按百分比切格后，无论曲子长短都是 100 次，均匀且不打手。
 */
private const val SEEK_STEPS = 100f

/** 拖动过程中的刻度感 */
private fun View.hapticTick() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** 松手时的确认感（Android 11+ 用 CONFIRM，老系统退回刻度） */
private fun View.hapticConfirm() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(constant)
}
