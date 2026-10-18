// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences

internal const val KEY_HOME_DOCK_ICON_ENABLED = "home_dock_icon_offset_enabled"
private const val KEY_HOME_DOCK_ICON_OFFSET = "home_dock_icon_offset"

internal fun readHomeDockIconSettings(p: SharedPreferences) = HomeDockIconSettings(
    enabled = p.getBoolean(KEY_HOME_DOCK_ICON_ENABLED, false),
    offset = p.getInt(KEY_HOME_DOCK_ICON_OFFSET, 0),
).normalized()

internal fun writeHomeDockIconSettings(e: SharedPreferences.Editor, s: HomeDockIconSettings) {
    e.putBoolean(KEY_HOME_DOCK_ICON_ENABLED, s.enabled).putInt(KEY_HOME_DOCK_ICON_OFFSET, s.offset)
}
