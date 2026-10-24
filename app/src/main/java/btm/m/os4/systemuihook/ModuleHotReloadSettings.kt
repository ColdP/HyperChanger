// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import io.github.libxposed.service.XposedService

internal const val KEY_PROCESS_HOT_RELOAD = "module_hot_reload_v1_enabled"

/** App-side permission for explicit reload requests, independent of HookSettings. */
internal class ModuleHotReloadSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    val enabled: Boolean get() = local.getBoolean(KEY_PROCESS_HOT_RELOAD, true)

    fun update(service: XposedService?, enabled: Boolean) {
        local.edit().putBoolean(KEY_PROCESS_HOT_RELOAD, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_PROCESS_HOT_RELOAD, enabled)?.apply()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val value = if (local.contains(KEY_PROCESS_HOT_RELOAD)) enabled else remote.getBoolean(KEY_PROCESS_HOT_RELOAD, true)
        local.edit().putBoolean(KEY_PROCESS_HOT_RELOAD, value).apply()
        remote.edit().putBoolean(KEY_PROCESS_HOT_RELOAD, value).apply()
    }
}
