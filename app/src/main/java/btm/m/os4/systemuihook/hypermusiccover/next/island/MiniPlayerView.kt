// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.CoverMorphMotion
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/** HyperChanger's compact card, hosted in SystemUI's shortcut area. */
internal class MiniPlayerView(context: Context) : FrameLayout(context) {
    private var materialLayer = MaterialElement(context)
    private val artwork = ImageView(context)

    /**
     * The artwork's place: [artwork], or a live view over it (showArtworkView). Everything that
     * moves, fades, turns or rounds the artwork does it to this, so a view in it goes along.
     */
    private val slot = FrameLayout(context)

    /**
     * Everything but the glass - the picture's slot, the lines, the buttons - in one layer: the
     * one view a content blur goes on, as ColorOS blurs a capsule's whole content view
     * (SystemUIPlugin u4.m, CapsuleContentViewState). Blurred piece by piece, a card morph was
     * five blurs a frame on this side alone, and the GPU ran ~110 offscreen passes a frame through
     * a run of taps (trace 2026-09-30). At the pill's own origin, so every piece's place in the
     * pill is what it was; a morph grows it with the frame (setMorphFrame), since a blurred layer
     * is cut to its own bounds.
     */
    private val content = FrameLayout(context)
    private var liveArt: View? = null
    private val title = EdgeBlurText(context)
    private val artist = EdgeBlurText(context)
    private val textColumn = LinearLayout(context)
    private val toggle = ImageButton(context)

    /**
     * A focus notification's second button, left of the first: shown while the pill is the big
     * island, the row's only one; gone as the small island comes in beside it (setSecondShown).
     */
    private val toggle2 = ImageButton(context)
    private val density = resources.displayMetrics.density
    private var lastAppearance: String? = null
    private var lastMaterial: String? = null
    private var lastArtwork: Bitmap? = null
    /**
     * Whether the title may scroll. Off for a player singing into TITLE (Salt, 汽水, QQ): a new
     * line every few seconds would start a scroll it never finished, so it is cut instead.
     */
    var titleScrolls = true

    /**
     * A line too long for the pill scrolls once - when its words change, and when the screen
     * comes on - and then rests cut with an ellipsis. It was a switch (2026-10-14): always
     * scrolling kept the lock screen drawing every frame, always cut never showed a long title
     * at all; neither was what anyone wanted. Never in the doze.
     */
    private fun scrollOnce(line: TextView) {
        rest(line)
        if (line === title && !titleScrolls) return
        if (display?.state != android.view.Display.STATE_ON) return
        // After the layout, which the new words or a new width have yet to make.
        val start = Runnable {
            val layout = line.layout ?: return@Runnable
            val room = line.width - line.totalPaddingLeft - line.totalPaddingRight
            val words = layout.getLineWidth(0)
            if (room <= 0 || words <= room) return@Runnable
            line.ellipsize = TextUtils.TruncateAt.MARQUEE
            line.marqueeRepeatLimit = 1
            line.isSelected = true
            // TextView's own marquee: 1.2s before it moves, 30dp a second, one pass is the words
            // and the gap of a third of the width. Its end has no callback, so it is timed.
            val pass = ((words + room / 3f) / (30f * density) * 1000f).toLong()
            line.postDelayed(restOf(line), 1200L + pass + 300L)
        }
        if (line === title) titleStart = start else artistStart = start
        line.post(start)
    }

    // Each line's pending start and its rest. Fields, not view tags: this view lives in SystemUI,
    // where the module's own ids are not resources.
    private var titleStart: Runnable? = null
    private var artistStart: Runnable? = null
    private val titleRest = Runnable { cut(title) }
    private val artistRest = Runnable { cut(artist) }

    private fun restOf(line: TextView) = if (line === title) titleRest else artistRest

    private fun cut(line: TextView) {
        line.isSelected = false
        line.ellipsize = TextUtils.TruncateAt.END
    }

    private fun rest(line: TextView) {
        (if (line === title) titleStart else artistStart)?.let(line::removeCallbacks)
        line.removeCallbacks(restOf(line))
        if (line.isSelected || line.ellipsize != TextUtils.TruncateAt.END) cut(line)
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        private var on = true
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display?.displayId) return
            val now = display?.state == android.view.Display.STATE_ON
            if (now == on) return
            on = now
            if (now) { scrollOnce(title); scrollOnce(artist) } else { rest(title); rest(artist) }
        }
    }

    /**
     * The picture as it is, no corner cut and no plate under it: a focus template's picture,
     * which its row draws bare - cut to the artwork's rounded square, parts of it went missing.
     */
    private var artworkBare = false

    /** The play button's picture and meaning taken by a notification's own action. */
    private var toggleOverride: android.graphics.drawable.Drawable? = null

    /** A picture that moves (a focus template's Lottie), shown over whatever bitmap is bound. */
    private var artworkOverride: android.graphics.drawable.Drawable? = null
    private var lastPlaying: Boolean? = null

    /**
     * The colour of the words and the play button: the system's own card's (MiniPlayerController
     * .ink), not white. Under the frosted material in light mode the pill is a light frost, and
     * white on it could not be read (the user, 2026-10-14).
     */
    private var ink = Color.WHITE

    fun setInk(color: Int) {
        if (ink == color) return
        ink = color
        title.setTextColor(color)
        artist.setTextColor(color)
        if (toggleOverride == null) lastPlaying?.let { playing ->
            toggle.setImageDrawable(MiniPlayerPathDrawable(if (playing) ICON_PAUSE else ICON_PLAY, color))
        }
    }
    private var artworkRadiusPx = dp(12).toFloat()
    private var artworkRadiusDp = 12f
    private var interactionsEnabled = true
    /** Where position() puts the view at rest; a morph moves it without losing this. */
    private var baseX = 0f
    private var baseY = 0f
    private var morphing = false
    private var morphW = 0
    private var morphH = 0
    private var morphRadius = 0f
    private var artworkMorphRadius = Float.NaN
    private var artworkHidden = false
    private var onToggle: (() -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null
    private var onNext: (() -> Unit)? = null
    private var onShowNative: (() -> Unit)? = null
    private var onOpenCover: (() -> Unit)? = null
    private var tracking = false
    private var toggleShown = true
    private var lastHeightRadiusDp = 27f

    init {
        clipToOutline = true
        outlineAmbientShadowColor = Color.TRANSPARENT
        outlineSpotShadowColor = Color.TRANSPARENT
        // A pill at rest; during a morph, the container's own corner. The material layer shares
        // it, so the glass is cut to the same shape as the frame it fills.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height,
                    if (morphing) morphRadius else view.height / 2f)
            }
        }
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        materialLayer.clipToOutline = true
        materialLayer.shape = outlineProvider
        addView(materialLayer, LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, -1))
        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        slot.clipToOutline = true
        slot.background = rounded(Color.rgb(55, 55, 55), dp(12).toFloat())
        slot.clipChildren = false
        slot.addView(artwork, LayoutParams(-1, -1))
        content.addView(slot)
        title.apply {
            setTextColor(Color.WHITE)
            textSize = 12.8f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        artist.apply {
            setTextColor(Color.WHITE)
            alpha = 0.8f
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.gravity = Gravity.CENTER_VERTICAL
        // Each line gets room above and below its glyphs, taken back by the margins so the rest
        // layout is unchanged. Without it the glyphs touched the view's own clip, and scaled onto
        // the card's text in a morph that edge cut through them as a hairline.
        val room = dp(3)
        title.setPadding(0, room, 0, room)
        artist.setPadding(0, room, 0, room)
        textColumn.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = -room
            bottomMargin = -room
        })
        textColumn.addView(artist, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = -room
            bottomMargin = -room
        })
        artist.translationY = -dp(2).toFloat()
        content.addView(textColumn)
        toggle.scaleType = ImageView.ScaleType.CENTER
        toggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        toggle.background = null
        toggle.contentDescription = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(context, "hmcPlayPause", "播放或暂停")
        toggle.setOnClickListener { (toggleFaceClick ?: onToggle)?.invoke() }
        content.addView(toggle)
        toggle2.scaleType = ImageView.ScaleType.FIT_CENTER
        toggle2.setPadding(dp(6), dp(6), dp(6), dp(6))
        toggle2.background = null
        toggle2.visibility = View.GONE
        toggle2.setOnClickListener { secondFaceClick?.invoke() }
        content.addView(toggle2)
        contentDescription = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(context, "锁屏胶囊", "锁屏胶囊")
    }

    fun bind(
        trackTitle: CharSequence,
        trackArtist: CharSequence,
        cover: Bitmap?,
        playing: Boolean,
        config: JSONObject,
        material: String,
        applyMaterial: (ImageView) -> Unit,
        togglePlayback: () -> Unit,
        skipPrevious: () -> Unit,
        skipNext: () -> Unit,
        showNative: () -> Unit,
        openCover: () -> Unit,
    ) { android.os.Trace.beginSection("MC v.bind"); try {
        val appearance = "$config|$material"
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            if (MiniPlayerMaterialState.replacesLayer(lastMaterial, material)) {
                // A fresh view also drops the previous vendor blur/material state. Only for
                // another effect: the same one dressed with new values keeps its layer, and the
                // animation running in it (PR #15).
                removeView(materialLayer)
                materialLayer = MaterialElement(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    clipToOutline = true
                    shape = this@MiniPlayerView.outlineProvider
                }
                addView(materialLayer, 0, LayoutParams(-1, -1))
            }
            lastMaterial = material
            materialAgain = {
                applyMaterial(materialLayer)
                materialLayer.outlineProvider = outlineProvider
                materialLayer.clipToOutline = true
            }
            applyMaterial(materialLayer)
            // The card's recipe gives the layer its own 24dp outline; the pill's shape - and the
            // morph's changing corner - is ours. MaterialElement keeps it so between dressings
            // too: the AOD's dim sets the card's corner again every frame.
            materialLayer.outlineProvider = outlineProvider
            materialLayer.clipToOutline = true
            artworkRadiusDp = config.getDouble(MiniPlayerConfig.ART_RADIUS).toFloat()
            updateGeometry(config.getDouble(MiniPlayerConfig.HEIGHT_RADIUS).toFloat())
        }
        // Equal by their words, so a line the row rewrote as a span is not set again every frame.
        if (!TextUtils.equals(title.text, trackTitle)) { title.text = trackTitle; scrollOnce(title) }
        else if (!titleScrolls) rest(title)
        if (!TextUtils.equals(artist.text, trackArtist)) { artist.text = trackArtist; scrollOnce(artist) }
        // A line with no words in it takes no room, and the column centres what is left: an island
        // whose template gives it only a timer (the recorder's, its param_v2 an animTextInfo and
        // nothing else) drew that one line on the upper one and read off centre (the user,
        // 2026-09-28). Both lines have words, the layout is the one it always was.
        title.visibility = if (trackTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        artist.visibility = if (trackArtist.isNullOrBlank()) View.GONE else View.VISIBLE
        if (lastArtwork !== cover) {
            lastArtwork = cover
            if (artworkOverride == null) artwork.setImageBitmap(cover)
        }
        if (lastPlaying != playing) {
            lastPlaying = playing
            if (toggleOverride == null) toggle.setImageDrawable(MiniPlayerPathDrawable(
                if (playing) ICON_PAUSE else ICON_PLAY, ink))
        }
        onToggle = togglePlayback
        onPrevious = skipPrevious
        onNext = skipNext
        onShowNative = showNative
        onOpenCover = openCover
    } finally { android.os.Trace.endSection() } }

    /**
     * Only taps reach the pill itself. Every drag - any direction - belongs to
     * MiniPlayerRuntime.routeTouch, which sends a CANCEL here the moment the finger passes the
     * slop and moves the pill through setNudge; so the play button still clicks, and a drag that
     * started on it is a drag.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    /**
     * Touches go past the pill and its buttons to what is under it: the lock screen's editor
     * button, up in the pill's place (MiniPlayerController.editButtonShown). Faded out, the pill
     * still took them - its buttons too, disabled or not - and the editor button could not be
     * pressed (2026-09-26).
     */
    var yieldTouches = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (yieldTouches && event.actionMasked == MotionEvent.ACTION_DOWN) false
        else super.dispatchTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean { android.os.Trace.beginSection("MC pillTouch"); try {
        if (!acceptsTouch()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tracking = true
            MotionEvent.ACTION_UP -> {
                if (tracking) onOpenCover?.invoke()
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    } finally { android.os.Trace.endSection() } }

    /**
     * A skip turns the artwork once round, the way the swipe went - left for the next track,
     * anticlockwise - slowing to a stop. The new artwork lands whenever the player sends it,
     * under the spin rather than as a cut.
     */
    /**
     * [drawable] in the artwork's place until [clearArtworkDrawable]: it runs itself (a Lottie
     * drawable animates and invalidates the artwork, which hands it its visibility - paused
     * while the pill is gone). The bound bitmap is kept for when it goes.
     */
    fun showArtworkDrawable(drawable: android.graphics.drawable.Drawable) {
        if (artworkOverride === drawable && artwork.drawable === drawable) return
        artworkOverride = drawable
        artwork.setImageDrawable(drawable)
    }

    /**
     * [view] in the artwork's place - one of the plugin's own, running itself once in a window
     * (the torch's light, a clip) - over the bound picture, which is kept for when it goes.
     * Null takes it out.
     */
    fun showArtworkView(view: View?) {
        if (liveArt === view) return
        liveArt?.let { slot.removeView(it) }
        liveArt = view
        if (view != null) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            slot.addView(view, LayoutParams(-1, -1))
        }
        artwork.visibility = if (view == null) View.VISIBLE else View.INVISIBLE
        // A live picture draws past its own box - the torch's glow is taller than its square,
        // and its row lets it (clipChildren false up the row): at rest the pill clipped each
        // child to its bounds, and the light came out cut to a square (2026-09-26). The pill's
        // own outline still bounds it.
        if (!morphing) clipPieces(view == null)
    }

    /** Each piece cut to its own bounds, or not: the content layer is their parent now, not the pill. */
    private fun clipPieces(on: Boolean) {
        clipChildren = on
        content.clipChildren = on
    }

    fun setArtworkBare(bare: Boolean) {
        if (artworkBare == bare) return
        artworkBare = bare
        if (lastHeightRadiusDp > 0f) updateGeometry(lastHeightRadiusDp)
        slot.clipToOutline = !bare
        slot.background = if (bare) null else rounded(Color.rgb(55, 55, 55), artworkRadiusPx)
        artwork.scaleType = if (bare) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
    }

    /** The button's face a focus notification gave it, by key: rebuilt only when it changes. */
    private var toggleFaceKey: String? = null
    private var toggleFaceClick: (() -> Unit)? = null
    private var secondFaceKey: String? = null
    private var secondFaceClick: (() -> Unit)? = null

    /**
     * A focus notification's button in the play button's place - a stopwatch's pause or go on,
     * a recording's done - on its own plate; null gives the button back to play and pause.
     */
    fun setToggleFace(face: ActionFace?) {
        if (face == null) {
            toggleFaceClick = null
            if (toggleFaceKey == null) return
            toggleFaceKey = null
            toggleOverride = null
            setToggleWide(0)
            toggle.background = null
            toggle.scaleType = ImageView.ScaleType.CENTER
            toggle.setPadding(dp(8), dp(8), dp(8), dp(8))
            toggle.contentDescription = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(context, "hmcPlayPause", "播放或暂停")
            lastPlaying?.let { playing ->
                toggle.setImageDrawable(MiniPlayerPathDrawable(if (playing) ICON_PAUSE else ICON_PLAY, ink))
            }
            return
        }
        toggleFaceClick = face.onClick
        if (toggleFaceKey == face.key) return
        toggleFaceKey = face.key
        val icon = face.icon()
        toggleOverride = icon ?: android.graphics.drawable.ColorDrawable(0)
        toggle.setImageDrawable(icon)
        toggle.background = face.background()
        toggle.scaleType = if (face.wide) ImageView.ScaleType.CENTER else ImageView.ScaleType.FIT_CENTER
        // The plugin's pictures bring their own round shell: little room round them.
        val pad = if (face.wide) dp(6) else dp(FACE_PAD_DP)
        toggle.setPadding(pad, pad, pad, pad)
        toggle.contentDescription = face.label ?: ""
        setToggleWide(if (face.wide) (icon?.intrinsicWidth ?: 0) + dp(24) else 0)
    }

    /** A text button's width past the round button's, in pixels; 0 for a round one. */
    private var toggleWidePx = 0
    private var toggle2WidePx = 0

    private fun setToggleWide(px: Int) {
        if (toggleWidePx == px) return
        toggleWidePx = px
        updateGeometry(lastHeightRadiusDp)
    }

    private fun setToggle2Wide(px: Int) {
        if (toggle2WidePx == px) return
        toggle2WidePx = px
        updateGeometry(lastHeightRadiusDp)
    }

    fun setSecondFace(face: ActionFace?) {
        secondFaceClick = face?.onClick
        if (face == null) {
            if (secondFaceKey == null) return
            secondFaceKey = null
            toggle2.setImageDrawable(null)
            toggle2.background = null
            setSecondShown(false, animate = false)
            setToggle2Wide(0)
            return
        }
        if (secondFaceKey == face.key) return
        secondFaceKey = face.key
        val icon = face.icon()
        toggle2.setImageDrawable(icon)
        toggle2.background = face.background()
        toggle2.scaleType = if (face.wide) ImageView.ScaleType.CENTER else ImageView.ScaleType.FIT_CENTER
        val pad = if (face.wide) dp(6) else dp(FACE_PAD_DP)
        toggle2.setPadding(pad, pad, pad, pad)
        toggle2.contentDescription = face.label ?: ""
        setToggle2Wide(if (face.wide) (icon?.intrinsicWidth ?: 0) + dp(24) else 0)
    }

    /** How far the second button is in, 0 to 1: its alpha and size, and the text's room. */
    private var secondShown = 0f
    private var secondWanted = false
    private var secondAnimator: android.animation.ValueAnimator? = null
    private var toggleSizePx = 0
    private var toggleGapPx = 0
    private var textRightBase = 0

    /**
     * Two buttons in the big island, one - its main one - in the middle island: the second comes
     * in or goes as the pill widens or narrows for the small island, on the switch's own curve,
     * the text giving it room as it does.
     */
    fun setSecondShown(shown: Boolean, animate: Boolean) {
        val want = shown && secondFaceKey != null
        val to = if (want) 1f else 0f
        // Asked for at once, it is there at once, even over a grow already on its way there: a
        // flight landing on the pill found the refresh's grow started a frame before, and the
        // left button grew in beside a right one that was simply there (filmed 2026-09-26).
        if (want == secondWanted && (if (animate) secondAnimator != null || secondShown == to
                else secondAnimator == null && secondShown == to)) return
        secondWanted = want
        secondAnimator?.cancel()
        secondAnimator = null
        if (!animate || !isShown) {
            applySecond(to)
            return
        }
        secondAnimator = android.animation.ValueAnimator.ofFloat(secondShown, to).apply {
            duration = SECOND_MS
            interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1f)
            addUpdateListener { applySecond(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (secondAnimator === animation) secondAnimator = null
                }
            })
            start()
        }
    }

    /**
     * The second button [v] of the way in, set from outside frame by frame: a stand-in leaving
     * the big island for the small place takes it away as it shrinks. Nothing without a face.
     */
    fun setSecondProgress(v: Float) {
        if (secondFaceKey == null) return
        secondAnimator?.cancel()
        secondAnimator = null
        secondWanted = v >= 0.5f
        val to = v.coerceIn(0f, 1f)
        if (to != secondShown) applySecond(to)
    }

    private fun applySecond(v: Float) {
        secondShown = v
        val vis = if (v > 0.001f) View.VISIBLE else View.GONE
        if (toggle2.visibility != vis) toggle2.visibility = vis
        toggle2.alpha = v * contentAlpha
        val k = 0.6f + 0.4f * v
        toggle2.scaleX = k
        toggle2.scaleY = k
        val lp = textColumn.layoutParams as? LayoutParams ?: return
        val margin = textRightBase + ((toggleSizePx + toggleGapPx) * v).toInt()
        if (lp.rightMargin != margin) {
            lp.rightMargin = margin
            textColumn.layoutParams = lp
        }
    }

    val secondToggleView: View get() = toggle2

    /** For `op mini`: how far the second button is in, + if it is wanted, and its face. */
    fun secondState(): String = "%.2f".format(secondShown) + (if (secondWanted) "+" else "-") +
        (if (secondFaceKey == null) "nf" else "") + (if (secondAnimator != null) "A" else "")

    fun clearArtworkDrawable() {
        if (artworkOverride == null) return
        artworkOverride = null
        artwork.setImageBitmap(lastArtwork)
    }

    /** The artwork alone, for a scaled copy that arrives between refreshes. */
    fun showArtwork(bitmap: Bitmap?) {
        if (bitmap == null || lastArtwork === bitmap) return
        lastArtwork = bitmap
        if (artworkOverride == null) artwork.setImageBitmap(bitmap)
    }

    /**
     * The pill holds the music, whose sideways swipe skips a track and turns the artwork once
     * round. A notification island has no track to skip: its picture stays still (2026-09-25).
     */
    var skippable = true

    fun performSkip(next: Boolean) {
        if (!skippable) return
        if (next) onNext?.invoke() else onPrevious?.invoke()
        if (morphing) return
        slot.animate().cancel()
        slot.rotation = 0f
        slot.animate()
            .rotationBy(if (next) -360f else 360f)
            .setDuration(560L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1f))
            .withEndAction { slot.rotation = 0f }
            .start()
    }

    /** The drag's offset on top of the rest place, in pixels; a spring takes it home. */
    private var offsetX = 0f
    private var offsetY = 0f
    private val springX = CoverMorphMotion()
    private val springY = CoverMorphMotion()
    private var springLast = 0L
    private val springFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC viewSpring"); try {
            val dt = if (springLast == 0L) 1f / 120f
                else ((frameTimeNanos - springLast) / 1e9f).coerceIn(0f, 0.05f)
            springLast = frameTimeNanos
            springX.step(dt, OFFSET_RESPONSE)
            springY.step(dt, OFFSET_RESPONSE)
            if (springX.atRest() && springY.atRest()) {
                applyOffset(0f, 0f)
                return
            }
            applyOffset(springX.value * 100f, springY.value * 100f)
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    val nudgeX: Float get() = offsetX
    val nudgeY: Float get() = offsetY

    /** Where the finger has pulled the pill to; the caller rubber-bands it. */
    fun setNudge(x: Float, y: Float) {
        Gen.unframe(springFrame)
        applyOffset(x, y)
    }

    /** Home with the finger's own speed, so a fling carries past the rest place and back. */
    fun springNudgeBack(vx: Float, vy: Float) {
        if (offsetX == 0f && offsetY == 0f) return
        springX.value = offsetX / 100f
        springY.value = offsetY / 100f
        springX.velocity = vx / 100f
        springY.velocity = vy / 100f
        springX.target = 0f
        springY.target = 0f
        springLast = 0L
        Gen.unframe(springFrame)
        Gen.frame(springFrame)
    }

    /** A finger on a pill still springing home: it stays where it is, under the finger. */
    fun holdNudge() {
        Gen.unframe(springFrame)
    }

    /** The morph takes the nudge over (MiniCardMorph's own), so the pill's goes to nothing. */
    fun clearNudge() = setNudge(0f, 0f)

    private fun applyOffset(x: Float, y: Float) {
        offsetX = x
        offsetY = y
        if (morphing) return
        translationX = rest(baseX, offsetX)
        translationY = rest(baseY, offsetY)
    }

    /** Re-applies the material now; for a first show that came before the pill had a size. */
    private var materialAgain: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!morphing && w > 1 && h > 1 && (oldw <= 1 || oldh <= 1)) materialAgain?.invoke()
    }

    private fun finish() {
        tracking = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.let { Gen.displayListener(it, displayListener, handler) }
    }

    override fun onDetachedFromWindow() {
        finish()
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.let { Gen.undisplayListener(it, displayListener) }
        rest(title)
        rest(artist)
        super.onDetachedFromWindow()
    }

    /** The artwork's place, which everything that moves the artwork moves. */
    val artworkView: View get() = slot

    /** The picture the artwork shows now, under a live view if one is over it. */
    val artworkDrawable: android.graphics.drawable.Drawable? get() = artwork.drawable

    /** For `op mini`: the artwork as it stands - shown, alpha, frame, bitmap. */
    fun artworkState(): String {
        val b = (artwork.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        return "v=${slot.visibility} a=${"%.2f".format(slot.alpha)} ${slot.left},${slot.top} " +
            "${slot.width}x${slot.height} bmp=${b?.let { "${it.width}x${it.height}${if (it.isRecycled) " RECYCLED" else ""}" }} " +
            "hidden=$artworkHidden text=${"%.2f".format(textColumn.alpha)}"
    }
    val titleView: TextView get() = title
    val artistView: TextView get() = artist
    val toggleView: View get() = toggle

    /** The size position() asked for; a morph changes the frame, not this. */
    private fun restWidth() = layoutParams?.width?.takeIf { it > 1 } ?: width
    private fun restHeight() = layoutParams?.height?.takeIf { it > 1 } ?: height

    /** The rest frame on screen, whatever a morph is doing to the view meanwhile. */
    fun restBoxOnScreen(): CoverMorphMotion.Box? {
        val host = parent as? View ?: return null
        if (!isAttachedToWindow || restWidth() <= 1 || restHeight() <= 1) return null
        val xy = IntArray(2).also(host::getLocationOnScreen)
        return CoverMorphMotion.Box(xy[0] + left + baseX, xy[1] + top + baseY,
            restWidth().toFloat(), restHeight().toFloat())
    }

    /** The artwork's rest slot on screen: where a flight from the cover has to land. */
    fun artworkRestBoxOnScreen(): CoverMorphMotion.Box? {
        val rest = restBoxOnScreen() ?: return null
        if (slot.width <= 0 || slot.height <= 0) return null
        return CoverMorphMotion.Box(rest.x + slot.left, rest.y + slot.top,
            slot.width.toFloat(), slot.height.toFloat())
    }

    fun artworkRestRadius(): Float = artworkRadiusPx

    fun setBaseTranslation(x: Float, y: Float) {
        baseX = x
        baseY = y
        if (morphing) return
        translationX = rest(x, offsetX)
        translationY = rest(y, offsetY)
    }

    /**
     * At rest, on a whole pixel. The glass draws its shape on the pixel grid while the frame's
     * outline clip follows the view wherever it is, so a pill left on a half pixel had the two
     * half a pixel apart: the rim was cut into steps along one arc - the right one, at
     * 268.5,2363.5 - and whole along the other. Centred between the buttons, the 477px pill
     * beside a small island lands on .5 on a 1200px screen and the full one does not, which is
     * why some phones and some widths showed it and others never did (`op edge` pixels,
     * 2026-09-30: the arc's wobble 0.3px -> 0.06px, both sides alike, once rounded). A nudge in
     * flight keeps its fractions, or it would move in whole-pixel steps.
     */
    private fun rest(base: Float, offset: Float): Float =
        if (offset == 0f) kotlin.math.round(base) else base + offset

    fun acceptsTouch(): Boolean = interactionsEnabled && (!morphing || layoutOnly)

    /** A frame of its own is on: a morph, a switch or the row's spring is drawing it. */
    fun inMorph(): Boolean = morphing

    /** For `op mini`: why it would not take a touch. */
    fun touchState(): String = "interactive=$interactionsEnabled morphing=$morphing" +
        "${if (morphing && layoutOnly) "(layout)" else ""} play=${if (toggle.isEnabled) "on" else "off"}"

    fun setInteractionsEnabled(enabled: Boolean) {
        if (interactionsEnabled == enabled) return
        interactionsEnabled = enabled
        // As beginMorph and acceptsTouch have it: a morph that only lays the pill out (the row's
        // spring narrowing it for a small island, a switch) leaves its buttons working. Given
        // back mid-way through one as `!morphing`, the play button stayed off after a message
        // came in beside the music until the next full morph ended (issue #35).
        toggle.isEnabled = enabled && (!morphing || layoutOnly)
        toggle2.isEnabled = toggle.isEnabled
        if (!enabled) finish()
    }

    /**
     * While a cover flight owns the artwork's pixels, the slot stays empty. A morph that only
     * moves the frame in the row - a switch, the row's spring - does not own the artwork's
     * alpha: the flight out of the cover landed while the small island was pushing the pill
     * narrower, the artwork was left hidden until that spring had settled, and it vanished
     * and came back (filmed 2026-09-25).
     */
    fun setArtworkHidden(hidden: Boolean) {
        if (artworkHidden == hidden) return
        artworkHidden = hidden
        if (!morphing) slot.alpha = if (hidden) 0f else 1f
        else if (layoutOnly) slot.alpha = if (hidden) 0f else contentAlpha
    }

    /**
     * [layoutOnly]: the frame only moves and resizes within the row - a switch, the row's layout
     * spring - and the pill keeps taking touches through it. Only a morph into a card or a row
     * shuts them off. The layout spring runs every time a notification comes or goes, and with
     * a busy group chat that shut the pill to touches most of the time (filmed 2026-09-25).
     */
    private var layoutOnly = false

    fun beginMorph(layoutOnly: Boolean = false) {
        if (morphing) return
        this.layoutOnly = layoutOnly
        // Over the media card for the morph - the card's layer is drawn after the pill's - and
        // back down among the lock screen when it ends. Z orders siblings without moving the
        // pill in its parent; the outline's shadow is switched off so the height casts none.
        translationZ = MORPH_Z
        slot.animate().cancel()
        slot.rotation = 0f
        morphing = true
        morphW = restWidth()
        morphH = restHeight()
        morphRadius = morphH / 2f
        toggle.isEnabled = layoutOnly && interactionsEnabled
        toggle2.isEnabled = toggle.isEnabled
        // A clipping parent clips each child to that child's own bounds: the text column is only
        // as tall as the pill, and the artist, moved onto the card's, was cut through by its
        // bottom edge - filmed as a hairline across the name. The pill's own outline still
        // clips everything to the container.
        clipPieces(false)
        textColumn.clipChildren = false
        finish()
    }

    /**
     * The container, in screen pixels. The frame itself is resized - never scaled - so the
     * material fills it at its own resolution and keeps its outline clip. custom.8 stretched it
     * with that clip switched off, and the recording shows it black for the whole flight.
     * setLeftTopRightBottom moves the frame without a layout pass;
     * onLayout() below keeps a pass that happens anyway from putting the rest size back.
     */
    fun setMorphFrame(box: CoverMorphMotion.Box, radius: Float, materialAlpha: Float) {
        if (!morphing) return
        val host = parent as? View ?: return
        val xy = traced("MC f.loc") { IntArray(2).also(host::getLocationOnScreen) }
        val w = max(1, box.w.roundToInt())
        val h = max(1, box.h.roundToInt())
        val r = radius.coerceIn(0f, min(w, h) / 2f)
        // A frame that only moves keeps its bounds and its outline: both were set again every
        // frame, the outline twice over - a resize rebuilds it by itself (View.sizeChange).
        val resized = w != width || h != height || materialLayer.width != w || materialLayer.height != h
        val rounded = r != morphRadius
        morphW = w
        morphH = h
        morphRadius = r
        if (resized) {
            traced("MC f.bounds") { setLeftTopRightBottom(left, top, left + morphW, top + morphH) }
            traced("MC f.matBounds") { materialLayer.setLeftTopRightBottom(0, 0, morphW, morphH) }
            growContent()
        }
        translationX = box.x - xy[0] - left
        translationY = box.y - xy[1] - top
        materialLayer.alpha = materialAlpha.coerceIn(0f, 1f)
        // Moving and resizing in the row, the play button keeps to the frame's right end. The
        // layout is already at the new width: laid out there, the button jumped to where the
        // pill was going the moment it set off, and the pill caught up with it (2026-09-25).
        if (layoutOnly) {
            val dx = (morphW - restWidth()).toFloat()
            if (toggle.translationX != dx) toggle.translationX = dx
            if (toggle2.translationX != dx) toggle2.translationX = dx
        }
        // Only the corner changed: the outline is not rebuilt by a resize this frame.
        if (rounded && !resized) traced("MC f.outline") {
            invalidateOutline()
            materialLayer.invalidateOutline()
        }
    }

    /** The artwork's corner in its own pixels while it is scaled onto the card's. */
    fun setArtworkMorphRadius(radius: Float) {
        if (artworkMorphRadius == radius) return
        artworkMorphRadius = radius
        slot.invalidateOutline()
    }

    fun endMorph() {
        if (!morphing) return
        morphing = false
        // The play button back in its own place, where a row move had carried it (setMorphFrame).
        if (layoutOnly) {
            toggle.translationX = 0f
            toggle2.translationX = 0f
        }
        layoutOnly = false
        artworkMorphRadius = Float.NaN
        slot.invalidateOutline()
        slot.alpha = if (artworkHidden) 0f else 1f
        materialLayer.alpha = 1f
        textColumn.clipChildren = true
        clipPieces(liveArt == null)
        // Laid out at the rest size again by the pass asked for below.
        contentW = 0
        contentH = 0
        translationZ = 0f
        toggle.isEnabled = interactionsEnabled
        toggle2.isEnabled = toggle.isEnabled
        translationX = rest(baseX, offsetX)
        translationY = rest(baseY, offsetY)
        requestLayout()
        invalidateOutline()
        materialLayer.invalidateOutline()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!morphing) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        // A layout pass mid-morph has just put the rest frame back (layout() is final): the
        // container frame goes straight back on, in the same pass, before anything is drawn.
        // Children keep their rest places - the morph positions them from there - and only the
        // material follows the frame.
        super.onLayout(changed, left, top, left + restWidth(), top + restHeight())
        setLeftTopRightBottom(left, top, left + morphW, top + morphH)
        materialLayer.layout(0, 0, morphW, morphH)
        // The pass laid the content layer out at the rest size too: back to the size it had grown
        // to, its pieces staying where the pass put them.
        if (contentW > content.width || contentH > content.height) {
            content.setLeftTopRightBottom(0, 0, max(contentW, content.width), max(contentH, content.height))
        }
        growContent()
    }

    /** The content layer's bounds through a morph: grown ahead of the frame, never shrunk. */
    private var contentW = 0
    private var contentH = 0

    /**
     * The content layer at least as big as the frame, so a blur on it cuts nothing the frame
     * shows. Grown in steps with room to spare rather than every frame: a blurred layer's
     * offscreen image is remade whenever its size changes, which is the reallocation the trace
     * counted 137 times a second (2026-09-30). Its pieces are not laid out again.
     */
    private fun growContent() {
        val w = max(content.width, contentW)
        val h = max(content.height, contentH)
        if (morphW <= w && morphH <= h) return
        contentW = max(w, (morphW * CONTENT_ROOM).roundToInt())
        contentH = max(h, (morphH * CONTENT_ROOM).roundToInt())
        content.setLeftTopRightBottom(0, 0, contentW, contentH)
    }

    /**
     * A notification island has no play button: its text runs on to the edge instead. The
     * button still holds its place in the layout while it is hidden, so hiding it lays out.
     */
    fun setToggleShown(shown: Boolean) {
        if (toggleShown == shown) return
        toggleShown = shown
        toggle.visibility = if (shown) View.VISIBLE else View.GONE
        updateGeometry(lastHeightRadiusDp)
    }

    /**
     * The pill's content - artwork, text, button - at one alpha, the glass left alone: an
     * island switch brings the new island's content in over the frame as it grows.
     */
    /** The content's alpha a switch last set, for the artwork coming back from a cover flight. */
    private var contentAlpha = 1f

    /** For `op mini`: the content's alpha as drawn (the text column's). */
    fun contentAlphaNow(): Float = textColumn.alpha

    /**
     * Held out of sight under a flight (transitionAlpha 0), it draws nothing at all. At an alpha
     * of 0 its content stayed in the tree, and the flight's glass over it took it in: pulled up,
     * the buttons came through blurred as the flight's own faded, and the time as a blurred
     * double beside the flight's (filmed 2026-09-26).
     */
    override fun setTransitionAlpha(alpha: Float) {
        val wasHidden = transitionAlpha <= 0f
        super.setTransitionAlpha(alpha)
        if (wasHidden != (alpha <= 0f)) invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (transitionAlpha <= 0f) return
        super.dispatchDraw(canvas)
    }

    fun setContentAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        contentAlpha = a
        slot.alpha = if (artworkHidden) 0f else a
        textColumn.alpha = a
        toggle.alpha = a
        toggle2.alpha = a * secondShown
    }

    /**
     * The row coming up out of nothing, its content in two beats: the picture pops in on its own
     * centre, the text follows sliding in from the left by [textShift] pixels. Nothing else moves
     * these two pieces this way; [clearAppearStagger] puts them back.
     */
    fun setAppearStagger(iconScale: Float, iconAlpha: Float, textShift: Float, textAlpha: Float) {
        slot.pivotX = slot.width / 2f
        slot.pivotY = slot.height / 2f
        slot.scaleX = iconScale
        slot.scaleY = iconScale
        slot.alpha = if (artworkHidden) 0f else iconAlpha.coerceIn(0f, 1f)
        textColumn.translationX = textShift
        setTextAlpha(textAlpha)
    }

    fun clearAppearStagger() {
        slot.scaleX = 1f
        slot.scaleY = 1f
        textColumn.translationX = 0f
    }

    /** The text and the button alone: an island shrinking to its circle keeps its picture. */
    fun setTextAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        textColumn.alpha = a
        toggle.alpha = a
        toggle2.alpha = a * secondShown
    }

    private var contentBlur = 0f

    /**
     * The content blurred by [radius] pixels, the glass left sharp: the super island's
     * BIG_ISLAND_BLUR, an island's content going out of focus as it leaves the big island's
     * place and coming into focus as it arrives there.
     */
    fun setContentBlur(radius: Float) {
        contentBlur = if (radius < 0.5f) 0f else radius
        pushContentBlur()
    }

    /** The radius a card morph last asked for (setMorphContentBlur); it wins over a switch's while set. */
    private var morphBlur = 0f

    /**
     * All of the content blurred together by [radius] screen pixels, the glass left sharp - as
     * ColorOS blurs a capsule's whole content view (SystemUIPlugin CapsuleContentViewState) -
     * through a card morph, which scales and moves each piece on its own inside the layer. One
     * blur on the content layer, in its own pixels, which are the screen's: the layer is never
     * scaled, only the pieces in it. It is grown with the morph's frame (growContent), so a line
     * moved out of its column is not cut off at the layer's edge the way it was at the column's
     * (filmed 2026-09-30). DECAL, so the blur fades out at the layer's bounds rather than
     * stretching its edge into a hard block.
     */
    fun setMorphContentBlur(radius: Float) {
        morphBlur = if (radius < 0.5f) 0f else radius
        pushContentBlur()
    }

    /** The radius on the content layer now; none yet, so the first is always written. */
    private var contentBlurred = -1f

    private fun pushContentBlur() {
        val r = if (morphBlur > 0f) morphBlur else contentBlur
        if (contentBlurred >= 0f && kotlin.math.abs(r - contentBlurred) < 0.25f &&
            (r == 0f) == (contentBlurred == 0f)) return
        contentBlurred = r
        content.setRenderEffect(if (r == 0f) null
            else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL))
    }

    /**
     * The pill's bits follow its height; music artwork retains the host's chosen corner radius.
     * Bare focus-template pictures keep their existing shape.
     */
    private fun updateGeometry(heightRadiusDp: Float) {
        lastHeightRadiusDp = heightRadiusDp
        val height = dp(heightRadiusDp * 2f).coerceAtLeast(dp(48))
        val verticalPadding = max(dp(7), height / 9)
        val artworkSize = (height * ShortcutDisc.ICON_SHARE).toInt().coerceAtLeast(dp(24))
        val horizontalPadding = max(dp(10), height / 7)
        slot.layoutParams = LayoutParams(artworkSize, artworkSize, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding
        }
        val toggleSize = dp(40).coerceAtMost((height - verticalPadding * 2).coerceAtLeast(dp(34)))
        val toggleW = max(toggleSize, toggleWidePx)
        val toggle2W = max(toggleSize, toggle2WidePx)
        toggle.layoutParams = LayoutParams(toggleW, toggleSize, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = max(dp(8), verticalPadding)
        }
        // The second button's own width: what the text gives up for it as it comes in.
        toggleSizePx = toggle2W
        // Two shells side by side, as the row sets them (2026-09-25: 6dp read as too far apart).
        toggleGapPx = dp(FACE_GAP_DP)
        toggle2.layoutParams = LayoutParams(toggle2W, toggleSize, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = max(dp(8), verticalPadding) + toggleW + toggleGapPx
        }
        textRightBase = if (toggleShown) toggleW + max(dp(10), verticalPadding) else horizontalPadding
        textColumn.layoutParams = LayoutParams(-1, -1, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding + artworkSize + max(dp(10), height / 8)
            rightMargin = textRightBase + ((toggle2W + toggleGapPx) * secondShown).toInt()
        }
        val radius = (artworkRadiusDp * density).coerceIn(0f, artworkSize / 2f)
        artworkRadiusPx = radius
        slot.background = if (artworkBare) null else rounded(Color.rgb(55, 55, 55), radius)
        slot.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height,
                    if (artworkMorphRadius.isNaN()) radius else artworkMorphRadius)
            }
        }
        slot.invalidateOutline()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun dp(value: Float) = (value * density + .5f).toInt()
}

/**
 * A focus notification's button as a pill draws it: its picture and its plate, made when first
 * shown (by [key]: the same button is not made again), and what a tap does.
 */
internal class ActionFace(
    val key: String,
    val icon: () -> android.graphics.drawable.Drawable?,
    val background: () -> android.graphics.drawable.Drawable?,
    val label: CharSequence?,
    val onClick: () -> Unit,
    /** Words, not a picture (a text button): the button as wide as they are, on its plate. */
    val wide: Boolean = false,
)

private const val SECOND_MS = 320L

/** Room round a focus button's picture, and between two of them. */
private const val FACE_PAD_DP = 4
private const val FACE_GAP_DP = 2

private class MiniPlayerPathDrawable(pathData: String, color: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = min(width, height) / 960f
        canvas.scale(scale, scale)
        canvas.translate((width / scale - 960f) / 2f, (height / scale - 960f) / 2f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private const val ICON_PLAY =
    "M320 687V273Q320 256 332 244.5Q344 233 360 233Q365 233 370.5 234.5Q376 236 381 239L707 446Q716 452 720.5 461Q725 470 725 480Q725 490 720.5 499Q716 508 707 514L381 721Q376 724 370.5 725.5Q365 727 360 727Q344 727 332 715.5Q320 704 320 687ZM400 346 610 480 400 614ZM400 614 610 480 400 346Z"
private const val ICON_PAUSE =
    "M640 760Q607 760 583.5 736.5Q560 713 560 680V280Q560 247 583.5 223.5Q607 200 640 200Q673 200 696.5 223.5Q720 247 720 280V680Q720 713 696.5 736.5Q673 760 640 760ZM320 760Q287 760 263.5 736.5Q240 713 240 680V280Q240 247 263.5 223.5Q287 200 320 200Q353 200 376.5 223.5Q400 247 400 280V680Q400 713 376.5 736.5Q353 760 320 760Z"

/** Seconds per undamped cycle for the sideways spring home: quicker than the morph's. */
private const val OFFSET_RESPONSE = 0.32f

/** Above every sibling while a morph runs; CoverMorphLayer sits above this again. */
internal const val MORPH_Z = 10000f

/** How far past the frame the content layer grows at a time (growContent). */
private const val CONTENT_ROOM = 1.3f

/**
 * A plugin view at the size the row's layout gives it, scaled into whatever box it is shown in:
 * its pictures and glow are pixels for that size (FlashLightView's textures are its own bitmaps,
 * its inner view as tall as the glow), and laid out at another size they were cut or misplaced.
 * [nativePx] 0 lays the view out at the box's own size.
 */
internal class LiveArtHost(context: Context, private val child: View, private val nativePx: Int) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
        addView(child, LayoutParams(-1, -1))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = if (nativePx > 0) nativePx else w
        val ch = if (nativePx > 0) nativePx else h
        child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        val cw = child.measuredWidth
        val ch = child.measuredHeight
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        child.layout(l, t, l + cw, t + ch)
        val k = if (nativePx > 0) min(w, h).toFloat() / nativePx else 1f
        child.pivotX = cw / 2f
        child.pivotY = ch / 2f
        child.scaleX = k
        child.scaleY = k
    }
}

/**
 * A line whose ends, where a scrolling line is cut by its box, blur and fade out rather than
 * stopping at a hard edge: two upright lines ran down the pill while its title scrolled
 * (2026-09-26). How much of each end is cut is the marquee's own reading - the fading edge
 * strengths, 0 at the start before it moves, 0 on the right once it has run out - so a line
 * that fits, or the end that is not cut, is left sharp, and one that fits carries no effect.
 * The fade is the framework's fading edge; the blur is a RenderEffect over the line, redone only
 * when an end's strength changes by a step.
 */
internal class EdgeBlurText(context: Context) : TextView(context) {
    private val edgePx = 16f * resources.displayMetrics.density
    private val blurPx = 3f * resources.displayMetrics.density
    private var shader: android.graphics.RuntimeShader? = null
    private var left = 0f
    private var right = 0f
    private var shownLeft = -1f
    private var shownRight = -1f
    private var shownWidth = -1

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(edgePx.toInt())
    }

    override fun getLeftFadingEdgeStrength(): Float = super.getLeftFadingEdgeStrength().also { left = it }

    override fun getRightFadingEdgeStrength(): Float = super.getRightFadingEdgeStrength().also { right = it }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The strengths were read for this frame's fade just before; the blur follows them.
        val l = step(left)
        val r = step(right)
        if (l == shownLeft && r == shownRight && width == shownWidth) return
        shownLeft = l
        shownRight = r
        shownWidth = width
        post { applyEdges(l, r) }
    }

    private fun step(v: Float) = (v.coerceIn(0f, 1f) * 10f).roundToInt() / 10f

    private fun applyEdges(l: Float, r: Float) {
        if (l == 0f && r == 0f || width <= 0) {
            edgeEffect = null
            push()
            return
        }
        val s = shader ?: android.graphics.RuntimeShader(EDGE_BLUR).also { shader = it }
        s.setFloatUniform("width", width.toFloat())
        s.setFloatUniform("edgeL", edgePx * l)
        s.setFloatUniform("edgeR", edgePx * r)
        s.setFloatUniform("maxBlur", blurPx)
        edgeEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content")
        push()
    }

    /**
     * The ends' blur, in this line's own pixels. A card morph's blur of the whole content is the
     * pill's content layer's (MiniPlayerView.setMorphContentBlur), not the line's.
     */
    private var edgeEffect: android.graphics.RenderEffect? = null

    private fun push() {
        setRenderEffect(edgeEffect)
    }

    private companion object {
        /** A horizontal blur growing to [maxBlur] at each cut end, over [edgeL] and [edgeR]. */
        const val EDGE_BLUR = """
            uniform shader content;
            uniform float width;
            uniform float edgeL;
            uniform float edgeR;
            uniform float maxBlur;
            half4 main(float2 p) {
                float e = 0.0;
                if (edgeL > 0.0) e = max(e, 1.0 - clamp(p.x / edgeL, 0.0, 1.0));
                if (edgeR > 0.0) e = max(e, 1.0 - clamp((width - p.x) / edgeR, 0.0, 1.0));
                if (e <= 0.0) return content.eval(p);
                float r = e * e * maxBlur;
                half4 c = half4(0.0);
                float total = 0.0;
                for (int i = -4; i <= 4; i++) {
                    float w = exp(-float(i * i) / 8.0);
                    c += content.eval(p + float2(float(i) * r / 4.0, 0.0)) * w;
                    total += w;
                }
                return c / total;
            }
        """
    }
}
