// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Copyright 2026 btm_m (HyperChanger adaptation)
// Adapted from HyperIsland 3.1.8 SmoothIslandHook; see assets/licenses/HyperIsland-MIT.txt.
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

/** 18.3.2.22.0 uses an inlined container provider and a separate background drawable. */
internal class IslandSmoothingHook(private val module: XposedModule) {
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val targets = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val backgrounds = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val nativeSmoothFlags = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val changingSmoothFlag = ThreadLocal<Boolean>()
    private val shape = IslandSmoothingShape()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val fillPaint = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val strokePaint = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    @Volatile private var settings = IslandSmoothingSettings()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var outlineHookInstalled = false
    private var loggedDrawFallback = false
    @Volatile private var bionicsActive: Method? = null
    private val smoothSetter = runCatching { View::class.java.getDeclaredMethod("setSmoothCornerEnabled", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }.getOrNull()
    private val smoothGetter = listOf("isSmoothCornerEnabled", "getSmoothCornerEnabled").firstNotNullOfOrNull { name ->
        runCatching { View::class.java.getDeclaredMethod(name).apply { isAccessible = true } }.getOrNull()
    }
    private val drawableStroke = runCatching { GradientDrawable::class.java.getDeclaredField("mStrokePaint").apply { isAccessible = true } }.getOrNull()

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        listOf(BACKGROUND, BASE_CONTENT).forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { onClassLoaded(it, preferences) }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in setOf(BACKGROUND, BASE_CONTENT) || !installed.add(clazz)) return
        runCatching {
            settings = readIslandSmoothingSettings(preferences)
            // Material children are often framework FrameLayouts, whose own loader
            // cannot resolve plugin classes. Resolve the native mode via the plugin.
            if (bionicsActive == null) bionicsActive = runCatching {
                clazz.classLoader?.loadClass("miui.systemui.util.MiBackgroundStyle")
                    ?.getMethod("isBionicsActive", Context::class.java)
            }.getOrNull()
            installOutlineHook()
            if (clazz.name == BACKGROUND) installDrawHook(clazz) else installMaterialTargets(clazz)
            if (listener == null) {
                val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == KEY_ISLAND_SMOOTHING_ENABLED || key == KEY_ISLAND_SMOOTHING_PERCENT) {
                        settings = readIslandSmoothingSettings(preferences)
                        shape.clear()
                        val snapshot = synchronized(targets) { targets.map(::WeakReference) }
                        val backgroundSnapshot = synchronized(backgrounds) { backgrounds.map(::WeakReference) }
                        handler.post {
                            snapshot.forEach { it.get()?.let { view ->
                                if (!settings.enabled) restoreSmoothFlag(view)
                                view.invalidateOutline()
                                view.invalidate()
                            } }
                            backgroundSnapshot.forEach { it.get()?.invalidate() }
                        }
                    }
                }
                listener = changeListener
                preferences.registerOnSharedPreferenceChangeListener(changeListener)
            }
        }.onFailure {
            installed.remove(clazz)
            module.log(Log.WARN, "HyperChanger", "Could not hook collapsed-island smoothing: ${clazz.name}", it)
        }
    }

    private fun installOutlineHook() {
        if (outlineHookInstalled) return
        module.hook(View::class.java.getDeclaredMethod("setOutlineProvider", ViewOutlineProvider::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("island-smoothing:outline-provider")
            .intercept { chain ->
                val view = chain.thisObject as View
                val provider = chain.getArg(0) as? ViewOutlineProvider
                if (provider != null && provider !is SmoothOutline && isTarget(view)) {
                    targets.add(view)
                    chain.proceed(arrayOf(SmoothOutline(provider)))
                } else chain.proceed()
            }
        smoothSetter?.let { setter ->
            module.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).setId("island-smoothing:native-flag")
                .intercept { chain ->
                    val view = chain.thisObject as View
                    if (changingSmoothFlag.get() != true && nativeSmoothFlags.containsKey(view)) {
                        nativeSmoothFlags[view] = chain.getArg(0) as Boolean
                        chain.proceed(arrayOf(false))
                    } else chain.proceed()
                }
        }
        outlineHookInstalled = true
    }

    private fun installMaterialTargets(clazz: Class<*>) {
        module.hook(clazz.getDeclaredMethod("updateBackgroundBg", View::class.java, Boolean::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("island-smoothing:material-target")
            .intercept { chain ->
                val view = chain.getArg(0) as View
                // Skip the expanded sheet: only a collapsed material child belongs here.
                val expanded = chain.thisObject.javaClass.getMethod("getExpandedView").invoke(chain.thisObject)
                if (view !== expanded) targets.add(view)
                val result = chain.proceed()
                if (view !== expanded) wrapOutline(view)
                result
            }
    }

    private fun isTarget(view: View): Boolean = view in targets ||
        generateSequence(view.javaClass as Class<*>?) { it.superclass }.any { it.name == BASE_CONTENT }

    private fun wrapOutline(view: View) {
        targets.add(view)
        val provider = view.outlineProvider ?: return
        if (provider !is SmoothOutline) view.outlineProvider = SmoothOutline(provider)
    }

    private inner class SmoothOutline(private val delegate: ViewOutlineProvider) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // Keep the OEM provider's actual* coordinate updates and RenderNode effects.
            delegate.getOutline(view, outline)
            runCatching {
                val bounds = Rect()
                if (settings.enabled && outline.getRect(bounds) &&
                    isCollapsedIslandCapsule(bounds.width().toFloat(), bounds.height().toFloat(), outline.radius)
                ) {
                    val path = Path(shape.path(bounds.width().toFloat(), bounds.height().toFloat(),
                        min(outline.radius, bounds.height() / 2f), settings.percent))
                    path.offset(bounds.exactCenterX(), bounds.exactCenterY())
                    disableSmoothFlag(view)
                    outline.setPath(path)
                } else restoreSmoothFlag(view)
            }.onFailure { restoreSmoothFlag(view) }
        }
    }

    private fun disableSmoothFlag(view: View) {
        val setter = smoothSetter ?: return
        if (nativeSmoothFlags.containsKey(view)) return
        val original = runCatching { smoothGetter?.invoke(view) as? Boolean }.getOrNull()
            ?: runCatching { bionicsActive?.invoke(null, view.context) as? Boolean }.getOrNull() ?: false
        nativeSmoothFlags[view] = original
        changingSmoothFlag.set(true)
        try {
            setter.invoke(view, false)
        } catch (_: Throwable) {
            nativeSmoothFlags.remove(view)
        } finally { changingSmoothFlag.remove() }
    }

    private fun restoreSmoothFlag(view: View) {
        val original = nativeSmoothFlags.remove(view) ?: return
        changingSmoothFlag.set(true)
        try { smoothSetter?.invoke(view, original) } catch (_: Throwable) {
        } finally { changingSmoothFlag.remove() }
    }

    private fun installDrawHook(clazz: Class<*>) {
        val methods = listOf("getDrawable", "getStokeWidth", "getActualLeft", "getActualTop", "getActualWidth", "getActualHeight")
            .associateWith { clazz.getMethod(it) }
        module.hook(clazz.getDeclaredMethod("onDraw", Canvas::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("island-smoothing:collapsed-background")
            .intercept { chain ->
                val view = chain.thisObject as ViewGroup
                backgrounds.add(view)
                for (index in 0 until view.childCount) {
                    view.getChildAt(index).takeIf(::isTarget)?.let(::wrapOutline)
                }
                if (settings.enabled && drawBackground(view, chain.getArg(0) as Canvas, methods)) null
                else chain.proceed()
            }
    }

    private fun drawBackground(view: View, canvas: Canvas, methods: Map<String, Method>): Boolean = runCatching {
        val drawable = methods.getValue("getDrawable").invoke(view) as? GradientDrawable ?: return@runCatching false
        if (drawable.shape != GradientDrawable.RECTANGLE || drawable.cornerRadii != null ||
            drawable.colors != null || drawable.colorFilter != null) return@runCatching false
        val fill = drawable.color ?: return@runCatching false
        val outset = methods.getValue("getStokeWidth").invoke(view) as Int
        val left = methods.getValue("getActualLeft").invoke(view) as Int
        val top = methods.getValue("getActualTop").invoke(view) as Int
        // actualWidth/Height are RIGHT/BOTTOM coordinates in the decompiled onDraw.
        val right = methods.getValue("getActualWidth").invoke(view) as Int
        val bottom = methods.getValue("getActualHeight").invoke(view) as Int
        val bodyRadius = min(drawable.cornerRadius, (bottom - top) / 2f)
        if (!isCollapsedIslandCapsule((right - left).toFloat(), (bottom - top).toFloat(), bodyRadius)) return@runCatching false
        val strokeField = drawableStroke ?: return@runCatching false
        val sourceStroke = strokeField.get(drawable) as? Paint
        val strokeWidth = sourceStroke?.strokeWidth?.coerceAtLeast(0f) ?: 0f
        val halfStroke = strokeWidth / 2f
        val width = right - left + 2f * outset - strokeWidth
        val height = bottom - top + 2f * outset - strokeWidth
        if (width <= 0f || height <= 0f) return@runCatching false
        val path = shape.path(width, height, min(bodyRadius + outset - halfStroke, min(width, height) / 2f), settings.percent)
        val fillColor = fill.getColorForState(drawable.state, fill.defaultColor)
        val saved = canvas.save()
        try {
            canvas.translate((left + right) / 2f, (top + bottom) / 2f)
            if (Color.alpha(fillColor) != 0) {
                val paint = checkNotNull(fillPaint.get())
                paint.color = islandSmoothingAlpha(fillColor, drawable.alpha)
                canvas.drawPath(path, paint)
            }
            if (strokeWidth > 0f && sourceStroke != null && Color.alpha(sourceStroke.color) != 0) {
                val paint = checkNotNull(strokePaint.get())
                paint.color = islandSmoothingAlpha(sourceStroke.color, drawable.alpha)
                paint.strokeWidth = strokeWidth
                canvas.drawPath(path, paint)
            }
        } finally { canvas.restoreToCount(saved) }
        true
    }.getOrElse {
        if (!loggedDrawFallback) {
            loggedDrawFallback = true
            module.log(Log.WARN, "HyperChanger", "Smooth island drawing fell back to OEM drawing", it)
        }
        false
    }

    companion object {
        private const val BACKGROUND = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"
        private const val BASE_CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    }
}
