// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Switches that reshape the system Settings app itself.
 *
 * This is best-effort: it flips an internal flag the platform does not expose, so it may not take
 * effect on every device or ROM build. Its summary says so.
 */
private const val KEY_SETTINGS_OLD_CONTROL_CENTER = "settings_old_control_center"

internal data class SettingsAppScopePreferences(
    val oldControlCenter: Boolean = false,
)

internal fun readSettingsAppScopePreferences(p: SharedPreferences) = SettingsAppScopePreferences(
    oldControlCenter = p.getBoolean(KEY_SETTINGS_OLD_CONTROL_CENTER, false),
)

internal class SettingsAppScopePreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSettingsAppScopePreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Local wins: the UI writes locally first and may do so before the binding exists.
        settings = if (local.contains(KEY_SETTINGS_OLD_CONTROL_CENTER)) {
            readSettingsAppScopePreferences(local)
        } else {
            readSettingsAppScopePreferences(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SettingsAppScopePreferences) -> SettingsAppScopePreferences) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SettingsAppScopePreferences) {
        p.edit()
            .putBoolean(KEY_SETTINGS_OLD_CONTROL_CENTER, s.oldControlCenter)
            .apply()
    }
}
