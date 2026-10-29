package com.sonoscast.local.core

import android.content.Context
import android.net.Uri

/**
 * 读取本地音频文件的真实采样率 / 位深，用来在推流前判断音箱能不能放。
 *
 * 为什么需要它：Sonos 全系（官方文档）本地音乐都封顶 **48 kHz**；
 * WAV / AIFF 只支持 **16 bit**。超出规格的文件不会报错，而是"拉一段数据后自己跳过"，
 * 表现为"没声音、几秒后跳下一首"，看起来像 App 的 bug（实测 96k/24 FLAC 就是这样）。
 */
object AudioProbe {

    data class Format(val codec: String, val sampleRate: Int, val bits: Int)

    /** 只读取文件头，几十字节，不做完整解码；认不出就返回 null（放行） */
    fun probe(context: Context, uri: Uri): Format? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64)
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
            if (n >= 42) {
                parseFlac(head, n) ?: parseWav(head, n) ?: parseAiff(head, n)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** fLaC + STREAMINFO：采样率 20 bit、位深 5 bit */
    private fun parseFlac(b: ByteArray, n: Int): Format? {
        if (n < 42) return null
        if (!(b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
                b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte())
        ) return null
        // 4 字节魔数 + 4 字节块头（type/长度）之后是 34 字节 STREAMINFO
        val p = 8
        if (n < p + 18) return null
        val rate = ((b[p + 10].toInt() and 0xFF) shl 12) or
            ((b[p + 11].toInt() and 0xFF) shl 4) or
            ((b[p + 12].toInt() and 0xFF) shr 4)
        val bits = (((b[p + 12].toInt() and 0x01) shl 4) or
            ((b[p + 13].toInt() and 0xF0) shr 4)) + 1
        if (rate <= 0) return null
        return Format("FLAC", rate, bits)
    }

    /** RIFF/WAVE + fmt 块 */
    private fun parseWav(b: ByteArray, n: Int): Format? {
        if (n < 36) return null
        if (!(b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte())
        ) return null
        var o = 12
        while (o + 8 <= n) {
            val id = String(b, o, 4, Charsets.US_ASCII)
            val size = le32(b, o + 4)
            val data = o + 8
            if (id == "fmt " && data + 16 <= n) {
                val rate = le32(b, data + 4)
                val bits = le16(b, data + 14)
                if (rate > 0) return Format("WAV", rate, bits)
                return null
            }
            if (size <= 0) break
            o = data + size + (size and 1)
        }
        return null
    }

    /** FORM/AIFF + COMM 块（采样率是 80-bit extended） */
    private fun parseAiff(b: ByteArray, n: Int): Format? {
        if (n < 40) return null
        if (!(b[0] == 'F'.code.toByte() && b[1] == 'O'.code.toByte() &&
                b[2] == 'R'.code.toByte() && b[3] == 'M'.code.toByte())
        ) return null
        var o = 12
        while (o + 8 <= n) {
            val id = String(b, o, 4, Charsets.US_ASCII)
            val size = be32(b, o + 4)
            val data = o + 8
            if (id == "COMM" && data + 18 <= n) {
                val bits = ((b[data + 6].toInt() and 0xFF) shl 8) or (b[data + 7].toInt() and 0xFF)
                val exp = ((b[data + 8].toInt() and 0x7F) shl 8) or (b[data + 9].toInt() and 0xFF)
                var mantissa = 0L
                for (i in 0 until 8) {
                    mantissa = (mantissa shl 8) or (b[data + 10 + i].toLong() and 0xFF)
                }
                val rate = if (exp == 0 || mantissa == 0L) 0
                else (mantissa.toDouble() * Math.pow(2.0, (exp - 16383 - 63).toDouble())).toInt()
                if (rate > 0) return Format("AIFF", rate, bits)
                return null
            }
            if (size <= 0) break
            o = data + size + (size and 1)
        }
        return null
    }

    /** 超出 Sonos 规格：>48 kHz；WAV/AIFF 超过 16 bit */
    fun isUnsupported(f: Format?): Boolean {
        if (f == null) return false
        if (f.sampleRate > 48000) return true
        if (f.codec == "WAV" || f.codec == "AIFF") return f.bits > 16
        return f.bits > 24
    }

    /** 给提示文案用的简短描述，例如 "FLAC 96 kHz / 24 bit" */
    fun describe(f: Format): String {
        val khz = if (f.sampleRate % 1000 == 0) "${f.sampleRate / 1000} kHz"
        else String.format("%.1f kHz", f.sampleRate / 1000f)
        return "${f.codec} $khz / ${f.bits} bit"
    }
}
