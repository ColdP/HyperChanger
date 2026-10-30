// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * The scoring is ported from HyperLyrics Enhanced (juren233/HyperLyrics-Enhanced,
 * online/OnlineLyricTargeterPolicy.kt and OnlineLyricTargeter.kt), GPL-3.0, and is used here under
 * this project's AGPL-3.0 (GPLv3 section 13). Rewritten in Java; the weights, the thresholds and
 * the album suffix list are theirs.
 */
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which of a catalogue's search results is the session's song - one rule for every catalogue.
 *
 * Every by-name source used to carry a matcher of its own, and they drifted: NetEase and KuGou
 * gated on a duration window and ranked the rest, QQ and Kuwo had none. This is HyperLyrics
 * Enhanced's, so that the two modules agree on what a match is when they run side by side, and
 * it scores rather than gates:
 *
 *   title  +50  one cleaned title equal to or containing the other
 *   artist +30  a credited name in common, either containing the other
 *   length +15 within 1.5s, +10 within 5s, -30 beyond - the live take is 25s off its studio one
 *   album  +10  the same album, +5 the same once edition words are stripped from the end
 *   tag    +20  "live" / "remastered" / "cover" / 翻唱 on both sides
 *
 * and a candidate is the song at 85. A right title by the right artist is 80 on its own: it has
 * to be the right length or on the right album as well. Another singer's song of the same name is
 * 50 + 15 + 10 = 75 at best and never passes.
 */
final class LyricMatch {

    private LyricMatch() {
    }

    static final int PASS_SCORE = 85;

    /** One search result, in the terms the scoring asks about. `extra` is the source's own. */
    static final class Candidate {
        final String id;
        final String title;
        final String artist;
        final String album;
        final long durationMs;
        final String extra;

        Candidate(String id, String title, String artist, String album, long durationMs,
                  String extra) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.durationMs = durationMs;
            this.extra = extra;
        }

        @Override
        public String toString() {
            return id + " \"" + title + "\" - " + artist + " [" + album + "] " + durationMs + "ms";
        }
    }

    /** The best candidate and what it scored. */
    static final class Pick {
        final Candidate candidate;
        final int score;
        final boolean artistMatched;

        Pick(Candidate candidate, int score, boolean artistMatched) {
            this.candidate = candidate;
            this.score = score;
            this.artistMatched = artistMatched;
        }

        boolean passes() {
            return candidate != null && score >= PASS_SCORE;
        }
    }

    /** What the session says, cleaned once for every candidate it is compared with. */
    static final class Wanted {
        final String title;
        final List<String> artists;
        final String album;
        final long durationMs;
        final List<String> features;
        /** Credited names, not counting the aliases added to artists. */
        private final int credits;

        Wanted(NcmLyrics.Query q) {
            title = clean(q.title);
            artists = new ArrayList<>();
            int n = 0;
            for (String a : splitArtists(q.artist)) {
                if (!clean(a).isEmpty()) n++;
                artists.add(clean(a));
                // Either of QQ's two names for a singer is them: NetEase credits あいみょん,
                // QQ 爱缪 (TrackName.aliased).
                String[] alias = TrackName.aliased(a);
                if (alias != null) artists.add(clean(alias[1]));
            }
            credits = n;
            album = normalizeAlbum(q.album);
            durationMs = q.durationMs;
            features = features(q.title);
        }

        /** Two or more credited names: "A / B" gets a second, album-keyed search. */
        boolean multiCredit() {
            return credits >= 2;
        }
    }

    /** The highest scorer, passing or not; null candidate for an empty list. */
    static Pick best(List<Candidate> candidates, Wanted w) {
        Candidate best = null;
        int bestScore = -1;
        boolean bestArtist = false;
        for (Candidate c : candidates) {
            int s = score(c, w);
            if (s > bestScore) {
                best = c;
                bestScore = s;
                bestArtist = hasCommonArtist(w.artists, cleanedArtists(c.artist));
            }
        }
        return new Pick(best, bestScore, bestArtist);
    }

    static int score(Candidate c, Wanted w) {
        int score = 0;
        if (w.durationMs > 0 && c.durationMs > 0) score += durationScore(w.durationMs, c.durationMs);
        String t = clean(c.title);
        // Both sides non-empty: a result whose title was all brackets cleans to "", and every
        // title contains "".
        if (!w.title.isEmpty() && !t.isEmpty()
                && (w.title.equals(t) || t.contains(w.title) || w.title.contains(t))) {
            score += 50;
        }
        if (hasCommonArtist(w.artists, cleanedArtists(c.artist))) score += 30;
        score += albumScore(w.album, normalizeAlbum(c.album));
        if (!w.features.isEmpty()) {
            for (String f : features(c.title)) {
                if (w.features.contains(f)) {
                    score += 20;
                    break;
                }
            }
        }
        return score;
    }

    private static int durationScore(long local, long remote) {
        long diff = Math.abs(local - remote);
        if (diff > 5000L) return -30;
        if (diff < 1500L) return 15;
        return 10;
    }

    private static final String[] FEATURES = {"live", "remastered", "翻唱", "cover"};

    private static List<String> features(String title) {
        List<String> out = new ArrayList<>();
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (String f : FEATURES) if (t.contains(f)) out.add(f);
        return out;
    }

    private static List<String> cleanedArtists(String artist) {
        List<String> out = new ArrayList<>();
        for (String a : splitArtists(artist)) out.add(clean(a));
        return out;
    }

    private static boolean hasCommonArtist(List<String> local, List<String> remote) {
        for (String l : local) {
            if (l.isEmpty()) continue;
            for (String r : remote) {
                if (r.isEmpty()) continue;
                if (l.equals(r) || r.contains(l) || l.contains(r)) return true;
            }
        }
        return false;
    }

    static List<String> splitArtists(String value) {
        if (value == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(value.split("[&,，、/／]")));
    }

    private static final java.util.regex.Pattern BRACKETS =
            java.util.regex.Pattern.compile("\\(.*?\\)|\\[.*?]|\\{.*?\\}");
    private static final java.util.regex.Pattern SPACE = java.util.regex.Pattern.compile("\\s+");

    /** Brackets out, lower case, simplified script, no whitespace at all. */
    static String clean(String input) {
        if (input == null) return "";
        String s = BRACKETS.matcher(input).replaceAll("").trim().toLowerCase(Locale.ROOT);
        return SPACE.matcher(NcmLyrics.folded(s)).replaceAll("");
    }

    /** The album the same way, but word boundaries kept until the scoring strips suffixes. */
    static String normalizeAlbum(String input) {
        if (input == null) return "";
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC);
        s = BRACKETS.matcher(s).replaceAll("").trim().toLowerCase(Locale.ROOT);
        return SPACE.matcher(NcmLyrics.folded(s)).replaceAll(" ").trim();
    }

    private static int albumScore(String local, String remote) {
        String le = compact(local), re = compact(remote);
        if (le.isEmpty() || re.isEmpty()) return 0;
        if (le.equals(re)) return 10;
        String lb = compact(stripVersionSuffixes(local)), rb = compact(stripVersionSuffixes(remote));
        return !lb.isEmpty() && lb.equals(rb) ? 5 : 0;
    }

    private static String compact(String s) {
        return SPACE.matcher(s).replaceAll("");
    }

    /**
     * Edition words taken off the end, one at a time, so a live or deluxe release of an album
     * reads as that album. Only whole trailing words: "Greatest Hits" keeps its "Hits", and
     * "Alive" is not read as "live" - an English suffix has to follow a separator.
     */
    private static String stripVersionSuffixes(String value) {
        String result = value.trim();
        boolean changed;
        do {
            changed = false;
            for (String suffix : SUFFIXES) {
                String stripped = stripTrailing(result, suffix);
                if (!stripped.equals(result)) {
                    result = stripped;
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private static String stripTrailing(String value, String suffix) {
        if (suffix.isEmpty() || !value.toLowerCase(Locale.ROOT).endsWith(suffix)) return value;
        if (value.length() == suffix.length()) return "";
        int boundary = value.length() - suffix.length();
        char preceding = value.charAt(boundary - 1);
        boolean cjk = false;
        for (int i = 0; i < suffix.length(); i++) {
            char ch = suffix.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjk = true;
                break;
            }
        }
        if (!cjk && Character.isLetterOrDigit(preceding)) return value;
        String cut = value.substring(0, boundary);
        int end = cut.length();
        while (end > 0 && " -_~·|/".indexOf(cut.charAt(end - 1)) >= 0) end--;
        return cut.substring(0, end);
    }

    /** Longest first, so "live version" goes before "live" can take half of it. */
    private static final String[] SUFFIXES = sortedLongestFirst(
            "live version", "acoustic version", "piano version", "studio version",
            "radio version", "deluxe edition", "full version", "clean version",
            "radio edit", "tv size", "hi-res", "320k",
            "live", "acoustic", "unplugged", "cover", "remastered", "remaster",
            "remix", "remixes", "deluxe", "explicit", "clean", "edited",
            "instrumental", "piano", "demo", "full", "studio", "radio", "edit",
            "single", "ep", "flac", "lossless",
            "现场版", "演唱会版", "不插电版", "木吉他版", "翻唱版", "重制版",
            "重置版", "混音版", "豪华版", "伴奏版", "纯音乐版", "钢琴版",
            "试听版", "完整版", "录音室版", "电台版", "单曲版", "无损版",
            "高音质版", "cover版", "remix版", "tv版", "短版", "剪辑版",
            "现场", "演唱会", "不插电", "吉他版", "翻唱", "重制", "重置",
            "混音", "豪华", "伴奏", "纯音乐", "试听", "录音室", "电台",
            "单曲", "无损", "高音质", "版");

    private static String[] sortedLongestFirst(String... s) {
        String[] out = s.clone();
        Arrays.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        return out;
    }
}
