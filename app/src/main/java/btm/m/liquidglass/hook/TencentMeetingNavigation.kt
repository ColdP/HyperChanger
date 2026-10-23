package btm.m.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap

/** Native 3.47.2.410 home navigation; never treat the full-screen tabView as a bar. */
internal class TencentMeetingNavigation {
    data class Item(val view: View, val label: String, val icon: ImageBitmap)
    data class Bar(
        val view: ViewGroup,
        val items: List<Item>,
        val selectedIndex: Int,
        val trailingAction: Item?
    )

    private val icons = IdentityHashMap<Drawable, ImageBitmap>()
    private var cachedBar: ViewGroup? = null

    fun detect(content: ViewGroup): Bar? {
        val home = views(content).firstOrNull {
            it.javaClass.name == PREFIX + "HomeTabView"
        } ?: return null
        val candidates = views(home).filterIsInstance<ViewGroup>().filter {
            it.isAttachedToWindow && it.isShown &&
                (it.javaClass.name == PREFIX + "HomeTabContainer" ||
                    it.javaClass.name == PREFIX + "FloatingTabContainer")
        }.toList()
        val bar = cachedBar?.takeIf { cached -> candidates.any { it === cached } }
            ?: candidates.firstOrNull()?.also { cachedBar = it }
            ?: return null
        val row = if (bar.javaClass.name == PREFIX + "HomeTabContainer") bar else
            views(bar).firstOrNull { entry(it) == "tabsView" } as? ViewGroup ?: return null
        if (row.childCount !in 2..8) return null
        val items = (0 until row.childCount).map { index ->
            val item = row.getChildAt(index)
            if (item.javaClass.name != PREFIX + "HomeTabItemView" &&
                item.javaClass.name != PREFIX + "HomeTabItemViewV2"
            ) return null
            if (!item.hasOnClickListeners()) return null
            val title = views(item).filterIsInstance<TextView>()
                .firstOrNull { entry(it) == "tvTitle" }?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val drawable = views(item).filterIsInstance<ImageView>()
                .firstOrNull { entry(it) == "ivIcon" }?.drawable ?: return null
            Item(item, title, bitmap(drawable) ?: return null)
        }
        val search = views(bar).firstOrNull { entry(it) == "searchEntryView" && it.isShown }
        val trailingAction = if (search != null) {
            val click = views(search).firstOrNull { it.hasOnClickListeners() } ?: return null
            val drawable = (search as? ImageView)?.drawable ?: return null
            Item(click, "搜索", bitmap(drawable) ?: return null)
        } else null
        val pager = views(home).firstOrNull { entry(it) == "viewPager" } ?: return null
        val selected = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as Int
        }.getOrNull()?.takeIf { it in 0 until row.childCount } ?: return null
        return Bar(bar, items, selected, trailingAction)
    }

    fun collapsedHeight(bar: View): Int = if (bar.javaClass.name == PREFIX + "FloatingTabContainer") {
        val id = bar.resources.getIdentifier("home_floating_tab_shadow_radius", "dimen", "com.tencent.wemeet.app")
        if (id != 0) bar.resources.getDimensionPixelSize(id) else 0
    } else 0

    private fun bitmap(drawable: Drawable): ImageBitmap? = icons[drawable] ?: runCatching {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val bounds = android.graphics.Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        } finally {
            drawable.bounds = bounds
        }
        bitmap.asImageBitmap().also {
            if (icons.size >= 64) icons.clear()
            icons[drawable] = it
        }
    }.getOrNull()

    private fun belongsTo(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun entry(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
        }
    }

    private companion object {
        const val PREFIX = "com.tencent.wemeet.sdk.meeting.premeeting.home.view."
    }
}
