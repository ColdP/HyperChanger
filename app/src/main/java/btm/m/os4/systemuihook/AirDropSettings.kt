// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/** Module setting for the MiShare AirDrop availability gate. */
data class AirDropSettings(val forceEnable: Boolean = false)

class AirDropSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: AirDropSettings = local.read()
        private set

    fun reload() {
        settings = local.read()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteSettings = remote.read()
        settings = if (remote.contains(KEY_AIRDROP_FORCE_ENABLE)) remoteSettings else settings
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (AirDropSettings) -> AirDropSettings) {
        settings = transform(settings)
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    private fun SharedPreferences.read() = AirDropSettings(
        forceEnable = getBoolean(KEY_AIRDROP_FORCE_ENABLE, false),
    )

    private fun SharedPreferences.write(value: AirDropSettings) {
        edit().putBoolean(KEY_AIRDROP_FORCE_ENABLE, value.forceEnable).apply()
    }
}

internal const val KEY_AIRDROP_FORCE_ENABLE = "airdrop_force_enable"
