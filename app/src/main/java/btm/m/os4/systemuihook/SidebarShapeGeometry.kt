// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.roundToInt

internal fun sidebarAdjustedRadius(baseline: Float, offset: Float, density: Float): Float =
    (baseline + (if (offset.isFinite()) offset.coerceIn(-32f, 64f) else 0f) * density).coerceAtLeast(0f)

/** Android cannot draw a rounded rectangle with a radius larger than its short edge. */
internal fun sidebarCardRadius(requested: Float, width: Int, height: Int): Float =
    requested.coerceAtLeast(0f).let { radius ->
        if (width > 0 && height > 0) minOf(radius, width / 2f, height / 2f) else radius
    }

internal data class SidebarCapsuleBounds(val width: Int, val height: Int, val inset: Int) {
    val bottomInset get() = inset
    val radius get() = height / 2f
    val listBottomSpace get() = height + bottomInset
}

/** Use one safe inset for both sides and the bottom, including heavily rounded rails. */
internal fun sidebarCapsuleBounds(width: Int, height: Int, radius: Float, density: Float): SidebarCapsuleBounds {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val inset = minOf((12f * density).roundToInt(), (w - 1) / 2, (h - 1) / 2).coerceAtLeast(0)
    val availableWidth = w - 2 * inset
    val availableHeight = h - 2 * inset
    val outerRadius = sidebarCardRadius(radius, w, h)
    val minimum = minOf(24f * density, availableWidth.toFloat(), availableHeight.toFloat())
    val originalHeight = maxOf(2f * (outerRadius - inset), minimum).roundToInt()
        .coerceIn(1, minOf(availableWidth, availableHeight))
    val buttonHeight = (originalHeight * 1.8f).roundToInt().coerceIn(1, minOf(w, h))
    // A taller capsule needs less inset to keep its bottom arcs aligned with the rail.
    val adjustedInset = (outerRadius - buttonHeight / 2f).roundToInt().coerceIn(0,
        minOf(inset, (w - buttonHeight) / 2, (h - buttonHeight) / 2))
    val buttonWidth = w - 2 * adjustedInset
    val shrunkenWidth = (buttonWidth * .82f).roundToInt().coerceAtLeast(1)
    // 0.30R exceeds (1 - 1/sqrt(2))R: even the button's bounding rectangle stays
    // inside the outer corner. A normal rail also keeps at least 16dp of breathing room.
    val sharedInset = maxOf((w - shrunkenWidth + 1) / 2, (16f * density).roundToInt(),
        kotlin.math.ceil(outerRadius * .30f).toInt())
        .coerceIn(0, minOf((w - 1) / 2, (h - 1) / 2))
    val finalWidth = w - 2 * sharedInset
    val finalHeight = (buttonHeight * .82f).roundToInt()
        .coerceIn(1, minOf(finalWidth, h - 2 * sharedInset))
    return SidebarCapsuleBounds(finalWidth, finalHeight, sharedInset)
}

/** Preserve the host's advanced-material colors and blend modes, at low opacity. */
internal fun sidebarCapsuleBlendColors(original: IntArray): IntArray =
    original.map { softGlassColorAlpha(it, 40) }.toIntArray()

internal fun sidebarCapsuleGlassRecipe(original: FloatArray): FloatArray = tuneSoftGlass(
    original, SoftGlassSettings(enabled = true,
        adjustments = mapOf("alpha" to -75, "light" to 80, "darker" to -100)),
)

internal fun sidebarCapsuleBlur(original: Int): Int = (original.coerceAtLeast(0) * .2f).roundToInt().coerceAtMost(12)
