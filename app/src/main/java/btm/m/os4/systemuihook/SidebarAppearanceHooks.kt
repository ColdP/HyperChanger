// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Expanded rail dimensions, fitted tool cards and adaptive all-apps panel. */
internal class SidebarAppearanceHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    private val dockHeightScope = ThreadLocal<Boolean>()
    private val parentHeightRead = ThreadLocal<Int>()
    private val measureWidth = ThreadLocal<Int>()
    private val nativeBoxRead = ThreadLocal<Boolean>()
    private val lookups = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val spanTables = Collections.synchronizedMap(WeakHashMap<Any, SidebarSpanTable>())
    private class PanelMeasureState(val availableWidth: Int, val configuration: Configuration)
    private val panelMeasureStates = Collections.synchronizedMap(WeakHashMap<View, PanelMeasureState>())

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        val settings = readSidebarAppearanceSettings(preferences)
        val spacing = readSidebarColumnSpacingSettings(preferences)
        installed = true
        if (settings.anyEnabled) {
            runCatching {
                val turbo = loader.loadClass(TURBO)
                val utils = loader.loadClass(UTILS)
                if (settings.dualRowApps || settings.widthScaleEnabled && settings.widthScale != 0f) {
                    fun adjustedWidth(original: Int, context: Context): Int {
                        val edge = context.resources.getIdentifier("dock_item_edge", "dimen", "com.miui.securitycenter")
                        val density = context.resources.displayMetrics.density
                        val icon = if (edge != 0) context.resources.getDimensionPixelSize(edge) else (48f * density).toInt()
                        return sidebarSpacedWidth(original, settings, spacing, density, icon)
                    }
                    val application = loader.loadClass("com.miui.securitycenter.Application").getDeclaredMethod("B")
                    val width = turbo.getDeclaredField("h").apply { isAccessible = true }
                    hook(turbo.getDeclaredMethod("C", Context::class.java), "initial-width") { chain ->
                        val result = chain.proceed()
                        val target = chain.thisObject
                        val original = width.getInt(target)
                        if (original > 0) width.setInt(target, adjustedWidth(original, chain.getArg(0) as Context))
                        result
                    }
                    hook(utils.getDeclaredMethod("g"), "animation-width") { chain ->
                        adjustedWidth(chain.proceed() as Int, application.invoke(null) as Context)
                    }
                }
                if (settings.heightScaleEnabled && settings.heightScale != 0f) {
                    installHeight(loader, turbo, utils, settings)
                }
            }.onFailure { log("Could not install sidebar dimensions", it) }
            if (settings.dualRowApps) runCatching { installGrid(loader) }
                .onFailure { log("Could not install sidebar two-column layout", it) }
            if (settings.dualRowApps) runCatching {
                installRailIconInsets(loader, settings, spacing, readSidebarShapeSettings(preferences))
            }
                .onFailure { log("Could not align sidebar two-column icon corners", it) }
            if (settings.iconScaleEnabled && settings.iconScale != 0f) runCatching { installIcons(loader, settings.iconScale) }
                .onFailure { log("Could not install sidebar icon scaling", it) }
        }
        runCatching { installToolboxScaling(loader, settings) }
            .onFailure { log("Could not install sidebar toolbox scaling", it) }
        runCatching { installAdaptiveLayout(loader, settings) }
            .onFailure { log("Could not install sidebar adaptive layout", it) }
        runCatching { installAdaptiveAppItems(loader) }
            .onFailure { log("Could not install sidebar adaptive app items", it) }
        runCatching { SidebarShapeHooks(module, hookDeoptimized, logTag).install(loader, preferences) }
            .onFailure { log("Could not install sidebar shape adjustments", it) }
    }

    private fun installAdaptiveAppItems(loader: ClassLoader) {
        val factory = loader.loadClass("com.miui.dock.allapps.b")
            .getDeclaredMethod("m", ViewGroup::class.java, Int::class.javaPrimitiveType)
        hook(factory, "all-apps-item") { chain ->
            val result = chain.proceed()
            runCatching {
                val root = result as? LinearLayout ?: return@runCatching result
                val layout = root.resources.getIdentifier("layout_apps_view_icon", "layout", "com.miui.securitycenter")
                if (layout == 0 || chain.getArg(1) != layout) result else SidebarAllAppsItemLayout.adapt(root)
            }.onFailure { log("Could not adapt sidebar app item", it) }.getOrDefault(result)
        }
    }

    private fun installRailIconInsets(loader: ClassLoader, settings: SidebarAppearanceSettings,
        spacing: SidebarColumnSpacingSettings, shape: SidebarShapeSettings) {
        SidebarRailInsetsHooks(module, hookDeoptimized, logTag).install(loader, settings, spacing, shape)
    }

    private fun installToolboxScaling(loader: ClassLoader, settings: SidebarAppearanceSettings) {
        val turbo = loader.loadClass(TURBO)
        val getBox = turbo.getDeclaredMethod("getBoxView")
        val getDock = turbo.getDeclaredMethod("getDockLayout")
        val getWidth = turbo.getDeclaredMethod("getBoxViewWidth")
        val getHeight = turbo.getDeclaredMethod("getBoxViewHeight")
        val target = turbo.getDeclaredMethod("getTargetBox")
        val percent = if (settings.heightScaleEnabled) settings.heightScale else 0f
        hook(target, "toolbox-container") { chain ->
            val result = chain.proceed()
            runCatching {
                val view = result as? View ?: return@runCatching result
                // Legacy standalone toolboxes keep their separate window ownership.
                if (view.parent != null || getDock.invoke(chain.thisObject) == null) return@runCatching result
                val (width, height) = inNativeBoxRead {
                    (getWidth.invoke(chain.thisObject) as Int) to (getHeight.invoke(chain.thisObject) as Int)
                }
                if (width <= 0 || height <= 0) result else SidebarToolboxScaleLayout(view, width, height, percent)
            }.onFailure { log("Could not wrap sidebar toolbox", it) }.getOrDefault(result)
        }
        listOf(getWidth, getHeight).forEach { method ->
            hook(method, "toolbox-dimension:${method.name}") { chain ->
                val original = chain.proceed()
                val box = if (nativeBoxRead.get() == true) null else getBox.invoke(chain.thisObject) as? SidebarToolboxScaleLayout
                if (box == null) original else if (method == getWidth) box.fit.width else box.fit.height
            }
        }
    }

    private fun installHeight(loader: ClassLoader, turbo: Class<*>, utils: Class<*>, settings: SidebarAppearanceSettings) {
        val percent = if (settings.heightScaleEnabled) settings.heightScale else 0f
        val nativeHeight = utils.getDeclaredMethod("c")
        if (percent != 0f) {
            hook(nativeHeight, "content-height") { chain -> sidebarScaledSize(chain.proceed() as Int, percent) }
            hook(utils.getDeclaredMethod("i"), "scoped-height-limit") { chain ->
                val original = chain.proceed() as Int
                val parentMinimum = parentHeightRead.get()
                when {
                    parentMinimum != null -> {
                        // Consume before RelativeLayout measures any video/conversation/game child.
                        parentHeightRead.remove()
                        maxOf(original, parentMinimum)
                    }
                    dockHeightScope.get() == true -> sidebarScaledSize(original, percent)
                    else -> original
                }
            }
            val animation = loader.loadClass(NEWBOX + "s0")
            // j/l each read the edit height independently. Scope only their i() reads.
            listOf(animation.getDeclaredMethod("j", Boolean::class.javaPrimitiveType),
                animation.getDeclaredMethod("l", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                turbo.getDeclaredMethod("v", Int::class.javaPrimitiveType),
            ).forEach { method ->
                hook(method, "height-scope:${method.name}") { chain -> inHeightScope { chain.proceed() } }
            }
        }
        listOf(utils.getDeclaredMethod("e"), utils.getDeclaredMethod("f", Int::class.javaPrimitiveType)).forEach { method ->
            hook(method, "layout-height:${method.name}") { chain ->
                val result = inHeightScope { chain.proceed() } as Int
                // A grid's shorter content must not shrink the existing wrap-content rail.
                if (result == ViewGroup.LayoutParams.WRAP_CONTENT) nativeHeight.invoke(null) as Int else result
            }
        }
    }

    private fun installAdaptiveLayout(loader: ClassLoader, settings: SidebarAppearanceSettings) {
        val turbo = loader.loadClass(TURBO)
        val wrapper = loader.loadClass("com.miui.dock.sidebar.p")
        val getTurbo = wrapper.getDeclaredMethod("C")
        val getDockWidth = turbo.getDeclaredMethod("getDockContainerWidth")
        val getDock = turbo.getDeclaredMethod("getDockLayout")
        val getBox = turbo.getDeclaredMethod("getBoxView")
        val getApps = turbo.getDeclaredMethod("getAppsLayout")
        val getBoxWidth = turbo.getDeclaredMethod("getBoxViewWidth")
        val getBoxHeight = turbo.getDeclaredMethod("getBoxViewHeight")
        val isLeft = turbo.getDeclaredMethod("G")
        val panel = loader.loadClass("com.miui.dock.allapps.w")
        val updatePanel = panel.getDeclaredMethod("v0").apply { isAccessible = true }
        val spec = loader.loadClass("com.miui.dock.allapps.y")
        val specWidth = spec.getDeclaredMethod("a")
        val specColumns = spec.getDeclaredMethod("b")
        val newSpec = spec.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val vertical = loader.loadClass("com.miui.gamebooster.utils.q4").getDeclaredMethod("M", Context::class.java)
        val nativeLimit = loader.loadClass(UTILS).getDeclaredMethod("i")
        val heightChanged = settings.heightScaleEnabled && settings.heightScale != 0f
        hook(loader.loadClass("com.miui.dock.allapps.x").getDeclaredMethod("c", Context::class.java, wrapper), "all-apps-spec") { chain ->
            val result = chain.proceed()
            runCatching {
                val owner = chain.getArg(1)?.let { getTurbo.invoke(it) } as? ViewGroup ?: return@runCatching result
                val context = chain.getArg(0) as Context
                val viewport = measureWidth.get()?.takeIf { it > 0 } ?: owner.width.takeIf { it > 0 }
                    ?: context.resources.displayMetrics.widthPixels
                val oppositePadding = if (isLeft.invoke(owner) == true) owner.paddingRight else owner.paddingLeft
                val gap = context.resources.getIdentifier("dp_8", "dimen", "com.miui.securitycenter")
                    .takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: return@runCatching result
                val available = viewport - (getDockWidth.invoke(owner) as Int) - oppositePadding - 2 * gap
                val originalWidth = specWidth.invoke(result) as Int
                val originalColumns = specColumns.invoke(result) as Int
                val adapted = sidebarAllAppsSpec(originalColumns, originalWidth, available)
                if (adapted.width == originalWidth && adapted.columns == originalColumns) result
                else newSpec.newInstance(adapted.columns, adapted.width)
            }.onFailure { log("Could not adapt sidebar all-apps specification", it) }.getOrDefault(result)
        }
        hook(turbo.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "adaptive-parent-measure") { chain ->
            val owner = chain.thisObject as ViewGroup
            val widthSpec = chain.getArg(0) as Int
            val heightSpec = chain.getArg(1) as Int
            val oldWidth = measureWidth.get()
            val oldParentRead = parentHeightRead.get()
            measureWidth.set(View.MeasureSpec.getSize(widthSpec))
            var adjustedHeightSpec = heightSpec
            try {
                runCatching {
                    (getApps.invoke(owner) as? View)?.let { apps ->
                        val measuredWidth = View.MeasureSpec.getSize(widthSpec).takeIf { it > 0 }
                            ?: owner.width.takeIf { it > 0 } ?: apps.width
                        val oppositePadding = if (isLeft.invoke(owner) == true) owner.paddingRight else owner.paddingLeft
                        val availableWidth = measuredWidth - (getDockWidth.invoke(owner) as Int) - oppositePadding
                        val previous = panelMeasureStates[apps]
                        val configuration = owner.resources.configuration
                        if (previous == null || previous.availableWidth != availableWidth ||
                            previous.configuration != configuration) {
                            updatePanel.invoke(apps)
                            panelMeasureStates[apps] = PanelMeasureState(availableWidth, Configuration(configuration))
                        }
                        val dock = getDock.invoke(owner) as? View
                        val params = apps.layoutParams as? RelativeLayout.LayoutParams
                        if (dock != null && dock.id > 0 && dock.parent === owner && apps.parent === owner && params != null &&
                            (params.getRule(RelativeLayout.ALIGN_TOP) != dock.id ||
                                params.getRule(RelativeLayout.ALIGN_BOTTOM) != dock.id ||
                                params.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
                                params.topMargin != 0 || params.bottomMargin != 0)) {
                            // RelativeLayout measures the rail first, then derives this card's
                            // exact bounds from it, including bottom anchoring and edit animation.
                            params.addRule(RelativeLayout.ALIGN_TOP, dock.id)
                            params.addRule(RelativeLayout.ALIGN_BOTTOM, dock.id)
                            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            params.topMargin = 0
                            params.bottomMargin = 0
                            apps.layoutParams = params
                        }
                    }
                    (getBox.invoke(owner) as? SidebarToolboxScaleLayout)?.let { box ->
                        val (width, height) = inNativeBoxRead {
                            (getBoxWidth.invoke(owner) as Int) to (getBoxHeight.invoke(owner) as Int)
                        }
                        val oppositePadding = if (isLeft.invoke(owner) == true) owner.paddingRight else owner.paddingLeft
                        val margins = box.layoutParams as? ViewGroup.MarginLayoutParams
                        val viewport = View.MeasureSpec.getSize(widthSpec).takeIf { it > 0 }
                            ?: owner.context.resources.displayMetrics.widthPixels
                        val available = viewport - (getDockWidth.invoke(owner) as Int) - oppositePadding -
                            (margins?.leftMargin ?: 0) - (margins?.rightMargin ?: 0)
                        val windowHeight = owner.rootView.height.takeIf { it > 0 }
                            ?: owner.context.resources.displayMetrics.heightPixels
                        val ownerMargins = owner.layoutParams as? ViewGroup.MarginLayoutParams
                        val maxHeight = windowHeight - (ownerMargins?.topMargin ?: 0) -
                            (ownerMargins?.bottomMargin ?: 0) - owner.paddingTop - owner.paddingBottom -
                            (margins?.topMargin ?: 0) - (margins?.bottomMargin ?: 0)
                        val dock = (getDock.invoke(owner) as? View)?.takeIf {
                            it.id > 0 && it.parent === owner && box.parent === owner
                        }
                        box.configure(width, height, available, maxHeight)
                        val params = box.layoutParams as? RelativeLayout.LayoutParams
                        if (dock != null && params != null &&
                            (params.getRule(RelativeLayout.ALIGN_TOP) != dock.id ||
                                params.getRule(RelativeLayout.ALIGN_BOTTOM) != dock.id ||
                                params.topMargin != 0 || params.bottomMargin != 0)) {
                            // Use the same final edge constraints as the all-apps card, instead
                            // of reading a sibling's provisional height during parent measurement.
                            params.addRule(RelativeLayout.ALIGN_TOP, dock.id)
                            params.addRule(RelativeLayout.ALIGN_BOTTOM, dock.id)
                            params.topMargin = 0
                            params.bottomMargin = 0
                            box.layoutParams = params
                        }
                    }
                    if (heightChanged) {
                        // Only the common container grows. Fitted cards keep their native content
                        // measurement inside their own scaled footprint and rounded outline.
                        val children = listOfNotNull(getDock.invoke(owner) as? View, getBox.invoke(owner) as? View,
                            getApps.invoke(owner) as? View)
                        val needed = children.maxOfOrNull { view ->
                            val params = view.layoutParams
                            val margins = params as? ViewGroup.MarginLayoutParams
                            val contentHeight = (view as? SidebarToolboxScaleLayout)?.fit?.height
                                ?: params?.height?.takeIf { it > 0 } ?: 0
                            contentHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
                        } ?: 0
                        val minimum = maxOf(nativeLimit.invoke(null) as Int, needed + owner.paddingTop + owner.paddingBottom)
                        if (vertical.invoke(null, owner.context) == true) parentHeightRead.set(minimum)
                        else if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED &&
                            minimum > View.MeasureSpec.getSize(heightSpec)) {
                            adjustedHeightSpec = View.MeasureSpec.makeMeasureSpec(minimum, View.MeasureSpec.getMode(heightSpec))
                        }
                        owner.clipChildren = false
                        owner.clipToPadding = false
                    }
                }.onFailure { log("Could not adapt sidebar parent bounds", it) }
                chain.proceed(arrayOf(widthSpec, adjustedHeightSpec))
            } finally {
                if (oldWidth == null) measureWidth.remove() else measureWidth.set(oldWidth)
                if (oldParentRead == null) parentHeightRead.remove() else parentHeightRead.set(oldParentRead)
            }
        }
    }

    private fun installGrid(loader: ClassLoader) {
        val dock = loader.loadClass(NEWBOX + "p0")
        val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
        val grid = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
        val defaultLookup = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager\$a")
        val layoutManager = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$n")
        val getAdapter = recycler.getMethod("getAdapter")
        val getLookup = grid.getDeclaredMethod("M")
        val getType = loader.loadClass("x7.e").getMethod("getItemViewType", Int::class.javaPrimitiveType)
        val getCount = loader.loadClass("x7.e").getMethod("getItemCount")
        fun table(adapter: Any): SidebarSpanTable = synchronized(spanTables) {
            spanTables.getOrPut(adapter) { SidebarSpanTable() }
        }.also { cache ->
            cache.prepare { IntArray(getCount.invoke(adapter) as Int) { position ->
                if (getType.invoke(adapter, position) == 2) 2 else 1
            } }
        }
        // Include single-item, range, payload and DiffUtil notifications. Invalidate before
        // dispatch: RecyclerView observers may synchronously query the new span geometry.
        loader.loadClass("androidx.recyclerview.widget.RecyclerView\$h").declaredMethods
            .filter { it.name.startsWith("notify") }.forEach { method ->
                hook(method, "invalidate-spans:${method.name}:${method.parameterCount}") { chain ->
                    spanTables[chain.thisObject]?.invalidate()
                    chain.proceed()
                }
            }
        hook(defaultLookup.getDeclaredMethod("f", Int::class.javaPrimitiveType), "divider-span") { chain ->
            val adapter = lookups[chain.thisObject]?.get()
            if (adapter == null) chain.proceed() else table(adapter).size(chain.getArg(0) as Int)
        }
        hook(defaultLookup.getDeclaredMethod("e", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "span-index") { chain ->
            val adapter = lookups[chain.thisObject]?.get()
            if (adapter == null || chain.getArg(1) != 2) chain.proceed()
            else table(adapter).index(chain.getArg(0) as Int)
        }
        hook(defaultLookup.superclass.getDeclaredMethod("d", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType), "span-group") { chain ->
            val adapter = lookups[chain.thisObject]?.get()
            if (adapter == null || chain.getArg(1) != 2) chain.proceed()
            else table(adapter).group(chain.getArg(0) as Int)
        }
        val recyclerField = dock.getDeclaredField("i").apply { isAccessible = true }
        val setManager = recycler.getMethod("setLayoutManager", layoutManager)
        val constructor = grid.getConstructor(Context::class.java, Int::class.javaPrimitiveType)
        hook(dock.getDeclaredMethod("t"), "rail-grid") { chain ->
            val result = chain.proceed()
            runCatching {
                val view = recyclerField.get(chain.thisObject) as? View ?: return@runCatching
                val adapter = getAdapter.invoke(view) ?: return@runCatching
                val manager = constructor.newInstance(view.context, 2)
                lookups[getLookup.invoke(manager)] = WeakReference(adapter)
                setManager.invoke(view, manager)
            }.onFailure { log("Could not apply sidebar grid", it) }
            result
        }
        // Native rail drag allows UP/DOWN (3). Add LEFT/RIGHT (12) only when it is enabled.
        val drag = loader.loadClass(NEWBOX + "u0")
        val holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$c0")
        hook(drag.getDeclaredMethod("k", recycler, holder), "grid-drag-directions") { chain ->
            val original = chain.proceed() as Int
            if (original == 0) original else original or 0x000c000c
        }
    }

    private fun installIcons(loader: ClassLoader, percent: Float) {
        val holder = loader.loadClass("x7.e\$f")
        val icon = holder.getDeclaredField("b").apply { isAccessible = true }
        val placeholder = holder.getDeclaredField("c").apply { isAccessible = true }
        val adapter = loader.loadClass("x7.e")
        hook(adapter.getDeclaredMethod("H", ViewGroup::class.java, Int::class.javaPrimitiveType), "new-icon") { chain ->
            val result = chain.proceed()
            runCatching {
                resizeIcon(icon.get(result) as? View, percent)
                resizeIcon(placeholder.get(result) as? View, percent)
            }.onFailure { log("Could not scale sidebar icon", it) }
            result
        }
        val dock = loader.loadClass(NEWBOX + "p0")
        val bottomIcon = dock.getDeclaredField("l").apply { isAccessible = true }
        hook(dock.getDeclaredMethod("s"), "bottom-icon") { chain ->
            val result = chain.proceed()
            resizeIcon(bottomIcon.get(chain.thisObject) as? View, percent)
            result
        }
    }

    private fun resizeIcon(view: View?, percent: Float) {
        val params = view?.layoutParams ?: return
        params.width = sidebarScaledSize(params.width, percent, minimum = 0)
        params.height = sidebarScaledSize(params.height, percent, minimum = 0)
        view.layoutParams = params
    }

    private inline fun <T> inHeightScope(block: () -> T): T {
        val previous = dockHeightScope.get()
        dockHeightScope.set(true)
        return try { block() } finally {
            if (previous == null) dockHeightScope.remove() else dockHeightScope.set(previous)
        }
    }

    private inline fun <T> inNativeBoxRead(block: () -> T): T {
        val previous = nativeBoxRead.get()
        nativeBoxRead.set(true)
        return try { block() } finally {
            if (previous == null) nativeBoxRead.remove() else nativeBoxRead.set(previous)
        }
    }

    private fun hook(method: Method, id: String, interceptor: io.github.libxposed.api.XposedInterface.Hooker) {
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("sidebar:appearance:$id").intercept(interceptor)
    }

    private fun log(message: String, error: Throwable) = HookModuleLogger.log(module, ModuleLog.WARN, logTag, message, error)

    private companion object {
        const val NEWBOX = "com.miui.gamebooster.windowmanager.newbox."
        const val TURBO = NEWBOX + "TurboLayout"
        const val UTILS = NEWBOX + "y2"
    }
}
