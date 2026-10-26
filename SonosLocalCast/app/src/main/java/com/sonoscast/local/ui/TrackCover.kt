package com.sonoscast.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonoscast.local.core.CoverLoader
import com.sonoscast.local.core.Track

/**
 * 曲目缩略图（专辑封面）。
 *
 * 曲库和播放清单共用同一个实现：先看 LruCache（同步命中就不会闪），
 * 没命中再异步加载；加载不出来就显示音符占位。
 */
@Composable
fun TrackCover(
    track: Track,
    size: Dp = 46.dp,
    corner: Dp = 10.dp
) {
    val context = LocalContext.current
    var bitmap by remember(track.id) { mutableStateOf(CoverLoader.peek(track)) }

    LaunchedEffect(track.id) {
        if (bitmap == null) {
            bitmap = CoverLoader.load(context, track)
        }
    }

    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(size / 2.3f),
                tint = Color.White.copy(alpha = 0.35f)
            )
        }
    }
}
