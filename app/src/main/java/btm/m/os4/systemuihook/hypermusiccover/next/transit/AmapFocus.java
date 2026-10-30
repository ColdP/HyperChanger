// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets 高德's ride card be a focus notification at all.
 *
 * The card is posted as 高德 (id 1239; a `param_v2` template now, a `miui.focus.rv` layout of its
 * own when this was written), and the focus plugin threw it away every time:
 *
 *   FocusPlugin: onInflateSuccess 0|com.autonavi.minimap|1239|null|10385
 *   FocusPlugin: onAuthFailed     0|com.autonavi.minimap|1239|null|10385  com.autonavi.minimap
 *   FocusPlugin: removeByKey / removeFocusNotificationByKey / removeIslandDataByKey
 *
 * The way through `FocusNotificationController.fetchAuthResult` (read out of the plugin's smali,
 * classes2.dex) is:
 *
 *   if (!ISLAND_XMS_SWITCHER)                                    -> onAuthSuccess
 *   if (notificationSettingsManager.canPassXMSPermission(pkg))   -> onAuthSuccess
 *   if (IS_INTERNATIONAL_BUILD)                                  -> onAuthSuccess
 *   if (focusNotifUtils.hasCustomFocusView(n)):                  // a miui.focus.rv is one
 *       if (canCustomFocus(pkg) || isNotificationPromotedOngoing) -> onAuthSuccess
 *       resetAllParam(bundle); onAuthFailed(key, pkg)            // <- where 高德 died
 *   if (signatureChecker.checkSignatures(pkg))                   -> onAuthSuccess
 *   AuthManager.auth(context, key, pkg, cb)                      // the cloud service
 *
 * So it is not the cross-package RemoteViews - that only explains `onInflateSuccess` - but
 * `canCustomFocus`, the cloud list of apps allowed a focus view of their own, which does not name
 * 高德. This answers it for that one package and leaves the list alone for everyone else.
 * `canPassXMSPermission` is answered too: it is the earlier door on the same path, and the one
 * that does not depend on which of the two questions a build asks first.
 *
 * Both live on `miui.systemui.notification.NotificationSettingsManager`, which is defined in the
 * control centre plugin's own APK and loaded by a class loader built inside SystemUI long after
 * this module's hooks go in - the same late arrival HyperTweaks watches for. That is what is
 * watched here: every class loader the process builds passes through BaseDexClassLoader's
 * constructor, and the first one that can see the class is the plugin's.
 */
public final class AmapFocus {

    private static final String TAG = "MCAmapFocus: ";
    /** The notification owner whose own layout is kept. */
    private static final String AMAP = "com.autonavi.minimap";
    /** 小爱建议, whose ride-code island MetroCodeIsland posts as it. */
    private static final String ASSISTANT = "com.miui.personalassistant";
    /** SystemUI itself, whose pickup-code island PickupCodeIsland posts. */
    private static final String SYSUI = "com.android.systemui";
    /** The plugin's settings manager, the one asked about a focus view. */
    private static final String SETTINGS =
            "miui.systemui.notification.NotificationSettingsManager";
    private static final String[] ASKED = {"canCustomFocus", "canPassXMSPermission"};

    /** Set once the plugin's loader has been found and its two answers are hooked. */
    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    /** Set once the watch itself is in, so a second SystemUI callback does not add another. */
    private static final AtomicBoolean sWatching = new AtomicBoolean(false);
    /** The questions already answered once, for the log. */
    private static final java.util.Set<String> sAnswered =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private AmapFocus() {
    }

    /** Called from SystemUI's own loader: finds nothing there, and waits for the plugin's. */
    public static void install(ClassLoader cl) {
        try {
            soft(cl, "systemui");
        } catch (Throwable t) {
            Xp.log(TAG + "not visible from SystemUI: " + t);
        }
        watch();
    }

    /** Every loader the process builds, the plugin's among them. */
    private static void watch() {
        if (!sWatching.compareAndSet(false, true)) return;
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            for (Constructor<?> ctor : base.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Xp.hook("sysui.amap_focus.loader_watch", ctor, chain -> {
                    Object result = chain.proceed();
                    if (!sHooked.get() && chain.getThisObject() instanceof ClassLoader) {
                        soft((ClassLoader) chain.getThisObject(), "loader");
                    }
                    return result;
                });
            }
            Xp.log(TAG + "watching for the plugin's loader");
        } catch (Throwable t) {
            Xp.w(TAG + "watch failed: " + t);
        }
    }

    /** A hot-reloaded generation's half: the plugin's loader is already here, re-run the watch. */
    public static void adopt(ClassLoader cl) {
        soft(cl, "adopt");
    }

    /** Hooks the two questions for 高德, once the loader that can see the class turns up. */
    private static void soft(ClassLoader cl, String where) {
        if (sHooked.get()) return;
        Class<?> cls;
        try {
            cls = Xp.findClass(SETTINGS, cl);
        } catch (Throwable t) {
            return; // most loaders are not the plugin's
        }
        try {
            int hooked = 0;
            for (String name : ASKED) {
                try {
                    Xp.hookAll("sysui.amap_focus." + Xp.slug(name), cls, name, chain -> {
                        final java.util.List<Object> a = chain.getArgs();
                        final Object arg = a != null && !a.isEmpty() ? a.get(0) : null;
                        if (AMAP.equals(arg) || ASSISTANT.equals(arg) || SYSUI.equals(arg)) {
                            // Asked on every post of every 高德 notification (its walking island
                            // reposts each second), so said once rather than each time.
                            if (sAnswered.add(name + arg)) Xp.log(TAG + name + "(" + arg + ") -> true");
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable t) {
                    Xp.log(TAG + name + " not on this build: " + t);
                }
            }
            if (hooked > 0) {
                sHooked.set(true);
                Xp.log(TAG + "hooked " + hooked + " of " + ASKED.length + " from the " + where);
            }
        } catch (Throwable t) {
            Xp.log(TAG + "not hooked from the " + where + ": " + t);
        }
    }
}
