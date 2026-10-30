// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import btm.m.os4.systemuihook.hypermusiccover.next.ProbeGuard
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高德's half of the lock screen's bus and subway card, in 高德's process: what ColorOS 17's
 * SceneService does with 高德's trip, done with what 高德 sends on HyperOS.
 *
 * On ColorOS 高德's script hands SceneService a GaoDePtIntentEntity with the milestone already in
 * it (`status` 1-9), through the IntelligentIntent provider. On HyperOS that script never opens
 * the channel (no `bizBegin(10200)`; checked on real rides), so the trip is read off the two
 * channels it does open, through NativesModuleWearable:
 *
 *   bizType 103  the trip card, every 1-3 s while a navigation is under way; its begin and end are
 *                the navigation's own (the route page never opens it; 「开始导航」 does).
 *   bizType 113  the plan (`type 24`, at the navigation's start) and the live data (`type 25`).
 *
 * AmapTransitEntity turns them into the entity; this decides what ColorOS gets from 高德 and 高德
 * here never says - which milestone it is - and runs SceneService's own rules on top:
 *
 *   - the milestone, read off which leg 高德's card is on and the stops left of it
 *     (AmapTransitMilestones), with GaoDePtRideCodeDeferBindManager's five minutes for a subway's
 *     到站 before the walk after it - cut short by the way out of the station: a ride code opened
 *     or a transit card's fare taken (RideCodeExit, from SystemUI), or 高德's walking navigation.
 *   - 到达起始站附近 (status 1), which 高德 decides itself for ColorOS: a walk within 200 m of the
 *     station of the ride after it (near()).
 *   - the walks: the silent card, with its 「步行导航」 button (startWalk), and 高德's walking
 *     navigation of the walk (channel 101) as the same card while it runs (walkNavi).
 *   - GaoDePtFinalDestCardManager: a trip whose last leg is a ride gets its end card five minutes
 *     (subway) or 35 s (bus) after that ride's 到站.
 *   - the end card (ya.a) the moment 高德 says the trip has arrived, for 30 s; nothing after it.
 *   - GaoDePtNaviSceneRouter.h: 30 s with no word after the end card, 15 min after a 到站, 30 min
 *     otherwise, and the card is taken down.
 *   - GaoDePtDismissHandler: the navigation ending in 高德 (its `bizEnd(103)`) takes it all down -
 *     unless the trip has arrived or is on its last walk (`shouldIgnoreDeleteIntent`).
 *
 * The entity goes to SystemUI (`op transit`, AmapTransitScene draws the page) and to the island
 * (AmapTransitIsland), which both build their card with AmapTransitCard.
 *
 * All of it runs on one thread of its own; the hooks only hand the payloads over.
 */
internal object AmapTransitShare {

    private const val TAG = "MCAmap: transit: "
    private const val SYSUI = "com.android.systemui"
    private const val SYSUI_PROBE = "btm.m.os4.systemuihook.hypermusiccover.next.PROBE"
    private const val WEARABLE = "com.amap.bundle.wearable.ajx.NativesModuleWearable"

    /** The trip card's channel (third_sdk_oppo_aod), and its begin and end are the navigation's. */
    private const val RIDE_BIZ = 103
    /** The plan and the live data's channel (amap_glass). */
    private const val LIVE_BIZ = 113
    private const val PLAN_TYPE = 24
    private const val RIDE_TYPE = 25
    /**
     * 高德's walking navigation (its walking island's own feed): inside a trip it is ColorOS's
     * GaoDeWalkingAndCyclingIntentEntity - `trigger_source` 1, `isFromBus` - status 1 while it
     * runs, 0 with `isArrived` when it ends.
     */
    private const val WALK_BIZ = 101
    /** A walking navigation this long without a frame has gone. */
    private const val WALK_NAVI_STALE_MS = 60_000L
    /**
     * 到达起始站附近 (status 1): a walk into a ride's station this close to its end. 高德 decides
     * it for ColorOS and never says how; this is ours. It lets go again past [FAR_M].
     */
    private const val NEAR_M = 200
    private const val FAR_M = 300

    /** GaoDePtNaviSceneRouter.h: how long a card lasts without another word. */
    private const val SILENCE_MS = 30 * 60_000L
    private const val ARRIVAL_SILENCE_MS = 15 * 60_000L
    private const val FINAL_MS = 30_000L
    /** GaoDePtFinalDestCardManager.i: a last ride's 到站 to the trip's end card. */
    private const val SUBWAY_LAST_MS = 300_000L
    private const val BUS_LAST_MS = 35_000L
    /** An unchanged state is told SystemUI again this often (a restarted SystemUI asks anyway). */
    private const val KEEPALIVE_MS = 60_000L

    /** 高德's own walking island, posted every second while its walking navigation runs. */
    private const val WALK_ISLAND_STALE_MS = 5_000L

    private val worker: Handler = Handler(Gen.handlerThread("mc-transit").looper)

    /** What a line on rails has in its name, where its type does not say so (AmapTransitScene). */
    private val RAIL = arrayOf("号线", "地铁", "城际", "轨道", "铁路", "有轨", "APM", "轻轨", "磁浮", "云巴")

    // ------------------------------------------------------------------ what 高德 has sent

    /** Whether 高德's trip card channel is open: a navigation is under way. */
    @Volatile private var navigating = false
    /** The last plans 高德 sent, newest first; the one that fits the trip's capsules is used. */
    private val plans = ArrayDeque<JSONObject>()
    /** The trip's legs, one capsule each (the latest card's `planData`). */
    @Volatile private var capsules: JSONArray? = null
    /** The latest card with words, and the latest one for each leg. */
    private var card: JSONObject? = null
    private val cardFor = HashMap<Int, JSONObject>()
    private var live: JSONObject? = null

    // ------------------------------------------------------------------ the trip's state

    private var tripId = ""
    /** Which leg, and which milestone of it. */
    private val milestones = AmapTransitMilestones()
    /** The exit 高德 named for the stop each ride ends at. */
    private val exits = HashMap<Int, String>()
    private var finalShown = false
    private var finalAt = 0L
    /** The end card waits on a subway's way out too (GaoDePtFinalDestCardManager's waitRideCode). */
    private var finalOnExit = false

    @Volatile private var walkIslandAt = 0L
    /** 高德's walking navigation of the current walk, its last frame, and when that came. */
    private var walkMsg: JSONObject? = null
    private var walkMsgAt = 0L
    /** The walk (its capsule) close enough to the station after it for 到达起始站附近, or -1. */
    private var nearLeg = -1
    /** What the ride last said of its stops left, so each change is logged once. */
    private var lastHeard = ""
    /**
     * The ride whose card's count of stops left is believed, and the count its first card came
     * with. A ride's card can come up still holding the leg before's count - a walk's card carries
     * a `remainStations` too, 1 on 10-05 - and the last ride's next stop was its end until 高德 said
     * it again (user, 10-27). Believed once 高德's live data is on the ride or the card's count moves.
     */
    private var countLeg = -1
    private var firstCountLeg = -1
    private var firstCount = -1

    /** A trip being played from its own plan (`transit sim`): 高德's own payloads wait meanwhile. */
    @Volatile private var simulating = false
    /** The last trip's capsules, kept past its end for `transit sim`. */
    private var lastCaps: JSONArray? = null

    /**
     * The app's 「高德公交地铁」 switch, as SystemUI holds it: off, the trip is still read - the
     * ride-code island's stops come out of it (tellMetro) - but neither the island nor the lock
     * screen page is told. SystemUI says it when 高德 starts (AmapImmerse asks), when SystemUI
     * starts, and when it changes ([setEnabled]); on until then, the way it ships.
     */
    @Volatile private var enabled = true

    /** What SystemUI was last told, and when. */
    @Volatile private var lastSent: String? = null
    private var lastSentAt = 0L
    @Volatile private var lastStatus = ""
    @Volatile private var lastKind = ""

    private val dismiss = Runnable { dismissAll("silence") }
    private val tick = Runnable { update() }
    private val finalCard = Runnable { showFinal("last ride") }

    // ------------------------------------------------------------------ hooks

    fun handle(cl: ClassLoader) {
        AmapTransitIsland.handle()
        // Without a connected device on 113 高德 sends no plan and no live data (AmapOppoBridge).
        AmapOppoBridge.handle(cl)
        try {
            val module = Xp.findClass(WEARABLE, cl)
            for (name in arrayOf("bizBegin", "bizBeginWithData", "bizEnd", "sendMessage")) {
                try {
                    Xp.hookAll("amap.share." + Xp.slug(name), module, name) { chain ->
                        val a = chain.args
                        val biz = a.firstOrNull { it is Int } as Int?
                        val text = a.firstOrNull { it is String } as String?
                        // The trip's own channels only: walking navigation's 101 every second
                        // would push them out of the log.
                        if (biz == RIDE_BIZ || biz == LIVE_BIZ) Ledger.record(name, biz, text)
                        if (name == "sendMessage") {
                            if (text != null && (biz == RIDE_BIZ || biz == LIVE_BIZ)) {
                                worker.post { if (!simulating) take(biz, text) }
                            } else if (text != null && biz == WALK_BIZ) {
                                worker.post { if (!simulating) walkNavi(text) }
                            }
                        } else if (biz == RIDE_BIZ) {
                            val closed = name == "bizEnd"
                            worker.post { if (!simulating) channel(closed) }
                        }
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    Xp.log(TAG + "$name not watched: $t")
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "wearable module not watched: $t")
        }
    }

    /** 高德's own walking island (1236) was posted, or taken down. */
    fun walkIsland(posted: Boolean) {
        val was = walkIslandUp()
        walkIslandAt = if (posted) SystemClock.uptimeMillis() else 0L
        if (was != walkIslandUp()) worker.post { update() }
    }

    fun walkIslandUp(): Boolean =
        !simulating && walkIslandAt != 0L && SystemClock.uptimeMillis() - walkIslandAt < WALK_ISLAND_STALE_MS

    /** 高德's walking navigation of a walk of this trip is running (its channel 101). */
    private fun walkNaviUp(): Boolean =
        walkMsg != null && SystemClock.uptimeMillis() - walkMsgAt < WALK_NAVI_STALE_MS

    /**
     * A frame of 高德's walking navigation. One of this trip's walks only - `trigger_source` 1 /
     * `isFromBus`, ColorOS's test for the public transport card - and only while a trip is up.
     * Running, it is the walking card (GaoDePtWalkRideHandler.d); ended short of the walk's end,
     * the silent card is back (keepSilentCardAfterInAppExit); ended at it, the trip goes on - ColorOS
     * tells 高德 to resume its transit navigation, which on HyperOS never stopped (10-05: 103
     * kept coming all through the walk).
     */
    private fun walkNavi(text: String) {
        val msg = try {
            JSONObject(text).optJSONObject("message")
        } catch (t: Throwable) {
            null
        } ?: return
        if (!msg.optBoolean("isFromBus", false) && msg.optInt("trigger_source", 0) != 1) return
        if (capsules == null) return
        if (msg.optInt("status", -1) == 0) {
            if (walkMsg == null) return
            walkMsg = null
            Xp.log(TAG + "walking navigation over (arrived=" + msg.optBoolean("isArrived", false) + ")")
        } else {
            if (walkMsg == null) Xp.log(TAG + "walking navigation under way")
            walkMsg = msg
            walkMsgAt = SystemClock.uptimeMillis()
        }
        update()
    }

    /**
     * The walking card's 「步行导航」 (onGaodePtNaviBeginNaviBtnClick): 高德's walking navigation to
     * where the current walk ends - the next ride's way in, or the trip's end.
     */
    fun startWalk() {
        worker.post {
            val caps = capsules ?: return@post
            val at = milestones.legAt
            if (at !in 0 until caps.length() || !walking(caps, at)) {
                Xp.log(TAG + "walk button, but leg $at is not a walk")
                return@post
            }
            val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
            var name = ""
            var lat: Double? = null
            var lng: Double? = null
            if (at + 1 < caps.length() && !walking(caps, at + 1)) {
                val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, at + 1))
                name = seg?.optJSONObject("on_station")?.optString("name")?.trim().orEmpty()
                val c = seg?.optJSONObject("inport")?.optJSONObject("coord")
                    ?: seg?.optJSONObject("driver_coord_list")?.optJSONObject("start")
                lat = c?.optString("lat")?.toDoubleOrNull()
                lng = (c?.optString("lon")?.ifEmpty { null } ?: c?.optString("lng"))?.toDoubleOrNull()
            } else {
                val e = plan?.optJSONObject("epoi")
                name = e?.optString("name")?.trim().orEmpty()
                val c = e?.optJSONObject("coord")
                lat = c?.optString("lat")?.toDoubleOrNull()
                lng = c?.optString("lon")?.toDoubleOrNull()
            }
            val cl = AmapImmerse.loader()
            if (lat == null || lng == null || cl == null) {
                Xp.log(TAG + "walk button: nowhere to walk to (plan=" + (plan != null) + ")")
                return@post
            }
            Xp.log(TAG + "walk to $name -> " + AmapFootNavi.start(cl, lat, lng, name))
        }
    }

    /**
     * Why 高德's process may not be hot reloaded now, or null. A trip is not handed over: its
     * state is this thread's, and the cards after a navigation is left - the end card, the last
     * walk - go away on timers the retirement's sweep would take, leaving them up for good. So a
     * trip, or anything SystemUI still shows of one, refuses; the next install after it lands.
     */
    @JvmStatic
    fun busyReason(): String? = when {
        simulating -> "a simulated trip is playing"
        navigating -> "a transit navigation is under way"
        capsules != null -> "a trip is still being followed"
        lastSent != null -> "a trip card is still up"
        else -> null
    }

    /** Runs [r] on the trip's own thread. */
    fun post(r: Runnable) {
        worker.post(r)
    }

    // ------------------------------------------------------------------ the channels

    /** One payload, from a channel or from the probe. */
    private fun take(biz: Int, text: String) {
        try {
            val root = JSONObject(text)
            if (biz == RIDE_BIZ) {
                val data = root.optJSONObject("cardData") ?: return
                val caps = data.optJSONArray("planData")
                if (caps != null && caps.length() > 0) {
                    capsules = caps
                    lastCaps = caps
                }
                // The card a navigation starts with: the capsules and nothing else.
                if (data.optString("title").trim().isEmpty()) return
                card = data
                index(data)?.let { cardFor[it] = data }
            } else {
                val datas = array(root.opt("datas")) ?: return
                for (i in 0 until datas.length()) {
                    val e = datas.optJSONObject(i) ?: continue
                    val d = e.optJSONObject("data") ?: continue
                    when (e.optInt("type", -1)) {
                        PLAN_TYPE -> {
                            plans.addFirst(d)
                            while (plans.size > 4) plans.removeLast()
                        }
                        RIDE_TYPE -> live = d
                    }
                }
            }
            update()
        } catch (t: Throwable) {
            Xp.w(TAG + "payload failed: $t " + t.stackTrace.take(3).joinToString(" | "))
        }
    }

    /**
     * 高德's trip card channel opening or closing: a navigation starting, or being left. A channel
     * already open is left alone - the script opens its channels again when it rebuilds them.
     */
    private fun channel(closed: Boolean) {
        if (!closed) {
            if (navigating) return
            navigating = true
            newNavi()
            return
        }
        navigating = false
        if (lastSent == null && capsules == null) return
        // GaoDePtDismissHandler.b: 高德 deleting the trip is not the end while the trip has arrived
        // or is on its last walk - those cards end on their own.
        if (finalShown || finalPending() || onLastWalk()) {
            Xp.log(TAG + "navigation left, card kept (" + lastKind + ")")
            return
        }
        dismissAll("navigation left")
    }

    /** A navigation has started: whatever the last one left is let go. */
    private fun newNavi() {
        if (lastSent != null) dismissAll("new navigation")
        reset()
        tripId = "gaode-pt-" + System.currentTimeMillis()
        Xp.log(TAG + "navigation started")
    }

    private fun reset() {
        worker.removeCallbacks(dismiss)
        worker.removeCallbacks(tick)
        worker.removeCallbacks(finalCard)
        capsules = null
        card = null
        cardFor.clear()
        live = null
        milestones.reset()
        exits.clear()
        finalShown = false
        finalAt = 0L
        finalOnExit = false
        walkMsg = null
        nearLeg = -1
        lastHeard = ""
        countLeg = -1
        firstCountLeg = -1
        firstCount = -1
    }

    // ------------------------------------------------------------------ the trip

    /**
     * What the trip is now, told on. Called for every payload and whenever something held runs
     * out; works out the leg, its milestone and what the island and the page get.
     */
    private fun update() {
        worker.removeCallbacks(tick)
        if (finalShown) return // GaoDePt_Helper: after the end card, nothing more is taken
        val caps = capsules ?: return
        val c = card ?: return
        val now = SystemClock.uptimeMillis()
        val title = c.optString("title").trim()
        if (c.optBoolean("arrived", false) || title.startsWith("已到达")) {
            showFinal("arrived")
            return
        }
        val n = caps.length()
        val at = index(c) ?: return
        if (at !in 0 until n) return
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }

        milestones.moveTo(at, n, now, { walking(caps, it) }, { subway(caps, it, plan) })
        // A walking navigation starting ends a subway's 到站 as 高德's walking island does
        // (GaoDePtRideCodeDeferBindManager.d "walk_ride_intent_share").
        val walkOn = walkNaviUp()
        val held = milestones.held(now, walkIslandUp() || walkOn)
        var shown = held?.leg ?: at
        var legCard = cardFor[shown] ?: c
        val status: String
        var info: AmapTransitEntity.Live? = null
        var wake = 0L
        var walkNavi: JSONObject? = null
        if (held != null) {
            status = held.status
            info = AmapTransitEntity.Live(0, 1.0, "", JSONArray())
            wake = held.until
        } else if (walking(caps, at) && plan != null && near(caps, at)) {
            // 到达起始站附近: the ride after the walk, its stops all ahead, on ColorOS's waiting card.
            shown = at + 1
            legCard = c
            val seg = plan.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, shown))
            val total = (seg?.optJSONArray("via_st_list")?.length() ?: -1) + 1
            info = AmapTransitEntity.Live(total.coerceAtLeast(0), -1.0, "",
                arrivals(seg, !subway(caps, shown, plan)))
            status = AmapTransitCard.ARRIVE_ORIGIN_NEARBY
        } else if (walking(caps, at)) {
            status = ""
            if (walkOn) walkNavi = walkMsg
        } else {
            info = ride(at, caps, plan, legCard, now)
            val group = live?.optJSONObject("locationData")?.optInt("groupIndex", -1) ?: -1
            val tip = if (group != at) -1
                else live?.optJSONObject("arriveRemind")?.optInt("tipType", -1) ?: -1
            // tipType 48: 高德 counting the leg as done (seen the moment a ride's card gave way).
            val step = milestones.rideStatus(info.remain, at == n - 1, tip == 48, plan != null, now)
            status = step.status
            wake = step.wakeAt
            // The last ride's end card was set going, and the ride is plainly still under way: two
            // stops and more left. A wrong count had it read as over, so the end card is let go.
            if (finalPending() && at == n - 1 && info.remain >= 2 &&
                status != AmapTransitCard.ARRIVE_LINE_DESTINATION) {
                worker.removeCallbacks(finalCard)
                finalAt = 0L
                finalOnExit = false
                Xp.log(TAG + "end card let go: ride $at has ${info.remain} stops left")
            }
        }
        if (wake > now) worker.postDelayed(tick, wake - now)
        // Near the station the card is still the walk's: its 「(E口)」 is the way in, not the ride's exit.
        val nearby = status == AmapTransitCard.ARRIVE_ORIGIN_NEARBY
        val exit = if (nearby) "" else AmapTransitEntity.exit(legCard)
            .also { if (it.isNotEmpty()) exits[shown] = it }.ifEmpty { exits[shown].orEmpty() }
        val entity = AmapTransitEntity.build(plan, caps, legCard, shown, status, info,
            if (plan == null) AmapTransitEntity.Fallback(ArrayList(milestones.seen), milestones.boardRemain)
            else null, exit, if (nearby) "" else AmapTransitEntity.sentence(legCard), tripId, walkNavi)
        // The last ride's 到站, with nothing after it: the end card follows (FinalDestCardManager).
        if (status == AmapTransitCard.ARRIVE_LINE_DESTINATION && shown == n - 1 && finalAt == 0L) {
            finalOnExit = subway(caps, shown, plan)
            val wait = if (finalOnExit) SUBWAY_LAST_MS else BUS_LAST_MS
            finalAt = now + wait
            worker.postDelayed(finalCard, wait)
        }
        tell(entity.toString(), status)
        silence(status)
    }

    /**
     * Whether walk [at] is close enough to the station of the ride after it for 到达起始站附近:
     * within [NEAR_M] of the walk's end by 高德's live data (`groupRemainDistance` of its own
     * leg, which counts as the capsules do), and so until it is past [FAR_M] again. Every walk
     * into a ride, the first and the changes. A walk whose distance does not move - 10-05's first
     * one stood at 745 m under 「信号弱」 - never gets there and goes straight to 候车, as before.
     */
    private fun near(caps: JSONArray, at: Int): Boolean {
        if (at + 1 >= caps.length() || walking(caps, at + 1)) {
            nearLeg = -1
            return false
        }
        if (nearLeg != at && nearLeg != -1) nearLeg = -1
        val loc = live?.optJSONObject("locationData")
        val left = if (loc != null && loc.optInt("groupIndex", -1) == at)
            loc.optInt("groupRemainDistance", -1) else -1
        if (left >= 0) {
            if (left <= NEAR_M && nearLeg != at) {
                nearLeg = at
                Xp.log(TAG + "near the station after walk $at (${left} m)")
            } else if (left > FAR_M && nearLeg == at) {
                nearLeg = -1
                Xp.log(TAG + "away from the station after walk $at again (${left} m)")
            }
        }
        return nearLeg == at
    }

    /** What the ride under way says about itself, and the milestones told of it. */
    private fun ride(at: Int, caps: JSONArray, plan: JSONObject?, c: JSONObject, now: Long): AmapTransitEntity.Live {
        // The live data's own leg is `locationData.groupIndex` - the same count as the capsules
        // (0 for the walk to the station, 1 for the ride, 2 for the walk after it on 10-05) - and
        // its stop count belongs to that leg only: on a walk it counts something else.
        val group = live?.optJSONObject("locationData")?.optInt("groupIndex", -1) ?: -1
        val where = if (group == at) live?.optJSONObject("arriveRemind") else null
        val loc = if (index(c) == at) c.optJSONObject("location") else null
        val bus = !subway(caps, at, plan)
        val cardCount = loc?.optInt("remainStations", -1) ?: -1
        val liveCount = where?.optInt("remainStopNum", -1) ?: -1
        val said = Regex("(\\d+)\\s*站").find(AmapTransitEntity.sentence(c))?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val cur = where?.optString("curStopName").orEmpty().replace(" ", "").trim()
        val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, at))
        val total = (seg?.optJSONArray("via_st_list")?.length() ?: -1) + 1
        // The card's count believed only once 高德 has shown it is about this ride ([countLeg]).
        if (firstCountLeg != at) {
            firstCountLeg = at
            firstCount = cardCount
        } else if (cardCount != firstCount) countLeg = at
        if (where != null) countLeg = at
        val believed = countLeg == at || total <= 0
        // A bus counts its own stops more finely than the card; a subway's card is its own count.
        // Not yet believed, the ride is all ahead of it, as the plan has it.
        val counted = if (believed) listOf(if (bus) liveCount else cardCount, cardCount, liveCount, said)
            .firstOrNull { it >= 0 } else total
        // No count at all: 0 only once the ride has been counted - seen first with none, it is the
        // plan's whole ride rather than its end.
        val remain = counted ?: if (milestones.counted || total <= 0) 0 else total
        // Every count 高德 gives, and the one its stop name implies (named), once per change: the
        // island ran a stop ahead early in a subway ride (user, 10-13) - which count moved first.
        val heard = "card=$cardCount live=$liveCount said=$said cur=$cur named=${byName(seg, cur)}" +
            if (believed) "" else " (card not yet believed)"
        if (heard != lastHeard) {
            lastHeard = heard
            Xp.log(TAG + "ride $at/$total: $heard -> $remain")
        }
        milestones.observe(remain, total, cur, now)
        return AmapTransitEntity.Live(remain, loc?.optDouble("persent", -1.0) ?: -1.0, cur,
            arrivals(seg, bus))
    }

    /**
     * The stops left from the stop [name] - the boarding stop or one of the plan's between -
     * as 高德 counts them (大学城南 3, 板桥 2, 员岗 1 on 10-05), or -1 when the plan has no such stop.
     * Only logged.
     */
    private fun byName(seg: JSONObject?, name: String): Int {
        if (seg == null || name.isEmpty()) return -1
        val via = seg.optJSONArray("via_st_list") ?: JSONArray()
        fun same(o: JSONObject?) = o?.optString("name")?.replace(" ", "")?.trim() == name
        if (same(seg.optJSONObject("on_station"))) return via.length() + 1
        for (i in 0 until via.length()) if (same(via.optJSONObject(i))) return via.length() - i
        return -1
    }

    /**
     * The next arrivals at the stop a ride boards at, as ColorOS's realtime items: a subway's
     * `subway[].tripTime`, a bus's `realtime.buses[].trip`, the ride's own line first.
     */
    private fun arrivals(seg: JSONObject?, bus: Boolean): JSONArray {
        val out = JSONArray()
        val id = seg?.optString("busid")?.trim().orEmpty()
        val name = seg?.optString("bus_key_name")?.trim().orEmpty()
        val dir = seg?.optString("directionName")?.trim().orEmpty()
        if (!bus) {
            val lines = live?.optJSONArray("subway") ?: return out
            val line = pick(lines, "lineId", id) ?: return out
            val times = line.optJSONArray("tripTime") ?: return out
            for (i in 0 until times.length()) {
                val t = times.optJSONObject(i) ?: continue
                out.put(JSONObject()
                    .put("mainTitle", t.optString("mainTitle").trim())
                    .put("orderTiptext", t.optString("orderTiptext").trim())
                    .put("lineName", name)
                    .put("lineDirection", dir))
            }
        } else {
            val buses = live?.optJSONObject("realtime")?.optJSONArray("buses") ?: return out
            val b = pick(buses, "line", id) ?: return out
            val trips = b.optJSONArray("trip")
            for (i in 0 until (trips?.length() ?: 0)) {
                val t = trips!!.optJSONObject(i) ?: continue
                val words = t.optString("grade_words").trim().ifEmpty { b.optString("sub_status").trim() }
                out.put(JSONObject()
                    .put("mainTitle", words)
                    .put("orderTiptext", t.optString("station_left").trim().let { if (it.isEmpty()) "" else it + "站" })
                    .put("lineName", name)
                    .put("lineDirection", dir))
            }
        }
        return out
    }

    /** The entry of [list] whose [key] is [id], or its only one. */
    private fun pick(list: JSONArray, key: String, id: String): JSONObject? {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (id.isNotEmpty() && o.optString(key).trim() == id) return o
        }
        return if (list.length() == 1) list.optJSONObject(0) else null
    }

    /**
     * GaoDePtRideCodeDeferBindManager.n: a ride code opened or a card swiped on the way out
     * ([why]) ends a subway's 到站 at once, and the walk after it is shown; a trip that ends on
     * the subway gets its end card at once instead. Nothing else is held for it - ColorOS
     * listens only while one of those is waiting.
     */
    private fun exited(why: String) {
        // GaoDePtFinalDestCardManager.j: a last ride on the subway, its end card waiting.
        if (finalPending() && finalOnExit) {
            showFinal("out of the station ($why)")
            return
        }
        if (!milestones.exited()) return
        Xp.log(TAG + "out of the station ($why)")
        update()
    }

    /** The trip has arrived: ColorOS's end card (ya.a), for 30 s, and nothing after it. */
    private fun showFinal(why: String) {
        if (finalShown) return
        val caps = capsules ?: return
        worker.removeCallbacks(finalCard)
        worker.removeCallbacks(tick)
        finalShown = true
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
        val last = caps.length() - 1
        val entity = AmapTransitEntity.build(plan, caps, card, last,
            AmapTransitCard.ARRIVE_FINAL_DESTINATION, null, null, "", "", tripId)
        Xp.log(TAG + "trip's end ($why)")
        tell(entity.toString(), AmapTransitCard.ARRIVE_FINAL_DESTINATION)
        silence(AmapTransitCard.ARRIVE_FINAL_DESTINATION)
    }

    /** GaoDePtNaviSceneRouter.h: how long the card lasts if nothing more is said. */
    private fun silence(status: String) {
        worker.removeCallbacks(dismiss)
        worker.postDelayed(dismiss, when (status) {
            AmapTransitCard.ARRIVE_FINAL_DESTINATION -> FINAL_MS
            AmapTransitCard.ARRIVE_LINE_DESTINATION -> ARRIVAL_SILENCE_MS
            else -> SILENCE_MS
        })
    }

    /** GaoDePtDismissHandler.a: the card down, and everything of the trip let go. */
    private fun dismissAll(why: String) {
        Xp.log(TAG + "dismissed: $why")
        reset()
        tell(null, "")
    }

    private fun finalPending(): Boolean = finalAt != 0L && !finalShown

    /** FinalDestCardManager.g: the trip is on its last leg, and it is a walk. */
    private fun onLastWalk(): Boolean {
        val caps = capsules ?: return false
        return milestones.legAt == caps.length() - 1 && walking(caps, milestones.legAt)
    }

    // ------------------------------------------------------------------ the legs

    /** The capsule the card is about: its `location.index`, else its line among the capsules. */
    private fun index(c: JSONObject): Int? {
        val loc = c.optJSONObject("location")
        if (loc != null && loc.has("index")) return loc.optInt("index")
        val caps = capsules ?: return null
        val line = c.optString("mainText").trim()
        if (!c.optString("title").contains("步行") && line.isNotEmpty()) {
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                if (!AmapTransitEntity.walking(cap) &&
                    AmapTransitEntity.sameLine(cap.optString("text").trim(), line)) return i
            }
        }
        return null
    }

    private fun walking(caps: JSONArray, i: Int): Boolean =
        caps.optJSONObject(i)?.let { AmapTransitEntity.walking(it) } ?: false

    /** Which ride of the trip capsule [i] is (0 for the first). */
    private fun rideNumber(caps: JSONArray, i: Int): Int {
        var k = 0
        for (j in 0 until i) if (!walking(caps, j)) k++
        return k
    }

    private fun subway(caps: JSONArray, i: Int, plan: JSONObject?): Boolean {
        val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, i))
        val cap = caps.optJSONObject(i) ?: return false
        return when {
            cap.optString("capsuleType").trim() == "2" -> true
            cap.optString("capsuleType").trim() == "1" -> false
            seg != null -> seg.optString("bustype").trim() == "2"
            else -> cap.optString("text").trim().endsWith("线")
        }
    }

    // ------------------------------------------------------------------ telling

    /** Passes the entity on; the same one again only once KEEPALIVE_MS has gone. */
    private fun tell(entity: String?, status: String) {
        val now = SystemClock.uptimeMillis()
        if (entity != null && entity == lastSent && now - lastSentAt < KEEPALIVE_MS) return
        lastSent = entity
        lastSentAt = now
        if (status != lastStatus) Xp.log(TAG + "status " + lastStatus.ifEmpty { "-" } + " -> " +
            status.ifEmpty { if (entity == null) "end" else "walk" })
        lastStatus = status
        send(entity)
    }

    private fun send(entity: String?) {
        val ctx = AmapImmerse.context() ?: run {
            Xp.log(TAG + "no context to tell SystemUI")
            return
        }
        if (!enabled) {
            tellMetro(ctx, entity != null)
            return
        }
        show(ctx, entity)
        tellMetro(ctx, entity != null)
    }

    /** The page and the island: [entity], or nothing for null. */
    private fun show(ctx: android.content.Context, entity: String?) {
        try {
            val i = Intent(SYSUI_PROBE).setPackage(SYSUI)
                .putExtra("op", "transit")
                .putExtra("src", "amap")
            if (entity == null) i.putExtra("do", "end") else i.putExtra("json", entity)
            ProbeGuard.send(ctx, i)
            lastKind = AmapTransitIsland.update(ctx, entity)
        } catch (t: Throwable) {
            Xp.w(TAG + "tell failed: $t")
        }
    }

    /**
     * The switch, from SystemUI. Turned off, what is up is taken down; turned on mid-trip, the
     * trip's state now goes up - lastSent is kept current either way.
     */
    fun setEnabled(on: Boolean) {
        worker.post {
            if (on == enabled) return@post
            enabled = on
            Xp.log(TAG + "switch " + (if (on) "on" else "off"))
            val ctx = AmapImmerse.context() ?: return@post
            if (!on) show(ctx, null)
            else lastSent?.let { show(ctx, it) }
        }
    }

    /**
     * The ride-code island in 小爱建议 (MetroCodeIsland) the stations this trip boards and leaves
     * the subway at: ColorOS puts the code up at a trip's first station and then only where its
     * route changes or gets off (MetroIntentManager.d, remindType 0 / 1). Said with every word to
     * SystemUI - at least each KEEPALIVE_MS - so a restarted 小爱建议 has it again; and its end.
     */
    private fun tellMetro(ctx: android.content.Context, on: Boolean) {
        val stops = if (on) metroStops() else emptyArray()
        try {
            val i = Intent(MetroCodeIsland.ACTION_TRIP).setPackage(MetroCodeIsland.PKG)
            if (stops.isEmpty()) i.putExtra("do", "end")
            else i.putExtra("do", "plan").putExtra("stops", stops)
            ProbeGuard.send(ctx, i)
        } catch (t: Throwable) {
            Xp.log(TAG + "metro not told: $t")
        }
    }

    /** Every rail ride's boarding and leaving station, out of the plan that fits the trip. */
    private fun metroStops(): Array<String> {
        val caps = capsules ?: return emptyArray()
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) } ?: return emptyArray()
        val segs = plan.optJSONArray("segmentlist") ?: return emptyArray()
        val out = LinkedHashSet<String>()
        for (i in 0 until caps.length()) {
            if (walking(caps, i)) continue
            val line = caps.optJSONObject(i)?.optString("text")?.trim().orEmpty()
            // An intercity or outer-loop line 高德 types as a bus is still one with gates.
            if (!subway(caps, i, plan) && RAIL.none { line.contains(it) }) continue
            val seg = segs.optJSONObject(rideNumber(caps, i)) ?: continue
            for (end in arrayOf("on_station", "off_station")) {
                val name = seg.optJSONObject(end)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) out += name
            }
        }
        return out.toTypedArray()
    }

    /** SystemUI started over: the last state again, if the trip has not ended since. */
    fun resend() {
        worker.post {
            val entity = lastSent ?: return@post
            lastSentAt = SystemClock.uptimeMillis()
            send(entity)
        }
    }

    // ------------------------------------------------------------------ the probe

    /**
     * `AMAPPROBE --es transit ...`:
     *   raw     a payload 高德 sent (`--es json <base64>`), taken as if it had just arrived - a
     *           103 card, a 113 `datas`, the way a captured ride is played back
     *   begin / stop   the navigation's begin and end (`bizBegin` / `bizEnd(103)`)
     *   demo / end     a made-up ride (AmapTransitScene.DEMO) straight to SystemUI and the island
     *   fast / slow    the milestones' own times (a stop's 30 s, a subway's 5 min) at a tenth, or
     *                  back, for a replay that does not take the ride's length
     *   sim            the last trip navigated, played stop by stop out of its plan (simulate)
 *   exited         the way out of a station was taken - a ride code opened, a card's fare
 *                  taken (RideCodeExit sends it, `--es why <what>`): a subway's 到站 ends
     */
    fun probe(what: String, json: String): String {
        when (what) {
            "raw", "payload" -> worker.post { raw(json) }
            "begin" -> worker.post { channel(false) }
            "stop" -> worker.post { channel(true) }
            "demo" -> worker.post { tell(AmapTransitScene.DEMO, "demo") }
            "end" -> worker.post { dismissAll("probe") }
            "fast" -> worker.post { milestones.scale = 0.1 }
            "slow" -> worker.post { milestones.scale = 1.0 }
            "sim" -> worker.post { simulate() }
            "exited" -> worker.post { exited(json.ifEmpty { "probe" }) }
        }
        return describe()
    }

    /**
     * `transit sim`: the last trip 高德 navigated, played stop by stop out of its own plan - every
     * leg's card and live data as 高德 sends them, at a tenth of the milestones' times - so the whole
     * trip can be seen without riding it. 高德's own payloads are held back while it plays; nothing
     * here starts a navigation in 高德.
     */
    private fun simulate() {
        val caps = lastCaps ?: run {
            Xp.log(TAG + "sim: no trip to play")
            return
        }
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
        val segs = plan?.optJSONArray("segmentlist")
        val dest = plan?.optJSONObject("epoi")?.optString("name")?.trim().orEmpty().ifEmpty { "目的地" }
        val n = caps.length()
        val steps = ArrayList<Pair<Long, () -> Unit>>()
        var t = 0L
        fun at(after: Long, f: () -> Unit) {
            t += after
            steps.add(t to f)
        }
        simulating = true
        milestones.scale = 0.1
        at(0) {
            dismissAll("sim")
            navigating = true
            tripId = "gaode-pt-sim-" + System.currentTimeMillis()
        }
        var ride = 0
        var lastSubwayRide = false
        for (i in 0 until n) {
            val cap = caps.optJSONObject(i) ?: continue
            if (AmapTransitEntity.walking(cap)) {
                val to = (ride until (segs?.length() ?: 0)).firstNotNullOfOrNull {
                    segs!!.optJSONObject(it)?.optJSONObject("on_station")?.optString("name")?.trim()
                        ?.takeIf { s -> s.isNotEmpty() }
                } ?: dest
                // After a subway with only walking left, its 到站 holds for 30 s (at a tenth).
                val wait = if (lastSubwayRide && (i + 1 until n).all { walking(caps, it) }) 33_000L else 4_000L
                at(3_000) {
                    take(RIDE_BIZ, simCard(caps, i, "步行至 $to", listOf("步行", "前往", to), 1, 0.5).toString())
                    take(LIVE_BIZ, simLive(i, "", 0))
                }
                t += wait - 3_000
                lastSubwayRide = false
                continue
            }
            val seg = segs?.optJSONObject(ride)
            val line = cap.optString("text").trim()
            val stops = ArrayList<String>()
            seg?.optJSONObject("on_station")?.optString("name")?.trim()?.let { stops.add(it) }
            val via = seg?.optJSONArray("via_st_list")
            for (k in 0 until (via?.length() ?: 0)) stops.add(via!!.optJSONObject(k)?.optString("name")?.trim().orEmpty())
            val off = seg?.optJSONObject("off_station")?.optString("name")?.trim().orEmpty()
            val exit = seg?.optJSONObject("outport")?.optString("name")?.trim().orEmpty()
            val later = (i + 1 until n).any { !walking(caps, it) }
            val total = if (stops.isEmpty()) 3 else stops.size
            for (remain in total downTo 1) {
                val stop = stops.getOrElse(total - remain) { "" }
                val items = arrayListOf("${remain}站", "后", " · ", off)
                if (exit.isNotEmpty() && !later) items.add("($exit)")
                items.add(if (later) "换乘" else "出站")
                at(if (remain == total) 3_000 else 5_000) {
                    take(LIVE_BIZ, simLive(i, stop, remain, seg?.optString("busid")?.trim().orEmpty()))
                    take(RIDE_BIZ, simCard(caps, i, "乘坐 $line", items, remain,
                        (total - remain).toDouble() / total).put("mainText", line).toString())
                }
            }
            t += 2_000
            lastSubwayRide = subway(caps, i, plan)
            ride++
        }
        at(3_000) {
            take(RIDE_BIZ, JSONObject().put("cardData", JSONObject().put("title", "已到达 $dest")
                .put("arrived", true)).toString())
            channel(true)
        }
        at(32_000) {
            simulating = false
            milestones.scale = 1.0
            Xp.log(TAG + "sim: done")
        }
        Xp.log(TAG + "sim: " + n + " legs, plan " + (plan != null) + ", " + t / 1000 + " s")
        for ((time, f) in steps) worker.postDelayed({ f() }, time)
    }

    private fun simCard(caps: JSONArray, i: Int, title: String, items: List<String>, remain: Int,
                        persent: Double): JSONObject {
        val words = JSONArray()
        for (w in items) words.put(JSONObject().put("text", w))
        return JSONObject().put("cardData", JSONObject()
            .put("title", title).put("arrived", false).put("offRoute", false)
            .put("titleItems", words).put("planData", caps)
            .put("location", JSONObject().put("index", i).put("persent", persent)
                .put("remainStations", remain)))
    }

    private fun simLive(i: Int, stop: String, remain: Int, line: String = ""): String {
        // At the stop a ride boards at, the next two trains, as 高德 sends them for a subway.
        val times = JSONArray()
        if (line.isNotEmpty()) times.put(JSONObject().put("lineId", line).put("tripTime", JSONArray()
            .put(JSONObject().put("mainTitle", "3分钟").put("orderTiptext", "第 1 辆"))
            .put(JSONObject().put("mainTitle", "9分钟").put("orderTiptext", "第 2 辆"))))
        val data = JSONObject()
            .put("arriveRemind", JSONObject().put("curStopName", stop).put("remainStopNum", remain)
                .put("tipType", 4))
            .put("locationData", JSONObject().put("groupIndex", i))
            .put("subway", times)
            .put("realtime", JSONObject())
        return JSONObject().put("datas", JSONArray().put(JSONObject().put("type", RIDE_TYPE)
            .put("data", data)).toString()).toString()
    }

    private fun raw(json: String) {
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Xp.log(TAG + "raw: not JSON: $t")
            return
        }
        when {
            o.has("cardData") -> take(RIDE_BIZ, json)
            o.has("datas") -> take(LIVE_BIZ, json)
            else -> Xp.log(TAG + "raw: neither a card nor a live payload")
        }
    }

    fun describe(): String {
        val sb = StringBuilder("transit: on=").append(enabled)
            .append(" navigating=").append(navigating)
            .append(" leg=").append(milestones.legAt)
            .append(" status=").append(lastStatus.ifEmpty { "-" })
            .append(" kind=").append(lastKind.ifEmpty { "-" })
            .append(" plans=").append(plans.size)
            .append(" fits=").append(capsules?.let { caps -> plans.any { AmapTransitEntity.fits(it, caps) } })
            .append(" hold=").append(milestones.hold?.let { it.status + "@" + it.leg } ?: "-")
            .append(" final=").append(if (finalShown) "shown" else if (finalPending()) "pending" else "-")
            .append(" walkIsland=").append(walkIslandUp())
            .append(" walkNavi=").append(walkNaviUp())
            .append(" near=").append(nearLeg)
            .append(" live=").append(lastSent != null)
        sb.append('\n').append(AmapTransitIsland.describe())
        sb.append('\n').append(AmapOppoBridge.describe())
        val caps = capsules
        if (caps != null) {
            sb.append("\ncapsules: ").append((0 until caps.length()).joinToString(" | ") {
                val c = caps.optJSONObject(it)
                if (c == null || AmapTransitEntity.walking(c)) "walk" else c.optString("text")
            })
        }
        val plan = plans.firstOrNull()
        val segs = plan?.optJSONArray("segmentlist")
        for (i in 0 until (segs?.length() ?: 0)) {
            val s = segs!!.optJSONObject(i) ?: continue
            sb.append("\nplan[").append(i).append("] ").append(s.optString("bus_key_name")).append(": ")
                .append(s.optJSONObject("on_station")?.optString("name")).append(" → ")
                .append(s.optJSONObject("off_station")?.optString("name"))
                .append(" (").append(s.optJSONArray("via_st_list")?.length() ?: 0).append(" between)")
        }
        return sb.toString()
    }

    private fun array(v: Any?): JSONArray? = when (v) {
        is JSONArray -> v
        is String -> runCatching { JSONArray(v) }.getOrNull()
        else -> null
    }

    /**
     * Every payload the trip's two channels carried, kept whole by its shape, and when each came:
     * what a ride is read back from (`AMAPPROBE --ez max true`, `--ez events true`). 高德's process
     * is the only place they ever are - nothing is written to disk.
     */
    object Ledger {
        private const val MAX_KINDS = 16
        private const val MAX_CHARS = 8000
        private const val MAX_EVENTS = 90
        private val startAt = SystemClock.uptimeMillis()
        private val kinds = LinkedHashMap<String, String>()
        private val events = ArrayList<String>()

        fun record(name: String, biz: Int?, text: String?) {
            val at = (SystemClock.uptimeMillis() - startAt) / 1000.0
            val key = "$biz $name " + shape(text)
            synchronized(this) {
                if (text != null && text.length > 2) {
                    kinds.remove(key)
                    val cut = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "…(+" +
                        (text.length - MAX_CHARS) + ")" else text
                    kinds[key] = String.format("%.1fs %dB %s", at, text.length, cut)
                    while (kinds.size > MAX_KINDS) kinds.remove(kinds.keys.first())
                }
                events.add(String.format("%.1fs %s %dB", at, key, text?.length ?: 0))
                while (events.size > MAX_EVENTS) events.removeAt(0)
            }
        }

        /** A payload's kind: a `datas` by its types, a card by its keys. */
        private fun shape(text: String?): String {
            if (text == null || text.length < 64 || (!text.contains("datas") && !text.contains("cardData"))) {
                return "text:" + text.orEmpty().take(24)
            }
            return try {
                val o = JSONObject(text)
                val datas = when (val d = o.opt("datas")) {
                    is JSONArray -> d
                    is String -> runCatching { JSONArray(d) }.getOrNull()
                    else -> null
                }
                if (datas != null) {
                    "datas:" + (0 until datas.length()).joinToString(",") {
                        datas.optJSONObject(it)?.optInt("type", -1)?.toString() ?: "?"
                    }
                } else {
                    val c = o.optJSONObject("cardData")
                    "card:" + (c ?: o).keys().asSequence().sorted().joinToString(",")
                }
            } catch (t: Throwable) {
                "?"
            }
        }

        fun dump(): String = synchronized(this) {
            if (kinds.isEmpty()) "ledger: nothing yet"
            else "ledger:" + kinds.entries.joinToString("") { "\n  " + it.key + "  " + it.value }
        }

        fun eventsDump(): String = synchronized(this) {
            if (events.isEmpty()) "events: nothing yet" else "events:" + events.joinToString("") { "\n  $it" }
        }
    }
}
