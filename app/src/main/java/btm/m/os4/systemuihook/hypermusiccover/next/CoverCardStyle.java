// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

/**
 * Values shared by the settings page, preview and the SystemUI-drawn cover.
 *
 * Two settings, both shares of the room between the clock and the media card, so every step of
 * either slider moves the square. They were three in dp - size, margin and offset - and they
 * overlapped: past the room the size slider did nothing (420dp never fits), the margin mostly
 * acted as a second size cap, and the offset was clamped away whenever the square filled the
 * room. The margin is a fixed GAP_DP now.
 */
final class CoverCardStyle {
    static final int FULL = 0;
    static final int CARD = 1;
    /**
     * 保留壁纸: cover mode with the user's own wallpaper behind it - nothing is pushed. The clock
     * goes small, the cut-out goes and the wallpaper dims only while the lyric page is up; off it
     * the lock screen is the OEM's, big clock and all. What #84's bug looked like, asked for.
     */
    static final int KEEP = 2;
    /** Least room kept to the clock, the media card and the screen edges. */
    static final float GAP_DP = 16f;
    static final float MIN_FILL = 0.4f;
    /**
     * The square's size and place, both fixed now.
     *
     * The app has no sliders for either and the state file is not read for either. They were the
     * defaults while they were settings, and they are the one pair that reads as a look rather
     * than as two numbers: a square that fills its room has no room left to move in, so "as big as
     * fits, centred" is one arrangement. `op coverstyle` still moves both for the rest of the
     * session.
     */
    static final float FIXED_FILL = 1f;
    static final float FIXED_POS = 0.5f;
    /**
     * What the corners fall back to before the media card's own radius has been measured: about
     * the 20dp a 300dp square was drawn with, which is what they were before they were the media
     * card's at all. The app's preview falls back to the same share. See [radius].
     */
    static final float FALLBACK_CORNER = 0.12f;

    /**
     * The media card's own corner radius in pixels, pushed in by Main when it measures the card.
     *
     * Pushed rather than asked for, and that is the point: this class is reached from the cover's
     * draw path and from a unit test, and Main is an Xposed entry point that a plain JVM cannot
     * even load. Nothing here needs SystemUI, so nothing here may name it.
     */
    private static volatile float sMediaCardRadiusPx;

    /** See [sMediaCardRadiusPx]. Anything that is not a positive finite number clears it. */
    static void noteMediaCardRadius(float px) {
        sMediaCardRadiusPx = Float.isFinite(px) && px > 0f ? px : 0f;
    }

    final int mode;
    /** The side as a share of the largest square that fits the room, MIN_FILL..1. */
    final float fill;
    /** Where the square sits in the height it leaves over: 0 top, 0.5 centre, 1 bottom. */
    final float pos;
    /**
     * An override for the corner radius - 0 square to 1 a circle, as a share of the half-side -
     * or NaN to take the media card's own.
     *
     * It used to be the setting, and the corners are the media notification's now: the square
     * cover and the media card are the same piece of furniture on the same screen, and the 0.12
     * was only ever an approximation of the card's radius at one particular size. Nothing writes
     * this down any more. It is reachable from `op coverstyle --es key corner` and nowhere else.
     */
    final float corner;

    CoverCardStyle(int mode, float fill, float pos, float corner) {
        this.mode = mode == CARD || mode == KEEP ? mode : FULL;
        this.fill = finite(fill, MIN_FILL, 1f, FIXED_FILL);
        this.pos = finite(pos, 0f, 1f, FIXED_POS);
        this.corner = Float.isFinite(corner) ? Math.max(0f, Math.min(1f, corner)) : Float.NaN;
    }

    static CoverCardStyle defaults() {
        return new CoverCardStyle(FULL, FIXED_FILL, FIXED_POS, Float.NaN);
    }

    CoverCardStyle with(String key, float value) {
        if ("fill".equals(key)) return new CoverCardStyle(mode, value, pos, corner);
        if ("pos".equals(key)) return new CoverCardStyle(mode, fill, value, corner);
        if ("corner".equals(key)) return new CoverCardStyle(mode, fill, pos, value);
        if ("mode".equals(key)) return new CoverCardStyle(Math.round(value), fill, pos, corner);
        return this;
    }

    /**
     * The corner radius for a card whose shorter side is this.
     *
     * The media card's own radius, in pixels, until the square would be a circle - the same
     * reading its outline is clipped with, so the two cards' corners are the same radius rather
     * than the same fraction of two different sizes. Before that has been measured, and when an
     * override is set, a share of the side instead.
     */
    float radius(float side) {
        float half = side * 0.5f;
        if (Float.isFinite(corner)) return half * corner;
        float r = sMediaCardRadiusPx;
        return r > 0f ? Math.min(r, half) : half * FALLBACK_CORNER;
    }

    /**
     * The same radius as a share of the half-side, which is what the shadow's tile is keyed on.
     *
     * Asked for at a card's RESTING side. The live one changes on every frame of the morph, and
     * the tile cache would then miss on every frame - a fresh software blur per frame, which is
     * the thing drawShadow exists to avoid. The two agree where it matters: at the landing.
     */
    float cornerShare(float side) {
        float half = side * 0.5f;
        return half > 0f ? Math.min(1f, radius(side) / half) : FALLBACK_CORNER;
    }

    /**
     * The card takes the artwork's own shape - a Bilibili video's 16:10 cover was centre-cropped
     * to a square and lost its sides - within these bounds; anything longer is cropped to them.
     */
    static final float MAX_ASPECT = 2f;

    /** Width over height for artwork of this size, held to 1/MAX_ASPECT..MAX_ASPECT. */
    static float aspect(float w, float h) {
        if (!(w > 0f && h > 0f)) return 1f;
        return Math.max(1f / MAX_ASPECT, Math.min(MAX_ASPECT, w / h));
    }

    static float finite(float v, float lo, float hi, float fallback) {
        return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
    }

    /** The square reserves its full playing size even while the paused artwork scales inward. */
    Rect place(float width, float height, float density, float clockBottom,
               float mediaTop) {
        if (!(width > 0f && height > 0f && density > 0f)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || !Float.isFinite(density)) return null;
        float gap = GAP_DP * density;
        float top = Math.max(0f, clockBottom > 0f && Float.isFinite(clockBottom)
                ? clockBottom : height * 0.18f) + gap;
        // A stale card rectangle from a previous lock session can put the art over the OEM
        // media card on wake. Wait for this session's measured boundary instead.
        if (!Float.isFinite(mediaTop) || mediaTop <= height / 3f
                || mediaTop > height) return null;
        float bottom = mediaTop - gap;
        float room = Math.min(width - 2f * gap, bottom - top);
        float least = 96f * density;
        if (room < least || !Float.isFinite(room)) return null;
        // A small share of a tight room is held at the least size rather than dropped.
        float side = Math.max(least, room * fill);
        float y = top + (bottom - top - side) * pos;
        return new Rect((width - side) * 0.5f, y, side);
    }

    static final class Rect {
        final float x, y, side;
        Rect(float x, float y, float side) {
            this.x = x;
            this.y = y;
            this.side = side;
        }
    }
}
