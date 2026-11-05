// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Host animations target this container; the inner transform owns only the fitted scale.
 * Android dispatches touch through both transforms back into unchanged native content.
 */
internal class SidebarToolboxScaleLayout(content: View, width: Int, height: Int, private val percent: Float) : ViewGroup(content.context) {
    private var nativeWidth = width.coerceAtLeast(1)
    private var nativeHeight = height.coerceAtLeast(1)
    private var availableWidth = Int.MAX_VALUE
    private var availableHeight = Int.MAX_VALUE
    private var contentHeight = nativeHeight
    // Verified roots: video/conversation main_content and the game's z1/w1 cards.
    // Keep their widths, gravity and all descendant layout parameters intact.
    private val fullHeightCards = (content as? ViewGroup)?.let { root ->
        (0 until root.childCount).map { root.getChildAt(it) }
            .filter { it.layoutParams?.height == nativeHeight }
    }.orEmpty()
    var fit = sidebarToolboxFit(nativeWidth, nativeHeight, percent, availableWidth, availableHeight)
        private set
    private val transform = FrameLayout(content.context).apply {
        clipChildren = false
        clipToPadding = false
        pivotX = 0f
        pivotY = 0f
        addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(transform, LayoutParams(nativeWidth, nativeHeight))
    }

    fun configure(width: Int, height: Int, maxWidth: Int, maxHeight: Int) {
        nativeWidth = width.coerceAtLeast(1)
        nativeHeight = height.coerceAtLeast(1)
        availableWidth = maxWidth.coerceAtLeast(1)
        availableHeight = maxHeight.coerceAtLeast(1)
        fit = sidebarToolboxFit(nativeWidth, nativeHeight, percent, availableWidth, availableHeight)
        // Called before the parent's measure pass; mutate its pending parameters without
        // scheduling another layout from inside onMeasure.
        layoutParams?.let { it.width = fit.width; it.height = LayoutParams.WRAP_CONTENT }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun limit(spec: Int, available: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) available
            else minOf(available, MeasureSpec.getSize(spec).coerceAtLeast(1))
        val maxWidth = limit(widthMeasureSpec, availableWidth)
        val maxHeight = limit(heightMeasureSpec, availableHeight)
        // RelativeLayout supplies EXACTLY after resolving both edges against the rail.
        // A sibling's measuredHeight may still be provisional in an earlier measure pass.
        val alignedHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1) else 0
        fit = sidebarToolboxFit(nativeWidth, nativeHeight, percent, maxWidth, maxHeight,
            alignedHeight)
        // Add vertical room at the existing scale. Content never gets stretched vertically
        // or reflowed into different columns merely because the rail is taller.
        contentHeight = sidebarToolboxContentHeight(nativeHeight, fit)
        fullHeightCards.forEach { it.layoutParams.height = contentHeight }
        transform.measure(MeasureSpec.makeMeasureSpec(nativeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY))
        transform.scaleX = fit.scale
        transform.scaleY = fit.scale
        setMeasuredDimension(fit.width, fit.height)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        transform.layout(0, 0, nativeWidth, contentHeight)
    }
}
