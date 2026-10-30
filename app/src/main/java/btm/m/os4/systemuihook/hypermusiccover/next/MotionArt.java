// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a track's Apple Music motion artwork and brings it home as a plain MP4.
 *
 * No account, no token: everything is read off the public web pages, the way a browser that is
 * not signed in sees them.
 *
 *   music.apple.com/{cc}/search?term=title artist   songs, each with its album's id
 *   music.apple.com/{cc}/album/{id}                 the track list with durations, and the album
 *                                                   header's motionDetailSquare HLS master
 *   master playlist -> an H.264 SDR variant, 1080 at most
 *   media playlist  -> one fragmented MP4, addressed in byte ranges: init + fragments
 *   -> downloaded, then re-muxed into an ordinary MP4 whose clock starts at zero, so that
 *      MediaPlayer's loop lands back on the first frame
 *
 * The search page rather than the iTunes Search API: that one answers nothing at all for the CN
 * storefront (resultCount 0 for "Taylor Swift 1989", 2026-10-13), and CN is where the module's
 * users are. The pages are not a published API - their shape is read defensively and any
 * surprise is a miss, never a guess.
 *
 * Worker thread only. Everything here blocks.
 */
final class MotionArt {

    private MotionArt() {
    }

    static final String TAG = "MCMotion";
    private static final String UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final String[] STOREFRONTS = {"cn", "us"};
    /** Candidates whose album page is read, per storefront. Each one is a ~500KB page. */
    private static final int MAX_CANDIDATES = 3;
    private static volatile java.util.concurrent.ExecutorService sPool;

    /**
     * The lookups' pool, built on first use rather than at class load: retirement shuts it
     * down through Gen, and the next generation builds its own.
     */
    private static java.util.concurrent.ExecutorService pool() {
        java.util.concurrent.ExecutorService p = sPool;
        if (p == null) {
            synchronized (MotionArt.class) {
                p = sPool;
                if (p == null) {
                    p = Gen.executor("MCMotionNet", MAX_CANDIDATES, Thread.NORM_PRIORITY, true);
                    sPool = p;
                }
            }
        }
        return p;
    }
    private static final int MAX_SIDE = 1080;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    /** A track whose length is known has to land within this of the store's. */
    private static final long DURATION_SLACK_MS = 3000L;

    /** What a lookup came to. */
    static final class Found {
        final String albumId;
        final String albumTitle;
        /** The HLS master of the album's square motion artwork. */
        final String master;

        Found(String albumId, String albumTitle, String master) {
            this.albumId = albumId;
            this.albumTitle = albumTitle;
            this.master = master;
        }
    }

    /** Thrown for a request that did not arrive - worth trying again later, unlike a miss. */
    static final class Transient extends Exception {
        Transient(String why) {
            super(why);
        }
    }

    // ================================================================ matching

    /**
     * The album this track is on and its motion artwork, or null when the store has the track
     * but no motion artwork for it, or does not have the track at all.
     */
    static Found find(String title, String artist, String album, long durationMs)
            throws Transient {
        String term = (title + " " + firstArtist(artist)).trim();
        boolean anyAnswer = false;
        for (String cc : STOREFRONTS) {
            String page = fetch("https://music.apple.com/" + cc + "/search?term="
                    + enc(term), "search " + cc);
            if (page == null) continue;
            anyAnswer = true;
            List<String[]> songs = songsOf(page, title, artist);
            Xp.log("[" + TAG + "] " + cc + ": " + songs.size() + " song(s) titled like it");
            Found fallback = null;
            // The candidates' album pages all at once - each is ~500KB and they were the bulk of
            // a lookup's time one after another - and then judged in the search's order.
            List<java.util.concurrent.Future<String>> pages = new ArrayList<>();
            for (int k = 0; k < songs.size() && k < MAX_CANDIDATES; k++) {
                final String url = "https://music.apple.com/" + cc + "/album/" + songs.get(k)[1];
                final String what = "album " + songs.get(k)[1];
                pages.add(pool().submit(() -> fetch(url, what)));
            }
            for (int k = 0; k < pages.size(); k++) {
                String[] s = songs.get(k);
                String albumPage;
                try {
                    albumPage = pages.get(k).get();
                } catch (Throwable t) {
                    albumPage = null;
                }
                if (albumPage == null) continue;
                JSONArray sections = sections(albumPage);
                if (sections == null) continue;
                long storeMs = durationOf(sections, s[0]);
                if (durationMs > 0 && storeMs > 0
                        && Math.abs(storeMs - durationMs) > DURATION_SLACK_MS) {
                    Xp.log("[" + TAG + "] album " + s[1] + ": length " + storeMs + "ms, not "
                            + durationMs + "ms");
                    continue;
                }
                JSONObject header = header(sections);
                String albumTitle = header == null ? "" : header.optString("title", "");
                String master = header == null ? null : motionSquare(header);
                boolean sameAlbum = !norm(album).isEmpty()
                        && norm(stripEdition(albumTitle)).equals(norm(stripEdition(album)));
                Xp.log("[" + TAG + "] album " + s[1] + " \"" + albumTitle + "\" sameAlbum="
                        + sameAlbum + " motion=" + (master != null));
                if (sameAlbum || norm(album).isEmpty()) {
                    // The album the player names, or no name to go by: this is the one, motion
                    // artwork or not. A different album's video is not taken in its place.
                    return master == null ? null : new Found(s[1], albumTitle, master);
                }
                // Same title, same artist, same length, another album: a single, a deluxe
                // edition, a compilation. Kept in case the player's album is not in this store
                // at all - as when its name is in the other script.
                if (fallback == null && master != null) {
                    fallback = new Found(s[1], albumTitle, master);
                }
            }
            if (fallback != null) {
                Xp.log("[" + TAG + "] the player's album was not found; using \""
                        + fallback.albumTitle + "\"");
                return fallback;
            }
        }
        if (!anyAnswer) throw new Transient("no storefront answered");
        return null;
    }

    /** The search page's songs titled like this one, as {songId, albumId}, in its order. */
    private static List<String[]> songsOf(String page, String title, String artist) {
        List<String[]> out = new ArrayList<>();
        JSONArray sections = sections(page);
        if (sections == null) return out;
        String want = norm(stripFeat(title));
        String who = norm(firstArtist(artist));
        for (int i = 0; i < sections.length(); i++) {
            JSONObject sec = sections.optJSONObject(i);
            if (sec == null) continue;
            JSONArray items = sec.optJSONArray("items");
            if (items == null) continue;
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                if (it == null) continue;
                JSONObject cd = it.optJSONObject("contentDescriptor");
                if (cd == null || !"song".equals(cd.optString("kind"))) continue;
                if (!want.equals(norm(stripFeat(it.optString("title", ""))))) continue;
                String by = subtitleArtist(it);
                // The artist as a loose check: the store may spell it in the other script.
                if (!who.isEmpty() && !by.isEmpty() && !norm(by).contains(who)
                        && !who.contains(norm(by))) {
                    continue;
                }
                Matcher m = ALBUM_URL.matcher(cd.optString("url", ""));
                if (!m.find()) continue;
                String songId = m.group(2);
                boolean dup = false;
                for (String[] o : out) dup |= o[0].equals(songId);
                if (!dup) out.add(new String[]{songId, m.group(1)});
            }
        }
        return out;
    }

    private static final Pattern ALBUM_URL = Pattern.compile("/album/(?:[^/?]+/)?(\\d+)\\?i=(\\d+)");

    private static String subtitleArtist(JSONObject it) {
        JSONArray links = it.optJSONArray("subtitleLinks");
        if (links != null && links.length() > 0) {
            JSONObject l = links.optJSONObject(0);
            if (l != null) return l.optString("title", "");
        }
        // The top result writes it as "歌曲 · 周杰伦".
        String sub = it.optString("subtitle", "");
        int dot = sub.indexOf('·');
        return dot >= 0 ? sub.substring(dot + 1).trim() : "";
    }

    private static JSONArray sections(String page) {
        int a = page.indexOf("id=\"serialized-server-data\">");
        if (a < 0) return null;
        a = page.indexOf('>', a) + 1;
        int b = page.indexOf("</script>", a);
        if (b < 0) return null;
        try {
            JSONObject root = new JSONObject(page.substring(a, b));
            return root.getJSONArray("data").getJSONObject(0).getJSONObject("data")
                    .getJSONArray("sections");
        } catch (Throwable t) {
            Xp.log("[" + TAG + "] page not in the shape expected: " + t);
            return null;
        }
    }

    private static JSONObject section(JSONArray sections, String prefix) {
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.optJSONObject(i);
            if (s != null && s.optString("id").startsWith(prefix)) return s;
        }
        return null;
    }

    /** The album header - its own section, never a related album further down the page. */
    private static JSONObject header(JSONArray sections) {
        JSONObject s = section(sections, "album-detail-header");
        JSONArray items = s == null ? null : s.optJSONArray("items");
        return items == null ? null : items.optJSONObject(0);
    }

    private static String motionSquare(JSONObject header) {
        JSONObject va = header.optJSONObject("videoArtwork");
        JSONObject dict = va == null ? null : va.optJSONObject("dictionary");
        JSONObject sq = dict == null ? null : dict.optJSONObject("motionDetailSquare");
        String url = sq == null ? null : sq.optString("video", null);
        return url != null && url.startsWith("https://") ? url : null;
    }

    /** The store's length of one song on the album, 0 when it is not listed. */
    private static long durationOf(JSONArray sections, String songId) {
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.optJSONObject(i);
            if (s == null || !s.optString("id").startsWith("track-list")) continue;
            JSONArray items = s.optJSONArray("items");
            if (items == null) continue;
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                JSONObject cd = it == null ? null : it.optJSONObject("contentDescriptor");
                JSONObject ids = cd == null ? null : cd.optJSONObject("identifiers");
                if (ids != null && songId.equals(ids.optString("storeAdamID"))) {
                    return it.optLong("duration", 0L);
                }
            }
        }
        return 0L;
    }

    // ================================================================ text

    /** For comparing names: width, case and every character that is not a letter or digit. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); ) {
            int c = n.codePointAt(i);
            if (Character.isLetterOrDigit(c)) b.appendCodePoint(c);
            i += Character.charCount(c);
        }
        return b.toString();
    }

    /** "Song (feat. X)" and "Song [with X]" are the song; a version mark is not cut. */
    private static String stripFeat(String s) {
        return s == null ? "" : s.replaceAll("(?i)\\s*[(\\[（【](feat\\.?|ft\\.|with)\\s[^)\\]）】]*[)\\]）】]", "");
    }

    /** The store's " - Single" / " - EP" suffix, which the players do not write. */
    private static String stripEdition(String s) {
        return s == null ? "" : s.replaceAll("(?i)\\s+-\\s+(single|ep)$", "");
    }

    /** The first of several credited artists, which is what the store searches well on. */
    static String firstArtist(String s) {
        if (s == null) return "";
        String[] parts = s.split("\\s*(/|、|,|，|&| feat\\.? | ft\\. | x )\\s*");
        return parts.length == 0 ? s.trim() : parts[0].trim();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    // ================================================================ network

    private static String fetch(String url, String what) {
        Http.Raw r = Http.request(url, TAG, null, "User-Agent", UA);
        if (r.body == null) {
            Xp.log("[" + TAG + "] " + what + ": HTTP " + r.code);
            return null;
        }
        try {
            return new String(r.body, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ download

    /**
     * The motion artwork into `out` as a plain MP4. False for a variant this cannot use; a
     * Transient for a network that failed on the way.
     */
    static boolean download(String master, File out) throws Transient {
        String m3u = fetch(master, "master");
        if (m3u == null) throw new Transient("master");
        String variant = pickVariant(master, m3u);
        if (variant == null) {
            Xp.log("[" + TAG + "] no H.264 SDR variant at or under " + MAX_SIDE);
            return false;
        }
        String media = fetch(variant, "media playlist");
        if (media == null) throw new Transient("media playlist");
        List<long[]> ranges = new ArrayList<>();
        String uri = rangesOf(variant, media, ranges);
        if (uri == null || ranges.isEmpty()) {
            Xp.log("[" + TAG + "] media playlist not one byte-ranged file");
            return false;
        }
        File raw = new File(out.getPath() + ".frag");
        File tmp = new File(out.getPath() + ".tmp");
        long t0 = SystemClock.uptimeMillis();
        try {
            long bytes = 0;
            try (FileOutputStream os = new FileOutputStream(raw)) {
                // Contiguous ranges of one file are asked for as one; there is no gap to skip.
                int i = 0;
                while (i < ranges.size()) {
                    long from = ranges.get(i)[0], to = from + ranges.get(i)[1];
                    int j = i + 1;
                    while (j < ranges.size() && ranges.get(j)[0] == to) {
                        to += ranges.get(j)[1];
                        j++;
                    }
                    bytes += get(uri, from, to, os, MAX_BYTES - bytes);
                    i = j;
                }
            }
            long fetched = SystemClock.uptimeMillis();
            remux(raw, tmp);
            if (!tmp.renameTo(out)) throw new java.io.IOException("rename");
            Xp.log("[" + TAG + "] downloaded " + (bytes / 1024) + "KB in " + (fetched - t0)
                    + "ms, remuxed in " + (SystemClock.uptimeMillis() - fetched) + "ms -> "
                    + out.getName() + " " + (out.length() / 1024) + "KB");
            return true;
        } catch (Transient t) {
            throw t;
        } catch (Throwable t) {
            Xp.w("[" + TAG + "] could not make the MP4: " + t);
            return false;
        } finally {
            raw.delete();
            tmp.delete();
        }
    }

    /**
     * The H.264 SDR stream at the largest size up to MAX_SIDE, the leanest of those at that
     * size. Apple lists several bitrates per size; the card is not 1080 wide anyway.
     */
    private static String pickVariant(String base, String m3u) {
        String best = null;
        int bestSide = 0;
        long bestBw = Long.MAX_VALUE;
        String[] lines = m3u.split("\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF:")) continue;
            String codecs = attr(l, "CODECS");
            String range = attr(l, "VIDEO-RANGE");
            String res = attr(l, "RESOLUTION");
            long bw = parseLong(attr(l, "BANDWIDTH"));
            if (codecs == null || !codecs.startsWith("avc1") || !"SDR".equals(range)
                    || res == null) {
                continue;
            }
            String[] wh = res.split("x");
            if (wh.length != 2) continue;
            int w = (int) parseLong(wh[0]), h = (int) parseLong(wh[1]);
            int side = Math.max(w, h);
            if (side > MAX_SIDE || w <= 0 || h <= 0) continue;
            if (side > bestSide || (side == bestSide && bw < bestBw)) {
                best = resolve(base, lines[i + 1].trim());
                bestSide = side;
                bestBw = bw;
            }
        }
        return best;
    }

    /** The ranges of the one file the media playlist addresses, init first. Null otherwise. */
    private static String rangesOf(String base, String media, List<long[]> out) {
        String file = null;
        long next = 0;
        String pendingRange = null;
        for (String raw : media.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("#EXT-X-MAP:")) {
                String u = resolve(base, attr(l, "URI"));
                long[] r = range(attr(l, "BYTERANGE"), 0);
                if (u == null || r == null) return null;
                file = u;
                out.add(r);
                next = r[0] + r[1];
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = l.substring("#EXT-X-BYTERANGE:".length()).trim();
            } else if (!l.isEmpty() && !l.startsWith("#")) {
                String u = resolve(base, l);
                long[] r = pendingRange == null ? null : range(pendingRange, next);
                pendingRange = null;
                if (u == null || r == null) return null;
                if (file == null) file = u;
                if (!file.equals(u)) return null;   // segments in separate files: not handled
                out.add(r);
                next = r[0] + r[1];
            }
        }
        return file;
    }

    /** "len@offset", or "len" continuing from `next`, as {offset, len}. */
    private static long[] range(String spec, long next) {
        if (spec == null) return null;
        spec = spec.replace("\"", "").trim();
        int at = spec.indexOf('@');
        long len = parseLong(at < 0 ? spec : spec.substring(0, at));
        long off = at < 0 ? next : parseLong(spec.substring(at + 1));
        return len > 0 && off >= 0 ? new long[]{off, len} : null;
    }

    private static long get(String uri, long from, long to, FileOutputStream os, long budget)
            throws Transient, java.io.IOException {
        if (to - from > budget) throw new java.io.IOException("over " + MAX_BYTES + " bytes");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=" + from + "-" + (to - 1));
            int code = c.getResponseCode();
            if (code != 206 && !(code == 200 && from == 0)) {
                throw new Transient("range answered " + code);
            }
            long want = to - from, got = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream()) {
                int n;
                while (got < want && (n = in.read(buf, 0, (int) Math.min(buf.length, want - got))) > 0) {
                    os.write(buf, 0, n);
                    got += n;
                }
            }
            if (got != want) throw new Transient("short read " + got + "/" + want);
            return got;
        } catch (java.net.SocketException | java.net.SocketTimeoutException
                 | java.net.UnknownHostException e) {
            throw new Transient(e.toString());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Fragments to a plain MP4 with its first sample at zero. Samples are copied, never decoded.
     * Apple's fragments start their clock wherever the master did; looped as they are, the player
     * would wait out that offset on every pass.
     */
    private static void remux(File src, File dst) throws java.io.IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(src.getPath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/avc")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) throw new java.io.IOException("no H.264 track");
            ex.selectTrack(track);
            MediaFormat fmt = ex.getTrackFormat(track);
            mux = new MediaMuxer(dst.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int out = mux.addTrack(fmt);
            mux.start();
            started = true;
            int cap = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 4 * 1024 * 1024;
            ByteBuffer buf = ByteBuffer.allocate(Math.max(cap, 1024 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long origin = -1;
            int samples = 0;
            while (true) {
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                long t = ex.getSampleTime();
                if (origin < 0) origin = t;
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, n, Math.max(0L, t - origin), flags);
                mux.writeSampleData(out, buf, info);
                samples++;
                ex.advance();
            }
            if (samples == 0) throw new java.io.IOException("no samples");
        } finally {
            ex.release();
            if (mux != null) {
                try {
                    if (started) mux.stop();
                } catch (Throwable ignored) {
                }
                mux.release();
            }
        }
    }

    // ================================================================ playlist text

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[:,])" + Pattern.quote(name)
                + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) return null;
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
