// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.View
import kotlin.math.roundToInt

/**
 * Media actions used by the expanded control center media card. Everything is best effort: a
 * player without a favourite action falls back to the rating path, and a provider that rejects a
 * call leaves the system state untouched.
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

/** Media volume; the native seekbar and the system volume panel stay in sync. */
internal object CcMediaVolume {
    fun audio(context: Context): AudioManager? = context.getSystemService(AudioManager::class.java)

    fun level(context: Context): Int =
        audio(context)?.let { runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() } ?: 0

    fun max(context: Context): Int =
        audio(context)?.let { runCatching { it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() } ?: 1

    fun set(context: Context, index: Int) {
        val audio = audio(context) ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max(context)), 0) }
    }
}

/** Output devices through AudioManager; switching is a best-effort system call. */
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
        val strategies = strategyClass.getMethod("getAudioProductStrategies").invoke(null) as? List<*> ?: return false
        val mediaStrategy = strategies.firstOrNull { strategy ->
            val value = strategy?.javaClass?.getMethod("getAudioAttributes")?.invoke(strategy)
            val usage = value?.javaClass?.getMethod("getUsage")?.invoke(value) as? Int
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

/**
 * Resources of the stock control center plugin. The expanded card reuses its layouts so the added
 * controls keep the native measurements instead of approximating them.
 */
internal object CcMediaNative {
    private const val PKG = "miui.systemui.plugin"

    fun layout(context: Context, name: String): View? = runCatching {
        val id = context.resources.getIdentifier(name, "layout", PKG)
        if (id == 0) return null
        android.view.LayoutInflater.from(context).inflate(id, null, false)
    }.getOrNull()

    fun id(context: Context, name: String): Int = runCatching {
        context.resources.getIdentifier(name, "id", PKG)
    }.getOrDefault(0)

    fun dimension(context: Context, name: String, fallback: Int): Int = runCatching {
        val id = context.resources.getIdentifier(name, "dimen", PKG)
        if (id == 0) fallback else context.resources.getDimensionPixelSize(id)
    }.getOrDefault(fallback)
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
        val size = minOf(width, height) * .46f
        val cx = width / 2f
        val cy = height / 2f
        val w = size
        val h = size
        val left = cx - w / 2f
        val top = cy - h / 2f
        path.reset()
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
