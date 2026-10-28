package btm.m.liquidglass.hook

import android.content.Context
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
                assets.open("navigation/mihealth/$name.${if (name.startsWith("device")) "png" else "webp"}")
                    .use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}
