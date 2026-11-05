// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Only the translucent fill and glyph are drawn here. Bloom is rendered by HyperOS. */
internal class SidebarCapsuleDrawable(private val glyphColor: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var selected = false
    private var pressed = false
    private var drawableAlpha = 255
    var arrowLeft = true
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val newSelected = android.R.attr.state_selected in state
        val newPressed = android.R.attr.state_pressed in state
        if (selected == newSelected && pressed == newPressed) return false
        selected = newSelected
        pressed = newPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val radius = minOf(rect.width(), rect.height()) / 2f
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = (if (pressed) 40 else 20) * drawableAlpha / 255
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = glyphColor
        paint.alpha = Color.alpha(glyphColor) * drawableAlpha / 255
        val edge = minOf(24f * density, rect.height() * .6f, rect.width() * .5f)
        val cx = rect.centerX()
        val cy = rect.centerY()
        if (selected) {
            val direction = if (arrowLeft) -1f else 1f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(density, edge * .09f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(cx - direction * edge * .15f, cy - edge * .3f)
            path.lineTo(cx + direction * edge * .15f, cy)
            path.lineTo(cx - direction * edge * .15f, cy + edge * .3f)
            canvas.drawPath(path, paint)
        } else {
            val cell = edge * .36f
            val offset = edge * .32f
            for (x in listOf(-1, 1)) for (y in listOf(-1, 1)) {
                val left = cx + x * offset - cell / 2f
                val top = cy + y * offset - cell / 2f
                canvas.drawRoundRect(left, top, left + cell, top + cell, cell * .24f, cell * .24f, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
