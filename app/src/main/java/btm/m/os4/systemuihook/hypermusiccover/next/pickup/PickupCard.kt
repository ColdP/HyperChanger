// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.BuildConfig
import btm.m.os4.systemuihook.R
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * The pickup island's card: HyperOS's own shape, taken from 小爱同学
 * (`res/layout/memory_scene_focused_remote_view.xml`), which is the card the phone shows when
 * its assistant reads a pickup code off the screen. [R.layout.mc_pickup_card] is that layout,
 * slot for slot and dp for dp.
 *
 * What is ours is what goes in the slots and the pictures: the brand, its colour and its
 * artwork come from the rules XML (`PickupRule`) and the artwork packed into this apk, the code
 * and product from our own reading of the page. A brand with no picture draws the card without
 * one rather than not at all - the code is what the island is for.
 *
 * Two colours cannot come from the rules, because the official card puts its content on the
 * backdrop SystemUI draws (day or night), not on a background of its own: the rule under the
 * code, and the text under it. They are the ones 小爱's own card uses - `#1affffff` and its
 * neighbours - so [night] picks the pair, and the notification carries both cards.
 */
internal object PickupCard {

    private const val DEFAULT_CODE = "#3482FF"
    private const val DEFAULT_BUTTON = "#3482FF"

    /**
     * What the back screen's card keeps clear of: the rear layout's own start and end padding
     * (113 of camera, 20 of edge) plus the 10 its button sits in from the words.
     */
    private const val REAR_TEXT_INSET = 143f

    /** The official rear button's corner radius, `memory_scene_rear_action_corner_radius`. */
    private const val REAR_BUTTON_RADIUS = 30.94f

    /** The rear card's two whites: the product and the button are one, the shop is 50%. */
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val HALF_WHITE = 0x80FFFFFF.toInt()

    /** The rear button's own colour: `memory_scene_rear_action_bg`, 15% white. */
    private const val CAPSULE = 0x26FFFFFF

    /**
     * How long the drink picture set by the last [build] runs, in milliseconds (0 when it is not
     * animated or could not be read). Read by [PickupCodeIsland]'s picture hook to time the logo's
     * fade-in.
     */
    @Volatile var lastAnimationMs = 0
        private set

    /** The card's own text, per theme: what 小爱's card uses over its day and night backdrop. */
    private const val DAY_TEXT = 0xFF000000.toInt()
    private const val NIGHT_TEXT = 0xFFFFFFFF.toInt()
    private const val DAY_SUBTITLE = 0x99000000.toInt()
    private const val NIGHT_SUBTITLE = 0x99FFFFFF.toInt()
    private const val DAY_RULE = 0x1A000000
    private const val NIGHT_RULE = 0x1AFFFFFF

    /** The pill while the order is being made: it opens the order's page. */
    const val OPEN = "查看订单"
    /** The pill once the order is ready, ColorOS's 「我已取餐」: it takes the card down. */
    const val PICKED = "我已取餐"

    /**
     * [alpha] is the picture's opacity. A card that floats up is posted faint and [PickupIsland]
     * fades the live view in from there (its `fadePictureIn`); 1f is the resting state.
     */
    fun build(
        c: Context,
        rule: PickupRule?,
        code: String,
        product: String?,
        /** The recognizer's reading of the drink's temperature; it picks the cold or hot cup. */
        temperature: String?,
        store: String?,
        brand: String,
        tap: PendingIntent,
        alpha: Float = 1f,
        night: Boolean = false,
        /** What the pill says and does; the rest of the card opens the order whatever it says. */
        action: String = OPEN,
        actionTap: PendingIntent = tap,
    ): RemoteViews {
        val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_pickup_card)

        // An Icon, not a bitmap and not a resource id: a Bitmap is frozen pixels - one frame of a
        // 53-frame animation - while an Icon is handed to the system to resolve, so what the
        // ImageView ends up holding is the animated drawable and it plays.
        //
        // Every picture below goes through `setImageViewIcon` for a second reason, and it is not
        // cosmetic: this same card is the one the back screen draws (see [RearScreen]), and that
        // app inflates it with an AppCompat factory, so what its layout calls an ImageView comes
        // out an `androidx.appcompat.widget.AppCompatImageView`. That class overrides
        // `setImageBitmap`/`setImageResource`/`setImageDrawable` without `@RemotableViewMethod`, so
        // RemoteViews refuses every one of them - "view: androidx.appcompat.widget.AppCompatImageView
        // can't use method with RemoteViews" - and the back screen's app dies on the spot, on every
        // post. `setImageIcon` is not overridden there and goes through. A card is never built for
        // one process only: whatever this file hands out has to survive both.
        val file = PickupArt.model(rule, brand, product, temperature)
        val model = file?.let { PickupArt.drawable(c, it) } ?: 0
        // The whole chain in one line: which picture the rules asked for, whether this build can
        // reach it by name, and what the card was told to draw. Three different failures look the
        // same on a lock screen.
        Xp.log("MCPickupCard: brand=$brand rule=${rule?.brandName ?: "-"} model=$file" +
            " id=$model code=$code product=${product ?: "-"} store=${store ?: "-"} night=$night")
        if (model != 0) {
            views.setImageViewIcon(R.id.mc_pickup_icon,
                Icon.createWithResource(BuildConfig.APPLICATION_ID, model))
            // How long that animation runs, so the module knows when to fade the logo in over it
            // (see PickupCodeIsland's picture hook).
            lastAnimationMs = PickupArt.duration(c, model)
        } else {
            views.setViewVisibility(R.id.mc_pickup_icon, View.INVISIBLE)
            lastAnimationMs = 0
        }

        // The brand's mark over the drink, as ColorOS's card has it (`stickers/<brand>.png` in the
        // rules XML, drawn in their second picture layer). Left transparent here: the module fades
        // it in once the drink has finished filling, which is what their `LevelDView` does.
        val sticker = PickupArt.sticker(rule)?.let { PickupArt.bitmap(it, (30 * c.resources.displayMetrics.density).toInt()) }
        if (sticker != null) {
            views.setImageViewIcon(R.id.mc_pickup_logo, Icon.createWithBitmap(sticker))
        } else {
            views.setViewVisibility(R.id.mc_pickup_logo, View.GONE)
        }
        // The brand's logo is the thing that fades in, so it is what this fades.
        val step = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        if (step < 255) views.setInt(R.id.mc_pickup_icon, "setImageAlpha", step)

        views.setTextViewText(R.id.mc_pickup_title, code)
        // The official card sets its code at 28sp whatever it says; a code long enough to be cut
        // off by the one line it is given steps down instead of trailing off.
        val size = if (code.any { it.code > 127 } || code.length > 6) 24f else 28f
        views.setTextViewTextSize(R.id.mc_pickup_title, TypedValue.COMPLEX_UNIT_SP, size)
        colour(rule?.pickupColor, DEFAULT_CODE)?.let {
            views.setTextColor(R.id.mc_pickup_title, it)
        }

        views.setTextViewText(R.id.mc_pickup_product, product?.ifEmpty { null } ?: brand)
        views.setTextColor(R.id.mc_pickup_product, if (night) NIGHT_TEXT else DAY_TEXT)
        views.setTextViewText(R.id.mc_pickup_store, store.orEmpty())
        views.setTextColor(R.id.mc_pickup_store, if (night) NIGHT_SUBTITLE else DAY_SUBTITLE)
        // The rule between them is a TextView with nothing in it, drawn by colour alone.
        views.setInt(R.id.mc_pickup_divider, "setBackgroundColor",
            if (night) NIGHT_RULE else DAY_RULE)

        // The pill: a rounded rectangle drawn here, because a RemoteViews cannot tint a shape
        // from resources but can be handed a bitmap, and the radius (56dp, as in the official
        // card) is well past half the height, so it comes out as the capsule it looks like.
        val button = colour(rule?.pickupButtonColor, DEFAULT_BUTTON)
        views.setImageViewIcon(R.id.mc_pickup_action_bg,
            Icon.createWithBitmap(pill(c, button ?: 0xFF3482FF.toInt())))
        // What the pill says follows the order: 「查看订单」 while it is being made, which opens the
        // order's page, and 「我已取餐」 (ColorOS's own word for it) once it is ready, which takes
        // the card down - see [PickupIsland]'s post.
        views.setTextViewText(R.id.mc_pickup_action, action)
        views.setTextColor(R.id.mc_pickup_action, 0xFFFFFFFF.toInt())
        views.setOnClickPendingIntent(R.id.mc_pickup_action_container, actionTap)
        views.setOnClickPendingIntent(R.id.mc_pickup_root, tap)
        return views
    }

    /**
     * The card the back screen draws.
     *
     * The same slots filled the same way as [build], in the rear card's own shape - see
     * [R.layout.mc_pickup_rear], which is 小爱's own rear layout - over a background the official
     * card gets from a flat scene colour under a black gradient, and this one from the brand's own
     * colour: one vertical ramp from a quarter of it at the top to seven tenths at the bottom, so
     * the code, the words and the capsule all sit on something rather than on the black panel.
     *
     * Its colours are the official rear card's, out of 小爱's `scene_style_*_dark`: the code in the
     * brand's colour, the product white, the shop at half white, and the capsule 15% white - the
     * one colour in their rear layout that is not a scene style (`memory_scene_rear_action_bg`).
     */
    fun rear(
        c: Context,
        rule: PickupRule?,
        code: String,
        product: String?,
        /** The recognizer's reading of the drink's temperature; it picks the cold or hot cup. */
        temperature: String?,
        store: String?,
        brand: String,
        tap: PendingIntent,
        action: String = OPEN,
        actionTap: PendingIntent = tap,
    ): RemoteViews {
        val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_pickup_rear)
        // The panel's own screen, not this process's: this card is built in SystemUI, and the back
        // screen is a different size. Every size below is in units of its height / 213, as the
        // back screen's own templates are (see [RearPanel]), so the card fills a 17 Pro's shorter
        // panel the way it fills a Pro Max's instead of running off the bottom of it.
        val panel = RearPanel.of(c)
        val u = panel.byHeight()
        scaleRear(views, u)
        val accent = colour(rule?.pickupColor, DEFAULT_CODE) ?: 0xFF3482FF.toInt()
        views.setImageViewIcon(R.id.mc_pickup_rear_backdrop, Icon.createWithBitmap(backdrop(accent)))

        val file = PickupArt.model(rule, brand, product, temperature)
        val model = file?.let { PickupArt.drawable(c, it) } ?: 0
        Xp.log("MCPickupRear: brand=$brand rule=${rule?.brandName ?: "-"} model=$file" +
            " id=$model code=$code store=${store ?: "-"} panel=${panel.width}x${panel.height}")
        if (model != 0) {
            views.setImageViewIcon(R.id.mc_pickup_rear_icon,
                Icon.createWithResource(BuildConfig.APPLICATION_ID, model))
        } else {
            views.setViewVisibility(R.id.mc_pickup_rear_icon, View.INVISIBLE)
        }
        // No fade here, unlike the front card: the drink is started by the back screen's own copy
        // of this module (see [RearScreen]), and nothing there knows how long it runs for.
        val sticker = PickupArt.sticker(rule)
            ?.let { PickupArt.bitmap(it, (30 * u).toInt()) }
        if (sticker != null) {
            views.setImageViewIcon(R.id.mc_pickup_rear_logo, Icon.createWithBitmap(sticker))
        } else {
            views.setViewVisibility(R.id.mc_pickup_rear_logo, View.GONE)
        }

        views.setTextViewText(R.id.mc_pickup_rear_title, code)
        views.setTextColor(R.id.mc_pickup_rear_title, accent)
        // The same rule the front card's title follows: the official card sets its code at 28sp
        // whatever it says, and one long enough to be cut off steps down instead.
        views.textPx(R.id.mc_pickup_rear_title,
            (if (code.any { it.code > 127 } || code.length > 6) 24f else 28f) * u)
        views.setTextViewText(R.id.mc_pickup_rear_product, product?.ifEmpty { null } ?: brand)
        views.setTextColor(R.id.mc_pickup_rear_product, WHITE)
        views.setTextViewText(R.id.mc_pickup_rear_store, store.orEmpty())
        views.setTextColor(R.id.mc_pickup_rear_store, HALF_WHITE)

        // The capsule, at the width it will be drawn at: the words start 113 in and the row ends
        // 20 off the right - the rear layout's own paddings - and the official button is another
        // 10 inside that. Drawn rather than stretched, so its ends stay round.
        val width = panel.width - (REAR_TEXT_INSET * u).toInt()
        views.setImageViewIcon(R.id.mc_pickup_rear_action_bg,
            Icon.createWithBitmap(capsule(width, (42 * u).toInt(), CAPSULE,
                REAR_BUTTON_RADIUS * u)))
        views.setTextViewText(R.id.mc_pickup_rear_action, action)
        views.setTextColor(R.id.mc_pickup_rear_action, WHITE)
        views.setOnClickPendingIntent(R.id.mc_pickup_rear_action_container, actionTap)
        views.setOnClickPendingIntent(R.id.mc_pickup_rear_root, tap)
        return views
    }

    /**
     * [R.layout.mc_pickup_rear]'s sizes, which are 小爱's dp, again in [u] - one 213th of the
     * panel's height. On a 213dp panel this is the layout as written.
     */
    private fun scaleRear(v: RemoteViews, u: Float) {
        v.setViewPadding(R.id.mc_pickup_rear_content,
            (113 * u).toInt(), (47 * u).toInt(), (20 * u).toInt(), (20 * u).toInt())
        v.height(R.id.mc_pickup_rear_row, 84 * u)
        v.width(R.id.mc_pickup_rear_picture, 72 * u)
        v.height(R.id.mc_pickup_rear_picture, 84 * u)
        v.width(R.id.mc_pickup_rear_logo, 30 * u)
        v.height(R.id.mc_pickup_rear_logo, 30 * u)
        v.margin(R.id.mc_pickup_rear_logo, RemoteViews.MARGIN_BOTTOM, 30 * u)
        v.margin(R.id.mc_pickup_rear_text, RemoteViews.MARGIN_START, 6 * u)
        v.margin(R.id.mc_pickup_rear_product, RemoteViews.MARGIN_TOP, 8 * u)
        v.textPx(R.id.mc_pickup_rear_product, 14 * u)
        v.margin(R.id.mc_pickup_rear_store, RemoteViews.MARGIN_TOP, 2 * u)
        v.textPx(R.id.mc_pickup_rear_store, 12 * u)
        v.height(R.id.mc_pickup_rear_action_container, 42 * u)
        v.margin(R.id.mc_pickup_rear_action_container, RemoteViews.MARGIN_START, 10 * u)
        v.margin(R.id.mc_pickup_rear_action_container, RemoteViews.MARGIN_TOP, 20 * u)
        v.textPx(R.id.mc_pickup_rear_action, 14 * u)
    }

    /**
     * The card's background: the brand's colour, darkened the way the official card's is.
     *
     * Their rear card lays a flat scene colour down and puts a black gradient over it, opaque at
     * the top left - where the camera is, and where nothing is meant to be read - and clear at the
     * bottom. This is that composite as one vertical ramp, and it is one pixel wide: it is going to
     * be stretched across the card, and a vertical gradient has nothing to put in a second column.
     * Sixty-four rows is more than the eye can find banding in over 213dp.
     */
    private fun backdrop(accent: Int): Bitmap {
        val rows = 64
        val bitmap = Bitmap.createBitmap(1, rows, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, rows.toFloat(),
                shade(accent, 0.24f), shade(accent, 0.70f), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 1f, rows.toFloat(), paint)
        return bitmap
    }

    /** [colour] kept to [amount] of its brightness, alpha intact. */
    private fun shade(colour: Int, amount: Float): Int = Color.argb(255,
        (Color.red(colour) * amount).toInt(),
        (Color.green(colour) * amount).toInt(),
        (Color.blue(colour) * amount).toInt())

    /** The card's button: a capsule of [fill], at the 56dp radius the official card is cut to. */
    private fun pill(c: Context, fill: Int): Bitmap {
        val density = c.resources.displayMetrics.density
        return capsule((104 * density).toInt(), (37 * density).toInt(), fill, 56f * density)
    }

    /** A rectangle of [fill] whose corners are [radius] round - past half the height, a capsule. */
    private fun capsule(width: Int, height: Int, fill: Int, radius: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(4), height.coerceAtLeast(2),
            Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        Canvas(bitmap).drawRoundRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
            radius, radius, paint)
        return bitmap
    }

    /**
     * The brand's logo, for the slot the *system* draws the picture in: the island's own icon and
     * the lock screen's capsule both take it from `iconTextInfo.animIconInfo` and lay it out
     * themselves, with no card of ours involved.
     *
     * The logo rather than the card's drink, because of how that artwork is framed: the drink is a
     * 300x351 canvas whose cup - shadow included - ends 59px above the bottom edge (measured on
     * base_bg_tea_style_yellow.webp), so once it is scaled into that small square slot the cup
     * rides high and reads as a smudge. The logo is already a tight square and is legible at the
     * size the slot gives it, which is what the official card does there too.
     */
    fun logoIcon(rule: PickupRule?): Icon? =
        PickupArt.logo(rule)?.let { PickupArt.bitmap(it, 96) }?.let { Icon.createWithBitmap(it) }

    /**
     * The collapsed island: the brand's logo and the code, which is what the island is for.
     *
     * Its own layout and its own key (`miui.focus.rv.tiny`), because the small island is not the
     * big card shrunk - hiding the whole card there would leave the icon and no code.
     */
    fun tiny(c: Context, rule: PickupRule?, code: String, fallback: Bitmap, tap: PendingIntent): RemoteViews {
        val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_pickup_tiny)
        val logo = PickupArt.logo(rule)?.let { PickupArt.bitmap(it, 72) }
        Xp.log("MCPickupTiny: code=$code logo=${PickupArt.logo(rule) ?: "-"}" +
            " drawn=${logo != null} fallback=${fallback != null}")
        views.setImageViewIcon(R.id.mc_pickup_tiny_icon, Icon.createWithBitmap(logo ?: fallback))
        views.setTextViewText(R.id.mc_pickup_tiny_code, code)
        colour(rule?.pickupColor, DEFAULT_CODE)?.let {
            views.setTextColor(R.id.mc_pickup_tiny_code, it)
        }
        views.setOnClickPendingIntent(R.id.mc_pickup_tiny_icon, tap)
        views.setOnClickPendingIntent(R.id.mc_pickup_tiny_code, tap)
        return views
    }

    /** A colour out of the rules XML, or null when it is absent or unreadable. */
    private fun colour(value: String?, fallback: String): Int? = try {
        Color.parseColor(value?.takeIf { it.isNotEmpty() } ?: fallback)
    } catch (_: IllegalArgumentException) {
        try {
            Color.parseColor(fallback)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
