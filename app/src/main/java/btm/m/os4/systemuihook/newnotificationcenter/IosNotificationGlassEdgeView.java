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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;

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
            uniform float uHdr;

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
                float highlight = pow(1.0 - curve, 2.0) * (0.32 + uHdr * 0.55);
                float alpha = band * edge * uAlpha;
                half3 color = mix(centerSample.rgb, refracted, 0.72)
                        + half3(highlight * (1.0 + uHdr * 0.35));
                return half4(color, alpha);
            }
            """;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RuntimeShader shader;
    private final RectF bounds = new RectF();
    private Bitmap frame;
    private BitmapShader frameShader;
    private long lastCapture;
    private boolean transitionActive;
    private boolean hdrEnabled;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile boolean capturePending;
    private volatile boolean released;
    private volatile Rect captureRegion;
    private volatile float captureScale = 0.35f;
    private volatile float captureOriginX;
    private volatile float captureOriginY;
    private volatile CaptureAccess captureAccess;

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

    void setTransitionActive(boolean active) {
        if (transitionActive == active) return;
        transitionActive = active;
        if (!active) {
            capturePending = false;
            if (frame != null && !frame.isRecycled()) frame.recycle();
            frame = null;
            frameShader = null;
        }
        invalidate();
    }

    void setHdrEnabled(boolean enabled) {
        if (hdrEnabled == enabled) return;
        hdrEnabled = enabled;
        invalidate();
    }

    void release() {
        released = true;
        transitionActive = false;
        capturePending = false;
        if (captureThread != null) captureThread.quitSafely();
        captureThread = null;
        captureHandler = null;
        captureAccess = null;
        if (frame != null && !frame.isRecycled()) frame.recycle();
        frame = null;
        frameShader = null;
        paint.setShader(null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (shader == null || !transitionActive || getWidth() <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCapture >= CAPTURE_INTERVAL_MS) requestCapture();
        Bitmap bitmap = frame;
        if (bitmap == null || bitmap.isRecycled()) return;
        int[] point = new int[2];
        getLocationOnScreen(point);
        float width = getWidth();
        float height = getHeight();
        float bandTop = Math.max(0f, height - 34f * getResources().getDisplayMetrics().density);
        float radius = Math.max(1f, 7f * getResources().getDisplayMetrics().density);
        if (frameShader == null) {
            frameShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setInputShader("uContent", frameShader);
        }
        shader.setFloatUniform("uScreenOrigin", point[0], point[1]);
        shader.setFloatUniform("uCaptureOrigin", captureOriginX, captureOriginY);
        shader.setFloatUniform("uCaptureScale", captureScale, captureScale);
        shader.setFloatUniform("uSize", width, height);
        shader.setFloatUniform("uBandTop", bandTop);
        shader.setFloatUniform("uBandHeight", height - bandTop);
        shader.setFloatUniform("uRadius", radius);
        shader.setFloatUniform("uAmount", 8f * getResources().getDisplayMetrics().density);
        shader.setFloatUniform("uAlpha", 0.28f);
        shader.setFloatUniform("uHdr", hdrEnabled ? 1f : 0f);
        bounds.set(0f, bandTop - 2f, width, height);
        canvas.drawRect(bounds, paint);
        if (transitionActive) postInvalidateOnAnimation();
    }

    private void requestCapture() {
        if (released || capturePending || !isAttachedToWindow()) return;
        int[] point = new int[2];
        getLocationOnScreen(point);
        float density = getResources().getDisplayMetrics().density;
        float height = getHeight();
        float bandTop = Math.max(0f, height - 34f * density);
        int top = Math.max(0, Math.round(point[1] + bandTop - 18f * density));
        int bottom = Math.min(getResources().getDisplayMetrics().heightPixels,
                Math.round(point[1] + height + 8f * density));
        int width = getResources().getDisplayMetrics().widthPixels;
        if (bottom <= top || width <= 0) return;
        captureRegion = new Rect(0, top, width, bottom);
        captureOriginX = 0f;
        captureOriginY = top;
        capturePending = true;
        lastCapture = android.os.SystemClock.uptimeMillis();
        ensureCaptureThread();
        captureHandler.post(this::captureFrame);
    }

    private void ensureCaptureThread() {
        if (captureHandler != null) return;
        captureThread = new HandlerThread("IOSNotificationGlassCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    private void captureFrame() {
        Bitmap bitmap = null;
        try {
            if (!transitionActive || released) return;
            if (captureAccess == null) {
                captureAccess = CaptureAccess.create(this);
            }
            bitmap = captureAccess.capture(captureRegion, captureScale);
            if (bitmap != null && !bitmap.isRecycled()) {
                Bitmap delivered = bitmap;
                new Handler(Looper.getMainLooper()).post(() -> deliverFrame(delivered));
                bitmap = null;
            }
        } catch (Throwable ignored) {
            captureAccess = null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            capturePending = false;
        }
    }

    private void deliverFrame(Bitmap bitmap) {
        if (released || !transitionActive) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        Bitmap old = frame;
        frame = bitmap;
        frameShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setInputShader("uContent", frameShader);
        if (old != null && !old.isRecycled()) old.recycle();
        invalidate();
    }

    private static final class CaptureAccess {
        private final Object service;
        private final Method captureDisplay;
        private final Method createListener;
        private final Class<?> builderClass;
        private final java.lang.reflect.Constructor<?> builderConstructor;
        private final Method build;
        private final Method setCrop;
        private final Method setSize;
        private final Method setScale;
        private final Method setScale2;
        private final Method setMode;
        private final Method setMiMode;
        private final Method setExcludeLayers;
        private final Method setLayerNames;
        private final Object excludedLayers;

        private CaptureAccess(Object service, Method captureDisplay, Method createListener,
                Class<?> builderClass, java.lang.reflect.Constructor<?> builderConstructor,
                Method build, Method setCrop, Method setSize, Method setScale, Method setScale2, Method setMode,
                Method setMiMode, Method setExcludeLayers, Method setLayerNames,
                Object excludedLayers) {
            this.service = service;
            this.captureDisplay = captureDisplay;
            this.createListener = createListener;
            this.builderClass = builderClass;
            this.builderConstructor = builderConstructor;
            this.build = build;
            this.setCrop = setCrop;
            this.setSize = setSize;
            this.setScale = setScale;
            this.setScale2 = setScale2;
            this.setMode = setMode;
            this.setMiMode = setMiMode;
            this.setExcludeLayers = setExcludeLayers;
            this.setLayerNames = setLayerNames;
            this.excludedLayers = excludedLayers;
        }

        static CaptureAccess create(View view) throws Exception {
            Class<?> captureClass = null;
            for (String name : new String[]{"android.window.ScreenCaptureInternal", "android.window.ScreenCapture"}) {
                try { captureClass = Class.forName(name); break; } catch (Throwable ignored) {}
            }
            if (captureClass == null) throw new IllegalStateException("ScreenCapture unavailable");
            Class<?> builderClass = Class.forName(captureClass.getName() + "$CaptureArgs$Builder");
            java.lang.reflect.Constructor<?> constructor = builderClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Method build = find(builderClass, "build");
            Method createListener = find(captureClass, "createSyncCaptureListener");
            Object service = find(Class.forName("android.view.WindowManagerGlobal"),
                    "getWindowManagerService").invoke(null);
            Method captureDisplay = null;
            Class<?> captureArgs = Class.forName(captureClass.getName() + "$CaptureArgs");
            for (Method method : service.getClass().getMethods()) {
                if ("captureDisplay".equals(method.getName()) && method.getParameterTypes().length == 3
                        && method.getParameterTypes()[0] == int.class
                        && method.getParameterTypes()[1].isAssignableFrom(captureArgs)) {
                    method.setAccessible(true);
                    captureDisplay = method;
                    break;
                }
            }
            if (captureDisplay == null) {
                for (Method method : service.getClass().getDeclaredMethods()) {
                    if ("captureDisplay".equals(method.getName()) && method.getParameterTypes().length == 3
                            && method.getParameterTypes()[0] == int.class
                            && method.getParameterTypes()[1].isAssignableFrom(captureArgs)) {
                        method.setAccessible(true);
                        captureDisplay = method;
                        break;
                    }
                }
            }
            if (captureDisplay == null) throw new IllegalStateException("captureDisplay unavailable");
            Class<?>[] intTypes = {int.class, int.class};
            Method setCrop = find(builderClass, "setSourceCrop", Rect.class);
            Method setSize = findOptional(builderClass, "setSize", intTypes);
            Method setScale = findOptional(builderClass, "setFrameScale", float.class);
            Method setScale2 = findOptional(builderClass, "setFrameScale", float.class, float.class);
            Method setMode = findOptional(builderClass, "setCaptureMode", int.class);
            Method setMiMode = findOptional(builderClass, "setMiCaptureMode", int.class);
            Method setNames = findOptional(builderClass, "setExcludeOrIncludeLayerNames", String[].class);
            Method exclude = null;
            for (Method method : builderClass.getMethods()) {
                if ("setExcludeLayers".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    method.setAccessible(true);
                    exclude = method;
                    break;
                }
            }
            Object excluded = currentExcludeLayers(view);
            if (setCrop == null || (setSize == null && setScale == null && setScale2 == null)) {
                throw new IllegalStateException("capture crop unavailable");
            }
            if (exclude == null && setNames == null) {
                throw new IllegalStateException("capture exclusion unavailable");
            }
            return new CaptureAccess(service, captureDisplay, createListener, builderClass, constructor,
                    build, setCrop, setSize, setScale, setScale2, setMode, setMiMode, exclude, setNames, excluded);
        }

        Bitmap capture(Rect region, float scale) throws Exception {
            Object builder = builderConstructor.newInstance();
            setCrop.invoke(builder, region);
            if (setSize != null) {
                setSize.invoke(builder, Math.max(1, Math.round(region.width() * scale)),
                        Math.max(1, Math.round(region.height() * scale)));
            } else if (setScale != null) {
                setScale.invoke(builder, scale);
            } else {
                setScale2.invoke(builder, scale, scale);
            }
            if (setMode != null) setMode.invoke(builder, 1);
            if (setMiMode != null && builderClass.getName().contains("ScreenCaptureInternal")) {
                setMiMode.invoke(builder, 1);
            }
            if (setExcludeLayers != null && excludedLayers != null) setExcludeLayers.invoke(builder, excludedLayers);
            else if (setLayerNames != null) setLayerNames.invoke(builder, (Object) new String[]{"StatusBar#", "NavigationBar0#"});
            Object args = build.invoke(builder);
            Object listener = createListener.invoke(null);
            int displayId = android.view.Display.DEFAULT_DISPLAY;
            captureDisplay.invoke(service, displayId, args, listener);
            Object buffer = find(listener.getClass(), "getBuffer").invoke(listener);
            return (Bitmap) find(buffer.getClass(), "asBitmap").invoke(buffer);
        }

        private static Object currentExcludeLayers(View view) {
            try {
                Class<?> surfaceClass = Class.forName("android.view.SurfaceControl");
                Object root = view.getRootView();
                Object viewRoot = find(root.getClass(), "getViewRootImpl").invoke(root);
                Object surface = find(viewRoot.getClass(), "getSurfaceControl").invoke(viewRoot);
                if (surface == null || !surfaceClass.isInstance(surface)) return null;
                Object array = java.lang.reflect.Array.newInstance(surfaceClass, 1);
                java.lang.reflect.Array.set(array, 0, surface);
                return array;
            } catch (Throwable ignored) { return null; }
        }

        private static Method find(Class<?> type, String name, Class<?>... parameters) throws Exception {
            try { Method method = type.getMethod(name, parameters); method.setAccessible(true); return method; }
            catch (Throwable ignored) {}
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try { Method method = current.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
                catch (Throwable ignored) {}
            }
            throw new NoSuchMethodException(type.getName() + "." + name);
        }

        private static Method findOptional(Class<?> type, String name, Class<?>... parameters) {
            try { return find(type, name, parameters); }
            catch (Throwable ignored) { return null; }
        }
    }
}
