package btm.m.liquidglass.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap

/** Original, byte-for-byte APK WebP assets. Unknown server tabs retain the host drawable. */
internal class CmbBankNavIcons(context: Context) {
    private val assets = runCatching {
        context.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY).assets
    }.getOrNull()
    private val extracted = mutableMapOf<String, ImageBitmap?>()
    private val native = IdentityHashMap<Drawable, MutableMap<List<Int>, ImageBitmap>>()

    fun icon(item: CmbBankNavigation.Item): ImageBitmap? {
        val stem = when (item.label) {
            "首页", "Home" -> "home"
            "财富", "Wealth" -> "fortune"
            "生活", "Life" -> "life"
            "借钱", "贷款", "Loans" -> "borrow"
            "我的", "Me" -> "mine"
            else -> null
        }
        if (stem != null) {
            val name = "${stem}_tab_default_${if (item.selected) "selected" else "normal"}.webp"
            val bitmap = extracted.getOrPut(name) {
                runCatching {
                    assets?.open("navigation/cmbbank/$name")?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                }.getOrNull()
            }
            if (bitmap != null) return bitmap
        }
        val drawable = item.icon.drawable ?: return null
        val state = drawable.state.toList()
        native[drawable]?.get(state)?.let { return it }
        return runCatching {
            val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(256) ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(256) ?: 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
            } finally {
                drawable.bounds = bounds
            }
            bitmap.asImageBitmap().also {
                if (native.size >= 64) native.clear()
                native.getOrPut(drawable) { mutableMapOf() }[state] = it
            }
        }.getOrNull()
    }
}
