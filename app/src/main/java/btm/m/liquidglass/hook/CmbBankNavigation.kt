package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Resource contract from cmb.pb 14.4.6; no reflection into shielded business code. */
internal object CmbBankNavigation {
    const val PACKAGE = "cmb.pb"
    const val ACTIVITY = "cmb.pb.app.mainframe.container.PBMainActivity"

    data class Item(
        val view: View,
        val clickTarget: View,
        val label: String,
        val icon: ImageView,
        val selected: Boolean
    )
    data class Bar(
        val view: ViewGroup,
        val background: ImageView,
        val page: ViewGroup,
        val items: List<Item>,
        val selectedIndex: Int
    )

    fun detect(content: ViewGroup): Bar? {
        for (row in views(content).filterIsInstance<ViewGroup>()) {
            if (entry(row) != "tab" || !row.isAttachedToWindow || !row.isShown ||
                row.childCount !in 2..8
            ) continue
            val parent = row.parent as? ViewGroup ?: continue
            val siblings = (0 until parent.childCount).map(parent::getChildAt)
            val page = siblings.singleOrNull { entry(it) == "tabContent" } as? ViewGroup ?: continue
            val background = siblings.singleOrNull { entry(it) == "tab_bg" } as? ImageView ?: continue
            if (!page.isShown) continue
            val items = (0 until row.childCount).map { index ->
                val cell = row.getChildAt(index)
                if (!cell.isShown) return null
                val title = views(cell).filterIsInstance<TextView>()
                    .singleOrNull { entry(it) == "title" } ?: return null
                val icon = views(cell).filterIsInstance<ImageView>()
                    .singleOrNull { entry(it) == "icon" } ?: return null
                // Avatar-based and other layouts have a different icon/selection contract.
                if (!icon.isShown || icon.drawable == null) return null
                val label = title.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val click = views(cell).firstOrNull { it.hasOnClickListeners() } ?: return null
                val selected = views(cell).any { it.isSelected } ||
                    icon.drawable!!.state.contains(android.R.attr.state_selected)
                Item(cell, click, label, icon, selected)
            }
            if (items.count { it.selected } != 1) return null
            return Bar(row, background, page, items, items.indexOfFirst { it.selected })
        }
        return null
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
}
