// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import kotlin.math.abs

/** Pull down recognizer used when a lockscreen notification card is returned to the capsule. */
internal class MiniPlayerCollapseGesture {
    var armed = false
        private set

    fun start(eligible: Boolean) { armed = eligible }

    fun move(dx: Float, dy: Float, slop: Float, atTop: Boolean): Boolean {
        if (!armed) return false
        if (!atTop || dy < -slop || abs(dx) > slop && abs(dx) >= abs(dy)) {
            armed = false
            return false
        }
        if (dy > slop && dy > abs(dx) * 1.2f) {
            armed = false
            return true
        }
        return false
    }

    fun reset() { armed = false }

    companion object {
        fun atListTop(scrollY: Int?, restingScrollY: Float?): Boolean =
            scrollY == null || restingScrollY == null || !restingScrollY.isFinite() ||
                scrollY <= restingScrollY.coerceAtLeast(0f) + 1f
    }
}
