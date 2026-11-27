// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Turns a wide (2x1 or wider) Do Not Disturb (`quietmode`) Control Center tile into an iOS-style
 * Focus Mode capsule pill with:
 * - Left circular icon button: directly toggles system Do Not Disturb ON/OFF.
 * - Right label + ArrowUpDown chevron zone (or long-press): opens a fullscreen blurred Focus Mode
 *   picker overlay inside the Control Center window (without a "New Focus" button).
 * All Focus modes toggle the real system Do Not Disturb tile while displaying custom icons/labels.
 */
internal class ControlCenterFocusModeHook(private val module: XposedModule) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val cardStates = WeakHashMap<ViewGroup, FocusCardState>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var activeOverlay: ActiveFocusOverlay? = null

    @Volatile private var focusSettings = ControlCenterFocusModeSettings()
    @Volatile private var localActiveModeId: String? = null
    @Volatile private var dndWideEnabled = false

    private class FocusCardState {
        var isDndCard: Boolean = false
        var dndActive: Boolean = false
        var focusStyleApplied: Boolean = false
        var nativeClickAction: Any? = null
        var nativeLongClickAction: Any? = null
        var lastTouchX: Float = -1f
        var overlayRow: LinearLayout? = null
        var iconCircle: FrameLayout? = null
        var iconView: ImageView? = null
        var labelView: TextView? = null
        var arrowView: ImageView? = null
    }

    private class ActiveFocusOverlay(
        val host: ViewGroup,
        val overlayRoot: FrameLayout,
        val stack: LinearLayout,
        val hiddenPanelTarget: View?,
        val collapseDeltaY: Float,
    )

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        syncPreferenceState(preferences)
        runCatching { classLoader.loadClass(QS_CARD_ITEM_VIEW) }.getOrNull()?.let {
            onClassLoaded(it, preferences)
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name != QS_CARD_ITEM_VIEW || !scheduled.add(clazz)) return
        syncPreferenceState(preferences)
        // Never enumerate target methods inside ClassLoader.loadClass (ART crash audit).
        mainHandler.post { installCardHooks(clazz) }
    }

    private fun syncPreferenceState(preferences: SharedPreferences) {
        val nextFocus = readControlCenterFocusModeSettings(preferences)
        focusSettings = nextFocus
        if (localActiveModeId == null || nextFocus.modes.none { it.id == localActiveModeId }) {
            localActiveModeId = nextFocus.activeId
        }
        dndWideEnabled = readIsDndWide(preferences)
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == null ||
                key == KEY_CC_FOCUS_MODE_ENABLED ||
                key == KEY_CC_FOCUS_MODE_LIST ||
                key == KEY_CC_FOCUS_MODE_ACTIVE_ID ||
                key == KEY_CC_TILE_LAYOUT_ENABLED ||
                key == KEY_CC_TILE_LAYOUT ||
                key == KEY_CC_TILE_LAYOUT_LANDSCAPE ||
                key == KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE
            ) {
                val updated = readControlCenterFocusModeSettings(preferences)
                focusSettings = updated
                if (key == KEY_CC_FOCUS_MODE_ACTIVE_ID || updated.modes.none { it.id == localActiveModeId }) {
                    localActiveModeId = updated.activeId
                }
                dndWideEnabled = readIsDndWide(preferences)
                mainHandler.post { refreshTrackedCards() }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readIsDndWide(preferences: SharedPreferences): Boolean {
        val layout = readCcTileLayoutSettings(preferences)
        if (!layout.enabled) return false
        if (layout.tiles.any { it.id == "quietmode" && it.w >= 2 }) return true
        val landscape = readCcTileLayoutLandscapeSettings(preferences)
        return !landscape.adaptive && landscape.tiles.any { it.id == "quietmode" && it.w >= 2 }
    }

    private fun installCardHooks(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            method(clazz, "updateState", 3)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-state")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val stateObj = chain.getArg(0)
                        val spec = field(stateObj, "spec") as? String
                        val tileState = (field(stateObj, "state") as? Int) ?: 1
                        val cardState = cardStates.getOrPut(card) { FocusCardState() }
                        cardState.isDndCard = isDndSpec(spec)
                        cardState.dndActive = tileState == 2
                        syncCardAppearance(card, cardState)
                        if (cardState.isDndCard && activeOverlay != null) {
                            refreshActiveOverlaySelection(card, cardState)
                        }
                        result
                    }
            }

            method(clazz, "updateBackground", 2)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-bg")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val cardState = card?.let { cardStates[it] }
                        val tileStateObj = card?.let { field(it, "state") }
                        val stateField = tileStateObj?.let { findField(it.javaClass, "state") }
                        val suppressActiveFill = card != null &&
                            cardState != null &&
                            shouldApplyFocusStyle(card, cardState) &&
                            stateField != null &&
                            tileStateObj != null &&
                            runCatching { stateField.getInt(tileStateObj) == 2 }.getOrDefault(false)
                        if (suppressActiveFill) {
                            // Keep HyperOS native frosted glass card background even while active;
                            // only the circular icon badge flips to solid white + accent color (iOS style).
                            val fieldRef = stateField!!
                            val stateRef = tileStateObj!!
                            fieldRef.setInt(stateRef, 1)
                            try {
                                chain.proceed()
                            } finally {
                                fieldRef.setInt(stateRef, 2)
                            }
                        } else {
                            chain.proceed()
                        }
                    }
            }

            method(clazz, "updateCornerRadius", 0)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-radius")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val cardState = cardStates[card] ?: return@intercept result
                        if (shouldApplyFocusStyle(card, cardState)) {
                            applyPillCornerRadius(card)
                        }
                        result
                    }
            }

            method(clazz, "attachListeners", 4)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-listeners")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val rawClick = chain.getArg(0)
                        val rawLongClick = chain.getArg(3)
                        if (card != null) {
                            val cardState = cardStates.getOrPut(card) { FocusCardState() }
                            cardState.nativeClickAction = rawClick
                            cardState.nativeLongClickAction = rawLongClick
                            val wrappedClick = rawClick?.let { wrapFunction1(it, card, isLongClick = false) }
                            val wrappedLongClick = rawLongClick?.let { wrapFunction1(it, card, isLongClick = true) }
                            val args = arrayOf(
                                wrappedClick ?: rawClick,
                                chain.getArg(1),
                                chain.getArg(2),
                                wrappedLongClick ?: rawLongClick,
                            )
                            return@intercept chain.proceed(args)
                        }
                        chain.proceed()
                    }
            }

            method(clazz, "onTouchEvent", 1)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-touch")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val event = chain.getArg(0) as? MotionEvent
                        if (card != null && event != null) {
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                                cardStates.getOrPut(card) { FocusCardState() }.lastTouchX = event.x
                            }
                        }
                        chain.proceed()
                    }
            }

            method(clazz, "onLayout", 5)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-layout")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val cardState = cardStates[card] ?: return@intercept result
                        syncCardAppearance(card, cardState)
                        if (cardState.focusStyleApplied) {
                            layoutFocusOverlay(card, cardState)
                            applyPillCornerRadius(card)
                        }
                        result
                    }
            }

            method(clazz, "onDetachedFromWindow", 0)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-focus-mode:card-detach")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        if (card != null && cardStates[card]?.isDndCard == true) {
                            dismissFocusPicker(immediate = true)
                        }
                        chain.proceed()
                    }
            }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not install Focus Mode hooks", error)
        }
    }

    private fun wrapFunction1(original: Any, card: ViewGroup, isLongClick: Boolean): Any? {
        val fnInterface = original.javaClass.interfaces.firstOrNull { iface ->
            iface.declaredMethods.any { it.name == "invoke" && it.parameterCount == 1 }
        } ?: return null
        val cardRef = WeakReference(card)
        return runCatching {
            Proxy.newProxyInstance(fnInterface.classLoader, arrayOf(fnInterface)) { _, method, args ->
                if (method.name == "invoke" && args?.size == 1) {
                    val view = cardRef.get()
                    val state = view?.let { cardStates[it] }
                    if (view != null && state != null && shouldApplyFocusStyle(view, state)) {
                        if (isLongClick) {
                            showFocusPicker(view, state)
                            return@newProxyInstance true
                        }
                        val tappedIcon = isTouchOnIconCircle(view, state)
                        if (tappedIcon) {
                            return@newProxyInstance invokeFunction1(original, args[0])
                        } else {
                            showFocusPicker(view, state)
                            return@newProxyInstance Unit
                        }
                    }
                }
                method.invoke(original, *(args ?: emptyArray()))
            }
        }.getOrNull()
    }

    private fun invokeFunction1(fn: Any?, arg: Any?): Any? {
        fn ?: return null
        val m = fn.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == 1 } ?: return null
        m.isAccessible = true
        return m.invoke(fn, arg)
    }

    private fun isTouchOnIconCircle(card: ViewGroup, state: FocusCardState): Boolean {
        val x = state.lastTouchX
        if (x < 0f) return false
        val circle = state.iconCircle ?: return false
        val d = card.resources.displayMetrics.density
        val slop = 8f * d
        val rtl = card.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (rtl) {
            x >= (circle.left - slop)
        } else {
            x <= (circle.right + slop)
        }
    }

    private fun isDndSpec(spec: String?): Boolean {
        val s = spec?.lowercase(Locale.ROOT) ?: return false
        return s == "quietmode" || s == "dnd" || s == "zen"
    }

    private fun shouldApplyFocusStyle(card: ViewGroup, state: FocusCardState): Boolean {
        if (!focusSettings.enabled || !state.isDndCard || !dndWideEnabled) return false
        if (card.width > 0 && card.height > 0 && card.width < (card.height * 1.15f)) return false
        return true
    }

    private fun currentMode(): CcFocusModeItem {
        val modes = focusSettings.modes
        val activeId = localActiveModeId ?: focusSettings.activeId
        return modes.firstOrNull { it.id == activeId } ?: modes.first()
    }

    private fun refreshTrackedCards() {
        cardStates.entries.toList().forEach { (card, state) ->
            if (!state.isDndCard) return@forEach
            syncCardAppearance(card, state)
            runCatching { call(card, "updateBackground", false, true) }
            card.requestLayout()
            card.invalidate()
        }
    }

    private fun syncCardAppearance(card: ViewGroup, state: FocusCardState) {
        val shouldApply = shouldApplyFocusStyle(card, state)
        val scaleContent = find(card, "scale_content") as? ViewGroup
        if (!shouldApply) {
            if (state.focusStyleApplied) {
                state.focusStyleApplied = false
                state.overlayRow?.visibility = View.GONE
                scaleContent?.let { content ->
                    for (i in 0 until content.childCount) {
                        val child = content.getChildAt(i)
                        if (child !== state.overlayRow) child.visibility = View.VISIBLE
                    }
                }
                runCatching { call(card, "updateCornerRadius") }
            }
            return
        }

        val host = scaleContent ?: card
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child !== state.overlayRow) child.visibility = View.INVISIBLE
        }

        val row = ensureFocusOverlay(card, host, state)
        row.visibility = View.VISIBLE
        state.focusStyleApplied = true

        val mode = currentMode()
        val active = state.dndActive
        val circle = state.iconCircle
        val iconView = state.iconView
        val labelView = state.labelView
        val arrowView = state.arrowView

        val circleBg = (circle?.background as? GradientDrawable) ?: GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            circle?.background = it
        }
        circleBg.setColor(if (active) Color.WHITE else 0x2EFFFFFF)

        val iconTint = if (active) mode.accentColor else Color.WHITE
        iconView?.setImageDrawable(CcFocusIcons.modeDrawable(card.context, mode, iconTint))

        val locale = card.resources.configuration.locales[0] ?: Locale.getDefault()
        labelView?.text = if (active) mode.name else CcFocusModeDefaults.offTitle(locale)
        labelView?.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xEBFFFFFF.toInt())

        arrowView?.setImageDrawable(CcFocusIcons.arrowUpDownDrawable(if (active) 0xCCFFFFFF.toInt() else 0xA8FFFFFF.toInt()))

        if (card.width > 0 && card.height > 0) {
            layoutFocusOverlay(card, state)
            applyPillCornerRadius(card)
        }
    }

    private fun ensureFocusOverlay(card: ViewGroup, host: ViewGroup, state: FocusCardState): LinearLayout {
        state.overlayRow?.let { existing ->
            if (existing.parent === host) return existing
            (existing.parent as? ViewGroup)?.removeView(existing)
            host.addView(existing)
            return existing
        }
        val context = card.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()

        val circle = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))

        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(0f, 1.05f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val arrow = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(circle)
            addView(label)
            addView(arrow)
        }
        host.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        state.overlayRow = row
        state.iconCircle = circle
        state.iconView = icon
        state.labelView = label
        state.arrowView = arrow
        return row
    }

    private fun layoutFocusOverlay(card: ViewGroup, state: FocusCardState) {
        val row = state.overlayRow ?: return
        val circle = state.iconCircle ?: return
        val icon = state.iconView ?: return
        val label = state.labelView ?: return
        val arrow = state.arrowView ?: return
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return

        val d = card.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()

        val circleSize = minOf(dp(46), (h * 0.66f).roundToInt()).coerceAtLeast(dp(34))
        val iconSize = (circleSize * 0.48f).roundToInt().coerceAtLeast(dp(18))
        val startPad = ((h - circleSize) / 2).coerceIn(dp(10), dp(16))
        val gapAfterCircle = dp(10)
        val arrowW = dp(10)
        val arrowH = dp(15)
        val gapBeforeArrow = dp(6)
        val endPad = dp(14)

        row.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, w, h)

        val rtl = card.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val circleY = (h - circleSize) / 2
        val circleX = if (rtl) w - startPad - circleSize else startPad
        circle.measure(
            View.MeasureSpec.makeMeasureSpec(circleSize, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(circleSize, View.MeasureSpec.EXACTLY),
        )
        circle.layout(circleX, circleY, circleX + circleSize, circleY + circleSize)

        val iconInset = (circleSize - iconSize) / 2
        icon.measure(
            View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY),
        )
        icon.layout(iconInset, iconInset, iconInset + iconSize, iconInset + iconSize)

        val arrowY = (h - arrowH) / 2
        val arrowX = if (rtl) endPad else w - endPad - arrowW
        arrow.measure(
            View.MeasureSpec.makeMeasureSpec(arrowW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(arrowH, View.MeasureSpec.EXACTLY),
        )
        arrow.layout(arrowX, arrowY, arrowX + arrowW, arrowY + arrowH)

        val labelAvailW = (w - startPad - circleSize - gapAfterCircle - gapBeforeArrow - arrowW - endPad).coerceAtLeast(1)
        label.measure(
            View.MeasureSpec.makeMeasureSpec(labelAvailW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((h - dp(10)).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
        )
        val labelW = label.measuredWidth.coerceAtMost(labelAvailW)
        val labelH = label.measuredHeight.coerceAtMost(h)
        val labelY = (h - labelH) / 2
        val labelX = if (rtl) {
            (arrowX + arrowW + gapBeforeArrow + (labelAvailW - labelW)).coerceAtLeast(0)
        } else {
            circleX + circleSize + gapAfterCircle
        }
        label.layout(labelX, labelY, labelX + labelW, labelY + labelH)
    }

    private fun applyPillCornerRadius(card: ViewGroup) {
        val h = card.height
        if (h <= 0) return
        val w = card.width
        // True capsule pill for single-row wide Focus tiles (e.g. 2x1, 3x1, 4x1).
        val targetRadius = if (w > h * 1.4f) h / 2f else (26f * card.resources.displayMetrics.density)
        runCatching {
            findField(card.javaClass, "_cornerRadius")?.setFloat(card, targetRadius)
            call(card, "setCornerRadius", targetRadius)
            card.invalidateOutline()
        }
    }

    // --- HyperOS Brightness/Volume-style Focus Mode Picker Overlay (no "New Focus" button) ---

    private fun resolveOverlayHostAndPanelTarget(card: ViewGroup): Pair<ViewGroup, View?>? {
        val rootView = card.rootView as? ViewGroup ?: return null
        // Mirror HyperOS MainPanelAnimController: hide headerBinding.getRoot() (the parent of
        // main_panel_container) and attach the expanded Focus panel to ControlCenterWindowViewImpl
        // so the native Control Center wallpaper blur remains cleanly visible behind the pills.
        var cursor: View? = card
        var mainPanelContainer: View? = null
        while (cursor != null && cursor !== rootView) {
            val entryName = if (cursor.id != View.NO_ID) {
                runCatching { cursor.resources.getResourceEntryName(cursor.id) }.getOrNull()
            } else {
                null
            }
            if (entryName == "main_panel_container") {
                mainPanelContainer = cursor
                break
            }
            cursor = cursor.parent as? View
        }
        val mainPanelRoot = mainPanelContainer?.parent as? ViewGroup
        val windowContainer = mainPanelRoot?.parent as? ViewGroup
        if (mainPanelRoot != null && windowContainer != null) {
            return windowContainer to mainPanelRoot
        }
        // Fallback: hide the direct child of rootView containing the Focus card.
        var childOfRoot: View? = card
        while (childOfRoot != null && childOfRoot.parent !== rootView) {
            childOfRoot = childOfRoot.parent as? View
        }
        return rootView to childOfRoot
    }

    private fun showFocusPicker(card: ViewGroup, cardState: FocusCardState) {
        dismissFocusPicker(immediate = true)
        val (host, hiddenPanelTarget) = resolveOverlayHostAndPanelTarget(card) ?: return
        val context = card.context
        val pluginLoader = card.javaClass.classLoader
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()

        val cardLoc = IntArray(2)
        val hostLoc = IntArray(2)
        card.getLocationOnScreen(cardLoc)
        host.getLocationOnScreen(hostLoc)
        val hostCenterY = hostLoc[1] + host.height / 2f
        val cardCenterY = cardLoc[1] + card.height / 2f
        val initialDeltaY = ((cardCenterY - hostCenterY) * 0.22f).coerceIn(-96f * d, 96f * d)

        val overlayRoot = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            clipToPadding = false
            alpha = 0f
            elevation = 24f * d
            setOnClickListener { dismissFocusPicker(immediate = false) }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissFocusPicker(immediate = false)
                    true
                } else {
                    false
                }
            }
        }

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipChildren = false
            clipToPadding = false
        }

        val maxCardWidth = minOf(dp(336), (host.width - dp(44)).coerceAtLeast(dp(240)))
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(24))
            clipChildren = false
            clipToPadding = false
        }

        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val modes = focusSettings.modes
        val activeModeId = currentMode().id
        val useNativeMaterial = isNativeTileMaterialEnabled(pluginLoader, context)

        modes.forEachIndexed { index, mode ->
            val isSelectedAndOn = cardState.dndActive && mode.id == activeModeId
            val pill = buildFocusPickerRow(
                context = context,
                pluginLoader = pluginLoader,
                useNativeMaterial = useNativeMaterial,
                mode = mode,
                active = isSelectedAndOn,
                locale = locale,
                widthPx = maxCardWidth,
            ) {
                onFocusModePicked(card, cardState, mode)
            }
            val lp = LinearLayout.LayoutParams(maxCardWidth, dp(72)).apply {
                if (index > 0) topMargin = dp(12)
            }
            stack.addView(pill, lp)
        }

        scrollView.addView(
            stack,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )
        overlayRoot.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        updateBlurEnhanceClip(pluginLoader, overlayRoot, false)
        updateBlurEnhanceClip(pluginLoader, scrollView, false)
        updateBlurEnhanceClip(pluginLoader, stack, false)

        host.addView(
            overlayRoot,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        overlayRoot.requestFocus()

        val overlayRecord = ActiveFocusOverlay(host, overlayRoot, stack, hiddenPanelTarget, initialDeltaY)
        activeOverlay = overlayRecord

        // Match HyperOS MainPanelAnimController hide animation: alpha -> 0f, scale -> 0.95f.
        hiddenPanelTarget?.let { target ->
            target.animate().cancel()
            target.pivotX = target.width / 2f
            target.pivotY = target.height / 2f
            target.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (activeOverlay === overlayRecord) {
                        target.visibility = View.INVISIBLE
                    }
                }
                .start()
        }

        stack.scaleX = 0.88f
        stack.scaleY = 0.88f
        stack.translationY = initialDeltaY
        overlayRoot.animate()
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        stack.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun buildFocusPickerRow(
        context: Context,
        pluginLoader: ClassLoader?,
        useNativeMaterial: Boolean,
        mode: CcFocusModeItem,
        active: Boolean,
        locale: Locale,
        widthPx: Int,
        onClick: () -> Unit,
    ): FrameLayout {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val cornerRadiusPx = 36f * d

        val pill = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
                false
            }
            setOnClickListener { onClick() }
        }

        val nativeMaterialApplied = useNativeMaterial && applyNativeTileMaterial(pluginLoader, pill, active)
        if (!nativeMaterialApplied) {
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
                if (active) {
                    setColor(0xF0FFFFFF.toInt())
                    setStroke(dp(1), 0x4DFFFFFF)
                } else {
                    setColor(0x36FFFFFF)
                    setStroke(dp(1), 0x42FFFFFF)
                }
            }
        }

        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(CcFocusIcons.modeDrawable(context, mode, if (active) mode.accentColor else Color.WHITE))
        }
        pill.addView(
            iconView,
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(24)
            },
        )

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val titleView = TextView(context).apply {
            text = mode.name
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) 0xFF1C1C1E.toInt() else 0xF5FFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        textColumn.addView(titleView)

        val subText = if (active) CcFocusModeDefaults.activeOnLabel(locale) else mode.subtitle
        if (subText.isNotBlank()) {
            val subtitleView = TextView(context).apply {
                text = subText
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                setTextColor(if (active) 0x991C1C1E.toInt() else 0xB8FFFFFF.toInt())
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
            }
            textColumn.addView(subtitleView)
        }

        val horizontalSafeMargin = dp(64).coerceAtMost((widthPx * 0.24f).roundToInt())
        pill.addView(
            textColumn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                marginStart = horizontalSafeMargin
                marginEnd = horizontalSafeMargin
            },
        )

        return pill
    }

    private fun isNativeTileMaterialEnabled(pluginLoader: ClassLoader?, context: Context): Boolean {
        val loader = pluginLoader ?: return false
        return runCatching {
            val utilsClass = loader.loadClass("miui.systemui.controlcenter.utils.ControlCenterUtils")
            val m = utilsClass.getMethod("getBackgroundMaterialOpenedWithoutSuperPower", Context::class.java)
            (m.invoke(null, context) as? Boolean) == true
        }.getOrDefault(false)
    }

    private fun applyNativeTileMaterial(pluginLoader: ClassLoader?, view: View, active: Boolean): Boolean {
        val loader = pluginLoader ?: return false
        return runCatching {
            val blurCompatClass = loader.loadClass("miui.systemui.util.MiBlurCompat")
            val blendTokenClass = loader.loadClass("miui.systemui.util.MiuiColorBlendToken")
            val bgStyleClass = loader.loadClass("miui.systemui.util.MiBackgroundStyle")

            val blendInstance = blendTokenClass.getField("INSTANCE").get(null)
            val bgStyleInstance = bgStyleClass.getField("INSTANCE").get(null)

            val colorTokenMethod = if (active) {
                "getCC_TILE_ON_BLEND_COLORS"
            } else {
                "getCC_TILE_DEFAULT_BLEND_COLORS"
            }
            val glassTokenMethod = if (active) {
                "getACTIVATED_GLASS_TOKEN"
            } else {
                "getDEFAULT_GLASS_TOKEN"
            }
            val colorToken = blendTokenClass.getMethod(colorTokenMethod).invoke(blendInstance) ?: return false
            val glassToken = bgStyleClass.getMethod(glassTokenMethod).invoke(bgStyleInstance) ?: return false

            view.background = null
            val setBlurMode = blurCompatClass.getMethod("setMiViewBlurModeCompat", View::class.java, Int::class.javaPrimitiveType)
            setBlurMode.invoke(null, view, 1)

            val setStyle = bgStyleClass.methods.firstOrNull {
                it.name == "setMiBackgroundStyle" && it.parameterCount == 3
            } ?: return false
            setStyle.invoke(null, view, colorToken, glassToken)
            true
        }.getOrDefault(false)
    }

    private fun updateBlurEnhanceClip(pluginLoader: ClassLoader?, viewGroup: ViewGroup, clip: Boolean) {
        val loader = pluginLoader ?: return
        runCatching {
            val utilsClass = loader.loadClass("miui.systemui.controlcenter.utils.ControlCenterUtils")
            val instance = utilsClass.getField("INSTANCE").get(null)
            val m = utilsClass.methods.firstOrNull {
                it.name == "updateBlurEnhanceClip" && it.parameterCount == 2
            } ?: return
            m.invoke(instance, viewGroup, clip)
        }
    }

    private fun onFocusModePicked(card: ViewGroup, cardState: FocusCardState, picked: CcFocusModeItem) {
        val currentId = currentMode().id
        val wasActive = cardState.dndActive
        if (wasActive && picked.id == currentId) {
            // Tapping the active Focus mode turns DND OFF.
            invokeFunction1(cardState.nativeClickAction, card)
        } else {
            localActiveModeId = picked.id
            if (!wasActive) {
                // Turning DND ON with the newly selected Focus mode.
                invokeFunction1(cardState.nativeClickAction, card)
            }
            syncCardAppearance(card, cardState)
        }
        dismissFocusPicker(immediate = false)
    }

    private fun refreshActiveOverlaySelection(card: ViewGroup, cardState: FocusCardState) {
        if (activeOverlay == null) return
        // Keep picker synced if external DND state changed while open.
        syncCardAppearance(card, cardState)
    }

    private fun dismissFocusPicker(immediate: Boolean) {
        val current = activeOverlay ?: return
        activeOverlay = null
        current.overlayRoot.animate().cancel()
        current.stack.animate().cancel()
        current.hiddenPanelTarget?.let { target ->
            target.animate().cancel()
            target.visibility = View.VISIBLE
            if (immediate) {
                target.alpha = 1f
                target.scaleX = 1f
                target.scaleY = 1f
            } else {
                target.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        if (immediate) {
            runCatching { current.host.removeView(current.overlayRoot) }
            return
        }
        current.stack.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .translationY(current.collapseDeltaY)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        current.overlayRoot.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                runCatching { current.host.removeView(current.overlayRoot) }
            }
            .start()
    }

    private fun find(view: View, name: String): View? {
        val id = view.resources.getIdentifier(name, "id", PLUGIN_PACKAGE)
        return if (id != 0) view.findViewById(id) else null
    }

    private fun field(target: Any?, name: String): Any? =
        target?.let { findField(it.javaClass, name)?.get(it) }

    private fun findField(clazz: Class<*>, name: String): Field? =
        generateSequence(clazz) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
            .firstOrNull()

    private fun method(clazz: Class<*>, name: String, count: Int): Method? =
        generateSequence(clazz) { it.superclass }
            .mapNotNull { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == count } }
            .firstOrNull()
            ?.apply { isAccessible = true }

    private fun call(target: Any?, name: String, vararg args: Any): Any? {
        target ?: return null
        val m = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.name == name && m.parameterCount == args.size && m.parameterTypes.zip(args).all { (type, arg) ->
                    type.isInstance(arg) ||
                        (type == Int::class.javaPrimitiveType && arg is Int) ||
                        (type == Float::class.javaPrimitiveType && arg is Float) ||
                        (type == Boolean::class.javaPrimitiveType && arg is Boolean)
                }
            } ?: return null
        return m.apply { isAccessible = true }.invoke(target, *args)
    }

    companion object {
        private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val QS_CARD_ITEM_VIEW = "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
    }
}
