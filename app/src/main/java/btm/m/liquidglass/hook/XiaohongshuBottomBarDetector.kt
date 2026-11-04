package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** The 9.42 row has a full-width background sibling and cells taller than the bar. */
internal object XiaohongshuBottomBarDetector {
    data class Tab(val feature: XiaohongshuTabFeatures.Tab, val view: View, val clickTarget: View)
    data class Bar(val container: ViewGroup, val tabs: List<Tab>)

    fun detect(content: ViewGroup, previous: ViewGroup?, searchHierarchy: Boolean = true): Bar? {
        if (previous?.isAttachedToWindow == true && previous.rootView === content.rootView) {
            parse(content, previous)?.let { return it }
        }
        if (!searchHierarchy) return null
        return descendants(content).filterIsInstance<ViewGroup>().mapNotNull { parse(content, it) }.firstOrNull()
    }

    private fun parse(content: ViewGroup, candidate: ViewGroup): Bar? {
        val density = content.resources.displayMetrics.density
        if (!candidate.isShown || candidate.width < content.width * 0.75f ||
            candidate.height !in (32 * density).toInt()..(128 * density).toInt()
        ) return null
        val origin = IntArray(2).also(content::getLocationInWindow)
        val position = IntArray(2).also(candidate::getLocationInWindow)
        val bottomGap = origin[1] + content.height - position[1] - candidate.height
        if (bottomGap !in 0..(96 * density).toInt()) return null
        val tabs = mutableListOf<Tab>()
        val targets = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        for (index in 0 until candidate.childCount) {
            val child = candidate.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) continue
            val nodes = descendants(child).filter { it.isShown }.toList()
            val clickTarget = nodes.firstOrNull { it.isEnabled && (it.hasOnClickListeners() || it.isClickable) }
            if (clickTarget == null) {
                // Skip only non-interactive background plates and thin separators.
                if (child is ViewGroup && child.width > 8 * density && child.height > 8 * density) return null
                continue
            }
            if (!targets.add(clickTarget) || child.width < 32 * density ||
                child.width > candidate.width * 0.4f || child.height < 24 * density ||
                child.height > 96 * density
            ) return null
            val descriptions = nodes.mapNotNull { it.contentDescription?.toString() }
            val captions = nodes.filterIsInstance<TextView>().map { it.text.toString() }
            val tags = nodes.mapNotNull {
                when (val tag = it.tag) {
                    is Enum<*> -> tag.name
                    is CharSequence -> tag.toString()
                    else -> null
                }
            }
            val resources = nodes.mapNotNull {
                if (it.id == View.NO_ID) null else runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull()
            }
            val feature = XiaohongshuTabFeatures.resolve(descriptions + tags + resources, captions) ?: return null
            tabs += Tab(feature, child, clickTarget)
        }
        if (!XiaohongshuTabFeatures.isNavigation(tabs.map { it.feature })) return null
        val ordered = tabs.sortedBy { IntArray(2).also(it.view::getLocationInWindow)[0] }
        val positions = ordered.map { IntArray(2).also(it.view::getLocationInWindow) }
        for (index in ordered.indices) {
            val point = positions[index]
            val view = ordered[index].view
            if (point[0] < position[0] - density ||
                point[0] + view.width > position[0] + candidate.width + density ||
                abs(point[1] - positions[0][1]) > 20 * density ||
                point[1] > position[1] + candidate.height || point[1] + view.height < position[1]
            ) return null
            if (index > 0 && point[0] < positions[index - 1][0] + ordered[index - 1].view.width - density) return null
        }
        if (ordered.sumOf { it.view.width } < candidate.width * 0.7f) return null
        return Bar(candidate, ordered)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
