// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val ASSISTANT_WIDGET_GLASS_PREFIX = "widget_glass_assistant_v1_"

internal data class WidgetGlassSettings(
    val useGlobal: Boolean = true,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
)

internal fun readWidgetGlassSettings(p: SharedPreferences) = WidgetGlassSettings(
    useGlobal = p.getBoolean(ASSISTANT_WIDGET_GLASS_PREFIX + "use_global", true),
    glass = readSoftGlassSettings(p, ASSISTANT_WIDGET_GLASS_PREFIX),
)

internal fun resolveWidgetGlass(local: WidgetGlassSettings, global: SoftGlassSettings): SoftGlassSettings =
    (if (local.useGlobal) global else local.glass).copy(
        enabled = local.glass.enabled,
        backgroundBlur = 100, backgroundScale = 100, backgroundOpacity = 100,
    )

private fun WidgetGlassSettings.values(): Map<String, Any> = buildMap {
    put(ASSISTANT_WIDGET_GLASS_PREFIX + "use_global", useGlobal)
    put(ASSISTANT_WIDGET_GLASS_PREFIX + "enabled", glass.enabled)
    put(ASSISTANT_WIDGET_GLASS_PREFIX + "refraction_blur", glass.refractionBlur)
    softGlassParameters.forEach {
        put(ASSISTANT_WIDGET_GLASS_PREFIX + if (it.key == "edge_feather") "edge_feather_inverse" else it.key,
            glass.adjustments[it.key] ?: if (it.key == "darker") -100 else 0)
    }
}

internal class WidgetGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    fun read() = readWidgetGlassSettings(local)

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            WidgetGlassSettings().values().keys.forEach { key ->
                if (!local.contains(key)) when (val value = remote.all[key]) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.apply()
        val value = read()
        write(local, value)
        write(remote, value)
    }

    fun update(service: XposedService?, transform: (WidgetGlassSettings) -> WidgetGlassSettings) {
        val value = transform(read()).let { it.copy(glass = it.glass.normalized()) }
        write(local, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(p: SharedPreferences, value: WidgetGlassSettings) {
        p.edit().apply {
            value.values().forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.apply()
    }
}

internal fun completeWidgetGlassPreset(values: JSONObject) {
    WidgetGlassSettings().values().forEach { (key, default) ->
        if (!values.has(key)) values.put(key, JSONObject()
            .put("type", if (default is Boolean) "boolean" else "int").put("value", default))
    }
}
