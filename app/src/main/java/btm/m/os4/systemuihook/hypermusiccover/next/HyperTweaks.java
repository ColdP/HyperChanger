// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * Adapted from HyperChanger (https://github.com/ColdP/HyperChanger),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: rewritten against this module's libxposed helpers, every hook made
 * to fail on its own, and the three restrictions that are always lifted here carry no setting of
 * their own - the class names, fields and the order the depth threshold has to be taken in are
 * HyperChanger's findings.
 */
package btm.m.os4.systemuihook.hypermusiccover.next;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * HyperOS restrictions this module lifts, none of which have anything to do with the cover.
 *
 * They are here because they are what a music lock screen runs into: the depth effect refuses
 * most wallpapers, the editor stops at ten saved lock screens, the big clock drops its colon, and
 * a global theme switches the soft glass off under everything.
 *
 * Three of the four are unconditional - there is no setting, because a restriction that only
 * exists to be worked around is not a preference. The colon is the exception: it changes what the
 * clock looks like, so it is a switch on the cover and clock page.
 *
 * Every hook stands alone. These classes are OEM internals that get renamed between HyperOS
 * builds, and a lookup that throws must cost its own feature and nothing else - see the module's
 * history of one missing class taking the whole of SystemUI unhooked with it.
 */
final class HyperTweaks {

    private HyperTweaks() {
    }

    private static final String TAG = "MCTweak: ";

    /** The one tweak here with a switch, driven from the cover and clock page. */
    static volatile boolean sForceColon;

    /**
     * The lock screen media card's progress bar, lit the way the island's is.
     *
     * On by default. Switched off it takes effect on the next card the OEM builds, not on the one
     * on screen: the mode is read in the widget's constructor and the shader it decides on is a
     * final field, so a bar already running it cannot be talked back out of it.
     */
    static volatile boolean sBarGlow = true;

    /**
     * What each hook did, in the order they were tried.
     *
     * Not a nicety. Module INFO logs are not readable on this device - LSPosed keeps only
     * error-level lines - so "did this install" is a question nothing could answer, and the
     * classes here are exactly the kind that get renamed between HyperOS builds. `op tweaks`
     * reads this back over the probe, which is the channel that does work.
     */
    private static final java.util.Map<String, String> STATUS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>());

    private static void ok(String what) {
        STATUS.put(what, "ok");
        Xp.log(TAG + what + " ok");
    }

    private static void failed(String what, Throwable t) {
        STATUS.put(what, "FAILED " + t);
        Xp.log(TAG + what + " FAILED: " + t);
    }

    /** One line per hook, for `op tweaks`. */
    static String describe() {
        StringBuilder sb = new StringBuilder("colon=").append(sForceColon ? "on" : "off");
        sb.append(System.lineSeparator()).append("barglow=").append(sBarGlow ? "on" : "off");
        synchronized (STATUS) {
            if (STATUS.isEmpty()) {
                sb.append(System.lineSeparator())
                        .append("(nothing ran - this process was never hooked)");
            }
            for (java.util.Map.Entry<String, String> e : STATUS.entrySet()) {
                sb.append(System.lineSeparator())
                        .append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    // The depth effect's own judgement of whether a picture has a subject worth cutting out.
    private static final String CLS_DEPTH_EVALUATOR = "com.miui.clock.utils.avoid.DepthAvoidEvaluator";
    private static final String CLS_DEPTH_THRESHOLD = "com.miui.clock.utils.avoid.DepthAvoidEvaluator$Threshold";
    private static final String CLS_HIERARCHY_AVOID = "com.miui.keyguard.editor.utils.HierarchyImageAvoidController";
    private static final String CLS_KG_DEPTH_INTERACTOR = "com.android.keyguard.depth.KeyguardDepthInteractor";
    private static final String CLS_KG_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController";
    private static final String CLS_WALLPAPER_INFO = "com.android.keyguard.wallpaper.entity.WallpaperInfo";
    private static final String CLS_HIERARCHY_ENABLE = "com.android.keyguard.wallpaper.entity.LargeScreenHierarchyEnable";
    private static final String CLS_AOD_WALLPAPER_INFO = "com.miui.keyguard.editor.data.bean.WallpaperInfo";
    private static final String CLS_AOD_HIERARCHY_ENABLE = "com.miui.keyguard.editor.data.bean.LargeScreenHierarchyEnable";
    private static final String CLS_CROSS_LIST_MODEL = "com.miui.keyguard.editor.homepage.model.CrossListDataModel";
    private static final String CLS_TEMPLATE_HISTORY_DAO =
            "com.miui.keyguard.editor.data.db.TemplateHistoryDao_Impl";
    private static final String CLS_CLOCK_BEAN = "com.miui.clock.module.ClockBean";
    private static final String CLS_MATERIAL_UTILS = "com.miui.systemui.controlcenter.utils.MiuiMaterialUtils";
    private static final String CLS_SYSUI_THEME_UTILS = "com.miui.utils.MiuiThemeUtils";
    private static final String CLS_CONFIG_CONTROLLER = "com.android.systemui.statusbar.phone.ConfigurationControllerImpl";
    private static final String CLS_PLUGIN_THEME_UTILS = "miui.systemui.util.ThemeUtils";
    private static final String CLS_MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat";
    private static final String CLS_DEFAULT_THEME_CONTROLLER =
            "miui.systemui.controlcenter.windowview.MiuiDefaultThemeControllerImpl";

    /** The stock threshold: a picture whose subject covers less than a fifth is refused. */
    private static final double DEPTH_THRESHOLD_STOCK = 0.2;
    /** Everything passes. */
    private static final double DEPTH_THRESHOLD_OPEN = 1.0;
    /** Saved lock screens, up from the stock twenty. */
    private static final int TEMPLATE_LIMIT = 100;
    /**
     * What the editor trims back to on its own, read out of this build.
     *
     * The ceiling is not a field anybody consults - see {@link #templateLimit} - it is this
     * number written into TemplateApiImpl.insertHistoryConfig, which on every save asks the
     * database how many saved lock screens there are and deletes the oldest until one more
     * fits.
     */
    private static final int TEMPLATE_LIMIT_STOCK = 20;

    // ------------------------------------------------------------------ SystemUI

    static void systemUi(ClassLoader cl) {
        openDepthThreshold(cl, "systemui", "sysui.depth.threshold");
        keyguardDepth(cl);
        thirdPartyWallpaperDepth(cl);
        softGlassTheme(cl);
        softGlassPlugin(cl, "systemui", "sysui");
        // Nothing found from SystemUI's own loader, which is the normal case: wait for the
        // plugin's loader to be built.
        if (!sPluginHooked) watchForPlugin();
        clockColon(cl, "systemui", "sysui.clock.colon", () -> sForceColon);
        mediaBarGlow(cl, () -> sBarGlow);
        coverUnlockFade(cl, Main::coverModeOn);
    }

    /**
     * The lock wallpaper faded out at an unlock while it holds the cover, rather than cut.
     *
     * The wallpaper process writes Settings.Secure miui_wallpaper_content_type: 0 when the lock
     * and home wallpapers are the same picture, 1 when they differ. The lock screen's copy of the
     * home wallpaper (CoverPush's repair) makes it 0, and on 0 MiuiKeyguardWallPaperManager hides
     * the lock wallpaper with anim=false - alpha straight to 0, since the home behind it is the
     * same picture. With the cover drawn on that wallpaper it is not: unlocking to the desktop,
     * the dark frosted cover vanished in one frame and the undimmed home wallpaper showed for
     * the frames before the launcher's own unlock dim took hold - a flash (2026-10-06, measured
     * off a recording: lock ~100, three frames at ~148, then the launcher's ~110). An app opened
     * from the lock screen covers the wallpaper, which is why only the desktop flashed.
     *
     * For differing wallpapers the OEM has the right animation already: animated, mode 1 is
     * KeyguardUnlockIAnimationRenderer's alpha 1 -> 0 over 350ms (280 on the fast animation
     * rate). So a hide asked for without animation becomes that one while cover mode is on.
     *
     * Mode 4 included: it is the fingerprint unlock from the always-on display, and the full-screen
     * one is the cover - the first build left it out as "the screen was off", and every unlock
     * from it still flashed, the mode 4 hide landing first and the exit's own hide finding the
     * wallpaper already gone. That call comes from BiometricUnlockControllerInjector with no
     * finished callback, so fading it holds nothing up.
     */
    private static void coverUnlockFade(ClassLoader cl, java.util.function.BooleanSupplier cover) {
        String what = "cover-unlock-fade";
        try {
            Class<?> cls = Xp.findClass(
                    "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager", cl);
            Method m = cls.getDeclaredMethod("updateKeyguardWallpaperStateAnim", boolean.class,
                    boolean.class, int.class, boolean.class, boolean.class, Runnable.class);
            Xp.hook("sysui.wallpaper.unlock_fade", m, chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean show = (Boolean) args[0];
                boolean anim = (Boolean) args[1];
                int mode = (Integer) args[2];
                if (!show && !anim && cover.getAsBoolean()) {
                    args[1] = Boolean.TRUE;
                    args[2] = 1;
                    Xp.log(TAG + "lock wallpaper hide faded for the cover (was mode " + mode + ")");
                }
                return chain.proceed(args);
            });
            ok(what);
        } catch (Throwable t) {
            failed(what, t);
        }
    }

    // The lock screen media card's progress bar, miuix's HyperProgressSeekBar. The island's media
    // card uses the same widget and leaves progressBarMode unset, so the widget asks the device
    // what it can do - DeviceUtils.getDeviceLevel() - and on this phone answers 2, the mode that
    // draws the bar with an AGSL runtime shader, glow and all. The lock screen's card names
    // Widget.HyperProgressSeekBar.Normal instead, and that style pins progressBarMode to 0: the
    // flat drawing, no shader, no glow. Nothing about the two cards' colours differs - both
    // styles carry the same drawable and the same alphas - so the mode is the whole of it.
    private static final String CLS_MEDIA_BAR = "miuix.miuixbasewidget.widget.HyperProgressSeekBar";

    /** The shader the widget loads at device level 2. Level 1 has a lighter one of its own. */
    private static final String SHADER_ENTRY = "music_player_tracker";

    /**
     * Lights the card's bar on the way in, so the view the OEM is about to bind is already the one
     * we want: progress, drag and the seek listener are all the OEM's, and none of them is ours to
     * re-attach.
     *
     * A constructor hook rather than anything later because the mode is read in the constructor
     * and the shader it picks is a final field, so an instance built flat cannot be upgraded into
     * the shader path by reflection - only its two fields can be filled in by hand, which is what
     * applyBarGlow does for the bar already on screen when the probe flips the switch.
     */
    private static void mediaBarGlow(ClassLoader cl, java.util.function.BooleanSupplier wanted) {
        String what = "media-bar-glow";
        try {
            Class<?> cls = Xp.findClass(CLS_MEDIA_BAR, cl);
            Xp.hookAllConstructors("sysui.media_bar.ctor", cls, chain -> {
                Object result = chain.proceed();
                if (wanted.getAsBoolean()) {
                    try {
                        applyBarGlow((android.view.View) chain.getThisObject());
                    } catch (Throwable t) {
                        // A bar left flat is a bar, not a broken lock screen.
                        Xp.log(TAG + "media bar glow skipped: " + t);
                    }
                }
                return result;
            });
            // A touch on a level 2 bar runs through the level 2 branch of onTouchEvent, and that
            // branch drives mHeadAlphaAnimator - the blob of light at the fill's edge, which
            // swells when the bar is taken hold of and springs back on release. A bar built at
            // level 0 has no such field at all, so that branch is a NullPointerException in
            // SystemUI's own thread; buildHeadAnimator below is the attempt to give it one.
            //
            // When that attempt did not take, the drag runs as level 0 instead - the path this
            // instance was built and fully set up for, at the cost of the press feedback, which
            // the level 0 path spends on a progress alpha the shader never reads. Restored in a
            // finally, because a touch that throws must not leave the bar drawing the flat way
            // for good.
            Xp.hookAll("sysui.media_bar.touch", cls, "onTouchEvent", chain -> {
                Object self = chain.getThisObject();
                if (!sGlowBars.containsKey(self) || hasHeadAnimator(self)) return chain.proceed();
                Xp.setObjectField(self, "mDeviceLevel", 0);
                try {
                    return chain.proceed();
                } finally {
                    Xp.setObjectField(self, "mDeviceLevel", 2);
                }
            });
            ok(what);
        } catch (Throwable t) {
            failed(what, t);
        }
    }

    /** The bars this module has put into the shader mode, for the touch guard above. */
    private static final java.util.Map<android.view.View, Boolean> sGlowBars =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, Boolean>());

    /**
     * Whether a touch on this bar can run the level 2 path: it animates the head through this
     * pair, so both halves have to be there. Answered per touch rather than remembered, because
     * the pair is what the touch path dereferences and nothing else is worth trusting.
     */
    private static boolean hasHeadAnimator(Object bar) {
        try {
            Object target = Xp.getObjectField(bar, "mHeadAlphaAnimator");
            return target != null && Xp.getObjectField(target, "mFolmeImpl") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The head animator a bar built at level 1 or 2 is given in its constructor, built here for a
     * bar that was built flat.
     *
     * ProgressAnimTarget is the widget's own FolmeObject (see newAnimTarget for how one is had),
     * and Folme hands it the implementation the touch path animates through that interface -
     * itself the reason this is not simply a field write.
     *
     * Best effort by design. Its absence costs the press feedback and nothing else, because the
     * touch guard above stands aside only when this is known to have worked, and a failure here
     * leaves the drag on the level 0 path rather than on a null dereference.
     */
    private static void buildHeadAnimator(android.view.View bar) {
        try {
            ClassLoader cl = bar.getClass().getClassLoader();
            Class<?> targetCls = Xp.findClass(
                    "miuix.miuixbasewidget.widget.HyperProgressSeekBar$ProgressAnimTarget", cl);
            Object target = newAnimTarget(targetCls, bar);
            Xp.findMethodExact(
                            Xp.findClass("miuix.animation.Folme", cl), "use",
                            Xp.findClass("miuix.animation.FolmeObject", cl))
                    .invoke(null, target);
            Object impl = Xp.getObjectField(target, "mFolmeImpl");
            if (impl == null) {
                Xp.log(TAG + "head animator built but Folme gave it no impl");
                return;
            }
            // The resting value the constructor sets, and not optional: a head alpha Folme has
            // never been told starts its first spring from nothing, and the shader it feeds
            // (uHeadGlowAlpha) drew no bar at all from the first tap on - measured 2026-10-23.
            // setTo is varargs, setTo(Object[]), so it is found and called as that; the old
            // two-argument callMethod matched no overload and failed without a word. Done before
            // the field is written, so a build where it cannot be done keeps the level 0 drag.
            java.lang.reflect.Method setTo = null;
            for (java.lang.reflect.Method m : impl.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if ("setTo".equals(m.getName()) && p.length == 1 && p[0] == Object[].class) {
                    setTo = m;
                    break;
                }
            }
            if (setTo == null) {
                Xp.log(TAG + "head animator built but its impl has no setTo(Object...)");
                return;
            }
            setTo.invoke(impl, (Object) new Object[]{
                    Xp.getObjectField(bar, "PROPERTY_HEAD_ALPHA"), Float.valueOf(1.0f)});
            Xp.setObjectField(bar, "mHeadAlphaAnimator", target);
        } catch (Throwable t) {
            Xp.log(TAG + "head animator not built, drag falls back to level 0: " + t);
        }
    }

    /**
     * A ProgressAnimTarget, however this build declares it. It is a static class with nothing to
     * construct - one field, set by Folme.use - and never did take the bar, which is why the
     * lookup for that constructor failed on every build. The control centre plugin's copy
     * (18.3.2.40.1) has a private no-argument one. SystemUI's (17.03 on OS4.0.18 and OS4.0.24) has
     * none at all: R8 took the empty one out, and the widget itself does new-instance then
     * Object.<init>. So the last resort is the same thing done through Unsafe, an instance with no
     * constructor run, which is all the widget's own code gives it.
     */
    private static Object newAnimTarget(Class<?> targetCls, android.view.View bar)
            throws Exception {
        for (java.lang.reflect.Constructor<?> c : targetCls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 0) {
                c.setAccessible(true);
                return c.newInstance();
            }
            if (p.length == 1 && p[0].isInstance(bar)) {
                c.setAccessible(true);
                return c.newInstance(bar);
            }
        }
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeCls.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return unsafeCls.getMethod("allocateInstance", Class.class)
                .invoke(f.get(null), targetCls);
    }

    /**
     * Puts one bar into the shader mode, by hand, and answers with a line about what happened.
     *
     * The three steps are the three things the constructor does for level 2 and skips for level 0:
     * build the shader, hand it to the field, then run initShaderConfig, which decodes the head
     * bitmap and pushes every uniform - without it the paint has no shader and the bar draws
     * nothing at all. onLayout is where the track is sized for this mode, and it has already run,
     * so the uniforms would stay at their defaults until the next layout pass without it.
     *
     * The answer is a string because module logs are not readable on this device: `op seekglow`
     * hands it back, and "FAILED ..." there is the only way a final-field write that this platform
     * refuses would ever be seen.
     */
    /** HyperProgressSeekBar's constructor values, in dp, before initShaderConfig converts them. */
    private static final float[] TRACK_POS_DP = {12f, 19f};
    private static final float[] TRACK_SIZE_DP = {220f, 6f};

    static String applyBarGlow(android.view.View bar) {
        try {
            // The island's copies arrive with the mode unset and already run the device's own
            // level; only the card's bar names a mode, and only that one is ours to light.
            Object mode = Xp.getObjectField(bar, "mProgressMode");
            if (!(mode instanceof Integer) || ((Integer) mode) != 0) return "not the card's bar";
            int level = (Integer) Xp.getObjectField(bar, "mDeviceLevel");
            if (level != 0) return "already level " + level;
            android.content.res.Resources res = bar.getContext().getResources();
            int id = res.getIdentifier(SHADER_ENTRY, "raw", "com.android.systemui");
            if (id == 0) return "no raw/" + SHADER_ENTRY + " in this build";
            String src = (String) Xp.callMethod(bar, "loadShader", id, res);
            android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(src);
            Xp.setObjectField(bar, "runtimeShader", shader);
            Xp.setObjectField(bar, "mDeviceLevel", 2);
            // initShaderConfig converts the track's place and thickness from dp to px in place,
            // on arrays the constructor filled - so a second call on one bar converts pixels
            // again, and a bar lit, cleared and lit again (#49) drew its track three times
            // too low, off its own canvas: gone. Put back to the constructor's dp first.
            float[] pos = (float[]) Xp.getObjectField(bar, "uTrackPosition");
            float[] track = (float[]) Xp.getObjectField(bar, "uTrackSize");
            pos[0] = TRACK_POS_DP[0];
            pos[1] = TRACK_POS_DP[1];
            track[0] = TRACK_SIZE_DP[0];
            track[1] = TRACK_SIZE_DP[1];
            Xp.callMethod(bar, "initShaderConfig");
            // What onSizeChanged and onLayout hand the shader at level 2, which a bar already on
            // screen has long been through at level 0 and will not go through again.
            if (bar.getWidth() > 0 && bar.getHeight() > 0) {
                float[] canvas = (float[]) Xp.getObjectField(bar, "uTrackCanvasSize");
                canvas[0] = bar.getWidth();
                canvas[1] = bar.getHeight();
                shader.setFloatUniform("uTrackCanvasSize", canvas);
                shader.setFloatUniform("uResolution", canvas);
                track[0] = bar.getWidth() - pos[0] * 2f;
                shader.setFloatUniform("uTrackSize", track);
                shader.setIntUniform("uIsRtl",
                        bar.getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL ? 1 : 0);
            }
            bar.requestLayout();
            bar.invalidate();
            buildHeadAnimator(bar);
            // Only now is the bar something the touch guard has to cover: it draws as level 2
            // without having been built as it.
            sGlowBars.put(bar, Boolean.TRUE);
            return hasHeadAnimator(bar) ? "level 2 + head" : "level 2, drag as level 0";
        } catch (Throwable t) {
            return "FAILED " + t;
        }
    }

    /**
     * The glow taken off a bar this lit (applyBarGlow), so switching it off does not wait for the
     * next card (#49).
     *
     * A bar built by the OEM at level 2 cannot go back - its shader is what it was built for - but
     * none of ours is one: every bar lit here was built flat and set up in full for level 0, the
     * draw and the drag alike, so putting the level back is all it takes. The shader and the head
     * animator left in its fields are never read at level 0.
     */
    static String clearBarGlow(android.view.View bar) {
        try {
            if (sGlowBars.remove(bar) == null) return "not lit here";
            Xp.setObjectField(bar, "mDeviceLevel", 0);
            bar.requestLayout();
            bar.invalidate();
            return "level 0";
        } catch (Throwable t) {
            return "FAILED " + t;
        }
    }

    /**
     * The editor and the always-on display, which judge a wallpaper's depth all over again with
     * their own copies of the same classes.
     */
    static void aod(ClassLoader cl) {
        openDepthThreshold(cl, "aod", "aod.depth.threshold");
        hierarchyBypass(cl);
        aodWallpaperDepth(cl);
        templateLimit(cl);
        historyTrimLimit(cl);
        // The always-on clock is drawn in this process, by the same clock library, so the same
        // hook works - it just has to ask a different place what the switch says.
        clockColon(cl, "aod", "aod.clock.colon", HyperTweaks::aodColonWanted);
        reportTo("/data/user/0/com.miui.aod/cache/mc_tweaks.txt");
    }

    /**
     * The same report, for a process the probe cannot reach.
     *
     * The receiver lives in SystemUI and nowhere else, so what the editor's hooks did would
     * otherwise be unknowable - INFO logs are not readable on this device. The process's own
     * cache directory is the one place it is certain to be allowed to write.
     */
    private static void reportTo(String path) {
        try {
            java.io.File out = new java.io.File(path);
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try {
                os.write(describe().getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            Xp.log(TAG + "could not write the editor report: " + t);
        }
    }

    /** The control centre, where the soft glass a global theme switched off actually shows. */
    static void plugin(ClassLoader cl) {
        softGlassPlugin(cl, "plugin", "plugin");
    }

    // ------------------------------------------------------------------ depth

    /**
     * The subject-size test, opened all the way up.
     *
     * Order is the whole trick, and it is HyperChanger's: the threshold object is built in the
     * evaluator's static initializer, so the constructor has to be hooked before anything
     * touches the evaluator class. {@link Xp#findClass} loads without initialising, which is what
     * makes that possible; reading the static field afterwards is what runs the initialiser, by
     * which time the hook is in place. The field is then overwritten as well, for the build whose
     * initialiser has already run.
     */
    private static void openDepthThreshold(ClassLoader cl, String where, String id) {
        try {
            Class<?> thresholdCls = Xp.findClass(CLS_DEPTH_THRESHOLD, cl);
            Class<?> evaluatorCls = Xp.findClass(CLS_DEPTH_EVALUATOR, cl);
            Constructor<?> ctor = thresholdCls.getDeclaredConstructor(double.class);
            ctor.setAccessible(true);
            Xp.hook(id, ctor, chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 1 && args[0] instanceof Double
                        && (Double) args[0] == DEPTH_THRESHOLD_STOCK) {
                    args[0] = DEPTH_THRESHOLD_OPEN;
                    sOpenedStock = true;
                }
                return chain.proceed(args);
            });
            Field image = evaluatorCls.getDeclaredField("IMAGE_THRESHOLD");
            image.setAccessible(true);
            final Object threshold = image.get(null);
            final Field rate = thresholdCls.getDeclaredField("rate");
            rate.setAccessible(true);
            // Given back at retirement: an inert successor writes nothing, and the open threshold
            // would otherwise outlive the module in this process. Reading the field is what runs
            // the initialiser, through the hook above - so an OPEN read here is the hook's own
            // work, and what it replaced was the stock value.
            double read = rate.getDouble(threshold);
            final double had = read == DEPTH_THRESHOLD_OPEN && sOpenedStock
                    ? DEPTH_THRESHOLD_STOCK : read;
            rate.setDouble(threshold, DEPTH_THRESHOLD_OPEN);
            Gen.swap(threshold, "DepthEvaluator.IMAGE_THRESHOLD.rate", had, () -> {
                try {
                    rate.setDouble(threshold, had);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            });
            ok("depth-threshold:" + where);
        } catch (Throwable t) {
            failed("depth-threshold:" + where, t);
        }
    }

    /**
     * The editor's own answer to "may this wallpaper have depth", which is asked separately from
     * the threshold above and refuses on its own.
     *
     * The user's switch still decides: both hooks read the OEM's own isUserOpenHierarchy flag, so
     * turning the effect off in the editor still turns it off.
     */
    private static void hierarchyBypass(ClassLoader cl) {
        try {
            Class<?> controller = Xp.findClass(CLS_HIERARCHY_AVOID, cl);
            Xp.hookAll("sysui.depth.hierarchy_enable", controller, "isHierarchyEnable", chain -> {
                Object result = chain.proceed();
                return userOpenedHierarchy(chain.getThisObject()) ? Boolean.TRUE : result;
            });
            Xp.hookAll("sysui.depth.hierarchy_change", controller, "onHierarchyEnableChange", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 2 && userOpenedHierarchy(chain.getThisObject())) {
                    args[0] = Boolean.TRUE;
                }
                return chain.proceed(args);
            });
            ok("depth-hierarchy");
        } catch (Throwable t) {
            failed("depth-hierarchy", t);
        }
    }

    private static boolean userOpenedHierarchy(Object instance) {
        try {
            return (Boolean) Xp.getObjectField(instance, "isUserOpenHierarchy");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The live keyguard's side: the avoid state that decides whether the clock is drawn behind
     * the subject, and the panel flag that decides whether the depth layout is used at all.
     */
    private static void keyguardDepth(ClassLoader cl) {
        Class<?> interactorCls;
        try {
            interactorCls = Xp.findClass(CLS_KG_DEPTH_INTERACTOR, cl);
        } catch (Throwable t) {
            failed("depth-interactor", t);
            return;
        }
        try {
            Xp.hookAll("sysui.depth.avoid_status", interactorCls, "updateAvoidStatus", chain -> {
                clearAvoidState(chain.getThisObject());
                // Not proceeded with: the OEM's own recomputation is exactly what puts the
                // avoidance back, so it is replaced rather than followed.
                return null;
            });
            ok("depth-avoid-status");
        } catch (Throwable t) {
            failed("depth-avoid-status", t);
        }
        try {
            for (Method m : interactorCls.getDeclaredMethods()) {
                if (!"setDepthTransitionAlpha".equals(m.getName()) || m.getParameterCount() != 3) {
                    continue;
                }
                m.setAccessible(true);
                Xp.hook("sysui.depth.alpha", m, chain -> {
                    clearAvoidState(chain.getThisObject());
                    return chain.proceed();
                });
                ok("depth-alpha");
            }
        } catch (Throwable t) {
            failed("depth-alpha", t);
        }
        try {
            Class<?> panelCls = Xp.findClass(CLS_KG_PANEL, cl);
            Field depthEnable = panelCls.getDeclaredField("depthEffectEnable");
            depthEnable.setAccessible(true);
            Field interactorField = panelCls.getDeclaredField("keyguardDepthInteractor");
            interactorField.setAccessible(true);
            Field actualDisplay = interactorField.getType().getDeclaredField("isActualDisplayDepth");
            actualDisplay.setAccessible(true);
            Field enableInner = interactorField.getType().getDeclaredField("depthEffectEnableInner");
            enableInner.setAccessible(true);
            Method updateElements = panelCls.getMethod("updateKeyguardElementsVisibility");
            Field videoEnable = interactorField.getType().getDeclaredField("depthVideoEnable");
            videoEnable.setAccessible(true);
            Method updateCrop = interactorField.getType().getMethod("updateDeductedImageView");
            Xp.hookAll("sysui.depth.show_state", panelCls, "updateShowDepthState", chain -> {
                Object panel = chain.getThisObject();
                sKgPanel = panel;
                Object interactor = interactorField.get(panel);
                if (videoEnable.getBoolean(interactor)) {
                    // A depth VIDEO is left to the OEM. Its subject is the video's own cut-out
                    // surface; the two flags forced below are the STILL depth path, which decodes
                    // the theme's crop of the video's last frame into deducted_image_view and
                    // leaves it standing over the video - and over the AOD, where nothing on the
                    // video path ever fades it: a sheep and a tuft of grass on a black screen.
                    // Only the OEM's own value (false: the video type is not 2) goes back, and
                    // the crop an earlier forced pass decoded is cleared the OEM's way.
                    if (enableInner.getBoolean(interactor)) {
                        depthEnable.setBoolean(panel, false);
                        enableInner.setBoolean(interactor, false);
                        updateCrop.invoke(interactor);
                        Xp.log(TAG + "depth video: still depth path handed back");
                    }
                    return chain.proceed();
                }
                depthEnable.setBoolean(panel, true);
                enableInner.setBoolean(interactor, true);
                Object result = chain.proceed();
                if (!actualDisplay.getBoolean(interactor)) {
                    actualDisplay.setBoolean(interactor, true);
                    updateElements.invoke(panel);
                }
                return result;
            });
            ok("depth-panel-state");
        } catch (Throwable t) {
            failed("depth-panel-state", t);
        }
        // The panel's first pass can come before the interactor knows the wallpaper is a video,
        // and a change of wallpaper to one comes after. updateVideoDepthSurface is where the
        // interactor finds out, so the panel is asked again from there.
        try {
            Method showDepth = Xp.findClass(CLS_KG_PANEL, cl).getMethod("updateShowDepthState");
            Field videoEnable = interactorCls.getDeclaredField("depthVideoEnable");
            videoEnable.setAccessible(true);
            Field enableInner = interactorCls.getDeclaredField("depthEffectEnableInner");
            enableInner.setAccessible(true);
            Xp.hookAll("sysui.depth.video_surface", interactorCls, "updateVideoDepthSurface", chain -> {
                Object result = chain.proceed();
                Object interactor = chain.getThisObject();
                Object panel = sKgPanel;
                if (panel != null && videoEnable.getBoolean(interactor)
                        && enableInner.getBoolean(interactor)) {
                    Gen.post(() -> {
                        try {
                            showDepth.invoke(panel);
                        } catch (Throwable t) {
                            Xp.w(TAG + "depth video hand-back failed: " + t);
                        }
                    });
                }
                return result;
            });
            ok("depth-video-handback");
        } catch (Throwable t) {
            failed("depth-video-handback", t);
        }
    }

    /** The keyguard panel controller, as its updateShowDepthState last saw it. */
    private static volatile Object sKgPanel;

    /** The StateFlow the interactor keeps the avoidance in; set back to "nothing to avoid". */
    private static void clearAvoidState(Object interactor) {
        try {
            Object state = Xp.getObjectField(interactor, "_avoidState");
            for (Method m : state.getClass().getMethods()) {
                if ("updateState$1".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(state, null, false);
                    return;
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "could not clear the depth avoid state: " + t);
        }
    }

    /**
     * Wallpapers the system did not ship, which are refused before any threshold is consulted:
     * the wallpaper says it has no subject, and its per-layout table says depth is off everywhere.
     */
    private static void thirdPartyWallpaperDepth(ClassLoader cl) {
        try {
            Class<?> infoCls = Xp.findClass(CLS_WALLPAPER_INFO, cl);
            Xp.hookAll("sysui.depth.support_subject", infoCls, "getSupportSubject", chain -> Boolean.TRUE);
            Class<?> hierarchyCls = Xp.findClass(CLS_HIERARCHY_ENABLE, cl);
            // Nine booleans, one per layout, and all of them yes. Built once: the getter is
            // called per frame in places, and a new instance each time would be litter.
            Constructor<?> ctor = hierarchyCls.getDeclaredConstructor(
                    boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class);
            ctor.setAccessible(true);
            final Object all = ctor.newInstance(
                    true, true, true, true, true, true, true, true, true);
            Xp.hookAll("sysui.depth.large_screen_hierarchy", infoCls, "getLargeScreenHierarchyEnable", chain -> all);
            ok("depth-wallpaper:systemui");
        } catch (Throwable t) {
            failed("depth-wallpaper:systemui", t);
        }
    }

    /** The same refusal again, in the editor's own model of a wallpaper. */
    private static void aodWallpaperDepth(ClassLoader cl) {
        try {
            Class<?> infoCls = Xp.findClass(CLS_AOD_WALLPAPER_INFO, cl);
            Xp.hookAll("aod.depth.support_subject", infoCls, "getSupportSubject", chain -> Boolean.TRUE);
            Class<?> hierarchyCls = Xp.findClass(CLS_AOD_HIERARCHY_ENABLE, cl);
            Constructor<?> ctor = hierarchyCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            final Object all = ctor.newInstance();
            Xp.hookAll("aod.depth.large_screen_hierarchy", infoCls, "getLargeScreenHierarchyEnable", chain -> all);
            ok("depth-wallpaper:aod");
        } catch (Throwable t) {
            failed("depth-wallpaper:aod", t);
        }
    }

    /**
     * The editor's ceiling on saved lock screens.
     *
     * The model reads its own field every time the list is built, so writing it once per
     * construction is enough and there is nothing to keep in sync afterwards.
     */
    private static void templateLimit(ClassLoader cl) {
        try {
            Class<?> modelCls = Xp.findClass(CLS_CROSS_LIST_MODEL, cl);
            Field max = modelCls.getDeclaredField("_maxTemplateCount");
            max.setAccessible(true);
            Xp.hookAllConstructors("aod.editor.template_limit", modelCls, chain -> {
                Object result = chain.proceed();
                try {
                    max.setInt(chain.getThisObject(), TEMPLATE_LIMIT);
                } catch (Throwable t) {
                    Xp.log(TAG + "could not raise the template limit: " + t);
                }
                return result;
            });
            ok("template-limit");
        } catch (Throwable t) {
            failed("template-limit", t);
        }
    }

    /**
     * The ceiling that is actually enforced.
     *
     * Saving a lock screen runs `TemplateApiImpl.insertHistoryConfig`, which asks the database
     * how many are stored and, at twenty or more, deletes the oldest until one more fits. There
     * is no refusal and no message - the twenty-first save silently takes the first one with it,
     * which is why raising `_maxTemplateCount` does nothing: nothing reads that field.
     *
     * The count is what that test is made of, so the count is what moves. Shifting it down by
     * the difference turns the OEM's own "20 or more" into "100 or more", and the number of
     * rows it then asks to delete comes out exactly right - it trims to one below the new
     * ceiling, the same shape the stock code has. The OEM's trimming is left in place rather
     * than replaced: a limit that never deletes anything is a database that grows forever.
     *
     * Safe to move because this DAO method has one caller in the whole app, which is that test.
     * (The other `getHistoryCount` in this package belongs to the old AOD history and is a
     * different class entirely.)
     */
    private static void historyTrimLimit(ClassLoader cl) {
        try {
            Class<?> daoCls = Xp.findClass(CLS_TEMPLATE_HISTORY_DAO, cl);
            final int shift = TEMPLATE_LIMIT - TEMPLATE_LIMIT_STOCK;
            Xp.hookAll("aod.editor.history_count", daoCls, "getHistoryCount", chain -> {
                Object real = chain.proceed();
                if (!(real instanceof Integer)) return real;
                int shifted = (Integer) real - shift;
                return shifted < 0 ? 0 : shifted;
            });
            ok("history-limit");
        } catch (Throwable t) {
            failed("history-limit", t);
        }
    }

    // ------------------------------------------------------------------ clock colon

    /** The big clock's colon, which some styles drop. Off unless the user asked for it. */
    private static void clockColon(ClassLoader cl, String where, String id,
                                   java.util.function.BooleanSupplier wanted) {
        try {
            Class<?> beanCls = Xp.findClass(CLS_CLOCK_BEAN, cl);
            Xp.hookAll(id, beanCls, "isColonShow",
                    chain -> wanted.getAsBoolean() ? Boolean.TRUE : chain.proceed());
            ok("clock-colon:" + where);
        } catch (Throwable t) {
            failed("clock-colon:" + where, t);
        }
    }

    // ------------------------------------------------------------------ the colon, off in AOD

    /**
     * Where the always-on display reads the colon switch.
     *
     * The switch lives in SystemUI's memory and in a file inside SystemUI's data directory,
     * and the always-on display is a different app under a different uid - it can reach
     * neither. Settings.Global is the one place both can see: SystemUI holds
     * WRITE_SECURE_SETTINGS and writes the flag there whenever it changes, and this process
     * only ever reads it.
     */
    private static final String GLOBAL_COLON = "hypermusiccover_force_colon";

    /** Mirrors the switch for the other process. Called from SystemUI, where the switch lives. */
    static void publishColon(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), GLOBAL_COLON, sForceColon ? 1 : 0);
        } catch (Throwable t) {
            // No permission, or a build that refuses the write: the always-on clock keeps the
            // colon it had. The lock screen's own colon does not go through here.
            Xp.log(TAG + "could not publish the colon flag: " + t);
        }
    }

    /** null until the flag has been read once; kept current by the observer below. */
    private static volatile Boolean sAodColon;

    /**
     * Read once and then watched, rather than asked per call: `isColonShow` is consulted while
     * the clock is being laid out, and a settings read is a call into system_server.
     *
     * The context is resolved lazily because there is none when the hooks are installed - the
     * process is still being built at that point.
     */
    private static boolean aodColonWanted() {
        Boolean cached = sAodColon;
        if (cached != null) return cached;
        try {
            android.content.Context ctx = (android.content.Context) Class
                    .forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            // No application yet: answer "off" without caching, so the next call tries again.
            if (ctx == null) return false;
            final android.content.ContentResolver cr = ctx.getContentResolver();
            boolean on = android.provider.Settings.Global.getInt(cr, GLOBAL_COLON, 0) != 0;
            sAodColon = on;
            Gen.observer(cr,
                    android.provider.Settings.Global.getUriFor(GLOBAL_COLON),
                    false,
                    new android.database.ContentObserver(Gen.handler()) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sAodColon = android.provider.Settings.Global
                                    .getInt(cr, GLOBAL_COLON, 0) != 0;
                        }
                    });
            return on;
        } catch (Throwable t) {
            Xp.log(TAG + "could not read the colon flag: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ soft glass

    /**
     * A global theme turns the soft glass off system-wide, and nothing puts it back.
     *
     * The answer is the same in both halves: whatever is asked, the theme is the default one.
     * The static flag is written as well as hooked, because a configuration change recomputes it
     * from the theme and the hooks below only cover the paths that ask.
     */
    private static void softGlassTheme(ClassLoader cl) {
        try {
            Class<?> materialUtils = Xp.findClass(CLS_MATERIAL_UTILS, cl);
            Xp.hookAll("sysui.glass.theme_changed", materialUtils, "onDefaultThemeChanged", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 1) args[0] = Boolean.TRUE;
                return chain.proceed(args);
            });
            ok("glass-theme");
        } catch (Throwable t) {
            failed("glass-theme", t);
        }
        try {
            Class<?> themeUtils = Xp.findClass(CLS_SYSUI_THEME_UTILS, cl);
            Field flag = themeUtils.getDeclaredField("sDefaultSysUiTheme");
            flag.setAccessible(true);
            forceStatic(flag, "ThemeUtils.sDefaultSysUiTheme", false);
            Class<?> config = Xp.findClass(CLS_CONFIG_CONTROLLER, cl);
            Xp.hookAll("sysui.glass.config_changed", config, "onConfigurationChanged", chain -> {
                Object result = chain.proceed();
                try {
                    flag.setBoolean(null, true);
                } catch (Throwable ignored) {
                    // A flag we cannot set is glass that goes away, not a broken shade.
                }
                return result;
            });
            ok("glass-theme-flag");
        } catch (Throwable t) {
            failed("glass-theme-flag", t);
        }
    }

    /**
     * The control centre plugin, which caches the same answer in three more places.
     *
     * Tried from SystemUI's own loader first because the plugin is usually loaded into it; the
     * plugin's package callback tries again with its own, and whichever runs second finds the
     * hooks already installed and does nothing.
     */
    private static volatile boolean sPluginHooked;

    /**
     * The control centre plugin is a second APK with a class loader of its own, built inside
     * SystemUI long after this module's hooks go in - and it never arrives as a package of its
     * own, so `miui.systemui.plugin` in the scope list buys nothing. Trying SystemUI's loader at
     * startup, which is what this used to do alone, could only ever say "not visible": the
     * plugin had not been loaded yet and its classes are in no parent of that loader.
     *
     * So the arrival is what gets watched. Every class loader in the process passes through
     * BaseDexClassLoader's constructor, of which there are a handful in a process's whole life -
     * compared with hooking `loadClass`, which is the same watch run tens of thousands of times.
     * The first one that can see the plugin's ThemeUtils is the plugin's, and once its hooks are
     * in the body below does nothing but read a flag.
     */
    private static void watchForPlugin() {
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            for (Constructor<?> ctor : base.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Xp.hook("sysui.glass.loader_watch", ctor, chain -> {
                    Object result = chain.proceed();
                    if (!sPluginHooked) {
                        Object loader = chain.getThisObject();
                        if (loader instanceof ClassLoader) {
                            // The plugin's loader, but still SystemUI's process: the id
                            // prefix is the process, not where the loader came from.
                            softGlassPlugin((ClassLoader) loader, "plugin", "sysui");
                        }
                    }
                    return result;
                });
            }
            ok("glass-plugin-watch");
        } catch (Throwable t) {
            failed("glass-plugin-watch", t);
        }
    }

    /**
     * A hot-reloaded generation's half of {@link #watchForPlugin()}: the plugin's loader exists
     * already, so the constructor watch will not fire again. Re-running the soft install with
     * that loader replaces the old hooks by id.
     */
    static void adoptPlugin(ClassLoader cl) {
        softGlassPlugin(cl, "adopt", "sysui");
    }

    private static void softGlassPlugin(ClassLoader cl, String where, String idPrefix) {
        if (sPluginHooked) return;
        Class<?> themeUtils;
        try {
            themeUtils = Xp.findClass(CLS_PLUGIN_THEME_UTILS, cl);
        } catch (Throwable t) {
            // Not an error, and not worth a line each time: most loaders are not the plugin's.
            // Only the startup attempt records anything, so the report says what was tried.
            if (!"plugin".equals(where)) STATUS.put("glass-plugin", "not visible from " + where);
            return;
        }
        try {
            for (String name : new String[] {"getDefaultPluginTheme", "getDefaultSysUiTheme"}) {
                try {
                    Xp.hookAll(idPrefix + ".glass.default_get." + Xp.slug(name), themeUtils, name,
                            chain -> Boolean.TRUE);
                } catch (Throwable ignored) {
                    // Not on this build; the other answers still stand.
                }
            }
            for (String name : new String[] {"updateDefaultPluginTheme", "updateDefaultSysUiTheme"}) {
                try {
                    Xp.hookAll(idPrefix + ".glass.default_update." + Xp.slug(name), themeUtils, name,
                            chain -> {
                                Object result = chain.proceed();
                                forceThemeFlags(themeUtils);
                                return result;
                            });
                } catch (Throwable ignored) {
                    // As above.
                }
            }
            forceThemeFlags(themeUtils);
            pluginMaterialGuards(
                    themeUtils.getClassLoader() == null ? cl : themeUtils.getClassLoader(),
                    idPrefix);
            sPluginHooked = true;
            ok("glass-plugin:" + where);
        } catch (Throwable t) {
            failed("glass-plugin:" + where, t);
        }
    }

    private static void forceThemeFlags(Class<?> themeUtils) {
        for (String name : new String[] {"defaultPluginTheme", "defaultSysUiTheme"}) {
            try {
                Field f = themeUtils.getDeclaredField(name);
                f.setAccessible(true);
                // Written from the plugin's watch and its update hooks, after install: a dry run
                // does not come back through here, so it must not give these back either.
                forceStatic(f, "plugin ThemeUtils." + name, true);
            } catch (Throwable ignored) {
                // One of the two is missing on some builds; the other still holds.
            }
        }
    }

    /** Set when the threshold hook turned a stock value open; see openDepthThreshold. */
    private static volatile boolean sOpenedStock;

    /**
     * The static booleans written so far, each recorded with its value from before the first.
     * By equality, not identity: getDeclaredField hands out a new Field every call, and the
     * plugin's update hooks look theirs up each time.
     */
    private static final java.util.Set<Field> FORCED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Sets a static boolean to true, recording the first time what it held so the retirement
     * gives it back. The hooks that write it again later find it recorded already.
     */
    private static void forceStatic(final Field f, String what, boolean outlivesDryRun)
            throws IllegalAccessException {
        if (FORCED.add(f)) {
            final boolean had = f.getBoolean(null);
            Gen.swap(f.getDeclaringClass(), what, had, () -> {
                try {
                    f.setBoolean(null, had);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }, outlivesDryRun);
        }
        f.setBoolean(null, true);
    }

    /** Newer builds keep their own copy of the answer; these are the two that ask. */
    private static void pluginMaterialGuards(ClassLoader cl, String idPrefix) {
        try {
            Class<?> blurCompat = Xp.findClass(CLS_MI_BLUR_COMPAT, cl);
            Xp.hookAll(idPrefix + ".glass.blur_compat",
                    blurCompat, "getBackgroundMaterialOpenedInDefaultTheme", chain -> Boolean.TRUE);
        } catch (Throwable t) {
            failed("glass-blur-compat", t);
        }
        try {
            Class<?> controller = Xp.findClass(CLS_DEFAULT_THEME_CONTROLLER, cl);
            Xp.hookAll(idPrefix + ".glass.default_theme_controller",
                    controller, "isDefaultTheme", chain -> Boolean.TRUE);
        } catch (Throwable t) {
            failed("glass-default-theme-controller", t);
        }
    }
}
