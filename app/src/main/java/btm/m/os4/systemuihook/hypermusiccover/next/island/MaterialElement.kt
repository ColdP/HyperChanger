// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook.hypermusiccover.next.island
import android.content.Context
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * The element a pill or a disc wears the card's material on, keeping the outline it is given.
 *
 * The glass draws its rim along the element's own outline. NotificationUtil.applyElementViewBlend
 * starts with setRoundRect, which puts the notification card's corner on whatever view it is
 * handed - 24dp, 72px here - and the full-screen AOD's dim runs it on these views every frame
 * (MiniPlayerRuntime.dimView), with nothing putting ours back when the wake settles at the
 * card's own look. A 72px corner on a view whose frame is cut round at 81px has its rim cut off
 * toward the diagonals: thinner there, and the cut is the frame's outline clip, jagged (#7, #21).
 * So the outline is [shape], whoever sets it.
 */
internal class MaterialElement(context: Context) : ImageView(context) {
    /** The only outline this element takes; null lets any be set, as a plain ImageView. */
    var shape: ViewOutlineProvider? = null
        set(value) {
            field = value
            if (value != null) {
                super.setOutlineProvider(value)
                invalidateOutline()
            }
        }

    override fun setOutlineProvider(provider: ViewOutlineProvider?) {
        super.setOutlineProvider(shape ?: provider)
    }
}
