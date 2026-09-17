package btm.m.os4.systemuihook.hypermusiccover;

import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Where the lyric rows come from.
 *
 * Deliberately not a lyric module's API. The modules that do this on this device reach the
 * players by hooking them - and on this device all three of the players were broken at once by
 * app updates the module had not caught up with: a NoSuchFieldError on Apple Music, a
 * NoSuchClassError on Salt, a DexKit cache miss on NetEase. Nothing that inherits that is worth
 * building on.
 *
 * This route never touches the player. A song id comes out of the media session, and the AMLL
 * database - a community-built set of word-level TTML files, one per song, named by the
 * platform's own id - is asked for that id directly. Nothing here breaks when a player updates,
 * because nothing here knows a player's internals.
 *
 * What it does inherit is coverage and the network. Tens of thousands of songs against millions
 * of songs means a miss is normal, and the mirrors are GitHub-hosted, which from here is a coin
 * toss: measured on this device the authoritative copy has answered in 610ms and has also timed
 * out after 23s. Both are handled by being explicit rather than optimistic - a miss leaves the
 * lock screen without lyrics, which is what it had before this existed, and no request is given
 * long enough to be felt.
 */
final class LyricSource {

    interface Callback {
        /** Always called on the main thread. lines is never null; empty means nothing was found. */
        void onLines(List<LyricLine> lines, String why);
    }

    private LyricSource() {
    }

    /**
     * The key OPlus's own lock screen reads its lyrics from, as a JSON string on the session's
     * metadata.
     *
     * This is the primary source and the ID lookup below is the fallback, because this one has
     * none of the ID route's problems: the lyrics are the player's own - for a local-file player
     * they are the file's own lyrics, which is the only thing that can be right for music that
     * is not in any online database - and they arrive with the session instead of after a network
     * round trip, so there is no coverage to miss and no mirror to be down.
     *
     * Nothing here talks to a player. The contract is a JSON object with a timed LRC string under
     * "lyric" (or, for word-level payloads, under "rawLyric"), plus songName/artist/album/ and a
     * translation under one of several names. It is written by whoever has the lyrics - the
     * player itself, or one of the provider modules that hook the players that do not.
     */
    private static final String KEY_LYRIC_INFO = "lyricInfo";

    /*
     * AMLL has the best source material when a player exposes its native song id: it preserves
     * TTML's word timing and translations.  IDs are not portable, though.  LRCLIB is deliberately
     * only the next step: it is a public, no-key catalogue which accepts portable session
     * metadata, so the same fallback works for NetEase, QQ Music, Apple Music and Spotify
     * without reverse-engineering any of their private APIs.
     */
    private static final String LRCLIB_GET = "https://lrclib.net/api/get";
    private static final String LRCLIB_SEARCH = "https://lrclib.net/api/search";
    private static final String ITUNES_SEARCH = "https://itunes.apple.com/search";
    private static final String MUSICBRAINZ_SEARCH =
            "https://musicbrainz.org/ws/2/recording/";
    private static final String CLIENT_USER_AGENT =
            "HyperChanger/1.1 (https://github.com/ColdP/HyperChanger)";

    private static final Object ITUNES_THROTTLE = new Object();
    private static final Object MUSICBRAINZ_THROTTLE = new Object();
    private static long lastItunesRequest;
    private static long lastMusicBrainzRequest;
    private static volatile long itunesBlockedUntil;
    private static volatile long musicBrainzBlockedUntil;
    private static final Object TITLE_RESOLUTION_LOCK = new Object();
    private static final ConcurrentHashMap<String, TitleCacheEntry> TITLE_CACHE =
            new ConcurrentHashMap<>();
    private static final long POSITIVE_TITLE_CACHE_MS = TimeUnit.HOURS.toMillis(24);
    private static final long NEGATIVE_TITLE_CACHE_MS = TimeUnit.MINUTES.toMillis(10);

    /** LRC-style timing, bracketed with [] or <>. <> is the word-level (enhanced) form. */
    private static final java.util.regex.Pattern TIMED =
            java.util.regex.Pattern.compile("[\\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\\]>]");

    /**
     * The player's own lyrics, if the session carries them. Null when nothing has published any,
     * which is the normal state on a device with no provider modules installed.
     */
    // "lyricInfo" is not one of the framework's metadata keys and is not meant to be: it is the
    // players' and provider modules' own, and a Bundle lookup by any string is valid.
    @android.annotation.SuppressLint("WrongConstant")
    static String lyricInfoOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            return md.getString(KEY_LYRIC_INFO);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The timed lyric text out of that payload, or null if there is none worth rendering.
     *
     * A payload without timing is rejected rather than shown: this view follows the singing, and
     * untimed text has nothing to follow. That is also the difference between the two fields -
     * "lyric" is the display form and is preferred, "rawLyric" is kept verbatim for word-level
     * renderers and is only used when "lyric" carries no timing at all.
     */
    static String textOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String lyric = o.optString("lyric", "");
            String raw = o.optString("rawLyric", "");
            if (TIMED.matcher(lyric).find()) {
                return lyric;
            }
            if (TIMED.matcher(raw).find()) {
                return raw;
            }
            return null;
        } catch (Throwable t) {
            Xp.log("[MCLyric] lyricInfo is not the expected JSON: " + t);
            return null;
        }
    }

    /** The payload's rawLyric, verbatim - the form that keeps word timings, when there are any. */
    static String rawOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String raw = new org.json.JSONObject(json).optString("rawLyric", "");
            // Not tested against TIMED: word-timed formats (YRC, QRC, TTML) do not use LRC's
            // [mm:ss] tags at all. Whether it is usable is decided by parsing it.
            return raw.trim().isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One directory per platform, each keyed by that platform's own song id.
     *
     * Which directory an id belongs in cannot be read off the number - a NetEase id and an Apple
     * id are both plausible-looking integers - so the owning package is asked first and the rest
     * are tried in order. Measured on this device: Apple Music publishes its store id in
     * MEDIA_ID, and looking that up under ncm-lyrics finds nothing, which is exactly what a
     * wrong-directory lookup looks like.
     */
    private static String[] dirsFor(MediaController c) {
        String pkg = c == null ? "" : c.getPackageName();
        if (pkg.contains("netease")) {
            return new String[]{"ncm-lyrics"};
        }
        if (pkg.contains("qqmusic") || pkg.contains("tencent")) {
            return new String[]{"qq-lyrics"};
        }
        if (pkg.contains("apple")) {
            return new String[]{"am-lyrics"};
        }
        if (pkg.contains("spotify")) {
            return new String[]{"spotify-lyrics"};
        }
        return new String[]{"ncm-lyrics", "am-lyrics", "qq-lyrics"};
    }

    /**
     * Every mirror is asked at once and the first one to have the file wins.
     *
     * Not a chain, which is what this started as and what the log showed the cost of: raw timed
     * out after 23s, ghfast after 16s, and only then did jsdelivr answer - so a song that was in
     * the database took the better part of a minute to appear, if the screen was still locked.
     * Racing them costs the same bandwidth as the chain's worst case and the latency of its
     * best. They are all the same file; there is nothing to be gained by preferring one.
     *
     * gitmirror is the exception and is kept last for the record: it does not resolve from this
     * network at all, which costs nothing to find out in parallel and would have cost the whole
     * timeout budget in a chain.
     */
    private static final String[][] MIRRORS = {
            {"raw", "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"jsdelivr", "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/%s/%s.ttml"},
            {"ghfast", "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"gitmirror", "https://raw.gitmirror.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
    };

    /** FOUND carries a body; MISSING is GitHub saying the file is not there; UNREACHABLE is us. */
    private static final int FOUND = 1;
    private static final int MISSING = 2;
    private static final int UNREACHABLE = 3;

    /** Package-private so the probe can read the status as well as the body. */
    static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MCLyricFetch");
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * The song id, if the session is publishing one.
     *
     * MEDIA_ID is the documented place, and it is what both a NetEase client and Apple Music
     * fill in - each with its own platform's id, which is exactly the key the database wants. It
     * is also frequently empty, and frequently an internal uri rather than an id, so it is only
     * taken when it is all digits. A local-file player publishes no id at all, and there is
     * nothing to guess from.
     */
    static String idOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id == null || id.isEmpty()) {
                return null;
            }
            for (int i = 0; i < id.length(); i++) {
                if (!Character.isDigit(id.charAt(i))) {
                    return null;
                }
            }
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Portable metadata for the public fallback.  It intentionally has no player-specific keys. */
    private static final class TrackMetadata {
        final String title;
        final String artist;
        final String album;
        final long durationSeconds;
        final boolean appleMusic;

        TrackMetadata(String title, String artist, String album, long durationSeconds) {
            this(title, artist, album, durationSeconds, false);
        }

        TrackMetadata(String title, String artist, String album, long durationSeconds,
                      boolean appleMusic) {
            this.title = cleanTitle(title);
            this.artist = trim(artist);
            this.album = trim(album);
            this.durationSeconds = durationSeconds;
            this.appleMusic = appleMusic;
        }

        boolean canLookup() {
            return !title.isEmpty() && !artist.isEmpty();
        }
    }

    private static TrackMetadata metadataOf(MediaController c) {
        if (c == null) return new TrackMetadata(null, null, null, 0);
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) return new TrackMetadata(null, null, null, 0);
            long durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long seconds = durationMs > 0 ? Math.round(durationMs / 1000d) : 0;
            return new TrackMetadata(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM), seconds,
                    c.getPackageName().toLowerCase(java.util.Locale.ROOT).contains("apple"));
        } catch (Throwable ignored) {
            return new TrackMetadata(null, null, null, 0);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** Removes localized parenthetical translations that players append to the canonical title. */
    private static String cleanTitle(String value) {
        String title = trim(value);
        if (title.isEmpty()) return title;
        StringBuilder result = new StringBuilder(title.length());
        int depth = 0;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c == '(' || c == '\uff08') {
                depth++;
            } else if (c == ')' || c == '\uff09') {
                if (depth > 0) depth--;
                else result.append(c);
            } else if (depth == 0) {
                result.append(c);
            }
        }
        return result.toString().trim();
    }

    /**
     * Fetch and parse, on a worker, then hand the rows to the main thread.
     *
     * The caller is a track change, i.e. SystemUI's main thread in the middle of a transition
     * that is already doing the cover and the clock - so nothing here touches the network or
     * parses a few tens of kilobytes on it.
     */
    static void load(final MediaController c, final Callback cb) {
        final String pkg = c == null ? "?" : c.getPackageName();

        // The player's own lyrics first. Everything the id route below cannot do - a player that
        // publishes no id, a song no online database has, a mirror that is down - is something
        // this route does not need: the lyrics came in with the session, and for a local-file
        // player they are the file's own.
        final String info = lyricInfoOf(c);
        final String text = textOfLyricInfo(info);
        final String raw = rawOfLyricInfo(info);
        final String id = idOf(c);
        final String[] dirs = dirsFor(c);
        final TrackMetadata metadata = metadataOf(c);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LyricLine> lines = Collections.emptyList();
                String why = null;
                try {
                    if (metadata.canLookup()) {
                        SourceResult local = loadLocalLyrics(metadata);
                        lines = local.lines;
                        if (!lines.isEmpty()) why = local.why;
                    }
                    if (lines.isEmpty() && (text != null || raw != null)) {
                        // The word-timed copy when there is one: "lyric" is the display form and
                        // is often line-timed even when "rawLyric" has every word's timing, and
                        // preferring it left a word-timed song with no word fill at all.
                        lines = Collections.emptyList();
                        String used = "lyric";
                        if (raw != null) {
                            List<LyricLine> r = LyricParse.parse(raw);
                            for (LyricLine l : r) {
                                if (l.hasWords()) {
                                    lines = r;
                                    used = "rawLyric";
                                    break;
                                }
                            }
                        }
                        if (lines.isEmpty() && text != null) lines = LyricParse.parse(text);
                        why = lines.isEmpty() ? "lyricInfo parsed to nothing"
                                : lines.size() + " lines from the player's own lyricInfo (" + used + ")";
                    }
                    if (lines.isEmpty() && id != null) {
                        SourceResult amll = loadAml(id, dirs);
                        lines = amll.lines;
                        why = amll.why;
                    }
                    if (lines.isEmpty() && metadata.canLookup()) {
                        SourceResult lrclib = loadLrclib(metadata);
                        lines = lrclib.lines;
                        why = lrclib.why;
                    }
                    if (why == null) why = "no lyricInfo, AMLL id, or usable metadata";
                } catch (Throwable t) {
                    Xp.log("[MCLyric] load failed: " + t);
                    lines = Collections.emptyList();
                    why = "error";
                }
                    Xp.log("[MCLyric] " + pkg + " -> " + why);
                    onMain(cb, lines, why);
            }
        }, "MCLyricSource").start();
    }

    /** App-managed lyrics use the same title and artist filters as the LRCLIB fallback. */
    private static SourceResult loadLocalLyrics(TrackMetadata wanted) {
        android.content.Context context = Main.appContext();
        if (context == null) {
            return new SourceResult(Collections.<LyricLine>emptyList(), "local library unavailable");
        }
        android.database.Cursor cursor = null;
        List<LyricLine> best = Collections.emptyList();
        int bestScore = Integer.MIN_VALUE;
        try {
            android.net.Uri library = android.net.Uri.parse(
                    "content://btm.m.os4.systemuihook.settingsappearance/lyrics");
            cursor = context.getContentResolver().query(library, null, null, null, null);
            if (cursor == null) {
                return new SourceResult(best, "local library unavailable");
            }
            int idColumn = cursor.getColumnIndex("id");
            int titleColumn = cursor.getColumnIndex("title");
            int artistColumn = cursor.getColumnIndex("artist");
            int aliasesColumn = cursor.getColumnIndex("aliases");
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                String artist = normalize(cursor.getString(artistColumn));
                String wantedArtist = normalize(wanted.artist);
                if (id == null || artist.isEmpty() || wantedArtist.isEmpty()) continue;
                int artistMatch = artistMatchLength(artist, wantedArtist);
                if (artistMatch < requiredArtistMatchLength(artist, wantedArtist)) continue;

                double titleMatch = titleSimilarity(
                        normalize(cleanTitle(cursor.getString(titleColumn))), normalize(wanted.title));
                String aliases = aliasesColumn < 0 ? "" : cursor.getString(aliasesColumn);
                if (aliases != null && !aliases.trim().isEmpty()) {
                    for (String alias : aliases.split("[,，]")) {
                        titleMatch = Math.max(titleMatch, titleSimilarity(
                                normalize(cleanTitle(alias)), normalize(wanted.title)));
                    }
                }
                if (titleMatch < 0.70d) continue;
                int candidateScore = (int) Math.round(titleMatch * 100)
                        + (artist.equals(wantedArtist) ? 50 : 20 + Math.min(artistMatch, 20));
                if (candidateScore <= bestScore) continue;

                android.net.Uri file = library.buildUpon().appendPath(id).build();
                java.io.InputStream input = context.getContentResolver().openInputStream(file);
                if (input == null) continue;
                List<LyricLine> parsed = LyricParse.parse(read(input));
                if (!parsed.isEmpty()) {
                    best = parsed;
                    bestScore = candidateScore;
                }
            }
            return best.isEmpty()
                    ? new SourceResult(best, "not in local lyric library")
                    : new SourceResult(best, best.size() + " lines from local lyric library");
        } catch (Throwable t) {
            Xp.log("[MCLyric] local library failed: " + t);
            return new SourceResult(Collections.<LyricLine>emptyList(), "local library unavailable");
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * The same thing for an id that came from somewhere other than a session - which is how the
     * effect gets tested without depending on either the network picking a song we have, or the
     * playing app publishing anything.
     */
    static void loadById(final String id, final boolean apple, final Callback cb) {
        load(id, apple
                ? new String[]{"am-lyrics", "ncm-lyrics"}
                : new String[]{"ncm-lyrics", "am-lyrics"}, "id " + id, cb);
    }

    private static void load(final String id, final String[] dirs, final String who,
                             final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LyricLine> lines = Collections.emptyList();
                String why;
                try {
                    SourceResult result = loadAml(id, dirs);
                    lines = result.lines;
                    why = result.why;
                } catch (Throwable t) {
                    Xp.log("[MCLyric] load failed: " + t);
                    why = "error";
                }
                Xp.log("[MCLyric] " + who + " id=" + id + " -> " + why);
                onMain(cb, lines, why);
            }
        }, "MCLyricSource").start();
    }

    private static final class SourceResult {
        final List<LyricLine> lines;
        final String why;

        SourceResult(List<LyricLine> lines, String why) {
            this.lines = lines;
            this.why = why;
        }
    }

    private static final class TitleResolution {
        final String title;
        final String artist;
        final String source;

        TitleResolution(String title, String artist, String source) {
            this.title = title;
            this.artist = artist;
            this.source = source;
        }
    }

    private static final class TitleCacheEntry {
        final TitleResolution resolution;
        final long expiresAt;

        TitleCacheEntry(TitleResolution resolution, long expiresAt) {
            this.resolution = resolution;
            this.expiresAt = expiresAt;
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;
        final String retryAfter;

        HttpResult(int code, String body, String retryAfter) {
            this.code = code;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private static SourceResult loadAml(String id, String[] dirs) {
        Answer answer = null;
        String foundIn = null;
        for (String dir : dirs) {
            answer = fetch(dir, id);
            if (answer.status == FOUND) {
                foundIn = dir;
                break;
            }
            if (answer.status == UNREACHABLE) break;
        }
        if (answer == null || answer.status != FOUND) {
            return new SourceResult(Collections.<LyricLine>emptyList(),
                    "not in AMLL (" + id + ")");
        }
        List<LyricLine> lines = LyricParse.parse(answer.body);
        return new SourceResult(lines, lines.isEmpty()
                ? "AMLL parsed to nothing (" + foundIn + ")"
                : lines.size() + " lines from AMLL " + foundIn);
    }

    /** LRCLIB first, then title aliases for players which publish localized Latin metadata. */
    private static SourceResult loadLrclib(TrackMetadata metadata) {
        try {
            if (metadata.appleMusic && !containsJapaneseKana(metadata.title)) {
                TitleResolution japanese = resolveCanonicalTitle(metadata, true);
                if (japanese != null) {
                    Xp.log("[MCLyric] Apple Music Japanese title: " + metadata.title
                            + " -> " + japanese.title);
                    TrackMetadata original = new TrackMetadata(japanese.title,
                            japanese.artist.isEmpty() ? metadata.artist : japanese.artist,
                            metadata.album, metadata.durationSeconds, true);
                    SourceResult japaneseResult = lookupLrclib(original);
                    if (!japaneseResult.lines.isEmpty()) {
                        return new SourceResult(japaneseResult.lines,
                                japaneseResult.lines.size()
                                        + " lines from LRCLIB via Japanese metadata");
                    }
                }
            }
            SourceResult direct = lookupLrclib(metadata);
            if (!direct.lines.isEmpty()) return direct;

            TitleResolution resolved = resolveCanonicalTitle(metadata);
            if (resolved == null || normalize(resolved.title).equals(normalize(metadata.title))) {
                return direct;
            }
            Xp.log("[MCLyric] " + resolved.source + " title: " + metadata.title
                    + " -> " + resolved.title);
            TrackMetadata canonical = new TrackMetadata(resolved.title,
                    resolved.artist.isEmpty() ? metadata.artist : resolved.artist,
                    metadata.album, metadata.durationSeconds, metadata.appleMusic);
            SourceResult retried = lookupLrclib(canonical);
            return retried.lines.isEmpty() ? direct : new SourceResult(retried.lines,
                    retried.lines.size() + " lines from LRCLIB via " + resolved.source);
        } catch (Throwable t) {
            Xp.log("[MCLyric] LRCLIB failed: " + t);
            return new SourceResult(Collections.<LyricLine>emptyList(), "LRCLIB unavailable");
        }
    }

    /** LRCLIB's exact signature route, followed by a locally-verified search fallback. */
    private static SourceResult lookupLrclib(TrackMetadata metadata) throws Exception {
        boolean preferJapanese = containsJapaneseKana(metadata.title)
                || containsJapaneseKana(metadata.artist);
        String exact = LRCLIB_GET + "?track_name=" + enc(metadata.title)
                + "&artist_name=" + enc(metadata.artist)
                + (metadata.album.isEmpty() ? "" : "&album_name=" + enc(metadata.album))
                + (metadata.durationSeconds <= 0 ? "" : "&duration=" + metadata.durationSeconds);
        String exactBody = httpGet(exact);
        preferJapanese = preferJapanese || isJapaneseLrclibRecord(exactBody);
        List<LyricLine> exactLines = syncedLines(exactBody);
        if (!preferJapanese && !exactLines.isEmpty()) {
            return new SourceResult(exactLines, exactLines.size() + " lines from LRCLIB signature");
        }
        // LRCLIB requires sequential requests and recommends a 200-500 ms delay.
        Thread.sleep(250L);
        String search = LRCLIB_SEARCH + "?track_name=" + enc(metadata.title)
                + "&artist_name=" + enc(metadata.artist);
        String body = httpGet(search);
        if (body == null || body.isEmpty()) {
            return exactLines.isEmpty()
                    ? new SourceResult(Collections.<LyricLine>emptyList(), "LRCLIB unavailable")
                    : new SourceResult(exactLines,
                    exactLines.size() + " lines from LRCLIB signature");
        }
        org.json.JSONArray candidates = new org.json.JSONArray(body);
        if (!preferJapanese) {
            for (int i = 0; i < candidates.length(); i++) {
                org.json.JSONObject candidate = candidates.optJSONObject(i);
                if (candidate != null && score(metadata, candidate) >= 0
                        && isJapaneseLrclibRecord(candidate.toString())) {
                    preferJapanese = true;
                    break;
                }
            }
        }
        List<LyricLine> best = exactLines;
        int bestScore = exactLines.isEmpty() ? Integer.MIN_VALUE
                : 200 + japaneseLyricBonus(exactBody, preferJapanese);
        for (int i = 0; i < candidates.length(); i++) {
            org.json.JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            int score = score(metadata, candidate);
            if (score < 0) continue;
            score += japaneseLyricBonus(candidate.toString(), preferJapanese);
            if (score <= bestScore) continue;
            List<LyricLine> parsed = syncedLines(candidate.toString());
            if (!parsed.isEmpty()) {
                best = parsed;
                bestScore = score;
            }
        }
        return best.isEmpty()
                ? new SourceResult(best, "no synchronized lyrics in AMLL or LRCLIB")
                : new SourceResult(best, best.size() + " lines from LRCLIB search");
    }

    private static int japaneseLyricBonus(String json, boolean preferJapanese) {
        if (!preferJapanese || json == null || json.isEmpty()) return 0;
        try {
            String lyrics = new org.json.JSONObject(json).optString("syncedLyrics", "");
            int kana = 0;
            for (int i = 0; i < lyrics.length(); i++) {
                Character.UnicodeScript script = Character.UnicodeScript.of(lyrics.charAt(i));
                if (script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA) {
                    if (++kana >= 2) return 1000;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean isJapaneseLrclibRecord(String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            org.json.JSONObject record = new org.json.JSONObject(json);
            String language = record.optString("lang", record.optString("language", ""));
            return language.equalsIgnoreCase("ja") || language.toLowerCase(
                    java.util.Locale.ROOT).startsWith("ja-");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static TitleResolution resolveCanonicalTitle(TrackMetadata metadata) {
        return resolveCanonicalTitle(metadata, false);
    }

    private static TitleResolution resolveCanonicalTitle(TrackMetadata metadata,
                                                          boolean japaneseOnly) {
        String key = (japaneseOnly ? "ja|" : "any|")
                + normalize(metadata.title) + "|" + normalize(metadata.artist)
                + "|" + metadata.durationSeconds;
        long now = System.currentTimeMillis();
        TitleCacheEntry cached = TITLE_CACHE.get(key);
        if (cached != null && cached.expiresAt > now) return cached.resolution;
        if (cached != null) TITLE_CACHE.remove(key, cached);

        synchronized (TITLE_RESOLUTION_LOCK) {
            now = System.currentTimeMillis();
            cached = TITLE_CACHE.get(key);
            if (cached != null && cached.expiresAt > now) return cached.resolution;
            if (cached != null) TITLE_CACHE.remove(key, cached);

            TitleResolution resolution = resolveWithItunes(metadata, japaneseOnly);
            if (resolution == null) resolution = resolveWithMusicBrainz(metadata, japaneseOnly);
            if (TITLE_CACHE.size() >= 256) TITLE_CACHE.clear();
            TITLE_CACHE.put(key, new TitleCacheEntry(resolution, now + (resolution == null
                    ? NEGATIVE_TITLE_CACHE_MS : POSITIVE_TITLE_CACHE_MS)));
            return resolution;
        }
    }

    private static TitleResolution resolveWithItunes(TrackMetadata metadata,
                                                     boolean japaneseOnly) {
        if (System.currentTimeMillis() < itunesBlockedUntil) return null;
        try {
            throttle(ITUNES_THROTTLE, true, 3000L);
            String address = ITUNES_SEARCH + "?term=" + enc(metadata.title + " " + metadata.artist)
                    + "&country=JP&media=music&entity=song&limit=8";
            HttpResult response = providerGet(address, "iTunes");
            if (response.code != 200) {
                if (response.code == 403 || response.code == 429 || response.code == 503) {
                    itunesBlockedUntil = blockedUntil(response.retryAfter, 60_000L);
                }
                return null;
            }
            org.json.JSONArray results = new org.json.JSONObject(response.body)
                    .optJSONArray("results");
            if (results == null) return null;
            String bestTitle = null;
            String bestArtist = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject candidate = results.optJSONObject(i);
                if (candidate == null) continue;
                String title = trim(candidate.optString("trackName", ""));
                String artist = trim(candidate.optString("artistName", ""));
                if (title.isEmpty()
                        || normalize(cleanTitle(title)).equals(normalize(metadata.title))) continue;
                if (japaneseOnly && !containsJapaneseWriting(title)) continue;
                boolean artistMatches = artistsCompatible(artist, metadata.artist);
                long duration = Math.round(candidate.optLong("trackTimeMillis", 0) / 1000d);
                int durationDelta = durationDelta(metadata.durationSeconds, duration);
                if (metadata.durationSeconds > 0 && durationDelta > 4) continue;
                if (metadata.durationSeconds <= 0 && !artistMatches) continue;
                int candidateScore = (artistMatches ? 60 : 0)
                        + (metadata.durationSeconds > 0 ? 50 - durationDelta * 8 : 0)
                        + Math.max(0, 20 - i);
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestTitle = title;
                    bestArtist = artist;
                }
            }
            return bestTitle == null ? null
                    : new TitleResolution(bestTitle, bestArtist, "iTunes");
        } catch (Throwable t) {
            Xp.log("[MCLyric] iTunes title lookup failed: " + t);
            return null;
        }
    }

    private static TitleResolution resolveWithMusicBrainz(TrackMetadata metadata,
                                                          boolean japaneseOnly) {
        if (System.currentTimeMillis() < musicBrainzBlockedUntil) return null;
        try {
            throttle(MUSICBRAINZ_THROTTLE, false, 1100L);
            String query = "alias:\"" + lucenePhrase(metadata.title) + "\"";
            String address = MUSICBRAINZ_SEARCH + "?query=" + enc(query)
                    + "&fmt=json&limit=10";
            HttpResult response = providerGet(address, "MusicBrainz");
            if (response.code != 200) {
                if (response.code == 429 || response.code == 503) {
                    musicBrainzBlockedUntil = blockedUntil(response.retryAfter, 5000L);
                }
                return null;
            }
            org.json.JSONArray recordings = new org.json.JSONObject(response.body)
                    .optJSONArray("recordings");
            if (recordings == null) return null;
            String bestTitle = null;
            String bestArtist = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < recordings.length(); i++) {
                org.json.JSONObject candidate = recordings.optJSONObject(i);
                if (candidate == null) continue;
                String title = trim(candidate.optString("title", ""));
                if (title.isEmpty()
                        || normalize(cleanTitle(title)).equals(normalize(metadata.title))) continue;
                if (japaneseOnly && !containsJapaneseWriting(title)) continue;
                String artist = musicBrainzArtists(candidate.optJSONArray("artist-credit"));
                boolean artistMatches = artistsCompatible(artist, metadata.artist);
                long duration = Math.round(candidate.optLong("length", 0) / 1000d);
                int durationDelta = durationDelta(metadata.durationSeconds, duration);
                if (metadata.durationSeconds > 0 && durationDelta > 4) continue;
                if (metadata.durationSeconds <= 0 && !artistMatches) continue;
                int candidateScore = candidate.optInt("score", 0)
                        + (artistMatches ? 60 : 0)
                        + (metadata.durationSeconds > 0 ? 50 - durationDelta * 8 : 0)
                        - i;
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestTitle = title;
                    bestArtist = artist;
                }
            }
            return bestTitle == null ? null
                    : new TitleResolution(bestTitle, bestArtist, "MusicBrainz");
        } catch (Throwable t) {
            Xp.log("[MCLyric] MusicBrainz title lookup failed: " + t);
            return null;
        }
    }

    private static List<LyricLine> syncedLines(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            String lrc = new org.json.JSONObject(json).optString("syncedLyrics", "");
            return lrc.trim().isEmpty() ? Collections.<LyricLine>emptyList() : LyricParse.parse(lrc);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** Reject weak title matches and duration mismatches instead of showing the wrong song. */
    private static int score(TrackMetadata wanted, org.json.JSONObject candidate) {
        String title = normalize(cleanTitle(
                candidate.optString("trackName", candidate.optString("name", ""))));
        String artist = normalize(candidate.optString("artistName", ""));
        String wantedTitle = normalize(wanted.title);
        String wantedArtist = normalize(wanted.artist);
        double titleMatch = titleSimilarity(title, wantedTitle);
        if (title.isEmpty() || titleMatch < 0.70d) return -1;
        if (artist.isEmpty() || wantedArtist.isEmpty()) return -1;
        int artistMatch = artistMatchLength(artist, wantedArtist);
        if (artistMatch < requiredArtistMatchLength(artist, wantedArtist)) return -1;
        long duration = candidate.optLong("duration", 0);
        if (wanted.durationSeconds > 0 && (duration <= 0
                || Math.abs(duration - wanted.durationSeconds) > 2)) return -1;
        int score = (int) Math.round(titleMatch * 100)
                + (artist.equals(wantedArtist) ? 50 : 20 + Math.min(artistMatch, 20));
        if (!wanted.album.isEmpty() && normalize(candidate.optString("albumName", ""))
                .equals(normalize(wanted.album))) score += 10;
        return score;
    }

    /** Levenshtein similarity keeps minor title variations while rejecting unrelated songs. */
    private static double titleSimilarity(String first, String second) {
        if (first.equals(second)) return 1d;
        int longest = Math.max(first.length(), second.length());
        if (longest == 0) return 1d;
        if (first.isEmpty() || second.isEmpty()) return 0d;
        int[] previous = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1]
                        + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return 1d - (double) previous[second.length()] / longest;
    }

    /** Longest contiguous match, which avoids accepting artists with only scattered letters. */
    private static int artistMatchLength(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) return 0;
        int[] previous = new int[second.length() + 1];
        int best = 0;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            for (int j = 1; j <= second.length(); j++) {
                if (first.charAt(i - 1) == second.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    best = Math.max(best, current[j]);
                }
            }
            previous = current;
        }
        return best;
    }

    private static int requiredArtistMatchLength(String first, String second) {
        int shorter = Math.min(first.length(), second.length());
        if (shorter <= 2) return shorter;
        return containsIdeograph(first) || containsIdeograph(second) ? 2 : 3;
    }

    private static boolean containsIdeograph(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) return true;
        }
        return false;
    }

    private static boolean containsJapaneseKana(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) return true;
        }
        return false;
    }

    private static boolean containsJapaneseWriting(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static boolean artistsCompatible(String first, String second) {
        String normalizedFirst = normalize(first);
        String normalizedSecond = normalize(second);
        if (normalizedFirst.isEmpty() || normalizedSecond.isEmpty()) return false;
        int match = artistMatchLength(normalizedFirst, normalizedSecond);
        return match >= requiredArtistMatchLength(normalizedFirst, normalizedSecond);
    }

    private static int durationDelta(long wanted, long actual) {
        if (wanted <= 0) return 0;
        if (actual <= 0) return Integer.MAX_VALUE;
        long delta = Math.abs(wanted - actual);
        return delta >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) delta;
    }

    private static String musicBrainzArtists(org.json.JSONArray credits) {
        if (credits == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < credits.length(); i++) {
            org.json.JSONObject credit = credits.optJSONObject(i);
            if (credit == null) continue;
            String name = credit.optString("name", "");
            org.json.JSONObject artist = credit.optJSONObject("artist");
            if (name.isEmpty() && artist != null) name = artist.optString("name", "");
            if (name.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(name);
        }
        return result.toString();
    }

    private static String lucenePhrase(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void throttle(Object lock, boolean itunes, long intervalMs)
            throws InterruptedException {
        synchronized (lock) {
            long now = android.os.SystemClock.elapsedRealtime();
            long previous = itunes ? lastItunesRequest : lastMusicBrainzRequest;
            long wait = previous + intervalMs - now;
            if (wait > 0) Thread.sleep(wait);
            long started = android.os.SystemClock.elapsedRealtime();
            if (itunes) lastItunesRequest = started;
            else lastMusicBrainzRequest = started;
        }
    }

    private static long blockedUntil(String retryAfter, long fallbackMs) {
        long delay = fallbackMs;
        if (retryAfter != null) {
            try {
                delay = Math.max(1000L, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (Throwable ignored) {
                // HTTP-date Retry-After values are uncommon here; use the provider fallback.
            }
        }
        return System.currentTimeMillis() + delay;
    }

    private static HttpResult providerGet(String address, String provider) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", CLIENT_USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String retry = conn.getHeaderField("Retry-After");
            String body = code == 200 ? read(conn.getInputStream()) : null;
            if (code != 200) {
                Xp.log("[MCLyric] " + provider + " -> HTTP " + code
                        + (retry == null ? "" : ", retry-after=" + retry));
            }
            return new HttpResult(code, body, retry);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "");
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String httpGet(String address) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", CLIENT_USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                String retry = conn.getHeaderField("Retry-After");
                Xp.log("[MCLyric] LRCLIB -> HTTP " + code
                        + (retry == null ? "" : ", retry-after=" + retry));
                return null;
            }
            return read(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void onMain(final Callback cb, final List<LyricLine> lines,
                               final String why) {
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                cb.onLines(lines, why);
            }
        });
    }

    /** What the mirrors said about one directory. Every mirror is asked at once. */
    static Answer fetch(String dir, String id) {
        final BlockingQueue<Answer> answers = new LinkedBlockingQueue<>();
        for (final String[] m : MIRRORS) {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    answers.offer(one(m, dir, id));
                }
            });
        }
        boolean sawMissing = false;
        int received = 0;
        // The budget is per directory and generous only relative to the per-request timeouts,
        // which are what actually bound this: whichever mirror answers first ends it, and four
        // that cannot be reached end it at the last of them.
        long deadline = android.os.SystemClock.uptimeMillis() + 7000L;
        while (true) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                break;
            }
            Answer a;
            try {
                a = answers.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (a == null) {
                break;
            }
            received++;
            if (a.status == FOUND) {
                return a;
            }
            if (a.status == MISSING) {
                sawMissing = true;
            }
            // A negative answer from every mirror is conclusive.  Previously this path waited
            // for the whole seven-second budget even after all four mirrors had already said
            // 404, delaying LRCLIB needlessly for every AMLL coverage miss.
            if (received == MIRRORS.length) {
                break;
            }
        }
        // Distinguishing the two is what keeps a wrong directory from looking like a dead
        // network: a 404 means this file is not in this directory and the next one is worth
        // asking, where a timeout means asking again will only cost another timeout.
        return new Answer(sawMissing ? MISSING : UNREACHABLE, null);
    }

    private static Answer one(String[] mirror, String dir, String id) {
        String url = String.format(mirror[1], dir, id);
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // Short on purpose. A lyric that arrives after the lock screen has been put away is
            // worth nothing, and four mirrors running at once means the slow one is never waited
            // for anyway - these bound the failure, not the success.
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            int code = conn.getResponseCode();
            long ms = android.os.SystemClock.uptimeMillis() - started;
            if (code == 404) {
                Xp.log("[MCLyric] " + mirror[0] + " -> 404 in " + ms + "ms (" + dir + ")");
                return new Answer(MISSING, null);
            }
            if (code != 200) {
                Xp.log("[MCLyric] " + mirror[0] + " -> HTTP " + code + " in " + ms + "ms");
                return new Answer(UNREACHABLE, null);
            }
            String body = read(conn.getInputStream());
            Xp.log("[MCLyric] " + mirror[0] + " -> 200, " + body.length() + " chars in " + ms
                    + "ms (" + dir + "/" + id + ")");
            return new Answer(FOUND, body);
        } catch (Throwable t) {
            Xp.log("[MCLyric] " + mirror[0] + " failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            return new Answer(UNREACHABLE, null);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
