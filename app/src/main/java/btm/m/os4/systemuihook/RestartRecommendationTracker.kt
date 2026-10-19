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
            val current = snapshot(group, prefs)
            val changedKeys = (snapshot.keys + current.keys).filter { snapshot[it] != current[it] }
            snapshot = current
            if (!ignoringChanges) {
                val scopes = changedKeys.flatMap { restartScopesForPreference(group, it) }.toSet()
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
    if (key in setOf(
            "navigation_style", "navigation_label_mode", "theme_mode", "predictive_back_enabled",
            "predictive_back_progress", "hide_app_icon", "user_shade_presets_v1", "soft_glass_custom_presets_v1",
        )) return emptySet()

    if (key.startsWith("scope.")) {
        return ScopeApplication.entries.filter { key.startsWith("scope.${it.packageName}.") }.toSet()
    }
    return when {
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
        key.startsWith("system_update_") -> setOf(ScopeApplication.SYSTEM_UPDATE)
        key.startsWith("screenrecorder_") -> setOf(ScopeApplication.SCREEN_RECORDER, ScopeApplication.XIAOMI_HEALTH)
        key.contains("music_controls_") -> setOf(ScopeApplication.SUBSCREEN_CENTER)
        key.startsWith("super_xiaoai_") -> setOf(ScopeApplication.SUPER_XIAOAI_IME)
        key == KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED || key == KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE ->
            setOf(ScopeApplication.SUBSCREEN_CENTER)
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
