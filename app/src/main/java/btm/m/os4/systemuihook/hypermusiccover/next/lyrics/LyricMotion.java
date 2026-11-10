// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

/** The full lyric page's normal line handoff, shared with the compact cover treatment. */
final class LyricMotion {
    private LyricMotion() { }
    static final float DAMPING_MULT = 2.2f;
    static final float SEEK_SPEED_BANDS = 8f;

    static float stiffness(boolean slow, int interval) {
        if (slow) return 90f;
        int iv = Math.max(100, Math.min(800, interval));
        float ratio = 1f - (iv - 100) / (float) (800 - 100);
        ratio = (float) Math.pow(ratio, 0.2);
        return 170f + ratio * (220f - 170f);
    }

    static void ripple(LyricFocus singing, float[] base, float[] height, long[] aimAt,
                       float[] nextAim, long now, float to, float anchor, float bandTop,
                       int focus, int previousCount) {
        float delay = 0f, step = 50f;
        for (int i = 0; i < base.length; i++) {
            aimAt[i] = now + Math.round(singing.count > 1 || previousCount > 1 ? 0f : delay);
            nextAim[i] = to;
            float y = anchor + singing.position(i, base) - to;
            if (y + height[i] >= bandTop) {
                delay += step;
                if (i >= focus) step *= 1f / 1.05f;
            }
        }
    }

    /** Returns the original LyricView motion diagnostic bits (16: integrated, 32: snapped). */
    static int step(int i, float[] scroll, float[] aim, float[] nextAim, float[] vel,
                    long[] aimAt, long now, float dt, boolean still,
                    float springK, float springC, float speedCap) {
        boolean started = still || now >= aimAt[i];
        if (aim[i] != nextAim[i] && started) aim[i] = nextAim[i];
        float x = scroll[i] - aim[i];
        if (still) {
            if (aimAt[i] > now) aimAt[i] = now;
            if (x != 0f || vel[i] != 0f) {
                scroll[i] = aim[i];
                vel[i] = 0f;
                return 32;
            }
        } else if (dt > 0f && (Math.abs(x) > 0.3f || Math.abs(vel[i]) > 2f)) {
            float left = dt;
            while (left > 0f) {
                float h = Math.min(left, 1f / 240f);
                vel[i] += (-springK * x - springC * vel[i]) * h;
                if (vel[i] > speedCap) vel[i] = speedCap;
                else if (vel[i] < -speedCap) vel[i] = -speedCap;
                x += vel[i] * h;
                left -= h;
            }
            scroll[i] = aim[i] + x;
            return 16;
        } else if (x != 0f) {
            scroll[i] = aim[i];
            vel[i] = 0f;
            return 32;
        }
        return 0;
    }

    static float approach(float v, float to, float dt, float tau) {
        if (dt <= 0f) return v;
        return v + (to - v) * (1f - (float) Math.exp(-dt / tau));
    }

    /** Original emphasis, line lighting, scale and depth-blur state updates from LyricView. */
    static int appearance(int i, float[] emph, float[] lit, float[] scale, float[] blur,
                          boolean focused, boolean still, int ms, int start, boolean started,
                          float dt, int count, float height, float band, float blurTarget) {
        int why = 0;
        float eTo = focused ? 1f : 0f;
        float e = approach(emph[i], eTo, dt, eTo > emph[i] ? .08f : .016f);
        if (Math.abs(e - eTo) < .003f) e = eTo;
        if (e != emph[i]) { emph[i] = e; why |= 64; }
        float lTo = focused && (still || ms >= start) ? 1f : 0f;
        float lv = approach(lit[i], lTo, dt, lTo > lit[i] ? .08f : .016f);
        if (Math.abs(lv - lTo) < .003f) lv = lTo;
        if (lv != lit[i]) { lit[i] = lv; why |= 64; }
        float scTo = focused ? 1f : .97f;
        float scaleTau = .15f * LyricTransition.motionWeight(count, height, Math.max(1f, band));
        float sc = started ? approach(scale[i], scTo, dt, scaleTau) : scale[i];
        if (Math.abs(sc - scTo) < .0005f) sc = scTo;
        if (sc != scale[i]) { scale[i] = sc; why |= 64; }
        float b = approach(blur[i], blurTarget, dt, blurTarget > blur[i] ? .05f : .07f);
        if (Math.abs(b - blurTarget) < .05f) b = blurTarget;
        if (b != blur[i]) { blur[i] = b; why |= 128; }
        return why;
    }

    static float depthBlur(int rows, float density) {
        if (rows <= 0) return 0f;
        return (0.9f + 1.6f * (Math.min(4, rows) - 1)) * density;
    }
}
