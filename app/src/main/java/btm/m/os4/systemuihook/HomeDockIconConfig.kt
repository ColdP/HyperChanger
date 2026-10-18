// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

internal data class HomeDockIconSettings(val enabled: Boolean = false, val offset: Int = 0) {
    fun normalized() = copy(offset = offset.coerceIn(-60, 30))
}
