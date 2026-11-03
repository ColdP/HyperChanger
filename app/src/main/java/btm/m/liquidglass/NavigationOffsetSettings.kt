package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Global offset and opt-in per-app overrides, stored outside HookSettings. Positive values move up. */
object NavigationOffsetSettings {
    const val MIN = -128
    const val MAX = 256
    const val DEFAULT = 0
    const val KEY_ENABLED = "navigation_vertical_offset_enabled"
    const val KEY_VALUE = "navigation_vertical_offset_dp"
    const val GLOBAL_ENABLED = "app_navigation_vertical_offset_enabled"
    const val GLOBAL_VALUE = "app_navigation_vertical_offset_dp"

    data class Settings(val enabled: Boolean = false, val valueDp: Int = DEFAULT)

    fun enabledKey(packageName: String?): String =
        if (packageName == null) GLOBAL_ENABLED else ScopedSettings.key(packageName, KEY_ENABLED)

    fun valueKey(packageName: String?): String =
        if (packageName == null) GLOBAL_VALUE else ScopedSettings.key(packageName, KEY_VALUE)

    fun isGlobalPreference(key: String): Boolean = key == GLOBAL_ENABLED || key == GLOBAL_VALUE

    fun isPreference(key: String): Boolean = isGlobalPreference(key) ||
        key.startsWith("scope.") && key.substringAfterLast('.') in setOf(KEY_ENABLED, KEY_VALUE)

    fun load(preferences: SharedPreferences?, packageName: String? = null): Settings = Settings(
        enabled = preferences?.getBoolean(enabledKey(packageName), false) ?: false,
        valueDp = (preferences?.getInt(valueKey(packageName), DEFAULT) ?: DEFAULT).coerceIn(MIN, MAX),
    )

    fun effectiveOffset(preferences: SharedPreferences?, packageName: String): Int {
        val app = load(preferences, packageName)
        if (app.enabled) return app.valueDp
        val global = load(preferences)
        return if (global.enabled) global.valueDp else DEFAULT
    }

    fun save(preferences: SharedPreferences, service: XposedService?, packageName: String?, settings: Settings) {
        fun write(target: SharedPreferences) {
            target.edit().putBoolean(enabledKey(packageName), settings.enabled)
                .putInt(valueKey(packageName), settings.valueDp.coerceIn(MIN, MAX)).apply()
        }
        write(preferences)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    /** Preserve offline local edits; recover keys from remote-only installations. */
    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val merged = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) {
            target.edit().apply {
                merged.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value.coerceIn(MIN, MAX))
                    }
                }
            }.apply()
        }
    }

    /** Export absent defaults too, so importing a default preset clears prior overrides. */
    fun completePreset(values: JSONObject) {
        for (packageName in listOf<String?>(null) + ScopedSettings.apps.map { it.packageName }) {
            val enabled = enabledKey(packageName)
            val value = valueKey(packageName)
            if (!values.has(enabled)) values.put(enabled, JSONObject().put("type", "boolean").put("value", false))
            if (!values.has(value)) values.put(value, JSONObject().put("type", "int").put("value", DEFAULT))
        }
    }
}
