// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX = "control_center_top_buttons_v1_"
internal const val CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX = "control_center_top_buttons_glass_v1_"
private const val KEY_CC_TOP_BUTTONS_V1_ENABLED = "control_center_top_buttons_v1_enabled"
private const val KEY_CC_TOP_BUTTONS_V1_LANDSCAPE = "control_center_top_buttons_v1_landscape"
private const val KEY_CC_TOP_BUTTONS_V1_ICON_SCALE = "control_center_top_buttons_v1_icon_scale"
private const val KEY_CC_TOP_BUTTONS_V1_MODE = "control_center_top_buttons_v1_mode"
private const val KEY_CC_TOP_BUTTONS_V1_PURE_COLOR = "control_center_top_buttons_v1_pure_color"
private const val KEY_CC_TOP_BUTTONS_V1_PURE_RADIUS = "control_center_top_buttons_v1_pure_radius"
private const val KEY_CC_TOP_BUTTONS_V1_ADVANCED_COLOR = "control_center_top_buttons_v1_advanced_color"
private const val KEY_CC_TOP_BUTTONS_V1_ADVANCED_RADIUS = "control_center_top_buttons_v1_advanced_radius"
private const val KEY_CC_TOP_BUTTONS_V1_ADVANCED_OPACITY = "control_center_top_buttons_v1_advanced_opacity"
private const val KEY_CC_TOP_BUTTONS_V1_ADVANCED_BLUR = "control_center_top_buttons_v1_advanced_blur"
private const val KEY_CC_TOP_BUTTONS_V1_ADVANCED_HIGHLIGHT = "control_center_top_buttons_v1_advanced_highlight"
private const val KEY_CC_TOP_BUTTONS_V1_SOFT_RADIUS = "control_center_top_buttons_v1_soft_radius"

internal const val CONTROL_CENTER_TOP_BUTTONS_MODE_NONE = 0
internal const val CONTROL_CENTER_TOP_BUTTONS_MODE_PURE = 1
internal const val CONTROL_CENTER_TOP_BUTTONS_MODE_ADVANCED = 2
internal const val CONTROL_CENTER_TOP_BUTTONS_MODE_SOFT_GLASS = 3
private const val KEY_CC_TOP_BUTTONS_V1_USE_GLOBAL_GLASS =
    CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "use_global"

internal data class ControlCenterTopButtonsGlassSettings(
    val enabled: Boolean = false,
    val showInLandscape: Boolean = false,
    val iconScale: Float = 1f,
    val backgroundMode: Int = CONTROL_CENTER_TOP_BUTTONS_MODE_NONE,
    val pureColor: Int = 0x73FFFFFF,
    val pureRadius: Float = 22f,
    val advancedColor: Int = 0xFFFFFFFF.toInt(),
    val advancedRadius: Float = 22f,
    val advancedOpacity: Int = 14,
    val advancedBlur: Int = 40,
    val advancedHighlight: Boolean = false,
    val softRadius: Float = 22f,
    val useGlobal: Boolean = false,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
)

internal fun readControlCenterTopButtonsGlassSettings(p: SharedPreferences): ControlCenterTopButtonsGlassSettings =
    ControlCenterTopButtonsGlassSettings(
        enabled = p.getBoolean(KEY_CC_TOP_BUTTONS_V1_ENABLED, p.getBoolean("add_control_center_top_buttons", false)),
        showInLandscape = p.getBoolean(KEY_CC_TOP_BUTTONS_V1_LANDSCAPE, p.getBoolean("show_control_center_top_buttons_in_landscape", false)),
        iconScale = p.getFloat(KEY_CC_TOP_BUTTONS_V1_ICON_SCALE, p.getFloat("control_center_top_buttons_icon_scale", 1f)).coerceIn(.5f, 2f),
        backgroundMode = p.getInt(KEY_CC_TOP_BUTTONS_V1_MODE, p.getInt("control_center_top_buttons_background_mode", 0)).coerceIn(0, 3),
        pureColor = p.getInt(KEY_CC_TOP_BUTTONS_V1_PURE_COLOR, p.getInt("control_center_top_buttons_pure_color", 0x73FFFFFF)),
        pureRadius = p.getFloat(KEY_CC_TOP_BUTTONS_V1_PURE_RADIUS, p.getFloat("control_center_top_buttons_pure_background_radius", 22f)).coerceIn(0f, 22f),
        advancedColor = p.getInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_COLOR, p.getInt("control_center_top_buttons_advanced_material_color", 0xFFFFFFFF.toInt())),
        advancedRadius = p.getFloat(KEY_CC_TOP_BUTTONS_V1_ADVANCED_RADIUS, p.getFloat("control_center_top_buttons_advanced_material_background_radius", 22f)).coerceIn(0f, 22f),
        advancedOpacity = p.getInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_OPACITY, p.getInt("control_center_top_buttons_advanced_material_opacity", 14)).coerceIn(0, 100),
        advancedBlur = p.getInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_BLUR, p.getInt("control_center_top_buttons_advanced_material_blur_radius", 40)).coerceIn(0, 40),
        advancedHighlight = p.getBoolean(KEY_CC_TOP_BUTTONS_V1_ADVANCED_HIGHLIGHT, p.getBoolean("control_center_top_buttons_advanced_material_highlight", false)),
        softRadius = p.getFloat(KEY_CC_TOP_BUTTONS_V1_SOFT_RADIUS, p.getFloat("control_center_top_buttons_soft_glass_background_radius", 22f)).coerceIn(0f, 22f),
        useGlobal = p.getBoolean(KEY_CC_TOP_BUTTONS_V1_USE_GLOBAL_GLASS, false),
        glass = readSoftGlassSettings(p, CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX),
    )

internal fun resolveControlCenterTopButtonsGlass(p: SharedPreferences): SoftGlassSettings =
    readControlCenterTopButtonsGlassSettings(p).let {
        (if (it.useGlobal) readSoftGlassSettings(p) else it.glass).copy(enabled = true)
    }

internal class ControlCenterTopButtonsGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readControlCenterTopButtonsGlassSettings(local)
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            remote.all.forEach { (key, value) ->
                if (!local.contains(key) && (key.startsWith(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX) ||
                    key.startsWith(CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX) ||
                    key.startsWith("control_center_top_buttons_") ||
                    key == "add_control_center_top_buttons" || key == "show_control_center_top_buttons_in_landscape")
                ) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }
        }.apply()
        settings = readControlCenterTopButtonsGlassSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (ControlCenterTopButtonsGlassSettings) -> ControlCenterTopButtonsGlassSettings) {
        settings = transform(readControlCenterTopButtonsGlassSettings(local)).let { it.copy(glass = it.glass.normalized()) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(KEY_CC_TOP_BUTTONS_V1_ENABLED, settings.enabled)
            putBoolean(KEY_CC_TOP_BUTTONS_V1_LANDSCAPE, settings.showInLandscape)
            putFloat(KEY_CC_TOP_BUTTONS_V1_ICON_SCALE, settings.iconScale)
            putInt(KEY_CC_TOP_BUTTONS_V1_MODE, settings.backgroundMode)
            putInt(KEY_CC_TOP_BUTTONS_V1_PURE_COLOR, settings.pureColor)
            putFloat(KEY_CC_TOP_BUTTONS_V1_PURE_RADIUS, settings.pureRadius)
            putInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_COLOR, settings.advancedColor)
            putFloat(KEY_CC_TOP_BUTTONS_V1_ADVANCED_RADIUS, settings.advancedRadius)
            putInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_OPACITY, settings.advancedOpacity)
            putInt(KEY_CC_TOP_BUTTONS_V1_ADVANCED_BLUR, settings.advancedBlur)
            putBoolean(KEY_CC_TOP_BUTTONS_V1_ADVANCED_HIGHLIGHT, settings.advancedHighlight)
            putFloat(KEY_CC_TOP_BUTTONS_V1_SOFT_RADIUS, settings.softRadius)
            putBoolean(KEY_CC_TOP_BUTTONS_V1_USE_GLOBAL_GLASS, settings.useGlobal)
            putBoolean(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "enabled", settings.glass.enabled)
            putInt(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "refraction_blur", settings.glass.refractionBlur)
            putInt(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "blur_percent", settings.glass.backgroundBlur)
            putInt(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "scale_percent", settings.glass.backgroundScale)
            putInt(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "opacity_percent", settings.glass.backgroundOpacity)
            softGlassParameters.forEach { definition ->
                putInt(CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key,
                    settings.glass.adjustments[definition.key] ?: if (definition.key == "darker") -100 else 0)
            }
        }.apply()
    }
}
