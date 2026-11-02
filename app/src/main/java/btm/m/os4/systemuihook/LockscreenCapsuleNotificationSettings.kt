// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_CAPSULE_NOTIFICATION_TYPES = "lockscreen_capsule_notification_types_v1"
internal const val CAPSULE_NOTIFICATION_MEDIA = 1
internal const val CAPSULE_NOTIFICATION_FOCUS = 2
internal const val CAPSULE_NOTIFICATION_ORDINARY = 4
internal const val CAPSULE_NOTIFICATION_ALL = 7

internal fun readCapsuleNotificationTypes(p: SharedPreferences): Int =
    p.getInt(KEY_CAPSULE_NOTIFICATION_TYPES, legacyCapsuleNotificationTypes(
        p.getString(HMC_NEXT_SETTINGS_KEY, null),
    )) and CAPSULE_NOTIFICATION_ALL

private fun legacyCapsuleNotificationTypes(raw: String?): Int {
    val ordinary = runCatching { JSONObject(raw.orEmpty()).optBoolean("ordinaryNotifications", false) }
        .getOrDefault(false)
    return CAPSULE_NOTIFICATION_MEDIA or CAPSULE_NOTIFICATION_FOCUS or
        (if (ordinary) CAPSULE_NOTIFICATION_ORDINARY else 0)
}

/** Used for untouched settings and older presets as well as the live settings reader. */
internal fun completeCapsuleNotificationPreset(values: JSONObject) {
    if (!values.has(KEY_CAPSULE_NOTIFICATION_TYPES)) {
        val legacy = values.optJSONObject(HMC_NEXT_SETTINGS_KEY)?.optString("value")
        values.put(KEY_CAPSULE_NOTIFICATION_TYPES, JSONObject().put("type", "int")
            .put("value", legacyCapsuleNotificationTypes(legacy)))
    }
}

internal class LockscreenCapsuleNotificationSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var types = readCapsuleNotificationTypes(local)
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        // Offline edits win; older installations inherit the existing ordinary-notification choice.
        types = when {
            local.contains(KEY_CAPSULE_NOTIFICATION_TYPES) -> readCapsuleNotificationTypes(local)
            remote.contains(KEY_CAPSULE_NOTIFICATION_TYPES) -> readCapsuleNotificationTypes(remote)
            local.contains(HMC_NEXT_SETTINGS_KEY) -> readCapsuleNotificationTypes(local)
            else -> readCapsuleNotificationTypes(remote)
        }
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, value: Int) {
        types = value and CAPSULE_NOTIFICATION_ALL
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().putInt(KEY_CAPSULE_NOTIFICATION_TYPES, types).apply()
    }
}
