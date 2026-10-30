// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyricSource;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.NcmLyrics;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The cover of the track that has not been asked for yet.
 *
 * Measured on this phone: from a press landing to the player saying a track changed is 713-972ms,
 * and everything the cover does after that is ~110ms. So the swap is not slow - it starts late,
 * and no amount of work on the second number touches the first.
 *
 * A player that publishes a play QUEUE says what is coming before it is asked, which is what this
 * uses: the artwork either side of the current item is fetched and kept, and the press itself -
 * caught on TransportControls, ~0.8s before the player reports anything - hands the right one
 * straight to the wallpaper.
 *
 * Only Apple Music publishes one here. Measured 2026-09-17 with `op queue`: NetEase, Salt Player
 * and Bilibili all answer "no queue", so for them this does nothing at all and the cover waits
 * for the player exactly as it did. Nothing about it is Apple-specific though - the queue and the
 * artwork URI are both public MediaSession API, and any player that fills them in gets it too.
 *
 * Guessing is the whole idea, so being wrong has to be cheap: the prediction is only ever an
 * early push of a picture, and the player's own metadata arrives a moment later and is what
 * settles it. See Main.onMediaUpdate(), which pushes again whenever the two disagree.
 */
final class Prefetch {

    private Prefetch() {
    }

    private static final String TAG = "[MCPre] ";

    /**
     * How many items either side of the current one are worth holding.
     *
     * Two, not one, because pressing next twice quickly is the case this exists for: the second
     * press predicts from where the first one landed, and one item of reach would already be
     * behind it.
     */
    private static final int REACH = 2;
    /** Artwork is ~1MB decoded; this is a handful of tracks, not a library. */
    private static final int CACHE_MAX = 6;

    /** One queue item, reduced to what a cover and a lyric lookup need. */
    private static final class Item {
        final long id;
        final String title;
        final Uri icon;
        /** The platform's own song id, which is what the lyric database is keyed by. */
        final String mediaId;
        /**
         * The other half of a lyric search. Only the artist, because the search takes the song's
         * name and its first artist and nothing else - the album and the duration that the rest
         * of a by-name lookup wants are not needed until the choosing, which happens later and
         * elsewhere. See NcmLyrics.terms().
         */
        final String artist;

        Item(long id, String title, Uri icon, String mediaId, String artist) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.mediaId = mediaId;
            this.artist = artist;
        }

        /** The same track: by the platform id where there is one, else by title. */
        boolean sameTrack(Item o) {
            if (o == null) return false;
            if (mediaId != null && o.mediaId != null) return mediaId.equals(o.mediaId);
            return title != null && title.equals(o.title);
        }
    }

    /**
     * The tracks played before this one, most recent first.
     *
     * Kept here because Apple Music's queue does not hold them: it always publishes the current
     * item at index 0 and what comes after (measured 2026-09-23 with `op queue`: "active id=0
     * (index 0)"), so a skip back found nothing before it and waited the whole ~0.8s for the
     * player, where a skip on was answered at once.
     */
    private static final java.util.ArrayList<Item> sHistory = new java.util.ArrayList<>();
    private static final int HISTORY_MAX = 4;
    /** The item that was current at the last queue read, to notice it changing. */
    private static Item sCurrent;
    /** How many skips back have been predicted from sHistory since the last queue read. */
    private static int sBackDepth;
    /** The session's last playback state, for where in the track a skip back is pressed. */
    private static volatile PlaybackState sState;

    /**
     * How far into a track a skip back only restarts it. media3's default
     * (maxSeekToPreviousPositionMs), which Apple Music is built on. Past it the cover is not
     * going to change, so nothing is predicted rather than flashing the previous album.
     */
    private static final long RESTART_MS = 3000L;

    /** Which player the queue belongs to; the lyric half is only run for one of them. */
    private static volatile String sPkg;

    private static volatile List<Item> sItems = new ArrayList<>();
    /** Where the player says it is in that list, or -1 when it does not say. */
    private static volatile int sIndex = -1;

    /** Decoded artwork, keyed on the URI it came from. Guarded by CACHE. */
    private static final java.util.LinkedHashMap<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);

    private static Handler sWork;

    /**
     * The title this predicted for the press that has not been confirmed yet, and when it was
     * predicted. Read by Main to decide whether the player's own report needs another push.
     */
    private static volatile String sPredicted;
    private static volatile long sPredictedAt;

    /** A prediction older than this is not worth matching against - the player never got there. */
    private static final long PREDICTION_TTL_MS = 4000L;

    private static synchronized Handler work() {
        if (sWork == null) {
            HandlerThread t = Gen.handlerThread("mc-prefetch",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    // ------------------------------------------------------------------ from Main

    /**
     * The player reported something: re-read its queue and fetch what sits either side of the
     * current track. Cheap when nothing moved - the fetches are keyed on the artwork URI and a
     * cached one does not go out again.
     */
    static void onTrack(final MediaController c) {
        if (c == null) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return;
        }
        work().post(new Runnable() {
            @Override
            public void run() {
                try {
                    readQueue(c);
                    fetchAround();
                    warmLyricAhead();
                } catch (Throwable t) {
                    Xp.w(TAG + "queue read failed: " + t);
                }
            }
        });
    }

    /**
     * The artwork already decoded for one URI, or null. Read by ArtUri: the cover asks for the
     * same URLs the queue holds - the track playing is in the queue too - and taking this copy is
     * what stops one picture being downloaded twice for the same track change.
     */
    static Bitmap cached(String uri) {
        if (uri == null) return null;
        synchronized (CACHE) {
            Bitmap b = CACHE.get(uri);
            return b != null && !b.isRecycled() ? b : null;
        }
    }

    /**
     * A skip was just asked for. Answers the artwork for where the queue says it lands, or null -
     * no queue, not fetched yet, or the queue has run out that way.
     *
     * Called on the thread that asked for the skip, so it only reads what is already in hand.
     */
    static Bitmap take(int dir) {
        if (dir < 0) {
            Bitmap back = takeBack();
            if (back != null) return back;
        }
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return null;
        int want = at + (dir < 0 ? -1 : 1);
        if (want < 0 || want >= items.size()) {
            // The ends are real: at the last track, next may wrap, stop, or do nothing at all,
            // and the queue does not say which. Not guessing is the cheaper mistake.
            return null;
        }
        Item it = items.get(want);
        if (it.icon == null) return null;
        Bitmap b;
        synchronized (CACHE) {
            b = CACHE.get(it.icon.toString());
        }
        if (b == null || b.isRecycled()) return null;
        // Moved here and now, so a second press within the burst predicts from the new place
        // rather than from where the player still thinks it is.
        sIndex = want;
        sPredicted = it.title;
        sPredictedAt = SystemClock.uptimeMillis();
        Xp.log(TAG + "predicting \"" + it.title + "\" for a skip " + (dir < 0 ? "back" : "on"));
        // The one after this one is now worth having. sIndex has already moved, so this reads
        // ahead of where the press is landing rather than of where the player still thinks it is
        // - which is the whole 0.8s the press is caught before the player reports it.
        work().post(new Runnable() {
            @Override
            public void run() {
                fetchAround();
            }
        });
        warmLyricAhead();
        return b;
    }

    /**
     * A skip back, answered from the tracks this has seen play rather than from the queue, which
     * does not hold them. Null past RESTART_MS, where the press restarts the track instead.
     */
    private static Bitmap takeBack() {
        if (positionMs() > RESTART_MS) return null;
        Item it;
        synchronized (sHistory) {
            // A player whose queue does hold the past is answered by the queue itself.
            if (sIndex > 0) return null;
            if (sBackDepth >= sHistory.size()) return null;
            it = sHistory.get(sBackDepth);
        }
        if (it.icon == null) return null;
        Bitmap b;
        synchronized (CACHE) {
            b = CACHE.get(it.icon.toString());
        }
        if (b == null || b.isRecycled()) return null;
        synchronized (sHistory) {
            sBackDepth++;
        }
        // The queue's place no longer says where the press is landing, so a skip on before the
        // player reports is left to the player rather than predicted from the wrong track.
        sIndex = -1;
        sPredicted = it.title;
        sPredictedAt = SystemClock.uptimeMillis();
        Xp.log(TAG + "predicting \"" + it.title + "\" for a skip back, from history");
        return b;
    }

    /** Where the session last said it was, carried forward to now. */
    private static long positionMs() {
        PlaybackState s = sState;
        if (s == null) return 0L;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime())
                    * s.getPlaybackSpeed());
        }
        return pos;
    }

    /**
     * Notes the current item changing: the one it replaced goes onto the history, or - when the
     * new one is the history's latest, a skip back - comes off it.
     */
    private static void noteCurrent(Item now) {
        synchronized (sHistory) {
            sBackDepth = 0;
            if (now == null) return;
            Item was = sCurrent;
            sCurrent = now;
            if (was == null || was.sameTrack(now)) return;
            if (!sHistory.isEmpty() && sHistory.get(0).sameTrack(now)) {
                sHistory.remove(0);
                return;
            }
            sHistory.add(0, was);
            while (sHistory.size() > HISTORY_MAX) sHistory.remove(sHistory.size() - 1);
        }
    }

    /**
     * Whether the track the player has now is the one already pushed for. Consumes the
     * prediction either way: it has been answered.
     */
    static boolean wasPredicted(String title) {
        String p = sPredicted;
        long at = sPredictedAt;
        sPredicted = null;
        if (p == null || title == null) return false;
        if (SystemClock.uptimeMillis() - at > PREDICTION_TTL_MS) return false;
        boolean hit = p.equals(title);
        Xp.log(TAG + (hit ? "prediction held: " : "prediction missed: predicted \"" + p
                + "\", the player went to ") + "\"" + title + "\"");
        return hit;
    }

    /** For `op queue` and the settings page: whether this can do anything for the player. */
    static String describe() {
        List<Item> items = sItems;
        int n;
        synchronized (CACHE) {
            n = CACHE.size();
        }
        int back;
        synchronized (sHistory) {
            back = sHistory.size();
        }
        return "queue=" + items.size() + " at=" + sIndex + " history=" + back + " cached=" + n
                + " predicted=" + sPredicted
                // What reading ahead has in hand. The module's own log cannot be read back on
                // this device, so this line is the only place the lyric prefetch is visible.
                + " " + NcmLyrics.describeSearches() + " " + LyricSource.describeWarm();
    }

    // ------------------------------------------------------------------ internals

    private static void readQueue(MediaController c) {
        String pkg = c.getPackageName();
        if (sPkg != null && !sPkg.equals(pkg)) {
            // Another player's past is not this one's.
            synchronized (sHistory) {
                sHistory.clear();
                sCurrent = null;
            }
        }
        sPkg = pkg;
        List<MediaSession.QueueItem> q = c.getQueue();
        if (q == null || q.isEmpty()) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return;
        }
        List<Item> items = new ArrayList<>(q.size());
        for (MediaSession.QueueItem qi : q) {
            MediaDescription d = qi.getDescription();
            items.add(new Item(qi.getQueueId(),
                    d == null || d.getTitle() == null ? null : d.getTitle().toString(),
                    d == null ? null : d.getIconUri(),
                    d == null ? null : d.getMediaId(),
                    str(d == null ? null : d.getSubtitle())));
        }
        long active = -1L;
        PlaybackState ps = c.getPlaybackState();
        sState = ps;
        if (ps != null) active = ps.getActiveQueueItemId();
        int at = -1;
        for (int n = 0; n < items.size(); n++) {
            if (items.get(n).id == active) {
                at = n;
                break;
            }
        }
        sItems = items;
        sIndex = at;
        if (at >= 0) noteCurrent(items.get(at));
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    /**
     * The lyric for the track after this one, fetched into the caches the real lookup reads.
     *
     * One ahead, where the artwork takes two either side. The cases are not the same shape: a
     * cover has to be right the instant a press lands, and two presses in a burst is what that
     * reach exists for, where nobody reads the lyrics of a song they skipped past in a second.
     * Going only forward and only one deep also keeps the request rate close to what it was,
     * which matters for the by-name half - NetEase answers a client it has decided is searching
     * too much by quietly leaving the right song out of the results.
     *
     * Apple Music only, which is not a limitation so much as a description: it is the one player
     * here that publishes a queue at all (measured 2026-09-17 - NetEase, Salt and Bilibili all
     * answer "no queue"), so for everyone else there is nothing to read ahead from.
     */
    private static void warmLyricAhead() {
        String pkg = sPkg;
        if (pkg == null || !pkg.contains("apple")) return;
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0 || at + 1 >= items.size()) return;
        final Item it = items.get(at + 1);
        final String dir = LyricSource.dirForPackage(pkg);
        // Apple's queue items carry the title, the artist and the platform id, and no duration
        // anywhere - the extras hold thirty of the player's own keys and not that one (measured
        // 2026-09-22 with `op queue`). So the by-name half is asked for only as far as a
        // duration is not needed, which is exactly as far as the search: NcmLyrics.warmSearch
        // runs it and keeps the results, and the real lookup does the choosing once the session
        // has told it how long the track is.
        final boolean byName = it.title != null && !it.title.isEmpty()
                && it.artist != null && !it.artist.isEmpty();
        if (it.mediaId == null && !byName) return;
        lyricWork().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Xp.log(TAG + "reading ahead for \"" + it.title + "\" (id=" + it.mediaId
                            + (byName ? ", searching \"" + it.artist + "\"" : ", no name search")
                            + ")");
                    LyricSource.warm(it.mediaId, dir, byName ? it.title : null,
                            byName ? it.artist : null);
                } catch (Throwable t) {
                    Xp.w(TAG + "reading ahead failed: " + t);
                }
            }
        });
    }

    /**
     * Its own thread, not the artwork's.
     *
     * The mirrors are allowed seconds and the by-name search is three round trips, and the
     * artwork prefetch is what makes a press answerable at all - sharing one thread would put
     * the cover behind the lyric of a song that has not started.
     */
    private static Handler sLyricWork;

    private static synchronized Handler lyricWork() {
        if (sLyricWork == null) {
            HandlerThread t = Gen.handlerThread("mc-lyricahead",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            sLyricWork = new Handler(t.getLooper());
        }
        return sLyricWork;
    }

    /** Fetches the artwork either side of where we think we are, newest need first. */
    private static void fetchAround() {
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return;
        for (int d = 1; d <= REACH; d++) {
            fetch(items, at + d);
            fetch(items, at - d);
        }
        // What a skip back lands on, which the queue does not hold. Usually still cached from
        // when it was the track ahead; fetched again if it was trimmed since.
        Item back;
        synchronized (sHistory) {
            back = sHistory.isEmpty() ? null : sHistory.get(0);
        }
        fetch(back);
        trim();
    }

    private static void fetch(List<Item> items, int at) {
        if (at < 0 || at >= items.size()) return;
        fetch(items.get(at));
    }

    private static void fetch(Item it) {
        if (it == null || it.icon == null) return;
        String key = it.icon.toString();
        synchronized (CACHE) {
            Bitmap have = CACHE.get(key);
            if (have != null && !have.isRecycled()) return;
        }
        long t0 = SystemClock.uptimeMillis();
        Bitmap b = load(it.icon);
        if (b == null) return;
        synchronized (CACHE) {
            CACHE.put(key, b);
        }
        Xp.log(TAG + "fetched \"" + it.title + "\" " + b.getWidth() + "x" + b.getHeight()
                + " in " + (SystemClock.uptimeMillis() - t0) + "ms");
    }

    private static void trim() {
        synchronized (CACHE) {
            while (CACHE.size() > CACHE_MAX) {
                java.util.Iterator<String> it = CACHE.keySet().iterator();
                if (!it.hasNext()) return;
                it.next();
                it.remove();
            }
        }
    }

    /**
     * The artwork behind one URI. http(s) goes through ArtUri - SystemUI holds INTERNET, and this
     * runs on the prefetch thread - and anything else goes through the resolver, so a player that
     * publishes content:// artwork is served the same way.
     *
     * It goes through ArtUri rather than one more connection of its own because SystemUI cannot
     * read a cleartext URL at all: every http artwork URL a player publishes (MeiloX's NetEase
     * covers, measured 2026-10-08) failed here with "Cleartext HTTP traffic ... not permitted",
     * 207 times in a day and no arrival. ArtUri rewrites the scheme and reads the size, so the
     * queue art arrives and the press prediction has something to answer with.
     */
    private static Bitmap load(Uri uri) {
        String scheme = uri.getScheme();
        try {
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return ArtUri.fetch(uri.toString());
            }
            Context ctx = Main.appContext();
            if (ctx == null) return null;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Xp.log(TAG + "could not read " + uri + ": " + t);
            return null;
        }
    }
}
