package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Temporarily lends the actual plugin subtree to the unlocked notification center. */
final class IosNotificationShortcutHost extends FrameLayout {
    private static Object plugin;
    private static IosNotificationShortcutLaunch launch;
    private static View content;
    private static View left;
    private static View right;
    private static IosNotificationShortcutHost overlay;
    private static ViewGroup originalParent;
    private static ViewGroup.LayoutParams originalParams;
    private static int originalIndex;
    private static final ArrayList<VisualState> visibilityOverrides = new ArrayList<>();
    private static final int[] POINT = new int[2];
    private static final Rect BOUNDS = new Rect();

    private boolean touching;
    private boolean childrenCancelled;
    private boolean discardingTouch;
    private float downX, downY;
    private MotionEvent lastTouch;
    private final int touchSlop;

    private IosNotificationShortcutHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    static void capture(Object controller) {
        try {
            Object currentPlugin = Xp.getObjectField(controller, "miuiShortcutPlugin");
            Object root = currentPlugin == null ? null : Xp.callMethod(currentPlugin, "getShortcutContentView");
            View currentContent = root instanceof View ? (View) root : null;
            if (plugin != currentPlugin || content != currentContent) clear();
            plugin = currentPlugin;
            content = currentContent;
            left = shortcut(controller, true);
            right = shortcut(controller, false);
            // A plugin can recreate its controllers without replacing its content root.
            if (launch != null) launch.cancel();
            launch = null;
            if (currentPlugin != null && currentContent != null) {
                launch = IosNotificationShortcutLaunch.create(controller, currentPlugin);
            }
            IosNotificationCenterPresentation.syncShortcuts();
        } catch (Throwable error) {
            clear();
            Xp.log("[IOSShade] native shortcut capture unavailable: " + error);
        }
    }

    private static View shortcut(Object controller, boolean isLeft) {
        Bundle args = new Bundle();
        args.putBoolean("isLeftShortcutView", isLeft);
        Object result = Xp.callMethod(controller, "onSystemUIAction$1", args, "getShortcutView");
        return result instanceof View ? (View) result : null;
    }

    static void clear() {
        restore();
        plugin = null;
        launch = null;
        content = null;
        left = null;
        right = null;
    }

    static void update(FrameLayout panel, View sheet, float progress, boolean enabled) {
        if (!enabled || panel == null || plugin == null || content == null || launch == null
                || (left == null && right == null)) {
            restore();
            return;
        }
        if (overlay != null && overlay.getParent() != panel) restore();
        if (overlay == null) {
            // Do not defeat the OEM's theme, provisioning, user or power-save restrictions.
            if (content.getVisibility() != View.VISIBLE
                    || !(content.getParent() instanceof ViewGroup)) return;
            originalParent = (ViewGroup) content.getParent();
            originalParams = content.getLayoutParams();
            originalIndex = originalParent.indexOfChild(content);
            IosNotificationShortcutHost created = new IosNotificationShortcutHost(panel.getContext());
            overlay = created;
            try {
                originalParent.removeView(content);
                created.addView(content, new FrameLayout.LayoutParams(-1, -1));
                panel.addView(created, new FrameLayout.LayoutParams(-1, -1));
                // The normal shortcut branch can be INVISIBLE after unlocking. Restore only
                // this presentation state, leaving absent/disabled icon visibility untouched.
                revealBranch(left);
                revealBranch(right);
            } catch (Throwable error) {
                restore();
                Xp.log("[IOSShade] native shortcut hosting unavailable: " + error);
                return;
            }
        }
        IosNotificationShortcutHost current = overlay;
        if (current == null) return;
        if (current.getAlpha() != progress) current.setAlpha(progress);
        float x = sheet == null ? 0f : sheet.getTranslationX();
        float y = sheet == null ? 0f : sheet.getTranslationY();
        if (current.getTranslationX() != x) current.setTranslationX(x);
        if (current.getTranslationY() != y) current.setTranslationY(y);
    }

    private static void revealBranch(View view) {
        if (view == null) return;
        for (View current = view; current != null; ) {
            // Keep GONE branches and the icon's own visibility under native control.
            if (current.getVisibility() == View.GONE) break;
            boolean recorded = false;
            for (VisualState state : visibilityOverrides) {
                if (state.view == current) { recorded = true; break; }
            }
            if (!recorded && (current.getAlpha() == 0f
                    || (current != view && current.getVisibility() == View.INVISIBLE))) {
                visibilityOverrides.add(new VisualState(current));
                current.setAlpha(1f);
                if (current != view) current.setVisibility(View.VISIBLE);
            }
            if (current == content) break;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
    }

    static void restore() {
        IosNotificationShortcutHost old = overlay;
        if (old == null) return;
        // Clear ownership before callbacks; restoring native gestures may synchronously hide shade.
        overlay = null;
        old.cancelTouch();
        for (VisualState state : visibilityOverrides) state.restore();
        visibilityOverrides.clear();
        if (content != null && content.getParent() == old) {
            old.removeView(content);
            if (originalParent != null) {
                int index = Math.max(0, Math.min(originalIndex, originalParent.getChildCount()));
                originalParent.addView(content, index, originalParams);
            }
        }
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        originalParent = null;
        originalParams = null;
    }

    /** Called before native window dispatch so the header/stack cannot steal icon gestures. */
    static boolean dispatchWindowTouch(MotionEvent event) {
        IosNotificationShortcutHost current = overlay;
        if (current == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            current.cancelTouch();
            // Buttons become interactive at the resting endpoint, matching the native shade.
            if (current.getAlpha() < .999f || !current.isShown()
                    || !(hit(left, event) || hit(right, event))) return false;
            launch.begin();
            current.touching = true;
            current.discardingTouch = false;
            current.downX = event.getRawX();
            current.downY = event.getRawY();
            current.childrenCancelled = false;
        }
        if (current.discardingTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                current.discardingTouch = false;
            }
            return true;
        }
        if (!current.touching) return false;
        current.forward(event);
        return true;
    }

    private static boolean hit(View view, MotionEvent event) {
        if (view == null || !view.isShown() || !view.isEnabled() || view.getAlpha() <= 0f
                || !view.getGlobalVisibleRect(BOUNDS)) return false;
        float x = event.getRawX(), y = event.getRawY();
        if (!BOUNDS.contains((int) x, (int) y)) return false;
        view.getLocationOnScreen(POINT);
        float dx = x - POINT[0] - view.getWidth() / 2f;
        float dy = y - POINT[1] - view.getHeight() / 2f;
        float radius = Math.min(view.getWidth(), view.getHeight()) / 2f;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void forward(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            // The OEM treats CANCEL as UP and can launch an active shortcut. Reset instead.
            cancelTouch();
            discardingTouch = action == MotionEvent.ACTION_POINTER_DOWN;
            return;
        }
        if (lastTouch != null) lastTouch.recycle();
        lastTouch = MotionEvent.obtain(event);
        MotionEvent nativeEvent = MotionEvent.obtain(event);
        // The plugin compares getX/Y with getLocationOnScreen, as on the lockscreen.
        nativeEvent.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        try {
            Xp.callMethod(plugin, "onTouchEvent", nativeEvent);
            if (overlay != this || !touching) return;
            if (!childrenCancelled && action == MotionEvent.ACTION_MOVE
                    && (Math.abs(event.getRawX() - downX) > touchSlop
                    || Math.abs(event.getRawY() - downY) > touchSlop)) {
                dispatchChildren(event, MotionEvent.ACTION_CANCEL);
                childrenCancelled = true;
            }
            if (!childrenCancelled) dispatchChildren(event, event.getAction());
            if (action == MotionEvent.ACTION_UP) {
                launch.released();
                finishTouch();
            }
        } catch (Throwable error) {
            cancelTouch();
            Xp.log("[IOSShade] native shortcut touch unavailable: " + error);
        } finally {
            nativeEvent.recycle();
        }
    }

    private void dispatchChildren(MotionEvent event, int action) {
        MotionEvent childEvent = MotionEvent.obtain(event);
        getLocationOnScreen(POINT);
        childEvent.offsetLocation(event.getRawX() - POINT[0] - event.getX(),
                event.getRawY() - POINT[1] - event.getY());
        childEvent.setAction(action);
        try { super.dispatchTouchEvent(childEvent); }
        finally { childEvent.recycle(); }
    }

    private void cancelTouch() {
        MotionEvent cancel = touching && lastTouch != null ? MotionEvent.obtain(lastTouch) : null;
        finishTouch();
        try {
            if (cancel != null) dispatchChildren(cancel, MotionEvent.ACTION_CANCEL);
        } catch (Throwable error) {
            Xp.log("[IOSShade] native shortcut cancellation unavailable: " + error);
        } finally {
            if (cancel != null) cancel.recycle();
            if (launch != null) launch.cancel();
        }
    }

    private void finishTouch() {
        touching = false;
        discardingTouch = false;
        if (lastTouch != null) { lastTouch.recycle(); lastTouch = null; }
    }

    // Window dispatch owns shortcut streams. The overlay passes all other touches through.
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }

    private static final class VisualState {
        final View view;
        final int visibility;
        final float alpha;
        VisualState(View view) {
            this.view = view;
            visibility = view.getVisibility();
            alpha = view.getAlpha();
        }
        void restore() {
            // Preserve any newer native animation state.
            if (view.getVisibility() == View.VISIBLE) view.setVisibility(visibility);
            if (view.getAlpha() == 1f) view.setAlpha(alpha);
        }
    }
}
