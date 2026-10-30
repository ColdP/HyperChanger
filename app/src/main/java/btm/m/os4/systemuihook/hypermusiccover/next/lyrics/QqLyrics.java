// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * The request shapes are taken from HyperLyrics Enhanced (juren233/HyperLyrics-Enhanced,
 * online/source/qm/QmSource.kt), GPL-3.0, and used here under this project's AGPL-3.0 (GPLv3
 * section 13). The QRC reading is our own: theirs pairs each word with the timestamp after the
 * next one.
 */
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ Music, by name: word-timed QRC with its translation and, for Japanese and Korean songs, a
 * word-timed romanisation - the largest catalogue there is for Chinese music, and the one that
 * still answers when NetEase is handing out decoys.
 *
 * Two POSTs to one endpoint: the lite client's search, then its lyric call for the chosen id. The
 * lyric fields come back hex-encoded and encrypted (see QrcCrypt); the lyric itself is QRC in an
 * XML envelope, the translation plain LRC, the romanisation QRC again.
 */
final class QqLyrics {

    private QqLyrics() {
    }

    private static final String URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String TAG = "MCQq";

    static final class Found {
        final String id;
        /** yrc when word-timed, LRC otherwise. */
        final String body;
        final String translation;
        final String roma;
        final boolean words;

        Found(String id, String body, String translation, String roma, boolean words) {
            this.id = id;
            this.body = body;
            this.translation = translation;
            this.roma = roma;
            this.words = words;
        }
    }

    private static final Found NONE = new Found(null, null, null, null, false);
    private static final int CACHE_MAX = 16;
    private static final Map<String, Found> CACHE =
            new LinkedHashMap<String, Found>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** Search, score, fetch. Blocking; null when nothing passed or nothing arrived. */
    static Found load(NcmLyrics.Query q) {
        if (q == null || q.title.isEmpty()) return null;
        String key = q.key();
        synchronized (CACHE) {
            Found hit = CACHE.get(key);
            if (hit != null) return hit == NONE ? null : hit;
        }
        boolean[] answered = {false};
        Found got = null;
        try {
            got = fetch(q, answered);
        } catch (Throwable t) {
            Xp.w("[" + TAG + "] failed: " + t);
            answered[0] = false;
        }
        // A miss is remembered only when the search actually answered.
        if (got != null || answered[0]) {
            synchronized (CACHE) {
                CACHE.put(key, got == null ? NONE : got);
            }
        }
        return got;
    }

    private static Found fetch(NcmLyrics.Query q, boolean[] answered) throws Exception {
        long started = android.os.SystemClock.uptimeMillis();
        // QQ 音乐 playing: its own id for the song, and its lyric by that - the original, the
        // translation and the romanisation, with no search to lose the song in. Measured
        // 2026-10-07, the id alone answers; names and length need not be sent.
        if (q.qqId != null) {
            Found f = lyrics(new LyricMatch.Candidate(q.qqId, q.title, q.artist, q.album, 0L, null));
            if (f != null) {
                answered[0] = true;
                Xp.log("[" + TAG + "] " + f.id + " by id -> " + (f.words ? "qrc" : "lrc")
                        + (f.translation != null ? " + translation" : "")
                        + (f.roma != null ? " + romanisation" : "") + " in "
                        + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                return f;
            }
        }
        LyricMatch.Wanted w = new LyricMatch.Wanted(q);
        List<LyricMatch.Candidate> cands = search(joined(TrackName.untranslated(q.title),
                TrackName.unaliased(NcmLyrics.firstArtist(q.artist))), answered);
        LyricMatch.Pick p = LyricMatch.best(cands, w);
        // Several credited names and none of them on the best result: the search ranked on the
        // wrong one. Asked again by the title and the album, as HyperLyrics Enhanced does.
        if (!p.passes() && w.multiCredit() && (p.candidate == null || !p.artistMatched)) {
            List<LyricMatch.Candidate> more = search(joined(q.title, q.album), answered);
            LyricMatch.Pick retry = LyricMatch.best(more, w);
            if (retry.score > p.score) p = retry;
        }
        if (p.candidate != null) {
            Xp.log("[" + TAG + "] best " + p.candidate + " scored " + p.score
                    + (p.passes() ? "" : ", under " + LyricMatch.PASS_SCORE));
        }
        if (!p.passes()) return null;
        Found f = lyrics(p.candidate);
        // The song was found and its lyric did not arrive: that is not a miss to remember for
        // the rest of the track, only a request to try again.
        if (f == null) answered[0] = false;
        if (f != null) {
            Xp.log("[" + TAG + "] " + f.id + " -> " + (f.words ? "qrc" : "lrc")
                    + (f.translation != null ? " + translation" : "")
                    + (f.roma != null ? " + romanisation" : "") + " in "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        }
        return f;
    }

    private static String joined(String a, String b) {
        if (b == null || b.isEmpty()) return a;
        return a + ' ' + b;
    }

    private static org.json.JSONObject comm() throws Exception {
        org.json.JSONObject c = new org.json.JSONObject();
        c.put("ct", "11");
        c.put("cv", "1003006");
        c.put("v", "1003006");
        c.put("os_ver", "15");
        c.put("phonetype", "24122RKC7C");
        c.put("tmeAppID", "qqmusiclight");
        c.put("nettype", "NETWORK_WIFI");
        return c;
    }

    private static org.json.JSONObject post(String method, String module,
                                            org.json.JSONObject param) throws Exception {
        org.json.JSONObject req = new org.json.JSONObject();
        req.put("method", method);
        req.put("module", module);
        req.put("param", param);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("comm", comm());
        body.put("req_0", req);
        Http.Raw r = Http.request(URL, TAG, body.toString(),
                "User-Agent", "okhttp/3.14.9", "Referer", "https://y.qq.com/",
                "Content-Type", "application/json");
        String text = r.text();
        return text == null ? null : new org.json.JSONObject(text);
    }

    /**
     * What a search answered lately, by its words, for a couple of minutes.
     *
     * The duration is not in the words but it is in the score, and QQ 音乐 publishes a new
     * track with the previous one's duration for its first half second: the right song came
     * back scored 60 against the stale duration, and the lookup for the real one (measured
     * 2026-10-06, 反着爱一场) asked the same question again - and that second request hung until
     * its read timeout, six seconds before the slow route found the lyric on KuGou. The same
     * words re-scored against the new duration need no second request.
     */
    private static final long SEARCH_TTL_MS = 120_000L;
    private static final Map<String, Object[]> SEARCHES =
            new LinkedHashMap<String, Object[]>(9, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                    return size() > 8;
                }
            };

    @SuppressWarnings("unchecked")
    static List<LyricMatch.Candidate> search(String keyword, boolean[] answered) throws Exception {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (SEARCHES) {
            Object[] hit = SEARCHES.get(keyword);
            if (hit != null && now - (Long) hit[0] < SEARCH_TTL_MS) {
                answered[0] = true;
                return (List<LyricMatch.Candidate>) hit[1];
            }
        }
        List<LyricMatch.Candidate> out = searchOnline(keyword, answered);
        if (answered[0] && !out.isEmpty()) {
            synchronized (SEARCHES) {
                SEARCHES.put(keyword, new Object[]{now, out});
            }
        }
        return out;
    }

    private static List<LyricMatch.Candidate> searchOnline(String keyword, boolean[] answered)
            throws Exception {
        List<LyricMatch.Candidate> out = new ArrayList<>();
        org.json.JSONObject param = new org.json.JSONObject();
        param.put("search_id", String.valueOf(10000000000000000L
                + (long) (Math.random() * 80000000000000000L)));
        param.put("remoteplace", "search.android.keyboard");
        param.put("query", keyword);
        param.put("search_type", 0);
        param.put("num_per_page", 20);
        param.put("page_num", 1);
        param.put("highlight", 0);
        param.put("nqc_flag", 0);
        param.put("page_id", 1);
        param.put("grp", 1);
        org.json.JSONObject o = post("DoSearchForQQMusicLite", "music.search.SearchCgiService", param);
        if (o == null) return out;
        answered[0] = true;
        org.json.JSONObject data = o.optJSONObject("req_0");
        data = data == null ? null : data.optJSONObject("data");
        org.json.JSONObject body = data == null ? null : data.optJSONObject("body");
        org.json.JSONArray songs = body == null ? null : body.optJSONArray("item_song");
        for (int i = 0; songs != null && i < songs.length(); i++) {
            org.json.JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            long id = s.optLong("id", 0L);
            String title = NcmLyrics.str(s, "title");
            if (id == 0L || title == null) continue;
            StringBuilder artists = new StringBuilder();
            org.json.JSONArray singers = s.optJSONArray("singer");
            for (int k = 0; singers != null && k < singers.length(); k++) {
                org.json.JSONObject a = singers.optJSONObject(k);
                String n = a == null ? null : NcmLyrics.str(a, "name");
                if (n == null) continue;
                if (artists.length() > 0) artists.append('/');
                artists.append(n);
            }
            org.json.JSONObject al = s.optJSONObject("album");
            out.add(new LyricMatch.Candidate(String.valueOf(id), title, artists.toString(),
                    al == null ? null : NcmLyrics.str(al, "name"), s.optLong("interval", 0L) * 1000L,
                    null));
        }
        return out;
    }

    private static String b64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
    }

    private static Found lyrics(LyricMatch.Candidate c) throws Exception {
        org.json.JSONObject param = new org.json.JSONObject();
        param.put("songID", Long.parseLong(c.id));
        param.put("songName", b64(c.title));
        param.put("albumName", b64(c.album));
        param.put("singerName", b64(c.artist));
        param.put("crypt", 1);
        param.put("qrc", 1);
        param.put("trans", 1);
        param.put("roma", 1);
        param.put("cv", 2111);
        param.put("ct", 19);
        param.put("lrc_t", 0);
        param.put("qrc_t", 0);
        param.put("roma_t", 0);
        param.put("trans_t", 0);
        param.put("type", 0);
        param.put("interval", c.durationMs / 1000L);
        org.json.JSONObject o = post("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param);
        org.json.JSONObject r0 = o == null ? null : o.optJSONObject("req_0");
        org.json.JSONObject data = r0 == null ? null : r0.optJSONObject("data");
        if (data == null) return null;
        String lyric = QrcCrypt.decrypt(NcmLyrics.str(data, "lyric"));
        if (lyric.isEmpty()) {
            Xp.log("[" + TAG + "] " + c.id + " has no lyric");
            return null;
        }
        List<TimedText.Line> lines = qrc(lyric);
        String body = lines.isEmpty() ? lyric : TimedText.yrc(lines);
        return new Found(c.id, body, auxiliary(NcmLyrics.str(data, "trans")),
                auxiliary(NcmLyrics.str(data, "roma")), !lines.isEmpty());
    }

    /** A translation or romanisation field: QRC or LRC once decrypted, handed on as LRC. */
    private static String auxiliary(String hex) {
        return auxText(QrcCrypt.decrypt(hex));
    }

    /** The decrypted half of auxiliary(), on its own so it can be checked off the device. */
    static String auxText(String text) {
        if (text == null || text.isEmpty()) return null;
        List<TimedText.Line> lines = qrc(text);
        if (!lines.isEmpty()) return TimedText.lrcOf(lines);
        // Plain LRC. QQ writes "//" for a line it has no translation for - the credits, the
        // instrumental breaks - which would be drawn as the translation.
        String clean = PLACEHOLDER.matcher(text).replaceAll("");
        return clean.trim().isEmpty() ? null : clean;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("(?m)^\\[[^\\]]*]\\s*//\\s*$");

    private static final Pattern XML = Pattern.compile("LyricContent=\"(.*?)\"\\s*/>",
            Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    /** A word and the time after it: "七(0,462)". Lazy, so "((4620,462)" is the word "(". */
    private static final Pattern WORD = Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)");

    /** The lines of a QRC document; empty when it is not one. Times are absolute. */
    static List<TimedText.Line> qrc(String doc) {
        String content = doc;
        Matcher x = XML.matcher(doc);
        if (x.find()) content = unescape(x.group(1));
        List<TimedText.Line> out = new ArrayList<>();
        for (String raw : content.split("\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            long start = Long.parseLong(m.group(1));
            long end = start + Long.parseLong(m.group(2));
            List<TimedText.Word> words = new ArrayList<>();
            Matcher wm = WORD.matcher(m.group(3));
            while (wm.find()) {
                long ws = Long.parseLong(wm.group(2));
                String t = wm.group(1);
                if (t.isEmpty()) continue;
                words.add(new TimedText.Word(ws, ws + Long.parseLong(wm.group(3)), t));
            }
            if (!words.isEmpty()) out.add(new TimedText.Line(start, end, words));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }
}
