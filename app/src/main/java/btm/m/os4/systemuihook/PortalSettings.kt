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
internal const val KEY_PORTAL_CUSTOM_EXPANDED_HEIGHT = "portal_custom_expanded_height"
internal const val KEY_PORTAL_EXPANDED_HEIGHT = "portal_expanded_height_offset_dp"
internal const val KEY_PORTAL_MATERIAL_MODE = "portal_material_mode"
internal const val KEY_PORTAL_USE_GLOBAL_GLASS = "portal_material_use_global_glass"
internal const val KEY_PORTAL_GLASS_PREFIX = "portal_material_glass_"
internal const val KEY_PORTAL_ADVANCED_HIGHLIGHT = "portal_advanced_material_highlight"
internal const val KEY_PORTAL_CUSTOM_SPLASH_SIZE = "portal_custom_splash_size"
internal const val KEY_PORTAL_SPLASH_SIZE_OFFSET = "portal_splash_size_offset_percent"
internal const val KEY_PORTAL_CUSTOM_SPLASH_ICON_SIZE = "portal_custom_splash_icon_size"
internal const val KEY_PORTAL_SPLASH_ICON_SIZE_OFFSET = "portal_splash_icon_size_offset_percent"
internal const val PORTAL_DEVELOPER_SETTING = "key_dev_developer_mode_v1"

internal const val PORTAL_MATERIAL_DEFAULT = 0
internal const val PORTAL_MATERIAL_ADVANCED = 1
internal const val PORTAL_MATERIAL_SOFT_GLASS = 2

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
    val customExpandedHeight: Boolean = false,
    val expandedHeightOffsetDp: Int = 0,
    val materialMode: Int = PORTAL_MATERIAL_DEFAULT,
    val useGlobalGlass: Boolean = true,
    val advancedHighlight: Boolean = false,
    val customSplashSize: Boolean = false,
    val splashSizeOffsetPercent: Int = 0,
    val customSplashIconSize: Boolean = false,
    val splashIconSizeOffsetPercent: Int = 0,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
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
        customExpandedHeight = preferences.getBoolean(KEY_PORTAL_CUSTOM_EXPANDED_HEIGHT, false),
        expandedHeightOffsetDp = preferences.getInt(KEY_PORTAL_EXPANDED_HEIGHT, 0).coerceIn(-128, 256),
        materialMode = preferences.getInt(KEY_PORTAL_MATERIAL_MODE, if (preferences.getBoolean(KEY_PORTAL_BLUR, false)) PORTAL_MATERIAL_ADVANCED else PORTAL_MATERIAL_DEFAULT).coerceIn(PORTAL_MATERIAL_DEFAULT, PORTAL_MATERIAL_SOFT_GLASS),
        useGlobalGlass = preferences.getBoolean(KEY_PORTAL_USE_GLOBAL_GLASS, true),
        advancedHighlight = preferences.getBoolean(KEY_PORTAL_ADVANCED_HIGHLIGHT, false),
        customSplashSize = preferences.getBoolean(KEY_PORTAL_CUSTOM_SPLASH_SIZE, false),
        splashSizeOffsetPercent = preferences.getInt(KEY_PORTAL_SPLASH_SIZE_OFFSET, 0).coerceIn(-100, 100),
        customSplashIconSize = preferences.getBoolean(KEY_PORTAL_CUSTOM_SPLASH_ICON_SIZE, false),
        splashIconSizeOffsetPercent = preferences.getInt(KEY_PORTAL_SPLASH_ICON_SIZE_OFFSET, 0).coerceIn(-100, 100),
        glass = readSoftGlassSettings(preferences, KEY_PORTAL_GLASS_PREFIX),
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
        if (!local.contains(KEY_PORTAL_CUSTOM_EXPANDED_HEIGHT)) editor.putBoolean(KEY_PORTAL_CUSTOM_EXPANDED_HEIGHT, value.customExpandedHeight)
        if (!local.contains(KEY_PORTAL_EXPANDED_HEIGHT)) editor.putInt(KEY_PORTAL_EXPANDED_HEIGHT, value.expandedHeightOffsetDp)
        if (!local.contains(KEY_PORTAL_MATERIAL_MODE)) editor.putInt(KEY_PORTAL_MATERIAL_MODE,
            if (local.contains(KEY_PORTAL_BLUR)) read(local).materialMode else value.materialMode)
        if (!local.contains(KEY_PORTAL_USE_GLOBAL_GLASS)) editor.putBoolean(KEY_PORTAL_USE_GLOBAL_GLASS, value.useGlobalGlass)
        if (!local.contains(KEY_PORTAL_ADVANCED_HIGHLIGHT)) editor.putBoolean(KEY_PORTAL_ADVANCED_HIGHLIGHT, value.advancedHighlight)
        if (!local.contains(KEY_PORTAL_CUSTOM_SPLASH_SIZE)) editor.putBoolean(KEY_PORTAL_CUSTOM_SPLASH_SIZE, value.customSplashSize)
        if (!local.contains(KEY_PORTAL_SPLASH_SIZE_OFFSET)) editor.putInt(KEY_PORTAL_SPLASH_SIZE_OFFSET, value.splashSizeOffsetPercent)
        if (!local.contains(KEY_PORTAL_CUSTOM_SPLASH_ICON_SIZE)) editor.putBoolean(KEY_PORTAL_CUSTOM_SPLASH_ICON_SIZE, value.customSplashIconSize)
        if (!local.contains(KEY_PORTAL_SPLASH_ICON_SIZE_OFFSET)) editor.putInt(KEY_PORTAL_SPLASH_ICON_SIZE_OFFSET, value.splashIconSizeOffsetPercent)
        remote.all.forEach { (key, entry) ->
            if (key.startsWith(KEY_PORTAL_GLASS_PREFIX) && !local.contains(key)) {
                when (entry) {
                    is Int -> editor.putInt(key, entry)
                    is Boolean -> editor.putBoolean(key, entry)
                }
            }
        }
        editor.apply()
        write(remote, read(local))
    }

    private fun write(preferences: SharedPreferences, settings: PortalAppearanceSettings) {
        preferences.edit()
            .putBoolean(KEY_PORTAL_BLUR, settings.materialMode == PORTAL_MATERIAL_ADVANCED)
            .putBoolean(KEY_PORTAL_CUSTOM_BLUR, settings.customBlur)
            .putInt(KEY_PORTAL_BLUR_RADIUS, settings.blurRadiusDp.coerceIn(0, 100))
            .putBoolean(KEY_PORTAL_CUSTOM_OPACITY, settings.customOpacity)
            .putInt(KEY_PORTAL_OPACITY, settings.opacityPercent.coerceIn(0, 100))
            .putBoolean(KEY_PORTAL_CUSTOM_CORNER, settings.customCorner)
            .putInt(KEY_PORTAL_CORNER, settings.cornerOffsetDp.coerceIn(-32, 64))
            .putBoolean(KEY_PORTAL_DEVELOPER, settings.developerMode)
            .putBoolean(KEY_PORTAL_REMOVE_SCRIM, settings.removeScrim)
            .putBoolean(KEY_PORTAL_JAPANESE_OCR, settings.japaneseOcr)
            .putBoolean(KEY_PORTAL_CUSTOM_EXPANDED_HEIGHT, settings.customExpandedHeight)
            .putInt(KEY_PORTAL_EXPANDED_HEIGHT, settings.expandedHeightOffsetDp.coerceIn(-128, 256))
            .putInt(KEY_PORTAL_MATERIAL_MODE, settings.materialMode.coerceIn(PORTAL_MATERIAL_DEFAULT, PORTAL_MATERIAL_SOFT_GLASS))
            .putBoolean(KEY_PORTAL_USE_GLOBAL_GLASS, settings.useGlobalGlass)
            .putBoolean(KEY_PORTAL_ADVANCED_HIGHLIGHT, settings.advancedHighlight)
            .putBoolean(KEY_PORTAL_CUSTOM_SPLASH_SIZE, settings.customSplashSize)
            .putInt(KEY_PORTAL_SPLASH_SIZE_OFFSET, settings.splashSizeOffsetPercent.coerceIn(-100, 100))
            .putBoolean(KEY_PORTAL_CUSTOM_SPLASH_ICON_SIZE, settings.customSplashIconSize)
            .putInt(KEY_PORTAL_SPLASH_ICON_SIZE_OFFSET, settings.splashIconSizeOffsetPercent.coerceIn(-100, 100))
            .putBoolean(KEY_PORTAL_GLASS_PREFIX + "enabled", settings.glass.enabled)
            .putInt(KEY_PORTAL_GLASS_PREFIX + "refraction_blur", settings.glass.refractionBlur)
            .putInt(KEY_PORTAL_GLASS_PREFIX + "blur_percent", settings.glass.backgroundBlur)
            .putInt(KEY_PORTAL_GLASS_PREFIX + "scale_percent", settings.glass.backgroundScale)
            .putInt(KEY_PORTAL_GLASS_PREFIX + "opacity_percent", settings.glass.backgroundOpacity)
            .also { editor ->
                softGlassParameters.forEach { definition ->
                    editor.putInt(KEY_PORTAL_GLASS_PREFIX + if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key,
                        settings.glass.adjustments[definition.key] ?: if (definition.key == "darker") -100 else 0)
                }
            }
            .apply()
    }
}
