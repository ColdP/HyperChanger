// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_CC_MEDIA_BIG_COVER_EXPAND = "control_center_media_big_cover_expand"
internal const val KEY_CC_MEDIA_BIG_COVER_IMMERSIVE = "control_center_media_big_cover_immersive"
internal const val KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW = "control_center_media_big_cover_text_shadow"
internal const val KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE = "control_center_media_big_cover_text_color_mode"

internal const val CC_MEDIA_TEXT_COLOR_OFF = 0
internal const val CC_MEDIA_TEXT_COLOR_MONET = 1
internal const val CC_MEDIA_TEXT_COLOR_OKLAB = 2

data class ControlCenterMediaBigCoverSettings(
    val enabled: Boolean = false,
    val immersiveCover: Boolean = false,
    val immersiveTextShadow: Boolean = true,
    val immersiveTextColorMode: Int = CC_MEDIA_TEXT_COLOR_OFF,
)

internal fun readControlCenterMediaBigCoverSettings(preferences: SharedPreferences) =
    ControlCenterMediaBigCoverSettings(
        enabled = preferences.getBoolean(KEY_CC_MEDIA_BIG_COVER_EXPAND, false),
        immersiveCover = preferences.getBoolean(KEY_CC_MEDIA_BIG_COVER_IMMERSIVE, false),
        immersiveTextShadow = preferences.getBoolean(KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW, true),
        immersiveTextColorMode = preferences
            .getInt(KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE, CC_MEDIA_TEXT_COLOR_OFF)
            .coerceIn(CC_MEDIA_TEXT_COLOR_OFF, CC_MEDIA_TEXT_COLOR_OKLAB),
    )

/** Keep exported and imported presets complete even when the toggle was never changed. */
internal fun completeControlCenterMediaBigCoverPreset(values: JSONObject) {
    if (!values.has(KEY_CC_MEDIA_BIG_COVER_EXPAND)) {
        values.put(
            KEY_CC_MEDIA_BIG_COVER_EXPAND,
            JSONObject().put("type", "boolean").put("value", false),
        )
    }
    if (!values.has(KEY_CC_MEDIA_BIG_COVER_IMMERSIVE)) {
        values.put(
            KEY_CC_MEDIA_BIG_COVER_IMMERSIVE,
            JSONObject().put("type", "boolean").put("value", false),
        )
    }
    if (!values.has(KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW)) {
        values.put(
            KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW,
            JSONObject().put("type", "boolean").put("value", true),
        )
    }
    if (!values.has(KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE)) {
        values.put(
            KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE,
            JSONObject().put("type", "int").put("value", CC_MEDIA_TEXT_COLOR_OFF),
        )
    }
}

class ControlCenterMediaBigCoverSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ControlCenterMediaBigCoverSettings = readControlCenterMediaBigCoverSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterMediaBigCoverSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(service: XposedService?, transform: (ControlCenterMediaBigCoverSettings) -> ControlCenterMediaBigCoverSettings) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(preferences: SharedPreferences, value: ControlCenterMediaBigCoverSettings) {
        preferences.edit()
            .putBoolean(KEY_CC_MEDIA_BIG_COVER_EXPAND, value.enabled)
            .putBoolean(KEY_CC_MEDIA_BIG_COVER_IMMERSIVE, value.immersiveCover)
            .putBoolean(KEY_CC_MEDIA_BIG_COVER_TEXT_SHADOW, value.immersiveTextShadow)
            .putInt(
                KEY_CC_MEDIA_BIG_COVER_TEXT_COLOR_MODE,
                value.immersiveTextColorMode.coerceIn(CC_MEDIA_TEXT_COLOR_OFF, CC_MEDIA_TEXT_COLOR_OKLAB),
            )
            .apply()
    }
}
