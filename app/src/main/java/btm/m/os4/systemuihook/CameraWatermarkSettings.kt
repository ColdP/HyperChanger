// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import org.json.JSONObject

internal const val CAMERA_XIAOMI_WATERMARK_SUFFIX = "camera_xiaomi_watermark_suffix"
internal const val CAMERA_WATERMARK_SUFFIXES = "camera_watermark_suffixes"
internal const val CAMERA_WATERMARK_BRAND = "camera_watermark_brand"
internal const val CAMERA_ALL_WATERMARKS = "camera_all_watermarks"
internal const val CAMERA_FRONT_WATERMARKS = "camera_front_watermarks"

data class CameraWatermarkSettings(val xiaomiSuffix: Boolean = false, val allWatermarks: Boolean = false, val frontWatermarks: Boolean = false, val suffixes: Int = 0, val brand: String = "")

/** Used for both the local settings and the existing remote settings group. */
internal fun SharedPreferences.readCameraWatermarkSettings() = CameraWatermarkSettings(
    suffixes = cameraWatermarkSuffixMask(this, android.os.Build.BRAND),
    brand = getString(CAMERA_WATERMARK_BRAND, "")?.trim()?.take(32).orEmpty(),
    xiaomiSuffix = getBoolean(CAMERA_XIAOMI_WATERMARK_SUFFIX, false),
    allWatermarks = getBoolean(CAMERA_ALL_WATERMARKS, false),
    frontWatermarks = getBoolean(CAMERA_FRONT_WATERMARKS, false),
)

internal fun SharedPreferences.Editor.writeCameraWatermarkSettings(value: CameraWatermarkSettings): SharedPreferences.Editor =
    putInt(CAMERA_WATERMARK_SUFFIXES, value.suffixes and 31)
        .putString(CAMERA_WATERMARK_BRAND, value.brand.trim().take(32))
        .putBoolean(CAMERA_XIAOMI_WATERMARK_SUFFIX, value.xiaomiSuffix)
        .putBoolean(CAMERA_ALL_WATERMARKS, value.allWatermarks)
        .putBoolean(CAMERA_FRONT_WATERMARKS, value.frontWatermarks)

internal fun completeCameraWatermarkPreset(values: JSONObject) {
    val enabled = values.optJSONObject(CAMERA_XIAOMI_WATERMARK_SUFFIX)?.getBoolean("value") ?: false
    values.put(CAMERA_XIAOMI_WATERMARK_SUFFIX, JSONObject().put("type", "boolean").put("value", enabled))
    val suffixes = values.optJSONObject(CAMERA_WATERMARK_SUFFIXES)?.getInt("value")
        ?: if (enabled || android.os.Build.BRAND.equals("redmi", true)) 15 else 0
    values.put(CAMERA_WATERMARK_SUFFIXES, JSONObject().put("type", "int").put("value", suffixes and 31))
    val brand = values.optJSONObject(CAMERA_WATERMARK_BRAND)?.getString("value").orEmpty().trim().take(32)
    values.put(CAMERA_WATERMARK_BRAND, JSONObject().put("type", "string").put("value", brand))
    val all = values.optJSONObject(CAMERA_ALL_WATERMARKS)?.getBoolean("value") ?: false
    values.put(CAMERA_ALL_WATERMARKS, JSONObject().put("type", "boolean").put("value", all))
    val front = values.optJSONObject(CAMERA_FRONT_WATERMARKS)?.getBoolean("value") ?: false
    values.put(CAMERA_FRONT_WATERMARKS, JSONObject().put("type", "boolean").put("value", front))
}

internal fun cameraWatermarkSuffixMask(prefs: SharedPreferences?, brand: String): Int =
    if (prefs?.contains(CAMERA_WATERMARK_SUFFIXES) == true) prefs.getInt(CAMERA_WATERMARK_SUFFIXES, 0) and 31
    else if (prefs?.getBoolean(CAMERA_XIAOMI_WATERMARK_SUFFIX, false) == true || brand.equals("REDMI", true)) 15 else 0
