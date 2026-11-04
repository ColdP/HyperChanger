package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.R
import java.util.IdentityHashMap

/** Text-only native tabs use the extracted APK vectors, never unrelated generic icons. */
internal class XiaohongshuNavIcons(private val activity: Activity) {
    private val moduleContext by lazy {
        activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY)
    }
    private val resources = mutableMapOf<Int, ImageBitmap>()
    private val snapshots = IdentityHashMap<ImageView, Snapshot>()

    fun icon(tab: View, kind: XiaohongshuTabFeatures.Kind, selected: Boolean): ImageBitmap? {
        val density = tab.resources.displayMetrics.density
        val image = descendants(tab).filterIsInstance<ImageView>().firstOrNull {
            it.isShown && it.width >= 14 * density && it.height >= 14 * density &&
                it.drawable != null && !it.javaClass.name.contains("Lottie")
        }
        if (image != null) snapshot(image)?.let { return it }
        val id = when (kind) {
            XiaohongshuTabFeatures.Kind.HOME -> if (selected) R.drawable.xhs_nav_red_view_home_f else R.drawable.xhs_nav_red_view_home
            XiaohongshuTabFeatures.Kind.MARKET -> if (selected) R.drawable.xhs_nav_red_view_store_f else R.drawable.xhs_nav_red_view_store
            XiaohongshuTabFeatures.Kind.PUBLISH -> R.drawable.xhs_nav_red_view_new_post_v2
            XiaohongshuTabFeatures.Kind.MESSAGES -> R.drawable.xhs_nav_red_view_message_b
            XiaohongshuTabFeatures.Kind.PROFILE -> if (selected) R.drawable.xhs_nav_red_view_me_f else R.drawable.xhs_nav_red_view_me
            else -> return null
        }
        return resourceIcon(id)
    }

    fun publishActionIcon(): ImageBitmap? = resourceIcon(R.drawable.xhs_nav_red_view_add)

    private fun resourceIcon(id: Int): ImageBitmap? = resources[id] ?: runCatching {
            moduleContext.getDrawable(id)?.let(::render)?.asImageBitmap()?.also { resources[id] = it }
        }.getOrNull()

    private fun snapshot(image: ImageView): ImageBitmap? {
        val drawable = image.drawable ?: return null
        val now = SystemClock.uptimeMillis()
        val state = drawable.state.contentHashCode()
        val old = snapshots[image]
        if (old?.drawable === drawable && old.state == state && now - old.at < 500L) return old.icon
        val pixels = runCatching { render(drawable) }.getOrNull() ?: return null
        if (old != null && old.pixels.sameAs(pixels)) {
            pixels.recycle()
            snapshots[image] = old.copy(drawable = drawable, state = state, at = now)
            return old.icon
        }
        return pixels.asImageBitmap().also { snapshots[image] = Snapshot(drawable, state, now, pixels, it) }
    }

    private fun render(drawable: Drawable): Bitmap {
        val width = 96
        val height = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            (96f * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt().coerceIn(24, 192)
        } else 96
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val bounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(it))
            } finally { drawable.bounds = bounds }
        }
    }

    fun retain(tabs: List<View>) {
        val live = tabs.flatMap { descendants(it).filterIsInstance<ImageView>().toList() }
        snapshots.keys.retainAll(live.toSet())
    }

    fun clear() { snapshots.clear(); resources.clear() }

    private data class Snapshot(val drawable: Drawable, val state: Int, val at: Long, val pixels: Bitmap, val icon: ImageBitmap)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
