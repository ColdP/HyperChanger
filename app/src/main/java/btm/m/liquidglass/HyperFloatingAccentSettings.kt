package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Optional per-app tint for HyperOS floating navigation; colors use NavigationAccentSettings. */
object HyperFloatingAccentSettings {
    const val KEY = "hyper_floating_accent_enabled"
    const val DEFAULT = false

    fun isPreference(key: String): Boolean = key.startsWith("scope.") && key.endsWith(".$KEY")

    fun isEnabled(preferences: SharedPreferences?, packageName: String): Boolean =
        ScopedSettings.getBoolean(preferences, packageName, KEY, DEFAULT)

    fun save(local: SharedPreferences, service: XposedService?, packageName: String, enabled: Boolean) {
        val key = ScopedSettings.key(packageName, KEY)
        local.edit().putBoolean(key, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(key, enabled)?.apply()
    }

    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val values = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            values.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) }
        }.apply()
    }

    fun completePreset(values: JSONObject) {
        ScopedSettings.apps.forEach { app ->
            val key = ScopedSettings.key(app.packageName, KEY)
            if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", DEFAULT))
        }
    }
}
