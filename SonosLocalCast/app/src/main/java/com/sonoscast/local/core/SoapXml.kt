package com.sonoscast.local.core

/**
 * SOAP / DIDL-Lite 报文构造。
 *
 * 所有值都要做 XML 转义。特别注意：CurrentURIMetaData 本身是一段 XML 字符串，
 * 它作为 SOAP 参数的**文本内容**出现，所以整段 DIDL 必须被转义成 `&lt;DIDL-Lite ...`。
 * 少转一层是 402 Invalid Args 的常见原因。
 */
object SoapXml {

    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SERVICE_ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

    fun envelope(
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        args.forEach { (key, value) ->
            append('<').append(key).append('>')
            append(escape(value))
            append("</").append(key).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body>")
        append("</s:Envelope>")
    }

    /**
     * 最小可用的 DIDL-Lite。
     *
     * Play:1 对 protocolInfo 相当敏感：`http-get:*:<mime>:*` 必须与 HTTP 响应的
     * Content-Type 完全一致，否则直接 714。这里刻意不写 duration，
     * 因为填错比不填更容易出问题。
     */
    fun didlLite(title: String, url: String, mimeType: String, id: String = "1"): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"").append(escape(id))
        append("\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(escape(title)).append("</dc:title>")
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"http-get:*:").append(mimeType).append(":*\">")
        append(escape(url))
        append("</res>")
        append("</item>")
        append("</DIDL-Lite>")
    }

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /** 把秒数格式化为 Seek 需要的 HH:MM:SS */
    fun timeString(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
