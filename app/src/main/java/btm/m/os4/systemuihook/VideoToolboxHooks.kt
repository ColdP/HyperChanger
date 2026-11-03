// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Marker and replacement follow HyperCeiler RemoveVideoTempLimit (AGPL-3.0, HyperCeiler Contributions). */
internal class VideoToolboxHooks(private val module: XposedModule) {
    private var installed = false

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed || !preferences.getBoolean(KEY_REMOVE_VIDEO_TEMP_LIMIT, false)) return
        runCatching {
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            val method = try {
                bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString("read board temp fail, node=", StringMatchType.Equals)
                        .returnType(java.lang.Float.TYPE).paramCount(0),
                )).singleOrNull()?.getMethodInstance(param.defaultClassLoader)
            } finally { bridge.close() }
            if (method == null) {
                HookModuleLogger.log(module, ModuleLog.WARN, "VideoTemperature", "No unique video temperature reader found")
                return
            }
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:securitycenter:video-temp-limit")
                .intercept { 0.0f }
            installed = true
            HookModuleLogger.log(module, ModuleLog.INFO, "VideoTemperature", "Installed video temperature hook")
        }.onFailure { HookModuleLogger.log(module, ModuleLog.WARN, "VideoTemperature", "Could not install video temperature hook", it) }
    }
}
