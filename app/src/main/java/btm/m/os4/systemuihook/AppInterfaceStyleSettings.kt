// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal enum class AppInterfaceStyle(val id: String, val label: String) {
    HYPER("hyper", "Hyper"), COLOR("color", "Color");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.id == value } ?: HYPER
    }
}

/** App-only style state never enters HookSettings or its serialization. */
internal object AppInterfaceStyleSettings {
    const val KEY = "app_interface_style"
    fun load(p: SharedPreferences) = AppInterfaceStyle.fromPreference(p.getString(KEY, null))
    fun save(p: SharedPreferences, service: XposedService?, style: AppInterfaceStyle) {
        write(p, style)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, style) }
    }
    fun sync(local: SharedPreferences, remote: SharedPreferences) {
        val style = load(if (local.contains(KEY)) local else remote)
        write(local, style)
        write(remote, style)
    }
    private fun write(p: SharedPreferences, style: AppInterfaceStyle) {
        p.edit().putString(KEY, style.id).apply()
    }
}

internal val LocalAppInterfaceStyle = staticCompositionLocalOf { AppInterfaceStyle.HYPER }

@Composable
internal fun rememberAppInterfaceStyle(): AppInterfaceStyle {
    val context = LocalContext.current
    val p = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var style by remember(p) { mutableStateOf(AppInterfaceStyleSettings.load(p)) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AppInterfaceStyleSettings.KEY) style = AppInterfaceStyleSettings.load(p)
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        style = AppInterfaceStyleSettings.load(p)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return style
}

@Composable
internal fun AppInterfaceStylePreference(service: XposedService?) {
    val context = LocalContext.current
    OverlayDropdownPreference(
        title = tr("appInterfaceStyle", "界面风格"),
        items = AppInterfaceStyle.entries.map { tr(it.label, it.label) },
        selectedIndex = LocalAppInterfaceStyle.current.ordinal,
        onSelectedIndexChange = { index ->
            AppInterfaceStyleSettings.save(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
                service, AppInterfaceStyle.entries[index])
        },
    )
}

internal fun completeAppInterfaceStylePreset(values: JSONObject) {
    val key = AppInterfaceStyleSettings.KEY
    if (!values.has(key)) values.put(key, JSONObject().put("type", "string").put("value", AppInterfaceStyle.HYPER.id))
    else {
        val encoded = values.getJSONObject(key)
        require(encoded.getString("type") == "string" && encoded.get("value") is String)
        require(AppInterfaceStyle.entries.any { it.id == encoded.getString("value") })
    }
}
