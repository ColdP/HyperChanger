// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService

internal const val KEY_APP_MONET_ENABLED = "app_theme_monet_enabled"
internal const val KEY_APP_COLOR_THEME = "app_theme_color_theme"
internal const val KEY_APP_CUSTOM_COLOR = "app_theme_custom_color"
internal const val APP_COLOR_SYSTEM = "system"
internal const val APP_COLOR_CUSTOM = "custom"
internal const val APP_COLOR_MONO = "mono"
private const val DEFAULT_APP_CUSTOM_COLOR = 0xFF6376B5.toInt()

internal data class AppColorPreset(val id: String, val labelKey: String, val name: String, val seed: Int)

// Each seed generates a complete Miuix tonal palette for both light and dark modes.
internal val appColorPresets = listOf(
    AppColorPreset("mist_blue", "theme_mist_blue", "雾岚蓝", 0xFF557FA8.toInt()),
    AppColorPreset("jade", "theme_jade", "青玉", 0xFF448575.toInt()),
    AppColorPreset("moss", "theme_moss", "苔庭绿", 0xFF71854C.toInt()),
    AppColorPreset("amber", "theme_amber", "暖琥珀", 0xFFB0833D.toInt()),
    AppColorPreset("apricot", "theme_apricot", "杏霞", 0xFFC17C5B.toInt()),
    AppColorPreset("rose", "theme_rose", "玫瑰砂", 0xFFAD6E85.toInt()),
    AppColorPreset("wisteria", "theme_wisteria", "藤烟紫", 0xFF8973B0.toInt()),
    AppColorPreset("indigo", "theme_indigo", "暮靛", 0xFF6376B5.toInt()),
    AppColorPreset("slate", "theme_slate", "岩雾", 0xFF73858A.toInt()),
    AppColorPreset("mono", "theme_mono", "黑白极简", 0xFF777777.toInt()),
)

internal data class AppThemeSettings(
    val monetEnabled: Boolean = false,
    val colorTheme: String = APP_COLOR_SYSTEM,
    val customColor: Int = DEFAULT_APP_CUSTOM_COLOR,
) {
    fun normalized() = copy(
        colorTheme = colorTheme.takeIf { it == APP_COLOR_SYSTEM || it == APP_COLOR_CUSTOM || appColorPresets.any { preset -> preset.id == it } }
            ?: APP_COLOR_SYSTEM,
        customColor = customColor or 0xFF000000.toInt(),
    )

    fun seedColor(previewColor: Int?): Int? = when (colorTheme) {
        APP_COLOR_CUSTOM -> previewColor ?: customColor
        else -> appColorPresets.firstOrNull { it.id == colorTheme }?.seed
    }
}

internal fun readAppThemeSettings(p: SharedPreferences) = AppThemeSettings(
    monetEnabled = p.getBoolean(KEY_APP_MONET_ENABLED, false),
    colorTheme = p.getString(KEY_APP_COLOR_THEME, APP_COLOR_SYSTEM) ?: APP_COLOR_SYSTEM,
    customColor = p.getInt(KEY_APP_CUSTOM_COLOR, DEFAULT_APP_CUSTOM_COLOR),
).normalized()

internal object AppThemePreferences {
    fun write(p: SharedPreferences, value: AppThemeSettings) {
        val normalized = value.normalized()
        p.edit()
            .putBoolean(KEY_APP_MONET_ENABLED, normalized.monetEnabled)
            .putString(KEY_APP_COLOR_THEME, normalized.colorTheme)
            .putInt(KEY_APP_CUSTOM_COLOR, normalized.customColor)
            .apply()
    }

    fun sync(local: SharedPreferences, remote: SharedPreferences): AppThemeSettings {
        val localValue = readAppThemeSettings(local)
        val remoteValue = readAppThemeSettings(remote)
        return AppThemeSettings(
            monetEnabled = if (remote.contains(KEY_APP_MONET_ENABLED)) remoteValue.monetEnabled else localValue.monetEnabled,
            colorTheme = if (remote.contains(KEY_APP_COLOR_THEME)) remoteValue.colorTheme else localValue.colorTheme,
            customColor = if (remote.contains(KEY_APP_CUSTOM_COLOR)) remoteValue.customColor else localValue.customColor,
        ).normalized().also { write(local, it); write(remote, it) }
    }
}

/** App appearance stays separate from HookSettings and its serialization. */
internal class AppThemeSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    val monetPalette = AppMonetPaletteSettingsStore(context)
    val monetColorSpec = AppMonetColorSpecSettingsStore(context)
    val monetNotice = AppMonetNoticeSettingsStore(context)
    var settings by mutableStateOf(readAppThemeSettings(local)); private set
    // Dialog previews are transient; cancel and page disposal restore the saved color.
    var previewColor by mutableStateOf<Int?>(null)

    fun observe(): () -> Unit {
        val disposePalette = monetPalette.observe()
        val disposeColorSpec = monetColorSpec.observe()
        val disposeNotice = monetNotice.observe()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(KEY_APP_MONET_ENABLED, KEY_APP_COLOR_THEME, KEY_APP_CUSTOM_COLOR)) {
                settings = readAppThemeSettings(local)
            }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        settings = readAppThemeSettings(local)
        return {
            local.unregisterOnSharedPreferenceChangeListener(listener)
            disposePalette()
            disposeColorSpec()
            disposeNotice()
        }
    }

    fun syncRemote(service: XposedService) {
        settings = AppThemePreferences.sync(local, service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
        // Read the legacy combined id before palette synchronization normalizes it.
        monetColorSpec.syncRemote(service)
        monetPalette.syncRemote(service)
        monetNotice.syncRemote(service)
    }

    fun update(service: XposedService?, value: AppThemeSettings) {
        val normalized = value.normalized()
        AppThemePreferences.write(local, normalized)
        settings = normalized
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { AppThemePreferences.write(it, normalized) }
    }
}
