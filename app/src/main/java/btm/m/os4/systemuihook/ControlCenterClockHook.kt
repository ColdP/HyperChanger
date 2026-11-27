// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import btm.m.os4.systemuihook.ModuleLog as Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.TimeZone
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Like the top buttons, inject native header views without replacing the OEM header. */
internal class ControlCenterClockHook(private val module: XposedModule) {
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val bindings = WeakHashMap<ViewGroup, WeakReference<ClockBinding>>()
    private val controlCenterBatteries = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        targetClasses.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { onClassLoaded(it, preferences) }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in targetClasses || !installed.add(clazz)) return
        runCatching {
            when (clazz.name) {
                COMBINED_HEADER -> installCombinedHeader(clazz, preferences)
                BATTERY_METER_VIEW -> installBatteryPercentLeft(clazz, preferences)
            }
            registerPreferenceListener(preferences)
        }.onFailure {
            installed.remove(clazz)
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not hook control-center top content: ${clazz.name}", it)
        }
    }

    private fun installCombinedHeader(clazz: Class<*>, preferences: SharedPreferences) {
        listOf("start", "updateControlCenterHeaderLayout").forEach { name ->
            module.hook(clazz.getDeclaredMethod(name))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("control-center:big-clock:$name")
                .intercept { chain ->
                    val result = chain.proceed()
                    bind(chain.thisObject, preferences)
                    result
                }
        }
    }

    private fun installBatteryPercentLeft(clazz: Class<*>, preferences: SharedPreferences) {
        val setMeasuredDimension = View::class.java.getDeclaredMethod(
            "setMeasuredDimension",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        module.hook(clazz.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:battery-percent-left-measure")
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View ?: return@intercept result
                if ((field(view, "mLayoutFromTag") as? Number)?.toInt() == LAYOUT_FROM_CONTROL_CENTER) {
                    synchronized(controlCenterBatteries) { controlCenterBatteries.add(view) }
                    if (shouldPlaceBatteryPercentLeft(view, preferences)) {
                        val gapPx = (2f * view.resources.displayMetrics.density).roundToInt()
                        if (gapPx > 0) {
                            runCatching {
                                setMeasuredDimension.invoke(view, view.measuredWidth + gapPx, view.measuredHeight)
                            }
                        }
                    }
                }
                result
            }

        module.hook(
            clazz.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
        )
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:battery-percent-left-layout")
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View ?: return@intercept result
                if ((field(view, "mLayoutFromTag") as? Number)?.toInt() == LAYOUT_FROM_CONTROL_CENTER) {
                    synchronized(controlCenterBatteries) { controlCenterBatteries.add(view) }
                    if (shouldPlaceBatteryPercentLeft(view, preferences)) {
                        layoutBatteryPercentLeft(view)
                    }
                }
                result
            }
    }

    private fun registerPreferenceListener(preferences: SharedPreferences) {
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in preferenceKeys) {
                val bindingSnapshot = synchronized(bindings) { bindings.values.toList() }
                val batterySnapshot = synchronized(controlCenterBatteries) { controlCenterBatteries.map(::WeakReference) }
                handler.post {
                    bindingSnapshot.forEach { it.get()?.reload() }
                    batterySnapshot.forEach { it.get()?.requestLayout() }
                }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun shouldPlaceBatteryPercentLeft(view: View, preferences: SharedPreferences): Boolean {
        val topContent = readControlCenterTopContentSettings(preferences)
        if (topContent.mode != CONTROL_CENTER_TOP_CONTENT_SIGNAL || !topContent.batteryPercentLeft) return false
        val style = (field(view, "mBatteryStyle") as? Number)?.toInt() ?: return false
        if (style != BATTERY_STYLE_PERCENT_OUTSIDE) return false
        val digital = field(view, "mBatteryDigitalView") as? View ?: return false
        val percent = field(view, "mBatteryPercentContainer") as? View ?: return false
        return digital.visibility != View.GONE && percent.visibility != View.GONE
    }

    private fun layoutBatteryPercentLeft(view: View) {
        val digital = field(view, "mBatteryDigitalView") as? View ?: return
        val percent = field(view, "mBatteryPercentContainer") as? View ?: return
        val digitalW = digital.measuredWidth
        val percentW = percent.measuredWidth
        if (digitalW <= 0 || percentW <= 0) return

        val width = view.width
        val paddingStart = view.paddingStart
        val paddingEnd = view.paddingEnd
        val gapPx = (width - paddingStart - paddingEnd - percentW - digitalW)
            .coerceIn(0, (4f * view.resources.displayMetrics.density).roundToInt())
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (!rtl) {
            val pLeft = paddingStart
            val pRight = pLeft + percentW
            val dLeft = pRight + gapPx
            val dRight = dLeft + digitalW
            percent.layout(pLeft, percent.top, pRight, percent.bottom)
            digital.layout(dLeft, digital.top, dRight, digital.bottom)
        } else {
            val pRight = width - paddingStart
            val pLeft = pRight - percentW
            val dRight = pLeft - gapPx
            val dLeft = dRight - digitalW
            percent.layout(pLeft, percent.top, pRight, percent.bottom)
            digital.layout(dLeft, digital.top, dRight, digital.bottom)
        }
    }

    private fun bind(controller: Any, preferences: SharedPreferences) {
        val date = field(controller, "controlCenterDateView") as? TextView ?: return
        val root = field(controller, "headerView") as? FrameLayout ?: return
        val controlCenterHeader = field(controller, "controlCenterHeaderView") as? ViewGroup
        val carrierLayout = field(controller, "controlCenterCarrierLayout") as? View
        val statusBar = field(controller, "controlCenterStatusBar") as? View
        val statusIcons = field(controller, "controlCenterStatusIcons") as? ViewGroup
        val systemIcons = field(controller, "controlCenterSystemIcons") as? View
        val privacyContainer = field(controller, "controlCenterPrivacyContainer") as? View
        synchronized(bindings) {
            if (bindings[root]?.get() == null) {
                val binding = ClockBinding(
                    root = root,
                    controlCenterHeader = controlCenterHeader,
                    date = date,
                    carrierLayout = carrierLayout,
                    statusBar = statusBar,
                    statusIcons = statusIcons,
                    systemIcons = systemIcons,
                    privacyContainer = privacyContainer,
                    preferences = preferences,
                )
                bindings[root] = WeakReference(binding)
                binding.install()
            }
        }
    }

    private inner class ClockBinding(
        private val root: FrameLayout,
        private val controlCenterHeader: ViewGroup?,
        private val date: TextView,
        private val carrierLayout: View?,
        private val statusBar: View?,
        private val statusIcons: ViewGroup?,
        private val systemIcons: View?,
        private val privacyContainer: View?,
        private val preferences: SharedPreferences,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val clock = TextView(root.context).apply {
            tag = "hyperchanger.control-center.big-clock"
            id = View.generateViewId()
            setSingleLine(true)
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        private val signalView = ControlCenterTopSignalView(root.context).apply {
            visibility = View.GONE
        }
        private var topContentSettings = readControlCenterTopContentSettings(preferences)
        private var settings = readControlCenterClockSettings(preferences)
        private var formatter: SimpleDateFormat? = null
        private var formatterLocale: java.util.Locale? = null
        private var lastSecond = Long.MIN_VALUE
        private var lastSignalReadMs = 0L
        private var lastSignalSnapshot: ControlCenterSignalSnapshot? = null
        private var dateHiddenBySignal = false
        private var carrierHiddenBySignal = false
        private var systemIconsShiftedBySignal = false
        private var statusIconsMovedToSecondRow = false
        private var privacyCenteredBySignal = false
        private val addedIgnoredSlots = mutableListOf<String>()
        private var scheduled = false
        private var observer: ViewTreeObserver? = null
        private val matrix = Matrix()
        private val anchor = FloatArray(4)
        private val statusAnchor = FloatArray(4)
        private val headerAnchor = FloatArray(2)
        private val powerManager = root.context.getSystemService(PowerManager::class.java)
        private val tick = Runnable {
            scheduled = false
            update()
        }

        fun install() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
            reload()
        }

        fun reload() {
            topContentSettings = readControlCenterTopContentSettings(preferences)
            settings = readControlCenterClockSettings(preferences)
            formatter = null
            lastSecond = Long.MIN_VALUE
            lastSignalReadMs = 0L
            signalView.invalidateIcons()

            when (topContentSettings.mode) {
                CONTROL_CENTER_TOP_CONTENT_CLOCK -> {
                    restoreSignalOverrides()
                    (signalView.parent as? ViewGroup)?.removeView(signalView)
                    if (clock.parent == null) root.addView(clock, FrameLayout.LayoutParams(-2, -2))
                    clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.sizeSp)
                }
                CONTROL_CENTER_TOP_CONTENT_SIGNAL -> {
                    (clock.parent as? ViewGroup)?.removeView(clock)
                    if (signalView.parent == null) root.addView(signalView, FrameLayout.LayoutParams(-2, -2))
                    applySignalOverrides()
                }
                else -> {
                    restoreSignalOverrides()
                    (clock.parent as? ViewGroup)?.removeView(clock)
                    (signalView.parent as? ViewGroup)?.removeView(signalView)
                    cancelTick()
                }
            }
            findBatteryView()?.requestLayout()
            update()
        }

        override fun onViewAttachedToWindow(view: View) {
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            lastSignalReadMs = 0L
            update()
        }

        override fun onViewDetachedFromWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            cancelTick()
        }

        override fun onPreDraw(): Boolean {
            update()
            return true
        }

        private fun cancelTick() {
            handler.removeCallbacks(tick)
            scheduled = false
        }

        private fun update() {
            when (topContentSettings.mode) {
                CONTROL_CENTER_TOP_CONTENT_CLOCK -> updateClock()
                CONTROL_CENTER_TOP_CONTENT_SIGNAL -> updateSignal()
                else -> {
                    clock.visibility = View.GONE
                    signalView.visibility = View.GONE
                    cancelTick()
                }
            }
        }

        private fun updateClock() {
            signalView.visibility = View.GONE
            // Only the control-center date controls visibility. Notification, editing,
            // brightness-mirror and collapsed states retain their OEM behavior.
            var alpha = 1f
            var ancestor: View? = date
            while (ancestor != null && ancestor !== root) {
                if (ancestor.visibility != View.VISIBLE) alpha = 0f
                alpha *= ancestor.alpha
                ancestor = ancestor.parent as? View
            }
            val interactive = powerManager?.isInteractive != false
            val visible = root.isAttachedToWindow && root.isShown &&
                root.windowVisibility == View.VISIBLE && interactive && ancestor === root &&
                alpha > 0f && date.width > 0 && date.height > 0
            if (!visible) {
                clock.visibility = View.GONE
                cancelTick()
                return
            }
            clock.visibility = View.VISIBLE
            clock.alpha = alpha.coerceIn(0f, 1f)
            if (clock.currentTextColor != date.currentTextColor) clock.setTextColor(date.currentTextColor)
            val locale = date.resources.configuration.locales[0]
            if (formatter == null || formatterLocale != locale) {
                formatter = SimpleDateFormat(settings.format, locale)
                formatterLocale = locale
                lastSecond = Long.MIN_VALUE
            }
            val currentFormatter = checkNotNull(formatter)
            val zone = TimeZone.getDefault()
            if (currentFormatter.timeZone != zone) {
                currentFormatter.timeZone = zone
                lastSecond = Long.MIN_VALUE
            }
            val now = System.currentTimeMillis()
            if (lastSecond != now / 1000L) {
                val formatted = formatControlCenterClock(currentFormatter, now)
                val text = SpannableString(formatted.text)
                if (settings.redOne) formatted.hourOneIndices.forEach { index ->
                    text.setSpan(ForegroundColorSpan(Color.rgb(235, 0, 41)), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                clock.text = text
                clock.contentDescription = formatted.text
                lastSecond = now / 1000L
            }
            clock.typeface = date.typeface
            clock.layoutDirection = date.layoutDirection
            clock.measure(
                View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            matrix.reset()
            date.transformMatrixToGlobal(matrix)
            root.transformMatrixToLocal(matrix)
            anchor[0] = 0f
            anchor[1] = 0f
            anchor[2] = date.width.toFloat()
            anchor[3] = 0f
            matrix.mapPoints(anchor)
            val rtl = date.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val left = (if (rtl) anchor[2] - clock.measuredWidth else anchor[0]).roundToInt()
            val top = (anchor[1] - settings.gapDp * root.resources.displayMetrics.density - clock.measuredHeight).roundToInt()
            if (clock.left != left || clock.top != top || clock.width != clock.measuredWidth || clock.height != clock.measuredHeight) {
                clock.layout(left, top, left + clock.measuredWidth, top + clock.measuredHeight)
            }
            if (!scheduled) {
                scheduled = true
                handler.postDelayed(tick, 1000L - now % 1000L)
            }
        }

        private fun updateSignal() {
            clock.visibility = View.GONE
            applySignalOverrides()

            val visibilitySource = statusBar ?: date
            var alpha = 1f
            var ancestor: View? = visibilitySource
            while (ancestor != null && ancestor !== root) {
                if (ancestor.visibility != View.VISIBLE) alpha = 0f
                alpha *= ancestor.alpha
                ancestor = ancestor.parent as? View
            }
            val interactive = powerManager?.isInteractive != false
            val visible = root.isAttachedToWindow && root.isShown &&
                root.windowVisibility == View.VISIBLE && interactive && ancestor === root &&
                alpha > 0f && visibilitySource.width > 0 && visibilitySource.height > 0
            if (!visible) {
                signalView.visibility = View.GONE
                cancelTick()
                return
            }

            val now = System.currentTimeMillis()
            if (lastSignalSnapshot == null || now - lastSignalReadMs >= SIGNAL_REFRESH_INTERVAL_MS) {
                val snapshot = ControlCenterSignalStateReader.read(root.context, carrierLayout)
                lastSignalSnapshot = snapshot
                lastSignalReadMs = now
            }
            val snapshot = lastSignalSnapshot ?: return

            val density = root.resources.displayMetrics.density
            val header = controlCenterHeader
            val headerWidth = (header?.width?.takeIf { it > 0 } ?: root.width).coerceAtLeast(1)
            val padHorizontal = (header?.paddingStart ?: 0) + (header?.paddingEnd ?: 0)
            val headerInnerWidth = (headerWidth - padHorizontal).coerceAtLeast(1)
            val hasDateAnchor = date.width > 0 && date.height > 0 && date.visibility != View.GONE
            val dualPortrait = snapshot.isDualSim && hasDateAnchor
            val batteryView = findBatteryView()
            val batteryWidth = batteryView?.width?.takeIf { it > 0 } ?: 0
            val statusIconsWidth = statusIcons?.width?.takeIf { it > 0 } ?: 0
            val totalRightWidth = systemIcons?.width?.takeIf { it > 0 } ?: (batteryWidth + statusIconsWidth)
            val minGapPx = (12f * density).roundToInt()

            // In dual-SIM portrait mode, statusIcons move onto Row 2 so Row 1 only shares width with battery.
            val maxRow1Width = (headerInnerWidth - (if (dualPortrait) batteryWidth else totalRightWidth) - minGapPx)
                .coerceAtLeast((84f * density).roundToInt())
            val maxRow2Width = (headerInnerWidth - (if (dualPortrait) statusIconsWidth else totalRightWidth) - minGapPx)
                .coerceAtLeast((84f * density).roundToInt())

            val direction = (header ?: date).layoutDirection
            signalView.visibility = View.VISIBLE
            signalView.alpha = alpha.coerceIn(0f, 1f)
            signalView.applyStyle(
                color = date.currentTextColor,
                typeface = date.typeface,
                direction = direction,
                maxRow1WidthPx = maxRow1Width,
                maxRow2WidthPx = maxRow2Width,
            )
            signalView.bind(snapshot, topContentSettings)

            signalView.measure(
                View.MeasureSpec.makeMeasureSpec(maxOf(maxRow1Width, maxRow2Width), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )

            // Map statusBar center (never translated by us) for stable vertical alignment.
            val statusTarget = statusBar ?: date
            matrix.reset()
            statusTarget.transformMatrixToGlobal(matrix)
            root.transformMatrixToLocal(matrix)
            statusAnchor[0] = 0f
            statusAnchor[1] = 0f
            statusAnchor[2] = statusTarget.width.toFloat()
            statusAnchor[3] = statusTarget.height.toFloat()
            matrix.mapPoints(statusAnchor)
            val statusCenterY = (statusAnchor[1] + statusAnchor[3]) / 2f

            val rtl = direction == View.LAYOUT_DIRECTION_RTL
            val left: Int
            val top: Int
            if (hasDateAnchor) {
                matrix.reset()
                date.transformMatrixToGlobal(matrix)
                root.transformMatrixToLocal(matrix)
                anchor[0] = 0f
                anchor[1] = 0f
                anchor[2] = date.width.toFloat()
                anchor[3] = date.height.toFloat()
                matrix.mapPoints(anchor)
                left = (if (rtl) anchor[2] - signalView.measuredWidth else anchor[0]).roundToInt()
                if (snapshot.isDualSim) {
                    val dateBottom = anchor[3]
                    top = (dateBottom - signalView.measuredHeight).roundToInt()
                    val row1CenterY = top + signalView.firstRowCenterOffsetPx()
                    val row2CenterY = top + signalView.secondRowCenterOffsetPx()

                    // Align battery with Row 1; move statusIcons (originally left of battery) onto Row 2 right-aligned.
                    if (systemIcons != null) {
                        val shiftY = (row1CenterY - statusCenterY).coerceIn(-36f * density, 0f)
                        if (abs(systemIcons.translationY - shiftY) > 0.5f) {
                            systemIcons.translationY = shiftY
                        }
                        systemIconsShiftedBySignal = true
                    }
                    applyDualSimSecondRowStatusIcons(
                        rtl = rtl,
                        batteryWidth = batteryWidth,
                        rowDeltaY = (row2CenterY - row1CenterY).coerceAtLeast(0f),
                    )
                } else {
                    top = (statusCenterY - signalView.measuredHeight / 2f).roundToInt()
                    resetSystemIconsShift()
                    resetSecondRowStatusIcons()
                }
                positionPrivacyTopCenter(signalTopInRoot = top, density = density)
            } else {
                // Landscape/horizontal mode: anchor to header start padding & statusBar center.
                val anchorView = header ?: root
                matrix.reset()
                anchorView.transformMatrixToGlobal(matrix)
                root.transformMatrixToLocal(matrix)
                val startX = if (rtl) (anchorView.width - anchorView.paddingStart).toFloat() else anchorView.paddingStart.toFloat()
                anchor[0] = startX
                anchor[1] = 0f
                anchor[2] = startX
                anchor[3] = 0f
                matrix.mapPoints(anchor)
                left = (if (rtl) anchor[0] - signalView.measuredWidth else anchor[0]).roundToInt()
                top = (statusCenterY - signalView.measuredHeight / 2f).roundToInt()
                resetSystemIconsShift()
                resetSecondRowStatusIcons()
                restorePrivacyPosition()
            }

            if (signalView.left != left || signalView.top != top ||
                signalView.width != signalView.measuredWidth || signalView.height != signalView.measuredHeight
            ) {
                signalView.layout(left, top, left + signalView.measuredWidth, top + signalView.measuredHeight)
            }
            if (!scheduled) {
                scheduled = true
                handler.postDelayed(tick, SIGNAL_TICK_INTERVAL_MS)
            }
        }

        private fun applyDualSimSecondRowStatusIcons(rtl: Boolean, batteryWidth: Int, rowDeltaY: Float) {
            val icons = statusIcons ?: return
            // Ensure status_bar_area & statusBar do not clip the icons shifted onto Row 2.
            (systemIcons as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            (systemIcons?.parent as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            (statusBar as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            val targetTx = if (!rtl) batteryWidth.toFloat() else -batteryWidth.toFloat()
            if (abs(icons.translationX - targetTx) > 0.5f) icons.translationX = targetTx
            if (abs(icons.translationY - rowDeltaY) > 0.5f) icons.translationY = rowDeltaY
            statusIconsMovedToSecondRow = true
        }

        private fun resetSecondRowStatusIcons() {
            if (!statusIconsMovedToSecondRow) return
            statusIcons?.let {
                it.translationX = 0f
                it.translationY = 0f
            }
            statusIconsMovedToSecondRow = false
        }

        private fun resetSystemIconsShift() {
            if (!systemIconsShiftedBySignal) return
            systemIcons?.translationY = 0f
            systemIconsShiftedBySignal = false
        }

        /** Moves the Control Center privacy chip (location/camera/mic) to top-center like iOS. */
        private fun positionPrivacyTopCenter(signalTopInRoot: Int, density: Float) {
            val privacy = privacyContainer ?: return
            val header = controlCenterHeader ?: return
            if (privacy.visibility != View.VISIBLE) return
            val headerW = header.width
            val pW = privacy.width.takeIf { it > 0 } ?: privacy.measuredWidth
            val pH = privacy.height.takeIf { it > 0 } ?: privacy.measuredHeight
            if (headerW <= 0 || pW <= 0 || pH <= 0) return

            matrix.reset()
            header.transformMatrixToGlobal(matrix)
            root.transformMatrixToLocal(matrix)
            headerAnchor[0] = 0f
            headerAnchor[1] = 0f
            matrix.mapPoints(headerAnchor)
            val signalTopInHeader = (signalTopInRoot - headerAnchor[1]).roundToInt()

            val statusBarHeight = readStatusBarHeightPx(density)
            val preferredTop = statusBarHeight + (6f * density).roundToInt()
            val maxTop = (signalTopInHeader - pH - (6f * density).roundToInt())
                .coerceAtLeast((8f * density).roundToInt())
            val targetTop = minOf(preferredTop, maxTop)
            val centerLeft = ((headerW - pW) / 2f).roundToInt()

            if (privacy.left != centerLeft || privacy.top != targetTop ||
                privacy.width != pW || privacy.height != pH
            ) {
                privacy.layout(centerLeft, targetTop, centerLeft + pW, targetTop + pH)
            }
            privacyCenteredBySignal = true
        }

        private fun restorePrivacyPosition() {
            if (!privacyCenteredBySignal) return
            privacyCenteredBySignal = false
            privacyContainer?.requestLayout()
        }

        private fun readStatusBarHeightPx(density: Float): Int {
            val res = root.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            val height = if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
            return if (height > 0) height else (36f * density).roundToInt()
        }

        private fun findBatteryView(): View? {
            val delegate = statusBar?.let { field(it, "delegate") }
            val fromDelegate = delegate?.let { field(it, "battery") as? View }
            if (fromDelegate != null) return fromDelegate
            val container = systemIcons as? ViewGroup ?: return null
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                if (child.javaClass.name == BATTERY_METER_VIEW) return child
            }
            return null
        }

        private fun applySignalOverrides() {
            if (date.visibility == View.VISIBLE) {
                date.visibility = View.INVISIBLE
                dateHiddenBySignal = true
            }
            if (carrierLayout != null && carrierLayout.visibility == View.VISIBLE) {
                carrierLayout.visibility = View.INVISIBLE
                carrierHiddenBySignal = true
            }
            val container = statusIcons ?: return
            @Suppress("UNCHECKED_CAST")
            val ignored = runCatching { field(container, "ignoredSlots") as? MutableList<String> }.getOrNull() ?: return
            var changed = false
            SIGNAL_HIDDEN_SLOTS.forEach { slot ->
                if (slot !in ignored) {
                    ignored.add(slot)
                    if (slot !in addedIgnoredSlots) addedIgnoredSlots.add(slot)
                    changed = true
                }
            }
            if (changed) container.requestLayout()
        }

        private fun restoreSignalOverrides() {
            if (dateHiddenBySignal) {
                if (date.visibility == View.INVISIBLE) date.visibility = View.VISIBLE
                dateHiddenBySignal = false
            }
            if (carrierHiddenBySignal) {
                if (carrierLayout?.visibility == View.INVISIBLE) carrierLayout.visibility = View.VISIBLE
                carrierHiddenBySignal = false
            }
            resetSystemIconsShift()
            resetSecondRowStatusIcons()
            restorePrivacyPosition()
            val container = statusIcons
            if (container != null && addedIgnoredSlots.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val ignored = runCatching { field(container, "ignoredSlots") as? MutableList<String> }.getOrNull()
                if (ignored != null) {
                    val removed = ignored.removeAll(addedIgnoredSlots.toSet())
                    if (removed) container.requestLayout()
                }
                addedIgnoredSlots.clear()
            }
        }
    }

    companion object {
        private const val COMBINED_HEADER = "com.android.systemui.controlcenter.shade.CombinedHeaderController"
        private const val BATTERY_METER_VIEW = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        private const val LAYOUT_FROM_CONTROL_CENTER = 6
        private const val BATTERY_STYLE_PERCENT_OUTSIDE = 3
        private const val SIGNAL_REFRESH_INTERVAL_MS = 350L
        private const val SIGNAL_TICK_INTERVAL_MS = 1000L
        private val targetClasses = setOf(COMBINED_HEADER, BATTERY_METER_VIEW)
        private val SIGNAL_HIDDEN_SLOTS = listOf("mobile", "wifi", "slave_wifi", "no_sim", "hd", "airplane")
        private val preferenceKeys = setOf(
            KEY_CONTROL_CENTER_TOP_CONTENT_MODE,
            KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE,
            KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL,
            KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL,
            KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT,
            KEY_CONTROL_CENTER_CLOCK_ENABLED, KEY_CONTROL_CENTER_CLOCK_FORMAT, KEY_CONTROL_CENTER_CLOCK_SIZE,
            KEY_CONTROL_CENTER_CLOCK_GAP, KEY_CONTROL_CENTER_CLOCK_RED_ONE,
            KEY_STATUS_BAR_ICON_IMPORT_ENABLED,
            KEY_STATUS_BAR_ICON_IMPORT_REVISION,
            KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE,
        )

        private fun field(instance: Any, name: String): Any? =
            runCatching { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) }.getOrNull()
    }
}
