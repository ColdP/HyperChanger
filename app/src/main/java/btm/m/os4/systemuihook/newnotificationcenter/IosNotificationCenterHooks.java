package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.ShadeLayer;
import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Hooks the OEM's raw shade gesture and notification/control-center ownership. */
public final class IosNotificationCenterHooks {
    private IosNotificationCenterHooks() {}

    public static void install(ClassLoader loader) {
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
}
