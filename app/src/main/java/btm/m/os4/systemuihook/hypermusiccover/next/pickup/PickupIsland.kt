// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.R
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject

/**
 * The pickup code's island, card and back-screen widget: what [PickupCodeIsland] found, posted as
 * a focus notification, and the tap and swipe that come back from it.
 *
 * The tracker hands over a [Page] with every code it shows, and everything about the card is
 * decided from that and from what this object last posted - nothing here reads the tracker's own
 * state, so a card stays right while the tracker has moved on to another task.
 *
 * A new code floats the island open, and so does the order turning ready to collect
 * ([PickupParse.READY]); anything else about a code already up only updates it. The card goes
 * [LIFE] after it was last posted, or when swiped away (that code is then not shown again for
 * [LIFE]). Its being up is asked of the system rather than remembered ([up]), since the
 * notification's own timeout takes it away unannounced.
 */
internal object PickupIsland {

    private const val TAG = PickupCodeIsland.TAG
    private const val SYSUI = PickupCodeIsland.SYSUI
    private const val WECHAT = PickupCodeIsland.WECHAT
    private const val ALIPAY = PickupCodeIsland.ALIPAY

    /**
     * The notification id a card goes out under, and the base it counts from.
     *
     * The back screen keeps a *widget* per (package, id) and slides one out by itself only when it
     * is a new widget - a notification that updates one it already has is drawn silently (see
     * [post]). 小爱's own pickup notification is a new id for every order for exactly that reason,
     * and one fixed id here meant only the first code of a session floated and every later one
     * waited to be pulled down by hand (2026-10-09). So a fresh code takes the next id and a quiet
     * update keeps the one it has.
     */
    private const val ID = 1241
    /** How many ids [ID] counts through before it starts over; far more codes than one session sees. */
    private const val IDS = 512
    private const val CHANNEL = "hyperchanger_hmc_next_pickup"
    private const val PIC = "miui.focus.pic_mc_pickup"
    private const val PIC_AOD = "miui.focus.pic_mc_aod"
    private const val PIC_MODEL = "miui.focus.pic_mc_model"

    /**
     * What this card calls itself in the focus and rear params. Ours, not 小爱's (`memory`): the
     * back screen's allow-list is keyed on it, and a business it does not know is one of the two
     * things that can have this card dropped on the way there.
     */
    private const val BUSINESS = "pickup_code"

    /**
     * What the rear copy calls itself. 小爱's own pickup notification posts as `memory`, and the
     * back screen only builds a widget for a business its list names - see where this is used.
     */
    private const val REAR_BUSINESS = "memory"

    /** The display the back screen's launcher runs on, `displayId=1` in `dumpsys activity top`. */
    private const val REAR_DISPLAY = 1

    private const val ACTION_OPEN = "btm.m.os4.systemuihook.hypermusiccover.next.PICKUP_OPEN"
    /** The island's own "back down" broadcast, which the plugin listens for and collapses on. */
    private const val ACTION_COLLAPSE_ISLAND = "com.miui.action.ACTION_COLLAPSE_ISLAND"
    private const val ACTION_GONE = "btm.m.os4.systemuihook.hypermusiccover.next.PICKUP_GONE"
    /** How long a card of an order still being made stays up: ColorOS's `seeding_max_time`. */
    private const val WAITED = 2 * 60 * 60_000L
    /** The ready card's 「我已取餐」. */
    private const val ACTION_PICKED = "btm.m.os4.systemuihook.hypermusiccover.next.PICKUP_PICKED"

    /**
     * What the tap needs, carried in the tap itself.
     *
     * The card outlives the process that posted it - a SystemUI restart, which is how a new build is
     * picked up, leaves the card on the back screen and this process with none of the state the tap
     * reads (the card's page: its task, app and launch url). Tapping it then opened nothing at
     * all, or the app's own home instead of the order page (2026-10-10). So the post writes what the
     * tap would otherwise have to remember.
     */
    private const val EXTRA_TASK = "hyperchanger_hmc_next_task"
    private const val EXTRA_HOST = "hyperchanger_hmc_next_host"
    private const val EXTRA_SCHEME = "hyperchanger_hmc_next_scheme"

    private const val LIFE = 30 * 60_000L
    /** The logo's fade-in, in ms: ColorOS's own `alpha_up_in` is 300ms on `coui_ease_move`. */
    private const val LOGO_FADE = 300L
    /** The least time between two plays of the card's picture. */
    private const val REPLAY_GAP = 600L
    /** The opacity a floated card's picture is posted at; [fadePictureIn] takes it up to 1. */
    private const val FADE_FROM = 0.15f
    /** That fade, in ms: the four 80ms steps it used to be posted as. */
    private const val PICTURE_FADE = 320L

    /**
     * What a card is about: the task it was read from, that task's app, its brand and rule, and the
     * mini program's launch url (支付宝's; empty for 微信).
     */
    data class Page(val task: Int, val host: String, val brand: String, val rule: PickupRule?, val scheme: String)

    private val bg get() = PickupCodeIsland.bg

    // All below on [bg].

    /**
     * The card up for one task: what it was posted with, and under which notification.
     * One per task, as ColorOS keeps one per observe task - several orders are several cards.
     */
    private class Card(var page: Page) {
        var key: String? = null
        /** The state [key] was posted with, to tell 制作中 → 待取餐 from a re-read of the same. */
        var status: String? = null
        /** The notification tag and id [key] was posted under; the back screen's widget is keyed on the id. */
        var tag: String? = null
        var id = ID
    }

    private val cards = LinkedHashMap<Int, Card>()
    /** The codes (`brand|code`) the user is done with, and until when each is not shown again. */
    private val muted = HashMap<String, Long>()
    /** Fresh codes posted by this process, counted into the id's range ([ID]). */
    private var ids = 0
    /** Whether the cards an earlier run of this process left posted have been taken down yet. */
    private var swept = false
    private var receivers = false
    private var iconSaid = false

    /** Whether a card of ours is up for [task], as far as this process knows (see [up]). */
    fun showing(task: Int): Boolean = cards[task]?.key != null

    /** The order state [task]'s card was posted with. */
    fun status(task: Int): String? = cards[task]?.status

    fun install() {
        // The card's picture is an animated WebP - ColorOS's drink, 53 frames of a cup filling -
        // and the platform will not play it on its own. Resources hands an animated image back as
        // an AnimatedImageDrawable that is *not running*: ImageDecoder's own contract says so
        // ("To start its animation, call AnimatedImageDrawable.start()"), ImageView never calls it,
        // and nothing in the RemoteViews path does either. A card drawn from the resource therefore
        // holds its first frame, which is the half-full cup that used to look like the picture
        // sitting too high.
        //
        // So it is started here, once, as the card is built: the cup fills the first time the card
        // comes up and stays full - the artwork is a one-shot 53-frame WebP, and no second start is
        // reachable (the RemoteViews is never applied again, the view is never detached and
        // re-attached, no island callback reaches SystemUI, and the copies the island hands over are
        // not the ones being drawn - all of that was measured in the log on 2026-10-08).
        //
        // The notification is posted from this process, so the ImageView that draws it is one this
        // module can reach. The icon setter is the narrow place to watch - it is the one call
        // RemoteViews' own `setImageViewIcon` makes, and little else in SystemUI goes through it.
        // (Switching the card to `setImageViewResource` or `setImageViewBitmap` would move this to
        // `setImageDrawable`, and a bitmap carries the one frame whatever is done to it.)
        runCatching {
            Xp.hookAll("sysui.pickup.icon", android.widget.ImageView::class.java, "setImageIcon") { chain ->
                val out = chain.proceed()
                val v = chain.thisObject
                // Guarded: this runs inside the host's own `setImageIcon`, and an exception here
                // would take the whole card down with it - a RemoteViews that throws while it is
                // applied simply does not appear.
                if (v is android.widget.ImageView && v.id == R.id.mc_pickup_icon) runCatching {
                    remember(v, chain.args.firstOrNull())
                    startPicture(v)
                }
                out
            }
            // The other half of RemoteViews: an asynchronous apply loads the icon off the main
            // thread through `setImageIconAsync` and hands back a Runnable that only *sets* the
            // drawable later (`setImageDrawable`), so the hook above never sees that card.
            Xp.hookAll("sysui.pickup.icon_async", android.widget.ImageView::class.java, "setImageIconAsync") { chain ->
                val out = chain.proceed()
                val v = chain.thisObject
                if (v is android.widget.ImageView && v.id == R.id.mc_pickup_icon && out is Runnable) {
                    val icon = chain.args.firstOrNull()
                    Runnable {
                        out.run()
                        runCatching {
                            remember(v, icon)
                            startPicture(v)
                        }
                    }
                } else out
            }
            // Played again each time the island opens. Of the many copies of the card SystemUI
            // keeps, the one the island is about to show is the one that turns visible - and the
            // platform tells exactly that view so, through `onVisibilityAggregated` (ImageView uses
            // it to show and hide its drawable; it is not called per frame). The picture is set
            // again from its icon rather than `start()`ed: a finished one-shot does not rewind, a
            // freshly loaded one plays from the empty cup.
            Xp.hookAll("sysui.pickup.icon_visibility", android.widget.ImageView::class.java, "onVisibilityAggregated") { chain ->
                val out = chain.proceed()
                val v = chain.thisObject
                if (v is android.widget.ImageView && v.id == R.id.mc_pickup_icon
                        && chain.args.firstOrNull() == true) runCatching { replay(v) }
                out
            }
            Xp.log(TAG + "watching the card's picture")
        }.onFailure { Xp.log(TAG + "card's picture not watched: $it") }
    }

    /** When each copy of the card's picture last started, so one opening plays it once. */
    private val started = java.util.WeakHashMap<android.widget.ImageView, Long>()

    private fun remember(v: android.widget.ImageView, icon: Any?) {
        if (icon is android.graphics.drawable.Icon) v.setTag(R.id.mc_pickup_logo, icon)
    }

    private fun startPicture(v: android.widget.ImageView) {
        (v.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
        started[v] = android.os.SystemClock.uptimeMillis()
        fadeLogoIn(v)
        fadePictureIn(v)
    }

    private fun replay(v: android.widget.ImageView) {
        val icon = v.getTag(R.id.mc_pickup_logo) as? android.graphics.drawable.Icon ?: return
        // Not while it is still filling: the island's own open animation can toggle the view's
        // visibility more than once, and the card's first appearance is already a play.
        val since = android.os.SystemClock.uptimeMillis() - (started[v] ?: 0L)
        if (since < PickupCard.lastAnimationMs.toLong().coerceAtLeast(REPLAY_GAP)) return
        v.post {
            runCatching {
                v.getTag(R.id.mc_pickup_icon).let { (it as? android.animation.Animator)?.cancel() }
                (v.parent as? android.view.View)?.findViewById<android.view.View>(R.id.mc_pickup_logo)?.alpha = 0f
                v.setImageIcon(icon) // runs the setter hook above, which starts it and fades the mark
            }
        }
    }

    /**
     * The brand's mark, faded in over the drink once the drink has finished filling.
     *
     * This is ColorOS's own card behaviour, taken from its implementation: `LevelDView` (the view
     * their `picture_d19`/`picture_d20` layers are) fades the second layer in over the first, and
     * its `card_modular_center_access_code.xml` anchors that layer 42dp above the picture's bottom -
     * the layout here carries the same layer, transparent. The delay is the picture's own length
     * (`PickupArt.duration`), because the drinks differ: 1749ms for the 53-frame ones, 600ms for the
     * 20-frame ones.
     *
     * The fade itself is a real animator on the live view. It has to be: a re-posted notification
     * would build the card again, and a card built again starts the drink over from an empty cup.
     * The module is in the same process as the host, so the view is one it can reach - which is also
     * how the drink is started in the first place.
     */
    private fun fadeLogoIn(picture: android.widget.ImageView) {
        val ms = PickupCard.lastAnimationMs
        Main.main().postDelayed({
            runCatching {
                val logo = (picture.parent as? android.view.View)
                    ?.findViewById(R.id.mc_pickup_logo) as? android.view.View ?: return@runCatching
                android.animation.ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f)
                    .setDuration(LOGO_FADE)
                    .start()
            }
        }, if (ms > 0) ms.toLong() else 0L)
    }

    /**
     * The drink folding in on a card that floats up: [post] draws a fresh card's picture at
     * [FADE_FROM], and this takes the live view from there to opaque.
     *
     * It used to be four more posts of the whole notification, 80ms apart, each drawing the picture
     * a little more opaque - five posts in 320ms, every one carrying four RemoteViews (day, night,
     * always-on, island) and the back screen's card, every one applied, inflated and run through
     * the shade's list again, right while the island was floating open. That was the stutter as a
     * code came up (#79). The view is in this process, as the drink's own start is.
     *
     * Read a frame later, because the RemoteViews sets the picture before it sets its alpha. A card
     * updated in place sets no alpha (it is posted opaque), so only a fresh one fades.
     */
    private fun fadePictureIn(picture: android.widget.ImageView) {
        picture.post {
            runCatching {
                val from = picture.imageAlpha
                if (from >= 255) return@runCatching
                (picture.getTag(R.id.mc_pickup_icon) as? android.animation.Animator)?.cancel()
                val fade = android.animation.ValueAnimator.ofInt(from, 255).setDuration(PICTURE_FADE)
                fade.interpolator = android.view.animation.DecelerateInterpolator()
                fade.addUpdateListener { picture.imageAlpha = it.animatedValue as Int }
                picture.setTag(R.id.mc_pickup_icon, fade)
                fade.start()
            }
        }
    }

    /**
     * The back screen, or null on a phone that has none.
     *
     * Asked before the rear card is built so that a phone without the second display posts nothing
     * for it, and asked here rather than trusted from the rear card's own fallback, which exists
     * only so its arithmetic cannot divide by a null.
     */
    private fun rearDisplay(c: Context): android.view.Display? = runCatching {
        c.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(REAR_DISPLAY)
    }.getOrNull()

    /** [r] on the island, for [page]; called on [bg] for every read that found a code. */
    fun show(r: PickupParse.Result, page: Page) {
        val brand = page.brand
        val key = "$brand|${r.code}|${r.status}|${r.store}"
        val now = SystemClock.elapsedRealtime()
        if ((muted["$brand|${r.code}"] ?: 0L) > now) return
        val ctx = Main.appContext() ?: return
        // The same order read in another task: the card up for it moves over rather than a second
        // one going up. 微信 makes a new task for the same page whenever the old one could not be
        // brought back - a parked task that would not move back to the phone's display, a mini
        // program closed and opened again - and with one card per task the same code stood on the
        // lock screen twice (#115, 肯德基's A0038).
        // A task whose own card is only waiting for its code (瑞幸's 148 and 153, both 940) moves
        // the other over too, its waiting card going down for it.
        val moved = if (r.code.isNotEmpty() && cards[page.task]?.key?.split('|')?.getOrNull(1).isNullOrEmpty())
            adopt(ctx, brand, r.code, page.task) else null
        val card = cards.getOrPut(page.task) { Card(page) }
        card.page = page
        // The notification goes by itself once its timeout is up, and the island with it; nothing on
        // that path tells us (a timeout is not a dismissal, so no delete intent). So ask the system:
        // with the card gone, its key is not evidence of anything, and what comes next is fresh.
        if (card.key != null && up(ctx, card) == false) clear(card)
        // A code once read stays: a read that found none - 瑞幸's page between two renders - put the
        // card back to 「等待出码」 and floated it open, and the next read brought 940 back and floated
        // it again, over and over (#115). The watch on the order skips such a read the same way.
        if (r.code.isEmpty() && card.key?.split('|')?.getOrNull(1)?.isNotEmpty() == true) return
        // Moved, it is posted again all the same: its taps still carry the old task.
        if (key == card.key && moved == null) return
        // A new code floats the island open; so does the order turning ready to collect. A state
        // changing again while it is already ready only updates, and so does the shop.
        val sameCode = card.key?.startsWith("$brand|${r.code}|") == true
        val fresh = !sameCode || ready(r.status) && !ready(card.status)
        runCatching { post(ctx, r, card, fresh) }
            .onSuccess {
                card.key = key
                card.status = r.status
            }
            .onFailure { Xp.log(TAG + "not posted: $it") }
    }

    private fun ready(status: String?): Boolean = PickupParse.isReady(status)

    /**
     * Another task's card for [brand]'s [code], moved to [task] - over the card [task] had waiting
     * for its code, if any; the task it moves from, or null when there is none. That task's wait
     * ends - the order is read from the new one now, and the old one, if it is still about, would
     * only put the card back where it was on its next round.
     */
    private fun adopt(c: Context, brand: String, code: String, task: Int): Int? {
        val from = cards.entries.firstOrNull { (t, card) ->
            t != task && card.key?.startsWith("$brand|$code|") == true
        }?.key ?: return null
        val card = cards.remove(from) ?: return null
        cards.put(task, card)?.takeIf { it.key != null }?.let { cancel(c, it) }
        Xp.log(TAG + "$brand $code: card moved from task $from to $task")
        PickupObserver.end(from, "the order is read in task $task")
        return from
    }

    /** A colour out of the rules XML as `#RRGGBB`, or the fallback when it cannot be read. */
    private fun colour(value: String?, fallback: String): String = runCatching {
        val parsed = android.graphics.Color.parseColor(value?.takeIf { it.isNotEmpty() } ?: fallback)
        String.format("#%06X", 0xFFFFFF and parsed)
    }.getOrDefault(fallback)

    /**
     * ColorOS's verdict on the order, said in the page's vocabulary - its own words are
     * completed/uncompleted/waiting, which mean nothing to a reader. This is the fallback, not the
     * first choice: see [post], and 麦当劳 below.
     *
     * Its three values are coarser than the page's own words and its default is the middle one -
     * the recognizer sets `orderStatus = uncompleted` when it starts and only moves it to
     * `completed` on a marker it knows - so `uncompleted` means "not read as finished" rather than
     * "being made". Shown as 「制作中」 that turned a page reading 「已准备完毕」 into an order still
     * in the kitchen (2026-10-09, 支付宝's 麦当劳 order).
     */
    private fun oemTitle(r: PickupParse.Result): String? = when (r.oemStatus) {
        "completed" -> "已完成"
        // Not 「制作中」: `uncompleted` is where the recognizer starts, so it is also what it says
        // when it read nothing, and shown as 「制作中」 it put a 「已准备完毕」 order back in the
        // kitchen. With the page silent too, the card says its label instead.
        "waiting" -> "等待出码"
        else -> null
    }

    /** Nothing is up for [card]: forget what it was showing. */
    private fun clear(card: Card) {
        card.key = null
        card.status = null
        card.tag = null
    }

    /**
     * Whether [card]'s notification is still posted, the island going when it goes. null is the
     * system not saying - not an answer, so the caller keeps what it had; reading a failure as "gone"
     * would float the island open again on every read.
     */
    private fun up(c: Context, card: Card): Boolean? = runCatching {
        c.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.notification.channelId == CHANNEL && it.tag == card.tag && it.id == card.id }
    }.getOrNull()

    /** Whether any card of ours is posted at all (the probe's `up=`). */
    fun up(c: Context): Boolean? = runCatching {
        c.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.notification.channelId == CHANNEL }
    }.getOrNull()

    /**
     * Every card of ours cancelled - the switch going off, and once per run of this process for the
     * cards an earlier run left posted. The back screen's widget for a card is removed when that
     * card's *notification* is removed, so a card nobody here remembers would stay there for good.
     */
    private fun sweep(nm: NotificationManager) {
        runCatching {
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun cancel(c: Context, card: Card) {
        val tag = card.tag ?: return
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(tag, card.id) }
    }

    /** [task]'s card down, for [why]. */
    fun takeDown(task: Int, why: String) {
        val card = cards.remove(task) ?: return
        if (card.key == null) return
        Main.appContext()?.let { cancel(it, card) }
        clear(card)
        Xp.log(TAG + "taken down ($task): $why")
    }

    /** Every card down: the switch going off. */
    fun takeDownAll(why: String) {
        cards.values.forEach { clear(it) }
        cards.clear()
        Main.appContext()?.getSystemService(NotificationManager::class.java)?.let { sweep(it) }
        Xp.log(TAG + "all taken down: $why")
    }

    /**
     * The tap's receiver, in place as soon as this process has a Context.
     *
     * Registering it on the way out of a [post] is not enough, and the case it misses is the one
     * the tap exists for: a SystemUI restart leaves the last card on the back screen while this
     * process is tracking an order it has not posted anything for, so nothing reaches [post] - and
     * the tap on that card was then a broadcast with no receiver behind it, which is to say the
     * button did nothing at all (2026-10-10, after a restart).
     */
    fun wake(ctx: Context) {
        receivers(ctx)
    }

    /**
     * Retirement: the guard that keeps wake() from registering twice is the only thing that
     * would keep the next generation from registering at all - the receiver itself is in Gen's
     * ledger and the sweep takes it. The cards stay posted: they are the user's order.
     */
    @JvmStatic
    fun retire() {
        receivers = false
        PickupObserver.retire()
        PickupPark.retire()
    }

    private fun receivers(ctx: Context) {
        if (receivers) return
        receivers = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_OPEN -> bg.post { open(c, i) }
                    ACTION_GONE -> bg.post { done("swiped away", c, i.getIntExtra(EXTRA_TASK, -1), cancel = false) }
                    // The order is collected: the card comes down and is not put up again for this
                    // code, and the wait for it is over.
                    ACTION_PICKED -> bg.post { done("picked up", c, i.getIntExtra(EXTRA_TASK, -1), cancel = true) }
                }
            }
        }
        Gen.receiver(ctx, r, IntentFilter().apply {
            addAction(ACTION_OPEN)
            addAction(ACTION_GONE)
            addAction(ACTION_PICKED)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The user is done with [task]'s card - swiped it away, or told it the order is collected. That
     * code is not shown again for [LIFE], and the wait on it ends. A swipe has already taken the
     * notification away; [cancel] takes it down for the button.
     */
    private fun done(why: String, c: Context, task: Int, cancel: Boolean) {
        val card = cards.remove(task)
        if (card != null) {
            card.key?.let { k -> muted[k.split('|').take(2).joinToString("|")] = SystemClock.elapsedRealtime() + LIFE }
            if (cancel) {
                cancel(c, card)
                collapse(c)
            }
            clear(card)
        } else if (cancel) {
            // A card this process never posted: one a SystemUI restart left up, with [cards] empty
            // and nothing read since to post over it. 「我已取餐」 on it did nothing but log
            // (2026-10-13, after a restart). It is found among the posted ones by the task it carries.
            orphan(c, task)?.let { sbn ->
                runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(sbn.tag, sbn.id) }
                collapse(c)
                Xp.log(TAG + "$why: took down a card left by an earlier run (${sbn.tag}/${sbn.id})")
            }
        }
        if (task >= 0) PickupObserver.end(task, why)
        Xp.log(TAG + "$why ($task)")
    }

    /**
     * The posted card for [task] that this process has no record of. Cards carry their task (see
     * [post]); one posted before they did is taken when it is the only card of ours up.
     */
    private fun orphan(c: Context, task: Int): android.service.notification.StatusBarNotification? {
        val ours = runCatching {
            c.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.filter { it.notification.channelId == CHANNEL }
        }.getOrNull().orEmpty()
        return ours.firstOrNull { it.notification.extras.getInt(EXTRA_TASK, -1) == task }
            ?: ours.singleOrNull()?.takeIf { !it.notification.extras.containsKey(EXTRA_TASK) }
    }

    /**
     * The mini program's task back in front, over its own app's task so that Back from it lands in
     * the app, and the app itself if the task is gone.
     *
     * The app's task is moved forward first, and the mini program's without [MOVE_TASK_WITH_HOME]:
     * that flag brings the task's home along, and the home of an appbrand task is the launcher -
     * tapping the island opened the mini program over the desktop, so Back took the user out of 微信
     * altogether (2026-10-08, the top the module saw right after a tap was com.miui.home/.launcher).
     */
    private fun open(c: Context, i: Intent) {
        // Where the tap should go, in order of how much it is *this* card's: the task the card was
        // posted for, then the task this process is tracking, then nothing.
        //
        // The first is the faithful one, but it is not always still there: a SystemUI restart takes
        // the parked mini program's task with it and the app makes another for the same page
        // (measured 2026-10-10 - 44999 before the restart, 45007 after), while the card that is up
        // is the older one, and tapping it has to land somewhere. It did not: the only task left to
        // try was the one from the card, so the button fell through to opening 微信 itself.
        val tapped = i.getIntExtra(EXTRA_TASK, -1)
        val page = cards[tapped]?.page
        val tasks = listOf(tapped, PickupCodeIsland.trackedTask()).filter { it >= 0 }.distinct()
        val pkg = i.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotEmpty() } ?: page?.host ?: WECHAT
        val scheme = i.getStringExtra(EXTRA_SCHEME).orEmpty().ifEmpty {
            if (pkg == ALIPAY) page?.scheme.orEmpty() else ""
        }
        // The one path in this file with nothing else to go on when it does not work.
        Xp.log(TAG + "tap: pkg=$pkg tasks=$tasks scheme=${scheme.isNotEmpty()} known=${page != null}")
        if (PickupPark.isParked(tapped)) {
            // §6.5: the task goes back to the display the user can see before it is brought to the
            // front - moved to the front while parked, it would come up on the hidden one. The wait on
            // the order goes on: the user looking in is not the order being over (ColorOS keeps its
            // task through it), and once they are out again its next round moves the page back.
            PickupObserver.handBack(tapped, "the island was tapped") { openNow(c, tasks, pkg, scheme) }
        } else {
            openNow(c, tasks, pkg, scheme)
        }
    }

    // Runs in SystemUI, which holds REORDER_TASKS; this APK's manifest is not the one asked.
    @android.annotation.SuppressLint("MissingPermission")
    private fun openNow(c: Context, tasks: List<Int>, pkg: String, scheme: String) {
        // 支付宝 runs a mini program inside its own app's task, and by the time the island is
        // tapped that task is usually not sitting on the mini program any more - so moving the
        // task to the front lands on whatever is, which is the app's own home (measured 2026-10-09:
        // tapping the order button on a 蜜雪冰城 code opened 支付宝 itself). The page's own launch
        // url has no such problem: it names the mini program, and asking for it again re-opens that
        // page - warm, because 支付宝 keeps the mini program alive.
        if (scheme.isNotEmpty() && startScheme(c, scheme, pkg)) {
            collapse(c)
            return
        }
        val am = c.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val running = runCatching { am.getRunningTasks(64) }.getOrDefault(emptyList())
        // The first of the candidates that is still running, and is moved: the app's own task first,
        // then the mini program's, so that Back lands in the app rather than out of it.
        var tried = 0
        val moved = tasks.any { target ->
            if (running.none { it.taskId == target }) return@any false
            tried++
            runCatching {
                running.firstOrNull { it.taskId != target && it.baseActivity?.packageName == pkg }
                    ?.let { am.moveTaskToFront(it.taskId, 0) }
                am.moveTaskToFront(target, 0)
            }.isSuccess
        }
        if (!moved) {
            Xp.log(TAG + "tap: none of $tasks running for $pkg (tried $tried), opening the app itself")
            runCatching {
                c.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }.onFailure { Xp.log(TAG + "$pkg not opened: $it") }
        }
        collapse(c)
    }

    /**
     * The island back down to its capsule, which the system does not do for this tap on its own.
     *
     * 小爱's card is a RemoteViews and the plugin watches its clicks
     * (`FocusNotifPreHandler.handleRemoteViewClick`) - but only collapses when the PendingIntent is
     * an *Activity* one, and this one cannot be: the tap has to run this module's own code (bring
     * the mini program's task forward, or hand 支付宝 back its page's launch url), and that needs a
     * broadcast. A broadcast PendingIntent only opens the app, so the island stayed expanded over
     * it (2026-10-09). The broadcast sent here is the very one the plugin's own Activity branch
     * sends.
     *
     * Nothing else collapses it: the `expandedTime` timer returns early once the user has expanded
     * the island by hand (`DynamicIslandSafeguardsController.delayCollapsed$lambda$3`), and a
     * re-post of the same notification reuses the island's view with its expanded state intact.
     */
    private fun collapse(c: Context) {
        runCatching { c.sendBroadcast(Intent(ACTION_COLLAPSE_ISLAND)) }
            .onFailure { Xp.log(TAG + "island not collapsed: $it") }
    }

    /**
     * The page's own launch url, handed back to the app that gave it.
     *
     * [pkg] is the app the card was posted for, not the tracker's host: after a SystemUI restart the field is
     * back at its default, and naming the wrong package here fails the launch silently.
     */
    private fun startScheme(c: Context, scheme: String, pkg: String): Boolean = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(scheme))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Named, so nothing else is offered and nothing needs to be visible to this process.
            .setPackage(pkg))
    }.onFailure { Xp.log(TAG + "scheme not opened: $it") }.isSuccess

    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(c: Context, read: PickupParse.Result, card: Card, fresh: Boolean) {
        val page = card.page
        // ColorOS's 「the code is coming」 card: no code yet, so the card says that where the code goes.
        val waiting = read.code.isEmpty()
        val r = if (waiting) read.copy(code = "等待出码") else read
        val brand = page.brand
        val oemRule = page.rule
        val host = page.host
        val task = page.task
        receivers(c) // already on at startup; this is for the path where that had no Context yet
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "取餐码",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val pic = icon(c, page)
        // ColorOS's recognizer names the drink; this file's reading of the page names the shop. The
        // second line leads with the drink, the way ColorOS's own card does, and falls back to the
        // shop for the brands whose pages carry no product name (蜜雪冰城's, for one).
        // The page's own words first, the recognizer's verdict only where the page has none: its
        // three values cannot say 「已准备完毕」, and its middle one is what it defaults to when it
        // read nothing it knows (see [oemTitle]). Both mean the same thing to a reader - what the
        // order is doing - so the page, which has the finer vocabulary, is the one to hear.
        // The order's state, and how long it has left where its page says (「制作中 约5分钟」).
        val title = (if (waiting) r.label else listOfNotNull(r.status ?: oemTitle(r) ?: r.label, r.waitTime)
            .joinToString(" "))
        // 「麦当劳示例广场餐厅」 under 麦当劳 is 「示例广场餐厅」.
        val store = r.store?.removePrefix(brand)?.trimStart('（', '(', ' ')?.ifEmpty { null }
        // The rules' own `<show_meal_name>`, which says whether the meal's name is shown at all;
        // off, the shop stands in its place - the same thing the card already does for the brands
        // whose pages carry no product name. Every rule for a page this module reads sets it true
        // today (the single exception, 肯德基's own app, is a path this module does not read).
        val meal = if (oemRule?.showMealName == false) null else r.product?.ifEmpty { null }
        val detail = meal ?: store
        val content = listOfNotNull(brand, detail).joinToString(" · ")
        // The animated drink picture, as an Icon for the param's own icon slot: an Icon is
        // resolved and drawn by the system, which is the only route by which an animated WebP can
        // play here - a RemoteViews ImageView never plays one, however it is handed over.
        // The brand's logo, for the slot the system draws itself (the island's icon and the lock
        // screen's capsule); the card's own picture is the drink, and that one is set by the card.
        val modelIcon = PickupCard.logoIcon(oemRule)
        // The island, in the shape the system's own renderer reads. Everything here comes out of
        // the island plugin (`miui.systemui.dynamicisland`): `IslandTemplateFactory.chooseModule`
        // picks the left area by `imageTextInfoLeft.type` (1 is the picture-and-text module, 5 the
        // icon-and-fixed-width-digit one, anything else throws IslandParamsException), and the right
        // area by `imageTextInfoRight.type` when there is one - which is why there is not, here; it
        // would win over `textInfo`.
        //   param_island { islandProperty, islandPriority, highlightColor,
        //                  bigIslandArea { imageTextInfoLeft {type,picInfo,textInfo{title,showHighlightColor}},
        //                                  textInfo {title,showHighlightColor} },
        //                  smallIslandArea { picInfo {type,pic} } }
        // The two halves say different things: the left is ours - our picture and the order's state,
        // which is the text that changes as the order moves (show() keys the post on the status) -
        // and the right is the official one, the code itself in the brand's colour. `highlightColor`
        // is where that colour comes from (`IslandTextViewHolder` paints `textInfo` with it when
        // `showHighlightColor` is true, at 80% alpha); without it the code reads white.
        // `smallIslandArea` stays wrapped in a `picInfo`, as 小爱's own notifications write it and as
        // the plugin's model (`SmallIslandArea` has `picInfo`/`combinePicInfo` and nothing else)
        // reads it. Flat `{type,pic}` parses to nothing and leaves the small island without a picture.
        val accent = colour(oemRule?.pickupColor, "#FFFFFF")
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("highlightColor", accent)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
                    .put("textInfo", JSONObject()
                        .put("title", title)
                        .put("showHighlightColor", false)))
                .put("textInfo", JSONObject()
                    .put("title", r.code)
                    .put("showHighlightColor", true)))
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", BUSINESS)
            .put("scene", "template_v2")
            .put("ticker", "${r.label} ${r.code}")
            .put("tickerPic", PIC)
            .put("aodTitle", "${r.label} ${r.code}")
            .put("aodPic", PIC)
            .put("enableFloat", fresh)
            // The island's own float, a second gate: FocusNotifPreHandler writes it as
            // miui.island.firstFloat from this key and defaults it to false when the key is missing,
            // and FocusNotificationController floats only when it is true. Left out, an update never
            // brings the island up - a new code in the same notification (id 1241 is one key) showed
            // in the shade alone. 地铁乘车码's card sets the same key the same way.
            .put("islandFirstFloat", fresh)
            .put("updatable", true)
            // No `outEffectSrc`. It is the ring of light around the drawn card - the plugin copies
            // it to `miui.effect.src` (`TemplateFactoryV3`) and SystemUI's glow layer draws it - and
            // AICR sets `outer_glow` when it posts. The pickup card does not have it: 小爱's own
            // focus notification carries no effect at all, and with this key set the card came up
            // inside an orange-and-blue halo that the official one never has.
            .put("param_island", island)
            // ColorOS animates the drink picture through this slot; the reference implementation
            // does the same, with the Icon under PIC_MODEL in miui.focus.pics and `autoplay` set.
            .put("iconTextInfo", JSONObject()
                .put("title", r.code)
                .put("content", detail)
                .put("subContent", title)
                .put("animIconInfo", JSONObject()
                    .put("type", if (modelIcon != null) 3 else 0)
                    .put("src", PIC_MODEL)
                    .put("autoplay", true)
                    .put("loop", false)
                    .put("number", 0)))
            .put("title", r.code)
            .put("content", content)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", r.code)
                .put("content", content)
                .put("subContent", title))
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
        // The always-on picture is the drink's own still where ColorOS ships one for this brand, and
        // the logo where it does not - the same file the card's left half is drawn from. Set here,
        // before the param is written out: the notification carries a string, not this object.
        val aod = PickupArt.aod(oemRule, PickupArt.model(oemRule, brand, r.product, r.temperature))
            ?.let { PickupArt.bitmap(it, 200) }
        if (aod != null) param.put("aodPic", PIC_AOD)
        val extras = Bundle()
        // Its task, for a tap that comes after a SystemUI restart to find it by (see [orphan]).
        extras.putInt(EXTRA_TASK, task)
        extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        // And under `custom`, which is the key the island reads once the notification carries a
        // card - and here the object has to go in **unwrapped**. The plugin picks the key by
        // whether there is a custom view and unwraps `param_v2` on only one of the two paths
        // (`DynamicIslandUtils.resolveFocusParam`):
        //     string = extras.getString(hasCustomFocusView(sbn) ? "…param.custom" : "…param")
        //     return (hasCustomFocusView || obj == null || obj.optJSONObject("param_v2") == null)
        //            ? obj : obj.optJSONObject("param_v2")
        // Without a card it unwraps for us, which is why the wrapped form worked while the card was
        // off. With a card it hands back exactly what was written, and every reader downstream
        // (`FocusNotifUtils`, `FocusNotifPreHandler`, `TemplateFactoryV3`) then looks for
        // `param_island` on what it was given. A wrapped string puts `param_island` one level too
        // deep: the island loses both halves and falls back to the notification's own app icon.
        // This is also how the reference implementation writes it - AICR's `jd3.b` puts
        // `param_island` straight into `.custom` and never writes `miui.focus.param` at all.
        extras.putString("miui.focus.param.custom", param.toString())
        extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(PIC, Icon.createWithBitmap(pic))
            if (aod != null) putParcelable(PIC_AOD, Icon.createWithBitmap(aod))
            if (modelIcon != null) putParcelable(PIC_MODEL, modelIcon)
        })
        // The tap carries the task it is about, its app, and (for 支付宝) the mini program's launch
        // url, so that it still knows where to go after a SystemUI restart has emptied this process
        // of everything it read - see [EXTRA_TASK]. FLAG_UPDATE_CURRENT keeps them current: the same
        // PendingIntent goes out with every card, and the one in the notification is the last card's.
        // The notification this post goes out under - see the end of this function - decided here,
        // because each card's intents are told apart by it: one set of request codes per card, or a
        // second order's tap would carry the first one's task.
        val tag = if (fresh || card.tag == null) "mc-" + SystemClock.elapsedRealtime() else card.tag
        val id = if (fresh || card.tag == null) ID + (++ids % IDS) else card.id
        val tap = PendingIntent.getBroadcast(c, id * 4, Intent(ACTION_OPEN).setPackage(SYSUI)
            .putExtra(EXTRA_TASK, task)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_SCHEME, if (host == ALIPAY) page.scheme.ifEmpty { null } else null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Ready to collect, the pill says so and takes the card down; before that it opens the order.
        val ready = PickupParse.isReady(r.status) && r.status !in PickupParse.DONE
        val action = if (ready) PickupCard.PICKED else PickupCard.OPEN
        val actionTap = if (!ready) tap else PendingIntent.getBroadcast(c, id * 4 + 2,
            Intent(ACTION_PICKED).setPackage(SYSUI).putExtra(EXTRA_TASK, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val gone = PendingIntent.getBroadcast(c, id * 4 + 1, Intent(ACTION_GONE).setPackage(SYSUI).putExtra(EXTRA_TASK, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(pic))
            .setContentTitle("${r.label} ${r.code}")
            .setContentText(listOf(content, title).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!fresh)
            .setShowWhen(false)
            .setAutoCancel(false)
            // ColorOS keeps an order being waited on up to `seeding_max_time` (2 hours), and one
            // with no state of its own `seeding_max_time_unfinish` (30 minutes).
            .setTimeoutAfter(if (r.status != null && r.status !in PickupParse.DONE) WAITED else LIFE)
            .setDeleteIntent(gone)
            .setContentIntent(tap)
            .addExtras(extras)
            .build()
        // The card ColorOS draws, in place of the focus template the system draws. Attached only
        // when it was actually built: a RemoteViews that fails to inflate takes the whole island
        // with it, and a card with a missing layout is worse than the template.
        if (PickupCodeIsland.sCard) {
            // A card that floats up comes in faint and is faded on the live view; see [fadePictureIn].
            val alpha = if (fresh) FADE_FROM else 1f
            runCatching {
                PickupCard.build(c, oemRule, r.code, meal, r.temperature, store, brand, tap,
                    alpha, false, action, actionTap) to
                    PickupCard.build(c, oemRule, r.code, meal, r.temperature, store, brand, tap,
                        alpha, true, action, actionTap)
            }.onSuccess { (day, night) ->
                // Every key Xiaomi's own focus notification sets, which is what this copies (the
                // assistant's card builder, `jd3.b`): the card in both themes, the always-on form,
                // and the island's expanded form.
                //
                // `miui.focus.rv.deco.*` and `miui.focus.rv.tiny*` are deliberately NOT here: that
                // same code sets those only when `MiuiMultiDisplayTypeInfo.isFlipDevice` is true -
                // they are the flip's cover-screen and its decorated window. On a normal phone the
                // card that comes up when the island is tapped is `miui.focus.rv`, which is why
                // posting only the deco keys left it looking like the plain template.
                n.extras.putParcelable("miui.focus.rv", day)
                n.extras.putParcelable("miui.focus.rvNight", night)
                n.extras.putParcelable("miui.focus.rv.fullAod", night)
                n.extras.putParcelable("miui.focus.rv.island.expand", night)
            }.onFailure { Xp.log(TAG + "card not built: $it") }
        }
        // The back screen, if this phone has one (2026-10-09). Xiaomi's own pickup notification
        // carries exactly these keys and nothing else does: `miui.rear.rv` is the card the back
        // screen draws - our card, in the shape that screen's own card is cut to, so it brings our
        // drink and its animation with it - and `miui.rear.param` is what names the business the
        // back screen's allow-list is keyed on. 小爱's own notification (com.miui.voiceassist,
        // business `memory`) is the shape being copied here.
        //
        // Its own card rather than the island's: the two screens are not the same shape, and the
        // one above is drawn by SystemUI around the island while this one is the whole card.
        if (rearDisplay(c) != null) {
            runCatching {
                PickupCard.rear(c, oemRule, r.code, meal, r.temperature, store, brand, tap, action, actionTap)
            }.onSuccess { card ->
                // Both keys get the same card: the rear one is dark to begin with, and the screen
                // it goes on is the one that is always at least a little dark.
                n.extras.putParcelable("miui.rear.rv", card)
                n.extras.putParcelable("miui.rear.rvAOD", card)
                n.extras.putString("miui.rear.param", JSONObject()
                    .put("rear_param_v1", JSONObject()
                        // `memory`, which is what 小爱's own pickup notification calls itself, and
                        // not `pickup_code`: the back screen builds a widget from a *business it
                        // knows* (its list is the whitelist's - memory, foodDelivery, music, ...),
                        // and one it does not know gets no widget at all, RemoteViews or no. The
                        // card under it is still ours - `miui.rear.rv` above is what it draws.
                        .put("business", REAR_BUSINESS)
                        .put("index", 1))
                    .toString())
            }.onFailure { Xp.log(TAG + "rear card not built: $it") }
        }
        // A post that must float the island gets a notification key of its own. MIUI keeps per-key
        // state for a focus notification (FocusNotificationController's hasEverExpandedKeys and its
        // island-data map), and with one fixed key - no tag, the same for every order - the island
        // came up for a key once and never again after it had gone: a second code, or one re-read
        // after the user tapped the island away, was left in the shade alone while the module kept
        // posting. A tag makes that a new key, and the one left behind is dropped first (two focus
        // notifications at once would be two islands). A quiet update keeps the key it was posted
        // under, so it stays one island being updated.
        //
        // The same rule carries the *id*, which the back screen's widget is keyed on: a fresh code
        // is a new id and so a new widget there, which is the only post that screen slides out by
        // itself - see [ID]. The card before it is swept first, since its widget only goes when its
        // own notification does.
        runCatching {
            // The first post of this process takes down what an earlier run left; after that a fresh
            // post replaces this card's own notification only - the other orders' cards stay.
            if (!swept) {
                swept = true
                sweep(nm)
            } else if (tag != card.tag) {
                card.tag?.let { nm.cancel(it, card.id) }
            }
            nm.notify(tag, id, n)
        }.onSuccess {
            card.tag = tag
            card.id = id
        }
        Xp.log(TAG + (if (fresh) "up: " else "updated: ") + brand + " (tag=${tag ?: "-"})")
    }

    /**
     * The mini program's own icon, which its task carries (TaskDescription: in memory, or the file
     * the system keeps it in); the brand's logo when the task has none; and the app's own icon only
     * when neither is there.
     *
     * 支付宝 runs a mini program inside the app's own task, and that description carries no icon at
     * all - so the app's own mark is what the small island and the lock screen's capsule showed for
     * a 支付宝 order: 支付宝's logo on 蜜雪冰城's pickup (2026-10-09). By then the brand is known,
     * and its logo is the picture ColorOS puts in that slot too.
     */
    private fun icon(c: Context, page: Page): Bitmap {
        val task = page.task
        val oemRule = page.rule
        val host = page.host
        val fromTask = runCatching {
            @Suppress("DEPRECATION")
            val td = c.getSystemService(ActivityManager::class.java).getRunningTasks(64)
                .firstOrNull { it.taskId == task }?.taskDescription ?: return@runCatching null
            val cls = td.javaClass
            (cls.getMethod("getInMemoryIcon").invoke(td) as? Bitmap) ?: run {
                val file = cls.getMethod("getIconFilename").invoke(td) as? String ?: return@run null
                cls.getMethod("loadTaskDescriptionIcon", String::class.java, Int::class.java)
                    // UserHandle's hash is its user id.
                    .invoke(null, file, android.os.Process.myUserHandle().hashCode()) as? Bitmap
            }
        }.onFailure {
            if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: $it") }
        }.getOrNull()
        if (fromTask != null) return fromTask
        val logo = PickupArt.logo(oemRule)?.let { PickupArt.bitmap(it, 144) }
        if (logo != null) {
            if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, the brand's logo instead") }
            return logo
        }
        if (!iconSaid) { iconSaid = true; Xp.log(TAG + "task icon: none, $host's instead") }
        val d = c.packageManager.getApplicationIcon(host)
        val size = 144
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(it))
        }
    }
}
