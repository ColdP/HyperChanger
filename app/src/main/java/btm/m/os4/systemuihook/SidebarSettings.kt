// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val SIDEBAR_PREFIX = "sidebar_glass_v1_"
private const val USE_GLOBAL = "${SIDEBAR_PREFIX}use_global"
private const val UNIFY = "${SIDEBAR_PREFIX}unify_cards"

internal data class SidebarSettings(
    val unifyCards: Boolean = false,
    val useGlobal: Boolean = true,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
)

internal fun readSidebarSettings(p: SharedPreferences) = SidebarSettings(
    unifyCards = p.getBoolean(UNIFY, false),
    useGlobal = p.getBoolean(USE_GLOBAL, true),
    glass = readSoftGlassSettings(p, SIDEBAR_PREFIX),
)

/** Followers select parameter values independently of the SystemUI master switch. */
internal fun resolveSidebarGlass(local: SidebarSettings, global: SoftGlassSettings): SoftGlassSettings =
    (if (local.useGlobal) global else local.glass).copy(
        enabled = local.glass.enabled,
        backgroundBlur = 100, backgroundScale = 100, backgroundOpacity = 100,
    )

private fun SidebarSettings.values(): Map<String, Any> = buildMap {
    put(UNIFY, unifyCards)
    put(USE_GLOBAL, useGlobal)
    put("${SIDEBAR_PREFIX}enabled", glass.enabled)
    put("${SIDEBAR_PREFIX}refraction_blur", glass.refractionBlur)
    softGlassParameters.forEach { definition ->
        val suffix = if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key
        put(SIDEBAR_PREFIX + suffix, glass.adjustments[definition.key] ?: if (definition.key == "darker") -100 else 0)
    }
}

internal class SidebarSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readSidebarSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            SidebarSettings().values().keys.forEach { key ->
                if (!local.contains(key)) {
                    when (val value = remote.all[key]) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }
        }.apply()
        settings = readSidebarSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (SidebarSettings) -> SidebarSettings) {
        settings = transform(readSidebarSettings(local)).let { it.copy(glass = it.glass.normalized()) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            settings.values().forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.apply()
    }
}

/** Export defaults as well as saved values; older imports reset the new feature to off. */
internal fun completeSidebarPreset(values: JSONObject) {
    SidebarSettings().values().forEach { (key, default) ->
        if (!values.has(key)) values.put(key, JSONObject()
            .put("type", if (default is Boolean) "boolean" else "int").put("value", default))
    }
}
