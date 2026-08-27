package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.AudioManager
import android.view.KeyEvent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

internal const val MINI_PLAYER_BACKGROUND_DEFAULT = 0
internal const val MINI_PLAYER_BACKGROUND_PURE = 1
internal const val MINI_PLAYER_BACKGROUND_ADVANCED = 2
internal const val MINI_PLAYER_BACKGROUND_SOFT_GLASS = 3

internal data class MiniPlayerAppearance(
    val backgroundMode: Int,
    val widthDp: Float,
    val heightDp: Float,
    val pureColor: Int = 0x73000000,
    val advancedColor: Int = 0xFFFFFFFF.toInt(),
    val advancedOpacity: Int = 14,
    val advancedBlurRadius: Int = 80,
    val advancedHighlight: Boolean = false,
    val softGlassColor: Int = 0xFFFFFFFF.toInt(),
    val softGlassOpacity: Int = 10,
    val softGlassBackdropBlurRadius: Int = 80,
    val softGlassBlurRadius: Int = 36,
    val softGlassLuminance: Float = 0.14f,
)

/** Values sourced from SystemUI's NotificationMediaManager rather than an inferred session list. */
internal object LockscreenMediaBridge {
    @Volatile var controller: MediaController? = null
        private set
    @Volatile var notificationKey: String? = null
        private set

    fun update(controller: MediaController?, notificationKey: String?) {
        this.controller = controller
        this.notificationKey = notificationKey
    }
}

internal enum class LockscreenMediaPresentation {
    MINI_PLAYER,
    MUSIC_LOCKSCREEN,
    SYSTEM_MEDIA,
}

internal interface LockscreenMediaPresentationListener {
    fun onMediaPresentationChanged(presentation: LockscreenMediaPresentation)
}

/** Coordinates the custom lockscreen card, music lockscreen, and SystemUI media notification. */
internal object LockscreenMediaPresentationBridge {
    private val listeners = Collections.newSetFromMap(
        WeakHashMap<LockscreenMediaPresentationListener, Boolean>(),
    )

    @Volatile var presentation: LockscreenMediaPresentation = LockscreenMediaPresentation.MINI_PLAYER
        private set
    val showMiniPlayer: Boolean get() = presentation == LockscreenMediaPresentation.MINI_PLAYER
    @Volatile var onPresentationChanged: ((LockscreenMediaPresentation) -> Unit)? = null

    fun register(listener: LockscreenMediaPresentationListener) {
        synchronized(listeners) { listeners += listener }
        listener.onMediaPresentationChanged(presentation)
    }

    fun unregister(listener: LockscreenMediaPresentationListener) {
        synchronized(listeners) { listeners -= listener }
    }

    fun setPresentation(value: LockscreenMediaPresentation) {
        if (presentation == value) return
        presentation = value
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onMediaPresentationChanged(value) }
        onPresentationChanged?.invoke(value)
    }

    fun setShowMiniPlayer(value: Boolean) {
        setPresentation(
            if (value) LockscreenMediaPresentation.MINI_PLAYER
            else LockscreenMediaPresentation.SYSTEM_MEDIA,
        )
    }
}

/** The SystemUI long-press menu is not part of the shortcut view hierarchy. */
internal object LockscreenCustomizationMenuBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenMiniPlayerController, Boolean>())
    @Volatile private var visible = false

    fun register(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners += controller }
        controller.onCustomizationMenuVisibilityChanged(visible)
    }

    fun unregister(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners -= controller }
    }

    fun setVisible(value: Boolean) {
        visible = value
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onCustomizationMenuVisibilityChanged(value) }
    }
}

/** Native lockscreen card; SystemUI owns media sessions and the final view hierarchy. */
internal class LockscreenMiniPlayerController(
    private val host: ViewGroup,
    private val leftShortcut: View,
    private val rightShortcut: View,
    private val enabled: () -> Boolean,
    private val musicLockscreenEnabled: () -> Boolean,
    private val lyricsEnabled: () -> Boolean,
    private val mediaNotificationMode: () -> Int,
    private val appearance: () -> MiniPlayerAppearance,
    private val applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
) : LockscreenMediaPresentationListener {
    private val context: Context = host.context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeController: MediaController? = null
    private var player: LockscreenMiniPlayerView? = null
    private var lyricsCard: LockscreenLyricsView? = null
    private var lyricSubscriber: LyriconLockscreenSubscriber? = null
    private var refreshPosted = false
    private var positionPosted = false
    private var baseTranslationX = 0f
    private var baseTranslationY = 0f
    private var lyricsBaseTranslationX = 0f
    private var lyricsBaseTranslationY = 0f
    private var customizationLift = 0f
    private var customizationVisible = false
    private var customizationMenuVisible = false
    private var mediaPresentationExitAnimating = false
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateCustomizationLift()
    }

    private val sessionCallback = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            selectController(controllers.orEmpty())
            refresh()
        }
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { scheduleRefresh() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { scheduleRefresh() }
    }

    init {
        // The card is centered between the shortcuts and may be taller than the shortcut
        // container's measured bounds. Keep the configured dp height from being clipped.
        host.clipChildren = false
        host.clipToPadding = false
        host.rootView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        LockscreenCustomizationMenuBridge.register(this)
        LockscreenMediaPresentationBridge.register(this)
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        leftShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        rightShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(sessionCallback, null, mainHandler)
            selectController(sessions?.getActiveSessions(null).orEmpty())
            refresh()
        }.onFailure { scheduleRefresh() }
    }

    fun destroy() {
        LockscreenCustomizationMenuBridge.unregister(this)
        LockscreenMediaPresentationBridge.unregister(this)
        runCatching { host.rootView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionCallback) }
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        runCatching { player?.let(host::removeView) }
        runCatching { lyricsCard?.let(host::removeView) }
        lyricSubscriber?.destroy()
        activeController = null
        player = null
        lyricsCard = null
        lyricSubscriber = null
        mainHandler.removeCallbacksAndMessages(null)
        positionPosted = false
    }

    private fun updateCustomizationLift() {
        val view = player ?: return
        val root = host.rootView as? ViewGroup ?: return
        if (!customizationMenuVisible) {
            animateCustomizationLift(false, 0f)
            return
        }
        val customButton = findCustomizationButton(root)
        if (customButton == null) {
            // onLongPress fires before the menu view has completed its first layout.
            animateCustomizationLift(true, -dp(64f).toFloat())
            return
        }
        val playerLocation = IntArray(2).also(view::getLocationOnScreen)
        val buttonLocation = IntArray(2).also(customButton::getLocationOnScreen)
        val overlap = playerLocation[1] + view.height - buttonLocation[1]
        val lift = if (overlap > 0) -(overlap + dp(12f)).toFloat() else 0f
        animateCustomizationLift(true, lift)
    }

    private fun animateCustomizationLift(visible: Boolean, lift: Float) {
        if (customizationVisible == visible && kotlin.math.abs(customizationLift - lift) < 1f) return
        customizationVisible = visible
        customizationLift = lift
        fun animate(view: View, baseY: Float) {
            view.animate()
                .translationY(baseY + lift)
                .setDuration(280L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        player?.let { animate(it, baseTranslationY) }
        lyricsCard?.let { animate(it, lyricsBaseTranslationY) }
    }

    internal fun onCustomizationMenuVisibilityChanged(visible: Boolean) {
        customizationMenuVisible = visible
        mainHandler.post { updateCustomizationLift() }
    }

    override fun onMediaPresentationChanged(presentation: LockscreenMediaPresentation) {
        mainHandler.post {
            if (presentation == LockscreenMediaPresentation.MINI_PLAYER) {
                mediaPresentationExitAnimating = false
                refresh()
                player?.let(::animateMiniPlayerIn)
                lyricsCard?.takeIf { it.visibility == View.VISIBLE }?.let(::animateMiniPlayerIn)
            } else {
                animateMiniPlayerOut()
            }
        }
    }

    private fun animateMiniPlayerIn(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = .68f
        view.scaleX = .78f
        view.scaleY = .78f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MEDIA_PRESENTATION_ENTER_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun animateMiniPlayerOut() {
        val views = listOfNotNull(player, lyricsCard).filter { it.visibility == View.VISIBLE }
        if (views.isEmpty()) {
            mediaPresentationExitAnimating = false
            refresh()
            return
        }
        mediaPresentationExitAnimating = true
        var pending = views.size
        views.forEach { view ->
            view.animate().cancel()
            view.animate()
                .alpha(.68f)
                .scaleX(.78f)
                .scaleY(.78f)
                .setDuration(MEDIA_PRESENTATION_EXIT_DURATION_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    pending -= 1
                    if (pending == 0 && !LockscreenMediaPresentationBridge.showMiniPlayer) {
                        mediaPresentationExitAnimating = false
                        listOfNotNull(player, lyricsCard).forEach { card ->
                            card.visibility = View.GONE
                            card.alpha = 1f
                            card.scaleX = 1f
                            card.scaleY = 1f
                        }
                        refresh()
                    }
                }
                .start()
        }
    }

    private fun findCustomizationButton(root: ViewGroup): View? {
        fun matches(view: View): Boolean {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return false
            val text = (view as? TextView)?.text?.toString().orEmpty()
            val description = view.contentDescription?.toString().orEmpty()
            val idName = runCatching { context.resources.getResourceEntryName(view.id) }.getOrDefault("")
            val haystack = "$text $description $idName".lowercase(java.util.Locale.ROOT)
            return haystack.contains("自定义锁屏") ||
                haystack.contains("custom lock") ||
                (haystack.contains("custom") && haystack.contains("lock"))
        }
        fun search(view: View): View? {
            if (view !== this@LockscreenMiniPlayerController.player && matches(view)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) search(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return search(root)
    }

    private fun selectController(controllers: List<MediaController>) {
        val platformController = LockscreenMediaBridge.controller
        val selected = platformController?.takeIf(::isUsable)
            ?: controllers.firstOrNull(::isUsable)
            ?: controllers.firstOrNull { it.metadata != null }
        setActiveController(selected)
    }

    private fun setActiveController(selected: MediaController?) {
        if (selected?.sessionToken != activeController?.sessionToken) {
            runCatching { activeController?.unregisterCallback(controllerCallback) }
            activeController = selected
            runCatching { selected?.registerCallback(controllerCallback, mainHandler) }
        }
    }

    private fun isUsable(controller: MediaController): Boolean = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun refresh() {
        runCatching { refreshUnsafe() }
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        mainHandler.post {
            refreshPosted = false
            refresh()
        }
    }

    private fun refreshUnsafe() {
        // NotificationMediaManager can publish a controller whose playback state is already
        // destroyed. Do not call selectController() here: that method refreshes synchronously,
        // and selecting an unusable bridge controller used to recurse until SystemUI crashed.
        LockscreenMediaBridge.controller?.let { platformController ->
            if (platformController.sessionToken != activeController?.sessionToken) {
                setActiveController(platformController.takeIf(::isUsable))
            }
        }
        val controller = activeController
        val state = controller?.playbackState
        if (!enabled() || controller == null || !isUsable(controller)) {
            player?.visibility = View.GONE
            lyricsCard?.visibility = View.GONE
            return
        }
        val mode = mediaNotificationMode()
        if (mode != LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC &&
            LockscreenMediaPresentationBridge.presentation == LockscreenMediaPresentation.SYSTEM_MEDIA
        ) {
            LockscreenMediaPresentationBridge.setPresentation(LockscreenMediaPresentation.MINI_PLAYER)
        }
        val currentAppearance = appearance()
        val view = player ?: LockscreenMiniPlayerView(context).also {
            player = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
            // The first refresh can happen before the shortcut row receives its final layout.
            // Start with the configured dimensions instead of a transient default size.
            host.addView(
                it,
                ViewGroup.LayoutParams(
                    dp(currentAppearance.widthDp),
                    dp(currentAppearance.heightDp * 2f).coerceAtLeast(dp(48f)),
                ),
            )
        }
        val showMiniPlayer = LockscreenMediaPresentationBridge.presentation ==
            LockscreenMediaPresentation.MINI_PLAYER
        if (showMiniPlayer) {
            view.visibility = View.VISIBLE
        } else if (!mediaPresentationExitAnimating) {
            view.visibility = View.GONE
        }
        view.bind(
            title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                .ifBlank { "正在播放" },
            artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            artwork = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            appearance = currentAppearance,
            applyPlatformMaterial = applyPlatformMaterial,
            onToggle = ::togglePlaybackSafely,
            onSkipToPrevious = { skipTrackSafely(next = false) },
            onSkipToNext = { skipTrackSafely(next = true) },
            onShowSystemMediaNotification = {
                if (mediaNotificationMode() == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC) {
                    LockscreenMediaPresentationBridge.setShowMiniPlayer(false)
                }
            },
            onShowMusicLockscreen = {
                if (musicLockscreenEnabled()) {
                    LockscreenMediaPresentationBridge.setPresentation(
                        LockscreenMediaPresentation.MUSIC_LOCKSCREEN,
                    )
                }
            },
        )
        bindLyrics(currentAppearance, showMiniPlayer)
        position()
    }

    private fun bindLyrics(appearance: MiniPlayerAppearance, showMiniPlayer: Boolean) {
        if (!lyricsEnabled()) {
            lyricsCard?.visibility = View.GONE
            lyricSubscriber?.destroy()
            lyricSubscriber = null
            return
        }
        val subscriber = lyricSubscriber ?: LyriconLockscreenSubscriber(context) { scheduleRefresh() }.also {
            lyricSubscriber = it
        }
        val lyric = subscriber.currentLyric() ?: run {
            lyricsCard?.visibility = View.GONE
            return
        }
        val view = lyricsCard ?: LockscreenLyricsView(context).also {
            lyricsCard = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
            host.addView(it, ViewGroup.LayoutParams(dp(240f), dp(52f)))
        }
        view.bind(lyric, appearance, applyPlatformMaterial)
        view.visibility = if (showMiniPlayer) View.VISIBLE else View.GONE
    }

    private fun togglePlaybackSafely() {
        // TransportControls is a Binder proxy. A player can disappear between the click and
        // the command, so dispatch on the SystemUI looper and contain every Binder failure.
        runCatching {
            mainHandler.post {
                runCatching {
                    // Refresh the session list at click time. Media apps can replace their
                    // session without emitting an active-session callback to SystemUI.
                    val current = sessions?.getActiveSessions(null).orEmpty()
                    if (current.isNotEmpty()) selectController(current)
                    val controller = LockscreenMediaBridge.controller ?: activeController
                    val state = controller?.playbackState
                    val playing = state?.state == PlaybackState.STATE_PLAYING
                    val action = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                    val supportsAction = state != null && state.actions and action != 0L
                    if (controller != null && supportsAction) {
                        runCatching {
                            if (playing) controller.transportControls.pause() else controller.transportControls.play()
                        }.onFailure { dispatchMediaKeyFallback(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                    } else {
                        dispatchMediaKeyFallback(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                }
            }
        }
    }

    private fun skipTrackSafely(next: Boolean) {
        // Use the active session when possible so the command follows the media app selected by
        // SystemUI, then fall back to the standard media key for sessions without transport APIs.
        runCatching {
            mainHandler.post {
                runCatching {
                    val current = sessions?.getActiveSessions(null).orEmpty()
                    if (current.isNotEmpty()) selectController(current)
                    val controller = LockscreenMediaBridge.controller ?: activeController
                    val state = controller?.playbackState
                    val action = if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    val keyCode = if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    val supportsAction = state != null && state.actions and action != 0L
                    if (controller != null && supportsAction) {
                        runCatching {
                            if (next) controller.transportControls.skipToNext()
                            else controller.transportControls.skipToPrevious()
                        }.onFailure { dispatchMediaKeyFallback(keyCode) }
                    } else {
                        dispatchMediaKeyFallback(keyCode)
                    }
                }
            }
        }
    }

    private fun dispatchMediaKeyFallback(keyCode: Int) {
        val manager = audioManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation {
            positionPosted = false
            position()
        }
    }

    private fun position() {
        val view = player ?: return
        if (host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        val shortcutsLaidOut = leftShortcut.width > 0 && leftShortcut.height > 0 &&
            rightShortcut.width > 0 && rightShortcut.height > 0
        val leftLocation = if (shortcutsLaidOut) IntArray(2).also(leftShortcut::getLocationOnScreen) else null
        val rightLocation = if (shortcutsLaidOut) IntArray(2).also(rightShortcut::getLocationOnScreen) else null
        // When lockscreen shortcuts are disabled, their containers remain in the hierarchy but
        // have no measured bounds. Center against the host instead of leaving the card at (0, 0).
        val centerX = if (leftLocation != null && rightLocation != null) {
            ((leftLocation[0] + leftShortcut.width / 2f) +
                (rightLocation[0] + rightShortcut.width / 2f)) / 2f - hostLocation[0]
        } else {
            host.width / 2f
        }
        val centerY = if (leftLocation != null && rightLocation != null) {
            ((leftLocation[1] + leftShortcut.height / 2f) +
                (rightLocation[1] + rightShortcut.height / 2f)) / 2f - hostLocation[1]
        } else {
            host.height / 2f
        }
        val requested = appearance()
        val horizontalRoom = min(centerX, host.width - centerX) * 2f - dp(12f)
        val width = min(dp(requested.widthDp).toFloat(), min(host.width * .64f, horizontalRoom))
            .toInt().coerceAtLeast(min(dp(160f), host.width))
        // The setting shares the shortcut circle-radius unit. Render the corresponding diameter
        // while keeping the platform's minimum card height of 48dp.
        val height = dp(requested.heightDp * 2f).coerceAtLeast(dp(48f))
        val params = view.layoutParams
        if (params == null || params.width != width || params.height != height) {
            val updated = params ?: ViewGroup.LayoutParams(width, height)
            updated.width = width
            updated.height = height
            view.layoutParams = updated
            // Do not calculate against the old child bounds. The parent will assign new
            // left/top/width/height during the next layout pass, then the layout listener above
            // will apply the final translation.
            schedulePosition()
            return
        }
        val actualWidth = view.width
        val actualHeight = view.height
        if (actualWidth <= 0 || actualHeight <= 0) {
            schedulePosition()
            return
        }
        // Preserve the requested center across parent layout passes.
        baseTranslationX = centerX - actualWidth / 2f - view.left
        val safeBottom = bottomSafeDistance()
        val maxCenterY = host.height - safeBottom - actualHeight / 2f
        // The shortcut host is only the bottom row (often shorter than the card itself). Allow a
        // negative local center/translation so the card can rise above that host and clear the
        // navigation gesture area instead of being clamped flush to the physical display edge.
        val adjustedCenterY = min(centerY, maxCenterY)
        baseTranslationY = adjustedCenterY - actualHeight / 2f - view.top
        view.translationX = baseTranslationX
        view.translationY = baseTranslationY + customizationLift
        positionLyrics(view, leftLocation, rightLocation, hostLocation)
    }

    private fun positionLyrics(
        playerView: View,
        leftLocation: IntArray?,
        rightLocation: IntArray?,
        hostLocation: IntArray,
    ) {
        val view = lyricsCard ?: return
        if (view.visibility != View.VISIBLE) return
        val playerLeft = playerView.left + playerView.translationX
        val playerRight = playerLeft + playerView.width
        val left = if (leftLocation != null && rightLocation != null) {
            min((leftLocation[0] - hostLocation[0]).toFloat(), playerLeft)
        } else {
            playerLeft
        }
        val right = if (leftLocation != null && rightLocation != null) {
            max((rightLocation[0] - hostLocation[0] + rightShortcut.width).toFloat(), playerRight)
        } else {
            playerRight
        }
        val width = (right - left).toInt().coerceIn(dp(160f), host.width)
        val height = max(dp(52f), (playerView.height * .86f).toInt())
        val params = view.layoutParams
        if (params == null || params.width != width || params.height != height) {
            val updated = params ?: ViewGroup.LayoutParams(width, height)
            updated.width = width
            updated.height = height
            view.layoutParams = updated
            schedulePosition()
            return
        }
        if (view.width <= 0 || view.height <= 0) {
            schedulePosition()
            return
        }
        val playerTop = playerView.top + baseTranslationY
        val lyricTop = playerTop - dp(LYRICS_PLAYER_GAP_DP) - view.height
        lyricsBaseTranslationX = left - view.left
        lyricsBaseTranslationY = lyricTop - view.top
        view.translationX = lyricsBaseTranslationX
        view.translationY = lyricsBaseTranslationY + customizationLift
    }

    private fun bottomSafeDistance(): Float {
        val systemBottomInset = runCatching {
            host.rootWindowInsets?.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.mandatorySystemGestures(),
            )?.bottom ?: 0
        }.getOrDefault(0)
        return max(dp(MINI_PLAYER_MIN_BOTTOM_MARGIN_DP).toFloat(),
            systemBottomInset + dp(MINI_PLAYER_INSET_EXTRA_DP).toFloat())
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val MEDIA_PRESENTATION_ENTER_DURATION_MS = 260L
        const val MEDIA_PRESENTATION_EXIT_DURATION_MS = 220L
        const val LYRICS_PLAYER_GAP_DP = 12f
        const val MINI_PLAYER_MIN_BOTTOM_MARGIN_DP = 24f
        const val MINI_PLAYER_INSET_EXTRA_DP = 8f
    }
}

private data class LockscreenLyricLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val translation: String,
    val roma: String,
)

private data class LockscreenLyric(
    val plainText: String = "",
    val plainDetail: String = "",
    val lines: List<LockscreenLyricLine> = emptyList(),
    val position: Long = 0L,
    val displayTranslation: Boolean = false,
    val displayRoma: Boolean = false,
) {
    fun displayText(): Pair<String, String>? {
        plainText.takeIf { it.isNotBlank() }?.let { return it to plainDetail }
        val line = lines.firstOrNull { position in it.begin until it.end }
            ?: lines.lastOrNull { it.begin <= position }
            ?: lines.firstOrNull()
            ?: return null
        if (line.text.isBlank()) return null
        val detail = when {
            displayTranslation && line.translation.isNotBlank() -> line.translation
            displayRoma && line.roma.isNotBlank() -> line.roma
            else -> ""
        }
        return line.text to detail
    }
}

/** Receives the active Lyricon Provider in the SystemUI process. */
private class LyriconLockscreenSubscriber(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val subscriber: LyriconSubscriber = LyriconFactory.createSubscriber(context.applicationContext)
    private var lyric = LockscreenLyric()
    private val listener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            // A Provider can change before its song callback arrives. Clear the old lyric now,
            // but keep display flags because Lyricon does not guarantee callback ordering.
            update {
                LockscreenLyric(
                    displayTranslation = lyric.displayTranslation,
                    displayRoma = lyric.displayRoma,
                )
            }
        }

        override fun onSongChanged(song: Song?) {
            update {
                LockscreenLyric(
                    plainText = "",
                    plainDetail = "",
                    lines = song?.lyrics.orEmpty().map { line ->
                        LockscreenLyricLine(
                            begin = line.begin,
                            end = line.end,
                            text = line.text.orEmpty(),
                            translation = line.translation.orEmpty(),
                            roma = line.roma.orEmpty(),
                        )
                    },
                    position = lyric.position,
                    displayTranslation = lyric.displayTranslation,
                    displayRoma = lyric.displayRoma,
                )
            }
        }

        override fun onReceiveText(text: String?) {
            update {
                val textLines = text.orEmpty()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toList()
                lyric.copy(
                    plainText = textLines.firstOrNull().orEmpty(),
                    plainDetail = textLines.drop(1).joinToString(" / "),
                    lines = emptyList(),
                )
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit

        override fun onPositionChanged(position: Long) {
            update { lyric.copy(position = position) }
        }

        override fun onSeekTo(position: Long) = onPositionChanged(position)

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            update { lyric.copy(displayTranslation = isDisplayTranslation) }
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            update { lyric.copy(displayRoma = isDisplayRoma) }
        }
    }

    init {
        runCatching {
            subscriber.subscribeActivePlayer(listener)
            subscriber.register()
        }
    }

    fun currentLyric(): LockscreenLyric? = lyric.takeIf { it.displayText() != null }

    fun destroy() {
        runCatching { subscriber.unsubscribeActivePlayer(listener) }
        runCatching { subscriber.unregister() }
        runCatching { subscriber.destroy() }
        handler.removeCallbacksAndMessages(null)
    }

    private fun update(transform: () -> LockscreenLyric) {
        handler.post {
            lyric = transform()
            onChanged()
        }
    }
}

private class LockscreenLyricsView(context: Context) : FrameLayout(context) {
    private val materialLayer = ImageView(context)
    private val mainLine = TextView(context)
    private val detailLine = TextView(context)
    private var lastAppearance: MiniPlayerAppearance? = null
    private var hasDetailLine = false
    private val density = resources.displayMetrics.density

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        materialLayer.clipToOutline = true
        materialLayer.outlineProvider = outlineProvider
        addView(materialLayer, LayoutParams(-1, -1))
        mainLine.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        detailLine.apply {
            setTextColor(Color.argb(224, 255, 255, 255))
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        addView(mainLine)
        addView(detailLine)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTextGeometry()
    }

    fun bind(
        lyric: LockscreenLyric,
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
    ) {
        val (main, detail) = lyric.displayText() ?: return
        mainLine.text = main
        detailLine.text = detail
        hasDetailLine = detail.isNotBlank()
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            materialLayer.setImageDrawable(
                when (appearance.backgroundMode) {
                    MINI_PLAYER_BACKGROUND_PURE -> rounded(appearance.pureColor, dp(40).toFloat())
                    MINI_PLAYER_BACKGROUND_ADVANCED,
                    MINI_PLAYER_BACKGROUND_SOFT_GLASS -> rounded(Color.argb(1, 255, 255, 255), dp(40).toFloat())
                    else -> rounded(Color.argb(158, 31, 35, 36), dp(40).toFloat()).apply {
                        setStroke(dp(1), Color.argb(78, 255, 255, 255))
                    }
                },
            )
            if (appearance.backgroundMode == MINI_PLAYER_BACKGROUND_ADVANCED ||
                appearance.backgroundMode == MINI_PLAYER_BACKGROUND_SOFT_GLASS
            ) {
                applyPlatformMaterial(materialLayer, appearance)
            }
        }
        updateTextGeometry()
    }

    private fun updateTextGeometry() {
        val inset = dp(18)
        if (!hasDetailLine) {
            mainLine.layoutParams = LayoutParams(-1, -1, Gravity.CENTER).apply {
                leftMargin = inset
                rightMargin = inset
            }
            detailLine.visibility = View.GONE
        } else {
            mainLine.layoutParams = LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                leftMargin = inset
                rightMargin = inset
                bottomMargin = height / 2
            }
            detailLine.layoutParams = LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                leftMargin = inset
                rightMargin = inset
                topMargin = height / 2
            }
            detailLine.visibility = View.VISIBLE
        }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()
}

private class LockscreenMiniPlayerView(context: Context) : FrameLayout(context) {
    private val materialLayer = ImageView(context)
    private val artwork = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val text = LinearLayout(context)
    private val toggle = ImageButton(context)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastAppearance: MiniPlayerAppearance? = null
    private var onSkipToPrevious: (() -> Unit)? = null
    private var onSkipToNext: (() -> Unit)? = null
    private var onShowSystemMediaNotification: (() -> Unit)? = null
    private var onShowMusicLockscreen: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var trackingSwipe = false
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (trackingSwipe) {
            longPressTriggered = true
            onShowMusicLockscreen?.invoke()
        }
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        materialLayer.clipToOutline = true
        materialLayer.outlineProvider = outlineProvider
        addView(materialLayer, LayoutParams(-1, -1))

        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.clipToOutline = true
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
        }
        artwork.background = rounded(Color.rgb(55, 55, 55), dp(12).toFloat())
        addView(artwork)

        title.setTextColor(Color.WHITE)
        title.textSize = 12.8f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.MARQUEE
        title.isSelected = true
        title.isSingleLine = true
        title.setHorizontallyScrolling(true)
        title.marqueeRepeatLimit = -1
        title.includeFontPadding = false
        title.setLineSpacing(0f, .5f)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        artist.setTextColor(Color.argb(232, 255, 255, 255))
        artist.textSize = 12f
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.MARQUEE
        artist.isSelected = true
        artist.isSingleLine = true
        artist.setHorizontallyScrolling(true)
        artist.marqueeRepeatLimit = -1
        artist.includeFontPadding = false
        artist.setLineSpacing(0f, .5f)
        artist.setTypeface(artist.typeface, android.graphics.Typeface.BOLD)
        text.orientation = LinearLayout.VERTICAL
        text.gravity = Gravity.CENTER_VERTICAL
        text.addView(title, LinearLayout.LayoutParams(-1, -2))
        text.addView(artist, LinearLayout.LayoutParams(-1, -2))
        artist.translationY = -dp(2).toFloat()
        addView(text)

        toggle.scaleType = ImageView.ScaleType.CENTER
        toggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        // Keep a transparent touch target, but remove the decorative circular backing.
        toggle.background = null
        toggle.contentDescription = "播放或暂停"
        addView(toggle)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingSwipe = true
                longPressTriggered = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                // NotificationPanelView recognizes horizontal lockscreen gestures before its
                // children. Keep this pointer stream with the player once it starts inside the
                // card, then release the parent chain at the end of the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (trackingSwipe) {
                val horizontalDistance = abs(event.x - downX)
                val verticalDistance = abs(event.y - downY)
                if (horizontalDistance > touchSlop || verticalDistance > touchSlop) {
                    removeCallbacks(longPressRunnable)
                }
                if (horizontalDistance > touchSlop && horizontalDistance > verticalDistance) {
                    // Intercept only after a clearly horizontal movement so the toggle remains
                    // clickable and vertical lockscreen gestures keep their normal behavior.
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                trackingSwipe = false
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingSwipe = true
                longPressTriggered = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val horizontalDistance = event.x - downX
                val verticalDistance = event.y - downY
                val minimumSwipeDistance = max(dp(48).toFloat(), width * .15f)
                if (trackingSwipe &&
                    abs(horizontalDistance) >= minimumSwipeDistance &&
                    abs(horizontalDistance) > abs(verticalDistance)
                ) {
                    if (horizontalDistance < 0f) onSkipToNext?.invoke() else onSkipToPrevious?.invoke()
                } else if (!longPressTriggered && trackingSwipe &&
                    abs(horizontalDistance) <= touchSlop && abs(verticalDistance) <= touchSlop
                ) {
                    onShowSystemMediaNotification?.invoke()
                }
                trackingSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingSwipe = false
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun bind(
        title: String,
        artist: String,
        artwork: Bitmap?,
        playing: Boolean,
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
        onToggle: () -> Unit,
        onSkipToPrevious: () -> Unit,
        onSkipToNext: () -> Unit,
        onShowSystemMediaNotification: () -> Unit,
        onShowMusicLockscreen: () -> Unit,
    ) {
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            applyAppearance(appearance, applyPlatformMaterial)
            updateGeometry(appearance.heightDp)
        }
        this.title.text = title
        this.artist.text = artist
        this.artwork.setImageBitmap(artwork)
        toggle.setImageDrawable(MaterialRoundPathDrawable(
            if (playing) MATERIAL_ICON_PAUSE else MATERIAL_ICON_PLAY,
            Color.WHITE,
        ))
        toggle.setOnClickListener { onToggle() }
        this.onSkipToPrevious = onSkipToPrevious
        this.onSkipToNext = onSkipToNext
        this.onShowSystemMediaNotification = onShowSystemMediaNotification
        this.onShowMusicLockscreen = onShowMusicLockscreen
    }

    private fun applyAppearance(
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
    ) {
        materialLayer.setImageDrawable(
            when (appearance.backgroundMode) {
                MINI_PLAYER_BACKGROUND_PURE -> rounded(appearance.pureColor, dp(40).toFloat())
                MINI_PLAYER_BACKGROUND_ADVANCED,
                MINI_PLAYER_BACKGROUND_SOFT_GLASS -> rounded(Color.argb(1, 255, 255, 255), dp(40).toFloat())
                else -> rounded(Color.argb(158, 31, 35, 36), dp(40).toFloat()).apply {
                    setStroke(dp(1), Color.argb(78, 255, 255, 255))
                }
            },
        )
        if (appearance.backgroundMode == MINI_PLAYER_BACKGROUND_ADVANCED ||
            appearance.backgroundMode == MINI_PLAYER_BACKGROUND_SOFT_GLASS
        ) {
            applyPlatformMaterial(materialLayer, appearance)
        }
    }

    private fun updateGeometry(heightDp: Float) {
        val height = dp(heightDp * 2f).coerceAtLeast(dp(48))
        val verticalPadding = max(dp(7), height / 9)
        val artworkSize = ((height - verticalPadding * 2) * .75f).toInt().coerceAtLeast(dp(24))
        val horizontalPadding = max(dp(10), height / 7)
        artwork.layoutParams = LayoutParams(artworkSize, artworkSize, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding
        }
        val toggleSize = dp(40).coerceAtMost((height - verticalPadding * 2).coerceAtLeast(dp(34)))
        toggle.layoutParams = LayoutParams(toggleSize, toggleSize, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = max(dp(8), verticalPadding)
        }
        text.layoutParams = LayoutParams(-1, -1, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding + artworkSize + max(dp(10), height / 8)
            rightMargin = toggleSize + max(dp(10), verticalPadding)
        }
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, artworkSize * .2f)
            }
        }
        invalidateOutline()
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()
    private fun dp(value: Float): Int = (value * density + .5f).toInt()
}

/** Full lockscreen music surface hosted above the clock and depth foreground by SystemUI. */
internal class LockscreenMusicLockscreenController(
    private val host: ViewGroup,
    private val enabled: () -> Boolean,
    private val isLockscreenShowing: () -> Boolean,
    private val appearance: () -> MiniPlayerAppearance,
    private val applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
) : LockscreenMediaPresentationListener {
    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeController: MediaController? = null
    private var view: LockscreenMusicLockscreenView? = null
    private var refreshPosted = false
    private var tickPosted = false
    private val sessionCallback = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            selectController(controllers.orEmpty())
            refresh()
        }
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { scheduleRefresh() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { scheduleRefresh() }
    }
    private val tick = object : Runnable {
        override fun run() {
            tickPosted = false
            refresh()
            if (isVisibleAndPlaying()) {
                tickPosted = true
                handler.postDelayed(this, 500L)
            }
        }
    }

    init {
        host.clipChildren = false
        host.clipToPadding = false
        LockscreenMediaPresentationBridge.register(this)
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(sessionCallback, null, handler)
            selectController(sessions?.getActiveSessions(null).orEmpty())
            refresh()
        }.onFailure { scheduleRefresh() }
    }

    fun destroy() {
        LockscreenMediaPresentationBridge.unregister(this)
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionCallback) }
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        view?.let { host.removeView(it) }
        handler.removeCallbacksAndMessages(null)
        view = null
        activeController = null
    }

    override fun onMediaPresentationChanged(presentation: LockscreenMediaPresentation) {
        handler.post {
            if (presentation == LockscreenMediaPresentation.MUSIC_LOCKSCREEN &&
                enabled() && isLockscreenShowing()
            ) {
                refresh()
                view?.let(::animateIn)
            } else {
                view?.let(::animateOut)
            }
        }
    }

    fun onKeyguardVisibilityChanged() {
        handler.post {
            if (!isLockscreenShowing()) {
                view?.let(::animateOut)
            } else if (LockscreenMediaPresentationBridge.presentation ==
                LockscreenMediaPresentation.MUSIC_LOCKSCREEN
            ) {
                refresh()
            }
        }
    }

    private fun selectController(controllers: List<MediaController>) {
        val bridge = LockscreenMediaBridge.controller
        val selected = bridge?.takeIf(::isUsable)
            ?: controllers.firstOrNull(::isUsable)
            ?: controllers.firstOrNull { it.metadata != null }
        if (selected?.sessionToken != activeController?.sessionToken) {
            runCatching { activeController?.unregisterCallback(controllerCallback) }
            activeController = selected
            runCatching { selected?.registerCallback(controllerCallback, handler) }
        }
    }

    private fun isUsable(controller: MediaController): Boolean = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun refresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post {
            refreshPosted = false
            runCatching { refreshUnsafe() }
        }
    }

    private fun scheduleRefresh() = refresh()

    private fun refreshUnsafe() {
        LockscreenMediaBridge.controller?.let { bridge ->
            if (bridge.sessionToken != activeController?.sessionToken) {
                selectController(listOf(bridge))
            }
        }
        val controller = activeController
        val state = controller?.playbackState
        if (!enabled() || !isLockscreenShowing() || controller == null || !isUsable(controller)) {
            view?.visibility = View.GONE
            return
        }
        val musicView = view ?: LockscreenMusicLockscreenView(context).also {
            view = it
            host.addView(it, ViewGroup.LayoutParams(dp(320f), dp(520f)))
        }
        musicView.bind(
            title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                .ifBlank { "正在播放" },
            artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            artwork = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            state = state,
            duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            appearance = appearance(),
            applyPlatformMaterial = applyPlatformMaterial,
            onToggle = { toggle(controller) },
            onPrevious = { skip(controller, false) },
            onNext = { skip(controller, true) },
            onSeek = { position ->
                runCatching { controller.transportControls.seekTo(position) }
            },
            onArtworkClick = {
                LockscreenMediaPresentationBridge.setPresentation(LockscreenMediaPresentation.MINI_PLAYER)
            },
        )
        musicView.visibility = if (
            LockscreenMediaPresentationBridge.presentation == LockscreenMediaPresentation.MUSIC_LOCKSCREEN &&
                isLockscreenShowing()
        ) View.VISIBLE else View.GONE
        position()
        if (isVisibleAndPlaying() && !tickPosted) {
            tickPosted = true
            handler.postDelayed(tick, 500L)
        }
    }

    private fun isVisibleAndPlaying() = view?.visibility == View.VISIBLE &&
        activeController?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun animateIn(target: View) {
        target.animate().cancel()
        target.visibility = View.VISIBLE
        target.alpha = 0f
        target.translationY = dp(18f).toFloat()
        target.animate().alpha(1f).translationY(0f).setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun animateOut(target: View) {
        if (target.visibility != View.VISIBLE) return
        target.animate().cancel()
        target.animate().alpha(0f).translationY(dp(18f).toFloat()).setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                target.visibility = View.GONE
                target.alpha = 1f
                target.translationY = 0f
            }.start()
    }

    private fun position() {
        val target = view ?: return
        if (host.width <= 0 || host.height <= 0) return
        // Match the outer span of the lockscreen shortcut backgrounds rather than the screen edges.
        val width = min(dp(336f), host.width - dp(48f)).coerceAtLeast(dp(240f))
        val height = min(dp(548f), (host.height * .60f).toInt()).coerceAtLeast(dp(470f))
        val params = target.layoutParams
        if (params == null || params.width != width || params.height != height) {
            target.layoutParams = (params ?: ViewGroup.LayoutParams(width, height)).apply {
                this.width = width
                this.height = height
            }
        }
        target.translationX = (host.width - width) / 2f
        target.translationY = (host.height * .27f).coerceAtMost((host.height - height - dp(72f)).toFloat())
        target.bringToFront()
    }

    private fun toggle(controller: MediaController) {
        runCatching {
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }.onFailure {
            val now = android.os.SystemClock.uptimeMillis()
            val key = if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                KeyEvent.KEYCODE_MEDIA_PAUSE
            } else KeyEvent.KEYCODE_MEDIA_PLAY
            runCatching {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
            }
        }
    }

    private fun skip(controller: MediaController, next: Boolean) {
        runCatching {
            if (next) controller.transportControls.skipToNext()
            else controller.transportControls.skipToPrevious()
        }.onFailure {
            val now = android.os.SystemClock.uptimeMillis()
            val key = if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
            runCatching {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
            }
        }
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density + .5f).toInt()
}

private class LockscreenMusicLockscreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val materialLayer = ImageView(context)
    private val materialEdge = View(context)
    private val artwork = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val elapsed = TextView(context)
    private val remaining = TextView(context)
    private val seekBar = LockscreenMusicSeekBar(context)
    private val previous = ImageButton(context)
    private val toggle = ImageButton(context)
    private val next = ImageButton(context)
    private val content = LinearLayout(context)
    private var lastAppearance: MiniPlayerAppearance? = null
    private var onSeek: ((Long) -> Unit)? = null

    init {
        clipToOutline = true
        outlineProvider = roundOutline(dp(30f).toFloat())
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        // The SystemUI material compositor needs the same rounded target as shortcut backgrounds.
        materialLayer.clipToOutline = true
        materialLayer.outlineProvider = roundOutline(dp(30f).toFloat())
        addView(materialLayer, LayoutParams(-1, -1))
        materialEdge.background = rounded(Color.TRANSPARENT, dp(30f).toFloat()).apply {
            setStroke(dp(1f), Color.argb(70, 255, 255, 255))
        }
        materialEdge.isClickable = false
        materialEdge.isFocusable = false
        addView(materialEdge, LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(dp(18f), dp(18f), dp(18f), dp(16f))
        addView(content, LayoutParams(-1, -1))
        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.background = rounded(Color.rgb(52, 52, 52), dp(20f).toFloat())
        artwork.clipToOutline = true
        artwork.outlineProvider = roundOutline(dp(20f).toFloat())
        artwork.setOnClickListener { tagArtworkClick?.invoke() }
        content.addView(artwork, LinearLayout.LayoutParams(0, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        title.textSize = 20f
        title.setTextColor(Color.rgb(45, 45, 45))
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        content.addView(title, LinearLayout.LayoutParams(-1, dp(30f)).apply { topMargin = dp(14f) })
        artist.textSize = 15f
        artist.setTextColor(Color.argb(190, 45, 45, 45))
        artist.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END
        content.addView(artist, LinearLayout.LayoutParams(-1, dp(24f)))
        content.addView(seekBar, LinearLayout.LayoutParams(-1, dp(24f)).apply { topMargin = dp(8f) })
        val times = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(elapsed, remaining).forEach {
            it.textSize = 11f
            it.setTextColor(Color.argb(190, 45, 45, 45))
        }
        times.addView(elapsed, LinearLayout.LayoutParams(0, dp(20f), 1f))
        remaining.gravity = Gravity.END
        times.addView(remaining, LinearLayout.LayoutParams(0, dp(20f), 1f))
        content.addView(times, LinearLayout.LayoutParams(-1, dp(20f)))
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER; weightSum = 3f }
        configureButton(previous, "上一首")
        configureButton(toggle, "播放或暂停")
        configureButton(next, "下一首")
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(68f), 1f))
        controls.addView(toggle, LinearLayout.LayoutParams(0, dp(76f), 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(68f), 1f))
        content.addView(controls, LinearLayout.LayoutParams(-1, dp(80f)).apply { topMargin = dp(4f) })
        seekBar.onSeekChanged = { position -> onSeek?.invoke(position) }
    }

    private var tagArtworkClick: (() -> Unit)? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateArtworkSize()
        materialLayer.invalidateOutline()
    }

    fun bind(
        title: String,
        artist: String,
        artwork: Bitmap?,
        state: PlaybackState?,
        duration: Long,
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
        onToggle: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onSeek: (Long) -> Unit,
        onArtworkClick: () -> Unit,
    ) {
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            materialLayer.setImageDrawable(
                when (appearance.backgroundMode) {
                    MINI_PLAYER_BACKGROUND_PURE -> rounded(appearance.pureColor, dp(30f).toFloat())
                    MINI_PLAYER_BACKGROUND_ADVANCED, MINI_PLAYER_BACKGROUND_SOFT_GLASS ->
                        rounded(Color.argb(1, 255, 255, 255), dp(30f).toFloat())
                    else -> rounded(Color.argb(166, 28, 31, 32), dp(30f).toFloat())
                },
            )
            if (appearance.backgroundMode == MINI_PLAYER_BACKGROUND_ADVANCED ||
                appearance.backgroundMode == MINI_PLAYER_BACKGROUND_SOFT_GLASS
            ) applyPlatformMaterial(materialLayer, appearance)
            materialEdge.visibility = if (appearance.backgroundMode == MINI_PLAYER_BACKGROUND_DEFAULT) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
        this.title.text = title
        this.artist.text = artist
        this.artwork.setImageBitmap(artwork)
        val total = duration.coerceAtLeast(0L)
        val position = currentPosition(state, total)
        seekBar.setPlayback(total, position, state != null &&
            state.actions and PlaybackState.ACTION_SEEK_TO != 0L)
        elapsed.text = formatTime(position)
        remaining.text = "-" + formatTime((total - position).coerceAtLeast(0L))
        toggle.setImageDrawable(MaterialRoundPathDrawable(if (state?.state == PlaybackState.STATE_PLAYING) {
            MATERIAL_ICON_PAUSE
        } else {
            MATERIAL_ICON_PLAY
        }, Color.rgb(48, 48, 48)))
        previous.setImageDrawable(MaterialRoundPathDrawable(MATERIAL_ICON_SKIP_PREVIOUS, Color.rgb(48, 48, 48)))
        next.setImageDrawable(MaterialRoundPathDrawable(MATERIAL_ICON_SKIP_NEXT, Color.rgb(48, 48, 48)))
        this.onSeek = onSeek
        tagArtworkClick = onArtworkClick
        toggle.setOnClickListener { onToggle() }
        previous.setOnClickListener { onPrevious() }
        next.setOnClickListener { onNext() }
        updateArtworkSize()
        post { updateArtworkSize() }
    }

    private fun currentPosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        val delta = if (state.state == PlaybackState.STATE_PLAYING) {
            ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        } else 0L
        return (state.position + delta).coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    private fun updateArtworkSize() {
        val innerWidth = (width - content.paddingLeft - content.paddingRight).coerceAtLeast(0)
        val fixedContentHeight = dp(196f)
        val innerHeight = (height - content.paddingTop - content.paddingBottom - fixedContentHeight)
            .coerceAtLeast(0)
        val size = min(innerWidth, innerHeight)
        val params = artwork.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            params.gravity = Gravity.CENTER_HORIZONTAL
            artwork.layoutParams = params
        }
    }

    private fun configureButton(button: ImageButton, description: String) {
        button.background = null
        button.contentDescription = description
        button.scaleType = ImageView.ScaleType.CENTER
        button.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun formatTime(value: Long): String {
        val seconds = (value / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60L, seconds % 60L)
    }

    private fun dp(value: Float) = (value * density + .5f).toInt()
}

/** A narrow, non-widget seek control so the lockscreen uses the reference's slim track. */
private class LockscreenMusicSeekBar(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(82, 36, 36, 36) }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 45, 45, 45) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(78, 78, 78) }
    private var duration = 0L
    private var position = 0L
    private var enabledForSeek = false
    private var tracking = false
    var onSeekChanged: ((Long) -> Unit)? = null

    fun setPlayback(duration: Long, position: Long, enabled: Boolean) {
        this.duration = duration.coerceAtLeast(0L)
        if (!tracking) this.position = position.coerceIn(0L, this.duration)
        enabledForSeek = enabled && this.duration > 0L
        alpha = if (enabledForSeek) 1f else .72f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val horizontalInset = dp(3f).toFloat()
        val start = horizontalInset
        val end = (width - horizontalInset).coerceAtLeast(horizontalInset)
        val centerY = height / 2f
        val radius = dp(2f).toFloat()
        canvas.drawRoundRect(start, centerY - radius, end, centerY + radius, radius, radius, trackPaint)
        val fraction = if (duration > 0L) position.toFloat() / duration else 0f
        val progressEnd = start + (end - start) * fraction.coerceIn(0f, 1f)
        canvas.drawRoundRect(start, centerY - radius, progressEnd, centerY + radius, radius, radius, progressPaint)
        if (duration > 0L) canvas.drawCircle(progressEnd, centerY, dp(3.5f).toFloat(), thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForSeek) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromX(event.x)
                tracking = false
                onSeekChanged?.invoke(position)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float) {
        val start = dp(3f).toFloat()
        val end = (width - dp(3f)).coerceAtLeast(dp(3f)).toFloat()
        val fraction = ((x - start) / (end - start).coerceAtLeast(1f)).coerceIn(0f, 1f)
        position = (duration * fraction).toLong()
        invalidate()
    }

    private fun dp(value: Float) = value * density + .5f
}

/**
 * Material Rounded paths held in a drawable so they do not need to resolve this module's
 * resources through SystemUI's foreign Context.
 */
private class MaterialRoundPathDrawable(
    pathData: String,
    color: Int,
) : Drawable() {
    private val path = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

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

private const val MATERIAL_ICON_PLAY =
    "M320 687V273Q320 256 332 244.5Q344 233 360 233Q365 233 370.5 234.5Q376 236 381 239L707 446Q716 452 720.5 461Q725 470 725 480Q725 490 720.5 499Q716 508 707 514L381 721Q376 724 370.5 725.5Q365 727 360 727Q344 727 332 715.5Q320 704 320 687ZM400 346 610 480 400 614ZM400 614 610 480 400 346Z"
private const val MATERIAL_ICON_PAUSE =
    "M640 760Q607 760 583.5 736.5Q560 713 560 680V280Q560 247 583.5 223.5Q607 200 640 200Q673 200 696.5 223.5Q720 247 720 280V680Q720 713 696.5 736.5Q673 760 640 760ZM320 760Q287 760 263.5 736.5Q240 713 240 680V280Q240 247 263.5 223.5Q287 200 320 200Q353 200 376.5 223.5Q400 247 400 280V680Q400 713 376.5 736.5Q353 760 320 760Z"
private const val MATERIAL_ICON_SKIP_NEXT =
    "M660 680V280Q660 263 671.5 251.5Q683 240 700 240Q717 240 728.5 251.5Q740 263 740 280V680Q740 697 728.5 708.5Q717 720 700 720Q683 720 671.5 708.5Q660 697 660 680ZM220 645V315Q220 297 232 286Q244 275 260 275Q265 275 271 276Q277 277 282 281L530 447Q539 453 543.5 461.5Q548 470 548 480Q548 490 543.5 498.5Q539 507 530 513L282 679Q277 683 271 684Q265 685 260 685Q244 685 232 674Q220 663 220 645ZM300 390 436 480 300 570ZM300 570 436 480 300 390Z"
private const val MATERIAL_ICON_SKIP_PREVIOUS =
    "M220 680V280Q220 263 231.5 251.5Q243 240 260 240Q277 240 288.5 251.5Q300 263 300 280V680Q300 697 288.5 708.5Q277 720 260 720Q243 720 231.5 708.5Q220 697 220 680ZM678 679 430 513Q421 507 416.5 498.5Q412 490 412 480Q412 470 416.5 461.5Q421 453 430 447L678 281Q683 277 689 276Q695 275 700 275Q716 275 728 286Q740 297 740 315V645Q740 663 728 674Q716 685 700 685Q695 685 689 684Q683 683 678 679ZM660 390V570L524 480ZM660 570V390L524 480Z"
