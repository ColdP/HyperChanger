// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.TrackName;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import android.content.Context;
import android.media.session.MediaController;
import android.text.Layout;
import android.widget.TextView;
import java.util.List;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * The OEM media card on the lock screen: whether it is up (the switch cover mode follows), its
 * log, where it sits, the guard that keeps our changes on it, and the cover look applied to it.
 *
 * Carved out of Main as it was (2026-10-14, the second cut after CoverPush): every member moved
 * whole with its comments, references across the line prefixed with Main. or MediaCard., and
 * nothing else changed - the reasoning in each comment is still the reasoning, and git log -L on
 * Main.java finds its history.
 */
public final class MediaCard {
    private MediaCard() {}

    /**
     * Whether the OEM media card is on the lockscreen. This, not playback state, is the switch:
     * pausing leaves the card up, so it leaves the cover up too, and dismissing the card is the
     * one thing that ends music mode.
     */
    static volatile boolean sCardShowing;

    /**
     * The card is there for the row of islands: up at once, gone only once "gone" has stood for
     * CARD_GONE_MS, in either mode. sCardShowing follows the OEM straight away without automatic
     * cover mode, and a track change is a removal and an add - the islands would lose the music
     * for every skip.
     */
    static volatile boolean sCardPresent;

    /** False until the card hook has fired once, so a restart does not act on an unknown state. */
    static volatile boolean sCardKnown;

    /** The session the card itself is bound to - exact, where pickController() only guesses. */
    static volatile android.media.session.MediaSession.Token sCardToken;

    /** Track identity straight off the card's MediaData. */
    static volatile String sCardKey = "";

    /**
     * A track change arrives as a remove followed by an add, so the card is briefly gone. Wait
     * this long before believing it: long enough to absorb that pair, short enough that
     * dismissing the card by hand still feels immediate.
     */
    static final long CARD_GONE_MS = 600L;

    /**
     * Where the card lands on the lock screen, in screen pixels. The app draws a preview of the
     * lock screen and cannot measure this itself - the card belongs to SystemUI - so it is
     * sampled here, while the keyguard is actually up, and kept for the app to ask about later.
     */
    static volatile int sCardL, sCardT, sCardW, sCardH;

    /** The reading waiting to be confirmed by holding still. See sampleCardRect(). */
    static int sCardSampleL, sCardSampleT, sCardSampleW, sCardSampleH;

    static long sCardSampleAt;

    static ViewTreeObserver.OnPreDrawListener sCardGuard;

    static View sCardGuarded, sCardArt, sCardTitle, sCardArtist;

    /**
     * How far the card is into the cover look: 0 is the card as the OEM draws it, 1 is the
     * artwork hidden and the text centred.
     *
     * Deliberately NOT an animator of its own. applyCollapse() already derives a 0..1 progress
     * from the notifY of the frame it is on, and both the clock's collapse scale and the glass
     * morph are read off it; hanging the card off the same number means the OEM's spring drives
     * the thumbnail, the clock and the glass together, and there is nothing left to keep in sync.
     */
    static volatile float sCardP;

    static boolean sCardFramePosted;

    /** The card's progress into the cover look, as last written. */
    public static float cardProgress() {
        return sCardP;
    }

    /** One frame of the card fade, from ClockCollapse's progress. */
    static void setCardProgressFrom(float p) {
        // 保留壁纸's veil rides the same number, and only while the clock is the cover's.
        KeepDim.apply(Main.keepsWallpaper() && Main.coverHoldsClock() ? p : 0f);
        // The clock can be held for an immersive page alone, and the card is not part of that
        // look. The cover's exit is let through: that is its card fading back.
        if (!Main.sCoverMode && !LockHold.exitingFor(LockHold.Owner.COVER)) p = 0f;
        setCardProgress(p);
    }

    static final int CARD_RETRIES = 12;

    static final long CARD_RETRY_MS = 250L;

    /** How long the card has to hold still before its position is believed. */
    static final long CARD_SETTLE_MS = 400L;

    /**
     * The top edge of the card rectangle on record, in screen pixels, or NaN when what is on
     * record is not a reading.
     *
     * The top rather than the bottom because that is the edge the band is measured against while
     * the card is up - the live route reads the card's own top - and a fallback that answers a
     * different edge answers a whole card's height away. On this screen the card is 1700..2257:
     * the two are 557px apart, 21% of the screen, and the lyrics moved half of that every time
     * the route changed. See LockLyrics.bandBottomOnScreen() for the wake that measured it.
     *
     * sampleCardRect() only ever writes a settled card in the lower two thirds, and the state file
     * re-applies the same test on load - so a zero, or a reading taken from the shade, is already
     * excluded on the way in. The test is repeated here because sScreenH is the default 2608 until
     * the container attaches, which is after loadState() has run.
     */
    public static float sampledCardTop() {
        if (sCardT <= Main.sScreenH / 3 || sCardH <= 0) return Float.NaN;
        return sCardT;
    }

    /**
     * Finds the card, applies the current look and leaves a guard behind to keep it applied.
     * Called whenever the card or cover mode changes; re-running it is free.
     *
     * The retries are the same story as the cut-out's: on a SystemUI that has just started,
     * cover mode is restored the moment the clock container attaches, which can be before the
     * notification stack has inflated anything.
     */
    static void applyMediaCard() {
        applyMediaCard(0);
    }

    static void applyMediaCard(final int attempt) {
        if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) {
            releaseCardGuard();
            return;
        }
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) return;
                View card = Main.findSysuiView("mi_media_controls");
                if (card == null) {
                    // Only worth waiting for while something still wants something from the
                    // card: the restyle on the way in, or the artwork tap that is the way back.
                    if (attempt < CARD_RETRIES
                            && ((Main.sCoverMode && (Main.sMcHideArt || Main.sMcTitleTap || Main.sMcPageBtn))
                                || Main.wantsArtTap())) {
                        Main.main().postDelayed(new Runnable() {
                            @Override
                            public void run() { applyMediaCard(attempt + 1); }
                        }, CARD_RETRY_MS);
                    }
                    return;
                }
                View art = card.findViewById(card.getResources()
                        .getIdentifier("album_art", "id", "com.android.systemui"));
                if (art != null && art != sCardArt) {
                    // A card view we have not written to. Forgetting what we wrote to the last
                    // one makes scaleArt() read this one's scale as the OEM's, which it is -
                    // otherwise a new thumbnail that happened to arrive carrying the number we
                    // left on the old view would be scaled against the wrong baseline.
                    Main.sArtWroteX = Main.sArtWroteY = Float.NaN;
                }
                sCardArt = art;
                sCardTitle = card.findViewById(card.getResources()
                        .getIdentifier("header_title", "id", "com.android.systemui"));
                sCardArtist = card.findViewById(card.getResources()
                        .getIdentifier("header_artist", "id", "com.android.systemui"));
                assertMediaCard(card);
                if (Main.sMcPageBtn || (Main.sCoverMode && (Main.sMcHideArt || Main.sMcTitleTap))) guardCard(card);
                else releaseCardGuard();
            }
        });
    }

    /**
     * One pass of "the card should look like this". Runs from the guard, so every write is
     * conditional on the value actually being wrong - setVisibility and setTranslationX only
     * invalidate, but setGravity requests a layout, and doing that unconditionally from a
     * pre-draw listener is a traversal loop.
     */
    static void assertMediaCard(View card) {
        if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()) return;
        // The lock screen and the shade share one media card view, so "is the keyguard up" has
        // to be part of the condition rather than just cover mode: without it, pulling the shade
        // down on an unlocked phone would show the same restyled card, and these two settings
        // are about the lock screen. The clock container is the test - only the keyguard shows
        // it - and because this runs every frame, the card restores itself on unlock by itself.
        View c = Main.sContainer;
        boolean onKeyguard = Main.sCardForced || (Main.keyguardShowing() && c != null && c.isShown());
        // A capture for the app's preview wants the settled look, not whichever frame an
        // animation happens to be on. Everywhere else the fade IS the state.
        float p = !onKeyguard ? 0f
                : Main.sCardForced ? (Main.sCoverMode ? 1f : 0f)
                : sCardP;
        // The exception: with the lyrics up, the thumbnail the setting hides comes back.
        //
        // Held across the exit of cover mode rather than asked there. The lyric view fades out on
        // the card's own progress, so on the way out the lyrics are still on screen while they
        // fade - but the phase has already left ON, and wantsShown() answers "is it on screen
        // right now", so it said no on the exit's first frame. hideP went to 1 with p still at 1:
        // the thumbnail vanished and the title snapped to the centre, and then the falling p
        // brought both back over the rest of the exit. That is the blink. The answer is held for
        // the exit and re-asked once the clock has landed, where it cannot be seen.
        boolean flying = ClockCollapse.phase() == ClockCollapse.Phase.EXIT;
        if (!flying) {
            // The screen going off is not one of the lyrics' comings and goings, which is why the
            // test is not wantsShown() alone. That answers "is the lyric view on screen now", and
            // it says no the moment the screen does off - so the always-on display, which keeps
            // drawing this card, lost the thumbnail every time the screen went dark. With the
            // screen off, or in the AOD a wake has not left yet, the question is the standing one
            // instead: are the lyrics what this lock screen is showing.
            CardArt.sLyricUp = LockLyrics.wantsShown() || LockLyrics.heldForBlur()
                    || ((ClockCollapse.phase() == ClockCollapse.Phase.AOD || !Main.screenOnCached())
                        && LockLyrics.wantsAttached());
        }
        float exc = CardArt.stepLyricArt(Main.sMcArtInLyrics && CardArt.sLyricUp);
        // One number, on purpose. Centring is not a setting of its own any more - a title with no
        // thumbnail beside it belongs in the middle and a title that has one does not - so the
        // title follows the same value the thumbnail does, and both now ride the exception's
        // progress instead of stepping to it: the thumbnail comes back and the title slides
        // aside for it, at the speed of the transition they are part of.
        float hideP = Main.sMcHideArt ? p * (1f - exc) : 0f;
        float centreP = hideP;
        View art = sCardArt;
        if (art != null) {
            // INVISIBLE at the far end, not GONE: the constraints around it are the card's
            // whole layout, and collapsing the artwork would drag the text sideways on its own
            // terms rather than ours. It also leaves the thumbnail's bitmap in place, which is
            // still the module's last-resort source for the cover itself.
            //
            // Anywhere short of the end it is VISIBLE with an alpha and a scale, and that is
            // the fade. Both only invalidate, so writing them from a pre-draw listener carries
            // the same guarantee the translationX below does, and the two resting states are
            // bit-for-bit the two the card had before there was an animation at all.
            if (hideP >= 1f) {
                if (art.getVisibility() != View.INVISIBLE) art.setVisibility(View.INVISIBLE);
                // Out of sight, but put back exactly as the OEM had it: the shade shows this
                // same view, and a thumbnail left at 0.82 of its size there would be ours.
                CardArt.scaleArt(art, 1f);
                if (art.getAlpha() != 1f) art.setAlpha(1f);
            } else {
                if (art.getVisibility() != View.VISIBLE) art.setVisibility(View.VISIBLE);
                float a = 1f - hideP;
                if (art.getAlpha() != a) art.setAlpha(a);
                CardArt.scaleArt(art, 1f - hideP * (1f - CardArt.CARD_ART_MIN_SCALE));
            }
            if (CoverMorphLayer.active()) {
                // The moving copy owns these pixels until it reaches either endpoint. Keep the
                // OEM view in the layout so its slot and the title continue to move normally.
                // On the way back it fades in under the landing copy instead of being switched on
                // at the end: the copy hides its pixels, but not its shadow, which popped in.
                if (art.getVisibility() != View.VISIBLE) art.setVisibility(View.VISIBLE);
                float a = Math.min(1f - hideP, CoverMorphLayer.thumbAlpha());
                if (art.getAlpha() != a) art.setAlpha(a);
            }
        }
        centreCardText(card, (TextView) sCardTitle, centreP);
        centreCardText(card, (TextView) sCardArtist, centreP);
        CardTouch.applyTitleTap((TextView) sCardTitle, Main.sMcTitleTap && Main.sCoverMode && onKeyguard);
        // On the card's progress, so it comes and goes with the restyle - including the exit,
        // where cover mode is already off but the card is still fading back. Not in a capture
        // for the preview, and not in the AOD: that is a smaller stand-in card with no taps.
        boolean pageBtn = Main.sMcPageBtn && onKeyguard && !Main.sCardForced
                && LockLyrics.sEnabled && LockLyrics.hasLyrics()
                && Main.screenOnCached() && ClockCollapse.phase() != ClockCollapse.Phase.AOD;
        // Which page it would go to. Asked of the lyrics' own intent while cover mode is on, so
        // the icon flips with the tap rather than when the lyric view has finished fading; held
        // through the exit like the thumbnail's exception above.
        boolean onLyrics = Main.sCoverMode ? LockLyrics.wantsAttached() : CardArt.sLyricUp;
        CardPageButton.apply(card, pageBtn ? 1f : 0f, onLyrics, (TextView) sCardTitle);
        if (onKeyguard && !Main.sCardForced) sampleCardRect(card, p);
    }

    /**
     * Asks for one frame of the card's pass.
     *
     * A retarget writes the same values it wrote a frame ago, so it invalidates nothing and the
     * frame that would move the spring never arrives. One posted frame is enough to start it:
     * from there every step writes a different alpha, scale and translationX, and those are what
     * carry the next frame.
     */
    static void kickCardFrame() {
        final View card = sCardGuarded;
        if (card == null || sCardFramePosted) return;
        sCardFramePosted = true;
        Gen.postOnAnimation(card, new Runnable() {
            @Override
            public void run() {
                sCardFramePosted = false;
                if (sCardGuarded == card) assertMediaCard(card);
            }
        });
    }

    /**
     * Centres one line of the card's text on the card.
     *
     * translationX only - it moves the view without touching the constraints, so the OEM can
     * re-run its own layout without fighting us and letting go is a single write back to zero.
     *
     * What is moved is the GLYPH RUN, not the view. The two obvious approaches both fail here:
     * the text views are constrained to the space beside the artwork, so centring the view in
     * the card leaves the text sitting at the left edge of a box that is wider than the words;
     * and setGravity(CENTER_HORIZONTAL) had no effect on this card at all (reported from the
     * device: text still hard left). Reading the drawn line straight off the Layout sidesteps
     * both - it already accounts for whatever gravity, padding and ellipsis are in force, so
     * the sum below is "where the ink starts now" against "where it should start".
     */
    static void centreCardText(View card, TextView t, float p) {
        if (t == null) return;
        if (p <= 0f) {
            if (t.getTranslationX() != 0f) t.setTranslationX(0f);
            return;
        }
        Layout lay = t.getLayout();
        if (lay == null || lay.getLineCount() < 1 || card.getWidth() <= 0) return;
        float ink = lay.getLineWidth(0);
        if (ink <= 0f) return;
        float have = t.getLeft() + t.getPaddingLeft() + lay.getLineLeft(0);
        // Scaled by the progress, so the line slides between where the OEM put it and the
        // centre instead of jumping between the two.
        float dx = ((card.getWidth() - ink) / 2f - have) * p;
        if (t.getTranslationX() != dx) t.setTranslationX(dx);
    }

    /**
     * Records where the card is, for the app's preview.
     *
     * Only called with the keyguard actually showing: the same card is what the shade puts at
     * the top of the notification list, at a completely different height, and a preview drawn
     * from that reading would put the card where it never appears on the lock screen.
     *
     * A reading is only believed once it has held still for CARD_SETTLE_MS, which is the same
     * rule the date offset uses and for the same reason: the card slides in when it appears and
     * slides away again at unlock, so those frames are readings of a card in flight. Measured
     * without any settling rule: 62,1676 - the card twenty pixels off centre, caught on its way
     * out. Two identical frames were not enough either - a card can be laid out at its starting
     * position and sit there for a few frames before the animation begins, which is how a
     * reading 244px above the real one got through.
     */
    static void sampleCardRect(View card, float p) {
        // Once, on the way past: the square cover's corners are the media card's own now, and this
        // is a frame the card is on screen and laid out for. See noteMediaCardRadius().
        if (!sMediaCardRadiusKnown) noteMediaCardRadius();
        // Not mid-morph. The reading below is of a card that has HELD STILL for CARD_SETTLE_MS,
        // and while the cover is going up or coming down the card is moving on every frame - so
        // the settle test can never pass and the only thing a read here produces is the bill for
        // getLocationOnScreen(), which walks the parent chain and its transforms. Asked on every
        // frame of a transition it measured 100us and up, which was the whole of what the card's
        // share of a frame cost once the two binder calls were memoised.
        //
        // The ends are what it is for: p at 0 is the card as the OEM lays it out, p at 1 is the
        // restyled one, and both hold still. Skipping the middle cannot lose a reading - the
        // settle rule stamps its clock from the first read, and the first read after the morph
        // stops is the same rectangle the skipped ones would have been stamped with.
        if (p > 0f && p < 1f) return;
        // Not while dozing. AOD shows the keyguard and holds still for as long as it is up, so
        // it sails past the settle rule below - and it lays the card out somewhere else, which
        // is how the preview ended up drawing it too high. isInteractive() is false in doze.
        if (!card.isShown() || !Main.screenOn()) return;
        int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] loc = Main.LOC_CARD;
        card.getLocationOnScreen(loc);
        // On the lock screen the card lives in the notification area, below a clock pinned near
        // the top; it is never up by the status bar. Anything that high is the shade's copy of
        // the same view - the panel can be pulled down over the lock screen too, where the
        // keyguard test above still passes.
        if (loc[1] < Main.sScreenH / 3) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (loc[0] != sCardSampleL || loc[1] != sCardSampleT
                || w != sCardSampleW || h != sCardSampleH) {
            sCardSampleL = loc[0];
            sCardSampleT = loc[1];
            sCardSampleW = w;
            sCardSampleH = h;
            sCardSampleAt = now;
            return;
        }
        if (now - sCardSampleAt < CARD_SETTLE_MS) return;
        if (loc[0] == sCardL && loc[1] == sCardT && w == sCardW && h == sCardH) return;
        sCardL = loc[0];
        sCardT = loc[1];
        sCardW = w;
        sCardH = h;
        Xp.log(Main.TAG + "media card at " + sCardL + "," + sCardT + " " + sCardW + "x" + sCardH);
        CoverCardLayer.refresh();
        ModuleState.saveStateSoon();
    }

    /** How long between attempts to read the media card's radius, for a phone with no card. */
    static final long MEDIA_CARD_RADIUS_RETRY_MS = 2000L;

    /** Whether the media card's corner radius has been read off it yet. */
    static volatile boolean sMediaCardRadiusKnown;

    /** When the last attempt was, so a card that cannot be found is not looked for per frame. */
    static volatile long sMediaCardRadiusTriedAt;

    /**
     * Hands the square cover the media card's own corner radius.
     *
     * The two are the same piece of furniture on the same screen, and the square's corners were a
     * share of its own side until this - which is the same radius only at one particular size.
     * The reading goes to CoverCardStyle rather than being kept here: that class draws the
     * corners, and it must not have to name this one to do it. See CoverCardStyle.radius.
     *
     * The same view previewBundle reads, found the same way, so the two can never disagree about
     * which card is the lock screen's. Called from the card's own per-frame hook, so it is floor'd
     * and one-shot: it walks the tree, the radius of a view already on screen does not change, and
     * a phone with no media card at all would otherwise pay for this walk on every frame.
     */
    static void noteMediaCardRadius() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sMediaCardRadiusTriedAt < MEDIA_CARD_RADIUS_RETRY_MS) return;
        sMediaCardRadiusTriedAt = now;
        View cardView = PreviewShots.cardShotRoot(Main.findSysuiView("mi_media_controls"));
        View bg = cardView == null ? null : Main.findByName(cardView, "media_bg");
        float r = bg == null ? 0f : Main.outlineRadius(bg);
        if (r <= 0f) return;
        sMediaCardRadiusKnown = true;
        CoverCardStyle.noteMediaCardRadius(r);
        Xp.log(Main.TAG + "media card radius = " + r + "px (the square cover's corners follow it)");
        CoverCardLayer.refresh();
    }

    static void setCardProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (Main.perfOff("card")) p = p < 0.5f ? 0f : 1f;
        if (sCardP == p) return;
        sCardP = p;
        CoverCardLayer.refresh();
        View card = sCardGuarded;
        if (card != null) assertMediaCard(card);
    }

    /** The rect cardRectContains tests, and whether the card is up at all, for the touch log. */
    static String describeCardRect() {
        View header = Main.miniPlayerMediaHeader();
        if (header == null) return "none";
        int[] loc = new int[2];
        header.getLocationOnScreen(loc);
        return (header.isShown() ? "" : "hidden:") + loc[0] + "," + loc[1] + "+" + header.getWidth()
                + "x" + header.getHeight();
    }

    static boolean cardRectContains(float x, float y) {
        View header = Main.miniPlayerMediaHeader();
        if (header == null || !header.isShown() || header.getWidth() <= 0) return false;
        int[] loc = new int[2];
        header.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + header.getWidth()
                && y >= loc[1] && y < loc[1] + header.getHeight();
    }

    static void guardCard(final View card) {
        if (sCardGuarded == card && sCardGuard != null) return;
        releaseCardGuard();
        sCardGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() { android.os.Trace.beginSection("MC cardGuard"); try {
                assertMediaCard(card);
                return true;
            } finally { android.os.Trace.endSection(); } }
        };
        Gen.preDraw(card, sCardGuard);
        sCardGuarded = card;
        Xp.log(Main.TAG + "media card guard installed");
    }

    /** Restore only a card this generation had taken, before publishing the ownership gate. */
    static void releaseHostLayout() {
        if (sCardGuarded != null) {
            sCardP = 0f;
            boolean forced = Main.sCardForced;
            Main.sCardForced = false;
            try { assertMediaCard(sCardGuarded); }
            finally { Main.sCardForced = forced; }
        }
        releaseCardGuard();
    }

    static void releaseCardGuard() {
        View d = sCardGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCardGuard;
        sCardGuarded = null;
        sCardGuard = null;
        // A frame asked for by kickCardFrame() may still be on its way, and it will find nothing
        // to assert into. Cleared here so the next cover, whose guard is a different card view,
        // is not told a frame is already posted when there is none.
        sCardFramePosted = false;
        // The guard is what gives the title back, so it has to happen here: with the guard gone
        // nothing would ever run the assert that would have done it, and the shade would keep a
        // title that pauses the music.
        CardTouch.applyTitleTap(null, false);
        // The same for the page button: drawn on the card's overlay, which is the shade's too.
        CardPageButton.release();
        if (d == null || g == null) return;
        try {
            Gen.unpreDraw(d, g);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The last few things that happened to the media card, as `op card` prints them.
     *
     * The card disappearing has three unrelated causes and the module can only see one of them
     * directly. The OEM dropping its media data arrives here, at onCardChanged(null), and is
     * recorded. A third party hiding the VIEW - HyperLight's music capsule rewrites
     * MiuiMediaHeaderView's VISIBLE into GONE, measured 2026-09-21 - never reaches any hook of
     * ours at all, so it leaves no event: what names it is the live snapshot `op card` takes
     * before printing this, where our own state still says the card is up while the view on
     * screen is GONE. A player dying shows up as the first with no track change before it.
     *
     * Nothing here hides or shows anything. Cover mode follows the card (see applyCardState);
     * the card follows the OEM.
     */
    static final String[] sCardLog = new String[10];

    static int sCardLogN;

    /** One line about the card right now: our state, and the view as it stands. */
    static String cardSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("auto=").append(Main.sAuto).append(" cover=").append(Main.sCoverMode)
          .append(" known=").append(sCardKnown).append(" showing=").append(sCardShowing)
          .append(" forced=").append(Main.sCardForced)
          .append(" token=").append(sCardToken != null)
          .append(" key=").append(sCardKey == null || sCardKey.isEmpty() ? "-" : sCardKey);
        View c = sCardGuarded;
        if (c == null) {
            try {
                c = Main.findSysuiView("mi_media_controls");
            } catch (Throwable ignored) {
            }
        }
        if (c == null) {
            sb.append(" view=none");
            return sb.toString();
        }
        sb.append(" view=").append(Main.visName(c.getVisibility()))
          .append(" shown=").append(c.isShown())
          .append(" attached=").append(c.isAttachedToWindow())
          .append(" alpha=").append(Main.r2(c.getAlpha()))
          .append(" h=").append(c.getHeight());
        // Whoever is actually keeping it off screen, named. isShown() is false as soon as any
        // ancestor is not VISIBLE, and which one it is decides whether this is the OEM's own
        // layout or somebody else's setVisibility.
        for (android.view.ViewParent vp = c.getParent(); vp instanceof View;
             vp = ((View) vp).getParent()) {
            View v = (View) vp;
            if (v.getVisibility() != View.VISIBLE) {
                sb.append(" hiddenBy=").append(Main.idOf(v)).append('=')
                  .append(Main.visName(v.getVisibility()));
                break;
            }
        }
        return sb.toString();
    }

    static void noteCard(String what) {
        int n = ++sCardLogN;
        sCardLog[(n - 1) % sCardLog.length] = "#" + n
                + "@" + (android.os.SystemClock.uptimeMillis() / 100) / 10f + "s "
                + what + " " + cardSnapshot();
    }

    /** The card's live state, then what has happened to it. Read by `op card`. */
    static String cardHistory() {
        StringBuilder sb = new StringBuilder("card now: " + cardSnapshot());
        sb.append(" | events=").append(sCardLogN);
        int keep = Math.min(sCardLogN, sCardLog.length);
        for (int i = 0; i < keep; i++) {
            String line = sCardLog[(sCardLogN - keep + i) % sCardLog.length];
            if (line != null) sb.append(" | ").append(line);
        }
        return sb.toString();
    }

    /**
     * The card appeared, changed track, or went away. A track change arrives as a removal
     * followed by an add, so "gone" is only believed after CARD_GONE_MS - otherwise every skip
     * would tear the wallpaper down and put it straight back.
     */
    static void onCardChanged(Object mediaData) {
        sCardKnown = true;
        boolean showing = mediaData != null;
        if (showing) {
            try {
                sCardToken = (android.media.session.MediaSession.Token)
                        Xp.getObjectField(mediaData, "token");
            } catch (Throwable t) {
                sCardToken = null;
            }
            sCardKey = cardKey(mediaData);
            // The card's title may be one the session callback has not shown us yet.
            TrackName.note(Main.sWatched);
        } else {
            sCardToken = null;
            sCardKey = "";
        }
        // Against the track the morph started on, not sTrackKey: a tap into cover mode clears
        // that, the card is rebound as the cover goes up, and every entry was cancelled a couple
        // of frames in - then begun again from the thumbnail by the entry's second call.
        if (!showing || (CoverMorphLayer.active() && !Main.sameTrack(sCardKey, Main.sMorphKey))) {
            CoverMorphLayer.cancel();
        }
        Xp.log(Main.TAG + "media card " + (showing ? "-> " + sCardKey : "gone"));
        noteCard(showing ? "OEM says up" : "OEM says gone (waiting " + CARD_GONE_MS + "ms)");
        Main.main().removeCallbacks(sCardGone);
        Main.main().removeCallbacks(sCardRemoved);
        if (showing) sCardPresent = true;
        else if (sCardPresent) Main.main().postDelayed(sCardRemoved, CARD_GONE_MS);
        if (!Main.sAuto) {
            sCardShowing = showing;
            MiniPlayerRuntime.refresh();
            return;
        }
        if (showing) {
            sCardShowing = true;
            MiniPlayerRuntime.refresh();
            applyCardState();
        } else {
            Main.main().postDelayed(sCardGone, CARD_GONE_MS);
        }
    }

    static final Runnable sCardGone = new Runnable() {
        @Override
        public void run() {
            sCardShowing = false;
            noteCard("gone stands, cover mode off");
            MiniPlayerRuntime.refresh();
            applyCardState();
        }
    };

    /** The card dismissed with its session still alive: the islands let go of the music. */
    static final Runnable sCardRemoved = new Runnable() {
        @Override
        public void run() {
            sCardPresent = false;
            MiniPlayerRuntime.mediaCardRemoved();
        }
    };

    /** Cover mode is on exactly when the card is up. Idempotent, so it is safe to re-run. */
    static void applyCardState() {
        // Nothing observed yet is not the same as nothing there: a SystemUI restart must not
        // tear down a cover that is on the phone just because the card hook has not fired for
        // the first time. That wait has to end somewhere though, and it did not - which left
        // the wallpaper on the last album for good. See releaseUnobservedCover().
        if (!Main.sAuto) return;
        if (!sCardKnown) {
            Main.releaseUnobservedCover(Main.activeSessions());
            return;
        }
        if (!sCardShowing) {
            Main.sTrackKey = "";
            // The card going away is what clears a tap-dismissed cover - but only when the music
            // went with it.
            //
            // Unlocking takes the card off the keyguard and the next lock puts it back, and that
            // round trip arrives here exactly as a dismissal does. Clearing on it unconditionally
            // is why a cover the user had just tapped away came back on its own: unlock, lock, and
            // the card returning read as a new session that had never been decided about. The
            // decision is meant to last as long as the music does.
            //
            // An EMPTY session list is the proof that the music itself has ended - a card cannot
            // exist without a session behind it - and it is the same evidence
            // releaseUnobservedCover() acts on. A list that cannot be read proves nothing either
            // way and leaves the decision standing; so does a list that still has something in it.
            if (Main.sTapSuppressed) {
                List<MediaController> sessions = Main.activeSessions();
                if (sessions != null && sessions.isEmpty()) {
                    Main.sTapSuppressed = false;
                    Xp.log(Main.TAG + "no session left: the tapped-away cover is forgotten");
                }
            }
            if (Main.sCoverMode) {
                Xp.log(Main.TAG + "media card dismissed, leaving cover mode");
                Main.setCoverEnabled(false, true);
            }
            return;
        }
        // The card is rebuilt around a track change, so this is where a fresh one is found -
        // and with the cover tapped away it is still where the tap listener is put back.
        if (Main.sCoverMode || Main.wantsArtTap()) applyMediaCard();
        Main.rebindSession();
    }

    /** Track identity as the card itself sees it. */
    static String cardKey(Object mediaData) {
        try {
            return Xp.getObjectField(mediaData, "packageName")
                    + "|" + Xp.getObjectField(mediaData, "song")
                    + "|" + Xp.getObjectField(mediaData, "artist");
        } catch (Throwable t) {
            return String.valueOf(mediaData);
        }
    }

    /** The controller the card is bound to, or null if the card has not named one. */
    static MediaController controllerFromCard(Context ctx) {
        android.media.session.MediaSession.Token t = sCardToken;
        if (t == null) return null;
        try {
            return new MediaController(ctx, t);
        } catch (Throwable e) {
            Xp.log(Main.TAG + "card token unusable: " + e);
            return null;
        }
    }

    static void hookMediaCard(final ClassLoader cl) {
        // The media card is the switch. Every add, track change and dismissal of the
        // lockscreen card funnels through this one static - MediaData in means a card is on
        // screen (and which track), null means it has gone. Playback state never reaches it,
        // which is exactly what we want: pausing leaves the card up, so it leaves the cover up.
        try {
            Class<?> card = Xp.findClass(Main.CLS_MEDIA_CARD, cl);
            Xp.hookAll("sysui.mediacard.top_data", card, "access$setTopMediaData", chain -> {
                java.util.List<Object> args = chain.getArgs();
                if (!args.isEmpty()) listenTo(args.get(0), cl);
                Object result = chain.proceed();
                Object data = args.size() > 1 ? args.get(1) : null;
                // The listener below runs inside the setter, so it has already seen this one.
                boolean seen = sHeardValid && sHeard == data;
                sHeardValid = false;
                sHeard = null;
                if (!seen) onCardChanged(data);
                return result;
            });
            Xp.log(Main.TAG + "media card hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "media card hook failed: " + t);
        }
        try {
            Class<?> card = Xp.findClass(Main.CLS_MEDIA_CARD, cl);
            Xp.hookAllConstructors("sysui.mediacard.ctor", card, chain -> {
                Object result = chain.proceed();
                listenTo(chain.getThisObject(), cl);
                return result;
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "media card listener hook failed: " + t);
        }
    }

    /**
     * The card change as the OEM's own listeners hear it, next to the setter hook above.
     *
     * The hook alone misses every change made by a caller that runs the setter's original past
     * the hooks - an older KeiMi build does exactly that for its carousel, so swiping to another
     * player's card changed the card and never reached us: the cover stayed on the first album.
     * The setter still tells topMediaListeners, whoever calls it, so we are one of them too
     * (KeiMi's own fix went the same way). Both paths fire on an ordinary change; the listener
     * runs first, inside the setter, and the hook skips the data it has already heard.
     */
    private static volatile Object sListened;
    /** The proxy added to sListened's topMediaListeners; the OEM holds it until we take it out. */
    private static Object sListener;
    private static Object sHeard;
    private static boolean sHeardValid;

    /**
     * Retirement: the card listener out of the OEM's set, the guard and the listeners off the
     * card, and the video surface released. The card itself is the OEM's and stays as it is.
     */
    static void retire() {
        unlisten();
        releaseCardGuard();
        CardVideo.retire();
        sHeard = null;
        sHeardValid = false;
    }

    /**
     * What a hot reload hands over: the OEM controller {@code topMediaListeners} our proxy was
     * in, the token the card is bound to, and the track it is showing. Host object, framework
     * object and primitives - the proxy itself is ours and cannot travel.
     */
    static Object[] save() {
        return new Object[]{sListened, sCardToken, sCardKey, sCardKnown, sCardPresent,
                sCardShowing};
    }

    /**
     * The new generation's half. The card is not rebuilt by a hot reload, so the OEM's set gets
     * the new proxy here rather than on the next bind; without this the cover follows nothing
     * until the user changes track. Runs on the main thread, before the container attach path
     * asks the card anything.
     */
    static void adopt(Object saved, ClassLoader cl) {
        if (!(saved instanceof Object[])) return;
        Object[] a = (Object[]) saved;
        if (a.length > 1 && a[1] instanceof android.media.session.MediaSession.Token) {
            sCardToken = (android.media.session.MediaSession.Token) a[1];
        }
        if (a.length > 2 && a[2] instanceof String) sCardKey = (String) a[2];
        if (a.length > 3 && a[3] instanceof Boolean) sCardKnown = (Boolean) a[3];
        if (a.length > 4 && a[4] instanceof Boolean) sCardPresent = (Boolean) a[4];
        if (a.length > 5 && a[5] instanceof Boolean) sCardShowing = (Boolean) a[5];
        if (cl != null && a.length > 0 && a[0] != null) {
            try {
                listenTo(a[0], cl);
            } catch (Throwable t) {
                Xp.w(Main.TAG + "media card listener not restored: " + t);
            }
        }
        Xp.log(Main.TAG + "media card adopted: known=" + sCardKnown + " present=" + sCardPresent
                + " showing=" + sCardShowing + " key=" + sCardKey
                + " listener=" + (sListened != null));
    }

    /** Takes our proxy out of the controller we added it to. Silent: every step is best effort. */
    private static void unlisten() {
        Object controller = sListened;
        Object listener = sListener;
        sListened = null;
        sListener = null;
        if (controller == null || listener == null) return;
        try {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> set =
                    (java.util.Set<Object>) Xp.getObjectField(controller, "topMediaListeners");
            set.remove(listener);
            Xp.log(Main.TAG + "media card listener removed");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "media card listener could not be removed: " + t);
        }
    }

    private static void listenTo(Object controller, ClassLoader cl) {
        if (controller == null || sListened == controller) return;
        // A new card controller: the previous one keeps every listener it was given, and ours
        // holds this class loader. Removed rather than abandoned.
        unlisten();
        try {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> set =
                    (java.util.Set<Object>) Xp.getObjectField(controller, "topMediaListeners");
            Class<?> iface = Xp.findClass(Main.CLS_MEDIA_CARD + "$MediaDataChangeListener", cl);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    (proxy, method, a) -> {
                        switch (method.getName()) {
                            case "onMediaDataChanged":
                                // A retirement that could not take this back out of the set
                                // leaves it here; a retired generation does not answer it.
                                if (!Gen.live()) return null;
                                Object data = a != null && a.length > 0 ? a[0] : null;
                                sHeard = data;
                                sHeardValid = true;
                                // Inside the OEM's own loop over its listeners: nothing of ours
                                // may throw out into it.
                                try {
                                    onCardChanged(data);
                                } catch (Throwable t) {
                                    Xp.w(Main.TAG + "card listener failed: " + t);
                                }
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return a != null && a.length > 0 && proxy == a[0];
                            case "toString":
                                return "MusicCover card listener";
                            default:
                                return null;
                        }
                    });
            set.add(listener);
            sListener = listener;
            sListened = controller;
            Xp.log(Main.TAG + "media card listener registered");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "media card listener not registered: " + t);
        }
    }
}
