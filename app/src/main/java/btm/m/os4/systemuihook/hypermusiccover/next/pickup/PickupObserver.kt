// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * An order waited on while the user is elsewhere - ColorOS's observe task for a pickup code
 * (Gleaner's `PickupCodeExecutor`, 取餐码-OPPO逆向/02), round for round:
 *
 *   gates     the task ends when it has run [DURATION] or the phone is at [MAX_TEMP]; a round is
 *             skipped while the phone is locked, offline, filming, or low on battery with the saver
 *             on and no charger (ColorOS's `ce.a.e()` gates, the ones this process can see).
 *   onNavi    the task is on the hidden display (PickupPark) - ColorOS moves it there for the round;
 *             with the user on the page (ColorOS's FRONT_MAIN_SCREEN) it is not moved, and the round
 *             is the tracker's read in front ([saw]).
 *   lifetime  the task is made on recognition and outlives the user going in and out: a tap on the
 *             island hands the page back ([handBack]) without ending it, and the user settling
 *             elsewhere brings its round forward ([kickAway]) - ColorOS's PickupCodeTask callbacks.
 *   onCollect the page is reloaded and read [REFRESH_WAIT] later.
 *   onExtract the front page's code, by the same recognizer the island uses.
 *   onDispatch the order's state, only ever forward; [EMPTY_MAX] rounds in a row without the order
 *             on the front page end the task (ColorOS's reason 3), and so does the order being over.
 *   interval  ColorOS's `c.f()`: ready → ready; the first round → first-after; making → making,
 *             anything else → ordered; a low battery stretches either to at least the low-battery gap.
 *
 * Two of ColorOS's moves are JS that it runs in 微信's WebView through its own framework, and are not
 * done here: `wx.reLaunch` back to the page the task was made on, and `wx.redirectTo` to reload it.
 * The first is why a task is only made from a page that shows the order ([PickupCodeIsland] checks
 * that before calling [start]) and why a page that stops showing it ends the task rather than being
 * sent back. The second is [PickupPark.cycleFrom], which puts the page through a hide and a show.
 *
 * And one is not done the same way: ColorOS borrows the task onto its display for each round and puts
 * it back at the *bottom* of the phone's display after (`moveTaskToDisplay(task, 0, false)`). The
 * platform has no bottom-of-the-display move - moveRootTaskToDisplay always lands on top - so the task
 * stays on the hidden display between rounds, and goes back when the task ends, when the island is
 * tapped, or when its app opens it from the hidden display (PickupPark.watch) - not merely for its app
 * being in front, which flashed the page up over 微信 each time.
 *
 * Up to three orders at once, as ColorOS's `max_task_count`, each on its own rounds; they share the
 * one hidden display, and so a reload of one is a reload of all.
 *
 * All on [PickupCodeIsland.bg].
 */
internal object PickupObserver {

    /** Retirement: the camera availability callback, and the latch that keeps it one-shot. */
    @JvmStatic
    fun retire() {
        PickupCamera.retire()
    }

    /**
     * Whether an order is being waited on, for the hot reload's busy check (main thread). The
     * map is [bg]'s; its size read from another thread can only be a moment stale, which the
     * check tolerates - a task that ends in that moment just refuses one reload.
     */
    fun observing(): Boolean = tasks.isNotEmpty()


    private const val TAG = "MCPickupObs: "

    /** ColorOS's `execution_duration` for a pickup code (observe-config.json). */
    private const val DURATION = 30 * 60_000L
    /** ColorOS's `max_execute_temperature` (observe-config.json): at or over it, the task ends. */
    private const val MAX_TEMP = 43f
    /** After the task is moved: ColorOS's `E()` waits this long before the round goes on. */
    private const val NAVI_WAIT = 1_000L
    /** After the reload: ColorOS's `C()` waits this long before the page is read. */
    private const val REFRESH_WAIT = 6_000L
    /** Rounds in a row without the order on the page before the task ends (ColorOS's `resultEmptyCount`). */
    private const val EMPTY_MAX = 5
    /** Orders waited on at once: ColorOS's `max_task_count` (observe-config.json). */
    private const val MAX_TASKS = 3
    /** Tries at putting a task back on the hidden display before it is given up. */
    private const val PARK_TRIES = 3

    private val bg get() = PickupCodeIsland.bg

    /** One order being waited on - ColorOS's task, and the executor's fields for it. */
    private class Obs(val task: Int, var page: PickupIsland.Page, var code: String, var status: String?) {
        val startedAt = SystemClock.uptimeMillis()
        /** Rounds finished, ColorOS's `completedObserveCount`: the first is followed by the first-after gap. */
        var rounds = 0
        var empty = 0
        var parkTries = 0
        var last = ""
        /** A round is between its move and its read: not to be started over by [kickAway]. */
        var busy = false
        /** What this order's rounds are queued under, so that ending it takes back its own and no others. */
        val token = Any()
    }

    /** The orders waited on, by task, in the order they came. */
    private val tasks = LinkedHashMap<Int, Obs>()

    fun observing(t: Int) = t >= 0 && tasks.containsKey(t)

    /**
     * The order [code] on [p]'s task waited on, its state as of now [now]. Made as soon as the order
     * page shows a code, with the user still on it - ColorOS's task is made on recognition, not on
     * the user leaving - and its first round runs at once, as ColorOS schedules its first at 0. Each
     * round decides for itself whether the page is moved: not while the user is on it ([round]).
     *
     * An order already waited on takes the newer page and, if the page now shows another order, that
     * one's code; a fourth is not taken.
     */
    fun start(p: PickupIsland.Page, code: String, now: String?) {
        tasks[p.task]?.let { o ->
            o.page = p
            if (code.isNotEmpty() && code != o.code) {
                Xp.log(TAG + "task ${p.task}: now waiting on $code ($now), was ${o.code}")
                o.code = code
                o.status = now
                o.empty = 0
            }
            return
        }
        if (tasks.size >= MAX_TASKS) {
            Xp.log(TAG + "not observing task ${p.task}: $MAX_TASKS orders are waited on already")
            return
        }
        val o = Obs(p.task, p, code, now)
        tasks[p.task] = o
        Xp.log(TAG + "observing task ${p.task} for ${code.ifEmpty { "a code to come" }} ($now), ${tasks.size} in all")
        bg.post { round(o) }
    }

    /**
     * The island tapped on [task]: its page goes back to the user, over what they are in, and [then]
     * runs after. The wait goes on - ColorOS's task outlives the user looking in, and reads the page
     * in place while they do; the next round once they are out of it again moves it back.
     */
    fun handBack(task: Int, why: String, then: () -> Unit) {
        tasks[task]?.let { Xp.log(TAG + "task $task handed back: $why (waited on still)") }
        PickupPark.releaseFrom(task, why, front = true, then)
    }

    /**
     * The user has settled somewhere that is not these orders' pages: each one off the display and
     * between rounds has its round now, rather than at the end of the gap it took while the user was
     * on it - ColorOS's applet-to-background callback. A round under way is left to finish.
     */
    fun kickAway() {
        tasks.values.filter { !it.busy && !PickupPark.isParked(it.task) }.forEach { o ->
            if (PickupCodeIsland.whereIs(o.task, o.page.host) != PickupCodeIsland.Where.AWAY) return@forEach
            bg.removeCallbacksAndMessages(o.token)
            round(o)
        }
    }

    /**
     * The page read in front by the tracker, while [task] is waited on: ColorOS's front-screen round,
     * which reads in place and counts as a success. Only forward, as the rounds' own reads.
     */
    fun saw(task: Int, r: PickupParse.Result) {
        val o = tasks[task] ?: return
        if (r.code != o.code) return
        o.empty = 0
        if (rank(r.status) >= rank(o.status)) o.status = r.status
    }

    /**
     * The order on [task] no longer waited on, for [why]: its page goes back to the phone's display -
     * over what the user is in only when [front] (its island was tapped) - and [then] runs after.
     * Ending one that is not waited on still releases it if it is parked.
     */
    fun end(task: Int, why: String, front: Boolean = false, then: (() -> Unit)? = null) {
        val o = tasks.remove(task)
        if (o != null) {
            Xp.log(TAG + "task $task ended: $why (${o.rounds} rounds, ${(SystemClock.uptimeMillis() - o.startedAt) / 1000}s)")
            bg.removeCallbacksAndMessages(o.token)
        }
        PickupPark.releaseFrom(task, why, front, then)
        if (o != null) PickupCodeIsland.observeEnded(task)
    }

    /** Every order let go: the switch going off. */
    fun endAll(why: String) {
        tasks.keys.toList().forEach { end(it, why) }
        PickupPark.releaseAllFrom(why)
    }

    fun describe(): String = if (tasks.isEmpty()) "obs: -" else "obs: " + tasks.values.joinToString("; ") { o ->
        "task=${o.task} code=${o.code} status=${o.status} rounds=${o.rounds} empty=${o.empty} " +
            "at=${(SystemClock.uptimeMillis() - o.startedAt) / 1000}s parked=${PickupPark.isParked(o.task)} last=${o.last}"
    }

    private fun live(o: Obs) = tasks[o.task] === o

    private fun round(o: Obs) {
        if (!live(o)) return
        val ctx = Main.appContext() ?: return next(o)
        if (SystemClock.uptimeMillis() - o.startedAt >= DURATION) return end(o.task, "${DURATION / 60_000} min")
        val temp = temperature(ctx)
        if (temp >= MAX_TEMP) return end(o.task, "too hot: $temp°C")
        // Off the display - its app came to the front, or a park did not take: back on once the user
        // is out of it, the way ColorOS moves its task over for every round.
        if (!PickupPark.isParked(o.task)) {
            when (PickupCodeIsland.whereIs(o.task, o.page.host)) {
                PickupCodeIsland.Where.GONE -> return end(o.task, "its task is gone")
                PickupCodeIsland.Where.WITH_USER -> {
                    o.last = "with the user"
                    return next(o)
                }
                PickupCodeIsland.Where.AWAY -> {
                    if (++o.parkTries > PARK_TRIES) return end(o.task, "not put back on the hidden display")
                    PickupPark.parkFrom(o.task)
                    o.busy = true
                    bg.postDelayed({ o.busy = false; round(o) }, o.token, NAVI_WAIT)
                    return
                }
            }
        }
        o.parkTries = 0
        skip(ctx)?.let { why ->
            o.last = "skipped: $why"
            return next(o)
        }
        o.busy = true
        PickupPark.cycleFrom {
            bg.postDelayed({ extract(o) }, o.token, REFRESH_WAIT)
        }
    }

    private fun extract(o: Obs) {
        if (!live(o)) return
        PickupCodeIsland.readOnce(o.task, frontOnly = true, rule = o.page.rule) { r, texts ->
            o.busy = false
            if (live(o)) dispatch(o, r, texts)
        }
    }

    private fun dispatch(o: Obs, r: PickupParse.Result?, texts: Int) {
        o.rounds++
        if (o.code.isEmpty() && r != null && r.code.isNotEmpty()) o.code = r.code
        if (r == null || r.code != o.code) {
            o.empty++
            o.last = if (r == null) "no code in $texts texts" else "another code: ${r.code}"
            if (o.empty >= EMPTY_MAX) {
                // ColorOS lets 瑞幸's order page come back empty for longer (`wx21c7506e98a2fe75`).
                if (o.page.rule?.tagAppName == "luckin") o.empty = 0
                else return end(o.task, "the order was not on the page $EMPTY_MAX times")
            }
            return next(o)
        }
        o.empty = 0
        // Forward only: a page that shows a step bar can read a step behind, and a state the order
        // has passed is not one to show again.
        val shown = if (rank(r.status) >= rank(o.status)) r.status else o.status
        o.status = shown
        o.last = "${o.code} ${shown ?: "-"}"
        PickupIsland.show(r.copy(status = shown), o.page)
        if (shown != null && shown in PickupParse.DONE) {
            // Over while the user is away: ColorOS keeps a finished card for 0s once its page is left.
            PickupIsland.takeDown(o.task, "the order reads $shown")
            return end(o.task, "the order reads $shown")
        }
        next(o)
    }

    private fun next(o: Obs) {
        if (!live(o)) return
        bg.postDelayed({ round(o) }, o.token, interval(o))
    }

    /** ColorOS's `c.f(completedObserveCount, orderStatus, isLowBattery, resolved)`. */
    private fun interval(o: Obs): Long {
        val cfg = PickupObserve.intervals
        if (PickupParse.isReady(o.status)) return cfg.ready
        if (o.rounds <= 1) return cfg.firstAfter
        val gap = if (PickupParse.isMaking(o.status)) cfg.making else cfg.ordered
        val ctx = Main.appContext() ?: return gap
        return if (lowBattery(ctx, cfg.lowBatteryPercent)) maxOf(gap, cfg.lowBattery) else gap
    }

    private fun rank(s: String?): Int = when {
        s == null -> 0
        s in PickupParse.DONE -> 4
        s in PickupParse.READY -> 3
        s in PickupParse.MAKING -> 2
        else -> 1
    }

    // ------------------------------------------------------------------ gates

    /** Why this round is skipped, or null. ColorOS's order: network, lock, camera, battery. */
    // Runs in SystemUI, which holds ACCESS_NETWORK_STATE; this APK's manifest is not the one asked.
    @android.annotation.SuppressLint("MissingPermission")
    private fun skip(ctx: Context): String? {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) return "offline"
        // Locked, and only locked: a screen that is merely off is read on, as ColorOS does.
        if (ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return "locked"
        if (PickupCamera.inUse(ctx)) return "camera in use"
        if (lowBattery(ctx, PickupObserve.intervals.skipPercent)) return "battery"
        // Game Turbo has a game in front, and the super power saver: MIUI's own settings for them.
        if (setting(ctx, secure = true, "gb_boosting") == 1) return "game"
        if (setting(ctx, secure = false, "power_supersave_mode_open") == 1) return "super saver"
        return null
    }

    private fun setting(ctx: Context, secure: Boolean, key: String): Int = runCatching {
        if (secure) android.provider.Settings.Secure.getInt(ctx.contentResolver, key, 0)
        else android.provider.Settings.System.getInt(ctx.contentResolver, key, 0)
    }.getOrDefault(0)

    /**
     * ColorOS's `StatusUtils.i(context, minBatteryLevel)`: under [percent], not charging, *and* the
     * battery saver on - all three, or it is not low.
     */
    private fun lowBattery(ctx: Context, percent: Int): Boolean {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java) ?: return false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val saver = ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        return level in 0 until percent && !bm.isCharging && saver
    }

    /**
     * The battery's temperature, in °C. ColorOS reads its own thermal service
     * (`OplusHoraeThermalHelper.getCurrentThermal`), which is not on this phone; the battery's is the
     * one every Android phone reports. NaN when it cannot be read, which, as there, never trips the gate.
     */
    private fun temperature(ctx: Context): Float = runCatching {
        val i = Gen.stickyReceiver(ctx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }.getOrDefault(Float.NaN)
}

/**
 * Whether a camera is open - ColorOS's "video call" gate (`ee.c.b()`, failure code -13), which it keeps
 * with an availability callback. Registered on first use and kept: the callback is how the platform
 * says which cameras are taken, and it says it once for each camera as soon as it is registered.
 */
private object PickupCamera {
    private val taken = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var watching = false
    private var manager: android.hardware.camera2.CameraManager? = null
    private var callback: android.hardware.camera2.CameraManager.AvailabilityCallback? = null

    fun inUse(ctx: Context): Boolean {
        if (!watching) {
            watching = true
            runCatching {
                val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
                if (cm != null) {
                    val cb = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                        override fun onCameraAvailable(id: String) { taken.remove(id) }
                        override fun onCameraUnavailable(id: String) { taken.add(id) }
                    }
                    manager = cm
                    callback = cb
                    Gen.cameraCallback(cm, cb, PickupCodeIsland.bg)
                }
            }.onFailure {
                watching = false
                Xp.log("MCPickupObs: camera not watched: $it")
            }
        }
        return taken.isNotEmpty()
    }

    /** Retirement: the callback is a host registration; Gen takes it back. */
    fun retire() {
        val cm = manager
        val cb = callback
        manager = null
        callback = null
        if (cm != null && cb != null) runCatching { Gen.uncameraCallback(cm, cb) }
        watching = false
        taken.clear()
    }
}
