// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Matrix
import android.graphics.RectF
import android.widget.ImageView

/** Scale only the glyph; retain the host's layout, hit area and View scale animations. */
internal fun applyPortalSplashIconSize(icon: ImageView, offsetPercent: Int) {
    val drawable = icon.drawable ?: return
    val width = icon.width - icon.paddingLeft - icon.paddingRight
    val height = icon.height - icon.paddingTop - icon.paddingBottom
    if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
    val matrix = Matrix()
    // The original recognition ImageView uses FIT_CENTER and has no tint/background.
    matrix.setRectToRect(
        RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat()),
        RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER,
    )
    val scale = portalSplashScale(offsetPercent)
    matrix.postScale(scale, scale, width / 2f, height / 2f)
    icon.scaleType = ImageView.ScaleType.MATRIX
    icon.imageMatrix = matrix
}
