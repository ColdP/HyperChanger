// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * The catalogues asked after NetEase has nothing - KuGou first, then LrcLib.
 *
 * NcmLyrics covers a song NetEase has and can be made to admit to. Two different things stop it,
 * and neither is rare. The catalogue itself is the first: NetEase is the whole Chinese
 * catalogue and a thin slice of everything else, so a western release routinely is not there at
 * all. Its search is the second, and the worse one - measured repeatedly on this device, being
 * searched too often is answered not with an error but with a page of plausible wrong songs,
 * and a song hidden that way looks exactly like a song that does not exist. NcmLyrics spends a
 * request proving which it is (see searchIsHonest) but it cannot make the answer appear.
 *
 * So the answer to both is another catalogue. These two were picked for what they add rather
 * than for more of the same:
 *
 *   KuGou    - the other whole Chinese catalogue, and the only free one that hands over WORD
 *              timings: its KRC files time every syllable, and carry the translation in the
 *              same file. Three requests and a decryption; see krc().
 *   LrcLib   - a community line-level catalogue, and the one that has the western releases the
 *              other two do not. Its /api/get is an exact matcher rather than a search, which
 *              is the shape this file wants: it either has the recording or says so.
 *
 * Both are plain GETs with no key, no signature and no account, the same as NetEase's. Neither
 * needs anything installed on the phone, which is the whole point of this route: the lyric
 * provider modules cover more players than we ever will, and every one of them is another APK
 * the owner of the phone has to find, install, enable and scope.
 *
 * Order is by what an answer is worth, not by catalogue: word timings beat line timings, so
 * KuGou is asked first and LrcLib answers for what it does not have. Both are asked only after
 * NetEase and the AMLL database have come up empty, so a song that already works costs nothing.
 *
 * The matching is NcmLyrics' - its norm(), its name scoring, its duration windows. That is
 * deliberate: the rules there were each paid for by a song that got the wrong lyrics, and a
 * second matcher would have to be taught the same lessons one at a time.
 */
public final class WebLyrics {

    private WebLyrics() {
    }

    /** KuGou's song search: what the app's own client asks, minus everything optional. */
    private static final String KG_SONGS =
            "https://mobileservice.kugou.com/api/v3/search/song"
                    + "?version=9108&plat=0&pagesize=8&showtype=0&keyword=%s";
    /** The lyric candidates for one recording, by the hash the song search gives for it. */
    private static final String KG_BY_HASH =
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=%s";
    /** The same service asked by name and length, for a recording the song search did not find. */
    private static final String KG_BY_NAME =
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&duration=%d&keyword=%s";
    /** One candidate's file. fmt is krc (word-timed, encrypted) or lrc (line-timed, plain). */
    private static final String KG_FILE =
            "https://lyrics.kugou.com/download?fmt=%s&charset=utf8&client=pc&ver=1"
                    + "&id=%s&accesskey=%s";

    /** LrcLib's exact matcher, and its search for when the exact one says no. */
    private static final String LL_GET =
            "https://lrclib.net/api/get?track_name=%s&artist_name=%s&album_name=%s&duration=%d";
    private static final String LL_SEARCH =
            "https://lrclib.net/api/search?track_name=%s&artist_name=%s";

    /** What came back for one song, in the shape LyricSource asks the other routes for. */
    static final class Found {
        /** The catalogue's own id for it, for the log and for nothing else. */
        final String id;
        final String body;
        /**
         * True when body is word-timed.
         *
         * Nothing here carries a translation of its own: a KRC holds its own inside body, in
         * the header the parser reads, and LrcLib publishes none at all.
         */
        final boolean words;
        /** Which catalogue answered, as a LyricSource.SRC_ constant. */
        final int source;

        Found(String id, String body, boolean words, int source) {
            this.id = id;
            this.body = body;
            this.words = words;
            this.source = source;
        }

        String who() {
            return source == LyricSource.SRC_KUGOU ? "KuGou" : "LrcLib";
        }
    }

    /**
     * Cache, keyed the same way NcmLyrics' is and for the same reason.
     *
     * A song none of the catalogues has is looked up once and then costs nothing for the rest of
     * its play. This route is the one where that matters most: it is the one that runs after two
     * others have already failed, so its misses are the slowest thing the lyric view ever does.
     */
    private static final int CACHE_MAX = 8;
    private static final Map<String, Found> CACHE =
            new LinkedHashMap<String, Found>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** A miss, so the map can hold "asked, nothing there" as distinct from "never asked". */
    private static final Found NONE = new Found(null, null, false, LyricSource.SRC_NONE);

    /**
     * Both catalogues, better one first. Blocking: the caller is already on a worker.
     *
     * Returns null when neither could be made to prove it has this recording, which is a normal
     * outcome. It is remembered as one only if somebody actually answered - a phone that was
     * offline for the length of a lookup has learnt nothing about the song.
     */
    static Found load(NcmLyrics.Query q) {
        return load(q, true, true);
    }

    /** One or both of the two, for an order that puts KuGou somewhere other than last-but-one. */
    static Found load(NcmLyrics.Query q, boolean kugou, boolean lrclib) {
        if (q == null) {
            return null;
        }
        String key = q.key() + (kugou ? "|k" : "") + (lrclib ? "|l" : "");
        synchronized (CACHE) {
            Found hit = CACHE.get(key);
            if (hit != null) {
                Xp.log("[MCWeb] cached: " + (hit == NONE ? "no match" : hit.who() + " " + hit.id));
                return hit == NONE ? null : hit;
            }
        }
        Ask ask = new Ask();
        Found got = null;
        try {
            if (kugou) got = kugou(q, ask);
        } catch (Throwable t) {
            Xp.w("[MCWeb] KuGou failed: " + t);
        }
        if (got == null && lrclib) {
            try {
                got = lrclib(q, ask);
            } catch (Throwable t) {
                Xp.w("[MCWeb] LrcLib failed: " + t);
            }
        }
        synchronized (CACHE) {
            if (got != null) {
                CACHE.put(key, got);
            } else if (ask.answered && !ask.ranOut && !ask.failed) {
                // Both catalogues were asked and both answered: this song has no lyrics on
                // either, which is worth remembering. A lookup that ran out of its budget has
                // proven nothing of the sort and is left to be tried again.
                CACHE.put(key, NONE);
            }
        }
        return got;
    }

    /**
     * One lookup's requests, and what they add up to.
     *
     * Two things have to be carried across six possible requests and neither belongs to any one
     * of them. The budget is the first: this route is reached only by a song that has already
     * spent the database's and NetEase's, so left unbounded it could go on asking long after
     * anyone stopped waiting - and the per-request timeouts alone allow a minute between them.
     * Whether anybody answered is the second, and it decides whether a miss is remembered: see
     * load().
     *
     * A 404 counts as an answer. It is LrcLib's way of saying it does not have the recording,
     * which is exactly the thing worth remembering.
     */
    private static final class Ask {
        /** Roughly the race's own budget: by the time this runs, that one is already spent. */
        private static final long BUDGET_MS = 8000L;

        private final long deadline =
                android.os.SystemClock.uptimeMillis() + BUDGET_MS;
        /** Set by any request that came back with something, including a 404. */
        boolean answered;
        /** Set when a request was skipped because the budget was gone. */
        boolean ranOut;
        /**
         * Set by a request that got no answer at all, or a server error: the search may have
         * answered and the lyric's own download not, and that is no miss to remember.
         */
        boolean failed;

        Http.Reply get(String url) {
            if (android.os.SystemClock.uptimeMillis() > deadline) {
                ranOut = true;
                return SKIPPED;
            }
            Http.Reply r = Http.get(url, "MCWeb");
            answered |= r.ok() || r.code == 404;
            failed |= !r.ok() && r.code != 404;
            return r;
        }
    }

    private static final Http.Reply SKIPPED = new Http.Reply(null, 0);

    /**
     * How many of a list's candidates are worth a download before giving up on the list.
     *
     * Each one costs a request, and two when its KRC does not decrypt. The lyric service ranks
     * its own candidates and the first is what its client shows, so a list whose first few
     * cannot be downloaded is not a list that gets better further down - it is a recording
     * whose lyrics are not there.
     */
    private static final int MAX_CANDIDATES = 3;

    // ---------------------------------------------------------------- KuGou

    /**
     * KuGou, by way of the recording's hash.
     *
     * Three requests, each one narrowing the last: the song search knows recordings and gives
     * each a hash; the lyric service knows which lyric files belong to a hash; the download
     * hands one over. The hash is what makes this precise - a lyric file is attached to a
     * recording rather than to a title, so once the search has proven which recording is playing
     * there is no second matching problem.
     *
     * Measured 2026-09-22: 七里香 came back as hash 9218d81686c6ac681bc7ca621958ad6d, candidate
     * 139713786, 6169 characters of KRC, which is 38 lines with a time on every syllable.
     */
    private static Found kugou(NcmLyrics.Query q, Ask ask) throws Exception {
        String terms = NcmLyrics.terms(q);
        if (terms.isEmpty() || q.durationMs <= 0) {
            // Without a duration nothing here can be proven, and the first result is a guess.
            return null;
        }
        long started = android.os.SystemClock.uptimeMillis();
        Http.Reply songs = ask.get(String.format(KG_SONGS, enc(terms)));
        String hash = songs.ok() ? hashOf(songs.body, q) : null;
        Found f = null;
        if (hash != null) {
            Http.Reply by = ask.get(String.format(KG_BY_HASH, hash));
            f = by.ok() ? download(by.body, q, true, ask) : null;
        }
        if (f == null) {
            // The song search did not place the recording - it is a search, and it answers with
            // covers and remixes like every other one. The lyric service will take a name and a
            // length directly, and its candidates carry their own durations, so the same proof
            // can be asked of them. Two ways in rather than one, for the same reason NcmLyrics
            // has the album route: the expensive part is already spent by the time we get here.
            Http.Reply by = ask.get(String.format(KG_BY_NAME, q.durationMs, enc(terms)));
            f = by.ok() ? download(by.body, q, false, ask) : null;
        }
        if (f != null) {
            Xp.log("[MCWeb] KuGou " + f.id + " -> " + (f.words ? "krc" : "lrc") + " "
                    + f.body.length() + " chars in "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        }
        return f;
    }

    /**
     * Which recording in a KuGou song search is the session's - or none of them. Scored by
     * LyricMatch, the rule every by-name source shares.
     */
    private static String hashOf(String json, NcmLyrics.Query q) {
        org.json.JSONArray info;
        try {
            info = new org.json.JSONObject(json).getJSONObject("data").getJSONArray("info");
        } catch (Throwable t) {
            return null;
        }
        java.util.List<LyricMatch.Candidate> cands = new java.util.ArrayList<>();
        for (int i = 0; i < info.length(); i++) {
            org.json.JSONObject s = info.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String name = NcmLyrics.str(s, "songname");
            String hash = NcmLyrics.str(s, "hash");
            if (name == null || hash == null) {
                continue;
            }
            cands.add(new LyricMatch.Candidate(hash, plain(name),
                    plain(NcmLyrics.str(s, "singername")), plain(NcmLyrics.str(s, "album_name")),
                    millis(s.optLong("duration", 0L)), null));
        }
        LyricMatch.Pick p = LyricMatch.best(cands, new LyricMatch.Wanted(q));
        if (p.candidate != null) {
            Xp.log("[MCWeb] KuGou best " + p.candidate + " scored " + p.score
                    + (p.passes() ? "" : ", under " + LyricMatch.PASS_SCORE));
        }
        return p.passes() ? p.candidate.id : null;
    }

    /**
     * The first candidate worth downloading, downloaded.
     *
     * `trusted` says whether the candidates were asked for by hash. They were, when the song
     * search placed the recording, and then the list belongs to that recording and its order is
     * the service's own ranking - the first is the one its client would show. Asked by name they
     * are search results again, so each one has to carry a duration inside the window and a
     * title and artist that agree before it is worth a request.
     */
    private static Found download(String json, NcmLyrics.Query q, boolean trusted, Ask ask) {
        org.json.JSONArray cs;
        try {
            cs = new org.json.JSONObject(json).optJSONArray("candidates");
        } catch (Throwable t) {
            return null;
        }
        if (cs == null) {
            return null;
        }
        LyricMatch.Wanted w = new LyricMatch.Wanted(q);
        int tried = 0;
        for (int i = 0; i < cs.length() && tried < MAX_CANDIDATES; i++) {
            org.json.JSONObject c = cs.optJSONObject(i);
            if (c == null) {
                continue;
            }
            // The id is a number in this response and the key is a string, so neither can be
            // read the way every other field here is.
            long id = c.optLong("id", 0L);
            String key = NcmLyrics.str(c, "accesskey");
            if (id == 0L || key == null) {
                continue;
            }
            if (!trusted && LyricMatch.score(new LyricMatch.Candidate(String.valueOf(id),
                    NcmLyrics.str(c, "song"), NcmLyrics.str(c, "singer"), null,
                    millis(c.optLong("duration", 0L)), null), w) < LyricMatch.PASS_SCORE) {
                continue;
            }
            tried++;
            Found f = file(String.valueOf(id), key, ask);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** One candidate's file: the word-timed one if it decrypts, the plain one if it does not. */
    private static Found file(String id, String accessKey, Ask ask) {
        Http.Reply krc = ask.get(String.format(KG_FILE, "krc", id, accessKey));
        String body = krc.ok() ? krc(contentOf(krc.body)) : null;
        if (body != null) {
            return new Found(id, body, true, LyricSource.SRC_KUGOU);
        }
        // A candidate with no KRC still has an LRC, and a line-timed lyric is worth far more
        // than none. Both come base64'd in the same envelope.
        Http.Reply lrc = ask.get(String.format(KG_FILE, "lrc", id, accessKey));
        String plain = lrc.ok() ? text(contentOf(lrc.body)) : null;
        if (plain == null || !plain.contains("[")) {
            return null;
        }
        return new Found(id, plain, false, LyricSource.SRC_KUGOU);
    }

    /** The base64 payload out of a download response. */
    private static String contentOf(String json) {
        try {
            return NcmLyrics.str(new org.json.JSONObject(json), "content");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The key KRC files are encrypted with, which is the same one in every KuGou client.
     *
     * A KRC is "krc1", then the body XORed byte by byte with this key repeating, then zlib. The
     * result is a text file whose lines are [start,duration] followed by one <offset,duration,0>
     * per syllable - which is a format the parser already reads, so nothing downstream needs to
     * know this route exists. Its [language:...] header holds the translation, base64'd JSON,
     * and the parser reads that too.
     *
     * Written as numbers rather than as a string because four of the sixteen bytes are not
     * characters, and because a source file's encoding is not something to bet a lyric on.
     */
    private static final byte[] KRC_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105
    };

    /** A KRC file's base64, decrypted and inflated - or null if it is not one. */
    private static String krc(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] in = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            if (in.length <= KRC_HEADER || in[0] != 'k' || in[1] != 'r' || in[2] != 'c') {
                return null;
            }
            byte[] out = new byte[in.length - KRC_HEADER];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (in[i + KRC_HEADER] ^ KRC_KEY[i % KRC_KEY.length]);
            }
            return inflate(out);
        } catch (Throwable t) {
            Xp.log("[MCWeb] not a KRC: " + t);
            return null;
        }
    }

    /** "krc1" - the four bytes the key does not apply to. */
    private static final int KRC_HEADER = 4;

    private static String inflate(byte[] deflated) throws Exception {
        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(deflated.length * 2);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** A plain file's base64, decoded. */
    private static String text(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            return new String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT),
                    "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- LrcLib

    /**
     * LrcLib, which is asked rather than searched.
     *
     * /api/get takes the four fields and answers with the recording or with a 404 - the matching
     * is done on their side, against the duration, which is exactly the proof this file insists
     * on everywhere else. So it is tried first and its 404 is believed.
     *
     * The search behind it is for the case the exact matcher cannot serve: a session whose album
     * is missing or written differently, or a duration a few seconds from theirs. Its results
     * carry durations, so the same window applies.
     */
    private static Found lrclib(NcmLyrics.Query q, Ask ask) throws Exception {
        if (q.title.isEmpty() || q.durationMs <= 0) {
            return null;
        }
        long started = android.os.SystemClock.uptimeMillis();
        String artist = NcmLyrics.firstArtist(q.artist);
        Http.Reply got = ask.get(String.format(LL_GET, enc(q.title), enc(artist),
                enc(q.album), Math.round(q.durationMs / 1000.0)));
        Found f = got.ok() ? synced(got.body, "exact") : null;
        if (f == null) {
            Http.Reply found = ask.get(String.format(LL_SEARCH, enc(q.title), enc(artist)));
            f = found.ok() ? search(found.body, q) : null;
        }
        if (f != null) {
            Xp.log("[MCWeb] LrcLib " + f.id + " -> " + f.body.length() + " chars in "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        }
        return f;
    }

    /** Which of a search's results is the session's recording - or none of them. */
    private static Found search(String json, NcmLyrics.Query q) {
        org.json.JSONArray tracks;
        try {
            tracks = new org.json.JSONArray(json);
        } catch (Throwable t) {
            return null;
        }
        LyricMatch.Wanted w = new LyricMatch.Wanted(q);
        Found best = null;
        int bestScore = -1;
        for (int i = 0; i < tracks.length(); i++) {
            org.json.JSONObject o = tracks.optJSONObject(i);
            if (o == null) {
                continue;
            }
            int score = LyricMatch.score(new LyricMatch.Candidate(String.valueOf(o.optLong("id")),
                    NcmLyrics.str(o, "trackName"), NcmLyrics.str(o, "artistName"),
                    NcmLyrics.str(o, "albumName"), millis(Math.round(o.optDouble("duration", 0))),
                    null), w);
            if (score < LyricMatch.PASS_SCORE || score <= bestScore) {
                continue;
            }
            Found f = synced(o, "search");
            if (f != null) {
                best = f;
                bestScore = score;
            }
        }
        return best;
    }

    private static Found synced(String json, String how) {
        try {
            return synced(new org.json.JSONObject(json), how);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The timed lyric out of one LrcLib track, or null when it has none worth showing.
     *
     * An instrumental is marked as one and is not a miss - there is nothing to sing. It is still
     * returned as null, because a row of nothing on the lock screen is what no lyrics already
     * looks like, and the routes below this one have nothing better to offer for it.
     */
    private static Found synced(org.json.JSONObject o, String how) {
        if (o.optBoolean("instrumental", false)) {
            return null;
        }
        String body = NcmLyrics.str(o, "syncedLyrics");
        if (body == null || !body.contains("[")) {
            // plainLyrics is untimed, and this view follows the singing.
            return null;
        }
        return new Found(o.optLong("id", 0L) + " (" + how + ")", body, false,
                LyricSource.SRC_LRCLIB);
    }

    // --------------------------------------------------------------- shared

    /**
     * A duration in whatever unit it arrived in, as milliseconds.
     *
     * The two KuGou endpoints do not agree with each other, and one of them does not agree with
     * itself: measured 2026-09-22, the lyric service's candidates for 七里香 came back as
     * 299311, 299 and 298 in the same response. Anything under the threshold is seconds - no
     * song is ten seconds long, and no song is three hours.
     */
    private static long millis(long duration) {
        return duration > 0 && duration < SECONDS_CEILING ? duration * 1000L : duration;
    }

    private static final long SECONDS_CEILING = 10000L;

    /** A title with the search's own match markup taken out before it is normalised. */
    private static String plain(String s) {
        return s == null ? null : s.replace("<em>", "").replace("</em>", "");
    }

    /**
     * A query parameter, with its spaces as %20 rather than as plus signs.
     *
     * URLEncoder writes a space as '+', which is the form-encoded spelling and is what NetEase
     * accepts. KuGou's lyric service does not: measured 2026-09-22, "七里香 周杰伦" spelled with
     * %20 came back with ten candidates and the same terms spelled with '+' came back with
     * none - not an error, an empty list, which from here is indistinguishable from a song it
     * does not have. A literal plus in a title is already %2B by the time this runs, so only
     * the spaces are touched.
     */
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
    }

    /**
     * Diagnostics: what these two make of a session's metadata, without touching the view.
     *
     * The same shape as NcmLyrics.describe and printed under it by the same probe, because the
     * question they answer together is one question - "this song got no lyrics, why" - and the
     * answer is which of the three catalogues was asked and what each one said.
     */
    public static String describe(NcmLyrics.Query q) {
        if (q == null) {
            return "nothing to search for";
        }
        StringBuilder sb = new StringBuilder();
        Ask ask = new Ask();
        try {
            Found f = kugou(q, ask);
            sb.append("\n  KuGou: ").append(f == null ? "no match"
                    : f.id + " " + (f.words ? "krc" : "lrc") + " " + f.body.length() + " chars");
        } catch (Throwable t) {
            sb.append("\n  KuGou: ").append(t);
        }
        try {
            Found f = lrclib(q, ask);
            sb.append("\n  LrcLib: ").append(f == null ? "no match"
                    : f.id + " " + f.body.length() + " chars");
        } catch (Throwable t) {
            sb.append("\n  LrcLib: ").append(t);
        }
        return sb.toString();
    }
}
