// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook.hypermusiccover.next

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method

/**
 * The lock clock's colour extraction, once the clock has stopped moving instead of on every
 * step of its animation.
 *
 * KeyguardClockInjector.triggerColorExtraction runs from AllInOneClockAnimation's
 * clockAnimationComplete, which the clock's folme listener calls on its updates as well as at
 * the end - and the clock animates whenever the stack's rows move it, which the islands do on
 * every pull, swap and landing. Each run asks the wallpaper process for the palette and the
 * part depth (IMiuiWallpaperManagerService #29/#30), and it decodes the whole lock wallpaper
 * from its file for each: 2026-10-07, 378 runs in 80s of use, a dozen decodes at a time and
 * up to 14 CPU-seconds a second in MiWallpaper, with SystemUI's frames starved alongside.
 *
 * The answer depends on where the clock ends up, not on the steps it took, so a run is let
 * through at once only after a quiet spell; one asked for sooner waits for the quiet and then
 * runs once, for wherever the clock is by then.
 */
internal object PaletteThrottle {
    private const val CLS = "com.android.keyguard.injector.KeyguardClockInjector"
    private const val NAME = "triggerColorExtraction"
    private const val QUIET_MS = 300L

    private val handler by lazy { Gen.handler() }
    private var lastRun = 0L
    private var passing = false
    private var pending: Runnable? = null
    private var asked = 0
    private var ran = 0

    /** The last extraction the system asked for, to ask again with (retrigger). */
    private var lastTarget: Any? = null
    private var lastMethod: Method? = null
    private var lastArgs: Array<Any?>? = null
    private var retrigger: Runnable? = null
    private var retriggered = 0

    fun install(classLoader: ClassLoader) {
        val cls = runCatching { Xp.findClass(CLS, classLoader) }.getOrElse {
            Xp.w("MCPalette: $CLS unavailable, extraction not throttled: $it")
            return
        }
        runCatching {
            Xp.hookAll("sysui.palette.throttle", cls, NAME) { chain ->
                if (passing || Looper.myLooper() != Looper.getMainLooper()) {
                    return@hookAll chain.proceed()
                }
                lastTarget = chain.thisObject
                lastMethod = chain.executable as Method
                lastArgs = chain.args.toTypedArray()
                asked++
                val now = SystemClock.uptimeMillis()
                if (pending == null && now - lastRun >= QUIET_MS) {
                    lastRun = now
                    ran++
                    return@hookAll chain.proceed()
                }
                // Asked again while the clock still moves: one run, after it has been still.
                val target = chain.thisObject
                val method = chain.executable as Method
                val args = chain.args.toTypedArray()
                pending?.let(handler::removeCallbacks)
                val run = Runnable {
                    pending = null
                    lastRun = SystemClock.uptimeMillis()
                    ran++
                    passing = true
                    try {
                        method.invoke(target, *args)
                    } catch (t: Throwable) {
                        Xp.w("MCPalette: deferred extraction failed: $t")
                    } finally {
                        passing = false
                    }
                }
                pending = run
                handler.postDelayed(run, QUIET_MS)
                null
            }
        }.onFailure { Xp.log("MCPalette: $NAME not throttled: $it") }
    }

    /**
     * Asks for the clock's colours again, a moment from now, with the system's own last request.
     *
     * The palette is the wallpaper process's answer for what the lock screen shows, and in cover
     * mode that is the cover (WallpaperProbe.installCoverPalette). But the system only asks when
     * its clock has moved: a new track under a still clock, or a cover put up or taken down
     * faster than the clock settles, would keep the last picture's colours. Delayed so the
     * wallpaper process has the new picture on its texture by the time it is asked, and through
     * the same throttle as everything else.
     */
    fun retriggerSoon(delayMs: Long) {
        // The other faces read the per-region palettes, which only a wallpaper change pushes.
        CoverPalette.refreshSoon(delayMs)
        val m = lastMethod ?: return
        val t = lastTarget ?: return
        val a = lastArgs ?: return
        retrigger?.let(handler::removeCallbacks)
        val run = Runnable {
            retrigger = null
            retriggered++
            runCatching { m.invoke(t, *a) }.onFailure { Xp.w("MCPalette: retrigger failed: $it") }
        }
        retrigger = run
        handler.postDelayed(run, delayMs)
    }

    /** For `op palette`. */
    fun describe(): String = "extraction asked $asked, ran $ran, retriggered $retriggered"
}
