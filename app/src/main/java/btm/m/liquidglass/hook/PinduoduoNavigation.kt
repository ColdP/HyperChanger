package btm.m.liquidglass.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap

/** Dynamic Pinduoduo bottom bar detector. It follows PddTabView's live child order. */
internal object PinduoduoNavigation {
    const val PACKAGE = "com.xunmeng.pinduoduo"
    const val ACTIVITY = "com.xunmeng.pinduoduo.ui.activity.HomeActivity"

    data class Item(val view: View, val clickTarget: View, val label: String, val icon: ImageBitmap?)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    fun detect(activity: Activity, root: ViewGroup): Bar? {
        descendants(root).filterIsInstance<ViewGroup>()
            .firstOrNull { it.javaClass.name == "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView" }
            ?.let { exact ->
                detectCandidate(activity, root, exact, true)?.let { return it }
            }
        val candidates = sequenceOf(root) + descendants(root).filterIsInstance<ViewGroup>()
        return candidates.mapNotNull { candidate -> detectCandidate(activity, root, candidate, false) }
            .maxByOrNull { it.items.size }
    }

    private fun detectCandidate(activity: Activity, root: ViewGroup, candidate: ViewGroup, exact: Boolean): Bar? {
            val isPddTabView = candidate.javaClass.name == "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView"
            if (!candidate.isShown || candidate.width < root.width * 0.55f) return null
            val location = IntArray(2).also(candidate::getLocationOnScreen)
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val bottomGap = rootLocation[1] + root.height - (location[1] + candidate.height)
            if (!isPddTabView && !exact && bottomGap !in 0..dp(activity, 96)) return null
            val items = (0 until candidate.childCount).mapNotNull { index ->
                val child = candidate.getChildAt(index)
                val target = clickable(child) ?: return@mapNotNull null
                val label = text(child)
                val selected = isSelected(child)
                val icon = extractedIcon(activity, label, selected) ?: image(child)
                if (label.isBlank() && icon == null) return@mapNotNull null
                Item(child, target, label.ifBlank { featureLabel(child) }, icon)
            }.distinctBy { it.view }
            if (items.size !in 3..8 && !isPddTabView) return null
            val ordered = items.sortedBy { IntArray(2).also(it.view::getLocationOnScreen)[0] }
            val score = ordered.count { item ->
                val s = item.label.lowercase()
                s.contains("首页") || s.contains("home") || s.contains("分类") || s.contains("个人") ||
                    s.contains("我的") || s.contains("聊天") || s.contains("消息") || s.contains("直播") ||
                    s.contains("关注") || s.contains("recommend")
            }
            if (score == 0 && ordered.count { it.icon != null } < 3 && !isPddTabView) return null
            val selected = ordered.indexOfFirst { isSelected(it.view) }.coerceAtLeast(0)
            return Bar(candidate, ordered, selected)
    }

    private fun featureLabel(view: View): String = when {
        view.javaClass.name.contains("Tab", true) -> "Tab"
        view.id != View.NO_ID -> runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        else -> "Tab"
    }

    private fun text(view: View): String {
        // Pinduoduo exposes the live tab title through contentDescription (often
        // with a numeric badge suffix), while the first descendant TextView may
        // be the unread-count badge. Prefer the semantic title and ignore badges.
        view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.replace(Regex("\\d+$"), "").trim()
        }
        val candidates = when (view) {
            is TextView -> sequenceOf(view)
            else -> descendants(view).filterIsInstance<TextView>()
        }.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("(?:[1-9]\\d?|99\\+)")) }
            .toList()
        return candidates.maxByOrNull(String::length).orEmpty()
    }

    private fun image(view: View): ImageBitmap? {
        val image = when (view) {
            is ImageView -> view
            else -> descendants(view).filterIsInstance<ImageView>().firstOrNull()
        } ?: return null
        val drawable = image.drawable ?: return null
        return drawableToBitmap(drawable)?.asImageBitmap()
    }

    private fun extractedIcon(activity: Activity, label: String, selected: Boolean): ImageBitmap? {
        val stem = when {
            label.contains("首页") || label.contains("home", true) || label.contains("推荐") -> "home"
            label.contains("分类") || label.contains("class", true) -> "classification"
            label.contains("聊天") || label.contains("消息") || label.contains("chat", true) -> "chat"
            label.contains("直播") || label.contains("live", true) -> "live"
            label.contains("个人") || label.contains("我的") || label.contains("profile", true) -> "personal"
            else -> return null
        }
        return runCatching {
            activity.createPackageContext("btm.m.os4.systemuihook", 4).assets
                .open("navigation/pinduoduo/${stem}_${if (selected) "selected" else "normal"}.png")
                .use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }

    private fun clickable(view: View): View? = when {
        view.isClickable -> view
        view is ViewGroup -> (0 until view.childCount).asSequence().mapNotNull { clickable(view.getChildAt(it)) }.firstOrNull()
        else -> null
    }

    private fun isSelected(view: View): Boolean = view.isSelected || view.isActivated ||
        (view is ViewGroup && descendants(view).any { it.isSelected || it.isActivated })

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

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
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }
}
