// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

private typealias StyleFourUpdate = (((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit)
private fun styleFourSave(update: StyleFourUpdate, transform: (DeviceStyleFourSettings) -> DeviceStyleFourSettings) =
    update { it.copy(deviceStyleCustomization = it.deviceStyleCustomization.copy(styleFour = transform(it.deviceStyleCustomization.styleFour))) }

@Composable
internal fun StyleFourBannerControls(appearance: SettingsAppearanceSettings, update: StyleFourUpdate) {
    val s = appearance.deviceStyleCustomization.styleFour
    fun save(f: (DeviceStyleFourSettings) -> DeviceStyleFourSettings) = styleFourSave(update, f)
    Group(tr("device3_background_card", "背景卡片")) {
        DeviceAppearanceAssetPreference(appearance, APPEARANCE_SLOT_STYLE4_BACKGROUND, tr("自定义背景", "自定义背景"), update)
        FourScale(tr("背景缩放", "背景缩放"), s.backgroundScale) { v -> save { it.copy(backgroundScale = v) } }
        PortalSlider(tr("背景模糊度", "背景模糊度"), s.backgroundBlur, 0..100, 0, " dp") { v -> save { it.copy(backgroundBlur = v) } }
        PortalSlider(tr("背景左右偏移", "背景左右偏移"), s.backgroundX, -100..100, 0, " dp", true) { v -> save { it.copy(backgroundX = v) } }
        PortalSlider(tr("背景上下偏移", "背景上下偏移"), s.backgroundY, -100..100, 0, " dp", true) { v -> save { it.copy(backgroundY = v) } }
        FourScale(tr("device4_banner_height", "顶部卡片高度缩放"), s.bannerHeightScale) { v -> save { it.copy(bannerHeightScale = v) } }
        PortalSlider(tr("device4_corner_radius", "卡片圆角"), s.cornerRadius, 0..80, 24, " dp") { v -> save { it.copy(cornerRadius = v) } }
        PortalSlider(tr("device4_shadow", "卡片阴影"), s.shadow, 0..40, 12, " dp") { v -> save { it.copy(shadow = v) } }
    }
}

@Composable
internal fun StyleFourLogoControls(appearance: SettingsAppearanceSettings, update: StyleFourUpdate) {
    val s = appearance.deviceStyleCustomization.styleFour
    var edit by remember { mutableStateOf(false) }
    fun save(f: (DeviceStyleFourSettings) -> DeviceStyleFourSettings) = styleFourSave(update, f)
    Group(tr("device3_logo_version", "LOGO与版本号")) {
        DeviceAppearanceAssetPreference(appearance, APPEARANCE_SLOT_STYLE4_LOGO, tr("自定义LOGO", "自定义LOGO"), update)
        FourAlignment(tr("LOGO对齐方式", "LOGO对齐方式"), s.logoAlignment) { v -> save { it.copy(logoAlignment = v) } }
        FourScale(tr("LOGO缩放", "LOGO缩放"), s.logoScale) { v -> save { it.copy(logoScale = v) } }
        PortalSlider(tr("LOGO左右偏移", "LOGO左右偏移"), s.logoX, -100..100, 0, " dp", true) { v -> save { it.copy(logoX = v) } }
        PortalSlider(tr("LOGO上下偏移", "LOGO上下偏移"), s.logoY, -100..100, 0, " dp", true) { v -> save { it.copy(logoY = v) } }
        FourColorMode(tr("LOGO颜色模式", "LOGO颜色模式"), s.logoColorMode) { v -> save { it.copy(logoColorMode = v) } }
        SwitchPreference(title = tr("device4_show_version", "显示版本号"), checked = s.versionVisible, onCheckedChange = { v -> save { it.copy(versionVisible = v) } })
        PortalAppearanceVisibility(s.versionVisible) {
            Column {
                SharedViewArrowPreference(title = tr("device3_custom_version", "自定义版本号"), summary = s.versionText.ifBlank { tr("device3_version_default_hint", "留空时保持系统默认") }, onClick = { edit = true })
                FourScale(tr("device3_version_scale", "版本号缩放"), s.versionScale) { v -> save { it.copy(versionScale = v) } }
                PortalSlider(tr("device3_line_spacing", "LOGO与版本号行间距"), s.versionSpacing, 0..100, 8, " dp") { v -> save { it.copy(versionSpacing = v) } }
                FourColorMode(tr("版本号颜色模式", "版本号颜色模式"), s.versionColorMode) { v -> save { it.copy(versionColorMode = v) } }
            }
        }
    }
    if (edit) DeviceProfileFieldDialog(tr("device3_custom_version", "自定义版本号"), s.versionText, { edit = false }, { value -> save { it.copy(versionText = value) }; edit = false }, tr("device3_version_default_hint", "留空时保持系统默认"))
}

internal fun styleFourRowLabels() = listOf(
    tr("设备名称", "设备名称"), tr("处理器", "处理器"), tr("存储空间", "存储空间"), tr("运行内存", "运行内存"),
    tr("电池容量", "电池容量"), tr("屏幕尺寸", "屏幕尺寸"), tr("分辨率", "分辨率"), tr("device4_camera", "影像参数"),
)

@Composable
internal fun StyleFourDetailControls(appearance: SettingsAppearanceSettings, update: StyleFourUpdate) {
    val s = appearance.deviceStyleCustomization.styleFour
    fun save(f: (DeviceStyleFourSettings) -> DeviceStyleFourSettings) = styleFourSave(update, f)
    var editModel by remember { mutableStateOf(false) }
    var editRow by remember { mutableStateOf(-1) }
    val labels = styleFourRowLabels()
    Group(tr("device4_model_heading", "机型标题")) {
        SharedViewArrowPreference(title = tr("device4_custom_model", "自定义机型标题"), summary = s.modelText.ifBlank { tr("device3_version_default_hint", "留空时保持系统默认") }, onClick = { editModel = true })
        FourAlignment(tr("device4_alignment", "对齐方式"), s.modelAlignment) { v -> save { it.copy(modelAlignment = v) } }
        FourScale(tr("文本缩放", "文本缩放"), s.modelScale) { v -> save { it.copy(modelScale = v) } }
        FourColorMode(tr("文本颜色模式", "文本颜色模式"), s.modelColorMode) { v -> save { it.copy(modelColorMode = v) } }
        PortalSlider(tr("device4_model_top_gap", "标题上方间距"), s.modelTopGap, 0..120, 32, " dp") { v -> save { it.copy(modelTopGap = v) } }
        PortalSlider(tr("device4_model_bottom_gap", "标题下方间距"), s.modelBottomGap, 0..120, 32, " dp") { v -> save { it.copy(modelBottomGap = v) } }
    }
    Group(tr("device4_parameter_layout", "参数布局")) {
        PortalSlider(tr("device4_content_margin", "边距"), s.contentMargin, -32..64, 0, " dp", true) { v -> save { it.copy(contentMargin = v) } }
        FourAlignment(tr("device4_alignment", "对齐方式"), s.alignment) { v -> save { it.copy(alignment = v) } }
        FourScale(tr("device4_value_scale", "参数内容缩放"), s.valueScale) { v -> save { it.copy(valueScale = v) } }
        FourScale(tr("device4_label_scale", "参数标签缩放"), s.labelScale) { v -> save { it.copy(labelScale = v) } }
        FourScale(tr("device4_row_height", "行间距"), s.rowHeightScale) { v -> save { it.copy(rowHeightScale = v) } }
        PortalSlider(tr("device4_row_gap", "额外行间距"), s.rowGap, 0..80, 0, " dp") { v -> save { it.copy(rowGap = v) } }
        PortalSlider(tr("device4_text_spacing", "内容与标签间距"), s.textSpacing, 0..60, 8, " dp") { v -> save { it.copy(textSpacing = v) } }
        FourColorMode(tr("device4_parameter_color", "参数颜色模式"), s.parameterColorMode) { v -> save { it.copy(parameterColorMode = v) } }
        SwitchPreference(title = tr("device4_pair_memory_storage", "运行内存与存储空间并排显示"), checked = s.pairMemoryStorage, onCheckedChange = { v -> save { it.copy(pairMemoryStorage = v) } })
        SwitchPreference(title = tr("device4_dividers", "显示分隔线"), checked = s.dividersVisible, onCheckedChange = { v -> save { it.copy(dividersVisible = v) } })
        SwitchPreference(title = tr("device4_markers", "显示参数矩形标"), checked = s.markersVisible, onCheckedChange = { v -> save { it.copy(markersVisible = v) } })
        PortalAppearanceVisibility(s.markersVisible) {
            Column {
                ShortcutBackgroundColorPreference(tr("device4_marker_color", "矩形标颜色"), s.markerColor) { v -> save { it.copy(markerColor = v) } }
                OverlayDropdownPreference(
                    title = tr("device4_marker_position", "矩形标位置"),
                    items = listOf(tr("device4_marker_after", "参数后面"), tr("device4_marker_before", "参数前面")),
                    selectedIndex = s.markerPosition,
                    onSelectedIndexChange = { v -> save { it.copy(markerPosition = v) } },
                )
                PortalSlider(tr("device4_marker_width", "矩形标宽度"), s.markerWidth, 1..20, 3, " dp") { v -> save { it.copy(markerWidth = v) } }
                PortalSlider(tr("device4_marker_height", "矩形标高度"), s.markerHeight, 1..60, 13, " dp") { v -> save { it.copy(markerHeight = v) } }
                PortalSlider(tr("device4_marker_spacing", "矩形标间距"), s.markerSpacing, 0..60, 6, " dp") { v -> save { it.copy(markerSpacing = v) } }
            }
        }
        labels.forEachIndexed { index, label ->
            SwitchPreference(title = label, checked = s.visibleRows[index], onCheckedChange = { v -> save { it.copy(visibleRows = it.visibleRows.mapIndexed { i, old -> if (i == index) v else old }) } })
            PortalAppearanceVisibility(s.visibleRows[index]) {
                Column {
                    SharedViewArrowPreference(title = tr("device4_custom_value", "自定义参数内容"), summary = s.rowValues[index].ifBlank { tr("device3_version_default_hint", "留空时保持系统默认") }, onClick = { editRow = index })
                }
            }
        }
    }
    if (editModel) DeviceProfileFieldDialog(tr("device4_custom_model", "自定义机型标题"), s.modelText, { editModel = false }, { v -> save { it.copy(modelText = v) }; editModel = false }, tr("device3_version_default_hint", "留空时保持系统默认"))
    if (editRow in 0..7) {
        val index = editRow
        DeviceProfileFieldDialog(
            title = labels[index], current = s.rowValues[index],
            onDismiss = { editRow = -1 }, inputLabel = tr("device3_version_default_hint", "留空时保持系统默认"),
            onSave = { v -> save { old -> old.copy(rowValues = old.rowValues.mapIndexed { i, value -> if (i == index) v else value }) }; editRow = -1 },
        )
    }
}

@Composable private fun FourScale(title: String, value: Int, save: (Int) -> Unit) = PortalSlider(title, value, -100..100, 0, "%", true, save)
@Composable private fun FourAlignment(title: String, value: Int, save: (Int) -> Unit) = OverlayDropdownPreference(title = title, items = listOf(tr("左对齐", "左对齐"), tr("居中", "居中"), tr("右对齐", "右对齐")), selectedIndex = value, onSelectedIndexChange = save)
@Composable private fun FourColorMode(title: String, value: Int, save: (Int) -> Unit) = OverlayDropdownPreference(title = title, items = listOf(tr("系统默认", "系统默认"), tr("浅色", "浅色"), tr("深色", "深色")), selectedIndex = value, onSelectedIndexChange = save)
