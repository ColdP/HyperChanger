// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.privacy

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

internal const val KEY_PRIVACY_MASTER = "preinstalled_privacy_enabled"
internal const val KEY_PRIVACY_BLOCK_REPORTS = "preinstalled_privacy_block_reports"
internal const val KEY_PRIVACY_BLOCK_INVENTORY = "preinstalled_privacy_block_inventory"
internal const val KEY_PRIVACY_BLOCK_DEVICE_IDS = "preinstalled_privacy_block_device_ids"
internal const val KEY_PRIVACY_BLOCK_CAPTURE = "preinstalled_privacy_block_capture"

internal data class PrivacyGuardSettings(
    val enabled: Boolean = false,
    val blockReports: Boolean = true,
    val blockInventory: Boolean = true,
    val blockDeviceIds: Boolean = true,
    val blockCapture: Boolean = true,
)

internal fun readPrivacyGuardSettings(preferences: SharedPreferences) = PrivacyGuardSettings(
    enabled = preferences.getBoolean(KEY_PRIVACY_MASTER, false),
    blockReports = preferences.getBoolean(KEY_PRIVACY_BLOCK_REPORTS, true),
    blockInventory = preferences.getBoolean(KEY_PRIVACY_BLOCK_INVENTORY, true),
    blockDeviceIds = preferences.getBoolean(KEY_PRIVACY_BLOCK_DEVICE_IDS, true),
    blockCapture = preferences.getBoolean(KEY_PRIVACY_BLOCK_CAPTURE, true),
)

/** Owns the preinstalled privacy switches without extending HookSettings. */
internal class PrivacyGuardSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readPrivacyGuardSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readPrivacyGuardSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (PrivacyGuardSettings) -> PrivacyGuardSettings) {
        settings = transform(settings)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_PRIVACY_MASTER, settings.enabled)
            .putBoolean(KEY_PRIVACY_BLOCK_REPORTS, settings.blockReports)
            .putBoolean(KEY_PRIVACY_BLOCK_INVENTORY, settings.blockInventory)
            .putBoolean(KEY_PRIVACY_BLOCK_DEVICE_IDS, settings.blockDeviceIds)
            .putBoolean(KEY_PRIVACY_BLOCK_CAPTURE, settings.blockCapture)
            .apply()
    }
}
