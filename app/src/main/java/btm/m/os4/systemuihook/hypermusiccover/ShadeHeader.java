package btm.m.os4.systemuihook.hypermusiccover;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/**
 * The control centre's own header - the big clock, the date, and the carrier group - moved up or
 * down by a fixed amount each.
 *
 * ## These are not the lock screen's clock, and they are not the content push
 *
 * Two things make this a different problem from {@link ShadeLayer}'s content push, and both were
 * measured off the tree before a line of this was written:
 *
 * - **The header is a different set of views from the lock screen's clock.** The lock screen's is
 *   `miui_keyguard_clock_container`; this one is `MiuiNotificationHeaderView`, and it reads
 *   INVISIBLE whenever the shade is shut. So unlike every other write in this module, there is no
 *   keyguard to be careful of here - the views are simply not on screen when it matters. The
 *   arming is still driven from the shade's own edge, because that is where the module knows the
 *   shade is up and it costs nothing to be consistent.
 * - **MIUI animates their translation itself.** Closed, `#big_time` sits at `ty=-187` and both
 *   `#date_time` and `#carrier_container` at `ty=-81`; open, all three read 0, and the clock is
 *   scaled 96x86 -> 312x192 on the way. A single write on the shade-open edge would therefore be
 *   overwritten before the pull finished - the edge fires at 4.5% and the header is still
 *   animating at 100%.
 *
 * So this is a DELTA SHADOW rather than a write. At pre-draw - after MIUI has applied this
 * frame's transform and before the frame is drawn, which is the only moment both are true - each
 * view's translation is read back and our offset is re-applied only when the value is not the one
 * we last wrote. MIUI is at rest almost all of the time, so almost every frame does nothing but
 * three float compares; when MIUI does move a view, we follow it by our offset on the same frame
 * instead of a frame later, which is what stops the header shivering the way the date does on
 * `all_in_one` (see Main's setNotifY note).
 *
 * The same bookkeeping is what makes the offset removable: what we added last time is remembered
 * per view, so handing back is "subtract what we added", not "set zero" - MIUI's own value is
 * under there and is its to keep.
 */
final class ShadeHeader {

    private ShadeHeader() {
    }

    private static final String TAG = "[MCHeader] ";

    /**
     * Resolved by id inside the header, never by class name alone.
     *
     * All four are real ids on this build (read off the tree with the `views` op), and the two
     * the module would otherwise have to guess at - the clock and the date - are both `MiuiClock`
     * or clock-shaped classes, so a class test could not tell them apart from the lock screen's.
     */
    private static final String ID_HEADER = "normal_notification_header_view";
    private static final String ID_CLOCK = "big_time";
    private static final String ID_DATE = "date_time";
    private static final String ID_CARRIER = "carrier_container";

    /** Pixels down for positive, up for negative. Both 0 by default: nothing moves unasked. */
    private static volatile int sClockShift;
    private static volatile int sCarrierShift;

    private static volatile View sClock;
    private static volatile View sDate;
    private static volatile View sCarrier;
    private static volatile ViewTreeObserver sObserver;
    private static volatile boolean sArmed;

    private static final int CLOCK = 0;
    private static final int DATE = 1;
    private static final int CARRIER = 2;
    /** What we added to each view last time, so MIUI's own value can be found underneath it. */
    private static final int[] sApplied = new int[3];
    /** The value we last wrote, or NaN when the view is not ours at the moment. */
    private static final float[] sWritten = {Float.NaN, Float.NaN, Float.NaN};

    // ------------------------------------------------------------------ settings

    static void setClock(int px) {
        sClockShift = px;
    }

    static void setCarrier(int px) {
        sCarrierShift = px;
    }

    static int clockShift() {
        return sClockShift;
    }

    static int carrierShift() {
        return sCarrierShift;
    }

    // ------------------------------------------------------------------ arming

    /**
     * Whether the header should be held at its offset right now.
     *
     * Called from the same edge as the rest of the shade work. Arming resolves the views against
     * the live tree every time - the shade's hierarchy is rebuilt across transitions and a cached
     * view is one that takes writes nobody sees.
     */
    static void arm(final boolean on) {
        if (!on) {
            disarm();
            return;
        }
        if (sClockShift == 0 && sCarrierShift == 0) return;

        final View root = ShadeLayer.shadeRoot();
        if (root == null) return;
        final View header = byId(root, ID_HEADER);
        if (header == null) {
            if (!sWarned) {
                sWarned = true;
                Xp.log(TAG + "no " + ID_HEADER + " under " + root.getClass().getSimpleName()
                        + " - there is nothing for the header offsets to move on this build");
            }
            return;
        }
        // Anything still held from a previous arming comes off FIRST. Without this the new
        // offset would be added on top of the old one, and a slider dragged across its range
        // would walk the header away a little on every step.
        if (sArmed) {
            track(CLOCK, sClock, 0, true);
            track(DATE, sDate, 0, true);
            track(CARRIER, sCarrier, 0, true);
            sArmed = false;
        }
        // Each lookup fails on its own: a build that renames one of the three must not take the
        // other two down with it. See the module's own note on hooks failing independently.
        sClock = byId(header, ID_CLOCK);
        sDate = byId(header, ID_DATE);
        sCarrier = byId(header, ID_CARRIER);
        if (sClock == null && sDate == null && sCarrier == null) {
            Xp.log(TAG + "none of " + ID_CLOCK + " / " + ID_DATE + " / " + ID_CARRIER
                    + " found inside the header - nothing to move");
            return;
        }
        for (int i = 0; i < 3; i++) {
            sApplied[i] = 0;
            sWritten[i] = Float.NaN;
        }
        watch(header);
        if (!sArmed) {
            Xp.log(TAG + "header armed: clock=" + (sClock != null) + " date=" + (sDate != null)
                    + " carriers=" + (sCarrier != null)
                    + " shift=" + sClockShift + "/" + sCarrierShift);
        }
        sArmed = true;
        // Once immediately, so a setting changed while the shade is already open does not wait
        // for a frame that may never come.
        apply();
    }

    private static volatile boolean sWarned;

    /** Stops watching and hands every view back. Idempotent. */
    static void disarm() {
        final ViewTreeObserver obs = sObserver;
        sObserver = null;
        if (obs != null) {
            try {
                if (obs.isAlive()) obs.removeOnPreDrawListener(GUARD);
            } catch (Throwable ignored) {
            }
        }
        if (!sArmed) return;
        sArmed = false;
        // The hand-back subtracts what we added rather than writing 0 over the top: MIUI's own
        // translation is under ours and is not this module's to reset.
        track(CLOCK, sClock, 0, true);
        track(DATE, sDate, 0, true);
        track(CARRIER, sCarrier, 0, true);
        sClock = null;
        sDate = null;
        sCarrier = null;
        Xp.log(TAG + "header released");
    }

    private static void watch(View header) {
        if (sObserver != null && sObserver.isAlive()) return;
        final ViewTreeObserver obs = header.getViewTreeObserver();
        if (!obs.isAlive()) return;
        sObserver = obs;
        obs.addOnPreDrawListener(GUARD);
    }

    /**
     * Read, correct, draw.
     *
     * Returns true unconditionally - this is an observer, and a pre-draw listener that answers
     * false cancels the frame it was asked about.
     */
    private static final ViewTreeObserver.OnPreDrawListener GUARD =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    apply();
                    return true;
                }
            };

    private static void apply() {
        if (!sArmed) return;
        track(CLOCK, sClock, sClockShift, false);
        track(DATE, sDate, sClockShift, false);
        track(CARRIER, sCarrier, sCarrierShift, false);
    }

    /**
     * Holds one view at MIUI's own value plus `delta`.
     *
     * @param release subtract our last offset and stop tracking, leaving MIUI's value alone.
     */
    private static void track(int slot, View v, int delta, boolean release) {
        if (v == null) return;
        try {
            final float cur = v.getTranslationY();
            final int was = sApplied[slot];
            final float next;
            if (release) {
                // Whatever is on the view is ours, so what we added comes straight back off and
                // MIUI's own value is what is left underneath. Writing 0 here instead would be
                // taking over a property that is not this module's.
                next = cur - was;
                sApplied[slot] = 0;
                sWritten[slot] = Float.NaN;
            } else if (cur == sWritten[slot]) {
                // Ours, and it has not moved. This is the ordinary frame - three compares and no
                // write, which is the whole cost of this being a per-frame guard.
                if (was == delta) return;
                // Ours, but the setting moved under it: the old offset comes off before the new
                // one goes on, or a slider dragged across its range walks the view away.
                next = cur - was + delta;
                sApplied[slot] = delta;
                sWritten[slot] = next;
            } else {
                // NOT ours. MIUI has written it since we last looked, so this value IS its own
                // and the offset goes on top of it.
                //
                // Measured, and this is the branch that had to be got right: MIUI returns all
                // three views to 0 when the pull settles, and the first version of this
                // subtracted what it had added last time - reading "0, after we wrote 150" as
                // "MIUI moved by -150" and writing 0 + 150 - 150. The offset cancelled itself
                // out on exactly the frame it was needed, and `shadedump` showed the tell:
                // `added=150,150,250` with `ty=0,0,0`.
                next = cur + delta;
                sApplied[slot] = delta;
                sWritten[slot] = next;
            }
            if (next != cur) v.setTranslationY(next);
        } catch (Throwable t) {
            Xp.log(TAG + "could not hold a header view: " + t);
        }
    }

    // ------------------------------------------------------------------ lookup

    private static View byId(View root, String name) {
        if (root == null) return null;
        try {
            final int id = root.getContext().getResources()
                    .getIdentifier(name, "id", "com.android.systemui");
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** What the header is holding, for the shade diagnostic. */
    static String describe() {
        if (sClockShift == 0 && sCarrierShift == 0) return "off";
        return (sArmed ? "armed" : "idle")
                + "/c" + sClockShift + " r" + sCarrierShift
                + " ty=" + ty(sClock) + "," + ty(sDate) + "," + ty(sCarrier)
                + " added=" + sApplied[CLOCK] + "," + sApplied[DATE] + "," + sApplied[CARRIER];
    }

    private static String ty(View v) {
        return v == null ? "-" : String.valueOf(Math.round(v.getTranslationY()));
    }
}
