// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** One visible 12dp gap at each custom card boundary; native parameter contents are untouched. */
internal class DeviceCardSpacingSession(private val info: View, private val detail: View) {
    private val infoMargins = (info.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin to it.bottomMargin }
    private val detailTop = (detail.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin

    fun matches(info: View, detail: View) = this.info === info && this.detail === detail

    fun enforce() {
        setMargins(info, 0, 0)
        setMargins(detail, (12 * detail.resources.displayMetrics.density).roundToInt(), null)
    }

    fun remove() {
        infoMargins?.let { (top, bottom) -> setMargins(info, top, bottom) }
        detailTop?.let { setMargins(detail, it, null) }
    }

    private fun setMargins(view: View, top: Int, bottom: Int?) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == top && (bottom == null || params.bottomMargin == bottom)) return
        params.topMargin = top
        bottom?.let { params.bottomMargin = it }
        view.layoutParams = params
    }
}
