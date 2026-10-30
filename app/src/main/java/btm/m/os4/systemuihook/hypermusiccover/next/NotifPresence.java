// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import java.lang.reflect.Method;

/**
 * Whether the lock screen "has notifications", as every clock face is told it - corrected for
 * the rows nobody can see.
 *
 * KeyguardPanelViewController.updateNotificationState(hasVisibleNotifsOnKeyguard) is the one
 * switch behind all of it: the hard-cut faces (rhombus, oriental A and C) swap to their
 * notification template on it, the magazine and oriental B faces to their notification layout,
 * all_in_one hides its signature, the depth effect goes off. And the system answers it from the
 * stack, where the media card stands in for the media island INVISIBLE and still laid out, and a
 * notification let into an island keeps its row: with nothing but the island on screen, every
 * face folded as if a notification sat under it (reported 2026-10-14, rhombus first, then "不止
 * 菱形"). The y the clock gives way to was already corrected for those rows (ClockRoom); this is
 * the yes/no that goes with it.
 *
 * Only ever turns the system's yes into a no, never the other way, and only while the mini
 * player's own reading of the stack says not one row is showing (stackContentTop() is MAX_VALUE).
 * When that changes - a real notification arrives, a card comes out of its island - the system
 * has no reason to say anything new, its flag having been true all along, so the switch is
 * thrown again from here (recheck).
 */
public final class NotifPresence {
    private NotifPresence() {}

    private static final String CLS_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController";
    private static final String NAME = "access$updateNotificationState";

    private static Method sUpdate;
    private static Object sPanel;
    /** What the system last said, and what we passed on. */
    private static boolean sSystemSays;
    private static boolean sPassed;
    private static boolean sOurs;
    /** recheck() runs off every clock frame; at most this often. */
    private static long sCheckedAt;
    private static final long CHECK_MS = 50L;

    static void install(ClassLoader cl) {
        try {
            Class<?> panel = Xp.findClass(CLS_PANEL, cl);
            for (Method m : panel.getDeclaredMethods()) {
                if (m.getName().equals(NAME) && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    sUpdate = m;
                }
            }
            Xp.hookAll("sysui.notif.presence", panel, NAME, chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 2 || !(args[1] instanceof Boolean)) return chain.proceed();
                sPanel = args[0];
                boolean says = (Boolean) args[1];
                // Our own re-throw carries the system's word, not a new one.
                if (!sOurs) sSystemSays = says;
                boolean pass = wanted(says);
                sPassed = pass;
                if (pass != says) args[1] = pass;
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "notification presence hook failed: " + t);
        }
    }

    /**
     * What the faces should be told. While the cover (or an immersive page) holds the lock screen,
     * always yes: the cover's small clock is built on each face's notification layout, the plain
     * one - magazine C's time over a single small date line - and with the stack folded to "1个通知"
     * the rows read as unseen, the face fell back to its full layout and the cover shrank that
     * instead: the big weekday and date stacked under the time (screenshots 2026-10-14 17:34).
     */
    private static boolean wanted(boolean systemSays) {
        if (LockHold.clockHeld()) return true;
        return systemSays && rowsShowing();
    }

    /**
     * Whether any notification row can be seen. Unknown - the mini player is off, or the stack
     * is not measured yet - is a yes: the system's word stands.
     */
    private static boolean rowsShowing() {
        try {
            return MiniPlayerRuntime.stackContentTop() != Float.MAX_VALUE;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Asked whenever the room under the clock is worked out again, which is whenever the rows
     * move: throws the switch again if what we passed on no longer matches.
     */
    static void recheck() {
        recheck(false);
    }

    /** @param force skip the rate limit - the cover has just let go, and no frame follows. */
    static void recheck(boolean force) {
        final Object panel = sPanel;
        final Method m = sUpdate;
        if (panel == null || m == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - sCheckedAt < CHECK_MS) return;
        sCheckedAt = now;
        if (wanted(sSystemSays) == shown(panel)) return;
        android.view.View v = Main.sContainer;
        if (v == null) return;
        Gen.postOn(v, () -> {
            if (wanted(sSystemSays) == shown(panel)) return;
            sOurs = true;
            try {
                m.invoke(null, panel, sSystemSays);
            } catch (Throwable t) {
                Xp.w(Main.TAG + "notification presence re-throw failed: " + t);
            } finally {
                sOurs = false;
            }
        });
    }

    /**
     * What the panel itself holds now. Read rather than remembered: the OEM ignores the call
     * off the keyguard (and when the value is unchanged), so what we passed is not always what
     * it has.
     */
    private static boolean shown(Object panel) {
        try {
            return (Boolean) Xp.getObjectField(panel, "hasNotification");
        } catch (Throwable t) {
            return sPassed;
        }
    }

    static String describe() {
        return "system=" + sSystemSays + " passed=" + sPassed + " rows=" + rowsShowing()
                + " hooked=" + (sUpdate != null);
    }
}
