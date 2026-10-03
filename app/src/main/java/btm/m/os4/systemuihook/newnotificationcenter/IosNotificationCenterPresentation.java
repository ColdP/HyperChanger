package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
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
    private static View clockInfo;
    private static float clockInfoOffset;
    private static float clockInfoBaseTranslation;
    private static long clockInfoRefreshMinute = -1L;
    private static ImageView depth;
    private static MirrorView status;
    private static View stack;
    private static View capturedStack;
    private static View emptyText;
    private static float emptyTextAlpha;
    private static View capturedHeader;
    private static View notificationHeader;
    private static float notificationHeaderAlpha = 1f;
    private static View headerGradient;
    private static int headerGradientVisibility = View.VISIBLE;
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
    private static boolean hideClearButton;
    private static View clearButton;
    private static int clearButtonVisibility = View.VISIBLE;
    private static float clearButtonAlpha = 1f;
    private static int lastBlur = -1;
    private static int lastStatusBlur = -1;

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

    public static void setHideClearButton(boolean hide) {
        hideClearButton = hide;
        syncClearButton();
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
        // updateTopPadding is the native list-boundary path. Applying another
        // mStackY offset here doubles the displacement during layout passes.
        return 0f;
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
        hideHeaderGradient();
        for (int i = 0; oldHeader != null && i < oldHeader.length; i++) {
            View original = oldHeader[i];
            if (original != null && original.getAlpha() != 0f) {
                oldHeaderAlpha[i] = original.getAlpha();
                original.setAlpha(0f);
            }
        }
        syncDepth();
        syncStatus();
        syncClearButton();
        syncClockInfo();
        syncEmptyText();
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
        if (notificationHeader != header) {
            notificationHeader = header;
            notificationHeaderAlpha = header.getAlpha();
        }
        // The native header carries a gradient blur layer which sits above the
        // mirrored status bar. The hosted lockscreen clock replaces its content.
        header.setAlpha(0f);
        hideHeaderGradient();
        // Hide the notification header's own date. The separately hosted OEM
        // lockscreen clock supplies the complete date/accessory information.
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

    private static void hideHeaderGradient() {
        if (window == null) return;
        if (headerGradient == null) {
            headerGradient = byId(window, "header_clip");
            if (headerGradient == null) headerGradient = byId(window, "header_gradient_blur");
            if (headerGradient != null) headerGradientVisibility = headerGradient.getVisibility();
        }
        if (headerGradient != null) headerGradient.setVisibility(View.GONE);
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

    private static void syncClearButton() {
        if (window == null) return;
        if (!hideClearButton) {
            if (clearButton != null) {
                clearButton.setVisibility(clearButtonVisibility);
                clearButton.setAlpha(clearButtonAlpha);
            }
            return;
        }
        if (clearButton == null) {
            String[] ids = {"clear_all", "clear_all_button", "notification_clear_all", "dismiss_button"};
            for (String id : ids) {
                clearButton = byId(window, id);
                if (clearButton != null) break;
            }
            if (clearButton == null) clearButton = findViewByClass(window, "NotificationDismissView");
            if (clearButton == null) clearButton = findViewByClass(window, "DismissView");
        }
        if (clearButton != null) {
            if (clearButton.getVisibility() != View.GONE) clearButtonVisibility = clearButton.getVisibility();
            clearButtonAlpha = clearButton.getAlpha();
            clearButton.setVisibility(View.GONE);
        }
    }

    private static void syncClockInfo() {
        if (clockInfo != null && !isClockInfo(clockInfo)) {
            clockInfo.setTranslationY(clockInfoBaseTranslation);
            clockInfo = null;
            clockInfoOffset = 0f;
        }
        if (clockInfo == null) {
            clockInfo = findClockInfo();
            if (clockInfo != null) clockInfoBaseTranslation = clockInfo.getTranslationY();
        }
        syncClockMetadata();
        if (clockInfo == null || clockInfo.getHeight() == 0) return;
        if (clockInfo instanceof TextView && ((TextView) clockInfo).length() == 0) {
            long minute = System.currentTimeMillis() / 60000L;
            if (clockInfoRefreshMinute != minute) {
                clockInfoRefreshMinute = minute;
                try {
                    clock.getClass().getMethod("updateTime").invoke(clock);
                } catch (Throwable ignored) {}
            }
        }
        if (clockInfo.getVisibility() != View.VISIBLE) {
            if (!(clockInfo instanceof TextView) || ((TextView) clockInfo).length() == 0) return;
            clockInfo.setVisibility(View.VISIBLE);
        }
        float top = 0f;
        try {
            Object value = clock.getClass().getMethod("getNotificationClockTop").invoke(clock);
            if (value instanceof Number) top = ((Number) value).floatValue();
        } catch (Throwable ignored) {}
        int[] infoPoint = new int[2];
        int[] clockPoint = new int[2];
        clockInfo.getLocationInWindow(infoPoint);
        clock.getLocationInWindow(clockPoint);
        float density = clock.getResources().getDisplayMetrics().density;
        float current = infoPoint[1] - clockPoint[1] - clockInfoOffset;
        if (top <= 0f) return;
        float desired = Math.max(64f * density, top - clockInfo.getHeight() - 8f * density);
        float offset = desired - current;
        if (Math.abs(offset - clockInfoOffset) > .5f) {
            clockInfoOffset = offset;
            clockInfo.setTranslationY(clockInfoBaseTranslation + offset);
        }
    }

    /** Keep all lockscreen date/lunar/weather/health slots supplied by the OEM clock. */
    private static void syncClockMetadata() {
        try {
            Object controller = Xp.getObjectField(clock, "mMiuiClockController");
            Object face = Xp.getObjectField(controller, "mClockView");
            Class<?> type = Class.forName("com.miui.clock.module.ClockViewType", false,
                    clock.getClass().getClassLoader());
            Method getter = face.getClass().getMethod("getIClockView", type);
            String[] names = {"NOTIFICATION_DATE", "NOTIFICATION_DATA_INFO", "FULL_DATE_WEEK",
                    "DATE", "WEEK", "WEATHER", "MAGAZINE_INFO", "TEXT_AREA", "TEXT_AREA2"};
            for (String name : names) {
                try {
                    Object view = getter.invoke(face, type.getField(name).get(null));
                    if (view instanceof View && isClockInfo((View) view)) {
                        View candidate = (View) view;
                        if (candidate.getVisibility() != View.VISIBLE
                                && (!(candidate instanceof TextView)
                                || ((TextView) candidate).length() > 0)) {
                            candidate.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Throwable ignored) {
                    // Clock templates do not expose every slot.
                }
            }
            // Classic clocks keep these lockscreen-only fields outside the enum
            // slots. Reuse them as well when the selected template provides them.
            for (String field : new String[]{"mCurrentDate", "mLunarCalendarInfo", "mOwnerInfo"}) {
                try {
                    Object value = Xp.getObjectField(face, field);
                    if (value instanceof View && isClockInfo((View) value)
                            && (!(value instanceof TextView) || ((TextView) value).length() > 0)) {
                        ((View) value).setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            // Older clock modules expose only getNotificationClockTopView().
        }
    }

    private static View findClockInfo() {
        try {
            Object controller = Xp.getObjectField(clock, "mMiuiClockController");
            Object face = Xp.getObjectField(controller, "mClockView");
            Method topGetter = face.getClass().getMethod("getNotificationClockTopView");
            Object topView = topGetter.invoke(face);
            if (topView instanceof View && isClockInfo((View) topView)) return (View) topView;
            Class<?> type = Class.forName("com.miui.clock.module.ClockViewType", false,
                    clock.getClass().getClassLoader());
            Method getter = face.getClass().getMethod("getIClockView", type);
            for (String name : new String[]{"TEXT_AREA", "FULL_DATE_WEEK", "DATE"}) {
                Object kind = type.getField(name).get(null);
                Object view = getter.invoke(face, kind);
                if (view instanceof View && isClockInfo((View) view)
                        && ((View) view).getVisibility() == View.VISIBLE
                        && (!(view instanceof TextView) || ((TextView) view).length() > 0)) {
                    return (View) view;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Object controller = Xp.getObjectField(clock, "mMiuiClockController");
            Object face = Xp.getObjectField(controller, "mClockView");
            Object source = Xp.getObjectField(face, "mCurrentDate");
            if (source instanceof View && isClockInfo((View) source)) return (View) source;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isClockInfo(View candidate) {
        if (clock == null || candidate == clock) return false;
        android.view.ViewParent parent = candidate.getParent();
        while (parent instanceof View && parent != clock) parent = ((View) parent).getParent();
        return parent == clock;
    }

    private static void syncEmptyText() {
        if (stack == null) return;
        if (emptyText == null) {
            View row = byId(stack, "empty_shade_view");
            if (row == null && stack instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) stack;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getClass().getName().endsWith(".EmptyShadeView")) {
                        row = child;
                        break;
                    }
                }
            }
            if (row != null) {
                try {
                    Object value = Xp.getObjectField(row, "mEmptyText");
                    if (value instanceof View) {
                        emptyText = (View) value;
                        emptyTextAlpha = emptyText.getAlpha();
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (emptyText != null && emptyText.getAlpha() != 0f) emptyText.setAlpha(0f);
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
        float gap = 10f * panel.getResources().getDisplayMetrics().density;
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
        float apiBottom = 0f;
        try {
            Object value = clock.getClass().getMethod("getClockBottom").invoke(clock);
            if (value instanceof Number) {
                float top = ((Number) value).floatValue();
                if (top > 0f && top < panel.getHeight()) apiBottom = top;
            }
        } catch (Throwable ignored) {}
        float bottom = visibleClockBottom(clock);
        // Some OEM clock templates report the notification baseline above the
        // rendered accessory/date views. Never let that baseline move cards over
        // the pixels that are actually visible on screen.
        bottom = Math.max(bottom, apiBottom);
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

    private static float visibleClockBottom(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return 0f;
        Rect bounds = new Rect();
        float bottom = 0f;
        if (view.getGlobalVisibleRect(bounds)) {
            int[] point = new int[2];
            panel.getLocationOnScreen(point);
            if (view != clock || view.getHeight() < panel.getHeight()) {
                bottom = bounds.bottom - point[1] - host.getTranslationY();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bottom = Math.max(bottom, visibleClockBottom(group.getChildAt(i)));
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
        if (notificationHeader != null) notificationHeader.setAlpha(notificationHeaderAlpha);
        notificationHeader = null;
        if (headerGradient != null) headerGradient.setVisibility(headerGradientVisibility);
        headerGradient = null;
        if (emptyText != null) emptyText.setAlpha(emptyTextAlpha);
        emptyText = null;
        if (clockInfo != null && clockInfoOffset != 0f) {
            clockInfo.setTranslationY(clockInfoBaseTranslation);
        }
        clockInfo = null;
        clockInfoOffset = 0f;
        clockInfoBaseTranslation = 0f;
        clockInfoRefreshMinute = -1L;
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
        if (clearButton != null) {
            clearButton.setVisibility(clearButtonVisibility);
            clearButton.setAlpha(clearButtonAlpha);
        }
        clearButton = null;
        host = null;
        panel = null;
        clock = null;
        depth = null;
        status = null;
        wallpaperSheet = null;
        lastBlur = -1;
        lastStatusBlur = -1;
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

    private static View findViewByClass(View root, String simpleName) {
        if (root == null) return null;
        if (root.getClass().getName().endsWith(simpleName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByClass(group.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
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
