package com.sonoscast.local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonoscast.local.core.Str

/**
 * 隐私声明（设置 → 隐私声明）。
 *
 * 事实清单，不是模板套话：
 * - 应用没有联网后端、没有统计/广告/崩溃上报 SDK
 * - 唯一的网络行为是局域网内对音箱发 UPnP/SOAP，以及手机自己开的 HTTP 媒体服务
 * - 曲库元数据、播放位置、收藏都只写在本机 SharedPreferences
 */
@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17171A),
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(22.dp),
        title = { Text(str(Str.PRIVACY)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "ZONOZ local stream · 隐私声明 / Privacy",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(BODY, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(str(Str.CLOSE)) }
        }
    )
}

private const val BODY = """本应用不收集、不上传任何个人数据。

1. 不联网
应用没有服务器后端，也不含统计、广告、崩溃上报或推送 SDK。唯一的外部通信是在
局域网内向你的 Sonos 音箱发送 UPnP/SOAP 指令，以及手机自己开启的 HTTP 媒体服务
（供音箱拉取音频，仅监听局域网，不暴露到公网）。

2. 权限与用途
· 音乐和音频：读取本地曲库，用于显示与播放
· 本地网络：发现音箱、推流
· 通知 / 前台服务：锁屏控制与后台续播
· 忽略电池优化、精确闹钟（可选）：锁屏长时间播放不被系统打断

3. 数据存在哪
曲库元数据、播放位置、收藏、语言与文字大小等设置，只写入本机的
SharedPreferences 与 MediaStore 缓存，不上传、不备份到云端。

4. 删除
卸载应用即清除上述全部本地数据。应用不会留存任何服务端副本（因为没有服务端）。

5. 第三方
音频传输协议为 UPnP/SOAP，仅与局域网内的音箱通信；不向任何第三方发送数据。
开源组件清单见「设置 → 开源许可」。

—— English ——
This app collects nothing and uploads nothing. There is no backend, no analytics,
no ads and no crash reporting. Its only network traffic is UPnP/SOAP to your Sonos
speakers on the local network, plus a local HTTP server (LAN-only) that the speaker
pulls audio from. Library metadata, playback position, favourites and preferences are
stored on-device only and removed when you uninstall the app."""
