// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;

/** Shared official cover tint and role/palette selection for both lyric presentations. */
final class LyricTextColors {
    private LyricTextColors() { }

    static int tint(int src) {
        if (src == 0) return -1;
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(src, hsv);
        hsv[1] = Math.min(0.28f, hsv[1] * 1.5f);
        hsv[2] = 1f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    static int resolve(int mode, int custom, int official, boolean backing,
                       boolean active, LyricPalette.Colors palette) {
        if (mode == LyricAppearance.CUSTOM) return custom;
        if (mode == LyricAppearance.COVER && palette != null) {
            return backing ? (active ? palette.bgLit : palette.bgDim)
                    : (active ? palette.mainLit : palette.mainDim);
        }
        return official;
    }
}
