package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Redirect native glass to the wallpaper in this window without replacing its glass recipe. */
public final class IosShadeMaterial {
    private static final String[] SETTERS = {
            "setMiBackgroundBlurMode", "setMiBackgroundBlurType", "setMiBackgroundBlurRadius",
            "setPassWindowBlurEnabled", "disableMiBackgroundContainBelow"
    };
    private static final String[] GETTERS = {
            "getMiBackgroundBlurMode", "getMiBackgroundBlurType", "getMiBackgroundBlurRadius",
            "getPassWindowBlurEnabled", "getMiBackgroundContainBelow"
    };
    private static final Method[] METHODS = new Method[SETTERS.length];
    private static final Method[] READERS = new Method[SETTERS.length];
    private static Method viewBlurSetter;
    private static final WeakHashMap<View, Object[]> SAVED = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> CONTAIN_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> MEDIA = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> VIEW_BLUR_MODES = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();
    private static boolean active;
    private static ViewGroup root;
    private static View clockRoot;
    private static ViewTreeObserver observer;
    private static boolean loggedFailure;
    private static int openingFrames;
    private static final ViewTreeObserver.OnGlobalLayoutListener LAYOUT = () -> {
        if (active && root != null) scan(root);
    };

    public static void install() {
        for (int index = 0; index < SETTERS.length; index++) {
            final int slot = index;
            try {
                Class<?> argument = slot >= 3 ? boolean.class : int.class;
                Method setter = View.class.getDeclaredMethod(SETTERS[slot], argument);
                setter.setAccessible(true);
                METHODS[slot] = setter;
                try {
                    READERS[slot] = View.class.getDeclaredMethod(GETTERS[slot]);
                    READERS[slot].setAccessible(true);
                } catch (Throwable ignored) {
                    // A missing getter is restored by the next native recipe, never guessed.
                }
                Xp.api().hook(setter)
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            View view = (View) chain.getThisObject();
                            if (Boolean.TRUE.equals(WRITING.get())) return chain.proceed();
                            Object requested = chain.getArgs().get(0);
                            if (slot == 4 && !isContainmentSurface(view)) return chain.proceed();
                            if (slot == 4 && isContainmentSurface(view)) {
                                CONTAIN_REQUESTS.put(view, (Boolean) requested);
                            }
                            if (!active || (!target(view) && !(slot == 4 && isClock(view)))
                                    || view.getRootView() != root) return chain.proceed();
                            Object[] saved = remember(view);
                            saved[slot] = requested;
                            return chain.proceed(new Object[]{wanted(view, slot, saved)});
                        });
            } catch (Throwable error) {
                Xp.log("[IOSShade] material API unavailable: " + SETTERS[slot] + ": " + error);
            }
        }
        // New or rebound rows need their local blur even after the opening edge has passed.
        try {
            Method setViewBlur = View.class.getDeclaredMethod("setMiViewBlurMode", int.class);
            setViewBlur.setAccessible(true);
            viewBlurSetter = setViewBlur;
            Xp.api().hook(setViewBlur)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View view = (View) chain.getThisObject();
                        if (!Boolean.TRUE.equals(WRITING.get())) {
                            VIEW_BLUR_MODES.put(view, (Integer) chain.getArgs().get(0));
                        }
                        if (active && !Boolean.TRUE.equals(WRITING.get()) && !SAVED.containsKey(view) && target(view)
                                && view.getRootView() == root) apply(view);
                        return result;
                    });
        } catch (Throwable error) {
            Xp.log("[IOSShade] row material refresh hook unavailable: " + error);
        }
    }

    public static void setActive(boolean enabled, ViewGroup window) {
        if (enabled == active && window == root) return;
        restore();
        root = window;
        active = enabled && window != null;
        if (!active) return;
        openingFrames = 0;
        observer = window.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(LAYOUT);
        scan(window);
    }

    public static void registerClock(View view) {
        clockRoot = view;
        if (active && view != null) scanClock(view);
    }

    public static void refreshInitialRows(boolean fullyExpanded) {
        if (!active || !fullyExpanded) {
            if (openingFrames < 2) openingFrames = 0;
            return;
        }
        if (openingFrames >= 2) return;
        if (++openingFrames < 2) {
            root.postInvalidateOnAnimation();
            return;
        }
        Method setter = viewBlurSetter;
        if (setter == null) return;
        scan(root);
        WRITING.set(true);
        try {
            for (View view : new ArrayList<>(SAVED.keySet())) {
                if (view == null || !view.isShown() || view.getRootView() != root
                        || !Integer.valueOf(1).equals(VIEW_BLUR_MODES.get(view))
                        || (!view.getClass().getName().endsWith(".NotificationBackgroundView")
                        && !MEDIA.containsKey(view))) continue;
                try {
                    setter.invoke(view, 0);
                    setter.invoke(view, 1);
                    view.invalidate();
                } catch (Throwable ignored) {
                    // One unsupported view should not prevent the other rows from refreshing.
                }
            }
        } finally {
            WRITING.remove();
        }
    }

    private static boolean isClock(View view) {
        View clock = clockRoot;
        for (View cursor = view; cursor != null; ) {
            if (cursor == clock) return clock != null;
            cursor = cursor.getParent() instanceof View ? (View) cursor.getParent() : null;
        }
        return false;
    }

    private static void scanClock(View view) {
        if (METHODS[4] != null && !SAVED.containsKey(view)) {
            Object[] saved = new Object[SETTERS.length];
            try {
                if (READERS[4] != null) saved[4] = !(Boolean) READERS[4].invoke(view);
                if (saved[4] != null) {
                    SAVED.put(view, saved);
                    WRITING.set(true);
                    METHODS[4].invoke(view, false);
                }
            } catch (Throwable ignored) {
            } finally {
                WRITING.remove();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scanClock(group.getChildAt(i));
        }
    }

    private static boolean isPanel(View view) {
        return view.getClass().getName().equals("com.android.systemui.shade.NotificationPanelView");
    }

    private static boolean isDismissView(View view) {
        // The clear-all control is a CircleAndTickAnimView. Its native material keeps the
        // notification surface as the sampling boundary, which is wrong over the iOS sheet.
        return view.getClass().getName().equals("com.miui.systemui.widget.CircleAndTickAnimView");
    }

    private static boolean isContainmentSurface(View view) {
        return isPanel(view) || isDismissView(view) || isClock(view);
    }

    private static boolean target(View view) {
        String name = view.getClass().getName();
        return isPanel(view) || name.endsWith(".NotificationBackgroundView")
                || name.equals("com.miui.systemui.widget.CircleAndTickAnimView") || MEDIA.containsKey(view);
    }

    public static void registerMedia(View header) {
        try {
            Object holder = Xp.callMethod(header, "getMediaViewHolder");
            if (holder == null) return;
            Object background = Xp.getObjectField(holder, "mediaBg");
            if (!(background instanceof View)) return;
            View view = (View) background;
            MEDIA.put(view, true);
            if (active && view.getRootView() == root && !SAVED.containsKey(view)) apply(view);
        } catch (Throwable ignored) {
        }
    }

    private static void scan(View view) {
        if (view == clockRoot) scanClock(view);
        if (view.getClass().getName().equals(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView")) registerMedia(view);
        if (target(view) && view.isShown() && !SAVED.containsKey(view)) apply(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
        }
    }

    private static Object[] remember(View view) {
        Object[] saved = SAVED.get(view);
        if (saved != null) return saved;
        saved = new Object[SETTERS.length];
        for (int slot = 0; slot < READERS.length; slot++) {
            try {
                if (READERS[slot] != null) saved[slot] = READERS[slot].invoke(view);
            } catch (Throwable ignored) {
            }
        }
        // The getter reports containment; the setter is the inverse (disable containment).
        if (saved[4] instanceof Boolean) saved[4] = !(Boolean) saved[4];
        else saved[4] = null;
        if (CONTAIN_REQUESTS.containsKey(view)) saved[4] = CONTAIN_REQUESTS.get(view);
        SAVED.put(view, saved);
        return saved;
    }

    private static Object wanted(View view, int slot, Object[] saved) {
        switch (slot) {
            case 0: return 1; // A blur owned by this surface instead of the app's window blur.
            case 1: return 1;
            case 2:
                int radius = saved[2] instanceof Number ? ((Number) saved[2]).intValue() : 0;
                // Keep the panel's native animated radius; rows and clear-all need their own.
                return isPanel(view) ? radius : (radius > 0 ? radius : 80);
            case 3: return false;
            case 4: return false; // Include the wallpaper sibling below NotificationPanelView.
            default: throw new IllegalArgumentException();
        }
    }

    private static void apply(View view) {
        Object[] saved = remember(view);
        WRITING.set(true);
        try {
            for (int slot = 0; slot < METHODS.length; slot++) {
                // The panel and clear-all control sample the wallpaper sibling below the
                // notification surface. Notification rows keep their native containment.
                if (slot == 4 && (!(isPanel(view) || isDismissView(view)) || saved[4] == null)) continue;
                if (METHODS[slot] != null) METHODS[slot].invoke(view, wanted(view, slot, saved));
            }
        } catch (Throwable error) {
            if (!loggedFailure) {
                loggedFailure = true;
                Xp.log("[IOSShade] local material failed: " + error);
            }
        } finally {
            WRITING.remove();
        }
    }

    private static void restore() {
        active = false;
        openingFrames = 0;
        clockRoot = null;
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(LAYOUT);
        observer = null;
        WRITING.set(true);
        try {
            for (Map.Entry<View, Object[]> entry : new ArrayList<>(SAVED.entrySet())) {
                View view = entry.getKey();
                Object[] values = entry.getValue();
                if (view == null) continue;
                for (int slot = 0; slot < METHODS.length; slot++) {
                    if (METHODS[slot] == null || values[slot] == null) continue;
                    try {
                        METHODS[slot].invoke(view, values[slot]);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            SAVED.clear();
            WRITING.remove();
        }
    }
}
