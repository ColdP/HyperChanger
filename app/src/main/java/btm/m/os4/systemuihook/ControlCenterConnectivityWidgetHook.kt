// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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

internal const val CC_CONNECTIVITY_TILE_SPEC = "custom_connectivity"

/**
 * Renders the 2x2 iOS-style Connectivity Widget (`custom_connectivity`) inside a native HyperOS
 * `QSCardItemView` and expands it using the same Brightness/Volume secondary-panel transition and
 * native tile glass material as `ControlCenterFocusModeHook`.
 *
 * All icons and localized labels are sourced live from SystemUI's real `QSTile` instances and the
 * user's active theme resources, with AirDrop replaced by Xiaomi Share (`misharetile`).
 */
internal class ControlCenterConnectivityWidgetHook(private val module: XposedModule) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val cardStates = WeakHashMap<ViewGroup, ConnectivityCardState>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var qsControllerRef: WeakReference<Any>? = null
    private var sysUiContextRef: WeakReference<Context>? = null
    private var activeOverlay: ActiveConnectivityOverlay? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var widgetSettings = ControlCenterConnectivityWidgetSettings()

    private enum class SlotKind(
        val candidates: List<String>,
        val activeColor: Int,
        val showChevronInExpanded: Boolean,
        val fallbackResNames: List<String>,
        val fallbackZh: String,
        val fallbackEn: String,
        val fallbackJa: String,
    ) {
        AIRPLANE(
            listOf("airplane"),
            0xFFFF9F0A.toInt(),
            false,
            listOf("airplane_mode", "quick_settings_airplane_mode_label"),
            "飞行模式",
            "Airplane Mode",
            "機内モード",
        ),
        MISHARE(
            listOf("misharetile", "custom(com.miui.mishare.connectivity/.tile.MiShareTileService)", "mishare"),
            0xFF0D84FF.toInt(),
            true,
            listOf("mishare_tile_label", "mi_share_title", "mishare_title"),
            "小米互传",
            "Xiaomi Share",
            "Xiaomi Share",
        ),
        WIFI(
            listOf("wifi"),
            0xFF0D84FF.toInt(),
            true,
            listOf("quick_settings_wifi_label", "wifi_label"),
            "WLAN",
            "Wi-Fi",
            "WLAN",
        ),
        CELL(
            listOf("cell"),
            0xFF30D158.toInt(),
            false,
            listOf("quick_settings_cellular_detail_title", "mobile_data"),
            "移动数据",
            "Cellular Data",
            "モバイルデータ",
        ),
        BLUETOOTH(
            listOf("bt"),
            0xFF0D84FF.toInt(),
            true,
            listOf("quick_settings_bluetooth_label", "bluetooth"),
            "蓝牙",
            "Bluetooth",
            "Bluetooth",
        ),
        HOTSPOT(
            listOf("hotspot"),
            0xFF30D158.toInt(),
            false,
            listOf("quick_settings_hotspot_label", "hotspot"),
            "个人热点",
            "Personal Hotspot",
            "アクセスポイント",
        ),
        EXTRA(
            listOf("vpn", "nfc", "gps"),
            0xFF0D84FF.toInt(),
            false,
            listOf("quick_settings_nfc_label", "quick_settings_location_label"),
            "NFC",
            "NFC",
            "NFC",
        ),
    }

    private class CircleSlotViews(
        val circle: FrameLayout,
        val icon: ImageView,
        var lastIconKey: Any? = null,
        var lastStateInt: Int = Int.MIN_VALUE,
        var lastTint: Int = 0,
    )

    private class ExpandedSlotViews(
        val circle: FrameLayout,
        val icon: ImageView,
        val title: TextView,
        val status: TextView,
        var lastIconKey: Any? = null,
        var lastStateInt: Int = Int.MIN_VALUE,
        var lastTint: Int = 0,
    )

    private class BoundTileEntry(
        val kind: SlotKind,
        val spec: String,
        val tile: Any,
        val owned: Boolean,
        val listeningToken: Any,
        var listening: Boolean = false,
        var callbackProxy: Any? = null,
        var lastState: Any? = null,
    )

    private class ConnectivityCardState {
        var isConnectivityCard: Boolean = false
        var widgetApplied: Boolean = false
        var attachedToWindow: Boolean = false
        var originalOutlineProvider: ViewOutlineProvider? = null
        var originalClipToOutline: Boolean = false
        var customOutlineApplied: Boolean = false
        var currentCornerRadiusPx: Float = -1f
        var widgetRoot: FrameLayout? = null
        var miniCluster: FrameLayout? = null
        val collapsedSlots = HashMap<SlotKind, CircleSlotViews>()
        val bindings = HashMap<SlotKind, BoundTileEntry>()
        val listeningToken = Any()
    }

    private class ActiveConnectivityOverlay(
        val cardRef: WeakReference<ViewGroup>,
        val host: ViewGroup,
        val overlayRoot: FrameLayout,
        val stack: LinearLayout,
        val hiddenPanelTarget: View?,
        val collapseDeltaX: Float,
        val collapseDeltaY: Float,
        val expandedSlots: HashMap<SlotKind, ExpandedSlotViews>,
    )

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        syncPreferenceState(preferences)
        listOf(QS_CARD_ITEM_VIEW, QS_CONTROLLER, QS_CARDS_CONTROLLER).forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { onClassLoaded(it, preferences) }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in TARGET_CLASSES || !scheduled.add(clazz)) return
        syncPreferenceState(preferences)
        // Never enumerate target methods inside ClassLoader.loadClass (ART crash audit).
        mainHandler.post {
            when (clazz.name) {
                QS_CONTROLLER -> installQsControllerHooks(clazz)
                QS_CARDS_CONTROLLER -> installQsCardsControllerHooks(clazz)
                QS_CARD_ITEM_VIEW -> installCardHooks(clazz)
            }
        }
    }

    private fun syncPreferenceState(preferences: SharedPreferences) {
        widgetSettings = readControlCenterConnectivityWidgetSettings(preferences)
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET) {
                widgetSettings = readControlCenterConnectivityWidgetSettings(preferences)
                mainHandler.post {
                    cardStates.entries.toList().forEach { (card, state) ->
                        if (state.isConnectivityCard) {
                            applyConnectivityCornerRadius(card)
                            card.invalidate()
                        }
                    }
                }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun installQsControllerHooks(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            clazz.declaredMethods
                .filter { it.name in setOf("getCardStyleTileSpecs", "updateExcludeTileSpecs", "getTile") }
                .forEach { target ->
                    module.hook(target)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("cc-connectivity:qs-ctrl:${target.name}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            captureQsController(chain.thisObject)
                            result
                        }
                }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not hook QSController for connectivity widget", error)
        }
    }

    private fun installQsCardsControllerHooks(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            clazz.declaredMethods
                .filter { it.name in setOf("createCardTiles", "onStart") && it.parameterCount == 0 }
                .forEach { target ->
                    module.hook(target)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("cc-connectivity:qs-cards:${target.name}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val qs = call(field(chain.thisObject, "qsController"), "get")
                            captureQsController(qs)
                            result
                        }
                }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not hook QSCardsController for connectivity widget", error)
        }
    }

    private fun captureQsController(controller: Any?) {
        if (controller == null) return
        val changed = qsControllerRef?.get() !== controller
        qsControllerRef = WeakReference(controller)
        (call(controller, "getContext") as? Context)?.let { ctx ->
            sysUiContextRef = WeakReference(ctx)
        }
        if (changed) {
            mainHandler.post {
                cardStates.entries.toList().forEach { (card, state) ->
                    if (state.isConnectivityCard && state.widgetApplied) {
                        ensureTileBindings(card, state)
                        refreshAllSlots(card, state)
                    }
                }
            }
        }
    }

    fun bindConnectivityCard(card: ViewGroup, qsController: Any?) {
        if (qsController != null) {
            captureQsController(qsController)
        }
        installCardHooks(card.javaClass)
        val cardState = cardStates.getOrPut(card) { ConnectivityCardState() }
        cardState.isConnectivityCard = true
        syncCardAppearance(card, cardState)
        if (card.width > 0 && card.height > 0) {
            layoutConnectivityWidget(card, cardState)
        }
    }

    private fun installCardHooks(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            method(clazz, "onBindViewHolder", 1)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-bind")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val record = chain.getArg(0)
                        val spec = call(record, "getSpec") as? String
                        field(record, "qsController")?.let(::captureQsController)
                        if (spec != null) {
                            val cardState = cardStates.getOrPut(card) { ConnectivityCardState() }
                            cardState.isConnectivityCard = spec == CC_CONNECTIVITY_TILE_SPEC
                            syncCardAppearance(card, cardState)
                        }
                        result
                    }
            }

            method(clazz, "updateState", 3)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-state")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val stateObj = chain.getArg(0)
                        val spec = field(stateObj, "spec") as? String
                        val cardState = cardStates.getOrPut(card) { ConnectivityCardState() }
                        if (spec != null) {
                            cardState.isConnectivityCard = spec == CC_CONNECTIVITY_TILE_SPEC
                        }
                        syncCardAppearance(card, cardState)
                        result
                    }
            }

            method(clazz, "updateBackground", 2)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-bg")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val cardState = card?.let { cardStates[it] }
                        val tileStateObj = card?.let { field(it, "state") }
                        val stateField = tileStateObj?.let { findField(it.javaClass, "state") }
                        val forceInactiveGlass = card != null &&
                            cardState?.isConnectivityCard == true &&
                            stateField != null &&
                            runCatching { stateField.getInt(tileStateObj) != 1 }.getOrDefault(false)
                        if (forceInactiveGlass) {
                            val fieldRef = stateField
                            val previous = fieldRef.getInt(tileStateObj)
                            fieldRef.setInt(tileStateObj, 1)
                            try {
                                chain.proceed()
                            } finally {
                                fieldRef.setInt(tileStateObj, previous)
                            }
                        } else {
                            chain.proceed()
                        }
                    }
            }

            method(clazz, "updateCornerRadius", 0)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-radius")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val cardState = cardStates[card] ?: return@intercept result
                        if (cardState.isConnectivityCard) {
                            applyConnectivityCornerRadius(card)
                        }
                        result
                    }
            }

            method(clazz, "attachListeners", 4)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-listeners")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val rawClick = chain.getArg(0)
                        val rawLongClick = chain.getArg(3)
                        if (card != null) {
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

            method(clazz, "onLayout", 5)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-layout")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val cardState = cardStates[card] ?: return@intercept result
                        if (cardState.isConnectivityCard) {
                            syncCardAppearance(card, cardState)
                            layoutConnectivityWidget(card, cardState)
                            applyConnectivityCornerRadius(card)
                        }
                        result
                    }
            }

            method(clazz, "onAttachedToWindow", 0)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-attach")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val card = chain.thisObject as? ViewGroup ?: return@intercept result
                        val cardState = cardStates[card] ?: return@intercept result
                        cardState.attachedToWindow = true
                        if (cardState.isConnectivityCard) {
                            ensureTileBindings(card, cardState)
                            setTileBindingsListening(cardState, true)
                            refreshAllSlots(card, cardState)
                        }
                        result
                    }
            }

            method(clazz, "onDetachedFromWindow", 0)?.let { target ->
                module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("cc-connectivity:card-detach")
                    .intercept { chain ->
                        val card = chain.thisObject as? ViewGroup
                        val cardState = card?.let { cardStates[it] }
                        if (card != null && cardState != null) {
                            cardState.attachedToWindow = false
                            setTileBindingsListening(cardState, false)
                            if (activeOverlay?.cardRef?.get() === card) {
                                dismissExpandedOverlay(immediate = true)
                            }
                        }
                        chain.proceed()
                    }
            }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not install Connectivity widget hooks", error)
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
                    if (view != null && state?.isConnectivityCard == true) {
                        if (isLongClick) {
                            showExpandedOverlay(view, state)
                            return@newProxyInstance true
                        }
                        // Plain click on empty gap of the 2x2 container does nothing accidental.
                        return@newProxyInstance Unit
                    }
                }
                method.invoke(original, *(args ?: emptyArray()))
            }
        }.getOrNull()
    }

    private fun syncCardAppearance(card: ViewGroup, state: ConnectivityCardState) {
        val scaleContent = find(card, "scale_content") as? ViewGroup
        val host = scaleContent ?: card
        if (!state.isConnectivityCard) {
            if (state.widgetApplied) {
                state.widgetApplied = false
                state.widgetRoot?.visibility = View.GONE
                setTileBindingsListening(state, false)
                detachTileBindings(state)
                if (state.customOutlineApplied) {
                    state.customOutlineApplied = false
                    state.currentCornerRadiusPx = -1f
                    card.outlineProvider = state.originalOutlineProvider
                    card.clipToOutline = state.originalClipToOutline
                    card.invalidateOutline()
                }
                for (i in 0 until host.childCount) {
                    val child = host.getChildAt(i)
                    if (child !== state.widgetRoot) child.visibility = View.VISIBLE
                }
            }
            return
        }

        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child !== state.widgetRoot) child.visibility = View.GONE
        }

        val wasApplied = state.widgetApplied
        val widgetRoot = ensureWidgetViews(card, host, state)
        widgetRoot.visibility = View.VISIBLE
        state.widgetApplied = true
        state.attachedToWindow = card.isAttachedToWindow

        ensureTileBindings(card, state)
        if (!wasApplied) {
            if (card.isAttachedToWindow) {
                setTileBindingsListening(state, true)
            }
            refreshAllSlots(card, state)
        }
        if (card.width > 0 && card.height > 0) {
            layoutConnectivityWidget(card, state)
            applyConnectivityCornerRadius(card)
        }
    }

    private fun applyConnectivityCornerRadius(card: ViewGroup) {
        val state = cardStates[card] ?: return
        if (!state.isConnectivityCard) return
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return
        val side = minOf(w, h)
        val d = card.resources.displayMetrics.density
        // Independent iOS 18/26 2x2 Connectivity concentric R-corner (~44dp at offset 0 dp),
        // completely decoupled from Control Center top-button corner radius settings.
        val baseRadius = side * 0.278f
        val targetRadius = (baseRadius + widgetSettings.tileCornerRadiusOffsetDp * d).coerceIn(0f, side / 2f)
        if (state.customOutlineApplied && kotlin.math.abs(state.currentCornerRadiusPx - targetRadius) < 0.25f) return
        if (!state.customOutlineApplied) {
            state.originalOutlineProvider = card.outlineProvider
            state.originalClipToOutline = card.clipToOutline
            state.customOutlineApplied = true
        }
        state.currentCornerRadiusPx = targetRadius
        runCatching {
            findField(card.javaClass, "_cornerRadius")?.setFloat(card, targetRadius)
            call(card, "setCornerRadius", targetRadius)
            card.clipToOutline = true
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, targetRadius)
                }
            }
            card.invalidateOutline()
        }
    }

    private fun ensureWidgetViews(card: ViewGroup, host: ViewGroup, state: ConnectivityCardState): FrameLayout {
        state.widgetRoot?.let { existing ->
            if (existing.parent === host) return existing
            (existing.parent as? ViewGroup)?.removeView(existing)
            host.addView(existing)
            return existing
        }

        val context = card.context
        val root = object : FrameLayout(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                layoutWidgetChildren(this, state, right - left, bottom - top)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Consume empty-gap taps without triggering the underlying placeholder tile.
            }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showExpandedOverlay(card, state)
                true
            }
        }

        fun makeCircle(kind: SlotKind, interactiveDirectToggle: Boolean): CircleSlotViews {
            val circle = FrameLayout(context).apply {
                clipToOutline = true
                outlineProvider = OVAL_OUTLINE_PROVIDER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x2EFFFFFF)
                }
                if (interactiveDirectToggle) {
                    isClickable = true
                    isFocusable = true
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.91f).scaleY(0.91f).setDuration(80L).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                        }
                        false
                    }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invokeSlotClick(card, state, kind)
                    }
                    setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showExpandedOverlay(card, state)
                        true
                    }
                } else {
                    isClickable = false
                    isFocusable = false
                }
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            circle.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            return CircleSlotViews(circle, icon).also { state.collapsedSlots[kind] = it }
        }

        val airplaneViews = makeCircle(SlotKind.AIRPLANE, interactiveDirectToggle = true)
        val mishareViews = makeCircle(SlotKind.MISHARE, interactiveDirectToggle = true)
        val wifiViews = makeCircle(SlotKind.WIFI, interactiveDirectToggle = true)

        val miniCluster = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.91f).scaleY(0.91f).setDuration(80L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }
                false
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showExpandedOverlay(card, state)
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showExpandedOverlay(card, state)
                true
            }
        }
        val cellViews = makeCircle(SlotKind.CELL, interactiveDirectToggle = false)
        val btViews = makeCircle(SlotKind.BLUETOOTH, interactiveDirectToggle = false)
        val hotspotViews = makeCircle(SlotKind.HOTSPOT, interactiveDirectToggle = false)
        val extraViews = makeCircle(SlotKind.EXTRA, interactiveDirectToggle = false)

        miniCluster.addView(cellViews.circle)
        miniCluster.addView(btViews.circle)
        miniCluster.addView(hotspotViews.circle)
        miniCluster.addView(extraViews.circle)

        root.addView(airplaneViews.circle)
        root.addView(mishareViews.circle)
        root.addView(wifiViews.circle)
        root.addView(miniCluster)

        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        state.widgetRoot = root
        state.miniCluster = miniCluster
        return root
    }

    private fun layoutConnectivityWidget(card: ViewGroup, state: ConnectivityCardState) {
        val root = state.widgetRoot ?: return
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return

        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        layoutWidgetChildren(root, state, w, h)
    }

    private fun layoutWidgetChildren(root: FrameLayout, state: ConnectivityCardState, w: Int, h: Int) {
        val miniCluster = state.miniCluster ?: return
        if (w <= 0 || h <= 0) return

        val side = minOf(w, h)
        val pad = (side * 0.095f).roundToInt()
        val gap = (side * 0.078f).roundToInt()
        val quad = ((side - 2 * pad - gap) / 2f).roundToInt().coerceAtLeast(1)
        val originX = (w - side) / 2 + pad
        val originY = (h - side) / 2 + pad
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL

        fun colX(col: Int): Int = if (rtl) {
            originX + (1 - col) * (quad + gap)
        } else {
            originX + col * (quad + gap)
        }

        fun layoutCircle(views: CircleSlotViews?, x: Int, y: Int, size: Int, iconRatio: Float) {
            views ?: return
            views.circle.measure(
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            )
            views.circle.layout(x, y, x + size, y + size)
            val iconSize = (size * iconRatio).roundToInt().coerceAtLeast(1)
            val inset = (size - iconSize) / 2
            views.icon.measure(
                View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY),
            )
            views.icon.layout(inset, inset, inset + iconSize, inset + iconSize)
        }

        layoutCircle(state.collapsedSlots[SlotKind.AIRPLANE], colX(0), originY, quad, 0.50f)
        layoutCircle(state.collapsedSlots[SlotKind.MISHARE], colX(1), originY, quad, 0.52f)
        layoutCircle(state.collapsedSlots[SlotKind.WIFI], colX(0), originY + quad + gap, quad, 0.50f)

        val clusterX = colX(1)
        val clusterY = originY + quad + gap
        miniCluster.measure(
            View.MeasureSpec.makeMeasureSpec(quad, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(quad, View.MeasureSpec.EXACTLY),
        )
        miniCluster.layout(clusterX, clusterY, clusterX + quad, clusterY + quad)

        val miniGap = (quad * 0.12f).roundToInt().coerceAtLeast(1)
        val miniSize = ((quad - miniGap) / 2f).roundToInt().coerceAtLeast(1)
        fun miniColX(col: Int): Int = if (rtl) (1 - col) * (miniSize + miniGap) else col * (miniSize + miniGap)

        layoutCircle(state.collapsedSlots[SlotKind.CELL], miniColX(0), 0, miniSize, 0.58f)
        layoutCircle(state.collapsedSlots[SlotKind.BLUETOOTH], miniColX(1), 0, miniSize, 0.58f)
        layoutCircle(state.collapsedSlots[SlotKind.HOTSPOT], miniColX(0), miniSize + miniGap, miniSize, 0.58f)
        layoutCircle(state.collapsedSlots[SlotKind.EXTRA], miniColX(1), miniSize + miniGap, miniSize, 0.58f)
    }

    // --- Live SystemUI QSTile Resolution & Real-Time State Sync ---

    private fun ensureTileBindings(card: ViewGroup, state: ConnectivityCardState) {
        val controller = qsControllerRef?.get() ?: return
        val pluginLoader = card.javaClass.classLoader ?: return

        val hostTileMap = HashMap<String, Any>()
        val host = field(controller, "host")
        val hostTiles = host?.let { call(it, "getTiles") as? Iterable<*> }
        hostTiles?.forEach { tile ->
            if (tile != null) {
                val spec = runCatching { call(tile, "getTileSpec") as? String }.getOrNull()
                if (!spec.isNullOrEmpty()) hostTileMap[spec] = tile
            }
        }

        val getTileMethod = method(controller.javaClass, "getTile", 1)
        val createTileMethod = method(controller.javaClass, "createTile", 1)

        SlotKind.entries.forEach { kind ->
            if (state.bindings.containsKey(kind)) return@forEach
            val resolved = resolveTile(controller, getTileMethod, createTileMethod, hostTileMap, kind.candidates) ?: return@forEach
            val (spec, tile, owned) = resolved
            val entry = BoundTileEntry(
                kind = kind,
                spec = spec,
                tile = tile,
                owned = owned,
                listeningToken = state.listeningToken,
                lastState = readTileState(tile),
            )
            registerTileCallback(pluginLoader, card, state, entry)
            state.bindings[kind] = entry
            if (state.attachedToWindow) {
                setEntryListening(entry, true)
            }
        }
    }

    private fun resolveTile(
        controller: Any,
        getTileMethod: Method?,
        createTileMethod: Method?,
        hostTileMap: Map<String, Any>,
        candidates: List<String>,
    ): Triple<String, Any, Boolean>? {
        // 1. Reuse existing live tile from QSCardsController / QSListController / MiuiQSHost (zero IPC).
        for (spec in candidates) {
            val record = getTileMethod?.let { runCatching { it.invoke(controller, spec) }.getOrNull() }
            val recordTile = record?.let { call(it, "getTile") }
            val tile = recordTile ?: hostTileMap[spec]
            if (tile != null) {
                val available = (call(tile, "isAvailable") as? Boolean) ?: true
                if (available) return Triple(spec, tile, false)
            }
        }
        // 2. Lazily create missing tile via QSController.createTile(spec).
        if (createTileMethod != null) {
            for (spec in candidates) {
                val created = runCatching { createTileMethod.invoke(controller, spec) }.getOrNull()
                if (created != null) {
                    val available = (call(created, "isAvailable") as? Boolean) ?: true
                    if (available) {
                        return Triple(spec, created, true)
                    } else {
                        runCatching { call(created, "destroy") }
                    }
                }
            }
        }
        return null
    }

    private fun registerTileCallback(
        pluginLoader: ClassLoader,
        card: ViewGroup,
        cardState: ConnectivityCardState,
        entry: BoundTileEntry,
    ) {
        val callbackIface = runCatching {
            pluginLoader.loadClass("com.android.systemui.plugins.qs.QSTile\$Callback")
        }.getOrNull() ?: return
        val cardRef = WeakReference(card)
        val proxy = Proxy.newProxyInstance(callbackIface.classLoader, arrayOf(callbackIface)) { self, method, args ->
            when (method.name) {
                "onStateChanged" -> {
                    val tileState = args?.firstOrNull()
                    entry.lastState = tileState
                    mainHandler.post {
                        val view = cardRef.get() ?: return@post
                        renderSlot(view, cardState, entry.kind, tileState)
                    }
                    null
                }
                "getCallbackType" -> 0
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "HyperChangerConnectivityCallback-${entry.kind.name}"
                else -> null
            }
        }
        entry.callbackProxy = proxy
        runCatching {
            entry.tile.javaClass.methods.firstOrNull { it.name == "addCallback" && it.parameterCount == 1 }
                ?.invoke(entry.tile, proxy)
        }
    }

    private fun setTileBindingsListening(state: ConnectivityCardState, listening: Boolean) {
        state.bindings.values.forEach { setEntryListening(it, listening) }
    }

    private fun setEntryListening(entry: BoundTileEntry, listening: Boolean) {
        if (entry.listening == listening) return
        entry.listening = listening
        runCatching {
            val m2 = entry.tile.javaClass.methods.firstOrNull { it.name == "setListening" && it.parameterCount == 2 }
            val m1 = entry.tile.javaClass.methods.firstOrNull { it.name == "setListening" && it.parameterCount == 1 }
            when {
                m2 != null -> m2.invoke(entry.tile, entry.listeningToken, listening)
                m1 != null -> m1.invoke(entry.tile, listening)
            }
            if (listening) {
                // Only force an initial refresh on tiles we created ourselves; refreshing shared
                // host tiles (especially MiShareTile, which allocates a new DrawableIcon on every
                // refreshState()) causes both the lower small tile and this widget to flash.
                if (entry.owned && entry.lastState == null) {
                    runCatching { call(entry.tile, "refreshState") }
                }
                entry.lastState = readTileState(entry.tile)
            }
        }
    }

    private fun detachTileBindings(state: ConnectivityCardState) {
        state.bindings.values.forEach { entry ->
            setEntryListening(entry, false)
            entry.callbackProxy?.let { cb ->
                runCatching {
                    entry.tile.javaClass.methods.firstOrNull { it.name == "removeCallback" && it.parameterCount == 1 }
                        ?.invoke(entry.tile, cb)
                }
            }
            if (entry.owned) {
                runCatching { call(entry.tile, "destroy") }
            }
        }
        state.bindings.clear()
    }

    private fun readTileState(tile: Any): Any? = runCatching { call(tile, "getState") }.getOrNull()

    private fun invokeSlotClick(card: ViewGroup, state: ConnectivityCardState, kind: SlotKind) {
        val entry = state.bindings[kind] ?: run {
            ensureTileBindings(card, state)
            state.bindings[kind]
        } ?: return
        runCatching {
            val click0 = entry.tile.javaClass.methods.firstOrNull { it.name == "click" && it.parameterCount == 0 }
            val click1 = entry.tile.javaClass.methods.firstOrNull { it.name == "click" && it.parameterCount == 1 }
            when {
                click1 != null -> click1.invoke(entry.tile, null)
                click0 != null -> click0.invoke(entry.tile)
            }
        }
    }

    private fun invokeSlotLongClick(card: ViewGroup, state: ConnectivityCardState, kind: SlotKind): Boolean {
        val entry = state.bindings[kind] ?: return false
        return runCatching {
            val long1 = entry.tile.javaClass.methods.firstOrNull { it.name == "longClick" && it.parameterCount == 1 }
            val long0 = entry.tile.javaClass.methods.firstOrNull { it.name == "longClick" && it.parameterCount == 0 }
            when {
                long1 != null -> { long1.invoke(entry.tile, null); true }
                long0 != null -> { long0.invoke(entry.tile); true }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun refreshAllSlots(card: ViewGroup, state: ConnectivityCardState) {
        SlotKind.entries.forEach { kind ->
            val entry = state.bindings[kind]
            val tileState = entry?.let { readTileState(it.tile) ?: it.lastState }
            renderSlot(card, state, kind, tileState)
        }
    }

    private fun renderSlot(card: ViewGroup, state: ConnectivityCardState, kind: SlotKind, tileState: Any?) {
        val entry = state.bindings[kind]
        val sysContext = resolveSystemUiContext(card, entry?.tile)
        val stateInt = (field(tileState, "state") as? Int) ?: 0
        val active = stateInt == 2
        val unavailable = stateInt == 0

        val circleColor = when {
            active -> kind.activeColor
            unavailable -> 0x1EFFFFFF
            else -> 0x33FFFFFF
        }
        val iconTint = when {
            active -> Color.WHITE
            unavailable -> 0x70FFFFFF
            else -> 0xEBFFFFFF.toInt()
        }

        // 1. Update collapsed 2x2 circle
        state.collapsedSlots[kind]?.let { views ->
            val bg = (views.circle.background as? GradientDrawable) ?: GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                views.circle.background = it
            }
            bg.setColor(circleColor)
            val iconObj = resolveTileIconObject(tileState)
            val stableMiShare = kind == SlotKind.MISHARE &&
                views.lastStateInt == stateInt &&
                views.lastTint == iconTint &&
                views.icon.drawable != null
            val sameIcon = (iconObj != null && iconObj == views.lastIconKey && views.lastStateInt == stateInt && views.lastTint == iconTint && views.icon.drawable != null)
            if (!stableMiShare && !sameIcon) {
                bindThemedTileIcon(views.icon, card, entry?.tile, iconObj, iconTint)
                views.lastIconKey = iconObj
                views.lastStateInt = stateInt
                views.lastTint = iconTint
            }
            views.circle.alpha = if (entry != null) 1f else 0.45f
        }

        // 2. Update expanded overlay card if currently open for this card
        val overlay = activeOverlay
        if (overlay != null && overlay.cardRef.get() === card) {
            overlay.expandedSlots[kind]?.let { views ->
                val bg = (views.circle.background as? GradientDrawable) ?: GradientDrawable().also {
                    it.shape = GradientDrawable.OVAL
                    views.circle.background = it
                }
                bg.setColor(circleColor)
                val iconObj = resolveTileIconObject(tileState)
                val stableMiShare = kind == SlotKind.MISHARE &&
                    views.lastStateInt == stateInt &&
                    views.lastTint == iconTint &&
                    views.icon.drawable != null
                val sameIcon = (iconObj != null && iconObj == views.lastIconKey && views.lastStateInt == stateInt && views.lastTint == iconTint && views.icon.drawable != null)
                if (!stableMiShare && !sameIcon) {
                    bindThemedTileIcon(views.icon, card, entry?.tile, iconObj, iconTint)
                    views.lastIconKey = iconObj
                    views.lastStateInt = stateInt
                    views.lastTint = iconTint
                }
                val (title, status) = resolveLocalizedTitleAndStatus(sysContext, card.context, kind, tileState, stateInt)
                views.title.text = title
                views.status.text = status
            }
        }
    }

    private fun resolveTileIconObject(tileState: Any?): Any? {
        if (tileState == null) return null
        val supplier = field(tileState, "iconSupplier")
        val suppliedIcon = supplier?.let { runCatching { call(it, "get") }.getOrNull() }
        return suppliedIcon ?: field(tileState, "icon")
    }

    private fun resolveSystemUiContext(card: ViewGroup, tile: Any?): Context {
        val candidates = candidateIconContexts(card, tile)
        return candidates.firstOrNull { it.packageName == SYSTEM_UI_PACKAGE }
            ?: candidates.firstOrNull()
            ?: card.context
    }

    private fun candidateIconContexts(card: ViewGroup, tile: Any?): List<Context> {
        val list = ArrayList<Context>(5)
        fun addUnique(ctx: Context?) {
            if (ctx != null && list.none { it === ctx }) list.add(ctx)
        }
        // 1. QSTileImpl.mContext (real com.android.systemui themed Context for SystemUI tiles)
        addUnique(tile?.let { field(it, "mContext") as? Context })
        // 2. Native QSCardItemIconView's Context (constructed with sysUIContext in HyperOS)
        addUnique((call(card, "getIcon") as? View)?.context)
        // 3. Process applicationContext (com.android.systemui in SystemUI process)
        addUnique(card.context.applicationContext)
        // 4. Captured QSController context + plugin card context (for plugin tiles like MiShareTile)
        addUnique(sysUiContextRef?.get())
        addUnique(card.context)
        return list
    }

    /**
     * Loads and binds the live icon Drawable directly from `QSTile.State` (`iconSupplier` -> `icon`)
     * using `com.android.systemui`'s themed Context (with plugin Context fallback), preserving
     * user-applied MIUI/HyperOS themes and matching `QSCardItemIconView`'s AVD final-frame rendering.
     */
    private fun bindThemedTileIcon(
        targetView: ImageView,
        card: ViewGroup,
        tile: Any?,
        iconObj: Any?,
        tintColor: Int,
    ) {
        if (iconObj == null) {
            targetView.setImageDrawable(null)
            return
        }

        val contexts = candidateIconContexts(card, tile)
        var rawDrawable: Drawable? = null
        var loadedContext: Context? = null
        for (ctx in contexts) {
            rawDrawable = runCatching { call(iconObj, "getInvisibleDrawable", ctx) as? Drawable }.getOrNull()
                ?: runCatching { call(iconObj, "getDrawable", ctx) as? Drawable }.getOrNull()
            if (rawDrawable != null) {
                loadedContext = ctx
                break
            }
        }
        if (rawDrawable == null) {
            targetView.setImageDrawable(null)
            return
        }

        val isAvd = rawDrawable is AnimatedVectorDrawable
        // Clone static drawables (e.g. MiShareTile's shared DrawableIcon) using the exact Context
        // that loaded them so tinting here NEVER mutates or flashes the lower small tile's icon.
        val drawable = if (!isAvd) {
            val cs = rawDrawable.constantState
            (if (cs != null && loadedContext != null) cs.newDrawable(loadedContext.resources) else rawDrawable).mutate()
        } else {
            rawDrawable.mutate()
        }
        drawable.setAutoMirrored(false)
        drawable.setTint(tintColor)

        // HyperOS SystemUI QS AnimatedVectorDrawables use a 71.5dp canvas around a ~34dp glyph,
        // while static plugin icons (e.g. MiShareTile) fill their 34dp viewport tightly.
        val visualScale = if (isAvd) 1.95f else 1f
        targetView.scaleX = visualScale
        targetView.scaleY = visualScale
        targetView.setImageDrawable(drawable)

        if (isAvd) {
            val avd = drawable as AnimatedVectorDrawable
            runCatching {
                val utils = card.javaClass.classLoader?.loadClass("miui.systemui.util.AnimatedVectorDrawableUtils")
                utils?.getMethod("forceAnimationOnUI", AnimatedVectorDrawable::class.java)?.invoke(null, avd)
            }
            runCatching {
                if (!avd.isRunning) avd.start()
                avd.stop()
            }
        }
    }

    /**
     * Resolves localized title & subtitle directly from the real `QSTile.State` and SystemUI/Plugin
     * string resources (`accessibility_sliding_button_state_description_on/off`).
     */
    private fun resolveLocalizedTitleAndStatus(
        sysContext: Context,
        pluginContext: Context,
        kind: SlotKind,
        tileState: Any?,
        stateInt: Int,
    ): Pair<String, String> {
        val locale = sysContext.resources.configuration.locales[0] ?: Locale.getDefault()
        val rawLabel = (field(tileState, "label") as? CharSequence)?.toString()?.trim().orEmpty()
        val rawSecondary = (field(tileState, "secondaryLabel") as? CharSequence)?.toString()?.trim().orEmpty()

        val defaultTitle = lookupSystemString(sysContext, pluginContext, kind.fallbackResNames)
            ?: localizedFallback(locale, kind.fallbackZh, kind.fallbackEn, kind.fallbackJa)

        val onText = lookupSystemString(
            pluginContext,
            sysContext,
            listOf("accessibility_sliding_button_state_description_on", "abc_capital_on"),
        ) ?: localizedFallback(locale, "已开启", "On", "オン")

        val offText = lookupSystemString(
            pluginContext,
            sysContext,
            listOf("accessibility_sliding_button_state_description_off", "abc_capital_off"),
        ) ?: localizedFallback(locale, "已关闭", "Off", "オフ")

        val active = stateInt == 2
        val baseStatus = if (active) onText else offText

        // HyperOS WifiTile / BluetoothTile / CellularTile sometimes replace `state.label` with the
        // connected SSID / device / carrier name when active while leaving `secondaryLabel` blank.
        val replacesLabelWithDetail = kind == SlotKind.WIFI || kind == SlotKind.BLUETOOTH || kind == SlotKind.CELL
        if (active && replacesLabelWithDetail && rawSecondary.isBlank() && rawLabel.isNotBlank() && !rawLabel.equals(defaultTitle, ignoreCase = true)) {
            return defaultTitle to rawLabel
        }

        val title = rawLabel.ifBlank { defaultTitle }
        val status = when {
            rawSecondary.isNotBlank() -> rawSecondary
            else -> baseStatus
        }
        return title to status
    }

    private fun lookupSystemString(primary: Context, secondary: Context, names: List<String>): String? {
        for (ctx in arrayOf(primary, secondary)) {
            val res = ctx.resources
            val pkg = ctx.packageName
            for (name in names) {
                val id = res.getIdentifier(name, "string", pkg)
                if (id != 0) {
                    val value = runCatching { res.getString(id).trim() }.getOrNull()
                    if (!value.isNullOrEmpty()) return value
                }
            }
        }
        return null
    }

    private fun localizedFallback(locale: Locale, zh: String, en: String, ja: String): String =
        when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> ja
            "en" -> en
            else -> zh
        }

    // --- Expanded Connectivity Overlay (mirrors Focus Mode / Brightness-Volume Secondary Panel) ---

    private fun resolveOverlayHostAndPanelTarget(card: ViewGroup): Pair<ViewGroup, View?>? {
        val rootView = card.rootView as? ViewGroup ?: return null
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
        var childOfRoot: View? = card
        while (childOfRoot != null && childOfRoot.parent !== rootView) {
            childOfRoot = childOfRoot.parent as? View
        }
        return rootView to childOfRoot
    }

    private fun showExpandedOverlay(card: ViewGroup, cardState: ConnectivityCardState) {
        dismissExpandedOverlay(immediate = true)
        ensureTileBindings(card, cardState)
        setTileBindingsListening(cardState, true)

        val (host, hiddenPanelTarget) = resolveOverlayHostAndPanelTarget(card) ?: return
        val context = card.context
        val pluginLoader = card.javaClass.classLoader
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()

        val cardLoc = IntArray(2)
        val hostLoc = IntArray(2)
        card.getLocationOnScreen(cardLoc)
        host.getLocationOnScreen(hostLoc)
        val hostCenterX = hostLoc[0] + host.width / 2f
        val hostCenterY = hostLoc[1] + host.height / 2f
        val cardCenterX = cardLoc[0] + card.width / 2f
        val cardCenterY = cardLoc[1] + card.height / 2f
        val initialDeltaX = ((cardCenterX - hostCenterX) * 0.20f).coerceIn(-84f * d, 84f * d)
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
            setOnClickListener { dismissExpandedOverlay(immediate = false) }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissExpandedOverlay(immediate = false)
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

        val totalWidth = minOf(dp(344), (host.width - dp(40)).coerceAtLeast(dp(250)))
        val rowGap = dp(12)
        val colGap = dp(12)
        val halfWidth = (totalWidth - colGap) / 2
        val squareHeight = minOf(dp(146), (halfWidth * 0.88f).roundToInt().coerceAtLeast(dp(124)))
        val pillHeight = dp(74)
        val useNativeMaterial = isNativeTileMaterialEnabled(pluginLoader, context)
        val expandedSlots = HashMap<SlotKind, ExpandedSlotViews>()

        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(24))
            clipChildren = false
            clipToPadding = false
        }

        // Row 1: Full-width Airplane Mode pill
        val airplanePill = buildExpandedPillCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.AIRPLANE,
            heightPx = pillHeight,
            expandedSlots = expandedSlots,
        )
        stack.addView(airplanePill, LinearLayout.LayoutParams(totalWidth, pillHeight))

        // Row 2: 2-column square cards — Wi-Fi + Xiaomi Share (replaces AirDrop)
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val wifiCard = buildExpandedSquareCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.WIFI,
            cardWidthPx = halfWidth,
            expandedSlots = expandedSlots,
        )
        val mishareCard = buildExpandedSquareCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.MISHARE,
            cardWidthPx = halfWidth,
            expandedSlots = expandedSlots,
        )
        row2.addView(wifiCard, LinearLayout.LayoutParams(halfWidth, squareHeight))
        row2.addView(mishareCard, LinearLayout.LayoutParams(halfWidth, squareHeight).apply { marginStart = colGap })
        stack.addView(row2, LinearLayout.LayoutParams(totalWidth, squareHeight).apply { topMargin = rowGap })

        // Row 3: 2-column square cards — Cellular Data + Bluetooth
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val cellCard = buildExpandedSquareCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.CELL,
            cardWidthPx = halfWidth,
            expandedSlots = expandedSlots,
        )
        val btCard = buildExpandedSquareCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.BLUETOOTH,
            cardWidthPx = halfWidth,
            expandedSlots = expandedSlots,
        )
        row3.addView(cellCard, LinearLayout.LayoutParams(halfWidth, squareHeight))
        row3.addView(btCard, LinearLayout.LayoutParams(halfWidth, squareHeight).apply { marginStart = colGap })
        stack.addView(row3, LinearLayout.LayoutParams(totalWidth, squareHeight).apply { topMargin = rowGap })

        // Row 4: Full-width Personal Hotspot pill
        val hotspotPill = buildExpandedPillCard(
            card = card,
            cardState = cardState,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            kind = SlotKind.HOTSPOT,
            heightPx = pillHeight,
            expandedSlots = expandedSlots,
        )
        stack.addView(hotspotPill, LinearLayout.LayoutParams(totalWidth, pillHeight).apply { topMargin = rowGap })

        // Row 5 (Optional): Full-width pill for the 4th mini-slot (VPN / NFC / Location) if available
        if (cardState.bindings.containsKey(SlotKind.EXTRA)) {
            val extraPill = buildExpandedPillCard(
                card = card,
                cardState = cardState,
                pluginLoader = pluginLoader,
                useNativeMaterial = useNativeMaterial,
                kind = SlotKind.EXTRA,
                heightPx = pillHeight,
                expandedSlots = expandedSlots,
            )
            stack.addView(extraPill, LinearLayout.LayoutParams(totalWidth, pillHeight).apply { topMargin = rowGap })
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
        updateBlurEnhanceClip(pluginLoader, row2, false)
        updateBlurEnhanceClip(pluginLoader, row3, false)

        host.addView(
            overlayRoot,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        overlayRoot.requestFocus()

        val overlayRecord = ActiveConnectivityOverlay(
            cardRef = WeakReference(card),
            host = host,
            overlayRoot = overlayRoot,
            stack = stack,
            hiddenPanelTarget = hiddenPanelTarget,
            collapseDeltaX = initialDeltaX,
            collapseDeltaY = initialDeltaY,
            expandedSlots = expandedSlots,
        )
        activeOverlay = overlayRecord
        refreshAllSlots(card, cardState)

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
        stack.translationX = initialDeltaX
        stack.translationY = initialDeltaY
        overlayRoot.animate()
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        stack.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun buildExpandedPillCard(
        card: ViewGroup,
        cardState: ConnectivityCardState,
        pluginLoader: ClassLoader?,
        useNativeMaterial: Boolean,
        kind: SlotKind,
        heightPx: Int,
        expandedSlots: HashMap<SlotKind, ExpandedSlotViews>,
    ): FrameLayout {
        val context = card.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val cornerRadiusPx = heightPx / 2f

        val pill = createExpandedGlassContainer(
            context = context,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            cornerRadiusPx = cornerRadiusPx,
            onClick = {
                invokeSlotClick(card, cardState, kind)
            },
            onLongClick = {
                if (invokeSlotLongClick(card, cardState, kind)) {
                    dismissExpandedOverlay(immediate = false)
                }
            },
        )

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(20), 0)
        }

        val circleSize = dp(46)
        val circle = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = OVAL_OUTLINE_PROVIDER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xF5FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(0xB8FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(title)
        textCol.addView(status)

        contentRow.addView(circle, LinearLayout.LayoutParams(circleSize, circleSize))
        contentRow.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            },
        )

        pill.addView(contentRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        expandedSlots[kind] = ExpandedSlotViews(circle, icon, title, status)
        return pill
    }

    private fun buildExpandedSquareCard(
        card: ViewGroup,
        cardState: ConnectivityCardState,
        pluginLoader: ClassLoader?,
        useNativeMaterial: Boolean,
        kind: SlotKind,
        cardWidthPx: Int,
        expandedSlots: HashMap<SlotKind, ExpandedSlotViews>,
    ): FrameLayout {
        val context = card.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val cornerRadiusPx = 28f * d

        val square = createExpandedGlassContainer(
            context = context,
            pluginLoader = pluginLoader,
            useNativeMaterial = useNativeMaterial,
            cornerRadiusPx = cornerRadiusPx,
            onClick = {
                invokeSlotClick(card, cardState, kind)
            },
            onLongClick = {
                if (invokeSlotLongClick(card, cardState, kind)) {
                    dismissExpandedOverlay(immediate = false)
                }
            },
        )

        val circleSize = dp(48)
        val circle = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = OVAL_OUTLINE_PROVIDER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        square.addView(
            circle,
            FrameLayout.LayoutParams(circleSize, circleSize, Gravity.START or Gravity.TOP).apply {
                marginStart = dp(16)
                topMargin = dp(16)
            },
        )

        val bottomCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xF5FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        val status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(0xB8FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            maxWidth = (cardWidthPx - dp(if (kind.showChevronInExpanded) 46 else 32)).coerceAtLeast(dp(48))
        }
        statusRow.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 0f
        })
        if (kind.showChevronInExpanded) {
            val chevron = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(CcFocusIcons.arrowUpDownDrawable(0x9EFFFFFF.toInt()))
            }
            statusRow.addView(
                chevron,
                LinearLayout.LayoutParams(dp(8), dp(12)).apply {
                    marginStart = dp(4)
                },
            )
        }

        bottomCol.addView(title)
        bottomCol.addView(statusRow)
        square.addView(
            bottomCol,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM,
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(14)
                bottomMargin = dp(16)
            },
        )

        expandedSlots[kind] = ExpandedSlotViews(circle, icon, title, status)
        return square
    }

    private fun createExpandedGlassContainer(
        context: Context,
        pluginLoader: ClassLoader?,
        useNativeMaterial: Boolean,
        cornerRadiusPx: Float,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ): FrameLayout {
        val d = context.resources.displayMetrics.density
        val container = FrameLayout(context).apply {
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
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(85L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(125L).start()
                }
                false
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick()
                true
            }
        }

        val nativeApplied = useNativeMaterial && applyNativeTileMaterial(pluginLoader, container)
        if (!nativeApplied) {
            container.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
                setColor(0x36FFFFFF)
                setStroke((1f * d).roundToInt().coerceAtLeast(1), 0x42FFFFFF)
            }
        }
        return container
    }

    private fun isNativeTileMaterialEnabled(pluginLoader: ClassLoader?, context: Context): Boolean {
        val loader = pluginLoader ?: return false
        return runCatching {
            val utilsClass = loader.loadClass("miui.systemui.controlcenter.utils.ControlCenterUtils")
            val m = utilsClass.getMethod("getBackgroundMaterialOpenedWithoutSuperPower", Context::class.java)
            (m.invoke(null, context) as? Boolean) == true
        }.getOrDefault(false)
    }

    private fun applyNativeTileMaterial(pluginLoader: ClassLoader?, view: View): Boolean {
        val loader = pluginLoader ?: return false
        return runCatching {
            val blurCompatClass = loader.loadClass("miui.systemui.util.MiBlurCompat")
            val blendTokenClass = loader.loadClass("miui.systemui.util.MiuiColorBlendToken")
            val bgStyleClass = loader.loadClass("miui.systemui.util.MiBackgroundStyle")

            val blendInstance = blendTokenClass.getField("INSTANCE").get(null)
            val bgStyleInstance = bgStyleClass.getField("INSTANCE").get(null)

            val colorToken = blendTokenClass.getMethod("getCC_TILE_DEFAULT_BLEND_COLORS").invoke(blendInstance) ?: return false
            val glassToken = bgStyleClass.getMethod("getDEFAULT_GLASS_TOKEN").invoke(bgStyleInstance) ?: return false

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

    private fun dismissExpandedOverlay(immediate: Boolean) {
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
            .translationX(current.collapseDeltaX)
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

    private fun call(target: Any?, name: String, vararg args: Any?): Any? {
        target ?: return null
        val m = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return null
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        const val QS_CARD_ITEM_VIEW = "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
        const val QS_CONTROLLER = "miui.systemui.controlcenter.qs.QSController"
        const val QS_CARDS_CONTROLLER = "miui.systemui.controlcenter.panel.main.qs.QSCardsController"
        val TARGET_CLASSES = setOf(QS_CARD_ITEM_VIEW, QS_CONTROLLER, QS_CARDS_CONTROLLER)
        val OVAL_OUTLINE_PROVIDER = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }
}
