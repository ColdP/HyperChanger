// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_ISLAND_SMOOTHING_ENABLED = "island_collapsed_smoothing_enabled"
internal const val KEY_ISLAND_SMOOTHING_PERCENT = "island_collapsed_smoothing_percent"
internal const val ISLAND_SMOOTHING_DEFAULT_PERCENT = 80

data class IslandSmoothingSettings(val enabled: Boolean = false, val percent: Int = ISLAND_SMOOTHING_DEFAULT_PERCENT)

internal fun readIslandSmoothingSettings(preferences: SharedPreferences) = IslandSmoothingSettings(
    enabled = preferences.getBoolean(KEY_ISLAND_SMOOTHING_ENABLED, false),
    percent = preferences.getInt(KEY_ISLAND_SMOOTHING_PERCENT, ISLAND_SMOOTHING_DEFAULT_PERCENT).coerceIn(0, 100),
)

/** Independently stores local edits and synchronizes them with the SystemUI process. */
class IslandSmoothingSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readIslandSmoothingSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readIslandSmoothingSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (IslandSmoothingSettings) -> IslandSmoothingSettings) {
        settings = transform(settings).let { it.copy(percent = it.percent.coerceIn(0, 100)) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_ISLAND_SMOOTHING_ENABLED, settings.enabled)
            .putInt(KEY_ISLAND_SMOOTHING_PERCENT, settings.percent)
            .apply()
    }
}
