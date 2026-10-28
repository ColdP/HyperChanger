package btm.m.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap

/** WeChat 8.0.79 LauncherUI bottom tabs. The host owns the click listeners and pager. */
internal object WeChatNavigation {
    const val PACKAGE = "com.tencent.mm"
    const val ACTIVITY = "com.tencent.mm.ui.LauncherUI"
    private const val BAR = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private const val ICON = "com.tencent.mm.ui.TabIconView"
    private val iconCache = IdentityHashMap<View, Pair<ImageBitmap, ImageBitmap>>()

    data class Item(val view: View, val clickTarget: View, val label: String, val icon: ImageBitmap?)
    data class Bar(val view: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    fun detect(content: ViewGroup): Bar? {
        val bar = descendants(content).filterIsInstance<ViewGroup>()
            .firstOrNull { it.javaClass.name == BAR && it.isShown && it.isAttachedToWindow }
            ?: return null
        val children = (0 until bar.childCount).map { bar.getChildAt(it) }
        if (children.size != 1 || children[0] !is ViewGroup) return null
        val row = children[0] as ViewGroup
        if (row.childCount !in 4..6) return null
        val items = row.children().mapNotNull { tab ->
            val label = descendants(tab).filterIsInstance<TextView>()
                .firstOrNull { it.text?.toString()?.trim()?.isNotEmpty() == true }
                ?.text?.toString()?.trim() ?: return@mapNotNull null
            val clickTarget = tab.takeIf { it.hasOnClickListeners() }
                ?: descendants(tab).firstOrNull { it.hasOnClickListeners() }
                ?: return@mapNotNull null
            val icon = descendants(tab).firstOrNull { it.javaClass.name == ICON }
                ?.let(::snapshot)
            Item(tab, clickTarget, label, icon)
        }.toList()
        if (items.size != row.childCount) return null
        val selected = runCatching {
            bar.javaClass.getMethod("getCurIdx").invoke(bar) as Int
        }.getOrNull()?.takeIf { it in items.indices } ?: items.indexOfFirst { it.view.isSelected }
        if (selected !in items.indices) return null
        return Bar(bar, items, selected)
    }

    private fun snapshot(view: View): ImageBitmap? = iconCache[view]?.let { pair ->
        if (view.isSelected) pair.second else pair.first
    } ?: runCatching {
        val fields = generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.onEach { it.isAccessible = true }
            .toList()
        val bitmaps = fields.mapNotNull { it.get(view) as? Bitmap }
        if (bitmaps.isEmpty()) return@runCatching null
        val focus = fields.firstOrNull { it.type == Int::class.javaPrimitiveType } ?: return@runCatching null
        val draw = generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) }
            ?: return@runCatching null
        draw.isAccessible = true
        val width = bitmaps.maxOf { it.width }.coerceAtMost(256)
        val height = bitmaps.maxOf { it.height }.coerceAtMost(256)
        val original = focus.getInt(view)
        fun render(alpha: Int): ImageBitmap {
            focus.setInt(view, alpha)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw.invoke(view, Canvas(bitmap))
            return bitmap.asImageBitmap()
        }
        try {
            val pair = render(0) to render(255)
            iconCache[view] = pair
            pair
        } finally {
            focus.setInt(view, original)
        }
        }.getOrNull()?.let { pair -> if (view.isSelected) pair.second else pair.first }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }
    private fun ViewGroup.children(): Sequence<View> = sequence { for (i in 0 until childCount) yield(getChildAt(i)) }
}
