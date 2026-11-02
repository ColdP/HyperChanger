// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.runtime.Composable

@Composable
internal fun LockscreenCapsuleAutoCollapsePreference() {
    LockscreenCapsuleNotificationPreference(automatic = true)
}
