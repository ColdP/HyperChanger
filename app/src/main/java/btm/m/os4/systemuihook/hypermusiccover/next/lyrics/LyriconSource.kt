// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.Context
import android.content.ContextWrapper
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo

/**
 * The player's own lyrics, as published by a Lyricon provider - the source that needs no network,
 * no matching and no luck.
 *
 * The two routes this module has had until now both start from the song's name or its id and go
 * looking for a lyric somewhere else, which is why every one of them can fail on a song that
 * plainly has lyrics: the AMLL database is 69,000 songs against everyone's millions, and NetEase
 * has to be asked by name - where the two catalogues disagree on the script of the title, on the
 * length of the master, or on whether the song is there at all.
 *
 * None of that applies to this one. Apple Music fetches syllable-timed TTML for the track it is
 * playing and hands it to its own lyrics view model; a Lyricon provider sits on that and publishes
 * the result. What arrives here is the whole song at once - every line's text, word timings,
 * translation, backing vocal and which side of a duet it is on - for the track the player says it
 * is playing. It is not a guess about a song with the same name; it is that song.
 *
 * It is also entirely optional, and has to behave that way: a device with no Lyricon core service
 * installed gets no callbacks and no error, the subscriber is created once and never touched
 * again, and every entry point here is wrapped, because this runs inside SystemUI.
 */
object LyriconSource {

    /** Null until attach() has run, and stays null if there is no Lyricon to attach to. */
    @Volatile
    private var sSubscriber: LyriconSubscriber? = null

    /** The last song published, or null when the provider has cleared. Read from any thread. */
    @Volatile
    private var sSong: Song? = null

    /** The last connection state, for the probe. Words rather than the enum, which is internal. */
    @Volatile
    private var sState = "not started"

    /** How many songs have arrived, so a probe can tell "connected" from "connected and working". */
    @Volatile
    private var sSongs = 0

    /** The active provider's name, which is which plugin is doing the reading. */
    @Volatile
    private var sProvider: String? = null

    /** The player the active provider reads from - whether a new song is on its way. */
    @Volatile
    private var sPlayer: String? = null

    /**
     * Songs the active provider has published since it took over sPlayer - whether it can be
     * waited for at all. A provider that is registered but broken is still the active one: the
     * Apple plugin on AM 6.5.2 announces itself for com.apple.android.music and never publishes a
     * song, and waiting CATCH_UP_MS for it held every Apple track change's lyrics back by two
     * seconds before the file or the catalogues were even asked (user, 2026-09-30).
     */
    @Volatile
    private var sPlayerSongs = 0

    /** Notified on every song the bridge publishes, for a lookup waiting out a stale one. */
    private val sSongLock = Object()

    /**
     * How long a lookup waits for the bridge to catch up with a track change.
     *
     * Only ever spent when the active provider is reading this very player, i.e. when the new
     * song is known to be coming; the provider has to fetch the lyric before it publishes, so
     * this is a fetch's worth of time rather than a callback's.
     */
    private const val CATCH_UP_MS = 2000L

    /** The last thing that went wrong, if anything did. Never thrown onward. */
    @Volatile
    private var sError: String? = null

    private var sAttached = false

    /** The package this subscriber registers as - see attach. Our applicationId, so it is unique. */
    private const val SUBSCRIBER_NAME = "com.github.zyl6932.HyperMusicCover"

    /**
     * Whether there is a Lyricon service on this phone at all - what "Lyricon is installed" means
     * for the settings page's lyric-source row.
     *
     * Not a package to look up: what a subscriber talks to is the Lyricon central, which may be
     * an app of its own or built into another module's, so the only thing that can answer the
     * question is whether one replied. "no service" is the connect timeout and is the one state
     * that means no; "failed" is our own subscriber throwing, which says nothing about the phone
     * and is left as a no for that reason.
     */
    @JvmStatic
    fun installed(): Boolean = when (sState) {
        "connected", "reconnected", "disconnected" -> true
        else -> false
    }

    /**
     * Start listening, once, from wherever the process first has a Context.
     *
     * Called from the same place and for the same reason as the module's other one-shot
     * registrations: nothing on the SystemUI side has a Context until the keyguard's clock
     * container attaches, and a subscriber needs one. A second call does nothing.
     *
     * Nothing here is allowed to throw. This is the only code in the module that talks to a
     * service which may not be installed, and a module that cannot be loaded because of a missing
     * optional dependency is worse than a module without lyrics.
     */
    @JvmStatic
    fun attach(ctx: Context) {
        synchronized(this) {
            if (sAttached) {
                return
            }
            sAttached = true
        }
        try {
            // Under a name of our own. The central keys a subscriber by (package, process) and a
            // second one under a key it already has is handed the FIRST one's connection, which
            // holds a single listener - so ours, registered from SystemUI as
            // com.android.systemui, took over the listener of whichever display module had
            // subscribed from SystemUI before us. HyperLyrics Enhanced does, with its central
            // built in, and its island lost every lyric from 0.3 on. The library takes the
            // package from the Context and nothing else from it: the register broadcast is sent
            // to com.android.systemui by name.
            val subscriber = LyriconFactory.createSubscriber(object : ContextWrapper(ctx) {
                override fun getPackageName(): String = SUBSCRIBER_NAME
            })
            subscriber.addConnectionListener(object : ConnectionListener {
                override fun onConnected(subscriber: LyriconSubscriber) {
                    sState = "connected"
                }

                override fun onReconnected(subscriber: LyriconSubscriber) {
                    sState = "reconnected"
                }

                override fun onDisconnected(subscriber: LyriconSubscriber) {
                    sState = "disconnected"
                }

                override fun onConnectTimeout(subscriber: LyriconSubscriber) {
                    sState = "no service"
                }
            })
            subscriber.subscribeActivePlayer(Listener)
            sSubscriber = subscriber
            subscriber.register()
            Xp.log("[MCLyricon] subscribed: " + describe())
        } catch (t: Throwable) {
            sState = "failed"
            sError = t.toString()
            Xp.log("[MCLyricon] could not subscribe: " + t)
        }
    }

    /**
     * Retirement: everything the bridge holds of ours is released.
     *
     * <p>The listener below and the connection listener in attach() are objects of the class
     * loader that is going away, and the central keys a subscriber by (package, process): a live
     * one left behind would be handed to the next generation instead of the one it creates (see
     * the identity collision this module already hit). destroy() releases the connection; the
     * active-player listener is taken off first, so no callback can arrive mid-teardown.
     *
     * <p>Safe to call when nothing was ever attached, and safe to call twice.
     */
    @JvmStatic
    fun detach() {
        val sub = synchronized(this) {
            if (!sAttached) return
            sAttached = false
            val s = sSubscriber
            sSubscriber = null
            s
        }
        try {
            sub?.unsubscribeActivePlayer(Listener)
        } catch (t: Throwable) {
            Xp.log("[MCLyricon] unsubscribe failed: " + t)
        }
        try {
            sub?.destroy()
        } catch (t: Throwable) {
            Xp.log("[MCLyricon] destroy failed: " + t)
        }
        synchronized(sSongLock) {
            sSong = null
            sSongLock.notifyAll()
        }
        sState = "not started"
        sPlayer = null
        sProvider = null
        sPlayerSongs = 0
        Xp.log("[MCLyricon] detached")
    }

    /**
     * What the bridge is handed. Only the song is kept: the position comes from the MediaSession
     * this module already follows, the plain-text callback has no timing in it, and the two
     * display toggles are the player's own UI settings rather than anything about the lyric.
     *
     * Every method is wrapped, because these arrive on whichever thread the bridge likes and an
     * exception here is an exception in SystemUI.
     */
    private object Listener : ActivePlayerListener {
        override fun onSongChanged(song: Song?) {
            try {
                val changed = synchronized(sSongLock) {
                    val changed = song != sSong
                    sSong = song
                    if (song != null) {
                        sSongs++
                        sPlayerSongs++
                    }
                    sSongLock.notifyAll()
                    changed
                }
                if (changed && !song?.lyrics.isNullOrEmpty()) LockLyrics.onProviderLyrics()
            } catch (t: Throwable) {
                sError = t.toString()
            }
        }

        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            try {
                sProvider = providerInfo?.let {
                    it.providerPackageName + " -> " + it.playerPackageName
                }
                val player = providerInfo?.playerPackageName
                if (player != sPlayer) sPlayerSongs = 0
                sPlayer = player
            } catch (t: Throwable) {
                sError = t.toString()
            }
        }

        override fun onReceiveText(text: String?) = Unit

        override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit

        override fun onPositionChanged(position: Long) = Unit

        override fun onSeekTo(position: Long) = Unit

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    /**
     * The song the bridge is holding, as rows this renderer can draw - or null.
     *
     * Null for every reason there is: nothing connected, nothing published, a song with no
     * lyric, or a song that is not the one being asked about. That last one is the reason this
     * takes a title at all. The bridge publishes whatever the active player is playing, on its
     * own schedule, and a lookup here happens during a track change - so the song in hand is the
     * previous one as often as not, and taking it would put the last song's words on this
     * song's screen for as long as it played. Compared the way the session's own payload is
     * compared, by containment either way, because a title carries decorations the bridge's copy
     * need not repeat.
     *
     * The title has to agree; the artist only must not disagree. Either one used to be enough,
     * and in a playlist of one singer the artist always agrees - so "轨迹" took the "周大侠"
     * the bridge was still holding, and the per-track cache kept it for every later play.
     *
     * When the song in hand is not this one but the active provider is reading this player, the
     * right one is on its way, so this waits for it - briefly, on the lookup's worker - rather
     * than handing the track to the catalogues, which is where the bridge's copy would be lost.
     */
    @JvmStatic
    fun linesFor(title: String?, artist: String?, player: String?): List<LyricLine>? {
        var song = sSong
        if (song == null || !isTrack(song, title, artist)) {
            // Only a provider that has shown it publishes; see sPlayerSongs.
            if (player != null && player == sPlayer && sPlayerSongs > 0) {
                val until = android.os.SystemClock.uptimeMillis() + CATCH_UP_MS
                synchronized(sSongLock) {
                    while (true) {
                        song = sSong
                        if (song != null && isTrack(song!!, title, artist)) break
                        val left = until - android.os.SystemClock.uptimeMillis()
                        if (left <= 0) break
                        sSongLock.wait(left)
                    }
                }
            }
            val held = song
            if (held == null || !isTrack(held, title, artist)) {
                if (held != null) {
                    Xp.log("[MCLyricon] holding \"" + held.name + "\" while the track is \""
                            + title + "\"; not reading it")
                }
                return null
            }
        }
        val rich = song!!.lyrics
        if (rich.isNullOrEmpty()) return null
        val out = ArrayList<LyricLine>(rich.size)
        // Where the tail of a line may run to when the bridge gave its last word no end of its
        // own: the arrival of the line after it, the same room every other word-timed source
        // leaves - see LyricParse.closeUntimedTail. By time, since the bridge's order is not
        // trusted either (the rows are sorted below).
        val begins = rich.map { it.begin.toInt() }.sorted().toIntArray()
        for (line in rich) {
            val nextStart = LyricParse.nextAfter(begins, line.begin.toInt(), line.end.toInt())
            convert(line, nextStart)?.let { out.add(it) }
        }
        if (out.isEmpty()) return null
        out.sortBy { it.start }
        return out
    }

    /**
     * Whether the bridge's song is this track: a title that agrees, and an artist that does not
     * disagree - or, on the players that sing into TITLE, the song and singer ARTIST spells
     * (TrackName.splits). Held against the title alone, 汽水's song was refused from its first
     * sung line on, and the lookup went to the catalogues with the line as the song's name (#56).
     */
    private fun isTrack(song: Song, title: String?, artist: String?): Boolean {
        if (song.name.isNullOrBlank()) return false
        if (!title.isNullOrBlank() && names(song.name, title) && names(song.artist, artist)) return true
        return TrackName.pairIn(song.name, song.artist, artist)
    }

    /** Whether two names agree, or say nothing. A blank on either side is not a contradiction. */
    private fun names(theirs: String?, ours: String?): Boolean {
        val a = theirs?.trim()?.lowercase().orEmpty()
        val b = ours?.trim()?.lowercase().orEmpty()
        if (a.isEmpty() || b.isEmpty()) return true
        return a.contains(b) || b.contains(a)
    }

    /**
     * One bridge line, with its word timings and whatever hangs off it.
     *
     * The three syllable arrays have to satisfy LyricLine's contract exactly - same length,
     * charEnd running up to the text's length, and every end after its own start - so they are
     * built from the words rather than from the line's own text: a bridge line whose words do
     * not add up to its text would otherwise produce arrays that index past the end of it.
     * A line whose words are missing or unusable stays line-timed, which draws correctly and
     * simply does not fill word by word.
     */
    private fun convert(line: RichLyricLine, nextStart: Int): LyricLine? {
        val text = line.text?.trim().orEmpty()
        val words = line.words
        var built: LyricLine? = null
        if (!words.isNullOrEmpty()) {
            val sb = StringBuilder()
            val starts = ArrayList<Int>(words.size)
            val ends = ArrayList<Int>(words.size)
            val chars = ArrayList<Int>(words.size)
            for (w in words) {
                val t = w.text ?: continue
                if (t.isEmpty()) continue
                sb.append(t)
                starts.add(w.begin.toInt())
                // A word with no end of its own is left to the tail-closing pass below, which is
                // the same thing every other word-timed source here needs.
                ends.add(if (w.end > w.begin) w.end.toInt() else w.begin.toInt())
                chars.add(sb.length)
            }
            var n = sb.length
            while (n > 0 && sb[n - 1].isWhitespace()) n--
            if (n > 0 && starts.isNotEmpty()) {
                for (k in chars.indices) if (chars[k] > n) chars[k] = n
                val s = starts.toIntArray()
                val e = ends.toIntArray()
                LyricParse.closeUntimedTail(s, e, line.end.toInt(), nextStart)
                built = LyricLine(sb.substring(0, n), line.translation,
                    LyricParse.romaOf(line.roma, sb.substring(0, n)), line.begin.toInt(),
                    line.end.toInt(), line.isAlignedRight, s, e, chars.toIntArray())
            }
        }
        if (built == null) {
            if (text.isEmpty()) return null
            built = LyricLine(text, line.translation, LyricParse.romaOf(line.roma, text),
                line.begin.toInt(), line.end.toInt(), line.isAlignedRight, null, null, null)
        }
        // The background vocal, which the renderer hangs under the line rather than beside it.
        // It stretches the line if it outlasts it, as it does from a file (LyricParse.parse).
        val bg = background(line, nextStart)
        if (bg != null) {
            built.bg = bg
            if (bg.end > built.end) built.end = bg.end
        }
        return built
    }

    /**
     * The backing vocal as a line of its own, with the bridge's own word timings when it
     * published them, or null when the bridge sent neither text nor words for it.
     *
     * Its text is the published secondary string where the words can be found in it:
     * `LyricWord.text` does not always carry the spaces between words, and a joined copy would
     * read "Fifty,fiftyfeetupunderground" (issue #29). Each word is found in that text in order
     * and its syllable covers where it was found; brackets left after the last word are its own,
     * since Flamingo publishes "(oh yeah)" over words that carry none (issue #102).
     *
     * Where the words cannot be found in it - Flamingo can also publish "(和声)和声", the bracketed
     * copy run into the plain one, over a single copy's words - the words are joined instead:
     * the timing the bridge has is worth more than the spaces, which only an English line loses.
     * Words alone, with no text published, are joined the same way. A line without usable words
     * stays line-timed, which draws correctly and simply does not fill word by word.
     *
     * This is also what keeps a backing vocal without any word timing off the word path: the
     * renderer used to hand every backing vocal to it whenever the lead line was word-timed,
     * and read the missing syllable array as a null (issue #94, SystemUI down).
     *
     * The brackets wrapping the whole line come off at the end, as from a file
     * (LyricParse.unwrapBackground).
     *
     * Its end is its last word's when that comes after the lead's: a backing vocal that runs on
     * keeps the line lit until it is sung (PR #103). Word times that do not fit an Int, or that
     * go backwards, are not word times the renderer can follow: the line stays line-timed.
     */
    internal fun background(line: RichLyricLine, nextStart: Int): LyricLine? {
        val published = line.secondary?.trim().orEmpty()
        val words = line.secondaryWords.orEmpty().filter { !it.text.isNullOrBlank() }
        var built: LyricLine? = null
        if (words.isNotEmpty() && timesFit(words)) {
            var text = published
            var chars = if (published.isEmpty()) null else placed(published, words)
            if (chars == null) {
                val sb = StringBuilder()
                val cut = IntArray(words.size)
                for ((k, w) in words.withIndex()) {
                    sb.append(w.text)
                    cut[k] = sb.length
                }
                var n = sb.length
                while (n > 0 && sb[n - 1].isWhitespace()) n--
                for (k in cut.indices) if (cut[k] > n) cut[k] = n
                text = sb.substring(0, n)
                chars = cut
            }
            if (text.isNotEmpty()) {
                val s = IntArray(words.size) { words[it].begin.toInt() }
                // A word with no end of its own is left to the tail-closing pass, as on the lead.
                val e = IntArray(words.size) {
                    val w = words[it]
                    if (w.end > w.begin) w.end.toInt() else w.begin.toInt()
                }
                LyricParse.closeUntimedTail(s, e, line.end.toInt(), nextStart)
                built = LyricLine(text, null, s[0], maxOf(line.end.toInt(), e.max()),
                    line.isAlignedRight, s, e, chars)
            }
        }
        if (built == null) {
            // Words whose times could not be used still say what is sung.
            val shown = published.ifEmpty { words.joinToString("") { it.text.orEmpty() }.trim() }
            if (shown.isEmpty()) return null
            built = LyricLine(shown, null, line.begin.toInt(), line.end.toInt(),
                line.isAlignedRight, null, null, null)
        }
        return LyricParse.unwrapBackground(built)
    }

    /** Every time inside an Int, and the words in the order they are sung. */
    private fun timesFit(words: List<LyricWord>): Boolean {
        val fits = 0L..Int.MAX_VALUE.toLong()
        var last = 0L
        for (w in words) {
            if (w.begin !in fits || w.end !in fits || w.begin < last) return false
            last = w.begin
        }
        return true
    }

    /**
     * Where each word ends in text, found in order, or null when one is not there or text goes on
     * past the last one with anything but spaces and closing brackets - characters the word path
     * would draw with no syllable of their own.
     */
    private fun placed(text: String, words: List<LyricWord>): IntArray? {
        val chars = IntArray(words.size)
        var at = 0
        for ((k, w) in words.withIndex()) {
            // Trimmed for the search only: the published line is trimmed too, so a word carrying
            // a trailing space would otherwise not be found in it. The space then sits at the
            // start of the next syllable's range and is still drawn.
            val t = w.text.orEmpty().trim()
            val i = text.indexOf(t, at)
            if (i < 0) return null
            at = i + t.length
            chars[k] = at
        }
        for (k in at until text.length) {
            val c = text[k]
            if (!c.isWhitespace() && c != ')' && c != '）') return null
        }
        chars[chars.size - 1] = text.length
        return chars
    }

    /** Diagnostics: what the bridge is doing, for the probe. Never throws. */
    @JvmStatic
    fun describe(): String {
        val sb = StringBuilder("state=").append(sState)
        sb.append(" songs=").append(sSongs).append(" playerSongs=").append(sPlayerSongs)
        sProvider?.let { sb.append(" provider=").append(it) }
        if (sSubscriber == null) {
            sb.append(" (no subscriber)")
        }
        val song = sSong
        if (song == null) {
            sb.append(" song=none")
        } else {
            val lines = song.lyrics?.size ?: 0
            val words = song.lyrics?.count { it.words?.isNotEmpty() == true } ?: 0
            sb.append(" song=").append(song.name).append(" / ").append(song.artist)
                .append(" / ").append(song.duration).append("ms")
                .append(" lines=").append(lines).append(" wordLines=").append(words)
        }
        sError?.let { sb.append(" lastError=").append(it) }
        return sb.toString()
    }
}
