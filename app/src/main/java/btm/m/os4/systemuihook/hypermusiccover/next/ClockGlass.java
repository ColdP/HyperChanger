// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.view.ViewGroup;
import android.view.View;
import java.lang.reflect.Method;

/**
 * The liquid-glass clock styles: which styles draw glass, the glass morph that follows the
 * collapse, the AOD's captured glass and its brightness, and the guard that keeps HyperLight
 * from clobbering it.
 *
 * Carved out of Main as it was (2026-10-14, after MediaCard): every member moved whole with its
 * comments, references across the line prefixed with Main. or ClockGlass., nothing else changed.
 */
final class ClockGlass {
    private ClockGlass() {}

    /**
     * Liquid-glass -> filled morph across the collapse. AllInOneBase.updateGlassValue(float)
     * writes glassData[36] and fades the glyph interior from fully transparent (the liquid
     * glass look, which degenerates into hairlines once the clock is small) to solid. NaN = off.
     */
    static volatile float sGlassV0 = Float.NaN, sGlassV1 = Float.NaN;

    static volatile float sAppliedGlassV = Float.NaN;

    /**
     * The cover's entry morph has reached the solid end. While it stands, the glass value is
     * held there against spurious collapse-progress frames - see applyGlassMorph. Cleared
     * wherever the glass state itself is.
     */
    static volatile boolean sGlassSettled;

    /** Armed once the HyperLight (统一柔光玻璃) counter-hook is installed; see armMiGlassGuard(). */
    static volatile boolean sMiGlassGuardArmed;

    /** How many times the counter-hook actually fired on a clock glyph, for the one-line proof. */
    static volatile int sMiGlassGuardHits;

    /** Arms the liquid-glass -> filled morph across the collapse. See applyGlassMorph(). */
    static void armGlassMorph() {
        sGlassV0 = 0f;
        sGlassV1 = Main.sGlassEnd;
        sAppliedGlassV = Float.NaN;
        // Each entry morphs from the start again. Left set by the previous cover, the hold in
        // applyGlassMorph() jumped every later entry to the solid end on its first frame.
        sGlassSettled = false;
    }

    /** Puts the glass back where the collapse found it, and stops morphing it. */
    static void restoreGlass() {
        if (Float.isNaN(sGlassV0)) return;
        float back = sGlassV0;
        sGlassV0 = sGlassV1 = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sGlassSettled = false;
        Main.callOnClockViews("updateGlassValue", "f", back, 0, false);
    }

    /** The clock's glass as the screen fell asleep: r, g, b and the fill. See captureAodGlass. */
    static final float[] sAodCoverGlass = new float[4];

    static volatile boolean sAodCoverGlassSet;

    /**
     * Remembers what colour and fill the clock's glyphs were in as the screen falls asleep, for a
     * doze that keeps the cover's clock.
     *
     * The doze repaints those glyphs from the wallpaper's palette, and in cover mode the wallpaper
     * is the album art - which is what turned the always-on clock gold, and is why an ordinary
     * doze is held at a neutral instead. A held doze is meant to be the lock screen's clock
     * carried into sleep, so it is held at the lock screen's own values. Called from toAod(), the
     * last moment they are still the lock screen's: the doze inks its own over them a frame later.
     */
    static void captureAodGlass() {
        sAodCoverGlassSet = false;
        for (View root : Main.clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null || t.getVisibility() != View.VISIBLE) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    sAodCoverGlass[0] = g[11];
                    sAodCoverGlass[1] = g[12];
                    sAodCoverGlass[2] = g[13];
                    // glassData[36] carries the fill our own morph last pushed, which is what the
                    // lock screen is drawn with - not sAppliedGlassV, which is the value asked
                    // for rather than the one that landed.
                    sAodCoverGlass[3] = g[36];
                    sAodCoverGlassSet = true;
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Puts the glass clock's dark/light switch where it belongs now: the cover's in cover mode,
     * the OEM's own reading of the wallpaper out of it. Needed at the two moments nothing of the
     * OEM's re-sends it - a wake in cover mode whose extraction ran while still dozing, and the
     * exit, which otherwise leaves the cover's answer on the wallpaper until the next doze.
     */
    static void applyGlassBrightness() {
        final Boolean want = Main.sCoverMode ? Main.sBrightCover : Main.sBrightOutside;
        final View v = Main.sContainer;
        if (want == null || v == null) return;
        Gen.postOn(v, () -> {
            for (View root : Main.clockRoots()) {
                for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                    try {
                        int rid = root.getContext().getResources()
                                .getIdentifier(id, "id", "com.android.systemui");
                        View t = rid == 0 ? null : root.findViewById(rid);
                        if (t == null || !glassStyleFor(t)) continue;
                        Xp.callMethod(t, "setBrightness", want);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** One `updateGlassValue` on both clock trees, screen on or off. */
    static void pushGlassFill(float v) {
        for (View root : Main.clockRoots()) {
            try {
                if (!(root instanceof ViewGroup)) continue;
                View c = ((ViewGroup) root).getChildAt(0);
                if (c != null) Xp.callMethod(c, "updateGlassValue", v);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Whether the clock this view belongs to is the liquid-glass one.
     *
     * The colour takeover is FOR the glass style and stays there. On every other style the clock
     * is coloured by the SystemUI palette and it is to stay that way: our substitution was
     * replacing a colour the OEM had already worked out with one derived from a single average
     * over the cover, and on a cover with a bright face under a dark border that average reads
     * "dark cover", so the clock comes out white on white. Passing the OEM value straight
     * through is both less machinery and a better answer.
     *
     * Read off the view itself first - the glyphs are TimeViews and carry the flag - and then by
     * walking up to whichever of the clock views answers.
     */
    static boolean glassStyleFor(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (declaresGlass(p)) return true;
            if (p == Main.sContainer) break;
        }
        // Nothing on the walk answered. Asked of the tree instead: all_in_one is the family whose
        // clock is built this way, and it is the one that answers `time_group`. The date used to
        // be the view that landed here; it is no longer coloured at all. See anchoredStyle().
        return ClockViews.anchoredStyle();
    }

    /**
     * Whether this view is built on the glass TimeView - asked of its CLASS, not of the switch.
     *
     * The first version read `isMiGlassEffectEnable` and returned its VALUE, which is the
     * question "is the glass switched on right now". It is false once the morph has run all the
     * way to solid, which is exactly the state the clock is in when it is collapsed - so the
     * takeover switched itself off for the whole time it was needed and the clock kept the
     * palette's colour. Measured: two frames with the cover's luma forced to 0.95 and to 0.05
     * came out grey-white and grey-white.
     */
    static boolean declaresGlass(View v) {
        Class<?> c = v.getClass();
        Boolean hit = sGlassClasses.get(c);
        if (hit != null) return hit;
        boolean glass = false;
        for (Class<?> k = c; k != null && !glass; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if ("setGlassColor".equals(m.getName())) { glass = true; break; }
            }
            if (!glass) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if ("isMiGlassEffectEnable".equals(f.getName())) { glass = true; break; }
                }
            }
        }
        sGlassClasses.put(c, glass);
        return glass;
    }

    /** Redraws the glass glyphs under `root`, so their next setMiGlass carries the scale now. */
    static void invalidateGlass(View root) {
        if (root == null) return;
        if (declaresGlass(root)) {
            root.invalidate();
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) invalidateGlass(g.getChildAt(i));
        }
    }

    /** TimeView.GLASS_EDGE_PX_INDEX: the glass edge band's width, in pixels (default 30). */
    static final int GLASS_EDGE_PX = 19;

    static void armMiGlassGuard() {
        if (sMiGlassGuardArmed) return;
        sMiGlassGuardArmed = true;
        try {
            Xp.hookAll("sysui.clock.glass_set", View.class, "setMiGlass", chain -> {
                Object self = chain.getThisObject();
                if (!(self instanceof View) || !declaresGlass((View) self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                boolean restored = false;
                if (args.length >= 1 && args[0] instanceof float[]) {
                    try {
                        float[] oem = (float[]) Xp.getObjectField((View) self, "glassData");
                        if (oem != null && oem.length >= 42) {
                            args[0] = oem;
                            restored = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // The always-on displays whose colour is ours (aodColourOurs). The colour pushed
                // here is the OEM's palette, computed from the wallpaper - which in cover mode is
                // our album art - and that palette would repaint the clock a moment after the
                // screen goes off. So the colour is dropped and the first lightness the doze inks
                // with is held instead, which is what holdAodColour() holds too.
                if (Main.sCoverMode && !Main.sScreenOn && AodColour.aodColourOurs() && args[0] instanceof float[]) {
                    float[] a = (float[]) args[0];
                    if (a.length >= 42) {
                        // Luminance, not max: the palette's gold is (1.0, 0.694, 0.384), whose
                        // max is 1.0 - neutralising on that turns the clock pure white. Its
                        // luminance is 0.736, which is the neutral the doze was inking before the
                        // palette landed (measured 189/255 = 0.741 on screen).
                        float mx = AodColour.luminance(a[11], a[12], a[13]);
                        if (Float.isNaN(Main.sAodGrey)) Main.sAodGrey = mx;
                        float[] neutral = a.clone();
                        neutral[11] = neutral[12] = neutral[13] = Main.sAodGrey;
                        args[0] = neutral;
                    }
                }
                // The glass edge as wide on screen as the glyph it is the edge of (#39). The
                // collapsed clock is the OEM's full-size clock scaled down on its time_group, and
                // TimeView strokes its glyph with glassData[GLASS_EDGE_PX_INDEX] in its own
                // coordinates - scaled with it - while the same number reaches the glass shader as
                // screen pixels, which are not. At the small clock's 10-30% the strokes are a few
                // pixels thick against a 30px edge band, the whole glyph is edge, and it refracts
                // like a chrome bevel: the background through it lands far from where it is
                // ("柔光玻璃背景显示异常"). The shader's copy is scaled by what the glyph is drawn
                // at; the field, which the stroke reads, is left as the OEM has it.
                //
                // What shows through the glass is not fixed by this: it is sampled where the glyph
                // would be at full size, before the collapse's scale and lift, and nothing here
                // moves the sampling. Filling the glyph solid to hide it was tried and was too
                // bright (user, 2026-10-06).
                if ((Main.sCoverMode || ClockCollapse.active()) && args[0] instanceof float[]) {
                    float[] a = (float[]) args[0];
                    float k = Main.drawnScale((View) self);
                    if (a.length > GLASS_EDGE_PX && k > 0f && k < 0.995f) {
                        float[] scaled = a.clone();
                        scaled[GLASS_EDGE_PX] = a[GLASS_EDGE_PX] * k;
                        args[0] = scaled;
                    }
                }
                int hits = ++sMiGlassGuardHits;
                // The first few fires prove which view carries the clock's glass and that the
                // field is there to hand back; the rest are counted rather than spammed.
                if (hits <= 5 || hits % 50 == 0) {
                    Xp.d(Main.TAG + "setMiGlass guard fired (" + hits + ") on "
                            + Main.viewIdOf((View) self) + " restored=" + restored);
                }
                return chain.proceed(args);
            });
            Xp.log(Main.TAG + "HyperLight setMiGlass guard armed");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "HyperLight setMiGlass guard unavailable: " + t);
        }
    }

    /**
     * Asked once per class, because the question is asked constantly.
     *
     * getDeclaredMethod/getDeclaredField answer by THROWING when the member is absent, and the
     * stack trace each throw fills in costs far more than the lookup. This runs from inside the
     * colour setters, which the OEM calls for every glyph on every frame of a collapse - so the
     * first version put dozens of exceptions per frame on the clock's own animation thread and
     * the collapse stuttered. Measured symptom: the entry scaling "not normal", with every
     * number in the log still correct. The answer cannot change while the process lives.
     */
    static final java.util.HashMap<Class<?>, Boolean> sGlassClasses = new java.util.HashMap<>();

    /** Whether these glyphs are drawn through the MiGlass shader, read off the views themselves. */
    static boolean glassGlyphs(View root) {
        View v = Main.sContainer;
        if (v == null) return false;
        for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
            int rid = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
            View t = rid == 0 ? null : root.findViewById(rid);
            if (t == null) continue;
            try {
                return Boolean.TRUE.equals(Xp.getObjectField(t, "isMiGlassEffectEnable"));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the clock style on screen right now has a glass channel to drive at all.
     *
     * The liquid-glass morph is AllInOneBase.updateGlassValue(float) - the OEM's own continuous
     * glass -> solid ramp - and it exists exactly where the glass shader does. A rhombus clock
     * draws vector digits, a doodle clock bitmaps, the oriental and magazine clocks plain text:
     * none of them has anything to drive, and a slider that pretends otherwise is a slider that
     * silently does nothing, which is worse than one that says so.
     *
     * Asked of the live class rather than written down as a list of style names, so a style that
     * gains the channel in a later build is picked up without a change here.
     */
    static boolean clockHasGlass() {
        for (View root : Main.clockRoots()) {
            View clock = ClockViews.clockViewOf(root);
            if (clock == null) continue;
            for (Class<?> k = clock.getClass(); k != null && k != Object.class;
                 k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("updateGlassValue", float.class);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** Rides the same progress as the scale, so one OEM spring drives size and look together. */
    static void applyGlassMorph(float p) {
        if (Float.isNaN(sGlassV0)) return;
        if (Main.perfOff("glass")) return;
        // Not while the screen is off, and this is the one that matters for the AOD. The clock
        // keeps its hold through doze - the re-asserts below put the placement back on the doze
        // layout - but they drove this on the way, and cover mode's glass value is not the one
        // the AOD was inked with: the clock came up system-coloured and turned to the glass
        // tint a moment later, every time. The placement is ours; the colour is not.
        if (!Main.sScreenOn) return;
        float g = sGlassV0 + p * (sGlassV1 - sGlassV0);
        // While the cover is up, the clock's glass belongs at the solid end. The collapse
        // progress p is read from the OEM's notifY, and a single frame of it reading the
        // natural position - a keyguard rebuild, a wake, a notification relayout - drags the
        // glass back toward the transparent end, where the hour glyphs vanish over the cover.
        // The placement recovers on the next driven frame; the glass does not - it freezes at
        // whatever was applied last, and that is exactly the broken clock being reported. So
        // once the entry morph has reached the solid end, the value holds there until cover
        // mode itself goes.
        if (Main.sCoverMode) {
            if (g >= sGlassV1 - 0.01f) sGlassSettled = true;
            if (sGlassSettled) g = sGlassV1;
        }
        if (!Float.isNaN(sAppliedGlassV) && Math.abs(g - sAppliedGlassV) < 0.004f) return;
        sAppliedGlassV = g;
        for (View root : Main.clockRoots()) {
            try {
                View c = ((android.view.ViewGroup) root).getChildAt(0);
                if (c == null) continue;
                Xp.callMethod(c, "updateGlassValue", g);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The MiGlass shader samples the wallpaper and does not follow a child View's scale. */
    static void setGlass(final boolean on) {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "no clock container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                for (View root : Main.clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null) continue;
                            Xp.callMethod(t, "setMiGlassEffectEnable", on);
                            t.invalidate();
                        } catch (Throwable e) {
                            Xp.w(Main.TAG + "setGlass " + id + " failed: " + e);
                        }
                    }
                }
                Xp.log(Main.TAG + "glass=" + on);
            }
        });
    }
}
