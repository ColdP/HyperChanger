// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import kotlin.math.abs
import kotlin.math.sign

internal data class DateStatusLayout(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val parentWidth: Int,
    val extra: Int,
    val aod: Boolean,
)

/** Only compensate for the width we added, never a new OEM clock layout or AOD pose. */
internal object DateStatusLayoutPolicy {
    fun offset(previous: DateStatusLayout?, current: DateStatusLayout, shown: Float): Float? {
        if (previous == null || previous.aod || current.aod ||
            previous.top != current.top || previous.height != current.height ||
            previous.parentWidth != current.parentWidth ||
            abs((previous.width - previous.extra) - (current.width - current.extra)) > 1) return null
        val extraChange = current.extra - previous.extra
        val shift = previous.left - current.left
        if (extraChange == 0) return if (shift == 0) shown else null
        if (shift != 0 && (shift.sign != extraChange.sign || abs(shift) > abs(extraChange) + 1)) return null
        return shown + shift
    }
}
