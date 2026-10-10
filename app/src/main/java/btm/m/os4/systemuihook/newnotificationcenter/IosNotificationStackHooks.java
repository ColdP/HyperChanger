package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Keeps focus/media cards expanded while retaining OEM stacking for ordinary notifications. */
final class IosNotificationStackHooks {
    private static final WeakHashMap<View, ArrayList<Path>> STACK_MASKS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> SYSTEM_EXPANSION = new WeakHashMap<>();
    private static ViewGroup maskedStack;
    private static Method resetGroupBackground;

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
        try {
            Object injector = Xp.callMethod(view, "getInjector");
            if (hasType(injector, ".MiuiMediaHeaderViewInjector")) return true;
            if (Boolean.TRUE.equals(Xp.callMethod(injector, "isFocusNotification"))) return true;
        } catch (Throwable ignored) {}
        if (hasType(view, ".MiuiMediaHeaderView")) return true;
        // Use the legacy entry as well: refactored rows can lack getEntry().
        Object entry = null;
        try {
            entry = Xp.callMethod(Xp.callMethod(view, "getInjector"), "getLegacyEntry");
        } catch (Throwable ignored) {}
        if (entry == null) {
            try { entry = Xp.callMethod(view, "getEntry"); } catch (Throwable ignored) {}
        }
        Object sbn = null;
        if (entry != null) {
            try { sbn = Xp.getObjectField(entry, "mSbn"); } catch (Throwable ignored) {}
            if (sbn == null) {
                try { sbn = Xp.callMethod(entry, "getSbn"); } catch (Throwable ignored) {}
            }
        }
        if (sbn == null) return false;
        // Keep these checks independent so a missing vendor field cannot skip media detection.
        try {
            if (Boolean.TRUE.equals(Xp.getObjectField(sbn, "mIsFocusNotification"))) return true;
        } catch (Throwable ignored) {}
        try {
            if (Boolean.TRUE.equals(Xp.getObjectField(sbn, "isMediaNotification"))) return true;
        } catch (Throwable ignored) {}
        try {
            android.app.Notification notification = (android.app.Notification) Xp.callMethod(sbn, "getNotification");
            return Boolean.TRUE.equals(Xp.callMethod(notification, "isMediaNotification"))
                    || notification.extras.containsKey("android.mediaSession")
                    || android.app.Notification.CATEGORY_TRANSPORT.equals(notification.category);
        } catch (Throwable ignored) {}
        return false;
    }

    /** Apply after OEM property composition, including compiled/inlined stacking paths. */
    static void enforceUnstackedRow(View row) {
        if (!IosNotificationCenterPresentation.ownsRow(row) || !isFocusOrMediaNotification(row)) return;
        try {
            Object injector = Xp.callMethod(row, "getInjector");
            Xp.setObjectField(injector, "stackingProgress", 0f);
            Xp.setObjectField(injector, "shouldHideDueToStacking", false);
            // Ext transforms contain unrelated swipe/heads-up effects. Only remove the
            // extra stacking transform applied on top of those base values.
            float y = ((Number) Xp.callMethod(row, "getExtTranslationY")).floatValue();
            float sx = ((Number) Xp.callMethod(row, "getExtScaleX")).floatValue();
            float sy = ((Number) Xp.callMethod(row, "getExtScaleY")).floatValue();
            Xp.callMethod(row, "setSuperTranslationY", row.getTranslationY() + y);
            Xp.callMethod(row, "setSuperScaleX", row.getScaleX() * sx);
            Xp.callMethod(row, "setSuperScaleY", row.getScaleY() * sy);
            if (((Number) Xp.callMethod(row, "getExtClipBottomAmount")).intValue() != 0) {
                Xp.callMethod(row, "setExtClipBottomAmount", 0);
            }
            float alpha = ((Number) Xp.callMethod(row, "getExtAlpha")).floatValue();
            Xp.callMethod(row, "setSuperAlpha", row.getAlpha() * alpha);
            Object dim = Xp.getObjectField(injector, "dimForeground");
            if (dim instanceof Drawable && ((Drawable) dim).getAlpha() != 0) ((Drawable) dim).setAlpha(0);
            Object state = Xp.callMethod(row, "getViewState");
            if (!Boolean.TRUE.equals(Xp.getObjectField(state, "hidden"))
                    && !Boolean.TRUE.equals(Xp.getObjectField(state, "gone"))
                    && row.getAlpha() > 0f && alpha > 0f
                    && row.getVisibility() == View.INVISIBLE) row.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
        try {
            if (resetGroupBackground != null && hasType(row, ".ExpandableNotificationRow")
                    && (Boolean.TRUE.equals(Xp.callMethod(row, "isGroupParent"))
                    || Boolean.TRUE.equals(Xp.callMethod(row, "isChildInGroup")))) {
                Object background = Xp.callMethod(Xp.callMethod(row, "getInjector"), "getBackgroundNormal");
                if (background instanceof View) {
                    View bg = (View) background;
                    Object bgInjector = Xp.getObjectField(bg, "mNotificationBackgroundViewInjector");
                    if (((Number) Xp.getObjectField(bgInjector, "extClipBottomAmount")).intValue() != 0
                            || ((Number) Xp.callMethod(bg, "getSuperTranslationY")).floatValue() != bg.getTranslationY()
                            || ((Number) Xp.callMethod(bg, "getSuperScaleX")).floatValue() != bg.getScaleX()
                            || ((Number) Xp.callMethod(bg, "getSuperScaleY")).floatValue() != bg.getScaleY()) {
                        resetGroupBackground.invoke(null, row);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    static void enforceUnstackedRows(View stack) {
        if (!IosNotificationCenterPresentation.ownsStack(stack) || !(stack instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) stack;
        for (int i = 0; i < group.getChildCount(); i++) {
            View row = group.getChildAt(i);
            enforceUnstackedRow(row);
            if (!hasType(row, ".ExpandableNotificationRow")) continue;
            try {
                Object children = Xp.callMethod(row, "getAttachedChildren");
                if (children instanceof List<?>) {
                    for (Object child : (List<?>) children) {
                        if (child instanceof View) enforceUnstackedRow((View) child);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean hasType(Object object, String suffix) {
        if (object == null) return false;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith(suffix)) return true;
        }
        return false;
    }

    /** Reserve the natural layout through the last full-height special card. */
    static float specialRowsExtent(View stack) {
        if (!(stack instanceof ViewGroup)) return 0f;
        float extent = 0f;
        float reserved = 0f;
        float padding = 0f;
        Object algorithm = null;
        try {
            algorithm = Xp.getObjectField(stack, "mStackScrollAlgorithm");
            padding = ((Number) Xp.getObjectField(algorithm, "mPaddingBetweenElements")).floatValue();
            Object ambient = Xp.getObjectField(stack, "mAmbientState");
            extent = ((Number) Xp.callMethod(algorithm, "getScrimTopPaddingOrZero", ambient)).floatValue();
        } catch (Throwable ignored) {}
        View previous = null;
        ViewGroup group = (ViewGroup) stack;
        for (int i = 0; i < group.getChildCount(); i++) {
            View row = group.getChildAt(i);
            if (row.getVisibility() == View.GONE || hasType(row, ".NotificationShelf")) continue;
            float height;
            try { height = ((Number) Xp.callMethod(row, "getIntrinsicHeight")).floatValue(); }
            catch (Throwable ignored) { height = row.getHeight(); }
            if (height <= 0f) continue;
            try {
                extent += ((Number) Xp.callMethod(algorithm, "getGapHeightForChild", row, previous)).floatValue();
            } catch (Throwable ignored) {}
            extent += height;
            if (isFocusOrMediaNotification(row)) reserved = extent;
            extent += padding;
            previous = row;
        }
        return reserved;
    }

    /** Only layout inputs are borrowed. Always return OEM state to its actual lifecycle. */
    private static final class LayoutInputs implements AutoCloseable {
        final Object ambient;
        final LinkedHashMap<String, Object> saved = new LinkedHashMap<>();

        LayoutInputs(Object ambient) { this.ambient = ambient; }

        void set(String name, Object value) {
            try {
                if (!saved.containsKey(name)) saved.put(name, Xp.getObjectField(ambient, name));
                Xp.setObjectField(ambient, name, value);
            } catch (Throwable ignored) {}
        }

        @Override public void close() {
            for (Map.Entry<String, Object> entry : saved.entrySet()) {
                try { Xp.setObjectField(ambient, entry.getKey(), entry.getValue()); }
                catch (Throwable ignored) {}
            }
        }
    }

    // Property composition runs after resetViewStates has restored the ambient state.
    // Give its stacking calculator the same resting origin throughout the exit.
    static Object restingStackOrigin(View row) {
        if (!IosNotificationCenterPresentation.ownsRow(row)) return null;
        try {
            Object ambient = Xp.getObjectField(Xp.callMethod(row, "getInjector"), "ambientState");
            View parent = row;
            while (parent.getParent() instanceof View &&
                    !IosNotificationCenterPresentation.ownsStack(parent)) parent = (View) parent.getParent();
            LayoutInputs inputs = new LayoutInputs(ambient);
            inputs.set("mStackY", ((Number) Xp.callMethod(parent, "getTopPadding")).floatValue());
            return inputs;
        } catch (Throwable ignored) { return null; }
    }

    static void restoreStackOrigin(Object token) {
        if (token instanceof LayoutInputs) ((LayoutInputs) token).close();
    }

    private static void constrainSpecialRows(ViewGroup stack, float top) {
        float bottom = IosNotificationCenterPresentation.viewportBottom(stack);
        // A block taller than the viewport remains a scrollable list, never overlapping cards.
        if (specialRowsExtent(stack) > bottom - top + 1f) return;
        float overflow = 0f;
        for (int i = 0; i < stack.getChildCount(); i++) {
            View row = stack.getChildAt(i);
            if (row.getVisibility() == View.GONE || !isFocusOrMediaNotification(row)) continue;
            try {
                Object state = Xp.callMethod(row, "getViewState");
                if (Boolean.TRUE.equals(Xp.getObjectField(state, "gone"))) continue;
                float y = ((Number) Xp.getObjectField(state, "mYTranslation")).floatValue();
                float height = ((Number) Xp.getObjectField(state, "height")).floatValue();
                overflow = Math.max(overflow, y + height - bottom);
            } catch (Throwable ignored) {}
        }
        if (overflow <= 0f) return;
        // Cancel excess downward spring displacement for the whole list, preserving spacing.
        for (int i = 0; i < stack.getChildCount(); i++) {
            try {
                Object state = Xp.callMethod(stack.getChildAt(i), "getViewState");
                float y = ((Number) Xp.getObjectField(state, "mYTranslation")).floatValue();
                Xp.callMethod(state, "setYTranslation", "IOS viewport", y - overflow);
            } catch (Throwable ignored) {}
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
            if (!child.isShown() || child.getAlpha() <= 0f || child.getWidth() <= 0
                    || (!hasType(child, ".ExpandableNotificationRow")
                    && !hasType(child, ".MiuiMediaHeaderView"))) continue;
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
            if (!hasType(row, ".ExpandableNotificationRow")) continue;
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
        IosNotificationAnimationHooks.install(loader);
        // EMPTY neutralizes translation, shrink, clipping and dimming. Intercept the final
        // application as well as the calculator: group/transition paths can reuse old results.
        try {
            Class<?> info = Xp.findClass(
                    "com.miui.systemui.notification.view.NotificationRowStackingInfo", loader);
            Object unstacked = info.getField("EMPTY").get(null);
            Class<?> injector = Xp.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableViewInjector", loader);
            for (Method method : injector.getDeclaredMethods()) {
                if (method.getName().equals("resetGroupBackgroundStackingInfo")) {
                    method.setAccessible(true);
                    resetGroupBackground = method;
                    break;
                }
            }
            Xp.hookAll(injector, "applyViewStackingInfo", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (IosNotificationCenterPresentation.isActive() && args.length >= 2
                        && args[0] instanceof View && isFocusOrMediaNotification((View) args[0])) {
                    args[1] = unstacked;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Xp.hookAll(injector, "updateDimmingAndVisible", chain -> {
                Object target = chain.getThisObject();
                Object row = Xp.getObjectField(target, "view");
                if (IosNotificationCenterPresentation.isActive() && row instanceof View
                        && isFocusOrMediaNotification((View) row)) {
                    Xp.setObjectField(target, "stackingProgress", 0f);
                    return chain.proceed(new Object[]{unstacked});
                }
                return chain.proceed();
            });
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
            Xp.hookAll(stack, "setAlpha", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 1 && args[0] instanceof Number) {
                    args[0] = IosNotificationCenterPresentation.panelStackAlpha(
                            (View) chain.getThisObject(), ((Number) args[0]).floatValue());
                }
                return chain.proceed(args);
            });
            IosNotificationAnimationHooks.deoptimize(stack, "setAlpha", "updateVisibility$5");
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification scroll range unavailable: " + error);
        }
        try {
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            Xp.hookAll(stack, "dispatchDraw", chain -> {
                // Android filters INVISIBLE children before drawChild. Clear stale stacking
                // visibility first, including notifications inside expanded groups.
                enforceUnstackedRows((View) chain.getThisObject());
                View view = (View) chain.getThisObject();
                if (!IosNotificationCenterPresentation.ownsStack(view)
                        || chain.getArgs().isEmpty() || !(chain.getArgs().get(0) instanceof Canvas)) {
                    return chain.proceed();
                }
                clipStackedRows(view, true);
                Canvas canvas = (Canvas) chain.getArgs().get(0);
                int save = canvas.save();
                try {
                    canvas.clipRect(0f, 0f, view.getWidth(),
                            IosNotificationCenterPresentation.viewportBottom(view));
                    return chain.proceed();
                } finally { canvas.restoreToCount(save); }
            });
            Xp.hookAll(stack, "drawChild", chain -> {
                // This is the last boundary before Android records the child's RenderNode.
                // It also covers ROMs which inline the OEM property/calculator methods.
                if (chain.getArgs().size() >= 2 && chain.getArgs().get(1) instanceof View) {
                    enforceUnstackedRow((View) chain.getArgs().get(1));
                }
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
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            // Resolve the real listener type from the field; JADX renames anonymous classes.
            // Otherwise AOT-inlined resetViewStates can bypass our appearance/offset hook.
            try {
                IosNotificationAnimationHooks.deoptimize(
                        stack.getDeclaredField("mChildrenUpdater").getType(), "onPreDraw");
            } catch (Throwable error) {
                Xp.log("[IOSShade] stack pre-draw deoptimization unavailable: " + error);
            }
            Xp.hookAll(algorithm, "resetViewStates", chain -> {
                if (chain.getArgs().isEmpty()) return chain.proceed();
                Object ambient = chain.getArgs().get(0);
                Object host = Xp.getObjectField(chain.getThisObject(), "mHostView");
                if (!(host instanceof android.view.View)) return chain.proceed();
                boolean own = IosNotificationCenterPresentation.ownsStack((View) host);
                if (own && host instanceof ViewGroup) {
                    expandSpecialRows((ViewGroup) host);
                }
                if (!own) return chain.proceed();
                ViewGroup view = (ViewGroup) host;
                float nativeTop = ((Number) Xp.callMethod(view, "getTopPadding")).floatValue();
                float top = nativeTop + IosNotificationCenterPresentation.stackOffset(view, nativeTop);
                float bottom = IosNotificationCenterPresentation.viewportBottom(view);
                Object controller = Xp.getObjectField(view, "mController");
                Object injector = Xp.getObjectField(controller, "mNsslControllerInjector");
                try (LayoutInputs inputs = new LayoutInputs(ambient);
                     LayoutInputs bounds = new LayoutInputs(injector)) {
                    bounds.set("stackingBottom", bottom);
                    inputs.set("panelAppeared", true);
                    inputs.set("panelVisible", true);
                    inputs.set("mShadeExpanded", true);
                    inputs.set("mExpansionFraction", 1f);
                    inputs.set("mExpansionChanging", false);
                    inputs.set("mLayoutHeight", view.getHeight());
                    inputs.set("mStackHeight", Math.max(0f, bottom - top));
                    inputs.set("mStackEndHeight", Math.max(0f, bottom - top));
                    inputs.set("mStackY", top);
                    inputs.set("mScrollY", Math.max(0,
                            ((Number) Xp.getObjectField(ambient, "mScrollY")).intValue()));
                    Object result = chain.proceed();
                    constrainSpecialRows(view, top);
                    return result;
                }
            });
            Xp.hookAll(algorithm, "updateViewWithShelf", chain -> {
                if (!chain.getArgs().isEmpty() && chain.getArgs().get(0) instanceof View) {
                    View row = (View) chain.getArgs().get(0);
                    if (IosNotificationCenterPresentation.ownsRow(row)
                            && isFocusOrMediaNotification(row)) return null;
                }
                return chain.proceed();
            });
            IosNotificationAnimationHooks.deoptimize(algorithm, "resetViewStates");
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification stack position unavailable: " + error);
        }
    }
}
