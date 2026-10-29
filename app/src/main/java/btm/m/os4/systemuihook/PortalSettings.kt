// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_PORTAL_BLUR = "portal_background_blur"
internal const val KEY_PORTAL_CUSTOM_BLUR = "portal_background_custom_blur"
internal const val KEY_PORTAL_BLUR_RADIUS = "portal_background_blur_radius_dp"
internal const val KEY_PORTAL_CUSTOM_OPACITY = "portal_background_custom_opacity"
internal const val KEY_PORTAL_OPACITY = "portal_background_opacity_percent"
internal const val KEY_PORTAL_CUSTOM_CORNER = "portal_background_custom_corner"
internal const val KEY_PORTAL_CORNER = "portal_background_corner_offset_dp"
internal const val KEY_PORTAL_DEVELOPER = "portal_developer_mode"
internal const val KEY_PORTAL_REMOVE_SCRIM = "portal_remove_fullscreen_scrim"
internal const val KEY_PORTAL_JAPANESE_OCR = "portal_japanese_ocr"
internal const val PORTAL_DEVELOPER_SETTING = "key_dev_developer_mode_v1"

internal data class PortalAppearanceSettings(
    val blur: Boolean = false,
    val customBlur: Boolean = false,
    val blurRadiusDp: Int = 24,
    val customOpacity: Boolean = false,
    val opacityPercent: Int = 70,
    val customCorner: Boolean = false,
    val cornerOffsetDp: Int = 0,
    val developerMode: Boolean = false,
    val removeScrim: Boolean = false,
    val japaneseOcr: Boolean = false,
)

internal fun portalCornerRadius(nativeRadiusPx: Float, offsetDp: Int, density: Float): Float =
    (nativeRadiusPx + offsetDp.coerceIn(-32, 64) * density).coerceAtLeast(0f)

internal object PortalSettings {
    fun read(preferences: SharedPreferences) = PortalAppearanceSettings(
        blur = preferences.getBoolean(KEY_PORTAL_BLUR, false),
        customBlur = preferences.getBoolean(KEY_PORTAL_CUSTOM_BLUR, false),
        blurRadiusDp = preferences.getInt(KEY_PORTAL_BLUR_RADIUS, 24).coerceIn(0, 100),
        customOpacity = preferences.getBoolean(KEY_PORTAL_CUSTOM_OPACITY, false),
        opacityPercent = preferences.getInt(KEY_PORTAL_OPACITY, 70).coerceIn(0, 100),
        customCorner = preferences.getBoolean(KEY_PORTAL_CUSTOM_CORNER, false),
        cornerOffsetDp = preferences.getInt(KEY_PORTAL_CORNER, 0).coerceIn(-32, 64),
        developerMode = preferences.getBoolean(KEY_PORTAL_DEVELOPER, false),
        removeScrim = preferences.getBoolean(KEY_PORTAL_REMOVE_SCRIM, false),
        japaneseOcr = preferences.getBoolean(KEY_PORTAL_JAPANESE_OCR, false),
    )

    fun save(context: Context, service: XposedService?, settings: PortalAppearanceSettings) {
        write(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE), settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    fun syncRemote(context: Context, service: XposedService) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val value = read(remote)
        val editor = local.edit()
        if (!local.contains(KEY_PORTAL_BLUR)) editor.putBoolean(KEY_PORTAL_BLUR, value.blur)
        if (!local.contains(KEY_PORTAL_CUSTOM_BLUR)) editor.putBoolean(KEY_PORTAL_CUSTOM_BLUR, value.customBlur)
        if (!local.contains(KEY_PORTAL_BLUR_RADIUS)) editor.putInt(KEY_PORTAL_BLUR_RADIUS, value.blurRadiusDp)
        if (!local.contains(KEY_PORTAL_CUSTOM_OPACITY)) editor.putBoolean(KEY_PORTAL_CUSTOM_OPACITY, value.customOpacity)
        if (!local.contains(KEY_PORTAL_OPACITY)) editor.putInt(KEY_PORTAL_OPACITY, value.opacityPercent)
        if (!local.contains(KEY_PORTAL_CUSTOM_CORNER)) editor.putBoolean(KEY_PORTAL_CUSTOM_CORNER, value.customCorner)
        if (!local.contains(KEY_PORTAL_CORNER)) editor.putInt(KEY_PORTAL_CORNER, value.cornerOffsetDp)
        if (!local.contains(KEY_PORTAL_DEVELOPER)) editor.putBoolean(KEY_PORTAL_DEVELOPER, value.developerMode)
        if (!local.contains(KEY_PORTAL_REMOVE_SCRIM)) editor.putBoolean(KEY_PORTAL_REMOVE_SCRIM, value.removeScrim)
        if (!local.contains(KEY_PORTAL_JAPANESE_OCR)) editor.putBoolean(KEY_PORTAL_JAPANESE_OCR, value.japaneseOcr)
        editor.apply()
        write(remote, read(local))
    }

    private fun write(preferences: SharedPreferences, settings: PortalAppearanceSettings) {
        preferences.edit()
            .putBoolean(KEY_PORTAL_BLUR, settings.blur)
            .putBoolean(KEY_PORTAL_CUSTOM_BLUR, settings.customBlur)
            .putInt(KEY_PORTAL_BLUR_RADIUS, settings.blurRadiusDp.coerceIn(0, 100))
            .putBoolean(KEY_PORTAL_CUSTOM_OPACITY, settings.customOpacity)
            .putInt(KEY_PORTAL_OPACITY, settings.opacityPercent.coerceIn(0, 100))
            .putBoolean(KEY_PORTAL_CUSTOM_CORNER, settings.customCorner)
            .putInt(KEY_PORTAL_CORNER, settings.cornerOffsetDp.coerceIn(-32, 64))
            .putBoolean(KEY_PORTAL_DEVELOPER, settings.developerMode)
            .putBoolean(KEY_PORTAL_REMOVE_SCRIM, settings.removeScrim)
            .putBoolean(KEY_PORTAL_JAPANESE_OCR, settings.japaneseOcr)
            .apply()
    }
}
