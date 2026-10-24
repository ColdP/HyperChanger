// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

const val KEY_IOS_NOTIFICATION_SHORTCUTS = "ios_notification_shortcuts"
const val KEY_IOS_NOTIFICATION_SHORTCUTS_BOTTOM_OFFSET = "ios_notification_shortcuts_bottom_offset_dp"

class IosNotificationShortcutsSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var enabled: Boolean = local.getBoolean(KEY_IOS_NOTIFICATION_SHORTCUTS, false)
        private set
    var bottomOffsetDp: Int = readIosNotificationShortcutsBottomOffset(local)
        private set

    fun syncRemote(service: XposedService) {
        enabled = local.getBoolean(KEY_IOS_NOTIFICATION_SHORTCUTS, false)
        bottomOffsetDp = readIosNotificationShortcutsBottomOffset(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), enabled, bottomOffsetDp)
    }

    fun updateEnabled(service: XposedService?, value: Boolean) {
        enabled = value
        write(local, enabled, bottomOffsetDp)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, enabled, bottomOffsetDp) }
    }

    fun updateBottomOffset(service: XposedService?, value: Int) {
        bottomOffsetDp = value.coerceIn(-40, 120)
        write(local, enabled, bottomOffsetDp)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, enabled, bottomOffsetDp) }
    }

    private fun write(prefs: SharedPreferences, enabled: Boolean, offset: Int) {
        prefs.edit()
            .putBoolean(KEY_IOS_NOTIFICATION_SHORTCUTS, enabled)
            .putInt(KEY_IOS_NOTIFICATION_SHORTCUTS_BOTTOM_OFFSET, offset)
            .apply()
    }
}

fun readIosNotificationShortcutsBottomOffset(prefs: SharedPreferences): Int =
    prefs.getInt(KEY_IOS_NOTIFICATION_SHORTCUTS_BOTTOM_OFFSET, 0).coerceIn(-40, 120)
