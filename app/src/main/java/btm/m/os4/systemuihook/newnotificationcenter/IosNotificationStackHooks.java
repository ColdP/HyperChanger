package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Runs the OEM's keyguard notification layout rules only for this shade calculation. */
final class IosNotificationStackHooks {
    private static final ThreadLocal<Boolean> CALCULATING = new ThreadLocal<>();

    private IosNotificationStackHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> algorithm = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm", loader);
            Xp.hookAll(algorithm, "resetViewStates", chain -> {
                if (!IosNotificationCenterPresentation.isActive()) return chain.proceed();
                CALCULATING.set(true);
                try {
                    return chain.proceed();
                } finally {
                    CALCULATING.remove();
                }
            });
            Class<?> ambient = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.AmbientState", loader);
            Xp.hookAll(ambient, "isOnKeyguard", chain -> Boolean.TRUE.equals(CALCULATING.get())
                    ? Boolean.TRUE : chain.proceed());
            Xp.log("[IOSShade] native keyguard stack algorithm scoped to notification shade");
        } catch (Throwable error) {
            Xp.log("[IOSShade] keyguard stack algorithm unavailable: " + error);
        }
        try {
            Class<?> injector = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutControllerInjectorImpl",
                    loader);
            Xp.hookAll(injector, "updateStackingBottomForMiui", chain -> {
                Object result = chain.proceed();
                if (IosNotificationCenterPresentation.isActive() && !chain.getArgs().isEmpty()) {
                    Xp.setObjectField(chain.getThisObject(), "stackingBottom",
                            ((Number) chain.getArgs().get(0)).floatValue());
                }
                return result;
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] keyguard stack bottom unavailable: " + error);
        }
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
