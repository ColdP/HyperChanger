package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap
import kotlin.math.abs

/** Session-owned correction for the downloaded order bundle's native-tab reservation. */
internal class MeituanTakeoutOrderLayout {
    private data class Height(val component: Any, val node: Any, val original: Float, var applied: Float)
    private data class Edge(val component: Any, val node: Any, val edge: Any,
                            val getter: String, val setter: String, val original: Float)
    private val heights = IdentityHashMap<View, Height>()
    private val edges = ArrayList<Edge>()
    private var activeContainer: View? = null

    fun maintain(page: ViewGroup, barHeight: Int) {
        val tolerance = maxOf(2f, page.resources.displayMetrics.density * 2f)
        val candidates = shallowViews(page, 8).filterIsInstance<ViewGroup>().filter { view ->
            if (view.javaClass.name != MACH_VIEW || !view.isShown || view.width != page.width) return@filter false
            val parent = view.parent as? ViewGroup ?: return@filter false
            val visible = children(parent).filter { it.visibility == View.VISIBLE }
            val pager = children(view).singleOrNull { it.visibility == View.VISIBLE } ?: return@filter false
            val gap = parent.height - view.bottom
            parent.height >= page.height * .9f && visible.size == 2 && visible.last() === view &&
                visible.first().top == 0 && abs(visible.first().bottom - view.top) <= tolerance &&
                view.top > 0 && view.height > parent.height / 2 && view.left == 0 &&
                pager.javaClass.name == MACH_PAGER && pager.top == 0 && pager.height == view.height &&
                ((barHeight > 0 && gap > 0 && abs(gap - barHeight) <= tolerance) ||
                    (abs(gap) <= tolerance && activeContainer === view)) &&
                component(view)?.let(::isOrderComponent) == true
        }.toList()
        val container = candidates.singleOrNull()
        if (container == null) {
            restore()
            return
        }
        if (activeContainer !== container) restore()
        val available = (container.parent as ViewGroup).height - container.top
        val oldHeight = heights[container]?.original ?: container.height.toFloat()
        val targets = IdentityHashMap<View, Any>()
        component(container)?.let { targets[container] = it }
        val pager = children(container).single { it.visibility == View.VISIBLE }
        // The pager component owns the actual ViewPager; its pages have MATCH_PARENT
        // Android shells and may contain explicit-height MachPro viewport roots.
        val owner = targets[container]
        val components = owner?.let { field(it, "mChildComponents") } as? Iterable<*>
        components?.filterNotNull()?.singleOrNull { call(it, "getView") === pager }
            ?.let { targets[pager] = it }
        if (pager is ViewGroup) shallowViews(pager, 5).forEach { view ->
            if (view !== pager && view.javaClass.name == MACH_VIEW && view.top == 0 && view.left == 0 &&
                view.width == page.width && abs(view.height - container.height) <= tolerance) {
                component(view)?.let { targets[view] = it }
            }
        }
        // Flex/percentage heights can also reserve space through an explicit bottom
        // padding, margin or absolute position. Clear only a POINT value equal to
        // the independently verified native-tab gap; keep Yoga's height semantics.
        val gap = (container.parent as ViewGroup).height - container.bottom
        if (gap > 0) {
            val parent = container.parent as ViewGroup
            val choices = listOfNotNull(
                edge(parent, "u", "m0"), // Yoga padding-bottom
                edge(container, "s", "Z"), // Yoga margin-bottom
                edge(container, "v", "o0") // Yoga position bottom
            ).filter { abs(it.original - gap) <= tolerance }
            // Ambiguous combinations must not erase multiple independent constraints.
            if (choices.size == 1) {
                val choice = choices.single()
                if (setEdge(choice, 0f) && edges.none { it.node === choice.node && it.getter == choice.getter }) edges.add(choice)
            }
        }
        val containerNode = targets[container]?.let { field(it, "mYogaNode") }
        val currentHeight = containerNode?.let(::pointHeight)
        val explicitHeight = currentHeight != null && (heights.containsKey(container) || abs(currentHeight - oldHeight) <= tolerance)
        if (!explicitHeight && edges.isEmpty()) {
            restore()
            return
        }
        activeContainer = container
        heights.keys.toList().filter { !targets.containsKey(it) }.forEach(::restoreView)
        targets.forEach { (view, component) ->
            if (!isOrderComponent(component)) return@forEach
            val node = field(component, "mYogaNode") ?: return@forEach
            val current = pointHeight(node) ?: return@forEach
            val previous = heights[view]
            if (previous != null && previous.node !== node) restoreView(view)
            if (heights[view] == null && abs(current - oldHeight) > tolerance) return@forEach
            if (abs(current - available) < .5f) return@forEach
            val saved = heights[view] ?: Height(component, node, current, current).also { heights[view] = it }
            if (setHeight(saved, available.toFloat())) saved.applied = available.toFloat()
        }
    }

    fun restore() {
        heights.keys.toList().forEach(::restoreView)
        edges.forEach { saved ->
            if (edgeValue(saved) == 0f) setEdge(saved, saved.original)
        }
        edges.clear()
        activeContainer = null
    }

    private fun restoreView(view: View) {
        val saved = heights.remove(view) ?: return
        // Do not overwrite a newer host constraint written after our correction.
        if (pointHeight(saved.node) == saved.applied) setHeight(saved, saved.original)
    }

    private fun setHeight(saved: Height, height: Float): Boolean = runCatching {
        if (call(saved.node, "B") != false) return false // Yoga native node already disposed.
        saved.node.javaClass.getMethod("V", Float::class.javaPrimitiveType).invoke(saved.node, height)
        call(saved.component, "requestLayout")
        true
    }.getOrDefault(false)

    private fun edge(view: View, getter: String, setter: String): Edge? = runCatching {
        val component = component(view)?.takeIf(::isOrderComponent) ?: return null
        val node = field(component, "mYogaNode") ?: return null
        if (call(node, "B") != false) return null
        val method = node.javaClass.methods.single { it.name == getter && it.parameterCount == 1 }
        val bottom = method.parameterTypes.single().enumConstants
            ?.singleOrNull { (it as? Enum<*>)?.name == "BOTTOM" } ?: return null
        val value = method.invoke(node, bottom)?.toString()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        Edge(component, node, bottom, getter, setter, value)
    }.getOrNull()

    private fun edgeValue(saved: Edge): Float? = runCatching {
        if (call(saved.node, "B") != false) return null
        saved.node.javaClass.getMethod(saved.getter, saved.edge.javaClass).invoke(saved.node, saved.edge)
            ?.toString()?.toFloatOrNull()
    }.getOrNull()

    private fun setEdge(saved: Edge, value: Float): Boolean = runCatching {
        if (call(saved.node, "B") != false) return false
        saved.node.javaClass.getMethod(saved.setter, saved.edge.javaClass, Float::class.javaPrimitiveType)
            .invoke(saved.node, saved.edge, value)
        call(saved.component, "requestLayout")
        true
    }.getOrDefault(false)

    private fun pointHeight(node: Any): Float? = if (call(node, "B") == false) {
        call(node, "k")?.toString()?.toFloatOrNull()?.takeIf { it.isFinite() }
    } else null
    private fun component(view: View): Any? = call(view, "getComponent")
    private fun isOrderComponent(component: Any): Boolean = field(component, "mMachContext")
        ?.let { call(it, "getBundleName") } == "mach_pro_waimai_order_list"
    private fun field(target: Any, name: String): Any? = runCatching { target.javaClass.getField(name).get(target) }.getOrNull()
    private fun call(target: Any, name: String): Any? = runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()
    private fun children(view: ViewGroup): List<View> = (0 until view.childCount).map(view::getChildAt)

    private fun shallowViews(root: ViewGroup, depth: Int): List<View> {
        val result = ArrayList<View>()
        val pending = ArrayDeque<Pair<View, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty() && result.size < 128) {
            val (view, level) = pending.removeFirst()
            if (!view.isShown) continue
            result.add(view)
            if (view is ViewGroup && level < depth) children(view).forEach { pending.add(it to level + 1) }
        }
        return result
    }

    private companion object {
        const val MACH_VIEW = "com.sankuai.waimai.machpro.component.view.c"
        const val MACH_PAGER = "com.sankuai.waimai.machpro.component.viewpager.a"
    }
}
