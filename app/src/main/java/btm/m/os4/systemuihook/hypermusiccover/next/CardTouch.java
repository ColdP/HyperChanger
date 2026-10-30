// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerCollapseGesture;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.text.Layout;
import android.widget.TextView;
import android.view.MotionEvent;
import android.view.View;

/**
 * Touches on the media card: the title's tap that plays and pauses, and the swipe down that
 * folds the card (and the stack with it) - with the reading of whether the keyguard can fold.
 *
 * Carved out of Main as it was (2026-10-14, after ClockGlass): every member moved whole with its
 * comments, references across the line prefixed with Main. or CardTouch., nothing else changed.
 */
public final class CardTouch {
    private CardTouch() {}

    /**
     * Arms the card's title as a play/pause button, or disarms it.
     *
     * Asserted from the guard rather than set once when the card was found, because the same
     * view is also the shade's: the lock screen's title is ours and the shade's is the OEM's,
     * and the guard is the only thing here that knows which of the two is on screen.
     *
     * Only a note of which view is armed. The tap itself is taken at the window's dispatch, on
     * the words alone - see swallowTitleTap. It used to be an OnClickListener on the view, and
     * the view is far wider than its words: centring moves the whole of it by translationX, and
     * with a short title that put its right end over the output chip, which comes before it in
     * the touch order. Tapping the chip paused the music (user, 2026-10-14).
     */
    static void applyTitleTap(TextView title, boolean on) {
        Main.sCardTitleTapped = on ? title : null;
    }

    static boolean sTitleSwallow;

    static long sTitleDownAt;

    /**
     * The title's play/pause, on the line of text as it is drawn - translation included, so
     * the centred words and nothing either side of them. Whole gesture, acted on at a brief UP
     * that stayed inside, as the artwork's is; anything else on the card is left to the OEM.
     */
    static boolean swallowTitleTap(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            sTitleSwallow = Main.sCardTitleTapped != null
                    && Main.screenOn() && Main.keyguardShowing() && Main.onKeyguardNow()
                    && !Main.bouncerUp() && !Main.sGestureOnCentre && !ChargeAnim.sGestureOnCharge
                    && titleInkContains(ev.getRawX(), ev.getRawY());
            if (sTitleSwallow) sTitleDownAt = android.os.SystemClock.uptimeMillis();
            return sTitleSwallow;
        }
        if (!sTitleSwallow) return false;
        if (action == MotionEvent.ACTION_UP) {
            sTitleSwallow = false;
            long held = android.os.SystemClock.uptimeMillis() - sTitleDownAt;
            if (held < Main.ART_TAP_MS && titleInkContains(ev.getRawX(), ev.getRawY())) {
                togglePlayback();
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            sTitleSwallow = false;
        }
        return true;
    }

    static boolean titleInkContains(float x, float y) {
        View v = Main.sCardTitleTapped;
        if (!(v instanceof TextView) || !v.isShown() || v.getHeight() <= 0) return false;
        TextView t = (TextView) v;
        Layout lay = t.getLayout();
        if (lay == null || lay.getLineCount() < 1) return false;
        int[] loc = new int[2];
        t.getLocationOnScreen(loc);
        float left = loc[0] + t.getPaddingLeft() + lay.getLineLeft(0);
        float right = left + lay.getLineWidth(0);
        return x >= left - Main.ART_TAP_SLOP && x < right + Main.ART_TAP_SLOP
                && y >= loc[1] - Main.ART_TAP_SLOP && y < loc[1] + t.getHeight() + Main.ART_TAP_SLOP;
    }

    /**
     * Play or pause whatever the card is showing.
     *
     * Through the session the card is bound to rather than the media-key broadcast the OEM's
     * own button ends up sending: same result when nothing else is going on, but it cannot
     * drift onto a different player, which a key event can when two of them are open.
     * The state is read rather than toggled blind because the transport controls have play()
     * and pause() and no playPause() of their own.
     */
    static void togglePlayback() {
        MediaController c = Main.sWatched;
        if (c == null) {
            Main.rebindSession();
            c = Main.sWatched;
        }
        if (c == null) {
            Xp.log(Main.TAG + "title tap: no session to play");
            return;
        }
        try {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            MediaController.TransportControls t = c.getTransportControls();
            if (t == null) {
                Xp.log(Main.TAG + "title tap: no transport controls");
                return;
            }
            if (playing) t.pause();
            else t.play();
            Xp.log(Main.TAG + "title tap -> " + (playing ? "pause" : "play")
                    + " on " + c.getPackageName());
        } catch (Throwable t) {
            Xp.w(Main.TAG + "title tap failed: " + t);
        }
    }

    /**
     * Where the card's thumbnail is on screen right now.
     *
     * NOT getLocationOnScreen() on the artwork. That maps (0,0) through the view's own matrix
     * before adding mLeft, and this view has a matrix: measured, album_art reports an origin
     * exactly its own width (158px) to the right of its layout slot, with translationX 0 and
     * scaleX 1 - a horizontal mirror, the OEM's own. The reported rectangle therefore lands on
     * the title, which is what "tapping the title expands it, not the thumbnail" was.
     *
     * A mirror does not move what the user sees, so the layout slot IS the visible rectangle.
     * Taking it from the card - which carries no transform of its own - plus the child's
     * getLeft()/getTop() sidesteps the artwork's matrix while still picking up every ancestor's.
     */
    static final int SWIPE_NONE = 0, SWIPE_FIRED = 1, SWIPE_HELD = 2;

    static boolean sCardSwipeFired;

    /** The pull down, told apart from a scroll of the lock screen's list (#12). */
    static final MiniPlayerCollapseGesture sCardCollapse = new MiniPlayerCollapseGesture();

    /** Started on the card or a let-out row; the landing pin let go once it scrolls instead. */
    static boolean sCardScrollTarget, sCardPinReleased;

    /**
     * The fired swipe is the keyguard's too: it goes on reaching the stack, which folds its
     * notifications away under the same pull, as it did before there was a pill to go back to.
     */
    static boolean sCardSwipeShared;

    static boolean sCardSwipeSpread;

    /** The swipe started on a notification's row that came out of the row of islands. */
    static String sCardSwipeRow;

    static float sCardSwipeX, sCardSwipeY;

    /**
     * The media card's half of the dynamic switch: a drag that starts on the card and goes
     * down, more down than sideways, past the touch slop. Horizontal drags (the progress bar)
     * and taps (the buttons) never qualify, and are left alone from the DOWN on.
     */
    static int cardSwipe(MotionEvent ev) { android.os.Trace.beginSection("MC t.cardSwipe"); try {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (sCardSwipeFired && sCardSwipeSpread) {
                    MiniPlayerRuntime.endSpreadCollapse(ev, true);
                }
                sCardSwipeFired = false;
                sCardSwipeShared = false;
                sCardSwipeSpread = !Main.sGestureOnCentre && !ChargeAnim.sGestureOnCharge
                        && MiniPlayerRuntime.spreadCollapseAt(ev.getRawX(), ev.getRawY());
                sCardSwipeRow = null;
                sCardPinReleased = false;
                boolean onCard = MiniPlayerRuntime.wantsNativeCardSwipe()
                        && !Main.sGestureOnCentre && !ChargeAnim.sGestureOnCharge
                        && MediaCard.cardRectContains(ev.getRawX(), ev.getRawY());
                if (!onCard && !sCardSwipeSpread && !Main.sGestureOnCentre && !ChargeAnim.sGestureOnCharge) {
                    sCardSwipeRow = MiniPlayerRuntime.releasedRowAt(ev.getRawX(), ev.getRawY());
                }
                sCardScrollTarget = onCard || sCardSwipeSpread || sCardSwipeRow != null;
                // A list scrolled away from its top is read back up by this gesture, not pulled
                // home: the rest boundary is read here, once, for the whole of it.
                boolean atTop = MiniPlayerRuntime.nativeStackAtTop(true);
                sCardCollapse.start(sCardScrollTarget && atTop);
                sCardSwipeX = ev.getRawX();
                sCardSwipeY = ev.getRawY();
                // For `op mini`: why a pull on the card did or did not take. A pull down in the
                // cover once did nothing until the player was restarted (2026-09-25), and nothing
                // said which of these it was.
                MiniPlayerRuntime.noteTouch("card down armed=" + sCardCollapse.getArmed()
                        + " top=" + atTop + " scroll=" + MiniPlayerRuntime.nativeScrollTrace()
                        + " want=" + MiniPlayerRuntime.wantsNativeCardSwipe()
                        + " centre=" + Main.sGestureOnCentre + " charge=" + ChargeAnim.sGestureOnCharge
                        + " cover=" + Main.sCoverMode + " card=" + MediaCard.describeCardRect()
                        + " row=" + sCardSwipeRow);
                return SWIPE_NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sCardSwipeFired) {
                    if (sCardSwipeSpread) MiniPlayerRuntime.moveSpreadCollapse(ev);
                    else MiniPlayerRuntime.dragMove(ev);
                    return sCardSwipeShared ? SWIPE_NONE : SWIPE_HELD;
                }
                if (!sCardScrollTarget) return SWIPE_NONE;
                float dx = ev.getRawX() - sCardSwipeX, dy = ev.getRawY() - sCardSwipeY;
                float slop = android.view.ViewConfiguration.get(Main.sAppCtx).getScaledTouchSlop();
                boolean pull = sCardCollapse.getArmed()
                        && sCardCollapse.move(dx, dy, slop, MiniPlayerRuntime.nativeStackAtTop(false));
                if (!pull) {
                    // Scrolling it: a card held where it landed goes with the list from here,
                    // or it stays behind while the rows around it move.
                    if (!sCardPinReleased && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                        sCardPinReleased = true;
                        MiniPlayerRuntime.releaseNativeScrollPin();
                    }
                    return SWIPE_NONE;
                }
                sCardSwipeFired = true;
                Main.sArtSwallow = false;
                if (sCardSwipeSpread) {
                    if (!MiniPlayerRuntime.beginSpreadCollapse(ev, sCardSwipeY)) {
                        sCardSwipeFired = false;
                        return SWIPE_NONE;
                    }
                    return SWIPE_FIRED;
                }
                if (sCardSwipeRow != null) {
                    // A notification the row of islands let out goes back into it. Refused
                    // (a morph already going), the gesture is the list's.
                    String key = sCardSwipeRow;
                    sCardSwipeRow = null;
                    if (!MiniPlayerRuntime.collapseRow(key, ev)) {
                        sCardSwipeFired = false;
                        return SWIPE_NONE;
                    }
                    return SWIPE_FIRED;
                }
                // With notifications to fold, the pull is not cancelled out from under the
                // stack, in the cover as out of it. Without any, it still is: there the
                // stack's own answer to a pull down is to start opening the shade.
                // Nor when the row of islands takes them: their rows come down with the card,
                // each into its own island, and folded they had nothing to come out of.
                sCardSwipeShared = keyguardCanFoldNotifications()
                        && !MiniPlayerRuntime.islandsTakeRows();
                MiniPlayerRuntime.noteTouch("card swipe fired cover=" + Main.sCoverMode
                        + " shared=" + sCardSwipeShared);
                if (Main.sCoverMode) {
                    // Out of the cover or the lyrics, landing on the pill: the scene exit
                    // shrinks the card into it.
                    MiniPlayerRuntime.preferMini();
                    MiniPlayerRuntime.rememberScene();
                    // The lyrics go the same way, shrinking into the island's place.
                    LockLyrics.notePopTarget(MiniPlayerRuntime.musicIslandHomeOnScreen());
                    Main.exitFromTap("media card swiped down");
                } else if (!MiniPlayerRuntime.beginDrag(true, ev)) {
                    // No geometry to pull: the switch still happens, on its own spring.
                    MiniPlayerRuntime.onNativeCardSwipeDown();
                }
                Xp.log(Main.TAG + "media card swiped down"
                        + (sCardSwipeShared ? ", shared with the notification fold" : ""));
                return sCardSwipeShared ? SWIPE_NONE : SWIPE_FIRED;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean held = sCardSwipeFired && !sCardSwipeShared;
                if (sCardSwipeFired) {
                    if (sCardSwipeSpread) MiniPlayerRuntime.endSpreadCollapse(ev,
                            ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    else MiniPlayerRuntime.dragEnd(ev,
                            ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                }
                sCardCollapse.reset();
                sCardSwipeFired = sCardSwipeShared = false;
                sCardSwipeSpread = false;
                sCardScrollTarget = sCardPinReleased = false;
                return held ? SWIPE_HELD : SWIPE_NONE;
            }
            default:
                return sCardSwipeFired && !sCardSwipeShared ? SWIPE_HELD : SWIPE_NONE;
        }
    } finally { android.os.Trace.endSection(); } }

    /**
     * Whether a pull down on the keyguard's notifications would fold them into the count: some
     * ordinary notifications are showing and they are not folded already. Read from the stack's
     * own container model - NotificationStackScrollLayout -> controller -> injector ->
     * NumStateTouchHelper - found from the media card, which is one of the stack's children.
     * False on a build laid out any other way, which keeps the swipe the card's alone.
     */
    static boolean keyguardCanFoldNotifications() {
        StringBuilder why = new StringBuilder();
        boolean can = readFoldState(why);
        sFoldProbe = "at=" + android.os.SystemClock.uptimeMillis() + " shared=" + can + " " + why;
        return can;
    }

    /** The last card swipe's fold decision, for `op fold`. */
    static volatile String sFoldProbe = "no card swipe yet";

    /** For `op fold`: the last swipe's decision, then the same reading taken now. */
    static String describeFold() {
        StringBuilder now = new StringBuilder();
        boolean can = readFoldState(now);
        return "last: " + sFoldProbe + " || now: can=" + can + " " + now;
    }

    /** The fold reading, each step it got through written to [why]. */
    static boolean readFoldState(StringBuilder why) {
        try {
            View v = Main.miniPlayerMediaHeader();
            why.append("header=").append(v == null ? "null" : v.getClass().getSimpleName()
                    + "/shown=" + v.isShown());
            StringBuilder chain = new StringBuilder();
            while (v != null && !v.getClass().getName().endsWith("NotificationStackScrollLayout")) {
                android.view.ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
                if (v != null) chain.append('>').append(v.getClass().getSimpleName());
            }
            why.append(" up=").append(chain);
            if (v == null) return false;
            Object controller = Xp.getObjectField(v, "mController");
            Object injector = Xp.getObjectField(controller, "mNsslControllerInjector");
            Object helper = Xp.getObjectField(injector, "numStateTouchHelper");
            Object model = Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get");
            Object folded = Xp.callMethod(Xp.callMethod(model, "isInNumState"), "getValue");
            why.append(" numState=").append(folded);
            try {
                why.append(" stackState=").append(
                        Xp.callMethod(Xp.callMethod(model, "getCurrentsStackState"), "getValue"));
            } catch (Throwable ignored) {
            }
            if (Boolean.TRUE.equals(folded)) return false;
            Object info = Xp.callMethod(Xp.callMethod(model, "getOnUpdateChildSampleStackInfo"),
                    "getValue");
            why.append(" info=").append(info);
            return info != null && ((Number) Xp.getObjectField(info, "normalNotifCount")).intValue() > 0;
        } catch (Throwable t) {
            why.append(" failed: ").append(t);
            Xp.log(Main.TAG + "notification fold state unreadable: " + t);
            return false;
        }
    }
}
