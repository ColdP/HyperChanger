package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup

/** The main page, tab backdrop and tab wrapper are siblings in takeout_activity_main. */
internal object MeituanTakeoutLayout {
    data class Surface(val page: ViewGroup, val background: ViewGroup)

    fun resolve(bar: ViewGroup): Surface? {
        if (entry(bar) != "pagerindicator") return null
        val wrapper = bar.parent as? ViewGroup ?: return null
        if (entry(wrapper) != "navigation_bar_margin_layout_container") return null
        val home = wrapper.parent as? ViewGroup ?: return null
        val siblings = (0 until home.childCount).map { home.getChildAt(it) }
        val page = siblings.singleOrNull { entry(it) == "pager_container" } as? ViewGroup ?: return null
        val background = siblings.singleOrNull { entry(it) == "indicator_bg_container" } as? ViewGroup ?: return null
        return Surface(page, background)
    }

    fun reservedHeights(surface: Surface, navigationInset: Int): Set<Int> {
        val resources = surface.page.resources
        val nativeHeights = listOf("wm_page_main_home_indicator_height", "wm_page_main_home_indicator_bg_height")
            .mapNotNull { name ->
                val id = resources.getIdentifier(name, "dimen", MeituanNavigation.TAKEOUT_PACKAGE)
                if (id == 0) null else resources.getDimensionPixelSize(id).takeIf { it > 0 }
            }.toSet()
        // tab.j and MainActivity.L4 can use the background's current height, already
        // including the navigation inset, rather than the initial XML reservation.
        val currentBackgroundHeight = surface.background.layoutParams?.height ?: 0
        val heights = nativeHeights + nativeHeights.map { it + navigationInset }
        return heights + listOf(currentBackgroundHeight, navigationInset).filter { it > 0 }
    }

    private fun entry(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
