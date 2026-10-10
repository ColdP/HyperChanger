// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_CONTROL_CENTER_CARRIER_REMOVE_HD = "control_center_carrier_remove_hd"
internal const val KEY_CONTROL_CENTER_CARRIER_HD_MODE = "control_center_carrier_hd_mode"
internal const val KEY_CONTROL_CENTER_CARRIER_CUSTOM_ENABLED = "control_center_carrier_custom_enabled"
internal const val KEY_CONTROL_CENTER_CARRIER_CUSTOM_TEXT = "control_center_carrier_custom_text"
internal const val KEY_CONTROL_CENTER_CARRIER_HIDE_MODE = "control_center_carrier_hide_mode"

data class ControlCenterCarrierSettings(
    // 0: OEM HD, 1: hidden, 2: actual mobile network type.
    val hdMode: Int = 0,
    val customEnabled: Boolean = false,
    val customText: String = "",
    // 0: show, 1: SIM 1, 2: SIM 2, 3: non-data SIM, 4: data SIM, 5: all.
    val hideMode: Int = 0,
)

internal fun readControlCenterCarrierSettings(preferences: SharedPreferences) = ControlCenterCarrierSettings(
    hdMode = if (preferences.contains(KEY_CONTROL_CENTER_CARRIER_HD_MODE)) {
        preferences.getInt(KEY_CONTROL_CENTER_CARRIER_HD_MODE, 0).coerceIn(0, 2)
    } else if (preferences.getBoolean(KEY_CONTROL_CENTER_CARRIER_REMOVE_HD, false)) 1 else 0,
    customEnabled = preferences.getBoolean(KEY_CONTROL_CENTER_CARRIER_CUSTOM_ENABLED, false),
    customText = preferences.getString(KEY_CONTROL_CENTER_CARRIER_CUSTOM_TEXT, "").orEmpty().take(256),
    hideMode = preferences.getInt(KEY_CONTROL_CENTER_CARRIER_HIDE_MODE, 0).coerceIn(0, 5),
)

class ControlCenterCarrierSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readControlCenterCarrierSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterCarrierSettings(local)
        write(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (ControlCenterCarrierSettings) -> ControlCenterCarrierSettings) {
        settings = transform(settings).let {
            it.copy(
                hdMode = it.hdMode.coerceIn(0, 2),
                customText = it.customText.take(256).replace('\n', ' ').replace('\r', ' '),
                hideMode = it.hideMode.coerceIn(0, 5),
            )
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putInt(KEY_CONTROL_CENTER_CARRIER_HD_MODE, settings.hdMode)
            .remove(KEY_CONTROL_CENTER_CARRIER_REMOVE_HD)
            .putBoolean(KEY_CONTROL_CENTER_CARRIER_CUSTOM_ENABLED, settings.customEnabled)
            .putString(KEY_CONTROL_CENTER_CARRIER_CUSTOM_TEXT, settings.customText)
            .putInt(KEY_CONTROL_CENTER_CARRIER_HIDE_MODE, settings.hideMode)
            .apply()
    }
}

/** Slot numbers are physical SIM slots, not subscription IDs or display order. */
internal fun hideControlCenterCarrierSlot(mode: Int, slot: Int, dataSlot: Int): Boolean = when (mode) {
    1 -> slot == 0
    2 -> slot == 1
    3 -> dataSlot in 0..1 && slot != dataSlot
    4 -> dataSlot in 0..1 && slot == dataSlot
    5 -> true
    else -> false
}
