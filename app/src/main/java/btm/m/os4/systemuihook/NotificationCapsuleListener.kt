package btm.m.os4.systemuihook

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

internal object NotificationCapsuleStore {
    const val PREFS = "notification_capsule"
    const val PACKAGE = "package"
    const val TITLE = "title"
    const val CONTENT = "content"
    const val TIME = "time"
}

class NotificationCapsuleListener : NotificationListenerService() {
    override fun onListenerConnected() {
        getActiveNotifications()?.maxByOrNull { it.postTime }?.let(::save)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        save(sbn)
    }

    private fun save(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val content = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && content.isBlank()) return
        getSharedPreferences(NotificationCapsuleStore.PREFS, MODE_PRIVATE)
            .edit()
            .putString(NotificationCapsuleStore.PACKAGE, sbn.packageName)
            .putString(NotificationCapsuleStore.TITLE, title)
            .putString(NotificationCapsuleStore.CONTENT, content)
            .putLong(NotificationCapsuleStore.TIME, sbn.postTime)
            .apply()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep the latest notification until another notification replaces it.
    }
}
