// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_ISLAND_SPACING_ENABLED = "island_spacing_enabled"
internal const val KEY_ISLAND_SPACING_OFFSET = "island_spacing_offset_dp"
internal const val ISLAND_SPACING_DEFAULT_OFFSET = 0
internal val ISLAND_SPACING_RANGE = -10..50

data class IslandSpacingSettings(
    val enabled: Boolean = false,
    val offsetDp: Int = ISLAND_SPACING_DEFAULT_OFFSET,
)

internal fun readIslandSpacingSettings(preferences: SharedPreferences) = IslandSpacingSettings(
    enabled = preferences.getBoolean(KEY_ISLAND_SPACING_ENABLED, false),
    offsetDp = preferences.getInt(KEY_ISLAND_SPACING_OFFSET, ISLAND_SPACING_DEFAULT_OFFSET)
        .coerceIn(ISLAND_SPACING_RANGE),
)

/** Stores this feature independently of HookSettings and mirrors edits to SystemUI. */
class IslandSpacingSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readIslandSpacingSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readIslandSpacingSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (IslandSpacingSettings) -> IslandSpacingSettings) {
        settings = transform(settings).let { it.copy(offsetDp = it.offsetDp.coerceIn(ISLAND_SPACING_RANGE)) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_ISLAND_SPACING_ENABLED, settings.enabled)
            .putInt(KEY_ISLAND_SPACING_OFFSET, settings.offsetDp)
            .apply()
    }
}
