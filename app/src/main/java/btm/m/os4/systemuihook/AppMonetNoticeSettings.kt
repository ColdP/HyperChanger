// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService

internal const val KEY_APP_MONET_NOTICE_DISMISSED = "app_theme_monet_notice_dismissed"
internal const val KEY_APP_MONET_NOTICE_DISMISS_COUNT = "app_theme_monet_notice_dismiss_count"
internal const val APP_MONET_NOTICE_DISMISS_LIMIT = 3

internal fun readAppMonetNoticeDismissCount(p: SharedPreferences): Int =
    if (p.contains(KEY_APP_MONET_NOTICE_DISMISS_COUNT)) {
        p.getInt(KEY_APP_MONET_NOTICE_DISMISS_COUNT, 0).coerceIn(0, APP_MONET_NOTICE_DISMISS_LIMIT)
    } else if (p.getBoolean(KEY_APP_MONET_NOTICE_DISMISSED, false)) 1 else 0

/** Owns the notice's close count; per-visit visibility remains in the page. */
internal class AppMonetNoticeSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var dismissCount by mutableStateOf(readAppMonetNoticeDismissCount(local)); private set

    fun observe(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_MONET_NOTICE_DISMISS_COUNT || key == KEY_APP_MONET_NOTICE_DISMISSED) {
                dismissCount = readAppMonetNoticeDismissCount(local)
            }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        dismissCount = readAppMonetNoticeDismissCount(local)
        return { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Prefer saved local edits so an offline reset is not overwritten by an older remote count.
        dismissCount = when {
            local.contains(KEY_APP_MONET_NOTICE_DISMISS_COUNT) -> readAppMonetNoticeDismissCount(local)
            remote.contains(KEY_APP_MONET_NOTICE_DISMISS_COUNT) -> readAppMonetNoticeDismissCount(remote)
            else -> maxOf(readAppMonetNoticeDismissCount(local), readAppMonetNoticeDismissCount(remote))
        }
        write(local, dismissCount)
        write(remote, dismissCount)
    }

    fun dismiss(service: XposedService?) {
        update(service, (dismissCount + 1).coerceAtMost(APP_MONET_NOTICE_DISMISS_LIMIT))
    }

    fun reset(service: XposedService?) = update(service, 0)

    private fun update(service: XposedService?, value: Int) {
        dismissCount = value
        write(local, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(p: SharedPreferences, value: Int) {
        p.edit().putInt(KEY_APP_MONET_NOTICE_DISMISS_COUNT, value)
            .remove(KEY_APP_MONET_NOTICE_DISMISSED).apply()
    }
}
