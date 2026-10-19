// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** Direction and distance policy from HyperLight, isolated from Android event dispatch. */
internal class RearScreenLeftSwipeGesture {
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    var captured = false
        private set
    var progress = 0f
        private set

    fun begin(x: Float, y: Float, width: Int) {
        reset()
        startX = x
        startY = y
        eligible = width > 0 && x > width * .6f
    }

    fun move(x: Float, y: Float, density: Float): Boolean {
        if (!eligible || density <= 0f) return captured
        val dx = x - startX
        val dy = y - startY
        if (!captured) {
            // Once a different direction wins, do not steal an already-running system gesture.
            if ((kotlin.math.abs(dy) > 10f * density && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) || dx > 10f * density) {
                eligible = false
                return false
            }
            captured = dx < -10f * density && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
        }
        if (captured) progress = (-dx / (150f * density)).coerceIn(0f, 1f)
        return captured
    }

    fun finish(): Boolean {
        val launch = captured && progress >= .75f
        reset()
        return launch
    }

    fun reset() {
        eligible = false
        captured = false
        progress = 0f
    }
}
