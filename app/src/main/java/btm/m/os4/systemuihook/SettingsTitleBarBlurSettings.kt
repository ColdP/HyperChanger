// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_SETTINGS_TITLE_BAR_BLUR = "settings_title_bar_gradient_blur"

internal data class SettingsTitleBarBlurSettings(val enabled: Boolean = false)

internal fun readSettingsTitleBarBlurSettings(p: SharedPreferences) =
    SettingsTitleBarBlurSettings(p.getBoolean(KEY_SETTINGS_TITLE_BAR_BLUR, false))

internal class SettingsTitleBarBlurSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsTitleBarBlurSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSettingsTitleBarBlurSettings(if (local.contains(KEY_SETTINGS_TITLE_BAR_BLUR)) local else remote)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsTitleBarBlurSettings) -> SettingsTitleBarBlurSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SettingsTitleBarBlurSettings) {
        p.edit().putBoolean(KEY_SETTINGS_TITLE_BAR_BLUR, value.enabled).apply()
    }
}

internal fun completeSettingsTitleBarBlurPreset(values: JSONObject) {
    if (!values.has(KEY_SETTINGS_TITLE_BAR_BLUR)) {
        values.put(KEY_SETTINGS_TITLE_BAR_BLUR, JSONObject().put("type", "boolean").put("value", false))
    }
}
