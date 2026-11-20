package btm.m.liquidglass.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TabHost
import android.widget.TabWidget
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import java.util.WeakHashMap

/**
 * Native bottom navigation adapter for Alipay (com.eg.android.AlipayGphone).
 * Inspects TabLauncherFragment's TabHost / TabWidget / TabWidgetItemView hierarchy
 * while keeping android.R.id.tabcontent visible as the liquid-glass backdrop source.
 */
internal class AlipayNavigation {
    data class Bar(
        val container: ViewGroup,
        val hiddenLayers: List<View>,
        val tabContent: ViewGroup?,
        val items: List<Item>,
        val selectedIndex: Int,
    )

    data class Item(
        val view: View,
        val clickTarget: View,
        val identity: String,
        val tabId: String,
        val label: String,
        val icon: ImageBitmap?,
        val preserveColor: Boolean,
        val badge: Boolean,
    )

    private var lastTreeSearchAt = 0L
    private var cachedTabHost: TabHost? = null
    private val icons = WeakHashMap<View, IconSnapshot>()

    fun clear() {
        lastTreeSearchAt = 0L
        cachedTabHost = null
        icons.clear()
    }

    fun detect(content: ViewGroup): Bar? {
        val now = SystemClock.uptimeMillis()
        val tabHost = cachedTabHost?.takeIf { isValidTabHost(it, content) } ?: run {
            if (now - lastTreeSearchAt < 500L) return null
            lastTreeSearchAt = now
            findTabHost(content)?.also { cachedTabHost = it } ?: return null
        }
        return parse(content, tabHost)
    }

    fun open(content: ViewGroup, identity: String): Boolean {
        val bar = detect(content) ?: return false
        val item = bar.items.firstOrNull { it.identity == identity } ?: return false
        val target = item.clickTarget
        if (!target.isAttachedToWindow || !target.isShown || !target.isEnabled) return false
        return target.callOnClick() || target.performClick()
    }

    private fun parse(content: ViewGroup, tabHost: TabHost): Bar? {
        val tabWidget = tabHost.findViewById<TabWidget>(android.R.id.tabs) ?: return null
        if (!tabWidget.isAttachedToWindow || !tabWidget.isShown ||
            tabWidget.width < content.width / 2 || tabWidget.height <= 0
        ) return null

        val cells = (0 until tabWidget.childCount)
            .map { tabWidget.getChildAt(it) }
            .filter { it.isShown }
        if (cells.size !in 2..8) return null

        val selectedIndex = cells.indices.filter { cells[it].isSelected }.singleOrNull()
            ?: run {
                val cur = tabHost.currentTab
                if (cur in cells.indices) cur else return null
            }

        val items = cells.mapIndexed { index, cell ->
            val tabId = resolveTabId(cell, index)
            val label = resolveLabel(cell, tabId) ?: return null
            val iconPair = resolveIcon(cell)
            val badge = hasVisibleBadge(cell)
            Item(
                view = cell,
                clickTarget = cell,
                identity = "alipay:$tabId",
                tabId = tabId,
                label = label,
                icon = iconPair.first,
                preserveColor = iconPair.second,
                badge = badge,
            )
        }

        val tabContent = tabHost.findViewById<ViewGroup>(android.R.id.tabcontent)
        val barContainer = (tabWidget.parent as? ViewGroup)
            ?.takeIf { it !== tabHost && it !== tabContent }
            ?: tabWidget

        val density = content.resources.displayMetrics.density
        val maxChromeHeight = (200f * density).toInt()
        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        val contentBottom = contentLocation[1] + content.height
        val hidden = linkedSetOf<View>(barContainer)
        for (i in 0 until tabHost.childCount) {
            val child = tabHost.getChildAt(i)
            if (child === tabContent || child === barContainer || child is ComposeView) continue
            val isAiBar = child.javaClass.name.contains("AITabWidget", ignoreCase = true) ||
                child.javaClass.name.contains("TabBar", ignoreCase = true) ||
                child.javaClass.name.contains("Jinni", ignoreCase = true)
            if (isAiBar) {
                hidden.add(child)
            } else if (child.height in 0..maxChromeHeight) {
                val loc = IntArray(2).also(child::getLocationInWindow)
                if (child.height == 0 || loc[1] + child.height >= contentBottom - maxChromeHeight) {
                    hidden.add(child)
                }
            }
        }

        return Bar(
            container = barContainer,
            hiddenLayers = hidden.toList(),
            tabContent = tabContent,
            items = items,
            selectedIndex = selectedIndex,
        )
    }

    private fun resolveTabId(cell: View, index: Int): String {
        fieldString(cell, "mTabId")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        (cell.tag as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return "tab_$index"
    }

    private fun resolveLabel(cell: View, tabId: String): String? {
        val primaryText = (fieldView(cell, "mTextView") as? TextView)
            ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (primaryText != null) return primaryText
        val found = views(cell)
            .filterIsInstance<TextView>()
            .filter { it.isShown && !it.javaClass.name.contains("Badge", ignoreCase = true) }
            .mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull { !it.all(Char::isDigit) }
        if (found != null) return found
        return when (tabId) {
            "20000002" -> "首页"
            "20001688" -> "理财"
            "o2oTab" -> "生活"
            "20000217" -> "消息"
            "20000004" -> "我的"
            "20000870" -> "国际"
            else -> null
        }
    }

    private fun resolveIcon(cell: View): Pair<ImageBitmap?, Boolean> {
        val cdpImage = (fieldView(cell, "mCdpImageView") as? ImageView)?.takeIf { it.isShown }
        val cdpDrawable = cdpImage?.drawable
        if (cdpDrawable != null) {
            return snapshotDrawable(cell, cdpDrawable, cell.drawableState) to true
        }
        val mainImage = (fieldView(cell, "mImageView") as? ImageView)
            ?.takeIf { it.drawable != null }
            ?: views(cell).filterIsInstance<ImageView>()
                .firstOrNull { it.drawable != null && !it.javaClass.name.contains("Badge", ignoreCase = true) }
        val drawable = mainImage?.drawable ?: return null to false
        return snapshotDrawable(cell, drawable, cell.drawableState) to false
    }

    private fun snapshotDrawable(cell: View, drawable: Drawable, state: IntArray): ImageBitmap? {
        val working = if (drawable.isStateful) {
            drawable.mutate().also { it.state = state }
        } else {
            drawable
        }
        val current = working.current
        val stateHash = working.state.contentHashCode()
        val theme = cell.resources.configuration.uiMode
        val now = SystemClock.uptimeMillis()
        icons[cell]?.takeIf {
            it.drawable === drawable && it.current === current && it.state == stateHash && it.theme == theme &&
                (working !is Animatable || now - it.at < 250L)
        }?.let { return it.bitmap }

        if (current is BitmapDrawable && current.bitmap != null && !current.bitmap.isRecycled) {
            val bmp = current.bitmap
            val edge = maxOf(bmp.width, bmp.height).coerceIn(48, 192)
            val imageBitmap = runCatching {
                val scale = edge.toFloat() / maxOf(bmp.width, bmp.height)
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { out ->
                    val dst = Rect((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                    Canvas(out).drawBitmap(bmp, null, dst, null)
                }.asImageBitmap()
            }.getOrNull()
            icons[cell] = IconSnapshot(drawable, current, stateHash, theme, now, imageBitmap)
            return imageBitmap
        }

        val bitmap = runCatching {
            val width = working.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = working.intrinsicHeight.takeIf { it > 0 } ?: 96
            val edge = maxOf(width, height).coerceIn(48, 192)
            val scale = edge.toFloat() / maxOf(width, height)
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { output ->
                val old = Rect(working.bounds)
                try {
                    working.setBounds((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                    working.draw(Canvas(output))
                } finally {
                    working.bounds = old
                }
            }.asImageBitmap()
        }.getOrNull()
        icons[cell] = IconSnapshot(drawable, current, stateHash, theme, now, bitmap)
        return bitmap
    }

    private fun hasVisibleBadge(cell: View): Boolean {
        val badge = fieldView(cell, "mBadgeView") ?: return false
        if (!badge.isShown || badge.width <= 0 || badge.height <= 0) return false
        if (badge is ViewGroup) {
            return (0 until badge.childCount).any { idx ->
                val c = badge.getChildAt(idx)
                c.isShown && (c !is TextView || !c.text.isNullOrBlank())
            }
        }
        return true
    }

    private fun findTabHost(content: ViewGroup): TabHost? =
        views(content).filterIsInstance<TabHost>().firstOrNull { isValidTabHost(it, content) }

    private fun isValidTabHost(tabHost: TabHost?, content: ViewGroup): Boolean {
        tabHost ?: return false
        if (!tabHost.isAttachedToWindow || !tabHost.isShown) return false
        if (tabHost.id != android.R.id.tabhost) return false
        val tabWidget = tabHost.findViewById<TabWidget>(android.R.id.tabs) ?: return false
        if (!tabWidget.isAttachedToWindow || !tabWidget.isShown) return false
        if (tabWidget.width < content.width / 2 || tabWidget.height <= 0) return false
        val visibleCount = (0 until tabWidget.childCount).count { tabWidget.getChildAt(it).isShown }
        return visibleCount in 2..8
    }

    private fun fieldString(receiver: Any, name: String): String? = runCatching {
        var c: Class<*>? = receiver.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return@runCatching f.get(receiver) as? String
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun fieldView(receiver: Any, name: String): View? = runCatching {
        var c: Class<*>? = receiver.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return@runCatching f.get(receiver) as? View
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
    }

    private data class IconSnapshot(
        val drawable: Drawable,
        val current: Drawable,
        val state: Int,
        val theme: Int,
        val at: Long,
        val bitmap: ImageBitmap?,
    )

    companion object {
        const val PACKAGE = "com.eg.android.AlipayGphone"
        const val ACTIVITY = "com.eg.android.AlipayGphone.AlipayLogin"
    }
}
