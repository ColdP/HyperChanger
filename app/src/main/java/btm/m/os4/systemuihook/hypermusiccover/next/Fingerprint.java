// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.island.NotificationSink;
import android.content.Context;
import android.view.ViewGroup;
import java.util.List;
import android.view.View;
import java.lang.reflect.Method;

/**
 * The under-display fingerprint on the lock screen: its icon and animation views across the
 * windows they live in, hiding them (and replaying the ring's frames on the way back), and the
 * OEM's avoidance that lifts the notifications above the sensor - which the 通知下沉 setting
 * overrides.
 *
 * Carved out of Main as it was (2026-10-14, after ClockRoom): every member moved whole with its
 * comments, references across the line prefixed with Main. or Fingerprint., nothing else changed.
 */
final class Fingerprint {
    private Fingerprint() {}

    /**
     * The fingerprint ring is drawn twice over, so hiding one of these leaves the other on
     * screen: the frame animation plays the pulsing circle from a list of drawables, and the
     * icon view holds the static print underneath it.
     */
    static final String CLS_FOD_ANIM =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation";

    static final String CLS_FOD_ICON =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView";

    /**
     * The view the frames are painted INTO, and the one that actually carries the print.
     *
     * Measured, not assumed: on this phone the icon view above is 206x206 with its alpha at zero
     * and the print is still on screen, while this one sits visible at full alpha in the
     * `gxzw_anim` window. The frame animation is how it is normally fed - which is why
     * substituting the frames hides the print once the animation has run - but the first
     * keyguard of a SystemUI start paints it without the animation ever being asked, and that
     * paint is the one nothing was intercepting.
     */
    static final String CLS_FOD_ANIM_VIEW =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimationView";

    private static final String AVOID_PREFIX = Main.CLS_KG_PANEL
            + "$nsslLockYPosition_delegate$lambda";

    private static final String AVOID_SUFFIX = "$$inlined$combine$1$3";

    /**
     * Hide the lock screen fingerprint ring. Unlike the two card switches above, this one is not
     * tied to cover mode: an icon that appeared and vanished as the music started and stopped
     * would read as a glitch rather than as a setting.
     */
    static volatile boolean sHideFp;

    /**
     * Whether the notification stack keeps clear of the fingerprint icon: 0 leaves it to the
     * system, 1 never avoids it, 2 always does. Not a boolean, because "off" here would mean two
     * different things - stop reserving the space, or reserve it even with no print enrolled.
     */
    static volatile int sFpAvoid;

    /**
     * Every fingerprint icon view built since SystemUI started, weakly held. The alpha is set at
     * construction, but the switch can move afterwards, and a hidden icon has to be able to come
     * back without the user restarting SystemUI.
     */
    static final java.util.Map<View, Boolean> sFodIcons =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    static final FingerprintFrameRestore<Object> sFodFrames = new FingerprintFrameRestore<>();

    static int sFodFramesReplayed;

    /** resId -> is this one of the ring's frames, so the name lookup happens once per drawable. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> sFodRing =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The combine behind nsslLockYPosition, whatever R8 called it this time.
     *
     * The name carries an ordinal that is assigned per build, so it cannot be written down: a
     * number measured on one HyperOS is simply a wrong guess on the next. The class loader's own
     * dex tables are asked instead, and the ordinal probe below only exists for the case where
     * those private fields have moved.
     */
    static Class<?> findAvoidCombine(ClassLoader cl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field pf = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pf.setAccessible(true);
            Object pathList = pf.get(cl);
            java.lang.reflect.Field ef = pathList.getClass().getDeclaredField("dexElements");
            ef.setAccessible(true);
            for (Object el : (Object[]) ef.get(pathList)) {
                try {
                    java.lang.reflect.Field df = el.getClass().getDeclaredField("dexFile");
                    df.setAccessible(true);
                    Object dex = df.get(el);
                    if (dex == null) continue;
                    java.util.Enumeration<?> en = (java.util.Enumeration<?>)
                            dex.getClass().getMethod("entries").invoke(dex);
                    while (en.hasMoreElements()) {
                        Object o = en.nextElement();
                        if (!(o instanceof String)) continue;
                        // Normalised first: entries() has been seen returning both the internal
                        // form and the binary one, and matching only one of them would fail
                        // silently on the other.
                        String n = ((String) o).replace('/', '.');
                        if (n.startsWith(AVOID_PREFIX) && n.endsWith(AVOID_SUFFIX)) names.add(n);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "dex walk for the avoid combine failed: " + t);
        }
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (Main.invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        for (int i = 1; i <= 400; i++) {
            try {
                Class<?> c = Class.forName(AVOID_PREFIX + "$" + i + AVOID_SUFFIX, false, cl);
                if (Main.invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Is this drawable one of the fingerprint ring's frames?
     *
     * Asked by resource name rather than against a list of ids: ids are assigned per build, so a
     * list would have to be re-measured on every phone, while the names the OEM gives these
     * frames have been stable. The answer is cached because the question is asked inside a draw.
     */
    static boolean isFodRing(int resId) {
        Boolean known = sFodRing.get(resId);
        if (known != null) return known;
        Context c = Main.sAppCtx;
        // No context yet is not an answer worth remembering: it means the keyguard has not
        // attached, and the next draw will resolve properly.
        if (c == null) return false;
        boolean ring = false;
        try {
            String name = c.getResources().getResourceEntryName(resId);
            ring = name != null
                    && (name.startsWith("finger_circle") || name.contains("fingerprint_circle"));
        } catch (Throwable ignored) {
            // Not a resource in SystemUI's table, so not one of the OEM's ring frames. Cached
            // as such - the same id will come back on the next frame.
        }
        sFodRing.put(resId, ring);
        return ring;
    }

    /** Set once the state file has been consulted for sHideFp, whether or not it had a value. */
    private static volatile boolean sHideFpPeeked;

    /**
     * Reads the fingerprint setting out of the state file before loadState() would.
     *
     * The print is not part of the keyguard's own tree. It lives in a window of its own -
     * `gxzw_touch`, 206x206, the module's report names it - which SystemUI paints as the
     * keyguard comes up, and that paint happens BEFORE KeyguardClockContainer attaches. Since
     * attaching is what gives the module a context, and the context is what lets it read the
     * state file, the first frame of the print was always decided with the setting still at its
     * default. The hook let it through, the window was never asked to draw again for as long as
     * the keyguard stayed up, and the print sat there for the whole session - which is what
     * "restarting SystemUI turns the hiding off until you lock the phone a second time" was.
     *
     * Only this one key, and only until the real load has happened: the rest of the file belongs
     * to loadState, which applies values through setters that want a built keyguard. A file
     * caught mid-write costs the key its default for this one read, the same exposure loadState
     * has always had.
     */
    static void peekHideFp() {
        if (sHideFpPeeked || Main.sAppCtx != null) return;
        sHideFpPeeked = true;
        try {
            // Reflection because ActivityThread is not in the SDK to compile against. This is
            // the process's own Application - there is no other context to be had this early,
            // and the hooks that call this run on SystemUI's main thread, where it is set.
            Context c = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (c == null) {
                // Nothing to read it with yet; the next draw tries again.
                sHideFpPeeked = false;
                return;
            }
            android.util.AtomicFile f = new android.util.AtomicFile(
                    new java.io.File(c.getFilesDir(), ModuleState.STATE_FILE));
            if (!ModuleState.stateExists(f)) return;
            for (String line : new String(f.readFully()).split("\n")) {
                if (line.startsWith("hidefp=")) {
                    sHideFp = "1".equals(line.substring(7).trim());
                    Xp.log(Main.TAG + "fingerprint setting read early: hide=" + sHideFp);
                    return;
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "reading the fingerprint setting early failed: " + t);
        }
    }

    /**
     * The root view of every window that belongs to the print, through WindowManagerGlobal.
     *
     * These windows are not in the keyguard's tree - `gxzw_touch` holds the print and
     * `gxzw_anim` the ring and the "try again" tip - so there is no way to them from a view we
     * were handed. Matched by window name, which is the OEM's and has been stable, and the
     * result is used for nothing but finding the icon views inside.
     */
    private static java.util.List<View> fodWindowRoots() {
        java.util.List<View> out = new java.util.ArrayList<>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object g = wmg.getMethod("getInstance").invoke(null);
            String[] names = (String[]) wmg.getMethod("getViewRootNames").invoke(g);
            java.lang.reflect.Method getRoot = wmg.getMethod("getRootView", String.class);
            for (String name : names) {
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("gxzw")) {
                    continue;
                }
                View v = (View) getRoot.invoke(g, name);
                if (v != null) out.add(v);
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "the fingerprint windows could not be read: " + t);
        }
        return out;
    }

    /**
     * Takes in any icon view the constructor hook never saw.
     *
     * On this phone it never sees any: the view on screen is a MiuiGxzwIconView by the name we
     * hook, yet no construction of it is ever intercepted, so the alpha the hook exists to set
     * was being applied to an empty list. Found by walking the print's own windows instead, and
     * put in the same map, so the setting's switch reaches them like any other.
     *
     * Only views that name themselves an icon: the ring and the "try again" tip share those
     * windows and neither is the print.
     */
    private static void adoptFodIcons() {
        for (View root : fodWindowRoots()) adoptFodIcons(root, 0);
    }

    private static void adoptFodIcons(View v, int depth) {
        if (v == null || depth > 6) return;
        // Both painters: the icon view and the one the frames land on. The tip view in the same
        // window is neither, and keeps its "try again" to itself.
        String name = v.getClass().getName();
        if (name.endsWith("IconView") || name.equals(CLS_FOD_ANIM_VIEW)) {
            sFodIcons.put(v, Boolean.TRUE);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) adoptFodIcons(g.getChildAt(i), depth + 1);
        }
    }

    /**
     * Applies the current setting to every icon view still alive. Runs on the main thread: the
     * receiver has no handler of its own, so it is already there.
     */
    static void applyHideFp() {
        adoptFodIcons();
        if (!sHideFp) {
            sFodFrames.restore((animation, resource) -> {
                try {
                    if (!fodForKeyguard(fodViewOf(animation))) return;
                    // Surface frames are independent of View.invalidate(). Restore through
                    // the OEM draw queue so rapid hide/show changes keep their original order.
                    Xp.callMethod(animation, "draw", resource);
                    sFodFramesReplayed++;
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "fingerprint frame restore failed: " + t);
                }
            });
        }
        java.util.List<View> views;
        synchronized (sFodIcons) {
            views = new java.util.ArrayList<>(sFodIcons.keySet());
        }
        // Redrawn, not dimmed: whether a frame is painted is decided per draw and per surface
        // (fodForKeyguard). An alpha on the view hid the print in an app's own prompt as well.
        for (View v : views) {
            try {
                v.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Retirement: every frame the hide has suppressed is drawn again, and every icon the hook
     * has seen is invalidated so the print comes back without a restart.
     *
     * <p>Not applyHideFp(): that follows the user's switch, and while the switch is on it
     * deliberately does not replay. The setting is the user's and is read again by the next
     * generation; what has to end here is this generation's drawing.
     */
    static void retire() {
        sFodFrames.restore((animation, resource) -> {
            try {
                if (!fodForKeyguard(fodViewOf(animation))) return;
                Xp.callMethod(animation, "draw", resource);
                sFodFramesReplayed++;
            } catch (Throwable t) {
                Xp.log(Main.TAG + "fingerprint frame restore failed: " + t);
            }
        });
        java.util.List<View> views;
        synchronized (sFodIcons) {
            views = new java.util.ArrayList<>(sFodIcons.keySet());
        }
        for (View v : views) {
            try {
                v.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Whether the under-screen print is showing for the lock screen or the always-on display, as
     * opposed to an app's own fingerprint prompt - a payment, a password manager. The same views
     * draw all three, so hiding them outright hid the print where an app asked for it too.
     *
     * Read the way HyperTweak reads it (TakeKazeX/HyperTweak, HideFingerprintIcon, GPL-3.0): the
     * gxzw views keep mDozing and mKeyguardAuthen - MiuiGxzwIconView on itself, the animation view
     * on its parent MiuiGxzwAnimViewInternal - and neither true is an app's prompt. A build
     * without the fields falls back to whether the keyguard is up.
     */
    static boolean fodForKeyguard(Object start) {
        Boolean dozing = null, authen = null;
        Object cur = start;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (dozing == null) dozing = fodFlag(cur, "mDozing");
            if (authen == null) authen = fodFlag(cur, "mKeyguardAuthen");
            if (dozing != null && authen != null) break;
            cur = cur instanceof View ? ((View) cur).getParent() : null;
        }
        if (dozing == null && authen == null) return Main.keyguardShowing();
        return Boolean.TRUE.equals(dozing) || Boolean.TRUE.equals(authen);
    }

    /** MiuiGxzwFrameAnimation's view, which carries the state its frames are drawn for. */
    static Object fodViewOf(Object frameAnimation) {
        try {
            return Xp.getObjectField(frameAnimation, "mMiuiGxzwAnimationView");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Per class and name: the Field, or NO_FIELD - looked up once, since a miss throws. */
    private static final java.util.Map<String, Object> sFodFields =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A boolean field on the object or its superclasses, or null where there is none. */
    private static Boolean fodFlag(Object o, String name) {
        String key = o.getClass().getName() + "#" + name;
        Object f = sFodFields.get(key);
        if (f == null) {
            f = Main.NO_FIELD;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field d = c.getDeclaredField(name);
                    if (d.getType() == boolean.class) {
                        d.setAccessible(true);
                        f = d;
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Further up.
                }
            }
            sFodFields.put(key, f);
        }
        if (!(f instanceof java.lang.reflect.Field)) return null;
        try {
            return ((java.lang.reflect.Field) f).getBoolean(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static void hookFingerprintAnimation(final ClassLoader cl) {
        // The fingerprint ring, when the user has asked for it to go. Two hooks, because the
        // ring is two things: MiuiGxzwFrameAnimation plays the pulsing circle out of a list of
        // drawables, and MiuiGxzwIconView holds the static print underneath. Hiding either one
        // alone leaves the other on screen.
        //
        // The split is HyperTweak's, read off https://github.com/TakeKazeX/HyperTweak: which of
        // these draws what, and that hiding one of the two is not enough. What this phone needed
        // on top of that was measured here rather than taken from anyone - see CLS_FOD_ANIM_VIEW.
        //
        // Both install whatever the setting says and read the flag per call, so the switch takes
        // effect on the next draw rather than on the next SystemUI restart. And both are on
        // their own terms: a build that renamed one still gets the other.
        //
        // Per call is not by itself enough, because the first call can come before the module
        // has read its settings at all - see peekHideFp, which is what both of them start with.
        try {
            Class<?> anim = Xp.findClass(CLS_FOD_ANIM, cl);
            Xp.hookAll("sysui.fp.anim_draw", anim, "draw", chain -> {
                Object[] args = chain.getArgs().toArray();
                // The print's own window is painted before the keyguard's clock container
                // attaches, so the setting has to be fetched here rather than waited for.
                peekHideFp();
                // draw(int resId) is the frame. Any other overload is not ours to touch, which
                // the argument check below says without having to name the signature.
                if (sHideFp && args.length == 1 && args[0] instanceof Integer
                        && isFodRing((Integer) args[0])
                        && fodForKeyguard(fodViewOf(chain.getThisObject()))) {
                    // Substituting the drawable rather than skipping the draw: the animation
                    // keeps its own timing and its own lifecycle, it just paints nothing. A
                    // skipped draw would leave whatever the OEM expects to be on that surface.
                    sFodFrames.suppressed(chain.getThisObject(), (Integer) args[0]);
                    args[0] = android.R.color.transparent;
                } else if (args.length == 1 && args[0] instanceof Integer) {
                    sFodFrames.painted(chain.getThisObject());
                }
                return chain.proceed(args);
            });
            Xp.log(Main.TAG + "fingerprint animation hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fingerprint animation hook failed, the ring will still pulse: " + t);
        }
    }

    static void hookFingerprintIcon(final ClassLoader cl) {
        try {
            Class<?> iconCls = Xp.findClass(CLS_FOD_ICON, cl);
            Xp.hookAllConstructors("sysui.fp.icon_ctor", iconCls, chain -> {
                Object result = chain.proceed();
                try {
                    View v = (View) chain.getThisObject();
                    // Built before the keyguard attaches on the same builds the ring is, and
                    // this is the one chance to dim it before it is ever seen.
                    peekHideFp();
                    sFodIcons.put(v, Boolean.TRUE);
                } catch (Throwable ignored) {
                    // A view we cannot dim is a visible print, not a broken keyguard.
                }
                return result;
            });
            // And the paint itself, where the build declares one. This view paints nothing on
            // the phone this was measured on - its alpha sits at zero with the print still on
            // screen - but on a build where it is the painter, a frame it never draws cannot be
            // brought back by anything that happens between frames, which the alpha above can.
            for (String name : new String[] {"onDraw", "draw"}) {
                try {
                    Xp.hookAll("sysui.fp.icon_paint." + Xp.slug(name), iconCls, name, chain -> {
                        peekHideFp();
                        if (sHideFp && fodForKeyguard(chain.getThisObject())) return null;
                        return chain.proceed();
                    });
                    break;
                } catch (Throwable ignored) {
                    // Not declared here; try the other name.
                }
            }
            Xp.log(Main.TAG + "fingerprint icon hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fingerprint icon hook failed, the static print will stay: " + t);
        }
    }

    static void hookFingerprintPrintView(final ClassLoader cl) {
        // The view the print is actually painted on. Its own hook because it is its own class
        // and its own failure: the frame substitution above covers it only once the animation
        // has run, and the first keyguard after a SystemUI start paints without it.
        try {
            Class<?> animView = Xp.findClass(CLS_FOD_ANIM_VIEW, cl);
            int hooked = 0;
            for (String name : new String[] {"onDraw", "draw", "dispatchDraw"}) {
                try {
                    Xp.hookAll("sysui.fp.print_paint." + Xp.slug(name), animView, name, chain -> {
                        peekHideFp();
                        // Painting nothing, rather than dimming: the alpha on this view is the
                        // OEM's to animate, and a frame it never paints cannot be animated back.
                        if (sHideFp && fodForKeyguard(chain.getThisObject())) return null;
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // Not declared on this build; the others still stand.
                }
            }
            Xp.log(Main.TAG + "fingerprint print view hooked, " + hooked + " of its paint methods");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fingerprint print view hook failed: " + t);
        }
    }

    static void hookFingerprintAvoidance(final ClassLoader cl) {
        // Both manual and automatic sinking use the OEM's fingerprint avoidance branch.
        // Add our policy as an input so the system recomputes and emits on its own coroutine.
        try {
            // Fail on the panel rather than on the lambda: "no KeyguardPanelViewController" is a
            // different build, "no combine lambda" is a different R8 run, and the log should say
            // which one happened.
            Xp.findClass(Main.CLS_KG_PANEL, cl);
            long t0 = android.os.SystemClock.uptimeMillis();
            Class<?> combine = findAvoidCombine(cl);
            java.lang.reflect.Method invoke = combine == null ? null : Main.invoke3(combine);
            if (invoke == null) {
                Xp.log(Main.TAG + "no nsslLockYPosition combine lambda on this build"
                        + " - fingerprint avoidance cannot be overridden");
            } else {
                Xp.hook("sysui.fp.avoid_combine", invoke, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length == 3 && args[1] instanceof Object[]) {
                        Object[] vals = (Object[]) args[1];
                        // Clone before overriding: combine retains its array across emissions.
                        Object[] effective = NotificationSink.withAvoidance(vals, sFpAvoid);
                        args[1] = effective;
                        Object result = chain.proceed(args);
                        String bound;
                        try {
                            bound = String.valueOf(Xp.callMethod(args[0], "getValue"));
                        } catch (Throwable t) {
                            bound = args[0].getClass().getName() + ": " + t;
                        }
                        Main.sNotificationSink.record(vals, effective, bound);
                        return result;
                    }
                    return chain.proceed();
                });
                try {
                    Class<?> combineKt = Xp.findClass("kotlinx.coroutines.flow.internal.CombineKt", cl);
                    Xp.hookAll("sysui.fp.avoid_combine_internal", combineKt, "combineInternal", chain -> {
                        Object[] args = chain.getArgs().toArray();
                        // Only this panel's transform: other SystemUI combines keep their inputs.
                        if (args.length == 5 && combine.isInstance(args[2])
                                && args[4] instanceof Object[]) {
                            try {
                                args[4] = Main.sNotificationSink.attachToCombine(
                                        args[1], (Object[]) args[4], sFpAvoid);
                                Main.sNotificationSink.hookStatus("notification combine entered");
                            } catch (Throwable t) {
                                Main.sNotificationSink.hookStatus("combine attachment failed: " + t);
                            }
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
                    // The transform is $1$3; its enclosing combined Flow is $1.
                    Class<?> combinedFlow = Xp.findClass(combine.getName().substring(0,
                            combine.getName().length() - 2), cl);
                    Xp.hookAll("sysui.fp.avoid_flow_collect", combinedFlow, "collect", chain -> {
                        try {
                            if (Main.sNotificationSink.attach(chain.getThisObject(), sFpAvoid))
                                Xp.log(Main.TAG + "notification bound policy flow attached");
                        } catch (Throwable t) {
                            Xp.log(Main.TAG + "notification bound policy flow unavailable: " + t);
                        }
                        return chain.proceed();
                    });
                    for (java.lang.reflect.Method method : combinedFlow.getDeclaredMethods())
                        if ("collect".equals(method.getName())) Xp.api().deoptimize(method);
                    // stateIn(Eagerly) starts collecting once. Attach when its sharing
                    // coroutine executes, before collect can be inlined or started.
                    Class<?> sharing = Xp.findClass(
                            "kotlinx.coroutines.flow.FlowKt__ShareKt$launchSharing$1", cl);
                    Xp.hookAll("sysui.fp.avoid_flow_share", sharing, "invokeSuspend", chain -> {
                        try {
                            Object upstream = Xp.getObjectField(chain.getThisObject(), "$upstream");
                            if (combinedFlow.isInstance(upstream)
                                    && Main.sNotificationSink.attach(upstream, sFpAvoid))
                                Xp.log(Main.TAG + "notification bound policy flow attached before collection");
                        } catch (Throwable t) {
                            Xp.log(Main.TAG + "notification bound sharing attachment failed: " + t);
                        }
                        return chain.proceed();
                    });
                    // ART may inline constructors and collect. Observe the running coroutine
                    // before its first collection, and deoptimize callers of invokeSuspend.
                    for (java.lang.reflect.Method method : sharing.getDeclaredMethods()) {
                        if ("invoke".equals(method.getName()) || "create".equals(method.getName())
                                || "invokeSuspend".equals(method.getName()))
                            Xp.api().deoptimize(method);
                    }
                    Main.sNotificationSink.hookStatus("sharing invokeSuspend installed");
                    Xp.log(Main.TAG + "notification bound sharing hook installed");
                } catch (Throwable t) {
                    Main.sNotificationSink.hookStatus("collection failed: " + t);
                    Xp.log(Main.TAG + "notification bound collection hook failed: " + t);
                }
                Xp.log(Main.TAG + "fingerprint avoidance hooked on " + combine.getName()
                        + " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
            }
        } catch (Throwable t) {
            Main.sNotificationSink.hookStatus("avoidance failed: " + t);
            Xp.w(Main.TAG + "fingerprint avoidance hook failed: " + t);
        }
    }
}
