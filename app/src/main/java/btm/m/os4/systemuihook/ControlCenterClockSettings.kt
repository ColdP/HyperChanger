// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Locale

internal const val KEY_CONTROL_CENTER_CLOCK_ENABLED = "control_center_clock_enabled"
internal const val KEY_CONTROL_CENTER_CLOCK_FORMAT = "control_center_clock_format"
internal const val KEY_CONTROL_CENTER_CLOCK_SIZE = "control_center_clock_size_sp"
internal const val KEY_CONTROL_CENTER_CLOCK_GAP = "control_center_clock_date_gap_dp"
internal const val KEY_CONTROL_CENTER_CLOCK_RED_ONE = "control_center_clock_red_one"
internal const val CONTROL_CENTER_CLOCK_DEFAULT_FORMAT = "HH:mm"

data class ControlCenterClockSettings(
    val enabled: Boolean = false,
    val format: String = CONTROL_CENTER_CLOCK_DEFAULT_FORMAT,
    val sizeSp: Float = 48f,
    val gapDp: Float = 8f,
    val redOne: Boolean = false,
)

internal fun isValidControlCenterClockFormat(pattern: String): Boolean =
    pattern.isNotBlank() && pattern.length <= 128 && '\n' !in pattern && '\r' !in pattern &&
        runCatching { SimpleDateFormat(pattern, Locale.getDefault()) }.isSuccess

internal fun normalizeControlCenterClockSettings(settings: ControlCenterClockSettings) = settings.copy(
    format = settings.format.takeIf(::isValidControlCenterClockFormat) ?: CONTROL_CENTER_CLOCK_DEFAULT_FORMAT,
    sizeSp = settings.sizeSp.takeIf { it.isFinite() }?.coerceIn(8f, 64f) ?: 48f,
    gapDp = settings.gapDp.takeIf { it.isFinite() }?.coerceIn(-20f, 40f) ?: 8f,
)

internal fun readControlCenterClockSettings(preferences: SharedPreferences) = normalizeControlCenterClockSettings(
    ControlCenterClockSettings(
        enabled = preferences.getBoolean(KEY_CONTROL_CENTER_CLOCK_ENABLED, false),
        format = preferences.getString(KEY_CONTROL_CENTER_CLOCK_FORMAT, CONTROL_CENTER_CLOCK_DEFAULT_FORMAT).orEmpty(),
        sizeSp = preferences.getFloat(KEY_CONTROL_CENTER_CLOCK_SIZE, 48f),
        gapDp = preferences.getFloat(KEY_CONTROL_CENTER_CLOCK_GAP, 8f),
        redOne = preferences.getBoolean(KEY_CONTROL_CENTER_CLOCK_RED_ONE, false),
    ),
)

/** Kept outside HookSettings, including offline edits and remote synchronization. */
class ControlCenterClockSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readControlCenterClockSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterClockSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (ControlCenterClockSettings) -> ControlCenterClockSettings) {
        settings = normalizeControlCenterClockSettings(transform(settings))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_CONTROL_CENTER_CLOCK_ENABLED, settings.enabled)
            .putString(KEY_CONTROL_CENTER_CLOCK_FORMAT, settings.format)
            .putFloat(KEY_CONTROL_CENTER_CLOCK_SIZE, settings.sizeSp)
            .putFloat(KEY_CONTROL_CENTER_CLOCK_GAP, settings.gapDp)
            .putBoolean(KEY_CONTROL_CENTER_CLOCK_RED_ONE, settings.redOne)
            .apply()
    }
}
