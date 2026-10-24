// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Proxy

/**
 * Owns the independent control-center groups migrated from HyperSystemUiModule.
 * Package dispatch, retry scheduling and shared class discovery stay at their original call sites.
 * Context providers read the existing shared state when needed, without copying or exposing it.
 */
internal class ControlCenterHooks(
    private val module: HyperSystemUiModule,
    private val readContext: (Any) -> Context?,
    private val applicationContext: () -> Context?,
) {
    private val controlCenterHeaderHeightHook by lazy { ControlCenterHeaderHeightHook(module) }
    private val controlCenterCarrierHook by lazy { ControlCenterCarrierHook(module) }
    private val controlCenterClockHook by lazy { ControlCenterClockHook(module) }

    private var customTileHookInstalled = false
    private var customPluginTileHookInstalled = false
    private var customTileRetryScheduled = false

    fun installHeaderHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        controlCenterHeaderHeightHook.install(classLoader, preferences)
        controlCenterCarrierHook.install(classLoader, preferences)
        controlCenterClockHook.install(classLoader, preferences)
    }

    fun onHeaderClassLoaded(loadedClass: Class<*>, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        controlCenterHeaderHeightHook.onClassLoaded(loadedClass, preferences)
        controlCenterCarrierHook.onClassLoaded(loadedClass, preferences)
        controlCenterClockHook.onClassLoaded(loadedClass, preferences)
    }

    /** Clears the "force new control center" flag so the legacy shade comes back. */
    fun installOldControlCenterHook(loader: ClassLoader, prefs: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (!readSettingsAppScopePreferences(prefs).oldControlCenter) return
        listOf(
            "com.miui.systemui.controlcenter.data.repository.ControlCenterSettingsRepositoryImpl" to "forceUseControlCenter",
            "com.android.systemui.controlcenter.policy.ControlCenterControllerImpl" to "forceUseControlCenterPanel",
        ).forEach { (className, fieldName) ->
            runCatching {
                val cls = loader.loadClass(className)
                cls.declaredConstructors.forEach { constructor ->
                    module.hook(constructor)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:old-cc-${cls.simpleName}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching {
                                cls.getDeclaredField(fieldName).apply { isAccessible = true }
                                    .setBoolean(chain.thisObject, false)
                            }
                            result
                        }
                }
            }
        }
    }

    private fun invokeNoArgResult(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
    }.getOrNull()

    private fun findTileInterface(type: Class<*>): Class<*>? {
        type.interfaces.forEach { iface ->
            if (iface.name == "com.android.systemui.plugins.miui.qs.MiuiQSTile") return iface
            findTileInterface(iface)?.let { return it }
        }
        return type.superclass?.let(::findTileInterface)
    }

    fun installCustomTileHooks(loader: ClassLoader, prefs: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (customTileHookInstalled) {
            installPluginTileHook(loader, prefs)
            return
        }
        val factory = runCatching { loader.loadClass("com.android.systemui.qs.tileimpl.MiuiQSFactory") }.getOrNull()
        if (factory == null) {
            if (!customTileRetryScheduled) {
                customTileRetryScheduled = true
                Handler(Looper.getMainLooper()).postDelayed({
                    customTileRetryScheduled = false
                    installCustomTileHooks(loader, prefs)
                }, 1500L)
            }
            return
        }
        val create = factory.methods.firstOrNull { it.name == "createTile" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            ?: return
        module.hook(create).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:custom-tiles").intercept { chain ->
            val spec = chain.getArg(0) as? String
            if (spec == "custom_5G" || (spec == "custom_GMS" && prefs.getBoolean(KEY_CONTROL_CENTER_GMS_TILE_ENABLED, false))) {
                injectPluginTile(chain.thisObject, loader, prefs, spec)
            }
            chain.proceed()
        }
        val pluginInstalled = installPluginTileHook(loader, prefs)
        module.hook(Resources::class.java.getMethod("getString", Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:custom-tile-stock").intercept { chain ->
                val value = chain.proceed()
                val resources = chain.thisObject as? Resources
                val id = chain.getArg(0) as? Int
                val name = runCatching { id?.let { resources?.getResourceEntryName(it) } }.getOrNull()
                if (value is String && name?.contains("quick_settings_tiles_stock") == true) {
                    value.split(',').map(String::trim).filter(String::isNotEmpty).toMutableList().apply {
                        if (prefs.getInt(KEY_CONTROL_CENTER_5G_TILE_MODE, 0) != 0 && "custom_5G" !in this) add("custom_5G")
                        if (prefs.getBoolean(KEY_CONTROL_CENTER_GMS_TILE_ENABLED, false) && "custom_GMS" !in this) add("custom_GMS")
                    }.joinToString(",")
                } else value
            }
        customTileHookInstalled = true
        if (!pluginInstalled && !customTileRetryScheduled) {
            customTileRetryScheduled = true
            Handler(Looper.getMainLooper()).postDelayed({
                customTileRetryScheduled = false
                installCustomTileHooks(loader, prefs)
            }, 1500L)
        }
    }

    private fun installPluginTileHook(loader: ClassLoader, prefs: SharedPreferences): Boolean {
        if (customPluginTileHookInstalled) return true
        return runCatching {
            val plugin = loader.loadClass("miui.systemui.quicksettings.LocalMiuiQSTilePlugin")
            val method = plugin.methods.first { it.name == "getAllPluginTiles" && it.parameterCount == 0 }
            module.hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:custom-plugin-tiles").intercept { chain ->
                val rawMap = chain.proceed()
                val map = rawMap as? MutableMap<Any?, Any?> ?: return@intercept rawMap
                val host = map.values.firstOrNull { it != null } ?: return@intercept map
                val hostLoader = host.javaClass.classLoader ?: loader
                if (prefs.getInt(KEY_CONTROL_CENTER_5G_TILE_MODE, 0) != 0 && !map.containsKey("custom_5G")) map["custom_5G"] = proxyTile(host, hostLoader, prefs, "custom_5G")
                if (prefs.getBoolean(KEY_CONTROL_CENTER_GMS_TILE_ENABLED, false) && !map.containsKey("custom_GMS")) map["custom_GMS"] = proxyTile(host, hostLoader, prefs, "custom_GMS")
                map
            }
            customPluginTileHookInstalled = true
            true
        }.getOrDefault(false)
    }

    private fun injectPluginTile(factory: Any?, loader: ClassLoader, prefs: SharedPreferences, spec: String?) = runCatching {
        val field = generateSequence(factory?.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == "qSTilePluginInteractor" } ?: return@runCatching
        field.isAccessible = true
        val interactor = field.get(factory)?.let { invokeNoArgResult(it, "get") } ?: return@runCatching
        val pluginField = interactor.javaClass.declaredFields.firstOrNull { it.name == "miuiQSTilePlugin" } ?: return@runCatching
        pluginField.isAccessible = true
        val map = invokeNoArgResult(pluginField.get(interactor), "getAllPluginTiles") as? MutableMap<Any?, Any?> ?: return@runCatching
        if (spec == "custom_5G" && !map.containsKey(spec)) map.values.firstOrNull()?.let { map[spec] = proxyTile(it, it.javaClass.classLoader ?: loader, prefs, spec) }
        if (spec == "custom_GMS" && !map.containsKey(spec)) map.values.firstOrNull()?.let { map[spec] = proxyTile(it, it.javaClass.classLoader ?: loader, prefs, spec) }
    }

    private fun proxyTile(host: Any, loader: ClassLoader, prefs: SharedPreferences, spec: String): Any {
        val iface = findTileInterface(host.javaClass) ?: error("MiuiQSTile interface unavailable")
        val context = readContext(host) ?: applicationContext() ?: error("tile context unavailable")
        val state = invokeNoArgResult(host, "getState")?.javaClass?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance() ?: error("tile state unavailable")
        val callbacks = mutableListOf<Any>()
        fun refresh() {
            val gms = spec == "custom_GMS"
            val enabled = if (gms) isGmsEnabled(context) else isUserFiveGEnabled(context)
            state.javaClass.getField("state").setInt(state, if (enabled) 2 else 1)
            state.javaClass.getField("label").set(state, if (gms) "Google 服务" else "5G")
            state.javaClass.getField("contentDescription").set(state, if (gms) "Google 服务" else "5G")
            // DrawableIcon moved from quicksettings to controlcenter.qs in 18.3.x.
            // Resolve both names because the plugin and SystemUI can use different
            // class loaders during a SystemUI/plugin upgrade.
            val iconClass = sequenceOf(
                "miui.systemui.controlcenter.qs.DrawableIcon",
                "miui.systemui.quicksettings.DrawableIcon"
            ).mapNotNull { name ->
                runCatching { host.javaClass.classLoader.loadClass(name) }.getOrNull()
                    ?: runCatching { loader.loadClass(name) }.getOrNull()
            }.firstOrNull() ?: error("DrawableIcon unavailable")
            val drawable = if (gms) loadTileDrawable(context, R.drawable.ic_control_center_google) else loadTileDrawable(context, when (prefs.getInt(KEY_CONTROL_CENTER_5G_TILE_MODE, 1)) { 2 -> R.drawable.ic_control_center_5g_semibold; 3 -> R.drawable.ic_control_center_5g_black; 4 -> R.drawable.ic_control_center_5g_signal; else -> R.drawable.ic_control_center_5g_regular })
            state.javaClass.getField("icon").set(state, iconClass.getConstructor(Drawable::class.java).newInstance(drawable))
            callbacks.toList().forEach { cb -> runCatching { cb.javaClass.methods.firstOrNull { it.name == "onStateChanged" && it.parameterCount == 1 }?.invoke(cb, state) } }
        }
        refresh()
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args -> when (method.name) {
            "getTileSpec" -> spec
            "isAvailable" -> if (spec == "custom_GMS") hasGms(context) else prefs.getInt(KEY_CONTROL_CENTER_5G_TILE_MODE, 0) != 0
            "getState" -> state
            // PluginTile calls this on every update and expects a fresh State.
            "newTileState" -> state.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            "refreshState" -> { refresh(); null }
            "addCallback" -> { args?.firstOrNull()?.let { if (it !in callbacks) callbacks += it }; refresh(); null }
            "removeCallback" -> { args?.firstOrNull()?.let(callbacks::remove); null }
            "setListening" -> null
            "composeChangeAnnouncement" -> spec
            // This value is unboxed by PluginTile; returning null here crashes SystemUI.
            "getMetricsCategory" -> 0
            "handleClick" -> { if (spec == "custom_GMS") toggleGms(context) else setUserFiveGEnabled(context, !isUserFiveGEnabled(context)); refresh(); null }
            "getLongClickIntent" -> if (spec == "custom_GMS") Intent().setClassName("com.miui.securitycenter", "com.miui.googlebase.ui.GmsCoreSettings") else Intent().setClassName("com.android.phone", "com.android.phone.settings.MiuiFiveGNetworkSetting")
            "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.firstOrNull(); "toString" -> "HyperChanger-$spec"; else -> null
        } }
    }

    private fun loadTileDrawable(context: Context, id: Int): Drawable = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources.getDrawable(id, null).mutate()
    private fun hasGms(c: Context) = runCatching { c.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false)
    private fun isGmsEnabled(c: Context) = runCatching { c.packageManager.getApplicationEnabledSetting("com.google.android.gms") != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }.getOrDefault(false)
    private fun toggleGms(c: Context) { val next = if (isGmsEnabled(c)) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED; listOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending").forEach { runCatching { c.packageManager.setApplicationEnabledSetting(it, next, 0) } } }
    private fun isUserFiveGEnabled(c: Context) = runCatching { val k = Class.forName("miui.telephony.TelephonyManager"); k.getDeclaredMethod("isUserFiveGEnabled").invoke(k.getDeclaredMethod("getDefault").invoke(null)) as Boolean }.getOrElse { Settings.Global.getInt(c.contentResolver, "fiveg_user_enable", 1) != 0 }
    private fun setUserFiveGEnabled(c: Context, value: Boolean) { runCatching { val k = Class.forName("miui.telephony.TelephonyManager"); k.getDeclaredMethod("setUserFiveGEnabled", Boolean::class.javaPrimitiveType).invoke(k.getDeclaredMethod("getDefault").invoke(null), value) }.onFailure { Settings.Global.putInt(c.contentResolver, "fiveg_user_enable", if (value) 1 else 0) } }

}
