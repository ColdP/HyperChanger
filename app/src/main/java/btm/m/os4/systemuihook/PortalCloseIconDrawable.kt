// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Original 5.1.5 close glyph, with its opaque circular plate moved to View.background. */
internal class PortalCloseIconDrawable(private val resources: Resources, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255
    private val path = PathParser.createPathFromPathData(
        "M57.0165,60.552C57.9928,61.5283 59.5757,61.5283 60.552,60.552C61.5283,59.5757 61.5283,57.9928 60.552,57.0165L48.1777,44.6422L60.5521,32.2678C61.5284,31.2915 61.5284,29.7085 60.5521,28.7322C59.5758,27.7559 57.9929,27.7559 57.0166,28.7322L44.6422,41.1066L32.2678,28.7322C31.2915,27.7559 29.7085,27.7559 28.7322,28.7322C27.7559,29.7085 27.7559,31.2915 28.7322,32.2678L41.1066,44.6422L28.7323,57.0165C27.756,57.9928 27.756,59.5757 28.7323,60.552C29.7086,61.5283 31.2915,61.5283 32.2679,60.552L44.6422,48.1777L57.0165,60.552Z",
    ).apply { fillType = Path.FillType.EVEN_ODD }

    override fun draw(canvas: Canvas) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        paint.color = if (dark) Color.WHITE else Color.BLACK
        paint.alpha = drawableAlpha * (if (dark) 204 else 255) / 255
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 90f, bounds.height() / 90f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(checkpoint)
    }
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
