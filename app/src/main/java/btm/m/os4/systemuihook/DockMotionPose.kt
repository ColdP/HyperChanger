// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** A transform of the existing Dock surface, in launcher-window coordinates. */
internal data class DockMotionPose(val x: Float, val y: Float, val scale: Float, val alpha: Float)

internal fun dockMotionPose(
    x: Float, y: Float, pivotX: Float, pivotY: Float,
    rowScale: Float, projection: Float, offsetX: Float, offsetY: Float, alpha: Float,
): DockMotionPose {
    val scale = rowScale * projection
    require(listOf(x, y, pivotX, pivotY, scale, offsetX, offsetY, alpha).all { it.isFinite() })
    require(scale in 0f..4f)
    return DockMotionPose(
        pivotX + (x - pivotX) * scale + offsetX,
        pivotY + (y - pivotY) * scale + offsetY,
        scale, alpha.coerceIn(0f, 1f),
    )
}
