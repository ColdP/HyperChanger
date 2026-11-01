// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Gravity
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * HyperiOS-style real 2D layoutChunk, limited to the stock five upper controls.
 * All RecyclerView objects belong to the plugin loader; only Android View crosses loaders.
 * No record reordering, replacement views, frame translations or secondary router hooks.
 */
internal class ControlCenterTileLayoutHook(private val module: XposedModule) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val managerInstalled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val managers = WeakHashMap<Any, WeakReference<Any>>()
    private val panels = WeakHashMap<Any, WeakReference<ViewGroup>>()
    private val adapted = WeakHashMap<View, CcTile>()
    private val sliderSizes = WeakHashMap<View, Pair<Int, Int>>()
    private val mediaProgress = WeakHashMap<View, WeakReference<MediaProgressView>>()
    private val mediaPlaceholders = WeakHashMap<View, WeakReference<View>>()
    private val mediaHolders = WeakHashMap<View, WeakReference<Any>>()
    private val mediaInstalled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val childLayouts = mutableSetOf<Method>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settings = CcTileLayoutSettings()

    fun install(loader: ClassLoader, p: SharedPreferences) {
        if (!p.getBoolean(KEY_CC_TILE_LAYOUT_ENABLED, false)) return
        runCatching { loader.loadClass(ADAPTER) }.getOrNull()?.let { onClassLoaded(it, p) }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, p: SharedPreferences) {
        if (clazz.name != ADAPTER || !p.getBoolean(KEY_CC_TILE_LAYOUT_ENABLED, false) || !scheduled.add(clazz)) return
        // Do not enumerate target methods from a ClassLoader.loadClass callback (ART crash audit).
        handler.post {
            runCatching { installAdapter(clazz, p) }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, "HyperChanger", "Could not install upper tile layout", it)
            }
        }
    }

    private fun installAdapter(clazz: Class<*>, p: SharedPreferences) {
        if (!installed.add(clazz)) return
        settings = readCcTileLayoutSettings(p)
        // This runs on the main handler, outside the ClassLoader discovery stack.
        listOf(MEDIA_HOLDER, MEDIA_ANIMATOR).forEach { name ->
            runCatching { installMediaHooks(clazz.classLoader!!.loadClass(name)) }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, "HyperChanger", "Could not install media layout hook: $name", it)
            }
        }
        if (preferenceListener == null) {
            preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == KEY_CC_TILE_LAYOUT || key == KEY_CC_TILE_LAYOUT_ENABLED || key == KEY_CC_MEDIA_SOURCE_ICON_HIDDEN) {
                    handler.post {
                        settings = readCcTileLayoutSettings(p)
                        if (!settings.enabled) adapted.entries.toList().filter { it.value.id == "media" }
                            .forEach { clearMedia(it.key) }
                        panels.entries.toList().forEach { (_, reference) ->
                            reference.get()?.let { rv ->
                                // Drop stale saved anchors after dimensions/order change; OEM lays out from top.
                                call(rv, "scrollToPosition", 0)
                                rv.requestLayout()
                            }
                        }
                    }
                }
            }.also(p::registerOnSharedPreferenceChangeListener)
        }
        clazz.declaredMethods.filter { it.name == "onAttachedToRecyclerView" || it.name == "onDetachedFromRecyclerView" }.forEach { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:${method.name}").intercept { chain ->
                val result = chain.proceed()
                val adapter = chain.thisObject
                val rv = chain.getArg(0) as? ViewGroup
                if (adapter != null && rv != null) {
                    if (method.name == "onAttachedToRecyclerView") {
                        registerPanel(adapter, rv)
                    } else {
                        val manager = field(adapter, "layoutManager")
                        if (manager != null) managers.remove(manager)
                        panels.remove(adapter)
                    }
                }
                result
            }
        }
        // Deferred class-load installation may run after the first attachment. Recover that
        // existing instance at its ordinary frame callback, without replacing frame behavior.
        clazz.getDeclaredMethod("onFrameCallback").let { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:existing-panel").intercept { chain ->
                val result = chain.proceed()
                val adapter = chain.thisObject
                if (adapter != null && panels[adapter]?.get() == null) {
                    (field(adapter,"recyclerView") as? ViewGroup)?.let { rv ->
                        registerPanel(adapter,rv)
                        rv.post { rv.requestLayout() }
                    }
                }
                result
            }
        }
    }

    private fun registerPanel(adapter: Any, rv: ViewGroup) {
        val manager = field(adapter,"layoutManager") ?: return
        managers[manager] = WeakReference(adapter)
        panels[adapter] = WeakReference(rv)
        installManager(manager.javaClass)
        installOrientationHelper(manager)
    }

    private fun mediaRoot(holder: Any?): View? = call(holder, "getContentView") as? View

    private fun customMedia(root: View?): Boolean = settings.enabled && root != null && adapted[root]?.id == "media"

    private fun mainMedia(root: View): Boolean {
        if (!customMedia(root)) return false
        var parent: android.view.ViewParent? = root.parent
        while (parent is View) {
            val group = parent as? ViewGroup
            val adapter = group?.let { call(it, "getAdapter") }
            if (adapter != null) {
                return panels[adapter]?.get() === group &&
                    field(adapter, "mode")?.toString() == "NORMAL" &&
                    field(adapter, "style")?.toString() == "VERTICAL"
            }
            parent = parent.parent
        }
        return false
    }

    private fun wideMedia(root: View): Boolean = adapted[root]?.let { it.w >= 3 && it.h >= 2 } == true

    private fun clearMedia(root: View) {
        mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
        mediaPlaceholders.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
        find(root, "device_icon")?.visibility = View.VISIBLE
        find(root, "cover")?.invalidateOutline()
    }

    private fun coverRadius(holder: Any?, cover: View): Float? {
        val radius = call(holder, "getCornerRadius") as? Float ?: return null
        if (cover.width <= 0 || cover.height <= 0) return null
        // Concentric inset corners share the same centre, including with the module radius offset.
        return (radius - minOf(cover.left, cover.top).coerceAtLeast(0))
            .coerceIn(0f, minOf(cover.width, cover.height) / 2f)
    }

    private inner class MediaCoverOutline(root: View, private val original: ViewOutlineProvider?) : ViewOutlineProvider() {
        private val rootRef = WeakReference(root)
        override fun getOutline(view: View, outline: Outline) {
            val root = rootRef.get()
            val radius = if (root != null && mainMedia(root)) coverRadius(mediaHolders[root]?.get(), view) else null
            if (radius == null) original?.getOutline(view, outline)
            else outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun syncCoverOutline(root: View) {
        val cover = find(root, "cover") ?: return
        if (cover.outlineProvider !is MediaCoverOutline) cover.outlineProvider = MediaCoverOutline(root, cover.outlineProvider)
        cover.invalidateOutline()
    }

    private fun installMediaHooks(clazz: Class<*>) {
        if (!mediaInstalled.add(clazz)) return
        if (clazz.name == MEDIA_ANIMATOR) {
            installMediaTransition(clazz)
            return
        }
        if (clazz.name == MEDIA_HOLDER) {
            clazz.getDeclaredMethod("getCoverRadius").let { m ->
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:cover-radius").intercept { chain ->
                    val original = chain.proceed()
                    val root = mediaRoot(chain.thisObject)
                    val cover = root?.let { find(it, "cover") }
                    if (root != null && mainMedia(root) && cover != null) coverRadius(chain.thisObject, cover) ?: original else original
                }
            }
            clazz.declaredMethods.filter { it.name in setOf("setCornerRadius", "updateRadius", "updateMetaData") }.forEach { m ->
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:cover-invalidate:${m.name}").intercept { chain ->
                    val result = chain.proceed()
                    mediaRoot(chain.thisObject)?.let { root ->
                        chain.thisObject?.let { mediaHolders[root] = WeakReference(it) }
                        if (mainMedia(root)) {
                            syncCoverOutline(root)
                            syncEmptyMedia(root)
                        }
                    }
                    result
                }
            }
        }
    }

    /** The stock animator owns the clock and snapshots; only correct its custom-card endpoints. */
    private fun installMediaTransition(clazz: Class<*>) {
        val frame = clazz.getDeclaredMethod("frameCallback")
        runCatching { module.deoptimize(frame) }
        module.hook(frame).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:media-transition").intercept { chain ->
            val result = chain.proceed()
            val animator = chain.thisObject
            val root = mediaRoot(field(animator, "fromView"))
            val snapshot = field(animator, "animValue")
            val fraction = field(animator, "size") as? Float
            val delegate = field(animator, "contentController")
            if (root != null && customMedia(root) && wideMedia(root) && snapshot != null && fraction != null && delegate != null) {
                // No second ValueAnimator and no scaling around a different pivot at hand-off.
                listOf("Prev", "Play", "Next").forEach { name ->
                    val start = call(snapshot, "getFromAudio$name")
                    val end = call(snapshot, "getToAudio$name")
                    val button = call(delegate, "getAv${name}View") as? View
                    if (start != null && end != null && button != null) {
                        val a = snapshotRect(start); val b = snapshotRect(end)
                        val rect = interpolate(a, b, fraction)
                        button.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                    }
                }
                val progress = mediaProgress[root]?.get()
                val expanded = call(delegate, "getProgressContainerView") as? View
                val end = call(snapshot, "getToProgressContainer")
                if (progress != null && expanded != null && end != null) {
                    val parent = expanded.parent as? View
                    if (parent != null) {
                        // Map the small row into the animated parent's current coordinate space.
                        // Both endpoints show the same row, so it stays visible during the morph.
                        val start = mapRect(root, RectF(progress.left.toFloat(), progress.top.toFloat(), progress.right.toFloat(), progress.bottom.toFloat()), parent)
                        if (start != null) {
                            val target = snapshotRect(end)
                            val rect = interpolate(start, target, fraction)
                            expanded.pivotX = 0f; expanded.pivotY = 0f
                            expanded.scaleX = rect.width() / target.width().coerceAtLeast(1f)
                            expanded.scaleY = rect.height() / target.height().coerceAtLeast(1f)
                            val x = rect.left.roundToInt(); val y = rect.top.roundToInt()
                            expanded.layout(x, y, x + target.width().roundToInt(), y + target.height().roundToInt())
                            expanded.alpha = if (find(root, "empty_state")?.visibility == View.VISIBLE) 0f else 1f
                            readMediaProgress(root)?.let { state ->
                                (find(expanded, "media_progress_bar") as? android.widget.SeekBar)?.let { bar ->
                                    if (!bar.isPressed) bar.progress = (state.first * bar.max).roundToInt()
                                }
                            }
                        }
                    }
                }
                val device = call(delegate, "getDeviceIconView") as? View
                val source = find(root, "device_icon")
                if (device != null && source != null) {
                    // This icon is hidden at the expanded endpoint by OEM; interpolate its alpha.
                    val alpha = if (settings.mediaSourceIconHidden) 0f else 1f - fraction.coerceIn(0f, 1f)
                    device.alpha = alpha
                    if (alpha > 0f) {
                        val parent = device.parent as? View
                        val rect = parent?.let { mapRect(root, RectF(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat()), it) }
                        if (rect != null) device.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                    }
                }
            }
            result
        }
    }

    private fun snapshotRect(value: Any): RectF {
        val x = (call(value, "getLeft") as Number).toFloat(); val y = (call(value, "getTop") as Number).toFloat()
        return RectF(x, y, x + (call(value, "getWidth") as Number).toFloat(), y + (call(value, "getHeight") as Number).toFloat())
    }

    private fun interpolate(a: RectF, b: RectF, f: Float) = RectF(a.left + (b.left-a.left)*f, a.top + (b.top-a.top)*f,
        a.right + (b.right-a.right)*f, a.bottom + (b.bottom-a.bottom)*f)

    private fun mapRect(from: View, rect: RectF, to: View): RectF? {
        val fromMatrix = Matrix(); from.transformMatrixToGlobal(fromMatrix)
        val toMatrix = Matrix(); to.transformMatrixToGlobal(toMatrix)
        val inverse = Matrix()
        if (!toMatrix.invert(inverse)) return null
        return RectF(rect).also { fromMatrix.mapRect(it); inverse.mapRect(it) }
    }

    private data class Block(val start: Int, val end: Int, val ids: Map<Int, String>)

    /** Reject any unfamiliar structure rather than moving lower tiles or mixed panel contents. */
    private fun block(adapter: Any, manager: Any): Block? {
        if (!settings.enabled || field(adapter, "mode")?.toString() != "NORMAL" || field(adapter, "style")?.toString() != "VERTICAL") return null
        if ((call(manager, "getSpanCount") as? Int) != 4 || (call(manager, "getOrientation") as? Int) != 1) return null
        val content = field(adapter, "contentMap") as? Map<*, *> ?: return null
        var index = 0
        val ids = linkedMapOf<Int, String>()
        content.forEach { (owner, raw) ->
            (raw as? List<*>)?.forEach { item ->
                val id = when (owner?.javaClass?.simpleName) {
                    "QSCardsController" -> when (call(item, "getSpec")) { "wifi" -> "wlan"; "cell" -> "data"; else -> null }
                    "MediaPlayerController" -> "media"
                    "BrightnessSliderController" -> "brightness"
                    "VolumeSliderController" -> "volume"
                    else -> null
                }
                if (id != null) ids[index] = id
                index++
            }
        }
        if (ids.size != 5 || ids.values.toSet() != CcTileLayouts.initial.map { it.id }.toSet()) return null
        val start = ids.keys.min(); val end = ids.keys.max()
        return if (end - start == 4) Block(start, end, ids) else null
    }

    private fun installManager(clazz: Class<*>) {
        val chunk = method(clazz, "layoutChunk", 4) ?: return
        if (!managerInstalled.add(clazz)) return
        method(clazz, "onLayoutChildren", 2)?.let { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:restore-oem").intercept { chain ->
                val manager = chain.thisObject
                val adapter = managers[manager]?.get()
                if (manager != null && adapter != null && block(adapter, manager) == null) {
                    val rv = panels[adapter]?.get()
                    sliderSizes.entries.toList().forEach { (slider, size) ->
                        var parent = slider.parent
                        while (parent is View && parent !== rv) parent = parent.parent
                        if (parent === rv) {
                            slider.layoutParams = slider.layoutParams.apply { width = size.first; height = size.second }
                            sliderSizes.remove(slider)
                        }
                    }
                    adapted.keys.toList().filter { it.parent === rv }.forEach { root ->
                        if (adapted[root]?.id == "media") clearMedia(root)
                        adapted.remove(root)
                    }
                }
                chain.proceed()
            }
        }
        module.hook(chunk).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:2d-chunk").intercept { chain ->
            val manager = chain.thisObject
            val adapter = managers[manager]?.get()
            val plan = if (manager != null && adapter != null) block(adapter, manager) else null
            val state = chain.getArg(1)
            val layout = chain.getArg(2)
            val result = chain.getArg(3)
            if (plan == null || state == null || layout == null || result == null ||
                call(state, "isPreLayout") == true || field(layout, "mScrapList") != null ||
                (field(layout, "mCurrentPosition") as? Int) !in plan.start..plan.end) {
                chain.proceed()
            } else {
                layoutBlock(manager!!, adapter!!, plan, chain.getArg(0)!!, layout, result)
                null
            }
        }
        // A recycled item can be the anchor anywhere inside the block. Normalize it to the
        // block edge and preserve its pixel origin; GridLayoutManager cannot infer 2D rows.
        method(clazz, "onAnchorReady", 4)?.let { anchorMethod ->
            module.hook(anchorMethod).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:anchor").intercept { chain ->
                val result = chain.proceed()
                val manager = chain.thisObject
                val adapter = managers[manager]?.get()
                val plan = if (manager != null && adapter != null) block(adapter, manager) else null
                val anchor = chain.getArg(2)
                if (plan != null && anchor != null) {
                    val position = field(anchor, "mPosition") as? Int ?: -1
                    if (position in plan.start..plan.end) {
                        // OrientationHelper already reports the complete block edge.
                        set(anchor, "mPosition", if ((chain.getArg(3) as Int) == 1) plan.start else plan.end)
                    }
                }
                result
            }
        }
        method(clazz, "supportsPredictiveItemAnimations", 0)?.let { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:predictive").intercept { chain ->
                val manager = chain.thisObject
                val adapter = managers[manager]?.get()
                if (manager != null && adapter != null && block(adapter, manager) != null) false else chain.proceed()
            }
        }
    }

    /** Treat the five real children as one recycling/scrolling extent. This prevents
     * recycling an early adapter record while a later record is visually above it. */
    private fun installOrientationHelper(manager: Any) {
        call(manager, "ensureLayoutState")
        val helper = field(manager, "mOrientationHelper") ?: return
        if (!installed.add(helper.javaClass)) return
        helper.javaClass.declaredMethods.filter {
            it.name in setOf("getDecoratedStart", "getDecoratedEnd", "getTransformedStartWithDecoration", "getTransformedEndWithDecoration") && it.parameterCount == 1
        }.forEach { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:extent:${m.name}").intercept { chain ->
                val original = chain.proceed() as Int
                val lm = field(chain.thisObject, "mLayoutManager")
                val adapter = managers[lm]?.get()
                val view = chain.getArg(0) as? View
                val t = adapted[view]
                if (lm != null && adapter != null && t != null && view?.parent === panels[adapter]?.get() && block(adapter,lm) != null) {
                    val pitch = step(lm)
                    if (m.name.contains("Start")) original - t.row * pitch
                    else original + (CcTileLayouts.rows(settings.tiles) - t.row - t.h) * pitch
                } else original
            }
        }
    }

    private fun step(manager: Any): Int = ((call(manager, "getWidth") as Int) -
        (call(manager, "getPaddingLeft") as Int) - (call(manager, "getPaddingRight") as Int)) / 4

    private fun layoutBlock(manager: Any, adapter: Any, block: Block, recycler: Any, layout: Any, result: Any) {
        val tileSnapshot = settings.tiles
        val pitch = step(manager).coerceAtLeast(1)
        val total = CcTileLayouts.rows(tileSnapshot) * pitch
        val forward = (field(layout, "mItemDirection") as Int) == 1
        val towardEnd = (field(layout, "mLayoutDirection") as Int) == 1
        val offset = field(layout, "mOffset") as Int
        val rv = panels[adapter]?.get() ?: error("Detached upper tile layout")
        val left = call(manager, "getPaddingLeft") as Int
        val top = if (towardEnd) offset else offset - total
        val rtl = rv.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Continue through the complete five-item block even when the scroll anchor lies inside it.
        set(layout, "mCurrentPosition", if (forward) block.start else block.end)
        val positions = if (forward) (block.start..block.end).toList() else (block.end downTo block.start).toList()
        var focusable = false
        positions.forEach { position ->
            val view = call(layout, "next", recycler) as? View ?: error("Missing upper tile at $position")
            val tile = tileSnapshot.first { it.id == block.ids.getValue(position) }
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            set(params, "mSpanIndex", tile.col); set(params, "mSpanSize", tile.w)
            if (forward) call(manager, "addView", view) else call(manager, "addView", view, 0)
            val decoration = Rect()
            call(manager, "calculateItemDecorationsForChild", view, decoration)
            val width = (tile.w * pitch - params.leftMargin - params.rightMargin - decoration.left - decoration.right).coerceAtLeast(1)
            val height = (tile.h * pitch - params.topMargin - params.bottomMargin - decoration.top - decoration.bottom).coerceAtLeast(1)
            prepareChild(view, tile, width, height)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            val col = if (rtl) 4 - tile.col - tile.w else tile.col
            val x = left + col * pitch; val y = top + tile.row * pitch
            call(manager, "layoutDecoratedWithMargins", view, x, y, x + tile.w * pitch, y + tile.h * pitch)
            focusable = focusable || view.hasFocusable()
        }
        set(layout, "mCurrentPosition", if (forward) block.end + 1 else block.start - 1)
        set(result, "mConsumed", total)
        set(result, "mFinished", false)
        set(result, "mIgnoreConsumed", false)
        set(result, "mFocusable", focusable)
    }

    private fun prepareChild(view: View, tile: CcTile, width: Int, height: Int) {
        adapted[view] = tile
        if (tile.id == "media") (view.parent as? ViewGroup)?.let { parent ->
            call(parent, "getChildViewHolder", view)?.let { mediaHolders[view] = WeakReference(it) }
        }
        // VerticalSeekBar is rotated 270 degrees: its unrotated touch dimensions are swapped.
        if (tile.slider) find(view, "slider")?.let { slider ->
            sliderSizes.putIfAbsent(slider, slider.layoutParams.width to slider.layoutParams.height)
            slider.layoutParams = slider.layoutParams.apply { this.width = height; this.height = width }
        }
        val layout = method(view.javaClass, "onLayout", 5) ?: return
        if (childLayouts.add(layout)) {
            module.hook(layout).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:content:${layout.declaringClass.name}").intercept { chain ->
                val result = chain.proceed()
                val root = chain.thisObject as? View
                val t = adapted[root]
                val parent = root?.parent
                val adapter = if (parent is ViewGroup) call(parent, "getAdapter") else null
                if (root != null && t != null && adapter != null && panels[adapter]?.get() === parent &&
                    settings.enabled && field(adapter, "mode")?.toString() == "NORMAL" && field(adapter, "style")?.toString() == "VERTICAL") {
                    if (t.id == "media") layoutMedia(root, t) else if (!t.slider) layoutCard(root, t)
                }
                result
            }
        }
    }

    /** Keep the OEM children/listeners; resize their actual boxes instead of scaling touch targets. */
    private fun layoutCard(root: View, t: CcTile) {
        val content = find(root, "scale_content") as? ViewGroup ?: return
        val label = find(root, "label_container") ?: return
        val icon = (0 until content.childCount).map(content::getChildAt).firstOrNull { it !== label } ?: return
        val d = root.resources.displayMetrics.density
        val size = minOf((32 * d).roundToInt(), (root.width * .42f).roundToInt())
        val inset = (12 * d).roundToInt()
        if (t.w == 1) {
            val labelHeight = minOf(label.measuredHeight.coerceAtLeast((24 * d).roundToInt()), root.height / 2)
            val y = ((root.height - size - labelHeight - 6 * d) / 2).roundToInt()
            box(icon, (root.width-size)/2, y, size, size)
            box(label, inset / 2, y + size + (6*d).roundToInt(), root.width-inset, labelHeight)
        } else {
            box(icon, inset, (root.height-size)/2, size, size)
            val x = inset * 2 + size
            box(label, x, (root.height-label.measuredHeight)/2, (root.width-x-inset).coerceAtLeast(1), label.measuredHeight)
        }
    }

    private fun layoutMedia(root: View, t: CcTile) {
        val d = root.resources.displayMetrics.density
        val inset = minOf((14*d).roundToInt(), root.width/8)
        val cover = find(root, "cover") ?: return
        val title = find(root, "title")
        val artist = find(root, "artist")
        val audio = find(root, "audio_container")
        val device = find(root, "device_icon")
        val empty = find(root, "empty_state")
        device?.visibility = if (settings.mediaSourceIconHidden) View.GONE else View.VISIBLE
        if (t.w == 2 && t.h == 2) {
            mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
            syncCoverOutline(root)
            syncEmptyMedia(root)
            return // Retain OEM geometry at the original size.
        }
        val wide = wideMedia(root)
        val horizontal = t.h == 1 || wide
        val coverSize = when {
            wide -> minOf(root.height - inset * 2, (root.width * .44f).roundToInt())
            horizontal -> minOf(root.height - inset * 2, root.width / 3)
            else -> minOf(root.width - inset * 2, root.height / 3)
        }.coerceAtLeast(1)
        val coverX = if (horizontal) inset else (root.width - coverSize) / 2
        val coverY = if (wide) (root.height - coverSize) / 2 else inset
        box(cover, coverX, coverY, coverSize, coverSize)
        syncCoverOutline(root)
        device?.let {
            if (it.visibility != View.GONE) {
                val s = minOf((22*d).roundToInt(),root.width/4)
                box(it,root.width-inset-s,inset,s,s)
            }
        }
        val textX = if(horizontal) coverX + coverSize + inset else inset
        val textW = (root.width-textX-inset).coerceAtLeast(1)
        val lineH = minOf((22*d).roundToInt(),root.height/4).coerceAtLeast(1)
        val textY = if(horizontal) inset else inset*2+coverSize
        val textEnd = if (wide && device?.visibility == View.VISIBLE) device.width + (6*d).roundToInt() else 0
        title?.let { box(it,textX,textY,(textW-textEnd).coerceAtLeast(1),lineH) }
        artist?.let { box(it,textX,textY+lineH,(textW-textEnd).coerceAtLeast(1),lineH) }
        empty?.let {
            (it as? android.widget.TextView)?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            box(it,textX,textY,textW,lineH*2)
        }
        audio?.let {
            val h = minOf(((if (wide) 38 else 32)*d).roundToInt(),root.height/3).coerceAtLeast(1)
            val x = if(horizontal) textX else inset
            val reserved = if (wide) progressHeight(root) + (8*d).roundToInt() else 0
            box(it,x,root.height-inset-h-reserved,(root.width-x-inset).coerceAtLeast(1),h)
            if (it is ViewGroup) {
                val controls = listOf("prev","play","next").mapNotNull { id -> find(it,id) }
                controls.forEachIndexed { i, child ->
                    val slot = it.width / 3
                    val s = if (wide) minOf((32*d).roundToInt(), minOf(h,slot)) else minOf(h,slot)
                    box(child, i*slot+(slot-s)/2, (h-s)/2, s, s)
                }
            }
        }
        if (wide && root is ViewGroup) {
            val progress = mediaProgress[root]?.get() ?: MediaProgressView(root.context, root).also {
                mediaProgress[root] = WeakReference(it)
                root.overlay.add(it)
            }
            box(progress, textX, root.height - inset - progressHeight(root),
                (root.width - textX - inset).coerceAtLeast(1), progressHeight(root))
            progress.invalidate()
        } else mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
        syncEmptyMedia(root)
    }

    private fun syncEmptyMedia(root: View) {
        if (root !is ViewGroup) return
        val empty = find(root, "empty_state") as? android.widget.TextView
        val cover = find(root, "cover") ?: return
        empty?.let {
            it.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            it.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        if (empty?.visibility == View.VISIBLE) {
            val placeholder = mediaPlaceholders[root]?.get() ?: View(root.context).also {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                it.isClickable = false
                mediaPlaceholders[root] = WeakReference(it)
                root.overlay.add(it)
            }
            val background = placeholder.background as? GradientDrawable ?: GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(0x26808080)
            }.also { placeholder.background = it }
            background.cornerRadius = coverRadius(mediaHolders[root]?.get(), cover) ?: (10f * root.resources.displayMetrics.density)
            box(placeholder, cover.left, cover.top, cover.width, cover.height)
        } else mediaPlaceholders.remove(root)?.get()?.let { root.overlay.remove(it) }
    }

    private fun progressHeight(root: View): Int = (30 * root.resources.displayMetrics.density).roundToInt()

    private inner class MediaProgressView(context: Context, root: View) : View(context) {
        private val mediaRoot = WeakReference(root)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private var cachedState: Triple<Float, Long, Long>? = null
        private val ticker = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                if (mediaRoot.get()?.isShown == true) invalidate()
                postDelayed(this, 500L)
            }
        }
        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(ticker); post(ticker) }
        override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val root = mediaRoot.get() ?: return
            if (!settings.enabled || !wideMedia(root)) return
            if (find(root, "empty_state")?.visibility == View.VISIBLE) { cachedState = null; return }
            readMediaProgress(root)?.let { cachedState = it }
            val state = cachedState ?: return
            val barH = 4f * density
            val barTop = 1f * density
            paint.color = 0x33ffffff
            canvas.drawRoundRect(RectF(0f, barTop, width.toFloat(), barTop + barH), barH, barH, paint)
            paint.color = 0xe6ffffff.toInt()
            canvas.drawRoundRect(RectF(0f, barTop, width * state.first, barTop + barH), barH, barH, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 10f * density
            paint.color = 0xe6ffffff.toInt()
            val baseline = height - paint.fontMetrics.descent - 1f * density
            canvas.drawText(formatTime(state.second), 0f, baseline, paint)
            val total = formatTime(state.third)
            canvas.drawText(total, width - paint.measureText(total), baseline, paint)
        }
    }

    private fun readMediaProgress(root: View): Triple<Float, Long, Long>? = runCatching {
        val c = Class.forName("com.android.systemui.MiPlayDetailViewModel", false, root.javaClass.classLoader)
        val vm = c.getField("INSTANCE").get(null)
        val meta = c.getMethod("getMMediaMetaData").invoke(vm)?.let { it.javaClass.getMethod("getValue").invoke(it) } ?: return null
        val duration = (meta.javaClass.getMethod("getDuration").invoke(meta) as Number).toLong()
        if (duration <= 0) return null
        val posLive = c.getMethod("getMPosition").invoke(vm)
        val pos = ((posLive?.javaClass?.getMethod("getValue")?.invoke(posLive) as? Number)?.toLong() ?: 0L).coerceIn(0L, duration)
        Triple(pos.toFloat() / duration, pos, duration)
    }.getOrNull()

    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        val minutes = seconds / 60L
        return if (minutes >= 60L) "%d:%02d:%02d".format(minutes / 60L, minutes % 60L, seconds % 60L)
        else "%02d:%02d".format(minutes, seconds % 60L)
    }

    private fun box(view: View, x: Int, y: Int, w: Int, h: Int) {
        if (view.visibility == View.GONE) return
        view.measure(View.MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h.coerceAtLeast(1), View.MeasureSpec.EXACTLY))
        view.layout(x,y,x+w.coerceAtLeast(1),y+h.coerceAtLeast(1))
    }
    private fun find(view: View, name: String): View? {
        val id = view.resources.getIdentifier(name,"id","miui.systemui.plugin")
        return if(id != 0) view.findViewById(id) else null
    }

    private fun field(target: Any?, name: String): Any? = target?.let { findField(it.javaClass,name)?.get(it) }
    private fun set(target: Any, name: String, value: Any) { (findField(target.javaClass,name) ?: error("Missing $name")).set(target,value) }
    private fun findField(clazz: Class<*>, name: String): Field? = generateSequence(clazz) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }.firstOrNull()
    private fun method(clazz: Class<*>, name: String, count: Int): Method? = generateSequence(clazz) { it.superclass }
        .mapNotNull { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == count } }.firstOrNull()?.apply { isAccessible = true }
    private fun call(target: Any?, name: String, vararg args: Any): Any? {
        target ?: return null
        val m = generateSequence(target.javaClass) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size && m.parameterTypes.zip(args).all { (type,arg) ->
                type.isInstance(arg) || (type == Int::class.javaPrimitiveType && arg is Int) || (type == Boolean::class.javaPrimitiveType && arg is Boolean)
            }
        } ?: return null
        return m.apply { isAccessible = true }.invoke(target,*args)
    }
    companion object {
        private const val ADAPTER = "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
        private const val MEDIA_HOLDER = "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder"
        private const val MEDIA_ANIMATOR = "miui.systemui.controlcenter.panel.secondary.media.MediaPanelAnimator"
    }
}
