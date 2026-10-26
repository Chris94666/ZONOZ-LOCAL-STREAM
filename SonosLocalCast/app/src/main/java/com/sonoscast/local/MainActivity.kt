package com.sonoscast.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.sonoscast.local.core.LogBus
import com.sonoscast.local.core.MusicLibrary
import com.sonoscast.local.service.SonosCastService
import com.sonoscast.local.ui.DebugDetailScreen
import com.sonoscast.local.ui.LibraryScreen
import com.sonoscast.local.ui.PlayerScreen
import com.sonoscast.local.ui.PlayerViewModel
import com.sonoscast.local.ui.QueueScreen
import com.sonoscast.local.ui.SettingsScreen
import com.sonoscast.local.ui.theme.SonosCastTheme

/**
 * 单 Activity + Compose。
 *
 * 页面层级（决定滑动方向）：
 * ```
 * 播放页 ──┬── 曲库
 *          ├── 播放清单
 *          └── 设置 ── 调试（三级）
 * ```
 * 从浅层进深层向左滑入，返回向右滑出；根容器铺纯黑，过渡时不会闪白。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝不影响前台服务，只是通知不显示
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonosCastTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .globalTapHaptics()
                ) {
                    AppRoot(viewModel)
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        SonosCastService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(vm: PlayerViewModel) {
    val context = LocalContext.current

    var screen by rememberSaveable { mutableStateOf(SCREEN_PLAYER) }
    var hasAudioPermission by remember { mutableStateOf(MusicLibrary.hasPermission(context)) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            LogBus.i("已获得音频读取权限，开始扫描曲库")
            vm.scanLibrary()
        } else {
            LogBus.w("用户拒绝了音频读取权限，曲库不可用")
        }
    }

    LaunchedEffect(Unit) {
        // 先恢复上次选的音乐文件夹，再决定是否要扫曲库
        MusicLibrary.restoreFolder(context)
        if (hasAudioPermission && !MusicLibrary.loaded.value) {
            vm.scanLibrary()
        }
    }

    // 返回：三级页回设置，其余回播放页
    BackHandler(enabled = screen != SCREEN_PLAYER) {
        screen = if (screen == SCREEN_DEBUG) SCREEN_SETTINGS else SCREEN_PLAYER
    }

    AnimatedContent(
        targetState = screen,
        label = "screen",
        transitionSpec = {
            // 曲库 / 播放清单是"从底部拉起来"的抽屉式页面 → 竖向；
            // 设置是多层导航 → 横向。方向按"谁更深"决定，返回时反向。
            val vertical = targetState == SCREEN_LIBRARY || targetState == SCREEN_QUEUE ||
                initialState == SCREEN_LIBRARY || initialState == SCREEN_QUEUE
            val entering = screenDepth(targetState) >= screenDepth(initialState)
            val direction = when {
                vertical && entering -> AnimatedContentTransitionScope.SlideDirection.Up
                vertical -> AnimatedContentTransitionScope.SlideDirection.Down
                entering -> AnimatedContentTransitionScope.SlideDirection.Start
                else -> AnimatedContentTransitionScope.SlideDirection.End
            }
            val duration = if (entering) 280 else 240
            (
                slideIntoContainer(direction, tween(duration, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(duration / 2))
                ).togetherWith(
                slideOutOfContainer(direction, tween(duration, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(duration / 2))
            ).using(SizeTransform(clip = false))
        }
    ) { current ->
        when (current) {
            SCREEN_LIBRARY -> LibraryScreen(
                vm = vm,
                hasPermission = hasAudioPermission,
                onBack = { screen = SCREEN_PLAYER },
                onRequestPermission = {
                    audioPermissionLauncher.launch(MusicLibrary.requiredPermission())
                }
            )

            SCREEN_QUEUE -> QueueScreen(
                vm = vm,
                onBack = { screen = SCREEN_PLAYER }
            )

            SCREEN_SETTINGS -> SettingsScreen(
                vm = vm,
                onBack = { screen = SCREEN_PLAYER },
                onOpenDebug = { screen = SCREEN_DEBUG }
            )

            SCREEN_DEBUG -> DebugDetailScreen(
                vm = vm,
                onBack = { screen = SCREEN_SETTINGS }
            )

            else -> PlayerScreen(
                vm = vm,
                onOpenLibrary = {
                    if (!hasAudioPermission) {
                        audioPermissionLauncher.launch(MusicLibrary.requiredPermission())
                    } else if (!MusicLibrary.loaded.value) {
                        vm.scanLibrary()
                    }
                    screen = SCREEN_LIBRARY
                },
                onOpenQueue = { screen = SCREEN_QUEUE },
                onOpenSettings = { screen = SCREEN_SETTINGS }
            )
        }
    }
}

// ------------------------------------------------------------------ 全局触觉

/**
 * 全局点击触感。
 *
 * 在根布局上旁听指针事件（Initial 阶段、不消费），只要「按下→抬起」位移很小、
 * 时间够短，就认为是一次点击，发一下 `CLOCK_TICK`。
 *
 * 这样做的好处：不用给每个 IconButton / TextButton / 列表行单独加代码，
 * 新增界面自动就有反馈；拖动（音量、进度条）位移大，不会被误判成点击。
 */
@Composable
private fun Modifier.globalTapHaptics(): Modifier {
    val view = LocalView.current
    return this.pointerInput(view) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val downTime = SystemClock.uptimeMillis()
                val downPosition = down.position
                var upPosition: Offset? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        upPosition = change.position
                        break
                    }
                }
                val held = SystemClock.uptimeMillis() - downTime
                val moved = upPosition?.let { (it - downPosition).getDistance() } ?: Float.MAX_VALUE
                if (held in 0..600 && moved < 28f) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }
}

private const val SCREEN_PLAYER = "player"
private const val SCREEN_LIBRARY = "library"
private const val SCREEN_QUEUE = "queue"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_DEBUG = "debug"

/** 层级越深越"靠里"，用来决定滑动方向 */
private fun screenDepth(screen: String): Int = when (screen) {
    SCREEN_PLAYER -> 0
    SCREEN_DEBUG -> 2
    else -> 1
}
