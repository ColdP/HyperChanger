package btm.m.liquidglass.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator

/** One overlay's visibility transition, owned and disposed by its Activity Session. */
internal class NavigationSlideTransition(
    private val view: View,
    private val baseTranslation: () -> Float,
) {
    private var fraction = 0f
    private var showing = false
    private var animator: ValueAnimator? = null

    fun setVisible(visible: Boolean) {
        if (showing == visible) return
        showing = visible
        cancelAnimation()
        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Preserve the previous composition throughout exit, and reverse from
        // the current progress if the host row returns before exit completes.
        view.visibility = View.VISIBLE
        refresh()
        val target = if (visible) 1f else 0f
        if (fraction == target) {
            if (!visible) view.visibility = View.GONE
            return
        }
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = ((if (visible) 260L else 200L) * kotlin.math.abs(target - fraction)).toLong().coerceAtLeast(1L)
            interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            addUpdateListener {
                fraction = it.animatedValue as Float
                refresh()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    fraction = target
                    refresh()
                    if (!showing) view.visibility = View.GONE
                }
            })
        }
        // Assign before start: disabled system animations may finish synchronously.
        animator?.start()
    }

    fun refresh() {
        val base = baseTranslation()
        val height = maxOf(view.height, view.layoutParams?.height ?: 0).coerceAtLeast(0)
        view.translationY = base + (1f - fraction) * (height + (-base).coerceAtLeast(0f))
    }

    fun hideImmediately() {
        showing = false
        cancelAnimation()
        fraction = 0f
        refresh()
        view.visibility = View.GONE
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun cancelAnimation() {
        animator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
    }
}
