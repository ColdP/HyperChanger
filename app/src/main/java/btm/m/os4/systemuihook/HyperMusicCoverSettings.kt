// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val HMC_NEXT_SETTINGS_KEY = "hmc_next_v1_settings"

/** New upstream options; the existing music/capsule keys stay with their original stores. */
data class HyperMusicCoverSettings(
    val coverStyle: Int = 0,
    val autoEnter: Boolean = false,
    val motionCover: Boolean = false,
    val motionOnData: Boolean = false,
    val clockSize: Float = 0.09f,
    val lyricsOnline: Boolean = true,
    val lyricsTranslation: Boolean = true,
    val lyricsRomanization: Boolean = false,
    val lyricsAlign: Int = 0,
    val lyricsSize: Float = 25f,
    val lyricsBackgroundSize: Float = 17f,
    val lyricAppearance: String = "{}",
    val islandStyle: Int = 0,
    val adaptiveWidth: Boolean = false,
    val ordinaryNotifications: Boolean = false,
    val groupByApp: Boolean = false,
    val statusAtDate: Boolean = false,
    val fingerprintLift: Boolean = true,
    val navigationKeepOn: Boolean = false,
) {
    fun normalized() = copy(
        coverStyle = coverStyle.coerceIn(0, 2),
        clockSize = if (clockSize.isFinite()) clockSize.coerceIn(0.05f, 1f) else 0.09f,
        lyricsAlign = lyricsAlign.coerceIn(0, 2),
        lyricsSize = if (lyricsSize.isFinite()) lyricsSize.coerceIn(18f, 36f) else 25f,
        lyricsBackgroundSize = if (lyricsBackgroundSize.isFinite()) lyricsBackgroundSize.coerceIn(10f, 30f) else 17f,
        islandStyle = islandStyle.coerceIn(0, 1),
        lyricAppearance = btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyricAppearance.parse(lyricAppearance).toJson(),
    )

    internal fun json(): String = JSONObject().apply {
        put("coverStyle", coverStyle); put("autoEnter", autoEnter)
        put("motionCover", motionCover); put("motionOnData", motionOnData); put("clockSize", clockSize)
        put("lyricsOnline", lyricsOnline); put("lyricsTranslation", lyricsTranslation)
        put("lyricsRomanization", lyricsRomanization); put("lyricsAlign", lyricsAlign)
        put("lyricsSize", lyricsSize); put("lyricsBackgroundSize", lyricsBackgroundSize)
        put("lyricAppearance", lyricAppearance)
        put("islandStyle", islandStyle); put("adaptiveWidth", adaptiveWidth)
        put("ordinaryNotifications", ordinaryNotifications); put("groupByApp", groupByApp)
        put("statusAtDate", statusAtDate); put("fingerprintLift", fingerprintLift)
        put("navigationKeepOn", navigationKeepOn)
    }.toString()
}

internal fun readHyperMusicCoverSettings(prefs: SharedPreferences): HyperMusicCoverSettings {
    val j = runCatching { JSONObject(prefs.getString(HMC_NEXT_SETTINGS_KEY, "{}").orEmpty()) }.getOrDefault(JSONObject())
    return HyperMusicCoverSettings(
        coverStyle = j.optInt("coverStyle", 0), autoEnter = j.optBoolean("autoEnter", false),
        motionCover = j.optBoolean("motionCover", false), motionOnData = j.optBoolean("motionOnData", false),
        clockSize = j.optDouble("clockSize", 0.09).toFloat(),
        lyricsOnline = j.optBoolean("lyricsOnline", true), lyricsTranslation = j.optBoolean("lyricsTranslation", true),
        lyricsRomanization = j.optBoolean("lyricsRomanization", false), lyricsAlign = j.optInt("lyricsAlign", 0),
        lyricsSize = j.optDouble("lyricsSize", 25.0).toFloat(), lyricsBackgroundSize = j.optDouble("lyricsBackgroundSize", 17.0).toFloat(),
        lyricAppearance = j.optString("lyricAppearance", "{}"),
        islandStyle = j.optInt("islandStyle", 0), adaptiveWidth = j.optBoolean("adaptiveWidth", false),
        ordinaryNotifications = j.optBoolean("ordinaryNotifications", false), groupByApp = j.optBoolean("groupByApp", false),
        statusAtDate = j.optBoolean("statusAtDate", false), fingerprintLift = j.optBoolean("fingerprintLift", true),
        navigationKeepOn = j.optBoolean("navigationKeepOn", false),
    ).normalized()
}

class HyperMusicCoverSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readHyperMusicCoverSettings(local)
        private set
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (local.contains(HMC_NEXT_SETTINGS_KEY)) readHyperMusicCoverSettings(local)
            else if (remote.contains(HMC_NEXT_SETTINGS_KEY)) readHyperMusicCoverSettings(remote) else settings
        local.edit().putString(HMC_NEXT_SETTINGS_KEY, settings.json()).apply()
        remote.edit().putString(HMC_NEXT_SETTINGS_KEY, settings.json()).apply()
    }
    fun update(service: XposedService?, transform: (HyperMusicCoverSettings) -> HyperMusicCoverSettings) {
        // Cover, lyrics and capsule pages edit this document independently.
        settings = transform(readHyperMusicCoverSettings(local)).normalized()
        local.edit().putString(HMC_NEXT_SETTINGS_KEY, settings.json()).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putString(HMC_NEXT_SETTINGS_KEY, settings.json())?.apply()
    }
}
