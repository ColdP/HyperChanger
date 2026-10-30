// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
/**
 * User-adjustable values for the module's own lyric view. No OEM classes are involved.
 *
 * The band the lyrics are centred in is set the way the square card is: a height as a share of
 * the room between the clock and the media card, and a place in what that leaves. It was an
 * offset and a gap in dp, and both only ever shrank the band - the offset trimmed the opposite
 * edge by twice itself, so "moving" the lyrics also cut lines off - and both were clamped away
 * silently once the room ran out. The gap is a fixed GAP_DP now.
 */
public final class LyricStyle {
    /** Least room kept to the clock and the media card. */
    static final float GAP_DP = 16f;
    static final float MIN_FILL = 0.4f;
    /**
     * The band the lyrics are always drawn in.
     *
     * Every one of these was a slider. The two sizes are again (the 歌词外观 page, 2026-10-22),
     * saved and read back; the other four have no row and loadState does not read them. They
     * are the values this shipped with, except the weight, which comes down from 600 to 500 -
     * the one that was worth changing on the way past. The backing vocal's 17sp is the size it
     * always had beside the 25sp lead. `op lyricstyle` still moves all six for the session.
     */
    static final LyricStyle DEFAULT = new LyricStyle(1f, 0.5f, 30f, 25f, 17f, 500);

    /** The band's height as a share of the room, MIN_FILL..1; never under MIN_ROWS rows. */
    public final float fill;
    /** Where the band sits in the height it leaves over: 0 top, 0.5 centre, 1 bottom. */
    public final float pos;
    public final float sideDp;
    public final float sizeSp;
    /** Independent backing-vocal size, matching the official 17 sp default. */
    public final float bgSizeSp;
    public final int weight;

    /** Rows of the main size the band may not shrink under. LyricView holds the same number. */
    private static final float MIN_ROWS = 2.4f;

    private LyricStyle(float fill, float pos, float sideDp, float sizeSp, float bgSizeSp, int weight) {
        this.fill = fill;
        this.pos = pos;
        this.sideDp = sideDp;
        this.sizeSp = sizeSp;
        this.bgSizeSp = bgSizeSp;
        this.weight = weight;
    }

    /** Non-finite input changes nothing; finite input is kept inside the UI's supported range. */
    LyricStyle with(String key, float value) {
        if (!Float.isFinite(value)) return this;
        float f = fill, p = pos, side = sideDp, size = sizeSp, bgSize = bgSizeSp;
        int fontWeight = weight;
        if ("fill".equals(key)) f = clamp(value, MIN_FILL, 1f);
        else if ("pos".equals(key)) p = clamp(value, 0f, 1f);
        else if ("side".equals(key)) side = clamp(value, 0f, 64f);
        else if ("size".equals(key)) size = clamp(value, 18f, 36f);
        else if ("bgsize".equals(key)) bgSize = clamp(value, 10f, 30f);
        else if ("weight".equals(key)) {
            // MiSans VF, the lock screen's font, has a weight axis that ends at 700.
            fontWeight = Math.max(300, Math.min(700, Math.round(value / 100f) * 100));
        } else return this;
        if (f == fill && p == pos && side == sideDp
                && size == sizeSp && bgSize == bgSizeSp && fontWeight == weight) return this;
        return new LyricStyle(f, p, side, size, bgSize, fontWeight);
    }

    boolean sameLayout(LyricStyle other) {
        return other != null && sideDp == other.sideDp && sizeSp == other.sizeSp
                && bgSizeSp == other.bgSizeSp && weight == other.weight;
    }

    /** Safe screen-space clipping band, written into a reusable two-float buffer. */
    boolean writeBand(float clockBottom, float cardTop, float density, float textPx, float[] out) {
        if (!Float.isFinite(clockBottom) || !Float.isFinite(cardTop)
                || !Float.isFinite(density) || !Float.isFinite(textPx)
                || density <= 0f || textPx <= 0f) return false;
        float minimum = MIN_ROWS * textPx;
        float room = cardTop - clockBottom;
        if (room < minimum) return false;
        float gap = Math.min(GAP_DP * density, (room - minimum) / 2f);
        float top = clockBottom + gap;
        float spare = cardTop - gap - top;
        float height = Math.max(minimum, spare * fill);
        top += (spare - height) * pos;
        float bottom = top + height;
        out[0] = top;
        out[1] = bottom;
        return true;
    }

    /**
     * The left edge of the text column; the column is the view's width less twice this.
     *
     * The column is as wide as the screen's short side and centred, with sideDp inside it. On a
     * phone upright that is the whole width and nothing changes; on a tablet held sideways the
     * lines used to run from the left edge across the whole screen.
     */
    float sidePx(int viewWidth, int shortSidePx, float density, float textPx) {
        if (viewWidth <= 0 || density <= 0f) return 0f;
        float column = shortSidePx > 0 ? Math.min(viewWidth, shortSidePx) : viewWidth;
        float minTextWidth = Math.min(column, Math.max(120f * density, 4f * textPx));
        float margin = Math.min(sideDp * density, Math.max(0f, (column - minTextWidth) / 2f));
        return (viewWidth - column) / 2f + margin;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
