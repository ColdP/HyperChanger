// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Preferences for the system updater integration (`com.android.updater`).
 *
 * Moved out of `HookSettings` so the updater spoofing feature owns its own storage instead of
 * growing the shared settings blob. Every key keeps its original name, so existing installs keep
 * their values across the move.
 */
internal const val KEY_SYSTEM_UPDATE_DISABLED = "system_update_disabled"
internal const val KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED = "system_update_ota_limit_removed"
internal const val KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED = "system_update_version_spoof_enabled"
internal const val KEY_SYSTEM_UPDATE_VERSION = "system_update_version"
internal const val KEY_SYSTEM_UPDATE_SOTA_VERSION = "system_update_sota_version"

internal data class SystemUpdatePreferences(
    val disabled: Boolean = false,
    val otaLimitRemoved: Boolean = false,
    val versionSpoofEnabled: Boolean = false,
    val version: String = "",
    val sotaVersion: String = "",
) {
    fun normalized() = copy(version = version.take(128), sotaVersion = sotaVersion.take(128))
}

internal fun readSystemUpdatePreferences(p: SharedPreferences): SystemUpdatePreferences =
    SystemUpdatePreferences(
        disabled = p.getBoolean(KEY_SYSTEM_UPDATE_DISABLED, false),
        otaLimitRemoved = p.getBoolean(KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED, false),
        versionSpoofEnabled = p.getBoolean(KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED, false),
        version = p.getString(KEY_SYSTEM_UPDATE_VERSION, "").orEmpty().take(128),
        sotaVersion = p.getString(KEY_SYSTEM_UPDATE_SOTA_VERSION, "").orEmpty().take(128),
    ).normalized()

internal class SystemUpdatePreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSystemUpdatePreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSystemUpdatePreferences(
            if (remote.contains(KEY_SYSTEM_UPDATE_DISABLED)) remote else local,
        )
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SystemUpdatePreferences) -> SystemUpdatePreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SystemUpdatePreferences) {
        p.edit()
            .putBoolean(KEY_SYSTEM_UPDATE_DISABLED, s.disabled)
            .putBoolean(KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED, s.otaLimitRemoved)
            .putBoolean(KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED, s.versionSpoofEnabled)
            .putString(KEY_SYSTEM_UPDATE_VERSION, s.version)
            .putString(KEY_SYSTEM_UPDATE_SOTA_VERSION, s.sotaVersion)
            .apply()
    }
}
