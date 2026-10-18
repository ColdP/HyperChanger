// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

// A fresh namespace intentionally ignores every legacy material/preset payload.
private const val PREFIX = "soft_glass_parameters_v3_"
internal const val KEY_SOFT_GLASS_ENABLED = "${PREFIX}enabled"
private const val KEY_SOFT_GLASS_PRESETS = "soft_glass_custom_presets_v1"

internal fun readSoftGlassSettings(p: SharedPreferences) = SoftGlassSettings(
    enabled = p.getBoolean(KEY_SOFT_GLASS_ENABLED, false),
    refractionBlur = readSoftGlassRefractionBlur(p),
    // The previous 'element' values were the actual sampled backdrop controls.
    backgroundBlur = p.getInt("${PREFIX}blur_percent", p.getInt("${PREFIX}element_blur", 100)),
    backgroundScale = p.getInt("${PREFIX}scale_percent", p.getInt("${PREFIX}element_scale", 100)),
    backgroundOpacity = p.getInt("${PREFIX}opacity_percent", 100),
    backgroundDarkening = p.getInt("${PREFIX}darkening_percent", 100),
    adjustments = softGlassParameters.associate { it.key to p.getInt("$PREFIX${it.key}", 0) },
).normalized()

/** Keep View setter hooks free of preference deserialization on every animation frame. */
internal class SoftGlassRuntimeSettings(private val preferences: SharedPreferences, onChanged: () -> Unit = {}) {
    @Volatile var settings = readSoftGlassSettings(preferences); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(PREFIX)) {
            settings = readSoftGlassSettings(preferences)
            onChanged()
        }
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}

internal class SoftGlassSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSoftGlassSettings(local); private set
    var presets = SoftGlassPresetCodec.decodeLibrary(local.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]"); private set

    fun reload() {
        settings = readSoftGlassSettings(local)
        presets = SoftGlassPresetCodec.decodeLibrary(local.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]")
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSoftGlassSettings(if (remote.contains(KEY_SOFT_GLASS_ENABLED)) remote else local)
        if (remote.contains(KEY_SOFT_GLASS_PRESETS)) {
            presets = SoftGlassPresetCodec.decodeLibrary(remote.getString(KEY_SOFT_GLASS_PRESETS, "[]") ?: "[]")
        }
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (SoftGlassSettings) -> SoftGlassSettings) {
        settings = transform(settings).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun savePreset(service: XposedService?, preset: SoftGlassPreset) {
        presets = (presets.filterNot { it.id == preset.id } + preset.copy(builtIn = false)).takeLast(100)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun deletePreset(service: XposedService?, id: String) {
        presets = presets.filterNot { it.id == id }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(KEY_SOFT_GLASS_ENABLED, settings.enabled)
            putSoftGlassRefractionBlur(settings.refractionBlur)
            putInt("${PREFIX}blur_percent", settings.backgroundBlur)
            putInt("${PREFIX}scale_percent", settings.backgroundScale)
            putInt("${PREFIX}opacity_percent", settings.backgroundOpacity)
            putInt("${PREFIX}darkening_percent", settings.backgroundDarkening)
            listOf("element_blur", "background_blur", "element_scale", "background_scale").forEach { remove("$PREFIX$it") }
            softGlassParameters.forEach { putInt("$PREFIX${it.key}", settings.adjustments[it.key] ?: 0) }
            putString(KEY_SOFT_GLASS_PRESETS, SoftGlassPresetCodec.encodeLibrary(presets))
        }.apply()
    }
}
