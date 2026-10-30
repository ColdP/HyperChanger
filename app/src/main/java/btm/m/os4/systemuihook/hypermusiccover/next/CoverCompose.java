// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Bitmap;

/**
 * The cover composition, shared by both processes.
 *
 * Lives on its own so the wallpaper process can call it without touching Main, whose static
 * state belongs to SystemUI. Pure: the same source, size and bias give the same pixels in
 * either process, which is what lets SystemUI hand over the small source instead of the
 * composed picture - see CoverPush.pushArtToWallpaper().
 */
public final class CoverCompose {

    private CoverCompose() {
    }

    // ------------------------------------------------------------------ the source hand-over

    /** "MCSR", then a version, so a file written by another build is refused rather than misread. */
    private static final int MAGIC = 0x4D435352;
    private static final int VERSION = 1;
    private static final int HEADER_INTS = 7;

    /** What a hand-over file holds: the source and everything the composition depends on. */
    static final class Source {
        final Bitmap src;
        final int w, h;
        final float bias;

        Source(Bitmap src, int w, int h, float bias) {
            this.src = src;
            this.w = w;
            this.h = h;
            this.bias = bias;
        }
    }

    /**
     * The source as it is composed from, in either process.
     *
     * A software ARGB_8888 copy, because the session can hand over a HARDWARE bitmap or an
     * RGB_565 one and neither can be read out as bytes. And no wider than the screen: the
     * composition only ever draws the source at the screen's width or smaller, so extra columns
     * are bytes to move for nothing - a player that publishes 3000x3000 would otherwise be a
     * 36MB hand-over. SystemUI composes from this same bitmap, so both sides get the same pixels.
     */
    static Bitmap prepareSource(Bitmap art, int screenW) {
        Bitmap b = art;
        if (b.getWidth() > screenW && screenW > 0) {
            int nh = Math.max(1, Math.round(b.getHeight() * (screenW / (float) b.getWidth())));
            b = Bitmap.createScaledBitmap(b, screenW, nh, true);
        }
        if (b.getConfig() != Bitmap.Config.ARGB_8888) {
            b = b.copy(Bitmap.Config.ARGB_8888, false);
        }
        return b;
    }

    /**
     * Writes the source to `dest` atomically: into a temporary file, then renamed over it.
     *
     * The rename is what makes one fixed name safe across fast track changes. A reader that
     * opened the previous file keeps reading that file's inode to the end; one that opens after
     * the rename gets the whole new one. Never half of each.
     */
    static void writeSource(java.io.File dest, Bitmap src, int w, int h, float bias)
            throws java.io.IOException {
        int n = src.getByteCount();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(HEADER_INTS * 4 + 4 + n);
        buf.putInt(MAGIC).putInt(VERSION)
                .putInt(src.getWidth()).putInt(src.getHeight())
                .putInt(w).putInt(h)
                .putInt(colorSpaceIndex(src))
                .putFloat(bias);
        src.copyPixelsToBuffer(buf);
        buf.flip();
        java.io.File tmp = new java.io.File(dest.getPath() + ".tmp");
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            java.nio.channels.FileChannel ch = out.getChannel();
            while (buf.hasRemaining()) ch.write(buf);
        } finally {
            out.close();
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(dest)) {
            throw new java.io.IOException("rename " + tmp + " -> " + dest + " failed");
        }
    }

    /** The source a hand-over file holds, or null if it is not one this build wrote. */
    static Source readSource(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.nio.channels.FileChannel ch = in.getChannel();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect((int) ch.size());
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // read it all
            }
            buf.flip();
            if (buf.remaining() < HEADER_INTS * 4 + 4) return null;
            if (buf.getInt() != MAGIC || buf.getInt() != VERSION) return null;
            int sw = buf.getInt(), sh = buf.getInt(), w = buf.getInt(), h = buf.getInt();
            int cs = buf.getInt();
            float bias = buf.getFloat();
            if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0
                    || buf.remaining() < (long) sw * sh * 4) {
                return null;
            }
            Bitmap src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888, true,
                    colorSpaceAt(cs));
            src.copyPixelsFromBuffer(buf);
            return new Source(src, w, h, bias);
        } finally {
            in.close();
        }
    }

    /** A named colour space's index, so a Display P3 source is composed as P3 on both sides. */
    private static int colorSpaceIndex(Bitmap b) {
        android.graphics.ColorSpace cs = b.getColorSpace();
        if (cs == null) return -1;
        android.graphics.ColorSpace.Named[] all = android.graphics.ColorSpace.Named.values();
        for (int i = 0; i < all.length; i++) {
            if (android.graphics.ColorSpace.get(all[i]).equals(cs)) return i;
        }
        return -1;
    }

    private static android.graphics.ColorSpace colorSpaceAt(int i) {
        android.graphics.ColorSpace.Named[] all = android.graphics.ColorSpace.Named.values();
        return android.graphics.ColorSpace.get(i >= 0 && i < all.length
                ? all[i] : android.graphics.ColorSpace.Named.SRGB);
    }

    /**
     * Lays the cover out the way the Apple reference does: the artwork sharp at full width and
     * its own aspect, with a heavily blurred copy filling the screen above and below it.
     * Center-cropping a square cover into a 1200x2608 screen instead zooms ~5x and throws most
     * of the artwork away - on the Lover cover it cut the face in half.
     *
     * bias places the sharp band in the leftover vertical space: 0 flush with the top, 0.5
     * centred, 1 flush with the bottom. Centred is what the media card covers, so the default
     * sits above that.
     */
    static Bitmap composeWallpaper(final Bitmap src, final int w, int h, float bias) {
        final float coverH = src.getHeight() * (w / (float) src.getWidth());
        if (bias < 0f) bias = 0f;
        if (bias > 1f) bias = 1f;
        final float top = (h - coverH) * bias;

        Bitmap bg = mirroredBackground(src, w, h, bias);
        final Bitmap blurred = blur(bg, 48, 4, 3);
        if (blurred != bg) bg.recycle();
        try {
            return full(w, h, cv -> {
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.FILTER_BITMAP_FLAG);
                // The 8% wash rides on the stretch rather than being a full-screen pass of its own.
                p.setColorFilter(WASH);
                cv.drawBitmap(blurred, null, new android.graphics.RectF(0, 0, w, h), p);
                p.setColorFilter(null);
                drawBand(cv, src, w, Math.round(coverH), Math.round(top),
                        Math.min(240, Math.round(coverH / 4f)), p);
            });
        } finally {
            blurred.recycle();
        }
    }

    /** Card mode uses the same mirrored, softened artwork as the full-cover backdrop. */
    static Bitmap cardBackground(Bitmap src, int w, int h) {
        Bitmap backdrop = mirroredBackground(src, w, h, 0.5f);
        Bitmap soft = blur(backdrop, 48, 4, 3);
        try {
            return full(w, h, cv -> {
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.FILTER_BITMAP_FLAG);
                p.setColorFilter(WASH);
                cv.drawBitmap(soft, null, new android.graphics.RectF(0, 0, w, h), p);
            });
        } finally {
            if (soft != backdrop) soft.recycle();
            backdrop.recycle();
        }
    }

    /**
     * drawColor(0x14000000) over an opaque picture, as a colour filter: source-over black at
     * 0x14 is every channel times (255 - 0x14) / 255. Put on the paint that stretches the small
     * blur, it costs nothing, where the drawColor was one more pass over all 3.1M pixels.
     */
    private static final android.graphics.ColorMatrixColorFilter WASH;

    static {
        float k = (255 - 0x14) / 255f;
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setScale(k, k, k, 1f);
        WASH = new android.graphics.ColorMatrixColorFilter(m);
    }

    /**
     * The quarter-size backdrop the composition blurs: the artwork at its place in the layout,
     * mirrored away from there until it fills the screen.
     *
     * Blurring a centre-crop gave a muddy wash whose colours did not meet the sharp cover at the
     * seam. Extending the artwork by MIRRORING it means the rows either side of a seam are the
     * same row of the artwork, so the join is continuous by construction, and the blur keeps
     * local colour instead of averaging the whole image.
     *
     * Its own method because the lyrics' frosted copy is built from the same backdrop - see
     * frostedFor(), which is the whole reason that copy no longer costs more than the
     * composition it follows.
     */
    private static Bitmap mirroredBackground(Bitmap src, int w, int h, float bias) {
        float coverH = src.getHeight() * (w / (float) src.getWidth());
        float top = (h - coverH) * bias;
        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        float k = bw / (float) w;
        // Floored at a pixel: a panorama's band is a fraction of a row, and the loop below counts
        // copies of it.
        float cH = Math.max(1f, coverH * k), tp = top * k;
        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bg);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, tp, bw, tp + cH), p);
        // One mirrored copy each way is what this was, and it covers the background only while
        // the band is a large part of the screen: a square cover puts it at 1200 of 2608 and the
        // copy reaches both edges. A LANDSCAPE cover does not. Measured on a 960x539 artwork at
        // the default bias: the band is 674px, the single copy below it ends at 2057, and the
        // last 551 rows were never painted at all - the blur came out with a black bottom fifth.
        // The mirror is periodic with 2*coverH either way, so this draws the same picture,
        // continued until the background runs out.
        int tiles = Math.min(64, (int) Math.ceil(bh / cH) + 1);
        for (int i = 1; i <= tiles; i++) {
            boolean flip = (i % 2) == 1;
            drawTile(bc, src, bw, cH, tp + i * cH, flip, p);
            drawTile(bc, src, bw, cH, tp - i * cH, flip, p);
        }
        return bg;
    }

    /**
     * One mirrored copy of the artwork, cH tall with its top edge at y, clipped to the canvas.
     *
     * `flip` alternates down the strip, and that is what makes it a mirror rather than a repeat:
     * every copy is the reflection of the one before it, so the rows either side of a seam are
     * the same row of the artwork and the join is continuous by construction. Drawing it as a
     * translate to the tile's own bottom edge plus a vertical scale of -1, into a destination
     * rect that starts at this canvas's origin, is the same transform the one-copy version used
     * for both of the copies it drew.
     */
    private static void drawTile(android.graphics.Canvas cv, Bitmap src, int bw, float cH,
                                 float y, boolean flip, android.graphics.Paint p) {
        if (y > cv.getHeight() || y + cH < 0f) return;
        if (!flip) {
            cv.drawBitmap(src, null, new android.graphics.RectF(0, y, bw, y + cH), p);
            return;
        }
        cv.save();
        cv.translate(0, y + cH);
        cv.scale(1f, -1f);
        cv.drawBitmap(src, null, new android.graphics.RectF(0, 0, bw, cH), p);
        cv.restore();
    }

    /**
     * The artwork at full width and its own aspect, `bandH` rows from row `top`, with the top
     * and bottom `feather` rows faded out - drawn straight onto the composition in one pass.
     *
     * Masking a layer is what this replaced first, and it put a one-pixel line along the band's
     * top edge: the layer began at a fractional `top`, the mask rect over it was snapped to whole
     * pixels by a different rule, and the band's first row landed on the background at full
     * strength. Then the band was its own 1200x1200 bitmap with the ramp in its pixels - right,
     * but a 5.7MB allocation and two more passes on every cover. Here the ramp is the alpha of
     * the shader that draws the artwork, and the band starts on a whole row, so its first row
     * still has nothing to show and there is no fractional bound for anything to disagree about.
     */
    private static void drawBand(android.graphics.Canvas cv, Bitmap src, int w, int bandH,
                                 int top, int feather, android.graphics.Paint p) {
        android.graphics.BitmapShader art = new android.graphics.BitmapShader(src,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(w / (float) src.getWidth(), bandH / (float) src.getHeight());
        m.postTranslate(0f, top);
        art.setLocalMatrix(m);
        android.graphics.Shader shader = art;
        if (feather > 0 && feather * 2 <= bandH) {
            float f = feather / (float) bandH;
            android.graphics.LinearGradient ramp = new android.graphics.LinearGradient(
                    0f, top, 0f, top + bandH,
                    new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[]{0f, f, 1f - f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            shader = new android.graphics.ComposeShader(art, ramp,
                    android.graphics.PorterDuff.Mode.DST_IN);
        }
        p.setShader(shader);
        cv.drawRect(0f, top, w, top + bandH, p);
        p.setShader(null);
    }

    /**
     * The cover behind the lock screen lyrics: the same picture at the same size, blurred until
     * only its colour is left and darkened so white text reads over any of it. Saturation goes up
     * a little first, because darkening alone turns a bright cover grey.
     */
    static Bitmap frosted(Bitmap src) {
        return frostedOf(blur(src, 36, 3, 3), src, src.getWidth(), src.getHeight());
    }

    /**
     * The same frosted cover, built from the layout instead of from the finished composition.
     *
     * Measured on device, 1200x2608: frosted() cost 110ms a track change - more than composing
     * the cover it follows - and nearly all of it went on carrying a full-screen picture down to
     * 36 pixels wide, one halving at a time, to throw away everything but its colour. The
     * backdrop the composition already builds is that colour, at a quarter of the size and with
     * the artwork in the same place, so blurring THAT to 36 lands on the same pixels for a
     * sixteenth of the work.
     *
     * Two things the finished composition has that this does not: the sharp band, which is the
     * same artwork in the same place and vanishes at 36 pixels either way, and the 8% darkening
     * over the backdrop, which is applied here so the two paths agree.
     */
    static Bitmap frostedFor(Bitmap src, int w, int h, float bias) {
        Bitmap small = frostedSmallFor(src, w, h, bias);
        Bitmap out = stretchFrosted(small, w, h);
        small.recycle();
        return out;
    }

    /**
     * frostedFor() before its stretch: every colour it will have, at the blur's own size (a few
     * hundred pixels wide, ~0.3MB). Small enough to keep around, which is the point - kept, the
     * frosted cover is one stretch away (stretchFrosted) instead of a blur.
     */
    static Bitmap frostedSmallFor(Bitmap src, int w, int h, float bias) {
        Bitmap bg = mirroredBackground(src, w, h, bias);
        Bitmap soft = blur(bg, 36, 3, 3);
        if (soft != bg) bg.recycle();
        return colouredFrost(soft, null);
    }

    /** A frostedSmallFor() picture at the texture's size. The small one is the caller's. */
    static Bitmap stretchFrosted(final Bitmap small, final int w, final int h) {
        return full(w, h, cv -> cv.drawBitmap(small, null,
                new android.graphics.RectF(0, 0, w, h),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)));
    }

    /**
     * Whether full-screen draws go to the GPU (full). The wallpaper process's switch alone: in
     * SystemUI the GPU's renderer is the lock screen's own, and a cover drawn there would queue
     * in front of its frames.
     */
    static volatile boolean sGpu;
    private static volatile boolean sGpuBroken;

    /** One full-screen picture's draws. */
    interface Draw {
        void into(android.graphics.Canvas c);
    }

    /**
     * A w x h software bitmap with [draw] in it, drawn by the GPU where sGpu says so.
     *
     * Rasterising these on the CPU was the cost of a new cover: the stretch of the blur over all
     * 3.1M pixels, the colour filter on it, the band and the frosting's stretch - 736ms of CPU on
     * a phone at 1.1GHz with four cores up, the most of any thread on it while a track changed,
     * and SystemUI's own frames went 73-112ms waiting for those cores (trace 2026-10-20). Recorded
     * as a Picture and rendered as a HARDWARE bitmap, the same draws are a GPU pass, and what is
     * left on the CPU is reading the pixels back - which the texture upload needs anyway. The
     * bitmaps drawn from must stay alive until this returns; it is synchronous.
     */
    static Bitmap full(int w, int h, Draw draw) {
        if (sGpu && !sGpuBroken) {
            try {
                android.graphics.Picture pic = new android.graphics.Picture();
                draw.into(pic.beginRecording(w, h));
                pic.endRecording();
                Bitmap hw = Bitmap.createBitmap(pic, w, h, Bitmap.Config.HARDWARE);
                Bitmap out = hw.copy(Bitmap.Config.ARGB_8888, true);
                hw.recycle();
                if (out != null) return out;
                throw new IllegalStateException("no read back");
            } catch (Throwable t) {
                sGpuBroken = true;
                Xp.w("[MCCompose] GPU drawing unavailable, back on the CPU: " + t);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        draw.into(new android.graphics.Canvas(out));
        return out;
    }

    /**
     * The shared tail: a blurred picture stretched over the whole screen, saturated a little
     * because darkening alone turns a bright cover grey, and then darkened so white text reads
     * over any of it. `keep` is the caller's own bitmap, never recycled here.
     */
    private static Bitmap frostedOf(Bitmap soft, Bitmap keep, int w, int h) {
        Bitmap small = colouredFrost(soft, keep);
        Bitmap out = stretchFrosted(small, w, h);
        small.recycle();
        return out;
    }

    private static Bitmap colouredFrost(Bitmap soft, Bitmap keep) {
        // Coloured at the blur's own size, then stretched once.
        //
        // Saturating and darkening are per-pixel linear operations and so is the stretch, so the
        // two orders land on the same colours - but after the stretch they run over every pixel
        // of the screen, three times, and before it they run over a sixteenth as many. The stretch
        // itself still has to happen either way; what this removes is the work on top of it.
        int sw = soft.getWidth(), sh = soft.getHeight();
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas sc = new android.graphics.Canvas(small);
        android.graphics.Paint sp = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.3f);
        sp.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        sc.drawBitmap(soft, 0f, 0f, sp);
        // 0x14 is the wash composeWallpaper() lays over its own backdrop; 0x61 is the frosting.
        // Both, so a cover frosted from the layout matches one frosted from the composition.
        if (keep == null) sc.drawColor(0x14000000);
        sc.drawColor(0x61000000);
        if (soft != keep) soft.recycle();
        return small;
    }

    /**
     * What a composition depends on, as one number: the source's pixels (a 64x64 grid of them),
     * its size and colour space, the screen, the bias and the mode. Equal keys compose to the
     * same picture, so a composition can be kept and handed out again under its key.
     */
    static long sourceKey(Source s, boolean cardMode) {
        Bitmap b = s.src;
        int bw = b.getWidth(), bh = b.getHeight();
        int sx = Math.max(1, bw / 64), sy = Math.max(1, bh / 64);
        int[] row = new int[bw];
        long k = 1125899906842597L;
        for (int y = 0; y < bh; y += sy) {
            b.getPixels(row, 0, bw, 0, y, bw, 1);
            for (int x = 0; x < bw; x += sx) k = 31 * k + row[x];
        }
        k = 31 * k + bw;
        k = 31 * k + bh;
        k = 31 * k + colorSpaceIndex(b);
        k = 31 * k + s.w;
        k = 31 * k + s.h;
        k = 31 * k + Float.floatToIntBits(s.bias);
        return 31 * k + (cardMode ? 1 : 0);
    }

    /**
     * Downscaling hard and letting one bilinear upscale smear it back is not a blur - it leaves
     * the tell-tale blocky diamonds of interpolating a tiny image. Halve step by step (each
     * halving is a box average), run a real separable box blur at the small size where it costs
     * almost nothing, then double back up, so nothing is ever interpolated across a big jump.
     */
    public static Bitmap blur(Bitmap src, int smallW, int radius, int passes) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 > smallW) {
            Bitmap next = Bitmap.createScaledBitmap(cur,
                    cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        int sh = Math.max(1, cur.getHeight() * smallW / cur.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(cur, smallW, sh, true);
        if (cur != src) cur.recycle();

        int n = smallW * sh;
        int[] a = new int[n], b = new int[n];
        small.getPixels(a, 0, smallW, 0, 0, smallW, sh);
        for (int i = 0; i < passes; i++) {
            boxH(a, b, smallW, sh, radius);
            boxV(b, a, smallW, sh, radius);
        }
        small.setPixels(a, 0, smallW, 0, 0, smallW, sh);

        // Climb back up in doublings; the caller's final draw stretches the last step.
        Bitmap up = small;
        while (up.getWidth() * 2 <= src.getWidth()) {
            Bitmap next = Bitmap.createScaledBitmap(up, up.getWidth() * 2, up.getHeight() * 2, true);
            if (up != small) up.recycle();
            up = next;
        }
        if (up != small) small.recycle();
        return up;
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[base + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[base + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[base + Math.min(w - 1, Math.max(0, x - r))];
                int in = src[base + Math.min(w - 1, Math.max(0, x + r + 1))];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[Math.min(h - 1, Math.max(0, y - r)) * w + x];
                int in = src[Math.min(h - 1, Math.max(0, y + r + 1)) * w + x];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }
}
