// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

/** Whether a manual page switch has a large artwork endpoint to morph through. */
public final class CoverMorphRoute {
    static final int NORMAL = 0;
    static final int COVER = 1;
    static final int LYRICS = 2;

    private CoverMorphRoute() { }

    static boolean shouldMorph(int from, int to) {
        return from != to && (from == COVER || to == COVER);
    }

    /** No entry clears the two-finger lyric dismissal: only the next two-finger tap does. */
    public static boolean lyricsAfterEntry(boolean enabled, boolean tapHidden, boolean demo) {
        return demo || enabled && !tapHidden;
    }

    public static boolean lyricsAfterToggle(boolean enabled, boolean tapHidden, boolean demo) {
        return demo || enabled && tapHidden;
    }
}
