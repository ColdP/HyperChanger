package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Hosts a second OEM keyguard clock in the unlocked notification panel. */
public final class IosNotificationCenterPresentation {
    private static final String TAG = "[IOSShade] ";
    private static ViewGroup window;
    private static FrameLayout panel;
    private static FrameLayout host;
    private static View clock;
    private static TextView date;
    private static TextView dateSource;
    private static ImageView depth;
    private static MirrorView status;
    private static View stack;
    private static View capturedStack;
    private static View capturedHeader;
    private static View[] oldHeader;
    private static float[] oldHeaderAlpha;
    private static float nativeTopPadding = Float.NaN;
    private static boolean writingTopPadding;
    private static float progress;
    private static float lastNotificationY = Float.NaN;
    private static Drawable lastDepthDrawable;
    private static ViewTreeObserver observer;
    private static boolean clockUnavailable;
    private static View wallpaperSheet;
    private static int lastBlur = -1;
    private static int lastStatusBlur = -1;
    private static long dateMinute = -1L;

    private IosNotificationCenterPresentation() {}

    public static void attach(ViewGroup root) {
        if (window == root) return;
        release();
        window = root;
        clockUnavailable = false;
    }

    public static void update(float fraction, boolean enabled, View sheet) {
        float previous = progress;
        progress = enabled ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        wallpaperSheet = sheet;
        if (progress <= 0f) {
            View previousStack = stack;
            releasePresentation();
            if (previous > 0f) requestStackUpdate(previousStack);
            return;
        }
        if (host == null && (clockUnavailable || !create())) return;
        host.setAlpha(progress);
        if (status != null) status.setAlpha(progress * progress * (3f - 2f * progress));
        sync();
        if (Math.abs(progress - previous) > .01f) requestStackUpdate(stack);
    }

    public static void release() {
        releasePresentation();
        window = null;
    }

    public static boolean isActive() {
        return host != null && progress > 0f;
    }

    static float adjustTopPadding(View view, float nativeTop) {
        if (writingTopPadding || !isActive() || view != stack || panel == null) return nativeTop;
        nativeTopPadding = nativeTop;
        return adjustedTopPadding(nativeTop);
    }

    static void captureStack(View view) {
        capturedStack = view;
        if (panel != null && stack == null && view.getRootView() == panel.getRootView()) {
            stack = view;
        }
    }

    static float stackOffset(View view, float nativeStackY) {
        if (!isActive() || view != stack || panel == null || clock == null) return 0f;
        float desired = desiredNotificationTop();
        float limit = Math.max(nativeStackY, view.getHeight() -
                160f * panel.getResources().getDisplayMetrics().density);
        return Math.max(0f, Math.min(limit, desired) - nativeStackY) * progress;
    }

    static void captureHeader(View view) {
        capturedHeader = view;
        if (isActive()) findHeader();
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
            container.setClipChildren(true);
            container.setClipToPadding(true);
            container.setClipToOutline(true);
            container.setClickable(false);
            allowBackdropSampling(container);
            allowBackdropSampling(nativeClock);
            container.addView(nativeClock, new FrameLayout.LayoutParams(-1, -1));
            TextView lockDate = new TextView(context);
            lockDate.setGravity(android.view.Gravity.CENTER);
            lockDate.setTextColor(Color.WHITE);
            int dateSize = context.getResources().getIdentifier(
                    "miui_common_unlock_screen_date_text_size", "dimen", "com.android.systemui");
            lockDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dateSize == 0
                    ? 16f * context.getResources().getDisplayMetrics().scaledDensity
                    : context.getResources().getDimension(dateSize));
            lockDate.setClickable(false);
            container.addView(lockDate, new FrameLayout.LayoutParams(-1,
                    Math.round(42f * context.getResources().getDisplayMetrics().density)));
            ImageView foreground = new ImageView(context);
            foreground.setScaleType(ImageView.ScaleType.FIT_XY);
            foreground.setClickable(false);
            container.addView(foreground, new FrameLayout.LayoutParams(-1, -1));
            MirrorView statusMirror = new MirrorView(context);
            target.addView(container, 0, new FrameLayout.LayoutParams(-1, -1));
            target.addView(statusMirror, 1, new FrameLayout.LayoutParams(-1,
                    Math.round(80f * context.getResources().getDisplayMetrics().density)));
            panel = target;
            host = container;
            clock = nativeClock;
            date = lockDate;
            IosShadeMaterial.registerClock(nativeClock);
            depth = foreground;
            status = statusMirror;
            stack = byId(target, "notification_stack_scroller");
            if (stack == null) stack = byId(root, "notification_stack_scroller");
            if (stack == null && capturedStack != null
                    && capturedStack.getRootView() == target.getRootView()) stack = capturedStack;
            Xp.log(TAG + "notification stack " + (stack == null ? "not found" : "attached"));
            findHeader();
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
        View sheet = wallpaperSheet;
        if (sheet != null && sheet.isAttachedToWindow()) {
            int[] sheetPoint = new int[2];
            int[] panelPoint = new int[2];
            sheet.getLocationInWindow(sheetPoint);
            panel.getLocationInWindow(panelPoint);
            float x = sheetPoint[0] - panelPoint[0];
            float y = sheetPoint[1] - panelPoint[1];
            if (host.getTranslationX() != x) host.setTranslationX(x);
            if (host.getTranslationY() != y) host.setTranslationY(y);
            if (host.getOutlineProvider() != sheet.getOutlineProvider()) {
                host.setOutlineProvider(sheet.getOutlineProvider());
                host.invalidateOutline();
            }
        }
        if (oldHeader == null) findHeader();
        for (int i = 0; oldHeader != null && i < oldHeader.length; i++) {
            View original = oldHeader[i];
            if (original != null && original.getAlpha() != 0f) {
                oldHeaderAlpha[i] = original.getAlpha();
                original.setAlpha(0f);
            }
        }
        syncDepth();
        syncStatus();
        syncDate();
        int blur = Math.round(28f * panel.getResources().getDisplayMetrics().density * (1f - progress));
        if (blur != lastBlur) {
            host.setRenderEffect(blur == 0 ? null : RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.CLAMP));
            lastBlur = blur;
        }
        int statusBlur = Math.round(12f * panel.getResources().getDisplayMetrics().density * (1f - progress));
        if (status != null && statusBlur != lastStatusBlur) {
            status.setRenderEffect(statusBlur == 0 ? null : RenderEffect.createBlurEffect(
                    statusBlur, statusBlur, Shader.TileMode.CLAMP));
            lastStatusBlur = statusBlur;
        }
        syncNotifications();
    }

    private static void findHeader() {
        View header = byId(capturedHeader, "normal_notification_header_view");
        if (header == null) header = byId(window, "normal_notification_header_view");
        if (header == null) return;
        View[] views = {byId(header, "big_time"), byId(header, "date_time"),
                byId(header, "horizontal_time")};
        if (oldHeader != null && oldHeader[0] == views[0]) return;
        oldHeader = views;
        oldHeaderAlpha = new float[views.length];
        for (int i = 0; i < views.length; i++) {
            if (views[i] != null) {
                oldHeaderAlpha[i] = views[i].getAlpha();
                views[i].setAlpha(0f);
            }
        }
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

    private static void syncDate() {
        if (date == null || clock == null) return;
        if (dateSource == null) dateSource = lockscreenDateSource();
        if (dateSource != null && dateSource.getText().length() > 0) {
            if (!dateSource.getText().toString().contentEquals(date.getText())) {
                date.setText(dateSource.getText());
            }
            if (date.getCurrentTextColor() != dateSource.getCurrentTextColor()) {
                date.setTextColor(dateSource.getCurrentTextColor());
            }
            if (date.getTypeface() != dateSource.getTypeface()) {
                date.setTypeface(dateSource.getTypeface());
            }
            if (date.getTextSize() != dateSource.getTextSize()) {
                date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dateSource.getTextSize());
            }
        } else {
            long minute = System.currentTimeMillis() / 60000L;
            if (minute != dateMinute) {
                dateMinute = minute;
                try {
                    Context context = date.getContext();
                    boolean hour24 = android.text.format.DateFormat.is24HourFormat(context);
                    int id = context.getResources().getIdentifier(hour24
                            ? "miui_lock_screen_date" : "miui_lock_screen_date_12",
                            "string", "com.android.systemui");
                    String pattern = context.getString(id);
                    Class<?> calendarType = Class.forName("miuix.pickerwidget.date.Calendar", false,
                            context.getClassLoader());
                    Object calendar = calendarType.getConstructor().newInstance();
                    calendarType.getMethod("setTimeInMillis", long.class)
                            .invoke(calendar, System.currentTimeMillis());
                    date.setText((CharSequence) calendarType.getMethod("format", Context.class, String.class)
                            .invoke(calendar, context, pattern));
                } catch (Throwable ignored) {
                    java.text.DateFormat format = java.text.DateFormat.getDateInstance(
                            java.text.DateFormat.FULL, date.getResources().getConfiguration().getLocales().get(0));
                    date.setText(format.format(new java.util.Date()));
                }
            }
        }
        int height = date.getLayoutParams().height;
        float top = 0f;
        try {
            Object value = clock.getClass().getMethod("getNotificationClockTop").invoke(clock);
            if (value instanceof Number) top = ((Number) value).floatValue();
        } catch (Throwable ignored) {}
        float density = date.getResources().getDisplayMetrics().density;
        float y = Math.max(64f * density, top - height - 8f * density);
        if (date.getTranslationY() != y) date.setTranslationY(y);
    }

    private static TextView lockscreenDateSource() {
        try {
            Object controller = Xp.getObjectField(clock, "mMiuiClockController");
            Object face = Xp.getObjectField(controller, "mClockView");
            Object source = Xp.getObjectField(face, "mCurrentDate");
            if (source instanceof TextView) return (TextView) source;
        } catch (Throwable ignored) {}
        return findDateText(clock);
    }

    private static TextView findDateText(View view) {
        if (view instanceof TextView && view.getId() != View.NO_ID) {
            try {
                if (view.getResources().getResourceEntryName(view.getId()).contains("date")) {
                    return (TextView) view;
                }
            } catch (Throwable ignored) {}
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findDateText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void syncNotifications() {
        View currentStack = stack;
        if (currentStack == null || currentStack.getHeight() == 0 || clock == null) return;
        if (Float.isNaN(nativeTopPadding)) {
            nativeTopPadding = getPadding(currentStack);
            if (nativeTopPadding < 0f) return;
        }
        float target = adjustedTopPadding(nativeTopPadding);
        if (Math.abs(getPadding(currentStack) - target) > .5f) writeTopPadding(currentStack, target);
        int[] panelPoint = new int[2];
        int[] stackPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        currentStack.getLocationInWindow(stackPoint);
        float notificationY = stackPoint[1] - panelPoint[1] + target;
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

    private static float adjustedTopPadding(float nativeTop) {
        if (stack == null || stack.getHeight() == 0 || clock == null) return nativeTop;
        float desired = desiredNotificationTop();
        float limit = Math.max(nativeTop, stack.getHeight() -
                160f * panel.getResources().getDisplayMetrics().density);
        return nativeTop + Math.max(0f, Math.min(limit, desired) - nativeTop) * progress;
    }

    private static float desiredNotificationTop() {
        int[] panelPoint = new int[2];
        int[] stackPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        stack.getLocationInWindow(stackPoint);
        float gap = 16f * panel.getResources().getDisplayMetrics().density;
        return clockBottom() + host.getTranslationY() + gap -
                (stackPoint[1] - panelPoint[1]);
    }

    private static void writeTopPadding(View view, float value) {
        writingTopPadding = true;
        try {
            view.getClass().getMethod("updateTopPadding", float.class, boolean.class)
                    .invoke(view, value, false);
        } catch (Throwable ignored) {
        } finally {
            writingTopPadding = false;
        }
    }

    private static float clockBottom() {
        try {
            Object value = clock.getClass().getMethod("getClockBottom").invoke(clock);
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
        if (bottom > 0f) return bottom;
        float density = panel.getResources().getDisplayMetrics().density;
        try {
            Object value = clock.getClass().getMethod("getNotificationClockTop").invoke(clock);
            if (value instanceof Number) {
                return Math.max(220f * density, ((Number) value).floatValue() + 140f * density);
            }
        } catch (Throwable ignored) {}
        return 220f * density;
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
        if (stack != null && !Float.isNaN(nativeTopPadding)) {
            writeTopPadding(stack, nativeTopPadding);
        }
        stack = null;
        nativeTopPadding = Float.NaN;
        lastNotificationY = Float.NaN;
        lastDepthDrawable = null;
        if (host != null && host.getParent() instanceof ViewGroup) {
            ((ViewGroup) host.getParent()).removeView(host);
        }
        if (status != null && status.getParent() instanceof ViewGroup) {
            ((ViewGroup) status.getParent()).removeView(status);
        }
        host = null;
        panel = null;
        clock = null;
        date = null;
        dateSource = null;
        depth = null;
        status = null;
        wallpaperSheet = null;
        lastBlur = -1;
        lastStatusBlur = -1;
        dateMinute = -1L;
    }

    private static int getPadding(View view) {
        try {
            return ((Number) view.getClass().getMethod("getTopPadding").invoke(view)).intValue();
        } catch (Throwable ignored) { return -1; }
    }

    private static void allowBackdropSampling(View view) {
        try {
            Method method = View.class.getDeclaredMethod("disableMiBackgroundContainBelow", boolean.class);
            method.setAccessible(true);
            method.invoke(view, false);
        } catch (Throwable error) {
            Xp.log(TAG + "clock backdrop containment unavailable: " + error);
        }
    }

    private static void requestStackUpdate(View currentStack) {
        if (currentStack == null) return;
        try {
            Xp.callMethod(currentStack, "requestChildrenUpdate");
        } catch (Throwable ignored) {
        }
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
            source.draw(canvas);
            if (isAttachedToWindow()) postInvalidateDelayed(1000L);
        }
    }

    private static final class DisplayHost extends FrameLayout {
        DisplayHost(Context context) { super(context); }

        @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }
    }
}
