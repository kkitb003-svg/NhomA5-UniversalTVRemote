package com.ued.universaltvremote.network

import android.util.Log
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val TAG = "ScreenMirrorServer"

/**
 * Captures the phone screen via MediaProjection API and streams it as
 * a Motion JPEG (MJPEG) HTTP stream that the TV browser can display.
 *
 * TV opens: http://PHONE_IP:8889/stream
 * Format: multipart/x-mixed-replace MJPEG (universally supported by browsers)
 */
class ScreenMirrorServer(
    private val port: Int = 8889
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val processingFrame = AtomicBoolean(false)

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    var isStreaming = false
        private set

    private var captureWidth = 720
    private var captureHeight = 1280
    private var captureDpi = 160

    private var startedAt = 0L

    fun startProjection(
        resultCode: Int,
        data: Intent,
        context: Context
    ) {
        try {
            stop()
        } catch (_: Exception) { }

        try {
            android.util.Log.d("ScreenMirror", "Starting projection...")

            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection permission was not granted")

            android.util.Log.d("ScreenMirror", "Projection acquired")

            configureCaptureMetrics(context)

            imageThread = HandlerThread("screen_mirror_capture").apply { start() }
            imageHandler = Handler(imageThread!!.looper)

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    if (!processingFrame.compareAndSet(false, true)) {
                        reader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }

                    val image = reader.acquireLatestImage()
                    if (image == null) {
                        processingFrame.set(false)
                        return@setOnImageAvailableListener
                    }

                    try {
                        val bitmap = imageToBitmap(image)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 55, output)
                        latestJpeg = output.toByteArray()
                        bitmap.recycle()
                    } catch (e: Exception) {
                        android.util.Log.e("ScreenMirror", "Frame capture error: ${e.message}")
                    } finally {
                        image.close()
                        processingFrame.set(false)
                    }
                }, imageHandler)
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirror",
                captureWidth,
                captureHeight,
                captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                imageHandler
            ) ?: throw IllegalStateException("Could not create virtual display")

            android.util.Log.d("ScreenMirror", "Virtual display created: ${captureWidth}x${captureHeight}")

            isStreaming = true
            startedAt = System.currentTimeMillis()
            startServer()
            startBroadcastLoop()
            android.util.Log.d("ScreenMirror", "Screen mirror server started on port $port")
        } catch (e: Exception) {
            android.util.Log.e("ScreenMirror", "Failed to start projection: ${e.message}")
            stop()
            throw e
        }
    }

    private fun configureCaptureMetrics(context: Context) {
        val metrics = context.resources.displayMetrics
        val maxDimension = 1280f
        val width = max(metrics.widthPixels, 1)
        val height = max(metrics.heightPixels, 1)
        val largest = max(width, height).toFloat()
        val scale = minOf(1f, maxDimension / largest)

        captureWidth = max((width * scale).toInt(), 360)
        captureHeight = max((height * scale).toInt(), 640)
        captureDpi = metrics.densityDpi
    }

    private fun startServer() {
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), 1)
                }
                while (isActive && isStreaming) {
                    val client = runCatching { serverSocket?.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start: ${e.message}")
            }
        }
    }

    private fun startBroadcastLoop() {
        broadcastJob = scope.launch {
            while (isActive && isStreaming) {
                val jpeg = latestJpeg
                if (jpeg != null && clients.isNotEmpty()) {
                    val frame = buildMjpegFrame(jpeg)
                    val toRemove = mutableListOf<OutputStream>()
                    for (out in clients) {
                        try {
                            out.write(frame)
                            out.flush()
                        } catch (_: Exception) {
                            toRemove.add(out)
                        }
                    }
                    clients.removeAll(toRemove.toSet())
                }
                delay(66L)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrBlank()) break
            }

            val output = socket.getOutputStream()
            when {
                requestLine.contains("/stream") -> {
                    val header = buildString {
                        appendLine("HTTP/1.1 200 OK")
                        appendLine("Content-Type: multipart/x-mixed-replace; boundary=frame")
                        appendLine("Cache-Control: no-cache, no-store, must-revalidate")
                        appendLine("Pragma: no-cache")
                        appendLine("Connection: close")
                        appendLine()
                    }
                    output.write(header.toByteArray())
                    output.flush()
                    clients.add(output)
                    while (isStreaming && !socket.isClosed) {
                        Thread.sleep(500L)
                    }
                }

                requestLine.contains("/viewer") -> {
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Screen Mirror</title>
                        <style>
                        body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;}
                        img{max-width:100%;max-height:100vh;object-fit:contain;}
                        </style>
                        </head>
                        <body><img src="/stream" /></body>
                        </html>
                    """.trimIndent()
                    val response = buildString {
                        appendLine("HTTP/1.1 200 OK")
                        appendLine("Content-Type: text/html; charset=utf-8")
                        appendLine("Content-Length: ${html.toByteArray().size}")
                        appendLine("Connection: close")
                        appendLine()
                    }
                    output.write(response.toByteArray())
                    output.write(html.toByteArray())
                    output.flush()
                }

                else -> {
                    output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildMjpegFrame(jpeg: ByteArray): ByteArray {
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
        return header.toByteArray() + jpeg + "\r\n".toByteArray()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureWidth
        val paddedBitmap = Bitmap.createBitmap(
            captureWidth + rowPadding / pixelStride,
            captureHeight,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding > 0) {
            Bitmap.createBitmap(paddedBitmap, 0, 0, captureWidth, captureHeight).also {
                paddedBitmap.recycle()
            }
        } else {
            paddedBitmap
        }
    }

    fun stop() {
        isStreaming = false
        try { broadcastJob?.cancel() } catch (_: Exception) {}
        try { serverJob?.cancel() } catch (_: Exception) {}

        val clientsCopy = clients.toList()
        clients.clear()
        clientsCopy.forEach { out ->
            try { out.close() } catch (_: Exception) {}
        }

        try { serverSocket?.close() } catch (_: Exception) {}
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageThread?.quitSafely() } catch (_: Exception) {}

        serverSocket = null
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        imageHandler = null
        imageThread = null
        latestJpeg = null
        processingFrame.set(false)
    }
}
