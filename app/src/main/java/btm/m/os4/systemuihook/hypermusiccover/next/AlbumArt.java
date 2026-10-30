// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Looper;
import android.widget.ImageView;
import android.view.View;

/**
 * Reading the track's artwork: from the session's metadata in its order of preference, from the
 * URI a player publishes instead of a bitmap, and from the media card's own thumbnail when the
 * session has none.
 *
 * Carved out of Main as it was (2026-10-14, after ChargeAnim): every member moved whole with its
 * comments, references across the line prefixed with Main. or AlbumArt., nothing else changed.
 */
public final class AlbumArt {
    private AlbumArt() {}

    static Bitmap albumArt(Context ctx) {
        return albumArt(ctx, true);
    }

    static Bitmap albumArt(Context ctx, boolean allowCard) {
        return albumArt(ctx, allowCard, null);
    }

    /**
     * Three sources, most preferred first: the session's own bitmap, the artwork URI it published
     * (read by ArtUri), and the media card thumbnail.
     *
     * allowCard gates the card. It lags a track change by a moment - long enough to hand back the
     * PREVIOUS album, or the card's own empty art - so callers that can afford to wait ask for the
     * session only, and the URI is given its window before the card is let in at all.
     *
     * The URI is read only on the push path (sessionBits != null), and only when the session
     * carried no bitmap of its own. That is the narrow case it exists for: a player that
     * publishes art as a URL and nothing else - MeiloX, and Bilibili for most of its switches -
     * had no source here at all, and the card underneath it answered with a black stub often
     * enough to be the visible bug. A player that does hand over a bitmap is never touched.
     *
     * sessionBits, when passed, is filled in with what the session turned out to be: 1 it carried
     * a bitmap (its own, or the one behind its URI), 0 it carried none but published artwork that
     * is being read right now, -1 there was no session to ask, -2 it carried no bitmap and no
     * artwork URI either. The caller needs the last two apart from the rest: 0 is a player still
     * coming, -2 is one that never will, and this is the only place that sees the difference.
     */
    static Bitmap albumArt(Context ctx, boolean allowCard, int[] sessionBits) {
        if (sessionBits != null) sessionBits[0] = -1;
        // The card's own session first: the media card is the switch, and when it shows a paused
        // player while another one plays (a carousel module's pick, KeiMi's) the ranking below
        // would hand over the other player's album - every retry, since it is the same picture.
        // A token whose session has gone answers no metadata; the ranking is the fallback then.
        MediaController c = MediaCard.controllerFromCard(ctx);
        if (c == null || c.getMetadata() == null) c = Main.pickController(ctx);
        if (c != null) {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null && md.getDescription() != null) {
                    b = md.getDescription().getIconBitmap();
                }
                if (b != null) {
                    if (sessionBits != null) sessionBits[0] = 1;
                    Xp.log(Main.TAG + "album art from " + c.getPackageName()
                            + " " + b.getWidth() + "x" + b.getHeight() + " \""
                            + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                    return b;
                }
                String uri = artworkUri(md);
                if (sessionBits != null && uri != null) {
                    Bitmap got = ArtUri.peek(uri);
                    if (got != null) {
                        sessionBits[0] = 1;
                        Xp.log(Main.TAG + "album art from " + c.getPackageName()
                                + "'s own artwork URI " + got.getWidth() + "x" + got.getHeight()
                                + " \"" + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                        return got;
                    }
                    ArtUri.warm(uri);
                    // Held back only while the read is young and the card is not yet allowed -
                    // which is every try but the last one, and the last one would otherwise push
                    // nothing at all. Past the window, or once the read has failed, this falls
                    // through to the card exactly as it did before.
                    if (ArtUri.waiting(uri) && !allowCard) {
                        sessionBits[0] = 0;
                        Xp.log(Main.TAG + c.getPackageName() + " carries no bitmap, reading its artwork"
                                + " URI (" + uri + ")");
                        return null;
                    }
                }
                if (sessionBits != null) sessionBits[0] = -2;
                Xp.log(Main.TAG + c.getPackageName() + " carries no art bitmap (uri=" + uri + ")");
            }
        }
        if (!allowCard) return null;
        return cardThumbnail();
    }

    /**
     * The one URI worth reading for this track: the album art's, or - when a player sets only
     * that - the general artwork's. Not the display icon: that is a notification's icon in most
     * players here, and a 96px one blown up to a wallpaper is worse than the card's copy.
     */
    static String artworkUri(MediaMetadata md) {
        String u = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (u == null || u.isEmpty()) u = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        return u == null || u.isEmpty() ? null : u;
    }

    /**
     * The media card's own thumbnail, read on the main thread - the composing now runs on a
     * worker, and the view tree belongs to the UI thread.
     */
    public static Bitmap cardThumbnail() {
        final View v = Main.sContainer;
        if (v == null) return null;
        final Bitmap[] out = new Bitmap[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("album_art_image", "id", "com.android.systemui");
                    // Under the card first. A carousel keeps other players' cards in the same
                    // header with the same child ids, and only the live one keeps the root id.
                    View card = Main.findSysuiView("mi_media_controls");
                    View art = id == 0 || card == null ? null : card.findViewById(id);
                    if (art == null && id != 0) art = v.getRootView().findViewById(id);
                    if (art instanceof ImageView) {
                        Drawable d = ((ImageView) art).getDrawable();
                        if (d instanceof BitmapDrawable) out[0] = ((BitmapDrawable) d).getBitmap();
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            Main.main().post(r);
            try {
                done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (out[0] != null) {
            Xp.log(Main.TAG + "album art from media card thumbnail "
                    + out[0].getWidth() + "x" + out[0].getHeight());
        }
        return out[0];
    }
}
