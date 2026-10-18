// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_MULTI_SPLIT = "home_multi_split_enabled"
internal val homeSixSplitTargetDevice: Boolean
    get() = Build.DEVICE == "lhasa" || Build.DEVICE == "yingtian"

internal data class HomeMultiSplitSettings(val enabled: Boolean = false)

internal fun readHomeMultiSplitSettings(prefs: SharedPreferences) = HomeMultiSplitSettings(
    enabled = prefs.getBoolean(KEY_HOME_MULTI_SPLIT, false) && homeSixSplitTargetDevice,
)

internal class HomeMultiSplitSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings = readHomeMultiSplitSettings(local)
        private set

    fun reload() {
        settings = readHomeMultiSplitSettings(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_HOME_MULTI_SPLIT)) {
            val remoteSettings = readHomeMultiSplitSettings(remote)
            settings = remoteSettings
        }
        write(local, settings)
        write(remote, settings)
        stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), multiSplitSettings = settings)
    }

    fun update(service: XposedService?, transform: (HomeMultiSplitSettings) -> HomeMultiSplitSettings): Boolean {
        val updated = transform(settings)
        settings = updated.copy(enabled = updated.enabled && homeSixSplitTargetDevice)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), multiSplitSettings = settings)
    }

    private fun write(prefs: SharedPreferences, value: HomeMultiSplitSettings) {
        prefs.edit().putBoolean(KEY_HOME_MULTI_SPLIT, value.enabled).apply()
    }
}

internal fun readLocalHomeMultiSplitSettings(context: Context): HomeMultiSplitSettings =
    readHomeMultiSplitSettings(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
