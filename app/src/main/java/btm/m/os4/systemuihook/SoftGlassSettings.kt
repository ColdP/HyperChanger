// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

// A fresh namespace intentionally ignores every legacy material/preset payload.
private const val PREFIX = "soft_glass_parameters_v3_"
internal const val KEY_SOFT_GLASS_ENABLED = "${PREFIX}enabled"

internal fun readSoftGlassSettings(p: SharedPreferences) = SoftGlassSettings(
    enabled = p.getBoolean(KEY_SOFT_GLASS_ENABLED, false),
    elementBlur = p.getInt("${PREFIX}element_blur", 100),
    backgroundBlur = p.getInt("${PREFIX}background_blur", 100),
    elementScale = p.getInt("${PREFIX}element_scale", 100),
    backgroundScale = p.getInt("${PREFIX}background_scale", 100),
    adjustments = softGlassParameters.associate { it.key to p.getInt("$PREFIX${it.key}", 0) },
).normalized()

/** Keep View setter hooks free of preference deserialization on every animation frame. */
internal class SoftGlassRuntimeSettings(private val preferences: SharedPreferences) {
    @Volatile var settings = readSoftGlassSettings(preferences); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(PREFIX)) settings = readSoftGlassSettings(preferences)
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}

internal class SoftGlassSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSoftGlassSettings(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSoftGlassSettings(if (remote.contains(KEY_SOFT_GLASS_ENABLED)) remote else local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (SoftGlassSettings) -> SoftGlassSettings) {
        settings = transform(settings).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(KEY_SOFT_GLASS_ENABLED, settings.enabled)
            putInt("${PREFIX}element_blur", settings.elementBlur)
            putInt("${PREFIX}background_blur", settings.backgroundBlur)
            putInt("${PREFIX}element_scale", settings.elementScale)
            putInt("${PREFIX}background_scale", settings.backgroundScale)
            softGlassParameters.forEach { putInt("$PREFIX${it.key}", settings.adjustments[it.key] ?: 0) }
        }.apply()
    }
}
