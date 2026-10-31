// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

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
public final class ClockCollapse {
    private ClockCollapse() {
    }

    private static final String TAG = Main.TAG;

    public enum Phase { OFF, ENTER, ON, EXIT, AOD }

    private static volatile Phase sPhase = Phase.OFF;
    /** This EXIT is the fall into the AOD, and lands in AOD rather than OFF. */
    private static boolean sExitToAod;

    /**
     * This doze is keeping the cover's clock rather than the OEM's.
     *
     * Latched when the screen falls asleep, not read live: the setting can be flipped from adb
     * at any moment, and a doze that changed its mind half way through would leave the clock
     * scaled onto one layout and posed for another.
     *
     * Off, sleeping hands the clock back - toAod() springs it to whatever the doze lays out and
     * land() clears our transforms. On, and only under the full-screen AOD (the mode the setting
     * is about; see Main.sAodSmall), the pose on screen at the moment of sleep is remembered and
     * re-applied every frame of the doze, so nothing moves at all.
     *
     * The OEM is trying to do the same thing - AllInOneClockAnimation.updateFullAodAnimState()
     * copies the LOCK SCREEN's timeHeight and clockTranslationY into all_in_one_full_aod - but by
     * the time it does, the clock it copies is the one we have already handed back, which is the
     * full-size one. The small clock has to stay ours.
     */
    private static boolean sAodHeld;

    /**
     * Whether this doze is the FULL-SCREEN one, latched at the start of sleep like sAodHeld.
     *
     * Latched for the same reason and read for one question: who the doze clock's COLOUR belongs
     * to when the clock was handed back. The full-screen doze is the one the setting is about - the
     * whole lock screen shown dimmed, its own big clock over the album art - and a user who turned
     * the setting off is asking for the system's clock there, colour included; a colour of ours on
     * it is invented out of nothing and reads as a grey slab (reported 2026-09-21). Every other
     * doze is held at a neutral, which is what stopped the plain AOD turning gold - see
     * AodColour.holdAodColour(). Latched rather than read live: `Main.fullAodOn()` reflects into the
     * interfaces manager and is not something to ask on a per-frame path.
     */
    private static boolean sAodFullScreen;

    /**
     * The OEM's y as it was held when the screen fell asleep, put back on it for the length of a
     * held doze.
     *
     * Not the same value as the doze would pick for itself, and that is the point: y is what the
     * OEM's VARIABLE FONT is sized and weighted by, so a clock drawn from the doze's own y is a
     * clock drawn in a lighter, wider face - and scaling that down to the cover's 174px gives
     * hairlines where the lock screen has strokes. Holding the lock screen's y again makes the
     * OEM redraw its glyphs in the face it was already using, and the same scale then lands on
     * the same picture. updateClockComponentStyle re-copies lockScreenStyle into the full-AOD
     * state on every restyle, so the AOD's own animation picks the change up as well.
     *
     * The y is still released for the hand-over itself - doAnimationToAod reads the clock's
     * translation and posts it to the AOD plugin - and put back on the first doze frame after.
     */
    private static float sAodHoldY = Float.NaN;

    /** Whether the doze about to start should keep our clock. Read once, at the start of sleep. */
    private static boolean keepInAod() {
        // An immersive page as well as the cover: the full-screen doze keeps the page under the
        // clock (ImmersiveHost), and only that doze - the plain one is the OEM's, page and clock
        // alike.
        return Main.sAodSmall && Main.fullAodOn();
    }

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
    /** How close the OEM's own clock has to be to the doze before the hand-over is safe. */
    private static final float DOZE_SETTLE_PX = 3f;

    /** The pose on screen when the transition started. NaN top = not captured yet. */
    private static float sFromTop = Float.NaN, sFromUnit = Float.NaN, sFromDate = Float.NaN;
    /** The last pose the AOD drew, for the wake to start from. NaN top = none yet. */
    private static float sAodTop = Float.NaN, sAodUnit = Float.NaN, sAodDate = Float.NaN;

    /**
     * The pose our own pre-draw last put on the clock while the lock screen was up.
     *
     * A held doze keeps "the pose on screen when the screen fell asleep", and that pose is a
     * number this class wrote itself - not one to measure again at the moment of sleep. The
     * keyguard need not be drawing then: the phone can be unlocked when the power button is
     * pressed, and the lock screen is re-shown behind the sleep. A fresh measurement of that
     * moment asks for ink that is actually drawn (ClockViews.collectTimeViews, drawnUnder and
     * usableDate all ignore a faded face), comes back empty, leaves sFromTop NaN, and the doze
     * hands the clock back to the OEM - the full-size clock (reported 2026-10-14, issue #88).
     *
     * Our own last write has none of those preconditions. Written by every ENTER/ON frame that
     * places the clock; read by toAod(). NaN = nothing placed this session.
     */
    private static float sDrawnTop = Float.NaN, sDrawnUnit = Float.NaN, sDrawnDate = Float.NaN;
    /** One warning per sleep when a held doze has no pose at all. See the AOD branch of frame(). */
    private static boolean sAodPoseWarned;

    /**
     * The doze pose as the frame before last reported it, waiting for a frame that agrees.
     *
     * Not a pose anything is placed from: it is the previous reading, and a reading is only
     * promoted to sAodTop/sAodUnit/sAodDate when the next one matches it.
     *
     * This is what fixed the wake that came out two different ways (reported 2026-09-21, and
     * again on 2026-09-22 when the first attempt turned out to be a no-op). A wake runs the
     * OEM's own clock animation for a good hundred milliseconds before any route of ours takes
     * the entry, and those frames arrive in the AOD branch like any other: every one of them
     * used to be written straight over the doze's pose, so the wake shrank from a clock that
     * had never been on screen - 1328.9 recorded against the 1164.5 the entry read two frames
     * later, 14%. Two readings that agree are a clock that is not being animated; one that is
     * never agrees with itself.
     */
    private static float sAodPendTop = Float.NaN, sAodPendUnit = Float.NaN;
    /** How far two consecutive doze readings may differ and still count as a clock at rest. */
    private static final float AOD_STEADY_PX = 0.5f;

    /** The doze's scale as last read, split into the clock's own and the whole chain's. */
    private static float sAodOwnScale = Float.NaN, sAodChainScale = Float.NaN;

    /** The OEM's box on the previous frame of a fall, to see it come to rest. */
    private static float sDozeSettleUnit = Float.NaN;
    /** The destination the last transition frame aimed at, for the fall trace. */
    private static float sLastToUnit = Float.NaN;
    /** The OEM's layout numbers as they were when the doze pose was committed. See layoutNow. */
    private static String sAodLayout = "none";

    private static boolean steady(float now, float before) {
        return !Float.isNaN(now) && !Float.isNaN(before) && Math.abs(now - before) <= AOD_STEADY_PX;
    }

    /** Nothing recorded yet this doze: the next two frames that agree start it over. */
    private static void clearAodPend() {
        sAodPendTop = Float.NaN;
        sAodPendUnit = Float.NaN;
    }
    /** Card progress (0 = OEM's look, 1 = cover look) at the two ends. */
    private static float sCardFrom, sCardTo;

    /** How far into the entry the card's material waits before it sets off (see frame()). */
    private static final float CARD_LAG = 0.3f;
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

    /** What the last transition cost, for `op aodprobe`. See land(). */
    private static String sLastFlight = "none";

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
     * of ours is on the clock.
     *
     * A held doze is the exception, and the reason this is a policy rather than a reading: the
     * pose there is re-applied every doze frame by writePose(), so sInkBottom is as fresh as it
     * is on the lock screen. What used to keep the lyrics out of the AOD - "the AOD's full clock
     * is the OEM's, and nothing may sit under it" - is exactly what the setting turns off.
     */
    public static float inkBottomOnScreen() {
        Phase p = sPhase;
        if (p == Phase.AOD) return sAodHeld ? sInkBottom : Float.NaN;
        return p == Phase.OFF ? Float.NaN : sInkBottom;
    }

    /**
     * The bottom of everything the clock brings with it, for whoever has to fit underneath.
     *
     * The signature bar hangs the clock, so on a style that has one the lock lyrics have to start
     * below it rather than be drawn through it. The bar keeps its own height - it is carried, not
     * scaled - so what sits under the clock is the ink bottom plus the OEM's gap plus the bar.
     *
     * NaN wherever the ink bottom is: nothing measured, nothing to sit under.
     */
    public static float contentBottomOnScreen() {
        float ink = inkBottomOnScreen();
        if (Float.isNaN(ink)) return Float.NaN;
        float bottom = ink;
        Live m = LIVE;
        for (int i = 0; i < m.sigN; i++) {
            Sig s = m.sig[i];
            if (s.v == null) continue;
            float b = ink + s.gap + s.v.getHeight();
            if (b > bottom) bottom = b;
        }
        float cb = sCompBottom;
        if (!Float.isNaN(cb) && cb > bottom) bottom = cb;
        return bottom;
    }

    /**
     * contentBottomOnScreen(), counting only the signature bars that are showing: one a style
     * carries but has hidden still has its height, and taken whole it put the bottom some 150px
     * below a small clock with nothing under it. For laying out against the clock as it looks.
     */
    public static float contentBottomShown() {
        float ink = inkBottomOnScreen();
        if (Float.isNaN(ink)) return Float.NaN;
        float bottom = ink;
        Live m = LIVE;
        for (int i = 0; i < m.sigN; i++) {
            Sig s = m.sig[i];
            if (s.v == null || !s.v.isShown() || s.v.getAlpha() <= 0.01f) continue;
            float b = ink + s.gap + s.v.getHeight();
            if (b > bottom) bottom = b;
        }
        float cb = sCompBottom;
        if (!Float.isNaN(cb) && cb > bottom) bottom = cb;
        return bottom;
    }

    /**
     * contentBottomOnScreen(), but where it is actually drawn right now: the ink box and the
     * signature bars mapped through every view's own transform up to the window.
     *
     * The pose is in unzoomed pixels, and outside the OEM's zooms those are the screen's. Inside
     * a doze or a swipe the zooms move the clock off its pose - which is what whoever has to sit
     * against the clock needs to know. See also contentBottomFor.
     */
    static float contentBottomDrawn() {
        View g = firstTarget();
        RectF box = ClockViews.glyphBox(true);
        if (g == null || box == null || box.height() <= 0f) return Float.NaN;
        float bottom = drawnY(g, box.centerX(), box.bottom);
        Live m = LIVE;
        for (int i = 0; i < m.sigN; i++) {
            Sig s = m.sig[i];
            if (s.v == null || !s.v.isShown()) continue;
            float b = drawnY(s.v, s.v.getWidth() / 2f, s.v.getHeight());
            if (b > bottom) bottom = b;
        }
        for (int i = 0; i < m.compN; i++) {
            Comp c = m.comp[i];
            if (c.v == null || !c.v.isShown()) continue;
            float b = drawnY(c.v, c.v.getWidth() / 2f, c.v.getHeight());
            if (b > bottom) bottom = b;
        }
        return bottom;
    }

    /**
     * contentBottomOnScreen(), as it is drawn, taken into v's own unzoomed frame: v's screen
     * origin plus the drawn distance over the scale v is drawn at. Subtract v's screen origin and
     * it is in v's coordinates.
     *
     * For whoever lays out against the clock from outside its tree. The pose is in unzoomed
     * pixels (parentTop), and the zooms on the clock's side are not all on theirs - a swipe
     * scales the clock's container but not keyguard_background_layer or the foreground layer -
     * so only the drawn clock is one they can sit against. Mapped from the pose through the
     * clock's ANCESTORS, which are the OEM's and already this frame's, rather than read off the
     * clock's own transform, which this frame's pre-draw may not have written yet.
     */
    public static float contentBottomFor(View v) {
        float pose = contentBottomOnScreen();
        if (Float.isNaN(pose)) return Float.NaN;
        View g = firstTarget();
        if (g == null || !(g.getParent() instanceof View)) return pose;
        View parent = (View) g.getParent();
        float drawn = drawnY(parent, 0f, 0f) + chainScaleY(parent) * (pose - parentTop(g));
        return unzoomY(v, drawn);
    }

    /** A drawn screen y taken into v's unzoomed frame; see contentBottomFor. */
    public static float unzoomY(View v, float screenY) {
        if (v == null || Float.isNaN(screenY)) return screenY;
        float k = chainScaleY(v);
        if (k <= 0f) return screenY;
        v.getLocationOnScreen(LOC);
        return LOC[1] + (screenY - LOC[1]) / k;
    }

    /** Where a point in a view's own coordinates is drawn on screen, every transform included. */
    private static float drawnY(View v, float x, float y) {
        float[] pt = {x, y};
        View cur = v;
        while (true) {
            android.graphics.Matrix mx = cur.getMatrix();
            if (!mx.isIdentity()) mx.mapPoints(pt);
            pt[0] += cur.getLeft();
            pt[1] += cur.getTop();
            if (!(cur.getParent() instanceof View)) break;
            View p = (View) cur.getParent();
            pt[0] -= p.getScrollX();
            pt[1] -= p.getScrollY();
            cur = p;
        }
        // The root's own place is the window's.
        cur.getLocationOnScreen(LOC);
        return pt[1] + LOC[1];
    }

    public static Phase phase() {
        return sPhase;
    }

    /** Whether this doze is keeping the cover's clock - for AodColour.holdAodColour(). */
    public static boolean aodHeld() {
        return sAodHeld;
    }

    /** Whether this doze is the full-screen one - for AodColour.holdAodColour(). */
    static boolean aodFullScreen() {
        return sAodFullScreen;
    }

    /**
     * The glyphs redrawn when the scale has moved far enough that their glass edge would show it.
     *
     * The edge's width reaches the shader from TimeView.onDraw, scaled there to what the glyph is
     * drawn at (ClockGlass.armMiGlassGuard, #39) - but a scale set on the time_group is a RenderNode
     * property and redraws nothing under it, so a clock that settled without one more draw kept
     * the edge it last drew with. A few percent of scale is not visible in the edge; per frame it
     * would be three glyph paths rebuilt for nothing.
     */
    private static void glassFollowsScale(View g, float scale) {
        Float last = sGlassScale.get(g);
        float was = last == null ? 1f : last;
        if (Math.abs(scale - was) <= 0.03f * Math.max(scale, was)) return;
        sGlassScale.put(g, scale);
        ClockGlass.invalidateGlass(g);
    }

    /** The scale each time_group's glyphs last redrew their glass at. A tag key would be this
     * module's resource id inside SystemUI's tree, where it could be one of SystemUI's own. */
    private static final java.util.Map<View, Float> sGlassScale = new java.util.WeakHashMap<>();

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
        enter(animate, wake, "?");
    }

    /** @param src which route took this entry, for the log - see noteEntry. */
    static void enter(boolean animate, boolean wake, String src) {
        if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) return;
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
            noteEntry(src, wake, false, false, Float.NaN, Float.NaN, Float.NaN, Float.NaN, LIVE.unit);
            stopFrame();
            sPhase = Phase.ON;
            sExitToAod = false;
            sT = 1f;
            sTv = 0f;
            hold(targetY(floor, natural));
            MediaCard.setCardProgressFrom(1f);
            sGlassP = 1f;
            invalidate();
            Xp.log(TAG + "clock: cover look on (floor y=" + Main.r1(floor) + ")");
            return;
        }
        // The pose the doze settled at, which is only ever written while the doze's clock is
        // standing still - see sAodPendTop.
        boolean fromAod = was == Phase.AOD && !Float.isNaN(sAodTop);
        if (fromAod) {
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
        // Where the face stands across: a doze that held the cover's pose was showing it centred
        // already, and a re-entry mid-flight starts from wherever the last one left it. Starting
        // every entry from the OEM's own x put the clock back at the side for one frame and flew
        // it to the middle on every wake.
        sCenterFrom = fromAod ? (sAodHeld ? 1f : 0f) : was == Phase.OFF ? 0f : sCenterK;
        sCenterTo = 1f;
        sCardFrom = wake ? 1f : MediaCard.cardProgress();
        sCardTo = 1f;
        sGlassFrom = sGlassP;
        sGlassTo = 1f;
        sYFrom = currentY(natural);
        sYTo = targetY(floor, natural);
        sExitToAod = false;
        // A wake's glyphs already settle after the size, on the OEM's own animation; a toggle's
        // are given the same tail. See TOGGLE_GLYPH_RESPONSE.
        start(Phase.ENTER, wake ? Float.NaN : TOGGLE_GLYPH_RESPONSE);
        // The OEM's own ink box as last measured - the reading that says whether its wake
        // animation had already begun when this entry was taken. LIVE, never a measurement of
        // its own: the entry that matters is taken from inside the pre-draw, where LIVE is this
        // frame's already, and a second glyphBox(true) there walks both clock trees again on the
        // one frame of the wake that has the least room for it (reported as a stutter in the
        // first frames, 2026-09-22). On the other routes it is a frame or two old, which is all
        // this field was ever read for. See noteEntry.
        noteEntry(src, wake, animate, fromAod, sYFrom, sYTo, sFromTop, sFromUnit, LIVE.unit);
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
        sCenterFrom = sCenterK;
        sCenterTo = 0f;
        sCardFrom = MediaCard.cardProgress();
        sCardTo = 0f;
        sGlassFrom = sGlassP;
        sGlassTo = 0f;
        sYFrom = currentY(natural);
        sYTo = exitY(natural);
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
        // The doze about to start has drawn nothing yet, so it has no pose to be steady against.
        clearAodPend();
        // The start pose is read BEFORE the OEM's y goes back: the y changes the OEM's box at
        // once, and the transforms on the views still describe the old one - reading after would
        // start the spring from a clock several times the size of the one on screen.
        boolean flying = (sPhase == Phase.ENTER || sPhase == Phase.ON) && Main.sContainer != null;
        boolean fromDrawn = false;
        if (flying) {
            // What our own last pre-draw put on the clock IS the pose on screen; measuring it
            // again here can come back empty when the keyguard is not drawing (unlocked, being
            // re-shown) - it did, and the doze showed the OEM's full clock. See sDrawnTop.
            // The measurement stays as the fallback for a process that has not placed a frame.
            if (!Float.isNaN(sDrawnTop)) {
                sFromTop = sDrawnTop;
                sFromUnit = sDrawnUnit;
                sFromDate = sDrawnDate;
                fromDrawn = true;
            } else {
                captureFrom();
            }
            sAodPoseWarned = false;
        }
        Float held = Main.sHoldY;
        // Kept for a held doze to put back on, one frame later - see sAodHoldY. ONLY when there
        // is one: the screen falling asleep calls this twice (KeyguardService's sleep hook, then
        // doAnimationToAod), the first call is the one that finds the hold and lets go of it, and
        // the second would otherwise write NaN over the lock screen's y - which is the single
        // value a held doze cannot be without. Measured: heldY=NaN and a doze clock drawn at
        // 1168px scaled to 0.149, against the lock screen's 337px at 0.518.
        if (held != null) sAodHoldY = held;
        float natural = naturalY();
        if (held != null) {
            Main.sHoldY = null;
            if (!Float.isNaN(natural)) Main.applyY(natural);
        }
        // Which of the two dozes this is, asked once per sleep and asked HERE for a reason: it
        // reflects into the interfaces manager, and the two things above it are the ones the
        // hand-over is timed against - the pose has to be read before the y goes back, and the y
        // before the OEM's doAnimationToAod reads it. Every path below returns through this, so
        // one latch covers them all. See sAodFullScreen.
        sAodFullScreen = Main.fullAodOn();
        switch (sPhase) {
            case AOD:
                // Already asleep. sAodHeld stands as it was latched.
                return;
            case EXIT:
                if (sExitToAod) return;
                // A plain exit that was already flying: keep flying, but land in the AOD.
                sAodHeld = false;
                sExitToAod = true;
                sYFrom = sYTo = Float.NaN;
                return;
            case OFF:
                if (!Main.clockHeld()) return;
                // Cover mode without our clock - unlocked, say. Nothing to walk back; watch the
                // AOD so the wake has a start.
                sAodHeld = false;
                sPhase = Phase.AOD;
                sAodTop = Float.NaN;
                install();
                Xp.log(TAG + "clock: watching the AOD");
                return;
            default:
                break;
        }
        if (!flying) {
            sAodHeld = false;
            releaseNow("to AOD with no container");
            return;
        }
        if (keepInAod()) {
            // The full-screen AOD keeps the cover's clock, so there is nothing to travel to:
            // sFromTop/sFromUnit/sFromDate, captured above, ARE the pose on screen and the pose
            // this doze will keep. The transforms stay where they are - that they survive the
            // hand-over is the whole of the setting - and frame() re-applies them off the box
            // the doze draws, which is not the lock screen's.
            //
            // The OEM's y has been put back above and stays back: doAnimationToAod reads the
            // clock's translation and posts it to the AOD plugin, and the clock is held by our
            // own transforms on time_group, which the OEM does not read.
            sAodHeld = true;
            // The colour too: a held doze is showing the lock screen's clock, so it holds the
            // lock screen's glass instead of the doze's neutral. Read here because this is the
            // last moment it is still the lock screen's - the doze inks its own over it.
            ClockGlass.captureAodGlass();
            stopFrame();
            sT = 1f;
            sTv = 0f;
            sExitToAod = false;
            sPhase = Phase.AOD;
            sGlassFrom = sGlassTo = sGlassP;
            install();
            Xp.log(TAG + "clock: into the AOD holding the cover pose (top="
                    + Main.r1(sFromTop) + " unit=" + Main.r1(sFromUnit)
                    + " date=" + Main.r1(sFromDate)
                    + " from=" + (fromDrawn ? "drawn" : "measured") + ")");
            return;
        }
        sAodHeld = false;
        sDozeSettleUnit = Float.NaN;
        sCenterFrom = sCenterK;
        sCenterTo = 0f;
        sCardFrom = MediaCard.cardProgress();
        sCardTo = sCardFrom;
        sGlassFrom = sGlassP;
        sGlassTo = 0f;
        sYFrom = sYTo = Float.NaN;
        sExitToAod = true;
        // sAodTop/sAodUnit/sAodDate are deliberately NOT cleared: they hold the pose the last
        // settled doze was drawn at, and this transition aims at it. It cannot aim at the OEM's
        // live clock - the y has just been put back in one step, so for the first frames that is
        // still the lock screen's clock, several hundred pixels taller than the doze. Measured
        // on houji, 2026-09-19: the clock grew to 506px against a 404px doze and then came back
        // down over 0.6s, which reads as the zoom running backwards.
        start(Phase.EXIT);
        Xp.log(TAG + "clock: into the AOD");
        final Choreographer.FrameCallback flight = sFrame;
        View c = Main.sContainer;
        Gen.postDelayedOn(c, new Runnable() {
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

    /**
     * The last few entries into the cover look, as `op entries` prints them.
     *
     * There are four routes into a wake - the pre-draw, the KeyguardService post, the
     * doAnimationToAod hook and the screen-on broadcast - and which one takes it, and what the
     * OEM's own animation was doing at that moment, cannot be seen from the screen: the two come
     * out at the same pose and differ only in the middle of the walk. Kept as text rather than
     * numbers because the question is "which of these was different", and the answer is read once.
     */
    private static final String[] sEntryLog = new String[8];
    private static int sEntryN;

    private static void noteEntry(String src, boolean wake, boolean animate, boolean fromAod,
                                  float y0, float yTo, float top0, float unit0, float oemUnit) {
        int n = ++sEntryN;
        sEntryLog[(n - 1) % sEntryLog.length] = "#" + n
                + "@" + (android.os.SystemClock.uptimeMillis() / 100) / 10f + "s"
                + " src=" + src + (wake ? "/wake" : "/toggle") + (animate ? "/anim" : "/cut")
                + " start=" + (fromAod ? "aod" : "live")
                + " tail=" + sGlyphTail
                + " y=" + Main.r1(y0) + "->" + Main.r1(yTo)
                + " top=" + Main.r1(top0) + " unit=" + Main.r1(unit0)
                + " oemUnit=" + Main.r1(oemUnit)
                + " aodUnit=" + Main.r1(sAodUnit);
    }

    /**
     * Every pose the SETTLED clock has been placed at, with what decided it.
     *
     * A transition ends on a spring landing, but the pose it lands on is re-derived on every
     * frame after it, and nothing on screen says which of those inputs moved when the clock is
     * seen to shift a moment after it stopped (reported 2026-09-22 for a wake: "停完过一会儿又
     * 挪一次"). Each line is a pose that differs from the one before it by more than half a
     * pixel, so a clock that is standing still costs three compares a frame and says nothing.
     *
     * The first line after a landing is the landing itself: land() forgets the last pose, so the
     * first settled frame always records.
     */
    private static final String[] sPoseLog = new String[8];
    private static int sPoseN;
    private static float sPoseTop = Float.NaN, sPoseUnit = Float.NaN, sPoseDate = Float.NaN;

    /** NaN counts as equal to NaN: a style with no date must not record a pose every frame. */
    private static boolean samePose(float a, float b) {
        if (Float.isNaN(a) && Float.isNaN(b)) return true;
        return Math.abs(a - b) <= 0.5f;
    }

    private static void notePose(Live m, float top, float unit, float date, float full,
                                 float room) {
        if (samePose(top, sPoseTop) && samePose(unit, sPoseUnit) && samePose(date, sPoseDate)) {
            return;
        }
        sPoseTop = top;
        sPoseUnit = unit;
        sPoseDate = date;
        int n = ++sPoseN;
        sPoseLog[(n - 1) % sPoseLog.length] = "#" + n
                + "@" + (android.os.SystemClock.uptimeMillis() / 100) / 10f + "s"
                + " top=" + Main.r1(top) + " unit=" + Main.r1(unit) + " date=" + Main.r1(date)
                // What the pose is derived from, so the line that moved names itself: the date
                // line and the date's own height place an anchored clock, the full unit sizes
                // it, and the room below is the only thing that can push it back up.
                + " | line=" + Main.r1(coverDateY(m.date)) + " dateH=" + Main.r1(m.dateH)
                + " full=" + Main.r1(full) + " room=" + Main.r1(room)
                + " anchored=" + m.anchored + " dateView=" + (m.date == null ? "none"
                        : Integer.toHexString(System.identityHashCode(m.date)))
                + " | oem top=" + Main.r1(m.inkTop) + " unit=" + Main.r1(m.unit)
                + " dateTop=" + Main.r1(m.dateTop)
                + " y=" + Main.sHoldY + " natural=" + Main.r1(naturalY())
                + " floor=" + Main.r1(sFloor);
    }

    /** The settled-pose log, oldest first. Read by `op poses`; see notePose. */
    static String poses() {
        StringBuilder sb = new StringBuilder("poses=" + sPoseN);
        int keep = Math.min(sPoseN, sPoseLog.length);
        for (int i = 0; i < keep; i++) {
            String s = sPoseLog[(sPoseN - keep + i) % sPoseLog.length];
            if (s != null) sb.append(" || ").append(s);
        }
        return sb.toString();
    }

    /**
     * The fall into the AOD, frame by frame: what the OEM's own clock was doing underneath and
     * where we were aiming while it did.
     *
     * The destination of this one transition is a pose REMEMBERED from the last doze, because
     * the OEM's live clock at the moment the fall starts is still the lock screen's. How long
     * it stays the lock screen's, and what it does on its way to the doze, is the whole
     * question - it decides whether a live aim is usable and from which frame - and it cannot
     * be seen: the two poses differ by a few percent and the fall is over in twenty frames.
     *
     * Four numbers a frame for one transition, overwritten by the next. Read by `op fall`.
     */
    private static final int FALL_MAX = 96;
    private static final float[] FALL_MS = new float[FALL_MAX];
    private static final float[] FALL_OEM = new float[FALL_MAX];
    private static final float[] FALL_TO = new float[FALL_MAX];
    private static final float[] FALL_AT = new float[FALL_MAX];
    private static int sFallN;
    private static long sFallT0;
    private static String sFallHow = "none";

    private static final float[] FALL_T = new float[FALL_MAX];
    private static final float[] FALL_AB = new float[FALL_MAX];

    private static void noteFall(float oemUnit, float toUnit, float atUnit, float above) {
        if (sFallN >= FALL_MAX) return;
        int i = sFallN++;
        FALL_MS[i] = android.os.SystemClock.uptimeMillis() - sFallT0;
        FALL_OEM[i] = oemUnit;
        FALL_TO[i] = toUnit;
        FALL_AT[i] = atUnit;
        FALL_T[i] = sT;
        FALL_AB[i] = above;
    }

    /** How long after a transition starts the trace keeps recording, landing included. */
    private static final long FLIGHT_TAIL_MS = 1500L;

    /**
     * The OEM's own layout numbers, as one string.
     *
     * maxSpace is the one that moves when the notification stack does, which is what a fall has
     * to know and what the y at that moment turned out not to carry (measured 1682.9 on two
     * falls either side of a dismissed notification). Read at the fall and again when a doze
     * pose is committed, it says whether the two describe the same lock screen.
     */
    private static String layoutNow() {
        View c = Main.sContainer;
        try {
            Object it = c == null ? null : Xp.getObjectField(c, "keyguardClockNotifInteractor");
            if (it == null) return "none";
            return "min=" + Main.r1(num(Xp.getObjectField(it, "timeMinHeight")))
                    + " max=" + Main.r1(num(Xp.getObjectField(it, "adaptTimeHeight")))
                    + " space=" + Main.r1(num(Xp.getObjectField(it, "maxSpace")));
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    /** The last fall into the AOD, frame by frame. See noteFall. */
    static String fall() {
        StringBuilder sb = new StringBuilder("flight " + sFallHow + " frames=" + sFallN
                + " aodUnit=" + Main.r1(sAodUnit)
                + " | layout at the doze: " + sAodLayout
                + " | layout now: " + layoutNow());
        for (int i = 0; i < sFallN; i++) {
            sb.append(" | ").append(Math.round(FALL_MS[i])).append("ms t=")
              .append(Main.r2(FALL_T[i])).append(" oem=")
              .append(Main.r1(FALL_OEM[i])).append(" to=").append(Main.r1(FALL_TO[i]))
              .append(" at=").append(Main.r1(FALL_AT[i]))
              .append(" ab=").append(Main.r3(FALL_AB[i]));
        }
        return sb.toString();
    }

    /** The entry log, oldest first. Read by `op entries`; see noteEntry. */
    static String entries() {
        StringBuilder sb = new StringBuilder("entries=" + sEntryN);
        int keep = Math.min(sEntryN, sEntryLog.length);
        for (int i = 0; i < keep; i++) {
            String s = sEntryLog[(sEntryN - keep + i) % sEntryLog.length];
            if (s != null) sb.append(" | ").append(s);
        }
        return sb.toString();
    }

    static String describe() {
        return "phase=" + sPhase + (sExitToAod ? "(to AOD)" : "") + (sAodHeld ? "(held)" : "")
                + " t=" + Main.r3(sT)
                + " waking=" + sWaking + " floor=" + Main.r1(sFloor)
                + " y " + Main.r1(sYFrom) + "->" + Main.r1(sYTo)
                + " card " + Main.r2(sCardFrom) + "->" + Main.r2(sCardTo)
                + " glass " + Main.r2(sGlassFrom) + "->" + Main.r2(sGlassTo)
                + " heldY=" + Main.r1(sAodHoldY)
                + " from top=" + Main.r1(sFromTop) + " unit=" + Main.r1(sFromUnit)
                + " date=" + Main.r1(sFromDate)
                + " aod top=" + Main.r1(sAodTop) + " unit=" + Main.r1(sAodUnit)
                + " scale own=" + Main.r3(sAodOwnScale) + " chain=" + Main.r3(sAodChainScale)
                + " | last flight: " + sLastFlight;
    }

    // ------------------------------------------------------------------ the OEM's y

    /** What the OEM last asked for on its own. */
    private static float naturalY() {
        return Main.sLastSystemY;
    }

    /**
     * Where the clock goes out of the cover: the OEM's y, given the room the rows really leave
     * (ClockRoom.roomForRows). The OEM's own figure keeps room for one big notification whatever is
     * there, so the exit landed on a squeezed clock and it grew again once let go - out of the
     * music card into a focus notification, the clock shrank and came back (2026-09-26).
     */
    private static float exitY(float natural) {
        // At once: the exit eases toward it on its own (0.25 of the way a frame).
        if (Float.isNaN(natural)) return natural;
        float y = ClockRoom.roomForRowsNow(natural);
        // "No notifications" is MAX_VALUE, which is not a y to walk to: aim at the clear boundary
        // the walk can reach, and the hand-back's next emission gives the OEM the real thing.
        if (y >= Float.MAX_VALUE / 2f) {
            float clear = ClockRoom.unobstructedClockY();
            return Float.isNaN(clear) ? natural : clear;
        }
        return y;
    }

    /** The share of the way to a moved exit end taken each frame. */
    private static final float EXIT_FOLLOW = 0.25f;

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
        // The reference the other styles are sized against, by the same largest-seen rule.
        if (LIVE.anchored && (!style.equals(sRefStyle) || !(unit < sRefNatural + 0.5f))) {
            sRefStyle = style;
            sRefNatural = unit;
            ModuleState.saveState();
        }
        // The largest seen: an entry can catch the OEM's clock still giving way to a notification,
        // and that is a smaller clock, never a larger one.
        if (style.equals(sFullStyle) && unit < sFullNatural + 0.5f) return;
        Xp.log(TAG + "clock: full unit measured " + Main.r1(unit) + " for " + style
                + " (was " + Main.r1(sFullNatural) + " for " + sFullStyle + ")");
        sFullStyle = style;
        sFullNatural = unit;
        ModuleState.saveState();
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

    /**
     * The full clock of the anchored styles (all_in_one), which the size setting is read against
     * on every OTHER style.
     *
     * A fraction of each style's own full clock is a different clock on each: magazine C's whole
     * clock is 339px where all_in_one's is ~750, so the same 9% came out at 30px - a clock too
     * small to read - and the oriental styles were smaller still. The setting is "how big the
     * small clock is", and that should not change with the face. Kept apart from sFullNatural,
     * which is per style and is overwritten the moment another style is entered.
     */
    private static volatile float sRefNatural = Float.NaN;
    private static volatile String sRefStyle;

    /** Until all_in_one has been measured once: its full clock as a share of the screen height
     * (~750px of 2608 on the phone this was measured on). */
    private static final float REF_SCREEN_SHARE = 0.287f;

    static float refUnitState() {
        return sRefNatural;
    }

    static void restoreRefUnit(float v) {
        if (v > 0f) sRefNatural = v;
    }

    private static float refUnit() {
        if (sRefNatural > 0f) return sRefNatural;
        View c = Main.sContainer;
        return c == null ? Float.NaN
                : REF_SCREEN_SHARE * c.getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * The unit the size setting is a fraction of. The style's own full clock on the anchored
     * styles; the anchored styles' on every other, so the small clock comes out the same size
     * whatever the face - never larger than the style's own full clock, though.
     */
    private static float sizeBase(Live m, float full) {
        if (m.anchored) return full;
        float ref = refUnit();
        return ref > 0f ? ref : full;
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
        sFallN = 0;
        sFallT0 = android.os.SystemClock.uptimeMillis();
        sFallHow = p + (sExitToAod ? "(to AOD)" : "") + " y=" + Main.r1(naturalY())
                + " | layout: " + layoutNow();
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
            public void doFrame(long now) { android.os.Trace.beginSection("MC clockFrame"); try {
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
                // The fall into the AOD is the one transition whose destination is a remembered
                // pose rather than the OEM's live clock (see the EXIT maths), so the spring
                // finishing is not the same as the clock being where the hand-over needs it:
                // landing now would clear our transforms off a clock still bigger than the one we
                // drew. Wait until the OEM's own clock has come down to the doze. LAND_ANYWAY_MS
                // in toAod() lands a doze that never settles.
                if (done && sExitToAod && !Float.isNaN(LIVE.unit)) {
                    // The spring finishing is not the same as the clock being where the
                    // hand-over needs it: the destination is the OEM's own clock and it is
                    // still walking to the doze. Wait for that to come to rest - two readings
                    // that agree - rather than for it to reach a figure, which is what a
                    // remembered figure could not deliver once it went stale.
                    boolean rest = !Float.isNaN(sDozeSettleUnit)
                            && Math.abs(LIVE.unit - sDozeSettleUnit) <= DOZE_SETTLE_PX;
                    sDozeSettleUnit = LIVE.unit;
                    done = rest;
                }
                if (sPerfLastAt != 0L) sPerfGapMax = Math.max(sPerfGapMax, now - sPerfLastAt);
                sPerfLastAt = now;
                sPerfN++;
                // Out of the cover, the rows settle under the clock while it goes: the card
                // behind the pill goes out of sight and the row let out drops to the stack's
                // bottom. The end is followed there, eased so a row landing is not a jump.
                if (sPhase == Phase.EXIT && !sExitToAod && !done && !Float.isNaN(sYTo)) {
                    float want = exitY(naturalY());
                    if (!Float.isNaN(want)) sYTo += (want - sYTo) * EXIT_FOLLOW;
                }
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
                    Gen.frame(this);
                    return;
                }
                sFrame = null;
                land();
            } finally { android.os.Trace.endSection(); } }
        };
        Gen.frame(sFrame);
        invalidate();
    }

    private static void stopFrame() {
        Choreographer.FrameCallback f = sFrame;
        sFrame = null;
        if (f != null) Gen.unframe(f);
    }

    private static void land() {
        // Kept, not only logged: the module's log is dropped by the log daemon on some builds
        // (this phone keeps nothing below error level), and the frame gap is the one number
        // that says whether a transition stuttered. Read back by `op aodprobe`.
        sLastFlight = sPhase + (sExitToAod ? "(to AOD)" : "") + " " + perfLine();
        Xp.log(TAG + "clock: " + sPhase + (sExitToAod ? "(to AOD)" : "") + " landed, " + perfLine());
        if (sPhase == Phase.ENTER) {
            sPhase = Phase.ON;
            sPoseTop = Float.NaN;
            Xp.log(TAG + "clock: cover look settled");
        } else if (sPhase == Phase.EXIT) {
            if (sExitToAod) {
                // The OEM's pose is on screen now; nothing of ours is left on the views, but the
                // listener stays to remember what the AOD draws.
                sExitToAod = false;
                sPhase = Phase.AOD;
                clearAodPend();
                clearTransforms();
                ClockGlass.restoreGlass();
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
        sAodHeld = false;
        sAodFullScreen = false;
        sAodHoldY = Float.NaN;
        sT = 1f;
        sTv = 0f;
        sFromTop = Float.NaN;
        sAodTop = Float.NaN;
        // The pose an old session put on the clock is not this session's: a cover that comes
        // back must record its own before toAod() may hold it.
        sDrawnTop = sDrawnUnit = sDrawnDate = Float.NaN;
        sAodPoseWarned = false;
        clearAodPend();
        sGlassP = 0f;
        uninstall();
        clearTransforms();
        Float held = Main.sHoldY;
        Main.sHoldY = null;
        // Given the rows' room too: applyY is our own write, which the notifY hooks let past
        // roomForRows, and the raw figure squeezed the clock the exit had just landed full.
        float natural = exitY(naturalY());
        if (held != null && !Float.isNaN(natural)) Main.applyY(natural);
        // The cover no longer asks for the notification layout; the rows decide again.
        NotifPresence.recheck(true);
        ClockGlass.restoreGlass();
        // The unlock path lands here without an exit frame, so the wallpaper dim has to be
        // let go explicitly - it is a value in the wallpaper process, not a view that goes
        // away with the keyguard. Its own call: a failure here must not cost the release.
        try {
            KeepDim.apply(0f);
        } catch (Throwable t) {
            Xp.w(TAG + "keep-dim release failed: " + t);
        }
        Main.onClockReleased();
        if (was) Xp.log(TAG + "clock: released (" + why + ")");
    }

    /** The dates placeClock has moved since the clock was last given back. Weak: views go. */
    private static final java.util.Set<View> MOVED_DATES =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static void clearTransforms() {
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            if (g.getScaleX() != 1f) g.setScaleX(1f);
            if (g.getScaleY() != 1f) g.setScaleY(1f);
            // The scale is a RenderNode property: without a redraw the full-size clock kept the
            // narrow glass edge it last drew while collapsed, and sGlassScale still holding that
            // scale let the next collapse skip the redraw it needed.
            glassFollowsScale(g, 1f);
            if (g.getTranslationY() != 0f) g.setTranslationY(0f);
            if (g.getTranslationX() != 0f) g.setTranslationX(0f);
        }
        View date = ClockViews.sDateView;
        if (date != null && date.getTranslationY() != 0f) date.setTranslationY(0f);
        if (date != null && date.getTranslationX() != 0f) date.setTranslationX(0f);
        // Every date this moved, not only the one looked up now: the lookup can come back with
        // nothing by the end of the way out - 保留壁纸, its lyrics gone with the player - and
        // the date it had moved stayed 213px up over the big clock (2026-10-14).
        for (View d : MOVED_DATES) {
            if (d.getTranslationY() != 0f) d.setTranslationY(0f);
        }
        MOVED_DATES.clear();
        for (View v : ClockViews.signatureViews()) {
            if (v.getTranslationY() != 0f) v.setTranslationY(0f);
        }
        clearCompanions();
    }

    // ------------------------------------------------------------------ per frame

    private static void install() {
        View v = Main.sContainer;
        if (v == null || sGuarded == v) return;
        uninstall();
        try {
            Gen.preDraw(v, PRE_DRAW);
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
            Gen.unpreDraw(v, PRE_DRAW);
        } catch (Throwable ignored) {
        }
    }

    private static void invalidate() {
        View v = Main.sContainer;
        if (v != null) v.invalidate();
    }

    private static void prepareGlass() {
        ClockGlass.armGlassMorph();
    }

    private static final ViewTreeObserver.OnPreDrawListener PRE_DRAW =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() { android.os.Trace.beginSection("MC clockPreDraw"); try {
            long t0 = System.nanoTime();
            try {
                frame();
            } catch (Throwable t) {
                Xp.w(TAG + "clock: frame failed: " + t);
            }
            if (sFrame != null) {
                long d = System.nanoTime() - t0;
                sPerfPreNs += d;
                if (d > sPerfPreMax) sPerfPreMax = d;
            }
            return true;
        } finally { android.os.Trace.endSection(); } }
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
            Xp.w(TAG + "clock: re-place failed: " + t);
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
        Sig[] sig;
        int sigN;
        /** What else the clock face shows, on a style that is not anchored. See companions(). */
        Comp[] comp;
        int compN;
        /** Some companion shares the clock's row: the row moves as one scaled piece. */
        boolean row;
    }

    /**
     * One piece of a clock face that is not the time - a date, a weekday - on a style that is not
     * anchored, as the OEM laid it out this frame (screen pixels, its own transform excluded).
     */
    private static final class Comp {
        View v;
        float left, top, w, h;
        /** Its ink inside the view: top, bottom, horizontal middle, in the view's own pixels. */
        float inkT, inkB, inkCx;
        /** BESIDE the time on its row, or under or over it. */
        int kind;
        /** This frame's placement: the scale and the screen y of the ink top. */
        float s, y;
    }

    private static final int BESIDE = 0, BELOW = 1, ABOVE = 2;

    private static final Comp[] COMPS = {new Comp(), new Comp(), new Comp(), new Comp(),
            new Comp(), new Comp()};

    /** Every view a companion transform was written to, so a release can take them all back. */
    private static final java.util.WeakHashMap<View, Boolean> sCompTouched =
            new java.util.WeakHashMap<>();

    /**
     * ViewGroups whose clipping was turned off for a moved piece, with what it was: bit 0 for
     * clipChildren, bit 1 for clipToPadding.
     */
    private static final java.util.WeakHashMap<android.view.ViewGroup, Integer> sUnclipped =
            new java.util.WeakHashMap<>();

    /** The bottom of the lowest companion as placed, in screen pixels, or NaN. */
    private static volatile float sCompBottom = Float.NaN;

    /**
     * The signature bar under the clock, as the OEM laid it out this frame.
     *
     * `top` is the layout position, so it says nothing about where we last put the bar, and `gap`
     * is its distance below the clock's own ink - the OEM's own measure, read live rather than
     * latched, so it follows whatever the OEM is doing to its clock (a squeeze, a notification
     * arriving, a minute ticking over into a wider glyph) instead of going stale on it.
     */
    private static final class Sig {
        View v;
        float top;
        float gap;
    }

    /** Two clock trees, so at most two bars, and a carried date. Reused, never reallocated. */
    private static final Sig[] SIGS = {new Sig(), new Sig(), new Sig()};

    private static final Live LIVE = new Live();

    /** How far a not-anchored style is taken to the middle of the screen, 0..1. */
    private static float sCenterK = 1f;
    /** Where the transition running now takes sCenterK from and to. */
    private static float sCenterFrom = 0f, sCenterTo = 1f;

    /** parentTop's twin across: v's parent's origin x, our own transforms above it excluded. */
    private static float parentLeft(View v) {
        if (!(v.getParent() instanceof View)) return 0f;
        float x = 0f;
        View p = (View) v.getParent();
        while (p.getParent() instanceof View) {
            View pp = (View) p.getParent();
            x += p.getLeft() + p.getTranslationX() - pp.getScrollX();
            p = pp;
        }
        p.getLocationOnScreen(LOC);
        return x + LOC[0];
    }

    private static float screenMidX() {
        View c = Main.sContainer;
        return c == null ? Float.NaN : c.getResources().getDisplayMetrics().widthPixels / 2f;
    }

    /** The middle of what a date view inks, in its own x: the text's, not the view's box. */
    private static float inkMidX(View v) {
        if (v instanceof android.widget.TextView) {
            RectF r = ClockViews.textInkBox((android.widget.TextView) v);
            if (r != null) return r.centerX();
        }
        return v.getWidth() / 2f;
    }

    /**
     * Where v's parent's origin would be on screen with every scale above it at 1 - translations
     * kept. The pixels every pose is in.
     *
     * Scales are the OEM's zooms: 0.95 on keyguard_root_view through a doze, and a swipe up puts
     * 0.95 there and 0.943 on the clock's and the notifications' containers besides (`op
     * alphasweep`, 2026-09-24). A pose in these pixels is scaled by them exactly as the OEM's own
     * clock would be, about their own pivots. It used to be the parent's DRAWN origin plus
     * unzoomed offsets, which scales about the parent's top instead: fitted off a recording, the
     * media card went 0.91 about y=1044 and the clock 0.91 about y=111 - up 24px while
     * everything else came in - reported as 上滑时时钟的轨迹跟封面和媒体卡片不同, and in the
     * held doze as 时钟往上移而不是向内缩.
     */
    private static float parentTop(View v) {
        if (!(v.getParent() instanceof View)) return 0f;
        float y = 0f;
        View p = (View) v.getParent();
        while (p.getParent() instanceof View) {
            View pp = (View) p.getParent();
            y += p.getTop() + p.getTranslationY() - pp.getScrollY();
            p = pp;
        }
        p.getLocationOnScreen(LOC);
        return y + LOC[1];
    }

    /**
     * How much taller than its layout this view is being drawn: its own scale times every
     * ancestor's.
     *
     * An ink box is measured in the target's own coordinates, so it says nothing about a scale
     * anywhere above it. Nothing is PLACED through this - a pose is written in the lock screen's
     * own pixels and the keyguard's shared zoom is left in the ancestors, see writePose - but it
     * is what names that zoom for the probe and the flight trace.
     */
    private static float chainScaleY(View v) {
        float s = 1f;
        for (View p = v; p != null; ) {
            s *= p.getScaleY();
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return s;
    }

    /**
     * The same product over this view's ANCESTORS only - the keyguard's shared zoom, with the
     * clock's own transform left out.
     *
     * 0.95 through a doze and back to 1 across the wake, all of it on keyguard_root_view
     * (measured 2026-09-22). The clock rides it; this only reports it, as `ab=` in the flight
     * trace, which is how the ride was shown to converge with the rest of the screen.
     */
    private static float aboveScaleY(View v) {
        return v.getParent() instanceof View ? chainScaleY((View) v.getParent()) : 1f;
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
        RectF box = ClockViews.glyphBox(true);
        if (box == null || box.height() <= 0f) return false;
        m.box = box;
        m.unit = ClockViews.glyphUnit(box);
        m.inkTop = parentTop(g) + g.getTop() + box.top;
        m.anchored = ClockViews.anchoredStyle();
        View date = ClockViews.visibleDate();
        Main.noteDateView(date);
        m.date = date;
        if (date != null) {
            m.dateTop = parentTop(date) + date.getTop();
            m.dateH = date.getHeight();
        } else {
            m.dateTop = Float.NaN;
            m.dateH = 0f;
        }
        companions(m, g);
        // The signature bar, on the styles that have one. Its distance below the clock's ink is
        // read off where the OEM put it, so it is the OEM's own measure whatever we have done to
        // the clock in the meantime. An empty bar is not carried: it is invisible, and the lock
        // lyrics must not be pushed down to make room for it.
        m.sig = SIGS;
        m.sigN = 0;
        for (Sig s : SIGS) s.v = null;
        for (View v : ClockViews.signatureViews()) {
            if (m.sigN >= SIGS.length) break;
            if (!ClockViews.signatureShows(v)) continue;
            Sig s = SIGS[m.sigN++];
            s.v = v;
            s.top = parentTop(v) + v.getTop();
            s.gap = s.top - (m.inkTop + box.height());
        }
        return !(m.anchored && date == null);
    }

    /**
     * The rest of the clock face on a style that is not anchored: every visible leaf of the clock
     * trees outside the time itself - its date, its weekday - and where each sits against the time.
     *
     * Not just the date. Magazine B draws "16:46  10/14  WED" as three TextViews on one row, and
     * moving the time and the date left the weekday standing on its own at the right of the
     * screen. ColorOS treats the face the same way: its magazine clock's immersed scene moves the
     * time zone, the local time and the date together.
     *
     * Read off the OEM's layout every frame, so a style that swaps its layout under us (magazine
     * C moves its date from under the digits to beside them as the media card changes) is simply
     * read again. Pieces the OEM has faded out - magazine C's big weekday and date, alpha 0 in its
     * notification layout - are left alone.
     */
    private static void companions(Live m, View g) {
        m.comp = COMPS;
        m.compN = 0;
        m.row = false;
        for (Comp c : COMPS) c.v = null;
        if (m.anchored) return;
        float inkT = m.inkTop, inkB = m.inkTop + m.box.height();
        for (View root : Main.clockRoots()) collectComps(m, root, g, inkT, inkB);
        for (int i = 0; i < m.compN; i++) if (COMPS[i].kind == BESIDE) m.row = true;
    }

    private static void collectComps(Live m, View v, View target, float inkT, float inkB) {
        if (m.compN >= COMPS.length || v == null || v == target) return;
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.01f) return;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            // The target's own ancestors are walked through; nothing inside the target is a
            // companion, it is the time.
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectComps(m, vg.getChildAt(i), target, inkT, inkB);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
        boolean draws = v instanceof android.widget.TextView
                ? ((android.widget.TextView) v).length() > 0
                : v instanceof android.widget.ImageView
                        && ((android.widget.ImageView) v).getDrawable() != null;
        if (!draws) return;
        Comp c = COMPS[m.compN++];
        c.v = v;
        c.left = parentLeft(v) + v.getLeft();
        c.top = parentTop(v) + v.getTop();
        c.w = v.getWidth();
        c.h = v.getHeight();
        // Its ink, for a text: a 117px line box around 80px digits would otherwise read as
        // reaching above and below the time on the same row.
        c.inkT = 0f;
        c.inkB = c.h;
        c.inkCx = c.w / 2f;
        if (v instanceof android.widget.TextView) {
            RectF r = ClockViews.textInkBox((android.widget.TextView) v);
            if (r != null) {
                c.inkT = r.top;
                c.inkB = r.bottom;
                c.inkCx = r.centerX();
            }
        }
        float t = c.top + c.inkT, b = c.top + c.inkB;
        // On the row when its middle is in the middle half of the time's ink. Not "overlaps it
        // by half": the rhombus face sets its date line so close over its digits that the two
        // boxes overlap that much, and it read as one row.
        float mid = (t + b) / 2f, quarter = (inkB - inkT) / 4f;
        if (mid >= inkT + quarter && mid <= inkB - quarter) c.kind = BESIDE;
        else c.kind = mid >= (inkT + inkB) / 2f ? BELOW : ABOVE;
    }

    /**
     * Turns off clipping between a moved piece and its clock tree, remembering what it was.
     *
     * Both kinds. Magazine B's row moved up to the date line came out with the top half of every
     * glyph missing, along one straight line, with clipChildren already off everywhere above it:
     * a ViewGroup also clips to its PADDING, on its own flag, and the clock roots keep a top
     * padding to hold the face below the status bar.
     */
    private static void unclip(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof android.view.ViewGroup;
             p = p.getParent()) {
            android.view.ViewGroup vg = (android.view.ViewGroup) p;
            if (!sUnclipped.containsKey(vg)) {
                sUnclipped.put(vg, (vg.getClipChildren() ? 1 : 0) | (vg.getClipToPadding() ? 2 : 0));
                if (vg.getClipChildren()) vg.setClipChildren(false);
                if (vg.getClipToPadding()) vg.setClipToPadding(false);
            }
            if (vg == Main.sContainer) break;
        }
    }

    /** The smallest a companion's ink is shrunk to, in dp: a date has to stay readable. */
    private static final float MIN_COMP_INK_DP = 12f;

    private static final Comp[] ORDER = new Comp[COMPS.length];

    /**
     * Lays the face out around the time's ink, placed with its top at inkTop and drawn at scale:
     * fills each companion's s and y.
     *
     * The OEM's arrangement, scaled with the clock. A piece on the time's row scales exactly with
     * it, so the row stays a row. A piece above or below scales too - magazine A's 128px date over
     * a clock taken down to 0.56 was larger than the time itself - but its ink is not taken under
     * MIN_COMP_INK_DP, and the pieces are stacked outward from the time in the OEM's order, each
     * gap scaled, so one held at its floor does not run into the next.
     */
    private static void layoutCompanions(Live m, float inkTop, float scale) {
        float d = Main.density();
        float inkTopOem = m.inkTop, inkBotOem = m.inkTop + m.box.height();
        float inkBot = inkTop + m.box.height() * scale;
        for (int pass = 0; pass < 2; pass++) {
            int want = pass == 0 ? BELOW : ABOVE;
            int n = 0;
            for (int i = 0; i < m.compN; i++) if (m.comp[i].kind == want) ORDER[n++] = m.comp[i];
            // Outward from the time: nearest first.
            for (int i = 1; i < n; i++) {
                Comp c = ORDER[i];
                int j = i - 1;
                while (j >= 0 && (want == BELOW ? ORDER[j].top > c.top : ORDER[j].top < c.top)) {
                    ORDER[j + 1] = ORDER[j];
                    j--;
                }
                ORDER[j + 1] = c;
            }
            float edgeOem = want == BELOW ? inkBotOem : inkTopOem;
            float edge = want == BELOW ? inkBot : inkTop;
            for (int i = 0; i < n; i++) {
                Comp c = ORDER[i];
                float ink = c.inkB - c.inkT;
                float floor = Math.min(1f, ink > 0f ? MIN_COMP_INK_DP * d / ink : 1f);
                c.s = Math.max(scale, floor);
                if (want == BELOW) {
                    float gap = gapFor((c.top + c.inkT) - edgeOem, scale, d);
                    c.y = edge + gap;
                    edge = c.y + ink * c.s;
                    edgeOem = c.top + c.inkB;
                } else {
                    float gap = gapFor(edgeOem - (c.top + c.inkB), scale, d);
                    c.y = edge - gap - ink * c.s;
                    edge = c.y;
                    edgeOem = c.top + c.inkT;
                }
            }
        }
        for (int i = 0; i < m.compN; i++) {
            Comp c = m.comp[i];
            if (c.kind != BESIDE) continue;
            c.s = scale;
            c.y = inkTop - (inkTopOem - (c.top + c.inkT)) * scale;
        }
    }

    /** The most air left between two stacked pieces of a small face, in dp. */
    private static final float MAX_COMP_GAP_DP = 12f;

    /**
     * An OEM gap between two stacked pieces, at the face's scale - but never more than
     * MAX_COMP_GAP_DP once the face is small. Oriental B leaves 340px between its date and its
     * digits, art direction for a full-screen face; scaled with a clock taken to half it still
     * hung the time 170px under the date. Faded in with the scale, so at full size the OEM's gap
     * is the OEM's gap and the hand-back lands where it should.
     */
    private static float gapFor(float gapOem, float scale, float d) {
        // Never closer than touching once small: a negative OEM gap is a line box reaching over
        // the digits (rhombus), not ink meant to overlap, and pieces held at their floor would
        // collide. Both limits fade in with the shrink, so at full size the gap is the OEM's own.
        float g = gapOem * scale;
        float want = Math.max(0f, Math.min(g, MAX_COMP_GAP_DP * d));
        float shrink = Math.max(0f, Math.min(1f, (1f - scale) / 0.25f));
        return g + (want - g) * shrink;
    }

    /** How far above the time's ink the face reaches once placed at scale s. */
    private static float leadAbove(Live m, float s) {
        layoutCompanions(m, 0f, s);
        float lead = 0f;
        for (int i = 0; i < m.compN; i++) lead = Math.max(lead, -m.comp[i].y);
        return lead;
    }

    /** The row's placed left edge and its OEM left edge, from writePose's clock pass. */
    private static float sRowX, sRowL;

    /**
     * Puts the face's other pieces where the clock now is - see layoutCompanions - each scaled
     * about its own top-left. A piece on the time's row moves with the row; one above or below is
     * centred on its own ink.
     */
    private static void placeCompanions(Live m, float top, float scale, float k, float midX) {
        layoutCompanions(m, top, scale);
        float bottom = Float.NaN;
        for (int i = 0; i < m.compN; i++) {
            Comp c = m.comp[i];
            View v = c.v;
            if (v == null) continue;
            sCompTouched.put(v, Boolean.TRUE);
            unclip(v);
            float s = c.s;
            float x;
            if (c.kind == BESIDE && m.row) {
                x = sRowX + (c.left - sRowL) * scale;
            } else {
                x = c.left;
                float centred = midX - c.inkCx * s;
                if (k > 0f && !Float.isNaN(midX)) x += (centred - c.left) * k;
            }
            float y = c.y - c.inkT * s;
            if (v.getPivotX() != 0f) v.setPivotX(0f);
            if (v.getPivotY() != 0f) v.setPivotY(0f);
            if (v.getScaleX() != s) v.setScaleX(s);
            if (v.getScaleY() != s) v.setScaleY(s);
            float tx = x - c.left, ty = y - c.top;
            if (Math.abs(v.getTranslationX() - tx) >= 0.25f) v.setTranslationX(tx);
            if (Math.abs(v.getTranslationY() - ty) >= 0.25f) v.setTranslationY(ty);
            float b = c.y + (c.inkB - c.inkT) * s;
            if (Float.isNaN(bottom) || b > bottom) bottom = b;
        }
        sCompBottom = bottom;
    }

    /** Puts back everything companions and unclip() changed. */
    private static void clearCompanions() {
        for (View v : sCompTouched.keySet()) {
            if (v == null) continue;
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.resetPivot();
        }
        sCompTouched.clear();
        for (java.util.Map.Entry<android.view.ViewGroup, Integer> e : sUnclipped.entrySet()) {
            android.view.ViewGroup vg = e.getKey();
            if (vg == null) continue;
            if ((e.getValue() & 1) != 0) vg.setClipChildren(true);
            if ((e.getValue() & 2) != 0) vg.setClipToPadding(true);
        }
        sUnclipped.clear();
        sCompBottom = Float.NaN;
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
            // All three, not just the top: a failed capture used to leave the previous unit and
            // date in place, and "top=NaN unit=75.0 date=238.4" is neither one pose nor none.
            sFromTop = sFromUnit = sFromDate = Float.NaN;
            return;
        }
        View g = firstTarget();
        // In the same space writePose writes: the clock's own transform over the OEM's box,
        // with the keyguard's shared zoom left in the ancestors where it belongs. Reading
        // through that zoom instead (tried 2026-09-22) puts this route 5% below the one that
        // reads a recorded doze pose, because that pose is a box.
        sFromTop = m.inkTop + g.getTranslationY();
        sFromUnit = m.unit * g.getScaleY();
        sFromDate = m.date == null ? Float.NaN : m.dateTop + m.date.getTranslationY();
    }

    private static void frame() {
        Phase phase = sPhase;
        if (phase == Phase.OFF) return;
        // The cover holds the y, so the room is not asked while it is up: the yes/no the faces
        // are told is checked from here instead (NotifPresence).
        NotifPresence.recheck();
        Live m = LIVE;
        boolean measured;
        android.os.Trace.beginSection("MC c.measure");
        try { measured = measure(m); } finally { android.os.Trace.endSection(); }
        if (!measured) return;

        if (phase == Phase.AOD) {
            if (sWaking && Main.clockHeld()) {
                // The first lock screen frame of the wake, before it is drawn. Enter now, from the
                // clock the AOD was showing, and place this very frame.
                enter(true, true, "predraw");
                phase = sPhase;
                if (phase != Phase.ENTER) return;
            } else if (sAodHeld && !Float.isNaN(sFromTop) && m.unit > 0f) {
                // Asleep with the cover's clock kept: the pose the lock screen was showing is put
                // back on the doze's own box, and nothing else of ours is touched - no card
                // progress, no glass morph (that one is already a no-op with the screen off), no
                // colour band. The colour of the doze belongs to holdAodColour().
                //
                // Re-applied rather than left alone because the AOD does hand the clock to its own
                // layout: updateFullAodAnimState() re-runs setAllInOneClockRectParams with the
                // lock screen's timeWidth/timeHeight, AnimationHelper re-runs the whole AOD
                // animation once a minute, and both move the box this maps from. The transforms
                // themselves survive all of it - nothing in the full-AOD path writes time_group.
                AodColour.holdAodColour();
                sCenterK = 1f;
                // Back on the lock screen's squeeze, so the OEM draws its glyphs in the face the
                // lock screen was showing rather than the doze's own - see sAodHoldY. Cheap: hold()
                // only reaches the OEM when the value is news, so this is one notifStateChange.
                if (!Float.isNaN(sAodHoldY)) hold(sAodHoldY);
                // Held still in the unzoomed pixels, so the doze's zoom takes it in with the rest
                // of the screen - see parentTop().
                writePose(m, sFromTop, sFromUnit, sFromDate, false);
                // The wake starts from what is on screen, which is this pose.
                sAodTop = sFromTop;
                sAodUnit = sFromUnit;
                sAodDate = sFromDate;
                return;
            } else if (sAodHeld) {
                // A held doze whose pose went missing. Do NOT fall through to the hand-back
                // below: clearTransforms() is exactly what gives the OEM its full-size clock
                // back, which is the bug this branch exists to prevent. The transforms survived
                // the hand-over and still describe the cover's clock, so they are kept; the y
                // they were written against goes back on the OEM so its own relayout does not
                // move them. sAodTop is cleared so the wake captures a fresh pose instead of
                // starting from a doze pose that was never recorded.
                AodColour.holdAodColour();
                sCenterK = 1f;
                if (!Float.isNaN(sAodHoldY)) hold(sAodHoldY);
                sAodTop = Float.NaN;
                sAodUnit = Float.NaN;
                sAodDate = Float.NaN;
                if (!sAodPoseWarned) {
                    sAodPoseWarned = true;
                    Xp.w(TAG + "clock: held doze has no pose, keeping the cover's transforms");
                }
                return;
            } else {
                // The OEM's clock as it is. What it looks like here is the wake's start.
                //
                // Its colour is asked about separately, and the answer depends on WHICH doze this
                // is: holdAodColour() holds a neutral for the plain one, where the gold is the
                // album art behind the glyphs and nothing else describes the picture the clock is
                // on, and holds nothing at all for a full-screen doze whose clock was handed back,
                // where the user asking for the system's clock means the system's colour too.
                // What the keyguard is being scaled by around the clock, read BEFORE the
                // clear because our own leftovers are part of that product. Recorded for the
                // probe only: the pose below is in the lock screen's own pixels and the zoom
                // stays in the ancestors, where writePose leaves it. Measured 2026-09-22:
                // `own` is 1 and the 0.95 is all keyguard_root_view's, so the clear below is
                // tidying up after us rather than fighting the OEM's doze animation.
                View tg = firstTarget();
                sAodOwnScale = tg == null ? 1f : tg.getScaleY();
                sAodChainScale = chainScaleY(tg);
                clearTransforms();
                // Only a pose the doze has been HOLDING is a pose the doze was showing.
                //
                // A wake runs the OEM's own clock animation for a good hundred milliseconds
                // before any route of ours takes the entry, and those frames arrive here, still
                // in Phase.AOD. Every one of them used to be written straight over the doze's
                // pose, so the wake shrank from a clock that had never been on screen: measured
                // on houji, a doze drawn at 1328.9 against the 1164.5 the entry read two frames
                // later, 14% - which is the whole of "单纯点击会在两种动画里随机出现".
                //
                // Two readings that agree are a clock that is not being animated; one that is
                // never agrees with itself. Both poses are gated together, because the box has a
                // second consumer with the same need - the fall INTO the doze aims at sAodUnit.
                if (steady(m.inkTop, sAodPendTop) && steady(m.unit, sAodPendUnit)) {
                    sAodTop = m.inkTop;
                    sAodUnit = m.unit;
                    sAodDate = m.dateTop;
                    sAodLayout = layoutNow();
                }
                sAodPendTop = m.inkTop;
                sAodPendUnit = m.unit;
                AodColour.holdAodColour();
                return;
            }
        }

        android.os.Trace.beginSection("MC c.legacy"); try { Main.convertLegacyHeight(m.box); } finally { android.os.Trace.endSection(); }
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
        float full;
        android.os.Trace.beginSection("MC c.full");
        try { full = fullUnitFor(phase, m); } finally { android.os.Trace.endSection(); }
        float size = Main.sClockSize;
        float base = sizeBase(m, full);
        float coverUnit = Float.isNaN(size) ? Main.sClockHeightDp * d : size * base;
        if (coverUnit > full) coverUnit = full;
        if (coverUnit < Main.MIN_CLOCK_K * base) coverUnit = Main.MIN_CLOCK_K * base;
        // The date and the clock move as one block.
        float offset = Main.sClockOffsetDp * d;
        float coverDate, coverTop;
        if (m.anchored) {
            coverDate = coverDateY(m.date) + offset;
            coverTop = coverDate + m.dateH + Main.CLOCK_GAP_DP * d;
        } else {
            // The whole face - the time and its companions, in the OEM's own arrangement - starts
            // on the line the anchored styles put their date on. NOT where the OEM has the ink:
            // these styles answer the held y with a plain translation of the whole clock - 716px
            // up on magazine C - and taking that as the pose put the clock off the top of the
            // screen. The companions are placed from the clock in writePose; here only how far
            // the topmost of them reaches above the time's ink.
            coverTop = coverDateY(null) + offset + leadAbove(m, coverUnit / m.unit);
            coverDate = Float.NaN;
        }

        // Notifications. The OEM squeezes its full clock out of their way - down to its minimum
        // height, then up - and ours gets the same rule, off the same number: the notifY the OEM
        // keeps reporting while we hold it is the screen y the stack starts at.
        float room;
        android.os.Trace.beginSection("MC c.room");
        try { room = roomBelow(); } finally { android.os.Trace.endSection(); }
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
            // Leaving into the AOD, the destination is the doze that was last on screen, not the
            // OEM's live clock - see toAod(). Leaving cover mode, it is the live clock: there is
            // no other pose to aim at, and the size follows the OEM's own as its glyphs grow on
            // their spring, one smooth curve in the OEM's own proportions the whole way, so never
            // wider than the clock it lands on. A width cap tried first put a kink in the growth
            // where it started to bind (40px a frame to 14 in one frame) - the "顿" at the end.
            //
            // Every one of these is a pose in SCREEN pixels, because that is what writePose
            // takes, and leaving - either way - aims at the OEM's own clock as it would be
            // drawn with nothing of ours on it. Re-derived every frame, so whatever the OEM is
            // doing underneath IS the destination, and the last frame of the walk is already
            // the pose the hand-back leaves on screen: clearing our transforms then changes
            // nothing, by construction.
            //
            // The fall into the doze used to aim at a pose REMEMBERED from the last doze, on a
            // reading taken 2026-09-19 that had the OEM's live clock grow past the doze and
            // come back down. It does not, on this build: `op fall` traced a whole fall on
            // 2026-09-22 and the OEM's box rises monotonically from the held clock to the doze
            // (337 -> 1162.9), stops there, and our walk lands on it to within 0.1px. What the
            // remembered pose did do was go stale - dismiss a notification and the next doze
            // lays its clock out 14% differently (1328.9 against 1168.9, both measured) - so
            // the fall drove to where the LAST doze had been, and the settle test, comparing
            // the OEM's live box against that same stale figure, could never pass: the walk sat
            // there until LAND_ANYWAY_MS gave up and the clock jumped to where it belonged.
            // Reported 2026-09-22 as 划掉通知后息屏，时钟还在没划掉的位置，过一段时间又跳回正
            // 确的位置. Nothing remembered, nothing to go stale.
            float toTop = in ? coverTop : m.inkTop;
            float toUnit = in ? coverUnit : m.unit;
            float toDate = in ? coverDate : m.dateTop;
            sLastToUnit = toUnit;
            top = sFromTop + (toTop - sFromTop) * t;
            unit = sFromUnit + (toUnit - sFromUnit) * t;
            date = Float.isNaN(sFromDate) ? toDate : sFromDate + (toDate - sFromDate) * t;
            float tc = Math.max(0f, Math.min(1f, t));
            // Into the cover, the card's material sets off after the artwork has: the entry's
            // first frames are the GPU's worst - the artwork's texture going up, the new effects
            // set up, three frames queued behind them (traced 2026-09-25, 7-34ms waits) - and the
            // card's crossfade was one more full-card redraw on each of them. It catches up on a
            // smooth curve and lands with the clock.
            float tCard = tc;
            if (in && !Main.perfOff("lag")) {
                float u = Math.max(0f, Math.min(1f, (tc - CARD_LAG) / (1f - CARD_LAG)));
                tCard = u * u * (3f - 2f * u);
            }
            card = sCardFrom + (sCardTo - sCardFrom) * tCard;
            glass = sGlassFrom + (sGlassTo - sGlassFrom) * tc;
        }
        // How far a style that is not anchored is taken to the middle - see writePose.
        sCenterK = phase == Phase.ON ? 1f
                : sCenterFrom + (sCenterTo - sCenterFrom) * Math.max(0f, Math.min(1f, sT));

        android.os.Trace.beginSection("MC c.write"); try { writePose(m, top, unit, date, phase == Phase.ENTER && sGlyphTail); } finally { android.os.Trace.endSection(); }
        // The pose the lock screen is showing, for a sleep taken while it is not drawing. See
        // sDrawnTop. Kept through a hidden keyguard on purpose: the transforms stay on the views
        // and are still what the lock screen comes back to. An EXIT is on its way to the OEM's
        // pose, so it is not "the cover's pose" and is not kept.
        if (phase == Phase.ENTER || phase == Phase.ON) {
            sDrawnTop = top;
            sDrawnUnit = unit;
            sDrawnDate = date;
        }
        if (phase == Phase.ON) { android.os.Trace.beginSection("MC c.note"); try { notePose(m, top, unit, date, full, room); } finally { android.os.Trace.endSection(); } }
        // Once per drawn frame: onGlyphDrawn re-places from inside the draw, so this runs
        // three times a frame on this style and a trace that kept them all covered a third of
        // one transition. See sRedoing.
        //
        // Kept running for FLIGHT_TAIL_MS past the start, so it covers the landing and what
        // happens after it. That tail is the whole question for a wake: the OEM zooms the
        // WHOLE keyguard (keyguard_root_view, 0.95 in the doze, measured 2026-09-22) back to 1
        // on its own schedule, and whether that finishes before or after our spring lands
        // decides whether the clock may simply ride it. `ab=` is that zoom.
        if (!sRedoing && sFallN < FALL_MAX
                && android.os.SystemClock.uptimeMillis() - sFallT0 < FLIGHT_TAIL_MS) {
            View gt = firstTarget();
            noteFall(m.unit, sLastToUnit, unit, gt == null ? 1f : aboveScaleY(gt));
        }
        android.os.Trace.beginSection("MC c.card"); try { MediaCard.setCardProgressFrom(card); } finally { android.os.Trace.endSection(); }
        sGlassP = glass;
        android.os.Trace.beginSection("MC c.glass"); try { ClockGlass.applyGlassMorph(glass); } finally { android.os.Trace.endSection(); }
        // The colour band describes the lock screen; the AOD's layout is not that.
        if (!(phase == Phase.EXIT && sExitToAod)) { android.os.Trace.beginSection("MC c.band"); try { Main.updateColorBand(); } finally { android.os.Trace.endSection(); } }
    }

    /**
     * Puts one pose on the clock: the ink top, the height of one row of digits, and the top of
     * the date, all in unzoomed screen pixels (parentTop), mapped onto whatever the OEM is about
     * to draw.
     *
     * The single place the clock's views are moved. The mapping is absolute rather than relative -
     * the translation is measured from the box about to be drawn to the pose asked for - so
     * anything the OEM does to an ancestor (its own squeeze translation, the AOD's re-layout, a
     * Folme animator settling) is absorbed here rather than added to.
     *
     * @param holdWidth keep the drawn width from growing - the entry's glyph tail; see the call
     */
    private static void writePose(Live m, float top, float unit, float date, boolean holdWidth) {
        // A pose is in the lock screen's OWN pixels, and the OEM is free to scale the lock
        // screen underneath it - the doze's 0.95 on keyguard_root_view, a swipe's zoom on the
        // clock's container. The clock rides those like the notifications, the media card and
        // everything else do, about the zoom's own pivot (see parentTop).
        //
        // Dividing that scale out was tried and put back (2026-09-22). It pins the clock and the
        // date to absolute screen pixels, which makes them the only two things on the lock
        // screen NOT zooming out of the AOD - reported as 息屏稳定后点亮和反复息屏点亮的
        // 动画不一样, since a wake that never reached the doze has no zoom to divide out.
        float scale = unit / m.unit;
        sInkBottom = top + m.box.height() * scale;
        float scaleX = scale;
        if (holdWidth && m.box.width() > 0f) {
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
        // The styles that are not anchored lay their clock out to one side - magazine C hard
        // left - and ColorOS takes exactly those to the middle when its music page is up: its
        // magazine clock's IMMERSED scene centres the time and the date and drops the rest
        // (Scene.KeyguardImmersed, c(.., 0.46f, centred = true, ..)). The same here, faded in and
        // out with the pose so the hand-back lands on the OEM's own x.
        float k = m.anchored ? 0f : sCenterK;
        float midX = k > 0f ? screenMidX() : Float.NaN;
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            float px = Main.clockPivotX(g, m.box);
            if (g.getPivotX() != px) g.setPivotX(px);
            if (g.getPivotY() != m.box.top) g.setPivotY(m.box.top);
            if (g.getScaleX() != scaleX) g.setScaleX(scaleX);
            if (g.getScaleY() != scale) g.setScaleY(scale);
            glassFollowsScale(g, scale);
            float ty = top - (parentTop(g) + g.getTop() + m.box.top);
            if (Math.abs(g.getTranslationY() - ty) >= 0.25f) g.setTranslationY(ty);
            // Never touched on the anchored styles: their x is the OEM's and DateStatus's.
            if (m.anchored) continue;
            // Remembered for the release: a style that swaps faces under us (oriental A) leaves
            // the old target hidden with our transform on it, and it would come back scaled.
            sCompTouched.put(g, Boolean.TRUE);
            unclip(g);
            // Where the ink's left edge lands with no x of ours, and where it is wanted.
            float inkL0 = parentLeft(g) + g.getLeft() + px + (m.box.left - px) * scaleX;
            float inkL = inkL0;
            if (m.row) {
                // The row as one piece: the time and what sits beside it, scaled together about
                // the row's own top-left and centred as a whole.
                float rl = parentLeft(g) + g.getLeft() + m.box.left, rr = rl + m.box.width();
                for (int i = 0; i < m.compN; i++) {
                    Comp c = m.comp[i];
                    if (c.kind != BESIDE) continue;
                    rl = Math.min(rl, c.left);
                    rr = Math.max(rr, c.left + c.w);
                }
                float oemInkL = parentLeft(g) + g.getLeft() + m.box.left;
                float row0 = inkL0 - (oemInkL - rl) * scale;
                float rowX = Float.isNaN(midX) ? row0
                        : row0 + (midX - (rr - rl) * scale / 2f - row0) * k;
                inkL = rowX + (oemInkL - rl) * scale;
                sRowX = rowX;
                sRowL = rl;
            } else if (k > 0f && !Float.isNaN(midX)) {
                inkL = inkL0 + (midX - (inkL0 + m.box.width() * scaleX / 2f)) * k;
            }
            float tx = inkL - inkL0;
            if (Math.abs(g.getTranslationX() - tx) >= 0.25f) g.setTranslationX(tx);
        }
        if (!m.anchored) placeCompanions(m, top, scale, k, midX);
        // The other styles lay their own date out, and it is only moved to follow the offset -
        // unless it sits inside the clock, which carries it already.
        if (m.anchored && m.date != null && !Float.isNaN(date)) {
            float dty = date - m.dateTop;
            if (Math.abs(m.date.getTranslationY() - dty) >= 0.25f) {
                m.date.setTranslationY(dty);
                MOVED_DATES.add(m.date);
            }
        }
        // The signature bar, the same problem one rung down. all_in_one derives its topMargin from
        // the clock's own rect - clockRect.bottom plus the gap between time and signature - and
        // classic chains it off the digit rows, so on both it hangs the clock by a distance the
        // OEM works out for itself. Moving the clock moves nothing of that: the bar is a sibling,
        // not a child, so it is carried here by the same distance off the ink this pose puts on
        // screen.
        //
        // The distance is the OEM's live one, which is also what makes the hand back exact: on the
        // way out the pose is the OEM's own box, so the target comes out at the bar's own layout
        // top and the translation lands on 0 by itself. No interpolation to keep in step with the
        // clock's, and nothing to remember for the next entry.
        for (int i = 0; i < m.sigN; i++) {
            Sig s = m.sig[i];
            if (s.v == null) continue;
            float sty = sInkBottom + s.gap - s.top;
            if (Math.abs(s.v.getTranslationY() - sty) >= 0.25f) s.v.setTranslationY(sty);
        }
    }

    /**
     * Drops the pose the last settled doze was drawn at.
     *
     * Held, that pose is the cover's small one, and the fall into an OEM doze aims at it - so a
     * setting that has just been turned off would land on the small clock. Clearing it is the
     * state a doze that has never settled is in, which falls back to the OEM's live clock.
     */
    static void forgetAodPose() {
        sAodTop = Float.NaN;
        sAodUnit = Float.NaN;
        sAodDate = Float.NaN;
        clearAodPend();
    }
}
