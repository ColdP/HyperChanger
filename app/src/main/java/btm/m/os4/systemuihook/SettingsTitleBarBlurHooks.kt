// SPDX-License-Identifier: MIT
// Copyright (c) 2026 HyperBackground contributors
// Adapted from HyperBackground 1.4.5-beta2, DynamicActionBarHook.kt:
// https://github.com/Solomonstery/HyperBackground/tree/v1.4.5-beta2
// Modifications Copyright 2026 btm_m: Settings-only dispatch, independent opt-in
// preferences, host hook API/DexKit integration, 114% blur height and failure recovery.
// Full license: app/src/main/assets/licenses/HyperBackground-MIT.txt.
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/** Only the top ActionBarContainer in com.android.settings; no button or bottom-bar hooks. */
internal class SettingsTitleBarBlurHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private data class State(
        val blur: View,
        var original: Drawable? = null,
        var cleared: Boolean = false,
        var lastRadius: Float = -1f,
        var lastHeight: Int = -1,
        var lastAlpha: Float = 0f,
        var originalClipChildren: Boolean? = null,
        var failed: Boolean = false,
    )

    private val owned = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private var installed = false
    private lateinit var preferences: SharedPreferences
    private lateinit var maskAlpha: Field
    private lateinit var isSplit: Field
    private lateinit var getPrimary: Method
    private lateinit var setPrimary: Method
    private lateinit var setType: Method
    private lateinit var setMode: Method
    private lateinit var setViewMode: Method
    private lateinit var setGradient: Method

    fun install(param: PackageLoadedParam, prefs: SharedPreferences) {
        if (installed || param.packageName != "com.android.settings" || !readSettingsTitleBarBlurSettings(prefs).enabled) return
        val loader = param.defaultClassLoader
        val type = loader.loadClass(BAR)
        // Resolve everything before registering or modifying a view. Unknown MIUIX builds stay native.
        isSplit = type.getDeclaredField("mIsSplit").apply { isAccessible = true }
        setType = View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType)
        setMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        setViewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        setGradient = View::class.java.getMethod("setBackgroundGradientBlurParams", FloatArray::class.java, Int::class.javaPrimitiveType)
        getPrimary = type.getMethod("getPrimaryBackground")
        setPrimary = type.getMethod("setPrimaryBackground", Drawable::class.java)
        val painter = discoverMask(type, loader, param.applicationInfo.sourceDir)
        val draw = type.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }
        val inflate = type.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }
        val layout = type.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        preferences = prefs
        installed = true

        hookDeoptimized(draw).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:draw").intercept { chain ->
                val bar = chain.thisObject as? ViewGroup
                val state = bar?.let { owned[it] }
                if (bar != null && state != null) {
                    try {
                        update(bar, state)
                    } catch (error: Throwable) {
                        state.failed = true
                        restore(bar, state)
                        HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings title bar", error)
                    }
                }
                if (painter == draw && bar != null && state?.cleared == true && !state.failed) {
                    val original = maskAlpha.getFloat(bar)
                    try {
                        maskAlpha.setFloat(bar, 0f)
                        chain.proceed()
                    } finally {
                        maskAlpha.setFloat(bar, original)
                    }
                } else chain.proceed()
            }
        if (painter != draw) {
            hookDeoptimized(painter).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-titlebar:mask").intercept { chain ->
                    val state = (chain.thisObject as? ViewGroup)?.let { owned[it] }
                    if (state?.cleared == true && !state.failed) null else chain.proceed()
                }
        }
        hookDeoptimized(inflate).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:inflate").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { bar ->
                    if (!isSplit.getBoolean(bar)) ensure(bar)
                }
                result
            }
        hookDeoptimized(layout).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:layout").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { bar ->
                    owned[bar]?.blur?.layout(0, 0, bar.width, gradientHeight(bar))
                }
                result
            }
        HookModuleLogger.log(module, Log.INFO, TAG,
            "Installed Settings title bar gradient blur: mask=${painter.name}, alpha=${maskAlpha.name}")
    }

    private fun discoverMask(type: Class<*>, loader: ClassLoader, apk: String): Method {
        System.loadLibrary("dexkit")
        DexKitBridge.create(apk).use { bridge ->
            val data = requireNotNull(bridge.getClassData(type))
            val painter = data.methods.filter { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("android.graphics.Canvas") &&
                    method.invokes.any { it.className == "android.graphics.Canvas" && it.name == "drawPath" } &&
                    method.invokes.any { it.className == BAR && it.name == "getCollapsedHeight" }
            }.singleOrNull() ?: error("Settings action bar mask is ambiguous")
            val field = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" && field.writers.any { writer ->
                    writer.invokes.any { it.className == "android.animation.ValueAnimator" && it.name == "getAnimatedValue" }
                }
            }.distinctBy { it.descriptor }.singleOrNull() ?: error("Settings action bar mask alpha is ambiguous")
            maskAlpha = field.getFieldInstance(loader).apply { isAccessible = true }
            return painter.getMethodInstance(loader).apply { isAccessible = true }
        }
    }

    private fun ensure(bar: ViewGroup) {
        if (owned.containsKey(bar)) return
        val child = View(bar.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        bar.addView(child, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        owned[bar] = State(child)
    }

    private fun update(bar: ViewGroup, state: State) {
        if (state.failed) return
        if (!readSettingsTitleBarBlurSettings(preferences).enabled) {
            restore(bar, state)
            return
        }
        // Extend only the blur surface, preserving the title bar's measured height and content.
        if (state.originalClipChildren == null) state.originalClipChildren = bar.clipChildren
        bar.clipChildren = false
        if (!state.cleared) {
            state.original = getPrimary.invoke(bar) as? Drawable
            state.cleared = true
        }
        if (getPrimary.invoke(bar) != null) setPrimary.invoke(bar, null)
        // HyperBackground defaults: strength 10, opacity 100%; retain the stock mask animation.
        val radius = min(10f * bar.resources.displayMetrics.density, bar.height * .5f)
        val height = gradientHeight(bar)
        val alpha = maskAlpha.getFloat(bar).coerceIn(0f, 1f)
        if (radius <= 0f || bar.height <= 0) {
            hide(state)
            return
        }
        if (alpha <= 0f) {
            // Keep the configured render node when scrolling crosses zero; only fade the layer.
            if (state.lastAlpha != 0f) {
                state.blur.alpha = 0f
                state.lastAlpha = 0f
            }
            return
        }
        if (state.lastRadius != radius || state.lastHeight != height) {
            setMode.invoke(state.blur, 1)
            setViewMode.invoke(state.blur, 1)
            setType.invoke(state.blur, 2)
            setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, height.toFloat(), 0f), 1)
            state.lastRadius = radius
            state.lastHeight = height
        }
        if (state.lastAlpha != alpha) {
            state.blur.alpha = alpha
            state.lastAlpha = alpha
        }
        state.blur.visibility = View.VISIBLE
    }

    private fun restore(bar: ViewGroup, state: State) {
        if (state.cleared) {
            if (getPrimary.invoke(bar) == null) setPrimary.invoke(bar, state.original)
            state.cleared = false
            state.original = null
        }
        hide(state)
        state.originalClipChildren?.let {
            bar.clipChildren = it
            state.originalClipChildren = null
        }
    }

    private fun gradientHeight(bar: ViewGroup): Int = (bar.height * 1.14f).roundToInt()

    private fun hide(state: State) {
        state.blur.visibility = View.INVISIBLE
        // Also clean up partially configured layers if a vendor API throws.
        if (state.lastRadius >= 0f || state.failed) {
            runCatching { setType.invoke(state.blur, 0) }
            runCatching { setViewMode.invoke(state.blur, 0) }
            runCatching { setMode.invoke(state.blur, 0) }
        }
        state.lastRadius = -1f
        state.lastHeight = -1
        state.lastAlpha = 0f
    }

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val TAG = "SettingsTitleBarBlur"
    }
}
