// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import org.json.JSONObject

internal const val ISLAND_SIMULATION_TAG = "hyperchanger_island_simulation"
internal const val ISLAND_SIMULATION_TYPE_EXTRA = "hyperchanger.island.simulation.type"

internal fun supportsIslandSimulationRearScreen(type: IslandSimulationType, logistics: Boolean): Boolean =
    type == IslandSimulationType.DELIVERY || (type == IslandSimulationType.MOVIE_LOGISTICS && logistics)

/** Only businesses with verified, reusable native rear-screen templates. */
internal fun buildIslandSimulationRearPayload(type: IslandSimulationType, settings: IslandSimulationSettings): String? {
    if (!settings.rearScreen || !supportsIslandSimulationRearScreen(type, settings.logistics)) return null
    return JSONObject().put("business", if (type == IslandSimulationType.DELIVERY) "foodDelivery" else "Logistics")
        .put("index", 0).put("priority", 500).put("disable_popup", false)
        .put("show_time_tip", true).put("swipe_out_screen_listener", false).toString()
}
