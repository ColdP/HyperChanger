package btm.m.liquidglass.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.HashMap
import java.lang.reflect.Method

/** Runtime evidence based detector for QQ 9.3.75's primary TabBarView. */
internal object QQNavigation {
    const val PACKAGE = "com.tencent.mobileqq"
    const val ACTIVITY = "com.tencent.mobileqq.activity.SplashActivity"
    private const val TAB_BAR = "com.tencent.mobileqq.widget.TabBarView"
    private const val QQ_TAB_LAYOUT = "com.tencent.mobileqq.widget.QQTabLayout"
    private const val QQ_TAB_WIDGET = "com.tencent.mobileqq.widget.QQTabWidget"
    private val iconCache = HashMap<String, ImageBitmap>()
    private val unreadNumber = Regex("(?:[1-9]\\d*\\+?)")

    data class Item(
        val view: View,
        val clickTarget: View,
        val label: String,
        val icon: ImageBitmap?,
        val badge: Boolean,
    )

    data class Bar(val view: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    /** Per-session binding, analogous to the reference project's tab-row binding. */
    class Binding(val bar: Bar) {
        private val row = itemContainer(bar.view)
        private val selectedGetter: Method? = listOf("j", "getCurrentTab", "getSelectedTabPosition")
            .firstNotNullOfOrNull { name -> runCatching { bar.view.javaClass.getMethod(name) }.getOrNull() }

        fun matchesStructure(): Boolean {
            val current = itemContainer(bar.view) ?: return false
            if (current !== row) return false
            var slot = 0
            for (index in 0 until current.childCount) {
                val child = current.getChildAt(index)
                if (child.visibility == View.GONE) continue
                if (slot >= bar.items.size || child !== bar.items[slot++].view) return false
            }
            return slot == bar.items.size
        }

        fun selectedIndex(): Int {
            val selected = bar.items.indexOfFirst { isSelected(it.view) }
            if (selected >= 0) return selected
            val raw = runCatching { selectedGetter?.invoke(bar.view) as? Int }.getOrNull() ?: return -1
            val selectedView = row?.getChildAt(raw) ?: return -1
            return bar.items.indexOfFirst { it.view === selectedView }
        }
    }

    fun readSelectedIndex(bar: Bar): Int = Binding(bar).selectedIndex()

    fun refreshBadges(bar: Bar): Bar {
        val refreshed = bar.items.map { item -> item.copy(badge = hasBadge(item.view)) }
        return if (refreshed == bar.items) bar else bar.copy(items = refreshed)
    }

    fun labelsMatch(bar: Bar): Boolean {
        val row = itemContainer(bar.view) ?: return false
        var slot = 0
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (slot >= bar.items.size || semanticText(child) != bar.items[slot++].label) return false
        }
        return slot == bar.items.size
    }

    fun isUsable(bar: Bar): Boolean = bar.view.isAttachedToWindow &&
        bar.view.visibility == View.VISIBLE && bar.items.isNotEmpty() &&
        bar.items.all { it.view.isAttachedToWindow && it.view.parent != null }

    fun refresh(activity: Activity, root: ViewGroup, previous: ViewGroup?): Bar? =
        previous?.takeIf { it.isAttachedToWindow && it.rootView === root.rootView }
            ?.let { detectCandidate(activity, root, it, exact = isKnownBar(it)) }
            ?: detect(activity, root)

    private fun isKnownBar(view: View): Boolean = view.javaClass.name in
        setOf(TAB_BAR, QQ_TAB_LAYOUT, QQ_TAB_WIDGET) ||
        (view.javaClass.simpleName == "TabBarView" && view.context.packageName == PACKAGE)

    private fun itemContainer(bar: ViewGroup): ViewGroup? =
        if (bar.childCount == 1) bar.getChildAt(0) as? ViewGroup else bar

    fun detect(activity: Activity, root: ViewGroup): Bar? {
        val exact = descendants(root).filterIsInstance<ViewGroup>()
            .filter {
                it.javaClass.name == TAB_BAR ||
                    it.javaClass.name == QQ_TAB_LAYOUT ||
                    it.javaClass.name == QQ_TAB_WIDGET ||
                    (it.javaClass.simpleName == "TabBarView" && it.context.packageName == PACKAGE)
            }
            .mapNotNull { detectCandidate(activity, root, it, exact = true) }
            .maxByOrNull { it.items.size }
        if (exact != null) return exact

        // Some QQ builds wrap TabBarView in an obfuscated scrolling container.
        // Keep a narrow structural fallback for the main bar, but require a
        // bottom anchored candidate with multiple known tab labels so detail
        // page toolbars are not mistaken for the primary navigation.
        return descendants(root).filterIsInstance<ViewGroup>()
            .mapNotNull { detectCandidate(activity, root, it, exact = false) }
            .maxByOrNull { it.items.size }
    }

    private fun detectCandidate(activity: Activity, root: ViewGroup, candidate: ViewGroup, exact: Boolean): Bar? {
        if (!candidate.isShown || !candidate.isAttachedToWindow || candidate.width <= 0 || candidate.height <= 0) return null
        if (!exact && candidate.width < root.width * .65f) return null
        val density = activity.resources.displayMetrics.density
        if (candidate.height !in (32 * density).toInt()..(128 * density).toInt()) return null
        val location = IntArray(2).also(candidate::getLocationInWindow)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val bottomGap = rootLocation[1] + root.height - location[1] - candidate.height
        // QQ also embeds TabBarView at the top of a vertical host (acx.xml). The
        // exact class match is sufficient; the structural fallback remains bottom-bound.
        if (bottomGap !in 0..(96 * density).toInt()) return null
        val itemContainer = itemContainer(candidate)
        if (itemContainer == null || itemContainer.childCount !in 3..8) return null

        val items = (0 until itemContainer.childCount).mapNotNull { index ->
            val child = itemContainer.getChildAt(index)
            if (child.visibility == View.GONE) return@mapNotNull null
            // TabBarView's child rows may delegate touch handling to the parent
            // without exposing an OnClickListener. Keep the row as a synthetic-tap
            // target so text-only QQ variants remain adaptable.
            val clickTarget = findClickable(child) ?: child
            val label = semanticText(child)
            val icon = staticIcon(activity, label, selected = false)
                ?: firstImage(child)?.let(::snapshot)
            if (label.isBlank() && icon == null && child.width <= 0) return@mapNotNull null
            Item(child, clickTarget, label, icon, hasBadge(child))
        }
        if (items.size !in 3..8 || items.map { it.view }.distinct().size != items.size) return null
        val labels = items.map { it.label }.filter(String::isNotBlank)
        val semanticScore = labels.count(::looksLikePrimaryTab)
        val resourceName = runCatching {
            candidate.resources.getResourceEntryName(candidate.id)
        }.getOrDefault("")
        val knownQQHost = candidate.javaClass.name.contains("QQTabLayout") ||
            resourceName.contains("37i") || resourceName == "1s8"
        if (!exact && semanticScore < 2 && !knownQQHost) return null
        val selected = runCatching {
            candidate.javaClass.getMethod("j").invoke(candidate) as Int
        }.getOrNull()?.takeIf { it in items.indices }
            ?: items.indexOfFirst { isSelected(it.view) }.takeIf { it >= 0 }
            ?: 0
        return Bar(candidate, items, selected)
    }

    private fun looksLikePrimaryTab(text: String): Boolean {
        val value = text.lowercase()
        return listOf("消息", "联系人", "动态", "频道", "小世界", "我的", "会话", "message", "contact", "dynamic", "discover", "profile", "guild").any(value::contains)
    }

    private fun semanticText(view: View): String {
        val values = ArrayList<String>(3)
        val name = view.javaClass.name
        if (!name.contains("Badge", true) && !name.contains("RedTouch", true)) {
            view.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
        }
        descendants(view).filterIsInstance<TextView>().forEach { text ->
            val type = text.javaClass.name
            if (!type.contains("Badge", true) && !type.contains("RedTouch", true) &&
                !type.contains("RedDot", true)
            ) text.text?.toString()?.trim()?.takeIf {
                it.isNotBlank() && it.length <= 24 && !unreadNumber.matches(it)
            }?.let(values::add)
        }
        return values.distinct().maxByOrNull(String::length).orEmpty()
    }

    private fun hasBadge(view: View): Boolean = descendants(view).any { child ->
        if (!child.isShown) return@any false
        val name = child.javaClass.name
        val text = (child as? TextView)?.text?.toString()?.trim().orEmpty()
        name.contains("Badge", true) || name.contains("RedTouch", true) ||
            unreadNumber.matches(text) || (name.contains("RedDotTextView") && child.background != null)
    }

    private fun findClickable(view: View): View? {
        if (view.isClickable || view.hasOnClickListeners()) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findClickable(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun firstImage(view: View): ImageView? = when (view) {
        is ImageView -> view
        is ViewGroup -> (0 until view.childCount).asSequence().mapNotNull { firstImage(view.getChildAt(it)) }.firstOrNull()
        else -> null
    }

    private fun isSelected(view: View): Boolean = view.isSelected || view.isActivated ||
        (view is ViewGroup && descendants(view).any { it.isSelected || it.isActivated })

    private fun snapshot(view: ImageView): ImageBitmap? {
        val drawable = view.drawable ?: return null
        val bitmap = drawableToBitmap(drawable) ?: return null
        return bitmap.asImageBitmap()
    }

    private fun staticIcon(activity: Activity, label: String, selected: Boolean): ImageBitmap? {
        val stem = when {
            label.contains("消息") || label.contains("会话") || label.contains("message", true) -> "message"
            label.contains("联系人") || label.contains("contact", true) -> "contact"
            label.contains("频道") || label.contains("guild", true) -> "channel"
            label.contains("小世界") || label.contains("world", true) -> "small_world"
            label.contains("动态") || label.contains("discover", true) || label.contains("dynamic", true) -> "activity"
            label.contains("我的") || label == "我" || label.contains("profile", true) -> "me"
            else -> return null
        }
        val fileName = when (stem) {
                "activity" -> if (selected) "activity_default_pressed.png" else "activity_nor.png"
                "channel" -> if (selected) "channel_default_pressed.png" else "channel_nor.png"
                "contact" -> if (selected) "contact_default_pressed.png" else "contact_nor.png"
                "me" -> if (selected) "me_default_pressed.png" else "me_nor.png"
                "message" -> if (selected) "message_default_pressed.png" else "message_nor.png"
                else -> if (selected) "small_world_default_pressed.png" else "small_world_nor.png"
        }
        val cacheKey = fileName
        iconCache[cacheKey]?.let { return it }
        return runCatching {
            activity.createPackageContext("btm.m.os4.systemuihook", 4).assets
                .open("navigation/qq/$fileName").use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()?.also {
                        iconCache[cacheKey] = it
                    }
                }
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = drawable.intrinsicWidth.takeIf { it > 1 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 1 } ?: 96
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val old = Rect(drawable.bounds)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(it))
                drawable.bounds = old
            }
        }.getOrNull()
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
