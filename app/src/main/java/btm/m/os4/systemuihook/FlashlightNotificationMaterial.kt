// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONObject

/** Keep actions, island metadata and newer protocol fields from the original plugin. */
internal fun unifyFlashlightNotificationPayload(payload: String): String {
    val json = JSONObject(payload)
    val template = json.optJSONObject("param_v2") ?: return payload
    template.optJSONObject("bgInfo")?.let { background ->
        background.put("colorBg", "")
        if (background.has("colorBgDark")) background.put("colorBgDark", "")
    }
    template.optJSONObject("baseInfo")?.let { info ->
        listOf("colorTitle", "colorContent", "colorSubContent").forEach { key ->
            if (info.has(key)) info.put(key, "")
            if (info.has(key + "Dark")) info.put(key + "Dark", "")
        }
    }
    return json.toString()
}
