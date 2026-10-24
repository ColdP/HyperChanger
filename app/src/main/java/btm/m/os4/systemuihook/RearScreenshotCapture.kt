// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import btm.m.os4.systemuihook.ModuleLog as Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One continuous Root recording session; no screenshot polling or primary-display fallback. */
internal class RearScreenCaptureSession(
    private val onFrame: (Bitmap) -> Unit,
    private val onFailure: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            var codec: MediaCodec? = null
            var recording: Process? = null
            try {
                val display = resolveRearScreenshotDisplay(
                    rearScreenshotRootCommand("dumpsys display").orEmpty(),
                    rearScreenshotRootCommand("dumpsys SurfaceFlinger --display-id").orEmpty(),
                ) ?: error("Cannot map logical rear display 1 to a physical display")
                if (!running.get()) return@execute
                Log.i(TAG, "Recording rear display physical=${display.physicalId}, ${display.width}x${display.height}")
                // The ID remains decimal text: never truncate a 64-bit physical ID into an Int.
                recording = ProcessBuilder("su", "-c", "exec screenrecord --display-id ${display.physicalId} " +
                    "--size ${display.width}x${display.height} --output-format=h264 --bit-rate 4000000 --time-limit 0 -").start()
                process = recording
                if (!running.get()) return@execute
                val stderrProcess = recording
                Thread({
                    runCatching { stderrProcess.errorStream.bufferedReader().use { reader ->
                        reader.forEachLine { Log.w(TAG, "screenrecord: ${it.take(500)}") }
                    } }
                }, "RearRecordingErrors").apply { isDaemon = true; start() }
                codec = MediaCodec.createDecoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", display.width, display.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                codec.configure(format, null, null, 0)
                codec.start()
                val activeCodec = codec
                var timestamp = 0L
                readRearScreenshotH264(recording.inputStream) { accessUnit ->
                    if (!running.get()) error("Recording closed")
                    var index = -1
                    while (running.get() && index < 0) {
                        drain(activeCodec)
                        index = activeCodec.dequeueInputBuffer(10_000L)
                    }
                    if (index >= 0) {
                        val input = requireNotNull(activeCodec.getInputBuffer(index))
                        require(accessUnit.size <= input.capacity()) { "Recording access unit is too large" }
                        input.clear()
                        input.put(accessUnit)
                        activeCodec.queueInputBuffer(index, 0, accessUnit.size, timestamp, 0)
                        timestamp += 33_333L
                        drain(activeCodec)
                    }
                }
                if (running.get()) error("Rear screen recording ended")
            } catch (error: Throwable) {
                if (running.get()) {
                    Log.w(TAG, "Rear recording failed", error)
                    main.post { if (running.get()) onFailure() }
                }
            } finally {
                recording?.destroy()
                runCatching { recording?.inputStream?.close() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                process = null
            }
        }
    }

    fun stop() {
        running.set(false)
        // Destroy only this page's recording. Decoder stop/release has one owner (the worker).
        process?.destroy()
        main.removeCallbacksAndMessages(null)
    }

    fun close() {
        stop()
        executor.shutdown()
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = codec.dequeueOutputBuffer(info, 0L)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) break
            try {
                codec.getOutputImage(index)?.use { image ->
                    val bitmap = rearRecordingBitmap(image)
                    // Keep only the newest frame if the UI is busy; never queue a frame backlog.
                    main.removeCallbacksAndMessages(FRAME_TOKEN)
                    main.postAtTime({
                        if (running.get()) onFrame(bitmap)
                    }, FRAME_TOKEN, android.os.SystemClock.uptimeMillis())
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    private companion object {
        const val TAG = "RearScreenshotPreview"
        val FRAME_TOKEN = Any()
    }
}

private fun rearRecordingBitmap(image: Image): Bitmap {
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    val planes = image.planes
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val y = row + crop.top
        val yOffset = yPlane.buffer.position() + y * yPlane.rowStride
        val uOffset = uPlane.buffer.position() + (y / 2) * uPlane.rowStride
        val vOffset = vPlane.buffer.position() + (y / 2) * vPlane.rowStride
        for (column in 0 until width) {
            val x = column + crop.left
            val luminance = ((yPlane.buffer.get(yOffset + x * yPlane.pixelStride).toInt() and 255) - 16).coerceAtLeast(0)
            val u = (uPlane.buffer.get(uOffset + (x / 2) * uPlane.pixelStride).toInt() and 255) - 128
            val v = (vPlane.buffer.get(vOffset + (x / 2) * vPlane.pixelStride).toInt() and 255) - 128
            val r = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + column] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

internal fun rearScreenshotRootCommand(command: String): String? = runCatching {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) output else null
}.getOrNull()
