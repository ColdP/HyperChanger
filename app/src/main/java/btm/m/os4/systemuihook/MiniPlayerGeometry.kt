// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import kotlin.math.max
import kotlin.math.min

/** Geometry rules carried over from HyperMusicCover's lockscreen super island. */
internal object MiniPlayerGeometry {
    const val DISC_GAP_DP = 8f
    const val MIN_PILL_DP = 140f

    fun heightDp(radiusDp: Float): Float = (radiusDp.coerceIn(10f, 60f) * 2f).coerceAtLeast(48f)

    fun widthPx(requestedPx: Int, hostWidth: Int, centerX: Float, marginPx: Int): Int {
        if (hostWidth <= 0) return 0
        val margin = marginPx.coerceIn(0, (hostWidth - 1) / 2)
        val center = centerX.coerceIn(margin.toFloat(), (hostWidth - margin).toFloat())
        val symmetricRoom = (2f * min(center, hostWidth - center) - margin).toInt()
        return min(requestedPx, min(hostWidth - 2 * margin, symmetricRoom)).coerceAtLeast(1)
    }

    fun clearOfDiscsPx(widthPx: Int, centerX: Float, leftCx: Float?, rightCx: Float?, discPx: Float,
                       gapPx: Float, minPx: Int): Int {
        var half = widthPx / 2f
        if (leftCx != null) half = min(half, centerX - (leftCx + discPx / 2f) - gapPx)
        if (rightCx != null) half = min(half, (rightCx - discPx / 2f) - centerX - gapPx)
        return max(min(minPx, widthPx), (half * 2f).toInt()).coerceAtMost(widthPx)
    }

    fun pillBesideIslandPx(widthPx: Int, roomPx: Int, islandPx: Int, gapPx: Int, minPx: Int): Int {
        val wanted = max(minPx, widthPx - gapPx - islandPx)
        val fits = roomPx - gapPx - islandPx
        return max(min(wanted, fits), min(wanted, islandPx)).coerceAtLeast(1)
    }
}
