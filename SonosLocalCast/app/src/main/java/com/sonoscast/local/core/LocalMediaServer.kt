package com.sonoscast.local.core

import android.content.Context
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

/**
 * 局域网媒体服务器。
 *
 * 为什么必须自己实现 Range：
 * Sonos 在播放前会先发 `Range: bytes=0-` 探测，播放中 seek 会再发 `bytes=N-`。
 * 如果服务器忽略 Range 直接回 200 全量，Play:1 通常会表现为"能播但不支持拖动"，
 * 大文件还会因为缓冲策略直接放弃（或反复重新拉流）。
 *
 * 另外把这些头部做对同样重要：
 * - `Accept-Ranges: bytes`  让音箱知道可以 seek
 * - `Content-Range: bytes s-e/total`  206 时必须精确
 * - `Content-Length` 必须是本次分片的长度，不是文件总长
 * - `Content-Type` 必须与文件真实格式一致，否则 714
 */
class LocalMediaServer(
    private val context: Context,
    hostname: String?,
    port: Int
) : NanoHTTPD(hostname, port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return when {
            uri == "/health" -> handleHealth()
            uri == "/" || uri == "/index.html" -> handleIndex()
            uri.startsWith("/stream") -> handleStream(session, uri)
            else -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "404 Not Found"
            )
        }
    }

    // ------------------------------------------------------------------ 路由

    private fun handleHealth(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "OK")

    private fun handleIndex(): Response {
        val item = MediaRegistry.current.value
        val ip = NetUtils.currentLanIp(context).orEmpty()
        val port = listeningPort
        val currentLine = if (item == null) {
            "尚未选择音频文件"
        } else {
            "${item.displayName} · ${item.sizeLabel} · ${item.mimeType}"
        }
        val streamLine = if (item == null) {
            "-"
        } else {
            buildStreamUrl(ip, port, item)
        }
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>ZONOZ LOCAL STREAM · 本地媒体服务</title>
              <style>
                :root { color-scheme: light dark; }
                body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
                       margin: 0; padding: 24px; line-height: 1.6; }
                h1 { font-size: 20px; margin: 0 0 4px; }
                .sub { opacity: .65; font-size: 13px; margin-bottom: 20px; }
                .card { border: 1px solid rgba(128,128,128,.35); border-radius: 14px;
                        padding: 16px; margin-bottom: 14px; }
                .k { font-size: 12px; opacity: .6; text-transform: uppercase; letter-spacing: .06em; }
                .v { font-size: 14px; word-break: break-all; margin-top: 2px; }
                code { background: rgba(128,128,128,.15); padding: 2px 6px; border-radius: 6px; }
              </style>
            </head>
            <body>
              <h1>ZONOZ LOCAL STREAM · 本地媒体服务</h1>
              <div class="sub">如果你能在另一台设备上打开这个页面，说明手机到局域网的入站连接是通的。</div>
              <div class="card">
                <div class="k">服务地址</div>
                <div class="v"><code>http://$ip:$port</code></div>
              </div>
              <div class="card">
                <div class="k">当前音频</div>
                <div class="v">$currentLine</div>
              </div>
              <div class="card">
                <div class="k">推送给音箱的 URL</div>
                <div class="v"><code>$streamLine</code></div>
              </div>
              <div class="card">
                <div class="k">自检</div>
                <div class="v"><code>/health</code> 应返回 OK；<code>/stream/&lt;fileId&gt;</code> 支持 Range(206)</div>
              </div>
            </body>
            </html>
        """.trimIndent()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // ------------------------------------------------------------------ 流

    private fun handleStream(session: IHTTPSession, uri: String): Response {
        val fileId = extractFileId(uri, session)
        val item = fileId?.let { MediaRegistry.get(it) }
        val remote = session.remoteIpAddress ?: "?"
        val rangeHeader = session.headers["range"]

        if (item == null) {
            LogBus.w("HTTP 404 · $remote ${session.method} $uri（未知 fileId）")
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "unknown fileId"
            )
        }

        val total = item.sizeBytes
        val isHead = session.method == Method.HEAD
        LogBus.i("HTTP ${session.method} · $remote · ${item.displayName} · Range=${rangeHeader ?: "(无)"}")

        // 长度未知时退化为整体 200，Sonos 仍能播，但 seek 不可用
        if (total <= 0L) {
            if (isHead) {
                val head = NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    item.mimeType,
                    java.io.ByteArrayInputStream(ByteArray(0)),
                    0L
                )
                head.addHeader("Accept-Ranges", "none")
                return head
            }
            val stream = openStream(item, 0L)
                ?: return serverError("无法打开文件")
            LogBus.w("文件长度未知，退化为 chunked 传输（Sonos 上可能无法 seek）")
            val response = NanoHTTPD.newChunkedResponse(Response.Status.OK, item.mimeType, stream)
            response.addHeader("Accept-Ranges", "none")
            response.addHeader("Cache-Control", "no-store")
            return response
        }

        val range = try {
            parseRange(rangeHeader, total)
        } catch (_: UnsatisfiableRange) {
            LogBus.w("HTTP 416 · $remote · Range 超出文件长度 $total")
            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain; charset=utf-8",
                "range not satisfiable"
            )
            response.addHeader("Content-Range", "bytes */$total")
            return response
        }

        val start = range?.start ?: 0L
        val end = range?.end ?: (total - 1)
        val length = range?.length ?: total

        if (isHead) {
            val status = if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = NanoHTTPD.newFixedLengthResponse(
                status,
                item.mimeType,
                java.io.ByteArrayInputStream(ByteArray(0)),
                length
            )
            response.addHeader("Accept-Ranges", "bytes")
            if (range != null) {
                response.addHeader("Content-Range", "bytes $start-$end/$total")
            }
            return response
        }

        val stream = openStream(item, start)
        if (stream == null) {
            LogBus.e("打开文件失败：${item.displayName} (${item.uri})")
            return serverError("cannot open file")
        }

        val status = if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = NanoHTTPD.newFixedLengthResponse(status, item.mimeType, stream, length)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store")
        if (range != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$total")
        }
        return response
    }

    private fun serverError(message: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", message)

    private fun extractFileId(uri: String, session: IHTTPSession): String? {
        session.parameters["fileId"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val segment = uri.removePrefix("/stream").trim('/').substringBefore('/')
        if (segment.isBlank()) return null
        // URL 形如 /stream/1a2b3c4d.mp3，去掉扩展名后才是 fileId
        return segment.substringBeforeLast('.', segment).ifBlank { segment }
    }

    // ---------------------------------------------------------------- 文件流

    /**
     * 打开从 [offset] 起始的输入流。
     *
     * 首选 ParcelFileDescriptor + FileChannel.position()：对本地文件是 O(1) 定位，
     * Sonos 拖进度条时不会因为 skip 几十 MB 而超时。
     * 部分云端 provider 不支持 seek，回退到普通 InputStream + skip。
     */
    private fun openStream(item: MediaItem, offset: Long): InputStream? {
        val resolver = context.contentResolver

        // 曲库兜底条目用的是 file:// 路径（媒体库没有索引），ContentResolver 打不开，
        // 直接按文件随机读。FileInputStream 的 channel 支持 O(1) 定位，和 PFD 分支等价。
        if (item.uri.scheme == "file") {
            val path = item.uri.path
            if (path.isNullOrBlank()) return null
            return runCatching {
                val fis = java.io.FileInputStream(path)
                if (offset > 0L) fis.channel.position(offset)
                fis
            }.getOrElse {
                LogBus.e("直接打开文件失败：$path · ${it.message}")
                null
            }
        }

        val viaFd = runCatching {
            val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(item.uri, "r")
                ?: return@runCatching null
            val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            try {
                if (offset > 0L) fis.channel.position(offset)
                fis
            } catch (t: Throwable) {
                runCatching { fis.close() }
                LogBus.d("该来源不支持随机定位，回退到 skip：${t.message}")
                null
            }
        }.getOrNull()
        if (viaFd != null) return viaFd

        val raw = runCatching { resolver.openInputStream(item.uri) }.getOrNull() ?: return null
        if (offset > 0L) {
            var remaining = offset
            try {
                while (remaining > 0L) {
                    val skipped = raw.skip(remaining)
                    if (skipped > 0L) {
                        remaining -= skipped
                    } else {
                        if (raw.read() < 0) return null
                        remaining--
                    }
                }
            } catch (t: Throwable) {
                runCatching { raw.close() }
                LogBus.e("定位到 $offset 失败：${t.message}")
                return null
            }
        }
        return raw
    }

    // ---------------------------------------------------------------- Range

    private data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    private class UnsatisfiableRange : Exception()

    /**
     * 解析 `Range: bytes=...`。返回 null 表示"没有 Range 或无法理解"，调用方回 200。
     * 抛出 [UnsatisfiableRange] 表示语法正确但范围越界，应回 416。
     */
    private fun parseRange(header: String?, total: Long): ByteRange? {
        if (header.isNullOrBlank() || total <= 0L) return null
        if (!header.startsWith("bytes=", ignoreCase = true)) return null

        val spec = header.substring(6).substringBefore(',').trim()
        if (spec.isEmpty()) return null

        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()

        return if (startPart.isEmpty()) {
            // bytes=-500：最后 500 字节
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0L) throw UnsatisfiableRange()
            val start = (total - suffix).coerceAtLeast(0L)
            ByteRange(start, total - 1)
        } else {
            val start = startPart.toLongOrNull() ?: return null
            if (start >= total) throw UnsatisfiableRange()
            val end = if (endPart.isEmpty()) {
                total - 1
            } else {
                (endPart.toLongOrNull() ?: return null).coerceAtMost(total - 1)
            }
            if (end < start) throw UnsatisfiableRange()
            ByteRange(start, end)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080

        /** 生成交给 Sonos 的 URL —— 必须是手机在局域网中的地址，绝不能是 127.0.0.1 */
        fun buildStreamUrl(ip: String, port: Int, item: MediaItem): String =
            "http://$ip:$port/stream/${item.fileId}${item.urlSuffix}"
    }
}
