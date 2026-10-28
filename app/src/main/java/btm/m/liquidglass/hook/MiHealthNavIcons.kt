package btm.m.liquidglass.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Original Xiaomi Health bottom-tab assets extracted from MiHealth 3.59.1. */
internal class MiHealthNavIcons(context: Context) {
    private val assets = context.createPackageContext(
        "btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY
    ).assets
    private val cache = mutableMapOf<String, ImageBitmap?>()

    fun icon(key: String, selected: Boolean): ImageBitmap? {
        val stem = when (key) {
            "HEALTH" -> "health"
            "WORKOUT" -> "sport"
            "DEVICE" -> "device"
            "PROFILE" -> "mine"
            else -> return null
        }
        val name = "${stem}_${if (selected) "checked" else "normal"}"
        return cache.getOrPut(name) {
            runCatching {
                val decoded = assets.open("navigation/mihealth/$name.${if (name.startsWith("device")) "png" else "webp"}")
                    .use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
                val bitmap = if (selected) decoded else normalizeOpacity(decoded)
                if (bitmap !== decoded) decoded.recycle()
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }

    /** A few extracted inactive resources are exported with alpha capped at 0.2. */
    private fun normalizeOpacity(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val maxAlpha = pixels.maxOfOrNull { it ushr 24 } ?: return source
        if (maxAlpha == 0 || maxAlpha == 255) return source
        val scale = 255f / maxAlpha
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            if (alpha != 0) {
                val boosted = (alpha * scale).toInt().coerceAtMost(255)
                pixels[index] = (pixel and 0x00FFFFFF) or (boosted shl 24)
            }
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }
}
