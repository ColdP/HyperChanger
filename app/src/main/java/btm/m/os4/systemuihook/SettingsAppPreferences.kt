// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/**
 * Preferences that belong to the system Settings app integration (`com.android.settings`).
 *
 * These used to live in `HookSettings`; they are kept in their own file and data class so the module
 * app entry, the Wi-Fi password viewer and the device-profile gates can evolve without growing the
 * shared settings blob.
 *
 * Wire format note: every key below keeps its original name, so existing installs keep their values
 * across the move.
 */
internal const val KEY_UNLOCK_NEVER_SCREEN_TIMEOUT = "unlock_never_screen_timeout"
internal const val KEY_SHOW_GOOGLE_SERVICE_ENTRY = "show_google_service_entry"
internal const val KEY_SHOW_SAVED_WIFI_PASSWORDS = "show_saved_wifi_passwords"
internal const val KEY_SETTINGS_APP_ENTRY_POSITION = "settings_app_entry_position"
internal const val KEY_SETTINGS_APP_ENTRY_TITLE = "settings_app_entry_title"
internal const val KEY_SETTINGS_APP_ENTRY_LABELS = "settings_app_entry_labels"

/** Header titles are single line, so the custom entry title stays short. */
internal const val MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH = 24

/**
 * Dashboard entry labels, keyed by the header id's **resource entry name** (for example
 * `my_device`). Names survive Settings upgrades; the numeric `R.id` values do not.
 */
internal fun readSettingsEntryLabels(p: SharedPreferences): Map<String, String> = runCatching {
    val raw = p.getString(KEY_SETTINGS_APP_ENTRY_LABELS, "{}") ?: "{}"
    val json = JSONObject(raw)
    json.keys().asSequence()
        .mapNotNull { key ->
            val value = json.optString(key).trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH)
            key.takeIf { value.isNotEmpty() }?.let { it to value }
        }
        .toMap()
}.getOrDefault(emptyMap())

internal data class SettingsAppPreferences(
    val unlockNeverScreenTimeout: Boolean = false,
    val showGoogleServiceEntry: Boolean = false,
    val showSavedWifiPasswords: Boolean = false,
    /** 0 = disabled, 1 = top, 2 = middle, 3 = bottom of the Settings dashboard. */
    val entryPosition: Int = 0,
    /** Blank means "use the module's application label". */
    val entryTitle: String = "",
    /** Custom dashboard entry labels; empty values mean "keep the system text". */
    val entryLabels: Map<String, String> = emptyMap(),
) {
    fun normalized() = copy(
        entryPosition = entryPosition.coerceIn(0, 3),
        entryTitle = entryTitle.trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH),
        entryLabels = entryLabels
            .mapValues { (_, value) -> value.trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH) }
            .filterValues { it.isNotEmpty() },
    )
}

internal fun readSettingsAppPreferences(p: SharedPreferences): SettingsAppPreferences =
    SettingsAppPreferences(
        unlockNeverScreenTimeout = p.getBoolean(KEY_UNLOCK_NEVER_SCREEN_TIMEOUT, false),
        showGoogleServiceEntry = p.getBoolean(KEY_SHOW_GOOGLE_SERVICE_ENTRY, false),
        showSavedWifiPasswords = p.getBoolean(KEY_SHOW_SAVED_WIFI_PASSWORDS, false),
        entryPosition = p.getInt(KEY_SETTINGS_APP_ENTRY_POSITION, 0),
        entryTitle = p.getString(KEY_SETTINGS_APP_ENTRY_TITLE, "").orEmpty(),
        entryLabels = readSettingsEntryLabels(p),
    ).normalized()

internal class SettingsAppPreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsAppPreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_SETTINGS_APP_ENTRY_POSITION)) {
            readSettingsAppPreferences(remote)
        } else {
            settings
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsAppPreferences) -> SettingsAppPreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SettingsAppPreferences) {
        p.edit()
            .putBoolean(KEY_UNLOCK_NEVER_SCREEN_TIMEOUT, s.unlockNeverScreenTimeout)
            .putBoolean(KEY_SHOW_GOOGLE_SERVICE_ENTRY, s.showGoogleServiceEntry)
            .putBoolean(KEY_SHOW_SAVED_WIFI_PASSWORDS, s.showSavedWifiPasswords)
            .putInt(KEY_SETTINGS_APP_ENTRY_POSITION, s.entryPosition)
            .putString(KEY_SETTINGS_APP_ENTRY_TITLE, s.entryTitle)
            .putString(KEY_SETTINGS_APP_ENTRY_LABELS, JSONObject(s.entryLabels as Map<*, *>).toString())
            .apply()
    }
}
