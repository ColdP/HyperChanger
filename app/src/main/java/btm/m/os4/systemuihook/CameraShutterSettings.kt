// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import org.json.JSONObject

internal const val CAMERA_SHUTTER_SOUND_ENABLED = "camera_shutter_sound_enabled"
internal const val CAMERA_SHUTTER_SOUND_ID = "camera_shutter_sound_id"
internal const val CAMERA_SHUTTER_SELECTION_REVISION = "camera_shutter_selection_revision"
data class CameraShutterSettings(val soundEnabled: Boolean = false, val soundId: String = "", val selectionRevision: Long = 0)

internal fun SharedPreferences.readCameraShutterSettings() = CameraShutterSettings(
    getBoolean(CAMERA_SHUTTER_SOUND_ENABLED, false),
    getString(CAMERA_SHUTTER_SOUND_ID, "").orEmpty().takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty(),
    getLong(CAMERA_SHUTTER_SELECTION_REVISION, 0).coerceAtLeast(0),
)
internal fun SharedPreferences.Editor.writeCameraShutterSettings(value: CameraShutterSettings): SharedPreferences.Editor =
    putBoolean(CAMERA_SHUTTER_SOUND_ENABLED, value.soundEnabled)
        .putString(CAMERA_SHUTTER_SOUND_ID, value.soundId.takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty())
        .putLong(CAMERA_SHUTTER_SELECTION_REVISION, value.selectionRevision.coerceAtLeast(0))

internal fun completeCameraShutterPreset(values: JSONObject) {
    for (key in listOf(CAMERA_SHUTTER_SOUND_ENABLED))
        values.put(key, JSONObject().put("type", "boolean").put("value", values.optJSONObject(key)?.optBoolean("value", false) ?: false))
    val id = values.optJSONObject(CAMERA_SHUTTER_SOUND_ID)?.optString("value").orEmpty()
        .takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty()
    values.put(CAMERA_SHUTTER_SOUND_ID, JSONObject().put("type", "string").put("value", id))
    values.put(CAMERA_SHUTTER_SELECTION_REVISION, JSONObject().put("type", "long")
        .put("value", values.optJSONObject(CAMERA_SHUTTER_SELECTION_REVISION)?.optLong("value", 0)?.coerceAtLeast(0) ?: 0))
}
