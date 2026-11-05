// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

/** Size baselines always come from the host, never from a previously changed view. */
internal fun sidebarScaledSize(original: Int, percent: Float, minimum: Int = 1): Int =
    if (original < 0) original else (original * (1f + percent / 100f)).roundToInt().coerceAtLeast(minimum)

internal fun sidebarScaledWidth(original: Int, settings: SidebarAppearanceSettings): Int =
    sidebarScaledSize(original * if (settings.dualRowApps) 2 else 1,
        if (settings.widthScaleEnabled) settings.widthScale else 0f)

/** Each extra pixel between column centers adds two pixels to the complete rail. */
internal fun sidebarSpacedWidth(original: Int, settings: SidebarAppearanceSettings,
    spacing: SidebarColumnSpacingSettings, density: Float, nativeIconSize: Int): Int {
    val baseline = sidebarScaledWidth(original, settings)
    val offset = spacing.normalized().spacing
    if (!settings.dualRowApps || !spacing.enabled || offset == 0f) return baseline
    val icon = sidebarScaledSize(nativeIconSize,
        if (settings.iconScaleEnabled) settings.iconScale else 0f, minimum = 0)
    return (baseline + 2f * offset * density).roundToInt()
        .coerceAtLeast(2 * (icon + (8f * density).roundToInt()))
}

internal data class SidebarRailInsets(val edge: Int, val icon: Int, val gap: Int)

/** Match the inner icon's approximate quarter-radius to the outer card with one inset.
 * Width gained from column spacing goes into the middle gap, never the outside edges.
 */
internal fun sidebarRailInsets(width: Int, radius: Float, requestedIcon: Int,
    density: Float, spacing: SidebarColumnSpacingSettings): SidebarRailInsets {
    val w = width.coerceAtLeast(1)
    val safe = (8f * density).roundToInt().coerceAtLeast(1)
    val offset = if (spacing.enabled) spacing.normalized().spacing.coerceAtLeast(0f) * density / 8f else 0f
    val edge = maxOf(safe, (radius - requestedIcon.coerceAtLeast(0) * .25f).roundToInt(),
        (8f * density + offset).roundToInt()).coerceIn(0, (w - 1) / 4)
    val cell = (w - 2 * edge) / 2
    val icon = requestedIcon.coerceAtLeast(0).coerceAtMost((cell - (safe + 1) / 2).coerceAtLeast(0))
    return SidebarRailInsets(edge, icon, w - 2 * edge - 2 * icon)
}

/** Width excludes the native outer margins and inner padding; reserve the badge too. */
internal fun sidebarAllAppsItemScale(contentWidth: Int, density: Float): Float =
    (contentWidth.coerceAtLeast(0) / (68f * density.coerceAtLeast(0.01f))).coerceIn(0f, 1.2f)

internal data class SidebarAllAppsSpec(val columns: Int, val width: Int)

/** Cached dual-column geometry for the dock adapter. The stock span lookup walks every prior
 * item for each query, which becomes visible while Folme animates the rail. */
internal class SidebarSpanTable {
    private var sizes = IntArray(0)
    private var indices = IntArray(0)
    private var groups = IntArray(0)
    private var valid = false

    @Synchronized
    fun prepare(provider: () -> IntArray) {
        if (valid) return
        sizes = provider().map { if (it == 2) 2 else 1 }.toIntArray()
        indices = IntArray(sizes.size)
        groups = IntArray(sizes.size)
        var span = 0
        var group = 0
        sizes.forEachIndexed { position, size ->
            if (span + size > 2) {
                span = 0
                group++
            }
            indices[position] = span
            groups[position] = group
            span += size
            if (span == 2) {
                span = 0
                group++
            }
        }
        valid = true
    }

    @Synchronized fun invalidate() { valid = false }
    @Synchronized fun size(position: Int): Int = sizes.getOrElse(position) { 1 }
    @Synchronized fun index(position: Int): Int = indices.getOrElse(position) { 0 }
    @Synchronized fun group(position: Int): Int = groups.getOrElse(position) { position }
}

internal data class SidebarToolboxFit(val scale: Float, val width: Int, val height: Int)

/** A single transform preserves the native content's aspect ratio, including its outline. */
internal fun sidebarToolboxFit(nativeWidth: Int, nativeHeight: Int, percent: Float,
    availableWidth: Int, availableHeight: Int, alignedHeight: Int = 0): SidebarToolboxFit {
    val width = nativeWidth.coerceAtLeast(1)
    val height = nativeHeight.coerceAtLeast(1)
    val maxWidth = availableWidth.coerceAtLeast(1)
    val maxHeight = availableHeight.coerceAtLeast(1).let { if (alignedHeight > 0) minOf(it, alignedHeight) else it }
    val requested = 1.0 + (if (percent.isFinite()) percent.coerceIn(-100f, 100f) else 0f) / 100.0
    val scale = minOf(requested, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    return SidebarToolboxFit(scale.toFloat(),
        ceil(width * scale).toInt().coerceIn(1, maxWidth),
        if (alignedHeight > 0) alignedHeight else ceil(height * scale).toInt().coerceIn(1, maxHeight))
}

/** Fit the painted native viewport inside the final bounds even when clipping is disabled. */
internal fun sidebarToolboxContentHeight(nativeHeight: Int, fit: SidebarToolboxFit): Int =
    if (fit.scale > 0f) floor(fit.height.toDouble() / fit.scale).toInt().coerceAtLeast(nativeHeight.coerceAtLeast(1))
    else nativeHeight.coerceAtLeast(1)

/** Keep the complete rounded card inside the space beside the actual rail. */
internal fun sidebarAllAppsSpec(originalColumns: Int, originalWidth: Int, availableWidth: Int): SidebarAllAppsSpec {
    val width = originalWidth.coerceAtLeast(1).coerceAtMost(availableWidth.coerceAtLeast(1))
    // Phone layouts use three columns in both orientations. Tablet layouts keep their
    // native span count; actual item content adapts to the space given to each cell.
    val columns = if (originalColumns <= 5) originalColumns.coerceIn(1, 3) else originalColumns
    return SidebarAllAppsSpec(columns, width)
}

/** A divider occupies a complete row; application order stays in adapter order. */
internal fun sidebarSpanIndex(position: Int, spanCount: Int, spanSize: (Int) -> Int): Int {
    if (spanSize(position) == spanCount) return 0
    var span = 0
    for (index in 0 until position) {
        span += spanSize(index)
        if (span == spanCount) span = 0
        else if (span > spanCount) span = spanSize(index)
    }
    return if (span + spanSize(position) <= spanCount) span else 0
}
