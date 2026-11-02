// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONObject

/** Include the existing Dock reveal default even before the switch is touched. */
internal fun completeDockRevealPreset(values: JSONObject) {
    if (!values.has(KEY_DOCK_ENTRY_ANIMATION)) {
        values.put(KEY_DOCK_ENTRY_ANIMATION, JSONObject().put("type", "boolean").put("value", true))
    }
}
