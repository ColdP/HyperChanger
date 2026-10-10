// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_REAR_SCREEN_THEME_APPLY_FIX = "rear_screen_theme_apply_fix"

/** Kept outside HookSettings and its serialization. */
class RearScreenThemeApplySettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var enabled: Boolean = local.isRearScreenThemeApplyFixEnabled()
        private set

    fun reload() {
        enabled = local.isRearScreenThemeApplyFixEnabled()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_REAR_SCREEN_THEME_APPLY_FIX)) {
            enabled = remote.isRearScreenThemeApplyFixEnabled()
        }
        local.edit().putBoolean(KEY_REAR_SCREEN_THEME_APPLY_FIX, enabled).apply()
        remote.edit().putBoolean(KEY_REAR_SCREEN_THEME_APPLY_FIX, enabled).apply()
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        local.edit().putBoolean(KEY_REAR_SCREEN_THEME_APPLY_FIX, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_REAR_SCREEN_THEME_APPLY_FIX, value)?.apply()
    }
}

internal fun SharedPreferences.isRearScreenThemeApplyFixEnabled(): Boolean =
    getBoolean(KEY_REAR_SCREEN_THEME_APPLY_FIX, false)
