// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED =
    "lockscreen_notification_bottom_offset_enabled"
internal const val KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET =
    "lockscreen_notification_bottom_offset_dp"

private const val LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MIN = -40
private const val LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MAX = 64

data class LockscreenNotificationBottomOffsetSettings(
    val enabled: Boolean = false,
    val offsetDp: Int = 0,
) {
    fun normalized() = copy(
        offsetDp = offsetDp.coerceIn(
            LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MIN,
            LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MAX,
        ),
    )
}

private fun SharedPreferences.toLockscreenNotificationBottomOffsetSettings() =
    LockscreenNotificationBottomOffsetSettings(
        enabled = getBoolean(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED, false),
        offsetDp = getInt(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET, 0).coerceIn(
            LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MIN,
            LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_MAX,
        ),
    )

class LockscreenNotificationBottomOffsetSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: LockscreenNotificationBottomOffsetSettings =
        local.toLockscreenNotificationBottomOffsetSettings()
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = (if (
            remote.contains(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED) ||
            remote.contains(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET)
        ) remote else local).toLockscreenNotificationBottomOffsetSettings().normalized()
        write(remote)
        write(local)
    }

    fun update(
        service: XposedService?,
        transform: (LockscreenNotificationBottomOffsetSettings) -> LockscreenNotificationBottomOffsetSettings,
    ) {
        settings = transform(settings).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        val value = settings.normalized()
        preferences.edit()
            .putBoolean(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED, value.enabled)
            .putInt(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET, value.offsetDp)
            .apply()
    }
}
