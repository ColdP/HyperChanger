// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;

/**
 * The lyric that ships with the file being played.
 *
 * Every other route in this module asks somebody else what this song's words are - the player,
 * a provider module, a catalogue keyed by an id or by a name. For music sitting on the phone
 * none of that is necessary and none of it is as good: the file itself carries the lyric, it is
 * the lyric the person chose to keep with it, and reading it cannot pick the wrong song the way
 * a search can.
 *
 * Measured 2026-09-22 on this device, over Salt Player's library: 2020 audio files, 1903 of them
 * with a `.lrc` of the same name beside them, and the ones without - 音频怪物 - 典狱司 among them
 * - carrying a timed lyric inside the file instead, as an ID3 USLT frame. So both forms are read,
 * and between them there is very little left for the network to do.
 *
 * SystemUI can read all of it without asking: MANAGE_EXTERNAL_STORAGE and READ_EXTERNAL_STORAGE
 * are both granted and SYSTEM_FIXED, so this is an ordinary file read from an ordinary path.
 */
public final class LocalLyrics {

    private LocalLyrics() {
    }

    /** A timed lyric out of a file on this phone, and where it was found. */
    static final class Found {
        final String body;
        /** "a .lrc beside it", "the file's own ID3 tag", "the file's own FLAC tag". */
        final String how;
        final String path;

        Found(String body, String how, String path) {
            this.body = body;
            this.how = how;
            this.path = path;
        }
    }

    /**
     * The lyric for whatever this session is playing, if it is playing a file we can find.
     *
     * Null for a streaming player, which is the normal answer: nothing about this route is Salt
     * Player's, but a session playing from the network has no file here to match and the
     * matching is written to say no rather than to guess.
     */
    static Found load(Context ctx, MediaController c) {
        if (ctx == null || c == null) {
            return null;
        }
        try {
            String path = pathOf(ctx, c);
            if (path == null) {
                return null;
            }
            Found f = sidecar(path);
            if (f == null) {
                f = embedded(path);
            }
            return f;
        } catch (Throwable t) {
            Xp.w("[MCLocal] lookup failed: " + t);
            return null;
        }
    }

    /**
     * Every step of the lookup, for `op local`.
     *
     * This route fails silently by design - a miss leaves the better answer alone - so without
     * this there is nothing to read when it finds nothing and should have. Each stage reports
     * what it was given as well as what it made of it, because most of the ways this can go
     * wrong are a field arriving empty rather than a decision going the wrong way.
     */
    public static String describe(Context ctx, MediaController c) {
        StringBuilder sb = new StringBuilder();
        sb.append("ctx=").append(ctx == null ? "null" : "ok");
        sb.append(" session=").append(c == null ? "null" : c.getPackageName());
        if (ctx == null || c == null) {
            return sb.toString();
        }
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return sb.append(" getMetadata threw ").append(t).toString();
        }
        if (md == null) {
            return sb.append(" no metadata").toString();
        }
        long dur = 0L;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
        }
        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String tail = artistTail(str(md, MediaMetadata.METADATA_KEY_ARTIST));
        sb.append(" dur=").append(dur);
        sb.append(" title=\"").append(title).append("\"->\"").append(NcmLyrics.norm(title));
        sb.append("\" tail=\"").append(tail).append("\"->\"").append(NcmLyrics.norm(tail))
                .append('"');

        Cursor cur = null;
        int rows = 0;
        int named = 0;
        StringBuilder near = new StringBuilder();
        try {
            cur = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.TITLE},
                    MediaStore.Audio.Media.DURATION + ">? AND "
                            + MediaStore.Audio.Media.DURATION + "<?",
                    new String[]{String.valueOf(dur - DURATION_SLACK_MS),
                            String.valueOf(dur + DURATION_SLACK_MS)}, null);
            if (cur == null) {
                return sb.append(" query returned null").toString();
            }
            String a = NcmLyrics.norm(title);
            String b = NcmLyrics.norm(tail);
            // The nearest few by duration, kept as they go past rather than collected and
            // sorted: printing the first few in cursor order is what hid the bug this probe was
            // written to find, and the whole question here is what sits closest to the target.
            final int show = 6;
            long[] diffs = new long[show];
            String[] lines = new String[show];
            java.util.Arrays.fill(diffs, Long.MAX_VALUE);
            while (cur.moveToNext() && rows < MAX_CANDIDATES) {
                rows++;
                String data = cur.getString(0);
                boolean ok = data != null && named(a, b, cur.getString(2), data);
                if (ok) {
                    named++;
                }
                long d = cur.getLong(1);
                long diff = Math.abs(d - dur);
                String line = "\n    " + (ok ? "MATCH " : "      ") + d + " ("
                        + (d - dur) + "ms) " + (data == null ? "?" : baseName(data))
                        + " [tag=" + cur.getString(2) + "]";
                for (int i = 0; i < show; i++) {
                    if (diff < diffs[i]) {
                        for (int j = show - 1; j > i; j--) {
                            diffs[j] = diffs[j - 1];
                            lines[j] = lines[j - 1];
                        }
                        diffs[i] = diff;
                        lines[i] = line;
                        break;
                    }
                }
            }
            for (int i = 0; i < show; i++) {
                if (lines[i] != null) {
                    near.append(lines[i]);
                }
            }
        } catch (Throwable t) {
            return sb.append(" query threw ").append(t).toString();
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable ignored) {
                }
            }
        }
        sb.append("\n  candidates=").append(rows).append(" named=").append(named)
                .append(" (nearest first)").append(near);

        String path = pathOf(ctx, c);
        sb.append("\n  picked=").append(path);
        if (path == null) {
            return sb.toString();
        }
        Found f = sidecar(path);
        sb.append("\n  sidecar=").append(f == null ? "none" : f.path);
        if (f == null) {
            f = embedded(path);
            sb.append(" embedded=").append(f == null ? "none" : f.how);
        }
        if (f != null) {
            sb.append("\n  -> ").append(LyricParse.parse(f.body).size()).append(" lines from ")
                    .append(f.how);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- finding the file

    /**
     * How far MediaStore's idea of a file's length may sit from the player's.
     *
     * They are reading the same file and still disagree: measured 2026-09-22, Salt reported
     * 245708ms for 典狱司 where MediaStore had 245734ms, 26ms apart, because the two of them
     * count a trailing frame differently. The window only has to cover that - it is not there to
     * let a different recording in, and the name check below is what actually decides.
     */
    private static final long DURATION_SLACK_MS = 1200L;

    /**
     * A ceiling on the walk, not a shortlist.
     *
     * It was 40, and the first thing it did was lose the song it was written for: MediaStore
     * hands rows back in its own order - by row id, which is roughly the order the files were
     * scanned - so the first 40 rows inside the window are 40 arbitrary songs of about the right
     * length, and 典狱司, 26ms from the target, was not among them. A window is not a ranking.
     * Every row inside it is looked at now and the closest match wins; this only stops a
     * pathological library from being walked forever.
     */
    private static final int MAX_CANDIDATES = 2000;

    /**
     * The file this session is playing, or null when nothing here is confidently it.
     *
     * Duration narrows, the name decides. Duration alone would be a trap: a streaming player is
     * always playing something 245 seconds long and this library always has something 245
     * seconds long, and handing back that file's lyric would put a wrong lyric on screen with
     * every appearance of a right one. So a candidate has to agree by name as well, and if none
     * does, this says so.
     */
    private static String pathOf(Context ctx, MediaController c) {
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return null;
        }
        if (md == null) {
            return null;
        }
        long dur = 0L;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
        }
        if (dur <= 0L) {
            // Without a length there is nothing to narrow the library down with, and a name on
            // its own matches every cover version in it.
            return null;
        }
        // Both readings of "what is this song called", because the players disagree about which
        // field holds it. Salt Player writes the line being sung into TITLE and puts
        // "歌手 - 歌名" in ARTIST, so on that player the tail is the only true name of the two.
        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String tail = artistTail(str(md, MediaMetadata.METADATA_KEY_ARTIST));
        String a = NcmLyrics.norm(title);
        String b = NcmLyrics.norm(tail);
        if (a.isEmpty() && b.isEmpty()) {
            return null;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TITLE};
        String where = MediaStore.Audio.Media.DURATION + ">? AND "
                + MediaStore.Audio.Media.DURATION + "<?";
        String[] args = {String.valueOf(dur - DURATION_SLACK_MS),
                String.valueOf(dur + DURATION_SLACK_MS)};

        Cursor cur = null;
        String best = null;
        long bestDiff = Long.MAX_VALUE;
        try {
            cur = ctx.getContentResolver().query(uri, cols, where, args, null);
            if (cur == null) {
                return null;
            }
            int n = 0;
            while (cur.moveToNext() && n < MAX_CANDIDATES) {
                n++;
                String data = cur.getString(0);
                if (data == null || data.isEmpty()) {
                    continue;
                }
                long d = cur.getLong(1);
                if (!named(a, b, cur.getString(2), data)) {
                    continue;
                }
                long diff = Math.abs(d - dur);
                if (diff < bestDiff) {
                    best = data;
                    bestDiff = diff;
                }
            }
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (best != null) {
            Xp.log("[MCLocal] " + best + " (" + bestDiff + "ms off)");
        }
        return best;
    }

    /**
     * Whether a candidate row is the song the session named.
     *
     * Two names are offered and either may be the real one, so either matching is a match. The
     * file name is tested as well as the tag: this library is full of files called
     * "音频怪物 - 典狱司.MP3" whose ID3 title is the same string, and full of others whose tags
     * were never filled in at all, where the name on disk is all there is.
     */
    private static boolean named(String a, String b, String tagTitle, String path) {
        String tag = NcmLyrics.norm(tagTitle);
        String file = NcmLyrics.norm(baseName(path));
        return holds(a, tag) || holds(a, file) || holds(b, tag) || holds(b, file);
    }

    /**
     * Containment either way, on normalised text, with a floor under it.
     *
     * The floor is what keeps this honest. Normalised names get short - a song called "1" is in
     * this library - and a one- or two-character name is inside half the file names on the disk,
     * so below three characters containment stops meaning anything and only equality is allowed.
     */
    private static boolean holds(String want, String got) {
        if (want.isEmpty() || got.isEmpty()) {
            return false;
        }
        if (want.length() < 3) {
            return want.equals(got);
        }
        return got.contains(want) || want.contains(got);
    }

    /** The file's name without its directory or its extension. */
    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** The song's name out of an "歌手 - 歌名" artist field, or null when it is not one. */
    private static String artistTail(String artist) {
        if (artist == null) {
            return null;
        }
        int dash = artist.indexOf(" - ");
        if (dash <= 0) {
            return null;
        }
        String tail = artist.substring(dash + 3).trim();
        return tail.isEmpty() ? null : tail;
    }

    private static String str(MediaMetadata md, String key) {
        try {
            String s = md.getString(key);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- reading the lyric

    /** How much of a lyric file or tag is worth reading. Larger than any real one by far. */
    private static final int MAX_LYRIC_BYTES = 512 * 1024;

    /**
     * The lyric file sitting beside the audio, if there is one.
     *
     * Tried before the tag because it is the one a person edits: a file whose embedded lyric is
     * wrong gets a .lrc dropped next to it, and that correction is the whole point of it being
     * there. Both cases of the extension are tried - this library holds .MP3 and .mp3 alike, and
     * the volume is case-insensitive in practice, but asking costs one stat.
     *
     * The richer formats first (PR #103): a TTML or a word-timed YRC/KRC/Lyricify file kept
     * beside the audio is there because its owner wanted the words timed. A KRC has to be the
     * decrypted text; one that does not parse is passed over for the next.
     *
     * Any other mix of case - "Song.Lrc", or a stem whose case differs from the audio's - is
     * found by listing the folder once, and only when the two stats per format found nothing
     * (PR #103): most songs have no file beside them, and a large folder costs more to list.
     */
    private static Found sidecar(String audio) {
        int dot = audio.lastIndexOf('.');
        String stem = dot <= 0 ? audio : audio.substring(0, dot);
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (String ext : SIDECAR_FORMATS) {
            for (String p : new String[] {stem + "." + ext, stem + "." + ext.toUpperCase()}) {
                tried.add(p);
                Found f = sidecarAt(p, ext);
                if (f != null) return f;
            }
        }
        File dir = new File(stem).getParentFile();
        String[] names = dir == null ? null : dir.list();
        if (names == null) return null;
        java.util.Arrays.sort(names);
        String base = new File(stem).getName();
        for (String ext : SIDECAR_FORMATS) {
            String want = base + "." + ext;
            for (String name : names) {
                if (!name.equalsIgnoreCase(want)) continue;
                String p = new File(dir, name).getPath();
                if (!tried.add(p)) continue;
                Found f = sidecarAt(p, ext);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The file at p as a lyric of the given format, or null when it is absent or unusable. */
    private static Found sidecarAt(String p, String ext) {
        File f = new File(p);
        if (!f.isFile() || f.length() == 0L || f.length() > MAX_LYRIC_BYTES) {
            return null;
        }
        String body = decode(readAll(f));
        return usable(body) ? new Found(body, "a ." + ext + " beside it", p) : null;
    }

    private static final String[] SIDECAR_FORMATS = {"ttml", "lrc", "yrc", "krc", "lys"};

    /** The lyric inside the audio file, for the formats that carry one where we can find it. */
    private static Found embedded(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".flac")) {
            String body = flac(path);
            return usable(body) ? new Found(body, "the file's own FLAC tag", path) : null;
        }
        String body = id3(path);
        return usable(body) ? new Found(body, "the file's own ID3 tag", path) : null;
    }

    /**
     * Whether what came out of a file is this song's lyric.
     *
     * Timings, because this view follows the singing and prose has nothing to follow. And not a
     * placeholder, which a file is as free to carry as a session is: measured 2026-09-22, the
     * first FLAC on this phone has a LYRICS tag holding exactly "[00:05.00]纯音乐，请欣赏" -
     * timed, well-formed, and not a lyric. Taken at face value it would have been worse here
     * than in the session route, because the file outranks the session: it would have replaced a
     * real lyric a provider module had published and, by not being empty, stopped the catalogues
     * being asked at all.
     *
     * Rejected per source rather than per file, so a .lrc holding a placeholder still falls
     * through to the tag inside the audio.
     *
     * Timings are judged by parsing rather than by LRC's [mm:ss] tag: TTML, YRC and Lyricify
     * carry theirs in other shapes. The cheap placeholder check goes first.
     */
    private static boolean usable(String body) {
        return body != null && !LyricSource.placeholder(body) && !LyricParse.parse(body).isEmpty();
    }

    // ---------------------------------------------------------------- ID3v2

    /**
     * The USLT frame out of an ID3v2 tag, decoded.
     *
     * Only the header is trusted to say how far the tag runs, and nothing past it is read, so a
     * file with no tag costs ten bytes. Frame sizes are read as ID3v2.4 synchsafe integers when
     * the tag says 2.4 and as plain big-endian below that - v2.3 wrote them plain, and reading a
     * v2.3 size as synchsafe walks off into the audio.
     *
     * Verified 2026-09-22 against 音频怪物 - 典狱司.MP3: USLT at offset 84, 2430 bytes,
     * encoding 01, language "XXX", body UTF-16LE with a BOM and [mm:ss.xx] timings.
     */
    private static String id3(String path) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            byte[] head = new byte[10];
            if (f.read(head) != 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
                return null;
            }
            int major = head[3] & 0xFF;
            boolean synchsafeFrames = major >= 4;
            int tagSize = synchsafe(head, 6);
            if (tagSize <= 0 || tagSize > MAX_LYRIC_BYTES * 8) {
                return null;
            }
            // An extended header, when there is one, is counted inside the tag and skipped by
            // its own length. Rare, and free to handle.
            long pos = 10;
            if ((head[5] & 0x40) != 0) {
                byte[] ext = new byte[4];
                f.seek(pos);
                if (f.read(ext) != 4) {
                    return null;
                }
                int extSize = synchsafeFrames ? synchsafe(ext, 0) : beInt(ext, 0);
                if (extSize < 0) {
                    return null;
                }
                pos += extSize;
            }
            long end = 10L + tagSize;
            byte[] fh = new byte[10];
            while (pos + 10 <= end) {
                f.seek(pos);
                if (f.read(fh) != 10) {
                    return null;
                }
                // Padding: the tag is over, the rest is zeroes up to the audio.
                if (fh[0] == 0) {
                    return null;
                }
                int size = synchsafeFrames ? synchsafe(fh, 4) : beInt(fh, 4);
                if (size <= 0 || pos + 10 + size > end) {
                    return null;
                }
                if (fh[0] == 'U' && fh[1] == 'S' && fh[2] == 'L' && fh[3] == 'T'
                        && size <= MAX_LYRIC_BYTES) {
                    byte[] body = new byte[size];
                    f.seek(pos + 10);
                    if (f.read(body) != size) {
                        return null;
                    }
                    return uslt(body);
                }
                pos += 10 + size;
            }
            return null;
        } catch (Throwable t) {
            Xp.w("[MCLocal] ID3 read failed: " + t);
            return null;
        } finally {
            close(f);
        }
    }

    /**
     * One USLT frame's text.
     *
     * Layout is an encoding byte, a three-byte language, a descriptor terminated the way that
     * encoding terminates strings, and then the lyric. The descriptor is the part worth being
     * careful with: under UTF-16 its terminator is two zero bytes on an even boundary, and
     * scanning for a single zero stops inside the first ASCII character of the text.
     */
    private static String uslt(byte[] b) {
        if (b.length < 5) {
            return null;
        }
        int enc = b[0] & 0xFF;
        int p = 4;
        boolean wide = enc == 1 || enc == 2;
        if (wide) {
            while (p + 1 < b.length && !(b[p] == 0 && b[p + 1] == 0)) {
                p += 2;
            }
            p += 2;
        } else {
            while (p < b.length && b[p] != 0) {
                p++;
            }
            p += 1;
        }
        if (p >= b.length) {
            return null;
        }
        byte[] text = new byte[b.length - p];
        System.arraycopy(b, p, text, 0, text.length);
        return decodeAs(text, enc);
    }

    private static String decodeAs(byte[] b, int enc) {
        try {
            switch (enc) {
                case 0:
                    return new String(b, "ISO-8859-1");
                case 1:
                    // With a BOM, which the JVM's "UTF-16" honours; UTF-16LE without one is the
                    // common way to get this wrong, and the BOM is what this frame carried.
                    return new String(b, "UTF-16");
                case 2:
                    return new String(b, "UTF-16BE");
                default:
                    return new String(b, "UTF-8");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** A four-byte size with the top bit of each byte dropped, which is how ID3v2.4 writes one. */
    private static int synchsafe(byte[] b, int at) {
        return ((b[at] & 0x7F) << 21) | ((b[at + 1] & 0x7F) << 14)
                | ((b[at + 2] & 0x7F) << 7) | (b[at + 3] & 0x7F);
    }

    private static int beInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    // ---------------------------------------------------------------- FLAC

    /** The tag names a lyric can be filed under in a Vorbis comment, in the order they win. */
    private static final String[] FLAC_KEYS = {"LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS",
            "SYNCEDLYRICS", "LYRICS-XXX"};

    /**
     * The lyric out of a FLAC's Vorbis comment block.
     *
     * The metadata blocks sit in a chain after "fLaC", each with a one-byte type whose top bit
     * marks the last one and a three-byte big-endian length. Only block type 4 is read, and the
     * walk stops at the audio: a FLAC's picture block is megabytes and there is no reason to
     * touch it.
     */
    private static String flac(String path) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            byte[] magic = new byte[4];
            if (f.read(magic) != 4 || magic[0] != 'f' || magic[1] != 'L'
                    || magic[2] != 'a' || magic[3] != 'C') {
                return null;
            }
            long pos = 4;
            byte[] bh = new byte[4];
            for (int guard = 0; guard < 64; guard++) {
                f.seek(pos);
                if (f.read(bh) != 4) {
                    return null;
                }
                boolean last = (bh[0] & 0x80) != 0;
                int type = bh[0] & 0x7F;
                int len = ((bh[1] & 0xFF) << 16) | ((bh[2] & 0xFF) << 8) | (bh[3] & 0xFF);
                if (len < 0) {
                    return null;
                }
                if (type == 4 && len <= MAX_LYRIC_BYTES * 2) {
                    byte[] body = new byte[len];
                    f.seek(pos + 4);
                    if (f.read(body) != len) {
                        return null;
                    }
                    String got = vorbis(body);
                    if (got != null) {
                        return got;
                    }
                }
                if (last) {
                    return null;
                }
                pos += 4 + len;
            }
            return null;
        } catch (Throwable t) {
            Xp.w("[MCLocal] FLAC read failed: " + t);
            return null;
        } finally {
            close(f);
        }
    }

    /**
     * One Vorbis comment block, read as far as a lyric.
     *
     * Everything in here is little-endian, unlike the FLAC block headers that wrap it, and every
     * comment is "NAME=value" in UTF-8 with the name case-insensitive.
     */
    private static String vorbis(byte[] b) {
        int p = 0;
        int vendor = leInt(b, p);
        if (vendor < 0) {
            return null;
        }
        p += 4 + vendor;
        if (p + 4 > b.length) {
            return null;
        }
        int count = leInt(b, p);
        p += 4;
        if (count < 0 || count > 4096) {
            return null;
        }
        String best = null;
        int bestKey = FLAC_KEYS.length;
        for (int i = 0; i < count && p + 4 <= b.length; i++) {
            int len = leInt(b, p);
            p += 4;
            if (len < 0 || p + len > b.length) {
                return best;
            }
            int eq = -1;
            for (int j = p; j < p + len; j++) {
                if (b[j] == '=') {
                    eq = j;
                    break;
                }
            }
            if (eq > p) {
                try {
                    String name = new String(b, p, eq - p, "UTF-8").toUpperCase();
                    for (int k = 0; k < bestKey; k++) {
                        if (FLAC_KEYS[k].equals(name)) {
                            best = new String(b, eq + 1, p + len - eq - 1, "UTF-8");
                            bestKey = k;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            p += len;
        }
        return best;
    }

    private static int leInt(byte[] b, int at) {
        if (at + 4 > b.length) {
            return -1;
        }
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    // ---------------------------------------------------------------- bytes

    private static byte[] readAll(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] out = new byte[(int) f.length()];
            int at = 0;
            while (at < out.length) {
                int n = in.read(out, at, out.length - at);
                if (n <= 0) {
                    break;
                }
                at += n;
            }
            if (at == out.length) {
                return out;
            }
            byte[] cut = new byte[at];
            System.arraycopy(out, 0, cut, 0, at);
            return cut;
        } catch (Throwable t) {
            Xp.log("[MCLocal] could not read " + f + ": " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * A .lrc's text, whatever it was saved as.
     *
     * A BOM settles it outright. Without one, UTF-8 is tried strictly - a strict decode of real
     * GBK text fails on the first Chinese character, which is exactly the signal needed - and
     * GBK is the fallback, because a .lrc old enough not to be UTF-8 on a Chinese phone is GBK.
     */
    private static String decode(byte[] b) {
        if (b == null || b.length == 0) {
            return null;
        }
        try {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                    && (b[2] & 0xFF) == 0xBF) {
                return new String(b, 3, b.length - 3, "UTF-8");
            }
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                return new String(b, "UTF-16");
            }
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                return new String(b, "UTF-16");
            }
            java.nio.charset.CharsetDecoder d =
                    java.nio.charset.Charset.forName("UTF-8").newDecoder();
            d.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            d.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            try {
                return d.decode(java.nio.ByteBuffer.wrap(b)).toString();
            } catch (Throwable notUtf8) {
                return new String(b, "GBK");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void close(RandomAccessFile f) {
        if (f != null) {
            try {
                f.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
