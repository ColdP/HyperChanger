package btm.m.os4.systemuihook.newnotificationcenter;

import android.view.View;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Settles row coordinates during entry; the parent supplies the gesture motion. */
final class IosNotificationEntryState {
    private static int yAnimatorTag;
    private static boolean entering;
    private static boolean fullyOpen;
    private static boolean deferredOnce;
    private static View preparedStack;

    private IosNotificationEntryState() {}

    static void configure(int tag) { yAnimatorTag = tag; }

    static void updateProgress(float previous, float current) {
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
        if (ownsEntry(stack) && preparedStack == stack && fullyOpen) clear();
    }

    static void settle(View row, Object state) {
        if (preparedStack == null || !ownsEntry(preparedStack)
                || !IosNotificationCenterPresentation.ownsRow(row)
                || !IosNotificationStackHooks.isNotificationRow(row)
                || row.getVisibility() == View.GONE) return;
        try {
            if (state == null) state = Xp.callMethod(row, "getViewState");
            if (Boolean.TRUE.equals(Xp.getObjectField(state, "gone"))) return;
            // Real removal and heads-up transitions keep their own animation lifecycle.
            if (flag(row, "isRemoved") || flag(row, "isHeadsUpState") || flag(row, "isHeadsUp")
                    || flag(row, "isPinned") || flag(row, "isHeadsUpAnimatingAway$1")) return;
            try {
                if (Boolean.TRUE.equals(Xp.getObjectField(row, "mDismissed"))) return;
            } catch (Throwable ignored) {}
            float target = ((Number) Xp.getObjectField(state, "mYTranslation")).floatValue();
            if (Float.isNaN(target) || Float.isInfinite(target)) return;
            // Disabling animateY would only retarget an existing animator. Use its native
            // cancellation path so finish listeners and animation tags are cleaned up.
            if (row.getTag(yAnimatorTag) != null) {
                Xp.callMethod(state, "abortAnimation", yAnimatorTag, row);
            }
            if (row.getTranslationY() != target) row.setTranslationY(target);
        } catch (Throwable ignored) {
            // Unsupported row/state versions retain the native application path.
        }
    }

    private static boolean flag(View row, String method) {
        try { return Boolean.TRUE.equals(Xp.callMethod(row, method)); }
        catch (Throwable ignored) { return false; }
    }
}
