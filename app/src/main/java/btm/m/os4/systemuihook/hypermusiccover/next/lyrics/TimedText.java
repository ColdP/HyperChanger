// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lyrics read out of a catalogue's own format, written back out as text LyricParse reads.
 *
 * QQ Music's QRC and Kuwo's lrcx are word-timed like NetEase's yrc but in shapes lyrics-core has
 * no parser for - QRC puts each word before its time, "七(0,462)", where yrc puts it after,
 * "(0,462,0)七", and a QRC line handed to the parser as it is comes back with its "[0,6930]" in
 * the first word. So the two sources parse their own format into these lines and hand over yrc,
 * which is the format the rest of the pipeline has been reading from NetEase all along. The
 * translation and the romanisation go the same way as plain LRC, and are joined to the lines by
 * time in LyricParse like NetEase's are.
 */
final class TimedText {

    private TimedText() {
    }

    static final class Word {
        final long start;
        final long end;
        final String text;

        Word(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    static final class Line {
        final long start;
        final long end;
        final List<Word> words;

        Line(long start, long end, List<Word> words) {
            this.start = start;
            this.end = end;
            this.words = words;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) sb.append(w.text);
            return sb.toString();
        }
    }

    /** NetEase's yrc: "[start,duration](start,duration,0)word(start,duration,0)word". */
    static String yrc(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (l.words.isEmpty()) continue;
            sb.append('[').append(l.start).append(',').append(Math.max(0L, l.end - l.start))
                    .append(']');
            for (Word w : l.words) {
                sb.append('(').append(w.start).append(',').append(Math.max(0L, w.end - w.start))
                        .append(",0)").append(w.text);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Plain LRC, one line per start, blank and placeholder lines left out. */
    static String lrc(List<Long> starts, List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < starts.size() && i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null) continue;
            t = t.trim().replaceAll("\\s+", " ");
            // QQ marks a line it has no translation for with "//".
            if (t.isEmpty() || t.equals("//")) continue;
            long ms = starts.get(i);
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", ms / 60000L,
                    ms / 1000L % 60L, ms % 1000L)).append(t).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The lines as plain LRC of their joined words - for a translation or romanisation. */
    static String lrcOf(List<Line> lines) {
        List<Long> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Line l : lines) {
            starts.add(l.start);
            texts.add(l.text());
        }
        return lrc(starts, texts);
    }
}
