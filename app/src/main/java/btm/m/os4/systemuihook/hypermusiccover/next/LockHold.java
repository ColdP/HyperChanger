// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import java.util.EnumSet;

/**
 * Who the lock screen is given up to, and the one place that gives it.
 *
 * Something drawn behind the whole lock screen - the album cover, an immersive page such as
 * 高德's map - takes the same things from it: the OEM's big clock goes small and up out of the
 * way (ClockCollapse), and the wallpaper's cut-out subject, which the keyguard draws above the
 * clock, goes. Those used to be cover mode's own calls, and every sleep, wake and screen-on path
 * asked "is cover mode on" before touching the clock; a second kind of backdrop had to be taught
 * to each of them (2026-09-29). Now an owner takes the lock screen and gives it back, the clock
 * and the cut-out follow the union of the owners, and the paths that move the clock across a
 * sleep and a wake ask {@link #clockHeld()}.
 *
 * What stays an owner's own is what only it has. The cover fades the media card's thumbnail on
 * the clock's progress and keeps the mini player off screen until its exit has landed; for those
 * the question is whether the clock's flight is the COVER's, which {@link #exitingFor} answers -
 * "the clock is moving" does not say whose it is any more.
 */
final class LockHold {

    private LockHold() {
    }

    private static final String TAG = Main.TAG;

    enum Owner {
        /** The album cover. Its entry fades the card on the clock's spring. */
        COVER(true),
        /** A full-screen immersive page: ImmersiveHost. */
        IMMERSIVE(false);

        /**
         * Taking the lock screen re-runs the clock's entry even when another owner already has
         * the clock small, because this owner's own things (the cover's card) ride that spring.
         */
        final boolean ridesEntry;

        Owner(boolean ridesEntry) {
            this.ridesEntry = ridesEntry;
        }
    }

    private static final EnumSet<Owner> sOwners = EnumSet.noneOf(Owner.class);

    /** Whose give-back the clock's exit in flight is; null when it is no one's. */
    private static Owner sExitOwner;

    /** Some owner has the lock screen: the clock stays small across sleeps, wakes and screen-ons. */
    static boolean clockHeld() {
        return !sOwners.isEmpty();
    }

    /** The clock is still flying home from [o] giving the lock screen back. */
    static boolean exitingFor(Owner o) {
        return sExitOwner == o && ClockCollapse.phase() != ClockCollapse.Phase.OFF;
    }

    /**
     * [o] takes the lock screen: the cut-out goes, and the clock springs small unless another
     * owner already has it there.
     *
     * @param animate spring rather than cut (ClockCollapse.enter decides the doze case itself)
     * @param src     which route took it, for the clock's entry log
     */
    static void take(Owner o, boolean animate, String src) {
        boolean first = sOwners.isEmpty();
        sOwners.add(o);
        Main.setDepthHidden(true);
        if (first || o.ridesEntry) {
            sExitOwner = null;
            ClockCollapse.enter(animate, false, src);
        }
        Xp.log(TAG + "lock screen taken by " + o + " (owners " + sOwners + ")");
    }

    /**
     * [o] gives the lock screen back. The clock and the cut-out go back only when no owner is
     * left; another one keeps them as they are.
     */
    static void give(Owner o, boolean animate) {
        if (!sOwners.remove(o)) return;
        Xp.log(TAG + "lock screen given back by " + o + " (owners " + sOwners + ")");
        if (!sOwners.isEmpty()) return;
        sExitOwner = o;
        Main.handBackDepth();
        ClockCollapse.exit(animate);
    }

    /** Whether the cut-out may come back: no owner is keeping it hidden. */
    static boolean depthFree() {
        return sOwners.isEmpty();
    }

    /** ClockCollapse let go of the clock: nothing is flying for anyone. */
    static void clockReleased() {
        sExitOwner = null;
    }

    static String describe() {
        return "hold owners=" + sOwners + " exit=" + sExitOwner;
    }
}
