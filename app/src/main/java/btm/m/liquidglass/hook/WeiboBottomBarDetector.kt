package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Collections
import java.util.IdentityHashMap

/** Finds the native navigation by geometry, independent click targets and semantic anchors. */
internal object WeiboBottomBarDetector {
    data class Tab(val feature: WeiboTabFeatures.Tab, val view: View, val clickTarget: View)
    data class Bar(val container: ViewGroup, val tabs: List<Tab>)

    fun detect(content: ViewGroup, previous: ViewGroup?, searchHierarchy: Boolean = true): Bar? {
        if (previous?.isAttachedToWindow == true && previous.rootView === content.rootView) {
            parse(content, previous)?.let { return it }
        }
        if (!searchHierarchy) return null
        // An injected Compose overlay is a sibling of android.R.id.content, so this
        // search cannot rediscover the module's own navigation as the native bar.
        return descendants(content).filterIsInstance<ViewGroup>()
            .mapNotNull { parse(content, it) }.firstOrNull()
    }

    private fun parse(content: ViewGroup, candidate: ViewGroup): Bar? {
        val density = content.resources.displayMetrics.density
        if (!candidate.isShown || candidate.width < content.width * 3 / 4 ||
            candidate.height !in (36 * density).toInt()..(128 * density).toInt()
        ) return null
        val contentPosition = IntArray(2).also(content::getLocationInWindow)
        val position = IntArray(2).also(candidate::getLocationInWindow)
        val bottomGap = contentPosition[1] + content.height - position[1] - candidate.height
        if (bottomGap !in 0..(96 * density).toInt()) return null

        val tabs = mutableListOf<Tab>()
        val targets = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        for (index in 0 until candidate.childCount) {
            val child = candidate.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.width == 0 || child.height == 0) continue
            val nodes = descendants(child).filter { it.isShown }.toList()
            val clickTarget = nodes.firstOrNull { it.isClickable }
            if (clickTarget == null) {
                // Thin separators/background layers are not tabs. A large unclickable
                // branch makes the candidate ambiguous and must not be hidden.
                if (child.width > 8 * density && child.height > 8 * density) return null
                continue
            }
            if (!targets.add(clickTarget) || child.width < 36 * density ||
                child.width > candidate.width * 0.6f || child.height < candidate.height / 2
            ) return null
            val labels = nodes.filterIsInstance<TextView>().map { it.text.toString() } +
                nodes.mapNotNull { it.contentDescription?.toString() }
            val tags = nodes.mapNotNull { node ->
                when (val tag = node.tag) {
                    is Enum<*> -> tag.name
                    is CharSequence -> tag.toString()
                    else -> null // Do not reflect into arbitrary app objects.
                }
            }
            val resources = nodes.mapNotNull { node ->
                if (node.id == View.NO_ID) null else runCatching {
                    node.resources.getResourceEntryName(node.id)
                }.getOrNull()
            }
            val feature = WeiboTabFeatures.resolve(tags, labels, resources) ?: return null
            tabs += Tab(feature, child, clickTarget)
        }
        if (!WeiboTabFeatures.isNavigation(tabs.map { it.feature })) return null
        val ordered = tabs.sortedBy { IntArray(2).also(it.view::getLocationInWindow)[0] }
        val positions = ordered.map { IntArray(2).also(it.view::getLocationInWindow) }
        // All cells must occupy one horizontal row, with no overlap and substantial
        // coverage of the candidate. This rejects page cards and vertical menus.
        for (index in ordered.indices) {
            val view = ordered[index].view
            val point = positions[index]
            if (point[0] < position[0] - density ||
                point[0] + view.width > position[0] + candidate.width + density ||
                kotlin.math.abs(point[1] - positions[0][1]) > 8 * density
            ) return null
            if (index > 0 && point[0] < positions[index - 1][0] + ordered[index - 1].view.width - density) {
                return null
            }
        }
        if (ordered.sumOf { it.view.width } < candidate.width * 0.7f) return null
        return Bar(candidate, ordered)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
