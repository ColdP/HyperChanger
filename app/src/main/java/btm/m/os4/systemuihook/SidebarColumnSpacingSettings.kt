// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val SIDEBAR_COLUMN_SPACING_PREFIX = "sidebar_column_spacing_v1_"
internal const val KEY_SIDEBAR_COLUMN_SPACING = SIDEBAR_COLUMN_SPACING_PREFIX + "value"
internal const val KEY_SIDEBAR_COLUMN_SPACING_ENABLED = SIDEBAR_COLUMN_SPACING_PREFIX + "enabled"

internal data class SidebarColumnSpacingSettings(val enabled: Boolean = false, val spacing: Float = 0f) {
    fun normalized() = copy(spacing = if (spacing.isFinite()) (spacing.coerceIn(-32f, 64f) * 100f).roundToInt() / 100f else 0f)
}

internal fun readSidebarColumnSpacingSettings(p: SharedPreferences) = SidebarColumnSpacingSettings(
    p.getBoolean(KEY_SIDEBAR_COLUMN_SPACING_ENABLED, false), p.getFloat(KEY_SIDEBAR_COLUMN_SPACING, 0f),
).normalized()

private fun SidebarColumnSpacingSettings.values(): Map<String, Any> = mapOf(
    KEY_SIDEBAR_COLUMN_SPACING_ENABLED to enabled, KEY_SIDEBAR_COLUMN_SPACING to spacing,
)

internal class SidebarColumnSpacingSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSidebarColumnSpacingSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            SidebarColumnSpacingSettings().values().forEach { (key, default) ->
                if (!local.contains(key)) when (default) {
                    is Boolean -> putBoolean(key, remote.getBoolean(key, default))
                    is Float -> putFloat(key, remote.getFloat(key, default))
                }
            }
        }.apply()
        settings = readSidebarColumnSpacingSettings(local)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SidebarColumnSpacingSettings) -> SidebarColumnSpacingSettings) {
        settings = transform(readSidebarColumnSpacingSettings(local)).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SidebarColumnSpacingSettings) {
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

internal fun completeSidebarColumnSpacingPreset(values: JSONObject) {
    SidebarColumnSpacingSettings().values().forEach { (key, value) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", if (value is Boolean) "boolean" else "float").put("value", value))
    }
}
