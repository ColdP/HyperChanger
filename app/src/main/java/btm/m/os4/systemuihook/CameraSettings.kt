// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

private const val CAMERA_PREFERENCE_GROUP = "settings"
private const val CAMERA_INITIALIZED = "initialized"
private const val CAMERA_MASTER_ENABLED = "master_enabled"
private const val CAMERA_LEICA_UI = "leica_ui"
private const val CAMERA_PRESERVE_FOCAL_LENGTHS = "preserve_native_focal_lengths"
private const val CAMERA_GALLERY_WATERMARKS = "gallery_all_watermarks"
private const val CAMERA_GALLERY_PALETTE = "gallery_palette_unlocked"
private const val CAMERA_INSTANT_MODE = "instant_mode"
private const val CAMERA_ALL_XIAOMI18_FEATURES = "camera_all_xiaomi18_features"
internal const val CAMERA_CUSTOM_WATERMARK_MODEL = "camera_custom_watermark_model"
internal const val CAMERA_CUSTOM_FOCAL_LENGTHS = "camera_custom_focal_lengths"
internal const val CAMERA_FOCAL_LENGTH_SOURCE = "camera_focal_length_source"
internal const val CAMERA_FOCAL_LENGTH_PRESET = "camera_focal_length_preset"
private const val CAMERA_REMOVE_MODEL_LIMITATION = "camera_remove_model_limitation"

data class CameraSettings(
    val masterEnabled: Boolean = true,
    val leicaUi: Boolean = true,
    val preserveNativeFocalLengths: Boolean = true,
    val galleryAllWatermarks: Boolean = true,
    val galleryPaletteUnlocked: Boolean = false,
    val instantMode: Int = 0,
    val allXiaomi18Features: Boolean = true,
    val customWatermarkModel: String = "",
    val watermark: CameraWatermarkSettings = CameraWatermarkSettings(),
    val shutter: CameraShutterSettings = CameraShutterSettings(),
    val customFocalLengths: String = "",
    val focalLengthSource: Int = 0,
    val focalLengthPreset: String = "auto",
    val removeModelLimitation: Boolean = false,
    val paletteFilter: String = "自然",
    val paletteTone: Float = 0f,
    val paletteColor: Float = 0f,
    val paletteIntensity: Float = 1f,
    val paletteExposure: Float = 0f,
    val paletteContrast: Float = 1f,
    val paletteSaturation: Float = 1f,
    val paletteWarmth: Float = 0f,
    val skinToneProtection: Boolean = true,
    val skinToneProtectionAmount: Float = .65f,
)

class CameraSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val local = appContext.getSharedPreferences(CAMERA_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: CameraSettings = CameraVersionCompatibility.constrain(appContext, local.toCameraSettings())
        private set

    init {
        if (!CameraVersionCompatibility.isSupported(appContext)) {
            local.edit().putBoolean(CAMERA_MASTER_ENABLED, false).apply()
        }
    }

    fun reload() {
        settings = CameraVersionCompatibility.constrain(appContext, local.toCameraSettings())
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(CAMERA_PREFERENCE_GROUP)
        settings = if (remote.contains(CAMERA_INITIALIZED)) remote.toCameraSettings() else settings.also { remote.writeCameraSettings(it) }
        settings = CameraVersionCompatibility.constrain(appContext, settings)
        if (!settings.masterEnabled) remote.edit().putBoolean(CAMERA_MASTER_ENABLED, false).apply()
        local.writeCameraSettings(settings)
    }

    fun update(service: XposedService?, transform: (CameraSettings) -> CameraSettings) {
        settings = CameraVersionCompatibility.constrain(appContext, transform(settings))
        local.writeCameraSettings(settings)
        service?.getRemotePreferences(CAMERA_PREFERENCE_GROUP)?.writeCameraSettings(settings)
    }
}

private fun SharedPreferences.toCameraSettings() = CameraSettings(
    masterEnabled = getBoolean(CAMERA_MASTER_ENABLED, true),
    leicaUi = getBoolean(CAMERA_LEICA_UI, true),
    preserveNativeFocalLengths = getBoolean(CAMERA_PRESERVE_FOCAL_LENGTHS, true),
    galleryAllWatermarks = getBoolean(CAMERA_GALLERY_WATERMARKS, true),
    galleryPaletteUnlocked = getBoolean(CAMERA_GALLERY_PALETTE, false),
    instantMode = getInt(CAMERA_INSTANT_MODE, 0).coerceIn(0, 1),
    allXiaomi18Features = getBoolean(CAMERA_ALL_XIAOMI18_FEATURES, true),
    customWatermarkModel = getString(CAMERA_CUSTOM_WATERMARK_MODEL, "")?.trim()?.take(64) ?: "",
    watermark = readCameraWatermarkSettings(),
    shutter = readCameraShutterSettings(),
    customFocalLengths = getString(CAMERA_CUSTOM_FOCAL_LENGTHS, "")?.trim()?.take(160) ?: "",
    focalLengthSource = getInt(CAMERA_FOCAL_LENGTH_SOURCE, 0).coerceIn(0, 2),
    focalLengthPreset = getString(CAMERA_FOCAL_LENGTH_PRESET, "auto")?.trim()?.take(32) ?: "auto",
    removeModelLimitation = getBoolean(CAMERA_REMOVE_MODEL_LIMITATION, false),
    paletteFilter = getString("palette_filter", "自然") ?: "自然",
    paletteTone = getFloat("palette_tone", 0f).coerceIn(-1f, 1f),
    paletteColor = getFloat("palette_color", 0f).coerceIn(-1f, 1f),
    paletteIntensity = getFloat("palette_intensity", 1f).coerceIn(0f, 1f),
    paletteExposure = getFloat("palette_exposure", 0f).coerceIn(-1f, 1f),
    paletteContrast = getFloat("palette_contrast", 1f).coerceIn(0f, 2f),
    paletteSaturation = getFloat("palette_saturation", 1f).coerceIn(0f, 1.8f),
    paletteWarmth = getFloat("palette_warmth", 0f).coerceIn(-1f, 1f),
    skinToneProtection = getBoolean("palette_skin_protection", true),
    skinToneProtectionAmount = getFloat("palette_skin_protection_amount", .65f).coerceIn(0f, 1f),
)

private fun SharedPreferences.writeCameraSettings(value: CameraSettings) {
    edit()
        .putBoolean(CAMERA_INITIALIZED, true)
        .putBoolean(CAMERA_MASTER_ENABLED, value.masterEnabled)
        .putBoolean(CAMERA_LEICA_UI, value.leicaUi)
        .putBoolean(CAMERA_PRESERVE_FOCAL_LENGTHS, value.preserveNativeFocalLengths)
        .putBoolean(CAMERA_GALLERY_WATERMARKS, value.galleryAllWatermarks)
        .putBoolean(CAMERA_GALLERY_PALETTE, value.galleryPaletteUnlocked)
        .putInt(CAMERA_INSTANT_MODE, value.instantMode.coerceIn(0, 1))
        .putBoolean(CAMERA_ALL_XIAOMI18_FEATURES, value.allXiaomi18Features)
        .putString(CAMERA_CUSTOM_WATERMARK_MODEL, value.customWatermarkModel.trim().take(64))
        .writeCameraWatermarkSettings(value.watermark)
        .writeCameraShutterSettings(value.shutter)
        .putString(CAMERA_CUSTOM_FOCAL_LENGTHS, value.customFocalLengths.trim().take(160))
        .putInt(CAMERA_FOCAL_LENGTH_SOURCE, value.focalLengthSource.coerceIn(0, 2))
        .putString(CAMERA_FOCAL_LENGTH_PRESET, value.focalLengthPreset.trim().take(32))
        .putBoolean(CAMERA_REMOVE_MODEL_LIMITATION, value.removeModelLimitation)
        .putString("palette_filter", value.paletteFilter)
        .putFloat("palette_tone", value.paletteTone.coerceIn(-1f, 1f))
        .putFloat("palette_color", value.paletteColor.coerceIn(-1f, 1f))
        .putFloat("palette_intensity", value.paletteIntensity.coerceIn(0f, 1f))
        .putFloat("palette_exposure", value.paletteExposure.coerceIn(-1f, 1f))
        .putFloat("palette_contrast", value.paletteContrast.coerceIn(0f, 2f))
        .putFloat("palette_saturation", value.paletteSaturation.coerceIn(0f, 1.8f))
        .putFloat("palette_warmth", value.paletteWarmth.coerceIn(-1f, 1f))
        .putBoolean("palette_skin_protection", value.skinToneProtection)
        .putFloat("palette_skin_protection_amount", value.skinToneProtectionAmount.coerceIn(0f, 1f))
        .apply()
}

/** Old presets retain the formerly unconditional 2090 feature unlocks. */
internal fun completeCameraFeaturePreset(values: JSONObject) {
    val enabled = values.optJSONObject(CAMERA_ALL_XIAOMI18_FEATURES)?.getBoolean("value") ?: true
    values.put(CAMERA_ALL_XIAOMI18_FEATURES, JSONObject().put("type", "boolean").put("value", enabled))
}
