package com.sonoscast.local.core

/**
 * MIME 规范化。
 *
 * Sonos 对 Content-Type 相当挑剔：错的类型会直接回 714 Illegal MIME-Type，
 * 而 Android 的 ContentResolver 经常给出 `audio/x-flac`、`audio/mp3` 这类非标准变体，
 * 所以这里做一次归一化，并让 HTTP 服务器与 DIDL-Lite 的 protocolInfo 使用同一个值。
 */
object MimeTypes {

    const val DEFAULT_AUDIO = "audio/mpeg"

    private val EXT_TO_MIME = linkedMapOf(
        "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "wave" to "audio/wav",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wma" to "audio/x-ms-wma"
    )

    private val MIME_ALIASES = mapOf(
        "audio/x-flac" to "audio/flac",
        "audio/flac" to "audio/flac",
        "audio/mp3" to "audio/mpeg",
        "audio/mpeg3" to "audio/mpeg",
        "audio/x-mpeg" to "audio/mpeg",
        "audio/x-mpeg-3" to "audio/mpeg",
        "audio/wave" to "audio/wav",
        "audio/x-wav" to "audio/wav",
        "audio/vnd.wave" to "audio/wav",
        "audio/x-aiff" to "audio/aiff",
        "audio/x-aif" to "audio/aiff",
        "audio/mp4a-latm" to "audio/mp4",
        "audio/x-m4a" to "audio/mp4",
        "audio/x-ms-wma" to "audio/x-ms-wma",
        "audio/x-ogg" to "audio/ogg",
        "audio/vorbis" to "audio/ogg"
    )

    /** Play:1（S2）实际可解码的容器/编码 */
    private val SONOS_SUPPORTED = setOf(
        "audio/mpeg", "audio/mp4", "audio/flac", "audio/wav", "audio/aiff",
        "audio/ogg", "audio/x-ms-wma", "audio/aac"
    )

    fun fromExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext]
    }

    /**
     * 结合 ContentResolver 给出的类型与文件名后缀，得到一个"可信"的 MIME。
     * 后缀优先 —— 因为部分文件管理器会把 flac 报成 application/octet-stream。
     */
    fun resolve(rawMime: String?, displayName: String): String {
        fromExtension(displayName)?.let { return it }
        val cleaned = rawMime?.substringBefore(';')?.trim()?.lowercase()
        if (cleaned.isNullOrEmpty()) return DEFAULT_AUDIO
        return MIME_ALIASES[cleaned] ?: cleaned
    }

    /** 为 URL 生成扩展名后缀（含前导点），拿不到则返回空串 */
    fun urlSuffix(mime: String): String = when (mime) {
        "audio/mpeg" -> ".mp3"
        "audio/mp4" -> ".m4a"
        "audio/aac" -> ".aac"
        "audio/flac" -> ".flac"
        "audio/wav" -> ".wav"
        "audio/aiff" -> ".aiff"
        "audio/ogg" -> ".ogg"
        "audio/x-ms-wma" -> ".wma"
        else -> ""
    }

    fun isSonosSupported(mime: String): Boolean = mime in SONOS_SUPPORTED
}
