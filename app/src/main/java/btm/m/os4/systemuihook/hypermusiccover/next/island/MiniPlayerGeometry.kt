// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the pill inside the shortcut host even if an OEM layout moves a shortcut off centre. */
internal object MiniPlayerGeometry {
    /** The room between the pill and each shortcut disc at rest; closing it presses on a disc. */
    const val DISC_GAP_DP = 8f

    /** However close the buttons, the pill keeps this width and presses on the discs instead. */
    const val MIN_PILL_DP = 140f

    const val STACK_BACK_SCALE = 0.9f
    const val STACK_BACK_ALPHA = 0.7f
    const val STACK_EDGE_DP = 4f

    fun stackBackSizePx(frontPx: Int): Int =
        (frontPx * STACK_BACK_SCALE).roundToInt().coerceAtLeast(1)

    /** Keep the rear card's lower edge [edgePx] below the front card's lower edge. */
    fun stackBackCenterYPx(frontCenterY: Float, frontHeightPx: Int, edgePx: Int): Float =
        frontCenterY + (frontHeightPx - stackBackSizePx(frontHeightPx)) / 2f + edgePx

    fun heightDp(radiusDp: Float): Float = (radiusDp.coerceIn(10f, 60f) * 2f).coerceAtLeast(48f)

    fun widthPx(requestedPx: Int, hostWidth: Int, centerX: Float, marginPx: Int): Int {
        if (hostWidth <= 0) return 0
        val margin = marginPx.coerceIn(0, (hostWidth - 1) / 2)
        val center = centerX.coerceIn(margin.toFloat(), (hostWidth - margin).toFloat())
        val symmetricRoom = (2f * min(center, hostWidth - center) - margin).toInt()
        return min(requestedPx, min(hostWidth - 2 * margin, symmetricRoom)).coerceAtLeast(1)
    }

    /**
     * The widest the pill can be and still clear both shortcut discs by [gapPx]: each disc is
     * a circle of [discPx] on its button's centre; a button that is not there takes nothing.
     * Never below [minPx] - past that the discs are simply pressed on from the start.
     */
    fun clearOfDiscsPx(widthPx: Int, centerX: Float, leftCx: Float?, rightCx: Float?,
                       discPx: Float, gapPx: Float, minPx: Int): Int {
        var half = widthPx / 2f
        if (leftCx != null) half = min(half, centerX - (leftCx + discPx / 2f) - gapPx)
        if (rightCx != null) half = min(half, (rightCx - discPx / 2f) - centerX - gapPx)
        return max(min(minPx, widthPx), (half * 2f).toInt()).coerceAtMost(widthPx)
    }

    /**
     * The pill's width with a small island of [islandPx] beside it, [gapPx] apart: the pair takes
     * the pill's own [widthPx] and gives the pill no less than [minPx] - but never reaches past
     * [roomPx], the most the row has between the two discs (clearOfDiscsPx with no minimum).
     * The first version let the minimum win, and the pair grew into the camera's disc: its
     * touch area took the small island's taps (filmed 2026-09-25). Only when even that room
     * is too small for a pill as wide as it is tall does the pair press on the discs.
     */
    fun pillBesideIslandPx(widthPx: Int, roomPx: Int, islandPx: Int, gapPx: Int, minPx: Int): Int {
        val wanted = max(minPx, widthPx - gapPx - islandPx)
        val fits = roomPx - gapPx - islandPx
        return max(min(wanted, fits), min(wanted, islandPx)).coerceAtLeast(1)
    }
}
