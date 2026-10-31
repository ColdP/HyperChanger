// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Bitmap;
import android.view.ViewGroup;
import android.view.View;

/**
 * The clock's colour in the always-on display and over the cover: holding the AOD's colour as
 * the OEM hands it over, measuring the cover behind the clock band, and recolouring the clock
 * from that - with the luminance every colour decision here reads.
 *
 * Carved out of Main as it was (2026-10-14, after CardArt): every member moved whole with its
 * comments, references across the line prefixed with Main. or AodColour., nothing else changed.
 */
final class AodColour {
    private AodColour() {}

    /**
     * Whether the doze clock's colour is ours to hold at all.
     *
     * Two of the three dozes are ours. A doze that KEPT the cover's clock is the lock screen's
     * carried into sleep, so it holds the lock screen's own colour (captureAodGlass). A plain
     * always-on display - the linkage AOD, the classic plugin one - is held at a neutral, because
     * what it would otherwise be painted is the wallpaper's palette and in cover mode the
     * wallpaper is the album art: that is the gold 84869b1 was written for.
     *
     * The third is the FULL-SCREEN doze whose clock was handed back, and its colour is the OEM's:
     * the setting exists to say whether that clock is ours, and with it off there is no reading of
     * ours to hold it at - the neutral is the first ink's lightness and nothing more - which comes
     * out as a flat grey slab where the system had glass over the wallpaper. Reported 2026-09-21:
     * "音乐封面模式开启 + 全屏息屏显示保持小时钟关闭" and the big clock came up grey. Only this
     * one configuration is left alone; the other two are held exactly as before.
     */
    static boolean aodColourOurs() {
        return ClockCollapse.aodHeld() || !ClockCollapse.aodFullScreen();
    }

    /**
     * Holds the always-on clock's glyphs at the colour they are ours to hold.
     *
     * The colour lives in `glassData[11..13]` and is uploaded from the view's own field when the
     * material is drawn - proved on a dozing keyguard by poking those three indices and watching
     * the whole clock turn red on the next frame. So the FIELD is what has to be held, not the
     * array handed to any one setter: on this build the OEM's palette pass reaches the glyphs by a
     * route that goes through neither `TimeView.setGlassColor` (the tint op proved that one is
     * dead here) nor `View.setMiGlass` (guarding that one still let the clock turn gold). Assert
     * the field every doze frame instead, the way notifY and the depth cut-out are asserted.
     *
     * @return true when a glyph was rewritten and so needs the redraw
     */
    static boolean holdAodColour() {
        if (!Main.sCoverMode || Main.sScreenOn || !aodColourOurs()) return false;
        // A doze that kept the cover's clock is showing the LOCK SCREEN's clock, so it holds the
        // colour and the fill the lock screen had. Read at the moment of sleep, before the doze had
        // inked anything with its own palette. The capture is only ever taken for a held doze, so
        // this also says whether the reading below is the lock screen's or a dead one.
        boolean cover = ClockCollapse.aodHeld() && ClockGlass.sAodCoverGlassSet;
        boolean wrote = false;
        for (View root : Main.clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null || t.getVisibility() != View.VISIBLE) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    if (cover) {
                        if (g[11] != ClockGlass.sAodCoverGlass[0] || g[12] != ClockGlass.sAodCoverGlass[1]
                                || g[13] != ClockGlass.sAodCoverGlass[2]) {
                            g[11] = ClockGlass.sAodCoverGlass[0];
                            g[12] = ClockGlass.sAodCoverGlass[1];
                            g[13] = ClockGlass.sAodCoverGlass[2];
                            wrote = true;
                        }
                        if (g[36] != ClockGlass.sAodCoverGlass[3]) {
                            g[36] = ClockGlass.sAodCoverGlass[3];
                            wrote = true;
                        }
                        if (wrote) t.invalidate();
                        continue;
                    }
                    // The plain always-on display, and a held doze whose glass could not be read at
                    // sleep. Neither has a colour of ours to be held at, and what the OEM would
                    // paint is the wallpaper's palette - the album art, in cover mode - so the
                    // neutral the doze first inks with is held instead. Only its lightness is kept,
                    // so a doze caught late still ends up neutral rather than gold.
                    if (Float.isNaN(Main.sAodGrey)) {
                        Main.sAodGrey = luminance(g[11], g[12], g[13]);
                    }
                    if (g[11] != Main.sAodGrey || g[12] != Main.sAodGrey || g[13] != Main.sAodGrey) {
                        g[11] = Main.sAodGrey;
                        g[12] = Main.sAodGrey;
                        g[13] = Main.sAodGrey;
                        wrote = true;
                    }
                    // And solid. This is the half that was missing: the gold is not a colour at
                    // all, it is the album-art wallpaper showing through a transparent glass
                    // glyph - proved on one doze by the date reading (182,182,182) neutral while
                    // the clock read (203,161,118) gold, the date being the one of the two with no
                    // glass. The doze's own fill is 0, so the backdrop shows; the two seconds of
                    // neutral the user saw at the start were the frames where it was still 1.
                    if (g[36] < 0.999f) {
                        g[36] = 1f;
                        wrote = true;
                    }
                    if (wrote) t.invalidate();
                } catch (Throwable ignored) {
                }
            }
        }
        if (wrote) ClockGlass.pushGlassFill(cover ? ClockGlass.sAodCoverGlass[3] : 1f);
        return wrote;
    }

    /**
     * Averages the strip of the composed cover the clock and its date are drawn on.
     *
     * Sampled from the composed bitmap rather than from the album art, because the two are not
     * the same picture where it matters: the sharp band starts below the clock at the default
     * bias, so what is actually behind the glyphs is the mirrored blur - and on a light cover
     * that blur is light. Costs 24 rows of getPixels on the worker that is already encoding the
     * same bitmap.
     */
    static void measureCover(Bitmap full) {
        measureCover(full, 1f);
    }

    /**
     * The same, on a composition drawn at 1/[scale] of the screen: the band is read where it
     * falls in that smaller picture. Its average is the band's average either way - the mirror
     * blur has nothing left at that scale that a quarter-size copy would lose.
     */
    static void measureCover(Bitmap full, float scale) {
        try {
            float d = Main.sAppCtx.getResources().getDisplayMetrics().density;
            float topPx = Main.sBandTopPx, botPx = Main.sBandBotPx;
            if (Float.isNaN(topPx) || Float.isNaN(botPx) || botPx - topPx < 8f) {
                topPx = Main.BAND_TOP_DP * d;
                botPx = Main.BAND_BOT_DP * d;
            } else {
                topPx -= Main.BAND_PAD_DP * d;
                botPx += Main.BAND_PAD_DP * d;
            }
            int top = Math.max(0, Math.round(topPx / scale));
            int bottom = Math.min(full.getHeight(), Math.round(botPx / scale));
            if (bottom - top < 8f / scale) { Main.sCoverTint = 0; return; }
            int stride = Math.max(1, (bottom - top) / 24);
            int[] row = new int[full.getWidth()];
            long sr = 0, sg = 0, sb = 0;
            int n = 0;
            int step = Math.max(1, Math.round(8f / scale));
            for (int y = top; y < bottom; y += stride) {
                full.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int x = 0; x < row.length; x += step) {
                    int c = row[x];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                    n++;
                }
            }
            if (n == 0) { Main.sCoverTint = 0; return; }
            // The plain average of the band, which the mirror-blur has already smoothed: one
            // number describing the picture behind the glyphs rather than any one pixel of it.
            Main.sCoverTint = 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8)
                    | (int) (sb / n);
            Xp.log(Main.TAG + "cover tint #" + Integer.toHexString(Main.sCoverTint) + " over " + n
                    + "px, band " + top + ".." + bottom);
        } catch (Throwable t) {
            Main.sCoverTint = 0;
            Xp.w(Main.TAG + "cover tint failed: " + t);
        }
    }

    /**
     * Asks the OEM for its clock colours again, so a cover that has just changed is judged
     * against the new one.
     *
     * Nothing else will. The colour pass runs when the wallpaper's palette changes, and swapping
     * the cover is not one - it happens behind the wallpaper's back. Without this a track change
     * would keep the previous cover's decision, which on the way from a light cover to a dark
     * one means a dark clock left dark on a dark picture: worse than the problem this exists to
     * fix. The values handed over are the OEM's own, off the style it is already holding; the
     * setter hooks are what make them come out legible.
     */
    static void recolorClock() {
        if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) return;
        final View v = Main.sContainer;
        if (v == null) return;
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) return;
                for (View root : Main.clockRoots()) {
                    try {
                        if (!(root instanceof ViewGroup)) continue;
                        View c = ((ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        if (ClockGlass.glassGlyphs(root)) {
                            // The glass is driven from our own morph, and re-running one frame of
                            // it is what makes it re-colour - the same call applyGlassMorph()
                            // makes on every frame of a collapse, so nothing new is being asked
                            // of it. updateClockColor, which is the other path, is deliberately
                            // NOT called here: the OEM only reaches for it on a solid style, and
                            // it writes the glyphs' paint colour as well as their glass data.
                            if (!Float.isNaN(ClockGlass.sAppliedGlassV)) {
                                Xp.callMethod(c, "updateGlassValue", ClockGlass.sAppliedGlassV);
                            }
                        } else if (Main.hasField(c, "mClockStyleInfo")) {
                            Object info = Xp.getObjectField(c, "mClockStyleInfo");
                            Xp.callMethod(c, "updateClockColor",
                                    Xp.callMethod(info, "getPrimaryColor"),
                                    Xp.callMethod(info, "getSecondaryColor"));
                        } else {
                            // Nothing to nudge on this style, and nothing to change either: its
                            // colours are the SystemUI palette business. This used to throw,
                            // one IllegalArgumentException per frame, which is how it was found.
                        }
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "recolor failed: " + t);
                    }
                }
            }
        });
    }

    /**
     * Counter-hook for HyperLight's "统一柔光玻璃" (its SoftGlassMaterialHook).
     *
     * HyperLight hooks {@code View.setMiGlass(float[])} and, for any view its classifier admits,
     * rewrites the whole 42-float MiGlass array to its soft-glass preset - indices 0..36, and
     * [34] and [36] in particular. Index 36 is the liquid-glass channel this module drives
     * through {@code updateGlassValue -> glassData[36]}; rewriting it is what turns the clock
     * from 液态玻璃 into plain glass ("非液态").
     *
     * Its classifier (gu1.o) admits a view whose setMiGlass happens with a
     * notification/shade/control-centre frame on the stack, which is exactly the media-card ->
     * cover-mode transition - which is why the clock comes up liquid after a restart and loses it
     * a moment after music starts, rather than immediately.
     *
     * The crucial asymmetry: HyperLight rewrites the COPY handed to setMiGlass, never the view's
     * own {@code glassData} field, so the field still holds the OEM's liquid values. Handing the
     * field back for the clock's own glyphs is the whole fix.
     *
     * Armed from the clock container's onAttachedToWindow rather than at onPackageLoaded - that
     * event reliably fires after both modules are loaded, so this hook registers AFTER HyperLight's
     * and runs last in the chain, where the last writer to args[0] is what the original receives.
     */
    /** Rec. 709 luminance of a glass colour triple, which is what an even grey has to match. */
    static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
