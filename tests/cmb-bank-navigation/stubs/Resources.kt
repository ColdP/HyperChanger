package android.content.res

class Resources {
    fun getResourceEntryName(id: Int): String = when (id) {
        1 -> "tab"; 2 -> "tabContent"; 3 -> "tab_bg"; 4 -> "icon"; 5 -> "title"
        else -> error("Unknown resource")
    }
}
