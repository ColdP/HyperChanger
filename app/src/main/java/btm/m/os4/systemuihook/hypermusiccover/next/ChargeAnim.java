// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.view.ViewGroup;
import android.view.View;

/**
 * The OEM's charging animation over the lock screen: whether it is up or coming in, so taps
 * and gestures under it are left alone, and the trail of its comings and goings for the probe.
 *
 * Carved out of Main as it was (2026-10-14, after Fingerprint): every member moved whole with its
 * comments, references across the line prefixed with Main. or ChargeAnim., nothing else changed.
 */
final class ChargeAnim {
    private ChargeAnim() {}

    /**
     * The charging animation HyperOS plays over the whole screen when the phone is plugged in.
     *
     * Matched as a package rather than by one class: the animation is one of several views in
     * there (MiuiChargeAnimationView on this phone, TinyMiuiChargeAnimationView on a folding
     * one's cover screen), and whichever of them is on the keyguard means the same thing to a
     * tap. See chargeAnimUp.
     */
    static final String CLS_CHARGE_PKG = "com.miui.charge.";

    /**
     * Whether the gesture in flight began on the charging animation.
     *
     * Recorded at ACTION_DOWN for the same reason the centre is: the animation is dismissed by
     * the very gesture that dismisses it, so by the time a held-back tap fires there is nothing
     * left on screen to ask, and the tap would be read as one on the cover. See chargeAnimUp.
     */
    static volatile boolean sGestureOnCharge;

    /**
     * Whether the charging animation is on the lock screen.
     *
     * HyperOS plays a full-screen animation when the phone is plugged in, and it does not take
     * the touch: it is a FrameLayout whose own click handling covers a fraction of its area, so
     * a DOWN anywhere else falls through to the shade window underneath and the lock screen's
     * own tap - ours - fires with the animation still on screen. Tapping to dismiss it then
     * toggles the cover as well.
     *
     * It is read off the live tree rather than tracked with a flag of our own. The animation view
     * is added straight to the keyguard's root view by MiuiChargeAnimationView.addChargeView and
     * taken out again by removeChargeView when it ends or the screen wakes, so the tree is the
     * one place that cannot go stale - a flag set by a hook would stay set for the rest of the
     * session if the build took the view out some other way, and every tap on the cover would be
     * dead. This way a build that renames the class answers "no" and leaves the tap exactly as
     * it was, which is the same bargain bouncerUp and controlCenterUp make.
     */
    static boolean chargeAnimUp() {
        View v = Main.sContainer;
        if (v == null) return false;
        View root = v.getRootView();
        return root != null && chargeAnimIn(root, 0);
    }

    /**
     * The walk above. Depth-bounded because the animation is added to the window root itself -
     * it is a child of the root, or at most a couple of levels down - and this runs on every
     * touch the lock screen sees.
     */
    static boolean chargeAnimIn(View v, int depth) {
        // isShown rather than the class alone: it is what "and the user can see it" means, and
        // the point of the guard is what is on the screen, not what is attached to the window.
        if (v.getClass().getName().startsWith(CLS_CHARGE_PKG) && v.isShown()) return true;
        if (depth >= 3 || !(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (chargeAnimIn(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    /** The last few attach/detach events, oldest first. */
    static final java.util.ArrayDeque<String> sChargeTrail = new java.util.ArrayDeque<>();

    /** When the animation last went up, or 0 when it is not up. */
    static long sChargeUpAt;

    /** How long the last one stayed up, for the line that records it going down. */
    static long sChargeUpFor;

    static void noteChargeAnim(boolean up, Object self) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (up) {
                sChargeUpAt = now;
            } else if (sChargeUpAt != 0L) {
                sChargeUpFor = now - sChargeUpAt;
                sChargeUpAt = 0L;
            }
            View view = self instanceof View ? (View) self : null;
            View root = Main.sContainer == null ? null : Main.sContainer.getRootView();
            String line = (up ? "up" : "down") + " @" + now
                    + " walk=" + chargeAnimUp()
                    + " view=" + (view == null ? "?"
                            : view.getClass().getSimpleName() + (view.isShown() ? " shown" : " hidden"))
                    + " root=" + (root == null ? "no keyguard" : root.getClass().getSimpleName())
                    + " kids=[" + Main.childNames(root) + "]"
                    + (up ? "" : " stayed=" + sChargeUpFor + "ms");
            synchronized (sChargeTrail) {
                sChargeTrail.addLast(line);
                while (sChargeTrail.size() > 8) sChargeTrail.removeFirst();
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "charge trail failed: " + t);
        }
    }

    /**
     * What a tap on the cover is reading right now, and what the animation has been doing.
     *
     * Answered through the ordered broadcast rather than the log, which is the channel that
     * works on this build - see the note on the app's own dump.
     */
    static String chargeAnimReport() {
        StringBuilder sb = new StringBuilder("chargeAnimUp=" + chargeAnimUp()
                + (sChargeUpAt == 0L ? "" : " (up since " + sChargeUpAt + "ms)"));
        synchronized (sChargeTrail) {
            for (String line : sChargeTrail) sb.append('\n').append(line);
        }
        View root = Main.sContainer == null ? null : Main.sContainer.getRootView();
        sb.append("\nroot=").append(root == null ? "no keyguard" : root.getClass().getName());
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                sb.append("\n  ").append(i).append(' ').append(c.getClass().getName())
                        .append(" vis=").append(c.getVisibility())
                        .append(" alpha=").append(c.getAlpha())
                        .append(c.isShown() ? " shown" : " hidden");
            }
        }
        return sb.toString();
    }

    static void hookChargingAnimation(final ClassLoader cl) {
        // The charging animation going up and coming down, recorded rather than guessed at.
        //
        // This is not what holds the tap back - chargeAnimUp reads the live tree and would go on
        // working with this hook missing entirely. It is the evidence for it: `op chargeanim`
        // prints one line per attach and detach with the tree as it was at that instant, which
        // is what tells "the walk finds the view" from "the walk is looking at the wrong root",
        // and what puts a number on how long a tap is held off for.
        try {
            Class<?> chargeAnim = Xp.findClass(
                    CLS_CHARGE_PKG + "container.MiuiChargeAnimationView", cl);
            // After the call, not before: the point of the snapshot is what the tree looks like
            // with the animation in it, and addChargeView is what puts it there.
            Xp.hookAll("sysui.charge.add_view", chargeAnim, "addChargeView", chain -> {
                Object result = chain.proceed();
                noteChargeAnim(true, chain.getThisObject());
                return result;
            });
            Xp.hookAll("sysui.charge.remove_view", chargeAnim, "removeChargeView", chain -> {
                Object result = chain.proceed();
                noteChargeAnim(false, chain.getThisObject());
                return result;
            });
            Xp.log(Main.TAG + "charging animation hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "charging animation hook failed: " + t);
        }
    }
}
