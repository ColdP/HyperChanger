package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.Gravity;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View.OnClickListener;
import android.util.Base64;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

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
    private static long clockMetadataSyncUptime;
    private static ArrayList<View> hostedClockMinuteViews;
    private static ImageView depth;
    private static MirrorView status;
    private static View stack;
    private static View transformedStack;
    private static float stackBaseTranslationY;
    private static float stackBaseTranslationX;
    private static float stackBaseAlpha;
    private static ViewGroup.LayoutParams stackOriginalLayoutParams;
    private static int stackOriginalWidth;
    private static int stackOriginalHeight;
    private static int stackOriginalLeftMargin;
    private static int stackOriginalTopMargin;
    private static int stackOriginalRightMargin;
    private static int stackOriginalBottomMargin;
    private static boolean stackOriginalHasMargins;
    private static boolean landscapeLayout;
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
    private static final ViewTreeObserver.OnPreDrawListener CLEAR_SYNC = () -> {
        syncClearButton();
        return true;
    };
    private static final ViewTreeObserver.OnGlobalLayoutListener CLEAR_LAYOUT = () -> {
        clearButtonScanNeeded = true;
        // Clock plugins may populate or replace their minute subtree after initial inflation.
        hostedClockMinuteViews = null;
    };
    private static boolean clearButtonScanNeeded = true;
    private static long lastClearButtonScanUptime;
    private static Drawable lastDepthDrawable;
    private static ViewTreeObserver observer;
    private static ViewTreeObserver clearObserver;
    private static boolean clockUnavailable;
    private static View wallpaperSheet;
    private static boolean hideClearButton;
    private static int notificationBottomOffsetDp;
    private static int shortcutNotificationBottomOffsetDp;
    private static boolean notificationShortcutsEnabled;
    private static View clearButton;
    private static int clearButtonVisibility = View.VISIBLE;
    private static float clearButtonAlpha = 1f;
    private static boolean clearButtonCaptured;
    private static View capturedClearButton;
    private static final WeakHashMap<View, int[]> clearButtonStates = new WeakHashMap<>();
    private static Boolean keyguardLockedOverride;
    private static int lastBlur = -1;
    private static int lastStatusBlur = -1;
    private static boolean switching;
    private static boolean gestureActive;
    private static boolean wallpaperEnabled = true;
    private static ValueAnimator headerExitAnimator;
    private static ValueAnimator notificationHeaderVisibilityAnimator;
    private static boolean notificationHeaderHasContent;
    private static NotificationHeaderView notificationHeaderBar;
    private static boolean notificationHeaderEnabled;
    private static int notificationHeaderContentMode;
    private static String notificationHeaderContentText = "";
    private static String notificationHeaderContentIcon = "";
    private static String notificationHeaderBuiltinIcon = "";
    private static int notificationHeaderIconScale = 100;
    private static int notificationHeaderTextScale = 100;
    private static int notificationHeaderIconTextSpacing = 8;
    private static int notificationHeaderGapDp;
    private static OnClickListener nativeClearAction;
    private static View nativeDismissView;
    private static int notificationClearStyle;
    private static int notificationClearMaterial;
    private static float notificationClearRadius = 22f;
    private static int notificationClearPureColor = 0x66FFFFFF;
    private static int notificationClearAdvancedColor = Color.WHITE;
    private static int notificationClearAdvancedOpacity = 14;
    private static int notificationClearAdvancedBlur = 36;
    private static boolean notificationClearAdvancedHighlight;
    private static int notificationClearSoftColor = Color.WHITE;
    private static int notificationClearSoftOpacity = 10;
    private static int notificationClearSoftBackdropBlur = 80;
    private static int notificationClearSoftBlur = 36;
    private static float notificationClearSoftLuminance = .14f;
    private static int notificationClearSoftRefraction;

    private IosNotificationCenterPresentation() {}

    public static void attach(ViewGroup root) {
        if (window == root) {
            syncClearButton();
            return;
        }
        release();
        window = root;
        clearButtonScanNeeded = true;
        if (root != null) {
            clearObserver = root.getViewTreeObserver();
            clearObserver.addOnPreDrawListener(CLEAR_SYNC);
            clearObserver.addOnGlobalLayoutListener(CLEAR_LAYOUT);
        }
        clockUnavailable = false;
        syncClearButton();
    }

    public static void update(float fraction, float layoutFraction, boolean enabled, View sheet) {
        if (!btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosEnabled()) {
            onFeatureDisabled();
            return;
        }
        float previousProgress = progress;
        float previousLayout = layoutProgress;
        boolean wasHoldingRows = IosNotificationExitState.isHolding();
        progress = enabled ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        layoutProgress = enabled ? Math.max(0f, Math.min(1f, layoutFraction)) : 0f;
        IosNotificationExitState.updateProgress(previousProgress, progress);
        wallpaperSheet = sheet;
        if (progress <= 0f) {
            IosNotificationShortcutHost.restore();
            if (host != null) host.setAlpha(0f);
            // The native window hide is deferred until this endpoint. Restoring alpha here
            // would expose the list for a frame before that hide reaches the view pipeline.
            // Ownership lasts until restoreStackTransform(), including progress == 0.
            // The live panel predicate is already false at this endpoint.
            if (transformedStack != null) {
                transformedStack.setAlpha(0f);
            } else {
                restoreStackTransform();
            }
            IosNotificationStackHooks.restoreStackedRows();
            if (previousProgress > 0f) IosNotificationStackHooks.restoreExpandedRows(stack);
            // Only retain an already-owned header at the exit endpoint. A reset before
            // the first presentation must not acquire and hide the native header.
            if (host != null) {
                if (notificationHeader == null || oldHeader == null) findHeader();
                hideNativeHeader();
            }
            return;
        }
        // An interrupted header restore must not release a newly reopened presentation.
        if (headerExitAnimator != null) {
            ValueAnimator previousExit = headerExitAnimator;
            headerExitAnimator = null;
            previousExit.cancel();
        }
        if (host == null && (clockUnavailable || !create())) return;
        // The shade material owner is deliberately released as soon as the previous
        // notification panel closes. A fast re-open can reuse this hosted clock before the
        // exit animator has removed it, so re-register it on every active update instead of
        // relying on create() having run again. Without this, its containment flag remains
        // native and the MiGlass sampler sees the notification panel's contents.
        if (clock != null) IosShadeMaterial.registerClock(clock);
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
        if (notificationHeaderVisibilityAnimator != null) {
            notificationHeaderVisibilityAnimator.cancel();
            notificationHeaderVisibilityAnimator = null;
        }
        if (clearObserver != null && clearObserver.isAlive()) {
            clearObserver.removeOnPreDrawListener(CLEAR_SYNC);
            clearObserver.removeOnGlobalLayoutListener(CLEAR_LAYOUT);
        }
        clearObserver = null;
        releasePresentation();
        restoreNativeClearButtons();
        window = null;
    }

    public static void setGestureActive(boolean active) {
        gestureActive = active;
    }

    /** Restore native state immediately when disabled, retaining the window for re-enabling. */
    public static void onFeatureDisabled() {
        switching = false;
        gestureActive = false;
        progress = 0f;
        if (headerExitAnimator != null) {
            ValueAnimator animation = headerExitAnimator;
            headerExitAnimator = null;
            animation.cancel();
        }
        if (notificationHeaderVisibilityAnimator != null) {
            notificationHeaderVisibilityAnimator.cancel();
            notificationHeaderVisibilityAnimator = null;
        }
        releasePresentation();
    }

    public static void setWallpaperEnabled(boolean enabled) {
        if (wallpaperEnabled == enabled) return;
        wallpaperEnabled = enabled;
        if (depth != null) {
            depth.setVisibility(View.GONE);
            lastDepthDrawable = null;
        }
    }

    public static void setSwitching(boolean value) {
        if (!btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosEnabled()) {
            onFeatureDisabled();
            return;
        }
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
        if (!btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.iosEnabled()) {
            onFeatureDisabled();
            return;
        }
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
        if (notificationHeaderVisibilityAnimator != null) {
            notificationHeaderVisibilityAnimator.cancel();
            notificationHeaderVisibilityAnimator = null;
        }
        final View[] views = oldHeader;
        final float[] targets = oldHeaderAlpha;
        final View container = notificationHeader;
        final float containerTarget = notificationHeaderAlpha;
        final NotificationHeaderView customBar = notificationHeaderBar;
        final float customBarTarget = customBar == null ? 0f : customBar.getAlpha();
        restoreHeaderChromeVisibility();
        if (views == null && container == null && customBar == null) {
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
            if (customBar != null) {
                customBar.setAlpha(customBarTarget * (1f - amount));
                customBar.setRenderEffect(amount >= 0.999f ? null : RenderEffect.createBlurEffect(
                        radius * (1f - amount), radius * (1f - amount), Shader.TileMode.CLAMP));
            }
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
        if (hideClearButton == hide) return;
        hideClearButton = hide;
        clearButtonScanNeeded = true;
        syncClearButton();
    }

    public static void setNotificationHeaderSettings(boolean enabled, int style, int material,
            float radius, int pureColor, int advancedColor, int advancedOpacity, int advancedBlur,
            boolean advancedHighlight, int softColor, int softOpacity, int softBackdropBlur,
            int softBlur, float softLuminance, int softRefraction, int contentMode,
            String contentText, String contentIcon, String builtinIcon, int iconScale,
            int textScale, int iconTextSpacing) {
        notificationHeaderEnabled = enabled;
        notificationClearStyle = Math.max(0, Math.min(2, style));
        notificationClearMaterial = Math.max(0, Math.min(2, material));
        notificationClearRadius = Math.max(0f, Math.min(60f, radius));
        notificationClearPureColor = pureColor;
        notificationClearAdvancedColor = advancedColor;
        notificationClearAdvancedOpacity = Math.max(0, Math.min(100, advancedOpacity));
        notificationClearAdvancedBlur = Math.max(0, Math.min(120, advancedBlur));
        notificationClearAdvancedHighlight = advancedHighlight;
        notificationClearSoftColor = softColor;
        notificationClearSoftOpacity = Math.max(0, Math.min(100, softOpacity));
        notificationClearSoftBackdropBlur = Math.max(0, Math.min(120, softBackdropBlur));
        notificationClearSoftBlur = Math.max(0, Math.min(120, softBlur));
        notificationClearSoftLuminance = Math.max(0f, Math.min(.4f, softLuminance));
        notificationClearSoftRefraction = Math.max(-100, Math.min(100, softRefraction));
        notificationHeaderContentMode = Math.max(0, Math.min(6, contentMode));
        notificationHeaderContentText = contentText == null ? "" : contentText;
        notificationHeaderContentIcon = contentIcon == null ? "" : contentIcon;
        notificationHeaderBuiltinIcon = builtinIcon == null ? "" : builtinIcon;
        notificationHeaderIconScale = Math.max(-60, Math.min(200, iconScale));
        notificationHeaderTextScale = Math.max(-60, Math.min(200, textScale));
        notificationHeaderIconTextSpacing = Math.max(0, Math.min(48, iconTextSpacing));
        clearButtonScanNeeded = true;
        if (notificationHeaderBar != null) {
            if (!enabled) removeNotificationHeader();
            else {
                notificationHeaderBar.applySettings();
                syncNotificationHeader();
            }
        }
        syncClearButton();
    }

    static void captureNativeDismissController(Object controller) {
        try {
            Object candidate = Xp.getObjectField(controller, "dismissView");
            if (!(candidate instanceof View)) return;
            View view = (View) candidate;
            captureNativeClearAction(view);
            clearButtonScanNeeded = true;
            syncClearButton();
        } catch (Throwable error) {
            Xp.log(TAG + "native clear action capture unavailable: " + error);
        }
    }

    static void onKeyguardStateChanged(boolean locked) {
        if (keyguardLockedOverride != null && keyguardLockedOverride == locked) return;
        keyguardLockedOverride = locked;
        if (locked) IosNotificationShortcutHost.restore();
        clearButtonScanNeeded = true;
        syncClearButton();
    }

    private static void captureNativeClearAction(View view) {
        if (view == null) return;
        try {
            Object listenerInfo = Xp.getObjectField(view, "mListenerInfo");
            Object listener = listenerInfo == null ? null : Xp.getObjectField(listenerInfo, "mOnClickListener");
            if (listener instanceof OnClickListener) {
                nativeDismissView = view;
                nativeClearAction = (OnClickListener) listener;
                clearButton = view;
            }
        } catch (Throwable error) {
            Xp.log(TAG + "native clear action lookup unavailable: " + error);
        }
    }

    public static void setNotificationBottomOffset(int offsetDp) {
        int bounded = Math.max(-40, Math.min(120, offsetDp));
        if (notificationBottomOffsetDp == bounded) return;
        notificationBottomOffsetDp = bounded;
        restingNotificationY = Float.NaN;
        lastNotificationY = Float.NaN;
        requestStackUpdate(stack);
    }

    public static void setNotificationShortcuts(boolean enabled, int offsetDp) {
        int bounded = Math.max(-40, Math.min(120, offsetDp));
        if (notificationShortcutsEnabled == enabled && shortcutNotificationBottomOffsetDp == bounded) return;
        notificationShortcutsEnabled = enabled;
        shortcutNotificationBottomOffsetDp = bounded;
        restingNotificationY = Float.NaN;
        lastNotificationY = Float.NaN;
        syncShortcuts();
        requestStackUpdate(stack);
    }

    public static void setNotificationHeaderGap(int gapDp) {
        int bounded = Math.max(-40, Math.min(64, gapDp));
        if (notificationHeaderGapDp == bounded) return;
        notificationHeaderGapDp = bounded;
        if (notificationHeaderBar != null) syncNotificationHeader();
    }

    public static boolean isHostedClockView(View view) {
        if (clock == null || view == null) return false;
        for (View current = view; current != null; ) {
            if (current == clock) return true;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    static void captureStack(View view) {
        capturedStack = view;
        if (panel != null && stack == null && view.getRootView() == panel.getRootView()) {
            stack = view;
        }
    }

    static float stackOffset(View view, float nativeStackY) {
        if (!isActive() || view != stack || panel == null || stack.getHeight() == 0) return 0f;
        if (isLandscapePanel()) {
            return Math.round(24f * view.getResources().getDisplayMetrics().density);
        }
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
        // Shift the resting boundary as well as the origin so the OEM stacking calculator
        // and special media/focus rows use the same bottom, instead of cancelling the offset.
        int offsetDp = notificationShortcutsEnabled && !isLandscapePanel()
                ? shortcutNotificationBottomOffsetDp : notificationBottomOffsetDp;
        float offset = offsetDp * view.getResources().getDisplayMetrics().density;
        if (notificationShortcutsEnabled && !isLandscapePanel()) {
            // The shortcut slider is an adjustment around the native shortcut area. Keep that
            // area reserved, then apply the signed user offset on top of it.
            offset += IosNotificationShortcutHost.bottomSpace(panel);
        }
        if (isLandscapePanel()) {
            // Landscape navigation bars are usually on the side. Reserve only a small
            // rounded-card margin so the last card is not cut at the bottom edge.
            inset = 8f * view.getResources().getDisplayMetrics().density;
        }
        return Math.max(0f, view.getHeight() - inset - offset);
    }

    /** Converts the panel-fixed bottom edge into the translated list's local coordinates. */
    static float viewportBottomForDraw(View view) {
        float bottom = viewportBottom(view);
        if (view == transformedStack) {
            bottom -= view.getTranslationY() - stackBaseTranslationY;
        }
        return Math.max(0f, bottom);
    }

    private static boolean isLandscapePanel() {
        return panel != null && panel.getWidth() > panel.getHeight();
    }

    static float panelStackAlpha(View view, float nativeAlpha) {
        // Native collapse/blur updates can write alpha after the sheet has reached zero.
        // Keep those writes hidden until the transform is actually handed back.
        if (view != transformedStack || host == null) {
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
            ensureNotificationHeader();
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
        }
        if (oldHeader == null) findHeader();
        syncStackTransform();
        syncLandscapeLayout();
        ensureHostedClockMinuteVisible();
        syncLandscapeClockHeight();
        if (sheet != null && sheet.isAttachedToWindow()) {
            ViewOutlineProvider provider = landscapeLayout
                    ? LANDSCAPE_CLOCK_OUTLINE : sheet.getOutlineProvider();
            if (host.getOutlineProvider() != provider) {
                host.setOutlineProvider(provider);
                host.invalidateOutline();
            } else if (landscapeLayout) {
                // The sheet's reveal outline changes without resizing the left clock column.
                host.invalidateOutline();
            }
        }
        hideNativeHeader();
        syncDepth();
        syncStatus();
        syncClearButton();
        syncShortcuts();
        syncEmptyText();
        // The OEM clock's depth layout may hide minute_view when its clockDepthType is a
        // lockscreen-only region. This hosted copy has no depth foreground layer, so that state
        // would make the minute half disappear until the next clock rebuild. Re-assert the
        // minute subtree after every layout/pre-draw pass while the iOS clock owns the surface.
        // Ease the clock from a soft, blurred entrance to a crisp resting state. Using the
        // same smooth-step curve as the sheet alpha avoids a sharp focus jump near the end of
        // an upward swipe when native expansion reports uneven frame deltas.
        float clockVisual = progress * progress * (3f - 2f * progress);
        int blur = Math.round(28f * panel.getResources().getDisplayMetrics().density
                * (1f - clockVisual));
        // RenderEffect changes rebuild a RenderNode. Two-pixel buckets avoid doing that for
        // subpixel finger deltas while keeping the transition visually smooth.
        blur = (blur + 1) / 2 * 2;
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
        statusBlur = (statusBlur + 1) / 2 * 2;
        if (status != null && statusBlur != lastStatusBlur) {
            status.setRenderEffect(statusBlur == 0 ? null : RenderEffect.createBlurEffect(
                    statusBlur, statusBlur, Shader.TileMode.CLAMP));
            lastStatusBlur = statusBlur;
        }
        syncNotificationHeader();
        syncNotifications();
        syncClockInfo();
    }

    private static void hideNativeHeader() {
        if (notificationHeader != null && notificationHeader.getAlpha() != 0f) {
            notificationHeader.setAlpha(0f);
        }
        if (wallpaperEnabled) hideHeaderGradient();
        else restoreHeaderChromeVisibility();
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
        if (wallpaperEnabled) hideHeaderGradient();
        else restoreHeaderChromeVisibility();
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
        if (!wallpaperEnabled || (panel != null && panel.getWidth() > panel.getHeight())) {
            // The portrait presentation uses MIUI's deducted subject layer for depth. A
            // landscape split has no meaningful subject-to-clock composition, so hide it and
            // let the cropped/blurred wallpaper provide a quiet backdrop behind both columns.
            if (depth != null && depth.getVisibility() != View.GONE) depth.setVisibility(View.GONE);
            lastDepthDrawable = null;
            return;
        }
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
        boolean keyguardLocked = isKeyguardLocked();
        if (!notificationHeaderEnabled && !hideClearButton && !keyguardLocked) {
            if (!clearButtonStates.isEmpty()) restoreNativeClearButtons();
            clearButtonScanNeeded = false;
            return;
        }
        if (notificationHeaderBar != null) notificationHeaderBar.setClearEnabled(true);
        long now = SystemClock.uptimeMillis();
        boolean retryMissing = clearButtonStates.isEmpty()
                && now - lastClearButtonScanUptime >= 1000L;
        if (clearButtonScanNeeded || retryMissing) {
            lastClearButtonScanUptime = now;
            ArrayList<View> candidates = new ArrayList<>();
            collectClearButtons(window, candidates);
            if (nativeDismissView != null && nativeDismissView.getRootView() == window.getRootView()
                    && !candidates.contains(nativeDismissView)) candidates.add(nativeDismissView);
            if (clearButton != null && clearButton.getRootView() == window.getRootView()
                    && !candidates.contains(clearButton)) candidates.add(clearButton);
            if (!candidates.isEmpty()) clearButton = candidates.get(0);
            for (View candidate : candidates) {
                int[] state = clearButtonStates.get(candidate);
                if (state == null) {
                    state = new int[]{candidate.getVisibility(), Float.floatToIntBits(candidate.getAlpha())};
                    clearButtonStates.put(candidate, state);
                }
                captureNativeClearAction(candidate);
            }
            clearButtonScanNeeded = false;
        }
        for (java.util.Map.Entry<View, int[]> entry : clearButtonStates.entrySet()) {
            View candidate = entry.getKey();
            int[] state = entry.getValue();
            if (candidate == null || state == null || candidate.getRootView() != window.getRootView()) continue;
            candidate.setVisibility(View.GONE);
            candidate.setAlpha(Float.intBitsToFloat(state[1]));
        }
    }

    private static void restoreNativeClearButtons() {
        for (java.util.Map.Entry<View, int[]> entry : new ArrayList<>(clearButtonStates.entrySet())) {
            View view = entry.getKey();
            int[] state = entry.getValue();
            if (view == null || state == null) continue;
            view.setVisibility(state[0]);
            view.setAlpha(Float.intBitsToFloat(state[1]));
        }
        clearButtonStates.clear();
        clearButtonCaptured = false;
        capturedClearButton = null;
        clearButton = null;
    }

    private static void collectClearButtons(View view, ArrayList<View> output) {
        if (view == null) return;
        String name = view.getClass().getName();
        boolean candidate = name.endsWith(".NotificationDismissView")
                || name.endsWith(".DismissView")
                || name.endsWith(".CircleAndTickAnimView");
        int id = view.getId();
        if (!candidate && id != View.NO_ID) {
            try {
                String entry = view.getResources().getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT);
                candidate = entry.contains("dismiss_view") || entry.contains("clear_all")
                        || entry.equals("notification_dismiss_view");
            } catch (Throwable ignored) {}
        }
        if (candidate && !output.contains(view)) output.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectClearButtons(group.getChildAt(i), output);
        }
    }

    private static boolean isKeyguardLocked() {
        return keyguardLockedOverride != null
                ? keyguardLockedOverride
                : btm.m.os4.systemuihook.hypermusiccover.ShadeLayer.keyguardLocked();
    }

    private static void findClearButton() {
        if (window == null) return;
        if (clearButton != null && clearButton.getRootView() == window.getRootView()) {
            captureNativeClearAction(clearButton);
            return;
        }
        String[] ids = {"notification_dismiss_view", "clear_all", "clear_all_button",
                "notification_clear_all", "dismiss_button"};
        for (String id : ids) {
            clearButton = byId(window, id);
            if (clearButton != null) break;
        }
        if (clearButton == null) clearButton = findViewByClass(window, "NotificationDismissView");
        if (clearButton == null) clearButton = findViewByClass(window, "DismissView");
        if (clearButton == null) clearButton = findViewByClass(window, "CircleAndTickAnimView");
        captureNativeClearAction(clearButton);
    }

    private static void ensureNotificationHeader() {
        if (!notificationHeaderEnabled || panel == null) {
            removeNotificationHeader();
            return;
        }
        if (notificationHeaderBar != null && notificationHeaderBar.getParent() == panel) return;
        removeNotificationHeader();
        notificationHeaderBar = new NotificationHeaderView(panel.getContext());
        notificationHeaderBar.applySettings();
        notificationHeaderBar.setAlpha(0f);
        notificationHeaderBar.setClickable(false);
        notificationHeaderBar.setClearEnabled(true);
        panel.addView(notificationHeaderBar, new FrameLayout.LayoutParams(-1, dp(52)));
    }

    private static void removeNotificationHeader() {
        if (notificationHeaderVisibilityAnimator != null) {
            notificationHeaderVisibilityAnimator.cancel();
            notificationHeaderVisibilityAnimator = null;
        }
        if (notificationHeaderBar != null && notificationHeaderBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) notificationHeaderBar.getParent()).removeView(notificationHeaderBar);
        }
        notificationHeaderBar = null;
        notificationHeaderHasContent = false;
    }

    private static void syncNotificationHeader() {
        if (panel == null || stack == null || !notificationHeaderEnabled) {
            removeNotificationHeader();
            return;
        }
        ensureNotificationHeader();
        if (notificationHeaderBar == null || stack.getHeight() == 0) return;
        int[] panelPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        int width = landscapeLayout ? landscapeStackWidth(stack) : ViewGroup.LayoutParams.MATCH_PARENT;
        ViewGroup.LayoutParams params = notificationHeaderBar.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            notificationHeaderBar.setLayoutParams(params);
        }
        float x = 0f;
        if (landscapeLayout) {
            int[] stackPoint = new int[2];
            stack.getLocationInWindow(stackPoint);
            x = stackPoint[0] - panelPoint[0] - notificationHeaderBar.getLeft();
        }
        if (notificationHeaderBar.getTranslationX() != x) notificationHeaderBar.setTranslationX(x);
        // Use the first real row as the anchor. The stack itself starts at the panel's
        // viewport origin on some HyperOS builds, which placed the bar at screen top when
        // its top padding was used directly. The row location already includes NSSL scroll.
        float firstTop = firstNotificationTop(stack, panelPoint);
        if (Float.isNaN(firstTop)) {
            animateNotificationHeader(false, 0f);
            return;
        }
        float y = firstTop - dp(60 + notificationHeaderGapDp);
        notificationHeaderBar.setTranslationY(y);
        animateNotificationHeader(true, progress * progress * (3f - 2f * progress));
        float visual = progress * progress * (3f - 2f * progress);
        float blur = 28f * panel.getResources().getDisplayMetrics().density * (1f - visual);
        notificationHeaderBar.setRenderEffect(blur <= 0f ? null
                : RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
    }

    private static void animateNotificationHeader(boolean show, float targetAlpha) {
        if (notificationHeaderBar == null) return;
        if (notificationHeaderHasContent == show) {
            if (notificationHeaderVisibilityAnimator == null) {
                notificationHeaderBar.setAlpha(show ? targetAlpha : 0f);
            }
            return;
        }
        notificationHeaderHasContent = show;
        if (notificationHeaderVisibilityAnimator != null) notificationHeaderVisibilityAnimator.cancel();
        float from = notificationHeaderBar.getAlpha();
        float to = show ? targetAlpha : 0f;
        notificationHeaderVisibilityAnimator = ValueAnimator.ofFloat(from, to);
        notificationHeaderVisibilityAnimator.setDuration(180L);
        notificationHeaderVisibilityAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        notificationHeaderVisibilityAnimator.addUpdateListener(a -> notificationHeaderBar.setAlpha((Float) a.getAnimatedValue()));
        notificationHeaderVisibilityAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (notificationHeaderVisibilityAnimator == animation) notificationHeaderVisibilityAnimator = null;
            }
        });
        notificationHeaderVisibilityAnimator.start();
    }

    private static int dp(int value) {
        if (panel == null) return value;
        return Math.round(value * panel.getResources().getDisplayMetrics().density);
    }

    private static void performNativeClear() {
        findClearButton();
        if (window != null && nativeClearAction != null && nativeDismissView != null
                && nativeDismissView.getRootView() == window.getRootView()) {
            try {
                nativeClearAction.onClick(nativeDismissView);
            } catch (Throwable error) {
                Xp.log(TAG + "native clear action unavailable: " + error);
            }
        }
        // The captured view listener can return without clearing while the custom bar owns
        // the visible control. Always drive the live stack as well when it is available.
        if (stack != null) {
            try {
                Xp.callMethod(stack, "clearNotifications", 0, true, true);
                return;
            } catch (Throwable error) {
                Xp.log(TAG + "notification stack clear unavailable: " + error);
            }
        }
        if (clearButton != null) {
            try {
                clearButton.performClick();
            } catch (Throwable error) {
                Xp.log(TAG + "clear-all view click unavailable: " + error);
            }
        }
    }

    private static void syncLandscapeClockHeight() {
        if (clock == null || panel == null || clock.getHeight() <= 0) return;
        if (!landscapeLayout) {
            clock.setPivotY(clock.getHeight() / 2f);
            clock.setScaleY(1f);
            clock.setScaleX(1f);
            return;
        }
        float contentBottom = 0f;
        try {
            Object value = clock.getClass().getMethod("getClockBottom").invoke(clock);
            if (value instanceof Number) contentBottom = ((Number) value).floatValue();
        } catch (Throwable ignored) {}
        if (contentBottom <= 0f || Float.isNaN(contentBottom) || Float.isInfinite(contentBottom))
            contentBottom = largestVisibleContentBottom(clock, clock);
        if (contentBottom <= 0f) contentBottom = clock.getHeight();
        // Leave a real safety margin: the clock can report its nominal bounds before the
        // child text has finished measuring, so fitting exactly to the column still clips a
        // glyph or its shadow on the next frame.
        float inset = dp(16);
        float available = Math.max(1f, panel.getHeight() - inset * 2f);
        float contentRight = largestVisibleContentRight(clock, clock);
        if (contentRight <= 0f) contentRight = clock.getWidth();
        float availableWidth = Math.max(1f, host == null
                ? clock.getWidth() - inset * 2f : host.getWidth() - inset * 2f);
        // Keep the aspect ratio intact. Independent X/Y fitting makes large clock glyphs look
        // compressed and still leaves the other axis vulnerable when the layout settles.
        float fitScale = Math.min(1f, Math.min(available / contentBottom,
                availableWidth / contentRight));
        clock.setPivotY(0f);
        clock.setPivotX(0f);
        if (Math.abs(clock.getScaleY() - fitScale) > .001f) clock.setScaleY(fitScale);
        if (Math.abs(clock.getScaleX() - fitScale) > .001f) clock.setScaleX(fitScale);
    }

    private static float largestVisibleContentBottom(View root, View view) {
        if (root == null || view == null || view.getVisibility() != View.VISIBLE) return 0f;
        if (!(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0) {
            if (view.getHeight() <= 0) return 0f;
            int[] rootPoint = new int[2];
            int[] viewPoint = new int[2];
            root.getLocationInWindow(rootPoint);
            view.getLocationInWindow(viewPoint);
            return viewPoint[1] - rootPoint[1] + view.getHeight();
        }
        float bottom = 0f;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            bottom = Math.max(bottom, largestVisibleContentBottom(root, group.getChildAt(i)));
        }
        return bottom;
    }

    private static float largestVisibleContentRight(View root, View view) {
        if (root == null || view == null || view.getVisibility() != View.VISIBLE) return 0f;
        if (!(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0) {
            if (view.getWidth() <= 0) return 0f;
            int[] rootPoint = new int[2];
            int[] viewPoint = new int[2];
            root.getLocationInWindow(rootPoint);
            view.getLocationInWindow(viewPoint);
            return viewPoint[0] - rootPoint[0] + view.getWidth();
        }
        float right = 0f;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            right = Math.max(right, largestVisibleContentRight(root, group.getChildAt(i)));
        }
        return right;
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
        long now = SystemClock.uptimeMillis();
        if (clockMetadataSyncUptime == 0L || now - clockMetadataSyncUptime >= 250L) {
            syncClockMetadata();
            clockMetadataSyncUptime = now;
        }
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
        // In landscape the clock is an independent left-hand column. Keep the OEM
        // notification metadata visible, but do not move the date upward or let the
        // notification-driven clock transition shrink the large clock.
        if (landscapeLayout) {
            if (clockInfoOffset != 0f) {
                clockInfoOffset = 0f;
                clockInfo.setTranslationY(clockInfoBaseTranslation);
            }
            return;
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

    /**
     * Keep the minute half of the hosted clock visible. HyperOS reuses the same clock templates
     * for the lockscreen depth composition and may call updateDepthContentVisibility() after the
     * clock has been attached, which sets minute_view to GONE for the depth region. The iOS
     * notification center deliberately has no depth clock layer, so that lockscreen decision is
     * invalid here. Resolve by resource entry name as clock-library ids are not always in the
     * SystemUI resource table.
     */
    private static void ensureHostedClockMinuteVisible() {
        if (clock == null || host == null || progress <= 0f) return;
        if (clock.getVisibility() != View.VISIBLE) clock.setVisibility(View.VISIBLE);
        if (hostedClockMinuteViews == null || hasDetachedClockMinuteView()) {
            hostedClockMinuteViews = new ArrayList<>();
            String[] names = {"minute_view", "minute1", "minute2", "time_minute",
                    "time_minute_right", "current_time_minute_style1", "time_below_view_minute1",
                    "time_below_view_minute2", "tv_minute", "hour_view", "hour1", "hour2",
                    "time_hour", "time_hour_left", "time_hour_right",
                    "current_time_hour_style1", "tv_hour"};
            for (String name : names) {
                View minute = findDescendantByEntryName(clock, name);
                if (minute != null && !hostedClockMinuteViews.contains(minute)) {
                    hostedClockMinuteViews.add(minute);
                }
            }
        }
        for (View minute : hostedClockMinuteViews) {
            for (View current = minute; current != null; ) {
                if (current.getVisibility() != View.VISIBLE) current.setVisibility(View.VISIBLE);
                if (current == clock) break;
                android.view.ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        }
    }

    private static boolean hasDetachedClockMinuteView() {
        for (View minute : hostedClockMinuteViews) {
            if (!isClockInfo(minute)) return true;
        }
        return false;
    }

    private static View findDescendantByEntryName(View root, String name) {
        if (root == null) return null;
        try {
            if (root.getId() != View.NO_ID
                    && name.equals(root.getResources().getResourceEntryName(root.getId()))) {
                return root;
            }
        } catch (Throwable ignored) {
            // Resource ids from the clock library may not resolve in the SystemUI table.
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findDescendantByEntryName(group.getChildAt(i), name);
            if (found != null) return found;
        }
        return null;
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

    /** Width used by the native list's measurement, before it measures its notification rows. */
    static int landscapeStackWidth(View view) {
        if (!ownsStack(view) || panel == null) return 0;
        return landscapeStackWidth(panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }

    private static int landscapeStackWidth(int width, int height) {
        if (width <= height || height <= 0) return 0;
        float density = panel.getResources().getDisplayMetrics().density;
        int rightWidth = width - Math.round(width * .42f) - Math.round(18f * density)
                - Math.round(16f * density);
        return rightWidth > Math.round(220f * density) ? rightWidth : 0;
    }

    private static final ViewOutlineProvider LANDSCAPE_CLOCK_OUTLINE = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            View sheet = wallpaperSheet;
            if (sheet == null || !sheet.isAttachedToWindow() || sheet.getOutlineProvider() == null) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
                return;
            }
            // Evaluate with the full sheet width, so its right corners stay at the screen edge.
            sheet.getOutlineProvider().getOutline(sheet, outline);
            int[] sheetPoint = new int[2];
            int[] clockPoint = new int[2];
            sheet.getLocationInWindow(sheetPoint);
            view.getLocationInWindow(clockPoint);
            outline.offset(sheetPoint[0] - clockPoint[0], sheetPoint[1] - clockPoint[1]);
        }
    };

    /**
     * Give the clock and notifications separate columns. Restore the native layout when the
     * display returns to portrait or the presentation is released.
     */
    private static void syncLandscapeLayout() {
        if (panel == null || host == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) return;
        int rightWidth = landscapeStackWidth(panel.getWidth(), panel.getHeight());
        boolean wantLandscape = rightWidth > 0;
        if (!wantLandscape) {
            if (landscapeLayout) restoreLandscapeStackLayout();
            ViewGroup.LayoutParams hostParams = host.getLayoutParams();
            if (hostParams != null && hostParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                hostParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                hostParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                host.setLayoutParams(hostParams);
            }
            host.setClipChildren(true);
            return;
        }

        float density = panel.getResources().getDisplayMetrics().density;
        int outer = Math.round(16f * density);
        int divider = Math.round(18f * density);
        int leftWidth = Math.round(panel.getWidth() * 0.42f);
        int rightLeft = leftWidth + divider;

        if (!landscapeLayout) {
            landscapeLayout = true;
            if (stack != null) {
                ViewGroup.LayoutParams params = stack.getLayoutParams();
                if (params != null) {
                    stackOriginalLayoutParams = params;
                    stackOriginalWidth = params.width;
                    stackOriginalHeight = params.height;
                    stackOriginalHasMargins = params instanceof ViewGroup.MarginLayoutParams;
                    if (stackOriginalHasMargins) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                        stackOriginalLeftMargin = margins.leftMargin;
                        stackOriginalTopMargin = margins.topMargin;
                        stackOriginalRightMargin = margins.rightMargin;
                        stackOriginalBottomMargin = margins.bottomMargin;
                    }
                }
            }
        }

        ViewGroup.LayoutParams hostParams = host.getLayoutParams();
        if (hostParams != null && hostParams.width != leftWidth) {
            hostParams.width = leftWidth;
            hostParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            host.setLayoutParams(hostParams);
        }
        host.setClipChildren(true);
        host.setClipToPadding(true);

        if (stack != null) {
            ViewGroup.LayoutParams params = stack.getLayoutParams();
            if (params != null) {
                boolean changed = params.width != rightWidth;
                params.width = rightWidth;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    changed |= margins.leftMargin != rightLeft || margins.rightMargin != outer;
                    margins.leftMargin = rightLeft;
                    margins.rightMargin = outer;
                }
                if (changed) stack.setLayoutParams(params);
            }
            // NSSL can live in a nested ConstraintLayout whose start/end constraints override
            // absolute margins. Align its actual window position with the panel's right column.
            int[] panelPoint = new int[2];
            int[] stackPoint = new int[2];
            panel.getLocationInWindow(panelPoint);
            stack.getLocationInWindow(stackPoint);
            float deltaX = panelPoint[0] + rightLeft - stackPoint[0];
            if (Math.abs(deltaX) > .5f) stack.setTranslationX(stack.getTranslationX() + deltaX);
        }
    }

    private static void restoreLandscapeStackLayout() {
        if (!landscapeLayout) return;
        if (stack != null) {
            ViewGroup.LayoutParams params = stackOriginalLayoutParams;
            if (params != null) {
                params.width = stackOriginalWidth;
                params.height = stackOriginalHeight;
                if (stackOriginalHasMargins && params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    margins.leftMargin = stackOriginalLeftMargin;
                    margins.topMargin = stackOriginalTopMargin;
                    margins.rightMargin = stackOriginalRightMargin;
                    margins.bottomMargin = stackOriginalBottomMargin;
                }
                stack.setLayoutParams(params);
            }
            stack.setTranslationX(stackBaseTranslationX);
        }
        stackOriginalLayoutParams = null;
        landscapeLayout = false;
    }

    private static void syncStackTransform() {
        if (stack == null || host == null) return;
        if (transformedStack != stack) {
            restoreStackTransform();
            transformedStack = stack;
            stackBaseTranslationY = stack.getTranslationY();
            stackBaseTranslationX = stack.getTranslationX();
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
        original.setTranslationX(stackBaseTranslationX);
        original.setAlpha(stackBaseAlpha);
        original.setRenderEffect(null);
        lastStackBlur = -1;
    }

    private static void syncNotifications() {
        View currentStack = stack;
        if (currentStack == null || currentStack.getHeight() == 0 || clock == null) return;
        if (landscapeLayout) return;
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
        // Apply the header edge after native resting/scroll clamping, otherwise that
        // clamp replaces our edge on the next frame and the clock only sees the row.
        if (notificationHeaderEnabled && notificationHeaderBar != null
                && notificationHeaderHasContent) {
            int[] headerPoint = new int[2];
            notificationHeaderBar.getLocationInWindow(headerPoint);
            float headerTop = headerPoint[1] - panelPoint[1] - host.getTranslationY();
            notificationY = Math.min(notificationY, headerTop);
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
        IosNotificationShortcutHost.restore();
        IosNotificationExitState.clear();
        restoreStackTransform();
        restoreLandscapeStackLayout();
        IosNotificationStackHooks.restoreStackedRows();
        IosNotificationStackHooks.restoreExpandedRows(stack);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(FRAME);
        observer = null;
        if (oldHeader != null) {
            for (int i = 0; i < oldHeader.length; i++) {
                if (oldHeader[i] != null) {
                    oldHeader[i].setAlpha(oldHeaderAlpha[i]);
                    oldHeader[i].setRenderEffect(null);
                }
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
        clockMetadataSyncUptime = 0L;
        hostedClockMinuteViews = null;
        stack = null;
        stackOriginalLayoutParams = null;
        landscapeLayout = false;
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
        boolean keyguardLocked = isKeyguardLocked();
        if (clearButton != null) {
            if (keyguardLocked) {
                clearButton.setVisibility(View.GONE);
                clearButton.setAlpha(clearButtonAlpha);
            } else {
                clearButton.setVisibility(clearButtonVisibility);
                clearButton.setAlpha(clearButtonAlpha);
            }
        }
        if (!keyguardLocked) {
            clearButton = null;
            clearButtonCaptured = false;
            capturedClearButton = null;
        }
        removeNotificationHeader();
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

    static void syncShortcuts() {
        IosNotificationShortcutHost.update(panel, wallpaperSheet, progress,
                notificationShortcutsEnabled && panel != null
                        && panel.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE
                        && isActive() && !isKeyguardLocked());
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
            // Status bar data changes slowly. A one-second self-redraw kept SystemUI busy even
            // when the shade was idle; two seconds keeps the mirror current at a much lower cost.
            if (isAttachedToWindow()) postInvalidateDelayed(2000L);
        }
    }

    private static final class NotificationHeaderView extends FrameLayout {
        private final TextView title;
        private final ImageView leftIcon;
        private final ClearButtonView clear;
        private final View glassSurface;
        private final GradientDrawable background = new GradientDrawable();

        NotificationHeaderView(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            title = new TextView(context);
            title.setText(localized(context, "通知中心", "Notification Center", "通知センター"));
            title.setTextColor(Color.WHITE);
            title.setTextSize(20f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            title.setPadding(Math.round(24.5f * getResources().getDisplayMetrics().density), 0, dp(12), 0);
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            addView(title, new FrameLayout.LayoutParams(-2, -1, Gravity.START));
            leftIcon = new ImageView(context);
            leftIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            leftIcon.setVisibility(View.GONE);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            iconParams.leftMargin = dp(24);
            addView(leftIcon, iconParams);

            glassSurface = new View(context);
            glassSurface.setClickable(false);
            glassSurface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            glassSurface.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyNativeMaterial(view);
                }
            });
            FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(dpScaled(84), dpScaled(40),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            surfaceParams.rightMargin = dp(23);
            addView(glassSurface, surfaceParams);

            clear = new ClearButtonView(context);
            clear.setOnClickListener(v -> performNativeClear());
            clear.setContentDescription(localized(context, "清除", "Clear", "消去"));
            setOnTouchListener((view, event) -> {
                if (!isInsideClear(event.getX(), event.getY())) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) return true;
                if (action == MotionEvent.ACTION_UP) {
                    performNativeClear();
                }
                return true;
            });
            FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(dp(84), dpScaled(40),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            clearParams.rightMargin = dp(23);
            addView(clear, clearParams);
            setClearEnabled(false);
        }

        private boolean isInsideClear(float x, float y) {
            return x >= clear.getLeft() && x < clear.getRight()
                    && y >= clear.getTop() && y < clear.getBottom();
        }

        void setClearEnabled(boolean enabled) {
            clear.setEnabled(enabled);
            clear.setAlpha(enabled ? 1f : .45f);
        }

        void applySettings() {
            applyLeftContent();
            clear.applyStyle(notificationClearStyle,
                    localized(getContext(), "清除", "Clear", "消去"), 13.5f * .92f);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clear.getLayoutParams();
            // Keep the transparent click target centered on the visible glass surface. The
            // surface can be resized independently, while its right edge stays fixed.
            int glassHeight = notificationTextHeight();
            int glassWidth;
            if (notificationClearStyle == 1) {
                // Keep the icon-only control circular while enlarging its radius to 118%.
                glassHeight = Math.round(glassHeight * 1.18f);
                glassWidth = glassHeight;
            } else {
                glassWidth = Math.round(glassHeight * clearButtonAspectRatio() * 1.22f);
            }
            params.width = glassWidth;
            params.height = dpScaled(40);
            params.rightMargin = dp(23);
            clear.setLayoutParams(params);
            FrameLayout.LayoutParams surfaceParams = (FrameLayout.LayoutParams) glassSurface.getLayoutParams();
            surfaceParams.height = glassHeight;
            surfaceParams.width = glassWidth;
            surfaceParams.rightMargin = params.rightMargin;
            glassSurface.setLayoutParams(surfaceParams);
            float radiusScale = notificationClearStyle == 1 ? 1.18f : 1f;
            background.setCornerRadius(dpFloat(notificationClearRadius * .92f * radiusScale));
            // HyperOS's backdrop renderer needs a drawable source, but the source must be
            // almost transparent so it can sample the wallpaper behind this control.
            int color = notificationClearMaterial == 2
                    ? notificationClearPureColor : Color.argb(1, 255, 255, 255);
            background.setColor(color);
            if (notificationClearAdvancedHighlight && notificationClearMaterial == 1) {
                background.setStroke(dp(1), Color.argb(90, 255, 255, 255));
            } else {
                background.setStroke(0, Color.TRANSPARENT);
            }
            glassSurface.setBackground(background);
            clear.setBackgroundColor(Color.TRANSPARENT);
            applyNativeMaterial(glassSurface);
        }

        private void applyLeftContent() {
            title.setText(localized(getContext(), "通知中心", "Notification Center", "通知センター"));
            title.setVisibility(View.VISIBLE);
            leftIcon.setVisibility(View.GONE);
            title.setTextSize(20f);
            title.setPadding(Math.round(24.5f * getResources().getDisplayMetrics().density), 0, dp(12), 0);
            FrameLayout.LayoutParams defaultIconParams = (FrameLayout.LayoutParams) leftIcon.getLayoutParams();
            defaultIconParams.width = dp(24);
            defaultIconParams.height = dp(24);
            defaultIconParams.leftMargin = dp(24);
            leftIcon.setLayoutParams(defaultIconParams);
            if (notificationHeaderContentMode == 1) {
                title.setVisibility(View.GONE);
                return;
            }
            boolean composite = notificationHeaderContentMode == 5 || notificationHeaderContentMode == 6;
            if (notificationHeaderContentMode == 2 || notificationHeaderContentMode == 4 || composite) {
                try {
                    boolean customIcon = notificationHeaderContentMode == 4 || notificationHeaderContentMode == 6;
                    String iconData = !customIcon
                            ? notificationHeaderBuiltinIcon : notificationHeaderContentIcon;
                    if (iconData.isEmpty()) iconData = notificationHeaderBuiltinIcon;
                    byte[] bytes = Base64.decode(iconData, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        leftIcon.setImageBitmap(bitmap);
                        leftIcon.setVisibility(View.VISIBLE);
                        if (!composite) {
                            title.setVisibility(View.GONE);
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (composite) {
                if (notificationHeaderContentMode == 6 && !notificationHeaderContentText.trim().isEmpty()) {
                    title.setText(notificationHeaderContentText.trim());
                }
                title.setTextSize(20f * (100 + notificationHeaderTextScale) / 100f);
                int iconSize = dp(Math.round(24f * (100 + notificationHeaderIconScale) / 100f));
                FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) leftIcon.getLayoutParams();
                iconParams.width = iconSize;
                iconParams.height = iconSize;
                iconParams.leftMargin = dp(24);
                leftIcon.setLayoutParams(iconParams);
                title.setPadding(dp(24) + iconSize + dp(notificationHeaderIconTextSpacing), 0, dp(12), 0);
            } else if (notificationHeaderContentMode == 3 && !notificationHeaderContentText.trim().isEmpty()) {
                title.setText(notificationHeaderContentText.trim());
            }
        }

        private void applyNativeMaterial(View view) {
            boolean glass = notificationClearMaterial != 2;
            optional(view, "clearMiBackgroundBlendColor");
            optional(view, "setPassWindowBlurEnabled", glass);
            optional(view, "setMiViewBlurMode", glass ? 1 : 0);
            optional(view, "setMiBackgroundBlurMode", glass ? 1 : 0);
            optional(view, "setMiBackgroundBlurRadius", notificationClearMaterial == 0
                    ? notificationClearSoftBackdropBlur : notificationClearAdvancedBlur);
            if (glass) {
                int blur = notificationClearSoftBlur;
                if (notificationClearMaterial == 0) {
                    optional(view, "setMiGlassBlurRadius", blur, Math.min(500, blur * 2));
                } else if (notificationClearAdvancedHighlight) {
                    optional(view, "setMiBloomStroke", new float[]{
                            3f, 180f, 1f, 1f, 1f, .05f, 8f, .5f, .5f, -.5f, 1f,
                            1f, 1f, .6f, .5f, .95f, -.5f, 1f, 1f, 1f, .35f,
                    });
                }
                int tint = notificationClearMaterial == 0 ? notificationClearSoftColor : notificationClearAdvancedColor;
                int opacity = notificationClearMaterial == 0
                        ? notificationClearSoftOpacity : notificationClearAdvancedOpacity;
                optional(view, "clearMiBackgroundBlendColor");
                optional(view, "addMiBackgroundBlendColor", withOpacity(tint, opacity), 101);
                if (notificationClearMaterial == 0) {
                applySystemGlassMaterial(view, blur, notificationClearSoftLuminance,
                        notificationClearSoftRefraction);
                }
            }
        }

        /** Same MiGlassCompat path used by lockscreen shortcut soft-glass backgrounds. */
        private void applySystemGlassMaterial(View view, int blurRadius, float luminance, int refraction) {
            try {
                Class<?> compat = null;
                ClassLoader[] loaders = {
                        view.getContext().getClassLoader(),
                        view.getClass().getClassLoader(),
                        Thread.currentThread().getContextClassLoader(),
                };
                for (ClassLoader loader : loaders) {
                    if (loader == null) continue;
                    try {
                        compat = Class.forName("com.miui.systemui.util.MiGlassCompat", false, loader);
                        break;
                    } catch (Throwable ignored) {}
                }
                if (compat == null) throw new ClassNotFoundException("MiGlassCompat");
                int smallBlur = Math.max(0, Math.min(100, blurRadius));
                float[] parameters = {
                        .67f, .16f, .09f, 0f, Math.max(0f, Math.min(.4f, luminance)), 1.4f,
                        -.02f, .3f, .6f, 1f, .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f,
                        72f, 3.8f, 80f, 800f, 1.2f, 1f, -.4f, .6f, -.8f, 1.4f, .7f,
                        .8f, 1.15f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                };
                parameters[32] += Math.max(-100, Math.min(100, refraction)) / 100f;
                compat.getMethod("setMiGlassBlurRadius", View.class, int.class, int.class)
                        .invoke(null, view, smallBlur, Math.min(500, smallBlur * 2));
                compat.getMethod("setMiViewMaterialTypeCompat", int.class, View.class)
                        .invoke(null, 1, view);
                try {
                    compat.getMethod("setMiGlassSdfMaxSizeCompat", View.class, float.class, float.class)
                            .invoke(null, view, (float) view.getWidth(), (float) view.getHeight());
                } catch (Throwable ignored) {}
                compat.getMethod("setMiGlassCompat", View.class, float[].class)
                        .invoke(null, view, parameters);
            } catch (Throwable error) {
                Xp.log(TAG + "clear button MiGlass unavailable: " + error);
            }
        }

        private void optional(View view, String method, Object... args) {
            try {
                Xp.callMethod(view, method, args);
            } catch (Throwable ignored) {
                // HyperOS blur APIs vary by build; keep the drawable fallback for missing calls.
            }
        }

        private int withOpacity(int color, int opacity) {
            return Color.argb(Math.round(255f * opacity / 100f), Color.red(color), Color.green(color), Color.blue(color));
        }

        private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
        private int dpScaled(int value) { return Math.round(value * getResources().getDisplayMetrics().density * .92f); }
        private float dpFloat(float value) { return value * getResources().getDisplayMetrics().density; }

        /** Height of the visible title glyphs, used to size the independent glass pill. */
        private int notificationTextHeight() {
            android.graphics.Paint.FontMetrics metrics = title.getPaint().getFontMetrics();
            float measured = metrics.descent - metrics.ascent;
            return Math.max(dp(1), Math.round(measured > 0f ? measured : dp(20)));
        }

        private float clearButtonAspectRatio() {
            return notificationClearStyle == 1 ? 40f / 40f
                    : (notificationClearStyle == 2 ? 68f / 40f : 84f / 40f);
        }

        private String localized(Context context, String zh, String en, String ja) {
            String language = context.getResources().getConfiguration().getLocales().isEmpty()
                    ? "zh" : context.getResources().getConfiguration().getLocales().get(0).getLanguage();
            return "en".equals(language) ? en : ("ja".equals(language) ? ja : zh);
        }
    }

    /** Centers the icon and label as one group, including icon-only and text-only styles. */
    private static final class ClearButtonView extends android.widget.LinearLayout {
        private final CloseIconView icon;
        private final TextView label;

        ClearButtonView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClipChildren(false);
            icon = new CloseIconView(context);
            label = new TextView(context);
            label.setTextColor(Color.WHITE);
            label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            addView(icon, new LayoutParams(1, 1));
            addView(label, new LayoutParams(-2, -1));
        }

        void applyStyle(int style, String text, float textSizeSp) {
            label.setText(text);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp);
            boolean iconOnly = style == 1;
            boolean textOnly = style == 2;
            icon.setVisibility(textOnly ? GONE : VISIBLE);
            label.setVisibility(iconOnly ? GONE : VISIBLE);
            int iconSize = Math.max(1, Math.round(label.getTextSize() * .95f));
            icon.setIconSize(iconSize);
            LayoutParams iconParams = (LayoutParams) icon.getLayoutParams();
            iconParams.width = iconSize;
            iconParams.height = iconSize;
            iconParams.rightMargin = textOnly || iconOnly ? 0 : Math.round(iconSize * .28f);
            icon.setLayoutParams(iconParams);
        }
    }

    /** Close glyph based on the supplied Close.svg path, sized from the clear text's font. */
    private static final class CloseIconView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int iconSize = 1;

        CloseIconView(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        void setIconSize(int size) {
            iconSize = Math.max(1, size);
            paint.setStrokeWidth(iconSize * .14f);
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(iconSize, iconSize);
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = iconSize * .14f;
            canvas.drawLine(pad, pad, iconSize - pad, iconSize - pad, paint);
            canvas.drawLine(iconSize - pad, pad, pad, iconSize - pad, paint);
        }
    }

    private static final class DisplayHost extends FrameLayout {
        DisplayHost(Context context) { super(context); }

        @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }
    }

}
