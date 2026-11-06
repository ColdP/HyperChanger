package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.content.res.Resources;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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
    // Enabling pass-window blur adds a visible texture entry in ViewRootImpl even if
    // the View's background mode is zero. Restore it BEFORE the local mode changes
    // back to zero, so that mode's setter also retires the texture entry. Reversing
    // these writes leaves a window-wide sampler alive after the sheet has closed.
    private static final int[] RESTORE_ORDER = {3, 0, 1, 2, 4};
    private static final WeakHashMap<View, Object[]> SAVED = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> CONTAIN_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> MEDIA = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> GROUP_CONTROLS = new WeakHashMap<>();
    // Only class names are immutable. Parentage and explicit group/media registration
    // must still be checked live when notification views are rebound or reparented.
    private static final class GroupType {
        final boolean control, owner;
        GroupType(Class<?> type) {
            String name = type.getName().toLowerCase(java.util.Locale.ROOT);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            control = simple.contains("expandbutton") || simple.contains("collapsebutton");
            owner = name.contains("expandablenotificationrow")
                    || name.contains("notificationchildrencontainer");
        }
    }
    private static final ClassValue<GroupType> GROUP_TYPES = new ClassValue<GroupType>() {
        @Override protected GroupType computeValue(Class<?> type) { return new GroupType(type); }
    };
    private static final WeakHashMap<Resources, HashMap<Integer, Boolean>> GROUP_RESOURCE_IDS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> GLASS_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> SAVED_GLASS = new WeakHashMap<>();
    private static Method glassRadius;
    private static int glassSmallRadius;
    private static int glassBigRadius;
    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();
    private static boolean active;
    private static ViewGroup root;
    private static View clockRoot;
    private static boolean clockRegistered;
    private static ViewTreeObserver observer;
    private static boolean loggedFailure;
    private static final ViewTreeObserver.OnGlobalLayoutListener LAYOUT = () -> {
        if (active && root != null) scan(root);
    };

    public static void install() {
        try {
            glassRadius = View.class.getDeclaredMethod("setMiGlassBlurRadius", int.class, int.class);
            glassRadius.setAccessible(true);
            Xp.api().hook(glassRadius)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        View view = (View) chain.getThisObject();
                        if (Boolean.TRUE.equals(WRITING.get()) || isPanel(view) || !target(view)) {
                            return chain.proceed();
                        }
                        int[] requested = {(Integer) chain.getArgs().get(0), (Integer) chain.getArgs().get(1)};
                        GLASS_REQUESTS.put(view, requested);
                        if (!active || view.getRootView() != root) return chain.proceed();
                        SAVED_GLASS.put(view, requested);
                        return chain.proceed(new Object[]{
                                requested[0] > 0 ? requested[0] : glassSmallRadius,
                                requested[1] > 0 ? requested[1] : glassBigRadius});
                    });
        } catch (Throwable error) {
            Xp.log("[IOSShade] glass sampling radius unavailable: " + error);
        }
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
                    // Only override a property after its native value is known. A missing
                    // getter must not leave an unrestorable material on the shared window.
                }
                Xp.api().hook(setter)
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            View view = (View) chain.getThisObject();
                            if (Boolean.TRUE.equals(WRITING.get())) return chain.proceed();
                            // This full-screen provider is also used by heads-up and theme
                            // transitions. Keep its mode/type/radius/pass recipe native; a
                            // row-local fallback (notably radius 0 -> 80) can cover the whole
                            // window. Only its wallpaper containment belongs to this layer.
                            if (isPanel(view) && slot != 4) return chain.proceed();
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
            Xp.api().hook(setViewBlur)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View view = (View) chain.getThisObject();
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
        glassSmallRadius = dimension(window, "notification_glass_small_blur_max_radius", 36);
        glassBigRadius = dimension(window, "notification_glass_big_blur_max_radius", 500);
        observer = window.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(LAYOUT);
        scan(window);
    }

    public static void registerClock(View view) {
        if (view == clockRoot && clockRegistered) return;
        clockRoot = view;
        if (active && view != null) {
            scanClock(view);
            clockRegistered = true;
        }
    }

    private static int dimension(View view, String name, int fallback) {
        try {
            int id = view.getResources().getIdentifier(name, "dimen", "com.android.systemui");
            return id == 0 ? fallback : view.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            return fallback;
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
                }
                // The hosted clock is an explicitly owned sampling surface. Some HyperOS
                // builds expose the setter but no readable containment getter; still reapply
                // the wallpaper boundary on a rapid reopen, where the existing clock is reused
                // and create() does not get a chance to call allowBackdropSampling().
                WRITING.set(true);
                METHODS[4].invoke(view, false);
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

    /**
     * The OEM's grouped-notification control is a separate NotificationExpandButton. It is
     * created/rebound when a group opens, so it cannot be treated as part of the row's native
     * material. Its containment boundary must follow the same wallpaper sibling as the sheet.
     */
    private static boolean isGroupCollapseView(View view) {
        if (GROUP_CONTROLS.containsKey(view)) return true;
        if (GROUP_TYPES.get(view.getClass()).control) return true;
        for (Object parent = view.getParent(); parent instanceof View;
                parent = ((View) parent).getParent()) {
            if (GROUP_TYPES.get(parent.getClass()).control) return true;
        }
        try {
            int id = view.getId();
            if (id > 0) {
                Resources resources = view.getResources();
                HashMap<Integer, Boolean> ids = GROUP_RESOURCE_IDS.get(resources);
                Boolean cached = ids == null ? null : ids.get(id);
                if (cached != null) return cached;
                String entry = resources.getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT);
                boolean control = entry.contains("collapse_button") || entry.contains("group_collapse")
                        || ((entry.contains("expand") || entry.contains("collapse"))
                        && (entry.contains("notification") || entry.contains("group")));
                if (ids == null) {
                    ids = new HashMap<>();
                    GROUP_RESOURCE_IDS.put(resources, ids);
                }
                ids.put(id, control);
                return control;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isWallpaperSurface(View view) {
        return isPanel(view) || isDismissView(view) || isGroupCollapseView(view) || isClock(view);
    }

    private static boolean isContainmentSurface(View view) {
        return isWallpaperSurface(view);
    }

    private static boolean target(View view) {
        String name = view.getClass().getName();
        return isPanel(view) || name.endsWith(".NotificationBackgroundView")
                || name.equals("com.miui.systemui.widget.CircleAndTickAnimView")
                || isGroupCollapseView(view) || MEDIA.containsKey(view);
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

    private static void registerGroupControl(View view) {
        if (view == null) return;
        GROUP_CONTROLS.put(view, true);
        if (active && view.getRootView() == root && !SAVED.containsKey(view)) apply(view);
    }

    private static final String[] GROUP_CONTROL_FIELDS = {
            "mExpandButton", "mGroupExpandButton", "mCollapseButton", "mCollapsedButton",
            "mChildrenContainer", "mNotificationChildrenContainer", "mGroupHeader",
            "mMiuiGroupHeader", "mGroupHeaderWrapper", "expandButton", "collapseButton",
            "mInjector"
    };

    private static void captureGroupFields(Object owner, int depth) {
        if (owner == null || depth < 0) return;
        for (String field : GROUP_CONTROL_FIELDS) {
            try {
                Object candidate = Xp.getObjectField(owner, field);
                if (candidate instanceof View) {
                    registerGroupControl((View) candidate);
                } else if (candidate != null && depth > 0) {
                    captureGroupFields(candidate, depth - 1);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void captureGroupControls(View owner) {
        if (!GROUP_TYPES.get(owner.getClass()).owner) return;
        // The MIUI group header keeps the actual collapse ImageView inside its injector.
        // Walk only the named notification-group fields, with a bounded depth, so ordinary
        // child notification rows are never reclassified as wallpaper surfaces.
        captureGroupFields(owner, 2);
    }

    private static void scan(View view) {
        captureGroupControls(view);
        if (view == clockRoot) {
            scanClock(view);
            return;
        }
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
            // Do not snapshot/replay full-screen properties we do not own. The OEM can
            // change them while the sheet is active without repeating every setter.
            if (isPanel(view) && slot != 4) continue;
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
                // The panel reports no native radius of its own, so honouring that value verbatim
                // left the iOS shade with refraction only: a transparent glass with no blur at all.
                // Fall back to the row radius whenever the native value is missing.
                return radius > 0 ? radius : 80;
            case 3: return false; // One local source for scene-to-wallpaper compositing.
            case 4: return false; // Include the wallpaper sibling below NotificationPanelView.
            default: throw new IllegalArgumentException();
        }
    }

    private static void apply(View view) {
        Object[] saved = remember(view);
        WRITING.set(true);
        try {
            for (int slot = 0; slot < METHODS.length; slot++) {
                // Unknown native values cannot be restored. The setter hook can still take
                // ownership later, once the OEM supplies an explicit value for this slot.
                if (saved[slot] == null) continue;
                // The panel and clear-all control sample the wallpaper sibling below the
                // notification surface. Notification rows keep their native containment.
                if (slot == 4 && (!(isPanel(view) || isDismissView(view) || isGroupCollapseView(view))
                        || saved[4] == null)) continue;
                if (METHODS[slot] != null) METHODS[slot].invoke(view, wanted(view, slot, saved));
            }
            if (!isPanel(view) && glassRadius != null) {
                // Native consumers inherit these radii from NotificationPanelView. Once a
                // consumer owns a local provider it also needs both glass sampling textures;
                // background radius alone only supplies the ordinary blur layer.
                int[] requested = GLASS_REQUESTS.get(view);
                if (requested == null) requested = new int[]{0, 0};
                SAVED_GLASS.put(view, requested);
                glassRadius.invoke(view, requested[0] > 0 ? requested[0] : glassSmallRadius,
                        requested[1] > 0 ? requested[1] : glassBigRadius);
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
        clockRoot = null;
        clockRegistered = false;
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(LAYOUT);
        observer = null;
        WRITING.set(true);
        try {
            for (Map.Entry<View, int[]> entry : new ArrayList<>(SAVED_GLASS.entrySet())) {
                if (entry.getKey() == null || glassRadius == null) continue;
                try {
                    int[] requested = entry.getValue();
                    glassRadius.invoke(entry.getKey(), requested[0], requested[1]);
                } catch (Throwable ignored) {}
            }
            for (Map.Entry<View, Object[]> entry : new ArrayList<>(SAVED.entrySet())) {
                View view = entry.getKey();
                Object[] values = entry.getValue();
                if (view == null) continue;
                for (int slot : RESTORE_ORDER) {
                    if (METHODS[slot] == null || values[slot] == null) continue;
                    try {
                        METHODS[slot].invoke(view, values[slot]);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            SAVED.clear();
            SAVED_GLASS.clear();
            GROUP_RESOURCE_IDS.clear();
            WRITING.remove();
        }
    }
}
