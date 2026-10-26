package com.sonoscast.local.core

enum class DiscoverySource {
    /** 通过 SSDP M-SEARCH 自动发现 */
    SSDP,

    /** 用户手动输入 IP */
    MANUAL
}

/**
 * 一台 Sonos 音箱（ZonePlayer）在控制面所需的全部信息。
 *
 * 注意：S1 / S2 固件的 UPnP 服务路径完全一致，`http://<ip>:1400/MediaRenderer/AVTransport/Control`
 * 在 S2 上依然有效 —— 变的只是 Sonos 自家 App 的控制通道（走 WebSocket + 云端），
 * 与我们使用的标准 UPnP 无关。
 */
data class SonosDevice(
    val uuid: String,
    val ip: String,
    val friendlyName: String,
    val roomName: String,
    val modelName: String,
    val location: String,
    val avTransportControlUrl: String = DEFAULT_AV_TRANSPORT_PATH,
    val renderingControlUrl: String = DEFAULT_RENDERING_CONTROL_PATH,
    val isSonos: Boolean = true,
    val source: DiscoverySource = DiscoverySource.SSDP,
    /**
     * 立体声配对 / 家庭影院里的"从设备"。
     *
     * 这类设备在 ZoneGroupTopology 里带 `Invisible="1"`，且 UUID != 所属 ZoneGroup 的 Coordinator。
     * 对它们发 SetAVTransportURI 会返回 Sonos 私有错误 1023。
     * 正确做法是只推主箱，从箱会自动同步 —— 不是故障。
     */
    val isSatellite: Boolean = false,
    /** 配对中的声道角色：LF（左）/ RF（右）/ 空 */
    val channelRole: String = "",
    /**
     * 固件代次：设备描述里的 `<swGen>`，S1 = 1，S2 = 2，0 表示没读到。
     *
     * S1（7.x 固件）与 S2（8x.x）暴露的 UPnP 服务基本一致，但老固件在
     * 服务发现、部分动作的支持度上有差异，所以控制层按它做降级判断。
     */
    val swGen: Int = 0,
    /** 固件版本号，如 `86.10-81100`（S2）或 `70.x-xxxxx`（S1），仅用于诊断展示 */
    val softwareVersion: String = ""
) {
    /** 展示用名称：优先房间名，其次 friendlyName，最后退化为 IP */
    val displayName: String
        get() = roomName.ifBlank { friendlyName }.ifBlank { ip }

    /** 用于 UI 选择集合的稳定 key */
    val key: String
        get() = uuid.ifBlank { ip }

    val isPlay1: Boolean
        get() = modelName.contains("Play:1", ignoreCase = true)

    /** 老 S1 固件：推送时按最小指令集处理 */
    val isLegacyS1: Boolean
        get() = swGen == 1

    /** 界面上显示的代次徽标（未知时不显示） */
    val generationLabel: String
        get() = when (swGen) {
            1 -> "S1"
            2 -> "S2"
            else -> ""
        }

    companion object {
        const val PORT = 1400
        const val DEFAULT_AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control"
        const val DEFAULT_RENDERING_CONTROL_PATH = "/MediaRenderer/RenderingControl/Control"
        const val DEVICE_DESCRIPTION_PATH = "/xml/device_description.xml"

        /** Sonos 专属的 SSDP 设备类型，比 ssdp:all 干净得多 */
        const val ST_ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"
    }
}
