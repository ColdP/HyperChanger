// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_ISLAND_ENABLED = "island_enabled"
internal const val KEY_ISLAND_WIDTH = "island_width"
internal const val KEY_SMALL_ISLAND_WIDTH = "small_island_width"
internal const val ISLAND_BIG_WIDTH_DEFAULT = 108
internal const val ISLAND_SMALL_WIDTH_DEFAULT = 34
internal val ISLAND_BIG_WIDTH_RANGE = 108..190
internal val ISLAND_SMALL_WIDTH_RANGE = 1..100

data class IslandWidthSettings(
    val enabled: Boolean = false,
    val bigMinWidth: Int = ISLAND_BIG_WIDTH_DEFAULT,
    val smallMinWidth: Int = ISLAND_SMALL_WIDTH_DEFAULT,
)

internal fun readIslandWidthSettings(preferences: SharedPreferences) = IslandWidthSettings(
    enabled = preferences.getBoolean(KEY_ISLAND_ENABLED, false),
    bigMinWidth = preferences.getInt(KEY_ISLAND_WIDTH, ISLAND_BIG_WIDTH_DEFAULT)
        .coerceIn(ISLAND_BIG_WIDTH_RANGE),
    smallMinWidth = preferences.getInt(KEY_SMALL_ISLAND_WIDTH, ISLAND_SMALL_WIDTH_DEFAULT)
        .coerceIn(ISLAND_SMALL_WIDTH_RANGE),
)

/** Stores island width controls independently of HookSettings and mirrors them to SystemUI. */
class IslandWidthSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readIslandWidthSettings(local)
        private set

    fun reload() {
        settings = readIslandWidthSettings(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (
            remote.contains(KEY_ISLAND_ENABLED) ||
            remote.contains(KEY_ISLAND_WIDTH) ||
            remote.contains(KEY_SMALL_ISLAND_WIDTH)
        ) readIslandWidthSettings(remote) else readIslandWidthSettings(local)
        write(remote)
        write(local)
    }

    fun update(service: XposedService?, transform: (IslandWidthSettings) -> IslandWidthSettings) {
        settings = transform(settings).let {
            it.copy(
                bigMinWidth = it.bigMinWidth.coerceIn(ISLAND_BIG_WIDTH_RANGE),
                smallMinWidth = it.smallMinWidth.coerceIn(ISLAND_SMALL_WIDTH_RANGE),
            )
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_ISLAND_ENABLED, settings.enabled)
            .putInt(KEY_ISLAND_WIDTH, settings.bigMinWidth)
            .putInt(KEY_SMALL_ISLAND_WIDTH, settings.smallMinWidth)
            .apply()
    }
}
