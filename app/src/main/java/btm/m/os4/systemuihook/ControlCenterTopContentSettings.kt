// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_CONTROL_CENTER_TOP_CONTENT_MODE = "control_center_top_content_mode"
internal const val KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE = "control_center_signal_icon_style"
internal const val KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL = "control_center_signal_sim1_label"
internal const val KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL = "control_center_signal_sim2_label"
internal const val KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT = "control_center_signal_battery_percent_left"

internal const val CONTROL_CENTER_TOP_CONTENT_DEFAULT = 0
internal const val CONTROL_CENTER_TOP_CONTENT_CLOCK = 1
internal const val CONTROL_CENTER_TOP_CONTENT_SIGNAL = 2

internal const val CONTROL_CENTER_SIGNAL_ICON_FOLLOW_STATUS_BAR = 0
internal const val CONTROL_CENTER_SIGNAL_ICON_IOS = 1
internal const val CONTROL_CENTER_SIGNAL_ICON_DOTS = 2

private const val MAX_SIM_BADGE_LABEL_LENGTH = 8

data class ControlCenterTopContentSettings(
    // 0: system default, 1: big clock above date, 2: iOS-style signal header.
    val mode: Int = CONTROL_CENTER_TOP_CONTENT_DEFAULT,
    // 0: follow status bar, 1: iOS bars, 2: iOS 10 dots.
    val signalIconStyle: Int = CONTROL_CENTER_SIGNAL_ICON_IOS,
    // Custom dual-SIM badge labels; blank falls back to "1" / "2".
    val sim1Label: String = "",
    val sim2Label: String = "",
    // Move Control Center's independent battery percentage to the left of the battery icon.
    val batteryPercentLeft: Boolean = false,
)

internal fun normalizeControlCenterTopContentMode(mode: Int): Int =
    mode.coerceIn(CONTROL_CENTER_TOP_CONTENT_DEFAULT, CONTROL_CENTER_TOP_CONTENT_SIGNAL)

internal fun normalizeControlCenterSignalIconStyle(style: Int): Int =
    style.coerceIn(CONTROL_CENTER_SIGNAL_ICON_FOLLOW_STATUS_BAR, CONTROL_CENTER_SIGNAL_ICON_DOTS)

internal fun normalizeControlCenterSimBadgeLabel(label: String): String =
    label.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_SIM_BADGE_LABEL_LENGTH)

internal fun normalizeControlCenterTopContentSettings(
    settings: ControlCenterTopContentSettings,
): ControlCenterTopContentSettings = settings.copy(
    mode = normalizeControlCenterTopContentMode(settings.mode),
    signalIconStyle = normalizeControlCenterSignalIconStyle(settings.signalIconStyle),
    sim1Label = normalizeControlCenterSimBadgeLabel(settings.sim1Label),
    sim2Label = normalizeControlCenterSimBadgeLabel(settings.sim2Label),
)

internal fun readControlCenterTopContentSettings(preferences: SharedPreferences): ControlCenterTopContentSettings {
    val mode = if (preferences.contains(KEY_CONTROL_CENTER_TOP_CONTENT_MODE)) {
        preferences.getInt(KEY_CONTROL_CENTER_TOP_CONTENT_MODE, CONTROL_CENTER_TOP_CONTENT_DEFAULT)
    } else if (preferences.getBoolean(KEY_CONTROL_CENTER_CLOCK_ENABLED, false)) {
        CONTROL_CENTER_TOP_CONTENT_CLOCK
    } else {
        CONTROL_CENTER_TOP_CONTENT_DEFAULT
    }
    return normalizeControlCenterTopContentSettings(
        ControlCenterTopContentSettings(
            mode = mode,
            signalIconStyle = preferences.getInt(
                KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE,
                CONTROL_CENTER_SIGNAL_ICON_IOS,
            ),
            sim1Label = preferences.getString(KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL, "").orEmpty(),
            sim2Label = preferences.getString(KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL, "").orEmpty(),
            batteryPercentLeft = preferences.getBoolean(KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT, false),
        ),
    )
}

/** Keep presets complete and migrate legacy big-clock toggles to the dropdown mode. */
internal fun completeControlCenterTopContentPreset(values: JSONObject) {
    val mode = if (values.has(KEY_CONTROL_CENTER_TOP_CONTENT_MODE)) {
        normalizeControlCenterTopContentMode(
            values.optJSONObject(KEY_CONTROL_CENTER_TOP_CONTENT_MODE)
                ?.optInt("value", CONTROL_CENTER_TOP_CONTENT_DEFAULT)
                ?: CONTROL_CENTER_TOP_CONTENT_DEFAULT,
        )
    } else {
        val legacyClockEnabled = values.optJSONObject(KEY_CONTROL_CENTER_CLOCK_ENABLED)
            ?.optBoolean("value", false)
            ?: false
        if (legacyClockEnabled) CONTROL_CENTER_TOP_CONTENT_CLOCK else CONTROL_CENTER_TOP_CONTENT_DEFAULT
    }
    val iconStyle = normalizeControlCenterSignalIconStyle(
        values.optJSONObject(KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE)
            ?.optInt("value", CONTROL_CENTER_SIGNAL_ICON_IOS)
            ?: CONTROL_CENTER_SIGNAL_ICON_IOS,
    )
    val sim1Label = normalizeControlCenterSimBadgeLabel(
        values.optJSONObject(KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL)?.optString("value").orEmpty(),
    )
    val sim2Label = normalizeControlCenterSimBadgeLabel(
        values.optJSONObject(KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL)?.optString("value").orEmpty(),
    )
    val batteryPercentLeft = values.optJSONObject(KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT)
        ?.optBoolean("value", false)
        ?: false

    values.put(KEY_CONTROL_CENTER_TOP_CONTENT_MODE, JSONObject().put("type", "int").put("value", mode))
    values.put(
        KEY_CONTROL_CENTER_CLOCK_ENABLED,
        JSONObject().put("type", "boolean").put("value", mode == CONTROL_CENTER_TOP_CONTENT_CLOCK),
    )
    values.put(KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE, JSONObject().put("type", "int").put("value", iconStyle))
    values.put(KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL, JSONObject().put("type", "string").put("value", sim1Label))
    values.put(KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL, JSONObject().put("type", "string").put("value", sim2Label))
    values.put(
        KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT,
        JSONObject().put("type", "boolean").put("value", batteryPercentLeft),
    )
}

/** Kept outside HookSettings, including offline edits and remote synchronization. */
class ControlCenterTopContentSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ControlCenterTopContentSettings = readControlCenterTopContentSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterTopContentSettings(local)
        write(local, settings)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(
        service: XposedService?,
        transform: (ControlCenterTopContentSettings) -> ControlCenterTopContentSettings,
    ) {
        settings = normalizeControlCenterTopContentSettings(transform(settings))
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(preferences: SharedPreferences, value: ControlCenterTopContentSettings) {
        preferences.edit()
            .putInt(KEY_CONTROL_CENTER_TOP_CONTENT_MODE, value.mode)
            .putBoolean(KEY_CONTROL_CENTER_CLOCK_ENABLED, value.mode == CONTROL_CENTER_TOP_CONTENT_CLOCK)
            .putInt(KEY_CONTROL_CENTER_SIGNAL_ICON_STYLE, value.signalIconStyle)
            .putString(KEY_CONTROL_CENTER_SIGNAL_SIM1_LABEL, value.sim1Label)
            .putString(KEY_CONTROL_CENTER_SIGNAL_SIM2_LABEL, value.sim2Label)
            .putBoolean(KEY_CONTROL_CENTER_SIGNAL_BATTERY_PERCENT_LEFT, value.batteryPercentLeft)
            .apply()
    }
}
