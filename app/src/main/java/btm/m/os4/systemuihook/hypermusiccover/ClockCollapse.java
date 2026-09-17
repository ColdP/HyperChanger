package btm.m.os4.systemuihook.hypermusiccover;

import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Cover mode's clock: one progress, one owner, one place that writes the views.
 *
 * Two hands move the clock, and they are kept apart.
 *
 * - The OEM's. `notifStateChange(y)` squeezes its variable font - height, width and weight all
 *   follow y down to a floor - and translates its container. y is walked alongside our progress
 *   only so the glyph SHAPE morphs with the motion. Nothing reads the OEM's size or position as
 *   an input to where the clock goes.
 * - Ours. On every pre-draw, after the OEM has written the frame, the clock's live ink box is
 *   read and mapped by one scale and one translation onto the pose this frame should show.
 *   Whatever the OEM did underneath - its squeeze, its growth on a wake, its swap to the AOD
 *   layout, a relayout under a settled clock - is absorbed, because the mapping starts from the
 *   geometry that is about to be drawn, not from a frame ago.
 *
 * A pose is {top of the ink, height of one row of digits, top of the date} in screen pixels. A
 * transition interpolates from the pose on screen when it started to a destination re-derived
 * live every frame; settled, the destination is applied as it is. Every number comes from views,
 * the dp height setting or the date line - nothing depends on the display's resolution.
 *
 * The phases:
 *
 *     OFF    nothing of ours on the clock, no listener
 *     ENTER  springing into the cover look
 *     ON     the cover look, re-applied every frame
 *     EXIT   springing back to the OEM's own pose - out of cover mode, or into the AOD
 *     AOD    cover mode is on but the screen is asleep: the OEM's clock as it is, watched, so the
 *            wake can start from exactly what the AOD was showing
 */
final class ClockCollapse {
    private ClockCollapse() {
    }

    private static final String TAG = Main.TAG;

    enum Phase { OFF, ENTER, ON, EXIT, AOD }

    private static volatile Phase sPhase = Phase.OFF;
    /** This EXIT is the fall into the AOD, and lands in AOD rather than OFF. */
    private static boolean sExitToAod;

    /**
     * Set on the binder thread the moment the keyguard hears it is waking, before the OEM lays
     * the lock screen out again. The pre-draw of the first lock screen frame reads it, so the
     * entry starts in the same frame and no frame of the OEM's own full-size clock is drawn.
     */
    private static volatile boolean sWaking;

    /** Spring position (0 = where the transition started, 1 = its destination) and velocity. */
    private static float sT = 1f, sTv = 0f;
    private static Choreographer.FrameCallback sFrame;
    /** A spring whose frames stop - the display going to doze - is landed by this instead. */
    private static final long LAND_ANYWAY_MS = 1500L;

    /** The pose on screen when the transition started. NaN top = not captured yet. */
    private static float sFromTop = Float.NaN, sFromUnit = Float.NaN, sFromDate = Float.NaN;
    /** The last pose the AOD drew, for the wake to start from. NaN top = none yet. */
    private static float sAodTop = Float.NaN, sAodUnit = Float.NaN, sAodDate = Float.NaN;
    /** Card progress (0 = OEM's look, 1 = cover look) at the two ends. */
    private static float sCardFrom, sCardTo;
    /**
     * The glass morph's progress, separately from the card's: on a wake the card has been in its
     * cover look all along while the clock arrives from the AOD's full size, and the glass belongs
     * to the clock. The last value written, so an interrupted transition starts where it is.
     */
    private static float sGlassP, sGlassFrom, sGlassTo;
    /** The OEM's notifY at the two ends. NaN = y is not walked by this transition. */
    private static float sYFrom = Float.NaN, sYTo = Float.NaN;

    /**
     * The notifY below which the OEM's glyphs stop shrinking and only its container moves.
     * Derived from the OEM's own ClockResult; see floorY().
     */
    private static float sFloor = Float.NaN;
    /** The old device-specific floor, as a dp, for the one case nothing can be read. */
    private static final float FALLBACK_FLOOR_DP = 246.7f;

    private static View sGuarded;
    private static final int[] LOC = new int[2];

    /**
     * What one transition cost, logged as one line when it lands: our pre-draw work, the OEM's
     * y write (which is where it recomputes its variable font), and the longest gap between two
     * of our frames. The last one is the jank as the screen saw it; the first two say whose.
     */
    private static int sPerfN;
    private static long sPerfPreNs, sPerfPreMax, sPerfYNs, sPerfYMax, sPerfGapMax, sPerfLastAt;

    private static void perfReset() {
        sPerfN = 0;
        sPerfPreNs = sPerfPreMax = sPerfYNs = sPerfYMax = sPerfGapMax = sPerfLastAt = 0L;
    }

    private static String perfLine() {
        int n = Math.max(1, sPerfN);
        return "frames=" + sPerfN + " gapMax=" + sPerfGapMax / 1000000 + "ms"
                + " predraw avg=" + sPerfPreNs / n / 1000 + "us max=" + sPerfPreMax / 1000 + "us"
                + " oemY avg=" + sPerfYNs / n / 1000 + "us max=" + sPerfYMax / 1000 + "us";
    }

    /** The bottom of the clock's ink as last placed, in screen pixels. */
    private static volatile float sInkBottom = Float.NaN;

    // ------------------------------------------------------------------ public surface

    /**
     * Where the clock ends on screen this frame, for the lyrics to sit under. NaN while nothing
     * of ours is on the clock - the AOD's full clock is the OEM's, and nothing may sit under it.
     */
    static float inkBottomOnScreen() {
        Phase p = sPhase;
        return p == Phase.OFF || p == Phase.AOD ? Float.NaN : sInkBottom;
    }

    static Phase phase() {
        return sPhase;
    }

    /** Anything of ours on the clock that belongs to the lock screen being up. */
    static boolean active() {
        return sPhase == Phase.ENTER || sPhase == Phase.ON || sPhase == Phase.EXIT;
    }

    /** On its way out of cover mode proper - not into the AOD, which keeps cover mode on. */
    static boolean exiting() {
        return sPhase == Phase.EXIT && !sExitToAod;
    }

    /** Cover mode's clock is not at, and not on its way to, the cover look. */
    static boolean leavingOrOff() {
        return sPhase == Phase.OFF || sPhase == Phase.EXIT || sPhase == Phase.AOD;
    }

    /**
     * Takes the clock into the cover look.
     *
     * @param animate spring there, rather than arriving in one frame
     * @param wake    this is the way up from the AOD: the card has been in its cover look all
     *                along, so only the clock travels, and it travels from the AOD's clock
     */
    static void enter(boolean animate, boolean wake) {
        if (!Main.screenOn() && !sWaking) {
            // The AOD shows the full clock; the wake brings it in.
            toAod();
            return;
        }
        Phase was = sPhase;
        if (wake) {
            sWaking = false;
            Main.noteAwake();
        }
        float natural = naturalY();
        float floor = floorY(natural);
        sFullUnit = Float.NaN;
        prepareGlass();
        install();
        if (!animate || Main.sContainer == null) {
            stopFrame();
            sPhase = Phase.ON;
            sExitToAod = false;
            sT = 1f;
            sTv = 0f;
            hold(targetY(floor, natural));
            Main.setCardProgressFrom(1f);
            sGlassP = 1f;
            invalidate();
            Xp.log(TAG + "clock: cover look on (floor y=" + Main.r1(floor) + ")");
            return;
        }
        if (was == Phase.AOD && !Float.isNaN(sAodTop)) {
            sFromTop = sAodTop;
            sFromUnit = sAodUnit;
            sFromDate = sAodDate;
        } else {
            captureFrom();
            if (was == Phase.OFF && !wake && !Float.isNaN(sFromTop)) noteNaturalUnit(LIVE.unit);
            if (was == Phase.OFF && !wake && !Float.isNaN(sFromDate) && LIVE.date != null) {
                sDateAnchor = sFromDate;
                sDateAnchorView = LIVE.date;
            }
        }
        sCardFrom = wake ? 1f : Main.cardProgress();
        sCardTo = 1f;
        sGlassFrom = sGlassP;
        sGlassTo = 1f;
        sYFrom = currentY(natural);
        sYTo = targetY(floor, natural);
        sExitToAod = false;
        // A wake's glyphs already settle after the size, on the OEM's own animation; a toggle's
        // are given the same tail. See TOGGLE_GLYPH_RESPONSE.
        start(Phase.ENTER, wake ? Float.NaN : TOGGLE_GLYPH_RESPONSE);
        Xp.log(TAG + "clock: enter" + (wake ? " from the AOD" : "") + " y " + Main.r1(sYFrom)
                + " -> " + Main.r1(sYTo) + " from " + was);
    }

    /** Gives the clock back to the OEM, leaving cover mode. */
    static void exit(boolean animate) {
        if (sPhase == Phase.OFF) return;
        float natural = naturalY();
        if (sPhase == Phase.AOD || !animate || !Main.screenOn() || Main.sContainer == null
                || Float.isNaN(natural)) {
            releaseNow("exit");
            return;
        }
        captureFrom();
        sCardFrom = Main.cardProgress();
        sCardTo = 0f;
        sGlassFrom = sGlassP;
        sGlassTo = 0f;
        sYFrom = currentY(natural);
        sYTo = natural;
        sExitToAod = false;
        // The entry's glyph tail, mirrored. Not by sizing for the full clock first: the glyphs
        // are still squeezed and relatively far wider, and at full height they ran off both
        // edges of the screen (recording 18:44). The pose follows the OEM's live clock instead.
        start(Phase.EXIT, EXIT_GLYPH_RESPONSE);
        Xp.log(TAG + "clock: exit y " + Main.r1(sYFrom) + " -> " + Main.r1(sYTo));
    }

    /** The binder thread heard the keyguard start waking up. */
    static void noteWaking() {
        sWaking = true;
    }

    /**
     * The screen is falling asleep with cover mode on - the fade to black that leaves only the
     * clock, and then the AOD.
     *
     * The OEM's lock screen does not cut its clock to the AOD's: the background fades out and the
     * clock is seen becoming the AOD's clock. Ours is small and near the top, so it is sprung to
     * whatever the OEM lays out for the doze - read live every frame, so the AOD's layout arriving
     * mid-way is simply where the spring is going. The card and the colour belong to cover mode
     * and stay. Idempotent: the start of sleep and doAnimationToAod both land here.
     *
     * The OEM's y goes back to its natural value in one step, before the OEM's own AOD hand-over
     * reads the clock's translation for the AOD plugin. The glyph shape changes with it; its size
     * and place do not, the spring carries those.
     */
    static void toAod() {
        sWaking = false;
        // The start pose is read BEFORE the OEM's y goes back: the y changes the OEM's box at
        // once, and the transforms on the views still describe the old one - reading after would
        // start the spring from a clock several times the size of the one on screen.
        boolean flying = (sPhase == Phase.ENTER || sPhase == Phase.ON) && Main.sContainer != null;
        if (flying) captureFrom();
        Float held = Main.sHoldY;
        float natural = naturalY();
        if (held != null) {
            Main.sHoldY = null;
            if (!Float.isNaN(natural)) Main.applyY(natural);
        }
        switch (sPhase) {
            case AOD:
                return;
            case EXIT:
                if (sExitToAod) return;
                // A plain exit that was already flying: keep flying, but land in the AOD.
                sExitToAod = true;
                sYFrom = sYTo = Float.NaN;
                return;
            case OFF:
                if (!Main.coverModeOn()) return;
                // Cover mode without our clock - unlocked, say. Nothing to walk back; watch the
                // AOD so the wake has a start.
                sPhase = Phase.AOD;
                sAodTop = Float.NaN;
                install();
                Xp.log(TAG + "clock: watching the AOD");
                return;
            default:
                break;
        }
        if (!flying) {
            releaseNow("to AOD with no container");
            return;
        }
        sCardFrom = Main.cardProgress();
        sCardTo = sCardFrom;
        sGlassFrom = sGlassP;
        sGlassTo = 0f;
        sYFrom = sYTo = Float.NaN;
        sExitToAod = true;
        sAodTop = Float.NaN;
        start(Phase.EXIT);
        Xp.log(TAG + "clock: into the AOD");
        final Choreographer.FrameCallback flight = sFrame;
        View c = Main.sContainer;
        c.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPhase == Phase.EXIT && sExitToAod && sFrame == flight) {
                    stopFrame();
                    sT = 1f;
                    land();
                    invalidate();
                }
            }
        }, LAND_ANYWAY_MS);
    }

    /** Everything of ours off the clock - the phone was unlocked, or cover mode went away. */
    static void release(String why) {
        releaseNow(why);
    }

    /** A setting that changes the destination moved; the next frame picks it up. */
    static void refresh() {
        if (!active()) return;
        prepareGlass();
        // A new size can mean a different y: the OEM draws its own glyphs at that height.
        if (sPhase == Phase.ON && !Float.isNaN(sFloor)) hold(targetY(sFloor, naturalY()));
        invalidate();
    }

    /** The keyguard was rebuilt: new views, same phase. */
    static void onAttached() {
        if (sPhase == Phase.OFF) return;
        install();
        if (sPhase == Phase.ON) {
            // A rebuilt keyguard is a new clock that has never been told the hold.
            float f = floorY(naturalY());
            Main.sHoldY = null;
            hold(targetY(f, naturalY()));
        }
        invalidate();
    }

    static void onDetached() {
        uninstall();
        // A transition that was running has lost its views; land it where it was going.
        if (sPhase == Phase.ENTER) {
            stopFrame();
            sPhase = Phase.ON;
            sT = 1f;
        } else if (sPhase == Phase.EXIT) {
            stopFrame();
            sT = 1f;
            land();
        }
    }

    static String describe() {
        return "phase=" + sPhase + (sExitToAod ? "(to AOD)" : "") + " t=" + Main.r3(sT)
                + " waking=" + sWaking + " floor=" + Main.r1(sFloor)
                + " y " + Main.r1(sYFrom) + "->" + Main.r1(sYTo)
                + " card " + Main.r2(sCardFrom) + "->" + Main.r2(sCardTo)
                + " glass " + Main.r2(sGlassFrom) + "->" + Main.r2(sGlassTo)
                + " from top=" + Main.r1(sFromTop) + " unit=" + Main.r1(sFromUnit)
                + " date=" + Main.r1(sFromDate)
                + " aod top=" + Main.r1(sAodTop) + " unit=" + Main.r1(sAodUnit);
    }

    // ------------------------------------------------------------------ the OEM's y

    /** What the OEM last asked for on its own. */
    private static float naturalY() {
        return Main.sLastSystemY;
    }

    private static float currentY(float natural) {
        Float held = Main.sHoldY;
        if (held != null) return held;
        return natural;
    }

    /**
     * Holds the OEM at a y, and tells it only when that is news.
     *
     * Each notifStateChange makes the OEM recompute its clock layout and variable font, measured
     * at 2-5ms and up to 9ms a call - most of a 120Hz frame on its own. The hold itself is what
     * coerces the OEM's own re-assertions, so a sub-pixel step changes nothing and costs a frame.
     */
    private static void hold(float y) {
        if (Float.isNaN(y)) return;
        Float was = Main.sHoldY;
        Main.sHoldY = y;
        if (was != null && Math.abs(was - y) < Y_STEP) {
            Main.sHoldY = was;
            return;
        }
        Main.applyY(y);
    }

    /**
     * The OEM height the size setting asks for, in the OEM's own timeHeight units: a fraction of
     * adaptTimeHeight. NaN when no size is set - the dp default is small enough that the floor
     * is always right for it.
     */
    private static float wantedOemHeight(float max) {
        float s = Main.sClockSize;
        return Float.isNaN(s) || !(max > 0f) ? Float.NaN : s * max;
    }

    /**
     * The y to hold the OEM at for the size that is set.
     *
     * The squeeze is not a uniform shrink - the variable font changes width and weight as the
     * height drops, and at the floor the digits are relatively much wider than at full size - so
     * scaling a floor-sized clock back up to 100% gives the right height and the wrong width.
     * Instead the OEM is walked only as far as it takes to draw its own glyphs at the height
     * asked for, and the pre-draw scale covers what is left. Above the floor the OEM's height
     * rises one for one with y, so that y is floor + (wanted - min), and never past natural.
     */
    private static float targetY(float floor, float natural) {
        if (Float.isNaN(floor)) return floor;
        View c = Main.sContainer;
        if (c == null) return floor;
        try {
            Object it = Xp.getObjectField(c, "keyguardClockNotifInteractor");
            if (it == null) return floor;
            float min = num(Xp.getObjectField(it, "timeMinHeight"));
            float max = num(Xp.getObjectField(it, "adaptTimeHeight"));
            float want = wantedOemHeight(max);
            if (Float.isNaN(want) || !(want > min + 0.5f)) return floor;
            float y = floor + (Math.min(want, max) - min);
            if (!Float.isNaN(natural) && y > natural) y = natural;
            return y;
        } catch (Throwable t) {
            return floor;
        }
    }

    /** The smallest change of the OEM's y worth a notifStateChange. */
    private static final float Y_STEP = 1f;

    /**
     * Where the OEM's squeeze bottoms out, read off its own ClockResult.
     *
     * Measured across a sweep: above the floor `timeHeight = y - offset` (slope exactly 1), and
     * below it `timeHeight` sits at `timeMinHeight` while `clockTranslationY = y - floor`. Either
     * relation gives the floor from one reading, as long as it is read with the y that produced
     * it: the held one while we hold, the natural one otherwise. Pixels in, pixels out, no
     * constant.
     */
    private static float floorY(float natural) {
        float derived = Float.NaN;
        String how = "";
        View c = Main.sContainer;
        Float held = Main.sHoldY;
        float y = held != null ? held : natural;
        if (c != null && !Float.isNaN(y)) {
            try {
                Object it = Xp.getObjectField(c, "keyguardClockNotifInteractor");
                Object res = it == null ? null : Xp.getObjectField(it, "clockResult");
                if (res != null) {
                    float h = num(Xp.getObjectField(res, "timeHeight"));
                    float tr = num(Xp.getObjectField(res, "clockTranslationY"));
                    float min = num(Xp.getObjectField(it, "timeMinHeight"));
                    float max = num(Xp.getObjectField(it, "adaptTimeHeight"));
                    String key = layoutKey(it);
                    if (key != null && key.equals(sExactKey) && !Float.isNaN(sExactFloor)) {
                        derived = sExactFloor;
                        how = "exact, remembered for this layout";
                    } else if (h > min + 0.5f && h < max - 0.5f) {
                        derived = y - h + min;
                        how = "height";
                    } else if (tr < -0.5f && Math.abs(h - min) <= 0.5f) {
                        derived = y - tr;
                        how = "translation";
                    } else if (Math.abs(h - min) <= 0.5f) {
                        derived = y;
                        how = "at the floor";
                    } else if (h >= max - 0.5f && max > min) {
                        // Clamped at the top: the knee is somewhere at or below y, so the floor
                        // is at most (max - min) further down. An estimate - it only decides the
                        // glyph shape; the pre-draw decides size and place either way.
                        derived = y - (max - min);
                        how = "estimated from the top clamp";
                    }
                }
            } catch (Throwable t) {
                Xp.log(TAG + "clock: floor not readable (" + t + ")");
            }
        }
        // Per layout, never carried: a floor read in another layout (the keyguard has several)
        // is a wrong number here, and was measured to be one - 1969 against a natural 1682,
        // which walked the OEM's clock BIGGER on every entry.
        int screenH = c == null ? 0 : c.getResources().getDisplayMetrics().heightPixels;
        boolean sane = !Float.isNaN(derived) && derived > 0f
                && (screenH == 0 || derived < screenH)
                && (Float.isNaN(natural) || derived <= natural);
        float floor = sane ? derived : (Float.isNaN(natural) ? FALLBACK_FLOOR_DP * Main.density()
                : natural);
        if (Float.isNaN(sFloor) || Math.abs(floor - sFloor) >= 1f) {
            Xp.log(TAG + "clock: squeeze floor y=" + Main.r1(floor) + " ("
                    + (sane ? how : "no squeeze: derived " + Main.r1(derived)) + ", read at y="
                    + Main.r1(y) + ", natural " + Main.r1(natural) + ")");
        }
        sFloor = floor;
        return floor;
    }

    /**
     * Where the date stood on screen the last time cover mode was entered from the OEM's own
     * lock screen, and the date view that was.
     */
    private static float sDateAnchor = Float.NaN;
    private static View sDateAnchorView;

    /**
     * Where cover mode puts the date: a line near the top of the screen, in dp, kept clear of the
     * cut-out and the status bar. The keyguard's layouts put the date anywhere from 233 to 455 on
     * this phone, and cover mode wants it up top in all of them.
     */
    private static final float DATE_LINE_DP = 76f;
    /** Air between the top inset and the date, when the inset is what decides the line. */
    private static final float DATE_SAFE_GAP_DP = 8f;
    /**
     * Closer than this to the line and the date stays where it already was. That is the layout
     * whose date sits a few pixels off the line (233 against 228 here), where moving it reads as
     * a nudge rather than a placement.
     */
    private static final float DATE_SNAP_DP = 12f;

    /** The date line cover mode uses, for the app's preview as well. */
    static float coverDateY(View date) {
        float d = Main.density();
        float line = DATE_LINE_DP * d;
        float safe = safeTopPx();
        if (safe > 0f) line = Math.max(line, safe + DATE_SAFE_GAP_DP * d);
        if (date != null && date == sDateAnchorView && !Float.isNaN(sDateAnchor)
                && Math.abs(sDateAnchor - line) <= DATE_SNAP_DP * d) {
            return sDateAnchor;
        }
        return line;
    }

    /** How deep the top of the screen is covered - cut-out, else status bar - or 0. */
    private static float safeTopPx() {
        View v = Main.sContainer;
        if (v == null) return 0f;
        try {
            android.view.WindowInsets insets = v.getRootWindowInsets();
            if (insets == null) return 0f;
            android.view.DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null && cut.getSafeInsetTop() > 0) return cut.getSafeInsetTop();
            int bar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            return Math.max(bar, 0);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** The exact floor last read off a layout, and which layout it was. */
    private static float sExactFloor = Float.NaN;
    private static String sExactKey;

    private static String layoutKey(Object it) {
        try {
            return num(Xp.getObjectField(it, "timeMinHeight")) + "/"
                    + num(Xp.getObjectField(it, "adaptTimeHeight")) + "/"
                    + num(Xp.getObjectField(it, "maxSpace"));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tightens the floor while the y walks, from the frame just written.
     *
     * The floor an entry starts with can be an estimate (the OEM clamped at the top of its range
     * when it was read), and an estimate that is too high leaves the OEM's glyphs larger than its
     * minimum. The size on screen does not care - the pre-draw scales whatever is drawn - but the
     * liquid glass does: its rim and refraction are drawn in the glyph's own pixels and shrink
     * with our scale, so a clock drawn at 747px and scaled to a seventh looks thinner-glassed than
     * one drawn at 337 and scaled to a third, which is what it always was.
     *
     * Any frame whose height is inside the OEM's range gives the exact floor; arriving at the
     * target still clamped at the top means the knee is lower yet, so the walk goes on.
     */
    private static void refineFloor(float y) {
        View c = Main.sContainer;
        if (c == null || Float.isNaN(sYTo) || sPhase != Phase.ENTER) return;
        try {
            Object it = Xp.getObjectField(c, "keyguardClockNotifInteractor");
            Object res = it == null ? null : Xp.getObjectField(it, "clockResult");
            if (res == null) return;
            float h = num(Xp.getObjectField(res, "timeHeight"));
            float min = num(Xp.getObjectField(it, "timeMinHeight"));
            float max = num(Xp.getObjectField(it, "adaptTimeHeight"));
            float next = Float.NaN;
            float want = wantedOemHeight(max);
            if (h > min + 0.5f && h < max - 0.5f) {
                next = y - h + min;
                sExactFloor = next;
                sExactKey = layoutKey(it);
            } else if (h >= max - 0.5f && Math.abs(y - sYTo) < 1f && max > min
                    && !(want >= max - 0.5f)
                    && num(Xp.getObjectField(res, "clockTranslationY")) > -0.5f) {
                // Still at the top of its range and not being moved either: the knee is lower.
                // A clock at the top of its range that IS being moved is a layout that does not
                // squeeze at all, and walking further would only buy OEM recomputes. A size that
                // asks for the full height arrives at the top on purpose.
                next = y - (max - min);
            }
            // 2px, not 1: the reading rounds either side of the true knee and a slow glyph walk
            // reads it every frame, which ping-ponged 742/743 and re-held the OEM each time.
            if (!Float.isNaN(next) && next > 0f
                    && (Float.isNaN(sFloor) || Math.abs(next - sFloor) >= 2f)) {
                Xp.log(TAG + "clock: floor " + Main.r1(sFloor) + " -> " + Main.r1(next)
                        + " (height " + Main.r1(h) + " at y=" + Main.r1(y) + ")");
                sFloor = next;
                sYTo = targetY(next, naturalY());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The lowest screen y the clock's ink may reach: the top of the notification stack, less the
     * OEM's own clock-to-notification margin. NaN when nothing says.
     */
    private static float roomBelow() {
        float y = naturalY();
        View c = Main.sContainer;
        if (Float.isNaN(y) || c == null) return Float.NaN;
        float margin = Float.NaN;
        try {
            Object it = Xp.getObjectField(c, "keyguardClockNotifInteractor");
            if (it != null) margin = num(Xp.getObjectField(it, "clockNotificationMargin"));
        } catch (Throwable ignored) {
        }
        if (Float.isNaN(margin)) margin = NOTIF_MARGIN_DP * Main.density();
        return y - margin;
    }

    /** The OEM's margin, as a dp, for a build whose interactor does not say. */
    private static final float NOTIF_MARGIN_DP = 18f;

    /**
     * How far a notification may squeeze the small clock, as a fraction of its set height: the
     * OEM's own minimum-to-maximum glyph ratio, so the two clocks give way alike - but never below
     * half, which is where a clock that is small to begin with stops being read at a glance.
     */
    private static float minSqueeze() {
        float r = Float.NaN;
        View c = Main.sContainer;
        try {
            Object it = c == null ? null : Xp.getObjectField(c, "keyguardClockNotifInteractor");
            if (it != null) {
                float min = num(Xp.getObjectField(it, "timeMinHeight"));
                float max = num(Xp.getObjectField(it, "adaptTimeHeight"));
                if (min > 0f && max > min) r = min / max;
            }
        } catch (Throwable ignored) {
        }
        if (Float.isNaN(r)) r = MIN_SQUEEZE;
        return Math.max(MIN_SQUEEZE, Math.min(1f, r));
    }

    private static final float MIN_SQUEEZE = 0.5f;

    private static float num(Object o) {
        return o instanceof Number ? ((Number) o).floatValue() : Float.NaN;
    }

    /**
     * The size setting's 100%: the row height of the clock the lock screen shows with cover mode
     * off, measured - not derived - and remembered per clock style, across restarts.
     *
     * Two derivations failed on the phone. unit x adaptTimeHeight / timeHeight drifts a few
     * percent with the held y (ink and timeHeight are not proportional under the variable font),
     * which was a jump on landing; and "timeHeight == adaptTimeHeight means full height" is not
     * true on the layouts whose height number never moves while the ink does, which stored a
     * squeezed clock as the full one and made a wake's clock half the size of a toggle's. The
     * only reading that is the full clock by definition is the OEM's own clock, untouched, at
     * the moment cover mode takes it from the lock screen - so that is the one used.
     */
    private static volatile float sFullNatural = Float.NaN;
    private static volatile String sFullStyle;
    /** Until a measurement exists: one estimate per transition, so it cannot move mid-flight. */
    private static float sFullUnit = Float.NaN;

    private static String styleKey() {
        View[] roots = Main.clockRoots();
        for (View root : roots) {
            if (root instanceof android.view.ViewGroup && ((android.view.ViewGroup) root).getChildCount() > 0) {
                return ((android.view.ViewGroup) root).getChildAt(0).getClass().getName();
            }
        }
        return null;
    }

    /** Called with the OEM's own lock screen clock, before anything of ours is on it. */
    private static void noteNaturalUnit(float unit) {
        String style = styleKey();
        if (style == null || !(unit > 0f)) return;
        // The largest seen: an entry can catch the OEM's clock still giving way to a notification,
        // and that is a smaller clock, never a larger one.
        if (style.equals(sFullStyle) && unit < sFullNatural + 0.5f) return;
        Xp.log(TAG + "clock: full unit measured " + Main.r1(unit) + " for " + style
                + " (was " + Main.r1(sFullNatural) + " for " + sFullStyle + ")");
        sFullStyle = style;
        sFullNatural = unit;
        Main.saveState();
    }

    /** For the state file: "style|unit", or null. */
    static String fullUnitState() {
        return sFullStyle == null || Float.isNaN(sFullNatural) ? null
                : sFullStyle + "|" + sFullNatural;
    }

    static void restoreFullUnit(String v) {
        int bar = v.lastIndexOf('|');
        if (bar <= 0) return;
        try {
            sFullNatural = Float.parseFloat(v.substring(bar + 1));
            sFullStyle = v.substring(0, bar);
        } catch (NumberFormatException ignored) {
        }
    }

    /** How many times the glyphs drawn now must grow to be the full clock, for the app. */
    static float fullRatio(float unitNow) {
        if (!(unitNow > 0f)) return 1f;
        String style = styleKey();
        if (style != null && style.equals(sFullStyle) && !Float.isNaN(sFullNatural)) {
            return sFullNatural / unitNow;
        }
        return oemGrowth();
    }

    private static float fullUnitFor(Phase phase, Live m) {
        String style = styleKey();
        if (style != null && style.equals(sFullStyle) && !Float.isNaN(sFullNatural)) {
            return sFullNatural;
        }
        if (!Float.isNaN(sFullUnit)) return sFullUnit;
        float live = m.unit * oemGrowth();
        if (phase == Phase.ON || phase == Phase.EXIT) sFullUnit = live;
        return live;
    }

    /**
     * How many times taller the OEM's clock is at full size than it is being drawn now:
     * adaptTimeHeight over timeHeight. 1 on the layouts whose squeeze is only a translation.
     *
     * timeHeight is not the ink height (502 against 423 once), so it is used only as a ratio,
     * against itself - the ink box is what gets multiplied.
     */
    static float oemGrowth() {
        View c = Main.sContainer;
        if (c == null) return 1f;
        try {
            Object it = Xp.getObjectField(c, "keyguardClockNotifInteractor");
            Object res = it == null ? null : Xp.getObjectField(it, "clockResult");
            if (res == null) return 1f;
            float h = num(Xp.getObjectField(res, "timeHeight"));
            float max = num(Xp.getObjectField(it, "adaptTimeHeight"));
            if (!(h > 0f) || !(max > h)) return 1f;
            return max / h;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** Whether v is drawn inside ancestor, and so already moves with it. */
    private static boolean inside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the spring

    /**
     * The glyph spring of a toggle between the big and small clock: critically damped, 0.9s.
     *
     * Copied from a wake, measured frame by frame on the phone (op motiontrace): the clock's size
     * lands in about 0.35s, and the OEM's glyphs keep settling after it - 22% of their change
     * still to come at 0.41s, 2% at 0.81s - which is the "drawn in" look. That tail is exactly a
     * zeta 1 spring of response 0.9s started with the size. A toggle used to walk the glyphs in
     * lockstep with the size, so everything stopped at once.
     */
    private static final float TOGGLE_GLYPH_RESPONSE = 0.9f;
    /** Where the glyph spring is aimed: past 1, so it crosses the end instead of creeping to it. */
    private static final float GLYPH_AIM = 1.02f;
    /** The same tail leaving, faster: the user found the height's catch-up too slow at 0.9. */
    private static final float EXIT_GLYPH_RESPONSE = 0.55f;

    /** Whether this transition runs the glyph spring. */
    private static boolean sGlyphTail;
    /** The narrowest the clock has been drawn this entry: its width only ever closes in. */
    private static float sMinDrawnW = Float.MAX_VALUE;

    /** How far the pose spring has ever got: without a glyph spring the OEM's y follows this. */
    private static float sTPeak;
    /** The glyph spring's position and velocity. */
    private static float sG, sGv;

    private static void start(Phase p) {
        start(p, Float.NaN);
    }

    /** @param glyphResponse the OEM's y on a spring of its own, in seconds; NaN = follow the pose */
    private static void start(Phase p, float glyphResponse) {
        stopFrame();
        perfReset();
        sPhase = p;
        sT = 0f;
        sTv = 0f;
        sTPeak = 0f;
        sG = 0f;
        sGv = 0f;
        final boolean glyphs = !Float.isNaN(glyphResponse);
        sGlyphTail = glyphs;
        sMinDrawnW = Float.MAX_VALUE;
        final float gw0 = glyphs ? (float) (2 * Math.PI / glyphResponse) : 0f;
        final float gk = gw0 * gw0;
        final float gDamp = 2f * gw0;
        final float zeta = Main.EASE_COVER[0];
        final float response = Main.sClockResponse;
        final float w0 = (float) (2 * Math.PI / response);
        final float k = w0 * w0;
        final float damp = 2f * zeta * w0;
        final long[] last = {0L};
        final int[] frames = {0};
        sFrame = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long now) {
                if (sFrame != this) return;
                if (last[0] == 0L) last[0] = now;
                float dt = (now - last[0]) / 1e9f;
                last[0] = now;
                if (dt <= 0f) dt = 1f / 120f;
                if (dt > 0.05f) dt = 0.05f;
                // The first frames of a toggle are the ones the rest of cover mode is busy on
                // (wallpaper, card, colour), and a stall there used to be integrated whole: the
                // clock jumped 110-140px in its second frame. Started from rest, it starts on time.
                if (++frames[0] <= 3 && dt > 1f / 60f) dt = 1f / 120f;
                int steps = Math.max(1, (int) Math.ceil(dt * 240f));
                float h = dt / steps;
                for (int i = 0; i < steps; i++) {
                    float a = -k * (sT - 1f) - damp * sTv;
                    sTv += a * h;
                    sT += sTv * h;
                }
                boolean poseDone = Math.abs(sT - 1f) < 0.001f && Math.abs(sTv) < 0.01f;
                if (poseDone) {
                    sT = 1f;
                    sTv = 0f;
                }
                // The OEM's shape: on its own, slower spring when one is given - the size lands
                // and the glyphs keep settling after it - otherwise the pose's progress.
                boolean shapeDone = true;
                float shape;
                if (glyphs) {
                    // Aimed a little past the end and stopped where it crosses it. A spring that
                    // settles onto 1 creeps the last few percent - 1-2px a frame for 250ms, which
                    // reads as the clock stopping - and a test that calls it done at 99% then
                    // hands the last 1% to the OEM in one frame (1022 -> 1030 measured). Crossing
                    // at speed ends it at about a pixel a frame, with nothing left to jump.
                    for (int i = 0; i < steps; i++) {
                        float a = -gk * (sG - GLYPH_AIM) - gDamp * sGv;
                        sGv += a * h;
                        sG += sGv * h;
                    }
                    shapeDone = sG >= 1f;
                    if (shapeDone) sG = 1f;
                    shape = Math.max(0f, Math.min(1f, sG));
                } else {
                    // Monotonic: a bounce belongs to the pose. Walking the OEM back would re-shape
                    // its glyphs and cost a relayout per frame for nothing.
                    sTPeak = Math.max(sTPeak, sT);
                    shape = Math.min(1f, sTPeak);
                }
                boolean done = poseDone && shapeDone;
                if (sPerfLastAt != 0L) sPerfGapMax = Math.max(sPerfGapMax, now - sPerfLastAt);
                sPerfLastAt = now;
                sPerfN++;
                if (!Float.isNaN(sYFrom) && !Float.isNaN(sYTo)) {
                    long y0 = System.nanoTime();
                    float y = done ? sYTo : sYFrom + (sYTo - sYFrom) * shape;
                    if (done && Main.sHoldY != null && Main.sHoldY != y) Main.sHoldY = null;
                    hold(y);
                    refineFloor(y);
                    // Landed on a floor that turned out not to be one: finish the walk now,
                    // while the clock is at its smallest. A few rounds, since each reading is
                    // exact only if the OEM's slope is what it was measured to be.
                    for (int i = 0; done && i < 4 && sPhase == Phase.ENTER
                            && Main.sHoldY != null && Math.abs(Main.sHoldY - sYTo) >= 1f; i++) {
                        hold(sYTo);
                        refineFloor(sYTo);
                    }
                    long yd = System.nanoTime() - y0;
                    sPerfYNs += yd;
                    if (yd > sPerfYMax) sPerfYMax = yd;
                }
                invalidate();
                if (!done) {
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                sFrame = null;
                land();
            }
        };
        Choreographer.getInstance().postFrameCallback(sFrame);
        invalidate();
    }

    private static void stopFrame() {
        Choreographer.FrameCallback f = sFrame;
        sFrame = null;
        if (f != null) Choreographer.getInstance().removeFrameCallback(f);
    }

    private static void land() {
        Xp.log(TAG + "clock: " + sPhase + (sExitToAod ? "(to AOD)" : "") + " landed, " + perfLine());
        if (sPhase == Phase.ENTER) {
            sPhase = Phase.ON;
            Xp.log(TAG + "clock: cover look settled");
        } else if (sPhase == Phase.EXIT) {
            if (sExitToAod) {
                // The OEM's pose is on screen now; nothing of ours is left on the views, but the
                // listener stays to remember what the AOD draws.
                sExitToAod = false;
                sPhase = Phase.AOD;
                clearTransforms();
                Main.restoreGlass();
                Xp.log(TAG + "clock: in the AOD");
            } else {
                releaseNow("exit landed");
            }
        }
    }

    // ------------------------------------------------------------------ release

    /**
     * Everything of ours off the clock, in one frame: transforms, hold, glass, listener. At the
     * end of an exit this changes nothing on screen - the last frame already drew the OEM's pose.
     */
    private static void releaseNow(String why) {
        boolean was = sPhase != Phase.OFF || Main.sHoldY != null;
        stopFrame();
        sPhase = Phase.OFF;
        sExitToAod = false;
        sT = 1f;
        sTv = 0f;
        sFromTop = Float.NaN;
        sAodTop = Float.NaN;
        sGlassP = 0f;
        uninstall();
        clearTransforms();
        Float held = Main.sHoldY;
        Main.sHoldY = null;
        float natural = naturalY();
        if (held != null && !Float.isNaN(natural)) Main.applyY(natural);
        Main.restoreGlass();
        Main.onClockReleased();
        if (was) Xp.log(TAG + "clock: released (" + why + ")");
    }

    private static void clearTransforms() {
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            if (g.getScaleX() != 1f) g.setScaleX(1f);
            if (g.getScaleY() != 1f) g.setScaleY(1f);
            if (g.getTranslationY() != 0f) g.setTranslationY(0f);
        }
        View date = Main.sDateView;
        if (date != null && date.getTranslationY() != 0f) date.setTranslationY(0f);
    }

    // ------------------------------------------------------------------ per frame

    private static void install() {
        View v = Main.sContainer;
        if (v == null || sGuarded == v) return;
        uninstall();
        try {
            v.getViewTreeObserver().addOnPreDrawListener(PRE_DRAW);
            sGuarded = v;
        } catch (Throwable t) {
            Xp.log(TAG + "clock: pre-draw not installed: " + t);
        }
    }

    private static void uninstall() {
        View v = sGuarded;
        sGuarded = null;
        if (v == null) return;
        try {
            v.getViewTreeObserver().removeOnPreDrawListener(PRE_DRAW);
        } catch (Throwable ignored) {
        }
    }

    private static void invalidate() {
        View v = Main.sContainer;
        if (v != null) v.invalidate();
    }

    private static void prepareGlass() {
        Main.armGlassMorph();
    }

    private static final ViewTreeObserver.OnPreDrawListener PRE_DRAW =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            long t0 = System.nanoTime();
            try {
                frame();
            } catch (Throwable t) {
                Xp.log(TAG + "clock: frame failed: " + t);
            }
            if (sFrame != null) {
                long d = System.nanoTime() - t0;
                sPerfPreNs += d;
                if (d > sPerfPreMax) sPerfPreMax = d;
            }
            return true;
        }
    };

    /**
     * The ink height each glyph view last reported, to spot the frame it jumps.
     *
     * TimeView computes its bounds inside its own onDraw (Path.computeBounds), so a pre-draw
     * reads the PREVIOUS frame's box. While the OEM moves smoothly that is a few pixels and
     * invisible. When it changes size in one frame - the wake, where it goes from the AOD's glyphs
     * to the lock screen's in a single layout - the pre-draw maps last frame's box, the scale comes
     * out near 1, and the full-size clock is drawn for a frame: the flash.
     */
    private static final java.util.WeakHashMap<View, Float> sGlyphH = new java.util.WeakHashMap<>();
    /**
     * A change worth re-placing for, in pixels of glyph height.
     *
     * Was 2% of the height, which only ever had to catch a wake's abrupt jump. With the glyphs on
     * their own spring they change 1-3% every frame, right across that line, so every other
     * frame was placed off last frame's box: the drawn height stepped +55 +52 +48 +22 +60 +7
     * +42 +5 - the judder read as "顿". Any visible change now re-places; the pass is ~0.2ms,
     * and RenderNode properties set during the draw still land on this frame.
     */
    private static final float GLYPH_JUMP_PX = 0.5f;
    private static boolean sRedoing;

    /**
     * A glyph view has just drawn, so its bounds are this frame's now.
     *
     * If they jumped, the frame is placed again with them, from inside the draw. The transforms
     * are RenderNode properties of the clock group and the date, which are synced to the render
     * thread after the draw pass, so writing them here still lands on the frame being drawn.
     */
    static void onGlyphDrawn(View tv) {
        if (sPhase == Phase.OFF || sRedoing) return;
        RectF b;
        try {
            b = (RectF) Xp.callMethod(tv, "getTextBoundsWithPosition");
        } catch (Throwable t) {
            return;
        }
        if (b == null) return;
        float h = b.height();
        Float was = sGlyphH.put(tv, h);
        if (was == null || h <= 0f || Math.abs(h - was) <= GLYPH_JUMP_PX) return;
        sRedoing = true;
        try {
            frame();
        } catch (Throwable t) {
            Xp.log(TAG + "clock: re-place failed: " + t);
        } finally {
            sRedoing = false;
        }
    }

    /** One measurement of what the OEM is about to draw, in screen pixels. */
    private static final class Live {
        RectF box;
        float unit;
        float inkTop;
        View date;
        float dateTop;
        float dateH;
        boolean anchored;
    }

    private static final Live LIVE = new Live();

    private static float parentTop(View v) {
        if (!(v.getParent() instanceof View)) return 0f;
        ((View) v.getParent()).getLocationOnScreen(LOC);
        return LOC[1];
    }

    private static View firstTarget() {
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g != null) return g;
        }
        return null;
    }

    private static boolean measure(Live m) {
        View g = firstTarget();
        if (g == null) return false;
        RectF box = Main.glyphBox(true);
        if (box == null || box.height() <= 0f) return false;
        m.box = box;
        m.unit = Main.glyphUnit(box);
        m.inkTop = parentTop(g) + g.getTop() + box.top;
        m.anchored = Main.anchoredStyle();
        View date = Main.visibleDate();
        Main.noteDateView(date);
        m.date = date;
        if (date != null) {
            m.dateTop = parentTop(date) + date.getTop();
            m.dateH = date.getHeight();
        } else {
            m.dateTop = Float.NaN;
            m.dateH = 0f;
        }
        return !(m.anchored && date == null);
    }

    /** For MotionTrace: the last frame's glyphs and the two springs, as drawn on screen. */
    static String traceLine() {
        Live m = LIVE;
        View g = firstTarget();
        if (m.box == null || g == null) return " clk=none";
        float s = g.getScaleY();
        return " clk[" + sPhase + " t=" + Main.r3(sT) + " g=" + Main.r3(sG)
                + " ink=" + Main.r1(m.unit * s) + "x" + Main.r1(m.box.width() * g.getScaleX())
                + " oem=" + Main.r1(m.unit) + "x" + Main.r1(m.box.width())
                + " top=" + Main.r1(m.inkTop + g.getTranslationY()) + " y=" + Main.sHoldY + "]";
    }

    /** What is on screen right now, from the transforms currently on the views. */
    private static void captureFrom() {
        Live m = LIVE;
        if (!measure(m)) {
            sFromTop = Float.NaN;
            return;
        }
        View g = firstTarget();
        sFromTop = m.inkTop + g.getTranslationY();
        sFromUnit = m.unit * g.getScaleY();
        sFromDate = m.date == null ? Float.NaN : m.dateTop + m.date.getTranslationY();
    }

    private static void frame() {
        Phase phase = sPhase;
        if (phase == Phase.OFF) return;
        Live m = LIVE;
        if (!measure(m)) return;

        if (phase == Phase.AOD) {
            if (sWaking && Main.coverModeOn()) {
                // The first lock screen frame of the wake, before it is drawn. Enter now, from the
                // clock the AOD was showing, and place this very frame.
                enter(true, true);
                phase = sPhase;
                if (phase != Phase.ENTER) return;
            } else {
                // The OEM's clock as it is. What it looks like here is the wake's start.
                clearTransforms();
                sAodTop = m.inkTop;
                sAodUnit = m.unit;
                sAodDate = m.dateTop;
                return;
            }
        }

        Main.convertLegacyHeight(m.box);
        if (phase == Phase.ON && Float.isNaN(sFloor)) {
            // Settled before the floor could be read - a restore at startup, before the keyguard
            // existed. The OEM has laid out by now, so read it and hold there.
            float f = floorY(naturalY());
            if (!Float.isNaN(sFloor)) hold(targetY(f, naturalY()));
        }
        if (phase != Phase.ON && Float.isNaN(sFromTop)) {
            // Nothing was measurable when the transition started. The pose on screen now is the
            // best start there is: the transforms are still whatever they were.
            View g = firstTarget();
            sFromTop = m.inkTop + g.getTranslationY();
            sFromUnit = m.unit * g.getScaleY();
            sFromDate = m.date == null ? Float.NaN : m.dateTop + m.date.getTranslationY();
        }

        // The cover pose, live.
        float d = Main.density();
        float full = fullUnitFor(phase, m);
        float size = Main.sClockSize;
        float coverUnit = Float.isNaN(size) ? Main.sClockHeightDp * d : size * full;
        if (coverUnit > full) coverUnit = full;
        if (coverUnit < Main.MIN_CLOCK_K * full) coverUnit = Main.MIN_CLOCK_K * full;
        // The date and the clock move as one block.
        float offset = Main.sClockOffsetDp * d;
        float coverDate, coverTop;
        if (m.anchored) {
            coverDate = coverDateY(m.date) + offset;
            coverTop = coverDate + m.dateH + Main.CLOCK_GAP_DP * d;
        } else {
            coverDate = m.dateTop + offset;
            coverTop = m.inkTop + offset;
        }

        // Notifications. The OEM squeezes its full clock out of their way - down to its minimum
        // height, then up - and ours gets the same rule, off the same number: the notifY the OEM
        // keeps reporting while we hold it is the screen y the stack starts at.
        float room = roomBelow();
        if (!Float.isNaN(room)) {
            float ratio = m.box.height() / m.unit;
            float bottom = coverTop + coverUnit * ratio;
            if (bottom > room) {
                float minUnit = coverUnit * minSqueeze();
                float fit = (room - coverTop) / ratio;
                float u = Math.max(minUnit, Math.min(coverUnit, fit));
                float overflow = coverTop + u * ratio - room;
                coverUnit = u;
                if (overflow > 0f) {
                    coverTop -= overflow;
                    if (!Float.isNaN(coverDate)) coverDate -= overflow;
                }
            }
        }

        float top, unit, date, card, glass;
        if (phase == Phase.ON) {
            top = coverTop;
            unit = coverUnit;
            date = coverDate;
            card = 1f;
            glass = 1f;
        } else {
            float t = sT;
            boolean in = phase == Phase.ENTER;
            float toTop = in ? coverTop : m.inkTop;
            // Leaving, the size follows the OEM's own clock as its glyphs grow on their spring:
            // one smooth curve, in the OEM's own proportions the whole way, so never wider than
            // the clock it lands on. A width cap tried first put a kink in the growth where it
            // started to bind (40px a frame to 14 in one frame) - the "顿" at the end.
            float toUnit = in ? coverUnit : m.unit;
            float toDate = in ? coverDate : m.dateTop;
            top = sFromTop + (toTop - sFromTop) * t;
            unit = sFromUnit + (toUnit - sFromUnit) * t;
            date = Float.isNaN(sFromDate) ? toDate : sFromDate + (toDate - sFromDate) * t;
            float tc = Math.max(0f, Math.min(1f, t));
            card = sCardFrom + (sCardTo - sCardFrom) * tc;
            glass = sGlassFrom + (sGlassTo - sGlassFrom) * tc;
        }

        float scale = unit / m.unit;
        sInkBottom = top + m.box.height() * scale;
        float scaleX = scale;
        if (phase == Phase.ENTER && sGlyphTail && m.box.width() > 0f) {
            // The OEM's glyph aspect is not monotonic along the walk - measured 1.47 -> 1.68 ->
            // 1.50 while the height held - so the drawn width would swell and shrink back as the
            // clock arrives. Only the width is held to closing in. Holding it through the uniform
            // scale took the height 12px under its target and let it spring back on landing,
            // which was worse; squeezing X alone narrows the glyphs a few percent for a moment
            // and returns to uniform by itself as the aspect comes back.
            float w = m.box.width() * scale;
            if (w > sMinDrawnW) {
                // Faded out with the glyph spring, so landing is uniform whatever the digits do.
                float held = sMinDrawnW / m.box.width();
                float g = Math.max(0f, Math.min(1f, sG));
                scaleX = held + (scale - held) * g * g;
            } else {
                sMinDrawnW = w;
            }
        }
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            float px = Main.clockPivotX(g, m.box);
            if (g.getPivotX() != px) g.setPivotX(px);
            if (g.getPivotY() != m.box.top) g.setPivotY(m.box.top);
            if (g.getScaleX() != scaleX) g.setScaleX(scaleX);
            if (g.getScaleY() != scale) g.setScaleY(scale);
            float ty = top - (parentTop(g) + g.getTop() + m.box.top);
            if (Math.abs(g.getTranslationY() - ty) >= 0.25f) g.setTranslationY(ty);
        }
        // The other styles lay their own date out, and it is only moved to follow the offset -
        // unless it sits inside the clock, which carries it already.
        if (m.date != null && !Float.isNaN(date)
                && (m.anchored || !inside(m.date, firstTarget()))) {
            float dty = date - m.dateTop;
            if (Math.abs(m.date.getTranslationY() - dty) >= 0.25f) m.date.setTranslationY(dty);
        }
        Main.setCardProgressFrom(card);
        sGlassP = glass;
        Main.applyGlassMorph(glass);
        // The colour band describes the lock screen; the AOD's layout is not that.
        if (!(phase == Phase.EXIT && sExitToAod)) Main.updateColorBand();
    }
}
