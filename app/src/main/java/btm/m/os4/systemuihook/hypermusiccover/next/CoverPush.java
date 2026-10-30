// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import btm.m.os4.systemuihook.hypermusiccover.LegacyShadeBridge;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.HandlerThread;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Getting the album art into the wallpaper, and keeping the lock wallpaper fit to take it.
 *
 * Split out of Main so that the push pipeline - compose, share the file, tell the wallpaper
 * process, and the video-wallpaper path that bypasses it - reads as one piece. Nothing here
 * decides WHETHER the cover should be on; Main does that and calls in.
 */
public final class CoverPush {
    /** How long after a push the clock asks for its colours again: past the cover's fade. */
    static final long PALETTE_AFTER_PUSH_MS = 700L;


    private CoverPush() {
    }

    /** Keyed on the artwork itself, so the app can re-ask as often as it likes for free. */
    private static volatile int sThumbPrint;
    private static volatile byte[] sThumbJpg;

    /**
     * The current artwork, small, as a JPEG. Runs on the receiver's thread - the main one - so
     * it stays deliberately cheap: one downscale and one encode of a picture a few hundred
     * pixels wide, and nothing at all when the artwork has not changed since the last ask.
     */
    static byte[] artThumbnail(Context ctx, int max) {
        Bitmap b = AlbumArt.albumArt(ctx);
        if (b == null) return null;
        int print = artPrint(b);
        byte[] cached = sThumbJpg;
        if (cached != null && print != 0 && print == sThumbPrint) return cached;
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, max / (float) Math.max(w, h));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : b;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            if (small != b) small.recycle();
            byte[] jpg = bos.toByteArray();
            sThumbPrint = print;
            sThumbJpg = jpg;
            return jpg;
        } catch (Throwable t) {
            Xp.w(Main.TAG + "art thumbnail failed: " + t);
            return null;
        }
    }

    /**
     * Hands the album art to the wallpaper process. It cannot read the media session itself, and
     * a full-screen bitmap is far past the binder limit, so send it as a JPEG the size of the
     * screen - already composed here, where we know the screen metrics.
     *
     * Composing costs a full-screen bitmap and a multi-pass blur, so callers go through
     * pushArtAsync() rather than calling this on the main thread.
     */
    private static void pushArtToWallpaper(Context ctx, boolean on) {
        pushArtToWallpaper(ctx, on, on ? AlbumArt.albumArt(ctx) : null);
    }

    private static void pushArtToWallpaper(Context ctx, boolean on, Bitmap art) {
        long t0 = android.os.SystemClock.uptimeMillis();
        // 保留壁纸: the wallpaper is the user's own throughout. Sent as the way out, so a cover
        // already up - the style switched with cover mode on - fades back to the wallpaper, and
        // so everything the way out drops (the motion cover, the square, a live wallpaper's view)
        // goes with it. The track key stays: it is what tells the next track change from this one.
        boolean keep = on && Main.keepsWallpaper();
        if (keep) on = false;
        Intent out = wallpaperIntent("art");
        out.putExtra("cardmode", Main.sCoverCardStyle.mode == CoverCardStyle.CARD);
        // The blur travels with the cover. A lyricblur broadcast is one shot - dropped, or
        // overtaken on the other side - and nothing else ever corrected it, so a song could play
        // out sharp under its lyrics. This makes every track change an agreement between the two
        // processes, and it also saves the new cover fading in sharp and frosting a beat later.
        //
        // It carries when the answer was decided as well, because this push is built on the
        // worker while the answer is decided on the main thread: a tap out of cover mode and
        // quickly back in has this push holding the answer from before the tap, and - being the
        // slow half, composed before it is sent - it reaches the wallpaper after the lyric switch
        // has said the opposite. The time is what lets that side drop the older of the two
        // instead of taking whichever arrived last. See LockLyrics.putBlurOn.
        LockLyrics.putBlurOn(out);
        // This side's half of the timeline, for `op timing` over there. See sCtTrack.
        out.putExtra("t0", Main.sCtTrack);
        out.putExtra("tskip", sSkipAt);
        out.putExtra("skipdir", sSkipDir);
        out.putExtra("tburst", Main.sCtBurst);
        out.putExtra("skips", Main.sCtSkips);
        out.putExtra("tart", Main.sCtArt);
        out.putExtra("checkms", Main.sCtCheckMs);
        out.putExtra("tries", Main.sCtTries);
        // Only a request. The wallpaper process falls back to the one-frame swap whenever it
        // does not hold both ends of the fade - after its own restart, most of all. Never with
        // the display off: the frames would be composed and uploaded into a screen nobody is
        // looking at, and the clock is not springing either.
        out.putExtra("fade", Main.sFadeWp && Main.screenOn());
        if (!on) {
            MotionCover.onCoverOff();
            CoverCardLayer.clear();
            if (Main.sVideoWallpaper) showVideoCover(ctx, false, null, null, 0);
            out.putExtra("off", true);
            ProbeGuard.send(ctx, out);
            // The wallpaper process keeps what was up for 30s more (WallpaperProbe.composedLater).
            if (sKeptUntil == Long.MAX_VALUE) {
                sKeptUntil = android.os.SystemClock.uptimeMillis() + KEPT_AFTER_OFF_MS;
            }
            if (!keep) Main.sTrackKey = "";
            // Nothing behind the clock any more, so nothing to take a colour from. The repaint
            // that hands the OEM's own colours back happens with the rest of cover mode.
            Main.sCoverTint = 0;
            // The shade has nothing to reveal any more - unless it is set to keep the last cover.
            // This branch is the single funnel for leaving cover mode - the tap, the dismissed
            // card, the release path and the cover op all end up here - so it is the only place
            // the picture has to be dropped.
            if (!LegacyShadeBridge.keepsArt()) LegacyShadeBridge.setArt(null);
            Xp.log(Main.TAG + (keep ? "pushart off: the wallpaper is kept" : "pushart off"));
            return;
        }
        if (art == null) { Xp.log(Main.TAG + "pushart: no album art"); return; }
        if (Main.sCoverCardStyle.mode == CoverCardStyle.CARD) CoverCardLayer.publish(art);
        MotionCover.onCover(Main.sCoverCardStyle.mode == CoverCardStyle.CARD);
        int w = Main.sScreenW, h = Main.sScreenH;
        if (!Main.sVideoWallpaper && Main.sWpComposes) {
            // The source goes over instead of the composed picture, and the wallpaper process
            // composes it for itself. Measured before this, on the path below: composing took
            // 27-94ms and the JPEG 47-185ms, all of it in front of the broadcast, and the other
            // side then spent 14-36ms decoding. The player's artwork is 512x512, so its raw
            // pixels are a 1MB write and no encode at all, and composing it in the wallpaper
            // process also takes that work off SystemUI while the clock is springing.
            Bitmap src = null;
            String shared = null;
            try {
                android.os.Trace.beginSection("MC push.source");
                src = CoverCompose.prepareSource(art, w);
                // The picture the lock screen handed over ahead of this tap (precomposeAsync) is
                // already on disk: the wallpaper process is pointed at it rather than given the
                // same megabyte again. 20-68ms of a busy phone's entry went on that write.
                shared = preSourceFor(art, w, h);
                if (shared == null) shared = writeSharedSource(src, w, h, Main.sBias);
                // Named, so the wallpaper process keeps it under this key and the next tap can
                // ask for it by name - and so a push for the picture the tap already put up
                // (sendKeptFirst) is let pass.
                boolean card = Main.sCoverCardStyle.mode == CoverCardStyle.CARD;
                String keptKey = preKey(art, w, h, card);
                out.putExtra("prekey", keptKey);
                noteKept(keptKey, layoutKey(w, h, card), Long.MAX_VALUE);
            } catch (Throwable t) {
                Xp.w(Main.TAG + "source hand-over failed, sending the composed JPEG instead: " + t);
            } finally {
                android.os.Trace.endSection();
            }
            if (shared != null) {
                out.putExtra("src", shared);
                out.putExtra("tsent", android.os.SystemClock.uptimeMillis());
                ProbeGuard.send(ctx, out);
                long sent = android.os.SystemClock.uptimeMillis();
                // Still composed here, but now after the send: the clock's tint and the shade
                // both need the picture, and neither of them is what the user is waiting on.
                // Same source, size and bias as the wallpaper process got, so the same pixels.
                //
                // The tint is all this copy is for: the average of the band behind the clock,
                // which a quarter-size composition gives as well as a full one. Composed at full
                // size it cost 0.1-1.8s a push on a busy phone (13s over 18 entries, trace
                // 2026-10-20), on the worker the next push waits for - and the key it was kept
                // under held the clock's band, which moves with every entry, so it was never
                // kept at all. Now the picture is kept per song and the band measured on it.
                String tintKey = artPrint(src) + ":" + src.getWidth() + "x" + src.getHeight()
                        + ":" + w + "x" + h + ":" + Main.sBias + ":" + Main.sCoverCardStyle.mode;
                boolean reused = tintKey.equals(sTintKey) && sTintPic != null;
                if (!reused) {
                    android.os.Trace.beginSection("MC push.compose");
                    try {
                        sTintPic = composeWallpaper(src, Math.max(1, w / TINT_SCALE),
                                Math.max(1, h / TINT_SCALE), Main.sBias);
                        sTintKey = tintKey;
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "composeWallpaper failed after the hand-over: " + t);
                        sTintPic = null;
                        return;
                    } finally {
                        android.os.Trace.endSection();
                    }
                }
                AodColour.measureCover(sTintPic, w / (float) sTintPic.getWidth());
                if (Main.sCoverMode) AodColour.recolorClock();
                // The shade builds its background from the square source and keeps nothing.
                android.os.Trace.beginSection("MC push.shade");
                try {
                    LegacyShadeBridge.setArt(src);
                } finally {
                    android.os.Trace.endSection();
                }
                Xp.log(Main.TAG + "pushart " + w + "x" + h + " bias=" + Main.sBias + " as a "
                        + src.getWidth() + "x" + src.getHeight() + " source, sent in "
                        + (sent - t0) + "ms, own copy " + (reused ? "reused" : "composed") + " in "
                        + (android.os.SystemClock.uptimeMillis() - sent) + "ms");
                // The clock's colours are the wallpaper process's reading of this picture.
                PaletteThrottle.INSTANCE.retriggerSoon(PALETTE_AFTER_PUSH_MS);
                return;
            }
        }
        // The video path's view shows the frosted copy, and that is made from the source and the
        // layout alone (CoverCompose.frostedFor) - nothing it needs waits on the composition. So it
        // is made alongside it rather than after it and the JPEG, which put 60ms of encoding for
        // the OTHER process in front of this one's own view on every entry.
        java.util.concurrent.FutureTask<Bitmap> frostTask = null;
        if (Main.sVideoWallpaper) {
            final Bitmap srcArt = art;
            final int fw = w, fh = h;
            final float fb = Main.sCoverCardStyle.mode == CoverCardStyle.CARD ? 0.5f : Main.sBias;
            frostTask = new java.util.concurrent.FutureTask<>(
                    () -> CoverCompose.frostedFor(srcArt, fw, fh, fb));
            Gen.thread("mc-cover-frost", frostTask);
        }
        Bitmap full;
        try {
            full = composeWallpaper(art, w, h, Main.sBias);
        } catch (Throwable t) {
            // This runs on a HandlerThread, and an uncaught throw on one reaches
            // KillApplicationHandler - so an OutOfMemoryError composing a full-screen cover
            // took SystemUI down with it. The shade's copy is what raises the steady-state
            // heap, which narrows the window rather than creating it; catching is cheap next
            // to a dead process, and the cost of catching is one track without a cover.
            Xp.w(Main.TAG + "composeWallpaper failed, no cover this round: " + t);
            return;
        }
        AodColour.measureCover(full);
        if (Main.sCoverMode) AodColour.recolorClock();
        if (frostTask != null) {
            // Up before the JPEG: the view is this process's own, and only reads `full`, which
            // the encode below only reads too.
            Bitmap frosted = null;
            try {
                frosted = frostTask.get();
            } catch (Throwable t) {
                Xp.w(Main.TAG + "frosted video cover failed: " + t);
            }
            showVideoCover(ctx, true, full, frosted, artPrint(art));
        }
        long tc = android.os.SystemClock.uptimeMillis();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int q = 95;
        byte[] jpg;
        do {
            bos.reset();
            full.compress(Bitmap.CompressFormat.JPEG, q, bos);
            jpg = bos.toByteArray();
            q -= 15;
        } while (jpg.length > 700 * 1024 && q > 25);
        // The cover video's own crossfade is built in the wallpaper process out of this, so
        // whether to ask for it has to travel with the push. See sVideoFade.
        out.putExtra("vfade", Main.sVideoFade);
        // By path, not by value, and that is not an optimisation.
        //
        // Measured on this phone: the largest cover in the library composes to 612KB, the
        // broadcast carrying it fails on the way out with `Binder transaction failure ...
        // error: -28 (No space left on device - Binder buffer full)`, system_server gives up on
        // the send, and Android kills the wallpaper process for a broadcast that could not be
        // delivered. Everything smaller went through, so size is the whole of the difference.
        // The process comes back, restores the previous track's cover from its own disk and
        // never asks for the right one, because as far as it knows it has art - which is the
        // album cover from the track before, on the lock screen, for the whole track.
        //
        // The byte path stays as the fallback: a build whose SELinux refuses the write still
        // has to work, and that is what it worked with before.
        String shared = writeSharedArt(jpg);
        if (shared != null) out.putExtra("file", shared);
        else out.putExtra("jpg", jpg);
        // Send broadcast first so wallpaper process begins decoding/encoding immediately!
        out.putExtra("tsent", android.os.SystemClock.uptimeMillis());
        ProbeGuard.send(ctx, out);
        // After the send: the shade's background is not what anyone is waiting on.
        LegacyShadeBridge.setArt(art);
        Xp.log(Main.TAG + "pushart " + w + "x" + h + " bias=" + Main.sBias
                + " as " + jpg.length + "B jpeg, draw " + (tc - t0) + "ms encode "
                + (android.os.SystemClock.uptimeMillis() - tc) + "ms");
        PaletteThrottle.INSTANCE.retriggerSoon(PALETTE_AFTER_PUSH_MS);

        // On the video path `full` went to the view above, which owns it now.
        if (!Main.sVideoWallpaper) full.recycle();
    }

    /**
     * The cover, for a lock screen whose wallpaper is a video (or any other live one).
     *
     * That case looked unreachable - MIUI builds a KeyguardVideoDepthEngineImpl instead of the
     * image engine, so the GL texture this module replaces never exists - but the video does
     * not live in the wallpaper process either. Traced on device: the engine decodes into two
     * SurfaceTextures that SYSTEMUI supplies, drawn by two full-screen TextureViews that
     * com.miui.keyguard.VideoDepthSurfaceHolder puts in keyguard_background_layer (behind the
     * clock) and keyguard_foreground_layer (the cut-out subject, in front of it).
     *
     * So on this path the wallpaper is already inside our own view tree, and the cover is just
     * a view above the background one. The rule that sent this module into the wallpaper
     * process in the first place - that the clock's liquid glass and the card blur sample the
     * wallpaper WINDOW, so an in-SystemUI cover is never picked up by them - does not hold
     * here, because MIUI is not using that window either. Confirmed on device: the glass
     * refracts a video wallpaper normally.
     *
     * Cheaper than the image path, too: no JPEG round trip and no cross-process broadcast, so
     * the composed bitmap goes straight onto the view.
     */
    private static void showVideoCover(Context ctx, boolean on, final Bitmap full,
                                       final Bitmap frosted, final int artPrint) {
        if (!on) {
            // Held and faded out rather than dropped on the spot: see armCoverFadeOut(). The
            // wallpaper's cut-out subject comes back with the view going, not now - it draws in
            // FRONT of the clock, so handed back at the start of a fade-out that is held for the
            // window's reload (a second, measured) it sat on top of the still-opaque cover:
            // the "mask is wrong" recording, pine branches and a sheep over the album art.
            armCoverFadeOut();
            Main.sVideoCoverBlurred = false;
            Xp.log(Main.TAG + "video cover off");
            return;
        }
        if (full == null) {
            Xp.log(Main.TAG + "video cover: no album art");
            if (frosted != null) frosted.recycle();
            return;
        }
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup layer = coverLayer();
                    if (layer == null) {
                        Xp.log(Main.TAG + "keyguard_background_layer not found for the video cover");
                        full.recycle();
                        if (frosted != null) frosted.recycle();
                        return;
                    }
                    ImageView iv = Main.sCover;
                    boolean isNew = (iv == null || iv.getParent() != layer);
                    if (isNew) {
                        Main.detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setAlpha(0f);
                        // Added last, so it draws over the video's TextureView. The layer is
                        // ordered, and a TextureView draws in the view hierarchy like any other
                        // view - unlike a SurfaceView, which would punch through whatever we
                        // put above it.
                        layer.addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        Gen.ownView(layer, iv);
                        Main.sCover = iv;
                    }
                    if (Main.sCoverCardStyle.mode == CoverCardStyle.CARD) {
                        CoverCardLayer.attach(layer);
                        CoverCardLayer.style(Main.sCoverCardStyle);
                        // The backdrop is this view here, not the wallpaper process, so nothing
                        // over there will say it is up: this line is.
                        CoverCardLayer.releaseHeld();
                    }
                    // So is an immersive page waiting on the cover to fade out over it.
                    ImmersiveHost.coverShown();
                    final Bitmap oldSharp = Main.sCoverBitmap;
                    final Bitmap oldBlur = Main.sCoverBlurBitmap;
                    final Bitmap oldShown = Main.sVideoCoverBlurred && oldBlur != null ? oldBlur : oldSharp;
                    Main.sCoverBitmap = full;
                    Main.sCoverBlurBitmap = frosted;
                    boolean wantBlur = LockLyrics.blurWanted();
                    Main.sVideoCoverBlurred = wantBlur;
                    final Bitmap target = (wantBlur && frosted != null) ? frosted : full;
                    boolean artChanged = artPrint != 0 && artPrint != sShownArtPrint;
                    sShownArtPrint = artPrint;
                    // A new album on a view that is already up is a crossfade INSIDE the view,
                    // not a fade from nothing: the view is opaque over the whole lock screen, so
                    // taking it to alpha 0 for the window's reload showed that reload - the
                    // previous cover, then a frame of the user's own video, then the new cover.
                    // It is held until the window has the new cover too (the same word the way
                    // in waits for), so the view and the glass and cards that sample the window
                    // change together.
                    //
                    // Only on a view that is on screen. A TransitionDrawable's clock starts on
                    // its first draw, so one armed while the guard has the cover hidden (the
                    // desktop) never moves: the old bitmaps are given back on a timer all the
                    // same, and the first frame after the wake drew the recycled layer 0 - a
                    // SystemUI crash. Hidden, there is nothing to fade, so the new one just goes on.
                    boolean swap = !isNew && artChanged && sCoverFadingOut == null
                            && sCoverFadeWaitMs <= 0 && iv.getAlpha() > 0f && iv.isShown()
                            && oldShown != null && !oldShown.isRecycled();
                    if (swap) {
                        armCoverSwap(iv, oldShown, target, oldSharp, oldBlur, full, frosted);
                    } else {
                        iv.setImageBitmap(target);
                        recycleUnless(oldSharp, full, frosted);
                        if (oldBlur != oldSharp) recycleUnless(oldBlur, full, frosted);
                    }
                    // The video's own cut-out subject is a second TextureView in the FOREGROUND
                    // layer, i.e. in front of the clock. Left alone it floats over the cover
                    // exactly the way deducted_image_view did on the image path.
                    Main.setDepthHidden(true);
                    guardVideoCover(iv);

                    // Fade in to bridge FastPlayer's first frame render (~120-150ms), so the
                    // background and the card's blur do not change on two different frames. OWED
                    // rather than started - see sCoverFadeWaitMs: this runs on a posted main
                    // thread task, and the cover is very often not on screen yet when it does.
                    if (isNew || sCoverFadingOut == iv || (iv.getAlpha() <= 0f && !swap)) {
                        // A fade-out still in flight is cancelled rather than left to fight this
                        // one: it would drive the alpha back down as this drives it up. Reaching
                        // here mid-fade-out means the user put the cover back before it was gone.
                        iv.animate().cancel();
                        sCoverFadingOut = null;
                        iv.setAlpha(0f);
                        armCoverFade();
                    }
                    Xp.log(Main.TAG + "video cover shown " + Main.sScreenW + "x" + Main.sScreenH
                            + " bias=" + Main.sBias + (isNew ? " (new view)" : "")
                            + (artChanged ? " (new album)" : "")
                            + (wantBlur ? " (frosted)" : " (sharp)")
                            + ", fade " + (sCoverFadeWaitMs > 0 ? "owed" : "not owed"));
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "video cover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Crossfades the video cover ImageView between sharp and frosted copy when lyrics toggle.
     */
    public static void updateVideoCoverBlur(final boolean blur) {
        if (!Main.sVideoWallpaper) return;
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                final ImageView iv = Main.sCover;
                if (iv == null || !Main.sCoverMode) return;
                if (Main.sVideoCoverBlurred == blur) return;
                Main.sVideoCoverBlurred = blur;
                final Bitmap sharp = Main.sCoverBitmap;
                final Bitmap frosted = Main.sCoverBlurBitmap;
                if (sharp == null || sharp.isRecycled()) return;
                if (blur && (frosted == null || frosted.isRecycled())) {
                    Gen.thread("mc-cover-blur", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final Bitmap f = CoverCompose.frosted(sharp);
                                Main.main().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // The sharp copy too: a track change while this ran
                                        // makes it the previous album's frost.
                                        if (Main.sVideoCoverBlurred && Main.sCover == iv
                                                && Main.sCoverBitmap == sharp) {
                                            Main.sCoverBlurBitmap = f;
                                            applyVideoCoverCrossfade(iv, sharp, f, true);
                                        } else if (f != null) {
                                            f.recycle();
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                Xp.w(Main.TAG + "generate frosted on demand failed: " + t);
                            }
                        }
                    });
                    return;
                }
                Bitmap from = blur ? sharp : (frosted != null ? frosted : sharp);
                Bitmap to = blur ? (frosted != null ? frosted : sharp) : sharp;
                applyVideoCoverCrossfade(iv, from, to, blur);
            }
        });
    }

    private static void applyVideoCoverCrossfade(ImageView iv, Bitmap from, Bitmap to, boolean blur) {
        if (iv == null || from == null || to == null || from == to || from.isRecycled() || to.isRecycled()) {
            if (iv != null && to != null && !to.isRecycled()) iv.setImageBitmap(to);
            return;
        }
        try {
            android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.BitmapDrawable(iv.getResources(), from),
                    new android.graphics.drawable.BitmapDrawable(iv.getResources(), to)
            };
            android.graphics.drawable.TransitionDrawable td =
                    new android.graphics.drawable.TransitionDrawable(layers);
            td.setCrossFadeEnabled(true);
            iv.setImageDrawable(td);
            int dur = Math.max(150, Math.min(500, (int) coverFadeMs()));
            td.startTransition(dur);
            Xp.log(Main.TAG + "video cover crossfading to " + (blur ? "blurred" : "sharp")
                    + " over " + dur + "ms");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "video cover blur crossfade failed: " + t);
            iv.setImageBitmap(to);
        }
    }

    /**
     * Keeps the live-wallpaper cover to the lock screen, and only the lock screen.
     *
     * keyguard_background_layer is not the keyguard's alone: MIUI draws it as the backdrop of
     * the Control Center too, the same way the media card view is shared between the lock
     * screen and the shade. So a full-screen ImageView parked in it turns up behind the
     * Control Center - reported from the device as the cover appearing there while music
     * played, and sharp rather than blurred, which is what says it is a VIEW being drawn and
     * not something sampling the wallpaper.
     *
     * The wallpaper's own TextureViews go the other way: they are hidden because our cover is
     * over them, and that is only true on the lock screen, so off it they are left exactly as
     * MIUI has them - see setVideoSurfacesHidden().
     *
     * A pre-draw listener rather than a one-off, for the reason the depth guard has one: this
     * has to be true on every frame something else draws that layer, and nothing tells us when
     * that is. Every write is conditional and visibility-only, so a frame that needs no change
     * costs a comparison.
     */
    private static void guardVideoCover(final View cover) {
        if (sCoverGuarded == cover && sCoverGuard != null) return;
        releaseCoverGuard();
        sCoverGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() { android.os.Trace.beginSection("MC coverGuard"); try {
                counterAodShrink(cover);
                if (Main.sUncoverProbe) {
                    // PROBE `uncover`: our view out of the way, MIUI's layers left hidden, so the
                    // screen shows the wallpaper WINDOW - which is what this probe is for.
                    if (cover.getVisibility() != View.INVISIBLE) cover.setVisibility(View.INVISIBLE);
                    hideVideoSurfaces();
                    return true;
                }
                if (sCoverFadingOut == cover) {
                    // On the way out. Cover mode is already off, so the rule below would hide
                    // this view on this very frame - and it is the only thing still over the
                    // wallpaper window while that window reloads the user's own video. Held up
                    // until its own fade-out ends; the TextureViews stay hidden under it because
                    // it is still covering them.
                    //
                    // The keyguard is still the condition, though, and that is not a formality:
                    // the hold can last a second and a half if the wallpaper process has nothing
                    // to say, and a cover left standing over the desktop is a cover on the
                    // desktop. Unlocking during the hold drops it on the spot - there is nothing
                    // left to mask there either, the window has already been handed back.
                    if (!Main.onKeyguardNow()) {
                        Xp.log(Main.TAG + "left the lock screen while the cover was fading out");
                        dropVideoCover();
                        return true;
                    }
                    if (cover.getVisibility() != View.VISIBLE) cover.setVisibility(View.VISIBLE);
                    hideVideoSurfaces();
                    return true;
                }
                // Only the AWAKE lock screen. The keyguard stays shown through the doze, and a
                // live wallpaper's AOD is the OEM's plain one - the video goes black and only its
                // cut-out subject stays. This view left up there turned the AOD into the album
                // cover. Going down it takes the doze's own dimming; coming back it owes the
                // fade below, so the wake dissolves from the video into the cover.
                boolean asleep = Main.sCoverMode && !Main.screenOnCached();
                boolean onKeyguard = Main.sCoverMode && Main.onKeyguardNow() && !asleep;
                int want = onKeyguard ? View.VISIBLE : View.INVISIBLE;
                if (cover.getVisibility() != want) cover.setVisibility(want);
                if (asleep && cover.getAlpha() > 0f) {
                    cover.animate().cancel();
                    cover.setAlpha(0f);
                    if (sCoverFadeWaitMs <= 0) sCoverFadeWaitMs = coverFadeMs();
                }
                if (onKeyguard) {
                    // The one place the cover's fade can start from. This runs on the frame the
                    // cover is really being drawn in, which is the frame the fade is for - the
                    // push that armed it happens whenever the broadcast does. See
                    // sCoverFadeWaitMs.
                    startCoverFade(cover);
                } else if (cover.getAlpha() < 1f) {
                    // Off the keyguard the fade is not seen, so it is not spent. An animator left
                    // running here walks 0..1 behind an INVISIBLE view and is over before the
                    // cover is shown again - a cover that appears in one frame, which is the
                    // "there is no fade-in at all" report. Cancel it and owe it again instead, so
                    // whichever frame the cover comes back on is the one it fades in on.
                    cover.animate().cancel();
                    cover.setAlpha(0f);
                    if (sCoverFadeWaitMs <= 0) sCoverFadeWaitMs = coverFadeMs();
                }
                // Our own view is the only thing to write off the lock screen. The wallpaper's
                // TextureViews belong to MIUI there, and handing them back inside a layer the
                // shade is drawing is what put a stray frame of the wallpaper into the first
                // pull-down - setVideoSurfacesHidden() has the measurement.
                if (!onKeyguard) return true;
                hideVideoSurfaces();
                return true;
            } finally { android.os.Trace.endSection(); } }
        };
        Gen.preDraw(cover, sCoverGuard);
        sCoverGuarded = cover;
        Xp.log(Main.TAG + "video cover guard installed");
    }

    /**
     * The scale the full-screen AOD's doDeductedImageScaleAnim last put on the keyguard's root
     * layout (wallpaperScale - 0.05), recorded by its hook in Main. NaN until it has run.
     */
    static volatile float sAodShrink = Float.NaN;

    /**
     * Keeps the video cover full-screen while the keyguard's root layout is shrunk for the
     * full-screen AOD. The layout carries the clock, the cards and the cover alike, and only the
     * cover must not move: the video wallpaper's window behind it stays at full size, so a
     * shrunk cover shows that window in all four corners. Undone with the inverse scale, around
     * the layout's own pivot expressed in the cover's coordinates. Only that one scale is
     * undone - an ancestor scaled by anything else (the unlock, the shade) is left to carry the
     * cover along as it always has.
     */
    private static void counterAodShrink(View cover) {
        float shrink = sAodShrink;
        View scaled = null;
        float offX = cover.getLeft(), offY = cover.getTop();
        for (android.view.ViewParent p = cover.getParent(); p instanceof View; p = p.getParent()) {
            View v = (View) p;
            if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
                scaled = v;
                break;
            }
            offX += v.getLeft();
            offY += v.getTop();
        }
        float want = 1f;
        if (scaled != null && shrink > 0f && shrink < 1f && scaled.getScaleX() == shrink) {
            want = 1f / shrink;
            cover.setPivotX(scaled.getPivotX() - offX);
            cover.setPivotY(scaled.getPivotY() - offY);
        }
        if (cover.getScaleX() != want) {
            cover.setScaleX(want);
            cover.setScaleY(want);
        }
    }

    /**
     * The cover's fade-in, owed rather than started.
     *
     * An alpha animation on a View runs whether or not that View is being drawn, and the guard
     * above hides the cover on every frame the keyguard is not what is in front - the shade, the
     * desktop, a rebuild of the keyguard, a re-entry into it. An animator started when the push
     * happens therefore commonly ran to completion behind an INVISIBLE view, and what the eye got
     * when the cover was shown was a single-frame appearance with the animation already spent.
     * That is the whole of "there is no fade-in on the device": the animator was the module's
     * own, running the whole time, on a view nobody could see.
     *
     * So the length is left here and the fade starts on the first frame the cover is genuinely
     * drawn - from the pre-draw guard, which is the only thing that knows whether it is. Zero
     * means nothing is owed.
     *
     * This only ever moves the module's own ImageView. What the notif card's blur samples is the
     * wallpaper WINDOW, which FastPlayer paints frame by frame and no View animation reaches -
     * see the note on showVideoCover().
     */
    static volatile long sCoverFadeWaitMs;

    /**
     * How long the cover's own fade runs, which is the wallpaper's crossfade length and not a
     * number of its own.
     *
     * The same rule the card's stand-in animator and the still path's crossfade already follow:
     * one fade per transition, derived from the same response curve, so the app's slider moves
     * all of them together. The wallpaper process is handed this same number (pushFadeMs), and
     * the cover video it builds runs its own crossfade over it - so the layer the eye sees and
     * the layer the notif card's blur samples are at least on the same clock, even though the
     * window's half of it starts later, when the player has the file.
     */
    private static long coverFadeMs() {
        return Main.fadeMsFor(Main.sClockResponse);
    }

    /** The fade mode by name, for the log and the state dump. See sFadeMode. */
    static String fadeModeName() {
        int m = Main.sFadeMode;
        return m == Main.FADE_MODE_OFF ? "off" : m == Main.FADE_MODE_STRETCH ? "stretch" : "hold";
    }

    /** Whether the owed fade is being held back for the wallpaper window. See sFadeMode. */
    static volatile boolean sCoverFadeWaiting;
    /** When the owed fade was armed, for the gap measurement and the log line. */
    static volatile long sCoverFadeArmedAt;
    /** How long the wallpaper window was behind the push last time, for FADE_MODE_STRETCH. */
    static volatile long sCoverFadeGapMs;
    /** How often the wallpaper process has said it is reloading, for the state dump. */
    static volatile int sVideoReloadSignals;

    /**
     * The ceiling on a fade that is matched to the wallpaper process's own timing: a phone that
     * was busy for a second on one transition must not leave the next one dissolving for a
     * second and a half. The floor is coverFadeMs() itself, which the matched length never goes
     * under (armCoverFade).
     */
    private static final long COVER_FADE_MAX_MS = 800L;

    /**
     * How long the owed fade waits for that word before starting anyway.
     *
     * A fallback, not a timer: the cover video's encode is a few hundred milliseconds and the
     * player's own rebuild sits on top of it, so this is comfortably past both. It is here so
     * that a phone whose video engine this module never captures - or whose cover video fails to
     * encode - cannot be left with the cover held at alpha 0 waiting for a message that is not
     * coming. See releaseCoverFadeWait().
     */
    // 1.8s: past the wallpaper process's own first-frame wait (1.5s) so its real signal is
    // the one that releases the held fade, with this timeout only as the stuck-path floor.
    private static final long COVER_FADE_SIGNAL_TIMEOUT_MS = 1800L;

    static final Runnable sCoverFadeTimeout = new Runnable() {
        @Override
        public void run() {
            releaseCoverFadeWait("no word from the wallpaper process");
        }
    };

    /**
     * Owes one fade-in for the cover.
     *
     * Not started here whatever the mode: the frame the fade belongs to is the one the cover is
     * next drawn in, which only the pre-draw guard knows. See sCoverFadeWaitMs.
     */
    private static void armCoverFade() {
        // Started on the cover's first drawn frame, never held for the window. Held, the lock
        // screen sat on the user's video for the whole encode and reload - a third of a second
        // with the clock already collapsing - and then the cover arrived at once: "slow, and no
        // transition". Instead the dissolve is stretched to the window's measured head start
        // when that is the longer of the two, so it is still under way when the window swaps,
        // and the glass and the cards change under a cover that is nearly opaque.
        long ms = coverFadeMs();
        if (sCoverFadeGapMs > 0) {
            ms = Math.max(ms, Math.min(COVER_FADE_MAX_MS, sCoverFadeGapMs));
        }
        sCoverFadeWaitMs = ms;
        sCoverFadeArmedAt = android.os.SystemClock.uptimeMillis();
        Main.main().removeCallbacks(sCoverFadeTimeout);
        sCoverFadeWaiting = false;
        Xp.log(Main.TAG + "cover fade armed: " + ms + "ms, last gap=" + sCoverFadeGapMs + "ms");
    }

    /**
     * Lets the owed fade go: the wallpaper window is reloading, or the wait for that word ran
     * out. Idempotent, and it starts the fade rather than only unblocking it, because the guard
     * has no other way to be told that the wait is over.
     */
    private static void releaseCoverFadeWait(String why) {
        if (!sCoverFadeWaiting) return;
        sCoverFadeWaiting = false;
        Main.main().removeCallbacks(sCoverFadeTimeout);
        Xp.log(Main.TAG + "cover fade released after "
                + (android.os.SystemClock.uptimeMillis() - sCoverFadeArmedAt) + "ms ("
                + why + "), owed " + sCoverFadeWaitMs + "ms");
        // Which way the transition runs is whichever the cover is owed: the view on its way out
        // is held by sCoverFadingOut, everything else by the owed fade-in.
        if (sCoverFadingOut != null) {
            startCoverFadeOut((ImageView) sCoverFadingOut);
            return;
        }
        if (sOwedSwap != null) {
            releaseOwedSwap();
            return;
        }
        startCoverFade(Main.sCover);
    }

    /**
     * The one place the wallpaper window's head start is measured: how long after the art was
     * pushed does this process say the player has been rebuilt. Recorded in every mode, because
     * FADE_MODE_STRETCH spends the next transition's length on it and is not itself waited on.
     */
    static void noteVideoReload() {
        sVideoReloadSignals++;
        long gap = android.os.SystemClock.uptimeMillis() - sCoverFadeArmedAt;
        // Only a fresh one counts: a push that armed nothing (a track change on a view that is
        // already up and opaque) leaves the timestamp where it was, and the difference from that
        // would be however long ago the last real transition was.
        // Fresh, and an entry: the way out has no encode in it, so its reload lands sooner and
        // measuring the stretch for the next transition off THAT would make the next cover's
        // dissolve too short.
        if (sCoverFadingOut == null && sCoverFadeArmedAt > 0 && gap > 0 && gap < 5000L) {
            sCoverFadeGapMs = gap;
        }
        releaseCoverFadeWait("the wallpaper window is reloading");
    }

    /**
     * The wallpaper window is about to take a new cover: its player rebuild is some 180ms from a
     * first frame. A track change's crossfade inside the view starts here rather than on
     * videoreload. The window swaps in one frame and the cards and notifications blur the
     * window, so a crossfade begun after the swap left them a whole fade ahead of the background
     * behind them - reported as the card changing before the cover. Begun now, the window's swap
     * lands about half way through the view's.
     */
    static void noteVideoReloading() {
        if (sOwedSwap != null && sCoverFadeWaiting) {
            releaseCoverFadeWait("the wallpaper window is about to reload");
        }
    }

    /** Starts the owed fade, if the cover is on screen for it. See sCoverFadeWaitMs. */
    private static void startCoverFade(final View cover) {
        long ms = sCoverFadeWaitMs;
        if (ms <= 0 || cover == null) return;
        // Held until the wallpaper window says it is reloading, so that the half of the
        // transition the eye sees and the half the cards' blur samples start together.
        if (sCoverFadeWaiting) return;
        if (cover.getVisibility() != View.VISIBLE || !cover.isShown()) return;
        sCoverFadeWaitMs = 0;
        cover.setAlpha(0f);
        cover.animate()
                .alpha(1f)
                .setDuration(ms)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        Xp.log(Main.TAG + "video cover fading in over " + ms + "ms, alpha "
                + Main.r2(cover.getAlpha()) + " -> 1");
    }

    /**
     * What is really on the lock screen in the video path - the one thing a screenshot cannot
     * answer.
     *
     * Our ImageView and MIUI's own TextureViews are children of the SAME layer, and which of them
     * the eye is reading is the order they sit in, not the fact that both are VISIBLE. The same
     * goes for the notif card's blur, which is painted from the wallpaper WINDOW and therefore
     * cannot be moved by anything we animate here. Reported by the state dump so that "the fade
     * is not visible" can be answered from the device instead of argued about.
     */
    static String describeVideoCover() {
        StringBuilder out = new StringBuilder();
        View iv = Main.sCover;
        if (iv == null) {
            out.append("no view");
        } else {
            ViewGroup parent = iv.getParent() instanceof ViewGroup ? (ViewGroup) iv.getParent() : null;
            int index = -1, count = 0;
            if (parent != null) {
                count = parent.getChildCount();
                for (int i = 0; i < count; i++) if (parent.getChildAt(i) == iv) index = i;
            }
            out.append(visOf(iv)).append(" alpha=").append(Main.r2(iv.getAlpha()))
               .append(" ").append(iv.getWidth()).append("x").append(iv.getHeight());
            if (parent != null) {
                out.append(" child ").append(index + 1).append("/").append(count)
                   .append(index == count - 1 ? " (top - ours is the visible one)" : " (NOT top - MIUI's is)");
            } else {
                out.append(" not in a layer");
            }
        }
        out.append("\n  owed fade=").append(sCoverFadeWaitMs).append("ms")
           .append(sCoverFadeWaiting ? " (held for the wallpaper window)" : "")
           .append("; fade mode=").append(fadeModeName())
           // The measured wallpaper head start, which is also the number the stretch mode spends
           // on the next transition's dissolve.
           .append(" (last gap ").append(sCoverFadeGapMs).append("ms)")
           .append(sCoverFadingOut != null ? " (fading out)" : "")
           .append("; reload signals=").append(sVideoReloadSignals)
           .append("; MIUI bg TextureView=").append(visOf(Main.sVideoBg))
           .append(" alpha=").append(Main.r2(alphaOf(Main.sVideoBg)))
           .append(" fg TextureView=").append(visOf(Main.sVideoFg))
           .append(" alpha=").append(Main.r2(alphaOf(Main.sVideoFg)))
           .append("; lock wallpaper=").append(Main.sVideoWallpaper ? "live (fastplayer)" : "still (texture)");
        return out.toString();
    }

    private static String visOf(View v) {
        if (v == null) return "absent";
        if (v.getVisibility() == View.VISIBLE) return "VISIBLE";
        return v.getVisibility() == View.INVISIBLE ? "INVISIBLE" : "GONE";
    }

    private static float alphaOf(View v) {
        return v == null ? -1f : v.getAlpha();
    }

    /**
     * MIUI's own two TextureViews, hidden while our cover is over them.
     */
    private static void hideVideoSurfaces() {
        View bg = Main.sVideoBg, fg = Main.sVideoFg;
        if (bg != null && bg.getVisibility() != View.INVISIBLE) {
            bg.setVisibility(View.INVISIBLE);
        }
        if (fg != null && fg.getVisibility() != View.INVISIBLE) {
            fg.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * The cover on the way out: the view that is already on screen is held and faded out, and
     * detached when the fade ends.
     *
     * The way back for a live wallpaper is a reload - the player is pointed at the user's own
     * video again and the window changes in one frame, with nothing in this process able to fade
     * it (the still path has a texture in the wallpaper process it can crossfade there; a video
     * wallpaper has only its player). Done in one frame that is the whole lock screen changing
     * at once while the clock is still springing back, which is the same complaint the way in
     * was fixed for. So the view that is already covering the window is the fade: it is held
     * until WallpaperProbe says the window is reloading, then it dissolves, and the window's own
     * cut happens underneath it while it is still opaque.
     *
     * Detaching immediately, which is what this used to do, is FADE_MODE_OFF.
     */
    private static void armCoverFadeOut() {
        final ImageView iv = Main.sCover;
        if (iv == null || Main.sFadeMode == Main.FADE_MODE_OFF) {
            dropVideoCover();
            return;
        }
        sCoverFadingOut = iv;
        // Nothing is owed to a view that is leaving: the length here is the transition's.
        sCoverFadeWaitMs = 0;
        sCoverFadeArmedAt = android.os.SystemClock.uptimeMillis();
        // Held whatever the mode: on the way out the view has to still be up when the window
        // swaps back, or the swap is a cut. Only FADE_MODE_OFF, above, skips it.
        sCoverFadeWaiting = true;
        Xp.log(Main.TAG + "cover fade-out armed, mode=" + fadeModeName()
                + (sCoverFadeWaiting ? " (held for the wallpaper window)" : ""));
        Main.main().removeCallbacks(sCoverFadeTimeout);
        if (!sCoverFadeWaiting) {
            startCoverFadeOut(iv);
            return;
        }
        Main.main().postDelayed(sCoverFadeTimeout, COVER_FADE_SIGNAL_TIMEOUT_MS);
    }

    /** One fade-out, once. See armCoverFadeOut(). */
    private static void startCoverFadeOut(final ImageView iv) {
        if (iv == null || sCoverFadingOut != iv) return;
        if (!iv.isShown() || iv.getAlpha() <= 0f) {
            sCoverFadingOut = null;
            // Only if it is still the cover: a keyguard rebuild replaces sCover underneath this,
            // and detaching whatever is there now would take the lock screen's cover away. Same
            // guard onCoverFadeOutEnd() has, for the same reason.
            if (Main.sCover == iv) dropVideoCover();
            return;
        }
        long ms = coverFadeMs();
        iv.animate().cancel();
        iv.animate()
                .alpha(0f)
                .setDuration(ms)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> onCoverFadeOutEnd(iv))
                .start();
        Xp.log(Main.TAG + "video cover fading out over " + ms + "ms");
    }

    /**
     * The end of a fade-out. Guarded on the view it was started for: a cover put back up while
     * this one was dissolving owns sCover, and detaching that one would take the lock screen's
     * cover away instead.
     */
    private static void onCoverFadeOutEnd(final View faded) {
        if (sCoverFadingOut != faded) return;
        sCoverFadingOut = null;
        dropVideoCover();
        Xp.log(Main.TAG + "video cover faded out");
    }

    /**
     * The end of a video cover that is going for good: the view comes off, and the wallpaper's
     * cut-out subject comes back with it - unless cover mode has come back in the meantime, in
     * which case the next cover is already hiding it again.
     */
    private static void dropVideoCover() {
        Main.detachCover();
        if (!Main.sCoverMode) Main.setDepthHidden(false);
    }

    /** A track change's crossfade, waiting for the window. See armCoverSwap(). */
    static volatile Runnable sOwedSwap;

    private static void recycleUnless(Bitmap b, Bitmap keep1, Bitmap keep2) {
        if (b != null && b != keep1 && b != keep2 && !b.isRecycled()) b.recycle();
    }

    /**
     * Takes a or b off the view before they are recycled: a crossfade still holding one of them
     * is put straight to its last layer, which is where it was going.
     */
    private static void settleOff(ImageView iv, Bitmap a, Bitmap b) {
        android.graphics.drawable.Drawable d = iv.getDrawable();
        if (!(d instanceof android.graphics.drawable.LayerDrawable)) return;
        android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
        boolean holds = false;
        for (int i = 0; i < ld.getNumberOfLayers(); i++) {
            android.graphics.drawable.Drawable l = ld.getDrawable(i);
            if (l instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap lb = ((android.graphics.drawable.BitmapDrawable) l).getBitmap();
                if (lb != null && (lb == a || lb == b)) holds = true;
            }
        }
        if (!holds) return;
        android.graphics.drawable.Drawable last = ld.getDrawable(ld.getNumberOfLayers() - 1);
        Bitmap end = last instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) last).getBitmap() : null;
        if (end != null && end != a && end != b && !end.isRecycled()) {
            iv.setImageBitmap(end);
        } else {
            iv.setImageDrawable(null);
            Xp.log(Main.TAG + "cover crossfade held only old bitmaps, cleared before the recycle");
        }
    }

    /**
     * Owes the view a crossfade from the cover it shows to the new one, started when the
     * wallpaper window has the new one as well (or the wait times out). The old bitmaps are the
     * crossfade's first layer, so they are only given back once it has run.
     */
    private static void armCoverSwap(final ImageView iv, final Bitmap from, final Bitmap to,
                                     final Bitmap oldSharp, final Bitmap oldBlur,
                                     final Bitmap full, final Bitmap frosted) {
        final Runnable run = () -> {
            if (Main.sCover != iv || from.isRecycled() || to.isRecycled()) {
                if (!to.isRecycled()) iv.setImageBitmap(to);
            } else {
                applyVideoCoverCrossfade(iv, from, to, Main.sVideoCoverBlurred);
            }
            final long dur = Math.max(150, Math.min(500, coverFadeMs())) + 100L;
            Gen.postDelayedOn(iv, () -> {
                // The timer is a guess at when the crossfade is done, and it is wrong for a view
                // that stopped being drawn part way through (hidden on the way to the desktop):
                // its transition is still where it was, and would draw the old layer next.
                settleOff(iv, oldSharp, oldBlur);
                recycleUnless(oldSharp, Main.sCoverBitmap, Main.sCoverBlurBitmap);
                if (oldBlur != oldSharp) {
                    recycleUnless(oldBlur, Main.sCoverBitmap, Main.sCoverBlurBitmap);
                }
            }, dur);
        };
        Runnable superseded = sOwedSwap;
        sOwedSwap = run;
        if (superseded != null) {
            // A skip before the last one landed: the view jumps to where the last one was going,
            // and this one runs from there. Its old bitmaps are given back by its own runnable.
            superseded.run();
        }
        sCoverFadeArmedAt = android.os.SystemClock.uptimeMillis();
        Main.main().removeCallbacks(sCoverFadeTimeout);
        sCoverFadeWaiting = Main.sFadeMode == Main.FADE_MODE_HOLD;
        if (!sCoverFadeWaiting) {
            releaseOwedSwap();
            return;
        }
        Main.main().postDelayed(sCoverFadeTimeout, COVER_FADE_SIGNAL_TIMEOUT_MS);
        Xp.log(Main.TAG + "cover swap armed, held for the wallpaper window");
    }

    private static void releaseOwedSwap() {
        Runnable r = sOwedSwap;
        sOwedSwap = null;
        if (r != null) r.run();
    }

    /** The view being faded out on the way back to the wallpaper. See armCoverFadeOut(). */
    static volatile View sCoverFadingOut;

    /**
     * Which album is on the cover view, as the 8x8 print artPrint() takes. Compared against the
     * one the next push carries, so that a track change fades and a re-composition of the same
     * album (the app's bias slider, a resend after a keyguard rebuild) does not.
     */
    static volatile int sShownArtPrint;

    /**
     * The live wallpaper's own TextureViews: hidden while the cover is over them, given back
     * when it goes.
     *
     * Giving them back only counts while the lock screen is actually in front.
     * keyguard_background_layer is not the keyguard's alone - the shade draws it as its own
     * backdrop over the desktop, which is how the cover could turn up behind the Control
     * Centre - so writing VISIBLE there off the lock screen leaves a surface on screen that
     * nobody is feeding. Measured on the device: exactly one bad frame, because MIUI sets it
     * straight back the moment that layer is drawn, which is why only the FIRST pull-down
     * after unlocking showed a stray wallpaper frame and the second was clean.
     *
     * So a hand-back we cannot make now is remembered instead, and paid the next time the
     * keyguard is in front (screen on, or a keyguard rebuild). That debt is the safety net the
     * old unconditional restore was: what must never happen is the wallpaper hidden with no
     * cover over it, which is a black lock screen.
     */
    static void setVideoSurfacesHidden(boolean hide, View bg, View fg) {
        if (!hide && !Main.onKeyguardNow()) {
            if (Main.sVideoWallpaper && !Main.sVideoWpOwed) {
                Main.sVideoWpOwed = true;
                Xp.log(Main.TAG + "off the lock screen: leaving the live wallpaper as MIUI left it,"
                        + " hand-back deferred");
            }
            return;
        }
        int vis = hide ? View.INVISIBLE : View.VISIBLE;
        if (bg != null) bg.setVisibility(vis);
        if (fg != null) fg.setVisibility(vis);
        Main.sVideoWpOwed = false;
    }

    static void releaseCoverGuard() {
        View c = sCoverGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCoverGuard;
        sCoverGuarded = null;
        sCoverGuard = null;
        if (c == null || g == null) return;
        try {
            Gen.unpreDraw(c, g);
        } catch (Throwable ignored) {
        }
    }

    private static View sCoverGuarded;
    private static ViewTreeObserver.OnPreDrawListener sCoverGuard;

    /** keyguard_background_layer, which sits behind the whole clock stack. */
    static ViewGroup coverLayer() {
        View v = Main.sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(
                "keyguard_background_layer", "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        return layer instanceof ViewGroup ? (ViewGroup) layer : null;
    }

    /**
     * The TextureView a live wallpaper is drawn into, in one of the keyguard's layers. It
     * carries no id - it is added in code by VideoDepthSurfaceHolder - so it is found by type,
     * and there is only ever one per layer.
     */
    static View videoSurfaceView(String layerId) {
        View v = Main.sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(layerId, "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        if (!(layer instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) layer;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof android.view.TextureView) return g.getChildAt(i);
        }
        return null;
    }

    /** One switch to the wallpaper process, for callers outside this file. */
    public static void sendToWallpaper(String op, boolean on) {
        sendToWallpaper(op, on, 0L);
    }

    /**
     * The same, with the time the switch was decided at.
     *
     * For an answer that can also travel on a cover push: the push is built on the worker and
     * this is sent from the main thread, so the two can cross, and the one that arrives last is
     * not the one that was decided last. 0 = no time, for a switch that is not one of those.
     */
    public static void sendToWallpaper(String op, boolean on, long decidedAt) {
        Context c = Main.sAppCtx;
        if (c == null) return;
        Intent out = wallpaperIntent(op);
        out.putExtra("on", on);
        if (decidedAt > 0L) out.putExtra("blurseq", decidedAt);
        ProbeGuard.send(c, out);
    }

    static Intent wallpaperIntent(String op) {
        Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.WPROBE");
        out.setPackage("com.miui.miwallpaper");
        out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        out.putExtra("op", op);
        out.putExtra("reload", true);
        // Which kind of lock wallpaper this push is for. The wallpaper process cannot work it
        // out for itself - it only knows which engines it has BUILT, and those are built once
        // per process - and this side re-reads it before every push anyway. See videoPath().
        out.putExtra("video", Main.sVideoWallpaper);
        out.putExtra("shared", sSharedLock);
        // For a shared still: the wallpaper process's own reading of the keyguard was measured
        // false with the phone locked, and its engine is only told at the next lock.
        if (sSharedLock) out.putExtra("locked", Main.keyguardShowing());
        return out;
    }

    /**
     * Hands the wallpaper process the crossfade that belongs to the current response.
     *
     * It has to be told. Its own copy is a static that starts at 370 and is never persisted,
     * so a wallpaper process that restarts would fade on the number that goes with 0.38 while
     * the clock, the card and everything else moved on the slider's - which is the desync the
     * two of them were moved together to remove, arriving silently instead.
     *
     * Called when the setting changes, and again on every cover entry so a restart is caught
     * before it can be seen.
     */
    static void pushFadeMs(Context ctx) {
        if (ctx == null) return;
        ProbeGuard.send(ctx, wallpaperIntent("fadems")
                .putExtra("v", (int) Main.fadeMsFor(Main.sClockResponse)));
        Xp.log(Main.TAG + "wallpaper fade = " + Main.fadeMsFor(Main.sClockResponse) + "ms for response "
                + Main.sClockResponse);
    }

    /** Asks the wallpaper process to re-upload the art it already has on disk. */
    static void requestWallpaperReload(Context ctx) {
        ProbeGuard.send(ctx, wallpaperIntent("reload"));
        Xp.log(Main.TAG + "wallpaper reload requested");
    }

    /**
     * Composes and pushes off the main thread.
     *
     * Getting the RIGHT art is the fiddly part of a track change, not getting art at all.
     * Players fill the session bitmap in asynchronously, so at the instant the metadata arrives
     * there is often nothing there - and the media card thumbnail we fall back to is still the
     * PREVIOUS track (verified on device: the wallpaper came up as the last album while the card
     * already read the new one). So attempts are spaced out, the session is preferred over the
     * card until the last one, and artwork identical to what is already on the wallpaper is read
     * as "not updated yet" rather than accepted.
     *
     * That budget only means anything for a player that HAS a bitmap to fill in. One that
     * publishes only an artwork URI never will, and for it the session is not a source at all -
     * Bilibili hands over an i1.hdslb.com URL and no bitmap, on 2309 of the 2690 session reads
     * in a day's log - so every try spent on the session is spent knowing it comes back empty.
     * Measured before this was understood: 143 video switches waited an average of 1.6s, and 128
     * of them burned all fourteen tries, with the wallpaper sitting on the previous video the
     * whole time, before the card thumbnail was ever consulted once.
     */
    private static final int ART_TRIES = 14;
    /**
     * Checking costs a metadata read, so poll rather than wait in lumps: the player usually
     * fills the session bitmap in within a few hundred ms, and 700ms steps meant a track change
     * that missed by 50ms still cost the full 700. Fourteen tries covers the same ~1.6s window.
     */
    private static final long ART_RETRY_MS = 120L;
    /** When a skip was last asked for, and which way. See the TransportControls hook. */
    private static volatile long sSkipAt;
    private static volatile int sSkipDir;

    /**
     * Someone asked the player to change track. Runs on whatever thread made the call, so it does
     * nothing but write the two fields down.
     */
    static void noteSkip(int dir) {
        sSkipAt = android.os.SystemClock.uptimeMillis();
        sSkipDir = dir;
        if (!Main.sCoverMode || !Main.screenOn()) return;
        // What the queue says is coming. Null for a player that publishes no queue, or a track
        // whose artwork has not been fetched yet - and then this does nothing and the cover waits
        // for the player exactly as it used to.
        final Bitmap art = Prefetch.take(dir);
        if (art == null) return;
        final Context ctx = Main.sAppCtx;
        if (ctx == null) return;
        // Superseding anything in flight, the way a real track change does: this IS the track
        // change, ~0.8s before the player will admit to it.
        final int gen = ++Main.sPushGen;
        Main.sCtTrack = sSkipAt;
        Main.sCtArt = sSkipAt;
        Main.sCtTries = 0;
        Main.sCtCheckMs = 0L;
        Main.worker().post(new Runnable() {
            @Override
            public void run() {
                if (gen != Main.sPushGen) return;
                pushArtToWallpaper(ctx, true, art);
            }
        });
    }

    /** What the wallpaper currently shows, coarsely, so a stale source can be recognised. */
    private static volatile int sArtPrint;
    /** The last composed cover's inputs and the clock tint measured off it. Worker thread only. */
    private static String sTintKey = "";
    /** The composition the tint is read from, at 1/TINT_SCALE of the screen. Worker only. */
    private static Bitmap sTintPic;
    private static final int TINT_SCALE = 4;
    /**
     * The size of that push, and the track it belonged to, so a WORSE source for the same track
     * can be refused.
     *
     * The fingerprint above cannot see a downgrade: the same cover at another size is a
     * different 8x8 hash, so it reads as a new picture. Measured on bilibili, which publishes an
     * 800x480 copy in its session while the video is in the foreground and later drops it, by
     * which point the media card's thumbnail holds a 144x86 one - the same cover at a fifth of
     * the width, composed into the same 1200x2608 wallpaper as an 8x upscale instead of a 1.5x
     * one. That is a visible loss of sharpness, and it arrives as a "new track", so without this
     * the cover gets worse while the track stays the same.
     */
    private static volatile int sArtW;
    private static volatile int sArtH;
    private static volatile int sArtLong;
    private static volatile String sArtKey = "";
    /**
     * Where a composed cover is handed to the wallpaper process. See writeSharedArt().
     *
     * MIUI's own wallpaper tree: mode 0777 and owned by the wallpaper app, and measured from
     * inside both processes - SystemUI writes and the wallpaper process reads, under Enforcing
     * SELinux and with the file made world-readable.
     */
    static final String SHARE_DIR = "/data/system/theme_magic/users/0/wallpaper";
    static final String SHARE_FILE = "hyperchanger_hmc_next_art_shared.jpg";

    /**
     * Puts a composed cover where the wallpaper process can read it, and answers the path - or
     * null when this process cannot write there, which sends the caller back to the broadcast.
     *
     * Readable by everyone on purpose: the two processes do not share a uid.
     */
    private static String writeSharedArt(byte[] jpg) {
        try {
            // Written beside it and renamed over it, as writeSource does: the wallpaper process
            // may still be reading the last track's file when a quick skip writes the next one.
            java.io.File f = new java.io.File(SHARE_DIR, SHARE_FILE);
            java.io.File tmp = new java.io.File(SHARE_DIR, SHARE_FILE + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(jpg);
            }
            tmp.setReadable(true, false);
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp + " -> " + f);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Xp.w(Main.TAG + "shared art write failed, carrying it in the broadcast: " + t);
            return null;
        }
    }

    static final String SHARE_SOURCE = "hyperchanger_hmc_next_src.raw";

    /** A late artwork update can recover a cover whose initial retry window found no image. */
    private static volatile long sMissingArtRetryAt;

    static void retryMissingArt() {
        if (!Main.sCoverMode || sArtPrint != 0 || Main.sCtArt == 0L) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - Main.sCtArt < 750L || now - sMissingArtRetryAt < 5000L) return;
        sMissingArtRetryAt = now;
        Xp.log(Main.TAG + "cover art still missing; retrying after media update");
        pushArtAsync(true, true);
    }

    /** The source, where the wallpaper process can read it. See CoverCompose.writeSource(). */
    private static String writeSharedSource(Bitmap src, int w, int h, float bias)
            throws java.io.IOException {
        java.io.File f = new java.io.File(SHARE_DIR, SHARE_SOURCE);
        CoverCompose.writeSource(f, src, w, h, bias);
        return f.getAbsolutePath();
    }

    /** Where a precompose's source goes: its own file, so a real push's is never written over. */
    static final String SHARE_PRE_SOURCE = "hyperchanger_hmc_next_src_pre.raw";
    /**
     * The last precompose's inputs and when it went. Written on the worker; read on the main
     * thread too, by the tap's own message (sendKeptFirst).
     */
    private static volatile String sPreKey = "";
    private static volatile long sPreAt;
    /**
     * The picture the wallpaper process has kept, as far as this side knows: its key, the song
     * the session was on, the screen, bias and mode, and until when it is kept there. Set by a
     * precompose (kept 60s there) and by every push (kept while the cover shows it, and 30s
     * after it goes). What the tap's own message asks for (sendKeptFirst).
     */
    private static volatile String sKeptKey = "";
    private static volatile String sKeptTitle;
    private static volatile String sKeptLayout = "";
    private static volatile long sKeptUntil;
    private static final long KEPT_AFTER_PRE_MS = 50_000L;
    private static final long KEPT_AFTER_OFF_MS = 25_000L;

    private static void noteKept(String key, String layout, long until) {
        sKeptTitle = Main.watchedTitle();
        sKeptLayout = layout;
        sKeptKey = key;
        sKeptUntil = until;
    }
    private static volatile boolean sPrePending;
    /** Shorter than the wallpaper process keeps it (PRECOMPOSED_KEEP_MS), so it is never gone. */
    private static final long PRE_RESEND_MS = 50_000L;

    /**
     * The lock screen is up with a media card and no cover: hand the wallpaper process this
     * song's source ahead of a tap, so entering cover mode finds the composition made
     * (WallpaperProbe.precompose) instead of composing between the tap and the fade - that wait
     * is the blurred background coming up late behind the clock, and on a slower phone the
     * compose is most of it. The wake and every media update ask; the same art is sent once.
     *
     * [delayMs] keeps it off the frames that asked: a wake's first ones are the busiest there are.
     */
    static void precomposeAsync(final long delayMs) {
        final Context ctx = Main.sAppCtx;
        if (ctx == null || sPrePending || !precomposeWanted()) return;
        sPrePending = true;
        Main.worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                sPrePending = false;
                if (!precomposeWanted() || !Main.keyguardShowing()) return;
                android.os.Trace.beginSection("MC push.precompose");
                try {
                    // The session's own bitmap only: the card lags a track change and can still
                    // be the last album, and a guess that misses is work for nothing.
                    Bitmap art = AlbumArt.albumArt(ctx, false);
                    if (art == null) return;
                    int w = Main.sScreenW, h = Main.sScreenH;
                    boolean card = Main.sCoverCardStyle.mode == CoverCardStyle.CARD;
                    String key = preKey(art, w, h, card);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (key.equals(sPreKey) && now - sPreAt < PRE_RESEND_MS) return;
                    Bitmap src = CoverCompose.prepareSource(art, w);
                    java.io.File f = new java.io.File(SHARE_DIR, SHARE_PRE_SOURCE);
                    CoverCompose.writeSource(f, src, w, h, Main.sBias);
                    Intent out = wallpaperIntent("precompose");
                    out.putExtra("src", f.getAbsolutePath());
                    out.putExtra("cardmode", card);
                    out.putExtra("prekey", key);
                    ProbeGuard.send(ctx, out);
                    sPreKey = key;
                    sPreAt = now;
                    noteKept(key, layoutKey(w, h, card), now + KEPT_AFTER_PRE_MS);
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "precompose failed: " + t);
                } finally {
                    android.os.Trace.endSection();
                }
            }
        }, delayMs);
    }

    private static String layoutKey(int w, int h, boolean card) {
        return w + "x" + h + ":" + Main.sBias + ":" + card;
    }

    /**
     * The tap's own message, sent from the main thread the moment cover mode is asked for: "the
     * picture you were handed ahead of this - show it". Only when that picture is still this
     * song's, for this screen, bias and mode.
     *
     * The push it goes ahead of runs on the worker, and on a busy phone the worker waited
     * 60-270ms to be run at all between reading the artwork and handing it over, with the clock
     * already moving (trace 2026-10-20: tap to background 60-554ms, all of the spread in three
     * queues). This skips that queue and the wallpaper process's composer queue both.
     *
     * Sent once cover mode is on (Main.setCoverEnabled, after enterCoverMode), not with the push:
     * the lyrics decide the frost as they attach, and a message sent before that carried "sharp"
     * - the cover faded in sharp, then the frost waited that fade out and faded again, and the
     * lyrics waited for both (2026-10-20). The push
     * still goes - the clock's tint and the shade are made there - and carries the same key, so
     * a wallpaper process that already put this picture up lets it pass; one that could not
     * (restarted, the kept picture let go) takes the push as it always did.
     */
    static void sendKeptFirst() {
        Context ctx = Main.sAppCtx;
        if (ctx == null || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        String key = sKeptKey;
        if (key.isEmpty() || !Main.sWpComposes || Main.sVideoWallpaper || Main.keepsWallpaper()) {
            return;
        }
        if (android.os.SystemClock.uptimeMillis() >= sKeptUntil) return;
        boolean card = Main.sCoverCardStyle.mode == CoverCardStyle.CARD;
        if (!layoutKey(Main.sScreenW, Main.sScreenH, card).equals(sKeptLayout)) return;
        String title = sKeptTitle;
        if (title == null || !title.equals(Main.watchedTitle())) return;
        try {
            Intent out = wallpaperIntent("art");
            out.putExtra("cardmode", card);
            LockLyrics.putBlurOn(out);
            out.putExtra("fade", Main.sFadeWp && Main.sScreenOn);
            out.putExtra("prekey", key);
            ProbeGuard.send(ctx, out);
            Xp.log(Main.TAG + "pushart: the kept cover asked for from the tap");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "the tap's cover message failed, the push carries it: " + t);
        }
    }

    /** What a precompose's file holds, as one string: the art, the screen, the bias, the mode. */
    private static String preKey(Bitmap art, int w, int h, boolean card) {
        return artPrint(art) + ":" + art.getWidth() + "x" + art.getHeight()
                + ":" + w + "x" + h + ":" + Main.sBias + ":" + card;
    }

    /**
     * The precompose's file, when it holds exactly the source this push would write; null when
     * it does not. Worker thread, as both writers are, so the file cannot change under this.
     */
    private static String preSourceFor(Bitmap art, int w, int h) {
        if (sPreKey.isEmpty()) return null;
        boolean card = Main.sCoverCardStyle.mode == CoverCardStyle.CARD;
        if (!preKey(art, w, h, card).equals(sPreKey)) return null;
        java.io.File f = new java.io.File(SHARE_DIR, SHARE_PRE_SOURCE);
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    /** Only where a cover would be composed from the source by the wallpaper process. */
    private static boolean precomposeWanted() {
        return !Main.sCoverMode && Main.sCoverFeatureOn && Main.sWpComposes && Main.sScreenOn
                && !Main.sVideoWallpaper && !Main.keepsWallpaper() && MediaCard.sCardPresent;
    }

    static void pushArtAsync(final boolean on, final boolean fresh) {
        final Context ctx = Main.sAppCtx;
        if (ctx == null) return;
        final int gen = ++Main.sPushGen;
        if (!on) {
            sMissingArtRetryAt = 0L;
            sArtPrint = 0;
            sArtW = 0;
            sArtH = 0;
            sArtLong = 0;
            sArtKey = "";
            // An immersive page fading in over the cover: the cover stays until the page covers
            // it, or the wallpaper shows through between the two. A push that comes in the
            // meantime - a tap back into cover mode - supersedes this one, and the cover never
            // leaves at all.
            long hold = 0L;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                hold = ImmersiveHost.coverOffHoldMs();
            }
            if (hold > 0L) Xp.log(Main.TAG + "pushart off held " + hold + "ms for the page over it");
            final boolean held = hold > 0L;
            Main.worker().postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Only a held leave is dropped: an unheld one goes as it always did, and the
                    // quick toggles without a page keep the path they were tuned on.
                    if (held && gen != Main.sPushGen) {
                        Xp.log(Main.TAG + "pushart off superseded, the cover stays");
                        return;
                    }
                    ImmersiveHost.coverOffSent();
                    pushArtToWallpaper(ctx, false, null);
                }
            }, hold);
            return;
        }
        // Checked before every push rather than once per process. The user can send the
        // wallpaper back to "follow home" at any moment - from Settings, a theme, the wallpaper
        // carousel - and that silently removes the keyguard renderer this whole thing hangs off.
        // A cached "already fine" would mean the module never noticed and the user had to go
        // press a repair button, which is exactly what should not be necessary.
        Main.worker().post(new Runnable() {
            @Override
            public void run() {
                // Timed because it is the first thing on this worker and the artwork read is
                // queued behind it: whatever it costs, the track change pays before it starts.
                long t = android.os.SystemClock.uptimeMillis();
                android.os.Trace.beginSection("MC push.ensureLock");
                try {
                    ensureLockWallpaper(ctx);
                } finally {
                    android.os.Trace.endSection();
                }
                Main.sCtCheckMs = android.os.SystemClock.uptimeMillis() - t;
            }
        });
        // Not a track change (a bias tweak, a manual pushart): take whatever is there now.
        tryPushArt(ctx, fresh ? 0 : ART_TRIES - 1, fresh, gen);
    }

    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen) {
        tryPushArt(ctx, attempt, fresh, gen, false);
    }

    /**
     * allowCard travels with the attempt because the first one is where the session gets judged.
     * When it answers with no bitmap there is nothing left to wait for on that side, and the
     * card thumbnail - which until then was read on the very last try only - can answer from the
     * next one instead. The try budget is untouched, so the worst case is exactly what it was;
     * what changes is that the common case stops spending it. The session is still asked first
     * on every attempt, so a player that does fill its bitmap in late is still picked up.
     */
    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen, final boolean allowCard) {
        Main.worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != Main.sPushGen) {
                    Xp.log(Main.TAG + "art push superseded, dropping it");
                    return;
                }
                boolean last = attempt >= ART_TRIES - 1;
                int[] sessionBits = new int[1];
                android.os.Trace.beginSection("MC push.read");
                Bitmap art;
                int print;
                try {
                    art = AlbumArt.albumArt(ctx, last || allowCard, sessionBits);
                    print = art == null ? 0 : artPrint(art);
                } finally {
                    android.os.Trace.endSection();
                }
                boolean stale = fresh && art != null && sArtPrint != 0 && print == sArtPrint;
                // The same track, and the copy being offered is smaller than the one already on
                // the wallpaper. Only on a fresh push: a non-fresh one is an explicit "hand it
                // over again" - after the wallpaper process restarted it may have nothing at all
                // - and refusing there would leave the lock screen with no cover rather than a
                // soft one. "The same track" is sameTrack()'s question, and it has to be asked as
                // loosely as that: the keys one track arrives under disagree about everything but
                // the package and the title.
                boolean worse = fresh && art != null && sArtPrint != 0 && !stale
                        && sArtLong > 0 && Main.sameTrack(sArtKey, Main.sTrackKey)
                        && Math.max(art.getWidth(), art.getHeight()) < sArtLong;
                if ((art == null || stale || worse) && !last) {
                    // -2 is "a session was there and published neither a bitmap nor an artwork
                    // URI": nothing on that side is coming, so the card is read from the next try.
                    // 0 is the opposite case - art published as a URI and being read right now -
                    // and is deliberately not in here: what it needs is the wait, and it ends by
                    // itself when the read lands or fails. -1 is "there was nothing to ask", which
                    // more tries might. Once the card is in it stays in, so this is worth looking
                    // at on any attempt - the session can turn up late - and declaring it once
                    // keeps the line off the log.
                    boolean bare = sessionBits[0] == -2 && !allowCard;
                    if (bare) {
                        Xp.log(Main.TAG + "session carries no bitmap and no artwork URI,"
                                + " reading the card from here");
                    }
                    Xp.log(Main.TAG + "art " + (art == null ? "not ready"
                                    : stale ? "still the old one" : "smaller than the one up")
                            + ", retrying (" + (attempt + 2) + "/" + ART_TRIES + ")");
                    tryPushArt(ctx, attempt + 1, fresh, gen, allowCard || bare);
                    return;
                }
                if (stale) {
                    // The next track off the same album really does have the same cover.
                    Xp.log(Main.TAG + "same artwork as the last track, wallpaper left alone");
                    return;
                }
                if (worse) {
                    // Waiting the tries out was the point: the session's own copy is often a
                    // moment behind the card's. It did not turn up, and the wallpaper keeps the
                    // bigger copy it already has - which is this same track's cover.
                    Xp.log(Main.TAG + "art " + art.getWidth() + "x" + art.getHeight()
                            + " is smaller than the " + sArtW + "x" + sArtH
                            + " already up for this track, wallpaper left alone");
                    return;
                }
                // Only when something is really going out. A push with no art leaves the
                // wallpaper showing what it already showed, and recording 0 here would claim it
                // was empty and disarm the stale-art check on the next track change.
                if (art != null) {
                    sArtPrint = print;
                    sArtW = art.getWidth();
                    sArtH = art.getHeight();
                    sArtLong = Math.max(sArtW, sArtH);
                    sArtKey = Main.sTrackKey;
                }
                Main.sCtTries = attempt + 1;
                Main.sCtArt = android.os.SystemClock.uptimeMillis();
                pushArtToWallpaper(ctx, true, art);
            }
        }, attempt == 0 ? 0L : ART_RETRY_MS);
    }

    /**
     * The whole cover mechanism rests on the lock screen having a wallpaper of its OWN. MIUI
     * only builds a KeyguardImageEngineImpl - and with it the keyguard renderer whose texture we
     * replace - when the lock screen wallpaper is separate from the home one. Share one between
     * them and the wallpaper process creates a single engine with isLockScreen=false, so there
     * is nothing to hook and the cover silently does nothing at all.
     *
     * This is not hypothetical: the lock wallpaper got reset to "same as home" (dumpsys wallpaper
     * showed an empty "Lock wallpaper state" and mWhich=3), and the module went on reporting
     * cover mode on, art pushed, reload requested - with nowhere to draw it.
     *
     * Copying the current home wallpaper into the lock slot satisfies the requirement without
     * changing what the user sees: the lock screen keeps the same picture, it just owns a copy.
     */
    static boolean ensureLockWallpaper(Context ctx) {
        return ensureLockWallpaper(ctx, false);
    }

    /** When the check below last ran, and what it answered. See ensureLockWallpaper(). */
    private static volatile long sWpCheckedAt;
    private static volatile boolean sWpCheckedAnswer;

    /**
     * The lock screen has no wallpaper entry of its own and shows the desktop's still picture -
     * what a carousel applied to both screens leaves behind (锁屏画报's `gallery` on both slots,
     * #84). MIUI then builds no keyguard engine at all, so the wallpaper process has to put the
     * cover on the DESKTOP engine while the lock screen is up. The slot is not ours to write: the
     * carousel owns it. Carried to the wallpaper process on every message, like the video flag.
     */
    static volatile boolean sSharedLock;

    /**
     * How long that answer stands. The check is the first thing on the worker before every push,
     * and the artwork read is queued behind it, so its cost lands in front of every track change:
     * two wallpaper files opened and their headers decoded, MIUI's records parsed for both slots,
     * and half a dozen binder calls - all to notice something the user changes by hand, at most
     * once in a while.
     *
     * A few seconds is the whole point: pressing next repeatedly is exactly when the delay shows,
     * and it is also exactly when nothing about the wallpaper can have changed. Anything slower
     * than this still gets the full check, and a forced one never consults this at all.
     */
    private static final long WP_CHECK_TTL_MS = 5000L;

    @SuppressLint("MissingPermission")
    static boolean ensureLockWallpaper(Context ctx, boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && sWpCheckedAt != 0L && now - sWpCheckedAt < WP_CHECK_TTL_MS) {
            return sWpCheckedAnswer;
        }
        boolean r = checkLockWallpaper(ctx, force);
        sWpCheckedAt = android.os.SystemClock.uptimeMillis();
        sWpCheckedAnswer = r;
        return r;
    }

    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean checkLockWallpaper(Context ctx, boolean force) {
        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) return false;
        wallpaperDiag(ctx, wm, force ? "before forced copy" : "before push");
        // Before anything else, and before `force` too - forcing is the same setBitmap and
        // does the same damage. See wallpaperKind(): a live wallpaper on either slot cannot
        // survive being replaced with a still, and this is the only place that could do it.
        String lockKind = wallpaperKind(ctx, "lock");
        boolean live = Main.liveKind(lockKind);
        Main.sVideoWallpaper = live;
        boolean shared = false;
        try {
            shared = !live && sharesTheDesktopStill(ctx, wm, lockKind);
        } finally {
            if (shared != sSharedLock) {
                Xp.log(Main.TAG + (shared ? "the lock screen shows the desktop's own still ("
                        + lockKind + "), so the cover goes on the desktop engine while locked"
                        : "the lock screen no longer shares the desktop's wallpaper"));
            }
            sSharedLock = shared;
        }
        if (shared) return false;
        if (live) {
            // Nothing to repair, and nothing that may be written: setBitmap(FLAG_LOCK) would
            // unbind the live wallpaper for good. The cover still works - it just goes into
            // our own keyguard layer instead of the wallpaper process. See showVideoCover().
            Xp.log(Main.TAG + "the lock screen has a " + lockKind + " wallpaper of its own; leaving "
                    + "the wallpaper alone and drawing the cover in the keyguard layer");
            return false;
        }
        if (lockKind != null && !Main.KIND_IMAGE.equals(lockKind)) {
            // A still the image engine draws, but one some other app manages (锁屏画报's
            // `gallery`, ...): the cover goes on its texture as on any picture, but nothing here
            // writes to the slot - not the copy below, not the re-fit - forced or not.
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock == null) return false;
                try {
                    lock.close();
                } catch (Throwable ignored) {
                }
                return true;
            } catch (Throwable t) {
                Xp.log(Main.TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
        }
        if (lockKind == null) {
            // Nothing of its own on the lock slot, so it is showing the home wallpaper - and
            // if that one is live, the lock screen IS that live wallpaper. Copying "the home
            // wallpaper" would get a still frame of it at best and the stock picture at worst,
            // and either way the animation is gone.
            String homeKind = wallpaperKind(ctx, "home");
            if (homeKind != null && !Main.KIND_IMAGE.equals(homeKind)) {
                Xp.log(Main.TAG + "the lock screen is following a " + homeKind + " home wallpaper. "
                        + "Leaving it alone rather than replacing it with a still of itself.");
                return false;
            }
        }
        if (!force) {
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock != null) {
                    try {
                        lock.close();
                    } catch (Throwable ignored) {
                    }
                    // The slot being populated is all the COVER needs. What the transition out
                    // of it needs as well is for the picture in there to be the size of the
                    // screen - see fitLockWallpaperToScreen().
                    fitLockWallpaperToScreen(wm);
                    return true;
                }
            } catch (Throwable t) {
                Xp.log(Main.TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
            Xp.log(Main.TAG + "lock screen has no wallpaper of its own - the keyguard "
                    + "wallpaper engine cannot exist, so the cover has nowhere to go. Giving it a "
                    + "copy of the home wallpaper.");
        }
        try {
            Bitmap home = homeWallpaper(wm);
            if (home == null) {
                Xp.log(Main.TAG + "no home wallpaper bitmap to copy");
                return false;
            }
            // At the screen's own size, deliberately. Whatever goes in this slot becomes the
            // texture the keyguard uploads, and every track change then has to rescale the
            // composed cover to it - on both sides. The home wallpaper here is 1579x3432, which
            // cost ~100ms a track for nothing: the picture is only ever shown on this screen.
            Bitmap fitted = home.getWidth() == Main.sScreenW && home.getHeight() == Main.sScreenH
                    ? home : centerCrop(home, Main.sScreenW, Main.sScreenH);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            if (fitted != home) fitted.recycle();
            home.recycle();
            Xp.log(Main.TAG + "lock wallpaper set at " + Main.sScreenW + "x" + Main.sScreenH
                    + "; the keyguard engine will be rebuilt");
            wallpaperDiag(ctx, wm, "after copying home into lock");
            return true;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "could not set a lock wallpaper: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * See sSharedLock. Only for a still some other app manages: a plain `image` with no slot of
     * its own still gets the copy of the home wallpaper below, which is the better answer when the
     * slot is the user's own.
     */
    @SuppressLint("MissingPermission")
    private static boolean sharesTheDesktopStill(Context ctx, android.app.WallpaperManager wm,
                                                 String lockKind) {
        if (Main.KIND_IMAGE.equals(lockKind)) return false;
        if (wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK) >= 0) return false;
        // MIUI keeps the lock record (`gallery`, carousel="true") while the slot itself follows
        // the desktop, so the record alone says nothing. What the lock screen shows is the
        // desktop's picture, and that is what has to be a still.
        String homeKind = wallpaperKind(ctx, "home");
        return homeKind != null && !Main.liveKind(homeKind)
                && (lockKind != null || !Main.KIND_IMAGE.equals(homeKind));
    }

    /**
     * The size of the last lock wallpaper this tried to re-fit, packed w<<32|h.
     *
     * Not a plain "done" flag: the user can change the wallpaper at any point and the next one
     * deserves its own attempt. What this stops is fighting the same picture over and over -
     * ensureLockWallpaper() runs before every art push, so a slot that cannot be rewritten would
     * otherwise be rewritten once per track change, forever.
     */
    private static volatile long sLockWpTried;

    /**
     * Puts an over-sized lock wallpaper back to the size of the screen, so leaving cover mode
     * can crossfade instead of cutting.
     *
     * Every frame of that crossfade re-uploads the WHOLE keyguard texture, and the texture is
     * the lock wallpaper at ITS OWN pixel size, not the screen's. Measured in the wallpaper
     * process, same phone, same 240ms fade, the only difference being the wallpaper's own
     * dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  fine
     *   1579x3432 (1.7x)          21MB/frame    the GL thread falls behind far enough that the
     *                                           compositor picks up a half-built frame - the
     *                                           cover drawn small on black, in a corner
     *
     * So WallpaperProbe.fadeTooExpensive() refuses to fade above 1.5x the screen and swaps in
     * one frame instead. That is the right call at that point, but from the outside it reads as
     * "the transition only works if your wallpaper happens to be the right resolution", which is
     * not something anyone should have to know. The extra pixels buy nothing either way: the
     * picture is only ever drawn on this screen, and every draw throws them away. Re-fitting is
     * the same centre-crop ensureLockWallpaper() already applies to the copy it writes itself,
     * so both paths leave the slot in the same shape.
     *
     * Two things it will not do:
     *
     * - Touch a wallpaper whose aspect ratio is not the screen's. A picture that much wider is
     *   being used for something - the parallax a scrolling wallpaper pans through - and
     *   cropping it would change what the user SEES rather than only its resolution. A hard cut
     *   is the smaller harm.
     * - Take a second run at the same picture. See sLockWpTried.
     *
     * Runs on the art worker, off the main thread, and only from the branch where the lock slot
     * already holds a still of its own - live wallpapers returned above, long before this.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them.
    @SuppressLint("MissingPermission")
    private static void fitLockWallpaperToScreen(android.app.WallpaperManager wm) {
        // Not while the wallpaper process sizes its texture to the screen (sTexFit, on by
        // default): that solves the same problem - a 2121x4712 wallpaper making every swap upload
        // 38MB and putting the crossfade over its own threshold - without writing anything. The
        // two must not both run, and if this one does, the user's depth cut-out goes with it:
        // MIUI segments the wallpaper the picker set, and nothing re-analyses a file we wrote.
        if (Main.sTexFit) return;
        int sw = Main.sScreenW, sh = Main.sScreenH;
        if (sw <= 0 || sh <= 0) return;
        int w = 0, h = 0;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options b =
                        new android.graphics.BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(in, null, b);
                w = b.outWidth;
                h = b.outHeight;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot measure the lock wallpaper: " + t);
            return;
        }
        if (w <= 0 || h <= 0) return;
        // The same gate the fade uses, and deliberately the same number: anything this leaves
        // alone is something WallpaperProbe will still agree to fade.
        if ((long) w * h * 2 <= (long) sw * sh * 3) return;
        float times = (float) (w * (double) h / (sw * (double) sh));
        // Aspect, to 1%. 1579x3432 and 1200x2608 are one picture at two resolutions; a panorama
        // is not, and centre-cropping one is a change nobody asked for.
        if (Math.abs((long) w * sh - (long) h * sw) * 100L > (long) h * sw) {
            Xp.log(Main.TAG + "lock wallpaper is " + w + "x" + h + ", " + Main.r2(times)
                    + "x the screen but not its shape - leaving it alone;"
                    + " the cover will swap in one frame rather than fade");
            return;
        }
        long size = ((long) w << 32) | (h & 0xffffffffL);
        if (sLockWpTried == size) return;
        sLockWpTried = size;
        Bitmap src = null, fitted = null;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                // Decoded straight down where a power of two allows it: the full 21MB only ever
                // existed to be thrown away, and this is a phone.
                o.inSampleSize = sampleSizeFor(w, h, sw, sh);
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                src = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (src == null) {
                Xp.log(Main.TAG + "lock wallpaper could not be decoded for re-fitting");
                return;
            }
            fitted = src.getWidth() == sw && src.getHeight() == sh ? src : fitKeepingColour(src, sw, sh);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(Main.TAG + "lock wallpaper was " + w + "x" + h + " (" + Main.r2(times)
                    + "x the screen) - re-fitted to " + sw + "x" + sh
                    + " so leaving cover mode can crossfade instead of cutting");
        } catch (Throwable t) {
            Xp.log(Main.TAG + "could not re-fit the lock wallpaper: " + Log.getStackTraceString(t));
        } finally {
            if (fitted != null && fitted != src) fitted.recycle();
            if (src != null) src.recycle();
        }
    }

    /**
     * centerCrop() with the source's colour space carried through.
     *
     * The shared one draws into `Bitmap.createBitmap(w, h, ARGB_8888)`, and that bitmap is
     * sRGB. Everywhere else in this module that is exactly right - what goes in is album art
     * that was composed here. Here it is the user's own wallpaper, which on this phone can be
     * a Display P3 photo, and drawing P3 into an sRGB bitmap gamut-clips it: saturated reds and
     * greens come back duller, permanently, in a file the user did not ask to have rewritten.
     * The OEM cares too - its upload path calls BitmapUtils.checkColorSpace() on every bitmap.
     *
     * Everything else about the crop is deliberately identical to centerCrop(), because
     * identical is the point: read off the dex, the OEM maps this texture to the screen with
     * AnimImageGLProgram.changeMvpMatrixCrop(), which is setIdentityM() plus a single scale on
     * whichever axis overflows - a centred scale-to-cover with no translation and no crop hint.
     * So the pixels this bakes into the file are the pixels that were already on screen.
     */
    private static Bitmap fitKeepingColour(Bitmap src, int w, int h) {
        Bitmap out = null;
        try {
            android.graphics.ColorSpace cs = src.getColorSpace();
            if (cs != null && !cs.equals(android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.SRGB))) {
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, src.hasAlpha(), cs);
                Xp.log(Main.TAG + "keeping the wallpaper's colour space: " + cs.getName());
            }
        } catch (Throwable t) {
            // A colour space the framework will not hand back to createBitmap. sRGB it is -
            // the same thing that happened before this method existed.
            Xp.log(Main.TAG + "cannot carry the wallpaper's colour space over: " + t);
        }
        if (out == null) return centerCrop(src, w, h);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new android.graphics.RectF(
                        (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** The largest power of two that still leaves the picture covering the screen. */
    private static int sampleSizeFor(int w, int h, int sw, int sh) {
        int s = 1;
        while (w / (s * 2) >= sw && h / (s * 2) >= sh) s *= 2;
        return s;
    }

    /**
     * What kind of wallpaper is on a slot - "image", "video", "sensor", "super_wallpaper", or
     * null when nothing here can say.
     *
     * This exists because getWallpaperFile(FLAG_LOCK) cannot tell two very different states
     * apart. It returns null when the lock screen follows the home wallpaper, and it returns
     * null when the lock screen has a LIVE wallpaper of its own, because a live wallpaper has
     * no bitmap file anywhere to hand back. ensureLockWallpaper read that null as the first
     * case and wrote a still copy of the home wallpaper into the lock slot - which is a
     * setBitmap(..., FLAG_LOCK), which unbinds the live wallpaper permanently. A user reported
     * exactly that: set a video lock wallpaper, play one track, and from then on the lock
     * screen is a still of the home wallpaper. Nothing here recorded what it replaced, so
     * leaving cover mode could not put it back either.
     *
     * Three sources, most authoritative first, all three read off the device:
     *
     * 1. MIUI's own record at /data/system/theme_magic/users/<u>/wallpaper/data/{lock,home}.xml.
     *    The root element is the answer - `wallpaper_data which="2" type="image"` - and the
     *    rest of the (38KB) tag is colour palettes. The files are 0777, so this needs no root,
     *    let alone the system uid SystemUI happens to have. Seen carrying last_type="video" on
     *    the home slot here, which is where the vocabulary is confirmed from.
     * 2. Settings.Secure `flag_lock_wallpaper_type` - lock only, and there is no home
     *    equivalent: `desktop_wallpaper_type` is unset in all three namespaces.
     * 3. Settings.Secure `constant_template_editor_info`, the lock screen editor's JSON, whose
     *    homeInfo/lockscreenInfo both carry a `resourceType`. The only source that covers home.
     *
     * Deliberately NOT WallpaperManager.getWallpaperInfo(FLAG_LOCK): on MIUI every wallpaper,
     * still or video, is drawn by the same com.miui.miwallpaper ImageWallpaper component, so
     * it answers "live" for all of them and distinguishes nothing.
     *
     * Unknown reads back as null, and null keeps the old behaviour. Being wrong in the shy
     * direction costs the cover on one phone; being wrong the other way costs someone their
     * wallpaper.
     */
    static String wallpaperKind(Context ctx, String slot) {
        boolean lock = "lock".equals(slot);
        String kind = kindFromMiui(lock);
        String from = "MiuiWallpaperManager";
        if (kind == null) {
            kind = kindFromThemeMagic(slot);
            from = "MIUI's " + slot + ".xml";
        }
        if (kind == null && lock) {
            kind = kindFromSettings(ctx, "flag_lock_wallpaper_type");
            from = "flag_lock_wallpaper_type";
        }
        if (kind == null) {
            kind = kindFromEditorInfo(ctx, lock ? "lockscreenInfo" : "homeInfo");
            from = "constant_template_editor_info";
        }
        // On every track change, so only when the answer moves. Which it does: this is
        // checked before each push precisely because the user can change the wallpaper at
        // any moment, and the interesting moment is the one where it just became a video.
        String last = "lock".equals(slot) ? sLockKindLogged : sHomeKindLogged;
        if (!java.util.Objects.equals(last, kind)) {
            Xp.log(Main.TAG + slot + " wallpaper is " + (kind == null ? "unknown" : kind)
                    + (kind == null ? " (neither MIUI's record nor Settings said)"
                                    : " per " + from));
            if ("lock".equals(slot)) sLockKindLogged = kind;
            else sHomeKindLogged = kind;
        }
        return kind;
    }

    private static String sLockKindLogged = "";
    private static String sHomeKindLogged = "";

    /**
     * MIUI's own answer, and the one to prefer: `MiuiWallpaperManager.getMiuiWallpaperType(which)`
     * is what MIUI asks itself, it returns the same vocabulary the files use ("image", "video",
     * "sensor", "super_wallpaper"), and both it and the SystemUI class holding the instance keep
     * their real names through R8 - so unlike everything below it, this should not need a new
     * heuristic per HyperOS version.
     *
     * `which` is 1 for home and 2 for lock, read off MiuiWallpaperManager's own constants rather
     * than assumed - though they do match WallpaperManager's FLAG_SYSTEM/FLAG_LOCK, and the
     * `which=` attribute in MIUI's XML agrees.
     */
    private static String kindFromMiui(boolean lock) {
        Object mgr = Main.sKgWallpaperMgr;
        if (mgr == null) return null;
        try {
            Object wm = Xp.getObjectField(mgr, "mMiuiWallpaperManager");
            if (wm == null) return null;
            int which = miuiWhich(wm.getClass(), lock);
            Object type = Xp.callMethod(wm, "getMiuiWallpaperType", which);
            String s = type == null ? null : type.toString();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "MiuiWallpaperManager could not say what the "
                    + (lock ? "lock" : "home") + " wallpaper is: " + t);
            return null;
        }
    }

    private static int miuiWhich(Class<?> wmCls, boolean lock) {
        String name = lock ? "MI_WALLPAPER_WHICH_LOCK" : "MI_WALLPAPER_WHICH_HOME";
        try {
            java.lang.reflect.Field f = wmCls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return lock ? 2 : 1;
        }
    }

    private static String kindFromThemeMagic(String slot) {
        java.io.InputStream in = null;
        try {
            // uid / 100000 is the user id - UserHandle.PER_USER_RANGE, and UserHandle's own
            // accessors for it are all @hide.
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return null;
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String type = p.getAttributeValue(null, "type");
                if (type != null && !type.isEmpty()) return type;
            }
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot read MIUI's " + slot + " wallpaper record: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String kindFromSettings(Context ctx, String key) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        String[] got = new String[3];
        try {
            got[0] = android.provider.Settings.Secure.getString(cr, key);
            got[1] = android.provider.Settings.System.getString(cr, key);
            got[2] = android.provider.Settings.Global.getString(cr, key);
        } catch (Throwable ignored) {
        }
        for (String v : got) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * The lock screen editor's own record, which is the only one of these that covers the home
     * slot: there is no `desktop_wallpaper_type` key - checked on device, all three namespaces.
     *
     * `constant_template_editor_info` is a single Settings.Secure JSON blob written by
     * com.miui.aod, with the same shape under `homeInfo` and `lockscreenInfo`. Its
     * `resourceType` uses the same vocabulary as the XML's `type` - "image", "video".
     */
    private static String kindFromEditorInfo(Context ctx, String section) {
        try {
            String raw = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "constant_template_editor_info");
            if (raw == null || raw.isEmpty()) return null;
            org.json.JSONObject info = new org.json.JSONObject(raw)
                    .optJSONObject(section);
            if (info == null) return null;
            org.json.JSONObject wp = info.optJSONObject("wallpaperInfo");
            if (wp == null) return null;
            String type = wp.optString("resourceType", "");
            return type.isEmpty() ? null : type;
        } catch (Throwable t) {
            Xp.log(Main.TAG + "cannot read " + section + " from the editor info: " + t);
            return null;
        }
    }

    /** [diag] The last report, so a push that changes nothing prints nothing. */
    private static String sWallpaperDiag = "";

    /**
     * [diag] Everything the SystemUI side can see about how the two wallpapers are framed, for
     * the "lock screen and desktop do not line up" report: the screen, both slots' kinds and
     * file sizes, and MIUI's own record of each slot minus its colour palettes. The wallpaper
     * process logs the other half - where its renderers put the picture - as [MCWall] [diag].
     */
    // Runs inside com.android.systemui, which holds READ_WALLPAPER_INTERNAL.
    @SuppressLint("MissingPermission")
    private static void wallpaperDiag(Context ctx, android.app.WallpaperManager wm, String why) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("screen=").append(Main.sScreenW).append('x').append(Main.sScreenH);
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                sb.append(" display=").append(dm.widthPixels).append('x')
                        .append(dm.heightPixels).append('@').append(dm.densityDpi);
            } catch (Throwable ignored) {
            }
            sb.append(" desired=").append(wm.getDesiredMinimumWidth()).append('x')
                    .append(wm.getDesiredMinimumHeight());
            for (String slot : new String[] {"home", "lock"}) {
                int flag = "lock".equals(slot) ? android.app.WallpaperManager.FLAG_LOCK
                                               : android.app.WallpaperManager.FLAG_SYSTEM;
                sb.append("\n  ").append(slot).append(": kind=")
                        .append(wallpaperKind(ctx, slot)).append(" file=");
                try {
                    android.os.ParcelFileDescriptor fd = wm.getWallpaperFile(flag);
                    if (fd == null) {
                        sb.append("none");
                    } else {
                        java.io.InputStream in =
                                new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
                        try {
                            android.graphics.BitmapFactory.Options b =
                                    new android.graphics.BitmapFactory.Options();
                            b.inJustDecodeBounds = true;
                            android.graphics.BitmapFactory.decodeStream(in, null, b);
                            sb.append(b.outWidth).append('x').append(b.outHeight);
                        } finally {
                            in.close();
                        }
                    }
                } catch (Throwable t) {
                    sb.append("? (").append(t).append(')');
                }
                try {
                    sb.append(" id=").append(wm.getWallpaperId(flag));
                } catch (Throwable ignored) {
                }
                sb.append("\n  ").append(slot).append(" record: ").append(miuiRecord(slot));
            }
            String report = sb.toString();
            if (report.equals(sWallpaperDiag)) return;
            sWallpaperDiag = report;
            Xp.log(Main.TAG + "[diag] wallpaper (" + why + ")\n  " + report);
        } catch (Throwable t) {
            Xp.w(Main.TAG + "[diag] wallpaper report failed: " + t);
        }
    }

    /** [diag] MIUI's slot record as attributes, the ~100 palette/colour ones left out. */
    private static String miuiRecord(String slot) {
        java.io.InputStream in = null;
        try {
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return "unreadable";
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String n = p.getAttributeName(i);
                    if (n.startsWith("palette") || n.startsWith("color")
                            || n.startsWith("allColors") || n.startsWith("partPalette")
                            || n.startsWith("partIsDeep") || n.startsWith("isDeep")) {
                        continue;
                    }
                    sb.append(n).append('=').append(p.getAttributeValue(i)).append(' ');
                }
                return sb.toString().trim();
            }
            return "empty";
        } catch (Throwable t) {
            return "? (" + t + ")";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The home wallpaper, decoded no larger than it needs to be for this screen. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static Bitmap homeWallpaper(android.app.WallpaperManager wm) {
        try {
            android.os.ParcelFileDescriptor sys =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if (sys != null) {
                java.io.InputStream in =
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(sys);
                try {
                    android.graphics.BitmapFactory.Options o =
                            new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "reading the home wallpaper file failed: " + t);
        }
        // A live or default home wallpaper has no file; render whatever is showing instead.
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Whether the cover has somewhere to go.
     *
     * Two conditions, and they are not the same one. The lock screen needs a wallpaper entry
     * of its own - about the ENTRY, not the picture: a copy of the home wallpaper counts, and
     * is exactly what ensureLockWallpaper() installs. And that wallpaper has to be a still
     * image, because a video or sensor one is drawn by an engine this module has nothing
     * hooked in, and ensureLockWallpaper will not (must not) convert it to a still.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    static boolean hasLockWallpaper(Context ctx) {
        try {
            String kind = wallpaperKind(ctx, "lock");
            if (Main.liveKind(kind)) return false;
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            android.os.ParcelFileDescriptor f =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (f == null) return false;
            try {
                f.close();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts the lock screen back to following the home wallpaper. Undoes ensureLockWallpaper. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    static void clearLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            wm.clear(android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(Main.TAG + "lock wallpaper cleared, back to following the home one");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "clearLockWallpaper failed: " + t);
        }
    }

    /** Coarse identity of an artwork: enough to tell one cover from another, cheap to take. */
    private static int artPrint(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            int[] px = new int[64];
            s.getPixels(px, 0, 8, 0, 0, 8, 8);
            s.recycle();
            return java.util.Arrays.hashCode(px);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * See CoverCompose.composeWallpaper(); kept here so the call sites read as they did. In card
     * mode the wallpaper is only the blurred backdrop - the square itself is CoverCardLayer.
     */
    private static Bitmap composeWallpaper(Bitmap src, int w, int h, float bias) {
        return Main.sCoverCardStyle.mode == CoverCardStyle.CARD
                ? CoverCompose.cardBackground(src, w, h)
                : CoverCompose.composeWallpaper(src, w, h, bias);
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF(
                (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        cv.drawBitmap(src, null, dst, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
