package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** A native glass consumer over a live, compositor-owned window backdrop. */
public final class IosShadeGlassView extends View {
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final View backdrop;
    private final Path shell = new Path();
    private ViewGroup root;
    private boolean active, ready, failed, exiting;
    private boolean refractionEnabled = true, materialReady;
    private int clipWidth, clipHeight;
    private float radius;
    private Runnable backdropReady;
    private float sheetOffset;
    private final IosShadeLiquidLens liquidLens = new IosShadeLiquidLens(this, () -> {
        // The sampled lens is already rendered. A vendor view-material pass here would
        // reinterpret its pixels as a glass mask and discard the optical image.
        if (ready) {
            try { applyRefraction(); }
            catch (Throwable ignored) { clearRefraction(); }
        }
        if (backdropReady != null) backdropReady.run();
    });

    public IosShadeGlassView(Context context) {
        super(context);
        visualOnly(this);
        setWillNotDraw(false);
        marker.setColor(0xFFFFFFFF);
        border.setColor(0x66FFFFFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        veil.setColor(0x0AFFFFFF);
        setClipToOutline(true);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner());
            }
        });
        backdrop = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawPoint(-1f, -1f, veil);
            }
        };
        visualOnly(backdrop);
        backdrop.setWillNotDraw(false);
        setVisibility(INVISIBLE);
        backdrop.setVisibility(INVISIBLE);
    }

    private static void visualOnly(View view) {
        view.setClickable(false);
        view.setFocusable(false);
        view.setEnabled(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public View backdropView() { return backdrop; }
    public boolean hasBackdrop() { return ready || liquidLens.hasFrame(); }
    public void setOnBackdropReady(Runnable listener) { backdropReady = listener; }

    public void setRefractionEnabled(boolean enabled) {
        if (refractionEnabled == enabled) return;
        refractionEnabled = enabled;
        // Keep the live provider available to row/clock sampling and wallpaper reversal.
        if (ready) {
            try { applyRefraction(); }
            catch (Throwable error) {
                clearRefraction();
                Xp.log("[IOSShade] glass refraction update unavailable: " + error);
            }
        }
        updateLiquidLens();
        invalidate();
    }

    @Override public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        // Called by the independent wallpaper timer, including when the finger is still.
        if (liquidLens != null) updateLiquidLens();
    }

    private void updateLiquidLens() {
        liquidLens.update(root, sheetOffset, radius,
                active && refractionEnabled && getAlpha() > 0f);
    }

    public void setExiting(boolean value) {
        if (exiting == value) return;
        exiting = value;
        invalidate();
    }

    public void update(ViewGroup window, float sheetOffset, float cornerRadius, boolean visible) {
        if (!visible || window == null) { reset(); return; }
        if (root != window) {
            reset();
            root = window;
        }
        active = true;
        this.sheetOffset = sheetOffset;
        if (radius != cornerRadius) {
            radius = cornerRadius;
            invalidateOutline();
        }
        backdrop.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
        if (!ready && !failed && getWidth() > 0 && getHeight() > 0) initialize();
        if (ready && (clipWidth != getWidth() || clipHeight != getHeight())) updateClip();
        updateLiquidLens();
        invalidate();
    }

    private void initialize() {
        try {
            // Keep a stable live provider for notification/clock source continuity.
            call(backdrop, "setPassWindowBlurEnabled", true);
            call(backdrop, "disableMiBackgroundContainBelow", false);
            call(backdrop, "setMiBackgroundBlurMode", 1);
            call(backdrop, "setMiViewBlurMode", 0);
            call(backdrop, "setMiBackgroundBlurScaleRatio", 0f);
            call(backdrop, "setMiBackgroundBlurRadius", 24);
            call(backdrop, "setMiGlassBlurRadius", 8, 24);
            applyRefraction();
            ready = true;
            if (backdropReady != null) backdropReady.run();
        } catch (Throwable error) {
            failed = true;
            releaseNative();
            Xp.log("[IOSShade] live glass unavailable: " + error);
        }
    }

    private void applyRefraction() {
        if (!refractionEnabled || liquidLens.hasFrame()) { clearRefraction(); return; }
        call(this, "setPassWindowBlurEnabled", false);
        call(this, "chooseBackgroundBlurContainer", backdrop);
        call(this, "setMiBackgroundBlurMode", 0);
        call(this, "setMiViewBlurMode", 3);
        call(this, "setMiViewMaterialType", 1);
        optional(this, "setMiCustomSurfaceColorType", 16);
        call(this, "setMiGlassBlurRadius", 8, 24);
        updateClip();
        float[] data = recipe();
        marker.setStrokeWidth(data[19]);
        marker.setStrokeMiter(data[20]);
        call(this, "setMiGlass", (Object) data);
        materialReady = true;
    }

    private float[] recipe() {
        float[] data = null;
        try {
            int id = getResources().getIdentifier("notification_glass_params_normal", "array", "com.android.systemui");
            String[] values = getResources().getStringArray(id);
            if (values.length >= 42) {
                data = new float[42];
                for (int i = 0; i < data.length; i++) data[i] = Float.parseFloat(values[i]);
            }
        } catch (Throwable ignored) {}
        if (data == null) data = new float[]{
                .5f, 1f, 0f, 1f, .2f, 2f, .2f, 0f, .6f, .8f, .04f, .9f, .9f, .9f,
                1f, 0f, 1f, 1f, 1f, 30f, 2f, 80f, 400f, 1f, .8f, -.4f, .6f, -.8f,
                1.4f, .7f, .8f, 1.15f, 1.33f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f};
        // Restore the optical shape slots that a notification token may flatten.
        data[19] = 30f;
        data[20] = 2f;
        data[21] = 80f;
        data[22] = 400f;
        data[32] = 1.33f;
        return data;
    }

    private void updateClip() {
        clipWidth = getWidth();
        clipHeight = getHeight();
        try { call(this, "setMiGlassClip", 0f, 0f, (float) clipWidth, (float) clipHeight); }
        catch (Throwable ignored) {}
    }

    private float corner() { return Math.max(0f, Math.min(radius, Math.min(getWidth(), getHeight()) * .5f)); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        float r = corner();
        if (materialReady) {
            shell.reset();
            shell.addRoundRect(0, 0, getWidth(), getHeight(), r, r, Path.Direction.CW);
            canvas.drawPath(shell, marker);
        }
        if (exiting || !materialReady) canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, veil);
        liquidLens.draw(canvas);
        float inset = border.getStrokeWidth() * .5f;
        canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
                Math.max(0f, r - inset), Math.max(0f, r - inset), border);
    }

    public void reset() {
        liquidLens.reset();
        if (active || ready || failed) releaseNative();
        active = ready = failed = exiting = false;
        root = null;
        clipWidth = clipHeight = 0;
        setVisibility(INVISIBLE);
        backdrop.setVisibility(INVISIBLE);
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    private void clearRefraction() {
        materialReady = false;
        optional(this, "chooseBackgroundBlurContainer", (Object) null);
        optional(this, "setMiGlass", (Object) null);
        optional(this, "setMiViewMaterialType", 0);
        optional(this, "setMiViewBlurMode", 0);
        optional(this, "setMiGlassBlurRadius", 0, 0);
    }

    private void releaseNative() {
        clearRefraction();
        // Mode shutdown also retires the pass-window texture entry.
        optional(backdrop, "setPassWindowBlurEnabled", false);
        optional(backdrop, "setMiBackgroundBlurMode", 0);
        optional(backdrop, "setMiBackgroundBlurRadius", 0);
        optional(backdrop, "setMiGlassBlurRadius", 0, 0);
    }

    private static void call(View view, String name, Object... args) { Xp.callMethod(view, name, args); }
    private static void optional(View view, String name, Object... args) {
        try { call(view, name, args); } catch (Throwable ignored) {}
    }
}
