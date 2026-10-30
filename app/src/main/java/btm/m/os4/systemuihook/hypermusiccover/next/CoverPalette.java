// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import java.util.HashMap;
import java.util.Map;

/**
 * Has the lock screen read its colours again, from whatever the wallpaper process now says is on
 * the lock texture - the cover while it is up (WallpaperProbe.installCoverPalette).
 *
 * Every clock face but all_in_one's variable-font one takes its colours from
 * MiuiKeyguardWallPaperManager.mColorMap, the per-region palettes the wallpaper process pushes
 * when the WALLPAPER changes (onPartColorComputeComplete -> updateColorAndDeep). A cover going up
 * is not a wallpaper change, so nothing was ever pushed for it: the solid, overlay and
 * gradient-blur effects kept the real wallpaper's colours over the cover (reported 2026-10-14;
 * only 混色 and liquid glass, which sample what is behind them, followed it).
 *
 * So the palettes are asked for again, the way SystemUI itself does at start
 * (getMiuiWallpaperPalette(2, empty) - every region), and handed to updateColorAndDeep directly.
 * Not through onPartColorComputeComplete: that one also rotates the "color_scheme" setting on
 * every call, as for a new wallpaper, and the clock's colour would cycle on every track change.
 * The depth map is left as it is: light or dark text stays the wallpaper's.
 */
public final class CoverPalette {
    private CoverPalette() {}

    private static final String CLS_MGR = "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager";

    private static volatile Object sMgr;
    private static Runnable sPending;
    private static volatile int sRuns, sFails;
    private static volatile String sLast = "never";

    static void install(ClassLoader cl) {
        try {
            Xp.hookAllConstructors("sysui.palette.manager_ctor", Xp.findClass(CLS_MGR, cl), chain -> {
                Object r = chain.proceed();
                sMgr = chain.getThisObject();
                return r;
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "cover palette manager hook failed: " + t);
        }
    }

    /**
     * Reads the palettes again: once right away and once more past the cover's fade.
     *
     * One read at 700ms - safely after the new picture is on the wallpaper's texture - was
     * correct and visibly late (the user, 2026-10-14: 切换有延迟). The picture is usually there
     * long before: the wallpaper process composes it within a frame or two of the push. So the
     * first read goes almost at once, and the late one only confirms it - applied only when it
     * reads something different, so a first read that was already right costs no second
     * recolour.
     */
    static void refreshSoon(long lateMs) {
        final android.view.View v = Main.sContainer;
        if (v == null || sMgr == null) return;
        if (sEarly != null) Gen.removeCallbacksOn(v, sEarly);
        if (sPending != null) Gen.removeCallbacksOn(v, sPending);
        sEarly = () -> {
            sEarly = null;
            Gen.thread("mc-cover-palette", CoverPalette::refreshNow);
        };
        sPending = () -> {
            sPending = null;
            // A binder call into the wallpaper process, which reads the picture: off the main
            // thread, and the result applied back on it.
            Gen.thread("mc-cover-palette", CoverPalette::refreshNow);
        };
        Gen.postDelayedOn(v, sEarly, Math.min(EARLY_MS, lateMs));
        if (lateMs > EARLY_MS) Gen.postDelayedOn(v, sPending, lateMs);
    }

    private static Runnable sEarly;
    /** The first read: a couple of frames for the wallpaper process to have the picture. */
    private static final long EARLY_MS = 80L;
    /** The palettes last applied, so an identical read is not applied again. */
    private static volatile Object sApplied;
    private static volatile long sReadMs;

    private static void refreshNow() {
        final Object mgr = sMgr;
        if (mgr == null) return;
        try {
            Object mwm = Xp.getObjectField(mgr, "mMiuiWallpaperManager");
            if (mwm == null) return;
            long t0 = android.os.SystemClock.uptimeMillis();
            final Object palette = Xp.callMethod(mwm, "getMiuiWallpaperPalette", 2, new HashMap<String, Object>());
            sReadMs = android.os.SystemClock.uptimeMillis() - t0;
            if (!(palette instanceof Map) || ((Map<?, ?>) palette).isEmpty()) {
                sLast = "empty palette";
                return;
            }
            if (palette.equals(sApplied)) {
                sLast = "unchanged (read " + sReadMs + "ms)";
                return;
            }
            android.view.View v = Main.sContainer;
            if (v == null) return;
            Gen.postOn(v, () -> {
                try {
                    if (Xp.getObjectField(mgr, "mPartDeepMap") == null) {
                        sLast = "no depth map yet";
                        return;
                    }
                    Xp.setObjectField(mgr, "mColorMap", palette);
                    if (Xp.getObjectField(mgr, "mFilterColorMap") == null) {
                        Xp.setObjectField(mgr, "mFilterColorMap", new HashMap<>());
                    }
                    Xp.callMethod(mgr, "updateColorAndDeep", false);
                    sApplied = palette;
                    sRuns++;
                    sLast = "applied, read " + sReadMs + "ms";
                } catch (Throwable t) {
                    sFails++;
                    sLast = "apply failed: " + t;
                }
            });
        } catch (Throwable t) {
            sFails++;
            sLast = "read failed: " + t;
        }
    }

    static String describe() {
        return "mgr=" + (sMgr != null) + " runs=" + sRuns + " fails=" + sFails + " last=" + sLast;
    }
}
