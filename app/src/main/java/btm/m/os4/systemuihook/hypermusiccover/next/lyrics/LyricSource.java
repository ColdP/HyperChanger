// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Where the lyric rows come from.
 *
 * Deliberately not a lyric module's API. The modules that do this on this device reach the
 * players by hooking them - and on this device all three of the players were broken at once by
 * app updates the module had not caught up with: a NoSuchFieldError on Apple Music, a
 * NoSuchClassError on Salt, a DexKit cache miss on NetEase. Nothing that inherits that is worth
 * building on.
 *
 * This route never touches the player. A song id comes out of the media session, and the AMLL
 * database - a community-built set of word-level TTML files, one per song, named by the
 * platform's own id - is asked for that id directly. Nothing here breaks when a player updates,
 * because nothing here knows a player's internals.
 *
 * What it does inherit is coverage and the network. Tens of thousands of songs against millions
 * of songs means a miss is normal, and the mirrors are GitHub-hosted, which from here is a coin
 * toss: measured on this device the authoritative copy has answered in 610ms and has also timed
 * out after 23s. Both are handled by being explicit rather than optimistic - a miss leaves the
 * lock screen without lyrics, which is what it had before this existed, and no request is given
 * long enough to be felt.
 */
public final class LyricSource {

    /** Nothing was found, by any route. */
    static final int SRC_NONE = 0;
    /** The player published the whole lyric itself, or a provider module wrote it to the session. */
    static final int SRC_LYRIC_INFO = 1;
    /** The file being played carried its own lyric - a .lrc beside it, or its own tag. */
    static final int SRC_LOCAL = 6;
    /** A LyricProvider plugin, through the Lyricon bridge this module subscribes to. */
    static final int SRC_LYRICON = 7;
    /** The AMLL database, keyed by the platform's song id. */
    static final int SRC_DATABASE = 2;
    /** Found by name on NetEase - the fallback for a session carrying neither of the above. */
    static final int SRC_NETEASE = 3;
    /** Found by name on KuGou, which is asked when NetEase cannot place the song. */
    static final int SRC_KUGOU = 4;
    /** Found by name on LrcLib - the last net, and the one with the western catalogue. */
    static final int SRC_LRCLIB = 5;
    /** Found by name on QQ Music. */
    static final int SRC_QQ = 8;
    /** Found by name on Kuwo. */
    static final int SRC_KUWO = 9;
    /** LunaBeat's TTML Hub, keyed by the platform's song id like the AMLL database. */
    static final int SRC_HUB = 10;

    interface Callback {
        /**
         * Always called on the main thread. lines is never null; empty means nothing was found.
         *
         * `source` is one of the SRC_ constants and is not decoration: the session's own lyrics
         * can arrive after the lyrics we settled for, and the caller upgrades to them when they
         * do - which it can only do if it knows what it is currently showing.
         */
        void onLines(List<LyricLine> lines, String why, int source);
    }

    /**
     * Whether the session is carrying a lyric worth using right now.
     *
     * Cheap enough to ask on every metadata change, which is what the caller does. A provider
     * module cannot write its lyric until the player has told it what is playing, so the session
     * routinely publishes lyricInfo a moment - or several seconds - after the track itself.
     * Before that, the field is either absent or an explicit empty shell: MeiLoX publishes
     * {"lyric":"","noLyric":true} while it is still looking.
     */
    public static boolean hasLyricInfo(MediaController c) {
        return usable(infoFor(c));
    }

    /**
     * Whether a payload is worth starting a lookup for, without parsing it.
     *
     * The single-timestamp case is the one this exists for. Several players publish the line
     * being sung under the same key as the whole lyric, and a payload carrying one line reads
     * here as "the session has this song's lyric" - which is the answer that makes the caller
     * throw away a whole file it already had. Counted rather than parsed because this is asked
     * on every metadata change and the parse is the expensive half.
     *
     * Only the display field is counted. Word-timed payloads (YRC, QRC, TTML) do not use LRC's
     * [mm:ss] tags at all, so there is nothing to count in rawLyric and it is taken at its word;
     * what it actually holds is settled by parsing it, in session().
     */
    static boolean usable(String info) {
        if (info == null) {
            return false;
        }
        // A payload that is only a TTML document - no lyric or rawLyric beside it - is still the
        // song's lyric, and the richest form of it.
        if (documentOfLyricInfo(info) != null) {
            return true;
        }
        String raw = rawOfLyricInfo(info);
        if (raw != null) {
            return !placeholder(raw);
        }
        String text = textOfLyricInfo(info);
        return text != null && timedLines(text) >= MIN_SESSION_LINES && !placeholder(text);
    }

    /** How many LRC timestamps a payload carries, counted no further than the answer needs. */
    private static int timedLines(String text) {
        java.util.regex.Matcher m = TIMED.matcher(text);
        int n = 0;
        while (n < MIN_SESSION_LINES && m.find()) {
            n++;
        }
        return n;
    }

    /**
     * How many lines a session payload must carry before it counts as this song's lyric.
     *
     * Two. One line is never a lyric file - it is the line being sung, or a placeholder like
     * NetEase's "纯音乐，请欣赏" - and the smallest genuine payload measured here was eighteen
     * lines, so there is nothing in between to get wrong.
     */
    private static final int MIN_SESSION_LINES = 2;

    /**
     * The payload the session is carrying, or null when it is not this song's.
     *
     * A track change is the one moment this field cannot be believed, and it is the only moment
     * anyone reads it. The player rewrites the metadata and the lyric as two separate updates,
     * so between them the session carries the new song's title over the old song's lyric.
     * Measured 2026-09-20 on NetEase: 以父之名 was read 1ms after the card reported it and
     * answered with a single line, which was then taken for that song's whole lyric, cached
     * under its key, and - being recorded as the session's own - locked every better route out
     * for the rest of the song. Five songs in one evening went that way.
     *
     * So the payload is asked which song it is for rather than assumed to be keeping up. Only a
     * positive contradiction rejects it: a payload with no songName is common and says nothing
     * either way, and a title carries decorations the payload need not repeat - "白色风车" and
     * "白色风车 (Live)" are the same song - so one containing the other is agreement.
     *
     * The title is not the only field that can answer, and on some players it is the wrong one.
     * Salt Player writes the line being sung into TITLE - measured 2026-09-22, TITLE went
     * "笑声更迷人" -> "Oh oh oh Oh oh" -> "抹去雨水双眼无故地仰望" through one play of 喜欢你 -
     * and puts "歌手 - 歌名" in ARTIST instead. Held against the title alone, a perfectly current
     * payload reads as a stale one from the first sung line onwards: the only moment it passes is
     * the track change itself, before the player has overwritten TITLE. That is why the lyric was
     * there when the provider module was quick and gone for the whole song when it was not.
     * So ARTIST's tail and the album are asked too, and agreement with any of the three is
     * agreement - a rejection now needs every field the session published to disagree.
     * ARTIST is read every way a player spells a song into it (TrackName.splits): Salt's
     * "歌手 - 歌名", 汽水's "歌名 — 歌手" and QQ's "歌名-歌手", which is how QQ's payload came to be
     * refused on every sung line (#47).
     */
    static String infoFor(MediaController c) {
        String session = sessionInfoFor(c);
        if (usable(session)) {
            return session;
        }
        // Players that put the lyric itself on the metadata, under the platform's key or one of
        // their own, rather than in a lyricInfo payload. Taken only when it is a lyric with times
        // in it: a few of these keys carry a plain text that cannot be followed.
        for (String key : METADATA_LYRIC_KEYS) {
            String body = metaOf(c, key);
            if (body == null || body.isEmpty() || placeholder(body)) {
                continue;
            }
            if (timedLines(body) < MIN_SESSION_LINES && !body.contains("<tt")
                    && !WORD_TIMED.matcher(body).find()) {
                continue;
            }
            try {
                return new org.json.JSONObject().put("rawLyric", body).toString();
            } catch (Throwable ignored) {
            }
        }
        return session;
    }

    private static final String[] METADATA_LYRIC_KEYS = {
            "android.media.metadata.LYRICS", "lyrics", "LYRICS", "lyric", "ttml"
    };
    /** A YRC/QRC line, [start,duration] then (start,duration,0) or <start,duration> words. */
    private static final java.util.regex.Pattern WORD_TIMED =
            java.util.regex.Pattern.compile("(?m)^\\[(?:[0-9]+,[0-9]+|[0-9]+)\\].*[<(][0-9]+,");

    private static String sessionInfoFor(MediaController c) {
        String info = lyricInfoOf(c);
        if (info == null) {
            return null;
        }
        String theirs = songNameOf(info);
        String ours = titleOf(c);
        if (theirs == null || ours == null) {
            return info;
        }
        String a = theirs.trim().toLowerCase();
        String b = ours.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)) {
            return info;
        }
        if (TrackName.songIn(a, metaOf(c, MediaMetadata.METADATA_KEY_ARTIST))
                || agrees(a, metaOf(c, MediaMetadata.METADATA_KEY_ALBUM))) {
            return info;
        }
        Xp.log("[MCLyric] the session's lyricInfo is still \"" + theirs
                + "\" while the track is \"" + ours + "\"; not reading it");
        return null;
    }

    /**
     * Whether one of the session's other fields carries the name the payload claims.
     *
     * `name` is already trimmed and lowered; the field is not. Same containment test as the
     * title's, and for the same reason: either side may carry decorations the other does not.
     */
    private static boolean agrees(String name, String other) {
        if (other == null) {
            return false;
        }
        String s = other.trim().toLowerCase();
        return !s.isEmpty() && (name.contains(s) || s.contains(name));
    }

    /** Which song the payload says it is for, or null when it does not say. */
    private static String songNameOf(String json) {
        try {
            return jsonString(new org.json.JSONObject(json), "songName");
        } catch (Throwable t) {
            return null;
        }
    }

    /** What the session says is playing, for the payload to be held against. */
    private static String titleOf(MediaController c) {
        return metaOf(c, MediaMetadata.METADATA_KEY_TITLE);
    }

    /** One metadata string, or null - including when the session has no metadata at all. */
    private static String metaOf(MediaController c, String key) {
        try {
            MediaMetadata md = c.getMetadata();
            return md == null ? null : md.getString(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private LyricSource() {
    }

    /**
     * The key OPlus's own lock screen reads its lyrics from, as a JSON string on the session's
     * metadata.
     *
     * This is the primary source and the ID lookup below is the fallback, because this one has
     * none of the ID route's problems: the lyrics are the player's own - for a local-file player
     * they are the file's own lyrics, which is the only thing that can be right for music that
     * is not in any online database - and they arrive with the session instead of after a network
     * round trip, so there is no coverage to miss and no mirror to be down.
     *
     * Nothing here talks to a player. The contract is a JSON object with a timed LRC string under
     * "lyric" (or, for word-level payloads, under "rawLyric"), plus songName/artist/album/ and a
     * translation under one of several names. It is written by whoever has the lyrics - the
     * player itself, or one of the provider modules that hook the players that do not.
     */
    private static final String KEY_LYRIC_INFO = "lyricInfo";

    /**
     * Our Apple hook's lyric, by reference: the metadata carries this, and the payload is asked
     * for with CMD_LYRIC. The payload in the metadata itself went to every controller on the
     * session each time the player set it, and dropped the mini player's callback - see
     * AppleLyrics.
     */
    static final String KEY_LYRIC_REF = "mcLyricRef";
    static final String CMD_LYRIC = "btm.m.os4.systemuihook.hypermusiccover.next.LYRIC";

    /** Which song's payload, and which payload: a lyric rebuilt for the same song is a new one. */
    static String lyricRef(String id, String payload) {
        return id + "#" + Integer.toHexString(payload.hashCode()) + "#" + payload.length();
    }

    /** The one payload fetched by reference, and the reference asked for last and when. */
    private static final Object REF_LOCK = new Object();
    private static String sRefHeld;
    private static String sRefPayload;
    private static String sRefAsked;
    private static long sRefAskedAt;
    /** Asked again after this, for an answer that never came or a player that had nothing yet. */
    private static final long REF_RETRY_MS = 3000L;

    /**
     * The payload `ref` names, or null while it is being fetched. The answer comes on a binder
     * thread; it is then the session's payload for every reader, and the lock screen is told the
     * session is carrying a lyric it has not read, as a provider module's late one would.
     */
    private static String byRef(MediaController c, final String ref) {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (REF_LOCK) {
            if (ref.equals(sRefHeld)) {
                return sRefPayload;
            }
            if (ref.equals(sRefAsked) && now - sRefAskedAt < REF_RETRY_MS) {
                return null;
            }
            sRefAsked = ref;
            sRefAskedAt = now;
        }
        final String pkg = c.getPackageName();
        android.os.Bundle args = new android.os.Bundle();
        args.putString(KEY_LYRIC_REF, ref);
        c.sendCommand(CMD_LYRIC, args, new android.os.ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int code, android.os.Bundle data) {
                String payload = data == null ? null : data.getString(KEY_LYRIC_INFO);
                if (code != 0 || payload == null) {
                    Xp.log("[MCLyric] " + pkg + " did not have " + ref);
                    return;
                }
                synchronized (REF_LOCK) {
                    sRefHeld = ref;
                    sRefPayload = payload;
                }
                Xp.log("[MCLyric] " + pkg + " sent " + payload.length() + " chars for " + ref);
                Main.main().post(LockLyrics::onSessionPayload);
            }
        });
        return null;
    }

    /** LRC-style timing, bracketed with [] or <>. <> is the word-level (enhanced) form. */
    static final java.util.regex.Pattern TIMED =
            java.util.regex.Pattern.compile("[\\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\\]>]");

    /**
     * The player's own lyrics, if the session carries them. Null when nothing has published any,
     * which is the normal state on a device with no provider modules installed.
     */
    // "lyricInfo" is not one of the framework's metadata keys and is not meant to be: it is the
    // players' and provider modules' own, and a Bundle lookup by any string is valid.
    @android.annotation.SuppressLint("WrongConstant")
    public static String lyricInfoOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            String info = md.getString(KEY_LYRIC_INFO);
            if (info != null) {
                return info;
            }
            String ref = md.getString(KEY_LYRIC_REF);
            return ref == null ? null : byRef(c, ref);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The timed lyric text out of that payload, or null if there is none worth rendering.
     *
     * A payload without timing is rejected rather than shown: this view follows the singing, and
     * untimed text has nothing to follow. That is also the difference between the two fields -
     * "lyric" is the display form and is preferred, "rawLyric" is kept verbatim for word-level
     * renderers and is only used when "lyric" carries no timing at all.
     */
    static String textOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String lyric = jsonString(o, "lyric");
            String raw = jsonString(o, "rawLyric");
            if (lyric != null && TIMED.matcher(lyric).find()) {
                return lyric;
            }
            if (raw != null && TIMED.matcher(raw).find()) {
                return raw;
            }
            return null;
        } catch (Throwable t) {
            Xp.log("[MCLyric] lyricInfo is not the expected JSON: " + t);
            return null;
        }
    }

    /**
     * The translated lines out of that payload, as a plain LRC, or null when it carries none.
     *
     * Eight names for one field. The contract the provider modules were written against calls
     * it "translation"; the players that publish their own lyricInfo, and the newer modules
     * that append to what a player already wrote, use one of the others. Nothing in a payload
     * says which convention it follows, so all of them are tried in the order a payload that
     * has more than one would want - the specific names first, the bare "translation" last.
     *
     * Until this existed the session route dropped every translation it was handed: the field
     * was read by the by-name routes, which fetch theirs separately, and by nothing else. A
     * player publishing a translated lyric showed the original alone.
     */
    static String translationOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            for (String key : TRANSLATION_KEYS) {
                String s = jsonString(o, key);
                if (s != null && TIMED.matcher(s).find()) {
                    return s;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String[] TRANSLATION_KEYS = {
            "translationLyric", "translatedLyric", "translateLyric", "transLyric",
            "lyricTranslation", "translationLrc", "transLrc", "translation",
    };

    /**
     * Whether a payload is a stand-in for a lyric rather than one.
     *
     * "暂无歌词" under a [00:00.00] is a timed line by every test this file makes, and several
     * players publish exactly that while they look - or when they have looked and found
     * nothing. Taken at face value it is worse than an empty field: it counts as the session
     * having this song's lyric, so the routes that might actually have found it are never asked,
     * and the lock screen shows the words "no lyrics" scrolling past in the singing's place.
     *
     * Every line has to be one of these before the payload is rejected. A line of a real song
     * can say anything, and a file is not a placeholder because one line of it matches.
     */
    static boolean placeholder(String text) {
        if (text == null) {
            return false;
        }
        // Walked rather than split: this is asked on every metadata change, the answer is no
        // for every real lyric, and the first sung line settles it - so a whole file's worth of
        // lines is neither allocated nor looked at.
        boolean any = false;
        int from = 0;
        while (from <= text.length()) {
            int nl = text.indexOf('\n', from);
            String line = nl < 0 ? text.substring(from) : text.substring(from, nl);
            from = (nl < 0 ? text.length() : nl) + 1;
            String s = TAGS.matcher(line).replaceAll("").trim();
            if (s.isEmpty()) {
                continue;
            }
            any = true;
            if (!isPlaceholder(s)) {
                return false;
            }
        }
        return any;
    }

    /** Timing and metadata tags alike: what is left of a line is what would be sung. */
    private static final java.util.regex.Pattern TAGS =
            java.util.regex.Pattern.compile("\\[[^\\]]*\\]|<[^>]*>");

    /**
     * A catalogue's answer that the song is instrumental, taken for what it means: no lyrics.
     *
     * The session, the bridge and the file were already held to placeholder(); the catalogues
     * were not, and NetEase answers an instrumental with a lyric of one line, "纯音乐，请欣赏"
     * (#25). Taken as the song's lyric it put the lyric page up over the cover with those words
     * on it; with no lyrics the lock screen keeps the cover, square or full, which is what a
     * song with nothing to sing should show. Judged on the parsed lines rather than the raw
     * text, because a catalogue's credits come first - NetEase writes them as JSON lines, which
     * only the parser turns into "作词: ..." - and a credit is neither a lyric nor a placeholder.
     * True when [r] was emptied.
     */
    private static boolean dropPlaceholder(Rows r) {
        if (!placeholderLines(r.lines)) return false;
        r.why = "instrumental, by its lyric: " + r.why;
        r.lines = java.util.Collections.emptyList();
        r.source = SRC_NONE;
        return true;
    }

    /** Every line a placeholder or a credit, and at least one a placeholder. */
    static boolean placeholderLines(List<LyricLine> lines) {
        boolean any = false;
        for (LyricLine l : lines) {
            String s = l.text == null ? "" : l.text.trim();
            if (s.isEmpty() || CREDIT.matcher(s).find()) {
                continue;
            }
            if (!isPlaceholder(s)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** "作词: ...", "Composer：...": a short name for a role, then a colon. */
    private static final java.util.regex.Pattern CREDIT =
            java.util.regex.Pattern.compile("^[^:：]{1,16}[:：]");

    private static boolean isPlaceholder(String line) {
        String s = line.toLowerCase();
        for (String p : PLACEHOLDERS) {
            if (s.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the players and the modules write when there is nothing to write.
     *
     * Matched by containment, because they are published with and without punctuation and with
     * the song's name appended - NetEase's is "纯音乐，请欣赏" and Apple's is "No lyrics
     * available". Lower-cased first, so the English ones are written that way here.
     */
    private static final String[] PLACEHOLDERS = {
            "暂无歌词", "没有歌词", "未找到歌词", "歌词加载中", "正在加载歌词", "纯音乐",
            "no lyrics", "no lyric", "lyrics not found", "instrumental",
    };

    /** The payload's rawLyric, verbatim - the form that keeps word timings, when there are any. */
    static String rawOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String raw = jsonString(new org.json.JSONObject(json), "rawLyric");
            // Not tested against TIMED: word-timed formats (YRC, QRC, TTML) do not use LRC's
            // [mm:ss] tags at all. Whether it is usable is decided by parsing it.
            return raw == null || raw.trim().isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A string field of a payload, or null when it is absent, null, or not a string at all.
     *
     * Not optString(key, ""), which answers a different question on Android than it does on the
     * JVM: there a JSON null is a sentinel object and optString returns String.valueOf(that) -
     * the string "null" - where the fallback was asked for. A player publishing an empty shell
     * while it looks the lyrics up writes exactly that, and reading it as a lyric whose text is
     * "null" made hasLyricInfo() report that the session had one when it did not, which is the
     * signal the caller uses to decide whether the lyrics it settled for are worth replacing.
     * The same rule, and the same trap, cost the NetEase route a whole song: see NcmLyrics.str.
     */
    private static String jsonString(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    /** The payload's TTML document, or null when it carries none or an empty one. */
    private static String documentOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            String doc = jsonString(new org.json.JSONObject(json), "ttml");
            return doc == null || doc.trim().isEmpty() ? null : doc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The one directory this player's ids belong in.
     *
     * One, not a list to work through. An id is only meaningful inside its own platform's
     * namespace, and the package says which platform that is - Apple Music publishes an Apple
     * store id, a NetEase client a NetEase id. Asking the other directories for it cannot
     * succeed on purpose and can succeed by accident: the numbers are plain integers in the same
     * range, so a collision returns a real, well-formed, completely unrelated song's lyrics with
     * nothing to mark them as wrong. Silently wrong beats nothing only in a bug report.
     *
     * It was a list, and the cost was not only the risk: three directories meant three rounds of
     * racing four mirrors each, every one of them a guaranteed miss for the two wrong ones, and
     * the fallback below did not even start until they had all finished. That is the "lyrics take
     * a while to turn up" this fixed.
     */
    private static String dirFor(MediaController c) {
        return dirForPackage(c == null ? "" : c.getPackageName());
    }

    /**
     * The players that play from a catalogue: what they play is that catalogue's recording, so a
     * file on this phone is not read for them even when it has the better lyric - only when
     * nothing else had one at all (a song uploaded to a cloud drive, say). Every player not
     * here is taken to play files and is read the other way round, the file first. Listed this
     * way because these are the few, and the file players many. Mi Music and the other phone
     * makers' players play both and count as these.
     */
    private static final String[] STREAMING = {
            "com.tencent.qqmusic", "com.netease.cloudmusic", "com.kugou", "cn.kuwo",
            "com.luna.music", "cn.wenyu.bodian", "cmccwm.mobilemusic", "com.apple.android.music",
            "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.google.android.youtube", "tv.danmaku.bili", "com.bilibili", "com.miui.player",
            "com.huawei.music", "com.heytap.music", "com.android.bbkmusic", "com.amazon.mp3",
            "deezer.android.app", "com.aspiro.tidal", "com.soundcloud.android",
    };

    static boolean streaming(String pkg) {
        if (pkg == null) return false;
        if (pkg.contains("meilox")) return true;
        for (String p : STREAMING) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    /** The same by package name, for a caller holding a queue rather than a session. */
    public static String dirForPackage(String pkg) {
        if (pkg == null) {
            return null;
        }
        // By the package itself, not by a word in it: "apple" was found in any package that
        // had it, and a player's ids looked up in another platform's directory come back as a
        // well-formed lyric of some other song (see below).
        if (pkg.startsWith("com.apple.android.music")) {
            return "am-lyrics";
        }
        if (pkg.startsWith("com.spotify.music")) {
            return "spotify-lyrics";
        }
        if (pkg.startsWith("com.tencent.qqmusic")) {
            return "qq-lyrics";
        }
        // MeiLoX is a NetEase client under its own name and publishes NetEase ids - measured,
        // not assumed: MEDIA_ID 2717588324 with cover art from p2.music.126.net.
        if (pkg.startsWith("com.netease.cloudmusic") || pkg.contains("meilox")) {
            return "ncm-lyrics";
        }
        // Everyone else: no directory, so the database is not asked at all.
        //
        // The database has four directories and they are four platforms' id spaces. A player
        // outside them - Kugou, Qishui, Salt, Mi Music - publishes ids that mean nothing in any
        // of them, so a lookup is a wasted request at best and a wrong song at worst: the ids
        // are plain integers in overlapping ranges, and a collision returns a real, well-formed,
        // unrelated lyric with nothing about it to look wrong. Falling back to ncm-lyrics for
        // unknown packages did exactly that. These players go to the by-name route, which is
        // where they were always going to end up.
        return null;
    }

    /**
     * Every mirror is asked at once and the first one to have the file wins.
     *
     * Not a chain, which is what this started as and what the log showed the cost of: raw timed
     * out after 23s, ghfast after 16s, and only then did jsdelivr answer - so a song that was in
     * the database took the better part of a minute to appear, if the screen was still locked.
     * Racing them costs the same bandwidth as the chain's worst case and the latency of its
     * best. They are all the same file; there is nothing to be gained by preferring one.
     *
     * gitmirror is the exception and is kept last for the record: it does not resolve from this
     * network at all, which costs nothing to find out in parallel and would have cost the whole
     * timeout budget in a chain.
     */
    private static final String[][] MIRRORS = {
            {"raw", "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"jsdelivr", "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/%s/%s.ttml"},
            {"ghfast", "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"gitmirror", "https://raw.gitmirror.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
    };

    /** FOUND carries a body; MISSING is GitHub saying the file is not there; UNREACHABLE is us. */
    private static final int FOUND = 1;
    private static final int MISSING = 2;
    private static final int UNREACHABLE = 3;

    /** Package-private so the probe can read the status as well as the body. */
    static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * Room for three lookups' mirrors at once, not one's.
     *
     * It was MIRRORS.length, which is the one size that cannot work: a single lookup fires every
     * mirror at once and so fills the pool exactly, and the next song's four requests then wait
     * behind four connections that are allowed 4s to connect and 6s to read. Skipping through a
     * few tracks was enough to put the newest song's requests last in a queue whose head had no
     * reason to hurry - the lyrics arriving "eventually", long after the song they belong to.
     *
     * Three deep rather than unbounded because the requests that would need a fourth are ones
     * superseded() has already declined to send, and this runs inside SystemUI. Derived from
     * MIRRORS.length so that adding a mirror widens the pool with it instead of quietly
     * recreating the jam.
     */
    private static final ExecutorService POOL = Gen.executor("MCLyricFetch",
            MIRRORS.length * 3, Thread.NORM_PRIORITY, true);

    /**
     * Which lookup is the live one. Only ever the newest.
     *
     * A track change makes every lookup before it unwanted - LockLyrics already drops an answer
     * that lands after the song moved on, by its own count. This is the same fact told to the
     * requests instead of to the answer, and it is what keeps the pool above from filling with
     * work whose result is known to be worthless: a superseded request sent anyway costs a
     * thread for the whole of its timeout, and the song that is actually playing waits for it.
     *
     * Bumped by every entry point, so the demo counts too. That is correct rather than merely
     * harmless - a demo and a track lookup are both "the lyrics on screen now", and only one of
     * them can be.
     */
    private static volatile int sLoadGen;

    /**
     * The same, for a lookup nobody is waiting for yet.
     *
     * Counted downwards so the two never collide: a real lookup's generation is always positive
     * and a prefetch's always negative. They have to be separate numbers rather than one, because
     * a prefetch outlives exactly the event that ends a real lookup - the track change it was
     * fetched in anticipation of - and a shared counter would cancel every prefetch at the moment
     * it became the answer.
     */
    private static volatile int sWarmGen;

    /** Whether a newer lookup has started, i.e. nothing this one finds will be shown. */
    private static boolean superseded(int gen) {
        return gen < 0 ? gen != sWarmGen : gen != sLoadGen;
    }

    /**
     * What a prefetch has already been told about a directory and id.
     *
     * Keyed the way the request is, not the way a track is: a queue item and the session that
     * later reports the same track agree on the platform id and on nothing else reliably, so the
     * id is the only thing both halves can be keyed on. Misses are kept as well as hits - a
     * prefetch that proved the song is not in the database saves the real lookup the same four
     * mirrors it would have raced to learn that.
     */
    private static final int WARM_MAX = 8;
    private static final java.util.LinkedHashMap<String, Answer> WARM =
            new java.util.LinkedHashMap<String, Answer>(WARM_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Answer> e) {
                    return size() > WARM_MAX;
                }
            };

    /**
     * Fetches a track that has not been asked for yet, into the caches the real lookup reads.
     *
     * Nothing here is handed to anyone: both halves write into a cache and the lookup that
     * follows the track change finds them there by its own ordinary route. That is the whole
     * point - the prediction can be wrong, and a wrong one then costs a cache entry nobody ever
     * looks up rather than a lyric on the wrong song.
     *
     * Blocking, and called on a thread of its own: the mirrors take seconds when they take
     * anything, and the artwork prefetch shares neither the thread nor the wait.
     */
    public static void warm(String id, String dir, String title, String artist) {
        if (!LockLyrics.sOnline) return;
        final int gen = --sWarmGen;
        if (id != null && dir != null) {
            String key = dir + '/' + id;
            boolean have;
            synchronized (WARM) {
                have = WARM.containsKey(key);
            }
            if (!have) {
                Answer a = fetch(gen, dir, id);
                if (a.status != UNREACHABLE) {
                    synchronized (WARM) {
                        WARM.put(key, a);
                    }
                    Xp.log("[MCLyric] warmed " + key + ": "
                            + (a.status == FOUND ? a.body.length() + " chars" : "not there"));
                }
            }
        }
        // Only the search, and only into NcmLyrics' own cache - the real lookup reads it there.
        // Not the whole by-name lookup: choosing between the results needs the duration, which a
        // queue item does not carry, and a choice made without one can land on another recording
        // of the same song. The search does not need it, so the search is what is done early.
        if (title != null) {
            NcmLyrics.warmSearch(title, artist);
        }
    }

    /** For op queue: what the database half of reading ahead is holding. */
    public static String describeWarm() {
        synchronized (WARM) {
            int found = 0;
            for (Answer a : WARM.values()) {
                if (a.status == FOUND) found++;
            }
            return "warmed=" + WARM.size() + "(" + found + " present)";
        }
    }

    /** A prefetched answer for this directory and id, or null when there is none. */
    private static Answer warmed(String dir, String id) {
        synchronized (WARM) {
            return WARM.get(dir + '/' + id);
        }
    }

    /**
     * The song id, if the session is publishing one.
     *
     * MEDIA_ID is the documented place, and it is what both a NetEase client and Apple Music
     * fill in - each with its own platform's id, which is exactly the key the database wants. It
     * is also frequently empty, and frequently an internal uri rather than an id, so it is only
     * taken when it is all digits. A local-file player publishes no id at all, and there is
     * nothing to guess from.
     */
    static String idOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id == null || id.isEmpty()) {
                return null;
            }
            for (int i = 0; i < id.length(); i++) {
                if (!Character.isDigit(id.charAt(i))) {
                    return null;
                }
            }
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fetch and parse, on a worker, then hand the rows to the main thread.
     *
     * The caller is a track change, i.e. SystemUI's main thread in the middle of a transition
     * that is already doing the cover and the clock - so nothing here touches the network or
     * parses a few tens of kilobytes on it.
     */
    static void load(final MediaController c, final Callback cb) {
        final String pkg = c == null ? "?" : c.getPackageName();

        // Not lyricInfoOf: at a track change the field routinely still holds the song before
        // this one, and believing it is what cost five songs their lyrics. See infoFor().
        final String info = infoFor(c);
        final String dir = dirFor(c);
        // An id is only worth having when there is a directory it belongs to; without one it
        // cannot be looked up anywhere, and pretending otherwise is how a lookup lands in the
        // wrong platform's id space.
        final String id = dir == null ? null : idOf(c);
        final NcmLyrics.Query q = NcmLyrics.queryOf(c);
        // Claimed before the early return below as well: a track with nothing to read from is
        // still a track change, and leaving the previous song's lookup live would let its
        // requests go on holding pool threads for a song nobody is listening to.
        final int gen = ++sLoadGen;
        // Held now, on the caller's thread, because the worker below has no way to reach one.
        final android.content.Context ctx = Main.sAppCtx;
        final MediaController controller = c;
        // Read once: a switch flipped mid-lookup starts a lookup of its own (LockLyrics.setOnline).
        final boolean online = LockLyrics.sOnline;
        if (info == null && id == null && q == null && ctx == null) {
            Xp.log("[MCLyric] " + pkg + " publishes neither lyricInfo, a song id, nor a name");
            onMain(cb, java.util.Collections.<LyricLine>emptyList(),
                    "nothing to read from " + pkg, SRC_NONE);
            return;
        }
        Gen.thread("MCLyricSource", new Runnable() {
            @Override
            public void run() {
                Rows r = new Rows();
                if (q != null && ctx != null) {
                    r.lines = HostLyricLibrary.load(ctx, q);
                    if (!r.lines.isEmpty()) {
                        r.source = SRC_LOCAL;
                        r.why = "HyperChanger lyric library";
                    }
                }
                // A player of files is playing a file on this phone, and its lyric is the one
                // the person keeps with it: read first, and kept over whatever the session or a
                // module has, word-timed or not (#64 - a module's lyric was taking Salt's place).
                // A streaming player's song is its catalogue's recording, which a file of the
                // same name need not be; it reads the file only once everything else is empty.
                boolean files = !streaming(pkg);
                if (files && r.lines.isEmpty()) {
                    local(ctx, controller, r);
                }
                // The sources after it, best first, each one asked only because the one before
                // it came up empty. Every step falls through rather than stopping, which is the
                // whole shape of this: a source that is present but useless - a provider module
                // that wrote a lyricInfo it could not fill, an id the database does not have -
                // used to end the search, and the song played on with nothing on screen while a
                // perfectly good answer sat one step further down.
                if (r.lines.isEmpty() && info != null) {
                    session(info, r);
                }
                // The bridge, when the session had nothing. Same standing as the session's own
                // payload and for the same reason - both are the player's lyric, handed over by
                // whoever managed to reach it.
                if (r.lines.isEmpty()) {
                    lyricon(controller, r);
                }
                // A placeholder from either of those two says only that whoever filled it had no
                // lyric - MeiloX's bridge sends one line of "纯音乐" for a song it has none for -
                // not that the song has no words. Dropped, and the search goes on: taken as a
                // catalogue's verdict it stopped a streaming player's .lrc being read (花海,
                // 2026-10-22). A file player's own file saying so is still believed, below.
                if (r.source == SRC_LYRIC_INFO || r.source == SRC_LYRICON) {
                    dropPlaceholder(r);
                }
                if (r.lines.isEmpty() && online && (id != null || q != null)) {
                    race(gen, pkg, ctx, id, dir, q, r);
                }
                // A catalogue that places the song and answers that it is instrumental has
                // answered: the song has no words, and the next catalogue is not asked for some.
                boolean instrumental = dropPlaceholder(r);
                // The other two catalogues, in order, and only for a song the first three could
                // not place. Sequential rather than raced: this is the slow path by definition,
                // nothing above it is still running by the time it starts, and a song that
                // already works never reaches it, so what it costs is paid only by songs that
                // would otherwise show nothing at all.
                if (r.lines.isEmpty() && online && q != null && !instrumental) {
                    web(pkg, q, r);
                    instrumental = dropPlaceholder(r);
                }
                // The file, for a streaming player, when nothing else had the song - and not
                // when a catalogue placed it and said it has no words.
                if (r.lines.isEmpty() && !files && !instrumental) {
                    local(ctx, controller, r);
                }
                // A translation left on the line itself by its tag writer (#95) is hung on the
                // line before anything reads it: borrow() must see it and not go and fetch a
                // second one, and the layout must draw it as a translation.
                r.lines = LyricParse.splitInlineTranslations(r.lines);
                Xp.log("[MCLyric] " + pkg + " -> " + r.why);
                onMain(cb, r.lines, r.why, r.source);
                // Extra to the answer already sent, so nothing in it is worth a thread - and an
                // uncaught throw on this one takes SystemUI with it.
                if (!online) return;
                try {
                    borrow(gen, pkg, ctx, id, dir, q, r, cb);
                } catch (Throwable t) {
                    Xp.w("[MCLyric] borrowing a translation failed: " + t);
                }
            }
        });
    }

    /**
     * A lyric with no translation or no romanisation, given one out of another copy of the song -
     * delivered as a second answer to the same lookup, once the first is already on screen.
     *
     * Whatever answered keeps its words and timing - the player's, the file's, the database's,
     * a catalogue's - and only the translations and romanisations are taken from the other copy
     * (LyricParse.borrow). Apple Music's lyric has no translation for most Japanese and English
     * songs (#62); a file saved without one has none, nor does KuGou for many Japanese songs,
     * and a romanisation is missing more often than not (#64). A translation is asked for when
     * its switch is on, no line has one and the words are not Chinese; a romanisation when its
     * switch is on, no line has one and the words are not in Latin letters - Chinese included,
     * for a Cantonese song's jyutping. Never from the source that answered: it has just said it
     * has none. Nothing is sent when nothing is found, and a newer lookup ends it like any other.
     */
    private static void borrow(int gen, String pkg, android.content.Context ctx, String id,
                               String dir, NcmLyrics.Query q, Rows r, Callback cb) {
        if (r.lines.isEmpty() || (id == null && q == null) || superseded(gen)) return;
        boolean hasTrans = false;
        boolean hasRoma = false;
        for (LyricLine l : r.lines) {
            hasTrans |= l.translation != null;
            hasRoma |= l.roma != null;
        }
        boolean[] want = {
                LockLyrics.sTrans && !hasTrans && LyricParse.foreign(r.lines),
                LockLyrics.sRoma && !hasRoma && LyricParse.romanisable(r.lines),
        };
        if (!want[0] && !want[1]) return;
        final int own = r.source;
        // The lenders in the order their copies are preferred: the database and the hub (by id,
        // hand-checked), the first two catalogues - all at once - then one search by a sung line,
        // and only then the slow catalogues one after another. In the other order a song the
        // line search finds at once waited out the slow route first - 11s for TIMELESS POWER
        // (2026-10-06).
        List<Lender> first = new ArrayList<>();
        if (id != null && own != SRC_DATABASE) first.add(Lender.database(gen, id, dir));
        if (id != null && own != SRC_HUB && TtmlHub.kindOf(dir) != null) {
            first.add(Lender.hub(ctx, id, dir));
        }
        List<OnlineLyrics.Src> rest = new ArrayList<>();
        if (q != null) {
            // Not LrcLib: a plain LRC, never with a translation or a romanisation in it.
            for (OnlineLyrics.Src s : OnlineLyrics.order(pkg)) {
                if (OnlineLyrics.sourceOf(s) != own && s != OnlineLyrics.Src.LRCLIB) rest.add(s);
            }
            for (int i = 0; i < 2 && !rest.isEmpty(); i++) {
                first.add(Lender.catalogue(rest.remove(0), q));
            }
        }
        Borrowed b = new Borrowed(r.lines);
        lendAll(gen, first, b, want);
        // The rest only for a song none of those placed. One that placed it and had nothing to
        // lend is a song with none to be had: the catalogues after it are no likelier to.
        if ((want[0] || want[1]) && !b.placed) {
            // By name the song was not there, or not this song: Apple renames Japanese songs
            // into romaji and Chinese for its storefront here. Its own words are in hand, so they
            // are asked instead (NcmLyrics.byLyric) - the line of ours most worth searching for.
            String line = own == SRC_NETEASE ? null : searchLine(r.lines);
            if (line != null && !superseded(gen)) {
                NcmLyrics.Found f = NcmLyrics.byLyric(line, q == null ? 0L : q.durationMs);
                if (f != null) {
                    b.lend(LyricParse.parse(f.body, f.translation, f.roma),
                            "NetEase " + f.id + " found by the line \"" + line + "\"", want);
                }
            }
        }
        for (int i = 0; i < rest.size() && (want[0] || want[1]) && !b.placed
                && !superseded(gen); i++) {
            Lender l = Lender.catalogue(rest.get(i), q);
            l.run();
            b.lend(l.lines, l.why, want);
        }
        if (superseded(gen)) return;
        if (b.lines == r.lines) {
            Xp.log("[MCLyric] " + pkg + ": nothing to borrow: " + b.why);
            return;
        }
        String why = r.why + " + " + b.why;
        Xp.log("[MCLyric] " + pkg + " -> " + why);
        onMain(cb, b.lines, why, r.source);
    }

    /** The lines as borrowing has left them so far, and the account of what each lender gave. */
    private static final class Borrowed {
        List<LyricLine> lines;
        String why = null;
        /** Some lender had this song, whether or not it had what was wanted. */
        boolean placed;

        Borrowed(List<LyricLine> lines) {
            this.lines = lines;
        }

        /** What `other` has of what is still wanted, taken; want is crossed off as it is. */
        void lend(List<LyricLine> other, String from, boolean[] want) {
            if (other == null || other.isEmpty() || !(want[0] || want[1])) return;
            LyricParse.Lent l = LyricParse.borrow(lines, other, want[0], want[1]);
            placed |= l.matched;
            String got = l.translations > 0 && l.romas > 0 ? "translation and romanisation"
                    : l.translations > 0 ? "translation" : l.romas > 0 ? "romanisation" : null;
            why = join(why, got == null ? "not from " + from + " (" + l.why + ")"
                    : got + " from " + from + " (" + l.why + ")");
            if (got == null) return;
            lines = l.lines;
            if (l.translations > 0) want[0] = false;
            if (l.romas > 0) want[1] = false;
        }
    }

    /**
     * The first lenders, all asked at once and lent from in their order: each is waited for
     * while it is the best one left, so a slow database still beats a quick catalogue, and the
     * wait ends as soon as nothing more is wanted.
     */
    private static void lendAll(int gen, final List<Lender> lenders, Borrowed b, boolean[] want) {
        if (lenders.isEmpty()) return;
        final BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
        for (int i = 0; i < lenders.size(); i++) {
            final int slot = i;
            Gen.thread("MCLyricLend", new Runnable() {
                @Override
                public void run() {
                    try {
                        lenders.get(slot).run();
                    } finally {
                        done.offer(slot);
                    }
                }
            }, () -> done.offer(slot));
        }
        boolean[] finished = new boolean[lenders.size()];
        int next = 0;
        long deadline = android.os.SystemClock.uptimeMillis() + RACE_BUDGET_MS;
        while (next < lenders.size() && (want[0] || want[1])) {
            if (finished[next]) {
                if (superseded(gen)) return;
                Lender l = lenders.get(next++);
                b.lend(l.lines, l.why, want);
                continue;
            }
            long left = deadline - android.os.SystemClock.uptimeMillis();
            Integer slot = null;
            try {
                if (left > 0) slot = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (slot == null) {
                // Out of time for the one in line: the ones after it that did answer still lend.
                for (int i = next + 1; i < lenders.size(); i++) {
                    if (finished[i] && (want[0] || want[1])) {
                        b.lend(lenders.get(i).lines, lenders.get(i).why, want);
                    }
                }
                return;
            }
            finished[slot] = true;
        }
    }

    /** One copy of the song to borrow from, fetched when run. */
    private abstract static class Lender {
        List<LyricLine> lines;
        String why = "nothing";

        abstract void fetch();

        final void run() {
            try {
                fetch();
            } catch (Throwable t) {
                why = "error: " + t;
            }
        }

        static Lender database(final int gen, final String id, final String dir) {
            return new Lender() {
                @Override
                void fetch() {
                    Rows r = new Rows();
                    LyricSource.database(gen, id, dir, r);
                    lines = r.lines;
                    why = r.why;
                }
            };
        }

        static Lender hub(final android.content.Context ctx, final String id, final String dir) {
            return new Lender() {
                @Override
                void fetch() {
                    Rows r = new Rows();
                    LyricSource.hub(ctx, id, dir, r);
                    lines = r.lines;
                    why = r.why;
                }
            };
        }

        static Lender catalogue(final OnlineLyrics.Src src, final NcmLyrics.Query q) {
            return new Lender() {
                @Override
                void fetch() {
                    OnlineLyrics.Found f = OnlineLyrics.ask(src, q);
                    why = f == null ? "no match on " + src : f.who() + " " + f.id;
                    lines = f == null ? null : LyricParse.parse(f.body, f.translation, f.roma);
                }
            };
        }
    }

    /**
     * The line of a lyric most worth searching the words for: the longest of its first two
     * thirds, where the verses are rather than the credits or the repeated chorus at the end,
     * and at least eight letters long, or null.
     */
    private static String searchLine(List<LyricLine> lines) {
        String best = null;
        int bestLen = 7;
        int end = Math.max(1, lines.size() * 2 / 3);
        for (int i = 0; i < end && i < lines.size(); i++) {
            String t = lines.get(i).text.trim();
            int len = 0;
            for (int k = 0; k < t.length(); k++) {
                if (Character.isLetterOrDigit(t.charAt(k))) len++;
            }
            if (len > bestLen) {
                bestLen = len;
                best = t;
            }
        }
        return best;
    }

    /** Lines and the one-line account of where they came from, filled in by one source. */
    private static final class Rows {
        List<LyricLine> lines = java.util.Collections.emptyList();
        String why = "nothing to read";
        int source = SRC_NONE;
    }

    /** The whole lookup, including the mirrors and a miss. Nothing is waited for past this. */
    private static final long RACE_BUDGET_MS = 8000L;

    /**
     * How long NetEase's answer waits for the database's, once it has one of its own.
     *
     * The database is the better of the two when it has the song - its files are hand-checked and
     * carry things NetEase's do not, like which voice sings which line - so it is worth a short
     * pause to let it win. Short, because it usually does not have the song: measured here a hit
     * from jsdelivr lands in ~450ms against NetEase's ~320ms, so a few hundred milliseconds is
     * enough to prefer it when it is there, and nothing like long enough to wait out a miss.
     */
    private static final long DATABASE_GRACE_MS = 400L;

    /**
     * Both routes at once, the better one preferred but not waited out.
     *
     * These used to run in order, and that is what made the lyrics late: a player that publishes
     * an id - Apple Music does - spent the database's full miss before the fallback was even
     * started, and the fallback is the one that actually had the song. Run together, a miss on
     * one costs nothing on the other.
     */
    private static void race(final int gen, final String pkg, final android.content.Context ctx,
                             final String id, final String dir,
                             final NcmLyrics.Query q, Rows out) {
        final Rows db = new Rows();
        final Rows ncm = new Rows();
        final Rows hub = new Rows();
        // Tags rather than the rows themselves: a row says nothing about which route produced it
        // once that route has come up empty, and "which one just finished" is the whole question.
        final BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
        int pending = 0;
        if (id != null) {
            pending++;
            Gen.thread("MCLyricDb", new Runnable() {
                @Override
                public void run() {
                    try {
                        database(gen, id, dir, db);
                    } finally {
                        done.offer(1);
                    }
                }
            }, () -> done.offer(1));
        }
        // The TTML Hub beside the database: the same kind of file, keyed by the same ids.
        if (id != null && TtmlHub.kindOf(dir) != null) {
            pending++;
            Gen.thread("MCLyricHub", new Runnable() {
                @Override
                public void run() {
                    try {
                        hub(ctx, id, dir, hub);
                    } finally {
                        done.offer(3);
                    }
                }
            }, () -> done.offer(3));
        }
        if (q != null) {
            pending++;
            Gen.thread("MCLyricNcm", new Runnable() {
                @Override
                public void run() {
                    try {
                        online(pkg, q, ncm);
                    } finally {
                        done.offer(2);
                    }
                }
            }, () -> done.offer(2));
        }
        boolean haveNcm = false;
        long deadline = android.os.SystemClock.uptimeMillis() + RACE_BUDGET_MS;
        while (pending > 0) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                break;
            }
            Integer tag;
            try {
                tag = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (tag == null) {
                break;
            }
            pending--;
            if (tag == 1 && !db.lines.isEmpty()) {
                out.lines = db.lines;
                out.why = join(out.why, db.why);
                out.source = db.source;
                return;
            }
            if (tag == 3 && !hub.lines.isEmpty()) {
                out.lines = hub.lines;
                out.why = join(out.why, hub.why);
                out.source = hub.source;
                return;
            }
            if (tag == 2 && !ncm.lines.isEmpty()) {
                haveNcm = true;
                // Hold the answer briefly in case the database is about to beat it on quality.
                long grace = android.os.SystemClock.uptimeMillis() + DATABASE_GRACE_MS;
                if (grace < deadline) {
                    deadline = grace;
                }
            }
        }
        if (haveNcm) {
            out.lines = ncm.lines;
            out.why = join(out.why, ncm.why);
            out.source = ncm.source;
            return;
        }
        // Neither had it. Both accounts are worth keeping - which one failed and how is the
        // first thing asked of a song that showed no lyrics.
        String both = join(join(db.why == null || id == null ? null : db.why,
                hub.why == null || id == null ? null : hub.why), q == null ? null : ncm.why);
        out.why = join(out.why, both == null ? "nothing found" : both);
    }

    /**
     * The lyric the session is already carrying - the player's own, or a provider module's.
     *
     * The best of the three when it is there: no network, no matching, and for a local-file
     * player it is the file's own lyric, which is the only thing that can be right for music no
     * online catalogue has.
     */
    private static void session(String info, Rows r) {
        try {
            String text = textOfLyricInfo(info);
            String raw = rawOfLyricInfo(info);
            String doc = documentOfLyricInfo(info);
            if (text == null && raw == null && doc == null) {
                // The field is there and empty, which is what a provider module publishes while
                // it is still fetching - MeiLoX writes {"lyric":"","noLyric":true}.
                r.why = "the session's lyricInfo is empty";
                return;
            }
            // "No lyrics for this song", timed and published as though it were the song. Caught
            // before the parse rather than after it because what makes it a placeholder is its
            // text, which the parsed rows keep but the account of them does not. Falls through
            // to the catalogues, which is where this song's lyric actually is.
            if (doc == null && placeholder(raw != null ? raw : text)) {
                r.why = "the session's lyricInfo says there are no lyrics";
                return;
            }
            // The translation the payload came with, if it named it one of the eight ways a
            // payload can. Joined to the lines by time, the same as NetEase's is.
            String tr = translationOfLyricInfo(info);
            // A whole-document form, when the payload carries one. Only our own Apple Music
            // hook writes this today, and only for a duet, because it is the one thing the flat
            // fields cannot say: LRC has no notion of who is singing, so a two-voice lyric read
            // out of "lyric" or "rawLyric" comes back as one voice against one edge. Tried
            // first and allowed to fail - if the document does not parse, the same lyric is
            // still sitting in the fields below.
            String used = "lyric";
            if (doc != null) {
                List<LyricLine> d = LyricParse.parse(doc, tr);
                if (!d.isEmpty()) {
                    r.lines = d;
                    used = "ttml";
                } else {
                    Xp.log("[MCLyric] the payload's ttml parsed to nothing; using its fields");
                }
            }
            List<LyricLine> rawLines = java.util.Collections.emptyList();
            if (r.lines.isEmpty() && raw != null) {
                rawLines = LyricParse.parse(raw, tr);
                for (LyricLine l : rawLines) {
                    if (l.hasWords()) {
                        r.lines = rawLines;
                        used = "rawLyric";
                        break;
                    }
                }
            }
            if (r.lines.isEmpty() && text != null) {
                r.lines = LyricParse.parse(text, tr);
            }
            // A rawLyric with no word timing is still a lyric when there is no lyric field to
            // prefer it to - which is how the metadata keys arrive (infoFor).
            if (r.lines.isEmpty() && !rawLines.isEmpty()) {
                r.lines = rawLines;
                used = "rawLyric";
            }
            if (r.lines.isEmpty()) {
                r.why = "lyricInfo parsed to nothing";
                return;
            }
            // The counted check again, now that the payload has actually been parsed - which is
            // the only way to reach a rawLyric's line count, and the only thing that can speak
            // for a format whose timings are not LRC's. Rejected rather than shown, and rejected
            // by falling through: a song whose session says one line is a song the two network
            // routes have never been asked about, and they are where its lyric actually is.
            if (r.lines.size() < MIN_SESSION_LINES) {
                r.lines = java.util.Collections.emptyList();
                r.why = "the session is publishing one line, not a lyric";
                return;
            }
            r.source = SRC_LYRIC_INFO;
            r.why = r.lines.size() + " lines from the session's own lyricInfo (" + used
                    + (tr != null ? " + translation" : "") + ")";
        } catch (Throwable t) {
            Xp.w("[MCLyric] lyricInfo parse failed: " + t);
            r.lines = java.util.Collections.emptyList();
            r.why = "lyricInfo parse error";
        }
    }

    /**
     * The Lyricon bridge's copy, if a LyricProvider plugin is publishing one for this track.
     *
     * Nothing about this route is asked for: the subscriber holds whatever the active player
     * last published, so this is a read of something already in memory - no network, no parse,
     * no waiting. A device with no bridge installed never connects and this always answers
     * nothing, which is why it can sit in the waterfall unconditionally.
     */
    private static void lyricon(MediaController c, Rows r) {
        String before = r.why;
        try {
            MediaMetadata md = c == null ? null : c.getMetadata();
            String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            List<LyricLine> lines = LyriconSource.linesFor(title, artist,
                    c == null ? null : c.getPackageName());
            if (lines == null || lines.isEmpty()) {
                return;
            }
            r.lines = lines;
            r.source = SRC_LYRICON;
            r.why = lines.size() + " lines from the Lyricon bridge";
        } catch (Throwable t) {
            Xp.w("[MCLyric] the Lyricon bridge failed: " + t);
            r.why = join(before, "Lyricon error");
        }
    }

    /**
     * The lyric shipped with the file being played, when the session is playing a file.
     *
     * A miss - a file with no lyric, a name that could not be matched - leaves r exactly as it
     * was, so the sources after it are still reached by a song that has none.
     */
    private static void local(android.content.Context ctx, MediaController c, Rows r) {
        String before = r.why;
        try {
            LocalLyrics.Found f = LocalLyrics.load(ctx, c);
            if (f == null) {
                return;
            }
            List<LyricLine> lines = LyricParse.parse(f.body);
            if (lines.isEmpty()) {
                r.why = join(before, "the local lyric parsed to nothing " + shape(f.body));
                return;
            }
            r.lines = lines;
            r.source = SRC_LOCAL;
            r.why = lines.size() + " lines from " + f.how;
        } catch (Throwable t) {
            Xp.w("[MCLyric] local lyric failed: " + t);
        }
    }

    /** The AMLL database, by platform id, in the one directory that id can belong to. */
    private static void database(int gen, String id, String dir, Rows r) {
        String before = r.why;
        // A lookup can be overtaken before its thread even runs. Said plainly rather than left
        // to come out as "could not reach the database", which is what the mirrors will report
        // for a request one() has declined to send and is the wrong thing to read in a log.
        if (superseded(gen)) {
            r.why = join(before, "the track changed before the database was asked");
            return;
        }
        try {
            // What a prefetch already went and got, when the track that just started is one the
            // queue saw coming. Nothing else changes: a miss here is an ordinary lookup.
            Answer warm = warmed(dir, id);
            if (warm != null) Xp.log("[MCLyric] " + dir + "/" + id + " was already fetched");
            Answer a = warm != null ? warm : fetch(gen, dir, id);
            if (a.status != FOUND) {
                r.why = join(before, a.status == MISSING
                        ? "not in " + dir + " (" + id + ")"
                        : "could not reach the database");
                return;
            }
            r.lines = LyricParse.parse(a.body);
            r.why = r.lines.isEmpty()
                    ? join(before, "parsed to nothing (" + dir + ") " + shape(a.body))
                    : r.lines.size() + " lines from " + dir;
            if (!r.lines.isEmpty()) r.source = SRC_DATABASE;
        } catch (Throwable t) {
            Xp.w("[MCLyric] database lookup failed: " + t);
            r.why = join(before, "database error");
        }
    }

    /** LunaBeat's TTML Hub, by the same id the database is asked for. */
    private static void hub(android.content.Context ctx, String id, String dir, Rows r) {
        try {
            String ttml = TtmlHub.lookup(ctx, dir, id);
            if (ttml == null) {
                r.why = "not in the TTML Hub";
                return;
            }
            r.lines = LyricParse.parse(ttml);
            r.why = r.lines.isEmpty() ? "the TTML Hub's file parsed to nothing"
                    : r.lines.size() + " lines from the TTML Hub";
            if (!r.lines.isEmpty()) r.source = SRC_HUB;
        } catch (Throwable t) {
            Xp.w("[MCLyric] TTML Hub lookup failed: " + t);
            r.why = "TTML Hub error";
        }
    }

    /**
     * By name, from the first two catalogues in this player's order - QQ Music and NetEase for
     * most players. See OnlineLyrics.
     */
    private static void online(String pkg, NcmLyrics.Query q, Rows r) {
        String before = r.why;
        try {
            OnlineLyrics.Found f = OnlineLyrics.first(pkg, q);
            if (f == null) {
                r.why = join(before, "no match on " + OnlineLyrics.describe(pkg).split(">")[0]
                        + " or " + OnlineLyrics.describe(pkg).split(">")[1]);
                return;
            }
            take(f, r, before);
        } catch (Throwable t) {
            Xp.w("[MCLyric] the by-name lookup failed: " + t);
            r.why = join(before, "by-name error");
        }
    }

    /** The rest of the order - Kuwo, KuGou, LrcLib for most players - one at a time. */
    private static void web(String pkg, NcmLyrics.Query q, Rows r) {
        String before = r.why;
        try {
            OnlineLyrics.Found f = OnlineLyrics.rest(pkg, q);
            if (f == null) {
                r.why = join(before, "no match on the rest of " + OnlineLyrics.describe(pkg));
                return;
            }
            take(f, r, before);
        } catch (Throwable t) {
            Xp.w("[MCLyric] the second net failed: " + t);
            r.why = join(before, "second net error");
        }
    }

    private static void take(OnlineLyrics.Found f, Rows r, String before) {
        r.lines = LyricParse.parse(f.body, f.translation, f.roma);
        r.why = r.lines.isEmpty()
                ? join(before, f.who() + " " + f.id + " parsed to nothing " + shape(f.body))
                : r.lines.size() + " lines from " + f.who() + " " + f.id
                + " (" + (f.words ? "word-timed" : "line-timed")
                + (f.translation != null ? " + translation" : "")
                + (f.roma != null ? " + romanisation" : "") + ")";
        if (!r.lines.isEmpty()) r.source = f.source();
    }

    /** Both halves of why it failed, when more than one source was asked and all came up empty. */
    private static String join(String before, String now) {
        if (before == null || "nothing to read".equals(before)) {
            return now;
        }
        return now == null ? before : before + "; " + now;
    }

    private static final int SHAPE_HEAD = 60;

    /**
     * The shape of a body that parsed to nothing: how long it is, and what it starts with.
     *
     * "Parsed to nothing" alone is the one account here that cannot be acted on - it says the
     * parser refused without saying what it refused, and the parser is never where the fault is.
     * Every other line in this file says which route failed and how, on the argument that that is
     * the first thing asked of a song showing no lyrics; this one was the exception and it cost
     * an afternoon. The body behind it for 带你飞 was the four characters "null", handed over in
     * place of the song by a platform quirk (see NcmLyrics.str), and nothing in the account could
     * say so. Quoted and kept to one line, because the probe prints the whole account inline.
     */
    private static String shape(String body) {
        if (body == null) {
            return "(no body)";
        }
        String head = body.length() > SHAPE_HEAD ? body.substring(0, SHAPE_HEAD) + "..." : body;
        return body.length() + " chars, head=\"" + head.replace("\n", "\\n").replace("\r", "")
                + '"';
    }

    /**
     * The same thing for an id that came from somewhere other than a session - which is how the
     * effect gets tested without depending on either the network picking a song we have, or the
     * playing app publishing anything.
     */
    static void loadById(final String id, final boolean apple, final Callback cb) {
        load(id, apple ? "am-lyrics" : "ncm-lyrics", "id " + id, cb);
    }

    private static void load(final String id, final String dir, final String who,
                             final Callback cb) {
        final int gen = ++sLoadGen;
        Gen.thread("MCLyricSource", new Runnable() {
            @Override
            public void run() {
                Rows r = new Rows();
                database(gen, id, dir, r);
                Xp.log("[MCLyric] " + who + " id=" + id + " -> " + r.why);
                onMain(cb, r.lines, r.why, r.source);
            }
        });
    }

    private static void onMain(final Callback cb, final List<LyricLine> lines,
                               final String why, final int source) {
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                cb.onLines(lines, why, source);
            }
        });
    }

    /** What the mirrors said about one directory. Every mirror is asked at once. */
    static Answer fetch(final int gen, String dir, String id) {
        final BlockingQueue<Answer> answers = new LinkedBlockingQueue<>();
        for (final String[] m : MIRRORS) {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    answers.offer(one(gen, m, dir, id));
                }
            });
        }
        boolean sawMissing = false;
        // The budget is per directory and generous only relative to the per-request timeouts,
        // which are what actually bound this: whichever mirror answers first ends it, and four
        // that cannot be reached end it at the last of them.
        long deadline = android.os.SystemClock.uptimeMillis() + 7000L;
        while (true) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                break;
            }
            Answer a;
            try {
                a = answers.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (a == null) {
                break;
            }
            if (a.status == FOUND) {
                return a;
            }
            if (a.status == MISSING) {
                sawMissing = true;
            }
        }
        // Distinguishing the two is what keeps a wrong directory from looking like a dead
        // network: a 404 means this file is not in this directory and the next one is worth
        // asking, where a timeout means asking again will only cost another timeout.
        return new Answer(sawMissing ? MISSING : UNREACHABLE, null);
    }

    private static Answer one(int gen, String[] mirror, String dir, String id) {
        // Checked here rather than before the pool took the work, because here is where a queued
        // request has been waiting: the track may well have changed between being submitted and
        // reaching a thread, and going out anyway would hold that thread for the full timeout on
        // behalf of a song that stopped playing. Nothing is logged - the request never happened,
        // and a burst of skips would otherwise put four of these in the log per track.
        if (superseded(gen)) {
            return new Answer(UNREACHABLE, null);
        }
        String url = String.format(mirror[1], dir, id);
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // Short on purpose. A lyric that arrives after the lock screen has been put away is
            // worth nothing, and four mirrors running at once means the slow one is never waited
            // for anyway - these bound the failure, not the success.
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            int code = conn.getResponseCode();
            long ms = android.os.SystemClock.uptimeMillis() - started;
            if (code == 404) {
                Xp.log("[MCLyric] " + mirror[0] + " -> 404 in " + ms + "ms (" + dir + ")");
                return new Answer(MISSING, null);
            }
            if (code != 200) {
                Xp.log("[MCLyric] " + mirror[0] + " -> HTTP " + code + " in " + ms + "ms");
                return new Answer(UNREACHABLE, null);
            }
            String body = read(conn.getInputStream());
            Xp.log("[MCLyric] " + mirror[0] + " -> 200, " + body.length() + " chars in " + ms
                    + "ms (" + dir + "/" + id + ")");
            return new Answer(FOUND, body);
        } catch (Throwable t) {
            Xp.w("[MCLyric] " + mirror[0] + " failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            return new Answer(UNREACHABLE, null);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Every key the session is publishing, with enough of each value to recognise it.
     *
     * Which players this feature covers is decided entirely by what they put on the session, and
     * that cannot be reasoned about - only read. Players differ in whether they publish lyrics at
     * all, under `lyricInfo` or a key of their own, whether MEDIA_ID is the platform's id or an
     * internal uri, and whether the title is the song or (for one local player) the current lyric
     * line. So this lists the keys rather than looking for the ones already known: a key nobody
     * here has heard of is exactly what widening the coverage needs to find.
     *
     * Values are cut short and bitmaps are reduced to their size, because a lyric payload is tens
     * of kilobytes and the point is to see WHICH keys carry something, not to read the words.
     */
    // Bundle.get is deprecated in favour of the typed getters, which is exactly what this cannot
    // use: the point is to print a key whose type nobody here knows yet.
    public @SuppressWarnings("deprecation")
    static String dumpMetadata(MediaController c) {
        if (c == null) return "no session being watched";
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return c.getPackageName() + ": getMetadata threw " + t;
        }
        if (md == null) return c.getPackageName() + ": no metadata";
        StringBuilder sb = new StringBuilder(c.getPackageName());
        sb.append('\n');
        java.util.Set<String> keys;
        try {
            keys = md.keySet();
        } catch (Throwable t) {
            return sb.append("keySet threw ").append(t).toString();
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        for (String k : sorted) {
            sb.append("  ").append(k).append(" = ").append(valueOf(md, k)).append('\n');
        }
        // The session's extras, which are a second place entirely - a Bundle the player sets on
        // the session rather than on the metadata. Nothing found here yet, but "we never looked"
        // and "there is nothing there" are different answers and this is the one worth having:
        // a player that puts its lyrics here would otherwise look identical to one publishing
        // nothing at all.
        try {
            android.os.Bundle ex = c.getExtras();
            if (ex == null || ex.isEmpty()) {
                sb.append("  (session extras: none)\n");
            } else {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    String s = v == null ? "null" : v.toString();
                    sb.append("  extras.").append(k).append(" = ")
                            .append(s.length() > 160 ? s.substring(0, 160) + "..." : s)
                            .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  (session extras threw ").append(t).append(")\n");
        }
        sb.append("  -> id=").append(idOf(c))
                .append(" lyricInfo=").append(lyricInfoOf(c) == null ? "no" : "yes")
                .append("\n  -> by name: ").append(NcmLyrics.queryOf(c));
        return sb.toString();
    }

    /** One metadata value, short enough to read and typed enough to act on. */
    private static String valueOf(MediaMetadata md, String k) {
        try {
            android.graphics.Bitmap b = md.getBitmap(k);
            if (b != null) return "Bitmap[" + b.getWidth() + "x" + b.getHeight() + "]";
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cs = md.getText(k);
            if (cs != null) {
                String s = cs.toString();
                String cut = s.length() > 160 ? s.substring(0, 160) + "..." : s;
                // Newlines would break the one-key-per-line shape a reader relies on.
                return "(" + s.length() + " chars) " + cut.replace("\n", "\\n");
            }
        } catch (Throwable ignored) {
        }
        try {
            long l = md.getLong(k);
            if (l != 0L) return String.valueOf(l);
        } catch (Throwable ignored) {
        }
        return "(empty or of another type)";
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
