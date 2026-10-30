// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Blurs an immersive page above and below its sharp band, so the clock and the cards over it
 * read against a soft picture instead of a map's lines and labels.
 *
 * The page is under the shade window, and the blur is the platform's cross-window one:
 * ViewRootImpl.createBackgroundBlurDrawable() gives a drawable that, drawn in a view, has
 * SurfaceFlinger blur whatever is behind the window inside its bounds - the page, here, as the
 * clock's glass and the cards' blur sample the wallpaper there. Checked on this phone
 * (2026-09-29): ro.surface_flinger.supports_background_blur=1, WindowManager mBlurEnabled=true.
 *
 * A blur region has hard edges, so the edge that meets the sharp band is cut into STEPS strips
 * whose alpha steps down to nothing. SurfaceFlinger blurs once per radius and draws each region
 * from that one blurred picture, so the strips cost little more than one region. They all have
 * the one radius and the strength goes by alpha alone: three radius tiers put a hard seam where
 * each tier began, the picture on either side of it being a different blur (screenshot
 * 2026-09-30: seams at 165px and 2330px of 2608, both tier changes).
 *
 * Over the blur, a black scrim above and below the band, as ColorOS lays its top_mask_view and
 * bottom_mask_view (plugin a6.e, seedling_immersive_overlay: 180dp, #80000000 to clear) over the
 * map. 高德's map is light by day and the clock's glass shows the map it is over: without the
 * scrim the clock and the date were the colour of the map around them (2026-09-30).
 *
 * Under the PIN pad, one more region over the whole screen (setPad): the pad's own blur is a
 * view in the same window and does not reach a page drawn under it (issue #40).
 *
 * All sizes are fractions of the view's height, or dp: nothing here depends on the display.
 */
public final class EdgeBlurView extends View {

    /**
     * Strips in each soft edge; the edge is the whole band now, so more of them. The steepest
     * step between two strips is 1.5 / STEPS of alpha (the smoothstep's slope).
     */
    private static final int STEPS = 24;
    /**
     * How far the soft edge reaches into the blurred side, as a fraction of the height. At least
     * the whole band: the blur is heaviest at the screen's edge and has gone by the sharp band,
     * with no solid stretch. A solid band from the edge to the clock read as too much blur, and
     * too heavy (user, 2026-09-29).
     */
    private static final float EDGE = 1f;
    /**
     * The blur's radius, the heaviest of the old tiers. It is still heavier at the edge (user,
     * 2026-09-29: "靠近屏幕边缘的模糊改重一点"): the strips there are at full alpha, the ones by the
     * band at almost none.
     */
    private static final float RADIUS_DP = 32f;

    /** The scrim's alpha at the screen's edge: ColorOS's #80. */
    private static final float SCRIM_EDGE = 0.5f;
    /**
     * Stops in each scrim's gradient, sampling scrim(): two stops would be a straight ramp, which
     * leaves the clock - low in the top band - a third of the edge's darkness.
     */
    private static final int SCRIM_STOPS = 9;

    private final Paint mTopScrim = new Paint();
    private final Paint mBottomScrim = new Paint();

    /** Top blurred band: solid, then its soft edge; the bottom band mirrors it. */
    private final List<Drawable> mStrips = new ArrayList<>();
    private final List<Float> mAlphas = new ArrayList<>();
    private float mTop = Float.NaN, mBottom = Float.NaN;
    private float mFade = 1f;
    private boolean mUnavailable;
    private Method mSetRadius;
    /** The whole screen's blur under the PIN pad. See setPad. */
    private Drawable mPad;
    private float mPadLevel;
    /** The emptied pad region has been drawn once, which is what tells SurfaceFlinger. */
    private boolean mPadCleared = true;
    /**
     * The pad region's radius, the one CoverCardLayer and LyricView blur themselves by under the
     * pad. Its strength goes by alpha, as the strips' does: one radius, blurred once.
     */
    private static final float PAD_RADIUS_DP = 24f;

    public EdgeBlurView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
    }

    /** The sharp band, as fractions of the height; null takes the blur away. */
    public void setBand(float[] band) {
        float top = band == null ? Float.NaN : band[0];
        float bottom = band == null ? Float.NaN : band[1];
        if (same(top, mTop) && same(bottom, mBottom)) return;
        mTop = top;
        mBottom = bottom;
        if (band == null) {
            // Emptied and drawn once more, rather than hidden: hidden, the strips' regions stayed
            // registered with SurfaceFlinger - 28 of them, radius up to 96px at full alpha across
            // the top and bottom, still there with the countdown's page up (2026-09-30).
            for (Drawable d : mStrips) {
                d.setBounds(0, 0, 0, 0);
                d.setAlpha(0);
            }
            mCleared = false;
        } else {
            layoutStrips();
        }
        invalidate();
    }

    /** Whether it is blurring anything: a page's band is set. */
    public boolean hasBand() {
        return !Float.isNaN(mTop);
    }

    /** The emptied strips have been drawn once, which is what tells SurfaceFlinger. */
    private boolean mCleared = true;

    /** The page's own opacity: the blur fades with it, or the wallpaper's edges would blur first. */
    public void setFade(float fade) {
        if (fade == mFade) return;
        mFade = fade;
        applyAlphas();
        invalidate();
    }

    public boolean unavailable() {
        return mUnavailable;
    }

    /**
     * How far the PIN pad's blur has come, 0..1: the page under the window blurs with it. Emptied
     * and drawn once at 0, like setBand(null), so no region is left registered.
     */
    public void setPad(float level) {
        if (level == mPadLevel) return;
        mPadLevel = level;
        Drawable d = mPad;
        if (d != null) {
            if (level <= 0f) {
                d.setBounds(0, 0, 0, 0);
                d.setAlpha(0);
                mPadCleared = false;
            } else {
                d.setBounds(0, 0, getWidth(), getHeight());
                d.setAlpha(Math.round(255f * level));
            }
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        build();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStrips.clear();
        mAlphas.clear();
        mPad = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutStrips();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable pad = mPad;
        if (pad != null && (mPadLevel > 0f || !mPadCleared)) {
            if (mPadLevel > 0f) pad.setBounds(0, 0, getWidth(), getHeight());
            pad.draw(canvas);
            mPadCleared = mPadLevel <= 0f;
        }
        if (Float.isNaN(mTop)) {
            if (!mCleared) {
                for (Drawable d : mStrips) d.draw(canvas);
                mCleared = true;
            }
            return;
        }
        if (mFade <= 0f) return;
        for (Drawable d : mStrips) d.draw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (mTopScrim.getShader() != null) canvas.drawRect(0, 0, w, mTop * h, mTopScrim);
        if (mBottomScrim.getShader() != null) canvas.drawRect(0, mBottom * h, w, h, mBottomScrim);
    }

    /** STEPS strips for each edge plus a solid band on each side: 2 * (STEPS + 1). */
    private void build() {
        mStrips.clear();
        mAlphas.clear();
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(this);
            Method create = vri.getClass().getMethod("createBackgroundBlurDrawable");
            for (int i = 0; i < 2 * (STEPS + 1); i++) {
                Drawable d = (Drawable) create.invoke(vri);
                if (mSetRadius == null) mSetRadius = d.getClass().getMethod("setBlurRadius", int.class);
                mStrips.add(d);
                mAlphas.add(1f);
            }
            Drawable pad = (Drawable) create.invoke(vri);
            mSetRadius.invoke(pad, Math.round(PAD_RADIUS_DP * getResources().getDisplayMetrics().density));
            pad.setBounds(0, 0, 0, 0);
            pad.setAlpha(0);
            mPad = pad;
            float level = mPadLevel;
            mPadLevel = 0f;
            setPad(level);
            mUnavailable = false;
        } catch (Throwable t) {
            mStrips.clear();
            mAlphas.clear();
            mPad = null;
            mUnavailable = true;
            Xp.w("MCImmersive: edge blur unavailable: " + t);
        }
        layoutStrips();
    }

    /**
     * Top: [0, top - edge] solid, then STEPS strips to top, fading out. Bottom: STEPS strips from
     * bottom, fading in, then [bottom + edge, height] solid. The fall is a smoothstep, so the
     * blur leaves the band without a visible first step.
     */
    private void layoutStrips() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || Float.isNaN(mTop)) return;
        layoutScrims(h);
        if (mStrips.isEmpty()) {
            applyAlphas();
            return;
        }
        float edge = EDGE * h;
        float top = mTop * h;
        float bottom = mBottom * h;
        float topSolid = Math.max(0f, top - edge);
        float bottomSolid = Math.min(h, bottom + edge);
        int k = 0;
        k = place(k, 0f, topSolid, 1f, w);
        for (int i = 0; i < STEPS; i++) {
            float a = topSolid + (top - topSolid) * i / STEPS;
            float b = topSolid + (top - topSolid) * (i + 1) / STEPS;
            // i = 0 is the strip at the screen's edge.
            k = place(k, a, b, 1f - smooth((i + 0.5f) / STEPS), w);
        }
        for (int i = 0; i < STEPS; i++) {
            float a = bottom + (bottomSolid - bottom) * i / STEPS;
            float b = bottom + (bottomSolid - bottom) * (i + 1) / STEPS;
            // Here the strip at the screen's edge is the last one.
            k = place(k, a, b, smooth((i + 0.5f) / STEPS), w);
        }
        place(k, bottomSolid, h, 1f, w);
        applyAlphas();
    }

    private int place(int k, float top, float bottom, float alpha, int w) {
        Drawable d = mStrips.get(k);
        d.setBounds(0, Math.round(top), w, Math.round(bottom));
        mAlphas.set(k, alpha);
        try {
            mSetRadius.invoke(d, Math.round(RADIUS_DP * getResources().getDisplayMetrics().density));
        } catch (Throwable t) {
            Xp.log("MCImmersive: edge blur radius not set: " + t);
        }
        return k + 1;
    }

    /**
     * Top: from the screen's edge down to the band; bottom: from the band down to the edge. Each
     * is dark at the edge and clear at the band.
     */
    private void layoutScrims(int h) {
        int[] colours = new int[SCRIM_STOPS];
        int[] reversed = new int[SCRIM_STOPS];
        float[] at = new float[SCRIM_STOPS];
        for (int i = 0; i < SCRIM_STOPS; i++) {
            at[i] = (float) i / (SCRIM_STOPS - 1);
            colours[i] = Color.argb(Math.round(255f * SCRIM_EDGE * scrim(at[i])), 0, 0, 0);
        }
        for (int i = 0; i < SCRIM_STOPS; i++) reversed[i] = colours[SCRIM_STOPS - 1 - i];
        mTopScrim.setShader(new LinearGradient(0, 0, 0, mTop * h, colours, at,
                Shader.TileMode.CLAMP));
        mBottomScrim.setShader(new LinearGradient(0, mBottom * h, 0, h, reversed, at,
                Shader.TileMode.CLAMP));
    }

    /**
     * The scrim's strength at t, 0 at the screen's edge and 1 at the band: held most of the way
     * and let go near the band, so the clock, some two thirds of the way down to the band, keeps
     * most of it (t = 0.66: 0.71 of the edge's; ColorOS's straight 180dp ramp would leave it
     * almost none on this layout).
     */
    private static float scrim(float t) {
        return 1f - t * t * t;
    }

    private void applyAlphas() {
        for (int i = 0; i < mStrips.size(); i++) {
            mStrips.get(i).setAlpha(Math.round(255f * mAlphas.get(i) * mFade));
        }
        int a = Math.round(255f * mFade);
        mTopScrim.setAlpha(a);
        mBottomScrim.setAlpha(a);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static boolean same(float a, float b) {
        return a == b || Float.isNaN(a) && Float.isNaN(b);
    }
}
