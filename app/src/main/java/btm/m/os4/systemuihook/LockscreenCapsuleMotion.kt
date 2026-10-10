// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** A small spring used by the capsule itself; it keeps the finger path and release animation in one model. */
internal class LockscreenCapsuleSpring(
    initial: Float = 0f,
    private val response: Float = .28f,
    private val damping: Float = .86f,
    private val onFrame: (Float) -> Unit,
) : Choreographer.FrameCallback {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var posted = false
    private var lastFrame = 0L

    fun snap(value: Float) {
        this.value = value
        velocity = 0f
        target = value
        onFrame(value)
    }

    fun aim(target: Float, velocity: Float = this.velocity) {
        this.target = target
        this.velocity = velocity
        if (!posted) {
            posted = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val dt = if (lastFrame == 0L) 1f / 120f
        else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, .05f)
        lastFrame = frameTimeNanos
        val omega = (2.0 * Math.PI / response.coerceAtLeast(.12f)).toFloat()
        val x = value - target
        val acceleration = -omega * omega * x - 2f * damping * omega * velocity
        velocity += acceleration * dt
        value += velocity * dt
        if (abs(value - target) < .0015f && abs(velocity) < .02f) {
            value = target
            velocity = 0f
        }
        onFrame(value)
        if (abs(value - target) >= .0015f || abs(velocity) >= .02f) {
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}

/** A compact version of HyperMusicCover's Jelly press/squeeze behavior for the local pill. */
internal class LockscreenCapsulePress(private val view: View) {
    private val spring = LockscreenCapsuleSpring(onFrame = ::apply)
    private var baseScaleX = 1f
    private var baseScaleY = 1f

    fun setBaseScale(x: Float, y: Float) {
        baseScaleX = x
        baseScaleY = y
        apply(spring.value)
    }

    fun press(down: Boolean) {
        spring.aim(if (down) 1f else 0f)
    }

    fun reset() = spring.snap(0f)

    private fun apply(value: Float) {
        val eased = value.coerceIn(0f, 1.2f)
        view.scaleX = baseScaleX * (1f - .045f * eased)
        view.scaleY = baseScaleY * (1f - .075f * eased)
    }
}

/** Shape and content transition used when the pill hands control back to the OEM media card. */
internal object LockscreenCapsuleCardMorph {
    fun run(source: View, target: View?, toNative: Boolean, onEnd: () -> Unit = {}) {
        val parent = source.parent as? ViewGroup
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val baseTx = source.translationX
        val baseTy = source.translationY
        val sourceCx = sourceLocation[0] + source.width / 2f
        val sourceCy = sourceLocation[1] + source.height / 2f
        val sourceW = source.width.coerceAtLeast(1).toFloat()
        val sourceH = source.height.coerceAtLeast(1).toFloat()
        val targetLocation = target?.let { IntArray(2).also(it::getLocationOnScreen) }
        val targetCx = targetLocation?.let { it[0] + target.width / 2f } ?: sourceCx
        val targetCy = targetLocation?.let { it[1] + target.height / 2f } ?: sourceCy
        val targetW = target?.width?.coerceAtLeast(1)?.toFloat() ?: sourceW * 1.18f
        val targetH = target?.height?.coerceAtLeast(1)?.toFloat() ?: sourceH * 1.18f
        val from = if (toNative) 0f else 1f
        val to = if (toNative) 1f else 0f
        target?.let {
            it.animate().cancel()
            if (toNative) {
                it.visibility = View.VISIBLE
                it.alpha = 0f
            }
        }
        source.animate().cancel()
        ValueAnimator.ofFloat(from, to).apply {
            duration = if (toNative) 360L else 300L
            interpolator = PathInterpolator(.2f, .9f, .2f, 1f)
            addUpdateListener { animation ->
                val p = animation.animatedValue as Float
                val cx = sourceCx + (targetCx - sourceCx) * p
                val cy = sourceCy + (targetCy - sourceCy) * p
                val w = sourceW + (targetW - sourceW) * p
                val h = sourceH + (targetH - sourceH) * p
                val scaleX = w / sourceW
                val scaleY = h / sourceH
                source.translationX = baseTx + (cx - sourceCx)
                source.translationY = baseTy + (cy - sourceCy)
                source.scaleX = scaleX
                source.scaleY = scaleY
                source.alpha = if (toNative) 1f - p * .72f else .28f + p * .72f
                target?.let {
                    it.alpha = if (toNative) p else 1f - p
                    if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    source.translationX = baseTx
                    source.translationY = baseTy
                    source.scaleX = 1f
                    source.scaleY = 1f
                    source.alpha = 1f
                    if (toNative) source.visibility = View.GONE else target?.visibility = View.GONE
                    target?.alpha = 1f
                    onEnd()
                }
            })
            start()
        }
    }
}

/**
 * Host for lockscreen immersive pages. Amap's process sends READY/STOP broadcasts after its
 * NativesModuleImmerseNavi is initialized; the host keeps the page below the capsule and fades
 * it with the same spring timing as the capsule morph.
 */
internal class LockscreenCapsuleImmersiveHost(
    private val host: ViewGroup,
) {
    companion object {
        const val ACTION = "btm.m.os4.systemuihook.LOCKSCREEN_CAPSULE_IMMERSIVE"
        const val EXTRA_STATE = "state"
        const val EXTRA_OVERVIEW = "overview"
        const val STATE_READY = "ready"
        const val STATE_STOP = "stop"
    }

    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private val layer = MapImmersiveLayer(context)
    private var registered = false
    private var ready = false
    private var overview = false
    private var shown = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            when (intent.getStringExtra(EXTRA_STATE)) {
                STATE_READY -> {
                    ready = true
                    if (shown) show(true)
                }
                STATE_STOP -> {
                    ready = false
                    show(false)
                }
            }
        }
    }

    init {
        layer.visibility = View.GONE
        layer.setOnClickListener { toggleOverview() }
        host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(ACTION))
            true
        }.getOrDefault(false)
    }

    fun destroy() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        handler.removeCallbacksAndMessages(null)
        runCatching { host.removeView(layer) }
    }

    fun setShown(value: Boolean) {
        shown = value
        show(value && ready)
    }

    fun toggleOverview() {
        overview = !overview
        layer.overview = overview
        context.sendBroadcast(Intent(ACTION).setPackage("com.autonavi.minimap")
            .putExtra(EXTRA_STATE, STATE_READY).putExtra(EXTRA_OVERVIEW, overview))
    }

    private fun show(value: Boolean) {
        layer.animate().cancel()
        if (value) {
            layer.visibility = View.VISIBLE
            layer.alpha = 0f
            layer.animate().alpha(1f).setDuration(360L).setInterpolator(PathInterpolator(.2f, .9f, .2f, 1f)).start()
        } else if (layer.visibility == View.VISIBLE) {
            layer.animate().alpha(0f).setDuration(240L).withEndAction {
                layer.visibility = View.GONE
                layer.alpha = 1f
            }.start()
        }
    }
}

private class MapImmersiveLayer(context: Context) : View(context) {
    var overview = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val route = Path()
    private var phase = 0f

    init {
        setBackgroundColor(Color.argb(235, 8, 18, 26))
        isClickable = true
        post(object : Runnable {
            override fun run() {
                phase = (phase + .035f) % 1f
                invalidate()
                postDelayed(this, 16L)
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(48, 120, 180, 220)
        canvas.drawCircle(w * .22f, h * .28f, min(w, h) * .2f, paint)
        canvas.drawCircle(w * .82f, h * .68f, min(w, h) * .26f, paint)
        route.reset()
        route.moveTo(w * .08f, h * .82f)
        route.cubicTo(w * .28f, h * .58f, w * .36f, h * .78f, w * .52f, h * .48f)
        route.cubicTo(w * .66f, h * .2f, w * .73f, h * .56f, w * .94f, h * .18f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(3f, w * .008f)
        paint.color = if (overview) Color.rgb(255, 183, 77) else Color.rgb(77, 196, 255)
        canvas.drawPath(route, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val t = if (overview) .18f + phase * .64f else .56f + phase * .25f
        val x = w * (0.08f + .86f * t)
        val y = h * (.82f - .2f * kotlin.math.sin(t * Math.PI).toFloat())
        canvas.drawCircle(x, y, max(6f, w * .016f), paint)
    }
}
