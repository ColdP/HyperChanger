// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal data class VolumePanelLayoutSettings(
    val sizeEnabled: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val hideRinger: Boolean = false,
    val hideExpand: Boolean = false,
    val cornerEnabled: Boolean = false,
    val corner: Int = 0,
    val longPressExpand: Boolean = false,
) {
    val longPressExpansionEnabled: Boolean get() = hideExpand && longPressExpand
}

internal object VolumePanelLayoutPreferences {
    private const val PREFIX = "volume_panel_layout_"
    private val booleans = listOf("size_enabled", "hide_ringer", "hide_expand", "corner_enabled", "long_press_expand")
    private val integers = listOf("width", "height", "corner")
    fun read(p: SharedPreferences) = VolumePanelLayoutSettings(
        p.getBoolean(PREFIX + "size_enabled", false),
        p.getInt(PREFIX + "width", 0).coerceIn(-50, 72),
        p.getInt(PREFIX + "height", 0).coerceIn(-64, 96),
        p.getBoolean(PREFIX + "hide_ringer", false),
        p.getBoolean(PREFIX + "hide_expand", false),
        p.getBoolean(PREFIX + "corner_enabled", false),
        p.getInt(PREFIX + "corner", 0).coerceIn(-40, 80),
        p.getBoolean(PREFIX + "long_press_expand", false),
    )
    fun write(p: SharedPreferences, s: VolumePanelLayoutSettings) {
        p.edit().putBoolean(PREFIX + "size_enabled", s.sizeEnabled)
            .putInt(PREFIX + "width", s.width.coerceIn(-50, 72))
            .putInt(PREFIX + "height", s.height.coerceIn(-64, 96))
            .putBoolean(PREFIX + "hide_ringer", s.hideRinger)
            .putBoolean(PREFIX + "hide_expand", s.hideExpand)
            .putBoolean(PREFIX + "long_press_expand", s.longPressExpand)
            .putBoolean(PREFIX + "corner_enabled", s.cornerEnabled)
            .putInt(PREFIX + "corner", s.corner.coerceIn(-40, 80)).apply()
    }
    fun sync(local: SharedPreferences, remote: SharedPreferences): VolumePanelLayoutSettings {
        val edit = local.edit()
        booleans.forEach { if (remote.contains(PREFIX + it)) edit.putBoolean(PREFIX + it, remote.getBoolean(PREFIX + it, false)) }
        integers.forEach { if (remote.contains(PREFIX + it)) edit.putInt(PREFIX + it, remote.getInt(PREFIX + it, 0)) }
        edit.apply()
        return read(local).also { write(remote, it) }
    }
}

internal class VolumePanelLayoutSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = VolumePanelLayoutPreferences.read(local); private set
    fun syncRemote(service: XposedService) {
        settings = VolumePanelLayoutPreferences.sync(local, service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }
    fun update(service: XposedService?, value: VolumePanelLayoutSettings) {
        VolumePanelLayoutPreferences.write(local, value)
        settings = VolumePanelLayoutPreferences.read(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { VolumePanelLayoutPreferences.write(it, settings) }
    }
}

/** Offsets always start from the system resource, never from a previously adjusted View. */
internal fun volumePanelLayoutDimensionDp(name: String?, originalDp: Float, settings: VolumePanelLayoutSettings): Float? {
    val offset = when (name) {
        // Only the collapsed physical-key panel uses these resources. Expanded/CC
        // widths (including single-notification columns) must retain stock dimensions.
        "miui_volume_column_width", "flip_tiny_miui_volume_column_width",
        "o3_miui_ringer_btn_width" ->
            if (settings.sizeEnabled) settings.width else 0
        "miui_volume_column_height", "flip_tiny_miui_volume_column_height" ->
            if (settings.sizeEnabled) settings.height else 0
        "o3_miui_volume_radius", "o3_miui_tiny_volume_radius", "miui_volume_bg_radius",
        "o3_miui_volume_bg_radius", "miui_volume_bg_radius_expanded", "miui_volume_blur_bg_radius",
        "volume_dialog_background_corner_radius", "volume_dialog_background_square_corner_radius" ->
            return if (settings.cornerEnabled && settings.corner != 0) (originalDp + settings.corner).coerceAtLeast(0f) else null
        else -> 0
    }
    if (offset != 0) return (originalDp + offset).coerceAtLeast(1f)
    return null
}
