package android.content.res
class Resources {
    fun getResourceEntryName(id: Int): String = when(id) {1 -> "tvTitle"; 2 -> "ivIcon"; 3 -> "tabsView"; 4 -> "viewPager"; 5 -> "searchEntryView"; else -> error("Unknown id")}
    fun getIdentifier(name: String, kind: String, pkg: String) = if(name == "home_floating_tab_shadow_radius") 40 else 0
    fun getDimensionPixelSize(id: Int) = id
}
