// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.graphics.Rect
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Creates the dock material in system_server, as a child of the launcher's own surface.
 * The launcher is Flutter/AOT and has no ART classes to hook, while WindowState still owns its
 * frame and compositor parent. This deliberately only handles the background surface; launcher
 * icons and touch remain entirely untouched.
 */
internal object DockWmsHook {
    private const val LAUNCHER = "com.miui.home"
    private const val DEFAULT_HEIGHT_DP = 96f
    private const val DEFAULT_MARGIN_DP = 16f
    private const val DEFAULT_BOTTOM_DP = 12f
    private const val DEFAULT_RADIUS_DP = 28f

    private data class Layer(val parent: SurfaceControl, val tint: SurfaceControl)
    private val layers = java.util.IdentityHashMap<Any, Layer>()

    fun install(module: HyperSystemUiModule, param: PackageLoadedParam) {
        install(module, param.defaultClassLoader)
    }

    fun install(module: HyperSystemUiModule, loader: ClassLoader) {
        val windowClass = runCatching { loader.loadClass("com.android.server.wm.WindowState") }
            .getOrNull() ?: run {
                android.util.Log.e("HyperChangerDock", "WindowState class unavailable")
                return
            }
        val prepare = windowClass.declaredMethods.firstOrNull { it.name == "prepareSurfaces" && it.parameterCount == 0 }
            ?: run {
                android.util.Log.e("HyperChangerDock", "WindowState.prepareSurfaces unavailable")
                return
            }
        android.util.Log.i("HyperChangerDock", "installing WMS hook ${prepare.toGenericString()}")
        module.installHook(prepare)
            .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:desktop-dock-wms")
            .intercept { chain ->
                val result = chain.proceed()
                runCatching { update(chain.thisObject, module) }
                    .onFailure { android.util.Log.e("HyperChangerDock", "Dock update failed", it) }
                result
            }
        val remove = windowClass.declaredMethods.firstOrNull { it.name == "removeImmediately" && it.parameterCount == 0 }
        if (remove != null) {
            module.installHook(remove)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:desktop-dock-remove")
                .intercept { chain ->
                    remove(chain.thisObject)
                    chain.proceed()
                }
        }
    }

    private fun update(window: Any, module: HyperSystemUiModule) {
        val attrs = field(window, "mAttrs")?.get(window) as? WindowManager.LayoutParams ?: return
        if (!isLauncher(attrs)) { remove(window); return }
        val prefs = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!prefs.getBoolean(KEY_DOCK_ENABLED, false)) { remove(window); return }
        val parent = call(window, "getSurfaceControl") as? SurfaceControl ?: return
        if (!parent.isValid) { remove(window); return }
        val frame = call(window, "getFrame") as? Rect ?: return
        val density = (call(window, "getConfiguration") as? android.content.res.Configuration)?.densityDpi?.toFloat()?.div(160f) ?: 1f
        val margin = (DEFAULT_MARGIN_DP * density).toInt().coerceIn(0, frame.width() / 2)
        val height = (DEFAULT_HEIGHT_DP * density).toInt().coerceAtMost(frame.height())
        val bottom = (DEFAULT_BOTTOM_DP * density).toInt().coerceIn(0, frame.height())
        val width = frame.width() - margin * 2
        if (width <= 0 || height <= 0) { remove(window); return }
        val y = frame.height() - bottom - height
        val layer = layers[window]?.takeIf { it.parent == parent } ?: create(window, parent)
        val tx = SurfaceControl.Transaction()
        invoke(tx, "setLayer", layer.tint, -1)
        invoke(tx, "setPosition", layer.tint, margin.toFloat(), y.toFloat())
        invoke(tx, "setWindowCrop", layer.tint, width, height)
        invoke(tx, "setCornerRadius", layer.tint, (DEFAULT_RADIUS_DP * density).coerceAtMost(height / 2f))
        invoke(tx, "setColor", layer.tint, floatArrayOf(1f, 1f, 1f))
        invoke(tx, "setAlpha", layer.tint, 0.22f)
        invoke(tx, "show", layer.tint)
        invoke(tx, "apply")
        invoke(tx, "close")
    }

    private fun create(window: Any, parent: SurfaceControl): Layer {
        layers[window]?.let { remove(window) }
        val builder = SurfaceControl.Builder()
        invoke(builder, "setName", "HyperChanger Dock")
        invoke(builder, "setParent", parent)
        invoke(builder, "setColorLayer")
        runCatching { invoke(builder, "setCallsite", "HyperChanger") }
        val tint = invoke(builder, "build") as SurfaceControl
        return Layer(parent, tint).also { layers[window] = it }
    }

    private fun remove(window: Any) {
        layers.remove(window)?.let {
            runCatching {
                val tx = SurfaceControl.Transaction()
                invoke(tx, "remove", it.tint)
                invoke(tx, "apply")
                invoke(tx, "close")
            }
        }
    }

    private fun isLauncher(attrs: WindowManager.LayoutParams): Boolean {
        if (attrs.packageName != LAUNCHER) return false
        val title = attrs.title?.toString() ?: return false
        return title.contains("Launcher", ignoreCase = true) &&
            !title.contains("Setting", ignoreCase = true) &&
            !title.contains("Preview", ignoreCase = true)
    }

    private fun field(target: Any, name: String) = generateSequence(target.javaClass) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()
    private fun call(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
    }.getOrNull()

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: target.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("$name unavailable")
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
