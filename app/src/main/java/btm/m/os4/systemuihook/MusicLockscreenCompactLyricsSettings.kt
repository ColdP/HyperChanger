// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyricAppearance

internal const val KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS = "lockscreen_music_compact_lyrics"

internal data class MusicLockscreenCompactLyricsSettings(
    val enabled: Boolean = false,
    val scale: Float = 1f,
    val verticalOffset: Float = 0f,
    val lineSpacing: Float = 8f,
    val keepScreenOn: Boolean = false,
    val appearance: String = "{}",
) {
    fun normalized() = copy(scale = if (scale.isFinite()) scale.coerceIn(.65f, 1.5f) else 1f,
        verticalOffset = if (verticalOffset.isFinite()) verticalOffset.coerceIn(-80f, 80f) else 0f,
        lineSpacing = if (lineSpacing.isFinite()) lineSpacing.coerceIn(0f, 32f) else 8f,
        appearance = LyricAppearance.parse(appearance).let {
            JSONObject().put("mainMode", it.mainMode).put("mainColor", it.mainColor)
                .put("mainOpacity", it.mainOpacity).toString()
        })
    fun json() = JSONObject().put("enabled", enabled).put("scale", scale)
        .put("verticalOffset", verticalOffset).put("lineSpacing", lineSpacing)
        .put("keepScreenOn", keepScreenOn).put("appearance", JSONObject(appearance)).toString()
    companion object {
        fun parse(raw: String?): MusicLockscreenCompactLyricsSettings {
            val j = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
            return MusicLockscreenCompactLyricsSettings(j.optBoolean("enabled", false),
                j.optDouble("scale", 1.0).toFloat(), j.optDouble("verticalOffset", 0.0).toFloat(),
                j.optDouble("lineSpacing", 8.0).toFloat(), j.optBoolean("keepScreenOn", false),
                (j.optJSONObject("appearance") ?: JSONObject()).toString()).normalized()
        }
    }
}

internal fun readMusicLockscreenCompactLyricsSettings(p: SharedPreferences) =
    MusicLockscreenCompactLyricsSettings.parse(p.getString(KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS, null))

internal class MusicLockscreenCompactLyricsSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readMusicLockscreenCompactLyricsSettings(local); private set
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readMusicLockscreenCompactLyricsSettings(
            if (local.contains(KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS)) local else remote)
        write(local); write(remote)
    }
    fun update(service: XposedService?, value: MusicLockscreenCompactLyricsSettings) {
        settings = value.normalized(); write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    private fun write(p: SharedPreferences) { p.edit().putString(KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS, settings.json()).apply() }
}

internal fun completeMusicLockscreenCompactLyricsPreset(values: JSONObject) {
    val settings = MusicLockscreenCompactLyricsSettings.parse(
        values.optJSONObject(KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS)?.optString("value"))
    values.put(KEY_MUSIC_LOCKSCREEN_COMPACT_LYRICS,
        JSONObject().put("type", "string").put("value", settings.json()))
}

@Composable
internal fun MusicLockscreenCompactLyricsPreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { MusicLockscreenCompactLyricsSettingsStore(context) }
    var value by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) { service?.let { store.syncRemote(it); value = store.settings } }
    fun update(next: MusicLockscreenCompactLyricsSettings) {
        value = next.normalized(); store.update(service, value)
    }
    Column {
        InterfaceSwitchPreference(title = tr("compactLyricsEnabled", "音乐锁屏状态下显示简略歌词"),
            checked = value.enabled, onCheckedChange = { update(value.copy(enabled = it)) })
        androidx.compose.animation.AnimatedVisibility(value.enabled) {
            Column {
                InterfaceSwitchPreference(title = tr("compactLyricsKeepOn", "显示简略歌词时保持亮屏"), checked = value.keepScreenOn,
                    onCheckedChange = { update(value.copy(keepScreenOn = it)) })
                val appearance = JSONObject(value.appearance)
                fun updateColor(key: String, next: Any) {
                    update(value.copy(appearance = JSONObject(value.appearance).put(key, next).toString()))
                }
                OverlayDropdownPreference(title = tr("hmcMainColorMode", "主歌词颜色模式"),
                    items = listOf(tr("hmcOfficial", "默认颜色"), tr("hmcFromCover", "跟随封面"), tr("hmcCustom", "自定义颜色")),
                    selectedIndex = appearance.optInt("mainMode", 0), onSelectedIndexChange = { updateColor("mainMode", it) })
                androidx.compose.animation.AnimatedVisibility(appearance.optInt("mainMode", 0) == 2) {
                    ShortcutBackgroundColorPreference(title = tr("hmcMainColor", "主歌词颜色"),
                        color = appearance.optInt("mainColor", -1), onColorChange = { updateColor("mainColor", it) })
                }
                EditableSliderPreference(title = tr("compactLyricsScale", "简略歌词文本缩放"), value = value.scale,
                    valueRange = .65f..1.5f, steps = 16, defaultValue = 1f, valueText = "${(value.scale * 100).toInt()}%",
                    onValueChange = { value = value.copy(scale = it) }, onValueChangeFinished = { update(value) })
                EditableSliderPreference(title = tr("compactLyricsOffset", "简略歌词垂直位置偏移"), value = value.verticalOffset,
                    valueRange = -80f..80f, steps = 31, defaultValue = 0f, valueText = "${value.verticalOffset.toInt()} dp",
                    onValueChange = { value = value.copy(verticalOffset = it) }, onValueChangeFinished = { update(value) })
                EditableSliderPreference(title = tr("compactLyricsSpacing", "简略歌词行间距"), value = value.lineSpacing,
                    valueRange = 0f..32f, steps = 15, defaultValue = 8f, valueText = "${value.lineSpacing.toInt()} dp",
                    onValueChange = { value = value.copy(lineSpacing = it) }, onValueChangeFinished = { update(value) })
            }
        }
    }
}
