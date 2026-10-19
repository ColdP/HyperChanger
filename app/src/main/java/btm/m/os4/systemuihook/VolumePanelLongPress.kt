// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Observe native slider gestures without replacing their touch/animation listeners. Main thread only. */
internal class VolumePanelLongPress(
    private val canExpand: (View) -> Boolean,
    private val expand: (View) -> Unit,
) {
    private class Gesture(val x: Float, val y: Float, val downTime: Long) {
        var pending: Runnable? = null
        var consumed = false
    }
    private val gestures = WeakHashMap<View, Gesture>()
    private var dispatchingCancel = false

    /** True consumes the remainder only after a successful long press. */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        if (dispatchingCancel) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel(view)
            if (!canExpand(view) || event.pointerCount != 1) return false
            val gesture = Gesture(event.rawX, event.rawY, event.downTime)
            gestures[view] = gesture
            val weakView = WeakReference(view)
            val task = Runnable {
                val target = weakView.get() ?: return@Runnable
                if (gestures[target] !== gesture) return@Runnable
                gesture.pending = null
                if (!target.isAttachedToWindow || !target.isShown || !canExpand(target)) {
                    gestures.remove(target)
                    return@Runnable
                }
                // Finish native slider tracking before the panel reparents its columns.
                val cancel = MotionEvent.obtain(gesture.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                dispatchingCancel = true
                try { target.onTouchEvent(cancel) } finally {
                    dispatchingCancel = false
                    cancel.recycle()
                }
                gesture.consumed = true
                target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                expand(target)
            }
            gesture.pending = task
            view.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
            return false
        }
        val gesture = gestures[view] ?: return false
        if (gesture.consumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancel(view)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
                val dx = event.rawX - gesture.x
                val dy = event.rawY - gesture.y
                if (event.pointerCount != 1 || dx * dx + dy * dy > slop * slop) cancel(view)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel(view)
        }
        return false
    }

    private fun cancel(view: View) {
        gestures.remove(view)?.pending?.let(view::removeCallbacks)
    }
}
