package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import io.github.libxposed.service.XposedService

internal const val KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE = "lockscreen_clock_horizontal_move"
internal const val KEY_LOCKSCREEN_CLOCK_HORIZONTAL_ENABLED_SYSTEM = "hyperchanger_lockscreen_clock_horizontal_enabled"
internal const val KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET = "hyperchanger_lockscreen_clock_horizontal_offset_v2"

class LockscreenClockHorizontalSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var enabled: Boolean = readLockscreenClockHorizontalEnabledSystemOrNull(context)
        ?: local.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE)) {
            enabled = remote.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)
        }
        val sys = readLockscreenClockHorizontalEnabledSystemOrNull(context)
        if (sys != null) {
            enabled = sys
        }
        local.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, enabled).apply()
        remote.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, enabled).apply()
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        local.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, value)?.apply()
        writeLockscreenClockHorizontalEnabledSystem(context, value)
        if (!value) {
            writeLockscreenClockHorizontalOffset(context, 0f)
        }
    }
}

internal fun readLockscreenClockHorizontalEnabledSystemOrNull(context: Context): Boolean? = runCatching {
    when (Settings.System.getInt(context.contentResolver, KEY_LOCKSCREEN_CLOCK_HORIZONTAL_ENABLED_SYSTEM, -1)) {
        0 -> false
        1 -> true
        else -> null
    }
}.getOrNull()

internal fun readLockscreenClockHorizontalEnabled(
    context: Context?,
    preferences: SharedPreferences?,
): Boolean {
    val sys = context?.let(::readLockscreenClockHorizontalEnabledSystemOrNull)
    if (sys != null) return sys
    return preferences?.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false) ?: false
}

internal fun writeLockscreenClockHorizontalEnabledSystem(context: Context, enabled: Boolean): Boolean {
    val direct = runCatching {
        Settings.System.putInt(
            context.contentResolver,
            KEY_LOCKSCREEN_CLOCK_HORIZONTAL_ENABLED_SYSTEM,
            if (enabled) 1 else 0,
        )
    }.getOrDefault(false)
    if (direct) return true
    return runCatching {
        val value = if (enabled) "1" else "0"
        ModuleRoot.run("settings put system $KEY_LOCKSCREEN_CLOCK_HORIZONTAL_ENABLED_SYSTEM $value", 4096).code == 0
    }.getOrDefault(false)
}

internal fun readLockscreenClockHorizontalOffset(context: Context): Float = runCatching {
    Settings.System.getFloat(context.contentResolver, KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET, 0f)
        .coerceIn(-0.45f, 0.45f)
}.getOrDefault(0f)

internal fun writeLockscreenClockHorizontalOffset(context: Context, value: Float): Boolean = runCatching {
    Settings.System.putFloat(
        context.contentResolver,
        KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET,
        value.coerceIn(-0.45f, 0.45f),
    )
}.getOrDefault(false)
