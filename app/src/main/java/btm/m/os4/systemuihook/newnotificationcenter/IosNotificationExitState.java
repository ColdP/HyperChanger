package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Retains the last drawn cards while the sheet owns the exit, including a cancelled exit. */
final class IosNotificationExitState {
    private static final WeakHashMap<View, Card> DRAWN = new WeakHashMap<>();
    private static boolean holding;
    private static int frameStamp;

    private IosNotificationExitState() {}

    static boolean isHolding() { return holding; }

    static void updateProgress(float previous, float current) {
        if (current <= 0f || previous <= 0f) {
            clear();
        } else if (current >= 1f) {
            // Resume native row updates only at the resting endpoint. Reversing a drag
            // midway through the exit must not briefly expose the collapsed native layout.
            holding = false;
        } else if (current < previous && !DRAWN.isEmpty()) {
            holding = true;
        }
    }

    static void clear() {
        holding = false;
        DRAWN.clear();
    }

    static void rememberFrame(View stack) {
        if (holding || !IosNotificationCenterPresentation.ownsStack(stack)
                || !(stack instanceof ViewGroup)) return;
        int stamp = ++frameStamp;
        ViewGroup group = (ViewGroup) stack;
        for (int i = 0; i < group.getChildCount(); i++) rememberRow(group.getChildAt(i), stamp);
        if (!DRAWN.isEmpty()) {
            java.util.Iterator<Map.Entry<View, Card>> it = DRAWN.entrySet().iterator();
            while (it.hasNext()) {
                Card card = it.next().getValue();
                if (card == null || card.stamp != stamp) it.remove();
            }
        }
    }

    private static void rememberRow(View row, int stamp) {
        if (!IosNotificationStackHooks.isNotificationRow(row) || row.getVisibility() == View.GONE) return;
        remember(row, true, stamp);
        Object injector = Xp.tryCallMethod(row, "getInjector");
        Object background = injector == null ? null : Xp.tryCallMethod(injector, "getBackgroundNormal");
        if (background instanceof View) remember((View) background, false, stamp);
        Object children = Xp.tryCallMethod(row, "getAttachedChildren");
        if (children instanceof List<?>) {
            for (Object child : (List<?>) children) {
                if (child instanceof View) rememberRow((View) child, stamp);
            }
        }
    }

    private static void remember(View view, boolean expandable, int stamp) {
        try {
            Card existing = DRAWN.get(view);
            if (existing == null) {
                existing = new Card();
                DRAWN.put(view, existing);
            }
            existing.capture(view, expandable, stamp);
        } catch (Throwable ignored) { /* Older OEM row types keep their native rendering. */ }
    }

    static void apply(View stack) {
        if (!holding || !IosNotificationCenterPresentation.ownsStack(stack)) return;
        // A weak key can disappear during an OEM update; iterate a stable list.
        for (Map.Entry<View, Card> entry : new ArrayList<>(DRAWN.entrySet())) {
            restore(entry.getKey(), entry.getValue());
        }
    }

    static void applyRow(View row) {
        if (holding) restore(row, DRAWN.get(row));
    }

    private static void restore(View view, Card card) {
        if (card == null || view == null || view.getVisibility() == View.GONE
                || !IosNotificationCenterPresentation.ownsRow(view)) return;
        try {
            // Keep real removals/dismissals under OEM control, even during an exit.
            if (card.expandable && Boolean.TRUE.equals(Xp.callMethod(view, "isRemoved"))) return;
        } catch (Throwable ignored) {}
        try {
            if (card.expandable && Boolean.TRUE.equals(Xp.getObjectField(view, "mDismissed"))) return;
        } catch (Throwable ignored) {}
        try { card.apply(view); }
        catch (Throwable ignored) {}
    }

    private static float value(View view, String method) {
        return ((Number) Xp.callMethod(view, method)).floatValue();
    }

    private static void physical(View view, String property, float target) {
        if (value(view, "getSuper" + property) != target) {
            Xp.callMethod(view, "setSuper" + property, target);
        }
    }

    private static void clip(View view, String property, int target) {
        if (((Number) Xp.callMethod(view, "get" + property)).intValue() != target) {
            Xp.callMethod(view, "set" + property, target);
        }
    }

    private static final class Card {
        boolean expandable;
        int stamp, visibility, height, clipTop, clipBottom, extClipBottom, dimAlpha;
        float x, y, z, sx, sy, alpha, transitionAlpha, pivotX, pivotY;
        boolean hasBounds;
        final Rect bounds = new Rect();

        void capture(View view, boolean expandable, int stamp) {
            this.expandable = expandable;
            this.stamp = stamp;
            visibility = view.getVisibility();
            x = value(view, "getSuperTranslationX");
            y = value(view, "getSuperTranslationY");
            z = value(view, "getSuperTranslationZ");
            sx = value(view, "getSuperScaleX");
            sy = value(view, "getSuperScaleY");
            alpha = value(view, "getSuperAlpha");
            transitionAlpha = value(view, "getSuperTransitionAlpha");
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            Rect clip = view.getClipBounds();
            if (clip == null) {
                hasBounds = false;
            } else {
                hasBounds = true;
                bounds.set(clip);
            }
            height = expandable ? (int) value(view, "getActualHeight") : 0;
            clipTop = expandable ? (int) value(view, "getClipTopAmount") : 0;
            clipBottom = expandable ? (int) value(view, "getClipBottomAmount") : 0;
            extClipBottom = expandable ? (int) value(view, "getExtClipBottomAmount") : 0;
            int dim = -1;
            if (expandable) {
                Object injector = Xp.tryCallMethod(view, "getInjector");
                Object drawable = injector == null ? null : Xp.tryGetObjectField(injector, "dimForeground");
                if (drawable instanceof Drawable) dim = ((Drawable) drawable).getAlpha();
            }
            dimAlpha = dim;
        }

        void apply(View view) {
            if (view.getPivotX() != pivotX) view.setPivotX(pivotX);
            if (view.getPivotY() != pivotY) view.setPivotY(pivotY);
            physical(view, "TranslationX", x);
            physical(view, "TranslationY", y);
            physical(view, "TranslationZ", z);
            physical(view, "ScaleX", sx);
            physical(view, "ScaleY", sy);
            physical(view, "Alpha", alpha);
            physical(view, "TransitionAlpha", transitionAlpha);
            if (expandable) {
                if ((int) value(view, "getActualHeight") != height) {
                    Xp.callMethod(view, "setActualHeight", height, false);
                }
                clip(view, "ClipTopAmount", clipTop);
                clip(view, "ClipBottomAmount", clipBottom);
                clip(view, "ExtClipBottomAmount", extClipBottom);
                if (dimAlpha >= 0) {
                    Object injector = Xp.tryCallMethod(view, "getInjector");
                    Object drawable = injector == null ? null : Xp.tryGetObjectField(injector, "dimForeground");
                    if (drawable instanceof Drawable && ((Drawable) drawable).getAlpha() != dimAlpha) {
                        ((Drawable) drawable).setAlpha(dimAlpha);
                    }
                }
            }
            Rect current = view.getClipBounds();
            if (!hasBounds) {
                if (current != null) view.setClipBounds(null);
            } else if (!bounds.equals(current)) {
                view.setClipBounds(bounds);
            }
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }
    }
}
