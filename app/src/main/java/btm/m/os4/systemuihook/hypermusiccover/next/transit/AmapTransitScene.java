// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit;
import btm.m.os4.systemuihook.hypermusiccover.next.ClockViews;
import btm.m.os4.systemuihook.hypermusiccover.next.ClockCollapse;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.CountdownScene;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveScene;
import btm.m.os4.systemuihook.hypermusiccover.next.island.LockIslands;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import btm.m.os4.systemuihook.hypermusiccover.next.PageSpring;
import btm.m.os4.systemuihook.hypermusiccover.next.ProbeGuard;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德's bus and subway trip as a page behind the lock screen: ColorOS 17's lock-screen card for
 * it (SceneService's 536879317, an immersive template card), drawn here.
 *
 * AmapTransitShare, in 高德's process, sends the trip as ColorOS's GaoDePtIntentEntity
 * ({@code op transit}); AmapTransitCard works out the card from it, the same card the island is
 * posted from. The card's own words are the focus notification's, so the page says what that
 * cannot (Route):
 *   - where the trip goes, how long and how far, how many changes;
 *   - the trip leg by leg - each walk's length, each line in its colour, the one ridden now
 *     filled;
 *   - the leg ridden now stop by stop: passed, at or coming to, getting off and what comes then
 *     (the line changed to, or the exit), the rest folded;
 *   - between them, the landmark ColorOS shows: in transit the one by the stop the card names (ya.d),
 *     at 到站 the exit's, else the city's, else the nation's (ya.b.O), from OPPO's CDN
 *     (AmapTransitLandmarks). Where ColorOS shows none the national picture stands in, so
 *     the page keeps a ground.
 *
 * Ready while 高德's trip has a card that is not a walk (a walk is 高德's own map, AmapNavScene);
 * it sits before AmapNavScene in ImmersiveHost.SCENES and claims 高德's island only while ready.
 *
 * The ground - graphite under the line's glow, and the still landmark - is a picture under the
 * shade window (CountdownScene.GroundSurface): the lock screen's glass rows sample what is behind
 * the window, never a view in it. The moving landmark and the words are a view over it.
 *
 * Probe: {@code op transit} - the state; {@code --es json '<entity>'} feeds one in by hand,
 * {@code --ez demo true} a made-up ride past 天安门, {@code --es do end} ends it.
 */
public final class AmapTransitScene implements ImmersiveScene {

    static final String ID = "amap-transit";

    @Override
    public boolean isNavigation() {
        return true;
    }
    static final String PKG = AmapNavScene.PKG;
    public static final AmapTransitScene INSTANCE = new AmapTransitScene();

    private static final String TAG = "MCImmersive: " + ID + ": ";

    /**
     * How long a card lasts without another word: GaoDePtNaviSceneRouter.h - the trip's end 30 s,
     * a 到站 15 min, the rest 30 min. AmapTransitShare keeps the same times, but in 高德's process,
     * which Greezer freezes once 高德 is in the background (after the trip, say), so its timer can
     * stop running; the page keeps its own.
     */
    private static long staleMs(String status) {
        if (AmapTransitCard.ARRIVE_FINAL_DESTINATION.equals(status)) return 30_000L;
        if (AmapTransitCard.ARRIVE_LINE_DESTINATION.equals(status)) return 15L * 60_000L;
        return 30L * 60_000L;
    }

    private final Handler mMain = Gen.handler();

    private AmapTransitCard.Trip mTrip;
    private AmapTransitCard.Card mCard;
    private Art.Pick mPick = Art.Pick.NONE;
    private long mTripAt;
    private int mShares;
    private String mEndedBy;

    private TransitView mView;
    private CountdownScene.GroundSurface mGround;
    private boolean mShown;
    private boolean mDozing;
    /** Where the words start on the screen, under the clock (wordsTop); NaN before a draw. */
    private float mTop = Float.NaN;
    /** Where the page ends on the screen, above the trip's row (pageLimit). */
    private float mLimit = Float.NaN;
    /** Where the view put the landmark's box on the screen, for the ground's still of it. */
    private RectF mArtBox;
    /** And where it fades in from under the words (the view's fadeFrom / fadeTo), on the screen. */
    private float mFadeFrom = Float.NaN;
    private float mFadeTo = Float.NaN;

    private AmapTransitScene() {
    }

    // ---------------------------------------------------------------- the switch

    /**
     * The app's 「高德公交地铁」 switch: off, neither this page nor the trip's island in 高德
     * (AmapTransitShare.setEnabled) is put up. Held here, in Main's state file; 高德 is told.
     */
    public static volatile boolean sOn = false;

    /** The switch moved ({@code op transitcfg --ez on}): 高德 told, and a page up taken down. */
    public void setOn(Context ctx, boolean on) {
        sOn = on;
        Xp.log(TAG + "switch " + (on ? "on" : "off"));
        if (!on) mMain.post(() -> end("switch off"));
        tellAmap(ctx);
    }

    /** Tells 高德 the switch: when it asks, when SystemUI starts, when it changes. */
    public void tellAmap(Context ctx) {
        try {
            ProbeGuard.send(ctx, new Intent(AmapNavScene.AMAP_PROBE).setPackage(PKG)
                    .putExtra("transiton", sOn));
        } catch (Throwable t) {
            Xp.log(TAG + "switch not told: " + t);
        }
    }

    // ---------------------------------------------------------------- what 高德 says

    /** {@code op transit}, any thread. The answer is the state as it is now. */
    public String command(String json, String what, boolean demo) {
        if (!sOn) {
            // A 高德 that has not heard yet - started before SystemUI, or missed the change.
            if (json != null && !demo && Main.appContext() != null) tellAmap(Main.appContext());
            return "off " + describe();
        }
        if (demo) json = DEMO;
        if (json != null) {
            final AmapTransitCard.Trip t;
            try {
                t = AmapTransitCard.Trip.parse(new JSONObject(json));
            } catch (Throwable e) {
                Xp.log(TAG + "unreadable trip: " + e);
                return "unreadable: " + e + "\n" + describe();
            }
            mMain.post(() -> take(t));
        } else if ("end".equals(what)) {
            mMain.post(() -> end("高德"));
        }
        return describe();
    }

    /** Whether 高德 has a trip on the card now. Main thread. */
    boolean tripUp() {
        return mTrip != null;
    }

    private void take(AmapTransitCard.Trip t) {
        AmapTransitCard.Card c = AmapTransitCard.of(t);
        if (c == null) {
            end("no card for status " + t.status);
            return;
        }
        boolean was = ready();
        mShares++;
        mTrip = t;
        mTripAt = SystemClock.uptimeMillis();
        mEndedBy = null;
        mMain.removeCallbacks(mStale);
        mMain.postDelayed(mStale, staleMs(c.status));
        Art.Pick pick = Art.Pick.of(t, c);
        boolean newPicture = !pick.equals(mPick);
        // The landmark plays once for a new state - a new picture, milestone or stop - and not
        // for the same state again (a keepalive, a picture that arrived late).
        boolean again = mCard == null || newPicture || !mCard.sameAs(c);
        mCard = c;
        mPick = pick;
        Xp.log(TAG + c.kind + " " + c.status + ": " + c.primary + " | " + c.secondaryLine
                + c.secondary + " art=" + pick);
        if (mView != null) {
            mView.setCard(c, Route.of(t, c), again);
            if (newPicture) Art.request(mView.getContext(), pick, this::onArt);
            if (mDozing) ImmersiveHost.lift(mView);
        }
        if (was != ready()) ImmersiveHost.readyChanged(this);
    }

    private final Runnable mStale = () -> end("silence");
    private final Runnable mRepaint = this::paintGround;

    private void end(String by) {
        mMain.removeCallbacks(mStale);
        if (mTrip == null) return;
        boolean was = ready();
        mTrip = null;
        mCard = null;
        mPick = Art.Pick.NONE;
        mEndedBy = by;
        Xp.log(TAG + "ended by " + by);
        if (was) ImmersiveHost.readyChanged(this);
    }

    /** The pictures for the card they were asked for, once they are here; main thread. */
    private void onArt(Art.Set set) {
        if (!mPick.equals(set.pick)) return;
        if (mView != null) mView.setArt(set);
        if (mGround != null) paintGround();
    }

    // ---------------------------------------------------------------- the scene

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean serves(String pkg, boolean focus) {
        return focus && PKG.equals(pkg);
    }

    /** The trip's island is this page's while there is a card to show; the map's otherwise. */
    @Override
    public boolean servesKey(String key) {
        return ready() && isTripKey(key);
    }

    /**
     * The trip's own island (AmapTransitIsland.ID), not 高德's others: its walking island opens
     * the map (AmapNavScene). Claimed whole, an exchange between the two islands left this page
     * up for both. The key is the notification's, user|pkg|id|tag|uid.
     */
    static boolean isTripKey(String key) {
        if (key == null) return false;
        String[] parts = key.split("[|]");
        return parts.length > 2 && String.valueOf(AmapTransitIsland.ID).equals(parts[2]);
    }

    /**
     * A card that is not a walk's: a walk is 高德's own navigation, and its map (AmapNavScene) -
     * which the trip's island opens then, as ColorOS's walking card opens 高德's immersive map.
     */
    @Override
    public boolean ready() {
        if (!PAGE) return false;
        AmapTransitCard.Card c = mCard;
        return c != null && !AmapTransitCard.Card.KIND_WALK.equals(c.kind)
                && !AmapTransitCard.Card.KIND_WALK_NAVI.equals(c.kind);
    }

    /**
     * Whether the trip's island opens this page at all. Off for now (user, 2026-10-07): a tap on
     * the island leaves the lock screen as it was, its own wallpaper, until the page is reworked.
     * Off, it is never ready, so it is never prepared and its ground never drawn.
     */
    private static final boolean PAGE = false;

    @Override
    public void prepare(ViewGroup slot) {
        if (mView != null) return;
        Context ctx = slot.getContext();
        TransitView v = new TransitView(ctx);
        v.setVisibility(View.INVISIBLE);
        CountdownScene.GroundSurface g = new CountdownScene.GroundSurface(ctx);
        g.setVisibility(View.INVISIBLE);
        slot.addView(g, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slot.addView(v, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mView = v;
        mGround = g;
        AmapTransitCard.Card c = mCard;
        if (c != null) {
            v.setCard(c, Route.of(mTrip, c), false);
            Art.request(ctx, mPick, this::onArt);
        }
        Xp.log(TAG + "page made");
        mMain.post(() -> {
            if (mView == v) ImmersiveHost.contentChanged(this);
        });
    }

    @Override
    public boolean hasContent() {
        return mView != null;
    }

    @Override
    public void onShown(boolean shown, boolean dozing) {
        TransitView v = mView;
        if (v == null) return;
        if (shown != mShown) {
            v.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
            if (mGround != null) mGround.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        }
        if (!shown) mLimit = Float.NaN;
        mShown = shown;
        boolean was = mDozing;
        mDozing = dozing;
        v.setLive(shown && !dozing);
        if (was != dozing) paintGround();
    }

    @Override
    public void setFade(float alpha) {
        if (mView != null) mView.setFade(alpha);
        if (mGround != null) mGround.setFade(alpha);
    }

    @Override
    public void release() {
        TransitView v = mView;
        if (v == null) return;
        mView = null;
        mShown = false;
        mLimit = Float.NaN;
        mArtBox = null;
        mFadeFrom = Float.NaN;
        mFadeTo = Float.NaN;
        v.setLive(false);
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
        CountdownScene.GroundSurface g = mGround;
        mGround = null;
        if (g != null && g.getParent() instanceof ViewGroup) ((ViewGroup) g.getParent()).removeView(g);
        Xp.log(TAG + "released");
        ImmersiveHost.contentChanged(this);
    }

    /** Still, apart from the landmark, which is stopped in the doze. */
    @Override
    public boolean needsDozeBeat() {
        return false;
    }

    /** The page is its own ground; nothing to blur. */
    @Override
    public float[] sharpBand() {
        return null;
    }

    @Override
    public String describe() {
        AmapTransitCard.Trip t = mTrip;
        AmapTransitCard.Card c = mCard;
        StringBuilder sb = new StringBuilder();
        sb.append("shares=").append(mShares);
        if (t == null || c == null) {
            sb.append(" no trip").append(mEndedBy == null ? "" : " (ended by " + mEndedBy + ")");
        } else {
            AmapTransitCard.Leg l = t.current;
            sb.append(' ').append(c.kind).append(" status=").append(c.status).append(' ').append(c.page)
                    .append(" leg=").append(t.currentIndex()).append('/').append(t.navi.size())
                    .append(l == null ? "" : " " + l.lineName + " remain=" + l.remain + " via=" + l.via.size())
                    .append(" age=").append(SystemClock.uptimeMillis() - mTripAt).append("ms")
                    .append(" [").append(c.primary).append(" | ").append(c.secondaryLine)
                    .append(c.secondary).append("] capsule=[").append(c.leftLine).append(' ')
                    .append(c.leftWhite).append(" | ").append(c.rightLine).append(' ')
                    .append(c.rightWhite).append(c.rightGray).append(']');
            if (c.stations != null) {
                sb.append(" stations=");
                for (AmapTransitCard.Node n : c.stations) {
                    sb.append(n.name).append(n.transfer ? "⇄" : "").append(n.badge.isEmpty() ? "" : "[" + n.badge + "]")
                            .append(',');
                }
                sb.append(c.atStation ? " at" : " between");
            }
            sb.append(" art=").append(mPick);
        }
        sb.append(" shown=").append(mShown).append(" dozing=").append(mDozing)
                .append(" top=").append(Math.round(mTop)).append(" limit=").append(Math.round(mLimit))
                .append(" [").append(sLimitWhy).append(']');
        if (mView != null) sb.append(' ').append(mView.describe());
        return sb.toString();
    }

    /**
     * The ground under the window: graphite under the line's glow, with the still landmark on it
     * when the view is not drawing the moving one over it - the doze, or no moving one to draw.
     */
    private void paintGround() {
        CountdownScene.GroundSurface g = mGround;
        TransitView v = mView;
        AmapTransitCard.Card c = mCard;
        if (g == null || v == null || c == null) return;
        int w = v.getResources().getDisplayMetrics().widthPixels;
        int h = v.getResources().getDisplayMetrics().heightPixels;
        boolean still = mDozing || !v.animating();
        g.setBitmap(Ground.make(w, h, c.lineBg, still ? v.stillArt() : null, mArtBox, mFadeFrom, mFadeTo));
    }

    // ---------------------------------------------------------------- the pictures

    /** Which pictures, and fetching them: on a thread of their own, kept on disk. */
    static final class Art {

        private Art() {
        }

        /** What to show, as addresses: the first of [ground] that arrives, and [label]. */
        static final class Pick {
            static final Pick NONE = new Pick(new String[0], null, false, "none");

            /** Animated first where there is one; then stills, each a fallback for the last. */
            final String[] ground;
            /** The landmark's name, white, for under it; null without a landmark. */
            final String label;
            /** A default rather than a landmark: the island's card draws it dimmer. */
            final boolean fallback;
            private final String why;

            Pick(String[] ground, String label, boolean fallback, String why) {
                this.ground = ground;
                this.label = label;
                this.fallback = fallback;
                this.why = why;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Pick)) return false;
                Pick p = (Pick) o;
                return java.util.Arrays.equals(ground, p.ground) && TextUtils.equals(label, p.label);
            }

            @Override
            public int hashCode() {
                return java.util.Arrays.hashCode(ground);
            }

            @Override
            public String toString() {
                return why;
            }

            /** SceneService's choice for a card: ya.d.a in transit, ya.b.O on arriving. */
            static Pick of(AmapTransitCard.Trip t, AmapTransitCard.Card c) {
                AmapTransitCard.Leg l = t.current;
                boolean subway = rail(t, c);
                if (!c.landmarkStation.isEmpty() && l != null) {
                    double[] at = stationPoint(l, c.landmarkStation);
                    AmapTransitLandmarks.Match m = at == null ? null
                            : AmapTransitLandmarks.near(t.cityCode, at[0], at[1]);
                    if (m != null) {
                        return new Pick(new String[] {m.animatedUrl(), m.stillUrl()}, m.labelUrl(),
                                false, "landmark " + m + " at " + c.landmarkStation);
                    }
                    double[] p = at != null ? at : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    return new Pick(defaults(t, subway, night, at), null, true,
                            "no landmark at " + c.landmarkStation + (night ? ", night" : ""));
                }
                if (c.arrivalArt && l != null) {
                    double[] exit = exitPoint(t, l);
                    double[] p = exit != null ? exit : where(t);
                    boolean night = night(p[0], p[1], System.currentTimeMillis());
                    List<String> urls = new ArrayList<>();
                    String label = null;
                    // Only a point of the trip's own is looked up: where() falls back to a fixed
                    // one for day and night, which would find that city's landmark for any trip.
                    AmapTransitLandmarks.Match m = exit == null ? null
                            : AmapTransitLandmarks.near(t.cityCode, p[0], p[1]);
                    if (m != null) {
                        urls.add(m.animatedUrl());
                        urls.add(m.stillUrl());
                        label = m.labelUrl();
                    }
                    // A subway arrival falls back to the city's picture, a bus straight to the
                    // nation's (PublicTransportDestinationImageUtil.j / .n).
                    String folder = AmapTransitLandmarks.folder(t.cityCode);
                    if (folder == null && exit != null) folder = AmapTransitLandmarks.folderNear(p[0], p[1]);
                    if (subway && folder != null) {
                        urls.add(AmapTransitLandmarks.cityDefaultUrl(folder, night));
                    }
                    urls.add(AmapTransitLandmarks.nationalDefaultUrl(subway, night));
                    return new Pick(urls.toArray(new String[0]), label, m == null,
                            (m == null ? "arrival, default" : "arrival, landmark " + m)
                                    + (night ? ", night" : ""));
                }
                double[] p = where(t);
                boolean night = night(p[0], p[1], System.currentTimeMillis());
                return new Pick(defaults(t, subway, night, null), null, true,
                        c.kind + (night ? ", night" : ""));
            }

            /**
             * With no landmark by the stop: the city's own picture for a subway, then the
             * nation's. ColorOS keeps the city's for an arrival and shows the nation's in transit
             * (ya.d); the generic station between a city's own pictures read as the wrong one
             * (user, 2026-10-07), so the city's stands in transit too. The city by its code, else
             * by a point of the trip's own ([own], or the leg's ends) - never where()'s fixed one.
             */
            private static String[] defaults(AmapTransitCard.Trip t, boolean subway, boolean night,
                                             double[] own) {
                List<String> urls = new ArrayList<>();
                if (subway) {
                    String folder = AmapTransitLandmarks.folder(t.cityCode);
                    double[] at = own;
                    AmapTransitCard.Leg l = t.current;
                    if (at == null && l != null) {
                        if (AmapTransitLandmarks.valid(l.offLat, l.offLng)) at = new double[] {l.offLat, l.offLng};
                        else if (AmapTransitLandmarks.valid(l.onLat, l.onLng)) at = new double[] {l.onLat, l.onLng};
                    }
                    if (folder == null && at != null) folder = AmapTransitLandmarks.folderNear(at[0], at[1]);
                    if (folder != null) urls.add(AmapTransitLandmarks.cityDefaultUrl(folder, night));
                }
                urls.add(AmapTransitLandmarks.nationalDefaultUrl(subway, night));
                return urls.toArray(new String[0]);
            }

            /**
             * Whether the trip is on rails where the card is, for a rail picture rather than the
             * bus's. ColorOS asks the card's own type, and only a subway (2) is one: an intercity
             * line or an outer-loop line that 高德 types as a bus got the bus, and so did the
             * arrival, a walk (2026-10-07). Here the leg is the one ridden now, else the last one
             * ridden, else the next; its type if it says subway, else its name.
             */
            static boolean rail(AmapTransitCard.Trip t, AmapTransitCard.Card c) {
                if (c.subway()) return true;
                AmapTransitCard.Leg l = t.current;
                int cur = t.currentIndex();
                if (l == null || !l.rides()) {
                    l = null;
                    for (int i = Math.min(cur, t.navi.size() - 1); i >= 0 && l == null; i--) {
                        if (t.navi.get(i).rides()) l = t.navi.get(i);
                    }
                    for (int i = Math.max(0, cur); i < t.navi.size() && l == null; i++) {
                        if (t.navi.get(i).rides()) l = t.navi.get(i);
                    }
                }
                if (l == null) return false;
                if (AmapTransitCard.subway(l.type)) return true;
                String name = l.lineName == null ? "" : l.lineName;
                for (String mark : RAIL_NAMES) {
                    if (name.contains(mark)) return true;
                }
                return false;
            }

            /** What a line on rails has in its name, where its type does not say so. */
            private static final String[] RAIL_NAMES = {
                    "号线", "地铁", "城际", "轨道", "铁路", "有轨", "APM", "轻轨", "磁浮", "云巴",
            };

            /** ya.d.c: the named stop's spot, a stop between the ends or the one gotten off at. */
            private static double[] stationPoint(AmapTransitCard.Leg l, String name) {
                for (AmapTransitCard.Station s : l.via) {
                    if (s.name.trim().equals(name) && s.located()) return new double[] {s.lat, s.lng};
                }
                if (l.offName.trim().equals(name) && AmapTransitLandmarks.valid(l.offLat, l.offLng)) {
                    return new double[] {l.offLat, l.offLng};
                }
                return null;
            }

            /**
             * ya.b.N: the exit 高德 named, by its name or its shield, else the stop's first exit,
             * else the stop itself.
             */
            private static double[] exitPoint(AmapTransitCard.Trip t, AmapTransitCard.Leg l) {
                if (!l.ports.isEmpty()) {
                    String exit = t.exitName.trim();
                    AmapTransitCard.Port port = null;
                    if (!exit.isEmpty()) {
                        for (AmapTransitCard.Port p : l.ports) {
                            if (p.name.equalsIgnoreCase(exit) || p.shield.equalsIgnoreCase(exit)
                                    || p.name.toLowerCase().contains(exit.toLowerCase())) {
                                port = p;
                                break;
                            }
                        }
                    }
                    if (port == null) port = l.ports.get(0);
                    if (port.located()) return new double[] {port.lat, port.lng};
                }
                if (AmapTransitLandmarks.valid(l.offLat, l.offLng)) return new double[] {l.offLat, l.offLng};
                return null;
            }

            /** Somewhere on the trip, for whether it is night there - never for a landmark. */
            private static double[] where(AmapTransitCard.Trip t) {
                AmapTransitCard.Leg l = t.current;
                if (l != null) {
                    if (AmapTransitLandmarks.valid(l.offLat, l.offLng)) return new double[] {l.offLat, l.offLng};
                    for (AmapTransitCard.Station s : l.via) {
                        if (s.located()) return new double[] {s.lat, s.lng};
                    }
                    if (AmapTransitLandmarks.valid(l.onLat, l.onLng)) return new double[] {l.onLat, l.onLng};
                }
                if (AmapTransitLandmarks.valid(t.destLat, t.destLng)) return new double[] {t.destLat, t.destLng};
                return new double[] {39.9, 116.4};
            }
        }

        /** What arrived for a pick. */
        static final class Set {
            final Pick pick;
            final Drawable ground;
            /** The still the animated one was made from, for the ground under the window. */
            final Bitmap still;
            final Drawable label;
            /** Its solid part's top and foot, as shares of its height (see solid). */
            final float solidTop;
            final float solidBottom;

            Set(Pick pick, Drawable ground, Bitmap still, Drawable label, float[] solid) {
                this.solidTop = solid[0];
                this.solidBottom = solid[1];
                this.pick = pick;
                this.ground = ground;
                this.still = still;
                this.label = label;
            }
        }

        interface Done {
            void done(Set set);
        }

        private static final ExecutorService IO =
                Gen.executor("mc-transit-art", Thread.NORM_PRIORITY, false);
        private static final Handler MAIN = Gen.handler();

        /** Fetches [pick] and hands it to [done] on the main thread; nothing if nothing came. */
        static void request(Context ctx, Pick pick, Done done) {
            if (pick == null || pick.ground.length == 0) return;
            final File dir = new File(ctx.getCacheDir(), "mc-transit");
            IO.execute(() -> {
                Drawable ground = null;
                Bitmap still = null;
                for (String url : pick.ground) {
                    byte[] b = fetch(dir, url);
                    if (b == null) continue;
                    ground = drawable(b);
                    if (ground == null) continue;
                    still = bitmap(b);
                    // An animated landmark's still is its .png beside it, for the ground.
                    if (ground instanceof AnimatedImageDrawable && url.endsWith(".webp")) {
                        byte[] s = fetch(dir, url.substring(0, url.length() - 5) + ".png");
                        Bitmap sb = s == null ? null : bitmap(s);
                        if (sb != null) still = sb;
                    }
                    break;
                }
                Drawable label = null;
                if (pick.label != null) {
                    byte[] b = fetch(dir, pick.label);
                    if (b != null) label = drawable(b);
                }
                if (ground == null) {
                    Xp.log(TAG + "no picture for " + pick);
                    return;
                }
                final Set set = new Set(pick, ground, still, label, solid(still));
                MAIN.post(() -> done.done(set));
            });
        }

        /**
         * Where a picture's solid part starts and ends down its height - the rows with anything
         * over SOLID_ALPHA in them - for the page to keep its rhythm to the picture rather than
         * its box. The subway stations start at about 0.35, the bus's stop sign at 0.2; the fixed
         * 0.35 put the bus over the summary (2026-10-07). The shares artRect assumes, without one.
         */
        static float[] solid(Bitmap b) {
            float[] out = {ART_SOLID_TOP, ART_SOLID_BOTTOM};
            if (b == null || !b.hasAlpha()) return out;
            int w = b.getWidth();
            int h = b.getHeight();
            int[] row = new int[w];
            int first = -1;
            int last = -1;
            for (int y = 0; y < h; y += 2) {
                b.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x += 2) {
                    if ((row[x] >>> 24) > SOLID_ALPHA) {
                        if (first < 0) first = y;
                        last = y;
                        break;
                    }
                }
            }
            if (first < 0 || last <= first) return out;
            out[0] = first / (float) h;
            out[1] = (last + 1) / (float) h;
            return out;
        }

        private static final int SOLID_ALPHA = 110;

        /** From the disk if it has been fetched before, else from OPPO's CDN, kept. */
        private static byte[] fetch(File dir, String url) {
            File f = new File(dir, url.substring(AmapTransitLandmarks.CDN.length()).replace('/', '_'));
            try {
                if (f.isFile() && f.length() > 0) return java.nio.file.Files.readAllBytes(f.toPath());
            } catch (Throwable ignored) {
            }
            Http.Raw r = Http.request(url, "transit", null);
            if (!r.ok()) return null;
            try {
                if (dir.isDirectory() || dir.mkdirs()) {
                    File tmp = new File(dir, f.getName() + ".part");
                    java.nio.file.Files.write(tmp.toPath(), r.body);
                    if (!tmp.renameTo(f)) tmp.delete();
                }
            } catch (Throwable t) {
                Xp.log(TAG + "not kept: " + t);
            }
            return r.body;
        }

        private static Drawable drawable(byte[] b) {
            try {
                return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(b)));
            } catch (Throwable t) {
                Xp.log(TAG + "undecodable picture: " + t);
                return null;
            }
        }

        private static Bitmap bitmap(byte[] b) {
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b)),
                        (d, info, src) -> d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Whether the sun is down at a spot: its altitude under -0.833 degrees, the sunset line
     * (SceneService picks day and night pictures by sunrise and sunset there, pa.f).
     */
    static boolean night(double lat, double lng, long now) {
        double d = now / 86400000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0);
        double q = (280.459 + 0.98564736 * d) % 360.0;
        double lon = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(lon), Math.cos(lon));
        double dec = Math.asin(Math.sin(e) * Math.sin(lon));
        double gmst = (18.697374558 + 24.06570982441908 * d) % 24.0;
        double ha = Math.toRadians(gmst * 15.0 + lng) - ra;
        double phi = Math.toRadians(lat);
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec)
                + Math.cos(phi) * Math.cos(dec) * Math.cos(ha));
        return Math.toDegrees(alt) < -0.833;
    }

    // ---------------------------------------------------------------- where things go

    /**
     * The page, top to bottom under the small clock: where the trip goes, large, and how long and
     * far; the landmark, its clear top tucked under those words; the trip leg by leg as the
     * picture's caption; this leg's stops. What the focus notification already says - the card's
     * own lines, the arrival, the line and its direction, the progress - is not said again here.
     * Everything ends by [BOTTOM] of the height, above the rows the lock screen keeps at its foot.
     */
    private static final float WORDS_TOP = 0.19f;
    private static final float BOTTOM = 0.70f;
    /**
     * The page's two rhythms, in dp, measured ink to ink: the summary sits as far under the title
     * as the landmark sits under it; the strip as far under the landmark as the stops under it.
     */
    private static final float GAP_HEAD_DP = 12f;
    private static final float GAP_BODY_DP = 22f;
    private static final float TITLE_SP = 32f;
    /** A long destination's title gets smaller down to this, then goes on two lines this far apart. */
    private static final float TITLE_MIN_SP = 22f;
    private static final float TITLE_LINE_GAP_DP = 8f;
    private static final float SUMMARY_SP = 15f;
    private static final float STRIP_DP = 30f;
    /** Between the strip's two lines, where a long trip needs two. */
    private static final float LINE_GAP_DP = 10f;
    /** How far a row of stops reaches above its middle: the halo round the stop coming to. */
    private static final float ROW_HALF_DP = 11f;
    private static final float ART_WIDTH = 0.80f;
    /**
     * The picture's fade in from under the words: from this far under the summary's ink, to this
     * far into its solid part (Art.solid), so its clear top never sits behind the summary.
     */
    private static final float FADE_CLEAR_DP = 2f;
    private static final float FADE_INTO_DP = 6f;
    /** Narrower than this, the picture is left out: the words and the stops are the page. */
    private static final float ART_MIN_WIDTH = 0.45f;
    /** OPPO's landmark pictures are 807x378. */
    private static final float ART_ASPECT = 378f / 807f;
    /**
     * The solid part of every one of them, as shares of its box's height (alpha over 140,
     * measured on the day and night defaults, 故宫 and 欢乐港湾): the block, with clear sky above
     * it and the city block's fade round it.
     */
    private static final float ART_SOLID_TOP = 0.35f;
    private static final float ART_SOLID_BOTTOM = 0.88f;
    /**
     * The key that takes the card colour OPPO's moving landmarks are rendered on (#1E1E1E, 30/255)
     * back out of a frame. A pixel is that colour over whatever the picture holds there, so how far
     * it is from it says how much of the picture there is: its largest channel's distance over
     * 40/255 is its opacity, and what it adds to the card colour is its colour. The grey block
     * the stills fade out comes back at about the stills' own weight, shadows darken whatever is
     * under them, and the building, far from the card colour everywhere, is untouched.
     */
    private static final String KEY = ""
            + "uniform shader content;"
            + "uniform float fadeFrom;"
            + "uniform float fadeTo;"
            + "half4 main(float2 p) {"
            + "  half4 c = content.eval(p);"
            + "  half3 d = c.rgb - half3(0.1176) * c.a;"
            + "  half a = clamp(max(max(abs(d.r), abs(d.g)), abs(d.b)) / 0.157, 0.0, 1.0) * c.a;"
            + "  half f = half(smoothstep(fadeFrom, fadeTo, p.y));"
            + "  return half4(clamp(half3(0.1176) * a + d, 0.0, a), a) * f;"
            + "}";
    private static boolean sKeyFailed;
    /**
     * Where the page ends, in screen pixels: [BOTTOM] of the height, or above the trip's own row
     * - the island opened into its notification at the foot of the lock screen - whichever is
     * higher, this much clear of it.
     */
    private static final float ROW_CLEAR_DP = 14f;
    /** How the strip of legs goes: its gaps, where it breaks, what is cut down, how tall it is. */
    static final class LegsPlan {
        final boolean[] dot;
        final boolean[] bare;
        float gap;
        int split;
        float height;

        LegsPlan(int n) {
            dot = new boolean[n];
            bare = new boolean[n];
            split = n;
        }
    }

    /** At most this many rows of stops, folds included, and at least this many. */
    private static final int MAX_ROWS = 5;
    private static final int MIN_ROWS = 3;
    private static final float ROW_DP = 38f;
    /** Clear space under the clock, where a clock style reaches below [WORDS_TOP]. */
    private static final float CLOCK_GAP_DP = 18f;

    /**
     * The ground: graphite with a breath of the line's colour, the line itself as a glow behind
     * the words at the top and a fainter one along the foot, and the still landmark.
     *
     * Graphite because of what OPPO's pictures are drawn for: each one stands on a grey city block
     * of its own (about #424245, fading out to clear at its edges), made to melt into the dark
     * neutral card ColorOS shows it on. On the line's colour mixed into black - this page before -
     * that block read as a patch of grey fog. Every picture is drawn whole: a default used to go
     * on at 43%, which greyed the night picture's lit entrance out, and the light is the picture's
     * own - there is none painted here.
     */
    static final class Ground {
        private Ground() {
        }

        private static final int TOP = 0xff0b0c0f;
        private static final int MIDDLE = 0xff0d0e11;
        private static final int BOTTOM = 0xff050506;

        /** At half the screen's size: soft gradients and glows, grained so none of it bands. */
        static Bitmap make(int w, int h, int lineBg, Bitmap still, RectF artBox, float fadeFrom,
                           float fadeTo) {
            int bw = Math.max(1, w / 2);
            int bh = Math.max(1, h / 2);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, bh,
                    new int[] {blend(lineBg, TOP, 0.84f), blend(lineBg, MIDDLE, 0.94f), BOTTOM},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            glow(c, bw * 0.5f, bh * 0.10f, bw * 1.0f, bh * 0.34f, lineBg, 0.34f, 1.4f);
            glow(c, bw * 0.5f, bh * 1.04f, bw * 0.85f, bh * 0.20f, lineBg, 0.20f, 1.3f);
            grain(b);
            float k = bw / (float) w;
            if (still != null && artBox != null && !artBox.isEmpty()) {
                RectF box = new RectF(artBox.left * k, artBox.top * k, artBox.right * k, artBox.bottom * k);
                Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                int layer = c.saveLayer(box, null);
                c.drawBitmap(still, null, box, ip);
                if (!Float.isNaN(fadeFrom) && fadeTo > fadeFrom) {
                    // The view's fade, so the still has nothing behind the words either.
                    Paint mask = new Paint();
                    mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                    mask.setShader(new LinearGradient(0f, fadeFrom * k, 0f, fadeTo * k,
                            0x00000000, 0xff000000, Shader.TileMode.CLAMP));
                    c.drawRect(box, mask);
                }
                c.restoreToCount(layer);
            }
            return b;
        }

        /**
         * An elliptical glow of [colour], [alpha] at its centre and nothing at its rim, falling
         * off as a smoothstep raised to [ease]: a radial gradient stretched to the ellipse.
         */
        private static void glow(Canvas c, float cx, float cy, float rx, float ry, int colour,
                                 float alpha, float ease) {
            int n = 9;
            int[] colours = new int[n];
            float[] stops = new float[n];
            for (int i = 0; i < n; i++) {
                float d = i / (float) (n - 1);
                float t = 1f - d;
                float a = alpha * (float) Math.pow(t * t * (3f - 2f * t), ease);
                stops[i] = d;
                colours[i] = Color.argb(Math.round(a * 255f), Color.red(colour), Color.green(colour),
                        Color.blue(colour));
            }
            RadialGradient g = new RadialGradient(0f, 0f, 1f, colours, stops, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(rx, ry);
            m.postTranslate(cx, cy);
            g.setLocalMatrix(m);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(g);
            c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, p);
        }

        /** A level or so of noise on every channel: dark gradients this long band without it. */
        private static void grain(Bitmap b) {
            int w = b.getWidth();
            int h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            long s = 0x9E3779B97F4A7C15L;
            for (int i = 0; i < px.length; i++) {
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                int d = (int) ((s >>> 40) % 3) - 1;
                int c = px[i];
                int r = Math.min(255, Math.max(0, ((c >> 16) & 0xff) + d));
                int g = Math.min(255, Math.max(0, ((c >> 8) & 0xff) + d));
                int bl = Math.min(255, Math.max(0, (c & 0xff) + d));
                px[i] = 0xff000000 | (r << 16) | (g << 8) | bl;
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
        }
    }

    /**
     * The title and the summary as their ink stands: the baselines that put the title's ink at
     * the words' top - its [lines] [TITLE_LINE_GAP_DP] apart - and the summary's [GAP_HEAD_DP]
     * under it, and how tall the two are together. Measured on fixed words of the same faces -
     * every Chinese line inks alike - so a title's size, not its words, decides where things go.
     */
    static final class Header {
        final float titleBase;
        final float titleStep;
        final float summaryBase;
        final float height;

        private Header(float titleBase, float titleStep, float summaryBase, float height) {
            this.titleBase = titleBase;
            this.titleStep = titleStep;
            this.summaryBase = summaryBase;
            this.height = height;
        }

        private static Header sLast;
        private static String sLastKey;

        static synchronized Header of(float dp, float titleSp, int lines) {
            String key = dp + "/" + titleSp + "/" + lines;
            if (sLast != null && key.equals(sLastKey)) return sLast;
            android.graphics.Rect r = new android.graphics.Rect();
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            t.setTextSize(titleSp * dp);
            t.getTextBounds("前往南锣鼓巷", 0, 6, r);
            float titleBase = -r.top;
            float step = r.height() + TITLE_LINE_GAP_DP * dp;
            float titleH = r.height() + (lines - 1) * step;
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            t.setTextSize(SUMMARY_SP * dp);
            t.getTextBounds("全程分钟换乘次", 0, 7, r);
            float sumTop = titleH + GAP_HEAD_DP * dp;
            sLast = new Header(titleBase, step, sumTop - r.top, sumTop + r.height());
            sLastKey = key;
            return sLast;
        }
    }

    /**
     * Where the page's words start, in screen pixels: [WORDS_TOP] of the height, or under the
     * clock where its style draws lower than that - a stacked or a large style can, small as the
     * immersive page makes it.
     *
     * The clock is where it rests in the lock screen's own pixels (ClockCollapse
     * .contentBottomOnScreen: the small clock's ink and any signature bar it carries), not where
     * it is drawn: through a doze HyperOS holds keyguard_root_view at 0.95 and the clock is drawn
     * lower, and words that followed it jumped up as the screen came on. ClockViews.glyphBox is no
     * substitute - it is the clock before it is made small, 240px further down. With no small
     * clock of ours, the date line, unzoomed the same way. A clock that seems to reach past the
     * middle of the screen is a measurement to distrust, not a clock.
     */
    static float wordsTop(int h, float dp) {
        float top = h * WORDS_TOP;
        try {
            float clock = ClockCollapse.contentBottomShown();
            View d = ClockViews.visibleDate();
            if (d != null && d.isShown() && d.getAlpha() > 0.01f) {
                int[] at = new int[2];
                d.getLocationOnScreen(at);
                float b = ClockCollapse.unzoomY(d, at[1] + d.getHeight() * d.getScaleY());
                clock = Float.isNaN(clock) ? b : Math.max(clock, b);
            }
            if (!Float.isNaN(clock) && clock < h * 0.5f) top = Math.max(top, clock + CLOCK_GAP_DP * dp);
        } catch (Throwable ignored) {
            // no clock to read: the share stands
        }
        return top;
    }

    /**
     * See ROW_CLEAR_DP. The row where it is drawn: the stack places its rows by their translation,
     * so that is part of where it is, not a motion to look past - taken off, it put the row at
     * -37px and the page fell back to its share, under the row (2026-10-07). A row somewhere a
     * resting row cannot be (above the middle, past the foot) is one still on its way in.
     */
    static float pageLimit(int h, float dp) {
        float limit = h * BOTTOM;
        String why;
        try {
            String key = LockIslands.INSTANCE.openSceneKey();
            View row = isTripKey(key) ? MiniPlayerRuntime.rowOf(key) : null;
            if (row == null) {
                why = "no row (key=" + key + ")";
            } else if (!row.isShown() || row.getHeight() <= 0) {
                why = "row not shown";
            } else {
                int[] at = new int[2];
                row.getLocationOnScreen(at);
                why = "row at " + at[1];
                if (at[1] > h * 0.45f && at[1] < h * 0.97f) {
                    limit = Math.min(limit, at[1] - ROW_CLEAR_DP * dp);
                } else {
                    why += " (moving, not taken)";
                }
            }
        } catch (Throwable t) {
            why = "failed: " + t;
        }
        sLimitWhy = why;
        return limit;
    }

    /** What pageLimit last found, for the probe. */
    private static volatile String sLimitWhy = "-";

    static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(255, r, g, bl);
    }

    /**
     * What the page says, worked out of the whole trip: where it goes, how long and how far, the
     * legs in order, and the stops of the leg on show. That leg is the one ridden now, else the
     * next one to ride (the walk to a station, a transfer's walk), else the last one ridden (an
     * arrival).
     */
    static final class Route {
        static final int DONE = 0;
        static final int NOW = 1;
        static final int AHEAD = 2;

        /** A leg in the strip: a ride's line, or a walk's length. */
        static final class Leg {
            final boolean ride;
            final String text;
            final int color;
            final int state;

            Leg(boolean ride, String text, int color, int state) {
                this.ride = ride;
                this.text = text;
                this.color = color;
                this.state = state;
            }
        }

        /** A row of the stops: one stop, or [count] of them folded into one line. */
        static final class Row {
            final int index;
            final int count;

            Row(int index, int count) {
                this.index = index;
                this.count = count;
            }

            boolean fold() {
                return count > 0;
            }
        }

        String dest = "";
        String summary = "";
        final List<Leg> legs = new ArrayList<>();
        /** The leg on show's stops, getting on to getting off; empty with nothing to ride. */
        final List<String> stops = new ArrayList<>();
        /** The most stops any leg of the trip has: what the page keeps room for, all trip long. */
        int mostStops;
        /** Its line's colour. */
        int color;
        /** The stop the train is at ([at]) or coming to. */
        int here;
        boolean at;
        String hereTag = "";
        String endTag = "";
        int endColor;

        static Route of(AmapTransitCard.Trip t, AmapTransitCard.Card c) {
            Route r = new Route();
            List<AmapTransitCard.Leg> navi = t.navi;
            int cur = t.currentIndex();
            int show = -1;
            if (t.current != null && t.current.rides()) show = cur;
            for (int i = Math.max(0, cur); show < 0 && i < navi.size(); i++) {
                if (navi.get(i).rides()) show = i;
            }
            for (int i = navi.size() - 1; show < 0 && i >= 0; i--) {
                if (navi.get(i).rides()) show = i;
            }
            AmapTransitCard.Leg last = null;
            int rides = 0;
            for (AmapTransitCard.Leg l : navi) {
                if (!l.rides()) continue;
                last = l;
                rides++;
            }

            r.dest = AmapTransitCard.trim(t.destStation);
            if (r.dest.isEmpty() && last != null) r.dest = AmapTransitCard.trim(last.offName);

            List<String> parts = new ArrayList<>();
            if (t.totalDuration > 0) {
                parts.add("全程 " + Math.max(1, Math.round(t.totalDuration / 60.0)) + " 分钟");
            }
            double metres = number(t.totalDistance);
            if (metres > 0) parts.add(distance(metres, " "));
            if (rides > 1) parts.add("换乘 " + (rides - 1) + " 次");
            // cardShowWeakInternet: ColorOS's weak-signal picture in the card's corner on 3 / 4 / 5;
            // the page has no corner icon, so 高德's own words for it (its card's tip) end this line.
            if (c != null && c.weakSignal) parts.add("信号弱");
            r.summary = TextUtils.join(" · ", parts);

            // The walks at either end are the map's business: only a change's walk is the trip's.
            int firstRide = -1;
            int lastRide = -1;
            for (int i = 0; i < navi.size(); i++) {
                if (!navi.get(i).rides()) continue;
                if (firstRide < 0) firstRide = i;
                lastRide = i;
            }
            for (int i = Math.max(0, firstRide); i <= lastRide; i++) {
                AmapTransitCard.Leg l = navi.get(i);
                if (l.rides()) r.mostStops = Math.max(r.mostStops, l.via.size() + 2);
                int state = cur < 0 ? AHEAD : i < cur ? DONE : i == cur ? NOW : AHEAD;
                if (l.rides()) {
                    r.legs.add(new Leg(true, shortName(l.lineName), AmapTransitCard.color(l.lineBg), state));
                } else {
                    double m = number(l.walkLength);
                    if (m > 0) r.legs.add(new Leg(false, distance(m, ""), 0, state));
                }
            }

            if (show < 0) return r;
            AmapTransitCard.Leg l = navi.get(show);
            r.color = AmapTransitCard.color(l.lineBg);
            r.stops.add(AmapTransitCard.trim(l.onName));
            for (AmapTransitCard.Station s : l.via) r.stops.add(AmapTransitCard.trim(s.name));
            r.stops.add(AmapTransitCard.trim(l.offName));
            int n = r.stops.size();
            if (show == cur) {
                int remain = l.remain == null ? n - 1 : l.remain;
                r.at = c.atStation;
                r.here = r.at ? n - 1 - remain : n - remain;
                r.hereTag = r.at ? "当前站" : "下一站";
            } else if (show > cur) {
                r.here = 0;
                r.at = true;
                r.hereTag = "上车";
            } else {
                r.here = n - 1;
                r.at = true;
            }
            r.here = Math.max(0, Math.min(n - 1, r.here));

            // Getting off: the line changed to and where it goes, else the exit.
            AmapTransitCard.Leg next = null;
            for (int i = show + 1; i < navi.size(); i++) {
                if (navi.get(i).rides()) {
                    next = navi.get(i);
                    break;
                }
            }
            if (next != null) {
                String dir = AmapTransitCard.trim(next.lineDirection);
                r.endTag = "下车 · 换乘 " + shortName(next.lineName) + (dir.isEmpty() ? "" : " 往" + dir);
                r.endColor = AmapTransitCard.color(next.lineBg);
            } else {
                String exit = AmapTransitCard.trim(t.exitName);
                r.endTag = exit.isEmpty() ? "下车" : "下车 · " + exit + "出站";
                r.endColor = r.color;
            }
            return r;
        }

        /**
         * The stops as at most [max] rows: getting off, the stop at or coming to and the one after
         * it always; getting on while it is near; the stop just passed where it still fits; and
         * every other run of two or more folded into a line of its own.
         */
        List<Row> rows(int max) {
            int n = stops.size();
            List<Row> rows = new ArrayList<>();
            if (n == 0 || max <= 0) return rows;
            boolean[] keep = new boolean[n];
            keep[n - 1] = true;
            keep[here] = true;
            if (here + 1 < n - 1) keep[here + 1] = true;
            if (here <= 1) keep[0] = true;
            rows = fold(keep);
            if (here >= 1 && !keep[here - 1]) {
                keep[here - 1] = true;
                List<Row> more = fold(keep);
                if (more.size() <= max) rows = more;
                else keep[here - 1] = false;
            }
            // Over the limit: give up, in turn, the stop after the next, getting on, the folds'
            // own lines, then stops from the top - getting off and this one stay to the last.
            if (rows.size() > max && here + 1 < n - 1 && keep[here + 1]) {
                keep[here + 1] = false;
                rows = fold(keep);
            }
            if (rows.size() > max && here > 0 && keep[0]) {
                keep[0] = false;
                rows = fold(keep);
            }
            if (rows.size() > max) {
                List<Row> stopsOnly = new ArrayList<>();
                for (Row r : rows) if (!r.fold()) stopsOnly.add(r);
                rows = stopsOnly;
            }
            while (rows.size() > max) {
                int drop = 0;
                while (drop < rows.size() - 1 && (rows.get(drop).index == here
                        || rows.get(drop).index == n - 1)) drop++;
                rows.remove(drop);
            }
            return rows;
        }

        private static List<Row> fold(boolean[] keep) {
            List<Row> out = new ArrayList<>();
            int n = keep.length;
            int i = 0;
            while (i < n) {
                if (keep[i]) {
                    out.add(new Row(i, 0));
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && !keep[j]) j++;
                if (j - i >= 2) {
                    out.add(new Row(i, j - i));
                } else {
                    for (int q = i; q < j; q++) out.add(new Row(q, 0));
                }
                i = j;
            }
            return out;
        }

        /** 「1号线(西单-四惠东)」 is 1号线: the bracket is the strip's room, not the line's name. */
        static String shortName(String name) {
            String s = AmapTransitCard.trim(name);
            String cut = s.replaceAll("[(（][^)）]*[)）]", "").trim();
            return cut.isEmpty() ? s : cut;
        }

        private static double number(String s) {
            try {
                return Double.parseDouble(AmapTransitCard.trim(s));
            } catch (Throwable t) {
                return 0;
            }
        }

        /** 420米 / 4.1公里, with [space] between the figure and the unit. */
        private static String distance(double metres, String space) {
            if (metres < 1000) return Math.round(metres) + space + "米";
            double km = Math.round(metres / 100.0) / 10.0;
            String v = km == Math.floor(km) ? String.valueOf((long) km) : String.valueOf(km);
            return v + space + "公里";
        }
    }

    /** Whether white on [c] would not read: 13号线's yellow and the like. */
    static boolean light(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f > 0.62f;
    }

    // ---------------------------------------------------------------- the page

    /** The words, the overview and the moving landmark, over the ground. */
    final class TransitView extends View {

        private final float mDp;
        private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mSummary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mChip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mWalk = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mNameBig = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mTag = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mTagPlain = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mFold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int[] mAt = new int[2];

        private AmapTransitCard.Card mC;
        private Route mRoute;
        private Drawable mArt;
        private Bitmap mStill;
        private android.graphics.RenderNode mArtNode;
        private android.graphics.RuntimeShader mKey;
        private float mKeyFrom = Float.NaN;
        private float mKeyTo = Float.NaN;
        /** The picture's solid part, as shares of its box's height (Art.solid). */
        private float mSolidTop = ART_SOLID_TOP;
        private float mSolidBottom = ART_SOLID_BOTTOM;
        private boolean mLive;

        TransitView(Context ctx) {
            super(ctx);
            mDp = ctx.getResources().getDisplayMetrics().density;
            Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            Typeface plain = Typeface.create("sans-serif", Typeface.NORMAL);
            mTitle.setTypeface(bold);
            mTitle.setColor(0xf7ffffff);
            mTitle.setTextAlign(Paint.Align.CENTER);
            mSummary.setTypeface(plain);
            mSummary.setColor(0x94ffffff);
            mSummary.setTextAlign(Paint.Align.CENTER);
            mChip.setTypeface(bold);
            mChip.setTextAlign(Paint.Align.CENTER);
            mWalk.setTypeface(plain);
            mName.setTypeface(plain);
            mNameBig.setTypeface(bold);
            mTag.setTypeface(bold);
            mTagPlain.setTypeface(plain);
            mFold.setTypeface(plain);
        }

        /** The new card; a different one plays the landmark once more. */
        void setCard(AmapTransitCard.Card c, Route route, boolean again) {
            mC = c;
            mRoute = route;
            if (again) replayArt();
            invalidate();
        }

        void setArt(Art.Set set) {
            stopArt();
            if (mArt != null) mArt.setCallback(null);
            mArt = set.ground;
            mStill = set.still;
            mSolidTop = set.solidTop;
            mSolidBottom = set.solidBottom;
            if (mArt != null) mArt.setCallback(this);
            startArt();
            invalidate();
        }

        Bitmap stillArt() {
            return mStill;
        }


        boolean animating() {
            return mLive && mArt instanceof AnimatedImageDrawable;
        }

        /** On the lit lock screen and shown: the landmark moves. */
        void setLive(boolean live) {
            if (mLive == live) return;
            mLive = live;
            if (live) startArt();
            else stopArt();
            invalidate();
        }

        /** The landmark plays once and stops: a new state is one pass, not a loop. */
        private void startArt() {
            if (mLive && mArt instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable a = (AnimatedImageDrawable) mArt;
                a.setRepeatCount(0);
                a.start();
            }
            paintGround();
        }

        void replayArt() {
            if (!(mArt instanceof AnimatedImageDrawable)) return;
            AnimatedImageDrawable a = (AnimatedImageDrawable) mArt;
            stopArt();
            if (mLive) a.start();
        }

        private void stopArt() {
            if (mArt instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) mArt).stop();
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == mArt || super.verifyDrawable(who);
        }

        @Override
        protected void onDetachedFromWindow() {
            stopArt();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            paintGround();
        }

        String describe() {
            return "art=" + (mArt == null ? "-" : mArt.getClass().getSimpleName())
                    + " live=" + mLive;
        }

        // ------------------------------------------------------------ fading

        /** The plugin's spring for a page coming in (PageSpring); no bounce, as the map has. */
        private static final float ENTER_SCALE = 1.06f;
        private static final float SCALE_RESPONSE = 0.45f;

        private float mLastFade = 1f;
        private float mScaleTo = 1f;
        private android.animation.ValueAnimator mScaleAnim;

        void setFade(float alpha) {
            float last = mLastFade;
            mLastFade = alpha;
            setAlpha(alpha);
            if (alpha <= 0f) {
                stopScale();
                setScale(ENTER_SCALE);
                mScaleTo = ENTER_SCALE;
            } else if (alpha >= 1f && last <= 0f) {
                stopScale();
                setScale(1f);
                mScaleTo = 1f;
            } else if (alpha > last) {
                if (mScaleTo != 1f) scaleTo(1f);
            } else if (alpha < last) {
                if (mScaleTo != ENTER_SCALE) scaleTo(ENTER_SCALE);
            }
        }

        private void setScale(float s) {
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            setScaleX(s);
            setScaleY(s);
        }

        private void stopScale() {
            android.animation.ValueAnimator a = mScaleAnim;
            mScaleAnim = null;
            if (a != null) a.cancel();
        }

        private void scaleTo(float to) {
            stopScale();
            mScaleTo = to;
            final float from = getScaleX();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(PageSpring.durationMs(SCALE_RESPONSE, 0f));
            a.setInterpolator(PageSpring.interpolator(SCALE_RESPONSE, 0f));
            a.addUpdateListener(an -> {
                if (mScaleAnim == an) setScale(from + (to - from) * (float) an.getAnimatedValue());
            });
            mScaleAnim = a;
            a.start();
        }

        // ------------------------------------------------------------ drawing

        @Override
        protected void onDraw(Canvas canvas) {
            AmapTransitCard.Card c = mC;
            Route rt = mRoute;
            int w = getWidth();
            int h = getHeight();
            if (c == null || rt == null || w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float maxW = w - 48f * mDp;
            // Under the clock, however low its style draws it, and clear of the trip's own row
            // at the foot; the ground follows a change.
            getLocationOnScreen(mAt);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            float screenTop = wordsTop(screenH, mDp);
            // The limit only goes up while the page is up: a row that grows (its progress bar
            // arriving) moves the page, one that shrinks does not pull it back and forth.
            float screenLimit = pageLimit(screenH, mDp);
            if (!Float.isNaN(mLimit)) screenLimit = Math.min(screenLimit, mLimit);
            if (Float.isNaN(mTop) || Math.abs(screenTop - mTop) > mDp
                    || Math.abs(screenLimit - mLimit) > mDp) {
                // The words follow at once; the ground, a picture made on the main thread, once
                // the clock and the row have stopped moving (both settle as the page comes in).
                mTop = screenTop;
                mLimit = screenLimit;
                mMain.removeCallbacks(mRepaint);
                mMain.postDelayed(mRepaint, 200);
            }
            float top = mTop - mAt[1];
            float limit = mLimit - mAt[1];
            String[] titles = fitTitle(rt.dest.isEmpty() ? c.lockTitle : "前往 " + rt.dest, maxW);
            Header hd = Header.of(mDp, mTitle.getTextSize() / mDp, titles.length);
            LegsPlan legs = legsPlan(rt, maxW);
            // One solve for the whole page, top to limit. The rows the trip can ever take - its
            // longest leg's stops, MAX_ROWS at most - are asked for whatever leg and stop it is
            // at, so nothing moves along the trip; what is left is the picture's, up to
            // ART_WIDTH. Short of room, in turn: the rows down to MIN_ROWS, the picture down to
            // ART_MIN_WIDTH, no picture, the rows down to getting off alone. Whatever is still
            // over goes half above, half below, so a short trip does not leave the foot empty.
            float rh = ROW_DP * mDp;
            float pad = 12f * mDp;
            float stripBlock = legs.height > 0f ? legs.height + GAP_BODY_DP * mDp : 0f;
            float at = top + hd.height + GAP_HEAD_DP * mDp;
            float solidShare = (mSolidBottom - mSolidTop) * ART_ASPECT;
            int rows = Math.min(MAX_ROWS, Math.max(rt.mostStops, rt.stops.size()));
            float aw;
            while (true) {
                float room = limit - at - (GAP_BODY_DP * mDp + stripBlock + rowsHeight(rows) + pad);
                float fit = Math.min(w * ART_WIDTH, room / solidShare);
                if (fit >= w * ART_MIN_WIDTH) {
                    aw = fit;
                    break;
                }
                if (rows > MIN_ROWS) {
                    rows--;
                    continue;
                }
                aw = 0f;
                while (rows > 1 && top + hd.height + GAP_BODY_DP * mDp + stripBlock
                        + rowsHeight(rows) + pad > limit) {
                    rows--;
                }
                break;
            }
            float ah = aw * ART_ASPECT;
            RectF art = new RectF((w - aw) / 2f, at - ah * mSolidTop, (w + aw) / 2f, at - ah * mSolidTop + ah);
            float strip = (aw > 0f ? art.top + ah * mSolidBottom : top + hd.height) + GAP_BODY_DP * mDp;
            float stops = strip + stripBlock + ROW_HALF_DP * mDp;
            float end = strip + stripBlock + rowsHeight(rows);
            float shift = Math.max(0f, (limit - pad - end) / 2f);
            top += shift;
            art.offset(0f, shift);
            strip += shift;
            stops += shift;
            // Nothing of the picture behind the words: its clear top - the city block's faint
            // grey fading out round it - comes in from under the summary to where it is solid.
            float fadeFrom = top + hd.height + FADE_CLEAR_DP * mDp;
            float fadeTo = Math.max(fadeFrom + mDp, art.top + ah * mSolidTop + FADE_INTO_DP * mDp);
            RectF screenArt = new RectF(art);
            screenArt.offset(mAt[0], mAt[1]);
            if (mArtBox == null || Math.abs(mArtBox.top - screenArt.top) > mDp
                    || Math.abs(mArtBox.width() - screenArt.width()) > mDp
                    || Float.isNaN(mFadeFrom) || Math.abs(mFadeFrom - (fadeFrom + mAt[1])) > mDp) {
                mArtBox = screenArt;
                mFadeFrom = fadeFrom + mAt[1];
                mFadeTo = fadeTo + mAt[1];
                mMain.removeCallbacks(mRepaint);
                mMain.postDelayed(mRepaint, 200);
            }

            // The landmark, moving, over the ground's own still of it.
            if (animating() && !art.isEmpty()) drawArt(canvas, art, fadeFrom, fadeTo);

            // Where to, large; how long and how far under it.
            float y = top;
            for (int i = 0; i < titles.length; i++) {
                canvas.drawText(titles[i], cx, y + hd.titleBase + i * hd.titleStep, mTitle);
            }
            if (!rt.summary.isEmpty()) {
                mSummary.setTextSize(SUMMARY_SP * mDp);
                String sum = TextUtils.ellipsize(rt.summary, mSummary, maxW, TextUtils.TruncateAt.END).toString();
                canvas.drawText(sum, cx, y + hd.summaryBase, mSummary);
            }

            // The trip, leg by leg, as the picture's caption; then this leg's stops.
            drawLegs(canvas, rt, cx, strip, legs);
            drawStops(canvas, rt, cx, stops, Math.max(1, rows), maxW);
        }

        /** [rows] rows of stops, from the first one's halo to the last one's. */
        private float rowsHeight(int rows) {
            return rows > 0 ? (rows - 1) * ROW_DP * mDp + 2f * ROW_HALF_DP * mDp : 0f;
        }

        /**
         * The moving landmark with its ground keyed out. OPPO's animations are opaque, rendered on
         * ColorOS's card colour (#1E1E1E) out to their box's edges, where the stills are clear
         * round their block: drawn as they are, a grey slab with a hard edge sat in the page
         * (2026-10-07). Each frame goes through KEY on the GPU, in a RenderNode of its own, which
         * takes the card colour back out - see KEY. Without a hardware canvas, drawn as it is.
         */
        private void drawArt(Canvas canvas, RectF box, float fadeFrom, float fadeTo) {
            int bw = Math.round(box.width());
            int bh = Math.round(box.height());
            if (canvas.isHardwareAccelerated() && !sKeyFailed) {
                try {
                    if (mArtNode == null) {
                        mArtNode = new android.graphics.RenderNode("mc-landmark");
                        mKey = new android.graphics.RuntimeShader(KEY);
                    }
                    // The fade's rows in the node's own coordinates; a new effect only when they move.
                    float from = fadeFrom - Math.round(box.top);
                    float to = fadeTo - Math.round(box.top);
                    if (from != mKeyFrom || to != mKeyTo) {
                        mKey.setFloatUniform("fadeFrom", from);
                        mKey.setFloatUniform("fadeTo", to);
                        mArtNode.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(
                                mKey, "content"));
                        mKeyFrom = from;
                        mKeyTo = to;
                    }
                    mArtNode.setPosition(0, 0, bw, bh);
                    android.graphics.RecordingCanvas rc = mArtNode.beginRecording(bw, bh);
                    try {
                        mArt.setBounds(0, 0, bw, bh);
                        mArt.draw(rc);
                    } finally {
                        mArtNode.endRecording();
                    }
                    canvas.save();
                    canvas.translate(Math.round(box.left), Math.round(box.top));
                    canvas.drawRenderNode(mArtNode);
                    canvas.restore();
                    return;
                } catch (Throwable t) {
                    sKeyFailed = true;
                    Xp.w(TAG + "landmark key unavailable, drawn as it is: " + t);
                }
            }
            mArt.setBounds(Math.round(box.left), Math.round(box.top), Math.round(box.left) + bw,
                    Math.round(box.top) + bh);
            mArt.draw(canvas);
        }

        /**
         * The legs in a row: a ride its line in a pill - filled with the line's colour while it is
         * ridden, an outline of it otherwise - a change's walk a small walker and its length, a
         * chevron between, a flag at the end. What is done is dimmed. The type stays as it is;
         * too wide for the page, the gaps close up, then it goes on two lines as even as they can
         * be, then a done leg is a dot and a walk its walker alone. Returns how tall it came out.
         */
        /**
         * The title as it goes on the page: whole, never cut. At TITLE_SP if it fits, else a size
         * smaller at a time down to TITLE_MIN_SP, else at that size on two lines broken where they
         * come out most even - after the space of 「前往 」 or a stop's punctuation where that is
         * near as even. Leaves mTitle at the size it settled on.
         */
        private String[] fitTitle(String title, float maxW) {
            float sp = TITLE_SP;
            mTitle.setTextSize(sp * mDp);
            while (mTitle.measureText(title) > maxW && sp > TITLE_MIN_SP) {
                sp -= 1f;
                mTitle.setTextSize(sp * mDp);
            }
            if (mTitle.measureText(title) <= maxW || title.length() < 2) return new String[] {title};
            int best = title.length() / 2;
            float bestScore = Float.MAX_VALUE;
            for (int k = 1; k < title.length(); k++) {
                float a = mTitle.measureText(title, 0, k);
                float b = mTitle.measureText(title, k, title.length());
                float score = Math.max(a, b);
                char before = title.charAt(k - 1);
                // A break at a space or a mark is worth a little unevenness.
                if (before == ' ' || before == '.' || before == '·' || before == '(' || before == '（'
                        || before == ')' || before == '）') {
                    score -= 12f * mDp;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            String one = title.substring(0, best).trim();
            String two = title.substring(best).trim();
            // Two lines still too wide is a name past any screen: the second line gives.
            two = TextUtils.ellipsize(two, mTitle, maxW, TextUtils.TruncateAt.END).toString();
            return new String[] {one, two};
        }

        private LegsPlan legsPlan(Route rt, float maxW) {
            List<Route.Leg> legs = rt.legs;
            int n = legs.size();
            LegsPlan plan = new LegsPlan(n);
            if (n == 0) return plan;
            mChip.setTextSize(15f * mDp);
            mWalk.setTextSize(14f * mDp);
            boolean[] dot = plan.dot;
            boolean[] bare = plan.bare;
            float gap = 20f * mDp;
            int split = n;
            if (legsWidth(legs, 0, n, gap, dot, bare) > maxW) gap = 14f * mDp;
            if (legsWidth(legs, 0, n, gap, dot, bare) > maxW) split = evenSplit(legs, gap, dot, bare);
            if (widest(legs, split, gap, dot, bare) > maxW) {
                for (int i = 0; i < n; i++) dot[i] = legs.get(i).state == Route.DONE;
            }
            if (widest(legs, split, gap, dot, bare) > maxW) {
                for (int i = 0; i < n; i++) bare[i] = !legs.get(i).ride;
            }
            plan.gap = gap;
            plan.split = split;
            float pillH = STRIP_DP * mDp;
            plan.height = split < n ? 2f * pillH + LINE_GAP_DP * mDp : pillH;
            return plan;
        }

        private void drawLegs(Canvas canvas, Route rt, float cx, float top, LegsPlan plan) {
            List<Route.Leg> legs = rt.legs;
            int n = legs.size();
            if (n == 0) return;
            mChip.setTextSize(15f * mDp);
            mWalk.setTextSize(14f * mDp);
            drawLegLine(canvas, legs, 0, plan.split, plan.gap, plan.dot, plan.bare, cx, top, plan.split == n);
            if (plan.split < n) {
                drawLegLine(canvas, legs, plan.split, n, plan.gap, plan.dot, plan.bare, cx,
                        top + (STRIP_DP + LINE_GAP_DP) * mDp, true);
            }
        }


        /** Where the legs break for two lines as even as they can be. */
        private int evenSplit(List<Route.Leg> legs, float gap, boolean[] dot, boolean[] bare) {
            int n = legs.size();
            int best = n;
            float bestW = Float.MAX_VALUE;
            for (int k = 1; k < n; k++) {
                float w = Math.max(legsWidth(legs, 0, k, gap, dot, bare), legsWidth(legs, k, n, gap, dot, bare));
                if (w < bestW) {
                    bestW = w;
                    best = k;
                }
            }
            return best;
        }

        private float widest(List<Route.Leg> legs, int split, float gap, boolean[] dot, boolean[] bare) {
            int n = legs.size();
            return Math.max(legsWidth(legs, 0, split, gap, dot, bare),
                    split < n ? legsWidth(legs, split, n, gap, dot, bare) : 0f);
        }

        /** Legs [from, to) on a line with their chevrons, and the flag where the trip ends. */
        private float legsWidth(List<Route.Leg> legs, int from, int to, float gap, boolean[] dot, boolean[] bare) {
            float w = 0f;
            for (int i = from; i < to; i++) w += legWidth(legs.get(i), dot[i], bare[i]) + gap;
            return w + 12f * mDp;
        }

        private float legWidth(Route.Leg l, boolean dot, boolean bare) {
            if (dot) return 8f * mDp;
            if (l.ride) return mChip.measureText(l.text) + 26f * mDp;
            return 14f * mDp + (bare ? 0f : mWalk.measureText(l.text));
        }

        private void drawLegLine(Canvas canvas, List<Route.Leg> legs, int from, int to, float gap,
                                 boolean[] dot, boolean[] bare, float cx, float top, boolean last) {
            float pillH = STRIP_DP * mDp;
            float total = legsWidth(legs, from, to, gap, dot, bare) - (last ? 0f : 12f * mDp);
            float x = cx - total / 2f;
            float mid = top + pillH / 2f;
            for (int i = from; i < to; i++) {
                Route.Leg l = legs.get(i);
                float w = legWidth(l, dot[i], bare[i]);
                float dim = l.state == Route.DONE ? 0.38f : 1f;
                if (dot[i]) {
                    mFill.setStyle(Paint.Style.FILL);
                    mFill.setColor(l.ride ? alpha(l.color, 0.5f) : 0x59ffffff);
                    canvas.drawCircle(x + w / 2f, mid, 3f * mDp, mFill);
                } else if (l.ride) {
                    mRect.set(x, top, x + w, top + pillH);
                    Paint.FontMetrics fm = mChip.getFontMetrics();
                    float base = mid - (fm.ascent + fm.descent) / 2f;
                    if (l.state == Route.NOW) {
                        mFill.setStyle(Paint.Style.FILL);
                        mFill.setColor(l.color);
                        canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mFill);
                        mChip.setColor(light(l.color) ? 0xff1a1a1c : Color.WHITE);
                    } else {
                        float sw = 1.5f * mDp;
                        mRect.inset(sw / 2f, sw / 2f);
                        mFill.setStyle(Paint.Style.STROKE);
                        mFill.setStrokeWidth(sw);
                        mFill.setColor(alpha(l.color, 0.95f * dim));
                        canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mFill);
                        mFill.setStyle(Paint.Style.FILL);
                        mChip.setColor(alpha(Color.WHITE, 0.88f * dim));
                    }
                    canvas.drawText(l.text, x + w / 2f, base, mChip);
                } else {
                    int col = alpha(Color.WHITE, 0.62f * dim);
                    drawWalker(canvas, x, mid, col);
                    if (!bare[i]) {
                        mWalk.setColor(col);
                        Paint.FontMetrics fm = mWalk.getFontMetrics();
                        canvas.drawText(l.text, x + 14f * mDp, mid - (fm.ascent + fm.descent) / 2f, mWalk);
                    }
                }
                x += w + gap;
                if (i == to - 1 && last) break;
                // The chevron to the next, the line's last one leading on to the next line.
                float mx = x - gap / 2f;
                mFill.setStyle(Paint.Style.STROKE);
                mFill.setStrokeWidth(1.6f * mDp);
                mFill.setStrokeCap(Paint.Cap.ROUND);
                mFill.setColor(0x52ffffff);
                canvas.drawLine(mx - 2.5f * mDp, mid - 4f * mDp, mx + 1.5f * mDp, mid, mFill);
                canvas.drawLine(mx + 1.5f * mDp, mid, mx - 2.5f * mDp, mid + 4f * mDp, mFill);
                mFill.setStyle(Paint.Style.FILL);
            }
            if (!last) return;
            // The flag at the end, after the last leg's chevron.
            float mx = x - gap / 2f;
            mFill.setStyle(Paint.Style.STROKE);
            mFill.setStrokeWidth(1.6f * mDp);
            mFill.setStrokeCap(Paint.Cap.ROUND);
            mFill.setColor(0x52ffffff);
            canvas.drawLine(mx - 2.5f * mDp, mid - 4f * mDp, mx + 1.5f * mDp, mid, mFill);
            canvas.drawLine(mx + 1.5f * mDp, mid, mx - 2.5f * mDp, mid + 4f * mDp, mFill);
            mFill.setColor(0xbfffffff);
            mFill.setStrokeWidth(1.8f * mDp);
            canvas.drawLine(x, mid - 8.5f * mDp, x, mid + 9f * mDp, mFill);
            mFill.setStyle(Paint.Style.FILL);
            Path flag = new Path();
            flag.moveTo(x, mid - 8.5f * mDp);
            flag.lineTo(x + 11f * mDp, mid - 4.5f * mDp);
            flag.lineTo(x, mid - 0.5f * mDp);
            flag.close();
            canvas.drawPath(flag, mFill);
        }

        /** A walker mid-stride, 9dp wide, standing on the row's middle line. */
        private void drawWalker(Canvas canvas, float x, float mid, int col) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(col);
            canvas.drawCircle(x + 4.5f * mDp, mid - 5.5f * mDp, 2.5f * mDp, mFill);
            mFill.setStyle(Paint.Style.STROKE);
            mFill.setStrokeCap(Paint.Cap.ROUND);
            mFill.setStrokeWidth(2f * mDp);
            canvas.drawLine(x + 4.5f * mDp, mid - 2f * mDp, x + 3f * mDp, mid + 7f * mDp, mFill);
            mFill.setStrokeWidth(1.6f * mDp);
            canvas.drawLine(x + 4f * mDp, mid + 2f * mDp, x + 8f * mDp, mid + 6f * mDp, mFill);
            mFill.setStyle(Paint.Style.FILL);
        }

        /**
         * This leg's stops down a bar in the line's colour as far as the train, grey after it: the
         * stop at or coming to large with a ringed dot and its tag, getting off large with a filled
         * tag in the colour of what comes next, the stops passed dimmed, folds as three dots and a
         * count. No wider than [maxW] - a tag too long loses its direction, then its end - and
         * centred on its widest row; as many rows as fit above [limit].
         */
        private void drawStops(Canvas canvas, Route rt, float cx, float top, int maxRows, float maxW) {
            int n = rt.stops.size();
            if (n == 0) return;
            float rh = ROW_DP * mDp;
            List<Route.Row> rows = rt.rows(maxRows);
            mName.setTextSize(18f * mDp);
            mNameBig.setTextSize(22f * mDp);
            mTag.setTextSize(13f * mDp);
            mTagPlain.setTextSize(13f * mDp);
            mFold.setTextSize(14f * mDp);
            String[] tags = new String[rows.size()];
            float widest = 0f;
            for (int k = 0; k < rows.size(); k++) {
                Route.Row r = rows.get(k);
                float w = rowWidth(rt, r);
                String tag = tagOf(rt, r);
                if (!tag.isEmpty()) {
                    tags[k] = fitTag(tag, r.index == n - 1 ? mTag : mTagPlain, maxW - w - 28f * mDp);
                    w += 28f * mDp + (r.index == n - 1 ? mTag : mTagPlain).measureText(tags[k]);
                }
                widest = Math.max(widest, Math.min(w, maxW));
            }
            float bx = cx - widest / 2f;
            int col = rt.color;

            float hereY = top;
            for (int k = 0; k < rows.size(); k++) {
                if (!rows.get(k).fold() && rows.get(k).index == rt.here) hereY = top + k * rh;
            }
            float trainY = rt.at ? hereY : Math.max(top, hereY - rh / 2f);
            float lastY = top + (rows.size() - 1) * rh;
            mFill.setStyle(Paint.Style.STROKE);
            mFill.setStrokeCap(Paint.Cap.ROUND);
            mFill.setStrokeWidth(5f * mDp);
            mFill.setColor(col);
            if (trainY > top) canvas.drawLine(bx, top, bx, trainY, mFill);
            mFill.setColor(0x26ffffff);
            if (lastY > trainY) canvas.drawLine(bx, trainY, bx, lastY, mFill);
            mFill.setStyle(Paint.Style.FILL);

            float textX = bx + 22f * mDp;
            for (int k = 0; k < rows.size(); k++) {
                Route.Row r = rows.get(k);
                float yy = top + k * rh;
                if (r.fold()) {
                    boolean passed = r.index < rt.here;
                    mFill.setColor(passed ? 0x99ffffff : 0x59ffffff);
                    for (int q = -1; q <= 1; q++) canvas.drawCircle(bx, yy + q * 6f * mDp, 1.4f * mDp, mFill);
                    mFold.setColor(0x6bffffff);
                    centreText(canvas, (passed ? "已过 " : "还有 ") + r.count + " 站", textX, yy, mFold);
                    continue;
                }
                int i = r.index;
                boolean here = i == rt.here;
                boolean end = i == n - 1;
                boolean passed = i < rt.here;
                if (here) {
                    mFill.setColor(alpha(col, 0.31f));
                    canvas.drawCircle(bx, yy, 11f * mDp, mFill);
                    mFill.setColor(Color.WHITE);
                    canvas.drawCircle(bx, yy, 7.5f * mDp, mFill);
                    mFill.setColor(col);
                    canvas.drawCircle(bx, yy, 3.8f * mDp, mFill);
                } else {
                    mFill.setColor(passed ? col : 0xff4a4c54);
                    canvas.drawCircle(bx, yy, (end ? 6.5f : 5.5f) * mDp, mFill);
                    mFill.setColor(0xebffffff);
                    canvas.drawCircle(bx, yy, 2.4f * mDp, mFill);
                }
                TextPaint p = here || end ? mNameBig : mName;
                p.setColor(alpha(Color.WHITE, passed ? 0.4f : here || end ? 1f : 0.78f));
                String name = TextUtils.ellipsize(rt.stops.get(i), p, maxW - 22f * mDp,
                        TextUtils.TruncateAt.END).toString();
                centreText(canvas, name, textX, yy, p);
                String tag = tags[k];
                if (tag == null || tag.isEmpty()) continue;
                float tx = textX + p.measureText(name) + 10f * mDp;
                TextPaint tp = end ? mTag : mTagPlain;
                float tw = tp.measureText(tag) + 18f * mDp;
                mRect.set(tx, yy - 11f * mDp, tx + tw, yy + 11f * mDp);
                if (end) {
                    mFill.setColor(rt.endColor);
                    canvas.drawRoundRect(mRect, 11f * mDp, 11f * mDp, mFill);
                    tp.setColor(light(rt.endColor) ? 0xff1a1a1c : Color.WHITE);
                } else {
                    float sw = 1.3f * mDp;
                    mRect.inset(sw / 2f, sw / 2f);
                    mFill.setStyle(Paint.Style.STROKE);
                    mFill.setStrokeWidth(sw);
                    mFill.setColor(0x52ffffff);
                    canvas.drawRoundRect(mRect, 11f * mDp, 11f * mDp, mFill);
                    mFill.setStyle(Paint.Style.FILL);
                    tp.setColor(0xbfffffff);
                }
                centreText(canvas, tag, tx + 9f * mDp, yy, tp);
            }
        }

        private String tagOf(Route rt, Route.Row r) {
            if (r.fold()) return "";
            if (r.index == rt.stops.size() - 1) return rt.endTag;
            return r.index == rt.here ? rt.hereTag : "";
        }

        /** [tag] in [room]: whole, else without the direction (「 往…」), else cut short. */
        private String fitTag(String tag, TextPaint p, float room) {
            if (p.measureText(tag) <= room) return tag;
            int dir = tag.lastIndexOf(" 往");
            if (dir > 0) {
                String shorter = tag.substring(0, dir);
                if (p.measureText(shorter) <= room) return shorter;
                tag = shorter;
            }
            return TextUtils.ellipsize(tag, p, Math.max(0f, room), TextUtils.TruncateAt.END).toString();
        }

        /** A row's width without its tag (the tag is fitted to what is left; see drawStops). */
        private float rowWidth(Route rt, Route.Row r) {
            if (r.fold()) return 22f * mDp + mFold.measureText("还有 " + r.count + " 站");
            int i = r.index;
            boolean end = i == rt.stops.size() - 1;
            boolean here = i == rt.here;
            return 22f * mDp + (here || end ? mNameBig : mName).measureText(rt.stops.get(i));
        }

        /** [text] from [x], its middle on [y]. */
        private void centreText(Canvas canvas, String text, float x, float y, TextPaint p) {
            Paint.FontMetrics fm = p.getFontMetrics();
            canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    private static int alpha(int colour, float a) {
        return (Math.round(Math.max(0f, Math.min(1f, a)) * 255f) << 24) | (colour & 0xffffff);
    }

    // ---------------------------------------------------------------- the demo

    /**
     * A ride on Beijing's line 1 towards 四惠东, coming to 天安门东 (by the Forbidden City, so the
     * landmark shows), getting off at 王府井 to change to line 8 - so the overview's last stop is a
     * transfer with an 8 on it. The shape is the entity AmapTransitShare sends.
     */
    static final String DEMO = "{\"status\":\"3\",\"entityId\":\"demo\",\"destCitycode\":\"010\","
            + "\"destStation\":\"南锣鼓巷\",\"exitName\":\"A口\",\"guideInfo\":\"\","
            + "\"deepLink\":\"amapuri://amap\",\"totalDuration\":1500,"
            + "\"naviInfo\":[{\"transportType\":\"0\",\"walkingOrRideLength\":\"420\","
            + "\"walkingOrRideDuration\":\"360\"},"
            + "{\"isCurrent\":true,\"transportType\":\"2\",\"lineName\":\"1号线\","
            + "\"lineDirection\":\"四惠东\",\"lineBgColor\":\"#C23A30\",\"lineTextColor\":\"#FFFFFF\","
            + "\"remainStations\":2,"
            + "\"on_station\":{\"stationName\":\"西单\"},"
            + "\"off_station\":{\"stationName\":\"王府井\",\"isTransferStation\":true,"
            + "\"coord\":{\"lat\":39.908,\"lng\":116.411},"
            + "\"port_list\":[{\"name\":\"A口\",\"coord\":{\"lat\":39.9085,\"lng\":116.4106}}]},"
            + "\"via_st_list\":[{\"name\":\"天安门西\",\"coord\":{\"lat\":39.9075,\"lng\":116.3912}},"
            + "{\"name\":\"天安门东\",\"coord\":{\"lat\":39.9078,\"lng\":116.4013}}]},"
            + "{\"transportType\":\"0\",\"walkingOrRideLength\":\"120\",\"walkingOrRideDuration\":\"120\"},"
            + "{\"transportType\":\"2\",\"lineName\":\"8号线\",\"lineDirection\":\"瀛海\","
            + "\"lineBgColor\":\"#009B6B\",\"on_station\":{\"stationName\":\"王府井\"},"
            + "\"off_station\":{\"stationName\":\"南锣鼓巷\"},"
            + "\"via_st_list\":[{\"name\":\"金鱼胡同\"},{\"name\":\"中国美术馆\"}]}]}";
}
