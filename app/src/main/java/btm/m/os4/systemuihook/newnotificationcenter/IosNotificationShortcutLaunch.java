package btm.m.os4.systemuihook.newnotificationcenter;

import android.os.Handler;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Adapts the native swipe animation's keyguard-only launch lifecycle to an unlocked shade. */
final class IosNotificationShortcutLaunch {
    private static final Set<Class<?>> hookedAnimations =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static IosNotificationShortcutLaunch active;

    private final Object controller;
    private final Object moveController;
    private final Object animation;
    private final Object manager;
    private final Handler handler;
    private boolean attempted;
    private boolean launched;
    private boolean finishing;
    private final Runnable completion = () -> finish(launched);
    private final Runnable cancellation = () -> finish(false);
    private final Runnable failure = this::showFailure;

    private void showFailure() {
        if (active != this || finishing) return;
        try { ((Runnable) Xp.getObjectField(animation, "dismissRunnable")).run(); }
        catch (Throwable error) { Xp.log("[IOSShade] shortcut failure feedback unavailable: " + error); }
        finally { finish(false); }
    }

    static IosNotificationShortcutLaunch create(Object controller, Object plugin) throws Exception {
        return new IosNotificationShortcutLaunch(controller, plugin);
    }

    private IosNotificationShortcutLaunch(Object controller, Object plugin) throws Exception {
        this.controller = controller;
        moveController = Xp.getObjectField(plugin, "shortcutMoveController");
        animation = Xp.getObjectField(moveController, "shortcutOccludedAnimController");
        manager = Xp.findClass("com.miui.keyguard.shortcuts.manager.MiuiShortcutManager",
                plugin.getClass().getClassLoader()).getField("INSTANCE").get(null);
        handler = (Handler) Xp.callMethod(animation, "getMainHandler");
        Class<?> type = animation.getClass();
        if (!hookedAnimations.contains(type)) {
            Xp.hookAll(type, "startShortcutActivity", chain -> {
                IosNotificationShortcutLaunch current = active;
                if (current == null || current.animation != chain.getThisObject()) return chain.proceed();
                // The OEM coroutine returns without launching when keyguardGoingAway remains
                // true after unlock, and otherwise waits for a keyguard occlusion we never get.
                // Keep its native shortcut selection, intent, user and analytics implementation.
                current.startActivity();
                return null;
            });
            hookedAnimations.add(type);
            IosNotificationAnimationHooks.deoptimize(type, "startFullScreenAnim");
        }
    }

    void begin() {
        if (active != null) active.finish(false);
        attempted = false;
        launched = false;
        active = this;
    }

    void released() {
        if (active != this || finishing) return;
        // A one-shot fallback also covers a dropped terminal callback; no idle polling.
        handler.removeCallbacks(completion);
        handler.postDelayed(completion, 2500L);
    }

    void cancel() { finish(false); }

    private void startActivity() {
        if (finishing || attempted) return;
        attempted = true;
        try {
            launched = Boolean.TRUE.equals(Xp.callMethod(manager, "startShortcutActivity"));
        } catch (Throwable error) {
            Xp.log("[IOSShade] shortcut launch failed: " + error);
        }
        if (!launched) {
            // Retain the OEM's failure feedback, but never leave its fullscreen layer behind.
            handler.post(failure);
        }
    }

    static boolean onCallback(Object owner, String action) {
        IosNotificationShortcutLaunch current = active;
        if (current == null || current.controller != owner) return false;
        switch (action) {
            case "onFullscreenAnimationStart":
                current.released();
                return true;
            case "onFullscreenAnimationEnd":
                if (!current.finishing) {
                    current.handler.removeCallbacks(current.completion);
                    current.handler.post(current.completion);
                }
                return true;
            case "onBackAnimationEnd":
            case "onDismissAnimationEnd":
                if (!current.finishing) current.handler.post(current.cancellation);
                return true;
            case "onOccludedAnimationStart":
            case "onOccludedAnimationEnd":
            case "onMoveDistanceUpdate":
            case "updateCanShowBiometricAuthentication":
            case "onTouchUp":
                // These callbacks change keyguard alpha, authentication and occlusion state.
                // The borrowed buttons must not drive that state from the unlocked shade.
                return true;
            default:
                return false;
        }
    }

    private void finish(boolean collapse) {
        if (active != this || finishing) return;
        finishing = true;
        handler.removeCallbacks(completion);
        handler.removeCallbacks(cancellation);
        handler.removeCallbacks(failure);
        try {
            if (collapse) IosNotificationCenterHooks.collapseForShortcut();
            Xp.callMethod(moveController, "reset", "ios notification shortcut finished");
        } catch (Throwable error) {
            Xp.log("[IOSShade] shortcut reset unavailable: " + error);
            try { Xp.callMethod(animation, "removeAnimShortcutView"); }
            catch (Throwable fallback) { Xp.log("[IOSShade] shortcut layer removal unavailable: " + fallback); }
        } finally {
            try {
                Xp.callMethod(manager, "setInAppOpening", false);
                Xp.callMethod(manager, "resetOccludedMovedShortcut");
                Xp.callMethod(manager, "removeWindowIfNeed");
            } catch (Throwable error) {
                Xp.log("[IOSShade] shortcut window cleanup unavailable: " + error);
            }
            active = null;
            finishing = false;
        }
    }
}
