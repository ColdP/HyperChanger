// SPDX-License-Identifier: AGPL-3.0-or-later
// Capability markers/behavior adapted from HyperCeiler battery hooks.
// Copyright (C) 2023-2026 HyperCeiler Contributions
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Searches the host DEX rather than relying on JADX's renamed obfuscated methods. */
internal class PowerBatteryDex(private val bridge: DexKitBridge, private val loader: ClassLoader) {
    fun methods(matcher: MethodMatcher): List<Method> = bridge.findMethod(
        FindMethod.create().matcher(matcher),
    ).map { it.getMethodInstance(loader).apply { isAccessible = true } }

    fun unique(marker: String, type: Class<*>, count: Int): Method? = methods(
        MethodMatcher.create().addUsingString(marker, StringMatchType.Equals).returnType(type).paramCount(count),
    ).singleOrNull()

    fun reader(marker: String, type: Class<*>): Method? = unique(marker, type, 0)
        ?.takeIf { Modifier.isStatic(it.modifiers) }
}

internal class PowerBatteryHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val installed = mutableSetOf<Method>()

    fun install(param: PackageLoadedParam, preferences: SharedPreferences, mainProcess: Boolean) {
        val settings = readPowerBatterySettings(preferences)
        val wanted = settings.enabled.any {
            it == PowerBatteryFeature.WIRELESS || it == PowerBatteryFeature.CHARGE_OPTIONS ||
                (mainProcess && it in setOf(PowerBatteryFeature.CONSUMPTION, PowerBatteryFeature.LOW_TEMPERATURE))
        }
        if (!wanted) return
        attempt("DEX") {
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            try {
                val dex = PowerBatteryDex(bridge, param.defaultClassLoader)
                if (PowerBatteryFeature.WIRELESS in settings) {
                    attempt("wireless capability") {
                        constant(requireNotNull(dex.reader("persist.vendor.tx.speed.control", Boolean::class.javaPrimitiveType!!)), true)
                    }
                    attempt("wireless transmitter") {
                        val methods = dex.methods(MethodMatcher.create()
                            .addUsingString("key_is_connected_super_wls_tx", StringMatchType.Equals))
                            .filter { (it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) ||
                                (it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0) }
                        val method = requireNotNull(methods.singleOrNull())
                        if (method.returnType == Boolean::class.javaPrimitiveType) constant(method, true)
                        else if (method !in installed) {
                            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                                .setId("hyperchanger:battery:wireless-transmitter")
                                .intercept { chain -> chain.proceed(arrayOf(true)) }
                            installed += method
                        }
                    }
                }
                if (PowerBatteryFeature.CHARGE_OPTIONS in settings) attempt("charging options") {
                    val methods = dex.methods(MethodMatcher.create()
                        .addUsingString("persist.vendor.smartchg", StringMatchType.Equals)
                        .returnType(Boolean::class.javaPrimitiveType!!))
                    check(methods.isNotEmpty())
                    methods.forEach { method -> attempt(method.toString()) { constant(method, true) } }
                }
                if (mainProcess && PowerBatteryFeature.LOW_TEMPERATURE in settings) attempt("cold endurance") {
                    val method = requireNotNull(dex.unique("low_temperature_extreme_endurance_support", Boolean::class.javaPrimitiveType!!, 1))
                    check(method.parameterTypes.contentEquals(arrayOf(Context::class.java)))
                    constant(method, true)
                }
                if (mainProcess && PowerBatteryFeature.CONSUMPTION in settings) attempt("consumption details") {
                    val whitelist = requireNotNull(dex.methods(MethodMatcher.create()
                        .addUsingString("ishtar", StringMatchType.Equals)
                        .addUsingString("nuwa", StringMatchType.Equals)
                        .addUsingString("fuxi", StringMatchType.Equals)
                        .returnType(Boolean::class.javaPrimitiveType!!).paramCount(0)).singleOrNull())
                    val stats = dex.methods(MethodMatcher.create().declaredClass(whitelist.declaringClass)
                        .addUsingString("not support screenPowerSplit", StringMatchType.Equals)
                        .addUsingString("PowerRankHelperHolder", StringMatchType.Equals))
                    check(stats.isNotEmpty())
                    val gates = whitelist.declaringClass.declaredMethods.filter {
                        it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
                    }
                    // This APK has exactly three gates; fail closed if the class changes shape.
                    check(gates.size == 3 && whitelist in gates)
                    gates.forEach { constant(it, it == whitelist) }
                }
            } finally { bridge.close() }
        }
    }

    private fun constant(method: Method, value: Boolean) {
        if (method in installed) return
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:battery:${method.declaringClass.name}:${method.name}")
            .intercept { value }
        installed += method
        HookModuleLogger.log(module, ModuleLog.INFO, "PowerBattery", "Installed $method = $value")
    }

    private inline fun attempt(label: String, block: () -> Unit) {
        runCatching(block).onFailure {
            HookModuleLogger.log(module, ModuleLog.WARN, "PowerBattery", "Battery hook unavailable: $label", it)
        }
    }
}
