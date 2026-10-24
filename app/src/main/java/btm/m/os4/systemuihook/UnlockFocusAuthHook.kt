// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Adapted for HyperChanger. See third_party/HyperIsland-LICENSE.
package btm.m.os4.systemuihook

import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Fork of HyperIsland's XMSF focus-auth bypass, kept behind its own setting. */
internal class UnlockFocusAuthHook(private val module: XposedModule) {
    private var installed = false

    fun install(classLoader: ClassLoader, preferences: android.content.SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return
        if (installed || !preferences.getBoolean(KEY_UNLOCK_FOCUS_AUTH, false)) return
        installed = true
        runCatching {
            val clazz = classLoader.loadClass("com.xiaomi.xms.auth.AuthSession")
            val method = clazz.declaredMethods.firstOrNull { it.name == "b" && it.parameterCount == 1 }
                ?: error("AuthSession.b(error) unavailable")
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:unlock-focus-auth")
                .intercept { chain ->
                    val error = chain.args.firstOrNull() ?: return@intercept chain.proceed()
                    runCatching {
                        val field = findField(error.javaClass, "a") ?: error("Missing error code")
                        val success = findMethod(chain.thisObject.javaClass, "h") ?: error("Missing success callback")
                        field.setInt(error, 0)
                        success.invoke(chain.thisObject)
                    }.getOrElse { chain.proceed() }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, "HyperChanger", "Installed focus authentication bypass")
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, "HyperChanger", "Focus authentication bypass unavailable", it) }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredMethod(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }
}
