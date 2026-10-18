// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_ALLOW_ALL_NOTIFICATIONS_ON_LOCKSCREEN = "allow_all_notifications_on_lockscreen"
internal const val KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW = "disable_media_island_bottom_glow"
internal const val KEY_DISABLE_NOTIFICATION_HISTORY_FOLDING = "disable_notification_history_folding"
internal const val KEY_EXPANDED_ISLAND_BACKGROUND_BLUR_RADIUS = "expanded_island_background_blur_radius"
internal const val KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED = "expanded_island_background_enabled"
internal const val KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY = "expanded_island_background_opacity"
internal const val KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS = "expanded_island_glass_blur_radius"
internal const val KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS = "expanded_island_glass_large_blur_radius"
internal const val KEY_EXPANDED_ISLAND_GLASS_REFLECTION = "expanded_island_glass_reflection"
internal const val KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS = "expanded_island_self_blur_radius"
internal const val KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT = "expanded_island_show_highlight"
internal const val KEY_FORCE_ALL_NOTIFICATIONS_HEADS_UP = "force_all_notifications_heads_up"
internal const val KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS = "heads_up_notification_soft_glass"
internal const val KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON = "hide_media_island_source_icon"
internal const val KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR = "hide_notification_mini_window_bar"
internal const val KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON = "hide_system_media_source_icon"
internal const val KEY_KEEP_NOTIFICATIONS = "keep_notifications"
internal const val KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET = "media_cover_corner_radius_offset"
internal const val KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET = "media_island_info_vertical_offset"
internal const val KEY_MEDIA_TITLE_ARTIST_SPACING = "media_title_artist_spacing"
internal const val KEY_NOTIFICATION_CORNER_RADIUS_OFFSET = "notification_corner_radius_offset"
internal const val KEY_REMOVE_BLE_UNLOCK_TOAST = "remove_ble_unlock_toast"
internal const val KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT =
    "remove_dynamic_island_media_mini_bar_whitelist_limit"
internal const val KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT =
    "remove_focus_and_island_whitelist_limit"
internal const val KEY_REMOVE_NOTIFICATION_IMPORTANCE_LIMIT = "remove_notification_importance_limit"
internal const val KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET = "system_media_info_vertical_offset"
internal const val KEY_UNIFY_NOTIFICATION_MATERIAL = "unify_notification_material"

internal const val KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET_ENABLED = "media_island_info_vertical_offset_enabled"
internal const val KEY_NOTIFICATION_CORNER_RADIUS_OFFSET_ENABLED = "notification_corner_radius_offset_enabled"

data class NotificationIslandSettings(
    val unifyNotificationMaterial: Boolean = false,
    val headsUpNotificationSoftGlass: Boolean = false,
    val removeFocusAndIslandWhitelistLimit: Boolean = false,
    val removeDynamicIslandMediaMiniBarWhitelistLimit: Boolean = false,
    val hideNotificationMiniWindowBar: Boolean = false,
    val expandedIslandBackgroundEnabled: Boolean = false,
    val expandedIslandBackgroundOpacity: Int = 97,
    val expandedIslandBackgroundBlurRadius: Int = 40,
    val expandedIslandGlassReflection: Int = 0,
    val expandedIslandGlassBlurRadius: Int = 110,
    val expandedIslandGlassLargeBlurRadius: Int = 110,
    val expandedIslandSelfBlurRadius: Int = 0,
    val expandedIslandShowHighlight: Boolean = false,
    val disableMediaIslandBottomGlow: Boolean = false,
    val hideSystemMediaSourceIcon: Boolean = false,
    val hideMediaIslandSourceIcon: Boolean = false,
    val systemMediaInfoVerticalOffset: Int = 0,
    val mediaIslandInfoVerticalOffset: Int = 0,
    val mediaTitleArtistSpacing: Int = 0,
    val mediaCoverCornerRadiusOffset: Int = 0,
    val notificationCornerRadiusOffset: Int = 0,
    val keepNotifications: Boolean = false,
    val allowAllNotificationsOnLockscreen: Boolean = false,
    val forceAllNotificationsHeadsUp: Boolean = false,
    val removeNotificationImportanceLimit: Boolean = false,
    val removeBleUnlockToast: Boolean = false,
    val disableNotificationHistoryFolding: Boolean = false,
    val mediaIslandInfoVerticalOffsetEnabled: Boolean = false,
    val notificationCornerRadiusOffsetEnabled: Boolean = false,
)

internal fun SharedPreferences.toNotificationIslandSettings() = NotificationIslandSettings(
    unifyNotificationMaterial = getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false),
    headsUpNotificationSoftGlass = getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false),
    removeFocusAndIslandWhitelistLimit =
        getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false),
    removeDynamicIslandMediaMiniBarWhitelistLimit = getBoolean(
        KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT,
        false,
    ),
    hideNotificationMiniWindowBar = getBoolean(KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR, false),
    expandedIslandBackgroundEnabled = getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false),
    expandedIslandBackgroundOpacity = getInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, 97).coerceIn(0, 100),
    expandedIslandBackgroundBlurRadius = getInt(
        KEY_EXPANDED_ISLAND_BACKGROUND_BLUR_RADIUS,
        maxOf(
            getInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, 40),
            getInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, 40),
            getInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, 0),
        ),
    ).coerceIn(0, 120),
    expandedIslandGlassReflection = getInt(KEY_EXPANDED_ISLAND_GLASS_REFLECTION, 0).coerceIn(0, 100),
    expandedIslandGlassBlurRadius = getInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, 40).coerceIn(0, 120),
    expandedIslandGlassLargeBlurRadius = getInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, 40).coerceIn(0, 120),
    expandedIslandSelfBlurRadius = getInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, 0).coerceIn(0, 120),
    expandedIslandShowHighlight = getBoolean(KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT, false),
    disableMediaIslandBottomGlow = getBoolean(KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW, false),
    hideSystemMediaSourceIcon = getBoolean(KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON, false),
    hideMediaIslandSourceIcon = getBoolean(KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON, false),
    systemMediaInfoVerticalOffset = getInt(KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET, 0).coerceIn(-50, 50),
    mediaIslandInfoVerticalOffset = getInt(KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET, 0).coerceIn(-50, 50),
    mediaTitleArtistSpacing = getInt(KEY_MEDIA_TITLE_ARTIST_SPACING, 0).coerceIn(-30, 30),
    mediaCoverCornerRadiusOffset = getInt(KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET, 0).coerceIn(-30, 30),
    notificationCornerRadiusOffset = getInt(KEY_NOTIFICATION_CORNER_RADIUS_OFFSET, 0).coerceIn(-30, 30),
    keepNotifications = getBoolean(KEY_KEEP_NOTIFICATIONS, false),
    allowAllNotificationsOnLockscreen = getBoolean(KEY_ALLOW_ALL_NOTIFICATIONS_ON_LOCKSCREEN, false),
    forceAllNotificationsHeadsUp = getBoolean(KEY_FORCE_ALL_NOTIFICATIONS_HEADS_UP, false),
    removeNotificationImportanceLimit = getBoolean(KEY_REMOVE_NOTIFICATION_IMPORTANCE_LIMIT, false),
    removeBleUnlockToast = getBoolean(KEY_REMOVE_BLE_UNLOCK_TOAST, false),
    disableNotificationHistoryFolding = getBoolean(KEY_DISABLE_NOTIFICATION_HISTORY_FOLDING, false),
    mediaIslandInfoVerticalOffsetEnabled = getBoolean(
        KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET_ENABLED, getInt(KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET, 0) != 0,
    ),
    notificationCornerRadiusOffsetEnabled = getBoolean(
        KEY_NOTIFICATION_CORNER_RADIUS_OFFSET_ENABLED, getInt(KEY_NOTIFICATION_CORNER_RADIUS_OFFSET, 0) != 0,
    ),
)

/** Retains the existing preference keys so upgrades and module presets keep their values. */
class NotificationIslandSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = local.toNotificationIslandSettings()
        private set

    fun reload() {
        settings = local.toNotificationIslandSettings()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = (if (remote.contains(KEY_UNIFY_NOTIFICATION_MATERIAL)) remote else local)
            .toNotificationIslandSettings()
        write(remote)
        write(local)
    }

    fun update(service: XposedService?, transform: (NotificationIslandSettings) -> NotificationIslandSettings) {
        settings = transform(settings)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
        .putBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, settings.unifyNotificationMaterial)
        .putBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, settings.headsUpNotificationSoftGlass)
        .putBoolean(
            KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT,
            settings.removeFocusAndIslandWhitelistLimit,
        )
        .putBoolean(
            KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT,
            settings.removeDynamicIslandMediaMiniBarWhitelistLimit,
        )
        .putBoolean(KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR, settings.hideNotificationMiniWindowBar)
        .putBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, settings.expandedIslandBackgroundEnabled)
        .putInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, settings.expandedIslandBackgroundOpacity)
        .putInt(KEY_EXPANDED_ISLAND_BACKGROUND_BLUR_RADIUS, settings.expandedIslandBackgroundBlurRadius)
        .putInt(KEY_EXPANDED_ISLAND_GLASS_REFLECTION, settings.expandedIslandGlassReflection)
        .putInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, settings.expandedIslandGlassBlurRadius)
        .putInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, settings.expandedIslandGlassLargeBlurRadius)
        .putInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, settings.expandedIslandSelfBlurRadius)
        .putBoolean(KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT, settings.expandedIslandShowHighlight)
        .putBoolean(KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW, settings.disableMediaIslandBottomGlow)
        .putBoolean(KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON, settings.hideSystemMediaSourceIcon)
        .putBoolean(KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON, settings.hideMediaIslandSourceIcon)
        .putInt(KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET, settings.systemMediaInfoVerticalOffset.coerceIn(-50, 50))
        .putInt(KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET, settings.mediaIslandInfoVerticalOffset.coerceIn(-50, 50))
        .putInt(KEY_MEDIA_TITLE_ARTIST_SPACING, settings.mediaTitleArtistSpacing.coerceIn(-30, 30))
        .putInt(KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET, settings.mediaCoverCornerRadiusOffset.coerceIn(-30, 30))
        .putInt(KEY_NOTIFICATION_CORNER_RADIUS_OFFSET, settings.notificationCornerRadiusOffset.coerceIn(-30, 30))
        .putBoolean(KEY_KEEP_NOTIFICATIONS, settings.keepNotifications)
        .putBoolean(KEY_ALLOW_ALL_NOTIFICATIONS_ON_LOCKSCREEN, settings.allowAllNotificationsOnLockscreen)
        .putBoolean(KEY_FORCE_ALL_NOTIFICATIONS_HEADS_UP, settings.forceAllNotificationsHeadsUp)
        .putBoolean(KEY_REMOVE_NOTIFICATION_IMPORTANCE_LIMIT, settings.removeNotificationImportanceLimit)
        .putBoolean(KEY_REMOVE_BLE_UNLOCK_TOAST, settings.removeBleUnlockToast)
        .putBoolean(KEY_DISABLE_NOTIFICATION_HISTORY_FOLDING, settings.disableNotificationHistoryFolding)
        .putBoolean(KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET_ENABLED, settings.mediaIslandInfoVerticalOffsetEnabled)
        .putBoolean(KEY_NOTIFICATION_CORNER_RADIUS_OFFSET_ENABLED, settings.notificationCornerRadiusOffsetEnabled)
        .apply()
    }
}
