// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** Launcher-owned background. Its layout and transitions follow the hotseat. */
internal data class HomeNativeDockSettings(
    val enabled: Boolean = false,
    val glass: Boolean = true,
    val radius: Int = 30,
    val padding: Int = 25,
) {
    fun normalized() = copy(radius = radius.coerceIn(0, 60), padding = padding.coerceIn(0, 150))
}
