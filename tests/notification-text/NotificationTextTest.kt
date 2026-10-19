// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject

private fun preferences(values: MutableMap<String, Any>): SharedPreferences =
    Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getInt" -> values[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                    when (setter.name) {
                        "putInt" -> { pending[input!![0] as String] = input[1]!!; proxy }
                        "apply" -> { values.putAll(pending); null }
                        else -> error(setter.name)
                    }
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences

fun main() {
    for (night in listOf(false, true)) for (headsUp in listOf(false, true)) {
        check(notificationTextColor(0, headsUp, night, 0f) == null)
        check(notificationTextColor(1, headsUp, night, 0f) == 0xE8FFFFFF.toInt())
        check(notificationTextColor(2, headsUp, night, 0f) == 0xE8000000.toInt())
    }
    check(notificationTextColor(3, true, false, .49f) == 0xE8FFFFFF.toInt())
    check(notificationTextColor(3, true, true, .5f) == 0xE8000000.toInt())
    check(notificationTextColor(3, false, false, 1f) == 0xE8FFFFFF.toInt())
    check(notificationTextColor(3, false, true, 0f) == 0xE8000000.toInt())
    val disk = mutableMapOf<String, Any>()
    val local = preferences(disk)
    val remote = preferences(mutableMapOf())
    check(readNotificationTextColorSettings(local) == NotificationTextColorSettings())
    NotificationTextColorPreferences.write(local, NotificationTextColorSettings(3, 2))
    check(NotificationTextColorPreferences.sync(local, remote) == NotificationTextColorSettings(3, 2))
    check(readNotificationTextColorSettings(preferences(disk)) == NotificationTextColorSettings(3, 2))
    NotificationTextColorPreferences.write(remote, NotificationTextColorSettings(1, 0))
    check(NotificationTextColorPreferences.sync(local, remote) == NotificationTextColorSettings(1, 0))
    NotificationTextColorPreferences.write(local, NotificationTextColorSettings(-10, 99))
    check(readNotificationTextColorSettings(local) == NotificationTextColorSettings(0, 3))

    val payload = """{"param_v2":{"business":"miui_flashlight","bgInfo":{"colorBg":"#1A1A1A","colorBgDark":"#000000","other":7},"baseInfo":{"title":"手电筒","colorTitle":"#FFFFFF","colorContent":"#7FFFFFFF","colorSubContent":"#FFFFFF"},"actions":[{"actionIntent":"intent:#Intent;action=close;end"}],"param_island":{"islandProperty":2},"enableFloat":false}}"""
    val normalized = JSONObject(unifyFlashlightNotificationPayload(payload)).getJSONObject("param_v2")
    check(normalized.getJSONObject("bgInfo").getString("colorBg") == "")
    check(normalized.getJSONObject("bgInfo").getString("colorBgDark") == "")
    check(normalized.getJSONObject("bgInfo").getInt("other") == 7)
    check(normalized.getJSONObject("baseInfo").getString("colorTitle") == "")
    check(normalized.getJSONObject("baseInfo").getString("title") == "手电筒")
    val original = JSONObject(payload).getJSONObject("param_v2")
    for (key in listOf("business", "actions", "param_island", "enableFloat")) {
        check(normalized.get(key).toString() == original.get(key).toString()) { "Lost protocol field: $key" }
    }
    val unchanged = "{\"future_protocol\":true}"
    check(unifyFlashlightNotificationPayload(unchanged) == unchanged)
    check(unifyFlashlightNotificationPayload(unifyFlashlightNotificationPayload(payload)) == unifyFlashlightNotificationPayload(payload))
    println("Notification text modes, persistence, sync and flashlight payload preservation checks passed")
}
