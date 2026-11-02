// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Settings for how notifications enter the lockscreen capsule. */
internal const val KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE = "lockscreen_capsule_auto_collapse"
internal const val KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES = "lockscreen_capsule_auto_collapse_types_v1"

internal fun readLockscreenCapsuleAutoCollapseTypes(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES,
        if (preferences.getBoolean(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE, true)) CAPSULE_NOTIFICATION_ALL else 0
    ) and CAPSULE_NOTIFICATION_ALL

internal fun completeLockscreenCapsuleBehaviorPreset(values: JSONObject) {
    if (!values.has(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES)) {
        val legacy = values.optJSONObject(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE)?.optBoolean("value", true) ?: true
        values.put(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES, JSONObject()
            .put("type", "int")
            .put("value", if (legacy) CAPSULE_NOTIFICATION_ALL else 0))
    }
}

internal class LockscreenCapsuleBehaviorSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var types: Int = readLockscreenCapsuleAutoCollapseTypes(local)
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        types = when {
            local.contains(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES) -> readLockscreenCapsuleAutoCollapseTypes(local)
            remote.contains(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES) -> readLockscreenCapsuleAutoCollapseTypes(remote)
            local.contains(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE) -> readLockscreenCapsuleAutoCollapseTypes(local)
            else -> readLockscreenCapsuleAutoCollapseTypes(remote)
        }
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, value: Int) {
        types = value and CAPSULE_NOTIFICATION_ALL
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun reload() {
        types = readLockscreenCapsuleAutoCollapseTypes(local)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().putInt(KEY_LOCKSCREEN_CAPSULE_AUTO_COLLAPSE_TYPES, types).apply()
    }
}
