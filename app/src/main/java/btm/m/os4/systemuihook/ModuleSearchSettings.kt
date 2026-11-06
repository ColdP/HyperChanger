// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** App search preferences stay outside HookSettings and its serialization. */
internal object ModuleSearchSettings {
    const val KEY_INCLUDE_MODULE_SETTINGS = "app_search_include_module_settings"
    const val DEFAULT_INCLUDE_MODULE_SETTINGS = true

    fun load(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_INCLUDE_MODULE_SETTINGS, DEFAULT_INCLUDE_MODULE_SETTINGS)

    fun save(local: SharedPreferences, service: XposedService?, value: Boolean) {
        local.edit().putBoolean(KEY_INCLUDE_MODULE_SETTINGS, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_INCLUDE_MODULE_SETTINGS, value)?.apply()
    }

    fun sync(local: SharedPreferences, remote: SharedPreferences) {
        // A local value can include edits made while the framework was disconnected.
        val source = if (local.contains(KEY_INCLUDE_MODULE_SETTINGS)) local else remote
        val value = load(source)
        local.edit().putBoolean(KEY_INCLUDE_MODULE_SETTINGS, value).apply()
        remote.edit().putBoolean(KEY_INCLUDE_MODULE_SETTINGS, value).apply()
    }
}

@Composable
internal fun rememberSearchIncludesModuleSettings(): Boolean {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var enabled by remember(preferences) { mutableStateOf(ModuleSearchSettings.load(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ModuleSearchSettings.KEY_INCLUDE_MODULE_SETTINGS) {
                enabled = ModuleSearchSettings.load(preferences)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        enabled = ModuleSearchSettings.load(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun ModuleSearchSettingsPreference(service: XposedService?) {
    val context = LocalContext.current
    val enabled = rememberSearchIncludesModuleSettings()
    SwitchPreference(
        title = tr("searchIncludeModuleSettings", "搜索结果展示 HyperChanger 设置项"),
        checked = enabled,
        onCheckedChange = { value ->
            ModuleSearchSettings.save(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE), service, value)
        },
    )
}

/** JSON/ZIP export, preview and import share this default and strict type validation. */
internal fun completeModuleSearchPreset(values: JSONObject) {
    val key = ModuleSearchSettings.KEY_INCLUDE_MODULE_SETTINGS
    if (!values.has(key)) {
        values.put(key, JSONObject().put("type", "boolean")
            .put("value", ModuleSearchSettings.DEFAULT_INCLUDE_MODULE_SETTINGS))
    } else {
        val encoded = values.getJSONObject(key)
        require(encoded.getString("type") == "boolean" && encoded.get("value") is Boolean)
    }
}
