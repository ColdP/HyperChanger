package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Distance-field liquid optics, over refreshed hardware frames in screen coordinates. */
final class IosShadeLiquidLens {
    private static final String OPTICS =
            "uniform shader content;\n"
            + "uniform float2 size; uniform float2 sceneSize;\n"
            + "uniform float offsetY; uniform float sourceTop; uniform float radius; uniform float density;\n"
            + "uniform float nativeBlurMix;\n"
            + "float2 safeNormal(float2 v) { return v / max(length(v), 0.0001); }\n"
            + "half4 sampleScene(float2 p) {\n"
            + " return content.eval(clamp(p + float2(0.0, offsetY - sourceTop), float2(0.5), sceneSize - 0.5)); }\n"
            + "half4 main(float2 p) {\n"
            + " float2 hs = size * 0.5; float2 cc = p - hs;\n"
            + " float2 q = abs(cc) - (hs - radius);\n"
            + " float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + " float band = max(20.0 * density, 1.0);\n"
            + " float blend = max(20.0 * density, 1.0);\n"
            + " float bottom = smoothstep(size.y - radius - band - blend, size.y - radius - band + 2.0 * density, p.y);\n"
            + " float edge = max(0.35 * density, 0.5);\n"
            + " if (sd > edge || -sd >= band || bottom <= 0.0) return half4(0.0);\n"
            + " float t = clamp(-sd / band, 0.0, 1.0);\n"
            + " float fade = 1.0 - t * t * (3.0 - 2.0 * t);\n"
            + " float circle = 1.0 - sqrt(max(0.0, 1.0 - (1.0 - t) * (1.0 - t)));\n"
            + " float2 s = mix(float2(-1.0), float2(1.0), step(float2(0.0), cc));\n"
            + " float2 g = max(q, 0.0);\n"
            + " if (length(g) > 0.0001) g = s * safeNormal(g);\n"
            + " else g = s * (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + " float2 n = safeNormal(g + fade * safeNormal(cc));\n"
            + " float d = -48.0 * density * circle * fade;\n"
            + " float2 uv = p + d * n;\n"
            + " float position = abs(cc.x * cc.y) / max(hs.x * hs.y, 1.0);\n"
            + " float2 split = d * n * (0.04 * (0.4 + 0.6 * position));\n"
            + " half4 red = sampleScene(uv + split); half4 green = sampleScene(uv);\n"
            + " half4 blue = sampleScene(uv - split);\n"
            + " half a = min(red.a, min(green.a, blue.a));\n"
            + " half3 rgb = half3(red.r / max(red.a, half(0.001)), green.g / max(green.a, half(0.001)), blue.b / max(blue.a, half(0.001)));\n"
            + " half luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));\n"
            + " rgb = clamp(mix(half3(luma), rgb, half(1.3)), 0.0, 1.0);\n"
            + " float light = dot(g, float2(0.70710678));\n"
            + " float highlight = 0.56 * pow(max(light, 0.0), 0.65) + 0.1972 * pow(max(-light, 0.0), 1.1);\n"
            + " rgb = mix(rgb, half3(1.0), half(highlight * fade * 0.25));\n"
            + " float coverage = (1.0 - smoothstep(-edge, edge, sd)) * bottom;\n"
            + " coverage *= 1.0 - smoothstep(0.15, 1.0, t);\n"
            // The shade-excluded source is sharp. Reveal the real system blur underneath
            // rather than covering it with opaque optical pixels or a second blur pass.
            + " coverage *= 1.0 - nativeBlurMix;\n"
            + " a *= half(coverage); return half4(rgb * a, a); }\n";

    private final View host;
    private final Runnable frameReady;
    private final Paint filtered = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Runnable refresh = this::capture;
    private RuntimeShader shader;
    private ViewGroup root;
    private Bitmap frame;
    private BitmapShader texture;
    private int sourceTop;
    private boolean running, pending, unavailable;
    private int generation, failures, width, height, sheetWidth, sheetHeight;
    private float offsetY, radius;
    private long requestedAt;

    IosShadeLiquidLens(View host, Runnable frameReady) { this.host = host; this.frameReady = frameReady; }

    boolean hasFrame() { return frame != null && shader != null; }

    void update(ViewGroup window, float offset, float corner, boolean visible) {
        if (window != root) { reset(); root = window; }
        if (window == null) return;
        offsetY = offset;
        radius = corner;
        int w = window.getWidth(), h = window.getHeight();
        boolean resized = width != w || height != h || sheetWidth != host.getWidth() || sheetHeight != host.getHeight();
        if (resized) {
            width = w; height = h;
            sheetWidth = host.getWidth(); sheetHeight = host.getHeight();
            generation++;
            pending = false;
            failures = 0;
            host.removeCallbacks(refresh);
            if (frame != null && w > 0 && h > 0 && sheetWidth > 0 && sheetHeight > 0) {
                uniforms();
            }
        }
        if (!visible || w <= 0 || h <= 0 || sheetWidth <= 0 || sheetHeight <= 0) {
            if (running) { generation++; pending = false; }
            running = false;
            host.removeCallbacks(refresh);
            return;
        }
        if (unavailable) return;
        if (shader == null) {
            try {
                shader = new RuntimeShader(OPTICS);
                filtered.setShader(shader);
            } catch (Throwable error) {
                fail(error);
                return;
            }
        }
        boolean starting = !running;
        running = true;
        uniforms();
        if (starting || resized) {
            host.removeCallbacks(refresh);
            capture();
        }
    }

    private void uniforms() {
        if (shader == null) return;
        shader.setFloatUniform("size", (float) sheetWidth, (float) sheetHeight);
        shader.setFloatUniform("sceneSize", frame == null ? (float) width : (float) frame.getWidth(),
                frame == null ? (float) height : (float) frame.getHeight());
        shader.setFloatUniform("offsetY", offsetY);
        shader.setFloatUniform("sourceTop", (float) sourceTop);
        shader.setFloatUniform("radius", Math.max(0f, Math.min(radius, Math.min(sheetWidth, sheetHeight) * .5f)));
        float density = host.getResources().getDisplayMetrics().density;
        shader.setFloatUniform("density", density);
        float blur = Math.max(0f, Math.min(1f, IosShadeBackdropBlur.currentRadius(root) / Math.max(1f, 24f * density)));
        shader.setFloatUniform("nativeBlurMix", .55f * blur * blur * (3f - 2f * blur));
    }

    private void capture() {
        if (!running || pending || unavailable || root == null || failures >= 4) return;
        pending = true;
        requestedAt = SystemClock.uptimeMillis();
        int request = generation;
        float density = host.getResources().getDisplayMetrics().density;
        float bottom = offsetY + sheetHeight;
        int top = Math.min(height - 1, Math.max(0, (int) Math.floor(bottom - radius - 96f * density)));
        int end = Math.min(height, Math.max(top + 1, (int) Math.ceil(bottom + 64f * density)));
        IosShadeBackdropCapture.request(root, top, end, bitmap -> {
            if (!running || request != generation) {
                // Only unpublished frames are safe to recycle explicitly.
                if (bitmap != null) bitmap.recycle();
                return;
            }
            pending = false;
            if (bitmap != null) {
                boolean first = frame == null;
                failures = 0;
                frame = bitmap;
                sourceTop = top;
                try {
                    texture = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                    shader.setInputShader("content", texture);
                    uniforms();
                    host.invalidate();
                    if (first) frameReady.run();
                } catch (Throwable error) { fail(error); return; }
            } else failures++;
            if (running && request == generation && failures < 4) {
                long delay = failures == 0 ? Math.max(1L, 50L - (SystemClock.uptimeMillis() - requestedAt))
                        : 80L << (failures - 1);
                host.postDelayed(refresh, delay);
            }
        });
    }

    void draw(Canvas canvas) {
        if (running && frame != null && shader != null && canvas.isHardwareAccelerated()) {
            try {
                uniforms();
                float bandTop = Math.max(0f, sheetHeight - radius - 40f * host.getResources().getDisplayMetrics().density);
                canvas.drawRect(0f, bandTop, sheetWidth, sheetHeight, filtered);
            } catch (Throwable error) { fail(error); }
        }
    }

    private void fail(Throwable error) {
        boolean hadFrame = hasFrame();
        unavailable = true;
        running = pending = false;
        generation++;
        host.removeCallbacks(refresh);
        frame = null;
        texture = null;
        filtered.setShader(null);
        if (hadFrame) frameReady.run();
        Xp.log("[IOSShade] liquid optics unavailable: " + error);
    }

    void reset() {
        generation++;
        running = pending = unavailable = false;
        host.removeCallbacks(refresh);
        frame = null;
        root = null;
        failures = width = height = sheetWidth = sheetHeight = 0;
        texture = null;
        filtered.setShader(null);
        shader = null;
    }
}
