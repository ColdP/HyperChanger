// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.newnotificationcenter

import android.content.Context
import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import btm.m.os4.systemuihook.SoftGlassSettings
import btm.m.os4.systemuihook.softGlassParameters
import io.github.libxposed.service.XposedService

const val KEY_IOS_NOTIFICATION_HEADER_GAP = "ios_notification_header_gap_dp"
private const val CLOCK_PREFIX = "ios_notification_clock_glass_"

internal data class IosNotificationClockSettings(
    val enabled: Boolean = false,
    val useGlobal: Boolean = true,
    val glass: SoftGlassSettings = SoftGlassSettings(),
)

internal fun readIosNotificationClockSettings(p: SharedPreferences) = IosNotificationClockSettings(
    enabled = p.getBoolean("${CLOCK_PREFIX}enabled", false),
    useGlobal = p.getBoolean("${CLOCK_PREFIX}use_global", true),
    glass = SoftGlassSettings(
        enabled = true,
        refractionBlur = p.getInt("${CLOCK_PREFIX}refraction_blur", 100),
        backgroundBlur = p.getInt("${CLOCK_PREFIX}background_blur", 100),
        backgroundScale = p.getInt("${CLOCK_PREFIX}background_scale", 100),
        backgroundOpacity = p.getInt("${CLOCK_PREFIX}background_opacity", 100),
        adjustments = softGlassParameters.associate { definition ->
            val key = if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key
            definition.key to p.getInt("$CLOCK_PREFIX$key", 0)
        },
    ).normalized(),
)

internal class IosNotificationLayoutSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var headerGapDp = local.getInt(KEY_IOS_NOTIFICATION_HEADER_GAP, 0).coerceIn(-40, 64)
        private set
    var clock = readIosNotificationClockSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    fun updateHeaderGap(service: XposedService?, value: Int) {
        headerGapDp = value.coerceIn(-40, 64)
        val edit: (SharedPreferences) -> Unit = { it.edit().putInt(KEY_IOS_NOTIFICATION_HEADER_GAP, headerGapDp).apply() }
        edit(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(edit)
    }

    fun updateClock(service: XposedService?, transform: (IosNotificationClockSettings) -> IosNotificationClockSettings) {
        val updated = transform(readIosNotificationClockSettings(local))
        clock = updated.copy(glass = updated.glass.normalized())
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        val value = clock
        p.edit().apply {
            putInt(KEY_IOS_NOTIFICATION_HEADER_GAP, headerGapDp)
            putBoolean("${CLOCK_PREFIX}enabled", value.enabled)
            putBoolean("${CLOCK_PREFIX}use_global", value.useGlobal)
            putInt("${CLOCK_PREFIX}refraction_blur", value.glass.refractionBlur)
            putInt("${CLOCK_PREFIX}background_blur", value.glass.backgroundBlur)
            putInt("${CLOCK_PREFIX}background_scale", value.glass.backgroundScale)
            putInt("${CLOCK_PREFIX}background_opacity", value.glass.backgroundOpacity)
            softGlassParameters.forEach { definition ->
                putInt("$CLOCK_PREFIX${if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key}", value.glass.adjustments[definition.key] ?: 0)
            }
        }.apply()
    }
}

internal class IosNotificationClockGlassRuntime(
    private val preferences: SharedPreferences,
    onChanged: () -> Unit,
) {
    @Volatile var settings = readIosNotificationClockSettings(preferences)
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_IOS_NOTIFICATION_HEADER_GAP || key.startsWith(CLOCK_PREFIX)) {
            settings = readIosNotificationClockSettings(preferences)
            onChanged()
        }
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
}
