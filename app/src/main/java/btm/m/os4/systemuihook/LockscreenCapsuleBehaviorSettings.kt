// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Settings for how notifications enter the lockscreen capsule. */
internal const val KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE = "lockscreen_capsule_auto_collapse"

internal fun readLockscreenCapsuleAutoCollapse(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE, true)

internal fun completeLockscreenCapsuleBehaviorPreset(values: JSONObject) {
    if (!values.has(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE)) {
        values.put(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE, JSONObject()
            .put("type", "boolean")
            .put("value", true))
    }
}

internal class LockscreenCapsuleBehaviorSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var autoCollapse: Boolean = readLockscreenCapsuleAutoCollapse(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        autoCollapse = when {
            local.contains(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE) -> readLockscreenCapsuleAutoCollapse(local)
            remote.contains(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE) -> readLockscreenCapsuleAutoCollapse(remote)
            else -> true
        }
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, value: Boolean) {
        autoCollapse = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun reload() {
        autoCollapse = readLockscreenCapsuleAutoCollapse(local)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().putBoolean(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE, autoCollapse).apply()
    }
}
