package com.sonoscast.local.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import android.content.Context
import android.app.AlarmManager
import android.os.Build
import com.sonoscast.local.BuildConfig
import com.sonoscast.local.core.I18n
import com.sonoscast.local.core.Lang
import com.sonoscast.local.core.LogEntry
import com.sonoscast.local.core.LogLevel
import com.sonoscast.local.core.ServicePhase
import com.sonoscast.local.core.Str
import com.sonoscast.local.core.UiScale
import com.sonoscast.local.service.SonosCastService

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 15.sp)

// ================================================================== 设置首页

/**
 * 设置首页。
 *
 * 只放"用户平时会调"的三项，顺序固定：音乐文件夹 → 语言 → 调试。
 * 调试相关的东西全部收进三级页（[DebugDetailScreen]），不再和日常设置混在一起。
 */
@Composable
fun SettingsScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit
) {
    val folderPath by vm.folderPath.collectAsStateWithLifecycle()
    val currentLang by I18n.current.collectAsStateWithLifecycle()
    val uiScale by UiScale.scale.collectAsStateWithLifecycle()

    var langMenuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var fontMenuOpen by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.setMusicFolder(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SettingsTopBar(
            title = str(Str.SETTINGS),
            onBack = onBack,
            actions = {
                IconButton(onClick = { showAbout = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = str(Str.ABOUT),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.shutdown() }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.PowerSettingsNew,
                        contentDescription = str(Str.STOP_SERVICE),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ① 音乐文件夹：与语言栏完全一致的二级下拉菜单（菜单里不再重复路径，
            //    上一级那一行已经显示了完整路径）
            Box {
                GlassCard(Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = str(Str.MUSIC_FOLDER),
                        subtitle = folderPath ?: str(Str.ALL_MUSIC),
                        subtitleMaxLines = 2,
                        onClick = { folderMenuOpen = true }
                    )
                }
                DropdownMenu(expanded = folderMenuOpen, onDismissRequest = { folderMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(str(Str.CHOOSE)) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            folderMenuOpen = false
                            folderPicker.launch(null)
                        }
                    )
                    if (folderPath != null) {
                        DropdownMenuItem(
                            text = { Text(str(Str.CLEAR)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                folderMenuOpen = false
                                vm.clearMusicFolder()
                            }
                        )
                    }
                }
            }

            // ② 语言
            Box {
                GlassCard(Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = str(Str.LANGUAGE),
                        subtitle = currentLang.nativeName,
                        onClick = { langMenuOpen = true }
                    )
                }
                DropdownMenu(expanded = langMenuOpen, onDismissRequest = { langMenuOpen = false }) {
                    Lang.values().forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.nativeName) },
                            leadingIcon = {
                                if (lang == currentLang) {
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
                                langMenuOpen = false
                                vm.setLang(lang)
                            }
                        )
                    }
                }
            }

            // ③ 文字大小（整体缩放，sp 全量生效；7 种语言共用同一套档位）
            Box {
                GlassCard(Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Rounded.FormatSize,
                        title = str(Str.FONT_SIZE),
                        subtitle = str(UiScale.labelKey(uiScale)),
                        onClick = { fontMenuOpen = true }
                    )
                }
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    UiScale.OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(str(UiScale.labelKey(option))) },
                            leadingIcon = {
                                if (kotlin.math.abs(option - uiScale) < 0.01f) {
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
                                fontMenuOpen = false
                                vm.setFontScale(option)
                            }
                        )
                    }
                }
            }

            // ④ 调试（三级页入口；后台保活等诊断项都在里面）
            GlassCard(Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Rounded.BugReport,
                    title = str(Str.DEBUG),
                    subtitle = str(Str.DEBUG_HINT),
                    onClick = onOpenDebug,
                    trailingArrow = true
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

// ================================================================== 调试（三级页）

/**
 * 调试页（设置 → 调试）。
 *
 * 顺序：运行中服务 → 设备信息 → 运行日志 → 手动输入音箱 IP。
 * 这里都是"平时不需要看"的东西，所以藏在三级页里。
 */
@Composable
fun DebugDetailScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val localIp by vm.localIp.collectAsStateWithLifecycle()
    val port by vm.serverPort.collectAsStateWithLifecycle()
    val streamUrl by vm.streamUrl.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val batteryExempt = remember { SonosCastService.isIgnoringBatteryOptimizations(context) }
    val exactAlarmAllowed = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    var serviceExpanded by rememberSaveable { mutableStateOf(true) }
    var logExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && logExpanded) listState.scrollToItem(logs.lastIndex)
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SettingsTopBar(title = str(Str.DEBUG), onBack = onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ① 后台保活（常驻，永远显示状态；缺什么就点它去补）
            val keepAlive = rememberKeepAliveState()
            GlassCard(Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = str(Str.KEEPALIVE),
                    subtitle = keepAlive.describe(),
                    subtitleColor = if (keepAlive.ok) null else Color(0xFFFFB86B),
                    iconTint = if (keepAlive.ok) null else Color(0xFFFFB86B),
                    subtitleMaxLines = 2,
                    onClick = {
                        if (!keepAlive.exactAlarm) {
                            SonosCastService.requestExactAlarmPermission(context)
                        }
                        if (!keepAlive.batteryExempt) {
                            SonosCastService.requestIgnoreBatteryOptimizations(context)
                        }
                    }
                )
            }

            // ② 运行中服务
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { serviceExpanded = !serviceExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(phaseColor(phase))
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            str(phaseKey(phase)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (serviceExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (serviceExpanded) str(Str.COLLAPSE) else str(Str.EXPAND),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = serviceExpanded) {
                        Column(
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    str(Str.SCAN_SPEAKERS),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.scanDevices() }, enabled = !scanning) {
                                    if (scanning) {
                                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.Refresh, null, Modifier.size(14.dp))
                                    }
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (scanning) str(Str.SCANNING) else str(Str.RESCAN))
                                }
                            }
                            KeyValue(
                                label = str(Str.SERVICE_ADDR),
                                value = if (port > 0 && localIp != null) "http://$localIp:$port/" else str(Str.PHASE_STOPPED),
                                onCopy = if (port > 0 && localIp != null) {
                                    { copy("http://$localIp:$port/") }
                                } else {
                                    null
                                }
                            )
                            streamUrl?.let { url ->
                                KeyValue(label = str(Str.STREAM_URL), value = url) { copy(url) }
                            }

                            // 后台续播的两个前置条件
                            if (!batteryExempt || !exactAlarmAllowed) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text(
                                    str(Str.HINT_BATTERY),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFFB86B)
                                )
                                if (!exactAlarmAllowed) {
                                    Text(
                                        str(Str.HINT_EXACT_ALARM),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { SonosCastService.requestExactAlarmPermission(context) }) {
                                        Text(str(Str.HINT_BATTERY_ACTION), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (!batteryExempt) {
                                    TextButton(onClick = { SonosCastService.requestIgnoreBatteryOptimizations(context) }) {
                                        Text(str(Str.HINT_BATTERY_ACTION), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ③ 设备信息
            if (devices.isNotEmpty()) {
                val followingLabel = str(Str.DEV_FOLLOWING)
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            str(Str.DEV_DIAGNOSTICS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        devices.forEach { device ->
                            Column {
                                Text(
                                    "${device.displayName} · ${device.ip}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1
                                )
                                Text(
                                    buildString {
                                        append(device.modelName.ifBlank { "-" })
                                        append(" · ")
                                        append(device.generationLabel.ifBlank { "?" })
                                        append(" · ")
                                        append(device.softwareVersion.ifBlank { "-" })
                                        if (device.isSatellite) {
                                            append(" · ")
                                            append(followingLabel)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ④ 运行日志
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { logExpanded = !logExpanded }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    strf(Str.LOGS_N, logs.size.toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (logExpanded) {
                    TextButton(onClick = { vm.clearLogs() }) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(str(Str.CLEAR_LOGS), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Icon(
                    if (logExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (logExpanded) str(Str.COLLAPSE) else str(Str.EXPAND),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (logExpanded) {
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (logs.isEmpty()) {
                            Text(
                                str(Str.NO_LOGS),
                                Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logs, key = { it.id }) { entry ->
                                    LogRow(entry)
                                }
                            }
                        }
                    }
                }
            }

            // ⑤ 手动输入音箱 IP
            ManualIpEntry { vm.addManualDevice(it) }

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ------------------------------------------------------------------ 共用小组件

@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = str(Str.CLOSE),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            title,
            fontSize = 15.4.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        actions?.invoke()
    }
}

/** 保活相关的两个系统开关（会随系统设置变化，所以每次回到界面都重新读一次） */
private data class KeepAliveState(val batteryExempt: Boolean, val exactAlarm: Boolean) {
    val ok: Boolean get() = batteryExempt && exactAlarm
}

/** 副标题：都就绪时给一句确认，缺什么就说缺什么（走 str() 以便切语言时重组） */
@Composable
private fun KeepAliveState.describe(): String = if (ok) {
    str(Str.KEEPALIVE_OK)
} else {
    listOfNotNull(
        if (batteryExempt) null else str(Str.KEEPALIVE_BATTERY),
        if (exactAlarm) null else str(Str.KEEPALIVE_ALARM)
    ).joinToString(" · ")
}

@Composable
private fun rememberKeepAliveState(): KeepAliveState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(readKeepAliveState(context)) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = readKeepAliveState(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

private fun readKeepAliveState(context: Context): KeepAliveState {
    val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    } else {
        true
    }
    return KeepAliveState(
        batteryExempt = SonosCastService.isIgnoringBatteryOptimizations(context),
        exactAlarm = exact
    )
}
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingArrow: Boolean = false,
    subtitleMaxLines: Int = 1,
    subtitleColor: Color? = null,
    iconTint: Color? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines
            )
        }
        if (trailingArrow) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = str(Str.COPY),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ManualIpEntry(onAdd: (String) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
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
        FilledTonalIconButton(
            onClick = {
                onAdd(ip.trim())
                ip = ""
            },
            enabled = ip.isNotBlank()
        ) {
            Icon(Icons.Rounded.Add, str(Str.ADD))
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            entry.time,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            levelTag(entry.level),
            style = MonoStyle,
            color = levelColor(entry.level),
            fontWeight = FontWeight.Bold
        )
        Text(
            entry.message,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun levelTag(level: LogLevel): String = when (level) {
    LogLevel.DEBUG -> "DBG"
    LogLevel.INFO -> "INF"
    LogLevel.WARN -> "WRN"
    LogLevel.ERROR -> "ERR"
    LogLevel.TX -> "TX "
    LogLevel.RX -> "RX "
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> Color(0xFFFFB86B)
    LogLevel.TX -> Color(0xFF7ED08A)
    LogLevel.RX -> Color(0xFF7FB6FF)
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun phaseColor(phase: ServicePhase): Color = when (phase) {
    ServicePhase.READY -> Color(0xFF6BE38C)
    ServicePhase.STANDBY -> Color(0xFFF5C518)
    ServicePhase.STARTING -> MaterialTheme.colorScheme.primary
    ServicePhase.ERROR -> MaterialTheme.colorScheme.error
    ServicePhase.STOPPED -> MaterialTheme.colorScheme.outline
}

private fun phaseKey(phase: ServicePhase): Str = when (phase) {
    ServicePhase.STOPPED -> Str.PHASE_STOPPED
    ServicePhase.STARTING -> Str.PHASE_STARTING
    ServicePhase.STANDBY -> Str.PHASE_STANDBY
    ServicePhase.READY -> Str.PHASE_READY
    ServicePhase.ERROR -> Str.PHASE_ERROR
}

// ------------------------------------------------------------------ 关于

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17171A),
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(str(Str.ABOUT)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ZONOZ LOCAL STREAM",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    strf(Str.VERSION, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    str(Str.AUTHOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    str(Str.ABOUT_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Kotlin · Jetpack Compose · NanoHTTPD · OkHttp · UPnP/SOAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(str(Str.CLOSE)) }
        }
    )
}
