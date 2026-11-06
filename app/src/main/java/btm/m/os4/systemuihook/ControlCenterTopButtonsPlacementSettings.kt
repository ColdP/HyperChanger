// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val CC_TOP_BUTTONS_PLACEMENT_PREFIX = "control_center_top_buttons_placement_"
internal const val KEY_CC_TOP_BUTTONS_AT_BOTTOM = CC_TOP_BUTTONS_PLACEMENT_PREFIX + "at_bottom"
internal const val KEY_CC_TOP_BUTTONS_BOTTOM_GAP = CC_TOP_BUTTONS_PLACEMENT_PREFIX + "gap_dp"

internal data class ControlCenterTopButtonsPlacementSettings(
    val atBottom: Boolean = false,
    val gapDp: Int = 0,
)

internal fun readControlCenterTopButtonsPlacement(p: SharedPreferences) = ControlCenterTopButtonsPlacementSettings(
    p.getBoolean(KEY_CC_TOP_BUTTONS_AT_BOTTOM, false),
    p.getInt(KEY_CC_TOP_BUTTONS_BOTTOM_GAP, 0).coerceIn(-48, 64),
)

internal fun controlCenterTopButtonsBottomGapPx(offsetDp: Int, density: Float): Int =
    kotlin.math.round((18 + offsetDp.coerceIn(-48, 64)) * density).toInt()

internal class ControlCenterTopButtonsPlacementSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readControlCenterTopButtonsPlacement(local); private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterTopButtonsPlacement(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, value: ControlCenterTopButtonsPlacementSettings) {
        settings = value.copy(gapDp = value.gapDp.coerceIn(-48, 64))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().putBoolean(KEY_CC_TOP_BUTTONS_AT_BOTTOM, settings.atBottom)
            .putInt(KEY_CC_TOP_BUTTONS_BOTTOM_GAP, settings.gapDp).apply()
    }
}

internal fun completeControlCenterTopButtonsPlacementPreset(values: JSONObject) {
    if (!values.has(KEY_CC_TOP_BUTTONS_AT_BOTTOM)) values.put(KEY_CC_TOP_BUTTONS_AT_BOTTOM,
        JSONObject().put("type", "boolean").put("value", false))
    if (!values.has(KEY_CC_TOP_BUTTONS_BOTTOM_GAP)) values.put(KEY_CC_TOP_BUTTONS_BOTTOM_GAP,
        JSONObject().put("type", "int").put("value", 0))
}
