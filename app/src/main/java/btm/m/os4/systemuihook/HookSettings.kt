// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

const val REMOTE_PREFERENCE_GROUP = "hyper_system_ui_hook"
internal const val KEY_SYSTEM_UPDATE_DISABLED = "system_update_disabled"
internal const val KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED = "system_update_ota_limit_removed"
internal const val KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED = "system_update_version_spoof_enabled"
internal const val KEY_SYSTEM_UPDATE_VERSION = "system_update_version"
internal const val KEY_SYSTEM_UPDATE_SOTA_VERSION = "system_update_sota_version"
internal const val KEY_STACKED_MOBILE_SIGNAL_ENABLED = "stacked_mobile_signal_enabled"
internal const val KEY_STACKED_MOBILE_SIGNAL_SCALE = "stacked_mobile_signal_scale"
internal const val KEY_STACKED_MOBILE_SIGNAL_VERTICAL_OFFSET = "stacked_mobile_signal_vertical_offset"
internal const val KEY_STACKED_MOBILE_SIGNAL_LEFT_MARGIN = "stacked_mobile_signal_left_margin"
internal const val KEY_STACKED_MOBILE_SIGNAL_RIGHT_MARGIN = "stacked_mobile_signal_right_margin"
internal const val KEY_MOBILE_SIGNAL_HIDE_MODE = "mobile_signal_hide_mode"
internal const val KEY_MOBILE_NETWORK_TYPE_SCALE = "mobile_network_type_scale"
internal const val KEY_MOBILE_NETWORK_TYPE_VERTICAL_OFFSET = "mobile_network_type_vertical_offset"
internal const val KEY_MOBILE_NETWORK_TYPE_LEFT_MARGIN = "mobile_network_type_left_margin"
internal const val KEY_MOBILE_NETWORK_TYPE_RIGHT_MARGIN = "mobile_network_type_right_margin"
internal const val KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE =
    "lockscreen_clock_colon_force_visible"
internal const val KEY_LOCKSCREEN_TEMPLATE_LIMIT_MODE = "lockscreen_template_limit_mode"
internal const val KEY_LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM = "lockscreen_template_limit_custom"

internal const val LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT = 0
internal const val LOCKSCREEN_TEMPLATE_LIMIT_50 = 1
internal const val LOCKSCREEN_TEMPLATE_LIMIT_60 = 2
internal const val LOCKSCREEN_TEMPLATE_LIMIT_80 = 3
internal const val LOCKSCREEN_TEMPLATE_LIMIT_100 = 4
internal const val LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM = 5
private const val KEY_STATUS_BAR_SCALE_ADJUSTMENT_MIGRATED =
    "status_bar_scale_adjustment_migrated"
private const val KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED =
    "status_bar_scale_absolute_migrated"
private const val KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED_V2 =
    "status_bar_scale_absolute_migrated_v2"

const val LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE = 0
const val LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE = 1
const val LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC = 2

data class GlassTuning(
    val blurPercent: Int = 100,
    val opacity: Int = 100,
    val color: Int = 0xFFFFFFFF.toInt(),
    val customColorEnabled: Boolean = false,
)

/**
 * Values map directly to the verified MiBackgroundStyle glass parameter
 * array. Zero deltas and a disabled override leave the platform untouched.
 */
data class MaterialOverride(
    val enabled: Boolean = false,
    val glassRadius: Int = 0,
    val blurPercent: Int = 100,
    val scalePercent: Int = 100,
    val brightness: Int = 0,
    val darker: Int = 0,
    val refraction: Int = 0,
    val burn: Int = 0,
    val saturation: Int = 0,
    val alpha: Int = 0,
    val edgeThickness: Int = 0,
    val reflection: Int = 0,
    val directionalLight: Int = 0,
    val backgroundSaturation: Int = 0,
    val backgroundBrightness: Int = 0,
    val tintEnabled: Boolean = false,
    val tintColor: Int = 0xFFFFFFFF.toInt(),
    val tintStrength: Int = 0,
)

data class ShadePreset(
    val name: String,
    val payload: String,
    val builtIn: Boolean = false,
)

data class HookSettings(
    val notificationElementsMaterial: MaterialOverride = MaterialOverride(),
    val controlCenterElementsMaterial: MaterialOverride = MaterialOverride(),
    val notificationCenterBackgroundMaterial: MaterialOverride = MaterialOverride(),
    val controlCenterBackgroundMaterial: MaterialOverride = MaterialOverride(),
    val shadeSettingsUnified: Boolean = false,
    val notificationOpacity: Int = 100,
    val controlCenterOpacity: Int = 100,
    val blurRadius: Float = 0f,
    val controlButtonOpacity: Int = 100,
    val controlButtonBlurRadius: Float = 0f,
    val controlSliderOpacity: Int = 100,
    val controlSliderBlurRadius: Float = 0f,
    val controlCardOpacity: Int = 100,
    val controlCardBlurRadius: Float = 0f,
    val notificationCardOpacity: Int = 100,
    val notificationCardBlurRadius: Float = 0f,
    val unifyNotificationMaterial: Boolean = false,
    val notificationContextUnified: Boolean = true,
    val notificationTypeUnified: Boolean = true,
    val notificationCenterBackground: GlassTuning = GlassTuning(),
    val controlCenterBackground: GlassTuning = GlassTuning(),
    val notificationCenterNormal: GlassTuning = GlassTuning(),
    val lockscreenNormal: GlassTuning = GlassTuning(),
    val notificationCenterMedia: GlassTuning = GlassTuning(),
    val lockscreenMedia: GlassTuning = GlassTuning(),
    val notificationCenterFocus: GlassTuning = GlassTuning(),
    val lockscreenFocus: GlassTuning = GlassTuning(),
    val controlCenterButton: GlassTuning = GlassTuning(),
    val controlCenterSlider: GlassTuning = GlassTuning(),
    val removeFocusAndIslandWhitelistLimit: Boolean = false,
    val islandEnabled: Boolean = false,
    val islandWidth: Int = 108,
    val expandedIslandBackgroundEnabled: Boolean = false,
    val expandedIslandBackgroundOpacity: Int = 97,
    val expandedIslandGlassBlurRadius: Int = 110,
    val expandedIslandGlassLargeBlurRadius: Int = 110,
    val expandedIslandSelfBlurRadius: Int = 0,
    val expandedIslandShowHighlight: Boolean = false,
    val superXiaoAiGlobalSearchAppearance: Boolean = false,
    val superXiaoAiBlacklistUnblocked: Boolean = false,
    val superXiaoAiClipboardUnblocked: Boolean = false,
    val superXiaoAiAiSafetyUnblocked: Boolean = false,
    val superXiaoAiVoiceSafetyUnblocked: Boolean = false,
    val superXiaoAiKeyboardStyleEnabled: Boolean = false,
    val superXiaoAiKeyboardColorMode: Int = 0,
    val superXiaoAiKeyboardCornerRadius: Int = 16,
    val superXiaoAiKeyboardOpacity: Int = 85,
    val superXiaoAiKeyboardBlur: Int = 50,
    val clockEnabled: Boolean = false,
    val clockSize: Float = 14.8f,
    val paddingEndEnabled: Boolean = false,
    val paddingEnd: Float = 0f,
    val paddingEndLegacyAbsolute: Float? = null,
    val paddingStartEnabled: Boolean = false,
    val paddingStart: Float = 12.5f,
    val heightEnabled: Boolean = false,
    val statusBarHeight: Int = 40,
    val paddingTopEnabled: Boolean = false,
    val paddingTop: Float = 0f,
    val paddingTopLegacyAbsolute: Float? = null,
    val topButtonsRadiusEnabled: Boolean = false,
    val topButtonsRadius: Float = 24f,
    val mediaCardRadiusEnabled: Boolean = false,
    val mediaCardRadius: Float = 24f,
    val sliderRadiusEnabled: Boolean = false,
    val sliderRadius: Float = 24f,
    val controlBottomButtonsRadiusEnabled: Boolean = false,
    val controlBottomButtonsRadius: Float = 24f,
    val volumePanelBlurRadius: Int = 24,
    val volumePanelGlassStrength: Int = 50,
    val volumePanelCornerRadius: Float = 24f,
    val volumePanelBackgroundOpacity: Int = 100,
    val volumePanelMaterialEnabled: Boolean = true,
    val deviceCenterRadiusEnabled: Boolean = false,
    val deviceCenterRadius: Float = 24f,
    val removeDepthImageLimit: Boolean = false,
    val rasterWallpaperEnabled: Boolean = false,
    val rasterWallpaperUris: String = "[]",
    val rasterWallpaperSensitivityPreset: Int = 1,
    val rasterWallpaperCustomSensitivity: Float = 1f,
    val notificationFodPositionLimitRemoved: Boolean = false,
    val fingerprintHideMode: Int = 0,
    val lockscreenClockColonForceVisible: Boolean = false,
    val lockscreenTemplateLimitMode: Int = LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT,
    val lockscreenTemplateLimitCustom: Int = 50,
    /** Bit mask: charging=1, do-not-disturb=2, notification count=4. */
    val lockscreenBottomTextMask: Int = 0,
    val lockscreenPinCircleBackgroundEnabled: Boolean = false,
    val lockscreenPinCircleRowSpacing: Float = 0f,
    val lockscreenShortcutBackgroundMode: Int = 0,
    val lockscreenShortcutGlassRadius: Float = 48f,
    val lockscreenShortcutBackgroundRadiusEnabled: Boolean = false,
    val lockscreenShortcutBackgroundRadius: Float = 24f,
    val lockscreenShortcutSpacingEnabled: Boolean = false,
    val lockscreenShortcutSpacing: Float = 0f,
    val lockscreenShortcutIconSizeEnabled: Boolean = false,
    val lockscreenShortcutIconSize: Float = 32f,
    val shortcutIconColorMode: Int = 0,
    val shortcutPureColor: Int = 0x73FFFFFF,
    val shortcutAdvancedMaterialColor: Int = 0xFFFFFFFF.toInt(),
    val shortcutAdvancedMaterialOpacity: Int = 14,
    val shortcutAdvancedMaterialBlurRadius: Int = 80,
    val shortcutAdvancedMaterialHighlight: Boolean = false,
    val shortcutSoftGlassColor: Int = 0xFFFFFFFF.toInt(),
    val shortcutSoftGlassOpacity: Int = 10,
    val shortcutSoftGlassBackdropBlurRadius: Int = 80,
    val shortcutSoftGlassBlurRadius: Int = 36,
    val shortcutSoftGlassLuminance: Float = 0.14f,
    val lockscreenMiniPlayerEnabled: Boolean = false,
    val lockscreenMiniPlayerLyricsEnabled: Boolean = false,
    val lockscreenMiniPlayerMediaNotificationMode: Int = LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
    val lockscreenMiniPlayerBackgroundMode: Int = 0,
    val lockscreenMiniPlayerWidth: Float = 240f,
    // This is the same unit as the lockscreen shortcut circle radius. The rendered card is
    // twice this value, so a shortcut radius of 10dp is matched by entering 10dp here.
    val lockscreenMiniPlayerHeight: Float = 36f,
    val lockscreenMiniPlayerArtworkCornerRadius: Float = 12f,
    val lockscreenWidgetEnabled: Boolean = false,
    /** Empty means use the current ro.product.marketname value. */
    val lockscreenWidgetDeviceName: String = "",
    /** Selected lock-screen widgets, persisted as a bit mask. */
    val lockscreenWidgetItems: Int = LOCKSCREEN_WIDGET_DEFAULT_ITEMS,
    /** Comma-separated widget flags, preserving the user-selected left-to-right order. */
    val lockscreenWidgetOrder: String = LOCKSCREEN_WIDGET_DEFAULT_ORDER,
    /** Changes whenever the local editor preview image is replaced. */
    val lockscreenWidgetPreviewVersion: Long = 0L,
    val lockscreenWidgetCombination: Int = LOCKSCREEN_WIDGET_COMBINATION_ONE,
    val lockscreenWidgetBatteryMaterialMode: Int = LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
    val lockscreenWidgetColorMode: Int = LOCKSCREEN_WIDGET_COLOR_AUTO,
    val lockscreenWidgetNotificationAvoid: Boolean = true,
    /** 0=none, 1=PNG, 2=SVG or Android Vector XML. */
    val lockscreenWidgetSignatureType: Int = LOCKSCREEN_WIDGET_SIGNATURE_NONE,
    /** Changes whenever the persisted signature asset is replaced or removed. */
    val lockscreenWidgetSignatureVersion: Long = 0L,
    val lockscreenWidgetSignatureColor: Int = 0xFFFFFFFF.toInt(),
    /** Percentage of the 142x64dp signature card's available content size. */
    val lockscreenWidgetSignatureScale: Int = 100,
    val lockscreenWidgetSignatureBackground: Boolean = true,
    val miniPlayerPureColor: Int = 0x73000000,
    val miniPlayerAdvancedMaterialColor: Int = 0xFFFFFFFF.toInt(),
    val miniPlayerAdvancedMaterialOpacity: Int = 14,
    val miniPlayerAdvancedMaterialBlurRadius: Int = 80,
    val miniPlayerAdvancedMaterialHighlight: Boolean = false,
    val miniPlayerSoftGlassColor: Int = 0xFFFFFFFF.toInt(),
    val miniPlayerSoftGlassOpacity: Int = 10,
    val miniPlayerSoftGlassBackdropBlurRadius: Int = 80,
    val miniPlayerSoftGlassBlurRadius: Int = 36,
    val miniPlayerSoftGlassLuminance: Float = 0.14f,
    val keepSoftGlassAfterGlobalTheme: Boolean = false,
    val removeClockMaterialLimit: Boolean = false,
    val hideStatusBarNetworkType: Boolean = false,
    val hideStatusBarWifiStandard: Boolean = false,
    val hideStatusBarClockText: Boolean = false,
    val hideStatusBarNetworkActivity: Boolean = false,
    val stackedMobileSignalEnabled: Boolean = false,
    val stackedMobileSignalScale: Float = 1f,
    val stackedMobileSignalVerticalOffset: Float = 0f,
    val stackedMobileSignalLeftMargin: Float = 0f,
    val stackedMobileSignalRightMargin: Float = 0f,
    /** 0=do not hide, 1=hide non-data SIM, 2=hide all system signal icons. */
    val mobileSignalHideMode: Int = 0,
    /** 0=system default, 1=independent text, 2=hidden. */
    val mobileNetworkTypeMode: Int = 0,
    /** 0=before signal, 1=after signal. */
    val mobileNetworkTypePosition: Int = 0,
    /** 0=always show, 1=show while mobile data is in use. */
    val mobileNetworkTypeDisplayLogic: Int = 0,
    val mobileNetworkTypeCustomText: String = "",
    val mobileNetworkTypeShrink5gaA: Boolean = false,
    val mobileNetworkTypeScale: Float = 1f,
    val mobileNetworkTypeVerticalOffset: Float = 0f,
    val mobileNetworkTypeLeftMargin: Float = 0f,
    val mobileNetworkTypeRightMargin: Float = 0f,
    val systemUpdateDisabled: Boolean = false,
    val systemUpdateOtaLimitRemoved: Boolean = false,
    val systemUpdateVersionSpoofEnabled: Boolean = false,
    val systemUpdateVersion: String = "",
    val systemUpdateSotaVersion: String = "",
    val themeMode: String = "system",
    val navigationStyle: String = "hyper_os",
    val navigationLabelMode: String = "icon_and_text",
    val predictiveBackEnabled: Boolean = true,
    val predictiveBackProgress: Int = 90,
)

class HookSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: HookSettings = run {
        migrateStatusBarScaleToAbsolute(local)
        local.toSettings()
    }
        private set

    fun reload() {
        settings = local.toSettings()
    }

    init {
        local.edit().removeLegacyIslandPreferences().removeLegacyShadePreferences().apply()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        migrateStatusBarScaleToAbsolute(remote)
        remote.edit().removeLegacyIslandPreferences().removeLegacyShadePreferences().apply()
        settings = if (remote.contains(KEY_INITIALIZED)) {
            remote.toSettings()
        } else {
            settings
        }
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (HookSettings) -> HookSettings) {
        settings = transform(settings)
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    fun userShadePresets(): List<ShadePreset> = runCatching {
        val entries = JSONObject(local.getString(KEY_USER_SHADE_PRESETS, "{}") ?: "{}")
        entries.keys().asSequence().mapNotNull { name ->
            entries.optString(name).takeIf { it.isNotBlank() }?.let { ShadePreset(name, it) }
        }.toList().sortedBy { it.name }
    }.getOrDefault(emptyList())

    fun saveUserShadePreset(name: String, settings: HookSettings) {
        val normalizedName = name.trim().take(MAX_PRESET_NAME_LENGTH)
        require(normalizedName.isNotBlank())
        val entries = JSONObject(local.getString(KEY_USER_SHADE_PRESETS, "{}") ?: "{}")
        entries.put(normalizedName, settings.exportShadePreset(normalizedName))
        local.edit().putString(KEY_USER_SHADE_PRESETS, entries.toString()).apply()
    }

    fun deleteUserShadePreset(name: String) {
        val entries = JSONObject(local.getString(KEY_USER_SHADE_PRESETS, "{}") ?: "{}")
        entries.remove(name)
        local.edit().putString(KEY_USER_SHADE_PRESETS, entries.toString()).apply()
    }
}

private const val KEY_INITIALIZED = "initialized"
private const val KEY_USER_SHADE_PRESETS = "user_shade_presets_v1"
private const val MAX_PRESET_NAME_LENGTH = 40
private const val KEY_NOTIFICATION_ELEMENTS_MATERIAL = "shade_notification_elements_material_v2"
private const val KEY_CONTROL_CENTER_ELEMENTS_MATERIAL = "shade_control_center_elements_material_v2"
private const val KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL = "shade_notification_center_background_material_v2"
private const val KEY_CONTROL_CENTER_BACKGROUND_MATERIAL = "shade_control_center_background_material_v2"
private const val KEY_SHADE_SETTINGS_UNIFIED = "shade_settings_unified"
private const val LEGACY_EXPANDED_ISLAND_BACKGROUND_COLOR = "expanded_island_background_color"
private const val LEGACY_ISLAND_GLOW_ENABLED = "island_glow_enabled"
private const val LEGACY_ISLAND_PROGRESS_STYLE = "island_progress_style"
private const val LEGACY_ISLAND_MUSIC_SOURCE_ICON_ENABLED = "island_music_source_icon_enabled"
private const val LEGACY_ISLAND_TITLE_TOP_SPACING = "island_title_top_spacing"
private const val KEY_NOTIFICATION_OPACITY = "notification_opacity"
private const val KEY_CONTROL_CENTER_OPACITY = "control_center_opacity"
private const val KEY_BLUR_RADIUS = "blur_radius"
private const val KEY_CONTROL_BUTTON_OPACITY = "control_button_opacity"
private const val KEY_CONTROL_BUTTON_BLUR_RADIUS = "control_button_blur_radius"
private const val KEY_CONTROL_SLIDER_OPACITY = "control_slider_opacity"
private const val KEY_CONTROL_SLIDER_BLUR_RADIUS = "control_slider_blur_radius"
private const val KEY_CONTROL_CARD_OPACITY = "control_card_opacity"
private const val KEY_CONTROL_CARD_BLUR_RADIUS = "control_card_blur_radius"
private const val KEY_NOTIFICATION_CARD_OPACITY = "notification_card_opacity"
private const val KEY_NOTIFICATION_CARD_BLUR_RADIUS = "notification_card_blur_radius"
private const val KEY_UNIFY_NOTIFICATION_MATERIAL = "unify_notification_material"
private const val KEY_NOTIFICATION_CONTEXT_UNIFIED = "notification_context_unified"
private const val KEY_NOTIFICATION_TYPE_UNIFIED = "notification_type_unified"
private const val KEY_NOTIFICATION_CENTER_BACKGROUND = "notification_center_background_glass"
private const val KEY_CONTROL_CENTER_BACKGROUND = "control_center_background_glass"
private const val KEY_NOTIFICATION_CENTER_NORMAL = "notification_center_normal_glass"
private const val KEY_LOCKSCREEN_NORMAL = "lockscreen_normal_glass"
private const val KEY_NOTIFICATION_CENTER_MEDIA = "notification_center_media_glass"
private const val KEY_LOCKSCREEN_MEDIA = "lockscreen_media_glass"
private const val KEY_NOTIFICATION_CENTER_FOCUS = "notification_center_focus_glass"
private const val KEY_LOCKSCREEN_FOCUS = "lockscreen_focus_glass"
private const val KEY_CONTROL_CENTER_BUTTON = "control_center_button_glass"
private const val KEY_CONTROL_CENTER_SLIDER = "control_center_slider_glass"
private const val KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT =
    "remove_focus_and_island_whitelist_limit"
private const val KEY_ISLAND_ENABLED = "island_enabled"
private const val KEY_ISLAND_WIDTH = "island_width"
private const val KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED = "expanded_island_background_enabled"
private const val KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY = "expanded_island_background_opacity"
private const val KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS = "expanded_island_glass_blur_radius"
private const val KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS = "expanded_island_glass_large_blur_radius"
private const val KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS = "expanded_island_self_blur_radius"
private const val KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT = "expanded_island_show_highlight"
private const val KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE =
    "super_xiaoai_global_search_appearance"
internal const val KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED = "super_xiaoai_blacklist_unblocked"
internal const val KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED = "super_xiaoai_clipboard_unblocked"
internal const val KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED = "super_xiaoai_ai_safety_unblocked"
internal const val KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED = "super_xiaoai_voice_safety_unblocked"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED = "super_xiaoai_keyboard_style_enabled"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE = "super_xiaoai_keyboard_color_mode"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS = "super_xiaoai_keyboard_corner_radius"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_OPACITY = "super_xiaoai_keyboard_opacity"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_BLUR = "super_xiaoai_keyboard_blur"
private const val KEY_CLOCK_ENABLED = "clock_enabled"
private const val KEY_CLOCK_SIZE = "clock_size"
private const val KEY_PADDING_END_ENABLED = "padding_end_enabled"
private const val KEY_PADDING_END = "padding_end"
private const val KEY_PADDING_END_LEGACY_ABSOLUTE = "padding_end_legacy_absolute"
private const val KEY_PADDING_START_ENABLED = "padding_start_enabled"
private const val KEY_PADDING_START = "padding_start"
private const val KEY_HEIGHT_ENABLED = "height_enabled"
private const val KEY_STATUS_BAR_HEIGHT = "status_bar_height"
private const val KEY_PADDING_TOP_ENABLED = "padding_top_enabled"
private const val KEY_PADDING_TOP = "padding_top"
private const val KEY_PADDING_TOP_LEGACY_ABSOLUTE = "padding_top_legacy_absolute"
private const val KEY_STATUS_DIMENSION_DELTAS_V1 = "status_dimension_deltas_v1"
private const val KEY_TOP_BUTTONS_RADIUS_ENABLED = "top_buttons_radius_enabled"
private const val KEY_TOP_BUTTONS_RADIUS = "top_buttons_radius"
private const val KEY_MEDIA_CARD_RADIUS_ENABLED = "media_card_radius_enabled"
private const val KEY_MEDIA_CARD_RADIUS = "media_card_radius"
private const val KEY_SLIDER_RADIUS_ENABLED = "slider_radius_enabled"
private const val KEY_SLIDER_RADIUS = "slider_radius"
private const val KEY_CONTROL_BOTTOM_BUTTONS_RADIUS_ENABLED = "control_bottom_buttons_radius_enabled"
private const val KEY_CONTROL_BOTTOM_BUTTONS_RADIUS = "control_bottom_buttons_radius"
private const val KEY_VOLUME_PANEL_BLUR_RADIUS = "volume_panel_blur_radius"
private const val KEY_VOLUME_PANEL_GLASS_STRENGTH = "volume_panel_glass_strength"
private const val KEY_VOLUME_PANEL_CORNER_RADIUS = "volume_panel_corner_radius"
private const val KEY_VOLUME_PANEL_BACKGROUND_OPACITY = "volume_panel_background_opacity"
private const val KEY_VOLUME_PANEL_BACKGROUND_TRANSPARENCY_LEGACY = "volume_panel_background_transparency"
private const val KEY_VOLUME_PANEL_MATERIAL_ENABLED = "volume_panel_material_enabled"
private const val KEY_DEVICE_CENTER_RADIUS_ENABLED = "device_center_radius_enabled"
private const val KEY_DEVICE_CENTER_RADIUS = "device_center_radius"
private const val KEY_REMOVE_DEPTH_IMAGE_LIMIT = "remove_depth_image_limit"
private const val KEY_RASTER_WALLPAPER_ENABLED = "raster_wallpaper_enabled"
private const val KEY_RASTER_WALLPAPER_URIS = "raster_wallpaper_uris"
private const val KEY_RASTER_WALLPAPER_SENSITIVITY_PRESET = "raster_wallpaper_sensitivity_preset"
private const val KEY_RASTER_WALLPAPER_CUSTOM_SENSITIVITY = "raster_wallpaper_custom_sensitivity"
private const val KEY_NOTIFICATION_FOD_MODE = "notification_fod_mode"
private const val KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED = "notification_fod_position_limit_removed"
private const val KEY_FINGERPRINT_HIDE_MODE = "fingerprint_hide_mode"
private const val KEY_NOTIFICATIONS_IGNORE_FOD = "notifications_ignore_fod"
private const val KEY_HIDE_LOCKSCREEN_CHARGING_TEXT = "hide_lockscreen_charging_text"
private const val KEY_LOCKSCREEN_BOTTOM_TEXT_MASK = "lockscreen_bottom_text_mask"
const val KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED =
    "lockscreen_pin_circle_background_enabled"
const val KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING = "lockscreen_pin_circle_row_spacing"
private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE = "lockscreen_shortcut_background_mode"
private const val KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED = "lockscreen_shortcut_glass_enabled"
private const val KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS = "lockscreen_shortcut_glass_radius"
private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED =
    "lockscreen_shortcut_background_radius_enabled"
private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS =
    "lockscreen_shortcut_background_radius"
private const val KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED = "lockscreen_shortcut_spacing_enabled"
private const val KEY_LOCKSCREEN_SHORTCUT_SPACING = "lockscreen_shortcut_spacing"
private const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED = "lockscreen_shortcut_icon_size_enabled"
private const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE = "lockscreen_shortcut_icon_size"
private const val KEY_SHORTCUT_ICON_COLOR_MODE = "shortcut_icon_color_mode"
private const val KEY_SHORTCUT_PURE_COLOR = "shortcut_pure_color"
private const val KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR = "shortcut_advanced_material_color"
private const val KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY = "shortcut_advanced_material_opacity"
private const val KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS = "shortcut_advanced_material_blur_radius"
private const val KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT = "shortcut_advanced_material_highlight"
private const val KEY_SHORTCUT_SOFT_GLASS_COLOR = "shortcut_soft_glass_color"
private const val KEY_SHORTCUT_SOFT_GLASS_OPACITY = "shortcut_soft_glass_opacity"
private const val KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS = "shortcut_soft_glass_backdrop_blur_radius"
private const val KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS = "shortcut_soft_glass_blur_radius"
private const val KEY_SHORTCUT_SOFT_GLASS_LUMINANCE = "shortcut_soft_glass_luminance"
private const val KEY_LOCKSCREEN_MINI_PLAYER_ENABLED = "lockscreen_mini_player_enabled"
private const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED = "lockscreen_mini_player_lyrics_enabled"
private const val KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION =
    "lockscreen_mini_player_hide_media_notification"
private const val KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE =
    "lockscreen_mini_player_media_notification_mode"
private const val KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE = "lockscreen_mini_player_background_mode"
private const val KEY_LOCKSCREEN_MINI_PLAYER_WIDTH = "lockscreen_mini_player_width"
private const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT = "lockscreen_mini_player_height"
private const val KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS =
    "lockscreen_mini_player_artwork_corner_radius"
private const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED =
    "lockscreen_mini_player_height_radius_migrated"
internal const val KEY_LOCKSCREEN_WIDGET_ENABLED = "lockscreen_widget_enabled"
internal const val KEY_LOCKSCREEN_WIDGET_DEVICE_NAME = "lockscreen_widget_device_name"
internal const val KEY_LOCKSCREEN_WIDGET_ITEMS = "lockscreen_widget_items"
internal const val KEY_LOCKSCREEN_WIDGET_ORDER = "lockscreen_widget_order"
internal const val KEY_LOCKSCREEN_WIDGET_PREVIEW_VERSION = "lockscreen_widget_preview_version"
internal const val KEY_LOCKSCREEN_WIDGET_COMBINATION = "lockscreen_widget_combination"
internal const val KEY_LOCKSCREEN_WIDGET_BATTERY_MATERIAL_MODE = "lockscreen_widget_battery_material_mode"
internal const val KEY_LOCKSCREEN_WIDGET_COLOR_MODE = "lockscreen_widget_color_mode"
internal const val KEY_LOCKSCREEN_WIDGET_NOTIFICATION_AVOID = "lockscreen_widget_notification_avoid"
internal const val KEY_LOCKSCREEN_WIDGET_SIGNATURE_TYPE = "lockscreen_widget_signature_type"
internal const val KEY_LOCKSCREEN_WIDGET_SIGNATURE_VERSION = "lockscreen_widget_signature_version"
internal const val KEY_LOCKSCREEN_WIDGET_SIGNATURE_COLOR = "lockscreen_widget_signature_color"
internal const val KEY_LOCKSCREEN_WIDGET_SIGNATURE_SCALE = "lockscreen_widget_signature_scale"
internal const val KEY_LOCKSCREEN_WIDGET_SIGNATURE_BACKGROUND = "lockscreen_widget_signature_background"
internal const val LOCKSCREEN_WIDGET_SIGNATURE_SLOT = "lockscreen_widget_signature"
internal const val LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE = 0
internal const val LOCKSCREEN_WIDGET_BATTERY_MATERIAL_ADVANCED = 1
internal const val LOCKSCREEN_WIDGET_BATTERY_MATERIAL_SOFT = 2
internal const val LOCKSCREEN_WIDGET_COMBINATION_ONE = 0
internal const val LOCKSCREEN_WIDGET_COMBINATION_TWO = 1
/** The two original combination-one cards remain distinct widgets in the editor. */
internal const val LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER = 1
internal const val LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY = 1 shl 1
internal const val LOCKSCREEN_WIDGET_ITEM_SUN = 1 shl 2
internal const val LOCKSCREEN_WIDGET_ITEM_STEPS = 1 shl 3
internal const val LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER = 1 shl 4
internal const val LOCKSCREEN_WIDGET_ITEM_SIGNATURE = 1 shl 5
internal const val LOCKSCREEN_WIDGET_ITEM_HUMIDITY = 1 shl 6
internal const val LOCKSCREEN_WIDGET_ITEM_AQI = 1 shl 7
internal const val LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE = 1 shl 8
internal const val LOCKSCREEN_WIDGET_ITEM_WIND = 1 shl 9
internal const val LOCKSCREEN_WIDGET_ITEM_STAND = 1 shl 10
internal const val LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE = 1 shl 11
internal const val LOCKSCREEN_WIDGET_ITEM_ALARM = 1 shl 12
internal const val LOCKSCREEN_WIDGET_ITEM_SCHEDULE = 1 shl 13
internal const val LOCKSCREEN_WIDGET_ALL_ITEMS =
    LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER or LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY or
        LOCKSCREEN_WIDGET_ITEM_SUN or LOCKSCREEN_WIDGET_ITEM_STEPS or
        LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER or LOCKSCREEN_WIDGET_ITEM_SIGNATURE or
        LOCKSCREEN_WIDGET_ITEM_HUMIDITY or LOCKSCREEN_WIDGET_ITEM_AQI or
        LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE or LOCKSCREEN_WIDGET_ITEM_WIND or
        LOCKSCREEN_WIDGET_ITEM_STAND or LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE or
        LOCKSCREEN_WIDGET_ITEM_ALARM or LOCKSCREEN_WIDGET_ITEM_SCHEDULE
internal const val LOCKSCREEN_WIDGET_DEFAULT_ITEMS =
    LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER or LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY
internal const val LOCKSCREEN_WIDGET_DEFAULT_ORDER = "1,2,16,4,8,32,64,128,256,512,1024,2048,4096,8192"
internal const val LOCKSCREEN_WIDGET_COLOR_LIGHT = 0
internal const val LOCKSCREEN_WIDGET_COLOR_DARK = 1
internal const val LOCKSCREEN_WIDGET_COLOR_AUTO = 2
internal const val LOCKSCREEN_WIDGET_SIGNATURE_NONE = 0
internal const val LOCKSCREEN_WIDGET_SIGNATURE_PNG = 1
internal const val LOCKSCREEN_WIDGET_SIGNATURE_VECTOR = 2
private const val KEY_MINI_PLAYER_PURE_COLOR = "mini_player_pure_color"
private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR = "mini_player_advanced_material_color"
private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY = "mini_player_advanced_material_opacity"
private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS =
    "mini_player_advanced_material_blur_radius"
private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT =
    "mini_player_advanced_material_highlight"
private const val KEY_MINI_PLAYER_SOFT_GLASS_COLOR = "mini_player_soft_glass_color"
private const val KEY_MINI_PLAYER_SOFT_GLASS_OPACITY = "mini_player_soft_glass_opacity"
private const val KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS =
    "mini_player_soft_glass_backdrop_blur_radius"
private const val KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS = "mini_player_soft_glass_blur_radius"
private const val KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE = "mini_player_soft_glass_luminance"
private const val KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME = "keep_soft_glass_after_global_theme"
private const val KEY_REMOVE_CLOCK_MATERIAL_LIMIT = "remove_clock_material_limit"
private const val KEY_HIDE_STATUS_BAR_NETWORK_TYPE = "hide_status_bar_network_type"
private const val KEY_HIDE_STATUS_BAR_WIFI_STANDARD = "hide_status_bar_wifi_standard"
private const val KEY_HIDE_STATUS_BAR_CLOCK_TEXT = "hide_status_bar_clock_text"
private const val KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY = "hide_status_bar_network_activity"
private const val KEY_MOBILE_NETWORK_TYPE_MODE = "mobile_network_type_mode"
private const val KEY_MOBILE_NETWORK_TYPE_POSITION = "mobile_network_type_position"
private const val KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC = "mobile_network_type_display_logic"
private const val KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT = "mobile_network_type_custom_text"
private const val KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A = "mobile_network_type_shrink_5ga_a"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_NAVIGATION_STYLE = "navigation_style"
private const val KEY_NAVIGATION_LABEL_MODE = "navigation_label_mode"
private const val KEY_PREDICTIVE_BACK_ENABLED = "predictive_back_enabled"
private const val KEY_PREDICTIVE_BACK_PROGRESS = "predictive_back_progress"
private fun migrateStatusBarScaleToAbsolute(preferences: SharedPreferences) {
    if (preferences.getBoolean(KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED_V2, false)) return
    val editor = preferences.edit()
    // The previous UI stored a delta where 0 meant the default size. The current UI stores
    // the final multiplier, so migrate each existing value once to keep the old appearance.
    if (preferences.contains(KEY_STACKED_MOBILE_SIGNAL_SCALE)) {
        editor.putFloat(
            KEY_STACKED_MOBILE_SIGNAL_SCALE,
            preferences.getFloat(KEY_STACKED_MOBILE_SIGNAL_SCALE, 0f) + 1f,
        )
    }
    if (preferences.contains(KEY_MOBILE_NETWORK_TYPE_SCALE)) {
        editor.putFloat(
            KEY_MOBILE_NETWORK_TYPE_SCALE,
            preferences.getFloat(KEY_MOBILE_NETWORK_TYPE_SCALE, 0f) + 1f,
        )
    }
    editor.putBoolean(KEY_STATUS_BAR_SCALE_ADJUSTMENT_MIGRATED, true)
    editor.putBoolean(KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED, true)
    editor.putBoolean(KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED_V2, true).apply()
}

internal fun SharedPreferences.readMiniPlayerHeightRadius(): Float {
    val raw = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, 36f)
    if (contains(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT) &&
        !getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, false)
    ) {
        // Values written by the previous version represented the final card height (48..120dp).
        // Convert them once so an existing 59dp card remains 59dp after the setting changes to
        // the shortcut-radius unit (29.5dp -> 59dp rendered height).
        val converted = if (raw >= 48f) raw / 2f else raw
        edit()
            .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, converted)
            .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, true)
            .apply()
        return converted.coerceIn(10f, 60f)
    }
    return raw.coerceIn(10f, 60f)
}

internal fun SharedPreferences.readLockscreenWidgetItems(): Int {
    val stored = if (contains(KEY_LOCKSCREEN_WIDGET_ITEMS)) {
        getInt(KEY_LOCKSCREEN_WIDGET_ITEMS, LOCKSCREEN_WIDGET_DEFAULT_ITEMS)
    } else {
        when (getInt(KEY_LOCKSCREEN_WIDGET_COMBINATION, LOCKSCREEN_WIDGET_COMBINATION_ONE)) {
            LOCKSCREEN_WIDGET_COMBINATION_TWO ->
                LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER or LOCKSCREEN_WIDGET_ITEM_SUN or LOCKSCREEN_WIDGET_ITEM_STEPS
            else -> LOCKSCREEN_WIDGET_DEFAULT_ITEMS
        }
    }
    // Before compact weather became its own widget, the old combination-two mask reused
    // flag 1. Migrate that exact legacy composition without changing combination one.
    val legacyCombinationTwo =
        LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER or LOCKSCREEN_WIDGET_ITEM_SUN or LOCKSCREEN_WIDGET_ITEM_STEPS
    val normalized = if (stored == legacyCombinationTwo) {
        LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER or LOCKSCREEN_WIDGET_ITEM_SUN or LOCKSCREEN_WIDGET_ITEM_STEPS
    } else {
        stored
    }
    return (normalized and LOCKSCREEN_WIDGET_ALL_ITEMS).takeIf { it != 0 }
        ?: LOCKSCREEN_WIDGET_DEFAULT_ITEMS
}

internal fun SharedPreferences.readLockscreenWidgetOrder(itemMask: Int = readLockscreenWidgetItems()): List<Int> {
    val hasCompactWeather = itemMask and LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER != 0
    val hasDetailedWeather = itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER != 0
    return getString(KEY_LOCKSCREEN_WIDGET_ORDER, LOCKSCREEN_WIDGET_DEFAULT_ORDER)
        .orEmpty()
        .split(',')
        .mapNotNull(String::toIntOrNull)
        .map { flag ->
            if (hasCompactWeather && !hasDetailedWeather && flag == LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER) {
                LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER
            } else {
                flag
            }
        }
        .filter { it in listOf(
            LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER,
            LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY,
            LOCKSCREEN_WIDGET_ITEM_SUN,
            LOCKSCREEN_WIDGET_ITEM_STEPS,
            LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE,
            LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER,
            LOCKSCREEN_WIDGET_ITEM_SIGNATURE,
            LOCKSCREEN_WIDGET_ITEM_HUMIDITY,
            LOCKSCREEN_WIDGET_ITEM_AQI,
            LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE,
            LOCKSCREEN_WIDGET_ITEM_WIND,
            LOCKSCREEN_WIDGET_ITEM_STAND,
            LOCKSCREEN_WIDGET_ITEM_ALARM,
            LOCKSCREEN_WIDGET_ITEM_SCHEDULE,
        ) && itemMask and it != 0 }
        .distinct()
        .let { stored ->
            stored + listOf(
                LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER,
                LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY,
                LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER,
                LOCKSCREEN_WIDGET_ITEM_SUN,
                LOCKSCREEN_WIDGET_ITEM_STEPS,
                LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE,
                LOCKSCREEN_WIDGET_ITEM_SIGNATURE,
                LOCKSCREEN_WIDGET_ITEM_HUMIDITY,
                LOCKSCREEN_WIDGET_ITEM_AQI,
                LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE,
                LOCKSCREEN_WIDGET_ITEM_WIND,
                LOCKSCREEN_WIDGET_ITEM_STAND,
                LOCKSCREEN_WIDGET_ITEM_ALARM,
                LOCKSCREEN_WIDGET_ITEM_SCHEDULE,
            ).filter { itemMask and it != 0 && it !in stored }
        }
}

private fun SharedPreferences.toSettings(): HookSettings {
    val statusDimensionsAreDeltas = getBoolean(KEY_STATUS_DIMENSION_DELTAS_V1, false)
    val oldPaddingEnd = getFloat(KEY_PADDING_END, 6f).coerceIn(0f, 32f)
    val oldPaddingTop = getFloat(KEY_PADDING_TOP, 15f).coerceIn(0f, 32f)
    return HookSettings(
    notificationElementsMaterial = getMaterialOverride(KEY_NOTIFICATION_ELEMENTS_MATERIAL),
    controlCenterElementsMaterial = getMaterialOverride(KEY_CONTROL_CENTER_ELEMENTS_MATERIAL),
    notificationCenterBackgroundMaterial = getMaterialOverride(KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL),
    controlCenterBackgroundMaterial = getMaterialOverride(KEY_CONTROL_CENTER_BACKGROUND_MATERIAL),
    shadeSettingsUnified = getBoolean(KEY_SHADE_SETTINGS_UNIFIED, false),
    notificationOpacity = getInt(KEY_NOTIFICATION_OPACITY, 100).coerceIn(0, 100),
    controlCenterOpacity = getInt(KEY_CONTROL_CENTER_OPACITY, 100).coerceIn(0, 100),
    blurRadius = getFloat(KEY_BLUR_RADIUS, 0f).coerceIn(0f, 40f),
    controlButtonOpacity = getInt(KEY_CONTROL_BUTTON_OPACITY, 100).coerceIn(0, 100),
    controlButtonBlurRadius = getFloat(KEY_CONTROL_BUTTON_BLUR_RADIUS, 0f).coerceIn(0f, 40f),
    controlSliderOpacity = getInt(KEY_CONTROL_SLIDER_OPACITY, 100).coerceIn(0, 100),
    controlSliderBlurRadius = getFloat(KEY_CONTROL_SLIDER_BLUR_RADIUS, 0f).coerceIn(0f, 40f),
    controlCardOpacity = getInt(KEY_CONTROL_CARD_OPACITY, 100).coerceIn(0, 100),
    controlCardBlurRadius = getFloat(KEY_CONTROL_CARD_BLUR_RADIUS, 0f).coerceIn(0f, 40f),
    notificationCardOpacity = getInt(KEY_NOTIFICATION_CARD_OPACITY, 100).coerceIn(0, 100),
    notificationCardBlurRadius = getFloat(KEY_NOTIFICATION_CARD_BLUR_RADIUS, 0f).coerceIn(0f, 40f),
    unifyNotificationMaterial = getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false),
    notificationContextUnified = getBoolean(KEY_NOTIFICATION_CONTEXT_UNIFIED, true),
    notificationTypeUnified = getBoolean(KEY_NOTIFICATION_TYPE_UNIFIED, true),
    notificationCenterBackground = getGlassTuning(KEY_NOTIFICATION_CENTER_BACKGROUND),
    controlCenterBackground = getGlassTuning(KEY_CONTROL_CENTER_BACKGROUND),
    notificationCenterNormal = getGlassTuning(KEY_NOTIFICATION_CENTER_NORMAL),
    lockscreenNormal = getGlassTuning(KEY_LOCKSCREEN_NORMAL),
    notificationCenterMedia = getGlassTuning(KEY_NOTIFICATION_CENTER_MEDIA),
    lockscreenMedia = getGlassTuning(KEY_LOCKSCREEN_MEDIA),
    notificationCenterFocus = getGlassTuning(KEY_NOTIFICATION_CENTER_FOCUS),
    lockscreenFocus = getGlassTuning(KEY_LOCKSCREEN_FOCUS),
    controlCenterButton = getGlassTuning(KEY_CONTROL_CENTER_BUTTON),
    controlCenterSlider = getGlassTuning(KEY_CONTROL_CENTER_SLIDER),
    removeFocusAndIslandWhitelistLimit =
        getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false),
    islandEnabled = getBoolean(KEY_ISLAND_ENABLED, false),
    islandWidth = getInt(KEY_ISLAND_WIDTH, 108).coerceIn(108, 190),
    expandedIslandBackgroundEnabled = getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false),
    expandedIslandBackgroundOpacity = getInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, 35).coerceIn(0, 100),
    expandedIslandGlassBlurRadius = getInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, 10).coerceIn(0, 40),
    expandedIslandGlassLargeBlurRadius = getInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, 10).coerceIn(0, 40),
    expandedIslandSelfBlurRadius = getInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, 0).coerceIn(0, 40),
    expandedIslandShowHighlight = getBoolean(KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT, false),
    superXiaoAiGlobalSearchAppearance = getBoolean(
        KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE,
        false,
    ),
    superXiaoAiBlacklistUnblocked = getBoolean(KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED, false),
    superXiaoAiClipboardUnblocked = getBoolean(KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED, false),
    superXiaoAiAiSafetyUnblocked = getBoolean(KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED, false),
    superXiaoAiVoiceSafetyUnblocked = getBoolean(KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED, false),
    superXiaoAiKeyboardStyleEnabled = getBoolean(KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED, false),
    superXiaoAiKeyboardColorMode = getInt(KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE, 0).coerceIn(0, 2),
    superXiaoAiKeyboardCornerRadius = getInt(KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS, 16).coerceIn(0, 48),
    superXiaoAiKeyboardOpacity = getInt(KEY_SUPER_XIAOAI_KEYBOARD_OPACITY, 85).coerceIn(0, 100),
    superXiaoAiKeyboardBlur = getInt(KEY_SUPER_XIAOAI_KEYBOARD_BLUR, 50).coerceIn(0, 100),
    clockEnabled = getBoolean(KEY_CLOCK_ENABLED, false),
    clockSize = getFloat(KEY_CLOCK_SIZE, 14.8f).coerceIn(10f, 24f),
    paddingEndEnabled = getBoolean(KEY_PADDING_END_ENABLED, false),
    paddingEnd = if (statusDimensionsAreDeltas) getFloat(KEY_PADDING_END, 0f).coerceIn(-35f, 35f) else 0f,
    paddingEndLegacyAbsolute = when {
        statusDimensionsAreDeltas && contains(KEY_PADDING_END_LEGACY_ABSOLUTE) ->
            getFloat(KEY_PADDING_END_LEGACY_ABSOLUTE, 0f).coerceIn(0f, 32f)
        !statusDimensionsAreDeltas && getBoolean(KEY_PADDING_END_ENABLED, false) -> oldPaddingEnd
        else -> null
    },
    paddingStartEnabled = getBoolean(KEY_PADDING_START_ENABLED, false),
    paddingStart = getFloat(KEY_PADDING_START, 12.5f).coerceIn(0f, 32f),
    heightEnabled = getBoolean(KEY_HEIGHT_ENABLED, false),
    statusBarHeight = getInt(KEY_STATUS_BAR_HEIGHT, 40).coerceIn(24, 72),
    paddingTopEnabled = getBoolean(KEY_PADDING_TOP_ENABLED, false),
    paddingTop = if (statusDimensionsAreDeltas) getFloat(KEY_PADDING_TOP, 0f).coerceIn(-35f, 35f) else 0f,
    paddingTopLegacyAbsolute = when {
        statusDimensionsAreDeltas && contains(KEY_PADDING_TOP_LEGACY_ABSOLUTE) ->
            getFloat(KEY_PADDING_TOP_LEGACY_ABSOLUTE, 0f).coerceIn(0f, 32f)
        !statusDimensionsAreDeltas && getBoolean(KEY_PADDING_TOP_ENABLED, false) -> oldPaddingTop
        else -> null
    },
    topButtonsRadiusEnabled = getBoolean(KEY_TOP_BUTTONS_RADIUS_ENABLED, false),
    topButtonsRadius = getFloat(KEY_TOP_BUTTONS_RADIUS, 24f).coerceIn(0f, 60f),
    mediaCardRadiusEnabled = getBoolean(KEY_MEDIA_CARD_RADIUS_ENABLED, false),
    mediaCardRadius = getFloat(KEY_MEDIA_CARD_RADIUS, 24f).coerceIn(0f, 60f),
    sliderRadiusEnabled = getBoolean(KEY_SLIDER_RADIUS_ENABLED, false),
    sliderRadius = getFloat(KEY_SLIDER_RADIUS, 24f).coerceIn(0f, 60f),
    controlBottomButtonsRadiusEnabled = getBoolean(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS_ENABLED, false),
    controlBottomButtonsRadius = getFloat(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS, 24f).coerceIn(0f, 60f),
    volumePanelBlurRadius = getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120),
    volumePanelGlassStrength = getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50).coerceIn(0, 100),
    volumePanelCornerRadius = getFloat(KEY_VOLUME_PANEL_CORNER_RADIUS, 24f).coerceIn(0f, 60f),
    volumePanelBackgroundOpacity = if (contains(KEY_VOLUME_PANEL_BACKGROUND_OPACITY)) {
        getInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, 100).coerceIn(0, 100)
    } else {
        // The pre-release control was labelled transparency. Preserve its value while
        // converting it to the new, direct opacity semantics.
        (100 - getInt(KEY_VOLUME_PANEL_BACKGROUND_TRANSPARENCY_LEGACY, 0)).coerceIn(0, 100)
    },
    volumePanelMaterialEnabled = getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true),
    deviceCenterRadiusEnabled = getBoolean(KEY_DEVICE_CENTER_RADIUS_ENABLED, false),
    deviceCenterRadius = getFloat(KEY_DEVICE_CENTER_RADIUS, 24f).coerceIn(0f, 60f),
    removeDepthImageLimit = getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false),
    rasterWallpaperEnabled = getBoolean(KEY_RASTER_WALLPAPER_ENABLED, false),
    rasterWallpaperUris = getString(KEY_RASTER_WALLPAPER_URIS, "[]").orEmpty(),
    rasterWallpaperSensitivityPreset = getInt(KEY_RASTER_WALLPAPER_SENSITIVITY_PRESET, 1).coerceIn(0, 4),
    rasterWallpaperCustomSensitivity = getFloat(KEY_RASTER_WALLPAPER_CUSTOM_SENSITIVITY, 1f).coerceIn(0.1f, 2f),
    notificationFodPositionLimitRemoved = if (contains(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED)) {
        getBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, false)
    } else {
        getInt(KEY_NOTIFICATION_FOD_MODE, if (getBoolean(KEY_NOTIFICATIONS_IGNORE_FOD, false)) 2 else 0)
            .coerceIn(0, 2) != 0
    },
    fingerprintHideMode = if (contains(KEY_FINGERPRINT_HIDE_MODE)) {
        getInt(KEY_FINGERPRINT_HIDE_MODE, 0).coerceIn(0, 2)
    } else {
        // The legacy hide-icon choice was global; preserve it during upgrade.
        if (getInt(KEY_NOTIFICATION_FOD_MODE, 0) == 1) 2 else 0
    },
    lockscreenClockColonForceVisible = getBoolean(
        KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE,
        false,
    ),
    lockscreenTemplateLimitMode = getInt(
        KEY_LOCKSCREEN_TEMPLATE_LIMIT_MODE,
        LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT,
    ).coerceIn(LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT, LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM),
    lockscreenTemplateLimitCustom = getInt(KEY_LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM, 50)
        .coerceIn(20, 200),
    lockscreenBottomTextMask = if (contains(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK)) {
        getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0).coerceIn(0, 7)
    } else {
        // The old mini-player switch forcibly wrote this key. Do not carry that implicit state
        // into the selectable mask; preserve the legacy toggle only when the mini-player is off.
        if (getBoolean(KEY_HIDE_LOCKSCREEN_CHARGING_TEXT, false) &&
            !getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)
        ) 1 else 0
    },
    lockscreenPinCircleBackgroundEnabled = getBoolean(
        KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED,
        false,
    ),
    lockscreenPinCircleRowSpacing = getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f)
        .coerceIn(-24f, 32f),
    lockscreenShortcutBackgroundMode = getInt(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE,
        if (getBoolean(KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED, false)) 3 else 0,
    ).coerceIn(0, 3),
    lockscreenShortcutGlassRadius = getFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, 48f)
        .coerceIn(10f, 60f),
    lockscreenShortcutBackgroundRadiusEnabled = getBoolean(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED,
        false,
    ),
    lockscreenShortcutBackgroundRadius = getFloat(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS,
        24f,
    ).coerceIn(0f, 60f),
    lockscreenShortcutSpacingEnabled = getBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, false),
    lockscreenShortcutSpacing = getFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, 0f).coerceIn(0f, 48f),
    lockscreenShortcutIconSizeEnabled = getBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, false),
    lockscreenShortcutIconSize = getFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, 32f).coerceIn(16f, 64f),
    shortcutIconColorMode = getInt(KEY_SHORTCUT_ICON_COLOR_MODE, 0).coerceIn(0, 2),
    shortcutPureColor = getInt(KEY_SHORTCUT_PURE_COLOR, 0x73FFFFFF),
    shortcutAdvancedMaterialColor = getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR, 0xFFFFFFFF.toInt()),
    shortcutAdvancedMaterialOpacity = getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY, 14).coerceIn(0, 100),
    shortcutAdvancedMaterialBlurRadius = getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS, 10).coerceIn(0, 40),
    shortcutAdvancedMaterialHighlight = getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
    shortcutSoftGlassColor = getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, 0xFFFFFFFF.toInt()),
    shortcutSoftGlassOpacity = getInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, 10).coerceIn(0, 100),
    shortcutSoftGlassBackdropBlurRadius = getInt(KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS, 10).coerceIn(0, 40),
    shortcutSoftGlassBlurRadius = getInt(KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS, 10).coerceIn(0, 40),
    shortcutSoftGlassLuminance = getFloat(KEY_SHORTCUT_SOFT_GLASS_LUMINANCE, 0.14f).coerceIn(0f, 0.4f),
    lockscreenMiniPlayerEnabled = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false),
    lockscreenMiniPlayerLyricsEnabled = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, false),
    lockscreenMiniPlayerMediaNotificationMode = if (
        contains(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE)
    ) {
        getInt(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE, LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE)
            .coerceIn(LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE, LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC)
    } else if (getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION, false)) {
        LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE
    } else {
        LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
    },
    lockscreenMiniPlayerBackgroundMode = getInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, 0)
        .coerceIn(0, 3),
    lockscreenMiniPlayerWidth = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, 240f)
        .coerceIn(160f, 360f),
    lockscreenMiniPlayerHeight = readMiniPlayerHeightRadius(),
    lockscreenMiniPlayerArtworkCornerRadius = getFloat(
        KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS,
        12f,
    ).coerceIn(0f, 60f),
    lockscreenWidgetEnabled = getBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, false),
    lockscreenWidgetDeviceName = getString(KEY_LOCKSCREEN_WIDGET_DEVICE_NAME, "")
        .orEmpty().take(64),
    lockscreenWidgetItems = readLockscreenWidgetItems(),
    lockscreenWidgetOrder = readLockscreenWidgetOrder().joinToString(","),
    lockscreenWidgetPreviewVersion = getLong(KEY_LOCKSCREEN_WIDGET_PREVIEW_VERSION, 0L),
    lockscreenWidgetCombination = getInt(
        KEY_LOCKSCREEN_WIDGET_COMBINATION,
        LOCKSCREEN_WIDGET_COMBINATION_ONE,
    ).coerceIn(
        LOCKSCREEN_WIDGET_COMBINATION_ONE,
        LOCKSCREEN_WIDGET_COMBINATION_TWO,
    ),
    lockscreenWidgetBatteryMaterialMode = getInt(
        KEY_LOCKSCREEN_WIDGET_BATTERY_MATERIAL_MODE,
        LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
    ).coerceIn(
        LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
        LOCKSCREEN_WIDGET_BATTERY_MATERIAL_SOFT,
    ),
    lockscreenWidgetColorMode = getInt(
        KEY_LOCKSCREEN_WIDGET_COLOR_MODE,
        LOCKSCREEN_WIDGET_COLOR_AUTO,
    ).coerceIn(
        LOCKSCREEN_WIDGET_COLOR_LIGHT,
        LOCKSCREEN_WIDGET_COLOR_AUTO,
    ),
    lockscreenWidgetNotificationAvoid = getBoolean(
        KEY_LOCKSCREEN_WIDGET_NOTIFICATION_AVOID,
        true,
    ),
    lockscreenWidgetSignatureType = getInt(
        KEY_LOCKSCREEN_WIDGET_SIGNATURE_TYPE,
        LOCKSCREEN_WIDGET_SIGNATURE_NONE,
    ).coerceIn(LOCKSCREEN_WIDGET_SIGNATURE_NONE, LOCKSCREEN_WIDGET_SIGNATURE_VECTOR),
    lockscreenWidgetSignatureVersion = getLong(KEY_LOCKSCREEN_WIDGET_SIGNATURE_VERSION, 0L),
    lockscreenWidgetSignatureColor = getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_COLOR, 0xFFFFFFFF.toInt()),
    lockscreenWidgetSignatureScale = getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_SCALE, 100).coerceIn(25, 200),
    lockscreenWidgetSignatureBackground = getBoolean(KEY_LOCKSCREEN_WIDGET_SIGNATURE_BACKGROUND, true),
    miniPlayerPureColor = getInt(KEY_MINI_PLAYER_PURE_COLOR, 0x73000000),
    miniPlayerAdvancedMaterialColor = getInt(
        KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR,
        0xFFFFFFFF.toInt(),
    ),
    miniPlayerAdvancedMaterialOpacity = getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY, 14)
        .coerceIn(0, 100),
    miniPlayerAdvancedMaterialBlurRadius = getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS, 10)
        .coerceIn(0, 40),
    miniPlayerAdvancedMaterialHighlight = getBoolean(
        KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT,
        false,
    ),
    miniPlayerSoftGlassColor = getInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, 0xFFFFFFFF.toInt()),
    miniPlayerSoftGlassOpacity = getInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, 10).coerceIn(0, 100),
    miniPlayerSoftGlassBackdropBlurRadius = getInt(
        KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
        10,
    ).coerceIn(0, 40),
    miniPlayerSoftGlassBlurRadius = getInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, 10)
        .coerceIn(0, 40),
    miniPlayerSoftGlassLuminance = getFloat(KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE, 0.14f)
        .coerceIn(0f, 0.4f),
    keepSoftGlassAfterGlobalTheme = getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false),
    removeClockMaterialLimit = getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false),
    hideStatusBarNetworkType = getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_TYPE, false),
    hideStatusBarWifiStandard = getBoolean(KEY_HIDE_STATUS_BAR_WIFI_STANDARD, false),
    hideStatusBarClockText = getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false),
    hideStatusBarNetworkActivity = getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY, false),
    stackedMobileSignalEnabled = getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false),
    stackedMobileSignalScale = getFloat(KEY_STACKED_MOBILE_SIGNAL_SCALE, 1f)
        .coerceIn(0.1f, 3f),
    stackedMobileSignalVerticalOffset = getFloat(KEY_STACKED_MOBILE_SIGNAL_VERTICAL_OFFSET, 0f)
        .coerceIn(-8f, 8f),
    stackedMobileSignalLeftMargin = getFloat(KEY_STACKED_MOBILE_SIGNAL_LEFT_MARGIN, 0f)
        .coerceIn(-8f, 8f),
    stackedMobileSignalRightMargin = getFloat(KEY_STACKED_MOBILE_SIGNAL_RIGHT_MARGIN, 0f)
        .coerceIn(-8f, 8f),
    mobileSignalHideMode = getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0).coerceIn(0, 2),
    mobileNetworkTypeMode = getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0).coerceIn(0, 2),
    mobileNetworkTypePosition = getInt(KEY_MOBILE_NETWORK_TYPE_POSITION, 0).coerceIn(0, 1),
    mobileNetworkTypeDisplayLogic = getInt(KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC, 0).coerceIn(0, 1),
    mobileNetworkTypeCustomText = getString(KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT, "")
        .orEmpty().take(128),
    mobileNetworkTypeShrink5gaA = getBoolean(KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A, false),
    mobileNetworkTypeScale = getFloat(KEY_MOBILE_NETWORK_TYPE_SCALE, 1f)
        .coerceIn(0.1f, 3f),
    mobileNetworkTypeVerticalOffset = getFloat(KEY_MOBILE_NETWORK_TYPE_VERTICAL_OFFSET, 0f)
        .coerceIn(-8f, 8f),
    mobileNetworkTypeLeftMargin = getFloat(KEY_MOBILE_NETWORK_TYPE_LEFT_MARGIN, 0f)
        .coerceIn(-8f, 8f),
    mobileNetworkTypeRightMargin = getFloat(KEY_MOBILE_NETWORK_TYPE_RIGHT_MARGIN, 0f)
        .coerceIn(-8f, 8f),
    systemUpdateDisabled = getBoolean(KEY_SYSTEM_UPDATE_DISABLED, false),
    systemUpdateOtaLimitRemoved = getBoolean(KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED, false),
    systemUpdateVersionSpoofEnabled = getBoolean(KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED, false),
    systemUpdateVersion = getString(KEY_SYSTEM_UPDATE_VERSION, "").orEmpty().take(128),
    systemUpdateSotaVersion = getString(KEY_SYSTEM_UPDATE_SOTA_VERSION, "").orEmpty().take(128),
    themeMode = getString(KEY_THEME_MODE, "system").orEmpty().ifBlank { "system" },
    navigationStyle = getString(KEY_NAVIGATION_STYLE, "hyper_os").orEmpty().ifBlank { "hyper_os" },
    navigationLabelMode = getString(KEY_NAVIGATION_LABEL_MODE, "icon_and_text").orEmpty().ifBlank { "icon_and_text" },
    predictiveBackEnabled = getBoolean(KEY_PREDICTIVE_BACK_ENABLED, true),
    predictiveBackProgress = getInt(KEY_PREDICTIVE_BACK_PROGRESS, 90).coerceIn(10, 100),
)
}

private fun SharedPreferences.Editor.removeLegacyIslandPreferences(): SharedPreferences.Editor =
    remove(LEGACY_EXPANDED_ISLAND_BACKGROUND_COLOR)
        .remove(LEGACY_ISLAND_GLOW_ENABLED)
        .remove(LEGACY_ISLAND_PROGRESS_STYLE)
        .remove(LEGACY_ISLAND_MUSIC_SOURCE_ICON_ENABLED)
        .remove(LEGACY_ISLAND_TITLE_TOP_SPACING)

private fun SharedPreferences.Editor.removeLegacyShadePreferences(): SharedPreferences.Editor =
    remove(KEY_NOTIFICATION_OPACITY)
        .remove(KEY_CONTROL_CENTER_OPACITY)
        .remove(KEY_BLUR_RADIUS)
        .remove(KEY_CONTROL_BUTTON_OPACITY)
        .remove(KEY_CONTROL_BUTTON_BLUR_RADIUS)
        .remove(KEY_CONTROL_SLIDER_OPACITY)
        .remove(KEY_CONTROL_SLIDER_BLUR_RADIUS)
        .remove(KEY_CONTROL_CARD_OPACITY)
        .remove(KEY_CONTROL_CARD_BLUR_RADIUS)
        .remove(KEY_NOTIFICATION_CARD_OPACITY)
        .remove(KEY_NOTIFICATION_CARD_BLUR_RADIUS)
        .remove(KEY_NOTIFICATION_CONTEXT_UNIFIED)
        .remove(KEY_NOTIFICATION_TYPE_UNIFIED)
        .remove(KEY_NOTIFICATION_CENTER_BACKGROUND)
        .remove(KEY_CONTROL_CENTER_BACKGROUND)
        .remove(KEY_NOTIFICATION_CENTER_NORMAL)
        .remove(KEY_LOCKSCREEN_NORMAL)
        .remove(KEY_NOTIFICATION_CENTER_MEDIA)
        .remove(KEY_LOCKSCREEN_MEDIA)
        .remove(KEY_NOTIFICATION_CENTER_FOCUS)
        .remove(KEY_LOCKSCREEN_FOCUS)
        .remove(KEY_CONTROL_CENTER_BUTTON)
        .remove(KEY_CONTROL_CENTER_SLIDER)

fun SharedPreferences.getMaterialOverride(key: String): MaterialOverride =
    parseMaterialOverride(getString(key, null)).let { value ->
        if (key == KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL || key == KEY_CONTROL_CENTER_BACKGROUND_MATERIAL) {
            value.copy(
                blurPercent = value.blurPercent.coerceIn(0, 100),
                alpha = if (value.enabled) value.alpha.coerceIn(-100, 0) else value.alpha,
            )
        } else {
            value
        }
    }

private fun parseMaterialOverride(encoded: String?): MaterialOverride {
    val parts = encoded?.split('|') ?: return MaterialOverride()
    fun int(index: Int, range: IntRange, fallback: Int) =
        parts.getOrNull(index)?.toIntOrNull()?.coerceIn(range) ?: fallback
    return MaterialOverride(
        enabled = parts.getOrNull(0)?.toBooleanStrictOrNull() ?: false,
        glassRadius = int(1, 0..40, 0),
        blurPercent = int(2, 0..200, 100),
        scalePercent = int(3, 0..200, 100),
        brightness = int(4, -30..30, 0),
        darker = int(5, -50..50, 0),
        refraction = int(6, -100..100, 0),
        burn = int(7, -50..50, 0),
        saturation = int(8, -100..100, 0),
        alpha = int(9, -100..0, 0),
        edgeThickness = int(10, -100..100, 0),
        reflection = int(11, -100..100, 0),
        directionalLight = int(12, -100..100, 0),
        backgroundSaturation = int(13, -100..100, 0),
        backgroundBrightness = int(14, -100..100, 0),
        tintEnabled = parts.getOrNull(15)?.toBooleanStrictOrNull() ?: false,
        tintColor = parts.getOrNull(16)?.toLongOrNull()?.toInt() ?: 0xFFFFFFFF.toInt(),
        tintStrength = int(17, 0..50, 0),
    )
}

private fun MaterialOverride.serialize(): String = listOf(
    enabled, glassRadius, blurPercent, scalePercent, brightness, darker, refraction, burn,
    saturation, alpha, edgeThickness, reflection, directionalLight, backgroundSaturation,
    backgroundBrightness, tintEnabled, tintColor.toLong(), tintStrength,
).joinToString("|")

fun HookSettings.exportShadePreset(name: String? = null): String = JSONObject().apply {
    put("format", "hyperchanger-shade-preset")
    put("version", 2)
    name?.trim()?.takeIf { it.isNotBlank() }?.let { put("name", it.take(MAX_PRESET_NAME_LENGTH)) }
    put("notificationElements", notificationElementsMaterial.serialize())
    put("controlCenterElements", controlCenterElementsMaterial.serialize())
    put("notificationCenterBackground", notificationCenterBackgroundMaterial.serialize())
    put("controlCenterBackground", controlCenterBackgroundMaterial.serialize())
}.toString(2)

fun HookSettings.importShadePreset(payload: String): HookSettings {
    val imported = parseShadePreset(payload).settings
    return copy(
        notificationElementsMaterial = imported.notificationElementsMaterial,
        controlCenterElementsMaterial = if (shadeSettingsUnified) {
            imported.notificationElementsMaterial
        } else {
            imported.controlCenterElementsMaterial
        },
        notificationCenterBackgroundMaterial = imported.notificationCenterBackgroundMaterial,
        controlCenterBackgroundMaterial = if (shadeSettingsUnified) {
            imported.notificationCenterBackgroundMaterial
        } else {
            imported.controlCenterBackgroundMaterial
        },
    )
}

fun parseShadePreset(payload: String): ShadePresetImport {
    val preset = JSONObject(payload)
    require(preset.optString("format") == "hyperchanger-shade-preset") { "不支持的预设文件" }
    require(preset.optInt("version", 0) == 2) { "不支持的预设版本" }
    fun value(key: String): String? = if (preset.has(key)) preset.getString(key) else null
    return ShadePresetImport(
        name = preset.optString("name").trim().take(MAX_PRESET_NAME_LENGTH).ifBlank { null },
        settings = HookSettings().copy(
        notificationElementsMaterial = parseMaterialOverride(value("notificationElements")),
        controlCenterElementsMaterial = parseMaterialOverride(value("controlCenterElements")),
        notificationCenterBackgroundMaterial = parseMaterialOverride(value("notificationCenterBackground")),
        controlCenterBackgroundMaterial = parseMaterialOverride(value("controlCenterBackground")),
        ),
    )
}

data class ShadePresetImport(val name: String?, val settings: HookSettings)

fun ShadePreset.applyTo(settings: HookSettings): HookSettings = settings.importShadePreset(payload)

private fun SharedPreferences.getGlassTuning(key: String): GlassTuning {
    val parts = getString(key, null)?.split('|') ?: return GlassTuning()
    return GlassTuning(
        blurPercent = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 200) ?: 100,
        opacity = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
        color = parts.getOrNull(2)?.toLongOrNull()?.toInt() ?: 0xFFFFFFFF.toInt(),
        customColorEnabled = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
    )
}

internal fun HookSettings.rasterWallpaperUriList(): List<String> = runCatching {
    val values = org.json.JSONArray(rasterWallpaperUris)
    List(values.length()) { index -> values.getString(index) }.filter { it.isNotBlank() }
}.getOrDefault(emptyList())

internal fun encodeRasterWallpaperUris(uris: List<String>): String = org.json.JSONArray(uris).toString()

private fun GlassTuning.serialize(): String =
    "$blurPercent|$opacity|${color.toLong()}|$customColorEnabled"

private fun SharedPreferences.write(value: HookSettings) {
    edit()
        .removeLegacyIslandPreferences()
        .removeLegacyShadePreferences()
        .putBoolean(KEY_INITIALIZED, true)
        .putString(KEY_NOTIFICATION_ELEMENTS_MATERIAL, value.notificationElementsMaterial.serialize())
        .putString(KEY_CONTROL_CENTER_ELEMENTS_MATERIAL, value.controlCenterElementsMaterial.serialize())
        .putString(KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL, value.notificationCenterBackgroundMaterial.serialize())
        .putString(KEY_CONTROL_CENTER_BACKGROUND_MATERIAL, value.controlCenterBackgroundMaterial.serialize())
        .putBoolean(KEY_SHADE_SETTINGS_UNIFIED, value.shadeSettingsUnified)
        .putBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, value.unifyNotificationMaterial)
        .putBoolean(
            KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT,
            value.removeFocusAndIslandWhitelistLimit,
        )
        .putBoolean(KEY_ISLAND_ENABLED, value.islandEnabled)
        .putInt(KEY_ISLAND_WIDTH, value.islandWidth)
        .putBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, value.expandedIslandBackgroundEnabled)
        .putInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, value.expandedIslandBackgroundOpacity)
        .putInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, value.expandedIslandGlassBlurRadius)
        .putInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, value.expandedIslandGlassLargeBlurRadius)
        .putInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, value.expandedIslandSelfBlurRadius)
        .putBoolean(KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT, value.expandedIslandShowHighlight)
        .putBoolean(
            KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE,
            value.superXiaoAiGlobalSearchAppearance,
        )
        .putBoolean(KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED, value.superXiaoAiBlacklistUnblocked)
        .putBoolean(KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED, value.superXiaoAiClipboardUnblocked)
        .putBoolean(KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED, value.superXiaoAiAiSafetyUnblocked)
        .putBoolean(KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED, value.superXiaoAiVoiceSafetyUnblocked)
        .putBoolean(KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED, value.superXiaoAiKeyboardStyleEnabled)
        .putInt(KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE, value.superXiaoAiKeyboardColorMode)
        .putInt(KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS, value.superXiaoAiKeyboardCornerRadius)
        .putInt(KEY_SUPER_XIAOAI_KEYBOARD_OPACITY, value.superXiaoAiKeyboardOpacity)
        .putInt(KEY_SUPER_XIAOAI_KEYBOARD_BLUR, value.superXiaoAiKeyboardBlur)
        .putBoolean(KEY_CLOCK_ENABLED, value.clockEnabled)
        .putFloat(KEY_CLOCK_SIZE, value.clockSize)
        .putBoolean(KEY_STATUS_DIMENSION_DELTAS_V1, true)
        .putBoolean(KEY_PADDING_END_ENABLED, value.paddingEndEnabled)
        .putFloat(KEY_PADDING_END, value.paddingEnd)
        .apply {
            if (value.paddingEndLegacyAbsolute != null) putFloat(KEY_PADDING_END_LEGACY_ABSOLUTE, value.paddingEndLegacyAbsolute)
            else remove(KEY_PADDING_END_LEGACY_ABSOLUTE)
        }
        .putBoolean(KEY_PADDING_START_ENABLED, value.paddingStartEnabled)
        .putFloat(KEY_PADDING_START, value.paddingStart)
        .putBoolean(KEY_HEIGHT_ENABLED, value.heightEnabled)
        .putInt(KEY_STATUS_BAR_HEIGHT, value.statusBarHeight)
        .putBoolean(KEY_PADDING_TOP_ENABLED, value.paddingTopEnabled)
        .putFloat(KEY_PADDING_TOP, value.paddingTop)
        .apply {
            if (value.paddingTopLegacyAbsolute != null) putFloat(KEY_PADDING_TOP_LEGACY_ABSOLUTE, value.paddingTopLegacyAbsolute)
            else remove(KEY_PADDING_TOP_LEGACY_ABSOLUTE)
        }
        .putBoolean(KEY_TOP_BUTTONS_RADIUS_ENABLED, value.topButtonsRadiusEnabled)
        .putFloat(KEY_TOP_BUTTONS_RADIUS, value.topButtonsRadius)
        .putBoolean(KEY_MEDIA_CARD_RADIUS_ENABLED, value.mediaCardRadiusEnabled)
        .putFloat(KEY_MEDIA_CARD_RADIUS, value.mediaCardRadius)
        .putBoolean(KEY_SLIDER_RADIUS_ENABLED, value.sliderRadiusEnabled)
        .putFloat(KEY_SLIDER_RADIUS, value.sliderRadius)
        .putBoolean(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS_ENABLED, value.controlBottomButtonsRadiusEnabled)
        .putFloat(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS, value.controlBottomButtonsRadius)
        .putInt(KEY_VOLUME_PANEL_BLUR_RADIUS, value.volumePanelBlurRadius)
        .putInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, value.volumePanelGlassStrength)
        .putFloat(KEY_VOLUME_PANEL_CORNER_RADIUS, value.volumePanelCornerRadius)
        .putInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, value.volumePanelBackgroundOpacity)
        .putBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, value.volumePanelMaterialEnabled)
        .remove(KEY_VOLUME_PANEL_BACKGROUND_TRANSPARENCY_LEGACY)
        .putBoolean(KEY_DEVICE_CENTER_RADIUS_ENABLED, value.deviceCenterRadiusEnabled)
        .putFloat(KEY_DEVICE_CENTER_RADIUS, value.deviceCenterRadius)
        .putBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, value.removeDepthImageLimit)
        .putBoolean(KEY_RASTER_WALLPAPER_ENABLED, value.rasterWallpaperEnabled)
        .putString(KEY_RASTER_WALLPAPER_URIS, value.rasterWallpaperUris)
        .putInt(KEY_RASTER_WALLPAPER_SENSITIVITY_PRESET, value.rasterWallpaperSensitivityPreset)
        .putFloat(KEY_RASTER_WALLPAPER_CUSTOM_SENSITIVITY, value.rasterWallpaperCustomSensitivity)
        .putBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, value.notificationFodPositionLimitRemoved)
        .putInt(KEY_FINGERPRINT_HIDE_MODE, value.fingerprintHideMode)
        .putBoolean(
            KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE,
            value.lockscreenClockColonForceVisible,
        )
        .putInt(
            KEY_LOCKSCREEN_TEMPLATE_LIMIT_MODE,
            value.lockscreenTemplateLimitMode.coerceIn(
                LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT,
                LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM,
            ),
        )
        .putInt(KEY_LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM, value.lockscreenTemplateLimitCustom.coerceIn(20, 200))
        .putInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, value.lockscreenBottomTextMask.coerceIn(0, 7))
        .putBoolean(KEY_HIDE_LOCKSCREEN_CHARGING_TEXT, value.lockscreenBottomTextMask and 1 != 0)
        .putBoolean(
            KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED,
            value.lockscreenPinCircleBackgroundEnabled,
        )
        .putFloat(
            KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING,
            value.lockscreenPinCircleRowSpacing.coerceIn(-24f, 32f),
        )
        .remove("lockscreen_pin_background_blur_mode")
        .putInt(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE, value.lockscreenShortcutBackgroundMode)
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED, value.lockscreenShortcutBackgroundMode != 0)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, value.lockscreenShortcutGlassRadius)
        .putBoolean(
            KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED,
            value.lockscreenShortcutBackgroundRadiusEnabled,
        )
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS, value.lockscreenShortcutBackgroundRadius)
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, value.lockscreenShortcutSpacingEnabled)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, value.lockscreenShortcutSpacing)
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, value.lockscreenShortcutIconSizeEnabled)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, value.lockscreenShortcutIconSize)
        .putInt(KEY_SHORTCUT_ICON_COLOR_MODE, value.shortcutIconColorMode)
        .putInt(KEY_SHORTCUT_PURE_COLOR, value.shortcutPureColor)
        .putInt(KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR, value.shortcutAdvancedMaterialColor)
        .putInt(KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY, value.shortcutAdvancedMaterialOpacity)
        .putInt(KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS, value.shortcutAdvancedMaterialBlurRadius)
        .putBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, value.shortcutAdvancedMaterialHighlight)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, value.shortcutSoftGlassColor)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, value.shortcutSoftGlassOpacity)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS, value.shortcutSoftGlassBackdropBlurRadius)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS, value.shortcutSoftGlassBlurRadius)
        .putFloat(KEY_SHORTCUT_SOFT_GLASS_LUMINANCE, value.shortcutSoftGlassLuminance)
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, value.lockscreenMiniPlayerEnabled)
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, value.lockscreenMiniPlayerLyricsEnabled)
        .putInt(
            KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
            value.lockscreenMiniPlayerMediaNotificationMode.coerceIn(
                LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
                LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC,
            ),
        )
        .remove(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION)
        .putInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, value.lockscreenMiniPlayerBackgroundMode)
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, value.lockscreenMiniPlayerWidth)
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, value.lockscreenMiniPlayerHeight)
        .putFloat(
            KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS,
            value.lockscreenMiniPlayerArtworkCornerRadius.coerceIn(0f, 60f),
        )
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT_RADIUS_MIGRATED, true)
        .putBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, value.lockscreenWidgetEnabled)
        .putString(KEY_LOCKSCREEN_WIDGET_DEVICE_NAME, value.lockscreenWidgetDeviceName.take(64))
        .putInt(
            KEY_LOCKSCREEN_WIDGET_ITEMS,
            (value.lockscreenWidgetItems and LOCKSCREEN_WIDGET_ALL_ITEMS)
                .takeIf { it != 0 } ?: LOCKSCREEN_WIDGET_DEFAULT_ITEMS,
        )
        .putString(KEY_LOCKSCREEN_WIDGET_ORDER, value.lockscreenWidgetOrder.take(47))
        .putLong(KEY_LOCKSCREEN_WIDGET_PREVIEW_VERSION, value.lockscreenWidgetPreviewVersion)
        .putInt(
            KEY_LOCKSCREEN_WIDGET_COMBINATION,
            value.lockscreenWidgetCombination.coerceIn(
                LOCKSCREEN_WIDGET_COMBINATION_ONE,
                LOCKSCREEN_WIDGET_COMBINATION_TWO,
            ),
        )
        .putInt(
            KEY_LOCKSCREEN_WIDGET_BATTERY_MATERIAL_MODE,
            value.lockscreenWidgetBatteryMaterialMode.coerceIn(
                LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
                LOCKSCREEN_WIDGET_BATTERY_MATERIAL_SOFT,
            ),
        )
        .putInt(
            KEY_LOCKSCREEN_WIDGET_COLOR_MODE,
            value.lockscreenWidgetColorMode.coerceIn(
                LOCKSCREEN_WIDGET_COLOR_LIGHT,
                LOCKSCREEN_WIDGET_COLOR_AUTO,
            ),
        )
        .putBoolean(
            KEY_LOCKSCREEN_WIDGET_NOTIFICATION_AVOID,
            value.lockscreenWidgetNotificationAvoid,
        )
        .putInt(
            KEY_LOCKSCREEN_WIDGET_SIGNATURE_TYPE,
            value.lockscreenWidgetSignatureType.coerceIn(
                LOCKSCREEN_WIDGET_SIGNATURE_NONE,
                LOCKSCREEN_WIDGET_SIGNATURE_VECTOR,
            ),
        )
        .putLong(KEY_LOCKSCREEN_WIDGET_SIGNATURE_VERSION, value.lockscreenWidgetSignatureVersion)
        .putInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_COLOR, value.lockscreenWidgetSignatureColor)
        .putInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_SCALE, value.lockscreenWidgetSignatureScale.coerceIn(25, 200))
        .putBoolean(KEY_LOCKSCREEN_WIDGET_SIGNATURE_BACKGROUND, value.lockscreenWidgetSignatureBackground)
        .putInt(KEY_MINI_PLAYER_PURE_COLOR, value.miniPlayerPureColor)
        .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR, value.miniPlayerAdvancedMaterialColor)
        .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY, value.miniPlayerAdvancedMaterialOpacity)
        .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS, value.miniPlayerAdvancedMaterialBlurRadius)
        .putBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, value.miniPlayerAdvancedMaterialHighlight)
        .putInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, value.miniPlayerSoftGlassColor)
        .putInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, value.miniPlayerSoftGlassOpacity)
        .putInt(
            KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
            value.miniPlayerSoftGlassBackdropBlurRadius,
        )
        .putInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, value.miniPlayerSoftGlassBlurRadius)
        .putFloat(KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE, value.miniPlayerSoftGlassLuminance)
        .putBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, value.keepSoftGlassAfterGlobalTheme)
        .putBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, value.removeClockMaterialLimit)
        .putBoolean(KEY_HIDE_STATUS_BAR_NETWORK_TYPE, value.hideStatusBarNetworkType)
        .putBoolean(KEY_HIDE_STATUS_BAR_WIFI_STANDARD, value.hideStatusBarWifiStandard)
        .putBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, value.hideStatusBarClockText)
        .putBoolean(KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY, value.hideStatusBarNetworkActivity)
        .putBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, value.stackedMobileSignalEnabled)
        .putFloat(KEY_STACKED_MOBILE_SIGNAL_SCALE, value.stackedMobileSignalScale.coerceIn(0.1f, 3f))
        .putFloat(
            KEY_STACKED_MOBILE_SIGNAL_VERTICAL_OFFSET,
            value.stackedMobileSignalVerticalOffset.coerceIn(-8f, 8f),
        )
        .putFloat(
            KEY_STACKED_MOBILE_SIGNAL_LEFT_MARGIN,
            value.stackedMobileSignalLeftMargin.coerceIn(-8f, 8f),
        )
        .putFloat(
            KEY_STACKED_MOBILE_SIGNAL_RIGHT_MARGIN,
            value.stackedMobileSignalRightMargin.coerceIn(-8f, 8f),
        )
        .putInt(KEY_MOBILE_SIGNAL_HIDE_MODE, value.mobileSignalHideMode.coerceIn(0, 2))
        .putInt(KEY_MOBILE_NETWORK_TYPE_MODE, value.mobileNetworkTypeMode.coerceIn(0, 2))
        .putInt(KEY_MOBILE_NETWORK_TYPE_POSITION, value.mobileNetworkTypePosition.coerceIn(0, 1))
        .putInt(KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC, value.mobileNetworkTypeDisplayLogic.coerceIn(0, 1))
        .putString(KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT, value.mobileNetworkTypeCustomText.take(128))
        .putBoolean(KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A, value.mobileNetworkTypeShrink5gaA)
        .putFloat(KEY_MOBILE_NETWORK_TYPE_SCALE, value.mobileNetworkTypeScale.coerceIn(0.1f, 3f))
        .putFloat(
            KEY_MOBILE_NETWORK_TYPE_VERTICAL_OFFSET,
            value.mobileNetworkTypeVerticalOffset.coerceIn(-8f, 8f),
        )
        .putFloat(
            KEY_MOBILE_NETWORK_TYPE_LEFT_MARGIN,
            value.mobileNetworkTypeLeftMargin.coerceIn(-8f, 8f),
        )
        .putFloat(
            KEY_MOBILE_NETWORK_TYPE_RIGHT_MARGIN,
            value.mobileNetworkTypeRightMargin.coerceIn(-8f, 8f),
        )
        .putBoolean(KEY_STATUS_BAR_SCALE_ADJUSTMENT_MIGRATED, true)
        .putBoolean(KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED, true)
        .putBoolean(KEY_STATUS_BAR_SCALE_ABSOLUTE_MIGRATED_V2, true)
        .putBoolean(KEY_SYSTEM_UPDATE_DISABLED, value.systemUpdateDisabled)
        .putBoolean(KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED, value.systemUpdateOtaLimitRemoved)
        .putBoolean(KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED, value.systemUpdateVersionSpoofEnabled)
        .putString(KEY_SYSTEM_UPDATE_VERSION, value.systemUpdateVersion.take(128))
        .putString(KEY_SYSTEM_UPDATE_SOTA_VERSION, value.systemUpdateSotaVersion.take(128))
        .putString(KEY_THEME_MODE, value.themeMode)
        .putString(KEY_NAVIGATION_STYLE, value.navigationStyle)
        .putString(KEY_NAVIGATION_LABEL_MODE, value.navigationLabelMode)
        .putBoolean(KEY_PREDICTIVE_BACK_ENABLED, value.predictiveBackEnabled)
        .putInt(KEY_PREDICTIVE_BACK_PROGRESS, value.predictiveBackProgress)
        .apply()
}
