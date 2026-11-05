// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val SIDEBAR_APPEARANCE_PREFIX = "sidebar_appearance_v1_"
internal const val KEY_SIDEBAR_DUAL_ROW = SIDEBAR_APPEARANCE_PREFIX + "dual_row_apps"
internal const val KEY_SIDEBAR_HEIGHT_SCALE = SIDEBAR_APPEARANCE_PREFIX + "height_scale"
internal const val KEY_SIDEBAR_WIDTH_SCALE = SIDEBAR_APPEARANCE_PREFIX + "width_scale"
internal const val KEY_SIDEBAR_ICON_SCALE = SIDEBAR_APPEARANCE_PREFIX + "icon_scale"

internal const val SIDEBAR_APPEARANCE_DEFAULT = 0f
internal const val SIDEBAR_APPEARANCE_MIN = -100f
internal const val SIDEBAR_APPEARANCE_MAX = 100f

internal data class SidebarAppearanceSettings(
    val dualRowApps: Boolean = false,
    val heightScaleEnabled: Boolean = false,
    val heightScale: Float = SIDEBAR_APPEARANCE_DEFAULT,
    val widthScaleEnabled: Boolean = false,
    val widthScale: Float = SIDEBAR_APPEARANCE_DEFAULT,
    val iconScaleEnabled: Boolean = false,
    val iconScale: Float = SIDEBAR_APPEARANCE_DEFAULT,
) {
    val anyEnabled get() = dualRowApps || heightScaleEnabled || widthScaleEnabled || iconScaleEnabled
    fun normalized() = copy(
        heightScale = heightScale.normalizeSidebarAppearancePercent(),
        widthScale = widthScale.normalizeSidebarAppearancePercent(),
        iconScale = iconScale.normalizeSidebarAppearancePercent(),
    )
}

private fun Float.normalizeSidebarAppearancePercent(): Float =
    if (isFinite()) (coerceIn(SIDEBAR_APPEARANCE_MIN, SIDEBAR_APPEARANCE_MAX) * 100f).roundToInt() / 100f
    else SIDEBAR_APPEARANCE_DEFAULT

internal fun readSidebarAppearanceSettings(p: SharedPreferences) = SidebarAppearanceSettings(
    dualRowApps = p.getBoolean(KEY_SIDEBAR_DUAL_ROW, false),
    heightScaleEnabled = p.getBoolean(KEY_SIDEBAR_HEIGHT_SCALE + "_enabled", false),
    heightScale = p.getFloat(KEY_SIDEBAR_HEIGHT_SCALE, SIDEBAR_APPEARANCE_DEFAULT),
    widthScale = p.getFloat(KEY_SIDEBAR_WIDTH_SCALE, SIDEBAR_APPEARANCE_DEFAULT),
    widthScaleEnabled = p.getBoolean(KEY_SIDEBAR_WIDTH_SCALE + "_enabled", false),
    iconScaleEnabled = p.getBoolean(KEY_SIDEBAR_ICON_SCALE + "_enabled", false),
    iconScale = p.getFloat(KEY_SIDEBAR_ICON_SCALE, SIDEBAR_APPEARANCE_DEFAULT),
).normalized()

private fun SidebarAppearanceSettings.values(): Map<String, Any> = mapOf(
    KEY_SIDEBAR_DUAL_ROW to dualRowApps,
    KEY_SIDEBAR_HEIGHT_SCALE + "_enabled" to heightScaleEnabled,
    KEY_SIDEBAR_HEIGHT_SCALE to heightScale,
    KEY_SIDEBAR_WIDTH_SCALE to widthScale,
    KEY_SIDEBAR_WIDTH_SCALE + "_enabled" to widthScaleEnabled,
    KEY_SIDEBAR_ICON_SCALE + "_enabled" to iconScaleEnabled,
    KEY_SIDEBAR_ICON_SCALE to iconScale,
)

internal class SidebarAppearanceSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSidebarAppearanceSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            SidebarAppearanceSettings().values().forEach { (key, default) ->
                if (!local.contains(key)) when (default) {
                    is Boolean -> putBoolean(key, remote.getBoolean(key, default))
                    is Float -> putFloat(key, remote.getFloat(key, default))
                }
            }
        }.apply()
        settings = readSidebarAppearanceSettings(local)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SidebarAppearanceSettings) -> SidebarAppearanceSettings) {
        settings = transform(readSidebarAppearanceSettings(local)).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SidebarAppearanceSettings) {
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

internal fun completeSidebarAppearancePreset(values: JSONObject) {
    SidebarAppearanceSettings().values().forEach { (key, value) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", if (value is Boolean) "boolean" else "float").put("value", value))
    }
}
