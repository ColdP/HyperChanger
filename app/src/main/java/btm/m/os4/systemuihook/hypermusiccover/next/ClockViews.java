// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.List;
import android.view.View;

/**
 * Finding the clock and measuring it: which views are the time, the date and the signature in
 * whatever style is on screen, which part of the OEM's layout each belongs to, and the boxes of
 * the glyphs as drawn (ink boxes, cached per view) that the collapse and the room are built on.
 *
 * Carved out of Main as it was (2026-10-14, after AodColour): every member moved whole with its
 * comments, references across the line prefixed with Main. or ClockViews., nothing else changed.
 */
public final class ClockViews {
    private ClockViews() {}

    /** The pooled glyph box as `l,t,r,b`, or none. */
    static String boxOf(RectF b) {
        if (b == null) return "none";
        return Main.r1(b.left) + "," + Main.r1(b.top) + "," + Main.r1(b.right) + "," + Main.r1(b.bottom);
    }

    /**
     * Finds whatever draws the time, without knowing what the style calls it.
     *
     * Looking it up by id does not scale: `time_group` and `hour_view` belong to all_in_one,
     * `time_view` to the classic style, and the user has more styles than that. What every style
     * does have in common is a view that draws a clock face, and there are only two kinds:
     *
     * - one of com.miui.clock's own TimeViews, recognisable because it can report the bounds of
     *   the glyphs it strokes (`getTextBoundsWithPosition`);
     * - an ordinary TextView holding something that reads as a time and nothing else.
     *
     * When several turn up - a style that draws the hour and the minute as separate views - the
     * one to scale is their common parent, so they shrink together. Unless that parent also
     * holds the date, which would drag the date along with them; then the first one is scaled on
     * its own, which is wrong-ish but visibly wrong rather than silently absent.
     */
    static View resolveTimeTarget(View root) {
        // The OEM's own answer first, before any guess at the tree.
        //
        // Every clock style implements IClockView.getIClockView(ClockViewType), and that is how
        // the OEM's own animation code finds the pieces it moves - so it is the one lookup that
        // keeps working for a style this module has never seen. The guess below does not: a
        // rhombus clock draws its digits as vector art, a doodle clock as bitmaps and the
        // oriental ones in Chinese numerals, so "a TextView holding digits and colons" names
        // none of them, and on those styles the clock simply did not shrink.
        for (String type : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME"}) {
            View v = oemPart(root, type);
            if (Main.usable(v) && drawnUnder(v, root)) return v;
        }
        java.util.List<View> parts = new java.util.ArrayList<>();
        for (String type : new String[]{"FULL_HOUR", "FULL_MINUTE", "HOUR1", "HOUR2",
                "MIN1", "MIN2"}) {
            View v = oemPart(root, type);
            if (Main.usable(v) && drawnUnder(v, root)) parts.add(v);
        }
        if (parts.size() == 1) return parts.get(0);
        if (parts.size() > 1) {
            View top = commonAncestor(parts);
            // A parent that also holds the date must not be scaled: the date is what the
            // placement anchors to, and dragging it along makes the anchor chase itself.
            View date = visibleDate();
            if (top != null && !(date != null && isDescendant(top, date))) return top;
        }

        java.util.List<View> hits = new java.util.ArrayList<>();
        collectTimeViews(root, hits);
        // An INVISIBLE container is the normal state of the background layer in cover mode -
        // the clock is being drawn by its notification variant - and requiring the walk to
        // start on a VISIBLE view is why it never started. The children are exactly as visible
        // as they ever are; the container's own visibility says nothing about whether a clock
        // is in there. This alone is what kept magazine, classic and the oriental styles out.
        if (hits.isEmpty() && root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), hits);
        }
        if (hits.isEmpty()) return clockContainerOrNull(root);
        if (hits.size() == 1) return hits.get(0);
        View top = commonAncestor(hits);
        if (top == null) return hits.get(0);
        View date = visibleDate();
        return date != null && isDescendant(top, date) ? hits.get(0) : top;
    }

    /**
     * The OEM's own clock container, as a last resort, or null.
     *
     * For a style whose clock is a single view - the doodle answers ALL_VIEW and CLOCK_CONTAINER
     * with the same graffiti artwork and names nothing else - this is the only answer there is,
     * and it is the right one: on that style the date and the time are drawn into the same
     * picture, so scaling it scales the clock and not something else.
     *
     * Never when the container holds a date this module found. On a style that splits the two,
     * scaling the container would drag the date along, and the date is what the placement hangs
     * the clock from.
     */
    static View clockContainerOrNull(View root) {
        View cc = oemPart(root, "CLOCK_CONTAINER");
        if (!Main.usable(cc)) return null;
        View date = visibleDate();
        return date != null && isDescendant(cc, date) ? null : cc;
    }

    /**
     * The view at the top of one clock layer, which is that style's own clock: every style's
     * root view implements MiuiClockController.IClockView and is what the OEM asks about itself.
     *
     * Taken from the tree rather than from MiuiClockController.mClockView because the foreground
     * layer has a controller of its own and only one of the two is reachable from the container
     * this module hooks.
     */
    static View clockViewOf(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        // The one that is showing. A layer can hold more than one face: oriental A keeps its
        // big clock and its notification variant side by side and shows one of them, and the
        // first child was the hidden one - every OEM part asked of it named views nobody sees.
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getAlpha() > 0.01f) return c;
        }
        return g.getChildCount() > 0 ? g.getChildAt(0) : null;
    }

    /**
     * Whether v is drawn at all inside its clock layer: it and every view between it and root
     * VISIBLE and not faded out. The root itself is not asked - see resolveTimeTarget for why.
     *
     * usable() looks at the view alone, and that was not enough: oriental A hides its big clock
     * face by alpha 0 in cover mode, its digits stay VISIBLE inside it, and the collapse scaled
     * them while the clock on screen stayed full size.
     */
    static boolean drawnUnder(View v, View root) {
        for (View p = v; p != null; ) {
            if (p == root) return true;
            if (p.getVisibility() != View.VISIBLE || p.getAlpha() <= 0.01f) return false;
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return false;
    }

    /**
     * One of com.miui.clock.module.ClockViewType's constants by name, or null on a build that
     * has no such constant. The enum is the OEM's, and a name that exists today is not promised
     * to exist on the next HyperOS - so a miss is a miss, not a failure.
     */
    static Object viewType(String name) {
        java.util.Map<String, Object> m = Main.sViewTypes;
        if (m == null) {
            m = new java.util.HashMap<>();
            try {
                Class<?> cls = Xp.findClass(Main.CLS_CLOCK_VIEW_TYPE,
                        Main.sContainerCls == null ? null : Main.sContainerCls.getClassLoader());
                Object[] all = cls.getEnumConstants();
                if (all != null) {
                    for (Object o : all) m.put(((Enum<?>) o).name(), o);
                }
            } catch (Throwable t) {
                Xp.log(Main.TAG + "no ClockViewType: " + t);
            }
            Main.sViewTypes = m;
        }
        return m.get(name);
    }

    /**
     * What the OEM itself says draws one named part of the clock in one layer, or null.
     *
     * This is the lookup that does not have to be maintained: the OEM finds everything it
     * animates this way, so a clock style added in a future build answers it too.
     */
    static View oemPart(View root, String type) {
        View clock = clockViewOf(root);
        if (clock == null) return null;
        Object t = viewType(type);
        if (t == null) return null;
        try {
            Object got = Xp.callMethod(clock, "getIClockView", t);
            return got instanceof View ? (View) got : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The nearest common ancestor of a set of views, or null if they share none within reach. */
    static View commonAncestor(java.util.List<View> views) {
        if (views == null || views.isEmpty()) return null;
        View top = views.get(0);
        for (int guard = 0; guard < 12 && top != null; guard++) {
            if (containsAll(top, views)) return top;
            top = top.getParent() instanceof View ? (View) top.getParent() : null;
        }
        return null;
    }

    static void collectTimeViews(View v, java.util.List<View> out) {
        if (!Main.usable(v) || v.getAlpha() <= 0.01f) return;
        if (isTimeView(v)) {
            out.add(v);
            return;
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), out);
    }

    static boolean isTimeView(View v) {
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
        }
        return v instanceof TextView && looksLikeTime(((TextView) v).getText());
    }

    /**
     * "14:51", "14", "9:05" - and not "9月9日周三 七月廿八", "9/9", "32°" or a signature.
     * Digits and colons only, and short: the date lines on these styles are none of that.
     */
    static boolean looksLikeTime(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().trim();
        if (looksLikeChineseTime(t)) return true;
        if (t.isEmpty() || t.length() > 5) return false;
        boolean digit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') { digit = true; continue; }
            if (c == ':' || c == '\uFF1A') continue;
            return false;
        }
        return digit;
    }

    /**
     * "十七時零五", "九点〇八" - the oriental C face writes its time in Chinese numerals in one
     * TextView, and nothing else recognised it: the collapse fell back to the whole container,
     * date included, and measured that block as the glyph. Numerals with one hour/minute marker
     * at most, short, and at least one numeral on each side of it - a date ("十月十四日") has a
     * day marker and is not this.
     */
    static boolean looksLikeChineseTime(String t) {
        if (t.length() < 2 || t.length() > 8) return false;
        int marks = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("〇零一二两三四五六七八九十".indexOf(c) >= 0) continue;
            if ("時时点點".indexOf(c) >= 0 && i > 0 && i < t.length() - 1) { marks++; continue; }
            if (c == '分' && i == t.length() - 1 && i > 0) continue;
            return false;
        }
        return marks == 1;
    }

    static boolean containsAll(View ancestor, java.util.List<View> views) {
        for (View v : views) {
            if (!isDescendant(ancestor, v)) return false;
        }
        return true;
    }

    static boolean isDescendant(View ancestor, View v) {
        for (int guard = 0; guard < 32 && v != null; guard++) {
            if (v == ancestor) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    /**
     * Resolved clock views, per tree. This is asked for on every frame of the squeeze, and
     * resolving it means a resource lookup and possibly a walk of the whole layer.
     *
     * Dropped when the keyguard is rebuilt, which is also when the clock style can have changed
     * underneath us - see the onAttachedToWindow hook.
     */
    static final java.util.WeakHashMap<View, View> sClockTargets =
            new java.util.WeakHashMap<>();

    /** The clock size a tree was answered "no clock here" at. See clockTarget(). */
    static final java.util.WeakHashMap<View, Integer> sNoClockAt =
            new java.util.WeakHashMap<>();

    /** The date view the cache above was filled against. See placeCollapsedClock(). */
    static View sDateView;

    /**
     * The box the digits actually ink, in the target view's own coordinates.
     *
     * Every clock style lays its glyphs out somewhere else - the single-line style puts hour and
     * minute side by side, the stacked one draws the hour at 650 and the minute at 909, the
     * classic one is a single left-aligned line - so this is measured, never assumed.
     */
    static RectF inkBox(View root, View target) {
        if (target == null) return null;
        RectF box = inkBoxOnce(root, target);
        if (box == null && sTimeViews.remove(target) != null) {
            // The remembered digit views drew nothing: the OEM swapped the clock's layout variant
            // under them. Collect again, once.
            box = inkBoxOnce(root, target);
        }
        return box;
    }

    static RectF inkBoxOnce(View root, View target) {
        RectF box = null;
        java.util.List<View> times = timeViewsOf(target);
        // Nothing inside the target reads as a clock, which is the normal case for a style
        // whose digits are not text. The OEM's own parts still name them, so measure those.
        //
        // Not on all_in_one: its target is time_group, the OEM calls the time_group itself
        // FULL_HOUR, and measuring a container as if it were its own glyph is the whole block
        // rather than the ink in it. That style's digits answer to getTextBoundsWithPosition
        // and are found above, so the fallback has nothing to add there and a great deal to
        // spoil - the foreground tree draws no ink in the notification variant, and handing it
        // a full-size box is what turned a correct 578x448 ink box into 1200x1159.
        boolean anchoredTarget = findClockView(root, "time_group") == target;
        if (times.isEmpty() && !anchoredTarget) {
            // The individual digits if the OEM names them - FULL_HOUR and FULL_MINUTE are the
            // hour and minute CONTAINERS, which on a style that draws both rows around a gap
            // is most of the clock's height - and only then those two.
            for (String part : new String[]{"HOUR1", "HOUR2", "MIN1", "MIN2"}) {
                View v = oemPart(root, part);
                if (v != target && Main.usable(v) && isDescendant(target, v)) times.add(v);
            }
            if (times.isEmpty()) {
                for (String part : new String[]{"FULL_HOUR", "FULL_MINUTE"}) {
                    View v = oemPart(root, part);
                    if (v != null && v != target && Main.usable(v) && isDescendant(target, v)) {
                        times.add(v);
                    }
                }
            }
            // The OEM's own index is not the only way a style names its digits, and on the
            // ones that draw them as art it answers with a CONTAINER: rhombus's FULL_HOUR is
            // `hour_container`, 1200x1408 wrapped around 489px of digits. Measuring that is the
            // "whole block as the glyph" mistake from the other side - the collapse divides by
            // 1322 instead of 489, so the clock comes out at 43px on a screen that asked for
            // 167. These views do give every digit an id of its own; measured on the device:
            //
            //   rhombus      hour1/2, minute1/2 (MiuiClockNumberView, 489px)
            //   magazine_c   current_time_hour_style1 / _minute_style1 (MiuiTextGlassView, 473px)
            //   doodle       time_hour, time_minute (ImageView, 514px)
            //   depth_pets   time_hour, time_hour_right, time_minute, time_minute_right (400px)
            //   eastern_b    time_below_view_hour1/2, _minute1/2 (TextView, 224px)
            //
            // Read through the same measurement loop below, which already knows a TextView
            // reports its own ink and an ImageView's bounds ARE its ink.
            for (String id : DIGIT_IDS) {
                View v = findByEntryName(target, id);
                if (v != null && v != target && Main.usable(v)) times.add(v);
            }
            // A style whose whole clock really is one view - the doodle, when nothing above
            // answered - has nothing left to measure but itself, and its own bounds are then
            // exactly the clock. Safe here and nowhere else: the guard above keeps this off
            // all_in_one, whose time_group is a container that would report the whole block.
            if (times.isEmpty() && Main.usable(target)) return ownBox(target);
        }
        // Still nothing measurable. Left null rather than falling back to the target's own
        // bounds: a tree that has a clock but no ink in it yet is a clock that has not been
        // through a layout pass, and the box that would come out of it is the block, not the
        // glyphs - the same half-a-box mistake clockPivotX() already documents.
        if (times.isEmpty()) return null;
        boolean trace = Diagnostics.sGeomTraceUntil != 0L;
        StringBuilder who = trace ? new StringBuilder("inkBox target=" + Main.viewIdOf(target)
                + " collected=" + times.size() + " :") : null;
        for (View v : times) {
            RectF r = null;
            try {
                r = (RectF) Xp.callMethod(v, "getTextBoundsWithPosition");
            } catch (Throwable ignored) {
            }
            if (r == null || r.height() <= 0f) {
                r = v instanceof TextView ? textInkBox((TextView) v) : null;
                // Digits drawn as vector art or as a bitmap have no text to measure, and a view
                // whose whole content is the glyph has no padding to undo - its own bounds are
                // the ink. That is a rhombus or a doodle digit.
                //
                // NOT for one of the OEM's own TimeViews. A TimeView that cannot report its
                // bounds has simply not been drawn yet, and its bounds are the entire clock
                // container - measuring those is the "block instead of glyphs" mistake that
                // turned all_in_one's 578x448 ink box into 1200x1159, and with it the pivot the
                // whole collapse hangs from.
                if (r == null && !reportsGlyphs(v)) r = ownBox(v);
                if (r == null) continue;
            } else {
                r = new RectF(r);
            }
            // Offsets accumulated up to the target, not just one level: a style is free to wrap
            // its digits in as many layouts as it likes between the two.
            //
            // And, off all_in_one, each view's own transform on the way: the rhombus face lays
            // its digits out as a diamond and, for its notification layout, scales every digit to
            // 0.44 and moves it into one row. Read off the layout alone the box was still the
            // diamond - 872x1322 for a row 214px tall - and the clock was sized off that.
            // all_in_one's digits are left as they were measured: nothing of the OEM's moves
            // them, and its collapse was tuned on exactly these numbers.
            boolean viaMatrix = !anchoredTarget;
            for (View p = v; p != null && p != target; ) {
                if (viaMatrix) {
                    android.graphics.Matrix mx = p.getMatrix();
                    if (!mx.isIdentity()) mx.mapRect(r);
                }
                r.offset(p.getLeft(), p.getTop());
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            if (box == null) box = r; else box.union(r);
            // The tallest single view, not the union: this is the one number the collapse should
            // divide by. See sInkUnit.
            if (r != null && r.height() > 0f
                    && (Float.isNaN(sInkUnit) || r.height() > sInkUnit)) sInkUnit = r.height();
            if (trace) who.append(' ').append(Main.viewIdOf(v)).append('=').append(Main.r1(r.height()));
        }
        if (trace) {
            who.append(" -> ").append(box == null ? "null" : boxOf(box))
               .append(" unit=").append(Main.r1(sInkUnit));
            Xp.log(Main.TAG + who);
        }
        return box;
    }

    /**
     * The views that draw the time inside a clock target, remembered for a moment.
     *
     * Collecting them walks the subtree and asks every view's class for a method by reflection -
     * a thrown NoSuchMethodException per view that is not a digit. Fine for a probe, not for the
     * cover clock's pre-draw, which measures on every frame. The list is only a list of views to
     * ask for their bounds; the bounds themselves are always read live. Re-collected when any of
     * them stops being usable, and on every keyguard rebuild or date-view change forgetGlyphBox()
     * hears about. Not on a timer: re-collecting walks the tree with reflection, and a timer
     * firing mid-transition was a 4-8ms spike in the one frame it landed on.
     */
    static final java.util.HashMap<View, java.util.List<View>> sTimeViews =
            new java.util.HashMap<>();

    static java.util.List<View> timeViewsOf(View target) {
        java.util.List<View> hit = sTimeViews.get(target);
        if (hit != null) {
            boolean ok = true;
            for (View v : hit) {
                if (!Main.usable(v)) { ok = false; break; }
            }
            if (ok) return hit;
        }
        java.util.List<View> times = new java.util.ArrayList<>();
        collectTimeViews(target, times);
        // Only a non-empty answer is remembered: the fallbacks after it depend on the OEM's own
        // index and on ids, and an empty one is the case where the layout has not arrived yet.
        if (!times.isEmpty()) sTimeViews.put(target, times);
        return new java.util.ArrayList<>(times);
    }

    /**
     * The same lookup by NAME, walked over the subtree and matched against each view's own
     * resource entry name.
     *
     * `findClockView()` resolves a name through one package's resource table
     * (`com.android.systemui`) and that is where this was first written. Measured on the device
     * with the `op ink` probe: `time_hour` answers from there, but `hour1` (rhombus) and
     * `current_time_hour_style1` (magazine_c) are ids of the clock library's own and resolve to
     * 0, so the pass did nothing at all on exactly the two styles it was written for - and did
     * it silently, which is how a fix can land and change nothing. Reading the name each view
     * already carries does not care which table the id came from.
     */
    static View findByEntryName(View root, String name) {
        if (root == null) return null;
        try {
            if (root.getId() != View.NO_ID
                    && name.equals(root.getResources().getResourceEntryName(root.getId()))) {
                return root;
            }
        } catch (Throwable ignored) {
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findByEntryName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The ids styles give the views that draw a time. Not a substitute for the OEM's own index -
     * that is asked first, and on every style whose digits are text it answers correctly - but
     * the only thing that answers at all on the styles that draw them as vector art or bitmaps,
     * where the OEM's index stops at the container.
     *
     * Every one of these was read off the device with the `op ink` probe rather than guessed;
     * a name that is not here costs nothing, and a wrong one would put a container back into
     * the measurement, so the list stays to what was actually seen.
     */
    static final String[] DIGIT_IDS = {
            "time_hour", "time_minute", "time_hour_right", "time_minute_right",
            "hour1", "hour2", "minute1", "minute2",
            "current_time_hour_style1", "current_time_minute_style1",
            "time_below_view_hour1", "time_below_view_hour2",
            "time_below_view_minute1", "time_below_view_minute2",
            "tv_time", "tv_minute", "tv_hour",
    };

    /** Whether this view can report the bounds of what it draws - one of the OEM's TimeViews. */
    static boolean reportsGlyphs(View v) {
        if (v == null) return false;
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The bounds of a view whose whole content is the glyph it draws. */
    static RectF ownBox(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        return new RectF(0f, 0f, v.getWidth(), v.getHeight());
    }

    /**
     * The same measurement for a plain TextView, which is what the classic style's clock is.
     *
     * Paint.getTextBounds gives the ink relative to the baseline origin, so it has to be put
     * back together with the layout's baseline and the view's padding. Not getLineTop/Bottom:
     * those are the line box, ascent and descent included, and anchoring the collapse to a line
     * box instead of to the ink is exactly the mistake section 13 is about.
     */
    static RectF textInkBox(TextView t) {
        Layout lay = t.getLayout();
        CharSequence cs = t.getText();
        if (lay == null || lay.getLineCount() < 1 || cs == null || cs.length() == 0) return null;
        String txt = cs.toString();
        Rect ink = new Rect();
        t.getPaint().getTextBounds(txt, 0, txt.length(), ink);
        if (ink.width() <= 0 || ink.height() <= 0) return null;
        float x = t.getPaddingLeft() + lay.getLineLeft(0) + ink.left;
        float base = t.getPaddingTop() + lay.getLineBaseline(0);
        return new RectF(x, base + ink.top, x + ink.width(), base + ink.bottom);
    }

    /**
     * The union of the drawn digits across both trees, in the target's coordinates.
     *
     * The top anchors the placement, the middle decides the pivot, and the size is what the
     * preview draws at - all three off this one pooled measurement.
     */
    /**
     * How long a measured glyph box is trusted for.
     *
     * For the preview and the probes, which ask several times in a row. The cover clock asks for a
     * fresh one every frame instead: the OEM's squeeze and its wake growth both change the box
     * frame by frame, and mapping a stale box is exactly the one-frame-late error.
     */
    static RectF sGlyphCache;

    static long sGlyphAt;

    static final long GLYPH_TTL_MS = 120L;

    /** Drops the remembered box. Called from every place the layout is known to have changed. */
    static void forgetGlyphBox() {
        sGlyphCache = null;
        sGlyphAt = 0L;
        sTimeViews.clear();
    }

    /**
     * How tall ONE ROW of the clock's digits is, as opposed to how tall the ink block is.
     *
     * The two are the same number on every style that draws its time on a single line - which is
     * most of them, and why this went unnoticed - and they are nothing like each other on the
     * styles that stack the hour over the minute. Measured on the device: rhombus collects four
     * MiuiClockNumberViews of 489px each and their UNION is 1322, because the hour row and the
     * minute row sit 833px apart. The setting says "the height of the digits when collapsed",
     * so dividing by 1322 asks for digits of 62px while every other style gets 167 - the clock
     * comes out a third of the size it should be, and looks broken next to the others.
     *
     * Published by inkBox() as it measures, and taken as the largest across the trees for the
     * same reason the box is unioned: all_in_one draws its hour in one tree and its minute in
     * the other, and the row is as tall as its tallest digit.
     */
    static volatile float sInkUnit = Float.NaN;

    static float glyphUnit(RectF box) {
        float u = sInkUnit;
        return (Float.isNaN(u) || u <= 0f) ? box.height() : u;
    }

    static RectF glyphBox() {
        return glyphBox(false);
    }

    /**
     * @param fresh measure this frame, past the trust window - what the cover clock's pre-draw
     *              wants, since it maps whatever is about to be drawn
     */
    static RectF glyphBox(boolean fresh) {
        long now = android.os.SystemClock.uptimeMillis();
        RectF cached = sGlyphCache;
        if (!fresh && !Main.sProbeLive && cached != null && now - sGlyphAt < GLYPH_TTL_MS) {
            return new RectF(cached);
        }
        RectF box = null;
        sInkUnit = Float.NaN;
        boolean treeDrewNothing = false;
        for (View root : Main.clockRoots()) {
            View target = Main.clockTarget(root);
            RectF r = inkBox(root, target);
            if (r == null) {
                if (Main.usable(target)) treeDrewNothing = true;
                continue;
            }
            if (box == null) box = r; else box.union(r);
        }
        if (box == null) return null;
        if (!treeDrewNothing) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        // (the unit below is published either way - see glyphUnit())
        // A tree that HAS a clock in it but shows no ink is either a clock that has not been
        // through a layout pass, or a tree that is simply not drawing - and those two want
        // opposite answers. Half a box is what makes clockPivotX() take the left-aligned
        // branch: all_in_one draws the hour in one tree and the minute in the other, so the
        // half that happens to be measured first has a centre nowhere near the screen's and
        // the clock collapses towards its own left edge. That is the crooked small clock a
        // SystemUI restart used to leave behind.
        //
        // But answering "no box at all" whenever a tree is empty took a good measurement down
        // with it, and in cover mode that is the normal state: all_in_one draws the WHOLE clock
        // in the background tree (hour_view and minute_view both visible, the foreground copy
        // left at 0x0), while the foreground `#time_group` still answers the id lookup and is
        // still VISIBLE. Voiding on it meant placeCollapsedClock() never ran a single frame,
        // and the size slider did nothing at all - on the style that was supposed to be the
        // working one.
        //
        // So what is left standing is judged instead of thrown away: the union of everything
        // that DID measure is a whole clock exactly when it sits where the screen's centre is,
        // which is the same test clockPivotX() is about to make. Off-centre, it is half of one
        // and the other half is still coming.
        View any = null;
        for (View root : Main.clockRoots()) {
            if (Main.clockTarget(root) != null) { any = root; break; }
        }
        if (any == null) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        float screenW = any.getResources().getDisplayMetrics().widthPixels;
        if (Math.abs(box.centerX() - screenW / 2f) >= screenW * 0.05f) return null;
        sGlyphCache = box;
        sGlyphAt = now;
        return new RectF(box);
    }

    /**
     * {width, height, screen y of the top} for the collapsed clock at scale 1, for the app's
     * preview. Multiplying the first two by the clock scale gives exactly what
     * placeCollapsedClock() puts on screen: it pivots on the glyph top, so the top edge does
     * not move with the scale and this y holds for every scale.
     *
     * Derived the same way placement is - the date at its fixed target, the clock a gap below
     * it - so the preview follows the rule instead of a second set of numbers that can drift.
     */
    /**
     * {w, h, y, x, pivotX} for the collapsed clock at scale 1, in screen pixels.
     *
     * The box is the padded one, because that is what the captures cover; y and pivotX are the
     * two anchors the phone actually scales about - the glyph top and clockPivotX() - so the app
     * can reproduce the same transform: `left = pivotX + (x - pivotX) * scale`. Centred styles
     * come out centred and left-aligned ones stay left, without the app knowing which is which.
     */
    static float[] clockGeometry() {
        // The date is not required: it is what the anchored placement hangs the clock from, and
        // a style with no date view is scaled where it stands. Its height still enters the y the
        // preview puts the clock at, and with no date there is none to add.
        View date = visibleDate();
        RectF box = paddedGlyphBox();
        if (box == null) return null;
        View g = null;
        for (View root : Main.clockRoots()) {
            View t = Main.clockTarget(root);
            if (t != null) { g = t; break; }
        }
        if (g == null) return null;
        float d = g.getResources().getDisplayMetrics().density;
        return new float[]{box.width(), box.height(),
                (date == null ? 0f : ClockCollapse.coverDateY(date) + date.getHeight()) + Main.CLOCK_GAP_DP * d,
                g.getLeft() + box.left, g.getLeft() + Main.clockPivotX(g, glyphBox())};
    }

    static RectF paddedGlyphBox() {
        RectF box = glyphBox();
        if (box == null) return null;
        box.inset(-Main.CLOCK_PAD, -Main.CLOCK_PAD);
        return box;
    }

    static View findClockView(View root, String id) {
        int i = resId(root.getResources(), id);
        return i == 0 ? null : root.findViewById(i);
    }

    /**
     * getIdentifier() walks the package's resource table by name on every call, and the collapse
     * asks for the same handful of ids several times a frame - both clock roots, the clock target
     * inside each, the date, `time_group` for the anchored test. That is measurable against the
     * OEM doing its own animation at the same time and coming out smooth: driving y makes the
     * OEM recompute the variable font every frame, and this used to be added on top of it.
     *
     * The answer cannot change while the process lives - a build either has the id or it does
     * not - so it is remembered, including the 0 a build without it answers.
     */
    static final java.util.HashMap<String, Integer> sResIds = new java.util.HashMap<>();

    static int resId(android.content.res.Resources r, String name) {
        Integer hit = sResIds.get(name);
        if (hit != null) return hit;
        int id = r.getIdentifier(name, "id", "com.android.systemui");
        sResIds.put(name, id);
        return id;
    }

    /** The ids the OEM gives a date line, the plain one first. */
    static final String[] DATE_IDS = {
            "text_area", "current_date", "notification_date", "tv_data", "date_and_time",
            "date_and_week",
    };

    /** What the OEM itself calls the date, for the styles whose id is not in the list above. */
    static final String[] DATE_PARTS = {
            "TEXT_AREA", "NOTIFICATION_DATE", "FULL_DATE", "FULL_DATE_WEEK",
    };

    static boolean usableDate(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getHeight() <= 0
                || !v.isAttachedToWindow()) return false;
        // Not inside a face that is faded out: oriental C keeps its big face's date_and_time
        // VISIBLE under an alpha-0 clock, and that was the date every other lookup hung from.
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            View pv = (View) p;
            if (pv == Main.sContainer) break;
            if (pv.getAlpha() <= 0.01f) return false;
        }
        return v.getAlpha() > 0.01f;
    }

    /**
     * The date line, on whatever style is loaded.
     *
     * This used to look for `text_area` and nothing else, which is all_in_one's id and
     * classic's. Every other style keeps its date under an id of its own - the rhombus style
     * has `current_date` and `notification_date`, the magazine ones `current_date`, the
     * oriental ones `tv_data` and `date_and_time` - so the lookup returned null, and
     * placeCollapsedClock() refuses to place anything at all without a date to anchor to.
     * That is why the collapse did nothing on those styles even before the driver question.
     *
     * Sticky, because placeCollapsedClock() compares this against the view it placed against
     * last and clears its caches when it changes: a date that alternated between two
     * candidates on consecutive frames would re-adopt the offset every frame.
     */
    public static View visibleDate() {
        View last = sDateView;
        if (usableDate(last)) return last;
        for (String id : DATE_IDS) {
            for (View root : Main.clockRoots()) {
                View v = findClockView(root, id);
                if (usableDate(v)) return v;
            }
        }
        for (String part : DATE_PARTS) {
            for (View root : Main.clockRoots()) {
                View v = oemPart(root, part);
                if (usableDate(v)) return v;
            }
        }
        return null;
    }

    /**
     * What the OEM gives the signature bar under the clock - the container, then its editor.
     *
     * The container is the one the OEM itself moves (see AllInOneBase), so it is tried first; the
     * editor is only there for a build that renames the container and keeps the editor's id.
     */
    static final String[] SIG_IDS = {
            "signature_text_container", "signature_text",
    };

    /**
     * The signature bar under the clock, at most one per clock tree.
     *
     * Cover mode moves the clock out from under it, and nothing in the OEM re-derives the bar's
     * position from anything we have touched, so it is left behind. Carrying it is the same
     * problem as the date's, and it is solved the same way: measure where the OEM put it, and
     * translate it back to that distance from wherever the clock now is. Eleven layouts in this
     * build carry the ids - all_in_one's three, classic's and its signature variants - and every
     * other style has neither, so it is left alone.
     *
     * Sticky, for the reason the date is: a bar that alternated between candidates on consecutive
     * frames would be re-measured every frame.
     */
    static View[] sSigViews = new View[0];

    static View[] signatureViews() {
        View[] last = sSigViews;
        if (last.length > 0) {
            boolean ok = true;
            for (View v : last) if (!usableDate(v)) ok = false;
            if (ok) return last;
        }
        View[] roots = Main.clockRoots();
        java.util.ArrayList<View> found = new java.util.ArrayList<>(roots.length);
        for (View root : roots) {
            View v = null;
            for (String id : SIG_IDS) {
                View c = findClockView(root, id);
                if (usableDate(c)) {
                    v = c;
                    break;
                }
            }
            if (v != null && !found.contains(v)) found.add(v);
        }
        sSigViews = found.toArray(new View[0]);
        return sSigViews;
    }

    /**
     * Whether the signature bar has anything in it.
     *
     * The container exists on every style carrying the ids whether or not a signature was ever
     * set, and an empty one is invisible - so carrying it would drag an empty box around and,
     * worse, would push the lock lyrics down to make room for nothing. The bar's own text is the
     * answer, and it is read off the already-resolved views every frame rather than folded into
     * the lookup above: the user edits it from the OEM's clock editor, so it can go from empty to
     * set while the lock screen is up, and the lookup is sticky by design.
     */
    static boolean signatureShows(View v) {
        if (v instanceof TextView) return ((TextView) v).length() > 0;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && signatureShows(c)) return true;
        }
        return false;
    }

    /**
     * Whether the clock on screen is the one the anchored placement was measured on.
     *
     * `time_group` is all_in_one's own id - the style the date target, the gap and the
     * one-continuous-motion entry were all measured against. Anything else lays its own date
     * out, and is left where its author put it.
     */
    static boolean anchoredStyle() {
        for (View root : Main.clockRoots()) {
            if (Main.usable(findClockView(root, "time_group"))) return true;
        }
        return false;
    }

    /** What the OEM's own ClockViewType map says this layer holds. */
    static String oemPartsOf(View root) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME", "FULL_HOUR",
                "FULL_MINUTE", "HOUR1", "HOUR2", "MIN1", "MIN2", "CLOCK_CONTAINER"}) {
            View v = oemPart(root, part);
            if (!Main.usable(v)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part).append("=#").append(Main.idOf(v)).append(' ')
              .append(v.getWidth()).append('x').append(v.getHeight());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static boolean isAncestor(View ancestor, View child) {
        for (android.view.ViewParent p = child.getParent(); p instanceof View;
             p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }
}
