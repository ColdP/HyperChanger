// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import btm.m.os4.systemuihook.BuildConfig
import btm.m.os4.systemuihook.hypermusiccover.next.ProbeGuard
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小爱建议's subway ride-code card, as a focus island.
 *
 * Near a station, Xiaomi's cloud hands the assistant an intention whose card is
 * SmallMetroCodeWidgetProvider (topic `metro_qr_code.*`): the station, the city, its lines and the
 * app the code opens in. The card lives in the 小爱建议 widget only and never reaches the island.
 *
 * Run inside 小爱建议 (com.miui.personalassistant) and posted as it. Both ways the assistant gets
 * its cards are read (personalassistant 25.41.82.00):
 *   - pulled: ServiceCardDataFetchTask.getIntentServiceList asks the engine's
 *     `content://com.xiaomi.aireco.intention.AssistantContentProvider` for `getIntentionData`;
 *     the answer's `data` is the JSON list of intentions. Read off ContentResolver.call.
 *   - pushed: the engine calls ServiceDeliverSystemProvider.call("localDirectRefresh"), and the
 *     assistant sends the cards out as `com.miui.servicedelivery.updateDataSet`, `deliveryEntity`
 *     the Gson of ServiceDeliveryEntity. Heard with a receiver of our own, here.
 * The list's items carry instanceId, target, periodList, extraInfo and slots. Each list is the
 * whole set: one without the card takes the island down, as does `completeService` naming its
 * instanceId, and the intention's own period ends it as a backstop.
 *
 * The slots are the ones SmallMetroCodeWidgetProvider.h reads: location_name, city_name,
 * line_info (a JSON object of line name to colour), package_name, often_package_name,
 * intent_content, app_title, default_flag. The education card (asking for the location
 * permission) and the default card (「智能感知站点」, no station) are not islands.
 *
 * The card is drawn after 小米智能卡's 「简洁刷卡」 island, from its own layout ([official]); this
 * module's template is kept for a phone without it.
 *
 * 小爱建议 sends a card at every station it notices, the ones ridden through as well. Which of
 * them get the island is ColorOS's rule (Metis, MetroIntentManager.d), kept per trip ([Trip]): the
 * first station after none, then only the ones the trip changes lines at or leaves the subway at
 * (its remindType 0 / 1), each once. Metis has those from the commute it learned the trip to be;
 * here from [MetroCommute], learned the same way from the trips this ride-code island has seen,
 * and from 高德's plan while it navigates (AmapTransitShare). A trip ends the way TripManager's
 * monitor ends it - no station for a while, or one stayed at too long - or on the way out
 * ([exit], from RideCodeExit in SystemUI), and is then recorded for the learning. Beyond ColorOS,
 * a learned commute also puts it up at the two stations before its end, and each station put up
 * vibrates once ([buzz]). One swiped away stays down at that station for this trip only.
 *
 * A tap is the widget's own tap: the island opens the assistant's RouterActivity, which hands the
 * card to a SmallMetroCodeWidgetProvider of its own - h() to take the intention, onReceive with
 * requestCode 6100 to open the code (the picked app, the card's intent, 支付宝; 微信's through
 * 小爱). [opener] is what is opened should that fail.
 */
internal object MetroCodeIsland {

    private const val TAG = "MCMetro: "
    private const val ACTION_UPDATE = "com.miui.servicedelivery.updateDataSet"
    private const val ACTION_COMPLETE = "com.miui.servicedelivery.completeService"
    private const val EXTRA_ENTITY = "deliveryEntity"
    private const val EXTRA_IDS = "instanceIds"
    private const val ACTION_DISMISS = "btm.m.os4.systemuihook.hypermusiccover.next.METRO_DISMISSED"
    /** 高德's plan and SystemUI's way out ([onTrip]), from the module's own processes. */
    const val ACTION_TRIP = "btm.m.os4.systemuihook.hypermusiccover.next.METRO_TRIP"
    private const val AMAP = "com.autonavi.minimap"
    private const val SYSUI = "com.android.systemui"
    /** The probe: `am broadcast -a btm.m.os4.systemuihook.hypermusiccover.next.METRO -p com.miui.personalassistant`. */
    private const val ACTION_PROBE = "btm.m.os4.systemuihook.hypermusiccover.next.METRO"

    const val PKG = "com.miui.personalassistant"
    /** 小米智能卡, whose 「简洁刷卡」 island [official] is drawn after. */
    private const val TSM = "com.miui.tsmclient"
    private const val ROUTER = "com.miui.personalassistant.service.aireco.common.ui.RouterActivity"
    private const val INTENTION =
        "com.miui.personalassistant.service.aireco.common.entity.intention.IntentionData"
    private const val GET_INTENTION = "getIntentionData"
    private const val TYPE = "hyperchanger_hmc_next_metro_code"
    private const val EXTRA_CARD = "hyperchanger_hmc_next_metro_card"
    /** The widget's own extras for a tap: BaseWidgetProvider.onReceive and SmallMetroCode...e. */
    private const val EXTRA_REQUEST = "request_code"
    private const val EXTRA_WIDGET = "appWidgetId"
    private const val EXTRA_INTENT_OPTION = "miuiDeliveryIntentExtraOption"
    private const val CLICK = 6100

    private const val PROVIDER =
        "com.miui.personalassistant.service.aireco.metro_code.widget.SmallMetroCodeWidgetProvider"
    private const val TOPIC = "metro_qr_code."
    private const val TOPIC_EDU = "metro_qr_code.metro_qr_code_education.metro_education"

    private const val ID = 1240
    private const val COMMUTE_FILE = "hyperchanger_hmc_next_metro_commute.json"
    private const val CHANNEL = "hyperchanger_hmc_next_metro_code"
    private const val PIC = "miui.focus.pic_mc_metro"
    private const val PIC_APP = "miui.focus.pic_mc_metro_app"

    private const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val MI_NFC = "com.miui.tsmclient"
    /** 支付宝's ride code, without the widget's channel tags. */
    private const val ALIPAY_METRO = "alipays://platformapi/startapp?appId=20002047&scene=metro"
    /** 小米智能卡's swipe page, as MetroCodeWidgetHelper.b opens it. */
    private const val MI_NFC_URI = "intent://tsmclient.mi.com/swiping?event_source=double_click_power" +
        "&card_group_id=1&from=com.xiaomi.aireco#Intent;scheme=https;package=com.miui.tsmclient;end"

    /** The island's blue where the card names no line. */
    private const val BLUE = 0xff3482ff.toInt()
    /** An intention with no period is given this long. */
    private const val DEFAULT_LIFE = 30 * 60_000L
    private const val MIN_LIFE = 60_000L
    private const val MAX_LIFE = 3 * 3600_000L

    /** LegacyFluidExitPolicy SINGLE_STATION_TIMEOUT: a first station's card, with no second one. */
    private const val ENTRY_MS = 5 * 60_000L
    /** Separates a station from its lines in the handover; see [save]. */
    private const val SEP = "\u0000"
    /**
     * TripManager's monitor ends a trip with no station for 5 min; a wait on the platform runs
     * past that off-peak, and the next station would then start a trip of its own and get a card.
     */
    private const val IDLE_MS = 10 * 60_000L
    /** TripManager's monitor: one station for this long is not a ride any more. */
    private const val LONG_STAY_MS = 20 * 60_000L
    /** 高德 says its plan again at least each minute while it navigates; past this it has gone. */
    private const val PLAN_STALE_MS = 30 * 60_000L

    private class Station(
        val instanceId: String,
        val location: String,
        val city: String,
        val lines: List<Pair<String, Int>>,
        val pkg: String,
        val oftenPkg: String,
        val intentContent: String,
        val appTitle: String,
        /** Wall-clock end of the intention's period, 0 for none. */
        val end: Long,
        /** The intention as the widget's IntentionData reads it, for the tap. */
        val card: String,
    ) {
        val name get() = location.ifEmpty { if (city.isNotEmpty()) city + "地铁" else "地铁站" }
        fun key() = "$instanceId|$location|$city|${lines.joinToString { it.first }}|$pkg|$oftenPkg"
    }

    @Volatile private var ctx: Context? = null
    @Volatile private var shown: Station? = null
    /**
     * The previous generation's island is still up: [shown] is ours and cannot be handed over
     * (Station is our class), so this is what lets [cancel] take that one down.
     */
    @Volatile private var inherited = false
    @Volatile private var lastKey: String? = null
    @Volatile private var updates = 0
    @Volatile private var lastSeen = "none"
    @Volatile private var lastError: String? = null
    private val logged = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * One ride through the subway as Metis keeps it (TripManager.h, tripStations): the stations
     * in the order their cards came, from the first one after none.
     */
    private class Trip(now: Long, val city: String) {
        val stations = ArrayList<String>()
        /** The lines each station's card named, for where the trip changed ([MetroCommute.changes]). */
        val lines = HashMap<String, Set<String>>()
        /** Wall clock at the first station: the commute's half hour. Replaced by [adopt]. */
        var startedAt = System.currentTimeMillis()
        /** Where the commute this trip was matched to changes and ends ([MetroCommute.match]). */
        var learned: Set<String> = emptySet()
        /** When the newest station came, and when any card last said where the phone is. */
        var newAt = now
        var seenAt = now
        /** The stations whose card has been up: once each (metroFluidMaxTimesPerStation). */
        val popped = HashSet<String>()
    }

    /**
     * The ride in progress, for the next generation (HotReload's saveFeatures). Strings and
     * numbers only: the saved state refuses anything of ours, and [Station] is ours.
     * [Trip.newAt]/[Trip.seenAt] and [planAt] are elapsed realtime - the same boot, so they stay
     * valid across a reload; [Trip.startedAt] is wall clock, as [MetroCommute] wants it.
     */
    @Synchronized
    fun save(): Array<Any?>? {
        val t = trip
        if (t == null && planStops.isEmpty() && planAt == 0L) return null
        return arrayOf(
            t?.city,
            t?.stations?.toTypedArray(),
            t?.lines?.map { (station, lines) -> station + SEP + lines.joinToString(",") }?.toTypedArray(),
            t?.startedAt ?: 0L,
            t?.learned?.toTypedArray(),
            t?.newAt ?: 0L,
            t?.seenAt ?: 0L,
            t?.popped?.toTypedArray(),
            planStops.toTypedArray(),
            planAt,
        )
    }

    /**
     * [save]'s other half, in the new generation, before [install]: install registers the
     * receiver, and a card that arrived before the trip was back would be read as "it starts
     * here". Plain data only; what needs the context waits for [adopted].
     */
    @Synchronized
    fun adopt(a: Array<Any?>?) {
        if (a == null) return
        val city = a.getOrNull(0) as? String
        val stations = strings(a.getOrNull(1))
        if (city != null && stations.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val t = Trip(now, city)
            t.stations.addAll(stations)
            for (row in strings(a.getOrNull(2))) {
                val at = row.indexOf(SEP)
                if (at <= 0) continue
                val lines = row.substring(at + SEP.length).split(",").filter { it.isNotEmpty() }.toSet()
                if (lines.isNotEmpty()) t.lines[row.substring(0, at)] = lines
            }
            t.startedAt = (a.getOrNull(3) as? Number)?.toLong() ?: t.startedAt
            t.learned = strings(a.getOrNull(4)).toSet()
            t.newAt = (a.getOrNull(5) as? Number)?.toLong() ?: t.newAt
            t.seenAt = (a.getOrNull(6) as? Number)?.toLong() ?: t.seenAt
            t.popped.addAll(strings(a.getOrNull(7)))
            trip = t
            Xp.log(TAG + "trip carried over at ${stations.last()} (station ${stations.size})")
        }
        planStops = strings(a.getOrNull(8)).toSet()
        planAt = (a.getOrNull(9) as? Number)?.toLong() ?: 0L
    }

    /** The rest of the adoption, after [install]: the context is there now. */
    @Synchronized
    fun adopted() {
        val t = trip
        // The first station's card has a timeout on it, and the reload took it off the main
        // queue with everything else.
        if (t != null && t.stations.size == 1) {
            val left = ENTRY_MS - (SystemClock.elapsedRealtime() - t.newAt)
            // Due while the reload was under way: run now rather than never.
            Gen.postDelayed(entryOver, left.coerceAtLeast(0L))
        }
        // The island the old generation posted, if it is still up.
        inherited = shown == null && runCatching {
            ctx?.getSystemService(NotificationManager::class.java)
                ?.activeNotifications?.any { it.id == ID }
        }.getOrNull() == true
        if (inherited) Xp.log(TAG + "the previous generation's island is still up")
    }

    private fun strings(v: Any?): List<String> =
        (v as? Array<*>)?.mapNotNull { it as? String }.orEmpty()

    private var trip: Trip? = null
    /** The commutes learned so far, in 小爱建议's no-backup storage ([register]). */
    private var commute: MetroCommute? = null
    /** Where 高德's plan boards and leaves the subway, and when it last said so. */
    private var planStops: Set<String> = emptySet()
    private var planAt = 0L
    private val entryOver = Runnable { entryTimeout() }
    private val BRACKETS = Regex("[（(][^）)]*[）)]")

    /** Called from 小爱建议's loader. Each hook stands on its own. */
    fun install(cl: ClassLoader) {
        try {
            val instr = Xp.findClass("android.app.Instrumentation", cl)
            Xp.hookAll("assist.metro.app_create", instr, "callApplicationOnCreate") { chain ->
                val out = chain.proceed()
                try {
                    val app = chain.args[0] as Application
                    if (Application.getProcessName() == PKG) register(app)
                } catch (t: Throwable) {
                    Xp.log(TAG + "not registered: $t")
                }
                out
            }
        } catch (t: Throwable) {
            Xp.w(TAG + "application hook failed: $t")
        }
        try {
            // Both overloads, (Uri, ...) and (String authority, ...): the method is the second.
            Xp.hookAll("assist.metro.resolver_call", android.content.ContentResolver::class.java, "call") { chain ->
                val out = chain.proceed()
                if (chain.args.getOrNull(1) == GET_INTENTION) {
                    try {
                        val data = (out as? Bundle)?.getString("data")
                        val c = ctx
                        if (c != null && data != null) update(c, JSONArray(data), "pull")
                    } catch (t: Throwable) {
                        lastError = t.toString()
                        Xp.log(TAG + "pull not read: $t")
                    }
                }
                out
            }
        } catch (t: Throwable) {
            Xp.w(TAG + "provider hook failed: $t")
        }
        try {
            Xp.hookAll("assist.metro.router_create", Xp.findClass(ROUTER, cl), "onCreate") { chain ->
                val out = chain.proceed()
                val a = chain.thisObject as android.app.Activity
                if (a.intent?.getStringExtra("type") == TYPE) tap(a, a.intent)
                out
            }
        } catch (t: Throwable) {
            Xp.w(TAG + "router hook failed: $t")
        }
        registerCurrent()
    }

    /**
     * A hot reload runs install() again in a process that already has its Application, and
     * callApplicationOnCreate will never fire a second time - so the new generation registers
     * against the running one (the ride-code island's receivers were unregistered by the old
     * generation's retirement). On a cold start this is null and the hook above does it.
     */
    private fun registerCurrent() {
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull() ?: return
        runCatching {
            if (Application.getProcessName() == PKG) register(app)
        }.onFailure { Xp.w(TAG + "registration from the running process failed: $it") }
    }

    @Synchronized
    private fun register(app: Context) {
        if (ctx != null) return
        ctx = app
        commute = runCatching { MetroCommute(java.io.File(app.noBackupFilesDir, COMMUTE_FILE)) }
            .onFailure { Xp.w(TAG + "commutes not loaded: $it") }.getOrNull()
        val heard = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    when (i.action) {
                        ACTION_UPDATE -> i.getStringExtra(EXTRA_ENTITY)?.let { e ->
                            update(c, JSONObject(e).optJSONArray("intentServiceInfoList") ?: JSONArray(), "push")
                        }
                        ACTION_COMPLETE -> complete(c, i.getStringArrayExtra(EXTRA_IDS))
                    }
                } catch (t: Throwable) {
                    lastError = t.toString()
                    Xp.w(TAG + "${i.action} failed: $t")
                }
            }
        }
        Gen.receiver(app, heard, IntentFilter().apply {
            addAction(ACTION_UPDATE)
            addAction(ACTION_COMPLETE)
        }, Context.RECEIVER_EXPORTED)
        Gen.receiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // Swiped away, or timed out (a timeout sends the delete intent too): down for now,
                // put up again at the trip's next station ([Trip.popped] keeps this one down).
                inherited = false
                val s = shown ?: return
                shown = null
                lastKey = null
                Xp.log(TAG + "gone: ${s.name}")
            }
        }, IntentFilter(ACTION_DISMISS), Context.RECEIVER_NOT_EXPORTED)
        class Trips : ProbeGuard.Receiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!ProbeGuard.admit(this, i)) return
                val answer = onTrip(c, i) ?: return
                // The app's page asks with an ordered broadcast (ModuleBridge.commute).
                if (isOrderedBroadcast) {
                    resultCode = 1
                    setResultExtras(answer)
                }
            }
        }
        ProbeGuard.register(app, IntentFilter(ACTION_TRIP), TAG, { Trips() },
            AMAP, SYSUI, BuildConfig.APPLICATION_ID)
        // adb's shell holds DUMP; nothing else that may send it here does.
        Gen.receiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                resultData = command(i.getStringExtra("json"), i.getStringExtra("do"))
            }
        }, IntentFilter(ACTION_PROBE), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        Xp.log(TAG + "listening for 小爱建议's cards")
    }

    /** A whole set of cards: the island follows whether the ride-code card is among them. */
    @Synchronized
    private fun update(c: Context, list: JSONArray, from: String) {
        updates++
        var found: Station? = null
        val topics = ArrayList<String>()
        for (k in 0 until list.length()) {
            val o = list.optJSONObject(k) ?: continue
            val topic = o.optJSONObject("extraInfo")?.optString("topicName").orEmpty()
            topics += topic.ifEmpty { "?" }
            if (!isMetro(o, topic)) continue
            val id = o.optString("instanceId")
            if (logged.add(id)) Xp.log(TAG + "card $id ($from): ${o.toString().take(1500)}")
            if (topic == TOPIC_EDU) continue
            val slots = o.optJSONObject("slots") ?: JSONObject()
            if (slots.optString("default_flag") == "1") continue
            found = parse(o, slots, topic)
            break
        }
        lastSeen = from + ":" + (if (topics.isEmpty()) "empty" else topics.joinToString(","))
        if (found == null) cancel(c, "the $from set has no ride-code card") else arrive(c, found)
    }

    // ------------------------------------------------------------------ the trip

    /**
     * A card for station [s]: whether it is one ColorOS puts up (MetroIntentManager.d) - the trip's
     * first station, or one the plan boards or leaves at - and not one it has put up already.
     */
    @Synchronized
    private fun arrive(c: Context, s: Station) {
        val now = SystemClock.elapsedRealtime()
        expire(now)
        val n = norm(s.location)
        val known = trip
        val t = known ?: Trip(now, s.city)
        if (n.isNotEmpty() && s.lines.isNotEmpty()) t.lines[n] = s.lines.map { it.first }.toSet()
        val remind: Boolean
        if (known == null) {
            // No trip: it starts here (tripStations.size == 1, metro_commute_start).
            t.stations += n
            trip = t
            Gen.removeCallbacks(entryOver)
            Gen.postDelayed(entryOver, ENTRY_MS)
            val route = runCatching { commute?.match(s.city, n, t.startedAt) }.getOrNull()
            t.learned = route?.stops.orEmpty()
            Xp.log(TAG + "trip starts at ${s.name}" +
                (route?.let { ", a learned commute (${it.trips} trips)" } ?: ""))
            route?.let { Xp.d(TAG + "commute: $it") }
            remind = true
        } else if (n.isNotEmpty() && t.stations.last() != n) {
            t.stations += n
            t.newAt = now
            Gen.removeCallbacks(entryOver)
            val plan = if (now - planAt < PLAN_STALE_MS) planStops else emptySet()
            remind = n in plan || n in t.learned
            Xp.log(TAG + "in the trip at ${s.name} (station ${t.stations.size}): " + when {
                n in plan -> "高德's plan boards or leaves here"
                remind -> "the learned commute changes, nears its end or leaves here"
                else -> "riding through"
            })
        } else {
            // The same station again: as it was.
            remind = shown != null
        }
        t.seenAt = now
        when {
            !remind -> cancel(c, "riding through")
            n in t.popped && shown == null -> Unit
            else -> {
                val first = t.popped.add(n)
                if (post(c, s) && first) buzz(c)
            }
        }
    }

    /** TripManager's monitor: no station for [IDLE_MS], or none new for [LONG_STAY_MS]. */
    private fun expire(now: Long) {
        val t = trip ?: return
        val why = when {
            now - t.seenAt > IDLE_MS -> "no station for ${(now - t.seenAt) / 60_000} min"
            now - t.newAt > LONG_STAY_MS -> "at one station for ${(now - t.newAt) / 60_000} min"
            else -> return
        }
        end(why)
    }

    private fun end(why: String, fareFrom: String = "", fareTo: String = "") {
        val t = trip ?: return
        trip = null
        Gen.removeCallbacks(entryOver)
        Xp.log(TAG + "trip over (" + t.stations.joinToString(" -> ") + "): $why")
        learn(t, fareFrom, fareTo)
    }

    /**
     * The trip, for [MetroCommute] (recordTrip). A card's fare names the gates it went in and out
     * of, which beat the first and last cards (a station noticed from the street, a last one
     * missed); a ride code's trip has only its cards.
     */
    private fun learn(t: Trip, fareFrom: String, fareTo: String) {
        val c = commute ?: return
        if (!c.learning) return
        val stations = ArrayList(t.stations)
        if (fareFrom.isNotEmpty() && stations.firstOrNull() != fareFrom) {
            if (stations.size > 1 && stations[1] == fareFrom) stations.removeAt(0) else stations[0] = fareFrom
        }
        if (fareTo.isNotEmpty() && stations.last() != fareTo) stations += fareTo
        if (stations.size < 2) return
        val changes = MetroCommute.changes(stations, t.lines)
        // The two before the end, not the start: the start is put up anyway.
        val approach = stations.subList(1, stations.size - 1).takeLast(2)
        runCatching {
            c.record(MetroCommute.Trip(t.city, stations.first(), stations.last(), changes, t.startedAt, approach))
        }.onFailure { Xp.w(TAG + "trip not recorded: $it") }
        Xp.d(TAG + "recorded: ${stations.first()} -> ${stations.last()}" +
            (if (changes.isEmpty()) "" else ", changing at " + changes.joinToString()) +
            (if (approach.isEmpty()) "" else ", before the end " + approach.joinToString()) +
            " (${c.count()} trips kept)")
    }

    /** SINGLE_STATION_TIMEOUT: still at the first station, its card goes. */
    @Synchronized
    private fun entryTimeout() {
        val t = trip ?: return
        if (t.stations.size != 1) return
        ctx?.let { cancel(it, "no second station in ${ENTRY_MS / 60_000} min") }
    }

    /**
     * [ACTION_TRIP]: 高德's plan (`do plan`, `stops` its boarding and leaving stations), its
     * navigation over (`do end`), or the way out of a station (`do exit`, `how` card or code).
     */
    @Synchronized
    private fun onTrip(c: Context, i: Intent): Bundle? {
        when (i.getStringExtra("do")) {
            // The app's page: what is learned, whether to learn, and forgetting it.
            "commute" -> return commuteState()
            "learn" -> {
                val on = i.getBooleanExtra("on", true)
                commute?.setLearning(on)
                Xp.log(TAG + "commute learning " + if (on) "on" else "off")
                return commuteState()
            }
            "forget" -> {
                commute?.forget()
                trip?.learned = emptySet()
                Xp.log(TAG + "learned commutes forgotten")
                return commuteState()
            }
            "plan" -> {
                val stops = i.getStringArrayExtra("stops").orEmpty()
                    .map { norm(it) }.filter { it.isNotEmpty() }.toSet()
                if (stops != planStops) Xp.log(TAG + "高德's plan stops at " + stops.joinToString())
                planStops = stops
                planAt = SystemClock.elapsedRealtime()
            }
            "end" -> if (planStops.isNotEmpty()) {
                planStops = emptySet()
                Xp.log(TAG + "高德's navigation over")
            }
            "exit" -> exit(c, i.getStringExtra("how").orEmpty(),
                norm(i.getStringExtra("from").orEmpty()), norm(i.getStringExtra("to").orEmpty()))
        }
        return null
    }

    /** For the app's page: each commute as start, changes (joined by a comma), end, trips. */
    private fun commuteState(): Bundle {
        val c = commute
        val routes = c?.routes().orEmpty()
        return Bundle().apply {
            putBoolean("loaded", c != null)
            putBoolean("learning", c?.learning ?: false)
            putInt("trips", c?.count() ?: 0)
            putStringArray("starts", routes.map { it.start }.toTypedArray())
            putStringArray("changes", routes.map { it.transfers.joinToString(",") }.toTypedArray())
            putStringArray("ends", routes.map { it.end }.toTypedArray())
            putIntArray("counts", routes.map { it.trips }.toIntArray())
        }
    }

    /**
     * The way out. A card's fare is taken only on leaving, and ends the trip and its card as
     * Alipay's boardingType 2 does (TripManager.e). A ride code opened at the first station is the
     * way in; after it, the way out - the island is left to its card, which may be the one opened.
     * A trip begun after it, an out-of-station change, starts with a card of its own.
     */
    private fun exit(c: Context, how: String, from: String = "", to: String = "") {
        val t = trip ?: return
        if (how == "card") cancel(c, "out of the station (card)")
        else if (t.stations.size < 2) return
        end("out of the station ($how)", from, to)
    }

    /** A station's name as both 高德 and 小爱建议 write it: no (地铁站), no 站 on the end. */
    private fun norm(name: String): String =
        name.replace(BRACKETS, "").replace(" ", "").trim().removeSuffix("地铁站").removeSuffix("站")

    private fun isMetro(o: JSONObject, topic: String): Boolean {
        if (topic.startsWith(TOPIC)) return true
        val target = o.optJSONArray("target") ?: return false
        for (t in 0 until target.length()) {
            val p = target.optJSONObject(t)?.optJSONObject("widgetImplInfo")?.optString("widgetProviderName")
            if (p == PROVIDER) return true
        }
        return false
    }

    private fun parse(o: JSONObject, slots: JSONObject, topic: String): Station {
        fun s(k: String) = if (slots.isNull(k)) "" else slots.optString(k).trim()
        val lines = ArrayList<Pair<String, Int>>()
        runCatching {
            val info = JSONObject(s("line_info"))
            for (name in info.keys()) {
                if (name.isEmpty()) continue
                val colour = runCatching { Color.parseColor(info.optString(name)) }.getOrDefault(BLUE)
                lines += name to colour
            }
        }
        var end = 0L
        o.optJSONArray("periodList")?.let { p ->
            for (k in 0 until p.length()) {
                var e = p.optJSONObject(k)?.optLong("endTime") ?: 0L
                if (e in 1 until 100_000_000_000L) e *= 1000 // seconds, not milliseconds
                end = maxOf(end, e)
            }
        }
        // Only what h() reads: the list's own fields (intentionType an int where IntentionData
        // has an enum) could fail its Gson read and leave the widget nothing.
        val card = JSONObject()
            .put("instanceId", o.optString("instanceId"))
            .put("extraInfo", JSONObject().put("topicName", topic)
                .put("traceId", o.optJSONObject("extraInfo")?.optString("traceId").orEmpty()))
            .put("slots", slots)
        return Station(o.optString("instanceId"), s("location_name"), s("city_name"), lines,
            s("package_name"), s("often_package_name"), s("intent_content"), s("app_title"), end,
            card.toString())
    }

    private fun complete(c: Context, ids: Array<String>?) {
        val s = shown ?: return
        if (ids != null && s.instanceId in ids) cancel(c, "completed")
    }

    private fun cancel(c: Context, why: String) {
        if (shown == null && !inherited) return
        shown = null
        inherited = false
        lastKey = null
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
        Xp.log(TAG + "taken down: $why")
    }

    // Runs in 小爱建议 and posts as it, under its own notification permission.
    @android.annotation.SuppressLint("NotificationPermission")
    /** Whether the card is up as [s] now - posted, or already up unchanged. */
    private fun post(c: Context, s: Station): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        // The island takes the notification down as the app would, which sends no delete intent:
        // what is up is asked, not remembered.
        if (lastKey != null && runCatching { nm.activeNotifications.none { it.id == ID } }.getOrDefault(false)) {
            shown = null
            lastKey = null
        }
        val key = s.key()
        if (key == lastKey) return true
        val fresh = shown?.instanceId != s.instanceId
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "地铁乘车码",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val router = Intent().setClassName(PKG, ROUTER)
            .putExtra("type", TYPE)
            .putExtra(EXTRA_CARD, s.card)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(c, ID, router,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val gone = PendingIntent.getBroadcast(c, ID,
            Intent(ACTION_DISMISS).setPackage(c.packageName), PendingIntent.FLAG_IMMUTABLE)
        val official = try {
            official(c, s, fresh, tap, gone)
        } catch (t: Throwable) {
            Xp.log(TAG + "the official card could not be made ($t); the template instead")
            null
        }
        if (official != null) {
            nm.notify(ID, official)
            shown = s
            inherited = false
            lastKey = key
            lastError = null
            Xp.log(TAG + (if (fresh) "up: " else "updated: ") + "${s.name} (official card)")
            return true
        }
        val open = opener(c, s)
        val appPkg = open?.`package` ?: open?.component?.packageName
        val appName = appPkg?.let { label(c, it) }.orEmpty()
        val lineText = s.lines.joinToString(" · ") { it.first }
        val content = lineText.ifEmpty { "一键进出站" }
        val sub = s.appTitle.ifEmpty { if (appName.isNotEmpty()) "打开${appName}乘车码" else "" }
        val colour = s.lines.firstOrNull()?.second ?: BLUE
        val glyph = metro(colour)
        val pics = Bundle().apply {
            putParcelable(PIC, Icon.createWithBitmap(glyph))
            putParcelable(PIC_APP, Icon.createWithBitmap(appPkg?.let { appIcon(c, it) } ?: glyph))
        }
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
                    .put("textInfo", JSONObject().put("title", "乘车码")))
                .put("imageTextInfoRight", JSONObject()
                    .put("type", 2)
                    .put("textInfo", JSONObject().put("title", s.name))))
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "metro_code")
            .put("scene", "template_v2")
            .put("ticker", s.name + " 乘车码")
            .put("tickerPic", PIC)
            .put("aodTitle", s.name + " 乘车码")
            .put("aodPic", PIC)
            .put("enableFloat", fresh)
            .put("updatable", true)
            .put("param_island", island)
            .put("title", s.name)
            .put("content", content)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", s.name)
                .put("content", content)
                .apply { if (sub.isNotEmpty()) put("subContent", sub) })
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_APP))
        val extras = Bundle()
        extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        extras.putBundle("miui.focus.pics", pics)
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(glyph))
            .setContentTitle(s.name)
            .setContentText(listOf(content, sub).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!fresh)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setTimeoutAfter(life(s))
            .setDeleteIntent(gone)
            .setContentIntent(tap)
            .addExtras(extras)
        nm.notify(ID, b.build())
        shown = s
        inherited = false
        lastKey = key
        lastError = null
        Xp.log(TAG + (if (fresh) "up: " else "updated: ") + "${s.name} [$lineText] -> " +
            (open?.let { it.`package` ?: it.component?.flattenToShortString() ?: it.data } ?: "nothing to open"))
        return true
    }

    /** Two short pulses, as a notification's: off in silent mode as the user set it. */
    // Runs in 小爱建议 and vibrates as it, under its own VIBRATE.
    @android.annotation.SuppressLint("MissingPermission")
    private fun buzz(c: Context) {
        try {
            val v = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } catch (t: Throwable) {
            Xp.log(TAG + "no vibration: $t")
        }
    }

    /**
     * The card in 小米智能卡's 「简洁刷卡」 style - the island it puts up for a card swiped at a
     * gate (SimpleCardSwipingNotificationUtils in com.miui.tsmclient 2026-09): its own layout,
     * island_custom_notification_view, as the focus view - a card picture on its plate, a title,
     * one line under it, a button on the right - and its island, the picture on the left and the
     * title on the right, floated open for its 3 seconds.
     *
     * The layout is the card app's, looked up by name in its package (RemoteViews carry the
     * package they inflate from); the picture is drawn here the way its CardTransformation draws
     * a card's art. What differs from a swipe: the notification stays in the shade and the island
     * stays as a small one while the card is up, since the code is wanted again at the gate, and
     * the screen is not woken. Null when the card app or one of its resources is not there.
     */
    private fun official(c: Context, s: Station, fresh: Boolean, tap: PendingIntent,
                         gone: PendingIntent): Notification? {
        val tsm = c.createPackageContext(TSM, 0)
        val res = tsm.resources
        fun id(name: String, type: String): Int {
            val v = res.getIdentifier(name, type, TSM)
            if (v == 0) throw IllegalStateException("the card app has no $type/$name")
            return v
        }
        val layout = id("island_custom_notification_view", "layout")
        val title = s.name
        // Its 「支付 x元 | 余额 y元」, in the same hand: the lines, else what the code does.
        // One line, its name whole. More, short: the numbered ones first, together, with one
        // 「号线」 after them - a line run through into another its number and the other's name,
        // 「4号线大兴线」 「4/大兴」 - then the named ones with their 「线」: 「2·4/大兴·13号线」,
        // 「8号线·浦江线」, 「5号线·环线」.
        val sub = (if (s.lines.size > 1) {
            val (numbered, named) = s.lines.map { it.first }.partition { lineNumber(it) != null }
            (listOfNotNull(numbered.takeIf { it.isNotEmpty() }
                ?.joinToString("·") { numberedShort(it) }?.plus("号线")) +
                named.map { shortName(it).let { n -> if (n.endsWith("线")) n else n + "线" } })
                .joinToString("·")
        } else s.lines.joinToString { it.first }).ifEmpty { "一键进出站" }
        val card = Icon.createWithBitmap(cardPicture(c, s.lines))
        // The layout's text is white, for the island's dark plate; on the shade's light card
        // ([light]) it is set dark, or it cannot be read.
        fun view(light: Boolean = false): android.widget.RemoteViews {
            val rv = android.widget.RemoteViews(TSM, layout)
            rv.setTextViewText(id("tv_title", "id"), title)
            rv.setTextViewText(id("tv_subtitle", "id"), sub)
            if (light) {
                rv.setTextColor(id("tv_title", "id"), 0xe6000000.toInt())
                rv.setTextColor(id("tv_subtitle", "id"), 0x99000000.toInt())
                rv.setTextColor(id("btn_right", "id"), 0xe6000000.toInt())
            }
            rv.setImageViewIcon(id("iv_left_icon", "id"), card)
            rv.setViewVisibility(id("iv_right_icon", "id"), android.view.View.GONE)
            // Its 「切卡」 button, for the code instead.
            rv.setViewVisibility(id("btn_right", "id"), android.view.View.VISIBLE)
            rv.setTextViewText(id("btn_right", "id"), "乘车码")
            rv.setOnClickPendingIntent(id("btn_right", "id"), tap)
            return rv
        }
        val nfcDynamic = runCatching {
            val plugin = c.createPackageContext("miui.systemui.plugin", 0)
            val b = plugin.resources.getIdentifier("nfc_dynamic", "bool", "miui.systemui.plugin")
            b != 0 && plugin.resources.getBoolean(b)
        }.getOrDefault(false)
        val life = (life(s) / 1000L).toInt().coerceAtLeast(60)
        val param = JSONObject()
            .put("isShowNotification", true)
            .put("islandFirstFloat", fresh)
            .put("business", "tsmclient")
            .put("updatable", true)
            .put("ticker", title)
            .put("tickerPic", "miui.focus.pic_start")
            .put("aodTitle", title)
            .put("aodPic", "miui.focus.pic_start")
            .put("param_island", JSONObject()
                .put("islandProperty", if (nfcDynamic) 0 else 1)
                .put("islandTimeout", life)
                .put("expandedTime", 3)
                .put("islandPriority", 1)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", JSONObject()
                        .put("type", 1)
                        .put("picInfo", JSONObject().put("type", 4).put("pic", "miui.focus.pic_start")))
                    .put("imageTextInfoRight", JSONObject()
                        .put("type", 2)
                        .put("textInfo", JSONObject().put("title", title))))
                .put("smallIslandArea", JSONObject()
                    .put("picInfo", JSONObject().put("type", 4).put("pic", "miui.focus.pic_start"))))
        val extras = Bundle()
        extras.putBundle("miui.focus.pics", Bundle().apply { putParcelable("miui.focus.pic_start", card) })
        extras.putBundle("miui.focus.actions", Bundle().apply {
            putParcelable("miui.focus.action_1", Notification.Action.Builder(card, "", tap).build())
        })
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(metro(s.lines.firstOrNull()?.second ?: BLUE)))
            .setContentTitle(title)
            .setContentText(sub)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!fresh)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setTimeoutAfter(life(s))
            .setContentIntent(tap)
            .setDeleteIntent(gone)
            .addExtras(extras)
            .build()
        n.extras.putParcelable("miui.focus.rv", view(light = true))
        n.extras.putParcelable("miui.focus.rvNight", view())
        n.extras.putParcelable("miui.focus.rv.island.expand", view())
        n.extras.putString("miui.focus.param.custom", param.toString())
        return n
    }

    /**
     * The station's lines as a card, the way CardTransformation draws a card's art for the
     * island: a 70x44dp card with 8dp corners, 6dp clear round it, edged with a 0.6dp hairline
     * going from 40% white at the top to 14% at the foot.
     *
     * One line fills the card with its colour and its number large on it in white, shadowed on a
     * light colour - 「13」 over a small 「号线」, a line with no number by its name, 「昌平」, one
     * of four characters or more in two lines. Two or three share it in upright bands
     * of their colours, each with its own number, as an interchange's sign has them; four in two
     * rows of two; five or six in two rows of three (a short row's cells widen to fill it); more,
     * five and a grey 「+n」 for the rest. No line, the blue with the train's front.
     */
    private fun cardPicture(c: Context, lines: List<Pair<String, Int>>): Bitmap {
        val d = c.resources.displayMetrics.density
        val cw = 70f * d
        val ch = 44f * d
        val pad = 6f * d
        val r = 8f * d
        val b = Bitmap.createBitmap(Math.round(cw + 2 * pad), Math.round(ch + 2 * pad), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val box = RectF(pad, pad, pad + cw, pad + ch)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        // Up to three in one row; four as two by two; five and more three to a row, two rows at
        // most, the sixth cell 「+n」 for the ones left over.
        val more = (lines.size - 6).coerceAtLeast(0).let { if (it > 0) it + 1 else 0 }
        val shown = if (more > 0) lines.take(5) else lines
        val cells = shown.size + (if (more > 0) 1 else 0)
        val rows = if (cells <= 3) 1 else 2
        val perRow = when {
            cells <= 3 -> maxOf(1, cells)
            cells == 4 -> 2
            else -> 3
        }
        fun cell(k: Int): RectF {
            val row = k / perRow
            val inRow = if (row == rows - 1) cells - row * perRow else perRow
            val w = cw / inRow
            val h = ch / rows
            val left = box.left + (k % perRow) * w
            val top = box.top + row * h
            return RectF(left, top, left + w, top + h)
        }
        cv.save()
        cv.clipPath(android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) })
        if (shown.isEmpty()) {
            p.color = BLUE
            cv.drawRect(box, p)
            val g = metro(Color.TRANSPARENT)
            val side = ch * 0.8f
            cv.drawBitmap(g, null, RectF(box.centerX() - side / 2, box.centerY() - side / 2,
                box.centerX() + side / 2, box.centerY() + side / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val unit = Paint(num).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
        for (k in 0 until cells) {
            val line = shown.getOrNull(k)
            val at = cell(k)
            val col = line?.second ?: 0xff5a5a5e.toInt()
            // Opaque again: the hairlines leave the paint at 35% white, and a shader takes the
            // paint's alpha - every cell after the first came out half its colour.
            p.color = Color.BLACK
            p.shader = android.graphics.LinearGradient(at.left, at.top, at.right, at.bottom,
                col, AmapTransitScene.blend(col, Color.BLACK, 0.16f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(at, p)
            p.shader = null
            // White on every colour, with a soft shadow that keeps it apart from a light one
            // (13号线's yellow).
            val light = (0.299f * Color.red(col) + 0.587f * Color.green(col) + 0.114f * Color.blue(col)) / 255f > 0.62f
            num.color = Color.WHITE
            num.setShadowLayer(if (light) 1.5f * d else 0f, 0f, 0.5f * d, 0x66000000)
            unit.color = num.color
            unit.setShadowLayer(if (light) 1.5f * d else 0f, 0f, 0.5f * d, 0x66000000)
            // A letter before the number stays with it: S1号线 is 「S1」, not line 1's 「1」.
            val digits = line?.let { (Regex("^[A-Za-z]+[0-9]+").find(it.first) ?: Regex("[0-9]+").find(it.first))?.value }
            val label = when {
                line == null -> "+$more"
                digits != null -> digits
                else -> shortName(line.first)
            }
            val cx = at.centerX()
            // As large as the cell allows, the unit under a number only where there is room.
            val withUnit = digits != null && cells == 1
            // A long name in two lines rather than one in small type: four characters and more
            // broken in the middle, 「市域/机场」 「大兴/机场」; a Latin one at its first space.
            val split = if (digits != null || line == null) null else wrap(label)
            num.textSize = (if (withUnit) ch * 0.56f else at.height() * 0.5f)
            val room = at.width() - (if (rows > 1) 4f else 8f) * d
            if (split != null) {
                num.textSize = at.height() * 0.32f
                val wide = maxOf(num.measureText(split.first), num.measureText(split.second))
                if (wide > room) num.textSize *= room / wide
            } else if (num.measureText(label) > room) num.textSize *= room / num.measureText(label)
            val fm = num.fontMetrics
            if (split != null) {
                val lineH = (fm.descent - fm.ascent) * 0.92f
                val base = at.centerY() - lineH / 2 - (fm.ascent + fm.descent) / 2
                cv.drawText(split.first, cx, base, num)
                cv.drawText(split.second, cx, base + lineH, num)
            } else if (withUnit) {
                unit.textSize = ch * 0.2f
                val total = (fm.descent - fm.ascent) * 0.78f + unit.textSize
                val top = box.centerY() - total / 2
                cv.drawText(label, cx, top - fm.ascent * 0.86f, num)
                cv.drawText("号线", cx, top + total, unit)
            } else {
                cv.drawText(label, cx, at.centerY() - (fm.ascent + fm.descent) / 2, num)
            }
            // Hairlines between the cells: left of each but a row's first, above the second row.
            p.color = 0x59ffffff
            if (at.left > box.left + 0.5f) cv.drawRect(at.left - 0.5f * d, at.top, at.left + 0.5f * d, at.bottom, p)
            if (at.top > box.top + 0.5f) cv.drawRect(at.left, at.top - 0.5f * d, at.right, at.top + 0.5f * d, p)
        }
        cv.restore()
        val edge = 0.6f * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = edge
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, ch, 0x66ffffff, 0x24ffffff,
            android.graphics.Shader.TileMode.CLAMP)
        val h = edge / 2f
        cv.drawRoundRect(RectF(box.left + h, box.top + h, box.right - h, box.bottom - h), r, r, p)
        return b
    }

    /** Operators written before a line's own name: JR山手线 is 山手, 都营大江户线 大江. */
    private val OPERATORS = listOf("东京Metro", "Metro", "JR", "都营", "东急", "京王", "阪急", "阪神", "京急", "京成", "西武", "东武")

    /**
     * A line's own name, whole, for a cell and the line under the title: 「线」 and 「号线」 off
     * and the operator in front dropped where the line's own name is left (「JR山手线」 「山手」,
     * 「都营大江户线」 「大江户」, but 「小田急线」 stays 「小田急」); a Latin name, its first word
     * (「Piccadilly」). The cell shrinks the type to fit it.
     */
    private fun shortName(name: String): String {
        var n = plainName(name).removePrefix("地铁").removeSuffix("号线").removeSuffix("线").trim()
        for (op in OPERATORS) {
            if (n.startsWith(op) && n.length - op.length >= 2) {
                n = n.removePrefix(op).trim()
                break
            }
        }
        if (n.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } == true) {
            return n.split(' ').first()
        }
        return n
    }

    /** A numbered line's number, 「S1」 of 「S1号线(机场线)」, 「4」 of 「4号线大兴线」; null for a named one. */
    private fun lineNumber(name: String): String? =
        Regex("^([A-Za-z]*[0-9]+)号线").find(plainName(name))?.groupValues?.get(1)

    /** A numbered line under the title: its number, and what it runs through into, 「4/大兴」. */
    private fun numberedShort(name: String): String {
        val n = plainName(name)
        val number = lineNumber(n) ?: return shortName(n)
        val rest = n.substringAfter("号线").removeSuffix("线").trim()
        return if (rest.isEmpty()) number else "$number/$rest"
    }

    /**
     * A cell's name as two lines, or null for one: a Latin name with a space at its first space,
     * any other of four characters or more in the middle, the longer half first.
     */
    private fun wrap(label: String): Pair<String, String>? {
        val space = label.indexOf(' ')
        if (space > 0) return label.substring(0, space) to label.substring(space + 1).trim()
        if (label.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } == true) return null
        if (label.length < 4) return null
        val half = (label.length + 1) / 2
        return label.substring(0, half) to label.substring(half)
    }

    /** The name without the alias the engine writes after it: 「S1号线(机场线)」 is 「S1号线」. */
    private fun plainName(name: String): String = name.replace(Regex("[(（][^)）]*[)）]"), "").trim()

    private fun life(s: Station): Long {
        if (s.end <= 0L) return DEFAULT_LIFE
        return (s.end - System.currentTimeMillis()).coerceIn(MIN_LIFE, MAX_LIFE)
    }

    // ------------------------------------------------------------------ the tap

    /**
     * The island's tap, in RouterActivity: a SmallMetroCodeWidgetProvider of our own takes the
     * card through h() - found by its parameters, (int[], String, IntentionData), as is g(), the
     * String-to-IntentionData read - and is sent the widget's click.
     */
    private fun tap(a: android.app.Activity, i: Intent) {
        val card = i.getStringExtra(EXTRA_CARD) ?: return
        try {
            val cl = a.classLoader
            val provider = cl.loadClass(PROVIDER)
            val intention = cl.loadClass(INTENTION)
            val methods = generateSequence<Class<*>>(provider) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }.toList()
            val read = methods.first { m ->
                m.returnType == intention && m.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            val take = methods.first { m ->
                m.parameterTypes.contentEquals(arrayOf(IntArray::class.java, String::class.java, intention))
            }
            read.isAccessible = true
            take.isAccessible = true
            val p = provider.getDeclaredConstructor().newInstance()
            take.invoke(p, IntArray(0), card, read.invoke(p, card))
            (p as BroadcastReceiver).onReceive(a, Intent()
                .putExtra(EXTRA_REQUEST, CLICK)
                .putExtra(EXTRA_WIDGET, 0)
                .putExtra(EXTRA_INTENT_OPTION, card))
            Xp.log(TAG + "tap: handed to the widget")
        } catch (t: Throwable) {
            Xp.w(TAG + "tap: the widget's way failed ($t), opening it ourselves")
            val s = shown ?: return
            opener(a, s)?.let { runCatching { a.startActivity(it) } }
        }
    }

    /** SmallMetroCodeWidgetProvider.e for requestCode 6100, without its 微信-through-小爱 path. */
    private fun opener(c: Context, s: Station): Intent? {
        val pm = c.packageManager
        fun installed(p: String) = p.isNotEmpty() && runCatching { pm.getPackageInfo(p, 0) }.isSuccess
        fun own(p: String): Intent? = when (p) {
            ALIPAY -> Intent(Intent.ACTION_VIEW, Uri.parse(ALIPAY_METRO)).setPackage(ALIPAY)
            MI_NFC -> fromUri(c, MI_NFC_URI, MI_NFC)
            else -> null
        }
        fun launch(p: String) = pm.getLaunchIntentForPackage(p)
        val alipay = if (installed(ALIPAY)) own(ALIPAY) else null
        val picked = when {
            installed(s.oftenPkg) -> own(s.oftenPkg) ?: launch(s.oftenPkg)
            s.intentContent.isNotEmpty() && installed(s.pkg) ->
                own(s.pkg) ?: fromUri(c, s.intentContent, s.pkg) ?: launch(s.pkg)
            else -> alipay
        } ?: alipay ?: return null
        return picked.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The card's intent URI, kept to [pkg]'s own exported activities: it comes from the cloud and
     * is sent from SystemUI, so no selector, no grants and nothing in another package.
     */
    private fun fromUri(c: Context, uri: String, pkg: String): Intent? = runCatching {
        val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        i.selector = null
        i.flags = 0
        val comp: ComponentName? = i.component
        if (comp != null && comp.packageName != pkg) return@runCatching null
        if (i.`package` != null && i.`package` != pkg) return@runCatching null
        if (comp == null) i.setPackage(pkg)
        val ri = c.packageManager.resolveActivity(i, 0) ?: return@runCatching null
        if (!ri.activityInfo.exported || ri.activityInfo.packageName != pkg) null else i
    }.getOrNull()

    private fun label(c: Context, pkg: String) = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    private fun appIcon(c: Context, pkg: String): Bitmap? = runCatching {
        val d = c.packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(b))
        b
    }.getOrNull()

    /** A train's front in white on a disc of the line's colour. */
    private fun metro(colour: Int): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colour
        cv.drawCircle(48f, 48f, 48f, p)
        p.color = Color.WHITE
        cv.drawRoundRect(RectF(29f, 22f, 67f, 66f), 10f, 10f, p)
        p.color = colour
        cv.drawRoundRect(RectF(34f, 28f, 62f, 44f), 4f, 4f, p)
        cv.drawCircle(38f, 55f, 3.5f, p)
        cv.drawCircle(58f, 55f, 3.5f, p)
        p.color = Color.WHITE
        p.strokeWidth = 5f
        p.strokeCap = Paint.Cap.ROUND
        cv.drawLine(36f, 68f, 30f, 77f, p)
        cv.drawLine(60f, 68f, 66f, 77f, p)
        return b
    }

    // ------------------------------------------------------------------ probe

    /**
     * The probe: the state; `--es json '<list of intentions>'` as if the engine had sent it;
     * `--es do demo` a made-up station; `--es do end` an empty set; `--es do exit` the way out;
     * `--es do forget` the learned commutes gone.
     */
    fun command(json: String?, what: String?): String {
        val c = ctx ?: return "not registered yet"
        return try {
            when {
                json != null -> update(c, JSONArray(json), "probe")
                what == "demo" -> update(c, demo(), "probe")
                what == "end" -> update(c, JSONArray(), "probe")
                what == "exit" -> synchronized(this) { exit(c, "probe") }
                what == "forget" -> synchronized(this) { commute?.forget() }
            }
            describe()
        } catch (t: Throwable) {
            "failed: $t"
        }
    }

    fun describe(): String {
        val s = shown
        return "metro: updates=$updates seen=[$lastSeen] shown=" +
            (s?.let { "${it.name} id=${it.instanceId} end=${it.end}" } ?: "none") +
            (lastError?.let { " error=$it" } ?: "") +
            " trip=" + (trip?.stations?.joinToString(" -> ") ?: "none") +
            " plan=" + planStops.joinToString(",").ifEmpty { "none" } +
            " learned=" + (trip?.learned?.joinToString(",")?.ifEmpty { null } ?: "none") +
            " commutes=" + (commute?.let { c ->
                (if (c.learning) "" else "off ") + "${c.count()} trips " + c.routes().joinToString("; ", "[", "]")
            } ?: "not loaded")
    }

    private fun demo(): JSONArray {
        val slots = JSONObject()
            .put("location_name", "西二旗")
            .put("city_name", "北京")
            .put("line_info", JSONObject().put("13号线", "#F9E700").put("昌平线", "#DE82B2").toString())
            .put("package_name", ALIPAY)
            .put("default_flag", "0")
        val item = JSONObject()
            .put("instanceId", "mc-demo-" + System.currentTimeMillis() / 60_000)
            .put("extraInfo", JSONObject().put("topicName", "metro_qr_code.metro_qr_code.open_metro_code"))
            .put("target", JSONArray().put(JSONObject().put("widgetImplInfo",
                JSONObject().put("widgetProviderName", PROVIDER))))
            .put("periodList", JSONArray().put(JSONObject()
                .put("startTime", System.currentTimeMillis())
                .put("endTime", System.currentTimeMillis() + 10 * 60_000L)))
            .put("slots", slots)
        return JSONArray().put(item)
    }
}
