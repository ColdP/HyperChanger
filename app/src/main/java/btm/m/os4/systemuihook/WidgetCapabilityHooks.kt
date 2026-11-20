// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ComponentName
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Assistant's real AppWidget models only. Never alter paid authorization or limits. */
internal class WidgetCapabilityHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    @Volatile private var settings = WidgetSettings()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        settings = readWidgetSettings(preferences)
        if (settings != WidgetSettings()) runCatching {
            LocalWidgetCatalogHooks.install(module, loader, preferences) { hookDeoptimized(it) }
        }.onFailure { error ->
            HookModuleLogger.log(module, android.util.Log.WARN, logTag, "Assistant local widget catalogue unavailable", error)
        }
        // This Assistant build already allows Android widgets in local stacks.
        // Its material capability is independent of the MIUI update protocol.
        if (!settings.androidGlass || !ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runCatching {
            val model = loader.loadClass("com.miui.personalassistant.widget.iteminfo.AppWidgetItemInfo")
            val parse = model.getDeclaredMethod("parseWidgetMetaData")
            check(parse.returnType == Void.TYPE)
            val provider = model.getField("provider")
            val blur = model.getField("supportBackgroundBlur")
            check(blur.type == Boolean::class.javaPrimitiveType)
            hookDeoptimized(parse).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("widget-capabilities:assistant:metadata").intercept { chain ->
                    val result = chain.proceed()
                    val item = chain.thisObject
                    // The parser has resolved an actual provider before changing its model.
                    if (provider.get(item) is ComponentName) {
                        val current = settings
                        if (current.androidGlass && ModuleSafetyRuntime.allows(SafetySection.GLASS)) blur.setBoolean(item, true)
                    }
                    result
                }
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key.startsWith(WIDGET_SETTINGS_PREFIX)) settings = readWidgetSettings(preferences)
            }.also(preferences::registerOnSharedPreferenceChangeListener)
            installed = true
        }.onFailure { error ->
            HookModuleLogger.log(module, android.util.Log.WARN, logTag, "Assistant widget capabilities unavailable", error)
        }
    }
}
