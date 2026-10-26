package com.sonoscast.local.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 给通知里的专辑图合成品牌角标。
 *
 * 为什么要自己做：Android 13+ 的媒体通知会把 App 的 **small icon** 叠在专辑图右下角，
 * 位置、大小、颜色全由系统/ROM 决定，改不了。所以这里把 small icon 换成透明，
 * 再由我们自己在 large icon 上画一个"白底黑字 ZONOZ"的角标 ——
 * 大小、留白、圆角都完全可控。
 *
 * 角标规格（相对封面尺寸）：
 * - 边长 ≈ 25%（要求"约 1/4"）
 * - 距右下边缘 ≈ 7%（要求"不贴边、留出间距"）
 * - 白底圆角方块 + 黑色 ZONOZ，和桌面图标同一套视觉
 */
object NotifArt {

    private const val BADGE_RATIO = 0.3125f
    private const val MARGIN_RATIO = 0.07f
    private const val RADIUS_RATIO = 0.22f

    /** 字距按字号的比例，必须和桌面图标（make_icons.py 的 SPACING_EM）一致 */
    private const val SPACING_EM = 0.114f

    private const val BRAND = "ZONOZ"

    /**
     * 把 [cover] 居中裁成正方形，再在右下角合成 ZONOZ 角标。
     * 任何异常都退回原封面，绝不因为画角标让通知崩掉。
     */
    fun withBrandBadge(cover: Bitmap, size: Int = 512): Bitmap = runCatching {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1) 封面居中裁切铺满
        val side = minOf(cover.width, cover.height)
        val src = Rect(
            (cover.width - side) / 2,
            (cover.height - side) / 2,
            (cover.width + side) / 2,
            (cover.height + side) / 2
        )
        canvas.drawBitmap(cover, src, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))

        // 2) 右下角品牌角标
        val badge = size * BADGE_RATIO
        val margin = size * MARGIN_RATIO
        val left = size - badge - margin
        val top = size - badge - margin
        val rect = RectF(left, top, left + badge, top + badge)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(rect, badge * RADIUS_RATIO, badge * RADIUS_RATIO, fill)

        // 3) 黑字 ZONOZ（逐字绘制，字距和桌面图标同一个比例）
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val chars = BRAND.map { it.toString() }
        val maxWidth = badge * 0.74f
        var textSize = badge * 0.42f
        var spacing = textSize * SPACING_EM
        while (textSize > 1f) {
            text.textSize = textSize
            spacing = textSize * SPACING_EM
            val total = chars.sumOf { text.measureText(it).toDouble() }.toFloat() +
                spacing * (chars.size - 1)
            if (total <= maxWidth) break
            textSize -= 0.5f
        }

        val textWidth = chars.sumOf { text.measureText(it).toDouble() }.toFloat() +
            spacing * (chars.size - 1)
        var x = rect.centerX() - textWidth / 2f
        val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2f
        chars.forEach { ch ->
            canvas.drawText(ch, x, baseline, text)
            x += text.measureText(ch) + spacing
        }

        out
    }.getOrDefault(cover)
}
