// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_APP_NAVIGATION_GRID_MODE = "app_navigation_grid_mode"

internal class AppNavigationLayoutSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var gridMode by mutableStateOf(local.getBoolean(KEY_APP_NAVIGATION_GRID_MODE, true))
        private set

    fun observe(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_NAVIGATION_GRID_MODE) {
                gridMode = local.getBoolean(KEY_APP_NAVIGATION_GRID_MODE, true)
            }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        gridMode = local.getBoolean(KEY_APP_NAVIGATION_GRID_MODE, true)
        return { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val source = if (local.contains(KEY_APP_NAVIGATION_GRID_MODE)) local else remote
        gridMode = source.getBoolean(KEY_APP_NAVIGATION_GRID_MODE, true)
        write(local, gridMode)
        write(remote, gridMode)
    }

    fun update(service: XposedService?, value: Boolean) {
        gridMode = value
        write(local, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(preferences: SharedPreferences, value: Boolean) {
        preferences.edit().putBoolean(KEY_APP_NAVIGATION_GRID_MODE, value).apply()
    }
}

internal fun completeAppNavigationLayoutPreset(values: JSONObject) {
    val encoded = values.optJSONObject(KEY_APP_NAVIGATION_GRID_MODE)
    if (encoded == null) {
        values.put(KEY_APP_NAVIGATION_GRID_MODE, JSONObject().put("type", "boolean").put("value", true))
    } else {
        require(encoded.getString("type") == "boolean" && encoded.get("value") is Boolean)
    }
}
