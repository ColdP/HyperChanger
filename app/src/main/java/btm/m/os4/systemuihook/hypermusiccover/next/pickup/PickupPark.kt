// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * The hidden display a mini program's order page waits on between visits - ColorOS's
 * ObserverVirtualDisplay, which its observeagent moves the task onto and refreshes the page in.
 *
 * Why a display at all: 微信 suspends a mini program once it is not in front, so a page left in the
 * background stops asking for its order's state. The code can still be read off such a task (that is
 * the island's first stage), but it is the code as of the moment the user left. A task on a display
 * of its own reads as visible, so the mini program stays up and keeps its own page current, with
 * nothing hooked inside 微信.
 *
 * The display is cheap and invisible: a 1200x2608 buffer whose frames are taken and dropped (a page
 * that is never drawn is a page whose WebView stops being asked to draw, so the reader is what keeps
 * it going), and no renderer of ours. It is a display the system has, not the user.
 *
 * [FLAGS] is ColorOS's own set. DESTROY_CONTENT_ON_REMOVAL, which ColorOS also sets, is left out on
 * purpose: with it, a display that goes away while a task is parked on it - this process being
 * killed, SystemUI being restarted (which this module does to itself while developing) - destroys the
 * task, and with it the user's order page. Without it the system moves the task back to display 0,
 * which is what [release] does anyway whenever there is time to.
 */
internal object PickupPark {

    private const val TAG = "MCPickupPark: "
    private const val NAME = "mc-pickup-park"
    private const val W = 1200
    private const val H = 2608
    private const val DPI = 480

    /**
     * PUBLIC | OWN_CONTENT_ONLY | TRUSTED | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED - AOSP's flags, the
     * set ColorOS builds its own hidden display with. TRUSTED is the one behind a permission
     * (ADD_TRUSTED_DISPLAY), so whether creating this succeeds from SystemUI rather than from a shell
     * is a real question about the module, not a detail.
     */
    private const val FLAGS = 1 or 8 or 1024 or 16384 or 65536

    /** OPPO's own interval for an order being made, the shortest of its three. */
    const val EVERY = 30_000L

    private val bg: Handler by lazy { Handler(Gen.handlerThread("mc-pickup-park").looper) }

    // All below on [bg], but for [parked], which anyone may read: a copy is swapped in whole.
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    /**
     * The tasks on the hidden display, in the order they came. Several at once, as ColorOS's has -
     * one display, up to three observe tasks on it (`max_task_count`).
     */
    @Volatile private var parked: Set<Int> = emptySet()
    /** The hidden display's id while it is up, -1 otherwise. */
    @Volatile private var vdId = -1
    private var until = 0L
    private var probeSince = 0L
    private var every = EVERY
    private var reads = 0
    private val seen = LinkedHashSet<String>()

    // ------------------------------------------------------------------ the display

    /**
     * The hidden display, made if it is not up yet. ADD_TRUSTED_DISPLAY is checked against this
     * process - SystemUI's uid, not adb's - so a refusal here is the answer to that question rather
     * than a bug: it is logged, and said in the probe's reply with the exception's own text.
     */
    // FLAGS carries a hidden flag the SDK annotation does not list; SystemUI may pass it.
    @android.annotation.SuppressLint("WrongConstant")
    private fun ensure(): String {
        val ctx = Main.appContext() ?: return "no context"
        vd?.let { return "display ${it.display.displayId}" }
        return runCatching {
            val dm = ctx.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
            val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
            // Frames have to be taken: a producer with no free buffer left stops drawing, and the
            // page's own redraws are half of what keeps it current.
            r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, bg)
            val d = dm.createVirtualDisplay(NAME, W, H, DPI, r.surface, FLAGS)
                ?: error("createVirtualDisplay returned null")
            reader = r
            vd = d
            vdId = d.display.displayId
            Xp.log(TAG + "hidden display ${d.display.displayId} (${W}x${H}@${DPI}, flags $FLAGS)")
            "display ${d.display.displayId}"
        }.getOrElse {
            Xp.log(TAG + "no hidden display: $it")
            "no display: $it"
        }
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(task, display): 微信's appbrand task onto the hidden
     * display, and back to 0 the same way. This phone's `am` has no move-stack, and the service is an
     * interface, so the method is found by name the way the assist request is.
     */
    private fun move(task: Int, display: Int): String = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val m = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
            ?: error("no moveRootTaskToDisplay")
        m.invoke(atm, task, display)
        Xp.log(TAG + "task $task -> display $display")
        "task $task -> display $display"
    }.getOrElse {
        // Through reflection the system's own reason is the cause: on its own the log said only
        // InvocationTargetException (#115, a phone where the move back did not take).
        val why = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
        Xp.log(TAG + "task $task not moved to $display: $why")
        "move failed: $why"
    }

    /**
     * [task] goes back to the phone's own display, and the display is let go once nothing is left
     * on it - never the other way round. Worth re-reading before changing.
     *
     * moveRootTaskToDisplay puts the task on *top* of display 0 - AOSP passes onTop = true and offers
     * no other way - and most releases are not the user asking for it: the order turning ready, 微信
     * coming to the front, another mini program being opened. The order page then came up over
     * whatever the user was in, 微信 included, and swiping it away parked it again, so it came back the
     * next time 微信 was opened (2026-10-13, 「卡在前台划不出去」). So unless [front] - the island was
     * tapped - the task the user was looking at is put back over it.
     */
    fun release(task: Int, why: String, front: Boolean = false): String {
        val back = if (task in parked) {
            parked = parked - task
            val keep = if (!front) frontTask(except = task) else -1
            ownMoveUntil = SystemClock.uptimeMillis() + OWN_MOVE
            val moved = move(task, 0)
            if (keep >= 0) restore(keep)
            moved
        } else "not parked"
        val s = "released $task ($why): $back"
        Xp.log(TAG + s)
        if (parked.isEmpty()) letGoSoon()
        return s
    }

    /** Every parked task back, and the display let go: the probe's `vdoff`, the switch going off. */
    fun releaseAll(why: String): String {
        parked.toList().forEach { release(it, why) }
        letGoSoon()
        return "released all ($why)"
    }

    /**
     * The display let go a while after its last task has left it, not at once. Released in the same
     * moment as a task came off it, the task's window reached display 0 with its input still filed
     * under the display that was gone - InputDispatcher's 「Found window …AppBrandUI00 in display 0,
     * but it should belong to display 21」 - and from then on no touch reached the mini program and
     * Back did nothing (2026-10-13 19:07:05, the island tapped while the order was parked). A park in
     * the meantime takes the display back up as it is ([park]).
     */
    private fun letGoSoon() {
        bg.removeCallbacksAndMessages(LET_GO)
        bg.postDelayed({ if (parked.isEmpty()) letGo() }, LET_GO, LET_GO_AFTER)
    }

    private val LET_GO = Any()
    private const val LET_GO_AFTER = 5_000L

    /** The display itself, once nothing is on it. */
    private fun letGo() {
        runCatching {
            vd?.release()
            reader?.close()
        }.onFailure { Xp.log(TAG + "display not released: $it") }
        vd = null
        vdId = -1
        reader = null
        until = 0L
    }

    /**
     * The task in front on the phone's own display, other than [except] - the one the user is looking
     * at - or -1. The running tasks come most recently active first, across every display, so the
     * hidden one's is skipped by its display rather than by its place in the list.
     */
    private fun frontTask(except: Int): Int = runCatching {
        val ctx = Main.appContext() ?: return@runCatching -1
        val displayId = android.app.TaskInfo::class.java.getField("displayId")
        @Suppress("DEPRECATION")
        ctx.getSystemService(android.app.ActivityManager::class.java).getRunningTasks(16)
            .firstOrNull { it.taskId != except && displayId.getInt(it) == 0 }?.taskId ?: -1
    }.onFailure { Xp.log(TAG + "front task not found: $it") }.getOrDefault(-1)

    /** [task] back in front, with no animation: it was in front a moment ago and is only kept there. */
    // Runs in SystemUI, which holds REORDER_TASKS; this APK's manifest is not the one asked.
    @android.annotation.SuppressLint("MissingPermission")
    private fun restore(task: Int) {
        runCatching {
            val ctx = Main.appContext() ?: return
            ctx.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(task, 0,
                android.app.ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
            Xp.log(TAG + "task $task kept in front")
        }.onFailure { Xp.log(TAG + "task $task not kept in front: $it") }
    }

    /**
     * [release] from any thread: the display's own thread does the work, [then] after it. [front] is
     * for a tap on the island, the one release that is meant to bring the order page up.
     */
    fun releaseFrom(task: Int, why: String, front: Boolean = false, then: (() -> Unit)? = null) {
        if (task !in parked) {
            then?.invoke()
            return
        }
        bg.post {
            release(task, why, front)
            then?.invoke()
        }
    }

    /** [releaseAll] from any thread. */
    fun releaseAllFrom(why: String) {
        if (parked.isEmpty() && vd == null) return
        bg.post { releaseAll(why) }
    }

    /**
     * The parked page reloaded, ColorOS's `refreshData` without its JS: where ColorOS runs
     * `wx.redirectTo(<the page>)` inside 微信, the hidden display is switched off for [OFF_HOLD] and
     * on again. The mini program goes through what a screen-off does to it - onHide, then onShow
     * - and an order page asks for its order again on onShow. Nothing is sent into 微信 and nothing
     * is tapped. [then] runs on this object's thread once the display is back on.
     *
     * Off is VirtualDisplay.setDisplayState(false) where the platform has it (hidden, Android 14+),
     * and a detached surface otherwise, which is the older way a virtual display goes dark.
     */
    fun cycleFrom(then: () -> Unit) {
        bg.post {
            val d = vd
            if (d == null || parked.isEmpty()) {
                then()
                return@post
            }
            val state = runCatching {
                VirtualDisplay::class.java.getMethod("setDisplayState", Boolean::class.javaPrimitiveType)
            }.getOrNull()
            fun power(on: Boolean) = runCatching {
                if (state != null) state.invoke(d, on) else d.surface = if (on) reader?.surface else null
            }.onFailure { Xp.log(TAG + "display ${if (on) "on" else "off"} failed: $it") }
            power(false)
            bg.postDelayed({
                if (vd === d) power(true)
                then()
            }, OFF_HOLD)
        }
    }

    /** How long the hidden display stays off in [cycleFrom]: long enough for the page to be stopped. */
    private const val OFF_HOLD = 800L

    /** [park] from any thread, once the order on screen is one worth waiting on. */
    fun parkFrom(task: Int) {
        if (task < 0 || task in parked) return
        bg.post { park(task) }
    }

    /** Until when a parked task coming to the front is our own doing - the park itself (uptime). */
    @Volatile private var ownMoveUntil = 0L
    private const val OWN_MOVE = 2_000L
    private var watching = false
    /** The ITaskStackListener registered in system_server, for [retire] to take back out. */
    private var watcher: Any? = null

    /**
     * A parked task brought to the front by its own app - the mini program opened from 微信's list
     * while its page sits on the hidden display, where it would come up out of sight. That, and only
     * that, is when the page goes back to the phone's display, on top: it is what the user just
     * asked for, so landing on top is right and there is no second move to make.
     *
     * It used to go back the moment 微信 came to the front at all, and with moveRootTaskToDisplay
     * landing on top that meant the order page over 微信, then 微信 put back over it - a flash of the
     * page each time 微信 was opened, and once, tapping 微信's icon on the desktop, the launcher pulled
     * back in front halfway through 微信's own start because 微信 was not in front yet (2026-10-13,
     * 18:50:00 and 18:52:04). ColorOS does not move its task for 微信 coming up either.
     *
     * Heard straight from the system, as an ITaskStackListener of our own - a bare Binder, the way
     * the assist receiver is ([PickupAssist]), its transaction codes read off the platform's own Stub.
     * Two of its calls mean "this task was brought up":
     *  - onActivityRestartAttempt: an activity start that landed on a task already there. Opening the
     *    mini program from 微信's list is one, and it is the *only* word of it: the task is already
     *    on top of the hidden display, so nothing moves to the front (2026-10-13 19:02:28 - opened,
     *    resumed on the hidden display, and not a sound from onTaskMovedToFront).
     *  - onTaskMovedToFront: anything else that moves it, the park itself included, which is not
     *    taken for the user's ([ownMoveUntil]).
     * SystemUI's own TaskStackChangeListeners would have done for the second, but R8 has cut its
     * interface down on this build and the first is not on it.
     */
    private fun watch() {
        if (watching) return
        watching = true
        runCatching {
            val stub = Class.forName("android.app.ITaskStackListener\$Stub")
            fun code(name: String): Int = runCatching {
                stub.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }.getInt(null)
            }.getOrDefault(-1)
            val restart = code("onActivityRestartAttempt")
            val front = code("onTaskMovedToFront")
            if (restart < 0 && front < 0) error("no transaction codes on ITaskStackListener.Stub")
            val listener = object : android.os.Binder() {
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                        return super.onTransact(code, data, reply, flags)
                    }
                    // Every call on this interface is oneway; the ones not wanted are only taken.
                    // So is all of it once this generation has retired: the system keeps the
                    // listener if the retirement could not unregister it.
                    if (code != restart && code != front || !Gen.live()) return true
                    val info = runCatching {
                        data.enforceInterface("android.app.ITaskStackListener")
                        if (data.readInt() != 0) android.app.ActivityManager.RunningTaskInfo.CREATOR.createFromParcel(data) else null
                    }.getOrNull() ?: return true
                    val task = info.taskId
                    val why = if (code == restart) "opened by its app" else "moved to the front"
                    if (task in parked) bg.post { broughtUp(task, why) }
                    return true
                }
            }
            listener.attachInterface(null, "android.app.ITaskStackListener")
            val proxy = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, listener)
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            atm.javaClass.methods.first { it.name == "registerTaskStackListener" }.invoke(atm, proxy)
            watcher = proxy
            Xp.log(TAG + "watching for a parked task opened by its app (codes $restart/$front)")
        }.onFailure {
            watching = false
            Xp.log(TAG + "not watching for a parked task opened by its app: $it")
        }
    }

    /**
     * [task], parked, was brought up - by its app, unless it is the move of our own just made. The
     * system may already have moved it to the phone's display itself (19:02:43: 微信's second open
     * landed it there, and moving it again threw "to its current taskDisplayArea"); then it is only
     * let go of here.
     */
    private fun broughtUp(task: Int, why: String) {
        if (task !in parked || SystemClock.uptimeMillis() < ownMoveUntil) return
        Xp.log(TAG + "task $task $why while parked")
        if (displayOf(task) == 0) {
            parked = parked - task
            Xp.log(TAG + "released $task ($why): already on display 0")
            if (parked.isEmpty()) letGoSoon()
            return
        }
        release(task, why, front = true)
    }

    /** The display [task] is on, or -1. */
    private fun displayOf(task: Int): Int = runCatching {
        val ctx = Main.appContext() ?: return@runCatching -1
        val displayId = android.app.TaskInfo::class.java.getField("displayId")
        @Suppress("DEPRECATION")
        ctx.getSystemService(android.app.ActivityManager::class.java).getRunningTasks(64)
            .firstOrNull { it.taskId == task }?.let { displayId.getInt(it) } ?: -1
    }.getOrDefault(-1)

    /**
     * [task] onto the hidden display and left there, beside whatever is already on it: the reads are
     * the observer's (PickupObserver), and [release] - or the display going away with this process -
     * is what ends it. Returns what happened, for the probe's reply.
     */
    private fun park(task: Int): String {
        if (task in parked) return "already parked: $task"
        bg.removeCallbacksAndMessages(LET_GO)
        val made = ensure()
        if (made.startsWith("no display")) return made
        val id = vd?.display?.displayId ?: return "no display"
        watch()
        ownMoveUntil = SystemClock.uptimeMillis() + OWN_MOVE
        val moved = move(task, id)
        ownMoveUntil = SystemClock.uptimeMillis() + OWN_MOVE
        if (moved.startsWith("move failed")) {
            if (parked.isEmpty()) letGo()
            return "$made, $moved"
        }
        parked = parked + task
        Xp.log(TAG + "parked task $task on $id (${parked.size} there)")
        return "$made, $moved"
    }

    /**
     * Retirement (main thread, SystemUiRetire through PickupIsland). The busy check refuses
     * while a task is parked, so normally only an idle display is left - one waiting out
     * [LET_GO_AFTER]. Both it and the task-stack listener are let go here: the listener sits in
     * system_server and holds this generation's loader, and with it the display, for as long as
     * the process runs. Not through [bg]: the sweep quits that thread right after.
     */
    fun retire() {
        val proxy = watcher
        watcher = null
        watching = false
        if (proxy != null) {
            runCatching {
                val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                atm.javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(atm, proxy)
            }.onFailure { Xp.log(TAG + "task-stack listener not removed: $it") }
        }
        // Only reached past a busy check that failed to see one; back to the phone's display.
        for (task in parked) move(task, 0)
        parked = emptySet()
        letGo()
    }

    fun isParked(task: Int): Boolean = task >= 0 && task in parked

    fun anyParked(): Boolean = parked.isNotEmpty()

    fun state(): String = "parked=${parked.ifEmpty { null } ?: "-"} vd=${vd?.display?.displayId ?: -1}" +
        if (until > 0) " probe: reads=$reads at=${(SystemClock.uptimeMillis() - probeSince) / 1000}s" +
            " left=${((until - SystemClock.uptimeMillis()).coerceAtLeast(0)) / 1000}s" +
            (if (seen.isEmpty()) "" else " seen=$seen") else ""

    // ------------------------------------------------------------------ the probe's experiment

    /**
     * `do vdtest`: ColorOS's second stage by hand, on the module's own account. [task] is parked on
     * the hidden display and its page read every [everyMs] for [minutes], each read logged with what
     * it found, and then the task is put back. The island's own state is not touched.
     */
    fun experiment(task: Int, minutes: Int, everyMs: Long = 0): String {
        val made = park(task)
        if (made.startsWith("no display") || made.startsWith("move failed") || made.startsWith("already")) {
            return made
        }
        probeSince = SystemClock.uptimeMillis()
        until = probeSince + minutes * 60_000L
        every = if (everyMs > 0) everyMs else EVERY
        reads = 0
        seen.clear()
        Xp.log(TAG + "reading task $task every ${every / 1000}s for $minutes min")
        bg.post { read(task) }
        return "$made, ${minutes} min"
    }

    private fun read(task: Int) {
        if (task !in parked) return
        if (SystemClock.uptimeMillis() >= until) {
            Xp.log(TAG + "parked $task for its ${(until - probeSince) / 60_000} min: $reads reads, $seen")
            until = 0L
            release(task, "the probe's time is up")
            return
        }
        val t0 = SystemClock.uptimeMillis()
        PickupCodeIsland.readOnce(task) { r, texts ->
            val ms = SystemClock.uptimeMillis() - t0
            reads++
            r?.status?.let { seen += it }
            Xp.log(TAG + "parked ${(SystemClock.uptimeMillis() - probeSince) / 1000}s: " +
                (if (r == null) "no code in $texts texts" else "${r.code} ${r.label} / ${r.status} / ${r.store}") +
                " (${ms}ms)")
            if (r != null && FINISHED.any { r.status?.contains(it) == true }) {
                until = 0L
                release(task, "the order is over (${r.status})")
                return@readOnce
            }
            bg.postDelayed({ read(task) }, every)
        }
    }

    /** The probe's reply: the display and the park, with the states the parked reads saw. */
    fun describe(): String = "park: " + state()

    /** An order that is over: nothing left to wait for. (已完成 is the ready state at 蜜雪冰城.) */
    private val FINISHED = setOf("已完成", "已取消", "已退款")
}
