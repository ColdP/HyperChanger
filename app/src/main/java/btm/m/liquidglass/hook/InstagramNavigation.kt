package btm.m.liquidglass.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import btm.m.os4.systemuihook.tr
import java.util.IdentityHashMap

/**
 * Instagram's tab row is identified by stable resource ids rather than by one
 * obfuscated implementation class.  The detector intentionally follows the
 * ordered ids used by HyperiOS and only returns ids that exist in this build.
 */
internal object InstagramNavigation {
    const val PACKAGE = "com.instagram.android"
    const val ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity"

    private val orderedNames = listOf("feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab")

    data class Item(
        val view: ViewGroup,
        val clickTarget: View,
        val label: String,
        val icon: ImageBitmap?,
        val profile: Boolean,
        val badge: Boolean,
    )

    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    class Detector(private val activity: Activity) {
        private var cached: ViewGroup? = null
        private var lastSearchAt = 0L
        private val iconSnapshots = IdentityHashMap<ImageView, Snapshot>()

        fun detect(root: ViewGroup): Bar? {
            cached?.takeIf { belongsTo(it, root) }?.let { parse(it, root)?.let { result -> return result } }
            val now = SystemClock.uptimeMillis()
            if (now - lastSearchAt < 250L) return null
            lastSearchAt = now

            val ids = orderedNames.mapNotNull { name ->
                val id = root.resources.getIdentifier(name, "id", PACKAGE)
                if (id == 0) null else name to id
            }
            val found = ids.mapNotNull { (name, id) ->
                val view = root.findViewById<View>(id) as? ViewGroup ?: return@mapNotNull null
                if (!view.isShown && view.visibility != View.VISIBLE) return@mapNotNull null
                val clickTarget = clickable(view) ?: return@mapNotNull null
                val image = imageView(view)
                val bitmap = image?.let { snapshot(it) }
                val profile = name == "profile_tab"
                val label = if (profile) {
                    profileUsername(root, view) ?: tr("个人资料", "Profile")
                } else {
                    text(view).ifBlank { fallbackLabel(name) }
                }
                Item(view, clickTarget, label, bitmap, profile, hasBadge(view))
            }
            if (found.isEmpty()) return null
            val container = commonContainer(found.map { it.view }) ?: return null
            return Bar(container, found, found.indexOfFirst { isSelected(it.view) }.coerceAtLeast(0))
                .also { cached = container }
        }

        fun clear() {
            cached = null
            lastSearchAt = 0L
            iconSnapshots.clear()
        }

        private fun parse(candidate: ViewGroup, root: ViewGroup): Bar? {
            val result = detectFromChildren(root, candidate) ?: return null
            return result
        }

        private fun detectFromChildren(root: ViewGroup, candidate: ViewGroup): Bar? {
            val items = orderedNames.mapNotNull { name ->
                val id = root.resources.getIdentifier(name, "id", PACKAGE)
                if (id == 0) return@mapNotNull null
                val view = root.findViewById<View>(id) as? ViewGroup ?: return@mapNotNull null
                val target = clickable(view) ?: return@mapNotNull null
                val image = imageView(view)
                val profile = name == "profile_tab"
                Item(
                    view = view,
                    clickTarget = target,
                    label = if (profile) profileUsername(root, view) ?: tr("个人资料", "Profile")
                    else text(view).ifBlank { fallbackLabel(name) },
                    icon = image?.let { snapshot(it) },
                    profile = profile,
                    badge = hasBadge(view),
                )
            }
            if (items.isEmpty() || items.none { it.view === candidate || belongsTo(it.view, candidate) }) return null
            val container = commonContainer(items.map { it.view }) ?: candidate
            return Bar(container, items, items.indexOfFirst { isSelected(it.view) }.coerceAtLeast(0))
        }

        private fun snapshot(image: ImageView): ImageBitmap? {
            val drawable = image.drawable ?: return null
            val stateKey = drawable.state?.contentHashCode() ?: 0
            val old = iconSnapshots[image]
            if (old?.drawable === drawable && old.stateKey == stateKey) return old.bitmap
            val bitmap = drawableToBitmap(drawable)?.asImageBitmap()
            iconSnapshots[image] = Snapshot(drawable, stateKey, bitmap)
            return bitmap
        }

        private fun profileUsername(root: ViewGroup, profile: ViewGroup): String? {
            val exactId = profile.resources.getIdentifier("profile_tab_text_view", "id", PACKAGE)
            if (exactId != 0) {
                (profile.findViewById<View>(exactId) as? TextView)?.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val local = descendants(profile).filterIsInstance<TextView>()
                .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
            if (local != null) return local
            return descendants(root).mapNotNull { view ->
                val name = view.id.takeIf { it != View.NO_ID }?.let {
                    runCatching { view.resources.getResourceEntryName(it) }.getOrNull()
                }.orEmpty().lowercase()
                val value = viewText(view)?.trim().orEmpty()
                if (value.isNotBlank() && (name.contains("username") || name.contains("user_name") || name.contains("profile_name"))) value else null
            }.firstOrNull()
        }

        private fun imageView(view: ViewGroup): ImageView? {
            val exactId = view.resources.getIdentifier("profile_tab_icon_view", "id", PACKAGE)
            if (exactId != 0) {
                (view.findViewById<View>(exactId) as? ImageView)?.let { return it }
            }
            return descendants(view).filterIsInstance<ImageView>().firstOrNull()
        }

        private fun commonContainer(views: List<ViewGroup>): ViewGroup? {
            val first = views.firstOrNull() ?: return null
            return generateSequence(first.parent as? View) { it.parent as? View }
                .filterIsInstance<ViewGroup>()
                .firstOrNull { candidate -> views.all { isDescendantOf(it, candidate) } }
        }

        private fun belongsTo(view: View, root: View): Boolean =
            view.isAttachedToWindow && generateSequence(view as View?) { it.parent as? View }.any { it === root }

        private fun isDescendantOf(view: View, ancestor: View): Boolean =
            generateSequence(view.parent as? View) { it.parent as? View }.any { it === ancestor }

        private fun clickable(view: View): View? = when {
            view.isClickable -> view
            view is ViewGroup -> (0 until view.childCount).asSequence().mapNotNull { clickable(view.getChildAt(it)) }.firstOrNull()
            else -> null
        }

        private fun text(view: View): String = descendants(view).mapNotNull { viewText(it) }.firstOrNull().orEmpty()

        private fun viewText(view: View): String? = when (view) {
            is TextView -> view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            else -> view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun hasBadge(view: View): Boolean = descendants(view).any {
            val name = it.id.takeIf { id -> id != View.NO_ID }?.let { id ->
                runCatching { it.resources.getResourceEntryName(id) }.getOrNull()
            }.orEmpty().lowercase()
            name.contains("badge") || name.contains("unread") || name.contains("notification") || name.contains("dot")
        }

        private fun isSelected(view: View): Boolean = view.isSelected || view.isActivated ||
            (view is ViewGroup && descendants(view).any { it.isSelected || it.isActivated })

        private fun fallbackLabel(name: String): String = when (name) {
            "feed_tab" -> tr("主页", "Home")
            "clips_tab" -> tr("短视频", "Reels")
            "direct_tab" -> tr("消息", "Messages")
            "search_tab" -> tr("搜索", "Search")
            else -> tr("个人资料", "Profile")
        }
    }

    private data class Snapshot(val drawable: Drawable, val stateKey: Int, val bitmap: ImageBitmap?)

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) {
            return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = drawable.intrinsicWidth.takeIf { it > 1 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 1 } ?: 96
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val old = Rect(drawable.bounds)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                drawable.bounds = old
            }
        }.getOrNull()
    }
}
