// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * HyperiOS-style real 2D layoutChunk for the upper controls and optional small-tile backfill.
 * All RecyclerView objects belong to the plugin loader; only Android View crosses loaders.
 * Uses native card factories/owners; no replacement views or secondary router hooks.
 */
internal class ControlCenterTileLayoutHook(private val module: XposedModule) {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val managerInstalled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val managers = WeakHashMap<Any, WeakReference<Any>>()
    private val panels = WeakHashMap<Any, WeakReference<ViewGroup>>()
    private val adapted = WeakHashMap<View, CcTile>()
    private data class BlockExtent(val start: Int, val end: Int, val total: Int)
    private val blockExtents = WeakHashMap<View, BlockExtent>()
    private val sliderSizes = WeakHashMap<View, Pair<Int, Int>>()
    private val mediaProgress = WeakHashMap<View, WeakReference<MediaProgressView>>()
    private val mediaPlaceholders = WeakHashMap<View, WeakReference<View>>()
    private val mediaHolders = WeakHashMap<View, WeakReference<Any>>()
    private val hiddenMediaChildren = WeakHashMap<View, MutableMap<String, Int>>()
    private val mediaLayoutSizes = WeakHashMap<View, Pair<Int, Int>>()
    private val mediaInstalled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val distributorInstalled = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val childLayouts = mutableSetOf<Method>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settings = CcTileLayoutSettings()
    @Volatile private var landscapeSettings = CcTileLayoutLandscapeSettings()
    private var selectionPreferences: SharedPreferences? = null
    private var selectedSpecs: Map<String, String> = emptyMap()
    private var styleControllerRef: WeakReference<Any>? = null
    private val cardSelections = WeakHashMap<Any, List<String>>()

    private fun isSupportedStyle(style: String?): Boolean = style == "VERTICAL" || style == "HORIZONTAL"

    private fun isRightPanel(adapter: Any?): Boolean = (field(adapter, "isRightPanel") as? Boolean) != false

    private fun adapterStyle(adapter: Any?): String? {
        adapter ?: return null
        val live = call(call(field(adapter, "mainPanelController"), "get"), "getStyle")?.toString()
        return live ?: field(adapter, "style")?.toString()
    }

    private fun activeStyleForContext(context: Context?): String {
        styleControllerRef?.get()?.let { sc ->
            call(sc, "getStyle")?.toString()?.let { return it }
        }
        panels.keys.asSequence().mapNotNull(::adapterStyle).firstOrNull()?.let { return it }
        if (context != null && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val forceVertical = runCatching {
                val cls = Class.forName("miui.systemui.util.CommonUtils", false, context.classLoader)
                val inst = cls.getField("INSTANCE").get(null)
                cls.getMethod("getForceVertical").invoke(inst) as? Boolean
            }.getOrDefault(false)
            if (forceVertical != true) return "HORIZONTAL"
        }
        return "VERTICAL"
    }

    private fun activeTiles(style: String?): List<CcTile> =
        if (style == "HORIZONTAL") resolveCcLandscapeTiles(settings.tiles, landscapeSettings) else settings.tiles

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
        landscapeSettings = readCcTileLayoutLandscapeSettings(p)
        selectionPreferences = p
        installTileSelection(clazz.classLoader!!, p)
        installDistributor(clazz.classLoader!!)
        installSmallTileTypes(clazz)
        // This runs on the main handler, outside the ClassLoader discovery stack.
        listOf(MEDIA_HOLDER, MEDIA_ANIMATOR).forEach { name ->
            runCatching { installMediaHooks(clazz.classLoader!!.loadClass(name)) }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, "HyperChanger", "Could not install media layout hook: $name", it)
            }
        }
        if (preferenceListener == null) {
            preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in setOf(KEY_CC_TILE_LAYOUT, KEY_CC_TILE_LAYOUT_ENABLED, KEY_CC_MEDIA_SOURCE_ICON_HIDDEN,
                        KEY_CC_SMALL_TILE_AUTO_FILL, KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE, KEY_CC_TILE_LAYOUT_LANDSCAPE,
                        KEY_CONTROL_CENTER_5G_TILE_MODE, KEY_CONTROL_CENTER_GMS_TILE_ENABLED)) {
                    handler.post {
                        settings = readCcTileLayoutSettings(p)
                        landscapeSettings = readCcTileLayoutLandscapeSettings(p)
                        if (!settings.enabled) adapted.entries.toList().filter { it.value.id == "media" }
                            .forEach { clearMedia(it.key) }
                        panels.entries.toList().forEach { (adapter, reference) ->
                            reference.get()?.let { rv ->
                                refreshTileSelection(adapter)
                                // Drop stale saved anchors after dimensions/order change; OEM lays out from top.
                                if (key == null || key in setOf(KEY_CC_TILE_LAYOUT, KEY_CC_TILE_LAYOUT_ENABLED,
                                        KEY_CC_SMALL_TILE_AUTO_FILL, KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE,
                                        KEY_CC_TILE_LAYOUT_LANDSCAPE)) {
                                    call(adapter, "notifyDataSetChanged")
                                }
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
                if (adapter != null && isRightPanel(adapter) && panels[adapter]?.get() == null) {
                    (field(adapter,"recyclerView") as? ViewGroup)?.let { rv ->
                        registerPanel(adapter,rv)
                        rv.post { rv.requestLayout() }
                    }
                }
                result
            }
        }
    }

    private fun installDistributor(loader: ClassLoader) {
        val distributorClass = runCatching { loader.loadClass(DISTRIBUTOR) }.getOrNull() ?: return
        if (!distributorInstalled.add(distributorClass)) return
        val distribute = distributorClass.declaredMethods.firstOrNull {
            it.name == "distributePanels" && it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return
        module.hook(distribute).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:distribute-panels").intercept { chain ->
            val result = chain.proceed()
            if (settings.enabled) {
                // MainPanelStyleController.set_style and onConfigurationChanged call distributePanels
                // immediately before handleNotifyChanged; sync orientation card specs right here.
                field(chain.thisObject, "rightAdapter")?.let { rightAdapter ->
                    refreshTileSelection(rightAdapter, fromDistribute = true)
                }
            }
            result
        }
    }

    private fun registerPanel(adapter: Any, rv: ViewGroup) {
        if (!isRightPanel(adapter)) return
        val manager = field(adapter,"layoutManager") ?: return
        managers[manager] = WeakReference(adapter)
        panels[adapter] = WeakReference(rv)
        installManager(manager.javaClass)
        installOrientationHelper(manager)
        rv.post { if (panels[adapter]?.get() === rv) refreshTileSelection(adapter) }
    }

    /** OEM creates and owns records, callbacks and tile lifecycles for the selected specs. */
    private fun installTileSelection(loader: ClassLoader, p: SharedPreferences) {
        val controller = loader.loadClass("miui.systemui.controlcenter.qs.QSController")
        controller.getDeclaredMethod("getCardStyleTileSpecs").let { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:selected-specs").intercept { chain ->
                val original = chain.proceed()
                val context = call(chain.thisObject, "getContext") as? Context
                val style = activeStyleForContext(context)
                if (!settings.enabled || context == null || !isSupportedStyle(style)) original else {
                    selectedSpecs = CcTileCatalog.specs(context, p, activeTiles(style))
                    selectedSpecs.values.toList()
                }
            }
        }
        loader.loadClass("miui.systemui.controlcenter.panel.main.media.MediaPlayerController")
            .getDeclaredMethod("getListItems").let { m ->
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:optional-media").intercept { chain ->
                    val original = chain.proceed()
                    val owner = chain.thisObject
                    val normal = call(call(field(owner, "mainPanelModeController"), "get"), "getMode")?.toString() == "NORMAL"
                    val styleController = call(field(owner, "mainPanelStyleController"), "get")?.also {
                        styleControllerRef = WeakReference(it)
                    }
                    val style = call(styleController, "getStyle")?.toString()
                    if (settings.enabled && normal && isSupportedStyle(style) && activeTiles(style).none { it.id == "media" }) emptyList<Any>() else original
                }
            }
    }

    private fun refreshTileSelection(adapter: Any, fromDistribute: Boolean = false) {
        val p = selectionPreferences ?: return
        val contents = (field(adapter, "contentList") as? Iterable<*>)
            ?: (field(adapter, "contentMap") as? Map<*, *>)?.keys
            ?: return
        val cards = contents.firstOrNull { it?.javaClass?.simpleName == "QSCardsController" } ?: return
        call(field(cards, "mainPanelStyleController"), "get")?.let { styleControllerRef = WeakReference(it) }
        val qs = call(field(cards, "qsController"), "get") ?: return
        val context = call(qs, "getContext") as? Context ?: return
        runCatching {
            val style = adapterStyle(adapter) ?: activeStyleForContext(context)
            val active = settings.enabled && isSupportedStyle(style)
            val tiles = activeTiles(style)
            selectedSpecs = CcTileCatalog.specs(context, p, tiles)
            val wanted = if (active) selectedSpecs.values.toList()
                else (call(qs, "getCardStyleTileSpecs") as? List<*>)?.filterIsInstance<String>().orEmpty()
            // Include media membership: its native content output must also be redistributed.
            val signature = wanted + if (!active || tiles.any { it.id == "media" }) listOf("@media") else emptyList()
            if (cardSelections[cards] == signature) return
            call(qs, "updateExcludeTileSpecs")
            // OEM clearCardTiles removes callbacks but does not release listening tokens.
            // Release outgoing records as onStop does; native holders start the new records.
            (field(cards, "cardRecords") as? Iterable<*>)?.toList()?.forEach { record ->
                call(record, "setListening", false)
                call(record, "stopMarquee")
            }
            call(cards, "createCardTiles")
            call(call(field(cards, "qsListController"), "get"), "onTilesChanged")
            if (!fromDistribute) call(cards, "distributeTiles")
            cardSelections[cards] = signature
        }.onFailure { HookModuleLogger.log(module, ModuleLog.WARN, "HyperChanger", "Could not refresh selected control center tiles", it) }
    }

    /** Use the plugin's ordinary tile factory/holder, just as HyperiOS does for small cards. */
    private fun installSmallTileTypes(clazz: Class<*>) {
        runCatching { clazz.classLoader!!.loadClass(QS_CARDS_CONTROLLER) }.getOrNull()?.let { cardClass ->
            cardClass.declaredMethods.filter { it.name == "createViewHolder" && it.parameterCount == 2 }.forEach { m ->
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:card-to-tile-holder").intercept { chain ->
                    if ((chain.getArg(1) as? Int) == 8453) {
                        val provider = field(chain.thisObject, "qsListController")
                        val listController = call(provider, "get")
                        val holder = call(listController, "createViewHolder", chain.getArg(0), 8453)
                        if (holder != null) holder else chain.proceed()
                    } else chain.proceed()
                }
            }
        }
        clazz.getDeclaredMethod("getItemViewType", Int::class.javaPrimitiveType).let { m ->
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).setId("cc-tiles:small-tile-type").intercept { chain ->
                val original = chain.proceed()
                val adapter = chain.thisObject
                val manager = field(adapter, "layoutManager")
                val position = chain.getArg(0) as Int
                val plan = if (adapter != null && manager != null) block(adapter, manager) else null
                val id = plan?.ids?.get(position)
                val tile = plan?.tiles?.get(position)
                if (id != null && id != "media" && tile != null && !tile.slider && tile.w == 1 && tile.h == 1) 8453 else original
            }
        }
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
                    isRightPanel(adapter) &&
                    field(adapter, "mode")?.toString() == "NORMAL" &&
                    isSupportedStyle(adapterStyle(adapter))
            }
            parent = parent.parent
        }
        return false
    }

    private fun wideMedia(root: View): Boolean = adapted[root]?.let { it.w == 4 && it.h == 2 } == true

    private fun hasMediaProgress(root: View): Boolean = adapted[root]?.let {
        it.w >= 2 && it.h >= 2 && (it.w >= 3 || it.h >= 3)
    } == true

    private fun mediaEmpty(root: View): Boolean =
        (hiddenMediaChildren[root]?.get("empty_state") ?: find(root, "empty_state")?.visibility) == View.VISIBLE

    private fun mediaChildVisible(root: View, name: String, visible: Boolean) {
        val child = find(root, name) ?: return
        if (visible) {
            hiddenMediaChildren[root]?.remove(name)?.let { child.visibility = it }
            if (name == "device_icon" && child.visibility == View.GONE) child.visibility = View.VISIBLE
        } else {
            hiddenMediaChildren.getOrPut(root) { mutableMapOf() }.putIfAbsent(name, child.visibility)
            // INVISIBLE still participates in OEM measurement and preserves expansion snapshots.
            child.visibility = View.INVISIBLE
        }
    }

    private fun restoreMediaChildren(root: View) {
        hiddenMediaChildren.remove(root)?.forEach { (name, visibility) -> find(root, name)?.visibility = visibility }
    }

    private fun clearMedia(root: View) {
        restoreMediaChildren(root)
        mediaLayoutSizes.remove(root)
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
                    if (m.name == "updateMetaData") mediaRoot(chain.thisObject)?.let(::restoreMediaChildren)
                    val result = chain.proceed()
                    mediaRoot(chain.thisObject)?.let { root ->
                        chain.thisObject?.let { mediaHolders[root] = WeakReference(it) }
                        if (mainMedia(root)) {
                            if (m.name == "updateMetaData") adapted[root]?.let { layoutMedia(root, it) }
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
            if (root != null && customMedia(root) && snapshot != null && fraction != null && delegate != null) {
                // No second ValueAnimator and no scaling around a different pivot at hand-off.
                listOf("Prev", "Play", "Next").forEach { name ->
                    val start = call(snapshot, "getFromAudio$name")
                    val end = call(snapshot, "getToAudio$name")
                    val button = call(delegate, "getAv${name}View") as? View
                    if (start != null && end != null && button != null) {
                        val a = snapshotRect(start); val b = snapshotRect(end)
                        val rect = interpolate(a, b, fraction)
                        button.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                        val id = name.lowercase(java.util.Locale.ROOT)
                        button.alpha = if (find(root, "audio_container")?.visibility != View.VISIBLE || find(root, id)?.visibility != View.VISIBLE)
                            fraction.coerceIn(0f, 1f) else 1f
                    }
                }
                val progress = mediaProgress[root]?.get()
                val expanded = call(delegate, "getProgressContainerView") as? View
                val end = call(snapshot, "getToProgressContainer")
                if (progress != null && expanded != null && end != null) {
                    val parent = expanded.parent as? View
                    if (parent != null) {
                        // Map the small row into the animated parent's current coordinate space.
                        // Re-measure & re-layout at the live width instead of non-uniform scaleX/Y
                        // so the SeekBar thumb and time labels never stretch or squish horizontally.
                        val start = mapRect(root, RectF(progress.left.toFloat(), progress.top.toFloat(), progress.right.toFloat(), progress.bottom.toFloat()), parent)
                        if (start != null) {
                            val target = snapshotRect(end)
                            val rect = interpolate(start, target, fraction)
                            val curW = rect.width().roundToInt().coerceAtLeast(1)
                            val curH = rect.height().roundToInt().coerceAtLeast(1)
                            expanded.pivotX = 0f; expanded.pivotY = 0f
                            expanded.scaleX = 1f
                            expanded.scaleY = 1f
                            expanded.measure(
                                View.MeasureSpec.makeMeasureSpec(curW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(curH, View.MeasureSpec.EXACTLY),
                            )
                            val x = rect.left.roundToInt(); val y = rect.top.roundToInt()
                            expanded.layout(x, y, x + curW, y + curH)
                            expanded.alpha = 1f
                            val empty = mediaEmpty(root)
                            val state = if (empty) Triple(0f, 0L, 0L) else readMediaProgress(root)
                            state?.let {
                                (find(expanded, "media_progress_bar") as? SeekBar)?.let { bar ->
                                    if (!bar.isPressed) {
                                        val maxVal = bar.max.coerceAtLeast(1)
                                        bar.progress = (state.first * maxVal).roundToInt()
                                    }
                                }
                                (find(expanded, "media_elapsed_time") as? TextView)?.let { tv ->
                                    applyMediaTime(tv, state.second)
                                }
                                (find(expanded, "media_total_time") as? TextView)?.let { tv ->
                                    applyMediaTime(tv, state.third)
                                }
                            }
                            progress.syncNow()
                        }
                    }
                }
                val device = call(delegate, "getDeviceIconView") as? View
                val source = find(root, "device_icon")
                if (device != null && source != null) {
                    // This icon is hidden at the expanded endpoint by OEM; interpolate its alpha.
                    val alpha = if (settings.mediaSourceIconHidden || source.visibility != View.VISIBLE) 0f else 1f - fraction.coerceIn(0f, 1f)
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

    private data class Block(
        val start: Int,
        val end: Int,
        val ids: Map<Int, String>,
        val tiles: Map<Int, CcTile>,
        val nativePositions: Set<Int>,
        val rows: Int,
    )

    /** Reject any unfamiliar structure rather than moving lower tiles or mixed panel contents. */
    private fun block(adapter: Any, manager: Any): Block? {
        if (!settings.enabled || !isRightPanel(adapter) || field(adapter, "mode")?.toString() != "NORMAL") return null
        val style = adapterStyle(adapter)
        if (!isSupportedStyle(style)) return null
        if ((call(manager, "getSpanCount") as? Int) != 4 || (call(manager, "getOrientation") as? Int) != 1) return null
        val content = field(adapter, "contentMap") as? Map<*, *> ?: return null
        val activeLayout = activeTiles(style)
        val specs = run {
            val expectedIds = activeLayout.mapNotNull { if (it.slider || it.id == "media") null else it.id }
            if (selectedSpecs.keys.containsAll(expectedIds)) selectedSpecs else {
                val rv = panels[adapter]?.get()
                val prefs = selectionPreferences
                if (rv != null && prefs != null) CcTileCatalog.specs(rv.context, prefs, activeLayout).also { selectedSpecs = it }
                else selectedSpecs
            }
        }
        var index = 0
        val all = mutableListOf<Triple<Int, String?, Any?>>()
        content.forEach { (owner, raw) ->
            (raw as? List<*>)?.forEach { item ->
                val id = when (owner?.javaClass?.simpleName) {
                    "QSCardsController" -> specs.entries.firstOrNull { it.value == call(item, "getSpec") }?.key
                    "MediaPlayerController" -> "media"
                    "BrightnessSliderController" -> "brightness"
                    "VolumeSliderController" -> "volume"
                    else -> null
                }
                all += Triple(index++, id, item)
            }
        }
        val ids = linkedMapOf<Int, String>()
        all.forEach { (position, id, _) -> if (id != null) ids[position] = id }
        if (ids.values.toSet().size != ids.size || "brightness" !in ids.values || "volume" !in ids.values) return null
        if (ids.values.any { id -> activeLayout.none { it.id == id } }) return null
        val start = ids.keys.min(); val end = ids.keys.max()
        if (end - start + 1 != ids.size) return null
        val tiles = linkedMapOf<Int, CcTile>()
        ids.forEach { (position, id) -> tiles[position] = activeLayout.first { it.id == id } }
        // In HORIZONTAL (landscape), QSListController lives in leftMainPanel rather than rightMainPanel.
        val allowSmallFill = settings.smallTileAutoFill && style == "VERTICAL"
        val following = if (allowSmallFill) all.filter { it.first > end }.map { (position, _, item) ->
            val span = ((call(item, "getSpanSize") as? Int) ?: 4).coerceIn(1, 4)
            CcFillRecord(position, span, item?.javaClass?.simpleName == "QSRecord" &&
                (call(item, "getType") as? Int) == 8453 && span == 1)
        } else emptyList()
        val lookup = if (following.isNotEmpty()) call(manager, "getSpanSizeLookup") else null
        val nextSpanIndex = (call(lookup, "getSpanIndex", end + 1, 4) as? Int) ?: 0
        val fill = planCcSmallTileFill(tiles, CcTileLayouts.rows(activeLayout), following,
            allowSmallFill, nextSpanIndex)
        return Block(start, fill.tiles.keys.max(), ids, fill.tiles, fill.nativePositions, fill.rows)
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
                    blockExtents.keys.toList().filter { it.parent === rv }.forEach(blockExtents::remove)
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

    /** Treat the reordered visual block as one recycling/scrolling extent. This prevents
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
                val extent = blockExtents[view]
                val plan = if (lm != null && adapter != null) block(adapter, lm) else null
                val position = if (lm != null && view != null) call(lm, "getPosition", view) as? Int else null
                if (extent != null && plan != null && position in plan.start..plan.end && view?.parent === panels[adapter]?.get()) {
                    if (m.name.contains("Start")) original - extent.start
                    else original + extent.total - extent.end
                } else original
            }
        }
    }

    private fun step(manager: Any): Int = ((call(manager, "getWidth") as Int) -
        (call(manager, "getPaddingLeft") as Int) - (call(manager, "getPaddingRight") as Int)) / 4

    private fun layoutBlock(manager: Any, adapter: Any, block: Block, recycler: Any, layout: Any, result: Any) {
        val pitch = step(manager).coerceAtLeast(1)
        val forward = (field(layout, "mItemDirection") as Int) == 1
        val towardEnd = (field(layout, "mLayoutDirection") as Int) == 1
        val offset = field(layout, "mOffset") as Int
        val rv = panels[adapter]?.get() ?: error("Detached upper tile layout")
        val left = call(manager, "getPaddingLeft") as Int
        val rtl = rv.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Collect in adapter order (or reverse), retaining every original view exactly once.
        set(layout, "mCurrentPosition", if (forward) block.start else block.end)
        val positions = if (forward) (block.start..block.end).toList() else (block.end downTo block.start).toList()
        val views = linkedMapOf<Int, View>()
        val verticalInsets = mutableMapOf<Int, Int>()
        val rowHeights = IntArray(block.rows) { pitch }
        val naturalRows = block.nativePositions.map { block.tiles.getValue(it).row }.toSet()
        naturalRows.forEach { rowHeights[it] = 0 }
        var focusable = false
        positions.forEach { position ->
            val view = call(layout, "next", recycler) as? View ?: error("Missing upper tile at $position")
            views[position] = view
            val tile = block.tiles.getValue(position)
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            set(params, "mSpanIndex", tile.col); set(params, "mSpanSize", tile.w)
            if (forward) call(manager, "addView", view) else call(manager, "addView", view, 0)
            val decoration = Rect()
            call(manager, "calculateItemDecorationsForChild", view, decoration)
            val width = (tile.w * pitch - params.leftMargin - params.rightMargin - decoration.left - decoration.right).coerceAtLeast(1)
            val insets = params.topMargin + params.bottomMargin + decoration.top + decoration.bottom
            verticalInsets[position] = insets
            if (position in block.nativePositions) {
                // Device entries and other system rows keep their OEM requested height.
                adapted.remove(view)
                blockExtents.remove(view)
                val heightSpec = if (params.height >= 0) View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)
                    else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec)
                rowHeights[tile.row] = maxOf(rowHeights[tile.row], view.measuredHeight + insets)
            } else {
                val height = (tile.h * pitch - insets).coerceAtLeast(1)
                prepareChild(view, tile, width, height)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                // A small tile with labels may need more vertical room than an empty slot.
                // Grow that shared row; spanning upper cards use the same row boundaries.
                if (tile.id.startsWith("small:")) {
                    val label = find(view, "tile_label")
                    if (label?.visibility == View.VISIBLE) rowHeights[tile.row] = maxOf(rowHeights[tile.row], params.height + insets)
                }
            }
            focusable = focusable || view.hasFocusable()
        }
        val rowOffsets = IntArray(block.rows + 1)
        for (row in rowHeights.indices) rowOffsets[row + 1] = rowOffsets[row] + rowHeights[row]
        val total = rowOffsets.last()
        val top = if (towardEnd) offset else offset - total
        positions.forEach { position ->
            val view = views.getValue(position)
            val tile = block.tiles.getValue(position)
            val start = rowOffsets[tile.row]
            val end = rowOffsets[tile.row + tile.h]
            val height = (end - start - verticalInsets.getValue(position)).coerceAtLeast(1)
            if (position !in block.nativePositions) prepareChild(view, tile, view.measuredWidth, height)
            view.measure(View.MeasureSpec.makeMeasureSpec(view.measuredWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            val col = if (rtl) 4 - tile.col - tile.w else tile.col
            val x = left + col * pitch
            call(manager, "layoutDecoratedWithMargins", view, x, top + start, x + tile.w * pitch, top + end)
            blockExtents[view] = BlockExtent(start, end, total)
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
                if (root != null && settings.enabled) {
                    if (t != null && adapter != null && panels[adapter]?.get() === parent &&
                        isRightPanel(adapter) && field(adapter, "mode")?.toString() == "NORMAL" && isSupportedStyle(adapterStyle(adapter))) {
                        if (t.id == "media") layoutMedia(root, t) else if (!t.slider) layoutCard(root, t)
                    }
                }
                result
            }
        }
    }

    /** Keep the OEM children/listeners; resize their actual boxes instead of scaling touch targets. */
    private fun layoutCard(root: View, t: CcTile) {
        // Native 1×1 tiles own their icon frame, text mode, background and touch geometry.
        if (find(root, "icon_frame") != null) return
        val content = find(root, "scale_content") as? ViewGroup ?: return
        val label = find(root, "label_container") ?: return
        val icon = (0 until content.childCount).map(content::getChildAt).firstOrNull { it !== label } ?: return
        val d = root.resources.displayMetrics.density
        if (t.w == 1) {
            val size = minOf((32 * d).roundToInt(), (root.width * .42f).roundToInt())
            val inset = (12 * d).roundToInt()
            val labelHeight = minOf(label.measuredHeight.coerceAtLeast((24 * d).roundToInt()), root.height / 2)
            val y = ((root.height - size - labelHeight - 6 * d) / 2).roundToInt()
            box(icon, (root.width-size)/2, y, size, size)
            box(label, inset / 2, y + size + (6*d).roundToInt(), root.width-inset, labelHeight)
            return
        }
        // Wide cards (2×1, 3×1, 4×1, 2×2, ...) are already laid out natively by QSCardItemView's
        // horizontal scale_content LinearLayout with qs_tile_item_icon_size (68dp) and native
        // icon/label margins. Only intervene if an unusually short row cannot fit the native icon.
        val nativeIconSize = dimenPx(root.context, "qs_tile_item_icon_size", (68 * d).roundToInt())
        if (root.height >= nativeIconSize && root.width >= nativeIconSize * 2) return
        val iconSize = minOf(nativeIconSize, root.height, (root.width * .44f).roundToInt()).coerceAtLeast(1)
        val iconStart = dimenPx(root.context, "qs_card_item_icon_margin_start", (-1 * d).roundToInt())
        val labelStartGap = dimenPx(root.context, "qs_card_item_label_container_margin_start", (-11 * d).roundToInt())
        val labelEnd = dimenPx(root.context, "qs_card_item_label_container_margin_end", (17 * d).roundToInt())
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val iconX = if (rtl) root.width - iconStart - iconSize else iconStart
        box(icon, iconX, (root.height - iconSize) / 2, iconSize, iconSize)
        val labelW = (root.width - iconStart - iconSize - labelStartGap - labelEnd).coerceAtLeast(1)
        val labelX = if (rtl) labelEnd else (iconStart + iconSize + labelStartGap).coerceAtLeast(0)
        box(label, labelX, (root.height - label.measuredHeight) / 2, labelW, label.measuredHeight)
    }

    private fun layoutMedia(root: View, t: CcTile) {
        if (root.width <= 0 || root.height <= 0) return
        val size = t.w to t.h
        if (mediaLayoutSizes[root] != size) {
            restoreMediaChildren(root)
            mediaLayoutSizes[root] = size
        }
        val d = root.resources.displayMetrics.density
        val inset = minOf((14*d).roundToInt(), root.width/8)
        val cover = find(root, "cover") ?: return
        val title = find(root, "title")
        val artist = find(root, "artist")
        val audio = find(root, "audio_container")
        val device = find(root, "device_icon")
        val empty = find(root, "empty_state")
        if (wideMedia(root)) {
            device?.visibility = if (settings.mediaSourceIconHidden) View.GONE else View.VISIBLE
        }
        if (!wideMedia(root)) {
            layoutAdaptiveMedia(root, t)
            return
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
            (it as? TextView)?.gravity = Gravity.START or Gravity.TOP
            box(it, textX, textY, (textW - textEnd).coerceAtLeast(1), lineH)
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
            progress.syncNow()
        } else mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
        syncEmptyMedia(root)
    }

    /** Size tiers follow HyperiOS: cover-only, compact transport, then metadata/transport. */
    private fun layoutAdaptiveMedia(root: View, t: CcTile) {
        val d = root.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).roundToInt()
        val inset = minOf(dp(12), root.width / 8, root.height / 8)
        val gap = dp(6)
        val width = (root.width - inset * 2).coerceAtLeast(1)
        val height = (root.height - inset * 2).coerceAtLeast(1)
        val coverOnly = t.w == 1 && t.h == 1
        val transportOnly = (t.w == 2 && t.h == 1) || (t.w == 1 && t.h == 2)
        val singleRow = t.h == 1 && !coverOnly && !transportOnly
        val vertical = t.w <= 2 && t.h > 1
        val showProgress = hasMediaProgress(root)
        listOf("title", "artist", "empty_state").forEach { mediaChildVisible(root, it, !coverOnly && !transportOnly) }
        mediaChildVisible(root, "audio_container", !coverOnly)
        listOf("prev", "play", "next").forEach { name ->
            mediaChildVisible(root, name, !coverOnly && (!singleRow || name == "play" || (t.w == 4 && name == "next")))
        }
        // A source icon must not compete with the cover or transport in narrow/short tiers.
        mediaChildVisible(root, "device_icon", !settings.mediaSourceIconHidden && t.w >= 2 && t.h >= 2)
        val cover = find(root, "cover") ?: return
        val coverSize = when {
            coverOnly -> minOf(width, height, dp(56))
            transportOnly && t.h == 1 -> minOf(height, (width * .28f).roundToInt())
            transportOnly -> minOf(width, (height * .36f).roundToInt())
            singleRow -> minOf(height, (width * .22f).roundToInt())
            vertical -> minOf(width, (height * .32f).roundToInt())
            else -> minOf(height, (width * .42f).roundToInt())
        }.coerceAtLeast(1)
        val coverX = if (t.w == 1 || coverOnly) (root.width - coverSize) / 2 else inset
        val coverY = if (coverOnly || !vertical) (root.height - coverSize) / 2 else inset
        box(cover, coverX, coverY, coverSize, coverSize)
        syncCoverOutline(root)
        find(root, "device_icon")?.let { device ->
            val s = minOf(dp(20), width / 4)
            box(device, root.width - inset - s, inset, s, s)
        }
        if (coverOnly) {
            mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
            syncEmptyMedia(root)
            return
        }
        val progressH = if (showProgress) progressHeight(root) else 0
        val contentX = if (vertical) inset else coverX + coverSize + gap
        val contentW = (root.width - inset - contentX).coerceAtLeast(1)
        val audio = find(root, "audio_container")
        val verticalButtons = t.w == 1
        val buttonCount = if (singleRow) if (t.w == 4) 2 else 1 else 3
        val audioW = if (singleRow) minOf(contentW / 2, dp(32) * buttonCount + gap) else contentW
        val audioH = when {
            verticalButtons -> minOf((height - coverSize - gap - if (transportOnly) 0 else dp(42) + gap).coerceAtLeast(1), dp(28) * 3)
            singleRow -> minOf(height, dp(36))
            else -> minOf(height / 3, dp(36))
        }.coerceAtLeast(1)
        val audioX = if (singleRow) root.width - inset - audioW else contentX
        val audioY = if (singleRow || transportOnly && !vertical) (root.height - audioH) / 2
            else root.height - inset - progressH - (if (showProgress) gap else 0) - audioH
        audio?.let {
            box(it, audioX, audioY, audioW, audioH)
            val controls = listOf("prev", "play", "next").mapNotNull { name -> find(it, name) }.filter { v -> v.visibility == View.VISIBLE }
            controls.forEachIndexed { index, child ->
                val slot = if (verticalButtons) audioH / controls.size else audioW / controls.size
                val s = minOf(dp(32), slot, if (verticalButtons) audioW else audioH).coerceAtLeast(1)
                box(child, if (verticalButtons) (audioW - s) / 2 else index * slot + (slot - s) / 2,
                    if (verticalButtons) index * slot + (slot - s) / 2 else (audioH - s) / 2, s, s)
            }
        }
        val textTop = if (vertical) coverY + coverSize + gap else inset
        val textBottom = if (singleRow) root.height - inset else audioY - gap
        val lineH = minOf(dp(22), (textBottom - textTop).coerceAtLeast(2) / 2).coerceAtLeast(1)
        val textY = if (singleRow) (root.height - lineH * 2) / 2 else textTop
        val textW = if (singleRow) (audioX - gap - contentX).coerceAtLeast(1)
            else (contentW - if (!vertical && find(root, "device_icon")?.visibility == View.VISIBLE) dp(26) else 0).coerceAtLeast(1)
        find(root, "title")?.let { box(it, contentX, textY, textW, lineH) }
        find(root, "artist")?.let { box(it, contentX, textY + lineH, textW, lineH) }
        find(root, "empty_state")?.let {
            (it as? TextView)?.gravity = Gravity.START or Gravity.TOP
            box(it, contentX, textY, textW, lineH)
        }
        if (showProgress && root is ViewGroup) {
            val progress = mediaProgress[root]?.get() ?: MediaProgressView(root.context, root).also {
                mediaProgress[root] = WeakReference(it)
                root.overlay.add(it)
            }
            box(progress, contentX, root.height - inset - progressH, contentW, progressH)
            progress.syncNow()
        } else mediaProgress.remove(root)?.get()?.let { (root as? ViewGroup)?.overlay?.remove(it) }
        syncEmptyMedia(root)
    }

    private fun syncEmptyMedia(root: View) {
        if (root !is ViewGroup) return
        val empty = find(root, "empty_state") as? TextView
        val cover = find(root, "cover") ?: return
        empty?.let {
            it.gravity = Gravity.START or Gravity.TOP
            it.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        if (mediaEmpty(root)) {
            val radius = coverRadius(mediaHolders[root]?.get(), cover) ?: (10f * root.resources.displayMetrics.density)
            val placeholder = (mediaPlaceholders[root]?.get() as? ImageView) ?: ImageView(root.context).also { iv ->
                iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                iv.isClickable = false
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.clipToOutline = true
                val drawableCtx = resolveMiPlayContext(root) ?: root.context
                val defaultCoverId = drawableCtx.resources.getIdentifier(
                    "miplay_cover_default",
                    "drawable",
                    "miui.systemui.plugin",
                )
                if (defaultCoverId != 0) {
                    runCatching { iv.setImageDrawable(drawableCtx.getDrawable(defaultCoverId)) }
                } else {
                    iv.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0x73808080)
                    }
                }
                mediaPlaceholders[root] = WeakReference(iv)
                root.overlay.add(iv)
            }
            placeholder.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            placeholder.invalidateOutline()
            box(placeholder, cover.left, cover.top, cover.width, cover.height)
        } else mediaPlaceholders.remove(root)?.get()?.let { root.overlay.remove(it) }
    }

    private fun progressHeight(root: View): Int {
        val existing = mediaProgress[root]?.get()
        val measured = existing?.preferredHeightPx() ?: 0
        if (measured > 0) return measured
        val miPlayCtx = resolveMiPlayContext(root) ?: root.context
        val d = miPlayCtx.resources.displayMetrics.density
        val barH = dimenPx(miPlayCtx, "miplay_detail_header_media_progress_bar_top", (20 * d).roundToInt())
        val gap = dimenPx(miPlayCtx, "miplay_detail_header_media_elapsed_time_margin_top", 0)
        val textH = dimenPx(miPlayCtx, "miplay_detail_header_media_time_text_size", (10 * d).roundToInt())
        return (barH + gap + (textH * 1.4f).roundToInt()).coerceAtLeast((30 * d).roundToInt())
    }

    private fun resolveMiPlayContext(root: View): Context? = runCatching {
        val c = Class.forName("com.android.systemui.MiPlayController", false, root.javaClass.classLoader)
        val inst = c.getField("INSTANCE").get(null)
        c.getMethod("getContext").invoke(inst) as? Context
    }.getOrNull()

    private fun dimenPx(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", "miui.systemui.plugin")
        return if (id != 0) runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(fallback) else fallback
    }

    private fun applyMediaTime(tv: TextView, ms: Long) {
        val applied = runCatching {
            val m = generateSequence<Class<*>>(tv.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "setMediaTime" && it.parameterCount == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType }
                ?: return@runCatching false
            m.isAccessible = true
            m.invoke(tv, ms)
            true
        }.getOrDefault(false)
        if (!applied) tv.text = formatTime(ms)
    }

    /**
     * Reuses the expanded panel's native container_progress (MiPlaySeekBar + MediaTimeTextView x2)
     * inside the collapsed tile so expand/collapse progress transitions are visually 1:1 with zero
     * horizontal compression or handoff snap. Falls back to canvas drawing if inflation fails.
     */
    private inner class MediaProgressView(context: Context, root: View) : FrameLayout(context) {
        private val mediaRoot = WeakReference(root)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private var cachedState: Triple<Float, Long, Long>? = null
        private var nativeContainer: View? = null
        private var nativeSeekBar: SeekBar? = null
        private var nativeElapsed: TextView? = null
        private var nativeTotal: TextView? = null
        private var seekBarFgActiveColor: Int = Color.WHITE
        private var seekBarFgDisableColor: Int = Color.WHITE
        private var cachedPreferredHeight = 0

        private val ticker = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                if (mediaRoot.get()?.isShown == true) syncNow()
                postDelayed(this, 500L)
            }
        }

        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            inflateNativeProgress(root)
            setWillNotDraw(nativeContainer != null)
        }

        private fun inflateNativeProgress(root: View) {
            runCatching {
                val miPlayCtx = resolveMiPlayContext(root) ?: root.context
                val layoutId = miPlayCtx.resources.getIdentifier(
                    "qs_control_detail_header_metainfo_layout",
                    "layout",
                    "miui.systemui.plugin",
                )
                if (layoutId == 0) return@runCatching
                val inflated = LayoutInflater.from(miPlayCtx).inflate(layoutId, null, false)
                val container = find(inflated, "container_progress") ?: return@runCatching
                (container.parent as? ViewGroup)?.removeView(container)
                container.alpha = 1f
                container.scaleX = 1f
                container.scaleY = 1f
                container.visibility = View.VISIBLE
                val bar = find(container, "media_progress_bar") as? SeekBar
                val elapsed = find(container, "media_elapsed_time") as? TextView
                val total = find(container, "media_total_time") as? TextView
                bar?.let {
                    it.isClickable = false
                    it.isFocusable = false
                    // Detaching from meta_data_info removes sibling @id/coverParent; anchor bar to container top.
                    it.layoutParams?.let { lp ->
                        runCatching { set(lp, "topToTop", 0) }
                        runCatching { set(lp, "topToBottom", -1) }
                        it.layoutParams = lp
                    }
                    if (it.max <= 0) it.max = 1000
                }
                addView(
                    container,
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                nativeContainer = container
                nativeSeekBar = bar
                nativeElapsed = elapsed
                nativeTotal = total
                seekBarFgActiveColor = colorRes(
                    miPlayCtx,
                    "miplay_media_progress_bar_foreground_primary_color",
                    Color.WHITE,
                )
                seekBarFgDisableColor = colorRes(
                    miPlayCtx,
                    "miplay_media_progress_bar_foreground_primary_disable_color",
                    seekBarFgActiveColor,
                )
                bar?.let { applyNativeSeekBarMaterial(it, miPlayCtx) }
            }
        }

        private fun applyNativeSeekBarMaterial(bar: SeekBar, miPlayCtx: Context) {
            runCatching {
                val loader = bar.javaClass.classLoader ?: return@runCatching
                val blurCompat = Class.forName("miui.systemui.util.MiBlurCompat", false, loader)
                val opened = blurCompat
                    .getMethod("getBackgroundMaterialOpenedInDefaultTheme", Context::class.java)
                    .invoke(null, miPlayCtx) as? Boolean ?: false
                if (opened) {
                    blurCompat.getMethod("setMiViewBlurModeCompat", View::class.java, Int::class.javaPrimitiveType)
                        .invoke(null, bar, 2)
                    val tokenClass = Class.forName("miui.systemui.util.MiuiColorBlendToken", false, loader)
                    val tokenInst = tokenClass.getField("INSTANCE").get(null)
                    val colors = tokenClass.getMethod("getCC_DETAIL_PANEL_MORE_BUTTON_BLEND_COLORS").invoke(tokenInst)
                    val setBlend = blurCompat.methods.firstOrNull {
                        it.name == "setMiBackgroundBlendColors" && it.parameterCount == 2
                    }
                    if (setBlend != null && colors != null) {
                        setBlend.invoke(null, bar, colors)
                    }
                }
                val tokensKt = Class.forName("com.android.systemui.miplay.material.MiPlayMaterialTokensKt", false, loader)
                val timelineToken = tokensKt.getMethod("getTimeLineSeekBarToken").invoke(null)
                val materialExt = Class.forName("miui.systemui.ui.material.MaterialBackgroundExt", false, loader)
                val setMaterial = materialExt.methods.firstOrNull {
                    it.name == "setMaterialBackground" && it.parameterCount == 3
                }
                if (setMaterial != null && timelineToken != null) {
                    setMaterial.invoke(null, bar, timelineToken, false)
                }
            }
        }

        private fun colorRes(context: Context, name: String, fallback: Int): Int {
            val id = context.resources.getIdentifier(name, "color", "miui.systemui.plugin")
            return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
        }

        private fun applySeekBarColors(bar: SeekBar, active: Boolean) {
            val fg = if (active) seekBarFgActiveColor else Color.TRANSPARENT
            val fgDisable = if (active) seekBarFgDisableColor else Color.TRANSPARENT
            runCatching { call(bar, "setForegroundPrimaryColor", fg) }
            runCatching { call(bar, "setForegroundPrimaryDisableColor", fgDisable) }
        }

        fun preferredHeightPx(): Int {
            val container = nativeContainer ?: return 0
            if (cachedPreferredHeight > 0) return cachedPreferredHeight
            val w = (width.takeIf { it > 0 } ?: (180 * density).roundToInt())
            container.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val measured = container.measuredHeight
            if (measured > 0) cachedPreferredHeight = measured
            return measured
        }

        fun syncNow() {
            val root = mediaRoot.get() ?: return
            if (!settings.enabled || !hasMediaProgress(root)) return
            val empty = mediaEmpty(root)
            if (empty) cachedState = null
            else readMediaProgress(root)?.let { cachedState = it }
            val state = cachedState ?: Triple(0f, 0L, 0L)
            val bar = nativeSeekBar
            if (bar != null) {
                val hasDuration = !empty && state.third > 0L
                if (bar.isEnabled != hasDuration) bar.isEnabled = hasDuration
                applySeekBarColors(bar, hasDuration)
                val maxVal = bar.max.coerceAtLeast(1)
                val nextProgress = if (hasDuration) (state.first * maxVal).roundToInt().coerceIn(0, maxVal) else 0
                if (bar.progress != nextProgress) bar.progress = nextProgress
                nativeElapsed?.let { applyMediaTime(it, state.second) }
                nativeTotal?.let { applyMediaTime(it, state.third) }
                val container = nativeContainer
                if (container != null && width > 0 && height > 0) {
                    container.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                    )
                    container.layout(0, 0, width, height)
                }
                container?.invalidate()
            } else {
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(ticker)
            syncNow()
            post(ticker)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(ticker)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (nativeContainer != null) return
            val root = mediaRoot.get() ?: return
            if (!settings.enabled || !hasMediaProgress(root)) return
            if (mediaEmpty(root)) cachedState = null
            else readMediaProgress(root)?.let { cachedState = it }
            val state = cachedState ?: Triple(0f, 0L, 0L)
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
                type.isInstance(arg) ||
                    (type == Int::class.javaPrimitiveType && arg is Int) ||
                    (type == Long::class.javaPrimitiveType && arg is Long) ||
                    (type == Boolean::class.javaPrimitiveType && arg is Boolean)
            }
        } ?: return null
        return m.apply { isAccessible = true }.invoke(target,*args)
    }
    companion object {
        private const val ADAPTER = "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
        private const val DISTRIBUTOR = "miui.systemui.controlcenter.panel.main.MainPanelContentDistributor"
        private const val QS_CARDS_CONTROLLER = "miui.systemui.controlcenter.panel.main.qs.QSCardsController"
        private const val MEDIA_HOLDER = "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder"
        private const val MEDIA_ANIMATOR = "miui.systemui.controlcenter.panel.secondary.media.MediaPanelAnimator"
    }
}
