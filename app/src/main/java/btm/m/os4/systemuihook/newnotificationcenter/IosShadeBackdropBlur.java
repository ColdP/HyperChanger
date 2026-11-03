package btm.m.os4.systemuihook.newnotificationcenter;

import android.view.View;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Applies a gesture envelope to the native window blur, without changing row materials. */
public final class IosShadeBackdropBlur {
    private static View ownedRoot;
    private static int requestedRadius;
    private static Method setter;
    private static Method getter;
    private static boolean writing;
    private static float amount = 1f;
    private static float gestureAmount, wallpaperAlpha;
    private static WeakReference<View> surfaceRoot = new WeakReference<>(null);
    private static int surfaceRadius;
    private static int requestedSurfaceRadius;
    private static WeakReference<Object> surfaceController = new WeakReference<>(null);
    private static WeakReference<Object> surfaceTarget = new WeakReference<>(null);
    private static Method surfaceApply;
    private static boolean writingSurface;

    private IosShadeBackdropBlur() {}

    static void install(ClassLoader loader) {
        try {
            setter = View.class.getDeclaredMethod("setMiBackgroundBlurRadius", int.class);
            getter = View.class.getDeclaredMethod("getMiBackgroundBlurRadius");
            setter.setAccessible(true);
            getter.setAccessible(true);
            Xp.api().hook(setter)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (writing || chain.getThisObject() != ownedRoot) return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        if (args.length == 1 && args[0] instanceof Integer) {
                            // Preserve every native request for close/disable restoration.
                            requestedRadius = (Integer) args[0];
                            args[0] = scaledRadius();
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            setter = null;
            getter = null;
            Xp.log("[IOSShade] staged backdrop blur control unavailable: " + error);
        }
        try {
            Method apply = Xp.findClass("com.android.systemui.statusbar.BlurUtils", loader)
                    .getDeclaredMethod("applyBlur", Xp.findClass("android.view.ViewRootImpl", loader), int.class);
            apply.setAccessible(true);
            surfaceApply = apply;
            Xp.api().hook(apply)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object target = chain.getArgs().get(0);
                        View view = null;
                        try {
                            if (target != null) view = (View) Xp.callMethod(target, "getView");
                        } catch (Throwable ignored) {}
                        Object[] args = chain.getArgs().toArray();
                        if (!writingSurface && view != null && args[1] instanceof Integer) {
                            surfaceRoot = new WeakReference<>(view);
                            surfaceController = new WeakReference<>(chain.getThisObject());
                            surfaceTarget = new WeakReference<>(target);
                            requestedSurfaceRadius = (Integer) args[1];
                            if (view == ownedRoot) args[1] = scaledSurfaceRadius();
                        }
                        Object result = chain.proceed(args);
                        try {
                            Object controller = chain.getThisObject();
                            if (target != null && Xp.getObjectField(controller, "lastTargetViewRootImpl") == target) {
                                if (surfaceRoot.get() == view) {
                                    surfaceRadius = Math.max(0, ((Number) Xp.getObjectField(controller, "lastAppliedBlur")).intValue());
                                }
                            }
                        } catch (Throwable ignored) {}
                        return result;
                    });
        } catch (Throwable error) {
            Xp.log("[IOSShade] window blur radius observation unavailable: " + error);
        }
    }

    public static void update(View root, boolean enabled, float progress) {
        float p = Float.isFinite(progress) ? Math.max(0f, Math.min(1f, progress)) : 0f;
        if (root == ownedRoot && enabled) {
            gestureAmount = p * p * (3f - 2f * p);
            updateAmount();
            return;
        }
        release();
        if (!enabled || root == null || setter == null || getter == null) return;
        try {
            requestedRadius = ((Number) getter.invoke(root)).intValue();
            ownedRoot = root;
            gestureAmount = p * p * (3f - 2f * p);
            amount = .3f * gestureAmount;
            write(root, scaledRadius());
            replaySurface(root, true);
        } catch (Throwable ignored) { release(); }
    }

    private static int scaledRadius() { return Math.round(requestedRadius * amount); }
    private static int scaledSurfaceRadius() { return Math.round(requestedSurfaceRadius * amount); }

    static void setWallpaperReveal(View root, float alpha) {
        if (root == null || root != ownedRoot) return;
        wallpaperAlpha = Float.isFinite(alpha) ? Math.max(0f, Math.min(1f, alpha)) : 0f;
        updateAmount();
    }

    private static void updateAmount() {
        float capped = .3f * gestureAmount;
        float next = capped + (1f - capped) * wallpaperAlpha;
        if (amount == next) return;
        amount = next;
        try {
            int wanted = scaledRadius();
            if (((Number) getter.invoke(ownedRoot)).intValue() != wanted) write(ownedRoot, wanted);
        } catch (Throwable ignored) {}
        replaySurface(ownedRoot, true);
    }

    private static void replaySurface(View root, boolean scaled) {
        if (root == null || surfaceRoot.get() != root || surfaceApply == null) return;
        Object controller = surfaceController.get(), target = surfaceTarget.get();
        if (controller == null || target == null) return;
        boolean previous = writingSurface;
        writingSurface = true;
        try { surfaceApply.invoke(controller, target, scaled ? scaledSurfaceRadius() : requestedSurfaceRadius); }
        catch (Throwable ignored) {}
        finally { writingSurface = previous; }
    }

    // Both radii are already in pixels; never multiply them by display density again.
    static int currentRadius(View root) {
        if (root == null || root != ownedRoot) return 0;
        return Math.max(Math.max(0, scaledRadius()), surfaceRoot.get() == root ? surfaceRadius : 0);
    }

    public static void release() {
        View previous = ownedRoot;
        ownedRoot = null;
        wallpaperAlpha = gestureAmount = 0f;
        if (previous == null) return;
        try { write(previous, requestedRadius); }
        catch (Throwable ignored) {}
        replaySurface(previous, false);
    }

    private static void write(View view, int radius) throws Exception {
        boolean previous = writing;
        writing = true;
        try { setter.invoke(view, radius); }
        finally { writing = previous; }
    }
}
