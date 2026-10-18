// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

const val KEY_IOS_NOTIFICATION_BOTTOM_OFFSET = "ios_notification_bottom_offset_dp"

fun readIosNotificationBottomOffset(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_IOS_NOTIFICATION_BOTTOM_OFFSET, 0).coerceIn(-30, 60)

/** Positive offsets move the notification resting edge upward; negative offsets move it down. */
class IosNotificationBottomOffsetSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var offsetDp: Int = readIosNotificationBottomOffset(local)
        private set

    fun syncRemote(service: XposedService) {
        offsetDp = readIosNotificationBottomOffset(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), offsetDp)
    }

    fun update(service: XposedService?, value: Int) {
        offsetDp = value.coerceIn(-30, 60)
        write(local, offsetDp)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, offsetDp) }
    }

    private fun write(preferences: SharedPreferences, value: Int) {
        preferences.edit().putInt(KEY_IOS_NOTIFICATION_BOTTOM_OFFSET, value).apply()
    }
}
