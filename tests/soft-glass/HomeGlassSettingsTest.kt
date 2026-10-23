// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Root transport is outside this JVM harness; exercise actual persistence,
// selection and wire writing against an in-memory preferences implementation.
internal const val KEY_HOME_MATERIAL_ENABLED = "home_material_enabled"
internal const val KEY_DOCK_ENABLED = "dock_enabled"
internal const val KEY_DOCK_MATERIAL = "dock_material"
internal fun readHomeRecentsSettings(@Suppress("UNUSED_PARAMETER") p: SharedPreferences) = Unit
internal fun stageHomeRecentsNativeConfig(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") settings: Unit) = true

internal fun testHomeGlass() {
    val local = memoryPreferences(mutableMapOf(
        KEY_HOME_MATERIAL_ENABLED to true, KEY_DOCK_ENABLED to true, KEY_DOCK_MATERIAL to 2,
        "home_glass_refraction_factor" to 180, // Old settings cannot leak into the new model.
        "soft_glass_parameters_v3_enabled" to false,
        "soft_glass_parameters_v3_refraction_blur_percent" to 45,
        "soft_glass_parameters_v3_edge_feather_inverse" to 65,
    ))
    val remote = memoryPreferences(mutableMapOf())
    val first = HomeGlassSettingsStore(local)
    check(first.read(true).dockSource == DockGlassParameterSource.SYSTEM_DEFAULT)
    first.update(null, false) { it.copy(glass = it.glass.copy(refractionBlur = 160, adjustments = mapOf("edge" to 80, "darker" to -100))) }
    check(!resolveHomeGlassNativeSettings(local).dock.enabled) // Stock Dock must not inherit desktop overrides.
    first.update(null, true) { it.copy(dockSource = DockGlassParameterSource.GLOBAL, glass = it.glass.copy(refractionBlur = 30)) }
    val resolved = resolveHomeGlassNativeSettings(local)
    check(resolved.desktop.enabled && resolved.desktop.values[0] == 160 && resolved.desktop.values[10] == 80)
    check(resolved.dock.enabled && resolved.dock.values[0] == 45 && resolved.dock.values[11] == 65) // SystemUI gate is independent.
    check(resolved.desktop.values[14] == -100)
    // The launcher wire stores blur as an absolute multiplier; only the native
    // publisher turns it into a delta from 100%. Keep each named slider in its
    // own positional slot so UI ordering regressions are caught here.
    val adjustments = softGlassParameters.mapIndexed { index, parameter -> parameter.key to (index + 1) }.toMap()
    first.update(null, false) { it.copy(glass = it.glass.copy(refractionBlur = 65, adjustments = adjustments)) }
    val ordered = resolveHomeGlassNativeSettings(local).desktop
    check(ordered.values == listOf(65) + (1..14).toList())
    check(ordered.isValid())
    first.update(null, false) { it.copy(glass = it.glass.copy(refractionBlur = 160, adjustments = mapOf("edge" to 80, "darker" to -100))) }
    check(resolveHomeGlassNativeSettings(local).desktop.values[0] == 160)
    check(homeGlassFollowsGlobal(local))
    first.syncPreferences(remote)
    // A stale remote snapshot must not replace a recently saved local edit.
    remote.edit().putInt("home_full_glass_v1_refraction_blur", 1).apply()
    HomeGlassSettingsStore(local).syncPreferences(remote)
    check(first.read(false).glass.refractionBlur == 160)
    val restored = HomeGlassSettingsStore(memoryPreferences(mutableMapOf()))
    restored.syncPreferences(remote)
    check(restored.read(false) == first.read(false))
    check(restored.read(true).dockSource == DockGlassParameterSource.GLOBAL)
    first.update(null, true) { it.copy(dockSource = DockGlassParameterSource.CUSTOM) }
    check(resolveHomeGlassNativeSettings(local).dock.values[0] == 30) // Retain independent custom sliders.
    check(!homeGlassFollowsGlobal(local))
    first.update(null, true) { it.copy(dockSource = DockGlassParameterSource.SYSTEM_DEFAULT) }
    check(!homeGlassFollowsGlobal(local))
    check(!resolveHomeGlassNativeSettings(local).dock.enabled)
    first.syncPreferences(remote)
    val reopened = HomeGlassSettingsStore(memoryPreferences(mutableMapOf()))
    reopened.syncPreferences(remote)
    check(reopened.read(true).dockSource == DockGlassParameterSource.SYSTEM_DEFAULT)
    reopened.update(null, true) { it.copy(dockSource = DockGlassParameterSource.CUSTOM) }
    check(reopened.read(true).glass.refractionBlur == 30)
    for (legacyGlobal in listOf(false, true)) {
        val legacy = memoryPreferences(mutableMapOf("dock_full_glass_v1_use_global" to legacyGlobal))
        val migrated = HomeGlassSettingsStore(legacy)
        val expected = if (legacyGlobal) DockGlassParameterSource.GLOBAL else DockGlassParameterSource.CUSTOM
        check(migrated.read(true).dockSource == expected)
        val stale = memoryPreferences(mutableMapOf("dock_full_glass_v1_source" to 0))
        migrated.syncPreferences(stale)
        check(migrated.read(true).dockSource == expected)
        check(stale.getInt("dock_full_glass_v1_source", -1) == expected.ordinal)
        val recovered = HomeGlassSettingsStore(memoryPreferences(mutableMapOf()))
        recovered.syncPreferences(memoryPreferences(mutableMapOf("dock_full_glass_v1_use_global" to legacyGlobal)))
        check(recovered.read(true).dockSource == expected)
    }
    local.edit().putBoolean(KEY_DOCK_ENABLED, false).apply()
    check(resolveHomeGlassNativeSettings(local).let { it.dock == it.desktop })
    local.edit().putBoolean(KEY_HOME_MATERIAL_ENABLED, false).apply()
    check(!resolveHomeGlassNativeSettings(local).desktop.enabled)

    var publications = 0
    val globals = SoftGlassSettingsStore(local) { publications++ }
    globals.update(null) { it.copy(refractionBlur = 120) }
    globals.applyPreset(null, builtInSoftGlassPresets().first())
    check(publications == 2) // Sliders and presets both republish follower settings.

    System.getProperty("home.glass.fixtures")?.let { path ->
        File(path).outputStream().use { out ->
            for (enabled in listOf(false, true)) for (dockEnabled in listOf(false, true)) for (blur in listOf(0, 50, 100, 160, 200)) for (amount in listOf(-100, 0, 100, 200)) {
                fun record(active: Boolean, inverse: Boolean) = HomeGlassNativeRecord(active,
                    listOf(blur) + List(14) { index ->
                        if (index == 10) (if (inverse) -amount else amount).coerceIn(-200, 100) else amount
                    })
                val blob = buildHomeLauncherBlob(2, 6, 12, true,
                    material = HomeMaterialSettings(enabled = true),
                    fullGlass = HomeGlassNativeSettings(record(enabled, false), record(dockEnabled, true)))
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array())
                out.write(blob)
            }
        }
    }
    check(!HomeGlassNativeRecord(values = List(15) { 999 }).isValid())
    check(!HomeGlassNativeRecord(values = listOf(100)).isValid())
    check(HomeGlassNativeRecord(values = listOf(0) + List(14) { 0 }).isValid())
    check(HomeGlassNativeRecord(values = listOf(200) + List(14) { 0 }).isValid())
}
