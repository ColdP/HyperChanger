// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.view.View
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Personal Assistant widget surfaces only; disabled by default. */
internal class WidgetGlassHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    private val recipes = Collections.synchronizedMap(WeakHashMap<View, SoftGlassRecipeCache>())
    private var widgetTypes: List<Class<*>> = emptyList()
    private var layerOwner: Field? = null
    private var layerRoot: Method? = null
    private var rootManager: Method? = null
    private val reflectedWrite = ThreadLocal<View>()
    private var global: SoftGlassRuntimeSettings? = null
    @Volatile private var settings = WidgetGlassSettings()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        if (installed || !ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        val initial = readWidgetGlassSettings(preferences)
        if (!initial.glass.enabled) return
        widgetTypes = listOf(
            "com.miui.personalassistant.core.view.WidgetHostView",
            "com.miui.personalassistant.core.view.MaMlHostView",
            "com.miui.personalassistant.core.blur.BlurPresentDrawView",
            "com.miui.personalassistant.service.servicedelivery.ServiceDeliveryWidgetCard",
        ).mapNotNull {
            runCatching { loader.loadClass(it) }.getOrNull()
        }
        if (widgetTypes.isEmpty()) return
        installed = true
        settings = initial
        global = SoftGlassRuntimeSettings(preferences)
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(ASSISTANT_WIDGET_GLASS_PREFIX)) settings = readWidgetGlassSettings(preferences)
        }.also(preferences::registerOnSharedPreferenceChangeListener)
        installMamlWriter(loader)
        installWidgetSampling(loader)
        runCatching {
            hookDeoptimized(View::class.java.getMethod("setMiGlass", FloatArray::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE).setId("widget-glass:assistant:recipe").intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val input = chain.getArg(0) as? FloatArray ?: return@intercept chain.proceed()
                    val local = settings
                    if (reflectedWrite.get() === view || !local.glass.enabled || !isWidgetSurface(view) || !hasSoftGlassRecipe(input)) return@intercept chain.proceed()
                    chain.proceedWith(view, arrayOf(tuneRecipe(view, input, local)))
                }
        }.onFailure { log("Personal Assistant widget glass API unavailable", it) }
        runCatching {
            hookDeoptimized(View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE).setId("widget-glass:assistant:blur").intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val local = settings
                    if (reflectedWrite.get() === view || !local.glass.enabled || !isWidgetSurface(view)) return@intercept chain.proceed()
                    val glass = resolveWidgetGlass(local, global?.settings ?: SoftGlassSettings())
                    chain.proceedWith(view, arrayOf(
                        softGlassBlur(chain.getArg(0) as Int, glass.refractionBlur),
                        softGlassBlur(chain.getArg(1) as Int, glass.refractionBlur),
                    ))
                }
        }.onFailure { log("Personal Assistant widget glass blur API unavailable", it) }
    }

    /** The host normally registers this sampler only inside StackLoopView.
     * Ordinary widgets already call this helper during material refresh, but it
     * returns before enabling sampling unless the stack-only tag is present.
     * Register the same native path; retain its blur/style/device checks and its
     * MAML forwarding instead of painting another glass layer over the content.
     */
    private fun installWidgetSampling(loader: ClassLoader) {
        runCatching {
            val observer = loader.loadClass("a6.l")
            val needsBackground = observer.getMethod("needBackgroundBlur")
            val isContainer = observer.getMethod("isBlurContainer")
            val supportsBackground = observer.getMethod("supportWidgetBackgroundBlur")
            val maml = loader.loadClass("com.miui.personalassistant.core.view.MaMlHostView")
            val registeredTag = loader.loadClass("com.miui.personalassistant.R\$id")
                .getField("pa_tag_widget_container_glass_registered").getInt(null)
            val helper = listOf("o", "l").firstNotNullOfOrNull { name ->
                runCatching {
                    val type = loader.loadClass("com.miui.personalassistant.homepage.utils.$name")
                    val token = type.getDeclaredMethod("c")
                    val update = type.getDeclaredMethod("d", View::class.java)
                    check(token.returnType.name == "miuix.theme.token.a")
                    check(Modifier.isStatic(update.modifiers) && update.returnType == Void.TYPE)
                    update
                }.getOrNull()
            } ?: error("Widget GlassEffectUtil sampler signature not found")
            hookDeoptimized(helper).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("widget-glass:assistant:sampling").intercept { chain ->
                    val view = chain.getArg(0) as? View
                    if (settings.glass.enabled && view != null && widgetTypes.any { it.isInstance(view) } && observer.isInstance(view)) {
                        // MAML can own GlassConfig while supportBackgroundBlur is
                        // false. Its native helper only forwards to existing configs.
                        val eligible = runCatching {
                            needsBackground.invoke(view) == true && isContainer.invoke(view) == false &&
                                (maml.isInstance(view) || supportsBackground.invoke(view) == true)
                        }.getOrDefault(false)
                        if (eligible) view.setTag(registeredTag, true)
                    }
                    chain.proceed()
                }
        }.onFailure { log("Personal Assistant widget glass sampling unavailable", it) }
    }

    /** MAML owns its layer material independently of WidgetBackgroundBlurManager.
     * Intercept before reflection as well as at View, without modifying config fields
     * or requesting extra updates. The View hook must not tune this write again.
     */
    private fun installMamlWriter(loader: ClassLoader) {
        runCatching {
            val layer = loader.loadClass("com.miui.maml.elements.LayerScreenElement")
            val view = loader.loadClass("com.miui.maml.elements.LayerScreenElement\$LayerView")
            layerOwner = view.declaredFields.single { layer.isAssignableFrom(it.type) }.apply { isAccessible = true }
            layerRoot = layer.getMethod("getRoot")
            rootManager = loader.loadClass("com.miui.maml.ScreenElementRoot").getMethod("getViewManager")
            val method = loader.loadClass("com.miui.maml.util.ReflectionHelper").getDeclaredMethod(
                "callObjectMethod", Any::class.java, String::class.java,
                emptyArray<Class<*>>().javaClass, Array<Any>::class.java,
            )
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("widget-glass:assistant:maml-writer").intercept { chain ->
                    val target = chain.getArg(0) as? View ?: return@intercept chain.proceed()
                    val name = chain.getArg(1) as? String ?: return@intercept chain.proceed()
                    if (name != "setMiGlass" && name != "setMiGlassBlurRadius") return@intercept chain.proceed()
                    val local = settings
                    if (!local.glass.enabled || !isWidgetSurface(target)) return@intercept chain.proceed()
                    val args = chain.getArg(3) as? Array<*> ?: return@intercept chain.proceed()
                    val tuned = when (name) {
                        "setMiGlass" -> {
                            val input = args.singleOrNull() as? FloatArray ?: return@intercept chain.proceed()
                            if (!hasSoftGlassRecipe(input)) return@intercept chain.proceed()
                            arrayOf<Any>(tuneRecipe(target, input, local))
                        }
                        else -> {
                            if (args.size != 2 || args[0] !is Int || args[1] !is Int) return@intercept chain.proceed()
                            val glass = resolveWidgetGlass(local, global?.settings ?: SoftGlassSettings())
                            arrayOf<Any>(softGlassBlur(args[0] as Int, glass.refractionBlur), softGlassBlur(args[1] as Int, glass.refractionBlur))
                        }
                    }
                    val previous = reflectedWrite.get()
                    reflectedWrite.set(target)
                    try {
                        chain.proceed(arrayOf(target, name, chain.getArg(2), tuned))
                    } finally {
                        if (previous == null) reflectedWrite.remove() else reflectedWrite.set(previous)
                    }
                }
        }.onFailure { log("Personal Assistant MAML widget glass writer unavailable", it) }
    }

    private fun tuneRecipe(view: View, input: FloatArray, local: WidgetGlassSettings): FloatArray {
        val glass = resolveWidgetGlass(local, global?.settings ?: SoftGlassSettings())
        val cache = synchronized(recipes) { recipes.getOrPut(view) { SoftGlassRecipeCache() } }
        return cache.tune(input, glass)
    }

    private fun isWidgetSurface(view: View): Boolean {
        // LayerView may be detached or hosted in a separate window. Its root's
        // current manager is authoritative; do not retain a stale owner on rebind.
        val field = layerOwner
        if (field != null && field.declaringClass.isInstance(view)) {
            return runCatching {
                val layer = field.get(view)
                val root = layerRoot?.invoke(layer)
                val manager = root?.let { rootManager?.invoke(it) }
                widgetTypes.any { it.isInstance(manager) }
            }.getOrDefault(false)
        }
        var current: View? = view
        while (current != null) {
            if (widgetTypes.any { it.isInstance(current) }) return true
            current = current.parent as? View
        }
        return false
    }

    private fun log(message: String, error: Throwable) =
        HookModuleLogger.log(module, ModuleLog.WARN, logTag, message, error)
}
