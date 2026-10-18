// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.xiaoaihook

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * MGL glass layer for the floating input module.
 *
 * Evidence from Super XiaoAI 8.3.30.4420:
 *
 * - `nm1.f` (GlassLayerController) keeps exactly ONE full-screen `GlassCardMglTextureView` inside
 *   `R.id.glass_container`. `nm1.f.m()` adds it with `FrameLayout.LayoutParams(-1, -1)` and
 *   `nm1.f.k()` feeds it screen-sized geometry (`ja.getScreenWidth/getScreenHeight`).
 * - `GlassCardMglTextureView.v()` passes the TextureView's OWN `getWidth()/getHeight()` into
 *   `s81.h0.attachPassBlur`, which sizes the SurfaceControl pass-blur buffer from that rect. A
 *   TextureView smaller than the screen therefore produces a wrong buffer and an intermittent or
 *   misplaced background - the previous per-input-sized container did exactly that.
 * - `s81.f0.attach()` keys the shared channel on `ViewRootImpl.getSurfaceControl()` (see
 *   `s81.f0.j(view)`), i.e. the WINDOW surface, and returns true when that surface is unchanged.
 *   A second TextureView inside the same window therefore *reuses* the channel instead of
 *   stealing it.
 * - `GlassCardMglTextureView.attachHost()` returns early while the TextureView has no size, and
 *   `onSizeChanged` re-attaches, so mounting order does not matter.
 *
 * Consequences implemented here:
 *
 * - The layer is a full-screen `GlassCardMglTextureView` added to the app's existing
 *   `glass_container`, so window coordinates and the shared pass-blur channel stay identical to
 *   the native card glass.
 * - Nothing is ever added to the input module's `ConstraintLayout`; the host view's layout params,
 *   constraints, size, translation and padding are never touched.
 * - The host background is only hidden after the glass actually produced a frame, so a failed
 *   glass can never leave the input without any background.
 */
internal class XiaoAiInputGlass(private val target: View) {
    private var texture: View? = null
    private var originalBackground: Drawable? = null
    private var originalBackgroundAlpha = 255
    private var hiddenBackground: Drawable? = null
    private var detachedBackground = false
    private var handedOver = false
    private var released = false
    private var lastRadius = Float.NaN

    /** True once the MGL layer is mounted. */
    val active: Boolean get() = texture != null

    /** True once the glass produced a frame and the host background has been handed over. */
    val hasHandedOver: Boolean get() = handedOver

    companion object {
        private const val TEXTURE_CLASS =
            "com.xiaomi.voiceassist.shaderanimation.mgl.glasscard.GlassCardMglTextureView"
        private const val CONTROLLER_CLASS = "nm1.f"
        private const val PANEL_STATE_CLASS =
            "com.xiaomi.voiceassist.shaderanimation.mgl.glasscard.GlassCardMglTextureView\$d"
        private const val ROTATION_READER_CLASS =
            "com.xiaomi.voiceassist.shaderanimation.mgl.glasscard.b\$a"

        /** `nm1.f$b` is the concrete rotation adapter; `b$a` above is only its interface. */
        private const val ROTATION_ADAPTER_CLASS = "nm1.f\$b"
        private const val DEFAULT_EXCLUDE_CLASS = "com.xiaomi.voiceassist.baselibrary.utils.n1"
        private const val UTILITY_CLASS = "com.xiaomi.voiceassistant.utils.ja"

        /**
         * Mirrors `m2.m(view)`: the window must own a native glass container and the device must
         * support the MGL glass profile, otherwise the module leaves the input completely alone.
         */
        fun isSupported(view: View): Boolean = runCatching {
            val container = findGlassContainer(view)
            container is FrameLayout &&
                view.context.classLoader.loadClass(CONTROLLER_CLASS)
                    .getMethod("isGlassSupported", Context::class.java)
                    .invoke(null, view.context) == true
        }.getOrDefault(false)

        private fun findGlassContainer(view: View): View? = runCatching {
            val id = view.resources.getIdentifier("glass_container", "id", view.context.packageName)
            if (id == 0) null else view.rootView.findViewById<View>(id)
        }.getOrNull()
    }

    /**
     * Mounts the full-screen glass TextureView into the window's native glass container.
     * Must run on the main thread. Returns true when the layer is running.
     */
    fun mount(): Boolean {
        if (released) return false
        val mounted = texture
        if (mounted != null) {
            // The window can be recreated while the module stays installed; remount in that case.
            if (mounted.rootView === target.rootView) return true
            detachTexture()
        }
        if (!target.isAttachedToWindow) return false
        return runCatching { create() }.getOrElse {
            GlassDiagnostics.warn(target.context, "input-mount", "Cannot mount floating input glass", it)
            detachTexture()
            false
        }
    }

    private fun create(): Boolean {
        val context = target.context
        val loader = context.classLoader
        val container = findGlassContainer() as? FrameLayout ?: return false
        val state = loader.loadClass(PANEL_STATE_CLASS)
        val panel = requireNotNull(state.enumConstants).first { (it as Enum<*>).name == "PANEL" }
        val view = loader.loadClass(TEXTURE_CLASS).getConstructor(Context::class.java)
            .newInstance(context) as View
        val cls = view.javaClass
        // The appear gate must exist before the surface arrives: l() resumes the scene with it.
        cls.getMethod("setFirstFrameListener", Runnable::class.java).invoke(view, Runnable { onFirstFrame() })
        cls.getMethod("setState", state).invoke(view, panel)
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        // Same screen size, exclude list and rotation adapter as nm1.f.k().
        val utility = loader.loadClass(UTILITY_CLASS)
        val width = utility.getMethod("getScreenWidth", Context::class.java).invoke(null, context) as Int
        val height = utility.getMethod("getScreenHeight", Context::class.java).invoke(null, context) as Int
        val excludes = loader.loadClass(DEFAULT_EXCLUDE_CLASS).getMethod("getDefaultExcludeLayers")
            .invoke(null) as List<*>
        val reader = loader.loadClass(ROTATION_ADAPTER_CLASS).getConstructor(Context::class.java)
            .newInstance(context)
        cls.getMethod(
            "enableLiveBackground", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Array<String>::class.java, loader.loadClass(ROTATION_READER_CLASS),
        ).invoke(view, width, height, excludes.filterIsInstance<String>().toTypedArray(), reader)
        cls.getMethod("setRenderEnabled", Boolean::class.javaPrimitiveType).invoke(view, true)
        cls.getMethod("setLiveBackgroundNeeded", Boolean::class.javaPrimitiveType).invoke(view, true)
        texture = view
        lastRadius = radius(target)
        attachHost(view, lastRadius)
        GlassDiagnostics.once(
            context, "input-mount",
            "Floating input glass mounted: host=" + target.javaClass.simpleName +
                " screen=" + width + "x" + height,
        )
        return true
    }

    private fun attachHost(view: View, radiusPx: Float) {
        view.javaClass.getMethod(
            "attachHost", View::class.java, Float::class.javaPrimitiveType, Runnable::class.java,
        ).invoke(view, target, radiusPx, null)
    }

    /**
     * Keeps the glass radius in sync with the host's current outline. `attachHost` would re-add the
     * tracker's pre-draw listener and reset its snapshot state, so only the radius is pushed.
     */
    fun syncRadius() {
        val view = texture ?: return
        val current = radius(target)
        if (current == lastRadius) return
        lastRadius = current
        runCatching {
            view.javaClass.getMethod("setHostRadiusPx", Float::class.javaPrimitiveType).invoke(view, current)
        }
    }

    /**
     * Re-applies the background handover after the app replaced the host drawable, which happens on
     * every input state change. Only meaningful once the glass has drawn a frame.
     */
    fun syncHostBackground() {
        if (released || !handedOver || texture == null) return
        hideHostBackground()
    }

    /**
     * Called from the scene's first-frame callback, i.e. on the MGL render thread. All view work is
     * posted to the host's handler.
     */
    private fun onFirstFrame() {
        if (released) return
        handedOver = true
        target.post {
            if (released || texture == null) return@post
            hideHostBackground()
            GlassDiagnostics.once(
                target.context, "input-first-frame",
                "Floating input glass first frame drawn, host background handed over",
            )
        }
    }

    /**
     * Hides the host's own rounded background so the glass underneath becomes visible.
     * The vendor drawable is shared through constant state, so it is cloned before mutating.
     */
    private fun hideHostBackground() {
        val view = target
        val current = view.background ?: return
        if (detachedBackground && current === hiddenBackground) return
        originalBackground = current
        originalBackgroundAlpha = current.alpha
        val clone = runCatching { current.constantState?.newDrawable(view.resources)?.mutate() }.getOrNull() ?: return
        clone.alpha = 0
        hiddenBackground = clone
        detachedBackground = true
        view.background = clone
        view.invalidate()
    }

    private fun restoreHostBackground() {
        val view = target
        if (!detachedBackground) return
        detachedBackground = false
        if (view.background === hiddenBackground) {
            val restore = originalBackground
            if (restore != null) {
                runCatching { restore.alpha = originalBackgroundAlpha }
                view.background = restore
                view.invalidate()
            }
        }
        hiddenBackground = null
    }

    private fun findGlassContainer(): View? = runCatching {
        val id = target.resources.getIdentifier("glass_container", "id", target.context.packageName)
        if (id == 0) null else target.rootView.findViewById<View>(id)
    }.getOrNull()

    private fun detachTexture() {
        lastRadius = Float.NaN
        val view = texture ?: return
        texture = null
        runCatching { view.javaClass.getMethod("destroy").invoke(view) }
            .onFailure { GlassDiagnostics.warn(target.context, "input-destroy", "Glass destroy failed", it) }
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Detaches the layer and restores the native host background. Safe to call repeatedly. */
    fun release() {
        if (released) return
        released = true
        detachTexture()
        restoreHostBackground()
    }

    private fun radius(view: View): Float {
        val outline = Outline()
        runCatching { view.background?.getOutline(outline) }
        if (!outline.isEmpty && outline.radius >= 0f) return outline.radius
        return 28f * view.resources.displayMetrics.density
    }
}
