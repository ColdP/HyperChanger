// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.trustservice

import android.util.Log
import btm.m.os4.systemuihook.HookModuleLogger
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * The preference key is intentionally kept stable so users who installed an earlier
 * diagnostic build do not lose their saved switch state.
 */
internal const val KEY_TRUSTSERVICE_DIAGNOSTICS = "trustservice_mrm_diagnostics"

/** Disables the MRMD initialization path in com.xiaomi.trustservice. */
internal object TrustServiceDiagnostics {
    private const val PACKAGE = "com.xiaomi.trustservice"
    private const val TAG = "HyperChangerTrustService"
    private const val HOOK_ID = "trustservice:disable-mrm"

    fun install(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != PACKAGE) return

        runCatching {
            System.loadLibrary("dexkit")
            val method = findTargetMethod(param)
            if (method == null) {
                HookModuleLogger.log(
                    module,
                    Log.WARN,
                    TAG,
                    "MRMD target method was not found uniquely; no hook was installed.",
                )
                return
            }

            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId(HOOK_ID)
                .intercept {
                    // Match HyperCeiler's before-hook behavior: skip the original method
                    // and force its boolean result to false.
                    false
                }

            HookModuleLogger.log(
                module,
                Log.INFO,
                TAG,
                "Installed MRMD-disable hook on ${method.toGenericString()}",
            )
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                Log.WARN,
                TAG,
                "Could not install MRMD-disable hook.",
                error,
            )
        }
    }

    private fun findTargetMethod(param: PackageLoadedParam): Method? {
        val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
        return try {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .usingStrings(
                            "MiTrustService/statusEventHandle",
                            "try init mrmd Service",
                        )
                        .returnType(Boolean::class.javaPrimitiveType!!),
                ),
            ).singleOrNull()?.getMethodInstance(param.defaultClassLoader)
        } finally {
            bridge.close()
        }
    }
}
