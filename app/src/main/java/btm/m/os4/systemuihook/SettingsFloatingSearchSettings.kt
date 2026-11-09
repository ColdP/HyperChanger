// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_SETTINGS_FLOATING_SEARCH = "settings_floating_search"
internal const val KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS = "settings_floating_search_parameters"
internal const val SETTINGS_SEARCH_MATERIAL_VERSION = 2

internal data class SettingsFloatingSearchSettings(
    val enabled: Boolean = false,
    val opacity: Int = 14,
    val blurRadius: Int = 40,
    val highlight: Boolean = false,
    val heightScale: Int = 0,
    val widthScale: Int = 0,
    val iconScale: Int = 0,
    val textScale: Int = 0,
    val iconTextSpacing: Int = 12,
) {
    fun normalized() = copy(opacity = opacity.coerceIn(0, 100), blurRadius = blurRadius.coerceIn(0, 120),
        heightScale = heightScale.coerceIn(-100, 100), widthScale = widthScale.coerceIn(-100, 100),
        iconScale = iconScale.coerceIn(-100, 100), textScale = textScale.coerceIn(-100, 100),
        iconTextSpacing = iconTextSpacing.coerceIn(0, 48))

    fun parameters(): String = JSONObject().apply {
        put("materialVersion", SETTINGS_SEARCH_MATERIAL_VERSION)
        put("blur", blurRadius.coerceIn(0, 120)); put("opacity", opacity.coerceIn(0, 100))
        put("highlight", highlight)
        // Explicitly disable legacy optical parameters in portable presets as well.
        put("refraction", 0); put("thickness", 0); put("pressBoost", 0); put("dispersion", false)
        put("heightScale", heightScale); put("widthScale", widthScale)
        put("iconScale", iconScale); put("textScale", textScale); put("iconTextSpacing", iconTextSpacing)
    }.toString()
}

internal fun readSettingsFloatingSearchSettings(p: SharedPreferences): SettingsFloatingSearchSettings {
    val value = runCatching { JSONObject(p.getString(KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS, "{}") ?: "{}") }
        .getOrElse { JSONObject() }
    return decodeSettingsFloatingSearchParameters(value).copy(enabled = p.getBoolean(KEY_SETTINGS_FLOATING_SEARCH, false))
}

private fun decodeSettingsFloatingSearchParameters(value: JSONObject): SettingsFloatingSearchSettings {
    val advanced = value.optInt("materialVersion", 0) == SETTINGS_SEARCH_MATERIAL_VERSION
    return SettingsFloatingSearchSettings(
        // Old shader values were recipe multipliers, not opacity percentages or blur pixels.
        opacity = if (advanced) value.optInt("opacity", 14) else 14,
        blurRadius = if (advanced) value.optInt("blur", 40) else 40,
        highlight = advanced && value.optBoolean("highlight", false),
        heightScale = value.optInt("heightScale", 0), widthScale = value.optInt("widthScale", 0),
        iconScale = value.optInt("iconScale", 0), textScale = value.optInt("textScale", 0),
        iconTextSpacing = value.optInt("iconTextSpacing", 12),
    ).normalized()
}

internal class SettingsFloatingSearchSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsFloatingSearchSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteSettings = readSettingsFloatingSearchSettings(remote)
        settings = readSettingsFloatingSearchSettings(local).let { value ->
            if (local.contains(KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS)) value.copy(
                enabled = if (local.contains(KEY_SETTINGS_FLOATING_SEARCH)) value.enabled else remoteSettings.enabled,
            ) else remoteSettings.copy(enabled = if (local.contains(KEY_SETTINGS_FLOATING_SEARCH)) value.enabled else remoteSettings.enabled)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsFloatingSearchSettings) -> SettingsFloatingSearchSettings) {
        settings = transform(readSettingsFloatingSearchSettings(local)).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SettingsFloatingSearchSettings) {
        p.edit().putBoolean(KEY_SETTINGS_FLOATING_SEARCH, value.enabled)
            .putString(KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS, value.parameters()).apply()
    }
}

internal fun completeSettingsFloatingSearchPreset(values: JSONObject) {
    if (!values.has(KEY_SETTINGS_FLOATING_SEARCH)) {
        values.put(KEY_SETTINGS_FLOATING_SEARCH, JSONObject().put("type", "boolean").put("value", false))
    }
    // Use the same migration/validation in export, preview, import and live preferences.
    val parameters = runCatching {
        JSONObject(values.optJSONObject(KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS)?.optString("value", "{}") ?: "{}")
    }.getOrElse { JSONObject() }
    values.put(KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS, JSONObject().put("type", "string")
        .put("value", decodeSettingsFloatingSearchParameters(parameters).parameters()))
}
