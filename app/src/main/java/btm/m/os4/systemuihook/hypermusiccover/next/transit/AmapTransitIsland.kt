// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bus and subway trip's focus notification, posted as 高德 in 高德's process: ColorOS 17's
 * card for the trip (AmapTransitCard) in HyperOS's template.
 *
 * HyperOS gives 高德 a focus island only for walking and cycling (id 1236); a bus or subway trip
 * has none. 高德's other one, XiaomiUAConnectedDevice (1237, the protocol-1 one-liner), is held
 * back while this is up ([handle]) - one trip, one island.
 *
 * Where ColorOS's card goes in the template (`param_v2`, scene `template_v2`):
 *   capsule left / right   -> param_island's imageTextInfoLeft / Right: the line-coloured part of
 *                             a half (the waiting card's line, the transfer's next line, the exit
 *                             at a subway's 到站) is a chip picture, the white part its text
 *   cardPrimaryInfo        -> baseInfo.title
 *   cardSecondaryInfo      -> baseInfo.content (with cardSecondaryLineName before it)
 *   titleInLock            -> ticker, aodTitle
 *   cardStationOverview    -> progressInfo: the leg's progress, its car, the stop and the end
 * The silent walking card is posted only while 高德's own walking island is not up: when 高德's
 * walking navigation runs, that is ColorOS's walking card too.
 *
 * AmapFocus (in SystemUI) is what lets the plugin take a focus notification of 高德's at all.
 */
internal object AmapTransitIsland {

    private const val TAG = "MCAmap: transit island: "
    /** Beside 高德's walking island, 1236, and clear of its XiaomiUAConnectedDevice's 1237. */
    /** The trip's notification id: the lock screen page answers this island only. */
    const val ID = 1239
    private const val AMAP_WALK_ID = 1236
    private const val AMAP_UA_ID = 1237
    private const val CHANNEL = "hyperchanger_hmc_next_transit"

    private const val PIC = "miui.focus.pic_mc_transit"
    private const val PIC_LEFT = "miui.focus.pic_mc_left"
    private const val PIC_RIGHT = "miui.focus.pic_mc_right"
    /** The progress bar's three pictures: the vehicle, the stop ahead, the leg's end. */
    private const val PIC_VEHICLE = "miui.focus.pic_mc_vehicle"
    private const val PIC_PIN = "miui.focus.pic_mc_pin"
    private const val PIC_FLAG = "miui.focus.pic_mc_flag"
    /** The walking navigation's bar: a walker on it (ColorOS's progress_walk). */
    private const val PIC_WALKER = "miui.focus.pic_mc_walker"
    /** 高德's own walking island's turn: its pic_forward_v2, the same picture as its pic_large. */
    private const val AMAP_TURN = "miui.focus.pic_forward_v2"
    private const val AMAP_TURN_LARGE = "miui.focus.pic_large"
    /**
     * The silent walking card's 「步行导航」 (onGaodePtNaviBeginNaviBtnClick): 高德 brought up with
     * this extra, and the walk's navigation asked for once its page is in front - 高德 pushes the
     * walking page only into a page stack that is showing (10-07: pressed from the background, the
     * walk began only when 高德 was opened by hand).
     */
    private const val EXTRA_WALK = "hyperchanger_hmc_next_transit_walk"
    private const val ACTION_WALK_KEY = "miui.focus.action_mc_walk"

    /**
     * The car the progress bar's thumb is drawn with, after the design: a white shell going grey
     * towards its foot, dark glass, a dark tail, dark bogies.
     */
    private const val SHELL_TOP = 0xfffbfcfe.toInt()
    private const val SHELL_FOOT = 0xffd3d8df.toInt()
    private const val GLASS = 0xff22262d.toInt()
    private const val WHEEL = 0xff2a2d33.toInt()
    private const val HUB = 0xff6c717a.toInt()
    private const val LAMP = 0xffdfe9ff.toInt()

    /**
     * The plugin's boxes for the bar's three pictures, in dp (focus_notification_module_progress):
     * the thumb is 60x47 and drawn `fitXY`, each pin 30x47, all three standing on the container's
     * foot, and the 12dp bar sits 4dp above that foot - 31dp to [BAR_FOOT] inside a box.
     */
    private const val THUMB_W = 60f
    private const val SLOT_W = 30f
    private const val SLOT_H = 47f
    private const val BAR_FOOT = 43f
    /**
     * The metro and the pins are drawn in 设计图.jpg's own pixels, three to a dp (its 12dp bar is
     * 36px tall): they were traced from it there, and matched to it there.
     */
    private const val DESIGN_PX = 3f
    private const val SLOT_Y = 138f
    private const val THUMB_X = 447.5f
    private const val UNDER = 0xff22282d.toInt()
    private const val GLOW = 0xffb4c6e6.toInt()
    private const val NOSE_WHITE = 0xffe8f0f2.toInt()

    /** The metro's parts, as SVG path data in the design's pixels (see [metro]). */
    private const val BODY = "M480.8 214.2 C480.9 211.6 482.6 210.1 485 210 L566 210 C573 210.2 579 211 584 212.8 " +
        "C590 215 595 219.5 598.8 225 C601.5 229 603.6 233 604.8 238 L605 246 C604.9 249.5 603.5 252.5 601.8 254.5 " +
        "C600 256.5 598.5 257.5 596 257.5 L485 257.5 C482.6 257.4 480.9 256 480.8 253.5 Z"
    private const val TAIL = "M482.5 210.5 L478.5 210.5 C474 210.8 470.6 215 470.2 222 L470 249 " +
        "C470.2 254.5 473 257.5 477.5 257.5 L482.5 257.5 Z"
    private const val SKIRT = "M576 256.5 L598.5 256.5 C600.6 256.6 601.2 258.2 599.8 259.4 C597 260.8 590 260.9 584 260.5 " +
        "C580 260.2 577 259 576 256.5 Z"
    private const val TAIL_RIM = "M482 211.6 C475.5 211.6 471.8 215.5 471.4 223 L471.3 234"
    private const val LOWER = "M480 246.8 L566 246.8 C568 246.8 570 247.2 572 248.2 C576 250.4 580 253.6 583 255.4 " +
        "C585 256.5 587 257.4 590 257.6 L606 257.6 L606 260 L480 260 Z"
    private const val STRIPE = "M480 244.4 L566 244.4 C570 244.6 574 246 577 248 C580 250 583 252.3 586 253.2 " +
        "C590 253.6 597 253.4 606 253.4 L606 257.6 L590 257.6 C587 257.4 585 256.5 583 255.4 " +
        "C580 253.6 576 250.4 572 248.2 C570 247.2 568 246.8 566 246.8 L480 246.8 Z"
    private const val BAND = "M583 205 L610 205 L610 222 L602.2 231 L600.6 240 C600 239.2 599.6 238.4 599 237.8 " +
        "C596.5 234.5 593.5 230.5 590.5 227.8 C588.6 226.2 587.3 225 586.2 224 L585.5 212.6 Z"
    private const val BAND_EDGE = "M600.6 240 C600 239.2 599.6 238.4 599 237.8 C596.5 234.5 593.5 230.5 590.5 227.8"
    private const val NOSE_LIT = "M585.5 212.6 L586.2 224 C587.3 225 588.6 226.2 590.5 227.8 C593.5 230.5 596.5 234.5 599 237.8 " +
        "C599.6 238.4 600 239.2 600.6 240 L602.2 231 L610 222 L610 254 L600 253.2 C594 250.8 590.6 248.6 590.6 246 " +
        "C590.6 244.2 591.8 242.8 593.4 242 L584 232 L583 213 Z"
    private const val UNDER_CAB = "M573 240.2 C577 241 581 242 585 242.8 C588 243.3 591 243.4 594 243.2"
    private const val NOSE_RIM = "M584 212.6 C590 215 595 219.5 598.8 225 C601.5 229 603.6 233 604.8 238"
    private const val CAB = "M576 228.1 C578.5 228.1 581.5 229.2 584 230.6 C587 232 590 234.8 592 238.6 " +
        "C592.6 239.8 593.1 241 593.4 242 C590 242 587.5 241.9 585.5 241.5 C582 240.8 578.5 240.1 576.5 239.4 " +
        "C575.2 238.9 574.6 237.8 574.6 236.5 L574.6 230 C574.6 228.9 575.2 228.1 576 228.1 Z"

    /**
     * The narrowest fill the bar draws as a piece of itself: below the bar's own height the caps
     * meet and the fill is a square head (about 4.8% of the bar on this phone).
     */
    private const val PROGRESS_MIN = 5
    /** The widest a capsule's line chip is drawn, as a multiple of its height. */
    private const val MAX_CHIP = 2.4f

    /**
     * A new milestone floats once where ColorOS reminds strongly (GaoDePtNaviSceneRouter.j's
     * isStrongRemind - remindType 12 and requestShowPanel): a transfer and an arrival, nothing else.
     * The end card is not one (showFinalDestCard posts it without).
     */
    private val FLOAT_AT = setOf(AmapTransitCard.ARRIVE_TRANSFER_STATION,
        AmapTransitCard.ARRIVE_LINE_DESTINATION)

    @Volatile private var posted = false
    @Volatile private var lastStatus: String? = null
    @Volatile private var lastKey: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var held = 0
    /** Whether the island up is the walking navigation's card: 高德's own walking island waits. */
    @Volatile private var naviShown = false
    @Volatile private var heldWalk = 0
    /** 高德's last turn picture, from its own walking island. */
    @Volatile private var turn: Icon? = null
    /** 高德 was brought up by the walk button and has not shown a page since. */
    @Volatile private var walkAsked = false

    /**
     * 高德's own notifications: its 1237 is not posted while the trip's card is up, and its walking
     * island (1236) is watched - and held back while the trip's card is the walking navigation's
     * own: ColorOS shows one card for the walk, its own, and that card takes 1236's turn picture.
     */
    fun handle() {
        try {
            Xp.hookAll("amap.transit.notify", NotificationManager::class.java, "notify") { chain ->
                val a = chain.args
                val id = a.firstOrNull { it is Int } as Int?
                if (id == AMAP_WALK_ID) {
                    AmapTransitShare.walkIsland(true)
                    (a.firstOrNull { it is Notification } as Notification?)?.let { keepTurn(it) }
                    if (posted && naviShown) {
                        if (heldWalk++ == 0) Xp.log(TAG + "holding back 高德's own $AMAP_WALK_ID")
                        return@hookAll null
                    }
                }
                if (!posted || id != AMAP_UA_ID) return@hookAll chain.proceed()
                if (held++ == 0) Xp.log(TAG + "holding back 高德's own $AMAP_UA_ID")
                null
            }
            Xp.hookAll("amap.transit.cancel", NotificationManager::class.java, "cancel") { chain ->
                val id = chain.args.firstOrNull { it is Int } as Int?
                if (id == AMAP_WALK_ID) AmapTransitShare.walkIsland(false)
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.w(TAG + "notification hooks failed: $t")
        }
        // The walk button's 高德, as it comes to the front: a new intent to a running page, or
        // the page an intent started. The walk is asked for once it is resumed.
        try {
            val instr = android.app.Instrumentation::class.java
            Xp.hookAll("amap.transit.new_intent", instr, "callActivityOnNewIntent") { chain ->
                (chain.args.firstOrNull { it is Intent } as Intent?)?.let { takeWalk(it) }
                chain.proceed()
            }
            Xp.hookAll("amap.transit.resume", instr, "callActivityOnResume") { chain ->
                val out = chain.proceed()
                (chain.args.firstOrNull() as? android.app.Activity)?.intent?.let { takeWalk(it) }
                if (walkAsked) {
                    walkAsked = false
                    Xp.log(TAG + "「步行导航」: 高德 in front")
                    AmapTransitShare.startWalk()
                }
                out
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "walk button not watched: $t")
        }
    }

    private fun takeWalk(i: Intent) {
        if (!i.getBooleanExtra(EXTRA_WALK, false)) return
        i.removeExtra(EXTRA_WALK)
        walkAsked = true
    }

    @Suppress("DEPRECATION")
    private fun keepTurn(n: Notification) {
        val pics = n.extras?.getBundle("miui.focus.pics") ?: return
        (pics.getParcelable(AMAP_TURN) as? Icon ?: pics.getParcelable(AMAP_TURN_LARGE) as? Icon)
            ?.let { turn = it }
    }

    /** The trip's island for [entity] - none for null - and the kind of card it is. */
    fun update(ctx: Context, entity: String?): String {
        return try {
            val trip = entity?.let { AmapTransitCard.Trip.parse(JSONObject(it)) }
            val card = AmapTransitCard.of(trip)
            if (trip == null || card == null) {
                cancel(ctx)
                return ""
            }
            if (card.kind == AmapTransitCard.Card.KIND_WALK && AmapTransitShare.walkIslandUp()) {
                cancel(ctx)
                return card.kind + " (高德's own)"
            }
            val navi = card.kind == AmapTransitCard.Card.KIND_WALK_NAVI
            post(ctx, trip, card)
            if (navi && !naviShown) {
                // 高德's own walking island is this card now: what it has up goes.
                ctx.getSystemService(NotificationManager::class.java)?.cancel(AMAP_WALK_ID)
            }
            naviShown = navi
            lastError = null
            card.kind
        } catch (t: Throwable) {
            lastError = t.toString()
            Xp.log(TAG + "not posted: $t")
            "error"
        }
    }

    private fun cancel(ctx: Context) {
        if (!posted) return
        posted = false
        naviShown = false
        lastStatus = null
        lastKey = null
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        Xp.log(TAG + "taken down")
    }

    fun describe(): String {
        val sb = StringBuilder("island: posted=").append(posted)
        if (held > 0) sb.append(" held1237=").append(held)
        if (heldWalk > 0) sb.append(" held1236=").append(heldWalk)
        if (naviShown) sb.append(" walkNavi")
        lastError?.let { sb.append(" error=").append(it) }
        return sb.toString()
    }

    // Runs in 高德's process and posts as 高德, under 高德's own notification permission - this
    // module's manifest, which lint holds the call against, has nothing to do with it.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(ctx: Context, trip: AmapTransitCard.Trip, c: AmapTransitCard.Card) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "公交/地铁导航",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        val walk = c.kind == AmapTransitCard.Card.KIND_WALK
        val walkNavi = c.kind == AmapTransitCard.Card.KIND_WALK_NAVI
        // ColorOS's walking card puts where the walk starts and where it goes side by side, a
        // route with an arrow between (cardPrimaryInfo / cardSecondaryInfo); in the template's
        // title and lines that read as 「我的位置」 for a heading. The walk is said the way its
        // lock-screen row says it instead - 「步行至大学城南」, its length and time - and the two
        // ends go under it.
        // The waiting card's second row is its vehicle list (cardWaitingInformation, ya.m): the
        // line in its colour, where it goes, the next train and the one after.
        val wait = c.waiting?.firstOrNull()
        // The walking navigation's: 高德's two lines (the first can be empty, 「直行97米」 then
        // alone), and how much is left, as the bar's own label.
        val title = when {
            walk -> c.lockTitle
            walkNavi -> c.primary.ifEmpty { c.secondary }
            else -> c.primary.ifEmpty { c.lockTitle }
        }
        val content = when {
            walk -> c.lockSubtitle
            walkNavi -> if (c.primary.isEmpty()) c.rightWhite else c.secondary
            // One line is all a floating card has for this and the next (screenshot 10-06): the
            // next train only, as the capsule's right half has it.
            wait != null -> wait.realtime1
            else -> listOf(c.secondaryLine, c.secondary).map { it.trim() }
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        val lineRow = when {
            // Not 「我的位置 → 大学城南」 under it: the title says where already (user, 10-07).
            walk -> ""
            walkNavi -> c.progressFrom
            // The line is the badge beside it already; only where it goes.
            wait != null -> wait.direction.ifEmpty { wait.name }
            else -> listOf(c.line, c.direction).filter { it.isNotEmpty() }.joinToString(" ")
        }
        // A new milestone floats once and may alert; the same one reposted stays quiet.
        val milestone = c.status + "|" + c.kind != lastStatus
        lastStatus = c.status + "|" + c.kind
        val float = milestone && c.status in FLOAT_AT
        val dp = Resources.getSystem().displayMetrics.density
        val icon = when {
            c.line.isNotEmpty() -> badge(c.line, c.lineBg, c.lineText)
            walk || walkNavi -> walker()
            else -> appIcon(ctx)
        }
        // The card's own picture: 高德's turn while it navigates the walk (ColorOS's entity.icon),
        // the weak-signal one when 高德 says the GPS is weak (cardShowWeakInternet / weakSignal.png).
        val main = when {
            c.weakSignal -> Icon.createWithBitmap(weakSignal())
            walkNavi -> turn ?: Icon.createWithBitmap(icon)
            else -> Icon.createWithBitmap(icon)
        }
        val pics = Bundle().apply {
            putParcelable(PIC, main)
            putParcelable("miui.focus.pic_large", main)
            // The capsule's left picture: the walking navigation's is the card's (capsuleLeftIcon);
            // a ride's keeps its line, its weak signal being the card's only.
            putParcelable(PIC_LEFT, when {
                c.leftLine.isNotEmpty() -> Icon.createWithBitmap(chip(c.leftLine, c.leftLineColor, dp))
                walkNavi -> main
                else -> Icon.createWithBitmap(icon)
            })
            if (c.progress >= 0) putParcelable(PIC_WALKER, Icon.createWithBitmap(walker()))
            if (c.rightLine.isNotEmpty()) {
                putParcelable(PIC_RIGHT, Icon.createWithBitmap(chip(c.rightLine, c.rightLineColor, dp)))
            }
            if (c.stations != null) {
                putParcelable(PIC_VEHICLE, Icon.createWithBitmap(vehicle(c.subway(), c.lineBg)))
                putParcelable(PIC_PIN, Icon.createWithBitmap(pin(c.lineBg)))
                putParcelable(PIC_FLAG, Icon.createWithBitmap(flag()))
            }
        }
        val param = template(trip, c, title, content, lineRow, milestone, float)
        val extras = Bundle()
        extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        extras.putBundle("miui.focus.pics", pics)
        if (c.button.isNotEmpty()) {
            // The silent walking card's 「步行导航」: 高德 itself, which starts the walk once in front
            // (handle). From the lock screen that asks for the unlock first.
            val launch = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
                .setPackage(ctx.packageName))
                .putExtra(EXTRA_WALK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val press = PendingIntent.getActivity(ctx, ID + 1, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            extras.putBundle("miui.focus.actions", Bundle().apply {
                putParcelable(ACTION_WALK_KEY, Notification.Action.Builder(main, c.button, press).build())
            })
        }
        val open = Intent(Intent.ACTION_VIEW,
            Uri.parse(trip.deepLink.ifEmpty { "amapuri://amap?clearStack=0&keepStack=1" }))
            .setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(ctx, ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // The back screen's card: without one it draws 高德's notifications as a ride-hailing car.
        try {
            val (day, night) = TransitRear.cards(ctx, c, title, lineRow, appIcon(ctx),
                { bigBus(c.lineBg) }, tap)
            extras.putParcelable("miui.rear.rv", day)
            extras.putParcelable("miui.rear.rvAOD", night)
            // Said, not left to the back screen's list: its default for 高德 is `carHailing`, and
            // naming the same business is what it checks ("Business matched"); a different one
            // would be rejected outright.
            extras.putString("miui.rear.param", JSONObject()
                .put("rear_param_v1", JSONObject().put("business", "carHailing"))
                .toString())
        } catch (t: Throwable) {
            Xp.log(TAG + "no back screen card: $t")
        }
        val small = ctx.resources.getIdentifier("notification_amap", "drawable", ctx.packageName)
            .takeIf { it != 0 } ?: ctx.applicationInfo.icon
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(small)
            .setContentTitle(title)
            .setContentText(content.ifEmpty { lineRow })
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(!milestone)
            .setShowWhen(false)
            .setTimeoutAfter(timeout(c.status))
            .setContentIntent(tap)
            .addExtras(extras)
            .build()
        nm.notify(ID, n)
        posted = true
        val key = "${c.kind}|$title|$content"
        if (key != lastKey) Xp.log(TAG + "${c.kind} ${c.status}: $title | $content")
        lastKey = key
    }

    /** GaoDePtNaviSceneRouter.h, as the notification's own end: AmapTransitShare's backstop. */
    private fun timeout(status: String): Long = when (status) {
        AmapTransitCard.ARRIVE_FINAL_DESTINATION -> 30_000L
        AmapTransitCard.ARRIVE_LINE_DESTINATION -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    /**
     * The template: the card's words in baseInfo, the line's badge in picInfo, the capsule in
     * param_island, and the leg's progress where the card has a station overview - the milestones
     * between stops, 下一站 / 下一站即终点 / 当前站 / 换乘.
     *
     * No ground of its own, and the system's own colours: the landmark is the lock screen page's
     * (AmapTransitScene), and as that page's row this card sat over it with a second, opaque copy
     * of the picture, hiding the stops under it (2026-10-07).
     */
    private fun template(trip: AmapTransitCard.Trip, c: AmapTransitCard.Card, title: String,
                         content: String, lineRow: String, milestone: Boolean, float: Boolean): JSONObject {
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_LEFT))
                    .put("textInfo", JSONObject().put("title", c.leftWhite)))
                .put("imageTextInfoRight", JSONObject()
                    .put("type", 2)
                    .apply {
                        if (c.rightLine.isNotEmpty()) {
                            put("picInfo", JSONObject().put("type", 1).put("pic", PIC_RIGHT))
                        }
                    }
                    .put("textInfo", JSONObject().put("title",
                        listOf(c.rightWhite, c.rightGray).filter { it.isNotEmpty() }.joinToString(" ")))))
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        val o = JSONObject()
            .put("protocol", 1)
            .put("scene", "template_v2")
            .put("ticker", c.lockTitle)
            .put("tickerPic", PIC)
            .put("aodTitle", c.lockTitle)
            .put("aodPic", PIC)
            .put("enableFloat", float)
            .put("reopen", if (milestone) "reopen" else "close")
            .put("updatable", true)
            .put("param_island", island)
            .put("title", title)
            .put("content", content)
            .put("showSmallIcon", false)
            .put("padding", true)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", title)
                .put("content", content)
                .put("subContent", lineRow))
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
        if (c.button.isNotEmpty()) {
            // ColorOS's cardButtonText on cardButtonBgColor rgba(255,255,255,0.1); type 2 is the
            // template's button that is its title on a plate.
            o.put("actions", JSONArray().put(JSONObject()
                .put("action", ACTION_WALK_KEY)
                .put("actionTitle", c.button)
                .put("type", 2)
                .put("actionTitleColor", "#FFFFFF")
                .put("actionBgColor", "#1AFFFFFF")))
        }
        if (c.progress >= 0) {
            // ya.n.y: the walk as a bar (cardProgressPercent), a walker on it.
            o.put("progressInfo", JSONObject()
                .put("progress", c.progress.coerceIn(0, 100))
                .put("colorProgress", WALK_BLUE)
                .put("colorProgressEnd", hex(AmapTransitScene.blend(0xff4a86ff.toInt(), 0xff000000.toInt(), 0.25f)))
                .put("picForward", PIC_WALKER))
        }
        val leg = trip.current
        if (c.stations != null && leg != null) {
            // 高德's own share of the leg (`location.persent`) where it gave one, else the stops
            // gone by of the leg's own. ColorOS's overview carries no figure at all - three stops
            // and which one the train is at - and this bar is the template's nearest thing to it.
            val total = leg.via.size + 1
            val done = (total - (leg.remain ?: total)).coerceIn(0, total)
            val said = if (trip.legPercent in 0.0..1.0) (trip.legPercent * 100).toInt()
                else done * 100 / total
            val percent = if (said in 1 until PROGRESS_MIN) PROGRESS_MIN else said
            // progressInfo rather than multiProgressInfo: the plugin asks for the latter first and
            // draws it as segments and dots; this one is the design's single bar with a car on it.
            o.put("progressInfo", JSONObject()
                .put("progress", percent.coerceIn(0, 100))
                .put("colorProgress", hex(c.lineBg))
                .put("colorProgressEnd", hex(AmapTransitScene.blend(c.lineBg, 0xff000000.toInt(), 0.25f)))
                .put("picForward", PIC_VEHICLE)
                .put("picMiddle", PIC_PIN)
                .put("picEnd", PIC_FLAG))
        }
        return o
    }

    // ------------------------------------------------------------------ pictures

    private const val WALK_BLUE = "#4A86FF"

    /**
     * ColorOS's weakSignal: three bars rising, the first lit and the others faint, on 高德's own
     * red for 「信号弱」.
     */
    private fun weakSignal(): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xffe5484d.toInt()
        c.drawCircle(48f, 48f, 48f, p)
        val w = 12f
        val gap = 7f
        val left = 48f - (3 * w + 2 * gap) / 2f
        for (i in 0 until 3) {
            p.color = if (i == 0) Color.WHITE else 0x59ffffff
            val x = left + i * (w + gap)
            val top = 66f - (i + 1) * 12f
            c.drawRoundRect(RectF(x, top, x + w, 68f), 3f, 3f, p)
        }
        return b
    }

    /**
     * The line-coloured half of a capsule half (ColorOS's capsule*TextLine): the text in white on
     * a rounded chip of the colour - 「7」 on 7号线's green, 「B口」 on the line's colour.
     *
     * On ColorOS this is text and grows with its name; here it is a picture in the island's icon
     * slot, so a long one is squeezed thin. ColorOS's short name (ya.b.B) leaves a name with no
     * 号线 / 线 / 路 whole - 「城际(琶洲-深圳机场)」 - so the bracket goes, and a name still long is
     * drawn smaller to keep the chip no wider than [MAX_CHIP] times its height.
     */
    private fun chip(raw: String, bg: Int, dp: Float): Bitmap {
        val text = raw.replace(Regex("[(（][^)）]*[)）]"), "").trim().ifEmpty { raw.trim() }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = 15f * dp
        val h = (p.fontMetrics.bottom - p.fontMetrics.top) + 6f * dp
        val room = h * MAX_CHIP - 14f * dp
        val tw = p.measureText(text)
        if (tw > room) p.textSize *= room / tw
        val fm = p.fontMetrics
        val w = maxOf(p.measureText(text) + 14f * dp, h)
        val b = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        p.color = bg
        c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), 6f * dp, 6f * dp, p)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, b.width / 2f, b.height / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /**
     * A walker in a disc, ColorOS's walkInCycle for the walking card's capsule: 高德's blue, a
     * white figure mid-stride.
     */
    private fun walker(): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xff4a86ff.toInt()
        c.drawCircle(48f, 48f, 48f, p)
        p.color = Color.WHITE
        c.drawCircle(52f, 21f, 7.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        // Body leaning into the stride, the arms and legs either side of it.
        c.drawLine(49f, 34f, 44f, 56f, p)
        c.drawPath(Path().apply { moveTo(31f, 50f); lineTo(39f, 39f); lineTo(49f, 35f); lineTo(58f, 46f); lineTo(66f, 48f) }, p)
        c.drawPath(Path().apply { moveTo(44f, 56f); lineTo(53f, 66f); lineTo(55f, 80f) }, p)
        c.drawPath(Path().apply { moveTo(44f, 56f); lineTo(38f, 69f); lineTo(29f, 78f) }, p)
        return b
    }

    /** 高德's own icon, for the cards that are not about a line (the end, off the route). */
    private fun appIcon(ctx: Context): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(b))
        } catch (t: Throwable) {
            Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff4a86ff.toInt() })
        }
        return b
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xffffff)

    /**
     * The vehicle the leg is ridden on, side on: a metro car for a subway, a bus for anything else.
     *
     * Side on because of where it is drawn - the progress bar's thumb, which the plugin keeps at
     * the fill's own edge (`ModuleProgressViewHolder.setProgressThumb` puts it at
     * `progress * width / 100`, centred on the point), so the picture is a car seen from the
     * platform and the bar is the track it is running along.
     *
     * The box is the plugin's own thumb box, [THUMB_W] x [SLOT_H], because the plugin draws it
     * `fitXY`: a picture of any other shape is stretched to that one.
     */
    private fun vehicle(subway: Boolean, bg: Int): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = Bitmap.createBitmap(Math.round(THUMB_W * d), Math.round(SLOT_H * d),
            Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (subway) {
            c.scale(d / DESIGN_PX, d / DESIGN_PX)
            c.translate(-THUMB_X, -SLOT_Y)
            metro(c, bg)
        } else {
            // Drawn on a 100 x 40 grid, the wheels' foot at 40.
            val u = 18.5f / 40f
            c.scale(d, d)
            c.translate((THUMB_W - 100f * u) / 2f, BAR_FOOT - 0.4f - 40f * u)
            c.scale(u, u)
            bus(c, bg)
        }
        return b
    }

    /**
     * The design's metro car (设计图.jpg), traced from it in its own pixels and drawn with its
     * depth: a rounded dark tail lit along its rim, a white shell going blue-grey where its flank
     * turns under, windows sunk in a soft glow, a wrap-round cockpit with its lit edge, the line's
     * stripe sweeping down the nose, wheels lit along their lower rims, and its shadow on the bar.
     * The dark under the car also hides the fill running on beneath it, as the design shows.
     */
    private fun metro(c: Canvas, line: Int) {
        val core = AmapTransitScene.blend(line, 0xff6a5a40.toInt(), 0.38f)
        val noseCore = AmapTransitScene.blend(line, 0xff8090a8.toInt(), 0.5f)
        val halo = AmapTransitScene.blend(line, 0xffe0e6ee.toInt(), 0.55f)

        c.drawRoundRect(RectF(464f, 255f, 600f, 267f), 4f, 4f, paint(alpha(Color.BLACK, 0.5f), blur = 2.5f))
        c.drawRoundRect(RectF(478f, 261f, 600f, 267.5f), 2f, 2f, paint(shader = LinearGradient(
            478f, 0f, 500f, 0f, alpha(UNDER, 0f), UNDER, Shader.TileMode.CLAMP), blur = 0.8f))
        c.drawRoundRect(RectF(484f, 256.5f, 598f, 263.5f), 2f, 2f, paint(0xff1d2026.toInt()))
        c.drawRect(484f, 258.6f, 598f, 259.6f, paint(alpha(0xff3f4a5a.toInt(), 0.8f)))
        for ((cx, lit) in listOf(490.5f to 0.6f, 497.5f to 0.35f, 562.5f to 0.6f, 572f to 0.35f)) {
            c.drawCircle(cx, 262f, 3f, paint(WHEEL))
            c.drawArc(RectF(cx - 2.4f, 259.9f, cx + 2.4f, 264.7f), 0f, 180f, false,
                paint(alpha(0xff9a9ea4.toInt(), lit), stroke = 0.9f))
        }

        // The nose's chin, the line's colour gone dark, coming down below the flank's foot.
        c.drawPath(svg(SKIRT), paint(shader = vg(257f to AmapTransitScene.blend(line, 0xff2a3040.toInt(), 0.6f),
            260.5f to AmapTransitScene.blend(line, 0xff2a3040.toInt(), 0.78f)), blur = 0.6f))

        val tail = svg(TAIL)
        // The tail's end face, turned away from us: lit down to the roof's fold, where the roof
        // comes round into it, dark below.
        c.drawPath(tail, paint(shader = vg(210.5f to 0xff5a5a5a.toInt(), 212f to 0xff8c8c8c.toInt(),
            215f to 0xff7a7a7a.toInt(), 219f to 0xff6e6e6e.toInt(), 222.6f to 0xff646466.toInt(),
            223.6f to 0xff404244.toInt(), 240f to 0xff34363a.toInt(), 254f to 0xff2a2a30.toInt(),
            257.5f to 0xff1f2230.toInt())))
        c.save()
        c.clipPath(tail)
        c.drawOval(RectF(473f, 209.5f, 483f, 216.5f), paint(alpha(Color.WHITE, 0.18f), blur = 1.2f))
        c.drawPath(svg(TAIL_RIM), paint(shader = vg(211f to alpha(0xff9a9a9a.toInt(), 0.4f),
            222f to alpha(0xff808080.toInt(), 0.25f), 234f to alpha(0xff606060.toInt(), 0f)),
            blur = 0.8f, stroke = 3.6f))
        c.restore()

        val body = svg(BODY)
        // The roof and the flank are two faces: the roof brightening towards the fold at 223.6,
        // the flank dropping away below it, greyer and cooler.
        c.drawPath(body, paint(shader = vg(210f to 0xffc6c6c6.toInt(), 211f to 0xfffbfbfb.toInt(),
            212f to 0xffe6e6e6.toInt(), 214f to 0xffebebeb.toInt(), 217f to 0xffeeeeee.toInt(),
            219f to 0xfff3f3f3.toInt(), 222.6f to 0xfff5f5f5.toInt(), 223.6f to 0xffeaeaeb.toInt(),
            224.6f to 0xffe1e2e5.toInt(), 226f to 0xffd8dce4.toInt(), 229f to 0xffd0d6e0.toInt(), 234f to 0xffc6ccd8.toInt(), 240f to 0xffbcc2cf.toInt(),
            243f to 0xffbcc6dc.toInt(), 258f to 0xffb8c2d8.toInt())))
        c.save()
        c.clipPath(body)
        // Its end turning away from us, greying along the edge where it meets the tail.
        c.drawRect(480f, 208f, 488f, 260f, paint(shader = LinearGradient(480.5f, 0f, 487f, 0f,
            alpha(0xff9aa0aa.toInt(), 0.75f), alpha(0xff9aa0aa.toInt(), 0f), Shader.TileMode.CLAMP)))
        c.drawPath(svg(LOWER), paint(shader = vg(246.8f to 0xffa9c0dd.toInt(), 249f to 0xffa7b8cb.toInt(),
            252f to 0xffa2b5c6.toInt(), 254f to 0xff97acc6.toInt(), 256f to 0xff8aa2c2.toInt(),
            257f to 0xff4a6a98.toInt(), 257.6f to 0xff3f6498.toInt())))
        val stripe = svg(STRIPE)
        c.drawPath(stripe, paint(alpha(halo, 0.75f), stroke = 2.2f))
        c.drawPath(stripe, paint(shader = LinearGradient(580f, 0f, 592f, 0f, core, noseCore,
            Shader.TileMode.CLAMP)))
        val glass = vg(228.1f to 0xff6f81a5.toInt(), 229f to 0xff1f2e4b.toInt(), 230f to 0xff141d2e.toInt(),
            231.2f to 0xff1b2121.toInt(), 239.5f to 0xff222421.toInt(), 240.8f to 0xff16191a.toInt(),
            241.5f to 0xff3d444c.toInt())
        for ((x, w) in listOf(488.4f to 28.8f, 522.6f to 27.5f)) {
            val box = RectF(x, 228.1f, x + w, 241.5f)
            c.drawRoundRect(box, 1.6f, 1.6f, paint(alpha(GLOW, 0.75f), blur = 0.8f, stroke = 3.2f))
            c.drawRoundRect(box, 1.6f, 1.6f, paint(shader = glass))
        }
        for (x in floatArrayOf(556.8f, 566.6f)) {
            c.drawLine(x, 227f, x, 256.5f, paint(alpha(0xffaab2bf.toInt(), 0.35f), blur = 0.5f, stroke = 1f))
        }
        c.drawPath(svg(NOSE_LIT), paint(shader = LinearGradient(584f, 0f, 595f, 0f,
            alpha(NOSE_WHITE, 0.3f), NOSE_WHITE, Shader.TileMode.CLAMP), blur = 1.5f))
        // The light running under the cab's window and on round into the nose's front.
        c.drawPath(svg(UNDER_CAB), paint(alpha(0xfff2f5f8.toInt(), 0.85f), blur = 0.7f, stroke = 2.4f))
        val band = svg(BAND)
        c.drawPath(band, paint(shader = LinearGradient(598f, 217f, 587f, 227f,
            0xff8e8e8e.toInt(), 0xff4c4c4e.toInt(), Shader.TileMode.CLAMP), blur = 0.6f))
        c.drawPath(svg(BAND_EDGE), paint(alpha(0xff2a2b30.toInt(), 0.9f), blur = 0.7f, stroke = 2f))
        c.save()
        c.clipPath(band)
        c.drawPath(svg(NOSE_RIM), paint(alpha(0xffb4b4b4.toInt(), 0.8f), blur = 0.5f, stroke = 1.4f))
        c.restore()
        val cab = svg(CAB)
        c.drawPath(cab, paint(alpha(GLOW, 0.4f), blur = 0.8f, stroke = 3f))
        c.drawPath(cab, paint(shader = vg(228.8f to 0xff3a4352.toInt(), 231f to 0xff2c3434.toInt(),
            240.6f to 0xff283030.toInt())))
        c.save()
        c.clipPath(cab)
        c.drawLine(583f, 231f, 590.5f, 240.5f, paint(alpha(0xff2a3358.toInt(), 0.8f), blur = 1f, stroke = 3f))
        c.restore()
        c.drawOval(RectF(600.3f, 244.9f, 602.7f, 248.1f), paint(0xffb8c4dc.toInt()))
        c.restore()
    }

    /**
     * [bus] for the back screen card, on the 100 x 40 grid it is drawn on. The card stretches it to
     * 233dp, about 700px at the back screen's 2.8x, but it goes into the notification twice (the
     * day card and the always-on one) and the notification is reposted all trip: 300px is a
     * quarter of the bytes of 600, and a flat drawing loses next to nothing upscaled.
     */
    private fun bigBus(line: Int): Bitmap {
        val w = 300
        val b = Bitmap.createBitmap(w, w * 2 / 5, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(w / 100f, w / 100f)
        bus(c, line)
        return b
    }

    /** A bus in the same hand: the same shell and glass, a flat front, three windows, two wheels. */
    private fun bus(c: Canvas, line: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shell = Path()
        shell.addRoundRect(RectF(2f, 1f, 98f, 34f),
            floatArrayOf(4f, 4f, 7f, 7f, 3f, 3f, 3f, 3f), Path.Direction.CW)
        p.shader = LinearGradient(0f, 0f, 0f, 34f, intArrayOf(SHELL_TOP, SHELL_TOP, SHELL_FOOT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(shell, p)
        p.shader = null

        c.save()
        c.clipPath(shell)
        p.color = line
        c.drawRect(0f, 24f, 100f, 26.6f, p)
        p.color = GLASS
        c.drawRoundRect(RectF(10f, 7f, 31f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(34f, 7f, 55f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(58f, 7f, 79f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(84.5f, 5.5f, 101f, 20f), 2f, 2f, p)
        c.restore()

        for (x in floatArrayOf(22f, 78f)) {
            p.color = WHEEL
            c.drawCircle(x, 35f, 4.6f, p)
            p.color = HUB
            c.drawCircle(x, 35f, 1.8f, p)
        }
        p.color = LAMP
        c.drawCircle(96.4f, 28.4f, 1f, p)
    }

    /**
     * A stop on the bar: the design's map pin in the line's colour with a metro car's front on it,
     * a soft bevel round its rim and a dark edge, so it stands off the card. The bar's middle pin
     * (`progress_point1`), which the plugin keeps at the bar's middle.
     */
    private fun pin(line: Int): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = slot(d)
        val c = Canvas(b)
        c.scale(d / DESIGN_PX, d / DESIGN_PX)
        c.translate(-(757f - SLOT_W * DESIGN_PX / 2f), -SLOT_Y)
        val drop = drop(757f, 194f, 36.5f, 47f, 48f, 3f, 5f, 10f)
        c.save()
        c.translate(0f, 1.5f)
        c.drawPath(drop, paint(alpha(Color.BLACK, 0.2f), blur = 2f))
        c.restore()
        c.drawPath(drop, paint(alpha(AmapTransitScene.blend(line, Color.BLACK, 0.55f), 0.8f), stroke = 2f))
        c.drawPath(drop, paint(line))
        c.save()
        c.clipPath(drop)
        c.drawPath(drop, paint(alpha(0xff7088b0.toInt(), 0.38f), blur = 1.5f, stroke = 9f))
        c.restore()

        // The car's front: its body and the base it stands on, white, a little shadow under them.
        val front = Path()
        front.addRoundRect(RectF(741.8f, 173.2f, 772.2f, 206.5f),
            floatArrayOf(7f, 7f, 7f, 7f, 5f, 5f, 5f, 5f), Path.Direction.CW)
        front.op(svg("M746 206 L768 206 L769 210 Q770 212.6 773.6 213.8 L740.4 213.8 Q744 212.6 745 210 Z"),
            Path.Op.UNION)
        c.drawPath(front, paint(alpha(Color.BLACK, 0.18f), blur = 0.8f))
        c.drawPath(front, paint(Color.WHITE))
        val grey = AmapTransitScene.blend(line, 0xff8a9090.toInt(), 0.62f)
        c.drawRect(748.5f, 206.8f, 766.5f, 209.6f, paint(grey))
        c.drawRoundRect(RectF(752f, 175.4f, 762f, 178f), 1.3f, 1.3f, paint(grey))
        val window = RectF(746.5f, 180.5f, 768.1f, 191.3f)
        c.drawRoundRect(window, 2f, 2f, paint(line))
        c.drawRoundRect(window, 2f, 2f, paint(AmapTransitScene.blend(line, 0xff103070.toInt(), 0.4f), stroke = 1f))
        c.drawCircle(748.8f, 199f, 2.5f, paint(grey))
        c.drawCircle(765.3f, 199f, 2.5f, paint(grey))
        return b
    }

    /**
     * Where the leg ends: the design's grey pin with a white flag on it, the bar's
     * `progress_point2`. The plugin stands it at the bar's end; the design has the pin's right
     * edge at that end, so it sits right of its box's middle.
     */
    private fun flag(): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = slot(d)
        val c = Canvas(b)
        c.scale(d / DESIGN_PX, d / DESIGN_PX)
        c.translate(-(1248f - SLOT_W * DESIGN_PX), -SLOT_Y)
        val drop = drop(1215f, 203.5f, 32.5f, 45f, 30f, 14f, 6f, 8f)
        c.save()
        c.translate(0f, 1.5f)
        c.drawPath(drop, paint(alpha(Color.BLACK, 0.2f), blur = 2f))
        c.restore()
        c.drawPath(drop, paint(shader = vg(171f to 0xffa3a1ad.toInt(), 236f to 0xff97959f.toInt(),
            248f to 0xff8c8b92.toInt())))
        c.drawRoundRect(RectF(1203f, 191f, 1206.6f, 220.2f), 0.6f, 0.6f, paint(Color.WHITE))
        val cloth = paint(Color.WHITE, stroke = 0.8f)
        cloth.style = Paint.Style.FILL_AND_STROKE
        cloth.strokeJoin = Paint.Join.ROUND
        c.drawPath(svg("M1206 191 C1211 189.8 1215 191 1219 192.3 L1231.5 192.5 L1231.5 211.8 " +
            "L1219 211.6 C1215 210.3 1211 209.8 1206 210.8 Z"), cloth)
        return b
    }

    /** A pin's box, the plugin's own: [SLOT_W] x [SLOT_H], `fitCenter`, standing on its foot. */
    private fun slot(d: Float): Bitmap = Bitmap.createBitmap(Math.round(SLOT_W * d),
        Math.round(SLOT_H * d), Bitmap.Config.ARGB_8888)

    /**
     * A map pin centred on (cx, cy): a round head of radius [r] drawn out to a point [tip] below
     * the centre. Each flank leaves the circle [deg] degrees either side of its foot and runs to
     * the point in one curve, its handles [k1] along the circle's tangent and ([c2x], [c2y]) off
     * the point - fitted to the design's pins row by row.
     */
    private fun drop(cx: Float, cy: Float, r: Float, tip: Float, deg: Float, k1: Float,
                     c2x: Float, c2y: Float): Path {
        val th = Math.toRadians(deg.toDouble())
        val px = (r * Math.sin(th)).toFloat()
        val py = (r * Math.cos(th)).toFloat()
        val c1x = px - k1 * Math.cos(th).toFloat()
        val c1y = py + k1 * Math.sin(th).toFloat()
        val p = Path()
        p.moveTo(cx, cy + tip)
        p.cubicTo(cx - c2x, cy + tip - c2y, cx - c1x, cy + c1y, cx - px, cy + py)
        p.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 90f + deg, 360f - 2f * deg, false)
        p.cubicTo(cx + c1x, cy + c1y, cx + c2x, cy + tip - c2y, cx, cy + tip)
        p.close()
        return p
    }

    /** SVG path data, the subset the pictures are written in: absolute M, L, C, Q and Z. */
    private fun svg(data: String): Path {
        val t = Regex("[MLCQZ]|-?[0-9.]+").findAll(data).map { it.value }.toList()
        val p = Path()
        var i = 0
        var cmd = 'M'
        fun n() = t[i++].toFloat()
        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            when (cmd) {
                'M' -> { p.moveTo(n(), n()); cmd = 'L' }
                'L' -> p.lineTo(n(), n())
                'C' -> p.cubicTo(n(), n(), n(), n(), n(), n())
                'Q' -> p.quadTo(n(), n(), n(), n())
                'Z' -> p.close()
            }
        }
        return p
    }

    /** A vertical gradient through (y, colour) stops. */
    private fun vg(vararg stops: Pair<Float, Int>): Shader {
        val y0 = stops.first().first
        val y1 = stops.last().first
        return LinearGradient(0f, y0, 0f, y1, IntArray(stops.size) { stops[it].second },
            FloatArray(stops.size) { (stops[it].first - y0) / (y1 - y0) }, Shader.TileMode.CLAMP)
    }

    private fun alpha(colour: Int, a: Float) = (Math.round(a * 255) shl 24) or (colour and 0xffffff)

    /**
     * A paint for the pictures: a colour or a shader, a stroke when [stroke] is set, and a blur of
     * [blur] - an SVG `stdDeviation`, which is how the pictures were matched to the design, turned
     * into the radius `BlurMaskFilter` takes (Skia: sigma = radius * 0.57735 + 0.5).
     */
    private fun paint(colour: Int = Color.BLACK, shader: Shader? = null, blur: Float = 0f,
                      stroke: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (shader != null) Color.BLACK else colour
        p.shader = shader
        if (blur > 0f) p.maskFilter = BlurMaskFilter(((blur - 0.5f) / 0.57735f).coerceAtLeast(0.1f),
            BlurMaskFilter.Blur.NORMAL)
        if (stroke > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
        }
        return p
    }

    /** The line as a disc in its colour, its number on it: 「地铁1号线」 is 1, 「机场线」 机场. */
    private fun badge(line: String, bg: Int, fg: Int): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bg
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        val digits = Regex("\\d+").find(line)?.value
        val label = digits ?: line.removePrefix("地铁").removeSuffix("线").take(2).ifEmpty { "M" }
        p.color = if (Color.alpha(fg) == 0) Color.WHITE else fg
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = if (label.length <= 1) 56f else if (label.length == 2) 44f else 32f
        val fm = p.fontMetrics
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }
}
