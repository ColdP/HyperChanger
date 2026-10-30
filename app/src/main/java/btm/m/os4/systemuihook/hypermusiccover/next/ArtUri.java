// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

/**
 * The picture behind a session's artwork URI - the art a player publishes instead of, or before,
 * putting the bitmap into its metadata.
 *
 * Two measured things made this worth having (2026-10-08, MeiloX, com.neoruaa.meilox):
 *
 * SystemUI cannot read a cleartext URL at all. MeiloX publishes http://p1|p2.music.126.net/...
 * and every read of it failed - "Cleartext HTTP traffic to p2.music.126.net not permitted" -
 * 207 attempts in one day's log, not one of them arriving, which also took the whole
 * press-prediction prefetch down with it. The same path over https serves the same picture (200,
 * 776KB for the full one; NetEase's own ?param= takes it to 142KB), so the scheme is rewritten
 * here rather than asking a player to change it.
 *
 * And what we fell back to was worse than no picture. With no bitmap in the session the cover
 * went to the media card's thumbnail, which in that moment is often the card's own empty art:
 * all four pushes taken from a 192x192 thumbnail that day composed to a pure black tint
 * (#ff000000, against real colours on the other thirty-nine), and a later track that read the
 * same stub was dismissed as "same artwork as the last track" and never pushed at all.
 *
 * So: one GET, decoded no larger than the compose ever needs, cached by URL, and asked for in
 * the background so that no caller has to wait on it. AlbumArt.albumArt() takes the copy when - and
 * only when - the session has no bitmap of its own, which keeps the rule that a player that does
 * hand over a bitmap never has anything here touch it.
 */
final class ArtUri {

    private ArtUri() {
    }

    private static final String TAG = "[MCArt] ";

    /**
     * The widest side a cover is ever needed at. The compose scales to the screen (1220 here) and
     * NetEase serves 1200 when asked, so this bounds a hostile or unusual original without ever
     * touching what a player actually hands over.
     */
    private static final int MAX = 1400;

    /**
     * How long the card thumbnail stays out of the way while a URI is being read.
     *
     * The retry chain is fourteen tries 120ms apart, so six of them is ~700ms: enough for a cold
     * fetch (164ms for the same job on this device) and short enough that the chain still has
     * tries left when the read fails and the card has to answer instead.
     */
    private static final long WAIT_MS = 700L;

    /** Decoded artwork by the URL it came from. Two entries: what is playing, and what just was. */
    private static final java.util.LinkedHashMap<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
                    // Evicted, not recycled: Prefetch may hold the same instance, and its eviction
                    // does not recycle either. The collector takes it when both have let go.
                    return size() > 2;
                }
            };

    /** When each URL was asked for, so waiting() has something to measure. Bounded below. */
    private static final java.util.HashMap<String, Long> ASKED = new java.util.HashMap<>();

    /** URLs a read is in flight for. Cleared when it lands or fails, so nothing is asked twice. */
    private static final java.util.HashSet<String> INFLIGHT = new java.util.HashSet<>();

    /** URLs that came back with nothing usable: not asked for again, and not waited for. */
    private static final java.util.LinkedHashSet<String> DEAD = new java.util.LinkedHashSet<>();

    private static Handler sWork;

    private static synchronized Handler work() {
        if (sWork == null) {
            HandlerThread t = Gen.handlerThread("mc-art", Process.THREAD_PRIORITY_BACKGROUND);
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    /**
     * The URL to actually ask for: the one the player published, with the scheme forced to https
     * and - for NetEase's CDN, which has no other way to say it - the size put in the query. Null
     * for anything this does not read, which includes content:// and file:// artwork: those have
     * their own reader in Prefetch and never needed a scheme fixing.
     */
    static String readable(String uri) {
        String u = httpsOnly(uri);
        if (u == null) return null;
        if (u.contains("music.126.net") && u.indexOf('?') < 0) u += "?param=" + MAX + "y" + MAX;
        return u;
    }

    /** The player's own URL with the scheme changed, and nothing else. Null if it is not a URL. */
    private static String httpsOnly(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("http://")) return "https://" + uri.substring("http://".length());
        return uri.startsWith("https://") ? uri : null;
    }

    /**
     * The picture, now. Runs on the caller's thread - only Prefetch's worker and mc-art call it -
     * and answers null rather than throwing, like every other read in this module.
     */
    static Bitmap fetch(String uri) {
        String url = readable(uri);
        if (url == null) return null;
        String plain = httpsOnly(uri);
        long t0 = SystemClock.uptimeMillis();
        Bitmap b = decode(Http.request(url, "MCArt", null));
        if (b == null && !url.equals(plain)) {
            // Only the size this added was a guess about somebody else's server, and a path that
            // does not take it answers 404 rather than the picture. Asking again without it is
            // worth one request; asking again over the scheme is not, because that one the
            // platform refuses before it leaves the phone.
            b = decode(Http.request(plain, "MCArt", null));
        }
        if (b == null) {
            Xp.log(TAG + "nothing readable at " + uri);
            return null;
        }
        Xp.log(TAG + b.getWidth() + "x" + b.getHeight() + " in "
                + (SystemClock.uptimeMillis() - t0) + "ms from " + url);
        return b;
    }

    /** Decodes no larger than MAX on the long side: the full 1.6MB original is 16MB of pixels. */
    private static Bitmap decode(Http.Raw reply) {
        if (reply == null || reply.body == null || reply.body.length == 0) return null;
        byte[] jpg = reply.body;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int scale = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (scale * 2) >= MAX) scale *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = scale;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        } catch (Throwable t) {
            Xp.log(TAG + "could not decode " + jpg.length + " bytes: " + t);
            return null;
        }
    }

    /**
     * What is already in hand for this URI, or null. No I/O on any path, so this is safe to call
     * from the main thread - which AlbumArt.albumArt() is.
     */
    static Bitmap peek(String uri) {
        String url = readable(uri);
        if (url == null) return null;
        synchronized (CACHE) {
            Bitmap b = CACHE.get(url);
            if (b != null && !b.isRecycled()) return b;
        }
        // The queue prefetch reads the same URLs for the tracks either side of a press, and on a
        // player that publishes a queue the one playing is among them - same URL, same picture,
        // already downloaded. Its copy is taken as it is: that skips the wait and the request.
        Bitmap pre = Prefetch.cached(uri);
        if (pre != null) {
            synchronized (CACHE) {
                CACHE.put(url, pre);
            }
        }
        return pre;
    }

    /**
     * Asks for it in the background. Called from any thread - including the main one - and does
     * nothing at all for a URL that has arrived, is being read, or has already failed.
     */
    static void warm(final String uri) {
        final String url = readable(uri);
        if (url == null) return;
        if (peek(uri) != null) return;
        long now = SystemClock.uptimeMillis();
        synchronized (CACHE) {
            if (INFLIGHT.contains(url)) return;
            INFLIGHT.add(url);
            ASKED.put(url, now);
            trim(ASKED);
        }
        work().post(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = fetch(uri);
                synchronized (CACHE) {
                    INFLIGHT.remove(url);
                    if (b != null) {
                        DEAD.remove(url);
                        CACHE.put(url, b);
                    } else {
                        DEAD.add(url);
                        trim(DEAD);
                    }
                }
            }
        });
    }

    /**
     * Whether the cover should keep waiting for this URI: asked for, not answered yet, and inside
     * the window above. False in the three cases that mean "fall back" - it has arrived (peek
     * finds it), it failed, or the window is out with the read still going.
     */
    static boolean waiting(String uri) {
        String url = readable(uri);
        if (url == null) return false;
        synchronized (CACHE) {
            if (CACHE.containsKey(url) || DEAD.contains(url)) return false;
            Long asked = ASKED.get(url);
            return asked != null && SystemClock.uptimeMillis() - asked < WAIT_MS;
        }
    }

    /** Both bookkeeping maps are keyed on a URL, and a long session sees a great many. */
    private static void trim(java.util.HashMap<String, Long> m) {
        while (m.size() > 4) {
            java.util.Iterator<String> it = m.keySet().iterator();
            if (!it.hasNext()) return;
            it.next();
            it.remove();
        }
    }

    private static void trim(java.util.LinkedHashSet<String> s) {
        while (s.size() > 4) {
            java.util.Iterator<String> it = s.iterator();
            if (!it.hasNext()) return;
            it.next();
            it.remove();
        }
    }
}
