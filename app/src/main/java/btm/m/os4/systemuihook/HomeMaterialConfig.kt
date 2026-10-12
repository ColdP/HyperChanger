// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

/** Percent of each launcher's stock glass parameter; 100 preserves its theme. */
internal data class HomeMaterialSettings(
    val enabled: Boolean = false,
    val blur: Int = 100,
    val refraction: Int = 100,
    val softLight: Int = 100,
    val saturation: Int = 100,
    val brightness: Int = 100,
    val opacity: Int = 100,
    val dockIcon: HomeDockIconSettings = HomeDockIconSettings(),
) {
    fun normalized() = copy(
        blur = blur.coerceIn(0, 200), refraction = refraction.coerceIn(0, 200),
        softLight = softLight.coerceIn(0, 200), saturation = saturation.coerceIn(0, 200),
        brightness = brightness.coerceIn(0, 200), opacity = opacity.coerceIn(0, 100), dockIcon = dockIcon.normalized(),
    )
}
