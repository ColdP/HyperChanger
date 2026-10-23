package android.view
import android.content.res.Resources
open class View {
    var id = -1
    var visibility = 0
    var parent: ViewGroup? = null
    var listener = false
    var clicks = 0
    val resources = Resources()
    val isAttachedToWindow: Boolean get() = parent != null
    val isShown: Boolean get() = visibility == 0 && (parent?.isShown ?: true)
    fun hasOnClickListeners() = listener
    fun performClick(): Boolean { if(listener) clicks++; return listener }
}
open class ViewGroup: View() {
    private val children = mutableListOf<View>()
    val childCount get() = children.size
    fun getChildAt(i: Int) = children[i]
    fun addView(v: View) { children += v; v.parent = this }
    fun removeView(v: View) { children -= v; v.parent = null }
}
