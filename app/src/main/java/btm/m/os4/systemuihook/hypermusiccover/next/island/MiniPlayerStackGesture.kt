// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import kotlin.math.abs

/** Downward browsing has its own direction, so reversing a pull never opens the cards. */
internal object MiniPlayerStackGesture {
    fun starts(dx: Float, dy: Float): Boolean = dy > 0f && dy >= abs(dx)

    fun commits(dy: Float, velocityY: Float, density: Float, cancelled: Boolean): Boolean =
        !cancelled && dy > 0f &&
            (dy >= 50f * density || dy >= 8f * density && velocityY > 1200f * density)

    fun collapseProgress(startProgress: Float, dy: Float, range: Float): Float =
        (startProgress - dy / range.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun nextKey(keys: List<String>, selected: String?): String? {
        if (keys.size < 2) return null
        val at = keys.indexOf(selected).coerceAtLeast(0)
        return keys[(at + 1) % keys.size]
    }
}
