// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal const val KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR = "lockscreen_music_wallpaper_blur"

internal const val KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER = "lockscreen_music_card_keep_wallpaper"

internal fun musicLockscreenWallpaperBlur(p: SharedPreferences) =
    p.getInt(KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR, 0).coerceIn(0, 100)

internal class MusicLockscreenWallpaperSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var keepWallpaper = local.getBoolean(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER, false)
        private set
    var blur = musicLockscreenWallpaperBlur(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        blur = musicLockscreenWallpaperBlur(if (local.contains(KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR)) local else remote)
        keepWallpaper = (if (local.contains(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER)) local else remote)
            .getBoolean(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER, false)
        write(local)
        write(remote)
    }
    fun update(service: XposedService?, value: Int) {
        blur = value.coerceIn(0, 100)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    fun updateKeepWallpaper(service: XposedService?, value: Boolean) {
        keepWallpaper = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    private fun write(p: SharedPreferences) {
        p.edit().putInt(KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR, blur)
            .putBoolean(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER, keepWallpaper).apply()
    }
}

internal fun completeMusicLockscreenWallpaperPreset(values: JSONObject) {
    val keep = values.optJSONObject(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER)?.optBoolean("value", false) ?: false
    values.put(KEY_MUSIC_LOCKSCREEN_KEEP_WALLPAPER, JSONObject().put("type", "boolean").put("value", keep))
    val blur = values.optJSONObject(KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR)?.optInt("value", 0) ?: 0
    values.put(KEY_MUSIC_LOCKSCREEN_WALLPAPER_BLUR,
        JSONObject().put("type", "int").put("value", blur.coerceIn(0, 100)))
}

@Composable
internal fun MusicLockscreenWallpaperPreference(cardStyle: Boolean) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { MusicLockscreenWallpaperSettingsStore(context) }
    var blur by remember { mutableIntStateOf(store.blur) }
    var keep by remember { mutableStateOf(store.keepWallpaper) }
    LaunchedEffect(service) { service?.let { store.syncRemote(it); blur = store.blur; keep = store.keepWallpaper } }
    androidx.compose.foundation.layout.Column {
        if (cardStyle) InterfaceSwitchPreference(
            title = tr("hmcKeepWallpaper", "保留壁纸"), checked = keep,
            onCheckedChange = { value -> store.updateKeepWallpaper(service, value); keep = store.keepWallpaper },
        )
        androidx.compose.animation.AnimatedVisibility(visible = !cardStyle || keep) {
            EditableSliderPreference(
                title = tr("musicWallpaperBlur", "壁纸模糊度"), value = blur.toFloat(),
                valueRange = 0f..100f, steps = 99, defaultValue = 0f, valueText = "$blur%",
                onValueChange = { blur = it.toInt() },
                onValueChangeFinished = { store.update(service, blur) },
            )
        }
    }
}
