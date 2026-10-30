// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method

/** Hooks the OEM wallpaper enumerators without replacing or modifying system files. */
internal class CustomDepthWallpaperHooks(
    private val module: HyperSystemUiModule,
) {
    private val installedThemeMethods = mutableSetOf<Method>()
    private var installedPresetHook = false
    private val installedValidationMethods = mutableSetOf<Method>()
    private val installedPresetPathMethods = mutableSetOf<Method>()
    private var installedWallpaperEditorHook = false
    private var installedVideoSizeHook = false

    fun installThemeManager(loader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        installWallpaperEditorSubjectHook(loader)
        val helper = runCatching {
            loader.loadClass("com.android.thememanager.settings.WallpaperLoadHelper")
        }.getOrNull() ?: return
        helper.declaredMethods
            .filter { method ->
                method.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                ) && java.util.List::class.java.isAssignableFrom(method.returnType) &&
                    method.name in setOf("cdj", "ni7", "o1t")
            }
            .forEach { method ->
                if (!installedThemeMethods.add(method)) return@forEach
                module.hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:custom-depth-theme:${method.name}")
                    .intercept { chain ->
                        val original = chain.proceed()
                        appendThemeGroups(helper, original, preferences)
                    }
            }
        HookModuleLogger.log(module, ModuleLog.INFO, "CustomDepthWallpaper", "ThemeManager custom wallpaper hooks installed")
    }

    /** Resource conversion initializes supportSubject to false even when a valid mask is present. */
    private fun installWallpaperEditorSubjectHook(loader: ClassLoader) {
        if (installedWallpaperEditorHook) return
        val editor = runCatching {
            loader.loadClass("com.miui.keyguard.editor.edit.base.WallpaperEditor")
        }.getOrNull() ?: return
        val methods = editor.declaredMethods.filter {
            it.name == "qrj" && it.parameterCount == 1 &&
                it.returnType.name == "com.miui.keyguard.editor.data.bean.WallpaperTypeInfo"
        }
        if (methods.isEmpty()) return
        methods.forEach { method ->
            module.hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:custom-depth-support-subject")
                .intercept { chain ->
                    val result = chain.proceed() ?: return@intercept null
                    runCatching {
                        val mask = result.javaClass.getMethod("getMaskPath").invoke(result) as? String
                        val mode = (result.javaClass.getMethod("getMattingMode").invoke(result) as? Int) ?: 0
                        if (!mask.isNullOrBlank() && mode == 2) {
                            result.javaClass.getMethod("setSupportSubject", Boolean::class.javaPrimitiveType)
                                .invoke(result, true)
                        }
                    }
                    result
                }
        }
        installedWallpaperEditorHook = true
    }

    fun installMiWallpaper(loader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN) || installedPresetHook) return
        val settings = readCustomDepthWallpaperSettings(preferences)
        if (!settings.enabled || settings.activePack.isBlank() || !settings.activePack.equals("snoopy", true)) return
        val util = runCatching {
            loader.loadClass("com.miui.miwallpaper.container.videodepth.PresetDepthUtil")
        }.getOrNull() ?: return
        installPresetPathHooks(loader, preferences)
        val method = util.declaredMethods.firstOrNull {
            it.name == "b" && it.parameterCount == 0 && it.returnType.name.endsWith("PresetDepthVideo")
        } ?: return
        installedPresetHook = true
        module.hookDeoptimized(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:custom-depth-preset")
            .intercept { chain ->
                val current = readCustomDepthWallpaperSettings(preferences)
                val config = current.miWallpaperDir.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(it, "wallpaper_config.json") }
                if (current.enabled && current.activePack.equals("snoopy", true) && config?.isFile == true) {
                    runCatching {
                        util.getDeclaredMethod("c", String::class.java).invoke(null, config.absolutePath)
                    }.getOrNull() ?: chain.proceed()
                } else {
                    chain.proceed()
                }
            }
        HookModuleLogger.log(module, ModuleLog.INFO, "CustomDepthWallpaper", "MiWallpaper preset depth hook installed")
    }

    /** Resolves relative paths at the final MiWallpaper getter boundary. The imported JSON stays portable. */
    private fun installPresetPathHooks(loader: ClassLoader, preferences: SharedPreferences) {
        val videoClass = runCatching {
            loader.loadClass("com.miui.miwallpaper.container.videodepth.PresetDepthVideo")
        }.getOrNull() ?: return
        videoClass.declaredMethods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java &&
                it.name in setOf("getVideoPath", "getPreviewPath", "getSubjectPath")
        }.forEach { method ->
            if (!installedPresetPathMethods.add(method)) return@forEach
            module.hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:custom-depth-path:${method.name}")
                .intercept { chain ->
                    val value = chain.proceed() as? String ?: return@intercept null
                    if (value.startsWith("/")) value
                    else {
                        val base = readCustomDepthWallpaperSettings(preferences).miWallpaperDir
                        if (base.isBlank()) value else java.io.File(base, value.removePrefix("./")).absolutePath
                    }
                }
        }
    }

    /** Bypasses ThemeManager/AOD's preflight checks; decoder/playback still validates the media. */
    fun installVideoValidationBypass(loader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        installVideoSizeClassificationBypass(loader)
        installVideoApplicationClassificationBypass(loader)
        fun hookBooleanMethods(className: String, names: Set<String>) {
            val clazz = runCatching { loader.loadClass(className) }.getOrNull() ?: return
            clazz.declaredMethods.filter { it.name in names && it.returnType == Boolean::class.javaPrimitiveType }
                .forEach { method ->
                    if (!installedValidationMethods.add(method)) return@forEach
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:custom-depth-video-check:${className}:${method.name}")
                        .intercept { true }
                }
        }
        fun hookCompanionBooleanMethods(className: String, names: Set<String>) {
            hookBooleanMethods("$className\$Companion", names)
        }
        hookBooleanMethods("com.android.thememanager.util.VideoCheckUtils", setOf("n7h", "k", "kja0"))
        hookCompanionBooleanMethods("com.android.thememanager.util.VideoCheckUtils", setOf("n7h", "k", "kja0"))
        hookBooleanMethods("com.personalizedEditor.interceptor.VideoCheckForDepthInterceptor", setOf("n7h", "ki", "zurt"))
        hookBooleanMethods("com.personalizedEditor.interceptor.VideoCheckForInterceptor", setOf("p", "n7h", "qrj"))
        hookBooleanMethods("com.miui.keyguard.editor.video.VideoCheckUtils", setOf("p", "n7h", "k", "kja0"))
        hookCompanionBooleanMethods("com.miui.keyguard.editor.video.VideoCheckUtils", setOf("p", "n7h", "k", "kja0"))
        hookBooleanMethods("com.miui.keyguard.editor.video.VideoCheckForDepthInterceptor", setOf("qrj", "cdj", "fn3e"))
        HookModuleLogger.log(module, ModuleLog.INFO, "CustomDepthWallpaper", "Video validation bypass hooks installed")
    }

    /**
     * The application screen performs a second, independent size classification through
     * VideoSizeConfirm.zy(width, height).  Its result controls the 4K/8K warning dialog;
     * it is separate from VideoCheckForDepthInterceptor's format check and therefore must
     * be bypassed as well for imported local wallpaper videos.
     */
    private fun installVideoSizeClassificationBypass(loader: ClassLoader) {
        if (installedVideoSizeHook) return
        val clazz = runCatching {
            loader.loadClass("com.android.thememanager.activity.VideoSizeConfirm")
        }.getOrNull() ?: return
        val methods = clazz.declaredMethods.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.name == "zy" &&
                it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                ) && it.returnType == Boolean::class.javaPrimitiveType
        }
        if (methods.isEmpty()) return
        methods.forEach { method ->
            if (!installedValidationMethods.add(method)) return@forEach
            module.hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:custom-depth-video-size-classification")
                .intercept { false }
        }
        installedVideoSizeHook = true
    }

    /**
     * Imported resources can carry a stale/incorrect MIME classification.  The detail
     * screen checks that classification before it reaches the dimension check, so a local
     * package can show the Dolby/4K/8K dialog even when the actual file is ordinary AVC.
     * Bypass the classification gate and the shared Dolby predicate; media decoding still
     * receives the original file and remains responsible for actual playback support.
     */
    private fun installVideoApplicationClassificationBypass(loader: ClassLoader) {
        val sizeConfirm = runCatching {
            loader.loadClass("com.android.thememanager.activity.VideoSizeConfirm")
        }.getOrNull()
        sizeConfirm?.declaredMethods
            ?.filter {
                it.name == "q" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Runnable::class.java &&
                    it.returnType == Void.TYPE
            }
            ?.forEach { method ->
                if (!installedValidationMethods.add(method)) return@forEach
                module.hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:custom-depth-video-apply-warning")
                    .intercept { chain ->
                        // q() only owns the pre-apply warning gate.  Continue the caller's
                        // original Runnable so the normal wallpaper application path runs.
                        runCatching { (chain.args.getOrNull(1) as? Runnable)?.run() }
                        null
                    }
            }

        val fragment = runCatching {
            loader.loadClass("com.android.thememanager.activity.BaseVideoDetailFragment")
        }.getOrNull()
        fragment?.declaredMethods
            ?.filter {
                it.name == "ap23" && it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            ?.forEach { method ->
                if (!installedValidationMethods.add(method)) return@forEach
                module.hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:custom-depth-video-dolby-classification")
                    .intercept { false }
            }

        val utils = runCatching {
            loader.loadClass("com.android.thememanager.detail.video.model.VideoWallpaperUtils")
        }.getOrNull()
        utils?.declaredMethods
            ?.filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.name == "isDolbyVisionVideo" &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            ?.forEach { method ->
                if (!installedValidationMethods.add(method)) return@forEach
                module.hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:custom-depth-video-dolby-predicate")
                    .intercept { false }
            }
    }

    private fun appendThemeGroups(helper: Class<*>, original: Any?, preferences: SharedPreferences): Any? {
        val settings = readCustomDepthWallpaperSettings(preferences)
        if (!settings.enabled || settings.themeDir.isBlank() || original !is java.util.List<*>) return original
        // WallpaperLoadHelper.fu4() expects a group root whose immediate children are
        // wallpaper directories. Passing the pack directory itself makes it enumerate JPG/MP4
        // files and return an empty list, so the imported pack never appears in ThemeManager.
        val groupRoot = java.io.File(settings.themeDir).parentFile?.absolutePath
            ?.takeIf { it.isNotBlank() } ?: settings.themeDir
        val extra = runCatching {
            helper.getDeclaredMethod("fu4", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
                .apply { isAccessible = true }
                .invoke(null, false, false, groupRoot) as? java.util.List<*>
        }.getOrNull().orEmpty()
        if (extra.isEmpty()) return original
        val result = java.util.ArrayList<Any?>(original.size + extra.size)
        result.addAll(original)
        extra.filterNotNull().filter { candidate ->
            result.none { existing -> existing?.toString() == candidate.toString() }
        }.forEach(result::add)
        return result
    }
}
