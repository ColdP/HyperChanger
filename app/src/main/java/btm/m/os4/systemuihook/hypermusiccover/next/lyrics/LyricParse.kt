// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
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

    /**
     * The same thing for a source that ships its translation separately.
     *
     * NetEase does: the timed lyric comes back under "yrc" (or "lrc") and the translated lines
     * under "tlyric", as a second plain LRC with no link to the first beyond its timestamps. So
     * the two are joined here by time rather than by index - the counts do not match, because
     * the translation has no entries for the instrumental breaks or the credits, and pairing
     * them off in order would slide every translation one line up partway through the song.
     */
    @JvmStatic
    fun parse(body: String, translation: String?): List<LyricLine> {
        val lines = parse(body)
        if (translation.isNullOrBlank() || lines.isEmpty()) return lines
        val tr = lrc(translation)
        if (tr.isEmpty()) return lines
        val texts = assign(lines, tr, ::translatable)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val text = texts[i]
            out.add(if (text == null) line else withTranslation(line, text))
        }
        return out
    }

    /**
     * A line a translation can belong to: not a singer's name standing alone ("应采儿:"), which
     * NetEase gives a line of its own just before the voice changes and which, being the nearer,
     * took the translation of the line it introduces.
     */
    private fun translatable(line: LyricLine): Boolean {
        val m = LABEL.find(line.text) ?: return true
        return m.range.last + 1 < line.text.length
    }

    /**
     * A line a romanisation can belong to: one with letters in a script other than Latin. An
     * English line in a Japanese song has nothing to romanise, and the nearest one took the
     * romanisation of the Japanese line beside it - shown under the English and missing under
     * its own line (KillKiss, One Last Kiss, 2026-10-07).
     */
    private fun romanisableLine(line: LyricLine): Boolean = nonLatin(line.text)

    private fun nonLatin(s: String): Boolean = s.any { it.isLetter() && it.code > 0x024F }

    /**
     * The same again with a romanisation beside the translation - QQ Music, NetEase and Kuwo
     * ship one for Japanese and Korean songs, as a third timed text joined by time like the
     * translation. Kept on the line as its own field, drawn above the translation under a
     * switch of its own (LockLyrics.sRoma).
     */
    @JvmStatic
    fun parse(body: String, translation: String?, roma: String?): List<LyricLine> {
        val lines = parse(body, translation)
        if (roma.isNullOrBlank() || lines.isEmpty()) return lines
        val ro = lrc(roma)
        if (ro.isEmpty()) return lines
        val best = assign(lines, ro, ::romanisableLine)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val r = romaOf(best[i], line.text)
            out.add(if (r == null || line.roma != null) line else withRoma(line, r))
        }
        return out
    }

    /**
     * Each timed entry of a translation or romanisation, given to the ONE line nearest it - not
     * each line taking the nearest entry. A catalogue leaves its credit lines untranslated, and
     * asked the other way round the credits 500ms before the first verse took the verse's
     * translation as their own (QQ's Lemon, 2026-09-24). One entry, one line; the closer of two
     * claimants keeps it. See TRANSLATION_WINDOW_MS for the window. Only lines that can carry
     * one are claimants (translatable, romanisableLine): the others are passed over, not given it.
     */
    private fun assign(lines: List<LyricLine>, entries: List<Pair<Int, String>>,
                       eligible: (LyricLine) -> Boolean = { true }): Array<String?> {
        val best = arrayOfNulls<String>(lines.size)
        val gaps = IntArray(lines.size) { Int.MAX_VALUE }
        val open = lines.indices.filter { eligible(lines[it]) }
        if (open.isEmpty()) return best
        val starts = IntArray(open.size) { lines[open[it]].start }
        // An entry that is only some line of the song again - an English line "romanised" or
        // "translated" into itself - is no entry. Kept, it was drawn under its own line twice
        // over (Everywhere We Go, 2026-10-07), and with that line passed over it would go to
        // the nearest line that can take one.
        val sung = lines.mapTo(HashSet()) { letters(it.text) }
        for ((at, text) in entries) {
            if (letters(text) in sung) continue
            var k = starts.binarySearch(at)
            if (k < 0) {
                val ins = -k - 1
                k = when {
                    ins == 0 -> 0
                    ins >= starts.size -> starts.size - 1
                    at - starts[ins - 1] <= starts[ins] - at -> ins - 1
                    else -> ins
                }
            }
            val i = open[k]
            val gap = kotlin.math.abs(starts[k] - at)
            if (gap <= TRANSLATION_WINDOW_MS && gap < gaps[i]) {
                gaps[i] = gap
                best[i] = text
            }
        }
        return best
    }

    /** What borrow() took: the lines, how many of them gained each kind, and the account of it. */
    class Lent(
        @JvmField val lines: List<LyricLine>,
        @JvmField val translations: Int,
        @JvmField val romas: Int,
        @JvmField val why: String,
        /** The other copy is this song, whether or not it had anything to lend. */
        @JvmField val matched: Boolean = false,
    )

    /**
     * Another copy's translations or romanisations, hung on our own lines.
     *
     * The lyric on screen is the best copy there is of the words and their timing - the
     * player's, the file's, the database's - and it is often one with no translation: Apple asks
     * for one in the system's language and has none for most Japanese and English songs (#62),
     * a file saved without one has none (#64), and a romanisation is rarer still. So the timing
     * and the words stay ours, and only what was asked for moves across, each entry to the one
     * line nearest it (assign), and only to a line without one of its own.
     *
     * Only from a copy of the same song: at least two lines in five of ours have to find the
     * same words, give or take punctuation and case, near them in the other copy - which also
     * says how far that copy is shifted against ours. A translation or a romanisation that only
     * repeats its line is not one.
     */
    @JvmStatic
    fun borrow(own: List<LyricLine>, other: List<LyricLine>, translations: Boolean,
               romas: Boolean): Lent {
        if (own.isEmpty() || other.isEmpty() || !(translations || romas)) {
            return Lent(own, 0, 0, "nothing to borrow into or from")
        }
        // Where the other copy's lines sit against ours. Two releases of a song are often
        // offset by a fraction of a second or more (another master, a longer intro), and the
        // two copies split lines their own way - Apple's TTML against NetEase's LRC for Glee's
        // Just Give Me a Reason found too few lines of the same words in the same place and lent
        // nothing (2026-10-06). So each of our lines looks for the same words, or one copy's
        // line inside the other's, within OFFSET_SEARCH_MS, and the median of those gaps is how
        // far the other copy is shifted.
        val gaps = ArrayList<Int>()
        for (line in own) {
            val k = letters(line.text)
            if (k.length < 2) continue
            var best = Int.MAX_VALUE
            for (o in other) {
                val d = o.start - line.start
                if (kotlin.math.abs(d) <= OFFSET_SEARCH_MS && kotlin.math.abs(d) < kotlin.math.abs(best)
                    && sameWords(k, letters(o.text))) best = d
            }
            if (best != Int.MAX_VALUE) gaps.add(best)
        }
        if (gaps.size * 5 < own.size * 2) {
            return Lent(own, 0, 0, "only ${gaps.size} of ${own.size} lines found in the other copy")
        }
        gaps.sort()
        val shift = gaps[gaps.size / 2]
        val tr = if (!translations) null
            else assign(own, other.mapNotNull { o -> o.translation?.let { (o.start - shift) to it } },
                ::translatable)
        val ro = if (!romas) null
            else assign(own, other.mapNotNull { o -> o.roma?.let { (o.start - shift) to it } },
                ::romanisableLine)
        var nt = 0
        var nr = 0
        val out = own.mapIndexed { i, line ->
            var l = line
            val t = tr?.get(i)
            if (t != null && l.translation == null && letters(t) != letters(l.text)) {
                l = withTranslation(l, t)
                nt++
            }
            val r = romaOf(ro?.get(i), l.text)
            if (r != null && l.roma == null) {
                l = withRoma(l, r)
                nr++
            }
            l
        }
        return Lent(if (nt + nr == 0) own else out, nt, nr,
            "$nt translations, $nr romanisations into ${own.size} lines, the other copy ${shift}ms off",
            true)
    }

    /** How far apart the two copies may be for a line to count towards their offset. */
    private const val OFFSET_SEARCH_MS = 5000

    /**
     * The same line in two copies, after letters(): the same, or one inside the other where the
     * shorter is at least half the longer - a copy that splits a line in two still matches it.
     */
    private fun sameWords(a: String, b: String): Boolean {
        if (a == b) return true
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= 4 && short.length * 2 >= long.length && long.contains(short)
    }

    /**
     * Whether a lyric is in a language worth translating here: more kana, hangul and Latin
     * letters than Han characters. A Chinese song has no translation anywhere, and asking the
     * catalogues for one on every Chinese track would be requests for nothing.
     */
    @JvmStatic
    fun foreign(lines: List<LyricLine>): Boolean {
        var han = 0
        var other = 0
        for (line in lines) {
            for (ch in line.text) {
                when {
                    ch.code in 0x4E00..0x9FFF -> han++
                    ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF -> other++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> other++
                }
            }
        }
        return other > han
    }

    /**
     * Whether a lyric is in a script a romanisation says anything about: a quarter or more of
     * its lines in a script other than Latin. Chinese counts - a Cantonese song's jyutping is
     * one - and an English song does not, whose "romanisation" in a catalogue is the song again;
     * its credit lines in Chinese are fewer than that.
     *
     * Counted by lines, not letters: an English word is several letters and a Japanese or Korean
     * one is one or two characters, so a song half in each read as English, and Make A Wish,
     * Life Is Like A Boat and KillKiss never asked for the romanisation of their other half
     * (2026-10-07).
     */
    @JvmStatic
    fun romanisable(lines: List<LyricLine>): Boolean {
        var total = 0
        var other = 0
        for (line in lines) {
            if (line.text.none { it.isLetter() }) continue
            total++
            if (nonLatin(line.text)) other++
        }
        return other > 0 && other * 4 >= total
    }

    /**
     * A romanisation fit to put under its line, or null - left out when it only repeats the
     * line, as a catalogue "romanises" an English song into itself.
     */
    internal fun romaOf(roma: String?, text: String): String? {
        val r = roma?.trim()?.replace(Regex("\\s+"), " ")
        if (r.isNullOrEmpty() || letters(r) == letters(text)) return null
        return r
    }

    private fun letters(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * How far a translated line's time may sit from its line's and still be its translation.
     *
     * A window rather than an equality test: yrc and tlyric are timed independently, and the
     * same line measured here starts at 4390ms in the word-timed copy and 4705ms in the LRC -
     * close enough to be obviously the same line, far enough apart that matching exactly would
     * find nothing at all. The window is wide enough for that drift and narrower than the gap
     * between two sung lines, so the nearest line inside it is the right one.
     */
    private const val TRANSLATION_WINDOW_MS = 1500

    /** Timestamped lines of a plain LRC, in time order, with the empty ones left out. */
    private fun lrc(body: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (raw in body.split('\n')) {
            val m = LRC_TIME.find(raw) ?: continue
            val text = raw.substring(m.range.last + 1).trim()
            if (text.isEmpty()) continue
            out.add(Pair(ms(m) ?: continue, text))
        }
        out.sortBy { it.first }
        return out
    }

    /** An LRC time tag's minutes, seconds and fraction, in milliseconds. */
    private fun ms(m: MatchResult): Int? {
        val min = m.groupValues[1].toIntOrNull() ?: return null
        val sec = m.groupValues[2].toIntOrNull() ?: return null
        val frac = m.groupValues[3]
        // One digit is tenths, two are hundredths, three are milliseconds.
        val ms = when (frac.length) {
            0 -> 0
            1 -> (frac.toIntOrNull() ?: 0) * 100
            3 -> frac.toIntOrNull() ?: 0
            else -> (frac.toIntOrNull() ?: 0) * 10
        }
        return min * 60000 + sec * 1000 + ms
    }

    private val LRC_TIME = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val LRC_TAG = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    /** The same line carrying a translation it did not come with. */
    private fun withTranslation(line: LyricLine, text: String): LyricLine {
        val copy = LyricLine(line.text, text, line.roma, line.start, line.end, line.opposite,
            line.sylStart, line.sylEnd, line.charEnd)
        copy.bg = line.bg
        return copy
    }

    /** The same line carrying a romanisation it did not come with. */
    private fun withRoma(line: LyricLine, roma: String): LyricLine {
        val copy = LyricLine(line.text, line.translation, roma, line.start, line.end,
            line.opposite, line.sylStart, line.sylEnd, line.charEnd)
        copy.bg = line.bg
        return copy
    }

    @JvmStatic
    fun parse(body: String): List<LyricLine> {
        // lyrics-core throws on lyrics it cannot make sense of - EnhancedLrcParser's
        // rearrangeUncheckedLineTime trips SyncedLine's require() on NetEase 2057709543 (邓紫棋
        // Pasión). Uncaught on a worker thread that is the whole of SystemUI going down, once per
        // play of the song (2026-10-06, twice in fifteen seconds). A lyric that cannot be read is
        // no lyric.
        val prepared = bracketWords(body)
        val lyrics = try {
            AutoParser().parse(prepared.body)
        } catch (t: Throwable) {
            // The log is SystemUI's; on the JVM the tests run on it is not there to write to.
            runCatching { Xp.log("[MCLyric] the parser gave up on a lyric (" + body.length + " chars): " + t) }
            return emptyList()
        }
        val src = unswallow(lyrics.lines, prepared.body)
        val out = ArrayList<LyricLine>(src.size)
        // Where the tail of a line may run to when the file left it without an end of its own:
        // the arrival of the line after it. Looked up by time and not read off the list: the list
        // is sorted only at the end of this function, and a background vocal handed back as a
        // line of its own starts inside the line it echoes - taken as the next line, it would cut
        // the last word down to the few milliseconds before the echo.
        val mains = src.filter { it !is KaraokeLine.AccompanimentKaraokeLine }
            .map { it.start }.sorted().toIntArray()
        for (line in src) {
            val nextStart = nextAfter(mains, line.start, line.end)
            when (line) {
                // Background vocals overlap the main line in time, so they are not lines of their
                // own - the renderer finds the singing line by start time, and one would steal
                // the focus for the length of an echo. They hang under the main line they belong
                // to (the last one started by then), and stretch it if they outlast it.
                is KaraokeLine.AccompanimentKaraokeLine -> {
                    val b = karaoke(line, nextStart)?.let { unwrapBackground(it) } ?: continue
                    val owner = out.lastOrNull { it.start <= b.start } ?: continue
                    if (owner.bg == null) {
                        owner.bg = b
                        if (b.end > owner.end) owner.end = b.end
                    }
                }
                is KaraokeLine -> {
                    val main = karaoke(line, nextStart)
                    if (main != null) {
                        out.add(main)
                        // Where the accompaniment actually arrives. The branch above is written
                        // for a parser that hands background vocals back as lines of their own,
                        // and lyrics-core 0.4.7 does not: it hangs them on the main line as a
                        // property, so that branch never fires and every background vocal was
                        // being dropped. Both are kept - which shape comes back is the library's
                        // business, and a version that goes back to separate lines still works.
                        val acc = (line as? KaraokeLine.MainKaraokeLine)
                            ?.accompanimentLines?.firstOrNull()
                        val b = acc?.let { karaoke(it, nextStart) }?.let { unwrapBackground(it) }
                        if (b != null) {
                            main.bg = b
                            if (b.end > main.end) main.end = b.end
                        }
                    }
                }
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
        return speakers(withLanes(out, prepared.lanes))
    }

    /**
     * Lines the parser took for the translation of the line before them (#113). lyrics-core makes
     * any line starting within 150ms of another, in other words, that line's translation - and a
     * file that crowds its credits into the first fraction of a second ("Lyrics by" at 60ms,
     * "Composed by" at 130, "Produced by" at 200, the first sung line at 271) lost every other
     * one, the first sung line with them: it went under "Produced by" as its translation, and its
     * own translation, at its start with nothing left there to belong to, was drawn as the line.
     *
     * A translation sits at its line's start (LANE_MS). A row of the body further off than that
     * is a line, and goes back to being one - parsed on its own, so its word timings come with
     * it - taking the row left on its own at its start as its translation.
     */
    private fun unswallow(lines: List<ISyncedLine>, body: String): List<ISyncedLine> {
        val rows = body.split('\n').mapNotNull { r ->
            val m = LRC_TIME.find(r.trimStart()) ?: return@mapNotNull null
            Triple(ms(m) ?: return@mapNotNull null,
                r.replace(LRC_TAG, "").replace(ANGLE_TAG, "").trim(), r.trim())
        }
        if (rows.isEmpty()) return lines
        val out = lines.toMutableList()
        var fixed = 0
        var i = 0
        while (i < out.size) {
            val line = out[i]
            val tr = translationOf(line)?.trim()
            val row = if (tr.isNullOrEmpty()) null else rows.firstOrNull {
                val d = kotlin.math.abs(it.first - line.start)
                it.second == tr && d > LANE_MS && d <= SWALLOW_MS
            }
            if (row == null) {
                i++
                continue
            }
            var back = try {
                AutoParser().parse(row.third).lines.firstOrNull()
            } catch (t: Throwable) {
                null
            }
            if (back == null || back.start != row.first) {
                i++
                continue
            }
            out[i] = untranslated(line, row.first)
            // A plain row parsed alone has no next line to end at.
            if (back is SyncedLine) {
                val next = out.map { it.start }.filter { it > row.first }.minOrNull()
                back = back.copy(end = next ?: maxOf(back.end, line.end))
            }
            val text = textOf(back)
            val own = out.indices.firstOrNull { j ->
                val o = out[j]
                j != i && o is SyncedLine && o.translation == null &&
                    kotlin.math.abs(o.start - row.first) <= LANE_MS && o.content.trim() != text
            }
            if (own != null) {
                back = withTranslationOf(back, (out[own] as SyncedLine).content.trim())
                out.removeAt(own)
                if (own < i) i--
            }
            out.add(i + 1, back)
            fixed++
            i++
        }
        if (fixed == 0) return lines
        runCatching { Xp.log("[MCLyric] $fixed lines taken for a translation put back") }
        return out.sortedBy { it.start }
    }

    /**
     * How far apart lyrics-core lets a line and its translation start; past it nothing is
     * paired, so nothing further off can have been swallowed.
     */
    private const val SWALLOW_MS = 150

    private fun translationOf(line: ISyncedLine): String? = when (line) {
        is KaraokeLine -> line.translation
        is SyncedLine -> line.translation
        else -> null
    }

    private fun textOf(line: ISyncedLine): String = when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }.trim()
        is SyncedLine -> line.content.trim()
        else -> ""
    }

    private fun withTranslationOf(line: ISyncedLine, text: String?): ISyncedLine = when (line) {
        is KaraokeLine.MainKaraokeLine -> line.copy(translation = text)
        is KaraokeLine.AccompanimentKaraokeLine -> line.copy(translation = text)
        is SyncedLine -> line.copy(translation = text)
        else -> line
    }

    /** A line without the translation it swallowed, ending no later than that line starts. */
    private fun untranslated(line: ISyncedLine, until: Int): ISyncedLine =
        if (line is SyncedLine && line.end > until) line.copy(translation = null, end = until)
        else withTranslationOf(line, null)

    /**
     * The translation some tag writers leave on the line itself, separated by a thin space
     * instead of shipped as a row of its own (#95).
     *
     * MusicTag for Android and the NetEase exports written by it join a line and its translation
     * with U+2009 - "Knows me the way I do when I feel overwhelmed\u2009无人像我自己一般了解我"
     * - and Salt Player reads that separator. lyrics-core does not: the Chinese stayed in the
     * line's text, drawn at the original's size inside its paragraph, and with no line carrying a
     * translation the translation switch went and fetched a second one from a catalogue. One
     * line, two translations, one of them twice the size it should be.
     *
     * Only a line that carries CJK is split: U+2009 is a typographic space too, and a Latin line
     * using one has no translation after it. The head stays the line's text - its word timing
     * clipped at the separator, since a translation is not sung a syllable at a time - and
     * everything after becomes its translation, on its own row when there was more than one.
     * Called on the lines of whichever route answered, before anything reads them, so that borrowing
     * sees the translations the lyric came with and the layout draws them under the line.
     */
    @JvmStatic
    fun splitInlineTranslations(lines: List<LyricLine>): List<LyricLine> {
        var changed = false
        val out = ArrayList<LyricLine>(lines.size)
        for (line in lines) {
            val split = splitInline(line)
            if (split == null) out.add(line) else {
                out.add(split)
                changed = true
            }
        }
        return if (changed) out else lines
    }

    /** The thin and narrow no-break spaces tag writers join a translation to its line with. */
    private const val THIN_SPACE = '\u2009'
    private const val NARROW_NO_BREAK_SPACE = '\u202f'

    /** One line with its same-line translation taken off it, or null when it has none. */
    private fun splitInline(line: LyricLine): LyricLine? {
        val text = line.text
        val at = text.indexOfFirst { it == THIN_SPACE || it == NARROW_NO_BREAK_SPACE }
        if (at <= 0 || at >= text.length - 1) return null
        if (!hasCjk(text)) return null
        // The space before the separator is not part of the line either.
        var cut = at
        while (cut > 0 && text[cut - 1].isWhitespace()) cut--
        if (cut == 0) return null
        val tail = text.substring(at + 1)
            .split(THIN_SPACE, NARROW_NO_BREAK_SPACE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tail.isEmpty()) return null
        val head = text.substring(0, cut)
        val rest = tail.joinToString("\n")
        val translation = line.translation?.let { it + "\n" + rest } ?: rest
        val starts = line.sylStart
        val ends = line.sylEnd
        val chars = line.charEnd
        if (starts == null || ends == null || chars == null) {
            val copy = LyricLine(head, translation, line.roma, line.start, line.end,
                line.opposite, null, null, null)
            copy.bg = line.bg
            return copy
        }
        // The syllables wholly inside the head stay; the one the separator falls in is cut
        // there, and the rest - the translation - goes with it. charEnd has to run no further
        // than the text's new length or the word path indexes past its end.
        val startsOut = ArrayList<Int>(starts.size)
        val endsOut = ArrayList<Int>(starts.size)
        val charsOut = ArrayList<Int>(starts.size)
        var from = 0
        for (k in starts.indices) {
            val to = chars[k]
            if (from >= cut) break
            val end = if (to > cut) cut else to
            if (end > from) {
                startsOut.add(starts[k])
                endsOut.add(ends[k])
                charsOut.add(end)
            }
            from = to
        }
        if (charsOut.isEmpty()) return null
        val copy = LyricLine(head, translation, line.roma, line.start, line.end,
            line.opposite, startsOut.toIntArray(), endsOut.toIntArray(), charsOut.toIntArray())
        copy.bg = line.bg
        return copy
    }

    /** Han, kana or hangul: the scripts a same-line translation is written in. */
    private fun hasCjk(s: String): Boolean = s.any {
        it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF
            || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF
    }

    /** A body made ready for the parser, and the translations taken off it by their line's start. */
    internal class Prepared(val body: String, val lanes: List<Pair<Int, String>>)

    /**
     * Word timings written in the line's own brackets - "[00:01.000]日[00:01.100]本[00:01.200]語
     * [00:01.500]", the form LDDC and the tools like it save - rewritten into Enhanced LRC's angle
     * brackets before the parser sees them. lyrics-core does not read that form (#64): it took the
     * first word for the line's time and lost it ("本語"), left a closing time on the screen
     * ("Goodbyes[01:15.342]") and on the end of a translation, and a duet's "女：" went with the
     * first word, so the two voices were never told apart.
     *
     * The same files give a translation a line of its own, timed one of two ways: at its line's
     * start, or with the same time twice - which no sung line has - just before the next line.
     * Either way it is taken off here and handed back to its line after parsing; left in, it was
     * a line of its own. A file with no word timings only loses the closing times.
     */
    internal fun bracketWords(body: String): Prepared {
        if (!body.contains('[')) return Prepared(body, emptyList())
        val rows = body.split('\n').flatMap { sungAgain(it) }
        val cut = rows.map { pieces(it) }
        val timed = cut.any { it != null && words(it) }
        val ahead = timed && translationsAhead(cut)
        val out = ArrayList<String>(rows.size)
        val lanes = ArrayList<Pair<Int, String>>()
        // The start of the word-timed line a translation after it would belong to.
        var owner: Int? = null
        for ((i, raw) in rows.withIndex()) {
            val p = cut[i]
            // Untouched: no time, one time, or times one after another before any text - the
            // same line sung more than once ("[00:12.00][01:30.00]...").
            if (p == null || p.size < 2) {
                if (p != null) owner = null
                out.add(raw)
                continue
            }
            val start = ms(p[0].first)
            if (start == null) {
                out.add(raw)
                continue
            }
            if (words(p)) {
                out.add(angle(p))
                owner = start
                continue
            }
            // "[t]text[t2]": a translation, or a line with its closing time.
            val text = p[0].second.trim()
            val end = ms(p.last().first) ?: start
            if (ahead) {
                // A file that writes each translation above its line: it is the next line's.
                val next = nextWords(cut, i)
                if (next != null && kotlin.math.abs(next - start) <= LANE_MS) {
                    lanes.add(Pair(next, text))
                    continue
                }
            }
            val own = owner
            val lane = !ahead && timed && own != null && (kotlin.math.abs(start - own) <= LANE_MS
                    || (end == start && start >= own && start <= nextStart(cut, i)))
            if (lane) {
                lanes.add(Pair(own!!, text))
                continue
            }
            if (timed && end > start) {
                out.add(angle(p))
                owner = start
            } else {
                out.add(p[0].first.value + text)
                owner = null
            }
        }
        return Prepared(originalsFirst(inOrder(out)).joinToString("\n"), lanes)
    }

    /**
     * Whether a word-timed file writes its translations above their lines rather than below:
     * its first translation - a row with no word timings, at the time of the line after it -
     * comes before its first sung line. Read the usual way, every translation went to the line
     * before its own and the first one was a line of its own (#64, 正文/翻译颠倒).
     */
    private fun translationsAhead(cut: List<List<Pair<MatchResult, String>>?>): Boolean {
        for ((i, p) in cut.withIndex()) {
            if (p == null || p.size < 2) continue
            if (words(p)) return false
            val start = ms(p[0].first) ?: continue
            val next = nextWords(cut, i) ?: return false
            return kotlin.math.abs(next - start) <= LANE_MS
        }
        return false
    }

    /** When the row after row i starts, if it is a word-timed one. */
    private fun nextWords(cut: List<List<Pair<MatchResult, String>>?>, i: Int): Int? {
        for (j in i + 1 until cut.size) {
            val p = cut[j] ?: continue
            return if (words(p)) ms(p[0].first) else null
        }
        return null
    }

    /**
     * Two rows at one time - a line and its translation, as LRC writes them - with the line first,
     * since the parser takes the first for the line and the second for its translation. Some
     * files write the translation first, and the song came out in Chinese with its own words
     * under it (#64). A row with word timings is the line wherever it is; between two without,
     * a Japanese or Korean one is the line and a Chinese one its translation, when the file puts
     * the Chinese first more often than not. Not English: a Chinese song translated into English
     * is written exactly like an English song with its translation first, and is left as it is.
     */
    private fun originalsFirst(rows: List<String>): List<String> {
        val at = rows.map { r -> LRC_TIME.find(r.trimStart())?.let { ms(it) } }
        val pairs = ArrayList<Int>()
        for (i in 0 until rows.size - 1) {
            if (at[i] != null && at[i] == at[i + 1]) pairs.add(i)
        }
        if (pairs.isEmpty()) return rows
        val out = rows.toMutableList()
        var behind = 0
        var ahead = 0
        for (i in pairs) {
            val a = script(rows[i])
            val b = script(rows[i + 1])
            if (a == HAN && b == KANA) ahead++
            if (a == KANA && b == HAN) behind++
        }
        val swapChinese = ahead > behind
        var i = 0
        while (i < rows.size - 1) {
            if (at[i] == null || at[i] != at[i + 1]) {
                i++
                continue
            }
            val wa = rows[i].contains('<')
            val wb = rows[i + 1].contains('<')
            val swap = if (wa != wb) wb
                else swapChinese && script(rows[i]) == HAN && script(rows[i + 1]) == KANA
            if (swap) {
                out[i] = rows[i + 1]
                out[i + 1] = rows[i]
            }
            i += 2
        }
        return out
    }

    private const val HAN = 1
    private const val KANA = 2

    /**
     * HAN for a row in Chinese, KANA for one with any kana or hangul in it - Japanese is Han and
     * kana both - and 0 for anything else.
     */
    private fun script(row: String): Int {
        val text = row.replace(LRC_TAG, "").replace(ANGLE_TAG, "")
        var h = 0
        var other = 0
        for (ch in text) {
            when {
                ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF -> return KANA
                ch.code in 0x4E00..0x9FFF -> h++
                ch.isLetter() -> other++
            }
        }
        return if (h > 0 && h > other) HAN else 0
    }

    private val ANGLE_TAG = Regex("<[^>]*>")

    /**
     * A line sung more than once, written once with all its times - "[00:12.00][01:30.00]副歌",
     * as hand-made LRC writes a chorus - as one row per time, which inOrder then puts in its
     * place. As written, the file read as no lyric at all. The same time twice is one row. A row with word timings
     * after its times is left as it is: those are the first time's, and no other time has any.
     */
    private fun sungAgain(raw: String): List<String> {
        val tags = LRC_TAG.findAll(raw).toList()
        if (tags.size < 2 || raw.substring(0, tags[0].range.first).isNotBlank()) return listOf(raw)
        var n = 1
        while (n < tags.size && raw.substring(tags[n - 1].range.last + 1, tags[n].range.first).isBlank()) n++
        if (n < 2 || n < tags.size) return listOf(raw)
        val text = raw.substring(tags[n - 1].range.last + 1)
        return tags.map { it.value }.distinct().map { it + text }
    }

    /**
     * The timed rows in time order, the rest (the tags, blank rows) ahead of them as they were.
     * lyrics-core throws on an LRC whose rows go back in time - its rearrangeUncheckedLineTime
     * requires the next line to start later - and a lyric it throws on is no lyric at all; a
     * chorus written once with all its times always does, and hand-edited files often. Stable,
     * so a translation stays after the line it shares a start with; run once bracketWords has
     * taken off the ones timed a millisecond early, which would have gone ahead of their line.
     * Untouched when in order.
     */
    private fun inOrder(rows: List<String>): List<String> {
        val at = rows.map { r -> LRC_TIME.find(r.trimStart())?.let { ms(it) } }
        var last = Int.MIN_VALUE
        var sorted = true
        for (t in at) {
            if (t == null) continue
            if (t < last) sorted = false
            last = t
        }
        if (sorted) return rows
        val timed = rows.indices.filter { at[it] != null }.sortedBy { at[it] }
        return rows.indices.filter { at[it] == null }.map { rows[it] } + timed.map { rows[it] }
    }

    /**
     * How far a translation's time may be from its line's and still be at its start: LDDC writes
     * them a millisecond apart as often as at the same time.
     */
    private const val LANE_MS = 20

    /**
     * A row's times and the text after each - "[t0]a[t1]b[t2]" is (t0, "a"), (t1, "b"), (t2, "").
     * Null for a row that does not open with a time, or opens with times one after another.
     */
    private fun pieces(raw: String): List<Pair<MatchResult, String>>? {
        val tags = LRC_TAG.findAll(raw).toList()
        if (tags.isEmpty() || raw.substring(0, tags[0].range.first).isNotBlank()) return null
        val p = tags.mapIndexed { i, m ->
            val end = if (i + 1 < tags.size) tags[i + 1].range.first else raw.length
            Pair(m, raw.substring(m.range.last + 1, end))
        }
        return if (p[0].second.isBlank()) null else p
    }

    /** Words after the first carry times of their own. */
    private fun words(p: List<Pair<MatchResult, String>>): Boolean =
        p.size > 1 && p.drop(1).any { it.second.isNotBlank() }

    /** The row again, every time after the first in angle brackets. */
    private fun angle(p: List<Pair<MatchResult, String>>): String {
        val sb = StringBuilder(p[0].first.value)
        for ((tag, text) in p) {
            sb.append('<').append(tag.value, 1, tag.value.length - 1).append('>').append(text)
        }
        return sb.toString()
    }

    /** When the next lyric row after row i starts; a translation before it can still be the last line's. */
    private fun nextStart(cut: List<List<Pair<MatchResult, String>>?>, i: Int): Int {
        for (j in i + 1 until cut.size) {
            val p = cut[j] ?: continue
            return ms(p[0].first) ?: continue
        }
        return Int.MAX_VALUE
    }

    /** The translations bracketWords took off, back on the lines that start where they were. */
    private fun withLanes(lines: List<LyricLine>, lanes: List<Pair<Int, String>>): List<LyricLine> {
        if (lanes.isEmpty()) return lines
        val by = LinkedHashMap<Int, String>()
        for ((at, text) in lanes) {
            if (text.isEmpty()) continue
            by[at] = by[at]?.let { it + "\n" + text } ?: text
        }
        return lines.map { line ->
            val t = by.remove(line.start) ?: return@map line
            withTranslation(line, line.translation?.let { it + "\n" + t } ?: t)
        }
    }

    /**
     * The first of the sorted starts that is later than t: when the line starting at t is followed
     * by another. The last line of a song has nothing after it, and gets the fallback.
     */
    internal fun nextAfter(sorted: IntArray, t: Int, fallback: Int): Int {
        val i = sorted.binarySearch(t + 1)
        val at = if (i >= 0) i else -i - 1
        return if (at < sorted.size) sorted[at] else fallback
    }

    private fun karaoke(line: KaraokeLine, nextStart: Int): LyricLine? {
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
        closeUntimedTail(starts, ends, line.end, nextStart)
        // The file's own romanisation - TTML's x-roman, a KRC's language block - kept the same
        // way a separately shipped one is.
        val shown = text.substring(0, n)
        return LyricLine(shown, line.translation, romaOf(line.phonetic, shown), line.start,
            line.end, line.alignment == KaraokeAlignment.End, starts, ends, chars)
    }

    /**
     * A background vocal without the one pair of brackets wrapping all of it.
     *
     * The line is already drawn smaller and fainter under the one it echoes, which is what the
     * brackets were saying; files and bridges disagree on whether to send them - TTML's x-bg
     * usually does, inside its first and last words, and Flamingo's published text does while its
     * words do not (issue #102) - so they are taken off everywhere and every source reads the
     * same. Only a pair that opens the line and closes it: "(oh) yeah (oh)" keeps both of its
     * own, and so does an unpaired one. ASCII and full-width alike.
     *
     * The syllables keep their timing and lose the characters taken off; a syllable that was
     * nothing but a bracket goes with it.
     */
    internal fun unwrapBackground(line: LyricLine): LyricLine {
        val text = line.text
        var from = 0
        var until = text.length
        while (from < until && text[from].isWhitespace()) from++
        while (until > from && text[until - 1].isWhitespace()) until--
        if (until - from < 2) return line
        val open = text[from]
        val close = when (open) {
            '(' -> ')'
            '（' -> '）'
            else -> return line
        }
        if (text[until - 1] != close) return line
        // The opening bracket has to be the one the last character closes, not one closed earlier.
        var depth = 0
        for (k in from until until - 1) {
            if (text[k] == open) depth++ else if (text[k] == close) depth--
            if (depth == 0) return line
        }
        from++
        until--
        while (from < until && text[from].isWhitespace()) from++
        while (until > from && text[until - 1].isWhitespace()) until--
        if (until == from) return line
        val shown = text.substring(from, until)
        val chars = line.charEnd
        if (chars == null) {
            return LyricLine(shown, line.translation, line.roma, line.start, line.end,
                line.opposite, null, null, null)
        }
        val starts = ArrayList<Int>(chars.size)
        val ends = ArrayList<Int>(chars.size)
        val cut = ArrayList<Int>(chars.size)
        var prev = 0
        for (k in chars.indices) {
            val c = (chars[k] - from).coerceIn(0, shown.length)
            if (c <= prev) continue
            starts.add(line.sylStart[k])
            ends.add(line.sylEnd[k])
            cut.add(c)
            prev = c
        }
        if (cut.isEmpty()) {
            return LyricLine(shown, line.translation, line.roma, line.start, line.end,
                line.opposite, null, null, null)
        }
        // Whatever was left past the last syllable before is drawn by it now.
        cut[cut.size - 1] = shown.length
        return LyricLine(shown, line.translation, line.roma, line.start, line.end, line.opposite,
            starts.toIntArray(), ends.toIntArray(), cut.toIntArray())
    }

    /**
     * The words at the end of a line that the source left without a span of their own take the
     * room it was leaving them.
     *
     * Word timing routinely runs out before the line does. A file that times a line by its words
     * alone has nowhere to read the last word's end from, and one that carries a duration per
     * word has nothing to say for a note the singer holds on: either way the last word arrives
     * with its end missing, or set equal to its own start. That is invisible anywhere else in a
     * line - sungChars() walks the syllables looking for the one the moment falls in, and a word
     * nobody is inside is simply stepped over - but at the end of a line there is no next word to
     * hand the fill on to, so the walk runs off the end of the array and reports the whole line
     * sung. The fill then crosses the last word in a single frame instead of sweeping it, and the
     * word is never the second long that the glow asks for.
     *
     * A word left without a span runs to the end of the line, and to the arrival of the line after
     * it when the file has no end to offer - which is the case for every file that times a line by
     * its words and nothing else. Not every such wait is a note, though: a singer will hold a word
     * through the last bar of a chorus but nobody holds one across an interlude, and the renderer
     * answers the second kind with its interlude dots (`LyricView.LULL_MS`, the same four seconds),
     * so past that the word keeps the nominal span and leaves the rest of the wait to them. Words
     * that share a start - which is what a wholly untimed tail looks like - divide the stretch
     * between them, rather than crossing together, so the fill still moves through them one at a
     * time.
     */
    internal fun closeUntimedTail(starts: IntArray, ends: IntArray, lineEnd: Int, nextStart: Int) {
        var k = 0
        while (k < starts.size) {
            if (ends[k] > starts[k]) {
                k++
                continue
            }
            // The run of untimed words this one belongs to.
            var last = k
            while (last + 1 < starts.size && ends[last + 1] <= starts[last + 1]) last++
            val from = starts[k]
            // A later word that starts later is where the run has to be over by. At the tail
            // there is none of those, and the line's own end closes it.
            var to = lineEnd
            for (m in last + 1 until starts.size) {
                if (starts[m] > from) {
                    to = starts[m]
                    break
                }
            }
            // A line whose own end is no later than its last word - which is what a file that
            // times lines by their words alone reports - leaves nothing to go on but the next
            // line's arrival.
            // The dots are drawn when what is left after this line's end reaches the lull, and
            // that end is the nominal one when the word does not run on - so it is the wait
            // after the nominal span that decides, or a gap just over four seconds would get
            // neither the held word nor the dots.
            if (to <= from) {
                val nominal = from + NOMINAL_WORD_MS * (last - k + 1)
                to = if (nextStart > from && nextStart - nominal < MAX_HELD_MS) nextStart
                else nominal
            }
            val count = last - k + 1
            for (m in k..last) ends[m] = from + (to - from) * (m - k + 1) / count
            k = last + 1
        }
    }

    /**
     * What a word is given when even the line it sits in has no end left to reach. Only a file
     * that times its lines by words alone gets here; about a word's worth of room, so it sweeps
     * rather than snaps.
     */
    private const val NOMINAL_WORD_MS = 300

    /**
     * How long a wait may be left after a last word's nominal span before it stops being a note
     * held across it and becomes an interlude, which the renderer is about to draw its dots
     * through anyway (the same four seconds; see LyricView.LULL_MS).
     */
    private const val MAX_HELD_MS = 4000

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
            LyricLine(text, line.translation, line.roma, line.start, line.end, opposite,
                null, null, null)
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
            LyricLine(text, line.translation, line.roma, line.start, line.end, opposite,
                starts.toIntArray(), ends.toIntArray(), chars.toIntArray())
        }
        result.bg = line.bg
        return result
    }
}
