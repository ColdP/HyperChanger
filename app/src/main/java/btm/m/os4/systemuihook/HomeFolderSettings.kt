// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_FOLDER_COLUMNS = "home_folder_columns"

internal data class HomeFolderSettings(val columns: Int = 0)

internal fun readHomeFolderSettings(prefs: SharedPreferences) = HomeFolderSettings(
    columns = prefs.getInt(KEY_HOME_FOLDER_COLUMNS, 0).takeIf { it in 4..6 } ?: 0,
)

internal class HomeFolderSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings = readHomeFolderSettings(local)
        private set

    fun reload() {
        settings = readHomeFolderSettings(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_HOME_FOLDER_COLUMNS)) settings = readHomeFolderSettings(remote)
        write(local, settings)
        write(remote, settings)
        stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), settings)
    }

    fun update(service: XposedService?, transform: (HomeFolderSettings) -> HomeFolderSettings): Boolean {
        val updated = transform(settings)
        settings = updated.copy(columns = updated.columns.takeIf { it in 4..6 } ?: 0)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), settings)
    }

    private fun write(prefs: SharedPreferences, value: HomeFolderSettings) {
        prefs.edit().putInt(KEY_HOME_FOLDER_COLUMNS, value.columns).apply()
    }
}
