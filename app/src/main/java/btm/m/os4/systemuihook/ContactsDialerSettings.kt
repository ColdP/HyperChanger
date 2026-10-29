// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_CONTACTS_DIALER_BACKGROUND_BLUR = "contacts_dialer_background_blur"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM = "contacts_dialer_background_blur_custom"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS = "contacts_dialer_background_blur_radius_dp"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED = "contacts_dialer_background_opacity_enabled"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_OPACITY = "contacts_dialer_background_opacity_percent"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_BORDER = "contacts_dialer_background_border_enabled"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH = "contacts_dialer_background_border_width_hundredths_px"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY = "contacts_dialer_background_border_opacity_percent"
internal const val KEY_CONTACTS_DIALER_BACKGROUND_CORNER = "contacts_dialer_background_corner_offset"

internal data class ContactsDialerBackgroundSettings(
    val blur: Boolean = false,
    val customBlur: Boolean = false,
    val blurRadiusDp: Int = 24,
    val customOpacity: Boolean = false,
    val opacityPercent: Int = 70,
    val border: Boolean = false,
    val borderWidthHundredthsPx: Int = 100,
    val borderOpacityPercent: Int = 100,
    val cornerOffset: Int = 0,
) {
    val borderWidthPx: Float get() = borderWidthHundredthsPx.coerceIn(1, 600) / 100f
}

/** Negative values scale the native radius down to zero; positive values add dp. */
internal fun contactsDialerCornerRadius(nativeRadiusPx: Float, offset: Int, density: Float): Float {
    val value = offset.coerceIn(-50, 64)
    return if (value <= 0) nativeRadiusPx * (value + 50) / 50f
    else nativeRadiusPx + value * density
}

internal object ContactsDialerSettings {
    fun read(preferences: SharedPreferences) = ContactsDialerBackgroundSettings(
        blur = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR, false),
        customBlur = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM, false),
        blurRadiusDp = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS, 24).coerceIn(0, 100),
        customOpacity = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED, false),
        opacityPercent = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY, 70).coerceIn(0, 100),
        border = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BORDER, false),
        borderWidthHundredthsPx = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH, 100).coerceIn(1, 600),
        borderOpacityPercent = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY, 100).coerceIn(0, 100),
        cornerOffset = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_CORNER, 0).coerceIn(-50, 64),
    )

    fun save(context: Context, service: XposedService?, settings: ContactsDialerBackgroundSettings) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    fun syncRemote(context: Context, service: XposedService) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteSettings = read(remote)
        // Merge per key so a first connection preserves existing remote values.
        val editor = local.edit()
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BLUR)) {
            editor.putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR, remoteSettings.blur)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM)) {
            editor.putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM, remoteSettings.customBlur)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS)) {
            editor.putInt(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS, remoteSettings.blurRadiusDp)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED)) {
            editor.putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED, remoteSettings.customOpacity)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY)) {
            editor.putInt(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY, remoteSettings.opacityPercent)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BORDER)) {
            editor.putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BORDER, remoteSettings.border)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH)) {
            editor.putInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH, remoteSettings.borderWidthHundredthsPx)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY)) {
            editor.putInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY, remoteSettings.borderOpacityPercent)
        }
        if (!local.contains(KEY_CONTACTS_DIALER_BACKGROUND_CORNER)) {
            editor.putInt(KEY_CONTACTS_DIALER_BACKGROUND_CORNER, remoteSettings.cornerOffset)
        }
        editor.apply()
        write(remote, read(local))
    }

    private fun write(preferences: SharedPreferences, settings: ContactsDialerBackgroundSettings) {
        preferences.edit()
            .putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR, settings.blur)
            .putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM, settings.customBlur)
            .putInt(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS, settings.blurRadiusDp.coerceIn(0, 100))
            .putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED, settings.customOpacity)
            .putInt(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY, settings.opacityPercent.coerceIn(0, 100))
            .putBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BORDER, settings.border)
            .putInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH, settings.borderWidthHundredthsPx.coerceIn(1, 600))
            .putInt(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY, settings.borderOpacityPercent.coerceIn(0, 100))
            .putInt(KEY_CONTACTS_DIALER_BACKGROUND_CORNER, settings.cornerOffset.coerceIn(-50, 64))
            .apply()
    }
}
