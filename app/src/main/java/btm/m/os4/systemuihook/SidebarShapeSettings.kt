// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val SIDEBAR_SHAPE_PREFIX = "sidebar_shape_v1_"
internal const val KEY_SIDEBAR_RADIUS = SIDEBAR_SHAPE_PREFIX + "radius_offset"
internal const val KEY_SIDEBAR_RADIUS_ENABLED = SIDEBAR_SHAPE_PREFIX + "radius_enabled"

internal const val KEY_SIDEBAR_CAPSULE_BUTTON = SIDEBAR_SHAPE_PREFIX + "capsule_button"
internal const val KEY_SIDEBAR_CAPSULE_ADVANCED_MATERIAL = SIDEBAR_SHAPE_PREFIX + "capsule_advanced_material"

internal data class SidebarShapeSettings(
    val radiusEnabled: Boolean = false, val radius: Float = 0f, val capsuleButton: Boolean = false,
    val advancedMaterial: Boolean = false,
) {
    fun normalized() = copy(radius = if (radius.isFinite()) (radius.coerceIn(-32f, 64f) * 100f).roundToInt() / 100f else 0f)
}

internal fun readSidebarShapeSettings(p: SharedPreferences) = SidebarShapeSettings(
    p.getBoolean(KEY_SIDEBAR_RADIUS_ENABLED, false), p.getFloat(KEY_SIDEBAR_RADIUS, 0f),
    p.getBoolean(KEY_SIDEBAR_CAPSULE_BUTTON, false),
    p.getBoolean(KEY_SIDEBAR_CAPSULE_ADVANCED_MATERIAL, false),
).normalized()

private fun SidebarShapeSettings.values(): Map<String, Any> = mapOf(
    KEY_SIDEBAR_RADIUS_ENABLED to radiusEnabled, KEY_SIDEBAR_RADIUS to radius,
    KEY_SIDEBAR_CAPSULE_BUTTON to capsuleButton,
    KEY_SIDEBAR_CAPSULE_ADVANCED_MATERIAL to advancedMaterial,
)

internal class SidebarShapeSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSidebarShapeSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            SidebarShapeSettings().values().forEach { (key, default) ->
                if (!local.contains(key)) when (default) {
                    is Boolean -> putBoolean(key, remote.getBoolean(key, default))
                    is Float -> putFloat(key, remote.getFloat(key, default))
                }
            }
        }.apply()
        settings = readSidebarShapeSettings(local)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SidebarShapeSettings) -> SidebarShapeSettings) {
        settings = transform(readSidebarShapeSettings(local)).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SidebarShapeSettings) {
        p.edit().apply {
            value.values().forEach { (key, item) ->
                when (item) {
                    is Boolean -> putBoolean(key, item)
                    is Float -> putFloat(key, item)
                }
            }
        }.apply()
    }
}

internal fun completeSidebarShapePreset(values: JSONObject) {
    SidebarShapeSettings().values().forEach { (key, value) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", if (value is Boolean) "boolean" else "float").put("value", value))
    }
}
