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
                IosNotificationCenterPresentation.captureStack(view);
                float nativeTop = ((Number) chain.getArgs().get(0)).floatValue();
                float adjusted = IosNotificationCenterPresentation.adjustTopPadding(view, nativeTop);
                if (adjusted == nativeTop) return chain.proceed();
                return chain.proceed(new Object[]{adjusted, chain.getArgs().get(1)});
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification bounds padding unavailable: " + error);
        }
        try {
            Class<?> algorithm = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm", loader);
            Xp.hookAll(algorithm, "resetViewStates", chain -> {
                Object ambient = chain.getArgs().get(0);
                Object host = Xp.getObjectField(chain.getThisObject(), "mHostView");
                if (!(host instanceof android.view.View)) return chain.proceed();
                float original = ((Number) Xp.getObjectField(ambient, "mStackY")).floatValue();
                float offset = IosNotificationCenterPresentation.stackOffset((android.view.View) host, original);
                if (offset <= .5f) return chain.proceed();
                Xp.setObjectField(ambient, "mStackY", original + offset);
                try {
                    return chain.proceed();
                } finally {
                    Xp.setObjectField(ambient, "mStackY", original);
                }
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification stack position unavailable: " + error);
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
