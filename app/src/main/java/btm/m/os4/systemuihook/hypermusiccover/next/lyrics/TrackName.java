// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which song a session is playing, for the players that sing into TITLE.
 *
 * Three players on HyperOS write the line being sung into TITLE (for car, Bluetooth and status
 * bar lyrics) and move the song's name into ARTIST - each spelt its own way, read off the logs
 * attached to #47 and #56:
 *
 * - Salt Player (com.salt.music): "歌手 - 歌名", the song after a spaced hyphen;
 * - 汽水音乐 (com.luna.music): "歌名 — 歌手", the song before a spaced em dash - and it switches
 *   between that and the plain shape (TITLE the song, ARTIST the singer) in the middle of a song;
 * - QQ 音乐 with a Bluetooth device connected: "歌名-歌手", a bare hyphen.
 *
 * Read as published, every sung line was a new track: the lyrics were thrown away and looked up
 * again under the line's text (#56, the lyrics flashing past), and the cover was pushed again on
 * every line (#47). What holds still through a song under every one of those shapes is the
 * package, the duration, the album and the singer - once 汽水's "歌名 — 歌手" is read as its singer.
 */
public final class TrackName {

    /** 汽水's separator: "歌名 — 歌手", spaces on both sides of an em dash. */
    static final String EM = " " + (char) 0x2014 + " ";

    private TrackName() {
    }

    /** One way of reading ARTIST as a song and its singer. */
    static final class Split {
        final String song;
        final String singer;
        /**
         * Only an exact match counts. A bare hyphen is part of a great many names - A-Lin, Jay-Z -
         * and a containment test against one half of "A-Lin" would agree with nearly anything.
         */
        final boolean exact;

        Split(String song, String singer, boolean exact) {
            this.song = song;
            this.singer = singer;
            this.exact = exact;
        }
    }

    /** Every song-and-singer reading of ARTIST that one of the three players could mean. */
    static List<Split> splits(String artist) {
        List<Split> out = new ArrayList<>(3);
        if (artist == null) {
            return out;
        }
        String a = artist.trim();
        if (add(out, a, a.indexOf(EM), EM.length(), true, false)) {
            return out;
        }
        if (add(out, a, a.indexOf(" - "), 3, false, false)) {
            return out;
        }
        // QQ's bare hyphen. The first one and the last one, since either the song or the singer
        // may carry a hyphen of its own.
        int first = a.indexOf('-');
        int last = a.lastIndexOf('-');
        add(out, a, first, 1, true, true);
        if (last != first) {
            add(out, a, last, 1, true, true);
        }
        return out;
    }

    private static boolean add(List<Split> out, String a, int at, int len, boolean songFirst,
                               boolean exact) {
        if (at <= 0) {
            return false;
        }
        String head = a.substring(0, at).trim();
        String tail = a.substring(at + len).trim();
        if (head.isEmpty() || tail.isEmpty()) {
            return false;
        }
        out.add(songFirst ? new Split(head, tail, exact) : new Split(tail, head, exact));
        return true;
    }

    /**
     * The reading of ARTIST as song and singer that this player is known to mean, or null when
     * ARTIST is to be taken as it stands.
     *
     * 汽水's em dash says so by itself. A hyphen does not - it is part of a great many names - so
     * one is believed only when its song half is something else this player has said: the album
     * (a single: QQ published "I'sland-I'SLAND" over the album I'sland, with a line being sung as
     * the title), or a title it has published lately (QQ goes back and forth inside one song
     * between TITLE the song with ARTIST the singer, and TITLE a line with ARTIST "歌名-歌手").
     */
    static Split sung(String pkg, String artist, String album) {
        List<Split> splits = splits(artist);
        if (splits.isEmpty()) {
            return null;
        }
        if (artist.contains(EM)) {
            return splits.get(0);
        }
        for (Split s : splits) {
            if (album != null && s.song.equalsIgnoreCase(album.trim())) {
                return s;
            }
        }
        if (pkg != null) {
            String prefix = pkg + "|";
            synchronized (sSongOf) {
                for (Split s : splits) {
                    if (sSongOf.containsKey(prefix + s.song)) {
                        return s;
                    }
                }
                // QQ's own title can carry the singer as well: "乌鸦 - 许嵩 (Vae Xu)" over
                // "乌鸦-许嵩" (2026-10-06). The song half starting a title it published, up to
                // a space or a bracket, is the same evidence.
                for (String seen : sSongOf.keySet()) {
                    if (!seen.startsWith(prefix)) {
                        continue;
                    }
                    for (Split s : splits) {
                        if (titleStartsWith(seen, prefix.length(), s.song)) {
                            return s;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Whether `key` from `from` on is `song` followed by a space or a bracket. */
    private static boolean titleStartsWith(String key, int from, String song) {
        if (!key.startsWith(song, from)) {
            return false;
        }
        int end = from + song.length();
        if (end == key.length()) {
            return true;
        }
        char c = key.charAt(end);
        return c == ' ' || c == '(' || c == (char) 0xFF08;
    }

    /**
     * The singer, out of the ARTIST of a player singing into TITLE (see sung), or ARTIST as it
     * is. Salt's spelling holds still through a song as published; 汽水's and QQ's move between
     * two spellings of one song, and read as published every move was a new track.
     */
    static String singer(String pkg, String artist, String album) {
        Split s = sung(pkg, artist, album);
        return s == null ? artist : s.singer;
    }

    /**
     * The song and singer to search for, when TITLE may be a line being sung - or null to search
     * the fields as published: the reading sung() believes, or the first one when this song's
     * title has moved more than once, which only a player singing into TITLE does. A lookup
     * started mid-song (the cover entered halfway through) is the one that needs this: at the
     * track change TITLE is usually still the song.
     */
    public static String[] searchName(String pkg, MediaMetadata md) {
        if (md == null) {
            return null;
        }
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        Split s = sung(pkg, artist, album);
        if (s == null && singsIntoTitle(songKey(pkg, md))) {
            List<Split> splits = splits(artist);
            s = splits.isEmpty() ? null : splits.get(0);
        }
        return s == null ? null : new String[]{s.song, s.singer};
    }

    /**
     * A title without the credits a storefront hangs on its end - "(feat. ...)", "(with ...)",
     * "[feat. ...]" - which no catalogue spells the same way and which drowned the name in the
     * search: "セカイツナガレ (feat. Liko(CV:Minori Suzuki) & Roy(CV:Yuka Terasaki))" found nothing
     * (2026-10-06). Cut at the first of them, nested brackets and all; a title that is nothing
     * but one is left alone.
     */
    static String undecorated(String title) {
        String low = title.toLowerCase();
        int cut = -1;
        for (String mark : new String[]{"(feat", "(ft.", "(with ", "[feat", "[with ", "（feat"}) {
            int at = low.indexOf(mark);
            if (at > 0 && (cut < 0 || at < cut)) cut = at;
        }
        if (cut <= 0) return title;
        String head = title.substring(0, cut).trim();
        return head.isEmpty() ? title : head;
    }

    /**
     * QQ 音乐's names with their other name in brackets after them: a Japanese title with its
     * Chinese one ("生きていたんだよな (她曾活过啊)"), a singer with the name they go by at home
     * ("爱缪 (あいみょん)", "Daoko (ダヲコ)/米津玄師 (よねづ けんし)"). Searched as published, no
     * catalogue found the song - QQ's own included - and the scoring, which drops brackets, kept
     * "爱缪" and threw away the あいみょん that NetEase credits (2026-10-07).
     *
     * {name, alias} when the text ends in one bracketed part with a name before it, else null.
     */
    static String[] aliased(String s) {
        if (s == null) return null;
        s = s.trim();
        int n = s.length();
        if (n < 4) return null;
        char close = s.charAt(n - 1);
        char open = close == ')' ? '(' : close == (char) 0xFF09 ? (char) 0xFF08 : 0;
        if (open == 0) return null;
        int at = s.lastIndexOf(open);
        if (at <= 0) return null;
        String name = s.substring(0, at).trim();
        String alias = s.substring(at + 1, n - 1).trim();
        if (name.isEmpty() || alias.isEmpty() || alias.indexOf(close) >= 0) return null;
        return new String[]{name, alias};
    }

    /**
     * A title's bracketed translation taken off, for a search: only a Chinese one after a name
     * written in something else. "(Live)", "(粤语版)" after a Chinese title and the like are
     * which recording it is and stay.
     */
    public static String untranslated(String title) {
        String[] a = aliased(title);
        if (a == null || !allHan(a[1]) || allHan(a[0])) return title;
        return a[0];
    }

    /** A singer's bracketed alias taken off, for a search: the name QQ 音乐 itself lists them by. */
    public static String unaliased(String artist) {
        String[] a = aliased(artist);
        return a == null ? artist : a[0];
    }

    private static boolean allHan(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false;
                any = true;
            }
        }
        return any;
    }

    /** Whether the song a source claims is the one ARTIST spells, by one of the readings above. */
    static boolean songIn(String name, String artist) {
        for (Split s : splits(artist)) {
            if (agrees(name, s.song, s.exact)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a source's song and singer are the pair ARTIST spells. The singer only must not
     * disagree: a source with no singer of its own says nothing against it.
     */
    static boolean pairIn(String name, String singer, String artist) {
        for (Split s : splits(artist)) {
            if (!agrees(name, s.song, s.exact)) {
                continue;
            }
            if (singer == null || singer.trim().isEmpty() || agrees(singer, s.singer, s.exact)) {
                return true;
            }
        }
        return false;
    }

    private static boolean agrees(String a, String b, boolean exact) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim().toLowerCase();
        String y = b.trim().toLowerCase();
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return exact ? x.equals(y) : x.contains(y) || y.contains(x);
    }

    /**
     * The song, as the fields that hold still through it: package, singer, album and duration.
     * Empty when the player publishes no duration, since without one the rest cannot tell two
     * songs of one album apart.
     */
    public static String songKey(String pkg, MediaMetadata md) {
        if (pkg == null || md == null) {
            return "";
        }
        long dur;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable t) {
            return "";
        }
        if (dur <= 0) {
            return "";
        }
        String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        return pkg + "|" + singer(pkg, md.getString(MediaMetadata.METADATA_KEY_ARTIST), album)
                + "|" + album + "|" + dur;
    }

    public static String songKey(MediaController c) {
        if (c == null) {
            return "";
        }
        try {
            return songKey(c.getPackageName(), c.getMetadata());
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ titles of one song

    /**
     * Which song each title seen lately belonged to, keyed pkg|title.
     *
     * The track keys Main compares are built from TITLE, and they are compared long after the
     * metadata they came from is gone - the cover's worker holds one across its whole push. So
     * the titles are written down as they go past, against the song they were published with,
     * and two titles of one song are the same track. A player that publishes the next track's
     * title before its duration has a title noted against the old song for one update; the
     * duration's own update writes it down again against the new one, and the change goes
     * through then.
     */
    private static final int TITLES = 64;
    private static final Map<String, String> sSongOf =
            new LinkedHashMap<String, String>(TITLES, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > TITLES;
                }
            };

    public static void note(String pkg, MediaMetadata md) {
        if (pkg == null || md == null) {
            return;
        }
        String title;
        try {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        } catch (Throwable t) {
            return;
        }
        String song = songKey(pkg, md);
        if (title == null || song.isEmpty()) {
            return;
        }
        synchronized (sSongOf) {
            sSongOf.put(pkg + "|" + title, song);
            String last = sLastTitle.put(song, title);
            if (last != null && !last.equals(title)) {
                Integer moves = sMoves.get(song);
                sMoves.put(song, moves == null ? 1 : moves + 1);
            }
        }
    }

    /** The last title each song was seen with, to notice the title moving under one song. */
    private static final Map<String, String> sLastTitle = bounded();
    /**
     * How often each song's title has moved. Once is not enough to call it singing: a player
     * that publishes the next track's duration before its title moves the title once on an
     * ordinary track change.
     */
    private static final Map<String, Integer> sMoves = bounded();

    private static <V> Map<String, V> bounded() {
        return new LinkedHashMap<String, V>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > TITLES;
            }
        };
    }

    /** Whether this song's title has moved more than once - a player singing into TITLE. */
    public static boolean singsIntoTitle(String songKey) {
        if (songKey == null || songKey.isEmpty()) {
            return false;
        }
        synchronized (sSongOf) {
            Integer moves = sMoves.get(songKey);
            return moves != null && moves >= 2;
        }
    }

    public static void note(MediaController c) {
        if (c == null) {
            return;
        }
        try {
            note(c.getPackageName(), c.getMetadata());
        } catch (Throwable ignored) {
        }
    }

    /** Whether two titles from one player were last seen on the same song. */
    public static boolean sameSong(String pkg, String titleA, String titleB) {
        synchronized (sSongOf) {
            String a = sSongOf.get(pkg + "|" + titleA);
            return a != null && a.equals(sSongOf.get(pkg + "|" + titleB));
        }
    }
}
