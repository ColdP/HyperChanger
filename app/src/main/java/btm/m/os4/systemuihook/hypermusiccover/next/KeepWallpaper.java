// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.Intent;

/** Background choice stays independent of KEEP's clock/lyric layout policy. */
public final class KeepWallpaper {
    private static volatile boolean cardKeep;
    private static volatile int blur;
    private static float sent = Float.NaN;

    static boolean background() {
        return Main.keepsWallpaper() || (cardKeep && Main.sCoverCardStyle.isCard());
    }

    public static void configure(boolean keep, int value) {
        boolean changed = cardKeep != keep;
        cardKeep = keep;
        blur = Math.max(0, Math.min(100, value));
        sync(false);
        if (changed && Main.sCoverMode && Main.sCoverCardStyle.isCard()) CoverPush.pushArtAsync(true, false);
    }

    static void sync(boolean force) {
        if (Main.sAppCtx == null) return;
        // Do not gate the source effect on the interactive display state: AOD is still the
        // keyguard wallpaper and keeps the selected blur while the panel is dozing.
        float want = background() && Main.sCoverMode && Main.keyguardShowing()
                && btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.coverAllowed() ? blur : 0f;
        if (!force && want == sent) return;
        sent = want;
        Intent out = CoverPush.wallpaperIntent("keepblur");
        out.putExtra("v", want);
        ProbeGuard.send(Main.sAppCtx, out);
    }
}
