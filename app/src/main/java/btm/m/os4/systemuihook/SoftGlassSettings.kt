// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

// A fresh namespace intentionally ignores every legacy material/preset payload.
private const val PREFIX = "soft_glass_parameters_v3_"
internal const val KEY_SOFT_GLASS_ENABLED = "${PREFIX}enabled"
private const val KEY_SOFT_GLASS_PRESETS = "soft_glass_custom_presets_v1"
private const val KEY_SOFT_GLASS_APPLIED_PRESET = "soft_glass_applied_preset_v1"

internal fun readSoftGlassSettings(p: SharedPreferences) = readSoftGlassSettings(p, "")

internal fun readSoftGlassSettings(p: SharedPreferences, prefix: String): SoftGlassSettings {
    val key = { suffix: String -> if (prefix.isEmpty()) "${PREFIX}${suffix}" else "${prefix}${suffix}" }
    return SoftGlassSettings(
    enabled = p.getBoolean(key("enabled"), false),
    refractionBlur = if (prefix.isEmpty()) readSoftGlassRefractionBlur(p) else p.getInt(key("refraction_blur"), 100),
    // The previous 'element' values were the actual sampled backdrop controls.
    backgroundBlur = p.getInt(key("blur_percent"), if (prefix.isEmpty()) p.getInt("${PREFIX}element_blur", 100) else 100),
    backgroundScale = p.getInt(key("scale_percent"), if (prefix.isEmpty()) p.getInt("${PREFIX}element_scale", 100) else 100),
    backgroundOpacity = p.getInt(key("opacity_percent"), 100),
    adjustments = softGlassParameters.associate { definition ->
        definition.key to if (prefix.isEmpty() && definition.key == "edge_feather") {
            if (p.contains(key("edge_feather_inverse"))) p.getInt(key("edge_feather_inverse"), 0)
            else migrateSoftGlassFeather(p.getInt(key("reflection"), 0), p.getInt(key("edge_softness"), 0))
        } else p.getInt(key(if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key), if (definition.key == "darker") -100 else 0)
    },
).normalized()
}

/** Keep View setter hooks free of preference deserialization on every animation frame. */
internal class SoftGlassRuntimeSettings(private val preferences: SharedPreferences, onChanged: () -> Unit = {}) {
    @Volatile var settings = readSoftGlassSettings(preferences); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(PREFIX) || key == KEY_VOLUME_PANEL_GLOBAL_GLASS || key == "volume_panel_material_enabled") {
            settings = readSoftGlassSettings(preferences)
            onChanged()
        }
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}

internal class SoftGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSoftGlassSettings(local); private set
    var presets = SoftGlassPresetCodec.decodeLibrary(local.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]"); private set
    var appliedPresetId: String? = local.getString(KEY_SOFT_GLASS_APPLIED_PRESET, null); private set

    fun reload() {
        settings = readSoftGlassSettings(local)
        presets = SoftGlassPresetCodec.decodeLibrary(local.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_SOFT_GLASS_APPLIED_PRESET, null)
    }

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        // Local settings are written before the Xposed connection is ready. A
        // stale remote snapshot must not replace a complete local preset.
        settings = readSoftGlassSettings(if (local.contains(KEY_SOFT_GLASS_ENABLED)) local else remote)
        // The app owns the library. Remote preferences can lag behind a just-saved
        // preset; only recover from them when no local library has ever been saved.
        val library = if (local.contains(KEY_SOFT_GLASS_PRESETS)) local else remote
        presets = SoftGlassPresetCodec.decodeLibrary(library.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_SOFT_GLASS_APPLIED_PRESET, null)
        write(local)
        write(remote)
        writePresets(local)
        writePresets(remote)
    }

    fun update(service: XposedService?, transform: (SoftGlassSettings) -> SoftGlassSettings) {
        settings = transform(readSoftGlassSettings(local)).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun applyPreset(service: XposedService?, preset: SoftGlassPreset): SoftGlassSettings {
        settings = preset.applyTo(settings)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
        return settings
    }

    fun savePreset(service: XposedService?, preset: SoftGlassPreset) {
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
            if (id == null) remove(KEY_SOFT_GLASS_APPLIED_PRESET) else putString(KEY_SOFT_GLASS_APPLIED_PRESET, id)
        }.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply {
            if (id == null) remove(KEY_SOFT_GLASS_APPLIED_PRESET) else putString(KEY_SOFT_GLASS_APPLIED_PRESET, id)
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
        p.edit().putString(KEY_SOFT_GLASS_PRESETS, SoftGlassPresetCodec.encodeLibrary(presets)).apply()
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(KEY_SOFT_GLASS_ENABLED, settings.enabled)
            putSoftGlassRefractionBlur(settings.refractionBlur)
            putInt("${PREFIX}blur_percent", settings.backgroundBlur)
            putInt("${PREFIX}scale_percent", settings.backgroundScale)
            putInt("${PREFIX}opacity_percent", settings.backgroundOpacity)
            remove("${PREFIX}darkening_percent")
            remove("${PREFIX}edge_feather")
            remove("${PREFIX}edge_softness")
            remove("${PREFIX}reflection")
            listOf("element_blur", "background_blur", "element_scale", "background_scale").forEach { remove("$PREFIX$it") }
            softGlassParameters.forEach { putInt("$PREFIX${if (it.key == "edge_feather") "edge_feather_inverse" else it.key}", settings.adjustments[it.key] ?: 0) }
        }.apply()
    }
}
