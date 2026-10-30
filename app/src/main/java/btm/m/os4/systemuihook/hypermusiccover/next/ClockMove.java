// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Read-only probe: how the clock moves while the notification stack changes under it.
 *
 * The gesture this exists for is the island being pulled up into a media notification and
 * pushed back down: the clock gives way on the way up over a spring, and on the way back the
 * user sees it jump. Three different hands can be moving that clock - the OEM's own flow
 * pushing a new y, our rewrite of that y (roomForRows / easeRoom), and the OEM's own Folme
 * animation of the y it was given - and a settled reading cannot say which of them stepped.
 *
 * So this records BOTH sides of it, one line per drawn frame: what the y channel was told and
 * what we did with it, next to what the clock views were actually carrying on that frame. The
 * line that comes out is the one where `sz` or `clk` steps in a single frame while `req` ramps
 * - or where `req` steps and the drawn clock steps with it, which is the OEM and not us.
 *
 * Armed from adb, answered from adb; nothing here writes to any view:
 *
 *     am broadcast -a btm.m.os4.systemuihook.hypermusiccover.next.PROBE --es op clockmove --ei ms 10000
 *     ... do the pull up and the pull back down ...
 *     am broadcast -a btm.m.os4.systemuihook.hypermusiccover.next.PROBE --es op clockmove
 *
 * The recording stops by itself when the window runs out, and is kept until the next arming.
 * Rows of the answer, in the order they are printed:
 *
 *   t     ms since arming.  +k before a line = k identical frames before it (nothing moved).
 *   nY    setNotifY calls in THIS frame, req = the y the OEM put in, out = the y it left with.
 *   st    notifStateChange calls this frame, stY = its y, anim = its isDragOrFling flag
 *         (all_in_one goes through setNotifY instead, so this is normally absent).
 *   room  roomForRowsNow calls, top = stackContentTop, src = where that top came from.
 *   ease  the ease's own state: 1 = running, shown = the y it is showing, raw = the y asked
 *         for, jump = a jump was seen this frame (this is our hand, not the OEM's).
 *   sz    the last setSizeInternal the OEM asked a TimeView for - the font size, and the one
 *         number that is the OEM's animation progress rather than its target.
 *   ra    reassertClockRoom emissions this frame, @ = the y it emitted; raFail = it threw.
 *   cac   translation on `clock_animation_container`, which carries the clock and the date.
 *   clk   the clock target on screen: y / height / scaleY / translationY, per clock tree.
 *   date  the date's y on screen and its height.
 *   ph    ClockCollapse's phase, so a reading inside cover mode can be told from one outside.
 */
final class ClockMove {

    private ClockMove() {
    }

    private static final String TAG = "[MCProbe] ";

    /**
     * Lines kept. Only frames where something moved are stored, so this is a lot of gesture:
     * the whole of the run above was 230 lines for two pulls and two minutes of idle.
     */
    private static final int MAX_FRAMES = 320;

    private static volatile long sUntil;
    private static long sArmAt;
    private static int sWindow;
    private static View sTraced;

    private static final java.util.ArrayDeque<String> sLines = new java.util.ArrayDeque<>();
    private static String sLastBody = "";
    private static int sRepeats;
    private static String sFooter = "";

    // What the hooks and the ease reported since the last frame. Reset at the end of each one.
    private static int nNotifY, nState, nRoom, nReassert, nReassertFail, nSize, nWeight;
    private static float lastReq = Float.NaN, lastOut = Float.NaN, lastState = Float.NaN,
            lastRoom = Float.NaN, lastTop = Float.NaN, lastSize = Float.NaN,
            lastWeight = Float.NaN, lastReassert = Float.NaN;
    private static boolean lastAnim, lastJump;
    private static String src = "-";

    /** Whether a recording is still running. Cheap: one volatile read and a clock. */
    static boolean on() {
        long u = sUntil;
        return u != 0L && SystemClock.uptimeMillis() < u;
    }

    static void arm(int ms) {
        long now = SystemClock.uptimeMillis();
        sArmAt = now;
        sWindow = Math.max(500, ms);
        sUntil = now + sWindow;
        synchronized (sLines) {
            sLines.clear();
        }
        sLastBody = "";
        sRepeats = 0;
        sFooter = "";
        sFrames = 0;
        sResolvedAt = -999;
        sInteractor = null;
        java.util.Arrays.fill(sChain, null);
        reset();
        final View v = Main.sContainer;
        if (v == null) {
            sFooter = "armed with no clock container: the keyguard is not built, nothing will be seen";
            Xp.log(TAG + "clockmove: " + sFooter);
            return;
        }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                detach();
                try {
                    resolve();
                    Gen.preDraw(v, F);
                    sTraced = v;
                } catch (Throwable t) {
                    sFooter = "listener not installed: " + t;
                }
            }
        });
    }

    /** Off the view it was installed on, whichever view that was. Also the hot-reload teardown. */
    static void detach() {
        View v = sTraced;
        sTraced = null;
        if (v == null) return;
        try {
            Gen.unpreDraw(v, F);
        } catch (Throwable ignored) {
        }
    }

    /** The recording, for `op clockmove` with no `ms`. */
    static String read() {
        StringBuilder sb = new StringBuilder("clockmove: one line per drawn frame, +k = k "
                + "identical frames before it.\n"
                + "  nY/req/out = setNotifY calls, the y the OEM gave and the y it left with\n"
                + "  room/top/src = roomForRowsNow calls, stackContentTop, where that top came from\n"
                + "  ease/shown/raw/jump = our ease: running, showing, asked for, a jump was seen\n"
                + "  sz / wt = the last setSizeInternal / setWeight (the OEM's own animation)\n"
                + "  soft = how much of a giving-room jump is still being walked slowly\n"
                + "  ra = reassertClockRoom emissions, cac = translation on clock_animation_container\n"
                + "  cty = clockTranslationY out of the OEM's own ClockResult for this frame\n"
                + "  box = the ink box on screen l,t,r,b - the clock the eye is actually tracking\n"
                + "  chain = the clock target and its ancestors, id@translationY/scaleY/screenY. A\n"
                + "          screenY that moves while translationY stays 0 is a LAYOUT move.\n"
                + "  date = the date's y/height\n"
                + "  ph + the trailing clk[...] = ClockCollapse's phase, its springs and its glyph box\n");
        if (sUntil == 0L && sArmAt == 0L) {
            return sb.append("nothing recorded yet - arm it with --ei ms <n> first").toString();
        }
        sb.append("armed at t0, window ").append(sWindow).append("ms")
          .append(sUntil == 0L ? ", finished" : ", still running").append('\n');
        synchronized (sLines) {
            for (String l : sLines) sb.append(l).append('\n');
        }
        if (!sFooter.isEmpty()) sb.append(sFooter).append('\n');
        return sb.toString();
    }

    // --- what the hooks report -------------------------------------------------------------

    /** From the setNotifY hook: what arrived, and what it was given instead. */
    static void noteNotifY(float requested, float out) {
        if (!on()) return;
        nNotifY++;
        lastReq = requested;
        lastOut = out;
    }

    /** From the notifStateChange hook: the other way in, with its own "animate" flag. */
    static void noteState(float requested, Object isDragOrFling, float out) {
        if (!on()) return;
        nState++;
        lastState = requested;
        lastAnim = Boolean.TRUE.equals(isDragOrFling);
        if (nNotifY == 0) {
            // Only until setNotifY answers with the same frame's landing, which is the more
            // useful of the two: this path is all_in_one's way in, not its way out.
            lastReq = requested;
            lastOut = out;
        }
    }

    /** From roomForRowsNow: the stack's own figure, and ours for it. */
    static void noteRoom(float requested, float top, float out) {
        if (!on()) return;
        nRoom++;
        lastRoom = requested;
        lastTop = top;
        try {
            String s = MiniPlayerRuntime.stackContentSource();
            src = s == null ? "-" : (s.length() > 60 ? s.substring(0, 60) : s);
        } catch (Throwable t) {
            src = "?";
        }
    }

    /** From easeRoom: a target that arrived in one step rather than being animated into place. */
    static void noteEase(boolean jump) {
        if (!on()) return;
        if (jump) lastJump = true;
    }

    /** From reassertClockRoom: our own per-frame nudge back into the y flow. */
    static void noteReassert(float y, boolean ok) {
        if (!on()) return;
        if (ok) {
            nReassert++;
            lastReassert = y;
        } else {
            nReassertFail++;
        }
    }

    /** From the TimeView hook: the point size the OEM asked for this frame. */
    static void noteSize(float size) {
        if (!on() || Float.isNaN(size)) return;
        nSize++;
        lastSize = size;
    }

    /** From the TimeView hook: the weight the OEM asked for, the font's other axis. */
    static void noteWeight(float weight) {
        if (!on() || Float.isNaN(weight)) return;
        nWeight++;
        lastWeight = weight;
    }

    // --- one line per drawn frame ----------------------------------------------------------

    private static final ViewTreeObserver.OnPreDrawListener F =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            frame();
            return true;
        }
    };

    private static void frame() {
        long now = SystemClock.uptimeMillis();
        if (sUntil == 0L) return;
        if (now > sUntil) {
            stop(now);
            return;
        }
        sFrames++;
        // Built WITHOUT the timestamp: the timestamp is what separates the lines, so comparing
        // it too would make every frame different and the run would fill its buffer with a
        // clock standing still (measured: 400 lines of an idle lock screen in 4.1 seconds).
        StringBuilder sb = new StringBuilder();
        if (nNotifY > 0) {
            sb.append(" nY=").append(nNotifY)
              .append(" req=").append(Main.r1(lastReq))
              .append(" out=").append(Main.r1(lastOut));
        }
        if (nState > 0) {
            sb.append(" st=").append(nState)
              .append(" stY=").append(Main.r1(lastState))
              .append(" anim=").append(lastAnim ? 1 : 0);
        }
        if (nRoom > 0) {
            sb.append(" room=").append(nRoom)
              .append(" top=").append(Main.r1(lastTop))
              .append(" src=").append(src);
        }
        sb.append(" ease=").append(ClockRoom.easeRunning() ? 1 : 0)
          .append(" shown=").append(Main.r1(ClockRoom.easeShown()))
          .append(" raw=").append(Main.r1(Main.easeRaw()))
          .append(" jump=").append(lastJump ? 1 : 0);
        if (ClockRoom.easeSoft() > 0f) sb.append(" soft=").append(Main.r1(ClockRoom.easeSoft()));
        if (nSize > 0) sb.append(" sz=").append(Main.r2(lastSize));
        if (nWeight > 0) sb.append(" wt=").append(Main.r2(lastWeight));
        if (nReassert > 0) {
            sb.append(" ra=").append(nReassert).append('@').append(Main.r1(lastReassert));
        }
        if (nReassertFail > 0) sb.append(" raFail=").append(nReassertFail);
        appendOem(sb);
        appendDrawn(sb);
        sb.append(" ph=").append(ClockCollapse.phase());
        // ClockCollapse's own line (see traceLine): in cover mode the clock's size and place are
        // its springs, not the notification Y, and the two have to be told apart in one reading.
        sb.append(ClockCollapse.traceLine());

        String body = sb.toString();
        reset();
        if (body.equals(sLastBody)) {
            sRepeats++;
            return;
        }
        String line = "t=" + (now - sArmAt) + (sRepeats > 0 ? " +" + sRepeats : "") + " " + body;
        sRepeats = 0;
        sLastBody = body;
        boolean full;
        synchronized (sLines) {
            sLines.addLast(line);
            full = sLines.size() >= MAX_FRAMES;
        }
        // The buffer keeps the FIRST lines and stops when it is full, rather than dropping the
        // head: the gesture that matters starts the moment the run is armed, and a rolling
        // window would have thrown that half away by the time it ended.
        if (full) stop(now);
    }

    /**
     * What the clock views are carrying on this frame, read rather than computed: the
     * translation the OEM moves (`clock_animation_container` carries the clock and the date
     * together) and the clock target's own place and size on the screen. Nothing is written back.
     *
     * The views are resolved once and remembered. `clockTarget` and `visibleDate` each walk a
     * resource table and a tree to answer, and this module has been bitten before by a probe
     * that cost the frames it was measuring - `op verbose` janks the transition it describes.
     * A style cannot change mid-gesture, so the only thing worth re-asking for is a view that
     * has left its window.
     */
    private static void appendDrawn(StringBuilder sb) {
        int[] loc = new int[2];
        try {
            if (!live(sDate) || sClocks[0] == null || sClocks[1] == null || sChain[0] == null) {
                // At most every half second: a style this probe cannot resolve a view for would
                // otherwise walk the resource table and the tree on every frame of the gesture.
                if (sFrames - sResolvedAt > 30) resolve();
            }
            // The ink box: what the eye is tracking, and the only reading that can show a glyph
            // size change, because the clock's view bounds do not move with the variable font.
            try {
                sb.append(" box=").append(ClockViews.boxOf(Diagnostics.measureLiveBox()));
            } catch (Throwable t) {
                sb.append(" box=?");
            }
            // Whose translation carries the clock, read up the chain: `getTop` never enters this,
            // so a change in the screen y of an entry whose ty is still 0 is a LAYOUT move.
            sb.append(" chain=");
            for (int i = 0; i < sChain.length; i++) {
                View v = sChain[i];
                if (v == null) break;
                v.getLocationOnScreen(loc);
                sb.append(i == 0 ? "" : ">").append(Main.idOf(v))
                  .append('@').append(Main.r1(v.getTranslationY()))
                  .append('/').append(Main.r2(v.getScaleY()))
                  .append('/').append(loc[1]);
            }
            View d = sDate;
            if (d != null) {
                d.getLocationOnScreen(loc);
                sb.append(" date=").append(loc[1]).append('/').append(d.getHeight());
            }
            View cac = sCac;
            sb.append(" cac=").append(cac == null ? "?" : Main.r1(cac.getTranslationY()));
        } catch (Throwable t) {
            sb.append(" drawn=?");
        }
    }

    /**
     * The OEM's own answer for this frame: `clockTranslationY` out of the ClockResult the
     * interactor just computed. This is the number the squeeze is supposed to move the clock by,
     * so a `cty` that stays put while the clock moves on screen says the motion never came from
     * the notification Y at all.
     */
    private static void appendOem(StringBuilder sb) {
        try {
            Object it = sInteractor != null ? sInteractor : (sInteractor = interactor());
            if (it == null) {
                sb.append(" cty=none");
                return;
            }
            Object cr = Xp.getObjectField(it, "clockResult");
            if (cr == null) {
                sb.append(" cty=null");
                return;
            }
            Object y;
            try {
                y = Xp.callMethod(cr, "getClockTranslationY");
            } catch (Throwable t) {
                // Kotlin data class: the getter may be obfuscated on some builds while the
                // backing field keeps its name, which is the case this device was read from.
                y = Xp.getObjectField(cr, "clockTranslationY");
            }
            sb.append(" cty=").append(y instanceof Number ? Main.r1(((Number) y).floatValue()) : "?");
        } catch (Throwable t) {
            sb.append(" cty=?");
        }
    }

    /** The interactor the clock container holds, which is where the OEM's own maths lands. */
    private static Object interactor() {
        View v = Main.sContainer;
        return v == null ? null : Xp.getObjectField(v, "keyguardClockNotifInteractor");
    }

    private static Object sInteractor;

    private static View sDate;
    private static View sCac;
    private static final View[] sClocks = new View[2];
    /** The clock target and four ancestors: what actually carries it towards the screen. */
    private static final View[] sChain = new View[5];

    private static boolean live(View v) {
        return v != null && v.isAttachedToWindow() && v.getParent() != null;
    }

    /** The nth clock tree's own clock view, for the two trees a style draws the clock in. */
    private static View resolveClock(int n) {
        try {
            View[] roots = Main.clockRoots();
            return n < roots.length ? Main.clockTarget(roots[n]) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sFrames, sResolvedAt = -999;

    /** Resolved at arming, and again only if one of them leaves its window. */
    private static void resolve() {
        sResolvedAt = sFrames;
        for (int i = 0; i < 2; i++) sClocks[i] = resolveClock(i);
        View up = sClocks[0] != null ? sClocks[0] : sClocks[1];
        for (int i = 0; i < sChain.length; i++) {
            sChain[i] = up;
            up = up != null && up.getParent() instanceof View ? (View) up.getParent() : null;
        }
        sDate = ClockViews.visibleDate();
        // `clock_animation_container` is the date's parent and the clock's, and it is the view
        // the OEM translates - the one thing here that cannot be read off `sContainer`, whose
        // own translation is always 0. Asked of the date first, of the clock tree if the date
        // is not one this module can find.
        View p = sDate != null && sDate.getParent() instanceof View ? (View) sDate.getParent() : null;
        if (p == null) {
            for (View g : sClocks) {
                if (g != null && g.getParent() instanceof View) {
                    p = (View) g.getParent();
                    break;
                }
            }
        }
        sCac = p;
    }

    private static void stop(long now) {
        sUntil = 0L;
        detach();
        int n;
        synchronized (sLines) {
            n = sLines.size();
        }
        sFooter = "ended t=" + (now - sArmAt) + "ms, " + n + " lines"
                + (sRepeats > 0 ? " (last line held for " + sRepeats + " frames)" : "");
        Xp.log(TAG + "clockmove " + sFooter);
    }

    private static void reset() {
        nNotifY = nState = nRoom = nReassert = nReassertFail = nSize = nWeight = 0;
        lastReq = lastOut = lastState = lastRoom = lastTop = lastSize = lastReassert = Float.NaN;
        lastWeight = Float.NaN;
        lastAnim = false;
        lastJump = false;
        src = "-";
    }
}
