// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/** Shared preference contract used by the rear-screen music whitelist hook. */
const val MUSIC_CONTROLS_WHITELIST_APPS = "music_controls_whitelist_apps"
const val HOOK_MUSIC_CONTROLS_WHITELIST = "enable_music_controls_whitelist_hook"
const val HOOK_MUSIC_CONTROLS_FORCE_UPDATE = "enable_music_controls_force_update"

class MusicControlSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var apps: Set<String> = local.getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet()).orEmpty()
        private set

    fun reload() {
        apps = local.getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet()).orEmpty()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteApps = remote.getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, null)
        if (remoteApps != null) apps = remoteApps
        write(remote)
        write(local)
    }

    fun update(service: XposedService?, next: Set<String>) {
        apps = next.toSet()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putStringSet(MUSIC_CONTROLS_WHITELIST_APPS, apps)
            .putBoolean(HOOK_MUSIC_CONTROLS_WHITELIST, true)
            .apply()
    }
}
