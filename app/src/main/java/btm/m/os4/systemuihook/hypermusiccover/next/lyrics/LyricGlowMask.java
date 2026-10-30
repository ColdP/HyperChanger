// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;

/** Immutable, color-independent held-note masks. Constructed only on the blur worker. */
final class LyricGlowMask {
    private static final int MAX_PIXELS = 1024 * 1024;

    static final class Run {
        final int from, to;
        final float left, top;
        final Bitmap[] layers = new Bitmap[LyricGlow.LAYERS];

        Run(int from, int to, float left, float top) {
            this.from = from;
            this.to = to;
            this.left = left;
            this.top = top;
        }
    }

    final List<Run> runs = new ArrayList<>();

    static LyricGlowMask build(LyricLine line, StaticLayout layout, float[] xs,
                               TextPaint paint, float radius, int minDuration) {
        LyricGlowMask result = new LyricGlowMask();
        if (!line.hasWords()) return result;
        paint.setColor(0xffffffff);
        paint.setShader(null);
        paint.clearShadowLayer();
        Paint.FontMetrics fm = paint.getFontMetrics();
        int pad = (int) Math.ceil(radius * 2f);
        int h = (int) Math.ceil(fm.descent - fm.ascent) + pad * 2;
        long pixels = 0;
        try {
            for (int r = 0; r < layout.getLineCount(); r++) {
                int rs = layout.getLineStart(r), re = layout.getLineEnd(r);
                for (int k = 0; k < line.sylStart.length; k++) {
                    if (line.sylEnd[k] - line.sylStart[k] < minDuration) continue;
                    int cs = Math.max(k == 0 ? 0 : line.charEnd[k - 1], rs);
                    int ce = Math.min(line.charEnd[k], re);
                    if (ce <= cs) continue;
                    float x0 = xs[cs], x1 = ce < re ? xs[ce] : layout.getLineRight(r);
                    int w = Math.max(1, (int) Math.ceil(Math.abs(x1 - x0)) + pad * 2);
                    long size = (long) w * h * LyricGlow.LAYERS;
                    // Malformed or unusually large lyrics cannot grow the cache indefinitely.
                    if (pixels + size > MAX_PIXELS) continue;
                    pixels += size;
                    Run run = new Run(cs, ce, x0 - pad, fm.ascent - pad);
                    result.runs.add(run);
                    for (int layer = 0; layer < LyricGlow.LAYERS; layer++) {
                        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                        run.layers[layer] = mask;
                        paint.setMaskFilter(new BlurMaskFilter(
                                radius * (layer + 1) / LyricGlow.LAYERS, BlurMaskFilter.Blur.NORMAL));
                        // Preserve the original row's shaping context, including joined scripts.
                        new Canvas(mask).drawTextRun(line.text, cs, ce, rs, re,
                                pad, pad - fm.ascent, false, paint);
                        mask.prepareToDraw();
                    }
                }
            }
        } catch (Throwable ignored) {
            result.recycle();
            result.runs.clear();
        }
        return result;
    }

    Run find(int from, int to) {
        for (Run run : runs) if (run.from == from && run.to == to) return run;
        return null;
    }

    /** Only for unpublished results; displayed textures may still be used by RenderThread. */
    void recycle() {
        for (Run run : runs) for (Bitmap mask : run.layers) if (mask != null) mask.recycle();
    }
}
