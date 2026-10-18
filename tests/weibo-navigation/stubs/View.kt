package android.view

import android.content.res.Resources

/** Minimal view geometry for JVM regression checks; not a substitute for device tests. */
open class View {
    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val NO_ID = -1
    }
    var width = 400
    var height = 64
    var x = 0
    var y = 736
    var visibility = VISIBLE
    var isClickable = false
    var tag: Any? = null
    var id = NO_ID
    var contentDescription: CharSequence? = null
    var parent: ViewGroup? = null
    var attached = true
    val resources = Resources()
    val rootView: View get() = parent?.rootView ?: this
    val isShown: Boolean get() = visibility == VISIBLE && (parent?.isShown ?: true)
    val isAttachedToWindow: Boolean get() = attached && (parent?.isAttachedToWindow ?: true)
    fun getLocationInWindow(position: IntArray) {
        position[0] = x
        position[1] = y
    }
}

open class ViewGroup : View() {
    private val children = mutableListOf<View>()
    val childCount get() = children.size
    fun getChildAt(index: Int) = children[index]
    fun addView(child: View) { child.parent = this; children += child }
    fun removeView(child: View) { children -= child; child.parent = null; child.attached = false }
}
