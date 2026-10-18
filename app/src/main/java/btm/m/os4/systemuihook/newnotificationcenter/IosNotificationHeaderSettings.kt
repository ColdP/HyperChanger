// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

/** Settings for the optional iOS-style notification header. Kept out of HookSettings. */
data class IosNotificationHeaderSettings(
    val enabled: Boolean = false,
    val clearButtonStyle: Int = 0, // 0 = icon and text, 1 = icon, 2 = text
    val clearButtonMaterial: Int = 0, // 0 = soft glass, 1 = advanced material, 2 = solid
    val clearButtonCornerRadius: Float = 22f,
    val clearButtonPureColor: Int = 0x66FFFFFF,
    val clearButtonAdvancedColor: Int = 0xFFFFFFFF.toInt(),
    val clearButtonAdvancedOpacity: Int = 14,
    val clearButtonAdvancedBlurRadius: Int = 36,
    val clearButtonAdvancedHighlight: Boolean = false,
    val clearButtonSoftGlassColor: Int = 0xFFFFFFFF.toInt(),
    val clearButtonSoftGlassOpacity: Int = 10,
    val clearButtonSoftGlassBackdropBlurRadius: Int = 80,
    val clearButtonSoftGlassBlurRadius: Int = 36,
    val clearButtonSoftGlassLuminance: Float = 0.14f,
    val clearButtonSoftGlassRefraction: Int = 0,
) {
    fun normalized() = copy(
        clearButtonStyle = clearButtonStyle.coerceIn(0, 2),
        clearButtonMaterial = clearButtonMaterial.coerceIn(0, 2),
        clearButtonCornerRadius = clearButtonCornerRadius.coerceIn(0f, 60f),
        clearButtonAdvancedOpacity = clearButtonAdvancedOpacity.coerceIn(0, 100),
        clearButtonAdvancedBlurRadius = clearButtonAdvancedBlurRadius.coerceIn(0, 120),
        clearButtonSoftGlassOpacity = clearButtonSoftGlassOpacity.coerceIn(0, 100),
        clearButtonSoftGlassBackdropBlurRadius = clearButtonSoftGlassBackdropBlurRadius.coerceIn(0, 120),
        clearButtonSoftGlassBlurRadius = clearButtonSoftGlassBlurRadius.coerceIn(0, 120),
        clearButtonSoftGlassLuminance = clearButtonSoftGlassLuminance.coerceIn(0f, 0.4f),
        clearButtonSoftGlassRefraction = clearButtonSoftGlassRefraction.coerceIn(-100, 100),
    )
}

class IosNotificationHeaderSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = local.read().normalized()
        private set

    fun syncRemote(service: XposedService) {
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(service: XposedService?, transform: (IosNotificationHeaderSettings) -> IosNotificationHeaderSettings) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun SharedPreferences.read() = IosNotificationHeaderSettings(
        enabled = getBoolean(KEY_IOS_NOTIFICATION_HEADER, false),
        clearButtonStyle = getInt(KEY_IOS_NOTIFICATION_CLEAR_STYLE, 0),
        clearButtonMaterial = getInt(KEY_IOS_NOTIFICATION_CLEAR_MATERIAL, 0),
        clearButtonCornerRadius = getFloat(KEY_IOS_NOTIFICATION_CLEAR_RADIUS, 22f),
        clearButtonPureColor = getInt(KEY_IOS_NOTIFICATION_CLEAR_PURE_COLOR, 0x66FFFFFF),
        clearButtonAdvancedColor = getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_COLOR, 0xFFFFFFFF.toInt()),
        clearButtonAdvancedOpacity = getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_OPACITY, 14),
        clearButtonAdvancedBlurRadius = getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_BLUR, 36),
        clearButtonAdvancedHighlight = getBoolean(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_HIGHLIGHT, false),
        clearButtonSoftGlassColor = getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_COLOR, 0xFFFFFFFF.toInt()),
        clearButtonSoftGlassOpacity = getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_OPACITY, 10),
        clearButtonSoftGlassBackdropBlurRadius = getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BACKDROP_BLUR, 80),
        clearButtonSoftGlassBlurRadius = getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BLUR, 36),
        clearButtonSoftGlassLuminance = getFloat(KEY_IOS_NOTIFICATION_CLEAR_SOFT_LUMINANCE, 0.14f),
        clearButtonSoftGlassRefraction = getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_REFRACTION, 0),
    )

    private fun write(p: SharedPreferences, value: IosNotificationHeaderSettings) {
        val v = value.normalized()
        p.edit()
            .putBoolean(KEY_IOS_NOTIFICATION_HEADER, v.enabled)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_STYLE, v.clearButtonStyle)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_MATERIAL, v.clearButtonMaterial)
            .putFloat(KEY_IOS_NOTIFICATION_CLEAR_RADIUS, v.clearButtonCornerRadius)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_PURE_COLOR, v.clearButtonPureColor)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_COLOR, v.clearButtonAdvancedColor)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_OPACITY, v.clearButtonAdvancedOpacity)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_BLUR, v.clearButtonAdvancedBlurRadius)
            .putBoolean(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_HIGHLIGHT, v.clearButtonAdvancedHighlight)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_COLOR, v.clearButtonSoftGlassColor)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_OPACITY, v.clearButtonSoftGlassOpacity)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BACKDROP_BLUR, v.clearButtonSoftGlassBackdropBlurRadius)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BLUR, v.clearButtonSoftGlassBlurRadius)
            .putFloat(KEY_IOS_NOTIFICATION_CLEAR_SOFT_LUMINANCE, v.clearButtonSoftGlassLuminance)
            .putInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_REFRACTION, v.clearButtonSoftGlassRefraction)
            .apply()
    }
}

const val KEY_IOS_NOTIFICATION_HEADER = "ios_notification_header"
const val KEY_IOS_NOTIFICATION_CLEAR_STYLE = "ios_notification_clear_style"
const val KEY_IOS_NOTIFICATION_CLEAR_MATERIAL = "ios_notification_clear_material"
const val KEY_IOS_NOTIFICATION_CLEAR_RADIUS = "ios_notification_clear_radius"
const val KEY_IOS_NOTIFICATION_CLEAR_PURE_COLOR = "ios_notification_clear_pure_color"
const val KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_COLOR = "ios_notification_clear_advanced_color"
const val KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_OPACITY = "ios_notification_clear_advanced_opacity"
const val KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_BLUR = "ios_notification_clear_advanced_blur"
const val KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_HIGHLIGHT = "ios_notification_clear_advanced_highlight"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_COLOR = "ios_notification_clear_soft_color"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_OPACITY = "ios_notification_clear_soft_opacity"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_BACKDROP_BLUR = "ios_notification_clear_soft_backdrop_blur"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_BLUR = "ios_notification_clear_soft_blur"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_LUMINANCE = "ios_notification_clear_soft_luminance"
const val KEY_IOS_NOTIFICATION_CLEAR_SOFT_REFRACTION = "ios_notification_clear_soft_refraction"
