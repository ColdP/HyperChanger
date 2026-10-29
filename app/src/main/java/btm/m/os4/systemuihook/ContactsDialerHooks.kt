// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.View
import java.util.WeakHashMap
import kotlin.math.roundToInt
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule

/** Owns the independent com.android.contacts dial-pad background hook. */
internal class ContactsDialerHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (java.lang.reflect.Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    // Weak keys never keep a destroyed dial-pad View tree alive.
    private val states = WeakHashMap<View, BackgroundState>()

    private class BackgroundState {
        var drawable: Drawable? = null
        var blurApplied = false
        var blurAttempted = false
    }

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        val settings = ContactsDialerSettings.read(preferences)
        if (installed || (!settings.blur && !settings.customOpacity && !settings.border && settings.cornerOffset == 0)) return
        runCatching {
            val dialpadLayout = classLoader.loadClass("com.android.contacts.dialer.view.DialpadLayout")
            val finishInflate = dialpadLayout.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }
            val layout = dialpadLayout.getDeclaredMethod(
                "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val getBackground = dialpadLayout.getMethod("getBackgroundView")
            // Resolve the two verified DEX lifecycle signatures before registering either.
            listOf(finishInflate, layout).forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:contacts-dialer-background:${method.name}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            (getBackground.invoke(chain.thisObject) as? View)?.let { apply(it, settings) }
                        }.onFailure { error ->
                            HookModuleLogger.log(module, Log.DEBUG, logTag, "Could not apply dialer background", error)
                        }
                        result
                    }
            }
            installed = true
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed Contacts dialer background hooks")
        }.onFailure { error ->
            HookModuleLogger.log(module, Log.WARN, logTag, "Contacts dialer background hooks unavailable", error)
        }
    }

    private fun apply(background: View, settings: ContactsDialerBackgroundSettings) {
        val state = states.getOrPut(background) { BackgroundState() }
        if (settings.blur && !state.blurAttempted) {
            state.blurAttempted = true
            state.blurApplied = runCatching {
                // Same mode/radius/view-mode sequence as the APK's miuix blur utility w7.j.p().
                val intType = Int::class.javaPrimitiveType
                val mode = View::class.java.getMethod("setMiBackgroundBlurMode", intType)
                val radius = View::class.java.getMethod("setMiBackgroundBlurRadius", intType)
                val viewMode = View::class.java.getMethod("setMiViewBlurMode", intType)
                mode.invoke(background, 1)
                val radiusDp = if (settings.customBlur) settings.blurRadiusDp else 24
                radius.invoke(background, (radiusDp * background.resources.displayMetrics.density).roundToInt().coerceIn(0, 400))
                viewMode.invoke(background, 1)
                // Keep the native NinePatch outline and its corner geometry.
                background.clipToOutline = true
                true
            }.onFailure { error ->
                runCatching {
                    View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
                        .invoke(background, 0)
                }
                HookModuleLogger.log(module, Log.DEBUG, logTag, "Dialer background blur API unavailable", error)
            }.getOrDefault(false)
        }
        val drawable = background.background ?: return
        if (state.drawable === drawable) return
        val fillOpacity = when {
            settings.customOpacity -> settings.opacityPercent
            state.blurApplied -> 70
            else -> null
        }
        if (fillOpacity == null && !settings.border && settings.cornerOffset == 0) return
        // Mutating the Drawable preserves native NinePatch padding/geometry and isolates it
        // from other resource consumers; View.alpha would also fade the input and keys.
        val isolated = if (settings.border || settings.cornerOffset != 0) {
            ContactsDialerBackgroundDrawable(
                drawable.mutate(), settings, background.resources.displayMetrics.density, fillOpacity,
            )
        } else {
            drawable.mutate().apply { alpha = (requireNotNull(fillOpacity) * 255f / 100f).roundToInt() }
        }
        state.drawable = isolated
        background.background = isolated
        background.invalidateOutline()
    }
}
