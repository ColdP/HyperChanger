// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.ModuleState;
import btm.m.os4.systemuihook.hypermusiccover.next.MediaCard;
import btm.m.os4.systemuihook.hypermusiccover.next.ClockCollapse;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverCardLayer;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverMorphRoute;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverPush;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lock screen lyrics: which song's lines are loaded, where the singing is, and whether the view
 * should be in the keyguard at all. The drawing is LyricView's; Main only reports events here.
 *
 * Everything runs on SystemUI's main thread except the fetch, which LyricSource does on its own
 * threads and hands back on main.
 *
 * The lines belong to the TRACK, not to cover mode. Leaving cover mode and coming back on the
 * same song - two taps on the cover - keeps them, where the first version dropped them on exit
 * and never looked the same song up again.
 */
public final class LockLyrics {
    private LockLyrics() {
    }

    private static final String TAG = "[MCLyric] ";

    public static volatile boolean verbose;

    /**
     * Whether the lyrics are the lock screen's to show.
     *
     * ON, and not a switch any more: the app has no row for it and loadState does not read one.
     * The two-finger tap below is how the lock screen is asked to show the cover instead, and it
     * is the one that survives - it is what the setting was for most of its life. It does mean
     * the network fetch happens inside SystemUI without anyone asking for it; that is the trade
     * the setting was removed for, and `op lyrics` still turns it off for the rest of the
     * session.
     */
    public static volatile boolean sEnabled = true;

    /**
     * Whether the lock screen's two-finger tap has taken the lyrics away.
     *
     * A view of the switch, not the switch: the app's setting is untouched, and this only says
     * the lock screen shows the cover instead. It stands until the next two-finger tap - across
     * songs, lock screens, the card going away and coming back, and a SystemUI restart (it is in
     * the state file). It used to be dropped on every fresh entry into cover mode, which the user
     * read as the choice being forgotten whenever the media card went.
     *
     * The one other thing that clears it is the app's switch: turning the lyrics on while a tap
     * had hidden them would otherwise leave the switch saying on over a lock screen showing the
     * cover. Written by setEnabled, toggleByTap and the state file, and nowhere else.
     */
    public static volatile boolean sTapHidden;

    private static String sKey = "";
    private static List<LyricLine> sLines = Collections.emptyList();
    private static int sVersion;
    /** Bumped per lookup, so an answer overtaken by the next song is dropped. */
    private static int sGen;
    private static String sWhy = "";

    private static LyricView sView;
    private static MediaController sController;
    private static volatile PlaybackState sState;
    private static long sStateReadAt;

    /** The user's second switch: keep the screen lit while lyrics are playing on the lock screen. */
    public static volatile boolean sKeepOn;
    private static boolean sHolding;

    /**
     * The third switch: a held note's glow brighter than white on an HDR screen.
     *
     * Off by default, and it was off for a reason that has since been fixed: the colour mode is a
     * whole window's, and the lyrics used to share the lock screen's with the clock and the media
     * card, so the card's material was drawn as HDR content and brightened (reported 2026-09-16,
     * still there on 2026-09-29 with the headroom request removed - SurfaceFlinger picks its own
     * 5.00). With this switch on the lyrics have a window of their own, and only it is switched
     * - see {@link LyricWindow}; with it off they stay in the keyguard's layer, since the window
     * costs them the lock screen's z-order (see attach). Off by default until that has been
     * lived with.
     */
    public static volatile boolean sHdr = false;

    /**
     * The switch, on a screen that can show it. Everything the highlight changes - the window,
     * the colour mode, the gain - asks this rather than the switch: on a panel with no HDR
     * headroom the window is all cost (HdrDisplay), so the lyrics stay in the keyguard's layer
     * there whatever the switch says.
     */
    static boolean hdr() {
        return sHdr && HdrDisplay.supported(Main.sAppCtx);
    }
    /**
     * The fourth switch: whether a line's translation is drawn under it.
     *
     * On by default, because it is what the lyrics look like when the source has one: off is for
     * people who read the language and find the second line in the way. It is not a filter on
     * what is fetched - the layout drops the translation's rows, so the lines close up rather
     * than leaving a gap where it was.
     */
    public static volatile boolean sTrans = true;
    /**
     * The fifth: whether a line's romanisation is drawn under it, over the translation. Off by
     * default and apart from the translation's switch - either can be shown alone (#64). Like
     * the translation it is laid out, not fetched: switching it redraws what is already there.
     */
    public static volatile boolean sRoma = false;
    /**
     * Whether a lyric may come off the network (PR #103): the database, the TTML Hub, the
     * catalogues, the queue's read-ahead and the translations borrowed from any of them. Off,
     * only the player's own session, its file and the Lyricon bridge are read. On by default.
     */
    public static volatile boolean sOnline = true;

    /**
     * Switches sOnline and looks the song on screen up again: off, a lyric that came off the
     * network goes - with nothing local to replace it the song has none; on, a song with no
     * lyric, or no translation, gets the chance of one. The cache goes either way, since it
     * holds answers found under the other setting.
     */
    /** Invalidate the current track when a user imports, edits or deletes a library entry. */
    public static void hostLibraryChanged() {
        CACHE.clear();
        if (!sEnabled || sDemo || sKey.isEmpty() || sController == null) return;
        Main.main().removeCallbacks(SETTLED_LOOKUP);
        unpark();
        lookup(sKey, sController, false);
    }

    public static boolean setOnline(boolean on) {
        if (on == sOnline) return false;
        sOnline = on;
        Main.main().post(() -> {
            CACHE.clear();
            if (!sEnabled || sDemo || sKey.isEmpty() || sController == null) return;
            if (!on && sLines.isEmpty() && !sLoading) return;
            Main.main().removeCallbacks(SETTLED_LOOKUP);
            unpark();
            sInfoSeen = LyricSource.infoFor(sController);
            lookup(sKey, sController, on && !sLines.isEmpty());
        });
        return true;
    }
    /** The two switches together, as LyricLine.under() and the layout read them. */
    static final int BELOW_TRANS = 1, BELOW_ROMA = 2;

    static int below() {
        return (sTrans ? BELOW_TRANS : 0) | (sRoma ? BELOW_ROMA : 0);
    }
    /** Where the lines settle in their column: left, centre or right, as the settings offer. */
    static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;
    /**
     * The alignment pref: one of the three constants above.
     *
     * Unlike the switches this cannot be drawn around once changed - the alignment is baked into
     * every StaticLayout - so LyricView lays the lines out again when this differs from what the
     * layout in use was built with (see its builtAlign). Left is what the lyrics did before the
     * setting existed, and what a state file written before it loads as.
     */
    public static volatile int sAlign = ALIGN_LEFT;
    /** Immutable snapshot: the UI, renderer and state loader all use the same validated values. */
    public static volatile LyricStyle sStyle = LyricStyle.DEFAULT;
    /** Colours and opacity of the lead and the backing vocal; the default is the official look. */
    public static volatile LyricAppearance sAppearance = LyricAppearance.DEFAULT;
    /**
     * The most lines sung at the same time that are lit together (LyricFocus), 1..4. Not saved:
     * every overlapping line is lit, up to the ceiling; `op lyricmaxlines` lowers it for the
     * session.
     */
    public static volatile int sMaxLines = LyricFocus.DEFAULT_LIMIT;

    public static void setAppearance(String json) {
        LyricAppearance next = LyricAppearance.parse(json);
        if (sAppearance.toJson().equals(next.toJson())) return;
        sAppearance = next;
        LyricView view = sView;
        if (view != null) {
            view.invalidate();
            view.kick();
        }
    }

    public static boolean setMaxLines(int value) {
        int next = LyricFocus.clampLimit(value);
        if (next == sMaxLines) return false;
        sMaxLines = next;
        LyricView view = sView;
        if (view != null) view.kick();
        return true;
    }

    /**
     * Applies one alignment pref, validated here rather than at the callers: a state file and an
     * adb broadcast are both untrusted, and no screen could have set a fourth value.
     */
    public static boolean setAlign(int mode) {
        int next = mode <= ALIGN_LEFT ? ALIGN_LEFT : Math.min(mode, ALIGN_RIGHT);
        if (next == sAlign) return false;
        sAlign = next;
        LyricView view = sView;
        if (view != null) view.kick();
        return true;
    }

    public static boolean setStyle(String key, float value) {
        LyricStyle next = sStyle.with(key, value);
        if (next == sStyle) return false;
        sStyle = next;
        LyricView view = sView;
        if (view != null) view.kick();
        return true;
    }

    /** A lookup is in the air; the blur is held rather than dropped while it is. */
    private static boolean sLoading;
    /**
     * Which route the lines on screen came from, as a LyricSource.SRC_ constant.
     *
     * Kept so the session can win later: anything short of the session's own lyric is provisional
     * and is replaced if one turns up, because a provider module writes it seconds after the
     * track starts and the key cannot see the difference.
     */
    private static int sSource = LyricSource.SRC_NONE;
    /**
     * What the wallpaper process was last told about the blur, and when that answer was made.
     * 0 = nothing decided yet, which is what makes the first one always go.
     *
     * Volatile because every cover push reads it off the push thread: a track change carries the
     * answer with it, which is what settles a switch the other process missed.
     *
     * The answer and its time are one long - bit 0 the answer, the rest the uptime it was decided
     * at - because they are read as a pair by a push that is built on another thread. As two
     * fields a decision landing between the reads would send one answer with another's time, and
     * the time is what the wallpaper process uses to drop an answer older than the one in hand.
     */
    private static volatile long sBlurSent;

    /**
     * How long the lyrics hold back to let the background blur transition settle.
     * Measured on device: wallpaper FastPlayer reload takes ~150ms-250ms (total ~266ms from broadcast),
     * and TransitionDrawable / crossfade takes ~300ms-370ms.
     */
    private static final long BLUR_ENTER_DELAY_MS = 280L;
    private static final long BLUR_ENTER_MIN_MS = 200L;

    private static volatile long sBlurStartedAt;
    private static volatile long sTrackChangedAt;
    private static volatile boolean sBlurVideoReloaded;

    private static final Runnable BLUR_KICK = new Runnable() {
        @Override
        public void run() {
            LyricView v = sView;
            if (v != null) v.kick();
        }
    };

    private static void scheduleBlurKick(long delayMs) {
        Main.main().removeCallbacks(BLUR_KICK);
        Main.main().postDelayed(BLUR_KICK, Math.max(16L, delayMs));
    }

    public static void onVideoReload() {
        sBlurVideoReloaded = true;
        LyricView v = sView;
        if (v != null) v.kick();
    }

    /**
     * The wallpaper process has started the still cover's frosting that the blur turning on
     * asked for (WallpaperProbe.tellBlurShown); [seq] is the decision it answers.
     */
    public static void onBlurShown(long seq) {
        if (seq < sBlurOnSeq) return;
        sBlurShown = true;
        LyricView v = sView;
        if (v != null) v.kick();
    }

    /** The decision that last turned the blur on, and whether the wallpaper has shown it. */
    private static volatile long sBlurOnSeq;
    private static volatile boolean sBlurShown = true;

    /**
     * The longest the lyrics wait for a still cover's frosting: frosting it (~150ms, measured
     * 2026-10-06), then the cover's own fade still running (up to 370ms entering), then a frame
     * or two. Past it they come in without, as before.
     */
    private static final long STILL_BLUR_WAIT_MS = 1200L;

    static boolean blurSettled() {
        if (!blurWanted()) return true;
        long now = SystemClock.uptimeMillis();
        if (!Main.sVideoWallpaper) {
            // A still cover frosts on the composer and then waits out the fade in the air, so
            // lyrics that arrived after the cover came in 0.3-0.9s ahead of their blur (#57).
            // Held only when the blur is turning on: through a track change it stays on, and
            // holding there made the band blink out on every song.
            long e = now - sBlurStartedAt;
            return sBlurShown || sBlurStartedAt <= 0L || e < 0 || e >= STILL_BLUR_WAIT_MS;
        }
        long blurElapsed = now - sBlurStartedAt;
        if (sBlurStartedAt > 0L && blurElapsed >= 0 && blurElapsed < BLUR_ENTER_DELAY_MS) {
            return sBlurVideoReloaded && blurElapsed >= BLUR_ENTER_MIN_MS;
        }
        long trackElapsed = now - sTrackChangedAt;
        if (sTrackChangedAt > 0L && trackElapsed >= 0 && trackElapsed < BLUR_ENTER_DELAY_MS) {
            return sBlurVideoReloaded && trackElapsed >= BLUR_ENTER_MIN_MS;
        }
        return true;
    }

    /**
     * Attached and only waiting out the video window's reload (blurSettled). The lyrics are
     * coming, so for anything that lays itself out around them - the card's thumbnail - they
     * are already up: answered by wantsShown() alone, the thumbnail went out and the title
     * slid to the middle on every entry, then both came back when the band did.
     */
    public static boolean heldForBlur() {
        return wantsAttached() && !blurSettled();
    }

    /** Whether the cover should be frosted right now, for a push to carry over. */
    public static boolean blurWanted() {
        return (sBlurSent & 1L) != 0L;
    }

    /**
     * Puts this process's answer, and when it was made, on a cover push.
     *
     * The push is built on the worker while the answer is decided on the main thread, so the
     * answer a push reads can be one a tap has already replaced - and the push, being the slow
     * half, arrives after the switch that replaced it. Without the time on it the wallpaper
     * process has no way to tell which of the two is the newer answer and takes the last one to
     * arrive: the cover comes in sharp under its lyrics and stays that way for the song, because
     * both sides believe they agree. See WallpaperProbe.takeBlurDecision.
     */
    public static void putBlurOn(Intent out) {
        long state = sBlurSent;      // one read: the answer and its time travel together
        out.putExtra("lyricblur", (state & 1L) != 0L);
        out.putExtra("blurseq", state >>> 1);
    }

    /** Writes an answer down, timed on the clock both processes share, and answers with it. */
    private static long setBlurSent(boolean on) {
        sBlurSent = SystemClock.uptimeMillis() << 1 | (on ? 1L : 0L);
        return sBlurSent;
    }

    private static boolean sDemo;
    private static long sDemoT0;

    private static View sCard;
    private static long sCardLookAt;

    /**
     * A session has carried its own lyric at some point since SystemUI started.
     *
     * The settings page asks, to tell "a provider module is installed" apart from "a provider
     * module is working". Those are different: LyricInfo is an LSPosed module, and one that is
     * installed but not enabled, or enabled without the player in its scope, writes nothing at
     * all while still being present in the package list. Only having read a real lyric off a
     * session proves the whole chain.
     */
    public static boolean sSawSessionLyric;

    /** Lines plus the route they came by - a cache hit has to answer both. */
    private static final class Cached {
        final List<LyricLine> lines;
        final int source;

        Cached(List<LyricLine> lines, int source) {
            this.lines = lines;
            this.source = source;
        }
    }

    /** Songs recently shown, so skipping back and forth does not go to the network each time. */
    private static final Map<String, Cached> CACHE =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
                    return size() > 12;
                }
            };

    // ------------------------------------------------------------------ for the view

    static int version() {
        return sVersion;
    }

    static List<LyricLine> lines() {
        return sLines;
    }

    /**
     * Whether the lyrics are wanted at all: the switch says so, and no tap has taken them away.
     * A demo asks for them itself, whatever the switch says.
     */
    private static boolean wanted() {
        return sEnabled && !sTapHidden || sDemo;
    }

    /**
     * Whether this track has lyrics to show - the lyric page is only the page while it does, and
     * a track without (a Douyin or Bilibili video, a song no source has) keeps the cover. It was
     * the page whenever the switch was on, and in card mode that hid the square over nothing.
     *
     * Through a lookup the last answer stands, the same hold the cover's blur has: a song with
     * lyrics followed by one still being looked up keeps the lyric page rather than flashing the
     * cover up for the length of the lookup, and a song without keeps the cover.
     *
     * The cache counts as well as the lines on screen: a two-finger tap that hides the lyrics
     * drops the lines, and the song still has them to bring back.
     */
    public static boolean hasLyrics() {
        return sDemo || !sLines.isEmpty() || CACHE.containsKey(sKey)
                || (sLoading && sHadLyrics);
    }

    /** Whether the last settled answer - not one still loading - had lines. See hasLyrics(). */
    private static boolean sHadLyrics;

    /** Whether the view belongs in the keyguard right now. */
    public static boolean wantsAttached() {
        return wanted() && Main.coverModeOn() && hasLyrics();
    }

    /**
     * Whether the lyric page should be *drawn* right now: the cover's answer, and the lock screen
     * actually being the thing in front.
     *
     * The second half is new with the window. The view used to be a child of the keyguard, so it
     * went away with it for free; a window of its own does not, and cover mode outlives the lock
     * screen (the media card is the switch, and the card is still there in the unlocked shade).
     * Without this the lyrics hang over the desktop after an unlock (user, 2026-09-29).
     *
     * Drawing only - NOT the window's lifetime, which is wantsAttached()'s. Detaching on an
     * unlock was the first try and cost the page until the next track change: the window came down
     * and cover mode had not exited, so nothing ever called attach() again on the way back to the
     * lock screen (user, 2026-09-29). Left up and empty instead, the view's own tick is still
     * running when the lock screen returns, and it brings the lines back by itself.
     */
    static boolean wantsWindow() {
        if (!wantsAttached()) return false;
        // In the keyguard's own layer the lock screen going takes the view with it, as it always
        // did; only the window needs telling.
        if (!hdr()) return true;
        return !lockScreenGone();
    }

    /**
     * The lock screen has gone, or has said it is going: the keyguard reads unlocked, or one of
     * the leaving hooks fired within the last LEAVING_MAX_MS. This is what the window's page is
     * cut on (LyricView.step's `gone`) - both halves, because the hook fires while the keyguard
     * still reads locked, and that stretch is exactly the residue the cut is for. The square
     * cover is cut on it too (Main.coverCardVisible).
     */
    public static boolean lockScreenGone() {
        if (!Main.keyguardLocked()) {
            // The keyguard's own answer has caught up: whatever "leaving" was for is spent.
            sLeaving = false;
            return true;
        }
        if (sLeaving && SystemClock.uptimeMillis() - sLeavingAt > LEAVING_MAX_MS) {
            // Told the lock screen was leaving, and it did not: a false alarm must not keep the
            // page out for the rest of the session.
            sLeaving = false;
        }
        return sLeaving;
    }

    /**
     * The lock screen is leaving, as told by whoever sees it start (Main hooks the keyguard
     * service's exit animation and MIUI's own injector, and names which in the probe).
     *
     * Deliberately not the KeyguardManager reading: at the start of the OEM's exit animation that
     * answer can still be "locked" for a few hundred ms - the animation is the dismissal - and the
     * page has to go *with* the lock screen rather than after it. This is also why the page is
     * cut rather than faded here (LyricView.step's `gone`): there is no lock screen left to fade
     * against, only the desktop behind it.
     */
    public static void lockScreenLeaving(String by) {
        sLeavingBy = by;
        sLeavingAt = SystemClock.uptimeMillis();
        sLastLeave = by + "@" + sLeavingAt;
        sLeaving = true;
    }

    /** When and by which door the lock screen last left, for the probe. Survives the clear. */
    static String lastLeave() {
        return sLastLeave.isEmpty() ? "none" : sLastLeave;
    }

    private static volatile String sLastLeave = "";

    /**
     * A light heartbeat while the page is up.
     *
     * It asks the keyguard question - cached for 150ms, so this is not a binder call every time -
     * and wakes the view the moment the answer changes what the page should do. Without it the
     * page only finds out on its next frame, and an idle page has none: it waits for the tick, up
     * to 500ms. That gap is exactly the "一瞬间的残留" an unlock left over the desktop (user,
     * 2026-09-29): the trace in `op lyricstate` caught the cut landing on a frame where the
     * keyguard already read unlocked, and `lastLeave=` says whether one of the leaving hooks saw
     * the lock screen go before the keyguard did.
     */
    private static final long WATCH_MS = 120L;
    /** The same beat in a doze, where it does nothing but keep the beat. See WATCH. */
    private static final long WATCH_AOD_MS = 1000L;
    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            LyricView v = sView;
            if (v == null) return;
            // A doze (or a screen that is off) asks nothing and wakes nothing: an unlock cannot
            // happen without a wake, the wake path re-reads the keyguard anyway, and eight wakeups
            // a second under a display that is trying to sit still is exactly what the AOD's own
            // 1Hz discipline is there to avoid (see aod-still-mode-lyrics). The beat stays because
            // the page is still up in there - the lyrics are shown in the held AOD.
            boolean doze = !Main.screenOnCached() || inHeldAod();
            if (!doze && v.pageWanted() != wantsWindow()) v.kick();
            // Unlocked and lit: the page is out and there is nothing left to watch for until the
            // lock screen comes back, which is a screen off (resumeWatch) or an attach. Beating on
            // here would be two binder calls every other beat for as long as music plays with
            // the phone in use - cover mode outlives the unlock.
            if (!doze && !Main.keyguardLocked()) return;
            Main.main().postDelayed(this, doze ? WATCH_AOD_MS : WATCH_MS);
        }
    };

    /** (Re)starts the heartbeat - only the window needs one; see WATCH. */
    private static void watch() {
        Main.main().removeCallbacks(WATCH);
        if (hdr() && LyricWindow.owns(sView)) Main.main().postDelayed(WATCH, WATCH_MS);
    }

    /** The screen went off or on: the lock screen may be back, so the heartbeat is too. */
    public static void resumeWatch() {
        if (sView != null) watch();
    }

    /**
     * Which of the doorways into "the lock screen is going" armed, for the probe: `+tag` armed,
     * `!tag` did not. Which one fires on an unlock is a question only the phone answers, and the
     * answer is the difference between a working signal and a hook that quietly is not there.
     */
    public static void armed(String tag) {
        sHooks = sHooks.isEmpty() ? tag : sHooks + " " + tag;
    }

    static String hooks() {
        return sHooks.isEmpty() ? "none" : sHooks;
    }

    private static volatile String sHooks = "";

    /** For the probe: who said so, or `-`. */
    static String leavingWhy() {
        return sLeaving ? "leave" + sLeavingBy : "-";
    }

    /**
     * How long "the lock screen is leaving" is believed without the keyguard agreeing. Short: it
     * only has to outlive the walk from the hook to the page, and a signal that fired on something
     * else has to give the page back quickly rather than keep it out until the next unlock.
     */
    private static final long LEAVING_MAX_MS = 800L;
    private static volatile boolean sLeaving;
    private static volatile long sLeavingAt;
    private static volatile String sLeavingBy = "?";

    /**
     * Where the lyrics grow out of on their next arrival: the music's island, as the entry from
     * it began - ColorOS's capsule-to-immersive entry (see LyricView.startPop). Screen pixels,
     * and the uptime it was taken at: an arrival later than POP_ORIGIN_MS is not this entry's.
     */
    private static volatile float sPopX = Float.NaN, sPopY = Float.NaN;
    private static volatile long sPopAt;
    private static final long POP_ORIGIN_MS = 1500L;

    /** An entry from the mini player: centre is the island's on screen, or null if not up. */
    public static void notePopOrigin(float[] centre) {
        if (centre == null) {
            sPopAt = 0L;
            return;
        }
        sPopX = centre[0];
        sPopY = centre[1];
        sPopAt = SystemClock.uptimeMillis();
    }

    /** The origin, once: out[0..1] on screen. False when there is none or it is stale. */
    static boolean takePopOrigin(float[] out) {
        long at = sPopAt;
        sPopAt = 0L;
        if (at == 0L || SystemClock.uptimeMillis() - at > POP_ORIGIN_MS) return false;
        out[0] = sPopX;
        out[1] = sPopY;
        return true;
    }

    /** The other way: where the island will be as the cover goes back into it. */
    private static volatile float sPopToX = Float.NaN, sPopToY = Float.NaN;
    private static volatile long sPopToAt;

    /** An exit into the mini player: centre is the island's home on screen, or null. */
    public static void notePopTarget(float[] centre) {
        if (centre == null) {
            sPopToAt = 0L;
            return;
        }
        sPopToX = centre[0];
        sPopToY = centre[1];
        sPopToAt = SystemClock.uptimeMillis();
    }

    static boolean takePopTarget(float[] out) {
        long at = sPopToAt;
        sPopToAt = 0L;
        if (at == 0L || SystemClock.uptimeMillis() - at > POP_ORIGIN_MS) return false;
        out[0] = sPopToX;
        out[1] = sPopToY;
        return true;
    }

    /** The lyric page a tap into cover mode will land on. */
    public static boolean willAttachOnEntry() {
        return hasLyrics() && CoverMorphRoute.lyricsAfterEntry(sEnabled, sTapHidden, sDemo);
    }

    public static boolean willAttachAfterTapToggle() {
        return hasLyrics() && CoverMorphRoute.lyricsAfterToggle(sEnabled, sTapHidden, sDemo);
    }

    /**
     * Whether it should be visible: attached, lit, and the clock settled small.
     *
     * Through ENTER as well, wake or toggle alike: they come up with the clock rather than after
     * it (user, 2026-09-16 - waiting for the landing made them turn up late). An early version
     * held them back because a recording (02:23) had them arriving on top of the full-size clock;
     * that was the band being stale, and it is measured from the clock's live ink every frame
     * now, so through the flight they follow it down instead.
     */
    public static boolean wantsShown() {
        if (!wantsWindow()) return false;
        // Hold the band back until the cover's blur transition has settled, so it does not
        // land over an artwork that is still sharpening. blurSettled() answers true when there
        // is no blur pending, so the held-AOD path below still shows through untouched.
        if (!blurSettled()) return false;
        ClockCollapse.Phase p = ClockCollapse.phase();
        if (p == ClockCollapse.Phase.ON || p == ClockCollapse.Phase.ENTER) {
            return Main.screenOnCached();
        }
        return inHeldAod();
    }

    /**
     * The full-screen always-on display that kept cover mode's clock - the lock screen, dimmed.
     *
     * The lyrics belong to it for the same reason the clock does: it is this lock screen being
     * shown, not the OEM's own doze, and both the clock's ink and the media card are still laid
     * out for the lyrics to sit between. Only that one doze: with the clock handed back to the
     * OEM there is nothing measured to sit under, and `inkBottomOnScreen()` says NaN.
     *
     * Which of the two dozes this is, is 息屏时显示大时钟's business and not this reading's:
     * whether the lyrics are drawn is wantsShown()'s, while layout and still-mode handling have
     * to go on knowing that the display is dozing.
     */
    static boolean inHeldAod() {
        return ClockCollapse.aodHeld() && !Main.screenOnCached();
    }

    /**
     * The media card, looked up again only when the one we hold has left the window.
     *
     * "Left the window" now includes "is not being shown": the card can be up in the tree and
     * hidden for a whole song - see cardTopOnScreen() - and a card nobody can see is a card whose
     * position nothing should be measured from, so the lookup is retried rather than held. What
     * comes back is still whatever the tree holds, shown or not: describe() reports whether the
     * card is up, and answering "none" there would lose the difference between "hidden" and
     * "not there at all".
     *
     * The lookup is findLockScreenView rather than findSysuiView for the same reason: the id is
     * declared by the media card and by both media island layouts, and the first match in the
     * tree is only the card while no island copy is up.
     */
    public static View card() {
        View c = sCard;
        if (c != null && c.isShown() && c.isAttachedToWindow()) return c;
        long now = SystemClock.uptimeMillis();
        if (now - sCardLookAt < 500L) return c;
        sCardLookAt = now;
        sCard = Main.findLockScreenView("mi_media_controls");
        return sCard;
    }

    /** The band stops above the card the lock screen is showing, read off it this frame. */
    private static final int BAND_LIVE = 0;
    /** ... at the same edge off the last card rectangle on record. */
    private static final int BAND_RECORDED = 1;
    /** ... off the card fraction LockPreview.kt carries for a phone nothing was measured on. */
    private static final int BAND_DEFAULT = 2;
    /**
     * The card's own share of the screen - the same fraction the app's preview falls back to
     * (LockPreview.kt's SAMPLE_CARD_T), so that a phone which has never had a card measured puts
     * the module's band and the app's preview in the same place.
     */
    private static final float CARD_TOP_FRACTION = 1700f / 2608f;
    /** Which of the three answered last; -1 until one has. */
    private static volatile int sBandSrc = -1;

    /**
     * Where the band's lower edge sits on screen, in pixels: the media card's own top edge.
     *
     * All three routes answer that same edge, and the sameness is the point. The band is the room
     * between the clock and the card and the block is centred in it, so an answer that moves by a
     * card's height moves the lyrics by half of one. The other two routes used to answer the
     * card's BOTTOM, deliberately: with no card drawn the band took the whole block the card would
     * have filled. That was for HyperLight's music capsule, which hooks
     * MiuiMediaHeaderView.setVisibility and rewrites the OEM's VISIBLE into GONE (measured
     * 2026-09-21) - and the lyrics used to give up entirely there, the band being unmeasurable.
     *
     * It cost a card's height every time the route changed, and a wake is exactly when the route
     * changes. Measured off a screen recording of the wake, 2026-09-28 (30fps, 880x1920 of a
     * 2608-tall screen): the block held still to within 12px for the whole recording except for
     * one step up of 230px of recording, three frames long, about a second after the screen came
     * on - and 230px of recording is 278px on the screen, which is 557/2, the half card that only
     * a route changing by a whole one can cost. The live card is what is missing for that first
     * second (see card()); which fallback answered instead is what `op lyricstate`'s bandSrc
     * names on the next wake. Reported as "the lyrics hide behind the media card, then jump up".
     *
     * The module has its own lock screen island now and no longer reserves the block for a card
     * that somebody else hid - where the card is taken away the lyrics keep the place they have
     * when it is up, which is also what makes the block hold still across every card appearance
     * and disappearance. So the fallbacks answer the top as well, and a route change can only
     * ever be the few pixels between a live reading and a recorded one.
     *
     * The recorded rectangle lags - the card is not sampled while it is hidden or dozing, and
     * onLockTap's notes record a reading of 1360 against a card actually at 1700 - so the route is
     * logged and reported rather than passed off as a measurement.
     *
     * Nothing on record at all leaves the fraction the app's preview uses, which is that same
     * edge: a phone that has never measured a card does not move when the first one arrives.
     */
    static float bandBottomOnScreen() {
        View c = card();
        int y = Integer.MIN_VALUE;
        if (c != null && c.isShown() && c.isAttachedToWindow()) {
            int[] loc = new int[2];
            c.getLocationOnScreen(loc);
            y = loc[1];
            // Below the clock, like the card. Anything higher is the shade's copy or an island,
            // and neither is what the band is measured against - unless it is the stack's own
            // card, pushed up by the notifications under it (Main.inNotificationStack).
            if (loc[1] >= Main.screenHeight() / 3 || Main.inNotificationStack(c)) {
                if (sBandSrc != BAND_LIVE && sBandSrc != -1) noteBandMiss("back live y=" + y);
                setBandSource(BAND_LIVE);
                return loc[1];
            }
        }
        // Only at the moment the live route is lost, so a card hidden for a whole song costs one
        // entry rather than one a frame.
        if (sBandSrc == BAND_LIVE) noteBandMiss(missReason(c, y));
        float recorded = MediaCard.sampledCardTop();
        if (!Float.isNaN(recorded)) {
            setBandSource(BAND_RECORDED);
            return recorded;
        }
        setBandSource(BAND_DEFAULT);
        return Main.screenHeight() * CARD_TOP_FRACTION;
    }

    /**
     * PROBE: the last few times the band lost the live card, and why - no view, a hidden one (and
     * which ancestor hides it), no height, or one found above the clock - with the phase and the
     * screen at that moment. For `op lyricstate`'s bandMiss=; this phone keeps no log.
     */
    private static final String[] sBandMiss = new String[8];
    private static int sBandMissN;

    private static void noteBandMiss(String what) {
        int n = ++sBandMissN;
        sBandMiss[(n - 1) % sBandMiss.length] = "#" + n + "@"
                + (SystemClock.uptimeMillis() / 100) / 10f + "s " + ClockCollapse.phase()
                + (Main.screenOnCached() ? " lit " : " dark ") + what;
    }

    private static String missReason(View c, int y) {
        if (c == null) return "no view";
        String id = " view@" + Integer.toHexString(System.identityHashCode(c))
                + " h=" + c.getHeight();
        if (!c.isAttachedToWindow()) return "detached" + id;
        if (!c.isShown()) {
            String by = c.getVisibility() != View.VISIBLE ? "itself" : "?";
            for (android.view.ViewParent p = c.getParent(); p instanceof View; p = p.getParent()) {
                View v = (View) p;
                if (v.getVisibility() != View.VISIBLE) {
                    by = Main.idOf(v) + "=" + v.getVisibility();
                    break;
                }
            }
            return "hidden by " + by + id;
        }
        return "above the clock y=" + y + id;
    }

    private static String bandMisses() {
        StringBuilder sb = new StringBuilder();
        int n = sBandMissN, len = sBandMiss.length;
        for (int i = Math.max(0, n - len); i < n; i++) sb.append(" | ").append(sBandMiss[i % len]);
        return sb.toString();
    }

    /** Which route answered bandBottomOnScreen(), as something readable in a broadcast result. */
    static String bandSource() {
        switch (sBandSrc) {
            case BAND_LIVE:
                return "live";
            case BAND_RECORDED:
                return "recorded";
            case BAND_DEFAULT:
                return "default";
            default:
                return "?";
        }
    }

    /**
     * Written once a frame from the band, so it only speaks when the answer changes: a card that
     * stays hidden for the whole song would otherwise log its line with every pre-draw.
     */
    private static void setBandSource(int src) {
        if (src == sBandSrc) return;
        sBandSrc = src;
        Xp.log(TAG + "band anchored to the " + bandSource() + " card position");
    }

    /** Where the singing is, extrapolated from the last position the session reported. */
    static int positionMs() {
        if (sDemo) {
            long t = SystemClock.uptimeMillis() - sDemoT0;
            return t < 0 ? 0 : (int) t;
        }
        PlaybackState s = sState;
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            long dt = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            if (dt > 0) pos += (long) (dt * s.getPlaybackSpeed());
        }
        return pos < 0 ? 0 : (int) Math.min(pos, Integer.MAX_VALUE);
    }

    static boolean playing() {
        if (sDemo) return true;
        PlaybackState s = sState;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    // ------------------------------------------------------------------ from Main

    /** The card is showing a track. Same key as last time is a no-op. */
    public static void onTrack(String key, MediaController c) {
        key = lyricKey(key, c);
        sController = c;
        readState(true);
        if (sDemo) {
            if (verbose) Xp.log(TAG + "demo is held, not looking " + key + " up");
            return;
        }
        if (key.equals(sKey)) {
            // Same song - but not necessarily the same evidence. A provider module (LyricInfo
            // and the ColorOS ones write the whole lyric to the session; the player itself may
            // too) cannot publish a lyric until it knows what is playing, so it writes one into
            // a session that already exists. None of the fields this key is built from change
            // when it does, which is the point of the key - so without this, the lyric a module
            // just went and fetched would sit on the session unread for the whole song, and
            // whatever we settled for in the first second would stand.
            //
            // The question is "is the session carrying a payload this song has not been read
            // against", not "did we settle for something worse than the session's own". The
            // second one cannot be asked of the case it most needs to catch: a payload read
            // during the track change belongs to the song before it as often as not, and once
            // one of those has been recorded as the session's own lyric, a gate that only fires
            // when the source is something else can never replace it. Asking after the payload
            // instead both fires for that and cannot loop, because each payload is tried once.
            rereadIfNewPayload(key, c);
            return;
        }
        String lastKey = sKey;
        sKey = key;
        long lastChange = sTrackAt;
        sTrackChangedAt = SystemClock.uptimeMillis();
        sTrackAt = sTrackChangedAt;
        Main.main().removeCallbacks(SETTLED_LOOKUP);
        unpark();
        sBlurVideoReloaded = false;
        if (sEnabled && !key.isEmpty()) scheduleBlurKick(BLUR_ENTER_DELAY_MS + 16L);
        // A different song: the budget above is per track, and so is the payload it was spent on.
        sInfoSeen = null;
        sInfoTries = 0;
        // The previous song's route says nothing about this one, and leaving it set would let a
        // track that follows a session-lyric track skip the upgrade check entirely.
        sSource = LyricSource.SRC_NONE;
        // Set before the lines are emptied, so the blur is held across the lookup instead of
        // being dropped by the empty set and put back when the answer lands.
        //
        // A cached answer counts as a lookup too, short as it is. Excluding it meant the empty
        // set below sent the blur off and the cache hit one line later sent it straight back on
        // - two messages, in the middle of the track change's crossfade, which is exactly when
        // the wallpaper process holds a switch back to wait for the fade. Those two could then
        // land in the wrong order and leave the cover sharp.
        sLoading = sEnabled && !key.isEmpty();
        setLines(Collections.<LyricLine>emptyList(), "track changed");
        if (!sEnabled || key.isEmpty()) return;
        Cached hit = CACHE.get(key);
        if (hit != null) {
            sLoading = false;
            sSource = hit.source;
            setLines(hit.lines, "cached");
            return;
        }
        // What the lookup about to start will read the session as, so a payload that turns up
        // after it - the provider module's real one - can be told apart from this one.
        sInfoSeen = LyricSource.infoFor(c);
        // The wait is for the network's sake. A lyric the session already carries for this song
        // costs nothing to read and is the best answer there is, so it is read at once: waited
        // for, QQ's own lyric came up SETTLE_MS late on every track (2026-10-06, 1.2s a track).
        if (sInfoSeen == null
                && ((lastChange > 0L && sTrackAt - lastChange < BURST_MS) || staleDuration(lastKey, key))) {
            Main.main().postDelayed(SETTLED_LOOKUP, SETTLE_MS);
            return;
        }
        lookup(key, c, false);
    }

    /**
     * A track change hard on the heels of another waits for the session to stop moving.
     *
     * QQ 音乐 publishes a new track several times over in its first second - measured
     * 2026-10-06, the durations 60000, 168000 and 237720 for one song, the artist going back and
     * forth between "Simyee陈芯怡" and "爱意侵占计划 (粤语版)-Simyee陈芯怡" - and every one was a
     * key of its own. Each started a lookup, each answer was thrown away as "arrived after the
     * track changed", and the one that counted started last, against the network the others
     * were still holding. A change on its own still looks up at once; one inside a burst waits
     * until nothing has moved for SETTLE_MS, and looks the settled key up once.
     */
    private static final long BURST_MS = 1500L;
    private static final long SETTLE_MS = 500L;

    /**
     * A new song carrying the old one's duration: QQ 音乐 publishes the next track's name a
     * half second before its duration (measured 2026-10-06: 反着爱一场 came in as 237226ms, the
     * song before it, then 173225ms). Looked up at once, the right song scored 60 against the
     * stale duration and was turned down. Waited for instead, like a burst; a song that really
     * is as long as the last one loses SETTLE_MS.
     */
    private static boolean staleDuration(String lastKey, String key) {
        if (lastKey == null || lastKey.isEmpty() || !pkgOf(lastKey).equals(pkgOf(key))) return false;
        String a = lastKey.substring(lastKey.lastIndexOf('|') + 1);
        String b = key.substring(key.lastIndexOf('|') + 1);
        return !a.isEmpty() && !"0".equals(a) && a.equals(b) && a.matches("[0-9]+");
    }

    private static final Runnable SETTLED_LOOKUP = new Runnable() {
        @Override
        public void run() {
            String key = sKey;
            MediaController c = sController;
            if (!sEnabled || sDemo || key.isEmpty()) return;
            Cached hit = CACHE.get(key);
            if (hit != null) {
                sLoading = false;
                sSource = hit.source;
                setLines(hit.lines, "cached");
                return;
            }
            sInfoSeen = LyricSource.infoFor(c);
            lookup(key, c, false);
        }
    };

    /**
     * Re-reads `key` when the session carries a payload it has not been read against. See the
     * same-song half of onTrack().
     *
     * Asked from two places: the same-song track update, and the end of every lookup. The second
     * is for a payload that lands while a lookup is running - which at a track change is the usual
     * case, not a rare one: our own Apple hook writes the lyric half a second after the track
     * changes, and the lookup takes one to three. The track update that brings it is turned away
     * because a lookup is in flight, and nothing else asks again until the card is rebuilt. When
     * the network had the song that cost only the better copy; when it did not, the song played
     * with no lyrics until a relock (user, 2026-09-30; log 07:52:01, payload at +0.5s, lookup
     * done at +2s, read only at the next entry at +5s).
     */
    /** A payload fetched by reference has come back (LyricSource.byRef). */
    static void onSessionPayload() {
        rereadIfNewPayload(sKey, sController);
    }

    private static void rereadIfNewPayload(String key, MediaController c) {
        // Parked is loading only in name: nothing is running, it is waiting for exactly this.
        // A lookup still running with nothing on screen is no reason to wait either: it is on
        // the network by now, and the session's own lyric outranks anything it can find. Turned
        // away, Apple's lyric - on the session 0.55s after the track change - waited out ten
        // seconds of catalogues that had nothing (2026-10-06, Here's to Never Growing Up).
        if (!sEnabled || key.isEmpty() || sDemo) return;
        if (sLoading && sParked == null && !sLines.isEmpty()) return;
        if (sParked != null && sParked.rereading) return;
        String info = LyricSource.infoFor(c);
        if (info == null || info.equals(sInfoSeen) || !LyricSource.usable(info)
                || sInfoTries >= MAX_INFO_TRIES) {
            return;
        }
        sInfoSeen = info;
        sInfoTries++;
        Xp.log(TAG + "the session is carrying a lyric " + key
                + " has not been read against; re-reading (" + sInfoTries + ")");
        CACHE.remove(key);
        // The lines already up are NOT cleared. A re-read is looking for something
        // better than what is on screen, and the first version emptied the view before
        // it knew whether there was any: a re-read that came back with nothing left the
        // song with no lyrics at all for the rest of its play. lookup() keeps them. With
        // none up it is the track's own lookup again, the running one superseded by it.
        lookup(key, c, !sLines.isEmpty());
        if (sParked != null) sParked.rereading = true;
    }

    /** How many times one song may be re-read because the session published something new. */
    private static final int MAX_INFO_TRIES = 3;
    /** The payload the lookup for sKey was started against, so the next one can be recognised. */
    private static String sInfoSeen;
    /** How much of MAX_INFO_TRIES this song has spent. */
    private static int sInfoTries;

    /**
     * The bridge has just published a song with lyrics (PR #103). Its answer often lands after the
     * lookup for the track has already gone past it and settled on a catalogue, or on nothing; the
     * lookup is run again, in its usual order, so the bridge's lyric takes its place. Not when
     * what is up already came from the player's own session, its file, or the bridge itself:
     * a re-run would only find that again.
     */
    static void onProviderLyrics() {
        Main.main().post(() -> {
            if (!sEnabled || sDemo || sKey.isEmpty() || sController == null) return;
            if (!sLines.isEmpty() && (sSource == LyricSource.SRC_LYRIC_INFO
                    || sSource == LyricSource.SRC_LOCAL || sSource == LyricSource.SRC_LYRICON)) {
                return;
            }
            CACHE.remove(sKey);
            lookup(sKey, sController, !sLines.isEmpty());
        });
    }

    /**
     * Starts the lookup for `key` and puts whatever it finds on screen.
     *
     * `keepCurrent` is the whole difference between the two callers. A track change has already
     * emptied the view, so anything found is an improvement on nothing and an empty answer costs
     * nothing to apply. A re-read is looking for something better than what is already up, and
     * an empty answer there must not be applied at all - the lines on screen are the best that
     * has been found for this song and there is nothing to replace them with.
     */
    private static void lookup(final String want, MediaController c, final boolean keepCurrent) {
        final int gen = ++sGen;
        sLoading = true;
        LyricSource.load(c, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why, int source) {
                if (gen != sGen || !want.equals(sKey) || sDemo) {
                    Xp.log(TAG + "lyrics for " + want + " arrived after the track changed");
                    return;
                }
                Parked parked = sParked;
                if (parked != null && parked.rereading) {
                    // The re-read the parked answer was waiting for. Whatever it found goes up in
                    // one change; if it found nothing, the parked answer does.
                    unpark();
                    if (lines.isEmpty()) {
                        Xp.log(TAG + "the session's lyric read as nothing (" + why
                                + "); putting up the parked answer");
                        settle(want, parked.lines, parked.why, parked.source);
                        return;
                    }
                    settle(want, lines, why, source);
                    return;
                }
                if (lines.isEmpty() && keepCurrent) {
                    sLoading = false;
                    Xp.log(TAG + "the re-read found nothing (" + why + "); keeping the "
                            + sLines.size() + " lines already up");
                    // Not setLines: the lines have not changed. The blur still has to be told,
                    // because sLoading was what was holding it across the lookup.
                    refresh();
                    rereadIfNewPayload(want, sController);
                    return;
                }
                if (!keepCurrent && source != LyricSource.SRC_LYRIC_INFO
                        && pkgOf(want).equals(sSessionPkg)) {
                    long left = sTrackAt + SESSION_WAIT_MS - SystemClock.uptimeMillis();
                    if (left > 0L) {
                        park(want, lines, why, source, left);
                        // It may be on the session already, having landed during the lookup.
                        rereadIfNewPayload(want, sController);
                        return;
                    }
                }
                settle(want, lines, why, source);
            }
        });
    }

    /** Puts a lookup's answer up: the one place lines found for `want` reach the screen. */
    private static void settle(String want, List<LyricLine> lines, String why, int source) {
        sLoading = false;
        sSource = source;
        // What the LAST lookup found, not what any lookup ever found.
        //
        // It has to fall as well as rise. Written once and kept, it said "a provider
        // module is working" for as long as the file lasted - so disabling the module
        // and restarting left the settings page still convinced, and the advice that
        // should have appeared never did.
        //
        // SRC_NONE is deliberately not an answer either way: finding nothing can mean
        // the network was down or the song simply has no lyrics anywhere, neither of
        // which says anything about the provider.
        //
        // Nor is SRC_LOCAL, for the opposite reason. The file's own lyric outranks the
        // session's, so a song that has one never reports what the session was carrying
        // - the module may have been working perfectly and simply not been needed.
        // Neither answer is available, so the last real one stands.
        if (source != LyricSource.SRC_NONE && source != LyricSource.SRC_LOCAL) {
            boolean fromSession = source == LyricSource.SRC_LYRIC_INFO;
            if (fromSession != sSawSessionLyric) {
                sSawSessionLyric = fromSession;
                ModuleState.saveState();
            }
        }
        if (source == LyricSource.SRC_LYRIC_INFO) sSessionPkg = pkgOf(want);
        if (!lines.isEmpty()) CACHE.put(want, new Cached(lines, source));
        setLines(lines, why);
        // A payload that turned up while this was looking. See rereadIfNewPayload().
        rereadIfNewPayload(want, sController);
    }

    /*
     * Waiting for the session's lyric instead of swapping to it.
     *
     * A player whose lyric comes through its own session publishes it a little after the track
     * changes - our Apple hook half a second later (measured 0.5-0.65s, 2026-09-30) - and a .lrc
     * beside the file answers in 50ms. Put up at once, the .lrc was on screen for half a second
     * and then replaced by the session's copy: other lines, other breaks, word timings where
     * there were none, which reads as the lyrics jumping. So when this player's last song came
     * from its session, an answer from anywhere else is held until the session's arrives, or
     * until SESSION_WAIT_MS after the track change, whichever is first. The page is held the way
     * any lookup holds it (sLoading), so there is no cover in between.
     *
     * A wait that runs out says the player stopped publishing - a song Apple has no lyric for,
     * the hook broken by an update - and the next song does not wait, until a session answer is
     * seen again.
     */

    /** The player whose last settled answer came from its session: its next one is expected. */
    private static String sSessionPkg;
    private static final long SESSION_WAIT_MS = 1200L;
    /** When sKey became the track; sTrackChangedAt is the blur's and is zeroed with it. */
    private static long sTrackAt;

    private static final class Parked {
        final String key;
        final List<LyricLine> lines;
        final String why;
        final int source;
        /** The session's payload is being read; its answer decides, not the timer. */
        boolean rereading;

        Parked(String key, List<LyricLine> lines, String why, int source) {
            this.key = key;
            this.lines = lines;
            this.why = why;
            this.source = source;
        }
    }

    private static Parked sParked;

    private static final Runnable RELEASE_PARKED = new Runnable() {
        @Override
        public void run() {
            Parked p = sParked;
            if (p == null || p.rereading) return;
            unpark();
            if (!p.key.equals(sKey) || sDemo) return;
            Xp.log(TAG + "no session lyric within " + SESSION_WAIT_MS + "ms; putting up "
                    + p.why + ", and not waiting for " + sSessionPkg + " again");
            sSessionPkg = null;
            settle(p.key, p.lines, p.why, p.source);
        }
    };

    private static void park(String key, List<LyricLine> lines, String why, int source,
                             long left) {
        sParked = new Parked(key, lines, why, source);
        Main.main().removeCallbacks(RELEASE_PARKED);
        Main.main().postDelayed(RELEASE_PARKED, left);
        Xp.log(TAG + "holding " + why + " up to " + left + "ms for the session's lyric");
    }

    private static void unpark() {
        sParked = null;
        Main.main().removeCallbacks(RELEASE_PARKED);
    }

    /** The player a key names: every key, the card's and lyricKey's alike, starts with it. */
    private static String pkgOf(String key) {
        int bar = key.indexOf('|');
        return bar < 0 ? key : key.substring(0, bar);
    }

    /**
     * Which song the lyrics belong to - deliberately without the title.
     *
     * Salt Player writes the line being sung INTO the title (for car and status bar lyrics) and
     * moves "artist - title" into the artist field. Keyed on the title, every line of every song
     * was a new track: the lines were thrown away and parsed again a line at a time, which on
     * screen was the lyrics blinking out and back (state log 2026-09-16 02:42). Artist, album
     * and duration hold still across a song under either convention.
     *
     * Except on 汽水 and QQ, which spell the artist two ways in one song - the singer, then
     * "歌名 — 歌手" or "歌名-歌手" while a line is in the title - so the artist is its singer here
     * (TrackName.singer). Read as published, that switch emptied the lyrics mid-song (#56).
     */
    private static String lyricKey(String cardKey, MediaController c) {
        String fallback = cardKey == null ? "" : cardKey;
        if (c == null || fallback.isEmpty()) return fallback;
        try {
            android.media.MediaMetadata md = c.getMetadata();
            if (md == null) return fallback;
            String album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            String artist = TrackName.singer(c.getPackageName(),
                    md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST), album);
            long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            if (artist == null && album == null && dur <= 0) return fallback;
            return c.getPackageName() + "|" + artist + "|" + album + "|" + dur;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static void onPlaybackState(PlaybackState s) {
        sState = s;
        sStateReadAt = SystemClock.uptimeMillis();
        LyricView v = sView;
        if (v != null) v.kick();
    }

    /**
     * Cover mode came on. Puts the view in the keyguard if the switch is on - in the keyguard's
     * foreground layer, or, with the HDR highlight on, in a window of its own.
     *
     * Two homes because the window is paid for in everything the view used to inherit for free
     * (see {@link LyricWindow}): the lock screen's alpha, visibility and zoom, and its z-order -
     * the PIN pad and the shade over the lock screen are drawn in the same window and used to be
     * above the lyrics. The HDR colour mode is the only thing that needs the window, so only the
     * switch that asks for it pays for it; with it off the view lives where it always did.
     * Flipping the switch moves the view: `lyrichdr` refreshes, and the refresh lands here.
     */
    public static void attach() {
        updateBlur();
        // wantsAttached(), not wantsWindow(): the view's life is the cover's, and whether the
        // lines are drawn is also the lock screen's. See wantsWindow().
        if (!wantsAttached()) return;
        final View anchor = Main.sContainer;
        if (anchor == null) return;
        try {
            LyricView v = hdr() ? inWindow(anchor) : inLayer(anchor);
            if (v == null) return;
            sView = v;
            v.kick();
            startTick();
            watch();
        } catch (Throwable t) {
            Xp.w(TAG + "attach failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * Retirement: the page comes out of wherever it lives, and the tick and the heartbeat stop.
     *
     * <p>Not setEnabled(false): that switch is the user's and is written to the state file, and
     * the next generation reads it again. The lines and their cache stay - they are text, and a
     * dry run puts the same page straight back up - so the rebuild does not have to go looking
     * for the lyric again.
     */
    public static void retire() {
        // A lookup still on its thread answers into this generation later; its gen check is what
        // drops that answer instead of putting the page back up. Not in a dry run: the same
        // generation comes straight back, and its lookup's answer is still the one it wants.
        if (!btm.m.os4.systemuihook.hypermusiccover.next.Gen.live()) sGen++;
        Main.main().removeCallbacks(TICK);
        Main.main().removeCallbacks(WATCH);
        // The display listener is Gen's and goes with the sweep; this latch is what would stop
        // the next generation from registering its own.
        sDisplayWatched = false;
        cancelStillWake();
        if (sDrawLock != null && sDrawLock.isHeld()) {
            try {
                sDrawLock.release();
            } catch (Throwable ignored) {
            }
        }
        LyricView v = sView;
        sView = null;
        if (v != null) {
            if (sHolding) {
                sHolding = false;
                v.setKeepScreenOn(false);
            }
            watchProximity(false);
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        }
        // With no page up the proximity listener is still Gen's, and the sweep takes it; its
        // latch has to go with it.
        sProximity = null;
        sCovered = false;
        // The window last: it is a window of its own when the HDR switch is on, and it is not
        // reachable through the keyguard's tree.
        LyricWindow.remove();
        sCard = null;
        // The blur is a value in the wallpaper process, not a view that goes with the page. The
        // next generation asks for it again from refresh().
        try {
            long blur = setBlurSent(false);
            CoverPush.sendToWallpaper("lyricblur", false, blur >>> 1);
            CoverPush.updateVideoCoverBlur(false);
        } catch (Throwable t) {
            Xp.w(TAG + "blur release failed: " + t);
        }
    }

    /** HDR on: the lyrics' own window, so the colour mode is theirs and not the media card's. */
    private static LyricView inWindow(View anchor) {
        LyricView old = sView;
        if (old != null && !LyricWindow.owns(old)) unhost(old);
        return LyricWindow.add(anchor);
    }

    /** HDR off: the keyguard's foreground layer, beside the clock and the card. */
    private static LyricView inLayer(View anchor) {
        LyricView old = sView;
        if (old != null && LyricWindow.owns(old)) unhost(old);
        // The switch is off: a window parked for the next entry has none coming.
        LyricWindow.remove();
        int id = anchor.getResources().getIdentifier(
                "keyguard_foreground_layer", "id", "com.android.systemui");
        View layer = id == 0 ? null : anchor.getRootView().findViewById(id);
        if (!(layer instanceof ViewGroup)) {
            Xp.log(TAG + "keyguard_foreground_layer not found");
            return null;
        }
        if (sView == null || sView.getContext() != anchor.getContext()) {
            sView = new LyricView(anchor.getContext());
        }
        LyricView v = sView;
        if (v.getParent() != layer) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            ((ViewGroup) layer).addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Gen.ownView((ViewGroup) layer, v);
            Xp.log(TAG + "view attached, " + sLines.size() + " lines");
        }
        return v;
    }

    /**
     * Takes a view out of whichever home it is in, and lets go of the screen if it was the one
     * holding it - the flag is the view's, so the view that replaces it has to ask again, and the
     * tick does.
     */
    private static void unhost(LyricView v) {
        if (v == null) return;
        if (sHolding) {
            sHolding = false;
            v.setKeepScreenOn(false);
        }
        // With no view the tick stops and would never let the sensor go.
        watchProximity(false);
        if (LyricWindow.owns(v)) {
            // Parked, not removed: the next entry takes the same window back without adding one.
            LyricWindow.park();
            if (sView == v) sView = null;
        } else if (v.getParent() instanceof ViewGroup) {
            // The layer's view is kept and put back by the next attach, as it always was.
            ((ViewGroup) v.getParent()).removeView(v);
            Xp.log(TAG + "view detached");
        }
    }

    /** Called by the view itself once it has faded out with no reason to stay. */
    static void detach(LyricView v) {
        if (wantsAttached()) return;
        unhost(v);
        Main.main().removeCallbacks(TICK);
        Main.main().removeCallbacks(WATCH);
    }

    public static void refresh() {
        // 保留壁纸 hangs the clock on the lyric page, and this is where the page comes and goes.
        Main.syncCoverHold(Main.screenOnCached());
        updateBlur();
        updateHdr();
        if (wantsAttached()) attach();
        LyricView v = sView;
        if (v != null) v.kick();
        // A new song or a switch in the still AOD: nothing is shown until the display is let up.
        if (sStill) drawStill();
        CoverCardLayer.refresh();
    }

    /** The app's switch. The setting: it is written to the state file and the app reads it back. */
    public static void setEnabled(boolean on, String key, MediaController c) {
        sEnabled = on;
        // The switch is the master, so it takes the tap's answer with it. Switching the lyrics
        // off and on again brings them back; without this, turning them on while a previous tap
        // had hidden them would leave the lock screen on the cover with the switch saying on.
        sTapHidden = false;
        Xp.log(TAG + "lyrics " + (on ? "on" : "off"));
        show(on, key, c, "switched off");
    }

    /**
     * The lock screen's two-finger tap: the cover and the lyrics, swapped until the next one.
     * The app's switch is not touched - a tap that was not meant would otherwise read as the
     * setting turning itself off - but the page it picked is saved, see sTapHidden.
     *
     * With the switch off the tap does nothing at all rather than turning the lyrics on: that
     * would be the lock screen writing the app's setting, which is what it no longer does.
     */
    public static void toggleByTap(String key, MediaController c) {
        if (!sEnabled) return;
        sTapHidden = !sTapHidden;
        Xp.log(TAG + "two-finger tap: lyrics " + (sTapHidden ? "hidden" : "shown"));
        show(!sTapHidden, key, c, "hidden by the two-finger tap");
        ModuleState.saveState();
    }

    /**
     * The show/hide half, shared by the switch and the tap; they differ only in whether the
     * answer is written down. Off is where the two part company in the log: the reason says
     * which of them did it and one of them is a setting.
     */
    private static void show(boolean on, String key, MediaController c, String why) {
        if (on) {
            sKey = "";
            onTrack(key, c);
        } else if (!sDemo) {
            sGen++;
            unpark();
            sLoading = false;
            setLines(Collections.<LyricLine>emptyList(), why);
        }
        refresh();
    }

    /**
     * Plays a lyric file by database id on its own clock, whatever is playing - so the look can
     * be judged without a player that publishes an id, or a song the database has.
     */
    public static void demo(String id, boolean apple) {
        sDemo = true;
        unpark();
        final int gen = ++sGen;
        sLoading = true;
        sKey = "demo:" + id;
        sTrackChangedAt = SystemClock.uptimeMillis();
        sBlurVideoReloaded = false;
        scheduleBlurKick(BLUR_ENTER_DELAY_MS + 16L);
        setLines(Collections.<LyricLine>emptyList(), "demo loading");
        LyricSource.loadById(id, apple, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why, int source) {
                if (gen != sGen || !sDemo) return;
                sLoading = false;
                sDemoT0 = SystemClock.uptimeMillis();
                setLines(lines, "demo " + why);
                refresh();
            }
        });
        refresh();
    }

    public static void endDemo(String key, MediaController c) {
        if (!sDemo) return;
        sDemo = false;
        sKey = "";
        onTrack(key, c);
        refresh();
    }

    /**
     * The route this track's lyric came from, as the settings page names it.
     *
     * [srcName]'s words - "session", "local", "qq" and the rest - or "none" when there is no
     * lyric to speak of. A string rather than the SRC_ constant because the reader is the app in
     * another process, and it does one thing with it: puts it in a row that says where the words
     * on the lock screen came from.
     */
    public static String sourceName() {
        return sLines.isEmpty() ? "none" : srcName(sSource);
    }

    /** The SRC_ constant as something readable in a broadcast result. */
    private static String srcName(int source) {
        switch (source) {
            case LyricSource.SRC_LYRIC_INFO:
                return "session";
            case LyricSource.SRC_LOCAL:
                return "local";
            case LyricSource.SRC_LYRICON:
                return "lyricon";
            case LyricSource.SRC_DATABASE:
                return "amll";
            case LyricSource.SRC_NETEASE:
                return "netease";
            case LyricSource.SRC_KUGOU:
                return "kugou";
            case LyricSource.SRC_LRCLIB:
                return "lrclib";
            case LyricSource.SRC_QQ:
                return "qq";
            case LyricSource.SRC_KUWO:
                return "kuwo";
            case LyricSource.SRC_HUB:
                return "ttmlhub";
            default:
                return "none";
        }
    }

    public static String describe() {
        LyricView v = sView;
        View c = Main.sContainer;
        View card = card();
        return "enabled=" + sEnabled + " tap=" + (sTapHidden ? "hidden" : "shown")
                + " demo=" + sDemo + " key=" + sKey + " lines=" + sLines.size()
                + " has=" + hasLyrics() + " loading=" + sLoading
                + " (" + sWhy + ") src=" + srcName(sSource)
                + " sessionHasLyric=" + LyricSource.hasLyricInfo(sController)
                + " pos=" + positionMs() + " playing=" + playing()
                + " cover=" + Main.coverModeOn() + " screen=" + Main.screenOnCached()
                // What the wallpaper process was last told, and when: the other side prints the
                // answer it holds and when it was decided, so the two readings side by side say
                // whether a switch was lost on the way rather than guessing at the cover.
                + " blur=" + (blurWanted() ? "on" : "off") + "@" + (sBlurSent >>> 1)
                + " phase=" + ClockCollapse.phase()
                + " inAod=" + inHeldAod() + " held=" + ClockCollapse.aodHeld()
                + " still=" + sStill + " display=" + displayState()
                + " stillLog=[" + sStillLog + "]"
                + " cardP=" + MediaCard.cardProgress()
                + " container=" + (c == null ? "none" : c.getAlpha() + "/shown=" + c.isShown())
                + " card=" + (card == null ? "none" : "shown=" + card.isShown())
                // Where the band's lower edge came from: live is the card's own top this frame,
                // recorded is that edge off the last card rectangle on record (what a hidden card
                // leaves behind), default is the fraction. All three are the same edge, so a card
                // that is hidden shows up here as which route placed the lyrics rather than as a
                // missing reading.
                + " bandSrc=" + bandSource()
                + " bandMiss=[" + bandMisses() + " ]"
                + " clockBottom=" + ClockCollapse.contentBottomOnScreen()
                + " ink=" + ClockCollapse.inkBottomOnScreen()
                + " shown=" + wantsShown() + " blurSettled=" + blurSettled() + " blurShown=" + sBlurShown + "@" + sBlurOnSeq
                + " blurElapsed=" + (sBlurStartedAt > 0 ? (SystemClock.uptimeMillis() - sBlurStartedAt) + "ms" : "none")
                + " tick=" + sTicking
                + " hooks=" + hooks()
                + " " + LyricWindow.describe()
                + " view={" + (v == null ? "none" : v.describe()) + "}";
    }

    /**
     * The wallpaper process restarted, or may have: tell it again.
     *
     * Told again rather than forgotten: the answer is still the answer, and this only skips the
     * "already sent that" short circuit below. What it must not do is make the answer look older
     * than the last one it gave - the other side drops those - so the clock it is timed on runs
     * on from here like any other decision.
     */
    public static void resendBlur() {
        updateBlur(true);
    }

    /**
     * Frosts the cover while there are lyrics on it, so they read over any artwork.
     *
     * Held through a track change's lookup rather than dropped and re-applied, which would pulse
     * the cover sharp and back on every song. Nothing is sent while cover mode is off: leaving
     * it fades the cover out whole, and the wallpaper process clears the blur with it. The
     * decision itself is written down either way, because a cover push carries it.
     */
    private static void updateBlur() {
        updateBlur(false);
    }

    /** again = tell the other side even when the answer has not changed. */
    private static void updateBlur(boolean again) {
        if (!Main.coverModeOn()) {
            setBlurSent(false);
            return;
        }
        boolean on = wanted();
        boolean want = on && (!sLines.isEmpty() || (sLoading && blurWanted()));
        long cur = sBlurSent;
        boolean wasOn = (cur & 1L) != 0L;
        if (!again && cur != 0L && wasOn == want) return;
        boolean turningOn = want && !wasOn;
        long state = setBlurSent(want);
        // Video wallpaper needs a moment to reload and cross-fade before the band lands over it;
        // arm the settle timer when blur turns on, and clear it when it turns off. blurSettled()
        // reads these to hold wantsShown() back through the transition.
        if (turningOn) {
            sBlurStartedAt = SystemClock.uptimeMillis();
            sBlurVideoReloaded = false;
            sBlurOnSeq = state >>> 1;
            sBlurShown = false;
            scheduleBlurKick((Main.sVideoWallpaper ? BLUR_ENTER_DELAY_MS : STILL_BLUR_WAIT_MS) + 16L);
        } else if (!want) {
            Main.main().removeCallbacks(BLUR_KICK);
            sBlurStartedAt = 0L;
            sTrackChangedAt = 0L;
            sBlurVideoReloaded = false;
        }
        CoverPush.sendToWallpaper("lyricblur", want, state >>> 1);
        CoverPush.updateVideoCoverBlur(want);
        Xp.log(TAG + "cover blur " + (want ? "on" : "off") + (again ? " (told again)" : ""));
    }

    // ------------------------------------------------------------------ internals

    private static void setLines(List<LyricLine> lines, String why) {
        sLines = lines == null ? Collections.<LyricLine>emptyList() : lines;
        // Only a settled answer: the empty set a track change puts up while it looks is not one.
        if (!sLoading) sHadLyrics = !sLines.isEmpty();
        sVersion++;
        sWhy = why;
        Xp.log(TAG + sLines.size() + " lines: " + why);
        refresh();
        Main.refreshLyricButton();
    }

    /** The session's position is re-read now and then, not per frame: it is a binder call. */
    private static void readState(boolean force) {
        MediaController c = sController;
        if (c == null || sDemo) return;
        long now = SystemClock.uptimeMillis();
        if (!force && now - sStateReadAt < 1000L) return;
        sStateReadAt = now;
        try {
            sState = c.getPlaybackState();
        } catch (Throwable ignored) {
        }
    }

    private static boolean sTicking;

    /**
     * Also called by the view whenever it is attached to a window: a keyguard rebuilt around the
     * view re-attaches it without going through attach(), and the tick stops when it finds the
     * view detached - with it stopped, a line-timed song never moves on to its next line.
     */
    static void startTick() {
        Main.main().removeCallbacks(TICK);
        Main.main().post(TICK);
    }

    /**
     * Wakes the view at the next line start, or twice a second, whichever is sooner - the view
     * asks for no frames of its own while nothing moves, so this is what moves the focus on a
     * line-timed file.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            LyricView v = sView;
            sTicking = v != null && v.isAttachedToWindow();
            if (!sTicking) {
                watchProximity(false);
                return;
            }
            long delay = 1000L;
            holdScreen(v);
            updateHdr();
            updateStill();
            if (sStill) {
                // The alarm draws in the still mode, and only the alarm: a frame from here is
                // drawn with the display held down, where it never reaches the panel - and
                // re-arming from here moved the alarm on to the next line before it could fire,
                // so every line was drawn that way (measured: one draw lock in a whole song).
                if (!sStillWakeSet && !sLines.isEmpty()) {
                    readState(false);
                    scheduleStillWake();
                }
            } else if ((Main.screenOnCached() || inHeldAod()) && !sLines.isEmpty()) {
                readState(false);
                v.kick();
                if (playing()) {
                    delay = 500L;
                    int pos = positionMs();
                    // The stack moves a second ahead of each line's first word (LyricView.LEAD_MS).
                    int next = nextStartAfter(pos + 1000);
                    if (next >= 0) delay = Math.max(16L, Math.min(delay, next - 1000L - pos + 8L));
                }
            }
            Main.main().postDelayed(this, delay);
        }
    };

    // ------------------------------------------------------------------ the AOD's still mode

    /**
     * The full-screen AOD has put the panel into its low-power still mode, and the lyrics are
     * drawn the way that mode allows: one settled picture per line, cut to, nothing animated.
     *
     * How the AOD refreshes, read off com.miui.aod (DozeMachine.State.screenState and
     * DozeScreenState): the doze starts with the display ON for about 6s, then the plugin asks for
     * Display.STATE_DOZE_SUSPEND. In that state the panel holds its last frame on its own and the
     * CPU is free to sleep - which is what froze the lyrics: the tick is a Handler and the view's
     * frames are vsync callbacks, and neither runs again until something wakes the phone. The OEM's
     * own clock gets through the same way this does: an exact wake-up alarm at the moment the
     * picture has to change, and a DRAW_WAKE_LOCK held over the redraw, which the power manager
     * answers by lifting the display from DOZE_SUSPEND to DOZE until it is let go.
     *
     * Latched: the display reads DOZE while our own draw lock is held, and that is not the AOD
     * leaving its still mode. Cleared only when the doze ends.
     */
    private static boolean sStill;

    /**
     * Measurement only (op stilloff): the still mode is entered as usual but never redraws, so the
     * lyrics hold still exactly as they did before it existed. Comparing the battery current with
     * and without it is what prices the per-line redraw.
     */
    public static volatile boolean sStillOff;

    /**
     * The redraw is driven by the display, not by a clock: take the lock, wait until the display
     * is actually in DOZE, draw one frame, wait for that frame to be committed, give the panel
     * STILL_PANEL_MS to show it, let go. Measured 2026-09-24: DOZE arrives 10-54ms after the lock
     * and a frame is on the panel 10-20ms after it is ready. The first version held the display up
     * for a fixed 300ms and drew three frames in it - one before the display was up, one
     * redundant - and cost about 60mA over the same AOD with the lyrics standing still.
     */
    private static final long STILL_PANEL_MS = 40L;
    /** Drawn anyway if DOZE has not been seen by then; not counted as the frame that was shown. */
    private static final long STILL_UP_FALLBACK_MS = 100L;
    /** The lock's own timeout, for whatever step fails to happen. */
    private static final long STILL_LOCK_MAX_MS = 400L;

    /** The display has been seen in DOZE since the current lock was taken. */
    private static boolean sStillUp;
    /** A frame was drawn after that, and releasing waits only on its commit. */
    private static boolean sStillDrawnUp;

    /** Late by this much on purpose, so the frame lands after the move rather than before it. */
    private static final long STILL_WAKE_SLACK_MS = 30L;

    /** PowerManager.DRAW_WAKE_LOCK, which the SDK hides. SystemUI holds DEVICE_POWER. */
    private static final int DRAW_WAKE_LOCK = 0x80;

    private static android.os.PowerManager.WakeLock sDrawLock;
    private static boolean sStillWakeSet;
    private static boolean sDisplayWatched;

    /** The lyric view's display state, for the probe: 2 ON, 3 DOZE, 4 DOZE_SUSPEND. */
    private static int displayState() {
        LyricView v = sView;
        android.view.Display d = v == null ? null : v.getDisplay();
        return d == null ? -1 : d.getState();
    }

    /**
     * The still mode's recent events, for the probe: each draw lock, each display state the
     * listener saw and each frame the view drew, in ms after the lock that preceded it. What it
     * answers is how long the lock has to be held for a frame to reach the panel.
     */
    private static final StringBuilder sStillLog = new StringBuilder();
    private static long sStillLockAt;

    static void noteStill(String what) {
        if (!sStill) return;
        long now = SystemClock.uptimeMillis();
        if ("acq".equals(what)) {
            sStillLockAt = now;
            sStillLog.append(" |");
        }
        sStillLog.append(' ').append(what).append('@').append(now - sStillLockAt);
        if (sStillLog.length() > 1500) sStillLog.delete(0, sStillLog.length() - 1200);
    }

    /**
     * Whether the AOD is showing at all. It goes dark on its own while the doze goes on (the
     * plugin sets Display.STATE_OFF), and the still mode stays latched through that, since the
     * doze has not ended; waking the phone per line to draw onto a display that is off
     * was measured going on every few seconds (2026-09-24). The display listener picks it up
     * again when the AOD comes back.
     */
    private static boolean stillVisible() {
        int s = displayState();
        return s == android.view.Display.STATE_DOZE || s == android.view.Display.STATE_DOZE_SUSPEND;
    }

    /** Whether the lyrics are in the AOD's still mode. The view settles instead of animating. */
    static boolean still() {
        return sStill;
    }

    /**
     * The next move, two ways at once. The alarm alone was not enough: the alarm manager holds
     * every app uid's alarms at least min_futurity (5s here) out, SystemUI's included, so a fast
     * song moved on only every 5s. The handler is exact and runs whenever the CPU is up - which
     * it is for as long as music plays, measured - and the alarm is the floor for a CPU that
     * went to sleep anyway. Whichever comes first draws, and sets both again.
     */
    private static final android.app.AlarmManager.OnAlarmListener STILL_WAKE =
            new android.app.AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    onStillWake("al");
                }
            };

    private static final Runnable STILL_WAKE_NOW = new Runnable() {
        @Override
        public void run() {
            onStillWake("h");
        }
    };

    /** When the wake that is set is due, so the log can say how late it came and by which way. */
    private static long sStillDueAt;

    private static void onStillWake(String by) {
        noteStill(by + "+" + (SystemClock.uptimeMillis() - sStillDueAt));
        cancelStillWake();
        updateStill();
        if (sStill) drawStill();
    }

    /**
     * The display changing state is when the still mode starts, and the picture it holds from
     * then on is whatever the last frame happened to be - often a line half way through its
     * scroll. Caught here so a settled one replaces it at once, not at the next line.
     */
    private static void watchDisplay() {
        if (sDisplayWatched || Main.sAppCtx == null) return;
        try {
            android.hardware.display.DisplayManager dm = Main.sAppCtx.getSystemService(
                    android.hardware.display.DisplayManager.class);
            Gen.displayListener(dm, new android.hardware.display.DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) {
                }

                @Override
                public void onDisplayRemoved(int id) {
                }

                @Override
                public void onDisplayChanged(int id) {
                    LyricView v = sView;
                    android.view.Display d = v == null ? null : v.getDisplay();
                    if (d == null || d.getDisplayId() != id) return;
                    noteStill("s" + d.getState());
                    if (d.getState() == android.view.Display.STATE_DOZE && sDrawLock != null
                            && sDrawLock.isHeld() && !sStillUp) {
                        sStillUp = true;
                        drawStillFrame();
                    }
                    boolean was = sStill;
                    updateStill();
                    // Entering, and coming back after the AOD went dark: either way nothing is
                    // set to wake it, and the picture on the panel is whatever was there last.
                    // Not on every 3/4 flip - our own draw lock makes those, with a wake set.
                    if (sStill && (!was || (!sStillWakeSet && playing() && stillVisible()))) {
                        drawStill();
                    }
                }
            }, Main.main());
            sDisplayWatched = true;
        } catch (Throwable t) {
            Xp.w(TAG + "display listener failed: " + t);
        }
    }

    /** Enters the still mode when the display has gone to DOZE_SUSPEND, leaves it with the doze. */
    private static void updateStill() {
        watchDisplay();
        if (!inHeldAod()) {
            if (sStill) {
                sStill = false;
                cancelStillWake();
                Xp.log(TAG + "AOD still mode off");
                LyricView v = sView;
                if (v != null) v.kick();
            }
            return;
        }
        if (sStill) return;
        LyricView v = sView;
        android.view.Display d = v == null ? null : v.getDisplay();
        if (d != null && d.getState() == android.view.Display.STATE_DOZE_SUSPEND) {
            sStill = true;
            Xp.log(TAG + "AOD still mode on");
        }
    }

    /** One settled frame, with the display let up long enough to show it; then the next wake. */
    private static void drawStill() {
        if (sStillOff || !stillVisible()) return;
        LyricView v = sView;
        if (v == null || !v.isAttachedToWindow()) return;
        try {
            if (sDrawLock == null) {
                android.os.PowerManager pm = Main.sAppCtx.getSystemService(
                        android.os.PowerManager.class);
                sDrawLock = pm.newWakeLock(DRAW_WAKE_LOCK, "MusicCover:lyricDraw");
                sDrawLock.setReferenceCounted(false);
            }
            sDrawLock.acquire(STILL_LOCK_MAX_MS);
            noteStill("acq");
        } catch (Throwable t) {
            Xp.w(TAG + "draw wake lock failed: " + t);
        }
        readState(true);
        sStillDrawnUp = false;
        Main.main().removeCallbacks(STILL_UP_FALLBACK);
        Main.main().removeCallbacks(STILL_RELEASE);
        // Already up - the OEM's own lock can be holding it there - or not yet, in which case the
        // display listener draws the moment it is.
        sStillUp = displayState() == android.view.Display.STATE_DOZE;
        if (sStillUp) {
            drawStillFrame();
        } else {
            Main.main().postDelayed(STILL_UP_FALLBACK, STILL_UP_FALLBACK_MS);
        }
        scheduleStillWake();
    }

    /**
     * The one frame, and once it is committed the release. Only a frame drawn with the display up
     * releases the lock early; the fallback's frame may never have reached the panel, so after it
     * the lock waits for the display to come up and draw again, or runs out.
     */
    private static void drawStillFrame() {
        LyricView v = sView;
        if (v == null || !v.isAttachedToWindow() || sStillDrawnUp) return;
        final boolean up = sStillUp;
        sStillDrawnUp = up;
        Main.main().removeCallbacks(STILL_UP_FALLBACK);
        v.getViewTreeObserver().registerFrameCommitCallback(new Runnable() {
            @Override
            public void run() {
                noteStill(up ? "commit" : "commit-early");
                if (up) Main.main().postDelayed(STILL_RELEASE, STILL_PANEL_MS);
            }
        });
        v.redraw();
    }

    private static final Runnable STILL_UP_FALLBACK = new Runnable() {
        @Override
        public void run() {
            noteStill("fallback");
            drawStillFrame();
        }
    };

    private static final Runnable STILL_RELEASE = new Runnable() {
        @Override
        public void run() {
            android.os.PowerManager.WakeLock l = sDrawLock;
            if (l != null && l.isHeld()) {
                l.release();
                noteStill("rel");
            }
        }
    };

    /** A wake at the next moment the stack moves, or none if it will not. See STILL_WAKE. */
    private static void scheduleStillWake() {
        LyricView v = sView;
        if (!sStill || sStillOff || v == null || sLines.isEmpty() || !playing()
                || !stillVisible()) {
            cancelStillWake();
            return;
        }
        int pos = positionMs();
        long at = v.nextMoveAfter(pos);
        if (at < 0) {
            cancelStillWake();
            return;
        }
        long delay = at - pos + STILL_WAKE_SLACK_MS;
        Main.main().removeCallbacks(STILL_WAKE_NOW);
        Main.main().postDelayed(STILL_WAKE_NOW, delay);
        sStillDueAt = SystemClock.uptimeMillis() + delay;
        sStillWakeSet = true;
        try {
            android.app.AlarmManager am = Main.sAppCtx.getSystemService(
                    android.app.AlarmManager.class);
            // Replaces the one already set: the same listener is one alarm.
            am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay, "MusicCover:lyric", STILL_WAKE,
                    Main.main());
            sStillWakeSet = true;
        } catch (Throwable t) {
            Xp.w(TAG + "still wake failed: " + t);
        }
    }

    private static void cancelStillWake() {
        if (!sStillWakeSet) return;
        sStillWakeSet = false;
        Main.main().removeCallbacks(STILL_WAKE_NOW);
        try {
            Main.sAppCtx.getSystemService(android.app.AlarmManager.class).cancel(STILL_WAKE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps the lock screen lit while the lyrics are playing, and lets it sleep again once they
     * are not - paused, hidden, switched off.
     *
     * keepScreenOn on the view is the whole mechanism. The keyguard's auto-sleep is not a timer of
     * SystemUI's own: NotificationShadeWindowControllerImpl.apply() sets the shade window's
     * userActivityTimeout to 10s on the lock screen (5s in the editor), and nothing else in
     * SystemUI puts the phone to sleep on a clock. The view's flag becomes FLAG_KEEP_SCREEN_ON on
     * that same window, for which the window manager holds a screen wake lock - and a held wake
     * lock outranks a user activity timeout. Nothing is faked as a touch.
     */
    private static void holdScreen(LyricView v) {
        // NOT in a doze, and this is the one that has to be got right: setKeepScreenOn on this
        // view is the whole mechanism above, and the view is in the SHADE window - so asking for
        // it while the display is dozing takes a screen wake lock and pulls the phone out of the
        // AOD at full brightness. Called from the tick, which never stops, so this would have
        // fired within a second of the screen going off.
        boolean asked = sKeepOn && wantsShown() && !inHeldAod()
                && !sLines.isEmpty() && playing();
        // Not in a pocket or face down: with nothing to stop it, a song playing kept the screen
        // lit and the lyrics drawing at 60Hz wherever the phone was put, for as long as it
        // played. Covered, the lock screen's own 10s timeout takes it again.
        watchProximity(asked);
        boolean want = asked && !sCovered;
        if (want == sHolding) return;
        sHolding = want;
        v.setKeepScreenOn(want);
        Xp.log(TAG + (want ? "holding the screen on" : "screen may sleep again"));
    }

    private static android.hardware.SensorEventListener sProximity;
    /** The proximity sensor reads near: the phone is in a pocket or face down. */
    private static boolean sCovered;

    /** Listens only while the screen is being held, so a sleeping phone keeps no sensor on. */
    private static void watchProximity(boolean on) {
        if (on == (sProximity != null)) return;
        android.hardware.SensorManager sm = Main.sAppCtx == null ? null
                : Main.sAppCtx.getSystemService(android.hardware.SensorManager.class);
        if (sm == null) return;
        if (!on) {
            Gen.unsensorListener(sm, sProximity);
            sProximity = null;
            sCovered = false;
            return;
        }
        final android.hardware.Sensor sensor =
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY);
        if (sensor == null) return;
        sProximity = new android.hardware.SensorEventListener() {
            @Override
            public void onSensorChanged(android.hardware.SensorEvent e) {
                // As the power manager reads it: near is anything short of the range, up to 5cm.
                float d = e.values[0];
                boolean near = d >= 0f && d < Math.min(sensor.getMaximumRange(), 5f);
                if (near == sCovered) return;
                sCovered = near;
                Xp.log(TAG + "proximity " + (near ? "covered" : "clear"));
                LyricView v = sView;
                if (v != null) holdScreen(v);
            }

            @Override
            public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {
            }
        };
        Gen.sensorListener(sm, sProximity, sensor,
                android.hardware.SensorManager.SENSOR_DELAY_NORMAL, Main.main());
    }

    /** Whether the singing words should be drawn in HDR right now. */
    static boolean hdrWanted() {
        // Never in a doze: the window would go bright and ask the display for headroom, which is
        // the opposite of what a display that has just dimmed itself wants. The glow that arms
        // this needs the lyrics on screen, so without this the AOD would ask for HDR too.
        return hdr() && sGlowing && wantsShown() && !inHeldAod() && !sLines.isEmpty();
    }

    private static boolean sGlowing;
    private static long sGlowEndAt;

    /**
     * The view says whether a held note is glowing. The window only goes HDR for those; it is
     * let go a moment after the last one, so two notes close together do not flip it twice.
     */
    static void setGlowing(boolean glowing) {
        long now = SystemClock.uptimeMillis();
        if (glowing) {
            sGlowEndAt = 0L;
            if (!sGlowing) {
                sGlowing = true;
                updateHdr();
            }
        } else if (sGlowing) {
            if (sGlowEndAt == 0L) sGlowEndAt = now;
            if (now - sGlowEndAt >= 800L) {
                sGlowing = false;
                sGlowEndAt = 0L;
                updateHdr();
            }
        }
    }

    /**
     * Asks the lyrics' own window for HDR, or for plain SDR again.
     *
     * This used to be written into the lock screen window's pending attributes from inside the
     * OEM's own apply, which is exactly what made the media card brighten: the colour mode is the
     * whole window's and the card is in it. Now it is one window's own layout params, and that
     * window holds nothing but the lyrics - see {@link LyricWindow}. The dedupe lives there too.
     */
    static void updateHdr() {
        LyricWindow.setHdr(hdrWanted());
    }

    /** Every string in the session's metadata, and lyricInfo written to a file whole. */
    // "lyricInfo" is the players' own key, not a framework one - see LyricSource.lyricInfoOf.
    @android.annotation.SuppressLint("WrongConstant")
    public static String dumpMetadata(android.content.Context ctx, MediaController c) {
        if (c == null) return "no session";
        StringBuilder sb = new StringBuilder(c.getPackageName());
        try {
            android.media.MediaMetadata md = c.getMetadata();
            if (md == null) return sb.append(" no metadata").toString();
            for (String k : md.keySet()) {
                CharSequence v = md.getText(k);
                sb.append(" | ").append(k).append('=');
                if (v == null) {
                    sb.append("(non-text)");
                } else {
                    String t = v.toString();
                    sb.append(t.length() > 80 ? t.substring(0, 80) + "...(" + t.length() + ")" : t);
                }
            }
            String info = md.getString("lyricInfo");
            if (info != null) {
                java.io.File f = new java.io.File(ctx.getFilesDir(), "hyperchanger_hmc_next_lyricinfo.json");
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                out.write(info.getBytes("UTF-8"));
                out.close();
                f.setReadable(true, false);
                sb.append(" | written ").append(f.getAbsolutePath());
            }
        } catch (Throwable t) {
            sb.append(" | failed: ").append(t);
        }
        return sb.toString();
    }

    private static int nextStartAfter(int pos) {
        List<LyricLine> l = sLines;
        int lo = 0, hi = l.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).start > pos) {
                best = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return best < 0 ? -1 : l.get(best).start;
    }
}
