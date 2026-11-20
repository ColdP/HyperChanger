// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val WIDGET_SETTINGS_PREFIX = "widgets_v1_"
internal const val KEY_WIDGET_WHITELIST = WIDGET_SETTINGS_PREFIX + "unlock_hyperos"
internal const val KEY_WIDGET_STACK = WIDGET_SETTINGS_PREFIX + "android_stack"
internal const val KEY_WIDGET_GLASS = WIDGET_SETTINGS_PREFIX + "android_glass"

internal data class WidgetSettings(
    val unlockHyperOs: Boolean = false,
    val androidStack: Boolean = false,
    val androidGlass: Boolean = false,
)

internal fun readWidgetSettings(p: SharedPreferences) = WidgetSettings(
    p.getBoolean(KEY_WIDGET_WHITELIST, false),
    p.getBoolean(KEY_WIDGET_STACK, false),
    p.getBoolean(KEY_WIDGET_GLASS, false),
)

private fun WidgetSettings.values() = mapOf(
    KEY_WIDGET_WHITELIST to unlockHyperOs,
    KEY_WIDGET_STACK to androidStack,
    KEY_WIDGET_GLASS to androidGlass,
)

internal class WidgetSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    fun read() = readWidgetSettings(local)
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        local.edit().apply {
            WidgetSettings().values().keys.forEach { key ->
                if (!local.contains(key)) (remote.all[key] as? Boolean)?.let { putBoolean(key, it) }
            }
        }.apply()
        write(local, read())
        write(remote, read())
        if (read() != WidgetSettings()) stageNative()
    }
    fun update(service: XposedService?, transform: (WidgetSettings) -> WidgetSettings) {
        val value = transform(read())
        write(local, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
        stageNative()
    }
    private fun stageNative() = stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local))
    private fun write(p: SharedPreferences, value: WidgetSettings) {
        p.edit().apply { value.values().forEach { (key, value) -> putBoolean(key, value) } }.apply()
    }
}

internal fun completeWidgetPreset(values: JSONObject) {
    WidgetSettings().values().forEach { (key, default) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", default))
    }
}
