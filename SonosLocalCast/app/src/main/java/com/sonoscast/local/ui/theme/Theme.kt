package com.sonoscast.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sonoscast.local.core.I18n
import com.sonoscast.local.core.LocalStrings
import com.sonoscast.local.core.UiScale

/**
 * 纯黑白主题。
 *
 * 播放器只留黑、白、以及不同透明度的白 —— 强调色就是白色本身：
 * 白色实心播放键 + 黑色图标，进度条白色，选中态靠亮度和描边区分，不引入任何彩色。
 */
private val MonoColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1F1F22),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFB4B4BC),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF242428),
    onSecondaryContainer = Color(0xFFEDEDF0),
    tertiary = Color(0xFF8C8C94),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF7F7F9),
    surface = Color(0xFF0C0C0E),
    onSurface = Color(0xFFF7F7F9),
    surfaceVariant = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFF9C9CA4),
    outline = Color(0xFF3C3C42),
    outlineVariant = Color(0xFF232327),
    error = Color(0xFFFF5C5C),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A0F0F),
    onErrorContainer = Color(0xFFFFB4B4)
)

@Composable
fun SonosCastTheme(content: @Composable () -> Unit) {
    // 语言表通过 CompositionLocal 统一下发：切换语言时，所有调用 str() 的界面
    // 会自动重组，不需要每个 Composable 各自去订阅 I18n.current。
    val lang by I18n.current.collectAsState()

    // 文字大小：只放大 fontScale，不动 density ——
    // 这样所有 sp 字号（含 Material 组件）整体缩放，而 dp 尺寸（图标/间距/圆角）保持不变。
    // 用户的系统字体缩放会以乘法叠加，符合"跟随系统再按 App 设置微调"的直觉。
    val uiScale by UiScale.scale.collectAsState()
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, uiScale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * uiScale)
    }

    CompositionLocalProvider(
        LocalStrings provides I18n.table(lang),
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = MonoColors,
            content = content
        )
    }
}
