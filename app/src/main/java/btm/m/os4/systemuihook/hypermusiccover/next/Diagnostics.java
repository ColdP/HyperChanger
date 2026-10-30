// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.text.Layout;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.List;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.reflect.Method;

/**
 * Everything the probe prints and traces: view-tree and class dumps, the clock's geometry and
 * ink reports, the geometry and transition traces, the AOD probe, the frame census and the
 * looper/queue dumps. Read-only by intent - nothing here is on the path of drawing the cover.
 *
 * The op dispatch that calls into it is still Main.boot.
 *
 * Carved out of Main as it was (2026-10-14, after PreviewShots): every member moved whole with
 * its comments, references across the line prefixed with Main. or Diagnostics., nothing else
 * changed.
 */
final class Diagnostics {
    private Diagnostics() {}

    /** Dumps the declared API of one view in the clock tree, to find the size knobs. */
    static void dumpApi(String idName) {
        View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "no clock container"); return; }
        if (idName == null) idName = "hour_view";
        int id = v.getContext().getResources().getIdentifier(idName, "id", "com.android.systemui");
        View target = id == 0 ? null : v.getRootView().findViewById(id);
        // "clock" means the AllInOneHourClock/AllInOneMinuteClock view itself, which carries no id.
        if ("clock".equals(idName)) {
            View[] roots = Main.clockRoots();
            target = roots.length == 0 ? null : ((android.view.ViewGroup) roots[0]).getChildAt(0);
        }
        if (target == null) { Xp.log(Main.TAG + idName + " not found"); return; }
        Class<?> c = target.getClass();
        Xp.log(Main.TAG + idName + " = " + c.getName());
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            Xp.log(Main.TAG + "--- " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("  ").append(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int k = 0; k < ps.length; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(ps[k].getSimpleName());
                }
                Xp.log(Main.TAG + sb.append(')'));
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(target); } catch (Throwable t) { val = "?"; }
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int len = java.lang.reflect.Array.getLength(val);
                    for (int j = 0; j < len; j++) {
                        if (j > 0) arr.append(", ");
                        arr.append(j).append(':').append(java.lang.reflect.Array.get(val, j));
                    }
                    val = arr.append(']').toString();
                }
                Xp.log(Main.TAG + "  ." + f.getName() + " = " + val);
            }
        }
    }

    static void dumpViewTree(boolean fromRoot) {
        View v = Main.sContainer;
        if (v == null) {
            Xp.log(Main.TAG + "no clock container");
            return;
        }
        dumpViewTree(fromRoot ? v.getRootView() : v, 0);
    }

    /**
     * Scales the digits as a group. TimeView.onDraw strokes a Path, so a parent scale is a
     * canvas matrix concat - the path rasterises at the final size, no quality loss - and
     * position scales with it, unlike setSizeInternal which shrinks the glyphs but leaves
     * them at draw origins computed for the big clock.
     */
    /**
     * The lockscreen editor writes its colour choice into clockInfo.clockEffect (see the
     * constant_template_editor_info setting). Dump ClockStyleInfo's constants so we can name
     * the modes instead of guessing at integers.
     */
    static void dumpClockStyleInfo() {
        try {
            Class<?> tv = Xp.findClass(Main.CLS_TIME_VIEW, Main.sContainerCls.getClassLoader());
            Class<?> info = tv.getDeclaredMethod("getClockStyleInfo").getReturnType();
            Xp.log(Main.TAG + "ClockStyleInfo = " + info.getName());
            for (Class<?> c = info; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        Xp.log(Main.TAG + "  " + c.getSimpleName() + "." + f.getName()
                                + " = " + f.get(null));
                    } catch (Throwable ignored) {
                    }
                }
                StringBuilder ms = new StringBuilder();
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("effect") || n.toLowerCase().contains("blend")
                            || n.toLowerCase().contains("color")) ms.append(n).append(' ');
                }
                if (ms.length() > 0) Xp.log(Main.TAG + "  " + c.getSimpleName() + " methods: " + ms);
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "dumpClockStyleInfo failed: " + Log.getStackTraceString(t));
        }
    }

    /** Dumps a class by name so we can find OEM entry points without pulling the dex apart. */
    static void dumpClass(String name, String grep) {
        ClassLoader cl = Main.sContainerCls == null ? null : Main.sContainerCls.getClassLoader();
        Class<?> c;
        try {
            c = Xp.findClass(name, cl);
        } catch (Throwable t) {
            Xp.log(Main.TAG + name + " NOT FOUND");
            return;
        }
        Xp.log(Main.TAG + "=== " + c.getName());
        String g = grep == null ? null : grep.toLowerCase();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int j = 0; j < ps.length; j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(ps[j].getSimpleName());
                }
                sb.append(')');
                String line = sb.toString();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(Main.TAG + "  " + line);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(Main.TAG + "  " + line);
                }
            }
        }
    }

    /**
     * Where the clock actually sits on screen. Every lock screen clock style lays its glyphs out
     * somewhere different, so anything that positions the collapsed clock has to come from these
     * numbers rather than from a constant measured against one style.
     */
    /**
     * The layout-space relationship between the date and the clock, printed on demand.
     *
     * W3 rests on one claim that has never been measured on this device: that the date and the
     * clock target sit in ONE frame, so their `getTop()`s can be subtracted meaningfully. Two
     * earlier attempts assumed a frame relation in one direction or the other and were off by
     * ~700px. So this prints the frames instead of assuming them - each view's own `getTop()`
     * (parent-relative, i.e. layout space), the same view's rendered position, and the chain of
     * ancestors up towards the container, so "do these two numbers mean the same thing" is
     * answered by what is actually on the screen.
     *
     * Called by hand (`op geom`) after each step of a sweep, which is why it logs a block and
     * writes nothing.
     */
    static void dumpGeom() {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "geom: no container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder("geom");
                    sb.append(" hold=").append(Main.sHoldY).append(" natural=").append(Main.r1(Main.sLastSystemY))
                      .append(" clock ").append(ClockCollapse.describe())
                      .append(" box=").append(ClockViews.boxOf(ClockViews.glyphBox()))
                      .append(" live=").append(ClockViews.boxOf(measureLiveBox()))
                      .append(" cac=").append(cacTy())
                      .append(" oemDateMargin=").append(oemDateMargin());
                    Xp.log(Main.TAG + sb.toString());
                    Xp.log(Main.TAG + "  date " + geomOf(ClockViews.sDateView));
                    for (View root : Main.clockRoots()) {
                        Xp.log(Main.TAG + "  clock(" + Main.viewIdOf(root) + ") "
                                + geomOf(Main.clockTarget(root)));
                    }
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "geom failed: " + t);
                }
            }
        });
    }

    /**
     * The glyph box measured on THIS frame, with the hold and the cache both stood down.
     *
     * A probe and nothing else: every reading it takes is put back exactly as it was, because
     * sInkUnit and the cached box are what the next real placement is made from and a diagnostic
     * that moves them is measuring itself.
     */
    static RectF measureLiveBox() {
        RectF keepBox = ClockViews.sGlyphCache;
        long keepAt = ClockViews.sGlyphAt;
        float keepUnit = ClockViews.sInkUnit;
        try {
            Main.sProbeLive = true;
            return ClockViews.glyphBox();
        } catch (Throwable t) {
            return null;
        } finally {
            Main.sProbeLive = false;
            ClockViews.sGlyphCache = keepBox;
            ClockViews.sGlyphAt = keepAt;
            ClockViews.sInkUnit = keepUnit;
        }
    }

    /**
     * Per-frame trace of the four numbers the date placement is made of, and nothing else.
     *
     * `op verbose` answers this too and costs the frame rate doing it - it goes through the
     * LSPosed bridge several times per frame and the lock screen visibly stutters, so a
     * transition measured under it is not the transition anyone sees. This is one line, built
     * from fields already in hand plus two getters, so the frame it describes is the real one.
     *
     * What it is for: the date's screen position is `container.ty + date.top + date.ty`. If the
     * jump is a compensation that arrives a frame late, then the STEP in `dscr` is larger than
     * the step in `cty` on exactly the frames where the OEM moves the container; if it is
     * something else, `dtop` moves instead. One or the other, and no way to tell them apart from
     * a settled reading.
     */
    static volatile long sGeomTraceUntil;

    static int sGeomTraceN;

    static void startGeomTrace(int ms) {
        sGeomTraceUntil = android.os.SystemClock.uptimeMillis() + Math.max(500, ms);
        sGeomTraceN = 0;
        // Its OWN listener, deliberately. It used to ride on sClockGuard, and exitCoverMode()
        // removes that guard before it starts the exit spring - so the one transition this
        // exists to look at was the one transition it could not see, and "no frames were drawn"
        // was the instrument being unplugged rather than the screen standing still.
        View v = Main.sContainer;
        if (v != null) {
            try {
                Gen.preDraw(v, sGeomTraceL);
                sGeomTraced = v;
            } catch (Throwable t) {
                Xp.log(Main.TAG + "geomtrace not installed: " + t);
            }
        }
        Xp.log(Main.TAG + "geomtrace for " + ms + "ms on " + (v == null ? "no container" : Main.idOf(v)));
    }

    static void stopGeomTrace() {
        View v = sGeomTraced;
        sGeomTraced = null;
        if (v == null) return;
        try {
            Gen.unpreDraw(v, sGeomTraceL);
        } catch (Throwable ignored) {
        }
    }

    static View sGeomTraced;

    static final ViewTreeObserver.OnPreDrawListener sGeomTraceL =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            traceGeomFrame();
            return true;
        }
    };

    static void traceGeomFrame() {
        if (sGeomTraceUntil == 0L) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now > sGeomTraceUntil) {
            sGeomTraceUntil = 0L;
            stopGeomTrace();
            Xp.log(Main.TAG + "geomtrace done, " + sGeomTraceN + " frames");
            return;
        }
        sGeomTraceN++;
        View date = ClockViews.sDateView;
        View c = Main.sContainer;
        StringBuilder sb = new StringBuilder("gf ");
        sb.append(ClockCollapse.phase());
        // The date's own parent is `clock_animation_container`, and THAT is the view the OEM
        // squeezes by translating - `sContainer` is `miui_keyguard_clock_container`, one level
        // further up, and its translation is always 0. Reading the wrong one of the two is how
        // a trace can show a container standing still while the date is provably being carried.
        View cac = date == null || !(date.getParent() instanceof View)
                ? null : (View) date.getParent();
        sb.append(" cac=").append(cac == null ? "?" : Main.r1(cac.getTranslationY()));
        sb.append(" cty=").append(c == null ? "?" : Main.r1(c.getTranslationY()));
        if (date == null) {
            sb.append(" date=MISSING");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append(" dtop=").append(date.getTop())
              .append(" dty=").append(Main.r1(date.getTranslationY()))
              .append(" dscr=").append(loc[1]);
        }
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            int[] loc = new int[2];
            g.getLocationOnScreen(loc);
            sb.append(" | ").append(Main.idOf(g)).append(" gtop=").append(g.getTop())
              .append(" gty=").append(Main.r1(g.getTranslationY()))
              .append(" gk=").append(Main.r2(g.getScaleY()))
              .append(" gscr=").append(loc[1]);
        }
        {
            sb.append(" | t=").append(now).append(" box=").append(ClockViews.boxOf(measureLiveBox()));
            // Anything above the container that could be carrying a wake animation.
            View up = c;
            for (int k = 0; k < 4 && up != null; k++) {
                sb.append(" ^").append(Main.viewIdOf(up)).append(" sy=").append(Main.r2(up.getScaleY()))
                  .append(" ty=").append(Main.r1(up.getTranslationY()))
                  .append(" a=").append(Main.r2(up.getAlpha()));
                up = up.getParent() instanceof View ? (View) up.getParent() : null;
            }
        }
        Xp.log(Main.TAG + sb.toString());
    }

    /** One view's layout position, its rendered position, and the frames it hangs in. */
    static String geomOf(View v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(Main.viewIdOf(v));
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" top=").append(v.getTop())
          .append(" left=").append(v.getLeft())
          .append(" h=").append(v.getHeight())
          .append(" ty=").append(Main.r1(v.getTranslationY()))
          .append(" sy=").append(Main.r2(v.getScaleY()))
          .append(" pivotY=").append(Main.r1(v.getPivotY()))
          .append(" screen=").append(loc[1]);
        // Up towards the container: a difference between two views only cancels if both ends
        // reach the same ancestor carrying the same accumulated translation.
        View p = v.getParent() instanceof View ? (View) v.getParent() : null;
        for (int i = 0; i < 5 && p != null; i++) {
            sb.append(" <- ").append(Main.viewIdOf(p)).append(" top=").append(p.getTop())
              .append(" ty=").append(Main.r1(p.getTranslationY()))
              .append(" sy=").append(Main.r2(p.getScaleY()));
            if (p == Main.sContainer) break;
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return sb.toString();
    }

    /** The translation the OEM puts on `clock_animation_container`, which the date rides. */
    static String cacTy() {
        View d = ClockViews.sDateView;
        if (d == null || !(d.getParent() instanceof View)) return "?";
        return Main.r1(((View) d.getParent()).getTranslationY());
    }

    /**
     * Every measurement the ink box could be taken from, per style, printed rather than picked.
     *
     * `glyphBox()` picks one of several sources per style and the choice is why the same 55.7dp
     * setting gives k=0.09 on one style and k=1.0 on the next: the fallback is the target's own
     * bounds, which is the whole container wherever the target is a container. This walks the
     * target and prints what each view would answer, so the replacement is chosen from numbers.
     */
    static void dumpInk() {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "ink: no container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                for (View root : Main.clockRoots()) {
                    View t = Main.clockTarget(root);
                    Xp.log(Main.TAG + "ink tree=" + Main.viewIdOf(root) + " target="
                            + (t == null ? "NONE" : Main.viewIdOf(t)));
                    if (t == null) continue;
                    inkWalk(t, t, 0);
                }
            }
        });
    }

    static void inkWalk(View target, View v, int depth) {
        if (v == null || depth > 6) return;
        StringBuilder sb = new StringBuilder("ink   ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append(" #").append(Main.viewIdOf(v))
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" at=").append(loc[0]).append(',').append(loc[1]);
        // The three shapes an ink measurement could come from, asked in the same order
        // inkBox() asks them.
        try {
            Object b = Xp.callMethod(v, "getTextBoundsWithPosition");
            if (b != null) sb.append(" textBounds=").append(b);
        } catch (Throwable ignored) {
        }
        for (String m : new String[]{"getRectSize", "getRealWidth", "getRealHeight",
                "getLeftPosition", "getTopPosition"}) {
            try {
                Object r = Xp.callMethod(v, m);
                if (r != null) sb.append(' ').append(m).append('=').append(r);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof android.widget.TextView) {
            android.text.Layout lay = ((android.widget.TextView) v).getLayout();
            if (lay != null && lay.getLineCount() > 0) {
                sb.append(" lineTop=").append(lay.getLineTop(0))
                  .append(" lineBottom=").append(lay.getLineBottom(lay.getLineCount() - 1))
                  .append(" lineWidth=").append(lay.getLineWidth(0));
            }
        }
        Xp.log(Main.TAG + sb.toString());
        if (v instanceof ViewGroup && depth < 6) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) inkWalk(target, g.getChildAt(i), depth + 1);
        }
    }

    /** The OEM's own date+margin height, or the field's value as it came, or n/a. */
    static String oemDateMargin() {
        try {
            View v = Main.sContainer;
            Object it = v == null ? null : Xp.getObjectField(v, "keyguardClockNotifInteractor");
            Object got = it == null ? null : Xp.getObjectField(it, "dateHeightWithMargin");
            if (got == null) return "n/a";
            return String.valueOf(got);
        } catch (Throwable t) {
            return "n/a";
        }
    }

    static void dumpClockBounds() {
        for (View root : Main.clockRoots()) {
            android.content.res.Resources r = root.getResources();
            for (String id : new String[]{"clock_animation_container", "time_group", "text_area",
                    "hour_view", "minute_view"}) {
                int i = r.getIdentifier(id, "id", "com.android.systemui");
                View v = i == 0 ? null : root.findViewById(i);
                if (v == null) continue;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                StringBuilder sb = new StringBuilder(Main.viewIdOf(root)).append(' ').append(id)
                        .append(" screen=").append(loc[0]).append(',').append(loc[1])
                        .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                        .append(" ty=").append(v.getTranslationY())
                        .append(" sy=").append(v.getScaleY())
                        .append(" pivotY=").append(v.getPivotY())
                        .append(" vis=").append(v.getVisibility());
                try {
                    sb.append(" textBounds=")
                            .append(Xp.callMethod(v, "getTextBoundsWithPosition"))
                            .append(" drawY=").append(Xp.callMethod(v, "getDrawTextY"))
                            .append(" textTop=").append(Xp.callMethod(v, "getTextTop"));
                } catch (Throwable ignored) {
                }
                Xp.log(Main.TAG + sb);
            }
        }
    }

    /** Reads, and optionally pokes, the MiGlass shader parameter array on both clock trees. */
    static void pokeGlassData(final int idx, final float value) {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "no clock container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                for (View root : Main.clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null || t.getVisibility() != View.VISIBLE) continue;
                            float[] g = (float[]) Xp.getObjectField(t, "glassData");
                            if (g == null) continue;
                            if (idx >= 0 && idx < g.length) g[idx] = value;
                            t.invalidate();
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < g.length; j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(j).append(':').append(g[j]);
                            }
                            Xp.log(Main.TAG + Main.viewIdOf(root) + "/" + id + " glassData=[" + sb + "]");
                        } catch (Throwable e) {
                            Xp.log(Main.TAG + "pokeGlassData " + id + ": " + e);
                        }
                    }
                }
            }
        });
    }

    /** Anything on the tree that carries text or draws glyphs, which is all this needs to see. */
    static void describeClockTree(View v, StringBuilder sb, int depth) {
        if (depth > 8 || v == null) return;
        boolean text = v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().length() > 0;
        boolean glyphs = ClockViews.isTimeView(v);
        if (text || glyphs) {
            sb.append("\n    ").append(v.getClass().getSimpleName()).append(" #").append(Main.idOf(v))
              .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
              .append(" vis=").append(v.getVisibility());
            if (text) sb.append(" \"").append(((TextView) v).getText()).append('"');
            if (glyphs) sb.append(" [timeview]");
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) describeClockTree(g.getChildAt(i), sb, depth + 1);
    }

    /**
     * Records what every moving part of the lock screen is doing, frame by frame, for the
     * length of one transition.
     *
     * For an artifact that lasts a few frames and is not in the wallpaper: the wallpaper
     * process proved its own geometry never moves during a swap, so whatever flashes on screen
     * is a VIEW in this process, and no log written after the fact can say which one. This
     * samples the candidates on the pre-draw of every frame and prints only what CHANGED, so a
     * part that sits still costs one string compare and says nothing, and the part that jumps
     * to the corner of the screen for three frames is the only thing in the output.
     *
     * Armed for a fixed window rather than left running: this is per-frame reflection over a
     * handful of views, which is fine for the 900ms of a transition and not fine forever.
     */
    static final String[] TRACE_VIEWS = {
            "media_player", "album_art", "album_art_image", "deducted_image_view",
            "keyguard_background_layer", "keyguard_foreground_layer",
            "notification_container_parent", "mi_media_controls",
    };

    static final long TRACE_MS = 900L;

    static ViewTreeObserver.OnPreDrawListener sTrace;

    static View sTraceOn;

    static long sTraceT0;

    static final java.util.HashMap<String, String> sTraceLast = new java.util.HashMap<>();

    static final StringBuilder sTraceOut = new StringBuilder();

    static void armTransitionTrace(final String why) {
        // Off unless someone is watching: this is per-frame reflection over a handful of views
        // plus a walk of the whole window, which is the right price for 900ms of a bug hunt and
        // the wrong one for every transition forever. `--es op verbose --ez on true` arms it.
        if (!Main.sVerbose) return;
        final View root = Main.sContainer;
        if (root == null || sTrace != null) return;
        sTraceT0 = android.os.SystemClock.uptimeMillis();
        sTraceLast.clear();
        sTraceOut.setLength(0);
        sTraceOut.append("transition trace (").append(why).append("):");
        sTraceOn = root;
        sTrace = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                long dt = android.os.SystemClock.uptimeMillis() - sTraceT0;
                for (String name : TRACE_VIEWS) {
                    View v = Main.findSysuiView(name);
                    String now = v == null ? "gone" : traceLine(v);
                    String was = sTraceLast.put(name, now);
                    if (now.equals(was)) continue;
                    sTraceOut.append("\n  +").append(dt).append("ms ").append(name)
                             .append(' ').append(now);
                }
                sweepForStrays(dt);
                if (dt >= TRACE_MS) {
                    releaseTransitionTrace();
                    Xp.log(Main.TAG + sTraceOut);
                }
                return true;
            }
        };
        Gen.preDraw(root, sTrace);
    }

    /**
     * Anything small being drawn high on the screen that is NOT one of the views above.
     *
     * The named list is a set of guesses, and a guess cannot find an artifact nobody has
     * identified yet - the report is "a small copy of the cover, up in the corner", and the
     * whole point of this pass is to name the view that is. So every frame also walks the
     * keyguard window for a visible view that is under half the screen wide and sitting in the
     * top third, which is what "small, up there" means in numbers. Bounded by the same 900ms
     * window as the rest of the trace.
     */
    static void sweepForStrays(long dt) {
        View root = Main.sContainer;
        if (root == null || Main.sScreenW <= 0) return;
        sweep(root.getRootView(), dt, 0);
    }

    static void sweep(View v, long dt, int depth) {
        if (depth > 14 || v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sweep(g.getChildAt(i), dt, depth + 1);
        }
        int w = v.getWidth(), h = v.getHeight();
        // Small, but cover-sized: below an eighth of the screen is the status bar's icons,
        // which jitter by a pixel every frame and would bury the one line that matters.
        if (w < Main.sScreenW / 8 || h < Main.sScreenW / 8 || w > Main.sScreenW / 2) return;
        if (!(v instanceof ImageView) && !(v instanceof android.view.TextureView)) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (loc[1] > Main.sScreenH / 3) return;
        String name = Main.idOf(v);
        String key = "stray:" + name + '@' + System.identityHashCode(v);
        String now = v.getClass().getSimpleName() + ' ' + traceLine(v);
        String was = sTraceLast.put(key, now);
        if (now.equals(was)) return;
        sTraceOut.append("\n  +").append(dt).append("ms STRAY #").append(name)
                 .append(' ').append(now);
    }

    static String traceLine(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder();
        sb.append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(Main.r2(v.getAlpha()))
          .append(" s=").append(Main.r2(v.getScaleX())).append('/').append(Main.r2(v.getScaleY()))
          .append(" t=").append(Main.r1(v.getTranslationX())).append(',')
          .append(Main.r1(v.getTranslationY()))
          .append(" shown=").append(v.isShown());
        return sb.toString();
    }

    static void releaseTransitionTrace() {
        View on = sTraceOn;
        ViewTreeObserver.OnPreDrawListener t = sTrace;
        sTraceOn = null;
        sTrace = null;
        if (on == null || t == null) return;
        try {
            Gen.unpreDraw(on, t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Everything needed to explain where this phone put the clock, as one block of text.
     *
     * Written to be pasted into a bug report by someone who cannot run adb, so it says what it
     * measured AND what it intended: a report where `target` and `dateOnScreen` agree is a
     * report about something other than the placement.
     */
    /**
     * PROBE: what ClockViewType resolves to on the style that is loaded right now.
     *
     * Every clock style answers the OEM's own "where is the time" question - each clock view
     * implements MiuiClockController.IClockView.getIClockView(ClockViewType), and the OEM's
     * animation code uses nothing else to find the pieces it moves. Guessing at the view tree by
     * class and text works for some styles and not at all for others (a rhombus clock draws its
     * digits as vector art, a doodle clock as bitmaps, an oriental one in Chinese numerals), so
     * the guess has to go - but only once it is known what the OEM's own answer actually is per
     * style. That is what this prints.
     */
    /**
     * The OEM's own account of how far the clock has squeezed, and of where its floor is.
     *
     * `KeyguardClockNotifInteractor` decides what the clock looks like for a given notifY by
     * picking one of a dozen named scenes - "Space Squeeze Result", "Scene 2.0 (Time not
     * squeezable)", "Scene 2.1.1.1 (Squeeze height only)", "Scene 2.1.1.2 (Squeeze to min &
     * move up)" - each of which is a formula over the fields below rather than a number. The y
     * the collapse has been holding at - 740 on this device - is not a constant anywhere in the
     * OEM: it is the y at which that choice lands on "not squeezable" any more, which is to say
     * the y at which `timeHeight` has reached `timeMinHeight`.
     *
     * If that holds, then none of it has to be learned: the module is already handed the same
     * ClockResult once per frame, and "how far into the collapse are we" is a question about
     * those two heights - a fraction of the OEM's own squeeze - rather than about pixels of y.
     * Which is what would make the collapse mean the same thing on every phone.
     */
    static void dumpNotifState() {
        View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "notif: no clock container"); return; }
        try {
            Object it = Xp.getObjectField(v, "keyguardClockNotifInteractor");
            if (it == null) {
                Xp.log(Main.TAG + "notif: no keyguardClockNotifInteractor on " + v.getClass().getName());
                return;
            }
            StringBuilder sb = new StringBuilder("notif ");
            sb.append(it.getClass().getSimpleName()).append(": ");
            for (String f : new String[]{"timeMinHeight", "baseClockMinHeight", "baseClockMaxHeight",
                    "adaptTimeHeight", "adaptTimeWidth", "adaptWidthHeightRatio",
                    "timeSqueezeRatio", "maxSpace", "dateHeightWithMargin", "notifPre",
                    "clockNotificationMargin", "isParamsValid", "rawNotifY"}) {
                try {
                    sb.append(f).append('=').append(Xp.getObjectField(it, f)).append(' ');
                } catch (Throwable t) {
                    sb.append(f).append("=n/a ");
                }
            }
            try {
                sb.append("| clockResult=").append(Xp.getObjectField(it, "clockResult"));
            } catch (Throwable t) {
                sb.append("| clockResult=n/a");
            }
            Xp.log(Main.TAG + sb.toString());
            Xp.log(Main.TAG + "notif: container getClockBottom=" + Xp.callMethod(v, "getClockBottom")
                    + " getNotificationClockTop=" + Xp.callMethod(v, "getNotificationClockTop")
                    + " hold=" + Main.sHoldY + " lastSystem=" + Main.r1(Main.sLastSystemY));
        } catch (Throwable t) {
            Xp.w(Main.TAG + "notif probe failed: " + t);
        }
    }

    /** Every `ClockViewType` this style answers, and the view it resolves to, per clock tree. */
    static void dumpClockViewTypes() {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "viewtypes: no container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                Class<?> cvt;
                Object[] all;
                try {
                    cvt = Xp.findClass(Main.CLS_CLOCK_VIEW_TYPE, Main.sContainerCls.getClassLoader());
                    all = cvt.getEnumConstants();
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "viewtypes: no ClockViewType (" + Main.CLS_CLOCK_VIEW_TYPE + "): " + t);
                    return;
                }
                if (all == null) { Xp.log(Main.TAG + "viewtypes: no constants"); return; }
                for (View root : Main.clockRoots()) {
                    View clock = root instanceof ViewGroup ? ((ViewGroup) root).getChildAt(0) : null;
                    Xp.log(Main.TAG + "viewtypes tree " + Main.viewIdOf(root) + ": clock="
                            + (clock == null ? "none" : clock.getClass().getName()));
                    if (clock == null) continue;
                    for (Object ct : all) {
                        Object got;
                        try {
                            got = Xp.callMethod(clock, "getIClockView", ct);
                        } catch (Throwable t) {
                            Xp.log(Main.TAG + "  " + ct + " -> threw " + t);
                            continue;
                        }
                        if (!(got instanceof View)) {
                            Xp.log(Main.TAG + "  " + ct + " -> " + got);
                            continue;
                        }
                        View g = (View) got;
                        int[] loc = new int[2];
                        g.getLocationOnScreen(loc);
                        Xp.log(Main.TAG + "  " + ct + " -> " + g.getClass().getSimpleName()
                                + " #" + Main.viewIdOf(g) + " " + g.getWidth() + "x" + g.getHeight()
                                + " vis=" + g.getVisibility() + " at=" + loc[0] + "," + loc[1]);
                    }
                }
            }
        });
    }

    static String clockReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("HyperMusicCover clock report\n");
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE)
          .append(")\nbuild: ").append(android.os.Build.DISPLAY)
          .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
          .append(" / SDK ").append(android.os.Build.VERSION.SDK_INT)
          .append("\nos: ").append(sysProp("ro.mi.os.version.name"))
          .append(' ').append(sysProp("ro.mi.os.version.incremental"))
          .append("\n");
        View v = Main.sContainer;
        if (v == null) {
            sb.append("no clock container - the module is loaded but the keyguard has not been "
                    + "built yet, or the hook did not take.\n");
            return sb.toString();
        }
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        sb.append("screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
          .append(" density=").append(dm.density).append(" dpi=").append(dm.densityDpi)
          .append(" moduleScreen=").append(Main.sScreenW).append('x').append(Main.sScreenH)
          .append(" statusBar=").append(sysDimen(v, "status_bar_height"))
          .append("\n");
        sb.append("state: cover=").append(Main.sCoverMode).append(" auto=").append(Main.sAuto)
          .append(" card=").append(MediaCard.sCardKnown ? (MediaCard.sCardShowing ? "showing" : "gone") : "unknown")
          .append(" keyguard=").append(Main.onKeyguardNow())
          .append(" videoWallpaper=").append(Main.sVideoWallpaper)
          .append("\nvideo cover: ").append(CoverPush.describeVideoCover())
          .append("\nhold: y=").append(Main.sHoldY)
          .append(" lastSystem=").append(Main.r1(Main.sLastSystemY))
          .append("\nclock: ").append(ClockCollapse.describe()).append('\n')
          .append(" clockH=").append(Main.r1(Main.sClockHeightDp)).append("dp")
          .append(" size=").append(Float.isNaN(Main.sClockSize) ? "dp" : Main.r3(Main.sClockSize))
          .append(" offset=").append(Main.r1(Main.sClockOffsetDp)).append("dp")
          .append(" response=").append(Main.r2(Main.sClockResponse)).append("s")
          .append(" fades=").append(Main.fadeMsFor(Main.sClockResponse)).append("ms")
          // Whether the app's glass slider is live at all: the morph exists only on the styles
          // whose clock view has updateGlassValue, and this is what tells the app which those
          // are. The first thing to look at when the slider appears to do nothing.
          .append(" anchored=").append(ClockViews.anchoredStyle())
          .append(" glassStyle=").append(ClockGlass.clockHasGlass())
          .append("\ndate: rest=").append(Main.r1(ClockCollapse.coverDateY(ClockViews.visibleDate())))
          .append("px from the top; cover mode leaves the date there and hangs the clock under"
                  + " it")
          .append("\ngap=").append(Main.CLOCK_GAP_DP).append("dp\n");

        View date = ClockViews.visibleDate();
        if (date == null) {
            sb.append("date: NOT FOUND - nothing to anchor the clock to\n");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append("date: #").append(Main.idOf(date)).append(' ')
              .append(date.getClass().getSimpleName())
              .append(" onScreen=").append(loc[0]).append(',').append(loc[1])
              .append(" top=").append(date.getTop()).append(" h=").append(date.getHeight())
              .append(" ty=").append(Main.r1(date.getTranslationY()))
              .append(" text=\"").append(date instanceof TextView
                    ? ((TextView) date).getText() : "?").append("\"\n");
        }
        View[] sigs = ClockViews.signatureViews();
        if (sigs.length == 0) {
            sb.append("signature: none on this style\n");
        } else {
            for (View s : sigs) {
                int[] sl = new int[2];
                s.getLocationOnScreen(sl);
                sb.append("signature: #").append(Main.idOf(s)).append(' ')
                  .append(s.getClass().getSimpleName())
                  .append(" onScreen=").append(sl[0]).append(',').append(sl[1])
                  .append(" top=").append(s.getTop()).append(" h=").append(s.getHeight())
                  .append(" ty=").append(Main.r1(s.getTranslationY()))
                  .append(" text=").append(ClockViews.signatureShows(s))
                  .append(" under=").append(Main.r1(ClockCollapse.contentBottomOnScreen()))
                  .append("\n");
            }
        }
        RectF pooled = ClockViews.glyphBox();
        sb.append("glyphs: ").append(pooled == null ? "NOT MEASURABLE" : pooled.toString())
          .append("\n");
        float[] cg = ClockViews.clockGeometry();
        sb.append("geometry: ").append(cg == null ? "null"
                : "w=" + Main.r1(cg[0]) + " h=" + Main.r1(cg[1]) + " y=" + Main.r1(cg[2])
                  + " x=" + Main.r1(cg[3]) + " pivotX=" + Main.r1(cg[4])).append("\n");

        for (View root : Main.clockRoots()) {
            sb.append("tree ").append(Main.idOf(root)).append(":\n");
            View g = Main.clockTarget(root);
            if (g == null) {
                sb.append("  target: NONE (this tree draws no clock)\n");
            } else {
                int[] gl = new int[2];
                g.getLocationOnScreen(gl);
                boolean byId = g == ClockViews.findClockView(root, "time_group");
                sb.append("  target: #").append(Main.idOf(g)).append(' ')
                  .append(g.getClass().getSimpleName())
                  .append(byId ? " (time_group)" : " (RESOLVED by search)")
                  .append(" left=").append(g.getLeft()).append(" top=").append(g.getTop())
                  .append(' ').append(g.getWidth()).append('x').append(g.getHeight())
                  .append(" onScreen=").append(gl[0]).append(',').append(gl[1])
                  .append(" scale=").append(Main.r2(g.getScaleX())).append('/').append(Main.r2(g.getScaleY()))
                  .append(" pivot=").append(Main.r1(g.getPivotX())).append(',').append(Main.r1(g.getPivotY()))
                  .append(" ty=").append(Main.r1(g.getTranslationY()))
                  .append("\n  ink: ").append(ClockViews.inkBox(root, g)).append('\n');
                // The one thing a screenshot cannot show and the placement depends on entirely:
                // whether the view being scaled also contains the date, which would drag the
                // date along with the clock and make the anchor chase itself.
                if (date != null) {
                    sb.append("  target holds the date: ").append(ClockViews.isAncestor(g, date)).append('\n');
                }
            }
            sb.append("  oem: ").append(ClockViews.oemPartsOf(root)).append('\n');
            describeClockTree(root, sb, 1);
            sb.append('\n');
        }

        return sb.toString();
    }

    static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object s = sp.getMethod("get", String.class).invoke(null, key);
            String out = s == null ? "" : s.toString();
            return out.isEmpty() ? "?" : out;
        } catch (Throwable t) {
            return "?";
        }
    }

    static String sysDimen(View v, String name) {
        try {
            int id = v.getResources().getIdentifier(name, "dimen", "android");
            return id == 0 ? "?" : String.valueOf(v.getResources().getDimensionPixelSize(id));
        } catch (Throwable t) {
            return "?";
        }
    }

    static int sVtreeLines;

    /**
     * PROBE: the keyguard window's whole view tree, one line per view - class, id, visibility,
     * alpha, size - with TextureViews and SurfaceViews marked. This is the ground truth behind
     * "the cover is up but the screen does not show it": the state dump reads our view's own
     * flags, and they can all say VISIBLE while the layer the view sits in is not what the eye
     * is looking at.
     */
    static void dumpViewTree() {
        try {
            View v = Main.sContainer;
            if (v == null) {
                Xp.log(Main.TAG + "vtree: no clock container");
                return;
            }
            sVtreeLines = 0;
            StringBuilder sb = new StringBuilder();
            walkTree(v.getRootView(), 0, sb);
            String s = sb.toString();
            int start = 0;
            while (start < s.length()) {
                int nl = s.indexOf('\n', start);
                if (nl < 0) nl = s.length();
                Xp.log(Main.TAG + "vtree " + s.substring(start, nl));
                start = nl + 1;
            }
            Xp.log(Main.TAG + "vtree done, " + sVtreeLines + " views");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "vtree failed: " + t);
        }
    }

    static void walkTree(View v, int depth, StringBuilder sb) {
        if (sVtreeLines > 400) return;
        sVtreeLines++;
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = v.getClass().getSimpleName();
        if (cls.isEmpty()) cls = v.getClass().getName();
        sb.append(cls).append('#').append(Main.resourceName(v))
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(Main.r2(v.getAlpha()))
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" tl=").append((int) v.getX()).append(',').append((int) v.getY());
        if (v instanceof android.view.TextureView) sb.append(" <<TEXTUREVIEW>>");
        if (v instanceof android.view.SurfaceView) sb.append(" <<SURFACEVIEW>>");
        if (v == Main.sCover) sb.append(" <<COVER>>");
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkTree(g.getChildAt(i), depth + 1, sb);
        }
    }

    /**
     * FLAG_RECEIVER_FOREGROUND is the whole reason a track change feels immediate: without it
     * this broadcast sits in the background queue and took a measured ~500ms to reach the
     * wallpaper process, which is more than composing and uploading the picture put together.
     */
    /** The main looper's queue, counted by callback or target and what, largest first. */
    static String looperCensus() {
        final java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        final int[] total = {0};
        Looper.getMainLooper().dump(new android.util.Printer() {
            @Override
            public void println(String x) {
                int m = x.indexOf("Message ");
                if (m < 0 || x.indexOf('{', m) < 0) return;
                total[0]++;
                String key;
                int cb = x.indexOf("callback=");
                if (cb >= 0) {
                    int end = x.indexOf(' ', cb);
                    key = "cb " + x.substring(cb + 9, end < 0 ? x.length() : end);
                } else {
                    int tg = x.indexOf("target=");
                    int wt = x.indexOf("what=");
                    int end = tg < 0 ? -1 : x.indexOf(' ', tg);
                    key = (tg < 0 ? "?" : x.substring(tg + 7, end < 0 ? x.length() : end))
                            + (wt < 0 ? "" : " " + x.substring(wt, Math.min(x.length(),
                                    x.indexOf(' ', wt) < 0 ? x.length() : x.indexOf(' ', wt))));
                }
                Integer v = counts.get(key);
                counts.put(key, v == null ? 1 : v + 1);
            }
        }, "");
        java.util.ArrayList<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(counts.entrySet());
        java.util.Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("queued=" + total[0]);
        for (int k = 0; k < Math.min(12, list.size()); k++) {
            sb.append(" | ").append(list.get(k).getValue()).append(" x ").append(list.get(k).getKey());
        }
        return sb.toString();
    }

    /** What decides whether a view reaches the screen: its own state and its parents'. */
    static String layerState(View v) {
        if (v == null) return "absent";
        StringBuilder sb = new StringBuilder();
        sb.append("vis=").append(v.getVisibility()).append(" a=").append(Main.r2(v.getAlpha()))
                .append(" ta=").append(Main.r2(v.getTransitionAlpha()))
                .append(" shown=").append(v.isShown())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ImageView) {
            android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                sb.append(" bmp=").append(b == null ? "null" : b.getWidth() + "x" + b.getHeight()
                        + "@" + Integer.toHexString(System.identityHashCode(b)));
            }
        }
        int depth = 0;
        for (android.view.ViewParent p = v.getParent(); p instanceof View && depth < 3; p = p.getParent(), depth++) {
            View pv = (View) p;
            sb.append(" <").append(Main.idName(pv)).append(" vis=").append(pv.getVisibility())
                    .append(" a=").append(Main.r2(pv.getAlpha()));
        }
        return sb.toString();
    }

    /**
     * Every clickable view on the keyguard, with whatever names it into something recognisable.
     *
     * For finding the media card's transport buttons: the whole point of hooking them is to hear
     * the press ~0.8s before the player admits a track changed, and the only reliable way to name
     * them across HyperOS builds is to look at what is actually there. Content descriptions are
     * the most durable handle - they are what the accessibility layer reads out, so they survive
     * the resource-id renames that R8 and OEM reskins do not.
     */
    static String dumpClickables() {
        try {
            View root = Main.sContainer == null ? null : Main.sContainer.getRootView();
            if (root == null) return "no keyguard view - is the lock screen up?";
            StringBuilder sb = new StringBuilder("=== clickable views on the keyguard ===");
            collectClickable(root, sb, new int[] {0});
            return sb.toString();
        } catch (Throwable t) {
            return "clickable dump failed: " + Log.getStackTraceString(t);
        }
    }

    static void collectClickable(View v, StringBuilder sb, int[] n) {
        if (n[0] > 60) return;
        if (v.isClickable() || v.hasOnClickListeners()) {
            n[0]++;
            int[] xy = new int[2];
            try {
                v.getLocationOnScreen(xy);
            } catch (Throwable ignored) {
            }
            sb.append('\n').append(v.getClass().getName())
                    .append(" id=").append(Main.idName(v))
                    .append(" desc=").append(v.getContentDescription())
                    .append(" at ").append(xy[0]).append(',').append(xy[1])
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(v.isShown() ? "" : " (hidden)");
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectClickable(g.getChildAt(i), sb, n);
        }
    }

    /**
     * What every active session publishes about its play queue.
     *
     * The question behind it: the player takes 0.7-1s to say a track changed, and the only way to
     * beat that is to know what is coming BEFORE it is asked for. A queue with artwork on its
     * items is what makes that possible; a queue without one, or no queue at all, means a player
     * this can never help. Measured per player rather than assumed - see `op queue`.
     */
    static String dumpQueues() {
        try {
            return dumpQueuesInner();
        } catch (Throwable t) {
            return "queue dump failed: " + Log.getStackTraceString(t);
        }
    }

    static String dumpQueuesInner() {
        List<MediaController> cs = Main.activeSessions();
        if (cs == null) return "sessions could not be read";
        StringBuilder sb = new StringBuilder("=== play queues ===\nprefetch: "
                + Prefetch.describe());
        for (MediaController c : cs) {
            sb.append('\n').append(c.getPackageName()).append(": ");
            List<android.media.session.MediaSession.QueueItem> q;
            try {
                q = c.getQueue();
            } catch (Throwable t) {
                sb.append("getQueue threw ").append(t);
                continue;
            }
            if (q == null || q.isEmpty()) {
                sb.append("no queue");
                continue;
            }
            long active = -1L;
            try {
                android.media.session.PlaybackState ps = c.getPlaybackState();
                if (ps != null) active = ps.getActiveQueueItemId();
            } catch (Throwable ignored) {
            }
            sb.append(q.size()).append(" items, active id=").append(active);
            int at = -1;
            for (int n = 0; n < q.size(); n++) {
                if (q.get(n).getQueueId() == active) {
                    at = n;
                    break;
                }
            }
            sb.append(" (index ").append(at).append(')');
            // The current item and the one after it: what a prefetch would have to work from.
            for (int n = Math.max(0, at); n < Math.min(q.size(), Math.max(0, at) + 2); n++) {
                android.media.MediaDescription d = q.get(n).getDescription();
                sb.append("\n  [").append(n).append("] id=").append(q.get(n).getQueueId())
                        .append(' ');
                if (d == null) {
                    sb.append("no description");
                    continue;
                }
                sb.append('"').append(d.getTitle()).append('"');
                sb.append(" icon=");
                android.graphics.Bitmap ib = null;
                try {
                    ib = d.getIconBitmap();
                } catch (Throwable ignored) {
                }
                if (ib != null) sb.append(ib.getWidth()).append('x').append(ib.getHeight());
                else sb.append("none");
                sb.append(" iconUri=").append(d.getIconUri());
                sb.append(" mediaId=").append(d.getMediaId());
                // The three fields a by-name lyric search needs, and where the only one of them
                // that has no field of its own could be hiding. Printed rather than assumed:
                // whether the read-ahead can ask the by-name half at all is decided entirely by
                // whether the player fills these in, and that cannot be reasoned about.
                sb.append("\n      subtitle=").append(d.getSubtitle())
                        .append(" description=").append(d.getDescription());
                android.os.Bundle ex = null;
                try {
                    ex = d.getExtras();
                } catch (Throwable ignored) {
                }
                if (ex == null || ex.isEmpty()) {
                    sb.append(" extras=none");
                } else {
                    sb.append(" extras={");
                    for (String k : ex.keySet()) {
                        Object v = ex.get(k);
                        String s = v == null ? "null" : v.toString();
                        sb.append(k).append('=')
                                .append(s.length() > 60 ? s.substring(0, 60) + "..." : s)
                                .append(' ');
                    }
                    sb.append('}');
                }
            }
        }
        return sb.toString();
    }

    /** One line about the doze clock's colour state, readable from `am broadcast`. */
    static String aodProbe() {
        boolean sbDone = false;
        StringBuilder sb = new StringBuilder("aodprobe cover=" + Main.sCoverMode
                + " screenOn=" + Main.sScreenOn + " grey=" + Main.sAodGrey
                + " aodsmall=" + Main.sAodSmall + " fullAod=" + Main.fullAodOn()
                + " aodFull=" + ClockCollapse.aodFullScreen()
                + " held=" + ClockCollapse.aodHeld()
                + " coverGlass=" + (ClockGlass.sAodCoverGlassSet
                        ? ClockGlass.sAodCoverGlass[0] + "," + ClockGlass.sAodCoverGlass[1] + ","
                          + ClockGlass.sAodCoverGlass[2] + " fill=" + ClockGlass.sAodCoverGlass[3]
                        : "none")
                + " guardHits=" + ClockGlass.sMiGlassGuardHits);
        // The clock's own state, because the whole setting is about where it is drawn: phase,
        // the pose being held, and the two the AOD recorded for the wake to start from.
        sb.append(" | ").append(ClockCollapse.describe());
        // And which route took each of the last few entries, with what it found - a wake that
        // comes out two different ways is only visible here; see ClockCollapse.noteEntry.
        sb.append(" | ").append(ClockCollapse.entries());
        // And where the clock has been placed since it last settled - a pose that moves after
        // the spring has landed is one of these inputs moving; see ClockCollapse.notePose.
        sb.append(" | ").append(ClockCollapse.poses());
        View date = ClockViews.visibleDate();
        sb.append(" | date=").append(date == null ? "none" : geomOf(date));
        for (View root : Main.clockRoots()) {
            View g = Main.clockTarget(root);
            if (g == null) continue;
            sb.append(" | tg scale=").append(g.getScaleY()).append(" ty=")
              .append(g.getTranslationY()).append(" vis=").append(g.getVisibility());
            // Every view between the clock and the root that is scaling it, named. Which one
            // holds the doze's 0.9516 is the difference between "the whole lock screen is
            // zooming out of the AOD" and "the clock's own container is", and nothing else
            // says: the pre-draw only ever sees the product. See ClockCollapse.aboveScaleY.
            sb.append(" chain=");
            float prod = 1f;
            for (android.view.ViewParent p = g.getParent(); p instanceof View;
                 p = ((View) p).getParent()) {
                View v2 = (View) p;
                prod *= v2.getScaleY();
                if (v2.getScaleY() != 1f || v2.getTranslationY() != 0f) {
                    sb.append(' ').append(Main.idOf(v2)).append("=sy").append(Main.r3(v2.getScaleY()))
                      .append("/ty").append(Main.r1(v2.getTranslationY()));
                }
            }
            sb.append(" product=").append(Main.r3(prod));
            break;
        }
        for (View root : Main.clockRoots()) {
            for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                try {
                    int rid = root.getContext().getResources()
                            .getIdentifier(id, "id", "com.android.systemui");
                    View t = rid == 0 ? null : root.findViewById(rid);
                    if (t == null) continue;
                    float[] g = (float[]) Xp.getObjectField(t, "glassData");
                    if (g == null || g.length < 42) continue;
                    sb.append(" | ").append(id).append(" vis=").append(t.getVisibility())
                      .append(" rgb=").append(g[11]).append(',').append(g[12]).append(',').append(g[13])
                      .append(" fill=").append(g[36]);
                    if (t.getVisibility() == View.VISIBLE && !sbDone) {
                        sbDone = true;
                        sb.append(" ALL=[");
                        for (int j = 0; j < g.length; j++) {
                            if (j > 0) sb.append(',');
                            sb.append(j).append(':').append(g[j]);
                        }
                        sb.append(']');
                        Object info = Xp.getObjectField(root, "mClockStyleInfo");
                        if (info == null) {
                            View clock = ((ViewGroup) root).getChildAt(0);
                            info = Xp.getObjectField(clock, "mClockStyleInfo");
                        }
                        if (info != null) {
                            sb.append(" style=[pri=")
                              .append(Xp.callMethod(info, "getPrimaryColor")).append(" sec=")
                              .append(Xp.callMethod(info, "getSecondaryColor")).append("]");
                        } else {
                            sb.append(" style=none");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    /**
     * Every callback queued on the main thread's Choreographer and every animator its
     * AnimationHandler is running, by class - a view asking for frames forever shows up here as
     * the same entry on every read.
     */
    static String frameCensus() {
        StringBuilder sb = new StringBuilder();
        try {
            Object ch = android.view.Choreographer.getInstance();
            Object[] queues = (Object[]) reflectField(ch, "mCallbackQueues");
            for (int q = 0; q < queues.length; q++) {
                for (Object rec = reflectField(queues[q], "mHead"); rec != null;
                     rec = reflectField(rec, "next")) {
                    Object action = reflectField(rec, "action");
                    sb.append(" q").append(q).append('=').append(censusName(action));
                    Object vri = action == null ? null : reflectField(action, "this$0");
                    if (vri != null && vri.getClass().getName().equals("android.view.ViewRootImpl")) {
                        describeDirty(vri, sb);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" choreographer: ").append(t);
        }
        try {
            Object h = Class.forName("android.animation.AnimationHandler")
                    .getMethod("getInstance").invoke(null);
            for (Object cb : (java.util.List<?>) reflectField(h, "mAnimationCallbacks")) {
                if (cb == null) continue;
                sb.append(" anim=").append(censusName(cb));
                if (cb instanceof android.animation.ObjectAnimator) {
                    sb.append(" target=").append(
                            censusName(((android.animation.ObjectAnimator) cb).getTarget()));
                }
                if (cb instanceof android.animation.AnimatorSet) {
                    for (android.animation.Animator a
                            : ((android.animation.AnimatorSet) cb).getChildAnimations()) {
                        sb.append(" child=").append(censusName(a));
                        if (a instanceof android.animation.ObjectAnimator) {
                            sb.append("->").append(censusName(
                                    ((android.animation.ObjectAnimator) a).getTarget()));
                        }
                        sb.append(" running=").append(a.isRunning());
                    }
                }
                if (cb instanceof android.animation.ValueAnimator) {
                    android.animation.ValueAnimator va = (android.animation.ValueAnimator) cb;
                    sb.append(" dur=").append(va.getDuration())
                            .append(" repeat=").append(va.getRepeatCount());
                    Object ls = reflectField(va, "mUpdateListeners");
                    if (ls instanceof java.util.List) {
                        for (Object l : (java.util.List<?>) ls) {
                            sb.append(" upd=").append(censusName(l));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" animators: ").append(t);
        }
        return sb.length() == 0 ? "nothing queued" : sb.toString();
    }

    /**
     * The window a traversal is queued for, and the deepest views in it that asked for one: the
     * ones marked invalidated (PFLAG_INVALIDATED) or waiting on a layout, with no child that is.
     * A view that invalidates itself from its own draw is the one left standing on every read.
     */
    static void describeDirty(Object vri, StringBuilder sb) {
        try {
            Object lp = reflectField(vri, "mWindowAttributes");
            if (lp instanceof android.view.WindowManager.LayoutParams) {
                sb.append(" window=").append(((android.view.WindowManager.LayoutParams) lp).getTitle());
            }
            sb.append(" layoutReq=").append(reflectField(vri, "mLayoutRequested"));
            Object root = reflectField(vri, "mView");
            if (root instanceof View) {
                java.util.List<String> hits = new java.util.ArrayList<>();
                collectDirty((View) root, hits);
                sb.append(" dirty=").append(hits);
            }
        } catch (Throwable t) {
            sb.append(" dirty: ").append(t);
        }
    }

    /** @return whether this view or anything under it is dirty */
    static boolean collectDirty(View v, java.util.List<String> hits) {
        // A view that is not visible keeps its flags from whenever it was hidden, and draws
        // nothing: it cannot be what the window is redrawing for.
        if (v.getVisibility() != View.VISIBLE) return false;
        boolean below = false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) below |= collectDirty(g.getChildAt(i), hits);
        }
        int flags = 0;
        try {
            Object f = reflectField(v, "mPrivateFlags");
            if (f instanceof Integer) flags = (Integer) f;
        } catch (Throwable ignored) {
        }
        boolean invalidated = (flags & 0x80000000) != 0;
        boolean layout = v.isLayoutRequested();
        if ((invalidated || layout) && !below && hits.size() < 20) {
            String id = "";
            try {
                if (v.getId() != View.NO_ID) id = "#" + v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable ignored) {
            }
            hits.add(v.getClass().getName() + id + (invalidated ? "(inv)" : "") + (layout ? "(lay)" : "")
                    + (v.isShown() ? "" : "(hidden)"));
        }
        return below || invalidated || layout;
    }

    /** A class name, and the outer instance's for an anonymous or inner one. */
    static String censusName(Object o) {
        if (o == null) return "null";
        String n = o.getClass().getName();
        try {
            Object outer = reflectField(o, "this$0");
            if (outer != null) n += "<" + outer.getClass().getName();
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** A field by name anywhere up the class chain, or null when there is none. */
    static Object reflectField(Object o, String name) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Reports the whole walk on demand - `--es op oemtrans [--ef y 1200]`.
     *
     * `ClockTranslationAnimator.computeTranslationY(float)` is what the OEM itself calls to turn a
     * notifY into the clock's translation. This walks to it through the clock container's fields
     * and names the step that fails.
     */
    static void dumpOemTranslation(final float y) {
        final View v = Main.sContainer;
        if (v == null) { Xp.log(Main.TAG + "oemtrans: no container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                Object helper = null, anim = null, tr = null;
                try {
                    helper = Xp.getObjectField(v, "mAnimationHelper");
                    Xp.log(Main.TAG + "oemtrans: mAnimationHelper = "
                            + (helper == null ? "null" : helper.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "oemtrans: mAnimationHelper FAILED: " + t);
                }
                try {
                    anim = helper == null ? null : Xp.getObjectField(helper, "mClockAnima");
                    Xp.log(Main.TAG + "oemtrans: mClockAnima = "
                            + (anim == null ? "null" : anim.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "oemtrans: mClockAnima FAILED: " + t);
                }
                try {
                    tr = anim == null ? null : Xp.getObjectField(anim, "clockTranslationAnimator");
                    Xp.log(Main.TAG + "oemtrans: clockTranslationAnimator = "
                            + (tr == null ? "null" : tr.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "oemtrans: clockTranslationAnimator FAILED: " + t);
                }
                if (tr == null) { Xp.log(Main.TAG + "oemtrans: chain stops here"); return; }
                try {
                    java.lang.reflect.Method m = tr.getClass()
                            .getMethod("computeTranslationY", float.class);
                    m.setAccessible(true);
                    float oem = (Float) m.invoke(tr, y);
                    View d = ClockViews.visibleDate();
                    int[] loc = new int[2];
                    if (d != null) d.getLocationOnScreen(loc);
                    float layout = d == null ? 0f : layoutScreenY(d);
                    Xp.log(Main.TAG + "oemtrans: computeTranslationY(" + y + ") = " + oem
                            + " | date layoutScreenY=" + Main.r1(layout)
                            + " + oem = " + Main.r1(layout + oem)
                            + " | measured onScreen=" + (d == null ? -1 : loc[1])
                            + " diff=" + (d == null ? Float.NaN : Main.r1(layout + oem - loc[1])));
                } catch (Throwable t) {
                    Xp.log(Main.TAG + "oemtrans: computeTranslationY FAILED: " + t);
                }
            }
        });
    }

    /** The view's screen y from the LAYOUT alone - no translation anywhere in the chain. */
    static float layoutScreenY(View v) {
        float y = 0f;
        for (int guard = 0; guard < 32 && v != null; guard++) {
            y += v.getTop();
            android.view.ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return y;
    }

    static void dumpViewTree(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        try {
            String name = v.getResources().getResourceEntryName(v.getId());
            sb.append(" #").append(name);
        } catch (Throwable ignored) {
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(' ').append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
            sb.append(" scale=").append(v.getScaleX()).append('/').append(v.getScaleY());
        }
        // translationX was missing here and cost an afternoon: album_art reported an x that
        // overlapped header_title, which is impossible from layout alone. getLocationInWindow
        // maps (0,0) through the view's OWN matrix before adding mLeft, so a translation or a
        // mirror moves the reported origin without moving the view's slot.
        // The layout slot, which is what the user actually touches. It differs from the
        // reported origin above whenever the view carries a transform of its own.
        sb.append(" L=").append(v.getLeft()).append(',').append(v.getTop());
        if (v.getRotationX() != 0f) sb.append(" rotX=").append(v.getRotationX());
        if (v.getRotationY() != 0f) sb.append(" rotY=").append(v.getRotationY());
        if (v.getRotation() != 0f) sb.append(" rot=").append(v.getRotation());
        if (v.getTranslationX() != 0f) sb.append(" tx=").append(v.getTranslationX());
        if (v.getTranslationY() != 0f) sb.append(" ty=").append(v.getTranslationY());
        if (v.getPivotX() != v.getWidth() / 2f) sb.append(" pivotX=").append(v.getPivotX());
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            sb.append(" textSize=").append(t.getTextSize())
              .append(" fontVar=").append(t.getFontVariationSettings())
              .append(" text=\"").append(t.getText()).append('"');
        }
        Xp.log(Main.TAG + sb);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    static void dumpInfo() {
        View v = Main.sContainer;
        if (v == null) {
            Xp.log(Main.TAG + "no clock container");
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Xp.log(Main.TAG + "container onScreen=[" + loc[0] + "," + loc[1] + "] size="
                + v.getWidth() + "x" + v.getHeight());
        try {
            Xp.log(Main.TAG + "getClockBottom()=" + Xp.callMethod(v, "getClockBottom"));
        } catch (Throwable t) {
            Xp.w(Main.TAG + "getClockBottom failed: " + t);
        }
        try {
            Xp.log(Main.TAG + "getNotificationClockTop()="
                    + Xp.callMethod(v, "getNotificationClockTop"));
        } catch (Throwable t) {
            Xp.w(Main.TAG + "getNotificationClockTop failed: " + t);
        }
        logViewById(v, "mi_media_controls");
        logViewById(v, "album_art_image");
    }

    /**
     * Reads the OEM's own miuix AnimConfig objects off the live clock animation, so we can
     * copy the real curve instead of guessing a duration.
     * KeyguardClockContainer.mAnimationHelper.mClockAnima -> AllInOneClockAnimation
     */
    static void dumpAnimConfigs() {
        View v = Main.sContainer;
        if (v == null) {
            Xp.log(Main.TAG + "no clock container");
            return;
        }
        try {
            Object helper = Xp.getObjectField(v, "mAnimationHelper");
            Object anim = Xp.getObjectField(helper, "mClockAnima");
            Xp.log(Main.TAG + "clock animation impl = " + anim.getClass().getName());
            for (String f : new String[]{"stateChangedAnimConfig", "notifsChangedAnimConfig",
                    "defaultAnimConfig", "animRunningConfig"}) {
                try {
                    Object cfg = Xp.getObjectField(anim, f);
                    Xp.log(Main.TAG + f + " = " + describe(cfg));
                } catch (Throwable t) {
                    Xp.w(Main.TAG + f + " unavailable: " + t);
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "dumpAnimConfigs failed: " + Log.getStackTraceString(t));
        }
    }

    /** Generic reflective field dump, one level deep, for opaque OEM value objects. */
    static String describe(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append('{');
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                Object val = f.get(o);
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int n = java.lang.reflect.Array.getLength(val);
                    for (int k = 0; k < n; k++) {
                        if (k > 0) arr.append(',');
                        arr.append(java.lang.reflect.Array.get(val, k));
                    }
                    val = arr.append(']').toString();
                } else if (val != null && val.getClass().getName().startsWith("miuix.")) {
                    val = val.getClass().getSimpleName() + describeShallow(val);
                }
                sb.append(f.getName()).append('=').append(val).append(' ');
            } catch (Throwable ignored) {
            }
        }
        return sb.append('}').toString();
    }

    /** Walks the whole class hierarchy - miuix ease styles keep their params in a super. */
    static String describeShallow(Object o) {
        StringBuilder sb = new StringBuilder("{");
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            sb.append('<').append(c.getSimpleName()).append("> ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(o);
                    if (val != null && val.getClass().isArray()) {
                        StringBuilder arr = new StringBuilder("[");
                        int n = java.lang.reflect.Array.getLength(val);
                        for (int k = 0; k < n; k++) {
                            if (k > 0) arr.append(',');
                            arr.append(java.lang.reflect.Array.get(val, k));
                        }
                        val = arr.append(']').toString();
                    }
                    sb.append(f.getName()).append('=').append(val).append(' ');
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.append('}').toString();
    }

    static void logViewById(View anchor, String idName) {
        try {
            Context c = anchor.getContext();
            int id = c.getResources().getIdentifier(idName, "id", "com.android.systemui");
            if (id == 0) {
                Xp.log(Main.TAG + idName + ": id not found");
                return;
            }
            View found = anchor.getRootView().findViewById(id);
            if (found == null) {
                Xp.log(Main.TAG + idName + ": view not present");
                return;
            }
            int[] loc = new int[2];
            found.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + found.getWidth(), loc[1] + found.getHeight());
            Xp.log(Main.TAG + idName + ": " + r + " vis=" + found.getVisibility());
        } catch (Throwable t) {
            Xp.w(Main.TAG + idName + " lookup failed: " + t);
        }
    }
}
