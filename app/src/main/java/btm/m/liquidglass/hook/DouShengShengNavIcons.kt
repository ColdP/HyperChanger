package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.R
import java.util.IdentityHashMap

internal class DouShengShengNavIcons(private val activity: Activity) {
    private val moduleContext by lazy {
        activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY)
    }
    private val fallbackIcons = mutableMapOf<Int, ImageBitmap>()
    private val snapshots = IdentityHashMap<View, Snapshot>()

    fun icon(item: DouShengShengNavigation.Item): ImageBitmap? {
        val cell = item.view
        val content = DouShengShengNavigation.invoke(cell, "getContentLayout") as? View
        if (content != null && DouShengShengNavigation.belongsTo(content, cell)) {
            val density = cell.resources.displayMetrics.density
            // Bound the search to native icon/title content; badges and lazy refresh views
            // are siblings elsewhere in BottomTab and must never become route icons.
            val image = DouShengShengNavigation.views(content).filterIsInstance<ImageView>().filter {
                it.isShown && it.drawable != null && it.width >= 12 * density && it.height >= 12 * density &&
                    DouShengShengNavigation.entry(it) !in setOf("kpf", "kor", "krr", "krs")
            }.maxByOrNull { it.width.toLong() * it.height }
            image?.drawable?.let { snapshot(cell, it)?.let { bitmap -> return bitmap } }
        }
        // LSHomePageTabConfigApiImpl.LIZLLL uses one home/orders resource in both states.
        val id = when (item.identity) {
            "homepage_groupon" -> R.drawable.dss_nav_home
            "homepage_life_services_profile" -> R.drawable.dss_nav_orders
            "homepage_life_services_shop" -> if (cell.isSelected) R.drawable.dss_nav_shop_selected else R.drawable.dss_nav_shop
            "homepage_life_services_community" -> if (cell.isSelected) R.drawable.dss_nav_community_selected else R.drawable.dss_nav_community
            else -> return null
        }
        return fallbackIcons[id] ?: runCatching {
            moduleContext.getDrawable(id)?.let(::render)?.asImageBitmap()?.also { fallbackIcons[id] = it }
        }.getOrNull()
    }

    private fun snapshot(cell: View, drawable: Drawable): ImageBitmap? {
        val now = SystemClock.uptimeMillis()
        val state = drawable.state.contentHashCode()
        val theme = cell.resources.configuration.uiMode
        val old = snapshots[cell]
        if (old?.drawable === drawable && old.current === drawable.current && old.state == state &&
            old.theme == theme && now - old.at < 250L) return old.icon
        val pixels = runCatching { render(drawable) }.getOrNull() ?: return null
        if (old != null && old.pixels.sameAs(pixels)) {
            pixels.recycle()
            snapshots[cell] = old.copy(drawable = drawable, current = drawable.current, state = state, theme = theme, at = now)
            return old.icon
        }
        return pixels.asImageBitmap().also {
            snapshots[cell] = Snapshot(drawable, drawable.current, state, theme, now, pixels, it)
        }
    }

    private fun render(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val edge = maxOf(width, height).coerceIn(48, 192)
        val scale = edge.toFloat() / maxOf(width, height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also {
            val bounds = Rect(drawable.bounds)
            try {
                drawable.setBounds((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                drawable.draw(Canvas(it))
            } finally { drawable.bounds = bounds }
        }
    }

    fun retain(items: List<DouShengShengNavigation.Item>) { snapshots.keys.retainAll(items.map { it.view }.toSet()) }
    fun clear() { snapshots.clear(); fallbackIcons.clear() }

    private data class Snapshot(val drawable: Drawable, val current: Drawable, val state: Int,
        val theme: Int, val at: Long, val pixels: Bitmap, val icon: ImageBitmap)
}
