// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP

const val KEY_IOS_NOTIFICATION_CENTER = "ios_notification_center"
const val KEY_IOS_NOTIFICATION_WALLPAPER = "ios_notification_wallpaper"
const val KEY_IOS_NOTIFICATION_HIDE_CLEAR = "ios_notification_hide_clear"

// Preserve the stored value 2 for no wallpaper. Retired home wallpaper (1) and
// unknown values fall back to the lock wallpaper (0), independently of UI indices.
fun normalizeIosNotificationWallpaper(value: Int): Int = if (value == 2) 2 else 0

data class IosNotificationCenterSettings(
    val enabled: Boolean = false,
    // 0 = lock wallpaper, 2 = keep the system background; 1 is retired.
    val wallpaper: Int = 0,
    val hideClearButton: Boolean = false,
)

class IosNotificationCenterSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = read(local)
        private set

    init {
        if (local.getInt(KEY_IOS_NOTIFICATION_WALLPAPER, 0) != settings.wallpaper) {
            local.edit().putInt(KEY_IOS_NOTIFICATION_WALLPAPER, settings.wallpaper).apply()
        }
    }

    fun syncRemote(service: XposedService) {
        // The app owns these settings; connecting must also upload offline edits.
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(service: XposedService?, transform: (IosNotificationCenterSettings) -> IosNotificationCenterSettings) {
        settings = transform(settings).let { it.copy(wallpaper = normalizeIosNotificationWallpaper(it.wallpaper)) }
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun read(prefs: SharedPreferences) = IosNotificationCenterSettings(
        enabled = prefs.getBoolean(KEY_IOS_NOTIFICATION_CENTER, false),
        wallpaper = normalizeIosNotificationWallpaper(prefs.getInt(KEY_IOS_NOTIFICATION_WALLPAPER, 0)),
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
