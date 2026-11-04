package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Experimental docked material and its optional per-app accent, separate from HookSettings. */
object HyperDockedNavigationSettings {
    const val KEY_GLASS = "hyper_docked_liquid_glass_enabled"
    const val KEY_ACCENT = "hyper_docked_accent_enabled"

    fun isPreference(key: String): Boolean = key == KEY_GLASS ||
        (key.startsWith("scope.") && key.endsWith(".$KEY_ACCENT"))
    fun glassEnabled(p: SharedPreferences?): Boolean = p?.getBoolean(KEY_GLASS, false) ?: false
    fun accentEnabled(p: SharedPreferences?, packageName: String): Boolean =
        ScopedSettings.getBoolean(p, packageName, KEY_ACCENT, false)
    fun saveGlass(p: SharedPreferences, service: XposedService?, enabled: Boolean) = save(p, service, KEY_GLASS, enabled)
    fun saveAccent(p: SharedPreferences, service: XposedService?, packageName: String, enabled: Boolean) =
        save(p, service, ScopedSettings.key(packageName, KEY_ACCENT), enabled)
    private fun save(p: SharedPreferences, service: XposedService?, key: String, enabled: Boolean) {
        p.edit().putBoolean(key, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(key, enabled)?.apply()
    }
    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val values = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            values.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) }
        }.apply()
    }
    fun completePreset(values: JSONObject) {
        for (key in listOf(KEY_GLASS) + ScopedSettings.apps.map { ScopedSettings.key(it.packageName, KEY_ACCENT) }) {
            if (!values.has(key)) values.put(key, JSONObject().put("type", "boolean").put("value", false))
        }
    }
}
