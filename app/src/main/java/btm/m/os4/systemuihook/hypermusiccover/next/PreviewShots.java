// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.BuildConfig;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.view.ViewGroup;
import android.view.View;

/**
 * What the app's settings pages are told about the lock screen when they ask: the state and
 * geometry in one bundle, and - when asked for them - pictures of the clock, the date, the card
 * and the shortcuts, drawn from the live views.
 *
 * Carved out of Main as it was (2026-10-14, after AlbumArt): every member moved whole with its
 * comments, references across the line prefixed with Main. or PreviewShots., nothing else
 * changed.
 */
final class PreviewShots {
    private PreviewShots() {}

    /**
     * Everything the app's preview needs that only this process can see.
     *
     * The app draws a lock screen it has no access to: it cannot read the media session (that
     * would need notification access), and the media card and the shortcut buttons are
     * SystemUI's own views. It used to draw its own diagram of the card out of rectangles, which
     * is exactly as convincing as it sounds. So the real views are captured here instead - one
     * software draw each - and the app paints the results into its preview at the coordinates
     * they occupy on the phone.
     *
     * Sizes: the artwork is a 256px JPEG, the card is scaled to CARD_SHOT_W and the shortcuts to
     * SHORTCUT_SHOT_W, all together well inside the ordered broadcast's binder budget. The
     * shortcuts are only sent when asked for - they never change - and the artwork is skipped
     * when the caller says it already has this track's.
     *
     * shots=false is the app's features page, which polls every few seconds for the state
     * fields alone and draws none of the pictures: it gets those fields and nothing is drawn.
     * Every capture is a software draw on this main thread, and the card's forces its keyguard
     * layout on and back off, so the full reply on that poll was work thrown away while the page
     * stayed open.
     */
    static android.os.Bundle previewBundle(Context c, Intent i) {
        if (!i.getBooleanExtra("shots", true)) {
            android.os.Bundle out = new android.os.Bundle();
            out.putBoolean("alive", true);
            out.putString("track", MediaCard.sCardKey);
            putPreviewState(out);
            return out;
        }
        android.os.Bundle out = new android.os.Bundle();
        // One line per request, in debug builds, saying where every piece of the picture came
        // from or why it is missing. The preview has six independent sources and a user reports
        // it as one thing ("the preview is blank"), so without this every report costs a round
        // trip to find out WHICH source failed.
        StringBuilder diag = DIAG ? new StringBuilder() : null;
        out.putBoolean("alive", true);
        out.putString("track", MediaCard.sCardKey);
        if (diag != null) {
            diag.append("preview: cover=").append(Main.sCoverMode)
                .append(" auto=").append(Main.sAuto)
                .append(" cardKnown=").append(MediaCard.sCardKnown)
                .append(" cardShowing=").append(MediaCard.sCardShowing)
                .append(" keyguard=").append(Main.keyguardShowing())
                .append(" screenOn=").append(Main.screenOn())
                .append(" container=").append(Main.sContainer != null)
                .append(" screen=").append(Main.sScreenW).append('x').append(Main.sScreenH)
                .append(" track=").append(MediaCard.sCardKey.isEmpty() ? "none" : MediaCard.sCardKey);
        }

        if (!MediaCard.sCardKey.isEmpty() && MediaCard.sCardKey.equals(i.getStringExtra("have"))) {
            out.putBoolean("same", true);
            if (diag != null) diag.append("\n  art: same track, not resent");
        } else {
            byte[] thumb = CoverPush.artThumbnail(c, 256);
            if (thumb != null) out.putByteArray("jpg", thumb);
            if (diag != null) {
                diag.append("\n  art: ").append(thumb == null ? "NULL" : thumb.length + "B");
                if (thumb == null) {
                    MediaController mc = MediaCard.controllerFromCard(c);
                    if (mc == null) mc = Main.pickController(c);
                    diag.append(" (session=")
                        .append(mc == null ? "none" : mc.getPackageName())
                        .append(" metadata=")
                        .append(mc == null || mc.getMetadata() == null ? "none" : "yes")
                        .append(" cardThumb=").append(AlbumArt.cardThumbnail() == null ? "none" : "yes")
                        .append(')');
                }
            }
        }

        View cardView = cardShotRoot(Main.findSysuiView("mi_media_controls"));
        View mediaBg = cardView == null ? null : Main.findByName(cardView, "media_bg");
        // 24dp on this device - notification_item_bg_radius, which media_bg's own outline
        // agrees with. The app rounds its stand-in frosting to the same number.
        if (mediaBg != null) out.putFloat("cardradius", Main.outlineRadius(mediaBg));
        putArtSlot(out, cardView);
        boolean dump = i.getBooleanExtra("dump", false);
        // The artwork at full size, for lifting a sample cover out of a running phone.
        if (dump) {
            Bitmap raw = AlbumArt.albumArt(c);
            if (raw != null) {
                try {
                    java.io.FileOutputStream f = new java.io.FileOutputStream(
                            new java.io.File(Main.sAppCtx.getFilesDir(), "hyperchanger_hmc_next_shot_art.jpg"));
                    raw.compress(Bitmap.CompressFormat.JPEG, 95, f);
                    f.close();
                    Xp.log(Main.TAG + "shot art " + raw.getWidth() + "x" + raw.getHeight());
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "art dump failed: " + t);
                }
            }
        }
        byte[] card = shootCard(cardView);
        if (dump) dumpShot("card", card);
        if (card != null) {
            out.putByteArray("card", card);
            // The SAMPLED rectangle, not a live reading: on an unlocked phone this same view is
            // the one at the top of the shade, six hundred pixels higher up.
            out.putIntArray("cardrect", new int[]{MediaCard.sCardL, MediaCard.sCardT, MediaCard.sCardW, MediaCard.sCardH});
        }
        if (diag != null) {
            diag.append("\n  card: view=");
            if (cardView == null) {
                diag.append("NOT FOUND (no media card in the keyguard tree)");
            } else {
                diag.append(cardView.getClass().getSimpleName())
                    .append(' ').append(cardView.getWidth()).append('x')
                    .append(cardView.getHeight())
                    .append(" vis=").append(cardView.getVisibility());
            }
            diag.append(" shot=").append(card == null ? "NULL" : card.length + "B")
                .append(" rect=").append(MediaCard.sCardL).append(',').append(MediaCard.sCardT).append(' ')
                .append(MediaCard.sCardW).append('x').append(MediaCard.sCardH)
                .append(" artslot=").append(out.containsKey("artfrac") ? "yes" : "no")
                .append(" hideArt=").append(Main.sMcHideArt);
        }

        putDate(out, dump);
        float[] cg = putPreviewState(out);
        if (diag != null) {
            View date = ClockViews.visibleDate();
            diag.append("\n  date: view=").append(date == null ? "NOT FOUND"
                    : date.getClass().getSimpleName() + " " + date.getWidth() + "x"
                      + date.getHeight() + " vis=" + date.getVisibility())
                .append(" shot=").append(out.containsKey("date") ? "yes" : "NULL");
            diag.append("\n  clock: geom=").append(cg == null ? "NULL (no date or no glyph box)"
                    : "w" + cg[0] + " h" + cg[1] + " y" + cg[2] + " x" + cg[3] + " pivot" + cg[4]);
            View[] roots = Main.clockRoots();
            diag.append(" roots=").append(roots.length);
            for (int t = 0; t < roots.length; t++) {
                View g = Main.clockTarget(roots[t]);
                diag.append(" target").append(t).append('=')
                    .append(g == null ? "none" : g.getClass().getSimpleName() + "#" + Main.idOf(g));
            }
        }

        // Both clock trees: the hour is drawn in the background layer and the minute in the
        // foreground one, and each carries half the time.
        for (int t = 0; t < 2; t++) {
            byte[] png = shootClock(t);
            if (dump) dumpShot("clock" + t, png);
            if (png != null) out.putByteArray("clock" + t, png);
            if (diag != null) {
                diag.append(" shot").append(t).append('=')
                    .append(png == null ? "NULL" : png.length + "B");
            }
        }
        if (diag != null && !sClockShot) diag.append(" [clock capture switched OFF]");

        if (i.getBooleanExtra("shortcuts", false)) {
            putShortcut(out, "sl", "shortcut_view_left", dump);
            putShortcut(out, "sr", "shortcut_view_right", dump);
            if (diag != null) {
                diag.append("\n  shortcuts: asked, left=")
                    .append(out.containsKey("sl") ? "yes" : "NOT FOUND")
                    .append(" right=").append(out.containsKey("sr") ? "yes" : "NOT FOUND");
            }
        } else if (diag != null) {
            diag.append("\n  shortcuts: not asked for (the app already has them)");
        }

        if (diag != null) Xp.log(Main.TAG + diag);
        return out;
    }

    /**
     * The fields of the preview that are not pictures, and the clock geometry they came from.
     *
     * Sent with the pictures, not only in answer to a query. The query happens once, when the
     * page is opened; the clock's geometry changes whenever the user changes the lock screen
     * clock style, and a stale zero here is the app holding a picture of a clock it then refuses
     * to draw for want of somewhere to put it.
     */
    static float[] putPreviewState(android.os.Bundle out) {
        float[] cg = ClockViews.clockGeometry();
        if (cg != null) {
            out.putFloat("clockw", cg[0]);
            out.putFloat("clockh", cg[1]);
            out.putFloat("clocky", cg[2]);
            out.putFloat("clockx", cg[3]);
            out.putFloat("clockpivotx", cg[4]);
            out.putFloat("clockpad", Main.CLOCK_PAD);
            out.putFloat("clockfull", Main.clockFullRatio());
            // For a size the slider has never set: the dp default, in this style's terms.
            out.putFloat("clocksize", Main.effectiveClockSize());
        }
        out.putBoolean("clockglass", ClockGlass.clockHasGlass());
        // Which route the lyric came from changes mid-track - the session's payload turns up
        // after the file, a network lookup lands after that - and the row that names it is read
        // while the page is open.
        out.putString("lyricsrc", LockLyrics.sourceName());
        return cg;
    }

    /**
     * Where the card draws the album art, and how rounded it is.
     *
     * The artwork is the one part of the card the app has to draw itself. A software canvas does
     * not apply a view's outline clip - only a hardware one does - so the captured card came
     * back with a hard-cornered square where the OEM draws a rounded one. Everything else about
     * it survives the capture, so only the artwork is cut out of the picture and painted back.
     *
     * The radius is read off the view's own OutlineProvider rather than a resource: it is the
     * number actually being clipped with, and it costs one call.
     */
    static void putArtSlot(android.os.Bundle out, View card) {
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) return;
        // Resolved off the card rather than read from the guard's cache: the guard is only
        // installed when one of the two card settings is on, and the app asks for this picture
        // whether or not anything has been switched on.
        View box = Main.findByName(card, "album_art");
        View img = box == null ? null : Main.findByName(box, "album_art_image");
        if (img == null) return;
        // Hidden by the user's own setting - nothing to draw, and saying so is how the app knows.
        if (Main.sMcHideArt || box.getVisibility() != View.VISIBLE) return;
        if (img.getWidth() <= 0 || img.getHeight() <= 0) return;
        int[] a = new int[2], b = new int[2];
        img.getLocationOnScreen(a);
        card.getLocationOnScreen(b);
        // FRACTIONS of the card, not pixels. The app pastes the capture into the rectangle the
        // card occupies on the LOCK screen, but the card it captured may have been laid out at
        // some other width - pull the shade down over the app and it is the same view, sized for
        // the shade - and pixels measured there land in the wrong place once the capture has
        // been stretched to the lock screen's width. Fractions survive the stretch.
        float w = card.getWidth(), h = card.getHeight();
        out.putFloatArray("artfrac", new float[]{(a[0] - b[0]) / w, (a[1] - b[1]) / h,
                img.getWidth() / w, img.getHeight() / h});
        // Measured on this device: the ImageView is not clipped at all, its CONTAINER is -
        // clipToOutline with a 30px radius - so the radius has to be looked for on both.
        float r = img.getClipToOutline() ? Main.outlineRadius(img) : 0f;
        if (r <= 0f && box.getClipToOutline()) r = Main.outlineRadius(box);
        out.putFloat("artradius", r / w);
    }

    static final int DATE_SHOT_W = 360;

    /**
     * The date line above the clock, captured like everything else rather than re-typed.
     *
     * It has to be the real view: the format, the language, the font and the colour all follow
     * the lock screen clock style, and a date drawn here would be a second thing to keep in step
     * with a theme the user can change at any moment. Because the app re-asks every few seconds,
     * a style change simply arrives with the next picture.
     *
     * The y is COMPUTED, not read. Cover mode leaves the date at its rest position (the clock
     * hangs under it), and the app asks for this with the phone unlocked, where
     * the live position is whatever the hidden keyguard happens to be holding. The x comes off
     * the view because it does not depend on the squeeze.
     */
    static void putDate(android.os.Bundle out, boolean dump) {
        View date = ClockViews.visibleDate();
        if (date == null || date.getWidth() <= 0 || date.getHeight() <= 0) return;
        byte[] png = shoot(date, DATE_SHOT_W);
        if (dump) dumpShot("date", png);
        if (png == null) return;
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        out.putByteArray("date", png);
        out.putIntArray("daterect", new int[]{loc[0], Math.round(ClockCollapse.coverDateY(date)),
                date.getWidth(), date.getHeight()});
    }

    static final int CLOCK_SHOT_W = 480;

    static volatile boolean sClockShot = true;

    /**
     * The clock's digits, cropped to the glyphs, drawn WITHOUT the collapse scale - the app
     * applies that itself, so dragging the size slider does not need a new picture.
     *
     * These are the OEM's own variable-font paths, which is why they can be captured at all:
     * TimeView.onDraw strokes a Path, and a Path draws into a software canvas like anything
     * else. The refracting glass is a RuntimeShader and does not, so what comes back is the
     * digits as they are filled at the moment of the capture.
     */
    static byte[] shootClock(int which) {
        if (!sClockShot) return null;
        View[] roots = Main.clockRoots();
        if (which >= roots.length) return null;
        View g = Main.clockTarget(roots[which]);
        RectF box = ClockViews.paddedGlyphBox();
        if (g == null || box == null || box.width() <= 0 || box.height() <= 0) return null;
        try {
            float k = Math.min(1f, CLOCK_SHOT_W / box.width());
            int w = Math.max(1, Math.round(box.width() * k));
            int h = Math.max(1, Math.round(box.height() * k));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            cv.translate(-box.left, -box.top);
            g.draw(cv);
            redrawAfterCapture(g);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.w(Main.TAG + "clock capture failed: " + t);
            return null;
        }
    }

    /**
     * Whether the card is currently laid out the way the lock screen lays it out.
     *
     * The app stretches this picture into the rectangle the card occupies on the LOCK screen, so
     * a picture of the card in any other layout is wrong by construction - and the shade
     * re-lays-out the very same view for its own panel. Taken there and stretched into the lock
     * screen's rectangle, the card's content ended up crammed into the top-left corner.
     *
     * The test is the sampled size rather than "is the shade open", because it is the size that
     * the stretch actually depends on: whatever the shade, an AOD or a future panel does to the
     * card, a picture is only usable if it was taken at the size it will be drawn at. When
     * nothing has been sampled yet there is no invariant to protect and the picture is taken.
     */
    static boolean cardLaidOutForKeyguard(View card) {
        if (MediaCard.sCardW <= 0 || MediaCard.sCardH <= 0) return true;
        boolean ok = Math.abs(card.getWidth() - MediaCard.sCardW) <= 2
                && Math.abs(card.getHeight() - MediaCard.sCardH) <= 2;
        if (!ok && !sCardShapeComplained) {
            sCardShapeComplained = true;
            Xp.log(Main.TAG + "card is " + card.getWidth() + "x" + card.getHeight()
                    + ", lock screen says " + MediaCard.sCardW + "x" + MediaCard.sCardH + " - not photographing it");
        }
        if (ok) sCardShapeComplained = false;
        return ok;
    }

    static boolean sCardShapeComplained;

    /**
     * What to photograph for "the media card".
     *
     * Not mi_media_controls, which is only the card's contents: the bright rim along the card's
     * edge is a FOREGROUND drawable on its parent, MiuiMediaHeaderView, and a foreground is
     * drawn after the children - so starting the capture at the child left the rim out
     * altogether. The parent has the same bounds, so nothing else about the picture changes.
     *
     * Guarded on the bounds matching rather than on the class name, because everything measured
     * and reported around the card - the sampled rectangle, the artwork's slot - is relative to
     * mi_media_controls, and only an identically placed parent keeps those true.
     */
    static View cardShotRoot(View card) {
        if (card == null) return null;
        if (!(card.getParent() instanceof View)) return card;
        View p = (View) card.getParent();
        boolean sameBox = p.getWidth() == card.getWidth() && p.getHeight() == card.getHeight()
                && card.getLeft() == 0 && card.getTop() == 0;
        return sameBox ? p : card;
    }

    /** Torch and camera. Their layout is the same locked or unlocked, so this reads it live. */
    static void putShortcut(android.os.Bundle out, String key, String id, boolean dump) {
        View v = Main.findSysuiView(id);
        if (v == null) {
            Xp.log(Main.TAG + id + " not found");
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
        byte[] png = shoot(v, SHORTCUT_SHOT_W);
        if (dump) dumpShot(key, png);
        if (png == null) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        out.putByteArray(key, png);
        out.putIntArray(key + "rect", new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()});
    }

    /** Debug builds narrate the preview pipeline; release builds only do so on `op=verbose`. */
    static final boolean DIAG = BuildConfig.DEBUG;

    static final int CARD_SHOT_W = 420;

    static final int SHORTCUT_SHOT_W = 160;

    /**
     * `--es op preview --ez dump true` drops the captures next to the state file, for looking at
     * when the preview shows the wrong thing. A capture can fail quietly - a view that renders
     * only on a hardware canvas comes out blank rather than throwing - and the size of the PNG
     * is not enough to tell that apart from a picture that is simply mostly transparent.
     */
    static void dumpShot(String name, byte[] png) {
        if (Main.sAppCtx == null) return;
        try {
            java.io.File f = new java.io.File(Main.sAppCtx.getFilesDir(), "hyperchanger_hmc_next_shot_" + name + ".png");
            if (png == null) {
                Xp.log(Main.TAG + "shot " + name + " = null");
                return;
            }
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(png);
            o.close();
            Xp.log(Main.TAG + "shot " + name + " " + png.length + "B -> " + f);
        } catch (Throwable t) {
            Xp.w(Main.TAG + "shot dump failed: " + t);
        }
    }

    /**
     * The media card as a picture.
     *
     * The two card settings only apply on the keyguard (see assertMediaCard), but the app asks
     * for this with the phone unlocked and in its own foreground, and what it needs to show is
     * what the LOCK screen will look like. So they are forced on for the duration of the draw
     * and put straight back. Nothing can be drawn to the screen in between - this is the main
     * thread, and a traversal cannot interleave with a broadcast receiver - so the real card
     * never flickers.
     */
    static byte[] shootCard(View card) {
        if (card == null) return null;
        if (!cardLaidOutForKeyguard(card)) return null;
        View box = Main.findByName(card, "album_art");
        View img = box == null ? null : Main.findByName(box, "album_art_image");
        // View.VISIBLE rather than a -1 sentinel for "no view": the null check below already
        // covers that case, and lint tracks the @Visibility typedef through this assignment -
        // a sentinel outside the set is a WrongConstant error at the restore.
        int wasVisible = img == null ? View.VISIBLE : img.getVisibility();
        Main.sCardForced = true;
        try {
            MediaCard.assertMediaCard(card);
            // Cut the artwork out of the picture; the app paints it back rounded (putArtSlot).
            // The badge over its corner is a sibling, so it still lands on top where it belongs.
            if (img != null) img.setVisibility(View.INVISIBLE);
            return shoot(card, CARD_SHOT_W);
        } catch (Throwable t) {
            Xp.w(Main.TAG + "card capture failed: " + t);
            return null;
        } finally {
            if (img != null) img.setVisibility(wasVisible);
            Main.sCardForced = false;
            MediaCard.assertMediaCard(card);
        }
    }

    /**
     * One view, drawn into a bitmap at most maxW wide, as a PNG.
     *
     * PNG rather than JPEG because these have to keep their alpha: the card is a rounded
     * translucent panel and the shortcuts are circles, and squaring either off would put a hard
     * rectangle on top of the wallpaper in the preview.
     */
    static byte[] shoot(View v, int maxW) {
        return shoot(v, maxW, null);
    }

    /**
     * extraBg is a child whose BACKGROUND has to be drawn by hand, for views MIUI's blur path
     * skips on a software canvas.
     */
    static byte[] shoot(View v, int maxW, View extraBg) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap b = null;
        try {
            float k = Math.min(1f, maxW / (float) w);
            b = Bitmap.createBitmap(Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            drawBackgroundOf(extraBg, cv);
            v.draw(cv);
            redrawAfterCapture(v);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.w(Main.TAG + "view capture failed: " + t);
            return null;
        } finally {
            if (b != null) b.recycle();
        }
    }

    /**
     * Puts a captured view back in line for a real redraw.
     *
     * Drawing a live view into an offscreen canvas is not free of consequences: View.draw()
     * leaves the view marked as drawn, and the framework is then entitled to skip it on the next
     * real frame, which shows up as stale pixels - half a clock, digits that did not follow the
     * minute over. Suspected cause of "the lock screen font is sometimes incomplete", reported
     * after the clock started being captured every few seconds.
     *
     * invalidate() does not reach children, and it is a child that draws the glyphs, so this
     * walks. Cheap: a handful of views, once per capture, and an invalidate on an unchanged view
     * costs one more draw of something that was going to be drawn anyway.
     */
    static void redrawAfterCapture(View v) {
        if (v == null) return;
        v.invalidate();
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) redrawAfterCapture(g.getChildAt(i));
    }

    /** Draws one child's background at its place in the parent, without going through the view. */
    static void drawBackgroundOf(View v, android.graphics.Canvas cv) {
        if (v == null) return;
        Drawable d = v.getBackground();
        if (d == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        Rect old = new Rect(d.getBounds());
        try {
            cv.save();
            cv.translate(v.getLeft(), v.getTop());
            d.setBounds(0, 0, v.getWidth(), v.getHeight());
            d.draw(cv);
        } catch (Throwable ignored) {
        } finally {
            d.setBounds(old);
            cv.restore();
        }
    }
}
