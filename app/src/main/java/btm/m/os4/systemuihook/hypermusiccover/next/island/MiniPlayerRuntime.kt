// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.AlbumArt
import btm.m.os4.systemuihook.hypermusiccover.next.CardTouch
import btm.m.os4.systemuihook.hypermusiccover.next.MediaCard
import btm.m.os4.systemuihook.hypermusiccover.next.ClockCollapse
import btm.m.os4.systemuihook.hypermusiccover.next.ClockRoom
import btm.m.os4.systemuihook.hypermusiccover.next.ClockRoomPolicy
import btm.m.os4.systemuihook.hypermusiccover.next.CoverMorphMotion
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.TrackName
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/** Owns one mini player for each live keyguard shortcut host. */
object MiniPlayerRuntime {
    @JvmField var hostMaterial: ((ImageView, ClassLoader) -> Unit)? = null
    @JvmStatic fun refreshHostMaterial() { materialGeneration++; refresh() }

    private data class Held(val left: View, val right: View, val controller: MiniPlayerController,
                            val listener: View.OnAttachStateChangeListener? = null)

    /** One thread for scaling artwork, so a track change never decodes on the main thread. */
    internal val artWorker: java.util.concurrent.ExecutorService =
        Gen.executor("MCMiniArt", Thread.MIN_PRIORITY, true)
    private val controllers = WeakHashMap<ViewGroup, Held>()
    private var materialFailed = false
    private val selectionLock = Any()
    private val sessionSelection = MiniPlayerSessionSelection()
    private var prefs: SharedPreferences? = null
    private var loader: ClassLoader? = null
    private var lastRoot: WeakReference<View>? = null
    private var lastShortcutController: WeakReference<Any>? = null
    private var hooksInstalled = false

    private fun prefs(context: Context): SharedPreferences = prefs
        ?: context.applicationContext.getSharedPreferences("hyperchanger_hmc_next_mini", Context.MODE_PRIVATE)
            .also { prefs = it }

    @JvmStatic fun configJson(context: Context): String = MiniPlayerConfig.fromPreferences(prefs(context))

    /** Only the automatic cover entry changes; an explicitly opened cover stays open. */
    @JvmStatic fun mediaCollapsedByDefault(context: Context?): Boolean {
        if (context == null) return false
        val config = JSONObject(configJson(context))
        return config.optBoolean(MiniPlayerConfig.ENABLED) &&
            config.optBoolean(MiniPlayerConfig.MEDIA_COLLAPSED_DEFAULT)
    }

    /** MiniPlayerConfig.NAV_KEEP_ON as saved: read once, then kept by applyConfig. */
    @Volatile private var navKeepOnSaved: Boolean? = null

    /** Whether a navigation page keeps the lock screen lit (ImmersiveHost.holdScreen, #63). */
    @JvmStatic fun navKeepOn(context: Context): Boolean = navKeepOnSaved
        ?: JSONObject(configJson(context)).optBoolean(MiniPlayerConfig.NAV_KEEP_ON)
            .also { navKeepOnSaved = it }

    /**
     * Whether the bottom's lasting lines stand after the date (DateStatus): with the islands on
     * and the setting on, which is how it has been since 2026-10-09; off puts them back at the
     * bottom (MiniPlayerConfig.STATUS_AT_DATE).
     */
    @JvmStatic fun statusAtDate(context: Context): Boolean {
        val config = JSONObject(configJson(context))
        return config.optBoolean(MiniPlayerConfig.ENABLED) &&
            config.optBoolean(MiniPlayerConfig.STATUS_AT_DATE, true)
    }

    @JvmStatic fun applyConfig(context: Context, raw: String?) {
        val saved = MiniPlayerConfig.apply(prefs(context), raw)
        DateStatus.configChanged()
        val keep = JSONObject(saved).optBoolean(MiniPlayerConfig.NAV_KEEP_ON)
        if (keep != navKeepOnSaved) {
            navKeepOnSaved = keep
            ImmersiveHost.navKeepOnChanged()
        }
        syncNotificationGrouping(context)
        lastRoot?.get()?.let { root -> root.post { attach(root, lastShortcutController?.get()) } }
        refresh()
    }

    private fun syncNotificationGrouping(context: Context) {
        val config = JSONObject(configJson(context))
        LockIslands.setNormalsInStack(config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true))
        LockIslands.setGroupByApp(config.optBoolean(MiniPlayerConfig.ENABLED) &&
            !config.optBoolean(MiniPlayerConfig.NORMALS_IN_STACK, true) &&
            config.optInt(MiniPlayerConfig.STYLE) == MiniPlayerConfig.STYLE_STACK &&
            config.optBoolean(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP))
    }

    /** The old state file is migration data; current preferences must win on every restart. */
    @JvmStatic fun restoreLegacyConfig(context: Context, raw: String?) {
        if (!prefs(context).contains("config")) applyConfig(context, raw)
    }

    @JvmStatic fun refresh() {
        synchronized(controllers) { controllers.values.map { it.controller } }
            .forEach { runCatching { it.refresh() } }
    }

    /**
     * Each hook on its own: a build that renamed one class costs the feature that needed it,
     * not the rest of the mini player.
     */
    /**
     * Retirement: every controller's own teardown - its frames, rows, discs, host views, session
     * listeners and LockIslands registration - and the attach listeners that would otherwise
     * outlive them. The hooks are the ledger's, and stay.
     */
    @JvmStatic
    fun retire() {
        installTask?.let { Gen.removeCallbacks(it) }
        installTask = null
        val all = synchronized(controllers) {
            val held = controllers.entries.map { it.key to it.value }
            controllers.clear()
            held
        }
        for ((host, h) in all) {
            runCatching { Gen.unattachState(host, h.listener) }
            runCatching { h.controller.destroy() }
        }
        // The OEM's full-AOD controller is a singleton and holds the proxy directly. Left
        // there, it is a host reference into this class loader - and `added` was a local, so
        // the next generation could never add its own listener to that controller.
        // The proxy and the reflected methods stay: they are install-time, a dry run does not
        // install again, and only this generation's own statics hold them.
        val c = aodController?.get()
        val l = aodListener
        val remove = aodRemove
        // For a dry run's reattach, which has no handover to read the controller from.
        lastAodController = c?.let(::WeakReference)
        aodController = null
        aodListenerAdded = false
        if (c != null && l != null) {
            runCatching {
                if (remove != null) {
                    remove.invoke(c, l)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val list = Xp.getObjectField(c, "mListeners") as MutableList<Any?>
                    list.remove(l)
                }
            }.onFailure { Xp.log("MCMini: full-AOD listener not removed: $it") }
        }
        aodDimFrames = 0
        aodDimLook = null
        aodDimDepth = -1f
        aodDimFlat = false
        // The AOD dressing belongs to the views that just went; left here it would hand the
        // next run a listener per orphaned view.
        aodDimHandler.removeCallbacks(aodDimSettle)
        aodDimArgs = null
        dressedViews.clear()
        notificationDressedViews.clear()
        // Rows the transient-removal hook is holding for a morph: with the controllers gone the
        // morph will not land, and the map must not keep telling the stack to keep them. One the
        // stack already tried to take away is waiting on us for it, so it is let go now.
        for (row in heldTransients.filterValues { it }.keys.toList()) {
            runCatching { releaseTransient(row) }
        }
        heldTransients.clear()
        passTransient = false
    }

    /**
     * Retirement's other half: a dry run does not rebuild the keyguard, so the shortcut hook
     * that normally builds a controller never fires again. The row it hung off is still there;
     * build on it the same way a config change does.
     */
    @JvmStatic
    fun reattach() {
        adoptAod(lastAodController?.get())
        lastRoot?.get()?.let { root ->
            if (root.isAttachedToWindow) scheduleInstall(root, lastShortcutController?.get())
        }
    }

    /**
     * What a hot reload hands over: the shortcut row the last controller hung off and the OEM
     * controller it was built from. Both are host objects. Without them the new generation has
     * nothing to reattach to - a hot reload does not rebuild the keyguard, so the shortcut hook
     * that normally supplies this pair will not fire again. The full-AOD controller goes along
     * for the same reason (see [adoptAod]).
     */
    @JvmStatic
    fun save(): Array<Any?> =
        arrayOf(lastRoot?.get(), lastShortcutController?.get(), aodController?.get())

    /** The new generation's half; see [save]. Main thread, like the attach path. */
    @JvmStatic
    fun adopt(root: View?, shortcutController: Any?, aod: Any?) {
        adoptAod(aod)
        if (root == null || !root.isAttachedToWindow) {
            Xp.log("MCMini: no shortcut row to adopt ($root)")
            return
        }
        scheduleInstall(root, shortcutController)
    }

    @JvmStatic fun install(classLoader: ClassLoader) {
        loader = classLoader
        if (hooksInstalled) return
        hooksInstalled = true
        runCatching {
            val cls = Xp.findClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView",
                classLoader,
            )
            Xp.hookAll("sysui.mini.header_visibility", cls, "setVisibility") { chain ->
                val args = chain.args.toTypedArray()
                if (args.size == 1 && args[0] == View.VISIBLE && keepNativeHeaderInvisible()) {
                    args[0] = View.INVISIBLE
                }
                chain.proceed(args)
            }
        }.onFailure { Xp.w("MCMini: native header visibility hook unavailable: $it") }
        runCatching {
            val cls = Xp.findClass("com.android.keyguard.shortcut.MiuiShortcutController", classLoader)
            Xp.hookAll("sysui.mini.shortcut_views", cls, "addShortcutViews") { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? View)?.let { scheduleInstall(it, chain.thisObject) }
                result
            }
        }.onFailure { Xp.w("MCMini: shortcut hook unavailable, no mini player: $it") }
        // The lock screen's list scrolling under the finger, wherever the gesture started -
        // between rows too, where the card swipe never sees it: the rows are the list's again.
        runCatching {
            val cls = Xp.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", classLoader)
            Xp.hookAll("sysui.mini.scroll_touch", cls, "onScrollTouch") { chain ->
                val result = chain.proceed()
                val event = chain.args.firstOrNull() as? MotionEvent
                val stack = chain.thisObject as? View
                if (event?.actionMasked == MotionEvent.ACTION_MOVE && stack != null &&
                    runCatching { Xp.getObjectField(stack, "mIsBeingDragged") == true }.getOrDefault(false)) {
                    live().forEach { it.onNativeListScroll(stack) }
                }
                result
            }
        }.onFailure { Xp.w("MCMini: list scroll hook unavailable: $it") }
        // A row given back to its island ahead of its morph home is left to the stack as a
        // transient view: laid out no more, drawn still. The stack takes it away when its own
        // animation ends; the morph's rows are kept until the morph has landed (holdTransient).
        for (name in listOf("com.android.systemui.statusbar.notification.row.ExpandableView",
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")) {
            runCatching {
                Xp.hookAll("sysui.mini.remove_transient", Xp.findClass(name, classLoader), "removeFromTransientContainer") { chain ->
                    val v = chain.thisObject as? View
                    if (v != null && !passTransient && heldTransients.containsKey(v) && !reAdding()) {
                        heldTransients[v] = true
                        null
                    } else {
                        // Being added back as a row: the stack takes it out of the transient views
                        // itself first, and held there, addView threw "already has a parent" and
                        // SystemUI went down (2026-09-26). It is the stack's row from here.
                        if (v != null) heldTransients.remove(v)
                        chain.proceed()
                    }
                }
            }.onFailure { Xp.w("MCMini: transient hold unavailable on $name: $it") }
        }
        runCatching {
            val cls = Xp.findClass(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutControllerInjectorImpl",
                classLoader)
            Xp.hookAll("sysui.mini.stack_bottom", cls, "updateStackingBottomForMiui") { chain ->
                val args = chain.args.toTypedArray()
                val bottom = args.getOrNull(0) as? Float
                if (bottom == null) chain.proceed() else {
                    boundsInjector = WeakReference(chain.thisObject)
                    boundsBottom = bottom
                    args[0] = notificationBottom(chain.thisObject, bottom).also { boundsGiven = it }
                    val result = chain.proceed(args)
                    if (floorMoving) {
                        floorMoving = false
                        runCatching { Xp.getObjectField(chain.thisObject, "layout") as? View }
                            .getOrNull()?.let(::restack)
                    }
                    result
                }
            }
        }.onFailure { Xp.w("MCMini: stacking bottom hook unavailable, cards may meet a lifted row: $it") }
        MiniPlayerScene.install(classLoader)
        installCardMaterialHooks(classLoader)
        runCatching { installAodDim(classLoader) }
            .onFailure { Xp.w("MCMini: full-AOD dim unavailable: $it") }
        LockIslands.install(classLoader)
    }

    /** Rows kept drawn as transient views, and whether the stack has tried to take each away. */
    private val heldTransients = java.util.WeakHashMap<View, Boolean>()
    private var passTransient = false

    /** [row] stays in the stack's transient views until [releaseTransient]. */
    internal fun holdTransient(row: View) {
        if (!heldTransients.containsKey(row)) heldTransients[row] = false
    }

    /**
     * Whether this removal is the pipeline adding the row back (ShadeViewDiffer attaching
     * children), not the stack's animation ending. Only asked for a held row, so rarely.
     */
    private fun reAdding(): Boolean = Throwable().stackTrace.any {
        it.methodName == "addChildAt" || it.methodName == "attachChildren" ||
            it.className.endsWith("ShadeViewDiffer") || it.methodName.startsWith("addContainerView")
    }

    /** [row] is a row in the stack again: nothing to take away, the stack moved it back itself. */
    internal fun forgetTransient(row: View) {
        heldTransients.remove(row)
    }

    /**
     * [row] is the stack's again: taken away now if the stack has already tried, or when its own
     * animation ends. Back in the stack as a row (re-added), there is nothing to take away.
     */
    internal fun releaseTransient(row: View) {
        val tried = heldTransients.remove(row) ?: return
        if (!tried) return
        passTransient = true
        try {
            Xp.callMethod(row, "removeFromTransientContainer")
        } catch (t: Throwable) {
            Xp.w("MCMini: transient release failed: $t")
        } finally {
            passTransient = false
        }
    }

    /**
     * Where the shortcut row was last seen, as a share of the window's height: the row's place
     * for a controller made with both shortcuts switched off (MiniPlayerController.rowCentreY).
     */
    @Volatile internal var rowCentreShare = Float.NaN

    /** `op fod`: a sensor rect to lay the row out against instead of the phone's; empty = none. */
    @Volatile internal var fodProbe: android.graphics.Rect? = null
    private var fodReadAt = 0L
    private var fodRead: android.graphics.Rect? = null

    /**
     * The under-display fingerprint sensor, in screen pixels, on a phone that has one with a
     * finger enrolled; null otherwise. Where it is low enough to meet the row of islands - an
     * optical sensor sits about as low as the torch and camera - the row goes above it: a swipe
     * across the islands began on the sensor, and the sensor took it (#66).
     *
     * SystemUI's own reading (MiuiGxzwUtils.getFodPosition: the vendor's
     * persist.vendor.sys.fp.fod.location.X_Y and size scaled to the screen as set). That answers
     * a default rect on a phone without the sensor, so ro.hardware.fp.fod is asked first.
     * Read again every few seconds at most: it is asked on every layout of the row.
     */
    internal fun fingerprintArea(context: Context): android.graphics.Rect? {
        fodProbe?.let { return it.takeUnless { r -> r.isEmpty } }
        val now = android.os.SystemClock.uptimeMillis()
        if (fodReadAt != 0L && now - fodReadAt < 5000L) return fodRead
        fodReadAt = now
        fodRead = runCatching { readFingerprintArea(context) }
            .onFailure { Xp.log("MCMini: fingerprint area unreadable: $it") }
            .getOrNull()
        return fodRead
    }

    private fun readFingerprintArea(context: Context): android.graphics.Rect? {
        val props = Class.forName(listOf("android", "os", "SystemProperties").joinToString("."))
        val fod = props.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "ro.hardware.fp.fod", false) as Boolean
        if (!fod) return null
        // No finger enrolled, no print on the lock screen and nothing there to touch.
        // FingerprintManager by name: the SDK this builds against no longer has it.
        val enrolled = runCatching {
            context.getSystemService("fingerprint")
                ?.let { Xp.callMethod(it, "hasEnrolledFingerprints") as Boolean }
        }.getOrNull() ?: true
        if (!enrolled) return null
        val utils = Xp.findClass("com.miui.keyguard.biometrics.fod.MiuiGxzwUtils",
            loader ?: context.classLoader)
        val rect = utils.getMethod("getFodPosition", Context::class.java)
            .invoke(null, context) as android.graphics.Rect
        return android.graphics.Rect(rect).takeUnless { it.isEmpty }
    }

    /**
     * The keyguard's stack piles its cards down to a bottom SystemUI gives it
     * (NotificationStackScrollLayoutControllerInjectorImpl.updateStackingBottomForMiui, from the
     * notification container's bounds) - on this phone 2257, a fixed distance above the screen's
     * foot, the sensor nowhere in it. The row lifted off a low sensor went up into that room, and
     * the media card at the bottom of the pile drew over it. So that bottom is kept above the
     * lifted row: the pile, the list's scroll range and the stack's touches all read it. Only on
     * the lock screen - the shade takes the same bottom, and floored there lost the touches and
     * the scroll below the floor - and kept while the lift is on, the row shown or not: a floor
     * that came and went with the row moved every card on the lock screen with it.
     */
    private var boundsInjector: WeakReference<Any>? = null
    /** The bottom SystemUI gave last, and what it was given instead; NaN until it gave one. */
    private var boundsBottom = Float.NaN
    private var boundsGiven = Float.NaN
    /** The bottom was given again for the lift: the stack is laid out again, animated, once it has it. */
    private var floorMoving = false

    /** Asked inside SystemUI's own call: anything going wrong gives SystemUI's bottom back. */
    private fun notificationBottom(injector: Any, bottom: Float): Float = runCatching {
        if (!showingKeyguardNotifications(injector)) return@runCatching bottom
        val floor = live().firstNotNullOfOrNull { it.stackFloor() } ?: return@runCatching bottom
        min(bottom, floor)
    }.getOrElse {
        Xp.w("MCMini: notification bottom left to SystemUI: $it")
        bottom
    }

    /** SystemUI's own test in updateStackingBottomForMiui for the stack being the lock screen's. */
    private fun showingKeyguardNotifications(injector: Any): Boolean {
        val lazy = Xp.getObjectField(injector, "notifContainerViewModel")
        val model = Xp.callMethod(lazy!!, "get")
        return Xp.callMethod(Xp.callMethod(model!!, "isShowingKeyguardNotification")!!, "getValue") == true
    }

    /** The lift's floor, or the lack of one, given to SystemUI if it is not what it has. */
    internal fun checkStackFloor() {
        val injector = boundsInjector?.get() ?: return
        if (boundsBottom.isNaN()) return
        if (kotlin.math.abs(notificationBottom(injector, boundsBottom) - boundsGiven) < 1f) return
        floorMoving = true
        runCatching { Xp.callMethod(injector, "updateStackingBottomForMiui", boundsBottom) }
            .onFailure { floorMoving = false; Xp.w("MCMini: notification bottom not given again: $it") }
        Xp.log("MCMini: notification bottom ${boundsBottom.toInt()} -> ${boundsGiven.toInt()}")
    }

    /**
     * The stack laid out again for a new bottom, as for a new top padding: its own animation
     * (the top padding event takes every row to its new place). Only the field is set otherwise,
     * and the rows stayed where they were until something else moved them.
     */
    private fun restack(stack: View) {
        runCatching {
            if (Xp.getObjectField(stack, "mAnimationsEnabled") == true && Xp.getObjectField(stack, "mIsExpanded") == true) {
                Xp.setBooleanField(stack, "mTopPaddingNeedsAnimation", true)
                Xp.setBooleanField(stack, "mNeedsAnimation", true)
            }
        }.onFailure { Xp.log("MCMini: stack moves to its new bottom unanimated: $it") }
        runCatching { Xp.callMethod(stack, "requestChildrenUpdate") }
            .onFailure { Xp.w("MCMini: stack not laid out for its new bottom: $it") }
    }

    /** For `op fod`. */
    private fun describeStackFloor(): String =
        "notifBottom sysui=${boundsBottom.toInt()} given=${boundsGiven.toInt()}" +
            (if (boundsInjector?.get() == null) " (not given yet)" else "")

    /**
     * Bumped whenever the card is dressed differently; part of the pill's appearance key. Not on
     * every dressing: the OEM dresses the card again on every rebind with the same calls, and
     * each bump rebuilt the pill's material layer and refreshed the whole row (PR #15).
     */
    @Volatile internal var materialGeneration = 0
        private set

    /**
     * The pill's material key: the effect, then the generation after a '#'. The pill builds a
     * new layer only when the effect before the '#' changes (MiniPlayerMaterialState).
     */
    internal val materialStyleKey: String get() = "$cardEffect#$materialGeneration"
    /** The last recipe's values, for telling a new one from the same one again. */
    private var materialSignature: Any? = null
    private var materialRepeats = 0

    /** Effects run on the main thread only; this counts how deep inside one another they are. */
    private var effectDepth = 0

    /** One material call the card got, to be made again on the pill with the pill put in. */
    private class Recorded(val method: Method, val args: Array<Any?>, val viewAt: Int)

    /** The card's material as it was last dressed: its background, then every call in order. */
    @Volatile private var cardRecipe: List<Recorded>? = null
    @Volatile private var cardBackground: android.graphics.drawable.Drawable.ConstantState? = null
    private var notificationBlend: IntArray? = null
    private var notificationGlass: FloatArray? = null
    private var notificationBlendMethod: Method? = null
    private var notificationGlassMethod: Method? = null
    private var notificationBackground: android.graphics.drawable.Drawable.ConstantState? = null
    private var notificationSignature: Any? = null
    private var notificationSource: WeakReference<View>? = null
    private var notificationFocusCheck: Method? = null
    private var recording: ArrayList<Recorded>? = null
    private var recordTarget: View? = null
    private var recordDepth = 0

    /**
     * The card's material, copied call for call. NotificationViewEffectHelper has eight media
     * card effects - plain, blur, blur on the keyguard, glass, glass on the keyguard, glass on a
     * light wallpaper, glass in the full-screen AOD - and the keyguard ones run the plain blur
     * inside themselves. Copying three of them by hand, the first version saw that inner blur
     * as the card's material and dressed the pill in the shade's dark one. So nothing is
     * copied by hand any more: while an effect dresses the lock screen's card, every material
     * call made on its media_bg is recorded, and the pill gets the same calls, in order.
     */
    private fun installCardMaterialHooks(classLoader: ClassLoader) {
        notificationFocusCheck = runCatching {
            Xp.findClass("com.android.systemui.statusbar.notification.utils.FocusUtils", classLoader)
                .getDeclaredMethod("isFocusNotification", android.app.Notification::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
        val effects = runCatching {
            val helper = Xp.findClass(
                "com.android.systemui.statusbar.notification.style.vieweffect.NotificationViewEffectHelper",
                classLoader)
            val field = helper.declaredFields.first { it.name == "mediaViewEffectsMap" }
            field.isAccessible = true
            (field.get(null) as Map<*, *>).values.filterNotNull().map { it.javaClass }.distinct()
        }.getOrElse {
            Xp.w("MCMini: effect map unavailable, naming the effects instead: $it")
            listOf("MediaViewNormalEffect", "MediaViewBlurEffect", "MediaViewBlurOnKeyguardEffect",
                "MediaViewGlassEffect", "MediaViewGlassOnKeyguardEffect",
                "MediaViewGlassOnKeyguardLightWallPaperEffect", "MediaViewGlassFullAodEffect")
                .mapNotNull { name -> runCatching { Xp.findClass(
                    "com.android.systemui.statusbar.notification.style.vieweffect.$name", classLoader)
                }.getOrNull() }
        }
        effects.forEach { cls ->
            runCatching {
                Xp.hookAll("sysui.mini.apply", cls, "apply") { chain ->
                    val target = chain.args.firstOrNull() as? View
                    val card = Main.miniPlayerMediaHeader()
                    // The pill and the islands live on the lock screen, so they wear the lock
                    // screen's effect. The OEM dresses the card in the plain one too - often the
                    // plain effect first and the keyguard one 50ms later, sometimes the plain one
                    // alone - and recorded last, the plain frost's dark recipe went onto the pill
                    // while its words took the keyguard card's near black: dark on dark after a
                    // wallpaper change (#87, the reporter's log ended on MediaViewBlurEffect).
                    // The plain one is still taken until a keyguard one has been seen at all.
                    val keyguardKind = isKeyguardEffect(cls.simpleName)
                    val mine = effectDepth == 0 && target != null && (card == null || target === card) &&
                        (keyguardKind || !isKeyguardEffect(cardEffect))
                    if (mine) {
                        recordTarget = Main.miniPlayerMediaBg(target)
                        recording = ArrayList()
                    }
                    effectDepth++
                    val result = try {
                        chain.proceed()
                    } finally {
                        effectDepth--
                    }
                    val recorded = recording
                    if (mine && recorded.isNullOrEmpty()) {
                        // An effect that made no material call on a card background - with no
                        // media card on the lock screen, one on another media view: kept, it
                        // replaced the card's recipe with nothing, and the pill, dressed again
                        // from it, went clear while the discs kept their glass (2026-09-25).
                        recording = null
                        recordTarget = null
                        if (emptyEffect != cls.simpleName) {
                            emptyEffect = cls.simpleName
                            Xp.log("MCMini: ${cls.simpleName} recorded no material calls; recipe kept")
                        }
                    } else if (mine) {
                        val bg = recordTarget
                        recording = null
                        recordTarget = null
                        val signature = materialSignature(cls.name, recorded!!, bg?.background)
                        if (signature != materialSignature) {
                            materialSignature = signature
                            cardRecipe = recorded
                            cardBackground = bg?.background?.constantState
                            runCatching { keepRecipe(target!!.context, recorded, bg?.background) }
                                .onFailure { Xp.log("MCMini: recipe not kept: $it") }
                            if (cardEffect != cls.simpleName) Xp.log("MCMini: card material -> ${cls.simpleName}")
                            cardEffect = cls.simpleName
                            materialGeneration++
                            refresh()
                        } else materialRepeats++
                    }
                    result
                }
            }.onFailure { Xp.w("MCMini: ${cls.simpleName} hook unavailable: $it") }
        }
        // What the effects call on the card's background: record the outermost of them.
        listOf(
            "com.android.systemui.statusbar.notification.utils.NotificationUtil" to
                setOf("applyElementViewBlend"),
            "com.miui.systemui.util.MiGlassCompat" to setOf("setMiGlassBlurRadius",
                "setMiGlassCompat", "setMiGlassSdfMaxSizeCompat", "setMiViewMaterialTypeCompat"),
            "com.miui.systemui.util.MiBlurCompat" to setOf("addMiBackgroundBlendColorCompat",
                "clearMiBackgroundBlendColorCompat", "setBlurOutlineEnable", "setGlassOutlineEnable",
                "setMiBackgroundBlendColors", "setMiBackgroundBlendColors\$default",
                "setMiBackgroundBlendColorsNew\$default", "setMiBackgroundBlurModeCompat",
                "setMiBackgroundBlurRadiusCompat", "setMiBackgroundBlurScaleRatioCompat",
                "setMiSelfBlurTypeCompat", "setMiViewBlurModeCompat", "setPassWindowBlurEnabledCompat"),
        ).forEach { (className, names) ->
            runCatching {
                val cls = Xp.findClass(className, classLoader)
                cls.declaredMethods.filter {
                    it.name in names && java.lang.reflect.Modifier.isStatic(it.modifiers)
                        && it.parameterTypes.any { p -> View::class.java.isAssignableFrom(p) }
                }.forEach { m ->
                    m.isAccessible = true
                    Xp.hook("sysui.mini.glass." + Xp.slug(className.substringAfterLast('.')) + "." + Xp.slug(m.name), m) { chain ->
                        val list = recording
                        val bg = recordTarget
                        if (list == null || bg == null || recordDepth > 0) {
                            val result = chain.proceed()
                            if (list == null && recordDepth == 0) runCatching {
                                recordNotificationMaterial(m, chain.args.toTypedArray())
                            }
                            return@hook result
                        }
                        val args = chain.args.toTypedArray()
                        val at = args.indexOfFirst { it === bg }
                        if (at < 0) return@hook chain.proceed()
                        recordDepth++
                        try {
                            chain.proceed()
                        } finally {
                            recordDepth--
                            // Copied: the OEM hands the same array to the next dressing, changed.
                            list.add(Recorded(m, Array(args.size) { i -> copyArray(args[i]) }, at))
                        }
                    }
                }
            }.onFailure { Xp.w("MCMini: material recorder on $className unavailable: $it") }
        }
    }

    private fun copyArray(value: Any?): Any? = when (value) {
        is IntArray -> value.copyOf()
        is FloatArray -> value.copyOf()
        else -> value
    }

    /** Only ordinary rows supply the glass; expanded focus cards have their own darker material. */
    private fun recordNotificationMaterial(method: Method, args: Array<Any?>) {
        if (!Main.keyguardLocked() || MiniPlayerScene.aodActive) return
        if (method.name != "applyElementViewBlend" && method.name != "setMiGlassCompat") return
        val element = args.firstOrNull { it is View } as? View ?: return
        if (element is MaterialElement) return
        var ancestor: View? = element
        var row: View? = null
        while (ancestor != null) {
            if (ancestor.javaClass.name.contains("ExpandableNotificationRow")) {
                row = ancestor
                break
            }
            ancestor = ancestor.parent as? View
        }
        val source = row ?: return
        val ordinary = runCatching {
            val entry = Xp.callMethod(source, "getEntry")
            val notification = (Xp.getObjectField(entry, "mSbn") as?
                android.service.notification.StatusBarNotification)?.notification ?: return@runCatching false
            // A media row or a focus/super-island row must never replace the ordinary palette.
            // If classification is unavailable, keep the last ordinary look or the keyguard
            // fallback rather than sampling an unknown card.
            !notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) &&
                notificationFocusCheck?.invoke(null, notification) == false
        }.getOrDefault(false)
        if (!ordinary) return
        val previousSource = notificationSource?.get()
        if (previousSource != null && previousSource !== source && previousSource.isAttachedToWindow) return
        notificationSource = WeakReference(source)
        when (method.name) {
            "applyElementViewBlend" -> {
                // An ordinary row blends two layers (NotificationRowBlurEffect: the int overload,
                // four values), the media card three. Only six were taken, so the row's look was
                // never recorded and the islands wore the fallback - the media card's modes over
                // the focus list's darker colours, a 60% black layer the torch and camera do not
                // have (#73, 2026-10-14). The int overload passes on to the array one, which is
                // the one recorded: taken from the outer call, the method was the eight-argument
                // overload, which the replay's five arguments do not fit.
                val colors = args.firstOrNull { it is IntArray } as? IntArray ?: return
                if (colors.isEmpty() || colors.size % 2 != 0) return
                notificationBlendMethod = method
                notificationBlend = colors.copyOf()
            }
            "setMiGlassCompat" -> {
                notificationGlassMethod = method
                notificationGlass =
                    (args.firstOrNull { it is FloatArray } as? FloatArray)?.copyOf()
            }
        }
        notificationBackground = element.background?.constantState ?: notificationBackground
        val blend = notificationBlend ?: return
        val glass = notificationGlass ?: return
        val signature = listOf(blend.toList(), glass.toList())
        if (signature != notificationSignature) {
            notificationSignature = signature
            materialGeneration++
            refresh()
        }
    }

    /** A recipe's values: the effect, the background, and every call with its arguments. */
    private fun materialSignature(effect: String, calls: List<Recorded>,
                                  bg: android.graphics.drawable.Drawable?): Any {
        val background: Any? = when (bg) {
            null -> null
            is GradientDrawable -> listOf(bg.javaClass.name, bg.color?.defaultColor, bg.color?.isStateful,
                bg.cornerRadius, MiniPlayerMaterialState.snapshot(bg.cornerRadii), bg.shape,
                bg.gradientType, bg.orientation, MiniPlayerMaterialState.snapshot(bg.colors))
            is android.graphics.drawable.ColorDrawable -> listOf(bg.javaClass.name, bg.color)
            else -> bg.constantState ?: bg
        }
        return listOf(effect, background, calls.map { call ->
            listOf(call.method, call.viewAt, call.args.mapIndexed { i, arg ->
                if (i == call.viewAt || arg is Context) null else MiniPlayerMaterialState.snapshot(arg)
            })
        })
    }

    // ---- the card's recipe, kept

    /**
     * The recipe is recorded only when the lock screen dresses a media card, and it lived in
     * memory: after SystemUI restarted with no music, there was no card to record from and the
     * row of notification islands was a flat grey fill until music played (2026-09-25). It is
     * kept in SystemUI's own preferences now, call by call - class, method, parameter types and
     * the plain values - and read back the first time a pill is dressed without one.
     */
    private const val RECIPE_KEY = "card_recipe_v1"
    private var recipeLoaded = false

    private fun keepRecipe(context: Context, list: List<Recorded>, bg: android.graphics.drawable.Drawable?) {
        val calls = org.json.JSONArray()
        for (r in list) {
            val args = org.json.JSONArray()
            r.args.forEachIndexed { i, a ->
                args.put(when {
                    i == r.viewAt -> JSONObject().put("t", "view")
                    a == null -> JSONObject().put("t", "null")
                    a is Int -> JSONObject().put("t", "i").put("x", a)
                    a is Long -> JSONObject().put("t", "l").put("x", a)
                    a is Float -> JSONObject().put("t", "f").put("x", a.toDouble())
                    a is Double -> JSONObject().put("t", "d").put("x", a)
                    a is Boolean -> JSONObject().put("t", "b").put("x", a)
                    a is String -> JSONObject().put("t", "s").put("x", a)
                    a is IntArray -> JSONObject().put("t", "ia").put("x", org.json.JSONArray(a.toList()))
                    a is FloatArray -> JSONObject().put("t", "fa")
                        .put("x", org.json.JSONArray(a.map { it.toDouble() }))
                    a is Context -> JSONObject().put("t", "ctx")
                    // Anything else cannot be written down: the recipe is not kept at all.
                    else -> error("argument ${a.javaClass.name} of ${r.method.name}")
                })
            }
            calls.put(JSONObject()
                .put("c", r.method.declaringClass.name)
                .put("m", r.method.name)
                .put("p", org.json.JSONArray(r.method.parameterTypes.map { it.name }))
                .put("v", r.viewAt)
                .put("a", args))
        }
        val kept = JSONObject().put("calls", calls)
        when (bg) {
            is GradientDrawable -> kept.put("bg", JSONObject()
                .put("t", "gradient")
                .put("color", bg.color?.defaultColor ?: 0)
                .put("radius", bg.cornerRadius.toDouble()))
            is android.graphics.drawable.ColorDrawable -> kept.put("bg", JSONObject()
                .put("t", "color").put("color", bg.color))
        }
        val text = kept.toString()
        val p = prefs(context)
        if (p.getString(RECIPE_KEY, null) != text) p.edit().putString(RECIPE_KEY, text).apply()
    }

    /** The recipe kept from an earlier dressing, when none has been recorded since SystemUI started. */
    private fun loadRecipe(context: Context, classLoader: ClassLoader) {
        if (recipeLoaded || cardRecipe != null) return
        recipeLoaded = true
        runCatching {
            val text = prefs(context).getString(RECIPE_KEY, null) ?: return
            val kept = JSONObject(text)
            val calls = kept.getJSONArray("calls")
            val list = ArrayList<Recorded>()
            fun type(name: String): Class<*> = when (name) {
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                "boolean" -> Boolean::class.javaPrimitiveType!!
                else -> Class.forName(name, false, classLoader)
            }
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val params = c.getJSONArray("p")
                val types = Array(params.length()) { type(params.getString(it)) }
                val method = Class.forName(c.getString("c"), false, classLoader)
                    .getDeclaredMethod(c.getString("m"), *types).apply { isAccessible = true }
                val a = c.getJSONArray("a")
                val args = Array<Any?>(a.length()) { j ->
                    val o = a.getJSONObject(j)
                    when (o.getString("t")) {
                        "view", "null" -> null
                        "i" -> o.getInt("x")
                        "l" -> o.getLong("x")
                        "f" -> o.getDouble("x").toFloat()
                        "d" -> o.getDouble("x")
                        "b" -> o.getBoolean("x")
                        "s" -> o.getString("x")
                        "ia" -> o.getJSONArray("x").let { x -> IntArray(x.length()) { x.getInt(it) } }
                        "fa" -> o.getJSONArray("x").let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } }
                        "ctx" -> context
                        else -> error("argument type ${o.getString("t")}")
                    }
                }
                list.add(Recorded(method, args, c.getInt("v")))
            }
            kept.optJSONObject("bg")?.let { bg ->
                cardBackground = when (bg.getString("t")) {
                    "gradient" -> GradientDrawable().apply {
                        setColor(bg.getInt("color"))
                        cornerRadius = bg.getDouble("radius").toFloat()
                    }.constantState
                    "color" -> android.graphics.drawable.ColorDrawable(bg.getInt("color")).constantState
                    else -> null
                }
            }
            cardRecipe = list
            cardEffect = "kept"
            Xp.log("MCMini: card recipe read back from preferences (${list.size} calls)")
        }.onFailure { Xp.log("MCMini: kept recipe unusable: $it") }
    }

    // ---- the full-screen AOD's dim, as the card takes it

    /**
     * Into the full-screen AOD the media card darkens - and the pill, the small island and the
     * discs, dressed in the card's material, did not (2026-09-26). The dim is not an alpha and
     * not an effect: NotifiFullAodController runs a "fullAod" fraction and hands every listener,
     * each frame, blend colours and glass parameters eased between the keyguard's and the AOD's
     * (notification_glass_params_on_keyguard -> _full_aod); the card's own listener
     * (MiuiMediaViewControllerImpl.mediaFullAodListener) puts them on its media_bg with
     * applyElementViewBlend and setMiGlassCompat. That happens outside any effect's apply, so
     * the recipe recorder never sees it. A listener of our own takes the same frames and puts
     * them on every view material() dressed, the way the card's does; once the wake's run is
     * over, each is dressed from the recipe again.
     */
    private val dressedViews: MutableSet<ImageView> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    private val notificationDressedViews: MutableSet<ImageView> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** The last frame's arguments while the AOD's colours are on the views; null otherwise. */
    private var aodDimArgs: Array<Any?>? = null

    /**
     * The last frame as the views take it: the card's own blend and glass eased toward the AOD's
     * by the frame's depth ([dimDepth]). Null to put the frame's own values on as they come.
     */
    private var aodDimLook: Pair<IntArray, FloatArray?>? = null
    private var aodDimDepth = -1f

    /**
     * A frame with the background blur off put the AOD's own flat item background on a view:
     * nothing eases that back, so the wake has to dress it again however deep the run ended.
     */
    private var aodDimFlat = false
    private var aodController: WeakReference<Any>? = null
    /** The proxy the OEM's full-AOD controller holds, and the way back out of it. */
    private var aodListener: Any? = null
    private var aodListenerAdded = false
    private var aodAdd: Method? = null
    private var aodRemove: Method? = null
    /** The controller retire() took our listener off, for a dry run's reattach. */
    private var lastAodController: WeakReference<Any>? = null
    private var aodDimFrames = 0
    private var blendMethod: Method? = null
    private var glassMethod: Method? = null
    private var blurOpenedMethod: Method? = null
    private val aodDimHandler by lazy { Gen.handler() }
    private val aodDimSettle = Runnable { settleAodDim() }

    private fun installAodDim(classLoader: ClassLoader) {
        val controller = Xp.findClass(
            "com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController", classLoader)
        val listener = Xp.findClass(
            "com.android.systemui.statusbar.notification.fullaod.INotifiFullAodListener", classLoader)
        blendMethod = Xp.findClass("com.android.systemui.statusbar.notification.utils.NotificationUtil",
            classLoader).declaredMethods.first { it.name == "applyElementViewBlend" && it.parameterTypes.size == 5 }
            .apply { isAccessible = true }
        glassMethod = Xp.findClass("com.miui.systemui.util.MiGlassCompat", classLoader)
            .getDeclaredMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .apply { isAccessible = true }
        blurOpenedMethod = runCatching {
            Xp.findClass("com.miui.systemui.notification.MiuiBaseNotifUtil", classLoader)
                .getDeclaredMethod("isBackgroundBlurOpened", Context::class.java).apply { isAccessible = true }
        }.getOrNull()
        val add = controller.getDeclaredMethod("addCallback", listener).apply { isAccessible = true }
        aodAdd = add
        // Optional: HyperOS 3's controller (17 Pro Max, 2026-10-27) has addCallback and no way
        // back, and requiring one threw here and took the whole AOD dim down with it. Without
        // it, retire() takes the proxy out of the controller's mListeners itself.
        aodRemove = (runCatching { controller.getDeclaredMethod("removeCallback", listener) }.getOrNull()
            ?: controller.declaredMethods.firstOrNull {
                it.name == "removeCallback" && it.parameterTypes.size == 1
            })?.apply { isAccessible = true }
        val ours = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(listener)) { self, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "MusicCover full-AOD dim"
                // Without removeCallback, retire() takes this out of mListeners by field name, and
                // a build that named it differently keeps it; a retired generation answers nothing.
                else -> if (!Gen.live()) null else when (m.name) {
                    "updateFullAodAnimState" -> { runCatching { onAodDim(args!!) }; null }
                    "completeFullAodAnim" -> { dimNote("complete"); runCatching { settleAodDim() }; null }
                    "onFullAodChange" -> { dimNote("change dark=${args?.getOrNull(0)} glass=${args?.getOrNull(1)}"); null }
                    else -> null
                }
            }
        }
        aodListener = ours
        // The controller is SystemUI's own singleton; the first listener it takes brings us in.
        Xp.hookAll("sysui.mini.session_callback", controller, "addCallback") { chain ->
            val result = chain.proceed()
            val firstController = aodController?.get() == null
            aodController = WeakReference(chain.thisObject)
            if (firstController) {
                materialGeneration++
                refresh()
            }
            if (!aodListenerAdded) {
                aodListenerAdded = true
                aodListener = ours
                runCatching { add.invoke(chain.thisObject, ours) }
                    .onFailure { Xp.log("MCMini: full-AOD listener not added: $it") }
            }
            result
        }
    }

    /**
     * The full-AOD controller a previous generation (or a dry run's retire) had our listener on.
     * It is SystemUI's singleton and its callbacks are added once, early: the addCallback hook
     * that normally brings us in will not fire again, so the listener is added here.
     */
    private fun adoptAod(controller: Any?) {
        if (controller == null || aodListenerAdded) return
        val add = aodAdd ?: return
        val ours = aodListener ?: return
        aodController = WeakReference(controller)
        aodListenerAdded = true
        runCatching { add.invoke(controller, ours) }
            .onSuccess {
                materialGeneration++
                refresh()
                Xp.log("MCMini: full-AOD listener adopted")
            }
            .onFailure {
                aodListenerAdded = false
                Xp.log("MCMini: full-AOD listener not adopted: $it")
            }
    }

    private fun onAodDim(args: Array<Any?>) {
        aodDimArgs = args
        aodDimFrames++
        val depth = runCatching { dimDepth(args) }.getOrNull()
        aodDimDepth = depth ?: -1f
        aodDimLook = depth?.let { d -> runCatching { dimLook(d) }.getOrNull() }
        run { for (view in dressedViews.toList()) dimView(view, args) }
        dimNote("frame ${Integer.toHexString(args[4] as Int)} ta=${"%.2f".format(args[13] as Float)} " +
            "d=${depth?.let { "%.2f".format(it) } ?: "?"}${if (aodDimLook == null) " raw" else ""}")
        // The run ends in completeFullAodAnim; should it be cut short, the last frame settles it.
        aodDimHandler.removeCallbacks(aodDimSettle)
        aodDimHandler.postDelayed(aodDimSettle, AOD_DIM_SETTLE_MS)
    }

    /** One frame of the AOD's colours on one view, as the card's listener puts them on its media_bg. */
    private fun dimView(view: ImageView, args: Array<Any?>) {
        if (!view.isAttachedToWindow) return
        val ctx = view.context
        val blurOpened = blurOpenedMethod?.let { runCatching { it.invoke(null, ctx) as Boolean }.getOrNull() } ?: true
        runCatching {
            if (blurOpened) {
                val look = if (view in notificationDressedViews) null else aodDimLook
                val colours = look?.first ?: IntArray(6) { args[4 + it] as Int }
                blendMethod?.invoke(null, ctx, view, false, colours, false)
                val params = if (look != null) look.second ?: args[10] as? FloatArray else args[10] as? FloatArray
                if (args[12] == true && params != null) glassMethod?.invoke(null, view, params)
            } else {
                val id = ctx.resources.getIdentifier("notification_media_fullaod_item_bg", "drawable",
                    "com.android.systemui")
                val bg = (if (id == 0) null else ctx.getDrawable(id)) as? GradientDrawable ?: return
                bg.mutate()
                bg.setColor(args[2] as Int)
                view.background = bg
                aodDimFlat = true
            }
        }
    }

    /**
     * How far into the AOD's look a frame is, 0 the lock screen's and 1 the AOD's: where its
     * glass parameters stand between the controller's two sets (mGlassParamsOnKeyguard ->
     * mGlassParamsFullAod), whichever way the run goes and for a reset's single frame alike.
     *
     * The frames' own colours end the wake on the focus rows' keyguard blend (ffa0a0a0), not the
     * card's (ff7a7a7a): the pill eased out of the AOD toward one and was dressed back to the
     * other on complete - undimmed, then a second change (2026-09-26, `dimlog`). The depth puts
     * the pill's own material at 0, so neither end of a run jumps.
     */
    private fun dimDepth(args: Array<Any?>): Float? {
        val c = aodController?.get() ?: return null
        val k = Xp.getObjectField(c, "mGlassParamsOnKeyguard") as? FloatArray ?: return null
        val a = Xp.getObjectField(c, "mGlassParamsFullAod") as? FloatArray ?: return null
        val p = args[10] as? FloatArray ?: return null
        if (k.size != a.size || p.size != k.size) return null
        var num = 0.0
        var den = 0.0
        for (i in k.indices) {
            val span = (a[i] - k[i]).toDouble()
            num += (p[i] - k[i]) * span
            den += span * span
        }
        if (den < 1e-9) return null
        return (num / den).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The card's recorded blend and glass eased toward the AOD's by [depth]. The AOD's colours
     * are the controller's focus list's AOD side (mFocusNotifiColorList: origin, target pairs;
     * the target is the AOD's on the way in, the origin on the way out), the ones the card's
     * listener puts on; the blend modes stay the card's.
     */
    private fun dimLook(depth: Float): Pair<IntArray, FloatArray?>? {
        val recipe = cardRecipe ?: return null
        val blend = recipe.firstOrNull { it.method.name == "applyElementViewBlend" }
            ?.args?.firstOrNull { it is IntArray } as? IntArray ?: return null
        if (blend.size != 6) return null
        val c = aodController?.get() ?: return null
        val list = Xp.getObjectField(c, "mFocusNotifiColorList") as? IntArray ?: return null
        if (list.size != 6) return null
        val into = c.javaClass.getField("mEnableFullAod").getBoolean(null)
        val evaluator = android.animation.ArgbEvaluator()
        val colours = blend.copyOf()
        for (j in 0 until 3) {
            val aod = list[2 * j + if (into) 1 else 0]
            colours[2 * j] = evaluator.evaluate(depth, blend[2 * j], aod) as Int
        }
        val cardGlass = recipe.lastOrNull { it.method.name == "setMiGlassCompat" }
            ?.args?.firstOrNull { it is FloatArray } as? FloatArray
        val aodGlass = Xp.getObjectField(c, "mGlassParamsFullAod") as? FloatArray
        val glass = if (cardGlass != null && aodGlass != null && cardGlass.size == aodGlass.size)
            FloatArray(cardGlass.size) { cardGlass[it] + (aodGlass[it] - cardGlass[it]) * depth }
        else null
        return colours to glass
    }

    /** Out of the AOD for good: every view back to the card's recipe. In the AOD, left as it is. */
    private fun settleAodDim() {
        aodDimHandler.removeCallbacks(aodDimSettle)
        if (aodDimArgs == null || MiniPlayerScene.aodActive) {
            dimNote("settle skipped: ${if (aodDimArgs == null) "not dim" else "in AOD"}")
            return
        }
        aodDimArgs = null
        // Eased all the way back to the card's own material: already dressed as it, and a
        // dressing again only risks a change on a pill that should not change.
        // The views out of their window missed the frames: those are dressed when they return.
        val home = aodDimLook != null && aodDimDepth in 0f..0.01f && !aodDimFlat
        aodDimLook = null
        aodDimFlat = false
        val cl = loader ?: return
        var later = 0
        for (view in dressedViews.toList()) {
            if (view.isAttachedToWindow) {
                if (!home) material(view, cl, view in notificationDressedViews)
            } else {
                // Out of its window now, dimmed while it was in: dressed again when it comes
                // back, or it came back dark on a lit screen with nothing left to undo it.
                later++
                Gen.attachState(view, object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        Gen.unattachState(v, this)
                        if (aodDimArgs == null) {
                            material(view, cl, view in notificationDressedViews)
                            dimNote("redressed on attach")
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        }
        dimNote("settle: ${if (home) "at the card's own look" else "redressed"}, $later out of window")
    }

    /**
     * The doze is over (MiniPlayerScene). The wake's run of the fraction brings the colours back
     * and ends in settleAodDim; a wake with no run would leave the views dark on a lit screen.
     */
    fun aodEnded() {
        dimNote("aod ended, ${if (aodDimArgs == null) "not dim" else "dim"}")
        if (aodDimArgs == null) return
        aodDimHandler.removeCallbacks(aodDimSettle)
        aodDimHandler.postDelayed(aodDimSettle, AOD_DIM_WAKE_MS)
    }

    /** For `op mini`. */
    private fun describeAodDim(): String = "aodDim=${if (aodDimArgs != null) "on" else "off"}/$aodDimFrames" +
        " ${describeAodListeners()}" +
        " dimlog=[${synchronized(dimLog) { dimLog.joinToString(" ; ") }}]"

    /**
     * The controller's listener list: how long, how many of them are a MusicCover proxy (any
     * generation's - they all print the same), and whether this generation's is one. After a hot
     * reload the proxies should still be exactly one, and it ours.
     */
    private fun describeAodListeners(): String {
        val c = aodController?.get() ?: return "aodListeners=(no controller)"
        return runCatching {
            val list = (Xp.getObjectField(c, "mListeners") as List<*>).toList()
            val proxies = list.count { it != null && it.toString() == "MusicCover full-AOD dim" }
            val oursIn = list.any { it === aodListener }
            "aodListeners=${list.size} proxies=$proxies ours=$oursIn"
        }.getOrElse { "aodListeners=? $it" }
    }

    /**
     * The dim's events for `op mini`, uptime ms: the listener's frames (the blend's first colour,
     * the rows' transitionAlpha), complete, the OEM's style change, the doze's end, the settle -
     * each with how the widest dressed view (the pill) is seen: its alpha down from the root.
     */
    private val dimLog = ArrayDeque<String>()

    private fun dimNote(what: String) {
        val pill = dressedViews.toList().filter { it.isAttachedToWindow }.maxByOrNull { it.width }
        var seen = 1f
        var v: View? = pill
        while (v != null) {
            seen *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        val line = "${android.os.SystemClock.uptimeMillis() % 100000} $what aod=${MiniPlayerScene.aodActive} " +
            "seen=${if (pill == null) "-" else "%.2f".format(seen)}"
        synchronized(dimLog) {
            dimLog.addLast(line)
            while (dimLog.size > 120) dimLog.removeFirst()
        }
    }

    /** Which effect dressed the card last, for the log and `op mini`. */
    @Volatile private var cardEffect = "none"

    private fun isKeyguardEffect(name: String) = "OnKeyguard" in name || "FullAod" in name

    /** The last effect whose empty recording was thrown away, for `op mini`. */
    @Volatile private var emptyEffect = "none"

    /** The pending retries of scheduleInstall, so a retirement can take them back. */
    private var installTask: Runnable? = null

    /** The shortcut row has just been built: the pill goes between its two buttons. */
    private fun scheduleInstall(root: View, shortcutController: Any?) {
        lastRoot = WeakReference(root)
        lastShortcutController = shortcutController?.let(::WeakReference)
        // Through Gen, not root.post: a retry left on the OEM view's handler runs after the
        // sweep and builds a controller into a generation that has let go. Removing the old
        // task first also keeps two dry runs from queueing four attaches between them.
        installTask?.let { Gen.removeCallbacks(it) }
        val task = Runnable {
            runCatching { attach(root, shortcutController) }
                .onFailure { Xp.w("MCMini: attach failed: $it") }
        }
        installTask = task
        task.run()
        Gen.post(task)
        Gen.postDelayed(task, 250L)
    }

    private fun attach(root: View, shortcutController: Any?) {
        val config = JSONObject(configJson(root.context))
        syncNotificationGrouping(root.context)
        val host = root.rootView as? ViewGroup ?: return
        val old = synchronized(controllers) { controllers[host] }
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) {
            old?.controller?.destroy()
            old?.listener?.let { Gen.unattachState(host, it) }
            synchronized(controllers) { controllers.remove(host) }
            return
        }
        val (left, right) = resolveShortcuts(shortcutController) ?: findShortcuts(root) ?: return
        if (left === right) return
        if (old != null && old.left === left && old.right === right) {
            old.controller.refresh()
            return
        }
        old?.controller?.destroy()
        // The row can be rebuilt under the same root (the keyguard keeps its window), and the
        // old listener only leaves on detach - which then never comes. Take it back here, or
        // every rebuild leaves one behind and pins the views it holds.
        old?.listener?.let { Gen.unattachState(host, it) }
        val controller = MiniPlayerController(host, left, right, prefs(root.context),
            loader ?: root.context.classLoader)
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                synchronized(controllers) {
                    if (controllers[host]?.controller === controller) controllers.remove(host)
                }
                controller.destroy()
                Gen.unattachState(host, this)
            }
        }
        synchronized(controllers) {
            controllers[host] = Held(left, right, controller, listener)
        }
        Gen.attachState(host, listener)
    }

    private fun resolveShortcuts(controller: Any?): Pair<View, View>? = runCatching {
        val method = controller?.javaClass?.methods?.firstOrNull {
            it.name == "onSystemUIAction\$1" && it.parameterCount == 2
        } ?: return@runCatching null
        fun get(left: Boolean): View? = method.invoke(controller,
            android.os.Bundle().apply { putBoolean("isLeftShortcutView", left) },
            "getShortcutView") as? View
        val left = get(true) ?: return@runCatching null
        val right = get(false) ?: return@runCatching null
        left to right
    }.getOrNull()

    private fun findShortcuts(root: View): Pair<View, View>? {
        var left: View? = null
        var right: View? = null
        fun visit(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name == "shortcut_view_left_layout") left = view
            if (name == "shortcut_view_right_layout") right = view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        val l = left ?: return null
        val r = right ?: return null
        return l to r
    }

    @JvmStatic fun wantsNativeArtworkGesture(): Boolean =
        synchronized(controllers) { controllers.values.any { it.controller.wantsNativeArtworkGesture() } }

    /**
     * The card can be swiped down into the pill: when it is up by choice,
     * and in the cover or lyrics - where the card stands in for the pill - whenever the pill
     * is what the lock screen goes back to.
     */
    @JvmStatic fun wantsNativeCardSwipe(): Boolean = live().any { it.wantsCardSwipe() }

    /**
     * The lock screen's notifications are the row's islands: a pull down on the media card
     * brings their rows home into the row with it, so it is not the stack's to fold them away.
     */
    @JvmStatic fun islandsTakeRows(): Boolean = live().any { it.takesRows() }

    /** The pill is what the lock screen returns to, without starting a morph of its own. */
    @JvmStatic fun preferMini() = live().forEach { it.preferMini() }

    /**
     * A tap on the music island with the cover switched off: the island goes up into the plain
     * card, the same choice a pull up on it makes, since there is no cover for it to open.
     */
    @JvmStatic fun openCardInstead() {
        val token = live().firstNotNullOfOrNull { it.sessionToken() } ?: return
        selectNative(token)
    }

    /**
     * The card was swiped down out of the cover or the lyrics. The next swipe up on the pill
     * goes back there rather than to the plain card - which of the two is the entry's own
     * question (LockLyrics.willAttachOnEntry keeps a two-finger dismissal), so it lands where
     * the swipe down left from. Forgotten on unlocking and with the session.
     */
    @Volatile private var restoreScene = false

    @JvmStatic fun rememberScene() { restoreScene = true }

    @JvmStatic fun forgetRestoreScene() { restoreScene = false }

    internal fun takeRestoreScene(): Boolean = restoreScene.also { restoreScene = false }

    private var routed: WeakReference<MiniPlayerView>? = null

    /**
     * Hands a whole gesture that starts on the pill to the pill and to nothing else. The pill's
     * own requestDisallowInterceptTouchEvent could not stop swipe-to-unlock, which the keyguard
     * decides above the view tree; this runs from the shade window's dispatch, before it.
     */
    @JvmStatic fun routeTouch(ev: MotionEvent): Boolean { android.os.Trace.beginSection("MC t.route"); try {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releasePress()
        if (action == MotionEvent.ACTION_DOWN) {
            routedX = ev.rawX
            routedY = ev.rawY
            routedDrag = false
            routedSwiped = false
            routedMorph = false
            routedSpread = false
            routedStack = false
            routedPullRefused = false
            // What the last gesture was, in case it never reached its UP or CANCEL here.
            routedOwner?.stopAnticipating()
            routedIsland = false
            routedNote = false
            routedOwner = null
            routedTracker?.recycle()
            routedTracker = null
            // A morph still moving on its own is taken hold of where it is: a pull, a let-go and
            // a pull again, as often as the finger likes - it used to shut touches off until it
            // had settled, and a finger landing on it meanwhile went to swipe-to-unlock.
            val catcher = live().firstOrNull { it.catchableAt(ev.rawX, ev.rawY) }
            routed = catcher?.pill()?.let(::WeakReference)
            if (catcher != null && routed != null) {
                if (catchDrag(catcher, ev)) {
                    routedTracker = VelocityTracker.obtain()
                    routedDrag = true
                    routedMorph = true
                } else routed = null
            }
            if (routed == null) {
                routed = live().firstNotNullOfOrNull { it.touchTarget(ev.rawX, ev.rawY) }
                    ?.let(::WeakReference)
                routedTracker = if (routed != null) VelocityTracker.obtain() else null
                // A pill still springing home is held where it is, and pulled on from there.
                routed?.get()?.let { pill ->
                    pill.holdNudge()
                    routedBaseX = pill.nudgeX
                    routedBaseY = pill.nudgeY
                }
            }
            // The finger swells what it is on: the pill, or the torch or camera's disc - whose
            // button still takes the touch itself.
            releasePress()
            val pill = routed?.get()
            val pillOwner = pill?.let { p -> live().firstOrNull { it.pill() === p } }
            routedSmall = pillOwner?.smallIslandAt(ev.rawX, ev.rawY) == true
            if (routedSmall && pillOwner != null && !routedMorph) {
                // Taken where it is, the pill's nudge left alone.
                pill?.springNudgeBack(0f, 0f)
                pillOwner.holdSmallNudge()
                pillOwner.smallNudgeNow.let { (x, y) -> routedBaseX = x; routedBaseY = y }
            }
            val hit = if (pill != null) pillOwner?.let { it to if (routedSmall) PRESS_SMALL else PRESS_PILL }
                else live().firstNotNullOfOrNull { c -> c.discAt(ev.rawX, ev.rawY)?.let { c to it } }
            hit?.let { (owner, side) ->
                owner.press(side, true)
                pressed = WeakReference(owner)
                pressedSide = side
            }
            noteTouch("down ${ev.rawX.toInt()},${ev.rawY.toInt()} pill=${pill != null} " +
                "small=$routedSmall caught=$routedMorph disc=${hit?.second}" +
                (pillOwner?.smallProbe(ev.rawX, ev.rawY)?.let { " [$it]" } ?: ""))
        }
        // A drag on the lock screen itself - the swipe up to the PIN pad - is the lock screen
        // leaving: the buttons held through the wake are let go so they fade with it.
        if (routed?.get() == null && action == MotionEvent.ACTION_MOVE && !routedSwiped) {
            val dx = ev.rawX - routedX
            val dy = ev.rawY - routedY
            routedSwiped = live().map { it.letGoOnDrag(dx, dy) }.any { it }
        }
        val target = routed?.get() ?: return false
        routedTracker?.addMovement(ev)
        val dx = ev.rawX - routedX
        val dy = ev.rawY - routedY
        val d = target.resources.displayMetrics.density
        if (!routedDrag) {
            val slop = ViewConfiguration.get(target.context).scaledTouchSlop
            if (action == MotionEvent.ACTION_MOVE && kotlin.math.hypot(dx, dy) > slop) {
                // From here the gesture moves the pill; it hears no more of it itself.
                routedDrag = true
                if (!routedSmall) dispatchTo(target, ev, MotionEvent.ACTION_CANCEL)
                // Sideways on a row of islands, the pull is the islands'; alone, it is a skip.
                routedOwner = live().firstOrNull { it.pill() === target }
                routedIsland = routedOwner?.allowsSideSwitch() == true &&
                    routedOwner?.islandCount()?.let { it >= 2 } == true &&
                    routedOwner?.exchangeRunning() != true &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                routedStack = routedOwner?.allowsStackSwitch() == true &&
                    MiniPlayerStackGesture.starts(dx, dy)
                if (routedStack && routedSmall) {
                    routedOwner?.springSmallNudgeBack(0f, 0f)
                    target.holdNudge()
                    routedBaseX = target.nudgeX
                    routedBaseY = target.nudgeY
                }
                // Up on the music: the rows its card takes along are asked for now, so they are
                // there by the time the pull opens it.
                if (!routedIsland && dy < 0f && -dy >= kotlin.math.abs(dx)) {
                    routedOwner?.anticipateRows(routedSmall)
                }
            } else {
                // An exchange running: a tap turns it round or cuts it short, and nothing else.
                val exchanging = live().firstOrNull { it.pill() === target }?.takeIf { it.exchangeRunning() }
                if (exchanging != null) {
                    if (action == MotionEvent.ACTION_UP) exchanging.tapDuringExchange(routedSmall)
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        // A press the pill heard before the exchange began is let go.
                        if (!routedSmall) dispatchTo(target, ev, MotionEvent.ACTION_CANCEL)
                        endRoute()
                    }
                    return true
                }
                // A tap on the small island is the small island's: it takes the pill.
                if (!routedSmall) dispatchTo(target, ev, action)
                else if (action == MotionEvent.ACTION_UP) {
                    live().firstOrNull { it.pill() === target }?.selectSmallIsland()
                }
                if (action == MotionEvent.ACTION_UP) noteTouch("tap small=$routedSmall ${target.touchState()}")
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endRoute()
                return true
            }
        }
        val nx = pillNudgeX(dx, d)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (!routedMorph && !routedIsland && !routedStack && !routedNote && !routedSpread &&
                    -dy > DRAG_THRESHOLD_DP * d) {
                    // Pulled far enough up to mean it: the music - the pill, or the small island -
                    // starts turning into the card under the finger, the nudge handed over as it is.
                    val stacked = routedOwner ?: live().firstOrNull { it.pill() === target }
                    if (stacked?.beginStackSpread(routedY) == true) {
                        routedSpread = true
                        routedOwner = stacked
                        target.clearNudge()
                        stacked.springSmallNudgeBack(0f, 0f)
                        noteTouch("pull up: stacked row to cards")
                    } else {
                    val owner = live().firstOrNull { it.canDrag(fromNative = false, small = routedSmall) }
                    if (owner != null && beginDrag(fromNative = false, ev, startY = routedY, small = routedSmall)) {
                        routedMorph = true
                        if (!routedSmall) target.clearNudge()
                        noteTouch("pull up: pill to card (small=$routedSmall)")
                    } else {
                        // A notification island - the pill's or the small one - opens into its row.
                        val o = routedOwner ?: live().firstOrNull { it.pill() === target }
                        val key = o?.noteKeyFor(routedSmall)
                        if (o != null && key != null && o.beginNoteDrag(key, routedSmall, routedY)) {
                            routedNote = true
                            routedOwner = o
                            noteTouch("pull up: note small=$routedSmall")
                        } else if (!routedPullRefused) {
                            routedPullRefused = true
                            noteTouch("pull up: nothing (small=$routedSmall key=${key != null} " +
                                "busy=${o?.noteBusy()})")
                        }
                    }
                    }
                }
                if (routedMorph) dragMove(ev, nx)
                else if (routedSpread) routedOwner?.spreadFingerMove(ev.rawY)
                else if (routedNote) routedOwner?.noteDragMove(ev.rawY, nx)
                else if (routedIsland) routedOwner?.islandDrag(dx)
                else if (routedStack) target.setNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
                // The small island follows the finger as the pill does, and presses as it does.
                else if (routedSmall) routedOwner?.setSmallNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
                else target.setNudge(routedBaseX + nx, routedBaseY + pillNudgeY(dy, d))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = routedTracker
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                val cancelled = action == MotionEvent.ACTION_CANCEL
                if (routedMorph) {
                    dragEnd(ev, cancelled)
                } else if (routedSpread) {
                    routedOwner?.spreadFingerEnd(vy, cancelled)
                } else if (routedNote) {
                    routedOwner?.noteDragEnd(vy, cancelled)
                    noteTouch("note pull dy=${dy.toInt()} cancelled=$cancelled")
                } else if (routedIsland) {
                    // The super island's threshold: past 50dp (or flung) the switch runs.
                    val commit = !cancelled && (kotlin.math.abs(dx) >= ISLAND_SWIPE_DP * d ||
                        kotlin.math.abs(vx) > 1200f * d)
                    routedOwner?.islandDragEnd(commit, next = dx < 0f, vx = vx)
                    noteTouch("island swipe dx=${dx.toInt()} commit=$commit")
                } else if (routedStack) {
                    val switched = routedOwner?.stackSwipeEnd(dy, vy, cancelled) == true
                    if (!switched) target.springNudgeBack(vx, vy)
                    noteTouch("stack down dy=${dy.toInt()} switched=$switched cancelled=$cancelled")
                } else if (routedSmall) {
                    // The small island's nudge only ever springs home: its sideways swipe is
                    // the row's, its pull up its row's.
                    routedOwner?.springSmallNudgeBack(vx, vy)
                } else {
                    // A short drag is only ever a nudge: it springs back and does nothing.
                    // Sideways far enough (or flung) skips; up far enough goes back into the
                    // cover or the lyrics the pill came out of.
                    val sideways = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    val swipe = kotlin.math.max(48f * d, target.width * 0.15f)
                    val fling = 1200f * d
                    if (!cancelled && sideways
                        && (kotlin.math.abs(dx) >= swipe || kotlin.math.abs(vx) > fling)) {
                        target.performSkip(next = dx < 0f)
                    } else if (!cancelled && !sideways && restoreScene
                        && (-dy > DRAG_THRESHOLD_DP * d || -vy > fling)) {
                        if (takeRestoreScene()) Main.miniPlayerEnterCover()
                    }
                    target.springNudgeBack(vx, vy)
                }
                endRoute()
            }
        }
        return true
    } finally { android.os.Trace.endSection() } }

    private var routedX = 0f
    private var routedY = 0f
    private var routedBaseX = 0f
    private var routedBaseY = 0f
    private var routedDrag = false
    private var routedMorph = false
    private var routedSpread = false
    private var routedStack = false
    private var routedTracker: VelocityTracker? = null

    /** This gesture, not the pill's, has been past the slop and told the rows (letGoOnDrag). */
    private var routedSwiped = false

    /** The gesture began on the small island, not the pill. */
    private var routedSmall = false

    /** A sideways pull on a row of islands, and the row it pulls on. */
    private var routedIsland = false

    /** An upward pull opening a notification island into its row. */
    private var routedNote = false
    private var routedOwner: MiniPlayerController? = null

    /** The super island's swipe threshold (island_swipe_threshold). */
    private const val ISLAND_SWIPE_DP = 50f

    /** Up past this, a pull stops being a nudge and opens (or goes back into the scene). */
    private const val DRAG_THRESHOLD_DP = 72f

    /** The pull up this gesture could not open anything with has been logged once. */
    private var routedPullRefused = false

    private var pressed: WeakReference<MiniPlayerController>? = null
    private var pressedSide = PRESS_PILL

    private fun releasePress() {
        pressed?.get()?.press(pressedSide, false)
        pressed = null
    }

    /** The side a press is on: the pill, or a disc's index (0 the torch, 1 the camera). */
    internal const val PRESS_PILL = -1
    internal const val PRESS_SMALL = -2

    private fun endRoute() {
        routedOwner?.stopAnticipating()
        routedIsland = false
        routedNote = false
        routedSpread = false
        routedStack = false
        routedOwner = null
        routed = null
        routedTracker?.recycle()
        routedTracker = null
    }

    /** Up as the card end of a drag measures it, so the hand-over to the morph is seamless. */
    private fun pillNudgeY(dy: Float, d: Float): Float =
        if (dy < 0f) -MiniCardMorph.rubber(-dy, DRAG_NUDGE_DP * d)
        else MiniCardMorph.rubber(dy, 28f * d)

    private const val DRAG_NUDGE_DP = 48f

    private fun dispatchTo(target: View, ev: MotionEvent, action: Int) {
        val xy = IntArray(2).also(target::getLocationOnScreen)
        val copy = MotionEvent.obtain(ev)
        copy.action = action
        copy.offsetLocation(ev.rawX - ev.x - xy[0], ev.rawY - ev.y - xy[1])
        try {
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    // ------------------------------------------------------------ the finger-driven switch

    private var dragOwner: MiniPlayerController? = null
    private var dragFromNative = false
    private var dragStartY = 0f
    private var dragSpan = 1f
    private var dragThreshold = 1f
    private var dragNudgeLimit = 1f
    private var dragTracker: VelocityTracker? = null

    /**
     * A finger has started pulling the pill up, or the card down. Nothing is chosen yet: a
     * short pull only nudges the one it started on and springs back; past [dragThreshold] the
     * container starts changing shape under the finger; the lift decides.
     */
    @JvmStatic @JvmOverloads
    fun beginDrag(fromNative: Boolean, ev: MotionEvent, startY: Float = ev.rawY,
                  small: Boolean = false): Boolean { android.os.Trace.beginSection("MC t.beginDrag"); try {
        // The media card pulled down with other islands in the row: the music comes home as a
        // flight, as a notification's row does, and the others make room for it.
        if (fromNative && live().any { it.musicCollapses() }) return collapseRow(MUSIC_ISLAND, ev)
        val owner = live().firstOrNull { it.canDrag(fromNative, small) }
            ?: return refused("drag", live().joinToString(",") { it.dragRefusal(fromNative, small) })
        val span = owner.dragSpan() ?: return refused("drag", "no span")
        if (!owner.beginDragMorph(fromNative)) return false
        dragCaught = false
        val density = owner.density()
        dragOwner = owner
        dragFromNative = fromNative
        dragStartY = startY
        dragSpan = span
        dragThreshold = DRAG_THRESHOLD_DP * density
        dragNudgeLimit = DRAG_NUDGE_DP * density
        dragTracker?.recycle()
        dragTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    } finally { android.os.Trace.endSection() } }

    @JvmStatic @JvmOverloads
    fun dragMove(ev: MotionEvent, nudgeX: Float = 0f) { android.os.Trace.beginSection("MC t.dragMove"); try {
        val owner = dragOwner
        if (owner == null) {
            // A notification's row pulled down into its island: the same pull, the island's morph.
            noteTracker?.addMovement(ev)
            noteOwner?.noteDragMove(ev.rawY, nudgeX)
            return
        }
        dragTracker?.addMovement(ev)
        if (dragCaught) {
            owner.dragTo(banded(caughtProgress(ev.rawY)), caughtNudge, caughtNudgeX + nudgeX)
            return
        }
        val (progress, nudge) = dragPose(ev.rawY)
        owner.dragTo(progress, nudge, nudgeX)
    } finally { android.os.Trace.endSection() } }

    private var dragCaught = false
    private var caughtBase = 0f
    private var caughtNudge = 0f
    private var caughtNudgeX = 0f
    private var caughtUp = 1f
    private var caughtScene = false

    /** Takes a running morph under the finger: progress follows it from where it was. */
    private fun catchDrag(owner: MiniPlayerController, ev: MotionEvent): Boolean {
        val span = owner.dragSpan() ?: return false
        val grab = owner.grabMorph() ?: return false
        dragOwner = owner
        dragCaught = true
        dragStartY = ev.rawY
        dragSpan = span
        caughtBase = grab.progress
        caughtNudge = grab.nudge
        caughtNudgeX = grab.nudgeX
        caughtScene = owner.morphIsScene()
        // Which way is toward the card: up from the pill, whatever the layout.
        caughtUp = if (owner.cardAbovePill()) 1f else -1f
        dragTracker?.recycle()
        dragTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    }

    private fun caughtProgress(y: Float) = caughtBase + caughtUp * (dragStartY - y) / dragSpan

    /** Past either end, the same resistance as a fresh pull. */
    private fun banded(p: Float): Float = when {
        p > 1f -> 1f + MiniCardMorph.rubber(p - 1f, 0.2f)
        p < 0f -> -MiniCardMorph.rubber(-p, 0.2f)
        else -> p
    }

    @JvmStatic fun dragEnd(ev: MotionEvent, cancelled: Boolean) { android.os.Trace.beginSection("MC t.dragEnd"); try {
        val owner = dragOwner
        if (owner == null) {
            val note = noteOwner ?: return
            noteOwner = null
            val tracker = noteTracker
            noteTracker = null
            tracker?.addMovement(ev)
            tracker?.computeCurrentVelocity(1000)
            val vy = tracker?.yVelocity ?: 0f
            tracker?.recycle()
            note.noteDragEnd(vy, cancelled)
            return
        }
        dragOwner = null
        val tracker = dragTracker
        dragTracker = null
        tracker?.addMovement(ev)
        tracker?.computeCurrentVelocity(1000)
        val vy = tracker?.yVelocity ?: 0f
        tracker?.recycle()
        if (dragCaught) {
            dragCaught = false
            val p = caughtProgress(ev.rawY)
            val v = -caughtUp * vy / dragSpan
            val toNative = if (cancelled) p > 0.5f else p + v * 0.18f > 0.5f
            if (!caughtScene) {
                owner.releaseDrag(toNative, v)
                return
            }
            // A scene's morph, caught: where it is let go decides the scene. Up with the cover
            // already off (it was leaving) goes back in; down with it on (it was coming) goes
            // back out; the same way as it was going just carries on.
            owner.releaseMorph(toNative, v)
            if (toNative && !Main.coverModeOn()) {
                if (takeRestoreScene()) Main.miniPlayerTurnScene(true)
            } else if (!toNative && Main.coverModeOn()) {
                preferMini()
                rememberScene()
                Main.miniPlayerTurnScene(false)
            }
            return
        }
        val (toNative, velocity) = lift(pulled(ev.rawY), if (dragFromNative) vy else -vy,
            dragFromNative, dragThreshold, dragSpan, cancelled)
        owner.releaseDrag(toNative, velocity)
    } finally { android.os.Trace.endSection() } }

    /**
     * Where a lift goes, for every island's pull alike - the music's into its card, a
     * notification's into its row, and each back: [pulled] pixels toward the far end, [speed]
     * that way in pixels a second. Past half way, or flung past it, it reaches the far end.
     * Returns whether it ends at the card or row, and the progress speed to hand the spring.
     */
    internal fun lift(pulled: Float, speed: Float, fromNative: Boolean, threshold: Float,
                      span: Float, cancelled: Boolean): Pair<Boolean, Float> {
        val engaged = ((pulled - threshold) / span).coerceAtLeast(0f)
        val projected = engaged + speed / span * 0.18f
        val far = !cancelled && projected > 0.5f
        val toNative = if (fromNative) !far else far
        val velocity = (if (fromNative) -speed else speed) / span
        return toNative to if (engaged > 0f) velocity else 0f
    }

    /** A notification row being pulled down into its island, and its finger's speed. */
    private var noteOwner: MiniPlayerController? = null
    private var noteTracker: VelocityTracker? = null

    /** Pixels the finger has gone toward the far end (negative: the wrong way). */
    private fun pulled(y: Float) = if (dragFromNative) y - dragStartY else dragStartY - y

    /**
     * Finger to (progress, nudge). Short of the threshold only the nudge moves, rubber-banded;
     * past it the progress follows the finger 1:1 over the span while the nudge the threshold
     * left hands over to it, so crossing is seamless; past the far end, rubber again.
     */
    private fun dragPose(y: Float): Pair<Float, Float> =
        pose(pulled(y), dragFromNative, dragThreshold, dragSpan, dragNudgeLimit)

    /**
     * The same for every island's pull, [pulled] pixels toward the far end. The nudge is in
     * MiniCardMorph's sense, toward the card or row: pulled from that end, the finger's way
     * is away from it. Given the far end's sign from a card pulled down, the card went up
     * under a finger going down for the first 72dp.
     */
    internal fun pose(pulled: Float, fromNative: Boolean, threshold: Float, span: Float,
                      nudgeLimit: Float): Pair<Float, Float> {
        val base = if (fromNative) 1f else 0f
        val dir = if (fromNative) -1f else 1f
        if (pulled <= threshold) {
            val nudge = if (pulled >= 0f) MiniCardMorph.rubber(pulled, nudgeLimit)
                else -MiniCardMorph.rubber(-pulled, nudgeLimit * 0.5f)
            return base to dir * nudge
        }
        val held = MiniCardMorph.rubber(threshold, nudgeLimit)
        var p = (pulled - threshold) / span
        if (p > 1f) p = 1f + MiniCardMorph.rubber(p - 1f, 0.2f)
        val nudge = held * (1f - p.coerceIn(0f, 1f))
        return (base + dir * p) to dir * nudge
    }

    /** What a pull's threshold and nudge are, in pixels, for this density. */
    internal fun dragThresholdPx(density: Float) = DRAG_THRESHOLD_DP * density
    internal fun dragNudgePx(density: Float) = DRAG_NUDGE_DP * density

    @JvmStatic fun onNativeCardSwipeDown() {
        synchronized(controllers) { controllers.values.map { it.controller } }
            .firstOrNull { it.wantsNativeArtworkGesture() }
            ?.showMiniPlayer()
    }

    /** The torch button: the lock screen element the pill sits between, for probes. */
    @JvmStatic fun shortcutView(): View? = synchronized(controllers) {
        controllers.values.firstOrNull()?.left
    }

    /**
     * For the settings preview, in px: host width, then the torch's and the camera's laid-out
     * centre and icon size (x, y, w, h each). Null until a shortcut row has been built.
     */
    @JvmStatic fun shortcutGeometry(): FloatArray? = live().firstNotNullOfOrNull { it.shortcutGeometry() }

    /** The last touches the row saw and what it made of them, for `op mini`. */
    private val touchLog = ArrayDeque<String>()

    @JvmStatic fun noteTouch(what: String) {
        synchronized(touchLog) {
            touchLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
            while (touchLog.size > 40) touchLog.removeFirst()
        }
    }

    /**
     * A pull or a scene that found no morph to run, and why: the card then went without one and
     * the pill turned up on its own a moment later (the user, 2026-10-07, not reproduced). Into
     * `op mini`'s touches and the log, whose clock lines it up with the swipe.
     */
    internal fun refused(what: String, why: String): Boolean {
        noteTouch("$what refused: $why")
        Xp.log("MCMini: $what refused: $why")
        return false
    }

    /**
     * Every time the pill went, and whether it went back into its circle or in one frame - with
     * each thing that decides that - for `op mini`, first in it so a long reply cannot cut it off.
     * Only one in so many went without its way back (2026-09-29), and not on demand.
     */
    private val goneLog = ArrayDeque<String>()

    @JvmStatic fun noteGone(what: String) {
        synchronized(goneLog) {
            goneLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
            while (goneLog.size > 12) goneLog.removeFirst()
        }
    }

    /**
     * For `op rowtree --es key <part>`: every stack row whose key has [match] in it (all of them
     * for an empty one), laid out as the stack draws it - what a focus notification's row shows on
     * the lock screen, next to what its island does.
     */
    @JvmStatic fun rowTree(match: String): String =
        live().firstOrNull()?.rowTree(match) ?: "no controller"

    /** The row spread out as the list is being pulled home (NumState's hands-up folds it). */
    @JvmStatic fun spreadFolding(): Boolean = live().any { it.spreadFolding() }

    /**
     * `op fod`: the fingerprint sensor the row of islands keeps clear of (#66), and where the row
     * is. `--es rect l,t,r,b` (screen pixels) lays it out against that rect instead, to try the
     * lift on a phone whose sensor sits clear of the row; `none` as if there were no sensor;
     * `phone` back to the phone's own. Not saved.
     */
    @JvmStatic fun fingerprintProbe(rect: String?): String {
        val note = when (rect?.trim()) {
            null, "" -> ""
            "phone" -> { fodProbe = null; "back to the phone's sensor; " }
            "none" -> { fodProbe = android.graphics.Rect(); "as if there were no sensor; " }
            else -> {
                val n = rect.split(',').map { it.trim().toInt() }
                require(n.size == 4) { "rect wants l,t,r,b" }
                fodProbe = android.graphics.Rect(n[0], n[1], n[2], n[3])
                "laid out against $fodProbe; "
            }
        }
        fodReadAt = 0L
        val rows = live()
        rows.forEach { it.relayout() }
        checkStackFloor()
        return note + "probe=${fodProbe ?: "off"} || " + describeStackFloor() + " || " +
            rows.joinToString(" || ") { it.describeFingerprint() }.ifEmpty { "no controller" }
    }

    /** The keyguard's notification stack, for NumStateProbe. */
    @JvmStatic fun stackForProbe(): ViewGroup? = live().firstNotNullOfOrNull { it.stackForProbe() }

    /** For `op mini`: what the islands are dressed in - the card's recipe and the notification's look. */
    private fun describeMaterial(): String {
        fun arg(v: Any?): String = when (v) {
            is IntArray -> v.joinToString(",", "[", "]") { Integer.toHexString(it) }
            is FloatArray -> v.joinToString(",", "[", "]") { "%.3f".format(it) }
            is View -> "V"
            null -> "null"
            else -> v.toString().take(24)
        }
        val recipe = cardRecipe?.joinToString(" ; ") { c -> c.method.name + c.args.joinToString(",", "(", ")") { arg(it) } }
        val bg = { d: android.graphics.drawable.Drawable.ConstantState? ->
            d?.newDrawable()?.let { (it as? GradientDrawable)?.color?.defaultColor?.let(Integer::toHexString) ?: it.javaClass.simpleName }
        }
        return "nlook=${arg(notificationBlend)}/${arg(notificationGlass)} nsrc=${notificationSource?.get() != null} " +
            "fallback=${notificationLook()?.let { arg(it.first) + "/" + arg(it.second) }} " +
            "cardBg=${bg(cardBackground)} notifBg=${bg(notificationBackground)} recipe={$recipe}"
    }

    /** For `op mini`: the pill, the card, and the torch button's chain as they are right now. */
    @JvmStatic fun describe(): String {
        val sb = StringBuilder("gone: " + synchronized(goneLog) { goneLog.joinToString(" ; ") } +
            " || material=$cardEffect gen=$materialGeneration same=$materialRepeats calls=${cardRecipe?.size} empty=$emptyEffect " +
            "${describeMaterial()} " +
            "aod=${MiniPlayerScene.aodActive} ${describeAodDim()} || ${LockIslands.describe()} || touches: " +
            synchronized(touchLog) { touchLog.joinToString(" ; ") })
        synchronized(controllers) { controllers.values.toList() }.forEach { held ->
            sb.append(" || ").append(held.controller.describe())
            sb.append(" || torch:")
            var v: View? = held.left
            while (v != null) {
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                    ?: v.javaClass.simpleName
                sb.append(' ').append(id).append("[v=").append(v.visibility)
                    .append(" a=").append("%.2f".format(v.alpha))
                    .append(" ta=").append("%.2f".format(v.transitionAlpha))
                if (v.scaleX != 1f || v.scaleY != 1f) sb.append(" s=").append("%.3f".format(v.scaleY))
                    .append("@").append(v.pivotY.toInt())
                if (v.translationY != 0f) sb.append(" ty=").append(v.translationY.toInt())
                sb.append(']')
                v = v.parent as? View
            }
        }
        return sb.toString()
    }

    @JvmStatic fun nativeHeaderHidden(): Boolean = synchronized(controllers) {
        controllers.values.any { it.controller.nativeHeaderHidden() }
    }

    private fun live(): List<MiniPlayerController> =
        synchronized(controllers) { controllers.values.map { it.controller } }

    /** Where the music's island is drawn, for the lyrics to grow out of; null when it is not up. */
    @JvmStatic fun musicIslandCentreOnScreen(): FloatArray? =
        live().firstNotNullOfOrNull { it.musicIslandCentreOnScreen() }

    /** Where the music's island will rest once back from its card, for the lyrics to go into. */
    @JvmStatic fun musicIslandHomeOnScreen(): FloatArray? =
        live().firstNotNullOfOrNull { it.musicIslandHomeOnScreen() }

    /** A notification's row in the stack, for ImmersiveHost: the open page's turn arrow is in it. */
    @JvmStatic fun rowOf(key: String): View? = live().firstNotNullOfOrNull { it.rowOf(key) }

    /**
     * A tap from the mini player into the cover or lyrics: the pill becomes the card that the
     * scene keeps. False when the mini player is not what is showing, and the OEM card's own
     * route applies.
     */
    @JvmStatic fun prepareSceneEntry(): Boolean =
        live().any { it.beginTransition(toNative = true, scene = true) }

    /** Out of the cover or lyrics: the card shrinks back into the pill. */
    @JvmStatic fun prepareSceneExit(): Boolean =
        live().any { it.beginTransition(toNative = false, scene = true) }

    @JvmStatic fun transitionActive(): Boolean = live().any { it.transitionActive() }

    /** A released notification's row under a point on screen: a swipe down there collapses it. */
    @JvmStatic fun releasedRowAt(x: Float, y: Float): String? =
        live().firstNotNullOfOrNull { it.releasedRowAt(x, y) }

    @JvmStatic fun spreadCollapseAt(x: Float, y: Float): Boolean =
        live().any { it.spreadCollapseAt(x, y) }

    private var spreadCollapseOwner: MiniPlayerController? = null
    private var spreadCollapseTracker: VelocityTracker? = null

    @JvmStatic fun beginSpreadCollapse(ev: MotionEvent, startY: Float): Boolean {
        val owner = live().firstOrNull { it.beginSpreadCollapse(startY) } ?: return false
        spreadCollapseOwner = owner
        spreadCollapseTracker?.recycle()
        spreadCollapseTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        owner.moveSpreadCollapse(ev.rawY)
        return true
    }

    @JvmStatic fun moveSpreadCollapse(ev: MotionEvent) {
        spreadCollapseTracker?.addMovement(ev)
        spreadCollapseOwner?.moveSpreadCollapse(ev.rawY)
    }

    @JvmStatic fun endSpreadCollapse(ev: MotionEvent, cancelled: Boolean) {
        val tracker = spreadCollapseTracker
        tracker?.addMovement(ev)
        tracker?.computeCurrentVelocity(1000)
        spreadCollapseOwner?.moveSpreadCollapse(ev.rawY)
        spreadCollapseOwner?.endSpreadCollapse(tracker?.yVelocity ?: 0f, cancelled)
        tracker?.recycle()
        spreadCollapseTracker = null
        spreadCollapseOwner = null
    }

    /**
     * The lock screen's list at its top, where a pull down on a card can fold it home.
     * [readRest]: read where the lock screen rests the list afresh - on the DOWN, once a gesture.
     * With no controller, or nothing readable, true: the pull as it was before this was asked.
     */
    @JvmStatic fun nativeStackAtTop(readRest: Boolean): Boolean =
        live().let { all -> all.isEmpty() || all.any { it.nativeStackAtTop(readRest) } }

    /** For `op mini`'s touch log: the scroll and the rest the last read compared. */
    @JvmStatic fun nativeScrollTrace(): String = live().joinToString { it.lastScrollRead }

    /** The list is being scrolled from a card: a card held where it landed goes with it. */
    @JvmStatic fun releaseNativeScrollPin() = live().forEach { it.releaseNativeScrollPin() }

    /**
     * That row folds back into the row of islands, following the finger from [ev] on as the
     * media card does when it is pulled down into the pill.
     */
    @JvmStatic fun collapseRow(key: String, ev: MotionEvent): Boolean { android.os.Trace.beginSection("MC t.collapseRowS"); try {
        val owner = live().firstOrNull { it.collapseRow(key, ev.rawY) } ?: return false
        noteOwner = owner
        noteTracker?.recycle()
        noteTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        return true
    } finally { android.os.Trace.endSection() } }

    /**
     * The top of visible stack content, MAX_VALUE with no visible rows, or NaN when unavailable.
     * The clock is given this rather than the stack's
     * own figure: the OEM's (NotificationStackingInteractor.calculateKeyguardNotifTop) reserves
     * room for one big notification (miuiBigNotificationHeight) above the stack's bottom however
     * short the rows are, so one row by the pill shrank the clock it came nowhere near
     * (filmed 2026-09-25).
     */
    @JvmStatic fun stackContentTop(): Float =
        live().map { it.stackContentTop() }.filterNot(Float::isNaN).minOrNull() ?: Float.NaN

    /** Which row the last stackContentTop came from, and how many hidden rows it passed over, for `op mini`. */
    @JvmStatic fun stackContentSource(): String = live().firstOrNull()?.contentTopFrom ?: "-"

    /** Where a flight from the cover lands, and the corner it lands in. */
    @JvmStatic @JvmName("artworkRestBox") internal fun artworkRestBox(): CoverMorphMotion.Box? =
        live().firstNotNullOfOrNull { it.artworkRestBox() }

    @JvmStatic fun artworkRadius(): Float =
        live().firstNotNullOfOrNull { it.artworkRadius() } ?: 0f

    /** CoverMorphLayer draws the artwork itself while it flies to or from the cover. */
    @JvmStatic fun setArtBridged(bridged: Boolean) = live().forEach { it.setArtBridged(bridged) }

    /** How far below where the stack has [card] now the card is held, or is going; 0 for neither. */
    @JvmStatic fun cardHeldDy(card: View): Float =
        live().firstNotNullOfOrNull { it.cardHeldDy(card) } ?: 0f

    private fun keepNativeHeaderInvisible(): Boolean = synchronized(controllers) {
        controllers.values.any { it.controller.hardSuppressionActive() }
    }

    /** Main saw the media card move to another track (its "card track" log). */
    @JvmStatic fun cardTrackChanged() = live().forEach { it.cardTrackChanged() }

    internal fun observeSession(token: Any, packageName: String): Boolean {
        val changed = synchronized(selectionLock) { sessionSelection.observe(token) }
        if (changed) {
            restoreScene = false
            Xp.log("MCMini: media session -> $packageName@${Integer.toHexString(token.hashCode())}; " +
                "dynamic choice reset to mini")
        }
        return changed
    }

    internal fun nativeRequested(token: Any?): Boolean = synchronized(selectionLock) {
        sessionSelection.nativeRequestedFor(token)
    }

    /** The card chosen with the card already up: nothing to morph, nothing to refresh yet. */
    internal fun noteNative(token: Any, why: String) {
        val changed = synchronized(selectionLock) { sessionSelection.requestNative(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> native ($why)")
    }

    /** The choice is made first; the morph then runs toward it, or turns round if one runs. */
    internal fun selectNative(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestNative(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> native")
        live().forEach { it.beginTransition(toNative = true, scene = false) }
        refresh()
    }

    internal fun selectMini(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestMini(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> mini")
        live().forEach { it.beginTransition(toNative = false, scene = false) }
        refresh()
    }

    internal fun chooseNative(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestNative(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> native (pulled)")
    }

    internal fun chooseMini(token: Any) {
        val changed = synchronized(selectionLock) { sessionSelection.requestMini(token) }
        if (changed) Xp.log("MCMini: dynamic choice -> mini (out of the scene)")
    }

    /** The media card dismissed, its session still alive (MediaCard.sCardRemoved). */
    @JvmStatic fun mediaCardRemoved() {
        resetDynamicChoice("media card dismissed")
        live().forEach { it.onMediaCardRemoved() }
    }

    internal fun resetDynamicChoice(reason: String) {
        val changed = synchronized(selectionLock) { sessionSelection.resetChoice() }
        if (changed) Xp.log("MCMini: dynamic choice reset to mini ($reason)")
    }

    internal fun endSession(token: Any, reason: String) {
        val changed = synchronized(selectionLock) { sessionSelection.end(token) }
        if (changed) Xp.log("MCMini: media session ended ($reason)")
    }

    /**
     * A real notification row's material, once one has been drawn; until then none, and the
     * islands wear the media card's own recipe. The fallback used to be the media recipe's modes
     * over the focus list's keyguard colours (mFocusNotifiColorList) - the expanded focus card's
     * darker material, a 60% black layer last. A lock screen with only focus notifications and
     * the music never draws an ordinary row, so every island dressed there went near black once
     * that list was filled; dressed before it was (all zeros, just after a restart) they were
     * light, which is why the black came and went with switches (#91, logged 2026-10-20).
     */
    private fun notificationLook(): Pair<IntArray, FloatArray>? {
        val recordedBlend = notificationBlend ?: return null
        val recordedGlass = notificationGlass ?: return null
        return recordedBlend.copyOf() to recordedGlass.copyOf()
    }

    /**
     * The pill's material: the card's recorded calls replayed on the pill's layer, with the
     * pill itself made the blur container they expect above them.
     *
     * HyperOS notification material has two layers. The card's background is only an element:
     * it tints and shapes a blur that an ancestor container provides, and that container is
     * what blurs through the window to the wallpaper. The pill hangs off the window root with
     * no such ancestor - the element alone drew nothing at all - so the pill is made a
     * container the way ElementSurfaceModel.updateBlurContainer makes one.
     */
    internal fun material(view: ImageView, classLoader: ClassLoader,
                          notificationStyle: Boolean = false) {
        hostMaterial?.let { it(view, classLoader); return }
        val ctx = view.context
        if (notificationStyle) notificationDressedViews.add(view)
        else notificationDressedViews.remove(view)
        runCatching {
            val res = ctx.resources
            fun id(type: String, name: String): Int =
                res.getIdentifier(name, type, "com.android.systemui").also {
                    require(it != 0) { "no $type/$name" }
                }
            val util = Class.forName("com.android.systemui.statusbar.notification.utils.NotificationUtil",
                false, classLoader)
            (view.parent as? View)?.let { container -> run {
                // Blur mode 1, the notification container radius, and through the window.
                util.getMethod("applyContainerViewBlur", Context::class.java, View::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType).invoke(null, ctx, container, 0, 0, false)
                View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(container,
                    res.getDimensionPixelSize(id("dimen", "notification_glass_small_blur_max_radius")),
                    res.getDimensionPixelSize(id("dimen", "notification_glass_big_blur_max_radius")))
                runCatching {
                    Class.forName("miuix.core.util.MiuiBlurUtils", false, classLoader)
                        .getMethod("setMiBlurWinType", View::class.java).invoke(null, container)
                }
            } }
            if (cardRecipe == null) loadRecipe(ctx, classLoader)
            val recipe = cardRecipe
            if (recipe == null && (!notificationStyle || notificationLook() == null))
                error("no card or notification material has been recorded yet")
            view.setImageDrawable(null)
            view.background = cardBackground?.newDrawable(res)?.mutate()
            if (recipe != null) run {
                recipe.forEach { call ->
                    val args = call.args.copyOf()
                    args[call.viewAt] = view
                    call.method.invoke(null, *args)
                }
            }
            if (notificationStyle) notificationLook()?.let { (colors, glass) ->
                runCatching { run {
                    notificationBackground?.newDrawable(res)?.mutate()?.let { view.background = it }
                    val blend = notificationBlendMethod ?: blendMethod ?: recipe?.firstOrNull {
                        it.method.name == "applyElementViewBlend"
                    }?.method
                    val setGlass = notificationGlassMethod ?: glassMethod ?: recipe?.lastOrNull {
                        it.method.name == "setMiGlassCompat"
                    }?.method
                    blend?.invoke(null, ctx, view, false, colors, false)
                    setGlass?.invoke(null, view, glass)
                } }.onFailure { Xp.log("MCMini: notification glass unavailable: $it") }
            }
            dressedViews.add(view)
            // Dressed in the AOD - a small island coming up there: dimmed as the rest are.
            aodDimArgs?.let { run { dimView(view, it) } }
        }.onFailure { error ->
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            if (!materialFailed) {
                materialFailed = true
                Xp.w("MCMini: card material unavailable, plain fill instead: $cause")
            }
            view.background = null
            view.setImageDrawable(GradientDrawable().apply { setColor(0x9E1F2324.toInt()) })
        }
    }
}

/** The full-screen AOD's colour run quiet this long: over, whether or not it said so. */
private const val AOD_DIM_SETTLE_MS = 600L

/** Awake with no colour run at all: back to the recipe after this long. */
private const val AOD_DIM_WAKE_MS = 1500L

/** A named section in a system trace (perfetto, atrace_apps com.android.systemui); free when none is recording. */
internal inline fun <T> traced(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try {
        return block()
    } finally {
        android.os.Trace.endSection()
    }
}
