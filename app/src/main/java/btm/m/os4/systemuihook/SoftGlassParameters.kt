// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.roundToInt

/** Verified against HyperLight q32.c / p72.m and SystemUI's 42-slot MiGlass recipes. */
internal data class SoftGlassParameter(
    val key: String,
    val title: String,
    val slot: Int,
    val bounds: ClosedFloatingPointRange<Float>,
)

internal val softGlassParameters = listOf(
    SoftGlassParameter("luminance", "明度混合强度", 4, 0f..2f),
    SoftGlassParameter("saturation", "材质饱和度", 5, 0f..3f),
    SoftGlassParameter("brightness", "混合亮度", 6, -1f..1f),
    SoftGlassParameter("alpha", "内层不透明度", 14, 0f..2f),
    SoftGlassParameter("refraction", "折射率", 32, 1f..5f),
    SoftGlassParameter("thickness", "光学厚度", 21, 0f..120f),
    SoftGlassParameter("reflection", "反射强度", 24, 0f..2f),
    SoftGlassParameter("background_saturation", "采样背景饱和度", 33, 0f..3f),
    SoftGlassParameter("background_brightness", "采样背景亮度", 34, -1f..1f),
    SoftGlassParameter("burn", "背景烧灼强度", 35, 0f..1f),
    SoftGlassParameter("edge", "边缘宽度", 19, 0f..100f),
    SoftGlassParameter("edge_feather", "边缘羽化", 20, .1f..12f),
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
    val backgroundDarkening: Int = 100,
    val adjustments: Map<String, Int> = emptyMap(),
) {
    fun normalized() = copy(
        refractionBlur = refractionBlur.coerceIn(0, 200),
        backgroundBlur = backgroundBlur.coerceIn(0, 200),
        backgroundScale = backgroundScale.coerceIn(0, 200),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 200),
        backgroundDarkening = backgroundDarkening.coerceIn(0, 200),
        adjustments = softGlassParameters.associate { it.key to (adjustments[it.key] ?: 0).coerceIn(-100, 200) },
    )
}

/** Zero adjustments preserve every stock value, including light/dark variants and clear payloads. */
internal fun tuneSoftGlass(original: FloatArray, settings: SoftGlassSettings): FloatArray {
    if (!settings.enabled || original.size < 42 || original.all { it == 0f } || original.any { !it.isFinite() }) return original
    return original.copyOf().apply {
        softGlassParameters.forEach { definition ->
            val delta = (settings.adjustments[definition.key] ?: 0).coerceIn(-100, 200)
            if (delta != 0) {
                val base = original[definition.slot]
                // HyperLight scales nonzero defaults. Limit the zero fallback to hundredths,
                // avoiding its abrupt full-unit jump when a theme's original value is zero.
                val adjusted = if (base == 0f) delta / 100f else base * (1f + delta / 100f)
                this[definition.slot] = adjusted.coerceIn(definition.bounds)
            }
        }
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
    // Two independent 0..200% controls can compound to 400% on the dim layer.
    val alpha = ((color ushr 24) * percent.coerceIn(0, 400) / 100f).roundToInt().coerceIn(0, 255)
    return (color and 0x00ffffff) or (alpha shl 24)
}
