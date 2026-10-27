// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_STATUS_BAR_SECONDS_MODE = "status_bar_seconds_mode"
internal const val KEY_STATUS_BAR_SECONDS_DURATION = "status_bar_seconds_duration_seconds"
internal const val STATUS_BAR_SECONDS_MODE_HIDDEN = 0
internal const val STATUS_BAR_SECONDS_MODE_ALWAYS = 1
internal const val STATUS_BAR_SECONDS_MODE_LONG_PRESS = 2
internal const val STATUS_BAR_SECONDS_DEFAULT_DURATION = 30

internal data class StatusBarSecondsSettings(
    val mode: Int = STATUS_BAR_SECONDS_MODE_HIDDEN,
    val durationSeconds: Int = STATUS_BAR_SECONDS_DEFAULT_DURATION,
)

internal fun readStatusBarSecondsSettings(preferences: SharedPreferences) = StatusBarSecondsSettings(
    mode = preferences.getInt(KEY_STATUS_BAR_SECONDS_MODE, STATUS_BAR_SECONDS_MODE_HIDDEN)
        .coerceIn(STATUS_BAR_SECONDS_MODE_HIDDEN, STATUS_BAR_SECONDS_MODE_LONG_PRESS),
    durationSeconds = preferences.getInt(KEY_STATUS_BAR_SECONDS_DURATION, STATUS_BAR_SECONDS_DEFAULT_DURATION)
        .coerceIn(30, 3600),
)

internal class StatusBarSecondsSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readStatusBarSecondsSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_STATUS_BAR_SECONDS_MODE) ||
            remote.contains(KEY_STATUS_BAR_SECONDS_DURATION)
        ) readStatusBarSecondsSettings(remote) else readStatusBarSecondsSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (StatusBarSecondsSettings) -> StatusBarSecondsSettings) {
        settings = transform(readStatusBarSecondsSettings(local)).let {
            it.copy(mode = it.mode.coerceIn(0, 2), durationSeconds = it.durationSeconds.coerceIn(30, 3600))
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putInt(KEY_STATUS_BAR_SECONDS_MODE, settings.mode)
            .putInt(KEY_STATUS_BAR_SECONDS_DURATION, settings.durationSeconds)
            .apply()
    }
}
