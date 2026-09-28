// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop
import java.util.IdentityHashMap

/** Backdrop for hosts such as React Native whose render tree cannot be redrawn safely. */
@Composable
fun rememberBitmapNativeViewBackdrop(
    sourceView: View,
    sampleHeightPx: Int,
    onHostPreDraw: () -> Unit
): BitmapNativeViewBackdrop {
    val backdrop = remember(sourceView) { BitmapNativeViewBackdrop(sourceView) }
    backdrop.sampleHeightPx = sampleHeightPx
    DisposableEffect(sourceView, onHostPreDraw) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            onHostPreDraw()
            backdrop.capture()
            true
        }
        sourceView.viewTreeObserver.addOnPreDrawListener(listener)
        sourceView.post(backdrop::capture)
        onDispose {
            val observer = sourceView.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            backdrop.release()
        }
    }
    return backdrop
}

/** Software snapshot avoids recording React Native's render tree into a Compose RenderNode. */
@Stable
class BitmapNativeViewBackdrop internal constructor(
    private val sourceView: View
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true
    internal var sampleHeightPx: Int = 0
    private var snapshot: Bitmap? = null
    private var snapshotTop = 0
    private var version by mutableIntStateOf(0)
    private var lastCaptureAtNanos = 0L

    internal fun capture() {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (now - lastCaptureAtNanos < 8_333_333L || !sourceView.isAttachedToWindow ||
            sourceView.width <= 0 || sourceView.height <= 0 || sampleHeightPx <= 0
        ) return
        lastCaptureAtNanos = now
        val height = sampleHeightPx.coerceAtMost(sourceView.height)
        val bitmap = snapshot?.takeIf { it.width == sourceView.width && it.height == height }
            ?: Bitmap.createBitmap(sourceView.width, height, Bitmap.Config.ARGB_8888).also {
                snapshot?.recycle()
                snapshot = it
            }
        val top = sourceView.height - height
        runCatching {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.translate(-sourceView.scrollX.toFloat(), -(top + sourceView.scrollY).toFloat())
            sourceView.draw(canvas)
        }.onSuccess {
            snapshotTop = top
            version++
        }
    }

    internal fun release() {
        snapshot?.recycle()
        snapshot = null
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        @Suppress("UNUSED_EXPRESSION")
        version
        val bitmap = snapshot ?: return
        val consumer = coordinates ?: return
        val sourceLocation = IntArray(2).also(sourceView::getLocationInWindow)
        val consumerLocation = consumer.positionInWindow()
        drawImage(
            image = bitmap.asImageBitmap(),
            topLeft = Offset(
                sourceLocation[0] - consumerLocation.x,
                sourceLocation[1] + snapshotTop - consumerLocation.y
            )
        )
    }
}

@Composable
fun rememberNativeViewBackdrop(
    sourceView: View,
    onHostPreDraw: () -> Unit,
    redrawNativeText: Boolean = false
): NativeViewBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val backdrop = remember(graphicsLayer) { NativeViewBackdrop(graphicsLayer) }
    backdrop.sourceView = sourceView
    backdrop.redrawNativeText = redrawNativeText
    backdrop.density = density
    backdrop.layoutDirection = layoutDirection

    DisposableEffect(sourceView, onHostPreDraw) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            onHostPreDraw()
            if (sourceView.isDirty) backdrop.bumpVersion()
            true
        }
        sourceView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            val observer = sourceView.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
    }
    return backdrop
}

@Stable
class NativeViewBackdrop internal constructor(
    private val graphicsLayer: GraphicsLayer
) : Backdrop {
    internal var sourceView: View? = null
    internal var redrawNativeText: Boolean = false
    internal var density: Density = Density(1f)
    internal var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var version by mutableIntStateOf(0)
    private val composeSnapshots = IdentityHashMap<View, android.graphics.Bitmap>()

    override val isCoordinatesDependent: Boolean = true

    internal fun bumpVersion() {
        version++
    }

    private fun recordSource() {
        val source = sourceView ?: return
        if (source.width <= 0 || source.height <= 0) return
        graphicsLayer.record(density, layoutDirection, IntSize(source.width, source.height)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val checkpoint = native.save()
                native.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
                source.draw(native)
                native.restoreToCount(checkpoint)
                if (redrawNativeText) {
                    redrawTextViews(native, source)
                    redrawComposeViews(native, source)
                }
            }
        }
    }

    private fun redrawTextViews(canvas: android.graphics.Canvas, source: View) {
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val pending = ArrayDeque<View>()
        if (source is ViewGroup) {
            for (index in 0 until source.childCount) pending.addLast(source.getChildAt(index))
        }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) continue
            if (view is TextView) {
                val location = IntArray(2).also(view::getLocationInWindow)
                val checkpoint = canvas.save()
                canvas.translate(
                    (location[0] - sourceLocation[0]).toFloat(),
                    (location[1] - sourceLocation[1]).toFloat()
                )
                canvas.clipRect(0, 0, view.width, view.height)
                view.draw(canvas)
                canvas.restoreToCount(checkpoint)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    /** Compose text is rendered through a RenderNode and can be skipped by a direct native draw. */
    private fun redrawComposeViews(canvas: android.graphics.Canvas, source: View) {
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val pending = ArrayDeque<View>()
        if (source is ViewGroup) {
            for (index in 0 until source.childCount) pending.addLast(source.getChildAt(index))
        }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) continue
            if (view is ComposeView) {
                val bitmap = composeSnapshots[view].let { cached ->
                    if (cached == null || cached.width != view.width || cached.height != view.height) {
                        cached?.recycle()
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                            composeSnapshots[view] = it
                        }
                    } else {
                        cached
                    }
                }
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                view.draw(android.graphics.Canvas(bitmap))
                val location = IntArray(2).also(view::getLocationInWindow)
                val checkpoint = canvas.save()
                canvas.translate(
                    (location[0] - sourceLocation[0]).toFloat(),
                    (location[1] - sourceLocation[1]).toFloat()
                )
                canvas.clipRect(0, 0, view.width, view.height)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                canvas.restoreToCount(checkpoint)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        @Suppress("UNUSED_EXPRESSION")
        version
        val source = sourceView ?: return
        val consumer = coordinates ?: return
        recordSource()

        val consumerInWindow = consumer.positionInWindow()
        val sourceInWindow = IntArray(2).also(source::getLocationInWindow)
        val offset = Offset(
            consumerInWindow.x - sourceInWindow[0],
            consumerInWindow.y - sourceInWindow[1]
        )
        translate(-offset.x, -offset.y) {
            drawLayer(graphicsLayer)
        }
    }
}
