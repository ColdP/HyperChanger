// SPDX-License-Identifier: AGPL-3.0-or-later
// Battery row preservation/readers adapted from HyperCeiler (AGPL-3.0-or-later).
// Copyright (C) 2023-2026 HyperCeiler Contributions
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI objects stay owned by the host fragment/its weak Handler; no Activity is retained here. */
internal class PowerBatteryInfoHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val installed = mutableSetOf<Method>()

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        val settings = readPowerBatterySettings(preferences)
        val more = PowerBatteryFeature.MORE_INFO in settings
        val health = PowerBatteryFeature.HEALTH in settings
        val temperature = PowerBatteryFeature.TEMPERATURE in settings
        if (!more && !health && !temperature) return
        runCatching {
            val loader = param.defaultClassLoader
            val fragmentClass = loader.loadClass("com.miui.powercenter.nightcharge.ChargeProtectFragment")
            val preferenceClass = loader.loadClass("androidx.preference.Preference")
            val groupClass = loader.loadClass("androidx.preference.PreferenceGroup")
            val textClass = loader.loadClass("miuix.preference.TextPreference")
            val find = fragmentClass.getMethod("findPreference", CharSequence::class.java)
            val getContext = fragmentClass.getMethod("getContext")
            val getKey = preferenceClass.getMethod("getKey")
            val setText = textClass.getMethod("setText", String::class.java)
            val setVisible = preferenceClass.getMethod("setVisible", Boolean::class.javaPrimitiveType)
            val handlerClass = fragmentClass.declaredClasses.single { clazz ->
                Handler::class.java.isAssignableFrom(clazz) && clazz.declaredMethods.any {
                    it.name == "handleMessage" && it.parameterTypes.contentEquals(arrayOf(Message::class.java))
                }
            }
            val fragmentReference = handlerClass.declaredFields.single { it.type == WeakReference::class.java }
                .apply { isAccessible = true }
            val keep = buildSet {
                if (health || more) add(HEALTH)
                if (temperature || more) add(TEMPERATURE)
                if (more) addAll(listOf(TODAY, CYCLES, PRODUCTION, FIRST_USE, DESIGN, FULL))
            }
            System.loadLibrary("dexkit")
            val bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            val readers = try {
                val dex = PowerBatteryDex(bridge, loader)
                fun reader(marker: String, type: Class<*>) = runCatching { requireNotNull(dex.reader(marker, type)) }
                    .onFailure { report("reader $marker", it) }.getOrNull()
                buildMap {
                    put(TEMPERATURE, dex.methods(MethodMatcher.create().declaredClass(fragmentClass)
                        .returnType(String::class.java).paramCount(1).addInvoke("Ljava/lang/Math;->abs(I)I"))
                        .singleOrNull { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(Context::class.java)) })
                    if (health) {
                        put(HEALTH, reader("getBatterySoh", String::class.java))
                        put("health_provider", reader("getBatteryHealthValue", Int::class.javaPrimitiveType!!))
                    }
                    if (more) {
                        put(CYCLES, reader("getBatteryCycleCount", String::class.java))
                        put(PRODUCTION, reader("manufacturing_date", String::class.java))
                        put(FIRST_USE, reader("first_usage_date", String::class.java))
                        put(DESIGN, reader("charge_full_design", Int::class.javaPrimitiveType!!))
                        put(FULL, reader("getBatteryChargeFull", Int::class.javaPrimitiveType!!))
                    }
                }
            } finally { bridge.close() }
            fun read(key: String): Any? = runCatching { readers[key]?.invoke(null) }.getOrNull()
            fun batteryTemperature(context: Context): String? {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val value = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                    ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: return null
                return String.format(Locale.getDefault(), UnlockIslandRuntime.tr(context,
                    "batteryTemperatureFormat", "%.1f ℃"), value / 10.0)
            }
            // Verified in this SecurityCenter build; keep its daily rollover/reset semantics.
            val todayReader = if (more) runCatching { loader.loadClass("qh.c").getDeclaredMethod("g")
                .takeIf { it.returnType == Int::class.javaPrimitiveType }?.apply { isAccessible = true } }.getOrNull() else null

            fun refresh(fragment: Any) {
                val context = getContext.invoke(fragment) as? Context ?: return
                val category = find.invoke(fragment, CATEGORY) ?: return
                fun row(key: String): Any? = find.invoke(fragment, key)
                fun text(key: String, value: String) {
                    row(key)?.let { setText.invoke(it, value); setVisible.invoke(it, true) }
                }
                fun tr(key: String, fallback: String) = UnlockIslandRuntime.tr(context, key, fallback)
                if (temperature || more) {
                    val value = if (temperature) batteryTemperature(context) else runCatching {
                        readers[TEMPERATURE]?.invoke(null, context) as? String
                    }.getOrNull()
                    text(TEMPERATURE, value ?: "--")
                }
                if (health) {
                    val value = PowerBatteryValues.health(read(HEALTH)) ?: PowerBatteryValues.health(read("health_provider"))
                    text(HEALTH, value?.let { NumberFormat.getPercentInstance().format(it / 100.0) } ?: "--")
                }
                if (!more) return
                val cycles = read(CYCLES)?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
                text(CYCLES, cycles?.let { NumberFormat.getIntegerInstance().format(it) } ?: "--")
                val today = runCatching { todayReader?.invoke(null) as? Int }.getOrNull()?.takeIf { it >= 0 }
                val plural = context.resources.getIdentifier("power_center_battery_charge_number_new", "plurals", context.packageName)
                text(TODAY, if (today != null && plural != 0) context.resources.getQuantityString(plural, today, today) else "--")
                for ((key, property) in listOf(PRODUCTION to 7, FIRST_USE to 8)) {
                    val raw = read(key) as? String
                    val name = if (key == PRODUCTION) "manufacturing_date" else "first_usage_date"
                    val native = PowerBatteryValues.date(raw)
                    val managerDate = if (native == null) runCatching {
                        val seconds = context.getSystemService(BatteryManager::class.java)?.getLongProperty(property) ?: 0L
                        if (seconds in 1..253402300799L) SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(seconds * 1000L)) else null
                    }.getOrNull() else null
                    text(key, native ?: PowerBatteryValues.date(managerDate)
                        ?: PowerBatteryValues.date(sysfs(name))
                        ?: (if (key == PRODUCTION) PowerBatteryValues.date(sysfs("production_date")) else null) ?: "--")
                }
                val count = groupClass.getMethod("getPreferenceCount").invoke(category) as Int
                val at = groupClass.getMethod("getPreference", Int::class.javaPrimitiveType)
                val order = preferenceClass.getMethod("getOrder")
                val maxOrder = (0 until count).maxOfOrNull { order.invoke(at.invoke(category, it)) as Int } ?: 100
                val nextOrder = maxOrder.coerceAtMost(Int.MAX_VALUE - 2) + 1
                for ((index, key) in listOf(DESIGN, FULL).withIndex()) {
                    if (row(key) == null) {
                        val pref = textClass.getConstructor(Context::class.java).newInstance(context)
                        preferenceClass.getMethod("setKey", String::class.java).invoke(pref, key)
                        preferenceClass.getMethod("setTitle", CharSequence::class.java).invoke(pref,
                            if (key == DESIGN) tr("batteryDesignCapacity", "电池设计容量") else tr("batteryFullCapacity", "当前满充容量"))
                        preferenceClass.getMethod("setSelectable", Boolean::class.javaPrimitiveType).invoke(pref, false)
                        preferenceClass.getMethod("setOrder", Int::class.javaPrimitiveType).invoke(pref, nextOrder + index)
                        groupClass.getMethod("addPreference", preferenceClass).invoke(category, pref)
                    }
                    val name = if (key == DESIGN) "charge_full_design" else "charge_full"
                    val capacity = PowerBatteryValues.capacityMah(read(key)) ?: PowerBatteryValues.capacityMah(sysfs(name))
                    text(key, capacity?.let { String.format(Locale.getDefault(), tr("batteryCapacityFormat", "%d mAh"), it) } ?: "--")
                }
            }

            if (temperature) readers[TEMPERATURE]?.let { method ->
                register(method, "temperature") { chain ->
                    val result = chain.proceed()
                    (chain.getArg(0) as? Context)?.let(::batteryTemperature) ?: result
                }
            }
            register(groupClass.getDeclaredMethod("removePreference", preferenceClass), "keep-rows") { chain ->
                val key = chain.getArg(0)?.let { getKey.invoke(it) }
                if (getKey.invoke(chain.thisObject) == CATEGORY && key in keep) false else chain.proceed()
            }
            register(fragmentClass.getDeclaredMethod("onCreatePreferences", Bundle::class.java, String::class.java), "create") { chain ->
                val result = chain.proceed()
                runCatching { chain.thisObject?.let(::refresh) }.onFailure { report("create refresh", it) }
                result
            }
            register(handlerClass.getDeclaredMethod("handleMessage", Message::class.java), "refresh") { chain ->
                val result = chain.proceed()
                runCatching {
                    val message = chain.getArg(0) as? Message
                    if (message?.what == 1 || message?.what == 2) {
                        val reference = fragmentReference.get(chain.thisObject) as? WeakReference<*>
                        reference?.get()?.let(::refresh)
                    }
                }.onFailure { report("handler refresh", it) }
                result
            }
        }.onFailure { report("installation", it) }
    }

    private fun register(method: Method, id: String, interceptor: io.github.libxposed.api.XposedInterface.Hooker) {
        if (method in installed) return
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:battery-info:$id").intercept(interceptor)
        installed += method
        HookModuleLogger.log(module, ModuleLog.INFO, "PowerBatteryInfo", "Installed $method")
    }

    private fun sysfs(name: String): String? = listOf("battery", "bms").firstNotNullOfOrNull { node ->
        runCatching { File("/sys/class/power_supply/$node/$name").bufferedReader().use { it.readLine() } }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "-1" && it != "0" }
    }

    private fun report(label: String, error: Throwable) = HookModuleLogger.log(
        module, ModuleLog.WARN, "PowerBatteryInfo", "Battery info unavailable: $label", error,
    )

    private companion object {
        const val CATEGORY = "preference_key_category_battery_info"
        const val HEALTH = "reference_battery_health"
        const val TEMPERATURE = "reference_current_temp"
        const val TODAY = "reference_toady_charge_time"
        const val CYCLES = "reference_cycle_count"
        const val PRODUCTION = "reference_production_date"
        const val FIRST_USE = "reference_first_use_date"
        const val DESIGN = "reference_battery_design_capacity"
        const val FULL = "reference_battery_full_capacity"
    }
}
