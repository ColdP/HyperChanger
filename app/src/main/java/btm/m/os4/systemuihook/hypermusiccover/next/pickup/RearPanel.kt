// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.RemoteViews

/**
 * The back screen's panel, and the unit a card drawn on it is laid out in.
 *
 * The back screen's app does not scale a card: the widget's frame is the whole panel
 * (`7f0a0090` in `smart_assistant_panel`, match_parent both ways) and a `miui.rear.rv` card is
 * applied straight into it (`q2.o.r`). A card written in dp is therefore as tall on every panel,
 * and the panels are not the same: the 17 Pro Max's (P2) is 976x596 at 450dpi, about 212dp, and
 * the 17 Pro's (Q200) is 904x572, about 203dp - a 213dp card loses its last 10dp there.
 *
 * Their own MAML cards are drawn on a 336x213 design and scaled to the panel whole: the generic
 * templates (`template_layout`, `template_layout3`) by `#view_height / 213`, the ride-hailing one
 * (`car_hailing`) by `#view_width / 336`. [byHeight] and [byWidth] are those two units, in pixels,
 * and the cards here set every size through them in pixels too - so the system's font size does
 * not reach them either, as it does not reach theirs.
 */
internal class RearPanel private constructor(
    /** The panel's long side, in pixels. */
    val width: Int,
    /** The panel's short side, in pixels. */
    val height: Int,
) {
    /** One unit of a 213-tall design, the generic templates' scale. */
    fun byHeight(): Float = height / 213f

    /** One unit of a 336-wide design, the ride-hailing template's scale. */
    fun byWidth(): Float = width / 336f

    companion object {
        /** The display the panel's launcher runs on (`displayId=1` in `dumpsys activity top`). */
        const val DISPLAY = 1

        /**
         * The panel, measured where the card is going rather than where it is built: the cards are
         * built in SystemUI, whose own metrics are the front screen's. Falls back to this process's
         * screen only so the arithmetic has something to work with - nothing posts a rear card on a
         * phone without the second display.
         */
        fun of(c: Context): RearPanel {
            val display = c.getSystemService(DisplayManager::class.java)?.getDisplay(DISPLAY)
            val m = if (display == null) c.resources.displayMetrics
            else DisplayMetrics().also { display.getRealMetrics(it) }
            return RearPanel(maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels))
        }
    }
}

/** [RemoteViews.setViewLayoutWidth] in pixels. */
internal fun RemoteViews.width(id: Int, px: Float) =
    setViewLayoutWidth(id, px, TypedValue.COMPLEX_UNIT_PX)

/** [RemoteViews.setViewLayoutHeight] in pixels. */
internal fun RemoteViews.height(id: Int, px: Float) =
    setViewLayoutHeight(id, px, TypedValue.COMPLEX_UNIT_PX)

/** One margin of [id] ([type] is a `RemoteViews.MARGIN_*`), in pixels. */
internal fun RemoteViews.margin(id: Int, type: Int, px: Float) =
    setViewLayoutMargin(id, type, px, TypedValue.COMPLEX_UNIT_PX)

/** [RemoteViews.setTextViewTextSize] in pixels. */
internal fun RemoteViews.textPx(id: Int, px: Float) =
    setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, px)
