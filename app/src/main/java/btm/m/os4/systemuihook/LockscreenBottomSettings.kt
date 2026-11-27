// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT = "lockscreen_bottom_custom_text"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_ENABLED = "lockscreen_gesture_line_enabled"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL = "lockscreen_gesture_line_material"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE = "lockscreen_gesture_line_height_scale"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE = "lockscreen_gesture_line_width_scale"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET = "lockscreen_gesture_line_vertical_offset"
internal const val KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM = "lockscreen_gesture_line_unlock_hint_anim"

internal const val LOCKSCREEN_GESTURE_LINE_MATERIAL_SOLID = 0
internal const val LOCKSCREEN_GESTURE_LINE_MATERIAL_BLUR = 1

internal const val LOCKSCREEN_GESTURE_LINE_PERCENT_MIN = -100
internal const val LOCKSCREEN_GESTURE_LINE_PERCENT_MAX = 100
internal const val LOCKSCREEN_BOTTOM_CUSTOM_TEXT_MAX_LEN = 120

data class LockscreenBottomSettings(
    val customText: String = "",
    val gestureLineEnabled: Boolean = false,
    val gestureLineMaterial: Int = LOCKSCREEN_GESTURE_LINE_MATERIAL_SOLID,
    val gestureLineHeightScale: Int = 0,
    val gestureLineWidthScale: Int = 0,
    val gestureLineVerticalOffset: Int = 0,
    val gestureLineUnlockHintAnim: Boolean = true,
) {
    fun normalized(): LockscreenBottomSettings = copy(
        customText = customText.replace('\n', ' ').replace('\r', ' ').trim().take(LOCKSCREEN_BOTTOM_CUSTOM_TEXT_MAX_LEN),
        gestureLineMaterial = gestureLineMaterial.coerceIn(
            LOCKSCREEN_GESTURE_LINE_MATERIAL_SOLID,
            LOCKSCREEN_GESTURE_LINE_MATERIAL_BLUR,
        ),
        gestureLineHeightScale = gestureLineHeightScale.coerceIn(
            LOCKSCREEN_GESTURE_LINE_PERCENT_MIN,
            LOCKSCREEN_GESTURE_LINE_PERCENT_MAX,
        ),
        gestureLineWidthScale = gestureLineWidthScale.coerceIn(
            LOCKSCREEN_GESTURE_LINE_PERCENT_MIN,
            LOCKSCREEN_GESTURE_LINE_PERCENT_MAX,
        ),
        gestureLineVerticalOffset = gestureLineVerticalOffset.coerceIn(
            LOCKSCREEN_GESTURE_LINE_PERCENT_MIN,
            LOCKSCREEN_GESTURE_LINE_PERCENT_MAX,
        ),
    )
}

internal fun readLockscreenBottomSettings(preferences: SharedPreferences): LockscreenBottomSettings =
    LockscreenBottomSettings(
        customText = preferences.getString(KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT, "").orEmpty(),
        gestureLineEnabled = preferences.getBoolean(KEY_LOCKSCREEN_GESTURE_LINE_ENABLED, false),
        gestureLineMaterial = preferences.getInt(
            KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL,
            LOCKSCREEN_GESTURE_LINE_MATERIAL_SOLID,
        ),
        gestureLineHeightScale = preferences.getInt(KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE, 0),
        gestureLineWidthScale = preferences.getInt(KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE, 0),
        gestureLineVerticalOffset = preferences.getInt(KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET, 0),
        gestureLineUnlockHintAnim = preferences.getBoolean(KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM, true),
    ).normalized()

/** Keep exported and imported presets complete even when settings remain at defaults. */
internal fun completeLockscreenBottomPreset(values: JSONObject) {
    if (!values.has(KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT)) {
        values.put(
            KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT,
            JSONObject().put("type", "string").put("value", ""),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_ENABLED)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_ENABLED,
            JSONObject().put("type", "boolean").put("value", false),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL,
            JSONObject().put("type", "int").put("value", LOCKSCREEN_GESTURE_LINE_MATERIAL_SOLID),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE,
            JSONObject().put("type", "int").put("value", 0),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE,
            JSONObject().put("type", "int").put("value", 0),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET,
            JSONObject().put("type", "int").put("value", 0),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM)) {
        values.put(
            KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM,
            JSONObject().put("type", "boolean").put("value", true),
        )
    }
}

class LockscreenBottomSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: LockscreenBottomSettings = readLockscreenBottomSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readLockscreenBottomSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(
        service: XposedService?,
        transform: (LockscreenBottomSettings) -> LockscreenBottomSettings,
    ) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(preferences: SharedPreferences, value: LockscreenBottomSettings) {
        preferences.edit()
            .putString(KEY_LOCKSCREEN_BOTTOM_CUSTOM_TEXT, value.customText)
            .putBoolean(KEY_LOCKSCREEN_GESTURE_LINE_ENABLED, value.gestureLineEnabled)
            .putInt(KEY_LOCKSCREEN_GESTURE_LINE_MATERIAL, value.gestureLineMaterial)
            .putInt(KEY_LOCKSCREEN_GESTURE_LINE_HEIGHT_SCALE, value.gestureLineHeightScale)
            .putInt(KEY_LOCKSCREEN_GESTURE_LINE_WIDTH_SCALE, value.gestureLineWidthScale)
            .putInt(KEY_LOCKSCREEN_GESTURE_LINE_VERTICAL_OFFSET, value.gestureLineVerticalOffset)
            .putBoolean(KEY_LOCKSCREEN_GESTURE_LINE_UNLOCK_HINT_ANIM, value.gestureLineUnlockHintAnim)
            .apply()
    }
}
