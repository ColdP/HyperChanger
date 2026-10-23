// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_ENABLED = "lockscreen_shortcut_background_enabled"
internal const val KEY_LOCKSCREEN_SHORTCUT_USE_GLOBAL_GLASS = "lockscreen_shortcut_use_global_glass"
internal const val KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE = "lockscreen_shortcut_gesture_mode"
internal const val KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX = "lockscreen_shortcut_v2_"
private const val KEY_SHORTCUT_GLASS_PREFIX = "lockscreen_shortcut_v2_glass_"

internal data class LockscreenShortcutBackgroundSettings(
    val enabled: Boolean,
    val useGlobalGlass: Boolean,
    val gestureMode: Int,
    val backgroundMode: Int,
    val circleRadius: Float,
    val radiusEnabled: Boolean,
    val radius: Float,
    val spacingEnabled: Boolean,
    val spacing: Float,
    val iconSizeEnabled: Boolean,
    val iconSize: Float,
    val pureColor: Int,
    val advancedColor: Int,
    val advancedOpacity: Int,
    val advancedBlur: Int,
    val advancedHighlight: Boolean,
    val glass: SoftGlassSettings,
) {
    val effectiveBackgroundMode: Int get() = if (enabled) backgroundMode else 0
}

internal fun readLockscreenShortcutBackgroundSettings(preferences: SharedPreferences): LockscreenShortcutBackgroundSettings =
    LockscreenShortcutBackgroundSettingsStore.readSettings(preferences)

internal class LockscreenShortcutBackgroundSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    )

    var settings = readSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        // Recover missing keys independently. A gesture-only local edit must not be
        // overwritten just because the background switch has never been saved.
        val editor = local.edit()
        remote.all.forEach { (key, value) ->
            if (!local.contains(key) && (key.startsWith("lockscreen_shortcut_") || key.startsWith("shortcut_"))) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
        }
        editor.apply()
        settings = readSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (LockscreenShortcutBackgroundSettings) -> LockscreenShortcutBackgroundSettings) {
        settings = transform(readSettings(local)).let { it.copy(glass = it.glass.normalized()) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun updateGestureMode(service: XposedService?, mode: Int) {
        val value = mode.coerceIn(0, 2)
        local.edit().putInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, value)?.apply()
        settings = readSettings(local)
    }

    companion object {
        fun readSettings(p: SharedPreferences): LockscreenShortcutBackgroundSettings {
            val legacyMode = p.getInt("lockscreen_shortcut_background_mode",
                if (p.getBoolean("lockscreen_shortcut_glass_enabled", false)) 3 else 0)
            val prefix = KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX
            fun flag(suffix: String, legacy: String) = p.getBoolean(prefix + suffix, p.getBoolean(legacy, false))
            fun dimension(suffix: String, legacy: String, default: Float, range: ClosedFloatingPointRange<Float>) =
                p.getFloat(prefix + suffix, p.getFloat(legacy, default)).coerceIn(range)
            fun color(suffix: String, legacy: String, default: Int) = p.getInt(prefix + suffix, p.getInt(legacy, default))
            return LockscreenShortcutBackgroundSettings(
                enabled = p.getBoolean(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_ENABLED, legacyMode != 0),
                useGlobalGlass = p.getBoolean(KEY_LOCKSCREEN_SHORTCUT_USE_GLOBAL_GLASS, false),
                gestureMode = p.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0).coerceIn(0, 2),
                backgroundMode = p.getInt(prefix + "mode", legacyMode.takeIf { it != 0 } ?: 3)
                    .let { if (it in 1..3) it else 3 },
                circleRadius = dimension("circle_radius", "lockscreen_shortcut_glass_radius", 48f, 10f..60f),
                radiusEnabled = flag("radius_enabled", "lockscreen_shortcut_background_radius_enabled"),
                radius = dimension("radius", "lockscreen_shortcut_background_radius", 24f, 0f..60f),
                spacingEnabled = flag("spacing_enabled", "lockscreen_shortcut_spacing_enabled"),
                spacing = dimension("spacing", "lockscreen_shortcut_spacing", 0f, 0f..48f),
                iconSizeEnabled = flag("icon_enabled", "lockscreen_shortcut_icon_size_enabled"),
                iconSize = dimension("icon_size", "lockscreen_shortcut_icon_size", 32f, 16f..64f),
                pureColor = color("pure_color", "shortcut_pure_color", 0x73FFFFFF),
                advancedColor = color("advanced_color", "shortcut_advanced_material_color", 0xFFFFFFFF.toInt()),
                advancedOpacity = color("advanced_opacity", "shortcut_advanced_material_opacity", 14).coerceIn(0, 100),
                advancedBlur = color("advanced_blur", "shortcut_advanced_material_blur_radius", 80).coerceIn(0, 120),
                advancedHighlight = flag("advanced_highlight", "shortcut_advanced_material_highlight"),
                // Old four-parameter glass payloads intentionally do not carry over.
                glass = readSoftGlassSettings(p, KEY_SHORTCUT_GLASS_PREFIX),
            )
        }
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().apply {
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_ENABLED, settings.enabled)
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_USE_GLOBAL_GLASS, settings.useGlobalGlass)
            putInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, settings.gestureMode)
            putInt(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "mode", settings.backgroundMode)
            putFloat(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "circle_radius", settings.circleRadius)
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "radius_enabled", settings.radiusEnabled)
            putFloat(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "radius", settings.radius)
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "spacing_enabled", settings.spacingEnabled)
            putFloat(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "spacing", settings.spacing)
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "icon_enabled", settings.iconSizeEnabled)
            putFloat(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "icon_size", settings.iconSize)
            putInt(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "pure_color", settings.pureColor)
            putInt(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "advanced_color", settings.advancedColor)
            putInt(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "advanced_opacity", settings.advancedOpacity)
            putInt(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "advanced_blur", settings.advancedBlur)
            putBoolean(KEY_LOCKSCREEN_SHORTCUT_V2_PREFIX + "advanced_highlight", settings.advancedHighlight)
            putBoolean(KEY_SHORTCUT_GLASS_PREFIX + "enabled", settings.glass.enabled)
            putInt(KEY_SHORTCUT_GLASS_PREFIX + "refraction_blur", settings.glass.refractionBlur)
            putInt(KEY_SHORTCUT_GLASS_PREFIX + "blur_percent", settings.glass.backgroundBlur)
            putInt(KEY_SHORTCUT_GLASS_PREFIX + "scale_percent", settings.glass.backgroundScale)
            putInt(KEY_SHORTCUT_GLASS_PREFIX + "opacity_percent", settings.glass.backgroundOpacity)
            softGlassParameters.forEach { definition ->
                putInt(KEY_SHORTCUT_GLASS_PREFIX + if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key, settings.glass.adjustments[definition.key] ?: if (definition.key == "darker") -100 else 0)
            }
        }.apply()
    }
}
