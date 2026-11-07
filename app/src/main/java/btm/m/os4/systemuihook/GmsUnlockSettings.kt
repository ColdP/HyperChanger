// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Dedicated setting for the process-local CN GMS feature unlock Hook. */
data class GmsUnlockSettings(val enabled: Boolean = false)

internal class GmsUnlockSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: GmsUnlockSettings = local.read()
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_GMS_UNLOCK_FEATURES)) remote.read() else settings
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (GmsUnlockSettings) -> GmsUnlockSettings) {
        settings = transform(settings)
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    private fun SharedPreferences.read() = GmsUnlockSettings(
        enabled = getBoolean(KEY_GMS_UNLOCK_FEATURES, false),
    )

    private fun SharedPreferences.write(value: GmsUnlockSettings) {
        edit().putBoolean(KEY_GMS_UNLOCK_FEATURES, value.enabled).apply()
    }
}

internal const val KEY_GMS_UNLOCK_FEATURES = "gms_unlock_features"

internal fun completeGmsUnlockPreset(values: JSONObject) {
    if (!values.has(KEY_GMS_UNLOCK_FEATURES)) {
        values.put(KEY_GMS_UNLOCK_FEATURES, JSONObject().put("type", "boolean").put("value", false))
    }
}
