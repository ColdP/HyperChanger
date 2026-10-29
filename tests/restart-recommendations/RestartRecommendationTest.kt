package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

private class Preferences {
    val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(args!![0])
            "getBoolean", "getInt", "getFloat", "getString", "getStringSet" -> values[args!![0]] ?: args[1]
            "registerOnSharedPreferenceChangeListener" -> { listeners += args!![0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            "unregisterOnSharedPreferenceChangeListener" -> { listeners -= args!![0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            else -> error("Unexpected call: ${method.name}")
        }
    } as SharedPreferences

    fun write(vararg changes: Pair<String, Any?>) {
        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        // Android delivers one callback per key after the whole editor transaction is applied.
        changes.forEach { (key, _) -> listeners.toList().forEach { it.onSharedPreferenceChanged(prefs, key) } }
    }

    fun clear() {
        values.clear()
        listeners.toList().forEach { it.onSharedPreferenceChanged(prefs, null) }
    }
}

fun main() {
    val portalPrefs = Preferences()
    check(PortalSettings.read(portalPrefs.prefs) == PortalAppearanceSettings())
    portalPrefs.write(KEY_PORTAL_BLUR_RADIUS to -1, KEY_PORTAL_OPACITY to 101, KEY_PORTAL_CORNER to -33)
    check(PortalSettings.read(portalPrefs.prefs).let { it.blurRadiusDp == 0 && it.opacityPercent == 100 && it.cornerOffsetDp == -32 })
    portalPrefs.write(KEY_PORTAL_BLUR_RADIUS to 101, KEY_PORTAL_OPACITY to -1, KEY_PORTAL_CORNER to 65)
    check(PortalSettings.read(portalPrefs.prefs).let { it.blurRadiusDp == 100 && it.opacityPercent == 0 && it.cornerOffsetDp == 64 })
    check(portalCornerRadius(30f, 0, 3f) == 30f)
    check(portalCornerRadius(30f, -32, 3f) == 0f)
    check(portalCornerRadius(30f, -5, 3f) == 15f)
    check(portalCornerRadius(30f, 64, 3f) == 222f)
    val dialerPrefs = Preferences()
    check(ContactsDialerSettings.read(dialerPrefs.prefs) == ContactsDialerBackgroundSettings())
    dialerPrefs.write(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS to -1, KEY_CONTACTS_DIALER_BACKGROUND_OPACITY to 101)
    check(ContactsDialerSettings.read(dialerPrefs.prefs).let { it.blurRadiusDp == 0 && it.opacityPercent == 100 })
    dialerPrefs.write(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS to 101, KEY_CONTACTS_DIALER_BACKGROUND_OPACITY to -1)
    check(ContactsDialerSettings.read(dialerPrefs.prefs).let { it.blurRadiusDp == 100 && it.opacityPercent == 0 })
    dialerPrefs.write(
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH to 1,
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY to 100,
        KEY_CONTACTS_DIALER_BACKGROUND_CORNER to -50,
    )
    check(ContactsDialerSettings.read(dialerPrefs.prefs).let { it.borderWidthPx == .01f && it.cornerOffset == -50 })
    dialerPrefs.write(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH to 123)
    check(ContactsDialerSettings.read(dialerPrefs.prefs).borderWidthPx == 1.23f)
    dialerPrefs.write(
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH to 601,
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY to -1,
        KEY_CONTACTS_DIALER_BACKGROUND_CORNER to 65,
    )
    check(ContactsDialerSettings.read(dialerPrefs.prefs).let {
        it.borderWidthPx == 6f && it.borderOpacityPercent == 0 && it.cornerOffset == 64
    })
    check(contactsDialerCornerRadius(90f, 0, 3f) == 90f)
    check(contactsDialerCornerRadius(90f, -50, 3f) == 0f)
    check(contactsDialerCornerRadius(90f, -25, 3f) == 45f)
    check(contactsDialerCornerRadius(90f, 64, 3f) == 282f)
    val local = Preferences()
    val remote = Preferences()
    val events = mutableListOf<Set<ScopeApplication>>()
    val tracker = RestartRecommendationTracker(
        listOf(REMOTE_PREFERENCE_GROUP to local.prefs, REMOTE_PREFERENCE_GROUP to remote.prefs),
        ::restartRecommendationSnapshot,
    ) { events += it }
    tracker.start()
    check(events.isEmpty())

    // Opening a page can persist getter defaults. This must stay quiet.
    local.write("island_enabled" to false, "island_width" to 108, "small_island_width" to 34)
    local.write("ios_notification_center" to false, "ios_notification_header" to false, "ios_notification_clear_style" to 0)
    local.write("control_center_header_height_offset_dp" to 0)
    check(events.isEmpty()) { "Default materialization generated restart suggestions: $events" }
    local.write(
        KEY_PORTAL_BLUR to false, KEY_PORTAL_CUSTOM_BLUR to false, KEY_PORTAL_BLUR_RADIUS to 24,
        KEY_PORTAL_CUSTOM_OPACITY to false, KEY_PORTAL_OPACITY to 70,
        KEY_PORTAL_CUSTOM_CORNER to false, KEY_PORTAL_CORNER to 0, KEY_PORTAL_DEVELOPER to false,
        KEY_PORTAL_REMOVE_SCRIM to false,
        KEY_PORTAL_JAPANESE_OCR to false,
    )
    check(events.isEmpty()) { "Portal defaults must not suggest a restart" }
    local.write(KEY_PORTAL_BLUR to true, KEY_PORTAL_CUSTOM_BLUR to true, KEY_PORTAL_BLUR_RADIUS to 48)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    check(events.isEmpty()) { "A multi-key Portal edit must produce one event" }
    local.write(KEY_PORTAL_CUSTOM_OPACITY to true, KEY_PORTAL_OPACITY to 0)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    local.write(KEY_PORTAL_CUSTOM_CORNER to true, KEY_PORTAL_CORNER to -32)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    local.write(KEY_PORTAL_DEVELOPER to true)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    local.write(KEY_PORTAL_REMOVE_SCRIM to true)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    check(PortalSettings.read(local.prefs).removeScrim)
    local.write(KEY_PORTAL_JAPANESE_OCR to true)
    check(events.removeLast() == setOf(ScopeApplication.PORTAL))
    check(PortalSettings.read(local.prefs).japaneseOcr)
    check(events.isEmpty())
    local.write(
        KEY_CONTACTS_DIALER_BACKGROUND_BLUR to false,
        KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM to false,
        KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS to 24,
        KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED to false,
        KEY_CONTACTS_DIALER_BACKGROUND_OPACITY to 70,
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER to false,
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH to 100,
        KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY to 100,
        KEY_CONTACTS_DIALER_BACKGROUND_CORNER to 0,
    )
    check(events.isEmpty()) { "Dialer defaults must not suggest a restart" }
    local.write(KEY_CONTACTS_DIALER_BACKGROUND_BLUR to true)
    check(events.removeLast() == setOf(ScopeApplication.CONTACTS_DIALER))
    local.write(KEY_CONTACTS_DIALER_BACKGROUND_BLUR_CUSTOM to true, KEY_CONTACTS_DIALER_BACKGROUND_BLUR_RADIUS to 48)
    check(events.removeLast() == setOf(ScopeApplication.CONTACTS_DIALER))
    check(events.isEmpty()) { "A multi-key dialer edit must produce one event" }
    local.write(KEY_CONTACTS_DIALER_BACKGROUND_OPACITY_ENABLED to true, KEY_CONTACTS_DIALER_BACKGROUND_OPACITY to 0)
    check(events.removeLast() == setOf(ScopeApplication.CONTACTS_DIALER))
    check(events.isEmpty())
    local.write(KEY_CONTACTS_DIALER_BACKGROUND_BORDER to true, KEY_CONTACTS_DIALER_BACKGROUND_BORDER_WIDTH to 1)
    check(events.removeLast() == setOf(ScopeApplication.CONTACTS_DIALER))
    local.write(KEY_CONTACTS_DIALER_BACKGROUND_BORDER_OPACITY to 50, KEY_CONTACTS_DIALER_BACKGROUND_CORNER to -50)
    check(events.removeLast() == setOf(ScopeApplication.CONTACTS_DIALER))
    check(events.isEmpty())
    local.write(KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED to false, KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE to "")
    check(events.isEmpty()) { "Rear-screen defaults must not suggest a restart" }
    local.write(KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED to true)
    check(events.removeLast() == setOf(ScopeApplication.SUBSCREEN_CENTER))
    local.write(KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE to "example.app")
    check(events.removeLast() == setOf(ScopeApplication.SUBSCREEN_CENTER))
    local.write(KEY_REAR_MINI_PLAYER_ENABLED to false, KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES to false)
    check(events.isEmpty()) { "Mini player default materialization must stay quiet" }
    local.write(KEY_REAR_MINI_PLAYER_ENABLED to true)
    check(events.removeLast() == setOf(ScopeApplication.NETEASE_MUSIC, ScopeApplication.SUBSCREEN_CENTER))
    local.write(KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES to true)
    check(events.isEmpty()) { "Framework policy must not recommend unrelated app restarts" }

    local.write("island_spacing_enabled" to true, "island_spacing_offset_dp" to 12)
    check(events.removeLast() == setOf(ScopeApplication.SYSTEM_UI))
    check(events.isEmpty()) { "A multi-key edit must produce one event" }
    local.write("island_spacing_enabled" to true, "island_spacing_offset_dp" to 12)
    check(events.isEmpty()) { "An unchanged write must stay quiet" }

    remote.write("scope.com.mipay.wallet.visible_tabs" to setOf("HOME", "PROFILE"))
    check(events.removeLast() == setOf(ScopeApplication.XIAOMI_WALLET))
    remote.write("scope.com.lucky.luckyclient.enabled" to false)
    check(events.removeLast() == setOf(ScopeApplication.LUCKIN_COFFEE))
    local.write("super_xiaoai_blacklist_unblocked" to true)
    check(events.removeLast() == setOf(ScopeApplication.SUPER_XIAOAI_IME))
    local.write("home_multi_split_enabled" to true)
    check(events.removeLast() == setOf(ScopeApplication.DESKTOP))
    local.write("rear_wallpaper_limit_enabled" to true)
    check(events.removeLast() == setOf(ScopeApplication.THEME_MANAGER))
    local.write("settings_old_control_center" to true)
    check(events.removeLast() == setOf(ScopeApplication.SETTINGS))
    local.write("audio_bose_enabled" to true)
    check(events.removeLast() == setOf(ScopeApplication.SOUND_EFFECT))
    local.write("mi_settings_show_more_fps" to true)
    check(events.removeLast() == setOf(ScopeApplication.MI_SETTINGS))

    local.write("home_material_enabled" to true, "home_full_glass_v1_use_global" to true)
    check(events.removeLast() == setOf(ScopeApplication.DESKTOP))
    local.write("soft_glass_parameters_v3_refraction_blur_percent" to 120)
    check(events.removeLast() == setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.DESKTOP))
    local.write("home_full_glass_v1_use_global" to false)
    check(events.removeLast() == setOf(ScopeApplication.DESKTOP))
    local.write("soft_glass_parameters_v3_refraction_blur_percent" to 130)
    check(events.removeLast() == setOf(ScopeApplication.SYSTEM_UI))

    local.write("navigation_style" to "liquid", "soft_glass_custom_presets_v1" to "[]", "initialized" to true)
    check(events.isEmpty()) { "Module-only preferences must stay quiet" }
    tracker.withoutTracking {
        local.write("island_width" to 140)
        remote.write("island_width" to 140)
    }
    local.write("island_width" to 140)
    check(events.isEmpty()) { "Sync baseline must not leak into subsequent callbacks" }
    local.write("island_width" to 150)
    check(events.removeLast() == setOf(ScopeApplication.SYSTEM_UI))
    local.write("island_width" to null)
    check(events.removeLast() == setOf(ScopeApplication.SYSTEM_UI))
    remote.clear()
    check(events.isNotEmpty()) { "Clear callbacks must detect removed keys" }
    events.clear()
    tracker.close()
    local.write("island_width" to 170)
    check(events.isEmpty()) { "Disposed trackers must unregister all listeners" }

    check(restartScopesForPreference("device_profile", "model") == setOf(ScopeApplication.SETTINGS))
    check(restartScopesForPreference("settings_appearance", "logo_mode") == setOf(ScopeApplication.SETTINGS))
    check(restartScopesForPreference("settings", "palette_tone") == setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR))
    println("Restart recommendation regression checks passed")
}
