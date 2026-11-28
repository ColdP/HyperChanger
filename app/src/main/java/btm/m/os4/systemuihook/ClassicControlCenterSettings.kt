// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val CLASSIC_CC_PREFIX = "classic_cc_v1_"
internal const val CLASSIC_CC_SOFT_GLASS_PREFIX = "${CLASSIC_CC_PREFIX}sg_"
internal const val CLASSIC_CC_CLEAR_FROST_PREFIX = "${CLASSIC_CC_PREFIX}cf_"
internal const val KEY_CLASSIC_CC_ENABLED = "${CLASSIC_CC_PREFIX}enabled"
internal const val KEY_CLASSIC_CC_UNLOCK_SYSTEMUI = "${CLASSIC_CC_PREFIX}unlock_systemui"
internal const val KEY_CLASSIC_CC_PRESETS = "classic_cc_custom_presets_v1"
internal const val KEY_CLASSIC_CC_APPLIED_PRESET = "classic_cc_applied_preset_v1"
internal const val CLASSIC_CC_MANAGED_SURFACE_TAG_KEY = 0x7F0A6C18

internal const val CLASSIC_CC_SURFACE_DEFAULT = 0
internal const val CLASSIC_CC_SURFACE_SOLID = 1
internal const val CLASSIC_CC_SURFACE_CLEAR_FROST = 2
internal const val CLASSIC_CC_SURFACE_SOFT_GLASS = 3
internal const val CLASSIC_CC_SURFACE_FOLLOW_SYSTEM = 4

internal const val CLASSIC_CC_HANDLE_DEFAULT = 0
internal const val CLASSIC_CC_HANDLE_PILL = 1
internal const val CLASSIC_CC_HANDLE_HIDDEN = 2

internal const val CLASSIC_CC_INDICATOR_DEFAULT = 0
internal const val CLASSIC_CC_INDICATOR_PILL = 1
internal const val CLASSIC_CC_INDICATOR_HIDDEN = 2

internal const val CLASSIC_CC_TILE_STYLE_SQUIRCLE = 0
internal const val CLASSIC_CC_TILE_STYLE_CIRCLE = 1
internal const val CLASSIC_CC_TILE_STYLE_PILL_CARD = 2
internal const val CLASSIC_CC_TILE_STYLE_GLASS_CARD = 3

internal const val CLASSIC_CC_ACCENT_MONET = 0
internal const val CLASSIC_CC_ACCENT_HYPER_BLUE = 1
internal const val CLASSIC_CC_ACCENT_CUSTOM = 2

internal const val CLASSIC_CC_BRIGHTNESS_POS_BOTTOM = 0
internal const val CLASSIC_CC_BRIGHTNESS_POS_TOP = 1

internal const val CLASSIC_CC_BRIGHTNESS_COLOR_ACCENT = 0
internal const val CLASSIC_CC_BRIGHTNESS_COLOR_WHITE = 1
internal const val CLASSIC_CC_BRIGHTNESS_COLOR_CUSTOM = 2

internal const val CLASSIC_CC_DATA_USAGE_SYSTEM = 0
internal const val CLASSIC_CC_DATA_USAGE_HIDE = 1

internal const val CLASSIC_CC_DEFAULT_PANEL_LIGHT_BG = 0xD8F6F8FC.toInt()
internal const val CLASSIC_CC_DEFAULT_PANEL_DARK_BG = 0xCC1C1E24.toInt()
internal const val CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG = 0xFF0D84FF.toInt()
internal const val CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_BG = 0x1A000000
internal const val CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_BG = 0x22FFFFFF
internal const val CLASSIC_CC_DEFAULT_TILE_ACTIVE_ICON = 0xFFFFFFFF.toInt()
internal const val CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_ICON = 0xDE1A1C20.toInt()
internal const val CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_ICON = 0xE6FFFFFF.toInt()
internal const val CLASSIC_CC_DEFAULT_BRIGHTNESS_FILL = 0xFF0D84FF.toInt()
internal const val CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_LIGHT = 0x1A000000
internal const val CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_DARK = 0x26FFFFFF
internal const val CLASSIC_CC_DEFAULT_BRIGHTNESS_ICON = 0xFFFFFFFF.toInt()

internal data class ClassicControlCenterSettings(
    // General & unlock
    val enabled: Boolean = false,
    val unlockClassicInSystemUi: Boolean = false,

    // Tab 1: Appearance & Material
    val panelSurfaceMode: Int = CLASSIC_CC_SURFACE_FOLLOW_SYSTEM,
    val useGlobalSoftGlass: Boolean = true,
    val softGlass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
    val useGlobalClearFrost: Boolean = false,
    val clearFrost: ClearFrostSettings = ClearFrostSettings(
        enabled = true,
        shadowEnabled = false,
    ),
    val shadeBackgroundCustomEnabled: Boolean = false,
    val shadeBackgroundBlur: Int = 100,
    val shadeGlassBlur: Int = 100,
    val shadeBackgroundScale: Int = 100,
    val shadeBackgroundOpacity: Int = 100,
    val panelCornerRadiusDp: Int = 28,
    val panelBlurRadius: Int = 72,
    val panelOpacity: Int = 72,
    val panelLightBgColor: Int = CLASSIC_CC_DEFAULT_PANEL_LIGHT_BG,
    val panelDarkBgColor: Int = CLASSIC_CC_DEFAULT_PANEL_DARK_BG,
    val panelStrokeEnabled: Boolean = true,
    val panelStrokeWidthDp: Float = 1.0f,
    val panelStrokeAlpha: Int = 28,
    val panelShadowEnabled: Boolean = true,
    val panelSideMarginDp: Int = 12,
    val panelHorizontalPaddingDp: Int = 16,
    val panelTopPaddingDp: Int = 16,
    val panelBottomPaddingDp: Int = 8,
    val panelNotificationGapDp: Int = 12,
    val modernizeDetailAndCustomizer: Boolean = true,
    val dragHandleStyle: Int = CLASSIC_CC_HANDLE_PILL,
    val dragHandleWidthDp: Int = 36,
    val dragHandleHeightDp: Int = 4,
    val pageIndicatorStyle: Int = CLASSIC_CC_INDICATOR_PILL,

    // Tab 2: Tiles & Layout
    val qqsTileCount: Int = 5,
    val qsColumnsPortrait: Int = 4,
    val qsRowsPortrait: Int = 3,
    val qsColumnsLandscape: Int = 6,
    val qsRowsLandscape: Int = 2,
    val tileStyle: Int = CLASSIC_CC_TILE_STYLE_SQUIRCLE,
    val tileHeightDp: Int = 92,
    val tileHorizontalSpacingDp: Int = 8,
    val tileTopMarginDp: Int = 16,
    val tileIconBgSizeDp: Int = 54,
    val tileCornerRadiusDp: Int = 18,
    val tileIconSizeDp: Int = 24,
    val showTileLabel: Boolean = true,
    val showTileSecondaryLabel: Boolean = true,
    val tileLabelSizeSp: Float = 11.5f,
    val tileLabelTopGapDp: Int = 7,
    val tileLabelBold: Boolean = false,
    val tileTouchFeedbackScale: Int = 92,
    val tileAccentMode: Int = CLASSIC_CC_ACCENT_HYPER_BLUE,
    val tileActiveBgColor: Int = CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG,
    val tileInactiveLightBgColor: Int = CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_BG,
    val tileInactiveDarkBgColor: Int = CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_BG,
    val tileActiveIconColor: Int = CLASSIC_CC_DEFAULT_TILE_ACTIVE_ICON,
    val tileInactiveLightIconColor: Int = CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_ICON,
    val tileInactiveDarkIconColor: Int = CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_ICON,
    val tileBorderEnabled: Boolean = false,

    // Tab 3: Sliders & Features
    val brightnessSliderVisible: Boolean = true,
    val brightnessSliderPosition: Int = CLASSIC_CC_BRIGHTNESS_POS_BOTTOM,
    val brightnessExpandedOnly: Boolean = false,
    val brightnessHeightDp: Int = 42,
    val brightnessCornerRadiusDp: Int = 21,
    val brightnessHorizontalMarginDp: Int = 16,
    val brightnessTopMarginDp: Int = 12,
    val brightnessBottomMarginDp: Int = 8,
    val brightnessShowPercent: Boolean = true,
    val brightnessShowAutoButton: Boolean = true,
    val brightnessColorMode: Int = CLASSIC_CC_BRIGHTNESS_COLOR_WHITE,
    val brightnessFillColor: Int = CLASSIC_CC_DEFAULT_BRIGHTNESS_FILL,
    val brightnessTrackLightColor: Int = CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_LIGHT,
    val brightnessTrackDarkColor: Int = CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_DARK,
    val brightnessIconColor: Int = CLASSIC_CC_DEFAULT_BRIGHTNESS_ICON,
    val volumeSliderEnabled: Boolean = false,
    val volumeSliderShowInCollapsed: Boolean = false,
    val volumeSliderShowPercent: Boolean = true,
    val volumeSliderShowMuteButton: Boolean = true,
    val volumeSliderHeightDp: Int = 38,
    val dataUsageVisibilityMode: Int = CLASSIC_CC_DATA_USAGE_SYSTEM,
    val dataUsageHideDivider: Boolean = true,
    val dataUsageModernPill: Boolean = true,

    // Tab 4: Header & Actions
    val headerUseModernFont: Boolean = true,
    val headerClockSizeSp: Float = 36f,
    val headerClockWeight: Int = 3,
    val headerDateSizeSp: Float = 13f,
    val headerHeightOffsetDp: Int = 0,
    val headerHideCarrier: Boolean = false,
    val headerCompactMode: Boolean = false,
    val headerShowSettingsButton: Boolean = true,
    val headerShowEditButton: Boolean = true,
    val headerShowPowerButton: Boolean = false,
    val headerActionPillStyle: Boolean = true,
    val headerActionButtonSizeDp: Int = 34,
    val smoothOpenAnimation: Boolean = true,
) {
    fun normalized(): ClassicControlCenterSettings = copy(
        panelSurfaceMode = panelSurfaceMode.coerceIn(CLASSIC_CC_SURFACE_DEFAULT, CLASSIC_CC_SURFACE_FOLLOW_SYSTEM),
        softGlass = softGlass.normalized(),
        clearFrost = clearFrost.normalized(),
        shadeBackgroundBlur = shadeBackgroundBlur.coerceIn(0, 200),
        shadeGlassBlur = shadeGlassBlur.coerceIn(0, 200),
        shadeBackgroundScale = shadeBackgroundScale.coerceIn(0, 200),
        shadeBackgroundOpacity = shadeBackgroundOpacity.coerceIn(0, 200),
        panelCornerRadiusDp = panelCornerRadiusDp.coerceIn(0, 48),
        panelBlurRadius = panelBlurRadius.coerceIn(0, 120),
        panelOpacity = panelOpacity.coerceIn(0, 100),
        panelStrokeWidthDp = panelStrokeWidthDp.coerceIn(0f, 3f),
        panelStrokeAlpha = panelStrokeAlpha.coerceIn(0, 100),
        panelSideMarginDp = panelSideMarginDp.coerceIn(0, 32),
        panelHorizontalPaddingDp = panelHorizontalPaddingDp.coerceIn(0, 32),
        panelTopPaddingDp = panelTopPaddingDp.coerceIn(0, 28),
        panelBottomPaddingDp = panelBottomPaddingDp.coerceIn(0, 28),
        panelNotificationGapDp = panelNotificationGapDp.coerceIn(0, 32),
        dragHandleStyle = dragHandleStyle.coerceIn(CLASSIC_CC_HANDLE_DEFAULT, CLASSIC_CC_HANDLE_HIDDEN),
        dragHandleWidthDp = dragHandleWidthDp.coerceIn(20, 80),
        dragHandleHeightDp = dragHandleHeightDp.coerceIn(2, 8),
        pageIndicatorStyle = pageIndicatorStyle.coerceIn(CLASSIC_CC_INDICATOR_DEFAULT, CLASSIC_CC_INDICATOR_HIDDEN),
        qqsTileCount = qqsTileCount.coerceIn(3, 7),
        qsColumnsPortrait = qsColumnsPortrait.coerceIn(2, 6),
        qsRowsPortrait = qsRowsPortrait.coerceIn(2, 5),
        qsColumnsLandscape = qsColumnsLandscape.coerceIn(4, 8),
        qsRowsLandscape = qsRowsLandscape.coerceIn(1, 3),
        tileStyle = tileStyle.coerceIn(CLASSIC_CC_TILE_STYLE_SQUIRCLE, CLASSIC_CC_TILE_STYLE_GLASS_CARD),
        tileHeightDp = tileHeightDp.coerceIn(52, 120),
        tileHorizontalSpacingDp = tileHorizontalSpacingDp.coerceIn(0, 24),
        tileTopMarginDp = tileTopMarginDp.coerceIn(0, 28),
        tileIconBgSizeDp = tileIconBgSizeDp.coerceIn(36, 72),
        tileCornerRadiusDp = tileCornerRadiusDp.coerceIn(0, 36),
        tileIconSizeDp = tileIconSizeDp.coerceIn(14, 34),
        tileLabelSizeSp = tileLabelSizeSp.coerceIn(9f, 16f),
        tileLabelTopGapDp = tileLabelTopGapDp.coerceIn(0, 14),
        tileTouchFeedbackScale = tileTouchFeedbackScale.coerceIn(82, 100),
        tileAccentMode = tileAccentMode.coerceIn(CLASSIC_CC_ACCENT_MONET, CLASSIC_CC_ACCENT_CUSTOM),
        brightnessSliderPosition = brightnessSliderPosition.coerceIn(CLASSIC_CC_BRIGHTNESS_POS_BOTTOM, CLASSIC_CC_BRIGHTNESS_POS_TOP),
        brightnessHeightDp = brightnessHeightDp.coerceIn(24, 56),
        brightnessCornerRadiusDp = brightnessCornerRadiusDp.coerceIn(4, 28),
        brightnessHorizontalMarginDp = brightnessHorizontalMarginDp.coerceIn(0, 28),
        brightnessTopMarginDp = brightnessTopMarginDp.coerceIn(0, 24),
        brightnessBottomMarginDp = brightnessBottomMarginDp.coerceIn(0, 24),
        brightnessColorMode = brightnessColorMode.coerceIn(CLASSIC_CC_BRIGHTNESS_COLOR_ACCENT, CLASSIC_CC_BRIGHTNESS_COLOR_CUSTOM),
        volumeSliderHeightDp = volumeSliderHeightDp.coerceIn(24, 56),
        dataUsageVisibilityMode = dataUsageVisibilityMode.coerceIn(CLASSIC_CC_DATA_USAGE_SYSTEM, CLASSIC_CC_DATA_USAGE_HIDE),
        headerClockSizeSp = headerClockSizeSp.coerceIn(20f, 52f),
        headerClockWeight = headerClockWeight.coerceIn(0, 4),
        headerDateSizeSp = headerDateSizeSp.coerceIn(10f, 20f),
        headerHeightOffsetDp = headerHeightOffsetDp.coerceIn(-24, 40),
        headerActionButtonSizeDp = headerActionButtonSizeDp.coerceIn(26, 44),
    )

    fun resetParameters(): ClassicControlCenterSettings =
        ClassicControlCenterSettings(
            enabled = enabled,
            unlockClassicInSystemUi = unlockClassicInSystemUi,
        )

    fun matchesParameters(other: ClassicControlCenterSettings): Boolean =
        normalized().copy(enabled = false, unlockClassicInSystemUi = false) ==
            other.normalized().copy(enabled = false, unlockClassicInSystemUi = false)
}

internal fun resolveClassicControlCenterSoftGlass(
    p: SharedPreferences,
    settings: ClassicControlCenterSettings,
): SoftGlassSettings = (if (settings.useGlobalSoftGlass) readSoftGlassSettings(p) else settings.softGlass)
    .copy(enabled = true)
    .normalized()

internal fun resolveClassicControlCenterClearFrost(
    p: SharedPreferences,
    settings: ClassicControlCenterSettings,
): ClearFrostSettings = (if (settings.useGlobalClearFrost) readClearFrostSettings(p) else settings.clearFrost)
    .copy(enabled = true)
    .normalized()

private fun readScopedClearFrost(p: SharedPreferences, prefix: String): ClearFrostSettings {
    val d = ClearFrostSettings(enabled = true, shadowEnabled = false)
    return ClearFrostSettings(
        enabled = true,
        lightStyle = p.getString("${prefix}light_style", d.lightStyle) ?: d.lightStyle,
        darkStyle = p.getString("${prefix}dark_style", d.darkStyle) ?: d.darkStyle,
        customLightColor1 = p.getInt("${prefix}custom_light_1", d.customLightColor1),
        customLightColor2 = p.getInt("${prefix}custom_light_2", d.customLightColor2),
        customLightColor3 = p.getInt("${prefix}custom_light_3", d.customLightColor3),
        customDarkColor1 = p.getInt("${prefix}custom_dark_1", d.customDarkColor1),
        customDarkColor2 = p.getInt("${prefix}custom_dark_2", d.customDarkColor2),
        customDarkColor3 = p.getInt("${prefix}custom_dark_3", d.customDarkColor3),
        elementOpacity = p.getInt("${prefix}element_opacity", d.elementOpacity),
        strokeEnabled = p.getBoolean("${prefix}stroke_enabled", d.strokeEnabled),
        strokePreset = p.getString("${prefix}stroke_preset", d.strokePreset) ?: d.strokePreset,
        strokeNormalWidth = p.getFloat("${prefix}stroke_normal_width", d.strokeNormalWidth),
        strokeBloomWidth = p.getFloat("${prefix}stroke_bloom_width", d.strokeBloomWidth),
        strokeBloomDegree = p.getInt("${prefix}stroke_bloom_degree", d.strokeBloomDegree),
        strokeBloomAlpha = p.getInt("${prefix}stroke_bloom_alpha", d.strokeBloomAlpha),
        strokeSource1Alpha = p.getInt("${prefix}stroke_source1_alpha", d.strokeSource1Alpha),
        strokeSource2Alpha = p.getInt("${prefix}stroke_source2_alpha", d.strokeSource2Alpha),
        shadowEnabled = p.getBoolean("${prefix}shadow_enabled", d.shadowEnabled),
        shadowAlpha = p.getInt("${prefix}shadow_alpha", d.shadowAlpha),
        shadowOffsetY = p.getInt("${prefix}shadow_offset_y", d.shadowOffsetY),
        shadowRadius = p.getInt("${prefix}shadow_radius", d.shadowRadius),
    ).normalized()
}

private fun SharedPreferences.Editor.writeScopedClearFrost(prefix: String, cf: ClearFrostSettings) {
    val s = cf.normalized()
    putString("${prefix}light_style", s.lightStyle)
    putString("${prefix}dark_style", s.darkStyle)
    putInt("${prefix}custom_light_1", s.customLightColor1)
    putInt("${prefix}custom_light_2", s.customLightColor2)
    putInt("${prefix}custom_light_3", s.customLightColor3)
    putInt("${prefix}custom_dark_1", s.customDarkColor1)
    putInt("${prefix}custom_dark_2", s.customDarkColor2)
    putInt("${prefix}custom_dark_3", s.customDarkColor3)
    putInt("${prefix}element_opacity", s.elementOpacity)
    putBoolean("${prefix}stroke_enabled", s.strokeEnabled)
    putString("${prefix}stroke_preset", s.strokePreset)
    putFloat("${prefix}stroke_normal_width", s.strokeNormalWidth)
    putFloat("${prefix}stroke_bloom_width", s.strokeBloomWidth)
    putInt("${prefix}stroke_bloom_degree", s.strokeBloomDegree)
    putInt("${prefix}stroke_bloom_alpha", s.strokeBloomAlpha)
    putInt("${prefix}stroke_source1_alpha", s.strokeSource1Alpha)
    putInt("${prefix}stroke_source2_alpha", s.strokeSource2Alpha)
    putBoolean("${prefix}shadow_enabled", s.shadowEnabled)
    putInt("${prefix}shadow_alpha", s.shadowAlpha)
    putInt("${prefix}shadow_offset_y", s.shadowOffsetY)
    putInt("${prefix}shadow_radius", s.shadowRadius)
}

private fun SharedPreferences.Editor.writeScopedSoftGlass(prefix: String, sg: SoftGlassSettings) {
    val s = sg.normalized()
    putBoolean("${prefix}enabled", true)
    putInt("${prefix}refraction_blur", s.refractionBlur)
    putInt("${prefix}blur_percent", s.backgroundBlur)
    putInt("${prefix}scale_percent", s.backgroundScale)
    putInt("${prefix}opacity_percent", s.backgroundOpacity)
    softGlassParameters.forEach { def ->
        val key = if (def.key == "edge_feather") "edge_feather_inverse" else def.key
        putInt("$prefix$key", s.adjustments[def.key] ?: if (def.key == "darker") -100 else 0)
    }
}

internal fun readClassicControlCenterSettings(p: SharedPreferences): ClassicControlCenterSettings {
    if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return ClassicControlCenterSettings()
    val d = ClassicControlCenterSettings()
    return ClassicControlCenterSettings(
        enabled = p.getBoolean(KEY_CLASSIC_CC_ENABLED, d.enabled),
        unlockClassicInSystemUi = p.getBoolean(KEY_CLASSIC_CC_UNLOCK_SYSTEMUI, d.unlockClassicInSystemUi),
        panelSurfaceMode = p.getInt("${CLASSIC_CC_PREFIX}panel_surface_mode", d.panelSurfaceMode),
        useGlobalSoftGlass = p.getBoolean("${CLASSIC_CC_PREFIX}use_global_soft_glass", d.useGlobalSoftGlass),
        softGlass = readSoftGlassSettings(p, CLASSIC_CC_SOFT_GLASS_PREFIX),
        useGlobalClearFrost = p.getBoolean("${CLASSIC_CC_PREFIX}use_global_clear_frost", d.useGlobalClearFrost),
        clearFrost = readScopedClearFrost(p, CLASSIC_CC_CLEAR_FROST_PREFIX),
        shadeBackgroundCustomEnabled = p.getBoolean("${CLASSIC_CC_PREFIX}shade_bg_custom", d.shadeBackgroundCustomEnabled),
        shadeBackgroundBlur = p.getInt("${CLASSIC_CC_PREFIX}shade_bg_blur", d.shadeBackgroundBlur),
        shadeGlassBlur = p.getInt("${CLASSIC_CC_PREFIX}shade_glass_blur", d.shadeGlassBlur),
        shadeBackgroundScale = p.getInt("${CLASSIC_CC_PREFIX}shade_bg_scale", d.shadeBackgroundScale),
        shadeBackgroundOpacity = p.getInt("${CLASSIC_CC_PREFIX}shade_bg_opacity", d.shadeBackgroundOpacity),
        panelCornerRadiusDp = p.getInt("${CLASSIC_CC_PREFIX}panel_corner_radius", d.panelCornerRadiusDp),
        panelBlurRadius = p.getInt("${CLASSIC_CC_PREFIX}panel_blur_radius", d.panelBlurRadius),
        panelOpacity = p.getInt("${CLASSIC_CC_PREFIX}panel_opacity", d.panelOpacity),
        panelLightBgColor = p.getInt("${CLASSIC_CC_PREFIX}panel_light_bg", d.panelLightBgColor),
        panelDarkBgColor = p.getInt("${CLASSIC_CC_PREFIX}panel_dark_bg", d.panelDarkBgColor),
        panelStrokeEnabled = p.getBoolean("${CLASSIC_CC_PREFIX}panel_stroke_enabled", d.panelStrokeEnabled),
        panelStrokeWidthDp = p.getFloat("${CLASSIC_CC_PREFIX}panel_stroke_width", d.panelStrokeWidthDp),
        panelStrokeAlpha = p.getInt("${CLASSIC_CC_PREFIX}panel_stroke_alpha", d.panelStrokeAlpha),
        panelShadowEnabled = p.getBoolean("${CLASSIC_CC_PREFIX}panel_shadow_enabled", d.panelShadowEnabled),
        panelSideMarginDp = p.getInt("${CLASSIC_CC_PREFIX}panel_side_margin", d.panelSideMarginDp),
        panelHorizontalPaddingDp = p.getInt("${CLASSIC_CC_PREFIX}panel_h_padding", d.panelHorizontalPaddingDp),
        panelTopPaddingDp = p.getInt("${CLASSIC_CC_PREFIX}panel_top_padding", d.panelTopPaddingDp),
        panelBottomPaddingDp = p.getInt("${CLASSIC_CC_PREFIX}panel_bottom_padding", d.panelBottomPaddingDp),
        panelNotificationGapDp = p.getInt("${CLASSIC_CC_PREFIX}panel_notif_gap", d.panelNotificationGapDp),
        modernizeDetailAndCustomizer = p.getBoolean("${CLASSIC_CC_PREFIX}modernize_detail", d.modernizeDetailAndCustomizer),
        dragHandleStyle = p.getInt("${CLASSIC_CC_PREFIX}drag_handle_style", d.dragHandleStyle),
        dragHandleWidthDp = p.getInt("${CLASSIC_CC_PREFIX}drag_handle_width", d.dragHandleWidthDp),
        dragHandleHeightDp = p.getInt("${CLASSIC_CC_PREFIX}drag_handle_height", d.dragHandleHeightDp),
        pageIndicatorStyle = p.getInt("${CLASSIC_CC_PREFIX}page_indicator_style", d.pageIndicatorStyle),
        qqsTileCount = p.getInt("${CLASSIC_CC_PREFIX}qqs_tile_count", d.qqsTileCount),
        qsColumnsPortrait = p.getInt("${CLASSIC_CC_PREFIX}qs_cols_port", d.qsColumnsPortrait),
        qsRowsPortrait = p.getInt("${CLASSIC_CC_PREFIX}qs_rows_port", d.qsRowsPortrait),
        qsColumnsLandscape = p.getInt("${CLASSIC_CC_PREFIX}qs_cols_land", d.qsColumnsLandscape),
        qsRowsLandscape = p.getInt("${CLASSIC_CC_PREFIX}qs_rows_land", d.qsRowsLandscape),
        tileStyle = p.getInt("${CLASSIC_CC_PREFIX}tile_style", d.tileStyle),
        tileHeightDp = p.getInt("${CLASSIC_CC_PREFIX}tile_height", d.tileHeightDp),
        tileHorizontalSpacingDp = p.getInt("${CLASSIC_CC_PREFIX}tile_h_spacing", d.tileHorizontalSpacingDp),
        tileTopMarginDp = p.getInt("${CLASSIC_CC_PREFIX}tile_top_margin", d.tileTopMarginDp),
        tileIconBgSizeDp = p.getInt("${CLASSIC_CC_PREFIX}tile_icon_bg_size", d.tileIconBgSizeDp),
        tileCornerRadiusDp = p.getInt("${CLASSIC_CC_PREFIX}tile_corner_radius", d.tileCornerRadiusDp),
        tileIconSizeDp = p.getInt("${CLASSIC_CC_PREFIX}tile_icon_size", d.tileIconSizeDp),
        showTileLabel = p.getBoolean("${CLASSIC_CC_PREFIX}show_tile_label", d.showTileLabel),
        showTileSecondaryLabel = p.getBoolean("${CLASSIC_CC_PREFIX}show_tile_secondary_label", d.showTileSecondaryLabel),
        tileLabelSizeSp = p.getFloat("${CLASSIC_CC_PREFIX}tile_label_size", d.tileLabelSizeSp),
        tileLabelTopGapDp = p.getInt("${CLASSIC_CC_PREFIX}tile_label_gap", d.tileLabelTopGapDp),
        tileLabelBold = p.getBoolean("${CLASSIC_CC_PREFIX}tile_label_bold", d.tileLabelBold),
        tileTouchFeedbackScale = p.getInt("${CLASSIC_CC_PREFIX}tile_touch_scale", d.tileTouchFeedbackScale),
        tileAccentMode = p.getInt("${CLASSIC_CC_PREFIX}tile_accent_mode", d.tileAccentMode),
        tileActiveBgColor = p.getInt("${CLASSIC_CC_PREFIX}tile_active_bg", d.tileActiveBgColor),
        tileInactiveLightBgColor = p.getInt("${CLASSIC_CC_PREFIX}tile_inactive_light_bg", d.tileInactiveLightBgColor),
        tileInactiveDarkBgColor = p.getInt("${CLASSIC_CC_PREFIX}tile_inactive_dark_bg", d.tileInactiveDarkBgColor),
        tileActiveIconColor = p.getInt("${CLASSIC_CC_PREFIX}tile_active_icon", d.tileActiveIconColor),
        tileInactiveLightIconColor = p.getInt("${CLASSIC_CC_PREFIX}tile_inactive_light_icon", d.tileInactiveLightIconColor),
        tileInactiveDarkIconColor = p.getInt("${CLASSIC_CC_PREFIX}tile_inactive_dark_icon", d.tileInactiveDarkIconColor),
        tileBorderEnabled = p.getBoolean("${CLASSIC_CC_PREFIX}tile_border_enabled", d.tileBorderEnabled),
        brightnessSliderVisible = p.getBoolean("${CLASSIC_CC_PREFIX}brightness_visible", d.brightnessSliderVisible),
        brightnessSliderPosition = p.getInt("${CLASSIC_CC_PREFIX}brightness_pos", d.brightnessSliderPosition),
        brightnessExpandedOnly = p.getBoolean("${CLASSIC_CC_PREFIX}brightness_expanded_only", d.brightnessExpandedOnly),
        brightnessHeightDp = p.getInt("${CLASSIC_CC_PREFIX}brightness_height", d.brightnessHeightDp),
        brightnessCornerRadiusDp = p.getInt("${CLASSIC_CC_PREFIX}brightness_radius", d.brightnessCornerRadiusDp),
        brightnessHorizontalMarginDp = p.getInt("${CLASSIC_CC_PREFIX}brightness_h_margin", d.brightnessHorizontalMarginDp),
        brightnessTopMarginDp = p.getInt("${CLASSIC_CC_PREFIX}brightness_top_margin", d.brightnessTopMarginDp),
        brightnessBottomMarginDp = p.getInt("${CLASSIC_CC_PREFIX}brightness_bottom_margin", d.brightnessBottomMarginDp),
        brightnessShowPercent = p.getBoolean("${CLASSIC_CC_PREFIX}brightness_show_percent", d.brightnessShowPercent),
        brightnessShowAutoButton = p.getBoolean("${CLASSIC_CC_PREFIX}brightness_show_auto_btn", d.brightnessShowAutoButton),
        brightnessColorMode = p.getInt("${CLASSIC_CC_PREFIX}brightness_color_mode", d.brightnessColorMode),
        brightnessFillColor = p.getInt("${CLASSIC_CC_PREFIX}brightness_fill_color", d.brightnessFillColor),
        brightnessTrackLightColor = p.getInt("${CLASSIC_CC_PREFIX}brightness_track_light", d.brightnessTrackLightColor),
        brightnessTrackDarkColor = p.getInt("${CLASSIC_CC_PREFIX}brightness_track_dark", d.brightnessTrackDarkColor),
        brightnessIconColor = p.getInt("${CLASSIC_CC_PREFIX}brightness_icon_color", d.brightnessIconColor),
        volumeSliderEnabled = p.getBoolean("${CLASSIC_CC_PREFIX}volume_enabled", d.volumeSliderEnabled),
        volumeSliderShowInCollapsed = p.getBoolean("${CLASSIC_CC_PREFIX}volume_in_collapsed", d.volumeSliderShowInCollapsed),
        volumeSliderShowPercent = p.getBoolean("${CLASSIC_CC_PREFIX}volume_show_percent", d.volumeSliderShowPercent),
        volumeSliderShowMuteButton = p.getBoolean("${CLASSIC_CC_PREFIX}volume_show_mute_btn", d.volumeSliderShowMuteButton),
        volumeSliderHeightDp = p.getInt("${CLASSIC_CC_PREFIX}volume_height", d.volumeSliderHeightDp),
        dataUsageVisibilityMode = p.getInt("${CLASSIC_CC_PREFIX}data_usage_mode", d.dataUsageVisibilityMode),
        dataUsageHideDivider = p.getBoolean("${CLASSIC_CC_PREFIX}data_usage_hide_divider", d.dataUsageHideDivider),
        dataUsageModernPill = p.getBoolean("${CLASSIC_CC_PREFIX}data_usage_modern_pill", d.dataUsageModernPill),
        headerUseModernFont = p.getBoolean("${CLASSIC_CC_PREFIX}header_modern_font", d.headerUseModernFont),
        headerClockSizeSp = p.getFloat("${CLASSIC_CC_PREFIX}header_clock_size", d.headerClockSizeSp),
        headerClockWeight = p.getInt("${CLASSIC_CC_PREFIX}header_clock_weight", d.headerClockWeight),
        headerDateSizeSp = p.getFloat("${CLASSIC_CC_PREFIX}header_date_size", d.headerDateSizeSp),
        headerHeightOffsetDp = p.getInt("${CLASSIC_CC_PREFIX}header_height_offset", d.headerHeightOffsetDp),
        headerHideCarrier = p.getBoolean("${CLASSIC_CC_PREFIX}header_hide_carrier", d.headerHideCarrier),
        headerCompactMode = p.getBoolean("${CLASSIC_CC_PREFIX}header_compact_mode", d.headerCompactMode),
        headerShowSettingsButton = p.getBoolean("${CLASSIC_CC_PREFIX}header_show_settings", d.headerShowSettingsButton),
        headerShowEditButton = p.getBoolean("${CLASSIC_CC_PREFIX}header_show_edit", d.headerShowEditButton),
        headerShowPowerButton = p.getBoolean("${CLASSIC_CC_PREFIX}header_show_power", d.headerShowPowerButton),
        headerActionPillStyle = p.getBoolean("${CLASSIC_CC_PREFIX}header_action_pill", d.headerActionPillStyle),
        headerActionButtonSizeDp = p.getInt("${CLASSIC_CC_PREFIX}header_action_size", d.headerActionButtonSizeDp),
        smoothOpenAnimation = p.getBoolean("${CLASSIC_CC_PREFIX}smooth_open_anim", d.smoothOpenAnimation),
    ).normalized()
}

internal fun writeClassicControlCenterSettings(p: SharedPreferences, value: ClassicControlCenterSettings) {
    val s = value.normalized()
    p.edit().apply {
        putBoolean(KEY_CLASSIC_CC_ENABLED, s.enabled)
        putBoolean(KEY_CLASSIC_CC_UNLOCK_SYSTEMUI, s.unlockClassicInSystemUi)
        putInt("${CLASSIC_CC_PREFIX}panel_surface_mode", s.panelSurfaceMode)
        putBoolean("${CLASSIC_CC_PREFIX}use_global_soft_glass", s.useGlobalSoftGlass)
        writeScopedSoftGlass(CLASSIC_CC_SOFT_GLASS_PREFIX, s.softGlass)
        putBoolean("${CLASSIC_CC_PREFIX}use_global_clear_frost", s.useGlobalClearFrost)
        writeScopedClearFrost(CLASSIC_CC_CLEAR_FROST_PREFIX, s.clearFrost)
        putBoolean("${CLASSIC_CC_PREFIX}shade_bg_custom", s.shadeBackgroundCustomEnabled)
        putInt("${CLASSIC_CC_PREFIX}shade_bg_blur", s.shadeBackgroundBlur)
        putInt("${CLASSIC_CC_PREFIX}shade_glass_blur", s.shadeGlassBlur)
        putInt("${CLASSIC_CC_PREFIX}shade_bg_scale", s.shadeBackgroundScale)
        putInt("${CLASSIC_CC_PREFIX}shade_bg_opacity", s.shadeBackgroundOpacity)
        putInt("${CLASSIC_CC_PREFIX}panel_corner_radius", s.panelCornerRadiusDp)
        putInt("${CLASSIC_CC_PREFIX}panel_blur_radius", s.panelBlurRadius)
        putInt("${CLASSIC_CC_PREFIX}panel_opacity", s.panelOpacity)
        putInt("${CLASSIC_CC_PREFIX}panel_light_bg", s.panelLightBgColor)
        putInt("${CLASSIC_CC_PREFIX}panel_dark_bg", s.panelDarkBgColor)
        putBoolean("${CLASSIC_CC_PREFIX}panel_stroke_enabled", s.panelStrokeEnabled)
        putFloat("${CLASSIC_CC_PREFIX}panel_stroke_width", s.panelStrokeWidthDp)
        putInt("${CLASSIC_CC_PREFIX}panel_stroke_alpha", s.panelStrokeAlpha)
        putBoolean("${CLASSIC_CC_PREFIX}panel_shadow_enabled", s.panelShadowEnabled)
        putInt("${CLASSIC_CC_PREFIX}panel_side_margin", s.panelSideMarginDp)
        putInt("${CLASSIC_CC_PREFIX}panel_h_padding", s.panelHorizontalPaddingDp)
        putInt("${CLASSIC_CC_PREFIX}panel_top_padding", s.panelTopPaddingDp)
        putInt("${CLASSIC_CC_PREFIX}panel_bottom_padding", s.panelBottomPaddingDp)
        putInt("${CLASSIC_CC_PREFIX}panel_notif_gap", s.panelNotificationGapDp)
        putBoolean("${CLASSIC_CC_PREFIX}modernize_detail", s.modernizeDetailAndCustomizer)
        putInt("${CLASSIC_CC_PREFIX}drag_handle_style", s.dragHandleStyle)
        putInt("${CLASSIC_CC_PREFIX}drag_handle_width", s.dragHandleWidthDp)
        putInt("${CLASSIC_CC_PREFIX}drag_handle_height", s.dragHandleHeightDp)
        putInt("${CLASSIC_CC_PREFIX}page_indicator_style", s.pageIndicatorStyle)
        putInt("${CLASSIC_CC_PREFIX}qqs_tile_count", s.qqsTileCount)
        putInt("${CLASSIC_CC_PREFIX}qs_cols_port", s.qsColumnsPortrait)
        putInt("${CLASSIC_CC_PREFIX}qs_rows_port", s.qsRowsPortrait)
        putInt("${CLASSIC_CC_PREFIX}qs_cols_land", s.qsColumnsLandscape)
        putInt("${CLASSIC_CC_PREFIX}qs_rows_land", s.qsRowsLandscape)
        putInt("${CLASSIC_CC_PREFIX}tile_style", s.tileStyle)
        putInt("${CLASSIC_CC_PREFIX}tile_height", s.tileHeightDp)
        putInt("${CLASSIC_CC_PREFIX}tile_h_spacing", s.tileHorizontalSpacingDp)
        putInt("${CLASSIC_CC_PREFIX}tile_top_margin", s.tileTopMarginDp)
        putInt("${CLASSIC_CC_PREFIX}tile_icon_bg_size", s.tileIconBgSizeDp)
        putInt("${CLASSIC_CC_PREFIX}tile_corner_radius", s.tileCornerRadiusDp)
        putInt("${CLASSIC_CC_PREFIX}tile_icon_size", s.tileIconSizeDp)
        putBoolean("${CLASSIC_CC_PREFIX}show_tile_label", s.showTileLabel)
        putBoolean("${CLASSIC_CC_PREFIX}show_tile_secondary_label", s.showTileSecondaryLabel)
        putFloat("${CLASSIC_CC_PREFIX}tile_label_size", s.tileLabelSizeSp)
        putInt("${CLASSIC_CC_PREFIX}tile_label_gap", s.tileLabelTopGapDp)
        putBoolean("${CLASSIC_CC_PREFIX}tile_label_bold", s.tileLabelBold)
        putInt("${CLASSIC_CC_PREFIX}tile_touch_scale", s.tileTouchFeedbackScale)
        putInt("${CLASSIC_CC_PREFIX}tile_accent_mode", s.tileAccentMode)
        putInt("${CLASSIC_CC_PREFIX}tile_active_bg", s.tileActiveBgColor)
        putInt("${CLASSIC_CC_PREFIX}tile_inactive_light_bg", s.tileInactiveLightBgColor)
        putInt("${CLASSIC_CC_PREFIX}tile_inactive_dark_bg", s.tileInactiveDarkBgColor)
        putInt("${CLASSIC_CC_PREFIX}tile_active_icon", s.tileActiveIconColor)
        putInt("${CLASSIC_CC_PREFIX}tile_inactive_light_icon", s.tileInactiveLightIconColor)
        putInt("${CLASSIC_CC_PREFIX}tile_inactive_dark_icon", s.tileInactiveDarkIconColor)
        putBoolean("${CLASSIC_CC_PREFIX}tile_border_enabled", s.tileBorderEnabled)
        putBoolean("${CLASSIC_CC_PREFIX}brightness_visible", s.brightnessSliderVisible)
        putInt("${CLASSIC_CC_PREFIX}brightness_pos", s.brightnessSliderPosition)
        putBoolean("${CLASSIC_CC_PREFIX}brightness_expanded_only", s.brightnessExpandedOnly)
        putInt("${CLASSIC_CC_PREFIX}brightness_height", s.brightnessHeightDp)
        putInt("${CLASSIC_CC_PREFIX}brightness_radius", s.brightnessCornerRadiusDp)
        putInt("${CLASSIC_CC_PREFIX}brightness_h_margin", s.brightnessHorizontalMarginDp)
        putInt("${CLASSIC_CC_PREFIX}brightness_top_margin", s.brightnessTopMarginDp)
        putInt("${CLASSIC_CC_PREFIX}brightness_bottom_margin", s.brightnessBottomMarginDp)
        putBoolean("${CLASSIC_CC_PREFIX}brightness_show_percent", s.brightnessShowPercent)
        putBoolean("${CLASSIC_CC_PREFIX}brightness_show_auto_btn", s.brightnessShowAutoButton)
        putInt("${CLASSIC_CC_PREFIX}brightness_color_mode", s.brightnessColorMode)
        putInt("${CLASSIC_CC_PREFIX}brightness_fill_color", s.brightnessFillColor)
        putInt("${CLASSIC_CC_PREFIX}brightness_track_light", s.brightnessTrackLightColor)
        putInt("${CLASSIC_CC_PREFIX}brightness_track_dark", s.brightnessTrackDarkColor)
        putInt("${CLASSIC_CC_PREFIX}brightness_icon_color", s.brightnessIconColor)
        putBoolean("${CLASSIC_CC_PREFIX}volume_enabled", s.volumeSliderEnabled)
        putBoolean("${CLASSIC_CC_PREFIX}volume_in_collapsed", s.volumeSliderShowInCollapsed)
        putBoolean("${CLASSIC_CC_PREFIX}volume_show_percent", s.volumeSliderShowPercent)
        putBoolean("${CLASSIC_CC_PREFIX}volume_show_mute_btn", s.volumeSliderShowMuteButton)
        putInt("${CLASSIC_CC_PREFIX}volume_height", s.volumeSliderHeightDp)
        putInt("${CLASSIC_CC_PREFIX}data_usage_mode", s.dataUsageVisibilityMode)
        putBoolean("${CLASSIC_CC_PREFIX}data_usage_hide_divider", s.dataUsageHideDivider)
        putBoolean("${CLASSIC_CC_PREFIX}data_usage_modern_pill", s.dataUsageModernPill)
        putBoolean("${CLASSIC_CC_PREFIX}header_modern_font", s.headerUseModernFont)
        putFloat("${CLASSIC_CC_PREFIX}header_clock_size", s.headerClockSizeSp)
        putInt("${CLASSIC_CC_PREFIX}header_clock_weight", s.headerClockWeight)
        putFloat("${CLASSIC_CC_PREFIX}header_date_size", s.headerDateSizeSp)
        putInt("${CLASSIC_CC_PREFIX}header_height_offset", s.headerHeightOffsetDp)
        putBoolean("${CLASSIC_CC_PREFIX}header_hide_carrier", s.headerHideCarrier)
        putBoolean("${CLASSIC_CC_PREFIX}header_compact_mode", s.headerCompactMode)
        putBoolean("${CLASSIC_CC_PREFIX}header_show_settings", s.headerShowSettingsButton)
        putBoolean("${CLASSIC_CC_PREFIX}header_show_edit", s.headerShowEditButton)
        putBoolean("${CLASSIC_CC_PREFIX}header_show_power", s.headerShowPowerButton)
        putBoolean("${CLASSIC_CC_PREFIX}header_action_pill", s.headerActionPillStyle)
        putInt("${CLASSIC_CC_PREFIX}header_action_size", s.headerActionButtonSizeDp)
        putBoolean("${CLASSIC_CC_PREFIX}smooth_open_anim", s.smoothOpenAnimation)
    }.apply()
}

internal class ClassicControlCenterRuntimeSettings(
    private val preferences: SharedPreferences,
    onChanged: () -> Unit = {},
) {
    @Volatile
    var settings: ClassicControlCenterSettings = readClassicControlCenterSettings(preferences)
        private set

    @Volatile
    var version: Int = 1
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null ||
            key.startsWith(CLASSIC_CC_PREFIX) ||
            key.startsWith("soft_glass_parameters_v3_") ||
            key.startsWith(CLEAR_FROST_PREFIX)
        ) {
            settings = readClassicControlCenterSettings(preferences)
            version++
            onChanged()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
}

internal class ClassicControlCenterSettingsStore(
    private val local: SharedPreferences,
    private val onChanged: () -> Unit = {},
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    )

    var settings: ClassicControlCenterSettings = readClassicControlCenterSettings(local)
        private set
    var presets: List<ClassicControlCenterPreset> =
        ClassicControlCenterPresetCodec.decodeLibrary(local.getString(KEY_CLASSIC_CC_PRESETS, "[]") ?: "[]")
        private set
    var appliedPresetId: String? = local.getString(KEY_CLASSIC_CC_APPLIED_PRESET, null)
        private set

    fun reload() {
        settings = readClassicControlCenterSettings(local)
        presets = ClassicControlCenterPresetCodec.decodeLibrary(local.getString(KEY_CLASSIC_CC_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_CLASSIC_CC_APPLIED_PRESET, null)
    }

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        settings = readClassicControlCenterSettings(if (local.contains(KEY_CLASSIC_CC_ENABLED)) local else remote)
        val library = if (local.contains(KEY_CLASSIC_CC_PRESETS)) local else remote
        presets = ClassicControlCenterPresetCodec.decodeLibrary(library.getString(KEY_CLASSIC_CC_PRESETS, "[]") ?: "[]")
        appliedPresetId = local.getString(KEY_CLASSIC_CC_APPLIED_PRESET, null)
        writeClassicControlCenterSettings(local, settings)
        writeClassicControlCenterSettings(remote, settings)
        writePresets(local)
        writePresets(remote)
        onChanged()
    }

    fun update(service: XposedService?, transform: (ClassicControlCenterSettings) -> ClassicControlCenterSettings) {
        settings = transform(readClassicControlCenterSettings(local)).normalized()
        writeClassicControlCenterSettings(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { writeClassicControlCenterSettings(it, settings) }
        onChanged()
    }

    fun applyPreset(service: XposedService?, preset: ClassicControlCenterPreset): ClassicControlCenterSettings {
        settings = preset.applyTo(readClassicControlCenterSettings(local))
        writeClassicControlCenterSettings(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { writeClassicControlCenterSettings(it, settings) }
        onChanged()
        return settings
    }

    fun savePreset(service: XposedService?, preset: ClassicControlCenterPreset) {
        reload()
        presets = (presets.filterNot { it.id == preset.id } + preset.copy(builtIn = false)).takeLast(100)
        writePresets(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::writePresets)
    }

    fun renamePreset(service: XposedService?, id: String, name: String) {
        reload()
        presets.firstOrNull { it.id == id }?.let { savePreset(service, it.copy(name = name.trim().take(40))) }
    }

    fun setAppliedPreset(service: XposedService?, id: String?) {
        appliedPresetId = id
        local.edit().apply {
            if (id == null) remove(KEY_CLASSIC_CC_APPLIED_PRESET) else putString(KEY_CLASSIC_CC_APPLIED_PRESET, id)
        }.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply {
            if (id == null) remove(KEY_CLASSIC_CC_APPLIED_PRESET) else putString(KEY_CLASSIC_CC_APPLIED_PRESET, id)
        }?.apply()
    }

    fun deletePreset(service: XposedService?, id: String) {
        reload()
        presets = presets.filterNot { it.id == id }
        if (appliedPresetId == id) setAppliedPreset(service, null)
        writePresets(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::writePresets)
    }

    private fun writePresets(p: SharedPreferences) {
        p.edit().putString(KEY_CLASSIC_CC_PRESETS, ClassicControlCenterPresetCodec.encodeLibrary(presets)).apply()
    }
}

internal fun completeClassicControlCenterPreset(values: JSONObject) {
    val d = ClassicControlCenterSettings()
    fun ensure(key: String, type: String, value: Any) {
        if (!values.has(key)) values.put(key, JSONObject().put("type", type).put("value", value))
    }
    ensure(KEY_CLASSIC_CC_ENABLED, "boolean", d.enabled)
    ensure(KEY_CLASSIC_CC_UNLOCK_SYSTEMUI, "boolean", d.unlockClassicInSystemUi)
    ensure("${CLASSIC_CC_PREFIX}panel_surface_mode", "int", d.panelSurfaceMode)
    ensure("${CLASSIC_CC_PREFIX}use_global_soft_glass", "boolean", d.useGlobalSoftGlass)
    ensure("${CLASSIC_CC_SOFT_GLASS_PREFIX}enabled", "boolean", true)
    ensure("${CLASSIC_CC_SOFT_GLASS_PREFIX}refraction_blur", "int", d.softGlass.refractionBlur)
    ensure("${CLASSIC_CC_SOFT_GLASS_PREFIX}blur_percent", "int", d.softGlass.backgroundBlur)
    ensure("${CLASSIC_CC_SOFT_GLASS_PREFIX}scale_percent", "int", d.softGlass.backgroundScale)
    ensure("${CLASSIC_CC_SOFT_GLASS_PREFIX}opacity_percent", "int", d.softGlass.backgroundOpacity)
    softGlassParameters.forEach { def ->
        val k = if (def.key == "edge_feather") "edge_feather_inverse" else def.key
        ensure("$CLASSIC_CC_SOFT_GLASS_PREFIX$k", "int", d.softGlass.adjustments[def.key] ?: if (def.key == "darker") -100 else 0)
    }
    ensure("${CLASSIC_CC_PREFIX}use_global_clear_frost", "boolean", d.useGlobalClearFrost)
    val cf = d.clearFrost
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}light_style", "string", cf.lightStyle)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}dark_style", "string", cf.darkStyle)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_light_1", "int", cf.customLightColor1)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_light_2", "int", cf.customLightColor2)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_light_3", "int", cf.customLightColor3)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_dark_1", "int", cf.customDarkColor1)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_dark_2", "int", cf.customDarkColor2)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}custom_dark_3", "int", cf.customDarkColor3)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}element_opacity", "int", cf.elementOpacity)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_enabled", "boolean", cf.strokeEnabled)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_preset", "string", cf.strokePreset)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_normal_width", "float", cf.strokeNormalWidth.toDouble())
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_bloom_width", "float", cf.strokeBloomWidth.toDouble())
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_bloom_degree", "int", cf.strokeBloomDegree)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_bloom_alpha", "int", cf.strokeBloomAlpha)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_source1_alpha", "int", cf.strokeSource1Alpha)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}stroke_source2_alpha", "int", cf.strokeSource2Alpha)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}shadow_enabled", "boolean", cf.shadowEnabled)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}shadow_alpha", "int", cf.shadowAlpha)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}shadow_offset_y", "int", cf.shadowOffsetY)
    ensure("${CLASSIC_CC_CLEAR_FROST_PREFIX}shadow_radius", "int", cf.shadowRadius)
    ensure("${CLASSIC_CC_PREFIX}shade_bg_custom", "boolean", d.shadeBackgroundCustomEnabled)
    ensure("${CLASSIC_CC_PREFIX}shade_bg_blur", "int", d.shadeBackgroundBlur)
    ensure("${CLASSIC_CC_PREFIX}shade_glass_blur", "int", d.shadeGlassBlur)
    ensure("${CLASSIC_CC_PREFIX}shade_bg_scale", "int", d.shadeBackgroundScale)
    ensure("${CLASSIC_CC_PREFIX}shade_bg_opacity", "int", d.shadeBackgroundOpacity)
    ensure("${CLASSIC_CC_PREFIX}panel_corner_radius", "int", d.panelCornerRadiusDp)
    ensure("${CLASSIC_CC_PREFIX}panel_blur_radius", "int", d.panelBlurRadius)
    ensure("${CLASSIC_CC_PREFIX}panel_opacity", "int", d.panelOpacity)
    ensure("${CLASSIC_CC_PREFIX}panel_light_bg", "int", d.panelLightBgColor)
    ensure("${CLASSIC_CC_PREFIX}panel_dark_bg", "int", d.panelDarkBgColor)
    ensure("${CLASSIC_CC_PREFIX}panel_stroke_enabled", "boolean", d.panelStrokeEnabled)
    ensure("${CLASSIC_CC_PREFIX}panel_stroke_width", "float", d.panelStrokeWidthDp.toDouble())
    ensure("${CLASSIC_CC_PREFIX}panel_stroke_alpha", "int", d.panelStrokeAlpha)
    ensure("${CLASSIC_CC_PREFIX}panel_shadow_enabled", "boolean", d.panelShadowEnabled)
    ensure("${CLASSIC_CC_PREFIX}panel_side_margin", "int", d.panelSideMarginDp)
    ensure("${CLASSIC_CC_PREFIX}panel_h_padding", "int", d.panelHorizontalPaddingDp)
    ensure("${CLASSIC_CC_PREFIX}panel_top_padding", "int", d.panelTopPaddingDp)
    ensure("${CLASSIC_CC_PREFIX}panel_bottom_padding", "int", d.panelBottomPaddingDp)
    ensure("${CLASSIC_CC_PREFIX}panel_notif_gap", "int", d.panelNotificationGapDp)
    ensure("${CLASSIC_CC_PREFIX}modernize_detail", "boolean", d.modernizeDetailAndCustomizer)
    ensure("${CLASSIC_CC_PREFIX}drag_handle_style", "int", d.dragHandleStyle)
    ensure("${CLASSIC_CC_PREFIX}drag_handle_width", "int", d.dragHandleWidthDp)
    ensure("${CLASSIC_CC_PREFIX}drag_handle_height", "int", d.dragHandleHeightDp)
    ensure("${CLASSIC_CC_PREFIX}page_indicator_style", "int", d.pageIndicatorStyle)
    ensure("${CLASSIC_CC_PREFIX}qqs_tile_count", "int", d.qqsTileCount)
    ensure("${CLASSIC_CC_PREFIX}qs_cols_port", "int", d.qsColumnsPortrait)
    ensure("${CLASSIC_CC_PREFIX}qs_rows_port", "int", d.qsRowsPortrait)
    ensure("${CLASSIC_CC_PREFIX}qs_cols_land", "int", d.qsColumnsLandscape)
    ensure("${CLASSIC_CC_PREFIX}qs_rows_land", "int", d.qsRowsLandscape)
    ensure("${CLASSIC_CC_PREFIX}tile_style", "int", d.tileStyle)
    ensure("${CLASSIC_CC_PREFIX}tile_height", "int", d.tileHeightDp)
    ensure("${CLASSIC_CC_PREFIX}tile_h_spacing", "int", d.tileHorizontalSpacingDp)
    ensure("${CLASSIC_CC_PREFIX}tile_top_margin", "int", d.tileTopMarginDp)
    ensure("${CLASSIC_CC_PREFIX}tile_icon_bg_size", "int", d.tileIconBgSizeDp)
    ensure("${CLASSIC_CC_PREFIX}tile_corner_radius", "int", d.tileCornerRadiusDp)
    ensure("${CLASSIC_CC_PREFIX}tile_icon_size", "int", d.tileIconSizeDp)
    ensure("${CLASSIC_CC_PREFIX}show_tile_label", "boolean", d.showTileLabel)
    ensure("${CLASSIC_CC_PREFIX}show_tile_secondary_label", "boolean", d.showTileSecondaryLabel)
    ensure("${CLASSIC_CC_PREFIX}tile_label_size", "float", d.tileLabelSizeSp.toDouble())
    ensure("${CLASSIC_CC_PREFIX}tile_label_gap", "int", d.tileLabelTopGapDp)
    ensure("${CLASSIC_CC_PREFIX}tile_label_bold", "boolean", d.tileLabelBold)
    ensure("${CLASSIC_CC_PREFIX}tile_touch_scale", "int", d.tileTouchFeedbackScale)
    ensure("${CLASSIC_CC_PREFIX}tile_accent_mode", "int", d.tileAccentMode)
    ensure("${CLASSIC_CC_PREFIX}tile_active_bg", "int", d.tileActiveBgColor)
    ensure("${CLASSIC_CC_PREFIX}tile_inactive_light_bg", "int", d.tileInactiveLightBgColor)
    ensure("${CLASSIC_CC_PREFIX}tile_inactive_dark_bg", "int", d.tileInactiveDarkBgColor)
    ensure("${CLASSIC_CC_PREFIX}tile_active_icon", "int", d.tileActiveIconColor)
    ensure("${CLASSIC_CC_PREFIX}tile_inactive_light_icon", "int", d.tileInactiveLightIconColor)
    ensure("${CLASSIC_CC_PREFIX}tile_inactive_dark_icon", "int", d.tileInactiveDarkIconColor)
    ensure("${CLASSIC_CC_PREFIX}tile_border_enabled", "boolean", d.tileBorderEnabled)
    ensure("${CLASSIC_CC_PREFIX}brightness_visible", "boolean", d.brightnessSliderVisible)
    ensure("${CLASSIC_CC_PREFIX}brightness_pos", "int", d.brightnessSliderPosition)
    ensure("${CLASSIC_CC_PREFIX}brightness_expanded_only", "boolean", d.brightnessExpandedOnly)
    ensure("${CLASSIC_CC_PREFIX}brightness_height", "int", d.brightnessHeightDp)
    ensure("${CLASSIC_CC_PREFIX}brightness_radius", "int", d.brightnessCornerRadiusDp)
    ensure("${CLASSIC_CC_PREFIX}brightness_h_margin", "int", d.brightnessHorizontalMarginDp)
    ensure("${CLASSIC_CC_PREFIX}brightness_top_margin", "int", d.brightnessTopMarginDp)
    ensure("${CLASSIC_CC_PREFIX}brightness_bottom_margin", "int", d.brightnessBottomMarginDp)
    ensure("${CLASSIC_CC_PREFIX}brightness_show_percent", "boolean", d.brightnessShowPercent)
    ensure("${CLASSIC_CC_PREFIX}brightness_show_auto_btn", "boolean", d.brightnessShowAutoButton)
    ensure("${CLASSIC_CC_PREFIX}brightness_color_mode", "int", d.brightnessColorMode)
    ensure("${CLASSIC_CC_PREFIX}brightness_fill_color", "int", d.brightnessFillColor)
    ensure("${CLASSIC_CC_PREFIX}brightness_track_light", "int", d.brightnessTrackLightColor)
    ensure("${CLASSIC_CC_PREFIX}brightness_track_dark", "int", d.brightnessTrackDarkColor)
    ensure("${CLASSIC_CC_PREFIX}brightness_icon_color", "int", d.brightnessIconColor)
    ensure("${CLASSIC_CC_PREFIX}volume_enabled", "boolean", d.volumeSliderEnabled)
    ensure("${CLASSIC_CC_PREFIX}volume_in_collapsed", "boolean", d.volumeSliderShowInCollapsed)
    ensure("${CLASSIC_CC_PREFIX}volume_show_percent", "boolean", d.volumeSliderShowPercent)
    ensure("${CLASSIC_CC_PREFIX}volume_show_mute_btn", "boolean", d.volumeSliderShowMuteButton)
    ensure("${CLASSIC_CC_PREFIX}volume_height", "int", d.volumeSliderHeightDp)
    ensure("${CLASSIC_CC_PREFIX}data_usage_mode", "int", d.dataUsageVisibilityMode)
    ensure("${CLASSIC_CC_PREFIX}data_usage_hide_divider", "boolean", d.dataUsageHideDivider)
    ensure("${CLASSIC_CC_PREFIX}data_usage_modern_pill", "boolean", d.dataUsageModernPill)
    ensure("${CLASSIC_CC_PREFIX}header_modern_font", "boolean", d.headerUseModernFont)
    ensure("${CLASSIC_CC_PREFIX}header_clock_size", "float", d.headerClockSizeSp.toDouble())
    ensure("${CLASSIC_CC_PREFIX}header_clock_weight", "int", d.headerClockWeight)
    ensure("${CLASSIC_CC_PREFIX}header_date_size", "float", d.headerDateSizeSp.toDouble())
    ensure("${CLASSIC_CC_PREFIX}header_height_offset", "int", d.headerHeightOffsetDp)
    ensure("${CLASSIC_CC_PREFIX}header_hide_carrier", "boolean", d.headerHideCarrier)
    ensure("${CLASSIC_CC_PREFIX}header_compact_mode", "boolean", d.headerCompactMode)
    ensure("${CLASSIC_CC_PREFIX}header_show_settings", "boolean", d.headerShowSettingsButton)
    ensure("${CLASSIC_CC_PREFIX}header_show_edit", "boolean", d.headerShowEditButton)
    ensure("${CLASSIC_CC_PREFIX}header_show_power", "boolean", d.headerShowPowerButton)
    ensure("${CLASSIC_CC_PREFIX}header_action_pill", "boolean", d.headerActionPillStyle)
    ensure("${CLASSIC_CC_PREFIX}header_action_size", "int", d.headerActionButtonSizeDp)
    ensure("${CLASSIC_CC_PREFIX}smooth_open_anim", "boolean", d.smoothOpenAnimation)
    ensure(KEY_CLASSIC_CC_PRESETS, "string", "[]")
}
