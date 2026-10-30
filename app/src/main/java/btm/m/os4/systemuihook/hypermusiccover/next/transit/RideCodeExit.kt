// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.ProbeGuard
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

/**
 * The way out of a subway station, seen from SystemUI: for the trip card's 到站 (AmapTransitShare)
 * and for the ride-code island's trip (MetroCodeIsland, in 小爱建议).
 *
 * ColorOS ends a subway's 到站 early when the rider is on the way out
 * (GaoDePtRideCodeDeferBindManager.n), from PublicTransportRideCodeAppSwitchHub: a ride code's
 * page or mini program coming to the front (OplusAppSwitchManager), or OPPO's wallet saying a
 * transit card was swiped (`content://com.finshell.wallet/bus/swipe`). Neither is on HyperOS, so:
 *
 *   - the ride code: MiuiTopActivityObserver.updateTopActivity, SystemUI's own record of the
 *     front activity, matched against ColorOS's list of ride-code pages ([PAGES]). ColorOS tells a
 *     ride-code mini program from any other by its appId, which the system hands only OPPO; here
 *     a 微信 mini program is any one, and 支付宝 at all is its ride code.
 *   - the card: 小米智能卡 posts the fare a station took when the rider leaves it - title the
 *     card, text 「大学城南 -> 大学城南：2.00元」 (seen 2026-10-07). The NFC event before it
 *     goes to 小米智能卡 alone (NfcEventMonitorService, by startService).
 *
 * 高德 is told only while it has a trip on the card, and takes it only while a subway's 到站 is
 * held, as ColorOS listens only then. 小爱建议 is told always: its trip needs no navigation.
 */
internal object RideCodeExit {

    private const val TAG = "MCAmap: exit: "
    private const val NOTIF_COLLECTION =
        "com.android.systemui.statusbar.notification.collection.NotifCollection"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"

    private const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val WECHAT = "com.tencent.mm"
    private const val WECHAT_MINI = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
    private const val MI_CARD = "com.miui.tsmclient"

    /** PublicTransportRideCodeTarget's ride-code apps (com.oplus.sdp.mb.a), by their page. */
    private val PAGES = setOf(
        "com.unionpay/com.unionpay.activity.react.UPActivityReactNative",
        "enfc.metro/enfc.metro.main.MainActivity",
        "com.app.shanghai.metro/com.app.shanghai.metro.ui.main.MainActivity",
        "com.infothinker.gzmetro/com.infothinker.gzmetro.tabGroup.UIMainActivity",
        "com.chinarainbow.tft/com.chinarainbow.tft.mvp.ui.activity.TftQrcodeActivity",
        "com.nsmetro.shengjingtong/com.nsmetro.shengjingtong.core.home.activity.QrCodeGoTrainActivity",
    )

    /** A station's fare: 「<in> -> <out>：<amount>元」. Only the two stations are passed on. */
    private val FARE = Regex("(.+) -> (.+)[：:].*元")

    private val main = Gen.handler()
    private var lastTop: ComponentName? = null

    /** SystemUI's loader. Each hook stands on its own. */
    fun install(cl: ClassLoader) {
        runCatching {
            Xp.hookAll("sysui.ride_exit.top_activity", Xp.findClass(TOP_OBSERVER, cl), "updateTopActivity") { chain ->
                val out = chain.proceed()
                runCatching {
                    val state = Xp.getObjectField(chain.thisObject, "mState")
                    val top = Xp.getObjectField(state, "topActivity") as? ComponentName
                    main.post { front(top) }
                }
                out
            }
        }.onFailure { Xp.log(TAG + "front activity not watched: $it") }
        runCatching {
            // postNotification(Ranking, StatusBarNotification): the collection's own entry for a
            // post (this build has no onNotificationPosted of its own; its handler calls this).
            Xp.hookAll("sysui.ride_exit.notification", Xp.findClass(NOTIF_COLLECTION, cl), "postNotification") { chain ->
                val out = chain.proceed()
                val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                if (sbn?.packageName == MI_CARD) {
                    val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                    val fare = text?.let { FARE.matchEntire(it) }
                    if (fare != null) {
                        val (from, to) = fare.destructured
                        main.post { tell("card", "card: $from -> $to", from.trim(), to.trim()) }
                    }
                }
                out
            }
        }.onFailure { Xp.log(TAG + "card fares not watched: $it") }
    }

    private fun front(top: ComponentName?) {
        if (top == null || top == lastTop) return
        lastTop = top
        val rideCode = when (top.packageName) {
            ALIPAY -> true
            WECHAT -> top.className.startsWith(WECHAT_MINI)
            else -> top.flattenToString() in PAGES
        }
        if (rideCode) tell("code", "ride code: " + top.flattenToShortString())
    }

    /**
     * [how] is `card` (a fare taken, so certainly out; [from] and [to] its gates, for the commute
     * the trip is learned as) or `code` (a ride code opened).
     */
    private fun tell(how: String, why: String, from: String = "", to: String = "") {
        val ctx = Main.appContext() ?: return
        Xp.log(TAG + why)
        runCatching {
            ProbeGuard.send(ctx, Intent(MetroCodeIsland.ACTION_TRIP).setPackage(MetroCodeIsland.PKG)
                .putExtra("do", "exit")
                .putExtra("how", how)
                .putExtra("from", from)
                .putExtra("to", to))
        }.onFailure { Xp.log(TAG + "小爱建议 not told: $it") }
        if (!AmapTransitScene.INSTANCE.tripUp()) return
        runCatching {
            ProbeGuard.send(ctx, Intent(AmapNavScene.AMAP_PROBE).setPackage(AmapNavScene.PKG)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("transit", "exited")
                .putExtra("why", why))
        }.onFailure { Xp.log(TAG + "高德 not told: $it") }
    }
}
