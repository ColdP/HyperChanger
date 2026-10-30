// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import kotlin.math.abs

/**
 * A pull down that folds a card back into the row of islands, told apart from a scroll of the
 * lock screen's list. Only a gesture that starts with the list at its top can become the pull;
 * one that scrolls first stays the list's until the finger lifts, even when it comes back up to
 * the top on the way (#12: reading back up a list opened out of the stack island folded it).
 *
 * Adapted from TakeKazeX's PR #15.
 */
internal class MiniPlayerCollapseGesture {
    var armed = false
        private set

    fun start(eligible: Boolean) { armed = eligible }

    /** True on the one move that turns the gesture into the pull. */
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
        /**
         * The list at its top: its own scroll no further than where the lock screen rests it.
         * HyperOS lays a stack opened out as a LIST below the clock with a positive scroll
         * before anyone has scrolled it. Unread either way, it counts as the top - the pull
         * goes on working as it did before the list was asked, on a build that names these
         * differently.
         */
        fun atListTop(scrollY: Int?, restingScrollY: Float?): Boolean =
            scrollY == null || restingScrollY == null || !restingScrollY.isFinite() ||
                scrollY <= restingScrollY.coerceAtLeast(0f) + 1f
    }
}
