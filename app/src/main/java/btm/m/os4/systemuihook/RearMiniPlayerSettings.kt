// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_REAR_MINI_PLAYER_ENABLED = "rear_mini_player_enabled"
internal const val KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES = "rear_mini_player_allow_all_activities"

/** Kept out of HookSettings and its serialization paths. */
internal class RearMiniPlayerSettingsStore internal constructor(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var enabled = local.isRearMiniPlayerEnabled()
        private set
    var allowAllActivities = local.isRearMiniPlayerAllowAllActivities()
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        // Offline edits, including explicit disable, take precedence over stale remote values.
        enabled = if (local.contains(KEY_REAR_MINI_PLAYER_ENABLED)) local.isRearMiniPlayerEnabled()
            else remote.isRearMiniPlayerEnabled()
        allowAllActivities = if (local.contains(KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES)) local.isRearMiniPlayerAllowAllActivities()
            else remote.isRearMiniPlayerAllowAllActivities()
        local.writeRearMiniPlayerEnabled(enabled)
        remote.writeRearMiniPlayerEnabled(enabled)
        local.writeRearMiniPlayerAllowAllActivities(allowAllActivities)
        remote.writeRearMiniPlayerAllowAllActivities(allowAllActivities)
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        local.writeRearMiniPlayerEnabled(value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.writeRearMiniPlayerEnabled(value)
    }

    fun prepareLaunch(service: XposedService?): Boolean = service != null &&
        commitLaunchPreference(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun commitLaunchPreference(remote: SharedPreferences): Boolean {
        enabled = local.isRearMiniPlayerEnabled()
        // apply() publishes asynchronously; launch must not race its first enable transaction.
        return enabled && remote.edit().putBoolean(KEY_REAR_MINI_PLAYER_ENABLED, true).commit()
    }

    fun updateAllowAllActivities(service: XposedService?, value: Boolean) {
        allowAllActivities = value
        local.writeRearMiniPlayerAllowAllActivities(value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.writeRearMiniPlayerAllowAllActivities(value)
    }
}

internal fun SharedPreferences.isRearMiniPlayerEnabled() = getBoolean(KEY_REAR_MINI_PLAYER_ENABLED, false)

private fun SharedPreferences.writeRearMiniPlayerEnabled(value: Boolean) {
    edit().putBoolean(KEY_REAR_MINI_PLAYER_ENABLED, value).apply()
}

internal fun SharedPreferences.isRearMiniPlayerAllowAllActivities() =
    getBoolean(KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES, false)

private fun SharedPreferences.writeRearMiniPlayerAllowAllActivities(value: Boolean) {
    edit().putBoolean(KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES, value).apply()
}
