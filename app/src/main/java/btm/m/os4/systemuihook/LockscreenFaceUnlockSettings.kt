// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_DISABLE_FACE_UNLOCK_ICON = "disable_face_unlock_icon"
internal const val KEY_FACE_UNLOCK_ISLAND = "face_unlock_island"

data class LockscreenFaceUnlockSettings(
    val disableIcon: Boolean = false,
    val unlockIsland: Boolean = false,
)

internal fun readLockscreenFaceUnlockSettings(preferences: SharedPreferences) =
    LockscreenFaceUnlockSettings(
        disableIcon = preferences.getBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, false),
        unlockIsland = preferences.getBoolean(KEY_FACE_UNLOCK_ISLAND, false),
    )

class LockscreenFaceUnlockSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readLockscreenFaceUnlockSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readLockscreenFaceUnlockSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (LockscreenFaceUnlockSettings) -> LockscreenFaceUnlockSettings) {
        settings = transform(settings)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, settings.disableIcon)
            .putBoolean(KEY_FACE_UNLOCK_ISLAND, settings.unlockIsland)
            .apply()
    }
}
