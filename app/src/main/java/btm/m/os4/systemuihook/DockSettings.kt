// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_DOCK_ENABLED = "dock_enabled"
internal const val KEY_DOCK_ENTRY_ANIMATION = "dock_entry_animation"
internal const val KEY_DOCK_MATERIAL = "dock_material"
internal const val KEY_DOCK_SOLID_COLOR = "dock_solid_color"
internal const val KEY_DOCK_ADVANCED_COLOR = "dock_advanced_color"
internal const val KEY_DOCK_ADVANCED_OPACITY = "dock_advanced_opacity"
internal const val KEY_DOCK_ADVANCED_BLUR = "dock_advanced_blur"
internal const val KEY_DOCK_ADVANCED_HIGHLIGHT = "dock_advanced_highlight"
internal const val KEY_DOCK_GLASS_COLOR = "dock_glass_color"
internal const val KEY_DOCK_GLASS_OPACITY = "dock_glass_opacity"
internal const val KEY_DOCK_GLASS_BACKDROP_BLUR = "dock_glass_backdrop_blur"
internal const val KEY_DOCK_GLASS_BLUR = "dock_glass_blur"
internal const val KEY_DOCK_GLASS_SOFT_LIGHT = "dock_glass_soft_light"
internal const val KEY_DOCK_HEIGHT = "dock_height"
internal const val KEY_DOCK_MARGIN = "dock_margin"
internal const val KEY_DOCK_BOTTOM = "dock_bottom"
internal const val KEY_DOCK_RADIUS = "dock_radius"

data class DockSettings(
    val enabled: Boolean = false,
    val entryAnimation: Boolean = true,
    val material: Int = 1,
    val solidColor: Int = 0xCCFFFFFF.toInt(),
    val advancedColor: Int = 0xFFFFFFFF.toInt(),
    val advancedOpacity: Int = 14,
    val advancedBlur: Int = 40,
    val advancedHighlight: Boolean = true,
    val glassColor: Int = 0xFFFFFFFF.toInt(),
    val glassOpacity: Int = 85,
    val glassBackdropBlur: Int = 40,
    val glassBlur: Int = 30,
    val glassSoftLight: Int = 50,
    val height: Int = 150,
    val margin: Int = 25,
    val bottom: Int = 15,
    val radius: Int = 30,
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

    private fun read(prefs: SharedPreferences) = readDockSettings(prefs)

    private fun write(prefs: SharedPreferences, value: DockSettings) {
        prefs.edit()
            .putBoolean(KEY_DOCK_ENABLED, value.enabled)
            .putBoolean(KEY_DOCK_ENTRY_ANIMATION, value.entryAnimation)
            .putInt(KEY_DOCK_MATERIAL, value.material.coerceIn(0, 2))
            .putInt(KEY_DOCK_SOLID_COLOR, value.solidColor)
            .putInt(KEY_DOCK_ADVANCED_COLOR, value.advancedColor)
            .putInt(KEY_DOCK_ADVANCED_OPACITY, value.advancedOpacity.coerceIn(0, 100))
            .putInt(KEY_DOCK_ADVANCED_BLUR, value.advancedBlur.coerceIn(0, 100))
            .putBoolean(KEY_DOCK_ADVANCED_HIGHLIGHT, value.advancedHighlight)
            .putInt(KEY_DOCK_GLASS_COLOR, value.glassColor)
            .putInt(KEY_DOCK_GLASS_OPACITY, value.glassOpacity.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_BACKDROP_BLUR, value.glassBackdropBlur.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_BLUR, value.glassBlur.coerceIn(0, 100))
            .putInt(KEY_DOCK_GLASS_SOFT_LIGHT, value.glassSoftLight.coerceIn(0, 100))
            .putInt(KEY_DOCK_HEIGHT, value.height.coerceIn(40, 300))
            .putInt(KEY_DOCK_MARGIN, value.margin.coerceIn(0, 150))
            .putInt(KEY_DOCK_BOTTOM, value.bottom.coerceIn(0, 150))
            .putInt(KEY_DOCK_RADIUS, value.radius.coerceIn(0, 60))
            .apply()
    }
}

internal fun readDockSettings(prefs: SharedPreferences) = DockSettings(
        enabled = prefs.getBoolean(KEY_DOCK_ENABLED, false),
        entryAnimation = prefs.getBoolean(KEY_DOCK_ENTRY_ANIMATION, true),
        material = prefs.getInt(KEY_DOCK_MATERIAL, 1).coerceIn(0, 2),
        solidColor = prefs.getInt(KEY_DOCK_SOLID_COLOR, 0xCCFFFFFF.toInt()),
        advancedColor = prefs.getInt(KEY_DOCK_ADVANCED_COLOR, 0xFFFFFFFF.toInt()),
        advancedOpacity = prefs.getInt(KEY_DOCK_ADVANCED_OPACITY, 14).coerceIn(0, 100),
        advancedBlur = prefs.getInt(KEY_DOCK_ADVANCED_BLUR, 40).coerceIn(0, 100),
        advancedHighlight = prefs.getBoolean(KEY_DOCK_ADVANCED_HIGHLIGHT, true),
        glassColor = prefs.getInt(KEY_DOCK_GLASS_COLOR, 0xFFFFFFFF.toInt()),
        glassOpacity = prefs.getInt(KEY_DOCK_GLASS_OPACITY, 85).coerceIn(0, 100),
        glassBackdropBlur = prefs.getInt(KEY_DOCK_GLASS_BACKDROP_BLUR, 40).coerceIn(0, 100),
        glassBlur = prefs.getInt(KEY_DOCK_GLASS_BLUR, 30).coerceIn(0, 100),
        glassSoftLight = prefs.getInt(KEY_DOCK_GLASS_SOFT_LIGHT, 50).coerceIn(0, 100),
        height = prefs.getInt(KEY_DOCK_HEIGHT, 150).coerceIn(40, 300),
        margin = prefs.getInt(KEY_DOCK_MARGIN, 25).coerceIn(0, 150),
        bottom = prefs.getInt(KEY_DOCK_BOTTOM, 15).coerceIn(0, 150),
        radius = prefs.getInt(KEY_DOCK_RADIUS, 30).coerceIn(0, 60),
    )
