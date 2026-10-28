// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.trustservice

import btm.m.os4.systemuihook.HookModuleLogger

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Shared preference key used by the UI and the trust-service process callback. */
internal const val KEY_TRUSTSERVICE_DIAGNOSTICS = "trustservice_mrm_diagnostics"

/**
 * Diagnostic-only port of HyperCeiler's target discovery.
 * It reports whether the expected method can be found, but deliberately does not modify its result.
 */
internal object TrustServiceDiagnostics {
    private const val PACKAGE = "com.xiaomi.trustservice"
    private const val TAG = "HyperChangerTrustService"

    fun inspect(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != PACKAGE) return

        runCatching {
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            try {
                val method = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create()
                            .usingStrings(
                                "MiTrustService/statusEventHandle",
                                "try init mrmd Service",
                            )
                            .returnType(Boolean::class.javaPrimitiveType!!),
                    ),
                ).singleOrNull()?.getMethodInstance(param.defaultClassLoader)

                if (method == null) {
                    HookModuleLogger.log(
                        module,
                        android.util.Log.WARN,
                        TAG,
                        "Target method was not found uniquely; no hook was installed.",
                    )
                } else {
                    HookModuleLogger.log(
                        module,
                        android.util.Log.INFO,
                        TAG,
                        "Target method located for diagnostics: ${method.toGenericString()}",
                    )
                }
            } finally {
                bridge.close()
            }
        }.onFailure { error ->
            HookModuleLogger.log(
                module,
                android.util.Log.WARN,
                TAG,
                "DexKit target discovery failed; no hook was installed.",
                error,
            )
        }
    }
}
