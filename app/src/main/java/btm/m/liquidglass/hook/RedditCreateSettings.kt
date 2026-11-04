package btm.m.liquidglass.hook

import android.content.SharedPreferences
import btm.m.liquidglass.ScopedSettings
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import kotlin.math.pow

/** All Reddit create-button preferences and synchronization live outside HookSettings. */
object RedditCreateSettings {
    const val PACKAGE = "com.reddit.frontpage"
    const val KEY_SEPARATE = "separate_create_button"
    const val KEY_BACKGROUND = "create_button_background"
    const val KEY_ICON = "create_button_icon_color"
    const val KEY_BACKGROUND_MODE = "create_button_background_mode"
    const val DEFAULT_BACKGROUND = 0xFFFF4500.toInt()
    val iconModes = listOf("auto", "light", "dark")
    val backgroundModes = listOf("default", "custom")
    data class Settings(
        val separate: Boolean = false,
        val background: Int = DEFAULT_BACKGROUND,
        val iconMode: String = "auto",
        val backgroundMode: String = "default",
    ) {
        fun iconColor(): Int = when (iconMode) {
            "light" -> 0xFFFFFFFF.toInt()
            "dark" -> 0xFF000000.toInt()
            else -> {
                fun linear(shift: Int): Double {
                    val value = ((background ushr shift) and 255) / 255.0
                    return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                }
                val luminance = .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
                if (luminance > .179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
    }
    fun key(name: String): String = ScopedSettings.key(PACKAGE, name)
    fun isPreference(key: String): Boolean = key in setOf(key(KEY_SEPARATE), key(KEY_BACKGROUND), key(KEY_ICON), key(KEY_BACKGROUND_MODE))
    fun load(preferences: SharedPreferences?): Settings = Settings(
        preferences?.getBoolean(key(KEY_SEPARATE), false) ?: false,
        (preferences?.getInt(key(KEY_BACKGROUND), DEFAULT_BACKGROUND) ?: DEFAULT_BACKGROUND) or 0xFF000000.toInt(),
        preferences?.getString(key(KEY_ICON), "auto")?.takeIf { it in iconModes } ?: "auto",
        preferences?.getString(key(KEY_BACKGROUND_MODE), "default")?.takeIf { it in backgroundModes } ?: "default",
    )
    fun save(local: SharedPreferences, service: XposedService?, value: Settings) {
        fun write(target: SharedPreferences) {
            target.edit().putBoolean(key(KEY_SEPARATE), value.separate)
                .putInt(key(KEY_BACKGROUND), value.background or 0xFF000000.toInt())
                .putString(key(KEY_ICON), value.iconMode.takeIf { it in iconModes } ?: "auto")
                .putString(key(KEY_BACKGROUND_MODE), value.backgroundMode.takeIf { it in backgroundModes } ?: "default").apply()
        }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val merged = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            merged.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value or 0xFF000000.toInt())
                is String -> if (key == key(KEY_BACKGROUND_MODE)) {
                    putString(key, value.takeIf { it in backgroundModes } ?: "default")
                } else putString(key, value.takeIf { it in iconModes } ?: "auto")
            } }
        }.apply()
    }
    fun completePreset(values: JSONObject) {
        for ((name, type, value) in listOf(
            Triple(KEY_SEPARATE, "boolean", false), Triple(KEY_BACKGROUND, "int", DEFAULT_BACKGROUND),
            Triple(KEY_ICON, "string", "auto"),
            Triple(KEY_BACKGROUND_MODE, "string", "default"),
        )) if (!values.has(key(name))) values.put(key(name), JSONObject().put("type", type).put("value", value))
    }
}
