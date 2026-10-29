package btm.m.liquidglass.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.BitmapFactory
import android.os.SystemClock
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
    private const val TAB_VIEW = "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView"
    private const val TAB_ITEM = "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.TabRelativeLayout"

    data class Item(val view: View, val clickTarget: View, val label: String, val icon: ImageBitmap?)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    /** Owned by one Activity session; never accept product cards as a fallback. */
    class Detector(private val activity: Activity) {
        private var cachedContainer: ViewGroup? = null
        private var lastSearchAt: Long? = null
        private val assetIcons = mutableMapOf<String, ImageBitmap?>()
        private val nativeIcons = IdentityHashMap<ImageView, IconSnapshot>()

        fun detect(root: ViewGroup): Bar? {
            cachedContainer?.takeIf { belongsTo(it, root) }?.let { bar ->
                parse(bar, root)?.let { return it }
            }
            val now = SystemClock.uptimeMillis()
            if (lastSearchAt?.let { now - it < 300L } == true) return null
            lastSearchAt = now
            // HomeActivity also contains product lists with arbitrary text and images.
            // Only PddTabView -> direct TabRelativeLayout children is a main tab row.
            return descendants(root).filterIsInstance<ViewGroup>()
                .filter { it.javaClass.name == TAB_VIEW }
                .mapNotNull { parse(it, root) }
                .firstOrNull()?.also { cachedContainer = it.container }
        }

        fun clear() {
            cachedContainer = null
            lastSearchAt = null
            nativeIcons.clear()
            assetIcons.clear()
        }

        private fun parse(candidate: ViewGroup, root: ViewGroup): Bar? {
            if (!candidate.isShown || candidate.width < root.width * 0.55f ||
                candidate.childCount !in 3..8
            ) return null
            if (candidate !== cachedContainer) nativeIcons.clear()
            nativeIcons.keys.removeAll { !belongsTo(it, candidate) }
            val items = (0 until candidate.childCount).mapNotNull { index ->
                val child = candidate.getChildAt(index)
                if (child.javaClass.name != TAB_ITEM || !child.isShown) return@mapNotNull null
                val target = clickable(child) ?: return@mapNotNull null
                val label = text(child).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val selected = isSelected(child)
                val icon = extractedIcon(label, selected) ?: image(child, selected)
                Item(child, target, label, icon)
            }
            if (items.size != candidate.childCount) return null
            // Child order is PddTabView's native click/index order. Its height is
            // deliberately zero after takeover; requiring visible geometry would
            // repeatedly restore/collapse the bar and trigger full-screen flashes.
            return Bar(candidate, items, items.indexOfFirst { isSelected(it.view) }.coerceAtLeast(0))
        }

        private fun image(view: View, selected: Boolean): ImageBitmap? {
            val image = descendants(view).filterIsInstance<ImageView>().firstOrNull() ?: return null
            val drawable = image.drawable ?: return null
            nativeIcons[image]?.takeIf { it.drawable === drawable && it.selected == selected }
                ?.let { return it.bitmap }
            return drawableToBitmap(drawable)?.asImageBitmap().also {
                nativeIcons[image] = IconSnapshot(drawable, selected, it)
            }
        }

        private fun extractedIcon(label: String, selected: Boolean): ImageBitmap? {
            val stem = iconStem(label) ?: return null
            val name = "${stem}_${if (selected) "selected" else "normal"}.png"
            if (!assetIcons.containsKey(name)) {
                assetIcons[name] = runCatching {
                    activity.createPackageContext("btm.m.os4.systemuihook", 4).assets
                        .open("navigation/pinduoduo/$name")
                        .use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }.getOrNull()
            }
            return assetIcons[name]
        }

        private data class IconSnapshot(val drawable: Drawable, val selected: Boolean, val bitmap: ImageBitmap?)
    }

    private fun text(view: View): String {
        // TabRelativeLayout.initView inserts tabTitleView before digitalFlagView.
        // Read that direct title first; preserve digits that are part of its name.
        if (view is ViewGroup) {
            (0 until view.childCount).asSequence().map { view.getChildAt(it) }
                .filterIsInstance<TextView>().firstOrNull()?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return view.contentDescription?.toString()?.trim()
            ?.replace(Regex("(?:\\d+\\+?)$"), "")?.trim().orEmpty()
    }

    private fun iconStem(label: String): String? = when {
        label.contains("首页") || label.contains("home", true) || label.contains("推荐") -> "home"
        label.contains("分类") || label.contains("class", true) -> "classification"
        label.contains("聊天") || label.contains("消息") || label.contains("chat", true) -> "chat"
        label.contains("直播") || label.contains("live", true) -> "live"
        label.contains("个人") || label.contains("我的") || label.contains("profile", true) -> "personal"
        else -> null
    }

    private fun clickable(view: View): View? = when {
        view.isClickable -> view
        view is ViewGroup -> (0 until view.childCount).asSequence().mapNotNull { clickable(view.getChildAt(it)) }.firstOrNull()
        else -> null
    }

    private fun isSelected(view: View): Boolean = view.isSelected || view.isActivated ||
        (view is ViewGroup && descendants(view).any { it.isSelected || it.isActivated })

    private fun belongsTo(view: View, root: ViewGroup): Boolean =
        view.isAttachedToWindow && generateSequence(view as View?) { it.parent as? View }.any { it === root }

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
