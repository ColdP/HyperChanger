// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

final class LyricTransition {
    private float[] positions = new float[0];
    private float[] alpha = new float[0];
    boolean moving;
    float fit = 1f;

    void reset(float[] base) {
        positions = base.clone();
        alpha = new float[base.length];
        java.util.Arrays.fill(alpha, 1f);
        moving = false;
        fit = 1f;
    }

    void rebase(LyricFocus focus, float[] base, float[] scroll, float[] aim, float[] nextAim) {
        for (int index = 0; index < base.length; index++) {
            float position = focus.position(index, base);
            float delta = position - positions[index];
            scroll[index] += delta;
            aim[index] += delta;
            nextAim[index] += delta;
            positions[index] = position;
        }
    }

    static float motionWeight(int count, float groupHeight, float available) {
        float occupancy = Math.min(1f, groupHeight / Math.max(1f, available));
        return 1f + 0.12f * (Math.max(1, count) - 1) + 0.18f * occupancy;
    }

    static boolean recentSeek(long now, long jumpedAt, long window) {
        return jumpedAt != Long.MIN_VALUE && now >= jumpedAt && now - jumpedAt < window;
    }

    static float scaledTop(float top, float height, float scale) {
        return top + height * (1f - scale) / 2f;
    }

    static void shift(float delta, float[] scroll, float[] aim, float[] nextAim) {
        if (delta == 0f) return;
        for (int index = 0; index < scroll.length; index++) {
            scroll[index] += delta;
            aim[index] += delta;
            nextAim[index] += delta;
        }
    }

    static void resize(float[] oldHeights, float[] heights, float ratio, float[] scale,
                       float[] scroll, float[] aim, float[] nextAim) {
        for (int index = 0; index < scale.length; index++) {
            float oldTop = scaledTop(0f, oldHeights[index], scale[index]);
            scale[index] *= ratio;
            float delta = scaledTop(0f, heights[index], scale[index]) - oldTop;
            scroll[index] += delta;
            aim[index] += delta;
            nextAim[index] += delta;
        }
    }

    boolean update(LyricFocus focus, float[] base, int dots, float aboveAlpha,
                   float fitTarget, float dt, boolean snap) {
        if (positions.length != base.length) reset(base);
        moving = false;
        boolean changed = false;
        for (int index = 0; index < base.length; index++) {
            float targetAlpha = !focus.visible(index) ? 0f
                    : (dots >= 0 ? index < dots : index < focus.last && !focus.contains(index))
                    ? aboveAlpha : 1f;
            float targetPosition = focus.position(index, base);
            float nextAlpha = snap ? targetAlpha : ease(alpha[index], targetAlpha, dt,
                    targetAlpha < alpha[index] ? 0.22f : 0.10f, 0.003f);
            float nextPosition = targetPosition;
            changed |= nextAlpha != alpha[index] || nextPosition != positions[index];
            alpha[index] = nextAlpha;
            positions[index] = nextPosition;
            moving |= nextAlpha != targetAlpha;
        }
        float nextFit = snap ? fitTarget : ease(fit, fitTarget, dt, 0.20f, 0.0005f);
        changed |= nextFit != fit;
        fit = nextFit;
        moving |= fit != fitTarget;
        return changed;
    }

    float position(int index) {
        return positions[index];
    }

    float alpha(int index) {
        return alpha[index];
    }

    private static float ease(float value, float target, float dt, float tau, float epsilon) {
        float next = value + (target - value) * (1f - (float) Math.exp(-Math.max(0f, dt) / tau));
        return Math.abs(next - target) < epsilon ? target : next;
    }
}
