package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Retains OEM notification stacking without changing the shade's keyguard state. */
final class IosNotificationStackHooks {
    private IosNotificationStackHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            Xp.hookAll(stack, "updateTopPadding", chain -> {
                if (chain.getArgs().size() != 2 || !(chain.getThisObject() instanceof android.view.View)) {
                    return chain.proceed();
                }
                android.view.View view = (android.view.View) chain.getThisObject();
                float nativeTop = ((Number) chain.getArgs().get(0)).floatValue();
                float adjusted = IosNotificationCenterPresentation.adjustTopPadding(view, nativeTop);
                if (adjusted == nativeTop) return chain.proceed();
                return chain.proceed(new Object[]{adjusted, chain.getArgs().get(1)});
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification bounds padding unavailable: " + error);
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
