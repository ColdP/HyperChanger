// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-page Control Center hook for MiuiSystemUIPlugin (18.3.2.22.0+):
 * - Page 0: Native mainPanelContainer transformed in-place (preserves all custom layouts & edit mode).
 * - Secondary pages reuse native HyperOS Control Center widgets directly (zero hand-drawn tiles/media):
 *   - Media page: Native QSControlMiPlayDetailContent via MiPlayController.createMiPlayDetailView().
 *   - Connectivity page: Native QSCardItemView via QSCardsController.createViewHolder(parent, 2273).
 *   - Quick Tools page: Native QSTileItemView via QSListController.createViewHolder(parent, 8453).
 * - Performance-first architecture:
 *   - Zero deoptimize() on 120Hz touch/expand paths.
 *   - All hot-path reflection Methods/Fields cached once per session (zero per-frame class scans).
 *   - Secondary pages are lazily inflated ONLY when first swiped/selected, so Page 0 open has zero overhead.
 *   - Tile listening / MiPlay detail showing runs ONLY for the currently visible secondary page.
 */
internal class ControlCenterMultiPageHook(
    private val module: XposedModule,
    private val onBindMediaDetailContent: ((View, View) -> Unit)? = null,
) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val sessions = WeakHashMap<View, MultiPageSession>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settings = ControlCenterMultiPageSettings()

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) return
        syncPreferenceState(preferences)
        targetClasses.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let {
                onClassLoaded(it, preferences)
            }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (!CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) return
        if (clazz.name !in targetClasses || !scheduled.add(clazz)) return
        syncPreferenceState(preferences)
        // Never enumerate target methods inside ClassLoader.loadClass (ART crash audit).
        mainHandler.post {
            installSingleClass(clazz)
            val loader = clazz.classLoader ?: return@post
            targetClasses.forEach { name ->
                if (name != clazz.name) {
                    runCatching { loader.loadClass(name) }.getOrNull()?.let { sibling ->
                        if (scheduled.add(sibling)) installSingleClass(sibling)
                    }
                }
            }
        }
    }

    private fun syncPreferenceState(preferences: SharedPreferences) {
        settings = readControlCenterMultiPageSettings(preferences)
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in prefKeys) {
                settings = readControlCenterMultiPageSettings(preferences)
                mainHandler.post {
                    sessions.values.toList().forEach { it.applySettings(settings) }
                }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun installSingleClass(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            when (clazz.name) {
                MAIN_PANEL_CONTROLLER -> hookMainPanelController(clazz)
                WINDOW_VIEW_IMPL -> hookWindowViewImpl(clazz)
                MAIN_PANEL_TOUCH_CONTROLLER -> hookMainPanelTouchController(clazz)
                MAIN_PANEL_ANIM_CONTROLLER -> hookMainPanelAnimController(clazz)
                EXPAND_CONTROLLER -> hookExpandController(clazz)
                MODE_CONTROLLER -> hookModeController(clazz)
            }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(
                module,
                Log.WARN,
                "HyperChanger",
                "Could not hook control-center multi-page target: ${clazz.name}",
                error,
            )
        }
    }

    // Do NOT deoptimize hot 120Hz SystemUI methods (forces interpreter mode and causes severe jank).
    private fun hookMethod(m: Method, id: String) =
        module.hook(m)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId(id)

    private fun hookMainPanelController(clazz: Class<*>) {
        val getViewMethod = method(clazz, "getView", 0)
        method(clazz, "onCreate", 0)?.let { m ->
            hookMethod(m, "cc-multipage:main-onCreate").intercept { chain ->
                val res = chain.proceed()
                val controller = chain.thisObject ?: return@intercept res
                ensureSession(controller, getViewMethod)?.attachAndSync(settings)
                res
            }
        }
        method(clazz, "onResume", 0)?.let { m ->
            hookMethod(m, "cc-multipage:main-onResume").intercept { chain ->
                val res = chain.proceed()
                if (!settings.isEffective()) return@intercept res
                ensureSession(chain.thisObject, getViewMethod)?.onPanelStart()
                res
            }
        }
        method(clazz, "onDestroy", 0)?.let { m ->
            hookMethod(m, "cc-multipage:main-onDestroy").intercept { chain ->
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                view?.let { sessions.remove(it)?.destroy() }
                chain.proceed()
            }
        }
        method(clazz, "onStop", 0)?.let { m ->
            hookMethod(m, "cc-multipage:main-onStop").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                view?.let { sessions[it]?.onPanelStop() }
                res
            }
        }
        method(clazz, "updateResources", 0)?.let { m ->
            hookMethod(m, "cc-multipage:main-updateResources").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                view?.let { sessions[it]?.onLayoutMetricsChanged() }
                res
            }
        }
        method(clazz, "onConfigurationChanged", 1)?.let { m ->
            hookMethod(m, "cc-multipage:main-onConfig").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                view?.let { sessions[it]?.onLayoutMetricsChanged() }
                res
            }
        }
        method(clazz, "onKeyEvent", 1)?.let { m ->
            hookMethod(m, "cc-multipage:main-onKeyEvent").intercept { chain ->
                if (!settings.isEffective()) return@intercept chain.proceed()
                val event = chain.getArg(0) as? KeyEvent
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                val session = view?.let { sessions[it] }
                if (event != null && session != null && session.handleKeyEvent(event)) {
                    return@intercept java.lang.Boolean.TRUE
                }
                chain.proceed()
            }
        }
    }

    private fun hookWindowViewImpl(clazz: Class<*>) {
        val getWindowViewControllerMethod = method(clazz, "getWindowViewController", 0)
        method(clazz, "onInterceptTouchEvent", 1)?.let { m ->
            hookMethod(m, "cc-multipage:window-onInterceptTouch").intercept { chain ->
                if (!settings.isEffective()) return@intercept chain.proceed()
                val windowView = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
                val event = chain.getArg(0) as? MotionEvent ?: return@intercept chain.proceed()
                val session = sessions[windowView] ?: ensureSessionFromWindowView(windowView, getWindowViewControllerMethod)
                if (session != null) {
                    when (session.onInterceptTouchEvent(event)) {
                        InterceptDecision.INTERCEPT -> return@intercept true
                        InterceptDecision.BYPASS_PASS_TO_CHILD -> return@intercept false
                        InterceptDecision.DEFER_TO_STOCK -> Unit
                    }
                }
                chain.proceed()
            }
        }
        method(clazz, "onTouchEvent", 1)?.let { m ->
            hookMethod(m, "cc-multipage:window-onTouch").intercept { chain ->
                if (!settings.isEffective()) return@intercept chain.proceed()
                val windowView = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
                val event = chain.getArg(0) as? MotionEvent ?: return@intercept chain.proceed()
                val session = sessions[windowView]
                if (session != null && session.onTouchEvent(event)) {
                    return@intercept true
                }
                chain.proceed()
            }
        }
    }

    private fun hookMainPanelTouchController(clazz: Class<*>) {
        val getViewMethod = method(clazz, "getView", 0)
        method(clazz, "handleMotionEvent", 3)?.let { m ->
            hookMethod(m, "cc-multipage:touch-handleMotion").intercept { chain ->
                if (!settings.isEffective()) return@intercept chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                val session = view?.let { sessions[it] }
                if (session != null && session.isOwningTouchGesture()) {
                    return@intercept false
                }
                chain.proceed()
            }
        }
    }

    private fun hookMainPanelAnimController(clazz: Class<*>) {
        val containerField = findField(clazz, "mainPanelContainer")
        listOf("startHide", "forceToHide", "startShow", "forceToShow").forEach { name ->
            clazz.declaredMethods.filter { it.name == name }.forEach { m ->
                hookMethod(m, "cc-multipage:anim-$name-${m.parameterCount}").intercept { chain ->
                    val res = chain.proceed()
                    if (!settings.isEffective()) return@intercept res
                    val container = containerField?.let { runCatching { it.get(chain.thisObject) as? View }.getOrNull() }
                    val parent = container?.parent as? View ?: return@intercept res
                    val session = sessions[parent] ?: return@intercept res
                    when (name) {
                        "startHide", "forceToHide" -> session.onSecondaryPanelOverlayState(hidden = true, animated = name == "startHide")
                        "startShow", "forceToShow" -> session.onSecondaryPanelOverlayState(hidden = false, animated = name == "startShow")
                    }
                    res
                }
            }
        }
    }

    private fun hookExpandController(clazz: Class<*>) {
        val getViewMethod = method(clazz, "getView", 0)
        method(clazz, "onStart", 0)?.let { m ->
            hookMethod(m, "cc-multipage:expand-onStart").intercept { chain ->
                val res = chain.proceed()
                if (!settings.isEffective()) return@intercept res
                val windowView = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? ViewGroup }.getOrNull() }
                    ?: return@intercept res
                (sessions[windowView] ?: ensureSessionFromWindowView(windowView, null))?.onPanelStart()
                res
            }
        }
        method(clazz, "onStop", 0)?.let { m ->
            hookMethod(m, "cc-multipage:expand-onStop").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                view?.let { sessions[it]?.onPanelStop() }
                res
            }
        }
        method(clazz, "showPanel", 2)?.let { m ->
            hookMethod(m, "cc-multipage:expand-showPanel").intercept { chain ->
                val res = chain.proceed()
                if (!settings.isEffective()) return@intercept res
                val windowView = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? ViewGroup }.getOrNull() }
                    ?: return@intercept res
                (sessions[windowView] ?: ensureSessionFromWindowView(windowView, null))?.onPanelStart()
                res
            }
        }
        method(clazz, "notifyExpansionChanged", 1)?.let { m ->
            hookMethod(m, "cc-multipage:expand-notifyExpansion").intercept { chain ->
                val res = chain.proceed()
                if (!settings.isEffective()) return@intercept res
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                    ?: return@intercept res
                val expansion = (chain.getArg(0) as? Number)?.toFloat() ?: return@intercept res
                sessions[view]?.onExpansionChanged(expansion)
                res
            }
        }
        method(clazz, "notifyAppearanceChanged", 2)?.let { m ->
            hookMethod(m, "cc-multipage:expand-notifyAppearance").intercept { chain ->
                val res = chain.proceed()
                if (!settings.isEffective()) return@intercept res
                val windowView = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? ViewGroup }.getOrNull() }
                    ?: return@intercept res
                val appearance = (chain.getArg(0) as? Boolean) ?: false
                (sessions[windowView] ?: ensureSessionFromWindowView(windowView, null))?.onAppearanceChanged(appearance)
                res
            }
        }
        method(clazz, "setInMirror", 1)?.let { m ->
            hookMethod(m, "cc-multipage:expand-setInMirror").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                val mirror = (chain.getArg(0) as? Boolean) ?: false
                view?.let { sessions[it]?.onMirrorChanged(mirror) }
                res
            }
        }
    }

    private fun hookModeController(clazz: Class<*>) {
        val getViewMethod = method(clazz, "getView", 0)
        val getInEditModeMethod = method(clazz, "getInEditMode", 0)
        clazz.declaredMethods.filter { it.name == "changeMode" }.forEach { m ->
            hookMethod(m, "cc-multipage:mode-change-${m.parameterCount}").intercept { chain ->
                val res = chain.proceed()
                val view = getViewMethod?.let { runCatching { it.invoke(chain.thisObject) as? View }.getOrNull() }
                val session = view?.let { sessions[it] } ?: return@intercept res
                val inEdit = (getInEditModeMethod?.let { runCatching { it.invoke(chain.thisObject) as? Boolean }.getOrNull() })
                    ?: false
                session.onEditModeChanged(inEdit)
                res
            }
        }
    }

    private fun ensureSession(mainPanelController: Any?, getViewMethod: Method?): MultiPageSession? {
        mainPanelController ?: return null
        val windowView = (getViewMethod?.let { runCatching { it.invoke(mainPanelController) as? ViewGroup }.getOrNull() }
            ?: call(mainPanelController, "getView") as? ViewGroup) ?: return null
        val existing = sessions[windowView]
        if (existing != null) {
            existing.ensureSettingsSynced(settings)
            return existing
        }
        val created = MultiPageSession(windowView, mainPanelController, mainHandler, module, onBindMediaDetailContent)
        sessions[windowView] = created
        created.attachAndSync(settings)
        return created
    }

    private fun ensureSessionFromWindowView(windowView: ViewGroup, getWindowViewControllerMethod: Method?): MultiPageSession? {
        val existing = sessions[windowView]
        if (existing != null) {
            existing.ensureSettingsSynced(settings)
            return existing
        }
        val wvc = runCatching {
            if (getWindowViewControllerMethod != null) {
                getWindowViewControllerMethod.invoke(windowView)
            } else {
                call(windowView, "getWindowViewController")
            }
        }.getOrNull() ?: return null
        val mpc = runCatching { call(wvc, "getMainPanelController") }.getOrNull() ?: return null
        return ensureSession(mpc, null)
    }

    private enum class InterceptDecision {
        DEFER_TO_STOCK,
        INTERCEPT,
        BYPASS_PASS_TO_CHILD,
    }

    /**
     * Per-window coordinator attached to ControlCenterWindowViewImpl.
     * Pre-caches all hot-path reflection handles and lazily creates secondary pages only when visited.
     */
    private class MultiPageSession(
        private val windowView: ViewGroup,
        private val mainPanelController: Any,
        private val mainHandler: Handler,
        private val module: XposedModule,
        private val onBindMediaDetailContent: ((View, View) -> Unit)?,
    ) {
        private val context: Context = windowView.context
        private val pluginLoader: ClassLoader = mainPanelController.javaClass.classLoader ?: context.classLoader
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(16f)

        // Pre-cached object references (resolved ONCE)
        private val mainPanelContainer: ViewGroup? = field(mainPanelController, "mainPanelContainer") as? ViewGroup
        private val rightMainPanel: View? = field(mainPanelController, "rightMainPanel") as? View
        private val leftMainPanel: View? = field(mainPanelController, "leftMainPanel") as? View
        private val qsController: Any? = field(mainPanelController, "qsController")
        private val rightAdapter: Any? = field(mainPanelController, "distributor")?.let {
            runCatching { call(it, "getRightAdapter") }.getOrNull()
        }
        private val expandController: Any? = field(mainPanelController, "expandController")
        private val headerController: Any? = field(mainPanelController, "headerController2")
        private val modeController: Any? = field(mainPanelController, "modeController")
        private val touchController: Any? = field(mainPanelController, "touchController")
        private val secondaryPanelRouter: Any? = field(mainPanelController, "secondaryPanelRouter")?.let {
            runCatching { call(it, "get") }.getOrNull()
        }

        // Pre-cached hot-path reflection handles (ZERO method scanning during touch/expand!)
        private val getVisibleMethod: Method? = expandController?.let { method(it.javaClass, "getVisible", 0) }
        private val getAppearanceMethod: Method? = expandController?.let { method(it.javaClass, "getAppearance", 0) }
        private val getExpansionMethod: Method? = expandController?.let { method(it.javaClass, "getExpansion", 0) }
        private val getInMirrorMethod: Method? = expandController?.let { method(it.javaClass, "getInMirror", 0) }
        private val getInEditModeMethod: Method? = modeController?.let { method(it.javaClass, "getInEditMode", 0) }
        private val getInMainPanelMethod: Method? = secondaryPanelRouter?.let { method(it.javaClass, "getInMainPanel", 0) }
        private val blockTouchField: Field? = findField(windowView.javaClass, "blockTouch")
        private val getAnySeekBarTrackingMethod: Method? = method(mainPanelController.javaClass, "getAnySeekBarTracking", 0)
        private val getPanelContainerWidthMethod: Method? = method(mainPanelController.javaClass, "getPanelContainerWidth", 0)
        private val getPanelWidthMethod: Method? = method(mainPanelController.javaClass, "getPanelWidth", 0)
        private val exitMethod: Method? = method(mainPanelController.javaClass, "exit", 0)
        private val collapseSpaceHeightField: Field? = touchController?.let { findField(it.javaClass, "collapseSpaceHeight") }
        private val headerLayoutParamsField: Field? = headerController?.let { findField(it.javaClass, "layoutParams") }

        private var activeSettings: ControlCenterMultiPageSettings = ControlCenterMultiPageSettings()
        private var pages: List<CcMultiPageId> = listOf(CcMultiPageId.FAVORITES)
        private var currentPage: Int = 0
        private var scrollProgress: Float = 0f
        private var expansion: Float = 0f
        private var appearance: Boolean = false
        private var inEditMode: Boolean = false
        private var inMirror: Boolean = false
        private var secondaryHidden: Boolean = false
        private var panelStarted: Boolean = false
        private var settingsInitialized: Boolean = false
        private var destroyed: Boolean = false

        // Cached layout metrics (recomputed only on config/layout change, NOT per touch!)
        private var cachedHeaderHeight: Int = dp(context, 92f)
        private var cachedCollapseSpaceHeight: Int = dp(context, 48f)
        private var cachedPanelWidth: Int = 0
        private var cachedIsLandscape: Boolean = false

        private var overlayHost: FrameLayout? = null
        private var indicatorRail: PageIndicatorRailView? = null
        private val pageViews = LinkedHashMap<CcMultiPageId, SecondaryPageView>()
        private var activeListeningPage: CcMultiPageId? = null
        private var snapAnimator: ValueAnimator? = null

        // Touch mediation state
        private var downX = 0f
        private var downY = 0f
        private var allowPagerDrag = false
        private var pagerDragging = false
        private var railTouching = false
        private var sliderTouching = false
        private var sliderActiveHorizontal = false
        private var dragStartY = 0f
        private var dragStartProgress = 0f
        private var velocityTracker: VelocityTracker? = null

        fun attachAndSync(newSettings: ControlCenterMultiPageSettings) {
            if (destroyed) return
            pullLiveControllerState()
            ensureOverlayAttached()
            applySettings(newSettings)
            HookModuleLogger.log(
                module,
                Log.INFO,
                "HyperChanger",
                "Attached multi-page session (native reuse): enabled=${newSettings.enabled}, pages=${pages.size}",
            )
        }

        fun ensureSettingsSynced(newSettings: ControlCenterMultiPageSettings) {
            if (!settingsInitialized || activeSettings != newSettings) {
                applySettings(newSettings)
            }
        }

        private fun pullLiveControllerState() {
            panelStarted = (getVisibleMethod?.let { runCatching { it.invoke(expandController) as? Boolean }.getOrNull() }) ?: panelStarted
            appearance = (getAppearanceMethod?.let { runCatching { it.invoke(expandController) as? Boolean }.getOrNull() }) ?: appearance
            expansion = ((getExpansionMethod?.let { runCatching { it.invoke(expandController) as? Number }.getOrNull() }?.toFloat()) ?: expansion).coerceIn(0f, 1f)
            inMirror = (getInMirrorMethod?.let { runCatching { it.invoke(expandController) as? Boolean }.getOrNull() }) ?: inMirror
            inEditMode = (getInEditModeMethod?.let { runCatching { it.invoke(modeController) as? Boolean }.getOrNull() }) ?: inEditMode
        }

        fun applySettings(newSettings: ControlCenterMultiPageSettings) {
            if (destroyed) return
            settingsInitialized = true
            activeSettings = newSettings
            val nextPages = if (newSettings.isEffective()) newSettings.activePages() else listOf(CcMultiPageId.FAVORITES)
            val pagesChanged = nextPages != pages
            pages = nextPages

            if (!newSettings.isEffective()) {
                cancelSnap()
                currentPage = 0
                scrollProgress = 0f
                updateActivePageListening(null)
                resetMainPanelTransform()
                overlayHost?.visibility = View.GONE
                indicatorRail?.visibility = View.GONE
                return
            }

            ensureOverlayAttached()
            if (pagesChanged) {
                pruneRemovedSecondaryPages()
            }
            if (currentPage >= pages.size) {
                currentPage = 0
                scrollProgress = 0f
            }
            indicatorRail?.setPages(pages)
            onLayoutMetricsChanged()
            applyPageTransforms()
            updateIndicatorVisibility()
            scheduleIdlePrewarm()
        }

        private val prewarmToken = Any()

        /**
         * Pre-warms secondary pages one-by-one while Control Center is idle/collapsed so they are
         * already inflated (still View.GONE and non-listening, zero draw/blur cost) before the first swipe.
         */
        private fun scheduleIdlePrewarm() {
            mainHandler.removeCallbacksAndMessages(prewarmToken)
            if (destroyed || !activeSettings.isEffective()) return
            pages.forEachIndexed { index, pageId ->
                if (pageId == CcMultiPageId.FAVORITES || pageId in pageViews) return@forEachIndexed
                val delayMs = 600L + (index - 1).coerceAtLeast(0) * 180L
                mainHandler.postAtTime({
                    if (!destroyed && activeSettings.isEffective() && !pagerDragging) {
                        ensurePageMaterialized(index)
                    }
                }, prewarmToken, android.os.SystemClock.uptimeMillis() + delayMs)
            }
        }

        private fun ensureOverlayAttached() {
            if (overlayHost != null && indicatorRail != null) return
            val host = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                visibility = View.GONE
            }
            val rail = PageIndicatorRailView(context).apply {
                visibility = View.GONE
                onSelectPage = { targetIndex ->
                    if (targetIndex in pages.indices) {
                        ensurePageMaterialized(targetIndex)
                        animateToPage(targetIndex, triggerHaptic = true)
                    }
                }
                onScrubProgress = { progress, crossedBoundary ->
                    cancelSnap()
                    val clamped = progress.coerceIn(0f, (pages.size - 1).coerceAtLeast(0).toFloat())
                    scrollProgress = clamped
                    val nearest = clamped.roundToInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    currentPage = nearest
                    ensurePageMaterialized(nearest)
                    applyPageTransforms()
                    if (crossedBoundary) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                onScrubFinished = {
                    val target = scrollProgress.roundToInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    ensurePageMaterialized(target)
                    animateToPage(target, triggerHaptic = false)
                }
            }
            val insertIndex = mainPanelContainer?.let { windowView.indexOfChild(it) + 1 }
                ?.coerceIn(0, windowView.childCount)
                ?: windowView.childCount
            windowView.addView(
                host,
                insertIndex,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            windowView.addView(
                rail,
                (insertIndex + 1).coerceAtMost(windowView.childCount),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ).apply {
                    marginEnd = dp(context, 4f)
                },
            )
            overlayHost = host
            indicatorRail = rail
        }

        private fun pruneRemovedSecondaryPages() {
            val host = overlayHost ?: return
            val needed = pages.filter { it != CcMultiPageId.FAVORITES }.toSet()
            val iter = pageViews.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key !in needed) {
                    if (activeListeningPage == entry.key) activeListeningPage = null
                    entry.value.setListening(false)
                    host.removeView(entry.value)
                    entry.value.destroyPage()
                    iter.remove()
                }
            }
        }

        /** Lazily creates and attaches a secondary page (or runs during idle pre-warm). */
        private fun ensurePageMaterialized(index: Int) {
            val pageId = pages.getOrNull(index) ?: return
            if (pageId == CcMultiPageId.FAVORITES || pageId in pageViews) return
            val host = overlayHost ?: return
            val view = when (pageId) {
                CcMultiPageId.MEDIA -> NativeMediaPageView(
                    context = context,
                    pluginLoader = pluginLoader,
                    onBindMediaDetailContent = onBindMediaDetailContent,
                    onDismissControlCenter = { dismissControlCenter() },
                )
                CcMultiPageId.CONNECTIVITY -> NativeTileGridPageView(
                    context = context,
                    pluginLoader = pluginLoader,
                    qsController = qsController,
                    rightAdapter = rightAdapter,
                    mainHandler = mainHandler,
                    pageMode = NativeTileGridPageView.PageMode.CONNECTIVITY,
                    onDismissControlCenter = { dismissControlCenter() },
                )
                CcMultiPageId.TOOLS -> NativeTileGridPageView(
                    context = context,
                    pluginLoader = pluginLoader,
                    qsController = qsController,
                    rightAdapter = rightAdapter,
                    mainHandler = mainHandler,
                    pageMode = NativeTileGridPageView.PageMode.TOOLS,
                    onDismissControlCenter = { dismissControlCenter() },
                )
                CcMultiPageId.FAVORITES -> return
            }
            view.visibility = View.GONE
            view.updateLayoutBounds(cachedHeaderHeight, cachedCollapseSpaceHeight, cachedPanelWidth, cachedIsLandscape)
            pageViews[pageId] = view
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        fun onLayoutMetricsChanged() {
            if (!activeSettings.isEffective()) return
            cachedHeaderHeight = readHeaderHeight()
            cachedCollapseSpaceHeight = readCollapseSpaceHeight()
            cachedPanelWidth = readPanelWidth()
            cachedIsLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            pageViews.values.forEach { page ->
                page.updateLayoutBounds(cachedHeaderHeight, cachedCollapseSpaceHeight, cachedPanelWidth, cachedIsLandscape)
            }
            indicatorRail?.let { rail ->
                val lp = rail.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    val marginEnd = dp(context, if (cachedIsLandscape) 10f else 4f)
                    val topMargin = (cachedHeaderHeight - cachedCollapseSpaceHeight) / 3
                    if (lp.marginEnd != marginEnd || lp.topMargin != topMargin) {
                        lp.marginEnd = marginEnd
                        lp.topMargin = topMargin
                        rail.layoutParams = lp
                    }
                }
            }
            if (scrollProgress > 0.002f) {
                applyPageTransforms()
            }
        }

        fun onPanelStart() {
            pullLiveControllerState()
            panelStarted = true
            if (!activeSettings.isEffective()) return
            secondaryHidden = false
            // Refresh cheap cached bounds once per open; DO NOT inflate secondary pages while on Page 0!
            cachedHeaderHeight = readHeaderHeight()
            cachedCollapseSpaceHeight = readCollapseSpaceHeight()
            cachedPanelWidth = readPanelWidth()
            cachedIsLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (currentPage > 0) {
                ensurePageMaterialized(currentPage)
                pageViews[pages.getOrNull(currentPage)]?.updateLayoutBounds(
                    cachedHeaderHeight,
                    cachedCollapseSpaceHeight,
                    cachedPanelWidth,
                    cachedIsLandscape,
                )
                applyPageTransforms()
                updateActivePageListening(pages.getOrNull(currentPage))
            }
            updateIndicatorVisibility()
        }

        fun onPanelStop() {
            panelStarted = false
            appearance = false
            expansion = 0f
            resetTouchFlags()
            cancelSnap()
            updateActivePageListening(null)
            if (activeSettings.resetOnClose || !activeSettings.isEffective()) {
                currentPage = 0
                scrollProgress = 0f
            }
            resetMainPanelTransform()
            overlayHost?.visibility = View.GONE
            indicatorRail?.visibility = View.GONE
        }

        fun onAppearanceChanged(visible: Boolean) {
            appearance = visible
            if (visible) {
                panelStarted = true
                secondaryHidden = false
            }
            if (!activeSettings.isEffective()) return
            updateIndicatorVisibility()
        }

        fun onExpansionChanged(value: Float) {
            expansion = value.coerceIn(0f, 1f)
            if (expansion > 0.05f) panelStarted = true
            if (!activeSettings.isEffective()) return
            updateIndicatorVisibility()
            if (currentPage > 0 || scrollProgress > 0.001f) {
                val host = overlayHost ?: return
                val alpha = if (secondaryHidden) 0f else effectiveExpandAlpha()
                host.alpha = alpha
                val scale = 0.94f + 0.06f * alpha
                host.scaleX = scale
                host.scaleY = scale
            }
        }

        private fun effectiveExpandAlpha(): Float {
            if (expansion > 0.01f) return expansion
            return if (appearance || panelStarted) 1f else 0f
        }

        fun onMirrorChanged(mirror: Boolean) {
            inMirror = mirror
            updateIndicatorVisibility()
        }

        fun onEditModeChanged(edit: Boolean) {
            inEditMode = edit
            if (edit && (currentPage != 0 || scrollProgress != 0f)) {
                cancelSnap()
                currentPage = 0
                scrollProgress = 0f
                updateActivePageListening(null)
                resetMainPanelTransform()
                overlayHost?.visibility = View.GONE
            }
            updateIndicatorVisibility()
        }

        fun onSecondaryPanelOverlayState(hidden: Boolean, animated: Boolean) {
            secondaryHidden = hidden
            if (!activeSettings.isEffective()) return
            if (currentPage > 0 || scrollProgress > 0.001f) {
                val targetAlpha = if (hidden) 0f else 1f
                overlayHost?.let { host ->
                    host.animate().cancel()
                    if (animated) {
                        host.animate().alpha(targetAlpha).setDuration(if (hidden) 120L else 200L).start()
                    } else {
                        host.alpha = targetAlpha
                    }
                }
                if (!hidden) {
                    applyPageTransforms()
                }
            }
            updateIndicatorVisibility()
        }

        fun handleKeyEvent(event: KeyEvent): Boolean {
            if (!activeSettings.isEffective() || inEditMode || secondaryHidden) return false
            if (event.action == KeyEvent.ACTION_UP &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) &&
                !event.isCanceled &&
                currentPage > 0
            ) {
                animateToPage(0, triggerHaptic = false)
                return true
            }
            return false
        }

        fun isOwningTouchGesture(): Boolean = pagerDragging || railTouching || sliderActiveHorizontal

        fun onInterceptTouchEvent(event: MotionEvent): InterceptDecision {
            if (!canHandleMultiPageTouch()) {
                if (pagerDragging || railTouching || sliderActiveHorizontal) resetTouchFlags()
                return InterceptDecision.DEFER_TO_STOCK
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTouchFlags()
                    downX = event.rawX
                    downY = event.rawY
                    if (isPointInIndicatorRail(event.rawX, event.rawY)) {
                        railTouching = true
                        return InterceptDecision.BYPASS_PASS_TO_CHILD
                    }
                    if (currentPage > 0) {
                        val activeOverlay = activeSecondaryPageView()
                        if (activeOverlay != null && activeOverlay.isHorizontalSliderTouch(event.rawX, event.rawY)) {
                            sliderTouching = true
                        }
                    }
                    val screenH = windowView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
                    allowPagerDrag = event.y > cachedHeaderHeight && event.rawY < (screenH - cachedCollapseSpaceHeight)
                    return InterceptDecision.DEFER_TO_STOCK
                }

                MotionEvent.ACTION_MOVE -> {
                    if (railTouching) return InterceptDecision.BYPASS_PASS_TO_CHILD
                    if (pagerDragging) return InterceptDecision.INTERCEPT
                    if (sliderActiveHorizontal) return InterceptDecision.BYPASS_PASS_TO_CHILD
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (sliderTouching && abs(dx) > touchSlop * 0.55f && abs(dx) >= abs(dy)) {
                        sliderActiveHorizontal = true
                        return InterceptDecision.BYPASS_PASS_TO_CHILD
                    }
                    if (!allowPagerDrag) return InterceptDecision.DEFER_TO_STOCK
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) {
                        if (currentPage == 0 && isMainSeekBarTracking()) return InterceptDecision.DEFER_TO_STOCK
                        if (shouldInterceptVerticalDrag(dy)) {
                            cancelSnap()
                            val nextIndex = if (dy < 0f) (currentPage + 1).coerceAtMost(pages.size - 1) else (currentPage - 1).coerceAtLeast(0)
                            ensurePageMaterialized(nextIndex)
                            pagerDragging = true
                            dragStartY = event.rawY
                            dragStartProgress = scrollProgress
                            recycleVelocityTracker()
                            velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                            return InterceptDecision.INTERCEPT
                        }
                    }
                    return InterceptDecision.DEFER_TO_STOCK
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (railTouching) {
                        railTouching = false
                        return InterceptDecision.BYPASS_PASS_TO_CHILD
                    }
                    if (sliderActiveHorizontal) {
                        sliderActiveHorizontal = false
                        sliderTouching = false
                        return InterceptDecision.BYPASS_PASS_TO_CHILD
                    }
                    val wasDragging = pagerDragging
                    sliderTouching = false
                    return if (wasDragging) InterceptDecision.INTERCEPT else InterceptDecision.DEFER_TO_STOCK
                }
            }
            return InterceptDecision.DEFER_TO_STOCK
        }

        fun onTouchEvent(event: MotionEvent): Boolean {
            if (railTouching) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    railTouching = false
                }
                return false
            }
            if (!pagerDragging) return false
            velocityTracker?.addMovement(event)
            val viewportH = viewportHeight().toFloat().coerceAtLeast(300f)
            val maxPage = (pages.size - 1).coerceAtLeast(0).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - dragStartY
                    val rawProgress = dragStartProgress - (deltaY / viewportH)
                    scrollProgress = when {
                        rawProgress < 0f -> rawProgress * 0.28f
                        rawProgress > maxPage -> maxPage + (rawProgress - maxPage) * 0.28f
                        else -> rawProgress
                    }
                    applyPageTransforms()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tracker = velocityTracker
                    var yVelocity = 0f
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000)
                        yVelocity = tracker.yVelocity
                    }
                    recycleVelocityTracker()
                    pagerDragging = false

                    val flingThreshold = dp(context, 420f).toFloat()
                    val base = dragStartProgress.roundToInt().coerceIn(0, pages.size - 1)
                    val deltaProgress = scrollProgress - base
                    val target = when {
                        event.actionMasked == MotionEvent.ACTION_CANCEL -> base
                        yVelocity < -flingThreshold -> (base + 1).coerceAtMost(pages.size - 1)
                        yVelocity > flingThreshold -> (base - 1).coerceAtLeast(0)
                        deltaProgress > 0.20f -> (base + 1).coerceAtMost(pages.size - 1)
                        deltaProgress < -0.20f -> (base - 1).coerceAtLeast(0)
                        else -> base
                    }
                    ensurePageMaterialized(target)
                    animateToPage(target, triggerHaptic = target != base)
                    return true
                }
            }
            return true
        }

        private fun shouldInterceptVerticalDrag(dy: Float): Boolean {
            val lastIndex = pages.size - 1
            if (lastIndex <= 0) return false
            if (dy < 0f) {
                if (currentPage == 0) {
                    return !canMainPanelScrollDown()
                }
                val overlay = activeSecondaryPageView()
                if (overlay != null && overlay.canInnerScrollDown()) return false
                return if (currentPage < lastIndex) true else !activeSettings.lastPageSwipeUpDismiss
            } else {
                if (currentPage <= 0) return false
                val overlay = activeSecondaryPageView()
                return overlay == null || !overlay.canInnerScrollUp()
            }
        }

        private fun canHandleMultiPageTouch(): Boolean {
            if (destroyed || !activeSettings.isEffective() || inEditMode || inMirror || secondaryHidden) {
                return false
            }
            val inMain = getInMainPanelMethod?.let {
                runCatching { it.invoke(secondaryPanelRouter) as? Boolean }.getOrNull()
            } ?: true
            if (!inMain) return false
            val app = getAppearanceMethod?.let {
                runCatching { it.invoke(expandController) as? Boolean }.getOrNull()
            } ?: appearance
            if (!app) return false
            val blocked = blockTouchField?.let {
                runCatching { it.getBoolean(windowView) }.getOrNull()
            } ?: false
            return !blocked
        }

        private fun isMainSeekBarTracking(): Boolean =
            getAnySeekBarTrackingMethod?.let {
                runCatching { it.invoke(mainPanelController) as? Boolean }.getOrNull()
            } ?: false

        private fun canMainPanelScrollDown(): Boolean {
            val rightCan = rightMainPanel?.isAttachedToWindow == true && rightMainPanel.canScrollVertically(1)
            val leftCan = leftMainPanel?.isAttachedToWindow == true &&
                leftMainPanel.visibility == View.VISIBLE &&
                leftMainPanel.canScrollVertically(1)
            return rightCan || leftCan
        }

        private fun activeSecondaryPageView(): SecondaryPageView? {
            val id = pages.getOrNull(currentPage) ?: return null
            return if (id == CcMultiPageId.FAVORITES) null else pageViews[id]
        }

        private fun isPointInIndicatorRail(rawX: Float, rawY: Float): Boolean {
            val rail = indicatorRail ?: return false
            if (rail.visibility != View.VISIBLE || rail.alpha < 0.1f) return false
            val loc = IntArray(2)
            rail.getLocationOnScreen(loc)
            val slop = dp(context, 14f)
            return rawX >= loc[0] - slop &&
                rawX <= loc[0] + rail.width + slop &&
                rawY >= loc[1] - slop &&
                rawY <= loc[1] + rail.height + slop
        }

        private fun animateToPage(targetIndex: Int, triggerHaptic: Boolean) {
            val clamped = targetIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            currentPage = clamped
            cancelSnap()
            val start = scrollProgress
            val end = clamped.toFloat()
            if (triggerHaptic) {
                windowView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (abs(end - start) < 0.002f) {
                scrollProgress = end
                applyPageTransforms()
                updateActivePageListening(pages.getOrNull(clamped))
                return
            }
            val duration = (220L + (abs(end - start) * 100f).roundToInt()).coerceIn(220L, 400L)
            snapAnimator = ValueAnimator.ofFloat(start, end).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(2.1f)
                addUpdateListener { anim ->
                    scrollProgress = anim.animatedValue as Float
                    applyPageTransforms()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        snapAnimator = null
                        scrollProgress = end
                        applyPageTransforms()
                        updateActivePageListening(pages.getOrNull(clamped))
                    }
                })
                start()
            }
        }

        private fun cancelSnap() {
            snapAnimator?.cancel()
            snapAnimator = null
        }

        private fun updateActivePageListening(target: CcMultiPageId?) {
            val desired = if (panelStarted && target != null && target != CcMultiPageId.FAVORITES) target else null
            if (activeListeningPage == desired) return
            activeListeningPage?.let { prev -> pageViews[prev]?.setListening(false) }
            activeListeningPage = desired
            desired?.let { next -> pageViews[next]?.setListening(true) }
        }

        private fun applyPageTransforms() {
            if (!activeSettings.isEffective()) return
            val viewportH = viewportHeight().toFloat().coerceAtLeast(400f)
            applyMainContainerTransform(scrollProgress, viewportH)

            val host = overlayHost
            val anySecondaryVisible = scrollProgress > 0.002f && !inEditMode
            if (host != null) {
                val targetVis = if (anySecondaryVisible) View.VISIBLE else View.GONE
                if (host.visibility != targetVis) host.visibility = targetVis
                if (anySecondaryVisible && !secondaryHidden) {
                    host.alpha = effectiveExpandAlpha()
                    host.scaleX = 1f
                    host.scaleY = 1f
                }
            }

            pages.forEachIndexed { index, pageId ->
                if (pageId == CcMultiPageId.FAVORITES) return@forEachIndexed
                val view = pageViews[pageId] ?: return@forEachIndexed
                val delta = index.toFloat() - scrollProgress
                val absDelta = abs(delta)
                if (absDelta >= 0.995f || inEditMode) {
                    if (view.visibility != View.GONE) view.visibility = View.GONE
                    view.alpha = 0f
                } else {
                    if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
                    // Pure translationY + alpha avoids Bionics glass SDF refraction recompute from scaleX/scaleY.
                    view.translationY = delta * viewportH
                    view.alpha = (1f - absDelta * 1.10f).coerceIn(0f, 1f)
                }
            }
            indicatorRail?.setScrollProgress(scrollProgress)
        }

        private fun applyMainContainerTransform(progress: Float, viewportH: Float) {
            val container = mainPanelContainer ?: return
            if (progress <= 0.002f) {
                container.translationY = -progress * viewportH
                if (!secondaryHidden) {
                    container.alpha = 1f
                    if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
                }
                return
            }
            val absP = abs(progress)
            container.translationY = -progress * viewportH
            val alpha = (1f - absP * 1.10f).coerceIn(0f, 1f)
            container.alpha = alpha
            val targetVis = if (alpha > 0.01f) View.VISIBLE else View.INVISIBLE
            if (container.visibility != targetVis) container.visibility = targetVis
        }

        private fun resetMainPanelTransform() {
            mainPanelContainer?.let { container ->
                container.translationY = 0f
                container.scaleX = 1f
                container.scaleY = 1f
                container.alpha = 1f
                if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
            }
        }

        private fun updateIndicatorVisibility() {
            val rail = indicatorRail ?: return
            val expandAlpha = effectiveExpandAlpha()
            val shouldShow = activeSettings.isEffective() &&
                activeSettings.showIndicator &&
                (panelStarted || appearance) &&
                !inEditMode &&
                !inMirror &&
                !secondaryHidden &&
                expandAlpha > 0.05f
            if (!shouldShow) {
                if (rail.visibility != View.GONE) rail.visibility = View.GONE
                rail.alpha = 0f
                return
            }
            if (rail.visibility != View.VISIBLE) rail.visibility = View.VISIBLE
            val fade = ((expandAlpha - 0.15f) / 0.85f).coerceIn(0f, 1f)
            rail.alpha = fade
            rail.translationX = (1f - fade) * dp(context, 16f)
        }

        private fun dismissControlCenter() {
            exitMethod?.let { runCatching { it.invoke(mainPanelController) } }
        }

        private fun readHeaderHeight(): Int {
            val lp = headerLayoutParamsField?.let {
                runCatching { it.get(headerController) as? ViewGroup.LayoutParams }.getOrNull()
            }
            val h = lp?.height ?: 0
            return if (h > 0) h else dp(context, 92f)
        }

        private fun readCollapseSpaceHeight(): Int {
            val value = collapseSpaceHeightField?.let {
                runCatching { it.getInt(touchController) }.getOrNull()
            } ?: 0
            return value.coerceAtLeast(dp(context, 44f))
        }

        private fun readPanelWidth(): Int {
            val w = (getPanelContainerWidthMethod?.let { runCatching { it.invoke(mainPanelController) as? Int }.getOrNull() })
                ?: (getPanelWidthMethod?.let { runCatching { it.invoke(mainPanelController) as? Int }.getOrNull() })
                ?: 0
            if (w > 0) return w
            return (context.resources.displayMetrics.widthPixels - dp(context, 36f)).coerceAtLeast(dp(context, 280f))
        }

        private fun viewportHeight(): Int {
            val h = windowView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            return (h - cachedHeaderHeight).coerceAtLeast(dp(context, 360f))
        }

        private fun resetTouchFlags() {
            allowPagerDrag = false
            pagerDragging = false
            railTouching = false
            sliderTouching = false
            sliderActiveHorizontal = false
            recycleVelocityTracker()
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        fun destroy() {
            destroyed = true
            mainHandler.removeCallbacksAndMessages(prewarmToken)
            cancelSnap()
            resetTouchFlags()
            updateActivePageListening(null)
            pageViews.values.forEach { it.destroyPage() }
            pageViews.clear()
            resetMainPanelTransform()
            overlayHost?.let { windowView.removeView(it) }
            indicatorRail?.let { windowView.removeView(it) }
            overlayHost = null
            indicatorRail = null
        }
    }

    /** Base class for lazily materialized secondary pages. */
    private abstract class SecondaryPageView(context: Context) : FrameLayout(context) {
        abstract fun updateLayoutBounds(topInset: Int, bottomInset: Int, panelWidth: Int, isLandscape: Boolean)
        abstract fun setListening(listening: Boolean)
        abstract fun destroyPage()
        open fun canInnerScrollUp(): Boolean = false
        open fun canInnerScrollDown(): Boolean = false
        open fun isHorizontalSliderTouch(rawX: Float, rawY: Float): Boolean = false
    }

    /**
     * Right-edge floating vertical icon indicator rail supporting tap-to-jump and continuous scrub.
     */
    private class PageIndicatorRailView(context: Context) : View(context) {
        var onSelectPage: ((Int) -> Unit)? = null
        var onScrubProgress: ((Float, Boolean) -> Unit)? = null
        var onScrubFinished: (() -> Unit)? = null

        private var pages: List<CcMultiPageId> = listOf(CcMultiPageId.FAVORITES)
        private var scrollProgress: Float = 0f
        private var scrubbing = false
        private var downY = 0f
        private var lastBoundaryPage = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private val railWidthPx = dp(context, 28f)
        private val itemStepPx = dp(context, 30f)
        private val verticalPadPx = dp(context, 10f)
        private val iconSizePx = dp(context, 14f).toFloat()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 20, 22, 28)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1f).toFloat()
        }
        private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val strokeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rectF = RectF()

        fun setPages(newPages: List<CcMultiPageId>) {
            if (pages == newPages) return
            pages = newPages
            requestLayout()
            invalidate()
        }

        fun setScrollProgress(progress: Float) {
            if (abs(scrollProgress - progress) < 0.002f) return
            scrollProgress = progress
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = verticalPadPx * 2 + (pages.size.coerceAtLeast(1) * itemStepPx)
            setMeasuredDimension(railWidthPx, h)
        }

        override fun onDraw(canvas: Canvas) {
            if (pages.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = w / 2f

            rectF.set(1f, 1f, w - 1f, h - 1f)
            canvas.drawRoundRect(rectF, radius, radius, bgPaint)
            canvas.drawRoundRect(rectF, radius, radius, borderPaint)

            val clampedProgress = scrollProgress.coerceIn(0f, (pages.size - 1).coerceAtLeast(0).toFloat())
            val pillCenterY = verticalPadPx + itemStepPx * (clampedProgress + 0.5f)
            canvas.drawCircle(w / 2f, pillCenterY, dp(context, 10.5f).toFloat(), activePillPaint)

            pages.forEachIndexed { index, pageId ->
                val cx = w / 2f
                val cy = verticalPadPx + itemStepPx * (index + 0.5f)
                val dist = abs(clampedProgress - index).coerceIn(0f, 1f)
                val focus = 1f - dist
                val alpha = (115 + (140 * focus).roundToInt()).coerceIn(0, 255)
                val scale = 0.92f + 0.16f * focus

                canvas.save()
                canvas.translate(cx, cy)
                canvas.scale(scale, scale)
                iconPaint.alpha = alpha
                strokeIconPaint.alpha = alpha
                drawPageGlyph(canvas, pageId)
                canvas.restore()
            }
        }

        private fun drawPageGlyph(canvas: Canvas, pageId: CcMultiPageId) {
            val r = iconSizePx * 0.5f
            when (pageId) {
                CcMultiPageId.FAVORITES -> {
                    val off = r * 0.48f
                    val dotR = r * 0.28f
                    canvas.drawCircle(-off, -off, dotR, iconPaint)
                    canvas.drawCircle(off, -off, dotR, iconPaint)
                    canvas.drawCircle(-off, off, dotR, iconPaint)
                    canvas.drawCircle(off, off, dotR, iconPaint)
                }

                CcMultiPageId.MEDIA -> {
                    strokeIconPaint.strokeWidth = dp(context, 1.8f).toFloat()
                    val xs = floatArrayOf(-r * 0.68f, -r * 0.23f, r * 0.23f, r * 0.68f)
                    val hs = floatArrayOf(r * 0.36f, r * 0.78f, r * 0.56f, r * 0.32f)
                    for (i in xs.indices) {
                        canvas.drawLine(xs[i], -hs[i], xs[i], hs[i], strokeIconPaint)
                    }
                }

                CcMultiPageId.CONNECTIVITY -> {
                    strokeIconPaint.strokeWidth = dp(context, 1.5f).toFloat()
                    canvas.drawCircle(0f, r * 0.12f, r * 0.19f, iconPaint)
                    canvas.drawLine(0f, r * 0.25f, 0f, r * 0.82f, strokeIconPaint)
                    val inner = r * 0.50f
                    rectF.set(-inner, -inner + r * 0.12f, inner, inner + r * 0.12f)
                    canvas.drawArc(rectF, -145f, 110f, false, strokeIconPaint)
                    val outer = r * 0.86f
                    rectF.set(-outer, -outer + r * 0.12f, outer, outer + r * 0.12f)
                    canvas.drawArc(rectF, -145f, 110f, false, strokeIconPaint)
                }

                CcMultiPageId.TOOLS -> {
                    strokeIconPaint.strokeWidth = dp(context, 1.5f).toFloat()
                    val y1 = -r * 0.36f
                    val y2 = r * 0.36f
                    canvas.drawLine(-r * 0.75f, y1, r * 0.75f, y1, strokeIconPaint)
                    canvas.drawCircle(r * 0.25f, y1, r * 0.24f, iconPaint)
                    canvas.drawLine(-r * 0.75f, y2, r * 0.75f, y2, strokeIconPaint)
                    canvas.drawCircle(-r * 0.25f, y2, r * 0.24f, iconPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (pages.size <= 1) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    scrubbing = false
                    lastBoundaryPage = scrollProgress.roundToInt().coerceIn(0, pages.size - 1)
                    scaleX = 1.10f
                    scaleY = 1.05f
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!scrubbing && abs(event.y - downY) > touchSlop * 0.35f) {
                        scrubbing = true
                    }
                    if (scrubbing) {
                        val p = yToProgress(event.y)
                        val rounded = p.roundToInt().coerceIn(0, pages.size - 1)
                        val crossed = rounded != lastBoundaryPage
                        if (crossed) lastBoundaryPage = rounded
                        onScrubProgress?.invoke(p, crossed)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scaleX = 1f
                    scaleY = 1f
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (scrubbing) {
                            onScrubFinished?.invoke()
                        } else {
                            val index = yToProgress(event.y).roundToInt().coerceIn(0, pages.size - 1)
                            onSelectPage?.invoke(index)
                        }
                    } else if (scrubbing) {
                        onScrubFinished?.invoke()
                    }
                    scrubbing = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun yToProgress(y: Float): Float {
            val firstCenter = verticalPadPx + itemStepPx * 0.5f
            val span = (itemStepPx * (pages.size - 1).coerceAtLeast(1)).toFloat()
            val normalized = ((y - firstCenter) / span).coerceIn(0f, 1f)
            return normalized * (pages.size - 1)
        }
    }

    /**
     * Native Media Page reusing HyperOS MiPlay's QSControlMiPlayDetailContent directly
     * (real album art, seekbar, transport buttons, volume bar, and device output picker).
     */
    private class NativeMediaPageView(
        context: Context,
        private val pluginLoader: ClassLoader,
        private val onBindMediaDetailContent: ((View, View) -> Unit)?,
        private val onDismissControlCenter: () -> Unit,
    ) : SecondaryPageView(context) {
        private val contentRoot = FrameLayout(context)
        private val cardFrame = FrameLayout(context)
        private var miPlayView: View? = null
        private var setDetailShowingMethod: Method? = null
        private var seekBarView: View? = null
        private var volumeBarView: View? = null
        private var listView: View? = null
        private var builtLandscape: Boolean? = null
        private var listening = false

        init {
            clipChildren = false
            clipToPadding = false
            setOnClickListener { onDismissControlCenter() }

            contentRoot.apply {
                clipChildren = false
                clipToPadding = false
            }
            addView(
                contentRoot,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )

            cardFrame.apply {
                isClickable = true
                clipToOutline = true
                val radius = dp(context, 34f).toFloat()
                outlineProvider = RoundRectOutline(radius)
                applyNativeSecondaryPanelBackground(this, pluginLoader, radius)
            }
            contentRoot.addView(
                cardFrame,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }

        private fun ensureNativeMiPlayView(isLandscape: Boolean) {
            if (miPlayView != null && builtLandscape == isLandscape) return
            val wasListening = listening
            if (wasListening) applyDetailShowing(false)
            cardFrame.removeAllViews()
            miPlayView = null
            setDetailShowingMethod = null
            seekBarView = null
            volumeBarView = null
            listView = null
            builtLandscape = isLandscape

            val created = runCatching {
                val ctrlCls = pluginLoader.loadClass("com.android.systemui.MiPlayController")
                val instance = ctrlCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                val methodName = if (isLandscape) "createMiPlayDetailViewSupportLand" else "createMiPlayDetailView"
                val m = method(ctrlCls, methodName, 0) ?: method(ctrlCls, "createMiPlayDetailView", 0)
                m?.invoke(instance) as? View
            }.getOrNull() ?: return

            miPlayView = created
            setDetailShowingMethod = method(created.javaClass, "setDetailShowing", 3)
            cardFrame.addView(
                created,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            seekBarView = findPluginView(created, "seek_bar") ?: findPluginView(created, "container_progress")
            volumeBarView = findPluginView(created, "volume_bar") ?: findPluginView(created, "volume_bar_container")
            listView = findPluginView(created, "list")
            onBindMediaDetailContent?.invoke(created, cardFrame)
            if (wasListening) applyDetailShowing(true)
        }

        override fun updateLayoutBounds(topInset: Int, bottomInset: Int, panelWidth: Int, isLandscape: Boolean) {
            contentRoot.setPadding(dp(context, 16f), topInset + dp(context, 6f), dp(context, 36f), bottomInset + dp(context, 12f))
            val lp = cardFrame.layoutParams as LayoutParams
            val targetW = if (isLandscape) {
                min(panelWidth, dp(context, 680f))
            } else {
                min(panelWidth, context.resources.displayMetrics.widthPixels - dp(context, 52f))
            }.coerceAtLeast(dp(context, 280f))
            if (lp.width != targetW) {
                lp.width = targetW
                cardFrame.layoutParams = lp
            }
            ensureNativeMiPlayView(isLandscape)
            miPlayView?.let { onBindMediaDetailContent?.invoke(it, cardFrame) }
        }

        override fun isHorizontalSliderTouch(rawX: Float, rawY: Float): Boolean =
            (seekBarView != null && isPointInsideView(seekBarView!!, rawX, rawY, dp(context, 10f))) ||
                (volumeBarView != null && isPointInsideView(volumeBarView!!, rawX, rawY, dp(context, 10f)))

        override fun canInnerScrollUp(): Boolean = listView?.canScrollVertically(-1) == true
        override fun canInnerScrollDown(): Boolean = listView?.canScrollVertically(1) == true

        override fun setListening(listening: Boolean) {
            if (this.listening == listening) return
            this.listening = listening
            if (listening) {
                miPlayView?.let { onBindMediaDetailContent?.invoke(it, cardFrame) }
            }
            applyDetailShowing(listening)
        }

        private fun applyDetailShowing(showing: Boolean) {
            val view = miPlayView ?: return
            val m = setDetailShowingMethod ?: return
            runCatching { m.invoke(view, showing, "controlcenter", false) }
        }

        override fun destroyPage() {
            setListening(false)
            cardFrame.removeAllViews()
            miPlayView = null
            seekBarView = null
            volumeBarView = null
            listView = null
        }
    }

    /**
     * Native Tile Grid Page reusing HyperOS's own QSCardItemView (for Connectivity) and
     * QSTileItemView (for Quick Tools) via QSCardsController / QSListController.createViewHolder().
     */
    private class NativeTileGridPageView(
        context: Context,
        private val pluginLoader: ClassLoader,
        private val qsController: Any?,
        private val rightAdapter: Any?,
        private val mainHandler: Handler,
        private val pageMode: PageMode,
        private val onDismissControlCenter: () -> Unit,
    ) : SecondaryPageView(context) {
        enum class PageMode { CONNECTIVITY, TOOLS }

        private val scrollView = ScrollView(context)
        private val innerContainer = LinearLayout(context)
        private val grid = GridLayout(context)
        private val listeningToken = Any()
        private val boundTiles = mutableListOf<NativeBoundTile>()
        private val ownedCreatedTiles = mutableListOf<Any>()
        private var listening = false
        private var populated = false
        private var lastColumns = if (pageMode == PageMode.CONNECTIVITY) 2 else 4

        private val qsListController: Any? by lazy {
            qsController?.let { call(it, "getQsListController") }?.let { call(it, "get") }
        }
        private val qsCardsController: Any? by lazy {
            qsListController?.let { call(it, "getQsCardsController") }
        }
        private val hostTilesMethod: Method? by lazy {
            val host = qsController?.let { field(it, "host") }
            host?.let { method(it.javaClass, "getTiles", 0) }
        }
        private val getTileMethod: Method? by lazy {
            qsController?.let { method(it.javaClass, "getTile", 1) }
        }
        private val createTileMethod: Method? by lazy {
            qsController?.let { method(it.javaClass, "createTile", 1) }
        }

        init {
            clipChildren = false
            clipToPadding = false

            scrollView.apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
                clipChildren = false
                clipToPadding = false
            }
            addView(
                scrollView,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )

            innerContainer.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                setOnClickListener { onDismissControlCenter() }
            }
            scrollView.addView(
                innerContainer,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )

            grid.apply {
                columnCount = lastColumns
                useDefaultMargins = false
                clipChildren = false
                clipToPadding = false
            }
            innerContainer.addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        override fun updateLayoutBounds(topInset: Int, bottomInset: Int, panelWidth: Int, isLandscape: Boolean) {
            val sideLeft = dp(context, 14f)
            val sideRight = dp(context, 36f)
            scrollView.setPadding(sideLeft, topInset + dp(context, 8f), sideRight, bottomInset + dp(context, 14f))

            val columns = when (pageMode) {
                PageMode.CONNECTIVITY -> if (isLandscape) 4 else 2
                PageMode.TOOLS -> if (isLandscape) 5 else 4
            }
            val maxGridW = min(
                panelWidth,
                context.resources.displayMetrics.widthPixels - sideLeft - sideRight,
            ).coerceAtLeast(dp(context, 260f))
            val lp = grid.layoutParams as LinearLayout.LayoutParams
            if (lp.width != maxGridW) {
                lp.width = maxGridW
                grid.layoutParams = lp
            }

            if (!populated) {
                populateNativeTiles(columns)
            } else if (lastColumns != columns) {
                relayoutGrid(columns)
            }
        }

        override fun canInnerScrollUp(): Boolean = scrollView.canScrollVertically(-1)
        override fun canInnerScrollDown(): Boolean = scrollView.canScrollVertically(1)

        override fun setListening(listening: Boolean) {
            if (this.listening == listening) return
            this.listening = listening
            if (listening && !populated) {
                populateNativeTiles(lastColumns)
            }
            boundTiles.forEach { it.setListening(listening) }
        }

        override fun destroyPage() {
            setListening(false)
            boundTiles.forEach { it.detach() }
            boundTiles.clear()
            ownedCreatedTiles.forEach { tile -> runCatching { call(tile, "destroy") } }
            ownedCreatedTiles.clear()
        }

        private fun populateNativeTiles(columns: Int) {
            populated = true
            lastColumns = columns
            grid.removeAllViews()
            grid.columnCount = columns

            // Snapshot existing live QSTiles from host (instant, zero Binder IPC).
            val hostTileMap = HashMap<String, Any>()
            val host = qsController?.let { field(it, "host") }
            val hostTiles = if (host != null && hostTilesMethod != null) {
                runCatching { hostTilesMethod!!.invoke(host) as? Iterable<*> }.getOrNull()
            } else {
                null
            }
            hostTiles?.forEach { tile ->
                if (tile != null) {
                    val spec = runCatching { call(tile, "getTileSpec") as? String }.getOrNull()
                    if (!spec.isNullOrEmpty()) hostTileMap[spec] = tile
                }
            }

            val useCardViews = pageMode == PageMode.CONNECTIVITY
            val specGroups = if (useCardViews) connectivitySpecGroups() else toolsSpecGroups()
            val addedSpecs = HashSet<String>()

            specGroups.forEach { (candidates, allowCreate) ->
                val resolved = resolveTile(candidates, addedSpecs, hostTileMap, allowCreate) ?: return@forEach
                addedSpecs.add(resolved.spec)
                val bound = createNativeBoundTile(resolved.tile, useCardViews) ?: return@forEach
                boundTiles.add(bound)
                grid.addView(bound.itemView, createCellParams(useCardViews))
                if (listening) bound.setListening(true)
            }
        }

        private fun relayoutGrid(columns: Int) {
            lastColumns = columns
            val useCardViews = pageMode == PageMode.CONNECTIVITY
            grid.removeAllViews()
            grid.columnCount = columns
            boundTiles.forEach { bound ->
                grid.addView(bound.itemView, createCellParams(useCardViews))
            }
        }

        private fun createCellParams(useCardViews: Boolean): GridLayout.LayoutParams {
            val gap = dp(context, if (useCardViews) 6f else 5f)
            return GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(gap, gap, gap, gap)
            }
        }

        private fun createNativeBoundTile(tile: Any, useCardView: Boolean): NativeBoundTile? {
            val holder = if (useCardView) {
                qsCardsController?.let { ctrl ->
                    runCatching {
                        method(ctrl.javaClass, "createViewHolder", 2)?.invoke(ctrl, grid, 2273)
                    }.getOrNull()
                }
            } else {
                qsListController?.let { ctrl ->
                    runCatching {
                        method(ctrl.javaClass, "createViewHolder", 2)?.invoke(ctrl, grid, 8453)
                    }.getOrNull()
                }
            } ?: return null

            // Initialize lateinit ControlCenterViewHolder.mainPanelAdapter BEFORE layout so
            // MainPanelItemViewHolder's OnLayoutChangeListener never throws UninitializedPropertyAccessException.
            if (rightAdapter != null) {
                runCatching { findField(holder.javaClass, "mainPanelAdapter")?.set(holder, rightAdapter) }
            }

            val itemView = field(holder, "itemView") as? View ?: return null
            if (!useCardView) {
                val textMode = qsListController?.let { field(it, "textMode") }
                if (textMode != null) {
                    runCatching { call(holder, "onTextModeChanged", textMode, false) }
                }
                runCatching { call(itemView, "updateAdded", true, false) }
            }
            return NativeBoundTile(
                pluginLoader = pluginLoader,
                tile = tile,
                itemView = itemView,
                listeningToken = listeningToken,
                mainHandler = mainHandler,
            )
        }

        private data class ResolvedTile(val spec: String, val tile: Any)

        private fun resolveTile(
            candidates: List<String>,
            alreadyUsed: Set<String>,
            hostTileMap: Map<String, Any>,
            allowCreate: Boolean,
        ): ResolvedTile? {
            val controller = qsController ?: return null
            // 1. Instant reuse from existing Control Center tiles (zero IPC).
            for (spec in candidates) {
                if (spec in alreadyUsed) continue
                val existingRecord = getTileMethod?.let { runCatching { it.invoke(controller, spec) }.getOrNull() }
                val recordTile = existingRecord?.let { call(it, "getTile") }
                val tile = recordTile ?: hostTileMap[spec]
                if (tile != null) {
                    val available = (call(tile, "isAvailable") as? Boolean) ?: true
                    if (available) return ResolvedTile(spec, tile)
                }
            }
            // 2. Lazily create missing core connectivity tiles once on first visit.
            if (allowCreate && createTileMethod != null) {
                for (spec in candidates) {
                    if (spec in alreadyUsed) continue
                    val created = runCatching { createTileMethod!!.invoke(controller, spec) }.getOrNull()
                    if (created != null) {
                        val available = (call(created, "isAvailable") as? Boolean) ?: true
                        if (available) {
                            ownedCreatedTiles.add(created)
                            return ResolvedTile(spec, created)
                        } else {
                            runCatching { call(created, "destroy") }
                        }
                    }
                }
            }
            return null
        }

        private fun connectivitySpecGroups(): List<Pair<List<String>, Boolean>> = listOf(
            listOf("wifi") to true,
            listOf("cell") to true,
            listOf("bt") to true,
            listOf("airplane") to true,
            listOf("hotspot") to true,
            listOf("mishare", "misharetile", "mi_share") to false,
            listOf("nfc") to true,
            listOf("gps", "location") to false,
            listOf("vpn") to false,
            listOf("custom_5G") to false,
        )

        private fun toolsSpecGroups(): List<Pair<List<String>, Boolean>> = listOf(
            listOf("flashlight") to false,
            listOf("mute") to false,
            listOf("quietmode", "dnd") to false,
            listOf("rotation") to false,
            listOf("screenshot") to false,
            listOf("screenrecorder", "screenrecord") to false,
            listOf("batterysaver") to false,
            listOf("night", "darkmode") to false,
            listOf("papermode") to false,
            listOf("autobrightness") to false,
            listOf("calculator") to false,
            listOf("camera") to false,
        )
    }

    /**
     * Binds a live HyperOS QSTile directly to a native QSCardItemView or QSTileItemView,
     * preserving native Bionics Liquid Glass, Folme transitions, icons, labels, and haptics.
     */
    private class NativeBoundTile(
        private val pluginLoader: ClassLoader,
        private val tile: Any,
        val itemView: View,
        private val listeningToken: Any,
        private val mainHandler: Handler,
    ) {
        private val updateStateMethod: Method? = method(itemView.javaClass, "updateState", 3)
        private val getStateMethod: Method? = method(tile.javaClass, "getState", 0)
        private val isConnectedMethod: Method? = method(tile.javaClass, "isConnected", 0)
        private val setListening2: Method? = tile.javaClass.methods.firstOrNull { it.name == "setListening" && it.parameterCount == 2 }
        private val setListening1: Method? = tile.javaClass.methods.firstOrNull { it.name == "setListening" && it.parameterCount == 1 }
        private val refreshStateMethod: Method? = method(tile.javaClass, "refreshState", 0)
        private val click0: Method? = tile.javaClass.methods.firstOrNull { it.name == "click" && it.parameterCount == 0 }
        private val click1: Method? = tile.javaClass.methods.firstOrNull { it.name == "click" && it.parameterCount == 1 }
        private val long0: Method? = tile.javaClass.methods.firstOrNull { it.name == "longClick" && it.parameterCount == 0 }
        private val long1: Method? = tile.javaClass.methods.firstOrNull { it.name == "longClick" && it.parameterCount == 1 }
        private var callbackProxy: Any? = null

        init {
            attachNativeListeners()
            registerCallback()
            applyTileState(readState(), animated = false)
        }

        private fun attachNativeListeners() {
            val attached = runCatching {
                val fn1 = pluginLoader.loadClass("kotlin.jvm.functions.Function1")
                val unitObj = runCatching {
                    pluginLoader.loadClass("H0.m").getDeclaredField("a").apply { isAccessible = true }.get(null)
                }.getOrNull() ?: runCatching {
                    pluginLoader.loadClass("kotlin.Unit").getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull()
                val clickProxy = Proxy.newProxyInstance(fn1.classLoader, arrayOf(fn1)) { _, method, _ ->
                    if (method.name == "invoke") {
                        invokeClick()
                        unitObj
                    } else null
                }
                val longClickProxy = Proxy.newProxyInstance(fn1.classLoader, arrayOf(fn1)) { _, method, _ ->
                    if (method.name == "invoke") {
                        invokeLongClick()
                        java.lang.Boolean.TRUE
                    } else null
                }
                val attach = method(itemView.javaClass, "attachListeners", 4) ?: return@runCatching false
                attach.invoke(itemView, clickProxy, null, null, longClickProxy)
                true
            }.getOrDefault(false)

            if (!attached) {
                itemView.setOnClickListener { invokeClick() }
                itemView.setOnLongClickListener {
                    invokeLongClick()
                    true
                }
            }
        }

        private fun registerCallback() {
            val callbackIface = runCatching {
                pluginLoader.loadClass("com.android.systemui.plugins.qs.QSTile\$Callback")
            }.getOrNull() ?: return
            val proxy = Proxy.newProxyInstance(callbackIface.classLoader, arrayOf(callbackIface)) { self, method, args ->
                when (method.name) {
                    "onStateChanged" -> {
                        val st = args?.firstOrNull()
                        mainHandler.post { applyTileState(st, animated = true) }
                        null
                    }
                    "getCallbackType" -> 0
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.firstOrNull()
                    "toString" -> "HyperChangerNativeTileCallback"
                    else -> null
                }
            }
            callbackProxy = proxy
            runCatching {
                tile.javaClass.methods.firstOrNull { it.name == "addCallback" && it.parameterCount == 1 }
                    ?.invoke(tile, proxy)
            }
        }

        fun setListening(listening: Boolean) {
            runCatching {
                when {
                    setListening2 != null -> setListening2.invoke(tile, listeningToken, listening)
                    setListening1 != null -> setListening1.invoke(tile, listening)
                }
                if (listening) {
                    refreshStateMethod?.invoke(tile)
                    applyTileState(readState(), animated = false)
                }
            }
        }

        fun detach() {
            runCatching { call(itemView, "detachListeners") }
            val cb = callbackProxy ?: return
            callbackProxy = null
            runCatching {
                tile.javaClass.methods.firstOrNull { it.name == "removeCallback" && it.parameterCount == 1 }
                    ?.invoke(tile, cb)
            }
        }

        private fun readState(): Any? = getStateMethod?.let { runCatching { it.invoke(tile) }.getOrNull() }

        private fun applyTileState(state: Any?, animated: Boolean) {
            state ?: return
            val connected = (isConnectedMethod?.let { runCatching { it.invoke(tile) as? Boolean }.getOrNull() }) ?: false
            runCatching { updateStateMethod?.invoke(itemView, state, connected, animated) }
        }

        private fun invokeClick() {
            itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            runCatching {
                when {
                    click0 != null -> click0.invoke(tile)
                    click1 != null -> {
                        val arg = if (View::class.java.isAssignableFrom(click1.parameterTypes[0])) itemView else null
                        click1.invoke(tile, arg)
                    }
                }
            }
        }

        private fun invokeLongClick() {
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            runCatching {
                when {
                    long0 != null -> long0.invoke(tile)
                    long1 != null -> {
                        val arg = if (View::class.java.isAssignableFrom(long1.parameterTypes[0])) itemView else null
                        long1.invoke(tile, arg)
                    }
                }
            }
        }
    }

    private class RoundRectOutline(private val radiusPx: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    companion object {
        private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val MAIN_PANEL_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.MainPanelController"
        private const val WINDOW_VIEW_IMPL =
            "miui.systemui.controlcenter.windowview.ControlCenterWindowViewImpl"
        private const val MAIN_PANEL_TOUCH_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.MainPanelTouchController"
        private const val MAIN_PANEL_ANIM_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.MainPanelAnimController"
        private const val EXPAND_CONTROLLER =
            "miui.systemui.controlcenter.windowview.ControlCenterExpandController"
        private const val MODE_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.MainPanelModeController"

        private val targetClasses = setOf(
            MAIN_PANEL_CONTROLLER,
            WINDOW_VIEW_IMPL,
            MAIN_PANEL_TOUCH_CONTROLLER,
            MAIN_PANEL_ANIM_CONTROLLER,
            EXPAND_CONTROLLER,
            MODE_CONTROLLER,
        )

        private val prefKeys = setOf(
            KEY_CC_MULTI_PAGE_ENABLED,
            KEY_CC_MULTI_PAGE_MEDIA,
            KEY_CC_MULTI_PAGE_CONNECTIVITY,
            KEY_CC_MULTI_PAGE_TOOLS,
            KEY_CC_MULTI_PAGE_ORDER,
            KEY_CC_MULTI_PAGE_INDICATOR,
            KEY_CC_MULTI_PAGE_RESET_ON_CLOSE,
            KEY_CC_MULTI_PAGE_LAST_PAGE_DISMISS,
        )

        private fun dp(context: Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).roundToInt()

        private fun findPluginView(root: View, name: String): View? {
            val id = root.resources.getIdentifier(name, "id", PLUGIN_PACKAGE)
            return if (id != 0) root.findViewById(id) else null
        }

        private fun isPointInsideView(view: View, rawX: Float, rawY: Float, slopPx: Int): Boolean {
            if (view.visibility != View.VISIBLE || !view.isShown) return false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return rawX >= loc[0] - slopPx &&
                rawX <= loc[0] + view.width + slopPx &&
                rawY >= loc[1] - slopPx &&
                rawY <= loc[1] + view.height + slopPx
        }

        private fun applyNativeSecondaryPanelBackground(view: View, pluginLoader: ClassLoader, radiusPx: Float) {
            val bgResId = view.resources.getIdentifier("secondary_panel_background", "drawable", PLUGIN_PACKAGE)
            if (bgResId != 0) {
                runCatching { view.setBackgroundResource(bgResId) }
            }
            if (view.background == null) {
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(Color.argb(120, 28, 30, 38))
                }
            }
            runCatching {
                val blurCompat = pluginLoader.loadClass("miui.systemui.util.MiBlurCompat")
                blurCompat.methods.firstOrNull {
                    it.name == "setMiViewBlurModeCompat" && it.parameterCount == 2
                }?.invoke(null, view, 1)

                val styleCls = pluginLoader.loadClass("miui.systemui.util.MiBackgroundStyle")
                val isBionics = styleCls.methods.firstOrNull {
                    it.name == "isBionicsMode" && it.parameterCount == 0
                }?.invoke(null) as? Boolean ?: false
                if (isBionics) {
                    val instance = styleCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                    val token = styleCls.methods.firstOrNull {
                        it.name == "getDEFAULT_GLASS_TOKEN" && it.parameterCount == 0
                    }?.invoke(instance)
                    val setStyle = styleCls.methods.firstOrNull {
                        it.name == "setMiBackgroundStyle" && it.parameterCount == 3
                    }
                    if (token != null && setStyle != null) {
                        setStyle.invoke(null, view, null, token)
                    }
                }
            }
        }

        private fun field(target: Any?, name: String): Any? {
            target ?: return null
            val clazz = target as? Class<*> ?: target.javaClass
            val instance = if (target is Class<*>) null else target
            return findField(clazz, name)?.get(instance)
        }

        private fun findField(clazz: Class<*>, name: String): Field? =
            generateSequence(clazz) { it.superclass }
                .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull()

        private fun method(clazz: Class<*>, name: String, count: Int): Method? =
            generateSequence(clazz) { it.superclass }
                .mapNotNull { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == count } }
                .firstOrNull()
                ?.apply { isAccessible = true }

        private fun call(target: Any?, name: String, vararg args: Any?): Any? {
            target ?: return null
            val m = generateSequence(target.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { candidate ->
                    candidate.name == name && candidate.parameterCount == args.size &&
                        candidate.parameterTypes.zip(args).all { (type, arg) ->
                            arg == null && !type.isPrimitive ||
                                (arg != null && (
                                    type.isInstance(arg) ||
                                        (type == Int::class.javaPrimitiveType && arg is Int) ||
                                        (type == Float::class.javaPrimitiveType && arg is Float) ||
                                        (type == Long::class.javaPrimitiveType && arg is Long) ||
                                        (type == Boolean::class.javaPrimitiveType && arg is Boolean)
                                    ))
                        }
                } ?: return null
            return m.apply { isAccessible = true }.invoke(target, *args)
        }
    }
}
