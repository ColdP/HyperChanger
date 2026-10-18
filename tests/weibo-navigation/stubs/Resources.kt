package android.content.res

class DisplayMetrics(val density: Float = 1f)
class Resources {
    val displayMetrics = DisplayMetrics()
    fun getResourceEntryName(id: Int): String = "renamed_$id"
}
