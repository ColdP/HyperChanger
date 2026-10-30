// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island

import btm.m.os4.systemuihook.hypermusiccover.next.AlbumArt
import btm.m.os4.systemuihook.hypermusiccover.next.CardTouch
import btm.m.os4.systemuihook.hypermusiccover.next.MediaCard
import btm.m.os4.systemuihook.hypermusiccover.next.ClockCollapse
import btm.m.os4.systemuihook.hypermusiccover.next.ClockRoom
import btm.m.os4.systemuihook.hypermusiccover.next.ClockRoomPolicy
import btm.m.os4.systemuihook.hypermusiccover.next.CoverMorphMotion
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.TrackName
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * One mini player: the pill, the small island beside it and every morph between them and
 * the lock screen's cards, for one keyguard shortcut host. MiniPlayerRuntime owns one per host.
 *
 * Moved out of MiniPlayerRuntime.kt as it was (2026-10-14): the file was two things, and this
 * is the larger. The class and two constants the runtime also reads became internal; nothing
 * else changed.
 */
internal class MiniPlayerController(
    private val host: ViewGroup,
    private val left: View,
    private val right: View,
    private val prefs: SharedPreferences,
    private val loader: ClassLoader,
) {
    private val context = host.context
    private val handler = Gen.handler()
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MiniPlayerView? = null
    private var controller: MediaController? = null
    private var header: WeakReference<View>? = null
    private val suppressedHeaders = WeakViewOverrideRegistry<View>()
    private var nativeSuppressionRequested = false
    private var morph: MiniCardMorph? = null
    private var morphScene = false
    private var artBridged = false
    /** Whether the pill is bound to the music as refresh last bound it, not to a notification. */
    private var pillShowsMusic = false
    /** Refresh had nothing to bind the pill's island to: it still holds another's picture. */
    private var pillUnbound = false
    private var lastTrack = ""
    private var cachedCover: Bitmap? = null
    private var refreshPosted = false
    private var positionPosted = false
    private var destroyed = false
    private var configuredHeightDp = MiniPlayerConfig.DEFAULT_HEIGHT_DP
    private var config = JSONObject(MiniPlayerConfig.defaultJson())
    private var forceHeaderRefresh = true
    private var lastPresentationLog = ""
    private var lastActive = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        configStale = true
        scheduleRefresh()
    }
    /** The config is read from preferences only when they change, not on every refresh. */
    private var configStale = true
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { scheduleRefresh() }
    private val mediaListener = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadataCalls++
            lastMetadataAt = android.os.SystemClock.uptimeMillis()
            liveMetadata = metadata
            scheduleRefresh()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Usable up to this very change: the gap is counted from here. Counted from the
            // last refresh that saw it usable, a track played through left that minutes old,
            // and QQ Music's stop between two tracks ended the music every time (#26, log
            // 2026-09-30: ended, and the same session back 220ms later).
            if (playbackUsable(liveState?.state) && !playbackUsable(state?.state)) {
                usableSeenAt = android.os.SystemClock.uptimeMillis()
            }
            liveState = state
            scheduleRefresh()
        }
        override fun onSessionDestroyed() {
            liveFor = null
            usableSeenAt = 0L
            scheduleRefresh()
        }
    }

    /** When the row's session last read as usable (uptime); 0 since it was chosen or destroyed. */
    private var usableSeenAt = 0L
    private val usableGapEnd = Runnable { refresh() }

    /**
     * The registered session's state and metadata, as its callbacks last handed them over.
     * Each read of a MediaController's own is a binder call into the system server - a refresh
     * made four or five, and on the lock screen's slow cores they came to ~2ms of the frame a
     * switch lands on (traced 2026-09-25). Another session's are still asked for.
     */
    private var liveFor: android.media.session.MediaSession.Token? = null
    private var liveState: PlaybackState? = null
    private var liveMetadata: MediaMetadata? = null
    /** For `op mini`: the metadata callbacks the registered session has delivered. */
    private var metadataCalls = 0
    private var lastMetadataAt = 0L

    /** For `op mini`: the session the pill follows, what it holds, and what is really there. */
    private fun describeSession(): String {
        fun id(c: MediaController?) = c?.let { it.packageName + "@" + Integer.toHexString(it.sessionToken.hashCode()) }
        fun title(m: MediaMetadata?) = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val c = controller
        val w = Main.miniPlayerSession()
        return "session=${id(c)} live=${c != null && c.sessionToken == liveFor} calls=$metadataCalls rearms=$rearms " +
            "liveTitle=${title(liveMetadata)} binderTitle=${runCatching { title(c?.metadata) }.getOrNull()} " +
            "state=${stateOf(c ?: return "session=null watched=${id(w)} ")?.state} seenAt=$usableSeenAt " +
            "watched=${id(w)} watchedTitle=${runCatching { title(w?.metadata) }.getOrNull()} " +
            "card=${Main.miniPlayerMediaCardPresent()} sameObj=${c === w} " +
            "cbReg=${runCatching { Xp.getObjectField(c, "mCbRegistered") }.getOrNull()} " +
            "cbs=${runCatching { (Xp.getObjectField(c, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "wCbs=${runCatching { (Xp.getObjectField(w, "mCallbacks") as List<*>).size }.getOrNull()} " +
            "lastCallAgo=${android.os.SystemClock.uptimeMillis() - lastMetadataAt} track=$lastTrack " +
            "ink=[${describeInk()}] "
    }

    /** For `op mini`: the text colours the system gave its own card and rows, by view id. */
    private fun describeInk(): String {
        fun hex(v: View?) = (v as? android.widget.TextView)?.let { Integer.toHexString(it.currentTextColor) }
        val sb = StringBuilder("cardTitle=${hex(Main.miniPairTitle())} cardArtist=${hex(Main.miniPairArtist())}")
        val seen = HashSet<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) {
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "?"
                val entry = "$id=${Integer.toHexString(v.currentTextColor)}"
                if (seen.add(entry) && seen.size <= 12) sb.append(' ').append(entry)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        notificationStack()?.let(::walk)
        Main.miniPlayerMediaHeader()?.let(::walk)
        return sb.toString()
    }

    private fun stateOf(c: MediaController): PlaybackState? =
        if (liveFor != null && c.sessionToken == liveFor) liveState else c.playbackState

    private fun metadataOf(c: MediaController): MediaMetadata? =
        if (liveFor != null && c.sessionToken == liveFor) liveMetadata else c.metadata

    /**
     * The media card has moved to another track: the callback is put on a controller of the
     * pill's own and the session read again. The system server drops a controller callback for
     * good the first time a call to it fails (MediaSessionRecord.performOnCallbackHolders), and
     * says nothing to the controller, which goes on believing it is registered. Apple's metadata
     * with its lyric attached runs to 50k characters, to every controller in SystemUI at once:
     * skipping through tracks filled SystemUI's binder buffer, the pill's callback was dropped,
     * and the pill showed one song while another played, until SystemUI restarted (2026-10-14).
     * Its own controller because the one it was handed is often Main's: with Main's callback
     * still on it, taking ours off and putting it back never registers anything again.
     */
    fun cardTrackChanged() {
        handler.post {
            val c = controller ?: return@post
            if (destroyed) return@post
            val own = runCatching { MediaController(context, c.sessionToken) }.getOrNull() ?: return@post
            runCatching { Gen.unsessionCallback(c, mediaListener) }
            controller = own
            runCatching { Gen.sessionCallback(own, mediaListener, handler) }
            // Not followLive: the gap a player between two tracks is given runs on (#26).
            liveFor = null
            liveState = own.playbackState
            liveMetadata = own.metadata
            liveFor = own.sessionToken
            rearms++
            refresh()
        }
    }
    /** For `op mini`: how often the callback has been put back on a track change. */
    private var rearms = 0

    /** The callbacks are [c]'s from here: what it has now, and what they bring after. */
    private fun followLive(c: MediaController?) {
        liveFor = null
        usableSeenAt = 0L
        handler.removeCallbacks(usableGapEnd)
        liveState = c?.playbackState
        liveMetadata = c?.metadata
        liveFor = c?.sessionToken
    }
    /** The stack's content top the clock was last given room by (ClockRoom.roomForRows). */
    private var clockTopAsked = Float.NaN

    private val preDraw = ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC islandsPreDraw"); try {
        holdKept()
        holdRows()
        // The rows can settle after the stack last told the clock where they are - a row let out
        // is laid out a frame or more after the list changed. The clock is asked again then.
        val top = stackContentTop()
        if (top.isNaN() != clockTopAsked.isNaN() || kotlin.math.abs(top - clockTopAsked) > 2f) {
            clockTopAsked = top
            ClockRoom.reassertClockRoom()
        }
        updateVisibility()
        if (player?.visibility == View.VISIBLE) {
            position()
            followShortcuts()
        }
        updateDiscs()
        lottiesFollowSight()
        true
    } finally { android.os.Trace.endSection() } }

    private val followRelative = Matrix()
    private val followHost = Matrix()
    private var followRoot: WeakReference<View>? = null
    private var rowHeldOff = false
    private var shortcutRow: WeakReference<View>? = null

    /**
     * The torch and camera's motion and fade, on the pill, every frame. The pill hangs off the
     * window root, outside everything the OEM animates. Swiping up, keyguard_bottom_area fades
     * to a transitionAlpha of 0.05 and zooms to 0.9 (`op alphasweep`, 2026-09-24) - but the two
     * buttons also move on their own inside it, drawing in toward each other and rising less
     * than the row's zoom alone would carry them; following the row, the pill (filmed) rose
     * faster than they did and kept its width while they closed in. So the pill follows the
     * buttons themselves: its centre on the midpoint of where the two are drawn, its scale the
     * ratio of their drawn spacing to their resting spacing.
     *
     * In the full-screen doze the buttons are put away while the pill stays; there it takes only
     * keyguard_root_view's own zoom and fade, and it goes back to the buttons once they have
     * faded all the way back in after the wake - switching earlier, it dropped with their fade
     * and came back: a flash.
     */
    private fun followShortcuts() {
        val view = player ?: return
        // The spread's morphs place the pill; out as the list, it is not drawn.
        if (spread != null) {
            view.setAnimationMatrix(null)
            return
        }
        // A flight's morph is not the pill's: the pill (and the small island coming up beside
        // it) still ride with the buttons while the flight is out.
        if (pillMorph() != null) {
            view.setAnimationMatrix(null)
            lastFollow.fill(0f)
            // The small island still in its place under the media card's morph: waiting for its
            // row, or coming and going there with no row.
            if (group != null) smallIsland?.let { placeSmallIsland(null, it.transitionAlpha) }
            return
        }
        if (shortcutRow?.get()?.isAttachedToWindow != true) findRow()?.let { shortcutRow = WeakReference(it) }
        val row = shortcutRow?.get() ?: return
        val keyguardRoot = followRoot?.get()
        followHost.reset()
        host.transformMatrixToGlobal(followHost)
        if (!followHost.invert(hostInverse)) return
        var fade = 1f
        var rowFade = 1f
        var aboveRoot = false
        var v: View? = row
        while (v != null && v !== host) {
            if (v === keyguardRoot) aboveRoot = true
            if (aboveRoot) fade *= v.alpha * v.transitionAlpha
            else rowFade *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        // Held with the buttons, for as long as they are: this runs before holdButtonsThroughDoze
        // each frame, and rowFade is whatever was last written - the wake's 0.04, or the 1 the
        // hold wrote a frame ago. Waking, the pill let go on one and took the other: the row
        // dropped to nothing and faded in beside buttons that stayed (filmed 2026-09-26).
        if (MiniPlayerScene.fullScreenAodActive || holdButtons) rowHeldOff = true
        // ...or until the PIN pad is up, which keeps the row's fade down for as long as it is.
        else if (rowHeldOff && (rowFade >= 0.99f || Main.bouncerShown())) rowHeldOff = false
        followRowFade = rowFade
        // The lock screen's editor button, up after a long press on the clock, is where the row
        // is and under it: the row goes as it comes, on its own fade, and comes back as it goes.
        val editShown = editButtonShown(row)
        fade *= 1f - editShown
        view.yieldTouches = editShown > 0.01f
        val matrix = followRelative
        // With a shortcut switched off the row rides on the one left, or on the row's own frame
        // with neither; it used to take only the keyguard's zoom then, and stood still while
        // the swipe up carried the buttons away.
        // Both switched on is not both drawn: with one of them not shown for a frame the pill
        // fell through to the keyguard's zoom alone and stayed down while the swipe up carried
        // the buttons off (#36). It rides on whichever one is drawn then, as with one switched off.
        val lUp = followable(left)
        val rUp = followable(right)
        val single = if (lUp != rUp) (if (lUp) left else right) else null
        if (!rowHeldOff && lUp && rUp) {
            followBranch = "pair"
            fade *= rowFade
            // Where each button is drawn now and where it rests, both in the host's pixels.
            if (!drawnCentre(left, drawnL) || !drawnCentre(right, drawnR)) return
            restCentre(left, restL)
            restCentre(right, restR)
            val restSpan = restR[0] - restL[0]
            if (kotlin.math.abs(restSpan) < 1f) return
            var scale = (drawnR[0] - drawnL[0]) / restSpan
            var mx = (drawnL[0] + drawnR[0]) / 2f - (restL[0] + restR[0]) / 2f
            var my = (drawnL[1] + drawnR[1]) / 2f - (restL[1] + restR[1]) / 2f
            // At rest the arithmetic gives 0.99999 and a hair of shift; writing that every frame
            // invalidated the pill every frame - a redraw that never stopped, blur and all.
            if (kotlin.math.abs(scale - 1f) < 0.0005f) scale = 1f
            if (kotlin.math.abs(mx) < 0.25f) mx = 0f
            if (kotlin.math.abs(my) < 0.25f) my = 0f
            matrix.reset()
            matrix.setTranslate(-(restL[0] + restR[0]) / 2f, -(restL[1] + restR[1]) / 2f)
            matrix.postScale(scale, scale)
            matrix.postTranslate((restL[0] + restR[0]) / 2f + mx, (restL[1] + restR[1]) / 2f + my)
        } else if (!rowHeldOff && single != null) {
            followBranch = if (single === left) "left" else "right"
            fade *= rowFade
            // Moved as its centre is, and zoomed about it as it is.
            if (!drawnCentre(single, drawnL)) return
            restCentre(single, restL)
            var scale = drawnZoom(single)
            var mx = drawnL[0] - restL[0]
            var my = drawnL[1] - restL[1]
            if (kotlin.math.abs(scale - 1f) < 0.0005f) scale = 1f
            if (kotlin.math.abs(mx) < 0.25f) mx = 0f
            if (kotlin.math.abs(my) < 0.25f) my = 0f
            matrix.reset()
            matrix.setTranslate(-restL[0], -restL[1])
            matrix.postScale(scale, scale)
            matrix.postTranslate(restL[0] + mx, restL[1] + my)
        } else if (!rowHeldOff && row.isShown) {
            followBranch = "row"
            fade *= rowFade
            // The row's own zoom and movement, as the keyguard's below.
            matrix.reset()
            row.transformMatrixToGlobal(matrix)
            matrix.postConcat(hostInverse)
            restOrigin(row, restL)
            matrix.preTranslate(-restL[0], -restL[1])
        } else if (keyguardRoot != null) {
            followBranch = if (rowHeldOff) "root/held" else "root"
            // The keyguard's own zoom only, as a matrix from its untransformed slot.
            matrix.reset()
            keyguardRoot.transformMatrixToGlobal(matrix)
            matrix.postConcat(hostInverse)
            restOrigin(keyguardRoot, restL)
            matrix.preTranslate(-restL[0], -restL[1])
        } else {
            followBranch = "none"
            matrix.reset()
        }
        fade = unheldInDoze(fade)
        placeSmallIsland(matrix, fade)
        // Into the pill's own frame: conjugated by its slot in the host.
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        matrix.postTranslate(-view.left.toFloat(), -view.top.toFloat())
        // The squeeze, inside that: about the pill's own centre in its slot, its nudge included.
        // In pixels, not shares: with a small island the row the squeeze measured is wider
        // than the pill, and its shares of the row are not the pill's.
        val sx = squeeze.pillScaleX(view.width.toFloat())
        val sy = squeeze.pillScaleY()
        val shift = squeeze.pillShiftPx()
        if (kotlin.math.abs(sx - 1f) > 0.0005f || kotlin.math.abs(sy - 1f) > 0.0005f
            || kotlin.math.abs(shift) > 0.25f) {
            pillSqueeze.setScale(sx, sy, view.translationX + view.width / 2f,
                view.translationY + view.height / 2f)
            pillSqueeze.postTranslate(shift, 0f)
            matrix.preConcat(pillSqueeze)
        }
        // Only a real change is written: each write invalidates the pill.
        matrix.getValues(followValues)
        if (!followValues.contentEquals(lastFollow)) {
            System.arraycopy(followValues, 0, lastFollow, 0, 9)
            if (matrix.isIdentity) view.setAnimationMatrix(null) else view.setAnimationMatrix(matrix)
        }
        // A notification flying out of the pill, or home into it, is the flight: the pill under
        // it stays out of sight until the flight lands on it.
        // Nor is it shown with another island's picture still in it, waiting to be bound.
        val pillFade = if (pillHeld() || exchangeHoldsPill() || pillUnbound) 0f else fade
        followPillFade = pillFade
        if (kotlin.math.abs(view.transitionAlpha - pillFade) > 0.002f) view.transitionAlpha = pillFade
    }

    /**
     * How much of the lock screen's editor entry is up, 0 to 1: its button in the bottom area
     * (custom_lockscreen_button, KeyguardBottomAreaInjector.showCustomLockscreenButton, a long
     * press on the clock), its alpha as it fades in and out. It sits where the row is, and the
     * row, drawn over it, hid it (2026-09-26).
     */
    private fun editButtonShown(row: View): Float {
        val button = editButton?.get()?.takeIf { it.isAttachedToWindow } ?: run {
            val id = row.resources.getIdentifier("custom_lockscreen_button", "id", "com.android.systemui")
            if (id == 0) return 0f
            row.findViewById<View>(id)?.also { editButton = WeakReference(it) } ?: return 0f
        }
        if (!button.isShown) return 0f
        return (button.alpha * button.transitionAlpha).coerceIn(0f, 1f)
    }

    private var editButton: WeakReference<View>? = null

    /** The editor's button is up over the row: its touches are its own. */
    private fun editButtonUp(): Boolean =
        shortcutRow?.get()?.let { editButtonShown(it) > 0.01f } == true

    /** What followShortcuts read and gave the pill last, for the doze trace and `op mini`. */
    private var followBranch = "-"
    private var followRowFade = 1f
    private var followPillFade = 1f

    private val hostInverse = Matrix()
    private val discToScreen = Matrix()
    private val discInverse = Matrix()
    private val scratch = Matrix()
    private val pillSqueeze = Matrix()
    private val drawnL = FloatArray(2)
    private val drawnR = FloatArray(2)
    private val restL = FloatArray(2)
    private val restR = FloatArray(2)
    private val followValues = FloatArray(9)
    private val lastFollow = FloatArray(9)

    /** A button the pill can take its motion from this frame: switched on, drawn, laid out. */
    private fun followable(button: View): Boolean =
        !switchedOff(button) && button.isAttachedToWindow && button.isShown &&
            button.width > 0 && button.height > 0

    /** A view's centre as drawn, every ancestor's transform included, in host pixels. */
    private fun drawnCentre(v: View, out: FloatArray, inverse: Matrix = hostInverse): Boolean {
        if (!v.isAttachedToWindow || v.width <= 0) return false
        scratch.reset()
        v.transformMatrixToGlobal(scratch)
        scratch.postConcat(inverse)
        out[0] = v.width / 2f
        out[1] = v.height / 2f
        scratch.mapPoints(out)
        return true
    }

    /** The same centre with no transform anywhere: layout positions only. */
    private fun restCentre(v: View, out: FloatArray = FloatArray(2)): FloatArray {
        restOrigin(v, out)
        out[0] += v.width / 2f
        out[1] += v.height / 2f
        return out
    }

    private fun restOrigin(v: View, out: FloatArray) {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== host) {
            val parent = cur.parent as? View
            x += cur.left - (parent?.scrollX ?: 0)
            y += cur.top - (parent?.scrollY ?: 0)
            cur = parent
        }
        out[0] = x
        out[1] = y
    }

    // ------------------------------------------------------------ the islands

    /** The row's islands in order: the focus ones that outrank the music, MUSIC_ISLAND, the rest. Set by refreshUnsafe. */
    private var islandKeys: List<String> = emptyList()

    /** The islands wear the ordinary notification's colours and soft glass, always (2026-10-09). */
    private fun notificationMaterial(): Boolean = true

    private fun stackedStyle(): Boolean =
        config.optInt(MiniPlayerConfig.STYLE, MiniPlayerConfig.STYLE_ROW) == MiniPlayerConfig.STYLE_STACK

    private fun smallRestHeight(): Int = if (stackedStyle())
        MiniPlayerGeometry.stackBackSizePx(discDiameter()) else discDiameter()

    private fun materialKey(): String =
        "${if (notificationMaterial()) "notification" else "media"}:${MiniPlayerRuntime.materialStyleKey}"

    private fun discMaterialGeneration(): Int =
        MiniPlayerRuntime.materialGeneration * 2 + if (notificationMaterial()) 1 else 0

    private fun smallRestWidth(): Int = if (stackedStyle())
        MiniPlayerGeometry.stackBackSizePx(pillRest(false)?.width ?: player?.width ?: discDiameter())
    else discDiameter()

    /** The island in the pill. The one after it is the small island beside the pill. */
    private var selectedIsland: String? = null

    private var smallIsland: ShortcutDisc? = null
    private var smallKey: String? = null
    private var smallIconKey: Any? = null
    private val smallRest = FloatArray(2)
    private val smallFollow = Matrix()
    private val smallFollowValues = FloatArray(9)
    private val lastSmallFollow = FloatArray(9)
    private val islandListener: () -> Unit = { refresh() }

    /** The row as it was last laid out: where a notification let out of it had its place. */
    private var islandOrder: List<String> = emptyList()

    /**
     * [key] - let out of the row and not among the notes for now - kept where it was in the row,
     * not at its end. Appended, the island after it was the row's first: with three islands, the
     * second pulled up out of the pill had the first's picture come up in the small place instead
     * of the third's, and it went back to the third's once the notification was home (2026-09-25).
     */
    private fun keptInPlace(base: List<String>, key: String): List<String> {
        if (key in base) return base
        val at = islandOrder.indexOf(key)
        if (at < 0) return base + key
        val before = islandOrder.subList(0, at).toSet()
        val insertAt = base.indexOfLast { it in before } + 1
        return base.toMutableList().apply { add(insertAt, key) }
    }

    /** How many islands the row has while the pill is showing; one is the pill alone. */
    fun islandCount(): Int = if (player?.visibility == View.VISIBLE) islandKeys.size else 0

    fun selectedIsMusic(): Boolean = selectedIsland == MUSIC_ISLAND

    fun allowsSideSwitch(): Boolean = !stackedStyle()

    fun allowsStackSwitch(): Boolean = stackedStyle() && islandCount() >= 2 &&
        !frameTaken() && !appearing

    /** The rear card takes the front; the current card goes down behind it. */
    fun stackSwipeEnd(dy: Float, velocityY: Float, cancelled: Boolean): Boolean {
        if (!allowsStackSwitch() ||
            !MiniPlayerStackGesture.commits(dy, velocityY, density(), cancelled)) return false
        val view = player ?: return false
        val oldBig = selectedIsland ?: return false
        val oldSmall = smallKey
        val next = oldSmall?.takeIf { it in islandKeys && it != oldBig }
            ?: MiniPlayerStackGesture.nextKey(islandKeys, oldBig) ?: return false
        val rest = view.restBoxOnScreen() ?: return false
        val w = rest.w * view.scaleX
        val h = rest.h * view.scaleY
        val drawn = CoverMorphMotion.Box(rest.cx() - w / 2f + view.nudgeX,
            rest.cy() - h / 2f + view.nudgeY, w, h)
        preferredSmall = null
        aloneFirst = null
        snapSmallOnce = true
        selectedIsland = next
        refresh()
        startSwap(oldBig, oldSmall, SWAP_STACK, pillFrom = drawn,
            flingPx = velocityY.coerceAtLeast(0f))
        return true
    }

    /**
     * The island a pull opens as a flight: the small island's, or the pill's. The music too,
     * once there are other islands in the row to stay behind; alone, it has the media card's
     * own morph (canDrag).
     */
    fun noteKeyFor(small: Boolean): String? =
        (if (small) smallKey else selectedIsland)?.takeIf { it != MUSIC_ISLAND || musicFlies() }

    /** For the touch log: the small island as a DOWN saw it, when there is one. */
    fun smallProbe(x: Float, y: Float): String? {
        val v = smallIsland ?: return null
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val dist = kotlin.math.hypot(x - xy[0] - smallRest[0], y - xy[1] - smallRest[1]).toInt()
        return "sk=${smallKey?.takeLast(12)} v=${v.visibility} grow=$smallGrowing " +
            "at=${(xy[0] + smallRest[0]).toInt()},${(xy[1] + smallRest[1]).toInt()} dist=$dist " +
            "w=${smallRestWidth()}"
    }

    /** For the touch log: why a notification could not be pulled out. */
    fun noteBusy(): String = "noteMorph=${noteMorphKey != null} morph=${morph != null} " +
        "flight=${flight != null} rowWait=${rowWaitKey != null}"

    /**
     * The row's first island, swiped to the row's right end: alone in the pill, the small
     * island gone. Only while it is still the one in the pill and still first.
     */
    private var aloneFirst: String? = null

    private fun firstAlone(keys: List<String>, selected: String?): Boolean =
        selected != null && aloneFirst == selected && keys.firstOrNull() == selected

    /**
     * Whether there is an island to switch to that way. The row has ends at both sides, each
     * an island alone in a full-width pill (2026-09-25, the user's choice): the first alone,
     * then first and second, second and third ... and the last alone. It used to go round,
     * and no island ever showed without a small one beside it.
     */
    private fun canSwitchIsland(next: Boolean): Boolean {
        val keys = islandKeys
        if (keys.size < 2) return false
        val at = keys.indexOf(selectedIsland).coerceAtLeast(0)
        return if (next) at < keys.size - 1 else at > 0 || !firstAlone(keys, selectedIsland)
    }

    /** The next island (a swipe to the left) or the one before it takes the pill. */
    fun switchIsland(next: Boolean, flingPx: Float = 0f) {
        // One island out as its card or not, a swipe switches the row's own islands: the card
        // stays as it is (2026-09-25).
        val keys = islandKeys
        if (!canSwitchIsland(next)) {
            resetIslandDrag()
            return
        }
        val oldBig = selectedIsland
        val oldSmall = smallKey
        val at = keys.indexOf(selectedIsland).coerceAtLeast(0)
        // At the first island the pill keeps it: to the left the second comes out beside it,
        // to the right the one beside it goes into hiding where it is and the pill widens.
        val kept = if (next) firstAlone(keys, oldBig) else at == 0
        // The pill as the finger left it - narrowed, and leaning toward the small island - and
        // the small island as small as the pull had made it: the switch goes on from there.
        // From the rest frame, both jumped back first and the widening started with a hitch that
        // the small island growing into the pill never had (2026-09-25).
        val oldRest = if (kept) player?.let(::drawnPillBox) else null
        val smallSide = smallIsland?.takeIf { kept && islandDragging && it.visibility == View.VISIBLE }
            ?.shapeWidth?.toFloat() ?: 0f
        preferredSmall = null
        if (kept) {
            selectedIsland = keys[0]
            aloneFirst = if (next) null else keys[0]
        } else {
            aloneFirst = null
            selectedIsland = keys[at + if (next) 1 else -1]
        }
        refresh()
        // One goes into hiding and another comes out of it, as the super island's row does
        // (SwipeEventCoordinator) - with two islands too, now that either can be alone.
        startSwap(oldBig, oldSmall, if (next) SWAP_NEXT else SWAP_PREV,
            pillFrom = oldRest, pillStays = kept, pillKept = kept, smallFromSide = smallSide,
            flingPx = flingPx)
    }

    /** The pill's rest frame as islandDrag has it drawn: scaled about its centre, and nudged. */
    private fun drawnPillBox(view: MiniPlayerView): CoverMorphMotion.Box? {
        val r = view.restBoxOnScreen() ?: return null
        if (!islandDragging) return r
        val w = r.w * view.scaleX
        val h = r.h * view.scaleY
        return CoverMorphMotion.Box(r.cx() - w / 2f + view.nudgeX, r.cy() - h / 2f + view.nudgeY, w, h)
    }

    /**
     * A tap on the small island opens what it stands for, as a tap on the pill would: a
     * notification comes out as its own row. The music has no row of its own to open - its
     * small island is the pill's to take, so the tap brings it into the pill.
     */
    fun selectSmallIsland() { android.os.Trace.beginSection("MC t.selectSmall"); try {
        val key = smallKey ?: return
        // The music opens the cover from the small island as from the pill.
        if (key == MUSIC_ISLAND && config.getBoolean(MiniPlayerConfig.ENABLED)) {
            tapIsland(key)
            return
        }
        // With one island out as its card, a tap on another opens it in that one's stead.
        if (key != MUSIC_ISLAND || expandedKey() != null) {
            expandNote(key)
            return
        }
        val oldBig = selectedIsland
        selectedIsland = key
        refresh()
        startSwap(oldBig, key)
    } finally { android.os.Trace.endSection() } }

    /**
     * Whether a point on screen is on the small island's circle, a little slop included - less
     * than the gap to the camera's disc, so a tap on that disc's near edge stays the camera's.
     */
    fun smallIslandAt(x: Float, y: Float): Boolean {
        val v = smallIsland ?: return false
        // Out of sight under an exchange - the place it left, or the one it lands in - the place
        // is still the small island's to tap.
        if ((v.visibility != View.VISIBLE || smallKey == null) &&
            exchange?.seats?.small == null) return false
        val xy = IntArray(2).also(host::getLocationOnScreen)
        if (stackedStyle()) {
            // Only the exposed lower rim selects the rear card; the face belongs to the front.
            val front = player?.restBoxOnScreen() ?: return false
            val rear = smallBoxOnScreen()
            return x >= rear.x && x <= rear.x + rear.w &&
                y >= front.y + front.h && y <= front.y + front.h + dp(12f)
        }
        val r = discDiameter() / 2f + minOf(dp(6f), dp(MiniPlayerGeometry.DISC_GAP_DP) * 3 / 4)
        val dx = x - (xy[0] + smallRest[0])
        val dy = y - (xy[1] + smallRest[1])
        return dx * dx + dy * dy <= r * r
    }

    /**
     * The small island: the island after the pill's, as a circle of the same glass beside it,
     * showing its picture. It sits under the pill in the host, as the super island's small
     * island sits under its big one.
     */
    private fun updateSmallIsland(music: MediaController?, notes: List<LockIslands.Note>) { android.os.Trace.beginSection("MC updateSmall"); try {
        // A notification folding back into the small island's place, or pulled out of it and
        // not let go yet, holds that place for as long as its flight is out: whatever was
        // there shrinks away and the flight lands in it. One that has left for good (a tap, a
        // committed pull) frees it: the next island comes up there while the flight is still
        // on its way, and with none the pill takes the room back - the first version held the
        // place empty till the flight had settled, and the next one popped up half a second
        // after (filmed 2026-09-25).
        val flying = noteMorphKey?.takeIf { flight != null }
        val held = flying?.takeIf { !flightOut }
        val keys = if (flying != null && flightOut) islandKeys - flying else islandKeys
        val preferred = preferredSmall?.takeIf { it in keys && it != selectedIsland }
        // A switch running: the small place is whoever the seats give it to - an island still on
        // its way there included, out of sight until it lands (exchangeHoldsSmall) - and nothing
        // else: with two islands, a circle came up beside the pill for every switch (2026-09-25).
        val seated = exchange?.seats
        // Anything else putting another island in the pill ends the first's being alone.
        if (aloneFirst != null && aloneFirst != selectedIsland) aloneFirst = null
        val key = when {
            seated != null -> seated.small?.takeIf { it != selectedIsland && it in islandKeys }
            held != null && held != selectedIsland -> held
            keys.size < 2 -> null
            preferred != null -> preferred
            // The first swiped alone, or the last with none after it: the pill alone (canSwitchIsland).
            firstAlone(keys, selectedIsland) && !stackedStyle() -> null
            stackedStyle() -> MiniPlayerStackGesture.nextKey(keys, selectedIsland)
            else -> keys.getOrNull(keys.indexOf(selectedIsland).coerceAtLeast(0) + 1)
        }
        val previous = smallKey
        smallKey = key
        // Gone from the row: updateVisibility shrinks it away rather than cutting it.
        if (key == null) return
        val d = discDiameter()
        val frame = maxOf(discFrame(d), smallRestWidth() + dp(16f))
        val view = smallIsland ?: ShortcutDisc(context).also {
            smallIsland = it
            // Right after the lock screen's layer, which puts it just under the pill.
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(frame, discFrame(d)))
            Gen.ownView(host, it)
        }
        val frameHeight = discFrame(d)
        if (!smallWide && (view.layoutParams.width != frame || view.layoutParams.height != frameHeight))
            resizeSmallIsland(view, frame, frameHeight)
        view.dress(discMaterialGeneration()) {
            MiniPlayerRuntime.material(it, loader, notificationMaterial())
        }
        view.setIconHidden(false)
        if (swap == null && !islandDragging && !smallGrowing && landingBox == null)
            view.setShape(smallRestWidth(), smallRestHeight())
        // The one showing, going for a flight coming in, goes as itself: it keeps its picture
        // while it shrinks away. It used to take the incoming one's at once - and, asked only
        // while it still showed, took it anyway on the next refresh, mid-shrink. It takes the
        // flight's picture once the flight is landing on it (flightLanding).
        if (key == held && !flightLanding) return
        val showing = view.visibility == View.VISIBLE && !(smallGrowing && smallGrow.target == 0f)
        val note = if (key == MUSIC_ISLAND) null
            else notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        // A focus template's own picture for the small island, moving if it moves; bare as the
        // super island draws it.
        // Every notification's picture comes cut or whole already (LockIslands.roundIcon).
        if (stackedStyle()) {
            val metadata = music?.let(::metadataOf)
            view.setCardText(
                note?.timer?.displayText() ?: note?.title ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(context, "hmcNowPlaying", "正在播放") },
                note?.text ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
        } else view.setCardText(null, null)
        view.setIconBare(note != null)
        view.setIconRadius(if (key == MUSIC_ISLAND) config.getDouble(MiniPlayerConfig.ART_RADIUS).toFloat() else null)
        // The pill's own Lottie, not the plugin's smaller one for its small island: the two
        // files draw at different sizes on their canvases, and in the same box the small island's
        // stopwatch came out smaller than the pill's (2026-09-25).
        val moving = focusLottie(view, note?.anim)
        view.setLive(liveArt(view, note?.live))
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover) else moving ?: note?.icon
        // Another island in a small island that is showing, nothing else moving it (a switch
        // animates its own): it comes up anew in the place rather than just changing its picture.
        if (previous != null && previous != key && showing && swap == null && !islandDragging &&
            held == null && !snapSmallOnce) regrowSmall()
        if (picture !== smallIconKey) {
            smallIconKey = picture
            view.setIcon(when (picture) {
                is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, picture)
                is android.graphics.drawable.Drawable -> picture
                else -> null
            })
        }
    } finally { android.os.Trace.endSection() } }

    private fun hideSmallIsland() {
        clearSmallNudge()
        smallKey = null
        smallGrowing = false
        smallIsland?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    /** A notification just folded back in, not yet given back by the stack: it keeps its place. */
    private val returning = LinkedHashMap<String, Long>()

    /** The notification last folded back in: it stays the small island until the row moves on. */
    private var preferredSmall: String? = null

    /** The next visibility pass shows the small island at once: a flight has just landed there. */
    private var snapSmallOnce = false

    /**
     * The flight has left the small island's place for good - a tap, or a pull let go past
     * the threshold - and the place is the row's again. Until then it is the flight's to come
     * back to.
     */
    private var flightOut = false

    /**
     * The flight is on its last stretch home: the real small island is already in its place
     * under it and the flight fades off it. Swapped only once it had landed, the glass's
     * refraction and the picture both changed on that frame (filmed 2026-09-25).
     */
    private var flightLanding = false

    /**
     * Landing, the small island is the flight's shape frame by frame - through the spring's
     * overshoot and back - and not its own rest circle: the flight fades off an island moving
     * exactly as it does. Put at rest under the flight, the small island stood still while the
     * flight went through the overshoot invisible, and the landing had no give at all, where
     * the pill's has (2026-09-25). On screen; null when not landing.
     */
    private var landingBox: CoverMorphMotion.Box? = null

    /**
     * The small island's pulse, the super island's smallIslandScaleAnimation: when the island out
     * as its card goes back into hiding - no place in the row for it - the small island swells to
     * 1.1 and back, to say the row still holds something (triggerScaleAnimationAfterExpandedHidden,
     * the phone's; a tablet pulses the big island). Each way sinInOut 200ms after 100ms, as
     * FolmeEase.sinInOut(200) with the 100ms delay the plugin gives both halves. Stopped when
     * the small place changes hands.
     */
    private var pulseKey: String? = null
    private var pulseAt = 0L

    private val pulseFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC pulse"); try {
            if (pulseKey == null) return
            smallPulse()
            followShortcuts()
            if (pulseKey != null) Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    private fun startSmallPulse(key: String?) {
        if (key == null) return
        pulseKey = key
        pulseAt = android.os.SystemClock.uptimeMillis()
        Gen.unframe(pulseFrame)
        Gen.frame(pulseFrame)
        MiniPlayerRuntime.noteTouch("pulse small=${key.takeLast(6)}")
    }

    /** The pulse's scale now; 1 with none, and ends it once over or once the small place is another's. */
    private fun smallPulse(): Float {
        val key = pulseKey ?: return 1f
        if (smallKey != key) {
            pulseKey = null
            return 1f
        }
        val t = (android.os.SystemClock.uptimeMillis() - pulseAt).toFloat()
        fun sinInOut(x: Float) = (1f - kotlin.math.cos(Math.PI.toFloat() * x.coerceIn(0f, 1f))) / 2f
        val up = PULSE_DELAY_MS + PULSE_MS
        val hold = up + PULSE_DELAY_MS
        return when {
            t < PULSE_DELAY_MS -> 1f
            t < up -> 1f + (PULSE_SCALE - 1f) * sinInOut((t - PULSE_DELAY_MS) / PULSE_MS)
            t < hold -> PULSE_SCALE
            t < hold + PULSE_MS -> PULSE_SCALE - (PULSE_SCALE - 1f) * sinInOut((t - hold) / PULSE_MS)
            else -> {
                pulseKey = null
                1f
            }
        }
    }

    /** The small island on its rest place, carried by the row's motion as the pill is. */
    private fun placeSmallIsland(follow: Matrix?, fade: Float) {
        val v = smallIsland ?: return
        if (v.visibility != View.VISIBLE) return
        // Its frame is centred on its rest place; a switch only widens the frame, never moves it.
        val fw = if (v.width > 0) v.width else v.layoutParams.width
        val fh = if (v.height > 0) v.height else v.layoutParams.height
        landingBox?.let { box ->
            // The flight's shape, where the flight draws it: the morph places both.
            val xy = IntArray(2).also(host::getLocationOnScreen)
            val lw = maxOf(v.layoutParams.width, fw)
            val lh = maxOf(v.layoutParams.height, fh)
            v.translationX = box.cx() - xy[0] - lw / 2f - v.left
            v.translationY = box.cy() - xy[1] - lh / 2f - v.top
            v.scaleX = 1f
            v.scaleY = 1f
            // Whole: it may have been shrinking away for the flight when the flight came in -
            // left at that alpha (0.08), the landing had nothing under the flight (2026-09-25).
            if (v.alpha != 1f) v.alpha = 1f
            v.setIconAlpha(1f)
            v.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1), 0)
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
            if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
            return
        }
        // The camera pushing on the row takes the small island back first: it is the row's end.
        // A push on it moves it aside.
        val d = discDiameter().toFloat()
        val stacked = stackedStyle()
        // A stacked rear card shares the front card's slot. It is not a second place in the
        // squeeze chain, and follows the front card's pressure instead of reserving space right.
        val offX = if (stacked) squeeze.pillShiftPx() + smallDx + smallNudgeX()
            else -squeeze.rowGivePx(1) + smallDx + smallNudgeX() +
                squeeze.smallShift() * d + squeeze.smallPushPx()
        val offY = smallNudgeY()
        var tx = smallRest[0] - fw / 2f - v.left + offX
        var ty = smallRest[1] - fh / 2f - v.top + offY
        // Flattened by the pill, by its shape - a scale would draw its edge jagged - when nothing
        // else is shaping it this frame.
        if (swap == null && !smallGrowing && !islandDragging && group?.inPlace != true) {
            val shapeX = if (stacked) squeeze.pillScaleX(player?.width?.toFloat() ?: 0f)
                else squeeze.smallShapeX()
            val shapeY = if (stacked) squeeze.pillScaleY() else squeeze.smallShapeY()
            v.setShape((smallRestWidth() * shapeX).roundToInt().coerceAtLeast(1),
                (smallRestHeight() * shapeY).roundToInt().coerceAtLeast(1), 0)
            v.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        }
        // A finger on it sinks it about its centre, which is its frame's centre; the pulse
        // swells it the same way.
        val swell = squeeze.smallSwell() * smallPulse()
        if (kotlin.math.abs(v.scaleX - swell) > 0.0005f) {
            v.scaleX = swell
            v.scaleY = swell
        }
        // At rest on whole pixels, as the pill and the discs are, or its rim is cut into steps.
        if (offX == 0f && offY == 0f && swell == 1f && (follow == null || follow.isIdentity)) {
            tx = kotlin.math.round(tx)
            ty = kotlin.math.round(ty)
            if (v.translationX != tx) v.translationX = tx
            if (v.translationY != ty) v.translationY = ty
        }
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (follow == null || follow.isIdentity) {
            if (lastSmallFollow.any { it != 0f }) {
                lastSmallFollow.fill(0f)
                v.setAnimationMatrix(null)
            }
        } else {
            smallFollow.set(follow)
            smallFollow.preTranslate(v.left.toFloat(), v.top.toFloat())
            smallFollow.postTranslate(-v.left.toFloat(), -v.top.toFloat())
            smallFollow.getValues(smallFollowValues)
            if (!smallFollowValues.contentEquals(lastSmallFollow)) {
                System.arraycopy(smallFollowValues, 0, lastSmallFollow, 0, 9)
                v.setAnimationMatrix(smallFollow)
            }
        }
        if (kotlin.math.abs(v.transitionAlpha - fade) > 0.002f) v.transitionAlpha = fade
    }

    /**
     * The small island under a finger that has not opened or switched anything: it follows,
     * rubber-banded as the pill is, any way round, pressing on the camera and the pill; let go,
     * it springs home. In hundreds of pixels, as the pill's nudge spring runs.
     */
    private val smallNudgeXs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private val smallNudgeYs = Jelly(SMALL_NUDGE_RESPONSE, SMALL_NUDGE_DAMPING)
    private var smallNudging = false
    private var smallNudgeLast = 0L

    private fun smallNudgeX() = smallNudgeXs.value * 100f
    private fun smallNudgeY() = smallNudgeYs.value * 100f

    private val smallNudgeFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC smallNudge"); try {
            if (!smallNudging) return
            val dt = if (smallNudgeLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallNudgeLast) / 1e9f).coerceIn(0f, 0.05f)
            smallNudgeLast = frameTimeNanos
            smallNudgeXs.step(dt)
            smallNudgeYs.step(dt)
            if (smallNudgeXs.atRest() && smallNudgeYs.atRest()) smallNudging = false
            else Gen.frame(this)
            nudgedSmall()
        } finally { android.os.Trace.endSection() } }
    }

    /** Where the finger has pulled the small island to, in pixels; the caller rubber-bands it. */
    fun setSmallNudge(x: Float, y: Float) {
        holdSmallNudge()
        smallNudgeXs.value = x / 100f; smallNudgeXs.target = smallNudgeXs.value; smallNudgeXs.velocity = 0f
        smallNudgeYs.value = y / 100f; smallNudgeYs.target = smallNudgeYs.value; smallNudgeYs.velocity = 0f
        nudgedSmall()
    }

    /** A finger on a small island still springing home: it stays where it is, under the finger. */
    fun holdSmallNudge() {
        smallNudging = false
        Gen.unframe(smallNudgeFrame)
    }

    val smallNudgeNow: Pair<Float, Float> get() = smallNudgeX() to smallNudgeY()

    /** Home with the finger's own speed, so a fling carries past the place and back. */
    fun springSmallNudgeBack(vx: Float, vy: Float) {
        if (smallNudgeXs.value == 0f && smallNudgeYs.value == 0f) return
        smallNudgeXs.target = 0f
        smallNudgeYs.target = 0f
        smallNudgeXs.velocity = vx / 100f
        smallNudgeYs.velocity = vy / 100f
        if (!smallNudging) {
            smallNudging = true
            smallNudgeLast = 0L
            Gen.frame(smallNudgeFrame)
        }
    }

    /**
     * Straight home: the small island is going, or something else is moving it now. Only the
     * springs: the next frame places it. Laying the row out from here brought the discs back
     * on a controller being destroyed, which removes them first.
     */
    private fun clearSmallNudge() {
        holdSmallNudge()
        for (spring in arrayOf(smallNudgeXs, smallNudgeYs)) {
            spring.value = 0f
            spring.target = 0f
            spring.velocity = 0f
        }
    }

    /** The row again, this frame: the squeeze first, then the small island placed by it. */
    private fun nudgedSmall() {
        updateDiscs()
        if (player?.visibility == View.VISIBLE) followShortcuts()
    }

    private fun removeSmallIsland() {
        clearSmallNudge()
        ghostPill?.let { runCatching { host.removeView(it) } }
        ghostPill = null
        ghostDisc?.let { runCatching { host.removeView(it) } }
        ghostDisc = null
        smallIsland?.let { runCatching { host.removeView(it) } }
        smallIsland = null
        smallKey = null
        smallIconKey = null
    }

    // ------------------------------------------------------------ islands in motion

    /**
     * An island switch in flight, as the super island switches (DynamicIslandAnimationDelegate,
     * CHANGE_EASE): the pill grows into its new island out of where that island was - the
     * small island's circle, or the pill's own left end for one coming back out of the stack -
     * while the island it held shrinks under it into the small island's place. Both are frames
     * changing size, never a scale, so the glass stays at its own pixels.
     */
    private class Swap(
        val pillFrom: CoverMorphMotion.Box,
        val pillTo: CoverMorphMotion.Box,
        /**
         * SMALL_FROM_PILL: the small island forms out of the pill's place (it was the pill's).
         * SMALL_POP: another island comes up in the small place. SMALL_EMERGE: the next one
         * slides out from under the pill's end, as the super island's HiddenToSmallIsland.
         * SMALL_KEPT: it is the one that was there, and the switch leaves it alone.
         */
        val smallMode: Int,
        /** SWAP_PAIR: two islands trading places. SWAP_NEXT / SWAP_PREV: a row of three or more. */
        val kind: Int = SWAP_PAIR,
        /** The pill's place is an expanded switch's card's to land in: the switch leaves its frame alone. */
        val holdPill: Boolean = false,
        /**
         * The pill keeps its island and only its width changes: the row's first island alone
         * gaining a small island beside it, or losing it (switchIsland). Its content stays.
         */
        val pillKept: Boolean = false,
    ) {
        /** The pill's frame: CHANGE_EASE out of the small island, APPEAR_EASE out of the middle. */
        val spring = (if (kind == SWAP_PREV && !pillKept) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** The small island's part: CHANGE_EASE forming out of the pill, APPEAR_EASE emerging. */
        val small = (if (smallMode == SMALL_EMERGE) Jelly(APPEAR_RESPONSE, APPEAR_DAMPING)
            else Jelly(SWAP_RESPONSE, SWAP_DAMPING)).apply { value = 0f; target = 1f }
        /** Out of the middle, the content comes into focus on HIDDEN_EASE, 100ms late. */
        val contentIn = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        /** The old big island into the middle: its frame on SHOW_EASE, its content on ALPHA_EASE. */
        val ghostBox = Jelly(SHOW_RESPONSE, SHOW_DAMPING).apply { value = 0f; target = 1f }
        val ghostContent = Jelly(ALPHA_RESPONSE, ALPHA_DAMPING).apply { value = 0f; target = 1f }
        /** The old small island going into hiding where it is, on HIDDEN_EASE. */
        val ghostDisc = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING).apply { value = 0f; target = 1f }
        var ghostFrom: CoverMorphMotion.Box? = null
        var ghost = false
        var disc = false
        /** The stand-in goes into the small island's place (BigIslandToSmallIsland), on [small]. */
        var ghostSmall = false
        /** The island the stand-in is: the one that left the pill for the small place. */
        var ghostKey: String? = null
        /** The stand-in's second button as it set out: 1 if it was the big island alone. */
        var ghostSecond = 0f
        /** The width the old small island's stand-in starts from; 0 is its whole size. */
        var discFromSide = 0f
        var started = 0L
        var last = 0L

        fun step(dt: Float, now: Long) {
            spring.step(dt)
            small.step(dt)
            if (now - started >= CONTENT_IN_DELAY_MS) contentIn.step(dt)
            if (ghost) { ghostBox.step(dt); ghostContent.step(dt) }
            if (disc) ghostDisc.step(dt)
        }

        fun atRest(now: Long) = spring.atRest() && small.atRest() &&
            (kind != SWAP_PREV || pillKept || (now - started >= CONTENT_IN_DELAY_MS && contentIn.atRest())) &&
            (!ghost || (ghostBox.atRest() && ghostContent.atRest())) && (!disc || ghostDisc.atRest())
    }

    private var swap: Swap? = null

    private val swapFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC swap"); try {
            val s = swap ?: return
            if (s.kind == SWAP_STACK && (destroyed || !Main.keyguardLocked() ||
                MiniPlayerScene.aodActive || !stackedStyle() || player?.visibility != View.VISIBLE)) {
                endSwap()
                return
            }
            val dt = if (s.last == 0L) 1f / 120f
                else ((frameTimeNanos - s.last) / 1e9f).coerceIn(0f, 0.05f)
            s.last = frameTimeNanos
            val now = android.os.SystemClock.uptimeMillis()
            s.step(dt, now)
            applySwap(s)
            if (s.atRest(now)) endSwap() else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /** The small island on screen, at rest. */
    private fun smallBoxOnScreen(): CoverMorphMotion.Box {
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val d = smallRestHeight().toFloat()
        val w = smallRestWidth().toFloat()
        return CoverMorphMotion.Box(xy[0] + smallRest[0] - w / 2f,
            xy[1] + smallRest[1] - d / 2f, w, d)
    }

    /**
     * Starts the switch that has just been made - [oldBig] was in the pill, [oldSmall] beside
     * it. Called after the refresh that put the new islands in, so the pill already holds the
     * new one and only its frame is still where the old one was.
     */
    private fun startSwap(oldBig: String?, oldSmall: String?, kind: Int = SWAP_PAIR,
                          intoSmall: Boolean = false,
                          /** One of the two places is an expanded switch's card's to land in (seatAtRest). */
                          holdPill: Boolean = false, holdSmall: Boolean = false,
                          /**
                           * The pill's island stays and goes on from [pillFrom], where a switch
                           * cut short by this one had got it to, to its rest.
                           */
                          pillFrom: CoverMorphMotion.Box? = null, pillStays: Boolean = false,
                          /** The pill's island is the same one: only its width goes (Swap.pillKept). */
                          pillKept: Boolean = false,
                          /** The small island going into hiding starts at this width (0: whole). */
                          smallFromSide: Float = 0f,
                          /** The finger's speed let go, px/s: the pill's frame starts at it. */
                          flingPx: Float = 0f) {
        val view = player ?: return
        // A switch cut short goes on from what it was drawing, at its speed - the super island's
        // folme.to from the current value and velocity. Restarted from rest, the pill lost its
        // speed at every tap mid-switch, and an island shrinking into the small place (its
        // stand-in) jumped to a still circle there, or the pill grew out of that circle.
        val prev = swap
        val prevPillVel = prev?.takeIf { !it.holdPill }?.let { velocityOf(it.pillFrom, it.pillTo, it.spring) }
        val ghosting = prev?.takeIf { it.smallMode == SMALL_FROM_GHOST && it.ghostFrom != null &&
            it.ghostKey != null && !it.small.atRest() }
        val ghostNow = ghosting?.let(::ghostSmallBox)
        val ghostVel = ghosting?.let { velocityOf(it.ghostFrom!!, smallBoxOnScreen(), it.small) }
        // Still the small island's, it keeps its stand-in and its spring; decided below, once the
        // new switch's own stand-ins are known - there is one of each.
        val keepGhost = ghosting != null && smallKey == ghosting.ghostKey && !holdSmall &&
            kind != SWAP_NEXT && !intoSmall && !(kind == SWAP_PREV && oldSmall != null && oldSmall != smallKey)
        endSwap(keepGhosts = keepGhost)
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        if (kind == SWAP_STACK) view.clearNudge()
        // A flight's morph is the flight's: the pill still switches under it.
        if (view.visibility != View.VISIBLE || morph != null && flight == null) {
            if (keepGhost) endGhosts()
            return
        }
        // Where the old big island was, before position() lays the pill out for the new row.
        val oldRest = view.restBoxOnScreen()
        val oldSmallBox = smallBoxOnScreen()
        val oldSmallRest = smallRest.copyOf()
        // The pill's size for the new row, as position() is about to lay it out. position() sets
        // the row's spring going toward it; the switch has the frame, so the spring stops again -
        // left running, it drew over the switch every frame and the pill went straight from its
        // old width to its new one instead of growing out of the small island (filmed 2026-09-25).
        position()
        endRow()
        val rest = view.restBoxOnScreen() ?: return
        val d = discDiameter().toFloat()
        val fromSmall = oldSmall != null && selectedIsland == oldSmall
        // The stand-in shrinking into the small place is the one growing into the pill now:
        // it grows from where it has got to.
        val fromGhost = fromSmall && ghostNow != null && selectedIsland == ghosting?.ghostKey
        val from = when {
            pillStays -> pillFrom ?: rest
            fromGhost -> ghostNow!!
            stackedStyle() -> if (fromSmall) oldSmallBox else rest
            // Out of hiding, the super island's HiddenToBigIsland: out of the middle.
            kind == SWAP_PREV -> cutoutBox(rest)
            fromSmall -> oldSmallBox
            else -> CoverMorphMotion.Box(rest.x, rest.y, d, rest.h)
        }
        val fromVel = when {
            pillStays && pillFrom != null -> prevPillVel
            fromGhost -> ghostVel
            else -> null
        }
        // Growing out of the small island's own circle with nothing to take its place: the circle
        // is the pill from here, not something left behind to shrink away under it.
        if (fromSmall && smallKey == null) setSmallShown(false, animate = false)
        // The pill held for a flight landing on it shows nothing of the island leaving it: that
        // island goes to the small place as a stand-in of itself, content and all, and the
        // small island takes over from it there. Formed out of the pill's place as a bare
        // circle, the island's picture came in only at the end - the music vanished from the
        // pill the moment the row was pulled and came back beside it (filmed 2026-09-25).
        val ghostToSmall = intoSmall && oldBig != null && smallKey == oldBig && oldRest != null
        val mode = when {
            kind == SWAP_STACK -> SMALL_STACK
            keepGhost -> SMALL_FROM_GHOST
            smallKey == null || holdSmall -> SMALL_KEPT
            ghostToSmall -> SMALL_FROM_GHOST
            smallKey == oldBig -> SMALL_FROM_PILL
            smallKey != oldSmall -> if (kind == SWAP_NEXT) SMALL_EMERGE else SMALL_POP
            else -> SMALL_KEPT
        }
        val s = Swap(from, rest, mode, kind, holdPill, pillKept)
        s.started = android.os.SystemClock.uptimeMillis()
        fromVel?.let { s.spring.velocity = springVelocity(it, from, rest) }
        if (kind == SWAP_STACK && flingPx > 0f) {
            s.spring.velocity = (flingPx / rest.h.coerceAtLeast(1f)).coerceAtMost(2f)
        } else if (fromVel == null && flingPx > 0f) {
            // The finger's speed as the frame's: its moving edge goes on at it - growing
            // or narrowing, whichever the switch does, the same way the finger went.
            val dx = rest.cx() - from.cx()
            val dw = rest.w - from.w
            s.spring.velocity = springVelocity(floatArrayOf(
                kotlin.math.sign(dx) * flingPx / 2f, kotlin.math.sign(dw) * flingPx), from, rest)
        }
        if (keepGhost) {
            val g = ghosting!!
            s.ghostFrom = g.ghostFrom
            s.ghostSmall = g.ghostSmall
            s.ghostKey = g.ghostKey
            s.ghostSecond = g.ghostSecond
            s.small.value = g.small.value
            s.small.velocity = g.small.velocity
            if (g.disc) {
                s.disc = true
                s.ghostDisc.value = g.ghostDisc.value
                s.ghostDisc.velocity = g.ghostDisc.velocity
            }
        }
        swap = s
        if (kind == SWAP_STACK && oldBig != null &&
            startGhost(oldBig, pillFrom ?: oldRest ?: rest)) {
            s.ghostFrom = pillFrom ?: oldRest ?: rest
            s.ghost = true
        }
        // The old big island into hiding (BigIslandToHidden): a stand-in of it, under the
        // row, shrinks into the middle while its content goes out of focus.
        if (kind == SWAP_NEXT && !pillKept && !ghostToSmall && oldRest != null && oldBig != null &&
            startGhost(oldBig, oldRest)) {
            s.ghostFrom = oldRest
            s.ghost = true
        }
        if (ghostToSmall && startGhost(oldBig!!, oldRest!!)) {
            s.ghostFrom = oldRest
            s.ghostSmall = true
            s.ghostKey = oldBig
            // Two buttons in the big island: it had them if it was alone there.
            s.ghostSecond = if (oldSmall == null) 1f else 0f
        }
        // The old small island into hiding (SmallIslandToHidden): a stand-in of it, where it is.
        // Also when the island leaving the pill takes the small place from it: with three
        // islands, the small one was simply replaced there, gone in a frame (2026-09-25).
        if ((kind == SWAP_PREV || ghostToSmall) && oldSmall != null && oldSmall != smallKey &&
            startGhostDisc(oldSmall)) {
            s.disc = true
            s.discFromSide = smallFromSide
            if (smallKey == null) {
                // The first island left alone: the pill widens over the place the small island
                // had, which goes into hiding there - its stand-in where it was, the island itself
                // off at once rather than shrinking away a second time under it.
                ghostDisc?.let {
                    it.translationX = oldSmallRest[0] - it.layoutParams.width / 2f - it.left
                    it.translationY = oldSmallRest[1] - it.layoutParams.height / 2f - it.top
                }
                setSmallShown(false, animate = false)
            }
        }
        view.beginMorph(layoutOnly = true)
        if (mode != SMALL_KEPT) {
            // The switch has the small island now: whatever it was growing into, it stops.
            smallGrowing = false
            Gen.unframe(smallGrowFrame)
            smallGrow.value = 1f
            smallGrow.target = 1f
            smallGrow.velocity = 0f
            smallIsland?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
            widenSmallIsland(rest)
        }
        applySwap(s)
        Gen.frame(swapFrame)
    }

    /** A box moving from [a] to [b] on [spring], in pixels a second: its centre's x and its width. */
    private fun velocityOf(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, spring: Jelly): FloatArray =
        floatArrayOf(spring.velocity * (b.cx() - a.cx()), spring.velocity * (b.w - a.w))

    /**
     * [v] (velocityOf) as a switch spring's own speed from [from] to [to]: read off whichever
     * of the two moves further, and kept within what a tap mid-switch could have given it.
     */
    private fun springVelocity(v: FloatArray, from: CoverMorphMotion.Box, to: CoverMorphMotion.Box): Float {
        val dx = to.cx() - from.cx()
        val dw = to.w - from.w
        val span = if (kotlin.math.abs(dw) >= kotlin.math.abs(dx)) dw else dx
        if (kotlin.math.abs(span) < dp(4f)) return 0f
        val px = if (span == dw) v[1] else v[0]
        return (px / span).coerceIn(-SWAP_CARRY_MAX, SWAP_CARRY_MAX)
    }

    /** The camera cutout's stand-in: where an island hides, a small capsule mid-row. */
    private fun cutoutBox(rest: CoverMorphMotion.Box): CoverMorphMotion.Box {
        val side = rest.h * CUTOUT_SHARE
        return CoverMorphMotion.Box(rest.cx() - side / 2f, rest.cy() - side / 2f, side, side)
    }

    private fun swapBlurPx() = dp(SWAP_BLUR_DP).toFloat()

    private fun applySwap(s: Swap) {
        val view = player ?: return
        if (s.kind == SWAP_STACK) {
            applyStackSwap(s, view)
            return
        }
        val p = s.spring.value
        val grown = lerpBox(s.pillFrom, s.pillTo, p)
        // Soft as the row's spring is: thinner while it widens fast, a touch taller coming back
        // off its overshoot (applyRow). The widening speed in pixels a second.
        val widening = s.spring.velocity * (s.pillTo.w - s.pillFrom.w)
        val squash = (widening / s.pillTo.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = grown.h * (1f - squash)
        val box = CoverMorphMotion.Box(grown.x, grown.y + (grown.h - h) / 2f, maxOf(grown.w, h), h)
        if (pillLandingBox != null || s.holdPill) {
            // The pill is under a flight landing on it, or waiting for a card to: that has its frame.
        } else if (s.pillKept) {
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setContentAlpha(1f)
        } else if (s.kind == SWAP_PREV || stackedStyle()) {
            view.setMorphFrame(box, box.h / 2f, 1f)
            // Out of the middle: the content comes into focus as it arrives.
            val c = if (s.kind == SWAP_PREV) s.contentIn.value.coerceIn(0f, 1f)
                else MiniCardMorph.smooth(0f, 1f, p)
            view.setContentAlpha(if (stackedStyle()) 1f else c)
            view.setContentBlur((1f - c) * swapBlurPx())
        } else {
            view.setMorphFrame(box, box.h / 2f, 1f)
            view.setContentAlpha(MiniCardMorph.smooth(0.35f, 0.9f, p))
        }
        applyGhost(s)
        applyGhostDisc(s)
        if (s.smallMode == SMALL_KEPT) return
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE && smallKey != null } ?: return
        val d = discDiameter().toFloat()
        val targetH = smallRestHeight().toFloat()
        val rearAlpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        val targetW = smallRestWidth().toFloat()
        val q = s.small.value
        if (s.smallMode == SMALL_FROM_GHOST) {
            // Under the stand-in, in its very shape, and in its place once it is there - through
            // the overshoot the stand-in has faded out of. Standing as a still circle under it,
            // the small island hid the overshoot and the landing had no give (2026-09-25).
            val box = ghostSmallBox(s)
            if (box != null) {
                val xy = IntArray(2).also(host::getLocationOnScreen)
                small.setShape(box.w.roundToInt().coerceAtLeast(1), box.h.roundToInt().coerceAtLeast(1),
                    (box.cx() - xy[0] - smallRest[0]).roundToInt())
            } else small.setShape(targetW.roundToInt(), targetH.roundToInt(), 0)
            small.setIconAlpha(1f)
            small.alpha = MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q) * rearAlpha
            return
        }
        if (s.smallMode == SMALL_EMERGE) {
            // Out from under the pill's end: from 0.6 of its size, sliding out to its place.
            val width = lerp(targetW * HIDDEN_SCALE, targetW, q).coerceAtLeast(1f).roundToInt()
            val height = lerp(targetH * HIDDEN_SCALE, targetH, q).coerceAtLeast(1f).roundToInt()
            small.setShape(width, height, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.15f, 0.7f, q))
            small.alpha = MiniCardMorph.smooth(0f, 0.12f, q) * rearAlpha
            smallDx = lerp(emergeDx(), 0f, q)
            followShortcuts()
            return
        }
        if (s.smallMode == SMALL_FROM_PILL) {
            val p = q
            // The pill's old island shrinking into the small island's place, under the new one.
            val xy = IntArray(2).also(host::getLocationOnScreen)
            val restCx = s.pillTo.cx() - xy[0]
            val w = lerp(s.pillTo.w, targetW, p).coerceAtLeast(d * 0.5f)
            val cx = lerp(restCx, smallRest[0], p)
            small.setShape(w.roundToInt(),
                lerp(s.pillFrom.h, targetH, p).roundToInt(),
                (cx - smallRest[0]).roundToInt())
            small.setIconAlpha(MiniCardMorph.smooth(0.55f, 1f, p))
            small.alpha = lerp(1f, rearAlpha, p)
        } else {
            // A new small island coming up in its own place.
            val p = q
            val width = lerp(targetW * 0.4f, targetW, p).coerceAtLeast(1f).roundToInt()
            val height = lerp(targetH * 0.4f, targetH, p).coerceAtLeast(1f).roundToInt()
            small.setShape(width, height, 0)
            small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, p))
            small.alpha = MiniCardMorph.smooth(0f, 0.5f, p) * rearAlpha
        }
    }

    /** Both cards follow the same spring; the departing front crosses behind while faded. */
    private fun applyStackSwap(s: Swap, view: MiniPlayerView) {
        val p = s.spring.value
        val q = p.coerceIn(0f, 1f)
        val front = lerpBox(s.pillFrom, s.pillTo, p)
        view.setMorphFrame(front, front.h / 2f, 1f)
        view.setContentAlpha(1f)
        view.setContentBlur(kotlin.math.sin(Math.PI.toFloat() * q) * swapBlurPx())
        view.alpha = lerp(MiniPlayerGeometry.STACK_BACK_ALPHA, 1f, q)
        val rear = smallBoxOnScreen()
        val from = s.ghostFrom
        if (from != null) ghostPill?.let { ghost ->
            val shrunk = lerpBox(from, rear, p)
            val drop = kotlin.math.sin(Math.PI.toFloat() * q) * s.pillTo.h * 0.75f
            val box = CoverMorphMotion.Box(shrunk.x, shrunk.y + drop, shrunk.w, shrunk.h)
            ghost.setMorphFrame(box, box.h / 2f, 1f)
            // Changing draw order with zero opacity avoids a visible jump at the crossing.
            ghost.translationZ = if (q < 0.5f) view.translationZ + dp(1f) else 0f
            ghost.alpha = if (q < 0.5f) 1f - MiniCardMorph.smooth(0.15f, 0.45f, q)
                else MiniPlayerGeometry.STACK_BACK_ALPHA *
                    MiniCardMorph.smooth(0.55f, 0.8f, q) *
                    (1f - MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q))
        }
        smallIsland?.let { small ->
            small.setShape(rear.w.roundToInt(), rear.h.roundToInt(), 0)
            small.setIconAlpha(1f)
            small.alpha = MiniPlayerGeometry.STACK_BACK_ALPHA *
                MiniCardMorph.smooth(GHOST_HANDOFF, 1f, q)
        }
    }

    /**
     * [keepGhosts]: a switch taking over this one's stand-ins where they are (startSwap), so
     * they are not put away for the frame between.
     */
    private fun endSwap(keepGhosts: Boolean = false) {
        val s = swap ?: return
        swap = null
        Gen.unframe(swapFrame)
        player?.let {
            // A card morph that has taken the pill since keeps its frame: handed back to rest
            // under it, the pill stood bare in its place with the morph still running - its
            // content cleared by the morph, its glass whole (2026-09-25, `op mini` scene trace).
            // One landing on it has its frame, and hands it back itself (endPillLanding).
            if ((morph == null || flight != null) && pillLandingBox == null) it.endMorph()
            it.setContentAlpha(1f)
            it.setContentBlur(0f)
            if (s.kind == SWAP_STACK) it.alpha = 1f
        }
        if (!keepGhosts) endGhosts()
        if (smallDx != 0f) {
            smallDx = 0f
            followShortcuts()
        }
        // A small island the switch left alone may be mid-grow on its own spring: not cut short.
        if (s.smallMode != SMALL_KEPT) restoreSmallIslandShape()
        schedulePosition()
        pendingExpand?.let { key ->
            pendingExpand = null
            if (selectedIsland == key) expandNote(key)
        }
    }

    // ---- the islands going into hiding

    /** A stand-in for the big island a switch sends into hiding: a pill of its own, under the row. */
    private var ghostPill: MiniPlayerView? = null

    /** A stand-in for the small island a switch sends into hiding. */
    private var ghostDisc: ShortcutDisc? = null

    /** The small island's offset from its place while it slides out from under the pill. */
    private var smallDx = 0f

    /** Where an emerging small island starts: its centre under the pill's end. */
    private fun emergeDx(): Float {
        val d = discDiameter().toFloat()
        return -(d * 0.8f + dp(MiniPlayerGeometry.DISC_GAP_DP))
    }

    /** Lowest in the row: under the small island, which is under the pill. */
    private fun ghostIndex(): Int {
        val small = smallIsland?.let { host.indexOfChild(it) }?.takeIf { it >= 0 }
        val pill = player?.let { host.indexOfChild(it) }?.takeIf { it >= 0 }
        return small ?: pill ?: lockScreenLayerIndex()
    }

    private fun startGhost(key: String, from: CoverMorphMotion.Box): Boolean {
        val view = ghostPill ?: MiniPlayerView(context).also {
            ghostPill = it
            host.addView(it, ghostIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(host, it)
        }
        val w = from.w.roundToInt().coerceAtLeast(1)
        val h = from.h.roundToInt().coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        val music = controller?.takeIf(::isUsable)
        val note = if (key == MUSIC_ISLAND) null
            else LockIslands.notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        when {
            key == MUSIC_ISLAND && music != null -> bindMusic(view, music, config)
            note != null -> bindNote(view, note, config)
            else -> return false
        }
        view.setInteractionsEnabled(false)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        // Laid out now, as the flight is: new, it showed an empty frame until the next pass.
        if (!view.isLaidOut || view.width != w || view.height != h) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        view.beginMorph(layoutOnly = true)
        // Under the small island and the pill: the super island's hidden layer is the lowest.
        view.translationZ = 0f
        view.setMorphFrame(from, from.h / 2f, 1f)
        return true
    }

    /**
     * The island leaving the pill for the small place, this frame: CHANGE_EASE carries it past
     * the circle and back, and it squeezes with its speed as the pill widening does (applySwap)
     * - a touch taller while it narrows fast.
     */
    private fun ghostSmallBox(s: Swap): CoverMorphMotion.Box? {
        val from = s.ghostFrom ?: return null
        val to = smallBoxOnScreen()
        val g = s.small.value
        val shrunk = lerpBox(from, to, g)
        val narrowing = s.small.velocity * (to.w - from.w)
        val squash = (narrowing / from.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = shrunk.h * (1f - squash)
        return CoverMorphMotion.Box(shrunk.x, shrunk.y + (shrunk.h - h) / 2f, shrunk.w, h)
    }

    private fun applyGhost(s: Swap) {
        if (s.ghostSmall) {
            // BigIslandToSmallIsland, on CHANGE_EASE: the island's own pill shrinks into the
            // small island's circle, its lines going first and its picture staying.
            val view = ghostPill ?: return
            val g = s.small.value
            val box = ghostSmallBox(s) ?: return
            view.setMorphFrame(box, box.h / 2f, 1f)
            // Its second button, there while it was the big island, goes as it shrinks - with
            // the text making room back, the middle island's way (MiniPlayerView.applySecond).
            // Made without it, the stand-in dropped the button the moment the switch began: the
            // stopwatch's left button gone in a frame as the cover was pulled down (2026-09-26).
            view.setSecondProgress(s.ghostSecond * (1f - MiniCardMorph.smooth(0f, 0.5f, g)))
            view.setTextAlpha(if (stackedStyle()) 1f else 1f - MiniCardMorph.smooth(0f, 0.5f, g))
            if (stackedStyle()) view.setContentBlur(kotlin.math.sin(Math.PI.toFloat() * g.coerceIn(0f, 1f)) * swapBlurPx())
            view.alpha = 1f - MiniCardMorph.smooth(GHOST_HANDOFF, 1f, g)
            return
        }
        if (!s.ghost) return
        val view = ghostPill ?: return
        val from = s.ghostFrom ?: return
        val g = s.ghostBox.value
        val box = lerpBox(from, cutoutBox(s.pillTo), g)
        view.setMorphFrame(box, box.h / 2f, 1f - MiniCardMorph.smooth(0.35f, 0.9f, g))
        val a = s.ghostContent.value.coerceIn(0f, 1f)
        view.setContentAlpha(1f - a)
        view.setContentBlur(a * swapBlurPx())
    }

    private fun startGhostDisc(key: String): Boolean {
        val d = discDiameter()
        val width = smallRestWidth()
        val frame = maxOf(discFrame(d), width + dp(16f))
        val frameHeight = discFrame(d)
        val disc = ghostDisc ?: ShortcutDisc(context).also {
            ghostDisc = it
            host.addView(it, ghostIndex(), ViewGroup.LayoutParams(frame, frameHeight))
            Gen.ownView(host, it)
        }
        if (disc.layoutParams.width != frame || disc.layoutParams.height != frameHeight) {
            disc.layoutParams = disc.layoutParams.apply { this.width = frame; height = frameHeight }
        }
        disc.dress(discMaterialGeneration()) {
            MiniPlayerRuntime.material(it, loader, notificationMaterial())
        }
        val picture: Any? = if (key == MUSIC_ISLAND) (thumbShown ?: cachedCover)
            else LockIslands.notes.firstOrNull { it.key == key }?.icon ?: LockIslands.noteFor(key)?.icon
        if (stackedStyle()) disc.copyCardTextFrom(smallIsland)
        else disc.setCardText(null, null)
        disc.setIconBare(key != MUSIC_ISLAND)
        disc.setIconRadius(if (key == MUSIC_ISLAND) config.getDouble(MiniPlayerConfig.ART_RADIUS).toFloat() else null)
        disc.setIcon(when (picture) {
            is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, picture)
            is android.graphics.drawable.Drawable -> picture
            else -> null
        })
        disc.setIconHidden(false)
        disc.setShape(width, smallRestHeight())
        disc.setIconAlpha(1f)
        disc.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        disc.translationX = smallRest[0] - frame / 2f - disc.left
        disc.translationY = smallRest[1] - frameHeight / 2f - disc.top
        disc.visibility = View.VISIBLE
        return true
    }

    private fun applyGhostDisc(s: Swap) {
        if (!s.disc) return
        val disc = ghostDisc ?: return
        val d = smallRestHeight().toFloat()
        val w = smallRestWidth().toFloat()
        val h = s.ghostDisc.value.coerceIn(0f, 1f)
        val start = if (s.discFromSide > 0f) s.discFromSide else w
        val width = lerp(start, w * HIDDEN_SCALE, h).coerceAtLeast(1f).roundToInt()
        val height = lerp(d, d * HIDDEN_SCALE, h).coerceAtLeast(1f).roundToInt()
        disc.setShape(width, height)
        disc.alpha = (1f - h) *
            if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    private fun endGhosts() {
        ghostPill?.let {
            it.endMorph()
            it.setContentBlur(0f)
            it.setContentAlpha(1f)
            it.alpha = 1f
            it.translationZ = 0f
            it.visibility = View.GONE
        }
        ghostDisc?.visibility = View.GONE
    }

    /** Room in the small island's frame for a shape as wide as the pill, where the pill is. */
    private fun widenSmallIsland(rest: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        val xy = IntArray(2).also(host::getLocationOnScreen)
        val reach = kotlin.math.abs(rest.cx() - xy[0] - smallRest[0]) + rest.w / 2f
        val wide = maxOf(discFrame(discDiameter()), (reach * 2f + dp(16f)).roundToInt())
        resizeSmallIsland(small, wide, small.layoutParams.height)
        smallWide = true
    }

    /**
     * The small island's frame to a new size now, about the centre it is drawn at. Set only in
     * its layout params, the size took the next layout pass, and for the frame before it the
     * island was placed by one size and drawn at the other - a jump sideways as it grew for a
     * flight or went back to its circle. From TakeKazeX's PR #15.
     */
    private fun resizeSmallIsland(small: ShortcutDisc, width: Int, height: Int) {
        if (small.layoutParams.width != width || small.layoutParams.height != height) {
            small.layoutParams = small.layoutParams.apply { this.width = width; this.height = height }
        }
        if (small.width == width && small.height == height) return
        val cx = small.x + small.width / 2f
        val cy = small.y + small.height / 2f
        small.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        small.layout(small.left, small.top, small.left + width, small.top + height)
        small.translationX = cx - small.left - width / 2f
        small.translationY = cy - small.top - height / 2f
    }

    private var smallWide = false

    private fun restoreSmallIslandShape() {
        val small = smallIsland ?: return
        val d = discDiameter()
        val frame = maxOf(discFrame(d), smallRestWidth() + dp(16f))
        val frameHeight = discFrame(d)
        if (smallWide || small.layoutParams.width != frame || small.layoutParams.height != frameHeight)
            resizeSmallIsland(small, frame, frameHeight)
        smallWide = false
        small.setShape(smallRestWidth(), smallRestHeight(), 0)
        small.setIconAlpha(1f)
        small.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    private fun lerpBox(a: CoverMorphMotion.Box, b: CoverMorphMotion.Box, t: Float) =
        CoverMorphMotion.Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t),
            maxOf(1f, lerp(a.w, b.w, t)), maxOf(1f, lerp(a.h, b.h, t)))

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- the row's layout, animated

    /**
     * The pill's place in the row, sprung: every change of the row's layout - a small island
     * coming or going, the pill giving room to a notification folding back in, taking it back
     * when one leaves - runs on the super island's CHANGE_EASE rather than jumping, as its
     * container clip does for each of those transitions. position() sets the target; the pill's
     * own layout is already at the target size, and the frame is drawn at the spring's.
     */
    private val rowLeft = Jelly(SWAP_RESPONSE, ROW_DAMPING)
    private val rowWidth = Jelly(SWAP_RESPONSE, ROW_DAMPING)
    private var rowKnown = false
    private var rowAnimating = false
    private var rowLast = 0L

    private val rowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC row"); try {
            if (!rowAnimating) return
            val dt = if (rowLast == 0L) 1f / 120f
                else ((frameTimeNanos - rowLast) / 1e9f).coerceIn(0f, 0.05f)
            rowLast = frameTimeNanos
            rowLeft.step(dt)
            rowWidth.step(dt)
            // Under the media card's morph the morph draws the pill; under a switch the switch does.
            if (group == null && swap == null) applyRow()
            if (rowLeft.atRest() && rowWidth.atRest()) endRow()
            else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /** position(): the pill's rest place in the host is now [left] and [width]. */
    private fun rowTarget(left: Float, width: Float) {
        val view = player
        // A flight's morph moves the flight, not the pill: the pill still gives room on its
        // spring. Counted busy, it jumped to its new width the moment a flight set out.
        // A media card's morph lands on the row as it is laid out, so the row is put there at once.
        val pillMorphing = morph != null && flight == null || group != null
        val busy = swap != null || pillMorphing || view == null || view.visibility != View.VISIBLE
        if (!rowKnown || busy) {
            // Nothing drawn to move from, or another motion owns the frame: straight there.
            rowLeft.value = left; rowLeft.target = left; rowLeft.velocity = 0f
            rowWidth.value = width; rowWidth.target = width; rowWidth.velocity = 0f
            rowKnown = view != null && view.visibility == View.VISIBLE
            if (rowAnimating) endRow()
            return
        }
        if (kotlin.math.abs(rowLeft.target - left) < 0.5f && kotlin.math.abs(rowWidth.target - width) < 0.5f) return
        rowLeft.target = left
        rowWidth.target = width
        if (!rowAnimating) {
            rowAnimating = true
            rowLast = 0L
            applyRow()
            Gen.frame(rowFrame)
        }
    }

    // ---- the row coming up out of nothing, and going back into it

    /**
     * The pill coming up where there was no row at all - the first island on a quiet lock screen,
     * the torch's own focus notification after a double tap on it. It used to be switched on
     * whole in one frame. It grows as an island does coming back into the row beside the media
     * card (startSwap's own-left-end case, the user's pick): out of a circle at its left end,
     * rightward, on CHANGE_EASE, thinned while it widens fast, the content coming in over it.
     * The row's spring, if the row moves meanwhile, is carried inside it.
     *
     * The last island going - the torch switched off - it goes back the same way, right to left
     * into that circle, which fades as it gets there; only then is it GONE. It used to go in one
     * frame too. [appear]'s target says which way; one coming in mid-way turns it round from
     * where it has got to.
     */
    // A spring of its own, bouncier than the super island's APPEAR (0.7 overshoots ~5%, which
    // the first build showed as no bounce at all, 2026-10-14): the rise carries well past its
    // place and settles back - the landing the squash in applyAppear answers.
    private val appear = Jelly(ROW_POP_RESPONSE, ROW_POP_DAMPING)
    private var appearing = false
    private var appearLast = 0L

    /** Since when a pill whose island has gone has been waiting to be on screen to go back (updateVisibility). */
    private var backWaitSince = 0L

    /**
     * The row the pill last stood for on screen; empty once its last island has gone. A pill
     * shown again with this empty is a new row, and grows in even when the screen is not yet
     * back to show it (appearFrame waits); one only hidden a while - the doze, a double press
     * of the power key, the cover - keeps its row and comes back on the lock screen's own fade.
     */
    private var lastShownKeys: List<String> = emptyList()
    private var growHeldSince = 0L

    /** The clock going into the cover or out of it: the row is put away or brought back by that, not by its islands. */
    private fun coverMoving(): Boolean = Main.coverSceneActive() && ClockCollapse.phase().let {
        // The cover's clock only: 高德's map moves the same clock and puts nothing away.
        it == ClockCollapse.Phase.ENTER || it == ClockCollapse.Phase.EXIT
    }

    /** The pill is on screen enough to be seen changing: lit or held into the AOD, the keyguard staying. */
    private fun pillSeen(): Boolean = !MiniPlayerScene.keyguardGoingAway && followPillFade > 0.5f &&
        (Main.screenOnCached() || MiniPlayerScene.aodActive)

    /** The way back as it was drawn, for its gone line: frames, frames the pill was in its morph, narrowest, start. */
    private var backFrames = 0
    private var backDrawn = 0
    private var backMinW = Float.NaN
    private var backStartedAt = 0L

    private val appearFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC appear"); try {
            if (!appearing) return
            val dt = if (appearLast == 0L) 1f / 120f
                else ((frameTimeNanos - appearLast) / 1e9f).coerceIn(0f, 0.05f)
            appearLast = frameTimeNanos
            // A new row come up while the screen is still coming back: it waits out of sight
            // and rises once it can be seen doing so.
            val held = appear.target == 1f && appear.value == 0f && !pillSeen() &&
                android.os.SystemClock.uptimeMillis() - growHeldSince < BACK_WAIT_MS
            if (!held) appear.step(dt)
            val applied = applyAppear()
            if (!applied && leaving()) MiniPlayerRuntime.noteGone("back cut at ${"%.2f".format(appear.value)}: ${quietWhy()}")
            if (!applied || appear.atRest()) endAppear()
            else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /** Nothing else has the pill's frame or is moving the row: a coming-up of its own is right. */
    private fun rowQuiet(): Boolean = !frameTaken() &&
        !rowHeldOff && followRowFade >= 0.99f && !MiniPlayerScene.aodActive

    /** What keeps the row from being quiet, for `op mini`'s gone lines; "quiet" when nothing does. */
    private fun quietWhy(): String = buildList {
        if (swap != null) add("swap")
        if (morph != null) add("morph")
        if (group != null) add("group")
        if (flight != null) add("flight")
        if (exchange != null) add("exchange")
        noteMorphKey?.let { add("noteMorph=${it.takeLast(6)}") }
        if (pillLandingBox != null) add("landing")
        if (rowHeldOff) add("heldOff")
        if (followRowFade < 0.99f) add("fade=${"%.2f".format(followRowFade)}")
        if (MiniPlayerScene.aodActive) add("aod")
        if (player?.visibility != View.VISIBLE) add("vis=${player?.visibility}")
    }.joinToString(",").ifEmpty { "quiet" }

    /** Another motion is drawing the pill's frame, and ends its own morph when it is done. */
    private fun frameTaken(): Boolean = swap != null || morph != null || group != null || flight != null ||
        exchange != null || noteMorphKey != null || pillLandingBox != null || spread != null

    /** Going back into nothing: set to GONE once it is there (endAppear). */
    private fun leaving(): Boolean = appearing && appear.target == 0f

    private fun startAppear() {
        val view = player ?: return
        if (appearing) {
            // Turned round on its way out: back up from where it is, at the speed it had.
            appear.target = 1f
            return
        }
        appear.value = 0f
        appear.velocity = 0f
        appear.target = 1f
        backFrames = 0
        backDrawn = 0
        backMinW = Float.NaN
        backStartedAt = android.os.SystemClock.uptimeMillis()
        growHeldSince = backStartedAt
        view.beginMorph(layoutOnly = true)
        appearing = true
        appearLast = 0L
        // The row's spring still holds wherever the pill was last shown: set afresh by the next
        // position(), not sprung from there.
        rowKnown = false
        // The first frame drawn is already the start of the rise, not the whole pill for one frame.
        if (!applyAppear()) view.setContentAlpha(0f)
        Gen.unframe(appearFrame)
        Gen.frame(appearFrame)
    }

    /**
     * The pill's last notification gone, and what is left for the row is nothing it would show:
     * no island, or only the music out as its media card. The pill goes back into its circle
     * with the notification it had. Bound to the music and switched first, the way it would
     * be for a row the music stays in, it put the music in the pill, grew it from its end, and
     * then the row went at once - on a lock screen with a song on its card, the torch's island
     * switched off vanished in a frame (2026-09-29).
     */
    private fun rowGoing(music: MediaController?): Boolean {
        val shown = selectedIsland ?: return false
        if (shown == MUSIC_ISLAND || shown in islandKeys || islandKeys.any { it != MUSIC_ISLAND }) return false
        if (player?.visibility != View.VISIBLE) return false
        return islandKeys.isEmpty() || musicCarded(music)
    }

    /**
     * The music is out of the row as its card: the media card, or the cover - refresh's
     * `carded`. The cover was left out here, so with a song on the cover the torch's island
     * switched off handed the pill to the music, which the cover hides at once: every one went
     * in a frame (2026-09-29, `op mini`: CUT sel=music cover=true after each).
     */
    private fun musicCarded(music: MediaController?): Boolean =
        music != null && (MiniPlayerRuntime.nativeRequested(music.sessionToken) || Main.coverModeOn())

    private fun startDisappear() {
        val view = player ?: return
        if (leaving()) return
        if (!appearing) {
            appear.value = 1f
            appear.velocity = 0f
            view.beginMorph(layoutOnly = true)
            appearing = true
            appearLast = 0L
            rowKnown = false
            Gen.unframe(appearFrame)
            Gen.frame(appearFrame)
        }
        appear.target = 0f
        backFrames = 0
        backDrawn = 0
        backMinW = Float.NaN
        backStartedAt = android.os.SystemClock.uptimeMillis()
        view.setInteractionsEnabled(false)
    }

    /**
     * The torch's and the camera's icons pushed out by [push] pixels and pressed thin, each from
     * its outer edge. The icon inside each button, never the button's own layout: the pill
     * follows where those layouts are drawn (followShortcuts), and moving them would read to it
     * as the row zooming - it would grow with the push. 0 puts both back.
     */
    private fun squeezeShortcuts(push: Float) {
        fun one(layout: View, outward: Float) {
            val icon = (layout as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0) ?: return
            if (push < 0.25f) {
                if (icon.translationX != 0f || icon.scaleX != 1f) {
                    icon.translationX = 0f
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                }
                return
            }
            val k = push / icon.width.coerceAtLeast(1)
            icon.pivotX = if (outward < 0f) 0f else icon.width.toFloat()
            icon.pivotY = icon.height / 2f
            icon.translationX = outward * push
            icon.scaleX = (1f - k * 0.5f).coerceAtLeast(0.7f)
            icon.scaleY = (1f + k * 0.25f).coerceAtMost(1.15f)
        }
        one(left, -1f)
        one(right, 1f)
    }

    /** One frame of it; false when another motion has taken the frame (it is theirs from here). */
    private fun applyAppear(): Boolean {
        val view = player ?: return false
        // Cut only by a motion taking the frame. The doze, the wake's fade and the row held into
        // the AOD only change how much of it is seen; cut by them, the pill went in a frame.
        if (frameTaken() || view.visibility != View.VISIBLE) return false
        val rest = view.restBoxOnScreen() ?: return true
        val xy = IntArray(2).also(host::getLocationOnScreen)
        // The row as its own spring has it, grown from its left end.
        val fullW = (if (rowKnown) rowWidth.value else rest.w).coerceAtLeast(rest.h)
        val left = if (rowKnown) xy[0] + rowLeft.value else rest.x
        val p = appear.value
        backFrames++
        // setMorphFrame does nothing to a pill out of its morph: the way is then not drawn.
        if (view.inMorph()) backDrawn++
        if (stackedStyle()) {
            val glass = p.coerceIn(0f, 1f)
            if (backMinW.isNaN() || fullW < backMinW) backMinW = fullW
            view.setMorphFrame(CoverMorphMotion.Box(left, rest.y, fullW, rest.h), rest.h / 2f, glass)
            view.setContentAlpha(1f)
            return true
        }
        // Up from below, whole (2026-10-14, user): it rose out of its left end's circle before,
        // and from the middle before that - both turned down. The pill comes up from under its
        // place a little small, the spring carrying it just past and back; rising fast it is
        // drawn a touch tall and narrow, and settling back off the overshoot a touch flat and
        // wide - the landing. Going, the same in reverse: it sinks away.
        val d = density()
        val shown = p.coerceAtLeast(0f)
        val rise = (1f - p) * APPEAR_RISE_DP * d
        // Not held at 1: past its place it is a touch bigger too, which is most of the bounce.
        // The height takes all of it; the width far less - from nearer its own size, and only a
        // share of the overshoot (2026-10-14, user: it spread sideways too much).
        val scale = lerp(APPEAR_SCALE_FROM, 1f, shown)
        val across = if (shown <= 1f) shown else 1f + (shown - 1f) * APPEAR_WIDTH_OVERSHOOT
        val widthScale = lerp(APPEAR_WIDTH_FROM, 1f, across)
        val v = appear.velocity
        val stretch = (if (v > 0f) v * APPEAR_STRETCH else v * APPEAR_LAND)
            .coerceIn(-APPEAR_SQUASH_MAX, APPEAR_SQUASH_MAX)
        val h = (rest.h * scale * (1f + stretch)).coerceAtLeast(1f)
        val w = (fullW * widthScale * (1f - stretch * 0.25f)).coerceAtLeast(h)
        val cx = left + fullW / 2f
        val cy = rest.y + rest.h / 2f + rise
        val glass = MiniCardMorph.smooth(0f, 0.3f, shown)
        if (backMinW.isNaN() || w < backMinW) backMinW = w
        view.setMorphFrame(CoverMorphMotion.Box(cx - w / 2f, cy - h / 2f, w, h), h / 2f, glass)
        // Wider than its place - the overshoot and the landing - it shoulders the torch and the
        // camera aside, and they give: pushed out and pressed thin against it.
        squeezeShortcuts(((w - fullW) / 2f).coerceAtLeast(0f) * APPEAR_PUSH_GAIN)
        // Out of focus to in, the glass left sharp, as the super island's content arrives.
        view.setMorphContentBlur((1f - MiniCardMorph.smooth(0f, 0.5f, shown)) * APPEAR_BLUR_DP * d)
        // The content in two beats: the picture first, popping in on its centre, the text a
        // little after it, sliding in from the left. Early, both: the whole of it is in by the
        // time the spring first reaches its place, not waiting on the bounce to settle.
        val icon = MiniCardMorph.smooth(0.05f, 0.4f, shown)
        val text = MiniCardMorph.smooth(0.2f, 0.65f, shown)
        view.setAppearStagger(
            iconScale = lerp(APPEAR_ICON_FROM, 1f, icon),
            iconAlpha = icon,
            textShift = -(1f - text) * APPEAR_TEXT_SHIFT_DP * d,
            textAlpha = text,
        )
        return true
    }

    private fun endAppear() {
        if (!appearing) return
        val left = appear.target == 0f
        MiniPlayerRuntime.noteGone((if (left) "back" else "up") + " done ${android.os.SystemClock.uptimeMillis() - backStartedAt}ms " +
            "frames=$backFrames drawn=$backDrawn minW=${if (backMinW.isNaN()) "-" else backMinW.toInt().toString()} " +
            "at=${"%.2f".format(appear.value)} row=${quietWhy()} pill=${player?.let { "${it.visibility}/t${"%.2f".format(it.transitionAlpha)}/a${"%.2f".format(it.alpha)}" }}")
        appearing = false
        Gen.unframe(appearFrame)
        squeezeShortcuts(0f)
        val view = player ?: return
        view.clearAppearStagger()
        view.setMorphContentBlur(0f)
        view.setContentAlpha(1f)
        if (left) {
            // Gone back into nothing (or cut short there): the row is not there any more. The
            // pill still holds the notification it went with; the refresh puts the row as it now is.
            lastShownKeys = emptyList()
            view.endMorph()
            view.visibility = View.GONE
            scheduleRefresh()
            return
        }
        // Handed on: the row's spring, still running, draws the frame and ends the morph itself;
        // so does any other motion that has taken the frame. Cut short by anything else - the
        // doze, the wake's fade, the row held off - nobody draws it again, and asked whether the
        // row was quiet the pill was left as that frame had it: a circle's width with the title
        // laid out past its end (screenshot 2026-09-29 20:20). It goes to its whole width.
        if (rowAnimating && group == null && swap == null) applyRow()
        else if (!frameTaken() && !rowAnimating) view.endMorph()
    }

    private fun applyRow() {
        val view = player ?: return
        val rest = view.restBoxOnScreen() ?: return
        if (pillLandingBox != null) return
        // Coming up out of nothing: that frame carries the row's spring (applyAppear).
        if (appearing) return
        // Also after a media card's morph has handed the pill back mid-spring.
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        val xy = IntArray(2).also(host::getLocationOnScreen)
        // Soft, as the pill is everywhere else: widening fast it thins, and coming back off its
        // overshoot it thickens - a squeeze that follows the width's own speed. On its spring
        // alone the pill slid to its new width like a rigid bar (2026-09-25).
        val squash = (rowWidth.velocity / rest.w.coerceAtLeast(1f) * ROW_SQUASH)
            .coerceIn(-ROW_SQUASH_MAX, ROW_SQUASH_MAX)
        val h = rest.h * (1f - squash)
        val box = CoverMorphMotion.Box(xy[0] + rowLeft.value, rest.y + (rest.h - h) / 2f,
            rowWidth.value.coerceAtLeast(h), h)
        view.setMorphFrame(box, box.h / 2f, 1f)
    }

    /** The row's spring hands the frame back: at rest, or to another motion taking it. */
    private fun endRow() {
        if (!rowAnimating) return
        rowAnimating = false
        Gen.unframe(rowFrame)
        rowLeft.value = rowLeft.target; rowLeft.velocity = 0f
        rowWidth.value = rowWidth.target; rowWidth.velocity = 0f
        // The media card's morph owns the pill's frame: not the row's to hand back. Nor is a
        // coming-up still growing.
        if (group == null && !appearing) player?.endMorph()
    }

    // ---- the row and the lock screen's cards, into each other together

    /**
     * The media card's morph with the row: the card and the notification rows that are the
     * row's islands change into those islands together, each into its own place, and back.
     * Pulled down, the card becomes the music's island - the pill, or the small island - while
     * the notification that is the row's other island comes down out of its row into its place
     * at the same time; pulled up, the reverse. One spring moves them all, the music's: the
     * other morph follows its progress frame by frame (MiniCardMorph.follow), so the two differ
     * only by their ends - a small island's circle, a card's size. The islands with no place
     * in the row (a third one on) have no island to become: their rows go as the stack folds
     * its notifications away (foldRows), and come back as it unfolds them.
     *
     * The first version laid the row out as the pill alone for the morph: the card landed as
     * the whole pill, and only then did the small island come out from under its end and
     * narrow it, while the rows went into their islands on the stack's own animation - one
     * motion after another, where each was to go home at once (2026-09-25).
     */
    private class Group(val musicSmall: Boolean, val down: Boolean) {
        /** The view drawing the small island's place through the morph: the music's, or [other]'s. */
        var smallView: MiniPlayerView? = null
        /** The notification in the row's other place, when there is one. */
        var other: String? = null
        /** ...and that place is the small island's. */
        var otherSmall = false
        /** [other]'s morph with its row, once the row is there. */
        var follower: MiniCardMorph? = null
        /** It set out after the music's, from [joinFrom], and comes up to it over GROUP_JOIN_MS. */
        var joinedAt = 0L
        var joinFrom = 0f
        /** Since when [other]'s row has been waited for. */
        var waitSince = 0L
        /** [other] has no row to go to or come from: its island comes and goes in its place. */
        var inPlace = false
        /** The real small island is under the view landing on it, taking over from it. */
        var smallLanding = false

        /**
         * The rows with no place in the row, as the stack's own fold moves them: down with the
         * finger (FOLD_PULL_RANGE_DP, the stack's friction), back up on the fold's release
         * spring, and out or in on its alpha spring (NormalNotificationsVisibleAnimatorImpl:
         * FolmeEase -2, 1.0, 0.2) once the lift has decided.
         */
        val foldDy = Jelly(FOLD_BACK_RESPONSE, HIDDEN_DAMPING)
        val foldAlpha = Jelly(HIDDEN_RESPONSE, HIDDEN_DAMPING)
        /** How far into the pile they are, 0 laid out to 1 piled (stackRows). */
        val foldK = Jelly(FOLD_BACK_RESPONSE, HIDDEN_DAMPING)
        var foldLast = 0L
        /** Where the card stood when the finger took it, on screen: what the pull is measured from. */
        var cardTop = Float.NaN
        /** Rows moved by the fold, to be given their own place back. */
        val folded = java.util.WeakHashMap<View, Boolean>()
    }

    private var group: Group? = null

    /**
     * A finger is pulling the music up and has not reached the threshold yet: the stack is
     * given the rows now, out of sight, so they are laid out by the time the morph wants them.
     */
    private var rowsAnticipated = false

    /** The stack may still show the rows for a moment after it was asked to leave them out. */
    private var rowsHeldUntil = 0L

    /**
     * The row's islands are the stack's rows for now: the stack is not to leave them out. Never
     * any more: with one island out as its card the others stay in the row, the music's morph
     * into its card or the cover included, and their rows stay out of the stack.
     */
    private fun rowsHeld(): Boolean = false

    /** A scene's exit is bringing the music down into the row: it has its place there again. */
    private var musicComingDown = false

    /** The morph drawing the pill: the media card's, or in a group whichever has the pill. */
    private fun pillMorph(): MiniCardMorph? {
        val g = group ?: return morph?.takeIf { flight == null }
        return if (g.musicSmall) g.follower?.takeIf { !g.otherSmall } else morph
    }

    /** Whether the small island shows while a group runs; null with no group running. */
    private fun groupShowsSmall(): Boolean? {
        val g = group ?: return null
        val drawnByView = g.musicSmall || g.otherSmall && g.follower != null
        return if (drawnByView) g.smallLanding else g.otherSmall
    }

    /** The small island [selected] would have beside it, as updateSmallIsland picks it. */
    private fun smallAfter(keys: List<String>, selected: String): String? {
        if (keys.size < 2) return null
        preferredSmall?.takeIf { it in keys && it != selected }?.let { return it }
        if (stackedStyle()) return MiniPlayerStackGesture.nextKey(keys, selected)
        if (firstAlone(keys, selected)) return null
        return keys.getOrNull(keys.indexOf(selected).coerceAtLeast(0) + 1)
    }

    /**
     * Sets the row up for the media card's morph and makes the music's morph, not started yet:
     * [toNative] the music goes up into the card, else the card comes down into the row.
     */
    /** Why prepareGroup gave none, read after it: the parts of its state that refuse one. */
    private fun groupRefusal(toNative: Boolean): String =
        "usable=${controller?.let(::isUsable)} note=${noteMorphKey != null} flight=${flight != null} " +
            "xchg=${exchange != null} sel=${selectedIsland?.takeLast(6)} small=${smallKey?.takeLast(6)} " +
            "keys=${islandKeys.joinToString(",") { it.takeLast(6) }} toNative=$toNative"

    private fun prepareGroup(native: View, toNative: Boolean): MiniCardMorph? {
        musicComingDown = !toNative
        try {
            return prepareGroupUnsafe(native, toNative)
        } finally {
            musicComingDown = false
        }
    }

    private fun prepareGroupUnsafe(native: View, toNative: Boolean): MiniCardMorph? {
        val view = player ?: return null
        if (controller?.takeIf(::isUsable) == null || noteMorphKey != null || flight != null ||
            exchange != null) return null
        if (toNative && selectedIsland != MUSIC_ISLAND && smallKey != MUSIC_ISLAND) return null
        endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        if (!toNative) {
            // Down: the music goes back where it was - the pill, or the small island - unless
            // that is out of sight now (the row moved on while the card was up), or the pill
            // would hold a notification with no row on screen to come down out of.
            // Out as its card with other islands in the row, the music was not in it: the pill
            // just chosen again (a scene's exit) puts it back first.
            if (MUSIC_ISLAND !in islandKeys) refresh()
            val keys = islandKeys
            if (MUSIC_ISLAND !in keys) return null
            val selected = selectedIsland?.takeIf { it in keys } ?: MUSIC_ISLAND
            selectedIsland = if (selected != MUSIC_ISLAND && smallAfter(keys, selected) == MUSIC_ISLAND &&
                rowFor(selected)?.let(::rowReady) == true) selected else MUSIC_ISLAND
        }
        val g = Group(musicSmall = selectedIsland != MUSIC_ISLAND, down = !toNative)
        // Coming down the other rows are there, whole; going up they come out of the fold.
        g.foldAlpha.value = if (toNative) 0f else 1f
        g.foldAlpha.target = g.foldAlpha.value
        // Going up they come out of the pile.
        g.foldK.value = if (toNative) 1f else 0f
        g.foldK.target = g.foldK.value
        group = g
        rowsAnticipated = false
        view.visibility = View.VISIBLE
        updateNativeSuppression(false)
        refresh()
        position()
        // The small place keeps what it holds till the morph is over, whatever comes in meanwhile.
        preferredSmall = smallKey
        // The other islands stay in the row: none goes into its row with the music any more.
        g.other = null
        g.otherSmall = g.other != null && !g.musicSmall
        g.waitSince = android.os.SystemClock.uptimeMillis()
        val lead = if (g.musicSmall) prepareSmallView(MUSIC_ISLAND) else view
        if (lead == null) {
            group = null
            return null
        }
        if (g.musicSmall) {
            // The view is the small island from here, in its very place.
            g.smallView = lead
            smallIsland?.visibility = View.GONE
        } else {
            // followShortcuts leaves the pill's fade alone while this morph draws it, so it kept
            // whatever was last written before the cover - once 0, and the card came down into
            // nothing and the pill turned up only once the morph had ended (2026-10-20). Set here,
            // from the binding refresh() has just made.
            val want = if (pillUnbound) 0f else 1f
            if (kotlin.math.abs(view.transitionAlpha - want) > 0.002f) {
                trace("group pill fade ${"%.2f".format(view.transitionAlpha)} -> $want")
                view.transitionAlpha = want
            }
        }
        val next = MiniCardMorph(lead, native, toNative, morphListener,
            restBox = if (g.musicSmall) { { smallBoxOnScreen() } } else null,
            circle = g.musicSmall && !stackedStyle())
        // The other island's row, if the stack has it laid out already: coming down it always
        // has; going up, only if the pull was seen coming (rowsAnticipated). Otherwise the music
        // sets out alone and the other island joins it the moment its row is there (groupFrame).
        g.other?.let { key ->
            val row = rowFor(key)?.takeIf(::rowReady)
            if (row != null) {
                if (!startFollower(g, row, fromIsland = toNative, joining = false)) g.inPlace = true
            } else if (!toNative) g.inPlace = true
        }
        trace("group ${if (toNative) "up" else "down"} musicSmall=${g.musicSmall} " +
            "other=${g.other?.takeLast(6)} small=${g.otherSmall} follower=${g.follower != null} " +
            "inPlace=${g.inPlace} " + smallState())
        return next
    }

    /**
     * A view of its own for the small island's place through a morph, bound to [key]'s island.
     * [pooled]: one kept from an earlier switch (spareViews) when there is one. A new one cost
     * 7ms - its material put on and its lines laid out for the first time - and a switch takes
     * two, on the frame it starts: the frame dropped on every tap (traced 2026-09-25, 14 of the
     * frame's 19ms). Kept, its material is already on and only what changed is bound again.
     */
    private fun prepareSmallView(key: String, pooled: Boolean = false): MiniPlayerView? {
        val pill = player ?: return null
        val music = controller?.takeIf(::isUsable)
        val note = if (key == MUSIC_ISLAND) null
            else LockIslands.notes.firstOrNull { it.key == key } ?: LockIslands.noteFor(key)
        if (if (key == MUSIC_ISLAND) music == null else note == null) return null
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        val spare = if (pooled) takeSpare() else null
        val view = spare ?: traced("MC sv.new") { MiniPlayerView(context) }
        if (spare == null) {
            traced("MC sv.add") { host.addView(view, lockScreenLayerIndex(), ViewGroup.LayoutParams(w, h)) }
            Gen.ownView(host, view)
        } else view.layoutParams.takeIf { it.width != w || it.height != h }?.let {
            it.width = w
            it.height = h
        }
        traced("MC sv.bind") { if (music != null && note == null) bindMusic(view, music, config) else bindNote(view, note!!, config) }
        // Laid out here and now, as a flight is: its morph starts on this frame.
        if (spare == null || view.isLayoutRequested || view.width != w || view.height != h) traced("MC sv.layout") {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        // Its second button as the pill has it: a pooled view kept whatever its last switch left,
        // and one flying home to the pill came down without its left button and grew it in
        // after landing - gone for every tap of a run of them (filmed 2026-09-26).
        view.setSecondShown(smallKey == null, animate = false)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        return view
    }

    /**
     * [view], landing in the pill, laid out as the pill will be once it is there: the row's width
     * with a small island beside it ([small]) or alone, and the second button that goes with it.
     * A stand-in made when the switch set out was laid out for the row as it was - the stopwatch
     * came into the big island's frame as the middle island, one button beside its text, and on
     * landing turned into the big island in a frame: the left button there whole, the right one
     * jumped to the end, the text let out (filmed 2026-09-26). Laid out mid-morph, it keeps its
     * frame (MiniPlayerView.onLayout); the morph moves what is in it.
     */
    private fun fitToPill(view: MiniPlayerView, small: Boolean) {
        val rest = pillRest(small) ?: return
        val lp = view.layoutParams ?: return
        if (lp.width != rest.width || lp.height != rest.height) {
            lp.width = rest.width
            lp.height = rest.height
            view.layoutParams = lp
        }
        if (view.isLayoutRequested) {
            view.measure(View.MeasureSpec.makeMeasureSpec(rest.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rest.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, rest.width, rest.height)
        }
        view.setSecondShown(!small, animate = false)
    }

    /** Views a switch has finished with, kept out of sight in the host for the next one. */
    private val spareViews = ArrayList<MiniPlayerView>()

    /** A stack can spread several cards at once; prepare their views across idle frames. */
    private fun spareLimit(): Int = if (stackedStyle()) STACK_SPARE_VIEWS else SPARE_VIEWS

    private fun takeSpare(): MiniPlayerView? {
        while (spareViews.isNotEmpty()) {
            val v = spareViews.removeAt(spareViews.size - 1)
            if (v.parent === host) return v
        }
        return null
    }

    /** Done with [v]: kept for the next switch, or let go if two are kept already. */
    private fun recycleSmallView(v: MiniPlayerView) {
        if (v.parent !== host || spareViews.size >= spareLimit() || v in spareViews) {
            runCatching { host.removeView(v) }
            return
        }
        v.visibility = View.GONE
        v.alpha = 1f
        v.translationZ = 0f
        v.setArtworkHidden(false)
        spareViews += v
    }

    /**
     * Even one island can open into a card: its flight is made while
     * the thread is idle, bound to an island so their material is on, and laid out, not on the
     * tap's frame.
     */
    private fun prewarmSpares() {
        val wanted = min(islandKeys.size, spareLimit())
        if (destroyed || prewarmPosted || spareViews.size >= wanted) return
        prewarmPosted = true
        android.os.Looper.myQueue().addIdleHandler {
            prewarmPosted = false
            if (!destroyed && host.isAttachedToWindow && exchange == null &&
                spareViews.size < min(islandKeys.size, spareLimit())) {
                val key = islandKeys.firstOrNull()
                val warmed = traced("MC sv.prewarm") { key?.let(::prepareSmallView) }
                if (warmed != null) {
                    recycleSmallView(warmed)
                    // One a pass; don't keep retrying an island whose content is unavailable.
                    prewarmSpares()
                }
            }
            false
        }
    }

    private var prewarmPosted = false

    /** A notification's row, laid out and on screen above the row of islands. */
    private fun rowReady(row: View): Boolean = row.isAttachedToWindow && row.isLaidOut &&
        row.width > 0 && row.height > 0 && rowOnScreen(row)

    /**
     * The other island goes with the music's morph from here, between its place and [row]:
     * [fromIsland] it starts at its island (going up), else at the row (coming down).
     * [joining] the music's morph is already under way: it comes up to it over a few frames.
     */
    private fun startFollower(g: Group, row: View, fromIsland: Boolean, joining: Boolean): Boolean {
        val key = g.other ?: return false
        val view = (if (g.otherSmall) prepareSmallView(key) else player) ?: return false
        // Out of sight while it waited (holdRows): the morph saves the row's own alpha.
        showRow(row)
        val f = MiniCardMorph(view, row, fromIsland, followerListener, rowLanding(row),
            restBox = if (g.otherSmall) { { smallBoxOnScreen() } } else null,
            circle = g.otherSmall && !stackedStyle())
        if (!f.startDragging()) {
            if (g.otherSmall) runCatching { host.removeView(view) }
            hideRow(row)
            return false
        }
        if (g.otherSmall) {
            g.smallView = view
            smallIsland?.visibility = View.GONE
        }
        g.follower = f
        g.joinFrom = f.progress
        g.joinedAt = if (joining) android.os.SystemClock.uptimeMillis() else 0L
        trace("group follower ${key.takeLast(6)} joining=$joining from=${"%.2f".format(g.joinFrom)}")
        return true
    }

    /** A follower ends with the music's morph: the group finishes it, never its own springs. */
    private val followerListener = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {}
    }

    /** Every frame of the music's morph: the rest of the row moves with it. */
    private fun groupFrame(lead: MiniCardMorph) {
        val g = group ?: return
        if (morph !== lead) return
        val now = android.os.SystemClock.uptimeMillis()
        val other = g.other
        // Going up, the other island's row may come a few frames after the music set out. Coming
        // back before it has, the island never left its place and needs none.
        if (other != null && g.follower == null && !g.inPlace && lead.toNative) {
            val row = traced("MC g.findRow") { rowFor(other) }
            if (row != null && rowReady(row)) {
                if (!traced("MC g.startFollower") { startFollower(g, row, fromIsland = true, joining = true) }) g.inPlace = true
            } else if (now - g.waitSince > ROW_WAIT_MS) {
                Xp.log("MCIsland: $other has no row for the card's morph; in place")
                g.inPlace = true
            }
        }
        g.follower?.let { f ->
            val share = if (g.joinedAt == 0L) 1f
                else MiniCardMorph.smooth(0f, 1f, (now - g.joinedAt) / GROUP_JOIN_MS.toFloat())
            if (share >= 1f) g.joinedAt = 0L
            traced("MC g.follow") { f.follow(lead, share, g.joinFrom) }
        }
        traced("MC g.fold") { foldRows(g, lead, now) }
        // The small place: the view drawing it hands over to the real small island on its last
        // stretch home, as a flight does (FLIGHT_HANDOFF), overshoot and all.
        val smallMorph = if (g.musicSmall) lead else g.follower?.takeIf { g.otherSmall }
        val view = g.smallView
        if (smallMorph != null && view != null) {
            val p = smallMorph.progress
            val landing = !lead.toNative && p < FLIGHT_HANDOFF
            if (landing) smallMorph.containerBox()?.let(::landSmall)
            else if (g.smallLanding) unlandSmall()
            g.smallLanding = landing
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, p) else 1f
            if (kotlin.math.abs(view.alpha - a) > 0.002f) view.alpha = a
        }
        if (g.inPlace && other != null) {
            // No row: the island grows out of its place as the card comes down and shrinks
            // away there as the music goes up, on the same progress.
            val v = (1f - lead.progress).coerceIn(0f, 1f)
            if (g.otherSmall) {
                val small = smallIsland
                if (small != null && smallKey == other) {
                    if (small.visibility != View.VISIBLE) small.visibility = View.VISIBLE
                    smallGrow.value = v
                    applySmallGrow()
                }
            } else player?.let { if (kotlin.math.abs(it.alpha - v) > 0.002f) it.alpha = v }
        }
    }

    /** The real small island in the shape and place of the view landing on it, this frame. */
    private fun landSmall(box: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        if (small.visibility != View.VISIBLE) {
            small.visibility = View.VISIBLE
            small.alpha = 1f
        }
        landOn(box)
    }

    /** Turned back before landing: the view has the small place to itself again. */
    private fun unlandSmall() {
        if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
        smallIsland?.visibility = View.GONE
    }

    /**
     * The music's morph is over, [toNative] at the card: the rows are the stack's again, the
     * islands' views are done. At the row: the islands are in their places, and the stack is
     * let leave their rows out - out of sight until it has taken them away.
     */
    private fun endGroup(toNative: Boolean) {
        val g = group ?: return
        group = null
        g.follower?.cancel()
        g.smallView?.let { runCatching { host.removeView(it) } }
        landingBox = null
        smallIsland?.iconAtStart = false
        if (g.inPlace) {
            player?.alpha = 1f
            smallGrow.value = 1f
            smallGrow.target = 1f
        }
        restoreSmallIslandShape()
        for (row in g.folded.keys) row.setAnimationMatrix(null)
        for (key in rowKeys()) {
            val (row, top) = findRow(key) ?: continue
            if (toNative) {
                showRow(row)
                showRow(top)
            } else hideRowUntilGone(top, key)
        }
        if (!toNative) {
            snapSmallOnce = true
            rowsHeldUntil = android.os.SystemClock.uptimeMillis() + ROW_GONE_MS
        } else rowsHeldUntil = 0L
        trace("group end at=${if (toNative) "card" else "row"} " + smallState())
    }

    /**
     * Before every frame is drawn, the rows the stack has for the row's islands while it has
     * been asked to keep them (rowsHeld): one whose island is still waiting for it stays out
     * of sight; one with no place in the row fades with the card - out as the card comes down,
     * in as the music goes up; one being morphed is its morph's. Just after, whatever the stack
     * still shows of them for a moment is not drawn.
     */
    private fun holdRows() {
        val g = group
        val holding = rowsHeld()
        if (!holding && android.os.SystemClock.uptimeMillis() > rowsHeldUntil) return
        // The row the other island is morphing with: a group holding it is not folded whole.
        val keep = g?.other?.let(::rowFor)
        val done = HashSet<View>()
        val pile = ArrayList<View>()
        for (key in rowKeys()) {
            if (g != null && key == g.other && g.follower != null) continue
            val row = (if (g != null && key == g.other) keep else wholeRowFor(key, keep)) ?: continue
            // Another notification of the app the morph is taking: the same row, which is the morph's.
            if (row === keep && (g == null || key != g.other)) continue
            if (!done.add(row)) continue
            if (!holding) {
                if (row !in hiddenRows) hideRowUntilGone(row, key)
                continue
            }
            hideRow(row)
            val folds = g != null && key != g.other
            val ta = if (folds) (hiddenRows[row] ?: 1f) * g!!.foldAlpha.value.coerceIn(0f, 1f) else 0f
            if (kotlin.math.abs(row.transitionAlpha - ta) > 0.002f) row.transitionAlpha = ta
            if (folds) pile += row
        }
        if (g != null && pile.isNotEmpty()) stackRows(g, pile)
    }

    /**
     * The rows with no place in the row, piled as the stack piles its cards
     * (NotificationStackingCalculator): the lowest stays in front and shrinks toward 0.934; each
     * one above it slides down behind it, shrinking on through 0.85 to 0.8 the deeper it goes,
     * its top showing notif_stacking_height_2 (12dp), then _1 more (32dp), over the front
     * card. All of it [Group.foldK] of the way, and down by the finger's pull on top. Each is
     * scaled about its own bottom, in the animation matrix over whatever the stack has put there.
     */
    private fun stackRows(g: Group, rows: List<View>) {
        val k = g.foldK.value.coerceIn(0f, 1.2f)
        val xy = IntArray(2)
        // Where the stack has each, on screen, by its parent: the matrix here is not in it.
        val tops = rows.associateWith { row ->
            (row.parent as? View)?.getLocationOnScreen(xy)
            xy[1] + row.top + row.translationY
        }
        val order = rows.sortedBy { tops[it] }
        val frontTop = tops[order.last()] ?: return
        val h1 = stackingHeight(1)
        val h2 = stackingHeight(2)
        for ((i, row) in order.withIndex()) {
            val depth = order.size - 1 - i
            val h = row.height.toFloat()
            val scale: Float
            var dy = g.foldDy.value
            if (depth == 0) {
                scale = firstStackedScale(k)
            } else {
                scale = otherStackedScale(k * (depth + 1))
                val peek = h2 + if (depth >= 2) h1 else 0f
                val top = tops[row] ?: continue
                // Its top, drawn at its scale about its bottom, peeking over the front card.
                val to = frontTop - peek - top - h * (1f - scale)
                dy += to * k.coerceAtMost(1f)
            }
            val m = Matrix()
            m.setScale(scale, scale, row.width / 2f, row.translationY + h)
            m.postTranslate(0f, dy)
            row.setAnimationMatrix(m)
            g.folded[row] = true
        }
    }

    private fun stackingHeight(which: Int): Float {
        val res = context.resources
        val id = res.getIdentifier("notif_stacking_height_" + which, "dimen", "com.android.systemui")
        return if (id != 0) runCatching { res.getDimension(id) }.getOrNull() ?: 0f
            else dp(if (which == 1) 32f else 12f).toFloat()
    }

    /** NotificationStackingCalculator.calculateScaleForFirstStackedCard. */
    private fun firstStackedScale(p: Float): Float = when {
        p < 1f -> lerp(1f, 0.934f, p)
        p < 1.5f -> lerp(0.934f, 0.85f, ((p - 1f) / 0.5f).coerceIn(0f, 1f))
        else -> lerp(0.85f, 0.8f, ((p - 1.5f) / 1.5f).coerceIn(0f, 1f))
    }.coerceAtLeast(0.8f)

    /** The notifications' keys behind the row's islands: the stack island's each, the others themselves. */
    private fun rowKeys(): List<String> = LockIslands.notes.flatMap { LockIslands.membersOf(it.key) }

    /**
     * The stack island's notification whose row it opens into and comes home from: the one
     * pulled down (releasedRowAt), else the newest - the one it shows.
     */
    private var stackLeadKey: String? = null
    private val appLeadKeys = HashMap<String, String>()

    private fun groupLead(key: String): String? {
        val members = LockIslands.membersOf(key)
        val preferred = (if (key == STACK_ISLAND) stackLeadKey else appLeadKeys[key])
            ?.takeIf { it in members }
        if (preferred != null && hasLaidOutRow(preferred)) return preferred
        // A filter after ours can still keep a member out of the stack: the island opened into a
        // row that was never there, and waited it out, while the others' rows stood ready (PR #15).
        return members.firstOrNull(::hasLaidOutRow)?.also {
            if (key == STACK_ISLAND) stackLeadKey = it else appLeadKeys[key] = it
        }
            ?: preferred ?: members.firstOrNull()
    }

    private fun hasLaidOutRow(key: String): Boolean = findRow(key)?.let { (row, top) ->
        row.isAttachedToWindow && row.height > 0 && top.height > 0 &&
            row.visibility == View.VISIBLE && top.visibility == View.VISIBLE
    } == true

    /** The stack island's other rows the pile has moved: their matrix and alpha are ours. */
    private val pileRows = HashSet<View>()

    /** One of those rows on its way out from under the island: its own spring, 0 there, 1 in the stack. */
    private class PileRow(val row: View) {
        val out = Jelly(PILE_RESPONSE, PILE_DAMPING)
        val matrix = Matrix()
        /** Its turn, from the lead: 1 the nearest to it. */
        var turn = 1
    }

    private val piles = HashMap<View, PileRow>()
    private var pileLast = 0L
    private var pileKey = STACK_ISLAND

    /** Where the island was, the last frame the lead drew: the rows go home under it after the lead has landed. */
    private var pileFrom: CoverMorphMotion.Box? = null

    /** The lead's progress the rows follow once it has landed, while they settle (pileSettle). */
    private var pileAt = 0f

    /** When the rows were left to settle on their own (pileSettle), which gives up past PILE_SETTLE_MAX_MS. */
    private var pileSettleSince = 0L

    /**
     * The stack island's other rows as its lead goes out to its row or comes home: each springs
     * out from under the island to where the stack draws it, or back, one after another - as
     * ColorOS lays its notifications out of its capsule (reference filmed 2026-09-26). Each row
     * follows the lead's [progress] a turn later than the one before it, on a spring of its own:
     * out, the nearest comes first; home, the farthest goes first. Turned round - by a finger, a
     * tap, another island - the lead turns and every row turns with it from where it is, at its
     * speed. [from] is the island's end of the lead's morph this frame: the pill, a narrower pill
     * or the small island's circle, whichever the island is.
     *
     * The end is where the stack itself draws the row, pile and all (superTranslationY and
     * superScale, on its render node): the matrix is only the way there from the island. The
     * version before scrolled the rows through the stack's own calculator, followed the lead's
     * progress with none of their own, and came up alone at the card's place before the card.
     */
    private fun pileGroup(key: String, progress: Float, from: CoverMorphMotion.Box?) = traced("MC pileLookup") {
        pileKey = key
        Gen.unframe(pileSettle)
        if (from != null) pileFrom = from
        pileAt = progress
        val bucket = (progress * 10f).toInt()
        if (bucket != pileTraceBucket) pileTraceNext = true
        pileTraceBucket = bucket
        observePiles(true)
        // Asked on every touch of a pull, several times a frame: the rows are looked for once a
        // frame at most - each look walks the stack's rows by reflection (2026-09-26, frames
        // dropped pulling the stack island down).
        val now = android.os.SystemClock.uptimeMillis()
        if (now - pileFoundAt < PILE_FIND_MS && !pileTraceNext) return@traced
        pileFoundAt = now
        val lead = groupLead(key) ?: return@traced
        val members = LockIslands.membersOf(key)
        val stack = notificationStack() ?: return@traced
        val children = (0 until stack.childCount).map { stack.getChildAt(it) }
            .filter { it.javaClass.name.contains("ExpandableNotificationRow") }
        val index = MiniPlayerRowIndex.build(children, ::rowKey, ::childRows,
            kept.filterValues { it.row.isAttachedToWindow }.mapValues { it.value.row })
        val keep = index[lead] ?: return@traced
        val rows = members.asSequence().filter { it != lead }
            .mapNotNull { index[it] }
            .filter { it !== keep && !isInside(keep, it) }.distinct().toList()
        if (pileTraceNext) trace("pile p=${"%.2f".format(progress)} lead=${shortKey(lead)} keep=${shortName(keep)}" +
            "@${(keep.translationY).toInt()} members=${members.joinToString(",") { shortKey(it) }} " +
            "rows=${rows.joinToString(",") { shortName(it) + "@" + it.translationY.toInt() }} " +
            "missing=${members.filter { it != lead && it !in index }.joinToString(",") { shortKey(it) }}")
        if (rows.isEmpty()) return@traced
        // Their turns: the nearest to the lead in the stack first.
        val at = stackTargetY(keep) + keep.top
        rows.map { it to kotlin.math.abs(stackTargetY(it) + it.top - at) }
            .sortedBy { it.second }.forEachIndexed { i, (row, _) ->
            val pile = piles.getOrPut(row) {
                // First seen where the lead is: nothing moves at once.
                PileRow(row).also { it.out.value = pileTarget(progress, i + 1) }
            }
            pile.turn = i + 1
        }
    }

    private var pileFoundAt = 0L
    private var pileTraceNext = false
    private var pileMoving = false

    /**
     * The rows are sprung and placed right before the frame is drawn, not on the lead's frame
     * callback: the stack writes where it draws a row - superTranslationY, superScale - later in
     * the same frame than that callback, so a row read there was placed against the stack's last
     * frame. As the stack laid the pile out again with the lead taken home (0.960 -> 0.885, 9px
     * down over 200ms), the row shook all the way (2026-09-26) - the same as the card's (see
     * MiniCardMorph.preDraw).
     */
    private val pilePreDraw = ViewTreeObserver.OnPreDrawListener {
        android.os.Trace.beginSection("MC pilePreDraw")
        try { pileMoving = stepPiles() } finally { android.os.Trace.endSection() }
        true
    }

    private var pileObserved: View? = null

    private fun observePiles(on: Boolean) {
        if (on && pileObserved?.isAttachedToWindow == true) return
        pileObserved?.let { Gen.unpreDraw(it, pilePreDraw) }
        pileObserved = null
        if (!on) return
        val stack = notificationStack() ?: return
        Gen.preDraw(stack, pilePreDraw)
        pileObserved = stack
    }

    /** Where row [turn] is headed while the lead is at [progress]: behind it by its turn. */
    private fun pileTarget(progress: Float, turn: Int): Float {
        val lag = (turn * PILE_STAGGER).coerceAtMost(PILE_STAGGER_MAX)
        return ((progress - lag) / (1f - lag)).coerceAtLeast(0f)
    }

    /** Every row's spring a frame on, toward its turn behind the lead, and drawn there. */
    private fun stepPiles(): Boolean {
        val traceIt = pileTraceNext
        pileTraceNext = false
        val now = System.nanoTime()
        val dt = if (pileLast == 0L) 1f / 120f else ((now - pileLast) / 1e9f).coerceIn(0f, 0.05f)
        pileLast = now
        var moving = false
        val xy = IntArray(2)
        val it = piles.values.iterator()
        while (it.hasNext()) {
            val pile = it.next()
            val row = pile.row
            if (!row.isAttachedToWindow) {
                it.remove()
                continue
            }
            hideRow(row)
            pileRows += row
            pile.out.target = pileTarget(pileAt, pile.turn)
            pile.out.step(dt)
            if (!pile.out.atRest()) moving = true
            val u = pile.out.value
            val base = hiddenRows[row] ?: 1f
            val ta = base * MiniCardMorph.smooth(PILE_IN_FROM, PILE_IN_TO, u)
            if (kotlin.math.abs(row.transitionAlpha - ta) > 0.002f) row.transitionAlpha = ta
            // Where the stack draws it: its own place and scale in the pile, on its render node.
            val ty = runCatching { (Xp.callMethod(row, "getSuperTranslationY") as Number).toFloat() }
                .getOrDefault(row.translationY)
            val s = runCatching { (Xp.callMethod(row, "getSuperScaleY") as Number).toFloat() }
                .getOrDefault(row.scaleY).takeIf { it > 0.01f } ?: 1f
            // Its top left before the matrix, in the matrix's space (the parent's, at left/top).
            val mx = row.pivotX * (1f - s)
            val my = ty + row.pivotY * (1f - s)
            (row.parent as? View)?.getLocationOnScreen(xy) ?: continue
            val w = row.width * s
            val h = rowHeight(row) * s
            val native = CoverMorphMotion.Box(xy[0] + row.left + mx, xy[1] + row.top + my, w, h)
            val island = pileFrom
            val m = pile.matrix
            m.reset()
            if (island != null && u < 1f - 0.0005f || u > 1f + 0.0005f) {
                // Out from under the island at its width, centred on it.
                val k0 = if (island != null && w > 0f) island.w / w else 1f
                val start = if (island != null) CoverMorphMotion.Box(island.cx() - w * k0 / 2f,
                    island.cy() - h * k0 / 2f, w * k0, h * k0) else native
                val box = lerpBox(start, native, u)
                val k = if (w > 0f) box.w / w else 1f
                m.setScale(k, k, mx, my)
                m.postTranslate(box.x - native.x, box.y - native.y)
            }
            row.setAnimationMatrix(m)
            if (traceIt) trace("  pile ${shortName(row)} #${pile.turn} u=${"%.2f".format(u)}>" +
                "${"%.2f".format(pile.out.target)} ta=${"%.2f".format(ta)} at=${native.y.toInt()} " +
                "s=${"%.3f".format(s)} from=${island?.let { "${it.cx().toInt()},${it.cy().toInt()} w${it.w.toInt()}" }}")
        }
        return moving
    }

    /** The lead has landed out in its row and the rows are still on their way: they go on on their own. */
    private val pileSettle = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pileMoving && piles.values.all { it.out.value == 1f }) {
                finishPile(true)
                return
            }
            // The rows are stepped only in the stack's own pre-draw. A stack that no longer
            // draws - the lock screen gone, rebuilt, this runtime destroyed - never steps them
            // home, and this asked for a frame every vsync for as long as SystemUI lived.
            val stack = pileObserved
            if (destroyed || stack?.isShown != true ||
                android.os.SystemClock.uptimeMillis() - pileSettleSince > PILE_SETTLE_MAX_MS) {
                finishPile(true)
                return
            }
            // Stepped and placed before the frame is drawn (pilePreDraw): this only asks for the frame.
            observePiles(true)
            pileObserved?.invalidate()
            Gen.frame(this)
        }
    }

    /** The stack island is on its way back into the row: by a switch, or a morph of its own. */
    private fun stackGoingHome(): Boolean =
        exchange?.movers?.get(pileKey)?.headedHome == true ||
            noteMorphKey == pileKey && morph?.toNative == false

    /** The last tenth of a pile's progress traced, so a pile is traced ten times, not every frame. */
    private var pileTraceBucket = -1

    private fun shortKey(key: String): String = key.substringAfter('|').substringBefore('|').substringAfterLast('.')

    private fun rowHeight(row: View): Float =
        runCatching { (Xp.callMethod(row, "getActualHeight") as Number).toFloat() }.getOrNull()
            ?.takeIf { it > 0f } ?: row.height.toFloat()

    /**
     * The stack island's lead has landed: out in its row ([toRow]) the rows still on their way
     * go on to theirs, a turn behind; home, whatever is left of them goes with it.
     */
    private fun endPile(toRow: Boolean) {
        trace("pile end toRow=$toRow rows=${pileRows.joinToString(",") { shortName(it) }}")
        pileTraceBucket = -1
        pileAt = if (toRow) 1f else 0f
        if (toRow && piles.values.any { !it.out.atRest() || it.out.value != 1f }) {
            pileMoving = true
            pileSettleSince = android.os.SystemClock.uptimeMillis()
            Gen.unframe(pileSettle)
            Gen.frame(pileSettle)
            return
        }
        finishPile(toRow)
    }

    private fun finishPile(toRow: Boolean) {
        Gen.unframe(pileSettle)
        observePiles(false)
        pileMoving = false
        pileFoundAt = 0L
        for (row in pileRows) {
            row.setAnimationMatrix(null)
            if (toRow) showRow(row)
            else {
                row.transitionAlpha = 0f
                hideRowUntilGone(row, pileKey)
            }
        }
        pileRows.clear()
        piles.clear()
        pileLast = 0L
        pileFrom = null
        if (!toRow) {
            if (pileKey == STACK_ISLAND) stackLeadKey = null else appLeadKeys.remove(pileKey)
        }
    }

    /** NotificationStackingCalculator.calculateScaleForOtherStackedCards. */
    private fun otherStackedScale(p: Float): Float = when {
        p < 1f -> lerp(1f, 0.934f, p)
        p < 2f -> lerp(0.934f, 0.85f, p - 1f)
        else -> lerp(0.85f, 0.8f, (p - 2f).coerceAtMost(1f))
    }.coerceAtLeast(0.8f)

    /**
     * The rows with no place in the row, this frame, as the stack's fold moves them. Under the
     * finger coming down they follow the card's pull with the stack's own friction, whole; let
     * go, they spring back and go out on the fold's alpha spring if the card is headed for the
     * row, stay if it goes back up. Going up they come in on that spring once the music is on
     * its way into the card for good.
     */
    private fun foldRows(g: Group, lead: MiniCardMorph, now: Long) {
        val dt = if (g.foldLast == 0L) 1f / 120f else ((now - g.foldLast) / 1000f).coerceIn(0f, 0.05f)
        g.foldLast = now
        val box = lead.containerBox()
        if (g.down && g.cardTop.isNaN() && box != null) g.cardTop = box.y
        if (lead.held) {
            if (g.down && box != null) {
                val range = dp(FOLD_PULL_RANGE_DP).toFloat()
                val t = ((box.y - g.cardTop) / range).coerceIn(0f, 1f)
                g.foldDy.value = (t * t * t / 3f - t * t + t) * range
                g.foldDy.target = g.foldDy.value
                g.foldDy.velocity = 0f
                // The pile gathers with the pull, as far as the finger has taken it.
                g.foldK.value = t
                g.foldK.target = t
                g.foldK.velocity = 0f
            }
            return
        }
        g.foldDy.target = 0f
        g.foldAlpha.target = if (lead.toNative) 1f else 0f
        g.foldK.target = if (lead.toNative) 0f else 1f
        g.foldDy.step(dt)
        g.foldAlpha.step(dt)
        g.foldK.step(dt)
    }

    /** A finger has started pulling the music up ([small]: out of the small island). */
    fun anticipateRows(small: Boolean) {
        if (rowsAnticipated || group != null || !canDrag(fromNative = false, small = small)) return
        if (LockIslands.notes.isEmpty()) return
        rowsAnticipated = true
        updateVisibility()
    }

    /** ...and let go without opening anything: the stack leaves the rows out again. */
    fun stopAnticipating() {
        if (!rowsAnticipated) return
        rowsAnticipated = false
        if (group != null) return
        rowsHeldUntil = android.os.SystemClock.uptimeMillis() + ROW_GONE_MS
        for (key in rowKeys()) findRow(key)?.let { hideRowUntilGone(it.second, key) }
        updateVisibility()
    }

    // ---- the small island coming and going

    /** The small island's presence, 0 gone to 1 there, sprung the same way. */
    private val smallGrow = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
    private var smallGrowing = false
    private var smallGrowLast = 0L

    private val smallGrowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC smallGrow"); try {
            if (!smallGrowing) return
            val dt = if (smallGrowLast == 0L) 1f / 120f
                else ((frameTimeNanos - smallGrowLast) / 1e9f).coerceIn(0f, 0.05f)
            smallGrowLast = frameTimeNanos
            smallGrow.step(dt)
            applySmallGrow()
            if (smallGrow.atRest()) {
                smallGrowing = false
                if (smallGrow.target == 0f) smallIsland?.visibility = View.GONE
                else restoreSmallIslandShape()
            } else Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * The small island shown or not: coming up it grows out of nothing in its place, going it
     * shrinks away there - unless the row itself is going, when it just goes with it.
     */
    private fun setSmallShown(shown: Boolean, animate: Boolean) {
        val small = smallIsland ?: return
        val visible = small.visibility == View.VISIBLE
        if (visible != shown) {
            trace("small ${if (shown) "show" else "hide"} animate=$animate key=${smallKey?.takeLast(6)} " +
                "noteMorph=${noteMorphKey?.takeLast(6)} out=$flightOut landing=$flightLanding group=${group != null}")
        }
        if (shown && visible && (!smallGrowing || smallGrow.target == 1f)) return
        if (!shown && (!visible || (smallGrowing && smallGrow.target == 0f))) return
        if (!animate || (swap?.smallMode ?: SMALL_KEPT) != SMALL_KEPT) {
            smallGrowing = false
            Gen.unframe(smallGrowFrame)
            smallGrow.value = if (shown) 1f else 0f
            smallGrow.target = smallGrow.value
            small.visibility = if (shown) View.VISIBLE else View.GONE
            if (shown && landingBox == null) restoreSmallIslandShape()
            return
        }
        if (shown && !visible) {
            smallGrow.value = 0f
            smallGrow.velocity = 0f
            small.visibility = View.VISIBLE
        }
        smallGrow.target = if (shown) 1f else 0f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            applySmallGrow()
            Gen.frame(smallGrowFrame)
        }
    }

    /** Another island in the small island's place: it comes up there from nothing. */
    private fun regrowSmall() {
        smallGrow.value = 0f
        smallGrow.velocity = 0f
        smallGrow.target = 1f
        if (!smallGrowing) {
            smallGrowing = true
            smallGrowLast = 0L
            Gen.frame(smallGrowFrame)
        }
        applySmallGrow()
    }

    private fun applySmallGrow() {
        val small = smallIsland ?: return
        val w = smallRestWidth().toFloat()
        val v = smallGrow.value
        val width = lerp(w * 0.4f, w, v).coerceAtLeast(1f).roundToInt()
        val height = lerp(smallRestHeight() * 0.4f, smallRestHeight().toFloat(), v)
            .coerceAtLeast(1f).roundToInt()
        small.setShape(width, height, 0)
        small.setIconAlpha(MiniCardMorph.smooth(0.3f, 1f, v))
        small.alpha = MiniCardMorph.smooth(0f, 0.5f, v) *
            if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
    }

    // ---- the finger on a row of islands

    private var islandDragging = false

    /**
     * The finger pulling sideways on a row of islands, as the super island follows it
     * (IslandSwipeAnimator): progress is the pull over half the width, times 0.14. The pill
     * narrows by that share; pulled toward the small island it leans that way, pulled away the
     * small island reaches toward it, by a quarter of the pill's width. Neither fades as the
     * super island's do: let go, the switch took over at full opacity and the pill flashed
     * (2026-09-25, the user had the fade taken out).
     */
    fun islandDrag(dx: Float) {
        islandDragStep(dx)
    }

    private fun islandDragStep(dx: Float) {
        val view = player ?: return
        // A switch still settling is finished where it was headed: the finger has the row now.
        if (swap != null) endSwap()
        if (!islandDragging) springSmallNudgeBack(0f, 0f)
        islandDragging = true
        // The row's end: nothing to switch to, so the pull takes the pill with it instead - it
        // runs into the torch or the camera there and they give way, as they do for a lone
        // island's pull (the user, 2026-09-29).
        val atEnd = !canSwitchIsland(next = dx < 0f)
        if (atEnd) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.setNudge(pillNudgeX(dx, host.resources.displayMetrics.density), 0f)
            // Back from a pull the other way, the small island leaves the shape it had for it.
            if (smallIsland?.visibility == View.VISIBLE && !smallGrowing) restoreSmallIslandShape()
            return
        }
        val p = (kotlin.math.abs(dx) / (host.width / 2f).coerceAtLeast(1f) * SWIPE_SHARE)
            .coerceAtMost(SWIPE_SHARE * 1.5f)
        val towardSmall = dx > 0f
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = 1f - p
        view.scaleY = 1f - p * 0.25f
        val small = smallIsland?.takeIf { it.visibility == View.VISIBLE } ?: return
        val d = discDiameter().toFloat()
        val w = view.width.toFloat()
        if (towardSmall) {
            view.setNudge((smallRest[0] - (view.left + view.translationX + w / 2f)) * p, 0f)
            val side = (d * (1f - 0.2f * p / SWIPE_SHARE)).roundToInt()
            small.setShape(side, side, 0)
        } else {
            view.setNudge(0f, 0f)
            val rest = view.restBoxOnScreen() ?: return
            widenSmallIsland(rest)
            small.setShape((d + w * p * 0.5f).roundToInt(), d.roundToInt(), -(w * p * 0.25f).roundToInt())
            small.alpha = 1f
        }
    }

    /** The finger is off: over the threshold the switch runs, under it the row springs back. */
    fun islandDragEnd(commit: Boolean, next: Boolean, vx: Float = 0f) {
        if (!islandDragging) return
        // Past the row's end there is nothing to switch to: it springs back as from a short pull.
        val switchable = canSwitchIsland(next)
        if (commit && switchable) {
            // A fling the way of the switch hands the switch its speed; one back against it none.
            switchIsland(next, flingPx = if (next == vx < 0f) kotlin.math.abs(vx) else 0f)
            return
        }
        val view = player ?: return
        islandDragging = false
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(SPRING_BACK_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1.05f))
            .start()
        view.springNudgeBack(0f, 0f)
        restoreSmallIslandShape()
    }

    private fun resetIslandDrag() {
        if (!islandDragging) return
        islandDragging = false
        player?.let {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
            it.clearNudge()
        }
    }

    // ------------------------------------------------------------ one island out as its card

    /**
     * The music has other islands in the row to stay behind when it goes out as its card: it
     * goes out and comes back as a flight, as a notification does, and they keep the row.
     * Alone it has the media card's own morph, the pill and the card one or the other.
     */
    private fun musicFlies(): Boolean = !Main.coverSceneActive() && othersBesideMusic()

    /** There are islands other than the music's, in the row or out as their rows. */
    private fun othersBesideMusic(): Boolean {
        if (controller?.takeIf(::isUsable) == null) return false
        // A notification out as its row counts: the music going out takes its place.
        return LockIslands.notes.isNotEmpty() || islandKeys.any { it != MUSIC_ISLAND } ||
            LockIslands.releasedKeys().any { rowFor(it) != null }
    }

    /**
     * Into the cover or out of it with other islands in the row: the music goes as a flight, as
     * it does pulled up into its card or down out of it - the row's next island grows into the
     * pill as it leaves, and the others make room as it comes back. The pill itself going into
     * the cover, the others stood still and the pill swapped islands in one jump at the end.
     */
    private fun startSceneFlight(native: View, toNative: Boolean): Boolean {
        if (noteMorphKey != null || flight != null || exchange != null) return false
        val view = player?.takeIf { it.visibility == View.VISIBLE } ?: return false
        endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        view.clearNudge()
        if (toNative) {
            val fromSmall = selectedIsland != MUSIC_ISLAND
            if (fromSmall && smallKey != MUSIC_ISLAND) return false
            if (prepareFlight(MUSIC_ISLAND) == null) return false
            noteMorphKey = MUSIC_ISLAND
            flightFromSmall = fromSmall
            flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
            flightOut = false
        } else {
            if (prepareFlight(MUSIC_ISLAND) == null) return false
            noteMorphKey = MUSIC_ISLAND
            flightFromSmall = false
            flightOut = false
            flightHome = if (MUSIC_ISLAND in releasedFromSmall) HOME_SMALL else HOME_PILL
            if (flightHome == HOME_PILL) {
                // The pill is the music's again: whatever took it forms into the small place.
                val oldBig = selectedIsland
                val oldSmall = smallKey
                selectedIsland = MUSIC_ISLAND
                refresh()
                startSwap(oldBig, oldSmall, intoSmall = true)
            }
            refresh()
            position()
        }
        morphScene = true
        // The card is the flight's other end: out from under the pill's suppression.
        updateNativeSuppression(false)
        trace("scene flight ${if (toNative) "in" else "out"} home=${if (flightHome == HOME_PILL) "pill" else "small"} " +
            smallState())
        if (!startNoteMorph(MUSIC_ISLAND, native, toRow = toNative)) {
            morphScene = false
            return false
        }
        return true
    }

    /**
     * The media card dismissed while its session lives on: everything of the music's that was
     * waiting on the card lets go of it. A switch waited on a card being laid out that never
     * would be, and the notifications' islands stopped answering with it (PR #15).
     */
    fun onMediaCardRemoved() {
        exchange?.let { x ->
            if (x.expanded == MUSIC_ISLAND) x.expanded = null
            if (x.pending == MUSIC_ISLAND) x.pending = null
            // Out of the movers first: its morph ending must not settle it as a landing.
            x.movers.remove(MUSIC_ISLAND)?.let { m ->
                unland(m)
                m.morph?.cancel()
                recycleSmallView(m.view)
            }
            x.seats = x.seats?.let { s ->
                Seats(s.big?.takeUnless { it == MUSIC_ISLAND }, s.small?.takeUnless { it == MUSIC_ISLAND })
            }
            if (x.pending == null && x.movers.isEmpty()) endSwitch(x)
        }
        if (noteMorphKey == MUSIC_ISLAND || morph != null && noteMorphKey == null) morph?.cancel()
        if (rowWaitKey == MUSIC_ISLAND) abandonNoteMorph(MUSIC_ISLAND)
        releasedFromPill -= MUSIC_ISLAND
        releasedFromSmall -= MUSIC_ISLAND
        returning.remove(MUSIC_ISLAND)
        if (selectedIsland == MUSIC_ISLAND) selectedIsland = null
        if (preferredSmall == MUSIC_ISLAND) preferredSmall = null
        restoreHeader()
        scheduleRefresh()
    }

    /** The music is out as its card - the media card, or the cover - the row holding the others. */
    private fun musicExpanded(includeCover: Boolean): Boolean = MiniPlayerPresentationPolicy.mediaExpanded(
        cardPresent = Main.miniPlayerMediaCardPresent(),
        sessionUsable = controller?.let(::isUsable) == true,
        musicInRow = MUSIC_ISLAND in islandKeys,
        nativeRequested = MiniPlayerRuntime.nativeRequested(controller?.sessionToken),
        coverActive = includeCover && Main.coverModeOn(),
    )

    /** The music is out as the media card, the row holding the others. */
    private fun musicCarded(): Boolean = musicExpanded(includeCover = false)

    /** A pull down on the media card brings the music home as a flight (musicFlies). */
    fun musicCollapses(): Boolean = config.getBoolean(MiniPlayerConfig.ENABLED) &&
        musicFlies() && musicCarded() && morph == null && exchange == null && noteMorphKey == null &&
        spread == null

    /**
     * The one island out as its card and settled there - the super island's expanded island.
     * Null with every island in the row. Only ever one: another asked out takes its place.
     */
    private fun expandedKey(): String? {
        // The cover is the music's card: another island opened there takes its place, and the
        // cover goes (2026-09-25) - not a row of its own in the cover's stack beside the card.
        if (musicExpanded(includeCover = true)) return MUSIC_ISLAND
        return LockIslands.releasedKeys().firstOrNull { it != noteMorphKey && rowFor(it) != null }
    }

    /**
     * The super island's compareState for this row: whether [origin] takes the place [cur]
     * holds, the big island's when [big]. The music is ranked as the super island ranks it
     * (MiuiIslandMediaControllerImpl: islandProperty 2, islandPriority 2), so a focus
     * notification of property 1 - 高德's navigation - takes the big place from it and it the
     * small place back (the user, 2026-10-24; it ranked above everything from 2026-09-25).
     * Against the ordinary notifications, which the super island has no island for, the music
     * still wins both places.
     */
    private fun takesPlace(cur: String, origin: String, big: Boolean): Boolean {
        if (origin == MUSIC_ISLAND && !isFocusIsland(cur)) return true
        if (cur == MUSIC_ISLAND && !isFocusIsland(origin)) return false
        val c = rankOf(cur) ?: return true
        val o = rankOf(origin) ?: return false
        return LockIslands.takesPlace(c, o, big)
    }

    private fun rankOf(key: String): LockIslands.Rank? =
        if (key == MUSIC_ISLAND) LockIslands.Rank(2, 2, musicSince) else LockIslands.rankOf(key)

    /** A focus notification's island: not the music, the stack island or an app's group. */
    private fun isFocusIsland(key: String): Boolean =
        key != MUSIC_ISLAND && key != STACK_ISLAND && !LockIslands.isAppGroup(key) &&
            LockIslands.noteFor(key)?.focus == true

    /** When the music's session was first seen, on the notifications' clock (Note.since). */
    private var musicSince = 0L

    /**
     * The islands this lock screen's row has had, and since when each known one has been
     * missing from it. A focus island not among them is new, and may take the pill (refreshUnsafe);
     * one missing for less than PICK_GRACE_MS keeps the pill it had. The time unlocked is not
     * counted ([knownLocked]): locked again, the row is the one that was left.
     */
    private val islandKnown = HashSet<String>()
    private val islandMissing = HashMap<String, Long>()
    private var knownLocked = false
    private val graceCheck = Runnable { refresh() }

    private fun noteKnown(present: List<String>, now: Long) {
        for (k in present) {
            islandKnown += k
            islandMissing -= k
        }
        val it = islandKnown.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k in present) continue
            val since = islandMissing.getOrPut(k) { now }
            if (now - since > KNOWN_FORGET_MS) {
                it.remove()
                islandMissing -= k
            }
        }
    }

    /** Who is in the big place and the small one once an expanded switch is over. */
    private class Seats(val big: String?, val small: String?)

    /**
     * The super island's expanded switch, as ClickEventCoordinator and its state handlers run it
     * (read from the plugin, 2026-09-25). [up] - in the big place if [fromPill], else the small
     * one - goes out as its card, and [down], out now, comes back in. Tapped in the big place,
     * that place is filled first (BigIslandStateHandler.handleFillInState): the small island
     * moves up into it and the next hidden one into the small place. Tapped in the small place,
     * the next hidden one fills that. Then [down] comes in at the big place
     * (handleReplacedState): if it takes the place from what is there, that one moves down to
     * the small place's turn; else [down] tries the small place itself, and whatever loses that
     * goes into hiding. So the card coming down does not simply land where the tapped island
     * was: with three, the third island can change places with it. With nothing out - the one
     * that was is on its way home already - only the place [up] leaves is filled.
     */
    private fun reseat(up: String, down: String?, fromPill: Boolean): Seats {
        val big0 = selectedIsland
        val small0 = smallKey
        val keys = islandKeys
        val at = keys.indexOf(big0).coerceAtLeast(0)
        // The hidden ones, next first: after the small island round the row.
        val hidden = (keys.drop(at) + keys.take(at))
            .filter { it != big0 && it != small0 && it != up && it != down }.toMutableList()
        var big: String?
        var small: String?
        if (fromPill) {
            big = small0?.takeIf { it != up && it != down } ?: hidden.removeFirstOrNull()
            small = hidden.removeFirstOrNull()
        } else {
            big = big0?.takeIf { it != up && it != down }
            small = hidden.removeFirstOrNull()
        }
        fun intoSmall(key: String) {
            val s = small
            when {
                s == null -> small = key
                takesPlace(s, key, big = false) -> { hidden.add(0, s); small = key }
                else -> hidden.add(0, key)
            }
        }
        val b = big
        when {
            down == null -> {}
            b == null -> big = down
            takesPlace(b, down, big = true) -> { big = down; intoSmall(b) }
            else -> intoSmall(down)
        }
        return Seats(big, small)
    }

    /** [key]'s card: the media card for the music, the notification's own row in the stack. */
    private fun nativeFor(key: String): View? = if (key == MUSIC_ISLAND)
        transitionHeader()?.takeIf { Main.miniPlayerMediaCardPresent() } else rowFor(key)

    // ---- a card landing where the stack is settling it

    /**
     * How far down [native] is still to go, on screen: from where the stack has it this frame
     * to where it will have it once [leaving]'s row and the rows already on their way out are
     * gone. The keyguard's stack is laid out from the bottom up, so every row going away under
     * a card moves that card down by the room it took. A card opening while the one it replaces
     * was still in the stack landed above that one's row and was slid down to the bottom by the
     * stack half a second later (filmed 2026-09-25); it lands at the bottom now. The stack's own
     * target (the view state it is animating each row to) is taken, not where it is mid-animation.
     */
    private fun settleDy(native: View, leaving: Collection<String>): Float { return traced("MC settleDy") {
        val stack = notificationStack() ?: return 0f
        if (native.parent !== stack) return 0f
        val gone = HashSet<View>()
        for (k in leaving) if (k != MUSIC_ISLAND) findRow(k)?.let { (row, top) -> if (row === top) gone += top }
        val kids = ArrayList<View>()
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility == View.GONE || c.height <= 0) continue
            val row = c.javaClass.name.contains("ExpandableNotificationRow")
            if (!row && c !== native) continue
            kids += c
            // Not the stack island's piled rows while it opens: hidden while they come up out of
            // the pile, they stay. Counted as leaving, the island's card flew 244px low, to where
            // WeChat's row was, and was pulled back up when the pile ended. Going home they go
            // with it: kept, a focus card let out in its place landed a row too high (2026-09-26).
            if (row && c !== native && c in hiddenRows && (c !in pileRows || stackGoingHome())) gone += c
        }
        val top = kids.associateWith { stackTargetY(it) + it.top }
        val nativeTop = top[native] ?: return 0f
        var shift = 0f
        var above = Float.NaN
        val now = android.os.SystemClock.uptimeMillis()
        val recordDiagnostic = now - settleDiagnosticAt >= 100L
        val goneTrace = if (recordDiagnostic) StringBuilder() else null
        // Rows given back early are transient views, not among these: what is left here is only
        // a row the stack has not been told about yet, for the frame or two until it has.
        for (c in kids.sortedBy { top[it] }) {
            val t = top[c] ?: continue
            val bottom = t + stackTargetHeight(c)
            if (c in gone && t > nativeTop && !above.isNaN()) shift += bottom - above
            if (c in gone && goneTrace != null) goneTrace.append(if (t > nativeTop) " v" else " ^").append(t.toInt())
                .append('+').append((bottom - t).toInt()).append("/ty").append(c.translationY.toInt())
            above = bottom
        }
        // The stack's own target, plus the room of the rows it has not been told are going yet.
        val stackTarget = stackTargetY(native)
        // Where a card rests alone at the bottom of the pile, learned while the stack is one.
        if (gone.isEmpty() && shift == 0f) learnBottomLine(kids, top)
        // Never past that bottom: the rows going are counted as if the stack were piled from the
        // bottom up, and in the stack island's list they reach below it - the stopwatch, laid out
        // over the list's three rows on its way up, was sent 244px under its place and pulled back
        // up once they had gone (2026-09-26).
        // In the stack's translations, as stackTarget is: the line is laid-out top plus translation.
        val floor = stackBottomLine.takeIf { shift > 0f && !it.isNaN() }
            ?.let { it - stackTargetHeight(native) - native.top }
        val target = if (floor != null) minOf(stackTarget + shift, floor) else stackTarget + shift
        lastGone = gone.size
        lastStackTarget = stackTarget
        if (recordDiagnostic) {
            settleDiagnosticAt = now
            lastSettle = (if (floor != null && stackTarget + shift > floor) "floor=${floor.toInt()} " else "") +
            "t=${target.toInt()} st=${stackTarget.toInt()}${if (targetRead) "" else "?"} " +
            "sh=${shift.toInt()} gone=${gone.size}$goneTrace kept=${kept.size} " +
            "ty=${native.translationY.toInt()} " +
            "rows=${kids.size} mt=${Main.liveMediaTop().let { if (it.isNaN()) "-" else it.toInt().toString() }}"
        }
        return target - native.translationY
    } }

    /**
     * The bottom of the keyguard stack's pile - where a lone card's bottom rests - in the stack's
     * own translation space; NaN until seen. Read off the rows as they are laid out while the
     * stack is a pile (not the island's list, which runs on below it), nothing leaving.
     */
    private var stackBottomLine = Float.NaN
    private var bottomLineAt = 0L

    private fun learnBottomLine(kids: List<View>, top: Map<View, Float>) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - bottomLineAt < 500L) return
        bottomLineAt = now
        if (stackState() != "STACK") return
        // Laid-out top plus translation, as settleDy's tops are.
        val bottom = kids.mapNotNull { c -> top[c]?.let { it + stackTargetHeight(c) } }.maxOrNull() ?: return
        stackBottomLine = bottom
    }

    /** The last settleDy's count of rows still on their way out. */
    private var lastGone = 0

    /** The stack's own target for the card, as the last settleDy read it. */
    private var lastStackTarget = 0f

    /** The last settleDy reading, for the landing trace. */
    private var lastSettle = ""
    private var settleDiagnosticAt = 0L
    private var contentDiagnosticAt = 0L

    /**
     * The translation the stack is taking [v] to: its view state's mYTranslation, which the
     * stack's animator runs translationY to (ViewState.startYTranslationAnimation) - else where
     * it is. Asked by the AOSP names first, it read where the row was every frame (2026-09-25).
     */
    private fun stackTargetY(v: View): Float = runCatching {
        val state = Xp.callMethod(v, "getViewState")!!
        (Xp.getObjectField(state, "mYTranslation") as Number).toFloat().also { targetRead = true }
    }.getOrElse {
        targetRead = false
        v.translationY
    }

    /** Whether the last stackTargetY read the stack's own target. */
    private var targetRead = false

    private var contentTopAt = 0L
    private var contentTop = Float.NaN

    /**
     * See the static stackContentTop: every row and card in the stack, at the stack's own target -
     * but not the rows this controller has hidden. A notification gone into an island keeps its
     * row in the stack, invisible, and the stack lays out from the bottom up: with the stopwatch
     * let out under it, the hidden QQ row stood 258px above the stopwatch's card and the clock
     * shrank for a row nobody could see (filmed 2026-09-26). settleDy skips them the same way.
     * Nor any child not drawn at all - the media card behind the pill is the other one.
     * With every row hidden, there is no notification constraint on the clock's edited position.
     */
    fun stackContentTop(): Float {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) return Float.NaN
        // Asked on every frame of the clock's own animation: read once a frame at most.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - contentTopAt < 6L) return contentTop
        contentTopAt = now
        val stack = notificationStack()
        contentTop = if (stack == null || !stack.isAttachedToWindow) Float.NaN else {
            var top = Float.POSITIVE_INFINITY
            var from: View? = null
            var skipped = 0
            val stackY = IntArray(2).also(stack::getLocationOnScreen)[1]
            // Every child weighed, for `op mini`: name:target-on-screen/translation and why skipped.
            val recordDiagnostic = now - contentDiagnosticAt >= 100L
            val seen = if (recordDiagnostic) StringBuilder() else null
            // A card a switch is taking home to its island is the morph's, drawn on its way to the
            // row, not where the stack still has it. The media card going back to the pill for a
            // focus notification was pushed up the stack by the row let out under it, 1700 -> 1425,
            // and the clock shrank for it until it went (probe, 2026-09-26).
            val leaving = exchange?.movers?.values?.filter { it.headedHome }?.map { it.native }.orEmpty() +
                // The same for the music's own morph into the pill: pulled down out of the cover,
                // the exit aimed at the card and then jumped to full size as it went (2026-09-26).
                listOfNotNull(transitionHeader()?.takeIf {
                    morph?.toNative == false && (noteMorphKey == null || noteMorphKey == MUSIC_ISLAND)
                })
            // The row spreading out as the list or folding back: where its islands start from.
            val spreadRest = spreadMoving()?.let {
                player?.takeIf { v -> v.isAttachedToWindow }
                    ?.let { v -> (IntArray(2).also(v::getLocationOnScreen)[1] - stackY).toFloat() }
                    ?: stack.height.toFloat()
            }
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility == View.GONE || c.height <= 0) continue
                val name = c.javaClass.name
                if (!name.contains("ExpandableNotificationRow") && !name.contains("MediaHeader")) continue
                val t = stackTargetY(c) + c.top
                // One of the spread's cards, hidden or drawn at nothing while its island's morph
                // has it: weighed where the morph is taking it, from its island's place to its own
                // by the spread's progress. Skipped as not drawn, the clock kept its size till the
                // whole list was out and then shrank all at once (the user, 2026-09-30).
                val share = if (spreadRest != null) spreadShare(c) else null
                if (share != null) {
                    val from0 = maxOf(spreadRest!!, t)
                    val at = from0 + (t - from0) * share
                    seen?.let { it.append(' ').append(shortName(c)).append(':').append((stackY + at).toInt()).append('S') }
                    if (at < top) { top = at; from = c }
                    continue
                }
                val home = leaving.any { isInside(it, c) }
                if (seen != null) {
                    seen.append(' ').append(shortName(c)).append(':').append((stackY + t).toInt())
                        .append('/').append((stackY + c.top + c.translationY).toInt())
                    if (c in hiddenRows) seen.append('H')
                    if (c.visibility != View.VISIBLE) seen.append('I')
                    if (c.alpha <= 0.01f) seen.append('A')
                    if (pinned?.get() === c) seen.append('P')
                    if (home) seen.append('L')
                }
                // Hidden only while its morph is being made - a tap has sent it out, the row is
                // there, the morph takes it a frame or two later: it is on its way, and the clock
                // gives way to where it is going. Skipped, the clock grew for the frame or two
                // and shrank back on every tap of a run of them (2026-09-26).
                val coming = c in hiddenRows && rowComingOut(c)
                if (coming) seen?.append('C')
                // The media card the pill stands in for is kept INVISIBLE, still laid out (see
                // the setVisibility hook in install): the clock gave way to it too (2026-09-26).
                // Folded into the stack's "N个通知" state, a row is laid out and drawn at nothing
                // (its numStateAlpha): not there for the clock either. Only while the stack says it
                // is folded - opening, the rows fade in from nothing and count from the first frame.
                val folded = NumState.inNumber == true && foldedAway(c)
                if (folded) seen?.append('F')
                if (home || c in hiddenRows && !coming || c.visibility != View.VISIBLE ||
                    c.alpha <= 0.01f && !coming || folded) { skipped++; continue }
                if (t < top) { top = t; from = c }
            }
            if (seen != null) {
                contentDiagnosticAt = now
                contentTopFrom = (from?.let(::shortName) ?: "-") + " skip=$skipped" +
                (if (exchange != null) " X" else "") + (if (morph != null) " M" else "") + " [" + seen.toString().trim() + "]"
            }
            ClockRoomPolicy.contentTop(stackY.toFloat(), top)
        }
        return contentTop
    }

    /** The spread on its way out or back in (not waiting, not open); null otherwise. */
    private fun spreadMoving(): Spread? =
        spread?.takeIf { it.phase == SPREAD_OPENING || it.phase == SPREAD_CLOSING }

    /**
     * [c], a stack child, is one of the moving spread's cards: its progress, 0 at the islands'
     * end. Not a card out from before while the list opens - that one is where it is.
     */
    private fun spreadShare(c: View): Float? {
        val s = spreadMoving() ?: return null
        val item = s.items.firstOrNull { item ->
            item.native?.let { isInside(it, c) } == true && !(item.wasOut && s.phase != SPREAD_CLOSING)
        } ?: return null
        return if (item.native == null) null else s.progress
    }

    /** A stack row the stack's fold has faded to nothing (its injector's numStateAlpha). */
    private fun foldedAway(c: View): Boolean = runCatching {
        (Xp.getObjectField(Xp.callMethod(c, "getInjector"), "numStateAlpha") as Float) <= 0.01f
    }.getOrDefault(false)

    /**
     * [c], a stack child this controller keeps out of sight, is on its way out as a card: the
     * row a tap or a pull is waiting to morph into, the card a switch is waiting to send up, or
     * one of the stack island's other rows while the island opens.
     */
    private fun rowComingOut(c: View): Boolean {
        fun top(key: String?): View? = when (key) {
            null -> null
            MUSIC_ISLAND -> transitionHeader()
            else -> findRow(key)?.second
        }
        if (rowWaitKey != null && top(rowWaitKey) === c) return true
        val pending = exchange?.pending
        if (pending != null && top(pending) === c) return true
        return c in pileRows && !stackGoingHome()
    }

    /** See stackContentSource. */
    var contentTopFrom = "-"
        private set

    private fun shortName(v: View): String =
        rowKey(v)?.substringAfter('|')?.substringBefore('|')?.substringAfterLast('.') ?: v.javaClass.simpleName

    private fun stackTargetHeight(v: View): Float = runCatching {
        (Xp.getObjectField(Xp.callMethod(v, "getViewState")!!, "height") as Number).toFloat()
    }.getOrElse {
        runCatching { (Xp.callMethod(v, "getActualHeight") as Number).toFloat() }.getOrDefault(v.height.toFloat())
    }.takeIf { it > 0f } ?: v.height.toFloat()

    /**
     * A card that has landed where the stack is settling it stays there, drawn, while the stack
     * catches up: the rows under it go and it moves down to the same place underneath.
     */
    private var pinned: WeakReference<View>? = null
    private var pinnedLeaving: Collection<String> = emptyList()
    private var pinnedSince = 0L

    /**
     * [from]: the stack translation the morph let go of it at, when that was not where the stack
     * is settling it - a card come back down out of a turned-round exchange lands where it left.
     */
    private fun pinCard(native: View, leaving: Collection<String>, from: Float? = null) {
        unpinCard()
        pinned = WeakReference(native)
        pinnedLeaving = leaving
        pinnedSince = android.os.SystemClock.uptimeMillis()
        // Where the morph let go of it, in the stack's translation.
        val settle = native.translationY + settleDy(native, leaving)
        pinY.value = from ?: settle
        pinY.target = pinY.value
        pinY.velocity = 0f
        pinWant = pinY.value
        pinLast = 0L
        pinPrevTarget = lastStackTarget
        pinSteady = 0
        observePin(native)
        // Placed now, in the frame the morph lets go of it; the callback carries on from there.
        pinFrame.doFrame(0L)
    }

    /**
     * The list scrolled from the landed card: the pin lets go, or the card stays where it landed
     * while the rest of the list moves. Not a card frozen for a morph about to take it.
     */
    fun releaseNativeScrollPin() {
        if (pinned?.get() == null || pinnedAt != null) return
        MiniPlayerRuntime.noteTouch("pin let go for a list scroll")
        unpinCard()
    }

    /**
     * The stack island opened out as the lock screen's list, and the list scrolling: the rows'
     * places are the list's from here. A morph still bringing the rows out ends where it was
     * going, the pile comes apart into the list and the pin lets go - kept, each of them held
     * its rows against the scroll.
     */
    fun onNativeListScroll(stack: View) {
        if (stack !== notificationStack() || !LockIslands.isReleased(STACK_ISLAND)) return
        val opening = morph?.takeIf { noteMorphKey == STACK_ISLAND && it.toNative }
        val moving = exchange?.movers?.get(STACK_ISLAND)?.morph?.takeIf { it.toNative }
        val held = pinned?.get() != null && pinnedAt == null
        if (opening == null && moving == null && piles.isEmpty() && !held) return
        MiniPlayerRuntime.noteTouch("list scroll takes the rows: open=${opening != null} " +
            "move=${moving != null} pile=${piles.size} pin=$held")
        opening?.cancel()
        moving?.cancel()
        if (piles.isNotEmpty()) finishPile(true)
        // Read again: a flight ended here pins its card where it lands (the flight's onSettled).
        if (pinned?.get() != null && pinnedAt == null) unpinCard()
    }

    /** [keep]: the card stays drawn where the pin had it, for a morph taking it this frame. */
    private fun unpinCard(keep: Boolean = false) {
        val v = pinned?.get()
        pinned = null
        Gen.unframe(pinFrame)
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        pinObserved = null
        if (!keep) v?.setAnimationMatrix(null)
        pinnedDy = 0f
        pinnedAt = null
    }

    /**
     * Holds [v] drawn at the stack translation [at], whatever the stack does with it meanwhile:
     * a card about to come down, for the frames before its morph takes it.
     */
    private fun freezeCard(v: View, at: Float) {
        unpinCard(keep = true)
        pinned = WeakReference(v)
        pinnedLeaving = emptyList()
        pinnedAt = at
        pinnedSince = android.os.SystemClock.uptimeMillis()
        observePin(v)
        pinFrame.doFrame(0L)
    }

    /**
     * The pin is written again right before the frame is drawn, against the translation the
     * stack has given the row by then. Written from a frame callback alone, it was against the
     * last frame's: the stack's animator moves the row later in the same frame, and while it
     * slid the row 20px a frame toward the card, the card was drawn off by that much and came
     * back - the shake after every switch (2026-09-25), with the held place itself never moving.
     */
    private val pinPreDraw = android.view.ViewTreeObserver.OnPreDrawListener { android.os.Trace.beginSection("MC pinPreDraw"); try {
        val v = pinned?.get()
        if (v != null) {
            val dy = (pinnedAt ?: pinY.value) - v.translationY
            if (kotlin.math.abs(dy - pinnedDy) > 0.01f) {
                pinnedDy = dy
                pinMatrix.setTranslate(0f, dy)
                v.setAnimationMatrix(pinMatrix)
            }
        }
        true
    } finally { android.os.Trace.endSection() } }

    private var pinObserved: WeakReference<View>? = null

    private fun observePin(v: View) {
        pinObserved?.get()?.let { Gen.unpreDraw(it, pinPreDraw) }
        Gen.preDraw(v, pinPreDraw)
        pinObserved = WeakReference(v)
    }

    /**
     * [card] - the media card or a view in it - is held below where the stack has it now: by the
     * pin, or as the card an exchange is taking the music up into. The cover lays its artwork
     * out above the card's top, read on screen, which is the stack's place for it: with the row
     * coming down still in the stack under it, a row higher. Into the cover out of an exchange,
     * the artwork flew to a size for that higher card and grew as the stack caught up (filmed
     * 2026-09-25). Null when it is neither.
     */
    fun cardHeldDy(card: View): Float? {
        pinned?.get()?.takeIf { viewWithin(it, card) }?.let { v ->
            return if (pinnedAt != null) pinnedAt!! - v.translationY else pinY.value - v.translationY
        }
        // The music going up into the cover lands where the stack is settling its card. Coming down
        // out of it - or, turned round, going back up into it - it leaves from and lands at where
        // it was held; the row going up pushes it up in the stack meanwhile, 180px, and the cover
        // was laid out small for that.
        val m = exchange?.movers?.get(MUSIC_ISLAND)?.takeIf { it.morph != null && viewWithin(it.native, card) }
            ?: return null
        return (if (m.fromCard) m.cardAt else m.settleAt) - m.native.translationY
    }

    private fun viewWithin(ancestor: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** A frozen card's stack translation (freezeCard); null for one landing (pinCard). */
    private var pinnedAt: Float? = null

    /** The pin's offset this frame, for a morph taking the card from where it is drawn. */
    private var pinnedDy = 0f

    private val pinMatrix = Matrix()

    /**
     * A landed card, held: it goes on its own spring toward where it is to be - the estimate
     * (rows still leaving) until the stack has taken them away and laid the rest out again, then
     * the stack's own target. The estimate can be a few pixels off the stack's final layout; held
     * at it until the hold ran out, the card then jumped those pixels (2026-09-25).
     */
    private val pinY = Jelly(PIN_RESPONSE, HIDDEN_DAMPING)
    private var pinWant = 0f
    private var pinLast = 0L

    /**
     * Where the landed card is to be drawn. It landed where the stack settles it (measured the
     * same, 2024 both, 2026-09-25), so it stays there: the frames while the rows leave are not
     * read for it - the stack's children, their targets and the rows going out are not all
     * updated in one frame, and followed frame by frame the card dipped 12px and came back. Only
     * a target the stack has held for a few frames with nothing leaving is taken, and only if it
     * is somewhere else (the stack itself changed meanwhile).
     */
    private fun landingDy(v: View, now: Long): Float {
        settleDy(v, pinnedLeaving)
        val st = lastStackTarget
        if (lastGone == 0 && kotlin.math.abs(st - pinPrevTarget) < 0.5f) pinSteady++ else pinSteady = 0
        pinPrevTarget = st
        if (pinSteady >= PIN_STEADY_FRAMES) pinWant = st
        val dt = if (pinLast == 0L) 1f / 120f else ((now - pinLast) / 1000f).coerceIn(0f, 0.05f)
        pinLast = now
        pinY.target = pinWant
        pinY.step(dt)
        return pinY.value - v.translationY
    }

    private var pinPrevTarget = 0f
    private var pinSteady = 0

    private val pinFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC pinFrame"); try {
            val v = pinned?.get() ?: return
            val at = pinnedAt
            val now = android.os.SystemClock.uptimeMillis()
            val dy = if (at != null) at - v.translationY else landingDy(v, now)
            val waited = now - pinnedSince
            val settled = at == null && kotlin.math.abs(dy) < 0.5f && pinY.atRest() &&
                kotlin.math.abs(pinWant - v.translationY) < 0.5f && pinSteady >= PIN_STEADY_FRAMES
            if (!v.isAttachedToWindow || waited > PIN_MS || settled && waited > PIN_SETTLE_MS) {
                trace("pin off ${if (settled) "settled" else if (waited > PIN_MS) "timeout" else "detached"} " +
                    "dy=${"%.1f".format(dy)} $lastSettle")
                unpinCard()
                return
            }
            pinnedDy = dy
            pinMatrix.setTranslate(0f, dy)
            v.setAnimationMatrix(pinMatrix)
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * [key] goes out as its card: the music is chosen as the media card, a notification is
     * given back to the stack. The card is kept out of sight until a morph takes it.
     */
    private fun letOut(key: String) { android.os.Trace.beginSection("MC letOut"); try {
        if (key != MUSIC_ISLAND) {
            LockIslands.release(key)
            return
        }
        val token = controller?.sessionToken ?: return
        MiniPlayerRuntime.chooseNative(token)
        updateVisibility()
        transitionHeader()?.let(::hideRow)
    } finally { android.os.Trace.endSection() } }

    /**
     * A row given back to its island before its morph home has landed, and where it was drawn
     * then. The stack lays out the rest without it at once - the card coming out lands where the
     * stack really puts it, nothing estimated - while the row, a transient view now, is drawn on
     * for the morph and held where it was (2026-09-26, the user's plan B).
     */
    private class Kept(val row: View, val ty: Float, val alpha: Float, val since: Long)

    private val kept = HashMap<String, Kept>()

    /** [key] - a notification, or the stack island with all of its rows - back into the row now. */
    private fun takeInEarly(key: String) {
        if (key == MUSIC_ISLAND || !LockIslands.isReleased(key)) return
        val now = android.os.SystemClock.uptimeMillis()
        val rows = StringBuilder()
        for (member in LockIslands.membersOf(key)) {
            val row = findRow(member)?.second ?: continue
            val held = pinned?.get() === row
            kept[member] = kept.values.firstOrNull { it.row === row }
                ?: Kept(row, row.translationY + if (held) pinnedDy else 0f, row.alpha, now)
            MiniPlayerRuntime.holdTransient(row)
            rows.append(' ').append(shortName(row)).append('@').append(row.translationY.toInt())
        }
        trace("early in ${shortKey(key)}$rows")
        takeIn(key)
    }

    /** [key]'s kept rows are the stack's again: landed home, or let out once more. */
    private fun letGo(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val rows = keys.mapNotNull { kept.remove(it)?.row }.distinct()
        for (row in rows) if (kept.values.none { it.row === row }) MiniPlayerRuntime.releaseTransient(row)
        trace("let go ${shortKey(key)} rows=${rows.size}")
    }

    /**
     * [key] was given back early and is turning round: out again. Its rows stay held until the
     * stack has them back as rows - it moves a transient view back itself when it re-adds it. Let
     * go at once, a removal the stack had asked for meanwhile ran then and there: the row left the
     * window under the morph turning it round, and the stack island's other rows jumped out
     * whole where they were (2026-09-26).
     */
    private fun letOutAgain(key: String) {
        val keys = (LockIslands.membersOf(key) + key).filter { it in kept }
        if (keys.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        for (row in keys.mapNotNull { kept.remove(it)?.row }.distinct()) {
            if (kept.values.none { it.row === row }) reAdding[row] = now
        }
        letOut(key)
        trace("out again ${shortKey(key)} rows=${reAdding.size}")
    }

    /** Rows let out again, held until the stack has them back (holdKept). */
    private val reAdding = HashMap<View, Long>()

    /**
     * An island turned round on its way home again is told to the stack only once it has kept
     * to that way for TURN_SETTLE_MS; turned back out meanwhile, nothing is told at all. Every release and recapture rebuilds the stack's whole list (ShadeListBuilder, 5-10ms,
     * and two or three smaller passes after it), and taken at once on every turn, a run of taps
     * mid-switch rebuilt it every 150-300ms: the frames dropped with each (traced 2026-09-26).
     * The first move - a tap, a card let go - still goes at once; landing, the island's last
     * way is told at once too (flushTurn).
     */
    private val turnWanted = HashMap<String, Boolean>()

    private val turnApply = Runnable {
        val due = HashMap(turnWanted)
        turnWanted.clear()
        for ((key, out) in due) tellTurn(key, out)
    }

    private fun turnTo(key: String, out: Boolean) {
        // Out again at once: the stack puts its row back with an animation of its own, and told
        // 200ms late, that played mid-way through the card's way up - the stopwatch flashed on
        // a second tap (2026-09-26). At once, the card is still the pill then. Home again is
        // what waits: the row is held where it is drawn meanwhile (holdKept), and a turn out
        // before the wait is up finds it still out, with nothing to tell.
        if (out) {
            turnWanted.remove(key)
            tellTurn(key, true)
            return
        }
        turnWanted[key] = out
        handler.removeCallbacks(turnApply)
        handler.postDelayed(turnApply, TURN_SETTLE_MS)
        trace("turn ${shortKey(key)} ${if (out) "out" else "home"} held")
    }

    /** [key]'s turn not told yet, told now: it has landed, or is being taken over. */
    private fun flushTurn(key: String) {
        val out = turnWanted.remove(key) ?: return
        tellTurn(key, out)
    }

    private fun tellTurn(key: String, out: Boolean) {
        // Back the way it was before the turns: nothing to tell the stack. But a page behind it
        // may have been taken over meanwhile - the map's island going home, the countdown's out,
        // the map's turned out again before its home was told: its row never went back, and the
        // countdown's home, told later, closed the only page open, down to the plain wallpaper
        // (2026-09-30). Released again, it takes its page back; nothing else happens to a key
        // that is released already.
        if (key != MUSIC_ISLAND && out == LockIslands.isReleased(key)) {
            if (out) LockIslands.release(key)
            return
        }
        if (out) {
            letOutAgain(key)
            if (key != MUSIC_ISLAND && !LockIslands.isReleased(key)) letOut(key)
        } else takeInEarly(key)
    }

    /**
     * The kept rows, each frame before it is drawn: where they were when given back, as they
     * were. The stack animates a removed row away on its own; the morph has it now.
     */
    private fun holdKept() {
        val now = android.os.SystemClock.uptimeMillis()
        if (reAdding.isNotEmpty()) {
            val it = reAdding.entries.iterator()
            while (it.hasNext()) {
                val (row, since) = it.next()
                val back = (row.parent as? ViewGroup)?.indexOfChild(row)?.let { i -> i >= 0 } == true
                if (back) MiniPlayerRuntime.forgetTransient(row)
                else if (now - since > REEADD_MAX_MS) MiniPlayerRuntime.releaseTransient(row)
                else continue
                trace("re-added ${shortName(row)} back=$back")
                it.remove()
            }
        }
        if (kept.isEmpty()) return
        val stale = kept.filter { (_, k) -> !k.row.isAttachedToWindow || now - k.since > KEPT_MAX_MS || !Main.keyguardLocked() }
        for (key in stale.keys) {
            trace("kept ${shortKey(key)} dropped: attached=${stale[key]?.row?.isAttachedToWindow}")
            letGo(key)
        }
        for (k in kept.values.distinctBy { System.identityHashCode(it.row) }) {
            if (k.row.translationY != k.ty) k.row.translationY = k.ty
            if (k.row.alpha != k.alpha) k.row.alpha = k.alpha
        }
    }

    /** ...and comes back into the row. */
    private fun takeIn(key: String) { android.os.Trace.beginSection("MC takeIn"); try {
        if (key == MUSIC_ISLAND) controller?.sessionToken?.let(MiniPlayerRuntime::chooseMini)
        else LockIslands.recapture(key)
    } finally { android.os.Trace.endSection() } }

    /** A notification taken back in keeps its place until the stack has given it back. */
    private fun markReturning(key: String) {
        returning[key] = android.os.SystemClock.uptimeMillis()
        // Asked again once the wait is up, in case the stack never gives it back.
        handler.postDelayed({ if (key in returning) refresh() }, RETURN_WAIT_MS + 50L)
    }

    /**
     * The super island's expanded state switching, as its plugin runs it (read 2026-09-25): a tap
     * on an island in the row sends it out as its card and brings the one out now back in, and
     * the row reseats around the two (reseat). Every island on its way between its place in the
     * row and its card is a [Mover]: a stand-in view on a MiniCardMorph of its own, on its own
     * spring.
     *
     * The super island takes a tap at any moment - DynamicIslandContentView.canClick does not wait
     * for its animations - gives it to whoever holds the place tapped (performClick, by the
     * place's rest frame), reseats the row at once, and every island turns toward its new end from
     * where it is and as fast as it is going (IslandTransitionExecutor: folme.to from the current
     * values, no start state). So does this: a mover turns round (MiniCardMorph.aim keeps the
     * spring's speed) and its row end springs to its new place; an island at rest in the row moves
     * with a switch of the pill and the small island (startSwap). The pill and the small island
     * are always the seats' islands, out of sight while the island they hold is still on its way
     * to them, and take over from it on its last stretch, as from a flight.
     *
     * The first version was one pair, the one going up leading and the one coming down following
     * it the other way; a tap on the place turned the two round, a tap anywhere else finished the
     * pair where it was headed at once and started the next - a jump on every such tap.
     */
    private class Mover(val key: String, val view: MiniPlayerView, val native: View) {
        var morph: MiniCardMorph? = null
        /** Its place in the row, from the seats: where it lands once it is headed home. */
        var place = LAND_PILL
        /** The row end on screen: springs from where it was to a new place, on CHANGE_EASE. */
        var endFrom: CoverMorphMotion.Box? = null
        var endTo: CoverMorphMotion.Box? = null
        val endT = Jelly(SWAP_RESPONSE, SWAP_DAMPING).apply { value = 1f; target = 1f }
        /** 0 a pill, 1 the small island's circle: the row end's shape, on the same spring. */
        val round = Jelly(SWAP_RESPONSE, SWAP_DAMPING)
        var endLast = 0L
        /** Came down out of its card: the card is held where it left from and, turned round, lands there. */
        var fromCard = false
        var cardAt = 0f
        /** The stack translation its card is landing at going up, as its morph last read it. */
        var settleAt = 0f
        /** Its card's end is the cover, not the media card chosen. */
        var cover = false
        /** The place's island has taken over from it on its last stretch home. */
        var landed = false
        /** The place it left the row from, for a pull down to bring it back there. */
        var origin = LAND_HIDDEN
        /** What its last trace line was about (moverFrame). */
        var traced = ""
        /** The stack translation it is held at on its way home; null to take it where it is drawn. */
        var homeAt: Float? = null
        /** The offset its card's end had last frame, so a turn starts from where it is drawn. */
        var lastDy = 0f
        val headedHome: Boolean get() = morph?.toNative == false
    }

    private class Switch {
        val movers = LinkedHashMap<String, Mover>()
        /** The island out as its card, or on its way there: the last one tapped. */
        var expanded: String? = null
        /** The row as the last tap reseated it. */
        var seats: Seats? = null
        /** A tapped island whose card is being laid out; nothing moves for it till it is. */
        var pending: String? = null
        var pendingPill = false
        var pendingCover = false
        var waitSince = 0L
        fun has(key: String) = key == expanded || key in movers || key == pending
        fun keys(): List<String> = listOfNotNull(expanded) + movers.keys + listOfNotNull(pending)
        fun homeKeys(): List<String> = movers.values.filter { it.headedHome }.map { it.key }
    }

    private var exchange: Switch? = null

    /** The island a place holds is still on its way to it: the place is out of sight till it lands. */
    private fun switchHolds(key: String?): Boolean {
        val m = exchange?.movers?.get(key ?: return false) ?: return false
        return m.headedHome && !m.landed
    }

    private fun exchangeHoldsPill(): Boolean = exchange?.let { switchHolds(it.seats?.big) } == true

    private fun exchangeHoldsSmall(): Boolean = exchange?.let { switchHolds(it.seats?.small) } == true

    fun exchangeRunning(): Boolean = exchange != null

    /** [up] - the pill's island or the small one - goes out in place of the one out now. */
    private fun startExchange(up: String, toCover: Boolean = false): Boolean { android.os.Trace.beginSection("MC startExchange"); try {
        val down = expandedKey()
        val busy = exchange != null || morph != null || noteMorphKey != null || flight != null || group != null
        if (down == null || down == up || busy) {
            MiniPlayerRuntime.noteTouch("exchange refused: out=${down?.takeLast(6)} busy=$busy")
            return false
        }
        val pill = player?.takeIf { it.visibility == View.VISIBLE } ?: return false
        val inPill = selectedIsland == up
        if (!inPill && smallKey != up) return false
        endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        pill.clearNudge()
        val x = Switch()
        x.expanded = down
        x.seats = Seats(selectedIsland, smallKey)
        exchange = x
        requestUp(x, up, inPill, toCover && up == MUSIC_ISLAND)
        return true
    } finally { android.os.Trace.endSection() } }

    /**
     * [key] - in the big place if [fromPill], else the small one - asked out as its card. On its
     * way home still, its card is out yet and it turns round now. Else its card is let out, and
     * the row reseats and everything moves once that card is laid out (switchWait): meanwhile the
     * island stays drawn where it is and the rest goes on as it was.
     */
    private fun requestUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean) {
        if (x.movers[key]?.morph != null) {
            if (!applyUp(x, key, fromPill, cover)) MiniPlayerRuntime.noteTouch("switch $key: no morph")
            return
        }
        // The one out resting in its card is held where it is drawn while the stack makes room
        // for the one coming out: the card the last switch pinned where the stack is settling
        // it, let go, jumped back up to wherever the stack had got to (2026-09-25).
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            nativeFor(out)?.let { native ->
                freezeCard(native, native.translationY + if (pinned?.get() === native) pinnedDy else 0f)
            }
            // Back into the row now, not once it has landed: the stack lays out the card coming
            // out without it, and that card's place is the stack's own.
            takeInEarly(out)
        }
        x.pending = key
        x.pendingPill = fromPill
        x.pendingCover = cover
        x.waitSince = android.os.SystemClock.uptimeMillis()
        if (cover) {
            // Into the cover the card is shown by the scene, not chosen: out from under the
            // pill's suppression for the morph, and out of sight until the morph takes it.
            updateNativeSuppression(false)
            transitionHeader()?.let(::hideRow)
        } else {
            letOut(key)
            if (key == STACK_ISLAND) showStackAsList("switch")
        }
        refresh()
        trace("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)} " + smallState())
        MiniPlayerRuntime.noteTouch("switch ask ${key.takeLast(6)} pill=$fromPill out=${x.expanded?.takeLast(6)}")
        Gen.unframe(switchWait)
        Gen.frame(switchWait)
    }

    /** Frame by frame until the card asked for is laid out: it has only just been let out. */
    private val switchWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC exchangeWait"); try {
            val x = exchange ?: return
            val key = x.pending ?: return
            val native = traced("MC x.findUp") { nativeFor(key) }
            native?.let(::hideRow)
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) pileGroup(key, 0f, null)
            val out = x.expanded
            // A row given back early is a transient view: detached and re-attached as one, it has
            // lost its laid-out flag and is never laid out again, and the switch waited for it
            // until it gave up - the stack island's rows gone, nothing opened (2026-09-26).
            val outReady = out == null || out in x.movers || nativeFor(out)?.let {
                it.isAttachedToWindow && (it.isLaidOut || kept.values.any { k -> k.row === it }) && it.width > 0
            } == true
            if (native != null && traced("MC x.ready") { rowReady(native) } && outReady) {
                x.pending = null
                if (!traced("MC x.begin") { applyUp(x, key, x.pendingPill, x.pendingCover) }) abandonUp(x, key, "no morph")
                return
            }
            if (android.os.SystemClock.uptimeMillis() - x.waitSince > ROW_WAIT_MS) {
                abandonUp(x, key, when {
                    native == null -> "no card"
                    !outReady -> "card out not ready: " + (out?.let(::nativeFor)?.let { "attached=${it.isAttachedToWindow} " +
                        "laid=${it.isLaidOut} w=${it.width} kept=${kept.values.any { k -> k.row === it }}" } ?: "none")
                    else -> "card not on screen: " + offScreenWhy(native)
                })
                return
            }
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    private fun placeOf(key: String, seats: Seats): Int = when (key) {
        seats.big -> LAND_PILL
        seats.small -> LAND_SMALL
        else -> LAND_HIDDEN
    }

    /** A place's rest frame on screen now; hiding is the super island's cutout, mid-row. */
    private fun placeBoxNow(place: Int): CoverMorphMotion.Box? = when (place) {
        LAND_PILL -> player?.restBoxOnScreen()
        LAND_SMALL -> smallBoxOnScreen()
        else -> player?.restBoxOnScreen()?.let(::cutoutBox)
    }

    /** [m]'s row end this frame, its end and shape springs stepped by the real gap. */
    private fun moverEnd(m: Mover): CoverMorphMotion.Box? {
        val now = System.nanoTime()
        if (m.endLast != 0L) {
            val dt = ((now - m.endLast) / 1e9f).coerceIn(0f, 0.05f)
            m.endT.step(dt)
            m.round.step(dt)
        }
        m.endLast = now
        val to = m.endTo ?: return m.endFrom
        val from = m.endFrom ?: return to
        return lerpBox(from, to, m.endT.value)
    }

    /**
     * [m]'s row end heads for [place]: from where it is drawn now, on CHANGE_EASE, unless
     * [snap] (a mover just made there). The shape follows on the same spring.
     */
    private fun aimEnd(m: Mover, place: Int, snap: Boolean = false, at: CoverMorphMotion.Box? = null) {
        val current = if (snap) null else moverEnd(m)
        m.place = place
        m.endTo = at ?: placeBoxNow(place)
        m.endFrom = current
        m.endT.value = if (current == null) 1f else 0f
        m.endT.velocity = 0f
        m.endT.target = 1f
        val round = if (place == LAND_HIDDEN || place == LAND_SMALL && !stackedStyle()) 1f else 0f
        if (snap) {
            m.round.value = round
            m.round.velocity = 0f
        }
        m.round.target = round
    }

    /**
     * The card's end of [m]'s morph, by the way it is headed now. Up: where the stack puts the
     * card, read live - the ones going home are out of its layout already (takeInEarly). Home:
     * held where it was drawn when it turned. Fixed by the way it was made, a card sent home and
     * turned back up flew to where it had left from, and was pulled up from there to the stack's
     * place for it once it landed (2026-09-26).
     */
    private fun moverDy(x: Switch, m: Mover): Float {
        val native = m.native
        // Asked while its morph is being made, it goes the way it was made.
        val dy = if (m.morph?.toNative ?: !m.fromCard) {
            m.homeAt = null
            settleDy(native, x.homeKeys()).also { m.settleAt = native.translationY + it }
        } else {
            val at = m.homeAt ?: (native.translationY + m.lastDy).also { m.homeAt = it }
            at - native.translationY
        }
        m.lastDy = dy
        return dy
    }

    /** [m] is not landing in its place after all: the place's island has its own frame back. */
    private fun unland(m: Mover) {
        if (!m.landed) return
        m.landed = false
        if (m.place == LAND_PILL) endPillLanding()
        else if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
        m.view.alpha = 1f
    }

    private fun moverListener(m: Mover) = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = m.key == MUSIC_ISLAND && artBridged
        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key))
                pileGroup(m.key, progress, morph.miniEndBox())
            moverFrame(m)
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (m.key == STACK_ISLAND || LockIslands.isAppGroup(m.key)) endPile(toNative)
            moverSettled(m, toNative, completed)
        }
    }

    /**
     * [key] the one asked out now, the one out till now coming back in: the row reseated, and
     * every island on its way to its new end.
     */
    private fun applyUp(x: Switch, key: String, fromPill: Boolean, cover: Boolean): Boolean {
        // Nothing out: the one that was is on its way home already (adoptNote).
        val out = x.expanded
        val oldBig = selectedIsland
        val oldSmall = smallKey
        // The pill as drawn now: an island still growing into it is taken from where it has got to.
        val pillDrawn = swap?.takeIf { !it.holdPill }?.let { lerpBox(it.pillFrom, it.pillTo, it.spring.value) }
        // The small island asked for, still shrinking into its place as a stand-in of itself (a
        // collapse onto the pill sends the pill's island there so): it goes up from the stand-in,
        // which goes - left, it drew the island a second time under its own way up.
        val ghostDrawn = swap?.takeIf { !fromPill && it.smallMode == SMALL_FROM_GHOST && it.ghostKey == key &&
            it.ghostFrom != null }?.let(::ghostSmallBox)
        if (ghostDrawn != null) endSwap()
        val seats = reseat(key, out, fromPill)
        // The one asked for: turned round on its way home, or up out of its place.
        val up = x.movers[key]
        if (up != null) {
            unland(up)
            turnTo(key, true)
            up.morph?.aim(true)
            if (up.cover) Main.miniPlayerTurnScene(true)
        } else {
            val native = nativeFor(key) ?: return false
            val view = prepareSmallView(key, pooled = true) ?: return false
            val m = Mover(key, view, native)
            m.origin = if (fromPill) LAND_PILL else LAND_SMALL
            m.cover = cover
            aimEnd(m, m.origin, snap = true, at = if (fromPill) pillDrawn ?: player?.restBoxOnScreen() else ghostDrawn)
            // Out of sight while it waited; the morph saves the card's own alpha.
            showRow(native)
            m.settleAt = native.translationY
            val morph = traced("MC x.upMorph") {
                MiniCardMorph(view, native, true, moverListener(m), landingFor(key, native),
                    { moverEnd(m) }, !fromPill, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[key] = m
            if (!traced("MC x.upStart") { morph.start() }) {
                x.movers.remove(key)
                recycleSmallView(view)
                return false
            }
        }
        if (out != null && !sendDown(x, key, out, seats)) return true
        // Everyone else on the way home: to its new place, from where it is.
        for (m in x.movers.values) {
            if (m.key == key || m.key == out || !m.headedHome) continue
            val place = placeOf(m.key, seats)
            if (place != m.place) {
                unland(m)
                aimEnd(m, place)
            }
        }
        x.expanded = key
        x.seats = seats
        seats.big?.let { selectedIsland = it }
        snapSmallOnce = true
        refresh()
        if (key == STACK_ISLAND) showStackAsList("switched out")
        traced("MC x.seat") { seatAtRest(x, oldBig, oldSmall, pillDrawn) }
        // What comes down into the pill comes down as the pill will be.
        for (m in x.movers.values) if (m.headedHome && m.place == LAND_PILL) fitToPill(m.view, seats.small != null)
        traced("MC x.after") {
            orderMovers(x)
            // The music is drawn by its view from here, not by the place it left.
            applyArtBridge()
            // A place whose island is on its way to it goes out of sight under it this frame.
            updateVisibility()
            followShortcuts()
        }
        val plan = "seats=${seats.big?.takeLast(6)}/${seats.small?.takeLast(6)} " +
            x.movers.values.joinToString(" ") { moverState(it) }
        trace("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan " + smallState())
        MiniPlayerRuntime.noteTouch("switch up=${key.takeLast(6)} down=${out?.takeLast(6)} pill=$fromPill $plan")
        return true
    }

    /**
     * [out], the one out till now, on its way to its new place: turned round on its way up, or
     * down out of its card. False when it has no card to come down from: the switch is given up.
     */
    private fun sendDown(x: Switch, key: String, out: String, seats: Seats): Boolean {
        // The one out: turned round on its way up, or down out of its card, to its new place.
        val outPlace = placeOf(out, seats)
        // Into hiding, with no place in the row: the small island pulses (ExpandedToHidden).
        if (outPlace == LAND_HIDDEN) startSmallPulse(seats.small)
        val down = x.movers[out]
        if (down != null) {
            down.homeAt = null
            down.morph?.aim(false)
            turnTo(out, false)
            if (down.cover) Main.miniPlayerTurnScene(false)
            aimEnd(down, outPlace)
        } else {
            val native = nativeFor(out)
            val view = native?.let { prepareSmallView(out, pooled = true) }
            if (native == null || view == null) {
                view?.let(::recycleSmallView)
                abandonMover(x, key)
                return false
            }
            val m = Mover(out, view, native)
            m.fromCard = true
            m.cover = out == MUSIC_ISLAND && Main.coverModeOn()
            // Leaves from where it is drawn and stays there: the row going up into the stack moves
            // it on the stack's side (the stack is laid out from the bottom up), and the pin may
            // still be holding it lower than the stack has it.
            val frozen = pinnedAt?.takeIf { pinned?.get() === native }
                ?: (native.translationY + if (pinned?.get() === native) pinnedDy else 0f)
            if (pinned?.get() === native) unpinCard(keep = true)
            m.cardAt = frozen
            m.homeAt = frozen
            aimEnd(m, outPlace, snap = true)
            val morph = traced("MC x.downMorph") {
                MiniCardMorph(view, native, false, moverListener(m), landingFor(out, native),
                    { moverEnd(m) }, outPlace != LAND_PILL, nativeDy = { moverDy(x, m) }, roundness = { m.round.value })
            }
            m.morph = morph
            x.movers[out] = m
            if (!traced("MC x.downStart") { morph.start() }) {
                x.movers.remove(out)
                recycleSmallView(view)
                abandonMover(x, key)
                return false
            }
            // Made anew on its way home - its last morph had already ended, as a turn a moment
            // after a landing does: back into the row now too, or its rows stayed in the stack
            // and the card coming out was laid out above them (2026-09-26).
            takeInEarly(out)
            // Out of the cover: the music coming down is the cover's card, and the cover goes with it.
            if (m.cover) Main.miniPlayerLeaveCover()
        }
        return true
    }

    private fun moverState(m: Mover): String = "${m.key.takeLast(6)}:" +
        (if (m.morph?.toNative == true) "card" else "p${m.place}") + (if (m.landed) "L" else "") +
        "@${"%.2f".format(m.morph?.progress ?: -1f)}"

    /**
     * The islands at rest in the row take their new places as the movers go (seatOthers of the
     * first version): the small island grows into the big place (SmallIslandToBigIsland); the big
     * one shrinks into the small place as itself (BigIslandToSmallIsland, SMALL_FROM_GHOST), or
     * into hiding with the next one out of it (BigIslandToHidden, HiddenToSmallIsland). A place a
     * mover is on its way to is its: out of sight, and the switch leaves it alone. An island still
     * growing into the pill goes on from where it has got to.
     */
    private fun seatAtRest(x: Switch, oldBig: String?, oldSmall: String?, pillDrawn: CoverMorphMotion.Box?) {
        val seats = x.seats ?: return
        val movers = x.movers
        val holdPill = seats.big != null && seats.big in movers
        val holdSmall = seats.small != null && seats.small in movers
        val bigRest = oldBig?.takeIf { it !in movers }
        val smallRest = oldSmall?.takeIf { it !in movers }
        val bigMoves = !holdPill && seats.big != bigRest
        val intoSmall = bigRest != null && seats.small == bigRest
        val bigHides = bigRest != null && bigRest != seats.big && bigRest != seats.small
        val smallChanges = !holdSmall && seats.small != smallRest
        val stillGrowing = !holdPill && pillDrawn != null
        if (!bigMoves && !intoSmall && !bigHides && !smallChanges && !stillGrowing) return
        startSwap(oldBig, if (intoSmall) null else oldSmall, kind = if (bigHides) SWAP_NEXT else SWAP_PAIR,
            intoSmall = intoSmall, holdPill = holdPill, holdSmall = holdSmall,
            pillFrom = if (!bigMoves && !holdPill) pillDrawn else null, pillStays = !bigMoves && !holdPill)
    }

    /**
     * Each in its destination's layer, as the super island stacks its islands' backgrounds - big
     * 3, expanded 2, small 1, hidden 0: one on its way to the big place over everything; one
     * going up to its card over the small island but under an island in the pill; one on its way
     * to the small place or into hiding under both. The pill and the small island are at 0, the
     * stand-ins added under them.
     */
    private fun orderMovers(x: Switch) {
        val pillShown = !exchangeHoldsPill()
        for (m in x.movers.values) {
            val z = when {
                !m.headedHome -> if (pillShown) 0f else 1f
                m.place == LAND_PILL -> 2f
                else -> -1f
            }
            if (m.view.translationZ != z) m.view.translationZ = z
        }
    }

    /** Every frame of [m]: on its last stretch home, its place's island takes over from it. */
    private fun moverFrame(m: Mover) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        val morph = m.morph ?: return
        // 1 at its card, 0 in its place.
        val p = morph.progress
        val home = !morph.toNative
        // Into hiding, nothing takes over from it: it fades out on the cutout.
        val hiding = home && m.place == LAND_HIDDEN
        val holder = when (m.place) {
            LAND_PILL -> x.seats?.big
            LAND_SMALL -> x.seats?.small
            else -> null
        }
        // Only once its end has got to the place: the place's island is at rest there.
        val landing = home && !hiding && p < FLIGHT_HANDOFF && holder == m.key && m.endT.atRest()
        if (landing != m.landed) {
            if (landing) {
                m.landed = true
                snapSmallOnce = true
            } else unland(m)
            refresh()
            orderMovers(x)
            followShortcuts()
        }
        if (m.landed) morph.containerBox()?.let { if (m.place == LAND_PILL) landOnPill(it) else landOn(it) }
        val a = if (m.landed || hiding) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, p)
            else if (m.place == LAND_SMALL && stackedStyle()) lerp(MiniPlayerGeometry.STACK_BACK_ALPHA, 1f, p)
            else 1f
        if (kotlin.math.abs(m.view.alpha - a) > 0.002f) m.view.alpha = a
        traced("MC xTrace") {
            // Only as it changes: a tenth of the way, a turn, the stack's target moving. Every
            // frame of two movers filled the whole trace with one switch (2026-09-26).
            val b = morph.containerBox()
            val mark = "${m.key}${morph.toNative}${(p * 10).toInt()}|" + lastStackTarget.toInt() / 3
            if (mark != m.traced) {
                m.traced = mark
                trace("x ${moverState(m)} box=${b?.cx()?.toInt()},${b?.y?.toInt()} " +
                    "${b?.w?.toInt()}x${b?.h?.toInt()} end=${"%.2f".format(m.endT.value)} " +
                    "at=${m.cardAt.toInt()} dty=${m.native.translationY.toInt()} " + lastSettle)
            }
        }
    }

    /** [m] is at an end: out as its card, or home in the row. */
    private fun moverSettled(m: Mover, toNative: Boolean, completed: Boolean) {
        val x = exchange ?: return
        if (x.movers[m.key] !== m) return
        flushTurn(m.key)
        x.movers.remove(m.key)
        unland(m)
        recycleSmallView(m.view)
        val key = m.key
        if (toNative) {
            // Pulled down again, it comes back where it went from.
            when (m.origin) {
                LAND_PILL -> { releasedFromPill += key; releasedFromSmall -= key }
                LAND_SMALL -> { releasedFromSmall += key; releasedFromPill -= key }
            }
            // Held where the stack is settling it until the stack has taken the others away.
            // Turned round, the card that came back up lands where it left, the rows that went
            // up on their way back out of the stack under it: held the same (2026-09-25).
            // Where the stack has it: the rows going home are out of its layout already.
            pinCard(m.native, x.homeKeys())
        } else {
            releasedFromPill -= key
            releasedFromSmall -= key
            if (key != MUSIC_ISLAND) {
                rowFor(key)?.let { hideRowUntilGone(it, key) }
                markReturning(key)
            } else transitionHeader()?.let(::showRow)
            takeIn(key)
            letGo(key)
        }
        trace("switch settled ${key.takeLast(6)} ${if (toNative) "card" else "home"} completed=$completed " +
            "left=${x.movers.size}" + if (completed) "" else " why=" + m.morph?.lastCancel)
        snapSmallOnce = true
        refresh()
        orderMovers(x)
        if (x.movers.isEmpty() && x.pending == null) endSwitch(x)
    }

    /** Everyone is at an end: the row is the seats'. */
    private fun endSwitch(x: Switch) {
        if (exchange !== x) return
        exchange = null
        endPillLanding()
        x.seats?.let { s ->
            s.big?.let { selectedIsland = it }
            preferredSmall = s.small
        }
        snapSmallOnce = true
        refresh()
        trace("exchange end " + smallState())
    }

    /**
     * A tap on the row while a switch runs, the super island's way: it goes to whoever holds the
     * place tapped - landed or still on its way there - and that island goes out as its card,
     * the one out (still going up, or resting in its card) coming back in, all from where they
     * are. Only a card still being laid out for the last tap keeps the next one waiting.
     */
    fun tapDuringExchange(small: Boolean) {
        val x = exchange ?: return
        if (x.pending != null) {
            MiniPlayerRuntime.noteTouch("exchange tap: still waiting")
            return
        }
        val seats = x.seats ?: return
        val key = (if (small) seats.small else seats.big) ?: return
        if (key == x.expanded) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!openSingleApp("tap during a switch")) openSpread("tap during a switch")
            return
        }
        trace("switch tap ${key.takeLast(6)} small=$small")
        val wasOut = x.movers[key]?.morph != null
        requestUp(x, key, fromPill = !small, cover = key == MUSIC_ISLAND)
        // The music opens the cover from any place, as from the pill (tapIsland).
        if (key == MUSIC_ISLAND && !wasOut) {
            MiniPlayerRuntime.forgetRestoreScene()
            Main.miniPlayerEnterCover()
        }
    }

    /** What a tap on an island does: the music opens the cover, a notification its row. */
    private fun tapIsland(key: String) {
        if (key == MUSIC_ISLAND) openCover() else expandNote(key)
    }

    /**
     * The music into its cover. With another island out as its card, that one comes down into
     * the music's place as the music goes up - one card at a time; it stood there beside the
     * cover's card before (2026-09-25).
     */
    private fun openCover() {
        MiniPlayerRuntime.forgetRestoreScene()
        // A notification still on its way - opening, or springing back out after a pull too
        // short to collapse it - is not "out" yet to expandedKey, and the cover came up over it
        // with the timer's card left standing in the stack under the media card (filmed
        // 2026-09-26). Taken into a switch as it is, it turns home while the music goes up, as
        // the super island does it: the next version waited for it to land and only then opened
        // the cover, one after the other.
        val moving = noteMorphKey?.takeIf { it != MUSIC_ISLAND }
        when {
            moving != null && morph != null -> {
                val m = morph!!
                trace("cover takes ${moving.takeLast(6)} in: p=${"%.2f".format(m.progress)} toRow=${m.toNative} " +
                    "out=$flightOut drag=${noteDrag != null} " + smallState())
                val inPill = selectedIsland == MUSIC_ISLAND
                val x = if (inPill || smallKey == MUSIC_ISLAND) adoptNote(moving) else null
                if (x == null) {
                    MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} moving, not taken in")
                    return
                }
                requestUp(x, MUSIC_ISLAND, inPill, cover = true)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} taken in, music up")
            }
            moving != null && rowWaitKey == moving -> {
                noteDrag = null
                abandonNoteMorph(moving)
                MiniPlayerRuntime.noteTouch("cover: ${moving.takeLast(6)} not opened")
            }
            expandedKey()?.let { it != MUSIC_ISLAND } == true -> startExchange(MUSIC_ISLAND, toCover = true)
        }
        Main.miniPlayerEnterCover()
    }

    /**
     * No card to go up into: the one asked for stays in the row, the one out stays out, and
     * whatever was already moving goes on.
     */
    private fun abandonUp(x: Switch, key: String, why: String) {
        if (exchange !== x) return
        x.pending = null
        Xp.log("MCIsland: switch $key not run: $why")
        MiniPlayerRuntime.noteTouch("exchange $why")
        abandonMover(x, key)
    }

    private fun abandonMover(x: Switch, key: String) {
        flushTurn(key)
        x.expanded?.let(::flushTurn)
        // It was given back for the other's sake: out again as it was.
        x.expanded?.takeIf { it !in x.movers }?.let(::letOutAgain)
        // The card that was to come down stays where it was held, while the row that was let out
        // for the other goes back out of the stack under it.
        x.expanded?.takeIf { it !in x.movers }?.let { out ->
            val held = pinned?.get()
            val at = pinnedAt
            if (held != null && at != null && held === nativeFor(out)) pinCard(held, listOf(key), from = at)
            else unpinCard()
        }
        x.movers.remove(key)?.let { m ->
            m.morph?.cancel()
            recycleSmallView(m.view)
        }
        if (key != MUSIC_ISLAND) rowFor(key)?.let { hideRowUntilGone(it, key) }
        else transitionHeader()?.let(::showRow)
        takeIn(key)
        if (key != MUSIC_ISLAND) markReturning(key)
        refresh()
        if (x.movers.isEmpty() && x.pending == null) {
            exchange = null
            refresh()
        }
    }

    /**
     * The notification opening or folding back as the flight, made a [Mover] of a new switch as
     * it is: its morph handed over (MiniCardMorph.handTo) at its progress and speed, the flight
     * its stand-in, its row end where the flight was headed. Out on its way up, it is the switch's
     * island out - the next tap turns it home. On its way home, nothing is out. Null with no
     * flight to take.
     */
    private fun adoptNote(key: String): Switch? {
        val running = morph ?: return null
        val view = flight ?: return null
        if (exchange != null || rowWaitKey != null) return null
        val native = running.card
        flushTurn(key)
        // Let go by no finger yet: from here the switch moves it, on its spring.
        if (running.held) running.release(running.toNative, 0f)
        noteDrag = null
        // Pulled and not let go, it was not out yet: its home is given up now, as a tap's is.
        if (running.toNative) setFlightOut(true) else takeInEarly(key)
        val home = if (flightHome == HOME_PILL) LAND_PILL else LAND_SMALL
        val end = if (home == LAND_PILL) player?.restBoxOnScreen() else smallBoxOnScreen()
        // The flight's landing - the pill or the small island in its shape - is the switch's to
        // make again (moverFrame), once the seats have it.
        endPillLanding()
        if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
        val x = Switch()
        val m = Mover(key, view, native)
        m.origin = home
        m.fromCard = !running.toNative
        m.lastDy = if (running.toNative) settleDy(native, emptyList()) else 0f
        m.settleAt = native.translationY + m.lastDy
        aimEnd(m, home, snap = true, at = end)
        m.morph = running
        running.handTo(moverListener(m), { moverEnd(m) }, { moverDy(x, m) }, { m.round.value })
        x.movers[key] = m
        x.expanded = key.takeIf { running.toNative }
        x.seats = Seats(selectedIsland, smallKey)
        if (!running.toNative && home == LAND_PILL) fitToPill(view, smallKey != null)
        // Nothing is the flight's any more.
        morph = null
        noteMorphKey = null
        flight = null
        flightFromSmall = false
        flightHome = HOME_SMALL
        flightOut = false
        flightLanding = false
        exchange = x
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        player?.clearNudge()
        trace("adopt ${key.takeLast(6)} ${moverState(m)} seats=${x.seats?.big?.takeLast(6)}/" +
            "${x.seats?.small?.takeLast(6)} " + smallState())
        refresh()
        orderMovers(x)
        updateVisibility()
        followShortcuts()
        return x
    }

    // ------------------------------------------------------------ the whole row out as the stack's list

    /**
     * The stack island opened ColorOS's way (2026-09-30, the user's call). Opened on its own -
     * the ordinary notifications out of the fold, the other islands left in the row - a card
     * then opened from one of those came up over the list. ColorOS's list state has no capsules
     * at all: so here the whole row goes out as the lock screen's own cards while the stack is its
     * list. The music goes up into the media card, each focus island into its row, the islands
     * with no place in the row fade in with the list, and the stack island's own place fades as
     * its rows come out of the fold. Pulled down, the list folds back and every card comes down
     * into its own place - the old "each to its own island" (Group), for the whole row.
     *
     * One progress runs all of it: where the stack's scroll is between the islands' end (the
     * folded stack, NUMBER) and the cards' end (the list), read every frame. Opening, that is the
     * stack's own scroll to its list; closing, the finger's pull and the stack's own settling
     * after it (NumState's hands-up keeps it from resting half way). So the finger, the list and
     * every island move as one and turn as one, with no spring of their own to disagree.
     */
    /**
     * [wasOut]: already out as its card when the spread began - a focus island tapped open before
     * the stack island was. It is in the list as it is, and folding it goes home with the rest;
     * it stayed behind as a card over the folded stack before (the user, 2026-09-30).
     */
    private class SpreadItem(val key: String, val seat: Int, val wasOut: Boolean = false) {
        /** The stack island itself, into its newest notification's row (stackLeadKey). */
        val lead get() = key == STACK_ISLAND
        /** Its card: the media card, or the notification's row. */
        var native: View? = null
        /** The island's view morphing: the pill, or a stand-in for the small island's place. */
        var view: MiniPlayerView? = null
        var standIn = false
        var morph: MiniCardMorph? = null
    }

    private class Spread(val items: List<SpreadItem>, val stackSeat: Int, val stacked: Boolean) {
        var phase = SPREAD_WAIT
        var since = 0L
        /** The stack's scroll at the islands' end and at the cards' end of this motion. */
        var islandsY = 0
        var cardsY = 0
        /** Frames the scroll has stood still, nothing moving it. */
        var still = 0
        var lastY = Int.MIN_VALUE
        var progress = 0f
        var rawProgress = 0f
        /** The stacked cards may need to catch up when the OEM scroll jumps to its end. */
        var visualAt = 0L
        /** Stuck between the two, the stack was sent to the nearer end once. */
        var nudged = false
        /** The stack's measured focus rows before the spread's came in (NumState.focusCount). */
        var focusBefore: Int? = null
        /** Open, the list's place was read again and the stack sent there once. */
        var reaimed = false
        /** The stack's own drag was on it last frame; and the let-go already sent it home. */
        var wasDragged = false
        var letGoSent = false
        /** Pulled open by a finger still on the island: it scrolls the stack (followFinger). */
        var finger: SpreadFinger? = null
        var collapseFinger: SpreadCollapseFinger? = null
        /** The screen went off or into the doze while it was open; and when it came back on. */
        var slept = false
        var wokeAt = 0L
        var lastFrameAt = 0L
    }

    private class SpreadCollapseFinger(val startY: Float, val startProgress: Float, val range: Float) {
        var y = startY
        var ended = false
        var target = 1f
        fun progress(): Float = if (ended) target
            else MiniPlayerStackGesture.collapseProgress(startProgress, y - startY, range)
    }

    /**
     * A pull up on the stack island past the threshold: from there the finger has the list, as
     * a pull on the music has its card - it scrolls the stack, and every island rides on the
     * scroll. Opened on the stack's own scroll instead, the finger was heard no more: pulled
     * back down, the list went on opening, stopped half way under the held finger and went to
     * an end on its own a moment later (the user, 2026-09-30).
     */
    private class SpreadFinger(val startY: Float) {
        var y = startY
        /** The pull at the moment the stack started to follow: its scroll's zero. */
        var base = Float.NaN
        var ended = false
        var toList = false
        fun pulled() = startY - y
    }

    private var spread: Spread? = null

    /**
     * The list was open when the phone was unlocked: it opens again on the next lock screen, once
     * the row is up with the stack island in it ([reopenKeptList]). Folded, it is not kept.
     */
    private var listKept = false
    private var listKeptShownAt = 0L

    private fun reopenKeptList(shown: Boolean) {
        if (!listKept) return
        val now = android.os.SystemClock.uptimeMillis()
        val awake = !MiniPlayerScene.aodActive && Main.screenOnCached()
        if (!shown || !awake || spread != null || player?.visibility != View.VISIBLE) {
            listKeptShownAt = 0L
            return
        }
        if (STACK_ISLAND in islandKeys && LockIslands.foldsNatively()) {
            listKept = false
            listKeptShownAt = 0L
            handler.post { if (spread == null) openSpread("kept over the unlock") }
            return
        }
        // Up without the stack island - its notifications went meanwhile: nothing to open.
        if (listKeptShownAt == 0L) listKeptShownAt = now
        else if (now - listKeptShownAt > LIST_KEPT_WAIT_MS) listKept = false
    }

    fun spreadCollapseAt(x: Float, y: Float): Boolean {
        val s = spread ?: return false
        if (s.phase != SPREAD_OPEN && s.phase != SPREAD_CLOSING) return false
        val stack = notificationStack() ?: return false
        val rect = android.graphics.Rect()
        return (0 until stack.childCount).any { i ->
            val row = stack.getChildAt(i)
            (row.javaClass.name.contains("ExpandableNotificationRow") ||
                s.items.any { it.native === row }) && row.isShown && row.alpha > 0f &&
                row.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())
        }
    }

    fun beginSpreadCollapse(startY: Float): Boolean {
        val s = spread ?: return false
        if (s.phase != SPREAD_OPEN && s.phase != SPREAD_CLOSING) return false
        if (s.phase == SPREAD_OPEN) {
            s.islandsY = NumState.scrollTo("NUMBER") ?: s.islandsY
            startSpreadMorphs(s, toCards = false)
            s.phase = SPREAD_CLOSING
        }
        s.collapseFinger = SpreadCollapseFinger(startY, s.progress, collapseRange(s))
        s.since = android.os.SystemClock.uptimeMillis()
        s.visualAt = s.since
        s.still = 0
        s.nudged = false
        s.letGoSent = true
        spreadTrace("spread collapse owned by islands")
        return true
    }

    /**
     * How far a pull down takes the list home. Stacked, the PR's 220dp. Plain, from the stack
     * island's card to its place in the row: the card stays under the finger. Left to the
     * stack's own drag, a short pull down was the stack's fold, which ran home on its own spring
     * and left the finger behind (#73, filmed 2026-10-14).
     */
    private fun collapseRange(s: Spread): Float {
        val fallback = dp(220f).toFloat()
        if (s.stacked) return fallback
        val pill = player?.restBoxOnScreen() ?: return fallback
        val row = (s.items.firstOrNull { it.lead } ?: s.items.firstOrNull { it.native != null })
            ?.native?.takeIf { it.isAttachedToWindow } ?: return fallback
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(dp(120f).toFloat())
    }

    fun moveSpreadCollapse(y: Float) {
        val s = spread ?: return
        val finger = s.collapseFinger?.takeIf { !it.ended } ?: return
        finger.y = y
        val p = finger.progress()
        NumState.setScroll((s.islandsY + (s.cardsY - s.islandsY) * p).roundToInt())
    }

    fun endSpreadCollapse(velocityY: Float, cancelled: Boolean) {
        val s = spread ?: return
        val finger = s.collapseFinger?.takeIf { !it.ended } ?: return
        finger.target = if (MiniPlayerStackGesture.commits(
                finger.y - finger.startY, velocityY, density(), cancelled)) 0f else 1f
        finger.ended = true
        s.still = 0
        NumState.goTo(if (finger.target == 0f) "NUMBER" else "LIST", why = "island collapse released")
    }

    /** For `op mini`. */
    private fun describeSpread(): String = spread?.let { s ->
        "spread ph=${s.phase} p=${"%.2f".format(s.rawProgress)}/${"%.2f".format(s.progress)} " +
            "y=${s.lastY} ${s.islandsY}->${s.cardsY} " +
            "stack=${s.stackSeat} focus=${s.focusBefore}->${NumState.focusCount()} items=" + s.items.joinToString(",") {
                "${it.key.takeLast(6)}:${it.seat}${if (it.morph?.active == true) "m" else ""}" +
                    "${if (it.native != null) "n" else ""}${if (it.wasOut) "o" else ""}"
            }
    } ?: "spread none"

    private val spreadListener = object : MiniCardMorph.Listener {
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) = true
        override fun artBridged() = false
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {}
    }

    /** Even a lone stacked card uses the list whose downward pull returns it to its island. */
    fun beginStackSpread(startY: Float): Boolean {
        if (!stackedStyle() || islandKeys.isEmpty() || spread != null ||
            morph != null || exchange != null || noteMorphKey != null || group != null ||
            flight != null || player?.visibility != View.VISIBLE) return false
        // The music alone, and no notification in the stack either: there is no list to spread
        // out to. The stack read its fold again a moment after the lift, the spread folded with
        // it, and the card went back into the pill by itself every time (Xiaomi 13, 2026-10-14).
        // Pulled up as the row style pulls it: into the card, and the card chosen.
        if (islandKeys.all { it == MUSIC_ISLAND } && !stackHasRows()) return false
        openSpread("stack swipe", SpreadFinger(startY))
        return spread != null
    }

    /** Any notification row in the lock screen's stack, folded away or not - the music's aside. */
    private fun stackHasRows(): Boolean {
        val stack = notificationStack() ?: return true
        for (i in 0 until stack.childCount) {
            val row = stack.getChildAt(i)
            if (!row.javaClass.name.contains("ExpandableNotificationRow")) continue
            val media = runCatching {
                val entry = Xp.callMethod(row, "getEntry")
                (Xp.getObjectField(entry, "mSbn") as? android.service.notification.StatusBarNotification)
                    ?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            }.getOrDefault(false)
            if (!media) return true
        }
        return false
    }

    /**
     * The stack island tapped with one app's notifications in it: that app's newest one opened,
     * as a tap on its own row opens it - the row's click (NotificationClicker), which wakes,
     * asks the keyguard to go first where it has to, and starts the notification's intent. A
     * spread out to a list of one app's notifications was a step on the way to the same place.
     * False when there is more than one app, nothing to open, or no row to click: then it opens.
     */
    private fun openSingleApp(why: String): Boolean {
        val key = LockIslands.stackSingleAppLead() ?: return false
        val stack = notificationStack() ?: return false
        var row: View? = null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            // Its own row, never its group's: the group's click expands the group instead.
            row = if (rowKey(child) == key) child else childRows(child).firstOrNull { rowKey(it) == key }
            if (row != null) break
        }
        if (row == null || !row.hasOnClickListeners()) {
            MiniPlayerRuntime.noteTouch("open app ($why): no row for ${key.takeLast(6)}, spread instead")
            return false
        }
        MiniPlayerRuntime.noteTouch("open app ($why): ${key.substringAfter('|').substringBefore('|')}")
        // The row's own listener (ExpandableNotificationRowInjector$1) only posts the click to a
        // flow, whose collector dropped it for a row folded away: tapped, nothing happened
        // (2026-10-07). NotificationClicker, which the flow ends in, is the injector's
        // clickListener (NotificationRowBinderImpl): called straight, as the flow would.
        val clicker = runCatching {
            Xp.getObjectField(Xp.callMethod(row, "getInjector"), "clickListener") as? View.OnClickListener
        }.getOrNull()
        if (clicker != null) clicker.onClick(row) else row.performClick()
        return true
    }

    /**
     * The stack island tapped or pulled open: the whole row out. With another motion under way -
     * a card out, a switch, a flight - only the stack opens, as it did before (openStack).
     */
    private fun openSpread(why: String, finger: SpreadFinger? = null) { android.os.Trace.beginSection("MC openSpread"); try {
        if (spread != null) return
        val busy = morph != null || exchange != null || noteMorphKey != null || group != null ||
            flight != null
        if (busy || player?.visibility != View.VISIBLE) {
            MiniPlayerRuntime.noteTouch("spread refused ($why): busy=$busy; the stack alone")
            LockIslands.openStack(why)
            return
        }
        endSwap()
        endRow()
        resetIslandDrag()
        clearSmallNudge()
        val big = selectedIsland
        val small = smallKey
        fun seatOf(key: String) = when (key) {
            big -> SEAT_BIG
            small -> SEAT_SMALL
            else -> SEAT_HIDDEN
        }
        // A page up behind the lock screen (高德's map, a countdown) goes: the list opens on the
        // plain lock screen, the page's notification one of its cards (the user, 2026-09-30).
        LockIslands.closeSceneForList()
        // Out as their cards already: in the list as they are, and home with the rest when it
        // folds - into no place of the row's, fading with the list as the placeless ones do.
        val out = LockIslands.releasedKeys().filter {
            it != STACK_ISLAND && it !in islandKeys && rowFor(it) != null
        }
        val items = islandKeys.filter { it != STACK_ISLAND }.flatMap { key ->
            listOf(SpreadItem(key, seatOf(key))) +
                if (LockIslands.isAppGroup(key)) LockIslands.membersOf(key)
                    .map { SpreadItem(it, SEAT_HIDDEN) } else emptyList()
        } + out.map { SpreadItem(it, SEAT_HIDDEN, wasOut = true) }
        val stackSeat = if (STACK_ISLAND in islandKeys) seatOf(STACK_ISLAND) else SEAT_NONE
        // The stack island goes out as the others do, into its newest notification's row as the
        // list opens. Without it the island only faded where it stood while the rows came out
        // of the fold (#73): the one island in the row with no way out.
        val lead = if (stackSeat == SEAT_BIG || stackSeat == SEAT_SMALL)
            listOf(SpreadItem(STACK_ISLAND, stackSeat)) else emptyList()
        val s = Spread(lead + items, stackSeat, stackedStyle())
        s.since = android.os.SystemClock.uptimeMillis()
        // Read before the spread's rows come in (setSpread runs the pipeline on the next frame).
        s.focusBefore = NumState.focusCount()
        s.finger = finger
        spread = s
        // A card held where it landed goes with the list from here.
        releaseNativeScrollPin()
        player?.setInteractionsEnabled(false)
        // Every focus notification's row into the stack, out of sight till its morph takes it.
        LockIslands.setSpread(true)
        spreadTrace("spread asked ($why): " + describeSpread())
        MiniPlayerRuntime.noteTouch("spread ($why) items=${items.size} stack=${s.stackSeat}")
        Gen.unframe(spreadFrame)
        Gen.frame(spreadFrame)
    } finally { android.os.Trace.endSection() } }

    private val spreadFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC spread"); try {
            val s = spread ?: return
            if (destroyed || !Main.keyguardLocked() || MiniPlayerScene.keyguardGoingAway) {
                // Open, it opens again on the next lock screen (the user, 2026-10-24).
                listKept = s.phase == SPREAD_OPEN && s.progress >= 0.5f
                endSpread(folded = s.progress < 0.5f, why = "lock screen gone")
                return
            }
            // No frames for a while: the screen was off.
            val frameAt = android.os.SystemClock.uptimeMillis()
            if (s.lastFrameAt != 0L && frameAt - s.lastFrameAt > SPREAD_SLEEP_GAP_MS) s.slept = true
            s.lastFrameAt = frameAt
            when (s.phase) {
                SPREAD_WAIT -> waitSpread(s)
                SPREAD_OPEN -> watchOpen(s)
                else -> moveSpread(s)
            }
            if (spread === s) Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * The focus rows coming into the stack: once they are all laid out - or ROW_WAIT_MS has
     * passed - the stack is asked for its list. Not before: the list's place is worked out once,
     * from the rows there when it is asked for (keyguard-list-state), and asked early it left
     * them out and the cards came up over the list.
     */
    private fun waitSpread(s: Spread) {
        var ready = true
        var coming = 0
        for (item in s.items) {
            // A card out already is in the list as it is.
            if (item.key == MUSIC_ISLAND || item.wasOut) continue
            if (LockIslands.noteFor(item.key)?.focus == true) coming++
            val row = rowFor(item.key)
            if (row == null || !row.isAttachedToWindow || !row.isLaidOut || row.width <= 0 || row.height <= 0) {
                ready = false
                continue
            }
            hideRow(row)
        }
        // Laid out is not yet measured by the stack: its sample (the list's place is worked out
        // from it) counts them a layout pass later. Asked in between, the list's place left them
        // out, and the stack came to rest as its pile - the list one time, the pile the next
        // (the user, 2026-09-30). The sample's count taking them is also the stack's own answer
        // to them coming in (its flows are synchronous): the list asked after that is not undone.
        val measured = s.focusBefore?.let { before -> NumState.focusCount()?.let { it >= before + coming } } ?: true
        val waited = android.os.SystemClock.uptimeMillis() - s.since
        if ((!ready || !measured) && waited < ROW_WAIT_MS) return
        // Let go before the rows were in, short of opening: nothing opened.
        val finger = s.finger
        if (finger != null && finger.ended && !finger.toList) {
            endSpread(folded = true, why = "let go before the list")
            return
        }
        if (finger?.ended == true) s.finger = null
        val held = s.finger
        s.islandsY = NumState.scrollTo("NUMBER") ?: NumState.position() ?: 0
        // As far as the stack really scrolls: aimed past it, the spread stood short of its end.
        s.cardsY = NumState.listScroll() ?: s.islandsY
        startSpreadMorphs(s, toCards = true)
        s.phase = SPREAD_OPENING
        s.since = android.os.SystemClock.uptimeMillis()
        s.visualAt = s.since
        s.still = 0
        s.lastY = Int.MIN_VALUE
        // Under a finger the stack goes where it takes it (followFinger), from where it is now.
        LockIslands.openStack("spread", scroll = held == null)
        if (held != null) held.base = held.pulled()
        // A short or unavailable scroll range can read as fully open on this first frame.
        // The visible islands still begin at their folded pose and catch up over real frames.
        s.rawProgress = spreadProgress(s, NumState.position() ?: s.islandsY)
        applySpread(s, if (s.stacked) 0f else s.rawProgress)
        spreadTrace("spread out after ${waited}ms ready=$ready measured=$measured: " + describeSpread())
    }

    /**
     * Every island with a place in the row sets out for its card, held at [toCards]'s start and
     * posed by the progress from then on. One with no card, or no place, fades where it is.
     */
    private fun startSpreadMorphs(s: Spread, toCards: Boolean) {
        val pill = player
        val claimedRows = HashSet<View>()
        // Closing, the row is drawn again: its places fade in as the cards come down.
        if (!toCards) pill?.visibility = View.VISIBLE
        for (item in s.items) {
            val native = nativeFor(item.key)
            // An app's several notification keys can resolve to one OEM group row. Only its
            // group island morphs that shared row; a second morph would fight its matrix.
            if (native != null && !claimedRows.add(native)) continue
            item.native = native
            if (native == null) continue
            // Out as its card already: it stays as it is while the list opens round it.
            if (toCards && item.wasOut) continue
            if (item.key == MUSIC_ISLAND) {
                // The media card is the spread's: the OEM may show it (hardSuppressionActive).
                updateNativeSuppression(false)
                ensureNativeHeaderVisible()
            }
            if (item.seat == SEAT_HIDDEN && (!s.stacked || item.wasOut)) {
                hideRow(native)
                continue
            }
            // Out of sight while it waited: the morph saves the row's own alpha, not ours.
            showRow(native)
            val small = item.seat != SEAT_BIG
            val view = if (small) prepareSmallView(item.key, pooled = true) else pill
            if (view == null) {
                hideRow(native)
                continue
            }
            view.visibility = View.VISIBLE
            view.alpha = if (s.stacked) when (item.seat) {
                SEAT_SMALL -> MiniPlayerGeometry.STACK_BACK_ALPHA
                SEAT_HIDDEN -> 0f
                else -> 1f
            } else 1f
            val m = MiniCardMorph(view, native, toCards, spreadListener, landingFor(item.key, native),
                restBox = if (small) { { smallBoxOnScreen() } } else null,
                circle = small && !s.stacked)
            if (!m.startDragging()) {
                if (small) recycleSmallView(view)
                if (item.lead) {
                    // No morph: the island fades in its place, as before, and the row is the
                    // stack's own to unfold - not faded by the item's own path as well.
                    showRow(native)
                    item.native = null
                } else hideRow(native)
                continue
            }
            item.view = view
            item.standIn = small
            item.morph = m
            if (item.lead) holdLeadThroughFold(native)
            // The stand-in is the small island's place from here, in its very shape.
            if (item.seat == SEAT_SMALL) smallIsland?.visibility = View.GONE
        }
    }

    /** 0 at the islands' end, 1 at the cards'. */
    private fun spreadProgress(s: Spread, y: Int): Float {
        val span = (s.cardsY - s.islandsY).toFloat()
        if (s.stacked && kotlin.math.abs(span) < dp(72f)) {
            s.finger?.takeIf { !it.ended && !it.base.isNaN() }?.let { finger ->
                return ((finger.pulled() - finger.base) / dp(220f)).coerceIn(0f, 1f)
            }
        }
        if (kotlin.math.abs(span) < 1f) return if (s.phase == SPREAD_CLOSING) 0f else 1f
        return ((y - s.islandsY) / span).coerceIn(0f, 1f)
    }

    /** Bound abrupt list-scroll updates while leaving a held finger directly in control. */
    private fun visibleSpreadProgress(s: Spread, target: Float): Float {
        if (!s.stacked) return target
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = (now - s.visualAt).coerceIn(0L, 50L).toFloat()
        s.visualAt = now
        if (s.finger?.ended == false || s.collapseFinger?.ended == false) return target
        if (elapsed <= 0f) return s.progress
        val eased = (target - s.progress) * (1f - kotlin.math.exp(-elapsed / 80f))
        val maxStep = elapsed / 220f
        return (s.progress + eased.coerceIn(-maxStep, maxStep)).coerceIn(0f, 1f)
    }

    private fun applySpread(s: Spread, p: Float) {
        s.progress = p
        var hiddenTurn = 0
        for (item in s.items) {
            val itemProgress = if (s.stacked && item.seat == SEAT_HIDDEN && !item.wasOut) {
                hiddenTurn++
                val delay = (hiddenTurn * 0.075f).coerceAtMost(0.225f)
                ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
            } else p
            val m = item.morph
            if (m != null) {
                m.led(itemProgress)
                if (s.stacked && item.standIn) item.view?.alpha = when (item.seat) {
                    SEAT_SMALL -> MiniPlayerGeometry.STACK_BACK_ALPHA +
                        (1f - MiniPlayerGeometry.STACK_BACK_ALPHA) * itemProgress
                    SEAT_HIDDEN -> itemProgress
                    else -> 1f
                }
                continue
            }
            // Out as its card from before: whole while the list opens, fading only as it folds.
            if (item.wasOut && s.phase != SPREAD_CLOSING) continue
            // No morph: its card fades in with the list, its place fades out, and back.
            item.native?.takeIf { it.isAttachedToWindow }?.let {
                if (kotlin.math.abs(it.transitionAlpha - itemProgress) > 0.002f)
                    it.transitionAlpha = itemProgress
            }
            fadeSeat(item.seat, 1f - itemProgress)
        }
        // Morphing, the stack island's place is the morph's view: fading it too fought the morph.
        if (s.items.none { it.lead && it.morph != null }) fadeSeat(s.stackSeat, 1f - p)
    }

    /**
     * The stack island's row under its morph, whole. An ordinary row is multiplied by the fold's
     * numStateAlpha (ExpandableViewBase.onPropertiesUpdate: extAlpha = folmeAlpha x it), 0 while
     * the stack reads NUMBER and springing to 1 only once it reads LIST - most of the way out. The
     * morph handed over to a row not there yet: the pill's glass gone, the row's not come, the
     * icon alone in the air for a few frames (#73, filmed 2026-10-14). Focus rows and the media
     * card are not the fold's, which is why theirs never did this. Written in pre-draw, after the
     * fold's own write for the frame; given back to the fold's state when the morph is over.
     */
    private var leadHeld: Pair<View, ViewTreeObserver.OnPreDrawListener>? = null

    private fun holdLeadThroughFold(row: View?) {
        val held = leadHeld
        if (held != null && held.first === row) return
        if (held != null) {
            Gen.unpreDraw(held.first, held.second)
            setFoldAlpha(held.first, if (NumState.inNumber == true) 0f else 1f)
        }
        leadHeld = null
        if (row == null) return
        val l = ViewTreeObserver.OnPreDrawListener { setFoldAlpha(row, 1f); true }
        Gen.preDraw(row, l)
        leadHeld = row to l
        setFoldAlpha(row, 1f)
    }

    private fun setFoldAlpha(row: View, a: Float) {
        runCatching {
            val injector = Xp.callMethod(row, "getInjector")
            if (Xp.getObjectField(injector, "numStateAlpha") as Float == a) return
            Xp.setObjectField(injector, "numStateAlpha", a)
            val folme = Xp.getObjectField(Xp.getObjectField(Xp.callMethod(row, "getViewState"),
                "mInjector"), "folmeAlpha") as Float
            Xp.callMethod(row, "setExtAlpha", folme * a)
        }
    }

    private fun fadeSeat(seat: Int, a: Float) {
        val v: View = when (seat) {
            SEAT_BIG -> player
            SEAT_SMALL -> smallIsland
            else -> null
        } ?: return
        val alpha = if (seat == SEAT_SMALL && stackedStyle())
            a * MiniPlayerGeometry.STACK_BACK_ALPHA else a
        if (alpha > 0f && v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        if (kotlin.math.abs(v.alpha - alpha) > 0.002f) v.alpha = alpha
    }

    /** Opening or closing: every island on the stack's scroll, until the stack rests at an end. */
    private fun moveSpread(s: Spread) {
        s.finger?.takeIf { !it.ended && s.phase == SPREAD_OPENING }?.let { followFinger(s, it) }
        val y = NumState.position() ?: return
        val p = s.collapseFinger?.progress() ?: spreadProgress(s, y)
        s.rawProgress = p
        if (s.items.any { it.morph?.active == false }) {
            // The lock screen's guard ended a morph (asleep, say): straight to the nearer end.
            endSpread(folded = p < 0.5f, why = "a morph was ended")
            return
        }
        applySpread(s, visibleSpreadProgress(s, p))
        if (y != s.lastY) spreadFrameLog(s, y, p)
        // Held by the finger, it rests where the finger does; the let-go sends it to an end.
        if (s.finger?.ended == false || s.collapseFinger?.ended == false) {
            s.lastY = y
            s.still = 0
            return
        }
        // At an end and the stack already reading that state, no finger on it: done now. The fold
        // overshoots - the scroll ran to -275 and took 700ms coming back to 0 - and waiting for it
        // to stand still held the media card out for all of it after the islands had landed
        // (the user's "pause" on a pull down, 2026-09-30). The finger by the stack's own drag
        // alone: the overshoot is a pull past the scroll too (os= in the frame log), not a finger.
        if (!stackDragged()) {
            if (s.phase == SPREAD_CLOSING && p <= 0.02f && s.progress <= 0.005f &&
                NumState.inNumber == true) {
                endSpread(folded = true, why = "folded")
                return
            }
            if (s.phase == SPREAD_OPENING && p >= 0.995f && s.progress >= 0.995f &&
                NumState.state() == "LIST") {
                finishOpen(s, y)
                return
            }
        }
        // Let go on the way home: sent there now. A fling never reaches the hands-up (NumState),
        // and a fling let go at 0.67 came to rest as the pile (filmed and logged 2026-09-30).
        val dragged = stackDragged()
        if (s.phase == SPREAD_CLOSING && s.wasDragged && !dragged && p < SPREAD_FOLD_BELOW && !s.letGoSent) {
            s.letGoSent = true
            NumState.goTo("NUMBER", why = "spread let go folding at ${"%.2f".format(p)}")
        }
        s.wasDragged = dragged
        // Still by the stack's own drag, not its pull: a fling left the pull at 44 for good, and
        // counted as a finger the spread never came to rest and stood half folded for 10s.
        val moving = s.collapseFinger?.let { !it.ended }
            ?: (y != s.lastY || NumState.busy() || dragged)
        s.still = if (moving) 0 else s.still + 1
        s.lastY = y
        // The native scroll can finish in one frame. Keep drawing the morph until its visible
        // progress reaches that end instead of handing all cards to SystemUI at once.
        if (s.stacked && kotlin.math.abs(s.progress - p) > 0.005f) {
            s.still = 0
            return
        }
        if (s.still < SPREAD_STILL_FRAMES) return
        val late = android.os.SystemClock.uptimeMillis() - s.since > SPREAD_GIVE_UP_MS
        when {
            p >= 0.98f -> finishOpen(s, y)
            p <= 0.02f -> endSpread(folded = true, why = "folded")
            // Pulled toward home and come to rest short of it: home, whichever end is nearer. Sent
            // to the nearer one, a pull let go at 0.57 was sent back out to the list (filmed
            // 2026-09-30).
            s.phase == SPREAD_CLOSING -> when {
                !s.nudged -> {
                    s.nudged = true
                    s.still = 0
                    NumState.goTo("NUMBER", why = "spread closing stuck at ${"%.2f".format(p)}")
                }
                late -> endSpread(folded = true, why = "gave up closing")
            }
            // Opening and come to rest a good way out, the stack no longer folded: the list ends
            // here - it goes no further than this, whatever its place was worked out to be.
            p >= SPREAD_OPEN_AT && NumState.inNumber != true -> {
                spreadTrace("spread opened short at ${"%.2f".format(p)} ($y of ${s.cardsY})")
                finishOpen(s, y)
            }
            !s.nudged -> {
                // At rest near the fold - a scroll that never came.
                s.nudged = true
                s.still = 0
                NumState.goTo("LIST", why = "spread stuck at ${"%.2f".format(p)}")
            }
            late -> endSpread(folded = true, why = "gave up")
        }
    }

    /** The spread's own events, in `op mini` and in `op numstate`'s frames with its frames. */
    private fun spreadTrace(what: String) {
        trace(what)
        NumState.frame(what.take(160))
    }

    /** One frame of the spread moving, for `op numstate` (frames=). */
    private fun spreadFrameLog(s: Spread, y: Int, p: Float) {
        val phase = when (s.phase) { SPREAD_OPENING -> "o"; SPREAD_CLOSING -> "c"; SPREAD_OPEN -> "O"; else -> "w" }
        NumState.frame("$phase y=$y sy=${NumState.scrollY()} os=${NumState.overScrollParts()} " +
            "p=${"%.2f".format(p)}/${"%.2f".format(s.progress)} ${s.islandsY}->${s.cardsY} d=${if (stackDragged()) 1 else 0}" +
            "${if (NumState.busy()) "b" else ""} st=${NumState.state()?.take(1)}" +
            (s.finger?.let { f -> " f=${f.pulled().toInt()}${if (f.ended) "e" else ""}" } ?: ""))
    }

    /** For NumState's hands-up: the row spread out is being pulled home. */
    fun spreadFolding(): Boolean {
        val s = spread ?: return false
        return s.phase == SPREAD_CLOSING && s.rawProgress < SPREAD_FOLD_BELOW
    }

    /** The stack scrolled out as far as the finger has pulled past where it started to follow. */
    private fun followFinger(s: Spread, f: SpreadFinger) {
        if (f.base.isNaN()) return
        val span = s.cardsY - s.islandsY
        val d = (f.pulled() - f.base).coerceAtLeast(0f).coerceAtMost(kotlin.math.abs(span).toFloat())
        NumState.setScroll(s.islandsY + (if (span >= 0) d else -d).toInt())
    }

    /** The finger on a pulled-open spread moved (the pill's route, as noteDragMove). */
    fun spreadFingerMove(y: Float): Boolean {
        val f = spread?.finger?.takeIf { !it.ended } ?: return false
        f.y = y
        return true
    }

    /**
     * The finger let go of a pulled-open spread: flung, the way it was flung; else open once it
     * is a third of the way out, or still waiting for the rows (the pull was past the threshold).
     * The stack is sent to that end on its own spring, and the spread follows it there.
     */
    fun spreadFingerEnd(velocityY: Float, cancelled: Boolean): Boolean {
        val s = spread ?: return false
        val f = s.finger?.takeIf { !it.ended } ?: return false
        f.ended = true
        val fling = SPREAD_FLING_DP * density()
        f.toList = !cancelled && when {
            velocityY < -fling -> true
            velocityY > fling -> false
            s.phase == SPREAD_WAIT -> true
            else -> s.progress >= SPREAD_OPEN_AT
        }
        spreadTrace("spread let go p=${"%.2f".format(s.progress)} v=${velocityY.toInt()} list=${f.toList}")
        // Still waiting for the rows: waitSpread takes the decision when they are in.
        if (s.phase != SPREAD_OPENING) return true
        s.finger = null
        s.still = 0
        s.nudged = false
        NumState.goTo(if (f.toList) "LIST" else "NUMBER", why = "spread let go")
        return true
    }

    /** The list is open: the cards are the lock screen's own, the row's views put away. */
    private fun finishOpen(s: Spread, y: Int) {
        s.collapseFinger = null
        holdLeadThroughFold(null)
        for (item in s.items) {
            item.morph?.cancel()
            item.morph = null
            item.view?.let { if (item.standIn) recycleSmallView(it) }
            item.view = null
            item.standIn = false
            item.native?.let(::showRow)
        }
        player?.visibility = View.INVISIBLE
        player?.alpha = 1f
        smallIsland?.visibility = View.GONE
        smallIsland?.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        if (s.items.any { it.key == MUSIC_ISLAND }) ensureNativeHeaderVisible()
        s.phase = SPREAD_OPEN
        s.cardsY = y
        s.still = 0
        // A wake while it is open wakes to the list, not to the folded stack (watchOpen).
        NumState.wakeAsList(true)
        // The list's place read again, from the stack as it is now: asked from a sample still
        // short of the rows that came in, it was short of the list (the stack's pile). The cards
        // are the stack's own from here, so the rest of the way is its scroll alone.
        val list = NumState.listScroll()
        val state = NumState.state()
        if (!s.reaimed && list != null && kotlin.math.abs(list - y) > dp(2f) && state != "LIST") {
            s.reaimed = true
            NumState.goTo("LIST", why = "spread re-aimed ($state at $y, list at $list)")
        }
        spreadTrace("spread open ($state): " + describeSpread())
    }

    /** The stack's own finger on it (NotificationStackScrollLayout.mIsBeingDragged). */
    private fun stackDragged(): Boolean = runCatching {
        Xp.getObjectField(notificationStack() ?: return false, "mIsBeingDragged") == true
    }.getOrDefault(false)

    /**
     * The list open: a finger pulling it down toward the fold starts every card home, and the
     * stack folding by itself (a wake to its default state) brings them home at once. Anything
     * else moving it - a notification coming or going - is the list's own: its rest follows.
     */
    private fun watchOpen(s: Spread) {
        // The list is kept over the doze (the user, 2026-10-24): the full-screen AOD scrolls the
        // stack to its count and the wake sends it to its default, and either folded it read as
        // the stack folding by itself - every card went home whenever the screen had gone off.
        // Asleep it is left be; woken, the stack goes back to the list if it is not there.
        val now = android.os.SystemClock.uptimeMillis()
        if (MiniPlayerScene.aodActive || !Main.screenOnCached()) {
            s.slept = true
            s.wokeAt = 0L
            return
        }
        if (s.slept) {
            s.slept = false
            s.wokeAt = now
            if (NumState.inNumber == true) NumState.goTo("LIST", why = "list kept over the doze")
            spreadTrace("spread woke: " + describeSpread())
        }
        if (s.wokeAt != 0L) {
            if (now - s.wokeAt < SPREAD_WAKE_MS && !listPulled() &&
                (NumState.inNumber == true || NumState.busy())) return
            s.wokeAt = 0L
        }
        val y = NumState.position() ?: return
        val islandsY = NumState.scrollTo("NUMBER") ?: return
        s.lastY = y
        val toward = if (s.cardsY >= islandsY) s.cardsY - y else y - s.cardsY
        val folded = NumState.inNumber == true
        if (folded || listPulled() && toward > dp(SPREAD_PULL_DP)) {
            s.islandsY = islandsY
            startSpreadMorphs(s, toCards = false)
            s.phase = SPREAD_CLOSING
            s.since = android.os.SystemClock.uptimeMillis()
            s.visualAt = s.since
            s.still = 0
            s.nudged = false
            s.wasDragged = stackDragged()
            s.letGoSent = false
            s.lastY = Int.MIN_VALUE
            s.rawProgress = spreadProgress(s, y)
            applySpread(s, if (s.stacked) 1f else s.rawProgress)
            spreadTrace("spread closing (${if (folded) "folded" else "pulled"}): " + describeSpread())
            return
        }
        if (!listPulled() && !NumState.busy()) s.cardsY = y
    }

    /**
     * A finger on the list: the stack's own drag, or its pull past the scroll - the keyguard's
     * pull down toward the fold is the latter alone, the scroll not moving till the let-go.
     */
    private fun listPulled(): Boolean = stackDragged() || kotlin.math.abs(NumState.overScroll()) > 0.5f

    /**
     * Over: [folded] the islands are back in their places and the focus rows go back out of the
     * stack, out of sight while it takes them away; else the cards stay as they are.
     */
    private fun endSpread(folded: Boolean, why: String) {
        val s = spread ?: return
        spread = null
        Gen.unframe(spreadFrame)
        holdLeadThroughFold(null)
        for (item in s.items) {
            item.morph?.cancel()
            item.morph = null
            item.view?.let { if (item.standIn) recycleSmallView(it) }
            item.view = null
            val native = item.native
            if (folded && item.wasOut) {
                // Folded with the rest: an island again. The stack gives it back a pipeline run
                // later; till then it keeps its place in the row (returning).
                native?.let { hideRowUntilGone(it, item.key); it.transitionAlpha = 0f }
                LockIslands.recapture(item.key)
                markReturning(item.key)
                releasedFromPill -= item.key
                releasedFromSmall -= item.key
                continue
            }
            if (native == null) continue
            // Folded natively the stack island's row never leaves the stack: the fold hides it.
            val stays = item.key == MUSIC_ISLAND || item.lead && LockIslands.foldsNatively()
            if (folded && !stays) hideRowUntilGone(native, item.key)
            else showRow(native)
        }
        player?.alpha = 1f
        if (folded) player?.visibility = View.VISIBLE
        smallIsland?.alpha = if (stackedStyle()) MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        // The small island's place as the fold left it: whoever came down into it stays. Nobody
        // did, and a card out from before came home: that one comes up there, growing in (the
        // row's own appearing) - after the pill, next in the row, it would have been left out.
        val cameHome = if (folded) s.items.firstOrNull { it.wasOut }?.key else null
        val smallTaken = s.stackSeat == SEAT_SMALL || s.items.any { it.seat == SEAT_SMALL }
        if (cameHome != null) preferredSmall = if (smallTaken) smallKey else cameHome
        snapSmallOnce = smallTaken || cameHome == null
        spreadTrace("spread end ($why) folded=$folded")
        MiniPlayerRuntime.noteTouch("spread end ($why) folded=$folded")
        if (folded) listKept = false
        NumState.wakeAsList(false)
        LockIslands.setSpread(false, folded)
        // Folded home but the stack left at its pile (a let-go it read as the pile): folded too.
        if (folded) NumState.foldIfPiled("spread folded")
        // The row as it is now: the media card put away again, the small island in its place.
        refresh()
        schedulePosition()
    }

    // ------------------------------------------------------------ notifications out to their rows and back

    /**
     * The notification island on its way out into its row in the stack, or back in from it. The
     * pill keeps showing it until the morph lands, whatever the row's islands have become.
     */
    private var noteMorphKey: String? = null

    /** A small island tapped: it takes the pill first, and opens once it has. */
    private var pendingExpand: String? = null

    private var rowWaitKey: String? = null
    private var rowWaitSince = 0L
    private var appListAsked = false
    private var stackRef: WeakReference<View>? = null

    /**
     * A tap on a notification island opens it into its own row, as the music island opens into
     * the media card: the stack is given the notification back, and once its row is laid out
     * the island becomes that row - its picture, title and text landing on the row's own.
     *
     * The pill's own island goes out as the pill. A small island goes out as itself: a second
     * pill, the flight, bound to its notification, starts as the small island's circle and grows
     * straight into the row, the pill beside it never changing. The first version switched the
     * small island into the pill and opened it from there - two motions where one was asked for.
     */
    fun expandNote(key: String) { android.os.Trace.beginSection("MC expandNote"); try {
        // Folded natively (LockIslands.nativeStack), the stack island opens as the stack's own
        // list, ColorOS's way: the island leaves the row and the rows come out of the fold where
        // they are. No flight: the rows were never out of the stack for one to land on.
        if (spread != null) return
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            if (!openSingleApp("tap")) openSpread("tap")
            return
        }
        // Pulled down and on its way home still: a tap on its place - its logical place, the
        // pill or the small island it is landing in - turns it round from where it is, as a tap
        // turns a switch (the super island's canClick waits for nothing). It used to do nothing
        // until the flight had landed.
        if (noteMorphKey == key && flight != null && morph?.toNative == false) {
            noteDrag = null
            releaseNote(true, 0f)
            MiniPlayerRuntime.noteTouch("open: ${key.takeLast(6)} turned out on its way home")
            return
        }
        if (noteMorphKey != null || morph != null || exchange != null) return
        // Every layout shares one expanded card: the previous island returns as this one opens.
        if (expandedKey()?.let { it != key } == true) {
            startExchange(key)
            return
        }
        val view = player
        if (view == null || view.visibility != View.VISIBLE) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        val fromSmall = selectedIsland != key
        // Out of the pill as out of the small island: a flight of its own, so the island beside
        // it can take the pill the moment it leaves. Going as the pill itself, the pill was the
        // morph's until it had landed on the row, and only then did the next island take the
        // pill - in one jump, from the small island's circle to the whole pill (filmed 2026-09-25).
        if (fromSmall && key != smallKey || prepareFlight(key) == null) {
            if (key != MUSIC_ISLAND) LockIslands.release(key)
            return
        }
        endSwap()
        // Opened, the stack island leads with its newest notification, the one it shows.
        if (key == STACK_ISLAND) stackLeadKey = null
        else if (LockIslands.isAppGroup(key)) appLeadKeys.remove(key)
        noteMorphKey = key
        flightFromSmall = fromSmall
        flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        flightOut = false
        letOut(key)
        // The list asked for now, while the rows are still being laid out: the card flies
        // straight to its place in the list. Asked once the morph had begun, it landed in the
        // pile first and the list opened after (2026-09-26).
        if (key == STACK_ISLAND) showStackAsList("open")
        else if (LockIslands.isAppGroup(key)) NumState.goTo("LIST", why = "app group open")
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        appListAsked = false
        Gen.frame(rowWait)
    } finally { android.os.Trace.endSection() } }

    /** Frame by frame until the released notification's row is in the stack and laid out. */
    private val rowWait = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { android.os.Trace.beginSection("MC rowWait"); try {
            val key = rowWaitKey ?: return
            // The stack island's other rows stay out of sight, piled, till the morph moves them.
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) pileGroup(key, 0f, null)
            val row = nativeFor(key)
            // The stack fades a returned row in on its own: seen before the morph took it, it
            // stood there whole, went, and came back as the morph's end (filmed 2026-09-25).
            if (row != null) {
                hideRow(row)
                if (LockIslands.isAppGroup(key) && !appListAsked && row.isLaidOut) {
                    appListAsked = true
                    NumState.goTo("LIST", why = "app group rows ready")
                }
            }
            val flightReady = flight?.let { it.isLaidOut && it.width > 1 } ?: true
            if (row != null && row.isAttachedToWindow && row.isLaidOut && row.height > 0 &&
                row.width > 0 && flightReady && rowOnScreen(row)) {
                rowWaitKey = null
                startNoteMorph(key, row, toRow = true)
                return
            }
            if (android.os.SystemClock.uptimeMillis() - rowWaitSince > ROW_WAIT_MS) {
                // No row to land on in time - none laid out, or one the stack keeps out of
                // sight: folded into its count, or faded out. Opened without a morph, the
                // island went into a stack that did not show it, and was simply gone. It
                // comes back into the row instead, where it was.
                val why = when {
                    row == null -> "no row"
                    !rowOnScreen(row) -> "row hidden: " + offScreenWhy(row)
                    else -> "row not laid out"
                }
                Xp.log("MCIsland: $key not opened: $why")
                MiniPlayerRuntime.noteTouch("open $why")
                rowWaitKey = null
                noteDrag = null
                abandonNoteMorph(key)
                return
            }
            Gen.frame(this)
        } finally { android.os.Trace.endSection() } }
    }

    /**
     * A swipe down on a notification's row that came out of the row of islands: it goes back in,
     * the row becoming the pill, as the media card folds back into it. It comes in as the
     * flight and lands on the pill's place; only then does the pill take it, so nothing in the
     * pill jumps while the row is still on its way.
     */
    fun collapseRow(key: String, startY: Float): Boolean { android.os.Trace.beginSection("MC collapseRow"); try {
        // Out as the list, a pull down is the stack's own, which takes the whole row home.
        if (spread != null) return false
        if (noteMorphKey != null || morph != null || exchange != null) {
            MiniPlayerRuntime.noteTouch("collapse refused: noteMorph=${noteMorphKey != null} " +
                "morph=${morph != null} exchange=${exchange != null}")
            return false
        }
        val row = nativeFor(key) ?: run {
            MiniPlayerRuntime.noteTouch("collapse refused: no row")
            return false
        }
        // Nothing left in the row - the only island was let out - and the notification coming
        // back is all of it: the pill. It used to just go back in there with no motion at all.
        val rowEmpty = player?.visibility != View.VISIBLE
        val flight = prepareFlight(key) ?: run {
            if (rowEmpty) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                takeIn(key)
                return true
            }
            MiniPlayerRuntime.noteTouch("collapse refused: no flight")
            return false
        }
        endSwap()
        noteMorphKey = key
        flightFromSmall = false
        flightOut = false
        // Back where it came out of: the pill it was in, or the small island. The music, out
        // as its card since before any pull, comes back into the pill - the super island's
        // expanded island folds into its big one.
        flightHome = if (rowEmpty || key in releasedFromPill ||
            key == MUSIC_ISLAND && key !in releasedFromSmall) HOME_PILL else HOME_SMALL
        MiniPlayerRuntime.noteTouch("collapse home=${if (flightHome == HOME_PILL) "pill" else "small"} " +
            "empty=$rowEmpty")
        if (rowEmpty) {
            // The row comes back with this notification in the pill - out of sight under the
            // flight until it lands there.
            selectedIsland = key
            refresh()
            position()
            if (player?.visibility != View.VISIBLE || player?.restBoxOnScreen() == null) {
                MiniPlayerRuntime.noteTouch("collapse: no pill, recaptured")
                noteMorphKey = null
                noteDrag = null
                dropFlight()
                takeIn(key)
                refresh()
                return true
            }
        } else if (flightHome == HOME_PILL) {
            // The pill is its again: whatever took it goes back to being the small island,
            // forming out of the pill's place while the row comes down onto it.
            val oldBig = selectedIsland
            val oldSmall = smallKey
            selectedIsland = key
            refresh()
            startSwap(oldBig, oldSmall, intoSmall = true)
        }
        noteDrag = NoteDrag(key, startY, opening = false,
            MiniPlayerRuntime.dragThresholdPx(density()), MiniPlayerRuntime.dragNudgePx(density()))
        // The row laid out with this notification as its small island: the pill gives it room
        // on its spring while the row comes down, as the super island's big island does.
        refresh()
        position()
        trace("collapse target=${smallBoxOnScreen().cx().toInt()} " + smallState())
        if (flight.isLaidOut && flight.width > 1) return startNoteMorph(key, row, toRow = false)
        // Laid out on the next frame at the earliest; the morph waits for it.
        flight.post { if (noteMorphKey == key && morph == null) startNoteMorph(key, row, toRow = false) }
        return true
    } finally { android.os.Trace.endSection() } }

    /** The flight: a pill of its own, bound to [key]'s notification, laid out at the pill's size. */
    private var flight: MiniPlayerView? = null
    private var flightFromSmall = false

    /** Where the flight's island lives when it is in the row: the pill, or the small island. */
    private var flightHome = HOME_SMALL

    /** Notifications let out of the pill itself: pulled down, their row goes back there. */
    private val releasedFromPill = HashSet<String>()

    /** ...and islands let out of the small place, for the music, which otherwise goes to the pill. */
    private val releasedFromSmall = HashSet<String>()

    /** The pill under a flight landing on it, taking the flight's shape; null otherwise. */
    private var pillLandingBox: CoverMorphMotion.Box? = null

    /** The pill's place is a flight's - out of it and not let go, or coming home to it. */
    private fun pillHeld(): Boolean = flight != null && flightHome == HOME_PILL &&
        selectedIsland == noteMorphKey && pillLandingBox == null &&
        // Pulled up, the pill itself follows the finger until the row is there to morph into.
        (morph != null || rowWaitKey == null)

    /**
     * The flight has left its home for good ([out]) or is headed back to it. Home is the small
     * island's place: the next island comes up there, or the flight's shrinks away for it. Home
     * is the pill: the island beside it takes the pill, growing out of its circle on the super
     * island's CHANGE_EASE (SmallIslandToBigIsland); coming back, that island shrinks into the
     * small place again (BigIslandToSmallIsland) while the flight lands on the pill.
     */
    private fun setFlightOut(out: Boolean) {
        val key = noteMorphKey
        if (flight == null || key == null || out == flightOut) return
        flightOut = out
        if (flightHome != HOME_PILL) {
            if (out) {
                releasedFromSmall += key
                releasedFromPill -= key
            }
            refresh()
            return
        }
        if (out) {
            releasedFromPill += key
            releasedFromSmall -= key
            val oldSmall = smallKey
            if (selectedIsland == key) {
                selectedIsland = oldSmall ?: islandKeys.firstOrNull { it != key }
                refresh()
                startSwap(key, oldSmall)
            } else refresh()
        } else if (selectedIsland != key) {
            val oldBig = selectedIsland
            val oldSmall = smallKey
            selectedIsland = key
            refresh()
            startSwap(oldBig, oldSmall, intoSmall = true)
        }
    }

    private fun prepareFlight(key: String): MiniPlayerView? { android.os.Trace.beginSection("MC prepareFlight"); try {
        val music = if (key == MUSIC_ISLAND) controller?.takeIf(::isUsable) ?: return null else null
        val note = if (music == null) LockIslands.noteFor(key) ?: return null else null
        val pill = player ?: return null
        // One kept from an earlier flight or switch when there is one: made new, a flight cost
        // 16ms on the touch that starts it - its material put on, its lines laid out for the
        // first time (traced 2026-09-25).
        val view = flight ?: takeSpare()?.also { flight = it } ?: MiniPlayerView(context).also {
            flight = it
            // Under the pill in the host; a morph lifts it above everything with translationZ.
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(host, it)
        }
        val w = pill.layoutParams.width.coerceAtLeast(1)
        val h = pill.layoutParams.height.coerceAtLeast(1)
        if (view.layoutParams.width != w || view.layoutParams.height != h) {
            view.layoutParams = view.layoutParams.apply { width = w; height = h }
        }
        traced("MC pf.bind") { if (music != null) bindMusic(view, music, config) else bindNote(view, note!!, config) }
        // Laid out here and now, not on the next pass: a flight is a new view each time, and
        // its morph waited for that pass - the row stood still under a finger already pulling
        // it down, and the flight then jumped to where the finger had got to (filmed 2026-09-25).
        if (!view.isLaidOut || view.isLayoutRequested || view.width != w || view.height != h) {
            traced("MC pf.layout") {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
            }
        }
        // Out of sight until its morph puts it where it starts.
        view.visibility = View.VISIBLE
        view.alpha = 0f
        return view
    } finally { android.os.Trace.endSection() } }

    private fun dropFlight() {
        flight?.let(::recycleSmallView)
        flight = null
        flightFromSmall = false
        flightHome = HOME_SMALL
        flightOut = false
        flightLanding = false
        endPillLanding()
        if (landingBox != null) {
            landingBox = null
            smallIsland?.iconAtStart = false
            restoreSmallIslandShape()
        }
    }

    /** Where an island's picture, title and text land on a notification's row, and its corners. */
    private fun rowLanding(row: View) = MiniCardMorph.Landing(
        // A focus template's own picture before any icon: searched in one pass, the header's
        // small app icon came first in the tree and the picture flew to it. From PR #15.
        findNamed(row, FOCUS_ART_NAMES) { it.width > 0 && it.height > 0 }
            ?: findNamed(row, ICON_NAMES) { it is ImageView },
        findNamed(row, TITLE_NAMES) { it is TextView },
        findNamed(row, TEXT_NAMES) { it is TextView },
        Main.notificationRowRadius(row),
        dp(8f).toFloat(),
    )

    /** Where [key]'s island lands on its card: the media card's own elements, or its row's. */
    private fun landingFor(key: String, native: View) =
        if (key == MUSIC_ISLAND) MiniCardMorph.Landing.mediaCard(native) else rowLanding(native)

    private fun startNoteMorph(key: String, row: View, toRow: Boolean): Boolean {
        val useFlight = flight != null
        val view = (if (useFlight) flight else player) ?: return false
        val landing = landingFor(key, row)
        // The flight's end, both ways: its home - the small island's circle, or the pill.
        val home = flightHome
        val restBox: (() -> CoverMorphMotion.Box?)? = when {
            !useFlight -> null
            home == HOME_PILL -> { { player?.restBoxOnScreen() } }
            else -> { { smallBoxOnScreen() } }
        }
        view.alpha = if (useFlight && home == HOME_SMALL && stackedStyle())
            MiniPlayerGeometry.STACK_BACK_ALPHA else 1f
        if (!useFlight) endRow()
        // A focus notification's second button as the pill has it at the flight's home end: the
        // big island's. The flight never set it and went without - out of the pill the button
        // vanished at once, and landing back the pill grew it in anew: a blink each way (2026-09-25).
        if (useFlight) view.setSecondShown(home == HOME_PILL && smallKey == null, animate = false)
        // Kept out of sight while it waited (rowWait); the morph saves the row's own alpha
        // and fades it in from there, in this same frame.
        showRow(row)
        // Opening, it goes to where the stack is settling its card, rows on their way out gone.
        if (toRow) {
            unpinCard()
            }
        val next = MiniCardMorph(view, row, toRow, noteMorphListener(key), landing, restBox,
            circle = useFlight && home == HOME_SMALL && !stackedStyle(),
            nativeDy = if (toRow) { { settleDy(row, emptyList()) } } else null)
        morph = next
        noteSpan = noteDragSpan(row, useFlight && home == HOME_SMALL)
        val drag = noteDrag?.takeIf { it.key == key }
        if (useFlight && toRow && home == HOME_SMALL) {
            // The flight is the small island from here: one hands over to the other in place.
            smallIsland?.visibility = View.GONE
            smallGrowing = false
            Gen.unframe(smallGrowFrame)
        }
        if (drag != null) drag.span = noteSpan
        // The island's own nudge is the morph's from here, as the pill's is handed to the card's.
        if (drag != null && drag.opening) {
            if (flightFromSmall) clearSmallNudge() else player?.clearNudge()
        }
        val started = if (drag != null) next.startDragging() else next.start()
        if (started && toRow && key == STACK_ISLAND) showStackAsList("open")
        if (!started) {
            morph = null
            noteMorphKey = null
            noteDrag = null
            dropFlight()
            if (!toRow) takeIn(key)
            refresh()
            return false
        }
        if (drag != null) {
            if (drag.ended) {
                // The finger already let go, committed: it opens from where the pull left it.
                noteDrag = null
                val (p, nudge) = notePose(drag)
                next.drag(p, nudge, drag.nudgeX)
                next.release(drag.toRow, noteVelocity(drag))
                if (!drag.toRow) takeInEarly(key)
            } else applyNoteDrag()
        }
        // The small place, the pill's width and the small island's presence follow the flight:
        // freed (the next island comes up, or the pill takes the room back) or held (what is
        // there shrinks away for the flight coming in). A tap, or a pull already let go past
        // the threshold, is gone for good and frees its home at once.
        if (useFlight) {
            refresh()
            if (toRow && (drag == null || drag.ended && drag.toRow)) setFlightOut(true)
        }
        // The pill it left goes out of sight this frame, not the next.
        if (home == HOME_PILL) followShortcuts()
        return true
    }

    private fun noteMorphListener(key: String) = object : MiniCardMorph.Listener {
        /** The music out of a scene lands once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            key != MUSIC_ISLAND || toNative || !morphScene || !Main.coverSceneActive()

        override fun artBridged() = key == MUSIC_ISLAND && artBridged

        override fun onFrame(morph: MiniCardMorph, progress: Float) {
            if ((key == STACK_ISLAND || LockIslands.isAppGroup(key)) &&
                this@MiniPlayerController.morph === morph) pileGroup(key, progress, morph.miniEndBox())
            val f = flight ?: return
            if (this@MiniPlayerController.morph !== morph) return
            val stackedSmall = stackedStyle() && flightHome == HOME_SMALL
            val stackedAlpha = MiniPlayerGeometry.STACK_BACK_ALPHA +
                (1f - MiniPlayerGeometry.STACK_BACK_ALPHA) * progress
            if (flightOut) {
                if (stackedSmall) f.alpha = stackedAlpha
                return
            }
            if (progress > 0f) restFrames = 0 else restFrames++
            if (!morph.toNative && restFrames <= 10) morph.containerBox()?.let { b ->
                trace("f c=${"%.3f".format(progress)} box=${b.cx().toInt()},${b.w.toInt()}x${b.h.toInt()} " +
                    "fa=${"%.2f".format(f.alpha)} land=$flightLanding " + smallState())
            }
            val landing = !morph.toNative && progress < FLIGHT_HANDOFF
            if (landing != flightLanding) {
                flightLanding = landing
                // Shown at once, whole, with its own picture: it is what the flight becomes.
                if (landing) snapSmallOnce = true
                scheduleRefresh()
            }
            val box = if (landing) morph.containerBox() else null
            if (flightHome == HOME_PILL) {
                if (box != null) landOnPill(box) else endPillLanding()
            } else if (box != null) landOn(box)
            else if (landingBox != null) {
                // Turned back out: the small island goes back to waiting in its place.
                landingBox = null
                smallIsland?.iconAtStart = false
                restoreSmallIslandShape()
            }
            val a = if (landing) MiniCardMorph.smooth(0f, FLIGHT_HANDOFF, progress)
                else if (stackedSmall) stackedAlpha else 1f
            if (kotlin.math.abs(f.alpha - a) > 0.002f) f.alpha = a
        }
        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            flushTurn(key)
            trace("settled toRow=$toNative completed=$completed " +
                (if (completed) "" else "why=${morph.lastCancel} ") + smallState())
            if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) endPile(toNative)
            if (key == MUSIC_ISLAND) morphScene = false
            if (this@MiniPlayerController.morph === morph) this@MiniPlayerController.morph = null
            noteDrag = null
            val flew = flight != null
            val home = flightHome
            val oldSmall = smallKey
            noteMorphKey = null
            flightOut = false
            dropFlight()
            if (toNative) {
                // Out in the stack. From the small island the next one has already come up in
                // its place (updateSmallIsland); from the pill, the pill takes the next island,
                // growing back out of its end, and the small island stays as it is unless the
                // pill took it.
                // A flight - out of the small island, or a collapse let go back to its row -
                // leaves the pill as it is.
                if (!flew) selectedIsland = null
                refresh()
                if (!flew) startSwap(null, oldSmall)
                // Held where it landed until the stack has it there too.
                nativeFor(key)?.let { pinCard(it, emptyList()) }
            } else {
                // Back in the row as the small island it landed as: the stack lets go of its row.
                // The morph has just put the row's alpha back, and the stack takes most of a
                // second to animate a removed row away: it stood there again, whole, after the
                // flight had landed (filmed 2026-09-25). Hidden until it is gone.
                // The music's card is put away by the pill being chosen again (updateVisibility).
                if (key != MUSIC_ISLAND) {
                    rowFor(key)?.let { hideRowUntilGone(it, key) }
                    // The stack gives it back a run of the pipeline later: until then the row
                    // keeps its place, or the small island showed the next one for a frame or two
                    // and then grew this one anew - the icon blinked on landing (2026-09-25).
                    markReturning(key)
                }
                takeIn(key)
                letGo(key)
                releasedFromPill -= key
                releasedFromSmall -= key
                if (flew && home == HOME_SMALL) {
                    preferredSmall = key
                    snapSmallOnce = true
                } else selectedIsland = key
                refresh()
            }
        }
    }

    /**
     * The pill under a flight landing on it: shown, with the notification it is taking back,
     * in the flight's shape frame by frame - the overshoot included - while the flight fades
     * off it, as the small island takes a flight landing on its circle.
     */
    private fun landOnPill(box: CoverMorphMotion.Box) {
        val view = player ?: return
        val first = pillLandingBox == null
        pillLandingBox = box
        if (!view.inMorph()) view.beginMorph(layoutOnly = true)
        view.setMorphFrame(box, box.h / 2f, 1f)
        // Whole on every frame it is landed on, not just the first: whatever was bringing its
        // content in while it was held (the switch that gave it back) is not done with it yet.
        view.setContentAlpha(1f)
        view.setContentBlur(0f)
        // Its second button as the flight landing on it has it: there, not growing in anew.
        if (first) view.setSecondShown(smallKey == null, animate = false)
        if (first) followShortcuts()
    }

    /** The flight has landed, or turned back: the pill's frame is its own again. */
    private fun endPillLanding() {
        if (pillLandingBox == null) return
        pillLandingBox = null
        // Anything else sizing the pill goes on from here; nothing else, its rest frame.
        if (swap == null && !rowAnimating) player?.endMorph()
        followShortcuts()
    }

    /** The small island takes the flight's shape this frame: room for it, then placed on it. */
    private fun landOn(box: CoverMorphMotion.Box) {
        val small = smallIsland ?: return
        val pad = dp(16f)
        val w = maxOf(discFrame(discDiameter()), (box.w + pad).roundToInt())
        val h = maxOf(discFrame(discDiameter()), (box.h + pad).roundToInt())
        // Only ever widened while landing: the shape shrinks inside the room it was given.
        if (landingBox == null || small.layoutParams.width < w || small.layoutParams.height < h) {
            resizeSmallIsland(small, maxOf(small.layoutParams.width, w),
                maxOf(small.layoutParams.height, h))
            smallWide = true
        }
        landingBox = box
        small.iconAtStart = true
        placeSmallIsland(null, small.transitionAlpha)
    }

    // ---- a finger pulling a notification island out

    /**
     * A notification island - the pill's or the small one - pulled up by the finger into its
     * row, as the music pill is pulled up into the media card. The row only exists once the
     * stack has been given the notification back, a frame or two later, so the finger is
     * followed from the start and the morph picks up wherever it has got to when the row is
     * there. Let go before then, the pull's own distance and speed decide.
     */
    private class NoteDrag(
        val key: String,
        val startY: Float,
        /** Up out of the island into its row; false for a row pulled down into its island. */
        val opening: Boolean,
        val threshold: Float,
        val nudgeLimit: Float,
    ) {
        var y = startY
        var nudgeX = 0f
        var ended = false
        /** The stack's list asked for: the pull went past its threshold. */
        var listAsked = false
        /** Where the lift sends it: its row (true) or its island. */
        var toRow = false
        /** Toward the far end, in pixels per second, as the lift measured it. */
        var speed = 0f
        /** The pull's length, from the island to the row: unknown (0) until the row is there. */
        var span = 0f

        fun pulled() = if (opening) startY - y else y - startY
    }

    private var noteDrag: NoteDrag? = null

    /** The running notification morph's pull length: what a finger catching it moves over. */
    private var noteSpan = 0f

    private fun spanOf(drag: NoteDrag) = drag.span.takeIf { it > 0f } ?: NOTE_SPAN_MIN_DP * density()

    /** The pull as the music's is posed (MiniPlayerRuntime.pose): progress, then nudge. */
    private fun notePose(drag: NoteDrag): Pair<Float, Float> = MiniPlayerRuntime.pose(drag.pulled(),
        !drag.opening, drag.threshold, spanOf(drag), drag.nudgeLimit)

    /** The lift's speed as the morph's progress speed. */
    private fun noteVelocity(drag: NoteDrag): Float =
        (if (drag.opening) drag.speed else -drag.speed) / spanOf(drag)

    /** A notification's morph, let go by a finger that caught it: to its row or its island. */
    private fun releaseNote(toRow: Boolean, velocity: Float) {
        morph?.release(toRow, velocity)
        noteMorphKey?.let { turnTo(it, toRow) }
        if (toRow && noteMorphKey == STACK_ISLAND) showStackAsList("turned out")
        // A flight headed out frees its home; headed home, it holds it again.
        setFlightOut(toRow)
    }

    fun beginNoteDrag(key: String, fromSmall: Boolean, startY: Float): Boolean {
        // Folded natively, a pull past the threshold opens the stack's list as a tap does; the
        // pull is taken (true) so nothing else runs under it, and the island springs back as it
        // goes.
        if (spread != null) return false
        if (key == STACK_ISLAND && LockIslands.foldsNatively()) {
            player?.springNudgeBack(0f, 0f)
            springSmallNudgeBack(0f, 0f)
            openSpread("pull", SpreadFinger(startY))
            return true
        }
        if (noteMorphKey != null || morph != null || exchange != null) return false
        val view = player ?: return false
        if (view.visibility != View.VISIBLE) return false
        if (fromSmall && key != smallKey) return false
        if (!fromSmall && selectedIsland != key) return false
        // Another island out as its card: the pull opens this one in its stead, the two
        // exchanged on their own spring (not under the finger, which only asked for it).
        if (expandedKey()?.let { it != key } == true) {
            player?.springNudgeBack(0f, 0f)
            springSmallNudgeBack(0f, 0f)
            // The music left the cover for the island out now (a page opened from it): pulled
            // back up it goes into the cover, as a tap does. Exchanged as a plain card it came
            // up over the wallpaper with the cover still held off (#63).
            if (key == MUSIC_ISLAND && MiniPlayerRuntime.takeRestoreScene()) {
                openCover()
                return true
            }
            return startExchange(key)
        }
        if (prepareFlight(key) == null) return false
        endSwap()
        resetIslandDrag()
        // The island keeps following the finger until its row is there to morph into; its
        // nudge is handed to the morph then (startNoteMorph), as the pill's is to the card's.
        noteMorphKey = key
        flightFromSmall = fromSmall
        flightHome = if (fromSmall) HOME_SMALL else HOME_PILL
        flightOut = false
        noteDrag = NoteDrag(key, startY, opening = true,
            MiniPlayerRuntime.dragThresholdPx(density()), MiniPlayerRuntime.dragNudgePx(density()))
        letOut(key)
        rowWaitKey = key
        rowWaitSince = android.os.SystemClock.uptimeMillis()
        appListAsked = false
        Gen.frame(rowWait)
        return true
    }

    fun noteDragMove(y: Float, nudgeX: Float = 0f) { android.os.Trace.beginSection("MC t.noteDragMove"); try {
        if (spreadFingerMove(y)) return
        val drag = noteDrag ?: return
        drag.y = y
        drag.nudgeX = nudgeX
        applyNoteDrag()
    } finally { android.os.Trace.endSection() } }

    /**
     * The finger is off: the music's lift decides (MiniPlayerRuntime.lift). Let go before the
     * row was there, the decision takes the shortest span a pull can have and the real one
     * sets the speed once the row is laid out (startNoteMorph) - with a 1px span, the flight
     * was once flung hundreds of times too fast (filmed 2026-09-25).
     */
    fun noteDragEnd(velocityY: Float, cancelled: Boolean) {
        if (spreadFingerEnd(velocityY, cancelled)) return
        val drag = noteDrag ?: return
        drag.ended = true
        val speed = if (drag.opening) -velocityY else velocityY
        val (toRow, _) = MiniPlayerRuntime.lift(drag.pulled(), speed, !drag.opening, drag.threshold,
            spanOf(drag), cancelled)
        drag.toRow = toRow
        drag.speed = if (drag.pulled() > drag.threshold) speed else 0f
        // Flung open short of the threshold: the list now, not once the card is there.
        if (toRow && drag.opening && drag.key == STACK_ISLAND && !drag.listAsked) {
            drag.listAsked = true
            showStackAsList("flung")
        }
        val running = morph
        if (running != null) {
            noteDrag = null
            releaseNote(toRow, noteVelocity(drag))
            return
        }
        if (!toRow && drag.opening) {
            // Let go short, before the row was even there: nothing opened.
            noteDrag = null
            abandonNoteMorph(drag.key)
        }
        // Headed for the row: its arrival opens it on its own.
    }

    private fun applyNoteDrag() {
        val drag = noteDrag ?: return
        // The list only once the pull means it: a pull let go short would have pressed the
        // clock down and let it back up for nothing.
        if (drag.key == STACK_ISLAND && drag.opening && !drag.listAsked && drag.pulled() > drag.threshold) {
            drag.listAsked = true
            showStackAsList("pulled")
        }
        if (drag.ended) return
        val running = morph
        if (running != null) {
            val (p, nudge) = notePose(drag)
            running.drag(p, nudge, drag.nudgeX)
            return
        }
        if (!drag.opening) return
        // No row to morph into yet: the island itself goes on following the finger, as far as
        // the morph would have carried it - rubber-banded to the threshold, then one to one.
        val pulled = drag.pulled()
        val up = if (pulled <= drag.threshold) MiniCardMorph.rubber(pulled.coerceAtLeast(0f), drag.nudgeLimit)
            else MiniCardMorph.rubber(drag.threshold, drag.nudgeLimit) + (pulled - drag.threshold)
        if (flightFromSmall) setSmallNudge(drag.nudgeX, -up) else player?.setNudge(drag.nudgeX, -up)
    }

    /** The row's arrival: how far the pull has to go, from the island's place to the row's. */
    private fun noteDragSpan(row: View, fromSmall: Boolean): Float {
        val start = (if (fromSmall) smallBoxOnScreen() else player?.restBoxOnScreen())?.y ?: return 1f
        val xy = IntArray(2).also(row::getLocationOnScreen)
        return kotlin.math.abs(start - xy[1]).coerceAtLeast(NOTE_SPAN_MIN_DP * density())
    }

    /** A notification that set out and did not open: back in the row as it was. */
    private fun abandonNoteMorph(key: String) {
        flushTurn(key)
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key)) endPile(toRow = false)
        // Whatever the finger had pulled goes home.
        player?.springNudgeBack(0f, 0f)
        springSmallNudgeBack(0f, 0f)
        if (key != MUSIC_ISLAND) rowFor(key)?.let { hideRowUntilGone(it, key) }
        else transitionHeader()?.let(::showRow)
        rowWaitKey = null
        noteMorphKey = null
        dropFlight()
        takeIn(key)
        letGo(key)
        refresh()
    }

    /** A released notification's row under a point on screen, by key. */
    fun releasedRowAt(x: Float, y: Float): String? {
        val xy = IntArray(2)
        for (key in LockIslands.releasedKeys()) {
            // The stack island's rows are all its: the one under the finger leads it home.
            for (member in LockIslands.membersOf(key)) {
                val row = findRow(member)?.first ?: continue
                if (!row.isShown || row.width <= 0) continue
                row.getLocationOnScreen(xy)
                if (x >= xy[0] && x < xy[0] + row.width && y >= xy[1] && y < xy[1] + row.height) {
                    if (key == STACK_ISLAND && noteMorphKey != STACK_ISLAND) stackLeadKey = member
                    else if (LockIslands.isAppGroup(key) && noteMorphKey != key)
                        appLeadKeys[key] = member
                    return key
                }
            }
        }
        return null
    }

    /** The list's own scroll: NSSL keeps it in mOwnScrollY, not in View.scrollY. Null unread. */
    private fun nativeScrollY(): Int? {
        val stack = notificationStack() ?: return null
        return (runCatching { Xp.callMethod(stack, "getOwnScrollY") }.getOrNull() as? Number)?.toInt()
            ?: (runCatching { Xp.getObjectField(stack, "mOwnScrollY") }.getOrNull() as? Number)?.toInt()
    }

    /** Where the lock screen rests the list, as the gesture's DOWN read it. */
    private var restScrollY: Float? = 0f
    private var listTopStrategy: Any? = null
    /** For the touch log: the scroll and rest last compared. */
    var lastScrollRead = "unread"
        private set

    /**
     * The list at its top ([MiniPlayerCollapseGesture.atListTop]). Its rest is 0 except with the
     * stack island opened out as the list, where the lock screen scrolls it down under the clock.
     */
    fun nativeStackAtTop(readRest: Boolean): Boolean {
        if (readRest) restScrollY = if (LockIslands.isReleased(STACK_ISLAND)) listRestScroll() else 0f
        val scrollY = nativeScrollY()
        lastScrollRead = "own=$scrollY/rest=$restScrollY"
        return MiniPlayerCollapseGesture.atListTop(scrollY, restScrollY)
    }

    /**
     * The LIST's resting scroll, as the lock screen works it out for a tap on its pile
     * (KeyguardNotificationClickToListStateStrategy): the list's origin less the target it
     * places the list's top at. Null when any of it cannot be read.
     */
    private fun listRestScroll(): Float? = runCatching {
        val model = containerModel() ?: return@runCatching null
        val sample = Xp.callMethod(Xp.callMethod(model, "getOnUpdateChildSampleStackInfo"), "getValue")
        val event = Xp.findClass("com.miui.systemui.notification.view.strategy.KeyguardStackStateChangeEvent", loader)
            .getField("CLICK_SHOW_LIST").get(null)
        val info = Xp.callMethod(model, "getKeyguardStackStatusInfo", sample, event)
        val focusHeight = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val origin = (Xp.callMethod(model, "positionWithoutMinScrollRange", focusHeight) as Number).toFloat()
        val strategy = listTopStrategy ?: Xp.findClass(
            "com.miui.systemui.notification.view.strategy.KeyguardNotificationClickToListStateStrategy", loader)
            .getDeclaredConstructor().newInstance().also { listTopStrategy = it }
        val top = (Xp.callMethod(strategy, "calculateTargetYPosition", info) as Number).toFloat()
        (origin - top).takeIf { it.isFinite() }?.coerceAtLeast(0f)
    }.onFailure { MiniPlayerRuntime.noteTouch("list rest unread: $it") }.getOrNull()

    /** The lock screen's notification stack, found once in the window. */
    private fun notificationStack(): ViewGroup? {
        (stackRef?.get() as? ViewGroup)?.takeIf { it.isAttachedToWindow }?.let { return it }
        val queue = ArrayDeque<View>()
        queue.add(host)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationStackScrollLayout") && v is ViewGroup) {
                stackRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** A notification's row in the stack, by its key. */
    private fun rowFor(key: String): View? = findRow(key)?.first

    /** [rowFor], for MiniPlayerRuntime.rowOf. */
    fun rowOf(key: String): View? = rowFor(key)

    /** [notificationStack], for MiniPlayerRuntime.stackForProbe. */
    fun stackForProbe(): ViewGroup? = notificationStack()

    fun rowTree(match: String): String {
        val stack = notificationStack() ?: return "no stack"
        val sb = StringBuilder()
        fun rows(v: View): List<View> = listOf(v) + childRows(v)
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            for (row in rows(child)) {
                val key = rowKey(row) ?: continue
                if (match.isNotEmpty() && !key.contains(match)) continue
                val pub = runCatching { Xp.callMethod(row, "isShowingPublic") }.getOrNull()
                val sens = runCatching { Xp.callMethod(row, "getSensitive") }.getOrNull()
                sb.append("ROW ").append(key).append(" public=").append(pub).append(" sensitive=")
                    .append(sens).append(" top=").append(row.translationY.toInt()).appendLine()
                dumpTree(row, 1, sb)
                if (sb.length > 60000) return sb.append("...cut").toString()
            }
        }
        return if (sb.isEmpty()) "no row matches '$match'" else sb.toString()
    }

    private fun dumpTree(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 22) return
        sb.append(" ".repeat(depth)).append(v.javaClass.simpleName)
        val id = runCatching { if (v.id > 0) v.resources.getResourceEntryName(v.id) else null }.getOrNull()
        if (id != null) sb.append(" #").append(id)
        sb.append(when (v.visibility) { View.VISIBLE -> ""; View.INVISIBLE -> " INV"; else -> " GONE" })
        if (v.alpha < 1f) sb.append(" a=").append("%.2f".format(v.alpha))
        if (v.transitionAlpha < 1f) sb.append(" ta=").append("%.2f".format(v.transitionAlpha))
        sb.append(' ').append(v.width).append('x').append(v.height)
            .append('@').append(v.left).append(',').append(v.top)
        when (v) {
            is android.widget.Chronometer -> sb.append(" CHRONO base=").append(v.base)
                .append(" down=").append(v.isCountDown).append(" text='").append(v.text).append("'")
            is android.widget.TextView -> sb.append(" '").append(v.text?.toString()?.take(60))
                .append("' sz=").append("%.0f".format(v.textSize)).append(" col=#")
                .append(Integer.toHexString(v.currentTextColor))
            is android.widget.ImageView -> v.drawable.let { d ->
                sb.append(" img=").append(d?.javaClass?.simpleName)
                    .append(' ').append(d?.intrinsicWidth).append('x').append(d?.intrinsicHeight)
            }
            is android.widget.ProgressBar -> sb.append(" progress=").append(v.progress).append('/').append(v.max)
            else -> {}
        }
        if (v.contentDescription != null) sb.append(" cd='").append(v.contentDescription.toString().take(40)).append("'")
        sb.appendLine()
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb)
    }

    /**
     * [key]'s row, and the stack's own row it is in: itself, or the group it is a child of. The
     * system groups an app's notifications by itself (高德's navigation came as two children of
     * an Aggregate_SilentSection summary), and looked for among the stack's rows alone a child
     * was never found: the media card's morph left its row standing, unfolded (2026-09-25).
     */
    private fun findRow(key: String): Pair<View, View>? {
        if (key == STACK_ISLAND || LockIslands.isAppGroup(key))
            return findRow(groupLead(key) ?: return null)
        // Given back early and drawn on as a transient view: no longer among the stack's rows.
        kept[key]?.row?.takeIf { it.isAttachedToWindow }?.let { return it to it }
        val stack = notificationStack() ?: return null
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            if (rowKey(child) == key) return child to child
            // One app, one row, as the stack groups them: a notification in a group is found as
            // the group's row, never its own child row. A child is not even drawn with the group
            // folded (the children's container INVISIBLE): three QQ messages grouped, the stack
            // island waited for a child that never came on screen and the tap did nothing, and
            // opened, the rows of one app were piled one by one (2026-09-26, the user's rule).
            for (sub in childRows(child)) if (rowKey(sub) == key) return child to child
        }
        return null
    }

    private fun rowKey(row: View): String? = runCatching {
        (Xp.getObjectField(Xp.callMethod(row, "getEntry"), "mSbn") as? android.service.notification.StatusBarNotification)?.key
    }.getOrNull()

    /** A group's rows for its children; none for a row that is not a group. */
    private fun childRows(row: View): List<View> = runCatching {
        (Xp.callMethod(row, "getAttachedChildren") as? List<*>)?.filterIsInstance<View>()
    }.getOrNull().orEmpty()

    /**
     * What the stack shows of [key] as a whole: its group's row - the group goes with its last
     * child - unless that group also holds [keep], the row an island is morphing with, which
     * would go with it: then the child's row alone.
     */
    private fun wholeRowFor(key: String, keep: View?): View? {
        val (row, top) = findRow(key) ?: return null
        if (row === top) return row
        return if (keep != null && isInside(keep, top)) row else top
    }

    private fun isInside(v: View, ancestor: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** The first shown view under [root] with one of [names] as its id, breadth first. */
    private fun findNamed(root: View, names: Set<String>, kind: (View) -> Boolean): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.isShown && kind(v)) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** Rows this controller has made invisible (transitionAlpha 0), and what they had. */
    private val hiddenRows = java.util.WeakHashMap<View, Float>()

    private fun hideRow(row: View) {
        if (row in hiddenRows) return
        trace("hide ${shortName(row)}")
        hiddenRows[row] = row.transitionAlpha
        row.transitionAlpha = 0f
    }

    private fun showRow(row: View) {
        val was = hiddenRows.remove(row) ?: return
        trace("show ${shortName(row)} ta=$was")
        row.transitionAlpha = was
    }

    /**
     * A row going back into the row of islands: invisible for as long as the stack takes to
     * animate it away, its alpha given back after that. Not on detach: the stack re-adds a
     * removed row as a transient view for exactly that animation, so the detach comes first
     * and would show it again for it. Given back whatever happens, a row can never be left
     * invisible in a stack that shows it later - the cover's, the unlocked shade's.
     */
    private fun hideRowUntilGone(row: View, key: String) {
        hideRow(row)
        handler.postDelayed({
            // Released again meanwhile and waiting to be morphed: that morph gives it back.
            // Held for the media card's morph meanwhile: that morph's end gives it back.
            if (rowWaitKey != key && !(noteMorphKey == key && morph != null) && !rowsHeld()) showRow(row)
        }, ROW_GONE_MS)
    }

    /** A row's alpha as drawn: its own and every ancestor's up to the window. */
    private fun drawnAlpha(row: View): Float {
        var a = 1f
        var v: View? = row
        while (v != null) {
            a *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        return a
    }

    /**
     * Whether a row is there to be landed on: shown, drawn at least half there, and inside the
     * screen above the row of islands. Not the stack's folded state: it read true with the row
     * plainly drawn (alpha 1, 2026-09-25), and the tap went back into its island - it is only
     * logged now.
     */
    /** Every ancestor of [v] not VISIBLE, with its id, up to the window: what isShown tripped on. */
    private fun hiddenAncestors(v: View): String {
        val out = ArrayList<String>()
        var p: View? = v.parent as? View
        while (p != null) {
            if (p.visibility != View.VISIBLE) {
                val id = runCatching { if (p.id > 0) p.resources.getResourceEntryName(p.id) else null }.getOrNull()
                out += p.javaClass.simpleName + (id?.let { "#$it" } ?: "") + "=" + p.visibility
            }
            p = p.parent as? View
        }
        return if (out.isEmpty()) "none(attached=${v.isAttachedToWindow} win=${v.windowVisibility})" else out.joinToString(">")
    }

    /** Why rowOnScreen said no, for the touch log. */
    private fun offScreenWhy(row: View): String {
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val parent = row.parent as? View
        return "${shortName(row)} v=${row.visibility} shown=${row.isShown} a=${"%.2f".format(row.alpha)} " +
            "ta=${"%.2f".format(row.transitionAlpha)} parentA=${parent?.let { "%.2f".format(drawnAlpha(it)) }} " +
            "y=${xy[1]} h=${row.height} laid=${row.isLaidOut} bottom=${player?.restBoxOnScreen()?.y?.toInt()} " +
            "folded=${stackFolded()} lead=${groupLead(STACK_ISLAND)?.let(::shortKey)} hiddenBy=" + hiddenAncestors(row) +
            (findRow(groupLead(STACK_ISLAND) ?: "")?.second?.takeIf { it !== row }?.let { top ->
                " group: v=${top.visibility} a=${"%.2f".format(top.alpha)} ta=${"%.2f".format(top.transitionAlpha)} " +
                    "h=${top.height} y=${IntArray(2).also(top::getLocationOnScreen)[1]} " +
                    "expanded=${runCatching { Xp.callMethod(top, "isGroupExpanded") }.getOrNull()}"
            } ?: "")
    }

    private fun rowOnScreen(row: View): Boolean {
        // The row's own alpha is the stack's fade-in (and our hiding): the morph owns it from
        // here. Its ancestors' are whether the stack shows it at all.
        val parent = row.parent as? View ?: return false
        if (!row.isShown || drawnAlpha(parent) < 0.5f) return false
        val xy = IntArray(2).also(row::getLocationOnScreen)
        val bottom = player?.restBoxOnScreen()?.y ?: host.height.toFloat()
        return xy[1] + row.height > 0 && xy[1] < bottom
    }

    /**
     * The stack folded into its "N notifications" count (KeyguardNotificationState.NUMBER),
     * read as CardTouch.readFoldState reads it but from the stack itself: the media card it starts
     * from is not there with a row of islands showing. Null when it cannot be read.
     */
    private fun stackFolded(): Boolean? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        val model = Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        Xp.callMethod(Xp.callMethod(model, "isInNumState"), "getValue") == true
    }.getOrNull()

    /** The stack's container model (NotificationContainerViewModel), as stackFolded reads it. */
    private fun containerModel(): Any? = runCatching {
        val stack = notificationStack() ?: return null
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
    }.getOrNull()

    /** The keyguard stack's state - NUMBER, STACK or LIST (KeyguardNotificationState); null unread. */
    private fun stackState(model: Any? = containerModel()): String? = runCatching {
        (Xp.callMethod(Xp.callMethod(model ?: return null, "getCurrentsStackState"), "getValue") as Enum<*>).name
    }.getOrNull()

    /**
     * The stack island's rows laid out as the lock screen's own list, one row an app: the stack
     * asked to do what a tap on its pile does (NotificationRowViewBinder's click on a stacked row:
     * NotificationContainerViewModel._onClicked, read 2026-09-26). The keyguard then moves its
     * notifications' top up to the list's, the clock pressed down to its smallest for them
     * (KeyguardNotificationClickToListStateStrategy), and every row is laid out whole instead of
     * piled at the bottom. The rows' ends are read from where the stack draws them (pileStack,
     * settleDy), so they go to the list as it opens. Asked again for a while if the stack is not
     * a pile yet to be tapped - the rows only just given back - and never once it is a list.
     */
    private fun showStackAsList(why: String) {
        listAskedAt = android.os.SystemClock.uptimeMillis()
        listWhy = why
        listClicked = false
        handler.removeCallbacks(listAsk)
        listAsk.run()
    }

    /** A card on its way home from the stack that the stack still lays out; null when none. */
    private fun leavingCards(): String? {
        val x = exchange ?: return null
        for (m in x.movers.values) {
            if (!m.headedHome || m.key == STACK_ISLAND || m.key == MUSIC_ISLAND) continue
            // Told to the stack now, if its turn home was waiting: the list waits for it.
            flushTurn(m.key)
            val parent = m.native.parent as? ViewGroup ?: continue
            if (parent.indexOfChild(m.native) >= 0) return shortName(m.native)
        }
        return null
    }

    private var listAskedAt = 0L
    private var listWhy = ""

    /**
     * The stack has been sent its tap for this ask: once, never again. Sent every step while the
     * stack stayed a pile - one notification, which fits whole and has no list to open into - the
     * lock screen took each for a tap of the user's, and buzzed for every one (2026-09-26).
     */
    private var listClicked = false

    /** The stack's own answer to whether a tap on its pile opens the list; null unread. */
    private fun stackWantsList(model: Any): Boolean? = runCatching {
        Xp.callMethod(Xp.callMethod(model, "getShouldNotifClickToList"), "getValue") as? Boolean
    }.getOrNull()

    private val listAsk = object : Runnable {
        override fun run() {
            // Only while the stack island is out, or on its way.
            val out = noteMorphKey == STACK_ISLAND && morph?.toNative != false ||
                exchange?.let { x -> x.expanded == STACK_ISLAND || x.pending == STACK_ISLAND } == true ||
                noteMorphKey == null && exchange == null && expandedKey() == STACK_ISLAND
            if (!out) return
            val model = containerModel() ?: return
            val state = stackState(model)
            val waited = android.os.SystemClock.uptimeMillis() - listAskedAt
            // A card on its way home still laid out in the stack: the list is worked out once,
            // from the rows there as it is asked for (ClickToListStateStrategy), and a stopwatch
            // still among them had the list stop half way up - its card gone, the notifications'
            // top never worked out again (2026-09-26). Asked once that card is out of the layout.
            val leaving = leavingCards()
            when {
                state == "LIST" -> {
                    trace("list is up ($listWhy) after ${waited}ms")
                    return
                }
                leaving != null -> if (waited % 100L < LIST_ASK_STEP_MS) trace("list waits for $leaving")
                listClicked -> {}
                state == "STACK" && stackWantsList(model) == false -> {
                    // One notification, whole where it is: the stack keeps it so.
                    trace("list not wanted ($listWhy)")
                    return
                }
                state == "STACK" -> runCatching {
                    listClicked = true
                    // SystemUI's own Unit, not ours: the module's kotlin is another class loader's,
                    // and whatever collects the click may cast what it is sent. The name put
                    // together here: as a constant in Class.forName, R8 rewrote it to the module's
                    // own obfuscated Unit ("hc2", ClassNotFoundException - 2026-09-26).
                    val unit = Class.forName(listOf("kotlin", "Unit").joinToString("."), false,
                        model.javaClass.classLoader).getField("INSTANCE").get(null)
                    Xp.callMethod(Xp.getObjectField(model, "_onClicked"), "tryEmit", unit)
                    trace("list asked ($listWhy) at ${waited}ms")
                }.onFailure { trace("list ask failed: $it") }
            }
            if (waited < LIST_ASK_MS) handler.postDelayed(this, LIST_ASK_STEP_MS)
            else trace("list not up ($listWhy): state=$state")
        }
    }

    // ------------------------------------------------------------ the shortcut discs

    private val discs = arrayOfNulls<ShortcutDisc>(2)

    /** The lock screen is locked with the mini player on: the discs go behind the buttons. updateVisibility. */
    private var discsWanted = false

    /**
     * There is a row - an island, or one out as its row - and so something for the full-screen
     * AOD to keep the buttons for (holdButtonsThroughDoze). With none, the doze takes the buttons
     * and their discs out as it always has.
     */
    private var rowWanted = false

    private val squeeze = MiniSqueeze(dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat()) { onSqueezeFrame() }
    private val buttonSqueeze = arrayOf(Matrix(), Matrix())
    private val buttonSqueezeSet = BooleanArray(2)
    /** What each button's matrix was last written as: a write invalidates the button, so only a change is written. */
    private val buttonSqueezeLast = arrayOf(FloatArray(9), FloatArray(9))
    private val buttonSqueezeValues = FloatArray(9)
    private val discPoint = FloatArray(2)
    private val discUnit = FloatArray(4)
    private val discRest = FloatArray(2)

    private fun button(side: Int) = if (side == 0) left else right

    /** A disc is a circle as tall as the pill. */
    private fun discDiameter(): Int = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))

    /** The disc's frame: room for its widest swelling and squeeze around the circle. */
    private fun discFrame(d: Int): Int = (d * DISC_FRAME).toInt()

    private fun onSqueezeFrame() {
        if (player?.visibility == View.VISIBLE) followShortcuts()
        updateDiscs()
    }

    /** A finger down on, or off, the pill ([side] PRESS_PILL) or a disc. */
    fun press(side: Int, down: Boolean) {
        when (side) {
            MiniPlayerRuntime.PRESS_PILL -> squeeze.pressPill(down)
            MiniPlayerRuntime.PRESS_SMALL -> squeeze.pressSmall(down)
            else -> squeeze.pressDisc(side, down)
        }
    }

    /**
     * Which disc's button a point is on, if its disc is showing: the icon, or the
     * shortcut_view_*_layout around it, which is what takes the tap.
     */
    fun discAt(x: Float, y: Float): Int? = (0..1).firstOrNull {
        if (discs[it]?.visibility != View.VISIBLE) return@firstOrNull false
        val icon = button(it)
        val frame = (icon.parent as? View)?.takeIf { p ->
            runCatching { p.resources.getResourceEntryName(p.id) }.getOrNull()?.endsWith("_layout") == true
        }
        onButton(icon, x, y) || frame != null && onButton(frame, x, y)
    }

    /**
     * Each disc on its button as the button is drawn - its centre, zoom and fade through
     * everything the OEM animates it with (the swipe away, the doze) - then flattened or swollen
     * by the squeeze. The button takes the same squeeze on top of its own motion through its
     * animation matrix, which the OEM does not write and drawnCentre does not read.
     */
    private fun updateDiscs() {
        // First, so that the discs below see the buttons as they will be drawn.
        holdButtonsThroughDoze()
        applyInk()
        val d = discDiameter()
        val frame = discFrame(d)
        followHost.reset()
        host.transformMatrixToGlobal(followHost)
        val parent = discParent()
        discToScreen.reset()
        parent.transformMatrixToGlobal(discToScreen)
        val placed = followHost.invert(hostInverse) && discToScreen.invert(discInverse)
        for (side in 0..1) {
            val button = button(side)
            val shown = placed && discsWanted && button.isShown && button.width > 0 && button.height > 0
            var disc = discs[side]
            if (!shown) {
                if (disc != null && disc.visibility != View.GONE) disc.visibility = View.GONE
                clearButtonSqueeze(side)
                continue
            }
            if (disc == null) {
                disc = ShortcutDisc(context)
                discs[side] = disc
            }
            if (!discInPlace(disc, parent)) {
                (disc.parent as? ViewGroup)?.removeView(disc)
                parent.addView(disc, layerSlot(parent), ViewGroup.LayoutParams(frame, frame))
                Gen.ownView(parent, disc)
            }
            if (disc.layoutParams.width != frame || disc.layoutParams.height != frame)
                resizeSmallIsland(disc, frame, frame)
            disc.dress(discMaterialGeneration()) {
                MiniPlayerRuntime.material(it, loader, notificationMaterial())
            }
            if (disc.visibility != View.VISIBLE) disc.visibility = View.VISIBLE
        }
        // The squeeze from this frame's row, then the discs from the squeeze - in that order, or
        // the discs are a frame behind the pill that is pushing them.
        squeezeScene(d.toFloat())
        for (side in 0..1) {
            val button = button(side)
            val disc = discs[side]?.takeIf { it.visibility == View.VISIBLE } ?: continue
            // In the disc's own parent's pixels: whatever that parent is doing - the doze's zoom
            // on keyguard_root_view - the disc is already drawn with.
            if (!drawnCentre(button, discPoint, discInverse)) continue
            val zoom = drawnZoom(button, discInverse)
            val outward = if (side == 0) -1f else 1f
            val cx = discPoint[0] + outward * squeeze.discShift(side) * d * zoom
            // The doze's "gone" is judged on the fade on screen; what is written is the part
            // below the parent, which applies the rest itself.
            val fade = if (unheldInDoze(chainFade(button)) == 0f) 0f
                else chainFade(button, disc.parent as? View ?: host)
            // Placed and zoomed with the button; its squeeze is its shape, at its own pixels.
            var tx = cx - frame / 2f - disc.left
            var ty = discPoint[1] - frame / 2f - disc.top
            // At rest on whole pixels, as the pill (MiniPlayerView.rest): the glass is drawn on
            // the pixel grid and the outline clip is not, so a disc left on a half pixel had its
            // rim cut into steps on one side - a button centre at x.5 on a 1280px screen
            // (warsaw, 2026-09-30), while the pill beside it on whole pixels was smooth.
            if (kotlin.math.abs(zoom - 1f) < 0.0005f && squeeze.discShift(side) == 0f) {
                tx = kotlin.math.round(tx)
                ty = kotlin.math.round(ty)
                if (disc.translationX != tx) disc.translationX = tx
                if (disc.translationY != ty) disc.translationY = ty
            }
            setIfChanged(disc, tx, ty, zoom, zoom, fade)
            disc.setShape((d * squeeze.discScaleX(side)).roundToInt().coerceIn(1, frame),
                (d * squeeze.discScaleY(side)).roundToInt().coerceIn(1, frame))
            squeezeButton(side, button, d)
        }
    }

    // ---- the torch and the camera through the doze

    /**
     * The doze does not put the torch and the camera away: it leaves them shown and fades
     * their chain out to a hundredth (`op mini` discs trace, 2026-09-25). The row keeps them:
     * every frame of the doze, before it is drawn, each view from a button's image up to the
     * keyguard's root is put back to full alpha over whatever the doze's animation wrote there
     * this frame - so the buttons are the lock screen's own, drawn as ever, and their discs
     * simply stay on them. The first version drew the glyphs itself onto the discs instead:
     * too big, the torch's half clear, and a flash at every hand-over.
     *
     * After the wake it lets go only once the lock screen's own alpha has been back at full
     * for a moment: it can show a button whole for a frame and then start fading it in from
     * nothing, and let go on that frame the buttons would drop with it.
     *
     * None of that runs in the full-screen doze any more (2026-10-09): 息屏时隐藏锁屏快捷功能 is
     * always on, so the shortcuts are left to the doze's own fade rather than held at full - the
     * torch and the camera dim to a hundredth with the rest of the chain, and the discs follow
     * them down (unheldInDoze). The row this holds for is the one the doze leaves alone.
     */
    private var holdButtons = false
    private var holdSince = 0L
    private var backSince = 0L

    private fun holdButtonsThroughDoze() {
        if (config.optBoolean(MiniPlayerConfig.HIDE_AOD_SHORTCUTS) &&
            MiniPlayerScene.fullScreenAodActive) return
        val root = followRoot?.get()
        val now = android.os.SystemClock.uptimeMillis()
        if (MiniPlayerScene.fullScreenAodActive && rowWanted && root != null) {
            holdButtons = true
            holdSince = now
        }
        if (!holdButtons) return
        // Any other AOD is the OEM's: its fade is let through (unheldInDoze).
        if (MiniPlayerScene.customAodActive) {
            holdButtons = false
            backSince = 0L
            return
        }
        // The pad up with the hold still on, put up by something other than a swipe (letGoOnDrag).
        if (!MiniPlayerScene.aodActive && Main.bouncerShown()) {
            letGoOfButtons("bouncer")
            return
        }
        // The lower of the two chains as the doze left it this frame, before it is put back.
        var natural = 1f
        for (side in 0..1) {
            var v: View? = findImage(button(side)) ?: button(side)
            while (v != null && v !== root && v !== host) {
                natural = min(natural, v.alpha * v.transitionAlpha)
                if (v.alpha < 1f) v.alpha = 1f
                if (v.transitionAlpha < 1f) v.transitionAlpha = 1f
                v = v.parent as? View
            }
        }
        if (MiniPlayerScene.aodActive) {
            backSince = 0L
            return
        }
        if (natural < 0.99f) backSince = 0L
        else if (backSince == 0L) backSince = now
        if (backSince != 0L && now - backSince > HOLD_SETTLE_MS || now - holdSince > HOLD_MAX_MS ||
            !discsWanted) {
            holdButtons = false
            backSince = 0L
        }
    }

    /**
     * The swipe up within the wake's hold: it let go only once the buttons had been back at full
     * for a while, or two seconds on - and while the swipe faded them it never was, so every
     * frame it wrote them back to 1 and the pill stayed on the held branch, deaf to the row's
     * fade. The torch, the camera and the pill stood over the PIN pad on the first swipe after
     * the wake, and were gone on the next (reported 2026-10-07). A drag past the slop, not the
     * DOWN: a double tap to wake lands in the wake's fade-in, and letting go there drops the
     * buttons to it. True once past the slop, whether or not there was a hold.
     */
    fun letGoOnDrag(dx: Float, dy: Float): Boolean {
        if (kotlin.math.hypot(dx, dy) <= ViewConfiguration.get(context).scaledTouchSlop) return false
        letGoOfButtons("swipe")
        return true
    }

    /**
     * The lock screen is leaving: the buttons are the OEM's again, and the pill follows the row's
     * fade from this frame rather than waiting for it to come back to full - it will not, until
     * the pad is put away.
     */
    private fun letGoOfButtons(why: String) {
        if (MiniPlayerScene.aodActive || !holdButtons && !rowHeldOff) return
        holdButtons = false
        backSince = 0L
        rowHeldOff = false
        MiniPlayerRuntime.noteTouch("buttons let go: $why")
    }

    /**
     * The row at rest, in host pixels, for the squeeze: the pill's rest frame with its nudge
     * (or a morph's container as drawn) and each showing disc on its button's laid-out centre.
     */
    private fun squeezeScene(d: Float) {
        fun disc(side: Int): CoverMorphMotion.Box? {
            if (discs[side]?.visibility != View.VISIBLE) return null
            restCentre(button(side), discRest)
            return CoverMorphMotion.Box(discRest[0] - d / 2f, discRest[1] - d / 2f, d, d)
        }
        val hostXY = IntArray(2).also(host::getLocationOnScreen)
        // A flight's morph is the flight's, and the small island's view in a group is not the
        // pill's: the row pressing on the discs is still the pill's.
        val running = pillMorph()
        val view = player
        val pill: CoverMorphMotion.Box? = when {
            running != null -> running.containerBox()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0], it.y - hostXY[1], it.w, it.h)
            }
            view != null && view.visibility == View.VISIBLE -> view.restBoxOnScreen()?.let {
                CoverMorphMotion.Box(it.x - hostXY[0] + view.nudgeX, it.y - hostXY[1] + view.nudgeY,
                    it.w, it.h)
            }
            else -> null
        }
        // The small island at rest where the finger has it; the row is worked out from the two.
        // Only one that has settled counts: one still appearing or leaving sits in the place the
        // pill is taking over from it, and by design the two overlap there - read as a press, the
        // pill's give snapped to its 6% cap in a single frame every time a pull reached the row's
        // end (measured 2026-09-29, `op mini` islandDrag: sq 0.999 -> 0.934 in 6ms, five pulls
        // running). The list's own idiom for a small island really there (showing).
        // The pill only meets it while it gives (setScene); a morph in the pill's place does not.
        val small = smallIsland?.takeIf { !stackedStyle() && it.visibility == View.VISIBLE && swap == null &&
            landingBox == null && (!smallGrowing || smallGrow.atRest()) }
            ?.let { CoverMorphMotion.Box(smallRest[0] - d / 2f + smallNudgeX(),
                smallRest[1] - d / 2f + smallNudgeY(), d, d) }
        // Not the pill's, but no less in the way: a notification's island on its way up out of
        // the row or down into it - a flight, the other half of a group, an exchange's movers -
        // swept through the discs, the pill and the small island without touching them (reported
        // 2026-09-30). It pushes each away from the place it leaves or comes home to, and not
        // the one whose place that is: that one is its own.
        squeezePushers.clear()
        fun mover(m: MiniCardMorph?) {
            if (m == null || m === running) return
            val it = m.containerBox() ?: return
            val box = CoverMorphMotion.Box(it.x - hostXY[0], it.y - hostXY[1], it.w, it.h)
            val endX = m.miniEndBox()?.cx()?.minus(hostXY[0]) ?: box.cx()
            fun away(b: CoverMorphMotion.Box?) = b != null && (endX < b.x || endX > b.x + b.w)
            squeezePushers += MiniSqueeze.Pusher(box, endX, away(pill), away(small))
        }
        mover(morph)
        mover(group?.follower)
        exchange?.movers?.values?.forEach { if (it.morph !== morph) mover(it.morph) }
        squeeze.setScene(pill, running == null, disc(0), disc(1), small, squeezePushers)
    }

    private val squeezePushers = ArrayList<MiniSqueeze.Pusher>(4)

    /**
     * The button rides on its disc in its own slot: moved with it, and swollen or shrunk about
     * its drawn centre there. Both count - a disc only pushed aside, not flattened, left its
     * icon behind when only the scale was checked.
     */
    private fun squeezeButton(side: Int, button: View, d: Int) {
        val k = squeeze.iconScale(side) *
            (configuredHeightDp / MiniPlayerConfig.DEFAULT_HEIGHT_DP)
        val outward = if (side == 0) -1f else 1f
        // The disc's shift in the button's own pixels: the button's own scale, not its row's.
        val shift = outward * squeeze.discShift(side) * d * button.scaleX
        if (kotlin.math.abs(k - 1f) < 0.0005f && kotlin.math.abs(shift) < 0.25f) {
            clearButtonSqueeze(side)
            return
        }
        val m = buttonSqueeze[side]
        m.setScale(k, k, button.translationX + button.width / 2f,
            button.translationY + button.height / 2f)
        m.postTranslate(shift, 0f)
        // A disc held flattened at rest is the same matrix every frame; written each time, it
        // redrew the lock screen every vsync with nothing moving.
        m.getValues(buttonSqueezeValues)
        if (buttonSqueezeSet[side] && buttonSqueezeValues.contentEquals(buttonSqueezeLast[side])) return
        System.arraycopy(buttonSqueezeValues, 0, buttonSqueezeLast[side], 0, 9)
        button.setAnimationMatrix(m)
        buttonSqueezeSet[side] = true
    }

    private fun clearButtonSqueeze(side: Int) {
        if (!buttonSqueezeSet[side]) return
        buttonSqueezeSet[side] = false
        button(side).setAnimationMatrix(null)
    }

    /** How much a view is scaled on screen, every ancestor's transform included. */
    private fun drawnZoom(v: View, inverse: Matrix = hostInverse): Float {
        scratch.reset()
        v.transformMatrixToGlobal(scratch)
        scratch.postConcat(inverse)
        discUnit[0] = 0f
        discUnit[1] = 0f
        discUnit[2] = 1f
        discUnit[3] = 0f
        scratch.mapPoints(discUnit)
        return kotlin.math.hypot(discUnit[2] - discUnit[0], discUnit[3] - discUnit[1])
    }

    /** A view's fade on screen: its own and every ancestor's, up to the host. */
    /**
     * A doze that does not hold the buttons (holdButtonsThroughDoze) - any AOD but the
     * full-screen one, or that one with no row - takes the pill, the small island and the discs
     * out on the lock screen's own fade, with the buttons. That fade ends near a hundredth, not
     * at nothing; the rest is taken to nothing, so no glass is left on the AOD. Their islands
     * stay as they are - hiding the pill instead let every island's notification back into the
     * stack for the doze and out again at the wake.
     */
    private fun unheldInDoze(fade: Float): Float =
        if (MiniPlayerScene.aodActive && !holdButtons && fade < DOZE_GONE) 0f else fade

    private fun chainFade(v: View, upTo: View = host): Float {
        var fade = 1f
        var cur: View? = v
        while (cur != null && cur !== upTo && cur !== host) {
            fade *= cur.alpha * cur.transitionAlpha
            cur = cur.parent as? View
        }
        return fade
    }

    /** Only real changes: each write invalidates, and a disc at rest must not redraw. */
    private fun setIfChanged(v: View, tx: Float, ty: Float, sx: Float, sy: Float, alpha: Float) {
        if (kotlin.math.abs(v.translationX - tx) > 0.25f) v.translationX = tx
        if (kotlin.math.abs(v.translationY - ty) > 0.25f) v.translationY = ty
        if (kotlin.math.abs(v.scaleX - sx) > 0.0005f) v.scaleX = sx
        if (kotlin.math.abs(v.scaleY - sy) > 0.0005f) v.scaleY = sy
        if (kotlin.math.abs(v.alpha - alpha) > 0.002f) v.alpha = alpha
    }

    private fun removeDiscs() {
        squeeze.reset()
        for (side in 0..1) {
            clearButtonSqueeze(side)
            discs[side]?.let { d -> runCatching { (d.parent as? ViewGroup)?.removeView(d) } }
            discs[side] = null
        }
    }

    /** keyguard_bottom_area above the torch, and keyguard_root_view above that. */
    private fun findRow(): View? {
        var row: View? = null
        var v: View? = left
        while (v != null && v !== host) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (name == "keyguard_bottom_area" && row == null) row = v
            if (name == "keyguard_root_view") followRoot = WeakReference(v)
            v = v.parent as? View
        }
        return row ?: left.parent as? View
    }

    /**
     * The shortcut row's layout listeners, kept so destroy() can take them off. The row is the
     * OEM's and outlives every controller; anonymous lambdas left on it called a destroyed
     * controller and piled up one pair per rebuild.
     */
    private val leftLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        schedulePosition()
    }
    private val rightLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        schedulePosition()
    }

    init {
        host.clipChildren = false
        host.clipToPadding = false
        Gen.prefListener(prefs, prefListener)
        Gen.preDraw(host, preDraw)
        Gen.layoutChange(left, leftLayoutListener)
        Gen.layoutChange(right, rightLayoutListener)
        runCatching { sessions?.let { Gen.activeSessionsListener(it, sessionListener, handler) } }
        LockIslands.addListener(islandListener)
        // Posted: run here, it came before the properties declared below this block were
        // made, and failed on the first of them (focusLotties) every time SystemUI started.
        scheduleRefresh()
    }

    fun destroy() {
        destroyed = true
        Gen.unprefListener(prefs, prefListener)
        runCatching { Gen.unpreDraw(host, preDraw) }
        runCatching { Gen.unlayoutChange(left, leftLayoutListener) }
        runCatching { Gen.unlayoutChange(right, rightLayoutListener) }
        runCatching { sessions?.let { Gen.unactiveSessionsListener(it, sessionListener) } }
        runCatching { controller?.let { Gen.unsessionCallback(it, mediaListener) } }
        followLive(null)
        morph?.cancel()
        if (piles.isNotEmpty() || pileMoving) finishPile(true)
        // Each of these ends itself once its own state has, but some wait on a frame of a tree
        // this runtime no longer watches: gone with it, not left asking for vsyncs. Through Gen,
        // because what is queued on the Choreographer is its wrapper, not the callback itself.
        listOf(pulseFrame, smallNudgeFrame, swapFrame, rowFrame, appearFrame, smallGrowFrame,
            pileSettle, spreadFrame, switchWait, rowWait).forEach { Gen.unframe(it) }
        // The waits for a row would otherwise go on to open it, on a controller already gone;
        // and a card still pinned is the stack's again, drawn where the stack has it.
        rowWaitKey = null
        unpinCard()
        if (spread != null) {
            spread?.items?.forEach { it.morph?.cancel() }
            spread = null
            LockIslands.setSpread(false, folded = true)
        }
        restoreHeader()
        removeDiscs()
        LockIslands.removeListener(islandListener)
        LockIslands.setActive(false)
        LockIslands.stopFolding()
        removeSmallIsland()
        dropFlight()
        spareViews.forEach { runCatching { host.removeView(it) } }
        spareViews.clear()
        player?.let { runCatching { host.removeView(it) } }
        player = null
        controller = null
        handler.removeCallbacksAndMessages(null)
    }

    fun shortcutGeometry(): FloatArray? {
        if (host.width <= 0 || left.width <= 0 || right.width <= 0) return null
        val l = restCentre(left)
        val r = restCentre(right)
        val li = iconSize(left)
        val ri = iconSize(right)
        return floatArrayOf(host.width.toFloat(), l[0], l[1], li[0], li[1], r[0], r[1], ri[0], ri[1])
    }

    /**
     * The glyph inside a shortcut button, as drawn: its image fitted into the padded box, then
     * only the part that has ink. The drawable's own box carries a wide transparent margin -
     * taken whole, the preview drew the icons several times too big. 0 if there is none.
     */
    private fun iconSize(v: View): FloatArray {
        val image = findImage(v) ?: return floatArrayOf(0f, 0f)
        val w = (image.width - image.paddingLeft - image.paddingRight).toFloat()
        val h = (image.height - image.paddingTop - image.paddingBottom).toFloat()
        val d = image.drawable
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f || iw <= 0f || ih <= 0f) return floatArrayOf(0f, 0f)
        val k = min(w / iw, h / ih)
        return runCatching { inkSize(d, (iw * k).toInt(), (ih * k).toInt()) }.getOrNull()
            ?: floatArrayOf(0f, 0f)
    }

    /** The opaque extent of a drawable drawn at this size, in pixels. */
    private fun inkSize(d: android.graphics.drawable.Drawable, w: Int, h: Int): FloatArray? {
        if (w <= 0 || h <= 0) return null
        val copy = d.constantState?.newDrawable(context.resources)?.mutate() ?: return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            copy.setBounds(0, 0, w, h)
            copy.draw(android.graphics.Canvas(bitmap))
            val row = IntArray(w)
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if ((row[x] ushr 24) < INK_ALPHA) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            return floatArrayOf((maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    /** A pixel this opaque counts as the glyph's ink. */
    private val INK_ALPHA = 40

    private fun findImage(v: View): ImageView? {
        if (v is ImageView && v.drawable != null && v.visibility == View.VISIBLE) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) findImage(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---- the landing, frame by frame, for `op mini`

    private val landTrace = ArrayDeque<String>()

    /** Frames the running notification morph has spent at its island's end. */
    private var restFrames = 0

    private fun trace(what: String) {
        landTrace.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what")
        while (landTrace.size > 600) landTrace.removeFirst()
    }

    /** The small island and the row as they stand this frame, in host pixels. */
    private fun smallState(): String {
        val v = smallIsland ?: return "no small"
        val pill = player
        return "sk=${smallKey?.takeLast(6)} v=${v.visibility} a=${"%.2f".format(v.alpha)} " +
            "ta=${"%.2f".format(v.transitionAlpha)} tx=${v.translationX.toInt()} l=${v.left} " +
            "w=${v.width}/${v.layoutParams.width} rest=${smallRest[0].toInt()} " +
            "pill=${pill?.left?.plus(pill.translationX)?.toInt()}+${pill?.width}/${pill?.layoutParams?.width} " +
            "row=${rowLeft.value.toInt()}+${rowWidth.value.toInt()}>${rowWidth.target.toInt()} " +
            "grow=$smallGrowing:${"%.2f".format(smallGrow.value)} keys=${islandKeys.size} ret=${returning.size} " +
            "held=${pillHeld()} pta=${"%.2f".format(pill?.transitionAlpha ?: -1f)} " +
            "pca=${"%.2f".format(pill?.contentAlphaNow() ?: -1f)} fca=${"%.2f".format(flight?.contentAlphaNow() ?: -1f)} " +
            "ghost=${ghostPill?.let { "${it.visibility}/${"%.2f".format(it.alpha)}/${it.width}" }} swap=${swap?.smallMode}"
    }

    fun describe(): String {
        val native = expandedKey()?.let(::nativeFor)
        val nativeY = native?.let { n -> IntArray(2).also(n::getLocationOnScreen)[1] }
        val sink = " nativeY=$nativeY nativeTy=${native?.translationY} nativeTarget=${native?.let(::stackTargetY)} " +
            "pin=${pinned?.get() != null} frozen=$pinnedAt pinY=${pinY.value} pinDy=$pinnedDy " +
            "stackTy=${notificationStack()?.translationY} "
        val islands = describeSession() + describeSpread() + " rowAnim=$rowAnimating swap=${swap != null} noteMorph=${noteMorphKey != null} " +
            "flight=${flight != null} drag=${noteDrag != null} ${player?.touchState()} " +
            "out=${expandedKey()?.takeLast(12)} xchg=${exchange?.let { x -> "out=${x.expanded?.takeLast(6)} pend=${x.pending?.takeLast(6)} " +
                "seats=${x.seats?.big?.takeLast(6)}/${x.seats?.small?.takeLast(6)} " +
                x.movers.values.joinToString(" ") { moverState(it) } }} " +
            "row=${islandKeys.size} sel=${selectedIsland?.takeLast(24)} " +
            "small=${smallKey?.takeLast(24)} smallShown=${smallIsland?.visibility == View.VISIBLE} " +
            "follow=$followBranch " + sink
        val v = player ?: return islands + "no pill"
        val xy = IntArray(2).also(v::getLocationOnScreen)
        val icons = "pillArt=[${v.artworkState()}] smallIcon=[${smallIsland?.iconState()}] " +
            focusLotties.entries.joinToString(" ") { (owner, held) ->
                val d = held.second.drawable
                val shownIn = when (owner) {
                    is MiniPlayerView -> owner.artworkDrawable.let { it === d || it === lottieWatches[d] }
                    else -> d?.callback != null
                }
                "lottie[${if (owner === v) "pill" else owner.javaClass.simpleName} ${held.first} " +
                    "anim=${runCatching { Xp.callMethod(held.second, "isAnimating") }.getOrNull()} " +
                    "p=${runCatching { Xp.callMethod(held.second, "getProgress") }.getOrNull()} " +
                    "vis=${d?.isVisible} cb=${d?.callback?.javaClass?.simpleName} " +
                    "shown=$shownIn]"
            } + liveArts.entries.joinToString(" ", prefix = " ") { (owner, held) ->
                "live[${if (owner === v) "pill" else owner.javaClass.simpleName} ${held.first} " +
                    "attached=${held.second.isAttachedToWindow} shown=${held.second.isShown} " +
                    "${held.second.width}x${held.second.height}]"
            } + " lottieWhy=[$focusLottieWhy] failed=$focusLottieFailed lottieLog=[" +
            synchronized(lottieLog) { lottieLog.joinToString(" ; ") } + "] "
        val h = header?.get()
        return islands + icons + "pill v=${v.visibility} a=${v.alpha} ta=${v.transitionAlpha} at=${xy[0]},${xy[1]} " +
            "${v.width}x${v.height} morph=${morph != null} header=" +
            (if (h == null) "none" else "v=${h.visibility} a=${h.alpha} ta=${h.transitionAlpha}") +
            " last=[$lastPresentationLog] || land: " + landTrace.joinToString(" ; ")
    }
    fun nativeHeaderHidden(): Boolean = nativeSuppressionRequested
    fun visibleHeightDp(): Float = configuredHeightDp

    fun transitionActive(): Boolean = morph != null
    /**
     * Only the media card's own morph lets the OEM show it. [morph] is a notification's too: while
     * one opened or went home, the stack's setVisibility(VISIBLE) on the header went through, and
     * with notifications coming one after another the card flashed up (the user, 2026-09-26).
     * Whose morph it is, is told the same way as cardMoving in updateVisibility.
     */
    fun hardSuppressionActive(): Boolean = nativeSuppressionRequested &&
        (morph == null || noteMorphKey != null && noteMorphKey != MUSIC_ISLAND)

    /** A morph into the cover or the lyrics landed on the card at this uptime, the scene not up yet. */
    private var sceneLandedAt = 0L

    private val morphListener = object : MiniCardMorph.Listener {
        /** A scene exit hands the pill back only once the cover has let go of the lock screen. */
        override fun canSettle(morph: MiniCardMorph, toNative: Boolean) =
            toNative || !morphScene || !Main.coverSceneActive()

        override fun artBridged() = artBridged

        override fun onFrame(morph: MiniCardMorph, progress: Float) = groupFrame(morph)

        override fun onSettled(morph: MiniCardMorph, toNative: Boolean, completed: Boolean) {
            if (this@MiniPlayerController.morph !== morph) return
            this@MiniPlayerController.morph = null
            // Into the cover or the lyrics: landed on the card a moment before the scene has
            // the lock screen, the pill was the lock screen's again for those frames and came
            // up bare between the torch and the camera - its artwork still in the flight
            // (filmed 2026-09-25). It stays down until the scene is up, or not coming.
            if (toNative && morphScene) sceneLandedAt = android.os.SystemClock.uptimeMillis()
            morphScene = false
            Xp.log("MCMini: container morph ${if (completed) "landed" else "cancelled"} " +
                "at ${if (toNative) "native" else "mini"}")
            endGroup(toNative)
            position()
            updateVisibility()
        }
    }

    /**
     * The music on its way up into the cover in a switch, and the cover asked to go: the island
     * that came down into the pill goes back up as its card and the music comes home. The
     * caller is taking the cover away itself, so the music's way home is not the cover's to end
     * again (sendDown turns the scene for a mover whose card is the cover).
     */
    private fun turnSwitchOutOfCover(x: Switch, music: Mover): Boolean {
        val big = x.seats?.big?.takeIf { it != MUSIC_ISLAND } ?: return false
        music.cover = false
        requestUp(x, big, fromPill = true, cover = false)
        MiniPlayerRuntime.noteTouch("cover out turns the switch: ${big.takeLast(6)} up, music home")
        return true
    }

    /**
     * Starts, or turns round, the container morph. A dynamic switch goes wherever the selection
     * now points; a scene route needs the mini player to be the selected presentation.
     */
    fun beginTransition(toNative: Boolean, scene: Boolean): Boolean {
        // The stack spread out as its list - with the music alone, that list is the media card -
        // and the card's artwork tapped into the cover: the spread stayed open under the scene,
        // and updateVisibility leaves everything to an open spread, so the card stood beside the
        // pill from then on, through the cover and after it (#82/#78; reproduced 2026-10-14,
        // `op mini` showed "spread open" with no end). The scene takes the card from here.
        spread?.takeIf { scene }?.let { s ->
            val token = controller?.sessionToken
            // Into the scene with the music out as the list's card: that card is the choice,
            // as one opened from the pill would be. Chosen before the spread ends, so the refresh
            // it ends with keeps the card and not the pill; and no morph - run from the pill, the
            // card played its opening over again (the user, 2026-10-14).
            val musicOut = toNative && token != null && s.items.any { it.key == MUSIC_ISLAND }
            if (musicOut) MiniPlayerRuntime.noteNative(token!!, "spread into the scene")
            endSpread(folded = false, why = "scene")
            if (musicOut) return false
        }
        morph?.let { running ->
            running.aim(toNative)
            morphScene = scene
            // A flight turned round holds its home again, or frees it.
            if (noteMorphKey == MUSIC_ISLAND) setFlightOut(toNative)
            return true
        }
        // The music exchanged into the cover with the island out, or out of it for an island
        // opened there: the exchange is the scene's morph - when it is going the scene's way.
        if (scene) exchange?.takeIf { it.has(MUSIC_ISLAND) }?.let { x ->
            val m = x.movers[MUSIC_ISLAND]
            when {
                toNative || m != null && m.headedHome -> return true
                // Pulled down out of the cover while the switch still had the music on its way
                // up into it: taken as going the scene's way, the switch landed the music as the
                // cover's card, the cover went, and the music was left out as a card nobody
                // showed - in neither the pill nor the card (filmed 2026-09-29 20:54, 460ms after
                // the switch). Turned round now, as a tap on the pill turns a switch.
                m?.morph != null -> return turnSwitchOutOfCover(x, m)
                // Already landed as the cover's card, the rest still moving: they land now, and
                // the music flies home as it does with no switch.
                else -> {
                    x.movers.values.toList().forEach { it.morph?.cancel() }
                    if (exchange != null) return false
                }
            }
        }
        val what = if (scene) "scene morph" else "switch morph"
        val view = player ?: return MiniPlayerRuntime.refused(what, "no pill")
        val token = controller?.sessionToken ?: return MiniPlayerRuntime.refused(what, "no session")
        // The card already chosen: nothing to become.
        if (scene && MiniPlayerRuntime.nativeRequested(token)) return false
        if (!view.isAttachedToWindow) return MiniPlayerRuntime.refused(what, "pill detached")
        if (!Main.miniPlayerMorphAllowed()) {
            return MiniPlayerRuntime.refused(what, "not allowed " + Main.miniPlayerMorphWhy())
        }
        val native = transitionHeader() ?: return MiniPlayerRuntime.refused(what, "no card")
        if (scene && othersBesideMusic()) return startSceneFlight(native, toNative)
        // A switch still settling is finished where it was headed: the card morph has the pill.
        val next = prepareGroup(native, toNative)
            ?: return MiniPlayerRuntime.refused(what, "no group " + groupRefusal(toNative))
        morph = next
        morphScene = scene
        if (!next.start()) {
            morph = null
            morphScene = false
            endGroup(!toNative)
            updateVisibility()
            return MiniPlayerRuntime.refused(what, "no geometry; switching in place")
        }
        return true
    }

    fun pill(): MiniPlayerView? = player

    /** The music's island - the pill or the small island, whichever holds it - drawn centre. */
    fun musicIslandCentreOnScreen(): FloatArray? {
        val v: View = when (MUSIC_ISLAND) {
            selectedIsland -> player
            smallKey -> smallIsland
            else -> null
        }?.takeIf { it.isShown && it.isAttachedToWindow && it.width > 0 } ?: return null
        val m = Matrix()
        v.transformMatrixToGlobal(m)
        val out = floatArrayOf(v.width / 2f, v.height / 2f)
        m.mapPoints(out)
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        v.getLocationOnScreen(onScreen)
        v.getLocationInWindow(inWindow)
        out[0] += (onScreen[0] - inWindow[0]).toFloat()
        out[1] += (onScreen[1] - inWindow[1]).toFloat()
        return out
    }

    /**
     * The music's island at rest: the small island's circle when the music sits there, else the
     * pill's rest box - the music is in its card now, so neither is drawn where it will be.
     */
    fun musicIslandHomeOnScreen(): FloatArray? {
        val box = if (smallKey == MUSIC_ISLAND && smallIsland != null) smallBoxOnScreen()
            else player?.restBoxOnScreen() ?: return null
        return floatArrayOf(box.cx(), box.cy())
    }

    /** The row can be shown and touched: with music, the media card has to be up; without, not. */
    private fun canShow(): Boolean =
        // The music on its way in or out of its card or the cover is not the row's to gate: the
        // row takes taps meanwhile, to turn the switch round (2026-09-25).
        if (MUSIC_ISLAND in islandKeys && exchange == null && noteMorphKey != MUSIC_ISLAND)
            Main.miniPlayerCanShow() else Main.miniPlayerIslandsCanShow()

    /** A morph moving on its springs under this point - the switch's, or a scene's. */
    /** A notification's morph is not the music's to catch: its drag engine would drive it wrong. */
    fun catchableAt(x: Float, y: Float): Boolean = noteDrag == null && morph?.catchableAt(x, y) == true

    /** Whether the running morph belongs to a cover or lyrics entry or exit. */
    fun morphIsScene(): Boolean = morph != null && morphScene

    /** Lets go of a caught scene morph toward an end, the scene itself decided by the caller. */
    fun releaseMorph(toNative: Boolean, velocity: Float) {
        morph?.release(toNative, velocity)
        if (noteMorphKey == MUSIC_ISLAND) setFlightOut(toNative)
    }

    fun grabMorph(): MiniCardMorph.Grab? = morph?.grab()

    fun cardAbovePill(): Boolean {
        // A notification's row is always above its island.
        if (noteMorphKey != null) return true
        val pill = player?.restBoxOnScreen() ?: return true
        val card = transitionHeader() ?: return true
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return xy[1] <= pill.y
    }

    /** Where a cover flight starts or lands: the artwork as a running morph draws it, else its slot. */
    fun artworkRestBox(): CoverMorphMotion.Box? = morph?.artworkBox()
        ?: exchange?.movers?.get(MUSIC_ISLAND)?.morph?.artworkBox()
        ?: musicSmallArtBox()
        ?: player?.artworkRestBoxOnScreen()

    /**
     * The music resting as the small island: its picture's place in the circle. The cover pulled
     * down into the small island flew on to the pill's artwork slot once the exchange had ended
     * - the pill holding a notification by then - and faded out over it (filmed 2026-09-25).
     */
    private fun musicSmallArtBox(): CoverMorphMotion.Box? {
        if (smallKey != MUSIC_ISLAND || smallIsland == null || pillShowsMusic) return null
        val b = smallBoxOnScreen()
        val side = b.h * MiniCardMorph.CIRCLE_ICON_SHARE
        return CoverMorphMotion.Box(b.x + (b.w - side) / 2f, b.y + (b.h - side) / 2f, side, side)
    }

    fun artworkRadius(): Float? = musicSmallArtBox()?.let {
        (config.getDouble(MiniPlayerConfig.ART_RADIUS).toFloat() * context.resources.displayMetrics.density)
            .coerceIn(0f, it.h / 2f)
    } ?: player?.artworkRestRadius()

    fun setArtBridged(bridged: Boolean) {
        artBridged = bridged
        applyArtBridge()
    }

    /**
     * Hides the artwork the cover's copy stands in for, wherever the music is drawn now. Asked
     * again whenever that changes under a running flight: into the cover out of an exchange,
     * the bridge was set once while the exchange still waited for the card - the pill, still the
     * music's, was hidden - and the island that landed in the pill had its icon hidden with it
     * until the flight ended (filmed 2026-09-25, Amap in the pill with no icon).
     */
    private fun applyArtBridge() {
        val bridged = artBridged
        // The music's artwork: the small island's view's while the music is the small island.
        val small = group?.takeIf { it.musicSmall }?.smallView
        small?.setArtworkHidden(bridged)
        // The music as a flight: the flight's artwork is the one the cover's stands in for.
        val flying = flight?.takeIf { noteMorphKey == MUSIC_ISLAND }
            ?: exchange?.movers?.get(MUSIC_ISLAND)?.view
        flying?.setArtworkHidden(bridged)
        // Where the music comes to rest, hidden too till the cover's copy has landed: the pill
        // a flight lands on is shown under it before the end, and the small island takes over
        // from its flight at the same point - both drew the music's picture there while the
        // cover's was still on its way down, two pictures at once (filmed 2026-09-25, 22:44).
        player?.setArtworkHidden(bridged && small == null && pillShowsMusic)
        smallIsland?.setIconHidden(bridged && smallKey == MUSIC_ISLAND)
    }

    private fun transitionHeader(): View? {
        val current = header?.get()?.takeIf { it.isAttachedToWindow }
            ?: Main.miniPlayerMediaHeader()
        if (current != null) header = WeakReference(current)
        return current
    }

    fun wantsNativeArtworkGesture(): Boolean {
        val token = controller?.sessionToken
        return config.getBoolean(MiniPlayerConfig.ENABLED) &&
            MiniPlayerRuntime.nativeRequested(token) &&
            Main.miniPlayerCanShow()
    }

    fun density(): Float = context.resources.displayMetrics.density

    /** The dynamic switch can be pulled from this end right now. */
    fun canDrag(fromNative: Boolean, small: Boolean = false): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED) || morph != null || spread != null) return false
        // With other islands in the row the music goes out and back as a flight, as they do.
        if (musicFlies() || exchange != null) return false
        // Only the music island has a card to open into - in the pill, or as the small island.
        if (!fromNative && (if (small) smallKey != MUSIC_ISLAND else !selectedIsMusic())) return false
        val token = controller?.sessionToken ?: return false
        if (!Main.miniPlayerMorphAllowed()) return false
        return MiniPlayerRuntime.nativeRequested(token) == fromNative
    }

    /** Which of canDrag's answers it was, for [MiniPlayerRuntime.refused]. */
    fun dragRefusal(fromNative: Boolean, small: Boolean): String = when {
        !config.getBoolean(MiniPlayerConfig.ENABLED) -> "off"
        morph != null -> "morph running"
        spread != null -> "spread"
        musicFlies() -> "music flies"
        exchange != null -> "exchange"
        !fromNative && (if (small) smallKey != MUSIC_ISLAND else !selectedIsMusic()) -> "not the music"
        controller?.sessionToken == null -> "no session"
        !Main.miniPlayerMorphAllowed() -> "not allowed " + Main.miniPlayerMorphWhy()
        else -> "nativeRequested=${MiniPlayerRuntime.nativeRequested(controller?.sessionToken)}"
    }

    /** How far apart the pill and the card are: what a full pull covers. */
    fun dragSpan(): Float? {
        if (noteMorphKey != null) return noteSpan.takeIf { it > 0f }
        val pill = player?.restBoxOnScreen() ?: return null
        val card = transitionHeader() ?: return null
        val xy = IntArray(2).also(card::getLocationOnScreen)
        return kotlin.math.abs(pill.y - xy[1]).coerceAtLeast(160f * density())
    }

    fun beginDragMorph(fromNative: Boolean): Boolean { android.os.Trace.beginSection("MC beginDragMorph"); try {
        if (player == null) return MiniPlayerRuntime.refused("drag", "no pill")
        val native = transitionHeader() ?: return MiniPlayerRuntime.refused("drag", "no card")
        // Starts at the end it was pulled from; release() aims it once the lift decides.
        val next = prepareGroup(native, toNative = !fromNative)
            ?: return MiniPlayerRuntime.refused("drag", "no group " + groupRefusal(!fromNative))
        morph = next
        morphScene = false
        if (!next.startDragging()) {
            morph = null
            endGroup(toNative = fromNative)
            updateVisibility()
            return MiniPlayerRuntime.refused("drag", "no geometry")
        }
        return true
    } finally { android.os.Trace.endSection() } }

    fun dragTo(progress: Float, nudge: Float, nudgeX: Float) {
        morph?.drag(progress, nudge, nudgeX)
    }

    /** The lift: the choice follows where the container is going, then the springs. */
    fun releaseDrag(toNative: Boolean, velocity: Float) {
        if (noteMorphKey != null) {
            releaseNote(toNative, velocity)
            return
        }
        val token = controller?.sessionToken
        val backIntoScene = toNative && MiniPlayerRuntime.takeRestoreScene()
        if (!backIntoScene && token != null) {
            if (toNative) MiniPlayerRuntime.chooseNative(token) else MiniPlayerRuntime.chooseMini(token)
        }
        morph?.release(toNative, velocity)
        if (backIntoScene) {
            // Back into the cover or the lyrics the moment the lift says it is going up - not
            // once the card has landed: the clock, the wallpaper and the artwork start with the
            // card's last stretch. The scene's own entry finds this morph running and takes it
            // over (beginTransition), and its artwork leaves from where the pill's is drawn.
            Main.miniPlayerEnterCover()
        }
    }

    fun takesRows(): Boolean = config.getBoolean(MiniPlayerConfig.ENABLED) && LockIslands.notes.isNotEmpty()

    fun wantsCardSwipe(): Boolean {
        if (!config.getBoolean(MiniPlayerConfig.ENABLED)) return false
        // Out as the stack's list, a pull down on the media card is the list's to fold, the
        // whole row coming home with it; taken as the card's, it was cancelled out from under
        // the stack and nothing folded.
        if (spread != null) return false
        val current = controller ?: return false
        if (!isUsable(current)) return false
        return Main.coverModeOn() || wantsNativeArtworkGesture()
    }

    fun sessionToken(): Any? = controller?.sessionToken

    fun preferMini() {
        controller?.sessionToken?.let { token ->
            MiniPlayerRuntime.chooseMini(token)
        }
    }

    /**
     * The pill, when it is showing, takes touches and the point is on it - or near it: the
     * pill is a small target, and a swipe up that began just off it went to swipe-to-unlock.
     * The margin stops short of the torch and camera buttons, which keep their own touches.
     */
    fun touchTarget(x: Float, y: Float): MiniPlayerView? {
        val view = player ?: return null
        if (editButtonUp()) return null
        val d = density()
        val xy = IntArray(2).also(view::getLocationOnScreen)
        // Across to the torch and camera, which keep their own touches.
        val lx = buttonEdge(left, inner = true) ?: (xy[0] - 8f * d)
        val rx = buttonEdge(right, inner = false) ?: (xy[0] + view.width + 8f * d)
        // From just above the pill down to the foot of the screen - a thumb starts a swipe low.
        // A row lifted off the sensor has left that room, though: unlifted it ends at the foot,
        // where the pill itself is, but lifted the region down there took swipes the row was no
        // longer under, and the islands switched from the bottom of the screen with the pill up
        // in the air (#81). Lifted, it ends 12dp under the pill's own bottom instead - the same
        // margin the lift keeps from the sensor, so the sensor's own rect begins about where the
        // region does and a finger put there is unlocking either way (#66).
        val foot = if (fodLiftPx > 0.5f) (xy[1] + view.height + dp(FOD_MARGIN_DP)).toFloat()
            else host.height.toFloat()
        val inside = x >= minOf(lx, xy[0].toFloat()) && x < maxOf(rx, xy[0] + view.width.toFloat())
            && y >= xy[1] - 36f * d && y < foot
            && fingerprintInHost()?.contains(x.toInt(), y.toInt()) != true
        // The small island first: the camera's button (its shortcut_view_right_layout above
        // all) reaches well past its disc, over the small island's right half. Asked second, a
        // tap there was the camera's - and opened it (filmed 2026-09-25). Taken here, the whole
        // gesture is the row's and the button never hears of it.
        val onSmall = smallIslandAt(x, y)
        if (!onSmall && (!inside || onButton(left, x, y) || onButton(right, x, y))) return null
        val refused = when {
            view.visibility != View.VISIBLE -> "hidden"
            !view.isAttachedToWindow -> "detached"
            morph != null -> "morph running"
            !view.acceptsTouch() -> "not interactive (canShow=${canShow()} " +
                "aod=${MiniPlayerScene.aodActive})"
            else -> null
        }
        if (refused != null) {
            // The finger was meant for the pill and went to swipe-to-unlock: say why.
            Xp.log("MCMini: down on the pill not taken: $refused")
            MiniPlayerRuntime.noteTouch("refused: $refused")
            return null
        }
        return view
    }

    /**
     * Right above the lock screen's own layer, not at the top of the window: the shade and the
     * control centre are drawn after it, and their blur covers the pill as it covers the torch
     * and camera. Added last, the pill sat above both, sharp - and was hidden for them instead.
     * A morph lifts it over the card for as long as it runs (MiniPlayerView.beginMorph).
     */
    private fun lockScreenLayerIndex(): Int {
        val at = lockScreenLayerSlot()
        return if (at >= host.childCount) at else at + 1
    }

    /** The lock screen's own layer in the host. */
    private fun lockScreenLayerSlot(): Int = layerSlot(host)

    /** Where in [parent] the child holding the buttons is: the discs go right before it. */
    private fun layerSlot(parent: ViewGroup): Int {
        var v: View = left
        while (true) {
            val up = v.parent as? View ?: return parent.childCount
            if (up === parent) {
                val at = parent.indexOfChild(v)
                return if (at < 0) parent.childCount else at
            }
            v = up
        }
    }

    /**
     * The discs' parent: keyguard_root_view, not the window root, when the buttons are in it.
     *
     * Under the buttons is not enough - the lock screen's depth cut-out (the wallpaper's subject,
     * deducted_image_view in keyguard_panel_view) is drawn in keyguard_root_view too, after
     * everything before that view in the root and before the buttons' keyguard_bottom_area. A disc
     * in the root, right before keyguard_root_view, was drawn under the subject: wherever the
     * subject reached the torch or the camera, that part of the disc was gone and the icon sat on
     * the picture (#18, measured: the disc's pixels over the subject equal the subject's). Right
     * before the buttons' own branch in keyguard_root_view it is over the subject and under the
     * icon, and otherwise stacked exactly as the buttons are, the shade's blur included.
     */
    private fun discParent(): ViewGroup {
        val root = followRoot?.get() as? ViewGroup ?: return host
        var v: View? = left
        while (v != null && v !== host) {
            if (v === root) return root
            v = v.parent as? View
        }
        return host
    }

    /** Right before the buttons' branch in [parent], with nothing but the other disc between. */
    private fun discInPlace(disc: View, parent: ViewGroup): Boolean {
        if (disc.parent !== parent) return false
        val slot = layerSlot(parent)
        val at = parent.indexOfChild(disc)
        if (at < 0 || at >= slot) return false
        for (i in at + 1 until slot) if (parent.getChildAt(i) !is ShortcutDisc) return false
        return true
    }

    /** The torch's right edge or the camera's left edge, where the pill's touch area stops. */
    private fun buttonEdge(button: View, inner: Boolean): Float? {
        if (!button.isShown || button.width <= 0) return null
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return if (inner) (xy[0] + button.width).toFloat() else xy[0].toFloat()
    }

    private fun onButton(button: View, x: Float, y: Float): Boolean {
        if (!button.isShown || button.width <= 0) return false
        val xy = IntArray(2).also(button::getLocationOnScreen)
        return x >= xy[0] && x < xy[0] + button.width && y >= xy[1] && y < xy[1] + button.height
    }

    fun showMiniPlayer() {
        if (!wantsNativeArtworkGesture()) return
        controller?.sessionToken?.let(MiniPlayerRuntime::selectMini)
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { scheduleRefresh(); return }
        if (destroyed) return
        runCatching { refreshUnsafe() }.onFailure { Xp.w("MCMini: refresh failed: $it") }
        // The lift switched on or off in the settings comes here, not to position().
        runCatching { MiniPlayerRuntime.checkStackFloor() }
            .onFailure { Xp.w("MCMini: notification bottom check failed: $it") }
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post { refreshPosted = false; refresh() }
    }

    private fun refreshUnsafe() { android.os.Trace.beginSection("MC refresh"); try {
        lottiesFollowDoze()
        if (configStale) {
            configStale = false
            val wasStacked = stackedStyle()
            config = JSONObject(MiniPlayerConfig.fromPreferences(prefs))
            if (wasStacked != stackedStyle()) squeeze.reset()
            configuredHeightDp = MiniPlayerConfig.visibleHeightDp(config.toString())
        }
        val config = this.config
        forceHeaderRefresh = true
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        if (!enabled) MiniPlayerRuntime.resetDynamicChoice("feature disabled")
        if (!enabled) {
            islandKeys = emptyList()
            player?.visibility = View.GONE
            hideSmallIsland()
            LockIslands.setActive(false)
            LockIslands.stopFolding()
            restoreHeader()
            return
        }
        // Active-session lists can be momentarily empty while a player advances its queue. Keep a
        // still-usable controller instead of flashing the vendor card during that bookkeeping gap.
        val chosen = traced("MC r.choose") { chooseController() ?: controller?.takeIf(::isUsable) }
        if (chosen?.sessionToken != controller?.sessionToken) {
            val previous = controller
            if (chosen == null && previous != null && !isUsable(previous)) {
                MiniPlayerRuntime.endSession(previous.sessionToken, "playback inactive")
            }
            runCatching { previous?.let { Gen.unsessionCallback(it, mediaListener) } }
            controller = chosen
            runCatching { chosen?.let { Gen.sessionCallback(it, mediaListener, handler) } }
            followLive(chosen)
            musicSince = System.currentTimeMillis()
            cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            lastTrack = ""
        }
        // The row is out as the stack's list: its views are the spread's till it folds back,
        // and it is put together again then (endSpread).
        if (spread != null) return
        val musicMoving = group != null || musicComingDown || morph != null && noteMorphKey == null ||
            noteMorphKey == MUSIC_ISLAND || exchange?.has(MUSIC_ISLAND) == true
        // No music island without its card: a session outlives a dismissed card, and the row
        // kept an island for it that no card would ever be there to back.
        val music = traced("MC r.music") {
            controller?.takeIf(::isUsable)?.takeIf { Main.miniPlayerMediaCardPresent() || musicMoving }
        }
        val notes = traced("MC r.notes") { LockIslands.notes }
        // One island out as its card, the rest in the row (the super island's expanded state):
        // the music out as the media card leaves the row to the notifications. Alone, it keeps
        // the row as it always has - the card or the pill, one or the other. Moving between
        // the two it keeps its place until it lands.
        // The cover is the music's card as much as the media card is.
        val carded = music != null &&
            (MiniPlayerRuntime.nativeRequested(music.sessionToken) || Main.coverModeOn())
        val others = notes.isNotEmpty() || returning.isNotEmpty() || exchange != null ||
            noteMorphKey.let { it != null && it != MUSIC_ISLAND }
        val musicInRow = music != null && (!carded || !others || musicMoving)
        // The row in the super island's order: the focus islands that take the big place from
        // the music (高德's navigation, islandProperty 1 against the music's 2) ahead of it, then
        // the music, then the rest in LockIslands' order. The music used to be first whatever
        // else there was, and the navigation had to be swiped to every time (the user, 2026-10-24).
        val (ahead, behind) = notes.partition { isFocusIsland(it.key) && takesPlace(MUSIC_ISLAND, it.key, big = true) }
        islandKeys = ahead.map { it.key } + (if (musicInRow) listOf(MUSIC_ISLAND) else emptyList()) +
            behind.map { it.key }
        // Two can be on their way back at once: a tap mid-switch brings one down as another lands.
        val now = android.os.SystemClock.uptimeMillis()
        returning.entries.removeAll { (key, since) ->
            notes.any { it.key == key } || now - since > RETURN_WAIT_MS || !Main.keyguardLocked()
        }
        for (key in returning.keys) islandKeys = keptInPlace(islandKeys, key)
        // A notification on its way out to its row, or in from it, stays in the pill till it lands.
        noteMorphKey?.let { key -> islandKeys = keptInPlace(islandKeys, key) }
        // Every island of a switch holds its place in the row till it is over.
        exchange?.let { x -> for (key in x.keys()) islandKeys = keptInPlace(islandKeys, key) }
        // The time unlocked does not count against an island: the row comes back as it was left.
        val locked = Main.keyguardLocked()
        if (locked && !knownLocked) for (k in islandMissing.keys) islandMissing[k] = now
        knownLocked = locked
        // The island in the pill missing from one run - 高德 re-posts its island every second, and
        // a row being re-inflated is left out of that run - keeps the pill for PICK_GRACE_MS.
        // It fell to the row's first island at once, and the navigation picked went back to the
        // music a moment later (the user, 2026-10-24).
        val graced = selectedIsland?.takeIf { k ->
            locked && k != MUSIC_ISLAND && k !in islandKeys && k in islandKnown &&
                !LockIslands.isReleased(k) && LockIslands.noteFor(k) != null &&
                now - (islandMissing[k] ?: now) < PICK_GRACE_MS
        }
        if (graced != null) {
            islandKeys = keptInPlace(islandKeys, graced)
            handler.removeCallbacks(graceCheck)
            handler.postDelayed(graceCheck, PICK_GRACE_MS + 50L)
        }
        // Islands out as their cards keep their places too, for when they come back.
        var order = islandKeys
        // A new island in the row comes up beside the first, swiped alone or not.
        if (aloneFirst != null && islandKeys.any { it !in islandOrder }) aloneFirst = null
        for (k in islandOrder) {
            if (k !in order && (LockIslands.isReleased(k) || k == MUSIC_ISLAND && carded)) {
                order = keptInPlace(order, k)
            }
        }
        islandOrder = order
        // Islands new to this lock screen's row, before they are counted as known below.
        val fresh = if (locked) order.filter { it !in islandKnown } else emptyList()
        if (locked) noteKnown(order - listOfNotNull(graced), now)
        noteMorphKey?.let { key ->
            // The pill itself morphing: it keeps what it is showing. Under a flight it goes on
            // being the row's (the island beside the flight can take it).
            if (flight == null) {
                updateSmallIsland(music, notes)
                applyArtBridge()
                updateVisibility()
                schedulePosition()
                return
            }
        }
        if (islandKeys.isEmpty() || rowGoing(music)) {
            // Not put away here: updateVisibility does it, and on a lock screen that stays it
            // goes back into its circle first. Set GONE here, the torch's island switched off
            // with a double tap was gone in one frame, the way back never started.
            hideSmallIsland()
            restoreHeader()
            updateVisibility()
            return
        }
        val lost = selectedIsland != null && selectedIsland !in islandKeys
        var selected = selectedIsland?.takeIf { it in islandKeys } ?: islandKeys.first()
        // A focus island new to the row takes the big place from the one there when it outranks
        // it, as the super island's handleReplacedState gives it: the navigation started comes up
        // in the pill and the music goes into the small place. An ordinary notification never
        // does - each message would take the pill. Busy with another motion, it is tried again
        // on the next refresh.
        val taker = if (lost || selectedIsland == null) null else fresh.firstOrNull {
            it != selected && it in islandKeys && isFocusIsland(it) && takesPlace(selected, it, big = true)
        }
        if (taker != null) {
            val busy = swap != null || morph != null || exchange != null || flight != null ||
                group != null || rowWaitKey != null || islandDragging
            if (busy) islandKnown.remove(taker)
            else {
                val oldBig = selected
                val oldSmall = smallKey
                selected = taker
                preferredSmall = null
                aloneFirst = null
                MiniPlayerRuntime.noteTouch("island ${taker.takeLast(6)} takes the pill from ${oldBig.takeLast(6)}")
                if (player?.visibility == View.VISIBLE) handler.post {
                    if (swap == null && morph == null && selectedIsland == taker) {
                        startSwap(oldBig, oldSmall, intoSmall = smallKey == oldBig)
                    }
                }
            }
        }
        selectedIsland = selected
        // The notification in the pill went (dismissed, answered elsewhere): the next island
        // grows into the pill from its end, as one coming out of the stack does.
        // A pill not on screen - the music hidden by its cover, say - has nothing to switch from:
        // a switch here took the next island's coming-up from its first frame (`op mini`: up done
        // frames=1), and it grows in as a new row instead.
        if (lost && player?.visibility == View.VISIBLE) {
            val oldSmall = smallKey
            handler.post { if (swap == null && morph == null) startSwap(null, oldSmall) }
        }
        val view = player ?: MiniPlayerView(context).also {
            player = it
            host.addView(it, lockScreenLayerIndex(), ViewGroup.LayoutParams(1, 1))
            Gen.ownView(host, it)
        }
        if (music != null) trackMusic(music)
        // A notification let out and on its way home to the pill is not among the notes yet - nor
        // for a run of the pipeline after it has landed (returning). Looked up in the notes
        // alone, the pill took the music for those frames: the music flashed in the pill right
        // after the notification had landed there (2026-09-25).
        val note = notes.firstOrNull { it.key == selected }
            ?: selected.takeIf { it != MUSIC_ISLAND }?.let(LockIslands::noteFor)
        // The music only for the music's seat: a notification whose row has not come back yet
        // took the music's picture for those frames, under the flight landing on the pill. It
        // binds nothing now, and the pill stays out of sight until it has (pillUnbound).
        // From TakeKazeX's PR #15.
        val showsMusic = selected == MUSIC_ISLAND && music != null
        traced("MC r.bind") {
            if (showsMusic) bindMusic(view, music!!, config)
            else if (note != null) bindNote(view, note, config)
        }
        pillShowsMusic = showsMusic
        // The music's own seat with no session for a moment (a stop between two tracks) keeps
        // what it showed, as it always has.
        pillUnbound = selected != MUSIC_ISLAND && note == null
        // After the small island is chosen: whether the music is it decides its picture's bridge.
        updateSmallIsland(music, notes)
        traced("MC r.artBridge") { applyArtBridge() }
        applyInk()
        updateVisibility()
        schedulePosition()
        prewarmSpares()
    } finally { android.os.Trace.endSection() } }

    /**
     * The colour the system gives the words on its own card, for the material it is in now: the
     * media card's title, or a lock screen notification's when there is no card. The pill and
     * the discs wear the card's material, so its words are the ones that can be read on them -
     * white on the glass and on the dark frost, near black on the frost in light mode. Opaque:
     * the pill's own alphas say how strong each line is.
     */
    private fun ink(): Int {
        val card = Main.miniPairTitle() as? TextView
        val color = card?.takeIf { it.isAttachedToWindow }?.currentTextColor ?: rowInk() ?: materialInk()
        return color or 0xFF000000.toInt()
    }

    private var materialInkAt = 0L
    private var materialInkCached = android.graphics.Color.WHITE

    /**
     * No card and no notification to read it off: the material's own answer. The frost in light
     * mode writes near black (the card's title, #E5000000); the frost in dark mode and the glass
     * write white. Defaulting to white, the torch and camera stayed white on the light frost's
     * discs with nothing else on the lock screen (the user, 2026-10-14). Read every frame by
     * updateDiscs, so held for a moment rather than asked each time.
     */
    private fun materialInk(): Int {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - materialInkAt < MATERIAL_INK_MS) return materialInkCached
        materialInkAt = now
        val ctx = Main.sAppCtx ?: return materialInkCached
        val frost = runCatching {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "material_style", 1) == 0
        }.getOrDefault(false)
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        materialInkCached = if (frost && !night) 0xE5000000.toInt() else android.graphics.Color.WHITE
        return materialInkCached
    }

    private fun rowInk(): Int? {
        fun find(v: View): TextView? {
            if (v is TextView && v.isShown &&
                runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() == "title") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return notificationStack()?.let(::find)?.currentTextColor
    }

    /** What applyInk last tinted the buttons with; null while they are the OEM's own. */
    private var buttonInk: Int? = null

    private fun applyInk() {
        val color = ink()
        player?.setInk(color)
        smallIsland?.setInk(color)
        ghostPill?.setInk(color)
        ghostDisc?.setInk(color)
        flight?.setInk(color)
        for (v in spareViews) v.setInk(color)
        // The torch and the camera are white glyphs; on discs of the light frost they vanished.
        // Tinted only while the discs are under them, and given back as they were after.
        val want = color.takeIf { discsWanted && discs.any { it?.visibility == View.VISIBLE } && it != android.graphics.Color.WHITE }
        if (want == buttonInk) return
        buttonInk = want
        for (side in 0..1) tintButton(button(side), want)
    }

    private fun tintButton(v: View, color: Int?) {
        val filter = color?.let { android.graphics.PorterDuffColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        if (v is ImageView) { v.colorFilter = filter; return }
        if (filter == null) v.setLayerType(View.LAYER_TYPE_NONE, null)
        else v.setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply { colorFilter = filter })
    }

    /** The session and track the music island is showing; a new one starts its artwork afresh. */
    private fun trackMusic(current: MediaController) {
        if (MiniPlayerRuntime.observeSession(current.sessionToken, current.packageName)) {
            cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            lastTrack = ""
        }
        val metadata = metadataOf(current)
        // The song, not the title: Salt, 汽水 and QQ sing into TITLE, and every line threw the
        // artwork away as a track change (#47, #56).
        val track = TrackName.songKey(current.packageName, metadata).ifEmpty {
            current.packageName + "|" + metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        }
        if (track != lastTrack) {
            if (lastTrack.isNotEmpty()) {
                Xp.log("MCMini: track changed; preserving dynamic choice=" +
                    if (MiniPlayerRuntime.nativeRequested(current.sessionToken)) "native" else "mini")
            }
            lastTrack = track
            cachedCover = null
        }
    }

    /** The music island's artwork, as far as it has been fetched and scaled. */
    private fun musicCover(metadata: MediaMetadata?): Bitmap? {
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: cachedCover?.takeUnless { it.isRecycled }
            ?: (if (!nativeSuppressionRequested) AlbumArt.cardThumbnail() else null)
        if (cover != null && !cover.isRecycled) cachedCover = cover
        return cover
    }

    private fun bindMusic(view: MiniPlayerView, current: MediaController, config: JSONObject) {
        view.setArtworkBare(false)
        view.setToggleFace(null)
        view.setSecondFace(null)
        view.skippable = true
        showTimer(view, null)
        showFocusAnim(view, null, null)
        val metadata = metadataOf(current)
        val shown = traced("MC b.thumb") { thumbnailFor(musicCover(metadata), view) }
        view.setToggleShown(true)
        // A title that is the line being sung is cut, never scrolled (MiniPlayerView.titleScrolls).
        view.titleScrolls = !TrackName.singsIntoTitle(TrackName.songKey(current))
        view.bind(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(context, "hmcNowPlaying", "正在播放") },
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            shown,
            stateOf(current)?.state == PlaybackState.STATE_PLAYING,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, loader, notificationMaterial()) },
            ::togglePlayback,
            { skip(next = false) },
            { skip(next = true) },
            {
                // Up undoes down: out of the cover or lyrics by a swipe, back into it by one.
                if (MiniPlayerRuntime.takeRestoreScene()) Main.miniPlayerEnterCover()
                else MiniPlayerRuntime.selectNative(current.sessionToken)
            },
            { openCover() },
        )
    }

    /**
     * A notification in the pill: its picture, title and newest line, no play button. A tap
     * puts it back in the stack as the OEM's own row for now; the morph into that row is to
     * come.
     */
    private fun bindNote(view: MiniPlayerView, note: LockIslands.Note, config: JSONObject) {
        // A focus notification shown in full is its template: its picture bare, as its row has
        // it, and its main button where the music's play button is.
        val template = note.focus && !note.redacted
        // Every notification's picture at the small island's size, cut to its circle or whole
        // as LockIslands.roundIcon left it (2026-09-26): a plain one was the cover's rounded
        // square, bigger, on a grey plate.
        view.setArtworkBare(true)
        view.skippable = false
        view.titleScrolls = true
        // Its buttons as its row has them: the last - its main one - in the play button's place,
        // the one before it beside it while the pill is the big island (setSecondShown).
        val buttons = if (template) note.buttons else emptyList()
        val primary = buttons.lastOrNull()
        val second = buttons.getOrNull(buttons.size - 2)
        view.setToggleShown(primary != null)
        view.setToggleFace(primary?.let { buttonFace(it, note.timer) })
        view.setSecondFace(second?.let { buttonFace(it, note.timer) })
        // The template's own text, spans and all: a focus protocol writes colour into it and the
        // row shows it coloured, so flattening it here showed the pill the markup instead
        // (the user, 2026-09-28).
        view.bind(
            note.timer?.displayText() ?: note.title.takeUnless { it.isBlank() } ?: appLabel(note.pkg),
            note.text,
            noteBitmap(note),
            false,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, loader, notificationMaterial()) },
            {},
            {},
            {},
            {},
            { expandNote(note.key) },
        )
        showTimer(view, note.timer)
        showFocusAnim(view, note.anim, note.live)
    }

    /**
     * Each pill's own player of a focus template's Lottie, and which picture it plays. The row's
     * Lottie is the plugin's own copy of the library, obfuscated, its composition out of reach:
     * the same file is loaded again with SystemUI's copy - the plugin names it
     * (LottieResUtils.getLottieRes) and its resources hold it - into a LottieAnimationView of
     * SystemUI's that is never attached, whose drawable the pill's artwork shows and runs.
     */
    private val focusLotties = java.util.WeakHashMap<Any, Pair<String, android.widget.ImageView>>()

    private fun showFocusAnim(view: MiniPlayerView, anim: LockIslands.Anim?, live: LockIslands.Live?) {
        val d = focusLottie(view, anim)
        if (d == null) view.clearArtworkDrawable() else view.showArtworkDrawable(d)
        view.showArtworkView(liveArt(view, live))
    }

    /**
     * Each place's own live picture ([LockIslands.Live]) and which one it is: a view has one
     * parent, so the pill, the small island and a flight each make their own.
     */
    private val liveArts = java.util.WeakHashMap<Any, Pair<String, View>>()
    private val liveFailed = HashSet<String>()

    /**
     * Live pictures no place shows any more, by id, for the next place that asks for the same.
     * Each one made is the plugin's own view, and the torch's inflates three bitmaps on the main
     * thread: made afresh for every place that took the torch - a switch's stand-ins, a flight,
     * a pill that had been the music in between - it was 7-18ms of the first frame of every
     * switch and every pull (perfetto 2026-09-29, `decodeBitmap` under FlashLightView inflate).
     */
    private val liveSpares = HashMap<String, ArrayDeque<View>>()

    private fun spareLive(held: Pair<String, View>) {
        // Not a clip: it is set playing once, when it is made, and put back up after its surface
        // went it is not known to play again.
        if (held.first.startsWith(LockIslands.Live.VIDEO + "#")) return
        val spares = liveSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /** A spare of [id] that has left its last place: one still in it would be taken from under it. */
    private fun takeSpareLive(id: String): View? {
        val spares = liveSpares[id] ?: return null
        val it = spares.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.parent == null) {
                it.remove()
                return v
            }
        }
        return null
    }

    /** [owner]'s view of [live], made on first asking; null for none, or one that would not make. */
    private fun liveArt(owner: Any, live: LockIslands.Live?): View? {
        val held = liveArts[owner]
        if (live == null) {
            liveArts.remove(owner)?.let(::spareLive)
            return null
        }
        if (held != null) {
            if (held.first == live.id) return held.second
            liveArts.remove(owner)
            spareLive(held)
        }
        if (live.id in liveFailed) return null
        takeSpareLive(live.id)?.let { spare ->
            liveArts[owner] = live.id to spare
            return spare
        }
        val view = runCatching { makeLiveArt(live) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            liveFailed += live.id
            lottieNote("live ${live.id} failed: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}", null)
        }.getOrNull() ?: return null
        liveArts[owner] = live.id to view
        lottieNote("live ${live.id} made", null)
        return view
    }

    /**
     * The plugin's own view for [live], set up as the row sets its own
     * (ModuleNewImageTextViewHolder.showEffects, IslandIconViewHolder.setMP4Icon), in a host that
     * draws it at the size the row's layout gives it.
     */
    private fun makeLiveArt(live: LockIslands.Live): View? {
        val plugin = LockIslands.pluginOf(live.row.get()) ?: return null
        val ctx = plugin.ctx
        fun dimen(name: String): Int {
            val id = ctx.resources.getIdentifier(name, "dimen", "miui.systemui.plugin")
            return if (id == 0) 0 else ctx.resources.getDimensionPixelSize(id)
        }
        return when (live.kind) {
            LockIslands.Live.FLASH -> {
                val v = plugin.loader.loadClass("com.mi.widget.view.FlashLightView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                // Its row's layout lets its glow - taller than its square - out (clipChildren
                // false); made in code, a FrameLayout clips it.
                (v as? ViewGroup)?.clipChildren = false
                (Xp.callMethod(v, "getFlashLightOffset") as? FloatArray)?.let { o ->
                    o[0] = 0f; o[1] = 0.14f; o[2] = -50f; o[3] = 0f
                }
                LiveArtHost(context, v, dimen("focus_notify_profile_container_size"))
            }
            LockIslands.Live.VIDEO -> {
                val v = plugin.loader.loadClass("miui.systemui.widget.TextureVideoView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                val uri = plugin.call("miui.systemui.util.VideoResUtils", "getVideoRes", live.src, ctx) as String
                Xp.callMethod(v, "setOnPreparedListener", android.media.MediaPlayer.OnPreparedListener { mp ->
                    // Silent on the lock screen whatever the clip carries.
                    runCatching { mp.setVolume(0f, 0f) }
                    mp.isLooping = live.loop
                    Xp.callMethod(v, "start")
                })
                Xp.callMethod(v, "setOnErrorListener", android.media.MediaPlayer.OnErrorListener { _, what, extra ->
                    lottieNote("live ${live.id} error $what/$extra", null)
                    runCatching { Xp.callMethod(v, "stopPlayback") }
                    true
                })
                Xp.callMethod(v, "setVideoURI", android.net.Uri.parse(uri))
                LiveArtHost(context, v, 0)
            }
            else -> null
        }
    }

    /**
     * [owner]'s player of [anim] - a pill, or the small island - its drawable to show, playing
     * or held as the template says; null, and [owner]'s player stopped, for none.
     */
    private fun focusLottie(owner: Any, anim: LockIslands.Anim?): android.graphics.drawable.Drawable? {
        if (anim == null) {
            focusLotties.remove(owner)?.let(::spareLottie)
            return null
        }
        val id = "${anim.src}#${anim.number}"
        var held = focusLotties[owner]
        if (held?.first != id) {
            held?.let { focusLotties.remove(owner); spareLottie(it) }
            // Another place's, let go: as liveArt's spares, not a player made for every place.
            val lottie = lottieSpares[id]?.removeFirstOrNull() ?: loadFocusLottie(anim)
                ?: return focusLottie(owner, null)
            held = id to lottie
            focusLotties[owner] = held
        }
        val lottie = held.second
        lottieAutoplay[lottie] = anim.autoplay
        runCatching {
            Xp.callMethod(lottie, "setRepeatCount", anim.repeat)
            Xp.callMethod(lottie, "setRepeatMode", 1)
            if (anim.autoplay && !MiniPlayerScene.aodActive) {
                // Held unseen: its place coming back plays it (lottiesFollowSight), not a refresh.
                if (lottie !in lottiesUnseen && Xp.callMethod(lottie, "isAnimating") != true) {
                    lottieNote("asked to resume vis=${lottie.drawable?.isVisible}", lottie)
                    Xp.callMethod(lottie, "resumeAnimation")
                }
            } else Xp.callMethod(lottie, "pauseAnimation")
        }
        return lottie.drawable?.let { d -> lottieWatches.getOrPut(d) { LottieWatch(d, lottie) } }
    }

    /** Which players their template asks to play, for the wake to play them again. */
    private val lottieAutoplay = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * The doze holds every place's Lottie on its frame; the wake plays again those that play.
     * The window stays visible through the AOD, so nothing pauses them on its own, and one that
     * loops redraws the whole NotificationShade every frame: the recorder's voiceWave kept the
     * AOD at ~63fps, RenderThread 57% and main 37% of a core (2026-09-30).
     */
    private fun lottiesFollowDoze() {
        val doze = MiniPlayerScene.aodActive
        for (held in focusLotties.values) runCatching {
            val lottie = held.second
            if (doze) Xp.callMethod(lottie, "pauseAnimation")
            else if (lottieAutoplay[lottie] == true && lottie !in lottiesUnseen &&
                Xp.callMethod(lottie, "isAnimating") != true)
                Xp.callMethod(lottie, "resumeAnimation")
        }
    }

    /** Players held because their place could not be seen, for the place coming back to play. */
    private val lottiesUnseen = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * A lit lock screen holds every place's Lottie that nobody can see; the place coming back
     * plays it again. A Lottie drawable pauses itself when its ImageView goes GONE or its window
     * hides (setVisible), but not when a place is faded out - alpha or transitionAlpha 0 on it
     * or an ancestor, which is how most places here are put away - and one that loops redraws
     * the whole NotificationShade every frame, seen or not: a looping one (the clock's island)
     * kept a still lock screen at ~95fps, main and RenderThread 68% of a core (2026-10-01). Asked
     * before every frame: an unseen player's own tick is what schedules the frame that holds it,
     * and the frame a place comes back in is the one that plays it again.
     */
    private fun lottiesFollowSight() {
        if (focusLotties.isEmpty() || MiniPlayerScene.aodActive) return
        for ((owner, held) in focusLotties) runCatching {
            val lottie = held.second
            if (lottieAutoplay[lottie] != true) return@runCatching
            val seen = (owner as? View)?.let(::onScreen) ?: true
            val playing = Xp.callMethod(lottie, "isAnimating") == true
            if (playing && !seen) {
                Xp.callMethod(lottie, "pauseAnimation")
                lottiesUnseen[lottie] = true
                lottieNote("unseen, held", lottie)
            } else if (seen && lottiesUnseen.remove(lottie) != null && !playing) {
                Xp.callMethod(lottie, "resumeAnimation")
                lottieNote("seen, played", lottie)
            }
        }
    }

    /** Drawn and not faded out: attached to a showing window, shown, and no alpha 0 up the chain. */
    private fun onScreen(v: View): Boolean {
        if (!v.isAttachedToWindow || v.windowVisibility != View.VISIBLE || !v.isShown) return false
        var p: View? = v
        while (p != null) {
            if (p.alpha <= 0f || p.transitionAlpha <= 0f) return false
            p = p.parent as? View
        }
        return true
    }

    /** Players no place shows any more, by picture, for the next place that plays the same. */
    private val lottieSpares = HashMap<String, ArrayDeque<android.widget.ImageView>>()

    private fun spareLottie(held: Pair<String, android.widget.ImageView>) {
        runCatching { Xp.callMethod(held.second, "pauseAnimation") }
        lottiesUnseen.remove(held.second)
        val spares = lottieSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /**
     * Compositions by the plugin's raw resource, shared by every pill that plays one; the
     * pictures that would not load, not tried again on every refresh (a failure was retried
     * twenty times in a minute, 2026-09-25).
     */
    private val focusCompositions = HashMap<Int, Any>()
    private val focusLottieFailed = HashSet<String>()

    private fun loadFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        if (anim.src in focusLottieFailed) return null
        return runCatching { makeFocusLottie(anim) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            focusLottieFailed += anim.src
            focusLottieWhy = "failed ${anim.src}: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}"
            lottieNote(focusLottieWhy, null)
        }.getOrNull()
    }

    /** Why the last try gave nothing, said once until it changes. */
    private var focusLottieWhy = ""

    private fun lottieMiss(why: String): android.widget.ImageView? {
        if (why != focusLottieWhy) {
            focusLottieWhy = why
            lottieNote("none: $why", null)
        }
        return null
    }

    /**
     * The plugin as a package of its own, code and resources: its raw Lottie files are its
     * resources, not SystemUI's, and a view of its in the row may carry SystemUI's.
     */
    private val pluginContext: android.content.Context? by lazy {
        runCatching {
            context.createPackageContext("miui.systemui.plugin",
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { MiniPlayerRuntime.noteTouch("focus lottie: no plugin package: $it") }.getOrNull()
    }

    /** The last load's own exception, when it gave no composition. */
    private var lottieLoadError: Any? = null

    /**
     * The plugin's raw Lottie [res] as SystemUI's copy of the library reads it: its
     * fromRawResSync(Context, String cacheKey, int), arguments reordered by R8 and matched by
     * type; its LottieResult's getters gone, its public fields read. One per file, shared.
     */
    private fun compositionFor(res: Int, plugin: android.content.Context): Any? {
        focusCompositions[res]?.let { return it }
        val systemui = host.javaClass.classLoader ?: return null
        val factory = systemui.loadClass("com.airbnb.lottie.LottieCompositionFactory")
        val load = factory.methods.first { m ->
            m.name == "fromRawResSync" && m.parameterTypes.any { it == Int::class.javaPrimitiveType } &&
                m.parameterTypes.any { it == android.content.Context::class.java }
        }
        val args = load.parameterTypes.map { t ->
            when (t) {
                android.content.Context::class.java -> plugin
                Int::class.javaPrimitiveType -> res
                String::class.java -> "musiccover_focus_$res"
                else -> null
            }
        }.toTypedArray()
        val result = load.invoke(null, *args)
        lottieLoadError = runCatching { Xp.getObjectField(result, "exception") }.getOrNull()
        return Xp.getObjectField(result, "value")?.also { focusCompositions[res] = it }
    }

    /** A LottieAnimationView of SystemUI's, never attached, holding [composition]. */
    private fun newLottie(composition: Any, plugin: android.content.Context, systemui: ClassLoader): android.widget.ImageView {
        val view = systemui.loadClass("com.airbnb.lottie.LottieAnimationView")
            .getConstructor(android.content.Context::class.java).newInstance(plugin) as android.widget.ImageView
        Xp.callMethod(view, "setComposition", composition)
        return view
    }

    private fun lottieView(res: Int, plugin: android.content.Context): android.widget.ImageView? {
        val composition = compositionFor(res, plugin) ?: return null
        return newLottie(composition, plugin, host.javaClass.classLoader ?: return null)
    }

    private fun makeFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        // The plugin as the row has it - its view's context and classes, which loaded the file
        // (2026-09-25) - else the plugin's package; its package context alone never did.
        val source = anim.row.get()?.let { LockIslands.focusIconView(it) }
        val plugin = source?.context ?: pluginContext ?: return lottieMiss("no plugin context")
        val loader = source?.javaClass?.classLoader ?: plugin.classLoader
        val utils = loader.loadClass("miui.systemui.util.LottieResUtils")
        val instance = runCatching { utils.getField("INSTANCE").get(null) }.getOrNull()
        val res = utils.methods.first { it.name == "getLottieRes" && it.parameterTypes.size == 2 }
            .invoke(instance, anim.src, anim.number) as Int
        if (res <= 0) return lottieMiss("no res for ${anim.src}")
        val systemui = host.javaClass.classLoader ?: return lottieMiss("no systemui loader")
        val composition = compositionFor(res, plugin)
            ?: return lottieMiss("load ${anim.src}: $lottieLoadError")
        val view = newLottie(composition, plugin, systemui)
        runCatching { utils.getField("IMAGE_ASSETS_FOLDER").get(null) as? String }.getOrNull()
            ?.let { Xp.callMethod(view, "setImageAssetsFolder", it) }
        runCatching {
            Xp.callMethod(view, "addAnimatorListener", object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) = lottieNote("start", view)
                override fun onAnimationEnd(a: android.animation.Animator) = lottieNote("end", view)
                override fun onAnimationCancel(a: android.animation.Animator) = lottieNote("cancel", view)
                override fun onAnimationPause(a: android.animation.Animator) = lottieNote("pause", view)
                override fun onAnimationResume(a: android.animation.Animator) = lottieNote("resume", view)
            })
        }
        focusLottieWhy = ""
        lottieNote("loaded ${anim.src} res=$res", view)
        return view
    }

    private val noteBitmaps = HashMap<String, Triple<Long, android.graphics.drawable.Drawable?, Bitmap?>>()

    /** For `op mini`: what the focus Lotties did, and who hid them - kept apart from the touches. */
    private val lottieLog = ArrayDeque<String>()

    private fun lottieNote(what: String, lottie: android.widget.ImageView?) {
        val p = lottie?.let { runCatching { Xp.callMethod(it, "getProgress") }.getOrNull() }
        synchronized(lottieLog) {
            lottieLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what p=$p")
            while (lottieLog.size > 24) lottieLog.removeFirst()
        }
    }

    /**
     * The Lottie drawable as the pill's artwork shows it: passed straight through, but its
     * visibility changes - the only thing that pauses a Lottie drawable on its own - are noted.
     */
    private inner class LottieWatch(inner: android.graphics.drawable.Drawable, val lottie: android.widget.ImageView) :
        android.graphics.drawable.DrawableWrapper(inner) {
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            if (visible != isVisible) lottieNote("visible=$visible", lottie)
            return super.setVisible(visible, restart)
        }
    }

    private val lottieWatches = java.util.WeakHashMap<android.graphics.drawable.Drawable, LottieWatch>()

    /**
     * A focus button as the pill draws it, by the plugin's own rule (ModuleButtonViewHolder
     * .setActionNormalData / setActionTextData), the same for every app: its picture the
     * plugin's own for its name when it has one (ACTIONS_RESOURCE_MAP), or its Lottie held at
     * its first frame (LOTTIE_RES_MAP_NEXT, updateAndPauseAnimation), else the app's picture;
     * its plate the plugin's focus_button_background_n in its colour, a pressed one under it.
     */
    private fun buttonFace(b: LockIslands.Button, timer: LockIslands.Timer?) = ActionFace(
        key = "${b.type}|${b.iconName}|${b.bg}|${b.press}|${b.label}|${b.titleColor}|" +
            "${b.progress?.let { "${it.value}/${it.auto}/${it.ccw}/${it.color}/${it.colorEnd}" }}",
        icon = {
            when (b.type) {
                // setActionTextData: its title, in its title colour, on its plate.
                2 -> TextFaceDrawable(b.label?.toString().orEmpty(), b.titleColor ?: android.graphics.Color.WHITE,
                    13f * context.resources.displayMetrics.scaledDensity)
                // setActionProgressData: its picture inside its ring.
                1 -> progressFace(b, timer)
                else -> buttonIcon(b)
            }
        },
        // A plate only under words: the plugin's pictures carry their own round shell, and
        // only setActionTextData gives its button a background - one under a picture drew a
        // second shell round it (2026-09-25).
        background = { if (b.type == 2) buttonPlate(b) else null },
        label = b.label,
        onClick = { pressButton(b) },
        wide = b.type == 2,
    )

    /**
     * A progress button: its picture inset in its ring - the ring at its value, or at its
     * timer's share of the timer's total for an auto one (NotificationTimeKeeper.getProgresses),
     * redrawn on each tick of the timer.
     */
    private fun progressFace(b: LockIslands.Button, timer: LockIslands.Timer?): android.graphics.drawable.Drawable {
        val spec = b.progress
        val auto = spec?.auto == true && timer != null && timer.totalMs > 0L
        val ring = ProgressRingDrawable(
            stroke = dp(2.5f).toFloat(),
            color = spec?.color ?: android.graphics.Color.WHITE,
            colorEnd = spec?.colorEnd,
            ccw = spec?.ccw == true,
            progress = if (auto) ({ timer.progress() })
                else ({ (spec?.value ?: 0).toFloat() }),
        )
        if (auto) autoRings[ring] = true
        val icon = buttonIcon(b)
        val layers = listOfNotNull(icon?.let { android.graphics.drawable.InsetDrawable(it, 0.24f) }, ring)
        return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    /** The plugin's context and classes as the row has them, else its package's. */
    private fun pluginOf(row: View?): Pair<android.content.Context, ClassLoader>? {
        LockIslands.pluginOf(row)?.let { return it.ctx to it.loader }
        val ctx = pluginContext ?: return null
        return ctx to ctx.classLoader
    }

    /** The plugin's name-to-picture maps for its buttons: its stills and its Lotties. */
    private var buttonMapsCache: Pair<Map<*, *>, Map<*, *>>? = null

    private fun buttonMaps(loader: ClassLoader): Pair<Map<*, *>, Map<*, *>>? = buttonMapsCache ?: runCatching {
        val holder = loader.loadClass("miui.systemui.notification.focus.moduleV3.ModuleButtonViewHolder")
        fun map(name: String) = holder.getDeclaredField(name).apply { isAccessible = true }.get(null) as Map<*, *>
        (map("ACTIONS_RESOURCE_MAP") to map("LOTTIE_RES_MAP_NEXT")).also { buttonMapsCache = it }
    }.onFailure { lottieNote("button maps: $it", null) }.getOrNull()

    private fun buttonIcon(b: LockIslands.Button): android.graphics.drawable.Drawable? {
        val name = b.iconName
        val plugin = pluginOf(b.row.get())
        if (name != null && plugin != null) {
            val maps = buttonMaps(plugin.second)
            // The Lottie first, as the plugin does (shouldAnimLottie, in setActionNormalData and
            // setActionProgressData alike): a name in both maps is drawn as its Lottie's first
            // frame. The still first drew the countdown's action_restart_timer as
            // restart_timer_v3 where the row has pause_island_blue (2026-09-30).
            (maps?.second?.get(name) as? Int)?.takeIf { it > 0 }?.let { res ->
                runCatching { lottieView(res, plugin.first) }.getOrNull()?.let { lottie ->
                    runCatching { Xp.callMethod(lottie, "setProgress", 0f) }
                    lottie.drawable?.let { return it }
                }
            }
            (maps?.first?.get(name) as? Int)?.takeIf { it != 0 }?.let { res ->
                runCatching { plugin.first.getDrawable(res) }.getOrNull()?.let { return it }
            }
        }
        return runCatching { (b.picture ?: b.action?.getIcon())?.loadDrawable(context) }.getOrNull()
    }

    private fun buttonPlate(b: LockIslands.Button): android.graphics.drawable.Drawable {
        val ctx = pluginOf(b.row.get())?.first
        fun plate(name: String, fallback: Int): android.graphics.drawable.Drawable {
            val id = ctx?.resources?.getIdentifier(name, "drawable", "miui.systemui.plugin") ?: 0
            return (if (id != 0) runCatching { ctx!!.getDrawable(id)?.mutate() }.getOrNull() else null)
                ?: android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(fallback)
                }
        }
        val normal = plate("focus_button_background_n", 0x33FFFFFF)
        val pressed = plate("focus_button_background_p", 0x55FFFFFF)
        b.bg?.let { (normal as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        // Pressed: its own colour, else its plate's a little lighter, as the dark template's.
        (b.press ?: b.bg?.let { androidx.core.graphics.ColorUtils.blendARGB(it, android.graphics.Color.WHITE, 0.15f) })
            ?.let { (pressed as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(IntArray(0), normal)
        }
    }

    /**
     * A tap: the row's own button is pressed - the plugin then does what it does for every app,
     * the keyguard asked to go for an activity, the state flipped, the notification collapsed
     * when asked. With no row to press, its intent is sent as the plugin would build it.
     */
    private fun pressButton(b: LockIslands.Button) {
        MiniPlayerRuntime.noteTouch("focus button ${b.index} '${b.label}' ${b.iconName}")
        val target = b.row.get()?.let { rowButton(it, b.index, b.type) }
        if (target != null && target.hasOnClickListeners()) {
            target.performClick()
            return
        }
        runCatching {
            val intent = b.action?.actionIntent ?: b.intentUri?.let { uri ->
                val i = android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
                val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                when (b.intentType) {
                    2 -> android.app.PendingIntent.getBroadcast(context, b.postTime.toInt(), i, flags)
                    3 -> android.app.PendingIntent.getService(context, b.postTime.toInt(), i, flags)
                    else -> android.app.PendingIntent.getActivity(context, b.postTime.toInt(), i, flags)
                }
            }
            intent?.send()
        }.onFailure { MiniPlayerRuntime.noteTouch("focus button failed: $it") }
    }

    /**
     * The row's own button: focus_button_icon1, 2, 3 for a picture, focus_button_progress1, 2, 3
     * for one in a ring, focus_button_container_action for the text one (ModuleButtonViewHolder).
     */
    private fun rowButton(row: View, index: Int, type: Int): View? {
        val names = when (type) {
            1 -> setOf("focus_button_progress${index + 1}")
            2 -> setOf("focus_button_container_action")
            else -> setOf("focus_button_icon${index + 1}")
        }
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.visibility == View.VISIBLE) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /**
     * Islands whose title is a focus template's timer - a stopwatch, a countdown - and the timer
     * each shows: the row's chronometer ticks, so the island's line does too, on the second as
     * the chronometer turns it, in tabular figures as HyperChronometer draws them ("tnum").
     */
    private val timerViews = java.util.WeakHashMap<MiniPlayerView, LockIslands.Timer>()

    private fun showTimer(view: MiniPlayerView, timer: LockIslands.Timer?) {
        if (timer == null) {
            rollers.remove(view)?.clear()
            if (timerViews.remove(view) != null) view.titleView.fontFeatureSettings = null
            return
        }
        // Bound again to the timer it already shows (every refresh): the line is set again over
        // what bind wrote, but a tick still coming goes on where it was rather than starting
        // over. One that stopped - every view it had detached - is started again. PR #15.
        val same = timerViews[view] === timer && handler.hasCallbacks(timerTick)
        timerViews[view] = timer
        if (view.titleView.fontFeatureSettings != "tnum") view.titleView.fontFeatureSettings = "tnum"
        timerText(view, timer, timer.displayText())
        if (same) return
        handler.removeCallbacks(timerTick)
        timerTick.run()
    }

    /** The pills whose timer line turns its digits: a running countdown's (timerType -1). */
    private val rollers = java.util.WeakHashMap<MiniPlayerView, RollingDigits>()

    private fun timerText(view: MiniPlayerView, timer: LockIslands.Timer, text: String) {
        if (timer.type == -1) {
            rollers.getOrPut(view) { RollingDigits(view.titleView) }.set(text)
        } else {
            rollers.remove(view)?.clear()
            if (view.titleView.text?.toString() != text) view.titleView.text = text
        }
    }

    /** Progress buttons run by their template's timer: redrawn on every tick. */
    private val autoRings = java.util.WeakHashMap<ProgressRingDrawable, Boolean>()

    private val timerTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var next = Long.MAX_VALUE
            for ((view, timer) in timerViews.entries.toList()) {
                if (!view.isAttachedToWindow || !timer.running) continue
                timerText(view, timer, timer.text(now))
                // To the chronometer's next second: up from its start, down to its end.
                val ms = timer.elapsedMs(now)
                val wait = if (timer.type > 0) 1000L - ms % 1000L else (ms % 1000L).takeIf { it > 0L } ?: 1000L
                next = minOf(next, wait)
            }
            autoRings.keys.toList().forEach { it.invalidateSelf() }
            if (next != Long.MAX_VALUE) handler.postDelayed(this, next + 5L)
        }
    }

    /** A note's picture as the pill's artwork wants it: a bitmap, drawn once per update. */
    private fun noteBitmap(note: LockIslands.Note): Bitmap? {
        // The picture too: the stack island's changes with its apps while its newest stays.
        noteBitmaps[note.key]?.let { (time, icon, bitmap) -> if (time == note.time && icon === note.icon) return bitmap }
        val drawable = note.icon
        val side = dp(56f)
        val bitmap = drawable?.let {
            runCatching {
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    // Fitted, not stretched: a picture left whole may not be square.
                    val w = it.intrinsicWidth
                    val h = it.intrinsicHeight
                    val old = it.copyBounds()
                    if (w > 0 && h > 0 && w != h) {
                        val k = side.toFloat() / maxOf(w, h)
                        val dw = (w * k).toInt()
                        val dh = (h * k).toInt()
                        it.setBounds((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
                    } else {
                        it.setBounds(0, 0, side, side)
                    }
                    it.draw(canvas)
                    it.bounds = old
                }
            }.getOrNull()
        }
        if (noteBitmaps.size > 60) noteBitmaps.clear()
        noteBitmaps[note.key] = Triple(note.time, note.icon, bitmap)
        return bitmap
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private var thumb: Bitmap? = null
    /** The artwork last handed to the pill; it stays until a new one is ready to replace it. */
    private var thumbShown: Bitmap? = null

    /**
     * The artwork at the size the pill draws it. A player's album art is often 1000px or more,
     * and handing that straight to the ImageView uploaded the whole thing to the GPU on the
     * frame of every track change. The scaling runs off the main thread; until it lands the
     * pill keeps the artwork it had.
     */
    private fun thumbnailFor(cover: Bitmap?, view: MiniPlayerView): Bitmap? {
        // A skip sends the new title before the new artwork: keep the old one until it comes
        // rather than going blank for those frames - the flash under the spin.
        if (cover == null || cover.isRecycled) return shown(null)
        // By content, not by object: MediaController.getMetadata() is a binder call and hands
        // back a new Bitmap every time. Compared by identity, every refresh was "new artwork",
        // scaled it again, and the scaled one's own refresh fetched another - a loop that never
        // let a scaled one be shown, which left the pill's artwork empty.
        val key = artworkKey(cover)
        if (key == thumbKey) return shown(thumb)
        // One thumbnail for every view the music is bound to, so sized by a laid-out artwork -
        // the pill's, else this one's - never less than the pill's default. A flight or spare
        // bound before its first layout has an artwork 1px wide: the thumbnail came out 2x2,
        // was kept for the track, and the pill drew it as a blurred blob (filmed 2026-09-25).
        val minSide = dp(40f)
        val side = (listOf(player?.artworkView?.width ?: 0, view.artworkView.width)
            .firstOrNull { it >= minSide } ?: dp(48f)) * 2
        thumbKey = key
        if (cover.width <= side && cover.height <= side) {
            thumb = cover
            return shown(cover)
        }
        thumb = null
        MiniPlayerRuntime.artWorker.execute {
            val scaled = runCatching {
                val k = side.toFloat() / minOf(cover.width, cover.height)
                Bitmap.createScaledBitmap(cover, (cover.width * k).toInt().coerceAtLeast(1),
                    (cover.height * k).toInt().coerceAtLeast(1), true)
            }.getOrNull()
            handler.post {
                if (thumbKey != key || scaled == null) return@post
                thumb = scaled
                // Straight onto the pill: a refresh here fetched the metadata again for nothing.
                val ready = shown(scaled)
                if (selectedIsland == MUSIC_ISLAND) player?.showArtwork(ready)
                else if (smallKey == MUSIC_ISLAND) refresh()
            }
        }
        return shown(null)
    }

    private var thumbKey: String? = null

    /** Size and an 8x8 sample of the pixels: cheap, and different whenever the picture is. */
    private fun artworkKey(b: Bitmap): String {
        var hash = 17L
        if (b.config != Bitmap.Config.HARDWARE) {
            runCatching {
                for (j in 0 until 8) for (i in 0 until 8) {
                    hash = hash * 31 + b.getPixel(i * (b.width - 1) / 7, j * (b.height - 1) / 7)
                }
            }
        } else hash = b.generationId.toLong()
        return "${b.width}x${b.height}#$hash"
    }

    /**
     * A skip sends several metadata updates, one of them with no artwork, while the new
     * artwork is still being scaled: each of those used to blank the pill's artwork for a
     * frame - the grey flash, twice per skip on film. Nothing replaces the shown artwork but
     * a ready one.
     */
    private fun shown(ready: Bitmap?): Bitmap? {
        if (ready != null && !ready.isRecycled) thumbShown = ready
        return thumbShown?.takeUnless { it.isRecycled }
    }

    private fun chooseController(): MediaController? {
        val platform = Main.miniPlayerSession()
        if (platform != null && isUsable(platform)) return platform
        val active = runCatching { sessions?.getActiveSessions(null).orEmpty() }.getOrDefault(emptyList())
        return active.firstOrNull(::isUsable)
    }

    /**
     * A player between two tracks can say STOPPED or NONE for a moment. Read as the music ending,
     * the music island left the row, the card's suppression let go, and the whole media card
     * came up where the pill was and went again (#26). The session the row is showing stays
     * usable for USABLE_GAP_MS after it last was; a session destroyed gets no such grace.
     */
    private fun isUsable(value: MediaController): Boolean {
        if (playbackUsable(stateOf(value)?.state)) {
            if (value.sessionToken == controller?.sessionToken) usableSeenAt = android.os.SystemClock.uptimeMillis()
            return true
        }
        if (cardHolds(value)) return true
        if (usableSeenAt == 0L || value.sessionToken != controller?.sessionToken ||
            value.sessionToken != liveFor) return false
        val left = usableSeenAt + USABLE_GAP_MS - android.os.SystemClock.uptimeMillis()
        if (left <= 0L) return false
        // Settled either way when the gap runs out: the state back, or the music gone for good.
        handler.removeCallbacks(usableGapEnd)
        handler.postDelayed(usableGapEnd, left + 1L)
        return true
    }

    /**
     * The session the lock screen's media card is showing, while the card is up, in a state that
     * says nothing has played yet. QQ Music puts up its notification - and so the card - as soon
     * as it is opened, with the session at NONE or STOPPED until the first play: read by state
     * alone that was no music, so the card stayed a card on the lock screen and never went into
     * the pill until something had been played. The card is what the lock screen shows as the
     * music; once it goes, the state decides again.
     */
    private fun cardHolds(value: MediaController): Boolean {
        val state = stateOf(value)?.state
        if (state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED) return false
        if (!Main.miniPlayerMediaCardPresent()) return false
        return value.sessionToken == Main.miniPlayerSession()?.sessionToken
    }

    private fun playbackUsable(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun updateVisibility() { android.os.Trace.beginSection("MC updateVisibility"); try {
        val view = player
        // Spread out as the stack's list, the row, the small island and the media card are the
        // spread's (endSpread hands them back).
        if (spread != null) {
            view?.setInteractionsEnabled(false)
            return
        }
        val config = this.config
        val enabled = config.getBoolean(MiniPlayerConfig.ENABLED)
        val current = controller
        // Any island at all: the music, or a notification.
        val sessionUsable = islandKeys.isNotEmpty()
        // Unlocking, the keyguard says it is going away on the first frame of its fade-out.
        // Handing the card back then showed the whole OEM card through that fade - the
        // artwork flashing on every unlock. Until the lock screen is actually off the screen,
        // it keeps what it had; the pill fades with it (followShortcuts).
        if (enabled && sessionUsable && MiniPlayerScene.keyguardGoingAway && Main.onKeyguardNow()) {
            player?.setInteractionsEnabled(false)
            return
        }
        val keyguardOwned = enabled && sessionUsable &&
            !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked()
        // The lock screen's own choice of pill or card holds through everything that passes over
        // the lock screen - the doze, the PIN pad, the control centre. It used to be dropped
        // for each of them, so the card came back for the doze and for every swipe that raised
        // the pad; the pill now fades and zooms with the shortcut row instead (followShortcuts)
        // and only stops taking touches.
        // With music, the lock screen's media card has to be up; a row of notifications alone
        // needs only the lock screen.
        // The music on its flight out of the cover is not gated by the card either, as canShow():
        // startSceneFlight puts it back first in the row a moment before its morph exists, and
        // for that refresh the cover still up read as "not presentable" - the row went and came
        // back within 2ms, a whole list rebuild each way, on every exit (probed 2026-09-25).
        val presentable = if (MUSIC_ISLAND in islandKeys && noteMorphKey != MUSIC_ISLAND)
            Main.miniPlayerPresentable() else Main.miniPlayerIslandsPresentable()
        val sceneVisible = keyguardOwned && presentable && !MiniPlayerScene.blocksMiniPlayer
        // Unlocking or a session ending mid-morph: straight to where it was going.
        if (!keyguardOwned) morph?.cancel()
        // The discs are the lock screen's, not the row's: with no island at all - no music, no
        // notification, or the last one pulled out as its row - the torch and camera kept losing
        // their glass (2026-09-25, and #10/#17 after it). They stay wherever the row could be.
        discsWanted = enabled && !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked()
        rowWanted = keyguardOwned || discsWanted && LockIslands.releasedKeys().isNotEmpty()
        // The control centre stands in for the lock screen's scene block, not for the row being
        // presentable at all. Tapped into the cover, the music is still the pill's choice - the
        // scene only borrows the card - so pulling the centre down put the pill up beside the
        // cover's card: two media cards (the user, 2026-09-30). Pulled up into it, the choice
        // was the card and it never showed.
        val controlCenterOpen = keyguardOwned && presentable &&
            (MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp())
        val nativeRequested = MiniPlayerRuntime.nativeRequested(current?.sessionToken)
        // The music's choice of card or pill is the row's only while the music is settled in
        // the row. Out as its card with other islands in the row, the row still shows, and the
        // card with it; on its way out or in, the card is its morph's.
        val musicSettled = MUSIC_ISLAND in islandKeys && noteMorphKey != MUSIC_ISLAND &&
            exchange?.has(MUSIC_ISLAND) != true
        // A notification's morph is not the media card's: the card stays put away. The music's own
        // flight is: the row stays up under it, through a scene too.
        val cardMoving = (morph != null || group != null) &&
            (noteMorphKey == null || noteMorphKey == MUSIC_ISLAND)
        fun presentationWith(transition: Boolean) = MiniPlayerPresentationPolicy.evaluate(
            MiniPlayerPresentationInput(
                enabled = enabled,
                sessionUsable = sessionUsable,
                nativeRequested = nativeRequested && musicSettled,
                keyguardOwned = keyguardOwned,
                sceneVisible = sceneVisible,
                nativeSceneOverride = keyguardOwned && Main.coverSceneActive(),
                transitionActive = transition,
                controlCenterOpen = controlCenterOpen,
                miniShowsMusic = pillShowsMusic,
            ),
        )
        // An exchange keeps the row up - out of the cover, the row went for its frames and every
        // island's notification stood in the stack with the card - but lets the media card out
        // only when the music is one of its two (musicSettled): two notifications switching, the
        // card came up over them and went again as the switch ended (2026-09-25).
        val presentation = presentationWith(cardMoving || exchange != null)
        val suppressCard = presentationWith(cardMoving).suppressNative
        if (sceneLandedAt != 0L && (Main.coverSceneActive() ||
                android.os.SystemClock.uptimeMillis() - sceneLandedAt > SCENE_WAIT_MS)) sceneLandedAt = 0L
        val shown = presentation.showMini && sceneLandedAt == 0L
        // The stack leaves the notifications out only while the row is there to show them.
        // ...and not while the media card's morph has the rows turning into islands or back.
        // No notification island in the row, but one would show if it came - the empty row, or
        // the music alone out as its card: the stack is kept from it already. Asked only once
        // it had come, the row let it into the stack first, and its row was drawn there, text and
        // icon, for the frames before the island took it (the torch's own notification after a
        // double tap on it, 2026-09-29).
        val noteWouldShow = enabled && !MiniPlayerScene.keyguardGoingAway && Main.keyguardLocked() &&
            sceneLandedAt == 0L && MiniPlayerPresentationPolicy.evaluate(
                MiniPlayerPresentationInput(
                    enabled = true,
                    sessionUsable = true,
                    nativeRequested = false,
                    keyguardOwned = true,
                    sceneVisible = Main.miniPlayerIslandsPresentable() && !MiniPlayerScene.blocksMiniPlayer,
                    controlCenterOpen = MiniPlayerScene.controlCenterIsActive || Main.miniPlayerControlCenterUp(),
                ),
            ).showMini
        val active = (shown && keyguardOwned || noteWouldShow && islandKeys.none { it != MUSIC_ISLAND }) &&
            !rowsHeld()
        if (active != lastActive) {
            // Which input turned the row off or on, and who asked: leaving the cover the row
            // went and came back within 10ms, each a whole list rebuild (2026-09-25).
            lastActive = active
            val by = Throwable().stackTrace.getOrNull(1)?.let { "${it.methodName}:${it.lineNumber}" }
            MiniPlayerRuntime.noteTouch("active=$active by=$by keys=${islandKeys.size} " +
                "ko=$keyguardOwned pres=$presentable blk=${MiniPlayerScene.blocksMiniPlayer} " +
                "nr=$nativeRequested ms=$musicSettled tr=${cardMoving || exchange != null} " +
                "cc=$controlCenterOpen landed=${sceneLandedAt != 0L} morph=${morph != null} " +
                "scene=${Main.coverSceneActive()}")
        }
        LockIslands.setActive(active)
        reopenKeptList(active && sceneVisible)
        // The island in the pill is kept across an unlock (the user, 2026-10-24): locked again,
        // the row is as it was left, and an island gone meanwhile falls to the row's first.
        // While a flight is out and coming back, its end is the small island's place: the small
        // island is the flight, and whatever was there makes way. Once it is out for good the
        // place is the next island's.
        // Coming in (a collapse), from the moment the flight exists: counted only once its morph
        // ran, the small island grew in its place for the frame before and then shrank for it.
        // Going out it waits for the morph, which takes it over in place.
        val held = flight != null && (morph != null || !flightFromSmall) && !flightOut &&
            smallKey == noteMorphKey && !flightLanding
        // Under the media card's morph the group has the small island (groupShowsSmall).
        setSmallShown(shown && smallKey != null && !held && !exchangeHoldsSmall() &&
            (groupShowsSmall() ?: true),
            animate = shown && !snapSmallOnce && group == null && exchange == null)
        snapSmallOnce = false
        if (!keyguardOwned) preferredSmall = null
        // Cards still out across an unlock (LockIslands keeps the focus ones) go home where they
        // came from; the rest of the record is this lock screen's.
        if (!Main.keyguardLocked()) {
            releasedFromPill.retainAll { it != MUSIC_ISLAND && LockIslands.isReleased(it) }
            releasedFromSmall.retainAll { it != MUSIC_ISLAND && LockIslands.isReleased(it) }
        }
        if (view != null) {
            val target = if (shown) View.VISIBLE else View.GONE
            if (view.visibility != target) {
                // Up out of nothing on a lock screen already there: it grows in. Coming back
                // under a flight, a card's morph or a switch, or with the row's wake fade, those
                // bring it; and a row going just goes.
                // New is a notification island the row did not have when last shown - not only an
                // empty record. The record is emptied only by the way back, so a row put away any
                // other way (the unlock, the doze, the cover) left it full, and the next
                // notification came up in a frame unless the lock screen happened to be quiet:
                // the animation "sometimes lost" (2026-10-14). The same islands coming back on
                // the wake's fade are still not new, and still come with it.
                val newRow = lastShownKeys.isEmpty() ||
                    islandKeys.any { it != MUSIC_ISLAND && it !in lastShownKeys }
                val growIn = target == View.VISIBLE && keyguardOwned && !frameTaken() &&
                    (rowQuiet() && Main.screenOnCached() || newRow)
                // The last island gone from a lock screen that stays: it goes back into nothing.
                // Not for a row put away with the card, the cover, the unlock or the control centre.
                // The pill still has a notification in it and nothing is left for the row: no
                // island, or only the music on its card (rowGoing). Not the pill that was the
                // music and has just gone up into its card - that has already gone, as its morph.
                val rowGone = target == View.GONE && !pillShowsMusic &&
                    islandKeys.none { it != MUSIC_ISLAND } &&
                    (islandKeys.isEmpty() || nativeRequested || Main.coverModeOn()) && enabled &&
                    Main.keyguardLocked() && !coverMoving() && !MiniPlayerScene.blocksMiniPlayer &&
                    !controlCenterOpen && !frameTaken()
                // Only a pill on screen goes back where it can be seen. The power key takes the
                // torch's island in the same moment it starts the screen off - the row faded to
                // nothing, the keyguard flagged as going - and a double press brings the screen
                // back without it: gone there in one frame, it was never seen going (2026-09-29).
                // Such a pill waits, as it is, until it is on screen again - lit, or held into
                // the AOD with the row - and goes back then; waited out, it just goes.
                // Half there is enough: coming back on the wake's fade, it goes back as it fades in.
                val seen = pillSeen()
                val shrinkOut = rowGone && seen
                val now = android.os.SystemClock.uptimeMillis()
                val waitBack = rowGone && !seen && !leaving() &&
                    (backWaitSince == 0L || now - backWaitSince < BACK_WAIT_MS)
                if (waitBack && backWaitSince == 0L) {
                    backWaitSince = now
                    MiniPlayerRuntime.noteGone("wait away=${MiniPlayerScene.keyguardGoingAway} " +
                        "pillFade=${"%.2f".format(followPillFade)} screen=${Main.screenOnCached()} aod=${MiniPlayerScene.aodActive}")
                }
                if (!waitBack) backWaitSince = 0L
                // Also the way back already running and cut here: the else below ends it in a frame.
                if (target == View.VISIBLE) MiniPlayerRuntime.noteGone(
                    "UP grow=$growIn new=$newRow seen=${pillSeen()} sel=${selectedIsland?.takeLast(6)} keys=${islandKeys.joinToString(",") { it.takeLast(6) }} " +
                    "owned=$keyguardOwned screen=${Main.screenOnCached()} aod=${MiniPlayerScene.aodActive} row=${quietWhy()}")
                if (target == View.GONE && !waitBack && (!leaving() || !shrinkOut)) MiniPlayerRuntime.noteGone(
                    (if (shrinkOut) "back" else if (leaving()) "CUT mid-way" else "CUT") + " sel=${selectedIsland?.takeLast(6)} " +
                    "keys=${islandKeys.joinToString(",") { it.takeLast(6) }} pillMusic=$pillShowsMusic " +
                    "native=$nativeRequested on=$enabled away=${MiniPlayerScene.keyguardGoingAway} " +
                    "locked=${Main.keyguardLocked()} cover=${Main.coverSceneActive()} " +
                    "blocks=${MiniPlayerScene.blocksMiniPlayer} cc=$controlCenterOpen " +
                    "screen=${Main.screenOnCached()} row=${quietWhy()}")
                if (shrinkOut) startDisappear()
                else if (waitBack) Unit
                else if (rowGone && leaving()) Unit // on its way back already, seen or not
                else {
                    if (rowGone) lastShownKeys = emptyList()
                    if (target == View.GONE) endAppear()
                    view.visibility = target
                    if (growIn) startAppear()
                }
            } else {
                if (target == View.VISIBLE && leaving()) startAppear()
                backWaitSince = 0L
            }
            view.setInteractionsEnabled(canShow() && !controlCenterOpen && !MiniPlayerScene.aodActive && !leaving() &&
                backWaitSince == 0L)
            if (view.visibility == View.VISIBLE && !leaving() && backWaitSince == 0L && islandKeys.isNotEmpty()) {
                lastShownKeys = islandKeys
            }
        }
        val suppressNative = keyguardOwned &&
            btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.suppressNativeMedia(suppressCard && musicSettled)
        updateNativeSuppression(suppressNative)
        if (!suppressNative && morph == null && keyguardOwned &&
            (nativeRequested || Main.coverSceneActive())) ensureNativeHeaderVisible()
        val log = "nativeRequested=$nativeRequested " +
            "showMini=${presentation.showMini} suppressNative=${presentation.suppressNative} " +
            "keyguardOwned=$keyguardOwned sceneVisible=$sceneVisible center=$controlCenterOpen " +
            "sceneOverride=${Main.coverSceneActive()} transition=${morph != null}"
        if (log != lastPresentationLog) {
            lastPresentationLog = log
            Xp.d("MCMini: presentation $log")
        }
        // A focus notification's second button while the pill is the only island: the big one.
        // Out of sight under a switch or a flight, it is set, not grown: it grew in only once the
        // pill was given back, a beat after everything else had landed.
        view?.setSecondShown(shown && smallKey == null, animate = !pillHeld() && !exchangeHoldsPill())
    } finally { android.os.Trace.endSection() } }

    private fun updateNativeSuppression(suppress: Boolean) {
        val wasRequested = nativeSuppressionRequested
        nativeSuppressionRequested = suppress
        if (suppress) updateHeader()
        else if (wasRequested || suppressedHeaders.hasOverrides) restoreHeader()
    }

    private fun ensureNativeHeaderVisible() {
        // A dismissed card is the OEM's to take away, not ours to bring back.
        if (!Main.miniPlayerMediaCardPresent()) return
        val current = transitionHeader() ?: return
        if (current.visibility != View.VISIBLE) current.visibility = View.VISIBLE
        if (current.alpha < 0.99f) current.alpha = 1f
    }

    private fun updateHeader() {
        val current = header?.get()?.takeIf { it.isAttachedToWindow && !forceHeaderRefresh }
            ?: Main.miniPlayerMediaHeader()
        forceHeaderRefresh = false
        val previous = header?.get()
        if (previous !== current) {
            header = current?.let(::WeakReference)
            if (previous != null && current != null) {
                Xp.log("MCMini: native media header replaced while suppression remains active")
            }
        }
        if (current == null) return
        // INVISIBLE keeps the OEM layout measured but prevents its renderer/property animator
        // from winning a frame over an alpha-only suppression during metadata replacement.
        suppressedHeaders.suppress(current,
            if (current.visibility == View.INVISIBLE) View.VISIBLE else current.visibility,
            if (current.alpha <= 0f) 1f else current.alpha) { state ->
            if (current.visibility != state.visibility) current.visibility = state.visibility
            if (current.alpha != state.alpha) current.alpha = state.alpha
        }
    }

    private fun restoreHeader() {
        nativeSuppressionRequested = false
        suppressedHeaders.restore { view, state ->
            view.visibility = state.visibility
            view.alpha = state.alpha
        }
    }

    /** The row laid out again on the next frame, for `op fod`. */
    fun relayout() = schedulePosition()

    private fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation { positionPosted = false; position() }
    }

    /** The pill at rest: its size, and the centre of the row it is laid out in. */
    private class PillRest(val width: Int, val height: Int, val centerX: Float, val centerY: Float)

    /**
     * Where the pill rests with a small island beside it ([small]) or alone - the row's own
     * layout (position), asked for another row than the one showing: an island landing in the
     * pill is laid out for the row it lands in (fitToPill).
     */
    private fun pillRest(small: Boolean): PillRest? {
        if (host.width <= 0 || host.height <= 0) return null
        // Where the buttons are laid out, not where they are drawn: whatever moves them on top
        // of their layout (the swipe, the doze) reaches the pill through followShortcuts.
        // Taking their drawn place here as well moved the pill twice as far as they went.
        // Only the ones switched on: one switched off takes no disc, and gives no room up.
        // Both had to be laid out before - one switched off since SystemUI started never was,
        // and the row went to the middle of the screen (#13).
        val l = if (placesRow(left)) restCentre(left) else null
        val r = if (placesRow(right)) restCentre(right) else null
        val centerX = if (l != null && r != null) (l[0] + r[0]) / 2f else host.width / 2f
        val config = this.config
        val requestedWidth = dp(config.getDouble(MiniPlayerConfig.WIDTH).toFloat())
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        val centerY = clearOfFingerprint(rowCentreY(l, r, height), height, l, r)
        val beside = small && !stackedStyle()
        if ((l == null || r == null) && config.optBoolean(MiniPlayerConfig.ADAPTIVE_WIDTH)) {
            widenedRest(l, r, beside, height, centerY)?.let { return it }
        }
        // Clear of the discs, a circle as tall as the pill on each button. Laid out rather than
        // shown: the buttons are put away in the doze, and the pill must not widen for it.
        val width = MiniPlayerGeometry.clearOfDiscsPx(
            MiniPlayerGeometry.widthPx(min(requestedWidth, (host.width * .64f).toInt()),
                host.width, centerX, dp(12f)),
            centerX, l?.get(0), r?.get(0), height.toFloat(),
            dp(MiniPlayerGeometry.DISC_GAP_DP).toFloat(), dp(MiniPlayerGeometry.MIN_PILL_DP))
        // With a small island the pill makes room for it, the two centred as one group - and
        // the group kept clear of both discs: the camera's touch area is wider than its disc,
        // and a group reaching into it lost the small island's taps to the camera.
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val pillWidth = if (!beside) width else MiniPlayerGeometry.pillBesideIslandPx(width,
            MiniPlayerGeometry.clearOfDiscsPx(
                MiniPlayerGeometry.widthPx(host.width, host.width, centerX, dp(12f)),
                centerX, l?.get(0), r?.get(0), height.toFloat(), gap.toFloat(), 0),
            height, gap, dp(MiniPlayerGeometry.MIN_PILL_DP))
        return PillRest(pillWidth, height, centerX, centerY)
    }

    /**
     * A shortcut switched off in the lock screen's settings: the OEM makes its image and its
     * frame GONE and leaves both in the tree (ShortcutViewLayoutController.updateShortcutView).
     * Put away for the doze or the PIN pad it is still switched on - that is the row's fade,
     * and the row must not widen for it.
     */
    private fun switchedOff(button: View): Boolean = button.visibility == View.GONE

    /** A button the row is laid out by: switched on, and laid out. */
    private fun placesRow(button: View): Boolean =
        !switchedOff(button) && button.width > 0 && button.height > 0

    /**
     * The row's height on the screen: the buttons', or where one switched off was laid out
     * last - GONE is not laid out again, it keeps its place - or, with neither ever laid out,
     * where a row was last seen in this SystemUI, as a share of the screen. Only a SystemUI
     * started with both switched off has none of those, and the row sits above the
     * navigation bar.
     */
    private fun rowCentreY(l: FloatArray?, r: FloatArray?, height: Int): Float {
        val y = when {
            l != null && r != null -> (l[1] + r[1]) / 2f
            l != null -> l[1]
            r != null -> r[1]
            left.height > 0 -> restCentre(left)[1]
            right.height > 0 -> restCentre(right)[1]
            else -> null
        }
        if (y != null) {
            MiniPlayerRuntime.rowCentreShare = y / host.height
            return y
        }
        val share = MiniPlayerRuntime.rowCentreShare
        if (!share.isNaN()) return share * host.height
        val inset = runCatching {
            host.rootWindowInsets?.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars())?.bottom
        }.getOrNull() ?: 0
        return (host.height - inset - dp(12f) - height / 2f).coerceAtLeast(height / 2f)
    }

    /**
     * The row's centre with the lift applied, and how far it had to move ([fodLiftPx]): the touch
     * region is the row's too, and it needs the second as much as the first (#81).
     */
    private fun clearOfFingerprint(centerY: Float, height: Int, l: FloatArray?, r: FloatArray?): Float {
        val lifted = liftOffFingerprint(centerY, height, l, r)
        fodLiftPx = (centerY - lifted).coerceAtLeast(0f)
        return lifted
    }

    /**
     * The row's centre moved up off the fingerprint sensor where the two would meet
     * (MiniPlayerRuntime.fingerprintArea, #66); the torch and camera stay where they are. Only
     * the span between the buttons counts across: the row never reaches past them. Under a
     * switch (MiniPlayerConfig.FOD_LIFT); off, the row stays put and only the touches that start
     * on the sensor are still left to it.
     */
    private fun liftOffFingerprint(centerY: Float, height: Int, l: FloatArray?, r: FloatArray?): Float {
        if (!config.optBoolean(MiniPlayerConfig.FOD_LIFT, true)) return centerY
        val fod = fingerprintInHost() ?: return centerY
        val from = l?.get(0) ?: 0f
        val to = r?.get(0) ?: host.width.toFloat()
        if (fod.right <= from || fod.left >= to) return centerY
        val margin = dp(FOD_MARGIN_DP)
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        if (bottom + margin <= fod.top || top - margin >= fod.bottom) return centerY
        val lifted = fod.top - margin - height / 2f
        if (lifted != fodLiftedTo) {
            fodLiftedTo = lifted
            Xp.log("MCMini: row lifted off the fingerprint sensor $fod: centre $centerY -> $lifted")
        }
        return min(centerY, lifted)
    }

    private var fodLiftedTo = Float.NaN

    /**
     * How far up the row actually sits off the line the buttons lay it out on, in px - 0 while
     * nothing lifts it. From the last layout, as [pillRest] worked it out.
     */
    private var fodLiftPx = 0f

    /**
     * Where the keyguard's notifications must end for the row lifted off the sensor - the lifted
     * row's top less the same margin it keeps from the sensor - in the stack's own coordinates,
     * as SystemUI's stacking bottom is; null with the row not lifted (MiniPlayerRuntime.
     * notificationBottom). Whether the row is shown does not count.
     */
    fun stackFloor(): Float? {
        if (host.width <= 0 || host.height <= 0) return null
        if (!config.optBoolean(MiniPlayerConfig.FOD_LIFT, true)) return null
        val l = if (placesRow(left)) restCentre(left) else null
        val r = if (placesRow(right)) restCentre(right) else null
        val height = dp(MiniPlayerConfig.visibleHeightDp(config.toString()))
        val natural = rowCentreY(l, r, height)
        val lifted = clearOfFingerprint(natural, height, l, r)
        if (lifted >= natural) return null
        val stackTop = stackTopInHost() ?: return null
        return lifted - height / 2f - dp(FOD_MARGIN_DP) - stackTop
    }

    /**
     * The stack's top in the host, as laid out: SystemUI's bottom is a layout position, and the
     * swipes and the unlock translate the stack's parents.
     */
    private fun stackTopInHost(): Float? {
        var v: View = notificationStack() ?: return null
        var y = 0f
        while (v !== host) {
            y += v.top
            v = v.parent as? View ?: return null
        }
        return y
    }

    /** The sensor in the host's coordinates, or null. */
    private fun fingerprintInHost(): android.graphics.Rect? {
        val fod = MiniPlayerRuntime.fingerprintArea(context) ?: return null
        val at = IntArray(2).also(host::getLocationOnScreen)
        return android.graphics.Rect(fod).apply { offset(-at[0], -at[1]) }
    }

    /** For `op fod`: the sensor as the row sees it, and where the row is. */
    fun describeFingerprint(): String {
        val fod = fingerprintInHost()
        val rest = pillRest(smallKey != null)
        return "sensor(host)=${fod ?: "none"} row centre=${rest?.centerY} height=${rest?.height}" +
            " lifted=${if (fodLiftedTo.isNaN()) "never" else fodLiftedTo}" +
            " liftPx=${fodLiftPx.toInt()}"
    }

    /**
     * The row across the room a switched-off shortcut leaves (MiniPlayerConfig.ADAPTIVE_WIDTH):
     * from the disc still there, or from where the missing disc's outer edge would be, across
     * to the other side - the pill and its small island centred in it as one group. Null when
     * that room is narrower than the pill's least width.
     */
    private fun widenedRest(l: FloatArray?, r: FloatArray?, small: Boolean, height: Int,
                            centerY: Float): PillRest? {
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        // The discs' outer margin, from a button still laid out, switched off or not.
        val margin = listOf(left, right).firstOrNull { it.width > 0 && it.height > 0 }
            ?.let { restCentre(it)[0] }
            ?.let { cx -> (min(cx, host.width - cx) - height / 2f).coerceAtLeast(dp(12f).toFloat()) }
            ?: dp(12f).toFloat()
        val start = l?.let { it[0] + height / 2f + gap } ?: margin
        val end = r?.let { it[0] - height / 2f - gap } ?: (host.width - margin)
        val room = (end - start).toInt()
        val least = dp(MiniPlayerGeometry.MIN_PILL_DP)
        if (room < least) return null
        val pillWidth = if (!small) room
            else MiniPlayerGeometry.pillBesideIslandPx(room, room, height, gap, least)
        return PillRest(pillWidth, height, (start + end) / 2f, centerY)
    }

    private fun position() {
        // The buttons laid out again may have moved the floor; the pill need not be up for it.
        runCatching { MiniPlayerRuntime.checkStackFloor() }
        val view = player ?: return
        if (view.visibility != View.VISIBLE || spread != null) return
        val small = smallKey != null
        val rest = pillRest(small) ?: return
        val pillWidth = rest.width
        val height = rest.height
        val centerY = rest.centerY
        val gap = dp(MiniPlayerGeometry.DISC_GAP_DP)
        val beside = small && !stackedStyle()
        val group = pillWidth + if (beside) gap + height else 0
        val groupLeft = rest.centerX - group / 2f
        if (small) {
            if (stackedStyle()) {
                smallRest[0] = rest.centerX
                // The 90% rear card exposes a rim below the front card.
                smallRest[1] = MiniPlayerGeometry.stackBackCenterYPx(centerY, height,
                    dp(MiniPlayerGeometry.STACK_EDGE_DP))
                if (!smallWide) smallIsland?.let {
                    resizeSmallIsland(it, maxOf(discFrame(height), smallRestWidth() + dp(16f)),
                        discFrame(height))
                }
            } else {
                smallRest[0] = groupLeft + pillWidth + gap + height / 2f
                smallRest[1] = centerY
            }
        }
        // The asked-for size, not view.width: a morph resizes the frame while this still runs.
        // The spring first, then the new size: set the other way round, the pill was drawn at
        // its new width for the frame before its spring set out from the old one.
        val x = groupLeft - view.left
        val y = centerY - height / 2f - view.top
        view.setBaseTranslation(x, y)
        rowTarget(groupLeft, pillWidth.toFloat())
        if (view.layoutParams.width != pillWidth || view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.width = pillWidth; this.height = height }
            schedulePosition()
        }
    }

    private fun togglePlayback() = command(
        PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    ) { current, playing ->
        if (playing) current.transportControls.pause() else current.transportControls.play()
    }

    private fun skip(next: Boolean) = command(
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    ) { current, _ ->
        if (next) current.transportControls.skipToNext() else current.transportControls.skipToPrevious()
    }

    private fun command(actionWhenPlaying: Long, actionWhenPaused: Long, fallbackKey: Int,
                        transport: (MediaController, Boolean) -> Unit) {
        handler.post {
            runCatching {
                val current = chooseController() ?: controller
                val playing = current?.playbackState?.state == PlaybackState.STATE_PLAYING
                val action = if (playing) actionWhenPlaying else actionWhenPaused
                val supported = current?.playbackState?.actions?.and(action) != 0L
                if (current != null && supported) {
                    runCatching { transport(current, playing) }
                        .onFailure { dispatchFallback(fallbackKey) }
                } else dispatchFallback(fallbackKey)
            }.onFailure { Xp.w("MCMini: media command failed: $it") }
        }
    }

    private fun dispatchFallback(key: Int) {
        val manager = audio ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now,
                android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
        }
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density + .5f).toInt()

}

/** The small island's nudge home: MiniPlayerView's OFFSET_RESPONSE, CoverMorphMotion's damping. */
private const val SMALL_NUDGE_RESPONSE = 0.32f
private const val SMALL_NUDGE_DAMPING = 0.8f

/** Between the row of islands and the fingerprint sensor it is lifted above (#66). */
private const val FOD_MARGIN_DP = 12f

/** How long the pill waits, down, for a scene its morph has just landed into. */
private const val SCENE_WAIT_MS = 1000L

/** After the doze, the buttons are the lock screen's again within this long whatever happens. */
private const val HOLD_MAX_MS = 2000L

/** ...and once its own alpha has been back at full for this long. */
private const val HOLD_SETTLE_MS = 300L

/** In a doze that does not hold the buttons, the row faded below this is gone (unheldInDoze). */
private const val DOZE_GONE = 0.05f

/** A disc's frame against its diameter: room for the widest swelling and squeeze. */
private const val DISC_FRAME = 1.6f

/** A flight's home: the small island's circle, or the pill. */
private const val HOME_SMALL = 0
private const val HOME_PILL = 1

/**
 * The row's layout spring: CHANGE_EASE's response with more give, so the pill overshoots its
 * new width and comes back - and squeezes with its speed, [ROW_SQUASH] of its height per
 * width-per-second, at most [ROW_SQUASH_MAX].
 */
private const val ROW_DAMPING = 0.7f
private const val ROW_SQUASH = 0.02f
private const val ROW_SQUASH_MAX = 0.08f

/** What a switch does with the small island: see Swap.smallMode. */
private const val SMALL_KEPT = 0
private const val SMALL_FROM_PILL = 1
private const val SMALL_POP = 2
private const val SMALL_EMERGE = 3
private const val SMALL_FROM_GHOST = 4
private const val SMALL_STACK = 5
/** The small island's pulse (smallIslandScaleAnimation): 1.1, sinInOut 200ms each way after 100ms. */
private const val PULSE_SCALE = 1.1f
private const val PULSE_MS = 200f
private const val PULSE_DELAY_MS = 100f
/** The fastest a switch cut short hands its speed on, in its progress a second. */
private const val SWAP_CARRY_MAX = 8f

/** From here on, a stand-in shrinking into the small island hands over to it. */
private const val GHOST_HANDOFF = 0.85f

/** What kind of switch: see Swap.kind. */
/** Where the card coming down in an expanded switch lands: the big place, the small one, hiding. */
private const val LAND_PILL = 0
private const val LAND_SMALL = 1
private const val LAND_HIDDEN = 2

private const val SWAP_PAIR = 0
private const val SWAP_NEXT = 1
private const val SWAP_PREV = 2
private const val SWAP_STACK = 3

/**
 * The super island's other springs (FolmeEase.spring(damping, response)): APPEAR 0.7/0.5,
 * SHOW 0.95/0.35, HIDDEN 1.0/0.2 (0.99 here: the spring's closed form divides by
 * sqrt(1 - damping^2)), ALPHA 0.95/0.15.
 */
private const val APPEAR_RESPONSE = 0.5f
private const val APPEAR_DAMPING = 0.7f
/**
 * The row coming up out of nothing (applyAppear): how far below its place it starts, how small,
 * how blurred its content, and the content's two beats - the picture's starting scale and how
 * far left the text slides in from. The stretch is per unit of the spring's velocity: rising
 * (tall and narrow) and settling back off the overshoot (flat and wide), the latter stronger
 * since that velocity is the smaller one.
 */
private const val APPEAR_RISE_DP = 40f
private const val APPEAR_SCALE_FROM = 0.75f
/** The width starts nearer its own size than the height does, and takes this share of the overshoot. */
private const val APPEAR_WIDTH_FROM = 0.9f
private const val APPEAR_WIDTH_OVERSHOOT = 0.4f
/** How far the torch and camera give, per pixel the pill stands wider than its place. */
private const val APPEAR_PUSH_GAIN = 1.6f
private const val APPEAR_BLUR_DP = 8f
private const val APPEAR_ICON_FROM = 0.6f
private const val APPEAR_TEXT_SHIFT_DP = 8f
private const val APPEAR_STRETCH = 0.012f
private const val APPEAR_LAND = 0.1f
private const val APPEAR_SQUASH_MAX = 0.1f
/**
 * Damping 0.5 overshoots ~16%: 40dp of rise carries ~6dp past, the height to ~1.04. 0.55/0.45
 * took 1.3s to settle (`op mini`: up done 1324ms), which read as slow.
 */
private const val ROW_POP_RESPONSE = 0.4f
private const val ROW_POP_DAMPING = 0.5f
private const val SHOW_RESPONSE = 0.35f
private const val SHOW_DAMPING = 0.95f
private const val HIDDEN_RESPONSE = 0.2f
private const val HIDDEN_DAMPING = 0.99f
private const val ALPHA_RESPONSE = 0.15f
private const val ALPHA_DAMPING = 0.95f

/** HiddenToBigIsland brings the content in on HIDDEN_EASE with this delay. */
private const val CONTENT_IN_DELAY_MS = 100L

/** A hidden island's size against its own (createHiddenProperties: 20% in on each side). */
private const val HIDDEN_SCALE = 0.6f

/** The cutout's stand-in, as a share of the row's height. */
private const val CUTOUT_SHARE = 0.55f

/** How far an island's content goes out of focus leaving the big island's place. */
/** ColorOS's capsule content blur, 9dp (SystemUIPlugin u4.k / u4.b0: j6.j.a(ctx, 9f)). */
private const val SWAP_BLUR_DP = 9f

/** The music's place in the row of islands, beside the notifications' keys. */
internal const val MUSIC_ISLAND = "\u0000music"
/** Every notification but the focus ones, as one island (LockIslands.stackMembers). */
private const val STACK_ISLAND = LockIslands.STACK_KEY

/** An island switch's spring: the super island's CHANGE_EASE. */
private const val SWAP_RESPONSE = 0.4f
private const val SWAP_DAMPING = 0.82f

/** The super island's swipe progress: the pull over half the width, times this. */
private const val SWIPE_SHARE = 0.14f

/**
 * Sideways, tightly: the row is pressing on what is beside it - the torch, the camera, the small
 * island - and a loose follow slid it far too easily (the user, 2026-09-29). It carries the row
 * past the end of its islands too (islandDrag), where the pull presses on a disc instead of
 * switching. Here rather than in the object: the controller's own drag reads it as well.
 */
internal fun pillNudgeX(dx: Float, d: Float): Float =
    Math.copySign(MiniCardMorph.rubber(kotlin.math.abs(dx), 24f * d, 0.6f), dx)
/** The stack island's other rows come in over this part of their own way out from under it (pileStack). */
private const val PILE_IN_FROM = 0f
private const val PILE_IN_TO = 0.3f
/** The stack is asked for its list for this long at most, every step, till it is one (showStackAsList). */
private const val LIST_ASK_MS = 900L
private const val LIST_ASK_STEP_MS = 50L
/** An island turned round is told to the stack once it has kept its way this long (turnTo). */
private const val TURN_SETTLE_MS = 200L
/** The stack island's rows are looked for again after this long at the most (pileStack). */
private const val PILE_FIND_MS = 8L
/** Each row's spring: a little softer than the switch's, so the last ones settle with a give. */
private const val PILE_RESPONSE = 0.32f
private const val PILE_DAMPING = 0.74f
/** How much of the lead's way each row starts behind the one before it, and at most. */
private const val PILE_STAGGER = 0.12f
private const val PILE_STAGGER_MAX = 0.48f
/** Live pictures kept per id for the next place to show (liveArt): the pill, the small island, a flight. */
private const val LIVE_SPARES = 3
/** How long a pill whose island went while it was not on screen waits to be seen going back. */
private const val BACK_WAIT_MS = 2500L
/** The rows settle in well under a second; past this the stack is not drawing them (pileSettle). */
private const val PILE_SETTLE_MAX_MS = 2000L

/** A pull under the threshold goes home in this long. */
private const val SPRING_BACK_MS = 320L

/** How long a notification folded back in may take to come back from the stack. */
private const val RETURN_WAIT_MS = 1500L

/** The island in the pill missing from the reads this long keeps the pill (高德 re-posts every second). */
private const val PICK_GRACE_MS = 2000L
/** An island missing this long is new when it comes back, and may take the pill again. */
private const val KNOWN_FORGET_MS = 3000L

/** A row hidden on its way out of the stack shows again if it is still there after this. */
private const val ROW_GONE_MS = 1200L

/** How long the row's session may read as stopped between two tracks and still be the music. */
private const val USABLE_GAP_MS = 1000L
/** A row given back early is drawn on for its morph at most this long, whatever happens to it. */
private const val KEPT_MAX_MS = 3000L
/** A row let out again that the stack has not taken back as a row by now is let go. */
private const val REEADD_MAX_MS = 1000L

/** How long a released notification's row may take to be laid out before the pill moves on. */
private const val ROW_WAIT_MS = 800L

/** The whole row out as the stack's list (Spread): where it is, and each island's place. */
private const val SPREAD_WAIT = 0
private const val SPREAD_OPENING = 1
private const val SPREAD_OPEN = 2
private const val SPREAD_CLOSING = 3
private const val SEAT_NONE = -1
private const val SEAT_BIG = 0
private const val SEAT_SMALL = 1
private const val SEAT_HIDDEN = 2

/** The stack's scroll unmoved this many frames, nothing dragging it: it is at rest. */
private const val SPREAD_STILL_FRAMES = 3

/** A spread still between its ends this long after it set out is sent to the nearer one for good. */
private const val SPREAD_GIVE_UP_MS = 3000L

/** How long materialInk holds its answer: it is read every frame the discs are up. */
private const val MATERIAL_INK_MS = 500L

/** A finger pulling the open list this far toward the fold takes the cards home with it. */
private const val SPREAD_PULL_DP = 8f
/** The spread's frames stopping this long means the screen was off. */
private const val SPREAD_SLEEP_GAP_MS = 700L
/** Woken, the stack has this long to be the list again before a fold counts as one. */
private const val SPREAD_WAKE_MS = 1500L
/** Locked again with the list kept, the row has this long to show the stack island to open. */
private const val LIST_KEPT_WAIT_MS = 1500L

/** Pulled home past this (from 1, the list), the let-go folds the row home: see spreadFolding. */
private const val SPREAD_FOLD_BELOW = 0.95f

/** A pulled-open spread let go this fast goes the way it was flung, whatever its progress. */
private const val SPREAD_FLING_DP = 800f

/** A pulled-open spread let go slowly opens from this far out, and folds back short of it. */
private const val SPREAD_OPEN_AT = 0.35f

/** A row's picture, title and text, by the ids the notification templates give them. */
// A focus notification's row is the plugin's template: its picture (a Lottie view or a still),
// its chronometer or title, and its content line.
private val ICON_NAMES = setOf("right_icon", "icon", "app_icon", "notification_icon", "left_icon",
    "focus_animation", "focus_animation_static", "focus_icon")
private val TITLE_NAMES = setOf("title", "notification_title", "chronometer", "focus_title")
/** A focus template's picture, which the island's picture lands on before any icon. */
private val FOCUS_ART_NAMES = setOf("focus_animation", "focus_animation_static", "focus_icon",
    "focus_large_icon", "focus_small_icon")
private val TEXT_NAMES = setOf("text", "big_text", "notification_text", "focus_content")

/** Below this progress, a flight coming home fades off the small island shown under it. */
private const val FLIGHT_HANDOFF = 0.18f

/** The longest a landed card is held where the stack is settling it, and the least. */
private const val PIN_MS = 1500L
private const val PIN_SETTLE_MS = 150L
/** The held card's spring toward where it is to be; how long a target left stale is believed. */
private const val PIN_RESPONSE = 0.3f
/** Frames the stack's target has to hold, nothing leaving, before a landed card follows it. */
private const val PIN_STEADY_FRAMES = 4

/**
 * The stack's fold, for the rows with no island of their own: pulled, a row follows the finger
 * with friction and goes at most a third of this; let go, it springs back on the stack's
 * release spring (NotificationNumStateAreaAnimator.releaseEase, FolmeEase.spring(1.0, 0.38)).
 */
private const val FOLD_PULL_RANGE_DP = 150f
private const val FOLD_BACK_RESPONSE = 0.38f

/** A row that came after the media card's morph set out comes up to its progress over this long. */
private const val GROUP_JOIN_MS = 160L

/** Views kept for the next switch (prepareSmallView pooled): one going up, one coming down. */
private const val SPARE_VIEWS = 2
private const val STACK_SPARE_VIEWS = 4

/** The shortest a notification pull can be, island to row, in dp. */
private const val NOTE_SPAN_MIN_DP = 160f
