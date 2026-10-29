// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Insets
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/** Background-only decoration: content, layout bounds and the host's animations stay native. */
internal class ContactsDialerBackgroundDrawable(
    private val source: Drawable,
    private val settings: ContactsDialerBackgroundSettings,
    private val density: Float,
    private val fillOpacityPercent: Int?,
) : Drawable(), Drawable.Callback {
    private val nativeAlpha = source.alpha
    private val nativeOutline = Outline()
    private val nativeRect = Rect()
    private val surface = RectF()
    private val strokeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = settings.borderWidthPx
    }
    private var fillColor = sampleCenterColor()
    private var corner = 0f
    private var drawableAlpha = 255

    init { source.callback = this }

    private fun sampleCenterColor(): Int {
        val previousBounds = Rect(source.bounds)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val width = source.intrinsicWidth.coerceAtLeast(1)
            val height = source.intrinsicHeight.coerceAtLeast(1)
            source.setBounds(0, 0, width, height)
            val canvas = Canvas(bitmap)
            canvas.translate(-(width / 2).toFloat(), -(height / 2).toFloat())
            source.draw(canvas)
            bitmap.getPixel(0, 0)
        } finally {
            source.bounds = previousBounds
            bitmap.recycle()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        source.bounds = bounds
        source.getOutline(nativeOutline)
        if (!nativeOutline.getRect(nativeRect) || nativeRect.isEmpty) nativeRect.set(bounds)
        surface.set(nativeRect)
        // Compiled NinePatch outline metadata is preferred. The APK controller uses 30dp
        // for its round-rect fallback; this is only needed if the theme omits that metadata.
        val nativeCorner = nativeOutline.radius.takeIf { it > 0f } ?: (30f * density)
        corner = contactsDialerCornerRadius(nativeCorner, settings.cornerOffset, density)
            .coerceIn(0f, min(surface.width(), surface.height()).coerceAtLeast(0f) / 2f)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val fillFactor = (fillOpacityPercent ?: 100) / 100f
        if (settings.cornerOffset == 0) {
            val alpha = (nativeAlpha * fillFactor * drawableAlpha / 255f).roundToInt().coerceIn(0, 255)
            if (source.alpha != alpha) source.alpha = alpha
            source.draw(canvas)
        } else {
            fill.color = fillColor
            fill.alpha = (Color.alpha(fillColor) * fillFactor * drawableAlpha / 255f).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(surface, corner, corner, fill)
        }
        if (settings.border && settings.borderOpacityPercent > 0) {
            stroke.alpha = (settings.borderOpacityPercent * drawableAlpha / 100f).roundToInt().coerceIn(0, 255)
            strokeRect.set(surface)
            val halfStroke = settings.borderWidthPx / 2f
            strokeRect.inset(halfStroke, halfStroke)
            if (!strokeRect.isEmpty) {
                val strokeCorner = (corner - halfStroke).coerceAtLeast(0f)
                canvas.drawRoundRect(strokeRect, strokeCorner, strokeCorner, stroke)
            }
        }
    }

    override fun getOutline(outline: Outline) {
        if (settings.cornerOffset == 0) source.getOutline(outline)
        else {
            outline.setRoundRect(nativeRect, corner)
            outline.alpha = nativeAlpha / 255f
        }
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getPadding(padding: Rect): Boolean = source.getPadding(padding)
    override fun getOpticalInsets(): Insets = source.opticalInsets
    override fun getIntrinsicWidth(): Int = source.intrinsicWidth
    override fun getIntrinsicHeight(): Int = source.intrinsicHeight
    override fun getMinimumWidth(): Int = source.minimumWidth
    override fun getMinimumHeight(): Int = source.minimumHeight
    override fun isStateful(): Boolean = source.isStateful
    override fun onStateChange(state: IntArray): Boolean {
        val changed = source.setState(state)
        if (changed && settings.cornerOffset != 0) fillColor = sampleCenterColor()
        return changed
    }
    override fun onLevelChange(level: Int): Boolean = source.setLevel(level)
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
