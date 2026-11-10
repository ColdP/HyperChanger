// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/** Shared decoration for the card and the existing thumbnail morph. */
final class VinylRecordRenderer {
    static boolean rotation;
    static boolean tonearm;
    static int colorMode;
    static int customColor = 0xFF292929;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SweepGradient sheen;
    private float lastX = Float.NaN, lastY = Float.NaN;
    private final RectF labelEdge = new RectF(-.98f, -.98f, .98f, .98f);
    private final RectF head = new RectF(-.054f, -.13f, .054f, .13f);
    private final RectF counterweight = new RectF(.78f, -.88f, .90f, -.75f);
    private final android.graphics.Path arm = new android.graphics.Path();
    private final RadialGradient innerShadow = new RadialGradient(0f, 0f, 1f,
            new int[] {0x00000000, 0x00000000, 0x18000000, 0x60000000, 0xB0000000},
            new float[] {0f, .70f, .86f, .96f, 1f}, Shader.TileMode.CLAMP);
    private final android.graphics.LinearGradient headMetal = new android.graphics.LinearGradient(-.054f, 0f, .054f, 0f,
            new int[] {0xFF31343B, 0xFF9298A1, 0xFF3A3E45}, null, Shader.TileMode.CLAMP);

    VinylRecordRenderer() {
        arm.moveTo(.84f, -.68f);
        arm.lineTo(.73f, -.06f);
        arm.quadTo(.71f, .02f, .66f, .08f);
        arm.lineTo(.37f, .43f);
    }

    static int color() {
        if (colorMode == 2) return customColor;
        if (colorMode != 1) return 0xFF292929;
        return CoverCardLayer.vinylColor();
    }

    void draw(Canvas canvas, RectF bounds, float radius, float opacity, float amount) {
        int color = color();
        float alpha = opacity * Color.alpha(color) / 255f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha(Math.round(255f * alpha));
        canvas.drawRoundRect(bounds, radius, radius, paint);
        float x = bounds.centerX(), y = bounds.centerY();
        if (sheen == null || x != lastX || y != lastY) {
            lastX = x;
            lastY = y;
            sheen = new SweepGradient(x, y,
                    new int[] {0x00FFFFFF, 0x48FFFFFF, 0x00FFFFFF, 0x28000000,
                            0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                    new float[] {0f, .16f, .32f, .5f, .66f, .82f, 1f});
        }
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(255f * alpha * amount));
        paint.setShader(sheen);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        float half = Math.min(bounds.width(), bounds.height()) * .5f;
        paint.setStrokeWidth(Math.max(.7f, half * .003f));
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(28f * alpha * amount));
        for (int i = 0; i < 9; i++) {
            canvas.drawCircle(x, y, half * (.75f + i * .026f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Drawn over the artwork, after rotation: light and the inset edge stay stationary. */
    void drawArtworkFinish(Canvas canvas, RectF artwork, float opacity, float amount) {
        float alpha = opacity * amount;
        if (alpha <= 0f) return;
        int save = canvas.save();
        canvas.translate(artwork.centerX(), artwork.centerY());
        float radius = Math.min(artwork.width(), artwork.height()) * .5f;
        canvas.scale(radius, radius);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(255f * alpha));
        paint.setShader(innerShadow);
        canvas.drawCircle(0f, 0f, 1f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.04f);
        paint.setColor(Color.BLACK);
        paint.setAlpha(Math.round(100f * alpha));
        canvas.drawArc(labelEdge, 190f, 130f, false, paint);
        paint.setStrokeWidth(.012f);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(110f * alpha));
        canvas.drawArc(labelEdge, 28f, 105f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(save);
    }

    /** Unit geometry stays inside the existing cover box and follows its existing scale. */
    void drawTonearm(Canvas canvas, RectF bounds, float opacity, float amount) {
        if (!tonearm || opacity <= 0f || amount <= 0f) return;
        float alpha = opacity * amount;
        int save = canvas.save();
        canvas.translate(bounds.centerX(), bounds.centerY());
        float radius = Math.min(bounds.width(), bounds.height()) * .5f;
        canvas.scale(radius, radius);
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // Offset, layered shadows avoid a new bitmap or blur layer on rotation frames.
        int shadowSave = canvas.save();
        canvas.translate(.018f, .026f);
        paint.setStyle(Paint.Style.STROKE);
        color(Color.BLACK, alpha);
        for (int i = 3; i >= 1; i--) {
            paint.setStrokeWidth(.05f + i * .018f);
            paint.setAlpha(Math.round(20f * alpha));
            canvas.drawPath(arm, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(shadowSave);
        color(0xFF24272D, alpha);
        canvas.drawRoundRect(counterweight, .025f, .025f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.062f);
        color(0xFF272B31, alpha);
        canvas.drawPath(arm, paint);
        paint.setStrokeWidth(.042f);
        color(0xFF9CA3AC, alpha);
        canvas.drawPath(arm, paint);
        int highlightSave = canvas.save();
        canvas.translate(-.009f, -.006f);
        paint.setStrokeWidth(.010f);
        color(0xFFEEF0F4, alpha);
        canvas.drawPath(arm, paint);
        canvas.restoreToCount(highlightSave);
        paint.setStyle(Paint.Style.FILL);
        int headSave = canvas.save();
        canvas.translate(.37f, .43f);
        canvas.rotate(40f);
        color(Color.BLACK, alpha);
        paint.setAlpha(Math.round(65f * alpha));
        canvas.translate(.015f, .025f);
        canvas.drawRoundRect(head, .025f, .025f, paint);
        canvas.translate(-.015f, -.025f);
        color(Color.WHITE, alpha);
        paint.setAlpha(Math.round(255f * alpha));
        paint.setShader(headMetal);
        canvas.drawRoundRect(head, .018f, .018f, paint);
        paint.setShader(null);
        color(0xFFD8DCE2, alpha);
        canvas.drawRect(-.035f, -.105f, -.026f, .08f, paint);
        color(0xFF17191E, alpha);
        canvas.drawRect(-.035f, .075f, .035f, .14f, paint);
        color(0xFFE4C47A, alpha);
        canvas.drawRect(-.009f, .12f, .009f, .16f, paint);
        canvas.restoreToCount(headSave);
        canvas.restoreToCount(save);
    }

    private void color(int value, float opacity) {
        paint.setColor(value);
        paint.setAlpha(Math.round(Color.alpha(value) * opacity));
    }

    static void artworkBounds(RectF outer, RectF artwork, float amount) {
        artwork.set(outer);
        float inset = Math.min(outer.width(), outer.height()) * .14f * amount;
        artwork.inset(inset, inset);
    }
}
