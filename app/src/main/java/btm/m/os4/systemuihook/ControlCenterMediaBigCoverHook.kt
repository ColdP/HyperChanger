// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Re-lays out the Control Center expanded MiPlay media secondary panel (MediaPanelController /
 * QSControlMiPlayDetailContent / QSControlMiPlayDetailHeader in MiuiSystemUIPlugin 18.3.2.22.0)
 * into an iOS-style vertical big-cover card with a bottom audio-output switcher pill, and adapts
 * MediaPanelAnimator for seamless element-by-element morph animations from any media tile layout.
 */
internal class ControlCenterMediaBigCoverHook(private val module: XposedModule) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val panelStates = WeakHashMap<View, PanelState>()
    private val headerToContent = WeakHashMap<View, WeakReference<View>>()
    private val activeAnimators = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val colorWorker by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "HyperChanger-MediaCoverColor").apply { isDaemon = true }
        }
    }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var enabled = false
    @Volatile private var immersiveCover = false
    @Volatile private var immersiveTextShadow = true
    @Volatile private var immersiveTextColorMode = CC_MEDIA_TEXT_COLOR_OFF
    @Volatile private var tileLayoutEnabled = false
    @Volatile private var tileLayoutHasProgress = false

    private data class ConstraintSnapshot(
        val width: Int,
        val height: Int,
        val marginStart: Int,
        val marginEnd: Int,
        val topMargin: Int,
        val bottomMargin: Int,
        val startToStart: Int,
        val startToEnd: Int,
        val endToEnd: Int,
        val endToStart: Int,
        val topToTop: Int,
        val topToBottom: Int,
        val bottomToBottom: Int,
        val bottomToTop: Int,
    )

    private data class StockSnapshot(
        val contentWidth: Int,
        val headerWidth: Int,
        val metaInfoWidth: Int,
        val headerPaddingStart: Int,
        val headerPaddingTop: Int,
        val headerPaddingEnd: Int,
        val headerPaddingBottom: Int,
        val coverParent: ConstraintSnapshot?,
        val coverParentOutlineProvider: ViewOutlineProvider?,
        val coverWidth: Int,
        val coverHeight: Int,
        val appIconContainer: ConstraintSnapshot?,
        val appIconContainerVisibility: Int,
        val title: ConstraintSnapshot?,
        val titleGravity: Int,
        val titleTextColors: ColorStateList?,
        val titleNoPlay: ConstraintSnapshot?,
        val titleNoPlayGravity: Int,
        val titleNoPlayTextColors: ColorStateList?,
        val subtitle: ConstraintSnapshot?,
        val subtitleGravity: Int,
        val subtitleTextColors: ColorStateList?,
        val audioContainer: ConstraintSnapshot?,
        val videoContainer: ConstraintSnapshot?,
        val progressContainer: ConstraintSnapshot?,
        val volumeWidth: Int,
        val volumeHeight: Int,
        val volumeTopMargin: Int,
        val volumeMarginStart: Int,
        val volumeMarginEnd: Int,
        val listVisibility: Int,
    )

    private data class TransitionSnapshot(
        val fromTitleVisible: Boolean,
        val fromArtistVisible: Boolean,
        val fromNoPlayVisible: Boolean,
        val fromTitleFontPx: Float,
        val fromArtistFontPx: Float,
        val fromNoPlayFontPx: Float,
        val fromTitleGravity: Int?,
        val fromArtistGravity: Int?,
        val fromNoPlayGravity: Int?,
        val fromAudioVisible: Boolean,
        val fromPrevVisible: Boolean,
        val fromPlayVisible: Boolean,
        val fromNextVisible: Boolean,
        val fromDeviceVisible: Boolean,
        val fromDeviceRect: Rect?,
        val fromHasCustomProgress: Boolean,
        val pillTarget: Rect?,
    )

    private class PanelState(
        val stock: StockSnapshot,
    ) {
        var containerRef: WeakReference<View>? = null
        var showingDevices: Boolean = false
        var bigCoverApplied: Boolean = false
        var immersiveApplied: Boolean = false
        var immersiveCardRadius: Float = 0f
        var immersiveTopRadius: Float = 0f
        var immersiveBottomExtend: Float = 0f
        var immersiveOutlineProvider: ViewOutlineProvider? = null
        var coverFadeShader: RuntimeShader? = null
        var coverLayoutListener: View.OnLayoutChangeListener? = null
        var lastCoverRef: WeakReference<Bitmap>? = null
        var colorRequestToken: Int = 0
        var appliedColorKey: String? = null
        var appliedColors: MediaCoverTextColors? = null
        var textColorAnimator: ValueAnimator? = null
        var textBarrier: View? = null
        var outputPill: LinearLayout? = null
        var outputPillIcon: ImageView? = null
        var outputPillLabel: TextView? = null
        var transition: TransitionSnapshot? = null
    }

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        syncPreferenceState(preferences)
        targetClasses.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let {
                onClassLoaded(it, preferences)
            }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in targetClasses || !scheduled.add(clazz)) return
        syncPreferenceState(preferences)
        // Never enumerate target methods inside a ClassLoader.loadClass callback (ART crash audit).
        mainHandler.post {
            installSingleClass(clazz, preferences)
            val loader = clazz.classLoader ?: return@post
            targetClasses.forEach { name ->
                if (name != clazz.name) {
                    runCatching { loader.loadClass(name) }.getOrNull()?.let { sibling ->
                        if (scheduled.add(sibling)) installSingleClass(sibling, preferences)
                    }
                }
            }
        }
    }

    private fun syncPreferenceState(preferences: SharedPreferences) {
        val current = readControlCenterMediaBigCoverSettings(preferences)
        enabled = current.enabled
        immersiveCover = current.immersiveCover
        immersiveTextShadow = current.immersiveTextShadow
        immersiveTextColorMode = current.immersiveTextColorMode
        tileLayoutEnabled = readCcTileLayoutSettings(preferences).enabled
        tileLayoutHasProgress = readTileLayoutHasProgress(preferences)
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == null ||
                key == KEY_CC_MEDIA_BIG_COVER_EXPAND ||
                key == KEY_CC_MEDIA_BIG_COVER_IMMERSIVE ||
                key == KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW ||
                key == KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE ||
                key == KEY_CC_TILE_LAYOUT_ENABLED ||
                key == KEY_CC_TILE_LAYOUT
            ) {
                val updated = readControlCenterMediaBigCoverSettings(preferences)
                enabled = updated.enabled
                immersiveCover = updated.immersiveCover
                immersiveTextShadow = updated.immersiveTextShadow
                immersiveTextColorMode = updated.immersiveTextColorMode
                tileLayoutEnabled = readCcTileLayoutSettings(preferences).enabled
                tileLayoutHasProgress = readTileLayoutHasProgress(preferences)
                mainHandler.post { refreshTrackedPanels() }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readTileLayoutHasProgress(preferences: SharedPreferences): Boolean {
        val tileSettings = readCcTileLayoutSettings(preferences)
        if (!tileSettings.enabled) return false
        val media = tileSettings.tiles.firstOrNull { it.id == "media" } ?: return false
        return media.w >= 2 && media.h >= 2 && (media.w >= 3 || media.h >= 3)
    }

    internal fun bindExternalDetailContent(content: View, container: View) {
        val header = find(content, "qs_control_detail_miplay_header") as? ViewGroup ?: return
        val state = ensureState(content, header)
        state.containerRef = WeakReference(container)
        state.showingDevices = false
        if (enabled && shouldUseBigCover(content)) {
            applyBigCoverLayout(content, header, state)
            updateOutputPillAppearance(content, state)
        } else {
            restoreStockLayout(content, header, state)
        }
    }

    private fun refreshTrackedPanels() {
        clearAnimatorSnapshots()
        panelStates.entries.toList().forEach { (content, state) ->
            val header = find(content, "qs_control_detail_miplay_header") as? ViewGroup ?: return@forEach
            if (enabled && shouldUseBigCover(content)) {
                if (!state.showingDevices) {
                    applyBigCoverLayout(content, header, state)
                } else {
                    applyDevicePickerLayout(content, header, state)
                }
            } else {
                state.showingDevices = false
                restoreStockLayout(content, header, state)
            }
        }
    }

    private fun installSingleClass(clazz: Class<*>, preferences: SharedPreferences) {
        if (!installed.add(clazz)) return
        runCatching {
            when (clazz.name) {
                MEDIA_PANEL_CONTROLLER -> hookMediaPanelController(clazz)
                MEDIA_PANEL_DELEGATE -> hookMediaPanelDelegate(clazz)
                MEDIA_PANEL_ANIMATOR -> hookMediaPanelAnimator(clazz)
                DETAIL_CONTENT -> hookDetailContent(clazz)
                DETAIL_CONTENT_LAND -> hookDetailContentLand(clazz)
                DETAIL_HEADER -> hookDetailHeader(clazz)
            }
        }.onFailure { error ->
            installed.remove(clazz)
            HookModuleLogger.log(
                module,
                Log.WARN,
                "HyperChanger",
                "Could not hook control-center media big-cover target: ${clazz.name}",
                error,
            )
        }
    }

    private fun hookMediaPanelController(clazz: Class<*>) {
        val updateSize = method(clazz, "updateSize", 0) ?: return
        module.hook(updateSize)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("cc-media-big-cover:controller-size")
            .intercept { chain ->
                val result = chain.proceed()
                val controller = chain.thisObject ?: return@intercept result
                if (!enabled) return@intercept result
                val context = call(controller, "getContext") as? Context ?: return@intercept result
                if (!isVerticalEligible(context)) return@intercept result
                val frame = call(controller, "getItemFrame") as? View ?: return@intercept result
                val currentMax = (call(frame, "getMaxHeight") as? Int) ?: 0
                val raised = computeBigCoverMaxHeight(context, currentMax)
                if (raised > currentMax) call(frame, "setMaxHeight", raised)
                result
            }
    }

    private fun hookMediaPanelDelegate(clazz: Class<*>) {
        val prepareShow = method(clazz, "prepareShow", 1)
        if (prepareShow != null) {
            module.hook(prepareShow)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:delegate-prepare")
                .intercept { chain ->
                    val result = chain.proceed()
                    val delegate = chain.thisObject ?: return@intercept result
                    val content = field(delegate, "contentView") as? View ?: return@intercept result
                    val header = field(delegate, "headerLayout") as? ViewGroup ?: return@intercept result
                    val binding = field(delegate, "binding")
                    val container = field(binding, "mediaContainer") as? View
                    val state = ensureState(content, header)
                    if (container != null) state.containerRef = WeakReference(container)
                    state.showingDevices = false
                    if (enabled && shouldUseBigCover(content)) {
                        applyBigCoverLayout(content, header, state)
                    } else {
                        restoreStockLayout(content, header, state)
                    }
                    clearAnimatorSnapshots()
                    result
                }
        }
        val onHidden = method(clazz, "onHidden", 0)
        if (onHidden != null) {
            module.hook(onHidden)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:delegate-hidden")
                .intercept { chain ->
                    val result = chain.proceed()
                    val delegate = chain.thisObject ?: return@intercept result
                    val content = field(delegate, "contentView") as? View ?: return@intercept result
                    panelStates[content]?.let { state ->
                        state.showingDevices = false
                        state.transition = null
                    }
                    result
                }
        }
        val onShown = method(clazz, "onShown", 0)
        if (onShown != null) {
            module.hook(onShown)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:delegate-shown")
                .intercept { chain ->
                    val result = chain.proceed()
                    val delegate = chain.thisObject ?: return@intercept result
                    val content = field(delegate, "contentView") as? View ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    if (enabled && shouldUseBigCover(content)) {
                        updateOutputPillAppearance(content, state)
                        val header = field(delegate, "headerLayout") as? ViewGroup
                        if (header != null && state.immersiveApplied && !state.showingDevices) {
                            syncImmersiveTextColor(
                                content = content,
                                header = header,
                                state = state,
                                bitmap = resolveCurrentCoverBitmap(content, header, state),
                                animate = false,
                            )
                        }
                    }
                    result
                }
        }
    }

    private fun hookDetailContent(clazz: Class<*>) {
        val resetMaxHeight = method(clazz, "resetMaxHeight", 0)
        if (resetMaxHeight != null) {
            module.hook(resetMaxHeight)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:content-max-height")
                .intercept { chain ->
                    val original = chain.proceed() as Int
                    val content = chain.thisObject as? View ?: return@intercept original
                    if (!enabled || !isVerticalEligible(content.context)) return@intercept original
                    computeBigCoverMaxHeight(content.context, original)
                }
        }
        val updateHeight = method(clazz, "updateHeight", 3)
        if (updateHeight != null) {
            module.hook(updateHeight)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:content-update-height")
                .intercept { chain ->
                    val content = chain.thisObject as? View
                    val state = content?.let { panelStates[it] }
                    if (content != null && state != null && state.bigCoverApplied && !state.showingDevices && enabled && shouldUseBigCover(content)) {
                        find(content, "list")?.visibility = View.GONE
                        updateOutputPillAppearance(content, state)
                        return@intercept null
                    }
                    val result = chain.proceed()
                    if (content != null && state != null && enabled && shouldUseBigCover(content)) {
                        updateOutputPillAppearance(content, state)
                    }
                    result
                }
        }
    }

    private fun hookDetailContentLand(clazz: Class<*>) {
        val resetByOrientation = method(clazz, "resetByOrientation", 1) ?: return
        module.hook(resetByOrientation)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("cc-media-big-cover:content-land-reset")
            .intercept { chain ->
                val result = chain.proceed()
                val content = chain.thisObject as? View ?: return@intercept result
                val state = panelStates[content] ?: return@intercept result
                val header = find(content, "qs_control_detail_miplay_header") as? ViewGroup ?: return@intercept result
                if (enabled && shouldUseBigCover(content)) {
                    if (!state.showingDevices) {
                        applyBigCoverLayout(content, header, state)
                    } else {
                        applyDevicePickerLayout(content, header, state)
                    }
                } else {
                    state.showingDevices = false
                    restoreStockLayout(content, header, state)
                }
                result
            }
    }

    private fun hookDetailHeader(clazz: Class<*>) {
        listOf(
            method(clazz, "updateSize", 0),
            method(clazz, "onOrientationChanged", 1),
            method(clazz, "prepareShowPanel", 0),
        ).filterNotNull().forEach { target ->
            module.hook(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:header-${target.name}")
                .intercept { chain ->
                    val result = chain.proceed()
                    val header = chain.thisObject as? ViewGroup ?: return@intercept result
                    val content = headerToContent[header]?.get() ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    if (enabled && shouldUseBigCover(content)) {
                        if (!state.showingDevices) {
                            applyBigCoverLayout(content, header, state)
                        } else {
                            applyDevicePickerLayout(content, header, state)
                        }
                    } else if (state.bigCoverApplied || state.outputPill?.visibility == View.VISIBLE) {
                        state.showingDevices = false
                        restoreStockLayout(content, header, state)
                    }
                    result
                }
        }

        val setCover = method(clazz, "setCover", 3)
        if (setCover != null) {
            module.hook(setCover)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:header-set-cover")
                .intercept { chain ->
                    val result = chain.proceed()
                    val header = chain.thisObject as? ViewGroup ?: return@intercept result
                    val bitmap = chain.getArg(0) as? Bitmap
                    val content = headerToContent[header]?.get() ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    state.lastCoverRef = bitmap?.let { WeakReference(it) }
                    if (enabled && shouldUseBigCover(content) && state.immersiveApplied && !state.showingDevices) {
                        syncImmersiveTextColor(content, header, state, bitmap, animate = true)
                    }
                    result
                }
        }

        val updateTextAppearance = method(clazz, "updateTextAppearance", 0)
        if (updateTextAppearance != null) {
            module.hook(updateTextAppearance)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:header-text-appearance")
                .intercept { chain ->
                    val result = chain.proceed()
                    val header = chain.thisObject as? ViewGroup ?: return@intercept result
                    val content = headerToContent[header]?.get() ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    if (enabled && shouldUseBigCover(content) && state.immersiveApplied && !state.showingDevices) {
                        syncImmersiveTextColor(
                            content = content,
                            header = header,
                            state = state,
                            bitmap = resolveCurrentCoverBitmap(content, header, state),
                            animate = false,
                        )
                    }
                    result
                }
        }
    }

    private fun hookMediaPanelAnimator(clazz: Class<*>) {
        val calc = method(clazz, "calculateViewValues", 0)
        if (calc != null) {
            module.hook(calc)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:animator-calc")
                .intercept { chain ->
                    val animator = chain.thisObject
                    if (animator != null) {
                        activeAnimators.add(animator)
                    }
                    val result = chain.proceed()
                    if (animator != null) {
                        val delegate = field(animator, "contentController")
                        val content = field(delegate, "contentView") as? View
                        val state = content?.let { panelStates[it] }
                        val fromView = field(animator, "fromView")
                        if (state != null) {
                            state.transition = captureTransitionSnapshot(fromView, state)
                        }
                    }
                    result
                }
        }

        val frame = method(clazz, "frameCallback", 0)
        if (frame != null) {
            runCatching { module.deoptimize(frame) }
            module.hook(frame)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:animator-frame")
                .intercept { chain ->
                    val result = chain.proceed()
                    val animator = chain.thisObject ?: return@intercept result
                    val delegate = field(animator, "contentController") ?: return@intercept result
                    val content = field(delegate, "contentView") as? View ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    if (!enabled || !shouldUseBigCover(content)) return@intercept result
                    val animValue = field(animator, "animValue") ?: return@intercept result
                    val size = (field(animator, "size") as? Float) ?: return@intercept result
                    val alphaProgress = (field(animator, "alphaProgress") as? Float) ?: 1f
                    val alphaDeviceIcon = (field(animator, "alphaDeviceIcon") as? Float) ?: (1f - size.coerceIn(0f, 1f))
                    val isCollapsing = (call(animator, "isCollapsing") as? Boolean) == true ||
                        (call(animator, "isCollapsed") as? Boolean) == true
                    if (state.transition == null) {
                        state.transition = captureTransitionSnapshot(field(animator, "fromView"), state)
                    }
                    adjustAnimatorFrame(delegate, state, animValue, size, alphaProgress, alphaDeviceIcon, isCollapsing)
                    result
                }
        }

        val complete = method(clazz, "onAnimComplete", 0)
        if (complete != null) {
            module.hook(complete)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("cc-media-big-cover:animator-complete")
                .intercept { chain ->
                    val result = chain.proceed()
                    val animator = chain.thisObject ?: return@intercept result
                    val delegate = field(animator, "contentController") ?: return@intercept result
                    val content = field(delegate, "contentView") as? View ?: return@intercept result
                    val state = panelStates[content] ?: return@intercept result
                    val expanded = (call(animator, "isExpanded") as? Boolean) == true
                    if (enabled && shouldUseBigCover(content)) {
                        resetAnimatedViewStates(delegate, state, expanded)
                        if (!expanded) {
                            state.transition = null
                        }
                    }
                    result
                }
        }
    }

    private fun captureTransitionSnapshot(fromView: Any?, state: PanelState): TransitionSnapshot {
        val titleView = call(fromView, "getTitleView") as? TextView
        val artistView = call(fromView, "getArtistView") as? TextView
        val noPlayView = call(fromView, "getNoPlayView") as? TextView
        val audioView = call(fromView, "getAudioContainerView") as? View
        val prevView = call(fromView, "getAudioPrevView") as? View
        val playView = call(fromView, "getAudioPlayView") as? View
        val nextView = call(fromView, "getAudioNextView") as? View
        val deviceView = call(fromView, "getDeviceIconView") as? View
        val pill = state.outputPill
        val pillRect = if (pill != null && pill.visibility == View.VISIBLE && pill.width > 0 && pill.height > 0) {
            Rect(pill.left, pill.top, pill.right, pill.bottom)
        } else {
            null
        }
        val deviceRect = if (deviceView != null && deviceView.visibility == View.VISIBLE && deviceView.width > 0 && deviceView.height > 0) {
            Rect(deviceView.left, deviceView.top, deviceView.right, deviceView.bottom)
        } else {
            null
        }
        return TransitionSnapshot(
            fromTitleVisible = titleView?.visibility == View.VISIBLE,
            fromArtistVisible = artistView?.visibility == View.VISIBLE,
            fromNoPlayVisible = noPlayView?.visibility == View.VISIBLE,
            fromTitleFontPx = titleView?.textSize ?: 0f,
            fromArtistFontPx = artistView?.textSize ?: 0f,
            fromNoPlayFontPx = noPlayView?.textSize ?: 0f,
            fromTitleGravity = titleView?.gravity,
            fromArtistGravity = artistView?.gravity,
            fromNoPlayGravity = noPlayView?.gravity,
            fromAudioVisible = audioView?.visibility == View.VISIBLE,
            fromPrevVisible = audioView?.visibility == View.VISIBLE && prevView?.visibility == View.VISIBLE,
            fromPlayVisible = audioView?.visibility == View.VISIBLE && playView?.visibility == View.VISIBLE,
            fromNextVisible = audioView?.visibility == View.VISIBLE && nextView?.visibility == View.VISIBLE,
            fromDeviceVisible = deviceRect != null,
            fromDeviceRect = deviceRect,
            fromHasCustomProgress = tileLayoutHasProgress,
            pillTarget = pillRect,
        )
    }

    private fun resetAnimatedViewStates(delegate: Any, state: PanelState, expanded: Boolean) {
        val toTitleSize = (call(delegate, "getToTitleFontSize") as? Float) ?: 0f
        val toSubSize = (call(delegate, "getToSubtitleFontSize") as? Float) ?: 0f
        val toNoPlaySize = (call(delegate, "getToNoPlayFontSize") as? Float) ?: 0f

        (call(delegate, "getTitleView") as? TextView)?.let { tv ->
            tv.alpha = 1f
            tv.scaleX = 1f
            tv.scaleY = 1f
            if (expanded && toTitleSize > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, toTitleSize)
        }
        (call(delegate, "getSubtitleView") as? TextView)?.let { tv ->
            tv.alpha = 1f
            tv.scaleX = 1f
            tv.scaleY = 1f
            if (expanded && toSubSize > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, toSubSize)
        }
        (call(delegate, "getNoPlayView") as? TextView)?.let { tv ->
            tv.alpha = 1f
            tv.scaleX = 1f
            tv.scaleY = 1f
            if (expanded && toNoPlaySize > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, toNoPlaySize)
        }
        listOf(
            "getAvContainerView",
            "getAvPrevView",
            "getAvPlayView",
            "getAvNextView",
            "getProgressContainerView",
            "getVolumeBarContainerView",
        ).forEach { getter ->
            (call(delegate, getter) as? View)?.let { v ->
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                if (!expanded && getter == "getProgressContainerView") {
                    v.requestLayout()
                }
            }
        }
        (call(delegate, "getDeviceIconView") as? View)?.let { device ->
            if (expanded) {
                device.alpha = 0f
                device.visibility = View.GONE
            }
        }
        (call(delegate, "getAppIconContainerView") as? View)?.let { appIcon ->
            appIcon.scaleX = 1f
            appIcon.scaleY = 1f
            appIcon.alpha = 1f
        }
        if (state.immersiveApplied) {
            val coverParent = call(delegate, "getCoverParentView") as? View
            val coverView = call(delegate, "getCoverView") as? View
            if (expanded) {
                state.immersiveTopRadius = state.immersiveCardRadius
                state.immersiveBottomExtend = state.immersiveCardRadius
                coverParent?.invalidateOutline()
                if (coverView != null) {
                    applyImmersiveCoverFade(
                        cover = coverView,
                        state = state,
                        heightPx = coverView.height.toFloat().coerceAtLeast(1f),
                        fadeStrength = 1f,
                    )
                }
            } else {
                coverView?.setRenderEffect(null)
            }
        }
        state.outputPill?.let { pill ->
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.alpha = 1f
        }
    }

    /**
     * Seamless element-by-element morph between ANY Control Center media tile layout (stock 2x2 or
     * custom 1x1 / 2x1 / 1x2 / 3x1 / 4x1 / 2x3 / 3x2 / 4x2 / 3x3 / 4x3) and our big-cover card.
     */
    private fun adjustAnimatorFrame(
        delegate: Any,
        state: PanelState,
        animValue: Any,
        size: Float,
        alphaProgress: Float,
        alphaDeviceIcon: Float,
        isCollapsing: Boolean,
    ) {
        val fromContent = call(animValue, "getFromContent") ?: return
        val toContent = call(animValue, "getToContent") ?: return
        val toMetaInfo = call(animValue, "getToMetaInfo") ?: return
        val fromCover = call(animValue, "getFromCover") ?: return
        val toCoverParent = call(animValue, "getToCoverParent") ?: return
        val toVolume = call(animValue, "getToVolumeContainer")
        val transition = state.transition

        val clampedSize = size.coerceIn(0f, 1f)
        val clampedAlpha = alphaProgress.coerceIn(0f, 1f)
        // Elements absent from the collapsed tile dissolve early during collapse so they never
        // collide with shrinking controls or protrude past the tile boundary.
        val secondaryAlpha = if (isCollapsing) {
            ((clampedSize - 0.35f) / 0.65f).coerceIn(0f, 1f) * clampedAlpha
        } else {
            clampedAlpha
        }

        val fromContentW = num(fromContent, "getWidth")
        val fromContentH = num(fromContent, "getHeight")
        val toContentW = num(toContent, "getWidth").coerceAtLeast(1f)
        val toContentH = num(toContent, "getHeight").coerceAtLeast(1f)
        val currentCardW = lerp(fromContentW, toContentW, size)
        val currentCardH = lerp(fromContentH, toContentH, size)
        val cardScale = (currentCardW / toContentW).coerceAtLeast(0.01f)

        val fromContentLocL = num(fromContent, "getLocLeft")
        val fromContentLocT = num(fromContent, "getLocTop")
        val metaLeft = (num(toMetaInfo, "getLocLeft") - num(toContent, "getLocLeft"))
            .takeIf { it != 0f || num(toMetaInfo, "getLeft") == 0f } ?: num(toMetaInfo, "getLeft")
        val metaTop = (num(toMetaInfo, "getLocTop") - num(toContent, "getLocTop"))
            .takeIf { it != 0f || num(toMetaInfo, "getTop") == 0f } ?: num(toMetaInfo, "getTop")

        // 1. Cover: exact window-aligned morph from collapsed cover to big rounded cover.
        var curCoverL = 0f
        var curCoverT = 0f
        var curCoverW = 0f
        var curCoverH = 0f
        val coverParent = call(delegate, "getCoverParentView") as? View
        val coverView = call(delegate, "getCoverView") as? View
        if (coverParent != null) {
            val fromCoverW = num(fromCover, "getWidth")
            val fromCoverH = num(fromCover, "getHeight")
            val fromCoverL = num(fromCover, "getLocLeft") - fromContentLocL
            val fromCoverT = num(fromCover, "getLocTop") - fromContentLocT
            val toCoverW = num(toCoverParent, "getWidth")
            val toCoverH = num(toCoverParent, "getHeight")
            val toCoverL = num(toCoverParent, "getLeft")
            val toCoverT = num(toCoverParent, "getTop")

            curCoverW = lerp(fromCoverW, toCoverW, size).coerceAtLeast(1f)
            curCoverH = lerp(fromCoverH, toCoverH, size).coerceAtLeast(1f)
            val rtl = coverParent.layoutDirection == View.LAYOUT_DIRECTION_RTL
            curCoverL = if (rtl) {
                val fromRight = fromContentW - fromCoverL - fromCoverW
                val toRight = toContentW - metaLeft - toCoverL - toCoverW
                val curRight = lerp(fromRight, toRight, size)
                currentCardW - metaLeft - curCoverW - curRight
            } else {
                lerp(fromCoverL - metaLeft, toCoverL, size)
            }
            curCoverT = lerp(fromCoverT - metaTop, toCoverT, size)
            coverParent.setLeftTopRightBottom(
                curCoverL.roundToInt(),
                curCoverT.roundToInt(),
                (curCoverL + curCoverW).roundToInt(),
                (curCoverT + curCoverH).roundToInt(),
            )
            coverView?.setLeftTopRightBottom(0, 0, curCoverW.roundToInt(), curCoverH.roundToInt())
            // Keep the native default cover opaque during empty-state transitions when the tile
            // displays our matching system default cover placeholder.
            if (coverView != null && tileLayoutEnabled) {
                coverView.alpha = 1f
            }
            if (state.immersiveApplied) {
                val fromCoverRadius = num(animValue, "getFromCoverRadius")
                state.immersiveTopRadius = lerp(fromCoverRadius, state.immersiveCardRadius, clampedSize)
                state.immersiveBottomExtend = state.immersiveCardRadius * clampedSize
                coverParent.invalidateOutline()
                if (coverView != null) {
                    applyImmersiveCoverFade(coverView, state, curCoverH, clampedSize)
                }
                val appIconContainer = call(delegate, "getAppIconContainerView") as? View
                val toAppIcon = call(animValue, "getToAppIconContainer")
                if (appIconContainer != null && appIconContainer.visibility == View.VISIBLE) {
                    val d = appIconContainer.resources.displayMetrics.density
                    val badgeW = (toAppIcon?.let { num(it, "getWidth") }?.takeIf { it > 0f }
                        ?: appIconContainer.width.toFloat().takeIf { it > 0f }
                        ?: (24f * d))
                    val badgeH = (toAppIcon?.let { num(it, "getHeight") }?.takeIf { it > 0f }
                        ?: appIconContainer.height.toFloat().takeIf { it > 0f }
                        ?: badgeW)
                    val margin = 16f * d
                    val badgeL = if (rtl) margin else (curCoverW - badgeW - margin).coerceAtLeast(0f)
                    val badgeT = margin.coerceAtMost((curCoverH - badgeH).coerceAtLeast(0f))
                    appIconContainer.pivotX = 0f
                    appIconContainer.pivotY = 0f
                    appIconContainer.scaleX = 1f
                    appIconContainer.scaleY = 1f
                    appIconContainer.alpha = secondaryAlpha
                    appIconContainer.setLeftTopRightBottom(
                        badgeL.roundToInt(),
                        badgeT.roundToInt(),
                        (badgeL + badgeW).roundToInt(),
                        (badgeT + badgeH).roundToInt(),
                    )
                    (call(delegate, "getAppIconView") as? View)?.setLeftTopRightBottom(
                        0,
                        0,
                        badgeW.roundToInt(),
                        badgeH.roundToInt(),
                    )
                }
            }
        }

        // 2. Device icon: fade seamlessly from its collapsed top-right position if it was visible.
        val deviceIcon = call(delegate, "getDeviceIconView") as? View
        if (deviceIcon != null && transition != null) {
            val fromRect = transition.fromDeviceRect
            if (transition.fromDeviceVisible && fromRect != null && !state.showingDevices) {
                val devAlpha = alphaDeviceIcon.coerceIn(0f, 1f)
                deviceIcon.visibility = if (devAlpha > 0.005f) View.VISIBLE else View.INVISIBLE
                deviceIcon.alpha = devAlpha
                val rtl = deviceIcon.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val devW = fromRect.width().toFloat()
                val devH = fromRect.height().toFloat()
                val devL = if (rtl) {
                    fromRect.left - metaLeft
                } else {
                    val rightInset = fromContentW - fromRect.right
                    currentCardW - metaLeft - rightInset - devW
                }
                val devT = fromRect.top - metaTop
                deviceIcon.setLeftTopRightBottom(
                    devL.roundToInt(),
                    devT.roundToInt(),
                    (devL + devW).roundToInt(),
                    (devT + devH).roundToInt(),
                )
            } else {
                deviceIcon.alpha = 0f
                deviceIcon.visibility = View.GONE
            }
        }

        if (!state.bigCoverApplied) {
            // In device-picker mode, only ensure volume bar height and output pill animate cleanly.
            animateVolumeAndPill(delegate, state, toVolume, currentCardW, currentCardH, toContentH, cardScale, secondaryAlpha)
            return
        }

        // 3. Title / Subtitle / Empty-State:
        //    - If visible in the collapsed tile (2x2, 3x1, 4x1, 2x3, 3x2, 4x2, ...), morph smoothly
        //      in position, bounds, and exact font size.
        //    - If hidden in the collapsed tile (1x1 cover-only, 2x1/1x2 transport-only), glide out
        //      from beneath the cover with smooth fade so there is zero pop-in.
        val titleView = call(delegate, "getTitleView") as? TextView
        val toTitle = call(animValue, "getToTitle")
        val fromTitle = call(animValue, "getFromTitle")
        if (titleView != null && toTitle != null && fromTitle != null) {
            val toFontPx = (call(delegate, "getToTitleFontSize") as? Float) ?: titleView.textSize
            val fromFontPx = transition?.fromTitleFontPx?.takeIf { it > 0f }
                ?: (call(delegate, "getFromTitleFontSize") as? Float) ?: toFontPx
            morphTextView(
                view = titleView,
                fromLoc = fromTitle,
                toLoc = toTitle,
                wasVisible = transition?.fromTitleVisible ?: (titleView.visibility == View.VISIBLE),
                fromFontPx = fromFontPx,
                toFontPx = toFontPx,
                fromGravity = transition?.fromTitleGravity,
                fromContentLocL = fromContentLocL,
                fromContentLocT = fromContentLocT,
                metaLeft = metaLeft,
                metaTop = metaTop,
                fallbackStartLeft = curCoverL,
                fallbackStartTop = curCoverT + curCoverH,
                size = size,
                clampedSize = clampedSize,
                cardScale = cardScale,
                clampedAlpha = secondaryAlpha,
            )
        }

        val subtitleView = call(delegate, "getSubtitleView") as? TextView
        val toSubtitle = call(animValue, "getToSubtitle")
        val fromArtist = call(animValue, "getFromArtist")
        if (subtitleView != null && toSubtitle != null && fromArtist != null) {
            val toFontPx = (call(delegate, "getToSubtitleFontSize") as? Float) ?: subtitleView.textSize
            val fromFontPx = transition?.fromArtistFontPx?.takeIf { it > 0f }
                ?: (call(delegate, "getFromSubtitleFontSize") as? Float) ?: toFontPx
            val fallbackTop = titleView?.bottom?.toFloat() ?: (curCoverT + curCoverH)
            morphTextView(
                view = subtitleView,
                fromLoc = fromArtist,
                toLoc = toSubtitle,
                wasVisible = transition?.fromArtistVisible ?: (subtitleView.visibility == View.VISIBLE),
                fromFontPx = fromFontPx,
                toFontPx = toFontPx,
                fromGravity = transition?.fromArtistGravity,
                fromContentLocL = fromContentLocL,
                fromContentLocT = fromContentLocT,
                metaLeft = metaLeft,
                metaTop = metaTop,
                fallbackStartLeft = curCoverL,
                fallbackStartTop = fallbackTop,
                size = size,
                clampedSize = clampedSize,
                cardScale = cardScale,
                clampedAlpha = secondaryAlpha,
            )
        }

        val noPlayView = call(delegate, "getNoPlayView") as? TextView
        val toNoPlay = call(animValue, "getToNoPlay")
        val fromNoPlay = call(animValue, "getFromNoPlay")
        if (noPlayView != null && toNoPlay != null && fromNoPlay != null) {
            val toFontPx = (call(delegate, "getToNoPlayFontSize") as? Float) ?: noPlayView.textSize
            val fromFontPx = transition?.fromNoPlayFontPx?.takeIf { it > 0f }
                ?: (call(delegate, "getFromNoPlayFontSize") as? Float) ?: toFontPx
            morphTextView(
                view = noPlayView,
                fromLoc = fromNoPlay,
                toLoc = toNoPlay,
                wasVisible = transition?.fromNoPlayVisible ?: (noPlayView.visibility == View.VISIBLE),
                fromFontPx = fromFontPx,
                toFontPx = toFontPx,
                fromGravity = transition?.fromNoPlayGravity,
                fromContentLocL = fromContentLocL,
                fromContentLocT = fromContentLocT,
                metaLeft = metaLeft,
                metaTop = metaTop,
                fallbackStartLeft = curCoverL,
                fallbackStartTop = curCoverT + curCoverH,
                size = size,
                clampedSize = clampedSize,
                cardScale = cardScale,
                clampedAlpha = secondaryAlpha,
            )
        }

        // 4. Transport controls (container_audio / container_video + prev / play / next):
        //    - Computed before fallback progress so progress stays cleanly slotted between text
        //      and transport controls during both expand and collapse.
        //    - Adapts 1x1 (fade in), 1x2 vertical buttons -> horizontal buttons, 3x1/4x1 partial
        //      buttons (visible buttons morph seamlessly, hidden buttons fade in), and all 2D sizes.
        val avContainer = call(delegate, "getAvContainerView") as? View
        val toAudio = call(animValue, "getToAudioContainer")
        val fromAudio = call(animValue, "getFromAudioContainer")
        var curAvTop = Float.NaN
        if (avContainer != null && toAudio != null && fromAudio != null) {
            val toL = num(toAudio, "getLeft")
            val toT = num(toAudio, "getTop")
            val toW = num(toAudio, "getWidth").coerceAtLeast(1f)
            val toH = num(toAudio, "getHeight").coerceAtLeast(1f)
            val audioWasVisible = transition?.fromAudioVisible ?: true

            if (audioWasVisible) {
                val fromL = (num(fromAudio, "getLocLeft") - fromContentLocL) - metaLeft
                val fromT = (num(fromAudio, "getLocTop") - fromContentLocT) - metaTop
                val fromW = num(fromAudio, "getWidth").coerceAtLeast(1f)
                val fromH = num(fromAudio, "getHeight").coerceAtLeast(1f)
                val curL = lerp(fromL, toL, size)
                val curT = lerp(fromT, toT, size)
                val curW = lerp(fromW, toW, size).coerceAtLeast(1f)
                val curH = lerp(fromH, toH, size).coerceAtLeast(1f)
                curAvTop = curT
                avContainer.scaleX = 1f
                avContainer.scaleY = 1f
                avContainer.alpha = 1f
                avContainer.setLeftTopRightBottom(
                    curL.roundToInt(),
                    curT.roundToInt(),
                    (curL + curW).roundToInt(),
                    (curT + curH).roundToInt(),
                )
                morphTransportButton(
                    button = call(delegate, "getAvPrevView") as? View,
                    fromVal = call(animValue, "getFromAudioPrev"),
                    toVal = call(animValue, "getToAudioPrev"),
                    wasVisible = transition?.fromPrevVisible ?: true,
                    size = size,
                    clampedAlpha = secondaryAlpha,
                )
                morphTransportButton(
                    button = call(delegate, "getAvPlayView") as? View,
                    fromVal = call(animValue, "getFromAudioPlay"),
                    toVal = call(animValue, "getToAudioPlay"),
                    wasVisible = transition?.fromPlayVisible ?: true,
                    size = size,
                    clampedAlpha = secondaryAlpha,
                )
                morphTransportButton(
                    button = call(delegate, "getAvNextView") as? View,
                    fromVal = call(animValue, "getFromAudioNext"),
                    toVal = call(animValue, "getToAudioNext"),
                    wasVisible = transition?.fromNextVisible ?: true,
                    size = size,
                    clampedAlpha = secondaryAlpha,
                )
            } else {
                // Collapsed tile was 1x1 cover-only: glide transport controls smoothly inside the card.
                val distFromBottom = (toContentH - (metaTop + toT)).coerceAtLeast(0f)
                val curT = (currentCardH - distFromBottom * cardScale) - metaTop
                val curL = (currentCardW - toW * cardScale) / 2f - metaLeft
                curAvTop = curT
                avContainer.pivotX = 0f
                avContainer.pivotY = 0f
                avContainer.scaleX = cardScale
                avContainer.scaleY = cardScale
                avContainer.alpha = secondaryAlpha
                avContainer.setLeftTopRightBottom(
                    curL.roundToInt(),
                    curT.roundToInt(),
                    (curL + toW).roundToInt(),
                    (curT + toH).roundToInt(),
                )
                listOf(
                    "getAvPrevView" to "getToAudioPrev",
                    "getAvPlayView" to "getToAudioPlay",
                    "getAvNextView" to "getToAudioNext",
                ).forEach { (viewGetter, toGetter) ->
                    val btn = call(delegate, viewGetter) as? View
                    val toBtn = call(animValue, toGetter)
                    if (btn != null && toBtn != null) {
                        val l = num(toBtn, "getLeft").roundToInt()
                        val t = num(toBtn, "getTop").roundToInt()
                        val w = num(toBtn, "getWidth").roundToInt().coerceAtLeast(1)
                        val h = num(toBtn, "getHeight").roundToInt().coerceAtLeast(1)
                        btn.alpha = 1f
                        btn.setLeftTopRightBottom(l, t, l + w, t + h)
                    }
                }
            }
        }

        // 5. Progress bar container:
        //    - If the collapsed custom tile owns a MediaProgressView, leave its seamless morph to
        //      ControlCenterTileLayoutHook on both expand and collapse.
        //    - Otherwise keep the progress bar centered inside the live gap between text and
        //      transport controls, scaling with card width and dissolving early on collapse.
        val progressView = call(delegate, "getProgressContainerView") as? View
        val toProgress = call(animValue, "getToProgressContainer")
        val hasCustomProgress = transition?.fromHasCustomProgress ?: tileLayoutHasProgress
        val customProgressActive = hasCustomProgress && progressView != null && progressView.alpha >= 0.99f
        if (progressView != null && toProgress != null && !customProgressActive) {
            val toProgT = num(toProgress, "getTop")
            val toProgW = num(toProgress, "getWidth").coerceAtLeast(1f)
            val toProgH = num(toProgress, "getHeight").coerceAtLeast(1f)
            val scaledProgW = toProgW * cardScale
            val scaledProgH = toProgH * cardScale

            val toTextBottom = when {
                subtitleView?.visibility == View.VISIBLE && toSubtitle != null ->
                    num(toSubtitle, "getTop") + num(toSubtitle, "getHeight")
                titleView?.visibility == View.VISIBLE && toTitle != null ->
                    num(toTitle, "getTop") + num(toTitle, "getHeight")
                noPlayView?.visibility == View.VISIBLE && toNoPlay != null ->
                    num(toNoPlay, "getTop") + num(toNoPlay, "getHeight")
                else -> num(toCoverParent, "getTop") + num(toCoverParent, "getHeight")
            }
            val curTextBottom = when {
                subtitleView?.visibility == View.VISIBLE -> subtitleView.bottom.toFloat()
                titleView?.visibility == View.VISIBLE -> titleView.bottom.toFloat()
                noPlayView?.visibility == View.VISIBLE -> noPlayView.bottom.toFloat()
                else -> curCoverT + curCoverH
            }

            val progT = if (!curAvTop.isNaN() && curAvTop > curTextBottom + 1f && toAudio != null) {
                val toAvTop = num(toAudio, "getTop")
                val expandedGap = (toAvTop - toTextBottom - toProgH).coerceAtLeast(1f)
                val gapRatio = ((toProgT - toTextBottom) / expandedGap).coerceIn(0f, 1f)
                val curSlack = curAvTop - curTextBottom - scaledProgH
                if (curSlack >= 0f) {
                    curTextBottom + curSlack * gapRatio
                } else {
                    (curTextBottom + curAvTop - scaledProgH) / 2f
                }
            } else {
                val distFromBottom = (toContentH - (metaTop + toProgT)).coerceAtLeast(0f)
                ((currentCardH - distFromBottom * cardScale) - metaTop)
                    .coerceIn(-metaTop, (currentCardH - metaTop - scaledProgH).coerceAtLeast(-metaTop))
            }
            val progL = (currentCardW - scaledProgW) / 2f - metaLeft

            progressView.pivotX = 0f
            progressView.pivotY = 0f
            progressView.scaleX = cardScale
            progressView.scaleY = cardScale
            progressView.alpha = secondaryAlpha
            progressView.setLeftTopRightBottom(
                progL.roundToInt(),
                progT.roundToInt(),
                (progL + toProgW).roundToInt(),
                (progT + toProgH).roundToInt(),
            )
        }

        // 6. Volume bar + Output Switcher Pill: stay proportionally inside the bottom of the card.
        animateVolumeAndPill(delegate, state, toVolume, currentCardW, currentCardH, toContentH, cardScale, secondaryAlpha)
    }

    private fun morphTextView(
        view: TextView,
        fromLoc: Any,
        toLoc: Any,
        wasVisible: Boolean,
        fromFontPx: Float,
        toFontPx: Float,
        fromGravity: Int?,
        fromContentLocL: Float,
        fromContentLocT: Float,
        metaLeft: Float,
        metaTop: Float,
        fallbackStartLeft: Float,
        fallbackStartTop: Float,
        size: Float,
        clampedSize: Float,
        cardScale: Float,
        clampedAlpha: Float,
    ) {
        val toL = num(toLoc, "getLeft")
        val toT = num(toLoc, "getTop")
        val toW = num(toLoc, "getWidth").coerceAtLeast(1f)
        val toH = num(toLoc, "getHeight").coerceAtLeast(1f)
        if (wasVisible) {
            if (fromGravity != null && view.gravity != fromGravity) {
                view.gravity = fromGravity
            }
            val fromL = (num(fromLoc, "getLocLeft") - fromContentLocL) - metaLeft
            val fromT = (num(fromLoc, "getLocTop") - fromContentLocT) - metaTop
            val fromW = num(fromLoc, "getWidth").coerceAtLeast(1f)
            val fromH = num(fromLoc, "getHeight").coerceAtLeast(1f)
            val curL = lerp(fromL, toL, size)
            val curT = lerp(fromT, toT, size)
            val curW = lerp(fromW, toW, clampedSize).coerceAtLeast(1f)
            val curH = lerp(fromH, toH, clampedSize).coerceAtLeast(1f)
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            if (fromFontPx > 0f && toFontPx > 0f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, lerp(fromFontPx, toFontPx, clampedSize))
            }
            view.setLeftTopRightBottom(
                curL.roundToInt(),
                curT.roundToInt(),
                (curL + curW).roundToInt(),
                (curT + curH).roundToInt(),
            )
        } else {
            val curL = lerp(fallbackStartLeft, toL, size)
            val curT = lerp(fallbackStartTop, toT, size)
            val textScale = lerp(cardScale.coerceIn(0.65f, 1f), 1f, clampedSize)
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = textScale
            view.scaleY = textScale
            view.alpha = clampedAlpha
            view.setLeftTopRightBottom(
                curL.roundToInt(),
                curT.roundToInt(),
                (curL + toW).roundToInt(),
                (curT + toH).roundToInt(),
            )
            if (toFontPx > 0f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, toFontPx)
            }
        }
    }

    private fun morphTransportButton(
        button: View?,
        fromVal: Any?,
        toVal: Any?,
        wasVisible: Boolean,
        size: Float,
        clampedAlpha: Float,
    ) {
        if (button == null || fromVal == null || toVal == null) return
        val fromL = num(fromVal, "getLeft")
        val fromT = num(fromVal, "getTop")
        val fromW = num(fromVal, "getWidth").coerceAtLeast(1f)
        val fromH = num(fromVal, "getHeight").coerceAtLeast(1f)
        val toL = num(toVal, "getLeft")
        val toT = num(toVal, "getTop")
        val toW = num(toVal, "getWidth").coerceAtLeast(1f)
        val toH = num(toVal, "getHeight").coerceAtLeast(1f)
        val curL = lerp(fromL, toL, size)
        val curT = lerp(fromT, toT, size)
        val curW = lerp(fromW, toW, size).coerceAtLeast(1f)
        val curH = lerp(fromH, toH, size).coerceAtLeast(1f)
        button.alpha = if (wasVisible) 1f else clampedAlpha
        button.setLeftTopRightBottom(
            curL.roundToInt(),
            curT.roundToInt(),
            (curL + curW).roundToInt(),
            (curT + curH).roundToInt(),
        )
    }

    private fun animateVolumeAndPill(
        delegate: Any,
        state: PanelState,
        toVolume: Any?,
        currentCardW: Float,
        currentCardH: Float,
        toContentH: Float,
        cardScale: Float,
        clampedAlpha: Float,
    ) {
        val volumeView = call(delegate, "getVolumeBarContainerView") as? View
        if (volumeView != null && toVolume != null) {
            val volW = num(toVolume, "getWidth").coerceAtLeast(1f)
            val volH = num(toVolume, "getHeight").coerceAtLeast(1f)
            val volToTop = num(toVolume, "getTop")
            val distFromBottom = (toContentH - volToTop).coerceAtLeast(0f)
            val curTop = currentCardH - distFromBottom * cardScale
            val curLeft = (currentCardW - volW * cardScale) / 2f
            volumeView.pivotX = 0f
            volumeView.pivotY = 0f
            volumeView.scaleX = cardScale
            volumeView.scaleY = cardScale
            volumeView.alpha = clampedAlpha
            volumeView.setLeftTopRightBottom(
                curLeft.roundToInt(),
                curTop.roundToInt(),
                (curLeft + volW).roundToInt(),
                (curTop + volH).roundToInt(),
            )
        }

        val pill = state.outputPill
        val pillTarget = state.transition?.pillTarget
        if (pill != null && pill.visibility == View.VISIBLE && pillTarget != null) {
            val pillW = pillTarget.width().toFloat().coerceAtLeast(1f)
            val pillH = pillTarget.height().toFloat().coerceAtLeast(1f)
            val distFromBottom = (toContentH - pillTarget.top).coerceAtLeast(0f)
            val curTop = currentCardH - distFromBottom * cardScale
            val curLeft = (currentCardW - pillW * cardScale) / 2f
            pill.pivotX = 0f
            pill.pivotY = 0f
            pill.scaleX = cardScale
            pill.scaleY = cardScale
            pill.alpha = clampedAlpha
            pill.setLeftTopRightBottom(
                curLeft.roundToInt(),
                curTop.roundToInt(),
                (curLeft + pillW).roundToInt(),
                (curTop + pillH).roundToInt(),
            )
        }
    }

    private fun ensureState(content: View, header: ViewGroup): PanelState {
        headerToContent[header] = WeakReference(content)
        panelStates[content]?.let { return it }

        val metaInfo = find(header, "meta_data_info")
        val coverParent = find(header, "coverParent")
        val cover = find(header, "cover")
        val appIconContainer = find(header, "app_icon_container")
        val title = find(header, "title") as? TextView
        val titleNoPlay = find(header, "title_no_play_back_history") as? TextView
        val subtitle = find(header, "subtitle") as? TextView
        val audioContainer = find(header, "container_audio")
        val videoContainer = find(header, "container_video")
        val progressContainer = find(header, "container_progress")
        val volumeContainer = find(header, "volume_bar_container")
        val listView = find(content, "list")

        val volLp = volumeContainer?.layoutParams as? ViewGroup.MarginLayoutParams
        val snapshot = StockSnapshot(
            contentWidth = content.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            headerWidth = header.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            metaInfoWidth = metaInfo?.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            headerPaddingStart = header.paddingStart,
            headerPaddingTop = header.paddingTop,
            headerPaddingEnd = header.paddingEnd,
            headerPaddingBottom = header.paddingBottom,
            coverParent = coverParent?.let(::captureConstraint),
            coverParentOutlineProvider = coverParent?.outlineProvider,
            coverWidth = cover?.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            coverHeight = cover?.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            appIconContainer = appIconContainer?.let(::captureConstraint),
            appIconContainerVisibility = appIconContainer?.visibility ?: View.VISIBLE,
            title = title?.let(::captureConstraint),
            titleGravity = title?.gravity ?: Gravity.START,
            titleTextColors = title?.textColors,
            titleNoPlay = titleNoPlay?.let(::captureConstraint),
            titleNoPlayGravity = titleNoPlay?.gravity ?: Gravity.START,
            titleNoPlayTextColors = titleNoPlay?.textColors,
            subtitle = subtitle?.let(::captureConstraint),
            subtitleGravity = subtitle?.gravity ?: Gravity.START,
            subtitleTextColors = subtitle?.textColors,
            audioContainer = audioContainer?.let(::captureConstraint),
            videoContainer = videoContainer?.let(::captureConstraint),
            progressContainer = progressContainer?.let(::captureConstraint),
            volumeWidth = volLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            volumeHeight = volLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            volumeTopMargin = volLp?.topMargin ?: 0,
            volumeMarginStart = volLp?.marginStart ?: 0,
            volumeMarginEnd = volLp?.marginEnd ?: 0,
            listVisibility = listView?.visibility ?: View.VISIBLE,
        )
        return PanelState(snapshot).also { panelStates[content] = it }
    }

    private fun resolveCardWidthPx(content: View, header: ViewGroup, state: PanelState): Int {
        val container = state.containerRef?.get()
        // SecondaryPanelControllerBase.updateContainerConstraint() runs immediately before
        // MediaPanelDelegate.prepareShow(), setting mediaContainer.layoutParams.width to the
        // exact ControlCenter-density pixel width. Prefer it over MiPlay's context dimen.
        container?.layoutParams?.width?.takeIf { it > 0 }?.let { return it }
        container?.width?.takeIf { it > 0 }?.let { return it }
        (content.parent as? View)?.width?.takeIf { it > 0 }?.let { return it }
        val d = header.resources.displayMetrics.density
        val fallback = (328f * d).roundToInt()
        if (container != null) {
            return dimen(container.context, "control_center_secondary_panel_content_width", fallback)
        }
        return dimen(header.context, "control_center_secondary_panel_content_width", fallback)
    }

    private fun applyBigCoverLayout(content: View, header: ViewGroup, state: PanelState) {
        val context = header.context
        val metaInfo = find(header, "meta_data_info") as? ViewGroup ?: return
        val coverParent = find(header, "coverParent") ?: return
        val cover = find(header, "cover") ?: return
        val appIconContainer = find(header, "app_icon_container")
        val title = find(header, "title") as? TextView
        val titleNoPlay = find(header, "title_no_play_back_history") as? TextView
        val subtitle = find(header, "subtitle") as? TextView
        val audioContainer = find(header, "container_audio")
        val videoContainer = find(header, "container_video")
        val progressContainer = find(header, "container_progress")
        val tvController = find(header, "tv_controller_panel")
        val volumeContainer = find(header, "volume_bar_container")
        val listView = find(content, "list")

        val metricContext = state.containerRef?.get()?.context ?: context
        val d = metricContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).roundToInt()

        val cardWidth = resolveCardWidthPx(content, header, state)

        // MediaPanelDelegate adds contentView to mediaContent (a horizontal LinearLayout) with
        // default WRAP_CONTENT width. Without the explicit card width, hiding RecyclerView causes
        // contentView to shrink to the output pill width and clip the big cover horizontally.
        content.layoutParams?.let { lp ->
            if (lp.width != cardWidth) {
                lp.width = cardWidth
                content.layoutParams = lp
            }
        }
        header.layoutParams?.let { lp ->
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                header.layoutParams = lp
            }
        }
        metaInfo.layoutParams?.let { lp ->
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                metaInfo.layoutParams = lp
            }
        }

        val stockMaxHeight = dimen(metricContext, "control_center_detail_max_height", dp(500))
        val maxCardHeight = computeBigCoverMaxHeight(metricContext, stockMaxHeight)
        val basePad = dp(20)
        val tvExtra = if (tvController?.visibility == View.VISIBLE) dp(58) else 0
        val useImmersive = immersiveCover
        val cardRadius = dimen(metricContext, "detail_panel_background_corner_radius", dp(36))
        val bottomPad = dp(18)

        val coverSize: Int
        val sidePad: Int
        val contentSideMargin: Int
        if (useImmersive) {
            // Keep strict 1:1 square aspect ratio spanning full card width edge-to-edge.
            val maxSquareByHeight = (maxCardHeight - dp(200) - tvExtra).coerceAtLeast(dp(180))
            coverSize = minOf(cardWidth, maxSquareByHeight)
            sidePad = basePad
            contentSideMargin = basePad
            header.setPaddingRelative(0, 0, 0, bottomPad)
            state.immersiveCardRadius = cardRadius.toFloat()
            state.immersiveTopRadius = cardRadius.toFloat()
            state.immersiveBottomExtend = cardRadius.toFloat()
            val outlineProvider = state.immersiveOutlineProvider ?: object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val topR = state.immersiveTopRadius.coerceAtLeast(0f)
                    val ext = state.immersiveBottomExtend.roundToInt().coerceAtLeast(0)
                    outline.setRoundRect(0, 0, view.width, view.height + ext, topR)
                }
            }.also { state.immersiveOutlineProvider = it }
            coverParent.outlineProvider = outlineProvider
            coverParent.clipToOutline = true
            cover.clipToOutline = false
            call(header, "setCoverRadius", cardRadius.toFloat())
            coverParent.invalidateOutline()
            (cover as? ImageView)?.scaleType = ImageView.ScaleType.CENTER_CROP
            applyImmersiveCoverFade(cover, state, coverSize.toFloat(), 1f)
            attachCoverLayoutListener(cover, state)
            state.immersiveApplied = true
        } else {
            clearImmersiveEffects(header, state)
            val nonCoverBudget = dp(288) + tvExtra
            val maxCoverByHeight = (maxCardHeight - nonCoverBudget).coerceAtLeast(dp(160))
            val maxCoverByWidth = (cardWidth - basePad * 2).coerceAtLeast(dp(160))
            coverSize = minOf(maxCoverByWidth, maxCoverByHeight)
            sidePad = ((cardWidth - coverSize) / 2).coerceAtLeast(dp(16))
            contentSideMargin = 0
            val topPad = sidePad.coerceAtMost(dp(22))
            header.setPaddingRelative(sidePad, topPad, sidePad, bottomPad)

            // Concentric rounded cover corners matching the outer card corner radius.
            val coverRadius = (cardRadius - sidePad).coerceIn(dp(14), dp(22)).toFloat()
            call(header, "setCoverRadius", coverRadius)
            coverParent.clipToOutline = true
            cover.clipToOutline = true
            coverParent.invalidateOutline()
            cover.invalidateOutline()
        }

        setConstraint(
            coverParent,
            width = coverSize,
            height = coverSize,
            marginStart = 0,
            marginEnd = 0,
            topMargin = 0,
            bottomMargin = 0,
            startToStart = PARENT_ID,
            startToEnd = UNSET,
            endToEnd = PARENT_ID,
            endToStart = UNSET,
            topToTop = PARENT_ID,
            topToBottom = UNSET,
            bottomToBottom = UNSET,
            bottomToTop = UNSET,
        )
        cover.layoutParams = cover.layoutParams.apply {
            width = coverSize
            height = coverSize
        }
        if (useImmersive) {
            appIconContainer?.let { badge ->
                val stockBadge = state.stock.appIconContainer
                val badgeW = stockBadge?.width ?: dp(24)
                val badgeH = stockBadge?.height ?: dp(24)
                setConstraint(
                    badge,
                    width = badgeW,
                    height = badgeH,
                    marginStart = 0,
                    marginEnd = dp(16),
                    topMargin = dp(16),
                    bottomMargin = 0,
                    startToStart = UNSET,
                    startToEnd = UNSET,
                    endToEnd = cover.id,
                    endToStart = UNSET,
                    topToTop = cover.id,
                    topToBottom = UNSET,
                    bottomToBottom = UNSET,
                    bottomToTop = UNSET,
                )
                val appIconView = find(badge, "app_icon") as? ImageView
                badge.visibility = if (appIconView?.drawable != null) View.VISIBLE else View.GONE
                badge.alpha = 1f
                badge.scaleX = 1f
                badge.scaleY = 1f
            }
        } else {
            appIconContainer?.visibility = View.GONE
        }

        val titleTopToTop = if (useImmersive) coverParent.id else UNSET
        val titleTopToBottom = if (useImmersive) UNSET else coverParent.id
        val titleTopMargin = if (useImmersive) (coverSize * 0.78f).roundToInt() else dp(16)
        val showShadow = useImmersive && immersiveTextShadow

        title?.let {
            it.gravity = Gravity.START
            if (showShadow) {
                it.setShadowLayer(4f * d, 0f, 1f * d, 0x55000000)
            } else {
                it.setShadowLayer(0f, 0f, 0f, 0)
            }
            setConstraint(
                it,
                width = 0,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = contentSideMargin,
                marginEnd = contentSideMargin,
                topMargin = titleTopMargin,
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = titleTopToTop,
                topToBottom = titleTopToBottom,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        titleNoPlay?.let {
            it.gravity = Gravity.START
            if (showShadow) {
                it.setShadowLayer(4f * d, 0f, 1f * d, 0x55000000)
            } else {
                it.setShadowLayer(0f, 0f, 0f, 0)
            }
            setConstraint(
                it,
                width = 0,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = contentSideMargin,
                marginEnd = contentSideMargin,
                topMargin = titleTopMargin,
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = titleTopToTop,
                topToBottom = titleTopToBottom,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        subtitle?.let {
            it.gravity = Gravity.START
            if (showShadow) {
                it.setShadowLayer(3f * d, 0f, 1f * d, 0x44000000)
            } else {
                it.setShadowLayer(0f, 0f, 0f, 0)
            }
            setConstraint(
                it,
                width = 0,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = contentSideMargin,
                marginEnd = contentSideMargin,
                topMargin = dp(2),
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = UNSET,
                topToBottom = title?.id ?: coverParent.id,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        val barrierId = ensureTextBarrier(metaInfo, state, title, subtitle, titleNoPlay)
        val progressAnchor = barrierId ?: subtitle?.id ?: title?.id ?: coverParent.id

        progressContainer?.let {
            setConstraint(
                it,
                width = 0,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = contentSideMargin,
                marginEnd = contentSideMargin,
                topMargin = dp(12),
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = UNSET,
                topToBottom = progressAnchor,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        val actionSize = dimen(context, "miplay_detail_header_action_size", dp(44))
        val controlsAnchor = progressContainer?.id ?: progressAnchor
        val controlsInset = contentSideMargin + dp(28)

        audioContainer?.let {
            setConstraint(
                it,
                width = 0,
                height = actionSize,
                marginStart = controlsInset,
                marginEnd = controlsInset,
                topMargin = dp(12),
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = UNSET,
                topToBottom = controlsAnchor,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        videoContainer?.let {
            setConstraint(
                it,
                width = 0,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = controlsInset,
                marginEnd = controlsInset,
                topMargin = dp(12),
                bottomMargin = 0,
                startToStart = PARENT_ID,
                startToEnd = UNSET,
                endToEnd = PARENT_ID,
                endToStart = UNSET,
                topToTop = UNSET,
                topToBottom = controlsAnchor,
                bottomToBottom = UNSET,
                bottomToTop = UNSET,
            )
        }

        volumeContainer?.let { volume ->
            (volume.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = dimen(context, "miplay_detail_header_volume_bar_height", dp(28))
                lp.topMargin = dp(16)
                lp.marginStart = contentSideMargin
                lp.marginEnd = contentSideMargin
                volume.layoutParams = lp
            }
        }

        val pill = ensureOutputPill(content, header, state)
        (pill.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = dp(16)
            pill.layoutParams = lp
        }
        pill.visibility = View.VISIBLE
        pill.scaleX = 1f
        pill.scaleY = 1f
        pill.alpha = 1f
        updateOutputPillAppearance(content, state)

        listView?.visibility = View.GONE
        state.bigCoverApplied = true
        if (useImmersive) {
            syncImmersiveTextColor(
                content = content,
                header = header,
                state = state,
                bitmap = resolveCurrentCoverBitmap(content, header, state),
                animate = false,
            )
        } else {
            restoreStockTextColors(header, state)
        }

        metaInfo.requestLayout()
        header.requestLayout()
        content.requestLayout()
    }

    /** Compact OEM header + native MiPlay device list, with the pill acting as a return button. */
    private fun applyDevicePickerLayout(content: View, header: ViewGroup, state: PanelState) {
        restoreStockViewsOnly(content, header, state)
        val cardWidth = resolveCardWidthPx(content, header, state)
        content.layoutParams?.let { lp ->
            if (lp.width != cardWidth) {
                lp.width = cardWidth
                content.layoutParams = lp
            }
        }
        val d = header.resources.displayMetrics.density
        val pill = ensureOutputPill(content, header, state)
        (pill.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = (12 * d).roundToInt()
            pill.layoutParams = lp
        }
        pill.visibility = View.VISIBLE
        pill.scaleX = 1f
        pill.scaleY = 1f
        pill.alpha = 1f
        updateOutputPillAppearance(content, state)

        find(content, "list")?.visibility = View.VISIBLE
        val listItems = call(content, "getMListItems")
        if (listItems != null) {
            call(content, "updateHeight", listItems, false, true)
        }
        header.requestLayout()
        content.requestLayout()
    }

    private fun restoreStockLayout(content: View, header: ViewGroup, state: PanelState) {
        restoreStockViewsOnly(content, header, state)
        content.layoutParams?.let { lp ->
            if (lp.width != state.stock.contentWidth) {
                lp.width = state.stock.contentWidth
                content.layoutParams = lp
            }
        }
        state.outputPill?.visibility = View.GONE
        state.transition = null
        find(content, "list")?.visibility = state.stock.listVisibility
        header.requestLayout()
        content.requestLayout()
    }

    private fun restoreStockViewsOnly(content: View, header: ViewGroup, state: PanelState) {
        clearImmersiveEffects(header, state)
        val stock = state.stock
        val context = header.context
        header.layoutParams?.let { lp ->
            if (lp.width != stock.headerWidth) {
                lp.width = stock.headerWidth
                header.layoutParams = lp
            }
        }
        find(header, "meta_data_info")?.let { meta ->
            meta.layoutParams?.let { lp ->
                if (lp.width != stock.metaInfoWidth) {
                    lp.width = stock.metaInfoWidth
                    meta.layoutParams = lp
                }
            }
        }
        header.setPaddingRelative(
            stock.headerPaddingStart,
            stock.headerPaddingTop,
            stock.headerPaddingEnd,
            stock.headerPaddingBottom,
        )
        val stockCoverRadius = dimen(
            context,
            "miplay_detail_header_cover_radius",
            (20 * context.resources.displayMetrics.density).roundToInt(),
        ).toFloat()
        call(header, "setCoverRadius", stockCoverRadius)

        find(header, "coverParent")?.let { v -> stock.coverParent?.let { restoreConstraint(v, it) } }
        find(header, "cover")?.let { cover ->
            cover.layoutParams = cover.layoutParams.apply {
                width = stock.coverWidth
                height = stock.coverHeight
            }
        }
        find(header, "app_icon_container")?.let { badge ->
            stock.appIconContainer?.let { restoreConstraint(badge, it) }
            badge.visibility = stock.appIconContainerVisibility
        }
        (find(header, "title") as? TextView)?.let { tv ->
            tv.gravity = stock.titleGravity
            tv.setShadowLayer(0f, 0f, 0f, 0)
            stock.title?.let { restoreConstraint(tv, it) }
        }
        (find(header, "title_no_play_back_history") as? TextView)?.let { tv ->
            tv.gravity = stock.titleNoPlayGravity
            tv.setShadowLayer(0f, 0f, 0f, 0)
            stock.titleNoPlay?.let { restoreConstraint(tv, it) }
        }
        (find(header, "subtitle") as? TextView)?.let { tv ->
            tv.gravity = stock.subtitleGravity
            tv.setShadowLayer(0f, 0f, 0f, 0)
            stock.subtitle?.let { restoreConstraint(tv, it) }
        }
        find(header, "container_audio")?.let { v -> stock.audioContainer?.let { restoreConstraint(v, it) } }
        find(header, "container_video")?.let { v -> stock.videoContainer?.let { restoreConstraint(v, it) } }
        find(header, "container_progress")?.let { v -> stock.progressContainer?.let { restoreConstraint(v, it) } }
        find(header, "volume_bar_container")?.let { volume ->
            (volume.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = stock.volumeWidth
                lp.height = stock.volumeHeight
                lp.topMargin = stock.volumeTopMargin
                lp.marginStart = stock.volumeMarginStart
                lp.marginEnd = stock.volumeMarginEnd
                volume.layoutParams = lp
            }
        }
        state.bigCoverApplied = false
        find(header, "meta_data_info")?.requestLayout()
    }

    private fun applyImmersiveCoverFade(
        cover: View,
        state: PanelState,
        heightPx: Float,
        fadeStrength: Float,
    ) {
        val strength = fadeStrength.coerceIn(0f, 1f)
        if (strength <= 0.001f) {
            cover.setRenderEffect(null)
            return
        }
        runCatching {
            val shader = state.coverFadeShader ?: RuntimeShader(IMMERSIVE_COVER_FADE_AGSL).also {
                state.coverFadeShader = it
            }
            shader.setFloatUniform("height", heightPx.coerceAtLeast(1f))
            shader.setFloatUniform("fadeStart", IMMERSIVE_FADE_START)
            shader.setFloatUniform("fadeStrength", strength)
            cover.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }
    }

    private fun attachCoverLayoutListener(cover: View, state: PanelState) {
        if (state.coverLayoutListener != null) return
        val listener = View.OnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val newH = bottom - top
            if (state.immersiveApplied && newH > 0 && newH != (oldBottom - oldTop)) {
                applyImmersiveCoverFade(v, state, newH.toFloat(), 1f)
            }
        }
        state.coverLayoutListener = listener
        cover.addOnLayoutChangeListener(listener)
    }

    private fun clearImmersiveEffects(header: ViewGroup, state: PanelState) {
        state.immersiveApplied = false
        find(header, "cover")?.let { cover ->
            cover.setRenderEffect(null)
            state.coverLayoutListener?.let { listener ->
                cover.removeOnLayoutChangeListener(listener)
                state.coverLayoutListener = null
            }
        }
        find(header, "coverParent")?.let { coverParent ->
            if (state.stock.coverParentOutlineProvider != null &&
                coverParent.outlineProvider === state.immersiveOutlineProvider
            ) {
                coverParent.outlineProvider = state.stock.coverParentOutlineProvider
            }
            coverParent.invalidateOutline()
        }
        find(header, "app_icon_container")?.let { badge ->
            state.stock.appIconContainer?.let { restoreConstraint(badge, it) }
        }
        (find(header, "title") as? TextView)?.setShadowLayer(0f, 0f, 0f, 0)
        (find(header, "title_no_play_back_history") as? TextView)?.setShadowLayer(0f, 0f, 0f, 0)
        (find(header, "subtitle") as? TextView)?.setShadowLayer(0f, 0f, 0f, 0)
        restoreStockTextColors(header, state)
    }

    private fun resolveCurrentCoverBitmap(content: View, header: ViewGroup, state: PanelState): Bitmap? {
        state.lastCoverRef?.get()?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { return it }
        runCatching {
            val vmClass = Class.forName(VIEW_MODEL, false, content.javaClass.classLoader)
            val vm = vmClass.getField("INSTANCE").get(null)
            val metaLive = vmClass.getMethod("getMMediaMetaData").invoke(vm)
            val meta = metaLive?.let { it.javaClass.getMethod("getValue").invoke(it) }
            val art = meta?.let { it.javaClass.getMethod("getArt").invoke(it) as? Bitmap }
            if (art != null && !art.isRecycled && art.width > 0 && art.height > 0) {
                state.lastCoverRef = WeakReference(art)
                return art
            }
        }
        val coverDrawable = (find(header, "cover") as? ImageView)?.drawable as? BitmapDrawable
        return coverDrawable?.bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }

    private fun syncImmersiveTextColor(
        content: View,
        header: ViewGroup,
        state: PanelState,
        bitmap: Bitmap?,
        animate: Boolean,
    ) {
        val mode = immersiveTextColorMode
        if (!enabled || !state.immersiveApplied || state.showingDevices || mode == CC_MEDIA_TEXT_COLOR_OFF) {
            restoreStockTextColors(header, state)
            return
        }
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            restoreStockTextColors(header, state)
            return
        }
        val key = ControlCenterMediaCoverColorEngine.cacheKey(bitmap, mode)
        if (state.appliedColorKey == key && state.appliedColors != null) {
            applyMediaTextColors(header, state, state.appliedColors!!, animate = false)
            return
        }
        ControlCenterMediaCoverColorEngine.getCached(key)?.let { cached ->
            state.colorRequestToken++
            state.appliedColorKey = key
            state.appliedColors = cached
            applyMediaTextColors(header, state, cached, animate = animate)
            return
        }

        val token = ++state.colorRequestToken
        val headerRef = WeakReference(header)
        val contentRef = WeakReference(content)
        val bitmapRef = WeakReference(bitmap)
        colorWorker.execute {
            val source = bitmapRef.get() ?: return@execute
            val extracted = runCatching {
                ControlCenterMediaCoverColorEngine.extract(source, mode)
            }.getOrNull() ?: return@execute
            mainHandler.post {
                val targetHeader = headerRef.get() ?: return@post
                val targetContent = contentRef.get() ?: return@post
                if (state.colorRequestToken != token) return@post
                if (!enabled || !shouldUseBigCover(targetContent) || !state.immersiveApplied || state.showingDevices) {
                    return@post
                }
                if (immersiveTextColorMode != mode) return@post
                state.appliedColorKey = key
                state.appliedColors = extracted
                applyMediaTextColors(targetHeader, state, extracted, animate = animate)
            }
        }
    }

    private fun applyMediaTextColors(
        header: ViewGroup,
        state: PanelState,
        colors: MediaCoverTextColors,
        animate: Boolean,
    ) {
        val title = find(header, "title") as? TextView
        val subtitle = find(header, "subtitle") as? TextView
        val titleNoPlay = find(header, "title_no_play_back_history") as? TextView
        state.textColorAnimator?.cancel()
        state.textColorAnimator = null

        val fromTitle = title?.currentTextColor ?: colors.titleColor
        val fromSubtitle = subtitle?.currentTextColor ?: colors.subtitleColor
        if (!animate || (fromTitle == colors.titleColor && fromSubtitle == colors.subtitleColor)) {
            title?.setTextColor(colors.titleColor)
            subtitle?.setTextColor(colors.subtitleColor)
            titleNoPlay?.setTextColor(colors.titleColor)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val curTitle = lerpArgb(fromTitle, colors.titleColor, f)
                val curSub = lerpArgb(fromSubtitle, colors.subtitleColor, f)
                title?.setTextColor(curTitle)
                subtitle?.setTextColor(curSub)
                titleNoPlay?.setTextColor(curTitle)
            }
        }
        state.textColorAnimator = animator
        animator.start()
    }

    private fun restoreStockTextColors(header: ViewGroup, state: PanelState) {
        state.colorRequestToken++
        state.textColorAnimator?.cancel()
        state.textColorAnimator = null
        state.appliedColorKey = null
        state.appliedColors = null
        state.stock.titleTextColors?.let { (find(header, "title") as? TextView)?.setTextColor(it) }
        state.stock.subtitleTextColors?.let { (find(header, "subtitle") as? TextView)?.setTextColor(it) }
        state.stock.titleNoPlayTextColors?.let {
            (find(header, "title_no_play_back_history") as? TextView)?.setTextColor(it)
        }
    }

    private fun lerpArgb(startArgb: Int, endArgb: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val sa = (startArgb ushr 24) and 0xFF
        val sr = (startArgb ushr 16) and 0xFF
        val sg = (startArgb ushr 8) and 0xFF
        val sb = startArgb and 0xFF
        val ea = (endArgb ushr 24) and 0xFF
        val er = (endArgb ushr 16) and 0xFF
        val eg = (endArgb ushr 8) and 0xFF
        val eb = endArgb and 0xFF
        val a = (sa + (ea - sa) * t).roundToInt().coerceIn(0, 255)
        val r = (sr + (er - sr) * t).roundToInt().coerceIn(0, 255)
        val g = (sg + (eg - sg) * t).roundToInt().coerceIn(0, 255)
        val b = (sb + (eb - sb) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun ensureTextBarrier(
        metaInfo: ViewGroup,
        state: PanelState,
        title: View?,
        subtitle: View?,
        titleNoPlay: View?,
    ): Int? {
        val ids = listOfNotNull(title?.id, subtitle?.id, titleNoPlay?.id)
            .filter { it != View.NO_ID }
            .toIntArray()
        if (ids.isEmpty()) return null
        val existing = state.textBarrier
        if (existing != null && existing.parent === metaInfo) {
            call(existing, "setReferencedIds", ids)
            return existing.id
        }
        return runCatching {
            val barrierClass = metaInfo.javaClass.classLoader!!.loadClass("androidx.constraintlayout.widget.Barrier")
            val barrier = barrierClass.getConstructor(Context::class.java).newInstance(metaInfo.context) as View
            barrier.id = View.generateViewId()
            call(barrier, "setType", BARRIER_BOTTOM)
            call(barrier, "setReferencedIds", ids)
            metaInfo.addView(
                barrier,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            state.textBarrier = barrier
            barrier.id
        }.getOrNull()
    }

    private fun ensureOutputPill(content: View, header: ViewGroup, state: PanelState): LinearLayout {
        state.outputPill?.let { existing ->
            if (existing.parent === header) return existing
            (existing.parent as? ViewGroup)?.removeView(existing)
            header.addView(existing)
            return existing
        }
        val context = header.context
        val d = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).roundToInt()

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPaddingRelative(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34),
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.65f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            setOnClickListener {
                if (it.alpha < 0.5f) return@setOnClickListener
                if (!enabled || !shouldUseBigCover(content)) return@setOnClickListener
                state.showingDevices = !state.showingDevices
                clearAnimatorSnapshots()
                if (state.showingDevices) {
                    applyDevicePickerLayout(content, header, state)
                } else {
                    applyBigCoverLayout(content, header, state)
                }
                content.animate().cancel()
                content.alpha = 0.78f
                content.animate().alpha(1f).setDuration(160L).start()
            }
        }

        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(0xE6FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(216)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(6)
            }
        }
        pill.addView(icon)
        pill.addView(label)
        header.addView(pill)

        state.outputPill = pill
        state.outputPillIcon = icon
        state.outputPillLabel = label
        return pill
    }

    private fun updateOutputPillAppearance(content: View, state: PanelState) {
        val pill = state.outputPill ?: return
        val icon = state.outputPillIcon ?: return
        val label = state.outputPillLabel ?: return
        val context = pill.context
        val d = context.resources.displayMetrics.density

        val bg = (pill.background as? GradientDrawable) ?: GradientDrawable().also { pill.background = it }
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = 17f * d
        if (state.showingDevices) {
            bg.setColor(0x42FFFFFF)
            bg.setStroke(1, 0x40FFFFFF)
        } else {
            bg.setColor(0x29FFFFFF)
            bg.setStroke(1, 0x26FFFFFF)
        }

        val (deviceName, iconRes) = readActiveOutputInfo(content)
        val fallbackLabel = pluginString(context, "miplay_accessibility_cast") ?: "AirPlay"
        label.text = deviceName?.takeIf { it.isNotBlank() } ?: fallbackLabel

        val resolvedIcon = iconRes.takeIf { it != 0 }
            ?: context.resources.getIdentifier("ic_media_device_default", "drawable", PLUGIN_PACKAGE)
        if (resolvedIcon != 0) {
            runCatching { icon.setImageResource(resolvedIcon) }
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
        pill.contentDescription = label.text
    }

    private fun readActiveOutputInfo(content: View): Pair<String?, Int> {
        var name: String? = null
        var iconRes = 0
        runCatching {
            val vmClass = Class.forName(VIEW_MODEL, false, content.javaClass.classLoader)
            val vm = vmClass.getField("INSTANCE").get(null)
            val descLive = vmClass.getMethod("getMCastDescription").invoke(vm)
            name = descLive?.let { it.javaClass.getMethod("getValue").invoke(it) as? String }
            val iconLive = vmClass.getMethod("getMCastingDeviceIcon").invoke(vm)
            iconRes = (iconLive?.let { it.javaClass.getMethod("getValue").invoke(it) as? Int }) ?: 0
        }
        return name to iconRes
    }

    private fun shouldUseBigCover(content: View): Boolean {
        if (!isVerticalEligible(content.context)) return false
        if (content is LinearLayout && content.orientation != LinearLayout.VERTICAL) return false
        return true
    }

    private fun isVerticalEligible(context: Context): Boolean {
        val config = context.resources.configuration
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        val metrics = context.resources.displayMetrics
        // Avoid tiny flip outer screens where a large vertical cover cannot fit.
        val heightDp = metrics.heightPixels / metrics.density.coerceAtLeast(1f)
        val widthDp = metrics.widthPixels / metrics.density.coerceAtLeast(1f)
        return heightDp >= 560f && widthDp >= 300f
    }

    private fun computeBigCoverMaxHeight(context: Context, stockMaxHeight: Int): Int {
        val metrics = context.resources.displayMetrics
        val desired = (632f * metrics.density).roundToInt()
        val screenCap = (metrics.heightPixels * 0.80f).roundToInt()
        return maxOf(stockMaxHeight, minOf(desired, screenCap))
    }

    private fun clearAnimatorSnapshots() {
        activeAnimators.toList().forEach { animator ->
            runCatching { setNullable(animator, "lastAnimValue", null) }
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun captureConstraint(view: View): ConstraintSnapshot? {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return null
        return ConstraintSnapshot(
            width = lp.width,
            height = lp.height,
            marginStart = lp.marginStart,
            marginEnd = lp.marginEnd,
            topMargin = lp.topMargin,
            bottomMargin = lp.bottomMargin,
            startToStart = intField(lp, "startToStart", UNSET),
            startToEnd = intField(lp, "startToEnd", UNSET),
            endToEnd = intField(lp, "endToEnd", UNSET),
            endToStart = intField(lp, "endToStart", UNSET),
            topToTop = intField(lp, "topToTop", UNSET),
            topToBottom = intField(lp, "topToBottom", UNSET),
            bottomToBottom = intField(lp, "bottomToBottom", UNSET),
            bottomToTop = intField(lp, "bottomToTop", UNSET),
        )
    }

    private fun restoreConstraint(view: View, snapshot: ConstraintSnapshot) {
        setConstraint(
            view = view,
            width = snapshot.width,
            height = snapshot.height,
            marginStart = snapshot.marginStart,
            marginEnd = snapshot.marginEnd,
            topMargin = snapshot.topMargin,
            bottomMargin = snapshot.bottomMargin,
            startToStart = snapshot.startToStart,
            startToEnd = snapshot.startToEnd,
            endToEnd = snapshot.endToEnd,
            endToStart = snapshot.endToStart,
            topToTop = snapshot.topToTop,
            topToBottom = snapshot.topToBottom,
            bottomToBottom = snapshot.bottomToBottom,
            bottomToTop = snapshot.bottomToTop,
        )
    }

    private fun setConstraint(
        view: View,
        width: Int,
        height: Int,
        marginStart: Int,
        marginEnd: Int,
        topMargin: Int,
        bottomMargin: Int,
        startToStart: Int,
        startToEnd: Int,
        endToEnd: Int,
        endToStart: Int,
        topToTop: Int,
        topToBottom: Int,
        bottomToBottom: Int,
        bottomToTop: Int,
    ) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.width = width
        lp.height = height
        lp.marginStart = marginStart
        lp.marginEnd = marginEnd
        lp.topMargin = topMargin
        lp.bottomMargin = bottomMargin
        setIntField(lp, "startToStart", startToStart)
        setIntField(lp, "startToEnd", startToEnd)
        setIntField(lp, "endToEnd", endToEnd)
        setIntField(lp, "endToStart", endToStart)
        setIntField(lp, "topToTop", topToTop)
        setIntField(lp, "topToBottom", topToBottom)
        setIntField(lp, "bottomToBottom", bottomToBottom)
        setIntField(lp, "bottomToTop", bottomToTop)
        view.layoutParams = lp
    }

    private fun intField(target: Any, name: String, fallback: Int): Int =
        runCatching { findField(target.javaClass, name)?.getInt(target) ?: fallback }.getOrDefault(fallback)

    private fun setIntField(target: Any, name: String, value: Int) {
        runCatching { findField(target.javaClass, name)?.setInt(target, value) }
    }

    private fun dimen(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", PLUGIN_PACKAGE)
        return if (id != 0) runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(fallback) else fallback
    }

    private fun pluginString(context: Context, name: String): String? {
        val id = context.resources.getIdentifier(name, "string", PLUGIN_PACKAGE)
        return if (id != 0) runCatching { context.resources.getString(id) }.getOrNull() else null
    }

    private fun find(view: View, name: String): View? {
        val id = view.resources.getIdentifier(name, "id", PLUGIN_PACKAGE)
        return if (id != 0) view.findViewById(id) else null
    }

    private fun num(target: Any, methodName: String): Float =
        ((call(target, methodName) as? Number)?.toFloat()) ?: 0f

    private fun field(target: Any?, name: String): Any? =
        target?.let { findField(it.javaClass, name)?.get(it) }

    private fun setNullable(target: Any, name: String, value: Any?) {
        findField(target.javaClass, name)?.set(target, value)
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
        private const val PARENT_ID = 0
        private const val UNSET = -1
        private const val BARRIER_BOTTOM = 3
        private const val IMMERSIVE_FADE_START = 0.46f
        private const val IMMERSIVE_COVER_FADE_AGSL = """
            uniform shader content;
            uniform float height;
            uniform float fadeStart;
            uniform float fadeStrength;
            half4 main(float2 coord) {
                half4 c = content.eval(coord);
                float denom = max(1.0 - fadeStart, 0.001);
                float t = clamp((coord.y / max(height, 1.0) - fadeStart) / denom, 0.0, 1.0);
                float fade = 1.0 - (t * t * (3.0 - 2.0 * t)) * fadeStrength;
                return c * fade;
            }
        """

        private const val MEDIA_PANEL_CONTROLLER =
            "miui.systemui.controlcenter.panel.secondary.media.MediaPanelController"
        private const val MEDIA_PANEL_DELEGATE =
            "miui.systemui.controlcenter.panel.secondary.media.MediaPanelDelegate"
        private const val MEDIA_PANEL_ANIMATOR =
            "miui.systemui.controlcenter.panel.secondary.media.MediaPanelAnimator"
        private const val DETAIL_CONTENT = "com.android.systemui.QSControlMiPlayDetailContent"
        private const val DETAIL_CONTENT_LAND = "com.android.systemui.QSControlMiPlayDetailContentSupportLand"
        private const val DETAIL_HEADER = "com.android.systemui.QSControlMiPlayDetailHeader"
        private const val VIEW_MODEL = "com.android.systemui.MiPlayDetailViewModel"

        private val targetClasses = setOf(
            MEDIA_PANEL_CONTROLLER,
            MEDIA_PANEL_DELEGATE,
            MEDIA_PANEL_ANIMATOR,
            DETAIL_CONTENT,
            DETAIL_CONTENT_LAND,
            DETAIL_HEADER,
        )
    }
}
