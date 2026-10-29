// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.securitycenter.KEY_SECURITYCENTER_ROOTED_DIAGNOSTICS
import btm.m.os4.systemuihook.securitycenter.KEY_SECURITYCENTER_ROOT_DIAGNOSTICS
import btm.m.os4.systemuihook.trustservice.KEY_TRUSTSERVICE_DIAGNOSTICS
import io.github.libxposed.service.XposedService

internal const val KEY_ENVIRONMENT_DETECTION_ENABLED = "environment_detection_enabled"

internal data class EnvironmentDetectionSettings(
    val enabled: Boolean = false,
    val disableMrmd: Boolean = false,
    val rootedCheck: Boolean = false,
    val rootCheck: Boolean = false,
)

internal fun SharedPreferences.isEnvironmentDetectionEnabled(): Boolean =
    getBoolean(KEY_ENVIRONMENT_DETECTION_ENABLED, false)

/** Owns environment detection preferences without extending HookSettings. */
internal class EnvironmentDetectionSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings = read(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Existing builds wrote the three feature switches directly to remote preferences.
        // Import them when no local value exists; retain changes made while disconnected.
        settings = read(local, remote)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (EnvironmentDetectionSettings) -> EnvironmentDetectionSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun read(prefs: SharedPreferences, fallback: SharedPreferences = prefs): EnvironmentDetectionSettings {
        fun flag(key: String) = (if (prefs.contains(key)) prefs else fallback).getBoolean(key, false)
        return EnvironmentDetectionSettings(
            enabled = flag(KEY_ENVIRONMENT_DETECTION_ENABLED),
            disableMrmd = flag(KEY_TRUSTSERVICE_DIAGNOSTICS),
            rootedCheck = flag(KEY_SECURITYCENTER_ROOTED_DIAGNOSTICS),
            rootCheck = flag(KEY_SECURITYCENTER_ROOT_DIAGNOSTICS),
        )
    }

    private fun write(prefs: SharedPreferences, value: EnvironmentDetectionSettings) {
        prefs.edit()
            .putBoolean(KEY_ENVIRONMENT_DETECTION_ENABLED, value.enabled)
            .putBoolean(KEY_TRUSTSERVICE_DIAGNOSTICS, value.disableMrmd)
            .putBoolean(KEY_SECURITYCENTER_ROOTED_DIAGNOSTICS, value.rootedCheck)
            .putBoolean(KEY_SECURITYCENTER_ROOT_DIAGNOSTICS, value.rootCheck)
            .apply()
    }
}
