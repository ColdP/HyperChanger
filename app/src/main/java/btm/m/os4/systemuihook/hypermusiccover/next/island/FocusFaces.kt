// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A countdown's digits as the focus template turns them (the plugin's HyperChronometer with a
 * CharChangeProcessor, effect level 2 - set for timerType -1 only): each character that changes
 * swaps in place, the old one sinking to the baseline's depth and shrinking to 0.8 as it fades,
 * the new one dropping in from as far above, growing from 0.8 (HyperChronometerEffectSpan
 * .startTextChangeAnimation, TextSwitcherAnimator). Both on FolmeEase.spring(0.75, 0.35), the
 * rightmost digit first and each one to its left 15ms later. Digits keep the widest digit's
 * width, so nothing beside them moves.
 */
internal class RollingDigits(private val view: TextView) {
    private var last: String? = null

    /** One per character, counted from the right: the right end is where a count ticks. */
    private val spans = ArrayList<DigitSpan>()

    fun set(text: String) {
        if (text == last && view.text is Spanned) return
        val old = last
        last = text
        val now = SystemClock.uptimeMillis()
        val out = SpannableString(text)
        var digit = -1
        for (k in text.indices) {
            val i = text.length - 1 - k
            val c = text[i]
            if (c.isDigit()) digit++
            val span = spans.getOrNull(k) ?: DigitSpan().also { spans.add(it) }
            span.numeric = c.isDigit()
            val before = old?.let { o -> o.getOrNull(o.length - 1 - k) }
            val delay = max(0, if (c.isDigit()) digit else 0) * STAGGER_MS
            when {
                old == null -> span.hold(c)
                before != c -> span.change(before, c, now + delay)
                else -> span.hold(c)
            }
            out.setSpan(span, i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        while (spans.size > text.length) spans.removeAt(spans.size - 1)
        view.text = out
        Gen.removeCallbacksOn(view, frame)
        if (spans.any { it.running(now) }) Gen.postOnAnimation(view, frame)
    }

    /** Back to plain text: the spans go, and nothing is left running. */
    fun clear() {
        Gen.removeCallbacksOn(view, frame)
        last = null
        spans.clear()
    }

    private val frame = object : Runnable {
        override fun run() {
            view.invalidate()
            if (spans.any { it.running(SystemClock.uptimeMillis()) }) Gen.postOnAnimation(view, this)
        }
    }

    private class DigitSpan : ReplacementSpan() {
        var numeric = false
        private var from: Char? = null
        private var to: Char = ' '
        private var start = 0L

        fun change(old: Char?, new: Char, at: Long) {
            from = old
            to = new
            start = at
        }

        fun hold(c: Char) {
            from = null
            to = c
            start = 0L
        }

        fun running(now: Long) = start != 0L && now - start < SETTLE_MS

        private fun width(paint: Paint): Float =
            if (numeric) (0..9).maxOf { paint.measureText(it.toString()) } else paint.measureText(to.toString())

        override fun getSize(paint: Paint, text: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return kotlin.math.round(width(paint)).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int,
                          bottom: Int, paint: Paint) {
            val w = width(paint)
            val now = SystemClock.uptimeMillis()
            val p = if (start == 0L) 1f else spring((now - start) / 1000f)
            if (p >= 1f || from == null && start == 0L) {
                glyph(canvas, to, x, w, y.toFloat(), 0f, 1f, 1f, paint)
                if (p >= 1f && start != 0L && !running(now)) start = 0L
                return
            }
            // How far each travels: the baseline's depth in the line, as the plugin's span has it.
            val travel = (y - top).toFloat()
            from?.let { glyph(canvas, it, x, w, y.toFloat(), p * travel, 1f - 0.2f * p, 1f - p, paint) }
            glyph(canvas, to, x, w, y.toFloat(), -(1f - p) * travel, 0.8f + 0.2f * p, p, paint)
        }

        private fun glyph(canvas: Canvas, c: Char, x: Float, w: Float, y: Float, dy: Float, scale: Float,
                          alpha: Float, paint: Paint) {
            if (alpha <= 0.001f) return
            val s = c.toString()
            val cw = paint.measureText(s)
            val keep = paint.alpha
            paint.alpha = (keep * alpha.coerceIn(0f, 1f)).toInt()
            canvas.save()
            val cx = x + w / 2f
            // About the glyph's own middle, in the frame the translation has already moved.
            val cy = y - paint.textSize * 0.35f
            canvas.translate(0f, dy)
            canvas.scale(scale, scale, cx, cy)
            canvas.drawText(s, cx - cw / 2f, y, paint)
            canvas.restore()
            paint.alpha = keep
        }
    }

    companion object {
        private const val STAGGER_MS = 15L
        private const val SETTLE_MS = 900L
        private const val DAMPING = 0.75f
        private const val RESPONSE = 0.35f

        /** FolmeEase.spring(0.75, 0.35) from 0 to 1, at [t] seconds: an underdamped spring, solved. */
        fun spring(t: Float): Float {
            if (t <= 0f) return 0f
            val w = 2.0 * PI / RESPONSE
            val zw = DAMPING * w
            val wd = w * sqrt(1.0 - DAMPING * DAMPING)
            val v = 1.0 - exp(-zw * t) * (cos(wd * t) + zw / wd * sin(wd * t))
            return v.toFloat()
        }
    }
}

/**
 * A focus template's text button (type 2): its title on its plate, the plate drawn by the
 * button's background and this the words, centred, as wide as they are.
 */
internal class TextFaceDrawable(private val text: String, color: Int, sizePx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sizePx
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false)
    }
    private val w = paint.measureText(text)
    private val fm = paint.fontMetrics

    override fun draw(canvas: Canvas) {
        val b = bounds
        val baseline = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, b.exactCenterX() - w / 2f, baseline, paint)
    }

    override fun getIntrinsicWidth() = kotlin.math.ceil(w).toInt()
    override fun getIntrinsicHeight() = kotlin.math.ceil(fm.descent - fm.ascent).toInt()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A focus template's progress button's ring (StatusProgressLayout's CircularProgressBar): from
 * the top round, clockwise unless [ccw], from its colour to its end colour. [progress] is read
 * at each draw, 0 to 100 - a timer's share of its total for an auto one, redrawn on each tick.
 */
internal class ProgressRingDrawable(
    private val stroke: Float,
    private val color: Int,
    private val colorEnd: Int?,
    private val ccw: Boolean,
    private val progress: () -> Float,
) : Drawable() {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        this.color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val inset = stroke / 2f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        arc.shader = colorEnd?.let { end ->
            SweepGradient(rect.centerX(), rect.centerY(), intArrayOf(color, end, color), floatArrayOf(0f, 0.999f, 1f))
                .apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, rect.centerX(), rect.centerY()) })
                }
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawOval(rect, track)
        val p = progress().coerceIn(0f, 100f)
        if (p <= 0f) return
        val sweep = 360f * p / 100f
        canvas.drawArc(rect, -90f, if (ccw) -sweep else sweep, false, arc)
    }

    override fun setAlpha(alpha: Int) {
        track.alpha = alpha / 5
        arc.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) { arc.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
