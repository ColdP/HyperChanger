package btm.m.os4.systemuihook.hypermusiccover;

import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import btm.m.os4.systemuihook.LockscreenMediaPresentationBridge;

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
final class LockLyrics {
    private LockLyrics() {
    }

    private static final String TAG = "[MCLyric] ";

    static volatile boolean verbose;

    /** The user's switch. Off by default: it fetches from the network inside SystemUI. */
    static volatile boolean sEnabled;

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
    static volatile boolean sKeepOn;
    private static boolean sHolding;

    /**
     * The third switch: a held note's glow brighter than white on an HDR screen. Off by default:
     * the HDR colour mode is the whole shade window's, and with it on the media card's material
     * changes colour too (reported 2026-09-16). Doing it without that needs its own window.
     */
    static volatile boolean sHdr = false;
    /** How far above SDR white the window may go; the text asks for less than this. */
    private static final float HDR_HEADROOM = 4f;
    private static Object sShadeWindow;
    private static boolean sHdrApplied;
    private static boolean sModeOwned;
    private static int sOrigColorMode;
    private static float sOrigHeadroom;

    /** A lookup is in the air; the blur is held rather than dropped while it is. */
    private static boolean sLoading;
    /** What the wallpaper process was last told about the blur. Null = unknown. */
    private static Boolean sBlurSent;

    private static boolean sDemo;
    private static long sDemoT0;

    private static View sCard;
    private static long sCardLookAt;

    /** Songs recently shown, so skipping back and forth does not go to the network each time. */
    private static final Map<String, List<LyricLine>> CACHE =
            new LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<LyricLine>> e) {
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

    /** Whether the view belongs in the keyguard right now. */
    static boolean wantsAttached() {
        return (sEnabled || sDemo) && Main.coverModeOn();
    }

    /**
     * Whether it should be visible: attached, lit, and the clock settled small.
     *
     * Not during ENTER. The clock is still full size for most of the entry, and a recording
     * (2026-09-16 02:23) showed the lyrics arriving on top of it - on a toggle and on a wake
     * alike. They fade in once the clock has landed instead.
     */
    static boolean wantsShown() {
        if (!wantsAttached() || !Main.screenOnCached()) return false;
        return ClockCollapse.phase() == ClockCollapse.Phase.ON;
    }

    /** The media card, looked up again only when the one we hold has left the window. */
    static View card() {
        View c = sCard;
        if (c != null && c.isAttachedToWindow()) return c;
        long now = SystemClock.uptimeMillis();
        if (now - sCardLookAt < 500L) return null;
        sCardLookAt = now;
        sCard = Main.findSysuiView("mi_media_controls");
        return sCard;
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
    static void onTrack(String key, MediaController c) {
        key = lyricKey(key, c);
        sController = c;
        readState(true);
        if (sDemo) {
            if (verbose) Xp.log(TAG + "demo is held, not looking " + key + " up");
            return;
        }
        if (key.equals(sKey)) return;
        sKey = key;
        // Set before the lines are emptied, so the blur is held across the lookup instead of
        // being dropped by the empty set and put back when the answer lands.
        sLoading = sEnabled && !key.isEmpty() && !CACHE.containsKey(key);
        setLines(Collections.<LyricLine>emptyList(), "track changed");
        if (!sEnabled || key.isEmpty()) return;
        List<LyricLine> hit = CACHE.get(key);
        if (hit != null) {
            setLines(hit, "cached");
            return;
        }
        final String want = key;
        final int gen = ++sGen;
        sLoading = true;
        LyricSource.load(c, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why) {
                if (gen != sGen || !want.equals(sKey) || sDemo) {
                    Xp.log(TAG + "lyrics for " + want + " arrived after the track changed");
                    return;
                }
                sLoading = false;
                if (!lines.isEmpty()) CACHE.put(want, lines);
                setLines(lines, why);
            }
        });
    }

    /**
     * Which song the lyrics belong to - deliberately without the title.
     *
     * Salt Player writes the line being sung INTO the title (for car and status bar lyrics) and
     * moves "artist - title" into the artist field. Keyed on the title, every line of every song
     * was a new track: the lines were thrown away and parsed again a line at a time, which on
     * screen was the lyrics blinking out and back (state log 2026-09-16 02:42). Artist, album
     * and duration hold still across a song under either convention.
     */
    private static String lyricKey(String cardKey, MediaController c) {
        String fallback = cardKey == null ? "" : cardKey;
        if (c == null || fallback.isEmpty()) return fallback;
        try {
            android.media.MediaMetadata md = c.getMetadata();
            if (md == null) return fallback;
            String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            String album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            if (artist == null && album == null && dur <= 0) return fallback;
            return c.getPackageName() + "|" + artist + "|" + album + "|" + dur;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void onPlaybackState(PlaybackState s) {
        sState = s;
        sStateReadAt = SystemClock.uptimeMillis();
        LyricView v = sView;
        if (v != null) v.kick();
    }

    /** Cover mode came on. Puts the view in the keyguard if the switch is on. */
    static void attach() {
        updateBlur();
        if (!wantsAttached()) return;
        final View anchor = Main.sContainer;
        if (anchor == null) return;
        try {
            int id = anchor.getResources().getIdentifier(
                    "keyguard_foreground_layer", "id", "com.android.systemui");
            View layer = id == 0 ? null : anchor.getRootView().findViewById(id);
            if (!(layer instanceof ViewGroup)) {
                Xp.log(TAG + "keyguard_foreground_layer not found");
                return;
            }
            if (sView == null || sView.getContext() != anchor.getContext()) {
                sView = new LyricView(anchor.getContext());
            }
            LyricView v = sView;
            if (v.getParent() != layer) {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                ((ViewGroup) layer).addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Xp.log(TAG + "view attached, " + sLines.size() + " lines");
            }
            v.kick();
            startTick();
        } catch (Throwable t) {
            Xp.log(TAG + "attach failed: " + Log.getStackTraceString(t));
        }
    }

    /** Called by the view itself once it has faded out with no reason to stay. */
    static void detach(LyricView v) {
        if (wantsAttached()) return;
        if (sHolding) {
            sHolding = false;
            v.setKeepScreenOn(false);
        }
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
            Xp.log(TAG + "view detached");
        }
        Main.main().removeCallbacks(TICK);
    }

    /** Anything that may change whether the view should be showing. */
    static void refresh() {
        updateBlur();
        updateHdr();
        if (wantsAttached()) attach();
        LyricView v = sView;
        if (v != null) v.kick();
    }

    static void setEnabled(boolean on, String key, MediaController c) {
        sEnabled = on;
        Xp.log(TAG + "lyrics " + (on ? "on" : "off"));
        if (on) {
            sKey = "";
            onTrack(key, c);
        } else if (!sDemo) {
            sGen++;
            sLoading = false;
            setLines(Collections.<LyricLine>emptyList(), "switched off");
        }
        refresh();
    }

    /**
     * Plays a lyric file by database id on its own clock, whatever is playing - so the look can
     * be judged without a player that publishes an id, or a song the database has.
     */
    static void demo(String id, boolean apple) {
        sDemo = true;
        final int gen = ++sGen;
        sLoading = true;
        sKey = "demo:" + id;
        setLines(Collections.<LyricLine>emptyList(), "demo loading");
        LyricSource.loadById(id, apple, new LyricSource.Callback() {
            @Override
            public void onLines(List<LyricLine> lines, String why) {
                if (gen != sGen || !sDemo) return;
                sLoading = false;
                sDemoT0 = SystemClock.uptimeMillis();
                setLines(lines, "demo " + why);
                refresh();
            }
        });
        refresh();
    }

    static void endDemo(String key, MediaController c) {
        if (!sDemo) return;
        sDemo = false;
        sKey = "";
        onTrack(key, c);
        refresh();
    }

    static String describe() {
        LyricView v = sView;
        View c = Main.sContainer;
        View card = card();
        return "enabled=" + sEnabled + " demo=" + sDemo + " key=" + sKey + " lines=" + sLines.size()
                + " (" + sWhy + ") pos=" + positionMs() + " playing=" + playing()
                + " presentation=" + LockscreenMediaPresentationBridge.INSTANCE.getPresentation()
                + " cover=" + Main.coverModeOn() + " screen=" + Main.screenOnCached()
                + " phase=" + ClockCollapse.phase() + " cardP=" + Main.cardProgress()
                + " container=" + (c == null ? "none" : c.getAlpha() + "/shown=" + c.isShown())
                + " card=" + (card == null ? "none" : "shown=" + card.isShown())
                + " clockBottom=" + ClockCollapse.inkBottomOnScreen()
                + " shown=" + wantsShown() + " tick=" + sTicking
                + " view={" + (v == null ? "none" : v.describe()) + "}";
    }

    /** The wallpaper process restarted, or may have: tell it again. */
    static void resendBlur() {
        sBlurSent = null;
        updateBlur();
    }

    /**
     * Frosts the cover while there are lyrics on it, so they read over any artwork.
     *
     * Held through a track change's lookup rather than dropped and re-applied, which would pulse
     * the cover sharp and back on every song. Nothing is sent while cover mode is off: leaving
     * it fades the cover out whole, and the wallpaper process clears the blur with it.
     */
    private static void updateBlur() {
        if (!Main.coverModeOn()) {
            sBlurSent = Boolean.FALSE;
            return;
        }
        boolean on = sEnabled || sDemo;
        boolean want = on && (!sLines.isEmpty() || (sLoading && Boolean.TRUE.equals(sBlurSent)));
        if (sBlurSent != null && sBlurSent == want) return;
        sBlurSent = want;
        Main.sendToWallpaper("lyricblur", want);
        Xp.log(TAG + "cover blur " + (want ? "on" : "off"));
    }

    // ------------------------------------------------------------------ internals

    private static void setLines(List<LyricLine> lines, String why) {
        sLines = lines == null ? Collections.<LyricLine>emptyList() : lines;
        sVersion++;
        sWhy = why;
        Xp.log(TAG + sLines.size() + " lines: " + why);
        refresh();
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
            if (!sTicking) return;
            long delay = 1000L;
            holdScreen(v);
            updateHdr();
            if (Main.screenOnCached() && !sLines.isEmpty()) {
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
        boolean want = sKeepOn && wantsShown() && !sLines.isEmpty() && playing();
        if (want == sHolding) return;
        sHolding = want;
        v.setKeepScreenOn(want);
        Xp.log(TAG + (want ? "holding the screen on" : "screen may sleep again"));
    }

    /** The shade window controller, seen on its first apply. */
    static void noteShadeWindow(Object controller) {
        sShadeWindow = controller;
    }

    /** Whether the singing words should be drawn in HDR right now. */
    static boolean hdrWanted() {
        return sHdr && sGlowing && wantsShown() && !sLines.isEmpty();
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
     * Writes the colour mode into the shade window's pending attributes, or puts back what the
     * OEM had there. Called inside the OEM's own apply, so it lands in the same update.
     */
    static void applyHdrTo(android.view.WindowManager.LayoutParams lp) {
        if (lp == null) return;
        if (hdrWanted()) {
            if (!sModeOwned) {
                sOrigColorMode = lp.getColorMode();
                sOrigHeadroom = lp.getDesiredHdrHeadroom();
                sModeOwned = true;
            }
            lp.setColorMode(android.content.pm.ActivityInfo.COLOR_MODE_HDR);
            lp.setDesiredHdrHeadroom(HDR_HEADROOM);
        } else if (sModeOwned) {
            lp.setColorMode(sOrigColorMode);
            lp.setDesiredHdrHeadroom(sOrigHeadroom);
            sModeOwned = false;
        }
    }

    /** Asks the OEM to re-apply its window attributes when what we want of them has changed. */
    static void updateHdr() {
        boolean want = hdrWanted();
        if (want == sHdrApplied) return;
        Object w = sShadeWindow;
        if (w == null) return;
        sHdrApplied = want;
        try {
            Xp.callMethod(w, "applyWindowLayoutParams");
            Xp.log(TAG + "lock screen window HDR " + (want ? "on" : "off"));
        } catch (Throwable t) {
            Xp.log(TAG + "applyWindowLayoutParams failed: " + t);
        }
    }

    /** Every string in the session's metadata, and lyricInfo written to a file whole. */
    // "lyricInfo" is the players' own key, not a framework one - see LyricSource.lyricInfoOf.
    @android.annotation.SuppressLint("WrongConstant")
    static String dumpMetadata(android.content.Context ctx, MediaController c) {
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
                java.io.File f = new java.io.File(ctx.getFilesDir(), "mc_lyricinfo.json");
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
