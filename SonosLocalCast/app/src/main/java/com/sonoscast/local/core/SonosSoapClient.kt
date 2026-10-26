package com.sonoscast.local.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface SoapResult {

    data class Success(val body: String) : SoapResult

    data class Fault(
        val httpStatus: Int,
        val errorCode: Int?,
        val description: String,
        val raw: String
    ) : SoapResult

    data class TransportError(val message: String, val cause: Throwable?) : SoapResult
}

val SoapResult.faultCode: Int?
    get() = (this as? SoapResult.Fault)?.errorCode

/**
 * 音箱当前的状态快照。
 *
 * 用来解决"App 进程被杀、但音箱还在播"导致的状态脱节 —— 重新进 App 时
 * 主动问音箱，而不是依赖本地内存里那点可能已经丢掉的状态。
 */
data class TransportSnapshot(
    val state: String,
    val uri: String,
    val title: String,
    val positionSec: Long,
    val durationSec: Long
) {
    val isPlaying: Boolean get() = state == "PLAYING"
    val isPaused: Boolean get() = state == "PAUSED_PLAYBACK"
    val hasMedia: Boolean get() = uri.isNotBlank()
}

/**
 * Sonos 的 UPnP 控制客户端。
 *
 * 只做三件事：拼 SOAP 信封、POST 到 1400 端口、把 UPnP Fault 翻译成错误码。
 * 没有引入任何 UPnP 协议栈 —— AVTransport 的报文结构很简单，
 * 自己拼反而能精确控制 metadata 的转义和 Content-Type。
 *
 * S2 说明：S2 固件仍然完整暴露 `http://<ip>:1400/MediaRenderer/AVTransport/Control`。
 * 说"S2 下 UPnP 失效"的说法通常指的是 Sonos 官方 App 的旧版控制协议，
 * 与标准 UPnP 无关。
 */
class SonosSoapClient {

    private val xmlMediaType = "text/xml; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------ AVTransport

    suspend fun setAvTransportUri(
        device: SonosDevice,
        uri: String,
        metadata: String
    ): SoapResult {
        // 701 排查的第一手资料：必须把真正发出去的 URL 打进日志
        LogBus.tx("SetAVTransportURI → ${device.displayName}(${device.ip}) CurrentURI=$uri")
        return avTransport(
            device,
            "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata
            )
        )
    }

    suspend fun play(device: SonosDevice): SoapResult =
        avTransport(
            device,
            "Play",
            listOf("InstanceID" to "0", "Speed" to "1")
        )

    suspend fun pause(device: SonosDevice): SoapResult =
        avTransport(device, "Pause", listOf("InstanceID" to "0"))

    suspend fun stop(device: SonosDevice): SoapResult =
        avTransport(device, "Stop", listOf("InstanceID" to "0"))

    suspend fun seek(device: SonosDevice, seconds: Long): SoapResult =
        avTransport(
            device,
            "Seek",
            listOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to SoapXml.timeString(seconds)
            )
        )

    /** 返回 PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING 等 */
    suspend fun getTransportState(device: SonosDevice): String? {
        val result = avTransport(device, "GetTransportInfo", listOf("InstanceID" to "0"))
        return if (result is SoapResult.Success) {
            TRANSPORT_STATE.find(result.body)?.groupValues?.get(1)
        } else {
            null
        }
    }

    /**
     * 播放进度：返回 (当前秒, 总秒)。
     * 用于 UI 的进度条 —— Sonos 是推流播放，本地没有播放器实例，只能靠轮询。
     */
    suspend fun getPositionInfo(device: SonosDevice): Pair<Long, Long>? {
        val result = avTransport(device, "GetPositionInfo", listOf("InstanceID" to "0"))
        if (result !is SoapResult.Success) return null
        val position = parseTime(POSITION.find(result.body)?.groupValues?.get(1))
        val duration = parseTime(TRACK_DURATION.find(result.body)?.groupValues?.get(1))
        if (position <= 0L && duration <= 0L) return null
        return position to duration
    }

    /** "0:01:23" / "1:23" 都可能出现，统一按冒号分段累加 */
    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        var total = 0L
        raw.trim().split(':').forEach { part ->
            total = total * 60 + (part.trim().toLongOrNull() ?: 0L)
        }
        return total
    }

    /**
     * 拉一次完整快照：当前在放什么、放到哪、总长多少、什么状态。
     * GetPositionInfo 给 URI/元数据/进度，GetTransportInfo 另给播放状态，所以要发两次。
     */
    suspend fun getTransportSnapshot(device: SonosDevice): TransportSnapshot? {
        val info = avTransport(device, "GetPositionInfo", listOf("InstanceID" to "0"))
        if (info !is SoapResult.Success) return null

        val transport = avTransport(device, "GetTransportInfo", listOf("InstanceID" to "0"))
        val state = (transport as? SoapResult.Success)
            ?.let { TRANSPORT_STATE.find(it.body)?.groupValues?.get(1) }
            ?: "STOPPED"

        val uri = TRACK_URI.find(info.body)?.groupValues?.get(1).orEmpty()
        val rawMeta = TRACK_META.find(info.body)?.groupValues?.get(1).orEmpty()
        // TrackMetaData 是**两层**转义的 DIDL：
        //   &lt;DIDL-Lite&gt;&lt;dc:title&gt;You&amp;apos;re Beautiful&lt;/dc:title&gt;...
        // 解一层拿到 DIDL 文本，正则取出的标题里仍然带 &apos;，所以要再解一次，
        // 否则界面会显示 "You&apos;re Beautiful"，而且按标题回曲库找歌也永远匹配不上。
        val didl = unescapeXml(rawMeta)
        val title = unescapeXml(DC_TITLE.find(didl)?.groupValues?.get(1).orEmpty())

        return TransportSnapshot(
            state = state,
            uri = uri,
            title = title,
            positionSec = parseTime(POSITION.find(info.body)?.groupValues?.get(1)),
            durationSec = parseTime(TRACK_DURATION.find(info.body)?.groupValues?.get(1))
        )
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    // ------------------------------------------------------- RenderingControl

    suspend fun setVolume(device: SonosDevice, volume: Int): SoapResult = call(
        device = device,
        serviceType = SoapXml.SERVICE_RENDERING_CONTROL,
        controlPath = device.renderingControlUrl,
        action = "SetVolume",
        args = listOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            "DesiredVolume" to volume.coerceIn(0, 100).toString()
        )
    )

    suspend fun getVolume(device: SonosDevice): Int? {
        val result = call(
            device = device,
            serviceType = SoapXml.SERVICE_RENDERING_CONTROL,
            controlPath = device.renderingControlUrl,
            action = "GetVolume",
            args = listOf("InstanceID" to "0", "Channel" to "Master")
        )
        return if (result is SoapResult.Success) {
            CURRENT_VOLUME.find(result.body)?.groupValues?.get(1)?.toIntOrNull()
        } else {
            null
        }
    }

    // ------------------------------------------------------- ZoneGroupTopology

    /**
     * 读取家庭区域组拓扑。
     *
     * 任意一台在线音箱都能回答，返回的是**全局**拓扑，所以问一次就能知道
     * 哪些设备是立体声配对里的从箱、谁是协调者（主箱）。
     */
    suspend fun getZoneGroupState(device: SonosDevice): String? {
        val result = call(
            device = device,
            serviceType = SoapXml.SERVICE_ZONE_GROUP_TOPOLOGY,
            controlPath = ZONE_GROUP_TOPOLOGY_PATH,
            action = "GetZoneGroupState",
            args = emptyList()
        )
        return (result as? SoapResult.Success)?.body
    }

    // ------------------------------------------------------------------ 底层

    private suspend fun avTransport(
        device: SonosDevice,
        action: String,
        args: List<Pair<String, String>>
    ): SoapResult = call(
        device = device,
        serviceType = SoapXml.SERVICE_AV_TRANSPORT,
        controlPath = device.avTransportControlUrl,
        action = action,
        args = args
    )

    private suspend fun call(
        device: SonosDevice,
        serviceType: String,
        controlPath: String,
        action: String,
        args: List<Pair<String, String>>
    ): SoapResult = withContext(Dispatchers.IO) {
        val url = "http://${device.ip}:${SonosDevice.PORT}$controlPath"
        val payload = SoapXml.envelope(serviceType, action, args)
        LogBus.tx("POST $url · $action")

        val request = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .header("Connection", "close")
            .post(payload.toRequestBody(xmlMediaType))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val errorCode = ERROR_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull()
                val errorDescription = ERROR_DESCRIPTION.find(body)?.groupValues?.get(1)?.trim().orEmpty()

                if (response.isSuccessful && errorCode == null) {
                    LogBus.rx("$action ← HTTP ${response.code} OK")
                    SoapResult.Success(body)
                } else {
                    LogBus.rx(
                        "$action ← HTTP ${response.code} · UPnP ${errorCode ?: "-"} " +
                            "${errorDescription.ifBlank { describeHttp(response.code) }}"
                    )
                    if (body.isNotBlank()) {
                        LogBus.rx("Fault Body: ${clip(body, 400)}")
                    }
                    SoapResult.Fault(
                        httpStatus = response.code,
                        errorCode = errorCode,
                        description = errorDescription.ifBlank { describeHttp(response.code) },
                        raw = body
                    )
                }
            }
        } catch (t: IOException) {
            LogBus.e("$action 网络异常：${t.javaClass.simpleName} ${t.message ?: ""}")
            SoapResult.TransportError(t.message ?: t.javaClass.simpleName, t)
        } catch (t: Throwable) {
            LogBus.e("$action 执行失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            SoapResult.TransportError(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun describeHttp(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found（控制 URL 可能不对）"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error（通常是 UPnP Fault）"
        503 -> "Service Unavailable"
        else -> "HTTP $code"
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…(${text.length} chars)"

    companion object {
        private const val ZONE_GROUP_TOPOLOGY_PATH = "/ZoneGroupTopology/Control"

        private val ERROR_CODE = Regex("<errorCode>\\s*(-?\\d+)\\s*</errorCode>")
        private val ERROR_DESCRIPTION = Regex("<errorDescription>([^<]*)</errorDescription>")
        private val TRANSPORT_STATE = Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>")
        private val CURRENT_VOLUME = Regex("<CurrentVolume>\\s*(\\d+)\\s*</CurrentVolume>")
        private val POSITION = Regex("<RelTime>([^<]*)</RelTime>")
        private val TRACK_DURATION = Regex("<TrackDuration>([^<]*)</TrackDuration>")
        private val TRACK_URI = Regex("<TrackURI>([^<]*)</TrackURI>")
        private val TRACK_META = Regex("<TrackMetaData>([^<]*)</TrackMetaData>")
        private val DC_TITLE = Regex("<dc:title>([^<]*)</dc:title>")
    }
}
