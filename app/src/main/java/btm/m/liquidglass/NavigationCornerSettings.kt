package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Per-app floating navigation corner preferences, independent of HookSettings. */
object NavigationCornerSettings {
    const val MIN = -96
    const val MAX = 0
    const val KEY_ENABLED = "navigation_corner_adjustment_enabled"
    const val KEY_VALUE = "navigation_corner_adjustment"
    data class Settings(val enabled: Boolean = false, val value: Int = 0) {
        fun normalized() = copy(value = value.coerceIn(MIN, MAX))
        val radiusFactor: Float get() = if (enabled) 1f + value.coerceIn(MIN, MAX) / 96f else 1f
    }
    fun supports(style: NavigationStyle): Boolean = NavigationScaleSettings.supports(style)
    fun key(packageName: String, name: String): String = ScopedSettings.key(packageName, name)
    fun isPreference(key: String): Boolean = key.startsWith("scope.") &&
        key.substringAfterLast('.') in setOf(KEY_ENABLED, KEY_VALUE)
    fun load(p: SharedPreferences?, packageName: String): Settings = Settings(
        p?.getBoolean(key(packageName, KEY_ENABLED), false) ?: false,
        p?.getInt(key(packageName, KEY_VALUE), 0) ?: 0,
    ).normalized()
    fun effective(p: SharedPreferences?, packageName: String, style: NavigationStyle): Settings =
        if (supports(style)) load(p, packageName) else Settings()
    fun save(p: SharedPreferences, service: XposedService?, packageName: String, settings: Settings) {
        val value = settings.normalized()
        fun write(target: SharedPreferences) = target.edit()
            .putBoolean(key(packageName, KEY_ENABLED), value.enabled)
            .putInt(key(packageName, KEY_VALUE), value.value).apply()
        write(p)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val merged = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            merged.forEach { (key, value) -> when {
                key.substringAfterLast('.') == KEY_ENABLED && value is Boolean -> putBoolean(key, value)
                key.substringAfterLast('.') == KEY_VALUE && value is Int -> putInt(key, value.coerceIn(MIN, MAX))
            } }
        }.apply()
    }
    fun completePreset(json: JSONObject) {
        for (app in ScopedSettings.apps) {
            val enabled = key(app.packageName, KEY_ENABLED)
            val value = key(app.packageName, KEY_VALUE)
            if (!json.has(enabled)) json.put(enabled, JSONObject().put("type", "boolean").put("value", false))
            if (!json.has(value)) json.put(value, JSONObject().put("type", "int").put("value", 0))
        }
    }
}
