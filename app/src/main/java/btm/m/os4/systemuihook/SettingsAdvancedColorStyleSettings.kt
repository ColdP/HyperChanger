// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_SETTINGS_ADVANCED_COLOR_STYLE = "settings_advanced_color_style"

internal data class SettingsAdvancedColorStyleSettings(
    val enabled: Boolean = false,
)

internal fun readSettingsAdvancedColorStyleSettings(p: SharedPreferences) =
    SettingsAdvancedColorStyleSettings(
        enabled = p.getBoolean(KEY_SETTINGS_ADVANCED_COLOR_STYLE, false),
    )

internal class SettingsAdvancedColorStyleSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsAdvancedColorStyleSettings(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Preserve changes made while the Xposed service was disconnected.
        settings = readSettingsAdvancedColorStyleSettings(
            if (local.contains(KEY_SETTINGS_ADVANCED_COLOR_STYLE)) local else remote,
        )
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsAdvancedColorStyleSettings) -> SettingsAdvancedColorStyleSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SettingsAdvancedColorStyleSettings) {
        p.edit().putBoolean(KEY_SETTINGS_ADVANCED_COLOR_STYLE, value.enabled).apply()
    }
}
