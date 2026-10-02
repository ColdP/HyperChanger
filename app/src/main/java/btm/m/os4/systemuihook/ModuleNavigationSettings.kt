package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import io.github.libxposed.service.XposedService

/** Module-app-only navigation preferences kept outside HookSettings. */
data class ModuleNavigationSettings(
    val style: String = NavigationStyle.HYPER_OS.preferenceValue,
    val labelMode: String = LabelMode.ICON_AND_TEXT.preferenceValue,
)

class ModuleNavigationSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ModuleNavigationSettings = local.read()
        private set

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

    private fun SharedPreferences.read() = ModuleNavigationSettings(
        style = NavigationStyle.fromPreference(getString(KEY_NAVIGATION_STYLE, NavigationStyle.HYPER_OS.preferenceValue)).preferenceValue,
        labelMode = LabelMode.fromPreference(getString(KEY_NAVIGATION_LABEL_MODE, LabelMode.ICON_AND_TEXT.preferenceValue)).preferenceValue,
    )

    private fun SharedPreferences.write(value: ModuleNavigationSettings) {
        edit()
            .putString(KEY_NAVIGATION_STYLE, value.style)
            .putString(KEY_NAVIGATION_LABEL_MODE, value.labelMode)
            .apply()
    }

    private fun ModuleNavigationSettings.normalized() = copy(
        style = NavigationStyle.fromPreference(style).preferenceValue,
        labelMode = LabelMode.fromPreference(labelMode).preferenceValue,
    )
}

private const val KEY_NAVIGATION_STYLE = "navigation_style"
private const val KEY_NAVIGATION_LABEL_MODE = "navigation_label_mode"
