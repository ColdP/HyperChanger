// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The keyguard stack's "N notifications" state (KeyguardNotificationState.NUMBER) as the stack
 * island's folded form - ColorOS's way (read 2026-09-30): the ordinary notifications' rows never
 * leave the stack; folded, the stack itself hides them (numStateAlpha 0, piled at its bottom) and
 * the island stands where its "N个通知" line was; opened, the stack scrolls them out as its list.
 *
 * Everything here is SystemUI's own path, read from this build (md5 4ddaa4a0): the state is
 * where the stack is scrolled to, and going to one is scrolling there, as the full-screen AOD
 * does it (NotificationContainerViewBinder lambda 16). See keyguard-notification-fold.
 */
internal object NumState {

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** The islands fold the ordinary notifications this way (LockIslands.setActive turns it on). */
    @Volatile var folding = false
        private set

    /** Whether the hooks went in: without them nothing here is used, and the old way stays. */
    @Volatile var available = false
        private set

    /** The stack as it last said, from its own enter/exitNumState; null until it has. */
    @Volatile var inNumber: Boolean? = null
        private set

    private var modelRef: WeakReference<Any>? = null
    private var countRef: WeakReference<View>? = null

    /** For `op mini` / `op numstate`: the last few things done here. */
    private val log = ArrayDeque<String>()

    fun trace(what: String) {
        synchronized(log) {
            log.addLast("${SystemClock.uptimeMillis() % 100000} $what")
            while (log.size > 16) log.removeFirst()
        }
    }

    fun describe(): String = "numstate folding=$folding available=$available in=$inNumber " +
        LockIslands.systemHiddenForProbe().let { if (it.isEmpty()) "" else "sysHidden=[$it] " } +
        "default=${runCatching { defaultFlow()?.let { Xp.callMethod(it, "getValue") } }.getOrNull()} " +
        "log=" + synchronized(log) { log.joinToString(" ; ") } +
        " || frames=" + framesText()

    fun install(classLoader: ClassLoader) {
        val vmClass = runCatching {
            Xp.findClass("com.miui.systemui.notification.view.viewmodel.NotificationContainerViewModel", classLoader)
        }.getOrElse {
            Xp.log("MCNum: no container model, ordinary notifications stay islands the old way: $it")
            return
        }
        runCatching {
            for ((name, value) in listOf("enterNumState" to true, "exitNumState" to false)) {
                Xp.hookAll("sysui.numstate." + Xp.slug(name), vmClass, name) { chain ->
                    val result = chain.proceed()
                    modelRef = WeakReference(chain.thisObject)
                    if (inNumber != value) {
                        inNumber = value
                        trace(if (value) "stack folded" else "stack opened")
                        listeners.forEach { runCatching { it(value) } }
                    }
                    result
                }
            }
            available = true
        }.onFailure { Xp.log("MCNum: num state unhookable: $it") }
        // Let go in between, the stack rests as a pile (STACK) with the rows out on the lock
        // screen and no island: folding, a hands-up that lands there lands folded instead, so
        // the stack is either the island or the list, as ColorOS's is either capsule or list.
        runCatching {
            Xp.hookAll("sysui.numstate.switch_stack", vmClass, "switchStackStatus") { chain ->
                val target = chain.proceed()
                runCatching { handsUp(chain.thisObject, chain.args.firstOrNull(), target as Float) }
                    .getOrNull() ?: target
            }
        }.onFailure { Xp.log("MCNum: hands-up unhookable: $it") }
    }

    fun addListener(l: (Boolean) -> Unit) { listeners.addIfAbsent(l) }

    /** The islands take over the ordinary notifications' folding, or give it back. */
    fun setFolding(on: Boolean) {
        if (!available || folding == on) return
        folding = on
        trace("folding=$on")
        forceDefault(on)
        if (!on) {
            Gen.removeCallbacks(foldCheck)
            hideCount(false)
        }
    }

    private fun handsUp(model: Any, info: Any?, target: Float): Float? {
        if (!folding || info == null || !LockIslands.foldsToCount()) return null
        val event = (Xp.getObjectField(info, "stackShowEvent") as? Enum<*>)?.name
        if (event != "HANDS_UP" || Xp.getObjectField(info, "isNumberState") == true) return null
        if ((Xp.getObjectField(info, "normalNotifCount") as Number).toInt() <= 0) return null
        val factory = Xp.getObjectField(Xp.getObjectField(model, "\$\$delegate_0"), "strategyFactory")
        fun strategy(name: String) = Xp.callMethod(Xp.getObjectField(factory, name), "get")
        fun number() = (Xp.callMethod(strategy("numberStrategy"), "calculateTargetYPosition", info) as Number).toFloat()
        // The row spread out as the list and pulled toward home: folded. The stack's own hands-up
        // reads the list's top without the pull on it (nsslTopYPositionWithoutOverScroll), and a
        // pull down on the list moves only the pull - so it put the list back every time, and with
        // the media card and a focus card in it the list and the pile rest at the same place
        // (the clock's smallest): the row stood half folded (filmed 2026-09-30).
        if (LockIslands.spreadingNow() && MiniPlayerRuntime.spreadFolding()) {
            val number = number()
            trace("hands up with the row going home: folded ($target -> $number)")
            return number
        }
        val stack = (Xp.callMethod(strategy("stackStrategy"), "calculateTargetYPosition", info) as Number).toFloat()
        if (kotlin.math.abs(stack - target) >= 1f && !pileAt(model, info, target)) return null
        val number = number()
        trace("hands up at the pile: folded instead ($target -> $number)")
        return number
    }

    /**
     * Whether the stack resting with its top at [top] reads as its pile (STACK): its own sum
     * (NotificationContainerViewModel's currentsStackState), the room under the top against the
     * number threshold and the pile's height. The strategy's pile top can be clamped to the
     * clock's smallest, where it is not where a hands-up leaves it.
     */
    private fun pileAt(model: Any, info: Any, top: Float): Boolean = runCatching {
        val sample = value(model, "getOnUpdateChildSampleStackInfo") ?: return false
        val bottom = (Xp.getObjectField(info, "notificationBottomOnKeyguard") as Number).toFloat()
        val scrim = (Xp.getObjectField(info, "scrimTopPadding") as Number).toFloat()
        val focus = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val room = bottom - top - scrim - focus
        val threshold = (Xp.callMethod(model, "getNumStateHeightThreshold", sample) as Number).toFloat()
        val pile = kotlin.math.ceil((Xp.callMethod(model, "stackingStateHeight\$default", model) as Number).toFloat())
        room >= threshold && room <= pile
    }.getOrDefault(false)

    // ---------------------------------------------------------------- the model and its state

    /** The stack's container model (NotificationContainerViewModel). */
    fun model(): Any? {
        modelRef?.get()?.let { return it }
        val stack = MiniPlayerRuntime.stackForProbe() ?: return null
        return runCatching {
            val controller = Xp.getObjectField(stack, "mController")
            val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
            val helper = Xp.getObjectField(injector, "numStateTouchHelper")
            Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        }.getOrNull()?.also { modelRef = WeakReference(it) }
    }

    private fun value(owner: Any, getter: String): Any? =
        Xp.callMethod(Xp.callMethod(owner, getter), "getValue")

    /** NUMBER, STACK or LIST, as the stack reads itself now; null unread. */
    fun state(): String? = runCatching {
        (value(model() ?: return null, "getCurrentsStackState") as Enum<*>).name
    }.getOrNull()

    /** The stack is moving between states on its own (a scroll or the fold's fade). */
    fun busy(): Boolean = runCatching {
        val model = model() ?: return false
        value(model, "isNumStateAnimating") == true ||
            Xp.getObjectField(Xp.getObjectField(model, "isBeingDraggedOrAnimating"), "flow")
                .let { Xp.callMethod(it, "getValue") } == true
    }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun stateConst(model: Any, name: String): Any {
        val now = value(model, "getCurrentsStackState") as Enum<*>
        return java.lang.Enum.valueOf(now.javaClass as Class<out Enum<*>>, name)
    }

    /** The scroll that puts the stack in [state], as SystemUI works it out. */
    fun scrollFor(model: Any, state: String): Int {
        val info = value(model, "getOnUpdateChildSampleStackInfo")!!
        val event = Xp.callMethod(model, "notifStackStateToEvent", stateConst(model, state))
        val status = Xp.callMethod(model, "getKeyguardStackStatusInfo", info, event)
        val top = (Xp.callMethod(model, "switchStackStatus", status) as Number).toFloat()
        val focus = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val bound = (Xp.callMethod(model, "positionWithoutMinScrollRange", focus) as Number).toFloat()
        return (bound - top).toInt()
    }

    /** The stack's own scroll now; null unread. */
    fun scrollY(): Int? = runCatching {
        val model = model() ?: return null
        (Xp.callMethod(Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get"),
            "getOwnScrollY") as Number).toInt()
    }.getOrNull()

    /**
     * The focus rows the stack has measured (its sample's focusNotifCount); null unread. The
     * list's place is worked out from this sample: asked before it counts rows just come in, the
     * place left them out and the stack read the result as its pile (STACK).
     */
    fun focusCount(): Int? = runCatching {
        val info = value(model() ?: return null, "getOnUpdateChildSampleStackInfo") ?: return null
        (Xp.getObjectField(info, "focusNotifCount") as Number).toInt()
    }.getOrNull()

    /**
     * The stack's pull past its scroll now (the model's overScrollAmount), 0 unread. A finger
     * pulling the list down toward the fold moves this, not the scroll: the scroll only follows
     * on the let-go, as the hands-up settles it (calculateNotifTop = base - scrollY + this).
     */
    fun overScroll(): Float {
        val fromModel = runCatching {
            val model = model() ?: return@runCatching 0f
            (Xp.callMethod(Xp.getObjectField(Xp.getObjectField(model, "overScrollAmount"), "flow"),
                "getValue") as Number).toFloat()
        }.getOrDefault(0f)
        // The stack's own reading of its pull at the top, should the model's not carry it.
        val fromStack = runCatching {
            (Xp.callMethod(MiniPlayerRuntime.stackForProbe() ?: return@runCatching 0f,
                "getCurrentOverScrollAmount", true) as Number).toFloat()
        }.getOrDefault(0f)
        return if (kotlin.math.abs(fromStack) > kotlin.math.abs(fromModel)) fromStack else fromModel
    }

    /** Both pulls apart, for the frame log. */
    fun overScrollParts(): String = runCatching {
        val m = model()?.let {
            (Xp.callMethod(Xp.getObjectField(Xp.getObjectField(it, "overScrollAmount"), "flow"), "getValue") as Number).toInt()
        }
        val s = MiniPlayerRuntime.stackForProbe()?.let {
            (Xp.callMethod(it, "getCurrentOverScrollAmount", true) as Number).toInt()
        }
        "$m/$s"
    }.getOrDefault("?")

    /**
     * The furthest the stack scrolls (NotificationStackScrollLayout.getScrollRange). The list's
     * place as the click-to-list strategy works it out can be past it - 658 against a stack that
     * went no further than 375 with the media card and a focus card in it (2026-09-30) - and the
     * spread, aimed there, stood at 0.57 for seconds, sent there again and again.
     */
    fun maxScroll(): Int? = runCatching {
        (Xp.callMethod(MiniPlayerRuntime.stackForProbe() ?: return null, "getScrollRange") as Number).toInt()
    }.getOrNull()

    /** Where the list rests: the strategy's place, as far as the stack can scroll. */
    fun listScroll(): Int? {
        val list = scrollTo("LIST") ?: return null
        val max = maxScroll() ?: return list
        val number = scrollTo("NUMBER") ?: return list
        return if (list > number) minOf(list, maxOf(max, number)) else list
    }

    // ---------------------------------------------------------------- the spread's frame log

    /** Every frame of the spread moving, for `op numstate`: the last FRAMES_KEPT of them. */
    private val frames = ArrayDeque<String>()
    private const val FRAMES_KEPT = 90

    fun frame(what: String) {
        synchronized(frames) {
            frames.addLast("${SystemClock.uptimeMillis() % 100000} $what")
            while (frames.size > FRAMES_KEPT) frames.removeFirst()
        }
    }

    fun framesText(): String = synchronized(frames) { frames.joinToString(" ; ") }

    /**
     * Where the stack's top is, as a scroll: the scroll less the pull on it, so a finger's pull
     * counts as it is made. On the same scale as [scrollFor]; null unread.
     */
    fun position(): Int? = scrollY()?.let { (it - overScroll()).toInt() }

    /** [scrollFor] for [state] as the stack is now; null unread. */
    fun scrollTo(state: String): Int? = runCatching { scrollFor(model() ?: return null, state) }.getOrNull()

    /** The stack scrolled to [state]: animated on the screen-on scroll's spring, or set. */
    fun goTo(state: String, anim: Boolean = true, why: String): String = runCatching {
        val model = model() ?: return "no model"
        val stack = MiniPlayerRuntime.stackForProbe() ?: return "no stack"
        val y = scrollFor(model, state)
        val refactor = Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get")
        val scroller = Xp.callMethod(refactor, "getOverScroller")
        val cur = (Xp.callMethod(refactor, "getOwnScrollY") as Number).toInt()
        val injector = Xp.getObjectField(Xp.getObjectField(stack, "mController"), "mNsslControllerInjector")
        val done = if (!anim || cur == y) {
            Xp.callMethod(scroller, "abortAnimation")
            Xp.callMethod(stack, "setOwnScrollY", y)
            "scrollY $cur -> $y (set)"
        } else {
            val animator = Xp.callMethod(Xp.getObjectField(model, "notificationScreenOnOffAnimator"), "get")
            val ease = animator.javaClass.getField("screenOnScrollYEaseStyle").get(null)
            Xp.callMethod(scroller, "startFolmeScroll", 0, cur, y - cur, 0, ease)
            Xp.callMethod(stack, "animateScroll")
            Xp.callMethod(injector, "onStartScroll", y.toFloat(), topChangeType(injector, "NOTIFS_CHANGED"))
            "scrollY $cur -> $y (folme)"
        }
        trace("$why: to $state, $done")
        done
    }.getOrElse { trace("$why: to $state failed: $it"); "failed: $it" }

    /**
     * The stack put at scroll [y] this frame, no animation (a finger has it): as goTo's unanimated
     * way, a running scroll of its own stopped first. False when it could not be.
     */
    fun setScroll(y: Int): Boolean = runCatching {
        val model = model() ?: return false
        val stack = MiniPlayerRuntime.stackForProbe() ?: return false
        val refactor = Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get")
        if ((Xp.callMethod(refactor, "getOwnScrollY") as Number).toInt() == y) return true
        Xp.callMethod(Xp.callMethod(refactor, "getOverScroller"), "abortAnimation")
        Xp.callMethod(stack, "setOwnScrollY", y)
        true
    }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun topChangeType(injector: Any, name: String): Any {
        val m = injector.javaClass.methods.first { it.name == "onStartScroll" && it.parameterTypes.size == 2 }
        return java.lang.Enum.valueOf(m.parameterTypes[1] as Class<out Enum<*>>, name)
    }

    // ---------------------------------------------------------------- the screen-on default

    /**
     * The state the lock screen wakes to is the user's setting default_keyguard_notif_state
     * (MiuiKeyguardNotificationSettingsRepository.defaultState). Folding, it is NUMBER in memory
     * only - its flow's value set, the setting itself untouched - so every wake finds the island
     * and not the rows. Given back, the setting's own value again. A change of the setting
     * while folding sets it back to the setting's, until the islands next turn this on.
     */
    private var forcedDefault = false

    private fun defaultFlow(): Any? = runCatching {
        val repo = Xp.getObjectField(model() ?: return null, "settingsRepository")
        Xp.getObjectField(Xp.getObjectField(repo, "defaultState"), "\$\$delegate_0")
    }.getOrNull()

    /**
     * Retirement: the OEM's default state flow gets the value the setting names, if an island
     * ever moved it. The listeners stay - they are installed once, with the hooks, and a dry run
     * does not reinstall - and folding is LockIslands' to give back.
     */
    @JvmStatic
    fun release() {
        forceDefault(false)
    }

    private fun forceDefault(on: Boolean) {
        runCatching {
            val flow = defaultFlow() ?: return
            val model = model() ?: return
            if (on) {
                Xp.callMethod(flow, "setValue", stateConst(model, "NUMBER"))
                forcedDefault = true
                trace("default NUMBER (in memory)")
            } else if (forcedDefault) {
                forcedDefault = false
                val setting = android.provider.Settings.System.getInt(
                    (Main.sAppCtx ?: return).contentResolver, "default_keyguard_notif_state", 2)
                val name = when (setting) { 3 -> "NUMBER"; 1 -> "LIST"; else -> "STACK" }
                Xp.callMethod(flow, "setValue", stateConst(model, name))
                trace("default back to the setting's $name")
            }
        }.onFailure { trace("default not set: $it") }
    }

    /**
     * The row spread out as the list: a wake meanwhile wakes to the list, the default NUMBER
     * otherwise (forceDefault). Only while folding has the default in hand.
     */
    fun wakeAsList(on: Boolean) {
        if (!folding || !forcedDefault) return
        runCatching {
            val flow = defaultFlow() ?: return
            val model = model() ?: return
            val name = if (on) "LIST" else "NUMBER"
            if ((Xp.callMethod(flow, "getValue") as? Enum<*>)?.name == name) return
            Xp.callMethod(flow, "setValue", stateConst(model, name))
            trace("default $name (in memory)")
        }.onFailure { trace("default not set: $it") }
    }

    /**
     * Folding and the lock screen up with the stack as a pile: folded. Asked when the islands
     * show (after SystemUI starts, the first lock screen has not been through a wake), and
     * never while the stack is being moved or is the list the user opened.
     */
    fun foldIfPiled(why: String) {
        if (!folding) return
        Gen.removeCallbacks(foldCheck)
        foldWhy = why
        Gen.postDelayed(foldCheck, FOLD_SETTLE_MS)
    }

    private var foldWhy = ""
    private const val FOLD_SETTLE_MS = 350L

    private val foldCheck = Runnable {
        if (!folding || !LockIslands.foldsToCount()) return@Runnable
        // The list the spread opened, passing through the pile on its way: not folded back.
        if (LockIslands.spreadingNow()) { trace("pile check skipped: spread ($foldWhy)"); return@Runnable }
        if (busy()) { foldIfPiled(foldWhy); return@Runnable }
        if (state() == "STACK") goTo("NUMBER", anim = true, why = "piled ($foldWhy)")
    }

    // ---------------------------------------------------------------- the count line

    /**
     * The stack's own "N个通知" line (NotificationNumStateView) sits inside the island's pill:
     * hidden while the island stands for it. On its children's transitionAlpha (the zen mark, the
     * divider, the count), which nothing of SystemUI's writes: the line's own transitionAlpha is
     * the keyguard's fade (scaleAndAlpha, NotificationNumStateViewBinder), and showing the line
     * by setting that to 1 put "勿扰" over the bouncer, where the keyguard had faded it out (#42).
     */
    fun hideCount(hide: Boolean) {
        val v = countView() as? ViewGroup ?: return
        val want = if (hide) 0f else 1f
        var changed = false
        for (k in 0 until v.childCount) {
            val c = v.getChildAt(k)
            if (c.transitionAlpha != want) {
                c.transitionAlpha = want
                changed = true
            }
        }
        if (changed) trace(if (hide) "count hidden" else "count shown")
    }

    private fun countView(): View? {
        countRef?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val root = MiniPlayerRuntime.stackForProbe()?.rootView ?: return null
        val queue = ArrayDeque<View>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationNumStateView")) {
                countRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) queue.add(v.getChildAt(k))
        }
        return null
    }
}
