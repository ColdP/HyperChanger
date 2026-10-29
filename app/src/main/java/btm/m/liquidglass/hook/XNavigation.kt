package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog
import btm.m.os4.systemuihook.tr
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.lang.reflect.Proxy

/** Host Compose objects stay in the host ClassLoader; only bitmaps cross into our UI. */
internal object XNavigation {
    const val PACKAGE = "com.twitter.android"
    const val ACTIVITY = "com.x.android.main.MainActivity"
    private val bridges = WeakHashMap<Activity, Bridge>()
    private val composingBottomBar = ThreadLocal<Bridge?>()
    private val insetOwners = WeakHashMap<Any, WeakReference<Bridge>>()

    private data class Spec(val icon: String, val selectedIcon: String, val title: String)
    private val specs = mapOf(
        "HOME" to Spec("ic_vector_home_stroke", "ic_vector_home", "home_title"),
        "EXPLORE" to Spec("ic_vector_search_stroke", "ic_vector_search", "tab_explore_title"),
        "GROK" to Spec("ic_vector_grok_icon", "ic_vector_grok_icon_filled", "grok_tab_name"),
        "NOTIFICATIONS" to Spec("ic_vector_notifications_stroke", "ic_vector_notifications", "settings_notifications_timeline"),
        "DM" to Spec("ic_vector_chat_stroke", "ic_vector_chat", "home_direct_messages"),
        // my.invoke uses the same communities outline in both states.
        "COMMUNITIES" to Spec("ic_vector_communities_stroke", "ic_vector_communities_stroke", "tab_communities"),
        "SPACES" to Spec("ic_vector_spaces_stroke", "ic_vector_spaces", "spaces_tab_name"),
    )

    private data class Entry(val id: Any, val kind: String, val badge: Boolean)
    private class Bridge(val host: WeakReference<ViewGroup>, val runtime: Runtime) {
        val ready = runtime.newState.invoke(null, false)!!
        val reservedSize = runtime.newState.invoke(null, 0)!!
        var mounted = false
        var sizeCallback: Any? = null
        var nativeTop: Int? = null
        var reportedTop: Int? = null
        val sizeProxy: Any = runtime.callback { value ->
            nativeTop = (value as Number).toInt()
            reportTop()
        }
        var entries = emptyList<Entry>()
        var selected: Any? = null
        var route: Any? = null
        var content: Any? = null
        var normalHidden = false
        var legacyHidden = false
        var pageHidden = false
        var hidden = false
        var valid = false
        var failed = false

        fun setReady(value: Boolean) {
            mounted = value
            if (runtime.getValue.invoke(ready) != value) runtime.setValue.invoke(ready, value)
            reportTop()
        }

        fun setSize(pixels: Int) {
            if (pixels > 0 && runtime.getValue.invoke(reservedSize) != pixels) {
                runtime.setValue.invoke(reservedSize, pixels)
                reportTop()
            }
        }

        fun reportTop() {
            val original = nativeTop ?: return
            val callback = sizeCallback ?: return
            val size = (runtime.getValue.invoke(reservedSize) as Number).toInt()
            // X reports the TOP coordinate (window height minus bar size), not its height.
            val height = host.get()?.rootView?.height ?: 0
            val top = if (mounted && valid && !hidden && height > 0 && size > 0) {
                minOf(original, (height - size).coerceAtLeast(0))
            } else original
            if (reportedTop != top) {
                reportedTop = top
                runtime.invokeRoute.invoke(callback, top)
            }
        }

        fun update(data: Any, selected: Any, route: Any) {
            val tabs = runtime.tabs.get(data) as? Map<*, *> ?: error("Missing X tab map")
            val parsed = tabs.map { (id, badge) ->
                val kind = (id as? Enum<*>)?.name ?: error("Unknown X tab id")
                check(kind in specs && badge != null) { "Unsupported X tab: $kind" }
                Entry(id, kind, runtime.badgeCount.getInt(badge) > 0 || runtime.unread.getBoolean(badge))
            }
            check(parsed.size in 2..10 && parsed.any { it.id == selected }) { "Incomplete X tab state" }
            entries = parsed
            this.selected = selected
            this.route = route
            valid = true
        }
    }

    /** Verified 12.37.2 signatures, validated together before registering any interception. */
    private class Runtime(private val loader: ClassLoader) {
        private fun type(name: String) = Class.forName(name, false, loader)
        private val main = type("com.x.main.a0")
        private val composer = type("androidx.compose.runtime.Composer")
        private val lambda = type("androidx.compose.runtime.internal.g")
        private val landing = type("com.x.main.api.landing.m")
        private val tab = type("com.x.navigation.id")
        private val upload = type("com.x.upload.z")
        private val f0 = type("kotlin.jvm.functions.Function0")
        private val f1 = type("kotlin.jvm.functions.Function1")
        private val intType = Int::class.javaPrimitiveType!!
        private val boolType = Boolean::class.javaPrimitiveType!!
        private val signature = arrayOf(intType, composer, lambda, landing, tab, upload, f0, f0, f1, f1, boolType, boolType)
        val mainMethod = main.getDeclaredMethod("g", *signature)
        val legacyMethod = main.getDeclaredMethod("p", *signature)
        val normalMethod = type("com.x.ui.common.n2").getDeclaredMethod(
            "a", lambda, type("dev.chrisbanes.haze.s0"), f1, type("androidx.compose.ui.Modifier"),
            lambda, boolType, boolType, boolType, lambda, composer, intType,
        )
        val provideInset = type("com.x.ui.common.i2").getDeclaredMethod(
            "a", type("com.x.ui.common.m2"), lambda, composer, intType,
        )
        val insetSize = type("com.x.ui.common.m2").getDeclaredMethod("getSize")
        private val localView = type("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt").getDeclaredField("f").get(null)
        private val consume = type("androidx.compose.runtime.o0").getDeclaredMethod("m", type("androidx.compose.runtime.i2"))
        val newState = type("androidx.compose.runtime.i").getDeclaredMethod("q", Any::class.java)
        val getValue = type("androidx.compose.runtime.l3").getMethod("getValue")
        val setValue = type("androidx.compose.runtime.p1").getMethod("setValue", Any::class.java)
        val invokeRoute = f1.getMethod("invoke", Any::class.java)
        val tabs = landing.getDeclaredField("d")
        val badgeCount = type("com.x.main.api.landing.z").getDeclaredField("a")
        val unread = type("com.x.main.api.landing.z").getDeclaredField("b")
        private val unit = type("kotlin.Unit").getDeclaredField("a").get(null)

        fun callback(block: (Any) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(f1)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> { block(args!![0]); unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "HyperChanger X bottom inset callback"
                else -> null
            }
        }

        fun bridge(composition: Any): Bridge? {
            val view = consume.invoke(composition, localView) as? ViewGroup ?: return null
            val activity = activity(view.context) ?: return null
            if (activity.packageName != PACKAGE || activity.javaClass.name != ACTIVITY) return null
            return synchronized(bridges) {
                bridges[activity]?.takeIf { it.host.get() === view }
                    ?: Bridge(WeakReference(view), this).also { replacement ->
                        bridges.put(activity, replacement)?.setReady(false)
                    }
            }
        }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val runtime = Runtime(loader)
        module.hook(runtime.provideInset).setId("x-navigation:inset-owner")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                composingBottomBar.get()?.let { owner ->
                    synchronized(insetOwners) { insetOwners[chain.getArg(0)] = WeakReference(owner) }
                }
                chain.proceed()
            }
        module.hook(runtime.insetSize).setId("x-navigation:reserved-size")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val original = chain.proceed() as Int
                val owner = synchronized(insetOwners) { insetOwners[chain.thisObject]?.get() }
                if (owner != null && runtime.getValue.invoke(owner.ready) == true && owner.valid && !owner.hidden) {
                    maxOf(original, (runtime.getValue.invoke(owner.reservedSize) as Number).toInt())
                } else original
            }
        fun report(bridge: Bridge?, error: Throwable) {
            bridge?.valid = false
            runCatching { bridge?.setReady(false) }
            if (bridge?.failed != true) HookModuleLogger.log(
                module, ModuleLog.WARN, "CustomNav-X", "Keeping native X navigation after bridge failure", error,
            )
            bridge?.failed = true
        }
        for (method in listOf(runtime.mainMethod, runtime.legacyMethod, runtime.normalMethod)) {
            module.hook(method)
                .setId("x-navigation:${method.declaringClass.name}:${method.name}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    var bridge: Bridge? = null
                    val args = runCatching {
                        val normal = method === runtime.normalMethod
                        val composerIndex = if (normal) 9 else 1
                        val state = runtime.bridge(chain.getArg(composerIndex)) ?: return@runCatching null
                        bridge = state
                        if (state.failed) return@runCatching null
                        if (method === runtime.mainMethod) {
                            // Keep g's own arguments intact, including its restart closure.
                            state.normalHidden = chain.getArg(10) as Boolean
                            state.legacyHidden = chain.getArg(11) as Boolean
                            state.pageHidden = state.normalHidden
                            state.hidden = state.pageHidden
                            state.content = chain.getArg(2)
                        }
                        if (!normal) {
                            state.update(chain.getArg(3), chain.getArg(4), chain.getArg(8))
                            if (method === runtime.legacyMethod) {
                                state.pageHidden = state.legacyHidden
                                state.hidden = state.pageHidden
                            }
                        } else if (!state.valid) {
                            return@runCatching null
                        } else {
                            // n2.a can remain mounted while a secondary surface replaces the
                            // main landing content. Its body lambda then differs from the one
                            // captured by a0.g. Do not read index 6 back: it may be our forced
                            // native-bar value and would create a visibility feedback loop.
                            val secondary = chain.getArg(8) !== state.content
                            state.hidden = state.pageHidden || secondary
                        }
                        val ready = runtime.getValue.invoke(state.ready) == true
                        if (method === runtime.mainMethod) return@runCatching null
                        else {
                        method.parameterTypes.indices.map { chain.getArg(it) }.toTypedArray().also { changed ->
                            val hidden = state.hidden
                            // Hide the native row only at the n2.a rendering boundary. The
                            // page state above is sampled before this override and is not read
                            // back while the custom bar is active, so the forced value cannot
                            // create a visibility feedback loop.
                            if (normal) changed[6] = hidden || (ready && state.valid)
                            else changed[10] = hidden || (ready && state.valid)
                            val callbackIndex = if (normal) 2 else 9
                            val callback = changed[callbackIndex]
                            if (callback !== state.sizeProxy && callback !== state.sizeCallback) {
                                state.sizeCallback = callback
                                state.reportedTop = null
                            }
                            changed[callbackIndex] = state.sizeProxy
                            if (normal) changed[10] = (changed[10] as Int) and 1572864.inv()
                            else changed[0] = (changed[0] as Int) and 48.inv()
                        }
                        }
                    }.onFailure { report(bridge, it) }.getOrNull()
                    // Exactly one original invocation; host failures are never retried.
                    val previous = composingBottomBar.get()
                    if (method === runtime.normalMethod && args != null) composingBottomBar.set(bridge)
                    try {
                        if (args == null) chain.proceed() else chain.proceed(args)
                    } finally {
                        composingBottomBar.set(previous)
                    }
                }
        }
        HookModuleLogger.log(module, ModuleLog.INFO, "CustomNav-X", "Compose bridge installed for X 12.37.2")
    }

    data class Item(val identity: String, val label: String, val icon: ImageBitmap, val badge: Boolean)
    data class Bar(val host: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    class Detector(private val activity: Activity) {
        private var active: Bridge? = null
        private val icons = mutableMapOf<String, ImageBitmap>()
        private val moduleContext by lazy {
            activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY)
        }

        fun detect(root: ViewGroup): Bar? {
            val bridge = synchronized(bridges) { bridges[activity] } ?: return null
            if (active !== bridge) {
                active?.setReady(false)
                active = bridge
            }
            val host = bridge.host.get() ?: return null
            if (!bridge.valid || bridge.failed || bridge.hidden || !host.isShown ||
                generateSequence(host as View?) { it.parent as? View }.none { it === root }
            ) return null
            val selected = bridge.entries.indexOfFirst { it.id == bridge.selected }
            if (selected < 0) return null
            val items = bridge.entries.mapIndexed { index, entry ->
                val spec = specs[entry.kind] ?: return null
                val resource = if (index == selected) spec.selectedIcon else spec.icon
                val icon = icon(resource) ?: return null
                val titleId = activity.resources.getIdentifier(spec.title, "string", PACKAGE)
                // Read host-localized strings, including changes supplied by updated resources.
                val label = if (titleId != 0) activity.getString(titleId) else return null
                Item("x:${entry.kind}", tr(label, label), icon, entry.badge)
            }
            return Bar(host, items, selected)
        }

        fun setActive(value: Boolean, heightPx: Int = 0) {
            active?.let { bridge ->
                if (value) bridge.setSize(heightPx)
                bridge.setReady(value)
            }
        }

        fun open(identity: String): Boolean {
            val bridge = active ?: return false
            if (!bridge.valid || bridge.hidden || bridge.failed) return false
            val entry = bridge.entries.firstOrNull { "x:${it.kind}" == identity } ?: return false
            val route = bridge.route ?: return false
            // The real callback preserves reselect-to-top, navigation and host analytics.
            return runCatching { bridge.runtime.invokeRoute.invoke(route, entry.id); true }.getOrDefault(false)
        }

        fun clear() {
            active?.setReady(false)
            active = null
            synchronized(bridges) { bridges.remove(activity) }?.setReady(false)
            icons.clear()
        }

        private fun icon(name: String): ImageBitmap? {
            icons[name]?.let { return it }
            return runCatching {
                val hostId = activity.resources.getIdentifier(name, "drawable", PACKAGE)
                val drawable = if (hostId != 0) activity.getDrawable(hostId) else {
                    val id = moduleContext.resources.getIdentifier("x_nav_${name.removePrefix("ic_vector_")}", "drawable", moduleContext.packageName)
                    if (id == 0) return null
                    moduleContext.getDrawable(id)
                } ?: return null
                val size = (24 * activity.resources.displayMetrics.density).toInt().coerceIn(24, 192)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(it))
                }.asImageBitmap().also { icons[name] = it }
            }.getOrNull()
        }
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
