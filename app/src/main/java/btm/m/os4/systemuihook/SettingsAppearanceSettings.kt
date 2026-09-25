// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val SETTINGS_APPEARANCE_PREFERENCES = "settings_appearance"
const val SETTINGS_APPEARANCE_AUTHORITY = "btm.m.os4.systemuihook.settingsappearance"

const val APPEARANCE_SLOT_HOME = "home"
const val APPEARANCE_SLOT_DEVICE = "device"
const val APPEARANCE_SLOT_LOGO = "logo"
const val APPEARANCE_SLOT_DEVICE_IMAGE = "device_image"
const val APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO = "custom_device_logo"
const val APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND = "style1_update_background"
const val APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE = "style2_device_image"
const val APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO = "style2_custom_device_logo"
const val APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND = "style2_update_background"
const val COLOR_MODE_LIGHT = "light"
const val COLOR_MODE_DARK = "dark"
val COLOR_MODE_ASSET_SLOTS = listOf(
    APPEARANCE_SLOT_DEVICE_IMAGE, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND,
    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND,
)
fun colorModeAssetKey(slot: String, dark: Boolean) = "$slot:${if (dark) COLOR_MODE_DARK else COLOR_MODE_LIGHT}"

const val DEVICE_INTERFACE_STYLE_SYSTEM = 0
const val DEVICE_INTERFACE_STYLE_ONE = 1
const val DEVICE_INTERFACE_STYLE_TWO = 2

const val LOGO_MODE_SYSTEM = 0
const val LOGO_MODE_NO_ADVANCED_MATERIAL = 1
const val LOGO_MODE_KEEP_ADVANCED_MATERIAL = 2

private const val KEY_INITIALIZED = "initialized"
private const val KEY_HOME_ENABLED = "home_enabled"
private const val KEY_HOME_OPACITY = "home_opacity"
private const val KEY_HOME_BLUR = "home_blur"
private const val KEY_HOME_FONT = "home_font"
private const val KEY_HOME_MIME = "home_mime"
private const val KEY_HOME_VERSION = "home_version"
private const val KEY_DEVICE_ENABLED = "device_enabled"
private const val KEY_DEVICE_OPACITY = "device_opacity"
private const val KEY_DEVICE_BLUR = "device_blur"
private const val KEY_DEVICE_FONT = "device_font"
private const val KEY_DEVICE_MIME = "device_mime"
private const val KEY_DEVICE_VERSION = "device_version"
private const val KEY_LOGO_MODE = "logo_mode"
private const val KEY_LOGO_SCALE = "logo_scale"
private const val KEY_LOGO_MIME = "logo_mime"
private const val KEY_LOGO_VERSION = "logo_version"
private const val KEY_LIGHT_CARD_OPACITY = "light_card_opacity"
private const val KEY_TUTORIAL_CARD_ENABLED = "tutorial_card_enabled"
private const val KEY_TUTORIAL_CARD_TITLE = "tutorial_card_title"
private const val KEY_TUTORIAL_CARD_SLOGAN = "tutorial_card_slogan"
private const val KEY_TUTORIAL_CARD_AUTHOR = "tutorial_card_author"
private const val KEY_TUTORIAL_CARD_IMAGE_SCALE = "tutorial_card_image_scale"
private const val KEY_TUTORIAL_CARD_IMAGE_MIME = "tutorial_card_image_mime"
private const val KEY_TUTORIAL_CARD_IMAGE_VERSION = "tutorial_card_image_version"
private const val KEY_TUTORIAL_CARD_LOGO_MIME = "tutorial_card_logo_mime"
private const val KEY_TUTORIAL_CARD_LOGO_VERSION = "tutorial_card_logo_version"
private const val KEY_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET = "tutorial_card_logo_vertical_offset"
private const val KEY_TUTORIAL_CARD_LOGO_SCALE = "tutorial_card_logo_scale"
private const val KEY_TUTORIAL_CARD_IMAGE_LOGO_SPACING = "tutorial_card_image_logo_spacing"
private const val KEY_TUTORIAL_CARD_TEXT_SPACING = "tutorial_card_text_spacing"
private const val KEY_TUTORIAL_CARD_INFO_CARDS_ENABLED = "tutorial_card_info_cards_enabled"
private const val KEY_TUTORIAL_CARD_BACKGROUND_MIME = "tutorial_card_background_mime"
private const val KEY_TUTORIAL_CARD_BACKGROUND_VERSION = "tutorial_card_background_version"
private const val KEY_TUTORIAL_CARD_BACKGROUND_BLUR = "tutorial_card_background_blur"
private const val KEY_TUTORIAL_CARD_BACKGROUND_VERTICAL_OFFSET = "tutorial_card_background_vertical_offset"
private const val KEY_TUTORIAL_CARD_BACKGROUND_HORIZONTAL_OFFSET = "tutorial_card_background_horizontal_offset"
private const val KEY_TUTORIAL_CARD_BACKGROUND_SCALE = "tutorial_card_background_scale"
private const val KEY_DEVICE_INTERFACE_STYLE = "device_interface_style"
private const val KEY_COLOR_MODE_ASSETS_ENABLED = "color_mode_assets_enabled"
private const val KEY_STYLE2_IMAGE_MIME = "style2_image_mime"
private const val KEY_STYLE2_IMAGE_VERSION = "style2_image_version"
private const val KEY_STYLE2_LOGO_MIME = "style2_logo_mime"
private const val KEY_STYLE2_LOGO_VERSION = "style2_logo_version"
private const val KEY_STYLE2_IMAGE_SCALE = "style2_image_scale"
private const val KEY_STYLE2_LOGO_VERTICAL_OFFSET = "style2_logo_vertical_offset"
private const val KEY_STYLE2_LOGO_VERTICAL_OFFSET_LEFT = "style2_logo_vertical_offset_left"
private const val KEY_STYLE2_LOGO_VERTICAL_OFFSET_CENTER = "style2_logo_vertical_offset_center"
private const val KEY_STYLE2_LOGO_VERTICAL_OFFSET_RIGHT = "style2_logo_vertical_offset_right"
private const val KEY_STYLE2_IMAGE_LOGO_SPACING = "style2_image_logo_spacing"
private const val KEY_STYLE2_BACKGROUND_MIME = "style2_background_mime"
private const val KEY_STYLE2_BACKGROUND_VERSION = "style2_background_version"
private const val KEY_STYLE2_LOGO_HORIZONTAL_OFFSET = "style2_logo_horizontal_offset"
private const val KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_LEFT = "style2_logo_horizontal_offset_left"
private const val KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_CENTER = "style2_logo_horizontal_offset_center"
private const val KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_RIGHT = "style2_logo_horizontal_offset_right"
private const val KEY_STYLE2_LOGO_ALIGNMENT = "style2_logo_alignment"
private const val KEY_STYLE2_LOGO_VERSION_SPACING = "style2_logo_version_spacing"
private const val KEY_STYLE2_TEXT_ENABLED = "style2_text_enabled"
private const val KEY_STYLE2_TEXT = "style2_text"
private const val KEY_STYLE2_TEXT_INDEPENDENT = "style2_text_independent"
private const val KEY_STYLE2_TEXT_SCALE = "style2_text_scale"
private const val KEY_STYLE2_TEXT_POSITION = "style2_text_position"
private const val KEY_STYLE2_TEXT_SPACING_ABOVE = "style2_text_spacing_above"
private const val KEY_STYLE2_TEXT_SPACING_BELOW = "style2_text_spacing_below"
private const val KEY_STYLE2_TEXT_ALIGNMENT = "style2_text_alignment"
private const val KEY_STYLE2_TEXT_HORIZONTAL_OFFSET = "style2_text_horizontal_offset"
private const val KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT = "style2_text_horizontal_offset_left"
private const val KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER = "style2_text_horizontal_offset_center"
private const val KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT = "style2_text_horizontal_offset_right"
private const val KEY_STYLE2_TEXT_VERTICAL_OFFSET = "style2_text_vertical_offset"
private const val KEY_STYLE2_TEXT_VERTICAL_OFFSET_LEFT = "style2_text_vertical_offset_left"
private const val KEY_STYLE2_TEXT_VERTICAL_OFFSET_CENTER = "style2_text_vertical_offset_center"
private const val KEY_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT = "style2_text_vertical_offset_right"
private const val KEY_STYLE2_LOGO_COLOR_MODE = "style2_logo_color_mode"
private const val KEY_STYLE2_VERSION_COLOR_MODE = "style2_version_color_mode"
private const val KEY_STYLE2_TEXT_COLOR_MODE = "style2_text_color_mode"
private const val KEY_STYLE2_BACKGROUND_BLUR = "style2_background_blur"
private const val KEY_STYLE2_BACKGROUND_VERTICAL_OFFSET = "style2_background_vertical_offset"
private const val KEY_STYLE2_BACKGROUND_HORIZONTAL_OFFSET = "style2_background_horizontal_offset"
private const val KEY_STYLE2_BACKGROUND_SCALE = "style2_background_scale"

data class SettingsAppearanceSettings(
    val homeEnabled: Boolean = false,
    val homeOpacity: Int = 100,
    val homeBlur: Float = 0f,
    val homeFontMode: Int = 0,
    val homeMime: String = "",
    val homeVersion: Long = 0L,
    val deviceEnabled: Boolean = false,
    val deviceOpacity: Int = 100,
    val deviceBlur: Float = 0f,
    val deviceFontMode: Int = 0,
    val deviceMime: String = "",
    val deviceVersion: Long = 0L,
    val logoMode: Int = LOGO_MODE_SYSTEM,
    val logoScale: Int = 100,
    val logoMime: String = "",
    val logoVersion: Long = 0L,
    val lightCardOpacity: Int = 100,
    val tutorialCardEnabled: Boolean = false,
    val tutorialCardTitle: String = "",
    val tutorialCardSlogan: String = "",
    val tutorialCardAuthor: String = "",
    val tutorialCardImageScale: Int = 100,
    val tutorialCardImageMime: String = "",
    val tutorialCardImageVersion: Long = 0L,
    val tutorialCardLogoMime: String = "",
    val tutorialCardLogoVersion: Long = 0L,
    val tutorialCardLogoVerticalOffset: Int = 0,
    val tutorialCardLogoScale: Int = 100,
    val tutorialCardImageLogoSpacing: Int = 0,
    val tutorialCardTextSpacing: Int = 0,
    val tutorialCardInfoCardsEnabled: Boolean = true,
    val tutorialCardBackgroundMime: String = "",
    val tutorialCardBackgroundVersion: Long = 0L,
    val tutorialCardBackgroundBlur: Float = 0f,
    val tutorialCardBackgroundVerticalOffset: Int = 0,
    val tutorialCardBackgroundHorizontalOffset: Int = 0,
    val tutorialCardBackgroundScale: Int = 100,
    val deviceInterfaceStyle: Int = DEVICE_INTERFACE_STYLE_SYSTEM,
    val colorModeAssetsEnabled: Boolean = false,
    val colorModeAssetMimes: Map<String, String> = emptyMap(),
    val colorModeAssetVersions: Map<String, Long> = emptyMap(),
    val style2ImageMime: String = "",
    val style2ImageVersion: Long = 0L,
    val style2LogoMime: String = "",
    val style2LogoVersion: Long = 0L,
    val style2ImageScale: Int = 100,
    val style2LogoVerticalOffset: Int = 0,
    val style2LogoVerticalOffsetLeft: Int = 0,
    val style2LogoVerticalOffsetCenter: Int = 0,
    val style2LogoVerticalOffsetRight: Int = 0,
    val style2ImageLogoSpacing: Int = 0,
    val style2BackgroundMime: String = "",
    val style2BackgroundVersion: Long = 0L,
    val style2LogoHorizontalOffset: Int = 0,
    val style2LogoHorizontalOffsetLeft: Int = 0,
    val style2LogoHorizontalOffsetCenter: Int = 0,
    val style2LogoHorizontalOffsetRight: Int = 0,
    val style2LogoAlignment: Int = 1,
    val style2LogoVersionSpacing: Int = 0,
    val style2TextEnabled: Boolean = false,
    val style2Text: String = "",
    val style2TextIndependent: Boolean = false,
    val style2TextScale: Int = 100,
    val style2TextPosition: Int = 0,
    val style2TextSpacingAbove: Int = 0,
    val style2TextSpacingBelow: Int = 0,
    val style2TextAlignment: Int = 0,
    val style2TextHorizontalOffset: Int = 0,
    val style2TextHorizontalOffsetLeft: Int = 0,
    val style2TextHorizontalOffsetCenter: Int = 0,
    val style2TextHorizontalOffsetRight: Int = 0,
    val style2TextVerticalOffset: Int = 0,
    val style2TextVerticalOffsetLeft: Int = 0,
    val style2TextVerticalOffsetCenter: Int = 0,
    val style2TextVerticalOffsetRight: Int = 0,
    val style2LogoColorMode: Int = 0,
    val style2VersionColorMode: Int = 0,
    val style2TextColorMode: Int = 0,
    val style2BackgroundBlur: Float = 0f,
    val style2BackgroundVerticalOffset: Int = 0,
    val style2BackgroundHorizontalOffset: Int = 0,
    val style2BackgroundScale: Int = 100,
)

class SettingsAppearanceStore(context: Context) {
    private val local = context.getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
    var settings: SettingsAppearanceSettings = local.toSettingsAppearance()
        private set

    fun reload() {
        settings = local.toSettingsAppearance()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        settings = if (remote.contains(KEY_INITIALIZED)) remote.toSettingsAppearance() else settings
        remote.writeSettingsAppearance(settings)
        local.writeSettingsAppearance(settings)
    }

    fun update(service: XposedService?, transform: (SettingsAppearanceSettings) -> SettingsAppearanceSettings) {
        settings = transform(settings).normalized()
        local.writeSettingsAppearance(settings)
        service?.getRemotePreferences(SETTINGS_APPEARANCE_PREFERENCES)?.writeSettingsAppearance(settings)
    }
}

internal fun SharedPreferences.toSettingsAppearance() = SettingsAppearanceSettings(
    homeEnabled = getBoolean(KEY_HOME_ENABLED, false),
    homeOpacity = getInt(KEY_HOME_OPACITY, 100),
    homeBlur = getFloatCompat(KEY_HOME_BLUR, 0f),
    homeFontMode = getInt(KEY_HOME_FONT, 0),
    homeMime = getString(KEY_HOME_MIME, "").orEmpty(),
    homeVersion = getLong(KEY_HOME_VERSION, 0L),
    deviceEnabled = getBoolean(KEY_DEVICE_ENABLED, false),
    deviceOpacity = getInt(KEY_DEVICE_OPACITY, 100),
    deviceBlur = getFloatCompat(KEY_DEVICE_BLUR, 0f),
    deviceFontMode = getInt(KEY_DEVICE_FONT, 0),
    deviceMime = getString(KEY_DEVICE_MIME, "").orEmpty(),
    deviceVersion = getLong(KEY_DEVICE_VERSION, 0L),
    logoMode = getInt(KEY_LOGO_MODE, LOGO_MODE_SYSTEM),
    logoScale = getInt(KEY_LOGO_SCALE, 100),
    logoMime = getString(KEY_LOGO_MIME, "").orEmpty(),
    logoVersion = getLong(KEY_LOGO_VERSION, 0L),
    lightCardOpacity = getInt(KEY_LIGHT_CARD_OPACITY, 100),
    tutorialCardEnabled = getBoolean(KEY_TUTORIAL_CARD_ENABLED, false),
    tutorialCardTitle = getString(KEY_TUTORIAL_CARD_TITLE, "").orEmpty(),
    tutorialCardSlogan = getString(KEY_TUTORIAL_CARD_SLOGAN, "").orEmpty(),
    tutorialCardAuthor = getString(KEY_TUTORIAL_CARD_AUTHOR, "").orEmpty(),
    tutorialCardImageScale = getInt(KEY_TUTORIAL_CARD_IMAGE_SCALE, 100),
    tutorialCardImageMime = getString(KEY_TUTORIAL_CARD_IMAGE_MIME, "").orEmpty(),
    tutorialCardImageVersion = getLong(KEY_TUTORIAL_CARD_IMAGE_VERSION, 0L),
    tutorialCardLogoMime = getString(KEY_TUTORIAL_CARD_LOGO_MIME, "").orEmpty(),
    tutorialCardLogoVersion = getLong(KEY_TUTORIAL_CARD_LOGO_VERSION, 0L),
    tutorialCardLogoVerticalOffset = getInt(KEY_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET, 0),
    tutorialCardLogoScale = getInt(KEY_TUTORIAL_CARD_LOGO_SCALE, 100),
    tutorialCardImageLogoSpacing = getInt(KEY_TUTORIAL_CARD_IMAGE_LOGO_SPACING, 0),
    tutorialCardTextSpacing = getInt(KEY_TUTORIAL_CARD_TEXT_SPACING, 0),
    tutorialCardInfoCardsEnabled = getBoolean(KEY_TUTORIAL_CARD_INFO_CARDS_ENABLED, true),
    tutorialCardBackgroundMime = getString(KEY_TUTORIAL_CARD_BACKGROUND_MIME, "").orEmpty(),
    tutorialCardBackgroundVersion = getLong(KEY_TUTORIAL_CARD_BACKGROUND_VERSION, 0L),
    tutorialCardBackgroundBlur = getFloatCompat(KEY_TUTORIAL_CARD_BACKGROUND_BLUR, 0f),
    tutorialCardBackgroundVerticalOffset = getInt(KEY_TUTORIAL_CARD_BACKGROUND_VERTICAL_OFFSET, 0),
    tutorialCardBackgroundHorizontalOffset = getInt(KEY_TUTORIAL_CARD_BACKGROUND_HORIZONTAL_OFFSET, 0),
    tutorialCardBackgroundScale = getInt(KEY_TUTORIAL_CARD_BACKGROUND_SCALE, 100),
    deviceInterfaceStyle = getInt(KEY_DEVICE_INTERFACE_STYLE, if (getBoolean(KEY_TUTORIAL_CARD_ENABLED, false)) DEVICE_INTERFACE_STYLE_ONE else DEVICE_INTERFACE_STYLE_SYSTEM),
    colorModeAssetsEnabled = getBoolean(KEY_COLOR_MODE_ASSETS_ENABLED, false),
    colorModeAssetMimes = COLOR_MODE_ASSET_SLOTS.flatMap { slot -> listOf(false, true).map { dark -> colorModeAssetKey(slot, dark) } }.associateWith { key -> getString("${key}_mime", "").orEmpty() },
    colorModeAssetVersions = COLOR_MODE_ASSET_SLOTS.flatMap { slot -> listOf(false, true).map { dark -> colorModeAssetKey(slot, dark) } }.associateWith { key -> getLong("${key}_version", 0L) },
    style2ImageMime = getString(KEY_STYLE2_IMAGE_MIME, "").orEmpty(),
    style2ImageVersion = getLong(KEY_STYLE2_IMAGE_VERSION, 0L),
    style2LogoMime = getString(KEY_STYLE2_LOGO_MIME, "").orEmpty(),
    style2LogoVersion = getLong(KEY_STYLE2_LOGO_VERSION, 0L),
    style2ImageScale = getInt(KEY_STYLE2_IMAGE_SCALE, 100),
    style2LogoVerticalOffset = getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET, 0),
    style2LogoVerticalOffsetLeft = getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_LEFT, getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET, 0)),
    style2LogoVerticalOffsetCenter = getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_CENTER, getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET, 0)),
    style2LogoVerticalOffsetRight = getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_RIGHT, getInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET, 0)),
    style2ImageLogoSpacing = getInt(KEY_STYLE2_IMAGE_LOGO_SPACING, 0),
    style2BackgroundMime = getString(KEY_STYLE2_BACKGROUND_MIME, "").orEmpty(),
    style2BackgroundVersion = getLong(KEY_STYLE2_BACKGROUND_VERSION, 0L),
    style2LogoHorizontalOffset = getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET, 0),
    style2LogoHorizontalOffsetLeft = getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_LEFT, getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET, 0)),
    style2LogoHorizontalOffsetCenter = getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_CENTER, getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET, 0)),
    style2LogoHorizontalOffsetRight = getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_RIGHT, getInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET, 0)),
    style2LogoAlignment = getInt(KEY_STYLE2_LOGO_ALIGNMENT, 1),
    style2LogoVersionSpacing = getInt(KEY_STYLE2_LOGO_VERSION_SPACING, 0),
    style2TextEnabled = getBoolean(KEY_STYLE2_TEXT_ENABLED, false),
    style2Text = getString(KEY_STYLE2_TEXT, "").orEmpty(),
    style2TextIndependent = getBoolean(KEY_STYLE2_TEXT_INDEPENDENT, false),
    style2TextScale = getInt(KEY_STYLE2_TEXT_SCALE, 100),
    style2TextPosition = getInt(KEY_STYLE2_TEXT_POSITION, 0),
    style2TextSpacingAbove = getInt(KEY_STYLE2_TEXT_SPACING_ABOVE, 0),
    style2TextSpacingBelow = getInt(KEY_STYLE2_TEXT_SPACING_BELOW, 0),
    style2TextAlignment = getInt(KEY_STYLE2_TEXT_ALIGNMENT, 0),
    style2TextHorizontalOffset = getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET, 0),
    style2TextHorizontalOffsetLeft = getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT, getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET, 0)),
    style2TextHorizontalOffsetCenter = getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER, getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET, 0)),
    style2TextHorizontalOffsetRight = getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT, getInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET, 0)),
    style2TextVerticalOffset = getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET, 0),
    style2TextVerticalOffsetLeft = getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_LEFT, getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET, 0)),
    style2TextVerticalOffsetCenter = getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_CENTER, getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET, 0)),
    style2TextVerticalOffsetRight = getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT, getInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET, 0)),
    style2LogoColorMode = getInt(KEY_STYLE2_LOGO_COLOR_MODE, 0),
    style2VersionColorMode = getInt(KEY_STYLE2_VERSION_COLOR_MODE, 0),
    style2TextColorMode = getInt(KEY_STYLE2_TEXT_COLOR_MODE, 0),
    style2BackgroundBlur = getFloatCompat(KEY_STYLE2_BACKGROUND_BLUR, 0f),
    style2BackgroundVerticalOffset = getInt(KEY_STYLE2_BACKGROUND_VERTICAL_OFFSET, 0),
    style2BackgroundHorizontalOffset = getInt(KEY_STYLE2_BACKGROUND_HORIZONTAL_OFFSET, 0),
    style2BackgroundScale = getInt(KEY_STYLE2_BACKGROUND_SCALE, 100),
).normalized()

private fun SettingsAppearanceSettings.normalized() = copy(
    homeOpacity = homeOpacity.coerceIn(0, 100),
    homeBlur = homeBlur.coerceIn(0f, 20f),
    homeFontMode = homeFontMode.coerceIn(0, 2),
    deviceOpacity = deviceOpacity.coerceIn(0, 100),
    deviceBlur = deviceBlur.coerceIn(0f, 20f),
    deviceFontMode = deviceFontMode.coerceIn(0, 2),
    logoMode = logoMode.coerceIn(LOGO_MODE_SYSTEM, LOGO_MODE_KEEP_ADVANCED_MATERIAL),
    logoScale = logoScale.coerceIn(50, 200),
    lightCardOpacity = lightCardOpacity.coerceIn(0, 100),
    tutorialCardImageScale = tutorialCardImageScale.coerceIn(40, 200),
    tutorialCardLogoVerticalOffset = tutorialCardLogoVerticalOffset.coerceIn(-120, 120),
    tutorialCardLogoScale = tutorialCardLogoScale.coerceIn(40, 200),
    tutorialCardImageLogoSpacing = tutorialCardImageLogoSpacing.coerceIn(-120, 120),
    tutorialCardTextSpacing = tutorialCardTextSpacing.coerceIn(-120, 120),
    tutorialCardBackgroundBlur = tutorialCardBackgroundBlur.coerceIn(0f, 25f),
    tutorialCardBackgroundVerticalOffset = tutorialCardBackgroundVerticalOffset.coerceIn(-120, 120),
    tutorialCardBackgroundHorizontalOffset = tutorialCardBackgroundHorizontalOffset.coerceIn(-120, 120),
    tutorialCardBackgroundScale = tutorialCardBackgroundScale.coerceIn(40, 200),
    deviceInterfaceStyle = deviceInterfaceStyle.coerceIn(DEVICE_INTERFACE_STYLE_SYSTEM, DEVICE_INTERFACE_STYLE_TWO),
    style2ImageScale = style2ImageScale.coerceIn(40, 200),
    style2LogoVerticalOffset = style2LogoVerticalOffset.coerceIn(-120, 120),
    style2LogoVerticalOffsetLeft = style2LogoVerticalOffsetLeft.coerceIn(-120, 120),
    style2LogoVerticalOffsetCenter = style2LogoVerticalOffsetCenter.coerceIn(-120, 120),
    style2LogoVerticalOffsetRight = style2LogoVerticalOffsetRight.coerceIn(-120, 120),
    style2ImageLogoSpacing = style2ImageLogoSpacing.coerceIn(-50, 50),
    style2LogoHorizontalOffset = style2LogoHorizontalOffset.coerceIn(-120, 120),
    style2LogoHorizontalOffsetLeft = style2LogoHorizontalOffsetLeft.coerceIn(-120, 120),
    style2LogoHorizontalOffsetCenter = style2LogoHorizontalOffsetCenter.coerceIn(-120, 120),
    style2LogoHorizontalOffsetRight = style2LogoHorizontalOffsetRight.coerceIn(-120, 120),
    style2LogoAlignment = style2LogoAlignment.coerceIn(0, 2),
    style2LogoVersionSpacing = style2LogoVersionSpacing.coerceIn(-120, 120),
    style2TextScale = style2TextScale.coerceIn(40, 200),
    style2TextPosition = style2TextPosition.coerceIn(0, 1),
    style2TextSpacingAbove = style2TextSpacingAbove.coerceIn(-120, 120),
    style2TextSpacingBelow = style2TextSpacingBelow.coerceIn(-120, 120),
    style2TextAlignment = style2TextAlignment.coerceIn(0, 2),
    style2TextHorizontalOffset = style2TextHorizontalOffset.coerceIn(-120, 120),
    style2TextHorizontalOffsetLeft = style2TextHorizontalOffsetLeft.coerceIn(-120, 120),
    style2TextHorizontalOffsetCenter = style2TextHorizontalOffsetCenter.coerceIn(-120, 120),
    style2TextHorizontalOffsetRight = style2TextHorizontalOffsetRight.coerceIn(-120, 120),
    style2TextVerticalOffset = style2TextVerticalOffset.coerceIn(-120, 120),
    style2TextVerticalOffsetLeft = style2TextVerticalOffsetLeft.coerceIn(-120, 120),
    style2TextVerticalOffsetCenter = style2TextVerticalOffsetCenter.coerceIn(-120, 120),
    style2TextVerticalOffsetRight = style2TextVerticalOffsetRight.coerceIn(-120, 120),
    style2LogoColorMode = style2LogoColorMode.coerceIn(0, 2),
    style2VersionColorMode = style2VersionColorMode.coerceIn(0, 2),
    style2TextColorMode = style2TextColorMode.coerceIn(0, 2),
    style2BackgroundBlur = style2BackgroundBlur.coerceIn(0f, 25f),
    style2BackgroundVerticalOffset = style2BackgroundVerticalOffset.coerceIn(-120, 120),
    style2BackgroundHorizontalOffset = style2BackgroundHorizontalOffset.coerceIn(-120, 120),
    style2BackgroundScale = style2BackgroundScale.coerceIn(40, 200),
)

private fun SharedPreferences.writeSettingsAppearance(value: SettingsAppearanceSettings) {
    edit()
        .putBoolean(KEY_INITIALIZED, true)
        .putBoolean(KEY_HOME_ENABLED, value.homeEnabled)
        .putInt(KEY_HOME_OPACITY, value.homeOpacity)
        .putFloat(KEY_HOME_BLUR, value.homeBlur)
        .putInt(KEY_HOME_FONT, value.homeFontMode)
        .putString(KEY_HOME_MIME, value.homeMime)
        .putLong(KEY_HOME_VERSION, value.homeVersion)
        .putBoolean(KEY_DEVICE_ENABLED, value.deviceEnabled)
        .putInt(KEY_DEVICE_OPACITY, value.deviceOpacity)
        .putFloat(KEY_DEVICE_BLUR, value.deviceBlur)
        .putInt(KEY_DEVICE_FONT, value.deviceFontMode)
        .putString(KEY_DEVICE_MIME, value.deviceMime)
        .putLong(KEY_DEVICE_VERSION, value.deviceVersion)
        .putInt(KEY_LOGO_MODE, value.logoMode)
        .putInt(KEY_LOGO_SCALE, value.logoScale)
        .putString(KEY_LOGO_MIME, value.logoMime)
        .putLong(KEY_LOGO_VERSION, value.logoVersion)
        .putInt(KEY_LIGHT_CARD_OPACITY, value.lightCardOpacity)
        .putBoolean(KEY_TUTORIAL_CARD_ENABLED, value.tutorialCardEnabled)
        .putString(KEY_TUTORIAL_CARD_TITLE, value.tutorialCardTitle)
        .putString(KEY_TUTORIAL_CARD_SLOGAN, value.tutorialCardSlogan)
        .putString(KEY_TUTORIAL_CARD_AUTHOR, value.tutorialCardAuthor)
        .putInt(KEY_TUTORIAL_CARD_IMAGE_SCALE, value.tutorialCardImageScale)
        .putString(KEY_TUTORIAL_CARD_IMAGE_MIME, value.tutorialCardImageMime)
        .putLong(KEY_TUTORIAL_CARD_IMAGE_VERSION, value.tutorialCardImageVersion)
        .putString(KEY_TUTORIAL_CARD_LOGO_MIME, value.tutorialCardLogoMime)
        .putLong(KEY_TUTORIAL_CARD_LOGO_VERSION, value.tutorialCardLogoVersion)
        .putInt(KEY_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET, value.tutorialCardLogoVerticalOffset)
        .putInt(KEY_TUTORIAL_CARD_LOGO_SCALE, value.tutorialCardLogoScale)
        .putInt(KEY_TUTORIAL_CARD_IMAGE_LOGO_SPACING, value.tutorialCardImageLogoSpacing)
        .putInt(KEY_TUTORIAL_CARD_TEXT_SPACING, value.tutorialCardTextSpacing)
        .putBoolean(KEY_TUTORIAL_CARD_INFO_CARDS_ENABLED, value.tutorialCardInfoCardsEnabled)
        .putString(KEY_TUTORIAL_CARD_BACKGROUND_MIME, value.tutorialCardBackgroundMime)
        .putLong(KEY_TUTORIAL_CARD_BACKGROUND_VERSION, value.tutorialCardBackgroundVersion)
        .putFloat(KEY_TUTORIAL_CARD_BACKGROUND_BLUR, value.tutorialCardBackgroundBlur)
        .putInt(KEY_TUTORIAL_CARD_BACKGROUND_VERTICAL_OFFSET, value.tutorialCardBackgroundVerticalOffset)
        .putInt(KEY_TUTORIAL_CARD_BACKGROUND_HORIZONTAL_OFFSET, value.tutorialCardBackgroundHorizontalOffset)
        .putInt(KEY_TUTORIAL_CARD_BACKGROUND_SCALE, value.tutorialCardBackgroundScale)
        .putInt(KEY_DEVICE_INTERFACE_STYLE, value.deviceInterfaceStyle)
        .putBoolean(KEY_COLOR_MODE_ASSETS_ENABLED, value.colorModeAssetsEnabled)
        .putString(KEY_STYLE2_IMAGE_MIME, value.style2ImageMime)
        .putLong(KEY_STYLE2_IMAGE_VERSION, value.style2ImageVersion)
        .putString(KEY_STYLE2_LOGO_MIME, value.style2LogoMime)
        .putLong(KEY_STYLE2_LOGO_VERSION, value.style2LogoVersion)
        .putInt(KEY_STYLE2_IMAGE_SCALE, value.style2ImageScale)
        .putInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET, value.style2LogoVerticalOffset)
        .putInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_LEFT, value.style2LogoVerticalOffsetLeft)
        .putInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_CENTER, value.style2LogoVerticalOffsetCenter)
        .putInt(KEY_STYLE2_LOGO_VERTICAL_OFFSET_RIGHT, value.style2LogoVerticalOffsetRight)
        .putInt(KEY_STYLE2_IMAGE_LOGO_SPACING, value.style2ImageLogoSpacing)
        .putString(KEY_STYLE2_BACKGROUND_MIME, value.style2BackgroundMime)
        .putLong(KEY_STYLE2_BACKGROUND_VERSION, value.style2BackgroundVersion)
        .putInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET, value.style2LogoHorizontalOffset)
        .putInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_LEFT, value.style2LogoHorizontalOffsetLeft)
        .putInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_CENTER, value.style2LogoHorizontalOffsetCenter)
        .putInt(KEY_STYLE2_LOGO_HORIZONTAL_OFFSET_RIGHT, value.style2LogoHorizontalOffsetRight)
        .putInt(KEY_STYLE2_LOGO_ALIGNMENT, value.style2LogoAlignment)
        .putInt(KEY_STYLE2_LOGO_VERSION_SPACING, value.style2LogoVersionSpacing)
        .putBoolean(KEY_STYLE2_TEXT_ENABLED, value.style2TextEnabled)
        .putString(KEY_STYLE2_TEXT, value.style2Text)
        .putBoolean(KEY_STYLE2_TEXT_INDEPENDENT, value.style2TextIndependent)
        .putInt(KEY_STYLE2_TEXT_SCALE, value.style2TextScale)
        .putInt(KEY_STYLE2_TEXT_POSITION, value.style2TextPosition)
        .putInt(KEY_STYLE2_TEXT_SPACING_ABOVE, value.style2TextSpacingAbove)
        .putInt(KEY_STYLE2_TEXT_SPACING_BELOW, value.style2TextSpacingBelow)
        .putInt(KEY_STYLE2_TEXT_ALIGNMENT, value.style2TextAlignment)
        .putInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET, value.style2TextHorizontalOffset)
        .putInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT, value.style2TextHorizontalOffsetLeft)
        .putInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER, value.style2TextHorizontalOffsetCenter)
        .putInt(KEY_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT, value.style2TextHorizontalOffsetRight)
        .putInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET, value.style2TextVerticalOffset)
        .putInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_LEFT, value.style2TextVerticalOffsetLeft)
        .putInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_CENTER, value.style2TextVerticalOffsetCenter)
        .putInt(KEY_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT, value.style2TextVerticalOffsetRight)
        .putInt(KEY_STYLE2_LOGO_COLOR_MODE, value.style2LogoColorMode)
        .putInt(KEY_STYLE2_VERSION_COLOR_MODE, value.style2VersionColorMode)
        .putInt(KEY_STYLE2_TEXT_COLOR_MODE, value.style2TextColorMode)
        .putFloat(KEY_STYLE2_BACKGROUND_BLUR, value.style2BackgroundBlur)
        .putInt(KEY_STYLE2_BACKGROUND_VERTICAL_OFFSET, value.style2BackgroundVerticalOffset)
        .putInt(KEY_STYLE2_BACKGROUND_HORIZONTAL_OFFSET, value.style2BackgroundHorizontalOffset)
        .putInt(KEY_STYLE2_BACKGROUND_SCALE, value.style2BackgroundScale)
        .apply {
            COLOR_MODE_ASSET_SLOTS.forEach { slot ->
                listOf(false, true).forEach { dark ->
                    val key = colorModeAssetKey(slot, dark)
                    putString("${key}_mime", value.colorModeAssetMimes[key].orEmpty())
                    putLong("${key}_version", value.colorModeAssetVersions[key] ?: 0L)
                }
            }
            apply()
        }
}

private fun SharedPreferences.getFloatCompat(key: String, default: Float): Float =
    runCatching { getFloat(key, default) }.getOrElse {
        runCatching { getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
    }

fun SettingsAppearanceSettings.style2LogoHorizontalOffsetForAlignment(alignment: Int = style2LogoAlignment): Int = when (alignment.coerceIn(0, 2)) {
    0 -> style2LogoHorizontalOffsetLeft
    2 -> style2LogoHorizontalOffsetRight
    else -> style2LogoHorizontalOffsetCenter
}

fun SettingsAppearanceSettings.style2LogoVerticalOffsetForAlignment(alignment: Int = style2LogoAlignment): Int = when (alignment.coerceIn(0, 2)) {
    0 -> style2LogoVerticalOffsetLeft
    2 -> style2LogoVerticalOffsetRight
    else -> style2LogoVerticalOffsetCenter
}

fun SettingsAppearanceSettings.withStyle2LogoHorizontalOffset(alignment: Int, value: Int): SettingsAppearanceSettings {
    val normalized = value.coerceIn(-120, 120)
    return when (alignment.coerceIn(0, 2)) {
        0 -> copy(style2LogoHorizontalOffset = normalized, style2LogoHorizontalOffsetLeft = normalized)
        2 -> copy(style2LogoHorizontalOffset = normalized, style2LogoHorizontalOffsetRight = normalized)
        else -> copy(style2LogoHorizontalOffset = normalized, style2LogoHorizontalOffsetCenter = normalized)
    }
}

fun SettingsAppearanceSettings.withStyle2LogoVerticalOffset(alignment: Int, value: Int): SettingsAppearanceSettings {
    val normalized = value.coerceIn(-120, 120)
    return when (alignment.coerceIn(0, 2)) {
        0 -> copy(style2LogoVerticalOffset = normalized, style2LogoVerticalOffsetLeft = normalized)
        2 -> copy(style2LogoVerticalOffset = normalized, style2LogoVerticalOffsetRight = normalized)
        else -> copy(style2LogoVerticalOffset = normalized, style2LogoVerticalOffsetCenter = normalized)
    }
}

fun SettingsAppearanceSettings.style2TextHorizontalOffsetForAlignment(alignment: Int = style2TextAlignment): Int = when (alignment.coerceIn(0, 2)) {
    1 -> style2TextHorizontalOffsetLeft
    2 -> style2TextHorizontalOffsetRight
    else -> style2TextHorizontalOffsetCenter
}

fun SettingsAppearanceSettings.style2TextVerticalOffsetForAlignment(alignment: Int = style2TextAlignment): Int = when (alignment.coerceIn(0, 2)) {
    1 -> style2TextVerticalOffsetLeft
    2 -> style2TextVerticalOffsetRight
    else -> style2TextVerticalOffsetCenter
}

fun SettingsAppearanceSettings.withStyle2TextHorizontalOffset(alignment: Int, value: Int): SettingsAppearanceSettings {
    val normalized = value.coerceIn(-120, 120)
    return when (alignment.coerceIn(0, 2)) {
        1 -> copy(style2TextHorizontalOffset = normalized, style2TextHorizontalOffsetLeft = normalized)
        2 -> copy(style2TextHorizontalOffset = normalized, style2TextHorizontalOffsetRight = normalized)
        else -> copy(style2TextHorizontalOffset = normalized, style2TextHorizontalOffsetCenter = normalized)
    }
}

fun SettingsAppearanceSettings.withStyle2TextVerticalOffset(alignment: Int, value: Int): SettingsAppearanceSettings {
    val normalized = value.coerceIn(-120, 120)
    return when (alignment.coerceIn(0, 2)) {
        1 -> copy(style2TextVerticalOffset = normalized, style2TextVerticalOffsetLeft = normalized)
        2 -> copy(style2TextVerticalOffset = normalized, style2TextVerticalOffsetRight = normalized)
        else -> copy(style2TextVerticalOffset = normalized, style2TextVerticalOffsetCenter = normalized)
    }
}
