package btm.m.liquidglass.hook

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import java.util.WeakHashMap

/**
 * Native bottom navigation adapter for JD (com.jingdong.app.mall).
 * Supports Classic (wa0.k), AI Floating (wa0.e), and AI Flatten (wa0.a) bars
 * rendered by JDNavigationFragment / NavigationGroup / AiNavigationGroup.
 */
internal class JdNavigation(private val activity: Activity) {
    data class Bar(
        val container: ViewGroup,
        val hiddenLayers: List<View>,
        val mainContent: ViewGroup?,
        val blurTarget: ViewGroup?,
        val isAiMode: Boolean,
        val items: List<Item>,
        val selectedIndex: Int,
    )

    data class Item(
        val view: View,
        val clickTarget: View,
        val identity: String,
        val navId: Int,
        val label: String,
        val icon: ImageBitmap?,
        val preserveColor: Boolean,
        val badge: Boolean,
    )

    private var lastTreeSearchAt = 0L
    private var cachedGroup: ViewGroup? = null
    private val icons = WeakHashMap<View, IconSnapshot>()
    private val fallbackDrawables = HashMap<String, Drawable?>()

    fun clear() {
        lastTreeSearchAt = 0L
        cachedGroup = null
        icons.clear()
        fallbackDrawables.clear()
    }

    fun detect(content: ViewGroup): Bar? {
        val now = SystemClock.uptimeMillis()
        val group = cachedGroup?.takeIf { isValidGroup(it, content) } ?: run {
            if (now - lastTreeSearchAt < 500L) return null
            lastTreeSearchAt = now
            findNavigationGroup(content)?.also { cachedGroup = it } ?: return null
        }
        return parse(content, group)
    }

    fun open(content: ViewGroup, identity: String): Boolean {
        val bar = detect(content) ?: return false
        val item = bar.items.firstOrNull { it.identity == identity } ?: return false
        val target = item.clickTarget
        if (!target.isAttachedToWindow || !target.isShown || !target.isEnabled) return false
        if (dispatchSyntheticTap(target)) return true
        return target.callOnClick() || target.performClick()
    }

    private fun dispatchSyntheticTap(target: View): Boolean {
        val now = SystemClock.uptimeMillis()
        val x = (target.width / 2f).coerceAtLeast(1f)
        val y = (target.height / 2f).coerceAtLeast(1f)
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val downHandled = target.dispatchTouchEvent(down)
            val upHandled = target.dispatchTouchEvent(up)
            downHandled || upHandled
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun parse(content: ViewGroup, group: ViewGroup): Bar? {
        val buttons = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { it.isShown && isInstanceOf(it, BUTTON_CLASS) }
        if (buttons.size !in 2..8) return null

        val checkId = invokeInt(group, "getCheckId") ?: fieldInt(group, "checkId")
        val density = content.resources.displayMetrics.density
        val maxChromeHeight = (200f * density).toInt()
        val hierarchy = resolveHierarchy(content, group, maxChromeHeight) ?: return null

        val navIds = buttons.mapIndexed { idx, btn -> resolveNavId(btn, idx) }
        val uniqueNavIds = navIds.distinct().size == navIds.size

        val rawSelection = buttons.mapIndexed { idx, btn ->
            val navId = navIds[idx]
            val touchView = invokeView(btn, "getTouchView")
            val byCheckId = checkId != null && checkId >= 0 && navId == checkId
            val byState = fieldBoolean(btn, "currentState") == true || touchView?.isSelected == true
            byCheckId to byState
        }
        val selectedIndex = rawSelection.indexOfFirst { it.first }.takeIf { it >= 0 }
            ?: rawSelection.indices.filter { rawSelection[it].second }.singleOrNull()
            ?: 0

        val items = buttons.mapIndexed { index, button ->
            val navId = navIds[index]
            val label = resolveLabel(button, navId) ?: return null
            val clickTarget = invokeView(button, "getTouchView")
                ?.takeIf { it.isShown && it.isEnabled }
                ?: button
            val selected = index == selectedIndex
            val iconBitmap = resolveButtonIcon(button, navId, selected, hierarchy.isAiMode)
            val preserveColor = invokeBoolean(button, "isDefaultIcon") == false
            val badge = hasVisibleBadge(button)
            val identity = if (uniqueNavIds) "jd:$navId" else "jd:$navId:$index"
            Item(
                view = button,
                clickTarget = clickTarget,
                identity = identity,
                navId = navId,
                label = label,
                icon = iconBitmap,
                preserveColor = preserveColor,
                badge = badge,
            )
        }
        return Bar(
            container = hierarchy.container,
            hiddenLayers = hierarchy.hiddenLayers,
            mainContent = hierarchy.mainContent,
            blurTarget = hierarchy.blurTarget,
            isAiMode = hierarchy.isAiMode,
            items = items,
            selectedIndex = selectedIndex,
        )
    }

    private data class Hierarchy(
        val container: ViewGroup,
        val hiddenLayers: List<View>,
        val mainContent: ViewGroup?,
        val blurTarget: ViewGroup?,
        val isAiMode: Boolean,
    )

    private fun resolveHierarchy(content: ViewGroup, group: ViewGroup, maxChromeHeight: Int): Hierarchy? {
        var current: View = group
        var parent = current.parent as? ViewGroup
        var aiFlag = group.javaClass.name.contains("Ai", ignoreCase = true)
        var mainFrameRoot: ViewGroup? = null
        var barHostInMain: ViewGroup? = null
        var hops = 0
        while (parent != null && hops < 8) {
            if (current.javaClass.name.contains("Ai", ignoreCase = true) ||
                current.javaClass.name.contains("NavBoxContainer", ignoreCase = true)
            ) {
                aiFlag = true
            }
            val isMainFrame = parent.javaClass.name.contains("MainFrameLayout") ||
                (parent.parent === content && parent is RelativeLayout)
            if (isMainFrame && current is ViewGroup) {
                mainFrameRoot = parent
                barHostInMain = current
                break
            }
            if (parent === content) break
            current = parent
            parent = current.parent as? ViewGroup
            hops++
        }

        val container = barHostInMain ?: (group.parent as? ViewGroup) ?: group
        val hostParent = mainFrameRoot ?: (container.parent as? ViewGroup) ?: return Hierarchy(
            container = container,
            hiddenLayers = listOf(container),
            mainContent = null,
            blurTarget = null,
            isAiMode = aiFlag,
        )

        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        val contentBottom = contentLocation[1] + content.height
        val hidden = linkedSetOf<View>(container)
        var mainContent: ViewGroup? = null
        var blurTarget: ViewGroup? = null

        for (i in 0 until hostParent.childCount) {
            val child = hostParent.getChildAt(i)
            if (child === container || child is ComposeView) continue
            if (child is ViewGroup && child.height >= content.height / 2 && child.width >= content.width / 2) {
                if (child.javaClass.name.contains("JdBlurTarget", ignoreCase = true)) {
                    blurTarget = child
                    val inner = (0 until child.childCount)
                        .map { child.getChildAt(it) }
                        .filterIsInstance<ViewGroup>()
                        .maxByOrNull { it.width.toLong() * it.height.toLong() }
                    mainContent = inner ?: child
                    aiFlag = true
                } else if (mainContent == null) {
                    mainContent = child
                }
                continue
            }
            if (child.height <= maxChromeHeight) {
                val loc = IntArray(2).also(child::getLocationInWindow)
                if (child.height == 0 || loc[1] + child.height >= contentBottom - maxChromeHeight) {
                    hidden.add(child)
                }
            }
        }
        return Hierarchy(
            container = container,
            hiddenLayers = hidden.toList(),
            mainContent = mainContent,
            blurTarget = blurTarget,
            isAiMode = aiFlag,
        )
    }

    private fun resolveNavId(button: View, fallbackIndex: Int): Int {
        invokeInt(button, "getNavigationId")?.let { return it }
        if (button.id != View.NO_ID && button.id >= 0) return button.id
        return fallbackIndex
    }

    private fun resolveLabel(button: View, navId: Int): String? {
        (invoke(button, "getLabel") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val text = views(button)
            .filterIsInstance<TextView>()
            .filter { it.isShown && !it.javaClass.name.contains("RedPoint", ignoreCase = true) }
            .mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull { !it.all(Char::isDigit) }
        if (text != null) return text
        return defaultLabel(navId)
    }

    private fun resolveButtonIcon(button: View, navId: Int, selected: Boolean, isAiMode: Boolean): ImageBitmap? {
        val iconView = (invokeView(button, "getNaviIconView") as? ImageView)
            ?: views(button).filterIsInstance<ImageView>()
                .firstOrNull { it.drawable != null && !it.javaClass.name.contains("RedPoint", ignoreCase = true) }
        val drawable = iconView?.drawable
        if (drawable != null) {
            if (isRadioStateDrawable(drawable)) {
                val cleanBitmap = extractRadioStateBitmap(drawable)
                if (cleanBitmap != null && !cleanBitmap.isRecycled) {
                    return snapshotBitmap(button, drawable, cleanBitmap)
                }
            }
            iconFromDrawable(button, drawable, iconView.imageTintList)?.let { return it }
        }
        val fallbackName = defaultIconName(navId, selected, isAiMode)
        val fallbackDrawable = fallback(fallbackName, button.drawableState)
        return iconFromDrawable(button, fallbackDrawable, null)
    }

    private fun snapshotBitmap(keyView: View, drawable: Drawable, bitmap: Bitmap): ImageBitmap? {
        val current = drawable.current
        val state = bitmap.generationId
        val theme = keyView.resources.configuration.uiMode
        val now = SystemClock.uptimeMillis()
        icons[keyView]?.takeIf {
            it.drawable === drawable && it.current === current && it.state == state && it.theme == theme
        }?.let { return it.bitmap }
        val edge = maxOf(bitmap.width, bitmap.height).coerceIn(48, 192)
        val imageBitmap = runCatching {
            if (bitmap.width == edge && bitmap.height == edge && bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap.asImageBitmap()
            } else {
                val scale = edge.toFloat() / maxOf(bitmap.width, bitmap.height)
                val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { out ->
                    val canvas = Canvas(out)
                    val dst = Rect((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                    canvas.drawBitmap(bitmap, null, dst, null)
                }.asImageBitmap()
            }
        }.getOrNull()
        icons[keyView] = IconSnapshot(drawable, current, state, theme, now, imageBitmap)
        return imageBitmap
    }

    private fun iconFromDrawable(keyView: View, drawable: Drawable?, tintList: ColorStateList?): ImageBitmap? {
        drawable ?: return null
        val current = drawable.current
        val state = drawable.state.contentHashCode()
        val theme = keyView.resources.configuration.uiMode
        val now = SystemClock.uptimeMillis()
        icons[keyView]?.takeIf {
            it.drawable === drawable && it.current === current && it.state == state && it.theme == theme &&
                (drawable !is Animatable || now - it.at < 250L)
        }?.let { return it.bitmap }

        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled && tintList == null) {
            return snapshotBitmap(keyView, drawable, drawable.bitmap)
        }

        val bitmap = runCatching {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            val edge = maxOf(width, height).coerceIn(48, 192)
            val scale = edge.toFloat() / maxOf(width, height)
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { output ->
                val old = Rect(drawable.bounds)
                try {
                    drawable.setBounds((edge - w) / 2, (edge - h) / 2, (edge + w) / 2, (edge + h) / 2)
                    drawable.draw(Canvas(output))
                } finally {
                    drawable.bounds = old
                }
            }.asImageBitmap()
        }.getOrNull()
        icons[keyView] = IconSnapshot(drawable, current, state, theme, now, bitmap)
        return bitmap
    }

    private fun hasVisibleBadge(button: View): Boolean {
        val redPoint = fieldView(button, "redPoint") ?: return false
        return redPoint.isShown && redPoint.width > 0 && redPoint.height > 0
    }

    private fun fallback(name: String?, state: IntArray): Drawable? {
        name ?: return null
        val key = "$name:${state.contentHashCode()}:${activity.resources.configuration.uiMode}"
        if (!fallbackDrawables.containsKey(key)) {
            if (fallbackDrawables.size >= 32) fallbackDrawables.clear()
            fallbackDrawables[key] = runCatching {
                val id = activity.resources.getIdentifier(name, "drawable", activity.packageName)
                if (id == 0) null else activity.getDrawable(id)?.mutate()?.also { it.state = state }
            }.getOrNull()
        }
        return fallbackDrawables[key]
    }

    private fun findNavigationGroup(content: ViewGroup): ViewGroup? {
        val candidates = views(content)
            .filterIsInstance<ViewGroup>()
            .filter { isValidGroup(it, content) }
            .toList()
        return candidates.maxByOrNull { group ->
            IntArray(2).also(group::getLocationInWindow)[1]
        }
    }

    private fun isValidGroup(view: View?, content: ViewGroup): Boolean {
        if (view !is ViewGroup) return false
        if (!isInstanceOf(view, BAR_CLASS) || !view.isAttachedToWindow || !view.isShown) return false
        if (view.width < content.width / 2 || view.height <= 0) return false
        if (content.height > 0) {
            val contentLoc = IntArray(2).also(content::getLocationInWindow)
            val viewLoc = IntArray(2).also(view::getLocationInWindow)
            val contentBottom = contentLoc[1] + content.height
            if (viewLoc[1] + view.height < contentBottom - content.height / 3) return false
        }
        val visibleButtons = (0 until view.childCount)
            .map { view.getChildAt(it) }
            .count { it.isShown && isInstanceOf(it, BUTTON_CLASS) }
        return visibleButtons in 2..8
    }

    private fun isRadioStateDrawable(drawable: Drawable): Boolean {
        var c: Class<*>? = drawable.javaClass
        while (c != null) {
            if (c.name == RADIO_STATE_DRAWABLE_CLASS) return true
            c = c.superclass
        }
        return false
    }

    private fun extractRadioStateBitmap(drawable: Drawable): Bitmap? = runCatching {
        var c: Class<*>? = drawable.javaClass
        while (c != null) {
            if (c.name == RADIO_STATE_DRAWABLE_CLASS) {
                val field = c.getDeclaredField("bitmap").apply { isAccessible = true }
                return@runCatching field.get(drawable) as? Bitmap
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun isInstanceOf(obj: Any, className: String): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            if (c.name == className) return true
            c = c.superclass
        }
        return false
    }

    private fun invoke(receiver: Any, name: String): Any? = runCatching {
        receiver.javaClass.getMethod(name).invoke(receiver)
    }.getOrNull()

    private fun invokeInt(receiver: Any, name: String): Int? = invoke(receiver, name) as? Int
    private fun invokeBoolean(receiver: Any, name: String): Boolean? = invoke(receiver, name) as? Boolean
    private fun invokeView(receiver: Any, name: String): View? = invoke(receiver, name) as? View

    private fun fieldInt(receiver: Any, name: String): Int? = runCatching {
        var c: Class<*>? = receiver.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return@runCatching f.get(receiver) as? Int
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun fieldBoolean(receiver: Any, name: String): Boolean? = runCatching {
        var c: Class<*>? = receiver.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return@runCatching f.get(receiver) as? Boolean
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

    private fun defaultLabel(navId: Int): String? = when (navId) {
        0 -> "首页"
        1, 12 -> "分类"
        2 -> "逛"
        11 -> "消息"
        3 -> "购物车"
        4 -> "我的"
        else -> null
    }

    private fun defaultIconName(navId: Int, selected: Boolean, isAiMode: Boolean): String? = when (navId) {
        0 -> if (isAiMode) (if (selected) "cze" else "czd") else (if (selected) "cux" else "cuy")
        1, 12 -> if (isAiMode) (if (selected) "cz9" else "cz8") else (if (selected) "cvh" else "cvg")
        2 -> if (isAiMode) (if (selected) "czb" else "cza") else (if (selected) "cuu" else "cuv")
        11 -> if (isAiMode) (if (selected) "czh" else "czg") else (if (selected) "cv1" else "cv0")
        3 -> if (isAiMode) (if (selected) "cz6" else "cz5") else (if (selected) "cur" else "cus")
        4 -> if (isAiMode) (if (selected) "czk" else "czj") else (if (selected) "cv3" else "cv4")
        else -> null
    }

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
        const val PACKAGE = "com.jingdong.app.mall"
        const val ACTIVITY = "com.jingdong.app.mall.MainFrameActivity"
        private const val BAR_CLASS = "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup"
        private const val BUTTON_CLASS = "com.jingdong.common.unification.navigationbar.newbar.NavigationButton"
        private const val RADIO_STATE_DRAWABLE_CLASS = "com.jingdong.common.unification.navigationbar.RadioStateDrawable"
    }
}
