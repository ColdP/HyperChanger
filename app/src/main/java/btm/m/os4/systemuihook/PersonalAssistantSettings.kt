// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR = "personal_assistant_remove_wallpaper_blur_v1"
internal const val KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR = "personal_assistant_custom_wallpaper_blur_v1"
internal const val KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS = "personal_assistant_wallpaper_blur_radius_v1"
internal const val KEY_ASSISTANT_BACKGROUND_MODE = "personal_assistant_background_mode_v1"
internal const val KEY_ASSISTANT_BACKGROUND_IMAGE = "personal_assistant_background_image_v1"
internal const val KEY_ASSISTANT_BACKGROUND_NAME = "personal_assistant_background_name_v1"
internal const val ASSISTANT_BLUR_RADIUS_DEFAULT = 100
internal const val ASSISTANT_BLUR_RADIUS_MAX = 200

internal data class PersonalAssistantSettings(
    val removeWallpaperBlur: Boolean = false,
    val customWallpaperBlur: Boolean = false,
    val wallpaperBlurRadius: Int = ASSISTANT_BLUR_RADIUS_DEFAULT,
    val backgroundMode: Int = 0,
    val backgroundImage: String = "",
    val backgroundName: String = "",
) {
    val customBlurActive: Boolean get() = !removeWallpaperBlur && customWallpaperBlur

    fun replacementBlurRadius(progress: Float, nativeMax: Float): Float {
        val max = if (removeWallpaperBlur) 0f else if (customBlurActive) wallpaperBlurRadius.toFloat()
            else nativeMax.takeIf { it.isFinite() && it >= 0f } ?: ASSISTANT_BLUR_RADIUS_DEFAULT.toFloat()
        return max * assistantBackgroundProgress(progress)
    }

    fun windowBlurRadius(radius: Float, nativeMax: Float): Float = when {
        removeWallpaperBlur -> 0f
        !customBlurActive || radius < 0f || !radius.isFinite() -> radius
        radius == 0f || nativeMax <= 0f || !nativeMax.isFinite() -> 0f
        else -> radius / nativeMax * wallpaperBlurRadius.coerceIn(0, ASSISTANT_BLUR_RADIUS_MAX)
    }
}

internal fun readPersonalAssistantSettings(p: SharedPreferences) = PersonalAssistantSettings(
    removeWallpaperBlur = p.getBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, false),
    customWallpaperBlur = p.getBoolean(KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR, false),
    wallpaperBlurRadius = p.getInt(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS, ASSISTANT_BLUR_RADIUS_DEFAULT)
        .coerceIn(0, ASSISTANT_BLUR_RADIUS_MAX),
    backgroundMode = p.getInt(KEY_ASSISTANT_BACKGROUND_MODE, 0).coerceIn(0, 2),
    backgroundImage = p.getString(KEY_ASSISTANT_BACKGROUND_IMAGE, "").orEmpty(),
    backgroundName = p.getString(KEY_ASSISTANT_BACKGROUND_NAME, "").orEmpty(),
)

internal fun assistantBackgroundProgress(progress: Float, offset: Float = 0f): Float {
    if (!progress.isFinite()) return 0f
    val start = offset.takeIf { it.isFinite() && it >= 0f && it < 1f } ?: 0f
    return ((progress - start) / (1f - start)).coerceIn(0f, 1f)
}

internal class PersonalAssistantSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readPersonalAssistantSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        val editor = local.edit()
        if (!local.contains(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR)) {
            editor.putBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR,
                remote.getBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, false))
        }
        if (!local.contains(KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR)) {
            editor.putBoolean(KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR,
                remote.getBoolean(KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR, false))
        }
        if (!local.contains(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS)) {
            editor.putInt(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS,
                remote.getInt(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS, ASSISTANT_BLUR_RADIUS_DEFAULT))
        }
        if (!local.contains(KEY_ASSISTANT_BACKGROUND_MODE)) {
            editor.putInt(KEY_ASSISTANT_BACKGROUND_MODE, remote.getInt(KEY_ASSISTANT_BACKGROUND_MODE, 0))
        }
        for (key in listOf(KEY_ASSISTANT_BACKGROUND_IMAGE, KEY_ASSISTANT_BACKGROUND_NAME)) {
            if (!local.contains(key)) editor.putString(key, remote.getString(key, ""))
        }
        editor.apply()
        settings = readPersonalAssistantSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (PersonalAssistantSettings) -> PersonalAssistantSettings) {
        val updated = transform(readPersonalAssistantSettings(local))
        settings = updated.copy(wallpaperBlurRadius = updated.wallpaperBlurRadius.coerceIn(0, ASSISTANT_BLUR_RADIUS_MAX),
            backgroundMode = updated.backgroundMode.coerceIn(0, 2))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().putBoolean(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, settings.removeWallpaperBlur)
            .putBoolean(KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR, settings.customWallpaperBlur)
            .putInt(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS, settings.wallpaperBlurRadius)
            .putInt(KEY_ASSISTANT_BACKGROUND_MODE, settings.backgroundMode)
            .putString(KEY_ASSISTANT_BACKGROUND_IMAGE, settings.backgroundImage)
            .putString(KEY_ASSISTANT_BACKGROUND_NAME, settings.backgroundName).apply()
    }
}

internal fun completePersonalAssistantPreset(values: JSONObject) {
    for (key in listOf(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR)) {
        if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", false))
    }
    if (!values.has(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS)) {
        values.put(KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS,
            JSONObject().put("type", "int").put("value", ASSISTANT_BLUR_RADIUS_DEFAULT))
    }
    if (!values.has(KEY_ASSISTANT_BACKGROUND_MODE)) {
        values.put(KEY_ASSISTANT_BACKGROUND_MODE, JSONObject().put("type", "int").put("value", 0))
    }
    for (key in listOf(KEY_ASSISTANT_BACKGROUND_IMAGE, KEY_ASSISTANT_BACKGROUND_NAME)) {
        if (!values.has(key)) values.put(key, JSONObject().put("type", "string").put("value", ""))
    }
}
