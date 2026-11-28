// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED = "lockscreen_clock_avoidance_enabled"
internal const val KEY_LOCKSCREEN_CLOCK_AVOIDANCE_SYSTEM = "hyperchanger_lockscreen_clock_avoidance_enabled"
internal const val KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID = "lockscreen_clock_custom_font_id"
internal const val KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION = "lockscreen_clock_custom_font_version"

internal data class LockscreenClockCustomSettings(
    val avoidanceEnabled: Boolean = false,
    val activeFontId: String = "",
    val fontVersion: Long = 0L,
)

internal fun readLockscreenClockCustomSettings(p: SharedPreferences): LockscreenClockCustomSettings {
    val rawId = p.getString(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID, "").orEmpty().trim()
    val validId = rawId.takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty()
    return LockscreenClockCustomSettings(
        avoidanceEnabled = p.getBoolean(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED, false),
        activeFontId = validId,
        fontVersion = p.getLong(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION, 0L).coerceAtLeast(0L),
    )
}

internal fun completeLockscreenClockCustomPreset(values: JSONObject) {
    if (!values.has(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED)) {
        values.put(
            KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED,
            JSONObject().put("type", "boolean").put("value", false),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID)) {
        values.put(
            KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID,
            JSONObject().put("type", "string").put("value", ""),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION)) {
        values.put(
            KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION,
            JSONObject().put("type", "long").put("value", 0L),
        )
    }
}

internal class LockscreenClockCustomSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: LockscreenClockCustomSettings = readLockscreenClockCustomSettings(local)
        private set

    fun reload() {
        settings = readLockscreenClockCustomSettings(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val hasLocal = local.contains(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED) ||
            local.contains(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID)
        settings = if (hasLocal) {
            readLockscreenClockCustomSettings(local)
        } else {
            readLockscreenClockCustomSettings(remote)
        }
        // Mirror live state from Settings.System if AOD editor toggled avoidance directly.
        val systemAvoid = readLockscreenClockAvoidanceSystemOrNull(context)
        if (systemAvoid != null && systemAvoid != settings.avoidanceEnabled) {
            settings = settings.copy(avoidanceEnabled = systemAvoid)
        }
        write(local, settings)
        write(remote, settings)
        LockscreenClockFontRemote.publish(context, service)
    }

    fun update(
        service: XposedService?,
        transform: (LockscreenClockCustomSettings) -> LockscreenClockCustomSettings,
    ) {
        val previous = settings
        val updated = transform(previous).normalized()
        settings = updated
        write(local, updated)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, updated) }
        if (previous.avoidanceEnabled != updated.avoidanceEnabled) {
            writeLockscreenClockAvoidanceSystem(context, updated.avoidanceEnabled)
        }
    }

    private fun write(p: SharedPreferences, value: LockscreenClockCustomSettings) {
        p.edit()
            .putBoolean(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED, value.avoidanceEnabled)
            .putString(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_ID, value.activeFontId)
            .putLong(KEY_LOCKSCREEN_CLOCK_CUSTOM_FONT_VERSION, value.fontVersion)
            .apply()
    }
}

private fun LockscreenClockCustomSettings.normalized(): LockscreenClockCustomSettings = copy(
    activeFontId = activeFontId.trim().takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty(),
    fontVersion = fontVersion.coerceAtLeast(0L),
)

internal fun readLockscreenClockAvoidanceSystemOrNull(context: Context): Boolean? = runCatching {
    val raw = Settings.System.getInt(context.contentResolver, KEY_LOCKSCREEN_CLOCK_AVOIDANCE_SYSTEM, -1)
    when (raw) {
        0 -> false
        1 -> true
        else -> null
    }
}.getOrNull()

internal fun readLockscreenClockAvoidanceEnabled(
    context: Context?,
    preferences: SharedPreferences?,
): Boolean {
    val sys = context?.let(::readLockscreenClockAvoidanceSystemOrNull)
    if (sys != null) return sys
    return preferences?.getBoolean(KEY_LOCKSCREEN_CLOCK_AVOIDANCE_ENABLED, false) ?: false
}

internal fun writeLockscreenClockAvoidanceSystem(context: Context, enabled: Boolean): Boolean {
    val direct = runCatching {
        Settings.System.putInt(
            context.contentResolver,
            KEY_LOCKSCREEN_CLOCK_AVOIDANCE_SYSTEM,
            if (enabled) 1 else 0,
        )
    }.getOrDefault(false)
    if (direct) return true
    return runCatching {
        val value = if (enabled) "1" else "0"
        ModuleRoot.run("settings put system $KEY_LOCKSCREEN_CLOCK_AVOIDANCE_SYSTEM $value", 4096).code == 0
    }.getOrDefault(false)
}
