package com.sonoscast.local.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 从专辑封面里取一个"能用"的主色。
 *
 * 用途：播放页顶栏（状态栏区域）叠一层从主色到透明的渐变，
 * 让白色的状态栏文字/图标有对比度，同时不破坏整体纯黑调性。
 *
 * 规则：
 * - 缩到 32×32 再统计，避免逐像素遍历大图；
 * - 只统计"够鲜艳"的像素（避免大片黑边/白底把颜色拉灰）；
 * - 结果太暗/太亮时往中间拉，保证叠在黑色上仍可见、又不刺眼；
 * - 取不到合适颜色就返回 null，调用方退回纯黑。
 */
object CoverPalette {

    fun of(bitmap: Bitmap?): Color? {
        val src = bitmap ?: return null
        if (src.width <= 0 || src.height <= 0) return null

        val small = runCatching {
            Bitmap.createScaledBitmap(src, 32, 32, true)
        }.getOrNull() ?: return null

        val pixels = IntArray(small.width * small.height)
        runCatching { small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height) }
            .onFailure { return null }
        if (small !== src) runCatching { small.recycle() }

        var weightSum = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        val hsv = FloatArray(3)
        for (p in pixels) {
            if (AndroidColor.alpha(p) < 200) continue
            AndroidColor.colorToHSV(p, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            // 灰/白/黑的权重压到很低，只让真正带颜色的区域说话
            val weight = saturation * saturation * (1.0 - abs(value - 0.55) * 0.8)
            if (weight <= 0.02) continue
            weightSum += weight
            r += AndroidColor.red(p) * weight
            g += AndroidColor.green(p) * weight
            b += AndroidColor.blue(p) * weight
        }
        if (weightSum < 0.5) return null

        var color = AndroidColor.rgb(
            (r / weightSum).toInt().coerceIn(0, 255),
            (g / weightSum).toInt().coerceIn(0, 255),
            (b / weightSum).toInt().coerceIn(0, 255)
        )
        AndroidColor.colorToHSV(color, hsv)
        // 太暗看不见、太亮发白：统一压到 0.45~0.72 的明度区间
        hsv[2] = hsv[2].coerceIn(0.45f, 0.72f)
        // 保证有一点饱和度，否则等于灰色蒙层
        hsv[1] = hsv[1].coerceAtLeast(0.25f)
        color = AndroidColor.HSVToColor(hsv)

        return Color(
            red = AndroidColor.red(color) / 255f,
            green = AndroidColor.green(color) / 255f,
            blue = AndroidColor.blue(color) / 255f
        )
    }
}
