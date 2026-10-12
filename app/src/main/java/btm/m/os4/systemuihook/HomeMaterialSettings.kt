// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_MATERIAL_ENABLED = "home_material_enabled"
internal const val KEY_HOME_MATERIAL_STYLE = "home_material_style"

internal data class HomeMaterialSettings(
    val enabled: Boolean = false,
    /** 0 = soft glass, 1 = advanced material. */
    val style: Int = 0,
    val glassColor: Int = 0xCCFFFFFF.toInt(),
    val glassOpacity: Int = 85,
    val glassBackdropBlur: Int = 40,
    val glassBlur: Int = 30,
    val glassSoftLight: Int = 50,
    val glassHighlight: Boolean = true,
    val advancedColor: Int = 0x24FFFFFF,
    val advancedOpacity: Int = 14,
    val advancedBlur: Int = 40,
    val advancedHighlight: Boolean = true,
)

internal fun readHomeMaterialSettings(prefs: SharedPreferences) = HomeMaterialSettings(
    enabled = prefs.getBoolean(KEY_HOME_MATERIAL_ENABLED, false),
    style = prefs.getInt(KEY_HOME_MATERIAL_STYLE, 0).coerceIn(0, 1),
    glassColor = prefs.getInt("home_material_glass_color", 0xCCFFFFFF.toInt()),
    glassOpacity = prefs.getInt("home_material_glass_opacity", 85).coerceIn(0, 100),
    glassBackdropBlur = prefs.getInt("home_material_glass_backdrop_blur", 40).coerceIn(0, 100),
    glassBlur = prefs.getInt("home_material_glass_blur", 30).coerceIn(0, 100),
    glassSoftLight = prefs.getInt("home_material_glass_soft_light", 50).coerceIn(0, 100),
    glassHighlight = prefs.getBoolean("home_material_glass_highlight", true),
    advancedColor = prefs.getInt("home_material_advanced_color", 0x24FFFFFF),
    advancedOpacity = prefs.getInt("home_material_advanced_opacity", 14).coerceIn(0, 100),
    advancedBlur = prefs.getInt("home_material_advanced_blur", 40).coerceIn(0, 100),
    advancedHighlight = prefs.getBoolean("home_material_advanced_highlight", true),
)

internal class HomeMaterialSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readHomeMaterialSettings(local); private set
    fun reload() { settings = readHomeMaterialSettings(local) }
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_HOME_MATERIAL_ENABLED)) settings = readHomeMaterialSettings(remote)
        write(local, settings); write(remote, settings)
        stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), readHomeFolderSettings(local), settings)
    }
    fun update(service: XposedService?, transform: (HomeMaterialSettings) -> HomeMaterialSettings): Boolean {
        settings = transform(settings).normalized()
        write(local, settings); service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), readHomeFolderSettings(local), settings)
    }
    private fun write(p: SharedPreferences, s: HomeMaterialSettings) = p.edit()
        .putBoolean(KEY_HOME_MATERIAL_ENABLED, s.enabled).putInt(KEY_HOME_MATERIAL_STYLE, s.style)
        .putInt("home_material_glass_color", s.glassColor).putInt("home_material_glass_opacity", s.glassOpacity)
        .putInt("home_material_glass_backdrop_blur", s.glassBackdropBlur).putInt("home_material_glass_blur", s.glassBlur)
        .putInt("home_material_glass_soft_light", s.glassSoftLight).putBoolean("home_material_glass_highlight", s.glassHighlight)
        .putInt("home_material_advanced_color", s.advancedColor).putInt("home_material_advanced_opacity", s.advancedOpacity)
        .putInt("home_material_advanced_blur", s.advancedBlur).putBoolean("home_material_advanced_highlight", s.advancedHighlight).apply()
}

private fun HomeMaterialSettings.normalized() = copy(
    style = style.coerceIn(0, 1), glassOpacity = glassOpacity.coerceIn(0, 100), glassBackdropBlur = glassBackdropBlur.coerceIn(0, 100),
    glassBlur = glassBlur.coerceIn(0, 100), glassSoftLight = glassSoftLight.coerceIn(0, 100), advancedOpacity = advancedOpacity.coerceIn(0, 100), advancedBlur = advancedBlur.coerceIn(0, 100),
)
