// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.PathParser
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Modernizes HyperOS 4's deprecated Classic Control Center (com.android.systemui.qs.*)
 * with native Soft Glass & Clear Frost materials, shade backdrop blur tuning,
 * adaptive contrast, smooth capsule sliders, balanced spacing, and notification
 * Soft Glass preservation when classic Control Center is active.
 */
internal class ClassicControlCenterHook(
    private val module: XposedModule,
    private val showGlobalActions: () -> Unit,
) {
    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val qsContainers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val qsPanels = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val quickQsPanels = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val tileLayouts = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val tileViews = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val iconViews = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val pageIndicators = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val headerViews = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val containerControllers = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val brightnessMirrors = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val containerBaseHeaderHeights = WeakHashMap<Any, Int>()
    private val autoBrightnessObservers = WeakHashMap<View, ContentObserver>()
    private val volumeReceivers = WeakHashMap<View, BroadcastReceiver>()

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFieldMarkers = ConcurrentHashMap<String, Boolean>()
    private val typefaceCache = SparseArray<Typeface>()
    @Volatile private var cachedNativeGlassRecipe: FloatArray? = null
    @Volatile private var lastObservedSystemMaterial: String? = null
    @Volatile private var materialStyleObserverRegistered: Boolean = false
    @Volatile private var cachedSecureMaterialStyle: Int? = null
    @Volatile private var systemMaterialVersion: Int = 1

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var runtime: ClassicControlCenterRuntimeSettings? = null
    private var prefsRef: SharedPreferences? = null
    private var systemUiClassLoader: ClassLoader? = null

    private val refreshRunnable = Runnable { refreshAllActiveViews() }

    // Cached View material methods
    private val viewSetPassWindowBlur by lazy {
        runCatching { View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewSetMiBackgroundBlurMode by lazy {
        runCatching { View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewSetMiBackgroundBlurRadius by lazy {
        runCatching { View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewSetMiBackgroundBlurScaleRatio by lazy {
        runCatching { View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewChooseBackgroundBlurContainer by lazy {
        runCatching { View::class.java.getMethod("chooseBackgroundBlurContainer", View::class.java) }.getOrNull()
    }
    private val viewSetMiViewBlurMode by lazy {
        runCatching { View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewClearMiBackgroundBlendColor by lazy {
        runCatching { View::class.java.getMethod("clearMiBackgroundBlendColor") }.getOrNull()
    }
    private val viewAddMiBackgroundBlendColor by lazy {
        runCatching {
            View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val viewSetMiBackgroundBlendColors by lazy {
        runCatching { View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java) }.getOrNull()
    }
    private val viewSetMiBackgroundBlurEnhanceFlag by lazy {
        runCatching {
            View::class.java.getMethod("setMiBackgroundBlurEnhanceFlag", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val viewSetMixEffectEnabled by lazy {
        runCatching { View::class.java.getMethod("setMixEffectEnabled", Boolean::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewSetMiBloomStroke by lazy {
        runCatching { View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java) }.getOrNull()
    }
    private val viewSetMiShadow by lazy {
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
    private val viewSetMiGlassBlurRadius by lazy {
        runCatching {
            View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val viewSetMiViewMaterialType by lazy {
        runCatching { View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType) }.getOrNull()
    }
    private val viewSetMiGlassSdfMaxSize by lazy {
        runCatching {
            View::class.java.getMethod("setMiGlassSdfMaxSize", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val viewSetMiGlass by lazy {
        runCatching { View::class.java.getMethod("setMiGlass", FloatArray::class.java) }.getOrNull()
    }

    @Volatile private var miBlurCompatClass: Class<*>? = null
    @Volatile private var miGlassCompatClass: Class<*>? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        systemUiClassLoader = classLoader
        ensureRuntime(preferences)
        TARGET_CLASSES.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { clazz ->
                onClassLoaded(clazz, preferences)
            }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (clazz.name !in TARGET_CLASSES || !hookedClasses.add(clazz)) return
        if (systemUiClassLoader == null) systemUiClassLoader = clazz.classLoader
        ensureRuntime(preferences)
        runCatching {
            when (clazz.name) {
                CLS_NOTIF_MATERIAL_STATE_LAMBDA -> hookNotificationMaterialSoftGlass(clazz)
                CLS_SHADE_BIONICS_LAMBDA -> hookShadeBionicsSoftGlass(clazz)
                CLS_QS_CONTAINER -> hookQsContainer(clazz)
                CLS_QS_CONTAINER_CONTROLLER -> hookQsContainerController(clazz)
                CLS_QS_PANEL -> hookQsPanel(clazz)
                CLS_QUICK_QS_PANEL -> hookQuickQsPanel(clazz)
                CLS_TILE_LAYOUT -> hookTileLayout(clazz)
                CLS_QS_TILE_BASE_VIEW -> hookTileBaseView(clazz)
                CLS_QS_TILE_VIEW -> hookTileView(clazz)
                CLS_QS_ICON_VIEW -> hookIconView(clazz)
                CLS_PAGE_INDICATOR -> hookPageIndicator(clazz)
                CLS_QS_HEADER_VIEW -> hookQsHeaderView(clazz)
                CLS_BRIGHTNESS_SLIDER_VIEW -> hookBrightnessSliderView(clazz)
                CLS_BRIGHTNESS_MIRROR_CONTROLLER -> hookBrightnessMirrorController(clazz)
            }
        }.onFailure { error ->
            hookedClasses.remove(clazz)
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not hook classic CC class: ${clazz.name}", error)
        }
    }

    private fun ensureRuntime(preferences: SharedPreferences) {
        if (runtime != null) return
        prefsRef = preferences
        runtime = ClassicControlCenterRuntimeSettings(preferences) {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, 48L)
        }
    }

    private fun currentSettings(): ClassicControlCenterSettings =
        runtime?.settings ?: prefsRef?.let(::readClassicControlCenterSettings) ?: ClassicControlCenterSettings()

    private fun currentVersion(): Int = (runtime?.version ?: 1) * 31 + systemMaterialVersion

    private fun isEnabled(): Boolean =
        ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER) && currentSettings().enabled

    private fun shouldPreserveNotificationSoftGlass(): Boolean {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return false
        val s = currentSettings()
        if (s.enabled || s.unlockClassicInSystemUi) return true
        return prefsRef?.let { readSettingsAppScopePreferences(it).oldControlCenter } == true
    }

    private fun isClassicControlCenterActive(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            Settings.System.getInt(context.contentResolver, "use_control_panel", 1) == 0
        }.getOrDefault(false)
    }

    private fun ensureSystemMaterialObserver(context: Context?) {
        if (materialStyleObserverRegistered || context == null) return
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver ?: return
        synchronized(this) {
            if (materialStyleObserverRegistered) return
            cachedSecureMaterialStyle = readSecureMaterialStyle(appContext)
            runCatching {
                val uri = Settings.Secure.getUriFor("material_style")
                resolver.registerContentObserver(uri, false, object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        val updated = readSecureMaterialStyle(appContext)
                        if (updated != cachedSecureMaterialStyle) {
                            cachedSecureMaterialStyle = updated
                            systemMaterialVersion++
                            if (isEnabled()) {
                                mainHandler.removeCallbacks(refreshRunnable)
                                mainHandler.postDelayed(refreshRunnable, 48L)
                            }
                        }
                    }
                })
            }
            materialStyleObserverRegistered = true
        }
    }

    private fun readSecureMaterialStyle(context: Context): Int = runCatching {
        Settings.Secure.getInt(context.contentResolver, "material_style", 0)
    }.getOrDefault(0)

    private fun readSystemMaterialStyleName(view: View? = null): String? = runCatching {
        val compat = view?.let(::resolveMiBlurCompat)
            ?: systemUiClassLoader?.let { runCatching { Class.forName(CLS_MI_BLUR_COMPAT, false, it) }.getOrNull() }
            ?: return@runCatching null
        val field = findField(compat, "materialStyle") ?: return@runCatching null
        (field.get(null) as? Enum<*>)?.name ?: field.get(null)?.toString()
    }.getOrNull()

    private fun resolveEffectiveSurfaceMode(settings: ClassicControlCenterSettings, view: View? = null): Int {
        if (settings.panelSurfaceMode != CLASSIC_CC_SURFACE_FOLLOW_SYSTEM) {
            return settings.panelSurfaceMode
        }
        view?.context?.let(::ensureSystemMaterialObserver)
        return when (readSystemMaterialStyleName(view)) {
            "GLASS" -> CLASSIC_CC_SURFACE_SOFT_GLASS
            "BLUR" -> CLASSIC_CC_SURFACE_CLEAR_FROST
            else -> {
                val styleInt = cachedSecureMaterialStyle ?: view?.context?.let(::readSecureMaterialStyle) ?: 1
                if (styleInt == 1) CLASSIC_CC_SURFACE_SOFT_GLASS else CLASSIC_CC_SURFACE_CLEAR_FROST
            }
        }
    }

    /**
     * Determines whether text/icons/indicators inside the classic QS card should be crisp WHITE
     * (true) or crisp DARK (false) so contrast always matches the rendered card material.
     */
    private fun useLightForegroundOnPanel(
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        view: View? = null,
    ): Boolean {
        return when (resolveEffectiveSurfaceMode(settings, view)) {
            CLASSIC_CC_SURFACE_SOFT_GLASS -> true
            CLASSIC_CC_SURFACE_CLEAR_FROST -> {
                val cf = prefsRef?.let { resolveClassicControlCenterClearFrost(it, settings) }
                    ?: settings.clearFrost.copy(enabled = true).normalized()
                !resolveClearFrostIsLight(isDark, isControlCenter = true, settings = cf)
            }
            CLASSIC_CC_SURFACE_SOLID -> {
                val bg = if (isDark) settings.panelDarkBgColor else settings.panelLightBgColor
                !isLightColor(bg)
            }
            else -> isDark
        }
    }

    // -------------------------------------------------------------------------
    // Preserve Notification Soft Glass & Watch System Material Transitions
    // -------------------------------------------------------------------------

    private fun hookNotificationMaterialSoftGlass(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 5 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:notif-soft-glass-invoke")
                .intercept { chain ->
                    val res = if (!shouldPreserveNotificationSoftGlass()) {
                        chain.proceed()
                    } else {
                        chain.proceed(
                            arrayOf(
                                chain.getArg(0),
                                chain.getArg(1),
                                chain.getArg(2),
                                java.lang.Boolean.TRUE,
                                chain.getArg(4),
                            ),
                        )
                    }
                    notifySystemMaterialIfChanged((res as? Enum<*>)?.name ?: res?.toString())
                    res
                }
        }
        clazz.declaredMethods.firstOrNull { it.name == "invokeSuspend" && it.parameterCount == 1 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:notif-soft-glass-suspend")
                .intercept { chain ->
                    if (shouldPreserveNotificationSoftGlass()) {
                        chain.thisObject?.let { setBooleanField(it, "Z\$2", true) }
                    }
                    val res = chain.proceed()
                    notifySystemMaterialIfChanged((res as? Enum<*>)?.name ?: res?.toString())
                    res
                }
        }
    }

    private fun notifySystemMaterialIfChanged(materialName: String?) {
        if (materialName == null || materialName == lastObservedSystemMaterial) return
        lastObservedSystemMaterial = materialName
        systemMaterialVersion++
        if (isEnabled()) {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, 60L)
        }
    }

    private fun hookShadeBionicsSoftGlass(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 4 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:shade-bionics-invoke")
                .intercept { chain ->
                    if (!shouldPreserveNotificationSoftGlass()) return@intercept chain.proceed()
                    chain.proceed(
                        arrayOf(
                            chain.getArg(0),
                            java.lang.Boolean.TRUE,
                            chain.getArg(2),
                            chain.getArg(3),
                        ),
                    )
                }
        }
        clazz.declaredMethods.firstOrNull { it.name == "invokeSuspend" && it.parameterCount == 1 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:shade-bionics-suspend")
                .intercept { chain ->
                    if (shouldPreserveNotificationSoftGlass()) {
                        chain.thisObject?.let { setBooleanField(it, "Z\$1", true) }
                    }
                    chain.proceed()
                }
        }
    }

    // -------------------------------------------------------------------------
    // Classic QS Hooks
    // -------------------------------------------------------------------------

    private fun hookQsContainer(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "addQSContent" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-add-content")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { container ->
                        ensureSystemMaterialObserver(container.context)
                        synchronized(qsContainers) { qsContainers.add(container) }
                        if (isEnabled()) applyQsContainerStyling(container, currentSettings(), force = true)
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "updateResources" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-update-resources")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { container ->
                        ensureSystemMaterialObserver(container.context)
                        synchronized(qsContainers) { qsContainers.add(container) }
                        if (isEnabled()) applyQsContainerStyling(container, currentSettings(), force = true)
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "onConfigurationChanged" && it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-config")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { container ->
                        ensureSystemMaterialObserver(container.context)
                        synchronized(qsContainers) { qsContainers.add(container) }
                        if (isEnabled()) {
                            container.post { applyQsContainerStyling(container, currentSettings(), force = true) }
                        }
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "setExpansion" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-set-expansion")
                .intercept { chain ->
                    val result = chain.proceed()
                    val expansion = (chain.getArg(0) as? Float) ?: 0f
                    (chain.thisObject as? ViewGroup)?.let { container ->
                        ensureSystemMaterialObserver(container.context)
                        synchronized(qsContainers) { qsContainers.add(container) }
                        if (isEnabled()) {
                            val settings = currentSettings()
                            ensureContainerStyledForCurrentVersion(container, settings)
                            applyContainerExpansion(container, expansion, settings)
                        }
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "updateExpansion" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-update-expansion")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { container ->
                        ensureSystemMaterialObserver(container.context)
                        synchronized(qsContainers) { qsContainers.add(container) }
                        if (isEnabled()) {
                            val settings = currentSettings()
                            ensureContainerStyledForCurrentVersion(container, settings)
                            adjustPanelBackgroundBounds(container, settings)
                        }
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "updateBackgroundBottom" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-bg-bottom")
                .intercept { chain ->
                    val container = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
                    ensureSystemMaterialObserver(container.context)
                    synchronized(qsContainers) { qsContainers.add(container) }
                    val settings = currentSettings()
                    if (!isEnabled()) return@intercept chain.proceed()
                    val footerBundle = readField(container, "footerBundle") as? ViewGroup ?: return@intercept chain.proceed()
                    val bg = readField(container, "qsBackground") as? View
                    val origBottom = (chain.getArg(0) as? Int) ?: return@intercept chain.proceed()
                    val animate = (chain.getArg(1) as? Boolean) ?: false
                    val gapPx = dp(container.context, settings.panelNotificationGapDp)
                    val targetBottom = if (footerBundle.height > 0) {
                        val minBottom = (bg?.top ?: 0) + dp(container.context, 48)
                        (footerBundle.translationY + footerBundle.height - gapPx).roundToInt().coerceAtLeast(minBottom)
                    } else {
                        (origBottom - gapPx).coerceAtLeast(0)
                    }
                    val result = chain.proceed(arrayOf<Any?>(targetBottom, animate))
                    if (bg != null) updateGlassSdfSizeIfNeeded(bg)
                    result
                }
        }
        clazz.declaredMethods.firstOrNull { it.name == "getMinHeight" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-min-height")
                .intercept { chain ->
                    val base = (chain.proceed() as? Int) ?: return@intercept 0
                    val container = chain.thisObject as? ViewGroup ?: return@intercept base
                    if (!isEnabled()) return@intercept base
                    val settings = currentSettings()
                    if (settings.brightnessSliderVisible && settings.brightnessSliderPosition == CLASSIC_CC_BRIGHTNESS_POS_TOP) {
                        val topSlot = container.findViewWithTag<View>(TAG_TOP_BRIGHTNESS_SLOT)
                        val extra = topSlot?.takeIf { it.visibility != View.GONE }?.let {
                            val h = if (it.measuredHeight > 0) it.measuredHeight else dp(container.context, settings.brightnessHeightDp)
                            h + dp(container.context, settings.brightnessTopMarginDp + settings.brightnessBottomMarginDp)
                        } ?: 0
                        base + extra
                    } else {
                        base
                    }
                }
        }
    }

    private fun hookQsContainerController(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "updateHeader\$1" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:container-controller-header")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject ?: return@intercept result
                    synchronized(containerControllers) { containerControllers.add(controller) }
                    if (!isEnabled()) return@intercept result
                    applyHeaderHeightOffset(controller, currentSettings())
                    result
                }
        }
    }

    private fun hookQsPanel(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:qs-panel-attach")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { panel ->
                        synchronized(qsPanels) { qsPanels.add(panel) }
                    }
                    result
                }
        }
    }

    private fun hookQuickQsPanel(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "updateResources\$1" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:qqs-update-resources")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { panel ->
                        synchronized(quickQsPanels) { quickQsPanels.add(panel) }
                        if (isEnabled()) {
                            invokeIntMethod(panel, "setMaxTiles", currentSettings().qqsTileCount)
                        }
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "setMaxTiles" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:qqs-set-max-tiles")
                .intercept { chain ->
                    (chain.thisObject as? ViewGroup)?.let { panel ->
                        synchronized(quickQsPanels) { quickQsPanels.add(panel) }
                    }
                    if (!isEnabled()) return@intercept chain.proceed()
                    chain.proceed(arrayOf(currentSettings().qqsTileCount))
                }
        }
    }

    private fun hookTileLayout(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "updateResources" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:tile-layout-resources")
                .intercept { chain ->
                    val result = chain.proceed()
                    val layout = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(tileLayouts) { tileLayouts.add(layout) }
                    if (!isEnabled()) return@intercept result
                    val changed = applyTileLayoutResources(layout, currentSettings())
                    if (result == true || changed) true else false
                }
        }
        runCatching {
            val headerTileLayout = clazz.classLoader?.loadClass("$CLS_QUICK_QS_PANEL\$HeaderTileLayout")
            headerTileLayout?.declaredMethods?.firstOrNull { it.name == "updateResources" && it.parameterCount == 0 }?.let { method ->
                module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("classic-cc:qqs-header-tile-layout-resources")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val layout = chain.thisObject as? ViewGroup ?: return@intercept result
                        synchronized(tileLayouts) { tileLayouts.add(layout) }
                        if (!isEnabled()) return@intercept result
                        applyHeaderTileLayoutResources(layout, currentSettings())
                        result
                    }
            }
        }
    }

    private fun hookTileBaseView(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:tile-base-attach")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { view ->
                        synchronized(tileViews) { tileViews.add(view) }
                        if (isEnabled()) applyTileViewLayout(view, currentSettings(), stateObj = null, forceIcon = false)
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "onConfigurationChanged" && it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:tile-base-config")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { view ->
                        if (isEnabled()) applyTileViewLayout(view, currentSettings(), stateObj = null, forceIcon = false)
                    }
                    result
                }
        }
    }

    private fun hookTileView(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "handleStateChanged" && it.parameterCount == 1 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:tile-view-state")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(tileViews) { tileViews.add(view) }
                    if (isEnabled()) {
                        applyTileViewLayout(view, currentSettings(), stateObj = chain.getArg(0), forceIcon = false)
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull { it.name == "updateResources" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:tile-view-resources")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(tileViews) { tileViews.add(view) }
                    if (isEnabled()) {
                        applyTileViewLayout(view, currentSettings(), stateObj = null, forceIcon = false)
                    }
                    result
                }
        }
    }

    private fun hookIconView(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "updateIcon" && it.parameterCount == 4 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:icon-view-update")
                .intercept { chain ->
                    val result = chain.proceed()
                    val iconView = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(iconViews) { iconViews.add(iconView) }
                    if (!isEnabled()) return@intercept result
                    val imageView = chain.getArg(0) as? ImageView ?: return@intercept result
                    val state = chain.getArg(1) ?: readField(iconView, "mState") ?: return@intercept result
                    applyModernIconDrawable(iconView, imageView, state, currentSettings())
                    result
                }
        }
        clazz.declaredMethods.firstOrNull { it.name == "updateResources" && it.parameterCount == 0 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:icon-view-resources")
                .intercept { chain ->
                    val result = chain.proceed()
                    val iconView = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(iconViews) { iconViews.add(iconView) }
                    if (!isEnabled()) return@intercept result
                    val settings = currentSettings()
                    applyIconViewDimensions(iconView, settings)
                    val imageView = readField(iconView, "mIcon") as? ImageView
                    val state = readField(iconView, "mState")
                    if (imageView != null && state != null) {
                        applyModernIconDrawable(iconView, imageView, state, settings)
                    }
                    result
                }
        }
    }

    private fun hookPageIndicator(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull {
            it.name == "setIndex" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:page-indicator-index")
                .intercept { chain ->
                    val result = chain.proceed()
                    val indicator = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(pageIndicators) { pageIndicators.add(indicator) }
                    if (isEnabled()) {
                        val index = (chain.getArg(0) as? Int) ?: 0
                        applyModernPageIndicator(indicator, index, currentSettings())
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "setNumPages" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:page-indicator-count")
                .intercept { chain ->
                    val result = chain.proceed()
                    val indicator = chain.thisObject as? ViewGroup ?: return@intercept result
                    synchronized(pageIndicators) { pageIndicators.add(indicator) }
                    if (isEnabled()) {
                        val pos = (readField(indicator, "mPosition") as? Int)?.coerceAtLeast(0) ?: 0
                        applyModernPageIndicator(indicator, pos, currentSettings())
                    }
                    result
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "onMeasure" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:page-indicator-measure")
                .intercept { chain ->
                    val indicator = chain.thisObject as? ViewGroup
                    val settings = currentSettings()
                    if (indicator == null || !isEnabled() || settings.pageIndicatorStyle != CLASSIC_CC_INDICATOR_PILL) {
                        return@intercept chain.proceed()
                    }
                    val count = indicator.childCount
                    if (count <= 0) return@intercept chain.proceed()
                    val dotSize = dp(indicator.context, 6)
                    val activeWidth = dp(indicator.context, 18)
                    val space = dp(indicator.context, 6)
                    val activeIndex = (readField(indicator, "mPosition") as? Int)?.coerceIn(0, count - 1) ?: 0
                    var totalWidth = 0
                    for (i in 0 until count) {
                        val w = if (i == activeIndex) activeWidth else dotSize
                        indicator.getChildAt(i).measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dotSize, View.MeasureSpec.EXACTLY),
                        )
                        totalWidth += w
                        if (i > 0) totalWidth += space
                    }
                    invokeSetMeasuredDimension(indicator, totalWidth, dotSize)
                    null
                }
        }
        clazz.declaredMethods.firstOrNull {
            it.name == "onLayout" && it.parameterCount == 5
        }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:page-indicator-layout")
                .intercept { chain ->
                    val indicator = chain.thisObject as? ViewGroup
                    val settings = currentSettings()
                    if (indicator == null || !isEnabled() || settings.pageIndicatorStyle != CLASSIC_CC_INDICATOR_PILL) {
                        return@intercept chain.proceed()
                    }
                    val count = indicator.childCount
                    if (count <= 0) return@intercept null
                    val space = dp(indicator.context, 6)
                    var left = 0
                    for (i in 0 until count) {
                        val child = indicator.getChildAt(i)
                        val w = child.measuredWidth
                        val h = child.measuredHeight
                        child.layout(left, 0, left + w, h)
                        left += w + space
                    }
                    null
                }
        }
    }

    private fun hookQsHeaderView(clazz: Class<*>) {
        listOf("onFinishInflate", "onAttachedToWindow", "updateResources\$17", "updateLayout", "updateCarrierVisibility", "updateShortCutVisibility")
            .forEach { methodName ->
                clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.let { method ->
                    module.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("classic-cc:header-$methodName")
                        .intercept { chain ->
                            val result = chain.proceed()
                            (chain.thisObject as? ViewGroup)?.let { header ->
                                synchronized(headerViews) { headerViews.add(header) }
                                if (isEnabled()) applyModernHeader(header, currentSettings())
                            }
                            result
                        }
                }
            }
    }

    private fun hookBrightnessSliderView(clazz: Class<*>) {
        clazz.declaredMethods.firstOrNull { it.name == "setValue" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            ?.let { method ->
                module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("classic-cc:brightness-set-value")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? ViewGroup)?.let { sliderView ->
                            if (isEnabled()) updateBrightnessPercentLabel(sliderView, currentSettings())
                        }
                        result
                    }
            }
    }

    private fun hookBrightnessMirrorController(clazz: Class<*>) {
        listOf("onUiModeChanged", "updateResources\$1").forEach { name ->
            clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { method ->
                module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("classic-cc:brightness-mirror-$name")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val controller = chain.thisObject ?: return@intercept result
                        synchronized(brightnessMirrors) { brightnessMirrors.add(controller) }
                        if (isEnabled()) {
                            mainHandler.post { styleBrightnessMirror(controller, currentSettings()) }
                        }
                        result
                    }
            }
        }
        clazz.declaredMethods.firstOrNull { it.name == "setLocationAndSize" && it.parameterCount == 1 }?.let { method ->
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("classic-cc:brightness-mirror-location")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject ?: return@intercept result
                    synchronized(brightnessMirrors) { brightnessMirrors.add(controller) }
                    if (isEnabled()) {
                        styleBrightnessMirror(controller, currentSettings())
                    }
                    result
                }
        }
    }

    // -------------------------------------------------------------------------
    // Panel, Native Soft Glass & Clear Frost Material, Gap, Sliders & Footer
    // -------------------------------------------------------------------------

    private fun applyQsContainerStyling(container: ViewGroup, settings: ClassicControlCenterSettings, force: Boolean = false) {
        val contentAdded = (readField(container, "contentAdded") as? Boolean) ?: false
        if (!contentAdded) return

        val ctx = container.context
        val isDark = isDarkMode(ctx)
        val useLightFg = useLightForegroundOnPanel(settings, isDark, container)
        val sideMarginPx = dp(ctx, settings.panelSideMarginDp)
        val hPaddingPx = dp(ctx, settings.panelHorizontalPaddingDp)

        setIntField(container, "sideMargins", sideMarginPx)
        setIntField(container, "contentPaddingStart", hPaddingPx)
        setIntField(container, "contentPaddingEnd", hPaddingPx)

        val header = readField(container, "header") as? View
        val qsContent = readField(container, "qsContent") as? ViewGroup
        val qsCustomizer = readField(container, "qsCustomizer") as? ViewGroup
        val qsDetail = readField(container, "qsDetail") as? ViewGroup
        val qsBackground = readField(container, "qsBackground") as? View
        val qsPanelScrollView = readField(container, "qsPanelScrollView") as? View
        val quickQSPanel = readField(container, "quickQSPanel") as? View
        val footerBundle = readField(container, "footerBundle") as? ViewGroup
        val brightnessView = readField(container, "brightnessView") as? ViewGroup
        val dataUsage = readField(container, "dataUsage") as? ViewGroup
        val dragHandle = readField(container, "dragHandle") as? ImageView

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child != header && child != qsCustomizer && child != qsDetail) {
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin != sideMarginPx || lp.rightMargin != sideMarginPx) {
                        lp.leftMargin = sideMarginPx
                        lp.rightMargin = sideMarginPx
                        child.layoutParams = lp
                    }
                }
                if (child != qsContent && child != header) {
                    if (child.paddingStart != hPaddingPx || child.paddingEnd != hPaddingPx) {
                        child.setPaddingRelative(hPaddingPx, child.paddingTop, hPaddingPx, child.paddingBottom)
                    }
                }
            }
        }
        qsDetail?.let { invokeTwoIntMethod(it, "setMargins", sideMarginPx, sideMarginPx) }
        qsCustomizer?.let { invokeTwoIntMethod(it, "setMargins", sideMarginPx, sideMarginPx) }

        if (qsBackground != null) {
            if (qsContent != null && qsBackground.parent === qsContent && qsContent.indexOfChild(qsBackground) != 0) {
                qsContent.removeView(qsBackground)
                qsContent.addView(qsBackground, 0)
            }
            qsBackground.elevation = 0f
            qsBackground.translationZ = 0f
            applyPanelSurfaceMaterial(qsBackground, settings, isDark, isPrimaryPanel = true, force = force)
        }

        if (brightnessView != null && footerBundle != null && qsContent != null) {
            val headerHeight = header?.layoutParams?.height?.takeIf { it > 0 } ?: header?.height ?: 0
            applyBrightnessPlacementAndStyle(
                container = container,
                qsContent = qsContent,
                footerBundle = footerBundle,
                brightnessView = brightnessView,
                qsPanelScrollView = qsPanelScrollView,
                quickQSPanel = quickQSPanel,
                headerHeight = headerHeight,
                settings = settings,
                isDark = isDark,
                useLightFg = useLightFg,
            )
        }

        if (footerBundle != null) {
            applyVolumeSliderRow(footerBundle, settings, isDark, useLightFg)
            val desiredBottomPad = dp(ctx, settings.panelBottomPaddingDp + settings.panelNotificationGapDp)
            if (footerBundle.paddingBottom != desiredBottomPad) {
                footerBundle.setPadding(
                    footerBundle.paddingLeft,
                    footerBundle.paddingTop,
                    footerBundle.paddingRight,
                    desiredBottomPad,
                )
            }
        }

        dataUsage?.let { applyDataUsageStyling(it, settings, useLightFg) }
        dragHandle?.let { applyDragHandleStyling(container, it, settings, useLightFg) }

        if (settings.modernizeDetailAndCustomizer) {
            qsDetail?.let { applyDetailModernization(it, settings, isDark, useLightFg, force) }
            qsCustomizer?.let { applyCustomizerModernization(it, settings, isDark, useLightFg, force) }
        }

        val currentExpansion = (readField(container, "qsExpansion") as? Float)?.takeUnless { it.isNaN() } ?: 0f
        applyContainerExpansion(container, currentExpansion, settings)
        adjustPanelBackgroundBounds(container, settings)
        container.requestLayout()
    }

    private fun ensureContainerStyledForCurrentVersion(container: ViewGroup, settings: ClassicControlCenterSettings) {
        val bg = readField(container, "qsBackground") as? View ?: return
        val isDark = isDarkMode(container.context)
        val effectiveMode = resolveEffectiveSurfaceMode(settings, bg)
        val expectedSig = computeBgSignature(currentVersion(), isDark, isPrimaryPanel = true, effectiveMode = effectiveMode)
        if (bg.getTag(TAG_BG_SIG_KEY) != expectedSig) {
            applyQsContainerStyling(container, settings, force = true)
        }
    }

    /**
     * Applies native HyperOS 4 element-only material (Follow System / Soft Glass / Clear Frost / Solid / Default).
     * Never installs container blur flags or RenderNode-elevating shadows on qsBackground so QS tiles are never blurred.
     */
    private fun applyPanelSurfaceMaterial(
        view: View,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        isPrimaryPanel: Boolean,
        force: Boolean = false,
    ) {
        view.setTag(CLASSIC_CC_MANAGED_SURFACE_TAG_KEY, true)
        val effectiveMode = resolveEffectiveSurfaceMode(settings, view)
        val sig = computeBgSignature(currentVersion(), isDark, isPrimaryPanel, effectiveMode)
        if (!force && view.getTag(TAG_BG_SIG_KEY) == sig) return
        view.setTag(TAG_BG_SIG_KEY, sig)

        val ctx = view.context
        val radiusPx = dp(ctx, settings.panelCornerRadiusDp).toFloat()

        clearContainerBlurProviderFlags(view)
        // Always clear MiShadow on background sibling views because View.setMiShadow calls
        // mRenderNode.setElevation(radius), lifting the blur background above foreground tiles!
        clearMiShadow(view)

        when (effectiveMode) {
            CLASSIC_CC_SURFACE_DEFAULT -> {
                clearElementMaterial(view)
                view.clipToOutline = false
                view.elevation = 0f
                return
            }

            CLASSIC_CC_SURFACE_SOLID -> {
                clearElementMaterial(view)
                val baseColor = if (isDark) settings.panelDarkBgColor else settings.panelLightBgColor
                val opacityAlpha = (settings.panelOpacity * 255 / 100).coerceIn(0, 255)
                val combinedAlpha = ((Color.alpha(baseColor) * opacityAlpha) / 255).coerceIn(0, 255)
                val tintedColor = Color.argb(combinedAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(tintedColor)
                    if (settings.panelStrokeEnabled && settings.panelStrokeWidthDp > 0f && settings.panelStrokeAlpha > 0) {
                        val strokeAlpha = (settings.panelStrokeAlpha * 255 / 100).coerceIn(0, 255)
                        setStroke(dpFloat(ctx, settings.panelStrokeWidthDp).roundToInt().coerceAtLeast(1), Color.argb(strokeAlpha, 255, 255, 255))
                    }
                }
                installRoundedOutline(view, radiusPx)
                view.clipToOutline = true
                setBlurOutlineEnable(view, false)
                setGlassOutlineEnable(view, false)
            }

            CLASSIC_CC_SURFACE_CLEAR_FROST -> {
                val cf = prefsRef?.let { resolveClassicControlCenterClearFrost(it, settings) }
                    ?: settings.clearFrost.copy(enabled = true).normalized()
                clearMiGlass(view)
                view.background = createTransparentRoundedDrawable(radiusPx)
                installRoundedOutline(view, radiusPx)
                view.clipToOutline = false
                setGlassOutlineEnable(view, false)
                setBlurOutlineEnable(view, true)

                val isLight = resolveClearFrostIsLight(isDark, isControlCenter = true, settings = cf)
                val applied = runCatching {
                    invokeMethod(viewSetMiViewBlurMode, view, 1)
                    val blendPoints = resolveClearFrostElementBlendPoints(cf, isLight = isLight, isControlCenter = true)
                        ?: readNativeNotificationShadeBlendPoints(ctx)?.let { scaleBlendPointsAlpha(it, cf.elementOpacity) }
                        ?: resolveClearFrostElementBlendPoints(
                            cf.copy(
                                separateScopes = false,
                                lightStyle = CLEAR_FROST_DEFAULT_LIGHT_STYLE,
                                darkStyle = CLEAR_FROST_DEFAULT_DARK_STYLE,
                            ),
                            isLight = isLight,
                            isControlCenter = true,
                        )
                    applyElementBlendPoints(view, blendPoints)
                    invokeMethod(viewSetMixEffectEnabled, view, true)
                    val stroke = buildClearFrostBloomStrokePx(cf, isLight = isLight, density = ctx.resources.displayMetrics.density)
                    invokeMethod(viewSetMiBloomStroke, view, stroke)
                    true
                }.getOrDefault(false)

                if (!applied) {
                    view.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radiusPx
                        setColor(if (isDark) settings.panelDarkBgColor else settings.panelLightBgColor)
                    }
                    view.clipToOutline = true
                }
            }

            CLASSIC_CC_SURFACE_SOFT_GLASS -> {
                val sg = prefsRef?.let { resolveClassicControlCenterSoftGlass(it, settings) }
                    ?: settings.softGlass.copy(enabled = true).normalized()
                view.background = createTransparentRoundedDrawable(radiusPx)
                installRoundedOutline(view, radiusPx)
                view.clipToOutline = false
                setBlurOutlineEnable(view, false)
                setGlassOutlineEnable(view, true)
                invokeMethod(viewSetMixEffectEnabled, view, false)
                invokeMethod(viewSetMiBloomStroke, view, CLEAR_FROST_EMPTY_STROKE)

                val applied = runCatching {
                    // Match NotificationRowGlassEffect: apply native element blend underlay first,
                    // then apply the native 42-slot notification_glass_params_normal recipe!
                    invokeMethod(viewSetMiViewBlurMode, view, 1)
                    val nativeBlend = readNativeNotificationShadeBlendPoints(ctx)
                        ?.let { scaleBlendPointsAlpha(it, sg.backgroundOpacity) }
                    if (nativeBlend != null && nativeBlend.isNotEmpty()) {
                        applyElementBlendPoints(view, nativeBlend)
                    } else {
                        invokeMethod(viewClearMiBackgroundBlendColor, view)
                        invokeMethod(viewAddMiBackgroundBlendColor, view, Color.argb(1, 255, 255, 255), 101)
                    }
                    applyNativeSoftGlass(view, sg)
                    ensureSdfLayoutListener(view)
                    true
                }.getOrDefault(false)

                if (!applied) {
                    view.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radiusPx
                        setColor(if (isDark) settings.panelDarkBgColor else settings.panelLightBgColor)
                    }
                    view.clipToOutline = true
                }
            }
        }

        view.elevation = 0f
        view.translationZ = 0f
        view.invalidateOutline()
        view.invalidate()
    }

    private fun createTransparentRoundedDrawable(radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.TRANSPARENT)
        }

    private fun scaleBlendPointsAlpha(points: ArrayList<Point>, percent: Int): ArrayList<Point> {
        if (percent == 100) return points
        return ArrayList<Point>(points.size).apply {
            points.forEach { add(Point(softGlassColorAlpha(it.x, percent), it.y)) }
        }
    }

    private fun readNativeNotificationShadeBlendPoints(ctx: Context): ArrayList<Point>? = runCatching {
        val res = ctx.resources
        val c1Id = res.getIdentifier("notification_element_blend_shade_color_1", "color", "com.android.systemui")
        val m1Id = res.getIdentifier("notification_element_blend_shade_mode_1", "integer", "com.android.systemui")
        val c2Id = res.getIdentifier("notification_element_blend_shade_color_2", "color", "com.android.systemui")
        val m2Id = res.getIdentifier("notification_element_blend_shade_mode_2", "integer", "com.android.systemui")
        if (c1Id == 0 || m1Id == 0 || c2Id == 0 || m2Id == 0) return@runCatching null
        arrayListOf(
            Point(res.getColor(c1Id, ctx.theme), res.getInteger(m1Id)),
            Point(res.getColor(c2Id, ctx.theme), res.getInteger(m2Id)),
        )
    }.getOrNull()

    private fun readNativeNotificationGlassRecipe(ctx: Context): FloatArray {
        cachedNativeGlassRecipe?.let { return it }
        val loaded = runCatching {
            val res = ctx.resources
            val id = res.getIdentifier("notification_glass_params_normal", "array", "com.android.systemui")
            if (id == 0) return@runCatching null
            res.getStringArray(id)
                .map { it.toFloatOrNull() ?: return@runCatching null }
                .toFloatArray()
                .takeIf { it.size >= 42 }
        }.getOrNull()
        if (loaded != null) {
            cachedNativeGlassRecipe = loaded
            return loaded
        }
        return CLASSIC_CC_SOFT_GLASS_BASELINE
    }

    private fun clearContainerBlurProviderFlags(view: View) {
        invokeMethod(viewSetPassWindowBlur, view, false)
        invokeMethod(viewSetMiBackgroundBlurMode, view, 0)
        invokeMethod(viewSetMiBackgroundBlurRadius, view, 0)
        invokeMethod(viewSetMiBackgroundBlurScaleRatio, view, 0f)
        invokeMethod(viewChooseBackgroundBlurContainer, view, null)
    }

    private fun clearElementMaterial(view: View) {
        clearMiGlass(view)
        invokeMethod(viewSetMiViewBlurMode, view, 0)
        invokeMethod(viewClearMiBackgroundBlendColor, view)
        invokeMethod(viewSetMixEffectEnabled, view, false)
        invokeMethod(viewSetMiBloomStroke, view, CLEAR_FROST_EMPTY_STROKE)
        setBlurOutlineEnable(view, false)
        setGlassOutlineEnable(view, false)
    }

    private fun clearMiGlass(view: View) {
        val glassCompat = resolveMiGlassCompat(view)
        if (glassCompat != null) {
            runCatching {
                glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
                    .invoke(null, view, FloatArray(42))
            }
            runCatching {
                glassCompat.getMethod("setMiViewMaterialTypeCompat", Int::class.javaPrimitiveType, View::class.java)
                    .invoke(null, 0, view)
            }
        } else {
            invokeMethod(viewSetMiGlass, view, FloatArray(42))
            invokeMethod(viewSetMiViewMaterialType, view, 0)
        }
    }

    private fun applyNativeSoftGlass(view: View, sg: SoftGlassSettings) {
        val baseRecipe = readNativeNotificationGlassRecipe(view.context)
        val tuned = tuneSoftGlass(baseRecipe, sg, standaloneBackground = true)
        val glassCompat = resolveMiGlassCompat(view)
        if (glassCompat != null) {
            glassCompat.getMethod(
                "setMiGlassCompat",
                View::class.java,
                FloatArray::class.java,
            ).invoke(null, view, tuned)
            glassCompat.getMethod(
                "setMiViewMaterialTypeCompat",
                Int::class.javaPrimitiveType,
                View::class.java,
            ).invoke(null, 1, view)
        } else {
            invokeMethod(viewSetMiGlass, view, tuned)
            invokeMethod(viewSetMiViewMaterialType, view, 1)
        }
        updateGlassSdfSizeIfNeeded(view, force = true)
    }

    private fun ensureSdfLayoutListener(view: View) {
        if (view.getTag(TAG_SDF_LISTENER_KEY) == true) return
        view.setTag(TAG_SDF_LISTENER_KEY, true)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (isEnabled() && resolveEffectiveSurfaceMode(currentSettings(), v) == CLASSIC_CC_SURFACE_SOFT_GLASS) {
                updateGlassSdfSizeIfNeeded(v)
            }
        }
    }

    private fun updateGlassSdfSizeIfNeeded(view: View, force: Boolean = false) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val packed = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
        if (!force && view.getTag(TAG_SDF_SIZE_KEY) == packed) return
        view.setTag(TAG_SDF_SIZE_KEY, packed)
        val glassCompat = resolveMiGlassCompat(view)
        if (glassCompat != null) {
            runCatching {
                glassCompat.getMethod(
                    "setMiGlassSdfMaxSizeCompat",
                    View::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                ).invoke(null, view, w.toFloat(), h.toFloat())
            }
        } else {
            invokeMethod(viewSetMiGlassSdfMaxSize, view, w.toFloat(), h.toFloat())
        }
        view.invalidateOutline()
    }

    private fun applyElementBlendPoints(view: View, points: ArrayList<Point>?) {
        invokeMethod(viewClearMiBackgroundBlendColor, view)
        if (points == null || points.isEmpty()) return
        // Match NotificationUtil.applyElementViewBlend / MiBlurCompat.setMiBackgroundBlendColors:
        // clear first, then add each (color, mode) layer explicitly.
        points.forEach { pt ->
            invokeMethod(viewAddMiBackgroundBlendColor, view, pt.x, pt.y)
        }
    }

    private fun setBlurOutlineEnable(view: View, enabled: Boolean) {
        val compat = resolveMiBlurCompat(view)
        if (compat != null) {
            runCatching {
                compat.getMethod("setBlurOutlineEnable", View::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, view, enabled)
                return
            }
        }
        invokeMethod(viewSetMiBackgroundBlurEnhanceFlag, view, if (enabled) 4096 else 0, 4096)
    }

    private fun setGlassOutlineEnable(view: View, enabled: Boolean) {
        val compat = resolveMiBlurCompat(view)
        if (compat != null) {
            runCatching {
                compat.getMethod("setGlassOutlineEnable", View::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, view, enabled)
                return
            }
        }
        invokeMethod(viewSetMiBackgroundBlurEnhanceFlag, view, if (enabled) 8192 else 0, 8192)
    }

    private fun installRoundedOutline(view: View, radiusPx: Float) {
        val prevRadius = view.getTag(TAG_OUTLINE_RADIUS_KEY) as? Float
        if (prevRadius != null && prevRadius == radiusPx && view.outlineProvider != null) return
        view.setTag(TAG_OUTLINE_RADIUS_KEY, radiusPx)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val r = (v.getTag(TAG_OUTLINE_RADIUS_KEY) as? Float) ?: radiusPx
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
    }

    private fun applyMiShadow(view: View, color: Int, offsetY: Float, radius: Float) {
        invokeMethod(viewSetMiShadow, view, color, 0f, offsetY, radius, 1.0f)
    }

    private fun clearMiShadow(view: View) {
        invokeMethod(viewSetMiShadow, view, 0, 0f, 0f, 0f, 0f)
    }

    private fun resolveMiGlassCompat(view: View): Class<*>? {
        miGlassCompatClass?.let { return it }
        val candidates = listOfNotNull(systemUiClassLoader, view.context.classLoader)
        for (loader in candidates) {
            val cls = runCatching { Class.forName(CLS_MI_GLASS_COMPAT, false, loader) }.getOrNull()
            if (cls != null) {
                miGlassCompatClass = cls
                return cls
            }
        }
        return null
    }

    private fun resolveMiBlurCompat(view: View): Class<*>? {
        miBlurCompatClass?.let { return it }
        val candidates = listOfNotNull(systemUiClassLoader, view.context.classLoader)
        for (loader in candidates) {
            val cls = runCatching { Class.forName(CLS_MI_BLUR_COMPAT, false, loader) }.getOrNull()
            if (cls != null) {
                miBlurCompatClass = cls
                return cls
            }
        }
        return null
    }

    private fun applyBrightnessPlacementAndStyle(
        container: ViewGroup,
        qsContent: ViewGroup,
        footerBundle: ViewGroup,
        brightnessView: ViewGroup,
        qsPanelScrollView: View?,
        quickQSPanel: View?,
        headerHeight: Int,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        useLightFg: Boolean,
    ) {
        val ctx = container.context
        val hMargin = dp(ctx, settings.brightnessHorizontalMarginDp)
        val topMargin = dp(ctx, settings.brightnessTopMarginDp)
        val bottomMargin = dp(ctx, settings.brightnessBottomMarginDp)
        val sliderHeight = dp(ctx, settings.brightnessHeightDp)
        val qsDisabled = (readField(container, "qsDisabled") as? Boolean) ?: false

        var topSlot = qsContent.findViewWithTag<FrameLayout>(TAG_TOP_BRIGHTNESS_SLOT)
        if (settings.brightnessSliderPosition == CLASSIC_CC_BRIGHTNESS_POS_TOP) {
            if (topSlot == null) {
                topSlot = FrameLayout(ctx).apply {
                    tag = TAG_TOP_BRIGHTNESS_SLOT
                    elevation = dp(ctx, 5).toFloat()
                    clipChildren = false
                    clipToPadding = false
                }
                qsContent.addView(
                    topSlot,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            if (brightnessView.parent !== topSlot) {
                (brightnessView.parent as? ViewGroup)?.removeView(brightnessView)
                topSlot.addView(
                    brightnessView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            val visible = settings.brightnessSliderVisible && !qsDisabled
            topSlot.visibility = if (visible) View.VISIBLE else View.GONE
            (topSlot.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != headerHeight + topMargin || lp.leftMargin != hMargin || lp.rightMargin != hMargin) {
                    lp.topMargin = headerHeight + topMargin
                    lp.leftMargin = hMargin
                    lp.rightMargin = hMargin
                    topSlot.layoutParams = lp
                }
            }
            val offset = if (visible) sliderHeight + topMargin + bottomMargin else 0
            (qsPanelScrollView?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != headerHeight + offset) {
                    lp.topMargin = headerHeight + offset
                    qsPanelScrollView.layoutParams = lp
                }
            }
            (quickQSPanel?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != headerHeight + offset) {
                    lp.topMargin = headerHeight + offset
                    quickQSPanel.layoutParams = lp
                }
            }
        } else {
            if (brightnessView.parent !== footerBundle) {
                (brightnessView.parent as? ViewGroup)?.removeView(brightnessView)
                val dragHandle = readField(container, "dragHandle") as? View
                val insertIndex = dragHandle?.let { footerBundle.indexOfChild(it) }?.takeIf { it >= 0 } ?: footerBundle.childCount
                footerBundle.addView(
                    brightnessView,
                    insertIndex,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            topSlot?.visibility = View.GONE
            (brightnessView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != hMargin || lp.rightMargin != hMargin || lp.topMargin != topMargin || lp.bottomMargin != bottomMargin) {
                    lp.leftMargin = hMargin
                    lp.rightMargin = hMargin
                    lp.topMargin = topMargin
                    lp.bottomMargin = bottomMargin
                    brightnessView.layoutParams = lp
                }
            }
            (qsPanelScrollView?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != headerHeight) {
                    lp.topMargin = headerHeight
                    qsPanelScrollView.layoutParams = lp
                }
            }
            (quickQSPanel?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != headerHeight) {
                    lp.topMargin = headerHeight
                    quickQSPanel.layoutParams = lp
                }
            }
        }

        val baseVisible = settings.brightnessSliderVisible && !qsDisabled
        if (!settings.brightnessExpandedOnly) {
            brightnessView.visibility = if (baseVisible) View.VISIBLE else View.GONE
        }
        styleBrightnessSlider(brightnessView, settings, isDark, useLightFg, isMirror = false)
    }

    private fun adjustPanelBackgroundBounds(container: ViewGroup, settings: ClassicControlCenterSettings) {
        val bg = readField(container, "qsBackground") as? View ?: return
        val header = readField(container, "header") as? View
        var boundsChanged = false

        if (settings.brightnessSliderVisible && settings.brightnessSliderPosition == CLASSIC_CC_BRIGHTNESS_POS_TOP && header != null) {
            val headerHeight = header.layoutParams?.height?.takeIf { it > 0 } ?: header.height
            if (headerHeight > 0 && bg.top > headerHeight) {
                bg.top = headerHeight
                boundsChanged = true
            }
        }

        val footerBundle = readField(container, "footerBundle") as? ViewGroup
        if (footerBundle != null && footerBundle.height > 0) {
            val gapPx = dp(container.context, settings.panelNotificationGapDp)
            val minBottom = bg.top + dp(container.context, 48)
            val targetBottom = (footerBundle.translationY + footerBundle.height - gapPx).roundToInt().coerceAtLeast(minBottom)
            if (bg.bottom != targetBottom) {
                setIntField(container, "backgroundBottom", targetBottom)
                bg.bottom = targetBottom
                boundsChanged = true
            }
        }

        if (boundsChanged && resolveEffectiveSurfaceMode(settings, bg) == CLASSIC_CC_SURFACE_SOFT_GLASS) {
            updateGlassSdfSizeIfNeeded(bg)
        } else if (boundsChanged) {
            bg.invalidateOutline()
        }
    }

    private fun styleBrightnessSlider(
        brightnessView: ViewGroup,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        useLightFg: Boolean,
        isMirror: Boolean,
    ) {
        val ctx = brightnessView.context
        val seekBar = findChildByIdName(brightnessView, "slider") as? SeekBar ?: return
        val sunIcon = findChildByIdName(brightnessView, "icon") as? ImageView
        val innerRel = seekBar.parent as? RelativeLayout ?: return

        val heightPx = dp(ctx, settings.brightnessHeightDp)
        val radiusPx = dp(ctx, settings.brightnessCornerRadiusDp).toFloat().coerceAtMost(heightPx / 2f)
        val fillColor = resolveBrightnessFillColor(ctx, settings, isDark)
        val trackColor = resolveBrightnessTrackColor(settings, useLightFg)
        val iconTint = resolveBrightnessIconColor(settings, fillColor)

        if (!isMirror) {
            var row = brightnessView.findViewWithTag<LinearLayout>(TAG_BRIGHTNESS_ROW)
            if (row == null) {
                row = LinearLayout(ctx).apply {
                    tag = TAG_BRIGHTNESS_ROW
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                if (innerRel.parent === brightnessView) {
                    brightnessView.removeView(innerRel)
                    brightnessView.addView(
                        row,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    row.addView(
                        innerRel,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
            }
            setupAutoBrightnessButton(row, settings, useLightFg, heightPx, radiusPx, fillColor, trackColor)
        }

        val seekLp = seekBar.layoutParams
        if (seekLp == null || seekLp.width != ViewGroup.LayoutParams.MATCH_PARENT || seekLp.height != heightPx) {
            seekBar.layoutParams = (seekLp ?: RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = heightPx
            }
        }
        seekBar.setPadding(0, 0, 0, 0)
        seekBar.thumb = ColorDrawable(Color.TRANSPARENT)
        seekBar.splitTrack = false
        seekBar.progressDrawable = createModernSliderDrawable(trackColor, fillColor, radiusPx, heightPx)
        installRoundedOutline(seekBar, radiusPx)
        seekBar.clipToOutline = true

        sunIcon?.let { icon ->
            val iconSize = (heightPx * 0.44f).roundToInt().coerceIn(dp(ctx, 16), dp(ctx, 22))
            val marginStart = dp(ctx, 13)
            icon.layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
                setMarginStart(marginStart)
            }
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.imageTintList = ColorStateList.valueOf(iconTint)
            icon.elevation = dp(ctx, 2).toFloat()
        }

        var percentView = innerRel.findViewWithTag<TextView>(TAG_BRIGHTNESS_PERCENT)
        if (settings.brightnessShowPercent) {
            if (percentView == null) {
                percentView = TextView(ctx).apply {
                    tag = TAG_BRIGHTNESS_PERCENT
                    isSingleLine = true
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = createWeightedTypeface(600)
                    elevation = dp(ctx, 2).toFloat()
                }
                innerRel.addView(
                    percentView,
                    RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        marginEnd = dp(ctx, 14)
                    },
                )
            }
            percentView.visibility = View.VISIBLE
            updateBrightnessPercentLabel(brightnessView, settings, useLightFg)
        } else {
            percentView?.visibility = View.GONE
        }
    }

    private fun updateBrightnessPercentLabel(
        brightnessView: ViewGroup,
        settings: ClassicControlCenterSettings,
        useLightFg: Boolean = useLightForegroundOnPanel(settings, isDarkMode(brightnessView.context), brightnessView),
    ) {
        if (!settings.brightnessShowPercent) return
        val seekBar = findChildByIdName(brightnessView, "slider") as? SeekBar ?: return
        val label = brightnessView.findViewWithTag<TextView>(TAG_BRIGHTNESS_PERCENT) ?: return
        val max = seekBar.max.coerceAtLeast(1)
        val pct = ((seekBar.progress.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        val text = "$pct%"
        if (label.text != text) label.text = text
        val fillColor = resolveBrightnessFillColor(brightnessView.context, settings, isDarkMode(brightnessView.context))
        val textColor = if (pct >= 84) {
            adjustAlpha(contrastOnColor(fillColor), 0.88f)
        } else if (useLightFg) {
            0xEBFFFFFF.toInt()
        } else {
            0xCC181A1F.toInt()
        }
        if (label.currentTextColor != textColor) label.setTextColor(textColor)
    }

    private fun setupAutoBrightnessButton(
        row: LinearLayout,
        settings: ClassicControlCenterSettings,
        useLightFg: Boolean,
        heightPx: Int,
        radiusPx: Float,
        fillColor: Int,
        trackColor: Int,
    ) {
        val ctx = row.context
        var btn = row.findViewWithTag<ImageView>(TAG_AUTO_BRIGHTNESS_BTN)
        if (!settings.brightnessShowAutoButton) {
            btn?.visibility = View.GONE
            return
        }
        if (btn == null) {
            btn = ImageView(ctx).apply {
                val button = this
                tag = TAG_AUTO_BRIGHTNESS_BTN
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    toggleAutoBrightness(v.context)
                    updateAutoBrightnessButtonAppearance(button, currentSettings())
                }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        registerAutoBrightnessObserver(button)
                        updateAutoBrightnessButtonAppearance(button, currentSettings())
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        unregisterAutoBrightnessObserver(button)
                    }
                })
            }
            row.addView(btn)
            if (btn.isAttachedToWindow) registerAutoBrightnessObserver(btn)
        }
        btn.visibility = View.VISIBLE
        btn.layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
            marginStart = dp(ctx, 10)
        }
        val pad = (heightPx * 0.27f).roundToInt()
        btn.setPadding(pad, pad, pad, pad)
        if (btn.drawable !is VectorPathDrawable) {
            btn.setImageDrawable(VectorPathDrawable(PATH_AUTO_BRIGHTNESS, 24f))
        }
        updateAutoBrightnessButtonAppearance(btn, settings, useLightFg, radiusPx, fillColor, trackColor)
    }

    private fun updateAutoBrightnessButtonAppearance(
        btn: ImageView,
        settings: ClassicControlCenterSettings,
        useLightFg: Boolean = useLightForegroundOnPanel(settings, isDarkMode(btn.context), btn),
        radiusPx: Float = dp(btn.context, settings.brightnessCornerRadiusDp).toFloat(),
        fillColor: Int = resolveBrightnessFillColor(btn.context, settings, isDarkMode(btn.context)),
        trackColor: Int = resolveBrightnessTrackColor(settings, useLightFg),
    ) {
        val autoOn = isAutoBrightnessEnabled(btn.context)
        val bgColor = if (autoOn) fillColor else trackColor
        val iconColor = if (autoOn) {
            contrastOnColor(fillColor)
        } else if (useLightFg) {
            0xEBFFFFFF.toInt()
        } else {
            0xCC1A1C20.toInt()
        }
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(bgColor)
        }
        btn.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun registerAutoBrightnessObserver(view: ImageView) {
        if (autoBrightnessObservers.containsKey(view)) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                if (isEnabled()) updateAutoBrightnessButtonAppearance(view, currentSettings())
            }
        }
        runCatching {
            view.context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false,
                observer,
            )
            autoBrightnessObservers[view] = observer
        }
    }

    private fun unregisterAutoBrightnessObserver(view: View) {
        autoBrightnessObservers.remove(view)?.let { obs ->
            runCatching { view.context.contentResolver.unregisterContentObserver(obs) }
        }
    }

    private fun isAutoBrightnessEnabled(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    private fun toggleAutoBrightness(context: Context) {
        val next = if (isAutoBrightnessEnabled(context)) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, next)
        }
    }

    private fun styleBrightnessMirror(controller: Any, settings: ClassicControlCenterSettings) {
        val mirror = runCatching {
            controller.javaClass.methods.firstOrNull { it.name == "getBrightnessMirror" && it.parameterCount == 0 }
                ?.invoke(controller) as? ViewGroup
        }.getOrNull() ?: return
        val sliderView = findChildByIdName(mirror, "brightness_slider") as? ViewGroup ?: mirror
        val isDark = isDarkMode(mirror.context)
        styleBrightnessSlider(sliderView, settings, isDark, useLightForegroundOnPanel(settings, isDark, mirror), isMirror = true)
    }

    // -------------------------------------------------------------------------
    // Media Volume Slider inside Classic Control Center
    // -------------------------------------------------------------------------

    private fun applyVolumeSliderRow(
        footerBundle: ViewGroup,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        useLightFg: Boolean,
    ) {
        val ctx = footerBundle.context
        var row = footerBundle.findViewWithTag<LinearLayout>(TAG_VOLUME_ROW)
        if (!settings.volumeSliderEnabled) {
            row?.visibility = View.GONE
            return
        }
        val heightPx = dp(ctx, settings.volumeSliderHeightDp)
        val radiusPx = dp(ctx, settings.brightnessCornerRadiusDp).toFloat().coerceAtMost(heightPx / 2f)
        val hMargin = dp(ctx, settings.brightnessHorizontalMarginDp)
        val fillColor = resolveBrightnessFillColor(ctx, settings, isDark)
        val trackColor = resolveBrightnessTrackColor(settings, useLightFg)
        val iconTint = resolveBrightnessIconColor(settings, fillColor)
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (row == null) {
            row = LinearLayout(ctx).apply {
                val volumeRow = this
                tag = TAG_VOLUME_ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                val sliderBox = RelativeLayout(ctx).apply { tag = TAG_VOLUME_SLIDER_BOX }
                val seekBar = SeekBar(ctx).apply {
                    tag = TAG_VOLUME_SEEKBAR
                    thumb = ColorDrawable(Color.TRANSPARENT)
                    splitTrack = false
                    setPadding(0, 0, 0, 0)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                runCatching {
                                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                                }
                            }
                            syncVolumeRowVisuals(volumeRow, currentSettings())
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }
                val leftIcon = ImageView(ctx).apply {
                    tag = TAG_VOLUME_ICON
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    elevation = dp(ctx, 2).toFloat()
                }
                val percentText = TextView(ctx).apply {
                    tag = TAG_VOLUME_PERCENT
                    isSingleLine = true
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = createWeightedTypeface(600)
                    elevation = dp(ctx, 2).toFloat()
                }
                sliderBox.addView(seekBar, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
                sliderBox.addView(leftIcon)
                sliderBox.addView(
                    percentText,
                    RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        marginEnd = dp(ctx, 14)
                    },
                )
                addView(sliderBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val muteBtn = ImageView(ctx).apply {
                    tag = TAG_VOLUME_MUTE_BTN
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        runCatching {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE,
                                0,
                            )
                        }
                        mainHandler.postDelayed({ syncVolumeRowVisuals(volumeRow, currentSettings()) }, 50L)
                    }
                }
                addView(muteBtn)

                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        registerVolumeReceiver(volumeRow)
                        syncVolumeRowVisuals(volumeRow, currentSettings())
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        unregisterVolumeReceiver(volumeRow)
                    }
                })
            }
            val dragHandleIndex = findChildByIdName(footerBundle, "qs_expand_indicator")
                ?.let { footerBundle.indexOfChild(it) }
                ?.takeIf { it >= 0 } ?: footerBundle.childCount
            footerBundle.addView(row, dragHandleIndex)
            if (row.isAttachedToWindow) registerVolumeReceiver(row)
        }

        if (settings.volumeSliderShowInCollapsed) {
            row.visibility = View.VISIBLE
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            leftMargin = hMargin
            rightMargin = hMargin
            topMargin = dp(ctx, 4)
            bottomMargin = dp(ctx, settings.brightnessBottomMarginDp)
        }

        val seekBar = row.findViewWithTag<SeekBar>(TAG_VOLUME_SEEKBAR)
        if (seekBar != null) {
            seekBar.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
            seekBar.progressDrawable = createModernSliderDrawable(trackColor, fillColor, radiusPx, heightPx)
            installRoundedOutline(seekBar, radiusPx)
            seekBar.clipToOutline = true
        }
        val leftIcon = row.findViewWithTag<ImageView>(TAG_VOLUME_ICON)
        if (leftIcon != null) {
            val iconSize = (heightPx * 0.44f).roundToInt().coerceIn(dp(ctx, 16), dp(ctx, 22))
            leftIcon.layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dp(ctx, 13)
            }
            leftIcon.imageTintList = ColorStateList.valueOf(iconTint)
        }
        val percentText = row.findViewWithTag<TextView>(TAG_VOLUME_PERCENT)
        if (percentText != null) {
            percentText.visibility = if (settings.volumeSliderShowPercent) View.VISIBLE else View.GONE
        }
        val muteBtn = row.findViewWithTag<ImageView>(TAG_VOLUME_MUTE_BTN)
        if (muteBtn != null) {
            muteBtn.visibility = if (settings.volumeSliderShowMuteButton) View.VISIBLE else View.GONE
            muteBtn.layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = dp(ctx, 10)
            }
            val pad = (heightPx * 0.27f).roundToInt()
            muteBtn.setPadding(pad, pad, pad, pad)
        }
        syncVolumeRowVisuals(row, settings)
    }

    private fun syncVolumeRowVisuals(row: LinearLayout, settings: ClassicControlCenterSettings) {
        val ctx = row.context
        val isDark = isDarkMode(ctx)
        val useLightFg = useLightForegroundOnPanel(settings, isDark, row)
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val isMuted = cur == 0 || runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)

        val seekBar = row.findViewWithTag<SeekBar>(TAG_VOLUME_SEEKBAR)
        if (seekBar != null) {
            if (seekBar.max != max) seekBar.max = max
            if (seekBar.progress != cur) seekBar.progress = cur
        }
        val leftIcon = row.findViewWithTag<ImageView>(TAG_VOLUME_ICON)
        leftIcon?.setImageDrawable(VectorPathDrawable(if (isMuted) PATH_VOLUME_OFF else PATH_VOLUME_UP, 24f))

        val fillColor = resolveBrightnessFillColor(ctx, settings, isDark)
        val pct = ((cur.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        val percentText = row.findViewWithTag<TextView>(TAG_VOLUME_PERCENT)
        if (percentText != null && settings.volumeSliderShowPercent) {
            val txt = "$pct%"
            if (percentText.text != txt) percentText.text = txt
            val textColor = if (pct >= 84) {
                adjustAlpha(contrastOnColor(fillColor), 0.88f)
            } else if (useLightFg) {
                0xEBFFFFFF.toInt()
            } else {
                0xCC181A1F.toInt()
            }
            if (percentText.currentTextColor != textColor) percentText.setTextColor(textColor)
        }

        val muteBtn = row.findViewWithTag<ImageView>(TAG_VOLUME_MUTE_BTN)
        if (muteBtn != null && settings.volumeSliderShowMuteButton) {
            val heightPx = dp(ctx, settings.volumeSliderHeightDp)
            val radiusPx = dp(ctx, settings.brightnessCornerRadiusDp).toFloat().coerceAtMost(heightPx / 2f)
            val trackColor = resolveBrightnessTrackColor(settings, useLightFg)
            muteBtn.setImageDrawable(VectorPathDrawable(if (isMuted) PATH_VOLUME_OFF else PATH_VOLUME_UP, 24f))
            muteBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(if (isMuted) fillColor else trackColor)
            }
            val iconColor = if (isMuted) {
                contrastOnColor(fillColor)
            } else if (useLightFg) {
                0xEBFFFFFF.toInt()
            } else {
                0xCC1A1C20.toInt()
            }
            muteBtn.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    private fun registerVolumeReceiver(row: LinearLayout) {
        if (volumeReceivers.containsKey(row)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isEnabled()) syncVolumeRowVisuals(row, currentSettings())
            }
        }
        runCatching {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                row.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                row.context.registerReceiver(receiver, filter)
            }
            volumeReceivers[row] = receiver
        }
    }

    private fun unregisterVolumeReceiver(view: View) {
        volumeReceivers.remove(view)?.let { r ->
            runCatching { view.context.unregisterReceiver(r) }
        }
    }

    private fun applyContainerExpansion(container: ViewGroup, expansion: Float, settings: ClassicControlCenterSettings) {
        val f = expansion.coerceIn(0f, 1f)
        val brightnessView = readField(container, "brightnessView") as? ViewGroup
        val footerBundle = readField(container, "footerBundle") as? ViewGroup

        if (brightnessView != null && settings.brightnessSliderVisible) {
            if (settings.brightnessExpandedOnly) {
                val targetVis = if (f > 0.08f) View.VISIBLE else View.GONE
                if (brightnessView.visibility != targetVis) brightnessView.visibility = targetVis
                brightnessView.alpha = ((f - 0.12f) / 0.88f).coerceIn(0f, 1f)
                brightnessView.isEnabled = f > 0.35f
            } else {
                if (brightnessView.visibility != View.VISIBLE) brightnessView.visibility = View.VISIBLE
                if (brightnessView.alpha != 1f) brightnessView.alpha = 1f
                brightnessView.isEnabled = true
            }
        }

        val volumeRow = footerBundle?.findViewWithTag<LinearLayout>(TAG_VOLUME_ROW)
        if (volumeRow != null && settings.volumeSliderEnabled) {
            if (!settings.volumeSliderShowInCollapsed) {
                val targetVis = if (f > 0.08f) View.VISIBLE else View.GONE
                if (volumeRow.visibility != targetVis) volumeRow.visibility = targetVis
                volumeRow.alpha = ((f - 0.15f) / 0.85f).coerceIn(0f, 1f)
            } else {
                if (volumeRow.visibility != View.VISIBLE) volumeRow.visibility = View.VISIBLE
                if (volumeRow.alpha != 1f) volumeRow.alpha = 1f
            }
        }

        if (settings.smoothOpenAnimation) {
            val qsPanel = readField(container, "qsPanel") as? View
            qsPanel?.let {
                val scale = 0.96f + 0.04f * f
                it.scaleX = scale
                it.scaleY = scale
            }
        }
    }

    private fun applyDataUsageStyling(dataUsage: ViewGroup, settings: ClassicControlCenterSettings, useLightFg: Boolean) {
        if (settings.dataUsageVisibilityMode == CLASSIC_CC_DATA_USAGE_HIDE) {
            dataUsage.visibility = View.GONE
            return
        }
        val divider = findChildByIdName(dataUsage, "qs_footer_data_usage_divider")
        divider?.visibility = if (settings.dataUsageHideDivider) View.GONE else View.VISIBLE
        if (settings.dataUsageModernPill) {
            val ctx = dataUsage.context
            dataUsage.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(if (useLightFg) 0x22FFFFFF else 0x10000000)
            }
            val pad = dp(ctx, 12)
            dataUsage.setPadding(pad, dp(ctx, 4), pad, dp(ctx, 4))
        } else {
            dataUsage.background = null
            dataUsage.setPadding(0, 0, 0, 0)
        }
    }

    private fun applyDragHandleStyling(
        container: ViewGroup,
        dragHandle: ImageView,
        settings: ClassicControlCenterSettings,
        useLightFg: Boolean,
    ) {
        val ctx = dragHandle.context
        when (settings.dragHandleStyle) {
            CLASSIC_CC_HANDLE_HIDDEN -> dragHandle.visibility = View.GONE
            CLASSIC_CC_HANDLE_PILL -> {
                dragHandle.visibility = View.VISIBLE
                val w = dp(ctx, settings.dragHandleWidthDp)
                val h = dp(ctx, settings.dragHandleHeightDp)
                dragHandle.setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = h / 2f
                    setSize(w, h)
                    setColor(if (useLightFg) 0x66FFFFFF else 0x3D000000)
                })
                dragHandle.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
            }
            else -> {
                dragHandle.visibility = View.VISIBLE
                (readField(container, "indicatorDrawable") as? Drawable)?.let { dragHandle.setImageDrawable(it) }
                dragHandle.setPadding(0, 0, 0, 0)
            }
        }
    }

    private fun applyDetailModernization(
        qsDetail: ViewGroup,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        useLightFg: Boolean,
        force: Boolean,
    ) {
        val container = readField(qsDetail, "mDetailContainer") as? View ?: return
        applyPanelSurfaceMaterial(container, settings, isDark, isPrimaryPanel = false, force = force)
        val content = readField(qsDetail, "mDetailContent") as? View
        content?.setBackgroundColor(Color.TRANSPARENT)
        val radiusPx = dp(qsDetail.context, 16).toFloat()
        listOf(
            readField(qsDetail, "mDetailSettingsButton") as? TextView,
            readField(qsDetail, "mDetailDoneButton") as? TextView,
        ).forEach { btn ->
            btn?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(if (useLightFg) 0x26FFFFFF else 0x12000000)
            }
        }
    }

    private fun applyCustomizerModernization(
        qsCustomizer: ViewGroup,
        settings: ClassicControlCenterSettings,
        isDark: Boolean,
        useLightFg: Boolean,
        force: Boolean,
    ) {
        val content = readField(qsCustomizer, "customizeContent") as? View ?: return
        setFloatField(qsCustomizer, "customizeContentRadius", dp(qsCustomizer.context, settings.panelCornerRadiusDp).toFloat())
        applyPanelSurfaceMaterial(content, settings, isDark, isPrimaryPanel = false, force = force)
        val radiusPx = dp(qsCustomizer.context, 16).toFloat()
        listOf(
            readField(qsCustomizer, "resetButton") as? TextView,
            readField(qsCustomizer, "doneButton") as? TextView,
        ).forEach { btn ->
            btn?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(if (useLightFg) 0x26FFFFFF else 0x12000000)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Grid Layout, Zero-Lag Dirty-Checked Tiles & Icons
    // -------------------------------------------------------------------------

    private fun applyTileLayoutResources(layout: ViewGroup, settings: ClassicControlCenterSettings): Boolean {
        if (layout.javaClass.name.contains("HeaderTileLayout")) return false
        val ctx = layout.context
        val isLand = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (isLand) settings.qsColumnsLandscape else settings.qsColumnsPortrait
        val rows = if (isLand) settings.qsRowsLandscape else settings.qsRowsPortrait
        val cellHeight = dp(ctx, settings.tileHeightDp)
        val spacing = dp(ctx, settings.tileHorizontalSpacingDp)
        val hPad = dp(ctx, settings.panelHorizontalPaddingDp)
        val topPad = dp(ctx, settings.tileTopMarginDp)
        val bottomPad = dp(ctx, 6)

        val prevCols = readField(layout, "mResourceColumns") as? Int
        val prevRows = readField(layout, "mMaxAllowedRows") as? Int
        val prevHeight = readField(layout, "mCellHeight") as? Int
        val prevSpacing = readField(layout, "mCellMarginHorizontal") as? Int

        setIntField(layout, "mResourceColumns", cols)
        setIntField(layout, "mMaxAllowedRows", rows)
        setIntField(layout, "mCellHeight", cellHeight)
        setIntField(layout, "mCellMarginHorizontal", spacing)
        if (layout.paddingLeft != hPad || layout.paddingTop != topPad || layout.paddingRight != hPad || layout.paddingBottom != bottomPad) {
            layout.setPadding(hPad, topPad, hPad, bottomPad)
        }
        layout.clipToPadding = false
        layout.clipChildren = false
        invokeNoArgMethod(layout, "updateColumns")

        return prevCols != cols || prevRows != rows || prevHeight != cellHeight || prevSpacing != spacing
    }

    private fun applyHeaderTileLayoutResources(layout: ViewGroup, settings: ClassicControlCenterSettings) {
        val ctx = layout.context
        val sizePx = dp(ctx, settings.tileIconBgSizeDp)
        val hPad = dp(ctx, settings.panelHorizontalPaddingDp)
        val topPad = dp(ctx, settings.panelTopPaddingDp)
        val bottomPad = dp(ctx, 6)
        val prevW = readField(layout, "mCellWidth") as? Int
        val prevH = readField(layout, "mCellHeight") as? Int
        setIntField(layout, "mCellWidth", sizePx)
        setIntField(layout, "mCellHeight", sizePx)
        val padChanged = layout.paddingLeft != hPad || layout.paddingTop != topPad || layout.paddingRight != hPad || layout.paddingBottom != bottomPad
        if (padChanged) layout.setPadding(hPad, topPad, hPad, bottomPad)
        if (prevW != sizePx || prevH != sizePx || padChanged) layout.requestLayout()
    }

    private fun applyTileViewLayout(
        tileView: ViewGroup,
        settings: ClassicControlCenterSettings,
        stateObj: Any? = null,
        forceIcon: Boolean = false,
    ) {
        val ctx = tileView.context
        val isDark = isDarkMode(ctx)
        val useLightFg = useLightForegroundOnPanel(settings, isDark, tileView)
        val isCollapsed = (readField(tileView, "mCollapsedView") as? Boolean) ?: false
        val stateInt = stateObj?.let { readField(it, "state") as? Int }
            ?: (readField(tileView, "mState") as? Int)
            ?: (readField(tileView, "mTileState") as? Int)
            ?: 1

        val version = currentVersion()
        val layoutSig = (((version * 31 + (if (isDark) 1 else 0)) * 31 + (if (useLightFg) 1 else 0)) * 31 + (if (isCollapsed) 1 else 0))
        val stateSig = layoutSig * 31 + stateInt

        val layoutChanged = tileView.getTag(TAG_TILE_LAYOUT_SIG_KEY) != layoutSig
        val stateChanged = tileView.getTag(TAG_TILE_STATE_SIG_KEY) != stateSig
        if (!layoutChanged && !stateChanged && !forceIcon) return

        val iconFrame = readField(tileView, "mIconFrame") as? ViewGroup ?: return
        val iconView = readField(tileView, "mIcon") as? ViewGroup
        val labelContainer = readField(tileView, "mLabelContainer") as? ViewGroup
        val label = readField(tileView, "mLabel") as? TextView
        val secondLine = readField(tileView, "mSecondLine") as? TextView
        val expandIndicator = readField(tileView, "mExpandIndicator") as? ImageView
        val expandSpace = readField(tileView, "mExpandSpace") as? View

        val isActive = stateInt == 2
        val isUnavailable = stateInt == 0
        val isPillCard = !isCollapsed && settings.tileStyle == CLASSIC_CC_TILE_STYLE_PILL_CARD
        val isGlassCard = !isCollapsed && settings.tileStyle == CLASSIC_CC_TILE_STYLE_GLASS_CARD

        if (layoutChanged) {
            tileView.setTag(TAG_TILE_LAYOUT_SIG_KEY, layoutSig)

            if (tileView is LinearLayout) {
                if (isPillCard) {
                    if (tileView.orientation != LinearLayout.HORIZONTAL) tileView.orientation = LinearLayout.HORIZONTAL
                    tileView.gravity = Gravity.CENTER_VERTICAL
                    val padH = dp(ctx, 12)
                    val padV = dp(ctx, 8)
                    tileView.setPadding(padH, padV, padH, padV)

                    val frameSize = dp(ctx, (settings.tileIconSizeDp + 10).coerceIn(28, 44))
                    val lp = (iconFrame.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(frameSize, frameSize)
                    lp.width = frameSize
                    lp.height = frameSize
                    lp.marginEnd = dp(ctx, 8)
                    iconFrame.layoutParams = lp

                    tileView.setOnClickListener { iconFrame.performClick() }
                    tileView.setOnLongClickListener { iconFrame.performLongClick() }
                } else {
                    if (tileView.orientation != LinearLayout.VERTICAL) tileView.orientation = LinearLayout.VERTICAL
                    tileView.gravity = Gravity.CENTER
                    tileView.setOnClickListener(null)
                    tileView.setOnLongClickListener(null)
                    if (isGlassCard) {
                        val cardRadius = dp(ctx, settings.tileCornerRadiusDp).toFloat()
                        val cardDrawable = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = cardRadius
                            setColor(if (useLightFg) 0x1EFFFFFF else 0x0D000000)
                            setStroke(dp(ctx, 1), if (useLightFg) 0x2EFFFFFF else 0x18000000)
                        }
                        tileView.background = InsetDrawable(cardDrawable, dp(ctx, 2), dp(ctx, 3), dp(ctx, 2), dp(ctx, 5))
                        tileView.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 8))
                    } else {
                        tileView.background = null
                        tileView.setPadding(0, if (isCollapsed) 0 else dp(ctx, 2), 0, if (isCollapsed) 0 else dp(ctx, 6))
                    }
                    val frameSize = dp(ctx, settings.tileIconBgSizeDp)
                    val lp = (iconFrame.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(frameSize, frameSize)
                    lp.width = frameSize
                    lp.height = frameSize
                    lp.marginEnd = 0
                    iconFrame.layoutParams = lp
                }
            }

            if (labelContainer != null) {
                if (isCollapsed || !settings.showTileLabel) {
                    labelContainer.visibility = View.GONE
                } else {
                    labelContainer.visibility = View.VISIBLE
                    if (isPillCard) {
                        labelContainer.setPadding(0, 0, 0, 0)
                        labelContainer.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        expandSpace?.visibility = View.GONE
                        label?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        secondLine?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        (findChildByIdName(labelContainer, "label_group") as? LinearLayout)?.gravity =
                            Gravity.START or Gravity.CENTER_VERTICAL
                    } else {
                        labelContainer.setPadding(0, dp(ctx, settings.tileLabelTopGapDp), 0, 0)
                        labelContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        label?.gravity = Gravity.CENTER
                        secondLine?.gravity = Gravity.CENTER
                        (findChildByIdName(labelContainer, "label_group") as? LinearLayout)?.gravity = Gravity.CENTER
                    }
                }
            }

            label?.let { tv ->
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.tileLabelSizeSp)
                val targetTf = createWeightedTypeface(if (settings.tileLabelBold) 600 else 500)
                if (tv.typeface !== targetTf) tv.typeface = targetTf
            }
            secondLine?.let { tv ->
                if (!settings.showTileSecondaryLabel) {
                    tv.visibility = View.GONE
                } else {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.tileLabelSizeSp - 1.5f).coerceAtLeast(8.5f))
                }
            }
            iconView?.let { applyIconViewDimensions(it, settings, isPillCard) }
        }

        if (stateChanged) {
            tileView.setTag(TAG_TILE_STATE_SIG_KEY, stateSig)

            if (isPillCard && tileView is LinearLayout) {
                val cardRadius = dp(ctx, settings.tileCornerRadiusDp).toFloat()
                val bgColor = resolveTileBgColor(ctx, settings, isActive, isUnavailable, useLightFg)
                val existingInset = tileView.background as? InsetDrawable
                val existingGrad = existingInset?.drawable as? GradientDrawable
                if (existingGrad != null) {
                    existingGrad.cornerRadius = cardRadius
                    existingGrad.setColor(bgColor)
                    if (settings.tileBorderEnabled) {
                        existingGrad.setStroke(dp(ctx, 1), if (useLightFg) 0x33FFFFFF else 0x1A000000)
                    } else {
                        existingGrad.setStroke(0, 0)
                    }
                } else {
                    val cardDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = cardRadius
                        setColor(bgColor)
                        if (settings.tileBorderEnabled) {
                            setStroke(dp(ctx, 1), if (useLightFg) 0x33FFFFFF else 0x1A000000)
                        }
                    }
                    val insetH = dp(ctx, 3)
                    val insetV = dp(ctx, 4)
                    tileView.background = InsetDrawable(cardDrawable, insetH, insetV, insetH, insetV)
                }
            }

            label?.let { tv ->
                val textColor = when {
                    isPillCard && isActive -> contrastOnColor(resolveTileBgColor(ctx, settings, true, false, useLightFg))
                    isUnavailable -> if (useLightFg) 0x66FFFFFF else 0x55000000
                    useLightFg -> 0xF5FFFFFF.toInt()
                    else -> 0xDE16181D.toInt()
                }
                if (tv.currentTextColor != textColor) tv.setTextColor(textColor)
            }
            secondLine?.let { tv ->
                if (!settings.showTileSecondaryLabel) {
                    if (tv.visibility != View.GONE) tv.visibility = View.GONE
                } else {
                    val secondaryColor = when {
                        isPillCard && isActive -> adjustAlpha(contrastOnColor(resolveTileBgColor(ctx, settings, true, false, useLightFg)), 0.78f)
                        useLightFg -> 0xB3FFFFFF.toInt()
                        else -> 0x8516181D.toInt()
                    }
                    if (tv.currentTextColor != secondaryColor) tv.setTextColor(secondaryColor)
                }
            }
            expandIndicator?.imageTintList = label?.textColors
        }

        if (forceIcon && iconView != null) {
            val imageView = readField(iconView, "mIcon") as? ImageView
            val cachedState = stateObj ?: readField(iconView, "mState")
            if (imageView != null && cachedState != null) {
                applyModernIconDrawable(iconView, imageView, cachedState, settings, isPillCard, force = true)
            }
        }
    }

    private fun applyIconViewDimensions(
        iconView: ViewGroup,
        settings: ClassicControlCenterSettings,
        isPillCard: Boolean = isInsidePillCardTile(iconView, settings),
    ) {
        val ctx = iconView.context
        val bgPx = if (isPillCard) dp(ctx, (settings.tileIconSizeDp + 10).coerceIn(28, 44)) else dp(ctx, settings.tileIconBgSizeDp)
        val iconPx = dp(ctx, settings.tileIconSizeDp)
        setIntField(iconView, "mIconBgSizePx", bgPx)
        setIntField(iconView, "mIconSizePx", iconPx)
    }

    private fun applyModernIconDrawable(
        iconView: ViewGroup,
        imageView: ImageView,
        state: Any,
        settings: ClassicControlCenterSettings,
        isPillCard: Boolean = isInsidePillCardTile(iconView, settings),
        force: Boolean = false,
    ) {
        val ctx = iconView.context
        val isDark = isDarkMode(ctx)
        val useLightFg = useLightForegroundOnPanel(settings, isDark, iconView)
        val stateInt = (readField(state, "state") as? Int) ?: 1
        val existing = imageView.drawable ?: return
        val innerDrawable: Drawable = if (existing is LayerDrawable && existing.numberOfLayers > 0) {
            existing.getDrawable(existing.numberOfLayers - 1)
        } else {
            existing
        }

        val iconSig = ((((currentVersion() * 31 + (if (useLightFg) 1 else 0)) * 31 + stateInt) * 31 + (if (isPillCard) 1 else 0)) * 31) +
            System.identityHashCode(innerDrawable)
        if (!force && existing is LayerDrawable && imageView.getTag(TAG_ICON_SIG_KEY) == iconSig) return
        imageView.setTag(TAG_ICON_SIG_KEY, iconSig)

        val isActive = stateInt == 2
        val isUnavailable = stateInt == 0
        val iconTint = resolveTileIconColor(ctx, settings, isActive, isUnavailable, useLightFg)
        runCatching {
            innerDrawable.mutate()
            innerDrawable.setTint(iconTint)
        }

        val iconPx = dp(ctx, settings.tileIconSizeDp)

        if (isPillCard) {
            val layers = LayerDrawable(arrayOf(ColorDrawable(Color.TRANSPARENT), innerDrawable)).apply {
                setLayerGravity(1, Gravity.CENTER)
                setLayerSize(1, iconPx, iconPx)
            }
            imageView.setImageDrawable(layers)
            return
        }

        val bgSizePx = dp(ctx, settings.tileIconBgSizeDp)
        val cornerPx = when (settings.tileStyle) {
            CLASSIC_CC_TILE_STYLE_CIRCLE -> bgSizePx / 2f
            else -> dp(ctx, settings.tileCornerRadiusDp).toFloat().coerceAtMost(bgSizePx / 2f)
        }
        val bgColor = resolveTileBgColor(ctx, settings, isActive, isUnavailable, useLightFg)
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setSize(bgSizePx, bgSizePx)
            setColor(bgColor)
            if (settings.tileBorderEnabled || settings.tileStyle == CLASSIC_CC_TILE_STYLE_GLASS_CARD) {
                val strokeColor = if (isActive) {
                    Color.argb(72, 255, 255, 255)
                } else if (useLightFg) {
                    0x30FFFFFF
                } else {
                    0x1A000000
                }
                setStroke(dp(ctx, 1), strokeColor)
            }
        }
        val layers = LayerDrawable(arrayOf(bgDrawable, innerDrawable)).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, iconPx, iconPx)
        }
        imageView.setImageDrawable(layers)
    }

    private fun isInsidePillCardTile(iconView: View, settings: ClassicControlCenterSettings): Boolean {
        if (settings.tileStyle != CLASSIC_CC_TILE_STYLE_PILL_CARD) return false
        val tileView = (iconView.parent as? View)?.parent as? View ?: return false
        val isCollapsed = (readField(tileView, "mCollapsedView") as? Boolean) ?: true
        return !isCollapsed
    }

    // -------------------------------------------------------------------------
    // Modern Page Indicator
    // -------------------------------------------------------------------------

    private fun applyModernPageIndicator(indicator: ViewGroup, activeIndex: Int, settings: ClassicControlCenterSettings) {
        val count = indicator.childCount
        when (settings.pageIndicatorStyle) {
            CLASSIC_CC_INDICATOR_HIDDEN -> indicator.visibility = View.GONE
            CLASSIC_CC_INDICATOR_PILL -> {
                indicator.visibility = if (count > 1) View.VISIBLE else View.INVISIBLE
                val ctx = indicator.context
                val useLightFg = useLightForegroundOnPanel(settings, isDarkMode(ctx), indicator)
                val dotSize = dp(ctx, 6)
                val activeWidth = dp(ctx, 18)
                val accent = resolveTileAccentColor(ctx, settings)
                val activeColor = if (settings.tileAccentMode == CLASSIC_CC_ACCENT_CUSTOM) {
                    accent
                } else if (useLightFg) {
                    0xF0FFFFFF.toInt()
                } else {
                    0xCC181A1F.toInt()
                }
                val inactiveColor = if (useLightFg) 0x4DFFFFFF else 0x33000000
                for (i in 0 until count) {
                    val dot = indicator.getChildAt(i) as? ImageView ?: continue
                    val isSelected = i == activeIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
                    dot.setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dotSize / 2f
                        setSize(if (isSelected) activeWidth else dotSize, dotSize)
                        setColor(if (isSelected) activeColor else inactiveColor)
                    })
                    dot.alpha = 1f
                }
                indicator.requestLayout()
            }
            else -> indicator.visibility = if (count > 1) View.VISIBLE else View.INVISIBLE
        }
    }

    // -------------------------------------------------------------------------
    // Modern Header: Typography, Compact Layout, Edit & Power Quick Actions
    // -------------------------------------------------------------------------

    private fun applyModernHeader(header: ViewGroup, settings: ClassicControlCenterSettings) {
        val ctx = header.context
        val isDark = isDarkMode(ctx)
        val bigTime = readField(header, "mBigTime") as? TextView
        val dateView = readField(header, "mDateView") as? TextView
        val horizontalDateView = readField(header, "mHorizontalDateView") as? TextView
        val carrierLayout = readField(header, "mCarrierTextLayout") as? View
        val carrierContainer = readField(header, "mCarrierTextContainerLayout") as? View
        val shortcut = readField(header, "mShortCut") as? ImageView
        val verticalInfo = readField(header, "mVerticalInfo") as? View

        bigTime?.let { clock ->
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.headerClockSizeSp)
            if (settings.headerUseModernFont) {
                val weight = when (settings.headerClockWeight) {
                    0 -> 300
                    1 -> 400
                    2 -> 500
                    3 -> 600
                    else -> 700
                }
                val tf = createWeightedTypeface(weight)
                if (clock.typeface !== tf) clock.typeface = tf
            }
            clock.includeFontPadding = false
        }
        dateView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.headerDateSizeSp)
        horizontalDateView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.headerDateSizeSp)

        if (settings.headerHideCarrier) {
            carrierLayout?.visibility = View.GONE
        }
        if (settings.headerCompactMode) {
            val sidePad = dp(ctx, settings.panelSideMarginDp + 4)
            header.setPadding(sidePad, dp(ctx, 4), sidePad, dp(ctx, 4))
            carrierContainer?.let { cc ->
                cc.layoutParams = cc.layoutParams?.apply {
                    height = dp(ctx, 20)
                }
            }
            verticalInfo?.setPadding(0, 0, 0, 0)
        }

        // Match header action icon tint directly to the live header clock luminance (white on HyperOS shade!)
        val clockIsLight = bigTime?.currentTextColor?.let(::isLightColor)
            ?: useLightForegroundOnPanel(settings, isDark, header)

        shortcut?.let { settingsBtn ->
            val actionsContainer = settingsBtn.parent as? LinearLayout ?: return@let
            val btnSize = dp(ctx, settings.headerActionButtonSizeDp)
            val btnGap = dp(ctx, 8)
            val tintColor = if (clockIsLight) 0xF2FFFFFF.toInt() else 0xDE1A1C20.toInt()

            settingsBtn.visibility = if (settings.headerShowSettingsButton) View.VISIBLE else View.GONE
            if (settings.headerShowSettingsButton) {
                styleHeaderActionButton(settingsBtn, settings, clockIsLight, btnSize, tintColor, marginStartPx = btnGap)
            }

            var editBtn = actionsContainer.findViewWithTag<ImageView>(TAG_HEADER_EDIT_BTN)
            if (settings.headerShowEditButton) {
                if (editBtn == null) {
                    editBtn = ImageView(ctx).apply {
                        tag = TAG_HEADER_EDIT_BTN
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        isClickable = true
                        isFocusable = true
                        setImageDrawable(VectorPathDrawable(PATH_EDIT_TILES, 24f))
                        setOnClickListener { v ->
                            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            openClassicTileCustomizer(v)
                        }
                    }
                    val settingsIdx = actionsContainer.indexOfChild(settingsBtn).coerceAtLeast(0)
                    actionsContainer.addView(editBtn, settingsIdx)
                }
                editBtn.visibility = View.VISIBLE
                styleHeaderActionButton(editBtn, settings, clockIsLight, btnSize, tintColor, marginStartPx = btnGap)
            } else {
                editBtn?.visibility = View.GONE
            }

            var powerBtn = actionsContainer.findViewWithTag<ImageView>(TAG_HEADER_POWER_BTN)
            if (settings.headerShowPowerButton) {
                if (powerBtn == null) {
                    powerBtn = ImageView(ctx).apply {
                        tag = TAG_HEADER_POWER_BTN
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        isClickable = true
                        isFocusable = true
                        setImageDrawable(VectorPathDrawable(PATH_POWER_MENU, 24f))
                        setOnClickListener { v ->
                            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            triggerPowerMenu()
                        }
                    }
                    actionsContainer.addView(powerBtn)
                }
                powerBtn.visibility = View.VISIBLE
                styleHeaderActionButton(powerBtn, settings, clockIsLight, btnSize, tintColor, marginStartPx = btnGap)
            } else {
                powerBtn?.visibility = View.GONE
            }
        }
    }

    private fun styleHeaderActionButton(
        button: ImageView,
        settings: ClassicControlCenterSettings,
        lightForeground: Boolean,
        sizePx: Int,
        tintColor: Int,
        marginStartPx: Int,
    ) {
        val ctx = button.context
        val lp = (button.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(sizePx, sizePx)
        lp.width = sizePx
        lp.height = sizePx
        lp.marginStart = marginStartPx
        lp.bottomMargin = dp(ctx, 2)
        button.layoutParams = lp

        if (settings.headerActionPillStyle) {
            val pad = (sizePx * 0.25f).roundToInt()
            button.setPadding(pad, pad, pad, pad)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (lightForeground) 0x2EFFFFFF else 0x16000000)
                setStroke(dp(ctx, 1), if (lightForeground) 0x33FFFFFF else 0x18000000)
            }
            button.imageTintList = ColorStateList.valueOf(tintColor)
        } else {
            val pad = dp(ctx, 4)
            button.setPadding(pad, pad, pad, pad)
            button.background = null
            button.imageTintList = ColorStateList.valueOf(tintColor)
        }
    }

    private fun openClassicTileCustomizer(anchor: View) {
        val snapshot = synchronized(qsPanels) { qsPanels.toList() }
        val targetPanel = snapshot.firstOrNull { it.isAttachedToWindow } ?: snapshot.firstOrNull()
        val customizer = targetPanel?.let { readField(it, "mCustomizePanel") }
        if (customizer != null) {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            val cx = loc[0] + anchor.width / 2
            val cy = loc[1] + anchor.height / 2
            runCatching {
                customizer.javaClass.methods.firstOrNull {
                    it.name == "show" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                }?.invoke(customizer, cx, cy)
            }.onFailure { error ->
                HookModuleLogger.log(module, Log.WARN, TAG, "Could not open classic QS customizer", error)
            }
        }
    }

    private fun triggerPowerMenu() {
        runCatching { showGlobalActions() }
            .onFailure { error ->
                HookModuleLogger.log(module, Log.WARN, TAG, "Could not invoke global actions from classic header", error)
            }
    }

    private fun applyHeaderHeightOffset(controller: Any, settings: ClassicControlCenterSettings) {
        val qsContainer = readField(controller, "mView") as? ViewGroup ?: return
        val header = runCatching { invokeNoArgMethod(qsContainer, "getHeader") as? View }.getOrNull() ?: return
        val lp = header.layoutParams ?: return
        if (lp.height <= 0) return

        val baseHeight = synchronized(containerBaseHeaderHeights) {
            containerBaseHeaderHeights.getOrPut(controller) { lp.height }
        }
        val offsetPx = dp(qsContainer.context, settings.headerHeightOffsetDp)
        val compactDelta = if (settings.headerCompactMode) -dp(qsContainer.context, 18) else 0
        val newHeight = (baseHeight + offsetPx + compactDelta).coerceAtLeast(dp(qsContainer.context, 40))
        if (lp.height != newHeight) {
            lp.height = newHeight
            header.layoutParams = lp
            runCatching {
                qsContainer.javaClass.declaredMethods.firstOrNull {
                    it.name == "updateResources" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }?.apply { isAccessible = true }?.invoke(qsContainer, false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Live Refresh Across All Active Classic QS Views & Shade Backdrop Blur
    // -------------------------------------------------------------------------

    private fun refreshAllActiveViews() {
        val settings = currentSettings()
        if (!settings.enabled) return

        val containers = synchronized(qsContainers) { qsContainers.map(::WeakReference) }
        val qqs = synchronized(quickQsPanels) { quickQsPanels.map(::WeakReference) }
        val layouts = synchronized(tileLayouts) { tileLayouts.map(::WeakReference) }
        val tiles = synchronized(tileViews) { tileViews.map(::WeakReference) }
        val indicators = synchronized(pageIndicators) { pageIndicators.map(::WeakReference) }
        val headers = synchronized(headerViews) { headerViews.map(::WeakReference) }
        val controllers = synchronized(containerControllers) { containerControllers.map(::WeakReference) }
        val mirrors = synchronized(brightnessMirrors) { brightnessMirrors.map(::WeakReference) }

        controllers.forEach { ref -> ref.get()?.let { runCatching { applyHeaderHeightOffset(it, settings) } } }
        headers.forEach { ref -> ref.get()?.let { if (it.isAttachedToWindow) runCatching { applyModernHeader(it, settings) } } }
        qqs.forEach { ref ->
            ref.get()?.let { panel ->
                if (panel.isAttachedToWindow) runCatching { invokeIntMethod(panel, "setMaxTiles", settings.qqsTileCount) }
            }
        }
        layouts.forEach { ref ->
            ref.get()?.let { layout ->
                if (layout.isAttachedToWindow) {
                    runCatching {
                        if (layout.javaClass.name.contains("HeaderTileLayout")) {
                            applyHeaderTileLayoutResources(layout, settings)
                        } else {
                            if (applyTileLayoutResources(layout, settings)) {
                                layout.requestLayout()
                            }
                            (layout.parent as? View)?.let { parent ->
                                setBooleanField(parent, "mDistributeTiles", true)
                                parent.requestLayout()
                            }
                        }
                    }
                }
            }
        }
        tiles.forEach { ref ->
            ref.get()?.let {
                if (it.isAttachedToWindow) runCatching { applyTileViewLayout(it, settings, stateObj = null, forceIcon = true) }
            }
        }
        indicators.forEach { ref ->
            ref.get()?.let { ind ->
                if (ind.isAttachedToWindow) {
                    val pos = (readField(ind, "mPosition") as? Int)?.coerceAtLeast(0) ?: 0
                    runCatching { applyModernPageIndicator(ind, pos, settings) }
                }
            }
        }
        containers.forEach { ref ->
            ref.get()?.let {
                if (it.isAttachedToWindow) runCatching {
                    val bg = readField(it, "qsBackground") as? View
                    bg?.setTag(TAG_BG_SIG_KEY, null)
                    applyQsContainerStyling(it, settings, force = true)
                    bg?.invalidate()
                    it.invalidate()
                }
            }
        }
        mirrors.forEach { ref -> ref.get()?.let { runCatching { styleBrightnessMirror(it, settings) } } }
    }

    // -------------------------------------------------------------------------
    // Color, Drawable & Cached Typeface Helpers
    // -------------------------------------------------------------------------

    private fun computeBgSignature(version: Int, isDark: Boolean, isPrimaryPanel: Boolean, effectiveMode: Int): Int =
        ((version * 31 + (if (isDark) 1 else 0)) * 31 + (if (isPrimaryPanel) 1 else 0)) * 31 + effectiveMode

    private fun resolveTileAccentColor(ctx: Context, settings: ClassicControlCenterSettings): Int =
        when (settings.tileAccentMode) {
            CLASSIC_CC_ACCENT_MONET -> runCatching {
                if (Build.VERSION.SDK_INT >= 31) ctx.getColor(android.R.color.system_accent1_500)
                else CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG
            }.getOrDefault(CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG)
            CLASSIC_CC_ACCENT_HYPER_BLUE -> CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG
            else -> settings.tileActiveBgColor
        }

    private fun resolveTileBgColor(
        ctx: Context,
        settings: ClassicControlCenterSettings,
        isActive: Boolean,
        isUnavailable: Boolean,
        useLightFg: Boolean,
    ): Int {
        if (isActive) return resolveTileAccentColor(ctx, settings)
        val inactive = if (settings.tileAccentMode == CLASSIC_CC_ACCENT_CUSTOM) {
            if (useLightFg) settings.tileInactiveDarkBgColor else settings.tileInactiveLightBgColor
        } else {
            if (useLightFg) 0x2EFFFFFF else 0x14000000
        }
        return if (isUnavailable) adjustAlpha(inactive, 0.55f) else inactive
    }

    private fun resolveTileIconColor(
        ctx: Context,
        settings: ClassicControlCenterSettings,
        isActive: Boolean,
        isUnavailable: Boolean,
        useLightFg: Boolean,
    ): Int {
        if (isActive) {
            return if (settings.tileAccentMode == CLASSIC_CC_ACCENT_CUSTOM) {
                settings.tileActiveIconColor
            } else {
                contrastOnColor(resolveTileAccentColor(ctx, settings))
            }
        }
        val inactive = if (settings.tileAccentMode == CLASSIC_CC_ACCENT_CUSTOM) {
            if (useLightFg) settings.tileInactiveDarkIconColor else settings.tileInactiveLightIconColor
        } else {
            if (useLightFg) 0xF2FFFFFF.toInt() else 0xDE1A1C20.toInt()
        }
        return if (isUnavailable) adjustAlpha(inactive, 0.45f) else inactive
    }

    private fun resolveBrightnessFillColor(ctx: Context, settings: ClassicControlCenterSettings, isDark: Boolean): Int =
        when (settings.brightnessColorMode) {
            CLASSIC_CC_BRIGHTNESS_COLOR_ACCENT -> resolveTileAccentColor(ctx, settings)
            CLASSIC_CC_BRIGHTNESS_COLOR_WHITE -> if (isDark) 0xEBFFFFFF.toInt() else 0xF2FFFFFF.toInt()
            else -> settings.brightnessFillColor
        }

    private fun resolveBrightnessTrackColor(settings: ClassicControlCenterSettings, useLightFg: Boolean): Int =
        if (settings.brightnessColorMode == CLASSIC_CC_BRIGHTNESS_COLOR_CUSTOM) {
            if (useLightFg) settings.brightnessTrackDarkColor else settings.brightnessTrackLightColor
        } else {
            if (useLightFg) 0x33FFFFFF else 0x16000000
        }

    private fun resolveBrightnessIconColor(settings: ClassicControlCenterSettings, fillColor: Int): Int =
        when (settings.brightnessColorMode) {
            CLASSIC_CC_BRIGHTNESS_COLOR_CUSTOM -> settings.brightnessIconColor
            else -> contrastOnColor(fillColor)
        }

    private fun createModernSliderDrawable(trackColor: Int, fillColor: Int, radiusPx: Float, heightPx: Int): LayerDrawable {
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(trackColor)
            setSize(-1, heightPx)
        }
        val progress = ModernCapsuleProgressDrawable(fillColor, radiusPx)
        return LayerDrawable(arrayOf(track, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) > 0.60
    }

    private fun contrastOnColor(color: Int): Int =
        if (isLightColor(color)) 0xDE16181D.toInt() else 0xFFFFFFFF.toInt()

    private fun adjustAlpha(color: Int, factor: Float): Int =
        Color.argb((Color.alpha(color) * factor).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun createWeightedTypeface(weight: Int): Typeface = synchronized(typefaceCache) {
        typefaceCache.get(weight) ?: (
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight, false)
            else Typeface.create("sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            ).also { typefaceCache.put(weight, it) }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpFloat(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private fun findChildByIdName(root: View, entryName: String): View? {
        val res = root.resources
        val id = res.getIdentifier(entryName, "id", root.context.packageName).takeIf { it != 0 }
            ?: res.getIdentifier(entryName, "id", "com.android.systemui").takeIf { it != 0 }
            ?: return null
        return root.findViewById(id)
    }

    // -------------------------------------------------------------------------
    // Cached Safe Reflection Helpers
    // -------------------------------------------------------------------------

    private fun invokeMethod(method: Method?, target: Any, vararg args: Any?) {
        if (method == null) return
        runCatching { method.invoke(target, *args) }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        val key = "${type.name}#$name"
        fieldCache[key]?.let { return it }
        if (missingFieldMarkers.containsKey(key)) return null
        val found = generateSequence(type) { it.superclass }.firstNotNullOfOrNull { c ->
            runCatching { c.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }
        if (found != null) {
            fieldCache[key] = found
        } else {
            missingFieldMarkers[key] = true
        }
        return found
    }

    private fun readField(target: Any, name: String): Any? =
        runCatching { findField(target.javaClass, name)?.get(target) }.getOrNull()

    private fun setIntField(target: Any, name: String, value: Int) {
        runCatching { findField(target.javaClass, name)?.setInt(target, value) }
    }

    private fun setFloatField(target: Any, name: String, value: Float) {
        runCatching { findField(target.javaClass, name)?.setFloat(target, value) }
    }

    private fun setBooleanField(target: Any, name: String, value: Boolean) {
        runCatching { findField(target.javaClass, name)?.setBoolean(target, value) }
    }

    private fun invokeNoArgMethod(target: Any, name: String): Any? = runCatching {
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.invoke(target)
    }.getOrNull()

    private fun invokeIntMethod(target: Any, name: String, arg: Int) {
        runCatching {
            generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                ?.apply { isAccessible = true }
                ?.invoke(target, arg)
        }
    }

    private fun invokeTwoIntMethod(target: Any, name: String, arg1: Int, arg2: Int) {
        runCatching {
            generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull {
                    it.name == name &&
                        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                }
                ?.apply { isAccessible = true }
                ?.invoke(target, arg1, arg2)
        }
    }

    private fun invokeSetMeasuredDimension(view: View, width: Int, height: Int) {
        runCatching {
            val method: Method = View::class.java.getDeclaredMethod(
                "setMeasuredDimension",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            method.invoke(view, width, height)
        }
    }

    /**
     * Smooth pill-rounded progress fill drawable (no harsh flat vertical cut at mid-progress).
     */
    private class ModernCapsuleProgressDrawable(
        fillColor: Int,
        private val cornerRadiusPx: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val clipPath = Path()
        private val fullRect = RectF()
        private val fillRect = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            fullRect.set(bounds)
            val r = cornerRadiusPx.coerceAtMost(fullRect.height() / 2f)
            clipPath.reset()
            clipPath.addRoundRect(fullRect, r, r, Path.Direction.CW)
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            if (level <= 0 || fullRect.isEmpty) return
            val fraction = (level / 10000f).coerceIn(0f, 1f)
            val h = fullRect.height()
            val w = fullRect.width()
            val r = cornerRadiusPx.coerceAtMost(h / 2f)
            // Keep at least one capsule diameter so the rounded right cap never pinches or flattens
            val minFillWidth = (h * 0.85f).coerceAtMost(w)
            val fillWidth = (minFillWidth + (w - minFillWidth) * fraction).coerceIn(0f, w)
            fillRect.set(fullRect.left, fullRect.top, fullRect.left + fillWidth, fullRect.bottom)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRoundRect(fillRect, r, r, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Lightweight scalable vector-path drawable for injected modern controls. */
    private class VectorPathDrawable(
        pathData: String,
        private val viewportSize: Float,
    ) : Drawable() {
        private val sourcePath: Path = runCatching { PathParser.createPathFromPathData(pathData) }.getOrDefault(Path())
        private val scaledPath = Path()
        private val matrix = android.graphics.Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private var tintList: ColorStateList? = null

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f || viewportSize <= 0f) return
            val scale = minOf(w, h) / viewportSize
            val dx = bounds.left + (w - viewportSize * scale) / 2f
            val dy = bounds.top + (h - viewportSize * scale) / 2f
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)
            sourcePath.transform(matrix, scaledPath)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(scaledPath, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tintList = tint
            paint.color = tint?.getColorForState(state, tint.defaultColor) ?: Color.WHITE
            invalidateSelf()
        }

        override fun setTint(tintColor: Int) {
            paint.color = tintColor
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "HyperChanger-ClassicCC"

        private const val CLS_NOTIF_MATERIAL_STATE_LAMBDA =
            "com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor\$materialTypeState\$1"
        private const val CLS_SHADE_BIONICS_LAMBDA =
            "com.miui.systemui.shade.blur.ShadeBlendBlurController\$isBionicsEnabled\$1"
        private const val CLS_MI_GLASS_COMPAT = "com.miui.systemui.util.MiGlassCompat"
        private const val CLS_MI_BLUR_COMPAT = "com.miui.systemui.util.MiBlurCompat"

        private const val CLS_QS_CONTAINER = "com.android.systemui.qs.MiuiQSContainer"
        private const val CLS_QS_CONTAINER_CONTROLLER = "com.android.systemui.qs.MiuiQSContainerController"
        private const val CLS_QS_PANEL = "com.android.systemui.qs.MiuiQSPanel"
        private const val CLS_QUICK_QS_PANEL = "com.android.systemui.qs.MiuiQuickQSPanel"
        private const val CLS_TILE_LAYOUT = "com.android.systemui.qs.MiuiTileLayout"
        private const val CLS_QS_TILE_BASE_VIEW = "com.android.systemui.qs.tileimpl.MiuiQSTileBaseView"
        private const val CLS_QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.MiuiQSTileView"
        private const val CLS_QS_ICON_VIEW = "com.android.systemui.qs.tileimpl.MiuiQSIconViewImpl"
        private const val CLS_PAGE_INDICATOR = "com.android.systemui.qs.MiuiPageIndicator"
        private const val CLS_QS_HEADER_VIEW = "com.android.systemui.qs.MiuiQSHeaderView"
        private const val CLS_BRIGHTNESS_SLIDER_VIEW = "com.android.systemui.settings.brightness.BrightnessSliderView"
        private const val CLS_BRIGHTNESS_MIRROR_CONTROLLER = "com.android.systemui.qs.brightness.MiuiBrightnessMirrorController"

        private val TARGET_CLASSES = setOf(
            CLS_NOTIF_MATERIAL_STATE_LAMBDA,
            CLS_SHADE_BIONICS_LAMBDA,
            CLS_QS_CONTAINER,
            CLS_QS_CONTAINER_CONTROLLER,
            CLS_QS_PANEL,
            CLS_QUICK_QS_PANEL,
            CLS_TILE_LAYOUT,
            CLS_QS_TILE_BASE_VIEW,
            CLS_QS_TILE_VIEW,
            CLS_QS_ICON_VIEW,
            CLS_PAGE_INDICATOR,
            CLS_QS_HEADER_VIEW,
            CLS_BRIGHTNESS_SLIDER_VIEW,
            CLS_BRIGHTNESS_MIRROR_CONTROLLER,
        )

        private val CLASSIC_CC_SOFT_GLASS_BASELINE = floatArrayOf(
            .67f, .16f, .09f, 0f, .24f, 1.4f, -.02f, .3f, .6f, 1f,
            .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
            1.2f, 1f, -.4f, .6f, -.8f, 1.4f, .7f, .8f, 1.15f, 4f, 2f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )

        private const val TAG_TOP_BRIGHTNESS_SLOT = "hc_classic_cc_top_brightness_slot"
        private const val TAG_BRIGHTNESS_ROW = "hc_classic_cc_brightness_row"
        private const val TAG_BRIGHTNESS_PERCENT = "hc_classic_cc_brightness_percent"
        private const val TAG_AUTO_BRIGHTNESS_BTN = "hc_classic_cc_auto_brightness_btn"
        private const val TAG_VOLUME_ROW = "hc_classic_cc_volume_row"
        private const val TAG_VOLUME_SLIDER_BOX = "hc_classic_cc_volume_slider_box"
        private const val TAG_VOLUME_SEEKBAR = "hc_classic_cc_volume_seekbar"
        private const val TAG_VOLUME_ICON = "hc_classic_cc_volume_icon"
        private const val TAG_VOLUME_PERCENT = "hc_classic_cc_volume_percent"
        private const val TAG_VOLUME_MUTE_BTN = "hc_classic_cc_volume_mute_btn"
        private const val TAG_HEADER_EDIT_BTN = "hc_classic_cc_header_edit_btn"
        private const val TAG_HEADER_POWER_BTN = "hc_classic_cc_header_power_btn"

        private const val TAG_TILE_LAYOUT_SIG_KEY = 0x7F0A6C11
        private const val TAG_TILE_STATE_SIG_KEY = 0x7F0A6C12
        private const val TAG_ICON_SIG_KEY = 0x7F0A6C13
        private const val TAG_BG_SIG_KEY = 0x7F0A6C14
        private const val TAG_OUTLINE_RADIUS_KEY = 0x7F0A6C15
        private const val TAG_SDF_LISTENER_KEY = 0x7F0A6C16
        private const val TAG_SDF_SIZE_KEY = 0x7F0A6C17

        private const val PATH_AUTO_BRIGHTNESS =
            "M10.85 12.65h2.3L12 9l-1.15 3.65zM20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM14.3 16l-.7-2h-3.2l-.7 2H7.8L11 7h2l3.2 9h-1.9z"
        private const val PATH_VOLUME_UP =
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
        private const val PATH_VOLUME_OFF =
            "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"
        private const val PATH_EDIT_TILES =
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
        private const val PATH_POWER_MENU =
            "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z"
    }
}
