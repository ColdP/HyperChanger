// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/** The module's own square artwork. The OEM clock and media card remain above this layer. */
public final class CoverCardLayer extends View implements Choreographer.FrameCallback {
    /** Volatile: the hot-reload check reads animating() from the framework's thread. */
    private static volatile CoverCardLayer sView;
    private static volatile Prepared sPending;
    private static volatile int sGeneration;
    private static volatile CoverCardStyle sStyle = CoverCardStyle.defaults();
    private static volatile boolean sPlayingState;

    private static final class Prepared {
        final Bitmap art;
        final Bitmap aodBackdrop;
        final int generation;
        Prepared(Bitmap art, Bitmap aodBackdrop, int generation) {
            this.art = art;
            this.aodBackdrop = aodBackdrop;
            this.generation = generation;
        }
        void recycle() {
            art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    private Prepared current, previous;
    private long changedAt;
    private long lastFrame;
    /** Volatile: written on the main thread, read by the hot-reload check off it. */
    private volatile boolean ticking;
    private float opacity;
    private final CardSpring scale = new CardSpring();
    private boolean playing = sPlayingState;
    private boolean exitWithCard;
    private CoverCardStyle style = sStyle;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF square = new RectF();
    /** Per-frame scratch, kept rather than allocated on every draw. */
    private final int[] tmpLoc = new int[2];
    private ViewTreeObserver geometryObserver;
    private ViewTreeObserver.OnPreDrawListener geometryListener;
    private boolean lastLockEligible;
    private int lastWidth = -1, lastHeight = -1, lastTop = Integer.MIN_VALUE;
    private float lastClock = Float.NaN, lastMedia = Float.NaN;

    private final Wash wash;
    /** Dozed under the OEM's big clock and not yet back to a settled lock screen; see doFrame. */
    private boolean afterBigClock;
    /** 0..1, how far the square has grown out of RISE_FROM; 1 everywhere but the big clock. */
    private float rise = 1f;
    /** The size the square is uncovered at, under the big clock's retreating digits. */
    private static final float RISE_FROM = 0.92f;
    /** How far down the square the big clock has to reach for it to be fully covered. */
    private static final float REVEAL_SPAN = 0.8f;
    /**
     * Falling asleep, the wallpaper goes to black in ~200ms along (1 - t)^2 (read frame by frame
     * off a recording), and the square goes with it. It used to ease at the clock's response and
     * was still half lit when the doze took the layer away, so it hung over black and then cut.
     */
    private static final float SLEEP_FADE_S = 0.2f;
    /** When this fall began (frame time, 0 when not falling) and the opacity it began from. */
    private long fallAt;
    private float fallFrom;
    /**
     * Waking, the wallpaper comes back from black in ~350ms along 1 - (1 - t)^3, read off the
     * same recording. The square was lit in one frame at 3/4 while the screen was still dark.
     */
    private static final float WAKE_FADE_S = 0.35f;
    /** How lit the wallpaper is taken to be, 0..1: what a wake starts from. */
    private float lit = 1f;
    private long wakeAt;
    private float wakeFrom;

    private CoverCardLayer(Context context) {
        super(context);
        wash = new Wash(context);
        setClickable(false);
        setFocusable(false);
    }

    static void attach(ViewGroup layer) {
        if (layer == null) return;
        final ViewGroup target = layer;
        Main.main().post(new Runnable() {
            @Override public void run() { attachNow(target); }
        });
    }

    private static void attachNow(ViewGroup layer) {
        if (!layer.isAttachedToWindow()) return;
        CoverCardLayer v = sView;
        if (v == null || v.getContext() != layer.getContext()) {
            if (v != null) {
                v.stop();
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                if (v.wash.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.wash.getParent()).removeView(v.wash);
                }
                if (v.previous != null && v.previous != sPending) v.previous.recycle();
                if (v.current != null && v.current != sPending) v.current.recycle();
            }
            v = new CoverCardLayer(layer.getContext());
            v.scale.snap(sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
            sView = v;
        }
        if (v.getParent() != layer) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            layer.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Gen.ownView(layer, v);
        }
        // Called on every entry; bringToFront lays the layer out again, so only when needed.
        if (layer.getChildAt(layer.getChildCount() - 1) != v) v.bringToFront();
        // Beside keyguard_root_view rather than inside it: the doze zooms that view to 0.95 and
        // its bounds clip after the zoom, so nothing under it reaches the screen edge - a
        // counter-scaled wash in the layer was measured still cut to 95%. Just below it keeps
        // the same stacking the layer gave. The layer is the fallback, zoom and all.
        ViewGroup host = layer;
        View below = v;
        View zoomed = zoomedRoot(layer);
        if (zoomed != null && zoomed.getParent() instanceof ViewGroup) {
            host = (ViewGroup) zoomed.getParent();
            below = zoomed;
        }
        if (v.wash.getParent() != host) {
            if (v.wash.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.wash.getParent()).removeView(v.wash);
            }
            host.addView(v.wash, host.indexOfChild(below), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Gen.ownView(host, v.wash);
        }
        v.style = sStyle;
        v.playing = sPlayingState;
        v.watchGeometry(layer);
        v.adoptPending();
        v.start();
    }

    /** keyguard_root_view, the view the doze zooms, found upwards from the layer. */
    private static View zoomedRoot(View from) {
        int id = from.getResources().getIdentifier("keyguard_root_view", "id",
                "com.android.systemui");
        if (id == 0) return null;
        for (Object p = from; p instanceof View; p = ((View) p).getParent()) {
            if (((View) p).getId() == id) return (View) p;
        }
        return null;
    }

    private void watchGeometry(ViewGroup layer) {
        if (geometryObserver == layer.getViewTreeObserver() && geometryListener != null) return;
        unwatchGeometry();
        geometryObserver = layer.getViewTreeObserver();
        geometryListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() { android.os.Trace.beginSection("MC coverGeom"); try {
                // This parent is also drawn behind the unlocked notification shade. The card's
                // animation stops when it settles, and ACTION_USER_PRESENT is not guaranteed to
                // arrive before the shade uses this layer. Enforce the lock-screen condition on
                // the parent's draw, even when no card frame has been requested.
                if (!layerVisible()) {
                    lastLockEligible = false;
                    hideOutsideKeyguard("parent pre-draw");
                    return true;
                }
                if (!lastLockEligible) {
                    lastLockEligible = true;
                    start();
                }
                // The pad coming up or going moves nothing of ours, so nothing else asks for the
                // frames the blur eases over.
                if (!ticking && bouncerP != bouncerTarget()) start();
                followSwipeFade();
                // Every frame of the window while the wash is up: the doze zoom it has to undo
                // animates, and nothing else of ours is drawing frames through it.
                if (wash.getVisibility() == VISIBLE) wash.fit();
                int[] loc = tmpLoc;
                getLocationOnScreen(loc);
                float clock = ClockCollapse.contentBottomFor(CoverCardLayer.this);
                float media = ClockCollapse.unzoomY(CoverCardLayer.this, Main.coverCardMediaTop());
                if (lastWidth != getWidth() || lastHeight != getHeight() || lastTop != loc[1]
                        || Float.floatToIntBits(lastClock) != Float.floatToIntBits(clock)
                        || Float.floatToIntBits(lastMedia) != Float.floatToIntBits(media)) {
                    lastWidth = getWidth();
                    lastHeight = getHeight();
                    lastTop = loc[1];
                    lastClock = clock;
                    lastMedia = media;
                    // In this frame, not the next: the card has already been moved for it.
                    if (carries(ClockCollapse.phase()) && opacity > 0f) {
                        CoverCardStyle.Rect goal = placeNow();
                        if (goal != null) {
                            carry(goal, true);
                            invalidate();
                        }
                    }
                    start();
                }
                return true;
            } finally { android.os.Trace.endSection(); } }
        };
        Gen.preDraw(geometryObserver, geometryListener);
    }

    private void unwatchGeometry() {
        if (geometryObserver != null && geometryListener != null
                && geometryObserver.isAlive()) {
            Gen.unpreDraw(geometryObserver, geometryListener);
        }
        geometryObserver = null;
        geometryListener = null;
        lastLockEligible = false;
        lastWidth = lastHeight = -1;
    }

    static void style(CoverCardStyle config) {
        sStyle = config;
        CoverCardLayer v = sView;
        if (v != null) {
            v.style = config;
            // The pre-draw guard runs on every frame of the shade window, lock screen or not.
            // Only the square needs it, so the full-screen cover does not pay for it.
            if (config.mode == CoverCardStyle.CARD) {
                if (v.getParent() instanceof ViewGroup) v.watchGeometry((ViewGroup) v.getParent());
                v.start();
            } else {
                v.unwatchGeometry();
                v.hideImmediately();
            }
        }
    }

    /** Called on the existing art worker. Never retain the player's bitmap. */
    static void publish(Bitmap source) {
        final int generation = ++sGeneration;
        if (source == null || source.isRecycled()) return;
        Bitmap readable = null, art = null, aodBackdrop = null;
        try {
            readable = source.getConfig() == Bitmap.Config.HARDWARE
                    ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            int w = readable.getWidth(), h = readable.getHeight();
            // The artwork's own shape, cropped only past CoverCardStyle.MAX_ASPECT. It was
            // cropped to a square here, which cut the sides off a video's cover.
            float aspect = CoverCardStyle.aspect(w, h);
            int cw = Math.min(w, Math.round(h * aspect));
            int ch = Math.min(h, Math.round(w / aspect));
            Rect crop = new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
            float down = Math.min(1f, 512f / Math.max(cw, ch));
            art = Bitmap.createBitmap(Math.max(1, Math.round(cw * down)),
                    Math.max(1, Math.round(ch * down)), Bitmap.Config.ARGB_8888);
            new Canvas(art).drawBitmap(readable, crop,
                    new Rect(0, 0, art.getWidth(), art.getHeight()),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            // The AOD's own dimming can flatten the wallpaper almost to black. A small copy of
            // the same static blur is drawn over it at low alpha only in the full-screen AOD.
            // Prepare it with the artwork, off the UI thread, then reuse it without animation.
            try {
                int backdropW = 256;
                int backdropH = Math.min(768, Math.max(256,
                        Math.round(backdropW * Main.screenHeight()
                                / (float) Main.screenWidth())));
                aodBackdrop = CoverCompose.cardBackground(readable, backdropW, backdropH);
            } catch (Throwable t) {
                Xp.w("[MCCard] AOD backdrop preparation failed: " + t);
            }
            final Prepared p = new Prepared(art, aodBackdrop, generation);
            art = null;
            aodBackdrop = null;
            final Prepared old = sPending;
            sPending = p;
            Main.main().post(new Runnable() {
                @Override public void run() {
                    CoverCardLayer v = sView;
                    // Decided here, on the thread that adopts: read from the worker, `current`
                    // could become `old` between the check and the recycle, and the next draw
                    // would throw inside SystemUI.
                    if (old != null && old != sPending && (v == null
                            || (old != v.current && old != v.previous))) old.recycle();
                    if (v == null) return;
                    // The first art has nothing to keep pace with. A swap waits for the
                    // wallpaper to start its own - see releaseHeld() - with a ceiling in case
                    // that word never comes.
                    Main.main().removeCallbacks(ADOPT_HELD);
                    if (v.current == null) v.adoptPending();
                    else Main.main().postDelayed(ADOPT_HELD, HOLD_MAX_MS);
                }
            });
        } catch (Throwable t) {
            Xp.w("[MCCard] artwork preparation failed: " + t);
        } finally {
            if (readable != null && readable != source) readable.recycle();
            if (art != null) art.recycle();
            if (aodBackdrop != null) aodBackdrop.recycle();
        }
    }

    /** Longer than the 70-160ms the wallpaper was measured behind by, short of feeling stuck. */
    private static final long HOLD_MAX_MS = 400L;

    private static final Runnable ADOPT_HELD = new Runnable() {
        @Override public void run() {
            CoverCardLayer v = sView;
            if (v != null) v.adoptPending();
        }
    };

    /**
     * The blurred background under the square has started changing - the wallpaper process
     * says so (op wpart), or the video path put it up itself - so the square turns over with it.
     */
    static void releaseHeld() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(new Runnable() { @Override public void run() { releaseHeld(); } });
            return;
        }
        Main.main().removeCallbacks(ADOPT_HELD);
        ADOPT_HELD.run();
    }

    private static boolean layerVisible() {
        return Main.coverCardVisible() || Main.coverCardBackdropInAod();
    }

    static void clear() {
        ++sGeneration;
        Main.main().post(new Runnable() {
            @Override public void run() {
                CoverCardLayer v = sView;
                if (v != null) v.start();
            }
        });
    }

    /**
     * Retirement: the layer and its wash out of the tree, the frames stopped, the bitmaps
     * released. clear() is the track-change path - it animates the square away and expects the
     * view to come back; here the view's class is the one going away.
     *
     * <p>sPending is kept: it is the artwork already prepared for the square, and a dry run
     * puts the square straight back up. A real retirement (Step 7) clears it with the cache.
     */
    /** Whether the square is being drawn frame by frame (an entrance, a track change, a swipe). */
    static boolean animating() {
        CoverCardLayer v = sView;
        return v != null && v.ticking;
    }

    static void retire() {
        // NOT ++sGeneration: the kept sPending is stamped with the current one, and adoptPending
        // is what puts the square straight back up on the rebuild. The queued adopts the bump
        // used to invalidate are gone with Main's messages, which the sweep clears.
        CoverCardLayer v = sView;
        sView = null;
        if (v == null) return;
        v.stop();
        v.unwatchGeometry();
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        if (v.wash.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.wash.getParent()).removeView(v.wash);
        }
        Prepared hold = sPending;
        if (v.previous != null && v.previous != hold) v.previous.recycle();
        if (v.current != null && v.current != hold) v.current.recycle();
        v.previous = null;
        v.current = null;
    }

    public static void refresh() {
        CoverCardLayer v = sView;
        if (v != null) {
            v.start();
            v.invalidate();
        }
    }

    static void hideNow() {
        CoverCardLayer v = sView;
        if (v == null) return;
        v.hideImmediately();
    }

    private void hideOutsideKeyguard(String from) {
        if (getVisibility() == VISIBLE) {
            Xp.log("[MCCard] hidden outside keyguard on " + from);
        }
        if (getVisibility() != GONE || ticking || opacity != 0f) hideImmediately();
    }

    /**
     * How far the PIN pad's blur has come over the square, 0..1.
     *
     * The OEM blurs the lock screen under the pad, but this layer is the keyguard's background
     * and is not among what it blurs, so the square stood sharp through the pad. It used to be
     * hidden for the pad instead, which cut it out as the pad came up and popped it back as the
     * pad went. It blurs itself now, eased with the pad - the same as LyricView.followBouncer.
     */
    private float bouncerP;
    private static final float BOUNCER_BLUR_DP = 24f;
    /**
     * Time constant of the ease, in seconds. Short: the level is the pad's own fade already
     * (Main.bouncerLevel), so this only smooths it. At 80ms it trailed the OEM's blur both ways.
     */
    private static final float BOUNCER_TAU = 0.03f;

    private static float bouncerTarget() {
        return Main.bouncerLevel();
    }

    /** @return whether the blur is still moving, so the frames keep coming until it lands */
    private boolean followBouncer(float dt) {
        float want = bouncerTarget();
        if (bouncerP == want) return false;
        // The first step of a frame has no dt; it still has to start moving.
        float k = dt <= 0f ? 0.25f : (float) (1.0 - Math.exp(-dt / BOUNCER_TAU));
        bouncerP += (want - bouncerP) * k;
        if (Math.abs(want - bouncerP) < 0.01f) bouncerP = want;
        float r = bouncerP * BOUNCER_BLUR_DP * getResources().getDisplayMetrics().density;
        if (r < 0.5f) r = 0f;
        // A new effect is a new blur node for the render thread; only when the radius moved.
        if (r != blurSet) {
            blurSet = r;
            setRenderEffect(r == 0f ? null : android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.DECAL));
        }
        return bouncerP != want;
    }

    /** The radius the view's blur was last set to; 0 is none. */
    private float blurSet = -1f;

    /**
     * The swipe's fade, taken from the media card's.
     *
     * Swiping up, the OEM fades the keyguard's foreground through transitionAlpha on two
     * containers - shared_notification_container, which holds the media card, and
     * miui_keyguard_clock_container - and on nothing between this layer and the window, so the
     * square stood out bright and whole while the clock and the card went (`op alphasweep`,
     * 2026-09-24: both at 0.63 at the deepest, keyguard_background_layer's chain untouched).
     * Its dim and blur, what the OEM gives its own background views, was tried first and was
     * not it. Read every frame of the window, so it is the finger's own curve.
     *
     * Not in the doze - the square fades with the wallpaper there, see SLEEP_FADE_S - and not
     * under the pad, which keeps the square and blurs it (followBouncer).
     */
    private View fadeSource;
    private static int sFadeSourceId = -1;

    private void followSwipeFade() {
        float a = 1f;
        if (ClockCollapse.phase() != ClockCollapse.Phase.AOD) {
            View src = fadeSource;
            if (src == null || !src.isAttachedToWindow()) {
                if (sFadeSourceId == -1) {
                    sFadeSourceId = getResources().getIdentifier("shared_notification_container",
                            "id", "com.android.systemui");
                }
                src = sFadeSourceId == 0 ? null : getRootView().findViewById(sFadeSourceId);
                fadeSource = src;
            }
            if (src != null) a = Math.max(src.getTransitionAlpha(), bouncerP);
        }
        if (getTransitionAlpha() != a) setTransitionAlpha(a);
    }

    private void hideImmediately() {
        stop();
        opacity = 0f;
        videoAlpha = 0f;
        CardVideo.want(false);
        exitWithCard = false;
        if (getVisibility() != GONE) setVisibility(GONE);
        wash.hideNow();
    }

    static void entering() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = false;
    }

    static void leaving() {
        CoverCardLayer v = sView;
        if (v != null) v.exitWithCard = v.opacity > 0.05f
                && !LockLyrics.wantsAttached();
    }

    static void playback(boolean on) {
        sPlayingState = on;
        CoverCardLayer v = sView;
        if (v != null) {
            v.playing = on;
            // An invisible card has no scale transition to preserve. Place it at the correct
            // playback size before a later thumbnail morph first asks for its destination.
            if (v.opacity <= 0f && !CoverMorphLayer.cardSuppressed()) {
                v.scale.snap(on ? CardSpring.PLAYING : CardSpring.PAUSED);
            }
            v.start();
        }
    }

    /** The square's current art, for the morph to fly - null when it has none yet. */
    static Bitmap currentArt() {
        CoverCardLayer v = sView;
        Prepared p = v == null ? null : v.current;
        return p == null || p.art.isRecycled() ? null : p.art;
    }

    /** For `op cardstate`. */
    static String describe() {
        CoverCardLayer v = sView;
        if (v == null) return "view=none";
        // The ancestors' scale and whether each one clips its children: the AOD wash is drawn
        // past this view's bounds, which only reaches the screen edge if nothing clips it.
        StringBuilder chain = new StringBuilder();
        for (Object p = v.getParent(); p instanceof ViewGroup; p = ((ViewGroup) p).getParent()) {
            ViewGroup g = (ViewGroup) p;
            String id = "?";
            try {
                if (g.getId() != View.NO_ID) id = g.getResources().getResourceEntryName(g.getId());
            } catch (Throwable ignored) {
            }
            chain.append(' ').append(id).append("[s=").append(g.getScaleX())
                    .append(g.getClipChildren() ? ",clip" : "").append(']');
        }
        Wash w = v.wash;
        int[] at = new int[2];
        w.getLocationOnScreen(at);
        chain.append(" wash[vis=").append(w.getVisibility() == VISIBLE)
                .append(' ').append(w.getWidth()).append('x').append(w.getHeight())
                .append(" s=").append(w.getScaleX()).append('/').append(w.getScaleY())
                .append(" pivot=").append(w.getPivotX()).append(',').append(w.getPivotY())
                .append(" at=").append(at[0]).append(',').append(at[1])
                .append(" screen=").append(Main.screenWidth()).append('x')
                .append(Main.screenHeight()).append(']');
        return "aodPlace=[" + v.aodPlaceNote + "] view.playing=" + v.playing
                + " scale=" + v.scale.value
                + " ticking=" + v.ticking + " opacity=" + v.opacity
                + " attached=" + v.isAttachedToWindow()
                + " fade=" + v.getTransitionAlpha() + " chain=" + chain;
    }

    static float renderedScale(ViewGroup layer) {
        CoverCardLayer v = sView;
        return v != null && v.getParent() == layer ? v.scale.value
                : (sPlayingState ? CardSpring.PLAYING : CardSpring.PAUSED);
    }

    /** A hidden card is kept drawing internally until it can take the morph without a jump. */
    static boolean morphHandoffReady() {
        CoverCardLayer v = sView;
        if (v == null || v.current == null || v.getParent() == null) return false;
        float target = v.playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        return v.opacity >= 0.995f && v.scale.atRest(target);
    }

    private void adoptPending() {
        Prepared p = sPending;
        if (p == null || p == current || p.generation != sGeneration) return;
        if (previous != null) previous.recycle();
        previous = current;
        current = p;
        changedAt = SystemClock.uptimeMillis();
        start();
    }

    private void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(new Runnable() { @Override public void run() { start(); } });
            return;
        }
        if (ticking) return;
        ticking = true;
        lastFrame = 0L;
        // A temporary vote for panels that honour it. The spring is sampled on every VSYNC,
        // and the vote is removed as soon as it settles; some SystemUI windows still cap at 60Hz.
        setRequestedFrameRate(120f);
        Gen.frame(this);
    }

    private void stop() {
        if (ticking) Gen.unframe(this);
        if (ticking) setRequestedFrameRate(0f);
        ticking = false;
        lastFrame = 0L;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // A detached/re-attached keyguard layer gets a new ViewTreeObserver. Keep the guard even
        // when SystemUI reuses this same card view for the shade.
        if (style.mode == CoverCardStyle.CARD && getParent() instanceof ViewGroup) {
            watchGeometry((ViewGroup) getParent());
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        unwatchGeometry();
        super.onDetachedFromWindow();
    }

    /** Where the square is drawn, eased towards placeNow(); NaN until it has a place. */
    private float drawX = Float.NaN, drawY, drawSide;
    /** The goal as last seen, so the part of its move the media card made can be carried over. */
    private float goalX = Float.NaN, goalY, goalSide;
    /**
     * A goal move bigger than this share of the square in one frame is a jump - a rebuilt media
     * card - and is eased. Under a finger the card moves a few dozen px a frame.
     */
    private static final float CARRY_MAX = 0.25f;
    /** The last place the lit lock screen gave it - what the doze keeps. */
    private CoverCardStyle.Rect lockPlace;
    /**
     * The place of the landed cover look, Phase.ON only - what the big clock's fall and wake
     * keep. Not lockPlace: that one is still written while the clock grows into the doze.
     */
    private CoverCardStyle.Rect restPlace;

    /**
     * 0..1, how much of the square's place the big clock has left on its way up: 0 while the
     * digits still reach REVEAL_SPAN of the way down it, 1 once they have cleared its top. The square is held
     * at restPlace and grows and brightens with this, so it comes out from under the clock at
     * the clock's own pace - no gap before it, and no place of its own that it has to jump from.
     */
    private float reveal() {
        CoverCardStyle.Rect r = restPlace;
        float clock = ClockCollapse.contentBottomFor(this);
        if (r == null || Float.isNaN(clock) || r.side <= 0f) return 0f;
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        // The big clock was measured reaching ~88% of the way down the square, not past it.
        float span = r.side * REVEAL_SPAN;
        float p = (r.y + span - (clock - loc[1])) / span;
        p = Math.max(0f, Math.min(1f, p));
        return p * p * (3f - 2f * p);
    }

    /**
     * The square's place from the live clock and media card, in this view's coordinates.
     *
     * Both as drawn, not as laid out: a swipe zooms the clock's and the card's containers and
     * not this layer, so a place taken from their layout sat still while they came in round it.
     */
    private CoverCardStyle.Rect placeNow() {
        float clock = ClockCollapse.contentBottomFor(this);
        float media = ClockCollapse.unzoomY(this, Main.coverCardMediaTop());
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        return style.place(getWidth(), getHeight(), getResources().getDisplayMetrics().density,
                clock - loc[1], media - loc[1]);
    }

    /**
     * Moves the drawn place one frame towards where the square belongs.
     *
     * Through the doze it keeps the lock screen's place: asked live, the media card's AOD stand-in
     * (70% of the screen) re-centred it, and it jumped 63px on the way in and back 11px on the
     * wake, while everything around it slid with the doze zoom. It sits inside that zoom, so
     * holding still in its own coordinates is exactly sliding with it. Any other change of
     * place - the wake landing, a rebuilt media card - is eased at the 缩放动画阻尼 response
     * instead of cut. A square that is not showing yet just takes its place.
     *
     * @return whether it is still on its way
     */
    private boolean followPlace(ClockCollapse.Phase phase, float dt, float response,
                                float target) {
        CoverCardStyle.Rect goal;
        if (afterBigClock && restPlace != null) {
            // Placed live, the big clock's bottom pushes it small and low against the media card.
            goal = restPlace;
        } else if (phase == ClockCollapse.Phase.AOD) {
            CoverCardStyle.Rect held = lockPlace != null ? lockPlace : placeNow();
            goal = held == null ? null : aodPlace(held);
        } else {
            goal = placeNow();
            if (goal != null) lockPlace = goal;
            if (goal != null && phase == ClockCollapse.Phase.ON) restPlace = goal;
        }
        if (goal == null) {
            goalX = Float.NaN;
            return false;
        }
        carry(goal, carries(phase));
        if (Float.isNaN(drawX) || opacity <= 0f) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
            return false;
        }
        float k = Math.min(1f, dt * 3f / response);
        drawX += (goal.x - drawX) * k;
        drawY += (goal.y - drawY) * k;
        drawSide += (goal.side - drawSide) * k;
        boolean moving = Math.abs(goal.x - drawX) > 0.5f || Math.abs(goal.y - drawY) > 0.5f
                || Math.abs(goal.side - drawSide) > 0.5f;
        if (!moving) {
            drawX = goal.x;
            drawY = goal.y;
            drawSide = goal.side;
        }
        return moving;
    }

    /**
     * How long into a doze the place keeps being re-derived. aodPlace() reads things that are
     * still moving when the doze starts - the zoom (529ms to settle), the held clock, the compact
     * media card swapping in - and the loop stops the first frame the square has caught up with
     * its goal. It stopped at k=0.967 with the clock 32px from where it came to rest, and the
     * gaps came out 158/131 instead of equal (2026-09-24).
     */
    private static final long AOD_SETTLE_NS = 1_500_000_000L;
    /** When the current doze began, 0 outside one. */
    private long aodSince;

    /** What aodPlace() last worked from, for `op cardstate`. */
    private String aodPlaceNote = "none";

    /**
     * The held place, moved to sit centred between the doze's own clock and its own media card.
     *
     * Held alone it rode the doze zoom exactly, and still came out off-centre, because the two
     * things it sits between do not ride that zoom: the held clock sits higher than the zoomed
     * lock screen's would (59px) and the AOD swaps in a compact media card whose top is lower
     * (135px). Measured 2026-09-24: gaps 48/50px lit, 105/183px in the AOD. Size and x are kept
     * - only the height it sits at follows the doze - and the move goes through followPlace's
     * easing, so it slides there with the dim rather than jumping.
     *
     * The live media card is read here, not coverCardMediaTop()'s stand-in: that one exists for
     * the place the doze is not allowed to re-derive. Anything unreadable, or no room, keeps held.
     */
    private CoverCardStyle.Rect aodPlace(CoverCardStyle.Rect held) {
        // Where the dozing clock is drawn, not the pose: inside the doze the two part by the
        // OEM's zoom and shifts (24px, which was the whole of what was left off-centre).
        float clock = ClockCollapse.contentBottomDrawn();
        float media = Main.liveMediaTop();
        float k = ancestorScale();
        if (Float.isNaN(clock) || Float.isNaN(media) || k <= 0f || media <= clock) {
            aodPlaceNote = "held clock=" + clock + " media=" + media;
            return held;
        }
        int[] loc = tmpLoc;
        getLocationOnScreen(loc);
        // Screen distances into this view's own coordinates, which the zoom above it scales.
        float top = (clock - loc[1]) / k, bottom = (media - loc[1]) / k;
        float room = bottom - top - held.side;
        if (room < 0f) {
            aodPlaceNote = "no room clock=" + clock + " media=" + media;
            return held;
        }
        float y = top + room / 2f;
        aodPlaceNote = "centred clock=" + clock + " media=" + media + " k=" + k
                + " y=" + Math.round(held.y) + "->" + Math.round(y);
        return new CoverCardStyle.Rect(held.x, y, held.side);
    }

    /** The product of every ancestor's vertical scale - the doze's 0.95 among them. */
    private float ancestorScale() {
        float k = 1f;
        for (Object p = getParent(); p instanceof View; p = ((View) p).getParent()) {
            k *= ((View) p).getScaleY();
        }
        return k;
    }

    /**
     * On the settled lock screen the goal only moves when the media card does - dragged, or
     * springing back after the finger lets go - and the square has to keep off it frame for
     * frame. Eased there, it lagged its own easing plus a frame and ran over the card.
     */
    private boolean carries(ClockCollapse.Phase phase) {
        return phase == ClockCollapse.Phase.ON && !afterBigClock;
    }

    /** Moves the drawn place by however far the goal moved since it was last seen. */
    private void carry(CoverCardStyle.Rect goal, boolean follow) {
        if (follow && !Float.isNaN(goalX) && !Float.isNaN(drawX)) {
            float dy = goal.y - goalY, ds = goal.side - goalSide;
            if (Math.abs(dy) + Math.abs(ds) < goal.side * CARRY_MAX) {
                drawX += goal.x - goalX;
                drawY += dy;
                drawSide += ds;
            }
        }
        goalX = goal.x;
        goalY = goal.y;
        goalSide = goal.side;
    }

    @Override public void doFrame(long nowNs) { android.os.Trace.beginSection("MC coverCardFrame"); try {
        if (!ticking || !isAttachedToWindow()) { stop(); return; }
        float dt = lastFrame == 0L ? 1f / 60f
                : Math.min(0.05f, Math.max(0f, (nowNs - lastFrame) / 1e9f));
        lastFrame = nowNs;
        boolean blurring = followBouncer(dt);
        ClockCollapse.Phase phase = ClockCollapse.phase();
        if (phase != ClockCollapse.Phase.AOD) aodSince = 0L;
        else if (aodSince == 0L) aodSince = nowNs;
        boolean inAod = Main.coverCardInAod();
        boolean visible = style.mode == CoverCardStyle.CARD && Main.coverCardVisible();
        // 0.2.2 can keep lyrics in the full-screen AOD. The selected lyric page owns this space.
        boolean lyrics = LockLyrics.wantsAttached();
        // A doze under the OEM's big clock hides the square. Its wake holds the square at the
        // place the landed lock screen gave it and lets the collapsing clock uncover it - see
        // reveal(). Shown at once it sat full size across the big digits, and placed live it was
        // squeezed small and low under the shrinking clock, then grew back.
        //
        // The fall counts, not only the doze: pressed again before the big clock has settled, the
        // clock never reaches Phase.AOD - it goes EXIT (into the AOD) straight back to ENTER.
        if (!ClockCollapse.aodHeld() && (phase == ClockCollapse.Phase.AOD
                || (phase == ClockCollapse.Phase.EXIT && !ClockCollapse.exiting()))) {
            afterBigClock = true;
        } else if (phase == ClockCollapse.Phase.ON || phase == ClockCollapse.Phase.OFF) {
            afterBigClock = false;
        }
        boolean bigWake = afterBigClock && phase == ClockCollapse.Phase.ENTER;
        // The other half: the clock growing into the big one covers the square as it comes down.
        boolean bigFall = afterBigClock && phase == ClockCollapse.Phase.EXIT
                && !ClockCollapse.exiting();
        boolean underBig = bigWake || bigFall;
        float target = visible && current != null && (!afterBigClock || underBig)
                && (phase == ClockCollapse.Phase.EXIT && !bigFall ? exitWithCard : !lyrics)
                ? (inAod ? 1f : underBig ? reveal() : MediaCard.cardProgress()) : 0f;
        float response = Math.max(0.18f, Main.sClockResponse);
        // Tied to the clock's own flight on the lit screen, and to the big clock's edge on the way
        // into and out of its doze. Otherwise falling asleep it eases.
        if (!bigFall) fallAt = 0L;
        if (!bigWake) wakeAt = 0L;
        if (!afterBigClock) lit = 1f;
        else if (phase == ClockCollapse.Phase.AOD) lit = 0f;
        if (bigFall) {
            // Out with the wallpaper as well, for a big clock that stops short of covering it.
            if (fallAt == 0L) {
                fallAt = nowNs;
                fallFrom = opacity;
            }
            float u = Math.min(1f, (nowNs - fallAt) / 1e9f / SLEEP_FADE_S);
            lit = (1f - u) * (1f - u);
            opacity = Math.min(target, fallFrom * lit);
        } else if (bigWake) {
            // And back in with it - from wherever a fall pressed short of the doze had left it.
            if (wakeAt == 0L) {
                wakeAt = nowNs;
                wakeFrom = lit;
            }
            float u = 1f - Math.min(1f, (nowNs - wakeAt) / 1e9f / WAKE_FADE_S);
            lit = wakeFrom + (1f - wakeFrom) * (1f - u * u * u);
            opacity = target * lit;
        } else if ((inAod && lyrics) || phase == ClockCollapse.Phase.ENTER
                || (phase == ClockCollapse.Phase.EXIT && Main.screenOn())) {
            opacity = target;
        } else {
            opacity += (target - opacity) * Math.min(1f, dt * 3f / response);
        }
        // Grows with the reveal on the way up, and shrinks under the digits on the way down.
        if (afterBigClock) rise = underBig ? target : Math.min(rise, opacity);
        else if (opacity <= 0f) rise = 1f;
        else rise += (1f - rise) * Math.min(1f, dt * 3f / response);
        if (rise > 0.998f) rise = 1f;
        if (Math.abs(opacity - target) < 0.002f) opacity = target;
        float scaleTarget = playing ? CardSpring.PLAYING : CardSpring.PAUSED;
        if (phase == ClockCollapse.Phase.AOD) scale.snap(scaleTarget);
        // At the response the app's 缩放动画阻尼 sets, so the card keeps time with the clock,
        // the wallpaper and the morph instead of running on a clock of its own.
        else scale.step(scaleTarget, dt, Main.sClockResponse);
        boolean placing = followPlace(phase, dt, response, target);
        if (previous != null && (phase == ClockCollapse.Phase.AOD
                || SystemClock.uptimeMillis() - changedAt > TRACK_FADE_MS)) {
            previous.recycle();
            previous = null;
        }
        boolean videoMoving = followVideo(visible, phase, inAod, dt);
        boolean backdrop = Main.coverCardBackdropInAod() && current != null
                && current.aodBackdrop != null;
        wash.show(backdrop);
        int visibility = opacity > 0f || target > 0f ? VISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (visibility == VISIBLE) invalidate();
        boolean settling = blurring || videoMoving || Math.abs(target - opacity) > 0.001f
                || (phase != ClockCollapse.Phase.AOD && !scale.atRest(scaleTarget))
                || previous != null || placing || (rise < 1f && opacity > 0f)
                || (aodSince != 0L && nowNs - aodSince < AOD_SETTLE_NS)
                // Still waking from the big clock: keep looking until the clock has landed.
                || (afterBigClock && phase != ClockCollapse.Phase.AOD);
        if (settling) {
            Gen.frame(this);
        } else {
            stop();
        }
    } finally { android.os.Trace.endSection(); } }

    @Override protected void onDraw(Canvas canvas) {
        // Last line of defence if SystemUI replaced the parent's observer during a transition.
        if (!layerVisible()) {
            lastLockEligible = false;
            hideOutsideKeyguard("card draw");
            return;
        }
        if (current == null) return;
        // A doze frame may draw before the queued animation frame clears a stale lock-screen
        // opacity. The lyric page must never show the square underneath it in the full AOD.
        if (opacity <= 0f || CoverMorphLayer.cardSuppressed()
                || (Main.coverCardInAod() && LockLyrics.wantsAttached())) return;
        float density = getResources().getDisplayMetrics().density;
        if (Float.isNaN(drawX)) return;
        CoverMorphMotion.Box actual = CoverMorphMotion.cardBox(drawX, drawY,
                drawSide, scale.value * (RISE_FROM + (1f - RISE_FROM) * rise), shownAspect());
        square.set(actual.x, actual.y, actual.x + actual.w, actual.y + actual.h);
        float radius = style.radius(Math.min(actual.w, actual.h));
        // The shadow is keyed on the corner at the RESTING side, not the moving one - see
        // CoverCardStyle.cornerShare, and the note on shadowTile below.
        drawShadow(canvas, square, style.cornerShare(drawSide), opacity, paint);
        // Each picture is its own rounded rect, not drawn inside a clip to one: an antialiased
        // path clip has the GPU build a coverage mask for the card every frame it draws, and
        // this one draws every frame of the morph and of a motion cover. A rounded rect is
        // drawn with its edge worked out in the shader.
        artRadius = radius;
        drawArt(canvas, previous, 1f);
        drawArt(canvas, current, fadeFraction());
        Bitmap frame = CardVideo.frame();
        // The last frame stays for the fade out: an unloaded video has no frame of its own any
        // more, and the still under it would otherwise cut in.
        if (frame != null) lastVideo = frame;
        else if (videoAlpha <= 0f) lastVideo = null;
        if (videoAlpha > 0f && lastVideo != null) drawBitmap(canvas, lastVideo, videoAlpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0x40FFFFFF);
        paint.setAlpha(Math.round(opacity * 64f));
        canvas.drawRoundRect(square, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * A soft drop shadow under the square, shared with CoverMorphLayer so the hand-over does not
     * change it. Blurred and pulled in from the edges: an unblurred copy offset downwards reads
     * as a dark slab along the bottom edge rather than as a shadow.
     *
     * Drawn from one small pre-blurred tile, scaled to the square. It was a BlurMaskFilter on the
     * live rect, and a rect that changes size every frame - the morph, the play/pause spring - is
     * a fresh GPU blur every frame: the frames at the start of a tap toggle measured 15-18ms of
     * GPU against an 8.3ms budget, on top of the wallpaper's own crossfade. Scaled, the shadow
     * keeps its proportions to the square: at a 300dp square it is the 16dp blur, 6dp inset and
     * 8dp drop it was drawn with before.
     */
    static void drawShadow(Canvas canvas, RectF box, float corner, float strength, Paint paint) {
        if (strength <= 0f || box.width() <= 0f || box.height() <= 0f) return;
        Bitmap tile = shadowTile(corner);
        float shortSide = Math.min(box.width(), box.height());
        float k = shortSide / SHADOW_UNIT;
        float pad = SHADOW_PAD * k, drop = SHADOW_DROP * shortSide;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(Math.round(strength * 90f));
        // Nine slices rather than one stretch, so a card that is not square keeps the corners and
        // the fall-off of a square one: the quarters at the short side's scale, and the tile's
        // middle row and column - the shadow along a straight edge - stretched across the rest.
        int t = tile.getWidth(), half = t / 2;
        int[] sx = {0, half, t - half, t};
        float q = half * k;
        float l = box.left - pad, r = box.right + pad;
        float top = box.top - pad + drop, bottom = box.bottom + pad + drop;
        float[] dx = {l, Math.min(l + q, (l + r) * 0.5f), Math.max(r - q, (l + r) * 0.5f), r};
        float[] dy = {top, Math.min(top + q, (top + bottom) * 0.5f),
                Math.max(bottom - q, (top + bottom) * 0.5f), bottom};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (dx[j + 1] - dx[j] <= 0f || dy[i + 1] - dy[i] <= 0f) continue;
                sShadowSrc.set(sx[j], sx[i], sx[j + 1], sx[i + 1]);
                sShadowDst.set(dx[j], dy[i], dx[j + 1], dy[i + 1]);
                canvas.drawBitmap(tile, sShadowSrc, sShadowDst, paint);
            }
        }
    }

    /** The tile's square, in its own pixels; the ratios below are the old dp values over 300dp. */
    private static final float SHADOW_UNIT = 200f;
    private static final float SHADOW_BLUR = SHADOW_UNIT * 16f / 300f;
    private static final float SHADOW_INSET = SHADOW_UNIT * 6f / 300f;
    private static final float SHADOW_DROP = 8f / 300f;
    /** Room round the square for the blur to fall off in. */
    private static final float SHADOW_PAD = SHADOW_BLUR * 2f;
    private static Bitmap sShadowTile;
    /** The corner setting, in whole percent, sShadowTile was drawn with. */
    private static int sShadowCorner = -1;
    private static final RectF sShadowDst = new RectF();
    private static final Rect sShadowSrc = new Rect();

    /**
     * Drawn at the card's corner setting, not the live radius: the morph eases the radius every
     * frame, and a tile per frame would be the per-frame blur this replaced. The shadow only
     * shows as the morph lands on the card, where the two agree.
     */
    private static Bitmap shadowTile(float corner) {
        int key = Math.round(corner * 100f);
        Bitmap t = sShadowTile;
        if (t != null && !t.isRecycled() && key == sShadowCorner) return t;
        int side = Math.round(SHADOW_UNIT + 2f * SHADOW_PAD);
        t = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR, BlurMaskFilter.Blur.NORMAL));
        float lo = SHADOW_PAD + SHADOW_INSET, hi = SHADOW_PAD + SHADOW_UNIT - SHADOW_INSET;
        float r = (hi - lo) * 0.5f * key / 100f;
        new Canvas(t).drawRoundRect(lo, lo, hi, hi, r, r, p);
        sShadowTile = t;
        sShadowCorner = key;
        return t;
    }

    /**
     * The full-screen AOD's faint colour wash, as a view of its own beside the square.
     *
     * The doze scales keyguard_root_view to 0.95 (`op cardstate` lists the chain). Drawn inside
     * it, the wash came out 5% short and framed by the dark wallpaper, and neither drawing past
     * the card layer's bounds nor scaling this view back out by the inverse of the zoom got past
     * that: the second one was measured landing on the full screen at (0,0) and still cut to
     * 95%. So it lives beside keyguard_root_view, just below it (see attachNow), and fit() is
     * left to undo whatever zoom is still above it - normally none. The zoom itself is right and
     * stays: the square rides it with the rest of the lock screen.
     */
    private static final class Wash extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] loc = new int[2];
        private final RectF bounds = new RectF();

        Wash(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        /**
         * In over the doze's own dimming and out over the wallpaper's brightening. Put up and
         * taken down in one frame it flashed: brighter for a moment going into the AOD, while the
         * wallpaper had not dimmed yet, and darker for ~100ms coming out, while it was still dim.
         */
        private static final long IN_MS = 500L, OUT_MS = 250L;
        private boolean want;
        private float from;
        private long changedAt;

        /** 0..1, how much of the wash is up right now. */
        private float level() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt)
                    / (float) (want ? IN_MS : OUT_MS));
            float e = t * t * (3f - 2f * t);
            return from + ((want ? 1f : 0f) - from) * e;
        }

        void show(boolean on) {
            if (on != want) {
                from = getVisibility() == VISIBLE ? level() : 0f;
                want = on;
                changedAt = SystemClock.uptimeMillis();
            }
            if (on && getVisibility() != VISIBLE) setVisibility(VISIBLE);
            if (getVisibility() == VISIBLE) {
                if (on) fit();
                invalidate();
            }
        }

        /** Unlocked, or anywhere else the wash has no business: gone at once. */
        void hideNow() {
            want = false;
            from = 0f;
            if (getVisibility() != GONE) setVisibility(GONE);
        }

        /** Scales this view so that its bounds land on the whole display. */
        void fit() {
            if (!(getParent() instanceof View) || getWidth() <= 0 || getHeight() <= 0) return;
            View parent = (View) getParent();
            float sx = 1f, sy = 1f;
            for (Object p = parent; p instanceof View; p = ((View) p).getParent()) {
                sx *= ((View) p).getScaleX();
                sy *= ((View) p).getScaleY();
            }
            int sw = Main.screenWidth(), sh = Main.screenHeight();
            if (sw <= 0 || sh <= 0 || !(sx > 0f) || !(sy > 0f)) return;
            parent.getLocationOnScreen(loc);
            fitAxis(true, loc[0], sx, sw / (getWidth() * sx));
            fitAxis(false, loc[1], sy, sh / (getHeight() * sy));
        }

        /**
         * On screen, x in this view lands at origin + s * (pivot + (x - pivot) * k). Solving for
         * x = 0 landing on 0 gives the pivot; k is what makes the far edge land on the far edge.
         */
        private void fitAxis(boolean x, float origin, float s, float k) {
            float pivot = 0f;
            if (Math.abs(1f - k) > 1e-4f) pivot = -origin / (s * (1f - k));
            else k = 1f;
            if (x) {
                if (getScaleX() != k) setScaleX(k);
                if (getPivotX() != pivot) setPivotX(pivot);
            } else {
                if (getScaleY() != k) setScaleY(k);
                if (getPivotY() != pivot) setPivotY(pivot);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            CoverCardLayer v = sView;
            Prepared p = v == null ? null : v.current;
            float level = level();
            boolean moving = SystemClock.uptimeMillis() - changedAt < (want ? IN_MS : OUT_MS);
            if (!want && !moving) {
                // Faded all the way out: off the draw list until it is wanted again.
                post(new Runnable() {
                    @Override public void run() { if (!want) setVisibility(GONE); }
                });
                return;
            }
            if (moving) postInvalidateOnAnimation();
            if (p == null || p.aodBackdrop == null || p.aodBackdrop.isRecycled()) return;
            paint.setAlpha(Math.round(64f * level));
            bounds.set(0f, 0f, getWidth(), getHeight());
            canvas.drawBitmap(p.aodBackdrop, null, bounds, paint);
        }
    }

    /**
     * The same crossfade the wallpaper runs under it on a track change - WallpaperProbe's
     * sTrackFadeMs and its cubic ease-out - so the square and its blurred backdrop turn over
     * together instead of the art lagging the background by 400ms.
     */
    private static final long TRACK_FADE_MS = 180L;

    private float fadeFraction() {
        if (previous == null) return 1f;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / (float) TRACK_FADE_MS);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float aspectOf(Prepared p) {
        return p == null ? 1f : CoverCardStyle.aspect(p.art.getWidth(), p.art.getHeight());
    }

    /**
     * The card's shape right now: the current art's, eased from the previous one's over the same
     * crossfade the art itself takes, so a song followed by a video's cover changes shape as it
     * turns over rather than jumping.
     */
    private float shownAspect() {
        float to = aspectOf(current);
        if (previous == null) return to;
        float from = aspectOf(previous);
        return from + (to - from) * fadeFraction();
    }

    private final RectF artSrc = new RectF();

    private void drawArt(Canvas canvas, Prepared p, float fraction) {
        if (p == null || fraction <= 0f) return;
        drawBitmap(canvas, p.art, fraction);
    }

    private void drawBitmap(Canvas canvas, Bitmap art, float fraction) {
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * opacity * fraction));
        // Centre-cropped to the card's shape, which is only ever off this art's own through a
        // crossfade between two shapes - drawn into it as it is, one of the two would stretch.
        int w = art.getWidth(), h = art.getHeight();
        float box = square.width() / Math.max(1f, square.height());
        int cw = Math.min(w, Math.round(h * box));
        int ch = Math.min(h, Math.round(w / box));
        artSrc.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        artMatrix.setRectToRect(artSrc, square, android.graphics.Matrix.ScaleToFit.FILL);
        android.graphics.BitmapShader shader = shaderFor(art);
        shader.setLocalMatrix(artMatrix);
        paint.setShader(shader);
        canvas.drawRoundRect(square, artRadius, artRadius, paint);
        paint.setShader(null);
    }

    /** The corner the pictures are drawn with this frame; see onDraw. */
    private float artRadius;
    private final android.graphics.Matrix artMatrix = new android.graphics.Matrix();
    /**
     * Shaders for the last two stills - the one fading in and the one under it - so a still
     * drawn every frame does not make a new one every frame. A video frame is a new Bitmap each
     * time and gets a new shader each time anyway.
     */
    private Bitmap shaderArtA, shaderArtB;
    private android.graphics.BitmapShader shaderA, shaderB;

    private android.graphics.BitmapShader shaderFor(Bitmap art) {
        if (art == shaderArtA && shaderA != null) return shaderA;
        if (art == shaderArtB && shaderB != null) return shaderB;
        android.graphics.BitmapShader s = new android.graphics.BitmapShader(art,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        if (art == lastVideo) return s;
        shaderArtB = shaderArtA;
        shaderB = shaderA;
        shaderArtA = art;
        shaderA = s;
        return s;
    }

    // ---------------------------------------------------------------- the motion cover

    /** 0..1, how far the video is drawn over the still. See CardVideo. */
    private float videoAlpha;
    /** What the video last drew, kept for its fade out. A wrapped HardwareBuffer: no copy. */
    private Bitmap lastVideo;
    private static final float VIDEO_FADE_S = 0.25f;

    /**
     * The video only plays on the settled, lit square: the morph, the big clock's fall and wake,
     * the doze and a track's crossfade all show the still, which every one of them was tuned
     * around. It fades in over the still once it has a frame and fades out the same way; the
     * morph is the exception and cuts it, because the morph flies the still and the square has
     * to be the still the moment the morph hands it over.
     *
     * @return whether the fade is still moving
     */
    private boolean followVideo(boolean visible, ClockCollapse.Phase phase, boolean inAod,
                                float dt) {
        boolean suppressed = CoverMorphLayer.cardSuppressed();
        boolean settled = CardVideo.loaded() && visible && phase == ClockCollapse.Phase.ON
                && !afterBigClock && !inAod && !suppressed && opacity > 0f && current != null;
        CardVideo.want(settled);
        if (suppressed) videoAlpha = 0f;
        float want = settled && previous == null && CardVideo.frame() != null ? 1f : 0f;
        if (videoAlpha == want) return false;
        float step = dt <= 0f ? 1f / 60f / VIDEO_FADE_S : dt / VIDEO_FADE_S;
        videoAlpha = want > videoAlpha ? Math.min(want, videoAlpha + step)
                : Math.max(want, videoAlpha - step);
        return videoAlpha != want;
    }

    /** Main thread: CardVideo has a new frame. */
    static void videoFrame() {
        CoverCardLayer v = sView;
        if (v == null || v.getVisibility() != VISIBLE) return;
        v.invalidate();
        // The first frame is what lets the fade start; nothing else would ask for its frames.
        if (v.videoAlpha < 1f) v.start();
    }

}
