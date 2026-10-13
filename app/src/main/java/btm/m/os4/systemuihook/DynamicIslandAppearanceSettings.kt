// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlin.math.roundToInt

internal const val KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED = "expanded_island_corner_radius_enabled"
internal const val KEY_EXPANDED_ISLAND_CORNER_RADIUS = "expanded_island_corner_radius"
private const val KEY_EXPANDED_ISLAND_CORNER_RADIUS_OFFSET_DP = "expanded_island_corner_radius_offset_dp"
internal const val KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED = "small_island_media_icon_corner_radius_enabled"
internal const val KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS = "small_island_media_icon_corner_radius"

internal const val EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT = 0f
internal const val EXPANDED_ISLAND_CORNER_RADIUS_MIN = -10f
internal const val EXPANDED_ISLAND_CORNER_RADIUS_MAX = 20f
internal const val SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT = 0

data class DynamicIslandAppearanceSettings(
    val expandedCornerRadiusEnabled: Boolean = false,
    val expandedCornerRadius: Float = EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT,
    val smallMediaIconCornerRadiusEnabled: Boolean = false,
    val smallMediaIconCornerRadius: Int = SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT,
)

private fun Float.normalizeExpandedIslandCornerRadius(): Float =
    if (isFinite()) {
        (coerceIn(EXPANDED_ISLAND_CORNER_RADIUS_MIN, EXPANDED_ISLAND_CORNER_RADIUS_MAX) * 100f)
            .roundToInt() / 100f
    } else EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT

internal fun SharedPreferences.readExpandedIslandCornerRadius(): Float {
    // A separate float key preserves old integer preferences without type errors.
    // Only the legacy integer -1 is a sentinel; the new -1.00 is a valid offset.
    val value = if (contains(KEY_EXPANDED_ISLAND_CORNER_RADIUS_OFFSET_DP)) {
        getFloat(KEY_EXPANDED_ISLAND_CORNER_RADIUS_OFFSET_DP, EXPANDED_ISLAND_CORNER_RADIUS_DEFAULT)
    } else {
        getInt(KEY_EXPANDED_ISLAND_CORNER_RADIUS, 0).let { if (it == -1) 0f else it.toFloat() }
    }
    return value.normalizeExpandedIslandCornerRadius()
}

internal fun SharedPreferences.toDynamicIslandAppearanceSettings() = DynamicIslandAppearanceSettings(
    expandedCornerRadiusEnabled = getBoolean(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED, false),
    expandedCornerRadius = readExpandedIslandCornerRadius(),
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
            .putFloat(KEY_EXPANDED_ISLAND_CORNER_RADIUS_OFFSET_DP, settings.expandedCornerRadius.normalizeExpandedIslandCornerRadius())
            .remove(KEY_EXPANDED_ISLAND_CORNER_RADIUS)
            .putBoolean(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED, settings.smallMediaIconCornerRadiusEnabled)
            .putInt(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS, settings.smallMediaIconCornerRadius.coerceIn(-64, 96))
            .apply()
    }

    private fun DynamicIslandAppearanceSettings.coerceToRange() = copy(
        expandedCornerRadius = expandedCornerRadius.normalizeExpandedIslandCornerRadius(),
        smallMediaIconCornerRadius = smallMediaIconCornerRadius.coerceIn(-64, 96),
    )
}
