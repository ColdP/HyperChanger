// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_REAR_WALLPAPER_LIMIT_ENABLED = "rear_wallpaper_limit_enabled"
internal const val KEY_REAR_WALLPAPER_LIMIT_MODE = "rear_wallpaper_limit_mode"
internal const val KEY_REAR_WALLPAPER_LIMIT_CUSTOM = "rear_wallpaper_limit_custom"
internal const val REAR_WALLPAPER_LIMIT_CUSTOM_MODE = 4

/** Independent of HookSettings and its serialization. Disabled means the stock limit. */
data class RearScreenWallpaperLimitSettings(
    val enabled: Boolean = false,
    val mode: Int = 0,
    val custom: Int = 50,
) {
    val limit: Int
        get() = if (!enabled) 15 else when (mode) {
            0 -> 50
            1 -> 60
            2 -> 80
            3 -> 100
            else -> custom.coerceIn(20, 200)
        }

    internal fun normalized() = copy(
        mode = mode.coerceIn(0, REAR_WALLPAPER_LIMIT_CUSTOM_MODE),
        custom = custom.coerceIn(20, 200),
    )
}

class RearScreenWallpaperLimitSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = local.readRearScreenWallpaperLimitSettings()
        private set

    fun reload() {
        settings = local.readRearScreenWallpaperLimitSettings()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Merge individual keys so a partial remote preference group cannot reset local values.
        val remoteSettings = remote.readRearScreenWallpaperLimitSettings()
        settings = RearScreenWallpaperLimitSettings(
            enabled = if (remote.contains(KEY_REAR_WALLPAPER_LIMIT_ENABLED)) remoteSettings.enabled else settings.enabled,
            mode = if (remote.contains(KEY_REAR_WALLPAPER_LIMIT_MODE)) remoteSettings.mode else settings.mode,
            custom = if (remote.contains(KEY_REAR_WALLPAPER_LIMIT_CUSTOM)) remoteSettings.custom else settings.custom,
        ).normalized()
        local.writeRearScreenWallpaperLimitSettings(settings)
        remote.writeRearScreenWallpaperLimitSettings(settings)
    }

    fun update(service: XposedService?, transform: (RearScreenWallpaperLimitSettings) -> RearScreenWallpaperLimitSettings) {
        settings = transform(settings).normalized()
        local.writeRearScreenWallpaperLimitSettings(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.writeRearScreenWallpaperLimitSettings(settings)
    }
}

internal fun SharedPreferences.readRearScreenWallpaperLimitSettings() = RearScreenWallpaperLimitSettings(
    enabled = getBoolean(KEY_REAR_WALLPAPER_LIMIT_ENABLED, false),
    mode = getInt(KEY_REAR_WALLPAPER_LIMIT_MODE, 0),
    custom = getInt(KEY_REAR_WALLPAPER_LIMIT_CUSTOM, 50),
).normalized()

private fun SharedPreferences.writeRearScreenWallpaperLimitSettings(value: RearScreenWallpaperLimitSettings) {
    edit().putBoolean(KEY_REAR_WALLPAPER_LIMIT_ENABLED, value.enabled)
        .putInt(KEY_REAR_WALLPAPER_LIMIT_MODE, value.mode)
        .putInt(KEY_REAR_WALLPAPER_LIMIT_CUSTOM, value.custom).apply()
}
