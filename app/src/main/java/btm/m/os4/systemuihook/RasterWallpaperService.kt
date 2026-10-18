// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_RASTER_FRAMES = 4
private const val KEY_RASTER_WALLPAPER_SENSITIVITY_PRESET = "raster_wallpaper_sensitivity_preset"
private const val KEY_RASTER_WALLPAPER_CUSTOM_SENSITIVITY = "raster_wallpaper_custom_sensitivity"
private const val KEY_RASTER_WALLPAPER_URIS = "raster_wallpaper_uris"
private const val RASTER_TAG = "RasterWallpaper"
private const val FRAME_INTERVAL_MS = 16L
private const val VIDEO_FRAME_INTERVAL_MS = 120L
private const val FRAME_EASING = 0.36f
private const val MAX_TILT_RADIANS = 0.22f

private data class RasterSource(
    val uri: String,
    var bitmap: Bitmap,
    val retriever: MediaMetadataRetriever?,
    val durationUs: Long,
)

class RasterWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = RasterEngine()

    private inner class RasterEngine : Engine(), SensorEventListener {
        private val context: Context = this@RasterWallpaperService
        private val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        private val sensorManager = context.getSystemService(SensorManager::class.java)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private val handler = Handler(Looper.getMainLooper())
        private val frameTick = object : Runnable {
            override fun run() {
                if (!visible) return
                refreshVideoFrames()
                drawFrame()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
        private var sensor: Sensor? = null
        private var sources: List<RasterSource> = emptyList()
        private var currentFrame = 0f
        private var targetFrame = 0f
        private var tilt = 0f
        private var visible = false
        private var drawing = false
        private var lastVideoFrameAt = 0L

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                loadSources()
                registerSensor()
                scheduleFrameTick()
                drawFrame()
            } else {
                unregisterSensor()
                handler.removeCallbacks(frameTick)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            loadSources()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            unregisterSensor()
            handler.removeCallbacks(frameTick)
            recycleSources()
        }

        override fun onDestroy() {
            unregisterSensor()
            handler.removeCallbacks(frameTick)
            recycleSources()
            super.onDestroy()
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!visible || sources.size < 2) return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    // Rotation-vector roll is the stable screen tilt signal.  A smaller
                    // reference angle makes ordinary wrist movement span more frames.
                    tilt = (orientation[2] / MAX_TILT_RADIANS).coerceIn(-1f, 1f)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    tilt = (event.values[0] / (SensorManager.GRAVITY_EARTH * 0.34f))
                        .coerceIn(-1f, 1f)
                }
            }
            val sensitivity = sensitivity()
            val normalized = ((tilt * sensitivity + 1f) / 2f).coerceIn(0f, 1f)
            targetFrame = normalized * sources.lastIndex
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun registerSensor() {
            if (sensor != null) return
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }

        private fun unregisterSensor() {
            sensorManager.unregisterListener(this)
            sensor = null
        }

        private fun scheduleFrameTick() {
            handler.removeCallbacks(frameTick)
            handler.post(frameTick)
        }

        private fun sensitivity(): Float = when (preferences.getInt(KEY_RASTER_WALLPAPER_SENSITIVITY_PRESET, 1)) {
            0 -> 0.55f
            1 -> 0.8f
            2 -> 1.1f
            3 -> 1.45f
            else -> preferences.getFloat(KEY_RASTER_WALLPAPER_CUSTOM_SENSITIVITY, 1f).coerceIn(0.1f, 2f)
        }

        private fun loadSources() {
            val uris = runCatching {
                val array = org.json.JSONArray(preferences.getString(KEY_RASTER_WALLPAPER_URIS, "[]"))
                List(array.length()) { array.getString(it) }.take(MAX_RASTER_FRAMES)
            }.getOrDefault(emptyList())
            if (uris.isEmpty()) {
                recycleSources()
                return
            }
            val loaded = uris.mapNotNull { decodeSource(it) }
            if (loaded.isNotEmpty()) {
                recycleSources()
                sources = loaded
                targetFrame = targetFrame.coerceIn(0f, sources.lastIndex.toFloat())
                currentFrame = targetFrame
            }
        }

        private fun decodeSource(uriString: String): RasterSource? = runCatching {
            val uri = Uri.parse(uriString)
            val type = context.contentResolver.getType(uri).orEmpty().lowercase()
            if (type.startsWith("video/") || uriString.substringBefore('?').lowercase().let {
                    it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".3gp") || it.endsWith(".mkv")
                }) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationUs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull()?.coerceAtLeast(1L)?.times(1000L) ?: 1L
                val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("视频没有可读取的画面")
                RasterSource(uriString, bitmap, retriever, durationUs)
            } else {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    val sample = calculateSample(bounds.outWidth, bounds.outHeight)
                    context.contentResolver.openInputStream(uri)?.use { second ->
                        BitmapFactory.decodeStream(second, null, BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })
                    }
                } ?: error("无法读取图片")
                RasterSource(uriString, bitmap, null, 0L)
            }
        }.onFailure { error ->
            android.util.Log.w(RASTER_TAG, "Unable to decode raster source: $uriString", error)
        }.getOrNull()

        private fun refreshVideoFrames() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastVideoFrameAt < VIDEO_FRAME_INTERVAL_MS) return
            lastVideoFrameAt = now
            sources.forEach { source ->
                val retriever = source.retriever ?: return@forEach
                runCatching {
                    val position = (now * 1000L) % source.durationUs
                    val next = retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (next != null && next !== source.bitmap) {
                        if (!source.bitmap.isRecycled) source.bitmap.recycle()
                        source.bitmap = next
                    }
                }.onFailure { error ->
                    android.util.Log.w(RASTER_TAG, "Unable to refresh raster video frame", error)
                }
            }
        }

        private fun calculateSample(width: Int, height: Int): Int {
            var sample = 1
            while (width / sample > 2160 || height / sample > 2160) sample *= 2
            return sample
        }

        private fun recycleSources() {
            sources.forEach { source ->
                if (!source.bitmap.isRecycled) source.bitmap.recycle()
                runCatching { source.retriever?.release() }
            }
            sources = emptyList()
        }

        private fun drawFrame() {
            if (drawing || !visible || sources.isEmpty()) return
            val holder = surfaceHolder ?: return
            drawing = true
            try {
                currentFrame += (targetFrame - currentFrame) * FRAME_EASING
                val framePosition = currentFrame.coerceIn(0f, sources.lastIndex.toFloat())
                val frameIndex = framePosition.roundToInt().coerceIn(0, sources.lastIndex)
                val canvas = holder.lockCanvas() ?: return
                try {
                    canvas.drawColor(android.graphics.Color.BLACK)
                    // A raster card presents one strip at a time. Cross-fading adjacent
                    // sources makes a flat phone look like two wallpapers are overlaid.
                    val bitmap = sources[frameIndex].bitmap
                    val destination = centerCrop(bitmap, canvas.width, canvas.height)
                    paint.alpha = 255
                    canvas.drawBitmap(bitmap, null, destination, paint)
                    paint.alpha = 255
                    drawRasterHighlight(canvas, destination, abs(targetFrame - currentFrame))
                } finally {
                    paint.alpha = 255
                    paint.shader = null
                    holder.unlockCanvasAndPost(canvas)
                }
            } finally {
                drawing = false
            }
        }

        private fun centerCrop(bitmap: Bitmap, width: Int, height: Int): RectF {
            val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            return RectF((width - drawWidth) / 2f, (height - drawHeight) / 2f,
                (width + drawWidth) / 2f, (height + drawHeight) / 2f)
        }

        private fun drawRasterHighlight(canvas: Canvas, destination: RectF, frameDelta: Float) {
            if (frameDelta < 0.03f) return
            val progress = (frameDelta / sources.lastIndex.coerceAtLeast(1)).coerceIn(0f, 1f)
            paint.shader = LinearGradient(
                destination.left, 0f, destination.right, 0f,
                0x00FFFFFF, (0x16 + (progress * 0x20).roundToInt()).shl(24) or 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(destination, paint)
            paint.shader = null
        }

    }
}
