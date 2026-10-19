// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED = "subscreen_inject_left_swipe_gesture"
internal const val KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE = "subscreen_custom_launch_package"

data class RearScreenGestureSettings(
    val enabled: Boolean = false,
    val packageName: String = "",
) {
    fun normalized() = copy(packageName = packageName.trim())
}

class RearScreenGestureSettingsStore internal constructor(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings: RearScreenGestureSettings = local.readRearScreenGestureSettings()
        private set

    fun reload() { settings = local.readRearScreenGestureSettings() }

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        reload()
        val remoteValue = remote.readRearScreenGestureSettings()
        // Local edits (including an explicitly cleared app) survive offline saves and
        // returning from the picker. Recover remote values only on a fresh local store.
        settings = RearScreenGestureSettings(
            enabled = if (local.contains(KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED)) settings.enabled else remoteValue.enabled,
            packageName = if (local.contains(KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE)) settings.packageName else remoteValue.packageName,
        ).normalized()
        local.writeRearScreenGestureSettings(settings)
        remote.writeRearScreenGestureSettings(settings)
    }

    fun update(service: XposedService?, transform: (RearScreenGestureSettings) -> RearScreenGestureSettings) {
        settings = transform(local.readRearScreenGestureSettings()).normalized()
        local.writeRearScreenGestureSettings(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.writeRearScreenGestureSettings(settings)
    }
}

internal fun SharedPreferences.readRearScreenGestureSettings() = RearScreenGestureSettings(
    enabled = getBoolean(KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED, false),
    packageName = getString(KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE, "").orEmpty(),
).normalized()

private fun SharedPreferences.writeRearScreenGestureSettings(value: RearScreenGestureSettings) {
    edit().putBoolean(KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED, value.enabled)
        .putString(KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE, value.packageName).apply()
}
