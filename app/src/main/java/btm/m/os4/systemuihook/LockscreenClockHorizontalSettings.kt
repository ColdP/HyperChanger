package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import io.github.libxposed.service.XposedService

internal const val KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE = "lockscreen_clock_horizontal_move"
internal const val KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET = "hyperchanger_lockscreen_clock_horizontal_offset_v2"

class LockscreenClockHorizontalSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var enabled: Boolean = local.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE)) {
            enabled = remote.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)
        }
        local.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, enabled).apply()
        remote.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, enabled).apply()
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        local.edit().putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, value)?.apply()
    }
}

internal fun readLockscreenClockHorizontalOffset(context: Context): Float =
    Settings.System.getFloat(context.contentResolver, KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET, 0f)
        .coerceIn(-0.45f, 0.45f)

internal fun writeLockscreenClockHorizontalOffset(context: Context, value: Float): Boolean =
    Settings.System.putFloat(
        context.contentResolver,
        KEY_LOCKSCREEN_CLOCK_HORIZONTAL_OFFSET,
        value.coerceIn(-0.45f, 0.45f),
    )
