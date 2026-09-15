package btm.m.os4.systemuihook.hypermusiccover;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MIUI's shade blur providers: which one is the window-level blur, and how to drive it.
 *
 * HyperOS builds three `ShadeBlendBlurController$BlurProvider`s, one each for the shade window,
 * the notification panel and the control centre. Only the window-level one is the background
 * blur this module replaces - driving the other two would make the notification rows' own blur
 * breathe with the pull-down, which is a bug that looks like a feature.
 *
 * Picking it is the fragile part of the whole port, so the rule is written out rather than
 * implied:
 *
 * 1. The provider whose `onSlowdownBlurChanged` callback is non-null is the window one. Only
 *    the window provider is handed a callback; the other two are constructed with the flag that
 *    nulls it. This is the module ShadeIOSBlur's rule, verified on device, and the field is
 *    confirmed present on OS4.
 * 2. If more than one answers to that, the largest `maxRadius` wins, because the window blur is
 *    the expensive one. If that is still a tie, the module does nothing and says so - a wrong
 *    guess here is silent, and silence is what makes it expensive.
 *
 * The constructor hook is the only place `enableScale` can be cleared: MIUI reads that field in
 * its own `setBlurRatio`, and the constructor is where it is still ours to write.
 */
final class ShadeBlur {

    private ShadeBlur() {
    }

    private static final String TAG = "[MCBlur] ";

    /** Every provider built so far. Three are expected; the list is small and lives as long. */
    private static final List<Object> PROVIDERS =
            Collections.synchronizedList(new ArrayList<Object>());

    private static volatile Object sWindow;
    private static volatile boolean sPushFailed;

    /**
     * The shade root now exists, so the identity rule can be applied.
     *
     * Needed because the order is not guaranteed: the blur providers are Dagger singletons built
     * during startup and the shade window may well be constructed after them, in which case the
     * identity check had nothing to compare against when it ran.
     */
    static void onShadeRootAvailable() {
        classify();
    }

    // ------------------------------------------------------------------ construction

    /**
     * Called from the BlurProvider constructor hook.
     *
     * The zoom-back is removed here and not in the per-frame path: `enableScale` is a field MIUI
     * reads when it computes its own `setBlurRatio`, so clearing it once at construction removes
     * the "the whole screen shrinks as you pull" half of the animation for the life of the
     * provider. The alternative - intercepting `setMiBackgroundBlurScaleRatio` - would be a hook
     * on the hottest path in the shade to undo a number we can simply not write.
     */
    static void onProvider(Object provider) {
        try {
            Xp.setBooleanField(provider, "enableScale", false);
        } catch (Throwable t) {
            // Costs the zoom-back and nothing else: the blur half of the effect still works.
            Xp.log(TAG + "no enableScale on " + provider.getClass().getName()
                    + ", the background will still shrink as you pull: " + t);
        }
        synchronized (PROVIDERS) {
            if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
        }
        classify();
    }

    private static synchronized void classify() {
        final View root = ShadeLayer.shadeRoot();
        Object best = null;
        int bestRadius = -1;
        int byView = 0;
        int byCallback = 0;

        final StringBuilder table = new StringBuilder();
        synchronized (PROVIDERS) {
            for (Object p : PROVIDERS) {
                final Object v = view(p);
                final boolean windowish = callback(p) != null;
                final boolean isRoot = root != null && v == root;
                final int radius = intField(p, "maxRadius", -1);
                table.append("\n  ").append(p.getClass().getName())
                        .append(" onSlowdownBlurChanged=").append(windowish)
                        .append(" supportSmartBlur=").append(boolField(p, "supportSmartBlur", false))
                        .append(" maxRadius=").append(radius)
                        .append(" view=").append(v == null ? "null" : v.getClass().getSimpleName())
                        .append(isRoot ? " (IS the shade root)" : "");
                if (isRoot) byView++;
                if (windowish) {
                    byCallback++;
                    if (radius > bestRadius) {
                        bestRadius = radius;
                        best = p;
                    }
                }
            }
        }

        // The table every time a provider is built, which is three lines per SystemUI start.
        // Logging it once was useless: the first provider is built alone, so the one table that
        // got printed described a list of one.
        Xp.log(TAG + "blur providers (" + PROVIDERS.size() + "):" + table);

        // Identity of the view is the strongest signal available and it is not an inference:
        // the window provider drives the shade window, and we are holding that very object. It
        // is also what made the callback rule insufficient on this device - all three providers
        // answer to onSlowdownBlurChanged here, with the same maxRadius, so that rule picks
        // whichever was built last.
        if (byView == 1) {
            sWindow = rootProvider(root);
            Xp.log(TAG + "window blur provider identified by its view being the shade root: "
                    + describe());
            return;
        }

        if (byCallback == 0) {
            if (sWindow != null) return;
            Xp.log(TAG + "no window-level blur provider found - the shade will not lag the "
                    + "finger. " + PROVIDERS.size() + " providers seen, none with a non-null "
                    + "onSlowdownBlurChanged and none whose view is the shade root.");
            return;
        }
        if (sWindow == best) return;
        sWindow = best;
        Xp.log(TAG + "window blur provider chosen by callback"
                + (byCallback > 1 ? " (" + byCallback + " claimed it, largest maxRadius="
                        + bestRadius + " won)" : "")
                + ": " + describe());
    }

    /** The provider whose `view` is the shade root, if exactly one is. */
    private static Object rootProvider(View root) {
        synchronized (PROVIDERS) {
            for (Object p : PROVIDERS) {
                if (view(p) == root) return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ per frame

    /**
     * The blur radius, as a fraction, driven by the spring.
     *
     * Called every frame the spring moves, so a failure is logged once rather than sixty times a
     * second - but only once, which means a permanently broken push is invisible after the first
     * line. That is the trade, and the first line is why it is not silent.
     */
    static void pushRatio(float ratio) {
        final Object p = sWindow;
        if (p == null) return;
        try {
            Xp.callMethod(p, "setBlurRatio", ratio);
        } catch (Throwable t) {
            if (sPushFailed) return;
            sPushFailed = true;
            Xp.log(TAG + "setBlurRatio failed - the shade will not lag the finger: " + t);
        }
    }

    /**
     * Whether this provider is the one the shade layer drives.
     *
     * The setBlurRatio hook checks this before touching the argument. Without it the same
     * substitution would land on the notification panel's and the control centre's providers
     * too, and their rows' own blur would start tracking the pull-down - a change nobody asked
     * for, on views this module has no business animating.
     */
    static boolean isWindow(Object provider) {
        return provider != null && provider == sWindow;
    }

    static String describe() {
        final Object p = sWindow;
        if (p == null) return "no window provider (" + PROVIDERS.size() + " seen)";
        return "window=" + p.getClass().getName()
                + " maxRadius=" + intField(p, "maxRadius", -1)
                + " enableScale=" + boolField(p, "enableScale", true)
                + " ratio=" + floatField(p, "blurRatio", -1f);
    }

    // ------------------------------------------------------------------ reflection

    private static Object callback(Object p) {
        try {
            return Xp.getObjectField(p, "onSlowdownBlurChanged");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object view(Object p) {
        try {
            return Xp.getObjectField(p, "view");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object p, String name, int def) {
        try {
            Object v = Xp.getObjectField(p, name);
            return v instanceof Integer ? (Integer) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static float floatField(Object p, String name, float def) {
        try {
            Object v = Xp.getObjectField(p, name);
            return v instanceof Float ? (Float) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean boolField(Object p, String name, boolean def) {
        try {
            Object v = Xp.getObjectField(p, name);
            return v instanceof Boolean ? (Boolean) v : def;
        } catch (Throwable t) {
            return def;
        }
    }
}
