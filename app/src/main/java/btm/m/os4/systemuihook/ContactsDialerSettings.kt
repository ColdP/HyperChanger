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

internal data class ContactsDialerBackgroundSettings(
    val blur: Boolean = false,
    val customBlur: Boolean = false,
    val blurRadiusDp: Int = 24,
    val customOpacity: Boolean = false,
    val opacityPercent: Int = 70,
)

internal object ContactsDialerSettings {
    fun read(preferences: SharedPreferences) = ContactsDialerBackgroundSettings(
        blur = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR, false),
        customBlur = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM, false),
        blurRadiusDp = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS, 24).coerceIn(0, 100),
        customOpacity = preferences.getBoolean(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED, false),
        opacityPercent = preferences.getInt(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY, 70).coerceIn(0, 100),
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
            .apply()
    }
}
