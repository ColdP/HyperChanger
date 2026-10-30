// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerViewKt;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

/** Temporary, non-interactive artwork shared between the OEM thumbnail and the cover. */
final class CoverMorphLayer extends View implements Choreographer.FrameCallback {
    /** Volatile: the hot-reload check reads active() from the framework's thread. */
    private static volatile CoverMorphLayer sView;

    private final CoverMorphMotion motion = new CoverMorphMotion();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF drawn = new RectF();
    /** Per-frame scratch, kept rather than allocated on every draw. */
    private final int[] tmpLoc = new int[2];
    private final RectF srcRect = new RectF();
    private final android.graphics.Matrix artMatrix = new android.graphics.Matrix();
    /** The shader drawing `art`, made once per flight rather than once per frame. */
    private android.graphics.BitmapShader artShader;
    private Bitmap shaderArt;
    private Bitmap art;
    private boolean cardMode;
    private CoverMorphMotion.Box thumb, cover;
    private long lastFrame, startedAt;
    private boolean awaitArtworkPush;
    private float fullAlpha;
    /** Volatile: written on the main thread, read by the hot-reload check off it. */
    private volatile boolean running;
    /** The far end is the mini player's artwork slot, not the OEM card's thumbnail. */
    private boolean mini;

    /**
     * One per window, kept attached and INVISIBLE between morphs. Added at the start of every
     * morph and removed at its end, it asked for a layout of the whole shade window twice per
     * morph; VISIBLE and INVISIBLE only redraw. (Not the 25-35ms first frame of a tap toggle:
     * a two-finger switch morphs the same way and never showed it.)
     */
    private CoverMorphLayer(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
        // Above the mini player, which a morph lifts over the media card: the artwork flying
        // out of it has to come out on top.
        setTranslationZ(MiniPlayerViewKt.MORPH_Z * 2f);
    }

    private void reset(Bitmap art, boolean cardMode, CoverMorphMotion.Box thumb,
                       CoverMorphMotion.Box cover, boolean toCover) {
        this.art = art;
        this.cardMode = cardMode;
        this.thumb = thumb;
        this.cover = cover;
        awaitArtworkPush = toCover && !Main.coverModeOn();
        fullAlpha = toCover ? 1f : 0f;
        motion.value = toCover ? 0f : 1f;
        motion.velocity = 0f;
        motion.aim(toCover);
        revealAt = 0L;
        lastFrame = 0L;
    }

    /** Called before the state switch so the source is still at its visible location. */
    static boolean begin(boolean toCover) {
        if (Looper.myLooper() != Looper.getMainLooper() || !Main.coverMorphEligible()) return false;
        // The square card only. The full-screen cover keeps its own transition - the clock
        // squeeze and the wallpaper crossfade - untouched.
        if (!Main.coverMorphCardMode()) {
            cancel();
            return false;
        }
        CoverMorphLayer old = sView;
        if (old != null && old.running) {
            if (!old.mini && old.cardMode == Main.coverMorphCardMode()) {
                old.motion.aim(toCover);
                old.revealAt = 0L;
                old.startedAt = SystemClock.uptimeMillis();
                old.invalidate();
                return true;
            }
            old.finish();
        }
        ViewGroup root = Main.coverMorphRoot();
        Bitmap art = Main.coverMorphSource();
        CoverMorphMotion.Box thumb = Main.coverMorphThumbnail();
        CoverMorphMotion.Box cover = art == null ? null : Main.coverMorphTarget(art);
        if (root == null || art == null || art.isRecycled() || thumb == null || cover == null) {
            return false;
        }
        run(root, art, thumb, cover, toCover, false);
        return true;
    }

    /**
     * The artwork between the mini player and the cover card. The pill itself turns into the
     * media card in MiniCardMorph; only the artwork leaves it, from - or back to - the pill's
     * own artwork slot, which stays where the pill rests while the container moves away.
     */
    static boolean beginMiniScene(boolean toCover) {
        if (Looper.myLooper() != Looper.getMainLooper() || !Main.coverMorphEligible()) return false;
        if (!Main.coverMorphCardMode()) {
            cancel();
            return false;
        }
        CoverMorphLayer old = sView;
        if (old != null && old.running) {
            if (old.mini) {
                old.motion.aim(toCover);
                old.startedAt = SystemClock.uptimeMillis();
                old.invalidate();
                return true;
            }
            old.finish();
        }
        ViewGroup root = Main.coverMorphRoot();
        Bitmap art = Main.coverMorphSource();
        CoverMorphMotion.Box slot = MiniPlayerRuntime.artworkRestBox();
        CoverMorphMotion.Box cover = art == null ? null : Main.coverMorphTarget(art);
        if (root == null || art == null || art.isRecycled() || slot == null || cover == null) {
            return false;
        }
        run(root, art, slot, cover, toCover, true);
        return true;
    }

    private static void run(ViewGroup root, Bitmap art, CoverMorphMotion.Box thumb,
                            CoverMorphMotion.Box cover, boolean toCover, boolean mini) {
        CoverMorphLayer v = sView;
        if (v == null || v.getParent() != root) {
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
            v = new CoverMorphLayer(root.getContext());
            root.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Gen.ownView(root, v);
            sView = v;
        }
        // bringToFront lays the parent out again too: only when something has come above it.
        if (root.getChildAt(root.getChildCount() - 1) != v) v.bringToFront();
        v.reset(art, Main.coverMorphCardMode(), thumb, cover, toCover);
        v.mini = mini;
        if (mini) MiniPlayerRuntime.setArtBridged(true);
        v.setVisibility(VISIBLE);
        v.running = true;
        v.startedAt = SystemClock.uptimeMillis();
        v.setRequestedFrameRate(120f);
        Main.refreshMediaCardForMorph();
        CoverCardLayer.refresh();
        Gen.frame(v);
    }

    static boolean active() {
        // One read: the hot-reload check calls this off the main thread, which can end the
        // morph (sView = null) between a null check and a second read.
        CoverMorphLayer v = sView;
        return v != null && v.running;
    }

    /** How quickly an end catches up with where its live box has moved to, in seconds. */
    private static final double ENDPOINT_TAU = 0.06;

    private static CoverMorphMotion.Box chase(CoverMorphMotion.Box from,
                                              CoverMorphMotion.Box to, float k) {
        if (from == null) return to;
        return new CoverMorphMotion.Box(from.x + (to.x - from.x) * k,
                from.y + (to.y - from.y) * k, from.w + (to.w - from.w) * k,
                from.h + (to.h - from.h) * k);
    }

    private static boolean near(CoverMorphMotion.Box a, CoverMorphMotion.Box b) {
        return Math.abs(a.x - b.x) < 0.5f && Math.abs(a.y - b.y) < 0.5f
                && Math.abs(a.w - b.w) < 0.5f && Math.abs(a.h - b.h) < 0.5f;
    }

    /** Leaves nothing held and the kept view idle; for a keyguard that is going away. */
    private void release() {
        art = null;
        artShader = null;
        shaderArt = null;
        thumb = cover = null;
    }

    /** How long the OEM thumbnail takes to fade back in under the copy that has landed on it. */
    private static final long THUMB_FADE_MS = 120L;
    /** When the copy came to rest on the thumbnail on the way back; 0 until it has. */
    private long revealAt;

    /**
     * The OEM thumbnail's alpha while a morph owns its pixels. Zero on the way out. On the way
     * back it fades in, so its own shadow comes in with it rather than all at once when this
     * layer goes - but only once the copy has settled on it. Earlier, it showed beside a copy
     * still larger and elsewhere, or as a ring round one dipping below its size in the bounce.
     */
    static float thumbAlpha() {
        CoverMorphLayer v = sView;
        if (v == null || !v.running) return 1f;
        // Towards the cover the card is up beside the flight, thumbnail and all: kept out of the
        // way as on the card's own route. Back to the pill the thumbnail was never the source.
        if (v.mini) return v.motion.target == 1f ? 0f : 1f;
        if (v.motion.target != 0f || v.revealAt == 0L) return 0f;
        float r = (SystemClock.uptimeMillis() - v.revealAt) / (float) THUMB_FADE_MS;
        return Math.max(0f, Math.min(1f, r));
    }
    static boolean cardSuppressed() { return active() && sView.cardMode; }

    static void cancel() {
        CoverMorphLayer v = sView;
        if (v == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) v.finish();
        else Main.main().post(new Runnable() { @Override public void run() { v.finish(); } });
    }

    /**
     * Retirement: the copy off the tree and the field cleared. cancel() keeps the view for the
     * next morph - the sweep in a dry run takes it away instead, and a static still pointing at
     * a detached view is what the next morph would find.
     */
    static void retire() {
        CoverMorphLayer v = sView;
        sView = null;
        if (v == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            v.finish();
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        } else {
            Main.main().post(new Runnable() {
                @Override
                public void run() {
                    v.finish();
                    if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                }
            });
        }
    }

    @Override public void doFrame(long nowNs) { android.os.Trace.beginSection("MC coverMorphFrame"); try {
        if (!running) return;
        if (!isAttachedToWindow() || !Main.coverMorphStillEligible()
                || getParent() != Main.coverMorphRoot()) {
            finish();
            return;
        }
        // Stepped by the real gap, up to 50ms. Held to 20ms to hide a slow frame, the copy
        // visibly stopped for it instead, which read worse than the catch-up.
        float dt = lastFrame == 0L ? 1f / 120f
                : Math.min(0.05f, Math.max(0f, (nowNs - lastFrame) / 1e9f));
        lastFrame = nowNs;
        motion.step(dt, Main.sClockResponse);
        CoverMorphMotion.Box liveThumb = mini
                ? MiniPlayerRuntime.artworkRestBox() : Main.coverMorphThumbnail();
        // Both ends chase their live boxes rather than taking them: the target is placed between
        // a clock that is collapsing and a media card being re-laid out, and one of them stepped
        // mid-flight - filmed as the copy sliding sideways at a constant size for a frame.
        float follow = (float) (1.0 - Math.exp(-dt / ENDPOINT_TAU));
        if (liveThumb != null) thumb = chase(thumb, liveThumb, follow);
        CoverMorphMotion.Box liveCover = Main.coverMorphTarget(art);
        if (liveCover != null) cover = chase(cover, liveCover, follow);
        boolean endsSettled = (liveThumb == null || near(thumb, liveThumb))
                && (liveCover == null || near(cover, liveCover));
        boolean artworkReady = !awaitArtworkPush || Main.coverMorphHandoffReady(cardMode)
                || SystemClock.uptimeMillis() - startedAt > 2200L;
        boolean cardReady = !cardMode || motion.target != 1f
                || CoverCardLayer.morphHandoffReady()
                || SystemClock.uptimeMillis() - startedAt > 2200L;
        float desiredAlpha = motion.target == 1f
                ? (motion.atRest() && artworkReady ? 0f : 1f)
                : Math.min(1f, Math.max(0f, (1f - motion.value) / 0.18f));
        fullAlpha += (desiredAlpha - fullAlpha) * Math.min(1f, dt * 20f);
        invalidate();
        if (motion.target == 0f) {
            if (revealAt == 0L && motion.atRest()) revealAt = SystemClock.uptimeMillis();
            // The fade-in is written by the card's own pass; it needs a frame to run in.
            if (revealAt != 0L) Main.refreshMediaCardForMorph();
        }
        ClockCollapse.Phase phase = ClockCollapse.phase();
        boolean clockFlying = motion.target == 1f
                ? phase == ClockCollapse.Phase.ENTER
                : phase == ClockCollapse.Phase.EXIT;
        // Back at the thumbnail, the layer stays until the thumbnail has fully faded in.
        boolean handoffDone = motion.target == 0f ? mini || thumbAlpha() >= 1f
                : artworkReady && cardReady && (cardMode || fullAlpha < 0.01f);
        if (motion.atRest() && handoffDone && endsSettled && (!clockFlying
                || SystemClock.uptimeMillis() - startedAt > 2200L)) {
            finish();
        } else {
            Gen.frame(this);
        }
    } finally { android.os.Trace.endSection(); } }

    @Override protected void onDraw(Canvas canvas) {
        if (!running || art == null || art.isRecycled()) return;
        if (Main.perfOff("flight")) return;
        float density = getResources().getDisplayMetrics().density;
        CoverMorphMotion.Box box = CoverMorphMotion.frame(thumb, cover, motion.value, density);
        int[] root = tmpLoc;
        getLocationOnScreen(root);
        drawn.set(box.x - root[0], box.y - root[1],
                box.x + box.w - root[0], box.y + box.h - root[1]);
        float p = Math.max(0f, Math.min(1f, motion.value));
        float startRadius = mini ? MiniPlayerRuntime.artworkRadius()
                : Math.min(14f * density, Math.min(thumb.w, thumb.h) * 0.20f);
        CoverCardStyle style = Main.sCoverCardStyle;
        float endRadius = cardMode ? style.radius(Math.min(cover.w, cover.h)) : 0f;
        float radius = startRadius + (endRadius - startRadius) * p;
        float decoration = cardMode ? CoverMorphMotion.cardDecoration(motion.value) : 0f;
        // `cover` is the landing box, so the corner share it asks for is the one the card will
        // have when it gets there - which is where this shadow is read. See cornerShare.
        CoverCardLayer.drawShadow(canvas, drawn,
                style.cornerShare(Math.min(cover.w, cover.h)), decoration, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        // The full wallpaper already contains the final sharp band. Hand its pixels over near
        // the end while this moving copy still covers the rest of the journey.
        float alpha = cardMode ? 1f : fullAlpha;
        paint.setAlpha(Math.round(255f * alpha));
        if (paint.getAlpha() == 0) return;
        int side = Math.min(art.getWidth(), art.getHeight());
        int cropW = Math.round(side + (art.getWidth() - side) * p);
        int cropH = Math.round(side + (art.getHeight() - side) * p);
        if (cardMode) {
            // The card lands in the artwork's own shape (CoverCardStyle.aspect), so the art is
            // cut to the box it is in on every frame: square at the thumbnail, its own at the end.
            float shape = drawn.width() / Math.max(1f, drawn.height());
            cropW = Math.min(art.getWidth(), Math.round(art.getHeight() * shape));
            cropH = Math.min(art.getHeight(), Math.round(art.getWidth() / shape));
        }
        RectF source = srcRect;
        source.set((art.getWidth() - cropW) / 2, (art.getHeight() - cropH) / 2,
                (art.getWidth() + cropW) / 2, (art.getHeight() + cropH) / 2);
        // A rounded rect with the art as its shader, not the art inside a clip to one: the
        // antialiased path clip had the GPU build a coverage mask every frame of the flight.
        if (art != shaderArt || artShader == null) {
            artShader = new android.graphics.BitmapShader(art,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shaderArt = art;
        }
        artMatrix.setRectToRect(source, drawn, android.graphics.Matrix.ScaleToFit.FILL);
        artShader.setLocalMatrix(artMatrix);
        paint.setShader(artShader);
        canvas.drawRoundRect(drawn, radius, radius, paint);
        paint.setShader(null);
        if (decoration > 0f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(0x40FFFFFF);
            paint.setAlpha(Math.round(64f * decoration));
            canvas.drawRoundRect(drawn, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void finish() {
        if (!running) return;
        running = false;
        Gen.unframe(this);
        setRequestedFrameRate(0f);
        // Kept for the next morph; see the constructor.
        if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
        release();
        if (mini) {
            mini = false;
            MiniPlayerRuntime.setArtBridged(false);
        }
        Main.refreshMediaCardForMorph();
        CoverCardLayer.refresh();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (running) finish();
        // A rebuilt keyguard gets a view of its own.
        if (sView == this) sView = null;
    }
}
