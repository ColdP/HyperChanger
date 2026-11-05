// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.Outline
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Required dual-column rail geometry; preserve host children, listeners and animations. */
internal class SidebarRailInsetsHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private class Item(icon: View, placeholder: View, indicator: View) {
        val icon = WeakReference(icon)
        val placeholder = WeakReference(placeholder)
        val indicator = WeakReference(indicator)
        val badgeWidth = indicator.layoutParams.width
        val badgeHeight = indicator.layoutParams.height
    }
    private class InsetsState {
        val outline = Outline()
        var width = -1
        var shortEdge = -1
        var provider: ViewOutlineProvider? = null
        var configuration: Configuration? = null
        var fit: SidebarRailInsets? = null
    }
    private val items = Collections.synchronizedMap(WeakHashMap<FrameLayout, Item>())
    private val listInsets = Collections.synchronizedMap(WeakHashMap<View, SidebarRailInsets>())
    private val dockInsets = Collections.synchronizedMap(WeakHashMap<View, InsetsState>())

    fun install(loader: ClassLoader, settings: SidebarAppearanceSettings,
        spacing: SidebarColumnSpacingSettings, shape: SidebarShapeSettings) {
        val dockType = loader.loadClass("com.miui.gamebooster.windowmanager.newbox.p0")
        val recyclerType = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
        val gridType = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
        val gridParams = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager\$b")
        val spanIndex = gridParams.getDeclaredMethod("a")
        val position = gridParams.getMethod("getViewLayoutPosition")
        val holder = loader.loadClass("x7.e\$f")
        val iconField = holder.getDeclaredField("b").apply { isAccessible = true }
        val placeholderField = holder.getDeclaredField("c").apply { isAccessible = true }
        val indicatorField = holder.getDeclaredField("e").apply { isAccessible = true }
        val itemView = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$c0")
            .getDeclaredField("itemView").apply { isAccessible = true }

        fun dimensions(dock: View, width: Int): SidebarRailInsets {
            val state = synchronized(dockInsets) { dockInsets.getOrPut(dock) { InsetsState() } }
            val configuration = dock.resources.configuration
            // Heights above the rail width cannot change its round-rect radius. Avoid resource
            // lookups and outline allocation on every height animation frame.
            val shortEdge = dock.measuredHeight.takeIf { it > 0 }?.coerceAtMost(width) ?: width
            state.fit?.let { fit ->
                if (state.width == width && state.shortEdge == shortEdge &&
                    state.provider === dock.outlineProvider && state.configuration == configuration) return fit
            }
            val density = dock.resources.displayMetrics.density
            val iconId = dock.resources.getIdentifier("dock_item_edge", "dimen", "com.miui.securitycenter")
            val nativeIcon = if (iconId == 0) (48f * density).roundToInt()
                else dock.resources.getDimensionPixelSize(iconId)
            val requested = sidebarScaledSize(nativeIcon,
                if (settings.iconScaleEnabled) settings.iconScale else 0f, minimum = 0)
            val radiusId = dock.resources.getIdentifier("global_dock_bg_radius", "dimen", "com.miui.securitycenter")
            val nativeRadius = if (radiusId == 0) 24f * density else dock.resources.getDimension(radiusId)
            val fallback = sidebarAdjustedRadius(nativeRadius, if (shape.radiusEnabled) shape.radius else 0f, density)
            val outline = state.outline
            outline.setEmpty()
            val radius = runCatching {
                dock.outlineProvider?.getOutline(dock, outline)
                if (outline.isEmpty) fallback else outline.radius.takeIf { it >= 0f } ?: fallback
            }.getOrDefault(fallback)
            return sidebarRailInsets(width, sidebarCardRadius(radius, width, dock.measuredHeight), requested, density, spacing)
                .also {
                    state.width = width
                    state.shortEdge = shortEdge
                    state.provider = dock.outlineProvider
                    state.configuration = Configuration(configuration)
                    state.fit = it
                }
        }

        hook(loader.loadClass("x7.e").getDeclaredMethod("H", ViewGroup::class.java,
            Int::class.javaPrimitiveType), "register-item") { chain ->
            val result = chain.proceed()
            runCatching {
                val root = itemView.get(result) as? FrameLayout ?: return@runCatching
                val icon = iconField.get(result) as? View ?: return@runCatching
                val placeholder = placeholderField.get(result) as? View ?: return@runCatching
                val indicator = indicatorField.get(result) as? View ?: return@runCatching
                if (listOf(icon, placeholder, indicator).all {
                    it.parent === root && it.layoutParams is FrameLayout.LayoutParams
                }) items[root] = Item(icon, placeholder, indicator)
            }.onFailure { log("Could not register rail icon geometry", it) }
            result
        }

        // Only RecyclerViews directly owned by the rail enter the padding path. Do not hook
        // framework FrameLayout.onMeasure, which also runs for every unrelated tool-card view.
        hook(recyclerType.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType), "list-measure") { chain ->
            val list = chain.thisObject as View
            val dock = list.parent as? View
            if (dock?.javaClass != dockType) return@hook chain.proceed()
            runCatching {
                    val width = View.MeasureSpec.getSize(chain.getArg(0) as Int).takeIf { it > 0 }
                        ?: list.measuredWidth
                    if (width > 0) {
                        val fit = dimensions(dock, width)
                        listInsets[list] = fit
                        if (list.paddingLeft != fit.edge || list.paddingRight != fit.edge || list.paddingTop != fit.edge)
                            list.setPadding(fit.edge, fit.edge, fit.edge, list.paddingBottom)
                    }
            }.onFailure { log("Could not measure rail padding", it) }
            chain.proceed()
        }

        // Run before GridLayoutManager's measurement-cache check so changed child geometry
        // can force the real item to measure. Preserve the host's native child and animations.
        hook(gridType.getDeclaredMethod("P", View::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), "item-measure") { chain ->
            val root = chain.getArg(0) as? FrameLayout ?: return@hook chain.proceed()
            runCatching {
                    val item = items[root] ?: return@runCatching
                    val list = root.parent as? ViewGroup ?: return@runCatching
                    val dock = list.parent as? View ?: return@runCatching
                    if (dock.javaClass != dockType || !gridParams.isInstance(root.layoutParams)) return@runCatching
                    val fit = listInsets[list] ?: dimensions(dock,
                        list.measuredWidth.takeIf { it > 0 } ?: dock.measuredWidth)
                    val params = root.layoutParams
                    val span = spanIndex.invoke(params) as Int
                    val adapterPosition = position.invoke(params) as Int
                    if (span !in 0..1 || adapterPosition < 0) return@runCatching
                    val leftColumn = (span == 0) != (list.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    val cellWidth = View.MeasureSpec.getSize(chain.getArg(1) as Int)
                    val size = fit.icon.coerceAtMost(cellWidth.coerceAtLeast(0))
                    val top = if (adapterPosition < 2) 0 else (8f * root.resources.displayMetrics.density).roundToInt()
                    val alignment = Gravity.TOP or if (leftColumn) Gravity.LEFT else Gravity.RIGHT
                    var changed = false
                    fun alignIcon(view: View?) {
                        val params = view?.layoutParams as? FrameLayout.LayoutParams ?: return
                        if (params.width == size && params.height == size && params.gravity == alignment &&
                            params.topMargin == top && params.leftMargin == 0 && params.rightMargin == 0 &&
                            params.marginStart == 0 && params.marginEnd == 0 && params.bottomMargin == 0) return
                        params.apply {
                            width = size; height = size; gravity = alignment
                            topMargin = top; leftMargin = 0; rightMargin = 0
                            marginStart = 0; marginEnd = 0; bottomMargin = 0
                        }
                        view.forceLayout()
                        changed = true
                    }
                    alignIcon(item.icon.get())
                    alignIcon(item.placeholder.get())
                    // Keep the edit indicator fully inside its icon, preserving its real
                    // view and Folme scale/alpha state instead of transforming the root.
                    item.indicator.get()?.let { badge ->
                        val badgeWidth = item.badgeWidth.coerceIn(0, size)
                        val badgeHeight = item.badgeHeight.coerceIn(0, size)
                        val badgeLeft = (if (leftColumn) 0 else cellWidth - size) + size - badgeWidth
                        val params = badge.layoutParams as FrameLayout.LayoutParams
                        if (params.width != badgeWidth || params.height != badgeHeight ||
                            params.gravity != (Gravity.TOP or Gravity.LEFT) || params.leftMargin != badgeLeft ||
                            params.topMargin != top || params.rightMargin != 0 || params.bottomMargin != 0) {
                        params.apply {
                            width = badgeWidth; height = item.badgeHeight.coerceIn(0, size)
                            gravity = Gravity.TOP or Gravity.LEFT
                            marginStart = Int.MIN_VALUE; marginEnd = Int.MIN_VALUE
                            leftMargin = badgeLeft
                            rightMargin = 0; topMargin = top; bottomMargin = 0
                        }
                        badge.forceLayout()
                        changed = true
                        }
                    }
                    if (changed) root.forceLayout()
            }.onFailure { log("Could not measure aligned rail icons", it) }
            chain.proceed()
        }
    }

    private fun hook(method: Method, id: String, interceptor: io.github.libxposed.api.XposedInterface.Hooker) {
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("sidebar:rail-insets:$id").intercept(interceptor)
    }
    private fun log(message: String, error: Throwable) =
        HookModuleLogger.log(module, ModuleLog.WARN, logTag, message, error)
}
