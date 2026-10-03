// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP

const val KEY_IOS_NOTIFICATION_CENTER = "ios_notification_center"
const val KEY_IOS_NOTIFICATION_WALLPAPER = "ios_notification_wallpaper"
const val KEY_IOS_NOTIFICATION_HIDE_CLEAR = "ios_notification_hide_clear"

data class IosNotificationCenterSettings(
    val enabled: Boolean = false,
    // 0 = lock wallpaper, 1 = home wallpaper.
    val wallpaper: Int = 0,
    val hideClearButton: Boolean = false,
)

class IosNotificationCenterSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = read(local)
        private set

    fun syncRemote(service: XposedService) {
        // The app owns these settings; connecting must also upload offline edits.
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(service: XposedService?, transform: (IosNotificationCenterSettings) -> IosNotificationCenterSettings) {
        settings = transform(settings).let { it.copy(wallpaper = it.wallpaper.coerceIn(0, 1)) }
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun read(prefs: SharedPreferences) = IosNotificationCenterSettings(
        enabled = prefs.getBoolean(KEY_IOS_NOTIFICATION_CENTER, false),
        wallpaper = prefs.getInt(KEY_IOS_NOTIFICATION_WALLPAPER, 0).coerceIn(0, 1),
        hideClearButton = prefs.getBoolean(KEY_IOS_NOTIFICATION_HIDE_CLEAR, false),
    )

    private fun write(prefs: SharedPreferences, value: IosNotificationCenterSettings) {
        prefs.edit()
            .putBoolean(KEY_IOS_NOTIFICATION_CENTER, value.enabled)
            .putInt(KEY_IOS_NOTIFICATION_WALLPAPER, value.wallpaper)
            .putBoolean(KEY_IOS_NOTIFICATION_HIDE_CLEAR, value.hideClearButton)
            .apply()
    }
}
