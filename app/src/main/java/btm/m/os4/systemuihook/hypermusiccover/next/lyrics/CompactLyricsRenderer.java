// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.os.SystemClock;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverCardLayer;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverMorphMotion;
import java.util.Collections;
import java.util.List;

/** Two-line cover presentation of the same lyric track, using the full page's motion engine. */
public final class CompactLyricsRenderer {
    private static boolean enabled, keepOn;
    private static float scale = 1f, offsetDp, spacingDp = 8f;
    private static LyricAppearance appearance = LyricAppearance.DEFAULT;
    private static final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final LyricFocus focus = new LyricFocus();
    private static final LyricTransition transition = new LyricTransition();
    private static List<LyricLine> lines = Collections.emptyList();
    private static int version = -1, current = -1, next = -1;
    private static float[] base = {}, height = {}, scroll = {}, aim = {}, nextAim = {}, velocity = {}, blur = {}, light = {}, emphasis = {}, textScale = {};
    private static long[] aimAt = {};
    private static long lastFrame;
    private static List<LyricLine> sourceLines;
    private static volatile StaticLayout[] layouts = {};
    private static float[][] charX = {};
    private static LyricGlowMask[] glowMasks = {};
    private static boolean[] glowPending = {};
    private static final LyricWordRenderer wordRenderer = new LyricWordRenderer();
    private static final LyricWordRenderer.Colors wordColors = new LyricWordRenderer.Colors() {
        @Override public long ink(float alpha, float emphasis, boolean backing) {
            return wordInk(alpha, emphasis);
        }
        @Override public long white(float alpha, float gain) { return wordInk(alpha, 1f); }
        @Override public float glowGain(float glow) { return 1f; }
    };
    private static int layoutWidth;
    private static float layoutSize;
    private static float rowHeight, springK, springC;
    private static boolean moving;
    private static LyricPalette.Colors palette;
    private static int requestedSeed = Integer.MIN_VALUE, paletteRequest;
    private static long wakeAt;
    private static final Runnable lyricTick = () -> { wakeAt = 0L; CoverCardLayer.refresh(); };

    private static Snapshot morphRows;
    private static final CoverMorphMotion pageMotion = new CoverMorphMotion();
    private static long morphFrame, morphStarted;
    private static float morphCoverBottom;

    private CompactLyricsRenderer() { }

    public static void beginPageMorph(boolean toFull) {
        if (!enabled || !CoverCardLayer.compactPageMorphAllowed()) return;
        if (!pageMorphActive()) {
            RectF cover = CoverCardLayer.compactLyricBounds();
            if (cover == null || Main.sContainer == null) return;
            float density = Main.sContainer.getResources().getDisplayMetrics().density;
            morphRows = snapshot(cover, Main.sContainer.getWidth(), density, toFull);
            morphCoverBottom = cover.bottom;
            if (morphRows == null) return;
            pageMotion.value = toFull ? 0f : 1f;
            pageMotion.velocity = 0f;
        }
        updatePalette();
        pageMotion.aim(toFull);
        morphStarted = SystemClock.uptimeMillis();
        morphFrame = 0L;
    }

    public static boolean pageMorphActive() {
        if (morphRows != null && (SystemClock.uptimeMillis() - morphStarted > 2400L
                || !enabled || !CoverCardLayer.compactPageMorphAllowed() || LockLyrics.lockScreenGone())) {
            cancelPageMorph();
        }
        return morphRows != null;
    }

    public static void cancelPageMorph() { morphRows = null; }

    static Snapshot morphRows() { return pageMorphActive() ? morphRows : null; }
    static float pageProgress() { return Math.max(0f, Math.min(1f, pageMotion.value)); }

    static boolean stepPageMorph() {
        if (!pageMorphActive()) return false;
        if (pageMotion.target == 0f) {
            RectF cover = CoverCardLayer.compactLyricBounds();
            if (cover != null) {
                float dy = cover.bottom - morphCoverBottom;
                for (int i = 0; i < morphRows.top.length; i++) morphRows.top[i] += dy;
                morphRows.x = cover.centerX();
                morphCoverBottom = cover.bottom;
            }
        }
        long now = SystemClock.uptimeMillis();
        float dt = morphFrame == 0L ? 0f : Math.min(.05f, (now - morphFrame) / 1000f);
        morphFrame = now;
        pageMotion.step(dt, Main.sClockResponse);
        if (pageMotion.atRest() && (pageMotion.target == 1f || CoverCardLayer.compactMorphArtworkReady())) {
            if (pageMotion.target == 0f) { current = next = -1; lastFrame = 0L; }
            cancelPageMorph();
            CoverCardLayer.refresh();
        }
        return true;
    }

    public static void configure(boolean on, float textScale, float offset,
                                 float spacing, boolean keep, String colors) {
        enabled = on;
        keepOn = keep;
        scale = Float.isFinite(textScale) ? Math.max(.65f, Math.min(1.5f, textScale)) : 1f;
        offsetDp = Float.isFinite(offset) ? Math.max(-80f, Math.min(80f, offset)) : 0f;
        spacingDp = Float.isFinite(spacing) ? Math.max(0f, Math.min(32f, spacing)) : 8f;
        appearance = LyricAppearance.parse(colors);
        version = -1;
        cancelTick();
        cancelPageMorph();
        if (!on) {
            current = next = -1;
            moving = false;
        }
    }

    public static boolean enabled() { return enabled; }

    public static boolean wantsKeepScreenOn() {
        return enabled && keepOn && !LockLyrics.lines().isEmpty() && LockLyrics.playing();
    }

    /** One shared measurement for the resting card, artwork flight and lyric morph. */
    public static float reservedHeight(float width, float density) {
        if (!enabled) return 0f;
        prepare(width, density);
        if (lines.isEmpty()) return 0f;
        int active = indexAt(LockLyrics.positionMs());
        return (18f + Math.max(0f, offsetDp)) * density + pairHeight(active, density);
    }

    private static int indexAt(int position) {
        int active = 0;
        for (int i = 0; i < lines.size() && lines.get(i).start <= position; i++) active = i;
        return active;
    }

    private static float pairHeight(int active, float density) {
        return height[active] + (active + 1 < height.length ? spacingDp * density + height[active + 1] : 0f);
    }

    private static boolean prepare(float width, float density) {
        List<LyricLine> source = LockLyrics.lines();
        int usable = Math.max(1, Math.round(width - 32f * density));
        float size = LockLyrics.sStyle.sizeSp * .65f * scale * density;
        if (version == LockLyrics.version() && sourceLines == source && layoutWidth == usable && layoutSize == size) return false;
        sourceLines = source;
        // Background timing must not select a different main row in the compact presentation.
        lines = new java.util.ArrayList<>();
        for (LyricLine row : source) lines.add(new LyricLine(row.text, null, row.start, row.end,
                false, row.sylStart, row.sylEnd, row.charEnd));
        version = LockLyrics.version(); layoutWidth = usable; layoutSize = size;
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.clearShadowLayer();
        int n = lines.size();
        layouts = new StaticLayout[n];
        charX = new float[n][];
        glowMasks = new LyricGlowMask[n];
        glowPending = new boolean[n];
        base = new float[n]; height = new float[n]; scroll = new float[n]; aim = new float[n];
        nextAim = new float[n]; velocity = new float[n]; aimAt = new long[n];
        blur = new float[n]; light = new float[n]; emphasis = new float[n]; textScale = new float[n];
        float cursor = 0f;
        for (int i = 0; i < n; i++) {
            String text = lines.get(i).text == null ? "" : lines.get(i).text;
            layouts[i] = StaticLayout.Builder.obtain(text, 0, text.length(), paint, usable)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build();
            if (lines.get(i).hasWords()) {
                charX[i] = new float[text.length() + 1];
                for (int ch = 0; ch < text.length(); ch++) charX[i][ch] = layouts[i].getPrimaryHorizontal(ch);
                charX[i][text.length()] = layouts[i].getLineRight(layouts[i].getLineCount() - 1);
            }
            base[i] = cursor; height[i] = layouts[i].getHeight();
            cursor += height[i] + spacingDp * density;
        }
        rowHeight = size * 1.28f + spacingDp * density;
        current = next = -1;
        focus.reset(); transition.reset(base); moving = false;
        return true;
    }

    public static void cancelTick() {
        Gen.removeCallbacks(lyricTick);
        wakeAt = 0L;
        lastFrame = 0L;
    }

    private static void wakeForBoundary(long now, int position) {
        long delay = 1000L; // Also repairs a media session that stopped publishing position updates.
        if (LockLyrics.playing()) {
            for (LyricLine line : lines) {
                if (line.start > position) delay = Math.min(delay, (long) line.start - position);
            }
        }
        long at = now + Math.max(16L, delay);
        if (wakeAt != 0L && wakeAt <= at) return;
        Gen.removeCallbacks(lyricTick);
        wakeAt = Gen.postDelayed(lyricTick, at - now) ? at : 0L;
    }

    private static void updatePalette() {
        if (appearance.mainMode != LyricAppearance.COVER) return;
        final int seed = Main.coverTint();
        if (requestedSeed == seed) return;
        requestedSeed = seed;
        final int request = ++paletteRequest;
        LyricView.preparePalette(() -> {
            LyricPalette.Colors result = LyricPalette.colors(seed);
            Gen.post(() -> {
                if (request != paletteRequest || seed != Main.coverTint()) return;
                palette = result;
                CoverCardLayer.refresh();
            });
        });
    }

    private static int color(boolean active) {
        return LyricTextColors.resolve(appearance.mainMode, appearance.mainColor,
                active ? -1 : LyricTextColors.tint(Main.coverTint()), false, active, palette);
    }

    private static long wordInk(float alpha, float emphasis) {
        int dim = color(false), lit = color(true);
        float e = Math.max(0f, Math.min(1f, emphasis));
        float r = (((dim >>> 16) & 255) + (((lit >>> 16) & 255) - ((dim >>> 16) & 255)) * e) / 255f;
        float g = (((dim >>> 8) & 255) + (((lit >>> 8) & 255) - ((dim >>> 8) & 255)) * e) / 255f;
        float b = ((dim & 255) + ((lit & 255) - (dim & 255)) * e) / 255f;
        return android.graphics.Color.pack(r, g, b,
                Math.max(0f, Math.min(1f, alpha * appearance.mainOpacity)));
    }

    private static void requestGlow(int active, float density) {
        final StaticLayout[] generation = layouts;
        for (int i = 0; i < glowMasks.length; i++) {
            if (i < active || i > active + 1) glowMasks[i] = null;
        }
        for (int i = active; i <= Math.min(active + 1, lines.size() - 1); i++) {
            if (glowMasks[i] != null || glowPending[i] || !lines.get(i).hasWords()) continue;
            LyricLine row = lines.get(i);
            boolean held = false;
            for (int k = 0; k < row.sylStart.length; k++) {
                if (row.sylEnd[k] - row.sylStart[k] >= LyricWordRenderer.GLOW_MIN_MS) { held = true; break; }
            }
            if (!held) continue;
            final int index = i;
            final StaticLayout layout = layouts[i];
            final float[] xs = charX[i];
            final TextPaint copy = new TextPaint(paint);
            glowPending[i] = true;
            LyricView.preparePalette(() -> {
                if (generation != layouts) return;
                LyricGlowMask result = LyricGlowMask.build(row, layout, xs, copy,
                        9f * density, LyricWordRenderer.GLOW_MIN_MS);
                Gen.post(() -> {
                    if (generation != layouts) { if (result != null) result.recycle(); return; }
                    glowPending[index] = false;
                    int current = indexAt(LockLyrics.positionMs());
                    if (!enabled || index < current || index > current + 1) {
                        if (result != null) result.recycle();
                        return;
                    }
                    glowMasks[index] = result;
                    CoverCardLayer.refresh();
                });
            });
        }
    }

    private static void drawRow(Canvas canvas, StaticLayout layout, LyricLine row,
                                float[] xs, LyricGlowMask glow, float x, float top, float zoom,
                                float alpha, float radius, float emphasis, boolean active, float density) {
        if (!row.hasWords() || emphasis <= 0f) {
            drawText(canvas, layout, x, top, zoom, alpha, radius, active);
            return;
        }
        int position = LockLyrics.positionMs();
        float k = radius <= 0f ? 0f : Math.max(0f, Math.min(1f, radius / Math.max(.01f, .9f * density)));
        if (k > 0f) drawText(canvas, layout, x, top, zoom,
                alpha * (.4f + .6f * emphasis) * k, radius, active);
        int save = canvas.save();
        canvas.translate(x, top); canvas.scale(zoom, zoom); canvas.translate(-layout.getWidth() / 2f, 0f);
        paint.setMaskFilter(null); paint.setShader(null); paint.setAlpha(255);
        wordRenderer.draw(canvas, row, layout, paint, xs, emphasis,
                alpha * (1f - k) * (.4f + .6f * emphasis), alpha * (1f - k) * .4f,
                1.8f * density, true, 0, position, glow, wordColors);
        canvas.restoreToCount(save);
    }

    static void drawSnapshotRow(Canvas canvas, Snapshot source, int slot, float x, float top,
                                float zoom, float alpha, float radius) {
        drawRow(canvas, source.text[slot], source.rows[slot], source.xs[slot], source.glow[slot],
                x, top, zoom, alpha, radius, source.emph[slot], slot == 0, source.density);
    }

    static void drawText(Canvas canvas, StaticLayout layout, float centerX, float top,
                         float zoom, float alpha, float radius, boolean active) {
        if (alpha <= 0f) return;
        paint.setShader(null);
        paint.setColor(color(active));
        paint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha * appearance.mainOpacity))));
        paint.setMaskFilter(Float.isFinite(radius) && radius > 0f
                ? new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) : null);
        int save = canvas.save();
        canvas.translate(centerX, top);
        canvas.scale(zoom, zoom);
        canvas.translate(-layout.getWidth() / 2f, 0f);
        layout.draw(canvas);
        canvas.restoreToCount(save);
        paint.setMaskFilter(null);
    }

    /** Screen-space rows consumed by the full page during a page morph. */
    static final class Snapshot {
        final int first;
        final StaticLayout[] text;
        final String[] words;
        float x;
        final float size, density;
        final LyricLine[] rows;
        final float[][] xs;
        final LyricGlowMask[] glow;
        final float[] top, zoom, alpha, depth, emph;
        Snapshot(int first, StaticLayout[] text, String[] words, float x, float size,
                 float[] top, float[] zoom, float[] alpha, float[] depth, LyricLine[] rows,
                 float[][] xs, LyricGlowMask[] glow, float[] emph, float density) {
            this.rows = rows; this.xs = xs; this.glow = glow; this.emph = emph; this.density = density;
            this.zoom = zoom; this.alpha = alpha; this.depth = depth;
            this.first = first; this.text = text; this.words = words; this.x = x; this.size = size; this.top = top;
        }
    }

    static Snapshot snapshot(RectF cover, float width, float density) {
        return snapshot(cover, width, density, true);
    }

    private static Snapshot snapshot(RectF cover, float width, float density, boolean useLive) {
        prepare(width, density);
        if (!enabled || lines.isEmpty()) return null;
        int active = indexAt(LockLyrics.positionMs());
        int count = Math.min(2, lines.size() - active);
        StaticLayout[] text = new StaticLayout[count];
        String[] words = new String[count];
        float[] top = new float[count], zoom = new float[count], alpha = new float[count], depth = new float[count], emph = new float[count];
        LyricLine[] rows = new LyricLine[count];
        float[][] xs = new float[count][];
        LyricGlowMask[] glow = new LyricGlowMask[count];
        requestGlow(active, density);
        float y = cover.bottom + (18f + offsetDp) * density;
        for (int slot = 0; slot < count; slot++) {
            int i = active + slot;
            text[slot] = layouts[i]; words[slot] = lines.get(i).text;
            boolean live = useLive && current == active;
            rows[slot] = lines.get(i); xs[slot] = charX[i]; glow[slot] = glowMasks[i];
            emph[slot] = live ? emphasis[i] : slot == 0 ? 1f : 0f;
            zoom[slot] = live ? textScale[i] : slot == 0 ? 1f : .97f;
            boolean wordsLive = rows[slot].hasWords() && emph[slot] > 0f;
            alpha[slot] = live ? transition.alpha(i) * (wordsLive ? 1f : .4f + .6f * light[i])
                    : slot == 0 ? 1f : .4f;
            depth[slot] = live ? blur[i] : slot == 0 ? 0f : .9f * density;
            float rowY = live ? cover.bottom + (18f + offsetDp) * density + transition.position(i) - scroll[i] : y;
            top[slot] = LyricTransition.scaledTop(rowY, height[i], zoom[slot]);
            y += height[i] + spacingDp * density;
        }
        return new Snapshot(active, text, words, cover.centerX(), layoutSize, top, zoom, alpha, depth, rows, xs, glow, emph, density);
    }

    public static void draw(Canvas canvas, RectF cover, float opacity, float density) {
        if (!enabled || opacity <= 0f) return;
        boolean rebuild = prepare(canvas.getWidth(), density);
        if (lines.isEmpty()) { moving = false; return; }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0L ? 0f : Math.min(.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        int position = LockLyrics.positionMs();
        float step = rowHeight;
        int fallback = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).start <= position) fallback = i;
            else break;
        }
        int previousCount = focus.count;
        focus.update(lines, position, 1, fallback);
        int active = focus.last;
        int upcoming = active + 1 < lines.size() ? active + 1 : -1;
        LyricLine lead = lines.get(active);
        boolean changed = active != current || upcoming != next;
        if (changed) {
            boolean first = current < 0;
            current = active;
            next = upcoming;
            focus.layout(base, height, spacingDp * density);
            float to = focus.position(active, base);
            if (first) {
                for (int i = 0; i < lines.size(); i++) {
                    scroll[i] = aim[i] = nextAim[i] = to;
                    velocity[i] = 0f;
                    aimAt[i] = now;
                }
            } else {
                springK = LyricMotion.stiffness(active == 0,
                        active == 0 ? 0 : lead.start - lines.get(active - 1).start);
                float weight = LyricTransition.motionWeight(Math.max(previousCount, focus.count),
                        focus.height(base, height), step * 2f);
                springK /= weight;
                springC = (float) Math.sqrt(springK) * LyricMotion.DAMPING_MULT;
                LyricMotion.ripple(focus, base, height, aimAt, nextAim, now, to,
                        0f, 0f, active, previousCount);
            }
            if (first) {
                for (int i = 0; i < lines.size(); i++) {
                    light[i] = emphasis[i] = i == active ? 1f : 0f;
                    textScale[i] = i == active ? 1f : .97f;
                    blur[i] = LyricMotion.depthBlur(focus.distance(i), density);
                }
            }
            CoverCardLayer.refresh();
        }
        wakeForBoundary(now, position);
        requestGlow(active, density);
        updatePalette();
        float x = cover.centerX();
        float top = cover.bottom + (18f + offsetDp) * density;
        moving = false;
        transition.update(focus, base, -1, .6f, 1f, dt, rebuild);
        moving = transition.moving;
        // Use the page's persistent per-line positions, ripple delays, spring integration and depth.
        int save = canvas.save();
        canvas.clipRect(0f, top - density * 2f, canvas.getWidth(), top + pairHeight(active, density) + density * 2f);
        for (int i = Math.max(0, active - 2); i <= Math.min(lines.size() - 1, active + 2); i++) {
            moving |= LyricMotion.step(i, scroll, aim, nextAim, velocity, aimAt, now, dt,
                    false, springK, springC, step * 2f * LyricMotion.SEEK_SPEED_BANDS) != 0;
            moving |= aim[i] != nextAim[i] || velocity[i] != 0f;
            float targetBlur = LyricMotion.depthBlur(focus.distance(i), density);
            moving |= LyricMotion.appearance(i, emphasis, light, textScale, blur, i == active,
                    false, position, lines.get(i).start, now >= aimAt[i], dt, focus.count,
                    height[i], step * 2f, targetBlur) != 0;
            float y = top + transition.position(i) - scroll[i];
            float scaledY = LyricTransition.scaledTop(y, height[i], textScale[i]);
            LyricLine row = lines.get(i);
            boolean words = row.hasWords() && emphasis[i] > 0f;
            drawRow(canvas, layouts[i], row, charX[i], glowMasks[i], x, scaledY, textScale[i],
                    opacity * transition.alpha(i) * (words ? 1f : .4f + .6f * light[i]),
                    blur[i], emphasis[i], i == active, density);
            moving |= LockLyrics.playing() && words && position >= row.start
                    && position < row.end + Math.max(LyricWordRenderer.LIFT_MIN_MS, LyricWordRenderer.GLOW_TAIL_MS);
        }
        canvas.restoreToCount(save);
    }

    public static boolean animating() { return enabled && moving; }
}
