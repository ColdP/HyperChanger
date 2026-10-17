// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Switches bound to `com.xiaomi.misettings` (the MIUI/HyperOS "Xiaomi Settings" app).
 *
 * `showMoreFps` replaces the `fpsList` array that FeatureParser hands to the refresh-rate picker,
 * the same approach HyperCeiler uses. It only changes the list of choices - the panel still refreshes
 * at whatever rate the hardware supports, so a value above the panel rating will not really apply.
 */
private const val KEY_MI_SETTINGS_MORE_FPS = "mi_settings_show_more_fps"

internal data class MiSettingsPreferences(
    val showMoreFps: Boolean = false,
)

internal fun readMiSettingsPreferences(p: SharedPreferences) = MiSettingsPreferences(
    showMoreFps = p.getBoolean(KEY_MI_SETTINGS_MORE_FPS, false),
)

internal class MiSettingsPreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readMiSettingsPreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Local wins: the UI writes locally first and may do so before the binding exists.
        settings = if (local.contains(KEY_MI_SETTINGS_MORE_FPS)) {
            readMiSettingsPreferences(local)
        } else {
            readMiSettingsPreferences(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (MiSettingsPreferences) -> MiSettingsPreferences) {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: MiSettingsPreferences) {
        p.edit()
            .putBoolean(KEY_MI_SETTINGS_MORE_FPS, s.showMoreFps)
            .apply()
    }
}
