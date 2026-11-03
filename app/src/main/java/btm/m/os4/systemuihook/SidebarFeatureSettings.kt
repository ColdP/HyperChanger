// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val SIDEBAR_FEATURE_PREFIX = "sidebar_feature_v1_"
internal const val KEY_REMOVE_VIDEO_TEMP_LIMIT = SIDEBAR_FEATURE_PREFIX + "remove_video_temp_limit"
internal const val KEY_REMOVE_HOTSPOT_TEMP_LIMIT = SIDEBAR_FEATURE_PREFIX + "remove_hotspot_temp_limit"

internal data class SidebarFeatureSettings(
    val removeVideoTemperatureLimit: Boolean = false,
    val removeHotspotTemperatureLimit: Boolean = false,
)

internal fun readSidebarFeatureSettings(p: SharedPreferences) = SidebarFeatureSettings(
    p.getBoolean(KEY_REMOVE_VIDEO_TEMP_LIMIT, false),
    p.getBoolean(KEY_REMOVE_HOTSPOT_TEMP_LIMIT, false),
)

private fun SidebarFeatureSettings.values() = mapOf(
    KEY_REMOVE_VIDEO_TEMP_LIMIT to removeVideoTemperatureLimit,
    KEY_REMOVE_HOTSPOT_TEMP_LIMIT to removeHotspotTemperatureLimit,
)

internal class SidebarFeatureSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSidebarFeatureSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            SidebarFeatureSettings().values().forEach { (key, default) ->
                if (!local.contains(key)) putBoolean(key, remote.getBoolean(key, default))
            }
        }.apply()
        settings = readSidebarFeatureSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (SidebarFeatureSettings) -> SidebarFeatureSettings) {
        settings = transform(readSidebarFeatureSettings(local))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply { settings.values().forEach { (key, value) -> putBoolean(key, value) } }.apply()
    }
}

internal fun completeSidebarFeaturePreset(values: JSONObject) {
    SidebarFeatureSettings().values().forEach { (key, default) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", default))
    }
}
