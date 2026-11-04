package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import btm.m.liquidglass.LabelMode
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Bottom bar styles that belong to the HyperChanger module application itself. */
enum class ModuleNavigationStyle(val preferenceValue: String, val displayName: String) {
    HYPER_OS_FLOATING("hyper_os_floating", "HyperOS 悬浮导航栏"),
    HYPER_OS("hyper_os", "HyperOS 底部导航栏"),
    HARMONY_OS_FLOATING("harmony_os_floating", "HarmonyOS 悬浮导航栏"),
    LIQUID_GLASS("liquid_glass", "液态玻璃底部导航栏");

    companion object {
        fun fromPreference(value: String?): ModuleNavigationStyle =
            if (value == "miuix_floating") HYPER_OS_FLOATING
            else entries.firstOrNull { it.preferenceValue == value } ?: HYPER_OS
    }
}

/** Module-app-only navigation preferences kept outside HookSettings. */
data class ModuleNavigationSettings(
    val style: String = ModuleNavigationStyle.HYPER_OS_FLOATING.preferenceValue,
    val labelMode: String = LabelMode.ICON_AND_TEXT.preferenceValue,
)

class ModuleNavigationSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ModuleNavigationSettings = local.read()
        private set

    fun reload() {
        settings = local.read()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val remoteSettings = remote.read()
        settings = if (remote.contains(KEY_NAVIGATION_STYLE) || remote.contains(KEY_NAVIGATION_LABEL_MODE)) {
            remoteSettings
        } else {
            settings
        }
        remote.write(settings)
        local.write(settings)
    }

    fun update(service: XposedService?, transform: (ModuleNavigationSettings) -> ModuleNavigationSettings) {
        settings = transform(settings).normalized()
        local.write(settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.write(settings)
    }

    private fun SharedPreferences.read(): ModuleNavigationSettings {
        val savedStyle = getString(KEY_NAVIGATION_STYLE, ModuleNavigationStyle.HYPER_OS_FLOATING.preferenceValue)
        val style = ModuleNavigationStyle.fromPreference(savedStyle).preferenceValue
        if (savedStyle == "miuix_floating") edit().putString(KEY_NAVIGATION_STYLE, style).apply()
        return ModuleNavigationSettings(
            style = style,
            labelMode = LabelMode.fromPreference(getString(KEY_NAVIGATION_LABEL_MODE, LabelMode.ICON_AND_TEXT.preferenceValue)).preferenceValue,
        )
    }

    private fun SharedPreferences.write(value: ModuleNavigationSettings) {
        edit()
            .putString(KEY_NAVIGATION_STYLE, value.style)
            .putString(KEY_NAVIGATION_LABEL_MODE, value.labelMode)
            .apply()
    }

    private fun ModuleNavigationSettings.normalized() = copy(
        style = ModuleNavigationStyle.fromPreference(style).preferenceValue,
        labelMode = LabelMode.fromPreference(labelMode).preferenceValue,
    )
}

private const val KEY_NAVIGATION_STYLE = "navigation_style"
private const val KEY_NAVIGATION_LABEL_MODE = "navigation_label_mode"

/** Keep module navigation styles valid in both JSON and ZIP preset paths. */
internal fun completeModuleNavigationPreset(values: JSONObject) {
    val style = values.optJSONObject(KEY_NAVIGATION_STYLE)?.getString("value")
        ?: ModuleNavigationStyle.HYPER_OS_FLOATING.preferenceValue
    val labelMode = values.optJSONObject(KEY_NAVIGATION_LABEL_MODE)?.getString("value")
        ?: LabelMode.ICON_AND_TEXT.preferenceValue
    values.put(KEY_NAVIGATION_STYLE, JSONObject().put("type", "string")
        .put("value", ModuleNavigationStyle.fromPreference(style).preferenceValue))
    values.put(KEY_NAVIGATION_LABEL_MODE, JSONObject().put("type", "string")
        .put("value", LabelMode.fromPreference(labelMode).preferenceValue))
}
