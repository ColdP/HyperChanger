// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_SHARED_VIEW_ANIMATION = "app_shared_view_animation_enabled"

internal class SharedViewAnimationSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(local.getBoolean(KEY_SHARED_VIEW_ANIMATION, false)); private set

    fun observe(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_SHARED_VIEW_ANIMATION) {
                enabled = local.getBoolean(KEY_SHARED_VIEW_ANIMATION, false)
            }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        enabled = local.getBoolean(KEY_SHARED_VIEW_ANIMATION, false)
        return { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Preserve edits made while the framework service was disconnected.
        val source = if (local.contains(KEY_SHARED_VIEW_ANIMATION)) local else remote
        enabled = source.getBoolean(KEY_SHARED_VIEW_ANIMATION, false)
        write(local, enabled)
        write(remote, enabled)
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        write(local, value)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(preferences: SharedPreferences, value: Boolean) {
        preferences.edit().putBoolean(KEY_SHARED_VIEW_ANIMATION, value).apply()
    }
}

internal fun completeSharedViewAnimationPreset(values: JSONObject) {
    val encoded = values.optJSONObject(KEY_SHARED_VIEW_ANIMATION)
    if (encoded == null) {
        values.put(KEY_SHARED_VIEW_ANIMATION, JSONObject().put("type", "boolean").put("value", false))
    } else {
        require(encoded.getString("type") == "boolean" && encoded.get("value") is Boolean)
    }
}
