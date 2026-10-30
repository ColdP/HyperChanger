// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.TrackName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.util.Arrays;

/**
 * Motion covers: which video the cover shows for the track on screen, and who plays it.
 *
 * SystemUI side. CoverPush tells this about every cover it puts up and takes down; this looks the
 * track up (MotionArt, on its own thread), keeps the videos in a cache keyed by album, and hands
 * the file to whichever cover is showing:
 *
 *   square card  -> CardVideo, in this process; the backdrop behind it stays the still
 *   full screen  -> the wallpaper process (op vidtex), which draws it into the lock wallpaper's
 *                   own frame so the clock glass and the card blur see it - see WallpaperVideo
 *
 * The still is what shows until a video is ready, and whenever there is none. Off by default;
 * downloads only on an unmetered network unless sOnData. Cached videos play on any network.
 */
final class MotionCover {

    private MotionCover() {
    }

    private static final String TAG = "[MCMotion] ";
    static volatile boolean sEnabled;
    /** Look up and download on a metered network too - mobile data. Off by default. */
    static volatile boolean sOnData;

    private static final String DIR = "motion";
    private static final String INDEX = "index.txt";
    // 300MB held about 30 albums at 5-15MB each, and an album played that morning had been
    // pushed out by evening (2026-10-14, 1989 evicted with 34 files on disk).
    private static final long CACHE_BYTES = 1024L * 1024 * 1024;
    /** A store that answered "no motion artwork" is asked again after this. */
    private static final long NONE_TTL_MS = 7L * 24 * 3600 * 1000;
    /** A request that did not get through is tried again after this. */
    private static final long FAIL_TTL_MS = 30L * 60 * 1000;
    /** Where the full-screen cover's video goes for the wallpaper process to read. */
    private static final String SHARED_NAME = "hyperchanger_hmc_next_motion.mp4";

    private static HandlerThread sThread, sIoThread;
    private static Handler sWork, sIo;

    /** Main-thread state: the lookup being answered, and what is playing. */
    private static volatile int sGen;
    private static String sWantKey = "";
    private static boolean sWantCard;
    private static float sSentBias = Float.NaN;
    private static String sPlayingFile;
    private static boolean sPlayingCard;
    private static String sLast = "nothing yet";

    /** Copies to the shared directory; never queued behind a lookup on the network. */
    private static synchronized Handler io() {
        if (sIo == null) {
            sIoThread = Gen.handlerThread("MCMotionIO");
            sIo = new Handler(sIoThread.getLooper());
        }
        return sIo;
    }

    private static synchronized Handler work() {
        if (sWork == null) {
            sThread = Gen.handlerThread("MCMotion");
            sWork = new Handler(sThread.getLooper());
        }
        return sWork;
    }

    // ================================================================ from CoverPush

    /** A cover is up for what the watched session is playing. Any thread. */
    static void onCover(final boolean card) {
        final Context ctx = Main.sAppCtx;
        MediaController c = Main.watched();
        MediaMetadata md = c == null ? null : c.getMetadata();
        if (ctx == null || md == null) return;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        // Salt, 汽水 and QQ (on Bluetooth) sing into TITLE and move the song into ARTIST; the
        // lyric lookup already knows how to read them back out. See TrackName.
        String[] sung = TrackName.searchName(c.getPackageName(), md);
        if (sung != null) {
            t = sung[0];
            a = sung[1];
        }
        // QQ's bracketed second names: the store has one of them, not both together.
        if (t != null) t = TrackName.untranslated(t);
        if (a != null) a = TrackName.unaliased(a);
        final String title = t;
        final String artist = a;
        final String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        final long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Main.main().post(() -> track(ctx, title, artist, album, dur, card));
    }

    /** The cover is gone. Any thread. */
    static void onCoverOff() {
        Main.main().post(() -> {
            sGen++;
            sWantKey = "";
            // The square's video is only paused - the card stops wanting it - and kept for a
            // while: torn down on every exit and rebuilt on every entry, it cost a decoder, a GL
            // context and 700ms before the video moved again. Back to the same album in time,
            // track() finds it still loaded and carries on from where it stopped.
            if (sPlayingFile != null && sPlayingCard) {
                final int park = ++sPark;
                Main.main().postDelayed(() -> {
                    if (park == sPark) stopVideo("parked " + PARK_MS / 1000 + "s");
                }, PARK_MS);
                return;
            }
            stopVideo("cover off");
        });
    }

    /** How long the square's video stays loaded after the cover goes. */
    private static final long PARK_MS = 30_000L;
    /** Main thread: bumped by anything that ends or reclaims a parked video. */
    private static int sPark;

    private static void track(Context ctx, String title, String artist, String album, long dur,
                              boolean card) {
        // The full-screen cover over a video lock wallpaper is a view in SystemUI, not the
        // wallpaper's texture, and there is no image engine there to draw a video into.
        if (!card && Main.sVideoWallpaper) {
            sGen++;
            sWantKey = "";
            stopVideo("video lock wallpaper");
            return;
        }
        // 保留壁纸 puts no cover up, so there is nothing for a video to stand in for. Not
        // card is not full screen: the switch is only hidden there, still on, and the video
        // went to the wallpaper over the user's own (2026-10-14).
        if (Main.sCoverCardStyle.mode == CoverCardStyle.KEEP) {
            sGen++;
            sWantKey = "";
            stopVideo("keep wallpaper");
            return;
        }
        if (!sEnabled || title == null || title.trim().isEmpty()) {
            sGen++;
            sWantKey = "";
            stopVideo(sEnabled ? "no title" : "off");
            return;
        }
        watchNetwork(ctx);
        final String key = cacheKey(title, artist, album);
        // Asked already, for this cover: playing, being looked up, or known to have none. The
        // metadata fires on every state change, and for some players on every sung line.
        if (key.equals(sWantKey) && sWantCard == card) {
            if (!card && sPlayingFile != null && Main.sBias != sSentBias) {
                sSentBias = Main.sBias;
                Intent i = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.WPROBE");
                i.setPackage("com.miui.miwallpaper");
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                i.putExtra("op", "vidbias");
                i.putExtra("bias", Main.sBias);
                ProbeGuard.send(ctx, i);
            }
            return;
        }
        // Same album, same video: a track change inside an album keeps it playing.
        boolean sameVideo = sPlayingFile != null && sPlayingCard == card
                && sPlayingFile.equals(cachedFile(ctx, key));
        sWantKey = key;
        sWantCard = card;
        if (sameVideo) {
            sPark++;   // a parked video is the cover's again
            return;
        }
        stopVideo("track");
        final int gen = ++sGen;
        // A video already on disk plays now, not after whatever lookups are still queued.
        // Not until the song has held still: skipping through, each song's video came on for a
        // few hundred ms and went again, and with the metadata a song ahead of or behind the
        // media card it could land on the wrong still - the cover jumping about (2026-10-13).
        // The old video is already going (stopVideo above fades it out); the new one waits.
        Main.main().postDelayed(() -> {
            if (gen != sGen || !Main.sCoverMode) return;
            String cached = cachedFile(ctx, key);
            if (cached != null) {
                sLast = "cached " + new File(cached).getName() + " for " + key;
                startVideo(ctx, new File(cached), card);
                return;
            }
            work().post(() -> {
                // Skipped past before its turn came: the lookup is for a song no longer playing.
                if (gen != sGen) return;
                File f = resolve(ctx, key, title, artist, album, dur);
                Main.main().post(() -> {
                    if (gen != sGen || f == null || !Main.sCoverMode) return;
                    startVideo(ctx, f, card);
                });
            });
        }, SETTLE_MS);
    }

    /** How long a song has to stay before its video starts - or is looked up. */
    private static final long SETTLE_MS = 700L;

    // ================================================================ players

    private static void startVideo(Context ctx, File f, boolean card) {
        sPlayingFile = f.getPath();
        sPlayingCard = card;
        f.setLastModified(System.currentTimeMillis());   // the cache's LRU clock
        if (card) {
            CardVideo.load(ctx, f.getPath());
            return;
        }
        // The wallpaper process has another uid; it reads from the directory the composed
        // covers already go through, written beside the target and renamed over it.
        io().post(() -> {
            String shared = share(f);
            if (shared == null) return;
            Main.main().post(() -> {
                if (!f.getPath().equals(sPlayingFile) || sPlayingCard) return;
                Intent i = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.WPROBE");
                i.setPackage("com.miui.miwallpaper");
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                i.putExtra("op", "vidtex");
                i.putExtra("path", shared);
                // The still's band position, sent rather than read back over there: a cover
                // composed here (the JPEG route - the island's expand takes it) leaves no source
                // file, and the video fell back to 0.25 and sat higher than the still.
                i.putExtra("bias", Main.sBias);
                sSentBias = Main.sBias;
                ProbeGuard.send(ctx, i);
            });
        });
    }

    private static void stopVideo(String why) {
        sPark++;
        if (sPlayingFile == null) return;
        boolean card = sPlayingCard;
        sPlayingFile = null;
        if (card) {
            CardVideo.unload(why);
        } else {
            Context ctx = Main.sAppCtx;
            if (ctx == null) return;
            Intent i = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.WPROBE");
            i.setPackage("com.miui.miwallpaper");
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            i.putExtra("op", "vidtex");
            i.putExtra("on", false);
            ProbeGuard.send(ctx, i);
        }
    }

    private static String share(File f) {
        try {
            File dst = new File(CoverPush.SHARE_DIR, SHARED_NAME);
            File tmp = new File(CoverPush.SHARE_DIR, SHARED_NAME + ".tmp");
            try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] b = new byte[256 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            tmp.setReadable(true, false);
            if (!tmp.renameTo(dst)) throw new java.io.IOException("rename");
            return dst.getPath();
        } catch (Throwable t) {
            Xp.w(TAG + "cannot share the video with the wallpaper process: " + t);
            return null;
        }
    }

    // ================================================================ cache

    /** By album when the player names one - the whole album shares one video - else by song. */
    private static String cacheKey(String title, String artist, String album) {
        String a = MotionArt.norm(MotionArt.firstArtist(artist));
        String al = MotionArt.norm(album);
        return al.isEmpty() ? "s:" + a + "|" + MotionArt.norm(title) : "a:" + a + "|" + al;
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** The cached video for a key, if it is on disk. Main thread safe: reads one small file. */
    private static synchronized String cachedFile(Context ctx, String key) {
        String[] e = entry(ctx, key);
        if (e == null || !e[1].startsWith("v:")) return null;
        File f = new File(dir(ctx), e[1].substring(2));
        return f.isFile() ? f.getPath() : null;
    }

    /** {key, state, time}: state "v:<file>", "none" or "fail". */
    private static synchronized String[] entry(Context ctx, String key) {
        for (String line : readIndex(ctx)) {
            String[] p = line.split("\t");
            if (p.length == 3 && p[0].equals(key)) return p;
        }
        return null;
    }

    private static synchronized void put(Context ctx, String key, String state) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : readIndex(ctx)) {
            if (!line.startsWith(key + "\t")) lines.add(line);
        }
        lines.add(key + "\t" + state + "\t" + System.currentTimeMillis());
        // Bounded: one line per album ever looked up is small, but not forever.
        while (lines.size() > 2000) lines.remove(0);
        try {
            File tmp = new File(dir(ctx), INDEX + ".tmp");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                os.write(String.join("\n", lines).getBytes("UTF-8"));
            }
            tmp.renameTo(new File(dir(ctx), INDEX));
        } catch (Throwable t) {
            Xp.w(TAG + "index write: " + t);
        }
    }

    private static java.util.List<String> readIndex(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File f = new File(dir(ctx), INDEX);
        if (!f.isFile()) return out;
        try {
            for (String l : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")
                    .split("\n")) {
                if (!l.isEmpty()) out.add(l);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Worker thread. The video for this track, from the cache or the store; null for none. */
    private static File resolve(Context ctx, String key, String title, String artist,
                                String album, long dur) {
        String[] e = entry(ctx, key);
        long now = System.currentTimeMillis();
        if (e != null) {
            long at = parse(e[2]);
            if (e[1].startsWith("v:")) {
                File f = new File(dir(ctx), e[1].substring(2));
                if (f.isFile()) {
                    sLast = "cached " + f.getName() + " for " + key;
                    return f;
                }
            } else if ("none".equals(e[1]) && now - at < NONE_TTL_MS) {
                sLast = "no motion artwork (asked " + ((now - at) / 3600000) + "h ago) " + key;
                return null;
            } else if ("fail".equals(e[1]) && now - at < FAIL_TTL_MS) {
                sLast = "failed recently, waiting " + key;
                return null;
            }
        }
        if (!sOnData && !unmetered(ctx)) {
            sLast = "not on an unmetered network; " + key + " not looked up";
            Xp.log(TAG + sLast);
            return null;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            MotionArt.Found found = MotionArt.find(title, artist, album, dur);
            if (found == null) {
                put(ctx, key, "none");
                sLast = "no motion artwork for " + key + " ("
                        + (android.os.SystemClock.uptimeMillis() - t0) + "ms)";
                Xp.log(TAG + sLast);
                return null;
            }
            String name = found.albumId + ".mp4";
            File f = new File(dir(ctx), name);
            if (!f.isFile()) {
                if (!MotionArt.download(found.master, f)) {
                    put(ctx, key, "none");
                    sLast = "motion artwork not usable for " + key;
                    return null;
                }
                trim(ctx);
            }
            put(ctx, key, "v:" + name);
            sLast = "found \"" + found.albumTitle + "\" for " + key + " in "
                    + (android.os.SystemClock.uptimeMillis() - t0) + "ms";
            Xp.log(TAG + sLast);
            return f;
        } catch (MotionArt.Transient t) {
            put(ctx, key, "fail");
            sLast = "network failed for " + key + ": " + t.getMessage();
            Xp.log(TAG + sLast);
            return null;
        } catch (Throwable t) {
            put(ctx, key, "fail");
            sLast = "lookup failed for " + key + ": " + t;
            Xp.w(TAG + sLast);
            return null;
        }
    }

    /** Oldest-played first, until the videos fit the budget. The one playing is the newest. */
    private static void trim(Context ctx) {
        File[] files = dir(ctx).listFiles((d, n) -> n.endsWith(".mp4"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= CACHE_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= CACHE_BYTES) break;
            if (f.getPath().equals(sPlayingFile)) continue;
            total -= f.length();
            f.delete();
        }
    }

    private static boolean sWatching;
    private static boolean sWasUnmetered;
    private static ConnectivityManager sNetMgr;
    private static ConnectivityManager.NetworkCallback sNetCb;

    /**
     * Retirement: the network callback and any motion video. Both are host registrations or a
     * decoder, not views; Gen takes the callback back, and the video is handed to the same stop
     * path a track change uses.
     */
    static void retire() {
        if (sPlayingFile != null) {
            try {
                stopVideo("hot reload");
            } catch (Throwable ignored) {
            }
        }
        ConnectivityManager cm = sNetMgr;
        ConnectivityManager.NetworkCallback cb = sNetCb;
        sNetMgr = null;
        sNetCb = null;
        if (cm != null && cb != null) {
            try {
                Gen.unnetworkCallback(cm, cb);
            } catch (Throwable ignored) {
            }
        }
        sWatching = false;
        sWasUnmetered = false;
    }

    /**
     * A song that started on mobile data was never looked up, and nothing asked again once Wi-Fi
     * came: the same key is "asked already" to track(). Coming onto an unmetered network with a
     * cover up and no video, the song is taken from the top (2026-10-14, a whole album stayed
     * still after the phone joined Wi-Fi). Main thread.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private static void watchNetwork(Context ctx) {
        if (sWatching) return;
        sWatching = true;
        sWasUnmetered = unmetered(ctx);
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            sNetMgr = cm;
            sNetCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(android.net.Network n, NetworkCapabilities nc) {
                    boolean now = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    boolean was = sWasUnmetered;
                    sWasUnmetered = now;
                    if (!now || was || !sEnabled || sWantKey.isEmpty() || sPlayingFile != null
                            || !Main.sCoverMode) {
                        return;
                    }
                    Xp.log(TAG + "unmetered network now; asking again for " + sWantKey);
                    retry();
                }

                @Override
                public void onLost(android.net.Network n) {
                    sWasUnmetered = false;
                }
            };
            Gen.networkCallback(cm, sNetCb, Main.main());
        } catch (Throwable t) {
            sWatching = false;
            sNetMgr = null;
            sNetCb = null;
            Xp.w(TAG + "cannot watch the network: " + t);
        }
    }

    /** The cover on screen, asked again if it has no video. Main thread. */
    static void retry() {
        if (!sEnabled || sWantKey.isEmpty() || sPlayingFile != null || !Main.sCoverMode) return;
        sWantKey = "";
        onCover(sWantCard);
    }

    // Runs in SystemUI, which holds ACCESS_NETWORK_STATE; this APK's manifest is not the one asked.
    @android.annotation.SuppressLint("MissingPermission")
    private static boolean unmetered(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Throwable t) {
            return false;
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** For `op motion`. */
    static String describe() {
        return "enabled=" + sEnabled + " want=" + sWantKey + " playing=" + sPlayingFile
                + (sPlayingFile == null ? "" : (sPlayingCard ? " (card)" : " (wallpaper)"))
                + " last=" + sLast;
    }

    /** Forget what was looked up and found - for `op motion --ez forget true`. */
    static void forget(Context ctx) {
        work().post(() -> {
            File[] files = dir(ctx).listFiles();
            if (files != null) for (File f : files) f.delete();
            sLast = "cache cleared";
        });
    }
}
