// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Media actions used by the expanded control center media card. Everything is best effort: a
 * player that does not expose a favourite action simply reports false and the control falls back
 * to the rating path, and any provider that rejects a call leaves the system state untouched.
 */
internal object CcMediaActions {
    private const val FAVORITE_HINTS = "favorite|favourite|like|collect|star|收藏|喜欢"

    fun controller(context: Context): MediaController? = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = manager.getActiveSessions(null as ComponentName?)
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }.getOrNull()

    fun favoriteAction(controller: MediaController): PlaybackState.CustomAction? =
        controller.playbackState?.customActions?.firstOrNull { action ->
            val name = action.name?.toString().orEmpty().lowercase()
            name.contains("favorite") || name.contains("favourite") || name.contains("like") ||
                name.contains("collect") || name.contains("star") ||
                Regex(FAVORITE_HINTS).containsMatchIn(name)
        }

    /** PlaybackState carries no per-action toggle state, so the heart follows the metadata rating. */
    fun isFavorite(controller: MediaController): Boolean = runCatching {
        val rating = controller.metadata?.getRating(MediaMetadata.METADATA_KEY_RATING) ?: return false
        rating.isRated && rating.hasHeart()
    }.getOrDefault(false)

    fun toggleFavorite(controller: MediaController) {
        val transport = controller.transportControls ?: return
        favoriteAction(controller)?.let { action ->
            transport.sendCustomAction(action.action, null)
            return
        }
        val rating = controller.metadata?.let { runCatching { it.getRating(MediaMetadata.METADATA_KEY_RATING) }.getOrNull() }
        if (rating != null) {
            val next = when {
                rating.ratingStyle != Rating.RATING_HEART -> Rating.newHeartRating(true)
                rating.hasHeart() -> Rating.newHeartRating(false)
                else -> Rating.newHeartRating(true)
            }
            transport.setRating(next)
        }
    }
}

/** Media volume through AudioManager; the system panel and this slider stay in sync. */
internal object CcMediaVolume {
    fun audio(context: Context): AudioManager? = context.getSystemService(AudioManager::class.java)

    fun level(context: Context): Int =
        audio(context)?.let { runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() } ?: 0

    fun max(context: Context): Int =
        audio(context)?.let { runCatching { it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() } ?: 1

    fun set(context: Context, index: Int) {
        val audio = audio(context) ?: return
        val target = index.coerceIn(0, max(context))
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }
}

/** Output routing through AudioManager; switching is a best-effort system call. */
internal object CcMediaRoute {
    private fun audio(context: Context): AudioManager? = context.getSystemService(AudioManager::class.java)

    fun outputs(context: Context): List<AudioDeviceInfo> = runCatching {
        audio(context)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { it.isSink }.orEmpty()
    }.getOrDefault(emptyList())

    fun currentName(context: Context): String? {
        val device = outputs(context).minByOrNull { priority(it.type) } ?: return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 6
        AudioDeviceInfo.TYPE_HEARING_AID -> 7
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        else -> 8
    }

    /** setPreferredDeviceForStrategy is a system API, so resolve it reflectively and fail quietly. */
    fun select(context: Context, device: AudioDeviceInfo): Boolean = runCatching {
        val manager = audio(context) ?: return false
        val attributes = Class.forName("android.media.AudioDeviceAttributes")
            .getConstructor(AudioDeviceInfo::class.java)
            .newInstance(device)
        val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val strategies = strategyClass.getMethod("getAudioProductStrategies").invoke(null) as? List<*> ?: return false false
        val mediaStrategy = strategies.firstOrNull { strategy ->
            val attributesValue = strategy?.javaClass?.getMethod("getAudioAttributes")?.invoke(strategy)
            val usage = attributesValue?.javaClass?.getMethod("getUsage")?.invoke(attributesValue) as? Int
            usage == android.media.AudioAttributes.USAGE_MEDIA
        } ?: return false
        val setter = AudioManager::class.java.getMethod(
            "setPreferredDeviceForStrategy",
            strategyClass,
            attributes.javaClass,
        )
        setter.invoke(manager, mediaStrategy, attributes) as? Boolean ?: false
    }.getOrDefault(false)
}

/** Heart toggle; polls the player so an external favourite change repaints. */
internal class CcMediaFavoriteView(
    context: Context,
    private val isFavorite: () -> Boolean,
    private val onToggle: () -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            invalidate()
            postDelayed(this, 700L)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onToggle(); invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val filled = runCatching { isFavorite() }.getOrDefault(false)
        val size = minOf(width, height) * .52f
        val cx = width / 2f
        val cy = height / 2f
        path.reset()
        val w = size
        val h = size
        val left = cx - w / 2f
        val top = cy - h / 2f
        path.moveTo(left + w / 2f, top + h * .92f)
        path.cubicTo(left - w * .12f, top + h * .58f, left + w * .04f, top + h * .06f, left + w / 2f, top + h * .30f)
        path.cubicTo(left + w * .96f, top + h * .06f, left + w * 1.12f, top + h * .58f, left + w / 2f, top + h * .92f)
        path.close()
        if (filled) {
            paint.style = Paint.Style.FILL
            paint.color = 0xffff5b7fu.toInt()
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * resources.displayMetrics.density
            paint.color = 0xe6ffffff.toInt()
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }
}

/** Media volume slider; tapping or dragging maps the x position straight onto the stream index. */
internal class CcMediaVolumeSlider(
    context: Context,
    private val level: () -> Int,
    private val max: () -> Int,
    private val onSet: (Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            invalidate()
            postDelayed(this, 700L)
        }
    }

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val usable = (width - 2 * density * 8).coerceAtLeast(1f)
                val ratio = ((event.x - density * 8) / usable).coerceIn(0f, 1f)
                val maximum = max().coerceAtLeast(1)
                onSet((ratio * maximum).roundToInt().coerceIn(0, maximum))
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val maximum = max().coerceAtLeast(1)
        val ratio = (level().toFloat() / maximum).coerceIn(0f, 1f)
        val inset = density * 8
        val cy = height / 2f
        val trackH = 5f * density
        val right = width - inset
        val left = inset
        paint.color = 0x33ffffff
        canvas.drawRoundRect(RectF(left, cy - trackH / 2, right, cy + trackH / 2), trackH, trackH, paint)
        paint.color = 0xe6ffffff.toInt()
        canvas.drawRoundRect(RectF(left, cy - trackH / 2, left + (right - left) * ratio, cy + trackH / 2), trackH, trackH, paint)
        val knob = 7f * density
        canvas.drawCircle(left + (right - left) * ratio, cy, knob, paint)
    }
}

/** One row that names the current output route and opens the picker. */
internal class CcMediaDeviceRow(
    context: Context,
    private val label: () -> String,
    private val onClick: (View) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            invalidate()
            postDelayed(this, 1500L)
        }
    }

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onClick(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = 12f * density
        paint.color = 0xe6ffffff.toInt()
        val text = runCatching { label() }.getOrDefault("")
        val textY = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(text, 2f * density, textY, paint)
        // Chevron on the trailing edge, matching HyperOS disclosure affordances.
        val cx = width - 8f * density
        val cy = height / 2f
        val size = 4f * density
        val stroke = 1.6f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = 0x99ffffff.toInt()
        canvas.drawLine(cx - size, cy - size, cx, cy, paint)
        canvas.drawLine(cx, cy, cx - size, cy + size, paint)
        paint.style = Paint.Style.FILL
    }
}
