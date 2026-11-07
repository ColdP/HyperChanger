// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

data class GmsQuickShareIslandSettings(val enabled: Boolean = false)

internal class GmsQuickShareIslandSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = local.read()
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_GMS_QUICK_SHARE_ISLAND)) remote.read() else settings
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (GmsQuickShareIslandSettings) -> GmsQuickShareIslandSettings) {
        settings = transform(settings)
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    private fun SharedPreferences.read() = GmsQuickShareIslandSettings(getBoolean(KEY_GMS_QUICK_SHARE_ISLAND, false))
    private fun SharedPreferences.write(value: GmsQuickShareIslandSettings) {
        edit().putBoolean(KEY_GMS_QUICK_SHARE_ISLAND, value.enabled).apply()
    }
}

internal const val KEY_GMS_QUICK_SHARE_ISLAND = "gms_quick_share_island"

internal fun completeGmsQuickShareIslandPreset(values: JSONObject) {
    if (!values.has(KEY_GMS_QUICK_SHARE_ISLAND)) {
        values.put(KEY_GMS_QUICK_SHARE_ISLAND, JSONObject().put("type", "boolean").put("value", false))
    }
}
