package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Method;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Hosts a second OEM keyguard clock in the unlocked notification panel. */
public final class IosNotificationCenterPresentation {
    private static final String TAG = "[IOSShade] ";
    private static ViewGroup window;
    private static FrameLayout panel;
    private static FrameLayout host;
    private static View clock;
    private static ImageView depth;
    private static MirrorView status;
    private static View stack;
    private static View[] oldHeader;
    private static float[] oldHeaderAlpha;
    private static float stackAdded;
    private static float stackWritten = Float.NaN;
    private static float progress;
    private static float lastNotificationY = Float.NaN;
    private static Drawable lastDepthDrawable;
    private static ViewTreeObserver observer;
    private static boolean clockUnavailable;

    private IosNotificationCenterPresentation() {}

    public static void attach(ViewGroup root) {
        if (window == root) return;
        release();
        window = root;
        clockUnavailable = false;
    }

    public static void update(float fraction, boolean enabled) {
        progress = enabled ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        if (progress <= 0f) {
            releasePresentation();
            return;
        }
        if (host == null && (clockUnavailable || !create())) return;
        host.setAlpha(progress);
        sync();
    }

    public static void release() {
        releasePresentation();
        window = null;
    }

    private static boolean create() {
        ViewGroup root = window;
        if (root == null) return false;
        try {
            View candidate = byId(root, "notification_panel");
            if (!(candidate instanceof FrameLayout)) {
                for (int i = 0; i < root.getChildCount(); i++) {
                    View child = root.getChildAt(i);
                    if (child.getClass().getName().equals("com.android.systemui.shade.NotificationPanelView")) {
                        candidate = child;
                        break;
                    }
                }
            }
            if (!(candidate instanceof FrameLayout)) return false;
            FrameLayout target = (FrameLayout) candidate;
            Context context = root.getContext();
            Class<?> type = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false,
                    context.getClassLoader());
            View nativeClock = (View) type.getConstructor(Context.class).newInstance(context);
            type.getMethod("addClockView").invoke(nativeClock);
            if (!(nativeClock instanceof FrameLayout) || ((FrameLayout) nativeClock).getChildCount() == 0) {
                Xp.log(TAG + "keyguard clock has no content; retaining native shade header");
                return false;
            }
            FrameLayout container = new DisplayHost(context);
            container.setClipChildren(false);
            container.setClipToPadding(false);
            container.setClickable(false);
            container.addView(nativeClock, new FrameLayout.LayoutParams(-1, -1));
            ImageView foreground = new ImageView(context);
            foreground.setScaleType(ImageView.ScaleType.FIT_XY);
            foreground.setClickable(false);
            container.addView(foreground, new FrameLayout.LayoutParams(-1, -1));
            MirrorView statusMirror = new MirrorView(context);
            container.addView(statusMirror, new FrameLayout.LayoutParams(-1, -1));
            target.addView(container, 0, new FrameLayout.LayoutParams(-1, -1));
            panel = target;
            host = container;
            clock = nativeClock;
            depth = foreground;
            status = statusMirror;
            stack = byId(target, "notification_stack_scroller");
            View header = byId(target, "normal_notification_header_view");
            oldHeader = new View[]{byId(header, "big_time"), byId(header, "date_time"),
                    byId(header, "horizontal_time")};
            oldHeaderAlpha = new float[oldHeader.length];
            for (int i = 0; i < oldHeader.length; i++) {
                if (oldHeader[i] != null) {
                    oldHeaderAlpha[i] = oldHeader[i].getAlpha();
                    oldHeader[i].setAlpha(0f);
                }
            }
            observer = target.getViewTreeObserver();
            observer.addOnPreDrawListener(FRAME);
            Xp.log(TAG + "keyguard clock presentation attached");
            return true;
        } catch (Throwable error) {
            Xp.log(TAG + "keyguard clock presentation unavailable: " + error);
            releasePresentation();
            clockUnavailable = true;
            return false;
        }
    }

    private static final ViewTreeObserver.OnPreDrawListener FRAME = () -> {
        sync();
        return true;
    };

    private static void sync() {
        if (host == null || panel == null || progress <= 0f) return;
        for (int i = 0; i < oldHeader.length; i++) {
            View original = oldHeader[i];
            if (original != null && original.getAlpha() != 0f) {
                oldHeaderAlpha[i] = original.getAlpha();
                original.setAlpha(0f);
            }
        }
        syncDepth();
        syncStatus();
        syncNotifications();
    }

    private static void syncDepth() {
        View source = byId(window, "deducted_image_view");
        if (!(source instanceof ImageView)) return;
        Drawable drawable = ((ImageView) source).getDrawable();
        if (drawable == lastDepthDrawable) return;
        lastDepthDrawable = drawable;
        Drawable copy = drawable == null || drawable.getConstantState() == null
                ? null : drawable.getConstantState().newDrawable().mutate();
        depth.setScaleType(((ImageView) source).getScaleType());
        depth.setImageDrawable(copy);
        depth.setVisibility(copy == null ? View.GONE : View.VISIBLE);
    }

    private static void syncStatus() {
        View source = byId(window, "keyguard_status_bar");
        if (source != null && source != status && source.getWidth() > 0 && source.getHeight() > 0) {
            if (status.source != source) {
                status.source = source;
                status.invalidate();
            }
        }
    }

    private static void syncNotifications() {
        View currentStack = stack;
        if (currentStack == null || currentStack.getHeight() == 0 || clock == null) return;
        int[] panelPoint = new int[2];
        int[] stackPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        currentStack.getLocationInWindow(stackPoint);
        float current = currentStack.getTranslationY();
        if (current != stackWritten) stackAdded = 0f;
        float nativeTop = stackPoint[1] - panelPoint[1] - stackAdded;
        float desired = clockNotificationTop();
        if (desired <= 0f) return;
        float gap = 16f * panel.getResources().getDisplayMetrics().density;
        float added = Math.max(0f, desired + gap - nativeTop) * progress;
        if (Math.abs(added - stackAdded) > .5f || current != stackWritten) {
            float next = current == stackWritten ? current - stackAdded + added : current + added;
            stackAdded = added;
            stackWritten = next;
            currentStack.setTranslationY(next);
        }
        float notificationY = nativeTop + added;
        if (Math.abs(notificationY - lastNotificationY) > 1f) {
            lastNotificationY = notificationY;
            try {
                Class<?> kind = Class.forName(
                        "com.miui.systemui.notification.data.repository.NotificationTopChangeType",
                        false, clock.getClass().getClassLoader());
                Object changed = kind.getField("NOTIFS_CHANGED").get(null);
                Method method = clock.getClass().getMethod("notifStateChange", float.class, boolean.class, kind);
                method.invoke(clock, notificationY, false, changed);
            } catch (Throwable ignored) {
                // Other OEM builds may use a different notification change type.
            }
        }
    }

    private static float clockNotificationTop() {
        try {
            Object value = clock.getClass().getMethod("getNotificationClockTop").invoke(clock);
            if (value instanceof Number) {
                float top = ((Number) value).floatValue();
                if (top > 0f && top < panel.getHeight()) return top;
            }
        } catch (Throwable ignored) {}
        Rect bounds = new Rect();
        ViewGroup group = (ViewGroup) clock;
        float bottom = 0f;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() >= panel.getHeight()) continue;
            if (child.getGlobalVisibleRect(bounds)) {
                int[] point = new int[2];
                panel.getLocationOnScreen(point);
                bottom = Math.max(bottom, bounds.bottom - point[1]);
            }
        }
        return bottom;
    }

    private static void releasePresentation() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(FRAME);
        observer = null;
        if (oldHeader != null) {
            for (int i = 0; i < oldHeader.length; i++) {
                if (oldHeader[i] != null) oldHeader[i].setAlpha(oldHeaderAlpha[i]);
            }
        }
        oldHeader = null;
        oldHeaderAlpha = null;
        if (stack != null && stackAdded != 0f && stack.getTranslationY() == stackWritten) {
            stack.setTranslationY(stack.getTranslationY() - stackAdded);
        }
        stack = null;
        stackAdded = 0f;
        stackWritten = Float.NaN;
        lastNotificationY = Float.NaN;
        lastDepthDrawable = null;
        if (host != null && host.getParent() instanceof ViewGroup) {
            ((ViewGroup) host.getParent()).removeView(host);
        }
        host = null;
        panel = null;
        clock = null;
        depth = null;
        status = null;
    }

    private static View byId(View root, String name) {
        if (root == null) return null;
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }

    private static final class MirrorView extends View {
        View source;

        MirrorView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return;
            int[] sourcePoint = new int[2];
            int[] ownPoint = new int[2];
            source.getLocationOnScreen(sourcePoint);
            getLocationOnScreen(ownPoint);
            int save = canvas.save();
            canvas.translate(sourcePoint[0] - ownPoint[0], sourcePoint[1] - ownPoint[1]);
            source.draw(canvas);
            canvas.restoreToCount(save);
            if (isAttachedToWindow()) postInvalidateDelayed(1000L);
        }
    }

    private static final class DisplayHost extends FrameLayout {
        DisplayHost(Context context) { super(context); }

        @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }
    }
}
