package btm.m.os4.systemuihook.hypermusiccover;

import android.view.View;

import java.lang.reflect.Method;

/**
 * HyperOS's private blur methods on View, wrapped so nothing else has to know they are private.
 *
 * Only the four that the notification cards need are resolved, and only the non-gradient ones.
 * The gradient pair (`setBackgroundGradientBlurParams`, `setMiBackgroundBlurType(2)`) is
 * deliberately absent: the module this is ported from shipped the gradient path, hit a
 * RenderThread SIGSEGV inside `vulkan.adreno.so` under frequent updates on this exact GPU, and
 * removed it permanently in favour of a clipped hard edge. There is no reason to walk back into
 * that for a transition band.
 *
 * Every call is individually guarded and a method that is missing simply does nothing. These are
 * OEM privates with no stability guarantee, and losing one should cost a soft edge on the cards,
 * not the feature.
 */
final class MiBlur {

    private MiBlur() {
    }

    private static final String TAG = "[MCMiBlur] ";

    private static volatile boolean sInited;
    private static Method sMode;
    private static Method sType;
    private static Method sRadius;
    private static Method sPassWindow;
    // Read-back, if this build has it. A write that is later rewritten by the system looks
    // exactly like a write that never landed, and only the getter can tell them apart.
    private static Method sGetMode;
    private static Method sGetRadius;
    private static Method sGetType;
    private static Method sGetPass;

    private static synchronized void init() {
        if (sInited) return;
        sInited = true;
        sMode = find("setMiBackgroundBlurMode", int.class);
        sType = find("setMiBackgroundBlurType", int.class);
        sRadius = find("setMiBackgroundBlurRadius", int.class);
        sPassWindow = find("setPassWindowBlurEnabled", boolean.class);
        sGetMode = find0("getMiBackgroundBlurMode");
        sGetRadius = find0("getMiBackgroundBlurRadius");
        sGetType = find0("getMiBackgroundBlurType");
        sGetPass = find0("getPassWindowBlurEnabled");
        Xp.log(TAG + "blur api: mode=" + (sMode != null) + " type=" + (sType != null)
                + " radius=" + (sRadius != null) + " passWindow=" + (sPassWindow != null)
                + " readBack=" + (sGetMode != null) + "/" + (sGetRadius != null)
                + "/" + (sGetType != null) + "/" + (sGetPass != null));
    }

    private static Method find0(String name) {
        try {
            final Method m = View.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** What a view's blur actually is right now. "?" means this build has no getter. */
    static String describe(View v) {
        init();
        return "mode=" + read(sGetMode, v) + ",r=" + read(sGetRadius, v)
                + ",type=" + read(sGetType, v) + ",pass=" + read(sGetPass, v);
    }

    /**
     * The blur's mode and radius, or -1 for either when this build has no getter.
     *
     * Read BEFORE a write and kept, so the write can be taken back exactly: the state a row
     * had is the system's own choice of material, and nothing here should have to guess at it.
     */
    static int[] snapshot(View v) {
        init();
        return new int[]{readInt(sGetMode, v), readInt(sGetRadius, v)};
    }

    /** Puts a view back to a snapshot taken before the local blur was written. */
    static void restore(View v, int[] was) {
        init();
        if (was == null || was.length < 2) return;
        call(sRadius, v, Math.max(0, was[1]));
        call(sMode, v, Math.max(0, was[0]));
        // The row's own default is to pass the window's blur through - that is what the system
        // sets on a row, and what our write turned off. Put back last, so a failure above leaves
        // the row closer to the system's material and not further from it.
        call(sPassWindow, v, true);
    }

    private static int readInt(Method m, View v) {
        if (m == null || v == null) return -1;
        try {
            final Object o = m.invoke(v);
            return o instanceof Integer ? (Integer) o : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object read(Method m, View v) {
        if (m == null || v == null) return "?";
        try {
            return m.invoke(v);
        } catch (Throwable t) {
            return "!";
        }
    }

    private static Method find(String name, Class<?> param) {
        try {
            final Method m = View.class.getDeclaredMethod(name, param);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The system's own recipe for a container that blurs what is inside the window rather than
     * what is behind it - copied from `NotificationUtil.applyContainerViewBlur(..., localOnly=true)`.
     *
     * The `passWindow = false` is the whole point and the part that is easy to get wrong. A
     * notification row's default treatment is `setMiViewBlurMode(1)`, which REUSES the
     * window-level blur - and that samples the app behind the window. Setting only that flag on
     * the row does nothing at all; the row has to be given a blur of its own and then told not
     * to pass the window's through.
     */
    static void applyLocalBlur(View v, int radius) {
        init();
        call(sMode, v, 1);
        call(sType, v, 1);
        call(sRadius, v, radius);
        call(sPassWindow, v, false);
    }

    static void clearLocalBlur(View v) {
        init();
        call(sRadius, v, 0);
        call(sMode, v, 0);
    }

    private static void call(Method m, View v, Object arg) {
        if (m == null) return;
        try {
            m.invoke(v, arg);
        } catch (Throwable t) {
            // One line per failure would be one line per notification row per re-bind.
        }
    }
}
