// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.runtime.Composable

internal val RECOGNIZED_LOGO_SLOTS = setOf(
    APPEARANCE_SLOT_LOGO, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO,
    APPEARANCE_SLOT_STYLE3_LOGO, APPEARANCE_SLOT_STYLE4_LOGO, APPEARANCE_SLOT_CPU_ICON,
    APPEARANCE_SLOT_STYLE3_CPU_ICON, APPEARANCE_SLOT_STYLE3_CAMERA_BRAND, APPEARANCE_SLOT_AUDIO_LOGO,
)

/** 0 preserves the legacy outline mode; 1 preserves the imported artwork's own colors. */
internal fun DeviceStyleCustomization.logoKeepsColors(slot: String) = logoRecognition[slot.substringBefore(':')] == 1

@Composable
internal fun LogoRecognitionPreference(
    appearance: SettingsAppearanceSettings, slot: String,
    update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
) {
    val base = slot.substringBefore(':')
    OverlayDropdownPreference(
        title = tr("logo_recognition_type", "LOGO 识别类型"),
        items = listOf(tr("logo_outline", "单色轮廓"), tr("logo_original_colors", "保留原色")),
        selectedIndex = if (appearance.deviceStyleCustomization.logoKeepsColors(base)) 1 else 0,
        onSelectedIndexChange = { value -> update {
            it.copy(deviceStyleCustomization = it.deviceStyleCustomization.copy(logoRecognition = it.deviceStyleCustomization.logoRecognition + (base to value)))
        } },
    )
}
