// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.Intent;

/**
 * 保留壁纸's veil: the user's own wallpaper, a little darker while the lyric page is up.
 *
 * Written into the wallpaper itself, not painted over it.
 *
 * A View at the top of keyguard_background_layer was the first version, and it was wrong in a
 * way that took a report to see (issue #96): the clock's glass and the media card's blur both
 * sample the wallpaper WINDOW, not SystemUI's tree, so a black View above the wallpaper in that
 * layer darkens what the user sees but is never in what the glass refracts. On the lyric page
 * the wallpaper was dimmed and the card stayed at full brightness over it - the brighter-looking
 * card, against every other surface on the screen. In 全屏封面 the same card follows the cover
 * because the cover IS the wallpaper texture; in 保留壁纸 the texture is the user's own picture
 * and the card samples it undimmed.
 *
 * So the dim goes where the sampling happens: the wallpaper process's fragment shader multiplies
 * the texture by 1 - value (WallpaperProbe's `uKeepDim`). The pixels on screen, the clock's glass
 * and the media card's material are then all the same darkened texture, by construction.
 *
 * No animator of its own. It rides the clock's progress - ClockCollapse hands the media card one
 * number per frame and this takes the same one - so the veil, the clock and the card arrive
 * together, as everything else in the cover's transition does. Values are quantised: a transition
 * costs a handful of crosses to the wallpaper process, not one per frame, and the wallpaper
 * process fills in between them - its own frame pump, its own 100ms chase - so the dim is
 * continuous even when a step is late. A still value costs nothing because a still wallpaper
 * is not drawing anyway.
 */
final class KeepDim {
    private KeepDim() {
    }

    /** How dark at the lyric page: a light veil, enough to lift white words off a pale wallpaper. */
    static final float MAX_ALPHA = 0.25f;

    /**
     * How far the value has to move before another target is worth sending.
     *
     * The wallpaper process does the fading: each target is reached over {@link #FADE_MS}
     * with its own frame pump, so these steps only have to keep up with the clock's spring,
     * not draw it. Every step is still a broadcast, and this runs inside the transition.
     */
    private static final float STEP = 0.05f;

    /** How long the wallpaper process takes to reach each target. */
    private static final int FADE_MS = 100;

    /** What the clock's progress asks for, before quantisation. */
    private static float sWant;
    /** What the wallpaper process was last told; NaN before the first send. */
    private static float sSent = Float.NaN;

    /** Main thread. p is the cover look's progress, 0 the OEM's lock screen and 1 the lyric page. */
    static void apply(float p) {
        float t = Math.max(0f, Math.min(1f, p));
        // Snap the ends: a spring's last frame lands a hair short of 1 (0.958 measured), and
        // a target of 0.239 instead of 0.25 leaves the wallpaper a little brighter than the
        // look's own number - every time, at rest, until the next transition.
        float want = t >= 0.95f ? MAX_ALPHA : t <= 0.05f ? 0f : t * MAX_ALPHA;
        sWant = want;
        if (!Float.isNaN(sSent) && want == sSent) return;
        // Zero always goes out: a quantised step must not leave a few percent of dim behind
        // when the lyric page leaves.
        if (want != 0f && !Float.isNaN(sSent) && Math.abs(want - sSent) < STEP) return;
        send(want);
    }

    /**
     * The wallpaper process starts with no value. If it started - or restarted - while the
     * lyric page was already up, this is how the dim comes back. Called from its `wphello`.
     */
    static void resend() {
        if (sWant > 0f) send(sWant);
    }

    /**
     * The screen came back. The doze had the dim released, so if the lyric page is still
     * the lock screen's it has to be asked for again - the clock's own re-entry usually
     * does it, but a clock that was already held draws no entry frames to ride.
     */
    static void resume() {
        if (Main.keepsWallpaper() && Main.coverHoldsClock()) {
            apply(MediaCard.cardProgress());
        }
    }

    private static void send(float v) {
        if (Main.sAppCtx == null) return;
        sSent = v;
        Intent out = CoverPush.wallpaperIntent("keepdim");
        out.putExtra("v", v);
        out.putExtra("ms", FADE_MS);
        // No log here on purpose: this runs inside the clock's transition, and on a phone
        // with 详细 on an Xp.d is an LSPosed-bridge write - one per step is exactly the
        // kind of thing that shows up as jank.
        ProbeGuard.send(Main.sAppCtx, out);
    }
}
