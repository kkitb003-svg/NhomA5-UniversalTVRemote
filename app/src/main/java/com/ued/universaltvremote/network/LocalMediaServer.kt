package com.ued.universaltvremote.network

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight HTTP server that serves a single media file to the TV browser.
 * Runs on a chosen port (default 8888) and serves the file at /media.
 * The TV opens http://PHONE_IP:8888/media to display the content.
 */
class LocalMediaServer(
    private val contentResolver: ContentResolver,
    private val port: Int = 8888
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var currentUri: Uri? = null
    private var currentMimeType: String = "application/octet-stream"

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun setMedia(uri: Uri, mimeType: String) {
        currentUri = uri
        currentMimeType = mimeType
    }

    fun start() {
        if (isRunning) return
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                while (isActive && serverSocket?.isClosed == false) {
                    val client = runCatching { serverSocket?.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val method = requestLine.substringBefore(' ').uppercase()
                val requestPath = requestLine.substringAfter(' ').substringBefore(' ')
                val requestHeaders = mutableMapOf<String, String>()

                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        requestHeaders[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }

                val output = client.getOutputStream()
                val uri = currentUri

                when {
                    requestPath.startsWith("/media") && uri != null -> serveMedia(method, requestHeaders, uri, output)
                    requestPath.startsWith("/preview") -> servePreview(method, output)
                    else -> send404(output)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun servePreview(method: String, output: OutputStream) {
        val html = buildPreviewHtml()
        val headers = buildString {
            appendLine("HTTP/1.1 200 OK")
            appendLine("Content-Type: text/html; charset=utf-8")
            appendLine("Content-Length: ${html.toByteArray().size}")
            appendLine("Access-Control-Allow-Origin: *")
            appendLine("Connection: close")
            appendLine()
        }
        output.write(headers.toByteArray())
        if (method != "HEAD") {
            output.write(html.toByteArray())
        }
        output.flush()
    }

    private fun serveMedia(
        method: String,
        headers: Map<String, String>,
        uri: Uri,
        output: OutputStream
    ) {
        val totalLength = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
        val requestedRange = parseRange(headers["range"], totalLength)

        contentResolver.openInputStream(uri)?.use { input ->
            val start = requestedRange?.first ?: 0L
            val end = requestedRange?.last ?: (totalLength?.minus(1) ?: -1L)
            if (start > 0) {
                skipFully(input, start)
            }

            val contentLength = if (end >= start && end >= 0) {
                end - start + 1
            } else {
                totalLength ?: -1L
            }
            val statusLine = if (requestedRange != null) {
                "HTTP/1.1 206 Partial Content"
            } else {
                "HTTP/1.1 200 OK"
            }

            val responseHeaders = buildString {
                appendLine(statusLine)
                appendLine("Content-Type: $currentMimeType")
                if (contentLength >= 0) {
                    appendLine("Content-Length: $contentLength")
                }
                if (totalLength != null) {
                    appendLine("Accept-Ranges: bytes")
                }
                if (requestedRange != null && totalLength != null) {
                    appendLine("Content-Range: bytes $start-$end/$totalLength")
                }
                appendLine("Access-Control-Allow-Origin: *")
                appendLine("Connection: close")
                appendLine()
            }

            output.write(responseHeaders.toByteArray())
            if (method != "HEAD") {
                copyStream(input, output, contentLength.takeIf { it >= 0 })
            } else {
                output.flush()
            }
        } ?: send404(output)
    }

    private fun send404(output: OutputStream) {
        val body = "404 Not Found"
        val headers = buildString {
            appendLine("HTTP/1.1 404 Not Found")
            appendLine("Content-Length: ${body.length}")
            appendLine("Connection: close")
            appendLine()
        }
        output.write(headers.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    private fun buildPreviewHtml(): String {
        val isVideo = currentMimeType.startsWith("video")
        val mediaTag = if (isVideo) {
            """<video src="/media" controls autoplay playsinline style="max-width:100%;max-height:100vh;margin:auto;display:block;"></video>"""
        } else {
            """<img src="/media" style="max-width:100%;max-height:100vh;margin:auto;display:block;object-fit:contain;" />"""
        }
        return """
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TV Media Cast</title>
        <style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;}</style>
        </head>
        <body>$mediaTag</body>
        </html>
        """.trimIndent()
    }

    private fun parseRange(header: String?, totalLength: Long?): LongRange? {
        if (header.isNullOrBlank() || totalLength == null || !header.startsWith("bytes=")) {
            return null
        }
        val value = header.removePrefix("bytes=").substringBefore(',').trim()
        val start = value.substringBefore('-').toLongOrNull() ?: return null
        val end = value.substringAfter('-', "").toLongOrNull() ?: (totalLength - 1)
        if (start < 0 || end < start) return null
        return start..minOf(end, totalLength - 1)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, bytesToCopy: Long?) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = bytesToCopy
        while (remaining == null || remaining > 0) {
            val maxRead = remaining?.let { minOf(buffer.size.toLong(), it).toInt() } ?: buffer.size
            val read = input.read(buffer, 0, maxRead)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining = remaining?.minus(read.toLong())
        }
        output.flush()
    }
}
