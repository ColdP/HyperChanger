// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Runtime controller for Clear Frost (Classic / material_style == 0) material customization
 * across SystemUI notifications, heads-up notifications, shade backdrop, and Control Center.
 *
 * Remains completely passive whenever the system is in Bionics (Soft Glass) mode or when
 * Clear Frost adjustment is disabled, preserving the existing Soft Glass pipeline untouched.
 */
internal class ClearFrostHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
    private val isExcludedSurface: (View) -> Boolean,
    private val isShadeBackgroundView: (View) -> Boolean,
    private val isNotificationRowBackground: (View) -> Boolean,
    private val isMediaNotificationView: (View) -> Boolean,
    private val isVolumePanelSurface: (View) -> Boolean,
) {
    private enum class SurfaceKind {
        NONE,
        SHADE_BACKDROP,
        NOTIFICATION_ELEMENT,
        CONTROL_CENTER_ELEMENT,
    }

    private data class BlendIntArrayBaseline(
        val method: Method,
        val colors: IntArray,
        val ratio: Float,
    )

    private data class StyledSurfaceRecord(
        val appliedStroke: Boolean,
        val appliedShadow: Boolean,
    )

    private var runtime: ClearFrostRuntimeSettings? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshing = ThreadLocal<Boolean>()
    private val dispatching = ThreadLocal<Boolean>()

    @Volatile private var materialStyleObserverRegistered = false
    @Volatile private var cachedMaterialStyle = 0

    private val viewSetterBaselines = WeakHashMap<View, MutableMap<Method, Array<Any?>>>()
    private val systemUiBlendBaselines = WeakHashMap<View, BlendIntArrayBaseline>()
    private val styledSurfaces = WeakHashMap<View, StyledSurfaceRecord>()
    private val shadowBlockedViews = WeakHashMap<View, Boolean>()

    private val setMiBloomStrokeMethod: Method? by lazy {
        runCatching { View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java) }.getOrNull()
    }
    private val setMiShadowMethod: Method? by lazy {
        runCatching {
            View::class.java.getMethod(
                "setMiShadow",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val setMixEffectEnabledMethod: Method? by lazy {
        runCatching { View::class.java.getMethod("setMixEffectEnabled", Boolean::class.javaPrimitiveType) }.getOrNull()
    }

    private val refreshRunnable = Runnable {
        val setterSnapshots = synchronized(viewSetterBaselines) {
            viewSetterBaselines.map { (view, setters) -> view to setters.toMap() }
        }
        val blendSnapshots = synchronized(systemUiBlendBaselines) {
            systemUiBlendBaselines.toMap()
        }
        val previousStyled = synchronized(styledSurfaces) {
            styledSurfaces.toMap()
        }
        val activeNow = isFeatureEnabled() && cachedMaterialStyle == 0
        refreshing.set(true)
        try {
            if (!activeNow) {
                previousStyled.forEach { (view, record) ->
                    if (view.isAttachedToWindow) clearSurfaceDecorations(view, record)
                }
            }
            setterSnapshots.forEach { (view, setters) ->
                if (view.isAttachedToWindow) {
                    setters.forEach { (method, args) ->
                        runCatching { method.invoke(view, *args) }
                    }
                }
            }
            blendSnapshots.forEach { (view, baseline) ->
                if (view.isAttachedToWindow) {
                    runCatching {
                        baseline.method.invoke(null, view, baseline.colors.copyOf(), baseline.ratio)
                    }
                }
            }
        } finally {
            refreshing.remove()
            if (!activeNow) {
                synchronized(viewSetterBaselines) { viewSetterBaselines.clear() }
                synchronized(systemUiBlendBaselines) { systemUiBlendBaselines.clear() }
                synchronized(styledSurfaces) { styledSurfaces.clear() }
                synchronized(shadowBlockedViews) { shadowBlockedViews.clear() }
            }
        }
    }

    fun install(preferences: SharedPreferences, classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runtime = ClearFrostRuntimeSettings(preferences) {
            scheduleRefresh()
        }
        installSystemUiBlendArrayHook(classLoader)
        installViewBlurModeClearHook()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 16L)
    }

    private fun isFeatureEnabled(): Boolean = runtime?.settings?.enabled == true

    private fun ensureMaterialStyleObserved(context: Context?) {
        if (materialStyleObserverRegistered || context == null) return
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver ?: return
        synchronized(this) {
            if (materialStyleObserverRegistered) return
            cachedMaterialStyle = readSystemMaterialStyle(appContext)
            runCatching {
                val uri = Settings.Secure.getUriFor("material_style")
                resolver.registerContentObserver(uri, false, object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        val updated = readSystemMaterialStyle(appContext)
                        if (updated != cachedMaterialStyle) {
                            cachedMaterialStyle = updated
                            scheduleRefresh()
                        }
                    }
                })
            }
            materialStyleObserverRegistered = true
        }
    }

    private fun readSystemMaterialStyle(context: Context): Int = runCatching {
        Settings.Secure.getInt(context.contentResolver, "material_style", 0)
    }.getOrDefault(0)

    /** True only when Clear Frost customization is enabled AND the system is in Classic mode (0). */
    fun isActive(view: View): Boolean {
        if (!isFeatureEnabled()) return false
        ensureMaterialStyleObserved(view.context)
        return cachedMaterialStyle == 0
    }

    fun isDispatching(): Boolean = dispatching.get() == true

    fun isRefreshing(): Boolean = refreshing.get() == true

    private fun rememberViewBaseline(view: View, method: Method, args: Array<Any?>) {
        if (refreshing.get() == true) return
        synchronized(viewSetterBaselines) {
            viewSetterBaselines.getOrPut(view) { mutableMapOf() }[method] = args
        }
    }

    private fun installSystemUiBlendArrayHook(classLoader: ClassLoader) {
        runCatching {
            val compatClass = classLoader.loadClass("com.miui.systemui.util.MiBlurCompat")
            val method = compatClass.getDeclaredMethod(
                "setMiBackgroundBlendColors",
                View::class.java,
                IntArray::class.java,
                Float::class.javaPrimitiveType,
            )
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("clear-frost-v1-systemui-blend-array")
                .intercept { chain ->
                    if (dispatching.get() == true) return@intercept chain.proceed()
                    val view = chain.getArg(0) as? View ?: return@intercept chain.proceed()
                    val originalColors = chain.getArg(1) as? IntArray ?: return@intercept chain.proceed()
                    val ratio = chain.getArg(2) as? Float ?: 1f
                    if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) {
                        return@intercept chain.proceed()
                    }
                    val settings = runtime?.settings ?: return@intercept chain.proceed()
                    val kind = classifySurface(view)
                    if (kind == SurfaceKind.NONE) return@intercept chain.proceed()

                    if (refreshing.get() != true) {
                        synchronized(systemUiBlendBaselines) {
                            systemUiBlendBaselines[view] = BlendIntArrayBaseline(method, originalColors.copyOf(), ratio)
                        }
                    }

                    if (kind == SurfaceKind.SHADE_BACKDROP) {
                        val originalPoints = intPairsToPoints(originalColors, ratio)
                        val tunedPoints = resolveClearFrostShadeBackdropPoints(originalPoints, settings)
                        val tunedPairs = pointsToIntPairs(tunedPoints)
                        dispatching.set(true)
                        return@intercept try {
                            chain.proceed(arrayOf(view, tunedPairs, 1f))
                        } finally {
                            dispatching.remove()
                        }
                    }

                    val isControlCenter = kind == SurfaceKind.CONTROL_CENTER_ELEMENT
                    val isLight = resolveClearFrostIsLight(isSystemDark(view), isControlCenter, settings)
                    val replacement = resolveClearFrostElementBlendPoints(settings, isLight, isControlCenter)
                    val tunedPairs = if (replacement != null) {
                        pointsToIntPairs(replacement)
                    } else {
                        scaleIntPairsAlpha(originalColors, settings.elementOpacity)
                    }

                    dispatching.set(true)
                    try {
                        val result = chain.proceed(arrayOf(view, tunedPairs, ratio))
                        applyElementDecorations(view, settings, isLight, allowShadow = canApplyShadow(view, kind))
                        result
                    } finally {
                        dispatching.remove()
                    }
                }
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed Clear Frost SystemUI blend hook")
        }.onFailure { error ->
            HookModuleLogger.log(module, Log.INFO, logTag, "Clear Frost SystemUI blend hook unavailable", error)
        }
    }

    private fun installViewBlurModeClearHook() {
        runCatching {
            val method = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("clear-frost-v1-blur-mode-clear")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val mode = chain.getArg(0) as? Int
                    if (view != null && mode == 0 && dispatching.get() != true) {
                        val record = synchronized(styledSurfaces) { styledSurfaces.remove(view) }
                        if (record != null) {
                            clearSurfaceDecorations(view, record)
                        }
                    }
                    chain.proceed()
                }
        }.onFailure { error ->
            HookModuleLogger.log(module, Log.INFO, logTag, "Clear Frost blur mode guard unavailable", error)
        }
    }

    /**
     * Handles View.setMiBackgroundBlurRadius(int) when Clear Frost is active.
     * Returns true if handled (result stored via [proceed]), false to fall back to SoftGlass/original.
     */
    fun handleBackgroundBlurRadius(
        view: View,
        method: Method,
        radius: Int,
        proceedWith: (Array<Any?>) -> Any?,
    ): Pair<Boolean, Any?> {
        if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) return false to null
        val settings = runtime?.settings ?: return false to null
        if (classifySurface(view) == SurfaceKind.NONE) return false to null
        rememberViewBaseline(view, method, arrayOf(radius))
        return true to proceedWith(arrayOf(softGlassBlur(radius, settings.backgroundBlur)))
    }

    fun handleBackgroundBlurScaleRatio(
        view: View,
        method: Method,
        scale: Float,
        proceedWith: (Array<Any?>) -> Any?,
    ): Pair<Boolean, Any?> {
        if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) return false to null
        val settings = runtime?.settings ?: return false to null
        if (classifySurface(view) == SurfaceKind.NONE) return false to null
        rememberViewBaseline(view, method, arrayOf(scale))
        return true to proceedWith(arrayOf(softGlassScale(scale, settings.backgroundScale)))
    }

    fun handleBackgroundBlendColors(
        view: View,
        method: Method,
        original: ArrayList<*>,
        proceedWith: (Array<Any?>) -> Any?,
    ): Pair<Boolean, Any?> {
        if (dispatching.get() == true) return false to null
        if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) return false to null
        val settings = runtime?.settings ?: return false to null
        val kind = classifySurface(view)
        if (kind == SurfaceKind.NONE) return false to null

        val copy = ArrayList<Point>(original.size).apply {
            original.filterIsInstance<Point>().forEach { add(Point(it.x, it.y)) }
        }
        if (copy.isEmpty()) return false to null

        if (kind == SurfaceKind.SHADE_BACKDROP) {
            rememberViewBaseline(view, method, arrayOf(copy))
            val tuned = resolveClearFrostShadeBackdropPoints(copy, settings)
            dispatching.set(true)
            return try {
                true to proceedWith(arrayOf(tuned))
            } finally {
                dispatching.remove()
            }
        }

        if (kind == SurfaceKind.CONTROL_CENTER_ELEMENT) {
            when (classifyControlCenterBlendPoints(copy)) {
                ControlCenterBlendState.ICON_OR_IGNORE -> return false to null
                ControlCenterBlendState.ACTIVE_ON -> {
                    clearDecorationsIfStyled(view)
                    return false to null
                }
                ControlCenterBlendState.TRANSITION -> {
                    clearDecorationsIfStyled(view)
                    return false to null
                }
                ControlCenterBlendState.INACTIVE_SURFACE -> {
                    // Proceed with Clear Frost styling below.
                }
            }
        }

        rememberViewBaseline(view, method, arrayOf(copy))
        val isControlCenter = kind == SurfaceKind.CONTROL_CENTER_ELEMENT
        val isLight = resolveClearFrostIsLight(isSystemDark(view), isControlCenter, settings)
        val replacement = resolveClearFrostElementBlendPoints(settings, isLight, isControlCenter)
        val finalPoints = replacement ?: scalePointsAlpha(copy, settings.elementOpacity)

        dispatching.set(true)
        return try {
            val result = proceedWith(arrayOf(finalPoints))
            applyElementDecorations(view, settings, isLight, allowShadow = canApplyShadow(view, kind))
            true to result
        } finally {
            dispatching.remove()
        }
    }

    fun handleSelfBlur(
        view: View,
        method: Method,
        radius: Int,
        original: ArrayList<*>,
        proceedWith: (Array<Any?>) -> Any?,
    ): Pair<Boolean, Any?> {
        if (dispatching.get() == true) return false to null
        if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) return false to null
        val settings = runtime?.settings ?: return false to null
        if (refreshing.get() != true && !stackContains("ShadeWindowBlurController")) return false to null

        val copy = ArrayList<Point>(original.size).apply {
            original.filterIsInstance<Point>().forEach { add(Point(it.x, it.y)) }
        }
        rememberViewBaseline(view, method, arrayOf(radius, copy))
        val tunedPoints = resolveClearFrostShadeBackdropPoints(copy, settings)
        dispatching.set(true)
        return try {
            true to proceedWith(arrayOf(softGlassBlur(radius, settings.backgroundBlur), tunedPoints))
        } finally {
            dispatching.remove()
        }
    }

    fun handleBackgroundColor(
        view: View,
        method: Method,
        color: Int,
        proceedWith: (Array<Any?>) -> Any?,
    ): Pair<Boolean, Any?> {
        if (!isActive(view) || isExcludedSurface(view) || isVolumePanelSurface(view)) return false to null
        val settings = runtime?.settings ?: return false to null
        if (refreshing.get() != true &&
            !stackContainsAny("ShadeWindowBlurController", "ShadeSolidBackgroundController")
        ) return false to null
        rememberViewBaseline(view, method, arrayOf(color))
        return true to proceedWith(arrayOf(softGlassColorAlpha(color, settings.backgroundOpacity)))
    }

    private fun applyElementDecorations(
        view: View,
        settings: ClearFrostSettings,
        isLight: Boolean,
        allowShadow: Boolean,
    ) {
        val density = view.resources?.displayMetrics?.density ?: 3f
        val applyStroke = settings.strokeEnabled
        val applyShadow = settings.shadowEnabled &&
            allowShadow &&
            settings.shadowRadius > 0 &&
            settings.shadowAlpha > 0

        runCatching { setMixEffectEnabledMethod?.invoke(view, true) }

        if (applyStroke) {
            val stroke = buildClearFrostBloomStrokePx(settings, isLight, density)
            runCatching { setMiBloomStrokeMethod?.invoke(view, stroke) }
        } else {
            val prev = synchronized(styledSurfaces) { styledSurfaces[view] }
            if (prev?.appliedStroke == true) {
                runCatching { setMiBloomStrokeMethod?.invoke(view, CLEAR_FROST_EMPTY_STROKE) }
            }
        }

        if (applyShadow) {
            val shadowColor = resolveClearFrostShadowColor(settings)
            runCatching {
                setMiShadowMethod?.invoke(
                    view,
                    shadowColor,
                    0f,
                    settings.shadowOffsetY.toFloat(),
                    settings.shadowRadius.toFloat(),
                    1.0f,
                )
            }
        } else {
            val prev = synchronized(styledSurfaces) { styledSurfaces[view] }
            if (prev?.appliedShadow == true) {
                runCatching { setMiShadowMethod?.invoke(view, 0, 0f, 0f, 0f, 1.0f) }
            }
        }

        synchronized(styledSurfaces) {
            if (applyStroke || applyShadow) {
                styledSurfaces[view] = StyledSurfaceRecord(applyStroke, applyShadow)
            } else {
                styledSurfaces.remove(view)
            }
        }
    }

    private fun clearDecorationsIfStyled(view: View) {
        val record = synchronized(styledSurfaces) { styledSurfaces.remove(view) } ?: return
        clearSurfaceDecorations(view, record)
    }

    private fun clearSurfaceDecorations(view: View, record: StyledSurfaceRecord) {
        if (record.appliedStroke) {
            runCatching { setMiBloomStrokeMethod?.invoke(view, CLEAR_FROST_EMPTY_STROKE) }
        }
        if (record.appliedShadow) {
            runCatching { setMiShadowMethod?.invoke(view, 0, 0f, 0f, 0f, 1.0f) }
        }
    }

    private enum class ControlCenterBlendState {
        INACTIVE_SURFACE,
        ACTIVE_ON,
        TRANSITION,
        ICON_OR_IGNORE,
    }

    /**
     * Classifies MiuiColorBlendToken payloads in MiuiSystemUIPlugin Classic mode so we only
     * restyle inactive/container frosted surfaces and preserve active ON tiles, slider fills,
     * icons, and smooth toggle transitions.
     */
    private fun classifyControlCenterBlendPoints(points: List<Point>): ControlCenterBlendState {
        var hasInactiveSurface = false
        var hasActiveOn = false
        var hasIcon = false
        for (p in points) {
            val color = p.x
            val mode = p.y
            val rgb = color and 0x00FFFFFF
            val alpha = (color ushr 24) and 0xFF
            if (mode == 100 /* LINEAR_LIGHT */) {
                when (rgb) {
                    0x7D7D7D -> {
                        // Icons use 0x807D7D7D or 0xFF7D7D7D; tile/card/slider-bg containers use 0xB37D7D7D or 0xBF7D7D7D.
                        if (alpha == 0x80 || alpha == 0xFF) {
                            hasIcon = true
                        } else if (alpha > 0) {
                            hasInactiveSurface = true
                        }
                    }
                    0x757575 -> {
                        // Edit button uses 0x4D757575.
                        if (alpha > 0) hasInactiveSurface = true
                    }
                    0xB0B0B0 -> {
                        // 0x99B0B0B0 is restricted tile background; 0xCCB0B0B0 / 0x66B0B0B0 are active ON / slider progress.
                        if (color == -1716473680 /* 0x99B0B0B0 */) {
                            hasInactiveSurface = true
                        } else if (alpha > 0) {
                            hasActiveOn = true
                        }
                    }
                }
            }
        }
        if (hasIcon && !hasInactiveSurface && !hasActiveOn) return ControlCenterBlendState.ICON_OR_IGNORE
        if (hasActiveOn && hasInactiveSurface) return ControlCenterBlendState.TRANSITION
        if (hasActiveOn) return ControlCenterBlendState.ACTIVE_ON
        if (hasInactiveSurface) return ControlCenterBlendState.INACTIVE_SURFACE
        return ControlCenterBlendState.ICON_OR_IGNORE
    }

    private fun classifySurface(view: View): SurfaceKind {
        if (isShadeBackgroundView(view)) return SurfaceKind.SHADE_BACKDROP
        if (isNotificationRowBackground(view) || isMediaNotificationView(view) ||
            view.javaClass.name == "com.miui.systemui.widget.CircleAndTickAnimView"
        ) {
            return SurfaceKind.NOTIFICATION_ELEMENT
        }
        val viewClass = view.javaClass.name
        if (viewClass.startsWith("miui.systemui.controlcenter.")) {
            return SurfaceKind.CONTROL_CENTER_ELEMENT
        }
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val name = frame.className
            if (name.contains("ShadeBlendBlurController", ignoreCase = true) ||
                name.contains("ShadeWindowBlurController", ignoreCase = true) ||
                name.contains("ShadeSolidBackgroundController", ignoreCase = true)
            ) {
                return SurfaceKind.SHADE_BACKDROP
            }
            if (name.startsWith("miui.systemui.controlcenter.") ||
                name.startsWith("miui.systemui.plugin.brightness.") ||
                name.startsWith("miui.systemui.ui.material.")
            ) {
                return SurfaceKind.CONTROL_CENTER_ELEMENT
            }
            if (name.startsWith("com.android.systemui.statusbar.notification.") ||
                name.startsWith("com.miui.systemui.notification.")
            ) {
                return SurfaceKind.NOTIFICATION_ELEMENT
            }
        }
        return if (refreshing.get() == true) {
            if (isParentControlCenter(view)) SurfaceKind.CONTROL_CENTER_ELEMENT else SurfaceKind.NOTIFICATION_ELEMENT
        } else {
            SurfaceKind.NONE
        }
    }

    private fun isParentControlCenter(view: View): Boolean {
        var current: Any? = view
        var depth = 0
        while (current is View && depth < 12) {
            val name = current.javaClass.name
            if (name.startsWith("miui.systemui.controlcenter.") || name.startsWith("miui.systemui.plugin.brightness.")) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * `View.setMiShadow(..., radius, ...)` also invokes `mRenderNode.setElevation(radius)` in the
     * framework. Applying it to a background-only sibling view (such as `NotificationBackgroundView`,
     * `mediaBg`, slider `progressBg`, or `contentBg`) raises that blur surface in Z above its
     * foreground content siblings, causing the background blur pass to draw over and blur the
     * content itself. Restrict surface shadows to safe Control Center containers/icons that do not
     * sit underneath overlapping foreground siblings.
     */
    private fun canApplyShadow(view: View, kind: SurfaceKind): Boolean {
        if (kind != SurfaceKind.CONTROL_CENTER_ELEMENT) return false
        if (synchronized(shadowBlockedViews) { shadowBlockedViews[view] == true }) return false
        val idName = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        if (idName == "progress_bg" || idName == "progressBg" || idName == "v_progress_bg" ||
            idName == "content_bg" || idName == "blur_bg" || idName == "media_bg" ||
            idName == "backgroundNormal" || idName == "backgroundDimmed"
        ) {
            return false
        }
        if (stackContainsAny(
                "ToggleSliderViewHolder",
                "BrightnessPanelSliderDelegate",
                "BrightnessWindowSliderView",
                "SecondaryPanelControllerBase",
                "EditButtonController",
            )
        ) {
            synchronized(shadowBlockedViews) { shadowBlockedViews[view] = true }
            return false
        }
        if (hasOverlappingForegroundSibling(view)) return false
        return true
    }

    private fun hasOverlappingForegroundSibling(view: View): Boolean {
        if (view is ViewGroup) return false
        val parent = view.parent as? ViewGroup ?: return false
        if (parent is LinearLayout) return false
        val index = parent.indexOfChild(view)
        if (index < 0 || index >= parent.childCount - 1) return false
        if (view.width > 0 && view.height > 0) {
            for (i in (index + 1) until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling.visibility != View.VISIBLE) continue
                if (sibling.width <= 0 || sibling.height <= 0) continue
                if (sibling.left < view.right &&
                    sibling.right > view.left &&
                    sibling.top < view.bottom &&
                    sibling.bottom > view.top
                ) {
                    return true
                }
            }
            return false
        }
        return parent is FrameLayout
    }

    private fun isSystemDark(view: View): Boolean =
        (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun stackContains(token: String): Boolean =
        Thread.currentThread().stackTrace.any { it.className.contains(token, ignoreCase = true) }

    private fun stackContainsAny(vararg tokens: String): Boolean {
        val stack = Thread.currentThread().stackTrace
        return stack.any { frame -> tokens.any { frame.className.contains(it, ignoreCase = true) } }
    }

    private fun intPairsToPoints(pairs: IntArray, ratio: Float): ArrayList<Point> {
        val count = pairs.size / 2
        return ArrayList<Point>(count).apply {
            for (i in 0 until count) {
                val baseColor = pairs[i * 2]
                val mode = pairs[i * 2 + 1]
                val color = if (ratio == 1f) baseColor else {
                    val alpha = (((baseColor ushr 24) and 0xFF) * ratio).toInt().coerceIn(0, 255)
                    (baseColor and 0x00FFFFFF) or (alpha shl 24)
                }
                add(Point(color, mode))
            }
        }
    }

    private fun pointsToIntPairs(points: List<Point>): IntArray =
        IntArray(points.size * 2).apply {
            points.forEachIndexed { index, point ->
                this[index * 2] = point.x
                this[index * 2 + 1] = point.y
            }
        }

    private fun scaleIntPairsAlpha(pairs: IntArray, percent: Int): IntArray {
        if (percent == 100) return pairs
        return pairs.copyOf().apply {
            var i = 0
            while (i + 1 < size) {
                this[i] = softGlassColorAlpha(this[i], percent)
                i += 2
            }
        }
    }

    private fun scalePointsAlpha(points: ArrayList<Point>, percent: Int): ArrayList<Point> {
        if (percent == 100) return points
        return ArrayList<Point>(points.size).apply {
            points.forEach { add(Point(softGlassColorAlpha(it.x, percent), it.y)) }
        }
    }
}
