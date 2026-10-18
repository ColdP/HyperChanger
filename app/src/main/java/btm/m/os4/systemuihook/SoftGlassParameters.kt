// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.roundToInt

/** Verified against HyperLight q32.c / p72.m and SystemUI's 42-slot MiGlass recipes. */
internal data class SoftGlassParameter(
    val key: String,
    val title: String,
    val slot: Int,
    val bounds: ClosedFloatingPointRange<Float>,
    val advanced: Boolean = false,
)

internal val softGlassParameters = listOf(
    SoftGlassParameter("luminance", "明度混合强度", 4, 0f..2f),
    SoftGlassParameter("saturation", "材质饱和度", 5, 0f..3f),
    SoftGlassParameter("brightness", "混合亮度", 6, -1f..1f),
    SoftGlassParameter("darker", "压暗强度", 7, 0f..1f),
    SoftGlassParameter("alpha", "内层不透明度", 14, 0f..2f),
    SoftGlassParameter("refraction", "折射率", 32, 1f..5f),
    SoftGlassParameter("thickness", "光学厚度", 21, 0f..120f),
    SoftGlassParameter("reflection", "反射强度", 24, 0f..2f),
    SoftGlassParameter("background_saturation", "采样背景饱和度", 33, 0f..3f),
    SoftGlassParameter("background_brightness", "采样背景亮度", 34, -1f..1f),
    SoftGlassParameter("burn", "背景烧灼强度", 35, 0f..1f),
    SoftGlassParameter("curve_0", "明度曲线采样点 0", 0, 0f..2f, true),
    SoftGlassParameter("curve_1", "明度曲线采样点 1", 1, 0f..2f, true),
    SoftGlassParameter("curve_2", "明度曲线采样点 2", 2, 0f..2f, true),
    SoftGlassParameter("curve_3", "明度曲线采样点 3", 3, 0f..2f, true),
    SoftGlassParameter("inner_bottom", "内层底部系数", 10, 0f..1f, true),
    SoftGlassParameter("inner_white", "内层白色混合", 15, 0f..2f, true),
    SoftGlassParameter("inner_mix", "内层颜色混合", 16, 0f..2f, true),
    SoftGlassParameter("inner_curve", "内层颜色曲线", 17, .1f..3f, true),
    SoftGlassParameter("edge", "边缘宽度", 19, 0f..100f, true),
    SoftGlassParameter("edge_curve", "边缘过渡曲线", 20, .1f..8f, true),
    SoftGlassParameter("reflection_offset", "反射偏移", 22, 0f..1000f, true),
    SoftGlassParameter("reflection_light", "反射提亮", 23, 0f..2f, true),
    // p72.m maps 25..27 to directionalLight.color, rather than a light position.
    SoftGlassParameter("light_red", "方向光红色分量", 25, -1f..1f, true),
    SoftGlassParameter("light_green", "方向光绿色分量", 26, -1f..1f, true),
    SoftGlassParameter("light_blue", "方向光蓝色分量", 27, -1f..1f, true),
    SoftGlassParameter("light", "方向光强度", 28, 0f..2f, true),
    SoftGlassParameter("light_white", "方向光白色混合", 29, 0f..2f, true),
    SoftGlassParameter("light_mix", "方向光颜色混合", 30, 0f..2f, true),
    SoftGlassParameter("light_curve", "方向光颜色曲线", 31, .1f..3f, true),
)

internal data class SoftGlassSettings(
    val enabled: Boolean = false,
    val elementBlur: Int = 100,
    val backgroundBlur: Int = 100,
    val elementScale: Int = 100,
    val backgroundScale: Int = 100,
    val adjustments: Map<String, Int> = emptyMap(),
) {
    fun normalized() = copy(
        elementBlur = elementBlur.coerceIn(0, 200),
        backgroundBlur = backgroundBlur.coerceIn(0, 200),
        elementScale = elementScale.coerceIn(0, 200),
        backgroundScale = backgroundScale.coerceIn(0, 200),
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
