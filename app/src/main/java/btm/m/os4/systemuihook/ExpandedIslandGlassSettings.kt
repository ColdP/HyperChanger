// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val EXPANDED_ISLAND_GLASS_PREFIX = "expanded_island_glass_v1_"
private const val USE_GLOBAL = "${EXPANDED_ISLAND_GLASS_PREFIX}use_global"

internal data class ExpandedIslandGlassSettings(
    val useGlobal: Boolean = false,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
)

internal fun readExpandedIslandGlassSettings(p: SharedPreferences) = ExpandedIslandGlassSettings(
    useGlobal = p.getBoolean(USE_GLOBAL, false),
    glass = readSoftGlassSettings(p, EXPANDED_ISLAND_GLASS_PREFIX).glassOnly(),
)

internal fun resolveExpandedIslandGlass(p: SharedPreferences): SoftGlassSettings =
    readExpandedIslandGlassSettings(p).let {
        (if (it.useGlobal) readSoftGlassSettings(p) else it.glass).glassOnly().copy(enabled = true)
    }

/** Start from the immutable vendor token on every update, never the last tuned array. */
internal fun tuneExpandedIslandGlass(original: FloatArray, opacity: Int, glass: SoftGlassSettings): FloatArray {
    val baseline = original.copyOf()
    if (baseline.size <= 14) return baseline
    return tuneSoftGlass(baseline, glass).also {
        it[14] = opacity.coerceIn(0, 100) / 100f * it[14]
    }
}

private fun SoftGlassSettings.glassOnly() = copy(backgroundBlur = 100, backgroundScale = 100, backgroundOpacity = 100)

internal class ExpandedIslandGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readExpandedIslandGlassSettings(local)
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        // Offline edits are authoritative; recover only keys absent from local storage.
        local.edit().apply {
            remote.all.forEach { (key, value) ->
                if (key.startsWith(EXPANDED_ISLAND_GLASS_PREFIX) && !local.contains(key)) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }
        }.apply()
        settings = readExpandedIslandGlassSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (ExpandedIslandGlassSettings) -> ExpandedIslandGlassSettings) {
        settings = transform(readExpandedIslandGlassSettings(local)).let { it.copy(glass = it.glass.normalized().glassOnly()) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(USE_GLOBAL, settings.useGlobal)
            putBoolean("${EXPANDED_ISLAND_GLASS_PREFIX}enabled", settings.glass.enabled)
            putInt("${EXPANDED_ISLAND_GLASS_PREFIX}refraction_blur", settings.glass.refractionBlur)
            softGlassParameters.forEach { definition ->
                val suffix = if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key
                putInt(EXPANDED_ISLAND_GLASS_PREFIX + suffix, settings.glass.adjustments[definition.key]
                    ?: if (definition.key == "darker") -100 else 0)
            }
        }.apply()
    }
}
