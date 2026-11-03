// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Clamp only the overlay's two blur writers; keep card materials and blend animations intact. */
internal class PersonalAssistantWallpaperHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val installed = mutableSetOf<String>()

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!readPersonalAssistantSettings(preferences).removeWallpaperBlur) return
        for (name in listOf("blurView", "blurOverlayWindow")) {
            if (name in installed) continue
            runCatching {
                val adapter = classLoader.loadClass("com.miui.personalassistant.device.DeviceBlurBlendAdapter")
                val method = adapter.getDeclaredMethod(name, java.lang.Float.TYPE)
                check(method.returnType == java.lang.Void.TYPE)
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:personal-assistant:wallpaper:$name")
                    .intercept { chain -> chain.proceed(arrayOf(0.0f)) }
                installed += name
                HookModuleLogger.log(module, ModuleLog.INFO, TAG, "Installed overlay wallpaper hook: $name")
            }.onFailure {
                HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Could not install overlay wallpaper hook: $name", it)
            }
        }
    }

    private companion object { const val TAG = "PersonalAssistantWallpaper" }
}
