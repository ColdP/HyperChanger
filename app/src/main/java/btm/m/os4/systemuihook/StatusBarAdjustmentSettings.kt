// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_STATUS_BAR_ICON_SIZE_PERCENT = "status_bar_icon_size_percent"
internal const val KEY_STATUS_BAR_BATTERY_PERCENT_MODE = "status_bar_battery_percent_mode"
internal const val KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE = "control_center_battery_percent_mode"
internal const val STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT = 0f
internal val STATUS_BAR_ICON_SIZE_PERCENT_RANGE = -100f..100f

internal data class StatusBarAdjustmentSettings(
    val iconSizePercent: Float = STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT,
    val statusBarBatteryPercentMode: Int = 0,
    val controlCenterBatteryPercentMode: Int = 0,
)

internal fun readStatusBarAdjustmentSettings(preferences: SharedPreferences) = StatusBarAdjustmentSettings(
    iconSizePercent = preferences.getFloat(
        KEY_STATUS_BAR_ICON_SIZE_PERCENT,
        STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT,
    ).coerceIn(STATUS_BAR_ICON_SIZE_PERCENT_RANGE),
    statusBarBatteryPercentMode = preferences.getInt(KEY_STATUS_BAR_BATTERY_PERCENT_MODE, 0).coerceIn(0, 3),
    controlCenterBatteryPercentMode = preferences.getInt(KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE, 0).coerceIn(0, 3),
)

internal class StatusBarAdjustmentSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readStatusBarAdjustmentSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_STATUS_BAR_ICON_SIZE_PERCENT) ||
            remote.contains(KEY_STATUS_BAR_BATTERY_PERCENT_MODE) ||
            remote.contains(KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE)
        ) readStatusBarAdjustmentSettings(remote) else readStatusBarAdjustmentSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (StatusBarAdjustmentSettings) -> StatusBarAdjustmentSettings) {
        // Multiple pages own a store; merge with the latest local values so changing
        // one option cannot overwrite another page's icon size or battery mode.
        settings = transform(readStatusBarAdjustmentSettings(local)).let {
            it.copy(
                iconSizePercent = it.iconSizePercent.coerceIn(STATUS_BAR_ICON_SIZE_PERCENT_RANGE),
                statusBarBatteryPercentMode = it.statusBarBatteryPercentMode.coerceIn(0, 3),
                controlCenterBatteryPercentMode = it.controlCenterBatteryPercentMode.coerceIn(0, 3),
            )
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putFloat(KEY_STATUS_BAR_ICON_SIZE_PERCENT, settings.iconSizePercent)
            .putInt(KEY_STATUS_BAR_BATTERY_PERCENT_MODE, settings.statusBarBatteryPercentMode)
            .putInt(KEY_CONTROL_CENTER_BATTERY_PERCENT_MODE, settings.controlCenterBatteryPercentMode)
            .apply()
    }
}
