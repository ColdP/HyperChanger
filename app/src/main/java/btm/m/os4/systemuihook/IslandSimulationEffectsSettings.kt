// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context

internal const val ISLAND_SIMULATION_OUTER_GLOW_EFFECT = "outer_glow"
internal const val ISLAND_SIMULATION_BIG_GLOW_EXTRA = "miui.bigIsland.effect.src"

internal fun shouldBridgeIslandSimulationGlow(
    packageName: String,
    tag: String?,
    id: Int,
    typeKey: String?,
    effect: String?,
): Boolean = packageName == BuildConfig.APPLICATION_ID && tag == ISLAND_SIMULATION_TAG &&
    effect == ISLAND_SIMULATION_OUTER_GLOW_EFFECT &&
    IslandSimulationType.entries.getOrNull(id)?.key?.let { it == typeKey } == true

/** App-only switch encoded into each notification; hook processes need no preference mirror. */
internal class IslandSimulationEffectsSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun outerGlow(): Boolean = prefs.getBoolean(OUTER_GLOW, false)

    fun setOuterGlow(enabled: Boolean) {
        prefs.edit().putBoolean(OUTER_GLOW, enabled).apply()
    }

    private companion object {
        const val PREFS = "island_simulation_effects"
        const val OUTER_GLOW = "outer_glow"
    }
}
