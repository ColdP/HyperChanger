package btm.m.os4.systemuihook.newnotificationcenter;

import android.view.View;
import android.view.ViewGroup;

import java.util.List;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Settles row coordinates during entry; the parent supplies the gesture motion. */
final class IosNotificationEntryState {
    private static int yAnimatorTag;
    private static int springAnimatorTag;
    private static boolean restingFrameDrawn;
    private static boolean entering;
    private static boolean fullyOpen;
    private static boolean deferredOnce;
    private static View preparedStack;

    private IosNotificationEntryState() {}

    static void configure(int tag, int springTag) {
        yAnimatorTag = tag;
        springAnimatorTag = springTag;
    }

    static boolean ownsPanelMotion() { return !restingFrameDrawn; }

    static void updateProgress(float previous, float current) {
        if (current < 1f) restingFrameDrawn = false;
        if (current <= 0f || current < previous) {
            clear();
        } else if (previous <= 0f) {
            entering = true;
            fullyOpen = current >= 1f;
            preparedStack = null;
            deferredOnce = false;
        } else if (entering) {
            fullyOpen = current >= 1f;
        }
    }

    static void clear() {
        restingFrameDrawn = false;
        entering = false;
        fullyOpen = false;
        preparedStack = null;
        deferredOnce = false;
    }

    private static boolean ownsEntry(View stack) {
        return yAnimatorTag != 0 && entering && !IosNotificationExitState.isHolding()
                && IosNotificationCenterPresentation.ownsStack(stack);
    }

    static void targetsReady(View stack) {
        if (ownsEntry(stack)) preparedStack = stack;
    }

    static boolean deferDraw(View stack) {
        if (!ownsEntry(stack) || preparedStack == stack || deferredOnce) return false;
        // Bound the fallback: an unsupported layout hook must never hide the list forever.
        deferredOnce = true;
        return true;
    }

    static void frameDrawn(View stack) {
        if (ownsEntry(stack) && preparedStack == stack && fullyOpen) {
            clear();
            restingFrameDrawn = true;
        }
    }

    static boolean settle(View row, Object state) {
        if (preparedStack == null || !ownsEntry(preparedStack)
                || !IosNotificationCenterPresentation.ownsRow(row)
                || !IosNotificationStackHooks.isNotificationRow(row)
                || row.getVisibility() == View.GONE) return false;
        try {
            if (state == null) state = Xp.callMethod(row, "getViewState");
            if (!eligible(row, state)) return false;
            float target = ((Number) Xp.getObjectField(state, "mYTranslation")).floatValue();
            if (Float.isNaN(target) || Float.isInfinite(target)) return false;
            cancelSpring(row, state);
            // Disabling animateY would only retarget an existing animator. Use its native
            // cancellation path so finish listeners and animation tags are cleaned up.
            if (row.getTag(yAnimatorTag) != null) {
                Xp.callMethod(state, "abortAnimation", yAnimatorTag, row);
            }
            if (row.getTranslationY() != target) row.setTranslationY(target);
            return true;
        } catch (Throwable ignored) {
            // Unsupported row/state versions retain the native application path.
            return false;
        }
    }

    static void prepareTargets(View stack) {
        if (ownsEntry(stack)) visitStack(stack, false);
    }

    /** Last draw boundary for ROMs that inline the OEM property pass. */
    static void settleForDraw(View stack) {
        if (preparedStack == stack && ownsEntry(stack)) visitStack(stack, true);
    }

    private static void visitStack(View stack, boolean draw) {
        if (!(stack instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) stack;
        for (int i = 0; i < group.getChildCount(); i++) visitRow(group.getChildAt(i), draw);
    }

    private static void visitRow(View row, boolean draw) {
        if (!IosNotificationStackHooks.isNotificationRow(row)
                || !IosNotificationCenterPresentation.ownsRow(row)) return;
        try {
            Object state = Xp.callMethod(row, "getViewState");
            if (!eligible(row, state)) return;
            if (draw) {
                if (settle(row, state)) {
                    // MIUI's setTranslationY updates a logical field, leaving the old
                    // RenderNode until the next properties pass. Publish the final Y now,
                    // preserving independent overscroll and other external transforms.
                    float y = row.getTranslationY()
                            + ((Number) Xp.callMethod(row, "getExtTranslationY")).floatValue();
                    if (((Number) Xp.callMethod(row, "getSuperTranslationY")).floatValue() != y) {
                        Xp.callMethod(row, "setSuperTranslationY", y);
                    }
                }
            } else {
                cancelSpring(row, state);
            }
        } catch (Throwable ignored) { /* Unsupported rows retain native geometry. */ }
        try {
            Object children = Xp.callMethod(row, "getAttachedChildren");
            if (children instanceof List<?>) for (Object child : (List<?>) children)
                if (child instanceof View) visitRow((View) child, draw);
        } catch (Throwable ignored) {}
    }

    private static void cancelSpring(View row, Object state) {
        if (springAnimatorTag == 0) return;
        try {
            Object injector = Xp.getObjectField(state, "mInjector");
            if (row.getTag(springAnimatorTag) != null) {
                Xp.callMethod(state, "abortAnimation", springAnimatorTag, row);
            }
            if (((Number) Xp.getObjectField(injector, "springYOffset")).intValue() != 0) {
                Xp.setObjectField(injector, "springYOffset", 0);
            }
        } catch (Throwable ignored) { /* Spring channel is optional on older ROMs. */ }
    }

    private static boolean eligible(View row, Object state) throws Throwable {
        if (row.getVisibility() == View.GONE || Boolean.TRUE.equals(Xp.getObjectField(state, "gone"))) return false;
        // Real removal and heads-up transitions keep their own animation lifecycle.
        if (flag(row, "isRemoved") || flag(row, "isHeadsUpState") || flag(row, "isHeadsUp")
                || flag(row, "isPinned") || flag(row, "isHeadsUpAnimatingAway$1")) return false;
        try {
            if (Boolean.TRUE.equals(Xp.getObjectField(row, "mDismissed"))) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    private static boolean flag(View row, String method) {
        try { return Boolean.TRUE.equals(Xp.callMethod(row, method)); }
        catch (Throwable ignored) { return false; }
    }
}
