package btm.m.os4.systemuihook.newnotificationcenter;

import android.util.SparseArray;
import android.view.View;

import java.lang.reflect.Method;

import btm.m.os4.systemuihook.hypermusiccover.ShadeLayer;
import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Replaces only the OEM panel entrance/exit transform with the hosted sheet motion. */
final class IosNotificationAnimationHooks {
    private IosNotificationAnimationHooks() {}

    private static boolean handlesPanel(Object owner) {
        if (!ShadeLayer.iosNotificationPanelEnabled()) return false;
        try {
            Object ambient = Xp.getObjectField(owner, "ambientState");
            return !Boolean.TRUE.equals(Xp.callMethod(ambient, "isOnKeyguard"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void neutralizePanelTransform(Object row) {
        try {
            Object injector = Xp.callMethod(row, "getInjector");
            if (!handlesPanel(injector)) return;
            Object ambient = Xp.getObjectField(injector, "ambientState");
            if (!IosNotificationCenterPresentation.isActive()
                    && !Boolean.TRUE.equals(Xp.getObjectField(ambient, "panelVisible"))) return;
            Object state = Xp.callMethod(row, "getViewState");
            Object stateInjector = Xp.getObjectField(state, "mInjector");
            Object map = Xp.getObjectField(stateInjector, "folmeStateMap");
            if (!(map instanceof SparseArray)) return;
            Object panel = ((SparseArray<?>) map).get(11018);
            if (panel == null) return;
            // 11018 aggregates the panel animation. Dismissal, heads-up and row changes
            // use separate entries and must keep their original values/lifecycle.
            Xp.setObjectField(panel, "alpha", 1f);
            Xp.setObjectField(panel, "scaleX", 1f);
            Xp.setObjectField(panel, "scaleY", 1f);
            Xp.setObjectField(panel, "translationY", 0f);
            Object animator = Xp.getObjectField(injector, "animator");
            float slideX = animator == null ? 0f
                    : ((Number) Xp.getObjectField(animator, "slideTransX")).floatValue();
            Xp.setObjectField(panel, "translationX", slideX);
        } catch (Throwable ignored) {
            // Unsupported OEM versions retain their native properties.
        }
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> controller = Xp.findClass(
                    "com.android.systemui.shade.MiuiNotificationPanelAnimControllerImpl", loader);
            Xp.hookAll(controller, "startPanelExpandAnimation$default", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 5 && handlesPanel(args[0])) {
                    args[3] = false;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Xp.hookAll(controller, "startPanelVisibleAnimation", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 3 && handlesPanel(chain.getThisObject())) {
                    // Retain native pipeline updates and the non-animated cleanup path.
                    // No animator begins, so no completion counter can remain pending.
                    args[1] = false;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            // Prevent compiled callers from bypassing the per-row hooks below.
            deoptimize(controller, "startPanelExpandAnimation$default", "startPanelVisibleAnimation");
        } catch (Throwable error) {
            Xp.log("[IOSShade] panel animation controller unavailable: " + error);
        }
        try {
            Class<?> animator = Xp.findClass(
                    "com.android.systemui.shade.MiuiPanelAnimValueObjectImpl", loader);
            Xp.hookAll(animator, "changeExpand", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 9 && handlesPanel(chain.getThisObject())) {
                    args[0] = false;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Xp.hookAll(animator, "changeVisible", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 7 && handlesPanel(chain.getThisObject())) {
                    args[3] = false;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Xp.hookAll(animator, "scheduleUpdate", chain -> {
                Object result = chain.proceed();
                if (handlesPanel(chain.getThisObject())) {
                    neutralizePanelTransform(Xp.getObjectField(chain.getThisObject(), "view"));
                }
                return result;
            });
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification panel animator unavailable: " + error);
        }
        try {
            Class<?> row = Xp.findClass(
                    "com.miui.systemui.notification.row.ExpandableViewBase", loader);
            Xp.hookAll(row, "onPropertiesUpdate", chain -> {
                if (chain.getThisObject() instanceof View) neutralizePanelTransform(chain.getThisObject());
                Object origin = chain.getThisObject() instanceof View
                        ? IosNotificationStackHooks.restingStackOrigin((View) chain.getThisObject()) : null;
                try {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View) {
                        IosNotificationStackHooks.enforceUnstackedRow((View) chain.getThisObject());
                    }
                    return result;
                } finally {
                    IosNotificationStackHooks.restoreStackOrigin(origin);
                }
            });
            // Stacking setters are small and can be inlined into this final property pass.
            // Deoptimize the caller so EMPTY also reaches clipping/dimming/visibility.
            deoptimize(row, "onPropertiesUpdate", "dispatchPropertiesUpdate");
            deoptimize(Xp.findClass("com.miui.systemui.widget.PropertiesFrameLayout", loader),
                    "dispatchPropertiesUpdate");
            Xp.log("[IOSShade] notifications follow the hosted sheet; OEM panel transforms disabled");
        } catch (Throwable error) {
            Xp.log("[IOSShade] notification property hook unavailable: " + error);
        }
    }

    static void deoptimize(Class<?> type, String... names) {
        for (Method method : type.getDeclaredMethods()) {
            for (String name : names) {
                if (method.getName().equals(name)) {
                    try {
                        Xp.api().deoptimize(method);
                    } catch (Throwable error) {
                        Xp.log("[IOSShade] cannot deoptimize " + name + ": " + error);
                    }
                    break;
                }
            }
        }
    }
}
