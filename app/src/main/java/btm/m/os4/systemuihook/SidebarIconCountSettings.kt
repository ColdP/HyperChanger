// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val SIDEBAR_ICON_COUNT_PREFIX = "sidebar_icon_count_v1_"
internal const val KEY_SIDEBAR_ICON_COUNT_MODE = SIDEBAR_ICON_COUNT_PREFIX + "mode"
internal const val KEY_SIDEBAR_ICON_COUNT_CUSTOM = SIDEBAR_ICON_COUNT_PREFIX + "custom"
internal val sidebarIconCountOptions = listOf(0, 6, 10, 16, 20, 30, 40, -1)
internal const val SIDEBAR_ICON_COUNT_CUSTOM_MODE = 7

internal data class SidebarIconCountSettings(val mode: Int = 0, val custom: Int = 16) {
    val limit: Int? get() = when (mode) {
        0 -> null
        SIDEBAR_ICON_COUNT_CUSTOM_MODE -> custom.coerceIn(1, 100)
        else -> sidebarIconCountOptions.getOrNull(mode)?.takeIf { it > 0 }
    }
    fun normalized() = copy(mode = mode.coerceIn(0, 7), custom = custom.coerceIn(1, 100))
}

internal fun readSidebarIconCountSettings(p: SharedPreferences) = SidebarIconCountSettings(
    p.getInt(KEY_SIDEBAR_ICON_COUNT_MODE, 0), p.getInt(KEY_SIDEBAR_ICON_COUNT_CUSTOM, 16),
).normalized()

internal class SidebarIconCountSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readSidebarIconCountSettings(local); private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    internal fun syncPreferences(remote: SharedPreferences) {
        local.edit().apply {
            if (!local.contains(KEY_SIDEBAR_ICON_COUNT_MODE)) putInt(KEY_SIDEBAR_ICON_COUNT_MODE, remote.getInt(KEY_SIDEBAR_ICON_COUNT_MODE, 0))
            if (!local.contains(KEY_SIDEBAR_ICON_COUNT_CUSTOM)) putInt(KEY_SIDEBAR_ICON_COUNT_CUSTOM, remote.getInt(KEY_SIDEBAR_ICON_COUNT_CUSTOM, 16))
        }.apply()
        settings = readSidebarIconCountSettings(local)
        write(local)
        write(remote)
    }
    fun update(service: XposedService?, transform: (SidebarIconCountSettings) -> SidebarIconCountSettings) {
        settings = transform(readSidebarIconCountSettings(local)).normalized()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    private fun write(p: SharedPreferences) {
        p.edit().putInt(KEY_SIDEBAR_ICON_COUNT_MODE, settings.mode)
            .putInt(KEY_SIDEBAR_ICON_COUNT_CUSTOM, settings.custom).apply()
    }
}

internal fun completeSidebarIconCountPreset(values: JSONObject) {
    mapOf(KEY_SIDEBAR_ICON_COUNT_MODE to 0, KEY_SIDEBAR_ICON_COUNT_CUSTOM to 16).forEach { (key, default) ->
        if (!values.has(key)) values.put(key, JSONObject().put("type", "int").put("value", default))
    }
}

/** Only the target's private count comparisons consume this translated count. */
internal fun sidebarNativeComparisonCount(actual: Int, limit: Int): Int = (actual + 16 - limit).coerceAtLeast(0)
