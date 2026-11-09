// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_SETTINGS_HOMEPAGE_DUAL_PANE = "settings_homepage_dual_pane"
internal const val KEY_SETTINGS_UNLOCK_LANDSCAPE = "settings_unlock_landscape"
internal const val KEY_SETTINGS_DUAL_PANE_BALANCE = "settings_dual_pane_balance"
internal const val KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY = "settings_dual_pane_landscape_only"
internal const val DEFAULT_SETTINGS_DUAL_PANE_BALANCE = -20

// SplitType.ratio rejects 0 and 1; retain both panes even at slider endpoints.
internal fun settingsDualPaneRatio(balance: Int): Float =
    ((100 + balance.coerceIn(-100, 100)) / 200f).coerceIn(.01f, .99f)

internal data class SettingsLayoutSettings(
    val dualPane: Boolean = false,
    val unlockLandscape: Boolean = false,
    val balance: Int = DEFAULT_SETTINGS_DUAL_PANE_BALANCE,
    val landscapeOnly: Boolean = false,
) {
    fun normalized() = copy(balance = balance.coerceIn(-100, 100))
}

internal fun readSettingsLayoutSettings(p: SharedPreferences) = SettingsLayoutSettings(
    dualPane = p.getBoolean(KEY_SETTINGS_HOMEPAGE_DUAL_PANE, false),
    unlockLandscape = p.getBoolean(KEY_SETTINGS_UNLOCK_LANDSCAPE, false),
    balance = p.getInt(KEY_SETTINGS_DUAL_PANE_BALANCE, DEFAULT_SETTINGS_DUAL_PANE_BALANCE),
    landscapeOnly = p.getBoolean(KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY, false),
).normalized()

internal class SettingsLayoutSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsLayoutSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = SettingsLayoutSettings(
            dualPane = (if (local.contains(KEY_SETTINGS_HOMEPAGE_DUAL_PANE)) local else remote)
                .getBoolean(KEY_SETTINGS_HOMEPAGE_DUAL_PANE, false),
            unlockLandscape = (if (local.contains(KEY_SETTINGS_UNLOCK_LANDSCAPE)) local else remote)
                .getBoolean(KEY_SETTINGS_UNLOCK_LANDSCAPE, false),
            balance = (if (local.contains(KEY_SETTINGS_DUAL_PANE_BALANCE)) local else remote)
                .getInt(KEY_SETTINGS_DUAL_PANE_BALANCE, DEFAULT_SETTINGS_DUAL_PANE_BALANCE),
            landscapeOnly = (if (local.contains(KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY)) local else remote)
                .getBoolean(KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY, false),
        ).normalized()
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsLayoutSettings) -> SettingsLayoutSettings) {
        settings = transform(readSettingsLayoutSettings(local)).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SettingsLayoutSettings) {
        p.edit().putBoolean(KEY_SETTINGS_HOMEPAGE_DUAL_PANE, value.dualPane)
            .putBoolean(KEY_SETTINGS_UNLOCK_LANDSCAPE, value.unlockLandscape)
            .putInt(KEY_SETTINGS_DUAL_PANE_BALANCE, value.balance)
            .putBoolean(KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY, value.landscapeOnly).apply()
    }
}

internal fun completeSettingsLayoutPreset(values: JSONObject) {
    for (key in listOf(KEY_SETTINGS_HOMEPAGE_DUAL_PANE, KEY_SETTINGS_UNLOCK_LANDSCAPE,
        KEY_SETTINGS_DUAL_PANE_LANDSCAPE_ONLY)) {
        if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", false))
    }
    val balance = values.optJSONObject(KEY_SETTINGS_DUAL_PANE_BALANCE)
        ?.optInt("value", DEFAULT_SETTINGS_DUAL_PANE_BALANCE) ?: DEFAULT_SETTINGS_DUAL_PANE_BALANCE
    values.put(KEY_SETTINGS_DUAL_PANE_BALANCE, JSONObject().put("type", "int").put("value", balance.coerceIn(-100, 100)))
}
