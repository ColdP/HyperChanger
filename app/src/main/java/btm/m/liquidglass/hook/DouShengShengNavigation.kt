package btm.m.liquidglass.hook

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** MainTab's own identity, listener and selected flag are the host navigation contract. */
internal class DouShengShengNavigation {
    data class Item(val view: View, val identity: String, val label: String)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    private var cached: ViewGroup? = null
    private var lastSearch: Long? = null

    fun detect(root: ViewGroup): Bar? {
        cached?.takeIf { belongsTo(it, root) }?.let { parse(it, root)?.let { bar -> return bar } }
        val now = SystemClock.uptimeMillis()
        if (lastSearch?.let { now - it < 500L } == true) return null
        lastSearch = now
        // Reject multiple live home containers rather than choosing a background page.
        return views(root).filterIsInstance<ViewGroup>().filter { it.javaClass.name == CONTAINER }
            .mapNotNull { parse(it, root) }.toList().singleOrNull()?.also { cached = it.container }
    }

    fun open(root: ViewGroup, identity: String): Boolean {
        val item = detect(root)?.items?.singleOrNull { it.identity == identity } ?: return false
        // MPFBottomTabComponent.LLILZ binds the business OnClickListener to BottomTab itself.
        return item.view.performClick()
    }

    fun clear() { cached = null; lastSearch = null }

    private fun parse(bar: ViewGroup, root: ViewGroup): Bar? {
        if (!bar.isShown || !bar.isAttachedToWindow || bar.width < root.width * .7f ||
            bar.height <= 0 || bar.height > 120 * root.resources.displayMetrics.density ||
            bar.translationY >= bar.height) return null
        val rows = views(bar).filterIsInstance<LinearLayout>().filter { row ->
            row.orientation == LinearLayout.HORIZONTAL && row.isShown && row.childCount in 2..8 &&
                (0 until row.childCount).all { isMainTab(row.getChildAt(it)) }
        }
        return rows.mapNotNull { row ->
            val cells = (0 until row.childCount).map { row.getChildAt(it) }
            // A partial row during server rebuilding is unsafe to present or dispatch.
            if (cells.any { !it.isShown || !it.isEnabled || !it.hasOnClickListeners() ||
                    it.width <= 0 || it.height <= 0 }) return@mapNotNull null
            if (cells.zipWithNext().any { (a, b) -> a.left + a.width > b.left }) return@mapNotNull null
            val items = cells.map { cell ->
                val identity = (invoke(cell, "getCurrentTabId") as? String)?.takeIf { it.isNotBlank() }
                    ?: (invoke(cell, "getTabType") as? String)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = invoke(cell, "getTabTitle") as? TextView ?: return@mapNotNull null
                if (!belongsTo(title, cell)) return@mapNotNull null
                val label = title.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Item(cell, identity, label)
            }
            if (items.map { it.identity }.distinct().size != items.size) return@mapNotNull null
            val selected = cells.indices.filter { cells[it].isSelected }.singleOrNull() ?: return@mapNotNull null
            Bar(bar, items, selected)
        }.toList().singleOrNull()
    }

    companion object {
        const val PACKAGE = "com.ss.android.ugc.lifeservices"
        const val ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity"
        const val CONTAINER = "com.ss.android.ugc.aweme.homepage.ui.bottombar.MainBottomTabContainer"
        private const val MAIN_TAB = "com.ss.android.ugc.aweme.homepage.ui.view.MainTab"

        private fun isMainTab(view: View): Boolean = generateSequence(view.javaClass as Class<*>?) {
            it.superclass
        }.any { it.name == MAIN_TAB }

        fun invoke(receiver: Any, name: String): Any? = runCatching {
            receiver.javaClass.getMethod(name).invoke(receiver)
        }.getOrNull()

        fun belongsTo(view: View, root: View): Boolean = view.isAttachedToWindow &&
            generateSequence(view as View?) { it.parent as? View }.any { it === root }

        fun views(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
        }

        fun entry(view: View): String? = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

        /** koc is the full business page, a direct sibling of the native bottom shell. */
        fun page(bar: View): ViewGroup? {
            val parent = bar.parent as? ViewGroup ?: return null
            return (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<ViewGroup>()
                .singleOrNull { entry(it) == "koc" && it.isShown && it.width >= bar.width * .9f &&
                    it.height > bar.height * 2 }
        }

        fun divider(bar: View): View? {
            val parent = bar.parent as? ViewGroup ?: return null
            return (0 until parent.childCount).map { parent.getChildAt(it) }.singleOrNull {
                entry(it) == "as3" && it.width >= bar.width * .9f &&
                    it.height in 1..(2 * bar.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            }
        }
    }
}
