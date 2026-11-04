package btm.m.liquidglass.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap

/** Only the verified home rows are accepted; restaurant/product navigation is unrelated. */
internal class MeituanNavigation(private val activity: Activity, private val takeout: Boolean) {
    data class Item(
        val view: View,
        val clickTarget: View,
        val identity: String,
        val label: String,
        val icon: ImageBitmap?,
        val preserveColor: Boolean = false,
    )
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    private var cachedBar: ViewGroup? = null
    private var lastSearchAt: Long? = null
    private val icons = IdentityHashMap<View, IconSnapshot>()
    private val fallbackDrawables = mutableMapOf<String, Drawable?>()

    fun detect(root: ViewGroup): Bar? {
        cachedBar?.takeIf { belongsTo(it, root) }?.let { parse(it, root)?.let { bar -> return bar } }
        val now = SystemClock.uptimeMillis()
        if (lastSearchAt?.let { now - it < 500L } == true) return null
        lastSearchAt = now
        return views(root).filterIsInstance<ViewGroup>().filter { candidate ->
            if (takeout) candidate.javaClass.name == TAKEOUT_BAR && entry(candidate) == "pagerindicator"
            else candidate.javaClass.name == MAIN_BAR || entry(candidate) == "bottom_tab_block"
        }.mapNotNull { parse(it, root) }.firstOrNull()?.also { cachedBar = it.container }
    }

    /** Re-resolve after server updates; one performClick preserves host checks and statistics. */
    fun open(root: ViewGroup, identity: String): Boolean {
        val current = detect(root)?.items?.singleOrNull { it.identity == identity } ?: return false
        return current.clickTarget.performClick()
    }

    fun clear() {
        cachedBar = null
        lastSearchAt = null
        icons.clear()
        fallbackDrawables.clear()
    }

    private fun parse(bar: ViewGroup, root: ViewGroup): Bar? {
        if (!bar.isShown || !bar.isAttachedToWindow || bar.width < root.width * .7f ||
            bar.height <= 0 || bar.height > 120 * root.resources.displayMetrics.density ||
            bar.translationY >= bar.height
        ) return null
        val parsed = if (takeout) parseTakeout(bar) else parseMain(bar)
        if (parsed != null) icons.keys.removeAll { view -> parsed.items.none { it.view === view } }
        return parsed
    }

    private fun parseMain(bar: ViewGroup): Bar? {
        val data = invoke(bar, "getTabData") as? Map<*, *> ?: return null
        val current = invoke(bar, "getCurrentTabArea") ?: return null
        val selectedIdentity = publicString(current, "tabName") ?: return null
        val tagId = bar.resources.getIdentifier("jtm", "id", MAIN_PACKAGE)
        if (tagId == 0) return null
        // s.c creates one direct horizontal row; every direct cell must implement the
        // native title/icon contract and carry a live business key from getTabData().
        val rows = (0 until bar.childCount).mapNotNull { bar.getChildAt(it) as? ViewGroup }
        val matches = rows.mapNotNull rowMap@ { row ->
            if (row.childCount !in 2..8 || !row.isShown) return@rowMap null
            val items = (0 until row.childCount).mapNotNull cellMap@ { index ->
                val cell = row.getChildAt(index)
                if (!cell.isShown) return@cellMap null
                val identity = cell.getTag(tagId) as? String ?: return@cellMap null
                if (!data.containsKey(identity)) return@cellMap null
                val label = (invoke(cell, "getTitle") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: mainLabel(identity) ?: return@cellMap null
                val target = views(cell).firstOrNull {
                    it.isShown && it.isEnabled && it.hasOnClickListeners() && it.getTag(tagId) == identity
                } ?: return@cellMap null
                val drawable = invoke(cell, "getIcon") as? Drawable
                    ?: fallback(mainIcon(identity), cell.drawableState)
                Item(cell, target, identity, label, icon(cell, drawable), identity == "xiaotuan")
            }
            if (items.size != row.childCount || items.map { it.identity }.distinct().size != items.size)
                return@rowMap null
            val selected = items.indexOfFirst { it.identity == selectedIdentity }
            if (selected < 0) null else Bar(bar, items, selected)
        }
        return matches.singleOrNull()
    }

    private fun parseTakeout(bar: ViewGroup): Bar? {
        if (bar.childCount !in 2..8 || invoke(bar, "getTabCount") != bar.childCount) return null
        // MainActivity retains optional routes as GONE cells. getTabCount() counts
        // them too; display only live cells without renumbering their native tags.
        val cells = (0 until bar.childCount).map { bar.getChildAt(it) }.filter { it.isShown }
        if (cells.size !in 2..8 || cells.any { it.tag !is Int } ||
            cells.map { it.tag }.distinct().size != cells.size) return null
        val items = cells.mapNotNull { cell ->
            val label = views(cell).filterIsInstance<TextView>().firstOrNull { entry(it) == "tab_title" }
                ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: cell.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            // ll_normal / iv_mt_cardview and the middle root all call BottomTabView.onClick.
            // Its integer tag is the current native route; never dispatch a fixed index.
            val target = views(cell).firstOrNull {
                it.isShown && it.isEnabled && it.hasOnClickListeners() && it.tag == cell.tag
            } ?: return@mapNotNull null
            val nativeImage = views(cell).filterIsInstance<ImageView>().firstOrNull {
                it.isShown && it.drawable != null && entry(it) in setOf("icon_normal", "iv_promotion", "iv_deep_eat_normal")
            }
            val drawable = nativeImage?.drawable ?: fallback(takeoutIcon(label, cell.isSelected), cell.drawableState)
            Item(cell, target, "${cell.tag}:$label", label, icon(cell, drawable),
                nativeImage?.let { entry(it) != "icon_normal" } == true)
        }
        if (items.size != cells.size) return null
        // setCurrentTab owns the direct cell flag; badges/animated children are not routes.
        val selected = items.indices.filter { items[it].view.isSelected }.singleOrNull() ?: return null
        return Bar(bar, items, selected)
    }

    private fun icon(cell: View, drawable: Drawable?): ImageBitmap? {
        drawable ?: return null
        val current = drawable.current
        val state = drawable.state.contentHashCode()
        val theme = cell.resources.configuration.uiMode
        val now = SystemClock.uptimeMillis()
        icons[cell]?.takeIf {
            it.drawable === drawable && it.current === current && it.state == state && it.theme == theme &&
                (drawable !is Animatable || now - it.at < 250L)
        }?.let { return it.bitmap }
        val bitmap = runCatching {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            val edge = maxOf(width, height).coerceIn(48, 192)
            val scale = edge.toFloat() / maxOf(width, height)
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { output ->
                val old = Rect(drawable.bounds)
                try {
                    drawable.setBounds((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                    drawable.draw(Canvas(output))
                } finally { drawable.bounds = old }
            }.asImageBitmap()
        }.getOrNull()
        icons[cell] = IconSnapshot(drawable, current, state, theme, now, bitmap)
        return bitmap
    }

    private fun fallback(name: String?, state: IntArray): Drawable? {
        name ?: return null
        val key = "$name:${state.contentHashCode()}:${activity.resources.configuration.uiMode}"
        if (!fallbackDrawables.containsKey(key)) {
            if (fallbackDrawables.size >= 32) fallbackDrawables.clear()
            fallbackDrawables[key] = runCatching {
                val id = activity.resources.getIdentifier(name, "drawable", activity.packageName)
                if (id == 0) null else activity.getDrawable(id)?.mutate()?.also { it.state = state }
            }.getOrNull()
        }
        return fallbackDrawables[key]
    }

    private fun invoke(receiver: Any, name: String): Any? = runCatching {
        receiver.javaClass.getMethod(name).invoke(receiver)
    }.getOrNull()

    private fun publicString(receiver: Any, name: String): String? = runCatching {
        receiver.javaClass.getField(name).get(receiver) as? String
    }.getOrNull()

    private fun mainIcon(identity: String): String? = when (identity) {
        "homepage" -> "ls6"
        "video" -> "ti2"
        "message", "order" -> "cpr"
        "mine" -> "jpw"
        "shoppingcart" -> "ozc"
        "xiaotuan" -> "ic_vector_xiaotuan"
        else -> null
    }

    private fun mainLabel(identity: String): String? = when (identity) {
        "homepage" -> "推荐"
        "video" -> "视频"
        "message" -> "消息"
        "order" -> "订单"
        "mine" -> "我的"
        "shoppingcart" -> "购物车"
        "xiaotuan" -> "小团"
        else -> null
    }

    private fun takeoutIcon(label: String, selected: Boolean): String? {
        val stem = when (label) {
            "首页", "外卖" -> "home"
            "神券", "神券节", "会员" -> "shen"
            "订单" -> "order"
            "我的" -> "mine"
            else -> return null
        }
        return "wm_main_tab_${stem}_${if (selected) "select" else "unselect"}"
    }

    private data class IconSnapshot(
        val drawable: Drawable, val current: Drawable, val state: Int, val theme: Int,
        val at: Long, val bitmap: ImageBitmap?,
    )

    private fun belongsTo(view: View, root: View): Boolean = view.isAttachedToWindow &&
        generateSequence(view as View?) { it.parent as? View }.any { it === root }

    private fun entry(view: View): String? = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
    }

    companion object {
        const val MAIN_PACKAGE = "com.sankuai.meituan"
        const val MAIN_ACTIVITY = "com.meituan.android.pt.homepage.activity.MainActivity"
        const val MAIN_BAR = "com.meituan.android.pt.homepage.tab.TabBlockV2"
        const val TAKEOUT_PACKAGE = "com.sankuai.meituan.takeoutnew"
        const val TAKEOUT_ACTIVITY = "com.sankuai.waimai.business.page.homepage.MainActivity"
        const val TAKEOUT_BAR = "com.sankuai.waimai.business.page.homepage.view.BottomTabView"
    }
}
