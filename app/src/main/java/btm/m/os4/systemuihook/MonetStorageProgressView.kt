// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.view.View
import com.materialkolor.palettes.CorePalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import java.util.concurrent.Executors

/** Single occupied-space indicator. Settings remains the source of both byte counts and text. */
internal class MonetStorageProgressView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var occupied = 0f
    private var foreground = Color.TRANSPARENT
    private var backgroundSource: SettingsAppearanceSource? = null
    private var sourceKey = ""
    private var palette: Accent? = null
    private var colorRequest = 0
    private var readRequest = 0
    private var reading = false
    private var hostAttached = false

    private val refreshUsage = object : Runnable {
        override fun run() {
            if (!hostAttached || reading) return
            reading = true
            val request = ++readRequest
            worker.execute {
                val fraction = runCatching {
                    val type = context.classLoader.loadClass("com.android.settings.device.MiuiAboutPhoneUtils")
                    val native = type.getMethod("getInstance", Context::class.java).invoke(null, context)
                    val total = (type.getMethod("getTotalMemoryBytes").invoke(native) as Number).toLong()
                    val available = (type.getMethod("getAvailableMemorySize").invoke(native) as Number).toLong()
                    storageOccupiedFraction(total, available)
                }.getOrNull()
                post {
                    if (!hostAttached || request != readRequest) return@post
                    reading = false
                    if (fraction != null && occupied != fraction) { occupied = fraction; invalidate() }
                    postDelayed(this, 15_000L)
                }
            }
        }
    }

    fun setBackgroundSource(source: SettingsAppearanceSource) {
        backgroundSource = source
        val key = if (source.exists) "${source.uri}:${source.mime}:${source.modified}:${source.size}" else "system"
        if (sourceKey == key) return
        sourceKey = key
        colorRequest++
        palette = if (source.exists) accents.get(key) else null
        refreshTheme()
        if (hostAttached) extractArtwork()
    }

    private fun extractArtwork() {
        val source = backgroundSource?.takeIf { it.exists } ?: return
        if (palette != null) return
        val key = sourceKey
        val request = ++colorRequest
        worker.execute {
            val accent = runCatching { extractAccent(context, source) }.getOrNull()
            if (accent != null) accents.put(key, accent)
            post {
                if (!hostAttached || sourceKey != key || request != colorRequest) return@post
                palette = accent
                refreshTheme()
            }
        }
    }

    fun refreshTheme() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val next = palette?.let { if (night) it.dark else it.light } ?: context.getColor(
            if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600,
        )
        if (next != foreground) { foreground = next; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hostAttached = true
        refreshTheme()
        extractArtwork()
        post(refreshUsage)
    }

    override fun onDetachedFromWindow() {
        hostAttached = false
        colorRequest++
        readRequest++
        reading = false
        removeCallbacks(refreshUsage)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTheme()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        paint.color = if (night) 0x24FFFFFF else 0xFFE6E6E6.toInt()
        canvas.drawRoundRect(bounds, radius, radius, paint)
        if (occupied <= 0f) return
        paint.color = foreground
        // Clip the rounded occupied pill to the real byte fraction, even for very small usage.
        canvas.save()
        canvas.clipRect(0f, 0f, width * occupied, height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, paint)
        canvas.restore()
    }

    private data class Accent(val light: Int, val dark: Int)

    companion object {
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "HyperChanger-StorageMonet").apply { isDaemon = true } }
        private val accents = LruCache<String, Accent>(8)

        private fun extractAccent(context: Context, source: SettingsAppearanceSource): Accent? {
            // Decode raster artwork at a bounded software size; render imported vectors as a fallback.
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source.uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(64, 64)
                }
            }.getOrNull() ?: LogoDrawableLoader.load(context, source)?.let { drawable ->
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val bounds = Rect(drawable.bounds)
                try { drawable.setBounds(0, 0, 64, 64); drawable.draw(Canvas(bitmap)) }
                finally { drawable.bounds = bounds }
                bitmap
            } ?: return null
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val opaque = pixels.filter { Color.alpha(it) >= 128 }.map { it or 0xFF000000.toInt() }.toIntArray()
                if (opaque.isEmpty()) return null
                val fallback = context.getColor(android.R.color.system_accent1_500)
                // Existing project Material color utilities: Celebi -> Monet seed -> primary tonal palette.
                val seed = Score.score(QuantizerCelebi.quantize(opaque, 128), 1, fallback, false).firstOrNull() ?: return null
                val tones = CorePalette.of(seed).a1
                return Accent(tones.tone(40), tones.tone(80))
            } finally { bitmap.recycle() }
        }
    }
}

internal fun storageOccupiedFraction(total: Long, available: Long): Float =
    if (total <= 0L) 0f else ((total - available.coerceIn(0L, total)).toDouble() / total).toFloat()
