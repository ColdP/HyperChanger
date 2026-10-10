package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
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
    private static View clockAnimation;
    private static float clockInfoOffset;
    private static float clockInfoBaseTranslation;
    private static long clockInfoRefreshMinute = -1L;
    private static ImageView depth;
    private static MirrorView status;
    private static View stack;
    private static View transformedStack;
    private static float stackBaseTranslationY;
    private static float stackBaseAlpha;
    private static int lastStackBlur = -1;
    private static View capturedStack;
    private static View emptyText;
    private static float emptyTextAlpha;
    private static View capturedHeader;
    private static View notificationHeader;
    private static float notificationHeaderAlpha = 1f;
    private static View headerGradient;
    private static int headerGradientVisibility = View.VISIBLE;
    private static View headerForeground;
    private static int headerForegroundVisibility = View.VISIBLE;
    private static View headerShadow;
    private static int headerShadowVisibility = View.VISIBLE;
    private static View[] oldHeader;
    private static float[] oldHeaderAlpha;
    private static float progress;
    private static float layoutProgress;
    private static float lastNotificationY = Float.NaN;
    private static float restingNotificationY = Float.NaN;
    private static final ThreadLocal<Boolean> DRIVING_HOSTED_CLOCK = new ThreadLocal<>();
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
    private static boolean switching;
    private static boolean gestureActive;
    private static ValueAnimator headerExitAnimator;

    private IosNotificationCenterPresentation() {}

    public static void attach(ViewGroup root) {
        if (window == root) return;
        release();
        window = root;
        clockUnavailable = false;
    }

    public static void update(float fraction, float layoutFraction, boolean enabled, View sheet) {
        float previousProgress = progress;
        float previousLayout = layoutProgress;
        boolean wasHoldingRows = IosNotificationExitState.isHolding();
        progress = enabled ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        layoutProgress = enabled ? Math.max(0f, Math.min(1f, layoutFraction)) : 0f;
        IosNotificationExitState.updateProgress(previousProgress, progress);
        wallpaperSheet = sheet;
        if (progress <= 0f) {
            if (host != null) host.setAlpha(0f);
            // The native window hide is deferred until this endpoint. Restoring alpha here
            // would expose the list for a frame before that hide reaches the view pipeline.
            if (transformedStack != null &&
                    btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosNotificationPanelEnabled()) {
                transformedStack.setAlpha(0f);
            } else {
                restoreStackTransform();
            }
            IosNotificationStackHooks.restoreStackedRows();
            if (previousProgress > 0f) IosNotificationStackHooks.restoreExpandedRows(stack);
            if (notificationHeader == null || oldHeader == null) findHeader();
            hideNativeHeader();
            return;
        }
        // An interrupted header restore must not release a newly reopened presentation.
        if (headerExitAnimator != null) {
            ValueAnimator previousExit = headerExitAnimator;
            headerExitAnimator = null;
            previousExit.cancel();
        }
        if (host == null && (clockUnavailable || !create())) return;
        host.setAlpha(progress);
        if (status != null) status.setAlpha(progress * progress * (3f - 2f * progress));
        sync();
        if (!IosNotificationExitState.isHolding()
                && (layoutProgress != previousLayout || wasHoldingRows)) {
            requestStackUpdate(stack);
        }
    }

    public static void release() {
        switching = false;
        gestureActive = false;
        if (headerExitAnimator != null) headerExitAnimator.cancel();
        releasePresentation();
        window = null;
    }

    public static void setGestureActive(boolean active) {
        gestureActive = active;
    }

    public static void setSwitching(boolean value) {
        switching = value;
        if (value) {
            if (notificationHeader == null || oldHeader == null) findHeader();
            hideNativeHeader();
        } else if (progress <= 0f) {
            finishPresentation();
        } else {
            sync();
        }
    }

    public static boolean isActive() {
        return host != null && progress > 0f;
    }

    static boolean ownsStack(View view) {
        return view != null && isActive() && view == stack
                && btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosNotificationPanelEnabled();
    }

    static boolean ownsRow(View view) {
        if (!ownsStack(stack) || view == null) return false;
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (parent == stack) return true;
        }
        return false;
    }

    public static boolean isHostedClock(View view) {
        return view != null && view == clock;
    }

    public static boolean isDrivingHostedClock() {
        return Boolean.TRUE.equals(DRIVING_HOSTED_CLOCK.get());
    }

    /** Called once the native panel reports that the notification center is actually hidden. */
    public static void finishPresentation() {
        switching = false;
        gestureActive = false;
        if (oldHeader == null && notificationHeader == null) {
            releasePresentation();
            return;
        }
        if (headerExitAnimator != null) {
            ValueAnimator previousExit = headerExitAnimator;
            headerExitAnimator = null;
            previousExit.cancel();
        }
        final View[] views = oldHeader;
        final float[] targets = oldHeaderAlpha;
        final View container = notificationHeader;
        final float containerTarget = notificationHeaderAlpha;
        restoreHeaderChromeVisibility();
        if (views == null && container == null) {
            releasePresentation();
            return;
        }
        final float radius = panel == null ? 18f
                : 18f * panel.getResources().getDisplayMetrics().density;
        headerExitAnimator = ValueAnimator.ofFloat(0f, 1f);
        headerExitAnimator.setDuration(180L);
        headerExitAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        headerExitAnimator.addUpdateListener(animation -> {
            float amount = (Float) animation.getAnimatedValue();
            if (container != null) container.setAlpha(containerTarget * amount);
            for (int i = 0; views != null && i < views.length; i++) {
                View view = views[i];
                if (view == null) continue;
                view.setAlpha((targets == null ? 1f : targets[i]) * amount);
                view.setRenderEffect(amount >= 0.999f ? null : RenderEffect.createBlurEffect(
                        radius * (1f - amount), radius * (1f - amount), Shader.TileMode.CLAMP));
            }
        });
        headerExitAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (headerExitAnimator != animation) return;
                headerExitAnimator = null;
                releasePresentation();
            }
        });
        headerExitAnimator.start();
    }

    public static void setHideClearButton(boolean hide) {
        hideClearButton = hide;
        syncClearButton();
    }

    static void captureStack(View view) {
        capturedStack = view;
        if (panel != null && stack == null && view.getRootView() == panel.getRootView()) {
            stack = view;
        }
    }

    static float stackOffset(View view, float nativeStackY) {
        if (!isActive() || view != stack || panel == null || stack.getHeight() == 0) return 0f;
        float reserve = IosNotificationStackHooks.restingRowsExtent(view);
        if (reserve <= 0f) reserve = 160f * panel.getResources().getDisplayMetrics().density;
        // Anchor the first full card and the OEM stack peeks above the bottom safe area.
        // Full-height focus/media cards are included in the reservation.
        float top = Math.max(nativeStackY, viewportBottom(view) - reserve);
        return Math.max(0f, top - nativeStackY);
    }

    /** Stable local viewport; native container bounds shrink ahead of the hosted exit. */
    static float viewportBottom(View view) {
        float inset = 16f * view.getResources().getDisplayMetrics().density;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets != null) inset = Math.max(inset,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom);
        return Math.max(0f, view.getHeight() - inset);
    }

    static float panelStackAlpha(View view, float nativeAlpha) {
        if (view != transformedStack || host == null ||
                !btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosNotificationPanelEnabled()) {
            return nativeAlpha;
        }
        return host.getAlpha();
    }

    static int extraScrollRange(View view) {
        float clearance = stackClearance(view);
        if (clearance <= .5f) return 0;
        try {
            float contentHeight = ((Number) Xp.getObjectField(view, "mContentHeight")).floatValue();
            float bottom = viewportBottom(view);
            float addedOverflow = Math.max(0f, contentHeight + clearance - bottom)
                    - Math.max(0f, contentHeight - bottom);
            return Math.max(0, (int) Math.ceil(Math.min(clearance, addedOverflow)));
        } catch (Throwable ignored) {
            return Math.max(0, (int) Math.ceil(clearance));
        }
    }

    private static float stackClearance(View view) {
        if (!isActive() || view != stack) return 0f;
        try {
            float nativeTop = ((Number) view.getClass().getMethod("getTopPadding").invoke(view)).floatValue();
            return stackOffset(view, nativeTop);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    static boolean shouldPassHeaderTouch(MotionEvent event) {
        if (!isActive() || stack == null || event == null
                || event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        try {
            Object row = stack.getClass().getMethod("getChildAtRawPosition", float.class, float.class)
                    .invoke(stack, event.getRawX(), event.getRawY());
            return row instanceof View && ((View) row).getVisibility() == View.VISIBLE;
        } catch (Throwable ignored) {
            return false;
        }
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
            container.setEnabled(false);
            container.setFocusable(false);
            container.setFocusableInTouchMode(false);
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            allowBackdropSampling(container);
            allowBackdropSampling(nativeClock);
            nativeClock.setClickable(false);
            nativeClock.setFocusable(false);
            nativeClock.setFocusableInTouchMode(false);
            nativeClock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            container.addView(nativeClock, new FrameLayout.LayoutParams(-1, -1));
            ImageView foreground = new ImageView(context);
            foreground.setScaleType(ImageView.ScaleType.FIT_XY);
            foreground.setClickable(false);
            foreground.setEnabled(false);
            foreground.setFocusable(false);
            foreground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            container.addView(foreground, new FrameLayout.LayoutParams(-1, -1));
            MirrorView statusMirror = new MirrorView(context);
            statusMirror.setClickable(false);
            statusMirror.setFocusable(false);
            statusMirror.setEnabled(false);
            statusMirror.setFocusableInTouchMode(false);
            statusMirror.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            clock = nativeClock;
            target.addView(container, 0, new FrameLayout.LayoutParams(-1, -1));
            target.addView(statusMirror, 1, new FrameLayout.LayoutParams(-1,
                    Math.round(80f * context.getResources().getDisplayMetrics().density)));
            panel = target;
            host = container;
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
        IosNotificationStackHooks.enforceUnstackedRows(stack);
        IosNotificationExitState.apply(stack);
        IosNotificationStackHooks.clipStackedRows(stack, ownsStack(stack));
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
        syncStackTransform();
        hideNativeHeader();
        syncDepth();
        syncStatus();
        syncClearButton();
        syncEmptyText();
        // Ease the clock from a soft, blurred entrance to a crisp resting state. Using the
        // same smooth-step curve as the sheet alpha avoids a sharp focus jump near the end of
        // an upward swipe when native expansion reports uneven frame deltas.
        float clockVisual = progress * progress * (3f - 2f * progress);
        int blur = Math.round(28f * panel.getResources().getDisplayMetrics().density
                * (1f - clockVisual));
        if (blur != lastBlur) {
            host.setRenderEffect(blur == 0 ? null : RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.CLAMP));
            lastBlur = blur;
        }
        if (stack != null && blur != lastStackBlur) {
            stack.setRenderEffect(blur == 0 ? null : RenderEffect.createBlurEffect(
                    blur, blur, Shader.TileMode.CLAMP));
            lastStackBlur = blur;
        }
        int statusBlur = Math.round(12f * panel.getResources().getDisplayMetrics().density * (1f - progress));
        if (status != null && statusBlur != lastStatusBlur) {
            status.setRenderEffect(statusBlur == 0 ? null : RenderEffect.createBlurEffect(
                    statusBlur, statusBlur, Shader.TileMode.CLAMP));
            lastStatusBlur = statusBlur;
        }
        syncNotifications();
        syncClockInfo();
    }

    private static void hideNativeHeader() {
        if (notificationHeader != null && notificationHeader.getAlpha() != 0f) {
            notificationHeader.setAlpha(0f);
        }
        hideHeaderGradient();
        for (int i = 0; oldHeader != null && i < oldHeader.length; i++) {
            View original = oldHeader[i];
            if (original != null && original.getAlpha() != 0f) {
                oldHeaderAlpha[i] = original.getAlpha();
                original.setAlpha(0f);
            }
        }
    }

    private static void restoreHeaderChromeVisibility() {
        if (headerGradient != null) headerGradient.setVisibility(headerGradientVisibility);
        if (headerForeground != null) headerForeground.setVisibility(headerForegroundVisibility);
        if (headerShadow != null) headerShadow.setVisibility(headerShadowVisibility);
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
        if (headerForeground == null) {
            headerForeground = byId(window, "header_foreground");
            if (headerForeground != null) headerForegroundVisibility = headerForeground.getVisibility();
        }
        if (headerForeground != null) headerForeground.setVisibility(View.GONE);
        if (headerShadow == null) {
            View headerContainer = byId(window, "shade_header_container");
            headerShadow = byId(headerContainer, "shadow");
            if (headerShadow == null) headerShadow = byId(notificationHeader, "shadow");
            if (headerShadow != null) headerShadowVisibility = headerShadow.getVisibility();
        }
        if (headerShadow != null) headerShadow.setVisibility(View.GONE);
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
            String[] ids = {"notification_dismiss_view", "clear_all", "clear_all_button",
                    "notification_clear_all", "dismiss_button"};
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
            clockAnimation = null;
            clockInfoOffset = 0f;
        }
        if (clockInfo == null) {
            clockInfo = findClockInfo();
            if (clockInfo != null) {
                clockInfoBaseTranslation = clockInfo.getTranslationY();
                clockAnimation = findClockAnimation(clockInfo);
            }
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
        if (clockAnimation != null) {
            boolean insideAnimation = false;
            for (android.view.ViewParent parent = clockInfo.getParent(); parent instanceof View;
                    parent = ((View) parent).getParent()) {
                if (parent == clockAnimation) {
                    insideAnimation = true;
                    break;
                }
            }
            float offset = insideAnimation ? 0f : clockAnimation.getTranslationY();
            ViewGroup.LayoutParams params = clockInfo.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                // The OEM updates the date margin with the VF clock rect before layout catches up.
                offset += ((ViewGroup.MarginLayoutParams) params).topMargin - clockInfo.getTop();
            }
            if (Math.abs(offset - clockInfoOffset) > .5f) {
                clockInfoOffset = offset;
                clockInfo.setTranslationY(clockInfoBaseTranslation + offset);
            }
            return;
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

    private static void syncStackTransform() {
        if (stack == null || host == null) return;
        if (transformedStack != stack) {
            restoreStackTransform();
            transformedStack = stack;
            stackBaseTranslationY = stack.getTranslationY();
            stackBaseAlpha = stack.getAlpha();
        }
        float y = stackBaseTranslationY + host.getTranslationY();
        if (stack.getTranslationY() != y) stack.setTranslationY(y);
        float alpha = host.getAlpha();
        if (stack.getAlpha() != alpha) stack.setAlpha(alpha);
    }

    private static void restoreStackTransform() {
        if (transformedStack == null) return;
        View original = transformedStack;
        transformedStack = null;
        original.setTranslationY(stackBaseTranslationY);
        original.setAlpha(stackBaseAlpha);
        original.setRenderEffect(null);
        lastStackBlur = -1;
    }

    private static void syncNotifications() {
        View currentStack = stack;
        if (currentStack == null || currentStack.getHeight() == 0 || clock == null) return;
        // The exiting cards retain their last drawn positions. Keep the clock's matching
        // notification baseline too, until a cancelled exit reaches the open endpoint.
        // Native padding/scroll geometry below no longer describes those retained cards.
        if (IosNotificationExitState.isHolding() && !Float.isNaN(lastNotificationY)) return;
        int[] panelPoint = new int[2];
        int[] stackPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        currentStack.getLocationInWindow(stackPoint);
        float nativeTop = 0f;
        try { nativeTop = ((Number) Xp.callMethod(currentStack, "getTopPadding")).floatValue(); }
        catch (Throwable ignored) {}
        float notificationY = stackPoint[1] - panelPoint[1]
                + nativeTop + stackOffset(currentStack, nativeTop) - host.getTranslationY();
        if (layoutProgress >= .95f && progress >= .95f) {
            if (Float.isNaN(restingNotificationY)) restingNotificationY = notificationY;
            int scrollY = 0;
            try {
                scrollY = ((Number) currentStack.getClass().getMethod("getOwnScrollY")
                        .invoke(currentStack)).intValue();
            } catch (Throwable ignored) {}
            if (scrollY > 0) {
                float firstTop = firstNotificationTop(currentStack, panelPoint);
                if (!Float.isNaN(firstTop)) firstTop -= host.getTranslationY();
                if (!Float.isNaN(firstTop)) {
                    float lower = restingNotificationY - scrollY
                            - 16f * panel.getResources().getDisplayMetrics().density;
                    notificationY = Math.min(restingNotificationY, Math.max(lower, firstTop));
                }
            } else {
                notificationY = restingNotificationY;
            }
        }
        if (Float.isNaN(lastNotificationY) || Math.abs(notificationY - lastNotificationY) > 1f) {
            try {
                Class<?> kind = Class.forName(
                        "com.miui.systemui.notification.data.repository.NotificationTopChangeType",
                        false, clock.getClass().getClassLoader());
                Object changed = kind.getField("NOTIFS_CHANGED").get(null);
                Method method = clock.getClass().getMethod("notifStateChange", float.class, boolean.class, kind);
                DRIVING_HOSTED_CLOCK.set(true);
                try {
                    method.invoke(clock, notificationY, true, changed);
                    lastNotificationY = notificationY;
                } finally {
                    DRIVING_HOSTED_CLOCK.remove();
                }
            } catch (Throwable ignored) {
                // Other OEM builds may use a different notification change type.
            }
        }
    }

    private static View findClockAnimation(View info) {
        try {
            Object controller = Xp.getObjectField(clock, "mMiuiClockController");
            Object face = Xp.getObjectField(controller, "mClockView");
            Object top = face.getClass().getMethod("getNotificationClockTopView").invoke(face);
            if (top != info) return null;
            Object animation = face.getClass().getMethod("getAnimationContainer").invoke(face);
            if (animation instanceof View && isClockInfo((View) animation)) {
                return (View) animation;
            }
        } catch (Throwable ignored) {
            // Other clock families keep their existing placement.
        }
        return null;
    }

    /** Returns the top of the first real notification in panel coordinates, including scroll. */
    private static float firstNotificationTop(View currentStack, int[] panelPoint) {
        if (!(currentStack instanceof ViewGroup)) return Float.NaN;
        ViewGroup group = (ViewGroup) currentStack;
        float top = Float.NaN;
        int[] point = new int[2];
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getName();
            if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0
                    || (!name.endsWith(".ExpandableNotificationRow")
                    && !name.endsWith(".MiuiMediaHeaderView"))) continue;
            child.getLocationInWindow(point);
            float childTop = point[1] - panelPoint[1];
            if (Float.isNaN(top) || childTop < top) top = childTop;
        }
        return top;
    }

    private static void releasePresentation() {
        IosNotificationExitState.clear();
        restoreStackTransform();
        IosNotificationStackHooks.restoreStackedRows();
        IosNotificationStackHooks.restoreExpandedRows(stack);
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
        if (headerForeground != null) headerForeground.setVisibility(headerForegroundVisibility);
        headerForeground = null;
        if (headerShadow != null) headerShadow.setVisibility(headerShadowVisibility);
        headerShadow = null;
        if (emptyText != null) emptyText.setAlpha(emptyTextAlpha);
        emptyText = null;
        if (clockInfo != null && clockInfoOffset != 0f) {
            clockInfo.setTranslationY(clockInfoBaseTranslation);
        }
        clockInfo = null;
        clockAnimation = null;
        clockInfoOffset = 0f;
        clockInfoBaseTranslation = 0f;
        clockInfoRefreshMinute = -1L;
        stack = null;
        layoutProgress = 0f;
        lastNotificationY = Float.NaN;
        restingNotificationY = Float.NaN;
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
