// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap

/** SecurityCenter 13.6.3 :ui surfaces. No App Vault, icon, or child-content hooks. */
internal class SidebarHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    private var dockType: Class<*>? = null
    private val cards = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val recipes = Collections.synchronizedMap(WeakHashMap<View, SoftGlassRecipeCache>())
    @Volatile private var settings = SidebarSettings()
    private var global: SoftGlassRuntimeSettings? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        if (installed || !ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        val initial = readSidebarSettings(preferences)
        if (!initial.glass.enabled && !initial.unifyCards) return
        installed = true
        settings = initial
        global = SoftGlassRuntimeSettings(preferences)
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(SIDEBAR_PREFIX)) settings = readSidebarSettings(preferences)
        }.also(preferences::registerOnSharedPreferenceChangeListener)
        runCatching {
            // Return-type discovery follows the reference and avoids hardcoding p0.
            dockType = loader.loadClass("com.miui.gamebooster.windowmanager.newbox.TurboLayout")
                .getDeclaredMethod("getDockLayout").returnType
        }.onFailure { log("Could not resolve sidebar DockLayout", it) }
        installOpticalHooks()

        // These adapters own the exact material roots, including plain FrameLayouts.
        // Other users of the common blur helper (toast/game panels) remain untouched.
        installToolCard(loader, "gb.p", "t", "b") // VideoBoxViewAdapter
        installToolCard(loader, "s8.i", "E", "d") // ConversationViewAdapter
        runCatching {
            val method = loader.loadClass("com.miui.dock.allapps.f0").getDeclaredMethod(
                "c", ViewGroup::class.java, Context::class.java,
            )
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("sidebar:all-apps-material").intercept { chain ->
                    val view = chain.getArg(0) as? View
                    if (view != null) cards[view] = true
                    val result = chain.proceed()
                    if (view != null && settings.unifyCards) applyNativeGlass(view, loader)
                    result
                }
        }.onFailure { log("Could not install sidebar all-apps material", it) }
    }

    private fun isSurface(view: View): Boolean = dockType?.isInstance(view) == true || cards.containsKey(view)

    private fun installOpticalHooks() {
        runCatching {
            val method = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("sidebar:glass-parameters").intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val input = chain.getArg(0) as? FloatArray ?: return@intercept chain.proceed()
                    val local = settings
                    if (!local.glass.enabled || !isSurface(view) || !hasSoftGlassRecipe(input)) return@intercept chain.proceed()
                    val glass = resolveSidebarGlass(local, global?.settings ?: SoftGlassSettings())
                    val cache = synchronized(recipes) { recipes.getOrPut(view) { SoftGlassRecipeCache() } }
                    chain.proceedWith(view, arrayOf(cache.tune(input, glass)))
                }
        }.onFailure { log("Sidebar setMiGlass unavailable", it) }
        runCatching {
            val method = View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("sidebar:glass-blur").intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val local = settings
                    if (!local.glass.enabled || !isSurface(view)) return@intercept chain.proceed()
                    val glass = resolveSidebarGlass(local, global?.settings ?: SoftGlassSettings())
                    chain.proceedWith(view, arrayOf(
                        softGlassBlur(chain.getArg(0) as Int, glass.refractionBlur),
                        softGlassBlur(chain.getArg(1) as Int, glass.refractionBlur),
                    ))
                }
        }.onFailure { log("Sidebar glass blur API unavailable", it) }
    }

    private fun installToolCard(loader: ClassLoader, name: String, methodName: String, fieldName: String) {
        runCatching {
            val type = loader.loadClass(name)
            val field = type.getDeclaredField(fieldName).apply { isAccessible = true }
            val method = type.getDeclaredMethod(methodName)
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("sidebar:tool-card:$name").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val view = field.get(chain.thisObject) as? View
                        if (view != null) {
                            cards[view] = true
                            if (settings.unifyCards) applyNativeGlass(view, loader)
                        }
                    }.onFailure { log("Could not apply sidebar card material: $name", it) }
                    result
                }
        }.onFailure { log("Could not install sidebar card material: $name", it) }
    }

    /** Reuse the host's exact Dock/AllApps token and material adapter, including blur radii.
     * Resolve the complete recipe and APIs before changing the view. Never invent a fallback.
     */
    private fun applyNativeGlass(view: View, loader: ClassLoader) {
        runCatching {
            val token = loader.loadClass("com.miui.gamebooster.utils.q").getField("a").get(null) ?: return
            val builderType = loader.loadClass("miuix.theme.token.MaterialToken\$b")
            val builder = builderType.getConstructor(Int::class.javaPrimitiveType).newInstance(40)
            builderType.getDeclaredMethod("i", token.javaClass).invoke(builder, token)
            val material = builderType.getDeclaredMethod("a").invoke(builder) ?: return
            val adapterType = loader.loadClass("vq.j")
            val adapter = adapterType.getDeclaredMethod("a", Parcelable::class.java).invoke(null, material) ?: return
            val glass = adapterType.getDeclaredMethod("e").invoke(adapter) ?: return
            val recipe = glass.javaClass.getField("a").get(glass) as? FloatArray ?: return
            if (!hasSoftGlassRecipe(recipe)) return
            val apply = loader.loadClass("vq.g").getDeclaredMethod("c", View::class.java, glass.javaClass)
            val clearBlend = View::class.java.getMethod("clearMiBackgroundBlendColor")
            // Do not change background blur/scale/opacity or any View alpha/geometry.
            apply.invoke(null, view, glass)
            clearBlend.invoke(view)
            val transparent = view.resources.getIdentifier("transparent", "color", "com.miui.securitycenter")
            if (transparent != 0) view.setBackgroundResource(transparent)
        }.onFailure { log("Could not apply native sidebar glass", it) }
    }

    private fun log(message: String, error: Throwable) =
        HookModuleLogger.log(module, ModuleLog.WARN, logTag, message, error)
}
