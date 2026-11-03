// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Adjust only the overlay's blur radius; keep card materials and blend animations intact. */
internal class PersonalAssistantWallpaperHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val installed = mutableSetOf<String>()
    private var backgroundHooks: PersonalAssistantBackgroundHooks? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        val settings = readPersonalAssistantSettings(preferences)
        if (!settings.removeWallpaperBlur && !settings.customBlurActive && settings.backgroundMode == 0) return
        for (name in listOf("blurView", "blurOverlayWindow")) {
            if (name in installed) continue
            runCatching {
                val adapter = classLoader.loadClass("com.miui.personalassistant.device.DeviceBlurBlendAdapter")
                val method = adapter.getDeclaredMethod(name, java.lang.Float.TYPE)
                check(method.returnType == java.lang.Void.TYPE)
                val nativeMax = if (settings.customBlurActive) adapter.getDeclaredField("maxBlurRadius")
                    .apply { check(type == java.lang.Float.TYPE); isAccessible = true } else null
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:personal-assistant:wallpaper:$name")
                    .intercept { chain ->
                        if (settings.removeWallpaperBlur || backgroundHooks?.replacesBackground(chain.thisObject) == true) {
                            chain.proceed(arrayOf(0.0f))
                        } else if (settings.customBlurActive) {
                            val instance = chain.thisObject
                            val radiusOrProgress = chain.getArg(0) as Float
                            val originalMax = nativeMax!!.getFloat(instance)
                            if (name == "blurOverlayWindow") {
                                chain.proceed(arrayOf(settings.windowBlurRadius(radiusOrProgress, originalMax)))
                            } else {
                                // Keep native progress (including blur scale) while changing only its radius.
                                nativeMax.setFloat(instance, settings.wallpaperBlurRadius.toFloat())
                                try { chain.proceed() } finally { nativeMax.setFloat(instance, originalMax) }
                            }
                        } else chain.proceed()
                    }
                installed += name
                HookModuleLogger.log(module, ModuleLog.INFO, TAG, "Installed overlay wallpaper hook: $name")
            }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Could not install overlay wallpaper hook: $name", it)
            }
        }
        if (settings.backgroundMode != 0 && installed.containsAll(listOf("blurView", "blurOverlayWindow")) && backgroundHooks == null) {
            runCatching {
                PersonalAssistantBackgroundHooks(module, hookDeoptimized, classLoader, settings).also {
                    backgroundHooks = it
                    it.install()
                }
            }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Could not install replacement background", it)
            }
        }
    }

    private companion object { const val TAG = "PersonalAssistantWallpaper" }
}
