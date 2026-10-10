// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Copyright 2026 btm_m (HyperChanger adaptation)
// Adapted from HyperIsland 3.1.8 SmoothIslandHook; see assets/licenses/HyperIsland-MIT.txt.
package btm.m.os4.systemuihook

import android.graphics.Path
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.min

internal fun isCollapsedIslandCapsule(width: Float, height: Float, radius: Float): Boolean =
    width.isFinite() && height.isFinite() && radius.isFinite() && width > 0f && height > 10f &&
        radius >= 4f && min(radius, height / 2f) >= height / 2f - 1.5f && width >= height

internal fun islandSmoothingAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (((color ushr 24) * alpha.coerceIn(0, 255) / 255) shl 24)

/** Pure curve geometry is shared by the material outline and the solid background. */
internal fun islandSmoothingPolygon(width: Float, height: Float, radius: Float, percent: Int): RoundedPolygon {
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    return RoundedPolygon(
        vertices = floatArrayOf(-halfWidth, -halfHeight, halfWidth, -halfHeight,
            halfWidth, halfHeight, -halfWidth, halfHeight),
        rounding = CornerRounding(radius.coerceIn(0f, min(halfWidth, halfHeight)), percent.coerceIn(0, 100) / 100f),
        centerX = 0f,
        centerY = 0f,
    )
}

internal class IslandSmoothingShape {
    private data class ShapeKey(val width: Float, val height: Float, val radius: Float, val percent: Int)
    private val cache = object : LinkedHashMap<ShapeKey, Path>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ShapeKey, Path>?) = size > 32
    }

    @Synchronized
    fun path(width: Float, height: Float, radius: Float, percent: Int): Path {
        val key = ShapeKey(width, height, radius, percent.coerceIn(0, 100))
        return cache.getOrPut(key) {
            islandSmoothingPolygon(width, height, radius, key.percent).toPath()
        }
    }

    @Synchronized
    fun clear() = cache.clear()
}
