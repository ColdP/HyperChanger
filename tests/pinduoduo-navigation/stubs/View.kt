package android.view

open class View {
    companion object { const val VISIBLE = 0; const val GONE = 8 }
    var visibility = VISIBLE
    var width = 400
    var height = 64
    var alpha = 1f
    var isClickable = false
    var isSelected = false
    var isActivated = false
    var contentDescription: CharSequence? = null
    var parent: ViewGroup? = null
    val isShown: Boolean get() = visibility == VISIBLE && (parent?.isShown ?: true)
    val isAttachedToWindow: Boolean get() = parent != null || this is Root
}

open class ViewGroup : View() {
    private val children = mutableListOf<View>()
    var reads = 0
    val childCount get() = children.size
    fun getChildAt(index: Int): View { reads++; return children[index] }
    fun addView(child: View) { children += child; child.parent = this }
    fun removeView(child: View) { children -= child; child.parent = null }
}

class Root : ViewGroup()
