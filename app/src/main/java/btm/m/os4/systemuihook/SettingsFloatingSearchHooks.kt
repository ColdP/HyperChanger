// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Presentation only: the original homepage listener, ActionMode, input and results own search. */
internal class SettingsFloatingSearchHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val sessions = IdentityHashMap<Any, Session>()
    private var installed = false
    private lateinit var anchorField: Field
    private lateinit var listField: Field
    private lateinit var panelAnchorField: Field
    private lateinit var containerField: Field
    private lateinit var inputField: Field

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed || param.packageName != "com.android.settings" || !readSettingsFloatingSearchSettings(preferences).enabled) return
        val fragment = param.defaultClassLoader.loadClass("com.android.settings.SettingsFragment")
        val panel = param.defaultClassLoader.loadClass("miuix.appcompat.internal.app.widget.SearchActionModeView")
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        anchorField = field(fragment, "mAnchorView")
        listField = field(fragment, "mListView")
        panelAnchorField = field(panel, "mAnchorView")
        containerField = field(panel, "mSearchContainer")
        inputField = field(panel, "mInputView")
        val created = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val destroyed = fragment.getDeclaredMethod("onDestroyView")
        val getActivity = fragment.getMethod("getActivity")
        val start = panel.getDeclaredMethod("notifyAnimationStart", Boolean::class.javaPrimitiveType)
        val update = panel.getDeclaredMethod("notifyAnimationUpdate", Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val end = panel.getDeclaredMethod("notifyAnimationEnd", Boolean::class.javaPrimitiveType)
        installed = true
        hookDeoptimized(created).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:create").intercept { chain ->
                val result = chain.proceed()
                val host = chain.thisObject ?: return@intercept result
                runCatching {
                    sessions.remove(host)?.destroy()
                    val activity = getActivity.invoke(host) as? Activity ?: return@runCatching
                    val root = chain.args[0] as? View ?: return@runCatching
                    val anchor = anchorField.get(host) as? ViewGroup ?: return@runCatching
                    val list = listField.get(host) as? View ?: return@runCatching
                    check(anchor.findViewById<TextView>(android.R.id.input) != null && anchor.hasOnClickListeners())
                    val session = Session(activity, root, anchor, list, preferences)
                    sessions[host] = session
                    try { session.attach() } catch (error: Throwable) {
                        sessions.remove(host)
                        session.destroy()
                        throw error
                    }
                }.onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings search", it) }
                result
            }
        hookDeoptimized(destroyed).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:destroy").intercept { chain ->
                sessions.remove(chain.thisObject)?.destroy()
                chain.proceed()
            }
        hookDeoptimized(start).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:start").intercept { chain ->
                val view = chain.thisObject as? ViewGroup
                val entering = chain.args[0] as Boolean
                val session = view?.let(::sessionFor)
                if (!entering) session?.safely { captureExit() }
                val result = chain.proceed()
                if (view != null) session?.safely { begin(view, entering) }
                result
            }
        hookDeoptimized(update).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:update").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { view ->
                    sessionFor(view)?.safely { progress(chain.args[0] as Boolean, chain.args[1] as Float) }
                }
                result
            }
        hookDeoptimized(end).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:end").intercept { chain ->
                val session = (chain.thisObject as? ViewGroup)?.let(::sessionFor)
                val result = chain.proceed()
                session?.safely { finish(chain.args[0] as Boolean) }
                result
            }
    }

    private fun sessionFor(panel: ViewGroup): Session? {
        sessions.values.firstOrNull { it.panel === panel }?.let { return it }
        val anchor = runCatching { (panelAnchorField.get(panel) as? WeakReference<*>)?.get() }.getOrNull() ?: return null
        return sessions.values.firstOrNull { it.anchor === anchor }
    }

    private enum class Phase { HOME, ENTERING, SEARCH, EXITING }

    private inner class Session(
        val activity: Activity,
        val root: View,
        val anchor: ViewGroup,
        val list: View,
        val preferences: SharedPreferences,
    ) : Application.ActivityLifecycleCallbacks {
        private val decor = activity.window.decorView as ViewGroup
        private val anchorHeight = anchor.layoutParams.height
        private val anchorTopMargin = (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
        private val anchorBottomMargin = (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
        private val anchorAlpha = anchor.alpha
        private val anchorVisibility = anchor.visibility
        private val anchorAccessibility = anchor.importantForAccessibility
        private val originalPadding = intArrayOf(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
        private val originalClipToPadding = (list as? ViewGroup)?.clipToPadding
        private var overlay: FrameLayout? = null
        private var pillView: SettingsSearchPillView? = null
        private var materialSurface: SettingsSearchMaterialView? = null
        private var destroyed = false
        private var resumed = true
        private var registered = false
        private var collapsed = false
        private var pendingOpen = false
        private var phase = Phase.HOME
        private var transitionStart = 0f
        private var expanded = 0f
        private val home = RectF()
        private val target = RectF()
        private val presentation = RectF()
        private var label = ""
        private var configuration = readSettingsFloatingSearchSettings(preferences)
        private var nativeContainer: View? = null
        private var nativeInput: EditText? = null
        private var nativeAlpha = 1f
        var panel: ViewGroup? = null
            private set
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private val hint = UnlockIslandRuntime.tr(activity, "settingsFloatingSearchHint", "搜索")
        private val listener = ViewTreeObserver.OnPreDrawListener {
            safely { synchronize() }
            true
        }
        private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_SETTINGS_FLOATING_SEARCH || key == KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS) {
                root.post { safely { configuration = readSettingsFloatingSearchSettings(preferences); synchronize() } }
            }
        }
        private val timeout = Runnable {
            safely { if (pendingOpen && phase == Phase.HOME) { pendingOpen = false; synchronize() } }
        }

        fun attach() {
            check(root !== decor)
            val material = SettingsSearchMaterialView(activity) { error -> root.post { safely { throw error } } }
            materialSurface = material
            material.visibility = View.INVISIBLE
            decor.addView(material, ViewGroup.LayoutParams(1, 1))
            val container = FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
                isForceDarkAllowed = false
                isClickable = false
                isFocusable = false
                visibility = View.INVISIBLE
            }
            val pill = SettingsSearchPillView(
                context = activity,
                onClick = { open() },
                onScaleChanged = { sx, sy, tx ->
                    materialSurface?.let {
                        it.scaleX = sx
                        it.scaleY = sy
                        it.translationX = tx
                    }
                },
            )
            container.addView(pill, FrameLayout.LayoutParams(1, 1))
            overlay = container
            pillView = pill
            label = hint
            decor.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            registered = true
            decor.viewTreeObserver.addOnPreDrawListener(listener)
            preferences.registerOnSharedPreferenceChangeListener(changed)
            activity.application.registerActivityLifecycleCallbacks(this)
            HookModuleLogger.log(module, Log.INFO, TAG,
                "Attached native MiBlur search material")
        }

        fun safely(action: Session.() -> Unit) {
            if (destroyed) return
            try { action() } catch (error: Throwable) {
                destroy()
                HookModuleLogger.log(module, Log.WARN, TAG, "Restored native Settings search", error)
            }
        }

        private fun open() = safely {
            if (phase != Phase.HOME || pendingOpen || !configuration.enabled) return@safely
            pendingOpen = true
            // A single real click retains enterprise restrictions, initialization, statistics and throttling.
            val handled = anchor.performClick()
            if (!handled) pendingOpen = false
            root.removeCallbacks(timeout)
            root.postDelayed(timeout, 1200L)
        }

        fun captureExit() {
            label = nativeInput?.text?.toString()?.takeIf { it.isNotEmpty() } ?: hint
            if (phase == Phase.SEARCH) nativeContainer?.let { boundsInto(it, target) }
        }

        fun begin(view: ViewGroup, entering: Boolean) {
            pendingOpen = false
            root.removeCallbacks(timeout)
            panel = view
            val container = containerField.get(view) as View
            if (nativeContainer !== container) {
                restoreNative()
                nativeContainer = container
                nativeAlpha = container.alpha
            }
            nativeInput = inputField.get(view) as EditText
            // Preserve the visible fraction when native search is reversed before entry finishes.
            transitionStart = if (phase == Phase.ENTERING || phase == Phase.EXITING) expanded else if (entering) 0f else 1f
            phase = if (entering) Phase.ENTERING else Phase.EXITING
            expanded = transitionStart
            if (entering) label = hint
            synchronize()
        }

        fun progress(entering: Boolean, fraction: Float) {
            if (phase != Phase.ENTERING && phase != Phase.EXITING) return
            // MIUIX runs 0→1 in both directions, not 1→0 on exit.
            expanded = lerp(transitionStart, if (entering) 1f else 0f, fraction.coerceIn(0f, 1f))
            synchronize()
        }

        fun finish(entering: Boolean) {
            expanded = if (entering) 1f else 0f
            phase = if (entering) Phase.SEARCH else Phase.HOME
            restoreNative()
            if (!entering) {
                label = hint
                nativeContainer = null
                nativeInput = null
                panel = null
            }
            synchronize()
        }

        private fun boundsInto(view: View, out: RectF) {
            view.getLocationInWindow(location)
            val left = (location[0] - origin[0]).toFloat()
            val top = (location[1] - origin[1]).toFloat()
            out.set(left, top, left + view.width, top + view.height)
        }

        private fun updatePresentationBounds(): RectF {
            val p = expanded
            if (phase == Phase.HOME || target.isEmpty) {
                presentation.set(home)
            } else {
                presentation.set(
                    lerp(home.left, target.left, p), lerp(home.top, target.top, p),
                    lerp(home.right, target.right, p), lerp(home.bottom, target.bottom, p),
                )
            }
            return presentation
        }

        private fun synchronize() {
            val container = overlay ?: return
            val pill = pillView ?: return
            val active = configuration.enabled && resumed && root.isShown && !activity.isFinishing && !activity.isDestroyed
            if (!active || container.width <= 0 || container.height <= 0) {
                container.visibility = View.INVISIBLE
                materialSurface?.visibility = View.INVISIBLE
                if (!configuration.enabled) { restoreNative(); restoreHome() }
                return
            }
            container.getLocationInWindow(origin)
            val viewport = RectF()
            boundsInto(root, viewport)
            val density = activity.resources.displayMetrics.density
            val insets = decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
            val width = settingsSearchWidth(viewport.width(), density, configuration.widthScale)
            val height = settingsSearchHeight(density, configuration.heightScale)
            val bottom = minOf(viewport.bottom, container.height - (insets?.bottom ?: 0).toFloat()) - 12f * density
            // Do not move the origin with the IME while the capsule is becoming native input.
            if (phase == Phase.HOME || home.isEmpty) {
                home.set(viewport.centerX() - width / 2f, bottom - height, viewport.centerX() + width / 2f, bottom)
            }
            if (phase == Phase.ENTERING || phase == Phase.SEARCH) {
                nativeContainer?.takeIf { it.width > 0 && it.height > 0 }?.let { boundsInto(it, target) }
            }
            // A GONE header is excluded by NestedHeaderLayout's range calculation, including margins.
            val params = anchor.layoutParams
            var changed = params.height != 0
            params.height = 0
            (params as? ViewGroup.MarginLayoutParams)?.let {
                changed = changed || it.topMargin != 0 || it.bottomMargin != 0
                it.topMargin = 0; it.bottomMargin = 0
            }
            if (changed) anchor.layoutParams = params
            anchor.visibility = View.GONE
            anchor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            collapsed = true
            val padding = originalPadding[3] + height.roundToInt() + (24f * density).roundToInt()
            if (list.paddingBottom != padding) list.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], padding)
            (list as? ViewGroup)?.let { if (it.clipToPadding) it.clipToPadding = false }
            val transitioning = phase == Phase.ENTERING || phase == Phase.EXITING
            val mix = handoff(expanded)
            if (transitioning) nativeContainer?.alpha = nativeAlpha * mix
            val vis = if (phase == Phase.SEARCH) View.INVISIBLE else View.VISIBLE
            container.visibility = vis
            val rect = updatePresentationBounds()
            val dark = activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val w = rect.width().roundToInt().coerceAtLeast(1)
            val h = rect.height().roundToInt().coerceAtLeast(1)
            pill.update(
                settings = configuration,
                dark = dark,
                text = label,
                pillWidth = w,
                pillHeight = h,
                left = rect.left,
                top = rect.top,
                interactionEnabled = phase == Phase.HOME && !pendingOpen,
                motionAlpha = 1f - mix,
                motionBlurPx = mix * 8f * density,
            )
            materialSurface?.let { material ->
                if (material.layoutParams.width != w || material.layoutParams.height != h) {
                    material.layoutParams = material.layoutParams.apply { this.width = w; this.height = h }
                }
                decor.getLocationInWindow(location)
                material.x = rect.left + origin[0] - location[0]
                material.y = rect.top + origin[1] - location[1]
                material.alpha = 1f - mix
                material.visibility = vis
                material.update(configuration, dark)
            }
        }

        private fun restoreNative() { nativeContainer?.alpha = nativeAlpha }

        private fun restoreHome() {
            if (!collapsed) return
            val params = anchor.layoutParams
            params.height = anchorHeight
            (params as? ViewGroup.MarginLayoutParams)?.let {
                anchorTopMargin?.let { v -> it.topMargin = v }
                anchorBottomMargin?.let { v -> it.bottomMargin = v }
            }
            anchor.layoutParams = params
            anchor.alpha = anchorAlpha
            anchor.visibility = anchorVisibility
            anchor.importantForAccessibility = anchorAccessibility
            list.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
            originalClipToPadding?.let { (list as? ViewGroup)?.clipToPadding = it }
            collapsed = false
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            sessions.entries.removeAll { it.value === this }
            // Cleanup failures must not prevent the host's onDestroyView/original callbacks.
            fun clean(action: () -> Unit) {
                runCatching(action).onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Search presentation cleanup failed", it) }
            }
            clean { root.removeCallbacks(timeout) }
            clean { decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
            materialSurface?.let { view ->
                clean { view.release() }
                clean { (view.parent as? ViewGroup)?.removeView(view) }
            }
            materialSurface = null
            if (registered) {
                clean { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
                clean { activity.application.unregisterActivityLifecycleCallbacks(this) }
                registered = false
            }
            clean { restoreNative() }
            clean { restoreHome() }
            pillView?.release()
            pillView = null
            overlay?.let { view ->
                clean { (view.parent as? ViewGroup)?.removeView(view) }
            }
            overlay = null
            nativeContainer = null
            nativeInput = null
            panel = null
        }

        override fun onActivityResumed(target: Activity) { if (target === activity) { resumed = true; safely { synchronize() } } }
        override fun onActivityPaused(target: Activity) { if (target === activity) {
            resumed = false
            materialSurface?.visibility = View.INVISIBLE
            overlay?.visibility = View.INVISIBLE
            restoreNative()
        } }
        override fun onActivityDestroyed(target: Activity) { if (target === activity) destroy() }
        override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(target: Activity) = Unit
        override fun onActivityStopped(target: Activity) = Unit
        override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
    }

    private companion object {
        const val TAG = "SettingsFloatingSearch"
        fun lerp(start: Float, end: Float, p: Float): Float = start + (end - start) * p
        fun handoff(p: Float): Float = ((p - .72f) / .28f).coerceIn(0f, 1f)
    }
}

/**
 * Lightweight native replacement for the live Settings floating search pill.
 * Avoids bootstrapping Jetpack Compose / Recomposer inside com.android.settings while
 * matching SettingsFloatingSearchSurface's shadow, icon, text, press/drag motion and handoff.
 */
private class SettingsSearchPillView(
    context: Context,
    private val onClick: () -> Unit,
    private val onScaleChanged: (scaleX: Float, scaleY: Float, translationX: Float) -> Unit,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val shadowSpace = (64f * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val pillPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(3, 0, 0, 0)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var settings = SettingsFloatingSearchSettings()
    private var dark = false
    private var label = ""
    private var pillWidth = 1
    private var pillHeight = 1
    private var interactionEnabled = true
    private var lastBlurPx = 0f

    private var pressFraction = 0f
    private var dragValue = 0f
    private var pressAnimator: ValueAnimator? = null
    private var dragAnimator: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
        isForceDarkAllowed = false
        isFocusable = false
    }

    fun update(
        settings: SettingsFloatingSearchSettings,
        dark: Boolean,
        text: String,
        pillWidth: Int,
        pillHeight: Int,
        left: Float,
        top: Float,
        interactionEnabled: Boolean,
        motionAlpha: Float,
        motionBlurPx: Float,
    ) {
        var needsRedraw = false
        if (this.settings != settings || this.dark != dark || this.label != text) {
            this.settings = settings
            this.dark = dark
            this.label = text
            contentDescription = text
            needsRedraw = true
        }
        this.interactionEnabled = interactionEnabled
        if (this.pillWidth != pillWidth || this.pillHeight != pillHeight) {
            this.pillWidth = pillWidth
            this.pillHeight = pillHeight
            val totalW = pillWidth + shadowSpace * 2
            val totalH = pillHeight + shadowSpace * 2
            val lp = layoutParams
            if (lp.width != totalW || lp.height != totalH) {
                lp.width = totalW
                lp.height = totalH
                layoutParams = lp
            }
            rebuildPath()
            needsRedraw = true
        }
        val targetX = left - shadowSpace
        val targetY = top - shadowSpace
        if (x != targetX) x = targetX
        if (y != targetY) y = targetY
        if (alpha != motionAlpha) alpha = motionAlpha
        if (abs(lastBlurPx - motionBlurPx) >= 0.25f || (motionBlurPx <= 0.5f && lastBlurPx > 0.5f)) {
            lastBlurPx = motionBlurPx
            setRenderEffect(
                if (motionBlurPx > 0.5f) RenderEffect.createBlurEffect(motionBlurPx, motionBlurPx, Shader.TileMode.CLAMP)
                else null,
            )
        }
        if (needsRedraw) invalidate()
    }

    private fun rebuildPath() {
        pillPath.reset()
        if (pillWidth > 0 && pillHeight > 0) {
            val radius = minOf(pillWidth, pillHeight) * 0.5f
            val l = shadowSpace.toFloat()
            val t = shadowSpace.toFloat()
            pillPath.addRoundRect(l, t, l + pillWidth, t + pillHeight, radius, radius, Path.Direction.CW)
        }
    }

    private fun applyTransform() {
        val pressScale = 1f + 0.015f * pressFraction
        val absDrag = abs(dragValue)
        val sx = pressScale * (1f + absDrag * 0.04f)
        val sy = pressScale * (1f - absDrag * 0.02f)
        val tx = dragValue * 5f * density
        pivotX = width * 0.5f
        pivotY = height * 0.5f
        scaleX = sx
        scaleY = sy
        translationX = tx
        onScaleChanged(sx, sy, tx)
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressFraction, target).apply {
            duration = if (target > 0f) 110L else 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressFraction = it.animatedValue as Float
                applyTransform()
            }
            start()
        }
    }

    private fun animateDragBack() {
        if (dragValue == 0f) return
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(dragValue, 0f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                dragValue = it.animatedValue as Float
                applyTransform()
            }
            start()
        }
    }

    private fun isInsidePill(px: Float, py: Float): Boolean =
        px >= shadowSpace && px <= shadowSpace + pillWidth &&
            py >= shadowSpace && py <= shadowSpace + pillHeight

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsidePill(event.x, event.y)) return false
                dragAnimator?.cancel()
                tracking = true
                dragging = false
                downX = event.x
                downY = event.y
                animatePress(1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    dragValue = (dx / pillWidth.coerceAtLeast(1)).coerceIn(-1f, 1f)
                    applyTransform()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val shouldClick = !dragging && isInsidePill(event.x, event.y)
                tracking = false
                dragging = false
                animatePress(0f)
                animateDragBack()
                if (shouldClick) {
                    performClick()
                    onClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                dragging = false
                animatePress(0f)
                animateDragBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        if (pillWidth <= 0 || pillHeight <= 0) return
        // Outer shadow outside the continuous capsule outline.
        canvas.save()
        canvas.clipOutPath(pillPath)
        canvas.translate(0f, 3f * density)
        for (i in 16 downTo 1) {
            shadowPaint.strokeWidth = i * density
            canvas.drawPath(pillPath, shadowPaint)
        }
        canvas.restore()

        val foreground = if (dark) Color.WHITE else Color.BLACK
        val leftEdge = shadowSpace + 18f * density
        val rightEdge = shadowSpace + pillWidth - 18f * density
        val centerY = shadowSpace + pillHeight * 0.5f

        canvas.save()
        canvas.clipPath(pillPath)

        // Draw search icon (24x24 viewport matching MiuixIcons.Regular.Search proportions).
        val iconSize = (22f * (1f + settings.iconScale / 100f) * density).coerceAtLeast(1f)
        val iconTop = centerY - iconSize * 0.5f
        val unit = iconSize / 24f
        iconPaint.color = foreground
        iconPaint.strokeWidth = 2.0f * unit
        canvas.drawCircle(leftEdge + 11f * unit, iconTop + 11f * unit, 7f * unit, iconPaint)
        canvas.drawLine(
            leftEdge + 16.2f * unit, iconTop + 16.2f * unit,
            leftEdge + 20.4f * unit, iconTop + 20.4f * unit,
            iconPaint,
        )

        // Draw single-line ellipsized hint/query label.
        val textLeft = leftEdge + iconSize + settings.iconTextSpacing * density
        val availableWidth = (rightEdge - textLeft).coerceAtLeast(0f)
        if (availableWidth > 1f && label.isNotEmpty()) {
            textPaint.color = foreground
            textPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                (17f * (1f + settings.textScale / 100f)).coerceAtLeast(1f),
                resources.displayMetrics,
            )
            val display = TextUtils.ellipsize(label, textPaint, availableWidth, TextUtils.TruncateAt.END)
            val fm = textPaint.fontMetrics
            val baseline = centerY - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(display, 0, display.length, textLeft, baseline, textPaint)
        }

        canvas.restore()
    }

    fun release() {
        pressAnimator?.cancel()
        pressAnimator = null
        dragAnimator?.cancel()
        dragAnimator = null
        setRenderEffect(null)
    }
}
