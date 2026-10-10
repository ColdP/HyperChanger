// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_CONTROL_CENTER_HEADER_HEIGHT_OFFSET = "control_center_header_height_offset_dp"

internal fun readControlCenterHeaderHeightOffset(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_CONTROL_CENTER_HEADER_HEIGHT_OFFSET, 0).coerceIn(-30, 30)

/** An additive dp offset, stored independently of HookSettings and its serialization. */
class ControlCenterHeaderHeightSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var offsetDp: Int = readControlCenterHeaderHeightOffset(local)
        private set

    fun syncRemote(service: XposedService) {
        // Local preferences own the value so reconnecting also uploads offline edits.
        offsetDp = readControlCenterHeaderHeightOffset(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), offsetDp)
    }

    fun update(service: XposedService?, value: Int) {
        offsetDp = value.coerceIn(-30, 30)
        write(local, offsetDp)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, offsetDp) }
    }

    private fun write(preferences: SharedPreferences, value: Int) {
        preferences.edit().putInt(KEY_CONTROL_CENTER_HEADER_HEIGHT_OFFSET, value).apply()
    }
}
