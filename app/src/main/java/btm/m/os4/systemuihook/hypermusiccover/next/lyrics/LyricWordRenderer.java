// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.text.StaticLayout;
import android.text.TextPaint;

/** The full page's word fill, shaped syllable lift and held-note drawing, shared unchanged. */
final class LyricWordRenderer {
    interface Colors {
        long ink(float alpha, float emphasis, boolean backing);
        default long ink(float alpha, float emphasis) { return ink(alpha, emphasis, false); }
        long white(float alpha, float gain);
        float glowGain(float glow);
    }
    static final int LIFT_MIN_MS = 320, GLOW_MIN_MS = 1000, GLOW_TAIL_MS = 380;
    static final float FEATHER_EM = .45f, GLOW_ALPHA = .55f, GLOW_SWELL = .045f;
    private final Matrix gradMatrix = new Matrix();
    private final LinearGradient[] grads = new LinearGradient[3];
    private final long[] gradHi = {-1L, -1L, -1L}, gradLo = {-1L, -1L, -1L};
    private float rowAt = Float.NaN, rowFeather, rowSungA, rowUnsungA;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    void draw(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                              float e, float sungA, float unsungA, float liftScale,
                              boolean glowOn, int gradSlot, int ms, LyricGlowMask masks, Colors colors) {
        float sung = l.sungChars(ms);
        boolean backing = gradSlot == 1;
        float feather = FEATHER_EM * p.getTextSize();
        int rows = lay.getLineCount();
        for (int r = 0; r < rows; r++) {
            int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
            rowAt = Float.NaN;
            if (sung >= re) {
                p.setColor(colors.ink(sungA, e, backing));
            } else if (sung <= rs) {
                p.setColor(colors.ink(unsungA, 0f, backing));
            } else {
                int ch = (int) sung;
                float f = sung - ch;
                float xa = xs[ch];
                float xb = ch + 1 < re ? xs[ch + 1] : lay.getLineRight(r);
                p.setColor(0xFFFFFFFF);
                rowAt = xa + (xb - xa) * f;
                p.setShader(gradient(colors.ink(sungA, e, backing), colors.ink(unsungA, 0f, backing), rowAt, feather,
                        gradSlot));
            }
            rowFeather = feather;
            rowSungA = sungA;
            rowUnsungA = unsungA;
            drawSyllables(c, l, lay, p, xs, e, r, liftScale, glowOn, ms, masks, colors);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
        }
    }

    private void drawSyllables(Canvas c, LyricLine l, StaticLayout lay, TextPaint p, float[] xs,
                               float e, int r, float liftScale, boolean glowOn, int ms, LyricGlowMask masks, Colors colors) {
        // Belt and braces: every caller is supposed to have asked hasWords() first.
        if (l.sylStart == null) return;
        int count = l.sylStart.length;
        int rs = lay.getLineStart(r), re = lay.getLineEnd(r);
        float baseline = lay.getLineBaseline(r);
        for (int k = 0; k < count; k++) {
            int from = k == 0 ? 0 : l.charEnd[k - 1];
            int cs = Math.max(from, rs), ce = Math.min(l.charEnd[k], re);
            if (ce <= cs) continue;
            int s = l.sylStart[k], end = l.sylEnd[k], dur = end - s;
            float rise = ms <= s ? 0f : clamp01((ms - s) / (float) Math.max(dur, LIFT_MIN_MS));
            float lift = liftScale * e * (1f - (1f - rise) * (1f - rise));
            float glow = 0f;
            if (glowOn && dur >= GLOW_MIN_MS && ms > s) {
                glow = ms < end ? clamp01((ms - s) / (dur * 0.3f))
                        : 1f - clamp01((ms - end) / (float) GLOW_TAIL_MS);
                glow *= e;
            }
            float x0 = xs[cs];
            if (glow > 0.01f) {
                float x1 = ce < re ? xs[ce] : lay.getLineRight(r);
                int save = c.save();
                float swell = 1f + GLOW_SWELL * glow;
                c.scale(swell, swell, (x0 + x1) / 2f, baseline);
                float g = colors.glowGain(glow);
                LyricGlowMask.Run halo = masks == null ? null : masks.find(cs, ce);
                if (halo != null) {
                    for (int layer = 0; layer < LyricGlow.LAYERS; layer++) {
                        float weight = LyricGlow.weight(glow, layer);
                        if (weight <= 0f) continue;
                        glowPaint.setColor(colors.white(GLOW_ALPHA * glow * weight, g));
                        c.drawBitmap(halo.layers[layer], halo.left, baseline - lift + halo.top, glowPaint);
                    }
                }
                // The glowing syllable itself goes above white with its halo: its sung part
                // through this row's gradient re-coloured, or all of it once the row is sung.
                Shader rowShader = p.getShader();
                long rowColor = p.getColorLong();
                if (g > 1.001f) {
                    if (Float.isNaN(rowAt)) {
                        if (rowShader == null && l.sungChars(ms) >= ce) {
                            p.setColor(colors.white(rowSungA, g));
                        }
                    } else {
                        p.setShader(gradient(colors.white(rowSungA, g), colors.ink(rowUnsungA, 0f), rowAt,
                                rowFeather, 2));
                    }
                }
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
                p.setShader(rowShader);
                p.setColor(rowColor);
                c.restoreToCount(save);
            } else {
                c.drawTextRun(l.text, cs, ce, rs, re, x0, baseline - lift, false, p);
            }
        }
    }

    private Shader gradient(long hi, long lo, float at, float feather, int slot) {
        LinearGradient g = grads[slot];
        if (g == null || hi != gradHi[slot] || lo != gradLo[slot]) {
            gradHi[slot] = hi;
            gradLo[slot] = lo;
            g = new LinearGradient(-1f, 0f, 1f, 0f, new long[]{hi, lo}, null,
                    Shader.TileMode.CLAMP);
            grads[slot] = g;
        }
        gradMatrix.setScale(feather, 1f);
        gradMatrix.postTranslate(at, 0f);
        g.setLocalMatrix(gradMatrix);
        return g;
    }
    private static float clamp01(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }
}
