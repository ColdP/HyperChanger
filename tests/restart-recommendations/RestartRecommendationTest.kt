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
