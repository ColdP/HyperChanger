// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

import org.json.JSONObject;

/** Persisted drawing preferences; defaults reproduce upstream colors and opacity. */
public final class LyricAppearance {
    public static final int OFFICIAL = 0, COVER = 1, CUSTOM = 2;
    public static final LyricAppearance DEFAULT = new LyricAppearance(0, 0, -1, -1, 1f, 0.72f);
    public final int mainMode, bgMode, mainColor, bgColor;
    public final float mainOpacity, bgOpacity;

    public LyricAppearance(int mainMode, int bgMode, int mainColor, int bgColor,
                           float mainOpacity, float bgOpacity) {
        this.mainMode = mode(mainMode);
        this.bgMode = mode(bgMode);
        this.mainColor = mainColor | 0xff000000;
        this.bgColor = bgColor | 0xff000000;
        this.mainOpacity = opacity(mainOpacity, 1f);
        this.bgOpacity = opacity(bgOpacity, 0.72f);
    }

    private static int mode(int value) { return value >= OFFICIAL && value <= CUSTOM ? value : OFFICIAL; }
    private static float opacity(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : fallback;
    }

    public static LyricAppearance parse(String text) {
        if (text == null || text.isEmpty()) return DEFAULT;
        try {
            JSONObject json = new JSONObject(text);
            return new LyricAppearance(json.optInt("mainMode", 0), json.optInt("bgMode", 0),
                    json.optInt("mainColor", -1), json.optInt("bgColor", -1),
                    (float) json.optDouble("mainOpacity", 1d),
                    (float) json.optDouble("bgOpacity", 0.72d));
        } catch (Exception ignored) { return DEFAULT; }
    }

    public String toJson() {
        try {
            return new JSONObject().put("mainMode", mainMode).put("bgMode", bgMode)
                    .put("mainColor", mainColor).put("bgColor", bgColor)
                    .put("mainOpacity", (double) mainOpacity).put("bgOpacity", (double) bgOpacity).toString();
        } catch (Exception ignored) { return "{}"; }
    }
}
