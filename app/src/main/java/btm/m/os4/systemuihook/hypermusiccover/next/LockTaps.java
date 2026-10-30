// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import java.util.List;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Taps on the lock screen itself: the single tap that toggles the cover, held until a double
 * tap has had its chance to claim it, and the two-finger tap that swaps the cover and the
 * lyrics, with the trail it keeps for `op mini`.
 *
 * Carved out of Main as it was (2026-10-14, after ClockViews): every member moved whole with its
 * comments, references across the line prefixed with Main. or LockTaps., nothing else changed.
 */
public final class LockTaps {
    private LockTaps() {}

    static GestureDetector sTapDetector;

    /**
     * How long a tap on the cover is held back before it means anything.
     *
     * GestureDetector reports a single tap the moment the finger lifts, and a fast double tap
     * is two gestures: the first one had already toggled the cover by the time the second
     * arrived, and MIUI's own double-tap-to-sleep is decided downstream of this hook
     * (KeyguardPanelViewInjector, over com.android.keyguard's DoubleTapHelper), so it cannot be
     * asked first. So the tap is armed instead and fired only once the double tap window has
     * passed. Reported as "double tap the lock screen and the wallpaper state has changed when
     * it comes back on".
     *
     * The framework's own timeout plus a margin. GestureDetector measures the first DOWN to the
     * second, MIUI measures the gap between the two taps, so the window it might still call a
     * double tap can end later than ours - the margin is that difference. Not a guess to be
     * trimmed without measuring first.
     */
    static final long TAP_CONFIRM_MS =
            android.view.ViewConfiguration.getDoubleTapTimeout() + 60L;

    /** Armed by a tap on the cover, cancelled while it is still a candidate double tap. */
    static Runnable sPendingTap;

    /**
     * Feeds the lock screen's touch stream to a detector without taking part in it.
     *
     * GestureDetector needs the whole DOWN..UP sequence, which is why this hangs off the shade
     * window's dispatchTouchEvent rather than any one child: it is the single point every event
     * passes through before the OEM decides what to do with it. The detector's answer is
     * discarded - consuming a DOWN here would take swipe-to-unlock with it.
     *
     * What it reports is not acted on the moment it reports it: a tap on the cover is armed and
     * fires only once it can no longer be the first half of a double tap. See armLockTap.
     */
    static void feedTap(MotionEvent ev) {
        if (ev == null) return;
        feedTwoFingerTap(ev);
        if (!Main.sTapToggle) return;
        // A gesture the system took away is never going to produce the second tap.
        if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelPendingTap("gesture cancelled");
        }
        if (sTapDetector == null) {
            Context c = Main.sAppCtx;
            if (c == null) return;
            // Constructed from inside the dispatch, so the looper it picks up is the one the
            // lock screen draws on, which is where the callback has to land.
            sTapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    armLockTap(e.getRawY());
                    return false;
                }

                /**
                 * Fired on the second DOWN, which is the whole point: two taps this close are
                 * the user asking the lock screen to sleep, not asking the cover to leave.
                 */
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    cancelPendingTap("second tap");
                    return false;
                }
            });
        }
        try {
            sTapDetector.onTouchEvent(ev);
        } catch (Throwable ignored) {
        }
    }

    // ---- the two-finger tap: the lock screen's lyrics switch
    /** Where the two fingers went down, and when; 0 = no candidate in progress. */
    static long sTwoDownAt;

    static float sTwoX0, sTwoY0, sTwoX1, sTwoY1;

    /** Replaced by the framework's own slop as soon as there is a context to ask. */
    static float sTwoSlop = 48f;

    /** The system cancelled this gesture. Watched on, not given up - see ACTION_CANCEL below. */
    static boolean sTwoCancelled;

    /** Diagnostics for `op twotap`: how many two-finger downs were seen, and what became of them. */
    static int sTwoSeen, sTwoFired;

    static String sTwoWhy = "nothing yet";

    static int sTwoMaxPointers;

    /**
     * Two fingers tapped at once toggles the lyrics.
     *
     * Every other gesture on the cover is taken: one tap is the cover itself, two in a row is the
     * OEM's sleep, a long press its lock screen editor. This one is free, and being anywhere on
     * the screen it needs no control of its own.
     *
     * The single-tap detector is not disturbed by it: the lock screen cancels its own gesture the
     * moment the second finger lands, and feedTap passes that cancel on to cancelPendingTap.
     */
    static void feedTwoFingerTap(MotionEvent ev) {
        if (ev.getPointerCount() > sTwoMaxPointers) sTwoMaxPointers = ev.getPointerCount();
        noteTwoAction(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sTwoDownAt = 0L;
                sTwoCancelled = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                // Not the end of the touch stream here, and not a reason to drop the candidate.
                // The lock screen cancels as soon as the second finger lands - measured trail
                // DdXuU, with no move in it at all (2026-09-16) - and then delivers the real
                // POINTER_UP and UP to this window anyway. Giving up on the cancel is what made
                // all eight two-finger taps of that recording do nothing. A gesture the system
                // has genuinely taken away is kept from firing by the slop and the long-press
                // timeout below, both of which still apply, and by the next DOWN clearing this.
                if (sTwoDownAt != 0L) sTwoCancelled = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() != 2) {
                    // A third finger: not this gesture.
                    sTwoDownAt = 0L;
                    sTwoWhy = "a third finger (" + ev.getPointerCount() + ")";
                    break;
                }
                sTwoSeen++;
                sTwoWhy = "two fingers down";
                sTwoCancelled = false;
                sTwoDownAt = ev.getEventTime();
                sTwoX0 = ev.getX(0);
                sTwoY0 = ev.getY(0);
                sTwoX1 = ev.getX(1);
                sTwoY1 = ev.getY(1);
                if (Main.sAppCtx != null) {
                    sTwoSlop = android.view.ViewConfiguration.get(Main.sAppCtx).getScaledTouchSlop()
                            * 1.5f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (sTwoDownAt == 0L || ev.getPointerCount() < 2) break;
                if (moved(ev.getX(0), ev.getY(0), sTwoX0, sTwoY0)
                        || moved(ev.getX(1), ev.getY(1), sTwoX1, sTwoY1)) {
                    // A pinch, a two-finger swipe, a scroll: not a tap.
                    sTwoDownAt = 0L;
                    sTwoWhy = "moved more than " + Math.round(sTwoSlop) + "px";
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (sTwoDownAt == 0L) break;
                long held = ev.getEventTime() - sTwoDownAt;
                sTwoDownAt = 0L;
                if (held <= android.view.ViewConfiguration.getLongPressTimeout()) {
                    onTwoFingerTap();
                } else {
                    sTwoWhy = "held " + held + "ms, too long";
                }
                break;
            default:
                break;
        }
    }

    /** The last gesture's actions, in order, for `op twotap`. */
    static final StringBuilder sTwoTrail = new StringBuilder();

    /**
     * The last gesture that had a second finger in it, kept whole. The live trail is overwritten
     * by whatever is touched next, and a single tap on the way to reading it used to take the
     * only record of the gesture being diagnosed with it.
     */
    static String sTwoTrailLast = "";

    static void noteTwoAction(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (sTwoTrail.indexOf("d") >= 0) sTwoTrailLast = sTwoTrail.toString();
            sTwoTrail.setLength(0);
        }
        if (a == MotionEvent.ACTION_MOVE && sTwoTrail.length() > 0
                && sTwoTrail.charAt(sTwoTrail.length() - 1) == 'm') {
            return;
        }
        if (sTwoTrail.length() > 40) return;
        sTwoTrail.append(a == MotionEvent.ACTION_DOWN ? "D"
                : a == MotionEvent.ACTION_POINTER_DOWN ? "d"
                : a == MotionEvent.ACTION_MOVE ? "m"
                : a == MotionEvent.ACTION_POINTER_UP ? "u"
                : a == MotionEvent.ACTION_UP ? "U"
                : a == MotionEvent.ACTION_CANCEL ? "X" : "?");
    }

    static boolean moved(float x, float y, float fromX, float fromY) {
        float dx = x - fromX, dy = y - fromY;
        return dx * dx + dy * dy > sTwoSlop * sTwoSlop;
    }

    /**
     * Swaps the cover and the lyrics on the lock screen, under the same guards as the cover's
     * own tap: only the lock screen itself, not the bouncer, the control centre or a shade
     * pulled down over an unlocked phone, and only while a track is on the card - there is
     * nothing to show without one.
     *
     * It is a view and not a setting: the app's switch and the state file are left alone, and
     * with the switch off the tap does nothing at all. See LockLyrics.toggleByTap.
     */
    static void onTwoFingerTap() {
        String why = Main.toggleLyricPage();
        if (why == null) sTwoFired++;
        else sTwoWhy = why;
    }

    /**
     * Holds a tap on the cover back until it cannot be the first half of a double tap.
     *
     * This decides when the tap runs, not what it does: onLockTap re-reads the screen, the
     * keyguard and the card when it fires, so a screen that went off inside the window drops
     * the tap on its own - which is what covers a build whose double tap window is longer than
     * ours, and every double tap the framework itself did not recognise as one.
     */
    static void armLockTap(final float y) {
        if (!Main.sTapToggle) return;
        // Decided at the DOWN, because by the time this runs the centre - or the charging
        // animation - may have closed under the guard in onLockTap. See the dispatch hook for
        // the full account.
        if (Main.sGestureOnCentre || ChargeAnim.sGestureOnCharge) return;
        flushPendingTap();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sPendingTap = null;
                onLockTap(y);
            }
        };
        sPendingTap = r;
        Main.main().postDelayed(r, TAP_CONFIRM_MS);
    }

    /**
     * Runs the tap that was waiting, because a new one has been armed on top of it.
     *
     * Reaching a second tap at all means the framework decided the first was not half of a
     * double tap - it cancels the waiting one on the second DOWN when it is. So the wait is
     * over: firing it here is what keeps "tap, pause, tap" meaning two toggles rather than the
     * second tap quietly replacing the first.
     */
    static void flushPendingTap() {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        Main.main().removeCallbacks(r);
        r.run();
    }

    /** A held-back tap that has stopped being one: log what ended it rather than guess later. */
    static void cancelPendingTap(String why) {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        Main.main().removeCallbacks(r);
        Xp.log(Main.TAG + "held-back cover tap dropped (" + why + ")");
    }

    /**
     * A tap on the cover toggles cover mode.
     *
     * "On the cover" is everything between the status bar and the top of the media card. The
     * card and everything below it belongs to the OEM - the transport buttons, the notification
     * list, the two shortcuts in the corners - and a tap down there still means what it always
     * meant. The card rectangle is the one sampled for the app's preview, and the fallback is a
     * fraction of the screen rather than a pixel count because it stands in for a measurement
     * this device has simply not taken yet.
     */
    static void onLockTap(float y) {
        if (!Main.sTapToggle) return;
        if (!Main.screenOn() || !Main.keyguardShowing()) return;
        // Only the keyguard shows the clock container, so this is also what rules out the shade
        // being pulled down over an unlocked phone - the same test the card restyle uses.
        View c = Main.sContainer;
        if (c == null || !c.isShown()) return;
        // The pad is up, so the taps on it are its own.
        if (Main.bouncerUp()) return;
        // The control centre covers the same region without hiding the clock, so none of the
        // guards above see it: a tap aimed at a quick toggle must not toggle the cover.
        if (Main.controlCenterUp()) return;
        // And the charging animation, which is a full-screen view over the same region. This is
        // the second half of the pair - a tap that began before the animation was up is caught
        // here, one that began under it was caught at the DOWN.
        if (ChargeAnim.chargeAnimUp()) return;
        // Nothing to toggle without music: the card is the switch, and this only chooses
        // whether the cover follows it.
        if (!MediaCard.sCardKnown || !MediaCard.sCardShowing) return;
        // An immersive page is the picture here, not the cover: a tap on it is a tap on the
        // page, and it put the cover and its card up over the map (user, 2026-09-29). The page
        // is left by its island, as it was opened.
        if (ImmersiveHost.holdsClock()) return;
        float top = Main.sScreenH * 0.08f;
        // Measured live where possible. The sampled rectangle is a settled reading taken for
        // the app's preview and it lags: seen at 1360 while the card was actually at 1700,
        // which as a boundary would be 340px of cover that answers no tap at all.
        float bottom;
        View card = MediaCard.sCardGuarded;
        if (card != null && card.isShown() && card.getHeight() > 0) {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            bottom = loc[1];
        } else {
            bottom = MediaCard.sCardT > Main.sScreenH / 3 ? MediaCard.sCardT : Main.sScreenH * 0.62f;
        }
        if (y < top || y > bottom) return;
        if (Main.sCoverMode) {
            Main.exitFromTap("tap at y=" + y);
        } else if (Main.sTapSuppressed) {
            Main.enterFromTap("tap at y=" + y);
        }
    }

    static void hookLockScreenTap(final ClassLoader cl) {
        // A tap on the cover puts the wallpaper back.
        //
        // Hooked on the shade window's dispatch rather than given a view of our own: anything
        // clickable added over the keyguard swallows the ACTION_DOWN in its region, and
        // swipe-to-unlock is a drag that begins with exactly that ACTION_DOWN. Here the events
        // are only observed - the detector's answer is thrown away and proceed() runs
        // unconditionally - so every gesture still reaches the OEM as it did before.
        try {
            Class<?> shade = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowView", cl);
            Xp.hookAll("sysui.taps.shade_dispatch", shade, "dispatchTouchEvent", chain -> {
                MotionEvent ev = null;
                try {
                    java.util.List<Object> a = chain.getArgs();
                    if (!a.isEmpty() && a.get(0) instanceof MotionEvent) {
                        ev = (MotionEvent) a.get(0);
                    }
                } catch (Throwable ignored) {
                }
                if (ev != null) {
                    // A touch on the lit lock screen counts as the user being there.
                    Main.touchKeepsAwake();
                    // Whether this gesture began on the control centre, decided at the DOWN and
                    // nowhere else.
                    //
                    // onLockTap's own controlCentreUp() guard is evaluated a double-tap window
                    // later, and by then the centre can be gone: swiping up to dismiss it takes
                    // the centre out from under the check, so it read false and the tap went
                    // through as a tap on the cover. Measured symptom - dragging up at the clock
                    // and date left cover mode instead of closing the centre.
                    //
                    // At the DOWN the answer is unambiguous: a finger that lands while the centre
                    // is open is aimed at the centre, whatever the gesture turns out to be.
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        Main.sGestureOnCentre = Main.controlCenterUp();
                        if (Main.sGestureOnCentre) cancelPendingTap("gesture began on the control centre");
                        // The charging animation, for the same reason and at the same point: a
                        // tap on it is what dismisses it, and asking later would be asking after
                        // it had gone. See chargeAnimUp.
                        ChargeAnim.sGestureOnCharge = ChargeAnim.chargeAnimUp();
                        if (ChargeAnim.sGestureOnCharge) cancelPendingTap("gesture began on the charging animation");
                    }
                    // A tap on the open immersive page's own target in its row - 高德's turn
                    // arrow - is the page's, and the row must not see it. See
                    // ImmersiveHost.routeTouch.
                    try {
                        if (ImmersiveHost.routeTouch(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    // The one case this hook does more than watch. Returning true without
                    // proceeding takes the gesture out of the dispatch entirely, which is the
                    // only way the artwork can mean something other than "open the player".
                    // A gesture that starts on the mini player is the mini player's alone: its
                    // swipe up would otherwise also be swipe-to-unlock.
                    try {
                        if (MiniPlayerRuntime.routeTouch(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    // A downward swipe on the media card folds it back into the mini player. Seen
                    // before the art tap, which it overrides; the moment it is recognised the
                    // rest of the tree gets a CANCEL, so the shade does not also start opening.
                    try {
                        int swipe = CardTouch.cardSwipe(ev);
                        if (swipe == CardTouch.SWIPE_FIRED) {
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            try {
                                chain.proceed(new Object[]{cancel});
                            } finally {
                                cancel.recycle();
                            }
                            return Boolean.TRUE;
                        }
                        if (swipe == CardTouch.SWIPE_HELD) return Boolean.TRUE;
                    } catch (Throwable t) {
                        MiniPlayerRuntime.noteTouch("card swipe failed: " + t);
                    }
                    // The card's page button, ahead of the artwork for no reason but that the two
                    // never overlap. Taken whole for the same reason the artwork's is: under it
                    // is the card, whose own click opens the player.
                    try {
                        if (Main.swallowPageTap(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (CardTouch.swallowTitleTap(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (Main.swallowArtTap(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    feedTap(ev);
                }
                return chain.proceed();
            });
            Xp.log(Main.TAG + "lock screen tap hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "lock screen tap hook failed: " + t);
        }
    }
}
