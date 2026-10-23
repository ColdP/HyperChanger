// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

private const val HOME_GLASS_PREFIX = "home_full_glass_v1_"
private const val DOCK_GLASS_PREFIX = "dock_full_glass_v1_"

internal enum class DockGlassParameterSource { SYSTEM_DEFAULT, GLOBAL, CUSTOM }

internal data class HomeGlassSurfaceSettings(
    val useGlobal: Boolean = false,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
    val dockSource: DockGlassParameterSource = DockGlassParameterSource.SYSTEM_DEFAULT,
)

internal fun readHomeGlassSurface(p: SharedPreferences, dock: Boolean): HomeGlassSurfaceSettings {
    val prefix = if (dock) DOCK_GLASS_PREFIX else HOME_GLASS_PREFIX
    val source = if (p.contains(DOCK_GLASS_PREFIX + "source")) {
        DockGlassParameterSource.entries.getOrNull(p.getInt(DOCK_GLASS_PREFIX + "source", 0))
            ?: DockGlassParameterSource.SYSTEM_DEFAULT
    } else if (p.contains(DOCK_GLASS_PREFIX + "use_global")) {
        if (p.getBoolean(DOCK_GLASS_PREFIX + "use_global", false)) DockGlassParameterSource.GLOBAL
        else DockGlassParameterSource.CUSTOM
    } else DockGlassParameterSource.SYSTEM_DEFAULT
    return HomeGlassSurfaceSettings(
        useGlobal = !dock && p.getBoolean(prefix + "use_global", false),
        glass = readSoftGlassSettings(p, prefix),
        dockSource = if (dock) source else DockGlassParameterSource.SYSTEM_DEFAULT,
    )
}

internal fun resolveHomeGlassNativeSettings(p: SharedPreferences): HomeGlassNativeSettings {
    val global = readSoftGlassSettings(p)
    fun record(dock: Boolean, enabled: Boolean): HomeGlassNativeRecord {
        val selected = readHomeGlassSurface(p, dock)
        if (dock && selected.dockSource == DockGlassParameterSource.SYSTEM_DEFAULT) return HomeGlassNativeRecord()
        // The surface's own gate controls activation, independently of SystemUI.
        val useGlobal = if (dock) selected.dockSource == DockGlassParameterSource.GLOBAL else selected.useGlobal
        val glass = (if (useGlobal) global else selected.glass).normalized()
        // Keep the UI's absolute multiplier on the wire (100 = stock amount).
        // The native publisher converts it to a delta exactly once.
        return HomeGlassNativeRecord(enabled, listOf(glass.refractionBlur) +
            softGlassParameters.map { glass.adjustments[it.key] ?: if (it.key == "darker") -100 else 0 })
    }
    val desktop = record(false, p.getBoolean(KEY_HOME_MATERIAL_ENABLED, false))
    val dockEnabled = p.getBoolean(KEY_DOCK_ENABLED, false) && p.getInt(KEY_DOCK_MATERIAL, 1) == 2
    return HomeGlassNativeSettings(desktop, if (dockEnabled) record(true, true) else desktop)
}

internal fun homeGlassFollowsGlobal(p: SharedPreferences): Boolean =
    (p.getBoolean(KEY_HOME_MATERIAL_ENABLED, false) && readHomeGlassSurface(p, false).useGlobal) ||
        (p.getBoolean(KEY_DOCK_ENABLED, false) && p.getInt(KEY_DOCK_MATERIAL, 1) == 2 &&
            readHomeGlassSurface(p, true).dockSource == DockGlassParameterSource.GLOBAL)

internal fun stageGlobalHomeGlass(context: Context) {
    val p = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    if (homeGlassFollowsGlobal(p)) stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(p))
}

internal class HomeGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    fun read(dock: Boolean) = readHomeGlassSurface(local, dock)

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        // A saved local legacy choice wins over a stale remote source during migration.
        if (!local.contains(DOCK_GLASS_PREFIX + "source") && local.contains(DOCK_GLASS_PREFIX + "use_global")) {
            local.edit().putInt(DOCK_GLASS_PREFIX + "source", read(true).dockSource.ordinal).apply()
        }
        local.edit().apply {
            remote.all.forEach { (key, value) ->
                if ((key.startsWith(HOME_GLASS_PREFIX) || key.startsWith(DOCK_GLASS_PREFIX)) && !local.contains(key)) {
                    when (value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) }
                }
            }
        }.apply()
        listOf(false, true).forEach { dock -> write(local, dock, read(dock)); write(remote, dock, read(dock)) }
    }

    fun update(service: XposedService?, dock: Boolean, transform: (HomeGlassSurfaceSettings) -> HomeGlassSurfaceSettings) {
        val value = transform(read(dock)).let { it.copy(glass = it.glass.normalized()) }
        write(local, dock, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, dock, value) }
    }

    private fun write(p: SharedPreferences, dock: Boolean, value: HomeGlassSurfaceSettings) {
        val prefix = if (dock) DOCK_GLASS_PREFIX else HOME_GLASS_PREFIX
        p.edit().apply {
            putBoolean(prefix + "use_global", if (dock) value.dockSource == DockGlassParameterSource.GLOBAL else value.useGlobal)
            if (dock) putInt(prefix + "source", value.dockSource.ordinal)
            // Only glass controls belong to the launcher; View backdrop controls are excluded.
            putInt(prefix + "refraction_blur", value.glass.refractionBlur)
            softGlassParameters.forEach {
                putInt(prefix + if (it.key == "edge_feather") "edge_feather_inverse" else it.key,
                    value.glass.adjustments[it.key] ?: if (it.key == "darker") -100 else 0)
            }
        }.apply()
    }
}
