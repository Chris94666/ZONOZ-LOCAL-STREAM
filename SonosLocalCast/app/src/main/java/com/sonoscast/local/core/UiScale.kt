package com.sonoscast.local.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面文字大小（整体缩放）。
 *
 * 实现方式是乘在 `LocalDensity.fontScale` 上：Compose 里所有 `sp` 字号都会跟着变，
 * 包括 Material 组件自带的排版，不需要逐个改 `fontSize`。
 *
 * 只影响字体、不影响 `dp` 尺寸（图标、间距保持原样），所以放大后布局不会被撑破。
 *
 * 多语言注意：缩放系数与语言无关，7 种语言都套同一个倍数；文案本身的长短差异
 * 靠既有布局消化（列表行 maxLines + ellipsis，弹窗文字可滚动）。
 */
object UiScale {

    private const val PREFS = "sonoscast.prefs"
    private const val KEY_SCALE = "ui_font_scale"

    /** 可选档位：0.85 小 / 1.0 标准 / 1.15 大 / 1.3 特大 */
    val OPTIONS = listOf(0.85f, 1.0f, 1.15f, 1.3f)

    const val DEFAULT = 1.0f

    private val _scale = MutableStateFlow(DEFAULT)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    fun init(context: Context) {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SCALE, DEFAULT)
        // 只接受预设档位，避免脏数据把界面搞成 0.1 倍
        _scale.value = OPTIONS.minByOrNull { kotlin.math.abs(it - saved) } ?: DEFAULT
    }

    fun set(context: Context, value: Float) {
        val safe = OPTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT
        _scale.value = safe
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCALE, safe)
            .apply()
    }

    /** 档位对应的文案键（0.85 → 小，1.0 → 标准 …） */
    fun labelKey(value: Float): Str = when {
        value < 0.95f -> Str.FONT_SMALL
        value < 1.10f -> Str.FONT_NORMAL
        value < 1.25f -> Str.FONT_LARGE
        else -> Str.FONT_XLARGE
    }
}
