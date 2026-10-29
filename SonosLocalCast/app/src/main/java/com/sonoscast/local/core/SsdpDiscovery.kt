package com.sonoscast.local.core

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.MulticastSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * SSDP 音箱发现。
 *
 * 策略（针对 S1/S2 都验证过的组合）：
 * 1. 先以 Sonos 专属 ST `urn:schemas-upnp-org:device:ZonePlayer:1` 发 M-SEARCH，
 *    回包干净、几乎只有 ZonePlayer 会应答；
 * 2. 若 1.5s 内无结果，再用 `ssdp:all` 兜一轮（部分老固件只对 ssdp:all 应答）；
 * 3. 总预算默认 3s，超时即返回空列表 —— 由上层进入"纯本地待命模式"，绝不抛异常。
 *
 * 关键实现点：
 * - DatagramSocket 显式绑定到 WiFi 网卡的本地地址，避免开了蜂窝/VPN 时从错误接口发出；
 * - 需要 MulticastLock，否则部分 ROM 会丢弃组播回包；
 * - 收到 LOCATION 后必须再 GET 一次设备描述，才能拿到 roomName / UUID / 控制路径。
 */
class SsdpDiscovery(private val context: Context) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 执行一次发现。永不抛异常；失败返回空列表。
     */
    suspend fun discover(totalTimeoutMs: Long = 3000L): List<SonosDevice> = withContext(Dispatchers.IO) {
        val lock = NetUtils.acquireMulticastLock(context, "sonoscast-ssdp")
        val headers = LinkedHashMap<String, Map<String, String>>()
        try {
            val deadline = System.currentTimeMillis() + totalTimeoutMs
            openSocket()?.use { socket ->
                // 第一轮：Sonos 专属 ST
                sendSearch(socket, SonosDevice.ST_ZONE_PLAYER)
                receiveLoop(
                    socket = socket,
                    sink = headers,
                    until = minOf(deadline, System.currentTimeMillis() + 1600L)
                )

                // 第二轮补齐：S1 老固件经常只对 ssdp:all 应答（ZonePlayer ST 回得很晚或干脆不回），
                // 所以只要还有时间预算就再扫一轮，两轮结果按 LOCATION 去重。
                if (System.currentTimeMillis() < deadline) {
                    if (headers.isEmpty()) {
                        LogBus.d("ZonePlayer ST 无响应，改用 ssdp:all 再试一轮")
                    } else {
                        LogBus.d("已收到 ${headers.size} 个响应，补一轮 ssdp:all 以兼容 S1 老固件")
                    }
                    sendSearch(socket, "ssdp:all")
                    receiveLoop(socket = socket, sink = headers, until = deadline)
                }
            }
        } catch (t: Throwable) {
            LogBus.w("SSDP 发现异常：${t.javaClass.simpleName} ${t.message ?: ""}")
        } finally {
            runCatching { lock?.release() }
        }

        if (headers.isEmpty()) {
            LogBus.w("SSDP 未收到任何响应（检查 Wi-Fi、AP 隔离、路由器是否屏蔽 239.255.255.250）")
                if (NetUtils.isVpnActive(context)) {
                    LogBus.w("当前有 VPN / 代理在跑，这是局域网发现的常见失败原因")
                    AppState.hint.value = Hint(Str.VPN_ACTIVE)
                }
            return@withContext emptyList()
        }

        val sonosOnly = headers.values.filter { looksLikeSonos(it) }
        LogBus.i("SSDP 收到 ${headers.size} 个响应，其中疑似 Sonos ${sonosOnly.size} 个")

        val devices = sonosOnly
            .map { h -> async { fetchDescription(h["location"].orEmpty(), DiscoverySource.SSDP) } }
            .awaitAll()
            .filterNotNull()

        devices
    }

    /** 手动添加：直接按 IP 探测描述文件，失败也返回一个最小可用条目，让用户可以强行推送 */
    suspend fun probeByIp(ip: String): SonosDevice? = withContext(Dispatchers.IO) {
        val hostPort = ip.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (hostPort.isBlank()) return@withContext null
        val host = hostPort.substringBefore(':').trim()
        if (host.isBlank()) return@withContext null
        val location = "http://$host:${SonosDevice.PORT}${SonosDevice.DEVICE_DESCRIPTION_PATH}"
        fetchDescription(location, DiscoverySource.MANUAL)
            ?: SonosDevice(
                uuid = "",
                ip = host,
                friendlyName = host,
                roomName = host,
                modelName = "未知设备",
                location = location,
                isSonos = false,
                source = DiscoverySource.MANUAL
            )
    }

    // ---------------------------------------------------------------- socket

    private fun openSocket(): DatagramSocket? = runCatching {
        // 必须用 MulticastSocket 才能指定"从哪块网卡发组播"。
        // 用普通 DatagramSocket 时，开了 VPN 的系统会按默认路由把 M-SEARCH 送进 tun，
        // 结果就是"局域网里明明有音箱却一个都搜不到"（用户实测）。
        val socket = MulticastSocket(null as SocketAddress?)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = 500
        val bindIp = NetUtils.currentLanIp(context)
        val bindAddress = if (bindIp != null) {
            InetAddress.getByName(bindIp)
        } else {
            InetAddress.getByName("0.0.0.0")
        }
        socket.bind(InetSocketAddress(bindAddress, 0))
        NetUtils.lanNetworkInterface()?.let { nif ->
            runCatching {
                socket.networkInterface = nif
                socket.timeToLive = 4
            }.onFailure { LogBus.w("SSDP 网卡绑定失败：${it.message}") }
        }
        if (NetUtils.isVpnActive(context)) {
            LogBus.w("检测到 VPN：已把 SSDP 钉在 Wi-Fi 网卡上，若仍搜不到请关闭 VPN")
        }
        socket
    }.getOrElse {
        LogBus.w("SSDP socket 创建失败：${it.message}")
        null
    }

    private fun sendSearch(socket: DatagramSocket, st: String) {
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: $st\r\n")
            append("USER-AGENT: $USER_AGENT\r\n")
            append("\r\n")
        }
        val payload = message.toByteArray(Charsets.UTF_8)
        val group = InetAddress.getByName(SSDP_HOST)
        // UDP 会丢包，连发两次
        repeat(2) {
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
            }.onFailure { LogBus.w("M-SEARCH 发送失败：${it.message}") }
        }
        LogBus.d("→ M-SEARCH ST=$st")
    }

    private suspend fun receiveLoop(
        socket: DatagramSocket,
        sink: MutableMap<String, Map<String, String>>,
        until: Long
    ) {
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < until && currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = parseHeaders(text)
                val location = headers["location"]
                if (!location.isNullOrBlank()) {
                    sink.putIfAbsent(location.lowercase(), headers)
                }
            } catch (_: SocketTimeoutException) {
                // 正常：继续等到 deadline
            } catch (t: Throwable) {
                if (!currentCoroutineContext().isActive) return
                LogBus.d("SSDP 接收中断：${t.message}")
                return
            }
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        raw.lineSequence().drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty() && key !in map) map[key] = value
            }
        }
        return map
    }

    private fun looksLikeSonos(headers: Map<String, String>): Boolean {
        val server = headers["server"].orEmpty()
        val location = headers["location"].orEmpty()
        val st = headers["st"].orEmpty()
        val usn = headers["usn"].orEmpty()
        return server.contains("sonos", true) ||
            server.contains("zoneplayer", true) ||
            location.contains(":${SonosDevice.PORT}/") ||
            st.contains("ZonePlayer", true) ||
            usn.contains("RINCON", true)
    }

    // ------------------------------------------------------------ description

    private fun fetchDescription(location: String, source: DiscoverySource): SonosDevice? {
        if (location.isBlank()) return null
        return runCatching {
            val request = Request.Builder()
                .url(location)
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogBus.w("设备描述请求失败 HTTP ${response.code} · $location")
                    return@use null
                }
                val xml = response.body?.string() ?: return@use null
                parseDescription(location, xml, source)
            }
        }.getOrElse {
            LogBus.w("解析设备描述失败：${it.message} · $location")
            null
        }
    }

    private fun parseDescription(
        location: String,
        xml: String,
        source: DiscoverySource
    ): SonosDevice? {
        var friendlyName = ""
        var roomName = ""
        var modelName = ""
        var manufacturer = ""
        var udn = ""
        var avTransport = ""
        var rendering = ""
        var currentServiceType = ""
        var inService = false
        var lastTag = ""
        // S1 / S2 判别：S1 是 <swGen>1</swGen>，S2 是 2
        var swGen = 0
        var softwareVersion = ""

        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        lastTag = parser.name
                        if (parser.name == "service") {
                            inService = true
                            currentServiceType = ""
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (lastTag) {
                                "friendlyName" -> if (friendlyName.isEmpty()) friendlyName = text
                                "roomName" -> roomName = text
                                "modelName" -> if (modelName.isEmpty()) modelName = text
                                "manufacturer" -> if (manufacturer.isEmpty()) manufacturer = text
                                "UDN" -> if (udn.isEmpty()) udn = text
                                "swGen" -> if (swGen == 0) swGen = text.toIntOrNull() ?: 0
                                "softwareVersion" -> if (softwareVersion.isEmpty()) softwareVersion = text
                                "serviceType" -> if (inService) currentServiceType = text
                                "controlURL" -> if (inService) {
                                    // 必须精确匹配服务名：设备描述里同时存在
                                    //   urn:...:service:RenderingControl:1
                                    //   urn:...:service:GroupRenderingControl:1
                                    // 用 contains("RenderingControl") 会让后出现的 Group 版覆盖掉前者，
                                    // 结果 SetVolume 打到 Group 服务上 → UPnP 401 Invalid Action。
                                    when {
                                        currentServiceType.contains(":service:AVTransport:") ->
                                            avTransport = text

                                        currentServiceType.contains(":service:RenderingControl:") ->
                                            rendering = text
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "service") {
                            inService = false
                            currentServiceType = ""
                        }
                        lastTag = ""
                    }
                }
                event = parser.next()
            }
        }.onFailure { LogBus.w("XML 解析异常：${it.message}") }

        val ip = runCatching { URI(location).host }.getOrNull() ?: return null
        val isSonos = manufacturer.contains("sonos", true) ||
            location.contains(":${SonosDevice.PORT}/") ||
            modelName.contains("Sonos", true) ||
            udn.contains("RINCON", true)

        return SonosDevice(
            uuid = udn.removePrefix("uuid:").ifBlank { "RINCON_$ip" },
            ip = ip,
            friendlyName = friendlyName,
            roomName = roomName,
            modelName = modelName.ifBlank { "Sonos" },
            location = location,
            avTransportControlUrl = toPath(avTransport, SonosDevice.DEFAULT_AV_TRANSPORT_PATH),
            renderingControlUrl = toPath(rendering, SonosDevice.DEFAULT_RENDERING_CONTROL_PATH),
            isSonos = isSonos,
            source = source,
            swGen = swGen,
            softwareVersion = softwareVersion
        )
    }

    private fun toPath(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        return when {
            raw.startsWith("http", ignoreCase = true) ->
                runCatching { URI(raw).path ?: fallback }.getOrDefault(fallback)

            raw.startsWith("/") -> raw
            else -> "/$raw"
        }
    }

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val USER_AGENT = "Android/9 UPnP/1.1 SonosLocalCast/1.0"
    }
}
