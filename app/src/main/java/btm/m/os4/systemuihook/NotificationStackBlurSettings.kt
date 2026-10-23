// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_NOTIFICATION_STACK_BLUR = "notification_stack_depth_blur_enabled"
internal const val KEY_NOTIFICATION_STACK_BLUR_OFFSET = "notification_stack_depth_blur_offset"
internal const val NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MIN = -30
internal const val NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MAX = 40

internal data class NotificationStackBlurSettings(val enabled: Boolean = false, val offset: Int = 0)

internal fun readNotificationStackBlurSettings(preferences: SharedPreferences) = NotificationStackBlurSettings(
    enabled = notificationStackBlurEnabled(preferences),
    offset = preferences.getInt(KEY_NOTIFICATION_STACK_BLUR_OFFSET, 0)
        .coerceIn(NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MIN, NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MAX),
)

internal fun notificationStackBlurEnabled(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_NOTIFICATION_STACK_BLUR, false)

/** Stores the notification-stack option outside HookSettings and mirrors it to SystemUI. */
internal class NotificationStackBlurSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var enabled = notificationStackBlurEnabled(local); private set
    var offset = readNotificationStackBlurSettings(local).offset; private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val source = if (remote.contains(KEY_NOTIFICATION_STACK_BLUR) || remote.contains(KEY_NOTIFICATION_STACK_BLUR_OFFSET)) remote else local
        val settings = readNotificationStackBlurSettings(source)
        enabled = settings.enabled
        offset = settings.offset
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, value: Boolean) {
        enabled = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun updateOffset(service: XposedService?, value: Int) {
        offset = value.coerceIn(NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MIN, NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MAX)
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(preferences: SharedPreferences) {
        preferences.edit().putBoolean(KEY_NOTIFICATION_STACK_BLUR, enabled)
            .putInt(KEY_NOTIFICATION_STACK_BLUR_OFFSET, offset).apply()
    }
}

/** HyperLight-compatible progress curve, capped to keep the front card crisp. */
internal fun notificationStackBlurRadius(progress: Float, landscape: Boolean, offset: Int = 0): Float {
    val threshold = if (landscape) .45f else .2f
    if (!progress.isFinite() || progress < threshold) return 0f
    return ((((progress - threshold) * 10f) / (1f - threshold)) +
        offset.coerceIn(NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MIN, NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MAX))
        .coerceIn(0f, 70f)
}
