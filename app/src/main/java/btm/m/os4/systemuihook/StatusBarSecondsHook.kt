// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.TextView
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.WeakHashMap

/** Mirrors 状态栏显秒.dex: OEM update, append seconds, then schedule a view tick. */
internal class StatusBarSecondsHook(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val clocks = WeakHashMap<TextView, ClockState>()
    private var settings = StatusBarSecondsSettings()
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val clockClass = classLoader.loadClass("com.android.systemui.statusbar.views.MiuiClock")
            val update = clockClass.getDeclaredMethod("updateTime")
            settings = readStatusBarSecondsSettings(preferences)
            hookDeoptimized(update)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:seconds:update-time")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? TextView
                    if (view != null && view.id == STATUS_BAR_CLOCK_ID) {
                        val state = synchronized(clocks) { clocks.getOrPut(view) { ClockState(view) } }
                        state.installLongPressListener()
                        state.renderAfterOemUpdate()
                    }
                    result
                }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == KEY_STATUS_BAR_SECONDS_MODE || key == KEY_STATUS_BAR_SECONDS_DURATION) {
                    val next = readStatusBarSecondsSettings(preferences)
                    if (settings.mode != next.mode) synchronized(clocks) { clocks.values.forEach { it.activeUntil = 0L } }
                    settings = next
                    synchronized(clocks) { clocks.values.toList() }.forEach { it.renderAfterOemUpdate() }
                }
            }
            preferenceListener = listener
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Could not install status-bar seconds hook", it)
        }
    }

    private inner class ClockState(private val view: TextView) {
        var activeUntil = 0L
        private var tickerOn = false
        private var installed = false
        private val tick = Runnable {
            tickerOn = false
            runCatching { view.javaClass.getMethod("updateTime").invoke(view) }
                .onFailure { HookModuleLogger.log(module, Log.DEBUG, "HyperChanger", "Status-bar seconds tick failed", it) }
        }

        fun installLongPressListener() {
            if (installed) return
            installed = true
            view.setOnLongClickListener {
                if (settings.mode == STATUS_BAR_SECONDS_MODE_LONG_PRESS) {
                    activeUntil = SystemClock.elapsedRealtime() + settings.durationSeconds * 1000L
                    renderAfterOemUpdate()
                    true
                } else false
            }
        }

        fun renderAfterOemUpdate() {
            val enabled = settings.mode == STATUS_BAR_SECONDS_MODE_ALWAYS ||
                (settings.mode == STATUS_BAR_SECONDS_MODE_LONG_PRESS &&
                    activeUntil > SystemClock.elapsedRealtime())
            if (!enabled || !view.isAttachedToWindow || !view.isShown) {
                tickerOn = false
                view.removeCallbacks(tick)
                return
            }
            val base = view.text?.toString().orEmpty()
            if (base.isBlank()) return
            val seconds = ((System.currentTimeMillis() / 1000L) % 60L).toInt()
            val rendered = buildString(base.length + 3) {
                append(base)
                append(':')
                if (seconds < 10) append('0')
                append(seconds)
            }
            if (view.text?.toString() != rendered) view.text = rendered
            if (!tickerOn) {
                tickerOn = true
                view.removeCallbacks(tick)
                view.postDelayed(tick, 1000L - (System.currentTimeMillis() % 1000L))
            }
        }
    }

    private companion object {
        // R.id.clock in the supplied 状态栏显秒.dex.
        const val STATUS_BAR_CLOCK_ID = 2131427965
    }
}
