// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.roundToInt

/** Verified against HyperLight q32.c / p72.m and SystemUI's 42-slot MiGlass recipes. */
internal data class SoftGlassParameter(
    val key: String,
    val title: String,
    val slot: Int,
    val bounds: ClosedFloatingPointRange<Float>,
    val adjustmentRange: IntRange = -100..200,
)

internal val softGlassParameters = listOf(
    SoftGlassParameter("luminance", "明度混合强度", 4, 0f..2f),
    SoftGlassParameter("saturation", "材质饱和度", 5, 0f..3f),
    SoftGlassParameter("brightness", "混合亮度", 6, -1f..1f),
    SoftGlassParameter("alpha", "内层不透明度", 14, 0f..2f),
    SoftGlassParameter("refraction", "折射率", 32, 1f..5f),
    SoftGlassParameter("thickness", "光学厚度", 21, 0f..120f),
    SoftGlassParameter("background_saturation", "采样背景饱和度", 33, 0f..3f),
    SoftGlassParameter("background_brightness", "采样背景亮度", 34, -1f..1f),
    SoftGlassParameter("burn", "背景烧灼强度", 35, 0f..1f),
    SoftGlassParameter("edge", "边缘宽度", 19, 0f..100f),
    // The former reflection-strength slider, with its sign reversed.
    SoftGlassParameter("edge_feather", "边缘羽化", 24, 0f..2f, -200..100),
    SoftGlassParameter("reflection_offset", "反射偏移", 22, 0f..1000f),
    SoftGlassParameter("light", "高光强度", 28, 0f..2f),
    SoftGlassParameter("darker", "压暗强度", 7, 0f..1f),
)

internal data class SoftGlassSettings(
    val enabled: Boolean = false,
    val refractionBlur: Int = 100,
    val backgroundBlur: Int = 100,
    val backgroundScale: Int = 100,
    val backgroundOpacity: Int = 100,
    val adjustments: Map<String, Int> = emptyMap(),
) {
    fun normalized() = copy(
        refractionBlur = refractionBlur.coerceIn(0, 200),
        backgroundBlur = backgroundBlur.coerceIn(0, 200),
        backgroundScale = backgroundScale.coerceIn(0, 200),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 200),
        adjustments = softGlassParameters.associate { it.key to (adjustments[it.key] ?: 0).coerceIn(it.adjustmentRange) },
    )

    fun resetParameters() = SoftGlassSettings(
        enabled = enabled,
        adjustments = mapOf("darker" to -100),
    ).normalized()
}

internal fun hasSoftGlassRecipe(params: FloatArray): Boolean =
    params.size >= 42 && params.all { it.isFinite() } && params.any { it != 0f }

internal fun tuneSoftGlass(
    original: FloatArray,
    settings: SoftGlassSettings,
    standaloneBackground: Boolean = false,
    contextBrightness: Float = 0f,
): FloatArray {
    if (!settings.enabled || !hasSoftGlassRecipe(original)) return original
    return original.copyOf().apply {
        softGlassParameters.forEach { definition ->
            val selected = (settings.adjustments[definition.key] ?: 0).coerceIn(definition.adjustmentRange)
            val delta = if (definition.key == "edge_feather") -selected else selected
            if (delta != 0) {
                val base = original[definition.slot]
                // HyperLight scales nonzero defaults. Limit the zero fallback to hundredths,
                // avoiding its abrupt full-unit jump when a theme's original value is zero.
                val adjusted = if (base == 0f) delta / 100f else base * (1f + delta / 100f)
                this[definition.slot] = adjusted.coerceIn(definition.bounds)
            }
        }
        if (contextBrightness != 0f) {
            // HyperLight raises the unified notification/volume backdrop brightness after
            // applying the global slots. Those surfaces otherwise retain their darker native
            // token even though the same global recipe is selected.
            this[34] = (this[34] + contextBrightness).coerceIn(-1f, 1f)
        }
    }
}

/** Collapse the previous two controls into one inverted reflection adjustment. */
internal fun migrateSoftGlassFeather(reflection: Int, oldFeather: Int = 0): Int {
    val reflectScale = 1f + reflection.coerceIn(-100, 200) / 100f
    val feather = oldFeather.coerceIn(-100, 200)
    val featherScale = if (feather > 0) 1f / (1f + feather / 100f) else 1f - feather / 100f
    return ((1f - reflectScale * featherScale) * 100f).roundToInt().coerceIn(-200, 100)
}

/** Reuse unchanged volume recipes while preserving in-place animation input changes. */
internal class SoftGlassRecipeCache {
    var baseline: FloatArray? = null; private set
    private var output: FloatArray? = null
    private var settings: SoftGlassSettings? = null
    private var standaloneBackground: Boolean = false
    private var contextBrightness: Float = 0f

    fun tune(input: FloatArray, current: SoftGlassSettings, standaloneBackground: Boolean = false, contextBrightness: Float = 0f): FloatArray {
        val source = if (input === output) baseline ?: input else input
        if (settings === current && this.standaloneBackground == standaloneBackground && this.contextBrightness == contextBrightness && baseline?.contentEquals(source) == true) return output!!
        baseline = source.copyOf()
        settings = current
        this.standaloneBackground = standaloneBackground
        this.contextBrightness = contextBrightness
        return tuneSoftGlass(baseline!!, current, standaloneBackground, contextBrightness).also { output = it }
    }
}

internal fun softGlassBlur(radius: Int, percent: Int): Int =
    if (percent == 100 || radius == 0) radius
    else (radius * percent.coerceIn(0, 200) / 100f).roundToInt().coerceIn(0, 500)

internal fun softGlassScale(scale: Float, percent: Int): Float =
    if (percent == 100 || !scale.isFinite()) scale
    else (scale * percent.coerceIn(0, 200) / 100f).coerceIn(0f, 1f)

/** Scale the platform color's alpha, keeping RGB, mode, and animation-zero intact. */
internal fun softGlassColorAlpha(color: Int, percent: Int): Int {
    if (percent == 100) return color
    val alpha = ((color ushr 24) * percent.coerceIn(0, 200) / 100f).roundToInt().coerceIn(0, 255)
    return (color and 0x00ffffff) or (alpha shl 24)
}
