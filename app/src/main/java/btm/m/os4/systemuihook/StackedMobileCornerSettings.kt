// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val KEY_STACKED_MOBILE_CORNER_ENABLED = "stacked_mobile_corner_enabled"
internal const val KEY_STACKED_MOBILE_CORNER_RADIUS = "stacked_mobile_corner_radius_dp"
internal const val STACKED_MOBILE_CORNER_DEFAULT_RADIUS = 0.5f
internal val STACKED_MOBILE_CORNER_RADIUS_RANGE = 0f..4f

internal data class StackedMobileCornerSettings(
    val enabled: Boolean = false,
    val radiusDp: Float = STACKED_MOBILE_CORNER_DEFAULT_RADIUS,
)

private fun normalizeStackedMobileCornerRadius(value: Float): Float =
    if (value.isFinite()) {
        (value.coerceIn(STACKED_MOBILE_CORNER_RADIUS_RANGE) * 100f).roundToInt() / 100f
    } else STACKED_MOBILE_CORNER_DEFAULT_RADIUS

internal fun readStackedMobileCornerSettings(preferences: SharedPreferences) = StackedMobileCornerSettings(
    enabled = preferences.getBoolean(KEY_STACKED_MOBILE_CORNER_ENABLED, false),
    radiusDp = normalizeStackedMobileCornerRadius(
        preferences.getFloat(KEY_STACKED_MOBILE_CORNER_RADIUS, STACKED_MOBILE_CORNER_DEFAULT_RADIUS),
    ),
)

internal class StackedMobileCornerSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readStackedMobileCornerSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readStackedMobileCornerSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun update(service: XposedService?, transform: (StackedMobileCornerSettings) -> StackedMobileCornerSettings) {
        settings = transform(readStackedMobileCornerSettings(local)).let {
            it.copy(radiusDp = normalizeStackedMobileCornerRadius(it.radiusDp))
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_STACKED_MOBILE_CORNER_ENABLED, settings.enabled)
            .putFloat(KEY_STACKED_MOBILE_CORNER_RADIUS, settings.radiusDp)
            .apply()
    }
}

/** Include defaults on export and normalize imported values, including presets predating these keys. */
internal fun completeStackedMobileCornerPreset(values: JSONObject) {
    val enabled = values.optJSONObject(KEY_STACKED_MOBILE_CORNER_ENABLED)?.getBoolean("value") ?: false
    val radius = values.optJSONObject(KEY_STACKED_MOBILE_CORNER_RADIUS)?.getDouble("value")?.toFloat()
        ?: STACKED_MOBILE_CORNER_DEFAULT_RADIUS
    values.put(KEY_STACKED_MOBILE_CORNER_ENABLED, JSONObject().put("type", "boolean").put("value", enabled))
    values.put(
        KEY_STACKED_MOBILE_CORNER_RADIUS,
        JSONObject().put("type", "float").put("value", normalizeStackedMobileCornerRadius(radius)),
    )
}
