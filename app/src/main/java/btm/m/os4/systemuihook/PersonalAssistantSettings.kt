// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR = "personal_assistant_remove_wallpaper_blur_v1"

internal data class PersonalAssistantSettings(val removeWallpaperBlur: Boolean = false)

internal fun readPersonalAssistantSettings(p: SharedPreferences) = PersonalAssistantSettings(
    removeWallpaperBlur = p.getBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, false),
)

internal class PersonalAssistantSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readPersonalAssistantSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        if (!local.contains(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR)) {
            local.edit().putBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR,
                remote.getBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, false)).apply()
        }
        settings = readPersonalAssistantSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (PersonalAssistantSettings) -> PersonalAssistantSettings) {
        settings = transform(readPersonalAssistantSettings(local))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().putBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, settings.removeWallpaperBlur).apply()
    }
}

internal fun completePersonalAssistantPreset(values: JSONObject) {
    if (!values.has(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR)) {
        values.put(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, JSONObject().put("type", "boolean").put("value", false))
    }
}
