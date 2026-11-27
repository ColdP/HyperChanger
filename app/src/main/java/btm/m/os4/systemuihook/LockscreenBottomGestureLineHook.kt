// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Owns the lockscreen custom bottom text and the iOS-style lockscreen gesture hint line
 * (hand-following rubber-band drag, velocity-preserving spring physics, adaptive solid/blur
 * material, and periodic/wake/tap unlock hint bounce animation).
 */
internal class LockscreenBottomGestureLineHook(
    private val module: HyperSystemUiModule,
    private val logTag: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var installed = false

    @Volatile
    private var preferencesRef: SharedPreferences? = null

    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var indicationControllerRef: WeakReference<Any>? = null
    private var panelViewControllerRef: WeakReference<Any>? = null
    private var bottomAreaInjectorRef: WeakReference<Any>? = null
    private var activeController: GestureLineController? = null

    @Volatile
    private var darkStyle: Boolean = false

    @Volatile
    private var dozing: Boolean = false

    @Volatile
    private var indicationVisible: Boolean = false

    @Volatile
    private var bouncerShowing: Boolean = false

    @Volatile
    private var screenInteractive: Boolean = true

    @Volatile
    private var expansionFraction: Float = 1f

    @Synchronized
    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        preferencesRef = preferences
        registerPreferenceListener(preferences)
        if (installed) return
        installed = true

        installIndicationControllerHooks(classLoader, preferences)
        installBottomAreaInjectorHooks(classLoader, preferences)
        installKeyguardPanelHooks(classLoader, preferences)
        installNotificationPanelTouchHooks(classLoader)
    }

    private fun registerPreferenceListener(preferences: SharedPreferences) {
        if (prefListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in MONITORED_KEYS) {
                mainHandler.post { refreshAllFromPreferences() }
            }
        }
        prefListener = listener
        runCatching { preferences.registerOnSharedPreferenceChangeListener(listener) }
    }

    private fun refreshAllFromPreferences() {
        val prefs = preferencesRef ?: return
        val settings = readLockscreenBottomSettings(prefs)
        activeController?.applySettings(settings, forceMaterialReapply = true)
        indicationControllerRef?.get()?.let { controller ->
            runCatching {
                controller.javaClass
                    .getMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType)
                    .invoke(controller, false)
            }
        }
    }

    private fun installIndicationControllerHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val controllerClass = classLoader.loadClass(KEYGUARD_INDICATION_CONTROLLER_CLASS)
            val indicationClass = classLoader.loadClass(KEYGUARD_INDICATION_CLASS)
            val rotateField = controllerClass.getDeclaredField("mRotateTextViewController")
                .apply { isAccessible = true }
            val initialColorField = controllerClass.getDeclaredField("mInitialTextColorState")
                .apply { isAccessible = true }
            val darkStyleField = controllerClass.getDeclaredField("mDarkStyle")
                .apply { isAccessible = true }
            val visibleField = controllerClass.getDeclaredField("mVisible")
                .apply { isAccessible = true }
            val dozingField = controllerClass.getDeclaredField("mDozing")
                .apply { isAccessible = true }

            val msgField = indicationClass.getDeclaredField("mMessage").apply { isAccessible = true }
            val colorField = indicationClass.getDeclaredField("mTextColor").apply { isAccessible = true }
            val clickField = indicationClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
            val bgField = indicationClass.getDeclaredField("mBackground").apply { isAccessible = true }
            val minVisField = indicationClass.getDeclaredField("mMinVisibilityMillis").apply { isAccessible = true }
            val liveField = indicationClass.getDeclaredField("mForceAccessibilityLiveRegionAssertive")
                .apply { isAccessible = true }
            val noArgCtor = runCatching {
                indicationClass.getDeclaredConstructor().apply { isAccessible = true }
            }.getOrNull()

            fun buildIndication(message: String, colorStateList: ColorStateList): Any? {
                val instance = noArgCtor?.newInstance() ?: return null
                msgField.set(instance, message)
                colorField.set(instance, colorStateList)
                clickField.set(instance, null)
                bgField.set(instance, null)
                minVisField.set(instance, null)
                liveField.setBoolean(instance, false)
                return instance
            }

            module.hook(controllerClass.getMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-bottom-custom-text:update")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject
                    indicationControllerRef = WeakReference(controller)
                    runCatching {
                        val isDark = darkStyleField.getBoolean(controller)
                        val isVisible = visibleField.getBoolean(controller)
                        val isDozing = dozingField.getBoolean(controller)
                        darkStyle = isDark
                        indicationVisible = isVisible
                        val wasDozing = dozing
                        dozing = isDozing
                        activeController?.onDarkStyleChanged(isDark)
                        if (wasDozing != isDozing) {
                            activeController?.onDozingChanged(isDozing)
                        }

                        val rotateController = rotateField.get(controller) ?: return@runCatching
                        val settings = readLockscreenBottomSettings(preferences)
                        if (!isVisible || isDozing) return@runCatching

                        if (settings.customText.isEmpty()) {
                            rotateController.javaClass
                                .getMethod("hideIndication", Int::class.javaPrimitiveType)
                                .invoke(rotateController, INDICATION_TYPE_OWNER_INFO)
                        } else {
                            val colorState = (initialColorField.get(controller) as? ColorStateList)
                                ?: ColorStateList.valueOf(
                                    if (isDark) 0xCC000000.toInt() else 0xCCFFFFFF.toInt(),
                                )
                            val indication = buildIndication(settings.customText, colorState)
                            if (indication != null) {
                                rotateController.javaClass.getMethod(
                                    "updateIndication",
                                    Int::class.javaPrimitiveType,
                                    indicationClass,
                                    Boolean::class.javaPrimitiveType,
                                ).invoke(rotateController, INDICATION_TYPE_OWNER_INFO, indication, false)
                            }
                        }
                    }.onFailure { error ->
                        HookModuleLogger.log(
                            module,
                            Log.WARN,
                            logTag,
                            "Could not update lockscreen custom bottom text",
                            error,
                        )
                    }
                    result
                }

            runCatching {
                module.hook(controllerClass.getMethod("setDarkStyle", Boolean::class.javaPrimitiveType))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:dark-style")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val isDark = chain.getArg(0) as? Boolean ?: false
                        darkStyle = isDark
                        activeController?.onDarkStyleChanged(isDark)
                        result
                    }
            }

            runCatching {
                module.hook(controllerClass.getMethod("setVisible", Boolean::class.javaPrimitiveType))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:indication-visible")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val visible = chain.getArg(0) as? Boolean ?: false
                        indicationVisible = visible
                        activeController?.onLockscreenVisibilityChanged()
                        result
                    }
            }

            HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen custom bottom text & dark-style hooks")
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                Log.ERROR,
                logTag,
                "Could not install lockscreen indication hooks",
                error,
            )
        }
    }

    private fun installBottomAreaInjectorHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val injectorClass = classLoader.loadClass(KEYGUARD_BOTTOM_AREA_INJECTOR_CLASS)
            val bottomAreaViewClass = classLoader.loadClass(KEYGUARD_BOTTOM_AREA_VIEW_CLASS)
            val setViewMethod = injectorClass.getMethod("setView", bottomAreaViewClass)
            val indicationTextBottomField = runCatching {
                injectorClass.getDeclaredField("mIndicationTextBottom").apply { isAccessible = true }
            }.getOrNull()

            module.hookDeoptimized(setViewMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-gesture-line:set-view")
                .intercept { chain ->
                    val result = chain.proceed()
                    val injector = chain.thisObject
                    bottomAreaInjectorRef = WeakReference(injector)
                    val bottomArea = chain.getArg(0) as? FrameLayout
                    if (bottomArea == null) {
                        activeController?.detach()
                        activeController = null
                    } else {
                        val indicationText = runCatching {
                            indicationTextBottomField?.get(injector) as? TextView
                        }.getOrNull()
                        attachController(bottomArea, indicationText, preferences)
                    }
                    result
                }

            HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen bottom-area gesture line hook")
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                Log.ERROR,
                logTag,
                "Could not install lockscreen bottom-area gesture line hook",
                error,
            )
        }
    }

    private fun attachController(
        bottomArea: FrameLayout,
        indicationText: TextView?,
        preferences: SharedPreferences,
    ) {
        val existing = activeController
        if (existing != null && existing.host === bottomArea) {
            existing.updateIndicationTextRef(indicationText)
            existing.applySettings(readLockscreenBottomSettings(preferences), forceMaterialReapply = true)
            return
        }
        existing?.detach()
        val controller = GestureLineController(
            host = bottomArea,
            indicationText = indicationText,
            isLockscreenActive = ::isLockscreenEligibleForInteraction,
            currentDarkStyle = { darkStyle },
            currentExpansionFraction = { expansionFraction },
        )
        activeController = controller
        controller.attach()
        controller.applySettings(readLockscreenBottomSettings(preferences), forceMaterialReapply = true)
    }

    private fun installKeyguardPanelHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)
            val bouncerField = runCatching {
                panelClass.getDeclaredField("keyguardBouncerShowing").apply { isAccessible = true }
            }.getOrNull()
            val interactiveField = runCatching {
                panelClass.getDeclaredField("interactive").apply { isAccessible = true }
            }.getOrNull()
            val statusBarStateField = runCatching {
                panelClass.getDeclaredField("statusBarState").apply { isAccessible = true }
            }.getOrNull()

            runCatching {
                module.hookDeoptimized(panelClass.getMethod("updateBottomAreaVisible"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:bottom-visible")
                    .intercept { chain ->
                        val result = chain.proceed()
                        panelViewControllerRef = WeakReference(chain.thisObject)
                        val ctrl = activeController
                        if (ctrl != null) {
                            ctrl.applySettings(readLockscreenBottomSettings(preferences), forceMaterialReapply = false)
                            ctrl.onLockscreenVisibilityChanged()
                        }
                        result
                    }
            }

            runCatching {
                module.hookDeoptimized(
                    panelClass.getMethod("updateKeyguardElementsExpansionInternal", Float::class.javaPrimitiveType),
                )
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:expansion")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val panel = chain.thisObject
                        panelViewControllerRef = WeakReference(panel)
                        val fraction = (chain.getArg(0) as? Float) ?: 1f
                        expansionFraction = fraction
                        val isBouncer = runCatching { bouncerField?.getBoolean(panel) == true }.getOrDefault(false)
                        val isInteractive = runCatching { interactiveField?.getBoolean(panel) != false }.getOrDefault(true)
                        val barState = runCatching { statusBarStateField?.getInt(panel) ?: 1 }.getOrDefault(1)
                        val bouncerChanged = bouncerShowing != isBouncer
                        bouncerShowing = isBouncer
                        screenInteractive = isInteractive
                        activeController?.onExpansionChanged(
                            fraction = fraction,
                            bouncer = isBouncer,
                            bouncerChanged = bouncerChanged,
                            onKeyguard = barState == 1,
                        )
                        result
                    }
            }

            runCatching {
                module.hookDeoptimized(panelClass.getMethod("onWakeupAnimationEnd"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:wakeup-end")
                    .intercept { chain ->
                        val result = chain.proceed()
                        screenInteractive = true
                        activeController?.onWakeupSettled()
                        result
                    }
            }

            runCatching {
                val wakeObserverClass = classLoader.loadClass(KEYGUARD_PANEL_WAKE_OBSERVER_CLASS)
                module.hookDeoptimized(wakeObserverClass.getMethod("onStartedWakingUp"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:wake-start")
                    .intercept { chain ->
                        val result = chain.proceed()
                        screenInteractive = true
                        activeController?.onStartedWakingUp()
                        result
                    }
                module.hookDeoptimized(wakeObserverClass.getMethod("onStartedGoingToSleep"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:sleep-start")
                    .intercept { chain ->
                        val result = chain.proceed()
                        screenInteractive = false
                        activeController?.onGoingToSleep()
                        result
                    }
                module.hookDeoptimized(wakeObserverClass.getMethod("onFinishedGoingToSleep"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-gesture-line:sleep-finish")
                    .intercept { chain ->
                        val result = chain.proceed()
                        screenInteractive = false
                        activeController?.onGoingToSleep()
                        result
                    }
            }.onFailure { error ->
                HookModuleLogger.log(
                    module,
                    Log.DEBUG,
                    logTag,
                    "Optional KeyguardPanelViewController wakeObserver hook unavailable",
                    error,
                )
            }

            HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen gesture-line panel hooks")
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                Log.WARN,
                logTag,
                "Could not install lockscreen gesture-line panel hooks",
                error,
            )
        }
    }

    private fun installNotificationPanelTouchHooks(classLoader: ClassLoader) {
        runCatching {
            val npvClass = classLoader.loadClass(NOTIFICATION_PANEL_VIEW_CLASS)
            val dispatchTouch = npvClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            module.hookDeoptimized(dispatchTouch)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-gesture-line:touch")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    if (event != null) {
                        runCatching { activeController?.onPanelTouchEvent(event) }
                    }
                    chain.proceed()
                }
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen gesture-line touch tracking hook")
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                Log.WARN,
                logTag,
                "Could not install NotificationPanelView touch hook",
                error,
            )
        }
    }

    private fun isLockscreenEligibleForInteraction(): Boolean {
        val controller = activeController ?: return false
        if (!controller.isEnabled) return false
        val host = controller.host
        if (!host.isAttachedToWindow || host.visibility != View.VISIBLE || !host.isShown) return false
        if (dozing || bouncerShowing || !screenInteractive) return false
        val panel = panelViewControllerRef?.get()
        if (panel != null) {
            val onKeyguard = runCatching {
                val statusField = panel.javaClass.getDeclaredField("statusBarState").apply { isAccessible = true }
                statusField.getInt(panel) == 1
            }.getOrDefault(true)
            if (!onKeyguard) return false
            val inEditor = runCatching {
                val editorField = panel.javaClass.getDeclaredField("keyguardEditorHelper").apply { isAccessible = true }
                val editor = editorField.get(panel) ?: return@runCatching false
                editor.javaClass.getMethod("isInEnterAndExitEditorMode").invoke(editor) as? Boolean ?: false
            }.getOrDefault(false)
            if (inEditor) return false
        }
        return true
    }

    /**
     * Manages the injected lockscreen gesture pill View, its material & layout, VSync spring physics,
     * touch hand-following, and periodic/wake/tap iOS unlock-hint bounce animations.
     */
    private class GestureLineController(
        val host: FrameLayout,
        private var indicationText: TextView?,
        private val isLockscreenActive: () -> Boolean,
        private val currentDarkStyle: () -> Boolean,
        private val currentExpansionFraction: () -> Float,
    ) : Choreographer.FrameCallback {

        private enum class TouchMode {
            NONE,
            UPWARD_DRAG,
            DOWNWARD_RUBBER,
            IGNORED,
        }

        private class SpringChannel(
            var value: Float = 0f,
            var velocity: Float = 0f,
            var target: Float = 0f,
            var stiffness: Float = 320f,
            var dampingRatio: Float = 0.75f,
            private val valueEpsilon: Float = 0.05f,
            private val velocityEpsilon: Float = 0.5f,
        ) {
            fun setTarget(newTarget: Float, newStiffness: Float, newDamping: Float) {
                target = newTarget
                stiffness = newStiffness
                dampingRatio = newDamping
            }

            fun snapTo(newValue: Float) {
                value = newValue
                target = newValue
                velocity = 0f
            }

            fun isSettled(): Boolean =
                abs(value - target) <= valueEpsilon && abs(velocity) <= velocityEpsilon

            /** Sub-stepped semi-implicit Euler integration for 60Hz-144Hz stability. */
            fun step(dtSec: Float): Boolean {
                if (isSettled()) {
                    value = target
                    velocity = 0f
                    return false
                }
                val omega = sqrt(stiffness.coerceAtLeast(1f))
                val damping = 2f * dampingRatio.coerceAtLeast(0.05f) * omega
                val subSteps = if (dtSec > 0.008f) 4 else 2
                val h = dtSec / subSteps
                var x = value
                var v = velocity
                repeat(subSteps) {
                    val accel = -stiffness * (x - target) - damping * v
                    v += accel * h
                    x += v * h
                }
                value = x
                velocity = v
                if (isSettled()) {
                    value = target
                    velocity = 0f
                    return false
                }
                return true
            }
        }

        private val handler = Handler(Looper.getMainLooper())
        private val choreographer = Choreographer.getInstance()
        private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop.toFloat()

        private val springY = SpringChannel(valueEpsilon = 0.1f, velocityEpsilon = 1.5f)
        private val springX = SpringChannel(valueEpsilon = 0.1f, velocityEpsilon = 1.5f)
        private val springScaleX = SpringChannel(
            value = 1f,
            target = 1f,
            valueEpsilon = 0.0008f,
            velocityEpsilon = 0.01f,
        )
        private val springScaleY = SpringChannel(
            value = 1f,
            target = 1f,
            valueEpsilon = 0.0008f,
            velocityEpsilon = 0.01f,
        )
        private val springAlpha = SpringChannel(
            value = 1f,
            target = 1f,
            valueEpsilon = 0.004f,
            velocityEpsilon = 0.02f,
        )

        private var frameLoopRunning = false
        private var lastFrameNanos = 0L

        private var settings = LockscreenBottomSettings()
        private var lastAppliedDarkStyle: Boolean? = null
        private var lastAppliedMaterial: Int = -1
        private var lastPillWidthPx = 0
        private var lastPillHeightPx = 0
        private var lastBottomMarginPx = 0
        private var stockIndicationBottomMargin: Int? = null
        private var stockIndicationOwner: TextView? = null

        val isEnabled: Boolean
            get() = settings.gestureLineEnabled

        private var touchMode = TouchMode.NONE
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var downX = 0f
        private var downY = 0f
        private var downTimeMs = 0L
        private var maxMoveDist = 0f
        private var nearBottomOnDown = false
        private var velocityTracker: VelocityTracker? = null
        private var isBounceRunning = false

        private val pillView: View = View(host.context).apply {
            tag = GESTURE_LINE_VIEW_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, h / 2f)
                    }
                }
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.post {
                        if (v.isAttachedToWindow && settings.gestureLineEnabled) {
                            applyMaterial(force = true)
                            scheduleNextBounceIfEligible(WAKE_BOUNCE_DELAY_MS)
                        }
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    stopAllAnimationsAndTimers()
                }
            })
        }

        private val hostLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                updateGeometry()
            }
        }

        private val bounceStartRunnable = Runnable {
            if (!canPlayBounce()) return@Runnable
            playUnlockHintBounce(isTapNudge = false)
            scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
        }

        private val tapBounceRunnable = Runnable {
            if (!canPlayBounce()) return@Runnable
            playUnlockHintBounce(isTapNudge = true)
            scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
        }

        private val bounceReturnRunnable = Runnable {
            isBounceRunning = false
            if (touchMode != TouchMode.NONE) return@Runnable
            // Classic iOS elastic spring settle back to rest with soft overshoot.
            springY.setTarget(0f, BOUNCE_RETURN_STIFFNESS, BOUNCE_RETURN_DAMPING)
            springScaleX.setTarget(1f, BOUNCE_RETURN_STIFFNESS, 0.72f)
            springScaleY.setTarget(1f, BOUNCE_RETURN_STIFFNESS, 0.72f)
            ensureFrameLoop()
        }

        fun updateIndicationTextRef(textView: TextView?) {
            if (textView != null) {
                indicationText = textView
                syncIndicationBottomMargin()
            }
        }

        fun attach() {
            host.clipChildren = false
            host.clipToPadding = false
            val existing = host.findViewWithTag<View>(GESTURE_LINE_VIEW_TAG)
            if (existing != null && existing !== pillView) {
                host.removeView(existing)
            }
            if (pillView.parent !== host) {
                (pillView.parent as? ViewGroup)?.removeView(pillView)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
                host.addView(pillView, lp)
            }
            host.removeOnLayoutChangeListener(hostLayoutListener)
            host.addOnLayoutChangeListener(hostLayoutListener)
            updateGeometry()
            applyMaterial(force = true)
        }

        fun detach() {
            stopAllAnimationsAndTimers()
            recycleVelocityTracker()
            host.removeOnLayoutChangeListener(hostLayoutListener)
            runCatching { HyperOsBackdropMaterial.clear(pillView) }
            (pillView.parent as? ViewGroup)?.removeView(pillView)
            restoreIndicationBottomMargin()
        }

        fun applySettings(newSettings: LockscreenBottomSettings, forceMaterialReapply: Boolean) {
            val previous = settings
            settings = newSettings.normalized()
            if (!settings.gestureLineEnabled) {
                stopAllAnimationsAndTimers()
                resetVisualStateImmediate()
                pillView.visibility = View.GONE
                runCatching { HyperOsBackdropMaterial.clear(pillView) }
                lastAppliedMaterial = -1
                restoreIndicationBottomMargin()
                return
            }

            pillView.visibility = View.VISIBLE
            pillView.bringToFront()
            updateGeometry()
            applyMaterial(
                force = forceMaterialReapply ||
                    previous.gestureLineMaterial != settings.gestureLineMaterial ||
                    !previous.gestureLineEnabled,
            )
            syncIndicationBottomMargin()

            if (!settings.gestureLineUnlockHintAnim) {
                cancelBounceTimers()
                if (isBounceRunning) {
                    isBounceRunning = false
                    returnToRestSpring(stiffness = RELEASE_STIFFNESS, damping = RELEASE_DAMPING)
                }
            } else if (!previous.gestureLineUnlockHintAnim || !previous.gestureLineEnabled) {
                scheduleNextBounceIfEligible(WAKE_BOUNCE_DELAY_MS)
            }
        }

        fun onDarkStyleChanged(isDark: Boolean) {
            if (!settings.gestureLineEnabled) return
            if (lastAppliedDarkStyle == isDark) return
            applyMaterial(force = true)
        }

        fun onDozingChanged(isDozing: Boolean) {
            if (isDozing) {
                stopAllAnimationsAndTimers()
                resetVisualStateImmediate()
            } else if (settings.gestureLineEnabled) {
                applyMaterial(force = false)
                scheduleNextBounceIfEligible(WAKE_BOUNCE_DELAY_MS)
            }
        }

        fun onStartedWakingUp() {
            if (!settings.gestureLineEnabled) return
            cancelBounceTimers()
            springAlpha.snapTo(1f)
            applyTransforms()
            scheduleNextBounceIfEligible(WAKE_BOUNCE_DELAY_MS)
        }

        fun onWakeupSettled() {
            if (!settings.gestureLineEnabled) return
            if (!isBounceRunning && touchMode == TouchMode.NONE) {
                scheduleNextBounceIfEligible(WAKE_SETTLED_BOUNCE_DELAY_MS)
            }
        }

        fun onGoingToSleep() {
            stopAllAnimationsAndTimers()
            recycleVelocityTracker()
            touchMode = TouchMode.NONE
            activePointerId = MotionEvent.INVALID_POINTER_ID
            resetVisualStateImmediate()
        }

        fun onLockscreenVisibilityChanged() {
            if (!settings.gestureLineEnabled) return
            if (isLockscreenActive()) {
                pillView.visibility = View.VISIBLE
                applyMaterial(force = false)
                if (touchMode == TouchMode.NONE && !isBounceRunning) {
                    springAlpha.setTarget(1f, 360f, 0.9f)
                    ensureFrameLoop()
                    scheduleNextBounceIfEligible(WAKE_BOUNCE_DELAY_MS)
                }
            } else {
                stopAllAnimationsAndTimers()
                resetVisualStateImmediate()
            }
        }

        fun onExpansionChanged(
            fraction: Float,
            bouncer: Boolean,
            bouncerChanged: Boolean,
            onKeyguard: Boolean,
        ) {
            if (!settings.gestureLineEnabled) return
            val density = host.resources.displayMetrics.density
            if (!onKeyguard || bouncer) {
                cancelBounceTimers()
                isBounceRunning = false
                if (bouncer && bouncerChanged) {
                    // Smooth upward glide & fade into bouncer.
                    springY.setTarget(-36f * density, 380f, 0.9f)
                    springAlpha.setTarget(0f, 480f, 0.95f)
                    springScaleX.setTarget(1.06f, 380f, 0.9f)
                    springScaleY.setTarget(0.92f, 380f, 0.9f)
                    ensureFrameLoop()
                } else if (!onKeyguard) {
                    stopAllAnimationsAndTimers()
                    resetVisualStateImmediate()
                }
                return
            }

            if (bouncerChanged && !bouncer) {
                // Returning from bouncer to lockscreen: restore smoothly.
                springAlpha.setTarget(1f, 320f, 0.85f)
                returnToRestSpring(RELEASE_STIFFNESS, RELEASE_DAMPING)
                scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
                return
            }

            val swipeProgress = (1f - fraction).coerceIn(0f, 1f)
            if (swipeProgress > 0.004f) {
                cancelBounceTimers()
                isBounceRunning = false
                val expansionLift = -swipeProgress * 48f * density
                val fadeAlpha = (1f - swipeProgress * 1.65f).coerceIn(0f, 1f)
                if (touchMode != TouchMode.UPWARD_DRAG) {
                    val stretch = min(swipeProgress * 1.4f, 1f)
                    springY.setTarget(expansionLift, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    springScaleX.setTarget(1f + 0.075f * stretch, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    springScaleY.setTarget(1f - 0.06f * stretch, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    springAlpha.setTarget(fadeAlpha, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    ensureFrameLoop()
                } else {
                    // While directly finger-tracking, use the stronger lift so there is no kink.
                    if (expansionLift < springY.target) {
                        springY.setTarget(expansionLift, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    }
                    springAlpha.setTarget(fadeAlpha, TRACKING_STIFFNESS, TRACKING_DAMPING)
                    ensureFrameLoop()
                }
            } else if (touchMode == TouchMode.NONE && !isBounceRunning) {
                springAlpha.setTarget(1f, 340f, 0.9f)
                if (abs(springY.target) > 0.1f || abs(springX.target) > 0.1f) {
                    returnToRestSpring(RELEASE_STIFFNESS, RELEASE_DAMPING)
                } else {
                    ensureFrameLoop()
                }
            }
        }

        fun onPanelTouchEvent(event: MotionEvent) {
            if (!settings.gestureLineEnabled) return
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                if (!isLockscreenActive()) {
                    touchMode = TouchMode.IGNORED
                    return
                }
                val screenW = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
                val screenH = host.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
                activePointerId = event.getPointerId(0)
                downX = event.getX(0)
                downY = event.getY(0)
                downTimeMs = SystemClock.uptimeMillis()
                maxMoveDist = 0f
                touchMode = TouchMode.NONE
                nearBottomOnDown = downY >= screenH * 0.58f

                recycleVelocityTracker()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }

                cancelBounceTimers()
                if (isBounceRunning && nearBottomOnDown) {
                    isBounceRunning = false
                }

                // Subtle iOS tactile press response when touching directly in the bottom gesture zone.
                val inCenterBottomZone = downY >= screenH * 0.80f &&
                    downX >= screenW * 0.18f &&
                    downX <= screenW * 0.82f
                if (inCenterBottomZone) {
                    springScaleX.setTarget(1.045f, 560f, 0.80f)
                    springScaleY.setTarget(1.08f, 560f, 0.80f)
                    ensureFrameLoop()
                }
                return
            }

            if (touchMode == TouchMode.IGNORED) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchMode = TouchMode.NONE
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    recycleVelocityTracker()
                }
                return
            }

            val pointerIndex = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
            val curX = event.getX(pointerIndex)
            val curY = event.getY(pointerIndex)
            val dx = curX - downX
            val dy = curY - downY
            val dist = hypot(dx, dy)
            if (dist > maxMoveDist) maxMoveDist = dist

            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val density = host.resources.displayMetrics.density
                    if (touchMode == TouchMode.NONE) {
                        val absDx = abs(dx)
                        val absDy = abs(dy)
                        val lockThreshold = max(3f * density, touchSlop * 0.30f)
                        if (dist >= lockThreshold) {
                            when {
                                absDx > absDy * 1.35f -> {
                                    // Horizontal swipe (camera / negative screen): stay completely calm.
                                    touchMode = TouchMode.IGNORED
                                    returnToRestSpring(RELEASE_STIFFNESS, RELEASE_DAMPING)
                                    return
                                }
                                dy < 0f && (nearBottomOnDown || currentExpansionFraction() < 0.996f) -> {
                                    touchMode = TouchMode.UPWARD_DRAG
                                    cancelBounceTimers()
                                    isBounceRunning = false
                                }
                                dy > 0f && nearBottomOnDown && absDy > absDx -> {
                                    touchMode = TouchMode.DOWNWARD_RUBBER
                                    cancelBounceTimers()
                                    isBounceRunning = false
                                }
                            }
                        }
                    }

                    when (touchMode) {
                        TouchMode.UPWARD_DRAG -> {
                            val dragUp = (-dy).coerceAtLeast(0f)
                            val maxLift = 68f * density
                            val scaled = dragUp * 0.64f
                            val effectiveLift = (scaled * maxLift) / (scaled + maxLift)
                            val maxDriftX = 8f * density
                            val driftX = (dx * 0.08f).coerceIn(-maxDriftX, maxDriftX)
                            val pullProgress = (effectiveLift / (46f * density)).coerceIn(0f, 1f)

                            springY.setTarget(-effectiveLift, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            springX.setTarget(driftX, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            springScaleX.setTarget(1f + 0.085f * pullProgress, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            springScaleY.setTarget(1f - 0.075f * pullProgress, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            ensureFrameLoop()
                        }
                        TouchMode.DOWNWARD_RUBBER -> {
                            val dragDown = dy.coerceAtLeast(0f)
                            val maxYield = 4f * density
                            val yieldY = ((dragDown * 0.14f) * maxYield) / ((dragDown * 0.14f) + maxYield)
                            val ratio = (yieldY / maxYield).coerceIn(0f, 1f)
                            springY.setTarget(yieldY, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            springScaleX.setTarget(1f - 0.025f * ratio, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            springScaleY.setTarget(1f + 0.04f * ratio, TRACKING_STIFFNESS, TRACKING_DAMPING)
                            ensureFrameLoop()
                        }
                        else -> Unit
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val upId = event.getPointerId(event.actionIndex)
                    if (upId == activePointerId && event.pointerCount > 1) {
                        val nextIdx = if (event.actionIndex == 0) 1 else 0
                        downX += event.getX(nextIdx) - curX
                        downY += event.getY(nextIdx) - curY
                        activePointerId = event.getPointerId(nextIdx)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velY = velocityTracker?.yVelocity ?: 0f
                    val velX = velocityTracker?.xVelocity ?: 0f
                    recycleVelocityTracker()

                    val previousMode = touchMode
                    touchMode = TouchMode.NONE
                    activePointerId = MotionEvent.INVALID_POINTER_ID

                    val elapsed = SystemClock.uptimeMillis() - downTimeMs
                    val density = host.resources.displayMetrics.density
                    val screenH = host.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
                    val screenW = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels

                    if (previousMode == TouchMode.UPWARD_DRAG) {
                        // Preserve release momentum for silky iOS spring return when not unlocking.
                        val maxVel = 780f * density
                        springY.velocity = (velY * 0.22f).coerceIn(-maxVel, maxVel)
                        springX.velocity = (velX * 0.12f).coerceIn(-300f * density, 300f * density)
                        returnToRestSpring(RELEASE_STIFFNESS, RELEASE_DAMPING)
                        scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
                    } else if (
                        action == MotionEvent.ACTION_UP &&
                        previousMode == TouchMode.NONE &&
                        maxMoveDist < touchSlop &&
                        elapsed in 10L..260L &&
                        downY > screenH * 0.18f &&
                        !(downY >= screenH * 0.82f && (downX < screenW * 0.24f || downX > screenW * 0.76f))
                    ) {
                        returnToRestSpring(RELEASE_STIFFNESS, 0.82f)
                        if (settings.gestureLineUnlockHintAnim && isLockscreenActive()) {
                            handler.removeCallbacks(tapBounceRunnable)
                            handler.postDelayed(tapBounceRunnable, TAP_BOUNCE_DELAY_MS)
                        } else {
                            scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
                        }
                    } else {
                        returnToRestSpring(RELEASE_STIFFNESS, RELEASE_DAMPING)
                        scheduleNextBounceIfEligible(IDLE_BOUNCE_INTERVAL_MS)
                    }
                }
            }
        }

        private fun canPlayBounce(): Boolean =
            settings.gestureLineEnabled &&
                settings.gestureLineUnlockHintAnim &&
                touchMode == TouchMode.NONE &&
                currentExpansionFraction() >= 0.99f &&
                isLockscreenActive()

        private fun scheduleNextBounceIfEligible(delayMs: Long) {
            handler.removeCallbacks(bounceStartRunnable)
            if (!settings.gestureLineEnabled || !settings.gestureLineUnlockHintAnim) return
            if (!isLockscreenActive()) return
            handler.postDelayed(bounceStartRunnable, delayMs)
        }

        private fun playUnlockHintBounce(isTapNudge: Boolean) {
            handler.removeCallbacks(bounceReturnRunnable)
            isBounceRunning = true
            val density = host.resources.displayMetrics.density
            val liftDp = if (isTapNudge) 10.5f else 12.0f
            val liftDurationMs = if (isTapNudge) 220L else 255L
            springY.setTarget(-liftDp * density, BOUNCE_LIFT_STIFFNESS, BOUNCE_LIFT_DAMPING)
            springScaleX.setTarget(if (isTapNudge) 1.045f else 1.055f, BOUNCE_LIFT_STIFFNESS, 0.86f)
            springScaleY.setTarget(if (isTapNudge) 0.95f else 0.94f, BOUNCE_LIFT_STIFFNESS, 0.86f)
            ensureFrameLoop()
            handler.postDelayed(bounceReturnRunnable, liftDurationMs)
        }

        private fun returnToRestSpring(stiffness: Float, damping: Float) {
            springY.setTarget(0f, stiffness, damping)
            springX.setTarget(0f, stiffness, 0.82f)
            springScaleX.setTarget(1f, stiffness, 0.78f)
            springScaleY.setTarget(1f, stiffness, 0.78f)
            springAlpha.setTarget(1f, 360f, 0.90f)
            ensureFrameLoop()
        }

        private fun cancelBounceTimers() {
            handler.removeCallbacks(bounceStartRunnable)
            handler.removeCallbacks(tapBounceRunnable)
            handler.removeCallbacks(bounceReturnRunnable)
        }

        private fun stopAllAnimationsAndTimers() {
            cancelBounceTimers()
            isBounceRunning = false
            if (frameLoopRunning) {
                choreographer.removeFrameCallback(this)
                frameLoopRunning = false
            }
            lastFrameNanos = 0L
        }

        private fun resetVisualStateImmediate() {
            springY.snapTo(0f)
            springX.snapTo(0f)
            springScaleX.snapTo(1f)
            springScaleY.snapTo(1f)
            springAlpha.snapTo(1f)
            applyTransforms()
        }

        private fun ensureFrameLoop() {
            if (frameLoopRunning) return
            frameLoopRunning = true
            lastFrameNanos = 0L
            choreographer.postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            val dtSec = if (lastFrameNanos == 0L) {
                0.008f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
            }
            lastFrameNanos = frameTimeNanos

            val activeY = springY.step(dtSec)
            val activeX = springX.step(dtSec)
            val activeSx = springScaleX.step(dtSec)
            val activeSy = springScaleY.step(dtSec)
            val activeAlpha = springAlpha.step(dtSec)
            applyTransforms()

            if (activeY || activeX || activeSx || activeSy || activeAlpha) {
                choreographer.postFrameCallback(this)
            } else {
                frameLoopRunning = false
                lastFrameNanos = 0L
            }
        }

        private fun applyTransforms() {
            pillView.translationY = springY.value
            pillView.translationX = springX.value
            pillView.scaleX = springScaleX.value
            pillView.scaleY = springScaleY.value
            pillView.alpha = springAlpha.value.coerceIn(0f, 1f)
        }

        private fun updateGeometry() {
            val res = host.resources
            val dm = res.displayMetrics
            val density = dm.density
            val screenWidth = host.width.takeIf { it > 0 } ?: dm.widthPixels
            val isPortrait = res.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

            // Base dimensions matching iOS lockscreen home indicator & HyperOS navigation handle.
            val baseWidthPx = screenWidth * if (isPortrait) 0.34f else 0.22f
            val baseHeightPx = 5.0f * density
            val baseBottomPx = 8.0f * density

            // Smooth monotonic scaling across -100%..100% with zero dead zones.
            val widthFactor = if (settings.gestureLineWidthScale < 0) {
                1f + 0.65f * (settings.gestureLineWidthScale / 100f)
            } else {
                1f + 0.85f * (settings.gestureLineWidthScale / 100f)
            }
            val heightFactor = if (settings.gestureLineHeightScale < 0) {
                1f + 0.70f * (settings.gestureLineHeightScale / 100f)
            } else {
                1f + 1.00f * (settings.gestureLineHeightScale / 100f)
            }

            val maxWidthPx = (screenWidth - 32f * density).coerceAtLeast(48f * density)
            val pillWidthPx = (baseWidthPx * widthFactor).coerceIn(36f * density, maxWidthPx).roundToInt()
            val pillHeightPx = (baseHeightPx * heightFactor).coerceIn(1.5f * density, 14f * density).roundToInt()

            // Vertical offset: 0% = 8dp default; negative moves smoothly down to 0dp; positive moves up to +40dp.
            val offsetPx = if (settings.gestureLineVerticalOffset < 0) {
                baseBottomPx * (settings.gestureLineVerticalOffset / 100f)
            } else {
                (40f * density) * (settings.gestureLineVerticalOffset / 100f)
            }
            val bottomMarginPx = (baseBottomPx + offsetPx).coerceAtLeast(0f).roundToInt()

            val changed = pillWidthPx != lastPillWidthPx ||
                pillHeightPx != lastPillHeightPx ||
                bottomMarginPx != lastBottomMarginPx
            if (!changed) return

            lastPillWidthPx = pillWidthPx
            lastPillHeightPx = pillHeightPx
            lastBottomMarginPx = bottomMarginPx

            val lp = (pillView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(
                pillWidthPx,
                pillHeightPx,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            )
            lp.width = pillWidthPx
            lp.height = pillHeightPx
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = bottomMarginPx
            pillView.layoutParams = lp
            pillView.pivotX = pillWidthPx / 2f
            pillView.pivotY = pillHeightPx / 2f
            pillView.invalidateOutline()
            applyMaterial(force = true)
            syncIndicationBottomMargin()
        }

        private fun applyMaterial(force: Boolean) {
            if (!settings.gestureLineEnabled) return
            val isDark = currentDarkStyle()
            val material = settings.gestureLineMaterial
            if (!force && lastAppliedDarkStyle == isDark && lastAppliedMaterial == material) return
            lastAppliedDarkStyle = isDark
            lastAppliedMaterial = material

            val radiusPx = (lastPillHeightPx.takeIf { it > 0 } ?: 15) / 2f
            if (material == LOCKSCREEN_GESTURE_LINE_MATERIAL_BLUR) {
                val fallbackFill = if (isDark) {
                    Color.argb(52, 20, 20, 20)
                } else {
                    Color.argb(60, 255, 255, 255)
                }
                pillView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(fallbackFill)
                }
                val blendColor = if (isDark) Color.rgb(20, 20, 20) else Color.WHITE
                val blendOpacity = if (isDark) 34 else 38
                runCatching {
                    HyperOsBackdropMaterial.apply(
                        view = pillView,
                        opacity = blendOpacity,
                        blurRadius = 42,
                        color = blendColor,
                        showHighlight = false,
                    )
                }
            } else {
                runCatching { HyperOsBackdropMaterial.clear(pillView) }
                val solidFill = if (isDark) {
                    Color.argb(178, 0, 0, 0)
                } else {
                    Color.argb(235, 255, 255, 255)
                }
                pillView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(solidFill)
                }
            }
            pillView.invalidateOutline()
            pillView.invalidate()
        }

        private fun syncIndicationBottomMargin() {
            val textView = indicationText ?: return
            val lp = textView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (stockIndicationOwner !== textView) {
                stockIndicationOwner = textView
                stockIndicationBottomMargin = lp.bottomMargin
            }
            val stockMargin = stockIndicationBottomMargin ?: lp.bottomMargin
            if (!settings.gestureLineEnabled) {
                if (lp.bottomMargin != stockMargin) {
                    lp.bottomMargin = stockMargin
                    textView.layoutParams = lp
                }
                return
            }
            val density = textView.resources.displayMetrics.density
            val clearance = lastBottomMarginPx + lastPillHeightPx + (8f * density).roundToInt()
            val desired = clearance.coerceIn(stockMargin, stockMargin + (36f * density).roundToInt())
            if (lp.bottomMargin != desired) {
                lp.bottomMargin = desired
                textView.layoutParams = lp
            }
        }

        private fun restoreIndicationBottomMargin() {
            val textView = indicationText ?: return
            if (stockIndicationOwner !== textView) return
            val stockMargin = stockIndicationBottomMargin ?: return
            val lp = textView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.bottomMargin != stockMargin) {
                lp.bottomMargin = stockMargin
                textView.layoutParams = lp
            }
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }
    }

    companion object {
        private const val KEYGUARD_INDICATION_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.KeyguardIndicationController"
        private const val KEYGUARD_INDICATION_CLASS =
            "com.android.systemui.keyguard.KeyguardIndication"
        private const val KEYGUARD_BOTTOM_AREA_INJECTOR_CLASS =
            "com.android.keyguard.injector.KeyguardBottomAreaInjector"
        private const val KEYGUARD_BOTTOM_AREA_VIEW_CLASS =
            "com.android.systemui.statusbar.phone.KeyguardBottomAreaView"
        private const val KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController"
        private const val KEYGUARD_PANEL_WAKE_OBSERVER_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController\$wakeObserver\$1"
        private const val NOTIFICATION_PANEL_VIEW_CLASS =
            "com.android.systemui.shade.NotificationPanelView"

        private const val INDICATION_TYPE_OWNER_INFO = 0
        private const val GESTURE_LINE_VIEW_TAG = "hc_lockscreen_gesture_line"

        // Spring tuning constants crafted for silky iOS-grade lockscreen feel.
        private const val TRACKING_STIFFNESS = 1450f
        private const val TRACKING_DAMPING = 0.96f
        private const val RELEASE_STIFFNESS = 320f
        private const val RELEASE_DAMPING = 0.72f
        private const val BOUNCE_LIFT_STIFFNESS = 290f
        private const val BOUNCE_LIFT_DAMPING = 0.88f
        private const val BOUNCE_RETURN_STIFFNESS = 260f
        private const val BOUNCE_RETURN_DAMPING = 0.56f

        private const val WAKE_BOUNCE_DELAY_MS = 950L
        private const val WAKE_SETTLED_BOUNCE_DELAY_MS = 550L
        private const val TAP_BOUNCE_DELAY_MS = 55L
        private const val IDLE_BOUNCE_INTERVAL_MS = 4600L

        private val MONITORED_KEYS = setOf(
            KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT,
            KEY_LOCKSCREEN_GESTURE_LINE_ENABLED,
            KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL,
            KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE,
            KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE,
            KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET,
            KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM,
        )
    }
}
