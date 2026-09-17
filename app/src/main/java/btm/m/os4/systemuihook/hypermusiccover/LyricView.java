package btm.m.os4.systemuihook.hypermusiccover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.List;

/**
 * The lock screen's lyrics: one view, drawn by hand, between the collapsed clock and the card.
 *
 * Every animated value belongs to a LINE, never to a slot on screen. The first version kept nine
 * TextViews and handed each its neighbour's text on a line change, so brightness, size and
 * weight stayed with the slot while the words moved - the line arriving at the focus snapped to
 * full brightness and the one leaving snapped dark, and only the far rows animated at all.
 * Here a line carries its own scroll position, emphasis, blur and word fill from the moment it
 * is parsed until the song changes.
 *
 * - Position: every line springs toward the scroll target, but only once its own start delay has
 *   passed - the lines below the focus set off one after another, so the stack flows up rather
 *   than moving as a slab. The spring is also a little softer further from the focus.
 * - Emphasis: one 0..1 per line. Brightness and scale both derive from it. The text is laid out
 *   once - the weight never changes, because a weight change re-wraps the line.
 * - Depth: a line that is not being sung is blurred by its distance from the one that is, drawn
 *   as a blurred bitmap made once per line and distance. A line is sharp while it moves and while it has any
 *   emphasis, and goes out of focus once it has settled - which keeps the motion clean and means
 *   no line ever switches between the blurred and the word-by-word path mid-blur.
 * - Words: a line with word timing is drawn a syllable at a time. Each syllable lifts a little
 *   as it is sung; a long one (a held note) also glows and swells while it lasts. The sung part
 *   is at the line's brightness and the rest at UNSUNG of it, through a soft-edged mask.
 * - Edges: a line fades as it approaches the top or bottom of the band.
 *
 * Frames are only asked for while something moves. Otherwise the view waits for its own
 * pre-draw, which fires whenever anything else in the keyguard window animates, and for
 * LockLyrics' tick, which wakes it at the next line start.
 */
final class LyricView extends View {

    private static final String TAG = "[MCLyric] ";

    // ---- the look. Sizes are sp/dp; nothing here is a pixel.
    private static final float TEXT_SP = 25f;
    private static final float TRANS_SP = 15f;
    /** The background vocal under a line: smaller, dimmer, and lifting less than the lead. */
    private static final float BG_SP = 17f;
    private static final int BG_WEIGHT = 500;
    private static final float BG_GAP_DP = 3f;
    private static final float BG_ALPHA = 0.72f;
    private static final float BG_LIFT = 0.6f;
    private static final int WEIGHT = 600;
    private static final int TRANS_WEIGHT = 500;
    private static final float SIDE_DP = 30f;
    /** Between one line's last row (or its translation) and the next line. */
    private static final float GAP_DP = 22f;
    private static final float TRANS_GAP_DP = 5f;
    /**
     * A line that is not being sung - and the unsung part of the one that is, which Apple draws at
     * the same level (measured: the arriving line's text is 146 on a 34 ground before its first
     * word and 146 after it is sharp, the same as a settled inactive line).
     */
    private static final float INACTIVE = 0.40f;
    /** The unsung words of the singing line, as a share of its emphasis above INACTIVE. None. */
    private static final float UNSUNG = 0f;
    /** Lines already sung, above the focus, are further back than the ones to come. */
    private static final float ABOVE_ALPHA = 0.6f;
    /** How saturated the dim text's tint may be; Apple's reads as a pale version of the cover. */
    private static final float TINT_SAT = 0.28f;
    private static final float TRANS_ALPHA = 0.62f;
    private static final float INACTIVE_SCALE = 0.97f;
    /** Half the width of the soft edge between sung and unsung, in text sizes. */
    private static final float FEATHER_EM = 0.45f;
    /**
     * Short on purpose: the top and bottom lines settle inside this zone, and at 44dp, on top of
     * their blur and the inactive level, they all but vanished.
     */
    private static final float EDGE_FADE_DP = 26f;
    /** Where the singing line's top sits, as a fraction down the band. */
    private static final float ANCHOR = 0.23f;
    private static final float CLOCK_GAP_DP = 22f;
    private static final float CARD_GAP_DP = 16f;
    /** A band shorter than this many rows of text is not worth showing lyrics in. */
    private static final float MIN_BAND_ROWS = 2.4f;

    // ---- depth
    /**
     * Blur radius per row of distance beyond the first, up to BLUR_MAX_ROWS. The lines right
     * next to the singing one stay sharp: blurring the next line as well left it unreadable
     * behind the edge fade (recording 02:23).
     */
    private static final float BLUR_NEXT_DP = 0.9f;
    private static final float BLUR_DP_PER_ROW = 1.6f;
    private static final int BLUR_MAX_ROWS = 4;
    /** A line going out of focus does it fast; one coming in clears a little slower. */
    private static final float TAU_BLUR_IN = 0.05f;
    private static final float TAU_BLUR_OUT = 0.07f;
    /**
     * How much brighter than SDR white the singing words are drawn when HDR is on - Apple
     * Music's highlight, asked to be obvious. The window's headroom is set above this.
     */
    private static final float HDR_GAIN = 3f;
    /** The window is switched to HDR this long before a glow starts, so it is ready for it. */
    private static final int HDR_ARM_MS = 250;
    private static final android.graphics.ColorSpace EXTENDED =
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB);
    private static final float BLUR_ALPHA_PER_DP = 0.12f;

    // ---- words
    /** How far a syllable rises as it is sung. */
    private static final float LIFT_DP = 1.8f;
    /** A syllable shorter than this still takes this long to rise, so quick words do not jitter. */
    private static final int LIFT_MIN_MS = 320;
    /** A syllable at least this long is a held note, and glows. */
    private static final int GLOW_MIN_MS = 1000;
    private static final float GLOW_DP = 9f;
    private static final float GLOW_ALPHA = 0.55f;
    private static final float GLOW_SWELL = 0.045f;
    /** How long a held note's glow takes to go after the note ends. */
    private static final int GLOW_TAIL_MS = 380;

    // ---- the motion: AMLL's (amll-dev/applemusic-like-lyrics, lyric-player/base), which is
    // what Apple's is taken to be. Not a bouncing spring - an over-damped one (ratio ~1.1) - and
    // what makes it read as water is the ripple: every line sets off a little after the one
    // above it, top of the view downwards.
    /** Normal play: stiffness between these, faster the closer the two lines are in time. */
    private static final float K_MIN = 170f, K_MAX = 220f;
    private static final int IV_MIN = 100, IV_MAX = 800;
    /** Damping is sqrt(stiffness) times this, mass 1. */
    private static final float DAMPING_MULT = 2.2f;
    /** A seek or an interlude: slower and softer. */
    private static final float K_SLOW = 90f, C_SLOW = 15f;
    /** The ripple: 50ms more per line from the top of the view, shrinking past the focus. */
    private static final float RIPPLE_MS = 50f;
    private static final float RIPPLE_DECAY = 1f / 1.05f;
    /** The stack moves to a line this long before its first word (measured 1.07s). */
    private static final long LEAD_MS = 1000L;
    /** Emphasis arrives quickly and leaves in one frame: the sung line drops out at once. */
    private static final float TAU_EMPH_IN = 0.08f;
    private static final float TAU_EMPH_OUT = 0.016f;
    /** AMLL's scale spring (stiffness 100, damping 25) settles in about this time constant. */
    private static final float TAU_SCALE = 0.15f;
    private static final float TAU_SHOW = 0.18f;
    /** Out faster than in: the clock starts growing into the lyrics' space at once. */
    private static final float TAU_HIDE = 0.06f;
    /** A jump of more lines than this is a seek, and is cut rather than scrolled. */
    private static final int SEEK_LINES = 8;
    /** A gap between lines at least this long gets the interlude dots. */
    private static final int LULL_MS = 4000;

    // ---- interlude dots, in text sizes and milliseconds (see drawDots)
    private static final float DOT_EM = 0.25f;
    private static final float DOT_GAP_EM = 0.43f;
    private static final float DOT_CENTER_EM = 0.9f;
    private static final float DOTS_SLOT_EM = 1.8f;
    private static final float DOT_DIM = 0.18f;
    private static final float DOT_RAMP = 0.7f;
    private static final float DOT_BREATH = 0.135f;
    private static final long DOT_BREATH_MS = 4200L;
    private static final long DOT_BREATH_PHASE_MS = 1470L;
    private static final long DOT_APPEAR_MS = 1200L;
    private static final long DOT_POP_MS = 90L;
    private static final long DOT_EXIT_MS = 180L;
    private static final long DOT_EXIT_LEAD_MS = 120L;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bgPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tintSrc = -1;
    private float tintR = 1f, tintG = 1f, tintB = 1f;
    private final Matrix gradMatrix = new Matrix();
    private final LinearGradient[] grads = new LinearGradient[3];
    private final long[] gradHi = {-1L, -1L, -1L}, gradLo = {-1L, -1L, -1L};
    /** The row being drawn: where its gradient crosses (NaN = no gradient) and its two levels. */
    private float rowAt = Float.NaN, rowFeather, rowSungA, rowUnsungA;

    private final float density;
    private final float textPx;
    private final float liftPx, glowPx;
    /** Room around a line for its glow and lift, and around a blurred node for the blur. */
    private final int wordPad, blurPad;

    // ---- content, rebuilt when the lines or the width change
    private int version = -1;
    private List<LyricLine> lines = Collections.emptyList();
    private int layoutWidth = -1;
    private StaticLayout[] main = new StaticLayout[0];
    private StaticLayout[] trans = new StaticLayout[0];
    private StaticLayout[] bgLay = new StaticLayout[0];
    private float[][] charXBg = new float[0][];
    /** Top of each line in content coordinates, and its full height with translation. */
    private float[] base = new float[0];
    private float[] height = new float[0];
    private float[][] charX = new float[0][];
    /**
     * Each line's blurred picture, at the radius its distance asks for. A bitmap, not a
     * RenderNode with a blur effect: that was the first version, and on this phone the node
     * drew nothing at all - every line vanished the moment it settled (recording 02:52).
     */
    private Bitmap[][] blurBmp = new Bitmap[0][];
    /** The distance each picture was blurred for; 0 for an empty slot. */
    private int[][] blurRows = new int[0][];
    /** Pictures asked of the blur thread and not back yet, as line * 8 + distance. */
    private final java.util.HashSet<Integer> blurPending = new java.util.HashSet<>();
    /** Bumped by every rebuild, so a picture made for the previous layout is thrown away. */
    private int buildGen;
    private static final int BLUR_SLOTS = 3;
    private final android.graphics.RectF bmpDst = new android.graphics.RectF();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ---- per-line animated state
    private float[] scroll = new float[0];
    private float[] vel = new float[0];
    /** What the line is springing to, what it will spring to, and when it switches. */
    private float[] aim = new float[0];
    private float[] nextAim = new float[0];
    private long[] aimAt = new long[0];
    private float[] emph = new float[0];
    /** A line-timed line's brightness: on while it is sung. */
    private float[] lit = new float[0];
    private float[] scale = new float[0];
    private float[] blur = new float[0];
    /** Where the interlude slot before each line sits; NaN where the gap is short. */
    private float[] dotsTop = new float[0];

    /** The line the stack is on, the line whose interlude is showing (-1), and both as one key. */
    private int focus = -1;
    private int dotsFor = -1;
    /** The scroll spring for the current move, set per move like AMLL's policy. */
    private float springK = K_SLOW, springC = C_SLOW;
    private boolean dotsWasShowing;
    private int focusKey = Integer.MIN_VALUE;
    private int ms;
    private float show;
    private float bandTop, bandBottom;
    private boolean bandOk;

    private long lastStep;

    private boolean looping;
    private final int[] loc = new int[2];

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            looping = false;
            if (step()) invalidate();
            if (needsFrames()) {
                looping = true;
                postOnAnimation(this);
            }
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDraw =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (step()) invalidate();
                    if (!looping && needsFrames()) kick();
                    return true;
                }
            };

    LyricView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics());
        liftPx = LIFT_DP * density;
        glowPx = GLOW_DP * density;
        wordPad = (int) Math.ceil(glowPx * 1.6f + liftPx + textPx * GLOW_SWELL);
        blurPad = (int) Math.ceil((BLUR_NEXT_DP + BLUR_DP_PER_ROW * BLUR_MAX_ROWS) * density * 2f);
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(textPx);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, WEIGHT, false));
        transPaint.setColor(0xFFFFFFFF);
        transPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TRANS_SP,
                getResources().getDisplayMetrics()));
        transPaint.setTypeface(Typeface.create(Typeface.DEFAULT, TRANS_WEIGHT, false));
        bgPaint.setColor(0xFFFFFFFF);
        bgPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, BG_SP,
                getResources().getDisplayMetrics()));
        bgPaint.setTypeface(Typeface.create(Typeface.DEFAULT, BG_WEIGHT, false));
        // Touches go through to the lock screen: the double tap and the swipes are the OEM's.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Something outside changed - a line start, the song, the setting. Wakes the loop. */
    void kick() {
        if (looping) return;
        looping = true;
        postOnAnimation(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
        lastStep = 0L;
        LockLyrics.startTick();
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        removeCallbacks(frame);
        looping = false;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ state

    /**
     * One step of everything. Returns whether anything visible changed. Safe to call twice in a
     * frame - the animation callback and the pre-draw both do - because the second call has no
     * time to integrate.
     */
    private boolean step() {
        long now = SystemClock.uptimeMillis();
        float dt = lastStep == 0L ? 0f : Math.min(0.05f, (now - lastStep) / 1000f);
        if (lastStep != 0L && now - lastStep < 2L) return false;
        lastStep = now;

        boolean changed = false;
        int why = 0;
        // Not before the first layout: a width of zero would wrap every line a character a row.
        if (getWidth() > 0 && (LockLyrics.version() != version || getWidth() != layoutWidth)) {
            rebuild();
            changed = true;
            why |= 1;
        }
        // Leaving, the band is frozen where it was: following the clock as it grows would drag
        // the fading lines up through it.
        if (LockLyrics.wantsShown() && updateBand()) {
            changed = true;
            why |= 2;
        }

        float showTo = showTarget();
        float s = approach(show, showTo, dt, showTo < show ? TAU_HIDE : TAU_SHOW);
        if (Math.abs(s - showTo) < 0.004f) s = showTo;
        if (s != show) {
            show = s;
            changed = true;
            why |= 4;
        }
        if (show == 0f && showTo == 0f && !LockLyrics.wantsAttached()) {
            // Faded out with nothing to come back for: leave the keyguard's tree.
            post(new Runnable() {
                @Override
                public void run() {
                    LockLyrics.detach(LyricView.this);
                }
            });
            return changed;
        }
        if (lines.isEmpty()) return changed;

        // The position moves on every step while playing, and that alone is NOT a change: it
        // used to be, so every pre-draw invalidated, which drew the next frame, whose pre-draw
        // invalidated again - the whole keyguard window redrawn at the refresh rate for as long
        // as music played. SystemUI's main thread went to GC for 27s in two minutes and was
        // killed for an ANR (2026-09-16 02:51). Only what moves redraws.
        ms = LockLyrics.positionMs();
        int n = lines.size();
        int idx = indexAt(ms);

        // Which line the stack is on, which can be ahead of the one being sung: Apple scrolls to
        // the next line about a second before its first word (measured 1.07s), once the line
        // before has finished. A long gap scrolls to the interlude dots instead, as soon as the
        // line before it ends.
        int sf, dots = -1;
        if (idx < 0) {
            sf = 0;
            if (!Float.isNaN(dotsTop[0]) && ms < switchAt(0)) dots = 0;
        } else {
            sf = idx;
            int next = idx + 1;
            if (next < n) {
                if (ms >= switchAt(next)) {
                    sf = next;
                } else if (!Float.isNaN(dotsTop[next]) && ms >= lines.get(idx).end) {
                    sf = next;
                    dots = next;
                }
            }
        }
        int key = dots >= 0 ? -(dots + 1) : sf;
        if (key != focusKey) {
            boolean seek = focus < 0 || Math.abs(sf - focus) > SEEK_LINES || sf < focus - 2;
            focus = sf;
            dotsFor = dots;
            focusKey = key;
            float to = dots >= 0 ? dotsTop[dots] : base[sf];
            if (seek) {
                snap(to);
            } else {
                // The spring for this move: slow into and out of an interlude, otherwise stiffer
                // the shorter the gap from the line before.
                boolean slow = dots >= 0 || dotsWasShowing || sf == 0;
                dotsWasShowing = dots >= 0;
                if (slow) {
                    springK = K_SLOW;
                    springC = C_SLOW;
                } else {
                    int iv = lines.get(sf).start - lines.get(sf - 1).start;
                    iv = Math.max(IV_MIN, Math.min(IV_MAX, iv));
                    float ratio = 1f - (iv - IV_MIN) / (float) (IV_MAX - IV_MIN);
                    ratio = (float) Math.pow(ratio, 0.2);
                    springK = K_MIN + ratio * (K_MAX - K_MIN);
                    springC = (float) Math.sqrt(springK) * DAMPING_MULT;
                }
                // The ripple, counted from the first line whose target is on screen.
                float bandH = bandBottom - bandTop;
                float anchor = bandTop + ANCHOR * bandH;
                float delay = 0f, step = RIPPLE_MS;
                for (int i = 0; i < n; i++) {
                    aimAt[i] = now + Math.round(delay);
                    nextAim[i] = to;
                    float y = anchor + base[i] - to;
                    if (y + height[i] >= bandTop) {
                        delay += step;
                        if (i >= sf) step *= RIPPLE_DECAY;
                    }
                }
            }
            prewarmBlur();
            if (LockLyrics.verbose) {
                Xp.log(TAG + (dots >= 0 ? "interlude before " : "line ") + sf + "/" + n
                        + " at " + ms + "ms");
            }
            changed = true;
            why |= 8;
        }

        float target = dotsFor >= 0 ? dotsTop[dotsFor] : base[focus];
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = 0; i < n; i++) {
            if (i < lo || i > hi) {
                if (scroll[i] != target || emph[i] != 0f || blur[i] != 0f) {
                    scroll[i] = aim[i] = nextAim[i] = target;
                    vel[i] = 0f;
                    emph[i] = 0f;
                    lit[i] = 0f;
                    scale[i] = INACTIVE_SCALE;
                    blur[i] = 0f;
                }
                dropBlurred(i);
                continue;
            }
            // Position: the line's own ripple delay, then the move's spring.
            boolean started = now >= aimAt[i];
            if (aim[i] != nextAim[i] && started) aim[i] = nextAim[i];
            float x = scroll[i] - aim[i];
            if (dt > 0f && (Math.abs(x) > 0.3f || Math.abs(vel[i]) > 2f)) {
                float left = dt;
                while (left > 0f) {
                    float h = Math.min(left, 1f / 240f);
                    vel[i] += (-springK * x - springC * vel[i]) * h;
                    x += vel[i] * h;
                    left -= h;
                }
                scroll[i] = aim[i] + x;
                changed = true;
                why |= 16;
            } else if (x != 0f) {
                scroll[i] = aim[i];
                vel[i] = 0f;
                changed = true;
                why |= 32;
            }

            LyricLine l = lines.get(i);
            boolean focused = dotsFor < 0 && i == focus;
            // Emphasis: on the scroll focus, and on a duet's overlapping answer while it is sung.
            // Off in one frame - the line that has been sung drops to the inactive level at once.
            boolean on = focused || (dotsFor < 0 && i < focus && i >= focus - 2
                    && ms >= l.start && ms < l.end);
            float eTo = on ? 1f : 0f;
            float e = approach(emph[i], eTo, dt, eTo > emph[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(e - eTo) < 0.003f) e = eTo;
            if (e != emph[i]) {
                emph[i] = e;
                changed = true;
                why |= 64;
            }
            // A line-timed line lights when it is sung, not when the stack arrives a second early.
            float lTo = on && ms >= l.start ? 1f : 0f;
            float lv = approach(lit[i], lTo, dt, lTo > lit[i] ? TAU_EMPH_IN : TAU_EMPH_OUT);
            if (Math.abs(lv - lTo) < 0.003f) lv = lTo;
            if (lv != lit[i]) {
                lit[i] = lv;
                changed = true;
                why |= 64;
            }
            // Size: the focus at full size, the rest a little smaller, travelling with the scroll.
            float scTo = focused ? 1f : INACTIVE_SCALE;
            float sc = started ? approach(scale[i], scTo, dt, TAU_SCALE) : scale[i];
            if (Math.abs(sc - scTo) < 0.0005f) sc = scTo;
            if (sc != scale[i]) {
                scale[i] = sc;
                changed = true;
                why |= 64;
            }
            // Depth: the line leaving goes out of focus fast, the one arriving clears a little
            // slower (about 100ms and 200ms in the frames).
            float bt = blurTarget(i);
            float b = approach(blur[i], bt, dt, bt > blur[i] ? TAU_BLUR_IN : TAU_BLUR_OUT);
            if (Math.abs(b - bt) < 0.05f) b = bt;
            if (b != blur[i]) {
                blur[i] = b;
                changed = true;
                why |= 128;
            }
        }
        if (wordsLive() || dotsLive()) why |= 256;
        LockLyrics.setGlowing(glowSoon());
        noteWhy(why);
        return changed || (why & 256) != 0;
    }

    /**
     * When the stack moves to line i: a second before its first word, but not before the line
     * ahead of it has finished, and never after its own start.
     */
    private long switchAt(int i) {
        LyricLine l = lines.get(i);
        long early = (long) l.start - LEAD_MS;
        if (i == 0) return early;
        long prevEnd = lines.get(i - 1).end;
        return Math.min(l.start, Math.max(early, prevEnd));
    }

    private float blurFor(int rows) {
        // The neighbours only just soft, so the next line still reads; then clearly out of focus.
        if (rows <= 0) return 0f;
        return (BLUR_NEXT_DP + BLUR_DP_PER_ROW * (Math.min(BLUR_MAX_ROWS, rows) - 1)) * density;
    }

    /** How many rows from the focus a line counts as - lines above count one further. */
    private int rowsFromFocus(int i) {
        if (dotsFor >= 0) return i >= dotsFor ? i - dotsFor + 1 : dotsFor - i + 1;
        if (i == focus) return 0;
        return i > focus ? i - focus : focus - i + 1;
    }

    /** The blur a line is heading for: none on the focus, then by distance. */
    private float blurTarget(int i) {
        return blurFor(rowsFromFocus(i));
    }

    private boolean moving(int i) {
        return aim[i] != nextAim[i] || scroll[i] != aim[i] || vel[i] != 0f;
    }

    /**
     * How visible the lyrics should be: the card's own progress into the cover look (so they
     * arrive and leave with the card's thumbnail), and the clock container's alpha (so they go
     * wherever the OEM fades the clock - the bouncer, the shade over the lock screen).
     */
    private float showTarget() {
        if (!LockLyrics.wantsShown() || !bandOk || lines.isEmpty()) return 0f;
        float v = clamp01(Main.cardProgress());
        View c = Main.sContainer;
        if (c != null) v *= clamp01(c.getAlpha());
        return v;
    }

    private boolean needsFrames() {
        if (!isAttachedToWindow()) return false;
        if (show != showTarget()) return true;
        ClockCollapse.Phase p = ClockCollapse.phase();
        if (p == ClockCollapse.Phase.ENTER || p == ClockCollapse.Phase.EXIT) return true;
        if (lines.isEmpty() || focus < 0 || show == 0f) return false;
        int n = lines.size();
        int lo = Math.max(0, focus - 6), hi = Math.min(n - 1, focus + 12);
        for (int i = lo; i <= hi; i++) {
            if (moving(i)) return true;
            float e = emph[i];
            if (e != 0f && e != 1f) return true;
            float lv = lit[i];
            if (lv != 0f && lv != 1f) return true;
            if (scale[i] != 1f && scale[i] != INACTIVE_SCALE) return true;
            if (now() < aimAt[i]) return true;
            if (blur[i] != blurTarget(i)) return true;
        }
        // The words move every frame while they are being sung - the fill, the lift, a held
        // note's glow - and the interlude dots breathe for as long as they are up.
        return wordsLive() || dotsLive();
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    /** The interlude dots are up, or about to be. */
    private boolean dotsLive() {
        return dotsFor >= 0 && show > 0f;
    }

    /** A held note is glowing now, or is about to - the window's HDR mode follows this. */
    private boolean glowSoon() {
        if (!LockLyrics.sHdr || focus < 0 || show == 0f || !LockLyrics.playing()) return false;
        for (int i = Math.max(0, focus - 1); i <= focus; i++) {
            LyricLine l = lines.get(i);
            if (!l.hasWords() || emph[i] <= 0f) continue;
            for (int k = 0; k < l.sylStart.length; k++) {
                int s = l.sylStart[k], end = l.sylEnd[k];
                if (end - s >= GLOW_MIN_MS && ms >= s - HDR_ARM_MS && ms < end + GLOW_TAIL_MS) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A singing line's words are moving: the fill, the lift, a held note's glow. */
    private boolean wordsLive() {
        if (!LockLyrics.playing() || focus < 0 || show == 0f) return false;
        for (int i = Math.max(0, focus - 1); i <= focus; i++) {
            LyricLine l = lines.get(i);
            if (l.hasWords() && emph[i] > 0f && ms >= l.start
                    && ms < l.end + Math.max(LIFT_MIN_MS, GLOW_TAIL_MS)) {
                return true;
            }
        }
        return false;
    }

    private static float approach(float v, float to, float dt, float tau) {
        if (dt <= 0f) return v;
        return v + (to - v) * (1f - (float) Math.exp(-dt / tau));
    }

    private int indexAt(int t) {
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).start <= t) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private void snap(float target) {
        for (int i = 0; i < scroll.length; i++) {
            scroll[i] = aim[i] = nextAim[i] = target;
            vel[i] = 0f;
            emph[i] = 0f;
            lit[i] = 0f;
            scale[i] = INACTIVE_SCALE;
            blur[i] = 0f;
        }
    }

    /** New lines, or a new width: lay every line out once. */
    private void rebuild() {
        version = LockLyrics.version();
        lines = LockLyrics.lines();
        layoutWidth = getWidth();
        buildGen++;
        blurPending.clear();
        int n = lines.size();
        int w = Math.max(1, layoutWidth - Math.round(2f * SIDE_DP * density));
        main = new StaticLayout[n];
        trans = new StaticLayout[n];
        bgLay = new StaticLayout[n];
        charXBg = new float[n][];
        base = new float[n];
        height = new float[n];
        charX = new float[n][];
        blurBmp = new Bitmap[n][BLUR_SLOTS];
        blurRows = new int[n][BLUR_SLOTS];
        scroll = new float[n];
        vel = new float[n];
        aim = new float[n];
        nextAim = new float[n];
        aimAt = new long[n];
        emph = new float[n];
        lit = new float[n];
        scale = new float[n];
        java.util.Arrays.fill(scale, INACTIVE_SCALE);
        blur = new float[n];
        dotsTop = new float[n];
        float y = 0f;
        float gap = GAP_DP * density;
        for (int i = 0; i < n; i++) {
            LyricLine l = lines.get(i);
            // A long gap before this line holds the interlude dots, in a slot of their own.
            long gapStart = i == 0 ? 0L : lines.get(i - 1).end;
            if (l.start - gapStart >= LULL_MS) {
                dotsTop[i] = y;
                y += DOTS_SLOT_EM * textPx + gap;
            } else {
                dotsTop[i] = Float.NaN;
            }
            Layout.Alignment align = l.opposite
                    ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            main[i] = StaticLayout.Builder.obtain(l.text, 0, l.text.length(), paint, w)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            float h = main[i].getHeight();
            if (l.bg != null) {
                bgLay[i] = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(), bgPaint, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                h += BG_GAP_DP * density + bgLay[i].getHeight();
            }
            if (l.translation != null) {
                trans[i] = StaticLayout.Builder.obtain(l.translation, 0, l.translation.length(),
                                transPaint, w)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                h += TRANS_GAP_DP * density + trans[i].getHeight();
            }
            base[i] = y;
            height[i] = h;
            y += h + gap;
        }
        focus = -1;
        dotsFor = -1;
        focusKey = Integer.MIN_VALUE;
        if (LockLyrics.verbose || n > 0) {
            Xp.log(TAG + "view laid out " + n + " lines at width " + w);
        }
    }

    /** Where the band between the clock and the card is, in this view's coordinates. */
    private boolean updateBand() {
        // Two getLocationOnScreen walks a frame are not free, and this runs from the keyguard's
        // pre-draw: nothing to show, nothing to measure.
        if (lines.isEmpty() && show == 0f) return false;
        float clock = ClockCollapse.inkBottomOnScreen();
        View card = LockLyrics.card();
        boolean ok = false;
        float top = bandTop, bottom = bandBottom;
        if (!Float.isNaN(clock) && card != null && card.isShown() && isAttachedToWindow()) {
            getLocationOnScreen(loc);
            float me = loc[1];
            card.getLocationOnScreen(loc);
            top = clock + CLOCK_GAP_DP * density - me;
            bottom = loc[1] - CARD_GAP_DP * density - me;
            ok = bottom - top >= MIN_BAND_ROWS * textPx;
        }
        boolean changed = ok != bandOk
                || Math.abs(top - bandTop) >= 0.5f || Math.abs(bottom - bandBottom) >= 0.5f;
        bandOk = ok;
        if (ok) {
            bandTop = top;
            bandBottom = bottom;
        }
        return changed;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        drawCount++;
        if (show <= 0.003f || lines.isEmpty() || focus < 0 || main.length != lines.size()) return;
        float bandH = bandBottom - bandTop;
        if (bandH <= 0f) return;
        float anchor = bandTop + ANCHOR * bandH;
        float side = SIDE_DP * density;
        float fade = Math.min(EDGE_FADE_DP * density, bandH / 3f);
        int n = lines.size();
        updateTint();

        int save = canvas.save();
        canvas.clipRect(0f, bandTop, getWidth(), bandBottom);
        for (int i = Math.max(0, focus - 6); i < n; i++) {
            float y = anchor + base[i] - scroll[i];
            if (y > bandBottom) break;
            if (y + height[i] < bandTop) continue;
            // Faded by the row's own top against the top edge and its own bottom against the
            // bottom edge, so a line is already gone by the time it would be cut.
            float edge = Math.min(clamp01((y - bandTop) / fade),
                    clamp01((bandBottom - (y + height[i])) / fade));
            float a = show * edge;
            // Lines already sung, above the focus, sit further back than the ones to come.
            if (dotsFor >= 0 ? i < dotsFor : i < focus) a *= ABOVE_ALPHA;
            if (a <= 0.003f) continue;
            drawLine(canvas, i, side, y, a);
        }
        if (dotsFor >= 0) {
            drawDots(canvas, dotsFor, side, anchor + dotsTop[dotsFor] - scroll[dotsFor]);
        }
        canvas.restoreToCount(save);
    }

    /**
     * The interlude: three dots where the next line will be, lighting one after another across
     * the gap and breathing while they do, then dropping away just before the stack moves on.
     * Every number is off the frames of Apple's: a dot a quarter of the text size across, 0.43
     * of it apart; dim at 18%, each lit linearly over about 70% of its third; a 4.2s breath of
     * +-13.5%; a 90ms pop in; a 180ms shrink and fade out that ends 120ms before the scroll.
     */
    private void drawDots(Canvas c, int d, float x, float top) {
        long gapStart = d == 0 ? 0L : lines.get(d - 1).end;
        long sw = switchAt(d);
        long appear = gapStart + Math.min(DOT_APPEAR_MS, (long) ((sw - gapStart) * 0.15f));
        long exitEnd = sw - DOT_EXIT_LEAD_MS;
        long exitStart = exitEnd - DOT_EXIT_MS;
        if (ms < appear || ms >= exitEnd || exitStart <= appear) return;
        float pop = clamp01((ms - appear) / (float) DOT_POP_MS);
        pop = 1f - (1f - pop) * (1f - pop);
        float exit = clamp01((ms - exitStart) / (float) DOT_EXIT_MS);
        float u = 3f * (ms - appear) / (float) (exitStart - appear);
        float breath = 1f + DOT_BREATH * (float) Math.cos(
                2.0 * Math.PI * (ms - appear - DOT_BREATH_PHASE_MS) / DOT_BREATH_MS);
        float size = DOT_EM * textPx;
        float cy = top + DOT_CENTER_EM * textPx;
        for (int k = 0; k < 3; k++) {
            float litK = clamp01((u - k) / DOT_RAMP);
            float r = size / 2f * breath * (0.3f + 0.7f * pop) * (1f - 0.3f * exit);
            float alpha = show * pop * (1f - exit) * (DOT_DIM + (1f - DOT_DIM) * litK);
            if (alpha <= 0.003f) continue;
            dotPaint.setColor(ink(alpha, litK));
            c.drawCircle(x + size / 2f + k * DOT_GAP_EM * textPx, cy, r, dotPaint);
        }
    }

    private void drawLine(Canvas canvas, int i, float x, float y, float a) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float e = emph[i];
        float sc = scale[i];
        // Pivot on the line's own edge, so a size change does not shift it sideways.
        float pivotX = l.opposite ? lay.getWidth() : 0f;
        boolean words = l.hasWords() && e > 0f;
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(sc, sc, pivotX, 0f);
        if (words) {
            // From the first frame of emphasis, so the first syllable lifts from the start. While
            // the line's blur is still clearing, its blurred picture is crossfaded out underneath
            // - handing over to the word path only once the blur had gone made the first word
            // jump up already half lifted.
            float k = blur[i] <= 0f ? 0f : clamp01(blur[i] / Math.max(0.01f, blurFor(1)));
            drawWords(canvas, i, a * (1f - k), e);
            if (k > 0f) {
                float r = blurFor(1);
                for (int d = 1; d <= BLUR_MAX_ROWS && blurFor(d) < blur[i]; d++) r = blurFor(d + 1);
                drawBlurLevel(canvas, i, r, a * INACTIVE * k, r);
            }
        } else {
            // The radius sits between two of the fixed ones - sharp, or a distance's blur - and is
            // drawn as a crossfade of those two pictures, so each is blurred once, not per frame.
            float r = blur[i];
            float lo = 0f, hi = 0f;
            for (int d = 0; d <= BLUR_MAX_ROWS; d++) {
                float v = blurFor(d);
                if (v <= r + 0.01f) lo = v;
                if (v >= r - 0.01f) {
                    hi = v;
                    break;
                }
                hi = v;
            }
            if (hi < lo) hi = lo;
            float t = hi > lo ? clamp01((r - lo) / (hi - lo)) : 0f;
            // A word-timed line that is not the focus is all unsung colour; a line-timed one is
            // lit while it is sung.
            float w = l.hasWords() ? 0f : lit[i];
            float base = a * (INACTIVE + (1f - INACTIVE) * w);
            if (t < 1f) drawBlurLevel(canvas, i, lo, base * (1f - t), hi, w);
            if (t > 0f) drawBlurLevel(canvas, i, hi, base * t, lo, w);
        }
        canvas.restoreToCount(save);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep) {
        drawBlurLevel(canvas, i, radius, alpha, keep, 0f);
    }

    private void drawBlurLevel(Canvas canvas, int i, float radius, float alpha, float keep,
                               float whiteness) {
        if (alpha <= 0.002f) return;
        Bitmap b = radius <= 0f ? null : blurredFor(i, rowsFor(radius));
        if (b == null) {
            drawStatic(canvas, i, alpha, whiteness);
            return;
        }
        // A blur spreads a glyph's ink over more pixels, so the same alpha reads fainter the more it
        // is blurred. Compensated a little, so distance reads as depth rather than as fading out.
        float boost = 1f + BLUR_ALPHA_PER_DP * (radius / density);
        bmpPaint.setAlpha(Math.round(255f * Math.min(1f, alpha * boost)));
        int w = main[i].getWidth() + 2 * blurPad;
        int h = Math.round(height[i]) + 2 * blurPad;
        bmpDst.set(-blurPad, -blurPad, w - blurPad, h - blurPad);
        canvas.drawBitmap(b, null, bmpDst, bmpPaint);
    }

    // ------------------------------------------------------------------ colour

    /**
     * The cover's hue, lightened, that the dim text takes on - Apple's lyrics let the background
     * through the unlit words (on a plum cover they read pink-grey, not grey). Sung text stays
     * white. Refreshed per frame from the tint the clock already samples; cheap when unchanged.
     */
    private void updateTint() {
        int src = Main.coverTint();
        if (src == tintSrc) return;
        tintSrc = src;
        if (src == 0) {
            tintR = tintG = tintB = 1f;
        } else {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(src, hsv);
            hsv[1] = Math.min(TINT_SAT, hsv[1] * 1.5f);
            hsv[2] = 1f;
            int t = android.graphics.Color.HSVToColor(hsv);
            tintR = ((t >> 16) & 0xff) / 255f;
            tintG = ((t >> 8) & 0xff) / 255f;
            tintB = (t & 0xff) / 255f;
        }
        int ti = android.graphics.Color.rgb(Math.round(tintR * 255f), Math.round(tintG * 255f),
                Math.round(tintB * 255f));
        bmpPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(ti,
                android.graphics.PorterDuff.Mode.SRC_IN));
    }

    /** Text colour: the tint at whiteness 0, white at 1, with an alpha. */
    private long ink(float alpha, float whiteness) {
        float w = clamp01(whiteness);
        float r = tintR + (1f - tintR) * w, g = tintG + (1f - tintG) * w, b = tintB + (1f - tintB) * w;
        return android.graphics.Color.pack(r, g, b, clamp01(alpha), EXTENDED);
    }

    /** The distance a blur radius belongs to. */
    private int rowsFor(float radius) {
        for (int d = 1; d <= BLUR_MAX_ROWS; d++) {
            if (Math.abs(blurFor(d) - radius) < 0.01f) return d;
        }
        return BLUR_MAX_ROWS;
    }

    /**
     * The line's picture blurred for a distance, or the nearest one it has while that is still
     * being made. Never made here: blurring on the UI thread, several lines at every line change,
     * was the hitch the scroll showed (99th percentile 38ms, 2026-09-16).
     */
    private Bitmap blurredFor(int i, int rows) {
        Bitmap best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int k = 0; k < BLUR_SLOTS; k++) {
            Bitmap b = blurBmp[i][k];
            if (b == null) continue;
            int gap = Math.abs(blurRows[i][k] - rows);
            if (gap == 0) return b;
            if (gap < bestGap) {
                bestGap = gap;
                best = b;
            }
        }
        requestBlur(i, rows);
        return best;
    }

    /** The pictures the lines around the focus will want next: their distance now, and one less. */
    private void prewarmBlur() {
        int n = lines.size();
        for (int i = Math.max(0, focus - 4); i <= Math.min(n - 1, focus + 10); i++) {
            int d = rowsFromFocus(i);
            if (d >= 1) requestBlur(i, Math.min(d, BLUR_MAX_ROWS));
            if (d >= 2) requestBlur(i, Math.min(d - 1, BLUR_MAX_ROWS));
            requestBlur(i, Math.min(d + 1, BLUR_MAX_ROWS));
        }
    }

    private void requestBlur(final int i, final int rows) {
        if (rows < 1 || i < 0 || i >= blurBmp.length) return;
        for (int k = 0; k < BLUR_SLOTS; k++) {
            if (blurBmp[i][k] != null && blurRows[i][k] == rows) return;
        }
        if (!blurPending.add(i * 8 + rows)) return;
        // Everything the other thread needs, taken here: the paints are this view's and change on
        // every frame, so it gets copies, and it lays the text out again for itself.
        final int gen = buildGen;
        final LyricLine l = lines.get(i);
        final int width = main[i].getWidth();
        final int fullW = width + 2 * blurPad, fullH = Math.round(height[i]) + 2 * blurPad;
        final float radius = blurFor(rows);
        final TextPaint p = new TextPaint(paint);
        final TextPaint tp = new TextPaint(transPaint);
        final TextPaint bp = new TextPaint(bgPaint);
        final int pad = blurPad;
        final float transGap = TRANS_GAP_DP * density;
        final float bgGap = BG_GAP_DP * density;
        blurHandler().post(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = makeBlurred(l, width, fullW, fullH, pad, radius, p, tp, bp,
                        transGap, bgGap);
                post(new Runnable() {
                    @Override
                    public void run() {
                        blurPending.remove(i * 8 + rows);
                        if (b == null || gen != buildGen || i >= blurBmp.length) return;
                        storeBlurred(i, rows, b);
                        invalidate();
                    }
                });
            }
        });
    }

    /** Off the UI thread: the line at half resolution, blurred. The blur scales with the canvas. */
    private static Bitmap makeBlurred(LyricLine l, int width, int fullW, int fullH, int pad,
                                      float radius, TextPaint p, TextPaint tp, TextPaint bp,
                                      float transGap, float bgGap) {
        try {
            Bitmap b = Bitmap.createBitmap(Math.max(1, fullW / 2), Math.max(1, fullH / 2),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(0.5f, 0.5f);
            c.translate(pad, pad);
            BlurMaskFilter mf = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
            Layout.Alignment align = l.opposite
                    ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            p.setShader(null);
            p.clearShadowLayer();
            p.setAlpha(255);
            p.setMaskFilter(mf);
            StaticLayout lay = StaticLayout.Builder.obtain(l.text, 0, l.text.length(), p, width)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .build();
            lay.draw(c);
            float below = lay.getHeight();
            if (l.bg != null) {
                bp.setShader(null);
                bp.setColor(0xFFFFFFFF);
                bp.setAlpha(Math.round(255f * BG_ALPHA));
                bp.setMaskFilter(mf);
                StaticLayout bl = StaticLayout.Builder.obtain(l.bg.text, 0, l.bg.text.length(),
                                bp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .build();
                int save = c.save();
                c.translate(0f, below + bgGap);
                bl.draw(c);
                c.restoreToCount(save);
                below += bgGap + bl.getHeight();
            }
            if (l.translation != null) {
                tp.setAlpha(Math.round(255f * TRANS_ALPHA));
                tp.setMaskFilter(mf);
                StaticLayout t = StaticLayout.Builder.obtain(l.translation, 0,
                                l.translation.length(), tp, width)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();
                c.translate(0f, below + transGap);
                t.draw(c);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Keeps the picture, evicting the one this line is least likely to need: the farthest distance. */
    private void storeBlurred(int i, int rows, Bitmap b) {
        int d = Math.max(1, Math.min(BLUR_MAX_ROWS, rowsFromFocus(i)));
        int slot = -1, worst = -1;
        for (int k = 0; k < BLUR_SLOTS; k++) {
            if (blurBmp[i][k] == null) {
                slot = k;
                break;
            }
            int gap = Math.abs(blurRows[i][k] - d);
            if (gap > worst) {
                worst = gap;
                slot = k;
            }
        }
        // Not recycled: the render thread may still be drawing it from the last frame. The
        // collector frees it once nothing holds it.
        blurBmp[i][slot] = b;
        blurRows[i][slot] = rows;
    }

    private void dropBlurred(int i) {
        if (i >= blurBmp.length) return;
        for (int k = 0; k < BLUR_SLOTS; k++) {
            blurBmp[i][k] = null;
            blurRows[i][k] = 0;
        }
    }

    private static android.os.Handler sBlurHandler;

    private static synchronized android.os.Handler blurHandler() {
        if (sBlurHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("MCLyricBlur",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sBlurHandler = new android.os.Handler(t.getLooper());
        }
        return sBlurHandler;
    }

    /** A line at one brightness: the text at a, its translation below it. */
    private void drawStatic(Canvas c, int i, float a) {
        drawStatic(c, i, a, 0f);
    }

    /** A line at one brightness, white by whiteness and the cover's tint otherwise. */
    private void drawStatic(Canvas c, int i, float a, float whiteness) {
        StaticLayout lay = main[i];
        paint.setColor(ink(a, whiteness));
        lay.draw(c);
        paint.setColor(0xFFFFFFFF);
        StaticLayout b = bgLay[i];
        if (b != null) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            bgPaint.setColor(ink(a * BG_ALPHA, whiteness));
            b.draw(c);
            bgPaint.setColor(0xFFFFFFFF);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a);
    }

    /** How far down the translation starts: under the line and its background vocal. */
    private float transTop(int i) {
        float y = main[i].getHeight();
        if (bgLay[i] != null) y += BG_GAP_DP * density + bgLay[i].getHeight();
        return y + TRANS_GAP_DP * density;
    }

    /** White at a brightness in SDR units - 1 is ordinary white, more is HDR - and an alpha. */
    private static long white(float alpha, float gain) {
        return android.graphics.Color.pack(gain, gain, gain, clamp01(alpha), EXTENDED);
    }

    /**
     * A held note's brightness: HDR only while it glows, scaled with the glow. Nothing else is
     * drawn above white - the highlight is the end of a long note lighting up, not the line.
     */
    private static float glowGain(float glow) {
        return LockLyrics.sHdr ? 1f + (HDR_GAIN - 1f) * glow : 1f;
    }

    private void drawTranslation(Canvas c, int i, float a) {
        StaticLayout t = trans[i];
        if (t == null) return;
        int save = c.save();
        c.translate(0f, transTop(i));
        transPaint.setColor(ink(a * TRANS_ALPHA, 0f));
        t.draw(c);
        transPaint.setColor(0xFFFFFFFF);
        c.restoreToCount(save);
    }

    /**
     * A line with word timing, a syllable at a time, row by row: a row already sung at the sung
     * brightness, a row not reached at the unsung one, and the row being sung through a gradient
     * that crosses from one to the other at the character being sung.
     *
     * The gradient is the text paint's own shader. It used to be a DST_IN mask over an offscreen
     * layer of the whole line - a second full-size pass on the render thread every frame, which
     * was a quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWords(Canvas c, int i, float a, float e) {
        LyricLine l = lines.get(i);
        StaticLayout lay = main[i];
        float level = INACTIVE + (1f - INACTIVE) * e;
        drawWordRows(c, l, lay, paint, charXFor(i, lay, l, charX), e, a * level,
                a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG), 1f, true, 0);
        StaticLayout bl = bgLay[i];
        if (bl != null && l.bg != null) {
            int save = c.save();
            c.translate(0f, lay.getHeight() + BG_GAP_DP * density);
            drawWordRows(c, l.bg, bl, bgPaint, charXFor(i, bl, l.bg, charXBg), e,
                    a * level * BG_ALPHA,
                    a * (INACTIVE + (1f - INACTIVE) * e * UNSUNG) * BG_ALPHA,
                    BG_LIFT, false, 1);
            c.restoreToCount(save);
        }
        drawTranslation(c, i, a * level);
    }

    /**
     * One word-timed layout, row by row: a row already sung at the sung brightness, a row not
     * reached at the unsung one, and the row being sung through a gradient that crosses from one
     * to the other at the character being sung.
     *
     * The gradient is the paint's own shader. It used to be a DST_IN mask over an offscreen layer
     * of the whole line - a second full-size pass on the render thread every frame, which was a
     * quarter of a core for as long as a word-timed song played (measured 2026-09-16).
     */
    private void drawWordRows(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                              float e, float sungA, float unsungA, float liftScale,
                              boolean glowOn, int gradSlot) {
        float sung = l.sungChars(ms);
        float feather = FEATHER_EM * p.getTextSize();
        int rows = lay.getLineCount();
        for (int r = 0; r < rows; r++) {
            int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
            rowAt = Float.NaN;
            if (sung >= re) {
                p.setColor(ink(sungA, e));
            } else if (sung <= rs) {
                p.setColor(ink(unsungA, 0f));
            } else {
                int ch = (int) sung;
                float f = sung - ch;
                float xa = xs[ch];
                float xb = ch + 1 < re ? xs[ch + 1] : lay.getLineRight(r);
                p.setColor(0xFFFFFFFF);
                rowAt = xa + (xb - xa) * f;
                p.setShader(gradient(ink(sungA, e), ink(unsungA, 0f), rowAt, feather,
                        gradSlot));
            }
            rowFeather = feather;
            rowSungA = sungA;
            rowUnsungA = unsungA;
            drawSyllables(c, l, lay, p, xs, e, r, liftScale, glowOn);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
        }
    }

    /**
     * One row's syllables in their places, each lifted by how far through it the singing is, and a
     * held note glowing and swelling while it lasts. Drawn as runs with the whole row as context,
     * so the shaping and the spacing are the ones the layout measured.
     */
    private void drawSyllables(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                               float e, int r, float liftScale, boolean glowOn) {
        int count = l.sylStart.length;
        int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
        float baseline = lay.getLineBaseline(r);
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : l.charEnd[k - 1];
            int cs = Math.max(from, rs), ce = Math.min(l.charEnd[k], re);
            if (ce <= cs) continue;
            int s = l.sylStart[k], end = l.sylEnd[k], dur = end - s;
            float rise = ms <= s ? 0f : clamp01((ms - s) / (float) Math.max(dur, LIFT_MIN_MS));
            float lift = liftPx * liftScale * e * (1f - (1f - rise) * (1f - rise));
            float glow = 0f;
            if (glowOn && dur >= GLOW_MIN_MS && ms > s) {
                glow = ms < end ? clamp01((ms - s) / (dur * 0.3f))
                        : 1f - clamp01((ms - end) / (float) GLOW_TAIL_MS);
                glow *= e;
            }
            float x0 = xs[cs];
            if (glow > 0.01f) {
                float x1 = ce < re ? xs[ce] : lay.getLineRight(r);
                int save = c.save();
                float swell = 1f + GLOW_SWELL * glow;
                c.scale(swell, swell, (x0 + x1) / 2f, baseline);
                float g = glowGain(glow);
                p.setShadowLayer(glowPx * glow, 0f, 0f, white(GLOW_ALPHA * glow, g));
                // The glowing syllable itself goes above white with its halo: its sung part
                // through this row's gradient re-coloured, or all of it once the row is sung.
                Shader rowShader = p.getShader();
                long rowColor = p.getColorLong();
                if (g > 1.001f) {
                    if (Float.isNaN(rowAt)) {
                        if (rowShader == null && l.sungChars(ms) >= ce) {
                            p.setColor(white(rowSungA, g));
                        }
                    } else {
                        p.setShader(gradient(white(rowSungA, g), ink(rowUnsungA, 0f), rowAt,
                                rowFeather, 2));
                    }
                }
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
                p.setShader(rowShader);
                p.setColor(rowColor);
                p.clearShadowLayer();
                c.restoreToCount(save);
            } else {
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
            }
        }
    }

    /** A left-to-right gradient centred on at. Rebuilt only when its two alphas change. */
    private Shader gradient(long hi, long lo, float at, float feather, int slot) {
        LinearGradient g = grads[slot];
        if (g == null || hi != gradHi[slot] || lo != gradLo[slot]) {
            gradHi[slot] = hi;
            gradLo[slot] = lo;
            g = new LinearGradient(-1f, 0f, 1f, 0f, new long[]{hi, lo}, null,
                    Shader.TileMode.CLAMP);
            grads[slot] = g;
        }
        gradMatrix.setScale(feather, 1f);
        gradMatrix.postTranslate(at, 0f);
        g.setLocalMatrix(gradMatrix);
        return g;
    }

    /** Each character's x, measured once per line the first time its words are drawn. */
    private float[] charXFor(int i, StaticLayout lay, LyricLine l, float[][] cache) {
        float[] xs = cache[i];
        if (xs != null) return xs;
        int len = l.text.length();
        xs = new float[len + 1];
        for (int k = 0; k < len; k++) xs[k] = lay.getPrimaryHorizontal(k);
        xs[len] = lay.getLineRight(lay.getLineCount() - 1);
        cache[i] = xs;
        return xs;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Diagnostics: which state asked for a redraw, per step, since the last describe(). */
    private final int[] whyCount = new int[9];
    private int stepCount, drawCount;

    private void noteWhy(int why) {
        stepCount++;
        for (int b = 0; b < whyCount.length; b++) {
            if ((why & (1 << b)) != 0) whyCount[b]++;
        }
    }

    String describe() {
        int words = 0;
        for (LyricLine l : lines) if (l.hasWords()) words++;
        StringBuilder w = new StringBuilder();
        String[] names = {"rebuild", "band", "show", "focus", "scroll", "snap", "emph", "blur", "words"};
        for (int b = 0; b < whyCount.length; b++) {
            if (whyCount[b] > 0) w.append(names[b]).append('=').append(whyCount[b]).append(',');
            whyCount[b] = 0;
        }
        String counts = " steps=" + stepCount + " draws=" + drawCount + " why=" + w;
        stepCount = drawCount = 0;
        return "lines=" + lines.size() + " wordLines=" + words + counts
                + " focus=" + focus + " ms=" + ms + " show=" + show
                + " band=" + (bandOk ? Math.round(bandTop) + ".." + Math.round(bandBottom) : "none")
                + " looping=" + looping + " parent=" + (getParent() instanceof ViewGroup);
    }
}
