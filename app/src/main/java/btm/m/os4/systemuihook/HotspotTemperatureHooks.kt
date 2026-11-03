// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

internal class HotspotTemperatureHooks(private val module: XposedModule) {
    private var installed = false
    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed || !preferences.getBoolean(KEY_REMOVE_HOTSPOT_TEMP_LIMIT, false)) return
        runCatching {
            val method = param.defaultClassLoader.loadClass("com.android.settings.MiuiTetherSettings")
                .getDeclaredMethod("isHighTemperatureProtect")
            check(method.returnType == java.lang.Boolean.TYPE)
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings:hotspot-temp-limit")
                .intercept { false }
            installed = true
            HookModuleLogger.log(module, ModuleLog.INFO, "HotspotTemperature", "Installed hotspot temperature hook")
        }.onFailure { HookModuleLogger.log(module, ModuleLog.WARN, "HotspotTemperature", "Could not install hotspot temperature hook", it) }
    }
}
