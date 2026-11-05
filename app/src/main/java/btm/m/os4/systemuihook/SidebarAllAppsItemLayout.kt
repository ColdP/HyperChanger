// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.roundToInt

/** Keep the host's real child views, bindings and touch animations at their native owners. */
internal class SidebarAllAppsItemLayout private constructor(
    original: LinearLayout,
    private val frame: FrameLayout,
    private val name: TextView,
    private val icon: ImageView,
    private val badges: List<ImageView>,
) : LinearLayout(original.context) {
    private val density = resources.displayMetrics.density
    private val originalPadding = intArrayOf(original.paddingLeft, original.paddingTop,
        original.paddingRight, original.paddingBottom)
    private val originalTextSize = name.textSize
    private val originalNameGap = (name.layoutParams as LayoutParams).topMargin
    private val originalImages = (listOf(icon) + badges).associateWith {
        FrameLayout.LayoutParams(it.layoutParams as FrameLayout.LayoutParams)
    }
    private val iconSize = originalImages.getValue(icon)
    private val topInset = badges.maxOfOrNull { -originalImages.getValue(it).topMargin }?.coerceAtLeast(0) ?: 0
    private val sideInset = badges.maxOfOrNull {
        val params = originalImages.getValue(it)
        params.marginStart + params.width - iconSize.width
    }?.coerceAtLeast(0) ?: 0

    init {
        id = original.id
        layoutParams = original.layoutParams
        orientation = original.orientation
        gravity = original.gravity
        layoutDirection = original.layoutDirection
        textDirection = original.textDirection
        textAlignment = original.textAlignment
        background = original.background
        foreground = original.foreground
        importantForAccessibility = original.importantForAccessibility
        contentDescription = original.contentDescription
        clipChildren = original.clipChildren
        clipToPadding = original.clipToPadding
        isBaselineAligned = original.isBaselineAligned
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            // Preserve the native padding unless the entire cell becomes narrower than it.
            val left = originalPadding[0].coerceAtMost(width / 4)
            val right = originalPadding[2].coerceAtMost(width / 4)
            setPadding(left, originalPadding[1], right, originalPadding[3])
            val scale = sidebarAllAppsItemScale(width - left - right, density)
            fun scaled(value: Int) = floor(value * scale.toDouble()).toInt().coerceAtLeast(0)
            frame.layoutParams.apply {
                this.width = scaled(iconSize.width + 2 * sideInset)
                height = scaled(iconSize.height + topInset)
            }
            // The edit badges used to extend beyond the icon frame. Give them real space,
            // with symmetric side insets so the icon itself remains centered in the cell.
            originalImages.forEach { (view, baseline) ->
                (view.layoutParams as FrameLayout.LayoutParams).apply {
                    this.width = scaled(baseline.width)
                    height = scaled(baseline.height)
                    marginStart = scaled(sideInset + if (view === icon) 0 else baseline.marginStart)
                    marginEnd = 0
                    topMargin = scaled(topInset + if (view === icon) 0 else baseline.topMargin)
                    bottomMargin = 0
                }
            }
            val fontSize = originalTextSize * scale
            if (name.textSize != fontSize) name.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            (name.layoutParams as LayoutParams).topMargin =
                maxOf((2f * density).roundToInt(), scaled(originalNameGap))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        fun adapt(original: LinearLayout): View {
            if (original.parent != null || original.childCount != 2 || original.orientation != VERTICAL) return original
            val frame = original.getChildAt(0) as? FrameLayout ?: return original
            val name = original.getChildAt(1) as? TextView ?: return original
            fun image(key: String): ImageView? {
                val id = original.resources.getIdentifier(key, "id", "com.miui.securitycenter")
                return if (id == 0) null else frame.findViewById(id)
            }
            val icon = image("ivIcon") ?: return original
            val badges = listOf("ivPin", "ivUnpin", "ivPinNotAllowed").map { image(it) ?: return original }
            if (frame.childCount != 4 || name.layoutParams !is LayoutParams || frame.layoutParams !is LayoutParams ||
                (listOf(icon) + badges).any {
                    val params = it.layoutParams
                    params !is FrameLayout.LayoutParams || params.width <= 0 || params.height <= 0
                }) return original
            val adapted = SidebarAllAppsItemLayout(original, frame, name, icon, badges)
            original.removeAllViews()
            try {
                adapted.addView(frame)
                adapted.addView(name)
            } catch (error: Throwable) {
                adapted.removeAllViews()
                original.addView(frame)
                original.addView(name)
                throw error
            }
            return adapted
        }
    }
}
