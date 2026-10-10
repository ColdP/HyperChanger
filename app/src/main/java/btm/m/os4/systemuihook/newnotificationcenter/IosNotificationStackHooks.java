package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.WeakHashMap;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Keeps focus/media cards expanded while retaining OEM stacking for ordinary notifications. */
final class IosNotificationStackHooks {
    private static final WeakHashMap<View, ArrayList<Path>> STACK_MASKS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> SYSTEM_EXPANSION = new WeakHashMap<>();
    private static ViewGroup maskedStack;

    private static final class RowPosition {
        final View view;
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float radius;

        RowPosition(View view, float left, float top, float right, float bottom, float radius) {
            this.view = view;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.radius = radius;
        }
    }

    private IosNotificationStackHooks() {}

    /**
     * The OEM uses this exact predicate for lock-screen stacking.  Media and focus rows are
     * always laid out at their full height and never used as a stack mask source or target.
     */
    static boolean isFocusOrMediaNotification(View view) {
        if (view == null) return false;
        String name = view.getClass().getName();
        if (name.endsWith(".MiuiMediaHeaderView")) return true;
        if (!name.endsWith(".ExpandableNotificationRow")) return false;
        try {
            Object injector = Xp.callMethod(view, "getInjector");
            return Boolean.TRUE.equals(Xp.callMethod(injector, "isFocusNotification"));
        } catch (Throwable ignored) {
            // Older SystemUI builds do not expose the injector method. Keep the row in the
            // normal path there instead of making a whole notification stack unrenderable.
            return false;
        }
    }

    static void clipStackedRows(View stack, boolean active) {
        if (!active || !(stack instanceof ViewGroup)) {
            restoreStackedRows();
            return;
        }
        ViewGroup group = (ViewGroup) stack;
        maskedStack = group;
        STACK_MASKS.clear();
        ArrayList<RowPosition> rows = new ArrayList<>();
        int[] point = new int[2];
        int[] stackPoint = new int[2];
        group.getLocationInWindow(stackPoint);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getName();
            if (!child.isShown() || child.getAlpha() <= 0f || child.getWidth() <= 0
                    || (!name.endsWith(".ExpandableNotificationRow")
                    && !name.endsWith(".MiuiMediaHeaderView"))) continue;
            if (isFocusOrMediaNotification(child)) continue;
            try {
                int height = ((Number) Xp.callMethod(child, "getActualHeight")).intValue();
                float scaleX = ((Number) Xp.callMethod(child, "getSuperScaleX")).floatValue();
                float scaleY = ((Number) Xp.callMethod(child, "getSuperScaleY")).floatValue();
                if (height <= 0 || scaleX <= 0f || scaleY <= 0f) continue;
                child.getLocationInWindow(point);
                Rect bounds = child.getClipBounds();
                int clipBottom = ((Number) Xp.callMethod(child, "getClipBottomAmount")).intValue();
                int stackBottom = ((Number) Xp.callMethod(child, "getExtClipBottomAmount")).intValue();
                int bottom = height - Math.max(clipBottom, stackBottom);
                if (bounds != null) bottom = Math.min(bottom, bounds.bottom);
                float left = point[0] - stackPoint[0];
                float top = point[1] - stackPoint[1];
                float radius = ((Number) Xp.callMethod(child, "getBgRadius")).floatValue()
                        * Math.min(scaleX, scaleY);
                rows.add(new RowPosition(child, left, top, left + child.getWidth() * scaleX,
                        top + Math.max(0, bottom) * scaleY, radius));
            } catch (Throwable ignored) {
                // Leave unsupported OEM row variants untouched.
            }
        }
        rows.sort(Comparator.comparingDouble(row -> row.top));
        for (int i = 1; i < rows.size(); i++) {
            RowPosition lower = rows.get(i);
            for (int j = 0; j < i; j++) {
                RowPosition upper = rows.get(j);
                if (upper.bottom <= lower.top || upper.left >= lower.right
                        || upper.right <= lower.left) continue;
                Path shape = new Path();
                shape.addRoundRect(upper.left, upper.top, upper.right, upper.bottom,
                        upper.radius, upper.radius, Path.Direction.CW);
                ArrayList<Path> masks = STACK_MASKS.get(lower.view);
                if (masks == null) {
                    masks = new ArrayList<>();
                    STACK_MASKS.put(lower.view, masks);
                }
                masks.add(shape);
            }
        }
    }

    static void restoreStackedRows() {
        maskedStack = null;
        STACK_MASKS.clear();
    }

    static void restoreExpandedRows(View stack) {
        for (View row : new ArrayList<>(SYSTEM_EXPANSION.keySet())) {
            try {
                Xp.callMethod(row, "setSystemExpanded", SYSTEM_EXPANSION.get(row));
                Xp.callMethod(row, "scheduleUpdateProperties");
            } catch (Throwable ignored) {}
        }
        SYSTEM_EXPANSION.clear();
        if (stack instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) stack;
            for (int i = 0; i < group.getChildCount(); i++) {
                View row = group.getChildAt(i);
                if (!isFocusOrMediaNotification(row)) continue;
                try {
                    Xp.callMethod(row, "scheduleUpdateProperties");
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void expandSpecialRows(ViewGroup stack) {
        for (int i = 0; i < stack.getChildCount(); i++) {
            View row = stack.getChildAt(i);
            if (!row.getClass().getName().endsWith(".ExpandableNotificationRow")) continue;
            try {
                if (isFocusOrMediaNotification(row)) {
                    if (!SYSTEM_EXPANSION.containsKey(row)) {
                        SYSTEM_EXPANSION.put(row,
                                Boolean.TRUE.equals(Xp.getObjectField(row, "mIsSystemExpanded")));
                    }
                    // System expansion supplies the default while preserving a user's
                    // explicit expand/collapse choice; never write mUserExpanded.
                    if (!Boolean.TRUE.equals(Xp.getObjectField(row, "mIsSystemExpanded"))) {
                        Xp.callMethod(row, "setSystemExpanded", true);
                        // resetViewStates consumes the injector's cached height.
                        Xp.callMethod(row, "getIntrinsicHeight");
                    }
                } else if (SYSTEM_EXPANSION.containsKey(row)) {
                    Xp.callMethod(row, "setSystemExpanded", SYSTEM_EXPANSION.remove(row));
                }
            } catch (Throwable ignored) {}
        }
    }

    static void install(ClassLoader loader) {
        // EMPTY is the OEM's neutral stacking result: no translation, shrink, clipping or
        // dimming. Intercept the calculator so every downstream property uses the same result.
        try {
            Class<?> info = Xp.findClass(
                    "com.miui.systemui.notification.view.NotificationRowStackingInfo", loader);
            Object unstacked = info.getField("EMPTY").get(null);
            Class<?> interactor = Xp.findClass(
                    "com.miui.systemui.notification.domain.interactor.NotificationStackingInteractor", loader);
            io.github.libxposed.api.XposedInterface.Hooker specialRows = chain -> {
                if (IosNotificationCenterPresentation.isActive() && !chain.getArgs().isEmpty()
                        && chain.getArgs().get(0) instanceof View
                        && isFocusOrMediaNotification((View) chain.getArgs().get(0))) return unstacked;
                return chain.proceed();
            };
            Xp.hookAll(interactor, "calculateStackingInfo", specialRows);
            Xp.hookAll(interactor, "updateParentStackingInfo", specialRows);
            Xp.hookAll(interactor, "updateChildStackingInfo", specialRows);
            Xp.log("[IOSShade] focus/media notification stacking excluded");
        } catch (Throwable error) {
            Xp.log("[IOSShade] focus/media notification stacking unavailable: " + error);
        }
        try {
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            Xp.hookAll(stack, "getScrollRange", chain -> {
                Object result = chain.proceed();
                if (!(chain.getThisObject() instanceof android.view.View) || !(result instanceof Number)) {
                    return result;
                }
                return ((Number) result).intValue() + IosNotificationCenterPresentation.extraScrollRange(
                        (android.view.View) chain.getThisObject());
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification scroll range unavailable: " + error);
        }
        try {
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            Xp.hookAll(stack, "drawChild", chain -> {
                if (chain.getThisObject() != maskedStack || chain.getArgs().size() < 2
                        || !(chain.getArgs().get(0) instanceof Canvas)
                        || !(chain.getArgs().get(1) instanceof View)) return chain.proceed();
                ArrayList<Path> masks = STACK_MASKS.get((View) chain.getArgs().get(1));
                if (masks == null || masks.isEmpty()) return chain.proceed();
                Canvas canvas = (Canvas) chain.getArgs().get(0);
                int save = canvas.save();
                try {
                    for (Path mask : masks) canvas.clipOutPath(mask);
                    return chain.proceed();
                } finally {
                    canvas.restoreToCount(save);
                }
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification stack mask unavailable: " + error);
        }
        try {
            Class<?> algorithm = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm", loader);
            Xp.hookAll(algorithm, "resetViewStates", chain -> {
                if (chain.getArgs().isEmpty()) return chain.proceed();
                Object ambient = chain.getArgs().get(0);
                Object host = Xp.getObjectField(chain.getThisObject(), "mHostView");
                if (!(host instanceof android.view.View)) return chain.proceed();
                if (IosNotificationCenterPresentation.isActive() && host instanceof ViewGroup) {
                    expandSpecialRows((ViewGroup) host);
                }
                float original = ((Number) Xp.getObjectField(ambient, "mStackY")).floatValue();
                float offset = IosNotificationCenterPresentation.stackOffset((android.view.View) host, original);
                if (offset <= .5f) return chain.proceed();
                Xp.setObjectField(ambient, "mStackY", original + offset);
                try {
                    return chain.proceed();
                } finally {
                    Xp.setObjectField(ambient, "mStackY", original);
                }
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification stack position unavailable: " + error);
        }
    }
}
