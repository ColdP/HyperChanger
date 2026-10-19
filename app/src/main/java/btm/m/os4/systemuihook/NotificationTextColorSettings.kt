// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HEADS_UP_TEXT_COLOR = "notification_heads_up_text_color_mode"
internal const val KEY_NOTIFICATION_TEXT_COLOR = "notification_text_color_mode"

internal data class NotificationTextColorSettings(val headsUp: Int = 0, val notification: Int = 0)

internal fun readNotificationTextColorSettings(p: SharedPreferences) = NotificationTextColorSettings(
    p.getInt(KEY_HEADS_UP_TEXT_COLOR, 0).coerceIn(0, 3),
    p.getInt(KEY_NOTIFICATION_TEXT_COLOR, 0).coerceIn(0, 3),
)

internal object NotificationTextColorPreferences {
    fun write(p: SharedPreferences, value: NotificationTextColorSettings) {
        p.edit().putInt(KEY_HEADS_UP_TEXT_COLOR, value.headsUp.coerceIn(0, 3))
            .putInt(KEY_NOTIFICATION_TEXT_COLOR, value.notification.coerceIn(0, 3)).apply()
    }

    fun sync(local: SharedPreferences, remote: SharedPreferences): NotificationTextColorSettings {
        val localValue = readNotificationTextColorSettings(local)
        val remoteValue = readNotificationTextColorSettings(remote)
        return NotificationTextColorSettings(
            if (remote.contains(KEY_HEADS_UP_TEXT_COLOR)) remoteValue.headsUp else localValue.headsUp,
            if (remote.contains(KEY_NOTIFICATION_TEXT_COLOR)) remoteValue.notification else localValue.notification,
        ).also { write(local, it); write(remote, it) }
    }
}

/** Isolated from HookSettings and its serialization. */
internal class NotificationTextColorSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readNotificationTextColorSettings(local); private set
    fun syncRemote(service: XposedService) {
        settings = NotificationTextColorPreferences.sync(local, service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }
    fun update(service: XposedService?, value: NotificationTextColorSettings) {
        NotificationTextColorPreferences.write(local, value)
        settings = readNotificationTextColorSettings(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { NotificationTextColorPreferences.write(it, settings) }
    }
}

/** Null means preserve the system's ColorStateList, including disabled/pressed states. */
internal fun notificationTextColor(mode: Int, headsUp: Boolean, systemNight: Boolean, darkIntensity: Float): Int? =
    when (mode) {
        1 -> 0xE8FFFFFF.toInt()
        2 -> 0xE8000000.toInt()
        3 -> if (if (headsUp) darkIntensity >= .5f else systemNight) 0xE8000000.toInt() else 0xE8FFFFFF.toInt()
        else -> null
    }
