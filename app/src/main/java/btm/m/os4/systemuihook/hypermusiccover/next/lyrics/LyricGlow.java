// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

/** Crossfades preblurred radii instead of rasterizing a new text shadow every frame. */
final class LyricGlow {
    static final int LAYERS = 3;

    static float weight(float glow, int layer) {
        float at = Math.max(0f, Math.min(1f, glow)) * LAYERS;
        return Math.max(0f, 1f - Math.abs(at - (layer + 1)));
    }

    static boolean arm(LyricLine line, int ms, int minimum, int lead, int tail) {
        if (!line.hasWords()) return false;
        for (int k = 0; k < line.sylStart.length; k++) {
            if (line.sylEnd[k] - line.sylStart[k] >= minimum &&
                    ms >= (long) line.sylStart[k] - lead && ms < (long) line.sylEnd[k] + tail) {
                return true;
            }
        }
        return false;
    }

    private LyricGlow() {}
}
