// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Executable

/** Unlock Settings' existing embedding rules without changing the device identity. */
internal class SettingsLayoutHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private var dualPaneInstalled = false
    private var landscapeInstalled = false

    fun installDualPane(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (dualPaneInstalled || param.packageName != SETTINGS_PACKAGE ||
            Application.getProcessName() != SETTINGS_PACKAGE || !readSettingsLayoutSettings(preferences).dualPane) return
        val layout = readSettingsLayoutSettings(preferences)
        val loader = param.defaultClassLoader
        val utils = loader.loadClass("com.android.settings.activityembedding.ActivityEmbeddingUtils")
        val enabled = utils.getDeclaredMethod("isSettingsSplitEnabled", Context::class.java)
        val widths = listOf("getMinCurrentScreenSplitWidthDp", "getMinSmallestScreenSplitWidthDp")
            .map { utils.getDeclaredMethod(it, Context::class.java) }
        val initialRatio = utils.getDeclaredMethod("getSplitRatio", Context::class.java)
        val params = loader.loadClass("androidx.window.embedding.SplitAttributesCalculatorParams")
        val getConfiguration = params.getMethod("getParentConfiguration")
        val constraintsSatisfied = params.getMethod("areDefaultConstraintsSatisfied")
        val attributes = loader.loadClass("androidx.window.embedding.SplitAttributes")
        val splitType = loader.loadClass("androidx.window.embedding.SplitAttributes\$SplitType")
        val ratio = splitType.getMethod("ratio", Float::class.javaPrimitiveType)
        val expanded = splitType.getField("SPLIT_TYPE_EXPAND").get(null)
        val attributesBuilder = loader.loadClass("androidx.window.embedding.SplitAttributes\$Builder")
        val copyAttributes = attributesBuilder.getConstructor(attributes)
        val setSplitType = attributesBuilder.getMethod("setSplitType", splitType)
        val buildAttributes = attributesBuilder.getMethod("build")
        val calculator = loader.loadClass("com.android.settings.activityembedding.ActivityEmbeddingRulesController\$1")
            .getDeclaredMethod("invoke", params)
        val checkBounds = loader.loadClass("androidx.window.embedding.SplitRule")
            .getDeclaredMethod("checkParentBounds\$window", Float::class.javaPrimitiveType, Rect::class.java)
        val controller = loader.loadClass("androidx.window.embedding.SplitController")
        val getInstance = controller.getMethod("getInstance", Context::class.java)
        val getStatus = controller.getMethod("getSplitSupportStatus")
        val available = loader.loadClass("androidx.window.embedding.SplitController\$SplitSupportStatus")
            .getField("SPLIT_AVAILABLE").get(null)
        val featureFlag = loader.loadClass("android.util.FeatureFlagUtils")
            .getDeclaredMethod("isEnabled", Context::class.java, String::class.java)
        val builders = listOf("SplitPairRule", "SplitPlaceholderRule").map { name ->
            val type = loader.loadClass("androidx.window.embedding.$name\$Builder")
            Triple(type.getDeclaredMethod("build"), type.getDeclaredMethod("setMinHeightDp", Int::class.javaPrimitiveType),
                if (name == "SplitPlaceholderRule") type.getDeclaredMethod("setSticky", Boolean::class.javaPrimitiveType) else null)
        }
        check(enabled.returnType == Boolean::class.javaPrimitiveType &&
            widths.all { it.returnType == Int::class.javaPrimitiveType } &&
            initialRatio.returnType == Float::class.javaPrimitiveType && calculator.returnType == attributes)
        val desiredRatio = settingsDualPaneRatio(layout.balance)
        val desiredSplitType = ratio.invoke(null, desiredRatio)

        val handles = mutableListOf<HookHandle>()
        try {
            // Native Settings supplies width/smallest-width, but leaves Builder's 600dp height default.
            builders.forEach { (build, setHeight, setSticky) ->
                handles += hookDeoptimized(build).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:settings-layout:${build.declaringClass.simpleName}-${build.returnType.simpleName}")
                    .intercept { chain ->
                        setHeight.invoke(chain.thisObject, 0)
                        if (layout.landscapeOnly) setSticky?.invoke(chain.thisObject, false)
                        chain.proceed()
                    }
            }
            widths.forEach { method ->
                handles += hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:settings-layout:${method.name}").intercept { 0 }
            }
            handles += hookDeoptimized(initialRatio).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:initial-ratio").intercept { desiredRatio }
            if (layout.landscapeOnly) {
                // A portrait launch must not start the Wi-Fi placeholder as a full-screen page.
                handles += hookDeoptimized(checkBounds).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:settings-layout:landscape-bounds").intercept { chain ->
                        val allowed = chain.proceed()
                        val bounds = chain.getArg(1) as? Rect
                        allowed == true && bounds != null && bounds.width() > bounds.height()
                    }
            }
            handles += hookDeoptimized(calculator).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:calculate-ratio").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val calculation = chain.getArg(0)
                        if (constraintsSatisfied.invoke(calculation) != true) return@runCatching result
                        val configuration = getConfiguration.invoke(calculation) as Configuration
                        val selectedType = if (layout.landscapeOnly &&
                            configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) expanded else desiredSplitType
                        val builder = copyAttributes.newInstance(result)
                        setSplitType.invoke(builder, selectedType)
                        buildAttributes.invoke(builder)
                    }.getOrElse { error ->
                        HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings split attributes", error)
                        result
                    }
                }
            handles += hookDeoptimized(featureFlag).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:large-screen-flag").intercept { chain ->
                    if (chain.getArg(1) == "settings_support_large_screen" &&
                        (chain.getArg(0) as? Context)?.packageName == SETTINGS_PACKAGE) true else chain.proceed()
                }
            // Install the entry gate last; keep real Window Extensions / manifest capability checks.
            handles += hookDeoptimized(enabled).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:split-enabled").intercept { chain ->
                    getStatus.invoke(getInstance.invoke(null, chain.getArg(0))) == available
                }
            dualPaneInstalled = true
            HookModuleLogger.log(module, Log.INFO, TAG, "Installed native Settings dual-pane unlock")
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    fun installLandscape(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (landscapeInstalled || param.packageName != SETTINGS_PACKAGE ||
            !readSettingsLayoutSettings(preferences).unlockLandscape) return
        val create = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        val request = Activity::class.java.getDeclaredMethod("setRequestedOrientation", Int::class.javaPrimitiveType)
        val handles = mutableListOf<HookHandle>()
        try {
            handles += hookDeoptimized(request).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:orientation-request").intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity?.packageName == SETTINGS_PACKAGE) {
                        chain.proceed(arrayOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
                    } else chain.proceed()
                }
            handles += hookDeoptimized(create).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-layout:orientation-create").intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity?.packageName == SETTINGS_PACKAGE) {
                        // Release manifest portrait requests as well as subsequent runtime requests.
                        runCatching { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                            .onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Could not unlock Settings orientation", it) }
                    }
                    result
                }
            landscapeInstalled = true
            HookModuleLogger.log(module, Log.INFO, TAG, "Installed Settings landscape unlock")
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val TAG = "SettingsLayout"
    }
}
