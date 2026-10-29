// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

internal fun portalSplashScale(offsetPercent: Int): Float =
    (100 + offsetPercent.coerceIn(-100, 100)) / 100f

internal fun portalSplashDimension(nativePx: Int, offsetPercent: Int): Int =
    ceil(nativePx.coerceAtLeast(0) * portalSplashScale(offsetPercent).toDouble()).toInt().coerceAtLeast(1)

/** Reserve the scaled window bounds while Android maps taps into the native content.
 * The host's card/icon animations keep their own unmodified scale and translation.
 */
internal class PortalSplashSizeLayout(
    private val content: View,
    private val nativeWidth: Int,
    private val offsetPercent: Int,
) : ViewGroup(content.context) {
    init {
        clipChildren = false
        clipToPadding = false
        addView(content, LayoutParams(nativeWidth, LayoutParams.WRAP_CONTENT))
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = portalSplashScale(offsetPercent)
        content.scaleY = portalSplashScale(offsetPercent)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.measure(
            MeasureSpec.makeMeasureSpec(nativeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(
            portalSplashDimension(content.measuredWidth, offsetPercent),
            portalSplashDimension(content.measuredHeight, offsetPercent),
        )
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(nativeWidth, LayoutParams.WRAP_CONTENT)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
    }
}
