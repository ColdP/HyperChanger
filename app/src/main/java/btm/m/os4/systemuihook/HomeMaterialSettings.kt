// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_MATERIAL_ENABLED = "home_material_enabled"

internal fun readHomeMaterialSettings(prefs: SharedPreferences) = HomeMaterialSettings(
    enabled = prefs.getBoolean(KEY_HOME_MATERIAL_ENABLED, false),
    blur = prefs.getInt("home_glass_blur_factor", 100),
    refraction = prefs.getInt("home_glass_refraction_factor", 100),
    softLight = prefs.getInt("home_glass_soft_light_factor", 100),
    saturation = prefs.getInt("home_glass_saturation_factor", 100),
    brightness = prefs.getInt("home_glass_brightness_factor", 100),
    dockIcon = readHomeDockIconSettings(prefs),
).normalized()

internal class HomeMaterialSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readHomeMaterialSettings(local); private set
    fun reload() { settings = readHomeMaterialSettings(local) }
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_HOME_MATERIAL_ENABLED) || remote.contains(KEY_HOME_DOCK_ICON_ENABLED)) settings = readHomeMaterialSettings(remote)
        write(local, settings); write(remote, settings)
        stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), readHomeFolderSettings(local), settings)
    }
    fun update(service: XposedService?, transform: (HomeMaterialSettings) -> HomeMaterialSettings): Boolean {
        settings = transform(settings).normalized()
        write(local, settings); service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(local), readHomeFolderSettings(local), settings)
    }
    private fun write(p: SharedPreferences, s: HomeMaterialSettings) {
        val editor = p.edit().putBoolean(KEY_HOME_MATERIAL_ENABLED, s.enabled)
            .putInt("home_glass_blur_factor", s.blur).putInt("home_glass_refraction_factor", s.refraction)
            .putInt("home_glass_soft_light_factor", s.softLight).putInt("home_glass_saturation_factor", s.saturation)
            .putInt("home_glass_brightness_factor", s.brightness)
        writeHomeDockIconSettings(editor, s.dockIcon)
        editor.apply()
    }
}
