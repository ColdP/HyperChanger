// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * Ported from HyperLyrics Enhanced (juren233/HyperLyrics-Enhanced, online/source/kuwo/
 * KuwoSource.kt), Copyright 2026 juren233, Apache-2.0 - see NOTICE. Rewritten in Java.
 */
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * Kuwo, by name: its web search, then the lyric its desktop client downloads - "lrcx", word
 * timed, XOR'd with a fixed key and deflated - falling back to the web API's line-timed LRC.
 * Translation and romanisation come as extra lines under the line they belong to.
 */
final class KuwoLyrics {

    private KuwoLyrics() {
    }

    private static final String TAG = "MCKuwo";
    private static final String SEARCH_URL =
            "https://www.kuwo.cn/openapi/v1/www/search/searchMusicBykeyWord";
    private static final String OPEN_LYRIC_URL = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric";
    private static final String LRCX_URL = "https://newlyric.kuwo.cn/newlyric.lrc";

    /** What came back for one song - the same shape as QQ's. */
    static final class Found {
        final String id;
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
        if (got != null || answered[0]) {
            synchronized (CACHE) {
                CACHE.put(key, got == null ? NONE : got);
            }
        }
        return got;
    }

    private static Found fetch(NcmLyrics.Query q, boolean[] answered) throws Exception {
        long started = android.os.SystemClock.uptimeMillis();
        LyricMatch.Wanted w = new LyricMatch.Wanted(q);
        LyricMatch.Pick p = LyricMatch.best(search(q.title + ' ' + q.artist, answered), w);
        if (!p.passes() && w.multiCredit() && (p.candidate == null || !p.artistMatched)) {
            LyricMatch.Pick retry = LyricMatch.best(
                    search(q.album.isEmpty() ? q.title : q.title + ' ' + q.album, answered), w);
            if (retry.score > p.score) p = retry;
        }
        if (p.candidate != null) {
            Xp.log("[" + TAG + "] best " + p.candidate + " scored " + p.score
                    + (p.passes() ? "" : ", under " + LyricMatch.PASS_SCORE));
        }
        if (!p.passes()) return null;
        Found f = parse(p.candidate.id, lyrics(Long.parseLong(p.candidate.id)));
        // The song was found and its lyric did not arrive: that is not a miss to remember for
        // the rest of the track, only a request to try again.
        if (f == null) answered[0] = false;
        if (f != null) {
            Xp.log("[" + TAG + "] " + f.id + " -> " + (f.words ? "lrcx" : "lrc") + " in "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        }
        return f;
    }

    private static final String[] WEB_HEADERS = {"User-Agent", "Mozilla/5.0",
            "Referer", "https://www.kuwo.cn/", "Accept-Encoding", "identity"};

    static List<LyricMatch.Candidate> search(String keyword, boolean[] answered) throws Exception {
        List<LyricMatch.Candidate> out = new ArrayList<>();
        Http.Raw r = Http.request(SEARCH_URL + "?key=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                + "&pn=1&rn=20&httpsStatus=1", TAG, null, WEB_HEADERS);
        String text = r.text();
        if (text == null) return out;
        answered[0] = true;
        org.json.JSONObject data = new org.json.JSONObject(text).optJSONObject("data");
        org.json.JSONArray list = data == null ? null : data.optJSONArray("list");
        for (int i = 0; list != null && i < list.length(); i++) {
            org.json.JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            long rid = o.optLong("rid", 0L);
            if (rid <= 0L) continue;
            out.add(new LyricMatch.Candidate(String.valueOf(rid), NcmLyrics.str(o, "name"),
                    NcmLyrics.str(o, "artist"), NcmLyrics.str(o, "album"),
                    Math.max(0L, o.optLong("duration", 0L)) * 1000L, null));
        }
        return out;
    }

    /** The lrcx if it has lines, else the web API's LRC. */
    private static String lyrics(long rid) throws Exception {
        try {
            String x = lrcx(rid);
            if (x != null && TIMESTAMP.matcher(x).find()) return x;
        } catch (Throwable t) {
            Xp.w("[" + TAG + "] lrcx failed: " + t);
        }
        Http.Raw r = Http.request(OPEN_LYRIC_URL + "?musicId=" + rid + "&httpsStatus=1", TAG, null,
                WEB_HEADERS);
        String text = r.text();
        if (text == null) return "";
        org.json.JSONObject data = new org.json.JSONObject(text).optJSONObject("data");
        org.json.JSONArray list = data == null ? null : data.optJSONArray("lrclist");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; list != null && i < list.length(); i++) {
            org.json.JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            double t;
            try {
                t = Double.parseDouble(o.optString("time"));
            } catch (NumberFormatException e) {
                continue;
            }
            long ms = (long) (t * 1000.0);
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", ms / 60000L, ms / 1000L % 60L,
                    ms % 1000L)).append(o.optString("lineLyric")).append('\n');
        }
        return sb.toString();
    }

    private static final byte[] KEY = "yeelion".getBytes(StandardCharsets.US_ASCII);

    private static String lrcx(long rid) throws Exception {
        String request = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_" + rid
                + "&lrcx=1";
        String query = java.util.Base64.getEncoder().encodeToString(xor(request.getBytes(
                StandardCharsets.UTF_8)));
        Http.Raw r = Http.request(LRCX_URL + "?" + query, TAG, null, "User-Agent",
                "okhttp/3.10.0", "Referer", "https://www.kuwo.cn/", "Accept-Encoding", "identity");
        return r.ok() ? decode(r.body) : null;
    }

    private static String decode(byte[] response) throws Exception {
        byte[] prefix = "tp=content".getBytes(StandardCharsets.US_ASCII);
        if (response.length < prefix.length) return null;
        for (int i = 0; i < prefix.length; i++) if (response[i] != prefix[i]) return null;
        int offset = indexOf(response, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        if (offset < 0) return null;
        offset += 4;
        InflaterInputStream in = new InflaterInputStream(
                new ByteArrayInputStream(response, offset, response.length - offset));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        byte[] enc = java.util.Base64.getMimeDecoder().decode(
                new String(out.toByteArray(), StandardCharsets.US_ASCII).trim());
        String s = new String(xor(enc), Charset.forName("GB18030"));
        return s.trim().isEmpty() ? null : s;
    }

    private static byte[] xor(byte[] v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) (v[i] ^ KEY[i % KEY.length]);
        return out;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int s = 0; s <= hay.length - needle.length; s++) {
            for (int k = 0; k < needle.length; k++) if (hay[s + k] != needle[k]) continue outer;
            return s;
        }
        return -1;
    }

    private static final Pattern TIMESTAMP =
            Pattern.compile("^\\[(\\d{1,3}):([0-5]\\d)(?:[.:]([0-9]{1,3}))?]", Pattern.MULTILINE);
    private static final Pattern WORD = Pattern.compile("<(-?\\d+),(-?\\d+)>([^<]*)");
    private static final Pattern SCALE = Pattern.compile("\\[kuwo:([0-7]+)]", Pattern.CASE_INSENSITIVE);

    private static final class Raw {
        final long begin;
        final String text;
        final List<TimedText.Word> words;
        /** 0 timed, 1 an auxiliary (every marker 0,0), 2 plain. */
        final int kind;

        Raw(long begin, String text, List<TimedText.Word> words, int kind) {
            this.begin = begin;
            this.text = text;
            this.words = words;
            this.kind = kind;
        }
    }

    private static final class Row {
        final long begin;
        final String text;
        final List<TimedText.Word> words;
        String translation;
        String roma;

        Row(long begin, String text, List<TimedText.Word> words) {
            this.begin = begin;
            this.text = text;
            this.words = words;
        }
    }

    /** lrcx or LRC into yrc plus its translation and romanisation. */
    static Found parse(String id, String raw) {
        if (raw == null || raw.isEmpty()) return null;
        long beginDiv = 2L, durDiv = 2L;
        Matcher sc = SCALE.matcher(raw);
        if (sc.find()) {
            int enc = Integer.parseInt(sc.group(1), 8);
            if (enc / 10 > 0 && enc % 10 > 0) {
                beginDiv = (enc / 10) * 2L;
                durDiv = (enc % 10) * 2L;
            }
        }
        List<Raw> parsed = new ArrayList<>();
        for (String line : raw.split("\r?\n")) {
            Raw r = parseLine(line, beginDiv, durDiv);
            if (r != null) parsed.add(r);
        }
        if (parsed.isEmpty()) return null;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            Raw line = parsed.get(i);
            Row last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (line.kind == 1) {
                if (last != null) {
                    if (last.translation == null) last.translation = line.text;
                    else if (last.roma == null) last.roma = line.text;
                }
            } else if (line.kind == 2) {
                Raw next = i + 1 < parsed.size() ? parsed.get(i + 1) : null;
                if (last != null && next != null && next.kind == 2 && next.begin == line.begin) {
                    if (last.translation == null) last.translation = line.text;
                } else if (!line.text.trim().isEmpty()) {
                    rows.add(new Row(line.begin, line.text, line.words));
                }
            } else if (!line.text.trim().isEmpty()) {
                rows.add(new Row(line.begin, line.text, line.words));
            }
        }
        List<TimedText.Line> lines = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        List<String> trs = new ArrayList<>(), romas = new ArrayList<>();
        boolean words = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            long end = r.begin + 5000L;
            if (!r.words.isEmpty()) end = r.words.get(r.words.size() - 1).end;
            else if (i + 1 < rows.size()) end = rows.get(i + 1).begin;
            end = Math.max(r.begin + 1L, end);
            List<TimedText.Word> ws = r.words;
            if (ws.isEmpty()) {
                ws = new ArrayList<>();
                ws.add(new TimedText.Word(r.begin, end, r.text));
            } else {
                words = true;
            }
            lines.add(new TimedText.Line(r.begin, end, ws));
            starts.add(r.begin);
            trs.add(r.translation);
            romas.add(r.roma);
        }
        if (lines.isEmpty()) return null;
        // Line-timed files go out as LRC: a yrc of one word a line would claim word timing.
        String body = words ? TimedText.yrc(lines) : TimedText.lrcOf(lines);
        return new Found(id, body, TimedText.lrc(starts, trs), TimedText.lrc(starts, romas), words);
    }

    private static Raw parseLine(String raw, long beginDiv, long durDiv) {
        Matcher m = TIMESTAMP.matcher(raw);
        if (!m.find()) return null;
        String frac = m.group(3) == null ? "" : m.group(3);
        long ms = frac.length() == 1 ? Long.parseLong(frac) * 100L
                : frac.length() == 2 ? Long.parseLong(frac) * 10L
                : frac.length() == 3 ? Long.parseLong(frac) : 0L;
        long begin = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L + ms;
        String payload = raw.substring(m.end());
        Matcher wm = WORD.matcher(payload);
        List<String[]> markers = new ArrayList<>();
        while (wm.find()) markers.add(new String[]{wm.group(1), wm.group(2), wm.group(3)});
        if (markers.isEmpty()) {
            return new Raw(begin, payload.trim(), new ArrayList<TimedText.Word>(), 2);
        }
        StringBuilder text = new StringBuilder();
        boolean allZero = true;
        for (String[] mk : markers) {
            text.append(mk[2]);
            if (!mk[0].equals("0") || !mk[1].equals("0")) allZero = false;
        }
        if (allZero) return new Raw(begin, text.toString().trim(), new ArrayList<TimedText.Word>(), 1);
        List<TimedText.Word> words = new ArrayList<>();
        for (String[] mk : markers) {
            long first = Long.parseLong(mk[0]), second = Long.parseLong(mk[1]);
            long relative = Math.floorDiv(first + second, beginDiv);
            long duration = Math.floorDiv(first - second, durDiv);
            long start = begin + relative;
            if (mk[2].trim().isEmpty()) {
                // The space between two English words has a marker of its own. Dropped, the
                // words would run together; it goes on the end of the word before it.
                if (!words.isEmpty() && !mk[2].isEmpty()) {
                    TimedText.Word prev = words.remove(words.size() - 1);
                    words.add(new TimedText.Word(prev.start, prev.end, prev.text + mk[2]));
                }
                continue;
            }
            if (duration <= 0 || start < begin) continue;
            words.add(new TimedText.Word(start, start + duration, mk[2]));
        }
        return new Raw(begin, text.toString().trim(), words, 0);
    }
}
