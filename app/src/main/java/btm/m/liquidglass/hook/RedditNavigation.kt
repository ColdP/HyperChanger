package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog
import btm.m.os4.systemuihook.tr
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URI
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** 2026.44.0: retain host composition and invoke the exact callback supplied to its tab factory. */
internal object RedditNavigation {
    const val PACKAGE = "com.reddit.frontpage"
    const val ACTIVITY = "com.reddit.launch.main.MainActivity"
    private data class Spec(val outline: String, val filled: String)
    private val specs = mapOf(
        "Home" to Spec("icon_home_24", "icon_home_24_fill"),
        "Communities" to Spec("icon_communities_24", "icon_communities_24_fill"),
        "Answers" to Spec("icon_answers_24", "icon_answers_24_fill"),
        "Games" to Spec("icon_game_controller_24", "icon_game_controller_24_fill"),
        "Post" to Spec("icon_add", "icon_add_fill"),
        "UnifiedInbox" to Spec("icon_notifications_24", "icon_notifications_24_fill"),
        "Profile" to Spec("icon_profile", "icon_profile_fill"),
    )
    private data class Entry(val kind: String, val title: Int, val click: WeakReference<Any>) {
        val identity get() = "$kind:$title"
    }
    private class Menu(val screen: WeakReference<Any>, val entries: List<Entry>, val avatarFactory: WeakReference<Any>?)
    private class Building(val screen: Any) {
        val entries = mutableListOf<Entry>()
        var avatarFactory: WeakReference<Any>? = null
    }
    private val building = ThreadLocal<Building?>()
    private class ContainerScope(val bridge: Bridge, val composer: Any?) { var materialized = false }
    private val containerScope = ThreadLocal<ContainerScope?>()
    private val contentScope = ThreadLocal<ContainerScope?>()
    private val menus = WeakHashMap<Any, Menu>()
    private val bridges = WeakHashMap<Activity, Bridge>()
    private val layers = WeakHashMap<Any, WeakReference<Bridge>>()
    private val avatarExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperChanger-Reddit-avatar").apply { isDaemon = true }
    }

    private class Bridge(val host: Runtime, val screen: WeakReference<Any>) {
        var menu: Menu? = null
        var layerMounted = false
        var containerMounted = false
        val ready: Any = host.newState.invoke(null, false)!!
        val layer: Any = Proxy.newProxyInstance(host.loader, arrayOf(host.function1)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    // Reading HOST snapshot state here invalidates this layer on pause/fallback,
                    // even if no unrelated host recomposition takes place.
                    host.alpha.invoke(args!![0], if (host.getValue.invoke(ready) == true) 0f else 1f)
                    host.unit
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "HyperChanger Reddit navigation layer"
                else -> null
            }
        }
        fun active(value: Boolean) {
            if (host.getValue.invoke(ready) != value) host.setValue.invoke(ready, value)
        }
    }

    private class Runtime(val loader: ClassLoader) {
        fun type(name: String): Class<*> = Class.forName(name, false, loader)
        private val screen = type("com.reddit.launch.bottomnav.BottomNavScreen")
        private val model = type("td6")
        private val callback = type("ed6")
        private val composer = type("wdc")
        private val list = type("aaq")
        val function1 = type("kotlin.jvm.functions.Function1")
        private val function0 = type("kotlin.jvm.functions.Function0")
        private val intType = Int::class.javaPrimitiveType!!
        val convert = screen.getDeclaredMethod("v5", list)
        val menu = screen.getDeclaredMethod("G5", type("com.reddit.launch.usecase.BottomNavTabListUseCaseParams"), composer, intType)
        val factories = listOf("ht60", "lel").map {
            type(it).getDeclaredMethod("a", model, type("oc6"), callback, callback)
        }
        val render = type("com.reddit.widget.bottomnav.a").getDeclaredMethod(
            "a", list, type("pby"), Float::class.javaPrimitiveType!!, type("wmq0"), composer, intType)
        val containers = listOf("l5", "o5").map {
            screen.getDeclaredMethod(it, type("pby"), composer, intType)
        }
        val materialize = type("androidx.compose.ui.b").getDeclaredMethod("c", composer, type("pby"))
        val pageContent = type("ld6").getDeclaredMethod("a", type("siz"),
            type("androidx.compose.runtime.internal.a"), composer, intType)
        val contentOwner = type("ld6").getDeclaredField("b")
        val navigationPadding = type("gwr0").getDeclaredMethod("f0", type("pby"))
        val padding = type("bdf").getDeclaredMethod("S", type("pby"),
            Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, intType)
        val emptyModifier = type("lby").getDeclaredField("a").get(null)!!
        val then = type("pby").getMethod("x", type("pby"))
        val graphicsLayer = type("androidx.compose.ui.graphics.d").getDeclaredMethod("a", type("pby"), function1)
        val alpha = type("s0c0").getDeclaredMethod("c", Float::class.javaPrimitiveType!!)
        val newState = type("androidx.compose.runtime.g").getDeclaredMethod("j", Any::class.java)
        val getValue = type("t1i0").getMethod("getValue")
        val setValue = type("c7z").getMethod("setValue", Any::class.java)
        val unit = type("kotlin.Unit").getDeclaredField("a").get(null)
        val invoke = function0.getMethod("invoke")
        val kind = model.getDeclaredField("a")
        val title = model.getDeclaredField("b")
        private val callbackKind = callback.getDeclaredField("a")
        private val callbackScreen = callback.getDeclaredField("b")
        private val callbackModel = callback.getDeclaredField("c")
        private val activity = screen.getMethod("l3")
        private val currentScreen = screen.getMethod("getCurrentScreen")
        private val chrome = type("com.reddit.screen.BaseScreen").getMethod("Q4")
        private val chromeType = type("cw5")
        private val hidden = chromeType.getDeclaredField("b")
        private val tabState = screen.getDeclaredField("f2")
        private val selectedState = type("ty6").getDeclaredField("b")
        private val outerRouter = type(ACTIVITY).getMethod("e")
        private val routerEntries = type("com.reddit.navstack.t").getMethod("h")
        private val entryScreen = type("glc0").getMethod("a")
        private val animation = screen.getDeclaredField("o2")
        private val animationValue = type("androidx.compose.animation.core.a").getDeclaredField("c")
        val avatarFactory = type("ht60")
        private val avatarProvider = avatarFactory.getDeclaredField("a")
        private val provide = type("njs").getMethod("get")
        private val accountState = type("yio0").getDeclaredField("b")
        private val loggedIn = type("djo0")
        private val username = loggedIn.getDeclaredField("a")
        private val snoovatar = loggedIn.getDeclaredField("c")
        private val userImage = loggedIn.getDeclaredField("d")

        fun owner(screen: Any): Activity? = (activity.invoke(screen) as? Activity)?.takeIf {
            it.packageName == PACKAGE && it.javaClass.name == ACTIVITY
        }
        fun entry(screen: Any, value: Any, click: Any): Entry {
            check(model.isInstance(value) && callback.isInstance(click))
            check(callbackKind.getInt(click) == 0 && callbackScreen.get(click) === screen && callbackModel.get(click) === value)
            val name = (kind.get(value) as Enum<*>).name
            check(name in specs) { "Unknown Reddit tab $name" }
            return Entry(name, title.getInt(value), WeakReference(click))
        }
        fun visible(screen: Any): Boolean {
            val owner = owner(screen) ?: return false
            val router = outerRouter.invoke(owner) ?: return false
            val top = (routerEntries.invoke(router) as? List<*>)?.lastOrNull() ?: return false
            if (entryScreen.invoke(top) !== screen) return false
            val current = currentScreen.invoke(screen) ?: return false
            val config = chrome.invoke(current) ?: return false
            return chromeType.isInstance(config) && !hidden.getBoolean(config)
        }
        fun fraction(screen: Any): Float =
            (getValue.invoke(animationValue.get(animation.get(screen))) as? Number)?.toFloat()
                ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        fun avatar(menu: Menu): Pair<String, String>? {
            val factory = menu.avatarFactory?.get() ?: return null
            val account = getValue.invoke(accountState.get(provide.invoke(avatarProvider.get(factory))))
                ?.takeIf(loggedIn::isInstance) ?: return null
            val url = (snoovatar.get(account) as? String)?.takeIf { it.isNotBlank() }
                ?: (userImage.get(account) as? String)?.takeIf { it.isNotBlank() } ?: return null
            return "${username.get(account)}:$url" to url
        }
        fun selected(screen: Any): String? =
            (getValue.invoke(selectedState.get(tabState.get(screen))) as? Enum<*>)?.name
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        // All version-specific signatures resolve before any hook is registered.
        val host = Runtime(loader)
        var reported = false
        fun report(error: Throwable) {
            bridges.values.forEach { runCatching { it.active(false) } }
            if (!reported) HookModuleLogger.log(module, ModuleLog.WARN, "CustomNav-Reddit",
                "Keeping native navigation after a menu bridge failure", error)
            reported = true
        }
        // ld6 owns the CONTENT wrapper. Only its pre-root modifiers reserve the
        // native 50dp row/navigation inset; IME padding and child content stay intact.
        module.hook(host.pageContent).setId("reddit-navigation:content-scope")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous = contentScope.get()
                val scope = runCatching {
                    val screen = host.contentOwner.get(chain.thisObject) ?: return@runCatching null
                    val activity = host.owner(screen) ?: return@runCatching null
                    val bridge = bridges[activity]?.takeIf { it.screen.get() === screen }
                        ?: Bridge(host, WeakReference(screen)).also {
                            bridges.put(activity, it)?.active(false)
                        }
                    ContainerScope(bridge, chain.getArg(2))
                }.onFailure(::report).getOrNull()
                contentScope.set(scope)
                try { chain.proceed() } finally { contentScope.set(previous) }
            }
        module.hook(host.navigationPadding).setId("reddit-navigation:content-system-inset")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val scope = contentScope.get()?.takeUnless { it.materialized }
                val active = scope?.let { host.getValue.invoke(it.bridge.ready) == true } == true
                // f0 only appends navigationBars; c0 (IME) still executes normally.
                if (active) chain.getArg(0) else chain.proceed()
            }
        module.hook(host.padding).setId("reddit-navigation:content-row-inset")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val scope = contentScope.get()?.takeUnless { it.materialized }
                val active = scope?.let { host.getValue.invoke(it.bridge.ready) == true } == true
                if (active && chain.getArg(4) == 50f && chain.getArg(5) == 7) {
                    val args = host.padding.parameterTypes.indices.map { chain.getArg(it) }.toTypedArray()
                    args[4] = 0f
                    chain.proceed(args)
                } else chain.proceed()
            }
        // l5/o5 own the row's background and navigation-inset space. Conceal
        // their ROOT layer as well as the inner widget, preserving composition.
        host.containers.forEach { method ->
            module.hook(method).setId("reddit-navigation:container:${method.name}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val previous = containerScope.get()
                    val scope = runCatching {
                        val screen = chain.thisObject ?: return@runCatching null
                        val activity = host.owner(screen) ?: return@runCatching null
                        val bridge = bridges[activity]?.takeIf { it.screen.get() === screen }
                            ?: Bridge(host, WeakReference(screen)).also {
                                bridges.put(activity, it)?.active(false)
                            }
                        ContainerScope(bridge, chain.getArg(1))
                    }.onFailure(::report).getOrNull()
                    containerScope.set(scope)
                    try { chain.proceed() } finally { containerScope.set(previous) }
                }
        }
        module.hook(host.materialize).setId("reddit-navigation:container-layer")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                contentScope.get()?.takeIf { it.composer === chain.getArg(0) }?.materialized = true
                val scope = containerScope.get()
                if (scope == null || scope.materialized || chain.getArg(0) !== scope.composer) {
                    chain.proceed()
                } else {
                    scope.materialized = true
                    val args = runCatching {
                        val modifier = chain.getArg(1) ?: return@runCatching null
                        // Prepend so the layer encloses background/draw modifiers too.
                        val layer = host.graphicsLayer.invoke(null, host.emptyModifier, scope.bridge.layer)
                        val outer = host.then.invoke(layer, modifier)
                        arrayOf(chain.getArg(0), outer)
                    }.onFailure(::report).getOrNull()
                    val result = if (args == null) chain.proceed() else chain.proceed(args)
                    if (args != null) scope.bridge.containerMounted = true
                    result
                }
            }
        module.hook(host.convert).setId("reddit-navigation:models")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous = building.get()
                val batch = Building(chain.thisObject!!)
                building.set(batch)
                try {
                    val result = chain.proceed()
                    runCatching {
                        val input = chain.getArg(0) as List<*>
                        check(input.size in 2..8 && input.size == batch.entries.size)
                        check(batch.entries.map { it.kind }.distinct().size == input.size)
                        if (result != null) menus[result] = Menu(WeakReference(batch.screen), batch.entries.toList(), batch.avatarFactory)
                    }.onFailure(::report)
                    result
                } finally { building.set(previous) }
            }
        host.factories.forEach { method ->
            module.hook(method).setId("reddit-navigation:factory:${method.declaringClass.name}")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    runCatching {
                        building.get()?.let { batch ->
                            batch.entries.add(host.entry(batch.screen, chain.getArg(0)!!, chain.getArg(2)!!))
                            if (batch.entries.last().kind == "Profile" && host.avatarFactory.isInstance(chain.thisObject)) {
                                batch.avatarFactory = WeakReference(chain.thisObject!!)
                            }
                        }
                    }.onFailure(::report)
                    chain.proceed()
                }
        }
        module.hook(host.menu).setId("reddit-navigation:bottom-menu")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    if ((chain.getArg(0) as? Enum<*>)?.name != "BOTTOM_BAR") return@runCatching
                    val screen = chain.thisObject ?: return@runCatching
                    val activity = host.owner(screen) ?: return@runCatching
                    val bridge = bridges[activity]?.takeIf { it.screen.get() === screen }
                        ?: Bridge(host, WeakReference(screen)).also {
                            bridges.put(activity, it)?.active(false)
                        }
                    val menu = menus[result]?.takeIf { it.screen.get() === screen }
                    if (bridge.menu !== menu) {
                        bridge.active(false)
                        bridge.layerMounted = false
                        bridge.menu = menu
                        AppBottomNavHooks.onRedditNavigationChanged(activity)
                    }
                }.onFailure(::report)
                result
            }
        module.hook(host.render).setId("reddit-navigation:native-layer")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val args = runCatching {
                    val menu = menus[chain.getArg(0)] ?: return@runCatching null
                    val screen = menu.screen.get() ?: return@runCatching null
                    val activity = host.owner(screen) ?: return@runCatching null
                    val bridge = bridges[activity]?.takeIf { it.menu === menu } ?: return@runCatching null
                    val modifier = chain.getArg(1) ?: return@runCatching null
                    // Host restart callbacks retain the argument supplied to the original
                    // composable. Reuse our layer instead of appending one on every restart.
                    val augmented = if (layers[modifier]?.get() === bridge) modifier else {
                        host.graphicsLayer.invoke(null, modifier, bridge.layer)!!.also {
                            layers[it] = WeakReference(bridge)
                        }
                    }
                    bridge.layerMounted = true
                    host.render.parameterTypes.indices.map { chain.getArg(it) }.toTypedArray().also { it[1] = augmented }
                }.onFailure(::report).getOrNull()
                // Preserve the original composable and its child callbacks, including restart slots.
                if (args == null) chain.proceed() else chain.proceed(args)
            }
    }

    fun setActive(activity: Activity, active: Boolean) { bridges[activity]?.active(active) }

    data class Item(val identity: String, val label: String, val icon: ImageBitmap, val action: Boolean,
        val preserveColor: Boolean = false)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selected: Int, val fraction: Float)
    internal class Detector(private val activity: Activity) {
        private val icons = mutableMapOf<String, ImageBitmap>()
        private val main = Handler(Looper.getMainLooper())
        private var avatarKey: String? = null
        private var avatarImage: ImageBitmap? = null
        private var avatarCheckAt = -500L
        private var avatarGeneration = 0
        private val moduleContext by lazy { runCatching {
            activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() }

        fun detect(content: ViewGroup): Bar? = runCatching {
            val config = activity.resources.configuration
            if (config.orientation != Configuration.ORIENTATION_PORTRAIT || config.screenWidthDp >= 600 ||
                content.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) return null
            val bridge = bridges[activity] ?: return null
            val screen = bridge.screen.get() ?: return null
            val menu = bridge.menu ?: return null
            if (!bridge.containerMounted || !bridge.layerMounted || !bridge.host.visible(screen) ||
                menu.entries.any { it.click.get() == null }) return null
            val id = activity.resources.getIdentifier("main_activity_navhost", "id", PACKAGE)
            val container = content.findViewById<ViewGroup>(id) ?: return null
            if (container.javaClass.name != "com.reddit.screen.RedditComposeView" ||
                !container.isShown || !container.isAttachedToWindow) return null
            val selected = bridge.host.selected(screen)
            val index = menu.entries.indexOfFirst { it.kind == selected }
            if (index < 0) return null
            updateAvatar(bridge, menu)
            val items = menu.entries.map { entry ->
                val label = activity.getString(entry.title)
                val avatar = avatarImage.takeIf { entry.kind == "Profile" }
                Item(entry.identity, tr(label, label), avatar ?: icon(entry.kind, entry.kind == selected) ?: return null,
                    entry.kind == "Post", avatar != null)
            }
            Bar(container, items, index, bridge.host.fraction(screen))
        }.getOrNull()

        fun open(content: ViewGroup, identity: String): Boolean = runCatching {
            if (detect(content)?.items?.none { it.identity == identity } != false) return false
            val bridge = bridges[activity] ?: return false
            val entry = bridge.menu?.entries?.singleOrNull { it.identity == identity } ?: return false
            val click = entry.click.get() ?: return false
            bridge.host.invoke.invoke(click)
            true
        }.getOrDefault(false)

        private fun icon(kind: String, selected: Boolean): ImageBitmap? {
            val spec = specs[kind] ?: return null
            val name = if (selected) spec.filled else spec.outline
            val key = "$name:${activity.resources.configuration.uiMode}"
            icons[key]?.let { return it }
            return runCatching {
                val resource = activity.resources.getIdentifier(name, "drawable", PACKAGE)
                val native = resource.takeIf { it != 0 }?.let(activity::getDrawable)
                val context = moduleContext
                val fallback = context?.resources?.getIdentifier("reddit_nav_$name", "drawable", context.packageName)
                    ?.takeIf { it != 0 }?.let(context::getDrawable)
                val drawable = native ?: fallback ?: return null
                val size = (24 * activity.resources.displayMetrics.density).toInt().coerceIn(24, 192)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val bounds = Rect(drawable.bounds)
                try { drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(bitmap)) }
                finally { drawable.bounds = bounds }
                bitmap.asImageBitmap().also { icons[key] = it }
            }.getOrNull()
        }
        private fun updateAvatar(bridge: Bridge, menu: Menu) {
            val now = SystemClock.uptimeMillis()
            if (now - avatarCheckAt < 500L) return
            avatarCheckAt = now
            val source = runCatching { bridge.host.avatar(menu) }.getOrNull()
            if (source?.first == avatarKey) return
            avatarKey = source?.first
            avatarImage = null
            val generation = ++avatarGeneration
            if (source == null) return
            avatarExecutor.execute {
                val image = runCatching { loadAvatar(source.second) }.getOrNull()
                main.post {
                    if (generation == avatarGeneration && source.first == avatarKey && !activity.isDestroyed) {
                        val current = bridges[activity]?.let { current ->
                            current.menu?.let { runCatching { current.host.avatar(it)?.first }.getOrNull() }
                        }
                        if (current != source.first) return@post
                        avatarImage = image
                        AppBottomNavHooks.onRedditNavigationChanged(activity)
                    }
                }
            }
        }

        private fun loadAvatar(address: String): ImageBitmap? {
            val url = URI(address)
            if (url.scheme != "https" || url.host.isNullOrBlank()) return null
            val connection = url.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            // Use the exact host account URL, with no credentials or guessed endpoint.
            connection.instanceFollowRedirects = false
            val bytes = try {
                if (connection.responseCode != 200 || connection.contentLengthLong > 2_097_152) return null
                connection.inputStream.use { it.readNBytes(2_097_153) }.takeIf { it.size <= 2_097_152 } ?: return null
            } finally { connection.disconnect() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val size = (24 * activity.resources.displayMetrics.density).toInt().coerceIn(24, 192)
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val path = android.graphics.Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(path)
            val edge = minOf(decoded.width, decoded.height)
            val x = (decoded.width - edge) / 2
            val y = (decoded.height - edge) / 2
            canvas.drawBitmap(decoded, Rect(x, y, x + edge, y + edge), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            return result.asImageBitmap()
        }

        fun clear() {
            setActive(activity, false)
            icons.clear()
            avatarGeneration++
            avatarKey = null
            avatarImage = null
            avatarCheckAt = -500L
        }
    }
}
