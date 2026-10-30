// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.LiveAlertScene;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.ProbeGuard;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 高德's walking and cycling navigation map, the first immersive page.
 *
 * 高德 17.00.0.2005 on this phone is byte-identical to the ColorOS build apart from its channel
 * id, service included, and the service's side of the protocol has no vendor test in it. What it
 * needs before it can draw is 高德's own navigation page calling
 * NativesModuleImmerseNavi.init() - it does here too (2026-09-29: walking navigation, init with
 * sceneType 4 / pageType 3, the page back in ~190ms). AmapImmerse, in 高德's process, watches that
 * call and the page's destroy and arms and disarms this scene over the SystemUI probe
 * ({@code op immersive --es id amap-nav --es do arm|disarm}).
 *
 * Opened from 高德's focus island - protocol 1, notification id 1236, re-posted every second
 * during the navigation (see the amap-nav-island-focus-notification notes).
 */
public final class AmapNavScene extends LiveAlertScene {

    public static final String ID = "amap-nav";
    public static final String PKG = "com.autonavi.minimap";
    /** AmapImmerse's receiver in 高德's main process. */
    static final String AMAP_PROBE = "btm.m.os4.systemuihook.hypermusiccover.next.AMAPPROBE";

    public static final AmapNavScene INSTANCE = new AmapNavScene();

    @Override
    public boolean isNavigation() {
        return true;
    }

    private AmapNavScene() {
        super(ID, PKG, "com.autonavi.minimap.immersenavi.AMapImmerseNaviService", "536879184");
    }

    /**
     * 高德's own message on the same Messenger: data{openOverview} 1 = the whole route
     * (isPreview true to its page), 2 = back to following you. ColorOS's card sends it from the
     * button beside the map; here the turn arrow in the island's row does.
     */
    private static final int MSG_OVERVIEW = 10000003;

    /**
     * How long 高德's camera takes to get there. A switch sent while the last one is still moving
     * loses its zoom and only turns the map (2026-09-30: taps ~1-1.5s apart, the map turned
     * north-up and back but stayed at the same scale), so switches are spaced at least this far
     * apart and the ones in between are folded into the last.
     */
    private static final long OVERVIEW_SETTLE_MS = 1500L;

    /** What the taps asked for: the whole route. */
    private boolean mOverview;
    /** What 高德's page was last told, and when. The page is 高德's; this is only our side. */
    private boolean mSentOverview;
    private long mSentAt;
    private final Handler mMain = Gen.handler();
    private final Runnable mFlush = this::flushOverview;

    /**
     * 高德's islands, but not the trip's own (AmapTransitIsland.ID): a tap on that one opens no
     * page while the trip's page is off, rather than this map in its place.
     */
    @Override
    public boolean servesKey(String key) {
        return !AmapTransitScene.isTripKey(key);
    }

    /** ColorOS's host grows the map in from 1.1 (a6.l, type 1). */
    @Override
    protected float enterScale() {
        return 1.1f;
    }

    @Override
    public String rowTapTarget() {
        return "focus_large_icon";
    }

    @Override
    public void onRowTap() {
        mOverview = !mOverview;
        flushOverview();
    }

    /**
     * A page re-rendered after a let-go is a new one, following you as 高德 starts it: told again
     * what it was showing.
     */
    @Override
    protected void onPage() {
        mSentOverview = false;
        mSentAt = 0L;
        flushOverview();
    }

    /** The navigation ended: the next one starts following. */
    @Override
    public void setArmed(boolean armed) {
        if (!armed) {
            mMain.removeCallbacks(mFlush);
            mOverview = false;
            mSentOverview = false;
            mSentAt = 0L;
        }
        super.setArmed(armed);
    }

    /** Tells 高德 what the taps asked for, once its camera has had the time to finish the last. */
    private void flushOverview() {
        mMain.removeCallbacks(mFlush);
        if (mOverview == mSentOverview) return;
        long wait = mSentAt + OVERVIEW_SETTLE_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            mMain.postDelayed(mFlush, wait);
            Xp.log("MCImmersive: " + ID + ": overview " + (mOverview ? "on" : "off")
                    + " held " + wait + "ms for the last switch to land");
            return;
        }
        Bundle data = new Bundle();
        data.putInt("openOverview", mOverview ? 1 : 2);
        boolean sent = send(MSG_OVERVIEW, data);
        if (sent) {
            mSentOverview = mOverview;
            mSentAt = SystemClock.uptimeMillis();
        }
        Xp.log("MCImmersive: " + ID + ": overview " + (mOverview ? "on" : "off")
                + (sent ? "" : " NOT sent (not connected)"));
    }

    @Override
    public String describe() {
        return super.describe() + " overview=" + mOverview + " sent=" + mSentOverview;
    }

    /**
     * Asks 高德 whether a navigation page is up, for a SystemUI that started in the middle of one:
     * the init that would have armed this happened before this process existed. AmapImmerse
     * answers with the same arm it sends on init. No answer is the answer - 高德 is not running,
     * or has no page up.
     */
    public void ask(Context ctx) {
        try {
            ProbeGuard.send(ctx, new Intent(AMAP_PROBE).setPackage(PKG).putExtra("ask", true));
        } catch (Throwable t) {
            Xp.w("MCImmersive: " + ID + ": ask failed: " + t);
        }
    }
}
