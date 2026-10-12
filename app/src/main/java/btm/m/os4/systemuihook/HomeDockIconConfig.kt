// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

internal data class HomeDockIconSettings(val enabled: Boolean = false, val offset: Int = 0) {
    fun normalized() = copy(offset = offset.coerceIn(-60, 30))
}
