// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.abs

/** JVM regression checks using the two recipes from the supplied SystemUI dump. */
fun main() {
    val regular = floatArrayOf(
        .5f, 1f, 0f, .8f, .5f, 1.2f, 0f, .2f, 0f, 0f, .03f,
        1f, 1f, 1f, 1.5f, 0f, .6f, .6f, 1f, 62f, 3.8f, 80f,
        600f, 1f, .8f, -.4f, .6f, -.8f, 1.2f, .6f, .8f, 1.15f,
        3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )
    val dark = floatArrayOf(
        .8f, 1f, 0f, 1f, .2f, 2f, .14f, .1f, 0f, 0f, .02f,
        .27f, .27f, .27f, .6f, 0f, .2f, 1.2f, 1f, 72f, 3.8f, 80f,
        600f, 1f, .8f, -.4f, .6f, -.8f, 1.5f, 1f, .8f, 1.15f,
        3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )
    val enabled = SoftGlassSettings(enabled = true).normalized()
    for (original in listOf(regular, dark)) {
        val saved = original.copyOf()
        check(tuneSoftGlass(original, enabled).contentEquals(original))
        check(tuneSoftGlass(original, enabled.copy(enabled = false, adjustments = mapOf("alpha" to 100))).contentEquals(original))
        val halfOpacity = tuneSoftGlass(original, enabled.copy(adjustments = mapOf("alpha" to -50)))
        check(abs(halfOpacity[14] - original[14] / 2f) < .00001f)
        check(halfOpacity.indices.filter { it != 14 }.all { halfOpacity[it] == original[it] })
        check(original.contentEquals(saved)) { "Do not mutate shared framework arrays" }
        for (definition in softGlassParameters) {
            for (delta in listOf(-100, -50, 0, 50, 200, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val tuned = tuneSoftGlass(original, enabled.copy(adjustments = mapOf(definition.key to delta)))
                check(tuned.all { it.isFinite() })
                if (delta != 0) check(tuned[definition.slot] in definition.bounds)
                check(tuned.indices.filter { it != definition.slot }.all { tuned[it] == original[it] })
            }
        }
    }
    val active = enabled.copy(adjustments = softGlassParameters.associate { it.key to 50 })
    check(tuneSoftGlass(FloatArray(42), active).all { it == 0f }) { "Clear must remain clear" }
    check(tuneSoftGlass(FloatArray(36) { 1f }, active).size == 36)
    check(tuneSoftGlass(FloatArray(42) { Float.NaN }, active).all { it.isNaN() })
    val extended = regular + floatArrayOf(12f, 34f)
    check(tuneSoftGlass(extended, active).takeLast(2) == listOf(12f, 34f))
    // Zero brightness defaults must respond smoothly, without HyperLight's full-unit jump.
    check(abs(tuneSoftGlass(regular, enabled.copy(adjustments = mapOf("brightness" to 1)))[6] - .01f) < .00001f)
    check(tuneSoftGlass(regular, active)[14] != tuneSoftGlass(dark, active)[14])
    check(softGlassBlur(80, 50) == 40 && softGlassBlur(30, 50) == 15)
    check(softGlassBlur(80, 0) == 0 && softGlassBlur(400, 200) == 500)
    check(softGlassBlur(700, 100) == 700 && softGlassBlur(0, 200) == 0)
    check(softGlassScale(.075f, 100) == .075f && softGlassScale(.075f, 0) == 0f)
    check(abs(softGlassScale(.075f, 200) - .15f) < .00001f)
    check(softGlassScale(Float.NaN, 50).isNaN())
    check(SoftGlassSettings(elementBlur = -1, backgroundBlur = 999, elementScale = -1, backgroundScale = 999).normalized().let {
        it.elementBlur == 0 && it.backgroundBlur == 200 && it.elementScale == 0 && it.backgroundScale == 200
    })
    check(softGlassParameters.map { it.key }.distinct().size == softGlassParameters.size)
    check(softGlassParameters.none { it.slot in setOf(8, 9, 11, 12, 13, 18, 36, 37, 38, 39, 40, 41) })
    println("Soft glass checks passed: stock defaults, light/dark recipes, clear, bounds, blur, scale, and shared-array safety")
}
