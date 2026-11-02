package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Creates an owned preview texture without cropping or recycling the system's source. */
public final class IosShadeWallpaper {
    private IosShadeWallpaper() {}

    public static Bitmap copyPreview(Bitmap source, int displayWidth, int displayHeight) {
        if (source == null || source.isRecycled()) return null;
        double budget = Math.max(1L, (long) Math.max(1, displayWidth) * Math.max(1, displayHeight));
        double ratio = Math.min(1d, Math.sqrt(budget / ((long) source.getWidth() * source.getHeight())));
        int width = Math.max(1, (int) Math.floor(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.floor(source.getHeight() * ratio));
        Bitmap owned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            new Canvas(owned).drawBitmap(source, null, new RectF(0f, 0f, width, height),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            return owned;
        } catch (Throwable error) {
            owned.recycle();
            throw error;
        }
    }
}
