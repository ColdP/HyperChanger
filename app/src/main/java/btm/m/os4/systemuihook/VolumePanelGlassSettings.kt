// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_VOLUME_PANEL_GLOBAL_GLASS = "volume_panel_use_global_soft_glass"

internal fun volumePanelUsesGlobalGlass(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, false)

internal data class VolumePanelGlassRuntimeState(
    val materialEnabled: Boolean,
    val useGlobal: Boolean,
) {
    val globalSelected: Boolean get() = materialEnabled && useGlobal
}

/** No remote preference calls from volume animation and View setter hooks. */
internal class VolumePanelGlassRuntimeSettings(private val preferences: SharedPreferences) {
    private fun read() = VolumePanelGlassRuntimeState(
        preferences.getBoolean("volume_panel_material_enabled", true),
        volumePanelUsesGlobalGlass(preferences),
    )
    @Volatile var state = read(); private set
    @Volatile var layout = VolumePanelLayoutPreferences.read(preferences); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("volume_panel_layout_")) layout = VolumePanelLayoutPreferences.read(preferences)
        if (key == null || key == KEY_VOLUME_PANEL_GLOBAL_GLASS ||
            key == "volume_panel_material_enabled") state = read()
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}

/** Kept separate from HookSettings and its serialization. */
internal class VolumePanelGlassSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var useGlobal = volumePanelUsesGlobalGlass(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        useGlobal = volumePanelUsesGlobalGlass(if (remote.contains(KEY_VOLUME_PANEL_GLOBAL_GLASS)) remote else local)
        local.edit().putBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, useGlobal).apply()
        remote.edit().putBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, useGlobal).apply()
    }

    fun update(service: XposedService?, enabled: Boolean) {
        useGlobal = enabled
        local.edit().putBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_VOLUME_PANEL_GLOBAL_GLASS, enabled)?.apply()
    }
}
