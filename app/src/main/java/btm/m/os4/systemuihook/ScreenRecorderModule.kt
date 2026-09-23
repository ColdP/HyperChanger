// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ContentValues
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Method

/** Hooks the versioned Xiaomi screen recorder without linking against its classes. */
class ScreenRecorderModule : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!OsCompatibility.areHooksAllowed() || param.packageName != PACKAGE) return
        val prefs = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        runCatching {
            if (prefs.getBoolean(SCREEN_RECORDER_CONFIG, false) ||
                prefs.getBoolean(SCREEN_RECORDER_FRAME_RATES, false) ||
                prefs.getBoolean(SCREEN_RECORDER_BIT_RATES, false)
            ) installExtendedOptions(param, prefs)
            if (prefs.getString(SCREEN_RECORDER_SAVE_PATH, "").orEmpty().isNotBlank()) {
                installSavePath(param.defaultClassLoader, prefs)
            }
        }.onFailure { log(Log.ERROR, TAG, "Could not install screen recorder hooks", it) }
    }

    private fun installExtendedOptions(param: PackageLoadedParam, prefs: SharedPreferences) {
        val hasSeparateOptions = prefs.contains(SCREEN_RECORDER_FRAME_RATES) ||
            prefs.contains(SCREEN_RECORDER_BIT_RATES)
        val legacyConfigEnabled = prefs.getBoolean(SCREEN_RECORDER_CONFIG, false)
        val frameEnabled = if (hasSeparateOptions) {
            prefs.getBoolean(SCREEN_RECORDER_FRAME_RATES, false)
        } else legacyConfigEnabled
        val bitRateEnabled = if (hasSeparateOptions) {
            prefs.getBoolean(SCREEN_RECORDER_BIT_RATES, false)
        } else legacyConfigEnabled
        val (frameMethod, bitRateMethod) = findConfigMethods(param)

        if (frameEnabled && frameMethod != null) {
            hook(frameMethod).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("screen-recorder:Frame").intercept { chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = 1200
                    args[1] = 1
                    replaceFinalArray(
                        frameMethod.declaringClass,
                        intArrayOf(15, 24, 30, 48, 60, 90),
                        intArrayOf(15, 24, 30, 48, 60, 90, 120, 144, 165, 185),
                    )
                    chain.proceed(args)
                }
        }

        if (bitRateEnabled && bitRateMethod != null) {
            hook(bitRateMethod).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("screen-recorder:BitRate").intercept { chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = 1200
                    args[1] = 1
                    replaceFinalArray(
                        bitRateMethod.declaringClass,
                        intArrayOf(200, 100, 50, 32, 24, 16, 8, 6, 4, 1),
                        intArrayOf(1200, 800, 400, 200, 100, 50, 32, 24, 16, 8, 6, 4, 1),
                    )
                    chain.proceed(args)
                }
        }

        if (frameEnabled && frameMethod == null) log(Log.ERROR, TAG, "Could not find frame-rate config method")
        if (bitRateEnabled && bitRateMethod == null) log(Log.ERROR, TAG, "Could not find bitrate config method")
    }

    private fun findConfigMethods(param: PackageLoadedParam): Pair<Method?, Method?> {
        var frameMethod: Method? = null
        var bitRateMethod: Method? = null
        runCatching {
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            try {
                frameMethod = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(FRAME_METHOD_MARKER),
                    ),
                ).singleOrNull()?.getMethodInstance(param.defaultClassLoader)
                bitRateMethod = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(BIT_RATE_METHOD_MARKER),
                    ),
                ).singleOrNull()?.getMethodInstance(param.defaultClassLoader)
            } finally {
                bridge.close()
            }
        }.onFailure { log(Log.WARN, TAG, "DexKit screen recorder lookup failed", it) }

        if (frameMethod == null || bitRateMethod == null) {
            runCatching {
                val legacyConfig = param.defaultClassLoader.loadClass(LEGACY_CONFIG_CLASS)
                if (frameMethod == null) {
                    frameMethod = legacyConfig.getDeclaredMethod(
                        LEGACY_FRAME_METHOD,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                }
                if (bitRateMethod == null) {
                    bitRateMethod = legacyConfig.getDeclaredMethod(
                        LEGACY_BIT_RATE_METHOD,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                }
            }.onFailure { log(Log.WARN, TAG, "Legacy screen recorder lookup failed", it) }
        }
        return frameMethod to bitRateMethod
    }

    private fun replaceFinalArray(owner: Class<*>, original: IntArray, expanded: IntArray) {
        owner.declaredFields.forEach { field ->
            field.isAccessible = true
            if (!java.lang.reflect.Modifier.isFinal(field.modifiers) ||
                !java.lang.reflect.Modifier.isStatic(field.modifiers)
            ) return@forEach
            val value = field.get(null) as? IntArray ?: return@forEach
            if (value.contentEquals(original)) {
                field.set(null, expanded)
                return
            }
        }
    }

    private fun installSavePath(loader: ClassLoader, prefs: SharedPreferences) {
        val pathMethod = loader.loadClass("j0.q").getDeclaredMethod("f", android.content.Context::class.java)
        hook(pathMethod).setExceptionMode(ExceptionMode.PROTECTIVE).setId("screen-recorder:save-path").intercept { chain ->
            val path = prefs.getString(SCREEN_RECORDER_SAVE_PATH, "").orEmpty().trim()
            if (path.isBlank()) chain.proceed() else File(path).apply { mkdirs() }
        }
        val put = ContentValues::class.java.getMethod("put", String::class.java, String::class.java)
        hook(put).setExceptionMode(ExceptionMode.PROTECTIVE).setId("screen-recorder:relative-path").intercept { chain ->
            if (chain.getArg(0) == "relative_path") {
                val path = prefs.getString(SCREEN_RECORDER_SAVE_PATH, "").orEmpty().trim()
                val root = Environment.getExternalStorageDirectory().absolutePath
                if (path.startsWith(root + File.separator)) {
                    chain.getArgs()[1] = path.removePrefix(root + File.separator).replace(File.separatorChar, '/')
                }
            }
            chain.proceed()
        }
    }

    companion object {
        private const val PACKAGE = "com.miui.screenrecorder"
        private const val TAG = "HyperChangerScreenRecorder"
        private const val FRAME_METHOD_MARKER = "Error when set frame value, maxValue = "
        private const val BIT_RATE_METHOD_MARKER = "defaultBitRate = "
        private const val LEGACY_CONFIG_CLASS = "b0.C0297c"
        private const val LEGACY_FRAME_METHOD = "v"
        private const val LEGACY_BIT_RATE_METHOD = "t"
    }
}
