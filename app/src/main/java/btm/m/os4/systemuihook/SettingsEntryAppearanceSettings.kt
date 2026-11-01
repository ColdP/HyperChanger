// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlin.math.roundToInt

internal const val KEY_SETTINGS_ENTRY_ICON_SCALE_ENABLED = "settings_entry_icon_scale_enabled"
internal const val KEY_SETTINGS_ENTRY_ICON_SCALE = "settings_entry_icon_scale"
internal const val KEY_SETTINGS_ENTRY_TEXT_SCALE_ENABLED = "settings_entry_text_scale_enabled"
internal const val KEY_SETTINGS_ENTRY_TEXT_SCALE = "settings_entry_text_scale"
internal const val KEY_SETTINGS_ENTRY_ROW_SPACING_ENABLED = "settings_entry_row_spacing_enabled"
internal const val KEY_SETTINGS_ENTRY_ROW_SPACING = "settings_entry_row_spacing"

internal const val SETTINGS_ENTRY_APPEARANCE_DEFAULT = 0f
internal const val SETTINGS_ENTRY_APPEARANCE_MIN = -100f
internal const val SETTINGS_ENTRY_APPEARANCE_MAX = 100f

internal data class SettingsEntryAppearanceSettings(
    val iconScaleEnabled: Boolean = false,
    val iconScale: Float = SETTINGS_ENTRY_APPEARANCE_DEFAULT,
    val textScaleEnabled: Boolean = false,
    val textScale: Float = SETTINGS_ENTRY_APPEARANCE_DEFAULT,
    val rowSpacingEnabled: Boolean = false,
    val rowSpacing: Float = SETTINGS_ENTRY_APPEARANCE_DEFAULT,
) {
    val anyEnabled: Boolean get() = iconScaleEnabled || textScaleEnabled || rowSpacingEnabled

    fun normalized() = copy(
        iconScale = iconScale.normalizeSettingsEntryAppearancePercent(),
        textScale = textScale.normalizeSettingsEntryAppearancePercent(),
        rowSpacing = rowSpacing.normalizeSettingsEntryAppearancePercent(),
    )
}

private fun Float.normalizeSettingsEntryAppearancePercent(): Float =
    if (isFinite()) {
        (coerceIn(SETTINGS_ENTRY_APPEARANCE_MIN, SETTINGS_ENTRY_APPEARANCE_MAX) * 100f)
            .roundToInt() / 100f
    } else SETTINGS_ENTRY_APPEARANCE_DEFAULT

internal fun readSettingsEntryAppearanceSettings(p: SharedPreferences) =
    SettingsEntryAppearanceSettings(
        iconScaleEnabled = p.getBoolean(KEY_SETTINGS_ENTRY_ICON_SCALE_ENABLED, false),
        iconScale = p.getFloat(KEY_SETTINGS_ENTRY_ICON_SCALE, SETTINGS_ENTRY_APPEARANCE_DEFAULT),
        textScaleEnabled = p.getBoolean(KEY_SETTINGS_ENTRY_TEXT_SCALE_ENABLED, false),
        textScale = p.getFloat(KEY_SETTINGS_ENTRY_TEXT_SCALE, SETTINGS_ENTRY_APPEARANCE_DEFAULT),
        rowSpacingEnabled = p.getBoolean(KEY_SETTINGS_ENTRY_ROW_SPACING_ENABLED, false),
        rowSpacing = p.getFloat(KEY_SETTINGS_ENTRY_ROW_SPACING, SETTINGS_ENTRY_APPEARANCE_DEFAULT),
    ).normalized()

internal class SettingsEntryAppearanceSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsEntryAppearanceSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val hasRemoteSettings = remote.contains(KEY_SETTINGS_ENTRY_ICON_SCALE_ENABLED) ||
            remote.contains(KEY_SETTINGS_ENTRY_ICON_SCALE) ||
            remote.contains(KEY_SETTINGS_ENTRY_TEXT_SCALE_ENABLED) ||
            remote.contains(KEY_SETTINGS_ENTRY_TEXT_SCALE) ||
            remote.contains(KEY_SETTINGS_ENTRY_ROW_SPACING_ENABLED) ||
            remote.contains(KEY_SETTINGS_ENTRY_ROW_SPACING)
        settings = (if (hasRemoteSettings) remote else local)
            .let(::readSettingsEntryAppearanceSettings)
        write(local, settings)
        write(remote, settings)
    }

    fun update(
        service: XposedService?,
        transform: (SettingsEntryAppearanceSettings) -> SettingsEntryAppearanceSettings,
    ) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: SettingsEntryAppearanceSettings) {
        p.edit()
            .putBoolean(KEY_SETTINGS_ENTRY_ICON_SCALE_ENABLED, value.iconScaleEnabled)
            .putFloat(KEY_SETTINGS_ENTRY_ICON_SCALE, value.iconScale)
            .putBoolean(KEY_SETTINGS_ENTRY_TEXT_SCALE_ENABLED, value.textScaleEnabled)
            .putFloat(KEY_SETTINGS_ENTRY_TEXT_SCALE, value.textScale)
            .putBoolean(KEY_SETTINGS_ENTRY_ROW_SPACING_ENABLED, value.rowSpacingEnabled)
            .putFloat(KEY_SETTINGS_ENTRY_ROW_SPACING, value.rowSpacing)
            .apply()
    }
}
