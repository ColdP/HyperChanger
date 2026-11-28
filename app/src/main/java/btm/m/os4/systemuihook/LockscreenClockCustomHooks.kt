// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns:
 * 1. Injecting imported custom fonts directly into AOD Lockscreen Clock Editor's font selector lists
 *    (AllInOneFontEditor + FontEditor) and resolving styleId >= 10000 in AOD & SystemUI without
 *    overwriting built-in OEM fonts.
 * 2. Samsung-style per-glyph lockscreen clock contour avoidance (SystemUI + AOD editor live preview)
 *    with strict adherence to the user's dragged clock box length, pixel-aligned editor/lockscreen
 *    mapping, calibrated per-font HGHT shortening, and bidirectional Depth Effect conflict handling.
 * 3. Native SlidingSwitch rows injected into AOD Clock Editor's Font Tab (FontFilterView):
 *    - 时钟避让 (Clock Avoidance)
 *    - 解除时钟水平移动限制 (Unlock Horizontal Clock Move)
 */
internal class LockscreenClockCustomHooks(
    private val module: HyperSystemUiModule,
    private val logTag: String,
) {
    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hc-clock-contour").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Hook installation guards ---
    private val systemUiInstalled = AtomicBoolean(false)
    private val aodInstalled = AtomicBoolean(false)
    private val themeManagerInstalled = AtomicBoolean(false)
    private val hookedAdapterClasses = Collections.synchronizedSet(HashSet<String>())

    // --- Custom font catalog & typeface cache (keyed by styleId >= 10000) ---
    private val fontCacheLock = Any()
    private var cachedCatalogVersion: Long = -1L
    private var cachedCatalogEntries: List<RemoteClockFontEntry> = emptyList()
    private var cachedEntryByStyleId: Map<Int, RemoteClockFontEntry> = emptyMap()
    private val cachedTypefaceByStyleId = HashMap<Int, Pair<Typeface, FontVariableAxesInfo?>>()
    private val cachedThumbByKey = HashMap<String, Bitmap>()

    // --- SystemUI contour & depth state ---
    @Volatile private var sysUiContour: SubjectContourProfile? = null
    @Volatile private var sysUiCropPathLoaded: String = ""
    @Volatile private var sysUiCropMtimeLoaded: Long = -1L
    @Volatile private var sysUiDepthInteractorRef: WeakReference<Any>? = null
    @Volatile private var sysUiObserverRegistered = false
    private val activeSystemUiTimeViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // --- AOD editor contour, depth & switch state ---
    @Volatile private var aodActiveEditorRef: WeakReference<View>? = null
    @Volatile private var aodCachedSubjectBitmapRef: WeakReference<Bitmap>? = null
    @Volatile private var aodCachedContour: SubjectContourProfile? = null
    @Volatile private var aodCachedContourKey: Long = 0L
    @Volatile private var aodSuppressSwitchCallback = false
    @Volatile private var aodAutoDisablingDepth = false
    private val aodAvoidSwitches = Collections.synchronizedMap(WeakHashMap<View, CompoundButton>())
    private val aodHorizontalSwitches = Collections.synchronizedMap(WeakHashMap<View, CompoundButton>())
    private val activeAodTimeViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val editorFontMap = Collections.synchronizedMap(WeakHashMap<Any, List<RemoteClockFontEntry>>())
    private val editorBaseCountMap = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val adapterToEditorMap = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    @Volatile private var paintVariationOverrideMethodResolved = false
    @Volatile private var paintVariationOverrideMethod: Method? = null
    private val reenteringCustomRectLayout = ThreadLocal<Boolean>()

    // --- Public entry points ---

    fun installSystemUi(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (!systemUiInstalled.compareAndSet(false, true)) return
        installFontResolutionHooks(classLoader, preferences, isEditor = false)
        installTimeViewAvoidanceHook(classLoader, preferences, isEditor = false)
        installSystemUiDepthConflictHooks(classLoader, preferences)
        HookModuleLogger.log(module, Log.INFO, logTag, "Installed SystemUI clock avoidance & custom font hooks")
    }

    fun installAod(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (!aodInstalled.compareAndSet(false, true)) return
        installFontResolutionHooks(classLoader, preferences, isEditor = true)
        installAodFontSelectorInjectionHooks(classLoader, preferences)
        installTimeViewAvoidanceHook(classLoader, preferences, isEditor = true)
        installAodFontTabSwitchHooks(classLoader, preferences, "aod")
        installAodDepthConflictAndApplyHooks(classLoader, preferences)
        HookModuleLogger.log(module, Log.INFO, logTag, "Installed AOD clock avoidance & custom font hooks")
    }

    fun installThemeManager(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (!themeManagerInstalled.compareAndSet(false, true)) return
        installAodFontSelectorInjectionHooks(classLoader, preferences)
        installAodFontTabSwitchHooks(classLoader, preferences, "thememanager")
    }

    // =========================================================================
    // 1. Custom Clock Font Remote Catalog & Typeface Resolution (by styleId)
    // =========================================================================

    private fun loadRemoteCatalog(preferences: SharedPreferences): List<RemoteClockFontEntry> {
        val version = preferences.getLong(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION, 0L)
        synchronized(fontCacheLock) {
            if (cachedCatalogVersion == version && version > 0L) {
                return cachedCatalogEntries
            }
        }
        val entries = runCatching {
            module.openRemoteFile(LOCKSCREEN_CLOCK_FONT_REMOTE_INDEX).use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    decodeLockscreenClockFontCatalog(input.readBytes())
                }
            }
        }.getOrDefault(emptyList())

        synchronized(fontCacheLock) {
            if (cachedCatalogVersion != version) {
                cachedTypefaceByStyleId.clear()
                cachedThumbByKey.clear()
            }
            cachedCatalogVersion = version
            cachedCatalogEntries = entries
            cachedEntryByStyleId = entries.associateBy { it.styleId }
        }
        return entries
    }

    private fun resolveCustomFontByStyleId(
        styleId: Int,
        preferences: SharedPreferences,
    ): Pair<Typeface, FontVariableAxesInfo?>? {
        if (styleId < LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) return null
        loadRemoteCatalog(preferences)
        val entry = synchronized(fontCacheLock) {
            cachedTypefaceByStyleId[styleId]?.let { return it }
            cachedEntryByStyleId[styleId]
        } ?: return null

        val loaded = runCatching {
            val remoteName = lockscreenClockFontRemoteFile(entry.id)
            var rawBytes: ByteArray? = null
            module.openRemoteFile(remoteName).use { pfd ->
                rawBytes = ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(pfd.fileDescriptor),
                ).use { it.readBytes() }
            }
            val bytes = rawBytes ?: return@runCatching null
            val axes = runCatching { ClockFontParser.inspect(bytes) }.getOrNull()
            val tf = module.openRemoteFile(remoteName).use { pfd ->
                ClockFontParser.buildTypefaceFromPfd(pfd)
            } ?: return@runCatching null
            tf to axes
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Failed loading custom font styleId=$styleId", err)
        }.getOrNull() ?: return null

        synchronized(fontCacheLock) {
            cachedTypefaceByStyleId[styleId] = loaded
        }
        return loaded
    }

    /**
     * Resolves custom fonts ONLY when fontStyle >= 10000 so built-in OEM fonts (21..26) stay untouched,
     * and applies ink-bounds-based layout for third-party fonts that do not follow HyperOS's bespoke
     * 3-axis (wght / wdth 10..100 / HGHT 10..100) font_set.xml specification.
     */
    private fun installFontResolutionHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        isEditor: Boolean,
    ) {
        val scope = if (isEditor) "aod" else "systemui"

        // 1. AOD's AllInOneBase.updateFontType(int)
        runCatching {
            val allInOneClass = classLoader.loadClass("com.miui.clock.allInOne.AllInOneBase")
            allInOneClass.declaredMethods
                .firstOrNull { it.name == "updateFontType" && it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
                ?.let { method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-font-update-type:$scope")
                        .intercept { chain ->
                            val base = chain.thisObject
                            val styleId = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                            if (styleId < LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) {
                                val wasCustom = readAllInOneCurrentFontStyle(base) >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE
                                val result = chain.proceed()
                                if (wasCustom) {
                                    restoreBuiltInAllInOneAfterCustomFont(base)
                                }
                                return@intercept result
                            }
                            val custom = resolveCustomFontByStyleId(styleId, preferences)
                                ?: return@intercept chain.proceed()
                            applyTypefaceToAllInOneBase(base, custom.first, custom.second, styleId)
                            relayoutAllInOneForCustomFontIfReady(base, custom.first, custom.second, styleId)
                            null
                        }
                }

            // 2. SystemUI & AOD AllInOneBase.setClockStyleInfo(ClockStyleInfo) — SystemUI inlines the font switch here
            allInOneClass.declaredMethods
                .filter { it.name == "setClockStyleInfo" && it.parameterCount == 1 }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-font-style-info:$scope:$idx")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val base = chain.thisObject
                            val styleId = readAllInOneInfoFontStyle(base)
                            if (styleId >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) {
                                resolveCustomFontByStyleId(styleId, preferences)?.let { custom ->
                                    applyTypefaceToAllInOneBase(base, custom.first, custom.second, styleId)
                                    relayoutAllInOneForCustomFontIfReady(base, custom.first, custom.second, styleId)
                                }
                            }
                            result
                        }
                }

            // 3. SystemUI & AOD AllInOneBase.setClockViewRect(Rect) — adapt non-HyperOS custom fonts cleanly
            allInOneClass.declaredMethods
                .firstOrNull { it.name == "setClockViewRect" && it.parameterCount == 1 && it.parameterTypes[0] == Rect::class.java }
                ?.apply { isAccessible = true }
                ?.let { method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-font-rect-layout:$scope")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (reenteringCustomRectLayout.get() == true) {
                                return@intercept result
                            }
                            val base = chain.thisObject
                            val styleId = readAllInOneInfoFontStyle(base).takeIf { it >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE }
                                ?: readAllInOneCurrentFontStyle(base)
                            if (styleId >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) {
                                val custom = resolveCustomFontByStyleId(styleId, preferences)
                                if (custom != null) {
                                    applyTypefaceToAllInOneBase(base, custom.first, custom.second, styleId)
                                    if (!isHyperOsStandardClockFont(custom.second)) {
                                        val rect = (runCatching {
                                            findField(base.javaClass, "mClockViewRect")?.get(base) as? Rect
                                        }.getOrNull() ?: chain.getArg(0) as? Rect)
                                        if (rect != null && rect.width() > 0 && rect.height() > 0) {
                                            layoutNonHyperCustomFontInAllInOne(
                                                base = base,
                                                rect = rect,
                                                typeface = custom.first,
                                                axes = custom.second,
                                                styleId = styleId,
                                            )
                                        }
                                    }
                                }
                            }
                            result
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "AllInOneBase custom font hooks skipped ($scope)", err)
        }

        // 4. Classic / ClassicPlus / ClassicMax font loaders (only when style >= 10000)
        runCatching {
            val classicUtils = classLoader.loadClass("com.miui.clock.utils.ClassicClockTimeUtils")
            classicUtils.declaredMethods
                .filter {
                    it.returnType == Typeface::class.java &&
                        (it.name == "getClassicMaxTypeface" || it.name == "getBigFontTypeface")
                }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-classic-custom-font:$scope:${method.name}:$idx")
                        .intercept { chain ->
                            val styleId = when (method.name) {
                                "getClassicMaxTypeface" -> chain.args.getOrNull(1) as? Int ?: 0
                                "getBigFontTypeface" -> chain.args.getOrNull(0) as? Int ?: 0
                                else -> 0
                            }
                            if (styleId >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) {
                                resolveCustomFontByStyleId(styleId, preferences)?.first ?: chain.proceed()
                            } else {
                                chain.proceed()
                            }
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "ClassicClockTimeUtils custom font hook skipped ($scope)", err)
        }
    }

    private fun isHyperOsStandardClockFont(axes: FontVariableAxesInfo?): Boolean {
        if (axes == null || !axes.hasHeightAxis || !axes.hasWidthAxis) return false
        return axes.widthMin <= 20f && axes.widthMax >= 90f &&
            axes.heightMin <= 20f && axes.heightMax >= 90f
    }

    private fun readAllInOneInfoFontStyle(base: Any): Int {
        val info = runCatching { findField(base.javaClass, "mClockStyleInfo")?.get(base) }.getOrNull() ?: return 0
        return readIntFieldOrGetter(info, "fontStyle", "getFontStyle")
    }

    private fun readAllInOneCurrentFontStyle(base: Any): Int {
        val hourView = runCatching { findField(base.javaClass, "mHourView")?.get(base) as? View }.getOrNull() ?: return 0
        return runCatching { findField(hourView.javaClass, "mFontStyle")?.getInt(hourView) ?: 0 }.getOrDefault(0)
    }

    private fun applyTypefaceToAllInOneBase(
        base: Any,
        typeface: Typeface,
        axes: FontVariableAxesInfo?,
        styleId: Int,
    ) {
        val isDoubleRow = runCatching {
            findField(base.javaClass, "mDoubleRow")?.getBoolean(base) ?: false
        }.getOrDefault(false)
        val featureSettings = if (isHyperOsStandardClockFont(axes)) {
            if (isDoubleRow) "tnum" else null
        } else {
            if (isDoubleRow) "lnum, tnum" else "lnum"
        }
        listOf("mHourView", "mMinuteView", "mColonView").forEach { fieldName ->
            val tv = runCatching { findField(base.javaClass, fieldName)?.get(base) as? View }.getOrNull() ?: return@forEach
            runCatching {
                tv.javaClass.getMethod("setTypeface", Typeface::class.java).invoke(tv, typeface)
            }
            runCatching {
                tv.javaClass.getMethod("setFontStyle", Int::class.javaPrimitiveType).invoke(tv, styleId)
            }
            runCatching {
                val paint = findField(tv.javaClass, "textPaint")?.get(tv) as? Paint
                if (paint != null) {
                    paint.fontFeatureSettings = featureSettings
                    if (!isHyperOsStandardClockFont(axes)) {
                        val w = findField(tv.javaClass, "weight")?.getFloat(tv) ?: (axes?.weightDefault ?: 400f)
                        val wd = axes?.widthDefault ?: 100f
                        val h = axes?.heightDefault ?: 28f
                        applyCustomFontVariation(paint, axes, w, wd, h)
                    }
                }
            }
            tv.invalidate()
        }
        runCatching {
            val measurePaint = findField(base.javaClass, "mTextMeasurePaint")?.get(base) as? Paint
            if (measurePaint != null) {
                measurePaint.typeface = typeface
                measurePaint.fontFeatureSettings = featureSettings
            }
        }
    }

    private fun relayoutAllInOneForCustomFontIfReady(
        base: Any,
        typeface: Typeface,
        axes: FontVariableAxesInfo?,
        styleId: Int,
    ) {
        val rect = runCatching {
            findField(base.javaClass, "mClockViewRect")?.get(base) as? Rect
        }.getOrNull() ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        if (!isHyperOsStandardClockFont(axes)) {
            layoutNonHyperCustomFontInAllInOne(base, Rect(rect), typeface, axes, styleId)
        } else {
            runCatching {
                reenteringCustomRectLayout.set(true)
                try {
                    base.javaClass.getMethod("setClockViewRect", Rect::class.java).invoke(base, Rect(rect))
                } finally {
                    reenteringCustomRectLayout.set(false)
                }
            }
        }
    }

    private fun restoreBuiltInAllInOneAfterCustomFont(base: Any) {
        val isDoubleRow = runCatching {
            findField(base.javaClass, "mDoubleRow")?.getBoolean(base) ?: false
        }.getOrDefault(false)
        listOf("mHourView", "mMinuteView", "mColonView").forEach { fieldName ->
            val tv = runCatching { findField(base.javaClass, fieldName)?.get(base) as? View }.getOrNull() ?: return@forEach
            runCatching {
                val paint = findField(tv.javaClass, "textPaint")?.get(tv) as? Paint
                if (paint != null) {
                    paint.fontFeatureSettings = if (isDoubleRow) "tnum" else null
                    applyVariationStringToPaint(paint, null)
                }
            }
        }
        val rect = runCatching {
            findField(base.javaClass, "mClockViewRect")?.get(base) as? Rect
        }.getOrNull() ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        runCatching {
            reenteringCustomRectLayout.set(true)
            try {
                base.javaClass.getMethod("setClockViewRect", Rect::class.java).invoke(base, Rect(rect))
            } finally {
                reenteringCustomRectLayout.set(false)
            }
        }
    }

    /**
     * Ink-bounds-based layout solver for custom fonts that do not follow HyperOS's 3-axis font_set.xml
     * specification. Eliminates tiny glyphs, horizontal clipping, crushed 'wdth', and fontMetrics.top
     * vertical drift in both single-row and double-row layouts across AOD Editor and SystemUI.
     */
    private fun layoutNonHyperCustomFontInAllInOne(
        base: Any,
        rect: Rect,
        typeface: Typeface,
        axes: FontVariableAxesInfo?,
        styleId: Int,
    ) {
        runCatching {
            val rectW = rect.width().toFloat()
            val rectH = rect.height().toFloat()
            if (rectW <= 4f || rectH <= 4f) return

            val hourView = findField(base.javaClass, "mHourView")?.get(base) as? View
            val minuteView = findField(base.javaClass, "mMinuteView")?.get(base) as? View
            val colonView = findField(base.javaClass, "mColonView")?.get(base) as? View
            val density = (base as? View)?.resources?.displayMetrics?.density
                ?: hourView?.resources?.displayMetrics?.density
                ?: 3f

            val isDoubleRow = findField(base.javaClass, "mDoubleRow")?.getBoolean(base) ?: false
            val is24Hour = findField(base.javaClass, "m24HourFormat")?.getBoolean(base) ?: true
            val colonShow = findField(base.javaClass, "mColonShow")?.getBoolean(base) ?: true
            val inNotif = findField(base.javaClass, "mInNotificationState")?.getBoolean(base) ?: false
            val rawWeight = findField(base.javaClass, "mAllInOneWeight")?.getFloat(base) ?: -1f
            val showColon = !isDoubleRow && (!is24Hour || colonShow)

            val hourText = hourView?.let { findField(it.javaClass, "mText")?.get(it) as? String }
                ?.takeIf { it.isNotEmpty() } ?: "12"
            val rawMinText = minuteView?.let { findField(it.javaClass, "mText")?.get(it) as? String }
                ?.takeIf { it.isNotEmpty() } ?: "00"
            val minuteDigits = rawMinText.removePrefix(":").ifEmpty { "00" }
            val fullText = if (showColon) "$hourText:$minuteDigits" else "$hourText$minuteDigits"

            val effectiveWeight = when {
                axes?.hasWeightAxis == true && rawWeight > 0f -> {
                    val w = if (inNotif) rawWeight * 0.85f else rawWeight
                    w.coerceIn(axes.weightMin, axes.weightMax)
                }
                axes?.hasWeightAxis == true -> axes.weightDefault.coerceIn(axes.weightMin, axes.weightMax)
                rawWeight > 0f -> rawWeight
                else -> 400f
            }

            val featureSettings = if (isDoubleRow) "lnum, tnum" else "lnum"
            val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.typeface = typeface
                this.fontFeatureSettings = featureSettings
            }

            val chosenHeight = axes?.heightDefault ?: 28f
            var chosenWidth = axes?.widthDefault ?: 100f
            val probeSize = 200f
            val probePath = Path()
            val probeBounds = RectF()

            // For standard variable fonts with a 'wdth' axis, adapt width smoothly to the box aspect ratio
            if (axes?.hasWidthAxis == true && axes.widthMax > axes.widthMin + 1f) {
                measurePaint.textSize = probeSize
                applyCustomFontVariation(measurePaint, axes, effectiveWeight, axes.widthDefault, chosenHeight)
                val probeStr = if (isDoubleRow) "00" else if (showColon) "00:00" else "0000"
                measurePaint.getTextPath(probeStr, 0, probeStr.length, 0f, 0f, probePath)
                probePath.computeBounds(probeBounds, true)
                if (!probeBounds.isEmpty && probeBounds.width() > 1f && probeBounds.height() > 1f) {
                    val glyphAspect = probeBounds.width() / probeBounds.height()
                    val targetAspect = if (isDoubleRow) {
                        (rectW * 0.90f) / (rectH * 0.45f)
                    } else {
                        (rectW * 0.94f) / (rectH * 0.90f)
                    }
                    val factor = (targetAspect / glyphAspect).coerceIn(0.55f, 1.45f)
                    chosenWidth = (axes.widthDefault * factor).coerceIn(axes.widthMin, axes.widthMax)
                }
            }

            applyCustomFontVariation(measurePaint, axes, effectiveWeight, chosenWidth, chosenHeight)

            if (!isDoubleRow) {
                measurePaint.textSize = probeSize
                val refStr = if (showColon) "00:00" else "0000"
                val fullProbeBounds = measureTextInkBounds(measurePaint, fullText)
                val refProbeBounds = measureTextInkBounds(measurePaint, refStr)
                val fitW = max(fullProbeBounds.width(), refProbeBounds.width() * 0.92f).coerceAtLeast(1f)
                val fitH = max(fullProbeBounds.height(), refProbeBounds.height()).coerceAtLeast(1f)
                val availW = rectW * 0.95f
                val availH = rectH * 0.94f
                var textSizePx = min(probeSize * (availW / fitW), probeSize * (availH / fitH))
                    .coerceIn(16f, 2000f)

                measurePaint.textSize = textSizePx
                var actualInk = measureTextInkBounds(measurePaint, fullText)
                if (actualInk.width() > rectW * 0.97f && actualInk.width() > 1f) {
                    textSizePx = (textSizePx * (rectW * 0.96f / actualInk.width())).coerceAtLeast(16f)
                    measurePaint.textSize = textSizePx
                    actualInk = measureTextInkBounds(measurePaint, fullText)
                }
                val zeroInk = measureTextInkBounds(measurePaint, "0")
                val refTop = if (!zeroInk.isEmpty) min(actualInk.top, zeroInk.top) else actualInk.top
                val refBottom = if (!zeroInk.isEmpty) max(actualInk.bottom, zeroInk.bottom) else actualInk.bottom
                val refH = (refBottom - refTop).coerceAtLeast(actualInk.height().coerceAtLeast(1f))

                val charWidths = FloatArray(fullText.length)
                measurePaint.getTextWidths(fullText, charWidths)
                var hourAdv = 0f
                for (i in 0 until min(hourText.length, charWidths.size)) hourAdv += charWidths[i]
                val colonAdv = if (showColon && hourText.length < charWidths.size) charWidths[hourText.length] else 0f
                var minuteAdv = 0f
                val minStart = hourText.length + if (showColon) 1 else 0
                for (i in minStart until charWidths.size) minuteAdv += charWidths[i]

                val drawX = rect.left + (rectW - actualInk.width()) * 0.5f - actualInk.left
                val extraH = (rectH - refH).coerceAtLeast(0f)
                val topOffset = min(extraH * 0.44f, max(extraH * 0.14f, 28f * density))
                val drawY = rect.top + topOffset - refTop

                if (hourView != null && hourView.visibility != View.GONE) {
                    updateTimeViewCustomMetrics(
                        tv = hourView,
                        typeface = typeface,
                        styleId = styleId,
                        featureSettings = featureSettings,
                        axes = axes,
                        weight = effectiveWeight,
                        width = chosenWidth,
                        height = chosenHeight,
                        textSizePx = textSizePx,
                        advanceWidth = hourAdv,
                        colonAdvanceWidth = null,
                        drawX = drawX,
                        drawY = drawY,
                    )
                }
                if (minuteView != null && minuteView.visibility != View.GONE) {
                    updateTimeViewCustomMetrics(
                        tv = minuteView,
                        typeface = typeface,
                        styleId = styleId,
                        featureSettings = featureSettings,
                        axes = axes,
                        weight = effectiveWeight,
                        width = chosenWidth,
                        height = chosenHeight,
                        textSizePx = textSizePx,
                        advanceWidth = if (showColon) colonAdv + minuteAdv else minuteAdv,
                        colonAdvanceWidth = if (showColon) colonAdv else 0f,
                        drawX = drawX + hourAdv,
                        drawY = drawY,
                    )
                }
                if (colonView != null) {
                    updateTimeViewCustomMetrics(
                        tv = colonView,
                        typeface = typeface,
                        styleId = styleId,
                        featureSettings = featureSettings,
                        axes = axes,
                        weight = effectiveWeight,
                        width = chosenWidth,
                        height = chosenHeight,
                        textSizePx = textSizePx,
                        advanceWidth = colonAdv,
                        colonAdvanceWidth = null,
                        drawX = drawX + hourAdv,
                        drawY = drawY,
                    )
                }
            } else {
                // Double-row layout: fit hour and minute cleanly with identical optical size and harmonious row gap
                measurePaint.textSize = probeSize
                val hourProbe = measureTextInkBounds(measurePaint, hourText)
                val minProbe = measureTextInkBounds(measurePaint, minuteDigits)
                val refProbe = measureTextInkBounds(measurePaint, "00")
                val fitW = max(max(hourProbe.width(), minProbe.width()), refProbe.width() * 0.92f).coerceAtLeast(1f)
                val fitH = max(max(hourProbe.height(), minProbe.height()), refProbe.height()).coerceAtLeast(1f)
                val availW = rectW * 0.90f
                val maxRowH = rectH * 0.465f
                var textSizePx = min(probeSize * (availW / fitW), probeSize * (maxRowH / fitH))
                    .coerceIn(16f, 2000f)

                measurePaint.textSize = textSizePx
                var hourInk = measureTextInkBounds(measurePaint, hourText)
                var minInk = measureTextInkBounds(measurePaint, minuteDigits)
                val maxInkW = max(hourInk.width(), minInk.width())
                if (maxInkW > rectW * 0.94f && maxInkW > 1f) {
                    textSizePx = (textSizePx * (rectW * 0.92f / maxInkW)).coerceAtLeast(16f)
                    measurePaint.textSize = textSizePx
                    hourInk = measureTextInkBounds(measurePaint, hourText)
                    minInk = measureTextInkBounds(measurePaint, minuteDigits)
                }

                val hourAdv = measurePaint.measureText(hourText)
                val minAdv = measurePaint.measureText(minuteDigits)
                val rowH = max(hourInk.height(), minInk.height()).coerceAtLeast(1f)
                val gap = min(rectH * 0.06f, rowH * 0.16f).coerceAtLeast(4f * density)
                val totalH = hourInk.height() + gap + minInk.height()
                val extraH = (rectH - totalH).coerceAtLeast(0f)
                val blockTop = rect.top + min(extraH * 0.45f, max(extraH * 0.15f, 24f * density))

                val hourX = rect.left + (rectW - hourInk.width()) * 0.5f - hourInk.left
                val hourY = blockTop - hourInk.top
                val minX = rect.left + (rectW - minInk.width()) * 0.5f - minInk.left
                val minY = blockTop + hourInk.height() + gap - minInk.top

                if (hourView != null && hourView.visibility != View.GONE) {
                    updateTimeViewCustomMetrics(
                        tv = hourView,
                        typeface = typeface,
                        styleId = styleId,
                        featureSettings = featureSettings,
                        axes = axes,
                        weight = effectiveWeight,
                        width = chosenWidth,
                        height = chosenHeight,
                        textSizePx = textSizePx,
                        advanceWidth = hourAdv,
                        colonAdvanceWidth = null,
                        drawX = hourX,
                        drawY = hourY,
                    )
                }
                if (minuteView != null && minuteView.visibility != View.GONE) {
                    updateTimeViewCustomMetrics(
                        tv = minuteView,
                        typeface = typeface,
                        styleId = styleId,
                        featureSettings = featureSettings,
                        axes = axes,
                        weight = effectiveWeight,
                        width = chosenWidth,
                        height = chosenHeight,
                        textSizePx = textSizePx,
                        advanceWidth = minAdv,
                        colonAdvanceWidth = 0f,
                        drawX = minX,
                        drawY = minY,
                    )
                }
            }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "Custom font layout fallback", err)
        }
    }

    private fun measureTextInkBounds(paint: Paint, text: String): RectF {
        val path = Path()
        val bounds = RectF()
        if (text.isNotEmpty()) {
            paint.getTextPath(text, 0, text.length, 0f, 0f, path)
            path.computeBounds(bounds, true)
        }
        return bounds
    }

    private fun updateTimeViewCustomMetrics(
        tv: View,
        typeface: Typeface,
        styleId: Int,
        featureSettings: String?,
        axes: FontVariableAxesInfo?,
        weight: Float,
        width: Float,
        height: Float,
        textSizePx: Float,
        advanceWidth: Float,
        colonAdvanceWidth: Float?,
        drawX: Float,
        drawY: Float,
    ) {
        val cls = tv.javaClass
        runCatching { cls.getMethod("setTypeface", Typeface::class.java).invoke(tv, typeface) }
        runCatching { cls.getMethod("setFontStyle", Int::class.javaPrimitiveType).invoke(tv, styleId) }
        runCatching {
            val paint = findField(cls, "textPaint")?.get(tv) as? Paint
            if (paint != null) {
                paint.fontFeatureSettings = featureSettings
                applyCustomFontVariation(paint, axes, weight, width, height)
            }
        }
        runCatching {
            cls.getMethod(
                "setClockParamsInternal",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).invoke(tv, weight, width, height)
        }
        runCatching {
            cls.getMethod("setSizeInternal", Float::class.javaPrimitiveType).invoke(tv, textSizePx)
        }
        runCatching {
            cls.getMethod("setAdvanceWidth", Float::class.javaPrimitiveType).invoke(tv, advanceWidth)
        }
        if (colonAdvanceWidth != null) {
            runCatching {
                cls.getMethod("setColonAdvanceWidth", Float::class.javaPrimitiveType).invoke(tv, colonAdvanceWidth)
            }
        }
        runCatching { findField(cls, "mDrawTextX")?.setFloat(tv, drawX) }
        runCatching { findField(cls, "mDrawTextY")?.setFloat(tv, drawY) }
        tv.invalidate()
    }

    // =========================================================================
    // 2. Inject Custom Fonts into AOD Lockscreen Clock Editor Font Selectors
    // =========================================================================

    private fun installAodFontSelectorInjectionHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        // A. AllInOneFontEditor
        runCatching {
            val editorClass = classLoader.loadClass("com.miui.keyguard.editor.edit.allinone.AllInOneFontEditor")
            editorClass.declaredConstructors.forEachIndexed { idx, ctor ->
                ctor.isAccessible = true
                module.hookDeoptimized(ctor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-allinone-font-editor-ctor:$idx")
                    .intercept { chain ->
                        val result = chain.proceed()
                        injectCustomFontsIntoAllInOneFontEditor(chain.thisObject, preferences)
                        result
                    }
            }
            // Hook isFontStyleSupportHollow so custom fonts gracefully disable hollow instead of breaking
            editorClass.declaredMethods
                .firstOrNull { it.name == "isFontStyleSupportHollow" && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?.let { method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-allinone-font-hollow-support")
                        .intercept { chain ->
                            val editor = chain.thisObject
                            val selected = runCatching {
                                findField(editor.javaClass, "selectedFontStyle")?.getInt(editor) ?: 21
                            }.getOrDefault(21)
                            if (selected >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) false else chain.proceed()
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "AllInOneFontEditor injection hook skipped", err)
        }

        // B. Classic / ClassicPlus / ClassicMax FontEditor
        runCatching {
            val fontEditorClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.FontEditor")
            fontEditorClass.declaredConstructors.forEachIndexed { idx, ctor ->
                ctor.isAccessible = true
                module.hookDeoptimized(ctor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-classic-font-editor-ctor:$idx")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val selectedStyle = chain.args.getOrNull(1) as? Int ?: 21
                        injectCustomFontsIntoClassicFontEditor(chain.thisObject, selectedStyle, preferences)
                        result
                    }
            }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "Classic FontEditor injection hook skipped", err)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectCustomFontsIntoAllInOneFontEditor(editor: Any, preferences: SharedPreferences) {
        val customFonts = loadRemoteCatalog(preferences)
        if (customFonts.isEmpty()) return
        runCatching {
            val adapter = findField(editor.javaClass, "fontAdapter")?.get(editor) ?: return
            val data = findField(adapter.javaClass, "data")?.get(adapter) as? MutableList<Int> ?: return
            val baseCount = data.size
            editorFontMap[editor] = customFonts
            editorBaseCountMap[editor] = baseCount
            adapterToEditorMap[adapter] = editor

            installFontAdapterHooksIfNeeded(adapter.javaClass, isAllInOne = true, preferences = preferences)

            for (i in customFonts.indices) {
                data.add(-(i + 1))
            }
            val selectedFontStyle = findField(editor.javaClass, "selectedFontStyle")?.getInt(editor) ?: 21
            val customIdx = customFonts.indexOfFirst { it.styleId == selectedFontStyle }
            if (customIdx >= 0) {
                val targetIndex = baseCount + customIdx
                adapter.javaClass.methods
                    .firstOrNull { it.name == "setSelectedIndex" && it.parameterCount == 1 }
                    ?.invoke(adapter, targetIndex)
            }
            adapter.javaClass.methods
                .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 }
                ?.invoke(adapter)
            if (customIdx >= 0) {
                val selectorView = findField(editor.javaClass, "selectorView")?.get(editor)
                val targetIndex = baseCount + customIdx
                selectorView?.javaClass?.methods
                    ?.firstOrNull { it.name == "scrollToPosition" && it.parameterCount == 1 }
                    ?.invoke(selectorView, targetIndex)
            }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Failed appending custom fonts to AllInOneFontEditor", err)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectCustomFontsIntoClassicFontEditor(
        editor: Any,
        selectedStyle: Int,
        preferences: SharedPreferences,
    ) {
        val customFonts = loadRemoteCatalog(preferences)
        if (customFonts.isEmpty()) return
        runCatching {
            val adapter = findField(editor.javaClass, "adapter")?.get(editor) ?: return
            val data = findField(adapter.javaClass, "data")?.get(adapter) as? MutableList<Int> ?: return
            val baseCount = data.size
            editorFontMap[editor] = customFonts
            editorBaseCountMap[editor] = baseCount
            adapterToEditorMap[adapter] = editor

            installFontAdapterHooksIfNeeded(adapter.javaClass, isAllInOne = false, preferences = preferences)

            for (i in customFonts.indices) {
                data.add(-(i + 1))
            }
            val customIdx = customFonts.indexOfFirst { it.styleId == selectedStyle }
            if (customIdx >= 0) {
                val targetIndex = baseCount + customIdx
                adapter.javaClass.methods
                    .firstOrNull { it.name == "setSelectedIndex" && it.parameterCount == 1 }
                    ?.invoke(adapter, targetIndex)
            }
            adapter.javaClass.methods
                .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 }
                ?.invoke(adapter)
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "Failed appending custom fonts to FontEditor", err)
        }
    }

    private fun installFontAdapterHooksIfNeeded(
        adapterClass: Class<*>,
        isAllInOne: Boolean,
        preferences: SharedPreferences,
    ) {
        if (!hookedAdapterClasses.add(adapterClass.name)) return

        // Hook onBind(View, int)
        adapterClass.declaredMethods
            .firstOrNull { it.name == "onBind" && it.parameterCount == 2 }
            ?.apply { isAccessible = true }
            ?.let { onBindMethod ->
                module.hookDeoptimized(onBindMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-font-adapter-bind:${adapterClass.simpleName}")
                    .intercept { chain ->
                        val adapter = chain.thisObject
                        val view = chain.getArg(0) as? View
                        val index = chain.getArg(1) as? Int ?: return@intercept chain.proceed()
                        val editor = adapterToEditorMap[adapter] ?: return@intercept chain.proceed()
                        val baseCount = editorBaseCountMap[editor] ?: return@intercept chain.proceed()
                        val customFonts = editorFontMap[editor] ?: return@intercept chain.proceed()
                        if (index < baseCount || index - baseCount >= customFonts.size) {
                            return@intercept chain.proceed()
                        }
                        if (view != null) {
                            val entry = customFonts[index - baseCount]
                            bindCustomFontSelectorItem(adapter, view, index, entry, preferences)
                        }
                        null
                    }
            }

        // Hook onItemSelect(View, int)
        adapterClass.declaredMethods
            .firstOrNull { it.name == "onItemSelect" && it.parameterCount == 2 }
            ?.apply { isAccessible = true }
            ?.let { onSelectMethod ->
                module.hookDeoptimized(onSelectMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-font-adapter-select:${adapterClass.simpleName}")
                    .intercept { chain ->
                        val adapter = chain.thisObject
                        val view = chain.getArg(0) as? View
                        val index = chain.getArg(1) as? Int ?: return@intercept chain.proceed()
                        val editor = adapterToEditorMap[adapter] ?: return@intercept chain.proceed()
                        val baseCount = editorBaseCountMap[editor] ?: return@intercept chain.proceed()
                        val customFonts = editorFontMap[editor] ?: return@intercept chain.proceed()
                        if (index < baseCount || index - baseCount >= customFonts.size) {
                            return@intercept chain.proceed()
                        }
                        val entry = customFonts[index - baseCount]
                        selectCustomFontSelectorItem(adapter, editor, view, index, entry, isAllInOne)
                        null
                    }
            }
    }

    private fun bindCustomFontSelectorItem(
        adapter: Any,
        itemView: View,
        index: Int,
        entry: RemoteClockFontEntry,
        preferences: SharedPreferences,
    ) {
        val selectedIndex = runCatching {
            findField(adapter.javaClass, "selectedIndex")?.getInt(adapter) ?: -1
        }.getOrDefault(-1)
        val isSelected = index == selectedIndex

        // Sync FilterIconLayout selection border & corner radius identically to OEM items
        val changedSelect = runCatching {
            itemView.javaClass.methods
                .firstOrNull { it.name == "changeSelect" && it.parameterCount == 2 }
                ?.invoke(itemView, isSelected, false)
            true
        }.getOrDefault(false)
        if (!changedSelect) {
            itemView.isSelected = isSelected
        }
        if (isSelected) {
            runCatching { findField(adapter.javaClass, "selectedItemView")?.set(adapter, itemView) }
        }
        runCatching {
            val radiusId = itemView.resources.getIdentifier(
                "kg_classic_clock_font_select_radius",
                "dimen",
                itemView.context.packageName,
            )
            if (radiusId != 0) {
                val radius = itemView.resources.getDimensionPixelSize(radiusId)
                itemView.javaClass.methods
                    .firstOrNull { it.name == "setRadius" && it.parameterCount == 1 }
                    ?.invoke(itemView, radius)
            }
        }

        // Render crisp "12" preview thumbnail in this custom font's actual Typeface
        val iconId = itemView.resources.getIdentifier(
            "drawable_selector_item_icon",
            "id",
            itemView.context.packageName,
        )
        val imageView = (if (iconId != 0) itemView.findViewById<ImageView>(iconId) else null)
            ?: findFirstImageView(itemView)
        if (imageView != null) {
            val isDark = (itemView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val tf = resolveCustomFontByStyleId(entry.styleId, preferences)?.first
            val thumb = renderCustomFontThumbnail(entry, tf, isDark)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.setImageBitmap(thumb)
            imageView.contentDescription = entry.name
        }
    }

    private fun renderCustomFontThumbnail(
        entry: RemoteClockFontEntry,
        typeface: Typeface?,
        isDark: Boolean,
    ): Bitmap {
        val key = "${entry.id}:${if (isDark) 1 else 0}"
        synchronized(fontCacheLock) {
            cachedThumbByKey[key]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fgColor = if (isDark) Color.WHITE else Color.rgb(28, 28, 30)
        val subColor = if (isDark) Color.argb(175, 255, 255, 255) else Color.argb(150, 28, 28, 30)

        // Draw "12" sample in upper/middle area optically centered by actual Path ink bounds
        val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            textSize = 84f
            fontFeatureSettings = "lnum"
            if (typeface != null) this.typeface = typeface
        }
        val samplePath = Path()
        val sampleBounds = RectF()
        samplePaint.getTextPath("12", 0, 2, 0f, 0f, samplePath)
        samplePath.computeBounds(sampleBounds, true)
        if (!sampleBounds.isEmpty && sampleBounds.width() > 1f && sampleBounds.height() > 1f) {
            val maxW = size * 0.78f
            val maxH = size * 0.50f
            val scale = min(maxW / sampleBounds.width(), maxH / sampleBounds.height()).coerceAtMost(1.35f)
            samplePaint.textSize *= scale
            samplePath.reset()
            samplePaint.getTextPath("12", 0, 2, 0f, 0f, samplePath)
            samplePath.computeBounds(sampleBounds, true)
            val dx = size * 0.5f - sampleBounds.centerX()
            val dy = size * 0.41f - sampleBounds.centerY()
            val saveCount = canvas.save()
            canvas.translate(dx, dy)
            canvas.drawPath(samplePath, samplePaint)
            canvas.restoreToCount(saveCount)
        } else {
            samplePaint.textAlign = Paint.Align.CENTER
            canvas.drawText("12", size * 0.5f, size * 0.52f, samplePaint)
        }

        // Draw compact font name below sample so multiple imported fonts are instantly distinguishable
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = subColor
            textAlign = Paint.Align.CENTER
            textSize = 22f
            this.typeface = Typeface.DEFAULT
        }
        val shortName = if (entry.name.length > 7) entry.name.take(6) + "…" else entry.name
        canvas.drawText(shortName, size * 0.5f, size * 0.86f, labelPaint)

        synchronized(fontCacheLock) {
            cachedThumbByKey[key] = bmp
        }
        return bmp
    }

    private fun selectCustomFontSelectorItem(
        adapter: Any,
        editor: Any,
        itemView: View?,
        index: Int,
        entry: RemoteClockFontEntry,
        isAllInOne: Boolean,
    ) {
        val currentIdx = runCatching {
            findField(adapter.javaClass, "selectedIndex")?.getInt(adapter) ?: -1
        }.getOrDefault(-1)
        if (currentIdx == index) return

        // Update selection visuals matching StyleSelectorAdapter.onItemSelect
        val prevView = runCatching {
            findField(adapter.javaClass, "selectedItemView")?.get(adapter) as? View
        }.getOrNull()
        if (itemView != null) {
            val handled = runCatching {
                itemView.javaClass.methods
                    .firstOrNull { it.name == "changeSelect" && it.parameterCount == 2 }
                    ?.invoke(itemView, true, true)
                prevView?.javaClass?.methods
                    ?.firstOrNull { it.name == "changeSelect" && it.parameterCount == 2 }
                    ?.invoke(prevView, false, true)
                true
            }.getOrDefault(false)
            if (!handled) {
                prevView?.isSelected = false
                itemView.isSelected = true
            }
            runCatching { findField(adapter.javaClass, "selectedItemView")?.set(adapter, itemView) }
        }
        runCatching { findField(adapter.javaClass, "selectedIndex")?.setInt(adapter, index) }

        val editCallback = runCatching {
            editor.javaClass.methods
                .firstOrNull { it.name == "getEditCallback" && it.parameterCount == 0 }
                ?.invoke(editor)
        }.getOrNull() ?: return

        if (isAllInOne) {
            runCatching { findField(editor.javaClass, "selectedFontStyle")?.setInt(editor, entry.styleId) }
            runCatching {
                editCallback.javaClass.methods
                    .firstOrNull { it.name == "onEdited" && it.parameterCount == 2 }
                    ?.invoke(editCallback, 232, entry.styleId)
            }
            runCatching {
                editor.javaClass.methods
                    .firstOrNull { it.name == "updateHollowStyleAvailability" && it.parameterCount == 0 }
                    ?.invoke(editor)
            }
            // Reset hollow to 0 (none) cleanly for custom font
            runCatching {
                val hollowAdapter = findField(editor.javaClass, "hollowAdapter")?.get(editor)
                val hollowIdx = hollowAdapter?.javaClass?.methods
                    ?.firstOrNull { it.name == "getSelectedIndex" && it.parameterCount == 0 }
                    ?.invoke(hollowAdapter) as? Int ?: 0
                if (hollowIdx > 0) {
                    findField(editor.javaClass, "restoreHollowIndexAfterFontBack")?.setInt(editor, hollowIdx)
                    editCallback.javaClass.methods
                        .firstOrNull { it.name == "onEdited" && it.parameterCount == 2 }
                        ?.invoke(editCallback, 231, 1)
                    editor.javaClass.methods
                        .firstOrNull { it.name == "select" && it.parameterCount == 2 }
                        ?.invoke(editor, hollowAdapter, 0)
                }
            }
        } else {
            runCatching {
                editCallback.javaClass.methods
                    .firstOrNull { it.name == "onEdited" && it.parameterCount == 2 }
                    ?.invoke(editCallback, 20, entry.styleId)
            }
        }
    }

    // =========================================================================
    // 3. Samsung-style Per-Glyph Clock Contour Avoidance (TimeView.onDraw)
    // =========================================================================

    private fun installTimeViewAvoidanceHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        isEditor: Boolean,
    ) {
        runCatching {
            val timeViewClass = classLoader.loadClass(TIME_VIEW_CLASS)
            val textMeasureClass = runCatching {
                classLoader.loadClass("com.miui.clock.allInOne.TextMeasureUtils")
            }.getOrNull()
            val miuiBlurClass = runCatching {
                classLoader.loadClass("com.miui.clock.utils.MiuiBlurUtils")
            }.getOrNull()

            val applyVariationMethod = textMeasureClass?.declaredMethods?.firstOrNull {
                it.name == "applyFontVariation" && it.parameterCount == 4
            }?.apply { isAccessible = true }
            val paintFirstInVariation = applyVariationMethod?.parameterTypes?.getOrNull(0) == Paint::class.java

            val simplifyPathMethod = miuiBlurClass?.declaredMethods?.firstOrNull {
                it.name == "simplifyPath" && it.parameterCount == 2
            }?.apply { isAccessible = true }
            val paintFirstInSimplify = simplifyPathMethod?.parameterTypes?.getOrNull(0) == Paint::class.java

            val setGlassEffectMethod = miuiBlurClass?.declaredMethods?.firstOrNull {
                it.name == "setPaintGlassEffect" && it.parameterCount == 2
            }?.apply { isAccessible = true }
            val glassEffectField = miuiBlurClass?.let { findField(it, "M_SET_GLASS_EFFECT") }
            val glassClipField = miuiBlurClass?.let { findField(it, "M_SET_MI_GLASS_CLIP") }
            val setMiGlassClipMethod = miuiBlurClass?.declaredMethods?.firstOrNull {
                it.name == "setMiGlassClip" && it.parameterCount == 5
            }?.apply { isAccessible = true }
            val setMiGlassMethod = miuiBlurClass?.declaredMethods?.firstOrNull {
                it.name == "setMiGlass" && it.parameterCount == 2
            }?.apply { isAccessible = true }

            val onDraw = timeViewClass.getDeclaredMethod("onDraw", Canvas::class.java)
                .apply { isAccessible = true }
            val scope = if (isEditor) "aod" else "systemui"

            module.hookDeoptimized(onDraw)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-avoidance-draw:$scope")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val canvas = chain.getArg(0) as? Canvas ?: return@intercept chain.proceed()
                    if (isEditor) {
                        activeAodTimeViews.add(view)
                    } else {
                        activeSystemUiTimeViews.add(view)
                        ensureSystemUiObserverRegistered(view.context, preferences)
                    }

                    // Ensure custom font is bound ONLY if this TimeView's mFontStyle is a custom styleId (>= 10000)
                    val fontStyle = findField(timeViewClass, "mFontStyle")?.getInt(view) ?: 21
                    val customFont = if (fontStyle >= LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) {
                        resolveCustomFontByStyleId(fontStyle, preferences)
                    } else null
                    val paint = findField(timeViewClass, "textPaint")?.get(view) as? Paint
                    if (paint != null && customFont != null && paint.typeface !== customFont.first) {
                        paint.typeface = customFont.first
                    }

                    val avoidEnabled = readLockscreenClockAvoidanceEnabled(view.context, preferences)
                    val contour = if (avoidEnabled && isEligibleForAvoidance(view, isEditor)) {
                        if (isEditor) resolveEditorContour(view) else resolveSystemUiContour(view.context)
                    } else null

                    if (contour != null && contour.hasValidSubject) {
                        val handled = runCatching {
                            drawContourAvoidedTimeView(
                                view = view,
                                timeViewClass = timeViewClass,
                                canvas = canvas,
                                contour = contour,
                                customFontAxes = customFont?.second,
                                isCustomFont = customFont != null,
                                applyVariationMethod = applyVariationMethod,
                                paintFirstInVariation = paintFirstInVariation,
                                simplifyPathMethod = simplifyPathMethod,
                                paintFirstInSimplify = paintFirstInSimplify,
                                setGlassEffectMethod = setGlassEffectMethod,
                                glassEffectField = glassEffectField,
                                glassClipField = glassClipField,
                                setMiGlassClipMethod = setMiGlassClipMethod,
                                setMiGlassMethod = setMiGlassMethod,
                            )
                        }.onFailure { err ->
                            HookModuleLogger.log(module, Log.DEBUG, logTag, "Clock avoidance draw fallback ($scope)", err)
                        }.getOrDefault(false)
                        if (handled) return@intercept null
                    }

                    // For custom fonts, bypass OEM TimeView.onDraw even when avoidance is off/inactive so
                    // HyperOS's hardcoded ('wdth' 10, 'HGHT' 50) doesn't crush third-party fonts, colons
                    // align optically, and Liquid Glass simplifies overlapping third-party bezier paths.
                    if (customFont != null) {
                        val handledCustom = runCatching {
                            drawCustomFontNormalTimeView(
                                view = view,
                                timeViewClass = timeViewClass,
                                canvas = canvas,
                                customFontAxes = customFont.second,
                                simplifyPathMethod = simplifyPathMethod,
                                paintFirstInSimplify = paintFirstInSimplify,
                                setGlassEffectMethod = setGlassEffectMethod,
                                glassEffectField = glassEffectField,
                                glassClipField = glassClipField,
                                setMiGlassClipMethod = setMiGlassClipMethod,
                                setMiGlassMethod = setMiGlassMethod,
                            )
                        }.onFailure { err ->
                            HookModuleLogger.log(module, Log.DEBUG, logTag, "Custom font draw fallback ($scope)", err)
                        }.getOrDefault(false)
                        if (handledCustom) return@intercept null
                    }

                    chain.proceed()
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Could not install TimeView avoidance hook", err)
        }
    }

    private fun isEligibleForAvoidance(timeView: View, isEditor: Boolean): Boolean {
        val parentAllInOne = findAncestorByClassName(timeView, "com.miui.clock.allInOne.AllInOneBase") ?: return false
        val inNotif = runCatching {
            findField(parentAllInOne.javaClass, "mInNotificationState")?.getBoolean(parentAllInOne) ?: false
        }.getOrDefault(false)
        if (inNotif) return false

        val isDoubleRow = runCatching {
            findField(parentAllInOne.javaClass, "mDoubleRow")?.getBoolean(parentAllInOne) ?: false
        }.getOrDefault(false)
        if (isDoubleRow) return false

        if (!isEditor) {
            val styleInfo = runCatching {
                findField(parentAllInOne.javaClass, "mClockStyleInfo")?.get(parentAllInOne)
            }.getOrNull()
            if (styleInfo != null) {
                val displayType = runCatching {
                    findField(styleInfo.javaClass, "displayType")?.getInt(styleInfo) ?: 0
                }.getOrDefault(0)
                val isAod = runCatching {
                    val infoClass = timeView.javaClass.classLoader!!.loadClass("com.miui.clock.module.ClockStyleInfo")
                    infoClass.getMethod("isAODType", Int::class.javaPrimitiveType).invoke(null, displayType) as? Boolean
                }.getOrDefault(false) == true
                if (isAod) return false
            }
        }
        return true
    }

    /**
     * Clean renderer for custom fonts (styleId >= 10000) when Clock Avoidance is off or inactive:
     * - Applies ONLY declared variable font axes clamped to real fvar ranges (never HyperOS's wdth=10 / HGHT=50).
     * - Strips HyperOS's ss01 feature and opts into lining figures (lnum) on non-HyperOS fonts.
     * - Optically centers the colon ':' vertically against digit ink height for non-HyperOS fonts.
     * - Always runs MiuiBlurUtils.simplifyPath when Liquid Glass (MiGlass) is active so third-party
     *   overlapping glyph contours never produce glass holes/artifacts.
     */
    private fun drawCustomFontNormalTimeView(
        view: View,
        timeViewClass: Class<*>,
        canvas: Canvas,
        customFontAxes: FontVariableAxesInfo?,
        simplifyPathMethod: Method?,
        paintFirstInSimplify: Boolean,
        setGlassEffectMethod: Method?,
        glassEffectField: Field?,
        glassClipField: Field?,
        setMiGlassClipMethod: Method?,
        setMiGlassMethod: Method?,
    ): Boolean {
        val text = findField(timeViewClass, "mText")?.get(view) as? String ?: return false
        if (text.isEmpty()) return false
        val paint = findField(timeViewClass, "textPaint")?.get(view) as? Paint ?: return false
        val textPath = findField(timeViewClass, "mTextPath")?.get(view) as? Path ?: return false
        val colonPath = findField(timeViewClass, "mColonPath")?.get(view) as? Path ?: return false
        val minuteOnlyPath = findField(timeViewClass, "mMinuteOnlyPath")?.get(view) as? Path ?: return false
        val textBounds = findField(timeViewClass, "mTextBounds")?.get(view) as? RectF ?: return false

        val weight = findField(timeViewClass, "weight")?.getFloat(view) ?: (customFontAxes?.weightDefault ?: 400f)
        val width = findField(timeViewClass, "width")?.getFloat(view) ?: (customFontAxes?.widthDefault ?: 100f)
        val height = findField(timeViewClass, "height")?.getFloat(view) ?: (customFontAxes?.heightDefault ?: 28f)
        val textSizePx = findField(timeViewClass, "textSizePx")?.getFloat(view) ?: 0f
        if (textSizePx <= 1f) return false

        val drawTextX = findField(timeViewClass, "mDrawTextX")?.getFloat(view) ?: 0f
        val drawTextY = findField(timeViewClass, "mDrawTextY")?.getFloat(view) ?: 0f
        val fontAlpha = findField(timeViewClass, "fontAlpha")?.getFloat(view) ?: 1f
        val colonAlpha = findField(timeViewClass, "mColonAlpha")?.getFloat(view) ?: 1f
        val colonSplitIndex = findField(timeViewClass, "mColonSplitIndex")?.getInt(view) ?: 0
        val colonAdvanceWidth = findField(timeViewClass, "mColonAdvanceWidth")?.getFloat(view) ?: 0f
        val isGlass = findField(timeViewClass, "isMiGlassEffectEnable")?.getBoolean(view) ?: false
        val glassData = findField(timeViewClass, "glassData")?.get(view) as? FloatArray

        val isHyperStandard = isHyperOsStandardClockFont(customFontAxes)
        if (!isHyperStandard) {
            val currentFeat = paint.fontFeatureSettings
            if (currentFeat == null || currentFeat.contains("ss01")) {
                paint.fontFeatureSettings = if (currentFeat?.contains("tnum") == true) "lnum, tnum" else "lnum"
            }
        }

        applyCustomFontVariation(paint, customFontAxes, weight, width, height)
        applyPaintGlassEffect(paint, isGlass, setGlassEffectMethod, glassEffectField)
        paint.textSize = textSizePx

        val splitActive = colonSplitIndex > 0 && colonAlpha < 1f
        val colonPos = text.indexOf(':')
        val shouldOpticalCenterColon = !isHyperStandard && colonPos >= 0

        if (shouldOpticalCenterColon) {
            val zeroBounds = measureTextInkBounds(paint, "0")
            val digitCenterY = if (!zeroBounds.isEmpty) zeroBounds.centerY() else 0f
            val digitH = zeroBounds.height().coerceAtLeast(1f)

            val singleColonPath = Path()
            paint.getTextPath(":", 0, 1, 0f, 0f, singleColonPath)
            val colonInk = RectF()
            singleColonPath.computeBounds(colonInk, true)
            var colonDy = if (!zeroBounds.isEmpty && !colonInk.isEmpty) {
                digitCenterY - colonInk.centerY()
            } else 0f
            if (abs(colonDy) <= digitH * 0.03f) {
                colonDy = 0f
            } else {
                colonDy = colonDy.coerceIn(-digitH * 0.35f, digitH * 0.35f)
            }

            if (text == ":") {
                textPath.reset()
                textPath.addPath(singleColonPath)
                if (colonDy != 0f) textPath.offset(0f, colonDy)
            } else if (colonPos == 0) {
                val advColon = if (colonAdvanceWidth > 0f) colonAdvanceWidth else paint.measureText(":")
                colonPath.reset()
                colonPath.addPath(singleColonPath)
                if (colonDy != 0f) colonPath.offset(0f, colonDy)

                minuteOnlyPath.reset()
                if (text.length > 1) {
                    paint.getTextPath(text, 1, text.length, 0f, 0f, minuteOnlyPath)
                }

                textPath.reset()
                textPath.addPath(colonPath)
                if (text.length > 1) {
                    val shiftedMin = Path(minuteOnlyPath)
                    shiftedMin.offset(advColon, 0f)
                    textPath.addPath(shiftedMin)
                }
            } else {
                val beforeW = paint.measureText(text, 0, colonPos)
                val colonW = paint.measureText(text, colonPos, colonPos + 1)
                textPath.reset()
                val tmp = Path()
                paint.getTextPath(text, 0, colonPos, 0f, 0f, tmp)
                textPath.addPath(tmp)
                singleColonPath.offset(beforeW, colonDy)
                textPath.addPath(singleColonPath)
                if (colonPos + 1 < text.length) {
                    tmp.reset()
                    paint.getTextPath(text, colonPos + 1, text.length, beforeW + colonW, 0f, tmp)
                    textPath.addPath(tmp)
                }
                if (splitActive) {
                    colonPath.reset()
                    paint.getTextPath(text, 0, colonSplitIndex, 0f, 0f, colonPath)
                    minuteOnlyPath.reset()
                    paint.getTextPath(text, colonSplitIndex, text.length, 0f, 0f, minuteOnlyPath)
                }
            }
        } else {
            textPath.reset()
            paint.getTextPath(text, 0, text.length, 0f, 0f, textPath)
            if (splitActive) {
                colonPath.reset()
                paint.getTextPath(text, 0, colonSplitIndex, 0f, 0f, colonPath)
                minuteOnlyPath.reset()
                paint.getTextPath(text, colonSplitIndex, text.length, 0f, 0f, minuteOnlyPath)
            }
        }

        if (isGlass && simplifyPathMethod != null) {
            invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, textPath)
            if (splitActive) {
                invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, colonPath)
                invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, minuteOnlyPath)
            }
        }

        textPath.computeBounds(textBounds, true)
        findField(timeViewClass, "textTop")?.setInt(view, (textBounds.top + drawTextY).toInt())

        if (isGlass && glassData != null) {
            val clipRect = RectF(textBounds)
            clipRect.offset(drawTextX, drawTextY)
            invokeGlassClip(
                view = view,
                left = clipRect.left - 50f,
                top = clipRect.top - 50f,
                right = clipRect.right + 50f,
                bottom = clipRect.bottom + 50f,
                glassClipField = glassClipField,
                setMiGlassClipMethod = setMiGlassClipMethod,
            )
            runCatching { setMiGlassMethod?.invoke(null, view, glassData) }
        }

        if (glassData != null && glassData.size > 20) {
            paint.strokeWidth = glassData[19]
            paint.strokeMiter = glassData[20]
        }

        val saveCount = canvas.save()
        try {
            if (!splitActive) {
                paint.alpha = (fontAlpha * 255f).roundToInt().coerceIn(0, 255)
                canvas.translate(drawTextX, drawTextY)
                canvas.drawPath(textPath, paint)
            } else {
                canvas.translate(drawTextX, drawTextY)
                if (colonAlpha > 0f) {
                    paint.alpha = (fontAlpha * colonAlpha * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawPath(colonPath, paint)
                }
                paint.alpha = (fontAlpha * 255f).roundToInt().coerceIn(0, 255)
                canvas.translate(colonAdvanceWidth, 0f)
                canvas.drawPath(minuteOnlyPath, paint)
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
        return true
    }

    /**
     * Unified logical-canvas per-glyph contour avoidance:
     * - Strictly obeys the user's chosen clock box length (no digit EVER stretches past userBottom).
     * - Works identically in both AOD Editor (scaled preview card) and SystemUI Lockscreen because
     *   both use TimeView's unscaled logical viewport dimensions (view.width x view.height).
     * - Uses calibrated per-font HGHT shortening (<= baseHght) with 1-step measured-bounds refinement
     *   to keep stroke weights uniform and prevent broken/morphed glyphs on thin/MiHaus fonts.
     */
    private fun drawContourAvoidedTimeView(
        view: View,
        timeViewClass: Class<*>,
        canvas: Canvas,
        contour: SubjectContourProfile,
        customFontAxes: FontVariableAxesInfo?,
        isCustomFont: Boolean,
        applyVariationMethod: Method?,
        paintFirstInVariation: Boolean,
        simplifyPathMethod: Method?,
        paintFirstInSimplify: Boolean,
        setGlassEffectMethod: Method?,
        glassEffectField: Field?,
        glassClipField: Field?,
        setMiGlassClipMethod: Method?,
        setMiGlassMethod: Method?,
    ): Boolean {
        val text = findField(timeViewClass, "mText")?.get(view) as? String ?: return false
        if (text.isEmpty()) return false
        val paint = findField(timeViewClass, "textPaint")?.get(view) as? Paint ?: return false
        val textPath = findField(timeViewClass, "mTextPath")?.get(view) as? Path ?: return false
        val colonPath = findField(timeViewClass, "mColonPath")?.get(view) as? Path ?: return false
        val minuteOnlyPath = findField(timeViewClass, "mMinuteOnlyPath")?.get(view) as? Path ?: return false
        val textBounds = findField(timeViewClass, "mTextBounds")?.get(view) as? RectF ?: return false

        val weight = findField(timeViewClass, "weight")?.getFloat(view) ?: 700f
        val width = findField(timeViewClass, "width")?.getFloat(view) ?: 100f
        val baseHght = findField(timeViewClass, "height")?.getFloat(view) ?: 28f
        val textSizePx = findField(timeViewClass, "textSizePx")?.getFloat(view) ?: 0f
        if (textSizePx <= 1f) return false

        // Use TimeView's own logical full-screen canvas size so Editor & SystemUI match 1:1
        val canvasW = view.width.takeIf { it > 0 }?.toFloat()
            ?: view.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val canvasH = view.height.takeIf { it > 0 }?.toFloat()
            ?: view.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)

        val drawTextX = findField(timeViewClass, "mDrawTextX")?.getFloat(view) ?: 0f
        val drawTextY = findField(timeViewClass, "mDrawTextY")?.getFloat(view) ?: 0f
        val fontAlpha = findField(timeViewClass, "fontAlpha")?.getFloat(view) ?: 1f
        val colonAlpha = findField(timeViewClass, "mColonAlpha")?.getFloat(view) ?: 1f
        val colonSplitIndex = findField(timeViewClass, "mColonSplitIndex")?.getInt(view) ?: 0
        val colonAdvanceWidth = findField(timeViewClass, "mColonAdvanceWidth")?.getFloat(view) ?: 0f
        val fontStyle = findField(timeViewClass, "mFontStyle")?.getInt(view) ?: 21
        val isGlass = findField(timeViewClass, "isMiGlassEffectEnable")?.getBoolean(view) ?: false
        val glassData = findField(timeViewClass, "glassData")?.get(view) as? FloatArray

        // Include any horizontal translationX on TimeView / parent AllInOneBase for exact X alignment
        val parentClock = findAncestorByClassName(view, "com.miui.clock.allInOne.AllInOneBase")
        val extraTx = view.translationX + (parentClock?.translationX ?: 0f)

        applyPaintGlassEffect(paint, isGlass, setGlassEffectMethod, glassEffectField)
        paint.textSize = textSizePx
        val supportsHghtAxis = !isCustomFont || (customFontAxes?.hasHeightAxis == true)
        val hghtMin = if (isCustomFont && customFontAxes != null) customFontAxes.heightMin else 10f

        if (isCustomFont) {
            if (!isHyperOsStandardClockFont(customFontAxes)) {
                val currentFeat = paint.fontFeatureSettings
                if (currentFeat == null || currentFeat.contains("ss01")) {
                    paint.fontFeatureSettings = if (currentFeat?.contains("tnum") == true) "lnum, tnum" else "lnum"
                }
            }
            applyCustomFontVariation(paint, customFontAxes, weight, width, baseHght)
        } else {
            invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, baseHght)
        }

        // Measure reference digit "0" at base params so mHourView & mMinuteView share an identical top & max height
        val refPath = Path()
        val refBounds = RectF()
        paint.getTextPath("0", 0, 1, 0f, 0f, refPath)
        refPath.computeBounds(refBounds, true)
        val refTop = refBounds.top
        val baseDigitHeight = refBounds.height().coerceAtLeast(24f)

        val clockTopInCanvas = drawTextY + refTop
        // STRICT ceiling: never stretch below the user's dragged clock box bottom!
        val userClockBottom = clockTopInCanvas + baseDigitHeight

        // Measure minimum natural height achievable at hghtMin for calibrated interpolation
        val minAxisHeight = if (supportsHghtAxis && baseHght > hghtMin + 0.5f) {
            if (isCustomFont) {
                applyCustomFontVariation(paint, customFontAxes, weight, width, hghtMin)
            } else {
                invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, hghtMin)
            }
            refPath.reset()
            paint.getTextPath("0", 0, 1, 0f, 0f, refPath)
            refPath.computeBounds(refBounds, true)
            val h = refBounds.height().coerceAtLeast(16f)
            if (isCustomFont) {
                applyCustomFontVariation(paint, customFontAxes, weight, width, baseHght)
            } else {
                invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, baseHght)
            }
            h
        } else {
            baseDigitHeight
        }

        // Compute per-character advance widths at base variation
        val advances = FloatArray(text.length)
        paint.getTextWidths(text, advances)

        val density = view.resources.displayMetrics.density
        val marginPx = 12f * density
        // Keep shortened digits comfortably legible (at least 42% of user box height and >= 46dp)
        val minAllowedHeight = min(baseDigitHeight, max(baseDigitHeight * 0.42f, 46f * density))

        // Check whether ANY part of the full clock box actually intersects the subject silhouette
        val fullClockSpan = resolveFullClockHorizontalSpan(parentClock, drawTextX + extraTx, advances.sum(), canvasW)
        val spanSubjectY = contour.percentileYInRange(
            (fullClockSpan.first / canvasW).coerceIn(0f, 1f),
            (fullClockSpan.second / canvasW).coerceIn(0f, 1f),
            percentile = 0.08f,
        ) * canvasH
        if (spanSubjectY < clockTopInCanvas + 36f * density) {
            // Subject occupies the very top of the clock -> fall back to unmodified OEM clock
            return false
        }
        if (spanSubjectY - marginPx >= userClockBottom) {
            // User's clock box is already completely above the subject -> zero distortion needed!
            return false
        }

        // Compute per-character target heights strictly within [minAllowedHeight, baseDigitHeight]
        val charTargets = FloatArray(text.length)
        var anyShortened = false
        var cursorX = 0f
        for (idx in text.indices) {
            val adv = advances[idx]
            val charLeft = drawTextX + extraTx + cursorX
            // Sample the inner core [14%..86%] of each digit using 15th percentile to avoid choppy edge spikes
            val inset = adv * 0.14f
            val subY = contour.percentileYInRange(
                ((charLeft + inset) / canvasW).coerceIn(0f, 1f),
                ((charLeft + adv - inset) / canvasW).coerceIn(0f, 1f),
                percentile = 0.15f,
            ) * canvasH
            val desiredBottom = min(userClockBottom, subY - marginPx)
            val targetH = (desiredBottom - clockTopInCanvas).coerceIn(minAllowedHeight, baseDigitHeight)
            if (targetH < baseDigitHeight - 2f) {
                anyShortened = true
            }
            charTargets[idx] = targetH
            cursorX += adv
        }

        if (!anyShortened) {
            return false
        }

        // Smooth colon against adjacent digits so it stays cleanly lifted above peaks
        for (idx in text.indices) {
            if (text[idx] == ':') {
                val leftNeighborH = if (idx > 0) charTargets[idx - 1] else {
                    val probeW = advances[idx].coerceAtLeast(16f) * 1.6f
                    val subY = contour.percentileYInRange(
                        ((drawTextX + extraTx - probeW) / canvasW).coerceIn(0f, 1f),
                        ((drawTextX + extraTx) / canvasW).coerceIn(0f, 1f),
                        percentile = 0.15f,
                    ) * canvasH
                    (min(userClockBottom, subY - marginPx) - clockTopInCanvas)
                        .coerceIn(minAllowedHeight, baseDigitHeight)
                }
                val rightNeighborH = if (idx + 1 < text.length) charTargets[idx + 1] else leftNeighborH
                val neighborCap = (leftNeighborH + rightNeighborH) * 0.5f
                charTargets[idx] = min(charTargets[idx], neighborCap).coerceIn(minAllowedHeight, baseDigitHeight)
            }
        }

        // Build combined Path (plus split colon/minute paths when colon fade animation is active)
        textPath.reset()
        val splitActive = colonSplitIndex > 0 && colonAlpha < 1f
        if (splitActive) {
            colonPath.reset()
            minuteOnlyPath.reset()
        }

        val charPath = Path()
        val charBounds = RectF()
        val transformMatrix = Matrix()
        cursorX = 0f

        for (idx in text.indices) {
            val ch = text[idx]
            val targetH = charTargets[idx]
            charPath.reset()

            if (supportsHghtAxis && baseHght > hghtMin + 0.5f) {
                // Calibrated linear solve between (hghtMin -> minAxisHeight) and (baseHght -> baseDigitHeight)
                val spanH = (baseDigitHeight - minAxisHeight).coerceAtLeast(1f)
                val t = ((targetH - minAxisHeight) / spanH).coerceIn(0f, 1f)
                var chosenHght = hghtMin + t * (baseHght - hghtMin)
                if (isCustomFont) {
                    applyCustomFontVariation(paint, customFontAxes, weight, width, chosenHght)
                } else {
                    invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, chosenHght)
                }
                paint.getTextPath(text, idx, idx + 1, 0f, 0f, charPath)
                charPath.computeBounds(charBounds, true)

                // Single refinement pass if font curve is non-linear (e.g. piecewise slope at HGHT=50)
                if (ch != ':' && !charBounds.isEmpty && charBounds.height() > 1f &&
                    targetH >= minAxisHeight && abs(charBounds.height() - targetH) > 4f
                ) {
                    val errRatio = targetH / charBounds.height()
                    val refined = (chosenHght * errRatio).coerceIn(hghtMin, baseHght)
                    if (abs(refined - chosenHght) > 0.3f) {
                        chosenHght = refined
                        if (isCustomFont) {
                            applyCustomFontVariation(paint, customFontAxes, weight, width, chosenHght)
                        } else {
                            invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, chosenHght)
                        }
                        charPath.reset()
                        paint.getTextPath(text, idx, idx + 1, 0f, 0f, charPath)
                        charPath.computeBounds(charBounds, true)
                    }
                }
            } else {
                paint.getTextPath(text, idx, idx + 1, 0f, 0f, charPath)
                charPath.computeBounds(charBounds, true)
            }

            if (!charBounds.isEmpty && charBounds.height() > 1f) {
                if (ch == ':') {
                    val actualH = charBounds.height()
                    val maxColonH = targetH * 0.72f
                    val scaleY = if (actualH > maxColonH) (maxColonH / actualH).coerceIn(0.65f, 1f) else 1f
                    transformMatrix.reset()
                    if (scaleY < 0.99f) {
                        transformMatrix.setScale(1f, scaleY, 0f, charBounds.top)
                        charPath.transform(transformMatrix)
                        charPath.computeBounds(charBounds, true)
                    }
                    val desiredColonTop = refTop + (targetH - charBounds.height()) * 0.45f
                    transformMatrix.reset()
                    transformMatrix.setTranslate(cursorX, desiredColonTop - charBounds.top)
                    charPath.transform(transformMatrix)
                } else {
                    val actualH = charBounds.height()
                    val ratio = targetH / actualH
                    // Only apply gentle corrective scaleY if targetH fell below hghtMin (or for static fonts)
                    val scaleY = if (supportsHghtAxis) {
                        if (ratio < 0.97f) ratio.coerceIn(0.72f, 1f)
                        else if (ratio > 1.03f) ratio.coerceIn(1f, 1.06f)
                        else 1f
                    } else {
                        ratio.coerceIn(0.68f, 1f)
                    }
                    transformMatrix.reset()
                    if (abs(scaleY - 1f) > 0.01f) {
                        transformMatrix.setScale(1f, scaleY, 0f, charBounds.top)
                        charPath.transform(transformMatrix)
                        charPath.computeBounds(charBounds, true)
                    }
                    // Pin top of every digit cleanly to refTop
                    transformMatrix.reset()
                    transformMatrix.setTranslate(cursorX, refTop - charBounds.top)
                    charPath.transform(transformMatrix)
                }
            }

            textPath.addPath(charPath)
            if (splitActive) {
                if (idx < colonSplitIndex) {
                    colonPath.addPath(charPath)
                } else {
                    val shifted = Path(charPath)
                    shifted.offset(-colonAdvanceWidth, 0f)
                    minuteOnlyPath.addPath(shifted)
                }
            }
            cursorX += advances[idx]
        }

        if (supportsHghtAxis) {
            if (isCustomFont) {
                applyCustomFontVariation(paint, customFontAxes, weight, width, baseHght)
            } else {
                invokeApplyFontVariation(applyVariationMethod, paintFirstInVariation, paint, weight, width, baseHght)
            }
        }

        val needSimplify = isGlass && (isCustomFont || fontStyle == 23 || fontStyle == 24 || fontStyle == 25)
        if (needSimplify && simplifyPathMethod != null) {
            invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, textPath)
            if (splitActive) {
                invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, colonPath)
                invokeSimplifyPath(simplifyPathMethod, paintFirstInSimplify, paint, minuteOnlyPath)
            }
        }

        textPath.computeBounds(textBounds, true)
        findField(timeViewClass, "textTop")?.setInt(view, (textBounds.top + drawTextY).toInt())

        if (isGlass && glassData != null) {
            val clipRect = RectF(textBounds)
            clipRect.offset(drawTextX, drawTextY)
            invokeGlassClip(
                view = view,
                left = clipRect.left - 50f,
                top = clipRect.top - 50f,
                right = clipRect.right + 50f,
                bottom = clipRect.bottom + 50f,
                glassClipField = glassClipField,
                setMiGlassClipMethod = setMiGlassClipMethod,
            )
            runCatching { setMiGlassMethod?.invoke(null, view, glassData) }
        }

        if (glassData != null && glassData.size > 20) {
            paint.strokeWidth = glassData[19]
            paint.strokeMiter = glassData[20]
        }

        val saveCount = canvas.save()
        try {
            if (!splitActive) {
                paint.alpha = (fontAlpha * 255f).roundToInt().coerceIn(0, 255)
                canvas.translate(drawTextX, drawTextY)
                canvas.drawPath(textPath, paint)
            } else {
                canvas.translate(drawTextX, drawTextY)
                if (colonAlpha > 0f) {
                    paint.alpha = (fontAlpha * colonAlpha * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawPath(colonPath, paint)
                }
                paint.alpha = (fontAlpha * 255f).roundToInt().coerceIn(0, 255)
                canvas.translate(colonAdvanceWidth, 0f)
                canvas.drawPath(minuteOnlyPath, paint)
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
        return true
    }

    private fun resolveFullClockHorizontalSpan(
        allInOneBase: View?,
        fallbackLeft: Float,
        fallbackWidth: Float,
        canvasWidth: Float,
    ): Pair<Float, Float> {
        if (allInOneBase != null) {
            val rect = runCatching {
                findField(allInOneBase.javaClass, "mClockViewRect")?.get(allInOneBase) as? Rect
            }.getOrNull()
            if (rect != null && rect.width() > 0) {
                val tx = allInOneBase.translationX
                val l = (rect.left + tx).coerceIn(0f, canvasWidth)
                val r = (rect.right + tx).coerceIn(l + 1f, canvasWidth)
                return l to r
            }
        }
        val left = (fallbackLeft - fallbackWidth * 0.4f).coerceIn(0f, canvasWidth)
        val right = (fallbackLeft + fallbackWidth * 1.4f).coerceIn(left + 1f, canvasWidth)
        return left to right
    }

    // =========================================================================
    // 4. SystemUI Depth Conflict Suppression & Contour Mask Loading
    // =========================================================================

    private fun installSystemUiDepthConflictHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val interactorClass = classLoader.loadClass("com.android.keyguard.depth.KeyguardDepthInteractor")
            interactorClass.declaredMethods
                .filter {
                    it.name in setOf(
                        "updateDeductedImageView",
                        "updateAvoidStatus",
                        "updateDepthViewVisibility",
                        "setActualDisplayDepth",
                    )
                }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-avoid-depth-interactor:${method.name}:$idx")
                        .intercept { chain ->
                            val interactor = chain.thisObject
                            sysUiDepthInteractorRef = WeakReference(interactor)
                            val ctx = runCatching {
                                findField(interactorClass, "context")?.get(interactor) as? Context
                            }.getOrNull()
                            val result = chain.proceed()
                            if (readLockscreenClockAvoidanceEnabled(ctx, preferences)) {
                                suppressSystemUiDepthForeground(interactor)
                                if (ctx != null) scheduleSystemUiContourReload(ctx, force = false)
                            }
                            result
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "KeyguardDepthInteractor hook partial/skipped", err)
        }

        runCatching {
            val panelClass = classLoader.loadClass("com.android.keyguard.panel.KeyguardPanelViewController")
            panelClass.declaredMethods
                .filter { it.name == "updateShowDepthState" }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-avoid-panel-depth:$idx")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val panel = chain.thisObject
                            val interactor = runCatching {
                                findField(panelClass, "keyguardDepthInteractor")?.get(panel)
                            }.getOrNull()
                            if (interactor != null) {
                                sysUiDepthInteractorRef = WeakReference(interactor)
                                val ctx = runCatching {
                                    findField(interactor.javaClass, "context")?.get(interactor) as? Context
                                }.getOrNull()
                                if (readLockscreenClockAvoidanceEnabled(ctx, preferences)) {
                                    suppressSystemUiDepthForeground(interactor)
                                    if (ctx != null) scheduleSystemUiContourReload(ctx, force = false)
                                }
                            }
                            result
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "KeyguardPanelViewController depth hook skipped", err)
        }
    }

    private fun suppressSystemUiDepthForeground(interactor: Any) {
        runCatching {
            listOf("deductedImageView", "mDeductedImageView", "foregroundTextureView", "keyguardForegroundLayer").forEach { fieldName ->
                val v = findField(interactor.javaClass, fieldName)?.get(interactor) as? View
                if (v != null && v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    v.alpha = 0f
                }
            }
            val holder = findField(interactor.javaClass, "videoDepthSurfaceHolder")?.get(interactor)
            if (holder != null) {
                val fg = findField(holder.javaClass, "foregroundTextureView")?.get(holder) as? View
                if (fg != null) {
                    fg.visibility = View.GONE
                    fg.alpha = 0f
                }
            }
        }
    }

    private fun restoreSystemUiDepthForeground(interactor: Any) {
        runCatching {
            val depthEnabled = findField(interactor.javaClass, "depthEffectEnableInner")
                ?.getBoolean(interactor) ?: false
            if (depthEnabled) {
                findField(interactor.javaClass, "deductedImageView")?.get(interactor)?.let { v ->
                    if (v is View) {
                        v.visibility = View.VISIBLE
                        v.alpha = 1f
                    }
                }
            }
            interactor.javaClass.declaredMethods
                .firstOrNull { it.name == "updateDeductedImageView" && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?.invoke(interactor)
        }
    }

    private fun ensureSystemUiObserverRegistered(context: Context, preferences: SharedPreferences) {
        if (sysUiObserverRegistered) return
        sysUiObserverRegistered = true
        val appContext = context.applicationContext ?: context
        runCatching {
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val enabled = readLockscreenClockAvoidanceEnabled(appContext, preferences)
                    val interactor = sysUiDepthInteractorRef?.get()
                    if (enabled) {
                        interactor?.let(::suppressSystemUiDepthForeground)
                        scheduleSystemUiContourReload(appContext, force = true)
                    } else {
                        interactor?.let(::restoreSystemUiDepthForeground)
                        invalidateTrackedTimeViews(activeSystemUiTimeViews)
                    }
                }
            }
            val cr = appContext.contentResolver
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_SYSTEM),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_ENABLED_SYSTEM),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("constant_template_editor_info"),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("wallpaper_effect_type_2"),
                false,
                observer,
            )
        }
        scheduleSystemUiContourReload(appContext, force = true)
    }

    private fun resolveSystemUiContour(context: Context): SubjectContourProfile? {
        val current = sysUiContour
        val candidatePath = resolveCropSubjectPath(context)
        if (candidatePath != null) {
            val file = File(candidatePath)
            val mtime = if (file.isFile) file.lastModified() else -1L
            if (current == null || sysUiCropPathLoaded != candidatePath || sysUiCropMtimeLoaded != mtime) {
                scheduleSystemUiContourReload(context, force = false)
            }
        }
        return sysUiContour
    }

    private fun resolveCropSubjectPath(context: Context): String? {
        val fromSettings = runCatching {
            val raw = Settings.Secure.getString(context.contentResolver, "constant_template_editor_info")
                ?.takeIf { it.isNotBlank() }
                ?: Settings.Secure.getString(context.contentResolver, "constant_lockscreen_info")
            if (raw.isNullOrBlank()) return@runCatching null
            val root = JSONObject(raw)
            val lock = root.optJSONObject("lockscreenInfo") ?: root
            val wp = lock.optJSONObject("wallpaperInfo")
            wp?.optString("cropSubject")?.takeIf { it.isNotBlank() && File(it).isFile }
        }.getOrNull()
        if (fromSettings != null) return fromSettings

        val uid = runCatching {
            UserHandle::class.java.getMethod("myUserId").invoke(null) as? Int
        }.getOrDefault(0) ?: 0
        val canonical = "/data/system/theme_magic/users/$uid/wallpaper/crop_subject"
        return canonical.takeIf { File(it).isFile }
    }

    private fun scheduleSystemUiContourReload(context: Context, force: Boolean) {
        bgExecutor.execute {
            runCatching {
                val path = resolveCropSubjectPath(context)
                if (path == null) {
                    sysUiContour = null
                    sysUiCropPathLoaded = ""
                    sysUiCropMtimeLoaded = -1L
                    mainHandler.post { invalidateTrackedTimeViews(activeSystemUiTimeViews) }
                    return@runCatching
                }
                val file = File(path)
                val mtime = file.lastModified()
                if (!force && sysUiCropPathLoaded == path && sysUiCropMtimeLoaded == mtime && sysUiContour != null) {
                    return@runCatching
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching
                val sample = max(1, min(opts.outWidth / 240, opts.outHeight / 420))
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(path, decodeOpts) ?: return@runCatching
                try {
                    val profile = SubjectContourProfile.fromBitmap(bmp, null, bmp.width, bmp.height)
                    sysUiContour = profile
                    sysUiCropPathLoaded = path
                    sysUiCropMtimeLoaded = mtime
                } finally {
                    bmp.recycle()
                }
                mainHandler.post { invalidateTrackedTimeViews(activeSystemUiTimeViews) }
            }.onFailure { err ->
                HookModuleLogger.log(module, Log.DEBUG, logTag, "SystemUI contour reload failed", err)
            }
        }
    }

    // =========================================================================
    // 5. AOD Font Tab Switch Injection (Clock Avoidance + Horizontal Move)
    // =========================================================================

    private fun installAodFontTabSwitchHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        scope: String,
    ) {
        runCatching {
            val fontFilterClass = classLoader.loadClass("com.miui.keyguard.editor.view.FontFilterView")
            val initBase = fontFilterClass.declaredMethods.firstOrNull {
                it.name == "initBaseConfig" && it.parameterCount == 2
            } ?: return@runCatching
            initBase.isAccessible = true
            module.hookDeoptimized(initBase)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-font-tab-switches:$scope")
                .intercept { chain ->
                    val result = chain.proceed()
                    val filterView = chain.thisObject as? ViewGroup ?: return@intercept result
                    val target = chain.getArg(0)?.toString().orEmpty()
                    if (target == "SECONDARY_PART") {
                        filterView.findViewWithTag<View>(INJECTED_AVOID_ROW_TAG)?.visibility = View.GONE
                        filterView.findViewWithTag<View>(INJECTED_HORIZONTAL_ROW_TAG)?.visibility = View.GONE
                        return@intercept result
                    }
                    injectOrUpdateSwitchRow(
                        filterView = filterView,
                        classLoader = classLoader,
                        rowTag = INJECTED_AVOID_ROW_TAG,
                        label = localizedClockAvoidanceLabel(),
                        checked = readLockscreenClockAvoidanceEnabled(filterView.context, preferences),
                        registry = aodAvoidSwitches,
                        onToggle = { enabled -> onAodClockAvoidanceToggled(filterView, enabled) },
                    )
                    injectOrUpdateSwitchRow(
                        filterView = filterView,
                        classLoader = classLoader,
                        rowTag = INJECTED_HORIZONTAL_ROW_TAG,
                        label = localizedHorizontalMoveLabel(),
                        checked = readLockscreenClockHorizontalEnabled(filterView.context, preferences),
                        registry = aodHorizontalSwitches,
                        onToggle = { enabled -> onAodHorizontalMoveToggled(filterView, enabled) },
                    )
                    // If Clock Avoidance is already active when Font Tab opens, enforce Depth OFF immediately
                    if (readLockscreenClockAvoidanceEnabled(filterView.context, preferences)) {
                        disableEditorDepthFromViewModel(filterView)
                    }
                    result
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Could not hook FontFilterView ($scope)", err)
        }
    }

    private fun injectOrUpdateSwitchRow(
        filterView: ViewGroup,
        classLoader: ClassLoader,
        rowTag: String,
        label: String,
        checked: Boolean,
        registry: MutableMap<View, CompoundButton>,
        onToggle: (Boolean) -> Unit,
    ) {
        runCatching {
            val container = findField(filterView.javaClass, "fontFilterContainer")?.get(filterView) as? LinearLayout
                ?: return
            val existingRow = container.findViewWithTag<View>(rowTag)
            if (existingRow != null) {
                existingRow.visibility = View.VISIBLE
                registry[existingRow]?.let { sw ->
                    aodSuppressSwitchCallback = true
                    try {
                        sw.isChecked = checked
                    } finally {
                        aodSuppressSwitchCallback = false
                    }
                }
                return
            }

            val context = filterView.context
            val splitContainer = findField(filterView.javaClass, "splitContainer")?.get(filterView) as? RelativeLayout
            val splitTitle = findField(filterView.javaClass, "splitTitle")?.get(filterView) as? TextView

            val row = RelativeLayout(context).apply {
                tag = rowTag
                isFocusable = true
                isClickable = true
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                val splitLp = splitContainer?.layoutParams as? ViewGroup.MarginLayoutParams
                if (splitLp != null) {
                    lp.topMargin = splitLp.topMargin
                    lp.marginStart = splitLp.marginStart
                    lp.marginEnd = splitLp.marginEnd
                } else {
                    val h = dp(filterView, 24f).roundToInt()
                    lp.topMargin = dp(filterView, 16f).roundToInt()
                    lp.marginStart = h
                    lp.marginEnd = h
                }
                layoutParams = lp
                val padTop = splitContainer?.paddingTop ?: dp(filterView, 10f).roundToInt()
                val padBottom = splitContainer?.paddingBottom ?: dp(filterView, 10f).roundToInt()
                setPaddingRelative(0, padTop, 0, padBottom)
            }

            val titleView = TextView(context).apply {
                text = label
                if (splitTitle != null) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, splitTitle.textSize)
                    setTextColor(splitTitle.textColors)
                    typeface = splitTitle.typeface
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                layoutParams = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
            }
            row.addView(titleView)

            val switchWidget: CompoundButton = runCatching {
                val slidingClass = classLoader.loadClass("miuix.slidingwidget.widget.SlidingSwitch")
                slidingClass.getConstructor(Context::class.java).newInstance(context) as CompoundButton
            }.getOrElse {
                android.widget.Switch(context)
            }
            switchWidget.layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            switchWidget.contentDescription = label
            aodSuppressSwitchCallback = true
            try {
                switchWidget.isChecked = checked
            } finally {
                aodSuppressSwitchCallback = false
            }
            switchWidget.setOnCheckedChangeListener { _, isChecked ->
                if (aodSuppressSwitchCallback) return@setOnCheckedChangeListener
                onToggle(isChecked)
            }
            row.addView(switchWidget)
            row.setOnClickListener { switchWidget.performClick() }

            container.addView(row)
            registry[row] = switchWidget
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Failed injecting switch row ($rowTag)", err)
        }
    }

    private fun onAodClockAvoidanceToggled(filterView: View, enabled: Boolean) {
        writeLockscreenClockAvoidanceSystem(filterView.context, enabled)
        if (enabled) {
            disableEditorDepthFromViewModel(filterView)
            hideEditorHierarchyLayer()
        }
        aodCachedContourKey = 0L
        invalidateTrackedTimeViews(activeAodTimeViews)
    }

    private fun onAodHorizontalMoveToggled(filterView: View, enabled: Boolean) {
        writeLockscreenClockHorizontalEnabledSystem(filterView.context, enabled)
        if (!enabled) {
            writeLockscreenClockHorizontalOffset(filterView.context, 0f)
        }
        invalidateTrackedTimeViews(activeAodTimeViews)
    }

    private fun disableEditorDepthFromViewModel(owner: Any) {
        runCatching {
            val viewModel = findField(owner.javaClass, "viewModel")?.get(owner) ?: run {
                val editor = aodActiveEditorRef?.get() ?: return
                val fragment = editor.javaClass.methods
                    .firstOrNull { it.name == "getEditFragment" && it.parameterCount == 0 }
                    ?.invoke(editor) ?: return
                val vmCompanion = editor.javaClass.classLoader!!
                    .loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")
                val companionInstance = editor.javaClass.classLoader!!
                    .loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel")
                    .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                vmCompanion.methods
                    .firstOrNull { it.name == "getViewModel" && it.parameterCount == 1 }
                    ?.invoke(companionInstance, fragment)
            } ?: return

            val statusWrapper = viewModel.javaClass.methods
                .firstOrNull { it.name == "getHierarchyStatus" && it.parameterCount == 0 }
                ?.invoke(viewModel)
            val currentStatus = statusWrapper?.javaClass?.methods
                ?.firstOrNull { it.name == "getIntValue" && it.parameterCount == 0 }
                ?.invoke(statusWrapper) as? Int ?: 2
            val disableStatus = when (currentStatus) {
                1, 3 -> 2 // static image depth OFF
                5 -> 6    // video depth OFF
                else -> -1
            }
            if (disableStatus != -1) {
                aodAutoDisablingDepth = true
                try {
                    invokeUpdateHierarchyStatus(viewModel, disableStatus)
                } finally {
                    aodAutoDisablingDepth = false
                }
            }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "Could not auto-disable editor depth", err)
        }
    }

    private fun hideEditorHierarchyLayer() {
        runCatching {
            val editor = aodActiveEditorRef?.get() ?: return
            val hierarchy = editor.javaClass.methods
                .firstOrNull { it.name == "getHierarchyLayer" && it.parameterCount == 0 }
                ?.invoke(editor) as? View
            hierarchy?.visibility = View.GONE
        }
    }

    // =========================================================================
    // 6. AOD Depth Conflict Handling & crop_subject Preservation
    // =========================================================================

    private fun installAodDepthConflictAndApplyHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        // 1. Capture subject bitmap & matrix updates from HierarchyImageView
        runCatching {
            val hierarchyClass = classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.HierarchyImageView")
            hierarchyClass.declaredMethods
                .firstOrNull { it.name == "setImageBitmap" && it.parameterCount == 1 && it.parameterTypes[0] == Bitmap::class.java }
                ?.apply { isAccessible = true }
                ?.let { setBitmapMethod ->
                    module.hookDeoptimized(setBitmapMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-clock-avoid-capture-subject")
                        .intercept { chain ->
                            val bmp = chain.getArg(0) as? Bitmap
                            if (bmp != null && !bmp.isRecycled) {
                                aodCachedSubjectBitmapRef = WeakReference(bmp)
                                aodCachedContourKey = 0L
                            }
                            val result = chain.proceed()
                            invalidateTrackedTimeViews(activeAodTimeViews)
                            result
                        }
                }
            hierarchyClass.declaredMethods
                .firstOrNull { it.name == "onChanged" && it.parameterCount == 1 && it.parameterTypes[0] == Matrix::class.java }
                ?.apply { isAccessible = true }
                ?.let { onChangedMethod ->
                    module.hookDeoptimized(onChangedMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-clock-avoid-matrix-change")
                        .intercept { chain ->
                            val result = chain.proceed()
                            aodCachedContourKey = 0L
                            invalidateTrackedTimeViews(activeAodTimeViews)
                            result
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "AOD HierarchyImageView hook skipped", err)
        }

        // 2. Track active EffectsTemplateView & enforce Depth OFF on editor open when Clock Avoidance is ON
        runCatching {
            val effectsClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.EffectsTemplateView")
            effectsClass.declaredMethods
                .filter { it.name in setOf("initTemplateBean", "initHierarchyButton", "onLayout") }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-clock-avoid-effects-track:${method.name}:$idx")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            if (view != null) {
                                aodActiveEditorRef = WeakReference(view)
                            }
                            val result = chain.proceed()
                            if (method.name == "initHierarchyButton" && view != null &&
                                readLockscreenClockAvoidanceEnabled(view.context, preferences)
                            ) {
                                mainHandler.post {
                                    disableEditorDepthFromViewModel(view)
                                    hideEditorHierarchyLayer()
                                    invalidateTrackedTimeViews(activeAodTimeViews)
                                }
                            }
                            result
                        }
                }

            // Intercept updateHierarchyButton(int) so if editor initializes Depth ON while Clock Avoidance is ON,
            // we turn Depth OFF cleanly (making the blue Depth button turn grey and hiding foreground occlusion!)
            effectsClass.declaredMethods
                .firstOrNull { it.name == "updateHierarchyButton" && it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
                ?.let { method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-clock-avoid-enforce-depth-off")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            val status = chain.getArg(0) as? Int ?: 0
                            val result = chain.proceed()
                            if (!aodAutoDisablingDepth && view != null &&
                                (status == 1 || status == 3 || status == 5) &&
                                readLockscreenClockAvoidanceEnabled(view.context, preferences)
                            ) {
                                mainHandler.post {
                                    if (readLockscreenClockAvoidanceEnabled(view.context, preferences)) {
                                        disableEditorDepthFromViewModel(view)
                                        hideEditorHierarchyLayer()
                                    }
                                }
                            }
                            result
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "AOD EffectsTemplateView tracking hook skipped", err)
        }

        // 3. Bidirectional mutual exclusion: when user explicitly taps Depth ON (status 1 or 5, onlySet=false),
        //    automatically turn Clock Avoidance OFF!
        runCatching {
            val vmClass = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel")
            vmClass.declaredMethods
                .filter { it.name == "updateHierarchyStatus" && it.parameterCount >= 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                .forEachIndexed { idx, method ->
                    method.isAccessible = true
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("aod-clock-avoid-mutual-exclusion:$idx")
                        .intercept { chain ->
                            val newStatus = chain.getArg(0) as? Int ?: 0
                            val onlySet = if (chain.args.size >= 2) (chain.getArg(1) as? Boolean ?: false) else false
                            if (!aodAutoDisablingDepth && !onlySet && (newStatus == 1 || newStatus == 5)) {
                                val ctx = aodActiveEditorRef?.get()?.context
                                if (ctx != null && readLockscreenClockAvoidanceEnabled(ctx, preferences)) {
                                    writeLockscreenClockAvoidanceSystem(ctx, false)
                                    syncAodAvoidSwitches(false)
                                    invalidateTrackedTimeViews(activeAodTimeViews)
                                }
                            }
                            chain.proceed()
                        }
                }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.DEBUG, logTag, "AOD ViewModel mutual exclusion hook skipped", err)
        }

        // 4. Preserve crop_subject export in TemplateApiImpl when Clock Avoidance is ON (even with Depth OFF / magicType == 0)
        runCatching {
            val apiClass = classLoader.loadClass("com.miui.keyguard.editor.data.template.TemplateApiImpl")
            val cropLambda = apiClass.declaredMethods.firstOrNull { method ->
                method.parameterCount == 7 &&
                    method.parameterTypes[0].name.endsWith("WallpaperPositionInfo") &&
                    method.parameterTypes[1].name.endsWith("WallpaperInfo") &&
                    method.parameterTypes[2].name.endsWith("TemplateApiImpl") &&
                    method.parameterTypes[3] == Bitmap::class.java &&
                    method.parameterTypes[4].name.endsWith("CommonConfig") &&
                    method.parameterTypes[5] == String::class.java
            }
            if (cropLambda != null) {
                cropLambda.isAccessible = true
                module.hookDeoptimized(cropLambda)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-clock-avoid-preserve-crop-subject")
                    .intercept { chain ->
                        val posInfo = chain.getArg(0)
                        val wpInfo = chain.getArg(1)
                        val api = chain.getArg(2)
                        val bmp = chain.getArg(3) as? Bitmap
                        val objectRef = chain.getArg(6)
                        val ctx = runCatching { findField(api.javaClass, "context")?.get(api) as? Context }.getOrNull()
                        val avoidOn = readLockscreenClockAvoidanceEnabled(ctx, preferences)
                        if (!avoidOn || posInfo == null || wpInfo == null || bmp == null || bmp.isRecycled) {
                            return@intercept chain.proceed()
                        }
                        val magicField = findField(wpInfo.javaClass, "magicType")
                        val originalMagic = runCatching { magicField?.getInt(wpInfo) }.getOrNull()
                        if (originalMagic == null || originalMagic == 20000) {
                            return@intercept chain.proceed()
                        }
                        val elementField = objectRef?.let { findField(it.javaClass, "element") }
                        val originalElement = runCatching { elementField?.get(objectRef) }.getOrNull()
                        try {
                            magicField?.setInt(wpInfo, 20000)
                            chain.proceed()
                        } finally {
                            runCatching { magicField?.setInt(wpInfo, originalMagic) }
                            runCatching { elementField?.set(objectRef, originalElement) }
                        }
                    }
                HookModuleLogger.log(module, Log.INFO, logTag, "Installed AOD crop_subject preservation hook (${cropLambda.name})")
            }
        }.onFailure { err ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Could not install AOD crop_subject preservation hook", err)
        }
    }

    /**
     * Resolves the editor subject contour in the EXACT same viewport coordinates as TimeView and
     * TemplateApiImpl.cropBitmap, pre-scaling wallpaperMatrix when subjectBitmap dimensions differ
     * from originBitmap dimensions.
     */
    private fun resolveEditorContour(timeView: View): SubjectContourProfile? {
        val editor = findAncestorByClassName(timeView, "com.miui.keyguard.editor.edit.base.EffectsTemplateView")
            ?: aodActiveEditorRef?.get()
            ?: return null

        val wpLayer = runCatching {
            editor.javaClass.methods
                .firstOrNull { it.name == "getWallpaperLayer" && it.parameterCount == 0 }
                ?.invoke(editor)
        }.getOrNull()
        val wpLayerView = runCatching {
            wpLayer?.javaClass?.methods
                ?.firstOrNull { it.name == "wallpaperLayerView" && it.parameterCount == 0 }
                ?.invoke(wpLayer) as? View
        }.getOrNull() ?: (wpLayer as? View)

        val viewportW = wpLayerView?.width?.takeIf { it > 0 }
            ?: timeView.width.takeIf { it > 0 }
            ?: timeView.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val viewportH = wpLayerView?.height?.takeIf { it > 0 }
            ?: timeView.height.takeIf { it > 0 }
            ?: timeView.resources.displayMetrics.heightPixels.coerceAtLeast(1)

        val subjectBitmap: Bitmap = runCatching {
            val segEditor = findField(editor.javaClass, "segmentEditor")?.get(editor)
            val segInfo = segEditor?.javaClass?.methods
                ?.firstOrNull { it.name == "getSubjectInfo" && it.parameterCount == 0 }
                ?.invoke(segEditor)
            val segBmp = segInfo?.javaClass?.methods
                ?.firstOrNull { it.name == "getBitmap" && it.parameterCount == 0 }
                ?.invoke(segInfo) as? Bitmap
            if (segBmp != null && !segBmp.isRecycled) return@runCatching segBmp

            val cfg = findField(editor.javaClass, "templateConfig")?.get(editor)
            val lock = cfg?.javaClass?.methods?.firstOrNull { it.name == "getLockscreenInfo" && it.parameterCount == 0 }?.invoke(cfg)
            val wp = lock?.javaClass?.methods?.firstOrNull { it.name == "getWallpaperInfo" && it.parameterCount == 0 }?.invoke(lock)
            val wpBmp = wp?.javaClass?.methods?.firstOrNull { it.name == "getSubjectBitmap" && it.parameterCount == 0 }?.invoke(wp) as? Bitmap
            if (wpBmp != null && !wpBmp.isRecycled) return@runCatching wpBmp

            val hierarchyView = editor.javaClass.methods
                .firstOrNull { it.name == "getHierarchyLayer" && it.parameterCount == 0 }
                ?.invoke(editor) as? ImageView
            val drawBmp = (hierarchyView?.drawable as? BitmapDrawable)?.bitmap
            if (drawBmp != null && !drawBmp.isRecycled) return@runCatching drawBmp

            aodCachedSubjectBitmapRef?.get()?.takeIf { !it.isRecycled }
        }.getOrNull() ?: return null

        // Align subjectBitmap -> viewport using wallpaperMatrix pre-scaled by (originW/subjectW, originH/subjectH)
        val mappedMatrix: Matrix? = runCatching {
            val wpMatrix = wpLayer?.javaClass?.methods
                ?.firstOrNull { it.name == "getWallpaperMatrix" && it.parameterCount == 0 }
                ?.invoke(wpLayer) as? Matrix
            if (wpMatrix != null && !wpMatrix.isIdentity) {
                val originBmp = wpLayer?.javaClass?.methods
                    ?.firstOrNull { it.name == "getOriginBitmap" && it.parameterCount == 0 }
                    ?.invoke(wpLayer) as? Bitmap
                val m = Matrix(wpMatrix)
                if (originBmp != null && !originBmp.isRecycled && originBmp.width > 0 && originBmp.height > 0 &&
                    (originBmp.width != subjectBitmap.width || originBmp.height != subjectBitmap.height)
                ) {
                    val sx = originBmp.width.toFloat() / subjectBitmap.width.toFloat()
                    val sy = originBmp.height.toFloat() / subjectBitmap.height.toFloat()
                    m.preScale(sx, sy)
                }
                return@runCatching m
            }
            val hierarchyView = editor.javaClass.methods
                .firstOrNull { it.name == "getHierarchyLayer" && it.parameterCount == 0 }
                ?.invoke(editor) as? ImageView
            hierarchyView?.imageMatrix?.takeIf { !it.isIdentity }?.let(::Matrix)
        }.getOrNull()

        val matrixValues = FloatArray(9)
        mappedMatrix?.getValues(matrixValues)
        var key = System.identityHashCode(subjectBitmap).toLong() * 31L + viewportW * 17L + viewportH
        if (mappedMatrix != null) {
            for (v in matrixValues) key = key * 31L + (v * 100f).roundToInt()
        }
        val cached = aodCachedContour
        if (cached != null && aodCachedContourKey == key) return cached

        val profile = SubjectContourProfile.fromBitmap(subjectBitmap, mappedMatrix, viewportW, viewportH)
        aodCachedContour = profile
        aodCachedContourKey = key
        return profile
    }

    private fun invokeUpdateHierarchyStatus(viewModel: Any, status: Int) {
        val methods = viewModel.javaClass.declaredMethods.filter { it.name == "updateHierarchyStatus" }
        val fourArg = methods.firstOrNull { it.parameterCount == 4 }
        if (fourArg != null) {
            fourArg.isAccessible = true
            // z=false (not onlySet), z2=true (synchronous setValue on main thread), z3=false
            fourArg.invoke(viewModel, status, false, Looper.myLooper() == Looper.getMainLooper(), false)
            return
        }
        val oneArg = methods.firstOrNull { it.parameterCount == 1 }
        if (oneArg != null) {
            oneArg.isAccessible = true
            oneArg.invoke(viewModel, status)
        }
    }

    private fun syncAodAvoidSwitches(checked: Boolean) {
        mainHandler.post {
            aodSuppressSwitchCallback = true
            try {
                synchronized(aodAvoidSwitches) {
                    aodAvoidSwitches.values.forEach { sw -> sw.isChecked = checked }
                }
            } finally {
                aodSuppressSwitchCallback = false
            }
        }
    }

    // =========================================================================
    // Reflection & Drawing Helpers
    // =========================================================================

    private fun resolvePaintVariationOverrideMethod(paint: Paint): Method? {
        if (paintVariationOverrideMethodResolved) return paintVariationOverrideMethod
        val resolved = runCatching {
            paint.javaClass.declaredMethods.firstOrNull {
                (it.name == "setFontVariationOverride" || it.name == "setCustomVariationOverride") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            }?.apply { isAccessible = true }
        }.getOrNull()
        paintVariationOverrideMethod = resolved
        paintVariationOverrideMethodResolved = true
        return resolved
    }

    private fun applyVariationStringToPaint(paint: Paint, variation: String?) {
        val overrideMethod = resolvePaintVariationOverrideMethod(paint)
        if (overrideMethod != null) {
            runCatching { overrideMethod.invoke(paint, variation) }.onFailure {
                if (variation == null) runCatching { overrideMethod.invoke(paint, "") }
            }
        }
        runCatching { paint.fontVariationSettings = variation }
    }

    private fun applyCustomFontVariation(
        paint: Paint,
        axes: FontVariableAxesInfo?,
        weight: Float,
        width: Float,
        height: Float,
    ) {
        if (axes == null || !axes.isVariable) {
            applyVariationStringToPaint(paint, null)
            return
        }
        val parts = ArrayList<String>(3)
        if (axes.hasWeightAxis) {
            val clamped = weight.coerceIn(axes.weightMin, axes.weightMax)
            parts.add(String.format(Locale.US, "'wght' %.2f", clamped))
        }
        if (axes.hasWidthAxis) {
            val clamped = width.coerceIn(axes.widthMin, axes.widthMax)
            parts.add(String.format(Locale.US, "'wdth' %.2f", clamped))
        }
        if (axes.hasHeightAxis) {
            val clamped = height.coerceIn(axes.heightMin, axes.heightMax)
            parts.add(String.format(Locale.US, "'HGHT' %.2f", clamped))
        }
        val setting = if (parts.isEmpty()) null else parts.joinToString(", ")
        applyVariationStringToPaint(paint, setting)
    }

    private fun invokeApplyFontVariation(
        method: Method?,
        paintFirst: Boolean,
        paint: Paint,
        weight: Float,
        width: Float,
        height: Float,
    ) {
        if (method != null) {
            runCatching {
                if (paintFirst) method.invoke(null, paint, weight, width, height)
                else method.invoke(null, weight, width, height, paint)
                return
            }
        }
        runCatching {
            paint.fontVariationSettings = String.format(Locale.US, "'wght' %.2f, 'wdth' %.2f, 'HGHT' %.2f", weight, width, height)
        }
    }

    private fun invokeSimplifyPath(method: Method, paintFirst: Boolean, paint: Paint, path: Path) {
        runCatching {
            if (paintFirst) method.invoke(null, paint, path)
            else method.invoke(null, path, paint)
        }
    }

    private fun applyPaintGlassEffect(
        paint: Paint,
        enabled: Boolean,
        setGlassEffectMethod: Method?,
        glassEffectField: Field?,
    ) {
        runCatching {
            if (setGlassEffectMethod != null) {
                setGlassEffectMethod.invoke(null, paint, enabled)
                return
            }
            val nativeMethod = glassEffectField?.get(null) as? Method
            nativeMethod?.invoke(paint, enabled)
        }
    }

    private fun invokeGlassClip(
        view: View,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        glassClipField: Field?,
        setMiGlassClipMethod: Method?,
    ) {
        runCatching {
            if (setMiGlassClipMethod != null) {
                setMiGlassClipMethod.invoke(null, view, left, top, right, bottom)
                return
            }
            val nativeMethod = glassClipField?.get(null) as? Method
            nativeMethod?.invoke(view, left, top, right, bottom)
        }
    }

    private fun invalidateTrackedTimeViews(set: MutableSet<View>) {
        synchronized(set) {
            set.toList().forEach { v -> runCatching { v.invalidate() } }
        }
    }

    private fun findFirstImageView(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstImageView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun readIntFieldOrGetter(target: Any, fieldName: String, getterName: String): Int {
        runCatching {
            findField(target.javaClass, fieldName)?.let { return it.getInt(target) }
        }
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == getterName && it.parameterCount == 0 }
                ?.invoke(target) as? Int ?: 0
        }.getOrDefault(0)
    }

    private fun findAncestorByClassName(view: View, targetClassName: String): View? {
        var current: View? = view.parent as? View
        while (current != null) {
            var cls: Class<*>? = current.javaClass
            while (cls != null && cls != Any::class.java) {
                if (cls.name == targetClassName) return current
                cls = cls.superclass
            }
            current = current.parent as? View
        }
        return null
    }

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun localizedClockAvoidanceLabel(): String {
        val lang = Locale.getDefault().language
        return when {
            lang.equals("ja", ignoreCase = true) -> "時計の回避"
            lang.equals("zh", ignoreCase = true) -> "时钟避让"
            else -> "Clock Avoidance"
        }
    }

    private fun localizedHorizontalMoveLabel(): String {
        val lang = Locale.getDefault().language
        return when {
            lang.equals("ja", ignoreCase = true) -> "時計の水平移動制限を解除"
            lang.equals("zh", ignoreCase = true) -> "解除时钟水平移动限制"
            else -> "Unlock Horizontal Clock Move"
        }
    }

    companion object {
        private const val TIME_VIEW_CLASS = "com.miui.clock.allInOne.TimeView"
        private const val INJECTED_AVOID_ROW_TAG = "hyperchanger_clock_avoidance_row"
        private const val INJECTED_HORIZONTAL_ROW_TAG = "hyperchanger_clock_horizontal_row"
    }
}

/**
 * 1D horizontal top-contour profile of the lockscreen foreground subject silhouette.
 * Each bin stores the normalized Y coordinate [0f..1f] of the topmost opaque foreground pixel
 * (1f = no subject present in that horizontal column).
 */
internal class SubjectContourProfile private constructor(
    private val topY: FloatArray,
    val hasValidSubject: Boolean,
) {
    /** Returns a robust low-percentile Y across [startNormX, endNormX] to ignore 1px stray edge hairs. */
    fun percentileYInRange(startNormX: Float, endNormX: Float, percentile: Float = 0.15f): Float {
        if (topY.isEmpty()) return 1f
        val last = topY.size - 1
        val i0 = (startNormX * last).roundToInt().coerceIn(0, last)
        val i1 = (endNormX * last).roundToInt().coerceIn(i0, last)
        val count = i1 - i0 + 1
        if (count == 1) return topY[i0]
        val slice = FloatArray(count) { topY[i0 + it] }
        slice.sort()
        val idx = (percentile * (count - 1)).roundToInt().coerceIn(0, count - 1)
        return slice[idx]
    }

    companion object {
        private const val BINS = 160
        private const val ROWS = 240
        private const val ALPHA_THRESHOLD = 112

        fun fromBitmap(
            bitmap: Bitmap,
            matrix: Matrix?,
            viewportWidth: Int,
            viewportHeight: Int,
        ): SubjectContourProfile {
            val bw = bitmap.width
            val bh = bitmap.height
            if (bw <= 0 || bh <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
                return SubjectContourProfile(FloatArray(BINS) { 1f }, false)
            }

            val invMatrix = Matrix()
            val hasInverse = if (matrix != null && !matrix.isIdentity) {
                matrix.invert(invMatrix)
            } else {
                val scale = max(viewportWidth.toFloat() / bw, viewportHeight.toFloat() / bh)
                val dx = (viewportWidth - bw * scale) * 0.5f
                val dy = (viewportHeight - bh * scale) * 0.5f
                val centerCrop = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                }
                centerCrop.invert(invMatrix)
            }

            val sampleW = min(bw, 256)
            val sampleH = min(bh, 480)
            val sampled = if (bw == sampleW && bh == sampleH && bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
            }
            val ownedSample = sampled !== bitmap
            val pixels = IntArray(sampleW * sampleH)
            try {
                sampled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            } finally {
                if (ownedSample) sampled.recycle()
            }

            val sxScale = sampleW.toFloat() / bw
            val syScale = sampleH.toFloat() / bh
            val raw = FloatArray(BINS) { 1f }
            val pt = FloatArray(2)
            var opaqueColumns = 0

            for (bx in 0 until BINS) {
                val vx = (bx + 0.5f) / BINS * viewportWidth
                var consecutive = 0
                var foundRow = -1
                val startRow = (ROWS * 0.03f).toInt()
                for (ry in startRow until ROWS) {
                    val vy = (ry + 0.5f) / ROWS * viewportHeight
                    val sx: Int
                    val sy: Int
                    if (hasInverse) {
                        pt[0] = vx
                        pt[1] = vy
                        invMatrix.mapPoints(pt)
                        sx = (pt[0] * sxScale).toInt()
                        sy = (pt[1] * syScale).toInt()
                    } else {
                        sx = (bx * sampleW) / BINS
                        sy = (ry * sampleH) / ROWS
                    }
                    val alpha = if (sx in 0 until sampleW && sy in 0 until sampleH) {
                        (pixels[sy * sampleW + sx] ushr 24) and 0xFF
                    } else {
                        0
                    }
                    if (alpha >= ALPHA_THRESHOLD) {
                        consecutive++
                        if (consecutive >= 2) {
                            foundRow = ry - 1
                            break
                        }
                    } else {
                        consecutive = 0
                    }
                }
                if (foundRow >= 0) {
                    raw[bx] = foundRow.toFloat() / ROWS
                    opaqueColumns++
                }
            }

            // 5-tap median smoothing for organic silhouette transitions
            val smoothed = FloatArray(BINS) { i ->
                val win = FloatArray(5) { k -> raw[(i + k - 2).coerceIn(0, BINS - 1)] }
                win.sort()
                win[2]
            }

            val hasValid = opaqueColumns >= BINS / 16
            return SubjectContourProfile(smoothed, hasValid)
        }
    }
}
