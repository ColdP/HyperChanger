// SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from HyperCeiler DisableRootedCheck.java and DisableRootCheck.kt.
// Copyright (C) 2023-2026 HyperCeiler Contributions
// HyperChanger adaptation: target package is com.miui.securitycenter
package btm.m.os4.systemuihook.securitycenter

import android.util.Log
import btm.m.os4.systemuihook.HookModuleLogger
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal const val KEY_SECURITYCENTER_ROOTED_DIAGNOSTICS = "securitycenter_rooted_check_diagnostics"
internal const val KEY_SECURITYCENTER_ROOT_DIAGNOSTICS = "securitycenter_root_check_diagnostics"

/** Strict Kotlin adaptation of HyperCeiler's two original Root-check hooks, scoped to com.miui.securitycenter */
internal object SecurityCenterRootDiagnostics {
    private const val PACKAGE = "com.miui.securitycenter"
    private const val TAG = "HyperChangerAOOptRootHook"

    private data class RootedMembers(
        val returnEnvironment: Method,
        val clientApiRequest: Class<*>,
        val environmentPut: Method,
        val riskAppClass: Class<*>,
        val riskAppField: Field,
        val unsetEnvironment: Method,
        val setEnvironmentMethods: List<Method>,
        val checkRootMethods: List<Method>,
    )

    fun inspectRootedCheck(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != PACKAGE) return
        runCatching {
            val appInfo = param.applicationInfo
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(appInfo.sourceDir)
            val members = try {
                findRootedMembers(bridge, param.defaultClassLoader)
            } finally {
                bridge.close()
            }

            HookModuleLogger.log(module, Log.INFO, TAG, "DisableRootedCheck: resolved original member set for ${param.packageName}")

            module.hook(members.returnEnvironment)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:a.oopt:DisableRootedCheck:return-environment")
                .intercept { chain ->
                    runCatching {
                        val request = chain.getArg(0)
                        if (request != null && members.clientApiRequest.isInstance(request)) {
                            members.environmentPut.isAccessible = true
                            members.environmentPut.invoke(request)
                            val owner = chain.thisObject
                            members.riskAppField.isAccessible = true
                            val riskApp = members.riskAppField.get(owner)
                            if (riskApp != null) {
                                members.setEnvironmentMethods
                                    .filter { it != members.unsetEnvironment }
                                    .forEach { method ->
                                        runCatching {
                                            method.isAccessible = true
                                            method.invoke(riskApp, request)
                                        }.onFailure { error ->
                                            HookModuleLogger.log(module, Log.WARN, TAG, "SetEnvironment call failed: ${method.toGenericString()}", error)
                                        }
                                    }
                            }
                        }
                    }.onFailure { error ->
                        HookModuleLogger.log(module, Log.WARN, TAG, "ReturnEnvironment pre-hook failed; proceeding with original method", error)
                    }
                    chain.proceed()
                }

            members.checkRootMethods.forEachIndexed { index, method ->
                HookModuleLogger.log(module, Log.INFO, TAG, "DisableRootedCheck: hooking CheckRoot method: $method")
                module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:a.oopt:DisableRootedCheck:check-root:$index:${method.name}")
                    .intercept {
                        false
                    }
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "DisableRootedCheck installed: ${members.checkRootMethods.size} CheckRoot method(s)")
        }.onFailure { error ->
            HookModuleLogger.log(module, Log.ERROR, TAG, "DisableRootedCheck initialization failed for ${param.packageName}", error)
        }
    }

    fun inspectRootCheck(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != PACKAGE) return
        runCatching {
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            val method = try {
                bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create()
                            .usingEqStrings("key_check_item_root")
                            .returnType(Boolean::class.javaPrimitiveType!!),
                    ),
                ).single().getMethodInstance(param.defaultClassLoader)
            } finally {
                bridge.close()
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "DisableRootCheck: resolved original method: ${method.toGenericString()}")
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:a.oopt:DisableRootCheck:${method.declaringClass.name}.${method.name}")
                .intercept {
                    false
                }
            HookModuleLogger.log(module, Log.INFO, TAG, "DisableRootCheck installed")
        }.onFailure { error ->
            HookModuleLogger.log(module, Log.ERROR, TAG, "DisableRootCheck initialization failed for ${param.packageName}", error)
        }
    }

    private fun findRootedMembers(bridge: DexKitBridge, loader: ClassLoader): RootedMembers {
        val returnEnvironment = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create().usingStrings("Invalid Task , reason:  [canceled, "),
            ),
        ).single().getMethodInstance(loader)

        val clientApiRequestData = bridge.findClass(
            FindClass.create().matcher(ClassMatcher.create().usingStrings("ClientApiRequest")),
        ).single()
        val clientApiRequestClass = clientApiRequestData.getInstance(loader)

        val environmentPut = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .declaredClass(clientApiRequestClass)
                    .returnType(Boolean::class.javaPrimitiveType!!),
            ),
        ).single().getMethodInstance(loader)

        val riskAppClass = bridge.findClass(
            FindClass.create().matcher(ClassMatcher.create().usingStrings("RiskAppManager")),
        ).single().getInstance(loader)

        val riskAppField = bridge.findField(
            FindField.create().matcher(
                FieldMatcher.create()
                    .type(riskAppClass)
                    .modifiers(Modifier.PRIVATE),
            ),
        ).single().getFieldInstance(loader)

        val unsetEnvironment = bridge.findMethod(
            FindMethod.create().matcher(MethodMatcher.create().usingStrings("RiskAppManager")),
        ).single().getMethodInstance(loader)

        val setEnvironmentMethods = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .returnType(Void.TYPE)
                    .paramTypes(clientApiRequestClass)
                    .declaredClass(riskAppClass),
            ),
        ).map { it.getMethodInstance(loader) }

        val checkRootMethods = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .usingStrings("/system/bin/")
                    .returnType(Boolean::class.javaPrimitiveType!!),
            ),
        ).map { it.getMethodInstance(loader) }

        return RootedMembers(
            returnEnvironment = returnEnvironment,
            clientApiRequest = clientApiRequestClass,
            environmentPut = environmentPut,
            riskAppClass = riskAppClass,
            riskAppField = riskAppField,
            unsetEnvironment = unsetEnvironment,
            setEnvironmentMethods = setEnvironmentMethods,
            checkRootMethods = checkRootMethods,
        )
    }
}
