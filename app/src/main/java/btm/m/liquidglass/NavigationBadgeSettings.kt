package btm.m.liquidglass

import android.content.SharedPreferences
import android.content.Context
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService

/** Per-app setting for preserving native unread dots on custom navigation tabs. */
object NavigationBadgeSettings {
    const val KEY_SHOW_BADGES = "show_navigation_badges"
    const val DEFAULT = true
    private val packages = listOf("com.tencent.mobileqq", "com.tencent.mm")

    @JvmStatic
    fun getShowBadges(preferences: SharedPreferences?, packageName: String): Boolean =
        packageName in packages && ScopedSettings.getBoolean(preferences, packageName, KEY_SHOW_BADGES, DEFAULT)

    @JvmStatic
    fun saveShowBadges(preferences: SharedPreferences, packageName: String, value: Boolean) {
        if (packageName !in packages) return
        preferences.edit().putBoolean(ScopedSettings.key(packageName, KEY_SHOW_BADGES), value).apply()
    }

    fun save(context: Context, preferences: SharedPreferences, packageName: String, value: Boolean) {
        saveShowBadges(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0), packageName, value)
        saveShowBadges(preferences, packageName, value)
    }

    fun syncRemote(context: Context, service: XposedService) {
        val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        packages.forEach { packageName ->
            val key = ScopedSettings.key(packageName, KEY_SHOW_BADGES)
            if (local.contains(key)) saveShowBadges(remote, packageName, getShowBadges(local, packageName))
            else if (remote.contains(key)) saveShowBadges(local, packageName, getShowBadges(remote, packageName))
        }
    }
}
