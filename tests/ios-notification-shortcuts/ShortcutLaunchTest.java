package btm.m.os4.systemuihook.newnotificationcenter;

import android.os.Handler;
import btm.m.os4.systemuihook.hypermusiccover.Xp;
import com.miui.keyguard.shortcuts.manager.MiuiShortcutManager;

class IosNotificationAnimationHooks {
    static void deoptimize(Class<?> type, String... names) {}
}
class IosNotificationCenterHooks {
    static int collapses;
    static Runnable onCollapse;
    static void collapseForShortcut() {
        collapses++;
        if (onCollapse != null) onCollapse.run();
    }
}

public final class ShortcutLaunchTest {
    public static final class Animation {
        public final Handler handler = new Handler();
        public int originalLaunches, feedback, removals;
        public boolean layer;
        public final Runnable dismissRunnable = () -> feedback++;
        public Handler getMainHandler() { return handler; }
        public void removeAnimShortcutView() { layer = false; removals++; }
        void requestLaunch() throws Throwable {
            Xp.hooks.get(getClass()).invoke(new Xp.Chain() {
                public Object getThisObject() { return Animation.this; }
                public Object proceed() { originalLaunches++; return null; }
            });
        }
    }
    public static final class Move {
        public final Animation shortcutOccludedAnimController = new Animation();
        public int resets;
        public boolean throwOnReset;
        public void reset(String reason) {
            resets++;
            if (throwOnReset) throw new IllegalStateException("vendor reset failed");
            shortcutOccludedAnimController.removeAnimShortcutView();
            // Vendor reset may synchronously finish a canceled fullscreen animation.
            IosNotificationShortcutLaunch.onCallback(owner, "onFullscreenAnimationEnd");
        }
        public Object owner;
    }
    public static final class Plugin {
        public final Move shortcutMoveController = new Move();
    }
    private static final MiuiShortcutManager manager = MiuiShortcutManager.INSTANCE;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Throwable {
        Object owner = new Object();
        Plugin plugin = new Plugin();
        Move move = plugin.shortcutMoveController;
        move.owner = owner;
        Animation animation = move.shortcutOccludedAnimController;
        IosNotificationShortcutLaunch bridge = IosNotificationShortcutLaunch.create(owner, plugin);
        IosNotificationShortcutLaunch.create(new Object(), new Plugin());
        check(Xp.registrations == 1, "one hook per plugin animation class");

        animation.requestLaunch();
        check(animation.originalLaunches == 1 && manager.launches == 0, "stock lockscreen launch unchanged");
        check(!IosNotificationShortcutLaunch.onCallback(owner, "onFullscreenAnimationStart"), "stock callbacks unchanged");

        bridge.begin();
        manager.selected = manager.inAppOpening = animation.layer = true;
        check(IosNotificationShortcutLaunch.onCallback(owner, "onFullscreenAnimationStart"), "hosted start callback consumed");
        check(!IosNotificationShortcutLaunch.onCallback(new Object(), "onFullscreenAnimationStart"), "controller identity scoped");
        animation.requestLaunch();
        animation.requestLaunch();
        bridge.released();
        check(manager.launches == 1 && animation.originalLaunches == 1, "launch exactly once despite stale goingAway");
        check(manager.keyguardGoingAway && animation.layer, "keyguard state preserved; animation retained until completion");
        IosNotificationCenterHooks.onCollapse = bridge::cancel;
        IosNotificationShortcutLaunch.onCallback(owner, "onFullscreenAnimationEnd");
        animation.handler.drain();
        check(IosNotificationCenterHooks.collapses == 1 && move.resets == 1, "completion handles synchronous restore exactly once");
        check(!animation.layer && !manager.inAppOpening && !manager.selected && animation.handler.empty(), "all launch state released");
        IosNotificationCenterHooks.onCollapse = null;

        bridge.begin();
        manager.selected = animation.layer = true;
        animation.requestLaunch();
        bridge.released();
        animation.handler.elapse();
        check(!animation.layer && animation.handler.empty(), "lost fullscreen callback times out and removes layer");

        int launches = manager.launches;
        bridge.begin();
        animation.layer = manager.inAppOpening = manager.selected = true;
        bridge.cancel();
        animation.handler.elapse();
        check(!animation.layer && manager.launches == launches && animation.handler.empty(), "cancel never launches");

        bridge.begin();
        animation.layer = manager.selected = true;
        IosNotificationShortcutLaunch.onCallback(owner, "onBackAnimationEnd");
        animation.handler.drain();
        check(!animation.layer && animation.handler.empty(), "short drag returns cleanly");

        manager.succeeds = false;
        bridge.begin();
        animation.layer = manager.selected = true;
        animation.requestLaunch();
        bridge.released();
        animation.handler.drain();
        check(animation.feedback == 1 && !animation.layer && animation.handler.empty(), "failed launch shows native feedback and removes layer");

        bridge.begin();
        manager.selected = true;
        animation.requestLaunch();
        bridge.cancel();
        bridge.begin();
        animation.handler.drain();
        check(animation.feedback == 1, "old failure cannot cancel a new gesture");
        bridge.cancel();

        bridge.begin();
        move.throwOnReset = true;
        animation.layer = manager.inAppOpening = true;
        bridge.cancel();
        check(!animation.layer && !manager.inAppOpening && animation.handler.empty(), "reset failure still removes layer and window state");
        check(!IosNotificationShortcutLaunch.onCallback(owner, "onMoveDistanceUpdate"), "callback ownership released");
        System.out.println("Shortcut launch lifecycle regression checks passed.");
    }
}
