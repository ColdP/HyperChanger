// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import android.graphics.Rect;
import android.view.Choreographer;
import android.view.View;

/**
 * The room the clock leaves for the notification rows: how much is wanted for the rows now,
 * where the system would put the clock unobstructed, and the eased, soft-started move between
 * them - reasserted when the OEM lays the clock out again over it.
 *
 * Carved out of Main as it was (2026-10-14, after LockTaps): every member moved whole with its
 * comments, references across the line prefixed with Main. or ClockRoom., nothing else changed.
 */
public final class ClockRoom {
    private ClockRoom() {}

    /** Room re-evaluation uses the OEM's animation channel without replacing its source Y. */
    static boolean sRoomReasserting;

    /**
     * The clock gives way to the notifications only as far as they really reach: the stack's
     * own figure keeps room for one big notification whatever is there (NotificationStacking
     * Interactor.calculateKeyguardNotifTop), so a single short row by the pill shrank a clock
     * it never came near (filmed 2026-09-25). Never higher than the system asked: rows that do
     * reach up still push it as they always did.
     */
    static float roomForRows(float requested) {
        float room = roomForRowsNow(requested);
        // An empty stack must immediately give back the edited clock position. Easing from
        // an old notification constraint across a doze can otherwise retain a squeezed pose.
        if (sRoomUnobstructed) return resetClockRoom(room);
        return easeRoom(room);
    }

    /** roomForRows at once, for a caller that eases its own way there (ClockCollapse.exitY). */
    static float roomForRowsNow(float requested) {
        // The yes/no that goes with this y, re-thrown if the rows changed under it.
        NotifPresence.recheck();
        sRoomUnobstructed = requested >= Float.MAX_VALUE / 2f;
        if (Float.isNaN(requested) || requested >= Float.MAX_VALUE / 2f) return requested;
        float top;
        try {
            top = MiniPlayerRuntime.stackContentTop();
        } catch (Throwable t) {
            return requested;
        }
        boolean nativeEmpty = systemClockUnobstructed();
        sRoomUnobstructed = nativeEmpty || top == Float.MAX_VALUE;
        // No row in sight - the media card is kept in the stack INVISIBLE behind the media island,
        // and island rows the same - and a style that is not all_in_one: tell it what the system
        // tells it when there are no notifications at all. These faces do not shrink with the y,
        // they swap to a separate notification layout for ANY finite one (oriental A drops to its
        // small clock with the date over it), so the boundary y below, which is all all_in_one
        // needs, folded the clock for an island that covers nothing.
        if (!nativeEmpty && top == Float.MAX_VALUE && !ClockViews.anchoredStyle()) {
            ClockMove.noteRoom(requested, top, Float.MAX_VALUE);
            return Float.MAX_VALUE;
        }
        float out = nativeEmpty ? requested : ClockRoomPolicy.resolve(
                requested, top, top == Float.MAX_VALUE ? unobstructedClockY() : Float.NaN);
        ClockMove.noteRoom(requested, top, out);
        return out;
    }

    static boolean systemClockUnobstructed() {
        try {
            Object it = Main.sContainer == null ? null : Xp.getObjectField(Main.sContainer, "keyguardClockNotifInteractor");
            return it != null && ((Number) Xp.getObjectField(it, "rawNotifY")).floatValue() == Float.MAX_VALUE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Same empty-notification boundary as the OEM's correctYValue, including edited clocks. */
    static float unobstructedClockY() {
        try {
            View v = Main.sContainer;
            if (v == null) return Float.NaN;
            Object it = Xp.getObjectField(v, "keyguardClockNotifInteractor");
            Rect rect = (Rect) Xp.getObjectField(it, "maxEditRect");
            if (rect != null && rect.bottom > 0)
                return (float) rect.bottom + ((Number) Xp.getObjectField(it, "clockNotificationMargin")).floatValue();
            return v.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    static float resetClockRoom(float y) {
        if (sRoomEasing) Gen.unframe(ROOM_FRAME);
        sRoomShown = sRoomRaw = y;
        sRoomEasing = false;
        sRoomSoft = 0f;
        return y;
    }

    /**
     * The room given to the clock, eased when it jumps. What is given replaces whatever the
     * clock's own animation was carrying (setNotifY is where it lands), so the OEM's easing
     * never reached our changes: a card switched in or out cut the clock from one size to the
     * other in a frame (2026-09-26). A jump - the rows changing under it, not the OEM's own
     * animation stepping, which moves it a little a frame - is followed over ~300ms instead,
     * the clock asked again every frame meanwhile (reassertClockRoom) until it is there.
     *
     * Except for the first stretch of a jump that GIVES the clock room back, which is walked
     * slowly instead (SOFT_STEP_PX). The clock only answers to the last ~40px of the y before it
     * is fully open, and an exponential ease is fastest at the start - so pulling the media island
     * back down, those 40px went by in a single frame while the same 40px on the way up took 22
     * (measured 2026-09-29, `op clockmove`). Entering that band never had the problem: the ease is
     * slowest where it ends, which is where the band is. So only the growing direction is changed,
     * and only its opening: what is walked slowly up there is invisible either way, because the
     * clock is already fully open until the y comes back down to it.
     */
    static float easeRoom(float raw) {
        if (Float.isNaN(raw) || raw >= Float.MAX_VALUE / 2f || !Main.sScreenOn) {
            sRoomShown = raw;
            sRoomRaw = raw;
            sRoomEasing = false;
            sRoomSoft = 0f;
            return raw;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (Float.isNaN(sRoomShown) || Float.isNaN(sRoomRaw)) {
            sRoomShown = raw;
            sRoomRaw = raw;
            return raw;
        }
        boolean jump = Math.abs(raw - sRoomRaw) > ROOM_JUMP_PX;
        ClockMove.noteEase(jump);
        sRoomRaw = raw;
        if (!sRoomEasing) {
            if (!jump) {
                sRoomShown = raw;
                return raw;
            }
            sRoomEasing = true;
            sRoomAt = now;
            // Only a jump that gives room back starts slowly; see the note above.
            sRoomSoft = raw > sRoomShown ? SOFT_START_PX : 0f;
            Gen.unframe(ROOM_FRAME);
            Gen.frame(ROOM_FRAME);
            return sRoomShown;
        }
        float dt = Math.min(50f, now - sRoomAt);
        sRoomAt = now;
        float step = (raw - sRoomShown) * (1f - (float) Math.exp(-dt / ROOM_TAU_MS));
        if (sRoomSoft > 0f) {
            float soft = Math.min(Math.abs(step), Math.min(SOFT_STEP_PX, sRoomSoft));
            sRoomShown += Math.signum(raw - sRoomShown) * soft;
            sRoomSoft -= soft;
        } else {
            sRoomShown += step;
        }
        if (Math.abs(raw - sRoomShown) < 1f) {
            sRoomShown = raw;
            sRoomEasing = false;
            sRoomSoft = 0f;
        }
        return sRoomShown;
    }

    /**
     * How much of a jump that gives room back is walked at SOFT_STEP_PX a frame.
     *
     * Sized for the band measured here - 40px - with a little over. It is a distance, not a
     * duration, so on a style whose band is wider it covers part of it and the rest is left to
     * the ease as before: a partial fix, never a worse one. And a style whose band is narrower
     * spends the extra only where the clock is already fully open, which is time and nothing else.
     */
    static final float SOFT_START_PX = 60f;

    static final float SOFT_STEP_PX = 5f;

    static float sRoomSoft;

    /** The ease's own state, for the clockmove probe. */
    static boolean easeRunning() {
        return sRoomEasing;
    }

    static float easeShown() {
        return sRoomShown;
    }

    /** How much of the soft start is still to be walked, for the clockmove probe. */
    static float easeSoft() {
        return sRoomSoft;
    }

    static float sRoomShown = Float.NaN, sRoomRaw = Float.NaN;

    static boolean sRoomUnobstructed;

    static long sRoomAt;

    static boolean sRoomEasing;

    /** A change of room bigger than this in one go is a jump, eased (easeRoom). */
    static final float ROOM_JUMP_PX = 24f;

    /** The ease's time constant: nine tenths of the way in ~160ms, there by ~300ms. */
    static final float ROOM_TAU_MS = 70f;

    /** Every frame of an ease: the clock asked again, which brings it the next step. */
    static final android.view.Choreographer.FrameCallback ROOM_FRAME =
            new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!sRoomEasing) return;
            if (Main.sHoldY != null || !Main.sScreenOn) {
                sRoomEasing = false;
                return;
            }
            reassertClockRoom();
            if (sRoomEasing) Gen.frame(this);
        }
    };

    /**
     * Retirement: a walk that was in flight ends here. Its frame callback is Gen's and the sweep
     * removes it, and sRoomEasing is what would keep the next ease from ever arming another.
     * The shown/raw pair is cleared so the next generation seeds from where the clock now is.
     */
    static void retire() {
        Gen.unframe(ROOM_FRAME);
        sRoomEasing = false;
        sRoomSoft = 0f;
        sRoomShown = Float.NaN;
        sRoomRaw = Float.NaN;
        sRoomUnobstructed = false;
    }

    /**
     * Re-evaluate room through the clock's animation channel, with the source Y left intact.
     * A small nudge passes its equal-value early-out; roomForRows supplies the current boundary.
     */
    public static void reassertClockRoom() {
        if (Main.sHoldY != null) return;
        if (systemClockUnobstructed()) {
            resetClockRoom(Main.sLastSystemY);
            return;
        }
        final View v = Main.sContainer;
        if (v == null || Main.sNotifStateChange == null) return;
        try {
            Object interactor = Xp.getObjectField(v, "keyguardClockNotifInteractor");
            Object flow = interactor == null ? null : Xp.getObjectField(interactor, "_notificationYState");
            if (flow == null) return;
            Object triple = Xp.callMethod(flow, "getValue");
            if (triple == null) return;
            float y = ((Number) Xp.callMethod(triple, "getFirst")).floatValue();
            if (!(y < Float.MAX_VALUE / 2f)) return;
            sRoomNudge = -sRoomNudge;
            // Its second is whether the clock jumps (ClockBaseAnimation.notifStateChange: true
            // -> Folme setTo, false -> doNotifyHeightChangeAnimation). Copied from the system's
            // last, which follows the stack's list scroll frame by frame and so says jump, the
            // clock cut from one size to the other on every switch between the stack island's
            // list and a card (2026-09-26). Ours is always a change of rows: animated.
            // Do not publish a fabricated notification position to the shared StateFlow.
            // Its other consumers and the next sleep/wake must retain the system's own Y.
            boolean wasReasserting = sRoomReasserting;
            sRoomReasserting = true;
            try {
                Main.sNotifStateChange.invoke(v, y + sRoomNudge, false, Xp.callMethod(triple, "getThird"));
            } finally {
                sRoomReasserting = wasReasserting;
            }
            ClockMove.noteReassert(y, true);
        } catch (Throwable t) {
            ClockMove.noteReassert(Float.NaN, false);
        }
    }

    static float sRoomNudge = 0.01f;
}
