package btm.m.os4.systemuihook.hypermusiccover

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/**
 * Whatever a lyric source handed us, turned into lines the renderer can draw.
 *
 * The formats are not ours to choose - the AMLL database alone ships TTML, LRC, YRC, QRC and
 * Lyricify for the same song - so AutoParser sniffs the format and this is the only place that
 * knows there was ever more than one. Kotlin only because AutoParser's constructor is all default
 * arguments, which Java cannot call.
 */
object LyricParse {

    @JvmStatic
    fun parse(body: String): List<LyricLine> {
        val lyrics = AutoParser().parse(body)
        val out = ArrayList<LyricLine>(lyrics.lines.size)
        for (line in lyrics.lines) {
            when (line) {
                // Background vocals overlap the main line in time, so they are not lines of their
                // own - the renderer finds the singing line by start time, and one would steal
                // the focus for the length of an echo. They hang under the main line they belong
                // to (the last one started by then), and stretch it if they outlast it.
                is KaraokeLine.AccompanimentKaraokeLine -> {
                    val b = karaoke(line) ?: continue
                    val owner = out.lastOrNull { it.start <= b.start } ?: continue
                    if (owner.bg == null) {
                        owner.bg = b
                        if (b.end > owner.end) owner.end = b.end
                    }
                }
                is KaraokeLine -> karaoke(line)?.let { out.add(it) }
                is SyncedLine -> {
                    // Instrumental breaks arrive as empty lines; a row of nothing would take a
                    // slot in the stack for its whole duration.
                    if (line.content.isBlank()) continue
                    out.add(LyricLine(line.content.trim(), line.translation,
                        line.start, line.end, false, null, null, null))
                }
            }
        }
        out.sortBy { it.start }
        return speakers(out)
    }

    private fun karaoke(line: KaraokeLine): LyricLine? {
        val syl = line.syllables
        if (syl.isEmpty()) return null
        val text = StringBuilder()
        val starts = IntArray(syl.size)
        val ends = IntArray(syl.size)
        val chars = IntArray(syl.size)
        for ((k, s) in syl.withIndex()) {
            text.append(s.content)
            starts[k] = s.start
            ends[k] = s.end
            chars[k] = text.length
        }
        // Trailing spaces belong to the last word in English files and would push a wrapped
        // line's measured width past its ink. Leading ones cannot be trimmed without shifting
        // every syllable's character range, and the files do not have them.
        var n = text.length
        while (n > 0 && text[n - 1].isWhitespace()) n--
        if (n == 0) return null
        for (k in chars.indices) if (chars[k] > n) chars[k] = n
        return LyricLine(text.substring(0, n), line.translation, line.start, line.end,
            line.alignment == KaraokeAlignment.End, starts, ends, chars)
    }

    /** "筷：" or "Jay: " at the head of a line - a name, then a full- or half-width colon. */
    private val LABEL = Regex("^([^\\s\\d:：]{1,6})\\s*[:：]\\s*")

    /** Labels that mean everyone at once. Drawn on the first singer's side. */
    private val TOGETHER = setOf("合", "合唱", "全", "All", "ALL", "all")

    /**
     * Duets marked the way NetEase lyrics mark them: the singer's name as a prefix on the line
     * where the voice changes ("筷：苍茫的天涯是我的爱", "凤：变成蜡烛燃烧自己"), holding until
     * the next prefix. MeiLoX reads the same prefixes to put the two voices on either side; the
     * files carry no other trace of who sings what.
     *
     * A name only counts once it has marked two lines, which leaves out the credits at the top
     * ("作词: ...", "作曲: ...") that appear once each. It takes two such names to be a duet; the
     * first to sing is on the left, the second on the right, and the prefix is not shown. A file
     * whose lines already say which side they are on (TTML's agents) is left as it is.
     */
    private fun speakers(lines: List<LyricLine>): List<LyricLine> {
        if (lines.any { it.opposite }) return lines
        val labels = lines.map { LABEL.find(it.text)?.groupValues?.get(1) }
        val counts = labels.filterNotNull().groupingBy { it }.eachCount()
        val singers = LinkedHashSet<String>()
        for (l in labels) {
            if (l != null && l !in TOGETHER && (counts[l] ?: 0) >= 2) singers.add(l)
        }
        if (singers.size < 2) return lines
        val order = singers.toList()
        val out = ArrayList<LyricLine>(lines.size)
        var right = false
        for ((i, line) in lines.withIndex()) {
            val label = labels[i]
            val known = label != null && (label in TOGETHER || label in singers)
            if (known) right = label !in TOGETHER && order.indexOf(label) % 2 == 1
            val cut = if (known) LABEL.find(line.text)!!.range.last + 1 else 0
            out.add(relabel(line, cut, right) ?: continue)
        }
        return out
    }

    /** The same line with its first `cut` characters gone and put on the given side. */
    private fun relabel(line: LyricLine, cut: Int, opposite: Boolean): LyricLine? {
        if (cut >= line.text.length) return null
        val text = line.text.substring(cut)
        val result = if (line.sylStart == null) {
            LyricLine(text, line.translation, line.start, line.end, opposite, null, null, null)
        } else {
            // Syllables that lay wholly inside the prefix go with it; the rest shift left.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val chars = ArrayList<Int>()
            for (k in line.sylStart.indices) {
                val e = line.charEnd[k] - cut
                if (e <= 0) continue
                starts.add(line.sylStart[k])
                ends.add(line.sylEnd[k])
                chars.add(e)
            }
            if (chars.isEmpty()) return null
            LyricLine(text, line.translation, line.start, line.end, opposite,
                starts.toIntArray(), ends.toIntArray(), chars.toIntArray())
        }
        result.bg = line.bg
        return result
    }
}
