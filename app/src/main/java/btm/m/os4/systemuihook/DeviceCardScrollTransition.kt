// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Native scroll progress is frame-driven; easing it directly keeps reversals in sync with the finger. */
internal fun deviceCardScrollOpacity(visibility: Float): Float {
    val t = visibility.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun deviceCardScrollVisibility(scroll: Float, nativeRange: Float): Float =
    1f - (scroll.coerceAtLeast(0f) / (nativeRange.coerceAtLeast(1f) * 1.8f)).coerceIn(0f, 1f)

internal class DeviceCardScrollTransition(private val card: View, private val host: View) {
    // Read unclamped scroll: native alpha has already reached zero before our longer interval ends.
    private val nativeMembers = runCatching {
        val scroll = host.javaClass.getDeclaredField("scrollValue").apply { isAccessible = true }
        val controller = host.javaClass.getField("myDeviceAnimationController")
        val padding = controller.type.getMethod("getLogoPadding")
        Triple(scroll, controller, padding)
    }.getOrNull()
    private val effects = arrayOfNulls<RenderEffect>(65)
    private var lastStep = -1

    fun apply(source: View?) {
        if (source == null) return
        card.translationX = source.translationX
        // Native Y includes the update-logo lift, which is intentionally excluded for all custom styles.
        card.translationY = 0f
        val visibility = nativeMembers?.let { (scroll, controller, padding) ->
            runCatching {
                val y = scroll.getInt(host)
                // At the top keep the native entrance animation, which also drives source alpha.
                if (y <= 0) return@runCatching null
                val inset = (host.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                val range = (padding.invoke(controller.get(host)) as Number).toFloat() - inset
                deviceCardScrollVisibility(y.toFloat(), range)
            }.getOrNull()
        }
        card.scaleX = visibility?.let { .9f + .1f * it } ?: source.scaleX
        card.scaleY = visibility?.let { .9f + .1f * it } ?: source.scaleY
        val alpha = deviceCardScrollOpacity(visibility ?: source.alpha)
        card.alpha = alpha
        val step = ((1f - alpha) * 64f).roundToInt().coerceIn(0, 64)
        if (lastStep == step) return
        lastStep = step
        val effect = if (step == 0) null else effects[step] ?: run {
            val radius = 18f * card.resources.displayMetrics.density * step / 64f
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).also { effects[step] = it }
        }
        card.setRenderEffect(effect)
    }

    fun clear() {
        card.setRenderEffect(null)
        effects.fill(null)
        lastStep = -1
    }
}
