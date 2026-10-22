// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_STATUS_BAR_ICON_SPACING_PERCENT = "status_bar_icon_spacing_percent"
internal val STATUS_BAR_ICON_SPACING_RANGE = -100..100

internal fun readStatusBarIconSpacing(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_STATUS_BAR_ICON_SPACING_PERCENT, 0).coerceIn(STATUS_BAR_ICON_SPACING_RANGE)

/** Relative icon spacing; zero preserves the system's spacing independently of icon size. */
internal class StatusBarIconSpacingSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var spacingPercent: Int = readStatusBarIconSpacing(local)
        private set

    fun syncRemote(service: XposedService) {
        spacingPercent = readStatusBarIconSpacing(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, value: Int) {
        spacingPercent = value.coerceIn(STATUS_BAR_ICON_SPACING_RANGE)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().putInt(KEY_STATUS_BAR_ICON_SPACING_PERCENT, spacingPercent).apply()
    }
}
