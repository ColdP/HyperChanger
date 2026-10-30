// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lyrics for a song nobody published an id for: found by name, fetched from NetEase.
 *
 * This is the last of the three sources and the only one that covers a player which publishes
 * nothing but a title. The other two need something from the session - the player's own
 * lyricInfo, or a MEDIA_ID the AMLL database happens to be keyed by - and measured on this
 * device the two together cover two of the four installed players. NetEase official and Salt
 * publish neither, and no amount of waiting makes an id appear.
 *
 * The reason this is worth a network round trip at all is coverage, and the numbers are not
 * close: AMLL is a community-built set of about 69,000 songs, and both of the first two songs
 * tried against it on this device - neither of them obscure - were 404s. NetEase is the whole
 * catalogue. Its search and lyric endpoints are the ones its old web client used: plain GETs,
 * no signature, no cookie, no account. Measured from this phone, search answers in ~250ms and
 * the lyrics in ~110ms on a reused connection, so ~320ms end to end - faster, as it happens,
 * than the GitHub mirrors the id route races.
 *
 * What it buys with that is a matching problem the id route does not have. An id is exact; a
 * name is not, and the wrong lyrics are worse than none - they scroll, they are confidently
 * mistimed, and there is nothing on screen to say they belong to another song. So the rule
 * here is that a candidate has to be proven, not merely ranked: see pick().
 */
public final class NcmLyrics {

    private NcmLyrics() {
    }

    /** The old web client's endpoints. No key, no signature - but also no promise they stay. */
    private static final String SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=1&limit=10";
    private static final String LYRIC =
            "https://music.163.com/api/song/lyric/v1?id=%s&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0"
                    + "&ytv=0&yrv=0";
    /** The album search, and the album itself: the route taken when the song search proves nothing. */
    private static final String ALBUM_SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=10&limit=10";
    private static final String ALBUM = "https://music.163.com/api/album/%s";

    /** What the session says about the song, reduced to the four things a match can use. */
    public static final class Query {
        final String title;
        final String artist;
        final String album;
        final long durationMs;
        /**
         * QQ 音乐's own id for the song (its MEDIA_ID), or null. With it QQ's lyric is asked for
         * by the id, no search: the search is where QQ's renamed songs got lost (QqLyrics).
         */
        final String qqId;

        Query(String title, String artist, String album, long durationMs) {
            this(title, artist, album, durationMs, null);
        }

        Query(String title, String artist, String album, long durationMs, String qqId) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.qqId = qqId;
        }

        /** Stable across a track's lifetime, so it can key a cache. */
        String key() {
            return title + '|' + artist + '|' + album + '|' + durationMs + '|' + qqId;
        }

        @Override
        public String toString() {
            return "title=" + q(title) + " artist=" + q(artist) + " album=" + q(album)
                    + " dur=" + durationMs;
        }

        private static String q(String s) {
            return s == null || s.isEmpty() ? "(none)" : '"' + s + '"';
        }
    }

    /** The session's metadata, read out and handed to build() to be made searchable. */
    public static Query queryOf(MediaController c) {
        if (c == null) {
            return null;
        }
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return null;
        }
        if (md == null) {
            return null;
        }
        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
        if (artist.isEmpty()) {
            artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        long dur = 0L;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
        }
        // A player singing into TITLE: the song and singer as ARTIST spells them (TrackName).
        try {
            String[] sung = TrackName.searchName(c.getPackageName(), md);
            if (sung != null) {
                title = sung[0];
                artist = sung[1];
            }
        } catch (Throwable ignored) {
        }
        String pkg = c.getPackageName();
        boolean qq = pkg != null && pkg.startsWith("com.tencent.qqmusic");
        // QQ 音乐 publishes 60000 for the length of every song now, the whole way through
        // (2026-10-07, 打上花火, aLIEz, 夢灯籠 - all a minute). Taken at its word, every right
        // answer lost 30 points for being four minutes long and nothing passed.
        if (qq && dur == QQ_PLACEHOLDER_MS) {
            dur = 0L;
        }
        Query q = build(title, artist, album, dur);
        String qqId = qq ? LyricSource.idOf(c) : null;
        return q == null || qqId == null ? q
                : new Query(q.title, q.artist, q.album, q.durationMs, qqId);
    }

    /** What QQ 音乐 publishes as a song's duration when it is not saying. */
    private static final long QQ_PLACEHOLDER_MS = 60_000L;

    /**
     * The four raw fields, rearranged into something searchable.
     *
     * Split out from queryOf so the diagnostic can drive it with fields typed by hand: this is
     * where every player-specific quirk lives, so it is the part a "no lyrics for this song"
     * report needs to be able to re-run.
     */
    public static Query build(String title, String artist, String album, long dur) {
        title = title == null ? "" : title.trim();
        artist = artist == null ? "" : artist.trim();
        album = album == null ? "" : album.trim();
        title = TrackName.undecorated(title);
        // 汽水 sings into TITLE as well, with "歌名 — 歌手" in ARTIST - the song first, an em dash
        // between. Searched as published, it asked for a song called "作曲：AKA时空恋人" (#56).
        java.util.List<TrackName.Split> em = TrackName.splits(artist);
        if (!em.isEmpty() && artist.contains(TrackName.EM)) {
            title = em.get(0).song;
            artist = em.get(0).singer;
        }
        // Salt Player publishes "Artist - Song" in ARTIST, so the song name is in there as well
        // as - or instead of - TITLE. The first " - " splits it: a dash inside the song name
        // ("i'm so tired... (Stripped - Live in LA)") comes after the one that matters, and
        // taking the first keeps the whole remainder as the title.
        int dash = artist.indexOf(" - ");
        if (dash > 0) {
            String head = artist.substring(0, dash).trim();
            String tail = artist.substring(dash + 3).trim();
            if (!head.isEmpty() && !tail.isEmpty()) {
                if (title.isEmpty()) {
                    title = tail;
                    artist = head;
                } else if (tail.equals(title)) {
                    // The tail is only repeating what we already know; drop it so the search
                    // gets a clean artist. An artist whose own name contains a dash and whose
                    // title is published properly keeps it.
                    artist = head;
                } else if (tail.equals(album)) {
                    // ARTIST and ALBUM agree on a name that TITLE does not carry - which is Salt
                    // Player with its status-bar lyric switched on, where TITLE is the line being
                    // sung. Searched as published it asks NetEase for a song called
                    // "抹去雨水双眼无故地仰望": measured 2026-09-22 the right track came back
                    // anyway, on the artist, with its duration matching to the millisecond, and
                    // pick() threw it away because the name scored zero. Two fields against one,
                    // and the two that agree are the two that hold still through a song.
                    title = tail;
                    artist = head;
                }
            }
        }
        // Only now, and only as a last resort: a single's album is the single, which is true
        // often enough to be worth trying and not often enough to be tried first. It was tried
        // first once, and Salt playing 逃跑计划's 夜空中最亮的星 off the album 世界 went looking
        // for a song called 世界 and found nothing.
        if (title.isEmpty()) {
            title = album;
        }
        if (title.isEmpty() && artist.isEmpty()) {
            return null;
        }
        return new Query(title, artist, album, dur);
    }

    private static String str(MediaMetadata md, String key) {
        try {
            String s = md.getString(key);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** What came back for one song: the timed body, and the translation if there is one. */
    static final class Found {
        final String id;
        final String body;
        /** A plain LRC of the translated lines, or null. Merged in by the parser. */
        final String translation;
        /** True when body is word-timed yrc rather than line-timed lrc. */
        final boolean words;
        /** A plain LRC of the romanisation - Japanese and Korean songs carry one - or null. */
        final String roma;

        Found(String id, String body, String translation, boolean words) {
            this(id, body, translation, words, null);
        }

        Found(String id, String body, String translation, boolean words, String roma) {
            this.id = id;
            this.body = body;
            this.translation = translation;
            this.words = words;
            this.roma = roma;
        }
    }

    /**
     * Cache, keyed by the session's own description of the track.
     *
     * Misses are cached as well as hits, and that is the more useful half: a song NetEase does
     * not have is looked up once and then costs nothing for the rest of its play, where without
     * this every screen-on would spend the round trip again to learn the same thing. Small
     * because the entries are whole lyric files and this lives in SystemUI.
     */
    private static final int CACHE_MAX = 16;
    private static final Map<String, Found> CACHE =
            new LinkedHashMap<String, Found>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** A miss, so the map can hold "asked, nothing there" as distinct from "never asked". */
    private static final Found NONE = new Found(null, null, null, false);

    /**
     * Search, match, fetch. Blocking: callers are already on a worker.
     *
     * Returns null when nothing could be proven to be this song - which is a normal outcome and
     * not an error, and is cached as such.
     */
    static Found load(Query q) {
        if (q == null) {
            return null;
        }
        String key = q.key();
        synchronized (CACHE) {
            Found hit = CACHE.get(key);
            if (hit != null) {
                Xp.log("[MCNcm] cached: " + (hit == NONE ? "no match" : hit.id));
                return hit == NONE ? null : hit;
            }
        }
        Found got = null;
        try {
            got = fetch(q);
        } catch (Throwable t) {
            Xp.w("[MCNcm] failed: " + t);
        }
        if (got != null) {
            synchronized (CACHE) {
                CACHE.put(key, got);
            }
            return got;
        }
        // Nothing found - and whether that is worth remembering depends on why.
        //
        // A thrown request is not cached: the next attempt may be on a working network, and
        // caching the network's bad minute as "this song has no lyrics" would outlast it. A
        // search that answered with a page of wrong songs is the same mistake wearing a better
        // disguise - it looks exactly like an honest miss from here, and costs the song its
        // lyrics for the whole play. One extra request, once every half minute at most, buys
        // the difference. See searchIsHonest().
        if (networkFailed) {
            return null;
        }
        if (!searchIsHonest()) {
            Xp.log("[MCNcm] not remembering the miss for " + q + ": the search is not answering "
                    + "honestly right now");
            return null;
        }
        synchronized (CACHE) {
            CACHE.put(key, NONE);
        }
        return got;
    }

    /** Set by the last request to fail on the network rather than on its answer. */
    private static volatile boolean networkFailed;

    /**
     * Whether the search is answering honestly, asked only when it matters.
     *
     * The endpoint's answer to being searched too much is not an error but a page of plausible
     * wrong songs: covers with the right title, instrumentals, other artists' songs of the same
     * name - everything except the recording asked for. From here that is indistinguishable from
     * a song the catalogue does not have, and the two want opposite treatment. A song that is
     * genuinely absent should be remembered as absent, or every screen-on spends the round trip
     * again; a song hidden by a bad minute must NOT be, because the minute passes and the miss
     * outlives it - cached per track, one bad minute costs that song its lyrics for the whole
     * play.
     *
     * So the question is put to a song whose right answer is known. 七里香 is on the service and
     * its id is 186001; a search that does not return it is not telling the truth, whatever it
     * says about anything else. Measured 2026-09-22: in this state the search for it answered
     * with ten covers, a music box version and two "pop beat" backing tracks, while the lyric
     * endpoint handed over the real thing by id without complaint - the block is on searching,
     * not on the catalogue.
     */
    private static final String HEALTH_TERMS = "七里香 周杰伦";
    private static final String HEALTH_ID = "186001";
    /** A wider page than the lookups use: honest or not, the answer is somewhere in the list. */
    private static final String HEALTH_SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=1&limit=30";

    /**
     * How long one answer about the endpoint's honesty stands.
     *
     * Long enough that a run of misses costs one extra request rather than one each, short
     * enough to follow the state changing - it clears on its own, and has taken anywhere from a
     * few minutes to over forty.
     */
    private static final long HEALTH_TTL_MS = 30000L;
    private static volatile long healthAt;
    private static volatile boolean healthy = true;

    /** True when the search can be believed - including when we could not find out. */
    private static boolean searchIsHonest() {
        long now = android.os.SystemClock.uptimeMillis();
        if (healthAt != 0L && now - healthAt < HEALTH_TTL_MS) {
            return healthy;
        }
        boolean ok = askHealth();
        healthy = ok;
        healthAt = now;
        return ok;
    }

    private static boolean askHealth() {
        try {
            String json = get(String.format(HEALTH_SEARCH,
                    URLEncoder.encode(HEALTH_TERMS, "UTF-8")));
            // A request that did not arrive says nothing about honesty, and answering "lying"
            // to it would stop every miss being remembered for as long as the network is down.
            if (json == null) {
                return true;
            }
            org.json.JSONArray songs = new org.json.JSONObject(json).getJSONObject("result")
                    .getJSONArray("songs");
            for (int i = 0; i < songs.length(); i++) {
                // Compared as an id, not as text anywhere in the body: the same digits turn up
                // as a duration and as other songs' ids, and a substring test would call a page
                // of decoys honest.
                if (HEALTH_ID.equals(String.valueOf(songs.getJSONObject(i).optLong("id")))) {
                    return true;
                }
            }
            Xp.log("[MCNcm] the search is serving decoys: " + HEALTH_TERMS + " came back without "
                    + HEALTH_ID + " in " + songs.length() + " results");
            return false;
        } catch (Throwable t) {
            // Same argument as a null body: not knowing is not evidence of lying.
            Xp.log("[MCNcm] could not check the search's honesty: " + t);
            return true;
        }
    }

    /**
     * The search response for one set of terms, so it can be fetched before it is needed.
     *
     * This is the half of the lookup that can be done early, and the only one. The search takes
     * the song's name and its first artist and nothing else - see terms() - which is exactly what
     * a play queue's item carries, while the choosing below needs the duration, which no queue
     * here publishes. So the prefetch asks the question and the real lookup, which by then has
     * the duration, answers it: the matching runs at full strength on results that are already
     * in hand. Nothing about which song gets picked changes; only when the bytes arrived does.
     *
     * Two minutes because that is far longer than the gap it exists to cover - a track starting
     * after the queue said it would - and short enough that a spell of the endpoint answering
     * with unrelated songs cannot be held over a song for long. See the re-ask in fetch().
     */
    private static final int SEARCH_CACHE_MAX = 8;
    private static final long SEARCH_TTL_MS = 120000L;

    private static final class Searched {
        final String json;
        final long at;

        Searched(String json, long at) {
            this.json = json;
            this.at = at;
        }
    }

    private static final Map<String, Searched> SEARCHES =
            new LinkedHashMap<String, Searched>(SEARCH_CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Searched> eldest) {
                    return size() > SEARCH_CACHE_MAX;
                }
            };

    /** The search response for these terms, from the prefetch if it got there first. */
    private static String search(String terms, String url) throws Exception {
        synchronized (SEARCHES) {
            Searched s = SEARCHES.get(terms);
            if (s != null && android.os.SystemClock.uptimeMillis() - s.at < SEARCH_TTL_MS) {
                Xp.log("[MCNcm] search for \"" + terms + "\" was already done");
                return s.json;
            }
        }
        String json = get(url);
        if (json != null) {
            synchronized (SEARCHES) {
                SEARCHES.put(terms, new Searched(json, android.os.SystemClock.uptimeMillis()));
            }
        }
        return json;
    }

    private static void forgetSearch(String terms) {
        synchronized (SEARCHES) {
            SEARCHES.remove(terms);
        }
    }

    /**
     * Runs the search for a track that has not started yet, and keeps the answer.
     *
     * Takes the two fields rather than a Query because a prefetch has no duration to build one
     * with - and needs none, which is the whole reason this half can be done early. Blocking;
     * the caller is a prefetch thread.
     */
    static void warmSearch(String title, String artist) {
        try {
            Query q = build(title, artist, null, 0L);
            if (q == null) {
                return;
            }
            String terms = terms(q);
            if (terms.isEmpty()) {
                return;
            }
            synchronized (SEARCHES) {
                Searched s = SEARCHES.get(terms);
                if (s != null && android.os.SystemClock.uptimeMillis() - s.at < SEARCH_TTL_MS) {
                    return;
                }
            }
            long t0 = android.os.SystemClock.uptimeMillis();
            String json = get(String.format(SEARCH, URLEncoder.encode(terms, "UTF-8")));
            if (json == null) {
                return;
            }
            synchronized (SEARCHES) {
                SEARCHES.put(terms, new Searched(json, android.os.SystemClock.uptimeMillis()));
            }
            Xp.log("[MCNcm] searched \"" + terms + "\" ahead of time in "
                    + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Xp.w("[MCNcm] searching ahead failed: " + t);
        }
    }

    /** For op queue: whether reading ahead has anything in hand, and who we last thought we were
     * talking to. The honesty is reported as last decided, never asked for here - a diagnostic
     * that sends a request of its own would be one more request against the thing it is
     * measuring. "unknown" means nothing has missed yet, which is the healthy case. */
    public static String describeSearches() {
        synchronized (SEARCHES) {
            return "searches=" + SEARCHES.size() + " honest="
                    + (healthAt == 0L ? "unknown" : String.valueOf(healthy));
        }
    }

    private static Found fetch(Query q) throws Exception {
        networkFailed = false;
        String terms = terms(q);
        if (terms.isEmpty()) {
            return null;
        }
        long started = android.os.SystemClock.uptimeMillis();
        String url = String.format(SEARCH, URLEncoder.encode(terms, "UTF-8"));
        String json = search(terms, url);
        if (json == null) {
            return null;
        }
        String id = pick(json, q);
        // The endpoint answers with songs that have nothing to do with the query for minutes at a
        // time: measured 2026-09-20, "勇敢 张惠妹" coming back as a page of FM-84 and then as itself
        // again a few minutes later, same phone, same headers, same URL. A search that proves
        // nothing is worth asking twice and no more - the miss is what gets cached, so without
        // this a bad minute on the other side costs the song its lyrics for the whole track, and
        // with it a song that genuinely is not there costs one extra request, once.
        if (id == null && q.durationMs > 0 && !norm(q.title).isEmpty()) {
            Xp.log("[MCNcm] nothing in the results for " + q + "; asking again");
            // get, not search: the point of asking again is to get a DIFFERENT answer, and a
            // cached one is the same answer by definition. The entry goes too - whatever it
            // holds has just been shown to prove nothing, and leaving it would hand the same
            // uselessness to the next lookup that searches these terms.
            forgetSearch(terms);
            json = get(url);
            if (json == null) {
                return null;
            }
            id = pick(json, q);
        }
        if (id == null) {
            // The search answered, and what it answered with was not this song - which it also
            // does when it is about to answer with something quite different. See byAlbum.
            id = byAlbum(q);
        }
        if (id == null) {
            // Whatever is held for these terms has now been shown to prove nothing, by every
            // route. Dropping it matters most in the case it is hardest to see: a page of
            // decoys kept for the two minutes this cache runs would hand the same page to the
            // read-ahead and to every other song that searches the same terms.
            forgetSearch(terms);
            Xp.log("[MCNcm] nothing matched " + q + " (searched \"" + terms
                    + "\", and its album)");
            return null;
        }
        return lyricsOf(id, started);
    }

    /** One song's lyric by its id, with whatever translation and romanisation it carries. */
    private static Found lyricsOf(String id, long started) throws Exception {
        String lyric = get(String.format(LYRIC, id));
        if (lyric == null) {
            return null;
        }
        org.json.JSONObject o = new org.json.JSONObject(lyric);
        String yrc = body(o, "yrc");
        String lrc = body(o, "lrc");
        String tlyric = body(o, "tlyric");
        String romalrc = body(o, "romalrc");
        String use = yrc != null ? yrc : lrc;
        if (use == null) {
            Xp.log("[MCNcm] " + id + " has no lyrics at all");
            return null;
        }
        Xp.log("[MCNcm] " + id + " -> " + (yrc != null ? "yrc" : "lrc") + " " + use.length()
                + " chars" + (tlyric != null ? " + translation" : "")
                + (romalrc != null ? " + romanisation" : "") + " in "
                + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        return new Found(id, use, tlyric, yrc != null, romalrc);
    }

    private static final String LYRIC_SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=1006&limit=10";

    /**
     * The song that sings `line`, found by its words rather than its name - the one of them
     * nearest `durationMs` long, and within LYRIC_SEARCH_SLACK_MS of it.
     *
     * For a song whose name cannot be matched across languages: Apple's Chinese storefront
     * publishes サザンオールスターズ's 真夏の果実 as "Manatsu No Kajitsu" by 南天群星, which no name
     * search finds, while one sung line of it finds the song and every cover of it (measured
     * 2026-10-06). Asked only to borrow a translation for a lyric already in hand, whose own lines
     * then decide whether the copy found is the same song (LyricParse.borrowTranslations).
     */
    static Found byLyric(String line, long durationMs) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        try {
            long started = android.os.SystemClock.uptimeMillis();
            String json = get(String.format(LYRIC_SEARCH, URLEncoder.encode(line.trim(), "UTF-8")));
            if (json == null) {
                return null;
            }
            org.json.JSONArray songs = new org.json.JSONObject(json).getJSONObject("result")
                    .getJSONArray("songs");
            String best = null;
            long bestGap = Long.MAX_VALUE;
            for (int i = 0; i < songs.length(); i++) {
                org.json.JSONObject song = songs.optJSONObject(i);
                if (song == null) {
                    continue;
                }
                long dur = song.optLong("duration", song.optLong("dt", 0L));
                long gap = durationMs > 0 && dur > 0 ? Math.abs(dur - durationMs) : Long.MAX_VALUE - 1;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = String.valueOf(song.optLong("id"));
                }
            }
            if (best == null || (durationMs > 0 && bestGap > LYRIC_SEARCH_SLACK_MS)) {
                Xp.log("[MCNcm] nothing sings \"" + line + "\" near " + durationMs + "ms");
                return null;
            }
            Xp.log("[MCNcm] \"" + line + "\" is sung by " + best + ", " + bestGap + "ms off");
            return lyricsOf(best, started);
        } catch (Throwable t) {
            Xp.w("[MCNcm] lyric search failed: " + t);
            return null;
        }
    }

    /** How far from our duration a song found by its words may be: a remaster, a longer fade. */
    private static final long LYRIC_SEARCH_SLACK_MS = 15000L;

    /** One of the response's lyric slots, or null when it is absent or empty. */
    private static String body(org.json.JSONObject o, String field) {
        org.json.JSONObject slot = o.optJSONObject(field);
        return slot == null ? null : str(slot, "lyric");
    }

    /**
     * A string field, or null when the field is absent, null, or not a string at all.
     *
     * Deliberately not optString(key, ""), which is not the same question on the two platforms
     * this code runs on. On the JVM a JSON null answers the fallback; on Android it is a sentinel
     * object, and optString answers String.valueOf(that) - the four characters "null" - so a
     * field that is explicitly null reads as a string that says "null".
     *
     * That is not a hypothetical: it is what this endpoint sends for the yrc slot of every song
     * whose lyric is the newer rich kind, and 带你飞 is one. The "null" came back as a lyric, and
     * being neither null nor blank it won the yrc-over-lrc choice in fetch() over the lrc beside
     * it, which held all 24 lines of the song. What the lock screen got was a four-character body
     * that parses to nothing, and because the answer is cached per track, the song played out
     * with no lyrics at all. Measured against the live response on the device, not reasoned.
     */
    static String str(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof String)) {
            return null;
        }
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Title and artist, which is what the search endpoint ranks on. */
    static String terms(Query q) {
        return joined(TrackName.untranslated(q.title), TrackName.unaliased(firstArtist(q.artist)));
    }

    /** The album's name and its artist, which is what the album search ranks on. */
    private static String albumTerms(Query q) {
        return joined(q.album, TrackName.unaliased(firstArtist(q.artist)));
    }

    /** Two search terms, either of which may be missing, in the one order the endpoint wants. */
    private static String joined(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        return b == null || b.isEmpty() ? a : a + ' ' + b;
    }

    /**
     * The first credited name out of a session's artist string.
     *
     * Only the first, for a search: "LAUV/Troye Sivan" as a whole matches nothing, and the search
     * ranks on the primary artist anyway. Whether a result is by this artist is a different
     * question and is asked of the whole string - see byArtist.
     */
    static String firstArtist(String artist) {
        int slash = artist.indexOf('/');
        return (slash > 0 ? artist.substring(0, slash) : artist).trim();
    }

    /** The songs out of a search response, matched - or null when the response holds none. */
    private static String pick(String json, Query q) {
        try {
            return choose(new org.json.JSONObject(json).getJSONObject("result")
                    .getJSONArray("songs"), q);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Which of these songs is the session's recording - or none of them.
     *
     * Asked of both ways in: the song search's results, and an album's own track list, which is
     * the same question put to a shorter and much more certain list.
     *
     * Scored by LyricMatch, the one rule every by-name source now shares (and HyperLyrics
     * Enhanced's, so the two modules agree). What this used to do by hand it still does: the
     * title has to match (新的心跳 against 多远都要在一起, same album, 441ms nearer), another
     * singer's song of the same name never passes (粟丹sudan's piano 余波荡漾), a live take 25s
     * off its studio one is marked down past passing, and the album carries a copy that a
     * different pressing puts a few seconds out (勇敢, 4.8s, on its own album).
     *
     * Nothing matching is a real answer, and a better one than the wrong song: the caller shows
     * no lyrics, which is what it did before this source existed, and byAlbum gets a second
     * chance at it.
     */
    private static String choose(org.json.JSONArray songs, Query q) {
        if (songs == null) {
            return null;
        }
        if (norm(q.title).isEmpty()) {
            Xp.log("[MCNcm] session publishes no title to match on");
            return null;
        }
        java.util.List<LyricMatch.Candidate> cands = new java.util.ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            org.json.JSONObject s = songs.optJSONObject(i);
            if (s == null) {
                continue;
            }
            // A result with no name is not a candidate: scored, "null" would only ever have been
            // a title that matches nothing. See str() for why it has to be asked this way.
            String name = str(s, "name");
            if (name == null) {
                continue;
            }
            org.json.JSONObject al = s.optJSONObject("album");
            // The search calls it "artists", the album's own track list "ar"; both carry names.
            org.json.JSONArray ar = s.optJSONArray("artists");
            if (ar == null) ar = s.optJSONArray("ar");
            StringBuilder artists = new StringBuilder();
            for (int k = 0; ar != null && k < ar.length(); k++) {
                org.json.JSONObject a = ar.optJSONObject(k);
                String n = a == null ? null : str(a, "name");
                if (n == null) continue;
                if (artists.length() > 0) artists.append('/');
                artists.append(n);
            }
            long dur = s.optLong("duration", 0L);
            if (dur <= 0) dur = s.optLong("dt", 0L);
            cands.add(new LyricMatch.Candidate(String.valueOf(s.optLong("id", 0L)), name,
                    artists.toString(), al == null ? null : str(al, "name"), dur, null));
        }
        LyricMatch.Pick p = LyricMatch.best(cands, new LyricMatch.Wanted(q));
        if (p.candidate != null) {
            Xp.log("[MCNcm] best " + p.candidate + " scored " + p.score
                    + (p.passes() ? "" : ", under " + LyricMatch.PASS_SCORE));
        }
        return p.passes() ? p.candidate.id : null;
    }

    /**
     * The same song, looked for inside the album the session names.
     *
     * The search endpoint does not always answer with what it would have answered a minute
     * earlier, and nothing in the response says which kind of answer it is. Measured 2026-09-20,
     * playing 田馥甄's 余波荡漾: the search came back as that title by other artists and as that
     * artist's other songs, with the song itself missing, while the album it is on answered for it
     * 44 milliseconds from the session's duration and carries 27 lines plus a word-timed copy of
     * them. The same query answered properly, three times running, a few minutes later.
     *
     * An album has nothing to rank, which is why it is the route taken here: ask for one by name
     * and artist and the answer is its track list, every track with its own duration. Two
     * requests, spent on the path where the answer so far is that there is nothing.
     */
    private static String byAlbum(Query q) {
        if (q.album.isEmpty() || q.title.isEmpty() || q.durationMs <= 0) {
            // Nothing to look an album up by, so this is not a second chance at anything.
            return null;
        }
        try {
            String json = get(String.format(ALBUM_SEARCH,
                    URLEncoder.encode(albumTerms(q), "UTF-8")));
            if (json == null) {
                return null;
            }
            String album = albumOf(json, q);
            if (album == null) {
                Xp.log("[MCNcm] no album of " + q.artist + "'s called " + q.album);
                return null;
            }
            json = get(String.format(ALBUM, album));
            if (json == null) {
                return null;
            }
            org.json.JSONObject found = new org.json.JSONObject(json).optJSONObject("album");
            String id = found == null ? null : choose(found.optJSONArray("songs"), q);
            Xp.log("[MCNcm] album " + album + " (" + q.album + ") -> " + id);
            return id;
        } catch (Throwable t) {
            Xp.w("[MCNcm] album lookup failed: " + t);
            return null;
        }
    }

    /**
     * Which of the album search's results is the album the session names - or none of them.
     *
     * Its name first and its artist second, the way choose() asks it of a song and for the same
     * reason: more than one artist has an album called 日常, and the session has said whose.
     */
    private static String albumOf(String json, Query q) {
        org.json.JSONArray albums;
        try {
            albums = new org.json.JSONObject(json).getJSONObject("result").getJSONArray("albums");
        } catch (Throwable t) {
            return null;
        }
        String wanted = norm(q.album);
        String best = null;
        int bestScore = 0;
        for (int i = 0; i < albums.length(); i++) {
            org.json.JSONObject a = albums.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = str(a, "name");
            if (name == null || !byArtist(q.artist, a)) {
                continue;
            }
            int score = nameScore(wanted, norm(name));
            if (score > bestScore) {
                best = String.valueOf(a.optLong("id", 0L));
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * 4 when the names are the same, 2 when one contains the other, 0 when they are unrelated.
     *
     * Asked of titles and of album names alike, which are the same problem: one name typed by two
     * catalogues.
     */
    static int nameScore(String wanted, String got) {
        if (got.isEmpty()) {
            return 0;
        }
        if (wanted.equals(got)) {
            return 4;
        }
        return wanted.contains(got) || got.contains(wanted) ? 2 : 0;
    }

    /**
     * Whether this result is by the artist the session says it is.
     *
     * The search ranks on title and artist together, but it returns other people's songs under the
     * same title, and until this existed an exact title with a duration inside the window was
     * enough to be chosen whatever name was on it. Measured 2026-09-20, playing 田馥甄's 余波荡漾:
     * the search answered with a page of that title by other artists and a page of that artist's
     * other songs, and not with the song, which is on the album it is on. pick() took 粟丹sudan's
     * piano version - exact title, 1673ms from the session. That one happened to carry no lyric,
     * so the screen stayed empty; the copy 158ms from the session was a cover, and it would have
     * been shown had the ranking put it first. Another singer's name is not a near miss.
     *
     * Compared with the latitude the titles get - everything that varies between catalogues
     * removed, and either name containing the other - which is what lets a session's
     * "LAUV/Troye Sivan" match a catalogue crediting only one of them.
     *
     * With nothing on either side to compare, the answer is yes: this can rule a candidate out,
     * it cannot find one, and a player publishing no artist should not lose its lyrics to it.
     */
    private static boolean byArtist(String wanted, org.json.JSONObject s) {
        String want = norm(wanted);
        org.json.JSONArray ar = s.optJSONArray("artists");
        if (want.isEmpty() || ar == null || ar.length() == 0) {
            return true;
        }
        for (int i = 0; i < ar.length(); i++) {
            org.json.JSONObject a = ar.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String got = norm(str(a, "name"));
            if (!got.isEmpty() && (got.contains(want) || want.contains(got))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A title with everything that varies between catalogues taken out.
     *
     * Case, spaces, punctuation and brackets all differ for the same song depending on who typed
     * it in - "i'm so tired... (Stripped - Live in LA)" against "i'm so tired...(Stripped - Live
     * in LA)" is the same recording with one space missing. What is left is letters and digits,
     * in order, which is enough to tell two titles apart and not enough to be upset by a comma.
     *
     * The script is the other thing that varies, and it is folded first - see folded().
     */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        s = folded(s);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * The same name written in simplified Chinese, because the two catalogues do not agree on the
     * script and neither of them says so.
     *
     * Measured 2026-09-20, playing 林忆莲's 是你治癒了我的孤单: Apple Music publishes the traditional
     * 癒 and NetEase the simplified 愈, and the one candidate that was the right song - 681ms from
     * the session's duration, right artist - differed from the session's title by that character
     * alone. Nothing else about them disagreed, norm() read them as two different songs, and the
     * song played with no lyrics while its lyrics sat one row down the same response. The split
     * runs through the names of people too (林憶蓮 against 林忆莲), which is why the whole name is
     * folded rather than the title.
     *
     * ICU's own transform, which this device has and which does exactly this. If a build ever
     * lacks it the name is compared as it is: that is the comparison that was here before, and it
     * is a better failure than every song with a character variant losing its lyrics.
     */
    static String folded(String s) {
        android.icu.text.Transliterator t = TRANSLIT;
        if (t == null) {
            return s;
        }
        try {
            // Not thread-safe, and the lookups arrive on more than one thread.
            synchronized (t) {
                return t.transliterate(s);
            }
        } catch (Throwable e) {
            return s;
        }
    }

    /** Built once: getInstance parses the rules, and norm() runs a dozen times per candidate. */
    private static final android.icu.text.Transliterator TRANSLIT = transliterator();

    private static android.icu.text.Transliterator transliterator() {
        try {
            return android.icu.text.Transliterator.getInstance("Traditional-Simplified");
        } catch (Throwable t) {
            Xp.log("[MCNcm] no traditional-to-simplified transform here: " + t);
            return null;
        }
    }

    /**
     * One GET, and the record of whether it arrived.
     *
     * The request itself is Http's, which is where the connection handling lives now that a
     * second by-name route asks other catalogues the same way. What stays here is the one thing
     * that is this route's own: a request that did not arrive must not be remembered as a song
     * without lyrics. See load().
     */
    private static String get(String url) throws Exception {
        Http.Reply r = Http.get(url, "MCNcm");
        if (!r.ok()) {
            networkFailed = true;
        }
        return r.body;
    }

    /**
     * Diagnostics: what the match would do for a session's metadata, without touching the view.
     *
     * Takes the four fields rather than reading them, so a report of "this song got no lyrics"
     * can be reproduced without owning the song or the player - the fields are all the matcher
     * ever sees, and a metadump from the reporter carries them.
     */
    public static String describe(String title, String artist, String album, long durationMs) {
        Query q = build(title, artist, album, durationMs);
        return q == null ? "nothing to search for" : describe(q);
    }

    /** Diagnostics: what the match would do for the playing session, without touching the view. */
    public static String describe(MediaController c) {
        Query q = queryOf(c);
        if (q == null) {
            return "no session / no metadata";
        }
        return describe(q);
    }

    private static String describe(Query q) {
        StringBuilder sb = new StringBuilder(q.toString());
        sb.append("\n  search terms: \"").append(terms(q)).append('"');
        try {
            String json = get(String.format(SEARCH, URLEncoder.encode(terms(q), "UTF-8")));
            if (json == null) {
                return sb.append("\n  search failed").toString();
            }
            org.json.JSONArray songs =
                    new org.json.JSONObject(json).getJSONObject("result").getJSONArray("songs");
            for (int i = 0; i < songs.length(); i++) {
                org.json.JSONObject s = songs.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                long dur = s.optLong("duration", 0L);
                sb.append("\n  ").append(s.optLong("id", 0L)).append(' ').append(dur)
                        .append("ms (").append(dur - q.durationMs).append(") ")
                        .append(s.optString("name", ""));
                org.json.JSONObject al = s.optJSONObject("album");
                if (al != null) {
                    sb.append(" | ").append(al.optString("name", ""));
                }
            }
            sb.append("\n  -> picked ").append(pick(json, q));
        } catch (Throwable t) {
            sb.append("\n  ").append(t);
        }
        return sb.toString();
    }
}
