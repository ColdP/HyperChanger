// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_MUSIC_LOCKSCREEN_MASTER = "lockscreen_music_master_enabled"

internal fun musicLockscreenMaster(prefs: SharedPreferences) =
    prefs.getBoolean(KEY_MUSIC_LOCKSCREEN_MASTER, true)

internal class MusicLockscreenSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    val enabled get() = musicLockscreenMaster(local)

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val value = if (local.contains(KEY_MUSIC_LOCKSCREEN_MASTER)) enabled else musicLockscreenMaster(remote)
        local.edit().putBoolean(KEY_MUSIC_LOCKSCREEN_MASTER, value).apply()
        remote.edit().putBoolean(KEY_MUSIC_LOCKSCREEN_MASTER, value).apply()
    }

    fun update(service: XposedService?, enabled: Boolean) {
        local.edit().putBoolean(KEY_MUSIC_LOCKSCREEN_MASTER, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_MUSIC_LOCKSCREEN_MASTER, enabled)?.apply()
    }
}
