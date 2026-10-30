// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * The media card's page button: swaps the cover and the lyric page, the same switch as the
 * two-finger tap. It takes the slot to the right of "next" - `action4` - and the player's own
 * button there is hidden under it for as long as it is up.
 *
 * Why that slot, and always that one: the OEM binds the row by meaning, not by order
 * (MiuiMediaActionButtonUtils.getActionById): action0 = custom0, action1 = prev, action2 =
 * play/pause, action3 = next, action4 = custom1. So action4 is the player's second custom action
 * on every player - a desktop-lyrics switch that does nothing on the lock screen, Apple's
 * shuffle, a like - and empty it still holds its place. The user chose to take it whatever it
 * is (2026-10-14), and the app's switch says so.
 *
 * Drawn on the card's ViewOverlay, not added as a child: the card is the OEM's ConstraintLayout
 * and a child of ours would need SystemUI's androidx to place it. The OEM button stays in the
 * layout at alpha 0, so nothing moves; the tap never reaches it, Main takes it at the window's
 * dispatch.
 *
 * The glyph names the page a tap goes TO - lyric lines on the cover page, a disc on the lyric
 * page - which is how the play/pause button beside it reads.
 */
final class CardPageButton {

    private CardPageButton() {
    }

    /** Glyph box when the neighbouring icons cannot be measured. */
    private static final float SIZE_DP = 24f;
    /** The glyph box as a share of the "next" icon's drawn box. See place(). */
    private static final float ICON_SHARE = 0.6f;

    private static View sHost;
    private static Glyph sGlyph;
    /** The OEM button under the glyph, and the alpha it had before we hid it. */
    private static View sSlot;
    private static float sSlotAlpha = 1f;
    private static int sSlotId;
    private static int sNextId;

    /**
     * One frame's worth: put the glyph over {@code card}'s action4 at {@code alpha} (0 hides it,
     * and gives the slot back), naming the other page. Called from the card guard's pre-draw, so
     * every write is conditional.
     */
    static void apply(View card, float alpha, boolean onLyrics, TextView title) {
        View slot = card.findViewById(id(card, true));
        if (alpha <= 0f || slot == null || slot.getWidth() <= 0) {
            if (sGlyph != null && sHost == card) sGlyph.set(0f, onLyrics, sGlyph.ink);
            restoreSlot();
            return;
        }
        if (sHost != card) {
            release();
            sGlyph = new Glyph();
            card.getOverlay().add(sGlyph);
            sHost = card;
        }
        View next = card.findViewById(id(card, false));
        place(card, slot, next);
        hideSlot(slot, alpha);
        sGlyph.set(alpha, onLyrics, ink(next, title));
    }

    /** Takes the glyph off the card and gives the slot back. The shade shows the same card. */
    static void release() {
        restoreSlot();
        View h = sHost;
        Glyph g = sGlyph;
        sHost = null;
        sGlyph = null;
        if (h == null || g == null) return;
        try {
            h.getOverlay().remove(g);
        } catch (Throwable ignored) {
        }
    }

    /** Whether a raw screen point lands on the slot the glyph is covering. */
    static boolean contains(float x, float y) {
        View s = sSlot;
        Glyph g = sGlyph;
        if (s == null || g == null || g.alpha < 0.5f || !s.isShown()) return false;
        int[] loc = new int[2];
        s.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + s.getWidth()
                && y >= loc[1] && y < loc[1] + s.getHeight();
    }

    static String describe() {
        Glyph g = sGlyph;
        if (g == null) return "glyph=none";
        return "glyph at " + g.getBounds().toShortString() + " a=" + g.alpha
                + " lyrics=" + g.onLyrics + " slot=" + (sSlot != null) + " slotAlpha=" + sSlotAlpha;
    }

    /**
     * The OEM button fades out as the glyph fades in, so the exit hands the slot back over the
     * same progress. Alpha only - it invalidates, and the button keeps its place in the row.
     */
    private static void hideSlot(View slot, float alpha) {
        if (sSlot != slot) {
            restoreSlot();
            sSlot = slot;
            sSlotAlpha = slot.getAlpha();
        }
        float a = sSlotAlpha * (1f - Math.min(1f, alpha));
        if (slot.getAlpha() != a) slot.setAlpha(a);
    }

    private static void restoreSlot() {
        View s = sSlot;
        sSlot = null;
        if (s == null) return;
        try {
            if (s.getAlpha() != sSlotAlpha) s.setAlpha(sSlotAlpha);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Centred on the slot, at the size the "next" icon is actually drawn: its drawable's own
     * size through the button's image matrix, so the glyph sits in the row at the row's scale.
     */
    private static void place(View card, View slot, View next) {
        // The row's vectors are 40dp boxes with the ink in the middle ~40% of them (next's arrow
        // measured 47px of a 120px box), and the glyph's grid puts its ink in about 70% of its
        // box - taken at the full box it came out twice the arrow's size (user, 2026-10-14).
        int size = Math.round(iconSize(next) * ICON_SHARE);
        if (size <= 0) size = Math.round(SIZE_DP * Main.density());
        float[] at = offsetIn(slot, card);
        if (at == null) return;
        float cx = at[0] + slot.getWidth() / 2f, cy = at[1] + slot.getHeight() / 2f;
        // Across, under the output chip's glyph rather than on the slot's centre: the chip and
        // the total time are one column at the card's right, and the slot sits ~20px right of it,
        // which next to them read as misaligned (user, 2026-10-14).
        View chip = card.findViewById(lookupOnce(card));
        if (chip != null && chip.isShown() && chip.getWidth() > 0) {
            float[] c = offsetIn(chip, card);
            if (c != null) cx = c[0] + chip.getWidth() / 2f;
        }
        int l = Math.round(cx - size / 2f), t = Math.round(cy - size / 2f);
        android.graphics.Rect b = sGlyph.getBounds();
        if (b.left != l || b.top != t || b.width() != size) {
            sGlyph.setBounds(l, t, l + size, t + size);
        }
    }

    private static int iconSize(View v) {
        if (!(v instanceof ImageButton)) return 0;
        ImageButton b = (ImageButton) v;
        Drawable d = b.getDrawable();
        if (d == null) return 0;
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return 0;
        float[] m = new float[9];
        Matrix im = b.getImageMatrix();
        im.getValues(m);
        float sx = m[Matrix.MSCALE_X] > 0f ? m[Matrix.MSCALE_X] : 1f;
        return Math.round(Math.max(w, h) * sx);
    }

    /** The "next" icon's colour as drawn; the title's when it has no tint to read. */
    private static int ink(View next, TextView title) {
        int c = title != null ? title.getCurrentTextColor() : Color.WHITE;
        if (next instanceof ImageButton) {
            android.content.res.ColorStateList tl = ((ImageButton) next).getImageTintList();
            if (tl != null) c = tl.getColorForState(next.getDrawableState(), c);
        }
        return c;
    }

    private static int id(View card, boolean slot) {
        if (slot) {
            if (sSlotId == 0) sSlotId = lookup(card, "action4");
            return sSlotId;
        }
        if (sNextId == 0) sNextId = lookup(card, "action3");
        return sNextId;
    }

    private static int sChipId;

    /** The output chip's glyph, media_seamless_image. */
    private static int lookupOnce(View card) {
        if (sChipId == 0) sChipId = lookup(card, "media_seamless_image");
        return sChipId;
    }

    private static int lookup(View card, String name) {
        int i = card.getResources().getIdentifier(name, "id", "com.android.systemui");
        return i == 0 ? -1 : i;
    }

    /** {@code v}'s top-left in {@code root}'s own coordinates, translations included. */
    private static float[] offsetIn(View v, View root) {
        float x = 0f, y = 0f;
        View cur = v;
        while (cur != root) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return null;
            cur = (View) p;
        }
        return new float[] {x, y};
    }

    /**
     * The glyph: three lyric lines, or a disc for the cover. Line art on a 32-unit grid with
     * round caps, about the weight of the row's outline icons (the like heart). The disc is round
     * on purpose: a framed cover read as a lit block (user, 2026-10-14).
     */
    private static final class Glyph extends Drawable {
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        float alpha;
        boolean onLyrics;
        int ink = Color.WHITE;

        Glyph() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(float a, boolean lyrics, int color) {
            if (a == alpha && lyrics == onLyrics && color == ink) return;
            alpha = a;
            onLyrics = lyrics;
            ink = color;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            if (alpha <= 0f) return;
            int a = Math.round(Color.alpha(ink) * Math.min(1f, alpha));
            stroke.setColor((ink & 0x00ffffff) | (a << 24));
            android.graphics.Rect b = getBounds();
            float u = b.width() / 32f, x0 = b.left, y0 = b.top;
            stroke.setStrokeWidth(2.6f * u);
            if (onLyrics) {
                // On the lyric page: the cover, as a disc - an outer ring and the spindle hole.
                float cx = x0 + 16f * u, cy = y0 + 16f * u;
                c.drawCircle(cx, cy, 11.5f * u, stroke);
                c.drawCircle(cx, cy, 2.8f * u, stroke);
            } else {
                // On the cover page: the lyrics - three lines, the middle one short.
                float l = x0 + 5f * u, w = 22f * u;
                float[] widths = {1f, 0.62f, 0.84f};
                for (int i = 0; i < 3; i++) {
                    float y = y0 + (8f + 8f * i) * u;
                    c.drawLine(l, y, l + w * widths[i], y, stroke);
                }
            }
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
