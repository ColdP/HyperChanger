package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.ShadeLayer;
import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Hooks the OEM's raw shade gesture and notification/control-center ownership. */
public final class IosNotificationCenterHooks {
    private static float switchDownX;
    private static float switchDownY;
    private static boolean switchFromEmptyArea;
    private static boolean horizontalSwitchGesture;

    private IosNotificationCenterHooks() {}

    public static void install(ClassLoader loader) {
        IosNotificationStackHooks.install(loader);
        try {
            Class<?> shadeWindow = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowView", loader);
            Xp.hookAll(shadeWindow, "dispatchTouchEvent", chain -> {
                android.view.MotionEvent event = (android.view.MotionEvent) chain.getArgs().get(0);
                trackSwitchGesture((android.view.View) chain.getThisObject(), event);
                try {
                    return chain.proceed();
                } finally {
                    int action = event.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_CANCEL) {
                        switchFromEmptyArea = false;
                        horizontalSwitchGesture = false;
                    }
                }
            });
            Class<?> wrapper = Xp.findClass(
                    "com.miui.systemui.shade.NotificationShadeWrapper", loader);
            Xp.hookAll(wrapper, "getAllowParentInterceptSwitchEvent", chain -> {
                Object result = chain.proceed();
                return Boolean.TRUE.equals(result) || (IosNotificationCenterPresentation.isActive()
                        && horizontalSwitchGesture);
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] horizontal switch gesture unavailable: " + error);
        }
        try {
            Class<?> headerContainer = Xp.findClass(
                    "com.miui.systemui.shade.header.ShadeHeaderContainer", loader);
            Xp.hookAll(headerContainer, "dispatchTouchEvent", chain -> {
                if (!chain.getArgs().isEmpty()
                        && chain.getArgs().get(0) instanceof android.view.MotionEvent
                        && IosNotificationCenterPresentation.shouldPassHeaderTouch(
                                (android.view.MotionEvent) chain.getArgs().get(0))) return false;
                return chain.proceed();
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] header touch routing unavailable: " + error);
        }
        try {
            Class<?> headerView = Xp.findClass("com.android.systemui.qs.MiuiNotificationHeaderView", loader);
            Xp.hookAll(headerView, "onFinishInflate", chain -> {
                Object result = chain.proceed();
                IosNotificationCenterPresentation.captureHeader((android.view.View) chain.getThisObject());
                return result;
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification header inflate capture unavailable: " + error);
        }
        try {
            Class<?> header = Xp.findClass(
                    "com.android.systemui.controlcenter.shade.CombinedHeaderController", loader);
            Xp.hookAll(header, "getHeaderView", chain -> {
                Object result = chain.proceed();
                if (result instanceof android.view.View) {
                    IosNotificationCenterPresentation.captureHeader((android.view.View) result);
                }
                return result;
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] combined header capture unavailable: " + error);
        }
        try {
            Class<?> expand = Xp.findClass("com.android.systemui.shade.NotificationPanelExpandController", loader);
            Xp.hookAll(expand, "notifyExpandHeightChanged", chain -> {
                Object result = chain.proceed();
                java.util.List<Object> args = chain.getArgs();
                if (args.size() == 4 && ShadeLayer.iosEnabled()) {
                    Object tracking = Xp.callMethod(chain.getThisObject(), "getTracking");
                    boolean dragging = Boolean.TRUE.equals(Xp.callMethod(tracking, "getValue"));
                    ShadeLayer.onRawExpansion(((Number) args.get(0)).floatValue(),
                            ((Number) args.get(1)).floatValue(), dragging, Boolean.TRUE.equals(args.get(3)));
                }
                return result;
            });
            Xp.hookAll(expand, "setVisible$2", chain -> {
                Object result = chain.proceed();
                if (Boolean.FALSE.equals(chain.getArgs().get(0))) ShadeLayer.onNotificationHidden();
                return result;
            });
            ShadeLayer.onRawControllerAvailable();
            Xp.log("[IOSShade] raw notification height and visibility hooked");
        } catch (Throwable error) {
            Xp.log("[IOSShade] raw driver unavailable; using native expansion: " + error);
        }
        try {
            Class<?> switcher = Xp.findClass("com.miui.systemui.shade.ShadeSwitchControllerImpl", loader);
            io.github.libxposed.api.XposedInterface.Hooker switchProgress = chain -> {
                Object result = chain.proceed();
                Object controller = chain.getThisObject();
                Object animator = Xp.getObjectField(controller, "progressAnimator");
                ShadeLayer.onSwitchProgress(((Number) Xp.getObjectField(animator, "_progress")).floatValue(),
                        Boolean.TRUE.equals(Xp.getObjectField(controller, "switching")));
                return result;
            };
            Xp.hookAll(switcher, "performProgressChanged", switchProgress);
            Xp.hookAll(switcher, "setSwitching", switchProgress);
            ShadeLayer.onSwitchDriverAvailable();
        } catch (Throwable error) {
            Xp.log("[IOSShade] native switch progress unavailable: " + error);
        }
        try {
            Class<?> injector = Xp.findClass("com.android.systemui.shade.NotificationPanelViewControllerInjector", loader);
            Xp.hookAll(injector, "onControlCenterAppearChanged", chain -> {
                ShadeLayer.onControlCenterAppearance(Boolean.TRUE.equals(chain.getArgs().get(0)));
                return chain.proceed();
            });
            Xp.hookAll(injector, "onNotificationAppearChanged", chain -> {
                if (Boolean.TRUE.equals(chain.getArgs().get(0))) ShadeLayer.onNotificationAppearance();
                return chain.proceed();
            });
            Xp.log("[IOSShade] notification/control-centre ownership hooked");
        } catch (Throwable error) {
            Xp.log("[IOSShade] panel ownership hooks unavailable: " + error);
        }
    }

    private static void trackSwitchGesture(android.view.View view, android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            switchDownX = event.getRawX();
            switchDownY = event.getRawY();
            switchFromEmptyArea = IosNotificationCenterPresentation.isActive()
                    && !IosNotificationCenterPresentation.shouldPassHeaderTouch(event);
            horizontalSwitchGesture = false;
        } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                && switchFromEmptyArea && !horizontalSwitchGesture) {
            float dx = event.getRawX() - switchDownX;
            float dy = event.getRawY() - switchDownY;
            float slop = 24f * view.getResources().getDisplayMetrics().density;
            horizontalSwitchGesture = dx < -slop && -dx > Math.abs(dy) * 1.25f;
        }
    }
}
