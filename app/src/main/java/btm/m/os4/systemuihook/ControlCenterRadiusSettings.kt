// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal data class ControlCenterRadiusSettings(
    val enabled: Boolean = false,
    val topButtons: Int = 0,
    val mediaCard: Int = 0,
    val sliders: Int = 0,
    val smallButtons: Int = 0,
    val deviceCenter: Int = 0,
    val expandedPanel: Int = 0,
)

internal object ControlCenterRadiusPreferences {
    private const val PREFIX = "control_center_radius_"
    fun read(p: SharedPreferences) = ControlCenterRadiusSettings(
        enabled = p.getBoolean(PREFIX + "enabled", false),
        topButtons = p.getInt(PREFIX + "top_buttons", 0).coerceIn(-40, 80),
        mediaCard = p.getInt(PREFIX + "media_card", 0).coerceIn(-40, 80),
        sliders = p.getInt(PREFIX + "sliders", 0).coerceIn(-40, 80),
        smallButtons = p.getInt(PREFIX + "small_buttons", 0).coerceIn(-40, 80),
        deviceCenter = p.getInt(PREFIX + "device_center", 0).coerceIn(-40, 80),
        expandedPanel = p.getInt(PREFIX + "expanded_panel", 0).coerceIn(-40, 80),
    )
    fun write(p: SharedPreferences, s: ControlCenterRadiusSettings) {
        p.edit().putBoolean(PREFIX + "enabled", s.enabled)
            .putInt(PREFIX + "top_buttons", s.topButtons.coerceIn(-40, 80))
            .putInt(PREFIX + "media_card", s.mediaCard.coerceIn(-40, 80))
            .putInt(PREFIX + "sliders", s.sliders.coerceIn(-40, 80))
            .putInt(PREFIX + "small_buttons", s.smallButtons.coerceIn(-40, 80))
            .putInt(PREFIX + "device_center", s.deviceCenter.coerceIn(-40, 80))
            .putInt(PREFIX + "expanded_panel", s.expandedPanel.coerceIn(-40, 80)).apply()
    }
    fun sync(local: SharedPreferences, remote: SharedPreferences): ControlCenterRadiusSettings {
        val keys = listOf("enabled", "top_buttons", "media_card", "sliders", "small_buttons", "device_center", "expanded_panel")
        val editor = local.edit()
        keys.forEach { key ->
            val full = PREFIX + key
            if (remote.contains(full)) {
                if (key == "enabled") editor.putBoolean(full, remote.getBoolean(full, false))
                else editor.putInt(full, remote.getInt(full, 0))
            }
        }
        editor.apply()
        return read(local).also { write(remote, it) }
    }
}

internal class ControlCenterRadiusSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = ControlCenterRadiusPreferences.read(local); private set
    fun syncRemote(service: XposedService) { settings = ControlCenterRadiusPreferences.sync(local, service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)) }
    fun update(service: XposedService?, value: ControlCenterRadiusSettings) {
        ControlCenterRadiusPreferences.write(local, value)
        settings = ControlCenterRadiusPreferences.read(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { ControlCenterRadiusPreferences.write(it, settings) }
    }
}

internal fun adjustedControlCenterRadius(originalPx: Float, offsetDp: Int, density: Float, enabled: Boolean): Float =
    if (!enabled || offsetDp == 0) originalPx else (originalPx + offsetDp.coerceIn(-40, 80) * density).coerceAtLeast(0f)

/** Setter/getter cycles must never add the offset twice to a radius stored by the system. */
internal class ControlCenterRadiusBaseline {
    var originalRadius: Float? = null; private set
    private var applied: Float? = null
    fun remember(original: Float, output: Float) { originalRadius = original; applied = output }
    fun resolve(input: Float, offsetDp: Int, density: Float, enabled: Boolean): Float {
        val baseline = if (input == applied) originalRadius ?: input else input
        originalRadius = baseline
        return adjustedControlCenterRadius(baseline, offsetDp, density, enabled).also { applied = it }
    }
}

internal class ControlCenterRadiusRuntimeSettings(private val preferences: SharedPreferences) {
    @Volatile var settings = ControlCenterRadiusPreferences.read(preferences); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("control_center_radius_")) settings = ControlCenterRadiusPreferences.read(preferences)
    }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}
