// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_VOLUME_PANEL_EXPANDED_CORNER = "volume_panel_expanded_corner_offset"

internal fun readVolumePanelExpandedCorner(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_VOLUME_PANEL_EXPANDED_CORNER, 0).coerceIn(-20, 30)

internal object VolumePanelExpandedCornerPreferences {
    fun write(preferences: SharedPreferences, offset: Int) {
        preferences.edit().putInt(KEY_VOLUME_PANEL_EXPANDED_CORNER, offset.coerceIn(-20, 30)).apply()
    }

    fun sync(local: SharedPreferences, remote: SharedPreferences): Int {
        val offset = readVolumePanelExpandedCorner(if (remote.contains(KEY_VOLUME_PANEL_EXPANDED_CORNER)) remote else local)
        write(local, offset)
        write(remote, offset)
        return offset
    }
}

internal class VolumePanelExpandedCornerSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var offset = readVolumePanelExpandedCorner(local); private set

    fun syncRemote(service: XposedService) {
        offset = VolumePanelExpandedCornerPreferences.sync(local, service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, value: Int) {
        offset = value.coerceIn(-20, 30)
        VolumePanelExpandedCornerPreferences.write(local, offset)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { VolumePanelExpandedCornerPreferences.write(it, offset) }
    }
}

internal fun volumePanelExpandedCornerDimensionDp(name: String?, originalDp: Float, offset: Int, enabled: Boolean): Float? =
    when (name) {
        "o3_miui_volume_bg_radius", "miui_volume_bg_radius_expanded", "miui_volume_blur_bg_radius",
        "volume_dialog_background_corner_radius", "volume_dialog_background_square_corner_radius" ->
            if (enabled && offset != 0) (originalDp + offset.coerceIn(-20, 30)).coerceAtLeast(0f) else null
        else -> null
    }
