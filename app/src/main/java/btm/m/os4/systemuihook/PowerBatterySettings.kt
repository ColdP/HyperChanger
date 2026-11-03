// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal enum class PowerBatteryFeature(val key: String, val title: String) {
    TEMPERATURE("power_battery_temperature_v1", "显示电池温度数值"),
    HEALTH("power_battery_health_v1", "显示电池健康百分比"),
    MORE_INFO("power_battery_more_info_v1", "扩展电池信息"),
    CONSUMPTION("power_battery_consumption_v1", "显示完整耗电统计"),
    WIRELESS("power_battery_wireless_v1", "开放高速无线充电"),
    LOW_TEMPERATURE("power_battery_low_temperature_v1", "开放低温续航模式"),
    CHARGE_OPTIONS("power_battery_charge_options_v1", "开放更多充电与电池选项"),
}

internal data class PowerBatterySettings(val enabled: Set<PowerBatteryFeature> = emptySet()) {
    operator fun contains(feature: PowerBatteryFeature) = feature in enabled
    fun with(feature: PowerBatteryFeature, value: Boolean) = copy(
        enabled = if (value) enabled + feature else enabled - feature,
    )
}

internal fun readPowerBatterySettings(p: SharedPreferences) = PowerBatterySettings(
    PowerBatteryFeature.entries.filterTo(mutableSetOf()) { p.getBoolean(it.key, false) },
)

internal class PowerBatterySettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readPowerBatterySettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        val editor = local.edit()
        PowerBatteryFeature.entries.filterNot { local.contains(it.key) }.forEach {
            editor.putBoolean(it.key, remote.getBoolean(it.key, false))
        }
        editor.apply()
        settings = readPowerBatterySettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, feature: PowerBatteryFeature, value: Boolean) {
        settings = readPowerBatterySettings(local).with(feature, value)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        val editor = p.edit()
        PowerBatteryFeature.entries.forEach { editor.putBoolean(it.key, it in settings) }
        editor.apply()
    }
}

internal fun completePowerBatteryPreset(values: JSONObject) {
    PowerBatteryFeature.entries.filterNot { values.has(it.key) }.forEach {
        values.put(it.key, JSONObject().put("type", "boolean").put("value", false))
    }
}
