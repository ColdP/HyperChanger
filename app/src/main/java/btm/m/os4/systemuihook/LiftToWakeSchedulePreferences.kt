// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Schedule for the "raise to wake" gesture.
 *
 * The window is stored as minute-of-day so comparisons are a single int test and the picker can work
 * in minutes directly. A window whose end is before its start intentionally wraps over midnight.
 */
internal const val KEY_LIFT_TO_WAKE_ENABLED = "lift_to_wake_schedule_enabled"
internal const val KEY_LIFT_TO_WAKE_START = "lift_to_wake_schedule_start"
internal const val KEY_LIFT_TO_WAKE_END = "lift_to_wake_schedule_end"

internal const val MINUTES_PER_DAY = 24 * 60
internal const val DEFAULT_LIFT_TO_WAKE_START = 7 * 60
internal const val DEFAULT_LIFT_TO_WAKE_END = 23 * 60

internal data class LiftToWakeSchedulePreferences(
    val enabled: Boolean = false,
    val startMinutes: Int = DEFAULT_LIFT_TO_WAKE_START,
    val endMinutes: Int = DEFAULT_LIFT_TO_WAKE_END,
) {
    fun normalized() = copy(
        startMinutes = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinutes = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
    )

    /** True when [nowMinutes] is inside the window; handles windows that cross midnight. */
    fun isActiveAt(nowMinutes: Int): Boolean {
        if (!enabled) return false
        val now = nowMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (startMinutes == endMinutes) return true
        return if (startMinutes < endMinutes) {
            now in startMinutes until endMinutes
        } else {
            now >= startMinutes || now < endMinutes
        }
    }

    fun windowLabel(): String = "${formatScheduleMinute(startMinutes)} – ${formatScheduleMinute(endMinutes)}"
}

internal fun formatScheduleMinute(minutes: Int): String {
    val value = minutes.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(value / 60, value % 60)
}

internal fun readLiftToWakeSchedulePreferences(p: SharedPreferences) = LiftToWakeSchedulePreferences(
    enabled = p.getBoolean(KEY_LIFT_TO_WAKE_ENABLED, false),
    startMinutes = p.getInt(KEY_LIFT_TO_WAKE_START, DEFAULT_LIFT_TO_WAKE_START),
    endMinutes = p.getInt(KEY_LIFT_TO_WAKE_END, DEFAULT_LIFT_TO_WAKE_END),
).normalized()

internal class LiftToWakeSchedulePreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readLiftToWakeSchedulePreferences(local); private set

    init {
        // The entry point is intentionally hidden for now. Nothing in the UI can turn this on any
        // more, so an install carrying an earlier value must not keep the schedule armed in the
        // background. Re-enabling later only needs to drop this block.
        if (local.getBoolean(KEY_LIFT_TO_WAKE_ENABLED, false)) {
            local.edit().putBoolean(KEY_LIFT_TO_WAKE_ENABLED, false).apply()
            settings = readLiftToWakeSchedulePreferences(local)
        }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // The settings UI always persists locally first, and it may do so before the service binding
        // exists. Treat an existing local record as authoritative, otherwise the first remote sync
        // after the binding arrives would overwrite a change the user just made.
        settings = if (local.contains(KEY_LIFT_TO_WAKE_ENABLED)) {
            readLiftToWakeSchedulePreferences(local)
        } else {
            readLiftToWakeSchedulePreferences(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (LiftToWakeSchedulePreferences) -> LiftToWakeSchedulePreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: LiftToWakeSchedulePreferences) {
        p.edit()
            .putBoolean(KEY_LIFT_TO_WAKE_ENABLED, s.enabled)
            .putInt(KEY_LIFT_TO_WAKE_START, s.startMinutes)
            .putInt(KEY_LIFT_TO_WAKE_END, s.endMinutes)
            .apply()
    }
}
