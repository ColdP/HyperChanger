// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Settings-only compositor surface, kept outside Compose's offscreen effect layers. */
internal class SettingsSearchMaterialView(
    context: Context,
    private val failure: (Throwable) -> Unit,
) : View(context) {
    private data class Material(val opacity: Int, val blur: Int, val highlight: Boolean, val dark: Boolean)
    private var requested = Material(14, 40, false, false)
    private var applied: Material? = null
    private var released = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val materialPath = Path()
    // Slot 1 is bloomStrokeGradientDegree in the native MaterialToken contract.
    private val searchHighlight = HyperOsBackdropMaterial.highlight.copyOf().apply { this[1] = 45f }
    private val setBlurPath = View::class.java.getMethod("setMiBackgroundBlurPath", Path::class.java)
    private val setBlurType = View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType)
    private val setPassWindow = View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
        isForceDarkAllowed = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) { outline.setEmpty(); return }
                // Bloom needs an explicit radius; a generic path has no round-rect radius.
                // The actual blur and fill still use the continuous materialPath below.
                outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) * .5f)
                outline.alpha = 1f
            }
        }
        // Do not cut the continuous material into the circular outline used by Bloom.
        clipToOutline = false
    }

    fun update(settings: SettingsFloatingSearchSettings, dark: Boolean) {
        val next = Material(settings.opacity, settings.blurRadius, settings.highlight, dark)
        if (requested != next) {
            requested = next
            invalidate()
        }
        applyMaterial()
    }

    private fun applyMaterial() {
        if (released || !isAttachedToWindow || width <= 0 || height <= 0 || requested == applied) return
        runCatching {
            // clear also removes a previous bloom when the highlight switch is disabled.
            HyperOsBackdropMaterial.clear(this)
            // Tint is drawn once by onDraw, not through a pass-window blend texture.
            HyperOsBackdropMaterial.apply(this, 0, requested.blur,
                if (requested.dark) 0xFF17181C.toInt() else Color.WHITE, requested.highlight, searchHighlight)
            // The search floats above this Settings window's list. Use its local content,
            // as the existing MiBlur.applyLocalBlur does, rather than a window behind it.
            setPassWindow.invoke(this, false)
            setBlurType.invoke(this, 1)
            setBlurPath.invoke(this, materialPath)
            invalidate()
            applied = requested
        }.onFailure {
            clearMaterial()
            applied = null
            failure(it)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applied = null
        applyMaterial()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        materialPath.set(settingsSearchPath(Size(w.toFloat(), h.toFloat()),
            Density(resources.displayMetrics.density), LayoutDirection.Ltr).asAndroidPath())
        invalidateOutline()
        applied = null
        applyMaterial()
    }

    override fun onDetachedFromWindow() {
        clearMaterial()
        applied = null
        super.onDetachedFromWindow()
    }

    fun release() {
        released = true
        clearMaterial()
        applied = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fill.color = if (requested.dark) 0xFF17181C.toInt() else Color.WHITE
        fill.alpha = requested.opacity.coerceIn(0, 100) * 255 / 100
        canvas.drawPath(materialPath, fill)
    }

    private fun clearMaterial() {
        HyperOsBackdropMaterial.clear(this)
        runCatching { setBlurType.invoke(this, 0) }
        runCatching { setBlurPath.invoke(this, Path()) }
    }
}
