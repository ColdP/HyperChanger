// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.view.View

internal fun resolvePortalGlass(preferences: SharedPreferences, settings: PortalAppearanceSettings): SoftGlassSettings =
    (if (settings.useGlobalGlass) readSoftGlassSettings(preferences) else settings.glass)
        .copy(enabled = true, backgroundBlur = 100, backgroundScale = 100, backgroundOpacity = 100).normalized()

/** Portal has no native MiGlass recipe; use the verified OS4 42-slot floating-surface recipe.
 * The shared tuning function owns all fifteen optical adjustments, including glass blur.
 */
internal object PortalGlassMaterial {
    private val baseline = floatArrayOf(
        .67f, .16f, .09f, 0f, .24f, 1.4f, -.02f, .3f, .6f, 1f,
        .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
        1.2f, 1f, -.4f, .6f, -.8f, 1.4f, .7f, .8f, 1.15f, 4f, 2f,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )

    fun apply(view: View, glass: SoftGlassSettings) {
        val intType = Int::class.javaPrimitiveType
        View::class.java.getMethod("setMiBackgroundBlurRadius", intType).invoke(view, 80)
        View::class.java.getMethod("setMiViewBlurMode", intType).invoke(view, 1)
        View::class.java.getMethod("setMiGlassBlurRadius", intType, intType)
            .invoke(view, softGlassBlur(36, glass.refractionBlur), softGlassBlur(72, glass.refractionBlur))
        View::class.java.getMethod("setMiViewMaterialType", intType).invoke(view, 1)
        if (view.width > 0 && view.height > 0) {
            runCatching {
                View::class.java.getMethod("setMiGlassSdfMaxSize", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(view, view.width.toFloat(), view.height.toFloat())
            }
        }
        View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            .invoke(view, tuneSoftGlass(baseline, glass))
        view.invalidate()
    }

    fun clear(view: View) {
        runCatching { View::class.java.getMethod("setMiGlass", FloatArray::class.java).invoke(view, floatArrayOf()) }
        runCatching { View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType).invoke(view, 0) }
    }
}
