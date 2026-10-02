package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Retains OEM notification stacking without changing the shade's keyguard state. */
final class IosNotificationStackHooks {
    private IosNotificationStackHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> interactor = Xp.findClass(
                    "com.miui.systemui.notification.domain.interactor.NotificationStackingInteractor", loader);
            Xp.hookAll(interactor, "getKeyguardTwoNotifFirstLineOffset", chain -> {
                if (IosNotificationCenterPresentation.isActive()
                        && !Boolean.TRUE.equals(chain.getArgs().get(0))
                        && ((Number) Xp.getObjectField(chain.getThisObject(), "visibleNotifCount")).intValue() == 2) {
                    return ((Number) Xp.getObjectField(chain.getThisObject(), "stackingHeight2")).floatValue();
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] two-notification stacking rule unavailable: " + error);
        }
    }
}
