// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.view.View;

/**
 * The card's thumbnail while the lyrics are up: how far it is scaled down and the spring that
 * takes it there and back.
 *
 * Carved out of Main as it was (2026-10-14, after CardTouch): every member moved whole with its
 * comments, references across the line prefixed with Main. or CardArt., nothing else changed.
 */
final class CardArt {
    private CardArt() {}

    /** How small the thumbnail gets at the far end of the fade. Apple's shrinks as it goes. */
    static final float CARD_ART_MIN_SCALE = 0.82f;

    /**
     * The lyrics exception as a number the card can move on, rather than a yes/no read off the
     * frame it happens to land on. 1 is "the thumbnail the setting hides is back because the
     * lyrics are up", 0 is "the card as the OEM draws it".
     *
     * Two reported bugs, one cause (2026-09-17). Read every frame with no progress of its own,
     * the answer flipped mid-transition, and both flips are visible: leaving cover mode with the
     * lyrics up put the thumbnail back for the first frames of the exit and took it away again
     * for the rest of it - "从歌词界面到正常封面切换的时候缩略图会闪一下" - and the title it
     * carries jumped between left and centre instead of sliding, which is the same number.
     *
     * Not a second animator. It is the same spring the cover itself moves on - EASE_COVER at the
     * response the user set - stepped from the card's own per-frame pass (see stepLyricArt), so
     * the thumbnail and the title cross at the speed of the transition they are part of and there
     * is still exactly one curve on this lock screen.
     */
    static volatile float sLyricArtP;

    static float sLyricArtV;

    static float sLyricArtTo = -1f;

    static long sLyricArtAt;

    /**
     * Whether the lyrics are up, remembered so the exit of cover mode does not re-ask it. The
     * lyric view fades out with the card's own progress, so during the exit the lyrics are still
     * on screen while they fade, and asking then is what made the thumbnail blink.
     */
    static boolean sLyricUp;

    /**
     * One step of the lyrics exception, and what it is now.
     *
     * Stepped, not switched: the two answers are two looks of the card, and moving between them
     * is the same transition the cover itself moves on. Same spring constants as the clock's
     * (EASE_COVER, at the response the user set for the transition), same integration, so the
     * thumbnail and the title cross at the speed of the morph they belong to. Anything faster
     * reads as the cut this replaces; anything slower stops matching the clock beside it.
     *
     * The frames come from the card's own pass - the guard already runs every frame of a
     * transition, and the lyrics being up means the lyric view is redrawing on those same
     * frames. Only the first step after a change has to be asked for (kickCardFrame): that step
     * writes nothing new, so nothing would invalidate and the spring would sit at rest one frame
     * short of moving.
     *
     * Snapped rather than sprung on the way into cover mode - see enterCoverMode - because a
     * thumbnail fading out and back in over the entry would be the same blink, moved.
     */
    static float stepLyricArt(boolean want) {
        float to = want ? 1f : 0f;
        // A capture is the app asking what the settled card looks like (see shootCard), and the
        // frame it lands on is not a thing to keep. Everywhere else the motion IS the state.
        if (Main.sCardForced) {
            sLyricArtP = sLyricArtTo = to;
            sLyricArtV = 0f;
            sLyricArtAt = 0L;
            return sLyricArtP;
        }
        if (to != sLyricArtTo) {
            // Retargeted. The spring starts on the next frame: this one has nothing new to write.
            sLyricArtTo = to;
            sLyricArtAt = 0L;
            MediaCard.kickCardFrame();
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sLyricArtAt == 0L) {
            sLyricArtAt = now;
        } else {
            // A frame that stalled must not be integrated whole, or the thumbnail jumps by
            // whatever the stall was, in one step, on the very transition this is smoothing.
            float dt = Math.min(0.05f, (now - sLyricArtAt) / 1000f);
            sLyricArtAt = now;
            if (dt > 0f) {
                final float zeta = Main.EASE_COVER[0];
                final float w0 = (float) (2 * Math.PI / Main.sClockResponse);
                final float k = w0 * w0, damp = 2f * zeta * w0;
                // Sub-stepped: a spring integrated at 60Hz with a response this short is not
                // stable, and the clock's own loop substeps for the same reason.
                int steps = Math.max(1, (int) Math.ceil(dt * 240f));
                float h = dt / steps;
                for (int i = 0; i < steps; i++) {
                    float a = -k * (sLyricArtP - to) - damp * sLyricArtV;
                    sLyricArtV += a * h;
                    sLyricArtP += sLyricArtV * h;
                }
                if (sLyricArtP < 0f) sLyricArtP = 0f;
                if (sLyricArtP > 1f) sLyricArtP = 1f;
                // Both ends are exact: hideP is p at 0 and 0 at 1, which are the two states the
                // card has always had, and an alpha left at 0.998 of one of them is not.
                if (Math.abs(sLyricArtP - to) < 0.002f && Math.abs(sLyricArtV) < 0.02f) {
                    sLyricArtP = to;
                    sLyricArtV = 0f;
                }
            }
        }
        return sLyricArtP;
    }

    /**
     * Shrinks the card's artwork to `factor` of whatever scale the OEM is giving it.
     *
     * Anything on the view that we did not put there is the OEM's, and remembering what we
     * last wrote is what tells the two apart. So the baseline is re-read on the first frame,
     * on a new card view, and on any frame the OEM's own animation has written over ours -
     * it follows the OEM live rather than latching one frame of it - and factor 1 writes that
     * baseline straight back. Sign included: see sArtBaseX for what a sampled scaleX cost.
     */
    static void scaleArt(View art, float factor) {
        float x = art.getScaleX(), y = art.getScaleY();
        // Only adopt a reading that could be a RESTING value. The OEM animates this view, and
        // a frame caught mid-animation is not a baseline: catching a 0 made the thumbnail
        // vanish for good, because from then on every frame wrote 0 * factor and the value it
        // read back was its own 0. Magnitude has to be ~1 - and only the magnitude, because
        // the sign is the OEM's mirror and taking that as "not resting" is what flipped the
        // thumbnail before. Anything else leaves the last good baseline in place.
        if ((x != Main.sArtWroteX || y != Main.sArtWroteY) && Main.resting(x) && Main.resting(y)) {
            Main.sArtBaseX = x;
            Main.sArtBaseY = y;
        }
        float nx = Main.sArtBaseX * factor, ny = Main.sArtBaseY * factor;
        if (x != nx) art.setScaleX(nx);
        if (y != ny) art.setScaleY(ny);
        Main.sArtWroteX = nx;
        Main.sArtWroteY = ny;
    }
}
