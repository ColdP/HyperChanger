// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * The wallpaper process's answers to the lock clock's colour questions, kept until the wallpaper
 * changes. Wallpaper process.
 *
 * Every time the lock clock settles, SystemUI asks IMiuiWallpaperManagerService for the palette
 * of the clock's region (getMiuiWallpaperPalette with z=true) and whether that region is deep
 * (getMiuiLockPartWallpaperIsDeep). Read off the dex: the whole-picture palette is cached by the
 * OEM, but both of these go to MiuiWallpaperManagerService.w / .r with a null Bitmap, which
 * decodes the whole wallpaper file afresh - every call, with nothing remembered.
 *
 * PaletteThrottle already folds the calls of one clock movement into one, but entering and
 * leaving the cover still moves the clock each time, so each did two full decodes for an answer
 * that cannot have changed: the cover is answered separately (WallpaperProbe.installCoverPalette)
 * and never reaches here, and the file is the same file. On a CPU capped near 2GHz - the 8 Elite
 * Gen 5 throttled, i.e. an ordinary phone on an ordinary day - each decode took 250-2000ms,
 * 41 of them in 27s, on the cores SystemUI's RenderThread needed: ~9 dropped frames per cover
 * toggle against ~3 (2026-10-20).
 *
 * The answer depends on the request (which wallpaper, the regions asked for) and on the picture.
 * The picture is identified by the system's wallpaper ids, which change on every set - ours
 * included - and by night mode, in case the OEM darkens what it decodes. Anything that cannot be
 * identified is not cached.
 */
final class PaletteCache {

    private PaletteCache() {
    }

    private static final String TAG = "[MCWall] palette cache: ";
    private static final String CTL = "com.miui.miwallpaper.server.MiuiWallPaperControllerService";
    private static final int MAX = 32;

    private static final ConcurrentHashMap<String, Object> sAnswers = new ConcurrentHashMap<>();
    /** One lock per question, so the same request arriving on three binder threads at once -
     * as it does - decodes once and the other two wait for that answer. */
    private static final ConcurrentHashMap<String, Object> sLocks = new ConcurrentHashMap<>();
    /** The picture the answers are about; they all go when it changes. */
    private static volatile String sPicture = "";
    private static volatile int sHits, sMisses, sUncached, sCleared;

    /**
     * Hooks the depth question, and the palette one too when the cover palette's hook - which
     * calls proceed() through here - did not go in. Each fails on its own.
     */
    static void install(ClassLoader cl, boolean paletteToo) {
        Class<?> ctl;
        try {
            ctl = Xp.findClass(CTL, cl);
        } catch (Throwable t) {
            Xp.log(TAG + "not installed: " + t);
            return;
        }
        int hooked = 0;
        for (Class<?> inner : ctl.getDeclaredClasses()) {
            for (java.lang.reflect.Method m : inner.getDeclaredMethods()) {
                final String name = m.getName();
                boolean deep = "getMiuiLockPartWallpaperIsDeep".equals(name);
                if (!deep && !(paletteToo && "getMiuiWallpaperPalette".equals(name))) continue;
                if (!asked(m)) continue;
                try {
                    Xp.hook("wall.palette.cache." + Xp.slug(name), m, chain -> proceed(chain, name));
                    hooked++;
                } catch (Throwable t) {
                    Xp.log(TAG + name + " not cached: " + t);
                }
            }
        }
        Xp.log(TAG + hooked + " entry point(s)" + (paletteToo ? ", palette included" : ""));
        installDecodeCache(cl);
    }

    // ------------------------------------------------------------ the decoded wallpaper

    private static final String SERVICE = "com.miui.miwallpaper.server.MiuiWallpaperManagerService";

    /**
     * The answers above are keyed on the regions asked about, and the clock asks about a region
     * where it stands - which moves with every way into and out of the cover. Measured
     * 2026-10-20: 140 hits and 32 misses in 78s, every miss a whole-file decode of the 1200x2608
     * wallpaper (MiuiWallpaperManagerService.s, called by w and r when they are handed no
     * bitmap), 0.4-2.2s each with several at once - 21.6 CPU seconds on the binder threads, in
     * the middle of the cover's own composition and fade.
     *
     * So the decode itself is kept: s(which) answers a copy of the one it made last, for as long
     * as the file it reads is that same file. A copy because every caller recycles what s hands
     * it; a 12.5MB copy is a few milliseconds against a decode's hundreds. Let go after
     * DECODE_KEEP_MS without a question.
     */
    private static final long DECODE_KEEP_MS = 60_000L;
    private static final ConcurrentHashMap<Integer, Object[]> sDecoded = new ConcurrentHashMap<>();
    private static java.lang.reflect.Method sFileOf;
    private static volatile int sDecodeHits, sDecodeMisses;
    private static android.os.Handler sDropper;

    private static void installDecodeCache(ClassLoader cl) {
        try {
            Class<?> svc = Xp.findClass(SERVICE, cl);
            java.lang.reflect.Method decode = null, file = null;
            int decodes = 0, files = 0;
            for (java.lang.reflect.Method m : svc.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != int.class) continue;
                if (m.getReturnType() == android.graphics.Bitmap.class) {
                    decode = m;
                    decodes++;
                } else if (m.getReturnType() == android.os.ParcelFileDescriptor.class) {
                    file = m;
                    files++;
                }
            }
            // Read off the dex: one of each. More than one is a build this was not read from.
            if (decodes != 1 || files != 1) {
                Xp.log(TAG + "decode not kept: " + decodes + " decoder(s), " + files + " file(s)");
                return;
            }
            file.setAccessible(true);
            sFileOf = file;
            Xp.hook("wall.palette.decode", decode, PaletteCache::decoded);
            Xp.log(TAG + "decode kept through " + decode.getName());
        } catch (Throwable t) {
            Xp.log(TAG + "decode not kept: " + t);
        }
    }

    private static Object decoded(XposedInterface.Chain chain) throws Throwable {
        Object which = chain.getArgs().get(0);
        String file = fileKey(chain.getThisObject(), which);
        if (!(which instanceof Integer) || file == null) return chain.proceed();
        Object[] lock = sDecoded.computeIfAbsent((Integer) which, k -> new Object[2]);
        synchronized (lock) {
            android.graphics.Bitmap had = (android.graphics.Bitmap) lock[1];
            if (had != null && file.equals(lock[0]) && !had.isRecycled()) {
                android.graphics.Bitmap copy = had.copy(had.getConfig(), true);
                if (copy != null) {
                    sDecodeHits++;
                    dropLater();
                    return copy;
                }
            }
            Object made = chain.proceed();
            sDecodeMisses++;
            lock[0] = null;
            lock[1] = null;
            if (made instanceof android.graphics.Bitmap && !((android.graphics.Bitmap) made).isRecycled()) {
                android.graphics.Bitmap b = (android.graphics.Bitmap) made;
                android.graphics.Bitmap keep = b.copy(b.getConfig(), false);
                if (keep != null) {
                    lock[0] = file;
                    lock[1] = keep;
                    dropLater();
                }
            }
            return made;
        }
    }

    /**
     * The file s(which) reads - the one its sibling hands it - as inode, size and modification
     * time, with night mode beside it; null when it cannot be told, which is never kept.
     */
    private static String fileKey(Object service, Object which) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            pfd = (android.os.ParcelFileDescriptor) sFileOf.invoke(service, which);
            if (pfd == null) return null;
            android.system.StructStat st = android.system.Os.fstat(pfd.getFileDescriptor());
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            int night = ctx == null ? -1 : ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtim.tv_sec + "."
                    + st.st_mtim.tv_nsec + ":" + night;
        } catch (Throwable t) {
            return null;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static synchronized void dropLater() {
        // Gen's handler: retirement clears what is still queued on it, so the decoded copy of
        // the wallpaper is not held by a message that would run old code later.
        if (sDropper == null) sDropper = Gen.handler();
        sDropper.removeCallbacks(DROP_DECODED);
        sDropper.postDelayed(DROP_DECODED, DECODE_KEEP_MS);
    }

    private static final Runnable DROP_DECODED = new Runnable() {
        @Override
        public void run() {
            for (Object[] lock : sDecoded.values()) {
                synchronized (lock) {
                    lock[0] = null;
                    lock[1] = null;
                }
            }
        }
    };

    /** The shape both questions have: (int which, ..., Map regions) -> Map. */
    static boolean asked(java.lang.reflect.Method m) {
        Class<?>[] pt = m.getParameterTypes();
        return pt.length >= 2 && pt[0] == int.class && Map.class.isAssignableFrom(pt[pt.length - 1])
                && Map.class.isAssignableFrom(m.getReturnType());
    }

    /** chain.proceed(), answered from what an earlier identical question was told. */
    static Object proceed(XposedInterface.Chain chain, String name) throws Throwable {
        String key = key(chain, name);
        if (key == null) {
            sUncached++;
            return chain.proceed();
        }
        Object lock = sLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            Object had = sAnswers.get(key);
            if (had != null) {
                sHits++;
                return copy(had);
            }
            Object answer = chain.proceed();
            sMisses++;
            // An empty answer is what a failed decode gives: asked again next time.
            if (answer instanceof Map && !((Map<?, ?>) answer).isEmpty()) {
                if (sAnswers.size() >= MAX) sAnswers.clear();
                sAnswers.put(key, copy(answer));
            }
            return answer;
        }
    }

    private static String key(XposedInterface.Chain chain, String name) {
        String picture = picture();
        if (picture == null) return null;
        if (!picture.equals(sPicture)) {
            sPicture = picture;
            if (!sAnswers.isEmpty()) {
                sAnswers.clear();
                sCleared++;
            }
        }
        StringBuilder b = new StringBuilder(name).append('|').append(picture);
        for (Object a : chain.getArgs()) {
            b.append('|');
            if (a instanceof Map) {
                // Sorted: two requests for the same regions are one question.
                try {
                    b.append(new TreeMap<>((Map<?, ?>) a));
                } catch (Throwable t) {
                    return null;   // keys that do not sort - not a request we know
                }
            } else {
                b.append(a);
            }
        }
        return b.toString();
    }

    /** The wallpapers on both slots, and night mode; null when they cannot be told. */
    private static String picture() {
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (ctx == null) return null;
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            int lock = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
            int home = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            if (lock < 0 && home < 0) return null;
            int night = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return lock + "/" + home + "/" + night;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The answer is parcelled back to SystemUI; a copy keeps ours out of anyone's hands. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object copy(Object answer) {
        return answer instanceof Map ? new java.util.HashMap((Map) answer) : answer;
    }

    static void clear() {
        sAnswers.clear();
        sCleared++;
    }

    /** For WPROBE op pcache. */
    static String describe() {
        return "hits=" + sHits + " misses=" + sMisses + " uncached=" + sUncached
                + " cleared=" + sCleared + " held=" + sAnswers.size() + " picture=" + sPicture
                + " decode hits=" + sDecodeHits + " misses=" + sDecodeMisses;
    }
}
