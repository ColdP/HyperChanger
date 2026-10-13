// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED = "expanded_island_corner_radius_enabled"
internal const val KEY_EXPANDED_ISLAND_CORNER_RADIUS = "expanded_island_corner_radius"
internal const val KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED = "small_island_media_icon_corner_radius_enabled"
internal const val KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS = "small_island_media_icon_corner_radius"

internal const val EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT = -1
internal const val SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT = 0

data class DynamicIslandAppearanceSettings(
    val expandedCornerRadiusEnabled: Boolean = false,
    val expandedCornerRadius: Int = EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT,
    val smallMediaIconCornerRadiusEnabled: Boolean = false,
    val smallMediaIconCornerRadius: Int = SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT,
)

internal fun SharedPreferences.toDynamicIslandAppearanceSettings() = DynamicIslandAppearanceSettings(
    expandedCornerRadiusEnabled = getBoolean(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED, false),
    expandedCornerRadius = getInt(KEY_EXPANDED_ISLAND_CORNER_RADIUS, EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT).coerceIn(-1, 50),
    smallMediaIconCornerRadiusEnabled = getBoolean(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED, false),
    smallMediaIconCornerRadius = getInt(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS, SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT).coerceIn(-64, 96),
)

class DynamicIslandAppearanceSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = local.toDynamicIslandAppearanceSettings()
        private set

    fun reload() { settings = local.toDynamicIslandAppearanceSettings() }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = (if (remote.contains(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED)) remote else local)
            .toDynamicIslandAppearanceSettings()
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (DynamicIslandAppearanceSettings) -> DynamicIslandAppearanceSettings) {
        settings = transform(settings).coerceToRange()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED, settings.expandedCornerRadiusEnabled)
            .putInt(KEY_EXPANDED_ISLAND_CORNER_RADIUS, settings.expandedCornerRadius.coerceIn(-1, 50))
            .putBoolean(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED, settings.smallMediaIconCornerRadiusEnabled)
            .putInt(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS, settings.smallMediaIconCornerRadius.coerceIn(-64, 96))
            .apply()
    }

    private fun DynamicIslandAppearanceSettings.coerceToRange() = copy(
        expandedCornerRadius = expandedCornerRadius.coerceIn(-1, 50),
        smallMediaIconCornerRadius = smallMediaIconCornerRadius.coerceIn(-64, 96),
    )
}
