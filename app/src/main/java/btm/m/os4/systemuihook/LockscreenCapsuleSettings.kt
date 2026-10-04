// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE = 0
const val LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE = 1
const val LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC = 2

/** Settings for the lockscreen capsule, kept separate from HookSettings. */
data class LockscreenCapsuleSettings(
    val enabled: Boolean = false,
    val lyricsEnabled: Boolean = false,
    val mediaNotificationMode: Int = LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
    val backgroundMode: Int = 0,
    val width: Float = 240f,
    /** Radius unit; the rendered capsule height is twice this value. */
    val height: Float = 36f,
    val artworkCornerRadius: Float = 12f,
    val pureColor: Int = 0x73000000,
    val advancedMaterialColor: Int = 0xFFFFFFFF.toInt(),
    val advancedMaterialOpacity: Int = 14,
    val advancedMaterialBlurRadius: Int = 80,
    val advancedMaterialHighlight: Boolean = false,
    val softGlassColor: Int = 0xFFFFFFFF.toInt(),
    val softGlassOpacity: Int = 10,
    val softGlassBackdropBlurRadius: Int = 80,
    val softGlassBlurRadius: Int = 36,
    val softGlassLuminance: Float = 0.14f,
)

class LockscreenCapsuleSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: LockscreenCapsuleSettings = local.read()
        private set

    fun reload() {
        settings = local.read()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteSettings = remote.read()
        settings = if (remote.contains(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED)) remoteSettings else settings
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (LockscreenCapsuleSettings) -> LockscreenCapsuleSettings) {
        settings = transform(settings).normalized()
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    private fun SharedPreferences.read() = LockscreenCapsuleSettings(
        enabled = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false),
        lyricsEnabled = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, false),
        mediaNotificationMode = when {
            contains(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE) -> getInt(
                KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
                LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
            ).coerceIn(LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE, LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC)
            getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION, false) ->
                LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE
            else -> LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
        },
        backgroundMode = getInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, 0).coerceIn(0, 3),
        width = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, 240f).coerceIn(160f, 360f),
        height = readMiniPlayerHeightRadius(),
        artworkCornerRadius = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS, 12f)
            .coerceIn(0f, 60f),
        pureColor = getInt(KEY_MINI_PLAYER_PURE_COLOR, 0x73000000),
        advancedMaterialColor = getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR, 0xFFFFFFFF.toInt()),
        advancedMaterialOpacity = getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY, 14).coerceIn(0, 100),
        advancedMaterialBlurRadius = getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS, 10).coerceIn(0, 40),
        advancedMaterialHighlight = getBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, false),
        softGlassColor = getInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, 0xFFFFFFFF.toInt()),
        softGlassOpacity = getInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, 10).coerceIn(0, 100),
        softGlassBackdropBlurRadius = getInt(KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS, 10).coerceIn(0, 40),
        softGlassBlurRadius = getInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, 10).coerceIn(0, 40),
        softGlassLuminance = getFloat(KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE, 0.14f).coerceIn(0f, 0.4f),
    )

    private fun SharedPreferences.write(value: LockscreenCapsuleSettings) {
        edit()
            .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, value.enabled)
            .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, value.lyricsEnabled)
            .putInt(
                KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
                value.mediaNotificationMode.coerceIn(
                    LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
                    LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC,
                ),
            )
            .remove(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION)
            .putInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, value.backgroundMode.coerceIn(0, 3))
            .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, value.width.coerceIn(160f, 360f))
            .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, value.height.coerceIn(10f, 60f))
            .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS, value.artworkCornerRadius.coerceIn(0f, 60f))
            .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, true)
            .putInt(KEY_MINI_PLAYER_PURE_COLOR, value.pureColor)
            .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR, value.advancedMaterialColor)
            .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY, value.advancedMaterialOpacity.coerceIn(0, 100))
            .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS, value.advancedMaterialBlurRadius.coerceIn(0, 40))
            .putBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, value.advancedMaterialHighlight)
            .putInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, value.softGlassColor)
            .putInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, value.softGlassOpacity.coerceIn(0, 100))
            .putInt(KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS, value.softGlassBackdropBlurRadius.coerceIn(0, 40))
            .putInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, value.softGlassBlurRadius.coerceIn(0, 40))
            .putFloat(KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE, value.softGlassLuminance.coerceIn(0f, 0.4f))
            .apply()
    }

    private fun LockscreenCapsuleSettings.normalized() = copy(
        mediaNotificationMode = mediaNotificationMode.coerceIn(
            LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
            LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC,
        ),
        backgroundMode = backgroundMode.coerceIn(0, 3),
        width = width.coerceIn(160f, 360f),
        height = height.coerceIn(10f, 60f),
        artworkCornerRadius = artworkCornerRadius.coerceIn(0f, 60f),
        advancedMaterialOpacity = advancedMaterialOpacity.coerceIn(0, 100),
        advancedMaterialBlurRadius = advancedMaterialBlurRadius.coerceIn(0, 40),
        softGlassOpacity = softGlassOpacity.coerceIn(0, 100),
        softGlassBackdropBlurRadius = softGlassBackdropBlurRadius.coerceIn(0, 40),
        softGlassBlurRadius = softGlassBlurRadius.coerceIn(0, 40),
        softGlassLuminance = softGlassLuminance.coerceIn(0f, 0.4f),
    )
}

internal const val KEY_LOCKSCREEN_MINI_PLAYER_ENABLED = "lockscreen_mini_player_enabled"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED = "lockscreen_mini_player_lyrics_enabled"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION = "lockscreen_mini_player_hide_media_notification"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE = "lockscreen_mini_player_media_notification_mode"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE = "lockscreen_mini_player_background_mode"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_WIDTH = "lockscreen_mini_player_width"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT = "lockscreen_mini_player_height"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS = "lockscreen_mini_player_artwork_corner_radius"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED = "lockscreen_mini_player_height_radius_migrated"
internal const val KEY_MINI_PLAYER_PURE_COLOR = "mini_player_pure_color"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR = "mini_player_advanced_material_color"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY = "mini_player_advanced_material_opacity"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS = "mini_player_advanced_material_blur_radius"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT = "mini_player_advanced_material_highlight"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_COLOR = "mini_player_soft_glass_color"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_OPACITY = "mini_player_soft_glass_opacity"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS = "mini_player_soft_glass_backdrop_blur_radius"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS = "mini_player_soft_glass_blur_radius"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE = "mini_player_soft_glass_luminance"

internal fun SharedPreferences.readMiniPlayerHeightRadius(): Float {
    val raw = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, 36f)
    if (contains(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT) &&
        !getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, false)
    ) {
        val converted = if (raw >= 48f) raw / 2f else raw
        edit()
            .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, converted)
            .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, true)
            .apply()
        return converted.coerceIn(10f, 60f)
    }
    return raw.coerceIn(10f, 60f)
}

internal fun SharedPreferences.readLockscreenCapsuleEnabled(): Boolean =
    getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)
