package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RecordingCanvas;
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
            + "uniform float outputTop;\n"
            + "float2 safeNormal(float2 v) { return v / max(length(v), 0.0001); }\n"
            + "half4 sampleScene(float2 p) {\n"
            + " return content.eval(clamp(p + float2(0.0, offsetY - sourceTop), float2(0.5), sceneSize - 0.5)); }\n"
            + "half4 main(float2 local) {\n"
            + " float2 p = local + float2(0.0, outputTop);\n"
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
            + " a *= half(coverage); return half4(rgb * a, a); }\n";

    private final View host;
    private final Runnable frameReady;
    private final Paint filtered = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Runnable refresh = this::capture;
    private RuntimeShader shader;
    private ViewGroup root;
    private Bitmap frame;
    private BitmapShader texture;
    private RenderNode optics;
    private RenderEffect sourceEffect, blurredSource;
    private int appliedBlur = -1, renderWidth, renderHeight;
    private float outputTop = Float.NaN, appliedDensity = Float.NaN;
    private boolean effectDirty;
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
        if (offsetY != offset || radius != corner) effectDirty = true;
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
            effectDirty = true;
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
                optics = new RenderNode("IosShadeEdgeOptics");
                filtered.setColor(0xFFFFFFFF);
                effectDirty = true;
            } catch (Throwable error) {
                fail(error);
                return;
            }
        }
        boolean starting = !running;
        running = true;
        if (starting || resized) {
            host.removeCallbacks(refresh);
            capture();
        }
    }

    private void prepareEffect() {
        float density = host.getResources().getDisplayMetrics().density;
        int blur = IosShadeBackdropBlur.currentRadius(root);
        float corner = Math.max(0f, Math.min(radius, Math.min(sheetWidth, sheetHeight) * .5f));
        // Keep all padded source pixels inside the effect input bounds. A tight
        // output-band node clips displaced samples before the shader can read them.
        float top = sourceTop - offsetY;
        if (renderWidth != frame.getWidth() || renderHeight != frame.getHeight()) {
            renderWidth = frame.getWidth(); renderHeight = frame.getHeight();
            optics.setPosition(0, 0, renderWidth, renderHeight);
            RecordingCanvas recording = optics.beginRecording(renderWidth, renderHeight);
            recording.drawRect(0f, 0f, renderWidth, renderHeight, filtered);
            optics.endRecording();
            effectDirty = true;
        }
        if (top != outputTop || appliedDensity != density) {
            outputTop = top;
            optics.setTranslationY(top);
            effectDirty = true;
            appliedDensity = density;
        }
        if (blurredSource == null || appliedBlur != blur) {
            // Blur the source pixels first, so every displaced/chromatic sample is blurred.
            blurredSource = blur > 0 ? RenderEffect.createBlurEffect(blur, blur, sourceEffect, Shader.TileMode.CLAMP) : sourceEffect;
            appliedBlur = blur;
            effectDirty = true;
        }
        if (!effectDirty) return;
        shader.setFloatUniform("size", (float) sheetWidth, (float) sheetHeight);
        shader.setFloatUniform("sceneSize", frame == null ? (float) width : (float) frame.getWidth(),
                frame == null ? (float) height : (float) frame.getHeight());
        shader.setFloatUniform("offsetY", offsetY);
        shader.setFloatUniform("sourceTop", (float) sourceTop);
        shader.setFloatUniform("radius", corner);
        shader.setFloatUniform("density", density);
        shader.setFloatUniform("outputTop", outputTop);
        // Runtime effects snapshot uniforms. Rebuild after geometry/source changes,
        // keeping the Gaussian source effect cached while its frame/radius are stable.
        optics.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content"), blurredSource));
        effectDirty = false;
    }

    private void capture() {
        if (!running || pending || unavailable || root == null || failures >= 4) return;
        pending = true;
        requestedAt = SystemClock.uptimeMillis();
        int request = generation;
        float density = host.getResources().getDisplayMetrics().density;
        float bottom = offsetY + sheetHeight;
        float support = 3f * IosShadeBackdropBlur.currentRadius(root);
        int top = Math.min(height - 1, Math.max(0, (int) Math.floor(bottom - radius - 96f * density - support)));
        int end = Math.min(height, Math.max(top + 1, (int) Math.ceil(bottom + 64f * density + support)));
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
                    sourceEffect = RenderEffect.createShaderEffect(texture);
                    blurredSource = null;
                    effectDirty = true;
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
                prepareEffect();
                canvas.drawRenderNode(optics);
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
        releaseEffects();
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
        releaseEffects();
        filtered.setShader(null);
        shader = null;
    }

    private void releaseEffects() {
        if (optics != null) {
            optics.setRenderEffect(null);
            optics.discardDisplayList();
        }
        optics = null;
        sourceEffect = blurredSource = null;
        appliedBlur = -1;
        renderWidth = renderHeight = 0;
        outputTop = appliedDensity = Float.NaN;
        effectDirty = true;
    }
}
