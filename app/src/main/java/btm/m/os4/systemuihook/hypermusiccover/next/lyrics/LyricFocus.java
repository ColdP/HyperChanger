// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import java.util.Arrays;
import java.util.List;

final class LyricFocus {
    /**
     * Every line being sung is lit, as many as overlap - not a setting (user, 2026-10-23: it
     * follows the lyric). Four is only the ceiling: no real lyric has more at once, and past it
     * the fitted layout would shrink the words too far.
     */
    static final int DEFAULT_LIMIT = 4;
    private final int[] selected = new int[4];
    int count;
    int first;
    int last;
    private float[] positions = new float[0];

    static int clampLimit(int value) {
        return Math.max(1, Math.min(4, value));
    }

    static int startOf(LyricLine line) {
        return line.bg == null ? line.start : Math.min(line.start, line.bg.start);
    }

    static int endOf(LyricLine line) {
        return line.bg == null ? line.end : Math.max(line.end, line.bg.end);
    }

    boolean update(List<LyricLine> lines, int position, int limit, int fallback) {
        int previousCount = count;
        int found = 0;
        boolean changed = false;
        limit = clampLimit(limit);
        for (int index = lines.size() - 1; index >= 0 && found < limit; index--) {
            LyricLine line = lines.get(index);
            if (position < startOf(line) || position >= endOf(line)) continue;
            if (selected[found] != index) changed = true;
            selected[found++] = index;
        }
        count = found;
        last = found == 0 ? fallback : selected[0];
        first = found == 0 ? fallback : selected[found - 1];
        return changed || previousCount != count;
    }

    boolean contains(int index) {
        if (count == 0) return index == last;
        for (int slot = 0; slot < count; slot++) if (selected[slot] == index) return true;
        return false;
    }

    boolean visible(int index) {
        return index <= first || index >= last || contains(index);
    }

    int distance(int index) {
        if (contains(index)) return 0;
        int nearest = Math.min(Math.abs(index - first), Math.abs(index - last));
        for (int slot = 0; slot < count; slot++) {
            nearest = Math.min(nearest, Math.abs(index - selected[slot]));
        }
        return nearest + (index < first ? 1 : 0);
    }

    void layout(float[] base, float[] heights, float gap) {
        if (positions.length != base.length) positions = new float[base.length];
        System.arraycopy(base, 0, positions, 0, base.length);
        if (count < 2) return;
        float cursor = base[first];
        for (int index = first; index <= last; index++) {
            positions[index] = cursor;
            if (contains(index)) cursor += heights[index] + gap;
        }
        float removed = base[last] - positions[last];
        for (int index = last + 1; index < base.length; index++) positions[index] -= removed;
    }

    float position(int index, float[] base) {
        return positions.length == base.length ? positions[index] : base[index];
    }

    float height(float[] base, float[] heights) {
        return position(last, base) - position(first, base) + heights[last];
    }

    static float fit(float height, float available) {
        return height > 0f && available > 0f ? Math.min(1f, available / height) : 1f;
    }

    void reset() {
        count = 0;
        first = last = -1;
        positions = new float[0];
        Arrays.fill(selected, -1);
    }
}
