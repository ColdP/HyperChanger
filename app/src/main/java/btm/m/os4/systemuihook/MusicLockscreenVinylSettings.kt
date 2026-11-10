// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_MUSIC_LOCKSCREEN_VINYL = "lockscreen_music_vinyl_v1"

internal data class MusicLockscreenVinylSettings(
    val rotation: Boolean = false,
    val rotationKeepOn: Boolean = false,
    val tonearm: Boolean = false,
    val colorMode: Int = 0,
    val customColor: Int = 0xFF292929.toInt(),
) {
    fun normalized() = copy(colorMode = colorMode.coerceIn(0, 2))
    fun json(): String = JSONObject().apply {
        put("rotation", rotation)
        put("rotationKeepOn", rotationKeepOn)
        put("tonearm", tonearm)
        put("colorMode", colorMode)
        put("customColor", customColor)
    }.toString()

    companion object {
        fun parse(raw: String?): MusicLockscreenVinylSettings {
            val j = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
            return MusicLockscreenVinylSettings(
                j.optBoolean("rotation", false), j.optBoolean("rotationKeepOn", false),
                j.optBoolean("tonearm", false), j.optInt("colorMode", 0),
                j.optInt("customColor", 0xFF292929.toInt()),
            ).normalized()
        }
    }
}

internal fun readMusicLockscreenVinylSettings(p: SharedPreferences) =
    MusicLockscreenVinylSettings.parse(p.getString(KEY_MUSIC_LOCKSCREEN_VINYL, null))

internal class MusicLockscreenVinylSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readMusicLockscreenVinylSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readMusicLockscreenVinylSettings(if (local.contains(KEY_MUSIC_LOCKSCREEN_VINYL)) local else remote)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (MusicLockscreenVinylSettings) -> MusicLockscreenVinylSettings) {
        settings = transform(readMusicLockscreenVinylSettings(local)).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().putString(KEY_MUSIC_LOCKSCREEN_VINYL, settings.json()).apply()
    }
}

internal fun completeMusicLockscreenVinylPreset(values: JSONObject) {
    val settings = MusicLockscreenVinylSettings.parse(values.optJSONObject(KEY_MUSIC_LOCKSCREEN_VINYL)?.optString("value"))
    values.put(KEY_MUSIC_LOCKSCREEN_VINYL, JSONObject().put("type", "string").put("value", settings.json()))
}

/** The same HCT engine used by the cover-driven lyric colors. */
object MusicLockscreenVinylPalette {
    /** Runs on the existing cover preparation worker, including when the wallpaper is kept. */
    @JvmStatic fun fromArtwork(art: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(art, 48, 48, true)
        return try {
            val pixels = IntArray(48 * 48)
            small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            val opaque = pixels.filter { it ushr 24 >= 128 }.map { it or 0xFF000000.toInt() }.toIntArray()
            if (opaque.isEmpty()) 0xFF292929.toInt()
            else color(Score.score(QuantizerCelebi.quantize(opaque, 128), 1, 0xFF292929.toInt(), true).firstOrNull() ?: 0)
        } finally {
            if (small !== art) small.recycle()
        }
    }

    @JvmStatic fun color(seed: Int): Int {
        if (seed == 0) return 0xFF292929.toInt()
        val source = Hct.fromInt(seed)
        return Hct.from(source.hue, minOf(source.chroma, 36.0), 32.0).toInt()
    }
}
