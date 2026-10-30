// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Whether the screen can show the HDR highlight at all. Both sides ask: SystemUI before giving
 * the lyrics a window of their own, the app before offering the switch.
 *
 * The highlight is a window asking for headroom above SDR white (LyricWindow.colourMode). A panel
 * that reports no HDR/SDR ratio has none to give, so the window would buy nothing on screen while
 * still costing everything a window does - a second full-screen layer for SurfaceFlinger, a
 * second draw-and-sync of the render thread every frame the words move, and a relayout each time
 * the colour mode flips. Those are the screens of most low-end phones.
 */
public final class HdrDisplay {

    private HdrDisplay() {
    }

    /** 1 can, 0 cannot, -1 not asked yet. The panel does not change under a running process. */
    private static volatile int sCan = -1;

    public static boolean supported(Context ctx) {
        int c = sCan;
        if (c >= 0) return c == 1;
        boolean can;
        try {
            DisplayManager dm = ctx == null ? null : ctx.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            // Asked too early to know (no context yet): not an answer to keep.
            if (d == null) return false;
            can = d.isHdrSdrRatioAvailable();
        } catch (Throwable t) {
            // Not known: not cached either, so the next call can still find out.
            return false;
        }
        sCan = can ? 1 : 0;
        return can;
    }
}
