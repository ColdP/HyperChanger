// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.cloudcontrol

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

internal const val KEY_CLOUD_CONTROL_ENABLED = "cloud_control_enabled"
internal const val KEY_HTML_VIEWER_DISABLE_CLOUD_CONTROL = "html_viewer_disable_cloud_control"
internal const val KEY_JOYOSE_DISABLE_CLOUD_CONTROL = "joyose_disable_cloud_control"

internal data class CloudControlSettings(
    val enabled: Boolean = false,
    val htmlViewer: Boolean = false,
    val joyose: Boolean = false,
)

internal fun SharedPreferences.isCloudControlEnabled(): Boolean =
    getBoolean(KEY_CLOUD_CONTROL_ENABLED, false)

internal fun readCloudControlSettings(preferences: SharedPreferences): CloudControlSettings =
    readCloudControl(preferences, preferences)

private fun readCloudControl(prefs: SharedPreferences, fallback: SharedPreferences): CloudControlSettings {
    fun flag(key: String) = (if (prefs.contains(key)) prefs else fallback).getBoolean(key, false)
    val htmlViewer = flag(KEY_HTML_VIEWER_DISABLE_CLOUD_CONTROL)
    val joyose = flag(KEY_JOYOSE_DISABLE_CLOUD_CONTROL)
    // Builds before the master switch wrote only the per-application switches. Keep those
    // applications active instead of silently disabling them on upgrade.
    val enabled = if (prefs.contains(KEY_CLOUD_CONTROL_ENABLED) || fallback.contains(KEY_CLOUD_CONTROL_ENABLED)) {
        flag(KEY_CLOUD_CONTROL_ENABLED)
    } else {
        htmlViewer || joyose
    }
    return CloudControlSettings(enabled = enabled, htmlViewer = htmlViewer, joyose = joyose)
}

/**
 * Owns the cloud control master switch and its per-application switches without extending
 * HookSettings. The HTML viewer and Joyose installers depend on this file; it never depends on
 * their packages, so the dependency stays one-way.
 */
internal class CloudControlSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings = readCloudControlSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readCloudControl(local, remote)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (CloudControlSettings) -> CloudControlSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(prefs: SharedPreferences, value: CloudControlSettings) {
        prefs.edit()
            .putBoolean(KEY_CLOUD_CONTROL_ENABLED, value.enabled)
            .putBoolean(KEY_HTML_VIEWER_DISABLE_CLOUD_CONTROL, value.htmlViewer)
            .putBoolean(KEY_JOYOSE_DISABLE_CLOUD_CONTROL, value.joyose)
            .apply()
    }
}
