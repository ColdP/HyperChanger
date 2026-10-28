// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import btm.m.os4.systemuihook.newnotificationcenter.*

/** Include getter defaults so a page writing its defaults does not count as an edit. */
internal fun restartRecommendationSnapshot(group: String, preferences: SharedPreferences): Map<String, *> {
    if (group != REMOTE_PREFERENCE_GROUP) return preferences.all.toMap()
    val values = preferences.all.toMutableMap()
    val p = object : SharedPreferences by preferences {
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            preferences.getBoolean(key, defValue).also { values[key] = it }
        override fun getInt(key: String, defValue: Int): Int =
            preferences.getInt(key, defValue).also { values[key] = it }
        override fun getFloat(key: String, defValue: Float): Float =
            preferences.getFloat(key, defValue).also { values[key] = it }
        override fun getLong(key: String, defValue: Long): Long =
            preferences.getLong(key, defValue).also { values[key] = it }
        override fun getString(key: String, defValue: String?): String? =
            preferences.getString(key, defValue).also { values[key] = it }
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            preferences.getStringSet(key, defValues).also { values[key] = it?.toSet() }
    }
    readControlCenterCarrierSettings(p)
    readControlCenterClockSettings(p)
    readControlCenterHeaderHeightOffset(p)
    ContactsDialerSettings.read(p)
    readDockSettings(p)
    readHomeDockIconSettings(p)
    readHomeFolderSettings(p)
    readHomeMaterialSettings(p)
    readHomeGlassSurface(p, false)
    readHomeGlassSurface(p, true)
    readHomeMultiSplitSettings(p)
    readHomeRecentsSettings(p)
    p.toDynamicIslandAppearanceSettings()
    readIslandSmoothingSettings(p)
    readIslandSpacingSettings(p)
    readIslandWidthSettings(p)
    readLiftToWakeSchedulePreferences(p)
    readLockscreenFaceUnlockSettings(p)
    p.toNotificationIslandSettings()
    readNotificationStackBlurSettings(p)
    readSettingsAppPreferences(p)
    readSettingsAppScopePreferences(p)
    readMiSettingsPreferences(p)
    readSoundEffectPreferences(p)
    readSuperXiaoAiPreferences(p)
    readSuperXiaoAiInputGlassSettings(p)
    readSystemUpdatePreferences(p)
    readSoftGlassSettings(p)
    readLockscreenShortcutBackgroundSettings(p)
    readLockscreenCapsuleGlassSettings(p)
    readExpandedIslandGlassSettings(p)
    volumePanelUsesGlobalGlass(p)
    readNotificationTextColorSettings(p)
    p.readRearScreenWallpaperLimitSettings()
    p.isRearScreenThemeApplyFixEnabled()
    p.readRearScreenGestureSettings()
    p.isRearMiniPlayerEnabled()
    p.isRearMiniPlayerAllowAllActivities()
    p.getBoolean(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED, false)
    p.getInt(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET, 0)
    readIosNotificationBottomOffset(p)
    p.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)
    p.getBoolean(KEY_IOS_NOTIFICATION_CENTER, false)
    p.getInt(KEY_IOS_NOTIFICATION_WALLPAPER, 0)
    p.getBoolean(KEY_IOS_NOTIFICATION_HIDE_CLEAR, false)
    val header = IosNotificationHeaderSettings()
    p.getBoolean(KEY_IOS_NOTIFICATION_HEADER, header.enabled)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_STYLE, header.clearButtonStyle)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_MATERIAL, header.clearButtonMaterial)
    p.getFloat(KEY_IOS_NOTIFICATION_CLEAR_RADIUS, header.clearButtonCornerRadius)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_PURE_COLOR, header.clearButtonPureColor)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_COLOR, header.clearButtonAdvancedColor)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_OPACITY, header.clearButtonAdvancedOpacity)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_BLUR, header.clearButtonAdvancedBlurRadius)
    p.getBoolean(KEY_IOS_NOTIFICATION_CLEAR_ADVANCED_HIGHLIGHT, header.clearButtonAdvancedHighlight)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_COLOR, header.clearButtonSoftGlassColor)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_OPACITY, header.clearButtonSoftGlassOpacity)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BACKDROP_BLUR, header.clearButtonSoftGlassBackdropBlurRadius)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_BLUR, header.clearButtonSoftGlassBlurRadius)
    p.getFloat(KEY_IOS_NOTIFICATION_CLEAR_SOFT_LUMINANCE, header.clearButtonSoftGlassLuminance)
    p.getInt(KEY_IOS_NOTIFICATION_CLEAR_SOFT_REFRACTION, header.clearButtonSoftGlassRefraction)
    return values
}
