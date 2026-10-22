// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

private data class StackedMobileSubscription(
    val slot: Int,
    val dataSim: Boolean?,
    val dataConnected: Boolean?,
    val isDefault: Boolean?,
    val signalLevel: Int,
)

private class StackedMobilePresentation(
    val root: ViewGroup,
    var signal: ImageView,
    var subscriptionId: Int,
) {
    var attachListenerInstalled = false
    var refreshPending = false
    var independentType: TextView? = null
    var mobileSignalContainer: ViewGroup? = null
    var mobileGroup: ViewGroup? = null
    var networkTypeView: TextView? = null
    var systemMobileType: ImageView? = null
    var savedSystemMobileTypeEndToStart: Int? = null
    var savedSystemMobileTypeTopToTop: Int? = null
    var savedSystemMobileTypeEndMargin: Int? = null
    var savedSystemMobileTypeTopMargin: Int? = null
    var networkTypeSource: Any? = null
    var dualContainer: FrameLayout? = null
    var dualSignal: ImageView? = null
    var savedDualTranslationY: Float? = null
    var savedDualMargins: IntArray? = null
    var savedRootVisibility: Int? = null
    var rootHiddenByStacked = false
    var savedIndependentView: TextView? = null
    var savedIndependentTypeface: Typeface? = null
    var hasSavedIndependentTypeface = false
    var savedIndependentTranslationY: Float? = null
    var savedIndependentMargins: IntArray? = null
    var savedIndependentParent: ViewGroup? = null
    var savedIndependentIndex: Int = -1
    var savedIndependentLayoutParams: ViewGroup.LayoutParams? = null
}

/**
 * The Hyper Helper default stacked icon: a full four-column signal on top and
 * a four-dot signal below.  It deliberately has its own geometry instead of
 * squeezing two unrelated SystemUI drawables into one ImageView.
 */
private class StackedMobileDrawable(
    private val upperLevel: Int,
    private val lowerLevel: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 0xFF
    private var tint: ColorStateList? = null
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        // Coordinates match XiaomiHelper's Signal-HyperOS3-Stacked.svg viewBox.
        val columnLeft = 0.108f
        val columnWidth = 0.124f
        val columnGap = 0.0886f
        val topRowBottom = 0.5985f
        val topRowTops = floatArrayOf(0.4635f, 0.3907f, 0.288f, 0.1987f)
        val lowerRowTop = 0.6661f
        val lowerRowBottom = 0.8013f
        val corner = minOf(width * 0.027f, height * 0.027f)
        paint.color = tint?.getColorForState(state, Color.WHITE) ?: Color.WHITE
        paint.colorFilter = drawableColorFilter

        drawSignalRow(canvas, upperLevel, columnLeft, columnWidth, columnGap, topRowTops, topRowBottom, corner)
        drawSignalRow(
            canvas,
            lowerLevel,
            columnLeft,
            columnWidth,
            columnGap,
            floatArrayOf(lowerRowTop, lowerRowTop, lowerRowTop, lowerRowTop),
            lowerRowBottom,
            corner,
        )
    }

    private fun drawSignalRow(
        canvas: Canvas,
        level: Int,
        columnLeft: Float,
        columnWidth: Float,
        columnGap: Float,
        tops: FloatArray,
        bottom: Float,
        corner: Float,
    ) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        for (column in 0 until 4) {
            val left = bounds.left + (columnLeft + column * (columnWidth + columnGap)) * width
            val top = bounds.top + tops[column] * height
            val right = left + columnWidth * width
            val rowBottom = bounds.top + bottom * height
            paint.alpha = if (column < level.coerceIn(0, 4)) drawableAlpha else (drawableAlpha * 0.35f).toInt()
            canvas.drawRoundRect(left, top, right, rowBottom, corner, corner, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun onStateChange(state: IntArray): Boolean {
        if (tint?.isStateful == true) invalidateSelf()
        return tint?.isStateful == true
    }

    override fun isStateful(): Boolean = tint?.isStateful == true
}

/**
 * Owns status-bar signal, network-type, battery and left-side icon hooks.
 * Process dispatch and the mixed global visibility/resource hooks remain in the module.
 * Shared reflection and deoptimization keep their original owners through callbacks.
 */
internal class StatusBarHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val readInstanceField: (Any, String) -> Any?,
    private val readInheritedField: (Any?, String) -> Any?,
    private val systemUiPackageName: String,
    private val keepThemeKey: String,
    private val hideClockTextKey: String,
    private val mobileNetworkTypeModeKey: String,
    private val stackedMobileDualContainerId: Int,
    private val logTag: String,
    private val applicationContext: () -> Context?,
) {
    fun installAdjustmentHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (statusBarAdjustmentHooksInstalled) return
        installClockSizeRefreshHook(classLoader, preferences)
        installStatusBarIconSizeHooks(classLoader, preferences)
        installIconSpacingHooks(classLoader, preferences)
        installBatteryPercentageHook(classLoader, preferences)
        installAdjustmentPreferenceListener(preferences)
        installImportedIconHooks(preferences)
        statusBarAdjustmentHooksInstalled = true
    }

    private fun installClockSizeRefreshHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val controller = classLoader.loadClass("com.android.systemui.statusbar.policy.MiuiStatusBarClockController")
            controller.declaredMethods.filter {
                it.name == "onMiuiThemeChanged" || it.name == "onDensityOrFontScaleChanged"
            }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:clock-size-refresh-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (preferences.getBoolean("clock_enabled", false)) {
                            val size = preferences.getFloat("clock_size", 14.8f).coerceIn(10f, 24f)
                            (readInheritedField(chain.thisObject, "mClockListeners") as? Iterable<*>)
                                ?.forEach { (it as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, size) }
                        }
                        result
                    }
            }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install clock size refresh hook", it) }
    }

    private fun installStatusBarIconSizeHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        installIconSizeLayoutHooks(classLoader, preferences)
        runCatching {
            val iconClass = classLoader.loadClass("com.android.systemui.statusbar.StatusBarIconView")
            iconClass.declaredConstructors.forEachIndexed { index, constructor ->
                hookDeoptimized(constructor).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:icon-size-init-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { trackIconSize(it, preferences) }
                        result
                    }
            }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install legacy status icon size hook", it) }

        runCatching {
            val modernClass = classLoader.loadClass(MODERN_STATUS_BAR_VIEW_CLASS)
            modernClass.declaredMethods.filter {
                (it.name == "initView" || it.name == "onDensityOrFontScaleChanged")
            }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:modern-icon-size-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { trackIconSize(it, preferences) }
                        result
                    }
            }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install modern status icon size hook", it) }
    }

    private fun statusBarIconSizeFactor(preferences: SharedPreferences): Float =
        (1f + preferences.getFloat(
            KEY_STATUS_BAR_ICON_SIZE_PERCENT,
            STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT,
        ).coerceIn(STATUS_BAR_ICON_SIZE_PERCENT_RANGE) / 100f).coerceIn(0f, 2f)

    private fun installIconSizeLayoutHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        val measuredDimension = View::class.java.getDeclaredMethod(
            "setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        fun setWidth(view: View, width: Int) {
            measuredDimension.invoke(
                view,
                width or (view.measuredWidthAndState and View.MEASURED_STATE_MASK),
                view.measuredHeightAndState,
            )
        }
        runCatching {
            val type = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)
            hookDeoptimized(type.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:battery-size-measure")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View ?: return@intercept result
                    if (!sizedIconViews.containsKey(view)) return@intercept result
                    val nativeWidth = view.measuredWidth
                    batteryIconLayouts[view] = BatteryIconLayout(nativeWidth)
                    val allocatedWidth = ceil(nativeWidth * iconSizeFactor(view, preferences).coerceAtLeast(1f)).toInt()
                    if (allocatedWidth != nativeWidth) setWidth(view, allocatedWidth)
                    result
                }
            hookDeoptimized(type.getDeclaredMethod(
                "onLayout", Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:battery-size-content-layout")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val nativeWidth = view?.let { batteryIconLayouts[it]?.nativeWidth }
                    val result = if (nativeWidth == null || view.width == nativeWidth) {
                        chain.proceed()
                    } else {
                        val args = chain.args.toTypedArray()
                        args[3] = (chain.getArg(1) as Int) + nativeWidth
                        chain.proceed(args)
                    }
                    // Also refresh when only the boundary allocation changed at a fixed outer size.
                    if (view != null && sizedIconViews.containsKey(view)) applyIconSize(view, preferences)
                    result
                }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install battery size layout hooks", it) }

        runCatching {
            val type = classLoader.loadClass("com.android.systemui.statusbar.views.MiuiStatusBatteryContainer")
            hookDeoptimized(type.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:signal-battery-boundary-measure")
                .intercept { chain ->
                    val owner = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val battery = readInheritedField(owner, "mBattery") as? View
                    // View.measure may reuse a cached measurement. Remove only our previous
                    // boundary allocation before the parent's one original measurement.
                    battery?.let { view -> batteryIconLayouts[view]?.let { previous ->
                        if (previous.boundarySpacing != 0) {
                            setWidth(view, (view.measuredWidth - previous.boundarySpacing).coerceAtLeast(0))
                            batteryIconLayouts[view] = previous.copy(boundarySpacing = 0)
                        }
                    } }
                    val result = chain.proceed()
                    val layout = battery?.let { batteryIconLayouts[it] } ?: return@intercept result
                    val statusIcons = readInheritedField(owner, "mStatusIcon") as? View ?: return@intercept result
                    val privacy = readInheritedField(owner, "mPrivacyState").toString()
                    if (battery.visibility != View.VISIBLE || statusIcons.visibility != View.VISIBLE ||
                        (readInheritedField(battery, "mLayoutFromTag") as? Number)?.toInt() != 0 ||
                        readInheritedField(owner, "mIsHideBattery") == true ||
                        privacy == "START_SHOW_PRIVACY" || privacy == "COMPLETE_SHOW_PRIVACY"
                    ) return@intercept result
                    // measureViews already excludes blocked/hidden slots and is ordered from
                    // start to end. Only the icon next to the battery contributes edge overflow;
                    // never reserve a scaled width for each SIM or multiply the manual spacing.
                    val edge = (readInheritedField(statusIcons, "measureViews") as? List<*>)
                        ?.filterIsInstance<View>()?.lastOrNull {
                            it.visibility == View.VISIBLE && it.measuredWidth > 0
                        } ?: return@intercept result
                    val edgeScale = iconSizeFactor(edge, preferences)
                    // A zero native inter-icon gap leaves the enlarged signal flush against the
                    // battery even after its overflow is reserved. Keep a visible gap at this
                    // one boundary; the manual icon-spacing setting stays independent.
                    val clearance = if (edgeScale > 1f) 2f * owner.resources.displayMetrics.density else 0f
                    val extra = ceil(edge.measuredWidth * (edgeScale - 1f).coerceAtLeast(0f) / 2f + clearance).toInt()
                    if (extra != 0) {
                        setWidth(battery, battery.measuredWidth + extra)
                        batteryIconLayouts[battery] = layout.copy(boundarySpacing = extra)
                        val widthSpec = chain.getArg(0) as Int
                        val width = when (View.MeasureSpec.getMode(widthSpec)) {
                            View.MeasureSpec.EXACTLY -> owner.measuredWidth
                            View.MeasureSpec.AT_MOST -> (owner.measuredWidth + extra).coerceAtMost(View.MeasureSpec.getSize(widthSpec))
                            else -> owner.measuredWidth + extra
                        }
                        if (width != owner.measuredWidth) setWidth(owner, width)
                    }
                    result
                }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install signal/battery boundary hook", it) }
    }

    private fun installIconSpacingHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val containerClass = classLoader.loadClass("com.android.systemui.statusbar.views.MiuiStatusIconContainer")
            val spacingField = containerClass.getDeclaredField("iconSpacing").apply { isAccessible = true }
            val layoutFromField = containerClass.getDeclaredField("_layoutFrom").apply { isAccessible = true }
            containerClass.declaredMethods.filter { it.name == "onMeasure" || it.name == "onLayout" }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:icon-spacing-$index")
                        .intercept { chain ->
                            val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                            if (layoutFromField.getInt(view) != 0) return@intercept chain.proceed()
                            spacedIconContainers[view] = true
                            val original = spacingField.getInt(view)
                            val percent = readStatusBarIconSpacing(preferences) / 100f
                            // HyperOS ships this resource as 0sp. Multiplication alone then
                            // makes every slider value identical; preserve zero at the default
                            // and use a density-aware basis for positive adjustments.
                            val basis = if (original > 0) original.toFloat()
                                else 4f * view.resources.displayMetrics.density
                            spacingField.setInt(view, (original + basis * percent).roundToInt().coerceAtLeast(0))
                            try {
                                chain.proceed()
                            } finally {
                                spacingField.setInt(view, original)
                            }
                        }
                }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install status icon spacing hooks", it) }
    }

    private data class BatteryIconLayout(val nativeWidth: Int, val boundarySpacing: Int = 0)

    private fun trackIconSize(view: View, preferences: SharedPreferences) {
        if (sizedIconViews.put(view, true) == null) {
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyIconSize(view, preferences)
            }
        }
        applyIconSize(view, preferences)
    }

    private fun applyIconSize(view: View, preferences: SharedPreferences) {
        val factor = iconSizeFactor(view, preferences)
        val batteryLayout = batteryIconLayouts[view]
        val contentWidth = batteryLayout?.nativeWidth ?: view.width
        val extraGap = batteryLayout?.boundarySpacing ?: 0
        val gapTranslation = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -extraGap / 2f else extraGap / 2f
        view.animationMatrix = if (factor == 1f && extraGap == 0) null else Matrix().apply {
            setScale(factor, factor, contentWidth / 2f, view.height / 2f)
            postTranslate((view.width - contentWidth) / 2f + gapTranslation, 0f)
        }
        view.invalidate()
    }

    private fun iconSizeFactor(view: View, preferences: SharedPreferences): Float {
        // Overflow dots are StatusBarIconViews nested inside modern signal views.
        // Scale the owning view only, and leave Folme's scaleX/scaleY animation intact.
        val nested = generateSequence(view.parent as? View) { it.parent as? View }
            .any { sizedIconViews.containsKey(it) }
        val batteryOutsideStatusBar = view.javaClass.name == BATTERY_METER_VIEW_CLASS &&
            (readInheritedField(view, "mLayoutFromTag") as? Number)?.toInt() != 0
        return if (nested || batteryOutsideStatusBar) 1f else statusBarIconSizeFactor(preferences)
    }

    private fun installAdjustmentPreferenceListener(preferences: SharedPreferences) {
        if (adjustmentPreferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_STATUS_BAR_ICON_SIZE_PERCENT || key == KEY_STATUS_BAR_ICON_SPACING_PERCENT) {
                val views = synchronized(sizedIconViews) { sizedIconViews.keys.toList() }
                views.forEach { view -> view.post {
                    view.requestLayout()
                    applyIconSize(view, preferences)
                } }
                val containers = synchronized(spacedIconContainers) { spacedIconContainers.keys.toList() }
                containers.forEach { view -> view.post { view.requestLayout() } }
            }
            if (key == null || key == KEY_STATUS_BAR_BATTERY_PERCENT_MODE ||
                key == KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE
            ) {
                val views = synchronized(batteryStyleViews) { batteryStyleViews.keys.toList() }
                views.forEach { view -> view.post { replayBatteryStyle(view) } }
            }
        }
        adjustmentPreferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun replayBatteryStyle(target: Any) {
        val style = (readInheritedField(target, "mStoreRealStyle") as? Number)?.toInt() ?: return
        if (style < 0) return // The first controller callback has not arrived yet.
        runCatching { batteryStyleMethod?.invoke(target, style) }
            .onFailure { module.log(Log.WARN, logTag, "Could not refresh battery percentage style", it) }
    }

    private fun installBatteryPercentageHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val viewClass = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)
            val realStyleField = viewClass.getDeclaredField("mStoreRealStyle").apply { isAccessible = true }
            val styleMethods = viewClass.declaredMethods.filter {
                it.name == "onBatteryStyleChanged" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }.onEach { it.isAccessible = true }
            styleMethods.forEachIndexed { index, method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-percent-mode-$index")
                        .intercept { chain ->
                            val target = chain.thisObject
                            val desiredStyle = batteryPercentMode(
                                (readInheritedField(target, "mLayoutFromTag") as? Number)?.toInt(),
                                preferences,
                            ).takeIf { it != 0 }?.let(::batteryStyleForMode)
                            if (desiredStyle == null) chain.proceed()
                            else {
                                val originalStyle = chain.getArg(0) as Int
                                try {
                                    chain.proceed(arrayOf(desiredStyle))
                                } finally {
                                    // Internal theme/island callbacks must keep the real system style
                                    // so returning either scope to default can restore it.
                                    realStyleField.setInt(target, originalStyle)
                                }
                            }
                        }
                }
            batteryStyleMethod = styleMethods.firstOrNull()
                ?: error("MiuiBatteryMeterView.onBatteryStyleChanged(int) was not found")
            viewClass.declaredMethods.filter {
                it.name == "setLayoutFromTag" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:battery-layout-tag-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val target = chain.thisObject
                        (target as? View)?.let { view ->
                            batteryStyleViews[view] = true
                            trackIconSize(view, preferences)
                        }
                        replayBatteryStyle(target)
                        result
                    }
            }
            viewClass.declaredMethods.filter {
                it.name == "onAttachedToWindow" && it.parameterCount == 0
            }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:battery-percent-attach-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        replayBatteryStyle(chain.thisObject)
                        result
                    }
            }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install battery percentage hook", it) }
    }

    private fun batteryPercentMode(tag: Int?, preferences: SharedPreferences): Int = when (tag) {
        0 -> preferences.getInt(KEY_STATUS_BAR_BATTERY_PERCENT_MODE, 0)
        5, 6 -> preferences.getInt(KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE, 0)
        else -> 0
    }.coerceIn(0, 3)

    private fun batteryStyleForMode(mode: Int): Int = when (mode) {
        1 -> 1
        2 -> 3
        else -> 0
    }

    private data class ImportedIcon(val bytes: ByteArray, val density: Int)

    fun installImportedIconHooks(preferences: SharedPreferences) {
        if (importedIconHooksInstalled) return
        runCatching {
            listOf(
                Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java),
                Resources::class.java.getMethod("getDrawableForDensity", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getDrawableForDensity", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Resources.Theme::class.java),
            ).forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:imported-drawable-$index")
                    .intercept { chain ->
                        val resources = chain.thisObject as? Resources ?: return@intercept chain.proceed()
                        val id = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                        val icon = findImportedIcon(resources, id, preferences) ?: return@intercept chain.proceed()
                        val options = BitmapFactory.Options().apply { inDensity = icon.density; inTargetDensity = resources.displayMetrics.densityDpi }
                        val bitmap = BitmapFactory.decodeByteArray(icon.bytes, 0, icon.bytes.size, options)
                            ?: return@intercept chain.proceed()
                        BitmapDrawable(resources, bitmap)
                    }
            }
            Resources::class.java.declaredMethods.filter {
                it.name == "openRawResource" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:imported-raw-$index")
                    .intercept { chain ->
                        val resources = chain.thisObject as? Resources ?: return@intercept chain.proceed()
                        val id = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                        val icon = findImportedIcon(resources, id, preferences) ?: return@intercept chain.proceed()
                        (chain.args.getOrNull(1) as? TypedValue)?.density = icon.density
                        ByteArrayInputStream(icon.bytes)
                    }
            }
            hookDeoptimized(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:imported-image-resource")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ImageView ?: return@intercept result
                    val id = chain.getArg(0) as? Int ?: return@intercept result
                    val resources = view.resources
                    val icon = findImportedIcon(resources, id, preferences) ?: return@intercept result
                    val options = BitmapFactory.Options().apply {
                        inDensity = icon.density
                        inTargetDensity = resources.displayMetrics.densityDpi
                    }
                    BitmapFactory.decodeByteArray(icon.bytes, 0, icon.bytes.size, options)?.let { bitmap ->
                        view.setImageDrawable(BitmapDrawable(resources, bitmap))
                    }
                    result
                }
            importedIconHooksInstalled = true
        }.onFailure { module.log(Log.WARN, logTag, "Could not install imported status icon hooks", it) }
    }

    private fun findImportedIcon(resources: Resources, id: Int, preferences: SharedPreferences): ImportedIcon? {
        if (!preferences.getBoolean(KEY_STATUS_BAR_ICON_IMPORT_ENABLED, false)) return null
        val packageName = runCatching { resources.getResourcePackageName(id) }.getOrNull() ?: return null
        if (packageName != systemUiPackageName && packageName != "miui.systemui.plugin") return null
        val type = runCatching { resources.getResourceTypeName(id) }.getOrNull() ?: return null
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return null
        if (type !in setOf("drawable", "mipmap", "raw")) return null
        val scope = preferences.getInt(KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE, SYSTEM_UI_RESOURCE_SCOPE_ALL)
        val requiredScope = if (name.startsWith("ic_qs_") || name.startsWith("ic_cc_qs_")) {
            SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER
        } else {
            SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR
        }
        if (scope and requiredScope == 0) return null
        val revision = preferences.getLong(KEY_STATUS_BAR_ICON_IMPORT_REVISION, 0L)
        val icons = synchronized(importedIconLock) {
            if (revision != importedIconRevision) {
                val loaded = loadImportedIcons() ?: return null
                importedIcons = loaded
                importedIconRevision = revision
            }
            importedIcons
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val prefix = "$packageName/${if (night) "night" else "day"}/"
        val candidates = icons.filterKeys { key ->
            key.startsWith(prefix) && key.substringAfter(prefix).substringBefore('/').substringBefore('-') == type &&
                key.substringAfterLast('/').substringBeforeLast('.') == name
        }
        val selected = candidates.entries.maxByOrNull { densityFromPath(it.key) }
            ?: if (night) icons.entries.filter { (key, _) ->
                key.startsWith("$packageName/day/") && key.substringAfterLast('/').substringBeforeLast('.') == name &&
                    key.substringAfter("$packageName/day/").substringBefore('/').substringBefore('-') == type
            }.maxByOrNull { densityFromPath(it.key) } else null
        return selected?.let { ImportedIcon(it.value, densityFromPath(it.key)) }
    }

    private fun loadImportedIcons(): Map<String, ByteArray>? {
        val context = applicationContext() ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull() ?: return null
        return runCatching {
            val uri = Uri.parse("content://$STATUS_BAR_ICON_AUTHORITY/icons")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                buildMap {
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) put(entry.name, zip.readBytes())
                            entry = zip.nextEntry
                        }
                    }
                }
            }
        }.getOrElse { error ->
            module.log(Log.WARN, logTag, "Could not read imported status icon archive", error)
            null
        }
    }

    private fun densityFromPath(path: String): Int = when {
        "xxxhdpi" in path -> 640
        "xxhdpi" in path -> 480
        "xhdpi" in path -> 320
        "hdpi" in path -> 240
        "mdpi" in path -> 160
        "ldpi" in path -> 120
        else -> 480
    }
    fun installStackedMobileSignalHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!stackedMobileSignalHookInstalled) {
            installStackedMobileSignalHook(classLoader, preferences)
            stackedMobileSignalHookInstalled = true
        }
    }

    fun installIconsLeftHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!statusBarIconsLeftHookInstalled) {
            installStatusBarIconsLeftHook(classLoader, preferences)
            statusBarIconsLeftHookInstalled = true
        }
    }

    private fun isBatteryPercentageView(view: TextView?, resourceName: String?): Boolean {
        return resourceName == "battery_text_digit_view" || resourceName == "battery_text_view"
    }

    fun installBatteryThemeAndTextHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val keepTheme = { preferences.getBoolean(keepThemeKey, false) }
        runCatching {
            val batteryViewClass = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)
            // Vendor builds have changed the callback signature (and sometimes moved it to
            // a nested icon class). Match by name so one missing overload cannot disable the
            // remaining status-bar guards.
            batteryViewClass.declaredMethods
                .filter { it.name == "onMiuiThemeChanged" || it.name == "updateResources" }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-theme-$index")
                        .intercept { chain ->
                            // A theme refresh rebuilds the battery drawable from the
                            // currently selected theme.  After a SystemUI restart there
                            // is no in-process drawable history to restore, so skip only
                            // this destructive refresh when the keep-theme option is on.
                            if (shouldSkipBatteryThemeRefresh(chain.thisObject, method.name, keepTheme())) {
                                return@intercept null
                            }
                            val snapshot = captureBatteryDrawables(chain.thisObject)
                            val result = chain.proceed()
                            if (keepTheme()) restoreBatteryDrawables(snapshot)
                            result
                        }
                }

            val refreshMethods = batteryViewClass.declaredMethods.filter {
                it.name == "updateChargeAndText" || it.name == "updateAll" ||
                    it.name == "updateAll\u00241" || it.name == "onBatteryStyleChanged"
            }
            refreshMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:battery-text-refresh-$index")
                    .intercept { chain ->
                        val snapshot = captureBatteryDrawables(chain.thisObject)
                        val result = chain.proceed()
                        if (keepTheme()) restoreBatteryDrawables(snapshot)
                        if (preferences.getBoolean(hideClockTextKey, false)) {
                            hideBatteryText(chain.thisObject, chain.thisObject as? ViewGroup)
                        }
                        result
                    }
            }
            module.log(Log.INFO, logTag, "Installed battery theme/text hooks (${refreshMethods.size} refresh methods)")
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install battery view theme/text hooks", error)
        }

        listOf(BATTERY_ICON_CLASS, BATTERY_INDICATOR_CLASS, BATTERY_HOLLOW_ICON_CLASS).forEach { className ->
            runCatching {
                val iconClass = classLoader.loadClass(className)
                iconClass.declaredMethods
                    .filter { it.name == "onMiuiThemeChanged" || it.name == "updateResources" }
                    .forEachIndexed { index, method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("status-bar:battery-icon-theme-${className.substringAfterLast('.')}-${index}")
                            .intercept { chain ->
                                if (shouldSkipBatteryThemeRefresh(chain.thisObject, method.name, keepTheme())) {
                                    return@intercept null
                                }
                                val snapshot = captureBatteryDrawables(chain.thisObject)
                                val result = chain.proceed()
                                if (keepTheme()) restoreBatteryDrawables(snapshot)
                                result
                            }
                    }
            }.onFailure { error ->
                module.log(Log.DEBUG, logTag, "Optional battery theme hook unavailable: $className", error)
            }
        }
        installStatusBarIconThemeGuards(classLoader, preferences, keepTheme)
    }

    private fun hideBatteryText(owner: Any?, root: ViewGroup?) {
        val views = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        listOf("mBatteryTextDigitView")
            .forEach { fieldName ->
                runCatching {
                    var type: Class<*>? = owner?.javaClass
                    while (type != null) {
                        val field = runCatching {
                            type!!.getDeclaredField(fieldName).apply { isAccessible = true }
                        }.getOrNull()
                        if (field != null) {
                            (field.get(owner) as? View)?.let { views += it }
                            break
                        }
                        type = type!!.superclass
                    }
                }
            }
        root?.findViewsByResourceNames(
            "battery_text_digit_view",
        )?.let { views.addAll(it) }
        views.forEach { view ->
            if (view is TextView) view.text = ""
            view.visibility = View.GONE
        }
    }

    private fun shouldSkipBatteryThemeRefresh(owner: Any?, methodName: String, keepTheme: Boolean): Boolean {
        if (!keepTheme) return false
        // Theme callbacks always rebuild the drawable from the default-theme resources.
        if (methodName.startsWith("onMiuiThemeChanged")) return true
        // Resource refresh is needed once during inflation.  Subsequent calls are the
        // reset path observed after a SystemUI restart, so keep the first themed drawable.
        if (owner == null) return false
        synchronized(batteryResourceRefreshSeen) {
            if (batteryResourceRefreshSeen.containsKey(owner)) return true
            batteryResourceRefreshSeen[owner] = true
        }
        return false
    }

    fun installBatteryInternalTextHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val hideText = { preferences.getBoolean(hideClockTextKey, false) }

        // The internal percentage is a TextView on the normal battery layout.  Keep this
        // hook scoped by resource id so the separately configurable external percentage is
        // never affected.
        runCatching {
            TextView::class.java.declaredMethods
                .filter { it.name == "setText" && it.parameterTypes.isNotEmpty() }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-internal-text-$index")
                        .intercept { chain ->
                            val view = chain.thisObject as? TextView
                            val resourceName = runCatching {
                                view?.resources?.getResourceEntryName(view.id)
                            }.getOrNull()
                            val internal = resourceName == "battery_text_digit_view"
                            val result = chain.proceed()
                            if (hideText() && internal) view?.visibility = View.GONE
                            result
                        }
                }
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Could not install battery TextView guard", error)
        }

        // Hollow battery themes draw the number directly on a Canvas instead of using the
        // TextView above.  Temporarily make only their text paints transparent while the
        // widget draws, then restore the paints immediately for future theme updates.
        runCatching {
            val hollowClass = classLoader.loadClass(
                "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView",
            )
            hollowClass.declaredMethods
                .filter { it.name == "onDraw" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Canvas::class.java }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-hollow-text-$index")
                        .intercept { chain ->
                            if (!hideText()) return@intercept chain.proceed()
                            val paints = listOf("textPaint", "hollowTextPaint").mapNotNull { name ->
                                runCatching {
                                    var type: Class<*>? = chain.thisObject?.javaClass
                                    while (type != null) {
                                        val field = runCatching {
                                            type!!.getDeclaredField(name).apply { isAccessible = true }
                                        }.getOrNull()
                                        if (field != null) return@runCatching field.get(chain.thisObject) as? Paint
                                        type = type!!.superclass
                                    }
                                    null
                                }.getOrNull()
                            }.distinct()
                            val alpha = paints.map { it.alpha }
                            paints.forEach { it.alpha = 0 }
                            try {
                                chain.proceed()
                            } finally {
                                paints.forEachIndexed { paintIndex, paint -> paint.alpha = alpha[paintIndex] }
                            }
                        }
                }
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Optional hollow battery text hook unavailable", error)
        }
    }

    private fun captureBatteryDrawables(owner: Any?): List<Pair<ImageView, Drawable>> {
        val result = ArrayList<Pair<ImageView, Drawable>>()
        val fieldNames = setOf("mBatteryIconView", "mBatteryChargingView", "mBatteryChargingInView")
        var type: Class<*>? = owner?.javaClass
        while (type != null) {
            type.declaredFields.filter { it.name in fieldNames }.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val view = field.get(owner) as? ImageView
                    val drawable = view?.drawable?.constantState?.newDrawable(view.resources)
                        ?: view?.let { batteryDrawableHistory[it]?.constantState?.newDrawable(it.resources) }
                    if (view != null && drawable != null) result += view to drawable
                }
            }
            type = type.superclass
        }
        (owner as? ImageView)?.let { view ->
            (view.drawable?.constantState?.newDrawable(view.resources)
                ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources))
                ?.let { result += view to it }
        }
        return result
    }

    private fun restoreBatteryDrawables(snapshot: List<Pair<ImageView, Drawable>>) {
        snapshot.forEach { (view, drawable) ->
            runCatching {
                val restored = drawable.constantState?.newDrawable(view.resources) ?: drawable
                batteryDrawableHistory[view] = restored.constantState?.newDrawable(view.resources) ?: restored
                restoringBatteryDrawable.set(true)
                try {
                    view.setImageDrawable(restored)
                } finally {
                    restoringBatteryDrawable.remove()
                }
            }
        }
    }

    fun installBatteryDrawableHistoryHook(preferences: SharedPreferences) {
        runCatching {
            module.hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:battery-drawable-history")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val old = view?.drawable
                    val restoring = restoringBatteryDrawable.get() ?: false
                    if (old != null && preferences.getBoolean(keepThemeKey, false) &&
                        !restoring
                    ) {
                        batteryDrawableHistory[view] = old.constantState?.newDrawable(view.resources) ?: old
                    }
                    chain.proceed()
                }
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Could not install battery drawable history hook", error)
        }
    }

    private fun installStatusBarIconThemeGuards(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        keepTheme: () -> Boolean,
    ) {
        val classNames = listOf(
            MODERN_STATUS_BAR_VIEW_CLASS,
            WIFI_VIEW_BINDER_CLASS,
            MOBILE_ICON_BINDER_CLASS,
        )
        classNames.forEach { className ->
            runCatching {
                val root = classLoader.loadClass(className)
                val candidates = buildList {
                    add(root)
                    addAll(root.declaredClasses)
                }
                candidates.forEach { candidate ->
                    candidate.declaredMethods
                        .filter { it.name.startsWith("onMiuiThemeChanged") }
                        .forEach { method ->
                            hookDeoptimized(method)
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .setId("status-bar:icon-theme-${candidate.name}")
                                .intercept { chain ->
                                    if (!keepTheme()) return@intercept chain.proceed()
                                    // These callbacks are the point where the vendor
                                    // pipeline replaces themed signal/Wi-Fi drawables with
                                    // its default set.  Skipping the callback keeps the
                                    // drawable selected during initial inflation, including
                                    // the lockscreen status-bar instance.
                                    null
                                }
                        }
                }
                module.log(Log.INFO, logTag, "Installed status-bar icon theme guard: $className")
            }.onFailure { error ->
                module.log(Log.DEBUG, logTag, "Optional status-bar icon theme guard unavailable: $className", error)
            }
        }
    }

    private fun captureImageViewDrawables(owner: Any?): List<Pair<ImageView, Drawable>> {
        val result = ArrayList<Pair<ImageView, Drawable>>()
        var type: Class<*>? = owner?.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val view = field.get(owner) as? ImageView ?: return@runCatching
                    val drawable = view.drawable?.constantState?.newDrawable(view.resources)
                        ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources)
                    if (drawable != null) result += view to drawable
                }
            }
            type = type.superclass
        }
        (owner as? ImageView)?.let { view ->
            (view.drawable?.constantState?.newDrawable(view.resources)
                ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources))
                ?.let { result += view to it }
        }
        return result.distinctBy { it.first }
    }

    private fun restoreImageViewDrawables(snapshot: List<Pair<ImageView, Drawable>>) {
        snapshot.forEach { (view, drawable) ->
            runCatching {
                restoringBatteryDrawable.set(true)
                try {
                    view.setImageDrawable(drawable.constantState?.newDrawable(view.resources) ?: drawable)
                } finally {
                    restoringBatteryDrawable.remove()
                }
            }
        }
    }

    private fun ViewGroup.findViewsByResourceNames(vararg names: String): List<TextView> {
        val result = ArrayList<TextView>()
        fun visit(view: View) {
            if (view is TextView) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name in names) result += view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
        return result
    }

    /**
     * Builds the compact two-row dual-SIM glyph from the legacy signal state.  The modern binder
     * supplies the live ImageView that will host it.
     */
    private fun installStatusBarIconsLeftHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val utils = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiIconManagerUtils")
            val right = utils.getDeclaredField("RIGHT_BLOCK_LIST").apply { isAccessible = true }.get(null) as? MutableList<Any?>
                ?: error("RIGHT_BLOCK_LIST unavailable")
            // HyperOS 4 renamed the sound-profile slot from the older customiuizer "volume"
            // alias to the policy slot "mute" (MiuiPhoneStatusBarPolicy.LazyInitSlot.MUTE).
            val slots = listOf(1 to "network_speed", 2 to "alarm_clock", 4 to "mute", 8 to "zen")

            // The new IconManager constructor has eight dependency parameters. Capture the
            // real, already-wired arguments from SystemUI instead of trying to reconstruct
            // WifiUiAdapter/MobileUiAdapter/Lazy/Kairos dependencies from private fields.
            val darkManagerClass = classLoader.loadClass("com.android.systemui.statusbar.phone.ui.DarkIconManager")
            darkManagerClass.constructors.filter { constructor ->
                constructor.parameterCount == 8 && ViewGroup::class.java.isAssignableFrom(constructor.parameterTypes.firstOrNull())
            }.forEach { constructor ->
                module.hook(constructor).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:icons-left-dark-manager-args")
                    .intercept { chain ->
                        val result = chain.proceed()
                        statusBarDarkIconManagerConstructor = constructor
                        statusBarDarkIconManagerArgs = chain.args.toMutableList().toTypedArray()
                        result
                    }
            }

            runCatching {
                val rootFactory = classLoader.loadClass("com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory")
                rootFactory.declaredConstructors.forEach { constructor ->
                    module.hook(constructor).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:icons-left-factory")
                        .intercept { chain ->
                            val result = chain.proceed()
                            statusBarDarkIconManagerFactory = chain.thisObject
                            result
                        }
                }
            }

            val viewClass = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            val attached = viewClass.methods.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
                ?: error("MiuiPhoneStatusBarView.onAttachedToWindow unavailable")
            module.hook(attached).setExceptionMode(ExceptionMode.PROTECTIVE).setId("status-bar:icons-left").intercept { chain ->
                val result = chain.proceed()
                applyStatusBarIconsLeft(chain.thisObject, classLoader, preferences, right, slots)
                chain.thisObject?.let { (it as? View)?.post { applyStatusBarIconsLeft(it, classLoader, preferences, right, slots) } }
                result
            }
            viewClass.methods.filter { it.name == "setDarkIconManager" && it.parameterCount == 1 }.forEach { setter ->
                module.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).setId("status-bar:icons-left-manager-ready").intercept { chain ->
                    val result = chain.proceed()
                    applyStatusBarIconsLeft(chain.thisObject, classLoader, preferences, right, slots)
                    result
                }
            }
            // The Compose home-status-bar binder adds the clock/start-side content after the
            // view's onAttachedToWindow callback. Re-apply the ordering after that bind, or the
            // binder's child insertion can place our group back before/after the wrong sibling.
            runCatching {
                val binderClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl",
                )
                binderClass.methods.filter { method ->
                    method.name == "bind" && method.parameterCount > 0 &&
                        method.parameterTypes[0].name.contains("PhoneStatusBarView")
                }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:icons-left-order")
                        .intercept { chain ->
                            val result = chain.proceed()
                            applyStatusBarIconsLeft(chain.getArg(0), classLoader, preferences, right, slots)
                            positionStatusBarLeftIconGroup(chain.getArg(0) as? View)
                            result
                        }
                }
            }
            module.log(Log.INFO, logTag, "Installed status bar icon left-position hook")
        }.onFailure { error -> module.log(Log.WARN, logTag, "Status bar icon left-position hook unavailable", error) }
    }

    private fun applyStatusBarIconsLeft(
        rawView: Any?,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        right: MutableList<Any?>,
        slots: List<Pair<Int, String>>,
    ) {
        val view = rawView ?: return
        val rootView = view as? View ?: return
        val manager = runCatching { view.javaClass.getField("mDarkIconManager").get(view) }.getOrNull() ?: return
                val mask = preferences.getInt(KEY_STATUS_BAR_ICONS_LEFT_MASK, 0).coerceIn(0, 15)
                val selectedSlots = slots.filter { mask and it.first != 0 }.map { it.second }
                // Keep the vendor right-side manager and StatusIconContainer in sync. The
                // block-list flow is collected after onAttachedToWindow, so update both here.
                selectedSlots.forEach { if (it !in right) right += it }
                manager.javaClass.getMethod("setBlockList", List::class.java).invoke(manager, right)
                val notificationArea = runCatching {
                    view.javaClass.getField("mDripStatusBarNotificationIconArea").get(view) as? View
                }.getOrNull()
                val clockId = rootView.resources.getIdentifier("clock", "id", "com.android.systemui")
                val clock = if (clockId != 0) rootView.findViewById<View>(clockId) else null
                val leftContainer = runCatching {
                    view.javaClass.getField("mStatusBarLeftContainer").get(view) as? ViewGroup
                }.getOrNull()
                    ?: clock?.parent as? ViewGroup
                    ?: notificationArea?.parent as? ViewGroup
                    ?: return
                val group = leftContainer.findViewWithTag<View>("hyperChangerLeftIcons") as? LinearLayout
                    ?: LinearLayout(leftContainer.context).apply {
                        tag = "hyperChangerLeftIcons"
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                if (mask == 0) {
                    (group.parent as? ViewGroup)?.removeView(group)
                    rootView.setTag(LEFT_STATUS_BAR_MANAGER_TAG, null)
                    return
                }
                if (group.parent == null) {
                    val clockIndex = clock?.let { leftContainer.indexOfChild(it) } ?: -1
                    if (clockIndex >= 0) leftContainer.addView(group, clockIndex + 1) else leftContainer.addView(group)
                }
                val clockIndex = clock?.let { leftContainer.indexOfChild(it) } ?: -1
                val notificationIndex = notificationArea?.let { leftContainer.indexOfChild(it) } ?: -1
                if (group.parent === leftContainer) {
                    val currentIndex = leftContainer.indexOfChild(group)
                    val targetIndex = when {
                        clockIndex >= 0 -> clockIndex + 1
                        notificationIndex >= 0 -> notificationIndex
                        else -> currentIndex
                    }
                    if (currentIndex >= 0 && targetIndex != currentIndex) {
                        leftContainer.removeViewAt(currentIndex)
                        leftContainer.addView(group, targetIndex.coerceAtMost(leftContainer.childCount))
                    }
                }
                if (rootView.getTag(LEFT_STATUS_BAR_MANAGER_TAG) != null) return
                val capturedConstructor = statusBarDarkIconManagerConstructor
                val capturedArgs = statusBarDarkIconManagerArgs?.copyOf()
                val leftManager = if (capturedConstructor != null && capturedArgs?.size == 8) {
                    capturedArgs[0] = group
                    capturedConstructor.apply { isAccessible = true }.newInstance(*capturedArgs)
                } else {
                    // On some HyperOS builds the manager constructor is hidden from the
                    // reflection view. The already-created StatusBarRootFactory exposes the
                    // same Dagger factory used by the stock binder, so use it as a fallback.
                    val rootFactory = statusBarDarkIconManagerFactory ?: return
                    val factory = runCatching {
                        rootFactory.javaClass.getField("darkIconManagerFactory").get(rootFactory)
                    }.getOrNull() ?: return
                    val dispatcher = runCatching { view.javaClass.getField("mDarkIconDispatcher").get(view) }.getOrNull() ?: return
                    val home = classLoader.loadClass("com.android.systemui.statusbar.phone.StatusBarLocation")
                        .enumConstants?.firstOrNull { it.toString() == "HOME" } ?: return
                    val create = factory.javaClass.methods.firstOrNull {
                        it.name == "create" && it.parameterCount == 3
                    } ?: return
                    create.invoke(factory, group, home, dispatcher)
                }
                leftManager.javaClass.getMethod("setBlockList", List::class.java).invoke(leftManager, right.filterNot { it in selectedSlots })
                manager.javaClass.getField("mController").get(manager)?.let { controller -> controller.javaClass.getMethod("addIconGroup", classLoader.loadClass("com.android.systemui.statusbar.phone.ui.IconManager")).invoke(controller, leftManager) }
                rootView.setTag(LEFT_STATUS_BAR_MANAGER_TAG, leftManager)
    }

    private fun positionStatusBarLeftIconGroup(root: View?) {
        val view = root ?: return
        val group = view.findViewWithTag<View>("hyperChangerLeftIcons") ?: return
        val clockId = view.resources.getIdentifier("clock", "id", "com.android.systemui")
        val clock = if (clockId != 0) view.findViewById<View>(clockId) else null
        val parent = clock?.parent as? ViewGroup ?: return
        if (group.parent !== parent) (group.parent as? ViewGroup)?.removeView(group)
        val clockIndex = parent.indexOfChild(clock)
        if (clockIndex < 0) return
        val currentIndex = parent.indexOfChild(group)
        if (currentIndex != clockIndex + 1) {
            if (currentIndex >= 0) parent.removeViewAt(currentIndex)
            parent.addView(group, (clockIndex + 1).coerceAtMost(parent.childCount))
        }
    }

    private fun installStackedMobileSignalHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        stackedMobilePreferences = preferences
        val enabled = { preferences.getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false) }
        if (!stackedMobilePreferenceListenerRegistered) {
            val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    mobileNetworkTypeModeKey,
                    KEY_MOBILE_NETWORK_TYPE_POSITION,
                    KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC,
                    KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT,
                    KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A,
                    KEY_MOBILE_NETWORK_TYPE_BOLD,
                    -> refreshStackedMobilePresentations(enabled)
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            stackedMobilePreferenceChangeListener = preferenceListener
            stackedMobilePreferenceListenerRegistered = true
        }
        val presentationRequired = {
            enabled() ||
                preferences.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0).coerceIn(0, 2) == 1 ||
                preferences.getInt(mobileNetworkTypeModeKey, 0).coerceIn(0, 2) == 1
        }
        var hookCount = 0

        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.connectivity.MobileSignalController",
            )
            val notifyListeners = controllerClass.methods.firstOrNull { method ->
                method.name == "notifyListeners" && method.parameterCount == 1
            } ?: error("MobileSignalController.notifyListeners was not found")
            module.hook(notifyListeners)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-controller")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject ?: return@intercept result
                    updateStackedMobileSubscription(controller)
                    refreshStackedMobilePresentations(enabled)
                    result
                }
            hookCount++

            val updateConnectivity = controllerClass.methods.firstOrNull { method ->
                method.name == "updateConnectivity" && method.parameterCount == 2
            } ?: error("MobileSignalController.updateConnectivity was not found")
            module.hook(updateConnectivity)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-connectivity")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject ?: return@intercept result
                    updateStackedMobileSubscription(controller)
                    refreshStackedMobilePresentations(enabled)
                    result
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Stacked mobile controller hook unavailable", error)
        }

        runCatching {
            val networkControllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.connectivity.NetworkControllerImpl",
            )
            val subscriptionsChanged = networkControllerClass.methods.firstOrNull { method ->
                method.name == "setCurrentSubscriptionsLocked" && method.parameterCount == 1
            } ?: error("NetworkControllerImpl.setCurrentSubscriptionsLocked was not found")
            module.hook(subscriptionsChanged)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-subscriptions")
                .intercept { chain ->
                    val result = chain.proceed()
                    synchronized(stackedMobileSignalLock) {
                        stackedMobileNetworkController = chain.thisObject
                    }
                    updateStackedMobileSubscriptions(chain.getArg(0) as? List<*>)
                    refreshStackedMobilePresentations(enabled)
                    result
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Stacked mobile subscription hook unavailable", error)
        }

        runCatching {
            val binderClass = loadFirstClass(classLoader, MOBILE_ICON_BINDER_CLASSES)
            val bindMethod = binderClass.methods.firstOrNull { method ->
                method.name == "bind" && method.parameterCount > 0 &&
                    ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: error("MiuiMobileIconBinder.bind was not found")
            module.hook(bindMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-bind")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.getArg(0) as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            captureMobileNetworkTypeSource(root, chain.getArg(2))
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Stacked mobile binder hook unavailable", error)
        }

        runCatching {
            val interactorClass = classLoader.loadClass(
                "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl",
            )
            val typeMethods = interactorClass.methods.filter { method ->
                method.name == "getMobileTypeName" && method.parameterCount == 1
            }
            if (typeMethods.isEmpty()) error("MiuiMobileIconInteractorImpl.getMobileTypeName was not found")
            typeMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:mobile-network-type-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val interactor = chain.thisObject
                        val subId = readInheritedField(interactor, "subId") as? Number
                        if (subId != null && result is String) {
                            synchronized(stackedMobileSignalLock) {
                                stackedMobileNetworkTypes[subId.toInt()] = result
                            }
                            refreshStackedMobilePresentations(enabled)
                        }
                        result
                    }
            }
            hookCount += typeMethods.size
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Mobile network type hook unavailable", error)
        }

        runCatching {
            val mobileViewClass = loadFirstClass(classLoader, MODERN_MOBILE_VIEW_CLASSES)
            val constructMethod = mobileViewClass.methods.firstOrNull { method ->
                method.name == "constructAndBind"
            } ?: error("ModernStatusBarMobileView.constructAndBind was not found")
            module.hook(constructMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-construct")
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++

            // StatusIconContainer lays out StatusIconDisplayable children from
            // isIconVisible(), not from View.visibility.  Hiding the secondary root alone
            // therefore leaves its measured width in the status-bar spacing calculation.
            val isIconVisibleMethod = mobileViewClass.methods.firstOrNull { method ->
                method.name == "isIconVisible" && method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            } ?: error("ModernStatusBarMobileView.isIconVisible was not found")
            module.hook(isIconVisibleMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-icon-visible")
                .intercept { chain ->
                    val view = chain.thisObject as? ViewGroup
                    if (view != null && shouldSuppressStackedMobileIcon(view)) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Stacked mobile construction hook unavailable", error)
        }

        runCatching {
            module.hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-drawable")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ImageView
                    if (view != null && isStackedMobilePresentationSignal(view)) {
                        refreshStackedMobilePresentations(enabled)
                    }
                    result
                }
            module.hook(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-resource")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ImageView
                    if (view != null && isStackedMobilePresentationSignal(view)) {
                        refreshStackedMobilePresentations(enabled)
                    }
                    result
                }
            module.hook(ImageView::class.java.getMethod("setImageTintList", ColorStateList::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-tint")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ImageView)?.let { view ->
                        refreshStackedMobilePresentationForView(view, enabled)
                    }
                    result
                }

            // Some HyperOS status-bar builds use a PorterDuff color filter for dark-icon
            // transitions instead of updating ImageView.imageTintList.  The replacement glyph
            // and the independent label must follow that path too. Hook every public overload so
            // vendor changes in the filter API do not leave either presentation stale.
            ImageView::class.java.methods
                .filter { it.name == "setColorFilter" }
                .distinctBy { method -> method.parameterTypes.toList() }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:stacked-mobile-color-filter-$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            (chain.thisObject as? ImageView)?.let { view ->
                                refreshStackedMobilePresentationForView(view, enabled)
                            }
                            result
                        }
                }
            ImageView::class.java.methods
                .firstOrNull { it.name == "clearColorFilter" && it.parameterCount == 0 }
                ?.let { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:stacked-mobile-clear-color-filter")
                        .intercept { chain ->
                            val result = chain.proceed()
                            (chain.thisObject as? ImageView)?.let { view ->
                                refreshStackedMobilePresentationForView(view, enabled)
                            }
                            result
                        }
                }

            // A stateful tint can change when SystemUI refreshes the ImageView drawable state,
            // without calling setImageTintList again. Coalescing in the presentation scheduler
            // keeps this broad hook cheap while covering those transitions.
            module.hook(View::class.java.getMethod("refreshDrawableState"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-drawable-state")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ImageView)?.let { view ->
                        refreshStackedMobilePresentationForView(view, enabled)
                    }
                    result
                }
            hookCount += 4
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Stacked mobile drawable hooks unavailable", error)
        }

        // The original mobile_type_single TextView is the most stable rendered network-type
        // source across HyperOS builds. Mirror its text after SystemUI updates it, while keeping
        // the injected TextView out of this hook by its private tag.
        runCatching {
            module.hook(TextView::class.java.getMethod("setText", CharSequence::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:mobile-network-type-text")
                .intercept { chain ->
                    val result = chain.proceed()
                    val sourceView = chain.thisObject as? TextView
                    if (sourceView != null && sourceView.tag != INDEPENDENT_MOBILE_TYPE_TAG &&
                        stackedMobileApplying.get() != true
                    ) {
                        val presentation = synchronized(stackedMobileSignalLock) {
                            stackedMobilePresentations.values.firstOrNull {
                                it.networkTypeView === sourceView
                            }
                        }
                        if (presentation != null) {
                            refreshStackedMobilePresentationForView(presentation.signal, enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Mobile network type text hook unavailable", error)
        }

        // SystemUI may apply the resolved dark/light color directly to the independent
        // TextView after the signal callback. Re-apply the signal's effective color after either
        // TextView.setTextColor overload so that late framework updates cannot overwrite it.
        runCatching {
            TextView::class.java.methods
                .filter { it.name == "setTextColor" }
                .distinctBy { method -> method.parameterTypes.toList() }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:independent-mobile-type-color-$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val sourceView = chain.thisObject as? TextView
                            if (sourceView != null && stackedMobileApplying.get() != true) {
                                val presentation = synchronized(stackedMobileSignalLock) {
                                    stackedMobilePresentations.values.firstOrNull {
                                        it.independentType === sourceView
                                    }
                                }
                                if (presentation != null) {
                                    stackedMobileApplying.set(true)
                                    try {
                                        sourceView.setTextColor(resolveMobileSignalColor(presentation.signal))
                                    } finally {
                                        stackedMobileApplying.remove()
                                    }
                                }
                            }
                            result
                        }
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Independent mobile network type color hook unavailable", error)
        }

        // The modern view assigns its subscription id after inflation. This also covers builds
        // where the binder call is hidden behind a generated lambda.
        runCatching {
            val modernClass = loadFirstClass(classLoader, MODERN_MOBILE_VIEW_CLASSES)
            val setSubId = modernClass.methods.firstOrNull { method ->
                method.name == "setSubId" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            } ?: error("ModernStatusBarMobileView.setSubId was not found")
            module.hook(setSubId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-sub-id")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            module.log(Log.DEBUG, logTag, "Stacked mobile subId hook unavailable", error)
        }

        if (hookCount > 0) {
            module.log(Log.INFO, logTag, "Installed stacked mobile signal hooks ($hookCount)")
        } else {
            module.log(Log.WARN, logTag, "No stacked mobile signal hooks could be installed")
        }
    }

    private fun updateStackedMobileSubscription(controller: Any) {
        readInstanceField(controller, "mNetworkController")?.let { networkController ->
            synchronized(stackedMobileSignalLock) { stackedMobileNetworkController = networkController }
        }
        val subscriptionInfo = readInstanceField(controller, "mSubscriptionInfo") ?: return
        val subscriptionId = invokeInt(subscriptionInfo, "getSubscriptionId") ?: return
        val slotIndex = invokeInt(subscriptionInfo, "getSimSlotIndex")
            ?: SubscriptionManager.getSlotIndex(subscriptionId)
        if (slotIndex < 0) return
        val currentState = readInheritedField(controller, "mCurrentState")
        val reportedDataSim = currentState?.let { readInheritedField(it, "dataSim") as? Boolean }
        val dataSim = resolveDataSim(subscriptionId, reportedDataSim)
        val dataConnected = currentState?.let { readInheritedField(it, "dataConnected") as? Boolean }
        val isDefault = currentState?.let { readInheritedField(it, "isDefault") as? Boolean }
        val signalLevel = (readInheritedField(currentState, "level") as? Number)
            ?.toInt()
            ?.coerceIn(0, 4)
            ?: 0
        synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[subscriptionId] = StackedMobileSubscription(
                slot = slotIndex,
                dataSim = dataSim,
                dataConnected = dataConnected,
                isDefault = isDefault,
                signalLevel = signalLevel,
            )
            stackedMobileActiveSubscriptionIds += subscriptionId
        }
    }

    private fun updateStackedMobileSubscriptions(subscriptions: List<*>?) {
        if (subscriptions == null) return
        val updated = LinkedHashMap<Int, StackedMobileSubscription>()
        subscriptions.forEach { subscriptionInfo ->
            if (subscriptionInfo == null) return@forEach
            val subscriptionId = invokeInt(subscriptionInfo, "getSubscriptionId") ?: return@forEach
            val slotIndex = invokeInt(subscriptionInfo, "getSimSlotIndex")
                ?: SubscriptionManager.getSlotIndex(subscriptionId)
            if (slotIndex < 0) return@forEach
            val previous = synchronized(stackedMobileSignalLock) {
                stackedMobileSubscriptions[subscriptionId]
            }
            updated[subscriptionId] = StackedMobileSubscription(
                slot = slotIndex,
                dataSim = resolveDataSim(subscriptionId, previous?.dataSim),
                dataConnected = previous?.dataConnected,
                isDefault = previous?.isDefault,
                signalLevel = previous?.signalLevel ?: 0,
            )
        }
        synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions.clear()
            stackedMobileSubscriptions.putAll(updated)
            stackedMobileActiveSubscriptionIds.clear()
            stackedMobileActiveSubscriptionIds.addAll(updated.keys)
        }
    }

    private fun captureMobileNetworkTypeSource(root: ViewGroup, vmImpl: Any?) {
        if (vmImpl == null) return
        val viewModel = runCatching {
            vmImpl.javaClass.methods.firstOrNull {
                it.name == "getCellProvider" && it.parameterCount == 0
            }?.invoke(vmImpl)
        }.getOrNull() ?: return
        val source = readInheritedField(viewModel, "showName") ?: return
        synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[root]?.networkTypeSource = source
        }
    }

    /**
     * The dual-row glyph and the independent network-type label both live in the mobile group.
     * Keep discovery independent from the network-type mode: mode 2 intentionally creates no
     * label, but still needs the signal container for the dual-row glyph.
     */
    private fun captureMobileSignalLayout(presentation: StackedMobilePresentation) {
        presentation.mobileGroup =
            findViewByEntryName(presentation.root, "mobile_group") as? ViewGroup
        presentation.mobileSignalContainer =
            findViewByEntryName(presentation.root, "mobile_signal_container") as? ViewGroup
        presentation.systemMobileType =
            findViewByEntryName(presentation.root, "mobile_type") as? ImageView
    }

    private fun resolveDataSim(subscriptionId: Int, fallback: Boolean?): Boolean? {
        // MobileSignalController updates mCurrentState.dataSim from MobileStatus and from
        // ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED. Keep that state authoritative when it is
        // available, then fall back to the live active-data subscription for early callbacks.
        if (fallback != null) return fallback

        val activeDataSubscriptionId = runCatching {
            SubscriptionManager.getActiveDataSubscriptionId()
        }.getOrNull()
        if (activeDataSubscriptionId != null &&
            SubscriptionManager.isValidSubscriptionId(activeDataSubscriptionId)
        ) {
            return subscriptionId == activeDataSubscriptionId
        }

        val defaultDataSubscriptionId = runCatching {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }.getOrNull()
        if (defaultDataSubscriptionId != null &&
            SubscriptionManager.isValidSubscriptionId(defaultDataSubscriptionId)
        ) {
            return subscriptionId == defaultDataSubscriptionId
        }
        return null
    }

    private fun ensureIndependentMobileType(presentation: StackedMobilePresentation) {
        captureMobileSignalLayout(presentation)
        val group = presentation.mobileGroup ?: return

        val original = findViewByEntryName(presentation.root, "mobile_type_single") as? TextView
        presentation.networkTypeView = original
        val previous = presentation.independentType
        val textView = original ?: previous ?: TextView(group.context).also {
            it.tag = INDEPENDENT_MOBILE_TYPE_TAG
            it.includeFontPadding = false
            it.gravity = Gravity.CENTER_VERTICAL
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            presentation.independentType = it
        }
        if (previous != null && previous !== textView) {
            restoreIndependentMobileType(presentation)
        }
        if (original != null && previous != null && previous !== original &&
            previous.tag == INDEPENDENT_MOBILE_TYPE_TAG
        ) {
            (previous.parent as? ViewGroup)?.removeView(previous)
        }
        if (textView === original && textView.tag == INDEPENDENT_MOBILE_TYPE_TAG) {
            textView.tag = null
        }
        presentation.independentType = textView
    }

    private fun captureHorizontalMargins(view: View): IntArray? =
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }

    private fun applyHorizontalMargins(
        view: View,
        original: IntArray?,
        leftOffsetPx: Int,
        rightOffsetPx: Int,
    ) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val baseline = original ?: return
        params.leftMargin = baseline[0] + leftOffsetPx
        params.topMargin = baseline[1]
        params.rightMargin = baseline[2] + rightOffsetPx
        params.bottomMargin = baseline[3]
        view.layoutParams = params
    }

    private fun restoreIndependentMobileType(presentation: StackedMobilePresentation) {
        val textView = presentation.independentType ?: return
        val baselineView = presentation.savedIndependentView ?: textView
        baselineView.scaleX = 1f
        baselineView.scaleY = 1f
        presentation.savedIndependentTranslationY?.let { baselineView.translationY = it }
        presentation.savedIndependentMargins?.let { original ->
            applyHorizontalMargins(baselineView, original, 0, 0)
        }
        if (presentation.hasSavedIndependentTypeface) {
            baselineView.typeface = presentation.savedIndependentTypeface
        }
        presentation.savedIndependentView = null
        presentation.savedIndependentTypeface = null
        presentation.hasSavedIndependentTypeface = false
        presentation.savedIndependentTranslationY = null
        presentation.savedIndependentMargins = null
        val originalParent = presentation.savedIndependentParent
        if (originalParent != null && textView.parent !== originalParent) {
            (textView.parent as? ViewGroup)?.removeView(textView)
            textView.layoutParams = presentation.savedIndependentLayoutParams
            originalParent.addView(
                textView,
                presentation.savedIndependentIndex.coerceIn(0, originalParent.childCount),
            )
        }
        presentation.savedIndependentParent = null
        presentation.savedIndependentIndex = -1
        presentation.savedIndependentLayoutParams = null
    }

    private fun applyIndependentMobileTypeTypeface(presentation: StackedMobilePresentation) {
        val textView = presentation.independentType ?: return
        if (!presentation.hasSavedIndependentTypeface) return
        if (stackedMobilePreferences?.getBoolean(KEY_MOBILE_NETWORK_TYPE_BOLD, false) == true) {
            textView.setTypeface(presentation.savedIndependentTypeface, Typeface.BOLD)
        } else {
            textView.typeface = presentation.savedIndependentTypeface
        }
    }

    private fun applyIndependentMobileType(
        presentation: StackedMobilePresentation,
        useStacked: Boolean,
    ) {
        val textView = presentation.independentType ?: return
        val group = presentation.mobileGroup ?: return
        val signalContainer = presentation.mobileSignalContainer
        val mode = stackedMobilePreferences?.getInt(mobileNetworkTypeModeKey, 0)
            ?.coerceIn(0, 2) ?: 0
        val position = stackedMobilePreferences?.getInt(KEY_MOBILE_NETWORK_TYPE_POSITION, 0)?.coerceIn(0, 1) ?: 0
        val slotIndex = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.slot
        } ?: SubscriptionManager.getSlotIndex(presentation.subscriptionId)
        // In normal mode preserve SystemUI's per-slot behavior.  Once the two roots are merged,
        // only the retained slot-0 root may render the independent type.
        val displayLogic = stackedMobilePreferences
            ?.getInt(KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC, 0)
            ?.coerceIn(0, 1) ?: 0
        val shouldShow = mode == 1 &&
            (!useStacked || slotIndex == 0) &&
            (displayLogic == 0 || isUsingMobileData(presentation))

        if (mode == 1) {
            if (presentation.savedIndependentView !== textView) {
                restoreIndependentMobileType(presentation)
                presentation.savedIndependentView = textView
                presentation.savedIndependentTypeface = textView.typeface
                presentation.hasSavedIndependentTypeface = true
                presentation.savedIndependentTranslationY = textView.translationY
                presentation.savedIndependentMargins = captureHorizontalMargins(textView)
            }
            if (textView.parent !== group) {
                presentation.savedIndependentParent = textView.parent as? ViewGroup
                presentation.savedIndependentIndex = presentation.savedIndependentParent
                    ?.indexOfChild(textView) ?: -1
                presentation.savedIndependentLayoutParams = textView.layoutParams
                (textView.parent as? ViewGroup)?.removeView(textView)
                val containerIndex = signalContainer?.let(group::indexOfChild) ?: -1
                val insertIndex = if (containerIndex >= 0) containerIndex else group.childCount
                group.addView(textView, insertIndex)
            }
            val baseTranslationY = presentation.savedIndependentTranslationY ?: 0f
            val density = textView.resources.displayMetrics.density
            val scale = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_SCALE, 1f)
                ?.coerceIn(0.1f, 3f) ?: 1f
            val verticalOffset = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_VERTICAL_OFFSET, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            val leftMargin = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_LEFT_MARGIN, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            val rightMargin = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_RIGHT_MARGIN, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            textView.scaleX = scale
            textView.scaleY = scale
            textView.translationY = baseTranslationY + verticalOffset * density
            applyIndependentMobileTypeTypeface(presentation)
            applyHorizontalMargins(
                textView,
                presentation.savedIndependentMargins,
                (leftMargin * density).roundToInt(),
                (rightMargin * density).roundToInt(),
            )
        } else {
            restoreIndependentMobileType(presentation)
        }

        if (mode == 1 && signalContainer != null) {
            val containerIndex = group.indexOfChild(signalContainer)
            if (containerIndex >= 0) {
                val targetIndex = containerIndex + if (position == 0) 0 else 1
                if (group.indexOfChild(textView) != targetIndex) {
                    group.removeView(textView)
                    val currentContainerIndex = group.indexOfChild(signalContainer)
                    if (currentContainerIndex >= 0) {
                        group.addView(
                            textView,
                            (currentContainerIndex + if (position == 0) 0 else 1)
                                .coerceIn(0, group.childCount),
                        )
                    } else {
                        group.addView(textView)
                    }
                }
            }
        }

        when (mode) {
            1 -> {
                val sourceValue = readCurrentFlowValue(presentation.networkTypeSource)
                val realType = sourceValue?.toString().orEmpty().ifBlank {
                    synchronized(stackedMobileSignalLock) {
                        stackedMobileNetworkTypes[presentation.subscriptionId].orEmpty()
                    }
                }.ifBlank { presentation.networkTypeView?.text?.toString().orEmpty() }
                val customText = stackedMobilePreferences
                    ?.getString(KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT, "")
                    .orEmpty()
                val displayText = customText.ifBlank { realType }
                stackedMobileApplying.set(true)
                try {
                    textView.text = formatIndependentMobileType(
                        displayText,
                        realType,
                        stackedMobilePreferences?.getBoolean(KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A, false) == true,
                    )
                } finally {
                    stackedMobileApplying.remove()
                }
                applyIndependentMobileTypeTypeface(presentation)
                val color = resolveMobileSignalColor(presentation.signal)
                textView.setTextColor(color)
                setStackedMobileViewVisibility(
                    textView,
                    if (shouldShow && displayText.isNotEmpty()) View.VISIBLE else View.GONE,
                )
            }
            2 -> setStackedMobileViewVisibility(textView, View.GONE)
            else -> if (textView.tag == INDEPENDENT_MOBILE_TYPE_TAG) {
                setStackedMobileViewVisibility(textView, View.GONE)
            }
        }
    }

    private fun formatIndependentMobileType(
        displayText: String,
        realType: String,
        shrink5gaA: Boolean,
    ): CharSequence {
        if (!shrink5gaA || !realType.equals("5GA", ignoreCase = true) || !displayText.endsWith("A")) {
            return displayText
        }
        return SpannableString(displayText).apply {
            setSpan(
                RelativeSizeSpan(0.5f),
                length - 1,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Matches the mobile data state used by SystemUI's MobileState. */
    private fun isUsingMobileData(presentation: StackedMobilePresentation): Boolean {
        val cached = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]
        }
        if (cached?.dataSim != null && cached.dataConnected != null && cached.isDefault != null) {
            return cached.isDefault && cached.dataSim && cached.dataConnected
        }

        val context = presentation.root.context
        val airplaneMode = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
        }.getOrDefault(false)
        if (airplaneMode) return false
        if (presentation.subscriptionId != SubscriptionManager.getDefaultDataSubscriptionId()) {
            return false
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun anchorSystemMobileTypeToDualSignal(presentation: StackedMobilePresentation) {
        val mobileType = presentation.systemMobileType ?: return
        val dualContainer = presentation.dualContainer ?: return
        val params = mobileType.layoutParams ?: return
        val marginParams = params as? ViewGroup.MarginLayoutParams
        if (presentation.savedSystemMobileTypeEndToStart == null) {
            presentation.savedSystemMobileTypeEndToStart = getLayoutParamInt(params, "endToStart")
            presentation.savedSystemMobileTypeTopToTop = getLayoutParamInt(params, "topToTop")
            presentation.savedSystemMobileTypeEndMargin = marginParams?.marginEnd
            presentation.savedSystemMobileTypeTopMargin = marginParams?.topMargin
        }
        setLayoutParamInt(params, "endToStart", dualContainer.id)
        setLayoutParamInt(params, "topToTop", dualContainer.id)
        val gapPx = (mobileType.resources.displayMetrics.density * 0.5f).roundToInt()
        val dualWidth = dualContainer.width.takeIf { it > 0 }
            ?: resolveDualMobileSignalWidth(presentation.signal)
        val rightShiftPx = (dualWidth * 0.4f).roundToInt()
        marginParams?.let { margins ->
            presentation.savedSystemMobileTypeEndMargin?.let {
                // Reducing the end margin moves the type toward the signal by 40% of its width.
                margins.setMarginEnd(it + gapPx - rightShiftPx)
            }
            presentation.savedSystemMobileTypeTopMargin?.let {
                margins.topMargin = it - gapPx
            }
        }
        mobileType.layoutParams = params
    }

    private fun restoreSystemMobileType(presentation: StackedMobilePresentation) {
        val mobileType = presentation.systemMobileType
        val params = mobileType?.layoutParams
        if (mobileType != null && params != null) {
            presentation.savedSystemMobileTypeEndToStart?.let {
                setLayoutParamInt(params, "endToStart", it)
            }
            presentation.savedSystemMobileTypeTopToTop?.let {
                setLayoutParamInt(params, "topToTop", it)
            }
            val marginParams = params as? ViewGroup.MarginLayoutParams
            presentation.savedSystemMobileTypeEndMargin?.let {
                marginParams?.setMarginEnd(it)
            }
            presentation.savedSystemMobileTypeTopMargin?.let {
                marginParams?.topMargin = it
            }
            mobileType.layoutParams = params
        }
        presentation.savedSystemMobileTypeEndToStart = null
        presentation.savedSystemMobileTypeTopToTop = null
        presentation.savedSystemMobileTypeEndMargin = null
        presentation.savedSystemMobileTypeTopMargin = null
    }

    private fun readCurrentFlowValue(flow: Any?): Any? = flow?.let {
        runCatching {
            it.javaClass.methods.firstOrNull { method ->
                method.name == "getValue" && method.parameterCount == 0
            }?.invoke(it)
        }.getOrNull() ?: readInheritedField(it, "value")
    }

    private fun registerStackedMobilePresentation(root: ViewGroup, enabled: () -> Boolean) {
        val signal = findViewByEntryName(root, "mobile_signal") as? ImageView ?: return
        val subscriptionId = invokeInt(root, "getSubId")
            ?: (readInstanceField(root, "subId") as? Number)?.toInt()
            ?: return
        if (subscriptionId < 0) return
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[root]?.also {
                it.signal = signal
                it.subscriptionId = subscriptionId
            } ?: StackedMobilePresentation(root, signal, subscriptionId).also {
                stackedMobilePresentations[root] = it
            }
        }
        registerMobileNetworkStateCallback(root.context, enabled)
        captureMobileSignalLayout(presentation)
        enforceSystemMobileSignalVisibility(presentation)
        if (stackedMobilePreferences?.getInt(mobileNetworkTypeModeKey, 0) == 1) {
            ensureIndependentMobileType(presentation)
        }
        if (enabled()) ensureDualMobileSignal(presentation)
        if (!presentation.attachListenerInstalled) {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    scheduleStackedMobilePresentationRefresh(presentation, enabled)
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            presentation.attachListenerInstalled = true
        }
        requestStackedMobileParentLayout(root)
    }

    private fun registerMobileNetworkStateCallback(
        context: Context,
        enabled: () -> Boolean,
    ) {
        synchronized(stackedMobileSignalLock) {
            if (stackedMobileNetworkCallbackRegistered) return
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshStackedMobilePresentations(enabled)
                }

                override fun onLost(network: Network) {
                    refreshStackedMobilePresentations(enabled)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refreshStackedMobilePresentations(enabled)
                }
            }
            runCatching {
                connectivity.registerDefaultNetworkCallback(callback)
                stackedMobileNetworkCallbackRegistered = true
            }.onFailure { error ->
                module.log(Log.DEBUG, logTag, "Mobile network state callback unavailable", error)
            }
        }
    }

    private fun isStackedMobilePresentationSignal(view: ImageView): Boolean {
        if (stackedMobileApplying.get() == true) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any { it.signal === view }
        }
    }

    fun isIndependentMobileTypeView(view: View?): Boolean {
        if (view == null) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any { it.independentType === view }
        }
    }

    private fun enforceSystemMobileSignalVisibility(presentation: StackedMobilePresentation) {
        val mode = stackedMobilePreferences
            ?.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0)
            ?.coerceIn(0, 2) ?: 0
        if (mode == 0) return
        val target = presentation.mobileSignalContainer ?: presentation.signal
        if (shouldHideSystemMobileSignal(target, mode)) {
            // The binder may have made the container visible before the presentation was
            // registered. Apply the setting once so the first state update is not required.
            target.visibility = View.GONE
        }
    }

    fun shouldHideSystemMobileSignal(view: View?, configuredMode: Int): Boolean {
        val mode = configuredMode.coerceIn(0, 2)
        if (mode == 0 || view == null) return false
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.firstOrNull { presentation ->
                presentation.signal === view || isDescendantOf(view, presentation.root)
            }
        } ?: return mode == 2
        if (mode == 2) return true
        val cachedDataSim = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.dataSim
        }
        val dataSim = resolveDataSim(presentation.subscriptionId, cachedDataSim)
        // If SystemUI has not reported the state for this subscription yet, keep the
        // icon visible instead of hiding the wrong SIM during initialization.
        return dataSim == false
    }

    private fun isDescendantOf(view: View, root: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun isStackedSecondaryMobileRoot(view: View?): Boolean {
        if (view == null) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any {
                it.root === view && it.rootHiddenByStacked
            }
        }
    }

    private fun shouldSuppressStackedMobileIcon(view: ViewGroup): Boolean {
        if (stackedMobilePreferences?.getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false) != true) {
            return false
        }
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[view]
        } ?: return false
        if (!isStackedMobileDualSim()) return false

        // The merged glyph is mounted in the physical slot-0 view.  The order of the rows may
        // put the data SIM first, but it must not decide which StatusIconContainer child remains
        // measurable: on devices with the data SIM in slot 1 that hid slot 0 while the renderer
        // simultaneously hid slot 1, leaving no mobile glyph at all.
        val slotIndex = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.slot
        } ?: SubscriptionManager.getSlotIndex(presentation.subscriptionId)
        return slotIndex > 0
    }

    private fun refreshStackedMobilePresentations(enabled: () -> Boolean) {
        val presentations = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.toList()
        }
        presentations.forEach { scheduleStackedMobilePresentationRefresh(it, enabled) }
    }

    private fun refreshStackedMobilePresentationForView(view: ImageView, enabled: () -> Boolean) {
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.firstOrNull { it.signal === view }
        } ?: return
        scheduleStackedMobilePresentationRefresh(presentation, enabled)
    }

    /**
     * Binder callbacks run before their status-bar root joins the window.  Posting from that
     * phase is not reliable: a View can discard the callback before it is attached.  The attach
     * listener registered above performs the first render; this helper only schedules work for
     * roots that can receive it and coalesces later signal updates to one callback per root.
     */
    private fun scheduleStackedMobilePresentationRefresh(
        presentation: StackedMobilePresentation,
        enabled: () -> Boolean,
    ) {
        if (!presentation.root.isAttachedToWindow) return
        val shouldPost = synchronized(stackedMobileSignalLock) {
            if (presentation.refreshPending) false else {
                presentation.refreshPending = true
                true
            }
        }
        if (!shouldPost) return
        presentation.root.post {
            try {
                applyStackedMobilePresentation(presentation, enabled())
            } finally {
                synchronized(stackedMobileSignalLock) { presentation.refreshPending = false }
            }
        }
    }

    private fun applyStackedMobilePresentation(presentation: StackedMobilePresentation, enabled: Boolean) {
        if (!presentation.root.isAttachedToWindow) return
        val useStacked = enabled && isStackedMobileDualSim()
        val mobileNetworkTypeMode = stackedMobilePreferences
            ?.getInt(mobileNetworkTypeModeKey, 0)
            ?.coerceIn(0, 2) ?: 0
        if (!useStacked || mobileNetworkTypeMode != 0) {
            restoreSystemMobileType(presentation)
        }
        applyIndependentMobileType(presentation, useStacked)
        if (!useStacked) {
            restoreSecondaryMobileRoot(presentation)
            if (presentation.dualContainer != null) restoreDualMobileSignal(presentation)
            return
        }

        val slotIndex = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.slot
        } ?: SubscriptionManager.getSlotIndex(presentation.subscriptionId)
        if (slotIndex > 0) {
            hideSecondaryMobileRoot(presentation)
            return
        }
        if (slotIndex < 0) {
            restoreSecondaryMobileRoot(presentation)
            restoreDualMobileSignal(presentation)
            return
        }
        restoreSecondaryMobileRoot(presentation)

        val orderedSubscriptions = stackedMobileRenderOrder().mapNotNull { subscriptionId ->
            synchronized(stackedMobileSignalLock) { stackedMobileSubscriptions[subscriptionId] }
        }
        val upperSubscription = orderedSubscriptions.getOrNull(0)
        val lowerSubscription = orderedSubscriptions.getOrNull(1)
        if (upperSubscription == null || lowerSubscription == null) {
            restoreDualMobileSignal(presentation)
            return
        }
        // Binder timing differs between HyperOS builds.  Re-check the hierarchy here because
        // this render can happen after a previously incomplete inflation pass.
        ensureDualMobileSignal(presentation)
        val dualContainer = presentation.dualContainer ?: run {
            restoreDualMobileSignal(presentation)
            return
        }
        val dualSignal = presentation.dualSignal ?: run {
            restoreDualMobileSignal(presentation)
            return
        }
        if (presentation.savedDualMargins == null) {
            presentation.savedDualTranslationY = dualContainer.translationY
            presentation.savedDualMargins = captureHorizontalMargins(dualContainer)
        }
        updateDualMobileSignalLayout(presentation)
        val density = dualContainer.resources.displayMetrics.density
        val scale = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_SCALE, 1f)
            ?.coerceIn(0.1f, 3f) ?: 1f
        val verticalOffset = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_VERTICAL_OFFSET, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        val leftMargin = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_LEFT_MARGIN, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        val rightMargin = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_RIGHT_MARGIN, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        dualContainer.scaleX = 1.06f * scale
        dualContainer.scaleY = scale
        dualContainer.translationY =
            (presentation.savedDualTranslationY ?: 0f) + verticalOffset * density
        applyHorizontalMargins(
            dualContainer,
            presentation.savedDualMargins,
            (leftMargin * density).roundToInt(),
            (rightMargin * density).roundToInt(),
        )
        val sourceTint = presentation.signal.imageTintList
            ?: resolveDrawableTintList(presentation.signal.drawable)
        val sourceColorFilter = resolveMobileSignalColorFilter(presentation.signal)
        dualSignal.setImageDrawable(
            StackedMobileDrawable(upperSubscription.signalLevel, lowerSubscription.signalLevel).apply {
                alpha = presentation.signal.imageAlpha
                setTintList(sourceTint)
                state = presentation.signal.drawableState
                sourceColorFilter?.let(::setColorFilter)
            },
        )
        if (mobileNetworkTypeMode == 0) {
            // SystemUI anchors mobile_type to mobile_signal.  The latter is GONE while the
            // merged glyph is active, so keep the stock network type anchored to its replacement.
            anchorSystemMobileTypeToDualSignal(presentation)
        }
        setDualMobileSignalVisibility(presentation, true)
    }

    private fun isStackedMobileDualSim(): Boolean = synchronized(stackedMobileSignalLock) {
        val controllerCount = stackedMobileNetworkController?.let { networkController ->
            val controllers = readInstanceField(networkController, "mMobileSignalControllers")
            (controllers as? android.util.SparseArray<*>)?.size() ?: 0
        } ?: 0
        stackedMobileActiveSubscriptionIds.size >= 2 || controllerCount >= 2
    }

    private fun stackedMobileRenderOrder(): List<Int> = synchronized(stackedMobileSignalLock) {
        val active = LinkedHashSet(stackedMobileActiveSubscriptionIds).apply {
            stackedMobilePresentations.values
                .filter { it.root.isAttachedToWindow }
                .forEach { add(it.subscriptionId) }
        }.toList()
        active.sortedWith(compareBy<Int>(
            { if (stackedMobileSubscriptions[it]?.dataSim == true) 0 else 1 },
            { stackedMobileSubscriptions[it]?.slot ?: SubscriptionManager.getSlotIndex(it) },
            { it },
        ))
    }

    private fun ensureDualMobileSignal(presentation: StackedMobilePresentation) {
        captureMobileSignalLayout(presentation)
        val signalContainer = presentation.mobileSignalContainer ?: run {
            module.log(Log.DEBUG, logTag, "Dual mobile signal container unavailable for subId=${presentation.subscriptionId}")
            return
        }
        val existing = signalContainer.findViewById<FrameLayout>(stackedMobileDualContainerId)
        val wasCreated = existing == null
        val dualContainer = existing ?: FrameLayout(signalContainer.context).apply {
            id = stackedMobileDualContainerId
            layoutParams = copyLayoutParams(presentation.signal) ?: ViewGroup.LayoutParams(
                resolveDualMobileSignalWidth(presentation.signal),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }.also { signalContainer.addView(it) }

        presentation.dualContainer = dualContainer
        presentation.dualSignal = dualContainer.getChildAt(0) as? ImageView ?: return
        configureDualMobileSignalConstraints(dualContainer)
        updateDualMobileSignalLayout(presentation)
        if (wasCreated) {
            // addView() happens after the parent has usually completed this frame's measure.
            // Keep the original signal visible until the next frame can measure the replacement.
            dualContainer.post {
                scheduleStackedMobilePresentationRefresh(presentation) {
                    stackedMobilePreferences?.getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false) == true
                }
            }
            module.log(
                Log.DEBUG,
                logTag,
                "Added dual mobile signal container for subId=${presentation.subscriptionId}, " +
                    "parent=${signalContainer.javaClass.name}",
            )
        }
    }

    private fun resolveDualMobileSignalWidth(signal: ImageView): Int {
        val density = signal.resources.displayMetrics.density
        val measured = signal.width
        if (measured > 0) return measured
        val layoutWidth = signal.layoutParams?.width ?: 0
        if (layoutWidth > 0) return layoutWidth
        if (signal.minimumWidth > 0) return signal.minimumWidth
        return (18f * density).roundToInt()
    }

    private fun updateDualMobileSignalLayout(presentation: StackedMobilePresentation) {
        val dualContainer = presentation.dualContainer ?: return
        val dualSignal = presentation.dualSignal ?: return
        val width = resolveDualMobileSignalWidth(presentation.signal)
        dualContainer.layoutParams?.let { params ->
            if (params.width != width) {
                params.width = width
                dualContainer.layoutParams = params
            }
        }
        dualSignal.layoutParams?.let { params ->
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                params.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                dualSignal.layoutParams = params
            }
        }
    }

    private fun configureDualMobileSignalConstraints(dualContainer: FrameLayout) {
        val params = dualContainer.layoutParams ?: return
        setLayoutParamInt(params, "endToEnd", 0)
        setLayoutParamInt(params, "startToStart", -1)
        setLayoutParamInt(params, "topToTop", 0)
        setLayoutParamInt(params, "bottomToBottom", 0)
        dualContainer.layoutParams = params
    }

    private fun copyLayoutParams(view: View): ViewGroup.LayoutParams? {
        val source = view.layoutParams ?: return null
        return runCatching {
            source.javaClass
                .getConstructor(ViewGroup.LayoutParams::class.java)
                .newInstance(source) as ViewGroup.LayoutParams
        }.getOrNull() ?: runCatching {
            ViewGroup.LayoutParams(source)
        }.getOrNull()
    }

    private fun getLayoutParamInt(params: ViewGroup.LayoutParams, name: String): Int? {
        var type: Class<*>? = params.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getInt(params)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun setLayoutParamInt(params: ViewGroup.LayoutParams, name: String, value: Int) {
        var type: Class<*>? = params.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.setInt(params, value)
                }
                return
            }
            type = type.superclass
        }
    }

    private fun setDualMobileSignalVisibility(presentation: StackedMobilePresentation, visible: Boolean) {
        val dualContainer = presentation.dualContainer
        val replacementReady = dualContainer != null &&
            presentation.dualSignal?.drawable != null &&
            dualContainer.parent === presentation.mobileSignalContainer &&
            dualContainer.isAttachedToWindow &&
            dualContainer.width > 0 && dualContainer.height > 0
        dualContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        // Do not trade a known-good system glyph for an unmeasured replacement.  This is also
        // important while SystemUI is rebuilding its status-bar hierarchy after configuration
        // changes.
        presentation.signal.visibility = if (visible && replacementReady) View.GONE else View.VISIBLE
        requestStackedMobileParentLayout(presentation.root)
    }

    private fun restoreDualMobileSignal(presentation: StackedMobilePresentation) {
        restoreSystemMobileType(presentation)
        val dualContainer = presentation.dualContainer
        if (dualContainer != null) {
            dualContainer.visibility = View.GONE
            dualContainer.scaleX = 1f
            dualContainer.scaleY = 1f
            presentation.savedDualTranslationY?.let { dualContainer.translationY = it }
            presentation.savedDualMargins?.let { original ->
                applyHorizontalMargins(dualContainer, original, 0, 0)
            }
        }
        presentation.signal.visibility = View.VISIBLE
        presentation.savedDualTranslationY = null
        presentation.savedDualMargins = null
        requestStackedMobileParentLayout(presentation.root)
    }

    private fun hideSecondaryMobileRoot(presentation: StackedMobilePresentation) {
        if (!presentation.rootHiddenByStacked) {
            presentation.savedRootVisibility = presentation.root.visibility
            presentation.rootHiddenByStacked = true
        }
        presentation.root.visibility = View.GONE
        presentation.root.requestLayout()
        (presentation.root.parent as? View)?.requestLayout()
    }

    private fun restoreSecondaryMobileRoot(presentation: StackedMobilePresentation) {
        if (!presentation.rootHiddenByStacked) return
        presentation.root.visibility = presentation.savedRootVisibility ?: View.VISIBLE
        presentation.savedRootVisibility = null
        presentation.rootHiddenByStacked = false
        presentation.root.requestLayout()
        (presentation.root.parent as? View)?.requestLayout()
    }

    private fun requestStackedMobileParentLayout(root: View) {
        (root.parent as? View)?.requestLayout()
    }

    private fun loadFirstClass(classLoader: ClassLoader, classNames: Array<String>): Class<*> {
        var lastError: Throwable? = null
        classNames.forEach { className ->
            try {
                return classLoader.loadClass(className)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw ClassNotFoundException(classNames.joinToString(), lastError)
    }

    private fun setStackedMobileViewVisibility(view: View, visibility: Int) {
        view.visibility = visibility
    }

    private fun invokeInt(target: Any, methodName: String): Int? = runCatching {
        (target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?.invoke(target) as? Number)?.toInt()
    }.getOrNull()

    private fun findViewByEntryName(root: ViewGroup, entryName: String): View? {
        val id = runCatching { root.resources.getIdentifier(entryName, "id", systemUiPackageName) }.getOrDefault(0)
        return if (id != 0) root.findViewById(id) else null
    }

    /**
     * Resolves the color currently rendered by the stock signal view. Depending on the
     * SystemUI generation, dark-icon transitions arrive as a stateful image tint, a
     * PorterDuff filter, or a tint/filter installed directly on the source drawable.
     */
    private fun resolveMobileSignalColor(signal: ImageView): Int {
        val state = signal.drawableState
        val tint = signal.imageTintList ?: resolveDrawableTintList(signal.drawable)
        val tintedColor = tint?.getColorForState(state, tint.defaultColor)
        val filter = signal.colorFilter ?: signal.drawable?.colorFilter
        val filteredColor = resolveColorFilterColor(filter)
        return filteredColor ?: tintedColor ?: Color.WHITE
    }

    private fun resolveMobileSignalColorFilter(signal: ImageView): ColorFilter? =
        signal.colorFilter ?: signal.drawable?.colorFilter

    private fun resolveDrawableTintList(drawable: Drawable?): ColorStateList? = drawable?.let {
        runCatching {
            it.javaClass.getMethod("getTintList").invoke(it) as? ColorStateList
        }.getOrNull()
    }

    private fun resolveColorFilterColor(filter: ColorFilter?): Int? {
        if (filter == null) return null
        return runCatching {
            filter.javaClass.getMethod("getColor").invoke(filter) as? Int
        }.getOrNull()
    }

    companion object {
        private const val KEY_MOBILE_NETWORK_TYPE_POSITION = "mobile_network_type_position"
private const val KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC = "mobile_network_type_display_logic"
        private const val KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT = "mobile_network_type_custom_text"
        private const val KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A = "mobile_network_type_shrink_5ga_a"
        private const val KEY_MOBILE_NETWORK_TYPE_BOLD = "mobile_network_type_bold"
        private const val INDEPENDENT_MOBILE_TYPE_TAG = "hyper_system_ui_hook.independent_mobile_type"
        private const val LEFT_STATUS_BAR_MANAGER_TAG = 0x7f0f0ad0
        private const val BATTERY_METER_VIEW_CLASS =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        private const val BATTERY_ICON_CLASS =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView"
        private const val BATTERY_INDICATOR_CLASS =
            "com.android.systemui.statusbar.views.BatteryIndicator"
        private const val BATTERY_HOLLOW_ICON_CLASS =
            "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView"
        private const val MODERN_STATUS_BAR_VIEW_CLASS =
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
        private const val WIFI_VIEW_BINDER_CLASS =
            "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
        private const val MOBILE_ICON_BINDER_CLASS =
            "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
        private val MOBILE_ICON_BINDER_CLASSES = arrayOf(
            MOBILE_ICON_BINDER_CLASS,
            "com.android.systemui.statusbar.pipeline.mobile.p130ui.binder.MiuiMobileIconBinder",
        )
        private val MODERN_MOBILE_VIEW_CLASSES = arrayOf(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            "com.android.systemui.statusbar.pipeline.mobile.p130ui.view.ModernStatusBarMobileView",
        )
        private var stackedMobileSignalHookInstalled = false
        private var statusBarIconsLeftHookInstalled = false
        private var statusBarAdjustmentHooksInstalled = false
        private var importedIconHooksInstalled = false
        private val importedIconLock = Any()
        private var importedIconRevision = Long.MIN_VALUE
        private var importedIcons: Map<String, ByteArray> = emptyMap()
        private val sizedIconViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val batteryIconLayouts = Collections.synchronizedMap(WeakHashMap<View, BatteryIconLayout>())
        private val spacedIconContainers = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val batteryStyleViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private var batteryStyleMethod: java.lang.reflect.Method? = null
        private var adjustmentPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var statusBarDarkIconManagerFactory: Any? = null
        private var statusBarDarkIconManagerConstructor: java.lang.reflect.Constructor<*>? = null
        private var statusBarDarkIconManagerArgs: Array<Any?>? = null
        private val batteryDrawableHistory =
            Collections.synchronizedMap(WeakHashMap<ImageView, Drawable>())
        private val batteryResourceRefreshSeen =
            Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        private val restoringBatteryDrawable = ThreadLocal<Boolean>()
        private val stackedMobileSignalLock = Any()
        private var stackedMobilePreferences: SharedPreferences? = null
        private var stackedMobilePreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var stackedMobilePreferenceListenerRegistered = false
        private var stackedMobileNetworkCallbackRegistered = false
        private var stackedMobileNetworkController: Any? = null
        private val stackedMobileSubscriptions = LinkedHashMap<Int, StackedMobileSubscription>()
        private val stackedMobileNetworkTypes = LinkedHashMap<Int, String>()
        private val stackedMobileActiveSubscriptionIds = LinkedHashSet<Int>()
        private val stackedMobilePresentations = WeakHashMap<ViewGroup, StackedMobilePresentation>()
        private val stackedMobileApplying = ThreadLocal<Boolean>()
    }
}
