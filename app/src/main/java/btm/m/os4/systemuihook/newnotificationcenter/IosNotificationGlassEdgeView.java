package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/** A small sampled/refracted rim at the bottom of the iOS notification sheet. */
final class IosNotificationGlassEdgeView extends View {
    private static final int CAPTURE_HEIGHT = 112;
    private static final long CAPTURE_INTERVAL_MS = 33L;
    private static final String EDGE_SHADER = """
            uniform shader uContent;
            uniform float2 uScreenOrigin;
            uniform float2 uCaptureOrigin;
            uniform float2 uCaptureScale;
            uniform float2 uSize;
            uniform float uBandTop;
            uniform float uBandHeight;
            uniform float uRadius;
            uniform float uAmount;
            uniform float uAlpha;

            half4 sampleContent(float2 screenCoord) {
                return uContent.eval((screenCoord - uCaptureOrigin) * uCaptureScale);
            }

            half4 main(float2 fragCoord) {
                float y = fragCoord.y;
                float band = smoothstep(uBandTop, uBandTop + 5.0, y);
                float edge = 1.0 - smoothstep(uBandTop + uBandHeight * 0.56,
                        uBandTop + uBandHeight, y);
                float2 screenCoord = fragCoord + uScreenOrigin;
                float2 center = float2(uSize.x * 0.5, uBandTop + uBandHeight);
                float2 direction = normalize(fragCoord - center + float2(0.0001));
                float curve = smoothstep(0.0, 1.0, (y - uBandTop) / uBandHeight);
                float2 refractedCoord = screenCoord + direction * (uAmount * curve);
                half4 centerSample = sampleContent(refractedCoord);
                half4 upperSample = sampleContent(refractedCoord + float2(0.0, -uRadius));
                half4 lowerSample = sampleContent(refractedCoord + float2(0.0, uRadius));
                half3 refracted = half3(upperSample.r, centerSample.g, lowerSample.b);
                float highlight = pow(1.0 - curve, 2.0) * 0.32;
                float alpha = band * edge * uAlpha;
                half3 color = mix(centerSample.rgb, refracted, 0.72) + half3(highlight);
                return half4(color, alpha);
            }
            """;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RuntimeShader shader;
    private final RectF bounds = new RectF();
    private View source;
    private Bitmap frame;
    private long lastCapture;
    private int sourceScreenX;
    private int sourceScreenY;
    private int captureTop;

    IosNotificationGlassEdgeView(Context context) {
        super(context);
        RuntimeShader value;
        try {
            value = new RuntimeShader(EDGE_SHADER);
        } catch (Throwable ignored) {
            value = null;
        }
        shader = value;
        paint.setShader(shader);
        setWillNotDraw(false);
        setClickable(false);
        setEnabled(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSource(View value) {
        if (source == value) return;
        source = value;
        lastCapture = 0L;
        invalidate();
    }

    void release() {
        source = null;
        if (frame != null && !frame.isRecycled()) frame.recycle();
        frame = null;
        paint.setShader(null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (shader == null || source == null || !source.isAttachedToWindow()
                || source.getWidth() <= 0 || source.getHeight() <= 0 || getWidth() <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCapture >= CAPTURE_INTERVAL_MS) captureSource();
        Bitmap bitmap = frame;
        if (bitmap == null || bitmap.isRecycled()) return;
        int[] point = new int[2];
        getLocationOnScreen(point);
        float width = getWidth();
        float height = getHeight();
        float bandTop = Math.max(0f, height - 34f * getResources().getDisplayMetrics().density);
        float radius = Math.max(1f, 7f * getResources().getDisplayMetrics().density);
        shader.setInputShader("uContent", new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP));
        shader.setFloatUniform("uScreenOrigin", point[0], point[1]);
        shader.setFloatUniform("uCaptureOrigin", sourceScreenX,
                sourceScreenY + captureTop);
        shader.setFloatUniform("uCaptureScale", bitmap.getWidth() / (float) source.getWidth(),
                bitmap.getHeight() / (float) CAPTURE_HEIGHT);
        shader.setFloatUniform("uSize", width, height);
        shader.setFloatUniform("uBandTop", bandTop);
        shader.setFloatUniform("uBandHeight", height - bandTop);
        shader.setFloatUniform("uRadius", radius);
        shader.setFloatUniform("uAmount", 8f * getResources().getDisplayMetrics().density);
        shader.setFloatUniform("uAlpha", 0.28f);
        bounds.set(0f, bandTop - 2f, width, height);
        canvas.drawRect(bounds, paint);
        if (source.isShown()) postInvalidateOnAnimation();
    }

    private void captureSource() {
        View view = source;
        if (view == null) return;
        int width = view.getWidth();
        int height = Math.min(CAPTURE_HEIGHT, view.getHeight());
        if (width <= 0 || height <= 0) return;
        Bitmap bitmap = frame;
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != width
                || bitmap.getHeight() != height) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame = bitmap;
        }
        try {
            int[] point = new int[2];
            view.getLocationOnScreen(point);
            sourceScreenX = point[0];
            sourceScreenY = point[1];
            captureTop = Math.max(0, view.getHeight() - height);
            Canvas bitmapCanvas = new Canvas(bitmap);
            bitmapCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            bitmapCanvas.save();
            bitmapCanvas.clipRect(0, 0, width, height);
            bitmapCanvas.translate(-view.getScrollX(), -(captureTop + view.getScrollY()));
            view.draw(bitmapCanvas);
            bitmapCanvas.restore();
            lastCapture = android.os.SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            // Sampling is an enhancement; a transient detached/rebound source must not affect
            // the notification panel or its gesture pipeline.
        }
    }
}
