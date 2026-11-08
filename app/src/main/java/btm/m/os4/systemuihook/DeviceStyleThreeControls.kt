// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.preference.SwitchPreference

private typealias AppearanceUpdate = (((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit)

@Composable
internal fun StyleThreeBackgroundControls(appearance: SettingsAppearanceSettings, update: AppearanceUpdate) {
    val s = appearance.deviceStyleCustomization
    fun save(transform: (DeviceStyleCustomization) -> DeviceStyleCustomization) = update { it.copy(deviceStyleCustomization = transform(it.deviceStyleCustomization)) }
    Group(tr("device3_background_card", "背景卡片")) {
        DeviceAppearanceAssetPreference(appearance, APPEARANCE_SLOT_STYLE3_BACKGROUND, tr("自定义背景", "自定义背景"), update)
        PortalSlider(tr("背景模糊度", "背景模糊度"), s.backgroundBlur, 0..100, 0, " dp") { v -> save { it.copy(backgroundBlur = v) } }
        PortalSlider(tr("背景左右偏移", "背景左右偏移"), s.backgroundX, -100..100, 0, " dp", true) { v -> save { it.copy(backgroundX = v) } }
        PortalSlider(tr("背景上下偏移", "背景上下偏移"), s.backgroundY, -100..100, 0, " dp", true) { v -> save { it.copy(backgroundY = v) } }
        DeviceRelativeScale(tr("背景缩放", "背景缩放"), s.backgroundScale) { v -> save { it.copy(backgroundScale = v) } }
    }
}

@Composable
internal fun StyleThreeLogoControls(appearance: SettingsAppearanceSettings, update: AppearanceUpdate) {
    val s = appearance.deviceStyleCustomization
    var editVersion by remember { mutableStateOf(false) }
    fun save(transform: (DeviceStyleCustomization) -> DeviceStyleCustomization) = update { it.copy(deviceStyleCustomization = transform(it.deviceStyleCustomization)) }
    Group(tr("device3_logo_version", "LOGO与版本号")) {
        DeviceAppearanceAssetPreference(appearance, APPEARANCE_SLOT_STYLE3_LOGO, tr("自定义LOGO", "自定义LOGO"), update)
        SharedViewArrowPreference(
            title = tr("device3_custom_version", "自定义版本号"),
            summary = s.versionText.ifBlank { tr("device3_version_default_hint", "留空时保持系统默认") },
            onClick = { editVersion = true },
        )
        OverlayDropdownPreference(
            title = tr("LOGO对齐方式", "LOGO对齐方式"),
            items = listOf(tr("左对齐", "左对齐"), tr("居中", "居中"), tr("右对齐", "右对齐")),
            selectedIndex = s.logoAlignment, onSelectedIndexChange = { v -> save { it.copy(logoAlignment = v) } },
        )
        PortalSlider(tr("device3_line_spacing", "LOGO与版本号行间距"), s.logoSpacing, -100..100, 0, " dp", true) { v -> save { it.copy(logoSpacing = v) } }
        PortalSlider(tr("LOGO左右偏移", "LOGO左右偏移"), s.logoX, -100..100, 0, " dp", true) { v -> save { it.copy(logoX = v) } }
        PortalSlider(tr("LOGO上下偏移", "LOGO上下偏移"), s.logoY, -100..100, 0, " dp", true) { v -> save { it.copy(logoY = v) } }
        DeviceRelativeScale(tr("LOGO缩放", "LOGO缩放"), s.logoScale) { v -> save { it.copy(logoScale = v) } }
        DeviceRelativeScale(tr("device3_version_scale", "版本号缩放"), s.versionScale) { v -> save { it.copy(versionScale = v) } }
        DeviceRelativeScale(tr("整体缩放", "整体缩放"), s.contentScale) { v -> save { it.copy(contentScale = v) } }
        DeviceColorMode(tr("LOGO颜色模式", "LOGO颜色模式"), s.logoColorMode) { v -> save { it.copy(logoColorMode = v) } }
        DeviceColorMode(tr("版本号颜色模式", "版本号颜色模式"), s.versionColorMode) { v -> save { it.copy(versionColorMode = v) } }
    }
    if (editVersion) DeviceProfileFieldDialog(
        title = tr("device3_custom_version", "自定义版本号"),
        current = s.versionText,
        inputLabel = tr("device3_version_default_hint", "留空时保持系统默认"),
        onDismiss = { editVersion = false },
        onSave = { text -> save { it.copy(versionText = text) }; editVersion = false },
    )
}

@Composable
internal fun StyleThreeUpdateControls(appearance: SettingsAppearanceSettings, update: AppearanceUpdate) {
    val s = appearance.deviceStyleCustomization
    fun save(transform: (DeviceStyleCustomization) -> DeviceStyleCustomization) = update { it.copy(deviceStyleCustomization = transform(it.deviceStyleCustomization)) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0) }
    var library by remember { mutableStateOf(SoftGlassSettingsStore(preferences).presets) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "soft_glass_custom_presets_v1" || key == null) library = SoftGlassSettingsStore(preferences).presets
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val presets = builtInSoftGlassPresets() + library
    Group(tr("device3_update_button", "更新状态按钮")) {
        SwitchPreference(title = tr("appearance_show_update_status", "显示更新状态按钮"), checked = appearance.style3UpdateStatusVisible,
            onCheckedChange = { v -> update { it.copy(style3UpdateStatusVisible = v) } })
        PortalAppearanceVisibility(appearance.style3UpdateStatusVisible) {
            Column {
                DeviceRelativeScale(tr("整体缩放", "整体缩放"), s.buttonOverallScale) { v -> save { it.copy(buttonOverallScale = v) } }
                DeviceRelativeScale(tr("文本缩放", "文本缩放"), s.buttonTextScale) { v -> save { it.copy(buttonTextScale = v) } }
                DeviceRelativeScale(tr("device3_button_background_height_scale", "按钮背景高度缩放"), s.buttonBackgroundWidthScale) { v -> save { it.copy(buttonBackgroundWidthScale = v) } }
                DeviceRelativeScale(tr("device3_button_background_width_scale", "按钮背景宽度缩放"), s.buttonBackgroundLengthScale) { v -> save { it.copy(buttonBackgroundLengthScale = v) } }
                PortalSlider(tr("device3_button_x", "按钮整体左右偏移"), s.buttonX, -100..100, 0, " dp", true) { v -> save { it.copy(buttonX = v) } }
                PortalSlider(tr("device3_button_y", "按钮整体上下偏移"), s.buttonY, -100..100, 0, " dp", true) { v -> save { it.copy(buttonY = v) } }
                OverlayDropdownPreference(title = tr("device3_button_material", "按钮材质"),
                    items = listOf(tr("纯色", "纯色"), tr("高级材质", "高级材质"), tr("柔光玻璃", "柔光玻璃")),
                    selectedIndex = s.buttonMaterial, onSelectedIndexChange = { v -> save { it.copy(buttonMaterial = v) } })
                PortalAppearanceVisibility(s.buttonMaterial == 0) {
                    Column {
                        ShortcutBackgroundColorPreference(tr("背景颜色", "背景颜色"), s.buttonPureColor) { v -> save { it.copy(buttonPureColor = v) } }
                        PortalSlider(tr("背景不透明度", "背景不透明度"), s.buttonPureOpacity, 0..100, 20, "%") { v -> save { it.copy(buttonPureOpacity = v) } }
                    }
                }
                PortalAppearanceVisibility(s.buttonMaterial == 1) {
                    Column {
                        PortalSlider(tr("背景不透明度", "背景不透明度"), s.buttonAdvancedOpacity, 0..100, 14, "%") { v -> save { it.copy(buttonAdvancedOpacity = v) } }
                        PortalSlider(tr("背景模糊度", "背景模糊度"), s.buttonAdvancedBlur, 0..120, 40, " px") { v -> save { it.copy(buttonAdvancedBlur = v) } }
                        SwitchPreference(title = tr("显示高光", "显示高光"), checked = s.buttonHighlight, onCheckedChange = { v -> save { it.copy(buttonHighlight = v) } })
                    }
                }
                PortalAppearanceVisibility(s.buttonMaterial == 2) {
                    OverlayDropdownPreference(title = tr("softGlassPresets", "柔光玻璃预设"),
                        items = presets.map { if (it.builtIn) tr(it.name, it.name) else it.name },
                        selectedIndex = presets.indexOfFirst { it.id == s.buttonGlassPresetId }.coerceAtLeast(0),
                        onSelectedIndexChange = { i -> presets.getOrNull(i)?.let { preset -> save { it.copy(buttonGlassPresetId = preset.id, buttonGlassPresetJson = SoftGlassPresetCodec.encode(preset)) } } })
                }
                DeviceColorMode(tr("文本颜色模式", "文本颜色模式"), s.buttonTextColorMode) { v -> save { it.copy(buttonTextColorMode = v) } }
                DeviceColorMode(tr("device3_button_color_mode", "按钮颜色模式"), s.buttonColorMode) { v -> save { it.copy(buttonColorMode = v) } }
            }
        }
    }
}

@Composable
internal fun StyleThreeSeparateDetailsControls(appearance: SettingsAppearanceSettings, update: AppearanceUpdate) {
    val s = appearance.deviceStyleCustomization
    var editCameraBrand by remember { mutableStateOf(false) }
    var editCameraBrandSuffix by remember { mutableStateOf(false) }
    fun save(transform: (DeviceStyleCustomization) -> DeviceStyleCustomization) =
        update { it.copy(deviceStyleCustomization = transform(it.deviceStyleCustomization)) }
    Group(tr("device3_separate_details", "分离式详细信息卡片")) {
        SwitchPreference(
            title = tr("device3_separate_details_enabled", "启用分离式详细信息卡片"),
            summary = tr("device3_separate_details_summary", "将详细参数拆分为独立卡片"),
            checked = s.separateDetailsEnabled,
            onCheckedChange = { enabled -> save { it.copy(separateDetailsEnabled = enabled) } },
        )
        PortalAppearanceVisibility(s.separateDetailsEnabled) {
            Column {
                OverlayDropdownPreference(
                    title = tr("device3_details_icon_style", "图标风格"),
                    items = listOf(tr("HyperOS", "HyperOS"), tr("ColorOS", "ColorOS")),
                    selectedIndex = s.separateDetailsIconStyle,
                    onSelectedIndexChange = { value -> save { it.copy(separateDetailsIconStyle = value) } },
                )
                OverlayDropdownPreference(
                    title = tr("device3_details_processor_icon", "处理器图标"),
                    items = listOf(
                        tr("device3_processor_default", "默认"),
                        tr("device3_processor_follow", "跟随全部参数替换"),
                        tr("骁龙", "骁龙"), tr("玄戒", "玄戒"), tr("天玑", "天玑"),
                        tr("Apple", "Apple"), tr("英特尔", "英特尔"), tr("英伟达", "英伟达"),
                        tr("AMD", "AMD"), tr("自定义", "自定义"),
                    ),
                    selectedIndex = s.separateProcessorIcon,
                    onSelectedIndexChange = { value -> save { it.copy(separateProcessorIcon = value) } },
                )
                PortalAppearanceVisibility(s.separateProcessorIcon == 9) {
                    DeviceAppearanceAssetPreference(
                        appearance = appearance,
                        slot = APPEARANCE_SLOT_STYLE3_CPU_ICON,
                        title = tr("device3_processor_custom_file", "自定义处理器图标"),
                        update = update,
                    )
                }
                OverlayDropdownPreference(
                    title = tr("device3_camera_brand", "影像联名"),
                    items = listOf(
                        tr("不显示", "不显示"), tr("device3_brand_leica", "徕卡"),
                        tr("device3_brand_zeiss", "蔡司"), tr("device3_brand_hasselblad", "哈苏"),
                        tr("device3_brand_fujifilm", "富士"), tr("device3_brand_sony", "索尼"),
                        tr("device3_brand_nikon", "尼康"), tr("device3_brand_canon", "佳能"),
                        tr("device3_brand_panasonic", "松下"), tr("device3_brand_custom_text", "自定义文本"),
                        tr("device3_brand_custom_image", "自定义图片"),
                    ),
                    selectedIndex = s.cameraBrand,
                    onSelectedIndexChange = { value -> save { it.copy(cameraBrand = value) } },
                )
                PortalAppearanceVisibility(s.cameraBrand == 9) {
                    SharedViewArrowPreference(
                        title = tr("device3_brand_custom_text", "自定义文本"),
                        summary = s.cameraBrandText.ifBlank { tr("device3_brand_text_hint", "点击输入影像联名文本") },
                        onClick = { editCameraBrand = true },
                    )
                }
                PortalAppearanceVisibility(s.cameraBrand == 10) {
                    DeviceAppearanceAssetPreference(appearance, APPEARANCE_SLOT_STYLE3_CAMERA_BRAND,
                        tr("device3_brand_custom_image", "自定义图片"), update)
                }
                PortalAppearanceVisibility(s.cameraBrandHasLogo) {
                    Column {
                        DeviceRelativeScale(tr("device3_brand_logo_scale", "影像联名标志缩放"), s.cameraBrandLogoScale) { v -> save { it.copy(cameraBrandLogoScale = v) } }
                        SharedViewArrowPreference(
                            title = tr("device3_brand_suffix", "标志后文本"),
                            summary = s.cameraBrandSuffix.ifBlank { tr("device3_brand_suffix_hint", "留空则不显示标志后文本") },
                            onClick = { editCameraBrandSuffix = true },
                        )
                        PortalAppearanceVisibility(s.cameraBrandSuffix.isNotBlank()) {
                            Column {
                                DeviceRelativeScale(tr("文本缩放", "文本缩放"), s.cameraBrandSuffixScale) { v -> save { it.copy(cameraBrandSuffixScale = v) } }
                                PortalSlider(tr("device3_brand_logo_text_spacing", "图标与文本间距"), s.cameraBrandLogoTextSpacing, 0..100, 8, " dp") { v -> save { it.copy(cameraBrandLogoTextSpacing = v) } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editCameraBrand && s.separateDetailsEnabled && s.cameraBrand == 9) {
        DeviceProfileFieldDialog(
            title = tr("device3_brand_custom_text", "自定义文本"),
            current = s.cameraBrandText,
            inputLabel = tr("device3_camera_brand", "影像联名"),
            onDismiss = { editCameraBrand = false },
            onSave = { text -> save { it.copy(cameraBrandText = text) }; editCameraBrand = false },
        )
    }
    if (editCameraBrandSuffix && s.separateDetailsEnabled && s.cameraBrandHasLogo) {
        DeviceProfileFieldDialog(
            title = tr("device3_brand_suffix", "标志后文本"),
            current = s.cameraBrandSuffix,
            inputLabel = tr("device3_brand_suffix_hint", "留空则不显示标志后文本"),
            onDismiss = { editCameraBrandSuffix = false },
            onSave = { text -> save { it.copy(cameraBrandSuffix = text) }; editCameraBrandSuffix = false },
        )
    }
}

@Composable
private fun DeviceRelativeScale(title: String, value: Int, save: (Int) -> Unit) = PortalSlider(title, value, -100..100, 0, "%", true, save)

@Composable
private fun DeviceColorMode(title: String, value: Int, save: (Int) -> Unit) {
    OverlayDropdownPreference(title = title, items = listOf(tr("系统默认", "系统默认"), tr("浅色", "浅色"), tr("深色", "深色")), selectedIndex = value, onSelectedIndexChange = save)
}
