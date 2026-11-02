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
    private static WeakReference<View> surfaceRoot = new WeakReference<>(null);
    private static int surfaceRadius;

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
            Xp.api().hook(apply)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object target = chain.getArgs().get(0);
                            Object controller = chain.getThisObject();
                            if (target != null && Xp.getObjectField(controller, "lastTargetViewRootImpl") == target) {
                                View view = (View) Xp.callMethod(target, "getView");
                                if (surfaceRoot.get() != view) surfaceRoot = new WeakReference<>(view);
                                surfaceRadius = Math.max(0, ((Number) Xp.getObjectField(controller, "lastAppliedBlur")).intValue());
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
        float next = p * p * (3f - 2f * p);
        if (root == ownedRoot && enabled) {
            if (amount == next) return;
            amount = next;
            try {
                int wanted = scaledRadius();
                if (((Number) getter.invoke(root)).intValue() != wanted) write(root, wanted);
            } catch (Throwable ignored) {}
            return;
        }
        release();
        if (!enabled || root == null || setter == null || getter == null) return;
        try {
            requestedRadius = ((Number) getter.invoke(root)).intValue();
            ownedRoot = root;
            amount = next;
            write(root, scaledRadius());
        } catch (Throwable ignored) { release(); }
    }

    private static int scaledRadius() { return Math.round(requestedRadius * amount); }

    // Both radii are already in pixels; never multiply them by display density again.
    static int currentRadius(View root) {
        if (root == null || root != ownedRoot) return 0;
        return Math.max(Math.max(0, scaledRadius()), surfaceRoot.get() == root ? surfaceRadius : 0);
    }

    public static void release() {
        View previous = ownedRoot;
        ownedRoot = null;
        if (previous == null) return;
        try { write(previous, requestedRadius); }
        catch (Throwable ignored) {}
    }

    private static void write(View view, int radius) throws Exception {
        boolean previous = writing;
        writing = true;
        try { setter.invoke(view, radius); }
        finally { writing = previous; }
    }
}
