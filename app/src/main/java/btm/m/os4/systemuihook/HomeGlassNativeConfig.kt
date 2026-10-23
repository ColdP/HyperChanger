// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** HGLS v1 order: glass blur, then the fourteen SoftGlass optical adjustments. */
internal data class HomeGlassNativeRecord(
    val enabled: Boolean = false,
    val values: List<Int> = listOf(100) + List(14) { if (it == 13) -100 else 0 },
) {
    fun isValid() = values.size == 15 && values[0] in 0..200 &&
        values.drop(1).withIndex().all { (index, value) -> value in if (index == 10) -200..100 else -100..200 }
}

internal data class HomeGlassNativeSettings(
    val desktop: HomeGlassNativeRecord = HomeGlassNativeRecord(),
    val dock: HomeGlassNativeRecord = HomeGlassNativeRecord(),
)
