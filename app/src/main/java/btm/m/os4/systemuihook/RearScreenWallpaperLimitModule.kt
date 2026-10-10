// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Modifier

/** Changes the add-capacity gate, leaving DRM, NFC resources and list persistence intact. */
class RearScreenWallpaperLimitModule : XposedModule() {
    private var installed = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != PACKAGE || !OsCompatibility.areHooksAllowed() || installed) return
        val prefs = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!prefs.readRearScreenWallpaperLimitSettings().enabled) return
        val loader = param.defaultClassLoader

        runCatching {
            val viewModel = loader.loadClass("com.rearScreen.viewModel.RearScreenDetailViewModel")
            val bean = loader.loadClass("com.rearScreen.bean.RearScreenListItemBean")
            val isNfc = bean.getMethod("isNFC")
            // The private boolean(List) check contains an inlined 15. Changing its
            // static final constant would not affect this code. Match its signature
            // rather than the version-dependent obfuscated method name (11.5.2.0: wt).
            val canAdd = viewModel.declaredMethods.single { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(List::class.java))
            }.apply { isAccessible = true }
            hook(canAdd).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-wallpaper-limit:can-add").intercept { chain ->
                    val settings = prefs.readRearScreenWallpaperLimitSettings()
                    if (!settings.enabled) return@intercept chain.proceed()
                    val list = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                    // Preserve the stock NFC exclusion; unknown item types fall back
                    // to the original check instead of granting capacity blindly.
                    val count = runCatching {
                        list.count { item ->
                            require(bean.isInstance(item))
                            isNfc.invoke(item) == false
                        }
                    }.getOrNull() ?: return@intercept chain.proceed()
                    count < settings.limit
                }
            installed = true
            log(Log.INFO, TAG, "Rear-screen wallpaper capacity check installed")
        }.onFailure { log(Log.ERROR, TAG, "Could not install rear-screen wallpaper limit", it) }

        if (installed) installAssistantGuard(loader, prefs)
    }

    private fun installAssistantGuard(loader: ClassLoader, prefs: SharedPreferences) {
        // Optional on older Themes versions. Keep the last-wallpaper deletion guard.
        runCatching {
            val prefix = "com.rearScreen.miclaw.appfunction.common.WallpaperListGuard"
            val guard = loader.loadClass(prefix)
            val pass = loader.loadClass("$prefix\$GuardResult\$Pass").getField("INSTANCE").get(null)
            val exceeded = loader.loadClass("$prefix\$GuardResult\$LimitExceeded").getField("INSTANCE").get(null)
            hook(guard.getDeclaredMethod("checkAddable", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-wallpaper-limit:assistant-add")
                .intercept { chain ->
                    val settings = prefs.readRearScreenWallpaperLimitSettings()
                    if (!settings.enabled) chain.proceed()
                    else if ((chain.getArg(0) as Int) < settings.limit) pass else exceeded
                }
        }.onFailure { log(Log.WARN, TAG, "Rear-screen assistant capacity guard unavailable", it) }
    }

    private companion object {
        const val PACKAGE = "com.android.thememanager"
        const val TAG = "HyperChangerRearWallpaper"
    }
}
