// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.Color
import android.view.View

/** Shared HyperOS compositor registration, blend and bloom path used by existing module surfaces. */
internal object HyperOsBackdropMaterial {
    val highlight = floatArrayOf(3f, 180f, 1f, 1f, 1f, .05f, 8f, .5f, .5f, -.5f, 1f, 1f, 1f, .6f, .5f, .95f, -.5f, 1f, 1f, 1f, .35f)

    fun apply(view: View, opacity: Int, blurRadius: Int, color: Int, showHighlight: Boolean, bloom: FloatArray = highlight) {
        val viewClass = View::class.java
        viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view)
        viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
            .invoke(view, true)
        viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
            .invoke(view, 1)
        viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
            .invoke(view, 1)
        viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
            .invoke(view, blurRadius.coerceIn(0, 120))
        viewClass.getMethod(
            "addMiBackgroundBlendColor",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(
            view,
            Color.argb(
                opacity.coerceIn(0, 100) * 255 / 100,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            ),
            101,
        )
        if (showHighlight) {
            viewClass.getMethod("setMiBloomStroke", FloatArray::class.java)
                .invoke(view, bloom)
        }
    }

    /** Clear only materials owned by this surface, e.g. when changing button mode or detaching. */
    fun clear(view: View) {
        val type = View::class.java
        runCatching { type.getMethod("setMiGlass", FloatArray::class.java).invoke(view, floatArrayOf()) }
        runCatching { type.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType).invoke(view, 0) }
        runCatching { type.getMethod("setMiBloomStroke", FloatArray::class.java).invoke(view, FloatArray(21)) }
        runCatching { type.getMethod("clearMiBackgroundBlendColor").invoke(view) }
        runCatching { type.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType).invoke(view, false) }
        runCatching { type.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 0) }
        runCatching { type.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 0) }
    }
}
