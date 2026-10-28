// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.hook.AppBottomNavHooks
import btm.m.liquidglass.hook.LuckinAntiDetectionHooks
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Executable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import org.json.JSONObject

/**
 * Owns independent app navigation, Settings, MiSettings, MiSound, MiShare and rear-screen hooks.
 * Package/process dispatch and lifecycle boundaries remain in the module. Framework hooks
 * retain their original implementation and both original entry points there.
 * The Settings resource cache keeps the exact original monitor supplied by the module.
 */
internal class OthersHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val settingsResourcesLock: Any,
    private val logTag: String,
) {
    private var customRearScreenWidgetRegistrationInstalled = false
    private var rearScreenLeftSwipeInstalled = false
    private var islandSimulationRearScreenInstalled = false
    private val rearMiniPlayerHooks = RearMiniPlayerHooks(module)
    private val screenshotHooks = ScreenshotHooks(module, hookDeoptimized, logTag)

    fun installScreenshotHooks(classLoader: ClassLoader, preferences: SharedPreferences) =
        screenshotHooks.install(classLoader, preferences)

    fun installRearMiniPlayerMusicHooks(preferences: SharedPreferences) = rearMiniPlayerHooks.installMusic(preferences)

    fun installRearMiniPlayerSubScreenHooks(preferences: SharedPreferences) = rearMiniPlayerHooks.installRear(preferences)

    /** Only our simulator notifications enter the verified SubScreen native card pipeline. */
    fun installIslandSimulationRearScreenHook(classLoader: ClassLoader) {
        if (islandSimulationRearScreenInstalled) return
        runCatching {
            val listenerClass = classLoader.loadClass("com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService")
            // Use DEX names from JADX's "renamed from" comments, not its Java aliases.
            val managerClass = classLoader.loadClass("U1.r0")
            val paramsClass = classLoader.loadClass("f2.a")
            val descriptorClass = classLoader.loadClass("I.g")
            val singleton = classLoader.loadClass("U1.p0").getField("a")
            val handlerField = managerClass.getField("F")
            val enabled = listenerClass.getDeclaredMethod("d")
            val parse = classLoader.loadClass("o2.c").getDeclaredMethod("k", android.os.Bundle::class.java)
            val descriptor = descriptorClass.getConstructor(java.io.Serializable::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val add = managerClass.getDeclaredMethod("r", String::class.java, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, String::class.java, descriptorClass,
                android.os.Bundle::class.java, paramsClass, Boolean::class.javaPrimitiveType)
            val remove = managerClass.getDeclaredMethod("l", String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, String::class.java)
            fun forward(sbn: android.service.notification.StatusBarNotification, removed: Boolean) {
                if (sbn.packageName != BuildConfig.APPLICATION_ID || sbn.tag != ISLAND_SIMULATION_TAG ||
                    sbn.user != android.os.Process.myUserHandle()) return
                val extras = android.os.Bundle(sbn.notification.extras)
                val type = IslandSimulationType.entries.firstOrNull {
                    it.key == extras.getString(ISLAND_SIMULATION_TYPE_EXTRA)
                } ?: return
                if (sbn.id != type.ordinal) return
                val handler = handlerField.get(null) as Handler
                handler.post {
                    runCatching {
                        val manager = singleton.get(null)
                        val rear = extras.getString("miui.rear.param")?.let(::JSONObject)
                        val business = rear?.optString("business")
                        val allowed = (type == IslandSimulationType.DELIVERY && business == "foodDelivery") ||
                            (type == IslandSimulationType.MOVIE_LOGISTICS && business == "Logistics")
                        if (removed || !allowed) {
                            remove.invoke(manager, sbn.packageName, sbn.id, 0, sbn.key)
                        } else if (enabled.invoke(null) == true) {
                            add.invoke(manager, "${sbn.packageName}:${sbn.id}", sbn.packageName, sbn.id,
                                sbn.key, business, descriptor.newInstance(business, 0, 500), extras, parse.invoke(null, extras), true)
                        }
                    }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not forward simulator notification to rear screen", error) }
                }
            }
            // Resolve all signatures before registering. Each original callback runs exactly once.
            val posted = listenerClass.getDeclaredMethod("onNotificationPosted", android.service.notification.StatusBarNotification::class.java)
            val removed = listenerClass.getDeclaredMethod("onNotificationRemoved", android.service.notification.StatusBarNotification::class.java)
            val connected = listenerClass.getDeclaredMethod("onListenerConnected")
            islandSimulationRearScreenInstalled = true
            hookDeoptimized(posted).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("island-simulation:rear-post").intercept { chain ->
                    val result = chain.proceed()
                    (chain.getArg(0) as? android.service.notification.StatusBarNotification)?.let { forward(it, false) }
                    result
                }
            hookDeoptimized(removed).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("island-simulation:rear-remove").intercept { chain ->
                    val result = chain.proceed()
                    (chain.getArg(0) as? android.service.notification.StatusBarNotification)?.let { forward(it, true) }
                    result
                }
            hookDeoptimized(connected).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("island-simulation:rear-reconnect").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        (chain.thisObject as? android.service.notification.NotificationListenerService)
                            ?.activeNotifications?.forEach { forward(it, false) }
                    }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not restore simulator rear-screen notifications", error) }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed simulator-only rear-screen notification bridge")
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install simulator rear-screen bridge for this SubScreen version", error) }
    }

    private class RearSwipeState {
        val gesture = RearScreenLeftSwipeGesture()
        var animator: ValueAnimator? = null
        var blur = 0f
        var ownsBlur = false
        var packageName = ""
        var suppressUntilUp = false
    }

    /** SubScreenLauncher only; unrelated rear-screen installers keep their own boundaries. */
    fun installRearScreenLeftSwipeHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (rearScreenLeftSwipeInstalled) return
        runCatching {
            val launcher = classLoader.loadClass("com.xiaomi.subscreencenter.SubScreenLauncher")
            val dispatch = launcher.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            // Verified in SubScreen RELEASE-1.0.2609181659: public background-blur API.
            val blurMethod = launcher.getDeclaredMethod("a", String::class.java, Float::class.javaPrimitiveType)
            val blurProgress = launcher.getDeclaredMethod("c")
            // JADX calls this f5788Z; its original DEX field name is Z.
            val acceptsGestures = launcher.getDeclaredField("Z").apply { isAccessible = true }
            val lifecycle = listOf("onPause", "onResume", "onDestroy").associateWith { launcher.getDeclaredMethod(it) }
            val states = java.util.WeakHashMap<Activity, RearSwipeState>()
            fun setBlur(activity: Activity, state: RearSwipeState, value: Float) {
                if (runCatching { blurMethod.invoke(activity, "gesture", value) }.isSuccess) {
                    state.blur = value
                    state.ownsBlur = value > 0f
                }
            }
            fun reset(activity: Activity, state: RearSwipeState, animate: Boolean) {
                state.gesture.reset()
                state.animator?.cancel()
                state.animator = null
                if (!state.ownsBlur) return
                if (!animate || state.blur <= 0f) {
                    setBlur(activity, state, 0f)
                } else {
                    state.animator = ValueAnimator.ofFloat(state.blur, 0f).apply {
                        duration = 180
                        addUpdateListener { setBlur(activity, state, it.animatedValue as Float) }
                        start()
                    }
                }
            }
            // Resolve all required members before installing any callback.
            module.hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-screen:left-swipe:touch")
                .intercept { chain ->
                    val activity = chain.thisObject as? Activity ?: return@intercept chain.proceed()
                    val event = chain.getArg(0) as? MotionEvent ?: return@intercept chain.proceed()
                    val state = states.getOrPut(activity) { RearSwipeState() }
                    val action = event.actionMasked
                    val config = preferences.readRearScreenGestureSettings()
                    if (action == MotionEvent.ACTION_DOWN) {
                        state.suppressUntilUp = false
                        reset(activity, state, false)
                        state.packageName = ""
                        val result = chain.proceed()
                        if (config.enabled && config.packageName.isNotBlank() &&
                            acceptsGestures.getBoolean(activity) && (blurProgress.invoke(activity) as Float) <= 0f) {
                            state.packageName = config.packageName
                            state.gesture.begin(event.x, event.y, activity.window.decorView.width)
                        }
                        return@intercept result
                    }
                    if (state.suppressUntilUp) {
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) state.suppressUntilUp = false
                        return@intercept true
                    }
                    val wasCaptured = state.gesture.captured
                    if (!config.enabled || config.packageName != state.packageName || event.pointerCount != 1 ||
                        action == MotionEvent.ACTION_CANCEL) {
                        reset(activity, state, true)
                        if (wasCaptured) {
                            state.suppressUntilUp = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                            return@intercept true
                        }
                        return@intercept chain.proceed()
                    }
                    if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                        state.gesture.move(event.x, event.y, activity.resources.displayMetrics.density)
                    }
                    if (!state.gesture.captured) return@intercept chain.proceed()
                    if (!wasCaptured) {
                        // The system/child already received DOWN. Cancel it exactly once.
                        val cancel = MotionEvent.obtain(event).apply { setAction(MotionEvent.ACTION_CANCEL) }
                        try { chain.proceed(arrayOf(cancel)) } finally { cancel.recycle() }
                    }
                    setBlur(activity, state, state.gesture.progress * .5f)
                    if (action == MotionEvent.ACTION_UP) {
                        if (state.gesture.finish()) {
                            val launched = runCatching {
                                val intent = activity.packageManager.getLaunchIntentForPackage(state.packageName)
                                    ?: return@runCatching false
                                setBlur(activity, state, .5f)
                                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                true
                            }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Rear-screen left-swipe launch failed", it) }.getOrDefault(false)
                            if (!launched) reset(activity, state, true)
                        } else reset(activity, state, true)
                    }
                    true
                }
            lifecycle.forEach { (name, method) ->
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-screen:left-swipe:$name")
                    .intercept { chain ->
                        val activity = chain.thisObject as? Activity
                        if (activity != null) states.remove(activity)?.let { reset(activity, it, false) }
                        chain.proceed()
                    }
            }
            rearScreenLeftSwipeInstalled = true
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed SubScreenLauncher left-swipe hooks")
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Rear-screen left-swipe hook unavailable on this build", it) }
    }

    fun installAppNavigation(param: PackageLoadedParam) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NAVIGATION)) return
        val packageName = param.packageName
        if (packageName != "com.xiaomi.shop" && packageName != "com.mipay.wallet" &&
            packageName != "com.mi.health" && packageName != "com.apple.android.music" &&
            packageName != "com.sina.weibo" && packageName != "com.xingin.xhs" &&
            packageName != "com.lucky.luckyclient" && packageName != "com.tencent.wemeet.app" &&
            packageName != "cmb.pb" && packageName != "com.tencent.mm"
            && packageName != "com.xunmeng.pinduoduo"
        ) return
        if (!runCatching { android.app.Application.getProcessName() == packageName }.getOrDefault(false)) return
        runCatching {
            val prefs = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            if (!ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_MODULE_ENABLED, true)) return
            if (packageName == "com.mi.health" && ScopedSettings.getBoolean(
                    prefs,
                    packageName,
                    ScopedSettings.KEY_REMOVE_WATCH_FACE_TRIAL_LIMIT,
                    false,
                )
            ) {
                runCatching {
                    installXiaomiHealthWatchFaceTrialBypass(param.defaultClassLoader)
                }.onFailure { error ->
                    btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not install Xiaomi Health watch-face trial hook", error)
                }
            }
            if (!ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_ENABLED, true)) return
            val style = ScopedSettings.getString(prefs, packageName, NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE)
            val label = ScopedSettings.getLabelMode(prefs, packageName, LabelMode.DEFAULT_VALUE)
            val color = ScopedSettings.getString(prefs, packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE)
            val blur = ScopedSettings.getBlurRadius(prefs, packageName, style, 18)
            val advanced = ScopedSettings.getBoolean(prefs, packageName, ScopedSettings.KEY_ADVANCED_MATERIAL, true)
            val accent = btm.m.liquidglass.NavigationAccentSettings.getColor(prefs, packageName)
            when (packageName) {
                "com.xiaomi.shop" -> AppBottomNavHooks.installXiaomiStore(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.mipay.wallet" -> AppBottomNavHooks.installXiaomiWallet(module, param.defaultClassLoader, blur, label, style, advanced, color, ScopedSettings.getWalletVisibleTabs(prefs), accent)
                "com.mi.health" -> {
                    AppBottomNavHooks.installXiaomiHealth(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                    AppBottomNavHooks.installXiaomiHealthWatchFaceMarket(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                }
                "com.apple.android.music" -> AppBottomNavHooks.installAppleMusic(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.sina.weibo" -> AppBottomNavHooks.installWeibo(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.xingin.xhs" -> AppBottomNavHooks.installXiaohongshu(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.tencent.wemeet.app" -> AppBottomNavHooks.installTencentMeeting(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "cmb.pb" -> AppBottomNavHooks.installCmbBank(module, blur, label, style, advanced, color, accent)
                "com.tencent.mm" -> AppBottomNavHooks.installWeChat(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.xunmeng.pinduoduo" -> AppBottomNavHooks.installPinduoduo(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                "com.lucky.luckyclient" -> {
                    LuckinAntiDetectionHooks.install(module)
                    AppBottomNavHooks.installLuckinCoffee(module, param.defaultClassLoader, blur, label, style, advanced, color, accent)
                }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed app navigation hooks for $packageName")
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not install app navigation hooks for $packageName", error) }
    }

    /** Override MiShare's app-side AirDrop availability checks when enabled for its scope. */
    fun installAirDropForceEnableHooks(param: PackageLoadedParam) {
        val preferences = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (!preferences.getBoolean(KEY_AIRDROP_FORCE_ENABLE, false)) return
        installAirDropDeviceProfileHooks()
        val availability = param.defaultClassLoader.loadClass(AIRDROP_AVAILABILITY_CLASS)
        listOf("c", "e").forEach { methodName ->
            val method = availability.getDeclaredMethod(methodName).apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:airdrop-force-enable:$methodName")
                .intercept { true }
        }
        // The settings entry immediately goes through AirDropCtaGateActivity. When the
        // force-enable switch is on, allow that gate to continue to the actual Mi Share page;
        // the transfer implementation and its runtime capability checks remain untouched.
        val ctaGate = param.defaultClassLoader.loadClass("com.miui.mishare.connectivity.R0")
        val ctaMethod = ctaGate.getDeclaredMethod("p", android.content.Context::class.java)
            .apply { isAccessible = true }
        module.hook(ctaMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:airdrop-force-enable:cta")
            .intercept { true }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed MiShare AirDrop availability hooks in ${param.packageName}")
    }

    /** Keep the Xiaomi 18 Pro Max / madrid identity local to the Mi Share process. */
    private fun installAirDropDeviceProfileHooks() {
        val spoofedProperties = mapOf(
            "ro.product.model" to "Xiaomi 18 Pro Max",
            "ro.product.marketname" to "Xiaomi 18 Pro Max",
            "ro.product.device" to "madrid",
            "ro.product.name" to "madrid",
            "ro.build.product" to "madrid",
            "ro.product.brand" to "Xiaomi",
            "ro.product.manufacturer" to "Xiaomi",
        )
        runCatching {
            val properties = Class.forName("android.os.SystemProperties", false, null)
            properties.declaredMethods
                .filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name in setOf("get", "getBoolean", "getInt", "getLong") &&
                        method.parameterTypes.firstOrNull() == String::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:mishare-device-profile:${method.name}:${method.parameterTypes.size}")
                        .intercept { chain ->
                            val value = spoofedProperties[chain.getArg(0) as? String]
                                ?: return@intercept chain.proceed()
                            when (method.name) {
                                "get" -> value
                                "getBoolean" -> value.equals("true", ignoreCase = true)
                                "getInt" -> value.toIntOrNull() ?: 0
                                "getLong" -> value.toLongOrNull() ?: 0L
                                else -> chain.proceed()
                            }
                        }
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install Mi Share property spoof", error) }

        runCatching {
            mapOf(
                "MODEL" to "Xiaomi 18 Pro Max",
                "DEVICE" to "madrid",
                "PRODUCT" to "madrid",
                "BRAND" to "Xiaomi",
                "MANUFACTURER" to "Xiaomi",
            ).forEach { (fieldName, value) ->
                android.os.Build::class.java.getDeclaredField(fieldName).apply {
                    isAccessible = true
                    set(null, value)
                }
            }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not update Mi Share Build identity", error) }
    }

    private fun installXiaomiHealthWatchFaceTrialBypass(loader: ClassLoader) {
        val moduleClass = loader.loadClass("com.xiaomi.wearable.yrn.modules.WatchFaceModule")
        val promiseClass = loader.loadClass("com.facebook.react.bridge.Promise")
        val method = moduleClass.getDeclaredMethod(
            "getEnterFaceMarketTime",
            String::class.java,
            promiseClass,
        ).apply { isAccessible = true }
        val resolve = promiseClass.getMethod("resolve", Any::class.java)
        hookDeoptimized(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:mi-health-watch-face-unlimited-trial")
            .intercept { chain ->
                val promise = chain.getArg(1) ?: return@intercept chain.proceed()
                val oneHundredYearsMs = 100L * 365L * 24L * 60L * 60L * 1000L
                resolve.invoke(promise, (System.currentTimeMillis() + oneHundredYearsMs).toDouble())
                null
            }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed Xiaomi Health unlimited watch-face trial hook")
    }

    private fun settingsField(target: Any, name: String): java.lang.reflect.Field? = generateSequence(target.javaClass) { it.superclass }
        .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()

    private fun getLongField(target: Any, name: String): Long = settingsField(target, name)?.get(target).let { value ->
        when (value) { is Number -> value.toLong(); else -> 0L }
    }

    private fun getIntField(target: Any, name: String): Int = settingsField(target, name)?.get(target).let { value ->
        when (value) { is Number -> value.toInt(); else -> 0 }
    }

    private fun setLongField(target: Any, name: String, value: Long) { settingsField(target, name)?.set(target, value) }

    private fun setIntField(target: Any, name: String, value: Int) { settingsField(target, name)?.set(target, value) }

    private fun setObjectField(target: Any, name: String, value: Any?) { settingsField(target, name)?.set(target, value) }

    private fun attachModuleResources(context: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val loader = synchronized(settingsResourcesLock) {
            settingsModuleResourcesLoader ?: run {
                val apk = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir
                val provider = ParcelFileDescriptor.open(java.io.File(apk), ParcelFileDescriptor.MODE_READ_ONLY).use {
                    ResourcesProvider.loadFromApk(it)
                }
                ResourcesLoader().apply { addProvider(provider) }.also { settingsModuleResourcesLoader = it }
            }
        }
        runCatching { context.resources.addLoaders(loader) }
        true
    }.getOrDefault(false)

    fun installSettingsAppEntryHook(loader: ClassLoader, prefs: SharedPreferences) {
        val settingsClass = runCatching { loader.loadClass("com.android.settings.MiuiSettings") }.getOrNull() ?: return
        val update = settingsClass.methods.firstOrNull {
            it.name == "updateHeaderList" && it.parameterTypes.size == 1 && java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return
        module.hook(update).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-app-entry").intercept { chain ->
            val result = chain.proceed()
            val headers = chain.getArg(0) as? MutableList<Any?> ?: return@intercept result
            // Read on every rebuild so changing the dropdown takes effect without restarting Settings.
            headers.removeAll { header -> runCatching { getLongField(header!!, "id") == SETTINGS_HEADER_ID }.getOrDefault(false) }
            val savedPosition = prefs.getInt(KEY_SETTINGS_APP_ENTRY_POSITION, 0).coerceIn(0, 3)
            if (savedPosition == 0) {
                return@intercept result
            }
            val settingsContext = (chain.thisObject as? android.app.Activity)?.baseContext ?: return@intercept result
            val headerClass = runCatching {
                loader.loadClass("com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header")
            }.getOrNull() ?: return@intercept result
            val header = runCatching { headerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull()
                ?: return@intercept result
            setLongField(header, "id", SETTINGS_HEADER_ID)
            val moduleContext = runCatching {
                settingsContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            attachModuleResources(settingsContext)
            val settingsIcon = if (moduleContext != null) {
                R.drawable.ic_hyperchanger_settings_entry
            } else {
                android.R.drawable.ic_menu_manage
            }
            setIntField(header, "iconRes", settingsIcon)
            // A custom title wins over the module's application label; blank keeps the default.
            val customTitle = prefs.getString(KEY_SETTINGS_APP_ENTRY_TITLE, "").orEmpty()
                .trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH)
            val label = if (customTitle.isNotBlank()) {
                customTitle
            } else {
                runCatching {
                    (moduleContext ?: settingsContext).applicationInfo.loadLabel(settingsContext.packageManager).toString()
                }.getOrDefault("HyperChanger")
            }
            setObjectField(header, "title", label)
            setObjectField(header, "intent", Intent().setClassName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.MainActivity").apply {
                putExtra("isDisplayHomeAsUpEnabled", true)
            })
            val deviceId = settingsContext.resources.getIdentifier("my_device", "id", settingsContext.packageName)
            val launcherId = settingsContext.resources.getIdentifier("launcher_settings", "id", settingsContext.packageName)
            val specialId = settingsContext.resources.getIdentifier("other_special_feature_settings", "id", settingsContext.packageName)
            val timerId = settingsContext.resources.getIdentifier("app_timer", "id", settingsContext.packageName)
            val anchor = when (savedPosition) {
                1 -> deviceId
                2 -> launcherId
                else -> if (android.os.Build.VERSION.SDK_INT >= 35) timerId else specialId
            }
            val index = headers.indexOfFirst { item -> runCatching { getLongField(item!!, "id").toInt() == anchor }.getOrDefault(false) }
            val insertAt = if (index >= 0) index + 1 else headers.size.coerceAtMost(25)
            if (headers.isNotEmpty()) {
                val groupSource = headers[(insertAt - 1).coerceIn(0, headers.lastIndex)]
                setIntField(header, "groupId", getIntField(groupSource!!, "groupId"))
            }
            headers.add(insertAt.coerceIn(0, headers.size), header)
            result
        }
    }

    fun installSettingsEntryLabelHook(loader: ClassLoader, prefs: SharedPreferences) {
        val adapter = runCatching { loader.loadClass(MIUI_SETTINGS_HEADER_ADAPTER) }.getOrNull() ?: return
        val bind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterCount == 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        val getItem = runCatching { adapter.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull() ?: return
        module.hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-entry-labels").intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val labels = readSettingsEntryLabels(prefs)
                if (labels.isEmpty()) return@runCatching
                val position = chain.getArg(1) as? Int ?: return@runCatching
                val header = getItem.invoke(chain.thisObject, position) ?: return@runCatching
                val id = header.javaClass.getField("id").getLong(header)
                if (id <= 0L) return@runCatching
                val holder = chain.getArg(0)
                val title = holder.javaClass.getField("title").get(holder) as? TextView ?: return@runCatching
                val name = runCatching { title.resources.getResourceEntryName(id.toInt()) }.getOrNull()
                    ?: return@runCatching
                val custom = labels[name] ?: return@runCatching
                if (title.text?.toString() != custom) title.text = custom
            }
            result
        }
    }

    /** Sound effect support for com.miui.misound: unlock BOSE and rebrand its badge. */
    fun installSoundEffectHooks(loader: ClassLoader, prefs: SharedPreferences) {
        runCatching {
            val getBoolean = Class.forName("android.os.SystemProperties").getDeclaredMethod(
                "getBoolean", String::class.java, Boolean::class.javaPrimitiveType,
            )
            module.hook(getBoolean)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:sound-effect-props")
                .intercept { chain ->
                    val key = chain.getArg(0) as? String
                    if (key == AUDIO_PROP_BOSE) readSoundEffectPreferences(prefs).boseEnabled else chain.proceed()
                }
        }
        runCatching {
            val gate = loader.loadClass("z.m")
            gate.methods.firstOrNull { it.name == "B" && it.parameterCount == 0 }?.let { target ->
                hookDeoptimized(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:sound-effect-gate")
                    .intercept { readSoundEffectPreferences(prefs).boseEnabled }
            }
        }
        runCatching {
            val taGet = android.content.res.TypedArray::class.java.getDeclaredMethod(
                "getDrawable", Int::class.javaPrimitiveType,
            )
            module.hook(taGet)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-ta")
                .intercept { chain ->
                    val typed = chain.thisObject as? android.content.res.TypedArray
                    val index = chain.getArg(0) as? Int
                    val replacement = if (typed != null && index != null) {
                        val resId = typed.getResourceId(index, 0)
                        if (resId != 0) audioLogoDrawable(typed.resources, resId, prefs) else null
                    } else null
                    if (replacement != null) replacement else chain.proceed()
                }
        }
        runCatching {
            val getDrawable = Resources::class.java.getDeclaredMethod(
                "getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java,
            )
            module.hook(getDrawable)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-res")
                .intercept { chain ->
                    val res = chain.thisObject as? Resources
                    val id = chain.getArg(0) as? Int
                    val replacement = if (res != null && id != null) audioLogoDrawable(res, id, prefs) else null
                    if (replacement != null) replacement else chain.proceed()
                }
        }
        runCatching {
            val setImage = ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType)
            module.hook(setImage)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-setter")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val resId = chain.getArg(0) as? Int
                    val replacement = if (view != null && resId != null) audioLogoDrawable(view.resources, resId, prefs) else null
                    if (replacement != null) { view!!.setImageDrawable(replacement); null } else chain.proceed()
                }
        }
        runCatching {
            val fragCls = loader.loadClass("androidx.preference.PreferenceFragmentCompat")
            val onCreateView = fragCls.getMethod(
                "onCreateView", android.view.LayoutInflater::class.java,
                android.view.ViewGroup::class.java, android.os.Bundle::class.java,
            )
            module.hook(onCreateView)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:audio-logo-view")
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? android.view.ViewGroup)?.let { root ->
                        runCatching { attachAudioBadge(chain.thisObject, root, prefs) }
                    }
                    result
                }
        }
    }

    /** Badge drawable for the selected mode, or null to leave the stock one alone. */
    private fun audioLogoDrawable(res: Resources, id: Int, prefs: SharedPreferences): Drawable? {
        val settings = readSoundEffectPreferences(prefs)
        if (!settings.boseEnabled) return null
        if (settings.logoMode == AUDIO_LOGO_NONE || settings.logoMode == AUDIO_LOGO_BOSE) return null
        var cached = boseLogoResId.get()
        if (cached <= 0) {
            cached = runCatching { res.getIdentifier("bose_logo", "drawable", "com.miui.misound") }.getOrDefault(0)
            if (cached > 0) boseLogoResId.set(cached)
        }
        if (cached <= 0 || id != cached) return null
        val appContext = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? android.content.Context ?: return null
        val moduleContext = runCatching {
            appContext.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val drawable = audioLogoFor(settings.logoMode, appContext, moduleContext) ?: return null
        val night = res.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return drawable.mutate().apply { setTint(if (night) 0x33FFFFFF.toInt() else 0x33000000.toInt()) }
    }

    private fun audioLogoFor(mode: Int, appContext: android.content.Context, moduleContext: android.content.Context): Drawable? =
        when (mode) {
            AUDIO_LOGO_XIAOMI -> moduleContext.getDrawable(R.drawable.ic_audio_logo_xiaomi)
            AUDIO_LOGO_HARMAN -> moduleContext.getDrawable(R.drawable.ic_audio_logo_harman)
            AUDIO_LOGO_DOLBY -> moduleContext.getDrawable(R.drawable.ic_audio_logo_dolby)
            AUDIO_LOGO_CUSTOM -> {
                val source = SettingsAppearanceSources.query(appContext, APPEARANCE_SLOT_AUDIO_LOGO)
                if (source.exists) LogoDrawableLoader.load(appContext, source) else null
            }
            else -> null
        }

    /** Adds the badge as a bottom-aligned view on the regular sound page when BOSE is off. */
    private fun attachAudioBadge(fragment: Any?, root: android.view.ViewGroup, prefs: SharedPreferences) {
        val settings = readSoundEffectPreferences(prefs)
        if (settings.boseEnabled || settings.logoMode == AUDIO_LOGO_NONE) return
        if (fragment == null) return
        val screen = runCatching {
            fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
        }.getOrNull() ?: return
        val findPreference = runCatching {
            screen.javaClass.getMethod("findPreference", CharSequence::class.java)
        }.getOrNull() ?: return
        if (runCatching { findPreference.invoke(screen, "effect_selection") }.getOrNull() == null) return
        val context = root.context
        val moduleContext = runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return
        val drawable = audioLogoFor(settings.logoMode, context, moduleContext) ?: return
        val night = root.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        drawable.mutate().setTint(if (night) 0x33FFFFFF.toInt() else 0x33000000.toInt())
        val density = root.resources.displayMetrics.density
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(drawable)
        val params = android.widget.FrameLayout.LayoutParams((130 * density).toInt(), (24 * density).toInt())
        params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        params.bottomMargin = (36 * density).toInt()
        root.addView(image, params)
    }

    private val boseLogoResId = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Adds extra refresh rates to the Xiaomi Settings picker.
     *
     * `FeatureParser.getIntArray("fpsList")` is what the display settings page reads to build its
     * list, so replacing that array is enough to add choices. This does not change the panel itself:
     * anything above the hardware rating will be offered but will not really apply.
     */
    fun installMiSettingsHooks(loader: ClassLoader, prefs: SharedPreferences) {
        if (!readMiSettingsPreferences(prefs).showMoreFps) return
        runCatching {
            val cls = loader.loadClass("miui.util.FeatureParser")
            val target = cls.declaredMethods.firstOrNull {
                it.name == "getIntArray" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            } ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:mi-settings-fps")
                .intercept { chain ->
                    if (chain.getArg(0) == "fpsList") {
                        intArrayOf(240, 185, 165, 144, 120, 90, 60, 30, 10)
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    /** Best-effort tweaks that reshape the system Settings app itself. */
    fun installSettingsAppScopeHooks(loader: ClassLoader, prefs: SharedPreferences) {
        val settings = readSettingsAppScopePreferences(prefs)
        if (settings.oldControlCenter) {
            runCatching {
                val cls = loader.loadClass("com.android.settings.utils.StatusBarUtils")
                val target = cls.declaredMethods.firstOrNull {
                    it.name == "isForceUseControlPanel" && it.parameterCount == 1
                } ?: return@runCatching
                hookDeoptimized(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:settings-old-control-center")
                    .intercept { false }
            }
        }
        if (readFrameworkPreferences(prefs).disableDeviceNameCheck) {
            val internationalField = runCatching {
                loader.loadClass("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD")
                    .apply { isAccessible = true }
            }.getOrNull()
            fun hookAsInternational(method: java.lang.reflect.Executable, id: String) {
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept { chain ->
                        if (internationalField == null) return@intercept chain.proceed()
                        val original = runCatching { internationalField.getBoolean(null) }.getOrDefault(false)
                        runCatching { internationalField.setBoolean(null, true) }
                        try {
                            chain.proceed()
                        } finally {
                            runCatching { internationalField.setBoolean(null, original) }
                        }
                    }
            }
            // The edit pages branch before DeviceNameCheckManager is called. Temporarily making
            // the Settings process look international makes them take the direct save path.
            listOf(
                "com.android.settings.MiuiDeviceNameEditFragment",
                "com.android.settings.wifi.EditTetherFragment",
            ).forEach { className ->
                runCatching {
                    loader.loadClass(className).declaredMethods
                        .filter { it.name == "onSave" && it.parameterCount == 0 }
                        .forEach { method ->
                            hookAsInternational(
                                method,
                                "hyperchanger:settings-device-name-save-${className.substringAfterLast('.')}",
                            )
                        }
                }
            }
            runCatching {
                loader.loadClass("com.android.settings.DeviceNameCheckManager").declaredMethods
                    .filter { it.name == "getDeviceNameCheckResult" && it.parameterCount == 4 }
                    .forEach { method -> hookAsInternational(method, "hyperchanger:settings-device-name-check") }
            }
            runCatching {
                val cls = loader.loadClass("com.android.settings.bluetooth.MiuiBTUtils")
                cls.declaredMethods.filter { it.name == "isInternationalBuild" && it.parameterCount == 0 }
                    .forEach { method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("hyperchanger:fw-device-name-intl")
                            .intercept { true }
                    }
                cls.declaredMethods.filter {
                    it.name == "isSupportNameComplianceCheck" && it.parameterCount == 1
                }.forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-device-name-compliance")
                        .intercept { false }
                }
            }
        }
    }

    fun installMusicControlWhitelistHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!preferences.getBoolean(HOOK_MUSIC_CONTROLS_WHITELIST, true)) return
        runCatching {
            val configClass = sequenceOf(
                "A2.a",
                "p2.a",
                "P2.a",
                "com.xiaomi.subscreencenter.p2.a",
            )
                .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
                .firstOrNull()
                ?: error("Music configuration class was not found")
            val whitelist = preferences.getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet()).orEmpty()
            val mapFields = configClass.declaredFields.filter { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) && Map::class.java.isAssignableFrom(field.type)
            }
            val mapField = mapFields.firstOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    val map = field.get(null) as? Map<*, *> ?: return@runCatching false
                    map.keys.any { it in setOf("com.xiaomi.music", "com.android.incallui", "com.xiaomi.smarthome") } ||
                        map.values.any { it == "unified.music" || it == "music" }
                }.getOrDefault(false)
            } ?: mapFields.firstOrNull()
            runCatching {
                if (mapField != null) {
                    mapField.isAccessible = true
                    val original = mapField.get(null) as? Map<*, *> ?: emptyMap<Any?, Any?>()
                    val replacement = LinkedHashMap<Any?, Any?>().apply {
                        putAll(original)
                        whitelist.forEach { put(it, "music") }
                    }
                    mapField.set(null, replacement)
                }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not replace rear music configuration map", error)
            }

            fun currentWhitelist(): Set<String> = preferences
                .getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet())
                .orEmpty()

            configClass.declaredMethods.firstOrNull { method ->
                method.name == "a" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    method.returnType == String::class.java
            }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-music-whitelist:business")
                    .intercept { chain ->
                        val packageName = chain.getArg(0) as? String
                        if (packageName != null && packageName in currentWhitelist()) "music"
                        else chain.proceed()
                    }
            }

            configClass.declaredMethods.firstOrNull { method ->
                method.name == "b" &&
                    method.parameterTypes.isEmpty() &&
                    Set::class.java.isAssignableFrom(method.returnType)
            }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-music-whitelist:supported-apps")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val supported = LinkedHashSet<Any?>()
                        (result as? Set<*>)?.let(supported::addAll)
                        supported.addAll(currentWhitelist())
                        supported
                    }
            }

            configClass.declaredMethods.firstOrNull { method ->
                method.name == "c" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-music-whitelist:is-music")
                    .intercept { chain ->
                        val packageName = chain.getArg(0) as? String
                        if (packageName != null && packageName in currentWhitelist()) true
                        else chain.proceed()
                    }
            }

            val utilityClass = sequenceOf("A2.g", "p2.g", "P2.g")
                .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
                .firstOrNull()
            utilityClass?.declaredMethods?.firstOrNull { method ->
                method.name == "k" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == String::class.java &&
                    Set::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                    Map::class.java.isAssignableFrom(method.parameterTypes[2]) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-music-whitelist:is-enabled")
                    .intercept { chain ->
                        val packageName = chain.getArg(0) as? String
                        if (packageName != null && packageName in currentWhitelist()) {
                            val switches = chain.getArg(2) as? Map<*, *>
                            (switches?.get("com.music.service") as? Boolean) ?: true
                        } else {
                            chain.proceed()
                        }
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed rear music whitelist hooks (${whitelist.size} app(s), ${configClass.name})")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not update rear music control whitelist", error)
        }
    }

    /** Virtually enables the same two gates as the root script, scoped to the three target apps. */
    fun installRearScreenAppWidgetUnlockHooks(classLoader: ClassLoader) {
        runCatching {
            // ThemeManager's RearScreenSettingModule gates the entire app-card catalog on
            // DeviceUtils.a() (DeviceHelper.toq()).  On the 18 Pro/Pro Max rear-screen builds
            // this probe is false even though the SubScreen service is present, so only the
            // custom property/settings hooks still leave the catalog partially hidden.
            runCatching {
                val deviceUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.DeviceUtils")
                deviceUtils.methods.filter {
                    it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:device-capability")
                        .intercept { true }
                }
            }
            runCatching {
                val deviceHelper = classLoader.loadClass("miuix.os.DeviceHelper")
                deviceHelper.methods.filter {
                    it.name == "toq" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:device-helper-capability")
                        .intercept { true }
                }
            }
            // ThemeManager's rear-screen personalization has a second, independent
            // capability gate.  In this release RearScreenFunction.q() is hard-coded false
            // and toq() depends on a server preset count, so the 18 Pro catalog never exposes
            // the newer personalization/video entries even when SubScreen is available.
            runCatching {
                val rearFunction = classLoader.loadClass("com.rearScreen.manager.RearScreenFunction")
                rearFunction.declaredMethods.filter { method ->
                    method.parameterTypes.isEmpty() && method.returnType == Boolean::class.javaPrimitiveType &&
                        method.name in setOf("q", "toq")
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:theme-rear-function:${method.name}")
                        .intercept { true }
                }
            }
            runCatching {
                val supportGuard = classLoader.loadClass("com.rearScreen.miclaw.appfunction.common.DeviceSupportGuard")
                supportGuard.declaredMethods.filter { method ->
                    method.name == "isSupported" && method.parameterTypes.isEmpty() &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:theme-device-support-guard")
                        .intercept { true }
                }
            }
            // SubScreenCenter parses /system/media/rearscreen/appcard/default/rearScreen.json
            // and consults AbstractC0666c.g() before adding migrated cards.  The stock
            // implementation only enables three packages (car, Security Center and stock
            // assistant), so hongkong-exclusive migrated cards are discarded even when the
            // JSON is present. Mark every returned package switch enabled; this is scoped to
            // the SubScreen process and does not alter notification/settings state elsewhere.
            runCatching {
                val localSettingUtils = classLoader.loadClass("o2.AbstractC0666c")
                localSettingUtils.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "g" && method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                        java.util.HashMap::class.java.isAssignableFrom(method.returnType)
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:subscreen-migrated-card-switches")
                        .intercept { chain ->
                            @Suppress("UNCHECKED_CAST")
                            val switches = (chain.proceed() as? MutableMap<Any?, Any?>)
                                ?: java.util.HashMap<Any?, Any?>()
                            switches.keys.toList().forEach { key -> switches[key] = true }
                            switches["com.miui.personalassistant"] = true
                            switches["com.miui.personalassistant_stock"] = true
                            switches
                        }
                }
            }
            val properties = Class.forName("android.os.SystemProperties", false, null)
            properties.declaredMethods
                .filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name in setOf("get", "getBoolean", "getInt", "getLong") &&
                        method.parameterTypes.firstOrNull() == String::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:property:${method.name}:${method.parameterTypes.size}")
                        .intercept { chain ->
                            val key = chain.getArg(0) as? String
                            val spoofed = when (key) {
                                "ro.product.model" -> "M610BB"
                                "ro.product.device", "ro.product.name", "ro.build.product" -> "hongkong"
                                "ro.product.brand", "ro.product.manufacturer" -> "Xiaomi"
                                "ro.mi.os.version.code" -> "4"
                                "persist.sys.muiltdisplay_type", "persist.sys.multi_display_type" -> "2"
                                "system.xiaomi.subscreen.dou", "persist.sys.replacement" -> "true"
                                else -> null
                            }
                            if (spoofed != null) {
                                when (method.name) {
                                    "get" -> spoofed
                                    "getBoolean" -> spoofed == "true"
                                    "getInt" -> spoofed.toIntOrNull() ?: 0
                                    "getLong" -> spoofed.toLongOrNull() ?: 0L
                                    else -> chain.proceed()
                                }
                            } else if (key != REAR_SCREEN_APP_WIDGET_PROPERTY) {
                                return@intercept chain.proceed()
                            } else {
                                when (method.name) {
                                    "get" -> "true"
                                    "getBoolean" -> true
                                    "getInt" -> 1
                                    "getLong" -> 1L
                                    else -> chain.proceed()
                                }
                            }
                        }
                }
            val miuiProperties = runCatching {
                Class.forName("miuix.os.SystemProperties", false, null)
            }.getOrNull()
            miuiProperties?.declaredMethods
                ?.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name in setOf("get", "getBoolean", "getInt", "toq", "zy", "k") &&
                        method.parameterTypes.firstOrNull() == String::class.java
                }
                ?.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:miui-property:${method.name}:${method.parameterTypes.size}")
                        .intercept { chain ->
                            val key = chain.getArg(0) as? String
                            val spoofed = when (key) {
                                "ro.product.model" -> "M610BB"
                                "ro.product.device", "ro.product.name", "ro.build.product" -> "hongkong"
                                "ro.product.brand", "ro.product.manufacturer" -> "Xiaomi"
                                "ro.mi.os.version.code" -> "4"
                                "persist.sys.muiltdisplay_type", "persist.sys.multi_display_type" -> "2"
                                "system.xiaomi.subscreen.dou", "persist.sys.replacement" -> "true"
                                else -> null
                            }
                            if (spoofed != null) {
                                when (method.returnType) {
                                    String::class.java -> spoofed
                                    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> spoofed == "true"
                                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> spoofed.toIntOrNull() ?: 0
                                    Long::class.javaPrimitiveType, Long::class.javaObjectType -> spoofed.toLongOrNull() ?: 0L
                                    else -> chain.proceed()
                                }
                            } else if (key != REAR_SCREEN_APP_WIDGET_PROPERTY) {
                                return@intercept chain.proceed()
                            } else {
                                when (method.returnType) {
                                    String::class.java -> "true"
                                    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> true
                                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> 1
                                    else -> chain.proceed()
                                }
                            }
                        }
                }

            val secure = android.provider.Settings.Secure::class.java
            secure.declaredMethods
                .filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name in setOf("getInt", "getString") &&
                        method.parameterTypes.getOrNull(1) == String::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-widget:secure:${method.name}:${method.parameterTypes.size}")
                        .intercept { chain ->
                            val settingKey = chain.getArg(1) as? String
                            if (settingKey !in setOf(
                                    REAR_SCREEN_APP_WIDGET_SETTING,
                                    REAR_SCREEN_THEME_WIDGET_SETTING,
                                    REAR_SCREEN_AOD_SETTING,
                                    REAR_SCREEN_AOD_MODE_SETTING,
                                    REAR_SCREEN_STOCK_REMINDER_SETTING,
                                )) {
                                return@intercept chain.proceed()
                            }
                            if (method.name == "getString") "1" else 1
                        }
                }

            val repositoryClass = runCatching {
                classLoader.loadClass("com.personalizedEditor.helper.settings.SettingRepository")
            }.getOrNull()
            repositoryClass?.declaredMethods?.firstOrNull { method ->
                method.name == "zy" && method.parameterTypes.size == 1 && method.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("rear-screen-app-widget:theme-manager-setting-repository")
                    .intercept { chain ->
                        val settingKey = chain.getArg(0)
                        val key = settingKey?.javaClass?.methods
                            ?.firstOrNull { it.name in setOf("getKey", "k") && it.parameterTypes.isEmpty() }
                            ?.let { runCatching { it.invoke(settingKey) as? String }.getOrNull() }
                        if (key == REAR_SCREEN_APP_WIDGET_PROPERTY || key == REAR_SCREEN_APP_WIDGET_SETTING || key == REAR_SCREEN_THEME_WIDGET_SETTING || key == REAR_SCREEN_AOD_SETTING || key == REAR_SCREEN_AOD_MODE_SETTING || key == REAR_SCREEN_STOCK_REMINDER_SETTING) true
                        else chain.proceed()
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Rear-screen app-widget gates enabled in this process")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not enable rear-screen app-widget gates", error)
        }
    }

    /**
     * PersonalAssistant owns the app-card store UI. Its OS-version gate reads
     * ro.mi.os.version.code (which can be absent on newer builds), and its cached-card
     * refresh removes every card whose bindApp package is not installed. Keep the complete
     * preset catalog visible without changing package checks elsewhere in the app.
     */
    fun installPersonalAssistantRearScreenAppCardHooks(classLoader: ClassLoader) {
        runCatching {
            runCatching {
                val wrapper = classLoader.loadClass("c8.d\$c")
                wrapper.declaredMethods.filter { method ->
                    method.name == "a" && method.parameterTypes.size == 1 &&
                        method.returnType.name == "okhttp3.v"
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:request-scope")
                        .intercept { chain ->
                            val matched = containsRearScreenRequestUrl(chain.getArg(0))
                            if (matched) personalAssistantRearScreenRequest.set(true)
                            try {
                                chain.proceed()
                            } finally {
                                if (matched) personalAssistantRearScreenRequest.remove()
                            }
                        }
                }
            }

            runCatching {
                val commonParams = classLoader.loadClass("com.miui.personalassistant.network.util.a")
                commonParams.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "a" &&
                        method.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
                        method.returnType == JSONObject::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:environment-signal")
                        .intercept { chain ->
                            val original = chain.proceed()
                            val json = original as? JSONObject ?: return@intercept original
                            if (personalAssistantRearScreenRequest.get() == true) {
                                json.put("phoneModel", "M610BB")
                                json.put("phoneDevice", "hongkong")
                                val incremental = json.optString("os")
                                val parts = incremental.split('.').toMutableList()
                                if (parts.size > 2) {
                                    parts[2] = "499"
                                    json.put("os", parts.joinToString("."))
                                }
                            }
                            json
                        }
                }
            }

            runCatching {
                JSONObject::class.java.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                        method.name == "put" &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java, Any::class.java)) &&
                        method.returnType == JSONObject::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:json-device-fields")
                        .intercept { chain ->
                            if (personalAssistantRearScreenRequest.get() == true) {
                                val key = chain.getArg(0) as? String
                                val value = when (key) {
                                    "phoneModel", "model" -> "M610BB"
                                    "phoneDevice", "device", "product" -> "hongkong"
                                    "os" -> {
                                        val original = chain.getArg(1)?.toString().orEmpty()
                                        val parts = original.split('.').toMutableList()
                                        if (parts.size > 2) {
                                            parts[2] = "499"
                                            parts.joinToString(".")
                                        } else original
                                    }
                                    else -> null
                                }
                                if (value != null) {
                                    return@intercept chain.proceedWith(
                                        chain.thisObject,
                                        arrayOf(chain.getArg(0), value),
                                    )
                                }
                            }
                            chain.proceed()
                        }
                }
            }

            runCatching {
                val deviceInfo = classLoader.loadClass(
                    "com.miui.personalassistant.maml.expand.device.DeviceInfoRepository\$Companion",
                )
                deviceInfo.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "b" && method.parameterTypes.isEmpty() &&
                        method.returnType.name == "okhttp3.o"
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:device-form")
                        .intercept { chain ->
                            val original = chain.proceed()
                            val fields = generateSequence(original?.javaClass) { it.superclass }
                                .flatMap { it.declaredFields.asSequence() }
                                .filter { field ->
                                    !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                                        List::class.java.isAssignableFrom(field.type)
                                }.toList()
                            val lists = fields.mapNotNull { field ->
                                runCatching {
                                    field.isAccessible = true
                                    field.get(original) as? List<*>
                                }.getOrNull()
                            }
                            val names = lists.firstOrNull { list ->
                                list.any { it == "product" } && list.any { it == "model" } && list.any { it == "device" }
                            }
                            val values = lists.firstOrNull { it !== names && it?.size == names?.size }
                            if (names == null || values == null) return@intercept original
                            val rewritten = ArrayList(values.map { it?.toString().orEmpty() })
                            names.forEachIndexed { index, name ->
                                when (name) {
                                    "model" -> rewritten[index] = "M610BB"
                                    "device", "product" -> rewritten[index] = "hongkong"
                                }
                            }
                            val constructor = original?.javaClass?.declaredConstructors?.firstOrNull { constructor ->
                                constructor.parameterTypes.size == 2 && constructor.parameterTypes.all {
                                    it.isAssignableFrom(ArrayList::class.java)
                                }
                            } ?: return@intercept original
                            runCatching {
                                constructor.isAccessible = true
                                constructor.newInstance(ArrayList(names.map { it?.toString().orEmpty() }), rewritten)
                            }.getOrDefault(original)
                        }
                }
            }

            runCatching {
                val systemInfo = classLoader.loadClass("com.miui.personalassistant.utils.z1")
                systemInfo.declaredMethods
                    .filter { method ->
                        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                            method.name == "b" && method.parameterTypes.isEmpty() &&
                            method.returnType == Int::class.javaPrimitiveType
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("rear-screen-app-card:personal-assistant-os-version")
                            .intercept { 4 }
                    }
            }

            runCatching {
                val storeViewModel = classLoader.loadClass(
                    "com.miui.personalassistant.backscreen.store.viewmodel.BackScreenStoreViewModel",
                )
                storeViewModel.declaredMethods
                    .filter { method ->
                        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "a" && method.parameterTypes.size == 2 &&
                            method.parameterTypes[1] == List::class.java &&
                            List::class.java.isAssignableFrom(method.returnType)
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("rear-screen-app-card:personal-assistant-catalog-filter")
                            .intercept { chain ->
                                val categories = chain.getArg(1) as? List<*>
                                if (categories != null) {
                                    // This helper is the last installed-package filter.  Keep
                                    // the original category/item objects so cloud and preset
                                    // entries are both passed to the UI; m1.i() is bypassed by
                                    // the dedicated hook below.
                                    java.util.ArrayList(categories)
                                } else chain.proceed()
                            }
                    }
            }

            // The backPage service filters app-bound cards using the compressed installed
            // package list (n0.b()), before the response ever reaches BackScreenStoreRepository.
            // On regional builds the optional Weather/Calendar and Mi Home providers can be
            // absent from that list even when their packages are present. Augment the source
            // list at n0.d(), so the app list is compressed by the stock encoder and remains
            // a valid request payload (rather than fabricating compressed bytes).
            runCatching {
                val installedCache = classLoader.loadClass("com.miui.personalassistant.utils.n0")
                installedCache.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "d" && method.parameterTypes.isEmpty()
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:augment-installed-package-list")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val list = result as? MutableList<Any?>
                            if (list != null) {
                                val appBaseInfo = classLoader.loadClass("com.miui.personalassistant.utils.AppBaseInfo")
                                val wanted = listOf(
                                    "com.miui.weather2",
                                    "com.android.calendar",
                                    "com.xiaomi.calendar",
                                    "com.xiaomi.smarthome",
                                    "com.mi.car.mobile",
                                )
                                wanted.forEach { packageName ->
                                    if (list.none { item ->
                                            runCatching {
                                                item?.javaClass?.getField("packageName")?.get(item) == packageName
                                            }.getOrDefault(false)
                                        }) {
                                        val info = appBaseInfo.getConstructor(String::class.java).newInstance(packageName)
                                        list.add(info)
                                    }
                                }
                            }
                            result
                        }
                }
                installedCache.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "b" && method.parameterTypes.isEmpty() &&
                        method.returnType == String::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:refresh-installed-package-cache")
                        .intercept { chain ->
                            // Force n0.b() to rebuild its compressed payload through the
                            // augmented d() result on every store request.
                            runCatching { setStaticFieldValue(installedCache, "f15559d", true) }
                            chain.proceed()
                        }
                }
            }
            // The ViewModel's installed filter delegates to m1.i(Context,String),
            // which performs a PackageManager lookup and drops every preset bound
            // to an optional companion app. The store is intended to show those
            // cards before installation (the detail page handles installation),
            // so bypass only this exact two-argument helper in PersonalAssistant.
            runCatching {
                val packageUtils = classLoader.loadClass("com.miui.personalassistant.utils.m1")
                packageUtils.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "i" && method.parameterTypes.contentEquals(
                            arrayOf(Context::class.java, String::class.java),
                        ) && method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:personal-assistant-bound-app-installed")
                        .intercept { true }
                }
            }
            // The bundled default_config.json contains weather, agenda/calendar and MiJia
            // cards, but DefaultConfig.c() applies DefaultWidgetFilter.DEFAULT_OFF_WIDGET
            // (which includes miot_device) before the store/home model sees them. Open only
            // these rear-screen service keys; unrelated homepage defaults keep their stock
            // behavior.
            runCatching {
                val defaultConfig = classLoader.loadClass(
                    "com.miui.personalassistant.homepage.cell.utils.DefaultConfig",
                )
                defaultConfig.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "c" && method.parameterTypes.size == 2 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-app-card:default-service-filter")
                        .intercept { chain ->
                            val widget = chain.getArg(0)
                            val serviceKey = widget?.javaClass?.methods
                                ?.firstOrNull { it.name == "getServiceKey" && it.parameterTypes.isEmpty() }
                                ?.invoke(widget) as? String
                            if (serviceKey in setOf("weather", "agenda", "calendar", "miot_device")) false
                            else chain.proceed()
                        }
                }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "PersonalAssistant rear-screen app-card catalog filters bypassed")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not unlock PersonalAssistant rear-screen app-card catalog", error)
        }
    }

    private fun containsRearScreenRequestUrl(root: Any?): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val endpoints = arrayOf(
            "component/store/backPage",
            "component/store/impl/tail",
            "component/store/updateInfo/backScreen",
        )

        fun visit(value: Any?, depth: Int): Boolean {
            if (value == null || depth > 3 || !visited.add(value)) return false
            val text = runCatching { value.toString() }.getOrDefault("")
            if (endpoints.any(text::contains)) return true
            if (value is String || value is Number || value is Boolean || value is Enum<*>) return false
            generateSequence(value.javaClass) { it.superclass }.forEach { type ->
                type.declaredFields.forEach { field ->
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                    val nested = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                    if (visit(nested, depth + 1)) return true
                }
            }
            return false
        }

        return visit(root, 0)
    }

    /** Make PersonalAssistant use the Xiaomi 18 Pro (hongkong) product identity. */
    private fun installPersonalAssistantHongkongBuildProfile() {
        runCatching {
            val values = mapOf(
                "DEVICE" to "hongkong",
                "PRODUCT" to "hongkong",
                "MODEL" to "M610BB",
                "BRAND" to "Xiaomi",
                "MANUFACTURER" to "Xiaomi",
            )
            values.forEach { (name, value) ->
                val field = android.os.Build::class.java.getDeclaredField(name).apply { isAccessible = true }
                runCatching { field.set(null, value) }.getOrElse {
                    runCatching {
                        val unsafeClass = Class.forName("sun.misc.Unsafe")
                        val singleton = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                        val unsafe = singleton.get(null)
                        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
                        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
                        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                            .invoke(unsafe, base, offset, value)
                    }
                }
            }
            // ThemeManager's network ParamInterceptor and RearScreenUtil import
            // miui.os.Build rather than android.os.Build. Keep both profiles aligned;
            // otherwise the remote rear-screen endpoint still receives the original
            // device code and omits the hongkong-only AI categories.
            runCatching {
                val miuiBuild = Class.forName("miui.os.Build")
                values.forEach { (name, value) ->
                    runCatching {
                        val field = miuiBuild.getDeclaredField(name).apply { isAccessible = true }
                        runCatching { field.set(null, value) }.getOrElse {
                            val unsafeClass = Class.forName("sun.misc.Unsafe")
                            val singleton = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                            val unsafe = singleton.get(null)
                            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
                            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
                            unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                                .invoke(unsafe, base, offset, value)
                        }
                    }
                }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "PersonalAssistant build profile applied: Xiaomi 18 Pro / hongkong (M610BB)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not apply PersonalAssistant hongkong build profile", error)
        }
    }

    /**
     * SubScreenCenter snapshots these values into o2.C0673j static finals during class
     * initialization, so changing SystemProperties afterwards is insufficient. The decompiled
     * gate is exactly: ro.mi.os.version.code >= 4 && persist.sys.app.widget.enable.
     */
    fun installSubScreenCenterAppWidgetState(classLoader: ClassLoader) {
        runCatching {
            val config = classLoader.loadClass("o2.C0673j")
            setStaticFieldValue(config, "f10018p", 4)
            setStaticFieldValue(config, "f10019q", true)
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "SubScreenCenter app-card state forced: osVersion=4, appWidget=true")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not force SubScreenCenter app-card state", error)
        }
    }

    /** Exact guards used by the ThemeManager rear-screen personalization/AI flows. */
    fun installThemeManagerRearScreenFeatureGuards(classLoader: ClassLoader) {
        runCatching {
            runCatching {
                val addDao = classLoader.loadClass("com.rearScreen.aiapp.db.RearScreenAiAddDao_Impl")
                addDao.declaredMethods.filter { method ->
                    method.name == "zy" && method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:remove-imported-ai-app-files")
                        .intercept { chain ->
                            val productId = chain.getArg(0) as? String
                            val result = chain.proceed()
                            if (!productId.isNullOrBlank()) {
                                runCatching {
                                    val importedRoot = java.io.File(
                                        "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app",
                                    )
                                    val importedFolder = java.io.File(importedRoot, "${productId}_extracted")
                                    java.io.File(importedRoot, "$productId.removed").writeText("removed")
                                    if (importedFolder.isDirectory) importedFolder.deleteRecursively()
                                    val runtimeFolder = java.io.File(
                                        "/data/system/theme_magic/users/0/rearScreenAiApp_Theme",
                                        productId,
                                    )
                                    if (runtimeFolder.isDirectory) runtimeFolder.deleteRecursively()
                                }
                            }
                            result
                        }
                }
            }
            runCatching {
                val appliedRepository = classLoader.loadClass("com.rearScreen.aiapp.repository.AiAppAppliedRepository")
                appliedRepository.declaredMethods.filter { method ->
                    method.name == "zy" && method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:remove-applied-ai-app-files")
                        .intercept { chain ->
                            val productId = chain.getArg(0) as? String
                            val result = chain.proceed()
                            if (!productId.isNullOrBlank()) {
                                runCatching {
                                    val importedFolder = java.io.File(
                                        "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app/${productId}_extracted",
                                    )
                                    java.io.File(importedFolder.parentFile, "$productId.removed").writeText("removed")
                                    importedFolder.deleteRecursively()
                                    java.io.File(
                                        "/data/system/theme_magic/users/0/rearScreenAiApp_Theme/$productId",
                                    ).deleteRecursively()
                                }
                            }
                            result
                        }
                }
            }
            fun forceBooleanMethod(className: String, methodName: String, id: String) {
                val type = classLoader.loadClass(className)
                type.declaredMethods.filter { method ->
                    method.name == methodName && method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId(id).intercept { false }
                }
            }
            // Demo-device checks hide locally generated rear-screen items and personalization.
            forceBooleanMethod(
                "com.android.thememanager.util.ai.AiUsageRepository",
                "isDemoDevice",
                "rear-screen-feature:theme-ai-demo-device",
            )
            forceBooleanMethod(
                "com.rearScreen.aiapp.repository.AiAppGenerateRepository",
                "h",
                "rear-screen-feature:ai-app-demo-device",
            )
            runCatching {
                val usage = classLoader.loadClass("com.android.thememanager.util.ai.AiUsageRepository")
                usage.declaredMethods.filter { method ->
                    method.name == "isDeviceTrusted" && method.parameterTypes.size == 1 &&
                        method.returnType == Any::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-ai-device-trust-direct")
                        .intercept { true }
                }
            }

            // These suspend guards return their result directly when intercepted; true means
            // trusted and null means no BlockReason, matching the callers' coroutine contract.
            runCatching {
                val guard = classLoader.loadClass("com.rearScreen.aiapp.manager.RearScreenAiAppGenerateGuard")
                guard.declaredMethods.filter { method ->
                    method.name == "q" && method.parameterTypes.size == 1 &&
                        method.returnType == Any::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:ai-app-device-trust")
                        .intercept { true }
                }
                guard.declaredMethods.filter { method ->
                    method.name == "zy" && method.parameterTypes.size == 1 &&
                        method.returnType == Any::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:ai-app-block-reason")
                        .intercept { null }
                }
            }
            runCatching {
                val router = classLoader.loadClass("com.android.thememanager.activity.ai.viewmodel.AiRouterVM")
                router.declaredMethods.filter { method ->
                    method.name == "zsr0" && method.parameterTypes.size == 1 && method.returnType == Any::class.java
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-ai-device-trust")
                        .intercept { true }
                }
            }
            // RearScreenPresetRepository.cdj() removes every preset whose resType is
            // "ai" when MiuiUtils.zurt() is true. Its decompiled condition is
            // AppUtils.toq() < 10278 (ThemeManager versionCode), which strips the
            // AI Companion and AI Group Photo entries from the personalization list.
            runCatching {
                val appUtils = classLoader.loadClass("com.android.thememanager.library.util.app.AppUtils")
                appUtils.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "toq" && method.parameterTypes.isEmpty() &&
                        method.returnType == Int::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-version-for-ai-preset-filter")
                        .intercept { 10278 }
                }
            }
            // Also force the helper result in case MiuiUtils was initialized before
            // AppUtils.toq() was intercepted.
            runCatching {
                val miuiUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils")
                miuiUtils.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "zurt" && method.parameterTypes.isEmpty() &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-ai-preset-filter")
                        .intercept { false }
                }
            }
            // RearScreenPresetRepository.cdj(ArrayList) is the final local preset
            // sanitizer.  On non-target products MiuiUtils.zurt() makes it remove
            // every item whose resType is "ai" (including the 18 Pro companion and
            // group-photo presets) before the list reaches the ViewModel.  Returning
            // without invoking this private method preserves the complete preset
            // catalog; the later detail/install checks remain unchanged.
            runCatching {
                val presetRepository = classLoader.loadClass("com.rearScreen.repository.RearScreenPresetRepository")
                presetRepository.declaredMethods.filter { method ->
                    method.name == "cdj" && method.parameterTypes.size == 1 &&
                        java.util.ArrayList::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.returnType == Void.TYPE
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-ai-preset-sanitizer")
                        .intercept { }
                }
            }
            // RearScreenSettingModule.k() has an independent MiuiVersion.toq(4)
            // check. MiuiVersion snapshots ro.mi.os.version.code into a static final
            // field, so property hooks alone are too late; force this exact predicate.
            runCatching {
                val miuiVersion = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils\$MiuiVersion")
                miuiVersion.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "toq" && method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:miui-version-capability")
                        .intercept { true }
                }
            }
            // This is the final entry gate used by the ThemeManager settings page.
            runCatching {
                val settingModule = classLoader.loadClass("com.personalizedEditor.helper.settings.RearScreenSettingModule\$Companion")
                settingModule.declaredMethods.filter { method ->
                    method.name == "k" && method.parameterTypes.isEmpty() &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:setting-module-entry")
                        .intercept { true }
                    }
            }
            runCatching {
                val interceptor = classLoader.loadClass(
                    "com.android.thememanager.basemodule.network.theme.interceptors.ParamInterceptor",
                )
                interceptor.declaredMethods.filter { method ->
                    method.parameterTypes.size == 3 &&
                        method.parameterTypes[0].name == "okhttp3.Request" &&
                        LinkedHashMap::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                        method.parameterTypes[2] == String::class.java &&
                        method.returnType.name == "okhttp3.Request"
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:theme-final-network-profile")
                        .intercept { chain ->
                            val requestText = runCatching { chain.getArg(0)?.toString().orEmpty() }.getOrDefault("")
                            val related = requestText.contains("/page/v3/REAR_SCREEN_SETTING") ||
                                requestText.contains("/native/page/v3/AI_GENERATED_APP") ||
                                requestText.contains("/native/page/v3/subjects/") ||
                                requestText.contains("/ai/") ||
                                requestText.contains("/checkupdate/hashpair")
                            if (related) {
                                @Suppress("UNCHECKED_CAST")
                                val params = chain.getArg(1) as? MutableMap<Any?, Any?>
                                params?.set("device", "hongkong")
                                if (params?.containsKey("product") == true) params["product"] = "hongkong"
                                if (params?.containsKey("model") == true) params["model"] = "M610BB"
                                val version = params?.get("version")?.toString()
                                if (!version.isNullOrEmpty()) {
                                    val parts = version.split('.').toMutableList()
                                    if (parts.size > 2) {
                                        parts[2] = "499"
                                        params["version"] = parts.joinToString(".")
                                    }
                                }
                            }
                            chain.proceed()
                        }
                }
            }
            // RearScreenListViewModel.wo() merges the remote page into the preset
            // categories. A remote card with isShield=1 removes the matching preset
            // category entirely. The new rear-screen AI categories are delivered this
            // way on older regional endpoints, so clear the shield bit before merge.
            runCatching {
                val listVm = classLoader.loadClass("com.rearScreen.viewModel.RearScreenListViewModel")
                // The merge routine removes categories again through the private gbni
                // predicate when their item list is empty. Keep the category object alive;
                // remote data may populate it asynchronously on the next refresh.
                listVm.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.name == "gbni" && method.parameterTypes.size == 1 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:keep-ai-categories")
                        .intercept { false }
                }
                listVm.declaredMethods.filter { method ->
                    method.name == "wo" && method.parameterTypes.size == 2 &&
                        method.returnType == Void.TYPE
                }.forEach { method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("rear-screen-feature:unshield-ai-categories")
                        .intercept { chain ->
                            val incoming = chain.getArg(1) as? Iterable<*>
                            incoming?.forEach { category ->
                                runCatching {
                                    val type = category?.javaClass?.methods
                                        ?.firstOrNull { it.name == "getCategoryType" && it.parameterTypes.isEmpty() }
                                        ?.invoke(category) as? String
                                    if (type in setOf("ai", "aiGroupPhoto", "groupPhoto", "companionPreset", "companionCustom", "ai-mate", "aiApp")) {
                                        val categoryMethods = category?.javaClass?.methods
                                        categoryMethods
                                            ?.firstOrNull { it.name == "setShield" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                                            ?.invoke(category, false)
                                        val itemList = categoryMethods
                                            ?.firstOrNull { it.name == "getItemList" && it.parameterTypes.isEmpty() }
                                            ?.invoke(category) as? Iterable<*>
                                        itemList?.forEach { item ->
                                            val itemMethods = item?.javaClass?.methods ?: return@forEach
                                            val tags = (itemMethods.firstOrNull {
                                                it.name == "getInnerTags" && it.parameterTypes.isEmpty()
                                            }?.invoke(item) as? Iterable<*>)?.mapNotNull { it as? String }
                                            if (tags != null) {
                                                // sourceCode: removes a matching local resId;
                                                // isShield:1 keeps the item out of the merge.
                                                val cleaned = tags.filterNot {
                                                    it == "isShield:1" || it.startsWith("sourceCode:")
                                                }
                                                itemMethods.firstOrNull {
                                                    it.name == "setInnerTags" && it.parameterTypes.size == 1
                                                }?.invoke(item, cleaned)
                                            }
                                        }
                                    }
                                }
                            }
                            chain.proceed()
                        }
                }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "ThemeManager rear-screen personalization guards bypassed")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not bypass ThemeManager rear-screen personalization guards", error)
        }
    }

    private fun setStaticFieldValue(type: Class<*>, name: String, value: Any) {
        val field = type.getDeclaredField(name).apply { isAccessible = true }
        runCatching { field.set(null, value) }.getOrElse {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val singleton = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = singleton.get(null)
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
            val put = when (value) {
                is Boolean -> unsafeClass.getMethod("putBoolean", Any::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                is Int -> unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                else -> unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            }
            when (value) {
                is Boolean -> put.invoke(unsafe, base, offset, value)
                is Int -> put.invoke(unsafe, base, offset, value)
                else -> put.invoke(unsafe, base, offset, value)
            }
        }
    }

    /**
     * The AI package directory is only the resource cache. ThemeManager normally creates the
     * database row and then calls WidgetBridge.insertAiAppWidget(), which in turn calls
     * SubScreen.g(context).x2(widget). Merely copying `rearscreen` therefore never creates a
     * visible card. Register every imported UUID folder through the same SDK call after the
     * ThemeManager process has started.
     */
    fun installCustomRearScreenWidgetRegistration(classLoader: ClassLoader) {
        if (customRearScreenWidgetRegistrationInstalled) return
        customRearScreenWidgetRegistrationInstalled = true
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val contextManager = classLoader.loadClass("com.android.thememanager.basemodule.context.AppContextManager")
                val context = contextManager.getMethod("q").invoke(null) as? Context ?: return@runCatching
                val widgetClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.Widget")
                val subScreenClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.SubScreen")
                val widgetFactory = widgetClass.methods.firstOrNull { it.name == "q" && it.parameterTypes.size == 7 }
                    ?: return@runCatching
                val subScreen = subScreenClass.getMethod("g", Context::class.java).invoke(null, context)
                val insert = subScreen.javaClass.methods.firstOrNull { it.name == "x2" && it.parameterTypes.size == 1 }
                    ?: return@runCatching
                val root = java.io.File("/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app")
                val runtimeRoot = java.io.File("/data/system/theme_magic/users/0/rearScreenAiApp_Theme")
                root.listFiles()?.filter { it.isDirectory && it.name.endsWith("_extracted") }?.forEach { folder ->
                    val productId = folder.name.removeSuffix("_extracted")
                    if (java.io.File(root, "$productId.removed").isFile) return@forEach
                    val appName = runCatching {
                        val xml = java.io.File(folder, "description.xml").readText()
                        Regex("<appName>\\s*(.*?)\\s*</appName>", RegexOption.DOT_MATCHES_ALL)
                            .find(xml)?.groupValues?.getOrNull(1)?.trim()
                            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
                    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: productId
                    val runtime = java.io.File(runtimeRoot, productId)
                    val resource = java.io.File(runtime, "rearScreen.mrc").takeIf { it.isFile }
                        ?: java.io.File(folder, "rearscreen")
                    if (!resource.isFile) return@forEach
                    val icon = java.io.File(runtime, "app_icon.png").takeIf { it.isFile }
                        ?: java.io.File(folder, "app/app_icon.png")
                    val preview = java.io.File(runtime, "preview.png").takeIf { it.isFile }
                        ?: folder.resolve("preview").listFiles()?.firstOrNull { it.isFile }
                    val bundle = android.os.Bundle().apply {
                        putBoolean("isGame", false)
                        putString("previewImagePath", preview?.absolutePath ?: "")
                    }
                    val widget = widgetFactory.invoke(null, productId, appName, 2, resource.absolutePath, icon.absolutePath, preview?.absolutePath ?: "", bundle)
                    val result = insert.invoke(subScreen, widget)
                    btm.m.os4.systemuihook.ModuleLog.i(logTag, "Registered imported rear-screen AI widget $productId: $result")
                }
            }.onFailure { error ->
                btm.m.os4.systemuihook.ModuleLog.w(logTag, "Could not register imported rear-screen AI widgets", error)
            }
        }, 2500L)
    }

    companion object {
        private const val AIRDROP_AVAILABILITY_CLASS = "com.miui.mishare.connectivity.c"
        private const val SETTINGS_HEADER_ID = 0x4843_0001L
        private const val MIUI_SETTINGS_HEADER_ADAPTER = "com.android.settings.MiuiSettings\$HeaderAdapter"
        @Volatile private var settingsModuleResourcesLoader: ResourcesLoader? = null
        private const val REAR_SCREEN_APP_WIDGET_PROPERTY = "persist.sys.app.widget.enable"
        private const val REAR_SCREEN_APP_WIDGET_SETTING = "subscreen_app_widget_enable"
        private const val REAR_SCREEN_THEME_WIDGET_SETTING = "theme_rear_widget"
        private const val REAR_SCREEN_AOD_SETTING = "rear_doze_always_on"
        private const val REAR_SCREEN_AOD_MODE_SETTING = "rear_aod_mode_user_set"
        private const val REAR_SCREEN_STOCK_REMINDER_SETTING = "key_back_screen_rear_stock_reminder_enabled"
        private val personalAssistantRearScreenRequest = ThreadLocal.withInitial<Boolean> { false }
    }
}
