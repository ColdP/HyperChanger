// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal const val DEVICE_STYLE_CUSTOMIZATION_KEY = "device_style_customization_v1"

/** Dedicated controls; persisted and synchronized by the existing appearance store. */
data class DeviceStyleCustomization(
    val independentAssets: List<Boolean> = List(4) { false },
    val backgroundBlur: Int = 0,
    val backgroundX: Int = 0,
    val backgroundY: Int = 0,
    val backgroundScale: Int = 0,
    val logoAlignment: Int = 1,
    val logoSpacing: Int = 0,
    val logoX: Int = 0,
    val logoY: Int = 0,
    val logoScale: Int = 0,
    val versionScale: Int = 0,
    val contentScale: Int = 0,
    val logoColorMode: Int = 0,
    val versionColorMode: Int = 0,
    val buttonOverallScale: Int = 0,
    val buttonTextScale: Int = 0,
    val buttonBackgroundLengthScale: Int = 0,
    val buttonBackgroundWidthScale: Int = 0,
    val buttonX: Int = 0,
    val buttonY: Int = 0,
    val buttonMaterial: Int = 1,
    val buttonPureColor: Int = 0xFFFFFFFF.toInt(),
    val buttonPureOpacity: Int = 20,
    val buttonAdvancedOpacity: Int = 14,
    val buttonAdvancedBlur: Int = 40,
    val buttonHighlight: Boolean = true,
    val buttonTextColorMode: Int = 0,
    val buttonColorMode: Int = 0,
    val buttonGlassPresetId: String = "builtin-custom-a",
    val buttonGlassPresetJson: String = "",
) {
    fun assetsForStyle(style: Int) = independentAssets.getOrElse(style.coerceIn(0, 3)) { false }
    fun withAssetsForStyle(style: Int, enabled: Boolean) = copy(independentAssets = List(4) { if (it == style) enabled else assetsForStyle(it) })

    internal fun encode(): String = JSONObject().apply {
        put("independentAssets", JSONArray(independentAssets))
        put("backgroundBlur", backgroundBlur)
        put("backgroundX", backgroundX)
        put("backgroundY", backgroundY)
        put("backgroundScale", backgroundScale)
        put("logoAlignment", logoAlignment)
        put("logoSpacing", logoSpacing)
        put("logoX", logoX)
        put("logoY", logoY)
        put("logoScale", logoScale)
        put("versionScale", versionScale)
        put("contentScale", contentScale)
        put("logoColorMode", logoColorMode)
        put("versionColorMode", versionColorMode)
        put("buttonOverallScale", buttonOverallScale)
        put("buttonTextScale", buttonTextScale)
        put("buttonBackgroundLengthScale", buttonBackgroundLengthScale)
        put("buttonBackgroundWidthScale", buttonBackgroundWidthScale)
        put("buttonX", buttonX)
        put("buttonY", buttonY)
        put("buttonMaterial", buttonMaterial)
        put("buttonPureColor", buttonPureColor)
        put("buttonPureOpacity", buttonPureOpacity)
        put("buttonAdvancedOpacity", buttonAdvancedOpacity)
        put("buttonAdvancedBlur", buttonAdvancedBlur)
        put("buttonHighlight", buttonHighlight)
        put("buttonTextColorMode", buttonTextColorMode)
        put("buttonColorMode", buttonColorMode)
        put("buttonGlassPresetId", buttonGlassPresetId)
        put("buttonGlassPresetJson", buttonGlassPresetJson)
    }.toString()

    internal fun glass(): SoftGlassSettings = runCatching {
        SoftGlassPresetCodec.decode(buttonGlassPresetJson).settings.copy(enabled = true)
    }.getOrElse { builtInSoftGlassPresets().firstOrNull { it.id == buttonGlassPresetId }?.settings?.copy(enabled = true)
        ?: builtInSoftGlassPresets().first().settings.copy(enabled = true) }

    companion object {
        internal fun decode(payload: String?, legacyAssets: Boolean = false): DeviceStyleCustomization = runCatching {
            val json = JSONObject(payload ?: "{}")
            val modes = json.optJSONArray("independentAssets")
            DeviceStyleCustomization(
                independentAssets = List(4) { modes?.optBoolean(it, legacyAssets) ?: legacyAssets },
                backgroundBlur = json.optInt("backgroundBlur", 0).coerceIn(0..100),
                backgroundX = json.optInt("backgroundX", 0).coerceIn(-100..100),
                backgroundY = json.optInt("backgroundY", 0).coerceIn(-100..100),
                backgroundScale = json.optInt("backgroundScale", 0).coerceIn(-100..100),
                logoAlignment = json.optInt("logoAlignment", 1).coerceIn(0..2),
                logoSpacing = json.optInt("logoSpacing", 0).coerceIn(-100..100),
                logoX = json.optInt("logoX", 0).coerceIn(-100..100),
                logoY = json.optInt("logoY", 0).coerceIn(-100..100),
                logoScale = json.optInt("logoScale", 0).coerceIn(-100..100),
                versionScale = json.optInt("versionScale", 0).coerceIn(-100..100),
                contentScale = json.optInt("contentScale", 0).coerceIn(-100..100),
                logoColorMode = json.optInt("logoColorMode", 0).coerceIn(0..2),
                versionColorMode = json.optInt("versionColorMode", 0).coerceIn(0..2),
                buttonOverallScale = json.optInt("buttonOverallScale", 0).coerceIn(-100..100),
                buttonTextScale = json.optInt("buttonTextScale", 0).coerceIn(-100..100),
                // Older presets scaled both background axes together.
                buttonBackgroundLengthScale = json.optInt("buttonBackgroundLengthScale", json.optInt("buttonScale", 0)).coerceIn(-100..100),
                buttonBackgroundWidthScale = json.optInt("buttonBackgroundWidthScale", json.optInt("buttonScale", 0)).coerceIn(-100..100),
                buttonX = json.optInt("buttonX", 0).coerceIn(-100..100),
                buttonY = json.optInt("buttonY", 0).coerceIn(-100..100),
                buttonMaterial = json.optInt("buttonMaterial", 1).coerceIn(0..2),
                buttonPureColor = json.optInt("buttonPureColor", 0xFFFFFFFF.toInt()),
                buttonPureOpacity = json.optInt("buttonPureOpacity", 20).coerceIn(0..100),
                buttonAdvancedOpacity = json.optInt("buttonAdvancedOpacity", 14).coerceIn(0..100),
                buttonAdvancedBlur = json.optInt("buttonAdvancedBlur", 40).coerceIn(0..120),
                buttonHighlight = json.optBoolean("buttonHighlight", true),
                buttonTextColorMode = json.optInt("buttonTextColorMode", 0).coerceIn(0..2),
                buttonColorMode = json.optInt("buttonColorMode", 0).coerceIn(0..2),
                buttonGlassPresetId = json.optString("buttonGlassPresetId", "builtin-custom-a"),
                buttonGlassPresetJson = json.optString("buttonGlassPresetJson", ""),
            )
        }.getOrElse { DeviceStyleCustomization(independentAssets = List(4) { legacyAssets }) }
    }
}

internal fun SharedPreferences.readDeviceStyleCustomization(): DeviceStyleCustomization =
    DeviceStyleCustomization.decode(getString(DEVICE_STYLE_CUSTOMIZATION_KEY, null), getBoolean("color_mode_assets_enabled", false))

internal fun SharedPreferences.Editor.putDeviceStyleCustomization(value: DeviceStyleCustomization): SharedPreferences.Editor =
    putString(DEVICE_STYLE_CUSTOMIZATION_KEY, value.encode())

internal fun appearanceAssetStyle(slot: String): Int = when (slot.substringBefore(':')) {
    APPEARANCE_SLOT_LOGO -> 0
    APPEARANCE_SLOT_DEVICE_IMAGE, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> 1
    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> 2
    else -> 3
}

internal fun deviceStyleScale(percent: Int) = (1f + percent.coerceIn(-100, 100) / 100f)
