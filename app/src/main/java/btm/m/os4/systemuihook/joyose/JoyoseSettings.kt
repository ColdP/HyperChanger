// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.joyose

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

internal const val KEY_JOYOSE_ENABLE_GPU_TUNER = "joyose_enable_gpu_tuner"

data class JoyoseSettings(
    val enableGpuTuner: Boolean = false,
)

internal fun readJoyoseSettings(preferences: SharedPreferences) = JoyoseSettings(
    enableGpuTuner = preferences.getBoolean(KEY_JOYOSE_ENABLE_GPU_TUNER, false),
)

/** Stores the Joyose GPU tuner switch independently of HookSettings and mirrors it to Joyose. */
class JoyoseSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readJoyoseSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readJoyoseSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (JoyoseSettings) -> JoyoseSettings) {
        settings = transform(settings)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_JOYOSE_ENABLE_GPU_TUNER, settings.enableGpuTuner)
            .apply()
    }
}
