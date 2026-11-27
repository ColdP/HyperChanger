// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val CLEAR_FROST_PREFIX = "clear_frost_parameters_v1_"
internal const val KEY_CLEAR_FROST_ENABLED = "${CLEAR_FROST_PREFIX}enabled"
internal const val KEY_CLEAR_FROST_PRESETS = "clear_frost_custom_presets_v1"
internal const val KEY_CLEAR_FROST_APPLIED_PRESET = "clear_frost_applied_preset_v1"

internal fun readClearFrostSettings(p: SharedPreferences): ClearFrostSettings {
    if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return ClearFrostSettings()
    return ClearFrostSettings(
        enabled = p.getBoolean(KEY_CLEAR_FROST_ENABLED, false),
        controlCenterThemeMode = p.getInt("${CLEAR_FROST_PREFIX}cc_theme_mode", CLEAR_FROST_CC_THEME_SYSTEM),
        separateScopes = p.getBoolean("${CLEAR_FROST_PREFIX}separate_scopes", false),
        lightStyle = p.getString("${CLEAR_FROST_PREFIX}light_style", "Colored_Thick_Light") ?: "Colored_Thick_Light",
        darkStyle = p.getString("${CLEAR_FROST_PREFIX}dark_style", "Colored_Extra_Thin_Dark") ?: "Colored_Extra_Thin_Dark",
        notificationLightStyle = p.getString("${CLEAR_FROST_PREFIX}notif_light_style", "Colored_Thick_Light") ?: "Colored_Thick_Light",
        notificationDarkStyle = p.getString("${CLEAR_FROST_PREFIX}notif_dark_style", "Colored_Extra_Thin_Dark") ?: "Colored_Extra_Thin_Dark",
        controlCenterLightStyle = p.getString("${CLEAR_FROST_PREFIX}cc_light_style", "Colored_Thick_Light") ?: "Colored_Thick_Light",
        controlCenterDarkStyle = p.getString("${CLEAR_FROST_PREFIX}cc_dark_style", "Colored_Extra_Thin_Dark") ?: "Colored_Extra_Thin_Dark",
        customLightColor1 = p.getInt("${CLEAR_FROST_PREFIX}custom_light_1", CLEAR_FROST_DEFAULT_LIGHT_COLOR_1),
        customLightColor2 = p.getInt("${CLEAR_FROST_PREFIX}custom_light_2", CLEAR_FROST_DEFAULT_LIGHT_COLOR_2),
        customLightColor3 = p.getInt("${CLEAR_FROST_PREFIX}custom_light_3", CLEAR_FROST_DEFAULT_LIGHT_COLOR_3),
        customDarkColor1 = p.getInt("${CLEAR_FROST_PREFIX}custom_dark_1", CLEAR_FROST_DEFAULT_DARK_COLOR_1),
        customDarkColor2 = p.getInt("${CLEAR_FROST_PREFIX}custom_dark_2", CLEAR_FROST_DEFAULT_DARK_COLOR_2),
        customDarkColor3 = p.getInt("${CLEAR_FROST_PREFIX}custom_dark_3", CLEAR_FROST_DEFAULT_DARK_COLOR_3),
        elementOpacity = p.getInt("${CLEAR_FROST_PREFIX}element_opacity", 100),
        strokeEnabled = p.getBoolean("${CLEAR_FROST_PREFIX}stroke_enabled", true),
        strokePreset = p.getString("${CLEAR_FROST_PREFIX}stroke_preset", CLEAR_FROST_STROKE_SMALL) ?: CLEAR_FROST_STROKE_SMALL,
        strokeNormalWidth = p.getFloat("${CLEAR_FROST_PREFIX}stroke_normal_width", 2.6f),
        strokeBloomWidth = p.getFloat("${CLEAR_FROST_PREFIX}stroke_bloom_width", 0.8f),
        strokeBloomDegree = p.getInt("${CLEAR_FROST_PREFIX}stroke_bloom_degree", 180),
        strokeBloomAlpha = p.getInt("${CLEAR_FROST_PREFIX}stroke_bloom_alpha", 5),
        strokeSource1Alpha = p.getInt("${CLEAR_FROST_PREFIX}stroke_source1_alpha", 60),
        strokeSource2Alpha = p.getInt("${CLEAR_FROST_PREFIX}stroke_source2_alpha", 35),
        strokeSource1X = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source1_x", 0.5f),
        strokeSource1Y = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source1_y", 0.5f),
        strokeSource1Z = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source1_z", -0.5f),
        strokeSource2X = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source2_x", 0.5f),
        strokeSource2Y = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source2_y", 0.95f),
        strokeSource2Z = p.getFloat("${CLEAR_FROST_PREFIX}stroke_source2_z", -0.5f),
        shadowEnabled = p.getBoolean("${CLEAR_FROST_PREFIX}shadow_enabled", false),
        shadowAlpha = p.getInt("${CLEAR_FROST_PREFIX}shadow_alpha", 35),
        shadowOffsetY = p.getInt("${CLEAR_FROST_PREFIX}shadow_offset_y", 30),
        shadowRadius = p.getInt("${CLEAR_FROST_PREFIX}shadow_radius", 26),
        backgroundBlur = p.getInt("${CLEAR_FROST_PREFIX}bg_blur", 100),
        backgroundScale = p.getInt("${CLEAR_FROST_PREFIX}bg_scale", 100),
        backgroundOpacity = p.getInt("${CLEAR_FROST_PREFIX}bg_opacity", 100),
        customShadeBlendEnabled = p.getBoolean("${CLEAR_FROST_PREFIX}custom_shade_enabled", false),
        customShadeBlendColor = p.getInt("${CLEAR_FROST_PREFIX}custom_shade_color", CLEAR_FROST_DEFAULT_SHADE_BLEND_COLOR),
    ).normalized()
}

internal fun writeClearFrostSettings(p: SharedPreferences, value: ClearFrostSettings) {
    val s = value.normalized()
    p.edit().apply {
        putBoolean(KEY_CLEAR_FROST_ENABLED, s.enabled)
        putInt("${CLEAR_FROST_PREFIX}cc_theme_mode", s.controlCenterThemeMode)
        putBoolean("${CLEAR_FROST_PREFIX}separate_scopes", s.separateScopes)
        putString("${CLEAR_FROST_PREFIX}light_style", s.lightStyle)
        putString("${CLEAR_FROST_PREFIX}dark_style", s.darkStyle)
        putString("${CLEAR_FROST_PREFIX}notif_light_style", s.notificationLightStyle)
        putString("${CLEAR_FROST_PREFIX}notif_dark_style", s.notificationDarkStyle)
        putString("${CLEAR_FROST_PREFIX}cc_light_style", s.controlCenterLightStyle)
        putString("${CLEAR_FROST_PREFIX}cc_dark_style", s.controlCenterDarkStyle)
        putInt("${CLEAR_FROST_PREFIX}custom_light_1", s.customLightColor1)
        putInt("${CLEAR_FROST_PREFIX}custom_light_2", s.customLightColor2)
        putInt("${CLEAR_FROST_PREFIX}custom_light_3", s.customLightColor3)
        putInt("${CLEAR_FROST_PREFIX}custom_dark_1", s.customDarkColor1)
        putInt("${CLEAR_FROST_PREFIX}custom_dark_2", s.customDarkColor2)
        putInt("${CLEAR_FROST_PREFIX}custom_dark_3", s.customDarkColor3)
        putInt("${CLEAR_FROST_PREFIX}element_opacity", s.elementOpacity)
        putBoolean("${CLEAR_FROST_PREFIX}stroke_enabled", s.strokeEnabled)
        putString("${CLEAR_FROST_PREFIX}stroke_preset", s.strokePreset)
        putFloat("${CLEAR_FROST_PREFIX}stroke_normal_width", s.strokeNormalWidth)
        putFloat("${CLEAR_FROST_PREFIX}stroke_bloom_width", s.strokeBloomWidth)
        putInt("${CLEAR_FROST_PREFIX}stroke_bloom_degree", s.strokeBloomDegree)
        putInt("${CLEAR_FROST_PREFIX}stroke_bloom_alpha", s.strokeBloomAlpha)
        putInt("${CLEAR_FROST_PREFIX}stroke_source1_alpha", s.strokeSource1Alpha)
        putInt("${CLEAR_FROST_PREFIX}stroke_source2_alpha", s.strokeSource2Alpha)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source1_x", s.strokeSource1X)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source1_y", s.strokeSource1Y)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source1_z", s.strokeSource1Z)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source2_x", s.strokeSource2X)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source2_y", s.strokeSource2Y)
        putFloat("${CLEAR_FROST_PREFIX}stroke_source2_z", s.strokeSource2Z)
        putBoolean("${CLEAR_FROST_PREFIX}shadow_enabled", s.shadowEnabled)
        putInt("${CLEAR_FROST_PREFIX}shadow_alpha", s.shadowAlpha)
        putInt("${CLEAR_FROST_PREFIX}shadow_offset_y", s.shadowOffsetY)
        putInt("${CLEAR_FROST_PREFIX}shadow_radius", s.shadowRadius)
        putInt("${CLEAR_FROST_PREFIX}bg_blur", s.backgroundBlur)
        putInt("${CLEAR_FROST_PREFIX}bg_scale", s.backgroundScale)
        putInt("${CLEAR_FROST_PREFIX}bg_opacity", s.backgroundOpacity)
        putBoolean("${CLEAR_FROST_PREFIX}custom_shade_enabled", s.customShadeBlendEnabled)
        putInt("${CLEAR_FROST_PREFIX}custom_shade_color", s.customShadeBlendColor)
    }.apply()
}

internal class ClearFrostRuntimeSettings(
    private val preferences: SharedPreferences,
    onChanged: () -> Unit = {},
) {
    @Volatile var settings: ClearFrostSettings = readClearFrostSettings(preferences); private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(CLEAR_FROST_PREFIX)) {
            settings = readClearFrostSettings(preferences)
            onChanged()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
}

internal class ClearFrostSettingsStore(
    private val local: SharedPreferences,
    private val onChanged: () -> Unit = {},
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    )

    var settings: ClearFrostSettings = readClearFrostSettings(local); private set
    var presets: List<ClearFrostPreset> = ClearFrostPresetCodec.decodeLibrary(local.getString(KEY_CLEAR_FROST_PRESETS, "[]") ?: "[]"); private set
    var appliedPresetId: String? = local.getString(KEY_CLEAR_FROST_APPLIED_PRESET, null); private set

    fun reload() {
        settings = readClearFrostSettings(local)
        presets = ClearFrostPresetCodec.decodeLibrary(local.getString(KEY_CLEAR_FROST_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_CLEAR_FROST_APPLIED_PRESET, null)
    }

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        settings = readClearFrostSettings(if (local.contains(KEY_CLEAR_FROST_ENABLED)) local else remote)
        val library = if (local.contains(KEY_CLEAR_FROST_PRESETS)) local else remote
        presets = ClearFrostPresetCodec.decodeLibrary(library.getString(KEY_CLEAR_FROST_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_CLEAR_FROST_APPLIED_PRESET, null)
        writeClearFrostSettings(local, settings)
        writeClearFrostSettings(remote, settings)
        writePresets(local)
        writePresets(remote)
        onChanged()
    }

    fun update(service: XposedService?, transform: (ClearFrostSettings) -> ClearFrostSettings) {
        settings = transform(readClearFrostSettings(local)).normalized()
        writeClearFrostSettings(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { writeClearFrostSettings(it, settings) }
        onChanged()
    }

    fun applyPreset(service: XposedService?, preset: ClearFrostPreset): ClearFrostSettings {
        settings = preset.applyTo(settings)
        writeClearFrostSettings(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { writeClearFrostSettings(it, settings) }
        onChanged()
        return settings
    }

    fun savePreset(service: XposedService?, preset: ClearFrostPreset) {
        reload()
        presets = (presets.filterNot { it.id == preset.id } + preset.copy(builtIn = false)).takeLast(100)
        writePresets(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::writePresets)
    }

    fun renamePreset(service: XposedService?, id: String, name: String) {
        reload()
        presets.firstOrNull { it.id == id }?.let { savePreset(service, it.copy(name = name.trim().take(40))) }
    }

    fun setAppliedPreset(service: XposedService?, id: String?) {
        appliedPresetId = id
        local.edit().apply {
            if (id == null) remove(KEY_CLEAR_FROST_APPLIED_PRESET) else putString(KEY_CLEAR_FROST_APPLIED_PRESET, id)
        }.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply {
            if (id == null) remove(KEY_CLEAR_FROST_APPLIED_PRESET) else putString(KEY_CLEAR_FROST_APPLIED_PRESET, id)
        }?.apply()
    }

    fun deletePreset(service: XposedService?, id: String) {
        reload()
        presets = presets.filterNot { it.id == id }
        if (appliedPresetId == id) setAppliedPreset(service, null)
        writePresets(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::writePresets)
    }

    private fun writePresets(p: SharedPreferences) {
        p.edit().putString(KEY_CLEAR_FROST_PRESETS, ClearFrostPresetCodec.encodeLibrary(presets)).apply()
    }
}

internal fun completeClearFrostPreset(values: JSONObject) {
    val defaults = ClearFrostSettings()
    fun ensure(key: String, type: String, value: Any) {
        if (!values.has(key)) values.put(key, JSONObject().put("type", type).put("value", value))
    }
    ensure(KEY_CLEAR_FROST_ENABLED, "boolean", defaults.enabled)
    ensure("${CLEAR_FROST_PREFIX}cc_theme_mode", "int", defaults.controlCenterThemeMode)
    ensure("${CLEAR_FROST_PREFIX}separate_scopes", "boolean", defaults.separateScopes)
    ensure("${CLEAR_FROST_PREFIX}light_style", "string", defaults.lightStyle)
    ensure("${CLEAR_FROST_PREFIX}dark_style", "string", defaults.darkStyle)
    ensure("${CLEAR_FROST_PREFIX}notif_light_style", "string", defaults.notificationLightStyle)
    ensure("${CLEAR_FROST_PREFIX}notif_dark_style", "string", defaults.notificationDarkStyle)
    ensure("${CLEAR_FROST_PREFIX}cc_light_style", "string", defaults.controlCenterLightStyle)
    ensure("${CLEAR_FROST_PREFIX}cc_dark_style", "string", defaults.controlCenterDarkStyle)
    ensure("${CLEAR_FROST_PREFIX}custom_light_1", "int", defaults.customLightColor1)
    ensure("${CLEAR_FROST_PREFIX}custom_light_2", "int", defaults.customLightColor2)
    ensure("${CLEAR_FROST_PREFIX}custom_light_3", "int", defaults.customLightColor3)
    ensure("${CLEAR_FROST_PREFIX}custom_dark_1", "int", defaults.customDarkColor1)
    ensure("${CLEAR_FROST_PREFIX}custom_dark_2", "int", defaults.customDarkColor2)
    ensure("${CLEAR_FROST_PREFIX}custom_dark_3", "int", defaults.customDarkColor3)
    ensure("${CLEAR_FROST_PREFIX}element_opacity", "int", defaults.elementOpacity)
    ensure("${CLEAR_FROST_PREFIX}stroke_enabled", "boolean", defaults.strokeEnabled)
    ensure("${CLEAR_FROST_PREFIX}stroke_preset", "string", defaults.strokePreset)
    ensure("${CLEAR_FROST_PREFIX}stroke_normal_width", "float", defaults.strokeNormalWidth.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_bloom_width", "float", defaults.strokeBloomWidth.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_bloom_degree", "int", defaults.strokeBloomDegree)
    ensure("${CLEAR_FROST_PREFIX}stroke_bloom_alpha", "int", defaults.strokeBloomAlpha)
    ensure("${CLEAR_FROST_PREFIX}stroke_source1_alpha", "int", defaults.strokeSource1Alpha)
    ensure("${CLEAR_FROST_PREFIX}stroke_source2_alpha", "int", defaults.strokeSource2Alpha)
    ensure("${CLEAR_FROST_PREFIX}stroke_source1_x", "float", defaults.strokeSource1X.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_source1_y", "float", defaults.strokeSource1Y.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_source1_z", "float", defaults.strokeSource1Z.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_source2_x", "float", defaults.strokeSource2X.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_source2_y", "float", defaults.strokeSource2Y.toDouble())
    ensure("${CLEAR_FROST_PREFIX}stroke_source2_z", "float", defaults.strokeSource2Z.toDouble())
    ensure("${CLEAR_FROST_PREFIX}shadow_enabled", "boolean", defaults.shadowEnabled)
    ensure("${CLEAR_FROST_PREFIX}shadow_alpha", "int", defaults.shadowAlpha)
    ensure("${CLEAR_FROST_PREFIX}shadow_offset_y", "int", defaults.shadowOffsetY)
    ensure("${CLEAR_FROST_PREFIX}shadow_radius", "int", defaults.shadowRadius)
    ensure("${CLEAR_FROST_PREFIX}bg_blur", "int", defaults.backgroundBlur)
    ensure("${CLEAR_FROST_PREFIX}bg_scale", "int", defaults.backgroundScale)
    ensure("${CLEAR_FROST_PREFIX}bg_opacity", "int", defaults.backgroundOpacity)
    ensure("${CLEAR_FROST_PREFIX}custom_shade_enabled", "boolean", defaults.customShadeBlendEnabled)
    ensure("${CLEAR_FROST_PREFIX}custom_shade_color", "int", defaults.customShadeBlendColor)
    ensure(KEY_CLEAR_FROST_PRESETS, "string", "[]")
}
