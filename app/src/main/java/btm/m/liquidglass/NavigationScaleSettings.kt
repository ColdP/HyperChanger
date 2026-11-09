package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.roundToInt

/** Per-app floating navigation dimensions; integer hundredths preserve 0.01% precision. */
object NavigationScaleSettings {
    const val MIN = -10000
    const val MAX = 10000
    const val KEY_WIDTH_ENABLED = "navigation_width_scale_enabled"
    const val KEY_WIDTH = "navigation_width_scale_hundredths"
    const val KEY_HEIGHT_ENABLED = "navigation_height_scale_enabled"
    const val KEY_HEIGHT = "navigation_height_scale_hundredths"
    private val flags = setOf(KEY_WIDTH_ENABLED, KEY_HEIGHT_ENABLED)
    private val values = setOf(KEY_WIDTH, KEY_HEIGHT)

    data class Settings(
        val widthEnabled: Boolean = false,
        val widthHundredths: Int = 0,
        val heightEnabled: Boolean = false,
        val heightHundredths: Int = 0,
    ) {
        val widthFactor: Float get() = factor(widthEnabled, widthHundredths)
        val heightFactor: Float get() = factor(heightEnabled, heightHundredths)
        fun normalized() = copy(widthHundredths = widthHundredths.coerceIn(MIN, MAX),
            heightHundredths = heightHundredths.coerceIn(MIN, MAX))
    }

    fun supports(style: NavigationStyle): Boolean = style == NavigationStyle.LIQUID_GLASS ||
        style == NavigationStyle.HYPER_OS_FLOATING || style == NavigationStyle.HARMONY_OS_FLOATING || style == NavigationStyle.HYPER_OS_FLOATING
    fun key(packageName: String, name: String): String = ScopedSettings.key(packageName, name)
    fun isPreference(key: String): Boolean = key.startsWith("scope.") &&
        key.substringAfterLast('.') in flags + values
    fun hundredths(percent: Float): Int = if (percent.isFinite())
        (percent.coerceIn(-100f, 100f) * 100f).roundToInt().coerceIn(MIN, MAX) else 0
    private fun factor(enabled: Boolean, value: Int): Float = if (enabled) 1f + value.coerceIn(MIN, MAX) / 10000f else 1f

    fun load(p: SharedPreferences?, packageName: String): Settings = Settings(
        p?.getBoolean(key(packageName, KEY_WIDTH_ENABLED), false) ?: false,
        p?.getInt(key(packageName, KEY_WIDTH), 0) ?: 0,
        p?.getBoolean(key(packageName, KEY_HEIGHT_ENABLED), false) ?: false,
        p?.getInt(key(packageName, KEY_HEIGHT), 0) ?: 0,
    ).normalized()

    /** Docked navigation bypasses preference reads as well as all dimension overrides. */
    fun effective(p: SharedPreferences?, packageName: String, style: NavigationStyle): Settings =
        if (supports(style)) load(p, packageName) else Settings()

    fun save(p: SharedPreferences, service: XposedService?, packageName: String, settings: Settings) {
        val value = settings.normalized()
        fun write(target: SharedPreferences) = target.edit()
            .putBoolean(key(packageName, KEY_WIDTH_ENABLED), value.widthEnabled)
            .putInt(key(packageName, KEY_WIDTH), value.widthHundredths)
            .putBoolean(key(packageName, KEY_HEIGHT_ENABLED), value.heightEnabled)
            .putInt(key(packageName, KEY_HEIGHT), value.heightHundredths).apply()
        write(p)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val merged = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            merged.forEach { (key, value) ->
                val name = key.substringAfterLast('.')
                if (name in flags && value is Boolean) putBoolean(key, value)
                if (name in values && value is Int) putInt(key, value.coerceIn(MIN, MAX))
            }
        }.apply()
    }

    fun completePreset(json: JSONObject) {
        for (app in ScopedSettings.apps) for (name in flags + values) {
            val key = key(app.packageName, name)
            if (!json.has(key)) json.put(key, JSONObject().put("type", if (name in flags) "boolean" else "int")
                .put("value", if (name in flags) false else 0))
        }
    }
}
