package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog
import btm.m.os4.systemuihook.R
import btm.m.os4.systemuihook.tr
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** GitHub 1.282.0: observe the host menu without replacing its Compose runtime. */
internal object GitHubNavigation {
    const val PACKAGE = "com.github.android"
    const val ACTIVITY = "com.github.android.main.MainActivity"
    private val snapshots = WeakHashMap<Activity, Snapshot>()
    private data class InsetState(val active: Boolean, val heightPx: Int)
    private val insetStates = WeakHashMap<Activity, InsetState>()
    private val lastAppliedInsets = WeakHashMap<View, Int>()
    private val lastBaseInsets = WeakHashMap<View, Int>()
    private var insetHookInstalled = false
    private var runtime: Runtime? = null

    private data class Snapshot(val entries: List<Any>, val click: WeakReference<Any>)
    data class Item(val identity: String, val label: String, val icon: ImageBitmap)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    // These are leaf destinations registered by MainActivity.onCreate, not parent graphs.
    private val rootRoutes = setOf(
        "com.github.android.home.navigation.HomeScreenRoute",
        "com.github.android.navigation.NotificationsScreenRoute",
        "com.github.android.feed.navigation.ExploreScreenRoute",
        "com.github.android.agents.copilothome.navigation.CopilotHomeNavRoute",
        "com.github.android.agents.copilothome.navigation.CopilotAHPHomeNavRoute",
    )

    private class Runtime(loader: ClassLoader) {
        private val classLoader = loader
        private fun type(name: String) = Class.forName(name, false, classLoader)
        private val main = type(ACTIVITY)
        private val tab = type("f4m")
        private val callback = type("n3m")
        private val function = type("i2i")
        val menu = type("n5a").getDeclaredMethod(
            "q", Int::class.javaPrimitiveType!!, type("nea"), function, function,
            function, function, type("j4i"), type("v4o"), List::class.java,
        )
        private val callbackKind = callback.getDeclaredField("q").apply { isAccessible = true }
        private val callbackActivity = callback.getDeclaredField("r").apply { isAccessible = true }
        private val callbackDestination = callback.getDeclaredField("s").apply { isAccessible = true }
        val invoke = function.getMethod("i", Any::class.java)
        val title = tab.getDeclaredField("a").apply { isAccessible = true }
        val normalIcon = tab.getDeclaredField("b").apply { isAccessible = true }
        val selectedIcon = tab.getDeclaredField("c").apply { isAccessible = true }
        private val navController = main.getDeclaredField("C0").apply { isAccessible = true }
        private val controllerState = type("feo").getDeclaredField("b").apply { isAccessible = true }
        private val backStack = type("odo").getDeclaredField("f").apply { isAccessible = true }
        private val entryDestination = type("gdo").getDeclaredField("r").apply { isAccessible = true }
        private val destinationInfo = type("ydo").getDeclaredField("r").apply { isAccessible = true }
        private val destinationDefinition = type("hc5").getDeclaredField("d").apply { isAccessible = true }
        private val destinationKey = type("ydo").getDeclaredField("q").apply { isAccessible = true }
        private val destinationRoute = type("hc5").getDeclaredField("c").apply { isAccessible = true }
        private val isSelected = main.getDeclaredMethod("r0", tab, type("ydo")).apply { isAccessible = true }

        fun owner(click: Any): Activity? = if (callback.isInstance(click) && callbackKind.getInt(click) == 0) {
            callbackActivity.get(click) as? Activity
        } else null

        fun entries(value: Any): List<Any> {
            val entries = (value as List<*>).filterNotNull()
            check(entries.size in 2..8 && entries.all(tab::isInstance)) { "Incomplete GitHub menu" }
            check(entries.map { title.getInt(it) }.distinct().size == entries.size) { "Duplicate GitHub tabs" }
            return entries
        }

        fun currentDestination(activity: Activity): Any? {
            val controller = navController.get(activity) ?: return null
            val state = controllerState.get(controller) ?: return null
            val entries = backStack.get(state) as? List<*> ?: return null
            return entries.lastOrNull()?.let(entryDestination::get)
        }

        fun rootRoute(destination: Any): Boolean {
            val info = destinationInfo.get(destination)
            val definition = destinationDefinition.get(info)
            val key = definition?.let { destinationKey.get(it) as? String }
            if (key in rootRoutes) return true
            val route = destinationRoute.get(info) as? String ?: return false
            val normalized = route.substringBefore('?').substringBefore('#')
            return normalized in rootRoutes
        }

        fun currentClick(snapshot: Snapshot, destination: Any): Any? = snapshot.click.get()?.takeIf {
            callbackDestination.get(it) === destination
        }

        fun selected(entry: Any, destination: Any) = isSelected.invoke(null, entry, destination) == true
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        // Resolve every version-specific member before installing the observation hook.
        val host = Runtime(loader)
        runtime = host
        installInsetBridge(module)
        var failureLogged = false
        module.hook(host.menu).setId("github-navigation:menu")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                runCatching {
                    val click = chain.getArg(3) as Any
                    val activity = host.owner(click) ?: return@runCatching
                    val entries = host.entries(chain.getArg(8))
                    val changed = synchronized(snapshots) {
                        val previous = snapshots.put(activity, Snapshot(entries, WeakReference(click)))
                        previous?.click?.get() !== click || previous.entries != entries
                    }
                    if (changed) AppBottomNavHooks.onGitHubNavigationChanged(activity)
                }.onFailure { error ->
                    if (!failureLogged) HookModuleLogger.log(
                        module, ModuleLog.WARN, "CustomNav-GitHub", "Unable to read GitHub menu; preserving native navigation", error,
                    )
                    failureLogged = true
                }
                // Never skip, modify or retry the original composable.
                chain.proceed()
            }
    }

    fun setActive(activity: Activity, active: Boolean, heightPx: Int = 0) {
        synchronized(insetStates) {
            if (active) insetStates[activity] = InsetState(true, heightPx.coerceAtLeast(0))
            else insetStates[activity] = InsetState(false, 0)
        }
        activity.findViewById<View>(android.R.id.content)?.requestApplyInsets()
    }

    private fun installInsetBridge(module: XposedModule) {
        if (insetHookInstalled || Build.VERSION.SDK_INT < 30) return
        val method = View::class.java.getDeclaredMethod("dispatchApplyWindowInsets", WindowInsets::class.java)
        module.hook(method)
            .setId("github-navigation:content-inset")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as? View
                val original = chain.getArg(0) as? WindowInsets
                val activity = view?.let(::activityFor)
                val state = activity?.let { synchronized(insetStates) { insetStates[it] } }
                if (view == null || original == null || activity == null || state == null || !isBusinessContent(view, activity)) {
                    return@intercept chain.proceed()
                }
                val nav = original.getInsets(WindowInsets.Type.navigationBars())
                val previous = synchronized(lastAppliedInsets) { lastAppliedInsets[view] }
                val baseBottom = if (previous != null && nav.bottom == previous) {
                    synchronized(lastBaseInsets) { lastBaseInsets[view] ?: nav.bottom }
                } else nav.bottom
                val targetBottom = if (state.active) maxOf(baseBottom, state.heightPx) else baseBottom
                val adjusted = if (targetBottom == nav.bottom) {
                    original
                } else {
                    WindowInsets.Builder(original)
                        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, targetBottom))
                        .build()
                }
                synchronized(lastAppliedInsets) {
                    if (state.active && targetBottom != nav.bottom) {
                        lastAppliedInsets[view] = targetBottom
                        lastBaseInsets[view] = baseBottom
                    } else {
                        lastAppliedInsets.remove(view)
                        lastBaseInsets.remove(view)
                    }
                }
                chain.proceed(arrayOf(adjusted))
            }
        insetHookInstalled = true
    }

    private fun isBusinessContent(view: View, activity: Activity): Boolean {
        if (view.id != View.NO_ID) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name == "navigation_bar") return false
        }
        val content = activity.findViewById<View>(android.R.id.content) ?: return false
        if (!isDescendantOf(view, content)) return false
        val navHostId = activity.resources.getIdentifier("nav_host_fragment", "id", PACKAGE)
        val navHost = if (navHostId == 0) null else content.findViewById<View>(navHostId)
        return navHost != null && (view === navHost || isDescendantOf(view, navHost))
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun activityFor(view: View): Activity? {
        var context = view.context
        repeat(16) {
            if (context is Activity) return context
            val next = (context as? android.content.ContextWrapper)?.baseContext ?: return null
            if (next === context) return null
            context = next
        }
        return null
    }

    internal class Detector(private val activity: Activity) {
        private val icons = mutableMapOf<Int, ImageBitmap>()
        private val moduleContext by lazy {
            runCatching { activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY) }.getOrNull()
        }

        fun detect(content: ViewGroup): Bar? = runCatching {
            if (activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return null
            val host = runtime ?: return null
            val snapshot = synchronized(snapshots) { snapshots[activity] } ?: return null
            val destination = host.currentDestination(activity) ?: return null
            if (!host.rootRoute(destination) || host.currentClick(snapshot, destination) == null) return null
            val id = activity.resources.getIdentifier("navigation_bar", "id", PACKAGE)
            val bar = content.findViewById<ViewGroup>(id) ?: return null
            val parent = bar.parent as? LinearLayout ?: return null
            if (parent.orientation != LinearLayout.VERTICAL || !bar.isShown || !bar.isAttachedToWindow ||
                bar.javaClass.name != "androidx.compose.ui.platform.ComposeView"
            ) return null
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                content.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            ) return null
            val selected = snapshot.entries.indexOfFirst { host.selected(it, destination) }
            if (selected < 0) return null
            val items = snapshot.entries.mapIndexed { index, entry ->
                val label = activity.getString(host.title.getInt(entry))
                val resource = (if (index == selected) host.selectedIcon else host.normalIcon).getInt(entry)
                Item("$index:${host.title.getInt(entry)}:${entry.javaClass.name}", tr(label, label), icon(resource) ?: return null)
            }
            Bar(bar, items, selected)
        }.getOrNull()

        fun open(identity: String): Boolean = runCatching {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
            if (detect(content)?.items?.none { it.identity == identity } != false) return false
            val host = runtime ?: return false
            val snapshot = synchronized(snapshots) { snapshots[activity] } ?: return false
            val destination = host.currentDestination(activity) ?: return false
            val click = host.currentClick(snapshot, destination) ?: return false
            val index = identity.substringBefore(':').toIntOrNull() ?: return false
            val entry = snapshot.entries.getOrNull(index) ?: return false
            host.invoke.invoke(click, entry)
            true
        }.getOrDefault(false)

        private fun icon(resource: Int): ImageBitmap? {
            if (resource == 0) return null
            icons[resource]?.let { return it }
            val entryName = runCatching { activity.resources.getResourceEntryName(resource) }.getOrNull()
            val extracted = when (entryName) {
                "ic_home_24" -> R.drawable.github_ic_home_24
                "ic_home_fill_24" -> R.drawable.github_ic_home_fill_24
                "ic_inbox_24" -> R.drawable.github_ic_inbox_24
                "ic_inbox_fill_24" -> R.drawable.github_ic_inbox_fill_24
                "ic_telescope_24" -> R.drawable.github_ic_telescope_24
                "ic_telescope_fill_24" -> R.drawable.github_ic_telescope_fill_24
                "ic_copilot_24" -> R.drawable.github_ic_copilot_24
                else -> null
            }
            val drawable = extracted?.let { moduleContext?.getDrawable(it) }
                ?: activity.getDrawable(resource) ?: return null
            val size = (24 * activity.resources.displayMetrics.density).toInt().coerceIn(24, 192)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            return bitmap.asImageBitmap().also { icons[resource] = it }
        }

        fun clear() {
            icons.clear()
            synchronized(snapshots) { snapshots.remove(activity) }
        }
    }
}
