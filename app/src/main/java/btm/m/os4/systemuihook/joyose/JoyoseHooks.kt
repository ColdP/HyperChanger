// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.joyose

import android.content.SharedPreferences
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog as Log
import btm.m.os4.systemuihook.cloudcontrol.KEY_JOYOSE_DISABLE_CLOUD_CONTROL
import btm.m.os4.systemuihook.cloudcontrol.isCloudControlEnabled
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * Ports HyperCeiler's two Joyose switches. Joyose obfuscates both entry points, so they are
 * located through DexKit string markers plus the original return type instead of class or member
 * names, and an ambiguous lookup installs nothing, exactly like the original single-candidate rule.
 * The cloud control switch additionally requires the shared cloud control master switch, matching
 * how environment detection gates its per-application switches.
 */
internal class JoyoseHooks(private val module: XposedModule) {
    private var installed = false

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed) return
        val disableCloudControl = preferences.isCloudControlEnabled() &&
            preferences.getBoolean(KEY_JOYOSE_DISABLE_CLOUD_CONTROL, false)
        val enableGpuTuner = preferences.getBoolean(KEY_JOYOSE_ENABLE_GPU_TUNER, false)
        if (!disableCloudControl && !enableGpuTuner) return
        installed = true
        if (disableCloudControl) installCloudControl(param)
        if (enableGpuTuner) installGpuTuner(param)
    }

    private fun installCloudControl(param: PackageLoadedParam) {
        runCatching {
            val method = findMarkedMethod(param, CLOUD_CONTROL_MARKER, java.lang.Void.TYPE, "cloud control")
                ?: return
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:joyose:disable-cloud-control")
                .intercept { null }
            HookModuleLogger.log(module, Log.INFO, TAG, "Installed Joyose cloud control hook")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not install Joyose cloud control hook", it)
        }
    }

    private fun installGpuTuner(param: PackageLoadedParam) {
        runCatching {
            val method = findMarkedMethod(param, GPU_TUNER_MARKER, java.lang.Boolean.TYPE, "GPU tuner")
                ?: return
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:joyose:enable-gpu-tuner")
                .intercept { true }
            HookModuleLogger.log(module, Log.INFO, TAG, "Installed Joyose GPU tuner hook")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not install Joyose GPU tuner hook", it)
        }
    }

    private fun findMarkedMethod(
        param: PackageLoadedParam,
        marker: String,
        returnType: Class<*>,
        label: String,
    ): Method? {
        System.loadLibrary("dexkit")
        val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
        val method = try {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(marker).returnType(returnType),
                ),
            ).singleOrNull()?.getMethodInstance(param.defaultClassLoader)
        } finally {
            bridge.close()
        }
        if (method == null) {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not resolve Joyose $label method")
        }
        return method
    }

    private companion object {
        const val TAG = "HyperChangerJoyose"
        const val CLOUD_CONTROL_MARKER = "job exist, sync local..."
        const val GPU_TUNER_MARKER = "GPUTUNER_SWITCH"
    }
}
