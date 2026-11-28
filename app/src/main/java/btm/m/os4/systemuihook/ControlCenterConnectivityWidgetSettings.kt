// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET =
    "control_center_connectivity_tile_corner_radius_offset"
internal const val CC_CONNECTIVITY_CORNER_RADIUS_MIN = -64
internal const val CC_CONNECTIVITY_CORNER_RADIUS_MAX = 96
internal const val CC_CONNECTIVITY_CORNER_RADIUS_DEFAULT = 0

internal data class ControlCenterConnectivityWidgetSettings(
    val tileCornerRadiusOffsetDp: Int = CC_CONNECTIVITY_CORNER_RADIUS_DEFAULT,
)

internal fun readControlCenterConnectivityWidgetSettings(
    preferences: SharedPreferences,
): ControlCenterConnectivityWidgetSettings = ControlCenterConnectivityWidgetSettings(
    tileCornerRadiusOffsetDp = preferences.getInt(
        KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET,
        CC_CONNECTIVITY_CORNER_RADIUS_DEFAULT,
    ).coerceIn(CC_CONNECTIVITY_CORNER_RADIUS_MIN, CC_CONNECTIVITY_CORNER_RADIUS_MAX),
)

internal fun completeControlCenterConnectivityWidgetPreset(values: JSONObject) {
    if (!values.has(KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET)) {
        values.put(
            KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET,
            JSONObject().put("type", "int").put("value", CC_CONNECTIVITY_CORNER_RADIUS_DEFAULT),
        )
    }
}

internal class ControlCenterConnectivityWidgetSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings: ControlCenterConnectivityWidgetSettings = readControlCenterConnectivityWidgetSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterConnectivityWidgetSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(
        service: XposedService?,
        transform: (ControlCenterConnectivityWidgetSettings) -> ControlCenterConnectivityWidgetSettings,
    ) {
        val next = transform(settings).let {
            it.copy(
                tileCornerRadiusOffsetDp = it.tileCornerRadiusOffsetDp.coerceIn(
                    CC_CONNECTIVITY_CORNER_RADIUS_MIN,
                    CC_CONNECTIVITY_CORNER_RADIUS_MAX,
                ),
            )
        }
        settings = next
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putInt(KEY_CC_CONNECTIVITY_TILE_CORNER_RADIUS_OFFSET, settings.tileCornerRadiusOffsetDp)
            .apply()
    }
}
