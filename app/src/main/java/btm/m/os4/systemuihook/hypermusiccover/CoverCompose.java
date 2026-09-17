package btm.m.os4.systemuihook.hypermusiccover;

import android.graphics.Bitmap;

/**
 * The cover composition, shared by both processes.
 *
 * Lives on its own so the wallpaper process can call it without touching Main, whose static
 * state belongs to SystemUI. Pure: the same source, size and bias give the same pixels in
 * either process, which is what lets SystemUI hand over the small source instead of the
 * composed picture - see Main.pushArtToWallpaper().
 */
final class CoverCompose {

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
    static Bitmap composeWallpaper(Bitmap src, int w, int h, float bias) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);

        float coverH = src.getHeight() * (w / (float) src.getWidth());
        if (bias < 0f) bias = 0f;
        if (bias > 1f) bias = 1f;
        float top = (h - coverH) * bias;

        // Blurring a centre-crop gave a muddy wash whose colours did not meet the sharp cover at
        // the seam. Extend the artwork by MIRRORING it above and below instead: the rows either
        // side of a seam are then the same row of the artwork, so the join is continuous by
        // construction, and the blur keeps local colour instead of averaging the whole image.
        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        float k = bw / (float) w;
        // Floored at a pixel: a panorama's band is a fraction of a row, and the loop below counts
        // copies of it.
        float cH = Math.max(1f, coverH * k), tp = top * k;
        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bg);
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

        Bitmap blurred = blur(bg, 48, 4, 3);
        cv.drawBitmap(blurred, null, new android.graphics.RectF(0, 0, w, h), p);
        cv.drawColor(0x14000000);
        // The sharp band, feathered in its own pixels and then drawn in one go. See feathered().
        Bitmap band = feathered(src, w, Math.round(coverH),
                Math.min(240, Math.round(coverH / 4f)));
        cv.drawBitmap(band, null, new android.graphics.RectF(0, top, w, top + coverH), p);
        band.recycle();
        return out;
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
     * The artwork at full width and its own aspect, with the top and bottom `feather` rows faded
     * to transparent - in the pixels, not by masking a layer.
     *
     * Masking is what this replaces, and it put a one-pixel line along the band's top edge. The
     * band was drawn into a saveLayer whose bounds begin at a fractional `top`, and the mask
     * rect over it was snapped to whole pixels by a different rule than the layer was, so the
     * band's first row could fall outside the mask and land on the blurred background at full
     * strength - a line that follows the artwork's own brightness, bright where the row below is
     * bright and dark where it is dark, which is exactly what it looked like on the phone.
     *
     * Here the ramp is part of the picture, so that first row has nothing to show whatever the
     * rounding does. The mask rects are on a bitmap whose edges are 0 and bandH - both whole
     * pixels - so there is no fractional bound left for anything to disagree about.
     */
    private static Bitmap feathered(Bitmap src, int w, int bandH, int feather) {
        Bitmap band = Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(band);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, 0, w, bandH),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        if (feather > 0 && feather * 2 <= bandH) {
            android.graphics.Paint mask = new android.graphics.Paint();
            mask.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN));
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, feather,
                    0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, 0, w, feather, mask);
            mask.setShader(new android.graphics.LinearGradient(0, bandH - feather, 0, bandH,
                    0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, bandH - feather, w, bandH, mask);
        }
        return band;
    }

    /**
     * The cover behind the lock screen lyrics: the same picture at the same size, blurred until
     * only its colour is left and darkened so white text reads over any of it. Saturation goes up
     * a little first, because darkening alone turns a bright cover grey.
     */
    static Bitmap frosted(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap soft = blur(src, 36, 3, 3);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.3f);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        cv.drawBitmap(soft, null, new android.graphics.RectF(0, 0, w, h), p);
        cv.drawColor(0x61000000);
        if (soft != src) soft.recycle();
        return out;
    }

    /**
     * Downscaling hard and letting one bilinear upscale smear it back is not a blur - it leaves
     * the tell-tale blocky diamonds of interpolating a tiny image. Halve step by step (each
     * halving is a box average), run a real separable box blur at the small size where it costs
     * almost nothing, then double back up, so nothing is ever interpolated across a big jump.
     */
    private static Bitmap blur(Bitmap src, int smallW, int radius, int passes) {
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
