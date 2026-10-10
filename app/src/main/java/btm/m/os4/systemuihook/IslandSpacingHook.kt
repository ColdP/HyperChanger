// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Copyright (c) 2026 btm_m
package btm.m.os4.systemuihook

/**
 * Adapted from HyperIsland 3.1.8's BigIslandMinWidthHook / ResourceDimenHook.
 * The OEM caches island_space in DynamicIslandBaseContentView's constructor;
 * changing that resource keeps layout, animation and touch bounds in agreement.
 * Always offset the original resource, never a previously adjusted view field.
 */
internal fun islandSpacingDp(originalDp: Float, settings: IslandSpacingSettings): Float? {
    if (!settings.enabled || settings.offsetDp == 0) return null
    return (originalDp + settings.offsetDp.coerceIn(ISLAND_SPACING_RANGE)).coerceAtLeast(0f)
}
