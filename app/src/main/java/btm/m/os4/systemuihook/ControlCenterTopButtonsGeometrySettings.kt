// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val CC_TOP_BUTTONS_GEOMETRY_PREFIX = "control_center_top_buttons_geometry_"

internal enum class CcButtonGeometryParameter(val suffix: String, val topKey: String, val min: Float, val max: Float, val default: Float) {
    ICON_SCALE("icon_scale", CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX + "icon_scale", .5f, 2f, 1f),
    PURE_RADIUS("pure_radius", CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX + "pure_radius", 0f, 22f, 22f),
    ADVANCED_RADIUS("advanced_radius", CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX + "advanced_radius", 0f, 22f, 22f),
    SOFT_RADIUS("soft_radius", CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX + "soft_radius", 0f, 22f, 22f),
    SPACING("spacing_dp", CC_TOP_BUTTONS_GEOMETRY_PREFIX + "top_spacing_dp", -64f, 96f, 0f);

    val bottomKey get() = CC_TOP_BUTTONS_GEOMETRY_PREFIX + "bottom_" + suffix
}

internal data class CcButtonGeometry(
    val iconScale: Float, val pureRadius: Float, val advancedRadius: Float, val softRadius: Float, val spacingDp: Int,
)

internal fun controlCenterTopButtonsSideOffsetPx(spacingDp: Int, density: Float): Int =
    (-spacingDp.coerceIn(-64, 96) * density / 2f).let { kotlin.math.round(it).toInt() }

internal fun readControlCenterTopButtonsGeometry(p: SharedPreferences, bottom: Boolean = readControlCenterTopButtonsPlacement(p).atBottom): CcButtonGeometry {
    val top = readControlCenterTopButtonsGlassSettings(p)
    fun value(parameter: CcButtonGeometryParameter, inherited: Float): Float =
        (if (bottom && p.contains(parameter.bottomKey)) p.getFloat(parameter.bottomKey, inherited) else inherited)
            .coerceIn(parameter.min, parameter.max)
    return CcButtonGeometry(
        value(CcButtonGeometryParameter.ICON_SCALE, top.iconScale),
        value(CcButtonGeometryParameter.PURE_RADIUS, top.pureRadius),
        value(CcButtonGeometryParameter.ADVANCED_RADIUS, top.advancedRadius),
        value(CcButtonGeometryParameter.SOFT_RADIUS, top.softRadius),
        value(CcButtonGeometryParameter.SPACING, p.getFloat(CcButtonGeometryParameter.SPACING.topKey, 0f)).toInt(),
    )
}

/** Optional bottom keys preserve inheritance separately for every geometry parameter. */
internal class ControlCenterTopButtonsGeometrySettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    fun read(bottom: Boolean) = readControlCenterTopButtonsGeometry(local, bottom)

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        val editor = remote.edit()
        CcButtonGeometryParameter.entries.forEach { parameter ->
            // The existing glass store owns top icon/radius synchronization.
            val keys = listOf(parameter.bottomKey) + if (parameter == CcButtonGeometryParameter.SPACING) listOf(parameter.topKey) else emptyList()
            keys.forEach { key ->
                if (local.contains(key)) editor.putFloat(key, local.getFloat(key, parameter.default).coerceIn(parameter.min, parameter.max))
                else editor.remove(key)
            }
        }
        editor.apply()
    }

    fun update(service: XposedService?, bottom: Boolean, parameter: CcButtonGeometryParameter, value: Float) {
        val key = if (bottom) parameter.bottomKey else parameter.topKey
        val normalized = value.coerceIn(parameter.min, parameter.max)
        local.edit().putFloat(key, normalized).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putFloat(key, normalized)?.apply()
    }
}

internal fun completeControlCenterTopButtonsGeometryPreset(values: JSONObject) {
    val key = CcButtonGeometryParameter.SPACING.topKey
    if (!values.has(key)) values.put(key, JSONObject().put("type", "float").put("value", 0f))
    // Missing bottom overrides mean inherit; never materialize defaults for them.
}
