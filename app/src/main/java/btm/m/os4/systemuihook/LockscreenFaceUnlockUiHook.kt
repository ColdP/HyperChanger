// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Adapted for HyperChanger. See third_party/HyperIsland-LICENSE.
package btm.m.os4.systemuihook

import android.view.View
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * Hides MIUI's native face-unlock UI on the main lock screen while leaving
 * face authentication and the bouncer retry entry untouched.
 */
internal class LockscreenFaceUnlockUiHook(private val module: XposedModule) {

    private val TAG = "HyperIsland[LockscreenFaceUi]"
    private val TARGET_CLASS =
        "com.miui.keyguard.biometrics.faceunlock.MiuiKeyguardFaceUnlockView"
    private val LOCKSCREEN_VIEW_FIELD = "mIsKeyguardFaceUnlockView"

    @Volatile private var hooked = false
    @Volatile private var lockscreenViewField: Field? = null

    fun install(classLoader: ClassLoader) {
        if (hooked) return

        val viewClass = runCatching {
            classLoader.loadClass(TARGET_CLASS)
        }.getOrElse {
            btm.m.os4.systemuihook.HookModuleLogger.log(module, android.util.Log.WARN, TAG, "$TARGET_CLASS not found: ${it.message}")
            return
        }

        val visibilityMethod = runCatching {
            viewClass.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType!!)
        }.getOrElse {
            btm.m.os4.systemuihook.HookModuleLogger.log(module, android.util.Log.WARN, TAG, "setVisibility(int) not found: ${it.message}")
            return
        }

        visibilityMethod.isAccessible = true
        module.hook(visibilityMethod).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val requestedVisibility = chain.args[0] as? Int
            val result = chain.proceed()
            if (
                requestedVisibility != View.GONE &&
                isMainLockscreenView(chain.thisObject)
            ) {
                (chain.thisObject as? View)?.visibility = View.GONE
            }
            result
        }

        // This marks the main lock-screen instance and also covers initial inflation.
        runCatching {
            val markMethod = viewClass.getDeclaredMethod(
                "setKeyguardFaceUnlockView",
                Boolean::class.javaPrimitiveType!!,
            )
            markMethod.isAccessible = true
            module.hook(markMethod).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                if (chain.args[0] == true) {
                    (chain.thisObject as? View)?.visibility = View.GONE
                }
                result
            }
        }.onFailure {
            btm.m.os4.systemuihook.HookModuleLogger.log(module, android.util.Log.WARN, TAG, "initial visibility fallback unavailable: ${it.message}")
        }

        hooked = true
        btm.m.os4.systemuihook.HookModuleLogger.log(module, android.util.Log.DEBUG, TAG, "native lock-screen face UI hidden; authentication remains enabled")
    }

    private fun isMainLockscreenView(instance: Any?): Boolean {
        if (instance == null) return false
        return runCatching {
            val field = lockscreenViewField ?: findField(instance.javaClass, LOCKSCREEN_VIEW_FIELD)
                .also { lockscreenViewField = it }
            field.getBoolean(instance)
        }.getOrDefault(false)
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("$name not found in ${clazz.name} hierarchy")
    }
}
