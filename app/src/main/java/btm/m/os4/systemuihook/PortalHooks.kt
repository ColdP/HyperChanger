// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Only Portal's main process installs these app-specific hooks. */
internal class PortalHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private val installed = mutableSetOf<String>()
    private val cards = WeakHashMap<View, CardState>()
    private val buttons = WeakHashMap<ImageView, Boolean>()
    private val splashIcons = WeakHashMap<ImageView, Boolean>()
    private var globalGlass: SoftGlassRuntimeSettings? = null
    private val ocrHooks = PortalOcrHooks(module, hookDeoptimized, logTag)
    private val cardNames = setOf(
        "TaplusRecognitionExpandedCard", "TaplusRecommendExpandedCard", "TaplusCtaCard",
        "TaplusRecommendShrinkCard", "TaplusRecognitionShrinkCard", "TaplusClipboardShrinkCard",
        "TaplusLoadingCard",
    ).map { "com.miui.contentextension.text.cardview.$it" }.toSet()
    private val floatPrefix = "com.miui.contentextension.text.floatview."

    // The retained native clone has no callback; the assigned Drawable is weak to avoid
    // keeping a destroyed View alive through Drawable.callback.
    private class CardState(var native: GradientDrawable) {
        var output = WeakReference<Drawable>(null)
    }

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        val stored = PortalSettings.read(preferences)
        val settings = stored.copy(glass = resolvePortalGlass(preferences, stored))
        if (settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS && settings.useGlobalGlass && globalGlass == null) {
            val main = Handler(Looper.getMainLooper())
            globalGlass = SoftGlassRuntimeSettings(preferences) {
                main.post {
                    cards.keys.toList().filter { it.isAttachedToWindow }.forEach { protect { applyCard(it, settings, refresh = true) } }
                    buttons.keys.toList().filter { it.isAttachedToWindow }.forEach { protect { applyCloseButton(it, settings) } }
                }
            }
        }
        if (settings.japaneseOcr) ocrHooks.install(classLoader, preferences)
        val material = settings.materialMode != PORTAL_MATERIAL_DEFAULT
        val adjustCards = material || (settings.customCorner && settings.cornerOffsetDp != 0)
        if (settings.customSplashSize) installHook("splash-size") {
            hookAfter(classLoader.loadClass(floatPrefix + "TaplusSplashFloatView"), "initView") { owner ->
                if (owner.field("mIsClipboardView") != false || settings.splashSizeOffsetPercent == 0) return@hookAfter
                val root = owner.field("mMainView") as? ViewGroup ?: return@hookAfter
                val params = owner.field("wmParams") as? WindowManager.LayoutParams ?: return@hookAfter
                if (params.width <= 0) return@hookAfter
                val nativeWidth = params.width
                val wrapper = PortalSplashSizeLayout(root, nativeWidth, settings.splashSizeOffsetPercent)
                owner.setField("mMainView", wrapper)
                params.width = portalSplashDimension(nativeWidth, settings.splashSizeOffsetPercent)
            }
        }
        if (settings.customSplashIconSize && settings.splashIconSizeOffsetPercent != 0) installHook("splash-icon-size") {
            val card = classLoader.loadClass("com.miui.contentextension.text.cardview.TaplusRecognitionShrinkCard")
            val setter = card.getDeclaredMethod("setRecognitionIcon", Int::class.javaPrimitiveType)
            hookDeoptimized(setter).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:portal:splash-icon-size").intercept { chain ->
                    val result = chain.proceed()
                    protect {
                        val icon = chain.thisObject.field("mRecognitionIcon") as? ImageView
                        if (icon != null) {
                            applyPortalSplashIconSize(icon, settings.splashIconSizeOffsetPercent)
                            if (splashIcons.put(icon, true) == null) {
                                icon.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                                    protect { applyPortalSplashIconSize(view as ImageView, settings.splashIconSizeOffsetPercent) }
                                }
                            }
                        }
                    }
                    result
                }
        }
        if (adjustCards) {
            installHook("card-background") {
                // Common setter also covers resource-backed and legacy background assignments.
                hookDeoptimized(View::class.java.getDeclaredMethod("setBackgroundDrawable", Drawable::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:portal:card-background")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val view = chain.thisObject as? View
                        if (view != null && view.javaClass.name in cardNames) protect { applyCard(view, settings) }
                        result
                    }
            }
            installHook("mounted-material") {
                val base = classLoader.loadClass(floatPrefix + "BaseFloatView")
                val method = base.getDeclaredMethod("addToWindow").apply { isAccessible = true }
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:portal:mounted-material")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val owner = chain.thisObject
                        if (owner.javaClass.name in setOf(floatPrefix + "TaplusMainFloatView", floatPrefix + "TaplusSplashFloatView")) {
                            protect {
                                (owner.field("mMainView") as? View)?.let { root ->
                                    applyTree(root, settings)
                                    // addView may return before the render root finishes attaching.
                                    root.post { protect { applyTree(root, settings) } }
                                }
                            }
                        }
                        result
                    }
            }
        }
        if (material) {
            installHook("main-window") {
                hookAfter(classLoader.loadClass(floatPrefix + "TaplusMainFloatView"), "initWindow") { owner ->
                    val params = owner.field("wmParams") as WindowManager.LayoutParams
                    // The captured bitmap includes the status bar at y=0. Explicitly
                    // retaining its fit inset shifts that image below the live status bar.
                    val overlayBars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    params.setFitInsetsTypes(params.fitInsetsTypes and overlayBars.inv())
                    @Suppress("DEPRECATION")
                    params.systemUiVisibility = params.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
            }
            installHook("navigation-dim") {
                val method = classLoader.loadClass(floatPrefix + "TaplusMainFloatView")
                    .getDeclaredMethod("addNavBarDimToWindow").apply { isAccessible = true }
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:portal:navigation-dim").intercept { null }
            }
        }
        if (adjustCards || settings.removeScrim || settings.customExpandedHeight) installHook("main-content") {
            hookAfter(classLoader.loadClass(floatPrefix + "TaplusMainFloatView"), "initView") { owner ->
                val root = owner.field("mMainView") as? View
                if (settings.removeScrim) (owner.field("mMainBg") as? View)?.setBackgroundColor(Color.TRANSPARENT)
                if (settings.customExpandedHeight && root != null) applyExpandedHeight(root, settings.expandedHeightOffsetDp)
                if (adjustCards && root != null) {
                    if (material) installNavigationInsets(root)
                    applyTree(root, settings)
                }
            }
        }
        if (settings.developerMode) installHook("developer-mode") {
            val utils = classLoader.loadClass("com.miui.contentextension.utils.TaplusSettingUtils")
            val getter = utils.getDeclaredMethod(
                "getTaplusSetting", Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
            )
            hookDeoptimized(getter).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:portal:developer-mode")
                .intercept { chain ->
                    if (chain.getArg(1) == PORTAL_DEVELOPER_SETTING && preferences.getBoolean(KEY_PORTAL_DEVELOPER, false)) true
                    else chain.proceed()
                }
        }
    }

    private fun installHook(name: String, action: () -> Unit) {
        if (name in installed) return
        runCatching { action(); installed.add(name) }
            .onFailure { HookModuleLogger.log(module, Log.WARN, logTag, "Portal hook unavailable: $name", it) }
    }

    private fun protect(action: () -> Unit) {
        runCatching(action).onFailure {
            HookModuleLogger.log(module, Log.DEBUG, logTag, "Portal appearance adjustment failed", it)
        }
    }

    private fun hookAfter(type: Class<*>, name: String, action: (Any) -> Unit) {
        val method = type.getDeclaredMethod(name).apply { isAccessible = true }
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:portal:${type.simpleName}:$name")
            .intercept { chain ->
                val result = chain.proceed()
                protect { action(chain.thisObject) }
                result
            }
    }

    private fun applyAdvancedHighlight(view: View, settings: PortalAppearanceSettings) {
        if (settings.materialMode != PORTAL_MATERIAL_ADVANCED || !settings.advancedHighlight) return
        protect {
            View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java)
                .invoke(view, floatArrayOf(
                    3f, 180f, 1f, 1f, 1f, .05f, 8f, .5f, .5f, -.5f, 1f,
                    1f, 1f, .6f, .5f, .95f, -.5f, 1f, 1f, 1f, .35f,
                ))
        }
    }

    private fun Any.field(name: String): Any? {
        var type: Class<*>? = javaClass
        while (type != null) {
            val current = type
            val member = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (member != null) return member.apply { isAccessible = true }.get(this)
            type = current.superclass
        }
        return null
    }

    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        while (type != null) {
            val member = runCatching { type!!.getDeclaredField(name) }.getOrNull()
            if (member != null) {
                member.isAccessible = true
                member.set(this, value)
                return
            }
            type = type.superclass
        }
    }

    private fun View.hasResourceName(name: String): Boolean =
        id != View.NO_ID && runCatching { resources.getResourceEntryName(id) == name }.getOrDefault(false)

    private fun View.findByResourceName(name: String): View? {
        if (hasResourceName(name)) return this
        if (this is ViewGroup) for (index in 0 until childCount) {
            getChildAt(index).findByResourceName(name)?.let { return it }
        }
        return null
    }

    private fun applyTree(view: View, settings: PortalAppearanceSettings) {
        if (view.javaClass.name in cardNames) applyCard(view, settings, refresh = true)
        if (settings.materialMode != PORTAL_MATERIAL_DEFAULT && view is ImageView && view.hasResourceName("icon_close")) {
            applyCloseButton(view, settings)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) applyTree(view.getChildAt(index), settings)
    }

    private fun applyCard(view: View, settings: PortalAppearanceSettings, refresh: Boolean = false) {
        val original = view.background as? GradientDrawable ?: return
        var state = cards[view]
        if (state == null) {
            val native = original.cloneFor(view) ?: return
            state = CardState(native)
            cards[view] = state
            installMaterialLayoutListener(view, settings)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.post { if (v.isAttachedToWindow) protect { applyCard(v, settings, refresh = true) } }
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        } else if (state.output.get() === original) {
            if (!refresh) return
        } else {
            state.native = original.cloneFor(view) ?: return
        }
        val background = state.native.cloneFor(view) ?: return
        // Register after attach as pass-window blur needs a ViewRootImpl. Before attach
        // retain native opacity until a backdrop can be registered.
        val blurred = settings.materialMode != PORTAL_MATERIAL_DEFAULT && view.isAttachedToWindow && applyBlur(view, settings)
        val advanced = settings.materialMode == PORTAL_MATERIAL_ADVANCED
        val opacity = if (!advanced) { if (blurred) 0 else null }
            else if (settings.customOpacity) settings.opacityPercent else if (blurred) 70 else null
        if (opacity != null) background.alpha = (state.native.alpha * opacity / 100f).roundToInt().coerceIn(0, 255)
        // Geometry belongs to the Portal card, independently of local/global optical settings.
        if (settings.customCorner && settings.cornerOffsetDp != 0) {
            val radii = state.native.cornerRadii
            val density = view.resources.displayMetrics.density
            if (radii != null) background.cornerRadii = FloatArray(radii.size) { portalCornerRadius(radii[it], settings.cornerOffsetDp, density) }
            else background.cornerRadius = portalCornerRadius(state.native.cornerRadius, settings.cornerOffsetDp, density)
        }
        state.output = WeakReference(background)
        view.background = background
        view.clipToOutline = true
        view.invalidateOutline()
        if (blurred) applyAdvancedHighlight(view, settings)
    }

    private fun GradientDrawable.cloneFor(view: View): GradientDrawable? =
        (constantState?.newDrawable(view.resources)?.mutate() as? GradientDrawable)?.apply { callback = null }

    private fun applyCloseButton(button: ImageView, settings: PortalAppearanceSettings) {
        if (buttons.put(button, true) == null) {
            installMaterialLayoutListener(button, settings)
            val size = button.drawable?.intrinsicWidth?.takeIf { it > 0 } ?: (30 * button.resources.displayMetrics.density).roundToInt()
            button.setImageDrawable(PortalCloseIconDrawable(button.resources, size))
            button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.post { protect { applyCloseButton(v as ImageView, settings) } } }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        val blurred = button.isAttachedToWindow && applyBlur(button, settings)
        val dark = button.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val fill = if (settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS) { if (blurred) 0 else 100 }
            else if (settings.customOpacity) settings.opacityPercent else if (blurred) 70 else 100
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dark) Color.rgb(36, 36, 36) else Color.WHITE)
            alpha = fill * 255 / 100
        }
        button.clipToOutline = true
        if (blurred) applyAdvancedHighlight(button, settings)
    }

    private fun installMaterialLayoutListener(view: View, settings: PortalAppearanceSettings) {
        if (settings.materialMode != PORTAL_MATERIAL_SOFT_GLASS) return
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (v.isAttachedToWindow && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                protect {
                    if (v is ImageView) applyCloseButton(v, settings) else applyCard(v, settings, refresh = true)
                }
            }
        }
    }

    private fun applyExpandedHeight(root: View, offsetDp: Int) {
        if (offsetDp == 0) return
        val card = root.findByResourceName("recognition_card") ?: return
        val pager = card.findByResourceName("recognition_pager") ?: return
        val cardHeight = card.layoutParams.height
        val pagerHeight = pager.layoutParams.height
        if (cardHeight <= 0 || pagerHeight <= 0) return
        // The group is bottom-anchored. A negative delta lowers its top edge.
        // Keep the page and its container in sync so the bottom mode switcher fits.
        val requested = (offsetDp.coerceIn(-128, 256) * root.resources.displayMetrics.density).roundToInt()
        val delta = requested.coerceAtLeast(1 - minOf(cardHeight, pagerHeight))
        card.layoutParams = card.layoutParams.apply { height = cardHeight + delta }
        pager.layoutParams = pager.layoutParams.apply { height = pagerHeight + delta }
    }

    private fun installNavigationInsets(root: View) {
        val card = root.findByResourceName("recognition_card") ?: return
        val bottom = card.paddingBottom
        val left = card.paddingLeft
        val top = card.paddingTop
        val right = card.paddingRight
        val height = card.layoutParams.height
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            card.setPadding(left + safe.left, top, right + safe.right, bottom + safe.bottom)
            if (height > 0 && card.layoutParams.height != height + safe.bottom) {
                card.layoutParams = card.layoutParams.apply { this.height = height + safe.bottom }
            }
            insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun applyBlur(view: View, settings: PortalAppearanceSettings): Boolean = runCatching {
        val intType = Int::class.javaPrimitiveType
        // Small overlay windows must explicitly opt into sampling the window behind them.
        enablePassWindowBlur(view)
        View::class.java.getMethod("setMiBackgroundBlurMode", intType).invoke(view, 1)
        if (settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS) {
            PortalGlassMaterial.apply(view, globalGlass?.settings?.copy(enabled = true) ?: settings.glass)
            return@runCatching true
        }
        View::class.java.getMethod("setMiBackgroundBlurRadius", intType).invoke(
            view, ((if (settings.customBlur) settings.blurRadiusDp else 24) * view.resources.displayMetrics.density).roundToInt().coerceIn(0, 400),
        )
        View::class.java.getMethod("setMiViewBlurMode", intType).invoke(view, 1)
        true
    }.onFailure { error ->
        if (settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS) PortalGlassMaterial.clear(view)
        runCatching { View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 0) }
        runCatching { View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 0) }
        HookModuleLogger.log(module, Log.DEBUG, logTag, "Portal background blur API unavailable", error)
    }.getOrDefault(false)

    private fun enablePassWindowBlur(view: View) {
        val setter = runCatching {
            View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
        }.getOrNull() ?: return // Older MIUI APIs can register a backdrop without this opt-in.
        if (setter.invoke(view, true) != false) return
        // Same per-ViewRoot permission path used by the project's dock material host.
        // This only adds Portal to its own window's filter; there is no global policy hook.
        val root = View::class.java.getMethod("getViewRootImpl").invoke(view)
        if (root != null) runCatching {
            val filter = root.javaClass.getDeclaredField("mPassWindowBlurFilterData").apply { isAccessible = true }
            val previous = filter.get(root) as? String
            if (previous != null && !previous.contains(view.context.packageName)) {
                filter.set(root, "$previous,${view.context.packageName}")
            }
        }
        if (setter.invoke(view, true) != false) return
        val enabled = runCatching {
            View::class.java.getDeclaredField("mNeedPassWindowBlur").apply { isAccessible = true }.getBoolean(view)
        }.getOrDefault(false)
        check(enabled) { "Portal window rejected pass-window blur" }
    }
}
