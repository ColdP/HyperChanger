package btm.m.os4.systemuihook.hypermusiccover;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Holds the notification panel invisible until a pull-down has actually got somewhere, so a
 * barely-started pull shows no notifications at all.
 *
 * ## Alpha, and nothing else
 *
 * This writes exactly one property: the panel's {@code alpha}. It does not touch scale, and it
 * does not touch translation. That is a deliberate retreat from the module this is ported from,
 * which animated all three, and it was not free to learn:
 *
 * - **Translation.** The reference formula is `sin((f - threshold) * PI/2) * maxH * 0.4`, which
 *   at full expansion evaluates to sin(0.9 * PI/2) = 0.99 - it rises monotonically and never
 *   comes back. So at the fully-open state the panel is displaced by 40% of the screen. That is
 *   not a damped slide, it is a panel parked a third of the way off the bottom, and once it is
 *   on device it looks like a shade that cannot be closed, because the thing under the finger is
 *   no longer where it is drawn.
 * - **Scale.** `NotificationPanelView`'s scale is MIUI's own - it is mid-animation on it during
 *   every open and close (measured at rest, scale 0.99999994, which is MIUI's own curve still
 *   settling). Writing ours over theirs means the last writer wins on a property the OEM is
 *   animating, and the loser is the gesture geometry.
 *
 * Alpha has neither problem: it changes nothing about where the panel is or how big it is, so it
 * cannot make a gesture land somewhere other than it looks like. That makes it the only part of
 * the three that is safe to take over, and it is the part that carries the actual behaviour the
 * user asked for - a pull that has barely started shows nothing.
 *
 * ## The failure this class has to be built around
 *
 * A panel left at alpha 0 still receives touches. Every other failure in this feature is
 * visible - a wrong picture, a missing blur, an edge in the wrong place - but this one produces
 * an apparently dead phone with a blank screen, no error, and no way back except a reboot.
 *
 * So the hidden state is undone by a path that cannot be missed: {@link #apply} arms a watchdog
 * every time it hides the panel, and the watchdog restores unconditionally if nothing has moved
 * the state on. The watchdog is not a fallback for a bug - it is the only thing between a
 * stopped frame callback and an unusable phone.
 */
final class ShadeGate {

    private ShadeGate() {
    }

    private static final String TAG = "[MCGate] ";

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Below this the panel is hidden wholesale. */
    private static final float DEAD = 0.045f;
    /** At and above this it is revealed; in between, it stays however it was last left. */
    private static final float THRESHOLD = 0.10f;

    private static final long REVEAL_MS = 350L;
    private static final long COLLAPSE_MS = 200L;
    private static final Interpolator EASE = new PathInterpolator(0.33f, 0f, 0.67f, 1f);

    /**
     * How long a hidden panel may stay hidden with no frame moving it before it is put back.
     *
     * Generous: the panel is legitimately hidden for the whole of a slow pull-down, and a
     * premature restore would flash the notifications in. It only has to be shorter than a user
     * would wait before deciding the phone has crashed.
     */
    private static final long WATCHDOG_MS = 6000L;

    private static AnimatorSet sRunning;
    private static boolean sRevealed;
    private static volatile boolean sHidden;

    private static final Runnable WATCHDOG = new Runnable() {
        @Override
        public void run() {
            if (!sHidden) return;
            Xp.log(TAG + "the panel has been hidden for " + WATCHDOG_MS + "ms with nothing "
                    + "moving it - restoring unconditionally. A panel at alpha 0 still takes "
                    + "touches, so leaving it there is a phone that looks dead.");
            restore();
        }
    };

    // ------------------------------------------------------------------ per frame

    static void apply(View panel, float f) {
        if (panel == null) return;

        if (f <= DEAD) {
            cancel();
            sRevealed = false;
            hide(panel);
            return;
        }
        if (f >= THRESHOLD) {
            if (!sRevealed) {
                sRevealed = true;
                reveal(panel);
            }
        } else if (sRevealed) {
            sRevealed = false;
            collapse(panel);
        }
    }

    private static void hide(View panel) {
        setAlpha(panel, 0f);
        if (sHidden) return;
        sHidden = true;
        UI.removeCallbacks(WATCHDOG);
        UI.postDelayed(WATCHDOG, WATCHDOG_MS);
    }

    private static void reveal(View panel) {
        sHidden = false;
        UI.removeCallbacks(WATCHDOG);
        start(panel, 1f, REVEAL_MS);
    }

    private static void collapse(View panel) {
        sHidden = true;
        UI.removeCallbacks(WATCHDOG);
        // Armed on the collapse path too, not only on the hard hide: collapsing also ends with
        // the panel invisible, which is the same state.
        UI.postDelayed(WATCHDOG, WATCHDOG_MS);
        start(panel, 0f, COLLAPSE_MS);
    }

    private static void start(View panel, float to, long ms) {
        cancel();
        final ObjectAnimator a = ObjectAnimator.ofFloat(panel, "alpha", to);
        a.setDuration(ms);
        a.setInterpolator(EASE);
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(a);
        sRunning = set;
        set.start();
    }

    private static void cancel() {
        final AnimatorSet set = sRunning;
        if (set == null) return;
        sRunning = null;
        try {
            set.cancel();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ hand back

    /**
     * Every property back, unconditionally, and the watchdog disarmed. Idempotent, and reachable
     * from the per-frame reset, from the cover exit and from the watchdog itself.
     */
    static void restore() {
        cancel();
        sRevealed = false;
        sHidden = false;
        UI.removeCallbacks(WATCHDOG);
        returnPanel(ShadeLayer.panelView());
    }

    /**
     * The unconditional half, split out so the watchdog can call it without going through the
     * bookkeeping that might itself be the thing that is stuck.
     *
     * Alpha only, and deliberately not `setScaleX(1)` / `setTranslationY(0)` as the reference
     * does - see the class comment. Handing back a property we never took is not "restoring",
     * it is writing over whatever the OEM was doing with it at that moment.
     */
    static void returnPanel(View panel) {
        setAlpha(panel, 1f);
    }

    private static void setAlpha(View panel, float a) {
        if (panel == null) return;
        try {
            panel.setAlpha(a);
        } catch (Throwable t) {
            Xp.log(TAG + "could not set the panel's alpha: " + t);
        }
    }

    static boolean hidden() {
        return sHidden;
    }
}
