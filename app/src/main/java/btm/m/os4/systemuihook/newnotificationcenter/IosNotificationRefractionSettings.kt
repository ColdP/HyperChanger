// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

const val KEY_IOS_NOTIFICATION_BOTTOM_REFRACTION = "ios_notification_bottom_refraction"

fun readIosNotificationBottomRefraction(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_IOS_NOTIFICATION_BOTTOM_REFRACTION, true)

internal fun completeIosNotificationRefractionPreset(values: JSONObject) {
    if (!values.has(KEY_IOS_NOTIFICATION_BOTTOM_REFRACTION)) {
        values.put(KEY_IOS_NOTIFICATION_BOTTOM_REFRACTION,
            JSONObject().put("type", "boolean").put("value", true))
    }
}

class IosNotificationRefractionSettingsStore internal constructor(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var enabled: Boolean = readIosNotificationBottomRefraction(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        enabled = readIosNotificationBottomRefraction(local)
        write(remote)
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().putBoolean(KEY_IOS_NOTIFICATION_BOTTOM_REFRACTION, enabled).apply()
    }
}
