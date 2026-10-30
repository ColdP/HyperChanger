// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.HotReload;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.media.MediaMetadata;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Apple Music's own lyrics, taken from inside Apple Music.
 *
 * This is the one player whose words we could never get. It publishes no lyricInfo and no lyric
 * text of any kind - only a numeric store id - so the lock screen has had to ask the AMLL
 * community database for it, and when that misses (which for anything but a well-known track it
 * does) fall through to searching NetEase by name. What Apple has is better than either: word
 * timings for every syllable, the translation, the background vocals, and which voice sings
 * which line.
 *
 * None of it leaves the app on its own. The lyric is built inside the player, for the lyric
 * page, and is never written to the session - so the only way to it is from inside the process,
 * which is why this file exists and why the module's scope now includes com.apple.android.music.
 *
 * The approach - hook the build, and trigger it by handing the player a Song that claims to have
 * lyrics - is Proify and Tomakino's, from LyricProvider's apple-music module (Apache-2.0,
 * github.com/tomakino/LyricProvider). Their reading of Apple's object graph is what this
 * reimplements; the code is ours, because their module is a Lyricon provider built on YukiHookAPI
 * and this one has to be a libxposed hook that answers to our own lock screen.
 *
 * Nothing here talks to SystemUI directly. The lyric goes through Apple Music's own
 * MediaSession, and the lock screen reads it through the route it has read every other player's
 * lyric through, word timings and all - see {@link LyricSource}.
 *
 * Not in the metadata itself, though, the way the provider modules put theirs. The payload runs
 * to 50k characters, and the metadata goes to every controller on the session each time the
 * player sets it - a dozen in SystemUI alone. Skipping through tracks, that filled SystemUI's
 * binder buffer, and the system server drops a controller callback for good the first time a call
 * to it fails: the lock screen's mini player went on showing a song long gone (2026-10-14). The
 * metadata carries a reference to the lyric instead (LyricSource.KEY_LYRIC_REF), and the lock
 * screen asks for the lyric itself with a session command, which only it receives.
 */
public final class AppleLyrics {

    private AppleLyrics() {
    }

    private static final String TAG = "[MCApple] ";

    /**
     * Bumped by hand whenever this file changes in a way a test needs to tell apart.
     *
     * The player is not restarted by installing the module, so a run can silently be the
     * previous build - and a change that only shows up as a line NOT being logged looks
     * identical to not having loaded at all. It cost a round trip once.
     */
    private static final String BUILD = "hr-1";

    /** The player's package, and the only process this class ever runs in. */
    public static final String PKG = "com.apple.android.music";

    public static void handle(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        // Independently, the way every other hook in this module is armed: the lyric build is
        // Apple's own class and can be renamed by an update, while setMetadata is a framework
        // method that cannot. One failing must not take the other down - without setMetadata
        // there is nowhere to publish, and without the build hook there is nothing to publish,
        // but each is worth having while the other is being fixed.
        try {
            hookSetMetadata();
        } catch (Throwable t) {
            note("setMetadata hook failed: " + t);
        }
        try {
            hookCommand();
        } catch (Throwable t) {
            note("command hook failed: " + t);
        }
        try {
            hookLyricBuild(cl);
        } catch (Throwable t) {
            note("lyric build hook failed: " + t);
        }
        sRequester = new Requester(cl);
        note("hooks armed, build " + BUILD);
    }

    // ---------------------------------------------------------------- what we are holding

    /** The session Apple Music is publishing through, so a late lyric can be pushed into it. */
    private static volatile Object sSession;
    /** The store id of whatever was last set on that session, and its title. */
    private static volatile String sPlayingId;
    private static volatile String sPlayingTitle;
    /** The id the last built lyric belongs to, and the payload built from it. */
    private static volatile String sHeldId;
    private static volatile String sHeldPayload;
    /** Set while we are re-publishing, so our own setMetadata does not re-enter the hook. */
    private static volatile boolean sPublishing;
    private static volatile Requester sRequester;

    // ---------------------------------------------------------------- hot reload (Step 0)

    /*
     * The new generation starts with every static empty, so the lyric this process is already
     * holding has to be handed over explicitly: the metadata already on the session refers to
     * it, and the lock screen will ask for exactly that reference. Without it the lyric page
     * stays empty until the next track, which is indistinguishable from a broken adoption.
     */

    public static String heldId() {
        return sHeldId;
    }

    public static String heldPayload() {
        return sHeldPayload;
    }

    public static String playingId() {
        return sPlayingId;
    }

    public static String playingTitle() {
        return sPlayingTitle;
    }

    public static void adoptState(String heldId, String heldPayload,
                                  String playingId, String playingTitle) {
        sHeldId = heldId;
        sHeldPayload = heldPayload;
        sPlayingId = playingId;
        sPlayingTitle = playingTitle;
        if (heldId != null) {
            note("adopted held lyric for " + heldId
                    + (heldPayload == null ? " (no payload)" : " (" + heldPayload.length() + " chars)"));
        }
    }

    // ---------------------------------------------------------------- hook 1: the session

    /**
     * Every metadata the player publishes, on its way out.
     *
     * Two jobs. It is where the lyric gets attached - the payload for the track being set is
     * written into the metadata before the framework ever sees it, so the session carries it
     * from the first moment SystemUI can read it. And it is what tells us the track changed, so
     * the download for the next one can be asked for.
     *
     * The framework's own method, not Apple's, so this survives the player's updates.
     */
    private static void hookSetMetadata() throws Throwable {
        Method m = android.media.session.MediaSession.class
                .getDeclaredMethod("setMetadata", MediaMetadata.class);
        Xp.hook("apple.lyrics.set_metadata", m, (XposedInterface.Hooker) chain -> {
            try {
                if (!sPublishing) {
                    onMetadata(chain.getThisObject(), chain.getArgs());
                }
            } catch (Throwable t) {
                note("onMetadata failed: " + t);
            }
            return chain.proceed();
        });
    }

    private static void onMetadata(Object session, List<Object> args) {
        if (session != null) {
            sSession = session;
        }
        Object a0 = args.isEmpty() ? null : args.get(0);
        if (!(a0 instanceof MediaMetadata)) {
            return;
        }
        MediaMetadata md = (MediaMetadata) a0;
        String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id == null || id.isEmpty()) {
            return;
        }
        sPlayingId = id;
        sPlayingTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (id.equals(sHeldId) && sHeldPayload != null) {
            // Already built, from a previous play of this track. Attach it on the way past,
            // which is cheaper and earlier than publishing it again afterwards.
            inject(md, id, sHeldPayload);
            return;
        }
        // Not built yet. Apple only builds a lyric when its own lyric page asks for one, so
        // left alone this would never fire for a track the person does not open that page for -
        // which is every track, when the phone is locked.
        Requester r = sRequester;
        if (r != null) {
            r.request(id);
        }
    }

    // ---------------------------------------------------------------- hook 2: the lyric

    /**
     * The moment the player has a parsed lyric in hand.
     *
     * buildTimeRangeToLyricsMap is called with a holder the built song sits inside; the song is
     * the root of the whole object graph read below. Hooked after, because before it the holder
     * is what was asked for rather than what came back.
     */
    private static void hookLyricBuild(ClassLoader cl) throws Throwable {
        Class<?> vm = cl.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
        List<XposedInterface.HookHandle> handles = Xp.hookAll("apple.lyrics.build",
                vm, "buildTimeRangeToLyricsMap",
                (XposedInterface.Hooker) chain -> {
                    Object result = chain.proceed();
                    try {
                        List<Object> args = chain.getArgs();
                        Object holder = args.isEmpty() ? null : args.get(0);
                        Object song = holder == null ? null : call(holder, "get");
                        if (song != null) {
                            onSong(song);
                        } else {
                            note("buildTimeRangeToLyricsMap gave no song");
                        }
                    } catch (Throwable t) {
                        note("onSong failed: " + t);
                    }
                    return result;
                });
        note("lyric build hooked (" + handles.size() + ")");
    }

    /** A built song: read it, turn it into a payload, and get it onto the session. */
    private static void onSong(Object song) {
        String id = str(call(song, "getAdamId"));
        List<Line> lines = readLines(song);
        List<String> voices = readVoices(song, lines);
        note("agents: " + describeAgents(song) + " -> voices=" + voices);
        if (lines.isEmpty()) {
            note("song " + id + " built with no lines");
            return;
        }
        String payload = payload(id, lines, voices);
        sHeldId = id;
        sHeldPayload = payload;
        note("song " + id + ": " + lines.size() + " lines, "
                + (lines.get(0).words.isEmpty() ? "line-timed" : "word-timed")
                + (voices.size() >= 2 ? ", duet(" + voices.size() + ")" : "")
                + ", bg=" + bgLines(lines)
                + ", first=" + lines.get(0).describe());
        // The track this belongs to is the one playing, so the session is holding a metadata
        // without it. Publishing again is what the lock screen's re-read is there to catch.
        if (id != null && id.equals(sPlayingId)) {
            republish(payload);
        }
    }

    // ---------------------------------------------------------------- reading Apple's objects

    /**
     * Apple's lyric, as a flat list of lines.
     *
     * The graph is song -> sections -> lines -> words, and every level of it is a native vector
     * whose elements come back wrapped: size() is a long, get(i) hands over a holder, and the
     * holder's own get() is the object. Sections carry the song's structure - verse, chorus -
     * which a lock screen has no use for, so they are flattened away.
     *
     * Everything is read by getter name. That is not a shortcut: these are JNI-bound objects
     * whose names survive the player's obfuscation, which is why this route is worth building on
     * at all - there is no mapping file to chase when Apple Music updates.
     */
    private static List<Line> readLines(Object song) {
        List<Line> out = new ArrayList<>();
        for (Object section : vector(call(song, "getSections"))) {
            for (Object native_ : vector(call(section, "getLines"))) {
                Line l = new Line();
                l.begin = num(call(native_, "getBegin"));
                l.end = num(call(native_, "getEnd"));
                l.agent = str(call(native_, "getAgent"));
                l.text = html(str(call(native_, "getHtmlLineText")));
                l.translation = html(str(call(native_, "getHtmlTranslationLineText")));
                for (Object w : vector(call(native_, "getWords"))) {
                    Word word = new Word();
                    word.begin = num(call(w, "getBegin"));
                    word.end = num(call(w, "getEnd"));
                    word.text = html(str(call(w, "getHtmlLineText")));
                    if (word.text != null && !word.text.isEmpty()) {
                        l.words.add(word);
                    }
                }
                // The background vocal, which takes an argument - Apple's getter is
                // getBackgroundWords(boolean), and asked without one it is simply not found.
                for (Object w : vector(call(native_, "getBackgroundWords", false))) {
                    Word word = new Word();
                    word.begin = num(call(w, "getBegin"));
                    word.end = num(call(w, "getEnd"));
                    word.text = html(str(call(w, "getHtmlLineText")));
                    if (word.text != null && !word.text.isEmpty()) {
                        l.bg.add(word);
                    }
                }
                respace(l.text, l.words);
                Object bgText = call(native_, "getHtmlBackgroundVocalsLineText");
                if (bgText == null) {
                    bgText = call(native_, "getHtmlBackgroundVocalsLineText", false);
                }
                respace(html(str(bgText)), l.bg);
                if (l.text != null && !l.text.isEmpty()) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /**
     * The spaces between the words, put back from the line they were cut out of.
     *
     * A word arrives as bare text - "Fifty,", "fifty", "under", "ground" - and a line's words
     * joined that way read "Fifty,fiftyfeetupunderground" (issue #29). Whether a space follows a
     * word cannot be told from the word itself - "under" and "ground" are one word sung in two -
     * so it is read off the line's own text, which does have them: each word is found in the line
     * from where the last one ended, and whatever lies between is hung on the end of the word
     * before, where every word-timed format here keeps it. A line the words cannot be found in
     * is left as it came; from the first word that is not found on, nothing is guessed.
     */
    private static void respace(String line, List<Word> words) {
        if (line == null || line.isEmpty() || words.size() < 2) {
            return;
        }
        int at = 0;
        Word prev = null;
        for (Word w : words) {
            int i = line.indexOf(w.text, at);
            if (i < 0) {
                return;
            }
            if (prev != null && i > at) {
                prev.text += line.substring(at, i);
            }
            at = i + w.text.length();
            prev = w;
        }
    }

    /** How many lines carry a background vocal, for the account in the log. */
    private static int bgLines(List<Line> lines) {
        int n = 0;
        for (Line l : lines) {
            if (!l.bg.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * The song's singers, in the order Apple lists them.
     *
     * A duet is not marked on the line - the line names an agent, and whether that means the
     * left or the right side is decided here. The lead is the first person. The answering voice
     * is the next agent that is not a group and actually sings a line: a lyric credited to a band
     * and one of its members is still not a duet, it is one voice, and that is the case the group
     * exclusion is for.
     *
     * It used to be people only, and Apple does not always call the second voice a person: 于是
     * (G.E.M.) names its agents {Person v1} {Other v2000}, so the duet came out as one voice and
     * replaced the AMLL copy - which does split it - a second after the track started
     * (2026-09-24).
     *
     * Two is the whole vocabulary. Apple can name more, the lock screen has two sides, and a
     * third voice would have to share one of them - so anything past the second is left on the
     * default side rather than being given a side at random.
     */
    private static List<String> readVoices(Object song, List<Line> lines) {
        List<String> out = new ArrayList<>();
        List<Object> agents = vector(call(song, "getAgents"));
        for (Object a : agents) {
            if (!person(call(a, "getType"))) {
                continue;
            }
            String id = str(call(a, "getId"));
            if (id != null && !id.isEmpty()) {
                out.add(id);
                break;
            }
        }
        for (Object a : agents) {
            Object type = call(a, "getType");
            if (group(type)) {
                continue;
            }
            String id = str(call(a, "getId"));
            if (id == null || id.isEmpty() || out.contains(id) || !sings(id, lines)) {
                continue;
            }
            if (out.isEmpty()) {
                out.add(id);
            } else {
                out.add(id);
                break;
            }
        }
        return out;
    }

    /** Whether any line is credited to this agent. */
    private static boolean sings(String id, List<Line> lines) {
        for (Line l : lines) {
            if (id.equals(l.agent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an agent is a group - the band, or everyone together - however the build says so.
     *
     * Only the enum's name is known (the vector hands over enums, measured). A number is a build
     * this has not seen, and there only a person counts, which is how voices were read before.
     */
    private static boolean group(Object type) {
        if (type == null) {
            return false;
        }
        if (type instanceof Number) {
            return ((Number) type).longValue() != AGENT_PERSON;
        }
        return "Group".equalsIgnoreCase(type.toString().trim());
    }

    /**
     * What the song says about its singers, in full, whether or not any of it was usable.
     *
     * Written for the case this was actually in: no duet came out and there was no way to see
     * whether the song had no agents, had agents of a type this does not count, or has them
     * somewhere this is not looking. So it reports the raw vector, every agent's type and id,
     * and - when there is no vector at all - which agent-ish methods the object does have.
     */
    private static String describeAgents(Object song) {
        Object v = call(song, "getAgents");
        if (v == null) {
            StringBuilder sb = new StringBuilder("getAgents() -> null; methods=[");
            Class<?> cls = song.getClass();
            int n = 0;
            for (Method m : cls.getMethods()) {
                String name = m.getName();
                if (m.getParameterCount() == 0 && name.startsWith("get") && n < 40) {
                    if (n++ > 0) {
                        sb.append(' ');
                    }
                    sb.append(name);
                }
            }
            return sb.append(']').toString();
        }
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        Object size = call(v, "size");
        sb.append(" size=").append(size);
        for (Object a : vector(v)) {
            sb.append(" {").append(a.getClass().getSimpleName())
                    .append(" type=").append(call(a, "getType"))
                    .append(" id=").append(call(a, "getId"))
                    .append('}');
        }
        return sb.toString();
    }

    /**
     * Whether an agent is a person, however this build of the player says so.
     *
     * Measured 2026-09-23, getType() answers with an enum that prints as "Person" - the numeric
     * form belongs to the serialised model, not to the object the vector hands over, and reading
     * it as a number quietly classified all three of 素颜's agents as "not a person" and lost
     * the duet. Both are accepted, because which one comes back is the player's business and
     * either answer is unambiguous.
     */
    private static boolean person(Object type) {
        if (type == null) {
            return false;
        }
        if (type instanceof Number) {
            return ((Number) type).longValue() == AGENT_PERSON;
        }
        return "Person".equalsIgnoreCase(type.toString().trim());
    }

    /** Apple's agent type for a person, as opposed to a group, a character or an organisation. */
    private static final long AGENT_PERSON = 1L;

    /** One native vector's elements, unwrapped. Empty for anything that is not one. */
    private static List<Object> vector(Object v) {
        List<Object> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        long n = num(call(v, "size"));
        // A vector long enough to be a mistake is a mistake: the lyric of a song is hundreds of
        // lines, and reading a million of anything here would hang the player's own thread.
        if (n <= 0 || n > 20000) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            Object holder = call(v, "get", i);
            Object item = holder == null ? null : call(holder, "get");
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private static final class Line {
        long begin;
        long end;
        String text;
        String translation;
        /** Which voice sings it, as Apple's own agent id. Null on a song with one singer. */
        String agent;
        /** The background vocal sung over this line, word by word. Empty for most lines. */
        final List<Word> bg = new ArrayList<>();
        final List<Word> words = new ArrayList<>();

        String describe() {
            return "[" + begin + "-" + end + "] " + text
                    + (words.isEmpty() ? "" : " (" + words.size() + " words, first "
                    + words.get(0).begin + "-" + words.get(0).end + " " + words.get(0).text + ")");
        }
    }

    private static final class Word {
        long begin;
        long end;
        String text;
    }

    // ---------------------------------------------------------------- making the payload

    /**
     * The lyric as the lock screen already knows how to read it.
     *
     * Three fields, because the reader prefers them in this order and can use any of them:
     * rawLyric carries the word timings as enhanced LRC, lyric is the same lines without them,
     * and translation is a plain LRC of the translated lines. A song Apple has no word timings
     * for simply gets no rawLyric, and the line-timed copy is what shows.
     */
    private static String payload(String id, List<Line> lines, List<String> voices) {
        StringBuilder plain = new StringBuilder();
        StringBuilder word = new StringBuilder();
        StringBuilder trans = new StringBuilder();
        boolean anyWords = false;
        boolean anyTrans = false;
        for (Line l : lines) {
            String stamp = stamp(l.begin);
            plain.append(stamp).append(l.text).append('\n');
            word.append(stamp);
            if (l.words.isEmpty()) {
                word.append(l.text);
            } else {
                anyWords = true;
                for (Word w : l.words) {
                    word.append('<').append(bare(w.begin)).append('>').append(w.text);
                }
                // The line's own end, so the last syllable has somewhere to stop. Without it a
                // word-timed renderer has no duration for the final word of every line.
                word.append('<').append(bare(l.end)).append('>');
            }
            word.append('\n');
            if (l.translation != null && !l.translation.isEmpty()) {
                anyTrans = true;
                trans.append(stamp).append(l.translation).append('\n');
            }
        }
        StringBuilder json = new StringBuilder(64);
        json.append("{\"songId\":").append(quote(id));
        // The name the payload is for, which the reader holds every payload against before it
        // believes one - see LyricSource.infoFor(). Apple publishes an ordinary title, so this
        // is a real check here rather than the dead weight it is on a player that writes
        // something else into that field.
        String title = sPlayingTitle;
        if (title != null && !title.isEmpty()) {
            json.append(",\"songName\":").append(quote(title));
        }
        json.append(",\"lyric\":").append(quote(plain.toString()));
        if (anyWords) {
            json.append(",\"rawLyric\":").append(quote(word.toString()));
        }
        if (anyTrans) {
            json.append(",\"translation\":").append(quote(trans.toString()));
        }
        // The same lyric again, in the one format that can carry everything it has. LRC has no
        // way to say who is singing, so a duet read back from the fields above is one voice;
        // TTML says it per line and the parser already believes it. Written alongside rather
        // than instead: this key is ours, every other reader of a lyricInfo will ignore it, and
        // if the parse of it ever fails the lines above are still a whole lyric.
        boolean anyBg = false;
        for (Line l : lines) {
            if (!l.bg.isEmpty()) {
                anyBg = true;
                break;
            }
        }
        if (voices.size() >= 2 || anyBg) {
            json.append(",\"ttml\":").append(quote(ttml(lines, voices)));
        }
        json.append('}');
        return json.toString();
    }

    /**
     * The lyric as TTML, for the one thing the other fields cannot express: who sings each line.
     *
     * Only the parts that carry meaning here are written - the agents, the lines, their words
     * and their translations. The first singer Apple lists is v1 and the second is v2, and the
     * renderer puts v2 against the other edge; a line sung by anyone else, or by nobody in
     * particular, stays on v1's side rather than being guessed at.
     */
    private static String ttml(List<Line> lines, List<String> voices) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
                .append("xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
                .append("<head><metadata>");
        for (int i = 0; i < voices.size() && i < 2; i++) {
            sb.append("<ttm:agent type=\"person\" xml:id=\"v").append(i + 1).append("\"/>");
        }
        sb.append("</metadata></head><body><div>");
        for (Line l : lines) {
            String v = "v1";
            if (l.agent != null && voices.size() >= 2 && l.agent.equals(voices.get(1))) {
                v = "v2";
            }
            sb.append("<p begin=\"").append(clock(l.begin)).append("\" end=\"")
                    .append(clock(l.end)).append("\" ttm:agent=\"").append(v).append("\">");
            if (l.words.isEmpty()) {
                sb.append(xml(l.text));
            } else {
                for (Word w : l.words) {
                    sb.append("<span begin=\"").append(clock(w.begin)).append("\" end=\"")
                            .append(clock(w.end)).append("\">").append(xml(w.text))
                            .append("</span>");
                }
            }
            // The background vocal, as the nested x-bg span the format reserves for it. The
            // parser turns this into a line of its own and the renderer hangs it under the line
            // it overlaps, smaller - so it must not be written as ordinary syllables, which
            // would put the echo in the main line's own text.
            if (!l.bg.isEmpty()) {
                Word first = l.bg.get(0);
                Word last = l.bg.get(l.bg.size() - 1);
                sb.append("<span ttm:role=\"x-bg\" begin=\"").append(clock(first.begin))
                        .append("\" end=\"").append(clock(last.end)).append("\">");
                for (Word w : l.bg) {
                    sb.append("<span begin=\"").append(clock(w.begin)).append("\" end=\"")
                            .append(clock(w.end)).append("\">").append(xml(w.text))
                            .append("</span>");
                }
                sb.append("</span>");
            }
            if (l.translation != null && !l.translation.isEmpty()) {
                sb.append("<span ttm:role=\"x-translation\">").append(xml(l.translation))
                        .append("</span>");
            }
            sb.append("</p>");
        }
        return sb.append("</div></body></tt>").toString();
    }

    /** TTML's clock time, written in full so nothing has to guess which fields were left out. */
    private static String clock(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long h = ms / 3600000;
        long m = (ms / 60000) % 60;
        long s = (ms / 1000) % 60;
        long f = ms % 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", h, m, s, f);
    }

    /** The five characters XML cannot carry as themselves. */
    private static String xml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** [mm:ss.SSS], which is what every LRC reader here accepts. */
    private static String stamp(long ms) {
        return "[" + bare(ms) + "]";
    }

    private static String bare(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long m = ms / 60000;
        long s = (ms / 1000) % 60;
        long f = ms % 1000;
        StringBuilder sb = new StringBuilder(10);
        if (m < 10) {
            sb.append('0');
        }
        sb.append(m).append(':');
        if (s < 10) {
            sb.append('0');
        }
        sb.append(s).append('.');
        if (f < 100) {
            sb.append('0');
        }
        if (f < 10) {
            sb.append('0');
        }
        sb.append(f);
        return sb.toString();
    }

    /** A JSON string literal, escaped the few ways a lyric can need. */
    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The text out of Apple's HTML.
     *
     * The fields are named htmlLineText and they mean it - a line arrives wrapped in markup and
     * with its entities escaped. What the lock screen draws is text, so the tags come out and
     * the handful of entities a lyric actually contains go back to being characters.
     */
    private static String html(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        String out = sb.toString();
        if (out.indexOf('&') >= 0) {
            out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                    .replace("&nbsp;", " ");
        }
        return out.trim();
    }

    // ---------------------------------------------------------------- publishing

    /**
     * Put the reference to the payload into a metadata on its way out, without disturbing
     * anything else in it. The payload itself is handed over on request - see hookCommand.
     */
    private static void inject(MediaMetadata md, String id, String payload) {
        try {
            Bundle b = (Bundle) Xp.getObjectField(md, "mBundle");
            if (b != null && id != null) {
                b.putString(LyricSource.KEY_LYRIC_REF, LyricSource.lyricRef(id, payload));
            }
        } catch (Throwable t) {
            note("could not attach to the metadata: " + t);
        }
    }

    /**
     * Set the session's current metadata again, with the lyric attached.
     *
     * For the common case - the lyric arrives seconds after the track - there is nothing else to
     * do: the metadata is already out, and the only way to revise it is to publish a new one.
     * The framework treats that as an ordinary metadata change, which is exactly what the lock
     * screen's re-read was built to notice.
     */
    private static void republish(String payload) {
        Object session = sSession;
        if (session == null) {
            note("no session to publish to");
            return;
        }
        try {
            Object controller = call(session, "getController");
            Object md = controller == null ? null : call(controller, "getMetadata");
            if (!(md instanceof MediaMetadata)) {
                note("session is holding no metadata to revise");
                return;
            }
            inject((MediaMetadata) md, sHeldId, payload);
            sPublishing = true;
            try {
                session.getClass().getMethod("setMetadata", MediaMetadata.class)
                        .invoke(session, md);
            } finally {
                sPublishing = false;
            }
            note("published the reference to " + payload.length() + " chars to the session");
        } catch (Throwable t) {
            sPublishing = false;
            note("republish failed: " + t);
        }
    }

    /**
     * The lock screen asking for the lyric a metadata referred to.
     *
     * MediaSession.dispatchCommand is the framework's own, called on the binder thread for every
     * command a controller sends: ours is answered here and never reaches the player, which
     * would not know it. The answer goes back to the one controller that asked.
     */
    // Inside Apple Music's process through the hook, on today's targets; the hook fails on its
    // own terms if the platform ever blocks it.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private static void hookCommand() throws Throwable {
        Method m = android.media.session.MediaSession.class.getDeclaredMethod("dispatchCommand",
                android.media.session.MediaSessionManager.RemoteUserInfo.class, String.class,
                Bundle.class, android.os.ResultReceiver.class);
        Xp.hook("apple.lyrics.dispatch_command", m, (XposedInterface.Hooker) chain -> {
            List<Object> args = chain.getArgs();
            if (!LyricSource.CMD_LYRIC.equals(args.get(1))) {
                return chain.proceed();
            }
            android.os.ResultReceiver cb = (android.os.ResultReceiver) args.get(3);
            Bundle asked = (Bundle) args.get(2);
            String ref = asked == null ? null : asked.getString(LyricSource.KEY_LYRIC_REF);
            String id = sHeldId;
            String payload = sHeldPayload;
            boolean have = ref != null && id != null && payload != null
                    && ref.equals(LyricSource.lyricRef(id, payload));
            if (cb != null) {
                Bundle out = new Bundle();
                if (have) {
                    out.putString("lyricInfo", payload);
                }
                cb.send(have ? 0 : 1, out);
            }
            note("asked for " + ref + (have ? ", sent " + payload.length() + " chars" : ", not held"));
            return null;
        });
    }

    // ---------------------------------------------------------------- asking for the download

    /**
     * Makes the player fetch a lyric it has not been asked for.
     *
     * Apple downloads a song's lyric when its own lyric page opens, and at no other time - so on
     * a locked phone the lyric for the track that just started does not exist yet and never
     * will. Handing the view model a Song that carries this id and claims to have lyrics starts
     * the same download the page would have started; the build hook above catches what comes
     * back. Nothing is displayed in the player and nothing about its playback is touched.
     */
    private static final class Requester {
        private final ClassLoader cl;
        private volatile Object vm;
        private volatile String last;

        Requester(ClassLoader cl) {
            this.cl = cl;
        }

        void request(String id) {
            if (id == null || id.isEmpty() || id.equals(last)) {
                return;
            }
            last = id;
            try {
                Class<?> songCls = cl.loadClass("com.apple.android.music.model.Song");
                Object song = songCls.newInstance();
                call(song, "setId", id);
                call(song, "setHasLyrics", true);
                if (vm == null) {
                    Class<?> vmCls = cl.loadClass(
                            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
                    android.app.Application app = app();
                    if (app == null) {
                        note("no application to build the view model with");
                        return;
                    }
                    vm = vmCls.getConstructor(android.app.Application.class).newInstance(app);
                }
                call(vm, "loadLyrics", song);
                note("asked for " + id);
            } catch (Throwable t) {
                note("could not ask for " + id + ": " + t);
            }
        }
    }

    /** The player's own Application, which the view model needs and nothing here else does. */
    private static android.app.Application app() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            return (android.app.Application) at.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    /**
     * A method by name on whatever this object is, called and allowed to fail.
     *
     * Every read of Apple's graph goes through here, and every one of them is allowed to come
     * back null: the shape of a lyric differs by song - no translation, no background vocals, no
     * word timings on an older track - and a missing getter is one of those, not an error.
     */
    private static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> cls = target.getClass();
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable ignored) {
                    // Another overload may still be the one meant.
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    // ---------------------------------------------------------------- diagnosis

    /**
     * A running account, in the player's own files directory.
     *
     * This process cannot reach the module's usual probe - that receiver lives in SystemUI - and
     * its log lines do not survive to logcat on this phone, so what happened in here would
     * otherwise be unreadable. Read it with
     * `su -c 'cat /data/data/com.apple.android.music/files/mc_apple.txt'`.
     */
    private static void note(String s) {
        Xp.log(TAG + s);
        try {
            android.app.Application app = app();
            if (app == null) {
                return;
            }
            java.io.File f = new java.io.File(app.getFilesDir(), "hyperchanger_hmc_next_apple.txt");
            if (f.length() > 64 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
            os.write((android.text.format.DateFormat.format("HH:mm:ss",
                    System.currentTimeMillis()) + "  " + s + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {
        }
    }
}
