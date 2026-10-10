// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Hooks the layout hand-off used by MiuiSystemUIPlugin 18.3.2.22.0. */
internal class ControlCenterHeaderHeightHook(private val module: XposedModule) {
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val headers = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        targetClasses.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let {
                onClassLoaded(it, preferences)
            }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in targetClasses || !installed.add(clazz)) return
        runCatching {
            when (clazz.name) {
                MAIN_PANEL -> {
                    val method = clazz.getDeclaredMethod("overrideHeaderLayoutParams", FrameLayout.LayoutParams::class.java)
                    module.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:header-height-layout")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val params = chain.getArg(0) as FrameLayout.LayoutParams
                            val view = getView(chain.thisObject)
                            // CombinedHeaderController creates a fresh system-height LayoutParams,
                            // passes it through this method, then applies the SAME object to the
                            // real header. MainPanelHeaderController copies it for both spacers.
                            // Never adjust an unspecified MATCH_PARENT / WRAP_CONTENT height.
                            if (view != null && params.height >= 0) {
                                params.height = (params.height + offsetPx(view, preferences)).coerceAtLeast(0)
                            }
                            result
                        }
                }
                HEADER_CONTROLLER -> {
                    val method = clazz.getDeclaredMethod("onCreate")
                    module.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:header-height-controller")
                        .intercept { chain ->
                            synchronized(headers) { headers.add(chain.thisObject) }
                            chain.proceed()
                        }
                    module.hook(clazz.getDeclaredMethod("onDestroy"))
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:header-height-controller-destroy")
                        .intercept { chain ->
                            synchronized(headers) { headers.remove(chain.thisObject) }
                            chain.proceed()
                        }
                    installPreferenceListener(preferences)
                }
                else -> {
                    // Keep the tile shrink animation and detail/brightness mirror header
                    // geometry in sync. The host's shared notification-header height stays
                    // untouched; offsetting getCombinedHeaderHeight globally affects both shades.
                    val name = if (clazz.name == ADAPTER) "getHeaderHeight" else "getHeight"
                    val method = clazz.getDeclaredMethod(name)
                    module.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:header-height:${clazz.name}")
                        .intercept { chain ->
                            val original = chain.proceed() as Float
                            val view = if (clazz.name == ADAPTER) null else getView(chain.thisObject)
                            val context = view?.context ?: clazz.getDeclaredField("context")
                                .apply { isAccessible = true }.get(chain.thisObject) as android.content.Context
                            val offset = (readControlCenterHeaderHeightOffset(preferences) * context.resources.displayMetrics.density).roundToInt()
                            if (offset == 0) original else (original + offset).coerceAtLeast(0f)
                        }
                }
            }
        }.onFailure {
            installed.remove(clazz)
            module.log(Log.WARN, "HyperChanger", "Could not hook control-center header height: ${clazz.name}", it)
        }
    }

    private fun installPreferenceListener(preferences: SharedPreferences) {
        if (preferenceListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_CONTROL_CENTER_HEADER_HEIGHT_OFFSET) {
                val snapshot = synchronized(headers) { headers.map(::WeakReference) }
                mainHandler.post {
                    snapshot.forEach { reference ->
                        reference.get()?.let { controller ->
                            // Re-enter the OEM layout callback, starting from the current
                            // system default each time. This also restores the default at 0.
                            runCatching {
                                controller.javaClass.getDeclaredMethod("updateResources")
                                    .apply { isAccessible = true }.invoke(controller)
                            }.onFailure {
                                module.log(Log.WARN, "HyperChanger", "Could not refresh control-center header height", it)
                            }
                        }
                    }
                }
            }
        }
        preferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun getView(controller: Any): View? =
        controller.javaClass.getMethod("getView").invoke(controller) as? View

    private fun offsetPx(view: View, preferences: SharedPreferences): Int =
        (readControlCenterHeaderHeightOffset(preferences) * view.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val MAIN_PANEL = "miui.systemui.controlcenter.panel.main.MainPanelController"
        private const val HEADER_CONTROLLER = "miui.systemui.controlcenter.panel.main.header.MainPanelHeaderController"
        private const val ADAPTER = "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
        private val targetClasses = setOf(
            MAIN_PANEL,
            HEADER_CONTROLLER,
            ADAPTER,
            "miui.systemui.controlcenter.panel.main.header.EmptyHeaderController",
            "miui.systemui.controlcenter.panel.main.header.EmptyHeaderMirrorController",
        )
    }
}
