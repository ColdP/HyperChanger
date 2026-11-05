// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolves the registry consumers, never the unrelated static maps of the configuration class. */
internal class RearMusicWhitelistHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private val installed = mutableSetOf<Method>()

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (!preferences.getBoolean(HOOK_MUSIC_CONTROLS_WHITELIST, true)) return
        runCatching {
            installResolved(resolve(param), preferences)
        }.onFailure {
            HookModuleLogger.log(module, ModuleLog.WARN, logTag, "Could not resolve rear music whitelist targets", it)
        }
    }

    private fun installResolved(targets: Targets, preferences: SharedPreferences) {
        fun whitelist(): Set<String> = preferences
            .getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet()).orEmpty()

        install(targets.business, "business") { builder ->
            builder.intercept { chain ->
                if (chain.getArg(0) as? String in whitelist()) "music" else chain.proceed()
            }
        }
        install(targets.supportedApps, "supported-apps") { builder ->
            builder.intercept { chain ->
                val original = chain.proceed()
                // LinkedHashSet also satisfies the native HashSet return type in newer APKs.
                LinkedHashSet<Any?>().apply {
                    (original as? Set<*>)?.let(::addAll)
                    addAll(whitelist())
                }
            }
        }
        install(targets.isMusic, "is-music") { builder ->
            builder.intercept { chain ->
                if (chain.getArg(0) as? String in whitelist()) true else chain.proceed()
            }
        }
        install(targets.isEnabled, "is-enabled") { builder ->
            builder.intercept { chain ->
                if (chain.getArg(0) as? String in whitelist()) {
                    // Match the native gate: a missing or disabled music switch does not allow playback.
                    (chain.getArg(2) as? Map<*, *>)?.get("com.music.service") == true
                } else chain.proceed()
            }
        }
    }

    private fun install(method: Method, role: String, register: (HookBuilder) -> Unit) {
        if (method in installed) return
        runCatching {
            register(hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-music-whitelist:$role"))
            installed += method
            HookModuleLogger.log(module, ModuleLog.INFO, logTag, "Installed rear music $role: $method")
        }.onFailure {
            HookModuleLogger.log(module, ModuleLog.WARN, logTag, "Could not install rear music $role: $method", it)
        }
    }

    private fun resolve(param: PackageLoadedParam): Targets {
        System.loadLibrary("dexkit")
        val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
        try {
            val musicData = bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create()
                    .addUsingString("unified.music", StringMatchType.Equals)
                    .addUsingString("music", StringMatchType.Equals)
                    .returnType(Boolean::class.javaPrimitiveType!!).paramTypes(String::class.java),
            )).filter { Modifier.isStatic(it.modifiers) }
                .singleOrNull() ?: error("No unique rear music classifier")
            val isMusic = musicData.getMethodInstance(param.defaultClassLoader)
            val configClass = isMusic.declaringClass
            val business = configClass.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }.singleOrNull() ?: error("No unique rear music business lookup in ${configClass.name}")
            val packageCandidates = bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(configClass).paramCount(0)
                    .addUsingString("music", StringMatchType.Equals),
            )).map { it.toDexMethod() }
            val supportedApps = resolvePackageList(packageCandidates, param.defaultClassLoader)
                ?: error("No unique rear music package list in ${configClass.name}: $packageCandidates")
            val isEnabled = bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().addUsingString("com.music.service", StringMatchType.Equals)
                    .returnType(Boolean::class.javaPrimitiveType!!)
                    .paramTypes(String::class.java, Set::class.java, Map::class.java)
                    .addInvoke(musicData.descriptor),
            )).filter { Modifier.isStatic(it.modifiers) }
                .singleOrNull()?.getMethodInstance(param.defaultClassLoader)
                ?: error("No unique rear music switch gate calling $isMusic")
            return Targets(business, supportedApps, isMusic, isEnabled)
        } finally {
            bridge.close()
        }
    }

    private fun resolvePackageList(candidates: List<DexMethod>, loader: ClassLoader): Method? = candidates
        // The configuration's <clinit> also contains "music" and has no parameters. It cannot
        // be reflected as a Method: exclude it before getMethodInstance, not after reflection.
        .filter { it.isMethod && it.paramTypeNames.isEmpty() && it.returnTypeName != "void" }
        .map { it.getMethodInstance(loader) }
        .filter {
            Modifier.isStatic(it.modifiers) && Set::class.java.isAssignableFrom(it.returnType) &&
                it.returnType.isAssignableFrom(LinkedHashSet::class.java)
        }.singleOrNull()

    private data class Targets(
        val business: Method,
        val supportedApps: Method,
        val isMusic: Method,
        val isEnabled: Method,
    )
}
