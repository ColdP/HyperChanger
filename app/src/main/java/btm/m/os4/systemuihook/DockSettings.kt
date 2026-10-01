// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_DOCK_ENABLED = "dock_enabled"
internal const val KEY_DOCK_GLASS_OPACITY = "dock_glass_opacity"
internal const val KEY_DOCK_GLASS_BACKDROP_BLUR = "dock_glass_backdrop_blur"
internal const val KEY_DOCK_GLASS_BLUR = "dock_glass_blur"
internal const val KEY_DOCK_GLASS_SOFT_LIGHT = "dock_glass_soft_light"

data class DockSettings(
    val enabled: Boolean = false,
    val glassOpacity: Int = 85,
    val glassBackdropBlur: Int = 40,
    val glassBlur: Int = 30,
    val glassSoftLight: Int = 50,
)

class DockSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings: DockSettings = read(local)
        private set

    fun reload() {
        settings = read(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_DOCK_ENABLED)) read(remote) else settings
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (DockSettings) -> DockSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun read(prefs: SharedPreferences) = DockSettings(
        enabled = prefs.getBoolean(KEY_DOCK_ENABLED, false),
        glassOpacity = prefs.getInt(KEY_DOCK_GLASS_OPACITY, 85).coerceIn(0, 100),
        glassBackdropBlur = prefs.getInt(KEY_DOCK_GLASS_BACKDROP_BLUR, 40).coerceIn(0, 100),
        glassBlur = prefs.getInt(KEY_DOCK_GLASS_BLUR, 30).coerceIn(0, 100),
        glassSoftLight = prefs.getInt(KEY_DOCK_GLASS_SOFT_LIGHT, 50).coerceIn(0, 100),
    )

    private fun write(prefs: SharedPreferences, value: DockSettings) {
        prefs.edit()
            .putBoolean(KEY_DOCK_ENABLED, value.enabled)
            .putInt(KEY_DOCK_GLASS_OPACITY, value.glassOpacity.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_BACKDROP_BLUR, value.glassBackdropBlur.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_BLUR, value.glassBlur.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_SOFT_LIGHT, value.glassSoftLight.coerceIn(0, 100))
            .apply()
    }
}
