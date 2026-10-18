// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_DISABLE_FACE_UNLOCK_ICON = "disable_face_unlock_icon"
internal const val KEY_FACE_UNLOCK_ISLAND = "face_unlock_island"
internal const val KEY_FACE_UNLOCK_FIRST_FLOAT = "face_unlock_island_first_float"
internal const val KEY_FACE_UNLOCK_KEEP_UNTIL_KEYGUARD_HIDDEN = "face_unlock_island_keep_until_keyguard_hidden"
internal const val KEY_FACE_UNLOCK_ANIMATION_STYLE = "face_unlock_island_animation_style"
internal const val KEY_UNLOCK_ALL_FOCUS = "unlock_all_focus"
internal const val KEY_UNLOCK_FOCUS_AUTH = "unlock_focus_auth"

data class LockscreenFaceUnlockSettings(
    val disableIcon: Boolean = false,
    val unlockIsland: Boolean = false,
    val firstFloat: Boolean = true,
    val keepUntilKeyguardHidden: Boolean = false,
    val animationStyle: String = "default",
    val unlockAllFocus: Boolean = false,
    val unlockFocusAuth: Boolean = false,
)

internal fun readLockscreenFaceUnlockSettings(preferences: SharedPreferences) =
    LockscreenFaceUnlockSettings(
        disableIcon = preferences.getBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, false),
        unlockIsland = preferences.getBoolean(KEY_FACE_UNLOCK_ISLAND, false),
        firstFloat = preferences.getBoolean(KEY_FACE_UNLOCK_FIRST_FLOAT, true),
        keepUntilKeyguardHidden = preferences.getBoolean(KEY_FACE_UNLOCK_KEEP_UNTIL_KEYGUARD_HIDDEN, false),
        animationStyle = preferences.getString(KEY_FACE_UNLOCK_ANIMATION_STYLE, "default")
            ?.takeIf { it in setOf("default", "lock", "lock_2") } ?: "default",
        unlockAllFocus = preferences.getBoolean(KEY_UNLOCK_ALL_FOCUS, false),
        unlockFocusAuth = preferences.getBoolean(KEY_UNLOCK_FOCUS_AUTH, false),
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
            .putBoolean(KEY_FACE_UNLOCK_FIRST_FLOAT, settings.firstFloat)
            .putBoolean(KEY_FACE_UNLOCK_KEEP_UNTIL_KEYGUARD_HIDDEN, settings.keepUntilKeyguardHidden)
            .putString(KEY_FACE_UNLOCK_ANIMATION_STYLE, settings.animationStyle)
            .putBoolean(KEY_UNLOCK_ALL_FOCUS, settings.unlockAllFocus)
            .putBoolean(KEY_UNLOCK_FOCUS_AUTH, settings.unlockFocusAuth)
            .apply()
    }
}
