// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ClassicControlCenterPreset(
    val name: String,
    val settings: ClassicControlCenterSettings,
    val id: String = UUID.randomUUID().toString(),
    val builtIn: Boolean = false,
) {
    fun applyTo(current: ClassicControlCenterSettings): ClassicControlCenterSettings =
        settings.copy(
            enabled = current.enabled,
            unlockClassicInSystemUi = current.unlockClassicInSystemUi,
        ).normalized()
}

internal fun builtInClassicControlCenterPresets(): List<ClassicControlCenterPreset> = listOf(
    ClassicControlCenterPreset(
        id = "builtin_classic_cc_soft_glass",
        name = "classic_cc_preset_soft_glass",
        builtIn = true,
        settings = ClassicControlCenterSettings(
            panelSurfaceMode = CLASSIC_CC_SURFACE_FOLLOW_SYSTEM,
            useGlobalSoftGlass = true,
            panelCornerRadiusDp = 28,
            panelNotificationGapDp = 12,
            tileStyle = CLASSIC_CC_TILE_STYLE_SQUIRCLE,
            tileAccentMode = CLASSIC_CC_ACCENT_HYPER_BLUE,
            qsColumnsPortrait = 4,
            qsRowsPortrait = 3,
            tileHeightDp = 92,
            tileTopMarginDp = 16,
            tileIconBgSizeDp = 54,
            tileCornerRadiusDp = 18,
            brightnessSliderVisible = true,
            brightnessSliderPosition = CLASSIC_CC_BRIGHTNESS_POS_BOTTOM,
            brightnessColorMode = CLASSIC_CC_BRIGHTNESS_COLOR_WHITE,
            brightnessShowAutoButton = true,
            brightnessShowPercent = true,
            volumeSliderEnabled = true,
            headerShowEditButton = true,
            headerShowPowerButton = true,
        ).normalized(),
    ),
    ClassicControlCenterPreset(
        id = "builtin_classic_cc_hyper_frost",
        name = "classic_cc_preset_hyper_frost",
        builtIn = true,
        settings = ClassicControlCenterSettings(
            panelSurfaceMode = CLASSIC_CC_SURFACE_CLEAR_FROST,
            useGlobalClearFrost = false,
            clearFrost = ClearFrostSettings(
                enabled = true,
                lightStyle = "Colored_Thick_Light",
                darkStyle = "Colored_Extra_Thin_Dark",
                strokeEnabled = true,
                strokePreset = CLEAR_FROST_STROKE_MIDDLE,
                shadowEnabled = true,
            ),
            panelCornerRadiusDp = 28,
            panelNotificationGapDp = 12,
            tileStyle = CLASSIC_CC_TILE_STYLE_SQUIRCLE,
            qsColumnsPortrait = 4,
            qsRowsPortrait = 3,
            tileCornerRadiusDp = 18,
            brightnessShowAutoButton = true,
            brightnessShowPercent = true,
            headerShowEditButton = true,
        ).normalized(),
    ),
    ClassicControlCenterPreset(
        id = "builtin_classic_cc_pill_cards",
        name = "classic_cc_preset_pill_cards",
        builtIn = true,
        settings = ClassicControlCenterSettings(
            panelSurfaceMode = CLASSIC_CC_SURFACE_SOFT_GLASS,
            useGlobalSoftGlass = true,
            panelCornerRadiusDp = 28,
            panelNotificationGapDp = 12,
            qsColumnsPortrait = 2,
            qsRowsPortrait = 4,
            tileStyle = CLASSIC_CC_TILE_STYLE_PILL_CARD,
            tileHeightDp = 64,
            tileCornerRadiusDp = 22,
            tileLabelSizeSp = 13f,
            tileLabelBold = true,
            brightnessColorMode = CLASSIC_CC_BRIGHTNESS_COLOR_WHITE,
            volumeSliderEnabled = true,
            headerShowEditButton = true,
        ).normalized(),
    ),
    ClassicControlCenterPreset(
        id = "builtin_classic_cc_compact",
        name = "classic_cc_preset_compact",
        builtIn = true,
        settings = ClassicControlCenterSettings(
            panelSurfaceMode = CLASSIC_CC_SURFACE_CLEAR_FROST,
            useGlobalClearFrost = false,
            clearFrost = ClearFrostSettings(
                enabled = true,
                lightStyle = "Pured_Regular_Glass_Light",
                darkStyle = "Pured_Regular_Glass_Dark",
                strokeEnabled = true,
                shadowEnabled = true,
            ),
            panelCornerRadiusDp = 26,
            panelNotificationGapDp = 10,
            qqsTileCount = 6,
            qsColumnsPortrait = 5,
            qsRowsPortrait = 3,
            tileStyle = CLASSIC_CC_TILE_STYLE_CIRCLE,
            tileHeightDp = 66,
            tileIconBgSizeDp = 48,
            showTileLabel = false,
            brightnessExpandedOnly = true,
            headerCompactMode = true,
            headerHideCarrier = true,
        ).normalized(),
    ),
)

internal object ClassicControlCenterPresetCodec {
    const val MAX_PAYLOAD_LENGTH = 32768

    fun encode(preset: ClassicControlCenterPreset): String = JSONObject().apply {
        put("type", "HyperChangerClassicCcPreset")
        put("version", 1)
        put("name", preset.name.trim().take(40))
        val s = preset.settings.normalized()
        put("appearance", JSONObject().apply {
            put("surface_mode", s.panelSurfaceMode)
            put("use_global_sg", s.useGlobalSoftGlass)
            put("sg_refraction_blur", s.softGlass.refractionBlur)
            put("sg_adjustments", JSONObject().apply {
                softGlassParameters.forEach { def ->
                    put(def.key, s.softGlass.adjustments[def.key] ?: if (def.key == "darker") -100 else 0)
                }
            })
            put("use_global_cf", s.useGlobalClearFrost)
            put("cf_light_style", s.clearFrost.lightStyle)
            put("cf_dark_style", s.clearFrost.darkStyle)
            put("cf_light_1", s.clearFrost.customLightColor1)
            put("cf_light_2", s.clearFrost.customLightColor2)
            put("cf_light_3", s.clearFrost.customLightColor3)
            put("cf_dark_1", s.clearFrost.customDarkColor1)
            put("cf_dark_2", s.clearFrost.customDarkColor2)
            put("cf_dark_3", s.clearFrost.customDarkColor3)
            put("cf_opacity", s.clearFrost.elementOpacity)
            put("cf_stroke_enabled", s.clearFrost.strokeEnabled)
            put("cf_stroke_preset", s.clearFrost.strokePreset)
            put("cf_stroke_normal_w", s.clearFrost.strokeNormalWidth.toDouble())
            put("cf_stroke_bloom_w", s.clearFrost.strokeBloomWidth.toDouble())
            put("cf_stroke_bloom_deg", s.clearFrost.strokeBloomDegree)
            put("cf_stroke_bloom_alpha", s.clearFrost.strokeBloomAlpha)
            put("cf_stroke_s1_alpha", s.clearFrost.strokeSource1Alpha)
            put("cf_stroke_s2_alpha", s.clearFrost.strokeSource2Alpha)
            put("cf_shadow_enabled", s.clearFrost.shadowEnabled)
            put("cf_shadow_alpha", s.clearFrost.shadowAlpha)
            put("cf_shadow_offset_y", s.clearFrost.shadowOffsetY)
            put("cf_shadow_radius", s.clearFrost.shadowRadius)
            put("shade_bg_custom", s.shadeBackgroundCustomEnabled)
            put("shade_bg_blur", s.shadeBackgroundBlur)
            put("shade_glass_blur", s.shadeGlassBlur)
            put("shade_bg_scale", s.shadeBackgroundScale)
            put("shade_bg_opacity", s.shadeBackgroundOpacity)
            put("corner_radius", s.panelCornerRadiusDp)
            put("blur_radius", s.panelBlurRadius)
            put("opacity", s.panelOpacity)
            put("light_bg", s.panelLightBgColor)
            put("dark_bg", s.panelDarkBgColor)
            put("stroke_enabled", s.panelStrokeEnabled)
            put("stroke_width", s.panelStrokeWidthDp.toDouble())
            put("stroke_alpha", s.panelStrokeAlpha)
            put("shadow_enabled", s.panelShadowEnabled)
            put("side_margin", s.panelSideMarginDp)
            put("h_padding", s.panelHorizontalPaddingDp)
            put("top_padding", s.panelTopPaddingDp)
            put("bottom_padding", s.panelBottomPaddingDp)
            put("notif_gap", s.panelNotificationGapDp)
            put("modernize_detail", s.modernizeDetailAndCustomizer)
            put("drag_handle_style", s.dragHandleStyle)
            put("drag_handle_width", s.dragHandleWidthDp)
            put("drag_handle_height", s.dragHandleHeightDp)
            put("page_indicator_style", s.pageIndicatorStyle)
        })
        put("tiles", JSONObject().apply {
            put("qqs_count", s.qqsTileCount)
            put("cols_port", s.qsColumnsPortrait)
            put("rows_port", s.qsRowsPortrait)
            put("cols_land", s.qsColumnsLandscape)
            put("rows_land", s.qsRowsLandscape)
            put("style", s.tileStyle)
            put("height", s.tileHeightDp)
            put("h_spacing", s.tileHorizontalSpacingDp)
            put("top_margin", s.tileTopMarginDp)
            put("icon_bg_size", s.tileIconBgSizeDp)
            put("corner_radius", s.tileCornerRadiusDp)
            put("icon_size", s.tileIconSizeDp)
            put("show_label", s.showTileLabel)
            put("show_secondary", s.showTileSecondaryLabel)
            put("label_size", s.tileLabelSizeSp.toDouble())
            put("label_gap", s.tileLabelTopGapDp)
            put("label_bold", s.tileLabelBold)
            put("touch_scale", s.tileTouchFeedbackScale)
            put("accent_mode", s.tileAccentMode)
            put("active_bg", s.tileActiveBgColor)
            put("inactive_light_bg", s.tileInactiveLightBgColor)
            put("inactive_dark_bg", s.tileInactiveDarkBgColor)
            put("active_icon", s.tileActiveIconColor)
            put("inactive_light_icon", s.tileInactiveLightIconColor)
            put("inactive_dark_icon", s.tileInactiveDarkIconColor)
            put("border_enabled", s.tileBorderEnabled)
        })
        put("sliders", JSONObject().apply {
            put("brightness_visible", s.brightnessSliderVisible)
            put("brightness_pos", s.brightnessSliderPosition)
            put("brightness_expanded_only", s.brightnessExpandedOnly)
            put("brightness_height", s.brightnessHeightDp)
            put("brightness_radius", s.brightnessCornerRadiusDp)
            put("brightness_h_margin", s.brightnessHorizontalMarginDp)
            put("brightness_top_margin", s.brightnessTopMarginDp)
            put("brightness_bottom_margin", s.brightnessBottomMarginDp)
            put("brightness_percent", s.brightnessShowPercent)
            put("brightness_auto_btn", s.brightnessShowAutoButton)
            put("brightness_color_mode", s.brightnessColorMode)
            put("brightness_fill", s.brightnessFillColor)
            put("brightness_track_light", s.brightnessTrackLightColor)
            put("brightness_track_dark", s.brightnessTrackDarkColor)
            put("brightness_icon", s.brightnessIconColor)
            put("volume_enabled", s.volumeSliderEnabled)
            put("volume_in_collapsed", s.volumeSliderShowInCollapsed)
            put("volume_percent", s.volumeSliderShowPercent)
            put("volume_mute_btn", s.volumeSliderShowMuteButton)
            put("volume_height", s.volumeSliderHeightDp)
            put("data_usage_mode", s.dataUsageVisibilityMode)
            put("data_usage_hide_divider", s.dataUsageHideDivider)
            put("data_usage_modern_pill", s.dataUsageModernPill)
        })
        put("header", JSONObject().apply {
            put("modern_font", s.headerUseModernFont)
            put("clock_size", s.headerClockSizeSp.toDouble())
            put("clock_weight", s.headerClockWeight)
            put("date_size", s.headerDateSizeSp.toDouble())
            put("height_offset", s.headerHeightOffsetDp)
            put("hide_carrier", s.headerHideCarrier)
            put("compact_mode", s.headerCompactMode)
            put("show_settings", s.headerShowSettingsButton)
            put("show_edit", s.headerShowEditButton)
            put("show_power", s.headerShowPowerButton)
            put("action_pill", s.headerActionPillStyle)
            put("action_size", s.headerActionButtonSizeDp)
            put("smooth_open", s.smoothOpenAnimation)
        })
    }.toString()

    fun decode(payload: String): ClassicControlCenterPreset {
        require(payload.length <= MAX_PAYLOAD_LENGTH)
        val root = JSONObject(payload)
        require(root.optString("type") == "HyperChangerClassicCcPreset")
        require(root.optInt("version", 0) == 1)
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 40)
        val d = ClassicControlCenterSettings()
        val a = root.optJSONObject("appearance") ?: JSONObject()
        val t = root.optJSONObject("tiles") ?: JSONObject()
        val sl = root.optJSONObject("sliders") ?: JSONObject()
        val h = root.optJSONObject("header") ?: JSONObject()
        val sgAdjJson = a.optJSONObject("sg_adjustments") ?: JSONObject()
        val sgAdj = softGlassParameters.associate { def ->
            def.key to sgAdjJson.optInt(def.key, if (def.key == "darker") -100 else 0)
        }
        val cfDefault = d.clearFrost
        val settings = ClassicControlCenterSettings(
            panelSurfaceMode = a.optInt("surface_mode", d.panelSurfaceMode),
            useGlobalSoftGlass = a.optBoolean("use_global_sg", d.useGlobalSoftGlass),
            softGlass = SoftGlassSettings(
                enabled = true,
                refractionBlur = a.optInt("sg_refraction_blur", 100),
                adjustments = sgAdj,
            ).normalized(),
            useGlobalClearFrost = a.optBoolean("use_global_cf", d.useGlobalClearFrost),
            clearFrost = ClearFrostSettings(
                enabled = true,
                lightStyle = a.optString("cf_light_style", cfDefault.lightStyle),
                darkStyle = a.optString("cf_dark_style", cfDefault.darkStyle),
                customLightColor1 = a.optInt("cf_light_1", cfDefault.customLightColor1),
                customLightColor2 = a.optInt("cf_light_2", cfDefault.customLightColor2),
                customLightColor3 = a.optInt("cf_light_3", cfDefault.customLightColor3),
                customDarkColor1 = a.optInt("cf_dark_1", cfDefault.customDarkColor1),
                customDarkColor2 = a.optInt("cf_dark_2", cfDefault.customDarkColor2),
                customDarkColor3 = a.optInt("cf_dark_3", cfDefault.customDarkColor3),
                elementOpacity = a.optInt("cf_opacity", cfDefault.elementOpacity),
                strokeEnabled = a.optBoolean("cf_stroke_enabled", cfDefault.strokeEnabled),
                strokePreset = a.optString("cf_stroke_preset", cfDefault.strokePreset),
                strokeNormalWidth = a.optDouble("cf_stroke_normal_w", cfDefault.strokeNormalWidth.toDouble()).toFloat(),
                strokeBloomWidth = a.optDouble("cf_stroke_bloom_w", cfDefault.strokeBloomWidth.toDouble()).toFloat(),
                strokeBloomDegree = a.optInt("cf_stroke_bloom_deg", cfDefault.strokeBloomDegree),
                strokeBloomAlpha = a.optInt("cf_stroke_bloom_alpha", cfDefault.strokeBloomAlpha),
                strokeSource1Alpha = a.optInt("cf_stroke_s1_alpha", cfDefault.strokeSource1Alpha),
                strokeSource2Alpha = a.optInt("cf_stroke_s2_alpha", cfDefault.strokeSource2Alpha),
                shadowEnabled = a.optBoolean("cf_shadow_enabled", cfDefault.shadowEnabled),
                shadowAlpha = a.optInt("cf_shadow_alpha", cfDefault.shadowAlpha),
                shadowOffsetY = a.optInt("cf_shadow_offset_y", cfDefault.shadowOffsetY),
                shadowRadius = a.optInt("cf_shadow_radius", cfDefault.shadowRadius),
            ).normalized(),
            shadeBackgroundCustomEnabled = a.optBoolean("shade_bg_custom", d.shadeBackgroundCustomEnabled),
            shadeBackgroundBlur = a.optInt("shade_bg_blur", d.shadeBackgroundBlur),
            shadeGlassBlur = a.optInt("shade_glass_blur", d.shadeGlassBlur),
            shadeBackgroundScale = a.optInt("shade_bg_scale", d.shadeBackgroundScale),
            shadeBackgroundOpacity = a.optInt("shade_bg_opacity", d.shadeBackgroundOpacity),
            panelCornerRadiusDp = a.optInt("corner_radius", d.panelCornerRadiusDp),
            panelBlurRadius = a.optInt("blur_radius", d.panelBlurRadius),
            panelOpacity = a.optInt("opacity", d.panelOpacity),
            panelLightBgColor = a.optInt("light_bg", d.panelLightBgColor),
            panelDarkBgColor = a.optInt("dark_bg", d.panelDarkBgColor),
            panelStrokeEnabled = a.optBoolean("stroke_enabled", d.panelStrokeEnabled),
            panelStrokeWidthDp = a.optDouble("stroke_width", d.panelStrokeWidthDp.toDouble()).toFloat(),
            panelStrokeAlpha = a.optInt("stroke_alpha", d.panelStrokeAlpha),
            panelShadowEnabled = a.optBoolean("shadow_enabled", d.panelShadowEnabled),
            panelSideMarginDp = a.optInt("side_margin", d.panelSideMarginDp),
            panelHorizontalPaddingDp = a.optInt("h_padding", d.panelHorizontalPaddingDp),
            panelTopPaddingDp = a.optInt("top_padding", d.panelTopPaddingDp),
            panelBottomPaddingDp = a.optInt("bottom_padding", d.panelBottomPaddingDp),
            panelNotificationGapDp = a.optInt("notif_gap", d.panelNotificationGapDp),
            modernizeDetailAndCustomizer = a.optBoolean("modernize_detail", d.modernizeDetailAndCustomizer),
            dragHandleStyle = a.optInt("drag_handle_style", d.dragHandleStyle),
            dragHandleWidthDp = a.optInt("drag_handle_width", d.dragHandleWidthDp),
            dragHandleHeightDp = a.optInt("drag_handle_height", d.dragHandleHeightDp),
            pageIndicatorStyle = a.optInt("page_indicator_style", d.pageIndicatorStyle),
            qqsTileCount = t.optInt("qqs_count", d.qqsTileCount),
            qsColumnsPortrait = t.optInt("cols_port", d.qsColumnsPortrait),
            qsRowsPortrait = t.optInt("rows_port", d.qsRowsPortrait),
            qsColumnsLandscape = t.optInt("cols_land", d.qsColumnsLandscape),
            qsRowsLandscape = t.optInt("rows_land", d.qsRowsLandscape),
            tileStyle = t.optInt("style", d.tileStyle),
            tileHeightDp = t.optInt("height", d.tileHeightDp),
            tileHorizontalSpacingDp = t.optInt("h_spacing", d.tileHorizontalSpacingDp),
            tileTopMarginDp = t.optInt("top_margin", d.tileTopMarginDp),
            tileIconBgSizeDp = t.optInt("icon_bg_size", d.tileIconBgSizeDp),
            tileCornerRadiusDp = t.optInt("corner_radius", d.tileCornerRadiusDp),
            tileIconSizeDp = t.optInt("icon_size", d.tileIconSizeDp),
            showTileLabel = t.optBoolean("show_label", d.showTileLabel),
            showTileSecondaryLabel = t.optBoolean("show_secondary", d.showTileSecondaryLabel),
            tileLabelSizeSp = t.optDouble("label_size", d.tileLabelSizeSp.toDouble()).toFloat(),
            tileLabelTopGapDp = t.optInt("label_gap", d.tileLabelTopGapDp),
            tileLabelBold = t.optBoolean("label_bold", d.tileLabelBold),
            tileTouchFeedbackScale = t.optInt("touch_scale", d.tileTouchFeedbackScale),
            tileAccentMode = t.optInt("accent_mode", d.tileAccentMode),
            tileActiveBgColor = t.optInt("active_bg", d.tileActiveBgColor),
            tileInactiveLightBgColor = t.optInt("inactive_light_bg", d.tileInactiveLightBgColor),
            tileInactiveDarkBgColor = t.optInt("inactive_dark_bg", d.tileInactiveDarkBgColor),
            tileActiveIconColor = t.optInt("active_icon", d.tileActiveIconColor),
            tileInactiveLightIconColor = t.optInt("inactive_light_icon", d.tileInactiveLightIconColor),
            tileInactiveDarkIconColor = t.optInt("inactive_dark_icon", d.tileInactiveDarkIconColor),
            tileBorderEnabled = t.optBoolean("border_enabled", d.tileBorderEnabled),
            brightnessSliderVisible = sl.optBoolean("brightness_visible", d.brightnessSliderVisible),
            brightnessSliderPosition = sl.optInt("brightness_pos", d.brightnessSliderPosition),
            brightnessExpandedOnly = sl.optBoolean("brightness_expanded_only", d.brightnessExpandedOnly),
            brightnessHeightDp = sl.optInt("brightness_height", d.brightnessHeightDp),
            brightnessCornerRadiusDp = sl.optInt("brightness_radius", d.brightnessCornerRadiusDp),
            brightnessHorizontalMarginDp = sl.optInt("brightness_h_margin", d.brightnessHorizontalMarginDp),
            brightnessTopMarginDp = sl.optInt("brightness_top_margin", d.brightnessTopMarginDp),
            brightnessBottomMarginDp = sl.optInt("brightness_bottom_margin", d.brightnessBottomMarginDp),
            brightnessShowPercent = sl.optBoolean("brightness_percent", d.brightnessShowPercent),
            brightnessShowAutoButton = sl.optBoolean("brightness_auto_btn", d.brightnessShowAutoButton),
            brightnessColorMode = sl.optInt("brightness_color_mode", d.brightnessColorMode),
            brightnessFillColor = sl.optInt("brightness_fill", d.brightnessFillColor),
            brightnessTrackLightColor = sl.optInt("brightness_track_light", d.brightnessTrackLightColor),
            brightnessTrackDarkColor = sl.optInt("brightness_track_dark", d.brightnessTrackDarkColor),
            brightnessIconColor = sl.optInt("brightness_icon", d.brightnessIconColor),
            volumeSliderEnabled = sl.optBoolean("volume_enabled", d.volumeSliderEnabled),
            volumeSliderShowInCollapsed = sl.optBoolean("volume_in_collapsed", d.volumeSliderShowInCollapsed),
            volumeSliderShowPercent = sl.optBoolean("volume_percent", d.volumeSliderShowPercent),
            volumeSliderShowMuteButton = sl.optBoolean("volume_mute_btn", d.volumeSliderShowMuteButton),
            volumeSliderHeightDp = sl.optInt("volume_height", d.volumeSliderHeightDp),
            dataUsageVisibilityMode = sl.optInt("data_usage_mode", d.dataUsageVisibilityMode),
            dataUsageHideDivider = sl.optBoolean("data_usage_hide_divider", d.dataUsageHideDivider),
            dataUsageModernPill = sl.optBoolean("data_usage_modern_pill", d.dataUsageModernPill),
            headerUseModernFont = h.optBoolean("modern_font", d.headerUseModernFont),
            headerClockSizeSp = h.optDouble("clock_size", d.headerClockSizeSp.toDouble()).toFloat(),
            headerClockWeight = h.optInt("clock_weight", d.headerClockWeight),
            headerDateSizeSp = h.optDouble("date_size", d.headerDateSizeSp.toDouble()).toFloat(),
            headerHeightOffsetDp = h.optInt("height_offset", d.headerHeightOffsetDp),
            headerHideCarrier = h.optBoolean("hide_carrier", d.headerHideCarrier),
            headerCompactMode = h.optBoolean("compact_mode", d.headerCompactMode),
            headerShowSettingsButton = h.optBoolean("show_settings", d.headerShowSettingsButton),
            headerShowEditButton = h.optBoolean("show_edit", d.headerShowEditButton),
            headerShowPowerButton = h.optBoolean("show_power", d.headerShowPowerButton),
            headerActionPillStyle = h.optBoolean("action_pill", d.headerActionPillStyle),
            headerActionButtonSizeDp = h.optInt("action_size", d.headerActionButtonSizeDp),
            smoothOpenAnimation = h.optBoolean("smooth_open", d.smoothOpenAnimation),
        ).normalized()
        return ClassicControlCenterPreset(name = name, settings = settings, builtIn = false)
    }

    fun encodeLibrary(presets: List<ClassicControlCenterPreset>): String = JSONArray().apply {
        presets.filterNot { it.builtIn }.takeLast(100).forEach { preset ->
            put(
                JSONObject()
                    .put("id", preset.id)
                    .put("payload", encode(preset)),
            )
        }
    }.toString()

    fun decodeLibrary(raw: String): List<ClassicControlCenterPreset> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val payload = obj.optString("payload")
                runCatching { decode(payload).copy(id = id, builtIn = false) }.getOrNull()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}
