// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Notification
import android.os.Bundle
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject

/** Adds the simulator's official templates to a copy of a GMS-owned transfer notification. */
internal object QuickShareIslandBridge {
    private const val MARKER = "hyperchanger.gms.quick_share"
    private const val PARAM = "miui.focus.param"
    private const val PICS = "miui.focus.pics"
    private const val ACTIONS = "miui.focus.actions"

    // Malformed extras, unavailable icons or oversized payloads must never suppress GMS delivery.
    fun transform(tag: String?, original: Notification): Notification? = runCatching {
        if (tag != "nearby_sharing" || original.channelId != "nearby_sharing_file") return null
        if (original.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val source = original.extras ?: return null
        if (source.containsKey(PARAM) || source.getBoolean(MARKER, false)) return null
        val title = source.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty().take(120)
        val content = source.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty().take(180)
        if (title.isEmpty() && content.isEmpty()) return null
        val icon = original.smallIcon ?: return null
        val indeterminate = source.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val max = source.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val raw = source.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = !indeterminate && max > 0
        val percent = if (hasProgress) (raw.toLong().coerceIn(0L, max.toLong()) * 100L / max).toInt() else 0
        val ongoing = indeterminate || hasProgress && percent < 100 ||
            original.flags and Notification.FLAG_ONGOING_EVENT != 0
        // All state text comes from GMS's localized notification. No inferred success or timers.
        val summary = content.ifEmpty { title }.take(80)
        val type = if (hasProgress) IslandSimulationType.PROGRESS else IslandSimulationType.FILE_SHARE
        val root = JSONObject(buildIslandSimulationPayload(
            type = type,
            settings = IslandSimulationSettings(title = title, content = content, summary = summary),
            progress = percent,
            terminal = false,
            completedText = summary,
            callStartedAt = 0L,
            kilometerText = "",
            codeText = "",
        ))
        val param = root.getJSONObject("param_v2")
        param.put("updatable", ongoing)
        param.getJSONObject("param_island").put("islandTimeout", if (ongoing) 3600 else 30)
        param.remove("textButton") // Replace simulation actions with GMS's real PendingIntents.

        val actions = Bundle(source.getBundle(ACTIONS) ?: Bundle())
        val pictures = Bundle(source.getBundle(PICS) ?: Bundle()).apply { putParcelable(ISLAND_SIM_ICON, icon) }
        val buttons = JSONArray()
        original.actions.orEmpty().filter { action ->
            action.actionIntent != null && !action.title.isNullOrBlank() && action.remoteInputs.isNullOrEmpty()
        }.take(2).forEachIndexed { index, action ->
            val key = "hyperchanger.gms.quick_share.action.$index"
            actions.putParcelable(key, action)
            pictures.putParcelable(key, action.getIcon() ?: icon)
            buttons.put(JSONObject().put("action", key)
                .put("actionTitle", TextUtils.htmlEncode(action.title.toString().take(48)))
                .put("actionIcon", key).put("actionIconDark", key).put("type", 0))
        }
        if (buttons.length() > 0) {
            param.put(if (type == IslandSimulationType.FILE_SHARE) "textButton" else "actions", buttons)
        }
        val payload = root.toString()
        if (payload.toByteArray(Charsets.UTF_8).size > 3072) return null
        original.clone().apply {
            extras = Bundle(source).apply {
                putString(PARAM, payload)
                putBundle(PICS, pictures)
                putBundle(ACTIONS, actions)
                putBoolean(MARKER, true)
            }
        }
    }.getOrNull()
}
