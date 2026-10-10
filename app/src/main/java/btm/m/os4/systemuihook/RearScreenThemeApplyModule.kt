// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Restores real rights acquisition for rear-screen resources only. */
class RearScreenThemeApplyModule : XposedModule() {
    private val checkingRearScreenRights = ThreadLocal<Boolean>()
    private var installed = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != PACKAGE || !OsCompatibility.areHooksAllowed() || installed) return
        val prefs = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!prefs.isRearScreenThemeApplyFixEnabled()) return

        runCatching {
            val loader = param.defaultClassLoader
            val serviceClass = loader.loadClass("com.android.thememanager.controller.online.DrmService")
            val contextClass = loader.loadClass("com.android.thememanager.ResourceContext")
            val resourceClass = loader.loadClass("com.android.thememanager.basemodule.resource.model.Resource")
            val resultClass = loader.loadClass("miui.drm.DrmManager\$DrmResult")
            // Match the signature rather than the version-dependent obfuscated method/field names.
            val checkRights = serviceClass.declaredMethods.single { method ->
                !Modifier.isStatic(method.modifiers) && method.returnType == resultClass &&
                    method.parameterTypes.contentEquals(arrayOf(resourceClass))
            }.apply { isAccessible = true }
            val resourceContext = serviceClass.declaredFields.single {
                !Modifier.isStatic(it.modifiers) && it.type == contextClass
            }.apply { isAccessible = true }
            val getResourceCode = contextClass.getMethod("getResourceCode")

            // Older theme-unlock modules also hook the framework DRM overloads. Restore
            // those only while this thread executes the original rear-screen rights check.
            loader.loadClass("miui.drm.DrmManager").declaredMethods.filter { method ->
                method.name == "isLegal" && Modifier.isStatic(method.modifiers) &&
                    method.returnType == resultClass
            }.forEach { method ->
                hook(method).setPriority(Int.MAX_VALUE).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-screen-theme-apply:drm:${method.toGenericString()}")
                    .intercept { chain ->
                        if (checkingRearScreenRights.get() == true) {
                            invokeOriginal(method, chain.thisObject, chain.args.toTypedArray())
                        } else chain.proceed()
                    }
            }

            hook(checkRights).setPriority(Int.MAX_VALUE).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-screen-theme-apply:acquire-rights").intercept { chain ->
                    val isRearScreen = runCatching {
                        val context = resourceContext.get(chain.thisObject)
                        context != null && getResourceCode.invoke(context) == "rearscreen"
                    }.getOrDefault(false)
                    if (!prefs.isRearScreenThemeApplyFixEnabled() || !isRearScreen) {
                        return@intercept chain.proceed()
                    }
                    // A forged DRM_SUCCESS skips the stock rights download; the later
                    // rear-screen apply task then fails with right_copy_fail. Returning the
                    // real result lets its existing callback acquire the missing rights.
                    val previous = checkingRearScreenRights.get()
                    checkingRearScreenRights.set(true)
                    try {
                        invokeOriginal(checkRights, chain.thisObject, chain.args.toTypedArray())
                    } finally {
                        if (previous == null) checkingRearScreenRights.remove()
                        else checkingRearScreenRights.set(previous)
                    }
                }
            installed = true
            log(Log.INFO, TAG, "Rear-screen rights acquisition compatibility installed")
        }.onFailure { log(Log.ERROR, TAG, "Could not install rear-screen theme apply fix", it) }
    }

    private fun invokeOriginal(method: Method, receiver: Any?, args: Array<Any?>): Any? = try {
        getInvoker(method).setType(Invoker.Type.ORIGIN).invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private companion object {
        const val PACKAGE = "com.android.thememanager"
        const val TAG = "HyperChangerRearThemeApply"
    }
}
