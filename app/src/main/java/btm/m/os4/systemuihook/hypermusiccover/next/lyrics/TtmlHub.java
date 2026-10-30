// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * The catalogue protocol follows HyperLyrics Enhanced (juren233/HyperLyrics-Enhanced,
 * online/source/lunabeat/LunaBeatTtmlRepository.kt), Copyright 2026 juren233, Apache-2.0 - see
 * NOTICE. Rewritten in Java.
 */
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.Http;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * LunaBeat's TTML Hub: a static catalogue of hand-timed TTML, the same kind of file the AMLL
 * database holds - word timing, duets, background vocals, translations - for songs AMLL does not
 * have. Looked up by id, like AMLL, so there is nothing to match: the songs carry their Apple
 * Music, NetEase and QQ ids.
 *
 * One manifest names the current index; the index (1.6MB, 2784 songs on 2026-09-24) lists every
 * song's ids, its file and the file's SHA-256. Both are kept on disk and the manifest is asked
 * again at most every six hours, with its ETag, so a lookup is normally no request at all until
 * the song is found. Every download is checked against its hash before it is kept or used.
 */
final class TtmlHub {

    private TtmlHub() {
    }

    private static final String TAG = "MCHub";
    private static final String BASE = "https://2755337087.github.io/ttml-hub/";
    private static final String MANIFEST = BASE + "api/v1/manifest.json";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_INDEX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TTML_BYTES = 256 * 1024;

    private static final class Entry {
        final String id;
        final String path;
        final String sha256;

        Entry(String id, String path, String sha256) {
            this.id = id;
            this.path = path;
            this.sha256 = sha256;
        }
    }

    /** "appleMusicId:1646586588" -> its file. Null until the index has been read once. */
    private static Map<String, Entry> sIndex;
    private static String sRevision;
    private static long sCheckedAt;

    /** Which of the hub's id kinds an AMLL directory's ids are. */
    static String kindOf(String dir) {
        if ("am-lyrics".equals(dir)) return "appleMusicId";
        if ("ncm-lyrics".equals(dir)) return "ncmMusicId";
        if ("qq-lyrics".equals(dir)) return "qqMusicId";
        return null;
    }

    /** The TTML for this id, or null. Blocking; the caller is a lookup thread. */
    static synchronized String lookup(android.content.Context ctx, String dir, String id) {
        String kind = kindOf(dir);
        if (ctx == null || kind == null || id == null) return null;
        try {
            File root = new File(ctx.getFilesDir(), "ttmlhub");
            if (!root.isDirectory() && !root.mkdirs()) return null;
            ensureIndex(root);
            Map<String, Entry> index = sIndex;
            Entry e = index == null ? null : index.get(kind + ":" + id.trim());
            if (e == null) return null;
            byte[] ttml = file(root, e);
            if (ttml == null) return null;
            Xp.log("[" + TAG + "] " + kind + " " + id + " -> " + e.id + " (" + ttml.length + " bytes)");
            return new String(ttml, "UTF-8");
        } catch (Throwable t) {
            Xp.w("[" + TAG + "] lookup failed: " + t);
            return null;
        }
    }

    private static void ensureIndex(File root) throws Exception {
        File indexFile = new File(root, "songs.json");
        File metaFile = new File(root, "meta.json");
        if (sIndex == null && indexFile.isFile()) {
            sIndex = parse(readAll(indexFile, MAX_INDEX_BYTES));
            try {
                org.json.JSONObject meta = new org.json.JSONObject(
                        new String(readAll(metaFile, 4096), "UTF-8"));
                sRevision = meta.optString("revision", null);
                sCheckedAt = meta.optLong("checkedAt", 0L);
            } catch (Throwable ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (sIndex != null && now - sCheckedAt < CHECK_INTERVAL_MS) return;
        Http.Raw m = Http.request(MANIFEST, TAG, null, "Accept", "application/json");
        sCheckedAt = now;
        if (!m.ok()) {
            writeMeta(metaFile);
            return;
        }
        org.json.JSONObject manifest = new org.json.JSONObject(m.text());
        String revision = manifest.optString("revision");
        if (manifest.optInt("schemaVersion") != 2 || revision.isEmpty()
                || (sIndex != null && revision.equals(sRevision))) {
            writeMeta(metaFile);
            return;
        }
        String name = manifest.optString("index");
        if (name.isEmpty() || name.contains("..")) return;
        Http.Raw idx = Http.request(BASE + "api/v1/" + name.replaceFirst("^/+", ""), TAG, null,
                "Accept", "application/json");
        if (!idx.ok() || idx.body.length > MAX_INDEX_BYTES
                || !sha256(idx.body).equalsIgnoreCase(manifest.optString("indexSha256"))) {
            Xp.log("[" + TAG + "] index for " + revision + " did not arrive intact");
            writeMeta(metaFile);
            return;
        }
        Map<String, Entry> parsed = parse(idx.body);
        if (parsed == null) return;
        write(indexFile, idx.body);
        sIndex = parsed;
        sRevision = revision;
        writeMeta(metaFile);
        Xp.log("[" + TAG + "] index " + revision + ": " + parsed.size() + " ids");
    }

    private static Map<String, Entry> parse(byte[] json) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(new String(json, "UTF-8"));
        if (o.optInt("schemaVersion") != 2) return null;
        org.json.JSONArray songs = o.optJSONArray("songs");
        if (songs == null) return null;
        Map<String, Entry> out = new HashMap<>();
        for (int i = 0; i < songs.length(); i++) {
            org.json.JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id"), path = s.optString("path"), sha = s.optString("sha256");
            if (id.isEmpty() || path.isEmpty() || sha.length() != 64) continue;
            Entry e = new Entry(id, path, sha);
            org.json.JSONObject ids = s.optJSONObject("sourceIds");
            if (ids == null) continue;
            java.util.Iterator<String> kinds = ids.keys();
            while (kinds.hasNext()) {
                String kind = kinds.next();
                org.json.JSONArray list = ids.optJSONArray(kind);
                for (int k = 0; list != null && k < list.length(); k++) {
                    String v = list.optString(k).trim();
                    if (!v.isEmpty()) out.put(kind + ":" + v, e);
                }
            }
        }
        return out;
    }

    /** The song's file, from the disk if a copy with the right hash is there. */
    private static byte[] file(File root, Entry e) throws Exception {
        File cached = new File(root, e.id + ".ttml");
        if (cached.isFile() && cached.length() > 0 && cached.length() <= MAX_TTML_BYTES) {
            byte[] b = readAll(cached, MAX_TTML_BYTES);
            if (b != null && sha256(b).equalsIgnoreCase(e.sha256)) return b;
            cached.delete();
        }
        String path = e.path.replaceFirst("^/+", "");
        if (!path.startsWith("lyrics/") || path.contains("..")) return null;
        Http.Raw r = Http.request(BASE + path, TAG, null,
                "Accept", "application/ttml+xml, application/xml, text/xml");
        if (!r.ok() || r.body.length > MAX_TTML_BYTES || !sha256(r.body).equalsIgnoreCase(e.sha256)) {
            return null;
        }
        write(cached, r.body);
        return r.body;
    }

    private static void writeMeta(File f) {
        try {
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("revision", sRevision == null ? "" : sRevision);
            meta.put("checkedAt", sCheckedAt);
            write(f, meta.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readAll(File f, int max) throws Exception {
        if (!f.isFile() || f.length() > max) return null;
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        } finally {
            in.close();
        }
        return b;
    }

    private static void write(File target, byte[] bytes) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }

    private static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
