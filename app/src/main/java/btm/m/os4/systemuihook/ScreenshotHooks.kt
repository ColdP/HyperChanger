// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Hooks the screenshot app's shell pipeline without modifying its private resource directory. */
internal class ScreenshotHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private val context = AtomicReference<Context?>()
    private var installed = false

    fun install(classLoader: ClassLoader, preferences: android.content.SharedPreferences) {
        if (installed) return
        runCatching {
            val provider = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellResourceProvider")
            val companion = provider.getField("Companion").get(null)
            val get = companion.javaClass.getDeclaredMethod("get", Context::class.java)
            hookDeoptimized(get).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("screenshot-shell:context").intercept { chain ->
                    (chain.getArg(0) as? Context)?.applicationContext?.let(context::set)
                    chain.proceed()
                }

            val catalog = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellCatalog")
            val resolve = catalog.getDeclaredMethod("resolveResourceId")
            hookDeoptimized(resolve).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("screenshot-shell:resource-id").intercept { chain ->
                    val original = chain.proceed()
                    if (original == Long.MIN_VALUE && hasAnyCustom(preferences)) CUSTOM_RESOURCE_ID else original
                }

            val modeClass = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellMode")
            // Intercept before the stock cache lookup. Its resource-id/mode key cannot
            // distinguish two custom styles, or a custom style from a restored default.
            val acquire = provider.declaredMethods.single { method ->
                method.name == "acquire" && method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == modeClass && method.parameterTypes[1] == Long::class.javaPrimitiveType
            }
            hookDeoptimized(acquire).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("screenshot-shell:acquire").intercept { chain ->
                    val mode = chain.getArg(0) ?: return@intercept chain.proceed()
                    customAsset(classLoader, modeClass, mode, preferences) ?: chain.proceed()
                }
            val providerNeedsDownload = provider.declaredMethods.firstOrNull { method ->
                method.name == "needsDownload" && method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType && method.parameterTypes[1] == modeClass
            }
            if (providerNeedsDownload != null) {
                hookDeoptimized(providerNeedsDownload).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("screenshot-shell:needs-download").intercept { chain ->
                        val mode = chain.getArg(1)
                        if (mode != null && hasCustom(preferences, mode.toString())) false else chain.proceed()
                    }
            }

            installed = true
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed screenshot shell asset hooks")
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install screenshot shell hooks", it) }
    }

    private fun hasAnyCustom(prefs: android.content.SharedPreferences): Boolean =
        activeStyleId(RearScreenshotShellMode.FOCUS, prefs) != null ||
            activeStyleId(RearScreenshotShellMode.FULL, prefs) != null

    private fun hasCustom(prefs: android.content.SharedPreferences, mode: String): Boolean {
        val customMode = if (mode.contains("FULL", true)) RearScreenshotShellMode.FULL else RearScreenshotShellMode.FOCUS
        return activeStyleId(customMode, prefs) != null
    }

    private fun activeStyleId(mode: RearScreenshotShellMode, prefs: android.content.SharedPreferences): String? {
        val key = if (mode == RearScreenshotShellMode.FOCUS) KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID else KEY_REAR_SCREENSHOT_SHELL_FULL_ID
        val resolver = appContext()?.contentResolver
        val response = runCatching {
            resolver?.call(
                Uri.parse("content://$REAR_SCREENSHOT_SHELL_AUTHORITY"),
                "activeStyle",
                mode.key,
                null,
            )
        }.getOrNull()
        // A successful empty response means cleared/disabled, even if remote prefs lag.
        if (response != null) return response.getString("id")?.takeIf(String::isNotBlank)
        return if (prefs.getBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, false))
            prefs.getString(key, "")?.takeIf(String::isNotBlank) else null
    }

    private fun appContext(): Context? {
        context.get()?.let { return it }
        return runCatching {
            (Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Context)
                ?.applicationContext?.also(context::set)
        }.getOrNull()
    }

    private fun customAsset(
        classLoader: ClassLoader,
        modeClass: Class<*>,
        mode: Any,
        prefs: android.content.SharedPreferences,
    ): Any? = runCatching {
        val modeValue = mode.toString()
        val customMode = if (modeValue.contains("FULL", true)) RearScreenshotShellMode.FULL else RearScreenshotShellMode.FOCUS
        val id = activeStyleId(customMode, prefs)
            ?: return null
        val resolver = appContext()?.contentResolver ?: return null
        val base = "content://${REAR_SCREENSHOT_SHELL_AUTHORITY}/${customMode.key}/$id"
        val bitmap = resolver.openInputStream(Uri.parse("$base/image"))?.use(BitmapFactory::decodeStream) ?: return null
        val geometryText = resolver.openInputStream(Uri.parse("$base/geometry"))?.use { it.bufferedReader().readText() } ?: return null
        val json = JSONObject(geometryText)
        val geometryClass = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellGeometry")
        val geometry = geometryClass.getConstructor(
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        ).newInstance(
            json.optDouble("with", 904.0).toFloat(), json.optDouble("height", 572.0).toFloat(),
            json.optDouble("leftMargin", 0.0).toFloat(), json.optDouble("topMargin", 0.0).toFloat(),
            json.optDouble("rightMargin", 0.0).toFloat(), json.optDouble("bottomMargin", 0.0).toFloat(),
        )
        val assetClass = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellAsset")
        val asset = assetClass.getConstructor(Long::class.javaPrimitiveType, modeClass, geometryClass, Bitmap::class.java)
            .newInstance(CUSTOM_RESOURCE_ID, mode, geometry, bitmap)
        classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.backscreen.ShellOutcome\$Ready")
            .getConstructor(assetClass).newInstance(asset)
    }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not load custom screenshot shell", it) }.getOrNull()

    private companion object {
        const val CUSTOM_RESOURCE_ID = -9_876_543_210L
    }
}
