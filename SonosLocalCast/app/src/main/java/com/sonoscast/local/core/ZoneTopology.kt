package com.sonoscast.local.core

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Sonos 区域组拓扑解析。
 *
 * `ZoneGroupTopology#GetZoneGroupState` 的响应里，`<ZoneGroupState>` 的文本内容是
 * **一整段被转义过的 XML**，形如：
 *
 * ```
 * <ZoneGroup Coordinator="RINCON_AAA01400" ID="RINCON_AAA01400:123456">
 *   <ZoneGroupMember UUID="RINCON_AAA01400" Location="http://192.168.1.4:1400/..." ZoneName="客厅"
 *                    ChannelMapSet="RINCON_AAA01400:RF,RF;RINCON_BBB01400:LF,LF"/>
 *   <ZoneGroupMember UUID="RINCON_BBB01400" Location="http://192.168.1.3:1400/..." ZoneName="客厅"
 *                    Invisible="1" ChannelMapSet="RINCON_AAA01400:RF,RF;RINCON_BBB01400:LF,LF"/>
 * </ZoneGroup>
 * ```
 *
 * 判定从设备有两条独立依据，任一成立即可：
 * 1. `Invisible="1"` —— Sonos 用来标记不单独暴露的卫星箱；
 * 2. `UUID != Coordinator` —— 同一组里只有协调者能接收 Transport 指令。
 */
object ZoneTopology {

    data class Node(
        val uuid: String,
        val coordinator: String,
        val invisible: Boolean,
        val channelMap: String,
        /** 固件代次：拓扑里 `SWGen="1"` 就是 S1；0 表示该字段缺失 */
        val swGen: Int = 0,
        val softwareVersion: String = ""
    ) {
        /** 是不是从设备 */
        fun isSatellite(): Boolean =
            invisible || (coordinator.isNotBlank() && coordinator != uuid)

        fun channel(): String = channelOf(channelMap, uuid)
    }

    /**
     * 解析 SOAP 响应正文。解析不出来就返回空列表 —— 调用方应当把"空"当作"不知道"，
     * 退回逐台尝试 + 1023 兜底，而不是中断推送。
     */
    fun parse(soapBody: String): List<Node> {
        val inner = extractInner(soapBody) ?: return emptyList()
        return parseMembers(inner)
    }

    /** 从 SOAP 信封里取出 ZoneGroupState 的文本内容（XmlPullParser 会自动反转义） */
    private fun extractInner(soapBody: String): String? {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(soapBody))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "ZoneGroupState") {
                    val text = parser.nextText()
                    if (!text.isNullOrBlank()) return text
                }
                event = parser.next()
            }
            null
        }.getOrNull()
    }

    private fun parseMembers(xml: String): List<Node> {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            val out = mutableListOf<Node>()
            var coordinator = ""
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "ZoneGroup" -> {
                            coordinator = parser.getAttributeValue(null, "Coordinator").orEmpty()
                        }

                        "ZoneGroupMember" -> {
                            val uuid = parser.getAttributeValue(null, "UUID").orEmpty()
                            if (uuid.isNotBlank()) {
                                out += Node(
                                    uuid = uuid,
                                    coordinator = coordinator.ifBlank {
                                        parser.getAttributeValue(null, "Coordinator").orEmpty()
                                    },
                                    invisible = parser.getAttributeValue(null, "Invisible") == "1",
                                    channelMap = parser.getAttributeValue(null, "ChannelMapSet").orEmpty(),
                                    swGen = parser.getAttributeValue(null, "SWGen")?.toIntOrNull() ?: 0,
                                    softwareVersion = parser.getAttributeValue(null, "SoftwareVersion").orEmpty()
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * 从 `RINCON_AAA:RF,RF;RINCON_BBB:LF,LF` 里取指定设备自己的声道。
     */
    fun channelOf(channelMap: String, uuid: String): String {
        if (channelMap.isBlank() || uuid.isBlank()) return ""
        val entry = channelMap.split(';').firstOrNull { it.startsWith("$uuid:") } ?: return ""
        return entry.substringAfter(':').substringBefore(',').trim()
    }
}
