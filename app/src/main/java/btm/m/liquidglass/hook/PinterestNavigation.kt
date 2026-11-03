package btm.m.liquidglass.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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

/** Verified against 14.42.0. Host objects never cross into the module's Compose runtime. */
internal object PinterestNavigation {
    const val PACKAGE = "com.pinterest"
    const val ACTIVITY = "com.pinterest.activityLibrary.activity.task.activity.MainActivity"

    private class Runtime(loader: ClassLoader) {
        val tab = Class.forName("ia2.z0", false, loader)
        val kind = tab.getMethod("d0")
        val model = tab.getMethod("O")
        private val modelType = Class.forName("af0.y", false, loader)
        val unselectedImage = modelType.getDeclaredField("b")
        val selectedImage = modelType.getDeclaredField("c")
        val label = modelType.getDeclaredField("g")
    }

    fun validate(loader: ClassLoader) { Runtime(loader) }

    data class Item(val view: ViewGroup, val kind: String, val label: String, val icon: ImageBitmap, val avatar: Boolean)
    data class Bar(val container: ViewGroup, val items: List<Item>, val selectedIndex: Int)

    class Detector(private val activity: Activity) {
        private val runtime = Runtime(activity.classLoader)
        private val ids = mutableMapOf<String, Int>()
        private val snapshots = IdentityHashMap<ImageView, Snapshot>()
        private val resourceIcons = mutableMapOf<Int, ImageBitmap>()
        private val moduleContext by lazy {
            activity.createPackageContext("btm.m.os4.systemuihook", Context.CONTEXT_IGNORE_SECURITY)
        }

        fun detect(root: ViewGroup): Bar? = runCatching {
            val bar = find(root, "bottom_nav_bar") as? ViewGroup ?: return null
            if (bar.javaClass.name != "com.pinterest.navigation.view.FloatingBottomNavBar" ||
                !bar.isShown || bar.height <= 0 || bar.translationY >= bar.height
            ) return null
            // L1 adds real z0 views in menu order, including Lilypad and expanded search.
            val tabs = descendants(bar).filterIsInstance<ViewGroup>().filter { runtime.tab.isInstance(it) }.toList()
            if (tabs.size !in 2..5) return null
            val selected = tabs.indexOfFirst { it.isSelected }
            if (selected < 0) return null
            val items = tabs.mapIndexed { index, view ->
                val kind = (runtime.kind.invoke(view) as? Enum<*>)?.name ?: return null
                if (kind !in setOf("HOME", "SEARCH", "CREATE", "NOTIFICATIONS", "PROFILE")) return null
                val model = runtime.model.invoke(view) ?: return null
                val labelId = runtime.label.getInt(model)
                val label = (find(view, "tab_label") as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: activity.getString(labelId)
                val avatarView = if (kind == "PROFILE") (find(view, "tab_avatar") as? ImageView)
                    ?.takeIf { locallyVisible(it, view) && it.drawable != null } else null
                val avatarIcon = avatarView?.let { snapshot(it, avatar = true) }
                val image = listOf("tab_icon_gestalt", "tab_icon").asSequence()
                    .mapNotNull { find(view, it) as? ImageView }
                    .firstOrNull { locallyVisible(it, view) && it.drawable != null }
                val resourceId = (if (index == selected) runtime.selectedImage else runtime.unselectedImage).getInt(model)
                val icon = avatarIcon ?: image?.let { snapshot(it, avatar = false) } ?: resourceIcon(resourceId) ?: return null
                Item(view, kind, tr(label, label), icon, avatarIcon != null)
            }
            if (items.map { it.kind }.distinct().size != items.size) return null
            Bar(bar, items, selected)
        }.getOrNull()

        fun clear() {
            snapshots.clear()
            resourceIcons.clear()
            ids.clear()
        }

        private fun find(root: View, name: String): View? {
            val id = ids.getOrPut(name) { activity.resources.getIdentifier(name, "id", PACKAGE) }
            return if (id == 0) null else root.findViewById(id)
        }

        private fun locallyVisible(image: View, tab: View): Boolean =
            generateSequence(image as View?) { it.parent as? View }.takeWhile { it !== tab }
                .all { it.visibility == View.VISIBLE }

        private fun snapshot(image: ImageView, avatar: Boolean): ImageBitmap? {
            val drawable = image.drawable ?: return null
            if (image.width <= 0 || image.height <= 0) return null
            val now = SystemClock.uptimeMillis()
            val state = drawable.state.contentHashCode()
            val old = snapshots[image]
            if (old?.drawable === drawable && old.state == state &&
                (!avatar || now - old.at < 500L)
            ) return old.icon
            val pixels = runCatching {
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
                    // Draw the actual ImageView so RoundedImageView's avatar crop is preserved.
                    val canvas = Canvas(bitmap)
                    canvas.scale(96f / image.width, 96f / image.height)
                    image.draw(canvas)
                }
            }.getOrNull() ?: return null
            if (old != null && old.pixels.sameAs(pixels)) {
                pixels.recycle()
                snapshots[image] = old.copy(drawable = drawable, state = state, at = now)
                return old.icon
            }
            return pixels.asImageBitmap().also { snapshots[image] = Snapshot(drawable, state, now, pixels, it) }
        }

        private fun resourceIcon(id: Int): ImageBitmap? = resourceIcons[id] ?: runCatching {
            val name = activity.resources.getResourceEntryName(id)
            val drawable = runCatching { activity.getDrawable(id) }.getOrNull() ?: run {
                val fallback = moduleContext.resources.getIdentifier("pinterest_nav_$name", "drawable", moduleContext.packageName)
                if (fallback == 0) return null
                moduleContext.getDrawable(fallback)
            } ?: return null
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
                val bounds = Rect(drawable.bounds)
                try {
                    drawable.setBounds(0, 0, 96, 96)
                    drawable.draw(Canvas(bitmap))
                } finally { drawable.bounds = bounds }
            }.asImageBitmap().also { resourceIcons[id] = it }
        }.getOrNull()
    }

    private data class Snapshot(val drawable: Drawable, val state: Int, val at: Long, val pixels: Bitmap, val icon: ImageBitmap)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
