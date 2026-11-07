// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences

/** Observe the storage boundary so settings saved by nested pages are covered too. */
internal class RestartRecommendationTracker(
    preferences: List<Pair<String, SharedPreferences>>,
    private val snapshotValues: (String, SharedPreferences) -> Map<String, *> = { _, prefs -> prefs.all.toMap() },
    private val onChanged: (Set<ScopeApplication>) -> Unit,
) : AutoCloseable {
    private var ignoringChanges = false
    private val sources = preferences.map { (group, prefs) -> Source(group, prefs) }

    private fun snapshot(group: String, prefs: SharedPreferences): Map<String, *> =
        runCatching { snapshotValues(group, prefs) }.getOrElse { prefs.all.toMap() }

    private inner class Source(val group: String, val prefs: SharedPreferences) {
        var snapshot = snapshot(group, prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // A remote load can write hundreds of keys. withoutTracking takes one
            // final baseline, so rebuilding every settings snapshot per key is wasted work.
            if (ignoringChanges) return@OnSharedPreferenceChangeListener
            val current = snapshot(group, prefs)
            val changedKeys = (snapshot.keys + current.keys).filter { snapshot[it] != current[it] }
            snapshot = current
            if (!ignoringChanges) {
                val scopes = changedKeys.flatMap { restartScopesForPreference(group, it) }.toMutableSet()
                if (group == REMOTE_PREFERENCE_GROUP && changedKeys.any { it.startsWith("soft_glass_parameters_v3_") }
                    && homeGlassFollowsGlobal(prefs)) scopes += ScopeApplication.DESKTOP
                if (group == REMOTE_PREFERENCE_GROUP && changedKeys.any { it.startsWith("soft_glass_parameters_v3_") }
                    && readSidebarSettings(prefs).let { it.glass.enabled && it.useGlobal }) scopes += ScopeApplication.SECURITY_CENTER
                if (group == REMOTE_PREFERENCE_GROUP && changedKeys.any { it.startsWith("soft_glass_parameters_v3_") }) {
                    if (readWidgetGlassSettings(prefs).let { it.glass.enabled && it.useGlobal }) scopes += ScopeApplication.PERSONAL_ASSISTANT
                }
                if (scopes.isNotEmpty()) onChanged(scopes)
            }
        }
    }

    fun start() {
        sources.forEach { source ->
            source.snapshot = snapshot(source.group, source.prefs)
            source.prefs.registerOnSharedPreferenceChangeListener(source.listener)
        }
    }

    /** Loading remote values is a baseline refresh, not a user edit. */
    fun withoutTracking(block: () -> Unit) {
        ignoringChanges = true
        try {
            block()
        } finally {
            sources.forEach { it.snapshot = snapshot(it.group, it.prefs) }
            ignoringChanges = false
        }
    }

    override fun close() {
        sources.forEach { it.prefs.unregisterOnSharedPreferenceChangeListener(it.listener) }
    }
}

internal fun restartScopesForPreference(group: String, key: String): Set<ScopeApplication> {
    if (key == "initialized" || key.endsWith("_migrated") || key.contains("_migrated_v")) return emptySet()
    if (group == "settings") {
        return setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR)
    }
    if (group == DEVICE_PROFILE_PREFERENCES || group == SETTINGS_APPEARANCE_PREFERENCES) {
        return setOf(ScopeApplication.SETTINGS)
    }
    if (group != REMOTE_PREFERENCE_GROUP) return emptySet()

    // Module UI and saved preset libraries do not change any hooked application.
    if (key == KEY_SHARED_VIEW_ANIMATION || key == KEY_APP_MONET_PALETTE || key == KEY_APP_MONET_COLOR_SPEC ||
        key == KEY_APP_MONET_NOTICE_DISMISSED || key == KEY_APP_MONET_NOTICE_DISMISS_COUNT
    ) return emptySet()
    if (key in setOf(
            "navigation_style", "navigation_label_mode", "theme_mode", "predictive_back_enabled",
            "predictive_back_progress", "hide_app_icon", "user_shade_presets_v1", "soft_glass_custom_presets_v1",
        )) return emptySet()

    if (btm.m.liquidglass.NavigationOffsetSettings.isGlobalPreference(key)) {
        val packages = btm.m.liquidglass.ScopedSettings.apps.map { it.packageName }.toSet()
        return ScopeApplication.entries.filter { it.packageName in packages }.toSet()
    }
    if (key == btm.m.liquidglass.NavigationMaterialSettings.KEY_MODULE) return emptySet()
    if ((btm.m.liquidglass.NavigationMaterialSettings.isPreference(key) || btm.m.liquidglass.HyperDockedNavigationSettings.isPreference(key)) && !key.startsWith("scope.")) {
        return ScopeApplication.entries.filter { scope ->
            btm.m.liquidglass.ScopedSettings.apps.any { it.packageName == scope.packageName }
        }.toSet()
    }
    if (key.startsWith("scope.")) {
        return ScopeApplication.entries.filter { key.startsWith("scope.${it.packageName}.") }.toSet()
    }
    return when {
        key == HMC_NEXT_SETTINGS_KEY -> setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER)
        key.startsWith("custom_depth_wallpaper_") -> setOf(
            ScopeApplication.SYSTEM_UI,
            ScopeApplication.WALLPAPER,
            ScopeApplication.AOD,
            ScopeApplication.THEME_MANAGER,
        )
        key.startsWith("contacts_dialer_background_") -> setOf(ScopeApplication.CONTACTS_DIALER)
        key in setOf(KEY_REMOVE_ASSISTANT_WALLPAPER_BLUR, KEY_ASSISTANT_CUSTOM_WALLPAPER_BLUR,
            KEY_ASSISTANT_WALLPAPER_BLUR_RADIUS, KEY_ASSISTANT_BACKGROUND_MODE,
            KEY_ASSISTANT_BACKGROUND_IMAGE, KEY_ASSISTANT_BACKGROUND_NAME) -> setOf(ScopeApplication.PERSONAL_ASSISTANT)
        key == KEY_REMOVE_VIDEO_TEMP_LIMIT -> setOf(ScopeApplication.SECURITY_CENTER)
        PowerBatteryFeature.entries.any { it.key == key } -> setOf(ScopeApplication.SECURITY_CENTER)
        key == KEY_REMOVE_HOTSPOT_TEMP_LIMIT -> setOf(ScopeApplication.SETTINGS)
        key.startsWith(ASSISTANT_WIDGET_GLASS_PREFIX) -> setOf(ScopeApplication.PERSONAL_ASSISTANT)
        key.startsWith(SIDEBAR_PREFIX) -> setOf(ScopeApplication.SECURITY_CENTER)
        key.startsWith(SIDEBAR_COLUMN_SPACING_PREFIX) -> setOf(ScopeApplication.SECURITY_CENTER)
        key.startsWith(SIDEBAR_SHAPE_PREFIX) -> setOf(ScopeApplication.SECURITY_CENTER)
        key.startsWith(SIDEBAR_APPEARANCE_PREFIX) -> setOf(ScopeApplication.SECURITY_CENTER)
        key.startsWith(SIDEBAR_ICON_COUNT_PREFIX) -> setOf(ScopeApplication.SECURITY_CENTER)
        key.startsWith("portal_") -> setOf(ScopeApplication.PORTAL)
        key.startsWith("home_") || key.startsWith("dock_") -> setOf(ScopeApplication.DESKTOP)
        key.startsWith("settings_") || key in setOf(
            "unlock_never_screen_timeout", "show_google_service_entry", "show_saved_wifi_passwords",
            "fw_disable_device_name_check",
        ) -> setOf(ScopeApplication.SETTINGS)
        // Other framework flags target system_server, which is not an app restart target.
        key.startsWith("fw_") -> emptySet()
        key.startsWith("mi_settings_") -> setOf(ScopeApplication.MI_SETTINGS)
        key.startsWith("audio_") -> setOf(ScopeApplication.SOUND_EFFECT)
        key.startsWith("airdrop_") -> setOf(ScopeApplication.AIRDROP)
        key == KEY_GMS_UNLOCK_FEATURES -> setOf(ScopeApplication.GOOGLE_PLAY_SERVICES)
        key == KEY_GMS_QUICK_SHARE_ISLAND -> setOf(ScopeApplication.GOOGLE_PLAY_SERVICES)
        key.startsWith("system_update_") -> setOf(ScopeApplication.SYSTEM_UPDATE)
        key.startsWith("screenrecorder_") -> setOf(ScopeApplication.SCREEN_RECORDER, ScopeApplication.XIAOMI_HEALTH)
        key.contains("music_controls_") -> setOf(ScopeApplication.SUBSCREEN_CENTER)
        key.startsWith("super_xiaoai_") -> setOf(ScopeApplication.SUPER_XIAOAI_IME)
        key == KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED || key == KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE ->
            setOf(ScopeApplication.SUBSCREEN_CENTER)
        key == KEY_REAR_MINI_PLAYER_ENABLED -> setOf(ScopeApplication.NETEASE_MUSIC, ScopeApplication.SUBSCREEN_CENTER)
        // Framework scope is not an app restart target; the page requests a manual reboot.
        key == KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES -> emptySet()
        key == "rear_screen_theme_apply_fix" || key.startsWith("rear_wallpaper_limit_") ->
            setOf(ScopeApplication.THEME_MANAGER)
        key.startsWith("rear_") || key.contains("rear_screen") || key.contains("subscreen") ->
            setOf(ScopeApplication.SUBSCREEN_CENTER, ScopeApplication.THEME_MANAGER, ScopeApplication.PERSONAL_ASSISTANT)
        key.startsWith("raster_wallpaper_") -> setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER)
        key.startsWith("lockscreen_") || key.startsWith("mini_player_") || key.startsWith("lift_to_wake_") ||
            key == "hyperchanger_lockscreen_clock_horizontal_offset_v2" ->
            setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD)
        else -> setOf(ScopeApplication.SYSTEM_UI)
    }
}
