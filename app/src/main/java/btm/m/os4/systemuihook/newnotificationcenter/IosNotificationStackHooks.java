package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Retains OEM notification stacking without changing the shade's keyguard state. */
final class IosNotificationStackHooks {
    private IosNotificationStackHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> stack = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader);
            Xp.hookAll(stack, "getScrollRange", chain -> {
                Object result = chain.proceed();
                if (!(chain.getThisObject() instanceof android.view.View) || !(result instanceof Number)) {
                    return result;
                }
                return ((Number) result).intValue() + IosNotificationCenterPresentation.extraScrollRange(
                        (android.view.View) chain.getThisObject());
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification scroll range unavailable: " + error);
        }
        try {
            Class<?> algorithm = Xp.findClass(
                    "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm", loader);
            Xp.hookAll(algorithm, "resetViewStates", chain -> {
                if (chain.getArgs().isEmpty()) return chain.proceed();
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
    }
}
