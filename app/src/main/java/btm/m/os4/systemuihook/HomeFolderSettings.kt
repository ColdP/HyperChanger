// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_FOLDER_COLUMNS = "home_folder_columns"
internal const val KEY_HOME_FOLDER_CUSTOM_COLUMNS = "home_folder_custom_columns"
internal const val KEY_HOME_FOLDER_ROW_SPACING = "home_folder_row_spacing"
internal const val KEY_HOME_FOLDER_ROW_SPACING_ENABLED = "home_folder_row_spacing_enabled"
internal const val HOME_FOLDER_CUSTOM_COLUMNS = -1

internal data class HomeFolderSettings(
    val columns: Int = 0,
    val customColumns: Int = 3,
    val rowSpacing: Int = 0,
    val rowSpacingEnabled: Boolean = false,
) {
    val effectiveColumns: Int get() = if (columns == HOME_FOLDER_CUSTOM_COLUMNS) customColumns else columns
}

internal fun readHomeFolderSettings(prefs: SharedPreferences) = HomeFolderSettings(
    columns = prefs.getInt(KEY_HOME_FOLDER_COLUMNS, 0).takeIf { it == HOME_FOLDER_CUSTOM_COLUMNS || it in 4..6 } ?: 0,
    customColumns = prefs.getInt(KEY_HOME_FOLDER_CUSTOM_COLUMNS, 3).coerceIn(1, 15),
    rowSpacing = prefs.getInt(KEY_HOME_FOLDER_ROW_SPACING, 0).coerceIn(-20, 80),
    rowSpacingEnabled = prefs.getBoolean(KEY_HOME_FOLDER_ROW_SPACING_ENABLED, false),
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
        // Older remote snapshots carry only the preset. Preserve newly saved local values
        // until this version has synchronized their keys for the first time.
        settings = HomeFolderSettings(
            columns = if (remote.contains(KEY_HOME_FOLDER_COLUMNS)) readHomeFolderSettings(remote).columns else settings.columns,
            customColumns = if (remote.contains(KEY_HOME_FOLDER_CUSTOM_COLUMNS)) remote.getInt(KEY_HOME_FOLDER_CUSTOM_COLUMNS, 3).coerceIn(1, 15) else settings.customColumns,
            rowSpacing = if (remote.contains(KEY_HOME_FOLDER_ROW_SPACING)) remote.getInt(KEY_HOME_FOLDER_ROW_SPACING, 0).coerceIn(-20, 80) else settings.rowSpacing,
            rowSpacingEnabled = if (remote.contains(KEY_HOME_FOLDER_ROW_SPACING_ENABLED)) remote.getBoolean(KEY_HOME_FOLDER_ROW_SPACING_ENABLED, false) else settings.rowSpacingEnabled,
        )
        write(local, settings)
        write(remote, settings)
        stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), settings)
    }

    fun update(service: XposedService?, transform: (HomeFolderSettings) -> HomeFolderSettings): Boolean {
        val updated = transform(settings)
        settings = updated.copy(
            columns = updated.columns.takeIf { it == HOME_FOLDER_CUSTOM_COLUMNS || it in 4..6 } ?: 0,
            customColumns = updated.customColumns.coerceIn(1, 15),
            rowSpacing = updated.rowSpacing.coerceIn(-20, 80),
        )
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), settings)
    }

    private fun write(prefs: SharedPreferences, value: HomeFolderSettings) {
        prefs.edit()
            .putInt(KEY_HOME_FOLDER_COLUMNS, value.columns)
            .putInt(KEY_HOME_FOLDER_CUSTOM_COLUMNS, value.customColumns)
            .putInt(KEY_HOME_FOLDER_ROW_SPACING, value.rowSpacing)
            .putBoolean(KEY_HOME_FOLDER_ROW_SPACING_ENABLED, value.rowSpacingEnabled)
            .apply()
    }
}
