// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import btm.m.xiaoaihook.SuperXiaoAiInputHook
import btm.m.xiaoaihook.SuperXiaoAiWindowHook
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.hook.AppBottomNavHooks
import btm.m.liquidglass.hook.LuckinAntiDetectionHooks
import java.util.Collections
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.json.JSONObject

private enum class NotificationMaterialType { NORMAL, MEDIA, FOCUS }

private data class ClockHorizontalDrag(val downX: Float, val initialOffset: Float)

private data class VolumeTuningSnapshot(
    val blurRadius: Int,
    val glassStrength: Int,
    val backgroundOpacity: Int,
    val viewCount: Int,
)


/** Applies the music-lockscreen clock collapse at the OEM TimeView size boundary. */
internal object LockscreenNativeClockScaler {
    private const val SCALE = 0.62f
    @Volatile private var active = false

    fun scaleFor(view: View, value: Float): Float =
        if (active && isKeyguardClock(view)) value * SCALE else value

    fun setActive(value: Boolean) { active = value }

    fun restore() { active = false }

    private fun isKeyguardClock(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("KeyguardClock", ignoreCase = true) ||
                name.contains("ClockContainer", ignoreCase = true)
            ) return true
            current = current.parent as? View
        }
        return false
    }
}



private class ControlCenterSvgDrawable(
    pathData: String,
    private val sourceSize: Float = 380f,
) : Drawable() {
    private val sourcePath: Path = PathParser.createPathFromPathData(pathData)
    private val drawPath = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private var drawableAlpha = 0xFF
    private var tint: ColorStateList? = null

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        matrix.reset()
        matrix.setScale(width / sourceSize, height / sourceSize)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        drawPath.reset()
        sourcePath.transform(matrix, drawPath)
        paint.color = tint?.getColorForState(state, Color.WHITE) ?: Color.WHITE
        paint.alpha = drawableAlpha
        canvas.drawPath(drawPath, paint)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun setTintList(tint: ColorStateList?) { this.tint = tint; invalidateSelf() }
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun onStateChange(state: IntArray): Boolean = tint?.isStateful == true
    override fun isStateful(): Boolean = tint?.isStateful == true
}

private const val CONTROL_CENTER_PLUS_PATH =
    "M352.567 216.376H216.377V352.566C216.377 367.84 204.921 379.295 189.648 379.295C174.374 379.295 162.919 367.84 162.919 352.566V216.376H26.7289C11.4552 216.376 2.38419e-06 204.921 2.38419e-06 189.648C2.38419e-06 174.374 11.4552 162.919 26.7289 162.919H162.919V26.7287C162.919 11.4551 174.374 -0.000180721 189.648 -0.000180721C204.921 -0.000180721 216.377 11.4551 216.377 26.7287V162.919H352.567C367.84 162.919 379.296 174.374 379.296 189.648C379.296 204.921 367.84 216.376 352.567 216.376Z"
private const val CONTROL_CENTER_POWER_PATH =
    "M248.197 0.000213623C263.47 0.000213623 274.926 12.7283 274.926 26.7291V244.379C274.926 259.652 263.47 271.107 248.197 271.107C232.923 271.107 221.468 259.652 221.468 244.379V26.7291C221.468 12.7283 232.923 0.000213623 248.197 0.000213623ZM388.205 77.6412C398.388 66.186 416.207 67.4588 426.389 77.6412C467.119 122.189 492.575 179.466 492.575 244.379C492.575 381.841 379.296 492.575 240.56 488.757C108.188 484.938 9.05991e-06 370.386 3.81842 236.742C5.09122e-06 175.647 30.5473 119.644 70.0042 77.6412C80.1866 67.4588 98.0059 66.186 108.188 77.6412C118.371 87.8237 118.371 104.37 109.461 114.553C77.641 148.918 58.549 194.739 58.549 244.379C58.549 350.021 145.1 435.299 250.742 434.026C355.112 432.753 440.39 343.657 437.845 239.287C436.572 190.921 418.752 147.645 386.932 115.825C378.023 104.37 376.75 87.8237 388.205 77.6412Z"

private val gestureMaterialOverlays = Collections.synchronizedMap(WeakHashMap<View, View>())

class HyperSystemUiModule : XposedModule() {
    private val controlCenterHooks by lazy {
        ControlCenterHooks(
            module = this,
            readContext = ::readContext,
            applicationContext = { systemUiApplicationContext },
        )
    }
    private val notificationHooks = NotificationHooks(
        module = this,
        readInstanceField = ::readInstanceField,
        findDynamicIslandField = ::findDynamicIslandField,
        systemUiPackageName = SYSTEM_UI,
        notificationSettingsManagerClassName = SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS,
        notificationRowInjectorClassName = EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS,
        hideMiniWindowBarKey = KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR,
        logTag = TAG,
    )
    private val lockscreenHooks = LockscreenHooks(this, TAG)
    private val statusBarHooks = StatusBarHooks(
        module = this,
        hookDeoptimized = ::hookDeoptimized,
        readInstanceField = ::readInstanceField,
        readInheritedField = ::readInheritedField,
        systemUiPackageName = SYSTEM_UI,
        keepThemeKey = KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME,
        hideClockTextKey = KEY_HIDE_STATUS_BAR_CLOCK_TEXT,
        mobileNetworkTypeModeKey = KEY_MOBILE_NETWORK_TYPE_MODE,
        stackedMobileDualContainerId = stackedMobileDualContainerId,
        logTag = TAG,
        applicationContext = { systemUiApplicationContext },
    )
    private val systemUiHooks = SystemUiHooks(
        module = this,
        hookDeoptimized = ::hookDeoptimized,
        findDynamicIslandField = ::findDynamicIslandField,
        systemUiPluginPackageName = SYSTEM_UI_PLUGIN,
        dynamicIslandBaseContentClass = DYNAMIC_ISLAND_BASE_CONTENT_CLASS,
        logTag = TAG,
    )
    private val islandSmoothingHook by lazy { IslandSmoothingHook(this) }
    private val lockscreenFaceUnlockHook by lazy { LockscreenFaceUnlockHook(this) }
    private val unlockFocusAuthHook by lazy { UnlockFocusAuthHook(this) }
    internal fun installHook(member: java.lang.reflect.Executable) = hook(member)

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (!HookModuleSafety.enter(this, "system")) return
        // system_server never goes through onPackageLoaded, so its hooks have to be installed here.
        runCatching {
            installFrameworkHooks(param.classLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install system framework hooks", error)
        }
    }

    private val othersHooks = OthersHooks(
        module = this,
        hookDeoptimized = ::hookDeoptimized,
        settingsResourcesLock = HyperSystemUiModule::class.java,
        logTag = TAG,
    )






    private fun readContext(target: Any): Context? = runCatching {
        target.javaClass.methods.firstOrNull { it.name in setOf("getContext", "getMContext") && it.parameterCount == 0 }?.invoke(target) as? Context
    }.getOrNull() ?: runCatching {
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstNotNullOfOrNull { field ->
                field.isAccessible = true
                field.get(target) as? Context
            }
    }.getOrNull()







    /**
     * Rewrites the visible titles of the Settings dashboard entries.
     *
     * `PreferenceActivity.Header.getTitle()` prefers `titleRes` over `title`, and `titleRes` is also
     * reused for the second-layer page title and for click analytics, so mutating the header would
     * break those. The bound row view is retitled instead, which touches display text only.
     *
     * The header id's resource entry name (`my_device`, `bluetooth_settings`, ...) is stable across
     * Settings builds; the numeric ids are not.
     */
    /**
     * Keeps the lift-to-wake gesture alive while the configured window is open.
     *
     * `KeyguardSensorInjector.updatePickupSensorRegistration` decides whether to listen for the lift
     * gesture from two flags: `mWakeupByPickUp` and the system pickup switch. Only the first is ours
     * to override, so inside the window it is forced on before the native evaluation runs; outside it
     * is left untouched so the user's own setting keeps working.
     */
    /**
     * Registers the lift-to-wake pickup sensor during the configured window.
     *
     * `updatePickupSensorRegistration` gates on two things: `mWakeupByPickUp` and the system pickup
     * switch. The switch is not ours to flip, and forcing the flag also broke the AOD wake path. So
     * neither is touched here: the injector instance is captured and the pickup listener is
     * registered directly while the window is open, then released once it closes. When the native
     * code already has the sensor registered its own handling wins and this stays out of the way.
     */
    private fun installLiftToWakeScheduleHook(loader: ClassLoader, prefs: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val cls = loader.loadClass("com.android.keyguard.injector.KeyguardSensorInjector")
        val instance = AtomicReference<Any?>(null)
        cls.declaredConstructors.forEach { constructor ->
            hook(constructor)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:lift-to-wake-instance")
                .intercept { chain ->
                    val created = chain.proceed()
                    if (created != null) instance.set(created)
                    created
                }
        }
        val main = Handler(Looper.getMainLooper())
        val registered = AtomicBoolean(false)
        val tick = object : Runnable {
            override fun run() {
                runCatching { applyLiftToWakeSchedule(cls, instance.get(), registered, prefs) }
                main.postDelayed(this, 20_000L)
            }
        }
        main.post(tick)
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lift-to-wake schedule hook")
    }

    private fun applyLiftToWakeSchedule(
        cls: Class<*>,
        injector: Any?,
        registered: AtomicBoolean,
        prefs: SharedPreferences,
    ) {
        if (injector == null) return
        val schedule = readLiftToWakeSchedulePreferences(prefs)
        val active = schedule.isActiveAt(currentMinuteOfDay())
        val nativeRegistered = runCatching {
            cls.getDeclaredField("mCurrentPickupSensorRegistered").apply { isAccessible = true }.getBoolean(injector)
        }.getOrDefault(false)
        val manager = runCatching {
            cls.getDeclaredField("mSensorManager").apply { isAccessible = true }.get(injector)
        }.getOrNull() as? SensorManager ?: return
        val listener = runCatching {
            cls.getDeclaredField("mPickupSensorListener").apply { isAccessible = true }.get(injector)
        }.getOrNull() as? SensorEventListener ?: return
        val sensorType = runCatching {
            cls.getDeclaredField("WAKEUP_AND_SLEEP_SENSOR_XIAOMI").apply { isAccessible = true }.getInt(injector)
        }.getOrDefault(33171036)
        if (active && !nativeRegistered && !registered.get()) {
            val sensor = manager.getDefaultSensor(sensorType, true) ?: return
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            registered.set(true)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Lift-to-wake window opened, pickup sensor registered")
        } else if ((!active || nativeRegistered) && registered.get()) {
            manager.unregisterListener(listener)
            registered.set(false)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Lift-to-wake window closed, pickup sensor released")
        }
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }







    /**
     * Hooks for the "System Framework" page. Everything here runs inside system_server.
     *
     * Each block is guarded on its own so that one missing class on a given ROM cannot disable the
     * rest. The switches are re-read on every call, so toggling one takes effect without restarting
     * system_server.
     */
    /**
     * Resolves the classloader that holds `miui-services.jar`.
     *
     * The platform keeps its MIUI implementations in that jar and loads it through
     * `MiuiStubRegistry` as a separate PathClassLoader, so those classes are not visible from the
     * system server loader. The registry caches the loader, which is the cheapest way to reach it.
     */
    private fun resolveMiuiServicesLoader(loader: ClassLoader): ClassLoader? {
        val registry = runCatching { loader.loadClass("com.miui.base.MiuiStubRegistry") }.getOrNull()
            ?: return null
        val cache = runCatching {
            registry.getDeclaredField("sExtClassLoaderCache").apply { isAccessible = true }.get(null)
        }.getOrNull() as? java.util.Map<*, *> ?: return null
        // More than one framework class loader can be present (for example the framework and
        // miui-services jars).  The old implementation picked HashMap.values.first(), which is
        // nondeterministic and frequently selected a loader that cannot see MIUI server classes.
        val cached = cache.values().asSequence().filterIsInstance<ClassLoader>().toList()
        val providerLoaders = runCatching {
            val providers = registry.getDeclaredField("sManifestStubs").apply { isAccessible = true }
                .get(null) as? java.util.Map<*, *>
            providers?.values()?.mapNotNull { it?.javaClass?.classLoader } ?: emptyList()
        }.getOrDefault(emptyList())
        return (cached + providerLoaders).distinct().firstOrNull { candidate ->
            runCatching {
                candidate.loadClass("com.android.server.wm.MiuiFreeFormStackDisplayStrategy")
            }.isSuccess
        } ?: (cached + providerLoaders).distinct().firstOrNull()
    }

    /** Load a framework class from the boot/system loader or any cached MIUI extension loader. */
    private fun loadFrameworkClass(
        loader: ClassLoader,
        miuiLoader: ClassLoader?,
        name: String,
    ): Class<*>? {
        val candidates = LinkedHashSet<ClassLoader>().apply {
            add(loader)
            miuiLoader?.let(::add)
            runCatching {
                val registry = loader.loadClass("com.miui.base.MiuiStubRegistry")
                val cache = registry.getDeclaredField("sExtClassLoaderCache").apply { isAccessible = true }
                    .get(null) as? java.util.Map<*, *>
                cache?.values()?.filterIsInstance<ClassLoader>()?.forEach(::add)
                val providers = registry.getDeclaredField("sManifestStubs").apply { isAccessible = true }
                    .get(null) as? java.util.Map<*, *>
                providers?.values()?.mapNotNull { it?.javaClass?.classLoader }?.forEach(::add)
            }
        }
        candidates.forEach { candidate ->
            runCatching { candidate.loadClass(name) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(name, false, loader) }.getOrNull()
    }

    /**
     * Hooks [member] after asking ART to drop any inlined copy of it.
     *
     * Private methods that the compiler inlined never reach the hook, which is what happened to
     * getMaxMiuiFreeFormStackCount. HyperCeiler does the same through Deoptimizers.deoptimize.
     */
    internal fun hookDeoptimized(member: java.lang.reflect.Executable) = runCatching {
        deoptimize(member)
    }.let { hook(member) }

    // Reference: REAREye RearScreenActivityWhitelistModule / ExternalDisplayLaunchUnlockModule.
    // This new feature is dispatched only by package `system`, independently of the existing
    // onSystemServerStarting/installFrameworkHooks lifecycle. Do not add android dispatch.
    private val rearActivityPolicyMethods = mutableSetOf<Method>()

    private fun installRearActivityPolicyHooks(loader: ClassLoader, prefs: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.FRAMEWORK)) return
        val miuiLoader = resolveMiuiServicesLoader(loader) ?: loader
        fun install(method: Method, onlyDisplayOne: Boolean = false) {
            if (method in rearActivityPolicyMethods) return
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:rear-activity-policy:${method.toGenericString()}")
                .intercept { chain ->
                    // Retain original callbacks/side effects exactly once, including when enabled.
                    val result = chain.proceed()
                    if (result == false && prefs.isRearMiniPlayerAllowAllActivities() &&
                        (!onlyDisplayOne || chain.getArg(2) == 1)) true else result
                }
            rearActivityPolicyMethods += method
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed rear Activity policy: ${method.toGenericString()}")
        }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.ActivityStarterImpl")
                ?: error("MIUI ActivityStarterImpl unavailable")
            val methods = cls.declaredMethods.filter { method ->
                method.returnType == Boolean::class.javaPrimitiveType && when (method.name) {
                    "isShouldShowOnRearDisplay" -> method.parameterCount == 1 &&
                        method.parameterTypes[0].name in setOf("android.content.pm.ActivityInfo", "com.android.server.wm.ActivityRecord")
                    "isAllowedToStartOnRearDisplay" -> method.parameterTypes.firstOrNull()?.name == "com.android.server.wm.ActivityRecord"
                    else -> false
                }
            }
            require(methods.any { it.name == "isShouldShowOnRearDisplay" }) { "Rear Activity eligibility method unavailable" }
            require(methods.any { it.name == "isAllowedToStartOnRearDisplay" }) { "Rear Activity start method unavailable" }
            methods.forEach { install(it) }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install rear Activity eligibility policy", it) }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.ActivityTaskSupervisor")
                ?: error("ActivityTaskSupervisor unavailable")
            val methods = cls.declaredMethods.filter { method ->
                method.name == "isCallerAllowedToLaunchOnDisplay" && method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.map { it.name } == listOf("int", "int", "int", "android.content.pm.ActivityInfo")
            }
            require(methods.isNotEmpty()) { "External display caller check unavailable" }
            methods.forEach { install(it, onlyDisplayOne = true) }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install rear display caller policy", it) }
    }

    private fun installFrameworkHooks(loader: ClassLoader, prefs: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.FRAMEWORK)) return
        val miuiLoader = resolveMiuiServicesLoader(loader) ?: loader
        fun on(block: (FrameworkPreferences) -> Boolean): Boolean {
            return runCatching { block(readFrameworkPreferences(prefs)) }.getOrDefault(false)
        }

        // ---- Small window ----
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.MiuiFreeFormStackDisplayStrategy")
                ?: return@runCatching
            cls.declaredMethods.filter {
                it.name == "getMaxMiuiFreeFormStackCount" ||
                    it.name == "getMaxMiuiFreeFormStackCountForFlashBack"
            }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-multiple-freeform-${method.name}")
                    .intercept { chain -> if (on { it.multipleFreeform }) 256 else chain.proceed() }
            }
        }
        runCatching {
            // getMaxMiuiFreeFormStackCount is private and gets inlined, so its calls never reach the
            // hook above. It simply returns mDefaultMaxFreeformCount, which is a plain field set to 2
            // and never written again - raising it in the constructor is what actually lifts the cap.
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.MiuiFreeFormStackDisplayStrategy")
                ?: return@runCatching
            val field = cls.getDeclaredField("mDefaultMaxFreeformCount").apply { isAccessible = true }
            cls.declaredConstructors.forEach { constructor ->
                hook(constructor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-multiple-freeform-ctor")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (on { it.multipleFreeform }) {
                            runCatching { field.setInt(chain.thisObject, 64) }
                        }
                        result
                    }
            }
        }
        runCatching {
            // The instance already exists before the hooks are installed, so the constructor hook
            // never fires. This method runs on every freeform task add and is the reliable trigger.
            // Note the typo in the real name: onMiuiFreeFormStasckAdded, not StackAdded.
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.MiuiFreeFormStackDisplayStrategy")
                ?: return@runCatching
            val field = cls.getDeclaredField("mDefaultMaxFreeformCount").apply { isAccessible = true }
            cls.declaredMethods.filter { it.name == "onMiuiFreeFormStasckAdded" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-multiple-freeform-added")
                    .intercept { chain ->
                        if (on { it.multipleFreeform }) {
                            runCatching { field.setInt(chain.thisObject, 64) }
                        }
                        chain.proceed()
                    }
            }
        }
        runCatching {
            val cls = loader.loadClass("android.app.ActivityTaskManager")
            cls.declaredMethods.filter { it.name == "supportsSplitScreen" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-freeform-split")
                    .intercept { chain -> if (on { it.forceFreeform }) true else chain.proceed() }
            }
        }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.Task")
                ?: return@runCatching
            cls.declaredMethods.filter { it.name == "isResizeable" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-freeform-resizeable-${method.parameterCount}")
                    .intercept { chain -> if (on { it.forceFreeform }) true else chain.proceed() }
            }
        }
        // The framework checks these lists before it reaches Task.isResizeable().  Clear both
        // static lists and their cloud-backed accessors, matching HyperCeiler's rule.
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "android.util.MiuiMultiWindowAdapter")
                ?: return@runCatching
            listOf(
                "FREEFORM_BLACK_LIST",
                "ABNORMAL_FREEFORM_BLACK_LIST",
                "START_FROM_FREEFORM_BLACK_LIST_ACTIVITY",
                "FOREGROUND_PIN_APP_BLACK_LIST",
            ).takeIf { on { it.forceFreeform } }?.forEach { fieldName ->
                runCatching {
                    cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(null, ArrayList<String>())
                }
            }
            listOf(
                "getFreeformBlackList",
                "getFreeformBlackListFromCloud",
                "getAbnormalFreeformBlackList",
                "getAbnormalFreeformBlackListFromCloud",
                "getStartFromFreeformBlackList",
                "getStartFromFreeformBlackListFromCloud",
                "getForegroundPinAppBlackList",
                "getForegroundPinAppBlackListFromCloud",
            ).forEach { methodName ->
                cls.declaredMethods.filter { it.name == methodName }.forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-force-freeform-list-$methodName-${method.parameterCount}")
                        .intercept { chain ->
                            if (on { it.forceFreeform }) ArrayList<Any?>() else chain.proceed()
                        }
                }
            }
        }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "android.util.MiuiMultiWindowUtils")
                ?: return@runCatching
            listOf("isForceResizeable", "supportFreeform").forEach { methodName ->
                cls.declaredMethods.filter { it.name == methodName }.forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-force-freeform-utils-$methodName-${method.parameterCount}")
                        .intercept { chain -> if (on { it.forceFreeform }) true else chain.proceed() }
                }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")
            cls.declaredMethods.filter { it.name == "retrieveSettings" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-freeform-settings")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (on { it.forceFreeform }) {
                            runCatching {
                                cls.getDeclaredField("mDevEnableNonResizableMultiWindow")
                                    .apply { isAccessible = true }
                                    .setBoolean(chain.thisObject, true)
                            }
                        }
                        result
                    }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.wm.WindowManagerService\$SettingsObserver")
            cls.declaredMethods.filter { it.name == "updateDevEnableNonResizableMultiWindow" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-freeform-observer")
                    .intercept { chain -> if (on { it.forceFreeform }) null else chain.proceed() }
            }
        }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.MiuiFreeFormGestureController")
                ?: return@runCatching
            val target = cls.declaredMethods.firstOrNull { it.name == "needForegroundPin" && it.parameterCount == 1 }
                ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-freeform-background")
                .intercept { chain -> if (on { it.freeformBackground }) true else chain.proceed() }
        }
        // "Remember state and position" has no hook yet: ActivityStarterInjector only exposes
        // startActivityUncheckedBefore on this build, and the bounds persistence lives behind it.

        // ---- Volume ----
        runCatching {
            // SoundDoseHelperStub is an interface resolved through MiuiStubUtil, so hook the consumer.
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.audio.SoundDoseHelper")
                ?: return@runCatching
            val target = cls.declaredMethods.firstOrNull {
                it.name == "safeMediaVolumeIndex" && it.parameterCount == 1
            } ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-volume-safe")
                .intercept { chain ->
                    when {
                        on { it.volumeSafeMode == VOLUME_SAFE_ALWAYS } -> 2147483646
                        on { it.volumeSafeMode == VOLUME_SAFE_HEADSET_ONLY } && hasHeadsetOutput() -> 2147483646
                        else -> chain.proceed()
                    }
                }
        }
        runCatching {
            // SoundDoseHelper delegates the platform limit to this MIUI implementation during
            // initialization and whenever the safe-volume policy is refreshed.  Hooking only the
            // consumer below leaves the cached MIUI value unchanged, so the warning immediately
            // comes back on the next refresh.
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.audio.SoundDoseHelperStubImpl")
                ?: return@runCatching
            cls.declaredMethods.filter {
                it.name == "updateSafeMediaVolumeIndex" && it.parameterCount == 1
            }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-volume-safe-stub")
                    .intercept { chain ->
                        when {
                            on { it.volumeSafeMode == VOLUME_SAFE_ALWAYS } -> 2147483646
                            on { it.volumeSafeMode == VOLUME_SAFE_HEADSET_ONLY } && hasHeadsetOutput() -> 2147483646
                            else -> chain.proceed()
                        }
                    }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.audio.AudioService\$VolumeController")
            val target = cls.declaredMethods.firstOrNull { it.name == "suppressAdjustment" && it.parameterCount == 3 }
                ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-volume-instant")
                .intercept { chain ->
                    val result = chain.proceed()
                    val streamType = chain.getArg(0) as? Int
                    val isMuteAdjust = chain.getArg(2) as? Boolean
                    if (on { it.volumeInstant } && streamType == android.media.AudioManager.STREAM_MUSIC && isMuteAdjust == false) {
                        // The platform rule only clears the result when a controller is attached,
                        // otherwise the mute-first behaviour is required to reach the controller.
                        val controller = runCatching {
                            cls.getDeclaredField("mController").apply { isAccessible = true }
                                .get(chain.thisObject)
                        }.getOrNull()
                        if (controller != null) false else result
                    } else {
                        result
                    }
                }
        }

        // ---- Features ----
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.ForceDarkAppListManager")
                ?: return@runCatching
            val buildCls = loadFrameworkClass(loader, miuiLoader, "miui.os.Build")
                ?: return@runCatching
            val flag = buildCls.getDeclaredField("IS_INTERNATIONAL_BUILD").apply { isAccessible = true }
            cls.declaredMethods.filter { it.name == "getDarkModeAppList" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-dark-list")
                    .intercept { chain ->
                        if (!on { it.forceDarkAllApps }) return@intercept chain.proceed()
                        val original = runCatching { flag.getBoolean(null) }.getOrDefault(false)
                        runCatching { flag.setBoolean(null, true) }
                        try {
                            chain.proceed()
                        } finally {
                            runCatching { flag.setBoolean(null, original) }
                        }
                    }
            }
            cls.declaredMethods.filter { it.name == "shouldShowInSettings" && it.parameterCount == 1 }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-force-dark-show")
                    .intercept { chain ->
                        if (!on { it.forceDarkAllApps }) return@intercept chain.proceed()
                        val info = chain.getArg(0) as? android.content.pm.ApplicationInfo
                        info != null && info.uid >= 10000 &&
                            info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    }
            }
        }
        runCatching {
            val cls = loader.loadClass("android.content.Intent")
            cls.declaredMethods.filter { it.name == "setFlags" && it.parameterCount == 1 }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-recents-visibility")
                    .intercept { chain ->
                        val flags = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                        if (!on { it.recentsVisibility }) return@intercept chain.proceed()
                        // 0x00800000 is FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS. Rewriting the argument is
                        // what the platform-level rule does; passing new args re-enters the method.
                        runCatching { chain.args[0] = flags and 0xFF7FFFFF.toInt() }
                        chain.proceed()
                    }
            }
        }
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.pm.PackageManagerServiceImpl")
                ?: return@runCatching
            // isCTS alone is not enough: the installer only takes the stock path while the caller is
            // inside one of these three methods, so the flag has to be scoped to them.
            val fakeCts = java.util.concurrent.atomic.AtomicBoolean(false)
            cls.declaredMethods.firstOrNull { it.name == "isCTS" && it.parameterCount == 0 }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-native-installer-cts")
                    .intercept { chain ->
                        if (on { it.nativeInstaller } && fakeCts.get()) true else chain.proceed()
                    }
            }
            listOf("hookChooseBestActivity", "updateDefaultPkgInstallerLocked", "assertValidApkAndInstaller")
                .forEach { name ->
                    cls.declaredMethods.filter { it.name == name }.forEach { method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("hyperchanger:fw-native-installer-$name")
                            .intercept { chain ->
                                if (!on { it.nativeInstaller }) return@intercept chain.proceed()
                                fakeCts.set(true)
                                try {
                                    chain.proceed()
                                } finally {
                                    fakeCts.set(false)
                                }
                            }
                    }
                }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")
            val target = cls.declaredMethods.firstOrNull { it.name == "rescheduleStrongAuthTimeoutAlarm" }
                ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-pin-72h")
                .intercept { chain -> if (on { it.disablePin72h }) null else chain.proceed() }
        }

        // ---- Screenshot ----
        // These methods are commonly inlined by ART before the first screenshot is taken.  Drop
        // the compiled copies so the hooks below are reached, matching HyperCeiler's approach.
        runCatching {
            listOf(
                "com.android.server.wm.WindowStateAnimator" to listOf("createSurfaceLocked"),
                "com.android.server.wm.WindowManagerService" to listOf("relayoutWindow"),
            ).forEach { (name, methods) ->
                loader.loadClass(name).declaredMethods
                    .filter { it.name in methods }
                    .forEach { deoptimize(it) }
            }
            for (index in 0 until 20) {
                runCatching {
                    val cls = loader.loadClass("com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index")
                    if (java.util.function.BiConsumer::class.java.isAssignableFrom(cls)) {
                        cls.declaredMethods.filter { it.name == "accept" }.forEach { deoptimize(it) }
                    }
                }
                runCatching {
                    val cls = loader.loadClass("com.android.server.wm.DisplayContent\$$index")
                    if (java.util.function.BiPredicate::class.java.isAssignableFrom(cls)) {
                        cls.declaredMethods.filter { it.name == "test" }.forEach { deoptimize(it) }
                    }
                }
            }
        }
        runCatching {
            // Moved out of PhoneWindowManager into KeyGestureController on this platform.
            val cls = loader.loadClass("com.android.server.input.KeyGestureController")
            val target = cls.declaredMethods.firstOrNull {
                it.name == "getScreenshotChordLongPressDelay" && it.parameterCount == 0
            } ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-quick-screenshot")
                .intercept { chain -> if (on { it.quickScreenshot }) 0L else chain.proceed() }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.wm.WindowState")
            val target = cls.declaredMethods.firstOrNull { it.name == "isSecureLocked" && it.parameterCount == 0 }
                ?: return@runCatching
            hookDeoptimized(target)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:fw-screenshot-anywhere")
                .intercept { chain ->
                    if (!on { it.screenshotAnywhere }) return@intercept chain.proceed()
                    // Keep the secure bit when a surface is created, otherwise the framework's
                    // capture-time checks see a non-secure window while the actual surface still
                    // remains protected.  This is the same call-site guard used by HyperCeiler.
                    val preserve = Thread.currentThread().stackTrace
                        .take(14)
                        .any { frame ->
                            frame.methodName == "setInitialSurfaceControlProperties" ||
                                frame.methodName == "createSurfaceLocked"
                        }
                    if (preserve) chain.proceed() else false
                }
        }
        // HyperOS adds a MIUI policy gate before ScreenCaptureInternal is reached.  Clearing the
        // secure bit alone therefore still returns error code 2 from WindowManagerService.
        runCatching {
            val cls = loadFrameworkClass(loader, miuiLoader, "com.android.server.wm.WindowManagerServiceImpl")
                ?: return@runCatching
            cls.declaredMethods.filter { it.name == "notAllowCaptureDisplay" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-screenshot-anywhere-miui-gate-${method.parameterCount}")
                    .intercept { chain -> if (on { it.screenshotAnywhere }) false else chain.proceed() }
            }
        }
        // Match HyperCeiler's companion hooks so apps cannot re-enable the capture policy through
        // screen-capture observer registration while this switch is enabled.
        runCatching {
            val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")
            cls.declaredMethods.filter { it.name == "registerScreenCaptureObserver" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-screenshot-observer-${method.parameterCount}")
                    .intercept { chain -> if (on { it.screenshotAnywhere }) null else chain.proceed() }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.wm.WindowManagerService")
            cls.declaredMethods.filter { it.name == "registerScreenRecordingCallback" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-screen-recording-observer-${method.parameterCount}")
                    .intercept { chain -> if (on { it.screenshotAnywhere }) false else chain.proceed() }
            }
        }
        // Xiaomi's hotspot/device-name policy lives in the Wi-Fi service extension and is loaded
        // through SystemServiceManager with its own class loader. Install it both when already
        // visible and when the service loader is asked for the class later.
        runCatching {
            val hookWifiUtils: (ClassLoader) -> Unit = { wifiLoader ->
                runCatching {
                    val cls = wifiLoader.loadClass("com.android.server.wifi.Utils")
                    cls.declaredMethods.filter { it.name == "checkDeviceNameIsIllegalSync" }.forEach { method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("hyperchanger:fw-device-name-wifi-${method.parameterCount}")
                            .intercept { chain ->
                                if (on { it.disableDeviceNameCheck }) false else chain.proceed()
                            }
                    }
                }
            }
            hookWifiUtils(loader)
            loadFrameworkClass(loader, miuiLoader, "com.android.server.wifi.Utils")?.classLoader?.let(hookWifiUtils)
            val manager = loader.loadClass("com.android.server.SystemServiceManager")
            val installed = java.util.concurrent.atomic.AtomicBoolean(false)
            manager.declaredMethods.filter { it.name == "loadClassFromLoader" && it.parameterCount == 2 }
                .forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:fw-device-name-wifi-loader")
                        .intercept { chain ->
                            val name = chain.getArg(0) as? String
                            val classLoader = chain.getArg(1) as? ClassLoader
                            if (name == "com.android.server.wifi.WifiService" && classLoader != null &&
                                installed.compareAndSet(false, true)
                            ) {
                                hookWifiUtils(classLoader)
                            }
                            chain.proceed()
                        }
                }
        }
        runCatching {
            // Clearing isSecureLocked only drops the surface flag; the capture path still filters
            // protected content, so relax the secure-content policy as well.  Android 15/16 use
            // ScreenCaptureInternal while older builds expose the same natives on ScreenCapture.
            val captureTargets = listOf(
                "android.window.ScreenCaptureInternal" to "android.window.ScreenCaptureInternal\$CaptureArgs",
                "android.window.ScreenCapture" to "android.window.ScreenCapture\$CaptureArgs",
            )
            captureTargets.forEach { (captureName, argsName) ->
                runCatching {
                    val cls = loader.loadClass(captureName)
                    val argsCls = loader.loadClass(argsName)
                    val policy = runCatching {
                        argsCls.getDeclaredField("mSecureContentPolicy").apply { isAccessible = true }
                    }.getOrNull() ?: argsCls.getDeclaredField("mCaptureSecureLayers").apply { isAccessible = true }
                    listOf("nativeCaptureDisplay", "nativeCaptureLayers").forEach { name ->
                        cls.declaredMethods.filter { it.name == name }.forEach { method ->
                            hookDeoptimized(method)
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .setId("hyperchanger:fw-screen-capture-$captureName-$name")
                                .intercept { chain ->
                                    if (on { it.screenshotAnywhere }) {
                                        runCatching {
                                            if (policy.type == Boolean::class.javaPrimitiveType) {
                                                policy.setBoolean(chain.getArg(0), true)
                                            } else {
                                                policy.setInt(chain.getArg(0), 1)
                                            }
                                        }
                                    }
                                    chain.proceed()
                                }
                        }
                    }
                }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            cls.declaredMethods.filter { it.name == "createVirtualDisplay" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-screenshot-virtual-display-${method.parameterCount}")
                    .intercept { chain ->
                        if (on { it.screenshotAnywhere } && chain.args.size > 1 && chain.getArg(1) is Boolean) {
                            chain.args[1] = true
                        }
                        chain.proceed()
                    }
            }
        }
        runCatching {
            val cls = loader.loadClass("com.android.server.display.VirtualDisplayAdapter")
            cls.declaredMethods.filter { it.name == "createVirtualDisplayLocked" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:fw-screenshot-virtual-display-locked-${method.parameterCount}")
                    .intercept { chain ->
                        if (!on { it.screenshotAnywhere } || chain.args.size <= 3) {
                            return@intercept chain.proceed()
                        }
                        val projection = chain.getArg(1)
                        val ownerUid = chain.getArg(2) as? Int
                        if (ownerUid != null && ownerUid >= 10000 && projection == null) {
                            return@intercept chain.proceed()
                        }
                        for (index in 3 until chain.args.size) {
                            val value = chain.getArg(index) as? Int ?: continue
                            chain.args[index] = value or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                            break
                        }
                        chain.proceed()
                    }
            }
        }
    }

    /** True when a headset is currently connected, used by the headset-only volume mode. */
    private fun hasHeadsetOutput(): Boolean = runCatching {
        val context = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context ?: return@runCatching false
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return@runCatching false
        audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                -> true
                else -> false
            }
        }
    }.getOrDefault(false)



    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!HookModuleSafety.enter(this, param.packageName)) return
        if (!OsCompatibility.areHooksAllowed()) return
        if (param.packageName == "com.android.systemui") {
            runCatching {
                installLiftToWakeScheduleHook(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lift-to-wake schedule hook", error) }
        }
        if (param.packageName == "com.miui.misound") {
            runCatching {
                othersHooks.installSoundEffectHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install sound effect hooks", error) }
            return
        }
        if (param.packageName == "com.miui.screenshot") {
            runCatching {
                othersHooks.installScreenshotHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install screenshot shell hooks", error) }
            return
        }
        if (param.packageName == "com.android.systemui") {
            runCatching {
                controlCenterHooks.installOldControlCenterHook(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install old control center hook", error) }
        }
        if (param.packageName == "com.xiaomi.misettings") {
            runCatching {
                othersHooks.installMiSettingsHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install Xiaomi Settings hooks", error) }
            return
        }
        if (param.packageName == "system") {
            runCatching {
                installRearActivityPolicyHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install rear Activity policies", error) }
            // HyperCeiler hooks system_server through onPackageLoaded, so this path is known to work
            // even though onSystemServerStarting also fires. Either one installs the framework hooks.
            runCatching {
                installFrameworkHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install system framework hooks", error) }
            return
        }
        if (param.packageName == RearMiniPlayerLaunch.MUSIC_PACKAGE) {
            runCatching { othersHooks.installRearMiniPlayerMusicHooks(getRemotePreferences(REMOTE_PREFERENCE_GROUP)) }
                .onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install NetEase rear mini player hooks", error) }
            return
        }
        if (param.packageName == "com.miui.home") {
            runCatching {
                val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
                syncHomeRecentsNativeConfig(
                    param, readHomeRecentsSettings(preferences), readHomeFolderSettings(preferences), readHomeMaterialSettings(preferences), readHomeNativeDockSettings(preferences), readHomeMultiSplitSettings(preferences), resolveHomeGlassNativeSettings(preferences),
                )
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not sync native desktop recents config", error)
            }
            return
        }
        if (param.packageName == "com.xiaomi.shop" || param.packageName == "com.mipay.wallet" ||
            param.packageName == "com.mi.health" || param.packageName == "com.apple.android.music" ||
            param.packageName == "com.sina.weibo" || param.packageName == "com.xingin.xhs" ||
            param.packageName == "com.lucky.luckyclient" || param.packageName == "com.tencent.wemeet.app" ||
            param.packageName == "cmb.pb"
        ) {
            othersHooks.installAppNavigation(param)
            return
        }
        if (param.packageName == SETTINGS_PACKAGE) {
            runCatching {
                othersHooks.installSettingsAppScopeHooks(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install Settings app scope hooks", error) }
            runCatching {
                othersHooks.installSettingsAppEntryHook(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install Settings app entry hook", error) }
            runCatching {
                othersHooks.installSettingsEntryLabelHook(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install Settings entry label hook", error) }
            return
        }
        if (param.packageName == AIRDROP_PACKAGE) {
            runCatching { othersHooks.installAirDropForceEnableHooks(param) }
                .onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install MiShare AirDrop hooks", error) }
            return
        }
        if (param.packageName == LOCKSCREEN_WALLPAPER) {
            // HyperMusicCover's original Java WallpaperProbe is registered separately through
            // java_init.list and owns this process's GL upload hook.
            return
        }
        if (param.packageName == XMSF_PACKAGE) {
            runCatching { unlockFocusAuthHook.install(param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP)) }
                .onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install focus authentication hook", error) }
            return
        }
        if (param.packageName == "com.miui.voiceassist") {
            runCatching {
                SuperXiaoAiWindowHook.install(this, param.defaultClassLoader, getRemotePreferences(REMOTE_PREFERENCE_GROUP))
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install Super XiaoAI floating window hooks", error) }
            return
        }
        if (param.packageName !in SYSTEM_UI_TARGETS) return
        if (param.packageName == SUPER_XIAOAI_IME || param.packageName == SUPER_XIAOAI_PHRASE) {
            installSuperXiaoAiHooks(param.packageName, param.defaultClassLoader)
            return
        }
        runCatching {
            val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            when (param.packageName) {
                SYSTEM_UI, SYSTEM_UI_PLUGIN -> {
                    controlCenterHooks.installHeaderHooks(param.defaultClassLoader, preferences)
                    statusBarHooks.installImportedIconHooks(preferences)
                    if (param.packageName == SYSTEM_UI) {
                        statusBarHooks.installAdjustmentHooks(param.defaultClassLoader, preferences)
                    }
                    islandSmoothingHook.install(param.defaultClassLoader, preferences)
                    if (param.packageName == SYSTEM_UI) {
                        lockscreenFaceUnlockHook.install(param.defaultClassLoader, preferences)
                    }
                    controlCenterHooks.installCustomTileHooks(param.defaultClassLoader, preferences)
                    if (param.packageName == SYSTEM_UI) {
                        synchronized(controlCenterButtonsLock) {
                            systemUiClassLoader = param.defaultClassLoader
                        }
                        installHyperMusicCoverGestureBridge(param.defaultClassLoader, preferences)
                        scheduleSoftGlassThemeActivation(preferences)
                    }
                    if (!resourceHooksInstalled) {
                        installDimensionHooks(preferences)
                        resourceHooksInstalled = true
                    }
                    if (!cornerHooksInstalled) {
                        installCornerRadiusHooks(preferences)
                        cornerHooksInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI_PLUGIN) {
                        installKnownCornerRadiusHooks(param.defaultClassLoader, preferences)
                    }
                    // Depending on the SystemUI build, the plugin classes can be loaded by
                    // SystemUI's class loader without a separate plugin package callback.
                    if (!controlCenterEditButtonHookInstalled ||
                        !controlCenterContentDistributorHookInstalled ||
                        !controlCenterTopButtonsHookInstalled ||
                        !controlCenterMainPanelHookInstalled
                    ) {
                        installControlCenterEditButtonHook(param.defaultClassLoader, preferences)
                        scheduleControlCenterHookRetries(param.defaultClassLoader, preferences)
                    }
                    if (!globalActionsHookInstalled) {
                        installGlobalActionsHook(param.defaultClassLoader)
                    }
                    if (!dynamicIslandClassDiscoveryInstalled) {
                        installDynamicIslandClassDiscovery(preferences, param.defaultClassLoader)
                        dynamicIslandClassDiscoveryInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI_PLUGIN && !dynamicIslandHooksInstalled) {
                        installDynamicIslandHooks(param.defaultClassLoader, preferences)
                        dynamicIslandHooksInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !mediaSourceIconHooksInstalled) {
                        installMediaSourceIconHooks(param.defaultClassLoader, preferences)
                        mediaSourceIconHooksInstalled = true
                    }
                    if (!volumePanelHooksInstalled) {
                        installVolumePanelHooks(param.defaultClassLoader, preferences)
                        volumePanelHooksInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !aospVolumePanelHooksInstalled) {
                        installAospVolumePanelFallback(param.defaultClassLoader, preferences)
                        aospVolumePanelHooksInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !focusIslandWhitelistSystemUiHooksInstalled) {
                        focusIslandWhitelistSystemUiHooksInstalled =
                            installFocusIslandWhitelistSystemUiHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI) {
                        notificationHooks.installNotificationRestrictions(param.defaultClassLoader, preferences)
                        notificationHooks.installNotificationTextHooks(param.defaultClassLoader, preferences)
                        notificationHooks.installNotificationStackBlurHooks(param.defaultClassLoader, preferences)
                    }
                    notificationHooks.installFlashlightMaterialHook(param.defaultClassLoader, preferences)
                    if (!focusIslandWhitelistPluginHooksInstalled) {
                        // The plugin is commonly loaded into SystemUI's class loader and may not
                        // receive a separate package callback.  Try the current loader first;
                        // class-load discovery below will retry when the plugin appears later.
                        focusIslandWhitelistPluginHooksInstalled =
                            installFocusIslandWhitelistPluginHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenNotificationHookInstalled) {
                        installLockscreenNotificationHook(param.defaultClassLoader, preferences)
                        lockscreenNotificationHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenMediaNotificationHookInstalled) {
                        // Keep the imported Main.java as the owner of the card's animation and
                        // wallpaper. This hook only supplies the real MIUI header lifecycle and
                        // the artwork gesture bridge that Main cannot see below the window.
                        installLockscreenMediaNotificationHook(param.defaultClassLoader, preferences)
                        lockscreenMediaNotificationHookInstalled = true
                    }
                    // The native clock already keeps the date attached while its glyph group is
                    // scaled for music lockscreen. A second date-follow translation fights that
                    // layout and can move the date off-screen, so do not install the legacy
                    // avoidance hook here.
                    lockscreenClockDateFollowHookInstalled = true
                    if (param.packageName == SYSTEM_UI && !systemUiLockscreenClockColonHookInstalled) {
                        installLockscreenClockColonHook(param.defaultClassLoader, preferences, "systemui")
                        systemUiLockscreenClockColonHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiLockscreenClockWidthHookInstalled) {
                        installLockscreenBigClockWidthHook(param.defaultClassLoader, preferences, "systemui")
                        systemUiLockscreenClockWidthHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiLockscreenClockHorizontalHookInstalled) {
                        installLockscreenClockHorizontalRectHook(param.defaultClassLoader, preferences, "systemui")
                        systemUiLockscreenClockHorizontalHookInstalled = true
                    }
                    if (!lockscreenCarrierHideHookInstalled) {
                        lockscreenCarrierHideHookInstalled = installLockscreenCarrierHideHook(
                            param.defaultClassLoader,
                            preferences,
                        )
                    }
                    // HyperMusicCover hooks TimeView itself and applies its original clock
                    // response/collapse path. Do not stack the former Kotlin size interceptor.
                    systemUiNativeClockScalerHookInstalled = true
                    if (param.packageName == SYSTEM_UI && !fingerprintIconHookInstalled) {
                        installFingerprintIconVisualHook(param.defaultClassLoader, preferences)
                        // This optional visual hook varies between HyperOS builds.  Do not
                        // let a missing vendor method abort all SystemUI hooks installed later.
                        runCatching {
                            installLockscreenFingerprintAnimationHook(param.defaultClassLoader, preferences)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Skipped unsupported lockscreen fingerprint animation hook", error)
                        }
                        fingerprintIconHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiDepthHookInstalled) {
                        installSystemUiDepthHooks(param.defaultClassLoader, preferences)
                        systemUiDepthHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI) {
                        lockscreenHooks.installBottomTextHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenWhiteBarHookInstalled) {
                        lockscreenWhiteBarHookInstalled =
                            installLockscreenWhiteBarHook(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !globalGestureHandleHookInstalled) {
                        globalGestureHandleHookInstalled =
                            installGlobalGestureHandleHook(param.defaultClassLoader, preferences)
                        installGlobalGestureControllerHook(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenShortcutGlassHookInstalled) {
                        installLockscreenShortcutPluginHook(param.defaultClassLoader, preferences)
                        installLockscreenShortcutGlassHook(param.defaultClassLoader, preferences)
                        lockscreenShortcutGlassHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenWidgetSceneVisibilityHookInstalled) {
                        installLockscreenWidgetSceneVisibilityHooks(param.defaultClassLoader)
                        lockscreenWidgetSceneVisibilityHookInstalled = true
                    }
                    // The imported Java module is the music-lockscreen implementation. The old
                    // Kotlin full-screen host is deliberately not installed alongside it.
                    lockscreenMusicLockscreenHookInstalled = true
                    if (param.packageName == SYSTEM_UI && !lockscreenPinCircleBackgroundHookInstalled) {
                        installLockscreenPinCircleBackgroundHook(param.defaultClassLoader, preferences)
                        lockscreenPinCircleBackgroundHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !shadeMaterialHooksInstalled) {
                        installShadeMaterialHooks(preferences, param.defaultClassLoader)
                        installHeadsUpNotificationSoftGlassHooks(preferences, param.defaultClassLoader)
                        shadeMaterialHooksInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !softGlassThemeSystemUiHookInstalled) {
                        installSoftGlassThemeSystemUiHook(param.defaultClassLoader, preferences)
                        softGlassThemeSystemUiHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !softGlassThemePluginFallbackHookInstalled) {
                        // The plugin is commonly loaded into SystemUI's class loader, so its
                        // package callback is not guaranteed to run. Install the same guard here.
                        installSoftGlassThemePluginHook(param.defaultClassLoader, preferences)
                        installSoftGlassThemeClassLoadGuard(preferences)
                        installDefaultThemeStateGuard(param.defaultClassLoader, preferences)
                        softGlassThemePluginFallbackHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !statusBarVisibilityHookInstalled) {
                        installStatusBarVisibilityHook(param.defaultClassLoader, preferences)
                        statusBarVisibilityHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI) {
                        statusBarHooks.installStackedMobileSignalHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI) {
                        statusBarHooks.installIconsLeftHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        installClockMaterialCapabilityHook(param.defaultClassLoader, preferences)
                        systemUiClockMaterialLimitHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI_PLUGIN && !softGlassThemePluginHookInstalled) {
                        installSoftGlassThemePluginHook(param.defaultClassLoader, preferences)
                        softGlassThemePluginHookInstalled = true
                    }
                }
                AOD -> {
                    lockscreenHooks.installShortcutGestureHooks(param.defaultClassLoader, preferences)
                    if (!depthEffectHookInstalled) {
                        installDepthEffectHook(param.defaultClassLoader, preferences)
                        installAodThirdPartyWallpaperDepthHook(param.defaultClassLoader, preferences)
                        installVideoWallpaperGlassSupportHook(param.defaultClassLoader)
                        depthEffectHookInstalled = true
                    }
                    if (!aodClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        installClockMaterialCapabilityHook(param.defaultClassLoader, preferences)
                        installAodClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        aodClockMaterialLimitHookInstalled = true
                    }
                    if (!aodLockscreenClockColonHookInstalled) {
                        installLockscreenClockColonHook(param.defaultClassLoader, preferences, "aod")
                        aodLockscreenClockColonHookInstalled = true
                    }
                    if (!aodLockscreenClockWidthHookInstalled) {
                        installLockscreenBigClockWidthHook(param.defaultClassLoader, preferences, "aod")
                        installLockscreenBigClockEditorWidthHook(param.defaultClassLoader, preferences)
                        aodLockscreenClockWidthHookInstalled = true
                    }
                    if (!aodLockscreenClockHorizontalHookInstalled) {
                        installLockscreenClockHorizontalRectHook(param.defaultClassLoader, preferences, "aod")
                        installLockscreenClockHorizontalEditorHook(param.defaultClassLoader, preferences)
                        aodLockscreenClockHorizontalHookInstalled = true
                    }
                    if (!aodLockscreenTemplateLimitHookInstalled) {
                        installAodLockscreenTemplateLimitHook(param.defaultClassLoader, preferences)
                        aodLockscreenTemplateLimitHookInstalled = true
                    }
                    installAodEditorBackgroundHook(param.defaultClassLoader, preferences)
                }
                SUBSCREEN_CENTER -> {
                    othersHooks.installRearMiniPlayerSubScreenHooks(preferences)
                    othersHooks.installIslandSimulationRearScreenHook(param.defaultClassLoader)
                    othersHooks.installRearScreenAppWidgetUnlockHooks(param.defaultClassLoader)
                    if (preferences.getBoolean("unlock_xiaomi_18_rear_screen_ai", false) || preferences.getBoolean("remove_custom_rear_screen_restrictions", false)) {
                        othersHooks.installSubScreenCenterAppWidgetState(param.defaultClassLoader)
                    }
                    othersHooks.installMusicControlWhitelistHook(param.defaultClassLoader, preferences)
                    othersHooks.installRearScreenLeftSwipeHook(param.defaultClassLoader, preferences)
                }
                PERSONAL_ASSISTANT -> {
                    // The app-card store and its catalog filters are process-local data
                    // preparation, not an optional visual tweak. Install them even when an
                    // older preference key was not migrated; otherwise none of the catalog
                    // hooks can run and the UI silently remains unchanged.
                    othersHooks.installPersonalAssistantRearScreenAppCardHooks(param.defaultClassLoader)
                    if (preferences.getBoolean("unlock_xiaomi_18_rear_screen_ai", false) || preferences.getBoolean("remove_custom_rear_screen_restrictions", false)) {
                    }
                }
                THEME_MANAGER -> {
                    // Catalog merge/sanitizer hooks are harmless when the feature toggle is
                    // off and must be installed before ThemeManager initializes its ViewModel.
                    othersHooks.installThemeManagerRearScreenFeatureGuards(param.defaultClassLoader)
                    if (!themeManagerClockMaterialLimitHookInstalled) {
                        installThemeManagerClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        installClockMaterialCapabilityHook(
                            param.defaultClassLoader,
                            preferences,
                            setOf("vyq", "lrht", "uv6"),
                        )
                        installThemeManagerEditorClockMaterialHook(param.defaultClassLoader, preferences)
                        themeManagerClockMaterialLimitHookInstalled = true
                    }
                    installAodLockscreenTemplateLimitHook(param.defaultClassLoader, preferences)
                    installVideoWallpaperGlassSupportHook(param.defaultClassLoader)
                    if (preferences.getBoolean("unlock_xiaomi_18_rear_screen_ai", false) || preferences.getBoolean("remove_custom_rear_screen_restrictions", false)) {
                        othersHooks.installCustomRearScreenWidgetRegistration(param.defaultClassLoader)
                    }
                }
                else -> return
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed hooks for ${param.packageName}")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }
    }

    /**
     * Keeps the existing mini-player gesture and setting as a thin control surface for the
     * imported runtime. The cover, clock and media-card implementation remain in
     * HyperMusicCover's Main; this only sends its public PROBE commands after Main has attached
     * its receiver to the keyguard clock.
     */
    private fun installHyperMusicCoverGestureBridge(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (hyperMusicCoverGestureBridgeInstalled) return
        hyperMusicCoverGestureBridgeInstalled = true

        LockscreenMediaPresentationBridge.onPresentationChanged = { presentation ->
            if (presentation != LockscreenMediaPresentation.LYRICS_LOCKSCREEN) {
                setLockscreenLyricsShowing(systemUiApplicationContext, false)
                syncedHyperMusicLyricsEnabled = null
            }
            dispatchHyperMusicCoverState(preferences, presentation)
            syncHyperMusicCoverLyrics(preferences)
            refreshLockscreenLyricButtons(preferences)
        }
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED ->
                    dispatchHyperMusicCoverState(
                        preferences,
                        LockscreenMediaPresentationBridge.presentation,
                    )
                KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED,
                KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED,
                KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON -> {
                    if (key == KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED &&
                        !preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false)
                    ) {
                        setLockscreenLyricsShowing(systemUiApplicationContext, false)
                        if (LockscreenMediaPresentationBridge.presentation ==
                            LockscreenMediaPresentation.LYRICS_LOCKSCREEN
                        ) {
                            LockscreenMediaPresentationBridge.setPresentation(
                                LockscreenMediaPresentation.SYSTEM_MEDIA,
                            )
                        }
                    }
                    syncHyperMusicCoverLyrics(preferences)
                    refreshLockscreenLyricButtons(preferences)
                }
            }
        }
        runCatching {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            hyperMusicCoverPreferenceChangeListener = preferenceListener
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not observe HyperMusicCover setting changes", error)
        }

        runCatching {
            val clockContainer = classLoader.loadClass("com.android.keyguard.clock.KeyguardClockContainer")
            val attach = generateSequence(clockContainer as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
                ?: error("KeyguardClockContainer.onAttachedToWindow was not found")
            hook(attach)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hypermusiccover:gesture-bridge-ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { clock ->
                        systemUiApplicationContext = clock.context.applicationContext
                        activeLockscreenClockContainer = WeakReference(clock)
                        clock.post {
                            syncHyperMusicCoverLyrics(preferences)
                            dispatchHyperMusicCoverState(
                                preferences,
                                LockscreenMediaPresentationBridge.presentation,
                            )
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed HyperMusicCover gesture bridge")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install HyperMusicCover gesture bridge", error)
        }
    }

    private fun dispatchHyperMusicCoverState(
        preferences: SharedPreferences,
        presentation: LockscreenMediaPresentation,
    ) {
        val context = systemUiApplicationContext ?: return
        // Music lockscreen is gesture-selected. Letting Main follow the media card would restore
        // a cover behind SYSTEM_MEDIA during process/keyguard startup and create a mixed state.
        sendHyperMusicCoverCommand(context, "auto", false)
        val musicLockscreen = preferences.getBoolean(
            KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED,
            false,
        ) && presentation == LockscreenMediaPresentation.MUSIC_LOCKSCREEN
        val lyricLockscreen = preferences.getBoolean(
            KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED,
            false,
        ) && presentation == LockscreenMediaPresentation.LYRICS_LOCKSCREEN
        val musicLockscreenStyle2 = preferences.getBoolean(
            KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED,
            false,
        ) &&
            presentation == LockscreenMediaPresentation.MUSIC_LOCKSCREEN_STYLE2
        if (musicLockscreen) {
            // Main.java owns the card transition. Set its targets before pushart changes
            // sCoverMode, so its existing spring drives artwork alpha/scale and glyph
            // translation together.
            sendHyperMusicCoverMediaCardCommand(context, hideArtwork = true, centerText = true)
            sendHyperMusicCoverCommand(context, "style2", false)
            if (syncedHyperMusicLyricsEnabled != false) {
                sendHyperMusicCoverCommand(context, "lyrics", false)
                syncedHyperMusicLyricsEnabled = false
            }
            sendHyperMusicCoverCommand(context, "pushart", true)
        } else if (lyricLockscreen) {
            // Lyrics are an overlay on the native media notification. Keep its artwork and
            // metadata in the stock positions instead of applying the music-lockscreen card
            // treatment (hidden artwork/centred text).
            sendHyperMusicCoverMediaCardCommand(context, hideArtwork = false, centerText = false)
            sendHyperMusicCoverCommand(context, "style2", false)
            // Enable the lyric renderer and its cover background in one receiver transaction.
            sendHyperMusicCoverCommand(context, "lyricsmode", true)
            syncedHyperMusicLyricsEnabled = true
        } else if (musicLockscreenStyle2) {
            if (syncedHyperMusicLyricsEnabled != false) {
                sendHyperMusicCoverCommand(context, "lyrics", false)
                syncedHyperMusicLyricsEnabled = false
            }
            sendHyperMusicCoverMediaCardCommand(context, hideArtwork = true, centerText = true)
            sendHyperMusicCoverCommand(context, "style2", true)
        } else {
            // Keep Main's hide/centre targets alive while its exit spring runs. Clearing them
            // first would make the notification snap back before the wallpaper/clock settle.
            sendHyperMusicCoverCommand(context, "style2", false)
            // Disable the lyric renderer and leave cover mode in the same receiver transaction so
            // a second press reliably restores the stock lockscreen and media notification.
            sendHyperMusicCoverCommand(context, "lyricsmode", false)
            syncedHyperMusicLyricsEnabled = false
            Handler(Looper.getMainLooper()).postDelayed({
                val current = LockscreenMediaPresentationBridge.presentation
                val stillOutsideMusic = current != LockscreenMediaPresentation.MUSIC_LOCKSCREEN &&
                    current != LockscreenMediaPresentation.LYRICS_LOCKSCREEN &&
                    current != LockscreenMediaPresentation.MUSIC_LOCKSCREEN_STYLE2
                if (stillOutsideMusic) {
                    sendHyperMusicCoverMediaCardCommand(
                        context,
                        hideArtwork = false,
                        centerText = false,
                    )
                }
            }, HYPER_MUSIC_COVER_CARD_RESET_DELAY_MS)
        }
    }

    private fun syncHyperMusicCoverLyrics(
        preferences: SharedPreferences,
        context: Context? = systemUiApplicationContext,
    ) {
        context ?: return
        val lyrics = preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false) &&
            lockscreenLyricsShowing(context) &&
            LockscreenMediaPresentationBridge.presentation ==
            LockscreenMediaPresentation.LYRICS_LOCKSCREEN
        val hdr = preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED, false)
        val keepOn = preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON, false)
        if (syncedHyperMusicLyricsEnabled != lyrics) {
            sendHyperMusicCoverCommand(context, "lyrics", lyrics)
            syncedHyperMusicLyricsEnabled = lyrics
        }
        if (syncedHyperMusicLyricsHdrEnabled != hdr) {
            sendHyperMusicCoverCommand(context, "lyrichdr", hdr)
            syncedHyperMusicLyricsHdrEnabled = hdr
        }
        if (syncedHyperMusicLyricsKeepScreenOn != keepOn) {
            sendHyperMusicCoverCommand(context, "lyrickeep", keepOn)
            syncedHyperMusicLyricsKeepScreenOn = keepOn
        }
    }

    private fun sendHyperMusicCoverMediaCardCommand(
        context: Context,
        hideArtwork: Boolean,
        centerText: Boolean,
    ) {
        runCatching {
            context.sendBroadcast(
                Intent(HYPER_MUSIC_COVER_ACTION)
                    .setPackage(SYSTEM_UI)
                    .putExtra("op", "mediacard")
                    .putExtra("hideart", hideArtwork)
                    .putExtra("centertext", centerText),
            )
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not send HyperMusicCover mediacard command", error)
        }
    }

    private fun sendHyperMusicCoverCommand(context: Context, op: String, on: Boolean) {
        runCatching {
            context.sendBroadcast(
                Intent(HYPER_MUSIC_COVER_ACTION)
                    .setPackage(SYSTEM_UI)
                    .putExtra("op", op)
                    .putExtra("on", on),
            )
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not send HyperMusicCover $op command", error)
        }
    }

    private fun installSuperXiaoAiHooks(packageName: String, classLoader: ClassLoader) {
        SuperXiaoAiInputHook.install(
            module = this,
            classLoader = classLoader,
            phraseProcess = packageName == SUPER_XIAOAI_PHRASE,
        )
    }

    /**
     * The control-center plugin is preloaded lazily after the package callback.  Retry against
     * the same loader after preload has had a chance to define its classes.
     */
    private fun scheduleControlCenterHookRetries(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterHookRetryScheduled) return
        controlCenterHookRetryScheduled = true
        val handler = Handler(Looper.getMainLooper())
        listOf(500L, 1500L, 3000L, 6000L, 10000L, 16000L).forEach { delay ->
            handler.postDelayed({
                controlCenterHooks.installHeaderHooks(classLoader, preferences)
                islandSmoothingHook.install(classLoader, preferences)
                val complete = controlCenterEditButtonHookInstalled &&
                    controlCenterTopButtonsHookInstalled &&
                    controlCenterMainPanelHookInstalled
                if (!complete) {
                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Retrying control-center hooks after ${delay}ms")
                    installControlCenterEditButtonHook(classLoader, preferences)
                }
            }, delay)
        }
    }

    private fun installControlCenterEditButtonHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        alreadyLoadedClass: Class<*>? = null,
    ): Boolean {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return false
        return runCatching {
            var installed = false
            val controllerClass = (alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS }
                ?: runCatching { classLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }.getOrNull())
            btm.m.os4.systemuihook.HookModuleLogger.log(this,
                Log.DEBUG,
                TAG,
                "Control-center hook discovery: controller=${controllerClass != null}, " +
                    "loader=${classLoader.javaClass.name}, loaded=${alreadyLoadedClass?.name ?: "none"}",
            )
            if (controllerClass != null && !controlCenterEditButtonHookInstalled) {
                val availabilityMethods = controllerClass.methods.filter { method ->
                    method.name == "available" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }
                val listMethods = controllerClass.methods.filter { method ->
                    method.name == "getListItems" &&
                        method.parameterCount == 0 &&
                        java.util.List::class.java.isAssignableFrom(method.returnType)
                }
                availabilityMethods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:hide-edit-button-$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&
                                !preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)
                            ) false
                            else chain.proceed()
                        }
                }
                listMethods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:hide-edit-button-items-$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&
                                !preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)
                            ) ArrayList<Any?>()
                            else chain.proceed()
                        }
                }
                if (availabilityMethods.isNotEmpty() || listMethods.isNotEmpty()) {
                    controlCenterEditButtonHookInstalled = true
                    installed = true
                }
                btm.m.os4.systemuihook.HookModuleLogger.log(this,
                    Log.INFO,
                    TAG,
                    "Control-center edit methods discovered: available=${availabilityMethods.size}, " +
                        "list=${listMethods.size}, controller=${controllerClass.name}",
                )
            }
            if (controllerClass != null && !controlCenterTopButtonsHookInstalled) {
                val bindMethods = controllerClass.methods.filter { method ->
                    method.name == "onBindViewHolder" && method.parameterCount == 0
                }
                bindMethods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:top-buttons-bind-$index")
                            .intercept { chain ->
                                val result = chain.proceed()
                                val controller = chain.thisObject
                                synchronized(controlCenterButtonsLock) {
                                    controlCenterEditController = controller
                                    controlCenterControllerClassLoader = controller.javaClass.classLoader
                                }
                                hideBoundControlCenterEditButton(controller, preferences)
                                installControlCenterTopButtons(controller, preferences)
                                Handler(Looper.getMainLooper()).postDelayed(
                                    { installControlCenterTopButtons(controller, preferences) },
                                    120L,
                                )
                                Handler(Looper.getMainLooper()).postDelayed(
                                    { installControlCenterTopButtons(controller, preferences) },
                                    600L,
                                )
                                result
                            }
                    controlCenterTopButtonsHookInstalled = true
                    installed = true
                }
                btm.m.os4.systemuihook.HookModuleLogger.log(this,
                    Log.INFO,
                    TAG,
                    "Control-center bind methods discovered: count=${bindMethods.size}, " +
                        "controller=${controllerClass.name}",
                )
            }
            installControlCenterMainPanelHook(classLoader, preferences)
            installControlCenterExpandLifecycleHook(classLoader)
            installControlCenterHeaderLifecycleHook(classLoader, preferences)
            val distributorClass = (alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS }
                ?: runCatching { classLoader.loadClass(CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS) }.getOrNull())
            if (!controlCenterContentDistributorHookInstalled) distributorClass?.methods?.firstOrNull { method ->
                method.name == "getChildControllers" &&
                    method.parameterCount == 0 &&
                    java.util.List::class.java.isAssignableFrom(method.returnType)
            }?.let { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:hide-edit-button-controllers")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) ||
                            preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)
                        ) result
                        else (result as? List<*>)?.let { controllers ->
                            ArrayList(controllers.filterNot {
                                it?.javaClass?.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS
                            })
                        } ?: result
                    }
                controlCenterContentDistributorHookInstalled = true
                installed = true
            }
            if (distributorClass != null) {
                btm.m.os4.systemuihook.HookModuleLogger.log(this,
                    Log.DEBUG,
                    TAG,
                    "Control-center distributor discovered: class=${distributorClass.name}, " +
                        "hooked=$controlCenterContentDistributorHookInstalled",
                )
            }
            installControlCenterTouchHook(classLoader)
            if (!controlCenterPreferenceListenerInstalled) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                    if (key == ADD_CONTROL_CENTER_TOP_BUTTONS_KEY ||
                        key == KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE ||
                        key == KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS ||
                        key == KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE
                    ) {
                        val controller = synchronized(controlCenterButtonsLock) {
                            controlCenterEditController
                        }
                        if (controller != null) {
                            Handler(Looper.getMainLooper()).post {
                                hideBoundControlCenterEditButton(controller, changed)
                                installControlCenterTopButtons(controller, changed)
                            }
                        }
                    }
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                controlCenterPreferenceListenerInstalled = true
            }
            check(installed) { "Control-center edit button methods were not found" }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed control-center edit button visibility hook")
            true
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install control-center edit button visibility hook", error)
        }.getOrDefault(false)
    }

    private fun installControlCenterMainPanelHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        alreadyLoadedClass: Class<*>? = null,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterMainPanelHookInstalled) return
        if (alreadyLoadedClass == null && controlCenterClassDiscoveryInProgress.get() == true) return
        runCatching {
            val panelClass = (alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS }
                ?: classLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS))
            val methods = panelClass.methods.filter { method ->
                method.name == "onCreate" && method.parameterCount == 0
            }
            methods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:top-buttons-main-panel-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val panel = chain.thisObject
                        val root = runCatching {
                            panel.javaClass.methods.firstOrNull {
                                it.name == "getView" && it.parameterCount == 0
                            }?.invoke(panel) as? ViewGroup
                        }.getOrNull()
                        if (root != null) {
                            installControlCenterTopButtonsIntoRoot(root, preferences)
                            Handler(Looper.getMainLooper()).postDelayed({
                                installControlCenterTopButtonsIntoRoot(root, preferences)
                            }, 250L)
                        } else {
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center main panel root unavailable after onCreate")
                        }
                        result
                    }
            }
            if (methods.isNotEmpty()) {
                controlCenterMainPanelHookInstalled = true
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed control-center MainPanelController hook")
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center MainPanelController hook unavailable", error)
        }
    }

    private fun installControlCenterHeaderLifecycleHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        alreadyLoadedClass: Class<*>? = null,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterHeaderLifecycleHookInstalled) return
        if (alreadyLoadedClass == null && controlCenterClassDiscoveryInProgress.get() == true) return
        runCatching {
            val headerClass = alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_HEADER_CONTROLLER_CLASS }
                ?: classLoader.loadClass(CONTROL_CENTER_HEADER_CONTROLLER_CLASS)
            var count = 0
            headerClass.methods.filter { method ->
                method.name in setOf("onCreate", "onMainPanelVisibleChanged", "onModeChanged")
            }.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:top-buttons-header-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val controller = synchronized(controlCenterButtonsLock) { controlCenterEditController }
                        val visible = (0 until method.parameterCount)
                            .mapNotNull { index -> chain.getArg(index) as? Boolean }
                            .firstOrNull()
                        if (visible == false) {
                            hideControlCenterTopButtons()
                        } else {
                            controller?.let { installControlCenterTopButtons(it, preferences) }
                            // Switching from the notification shade to the control center
                            // horizontally can make the header visible without emitting an
                            // expand-progress callback.  In that path the injected buttons
                            // remain in their default hidden-by-collapse state until the user
                            // pulls down once more.  A visible header means the panel is already
                            // shown, so synchronize the buttons to the fully expanded state.
                            if (visible == true) updateControlCenterTopButtonsProgress(1f)
                        }
                        result
                    }
                count++
            }
            if (count > 0) controlCenterHeaderLifecycleHookInstalled = true
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center header lifecycle hook unavailable", error) }
    }

    private fun hideBoundControlCenterEditButton(controller: Any?, preferences: SharedPreferences) {
        if (!preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false)) return
        val target = runCatching {
            controller?.javaClass?.declaredMethods?.firstOrNull {
                it.name == "getEditButton" && it.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(controller) as? View
        }.getOrNull() ?: return
        if (target.visibility != View.GONE) {
            target.visibility = View.GONE
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Hid bound control-center edit button for top-button replacement")
        }
    }

    private fun installControlCenterTouchHook(classLoader: ClassLoader, alreadyLoadedClass: Class<*>? = null) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterTouchHookInstalled) return
        if (controlCenterClassDiscoveryInProgress.get() == true && alreadyLoadedClass == null) return
        runCatching {
            val touchClass = alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_TOUCH_CONTROLLER_CLASS }
                ?: classLoader.loadClass(CONTROL_CENTER_TOUCH_CONTROLLER_CLASS)
            touchClass.methods.filter { method ->
                method.name == "onInterceptTouchEvent" && method.parameterCount == 1 &&
                    method.parameterTypes[0] == MotionEvent::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:top-buttons-touch-$index")
                    .intercept { chain ->
                        val event = chain.getArg(0) as? MotionEvent
                        if (event == null) return@intercept chain.proceed()
                        val active = synchronized(controlCenterButtonsLock) {
                            controlCenterActiveTouchButton
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                val hit = findControlCenterTopButtonHit(event)
                                if (hit != null) {
                                    synchronized(controlCenterButtonsLock) {
                                        controlCenterActiveTouchButton = hit
                                    }
                                    // Do not let MainPanelTouchController intercept the head
                                    // gesture.  Returning false keeps this gesture on the
                                    // injected child, whose listener owns the eventual action.
                                    false
                                } else {
                                    chain.proceed()
                                }
                            }
                            MotionEvent.ACTION_MOVE,
                            -> if (active != null) false else chain.proceed()
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> if (active != null) {
                                synchronized(controlCenterButtonsLock) {
                                    controlCenterActiveTouchButton = null
                                }
                                false
                            } else chain.proceed()
                            else -> chain.proceed()
                        }
                    }
                controlCenterTouchHookInstalled = true
            }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center touch hook unavailable", error) }
    }

    private fun installControlCenterEventHandlerHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterEventHandlerHookInstalled) return
        runCatching {
            val handlerClass = classLoader.loadClass(CONTROL_CENTER_EVENT_HANDLER_CLASS)
            val methods = handlerClass.methods.filter { method ->
                method.name == "handleExpandEvent" && method.parameterCount >= 1 &&
                    method.parameterTypes.firstOrNull() == MotionEvent::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
            methods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:top-buttons-event-handler-$index")
                    .intercept { chain ->
                        val event = chain.getArg(0) as? MotionEvent
                        if (event == null) return@intercept chain.proceed()
                        val active = synchronized(controlCenterButtonsLock) {
                            controlCenterActiveTouchButton
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                val hit = findControlCenterTopButtonHit(event)
                                if (hit != null) {
                                    synchronized(controlCenterButtonsLock) {
                                        controlCenterActiveTouchButton = hit
                                    }
                                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center button DOWN via event handler: ${hit.tag}")
                                    true
                                } else {
                                    chain.proceed()
                                }
                            }
                            MotionEvent.ACTION_MOVE -> if (active != null) true else chain.proceed()
                            MotionEvent.ACTION_UP -> if (active != null) {
                                synchronized(controlCenterButtonsLock) {
                                    controlCenterActiveTouchButton = null
                                }
                                performControlCenterTopButtonAction(active)
                                true
                            } else chain.proceed()
                            MotionEvent.ACTION_CANCEL -> if (active != null) {
                                synchronized(controlCenterButtonsLock) {
                                    controlCenterActiveTouchButton = null
                                }
                                true
                            } else chain.proceed()
                            else -> if (active != null) true else chain.proceed()
                        }
                    }
            }
            if (methods.isNotEmpty()) {
                controlCenterEventHandlerHookInstalled = true
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed ControlCenterEventHandler touch hook")
            }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "ControlCenterEventHandler hook unavailable", error) }
    }

    private fun installControlCenterRootDispatchHook(root: ViewGroup) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        // The vendor root invokes its own click listener when a tap in the empty collapse
        // area starts the close animation.  Observe that click instead of consuming the root
        // dispatch stream: consuming dispatch prevented the injected child views from ever
        // receiving their complete DOWN/UP sequence.
        installControlCenterRootClickHook()
    }

    private fun installControlCenterRootClickHook() {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterRootClickHookInstalled) return
        runCatching {
            hook(View::class.java.getMethod("performClick"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("control-center:top-buttons-root-click")
                .intercept { chain ->
                    val root = synchronized(controlCenterButtonsLock) { controlCenterRoot }
                    if (chain.thisObject === root) {
                        // This runs in the same ACTION_UP that invokes the vendor collapse
                        // listener, so the injected buttons begin leaving with the panel.
                        startControlCenterTopButtonsCollapse()
                    }
                    chain.proceed()
                }
            controlCenterRootClickHookInstalled = true
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed control-center root click hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center root click hook unavailable", error)
        }
    }

    private fun installControlCenterExpandLifecycleHook(
        classLoader: ClassLoader,
        alreadyLoadedClass: Class<*>? = null,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (controlCenterExpandLifecycleHookInstalled) return
        if (controlCenterClassDiscoveryInProgress.get() == true && alreadyLoadedClass == null) return
        runCatching {
            val expandClass = alreadyLoadedClass
                ?.takeIf { it.name == CONTROL_CENTER_EXPAND_CONTROLLER_CLASS }
                ?: classLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS)
            var count = 0
            expandClass.methods.filter { method ->
                (method.name == "onExpandChange" && method.parameterCount == 3 &&
                    method.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Float::class.javaPrimitiveType &&
                    method.parameterTypes[2] == Boolean::class.javaPrimitiveType) ||
                    (method.name == "hidePanel" && method.parameterCount == 2 &&
                        method.parameterTypes.all { it == Boolean::class.javaPrimitiveType }) ||
                    (method.name == "onStop" && method.parameterCount == 0) ||
                    (method.name == "onExpandFinish" && method.parameterCount == 1)
            }.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:top-buttons-expand-$index")
                    .intercept { chain ->
                        if (method.name == "hidePanel") {
                            // hidePanel starts the vendor's non-drag close path.  onStop only
                            // arrives after that animation, which was why the buttons lingered.
                            if (chain.getArg(0) == true) startControlCenterTopButtonsCollapse()
                            else hideControlCenterTopButtons()
                        }
                        val result = chain.proceed()
                        synchronized(controlCenterButtonsLock) {
                            controlCenterExpandController = chain.thisObject
                        }
                        when (method.name) {
                            "onExpandChange" -> updateControlCenterTopButtonsProgress(
                                controlCenterExpansionProgress(chain.thisObject),
                            )
                            "onStop" -> hideControlCenterTopButtons()
                            "onExpandFinish" -> {
                                val expanded = runCatching {
                                    method.declaringClass.methods.firstOrNull {
                                        it.name == "getAppearance" && it.parameterCount == 0
                                    }?.invoke(chain.thisObject) as? Boolean
                                }.getOrNull() == true
                                if (expanded) updateControlCenterTopButtonsProgress(1f)
                                else hideControlCenterTopButtons()
                            }
                        }
                        result
                    }
                count++
            }
            if (count > 0) {
                controlCenterExpandLifecycleHookInstalled = true
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed control-center expand lifecycle hook")
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center expand lifecycle hook unavailable", error)
        }
    }

    private fun hideControlCenterTopButtons() {
        synchronized(controlCenterButtonsLock) {
            controlCenterActiveTouchButton = null
            controlCenterButtonsExpansionProgress = 0f
            controlCenterButtonsHiddenByCollapse = true
            controlCenterButtonsCollapsing = false
            listOf(controlCenterPlusButton, controlCenterPowerButton).forEach { button ->
                button?.animate()?.cancel()
                button?.alpha = 0f
                button?.translationY = 0f
                button?.visibility = View.GONE
            }
        }
    }

    private fun updateControlCenterTopButtonsProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val visible = clamped > 0.05f
        synchronized(controlCenterButtonsLock) {
            // A non-drag collapse has no intermediate expand-height callbacks.  Ignore a
            // stale visible value while its own exit animation is already in progress.
            if (controlCenterButtonsCollapsing && visible) return
            controlCenterButtonsExpansionProgress = clamped
            controlCenterButtonsHiddenByCollapse = !visible
            if (visible) controlCenterButtonsCollapsing = false
            val plus = controlCenterPlusButton
            val power = controlCenterPowerButton
            val offset = ((plus ?: power)?.resources?.displayMetrics?.density ?: 1f) * 20f
            listOf(plus, power).forEach { button ->
                if (button == null) return@forEach
                button.animate().cancel()
                button.visibility = if (visible) View.VISIBLE else View.GONE
                button.alpha = clamped
                button.translationY = -(1f - clamped) * offset
            }
        }
        if (!visible) {
            synchronized(controlCenterButtonsLock) {
                controlCenterPlusButton?.translationY = 0f
                controlCenterPowerButton?.translationY = 0f
            }
        }
    }

    private fun startControlCenterTopButtonsCollapse() {
        synchronized(controlCenterButtonsLock) {
            if (controlCenterButtonsCollapsing) return
            controlCenterButtonsHiddenByCollapse = true
            controlCenterButtonsCollapsing = true
            controlCenterActiveTouchButton = null
            var animated = false
            listOf(controlCenterPlusButton, controlCenterPowerButton).forEach { button ->
                if (button == null) return@forEach
                button.animate().cancel()
                if (button.visibility != View.VISIBLE || button.alpha <= 0f) {
                    button.visibility = View.GONE
                    button.alpha = 0f
                    return@forEach
                }
                animated = true
                button.animate()
                    .alpha(0f)
                    .translationY(-button.resources.displayMetrics.density * 20f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        button.visibility = View.GONE
                        button.translationY = 0f
                        synchronized(controlCenterButtonsLock) {
                            val allHidden = listOf(
                                controlCenterPlusButton,
                                controlCenterPowerButton,
                            ).filterNotNull().all { it.visibility != View.VISIBLE }
                            if (allHidden) controlCenterButtonsCollapsing = false
                        }
                    }
                    .start()
            }
            if (!animated) controlCenterButtonsCollapsing = false
        }
    }

    private fun controlCenterExpansionProgress(controller: Any?): Float = runCatching {
        if (controller == null) return@runCatching 0f
        val height = controller.javaClass.methods.firstOrNull {
            it.name == "getExpandHeight" && it.parameterCount == 0
        }?.invoke(controller) as? Number
        val threshold = controller.javaClass.methods.firstOrNull {
            it.name == "getExpandThresh" && it.parameterCount == 0
        }?.invoke(controller) as? Number
        val max = threshold?.toFloat() ?: 0f
        if (height == null || max <= 0f) 0f else (height.toFloat() / max).coerceIn(0f, 1f)
    }.getOrDefault(0f)

    private fun findControlCenterTopButtonHit(event: MotionEvent): View? {
        val buttons = synchronized(controlCenterButtonsLock) {
            listOf(controlCenterPlusButton, controlCenterPowerButton)
        }
        return buttons.filterNotNull().firstOrNull { button ->
            if (button.visibility != View.VISIBLE || button.width <= 0 || button.height <= 0) return@firstOrNull false
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            val x = event.rawX
            val y = event.rawY
            x >= location[0] && x <= location[0] + button.width &&
                y >= location[1] && y <= location[1] + button.height
        }
    }

    private fun isControlCenterDispatchOwner(candidate: Any?, buttonRoot: ViewGroup): Boolean {
        if (candidate === buttonRoot) return true
        var parent = buttonRoot.parent
        while (parent is View) {
            if (parent === candidate) return true
            parent = parent.parent
        }
        return false
    }

    private fun isControlCenterTopButtonHit(event: MotionEvent): Boolean =
        findControlCenterTopButtonHit(event) != null

    private fun performControlCenterTopButtonAction(button: View) {
        val (plus, power, shouldRun) = synchronized(controlCenterButtonsLock) {
            val now = SystemClock.uptimeMillis()
            val duplicate = button === controlCenterLastActionButton &&
                now - controlCenterLastActionUptime < CONTROL_CENTER_BUTTON_ACTION_DEBOUNCE_MS
            if (!duplicate) {
                controlCenterLastActionButton = button
                controlCenterLastActionUptime = now
            }
            Triple(controlCenterPlusButton, controlCenterPowerButton, !duplicate)
        }
        if (!shouldRun) return
        when {
            button === plus -> showControlCenterEdit()
            button === power -> showCachedGlobalActions()
            else -> button.performClick()
        }
    }

    private fun showControlCenterEdit(): Boolean {
        val controller = synchronized(controlCenterButtonsLock) { controlCenterEditController }
        val result = runCatching {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center edit click: controller=${controller?.javaClass?.name ?: "null"}")
            val provider = controller?.let { readInstanceField(it, "qsListController") }
            // The plugin's dependency wrapper is obfuscated in production builds (F0.a on
            // this version), so its class name does not identify it as a Provider or Lazy.
            // Always prefer its zero-argument get() result when present.
            val qsListController = provider?.let { wrapper ->
                runCatching {
                    wrapper.javaClass.methods.firstOrNull {
                        it.name == "get" && it.parameterCount == 0
                    }?.apply { isAccessible = true }?.invoke(wrapper)
                }.getOrNull() ?: wrapper
            }
            val startQuery = qsListController?.javaClass?.methods?.firstOrNull { method ->
                method.name == "startQuery" && method.parameterCount == 1 && method.parameterTypes[0].isEnum
            }
            val editMode = startQuery?.parameterTypes?.get(0)?.let { enumType ->
                runCatching {
                    java.lang.Enum.valueOf(
                        enumType.asSubclass(Enum::class.java),
                        "EDIT",
                    )
                }.getOrNull()
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this,
                Log.DEBUG,
                TAG,
                "Control-center edit target: qsList=${qsListController?.javaClass?.name ?: "null"}, " +
                    "startQuery=${startQuery?.toGenericString() ?: "null"}, editMode=${editMode != null}",
            )
            if (qsListController != null && editMode != null && startQuery != null) {
                startQuery.invoke(qsListController, editMode)
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Opened control-center edit mode via QSListController.startQuery")
                true
            } else {
                btm.m.os4.systemuihook.HookModuleLogger.log(this,
                    Log.WARN,
                    TAG,
                    "Control-center edit reflection unavailable: controller=${controller?.javaClass?.name}, " +
                        "qsList=${qsListController?.javaClass?.name}, mode=${editMode != null}, startQuery=${startQuery != null}",
                )
                false
            }
        }.getOrElse { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Control-center edit reflection failed", error)
            false
        }
        if (result) return true
        val target = synchronized(controlCenterButtonsLock) { controlCenterEditTarget }
        return target?.performClick() == true
    }

    private fun installControlCenterTopButtons(controller: Any?, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        val controllerObject = controller ?: return
        synchronized(controlCenterButtonsLock) {
            controlCenterControllerClassLoader = controllerObject.javaClass.classLoader
        }
        val editButton = runCatching {
            controllerObject.javaClass.declaredMethods.firstOrNull {
                it.name == "getEditButton" && it.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(controllerObject) as? View
        }.getOrNull()
        synchronized(controlCenterButtonsLock) {
            controlCenterEditTarget = editButton
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this,
            Log.DEBUG,
            TAG,
            "Control-center top buttons bind: controller=${controllerObject.javaClass.name}, " +
                "editButton=${editButton?.javaClass?.name ?: "null"}",
        )
        val mainPanelRef: Any = readInstanceField(controllerObject, "mainPanelController") ?: run {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center top buttons: mainPanelController unavailable")
            val fallbackRoot = findControlCenterWindowRoot(editButton)
            if (fallbackRoot != null) {
                installControlCenterTopButtonsIntoRoot(fallbackRoot, preferences)
            }
            return
        }
        val mainPanel = runCatching {
            mainPanelRef.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(mainPanelRef)
        }.getOrNull() ?: run {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center top buttons: MainPanel instance unavailable")
            return
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center top buttons: MainPanel=${mainPanel.javaClass.name}")
        val root = runCatching {
            mainPanel.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 0 }?.invoke(mainPanel)
        }.getOrNull() as? ViewGroup ?: run {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center top buttons: panel root unavailable")
            findControlCenterWindowRoot(editButton)?.let { installControlCenterTopButtonsIntoRoot(it, preferences) }
            return
        }
        installControlCenterTopButtonsIntoRoot(root, preferences)
    }

    private fun findControlCenterWindowRoot(view: View?): ViewGroup? {
        var current = view?.parent as? View
        var fallback: ViewGroup? = null
        while (current != null) {
            if (current is ViewGroup) {
                fallback = current
                if (current.javaClass.name.contains("ControlCenterWindowViewImpl")) return current
            }
            current = current.parent as? View
        }
        return fallback
    }

    private fun installControlCenterTopButtonsIntoRoot(root: ViewGroup, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        synchronized(controlCenterButtonsLock) { controlCenterRoot = root }
        installControlCenterRootDispatchHook(root)
        val enabled = preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false) &&
            (root.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
                preferences.getBoolean(KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE, false))
        btm.m.os4.systemuihook.HookModuleLogger.log(this,
            Log.DEBUG,
            TAG,
            "Control-center top buttons root=${root.javaClass.name}, enabled=$enabled, " +
                "childCount=${root.childCount}",
        )
        val existingPlus = root.findViewWithTag<View>(CONTROL_CENTER_PLUS_TAG)
        val existingPower = root.findViewWithTag<View>(CONTROL_CENTER_POWER_TAG)
        if (!enabled) {
            existingPlus?.let { (it.parent as? ViewGroup)?.removeView(it) }
            existingPower?.let { (it.parent as? ViewGroup)?.removeView(it) }
            synchronized(controlCenterButtonsLock) {
                if (controlCenterPlusButton === existingPlus) controlCenterPlusButton = null
                if (controlCenterPowerButton === existingPower) controlCenterPowerButton = null
            }
            return
        }
        val density = root.resources.displayMetrics.density
        fun createButton(tag: String, path: String): FrameLayout {
            val button = FrameLayout(root.context).apply {
                this.tag = tag
                clipChildren = false
                clipToPadding = false
                isClickable = true
                isFocusable = true
                contentDescription = tag
                setOnClickListener { view -> performControlCenterTopButtonAction(view) }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            // Keep accessibility and click semantics on the actual child.
                            // Its parent touch controller has been told not to intercept this
                            // gesture, so this cannot bubble into the panel-close click.
                            view.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            true
                        }
                        else -> true
                    }
                }
            }
            val background = ImageView(root.context).apply {
                this.tag = "$tag.background"
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val icon = ImageView(root.context).apply {
                this.tag = "$tag.icon"
                setImageDrawable(
                    ControlCenterSvgDrawable(
                        path,
                        if (path == CONTROL_CENTER_POWER_PATH) 493f else 380f,
                    ),
                )
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val iconPadding = (14f * density).roundToInt()
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            button.addView(background, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            button.addView(icon, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            return button
        }
        fun addButton(existing: View?, tag: String, path: String, gravity: Int) {
            if (existing != null) {
                existing.bringToFront()
                return
            }
            val button = createButton(tag, path)
            val size = (44f * density).roundToInt()
            val horizontalMargin = (32f * density).roundToInt()
            val topMargin = (18f * density).roundToInt()
            button.alpha = 0f
            button.visibility = View.GONE
            if (root is FrameLayout) {
                root.addView(button, FrameLayout.LayoutParams(size, size, gravity).apply {
                    setMargins(horizontalMargin, topMargin, horizontalMargin, topMargin)
                })
            } else {
                root.addView(button, ViewGroup.LayoutParams(size, size))
            }
        }
        addButton(existingPlus, CONTROL_CENTER_PLUS_TAG, CONTROL_CENTER_PLUS_PATH, Gravity.TOP or Gravity.START)
        addButton(existingPower, CONTROL_CENTER_POWER_TAG, CONTROL_CENTER_POWER_PATH, Gravity.TOP or Gravity.END)
        synchronized(controlCenterButtonsLock) {
            controlCenterPlusButton = root.findViewWithTag(CONTROL_CENTER_PLUS_TAG)
            controlCenterPowerButton = root.findViewWithTag(CONTROL_CENTER_POWER_TAG)
        }
        synchronized(controlCenterButtonsLock) {
            listOf(controlCenterPlusButton, controlCenterPowerButton).forEach { button ->
                button?.let { target ->
                    applyControlCenterTopButtonIconScale(target, preferences)
                    val materialLayer = (target as? ViewGroup)
                        ?.findViewWithTag<View>("${target.tag}.background") as? ImageView
                        ?: target as? ImageView
                    materialLayer?.let {
                        applyControlCenterTopButtonBackground(
                            it,
                            preferences,
                            controlCenterTopButtonsMaterialClassLoader(root),
                        )
                    }
                }
            }
        }
        val expandController = synchronized(controlCenterButtonsLock) { controlCenterExpandController }
        val hiddenByCollapse = synchronized(controlCenterButtonsLock) {
            controlCenterButtonsHiddenByCollapse
        }
        if (hiddenByCollapse) {
            synchronized(controlCenterButtonsLock) {
                listOf(controlCenterPlusButton, controlCenterPowerButton).forEach { button ->
                    button?.animate()?.cancel()
                    button?.alpha = 0f
                    button?.translationY = 0f
                    button?.visibility = View.GONE
                }
            }
        } else if (expandController != null) {
            updateControlCenterTopButtonsProgress(controlCenterExpansionProgress(expandController))
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Control-center top buttons injected into ${root.javaClass.name}")
    }

    private fun applyControlCenterTopButtonBackground(
        button: ImageView,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        val mode = preferences.getInt(
            KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE,
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_NONE,
        ).coerceIn(
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_NONE,
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_SOFT_GLASS,
        )
        val radiusDp = when (mode) {
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_PURE -> preferences.getFloat(
                KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS,
                CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_DEFAULT_DP,
            )
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_ADVANCED -> preferences.getFloat(
                KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS,
                CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_DEFAULT_DP,
            )
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_SOFT_GLASS -> preferences.getFloat(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS,
                CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_DEFAULT_DP,
            )
            else -> 0f
        }.coerceIn(0f, CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_MAX_DP)
        val showBackground = mode != CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_NONE && radiusDp > 0f
        val diameter = (radiusDp * button.resources.displayMetrics.density * 2f)
            .roundToInt()
            .coerceAtLeast(1)
        val layoutParams = (button.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER)
        layoutParams.width = diameter
        layoutParams.height = diameter
        layoutParams.gravity = Gravity.CENTER
        button.layoutParams = layoutParams
        runCatching {
            View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(button)
            View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
                .invoke(button, false)
        }
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (!showBackground) {
                    Color.TRANSPARENT
                } else when (mode) {
                    CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_PURE ->
                        preferences.getInt(
                            KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR,
                            SHORTCUT_PURE_COLOR,
                        )
                    CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_ADVANCED,
                    CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_SOFT_GLASS ->
                        Color.argb(1, 255, 255, 255)
                    else -> Color.TRANSPARENT
                },
            )
        }
        button.background = null
        button.setImageDrawable(background)
        button.visibility = if (showBackground) View.VISIBLE else View.INVISIBLE
        button.clipToOutline = showBackground
        button.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        if (showBackground) applyControlCenterTopButtonMaterial(button, preferences, classLoader, mode)
        button.invalidateOutline()
        if (showBackground && mode == CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_SOFT_GLASS) {
            // Dynamic background layers need a second registration after their measured size
            // changes, otherwise some HyperOS builds retain only the legacy blend color.
            button.post {
                if (button.isAttachedToWindow && button.visibility == View.VISIBLE) {
                    applyControlCenterTopButtonSoftGlass(button, preferences, classLoader)
                }
            }
        }
    }

    private fun applyControlCenterTopButtonIconScale(button: View, preferences: SharedPreferences) {
        val icon = (button as? ViewGroup)
            ?.findViewWithTag<View>("${button.tag}.icon")
            ?: return
        val scale = preferences.getFloat(KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE, 1f)
            .coerceIn(CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MIN, CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MAX)
        icon.scaleX = scale
        icon.scaleY = scale
    }

    private fun controlCenterTopButtonsMaterialClassLoader(root: View): ClassLoader =
        synchronized(controlCenterButtonsLock) {
            controlCenterControllerClassLoader ?: systemUiClassLoader
        } ?: root.context.classLoader

    private fun applyControlCenterTopButtonMaterial(
        button: ImageView,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
        mode: Int,
    ) {
        when (mode) {
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_ADVANCED -> runCatching {
                applyLegacyBackdropMaterial(
                    view = button,
                    opacity = preferences.getInt(
                        KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY,
                        DEFAULT_ADVANCED_MATERIAL_OPACITY,
                    ),
                    blurRadius = preferences.getInt(
                        KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS,
                        DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS,
                    ),
                    color = preferences.getInt(
                        KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR,
                        DEFAULT_ADVANCED_MATERIAL_COLOR,
                    ),
                    showHighlight = preferences.getBoolean(
                        KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT,
                        false,
                    ),
                )
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Top button advanced material unavailable", error) }
            CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_SOFT_GLASS -> {
                applyControlCenterTopButtonSoftGlass(button, preferences, classLoader)
            }
        }
    }

    private fun applyControlCenterTopButtonSoftGlass(
        button: ImageView,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) = runCatching {
        applyLegacyBackdropMaterial(
            view = button,
            opacity = preferences.getInt(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY,
                DEFAULT_SOFT_GLASS_OPACITY,
            ),
            blurRadius = preferences.getInt(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
            ),
            color = preferences.getInt(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR,
                DEFAULT_SOFT_GLASS_COLOR,
            ),
            showHighlight = false,
        )
        applySystemGlassMaterial(
            view = button,
            classLoader = classLoader,
            blurRadius = preferences.getInt(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS,
                DEFAULT_SOFT_GLASS_BLUR_RADIUS,
            ),
            luminance = preferences.getFloat(
                KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE,
                DEFAULT_SOFT_GLASS_LUMINANCE,
            ),
        )
    }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Top button soft-glass material unavailable", error) }

    private fun showCachedGlobalActions() {
        val (component, impl, queue, plugin, manager) = synchronized(globalActionsLock) {
            listOf(globalActionsComponent, globalActionsImpl, commandQueue, globalActionsPlugin, globalActionsManager)
        }
        val attempts = listOf(
            Triple("GlobalActionsComponent.handleShowGlobalActionsMenu", component, null),
            Triple("GlobalActionsPlugin.showGlobalActions", plugin, manager),
            Triple("GlobalActionsImpl.showGlobalActions", impl, manager),
            Triple("CommandQueue.showGlobalActionsMenu", queue, null),
        )
        for ((label, target, arg) in attempts) {
            if (target == null) {
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Power button action unavailable: $label target=null")
                continue
            }
            val success = runCatching {
                val methodName = label.substringAfterLast('.')
                val method = target.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterCount == (if (arg == null) 0 else 1) &&
                        (arg == null || candidate.parameterTypes[0].isInstance(arg))
                } ?: generateSequence<Class<*>>(target.javaClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull { candidate ->
                        candidate.name == methodName &&
                            candidate.parameterCount == (if (arg == null) 0 else 1) &&
                            (arg == null || candidate.parameterTypes[0].isInstance(arg))
                    }?.apply { isAccessible = true }
                    ?: error("method not found")
                if (arg == null) method.invoke(target) else method.invoke(target, arg)
                true
            }.getOrElse { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Power button action failed: $label", error)
                false
            }
            if (success) {
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Shown system global actions from control-center power button via $label")
                return
            }
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not show system global actions: no usable target")
    }

    private fun installGlobalActionsHook(classLoader: ClassLoader, alreadyLoadedClass: Class<*>? = null) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        val candidates = listOf(
            GLOBAL_ACTIONS_PLUGIN_CLASS,
            GLOBAL_ACTIONS_COMPONENT_CLASS,
            GLOBAL_ACTIONS_IMPL_CLASS,
            COMMAND_QUEUE_CLASS,
        )
        candidates.forEach { className ->
            if (globalActionsHookedClasses.contains(className)) return@forEach
            runCatching {
                val targetClass = alreadyLoadedClass?.takeIf { it.name == className }
                    ?: classLoader.loadClass(className)
                var hooked = false
                targetClass.methods.filter { method ->
                    when (className) {
                        GLOBAL_ACTIONS_PLUGIN_CLASS,
                        GLOBAL_ACTIONS_IMPL_CLASS -> method.name == "showGlobalActions" && method.parameterCount == 1
                        GLOBAL_ACTIONS_COMPONENT_CLASS -> method.name in setOf("handleShowGlobalActionsMenu", "handleShowOrHideGlobalActionsMenu", "start")
                        COMMAND_QUEUE_CLASS -> method.name == "showGlobalActionsMenu" && method.parameterCount == 0
                        else -> false
                    }
                }.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("control-center:global-actions-${className.hashCode()}-$index")
                        .intercept { chain ->
                            synchronized(globalActionsLock) {
                                when (className) {
                                    GLOBAL_ACTIONS_PLUGIN_CLASS -> {
                                        globalActionsPlugin = chain.thisObject
                                        if (chain.getArg(0) != null) globalActionsManager = chain.getArg(0)
                                    }
                                    GLOBAL_ACTIONS_IMPL_CLASS -> {
                                        globalActionsImpl = chain.thisObject
                                        if (chain.getArg(0) != null) globalActionsManager = chain.getArg(0)
                                    }
                                    GLOBAL_ACTIONS_COMPONENT_CLASS -> {
                                        globalActionsComponent = chain.thisObject
                                        // GlobalActionsComponent implements GlobalActionsManager;
                                        // plugins must receive this object, not themselves.
                                        globalActionsManager = chain.thisObject
                                        readInstanceField(chain.thisObject, "mPlugin")?.let { globalActionsPlugin = it }
                                        readInstanceField(chain.thisObject, "mCommandQueue")?.let { commandQueue = it }
                                        readInstanceField(chain.thisObject, "mExtension")?.let { extension ->
                                            readInstanceField(extension, "mItem")?.let { item -> globalActionsPlugin = item }
                                        }
                                        readInstanceField(chain.thisObject, "mGlobalActions")?.let { globalActionsImpl = it }
                                    }
                                    COMMAND_QUEUE_CLASS -> commandQueue = chain.thisObject
                                }
                            }
                            val result = chain.proceed()
                            if (className == GLOBAL_ACTIONS_COMPONENT_CLASS) {
                                synchronized(globalActionsLock) {
                                    globalActionsComponent = chain.thisObject
                                    globalActionsManager = chain.thisObject
                                    readInstanceField(chain.thisObject, "mPlugin")?.let { globalActionsPlugin = it }
                                    readInstanceField(chain.thisObject, "mCommandQueue")?.let { commandQueue = it }
                                    readInstanceField(chain.thisObject, "mExtension")?.let { extension ->
                                        readInstanceField(extension, "mItem")?.let { item -> globalActionsPlugin = item }
                                    }
                                }
                            }
                            result
                        }
                    hooked = true
                }
                if (hooked) {
                    globalActionsHookedClasses.add(className)
                    globalActionsHookInstalled = true
                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed global-actions hook for $className")
                }
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "GlobalActions hook unavailable for $className", error) }
        }
    }










    /** Keep the stock bionic/soft-glass pipeline active when a global theme is applied. */
    private fun scheduleSoftGlassThemeActivation(preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        if (themeActivationScheduled) return
        themeActivationScheduled = true
        // The control-center plugin now creates its default-theme StateFlow while it is
        // loading. Delaying this flag lets a global-theme "false" be cached permanently
        // until the next configuration change, so it must be available before the plugin.
        themeOverrideReady = true
        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false)) {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft-glass theme override enabled after startup")
        }
    }

    private fun installSoftGlassThemeSystemUiHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runCatching {
            val materialUtils = classLoader.loadClass(MIUI_MATERIAL_UTILS_CLASS)
            hook(materialUtils.getMethod("onDefaultThemeChanged", Boolean::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-theme:systemui-default-theme")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                        themeOverrideReady
                    ) {
                        chain.proceedWith(chain.thisObject, arrayOf(true))
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed soft-glass global-theme hook for SystemUI")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install soft-glass global-theme hook for SystemUI", error)
        }
    }

    private fun installSoftGlassThemePluginHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runCatching {
            val themeUtils = classLoader.loadClass(MIUI_THEME_UTILS_CLASS)
            listOf("getDefaultPluginTheme", "getDefaultSysUiTheme").forEach { methodName ->
                hook(themeUtils.getMethod(methodName))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("soft-glass-theme:plugin-$methodName")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                            themeOverrideReady
                        ) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
            }
            listOf("updateDefaultPluginTheme", "updateDefaultSysUiTheme").forEach { methodName ->
                hook(themeUtils.getMethod(methodName))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("soft-glass-theme:plugin-$methodName")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                            themeOverrideReady
                        ) {
                            forceThemeUtilsFlags(themeUtils)
                        }
                        result
                    }
            }
            installSoftGlassThemePluginMaterialGuards(classLoader, preferences)
            if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) && themeOverrideReady) {
                forceThemeUtilsFlags(themeUtils)
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed soft-glass global-theme hook for plugin")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install soft-glass global-theme hook for plugin", error)
        }
    }

    private fun installSoftGlassThemeClassLoadGuard(preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runCatching {
            hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-theme:plugin-class-load")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (result is Class<*> && result.name == MIUI_THEME_UTILS_CLASS) {
                        installSoftGlassThemePluginClass(result, preferences)
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed plugin ThemeUtils class-load guard")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install plugin ThemeUtils class-load guard", error)
        }
    }

    private fun installSoftGlassThemePluginClass(
        themeUtils: Class<*>,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        if (dynamicPluginThemeHookInstalled) return
        runCatching {
            listOf("getDefaultPluginTheme", "getDefaultSysUiTheme").forEach { methodName ->
                hook(themeUtils.getMethod(methodName))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("soft-glass-theme:dynamic-$methodName")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                            themeOverrideReady
                        ) true
                        else chain.proceed()
                    }
            }
            listOf("updateDefaultPluginTheme", "updateDefaultSysUiTheme").forEach { methodName ->
                hook(themeUtils.getMethod(methodName))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("soft-glass-theme:dynamic-$methodName")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                            themeOverrideReady
                        ) {
                            forceThemeUtilsFlags(themeUtils)
                        }
                        result
                    }
            }
            themeUtils.classLoader?.let { pluginClassLoader ->
                installSoftGlassThemePluginMaterialGuards(pluginClassLoader, preferences)
            }
            if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) && themeOverrideReady) {
                forceThemeUtilsFlags(themeUtils)
            }
            dynamicPluginThemeHookInstalled = true
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed soft-glass global-theme hook for dynamically loaded plugin")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not hook dynamically loaded plugin ThemeUtils", error)
        }
    }

    private fun installDefaultThemeStateGuard(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        runCatching {
            val themeClass = classLoader.loadClass("com.miui.utils.MiuiThemeUtils")
            val field = themeClass.getDeclaredField("sDefaultSysUiTheme").apply { isAccessible = true }
            hook(classLoader.loadClass("com.android.systemui.statusbar.phone.ConfigurationControllerImpl")
                .getMethod("onConfigurationChanged", android.content.res.Configuration::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-theme:systemui-state-guard")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                        themeOverrideReady
                    ) {
                        field.setBoolean(null, true)
                    }
                    result
                }
            if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                themeOverrideReady
            ) {
                field.setBoolean(null, true)
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed default-theme state guard")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install default-theme state guard", error)
        }
    }

    private fun forceThemeUtilsFlags(themeClass: Class<*>) {
        runCatching {
            themeClass.getDeclaredField("defaultPluginTheme").apply { isAccessible = true }.setBoolean(null, true)
            themeClass.getDeclaredField("defaultSysUiTheme").apply { isAccessible = true }.setBoolean(null, true)
        }
    }

    /**
     * Newer control-center builds cache the result of ThemeUtils in their own StateFlow.
     * Guard the cache's initialization path and the material capability predicate directly,
     * so a global theme cannot disable glass after ThemeUtils has already been updated.
     */
    private fun installSoftGlassThemePluginMaterialGuards(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GLASS)) return
        if (softGlassThemePluginMaterialHooksInstalled) return
        runCatching {
            val blurCompat = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)
            hook(blurCompat.getMethod(
                "getBackgroundMaterialOpenedInDefaultTheme",
                android.content.Context::class.java,
            ))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-theme:plugin-material-enabled")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                        themeOverrideReady
                    ) {
                        true
                    } else {
                        chain.proceed()
                    }
                }

            val defaultThemeController = classLoader.loadClass(MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS)
            hook(defaultThemeController.getMethod("isDefaultTheme"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-theme:plugin-default-theme-controller")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                        themeOverrideReady
                    ) {
                        true
                    } else {
                        chain.proceed()
                    }
                }

            softGlassThemePluginMaterialHooksInstalled = true
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed soft-glass plugin material-state guards")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install soft-glass plugin material-state guards", error)
        }
    }

    private fun installStatusBarVisibilityHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val statusAllowed = ModuleSafetyRuntime.allows(SafetySection.STATUS_BAR)
        val controlAllowed = ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)
        val notificationsAllowed = ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)
        if (!statusAllowed && !controlAllowed && !notificationsAllowed) return
        runCatching {
            hook(View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:visibility")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val requestedVisibility = chain.getArg(0) as? Int
                    if (controlAllowed && preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&
                        isControlCenterEditButtonView(view) &&
                        requestedVisibility != View.GONE
                    ) {
                        logControlCenterEditButtonHidden(view, "visibility")
                        return@intercept chain.proceedWith(
                            chain.thisObject,
                            arrayOf(View.GONE),
                        )
                    }
                    val resourceName = runCatching {
                        view?.resources?.getResourceEntryName(view.id)
                    }.getOrNull()
                    // 0 keeps SystemUI's original presentation, 1 replaces it with the
                    // independent label, and 2 hides the network type completely.
                    val mobileNetworkTypeMode = preferences
                        .getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0)
                        .coerceIn(0, 2)
                    val hideWifiStandard = preferences.getBoolean(KEY_HIDE_STATUS_BAR_WIFI_STANDARD, false)
                    val hideClockText = preferences.getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false)
                    val hideNetworkActivity = preferences.getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY, false)
                    val isIndependentMobileType = statusAllowed && statusBarHooks.isIndependentMobileTypeView(view)
                    val hideSecondaryMobileRoot = statusAllowed && statusBarHooks.isStackedSecondaryMobileRoot(view)
                    val hideOriginalDualSignal =
                        statusAllowed &&
                        (resourceName == "mobile_signal" || resourceName == "mobile_signal_container") &&
                        statusBarHooks.shouldHideSystemMobileSignal(view, preferences.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0))
                    val forcedHidden =
                        hideSecondaryMobileRoot || hideOriginalDualSignal ||
                        (notificationsAllowed && resourceName == "mini_window_bar" &&
                            preferences.getBoolean(KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR, false)) ||
                        (statusAllowed && (((resourceName == "mobile_type" || resourceName == "mobile_type_single" ||
                            resourceName == "mobile_special_5G") &&
                            mobileNetworkTypeMode != 0 &&
                            !(resourceName == "mobile_type_single" &&
                                isIndependentMobileType && mobileNetworkTypeMode == 1)) ||
                            (resourceName == "wifi_standard" && hideWifiStandard) ||
                            (resourceName in setOf("wifi_activity", "mobile_left_mobile_inout") && hideNetworkActivity) ||
                            (resourceName == "battery_text_digit_view" && hideClockText)))
                    if (forcedHidden) {
                        chain.proceedWith(chain.thisObject, arrayOf(View.GONE))
                    } else {
                        chain.proceed()
                    }
                }

            // Some SystemUI builds leave the customize button visible without calling
            // setVisibility after inflation. Apply the setting when the view enters a
            // window as a second, lifecycle-level guard.
            if (controlAllowed) hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("control-center:hide-edit-button-attach")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&
                        view != null &&
                        isControlCenterEditButtonView(view) &&
                        view.visibility != View.GONE
                    ) {
                        logControlCenterEditButtonHidden(view, "attach")
                        view.visibility = View.GONE
                    }
                    result
                }

            statusBarHooks.installBatteryThemeAndTextHooks(classLoader, preferences)
            statusBarHooks.installBatteryInternalTextHooks(classLoader, preferences)
            statusBarHooks.installBatteryDrawableHistoryHook(preferences)

            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed status-bar visibility hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install status-bar visibility hooks", error)
        }
    }

    private fun isControlCenterEditButtonView(view: View?): Boolean {
        if (view == null || view.id == View.NO_ID) return false
        return runCatching {
            view.resources.getResourceEntryName(view.id) == "qs_customize_button"
        }.getOrDefault(false)
    }

    private fun logControlCenterEditButtonHidden(view: View?, source: String) {
        val resourceName = runCatching {
            view?.resources?.getResourceEntryName(view.id)
        }.getOrNull() ?: "unknown"
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Hid control-center edit button view ($source, id=$resourceName)")
    }













    /** Restore clock material values that OS4's OTA conversion rejects (notably glass). */
    private fun installClockMaterialLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val utilityClass = classLoader.loadClass(CLOCK_UTILITY_CLASS)
            val applyMethod = utilityClass.declaredMethods.firstOrNull {
                it.name == CLOCK_UTILITY_METHOD && it.parameterCount == 3
            } ?: error("$CLOCK_UTILITY_METHOD was not found")
            applyMethod.isAccessible = true
            hook(applyMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("clock-material-limit")
                .intercept { chain ->
                    if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {
                        return@intercept chain.proceed()
                    }
                    // SystemUI uses (preset, context, bean), while the DEV AOD build uses
                    // (bean, context, preset). Locate the bean by its accessor instead of
                    // relying on an argument position.
                    val bean = (0 until applyMethod.parameterCount)
                        .asSequence()
                        .mapNotNull { index -> chain.getArg(index) }
                        .firstOrNull { candidate ->
                            runCatching {
                                candidate.javaClass.getMethod("getClockEffect")
                            }.isSuccess
                        }
                    val originalEffect = runCatching {
                        bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int
                    }.getOrNull()
                    val result = chain.proceed()
                    if (originalEffect == CLOCK_EFFECT_GLASS || originalEffect == CLOCK_EFFECT_OVERLAY) {
                        runCatching {
                            bean?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)
                                ?.invoke(bean, originalEffect)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not restore clock material effect", error)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed clock material-limit bypass")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install clock material-limit bypass", error)
        }
    }

    private fun installDynamicIslandHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return
        installDynamicIslandBackgroundHooks(classLoader, preferences)
        systemUiHooks.installDynamicIslandLayoutHooks(classLoader, preferences)
        installDynamicIslandExpandedMaterialHook(classLoader, preferences)
        systemUiHooks.installDynamicIslandBottomGlowHook(classLoader, preferences)
        systemUiHooks.installIslandSimulationGlowBridge(classLoader)
        installDynamicIslandGlassBlurHook(classLoader, preferences)
        installMediaSourceIconHooks(classLoader, preferences)
        systemUiHooks.installDynamicIslandMiniBarHook(classLoader, preferences)
        systemUiHooks.installDynamicIslandAppearanceHooks(classLoader, preferences)
        if (!focusIslandWhitelistPluginHooksInstalled) {
            focusIslandWhitelistPluginHooksInstalled =
                installFocusIslandWhitelistPluginHooks(classLoader, preferences)
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed dynamic-island hooks")
    }





    private fun installMediaSourceIconHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        listOf(
            Triple(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl",
                KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON,
                KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET,
            ),
            Triple(
                "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl",
                KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON,
                KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET,
            ),
        ).forEach { (className, sourceIconKey, verticalOffsetKey) ->
            val section = if (sourceIconKey == KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON) SafetySection.ISLAND else SafetySection.NOTIFICATIONS
            if (!ModuleSafetyRuntime.allows(section)) return@forEach
            runCatching {
                val targetClass = classLoader.loadClass(className)
                val methods = targetClass.declaredMethods.filter {
                    it.name == "bindMediaData" && it.parameterTypes.size == 1
                }
                check(methods.isNotEmpty()) { "$className#bindMediaData not found" }
                methods.forEachIndexed { index, method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("media-card-adjustments:$sourceIconKey:$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val holder = runCatching {
                                targetClass.getDeclaredField("holder").apply { isAccessible = true }.get(chain.thisObject)
                            }.getOrNull()
                            applyMediaCardAdjustments(holder, sourceIconKey, verticalOffsetKey, preferences)
                            result
                        }
                }
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed media source icon hook for $className")
            }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install media source icon hook for $className", error) }
        }
    }

    private fun applyMediaCardAdjustments(
        holder: Any?,
        sourceIconKey: String,
        verticalOffsetKey: String,
        preferences: SharedPreferences,
    ) {
        if (holder == null) return
        fun view(fieldName: String): View? = runCatching {
            holder.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(holder) as? View
        }.getOrNull()

        if (preferences.getBoolean(sourceIconKey, false)) view("appIcon")?.visibility = View.GONE

        val density = view("player")?.resources?.displayMetrics?.density ?: return
        val vertical = -preferences.getInt(verticalOffsetKey, 0).coerceIn(-50, 50) * density
        val spacing = preferences.getInt(KEY_MEDIA_TITLE_ARTIST_SPACING, 0).coerceIn(-30, 30) * density
        view("titleText")?.translationY = vertical - spacing / 2f
        view("artistText")?.translationY = vertical + spacing / 2f

        val cornerOffset = preferences.getInt(KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET, 0).coerceIn(-30, 30)
        if (cornerOffset != 0) {
            view("albumView")?.let { album ->
                val baseId = album.resources.getIdentifier("album_art_bg_radius", "dimen", SYSTEM_UI)
                val baseRadius = if (baseId != 0) album.resources.getDimension(baseId) else 0f
                val radius = (baseRadius + cornerOffset * density).coerceAtLeast(0f)
                album.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                album.clipToOutline = true
                album.invalidateOutline()
            }
        }
    }


    private fun findDynamicIslandField(target: Any, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }



    private fun installFocusIslandWhitelistPluginHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return false
        runCatching {
            classLoader.loadClass("miui.systemui.notification.focus.SignatureChecker")
                .declaredMethods.filter {
                    it.name == "checkSignatures" && it.returnType == Boolean::class.javaPrimitiveType
                }.forEachIndexed { index, method ->
                    method.isAccessible = true
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("unlock-all-focus:signature:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_UNLOCK_ALL_FOCUS, false)) true else chain.proceed()
                        }
                }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Focus signature hook unavailable", it) }
        return runCatching {
            val settingsClass = classLoader.loadClass(PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS)
            listOf("canCustomFocus", "mediaIslandSupportMiniWindow").forEach { name ->
                hook(settingsClass.getMethod(name, String::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("focus-island-whitelist-plugin:$name")
                    .intercept { chain ->
                        if (isFocusWhitelistBypassEnabled(preferences)) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
            }
            hook(settingsClass.getMethod("canShowFocus", android.content.Context::class.java, String::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("focus-island-whitelist-plugin:canShowFocus")
                .intercept { chain ->
                    if (isFocusWhitelistBypassEnabled(preferences)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }

            val focusUtilsClass = classLoader.loadClass(FOCUS_NOTIFICATION_UTILS_CLASS)
            val focusMethod = focusUtilsClass.declaredMethods.first {
                it.name == "canShowFocus" && it.parameterCount == 3
            }
            hook(focusMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("focus-island-whitelist-plugin:focus-permission")
                .intercept { chain ->
                    if (isFocusWhitelistBypassEnabled(preferences)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }

            // Custom focus notifications perform a second, independent signature/XMS
            // authorization in FocusNotificationController.fetchAuthResult.  Bypass only
            // that authorization when the user enabled the whitelist-limit removal.
            runCatching {
                val controllerClass = classLoader.loadClass(FOCUS_NOTIFICATION_CONTROLLER_CLASS)
                val authMethod = controllerClass.declaredMethods.firstOrNull {
                    it.name == "fetchAuthResult" && it.parameterCount == 5
                }
                if (authMethod != null) {
                    hook(authMethod)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("focus-island-whitelist-plugin:auth-result")
                        .intercept { chain ->
                            if (!isFocusWhitelistBypassEnabled(preferences)) {
                                return@intercept chain.proceed()
                            }
                            val sbn = chain.getArg(1)
                            val key = runCatching {
                                sbn?.javaClass?.getMethod("getKey")?.invoke(sbn) as? String
                            }.getOrNull()
                            val packageName = chain.getArg(2) as? String
                            val callback = chain.getArg(4)
                            val success = runCatching {
                                if (key == null || packageName == null || callback == null) {
                                    false
                                } else {
                                    val successMethod = callback.javaClass.methods.firstOrNull {
                                        it.name == "onAuthSuccess" && it.parameterCount == 2
                                    }
                                    if (successMethod == null) {
                                        false
                                    } else {
                                        successMethod.invoke(callback, key, packageName)
                                        true
                                    }
                                }
                            }.getOrDefault(false)
                            if (!success) {
                                chain.proceed()
                            } else {
                                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Allowed focus authorization for $packageName")
                                null
                            }
                        }
                }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install FocusNotificationController auth hook", error)
            }

            val coordinatorClass = classLoader.loadClass(DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS)
            hook(coordinatorClass.getMethod("mediaIslandSupportMiniWindow", String::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("focus-island-whitelist-plugin:event-coordinator")
                .intercept { chain ->
                    if (isFocusWhitelistBypassEnabled(preferences)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }

            val stateCallbackClass = classLoader.loadClass(ISLAND_STATE_CALLBACK_CONTROLLER_CLASS)
            val callbackMethod = stateCallbackClass.declaredMethods.first {
                it.name == "buildPendingStateCallback" && it.parameterCount == 3
            }
            hook(callbackMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("focus-island-whitelist-plugin:state-callback")
                .intercept { chain ->
                    if (isFocusWhitelistBypassEnabled(preferences)) {
                        allowIslandStateCallbackPackage(chain.thisObject, stateCallbackClass, chain.getArg(1))
                    }
                    chain.proceed()
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed focus-notification and island whitelist hooks for plugin")
            true
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install focus-notification and island whitelist plugin hooks", error)
        }.getOrDefault(false)
    }

    private fun isFocusWhitelistBypassEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false) ||
            preferences.getBoolean(KEY_UNLOCK_ALL_FOCUS, false)

    private fun allowIslandStateCallbackPackage(
        controller: Any?,
        controllerClass: Class<*>,
        islandView: Any?,
    ) {
        val sourcePackage = runCatching {
            val data = islandView?.javaClass?.getMethod("getCurrentIslandData")?.invoke(islandView)
            val extras = data?.javaClass?.getMethod("getExtras")?.invoke(data) as? android.os.Bundle
            extras?.getString(DYNAMIC_ISLAND_SOURCE_PACKAGE_KEY)
        }.getOrNull() ?: return
        runCatching {
            val packagesField = controllerClass.getDeclaredField("callbackPackages").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val current = packagesField.get(controller) as? List<String>
            if (current?.contains(sourcePackage) != true) {
                packagesField.set(controller, ArrayList(current.orEmpty()).apply { add(sourcePackage) })
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not extend the island state-callback whitelist", error)
        }
    }

    private fun installFocusIslandWhitelistSystemUiHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return false
        return runCatching {
            val settingsClass = classLoader.loadClass(SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS)
            listOf(
                "canShowFocusState",
                "canShowFocusStateApp",
                "canShowFocusMediaState",
            ).forEach { name ->
                hook(settingsClass.getMethod(name, android.content.Context::class.java, String::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("focus-island-whitelist-systemui:$name")
                    .intercept { chain ->
                        if (isFocusWhitelistBypassEnabled(preferences)) {
                            1
                        } else {
                            chain.proceed()
                        }
                    }
            }

            // The public provider is the entry point used by FocusPlugin for the
            // cross-process canShowFocus query.  It reads app_notification directly,
            // so the settings-manager hooks above cannot affect its result.
            runCatching {
                val providerClass = classLoader.loadClass(NOTIFICATION_PROVIDER_PUBLIC_CLASS)
                hook(
                    providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        android.os.Bundle::class.java,
                    )
                )
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("focus-island-whitelist-systemui:provider-call")
                    .intercept { chain ->
                        val result = chain.proceed() as? android.os.Bundle
                        if (!isFocusWhitelistBypassEnabled(preferences) ||
                            chain.getArg(0) != "canShowFocus"
                        ) {
                            result
                        } else {
                            (result ?: android.os.Bundle()).apply {
                                putBoolean("canShowFocus", true)
                            }
                        }
                    }
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed focus permission hook for NotificationProviderPublic.call")
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install NotificationProviderPublic.call focus hook", error)
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed focus-notification whitelist hooks for SystemUI")
            true
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install focus-notification whitelist SystemUI hooks", error)
        }.getOrDefault(false)
    }

    private fun installDynamicIslandClassDiscovery(
        preferences: SharedPreferences,
        systemUiClassLoader: ClassLoader,
    ) {
        runCatching {
            val loadClassMethod = ClassLoader::class.java.getMethod("loadClass", String::class.java)
            // ClassLoader.loadClass(String, boolean) is protected on Android.  Looking it up
            // with getMethod() throws before either discovery hook is installed, which leaves
            // lazily loaded SystemUI plugin classes invisible to the module.
            val loadClassWithResolveMethod = ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            fun handleLoadedClass(loadedClass: Class<*>) {
                if (controlCenterClassDiscoveryInProgress.get() == true) return
                controlCenterClassDiscoveryInProgress.set(true)
                try {
                controlCenterHooks.onHeaderClassLoaded(loadedClass, preferences)
                notificationHooks.onClassLoaded(loadedClass, preferences)
                islandSmoothingHook.onClassLoaded(loadedClass, preferences)
                lockscreenFaceUnlockHook.onClassLoaded(loadedClass)
                if ((loadedClass.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS ||
                    loadedClass.name == CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS ||
                    loadedClass.name == CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS ||
                    loadedClass.name == CONTROL_CENTER_EXPAND_CONTROLLER_CLASS ||
                    loadedClass.name == CONTROL_CENTER_HEADER_CONTROLLER_CLASS ||
                    loadedClass.name == CONTROL_CENTER_TOUCH_CONTROLLER_CLASS ||
                    loadedClass.name == CONTROL_CENTER_EVENT_HANDLER_CLASS) &&
                    (!controlCenterEditButtonHookInstalled || !controlCenterContentDistributorHookInstalled ||
                        !controlCenterTopButtonsHookInstalled || !controlCenterExpandLifecycleHookInstalled ||
                        !controlCenterHeaderLifecycleHookInstalled ||
                        !controlCenterTouchHookInstalled)
                ) {
                    loadedClass.classLoader?.let { pluginClassLoader ->
                        if (loadedClass.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS ||
                            loadedClass.name == CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS
                        ) {
                            installControlCenterEditButtonHook(pluginClassLoader, preferences, loadedClass)
                        }
                if (loadedClass.name == CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS) {
                            installControlCenterMainPanelHook(pluginClassLoader, preferences, loadedClass)
                        }
                        if (loadedClass.name == CONTROL_CENTER_EXPAND_CONTROLLER_CLASS) {
                            installControlCenterExpandLifecycleHook(pluginClassLoader, loadedClass)
                        }
                        if (loadedClass.name == CONTROL_CENTER_HEADER_CONTROLLER_CLASS) {
                            installControlCenterHeaderLifecycleHook(pluginClassLoader, preferences, loadedClass)
                        }
                        if (loadedClass.name == CONTROL_CENTER_TOUCH_CONTROLLER_CLASS) {
                            installControlCenterTouchHook(pluginClassLoader, loadedClass)
                        }
                        if (loadedClass.name == CONTROL_CENTER_EVENT_HANDLER_CLASS) {
                            installControlCenterEventHandlerHook(pluginClassLoader)
                        }
                    }
                }
                if (loadedClass.name in setOf(
                        GLOBAL_ACTIONS_PLUGIN_CLASS,
                        GLOBAL_ACTIONS_COMPONENT_CLASS,
                        GLOBAL_ACTIONS_IMPL_CLASS,
                        COMMAND_QUEUE_CLASS,
                    ) && !globalActionsHookedClasses.contains(loadedClass.name)
                ) {
                    loadedClass.classLoader?.let { pluginClassLoader ->
                        installGlobalActionsHook(pluginClassLoader, loadedClass)
                    }
                }
                if (loadedClass.name == DYNAMIC_ISLAND_BACKGROUND_CLASS && !dynamicIslandHooksInstalled) {
                    dynamicIslandHooksInstalled = true
                    loadedClass.classLoader?.let { pluginClassLoader ->
                        runCatching {
                            installDynamicIslandHooks(pluginClassLoader, preferences)
                        }.onFailure { error ->
                            dynamicIslandHooksInstalled = false
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize dynamic-island hooks from plugin loader", error)
                        }
                    }
                }
                if (loadedClass.name == PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS &&
                    !focusIslandWhitelistPluginHooksInstalled &&
                    focusIslandWhitelistPluginInstalling.get() != true
                ) {
                    loadedClass.classLoader?.let { pluginClassLoader ->
                        focusIslandWhitelistPluginInstalling.set(true)
                        try {
                            focusIslandWhitelistPluginHooksInstalled =
                                installFocusIslandWhitelistPluginHooks(pluginClassLoader, preferences)
                        } finally {
                            focusIslandWhitelistPluginInstalling.remove()
                        }
                    }
                }
                } finally {
                    controlCenterClassDiscoveryInProgress.remove()
                }
            }
            hook(loadClassMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-class-discovery")
                .intercept { chain ->
                    val loadedClass = chain.proceed() as? Class<*> ?: return@intercept null
                    handleLoadedClass(loadedClass)
                    loadedClass
                }
            hook(loadClassWithResolveMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-class-discovery-resolve")
                .intercept { chain ->
                    val loadedClass = chain.proceed() as? Class<*> ?: return@intercept null
                    handleLoadedClass(loadedClass)
                    loadedClass
                }
            // The plugin may have loaded before SystemUI delivered its package callback.
            runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }
                .getOrNull()
                ?.let(::handleLoadedClass)
            runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS) }
                .getOrNull()
                ?.let(::handleLoadedClass)
            runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS) }
                .getOrNull()
                ?.let(::handleLoadedClass)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed dynamic-island plugin class discovery hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install dynamic-island plugin class discovery hook", error)
        }
    }

    private fun installDynamicIslandBackgroundHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return
        runCatching {
            val backgroundClass = classLoader.loadClass(DYNAMIC_ISLAND_BACKGROUND_CLASS)
            listOf("setDrawable", "setActualHeight", "setActualWidth").forEach { name ->
                val method = when (name) {
                    "setDrawable" -> backgroundClass.getMethod(name, Drawable::class.java)
                    else -> backgroundClass.getMethod(name, Int::class.javaPrimitiveType)
                }
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-island-background-$name")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { if (name == "setDrawable") (chain.thisObject as? View)?.let(expandedIslandMaterialSettings::remove) }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed DynamicIslandBackgroundView update hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install DynamicIslandBackgroundView update hooks", error)
        }
    }


    private fun installDynamicIslandGlassBlurHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return
        runCatching {
            val style = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)
            val method = style.getDeclaredMethod("setBackgroundBlurRadius", View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-glass-blur").intercept { chain ->
                    val view = chain.getArg(0) as? View
                    if (view?.javaClass?.name != "miui.systemui.dynamicisland.window.DynamicIslandWindowView" ||
                        !preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)) return@intercept chain.proceed()
                    val glass = resolveExpandedIslandGlass(preferences)
                    expandedIslandMaterialApplying.set(true)
                    try {
                        // This target is static: replace arguments without replacing its null receiver.
                        chain.proceed(arrayOf(view, chain.getArg(1),
                            softGlassBlur(chain.getArg(2) as Int, glass.refractionBlur),
                            softGlassBlur(chain.getArg(3) as Int, glass.refractionBlur)))
                    } finally {
                        expandedIslandMaterialApplying.remove()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed expanded-island glass blur hook")
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install expanded-island glass blur hook", it) }
    }

    private fun installDynamicIslandExpandedMaterialHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.ISLAND)) return
        runCatching {
            val baseClass = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)
            val method = baseClass.getDeclaredMethod("updateBackgroundBg", View::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val tokenField = baseClass.getDeclaredField("EXPANDED_GLASS_TOKEN").apply { isAccessible = true }
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("dynamic-island-expanded-native-material").intercept { chain ->
                val view = chain.getArg(0) as? View
                if (view != null) expandedIslandGlassSurfaces.add(view)
                val result = chain.proceed()
                applyExpandedIslandNativeMaterial(view, tokenField.get(null), preferences, classLoader)
                // The window owns the glass samples (50/500 in this plugin). Re-run its
                // native update so edits and disabling take effect on the next expansion.
                runCatching {
                    val window = generateSequence(view) { it.parent as? View }.firstOrNull {
                        it.javaClass.name == "miui.systemui.dynamicisland.window.DynamicIslandWindowView"
                    } ?: return@runCatching
                    val opened = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)
                        .getMethod("getBackgroundMaterialOpened", Context::class.java).invoke(null, window.context)
                    window.javaClass.getMethod("updateWindowBlur", Boolean::class.javaPrimitiveType).invoke(window, opened)
                }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not refresh expanded-island glass samples", it) }
                result
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed 18.3 expanded-island material hook")
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install 18.3 expanded-island material hook", it) }
    }

    private val expandedIslandMaterialApplying = ThreadLocal<Boolean>()
    private val expandedIslandGlassSurfaces = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    private fun applyExpandedIslandNativeMaterial(view: View?, token: Any?, preferences: SharedPreferences, classLoader: ClassLoader) {
        if (view == null || token == null || !preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)) return
        val opacity = preferences.getInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, 97).coerceIn(0, 100)
        val glass = resolveExpandedIslandGlass(preferences)
        expandedIslandMaterialApplying.set(true)
        try {
            runCatching {
                val style = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)
                // Respect solid/classic modes and detached views just like the original method.
                if (view.parent == null || style.getMethod("isBionicsActive", Context::class.java).invoke(null, view.context) != true) return@runCatching
                val params = token.javaClass.getMethod("getToBionicsParams").invoke(token) as? FloatArray ?: return@runCatching
                view.javaClass.getMethod("setMiGlass", FloatArray::class.java)
                    .invoke(view, tuneExpandedIslandGlass(params, opacity, glass))
                view.invalidate()
            }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not tune 18.3 expanded-island material", it) }
        } finally {
            expandedIslandMaterialApplying.remove()
        }
    }

    private fun applyExpandedIslandBackground(view: View?, preferences: SharedPreferences, classLoader: ClassLoader) {
        if (view == null || !preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)) return
        if (view.javaClass.name != DYNAMIC_ISLAND_BACKGROUND_CLASS) return
        // alphaAnimation()/scheduleUpdate() owns this drawable's alpha. Expanded Glass is
        // tuned on DynamicIslandExpandedView so the stock black-to-glass transition survives.
    }

    private fun findDynamicIslandBackground(view: View): View? {
        val root = findViewRoot(view)
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (candidate.javaClass.name == DYNAMIC_ISLAND_BACKGROUND_CLASS) return candidate
            if (candidate is ViewGroup) {
                repeat(candidate.childCount) { index ->
                    pending.add(candidate.getChildAt(index))
                }
            }
        }
        return null
    }

    private fun findViewRoot(view: View): View {
        var root = view
        while (root.parent is View) root = root.parent as View
        return root
    }

    private fun isDynamicIslandView(view: View): Boolean =
        generateSequence<View>(view) { it.parent as? View }.any { it.javaClass.name.contains("dynamicisland", true) }

    private var softGlassRuntime: SoftGlassRuntimeSettings? = null
    private var iosNotificationClockGlassRuntime: btm.m.os4.systemuihook.newnotificationcenter.IosNotificationClockGlassRuntime? = null
    private var volumeGlassRuntime: VolumePanelGlassRuntimeSettings? = null
    private var controlCenterRadiusRuntime: ControlCenterRadiusRuntimeSettings? = null
    private val volumeGlobalRecipes = WeakHashMap<View, SoftGlassRecipeCache>()
    private val volumeResourceNames = WeakHashMap<View, Pair<Int, String>>()
    private val softGlassBaselines = WeakHashMap<View, MutableMap<Method, Array<Any?>>>()
    private val softGlassLegacyBlends = WeakHashMap<View, ArrayList<Point>>()
    private val volumeOriginalDrawableAlphas = WeakHashMap<Drawable, Int>()
    private val softGlassArrayPairs = WeakHashMap<View, Pair<FloatArray, FloatArray>>()
    private val softGlassRefreshHandler = Handler(Looper.getMainLooper())
    private val softGlassRefreshing = ThreadLocal<Boolean>()
    private val softGlassBlendDispatching = ThreadLocal<Boolean>()
    private val volumeMaterialDispatching = ThreadLocal<Boolean>()
    private val standaloneSoftGlassSurfaces = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val softGlassRefresh = Runnable {
        val surfaces = synchronized(softGlassBaselines) {
            softGlassBaselines.map { (view, setters) -> view to setters.toMap() }
        }
        val legacyBlends = synchronized(softGlassBaselines) { softGlassLegacyBlends.map { it.key to it.value.toList() } }
        softGlassRefreshing.set(true)
        try {
            surfaces.forEach { (view, setters) ->
                if (view.isAttachedToWindow) setters.forEach { (method, arguments) ->
                    runCatching { method.invoke(view, *arguments) }
                }
            }
            legacyBlends.forEach { (view, colors) ->
                if (view.isAttachedToWindow) runCatching {
                    View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(view)
                    val add = View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    colors.forEach { add.invoke(view, it.x, it.y) }
                }
            }
        } finally {
            softGlassRefreshing.remove()
            if (softGlassRuntime?.settings?.enabled != true) {
                val retainClock = iosNotificationClockGlassRuntime?.settings?.enabled == true
                if (retainClock) {
                    synchronized(softGlassBaselines) {
                        val retired = softGlassBaselines.keys.filterNot {
                            btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView(it)
                        }
                        retired.forEach { softGlassBaselines.remove(it); softGlassArrayPairs.remove(it); softGlassLegacyBlends.remove(it); volumeGlobalRecipes.remove(it) }
                    }
                } else {
                    synchronized(softGlassBaselines) { softGlassBaselines.clear(); softGlassArrayPairs.clear(); softGlassLegacyBlends.clear(); volumeGlobalRecipes.clear() }
                }
                standaloneSoftGlassSurfaces.clear()
            } else if (!volumeGlobalGlassSelected()) {
                // Restore once when leaving global mode, then retire the old volume
                // snapshots so later shade edits cannot replay stale volume recipes.
                synchronized(softGlassBaselines) {
                    val retired = softGlassBaselines.keys.filter(::isVolumePanelSurface)
                    retired.forEach { softGlassBaselines.remove(it); softGlassArrayPairs.remove(it); softGlassLegacyBlends.remove(it); volumeGlobalRecipes.remove(it); standaloneSoftGlassSurfaces.remove(it) }
                }
            }
        }
    }

    private fun rememberSoftGlassBaseline(view: View, method: Method, arguments: Array<Any?>) {
        if (softGlassRefreshing.get() == true) return
        synchronized(softGlassBaselines) { softGlassBaselines.getOrPut(view) { mutableMapOf() }[method] = arguments }
    }

    private fun volumeGlobalGlassSelected(): Boolean = volumeGlassRuntime?.state?.globalSelected == true

    private fun iosClockGlassSettings(view: View): btm.m.os4.systemuihook.SoftGlassSettings? {
        if (!btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView(view)) return null
        val selected = iosNotificationClockGlassRuntime?.settings?.takeIf { it.enabled } ?: return null
        return if (selected.useGlobal) (softGlassRuntime?.settings ?: SoftGlassSettings(enabled = true)).copy(enabled = true)
        else selected.glass.copy(enabled = true)
    }

    private fun isSoftGlassSurface(view: View): Boolean {
        if (expandedIslandMaterialApplying.get() == true || expandedIslandGlassSurfaces.contains(view)) return false
        if (view.tag == SHORTCUT_GLASS_TAG || view.tag == LOCKSCREEN_CAPSULE_MATERIAL_TAG) return false
        if (isVolumePanelSurface(view) || stackContainsClass("com.android.systemui.miui.volume.")) {
            // Some anonymous volume views receive their first recipe before attachment.
            volumePanelSurfaceRoots.add(view)
            return volumeGlobalGlassSelected()
        }
        return softGlassRefreshing.get() == true || isNotificationRowBackground(view) || isShadeBackgroundView(view) ||
            isControlCenterCall() || isNotificationCenterCall() || isMediaNotificationView(view)
    }

    private fun isSoftGlassPanelLayer(view: View): Boolean =
        expandedIslandMaterialApplying.get() != true && !expandedIslandGlassSurfaces.contains(view) && view.tag != SHORTCUT_GLASS_TAG && view.tag != LOCKSCREEN_CAPSULE_MATERIAL_TAG && (
            (volumeGlobalGlassSelected() && (isVolumePanelSurface(view) || stackContainsClass("com.android.systemui.miui.volume."))) ||
            isShadeBackgroundView(view) || stackContainsClass("ShadeBlendBlurController") ||
            stackContainsClass("ShadeWindowBlurController") || stackContainsClass("ShadeSolidBackgroundController"))

    private fun isSoftGlassBlendSurface(view: View): Boolean {
        if (expandedIslandMaterialApplying.get() == true || expandedIslandGlassSurfaces.contains(view)) return false
        if (view.tag == SHORTCUT_GLASS_TAG || view.tag == LOCKSCREEN_CAPSULE_MATERIAL_TAG) return false
        if (isVolumePanelSurface(view)) return volumeGlobalGlassSelected()
        return (isNotificationRowBackground(view) && isHeadsUpNotificationSurface(view)) ||
            isSoftGlassPanelLayer(view) || softGlassRefreshing.get() == true
    }

    private fun usesStandaloneSoftGlass(view: View): Boolean =
        softGlassRuntime?.settings?.enabled == true && standaloneSoftGlassSurfaces.contains(view) &&
            (if (isVolumePanelSurface(view)) volumeGlobalGlassSelected() else isHeadsUpNotificationSurface(view))

    private fun updateStandaloneSoftGlass(view: View, active: Boolean) {
        if (!active) {
            standaloneSoftGlassSurfaces.remove(view)
            return
        }
        if (!standaloneSoftGlassSurfaces.add(view)) return
        // Keep the native blend points: MIUI uses them to enable the background sampling
        // layer even when a Bionics recipe is active. Their alpha is normalized below so the
        // global opacity is not multiplied into the same glass surface a second time.
    }

    private fun softGlassBlendPoints(original: ArrayList<*>, percent: Int): ArrayList<Point> =
        ArrayList<Point>(original.size).apply {
            original.filterIsInstance<Point>().forEach { add(Point(softGlassColorAlpha(it.x, percent), it.y)) }
        }

    private fun installShadeMaterialHooks(preferences: SharedPreferences, classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allowsSharedShade()) return
        softGlassRuntime = SoftGlassRuntimeSettings(preferences) {
            softGlassRefreshHandler.removeCallbacks(softGlassRefresh)
            softGlassRefreshHandler.postDelayed(softGlassRefresh, 16L)
        }
        iosNotificationClockGlassRuntime = btm.m.os4.systemuihook.newnotificationcenter.IosNotificationClockGlassRuntime(preferences) {
            softGlassRefreshHandler.removeCallbacks(softGlassRefresh)
            softGlassRefreshHandler.postDelayed(softGlassRefresh, 16L)
        }
        installFocusNotificationMaterialEnforcementHooks(preferences, classLoader)
        installFocusNotificationBackgroundHook(preferences, classLoader)
        runCatching {
            val method = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-recipe")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val original = chain.getArg(0) as? FloatArray ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings
                    val clockSettings = iosClockGlassSettings(view)
                    if (clockSettings != null) {
                        val baseline = synchronized(softGlassBaselines) {
                            val previous = softGlassArrayPairs[view]
                            if (original === previous?.second) previous.first else original.copyOf()
                        }
                        rememberSoftGlassBaseline(view, method, arrayOf(baseline))
                        val tuned = tuneSoftGlass(baseline, clockSettings)
                        synchronized(softGlassBaselines) { softGlassArrayPairs[view] = baseline to tuned }
                        return@intercept chain.proceedWith(view, arrayOf(tuned))
                    }
                    if (isVolumePanelSurface(view)) {
                        if (settings?.enabled != true || !volumeGlobalGlassSelected()) {
                            updateStandaloneSoftGlass(view, false)
                            return@intercept chain.proceed()
                        }
                        if (!hasSoftGlassRecipe(original)) {
                            updateStandaloneSoftGlass(view, false)
                            return@intercept chain.proceed()
                        }
                        val cache = synchronized(softGlassBaselines) { volumeGlobalRecipes.getOrPut(view) { SoftGlassRecipeCache() } }
                        val previous = cache.baseline
                        // HyperLight's unified path uses the normal glass recipe for volume
                        // surfaces before applying global slots. This avoids carrying the
                        // darker collapsed volume token into the shared material.
                        val unified = normalNotificationGlassParams(view) ?: original
                        val tuned = cache.tune(
                            unified,
                            settings,
                            standaloneBackground = true,
                            contextBrightness = 0.48f,
                        )
                        if (cache.baseline !== previous) rememberSoftGlassBaseline(view, method, arrayOf(original.copyOf()))
                        val result = chain.proceedWith(view, arrayOf(tuned))
                        updateStandaloneSoftGlass(view, hasSoftGlassRecipe(tuned))
                        return@intercept result
                    }
                    val normal = if (original.size >= 42 && original.any { it != 0f } &&
                        shouldUseNormalNotificationMaterial(view, preferences)) normalNotificationGlassParams(view) else null
                    if (settings?.enabled != true || !isSoftGlassSurface(view)) {
                        updateStandaloneSoftGlass(view, false)
                        return@intercept if (normal != null) chain.proceedWith(view, arrayOf(normal)) else chain.proceed()
                    }
                    val baseline = synchronized(softGlassBaselines) {
                        val previous = softGlassArrayPairs[view]
                        if (original === previous?.second) previous.first else original.copyOf()
                    }
                    rememberSoftGlassBaseline(view, method, arrayOf(baseline))
                    val standalone = isNotificationRowBackground(view) && isHeadsUpNotificationSurface(view)
                    val tuned = tuneSoftGlass(
                        normal ?: baseline,
                        settings,
                        standaloneBackground = standalone,
                        // HeadsUpNotificationGlassEffect already supplies the correct
                        // light/dark recipe.  HyperModifier preserves that mode-specific
                        // payload and only adjusts its slots; adding the unified +0.48
                        // compensation here makes light-mode heads-up backgrounds glow and
                        // washes out notification text.
                        contextBrightness = 0f,
                    )
                    synchronized(softGlassBaselines) { softGlassArrayPairs[view] = baseline to tuned }
                    val result = chain.proceedWith(view, arrayOf(tuned))
                    updateStandaloneSoftGlass(view, standalone && hasSoftGlassRecipe(tuned))
                    result
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiGlass", error) }
        runCatching {
            val method = View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-dual-radius")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                    val clockSettings = iosClockGlassSettings(view)
                    if (clockSettings != null) {
                        rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0), chain.getArg(1)))
                        return@intercept chain.proceedWith(view, arrayOf(
                            softGlassBlur(chain.getArg(0) as Int, clockSettings.refractionBlur),
                            softGlassBlur(chain.getArg(1) as Int, clockSettings.refractionBlur),
                        ))
                    }
                    if (!settings.enabled || !isSoftGlassSurface(view)) return@intercept chain.proceed()
                    rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0), chain.getArg(1)))
                    // GlassToken.blurRadius is separate from the ordinary background blur.
                    // Keep both native samples and their animation-zero values independent.
                    chain.proceedWith(view, arrayOf(softGlassBlur(chain.getArg(0) as Int, settings.refractionBlur), softGlassBlur(chain.getArg(1) as Int, settings.refractionBlur)))
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiGlassBlurRadius", error) }
        runCatching {
            val method = View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-background-radius")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                    val clockSettings = iosClockGlassSettings(view)
                    if (clockSettings != null) {
                        rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0)))
                        return@intercept chain.proceedWith(view, arrayOf(softGlassBlur(chain.getArg(0) as Int, clockSettings.backgroundBlur)))
                    }
                    if (!settings.enabled || !isSoftGlassSurface(view)) return@intercept chain.proceed()
                    rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0)))
                    chain.proceedWith(view, arrayOf(softGlassBlur(chain.getArg(0) as Int, settings.backgroundBlur)))
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiBackgroundBlurRadius", error) }
        runCatching {
            val method = View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-sampling-scale")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                    val clockSettings = iosClockGlassSettings(view)
                    if (clockSettings != null) {
                        rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0)))
                        return@intercept chain.proceedWith(view, arrayOf(softGlassScale(chain.getArg(0) as Float, clockSettings.backgroundScale)))
                    }
                    if (!settings.enabled || !isSoftGlassSurface(view)) return@intercept chain.proceed()
                    rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0)))
                    chain.proceedWith(view, arrayOf(softGlassScale(chain.getArg(0) as Float, settings.backgroundScale)))
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiBackgroundBlurScaleRatio", error) }
        runCatching {
            val method = View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-blend-batch")
                .intercept { chain ->
                    if (softGlassBlendDispatching.get() == true) return@intercept chain.proceed()
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val original = chain.getArg(0) as? ArrayList<*> ?: return@intercept chain.proceed()
                    val clockSettings = iosClockGlassSettings(view)
                    if (clockSettings != null) {
                        val copy = ArrayList(original.filterIsInstance<Point>().map { Point(it.x, it.y) })
                        rememberSoftGlassBaseline(view, method, arrayOf(copy))
                        softGlassBlendDispatching.set(true)
                        try {
                            return@intercept chain.proceedWith(view, arrayOf(softGlassBlendPoints(copy, clockSettings.backgroundOpacity)))
                        } finally {
                            softGlassBlendDispatching.remove()
                        }
                    }
                    val normal = if (isVolumePanelSurface(view)) null else normalNotificationBlendPoints(view, preferences)
                    val settings = softGlassRuntime?.settings
                    if (settings?.enabled != true || !isSoftGlassBlendSurface(view)) {
                        return@intercept if (normal != null) chain.proceedWith(view, arrayOf(normal)) else chain.proceed()
                    }
                    // New batch setter and old addMiBackgroundBlendColor are alternatives.
                    // Never tune this list again through the Compat wrapper.
                    val copy = ArrayList(original.filterIsInstance<Point>().map { Point(it.x, it.y) })
                    rememberSoftGlassBaseline(view, method, arrayOf(copy))
                    if (softGlassRefreshing.get() != true) synchronized(softGlassBaselines) { softGlassLegacyBlends.remove(view) }
                    val percent = settings.backgroundOpacity
                    softGlassBlendDispatching.set(true)
                    try {
                        val points = softGlassBlendPoints(normal ?: copy, if (usesStandaloneSoftGlass(view)) 100 else percent)
                        chain.proceedWith(view, arrayOf(points))
                    }
                    finally { softGlassBlendDispatching.remove() }
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiBackgroundBlendColors", error) }
        runCatching {
            val clear = View::class.java.getMethod("clearMiBackgroundBlendColor")
            val add = View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            hookDeoptimized(clear).setExceptionMode(ExceptionMode.PROTECTIVE).setId("soft-glass-v4-blend-clear").intercept { chain ->
                val view = chain.thisObject as? View
                if (view != null && softGlassRefreshing.get() != true && softGlassBlendDispatching.get() != true) synchronized(softGlassBaselines) {
                    softGlassLegacyBlends.remove(view)
                    softGlassBaselines[view]?.keys?.removeAll { it.name == "setMiBackgroundBlendColors" }
                }
                chain.proceed()
            }
            hookDeoptimized(add).setExceptionMode(ExceptionMode.PROTECTIVE).setId("soft-glass-v4-blend-add").intercept { chain ->
                val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                if (softGlassBlendDispatching.get() == true) return@intercept chain.proceed()
                val clockSettings = iosClockGlassSettings(view)
                if (clockSettings != null) {
                    if (softGlassRefreshing.get() != true) synchronized(softGlassBaselines) {
                        softGlassLegacyBlends.getOrPut(view) { arrayListOf() }.add(Point(chain.getArg(0) as Int, chain.getArg(1) as Int))
                    }
                    softGlassBlendDispatching.set(true)
                    return@intercept try {
                        chain.proceedWith(view, arrayOf(softGlassColorAlpha(chain.getArg(0) as Int, clockSettings.backgroundOpacity), chain.getArg(1)))
                    } finally {
                        softGlassBlendDispatching.remove()
                    }
                }
                if (!settings.enabled || !isSoftGlassBlendSurface(view)) return@intercept chain.proceed()
                if (softGlassRefreshing.get() != true) synchronized(softGlassBaselines) {
                    softGlassLegacyBlends.getOrPut(view) { arrayListOf() }.add(Point(chain.getArg(0) as Int, chain.getArg(1) as Int))
                }
                // Native glass still needs the points for its backdrop sampler. Keep their
                // original alpha for volume and heads-up surfaces to avoid double dimming.
                val percent = if (usesStandaloneSoftGlass(view)) 100 else settings.backgroundOpacity
                chain.proceedWith(view, arrayOf(softGlassColorAlpha(chain.getArg(0) as Int, percent), chain.getArg(1)))
            }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Legacy blend API unavailable", error) }
        runCatching {
            val method = View::class.java.getMethod("setMiSelfBlur", Int::class.javaPrimitiveType, ArrayList::class.java)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-window-self-blur")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                    if (isVolumePanelSurface(view)) return@intercept chain.proceed()
                    if (!settings.enabled || (!stackContainsClass("ShadeWindowBlurController") && softGlassRefreshing.get() != true)) return@intercept chain.proceed()
                    val original = chain.getArg(1) as? ArrayList<*> ?: return@intercept chain.proceed()
                    val copy = ArrayList(original.filterIsInstance<Point>().map { Point(it.x, it.y) })
                    rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0), copy))
                    val percent = settings.backgroundOpacity
                    softGlassBlendDispatching.set(true)
                    try { chain.proceedWith(view, arrayOf(softGlassBlur(chain.getArg(0) as Int, settings.backgroundBlur), softGlassBlendPoints(copy, percent))) }
                    finally { softGlassBlendDispatching.remove() }
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setMiSelfBlur", error) }
        runCatching {
            val method = View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("soft-glass-v4-panel-color")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val settings = softGlassRuntime?.settings ?: return@intercept chain.proceed()
                    if (!settings.enabled) return@intercept chain.proceed()
                    if (isVolumePanelSurface(view)) return@intercept chain.proceed()
                    val dim = stackContainsClass("ShadeWindowBlurController")
                    val solid = stackContainsClass("ShadeSolidBackgroundController")
                    if (!settings.enabled || (!dim && !solid && softGlassRefreshing.get() != true)) return@intercept chain.proceed()
                    rememberSoftGlassBaseline(view, method, arrayOf(chain.getArg(0)))
                    val percent = settings.backgroundOpacity
                    chain.proceedWith(view, arrayOf(softGlassColorAlpha(chain.getArg(0) as Int, percent)))
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Soft glass API unavailable: setBackgroundColor", error) }
    }

    private fun installLockscreenClockColonHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        scope: String,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val method = classLoader.loadClass(CLOCK_BEAN_CLASS)
                .getDeclaredMethod(CLOCK_BEAN_IS_COLON_SHOW_METHOD)
                .apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-colon:$scope")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE, false)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen clock colon hook for $scope")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen clock colon hook for $scope", error)
        }
    }

    private fun installLockscreenCarrierHideHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean = if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) false else runCatching {
        val carrierClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterCarrierText")
        val method = carrierClass.getDeclaredMethod("shouldShow").apply { isAccessible = true }
        hookDeoptimized(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-carrier-hide")
            .intercept { chain ->
                val original = chain.proceed() as? Boolean ?: false
                val mode = preferences.getInt(KEY_LOCKSCREEN_CARRIER_HIDE_MODE, LOCKSCREEN_CARRIER_HIDE_NONE)
                if (!original && mode == LOCKSCREEN_CARRIER_HIDE_NONE) {
                    return@intercept original
                }
                val carrier = chain.thisObject
                val keyguard = runCatching {
                    carrier.javaClass.getField("isKeyguardLayout").getBoolean(carrier)
                }.getOrDefault(false)
                if (!keyguard) return@intercept original
                val slot = runCatching { carrier.javaClass.getMethod("getSlotId").invoke(carrier) as Int }
                    .getOrDefault(-1)
                if (slot !in 0..1) return@intercept original
                val hide = when (mode) {
                    LOCKSCREEN_CARRIER_HIDE_SIM1 -> slot == 0
                    LOCKSCREEN_CARRIER_HIDE_SIM2 -> slot == 1
                    LOCKSCREEN_CARRIER_HIDE_NON_DATA,
                    LOCKSCREEN_CARRIER_HIDE_DATA -> {
                        val dataSubId = SubscriptionManager.getActiveDataSubscriptionId().takeIf {
                            SubscriptionManager.isValidSubscriptionId(it)
                        } ?: SubscriptionManager.getDefaultDataSubscriptionId()
                        if (!SubscriptionManager.isValidSubscriptionId(dataSubId)) return@intercept original
                        val dataSlot = SubscriptionManager.getSlotIndex(dataSubId)
                        if (dataSlot !in 0..1) return@intercept original
                        if (mode == LOCKSCREEN_CARRIER_HIDE_DATA) slot == dataSlot else slot != dataSlot
                    }
                    else -> false
                }
                if (hide) false else original
            }
        val layoutClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout")
        val onMeasure = layoutClass.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        hook(onMeasure)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-carrier-hide-layout")
            .intercept { chain ->
                val layout = chain.thisObject
                val mode = preferences.getInt(KEY_LOCKSCREEN_CARRIER_HIDE_MODE, LOCKSCREEN_CARRIER_HIDE_NONE)
                val keyguard = runCatching {
                    layout.javaClass.getDeclaredMethod("getKeyguardHeaderLayout").apply { isAccessible = true }
                        .invoke(layout) as Boolean
                }.getOrDefault(false)
                val children = listOf("leftCarrierTextView", "rightCarrierTextView").mapNotNull { name ->
                    runCatching { layout.javaClass.getField(name).get(layout) as? View }.getOrNull()
                }
                // Restore views before the vendor measure pass; otherwise a previous GONE state
                // would make the vendor shouldShow() permanently return false after the setting
                // is changed back to "Do not hide".
                if (keyguard) children.forEach { it.visibility = View.VISIBLE }
                chain.proceed()
                if (keyguard && mode != LOCKSCREEN_CARRIER_HIDE_NONE) {
                    children.forEach { child ->
                        val slot = runCatching { child.javaClass.getMethod("getSlotId").invoke(child) as Int }
                            .getOrDefault(-1)
                        if (slot !in 0..1) return@forEach
                        val hide = when (mode) {
                            LOCKSCREEN_CARRIER_HIDE_SIM1 -> slot == 0
                            LOCKSCREEN_CARRIER_HIDE_SIM2 -> slot == 1
                            LOCKSCREEN_CARRIER_HIDE_NON_DATA,
                            LOCKSCREEN_CARRIER_HIDE_DATA -> {
                                val dataSubId = SubscriptionManager.getActiveDataSubscriptionId().takeIf {
                                    SubscriptionManager.isValidSubscriptionId(it)
                                } ?: SubscriptionManager.getDefaultDataSubscriptionId()
                                val dataSlot = SubscriptionManager.getSlotIndex(dataSubId)
                                dataSlot in 0..1 && if (mode == LOCKSCREEN_CARRIER_HIDE_DATA) slot == dataSlot else slot != dataSlot
                            }
                            else -> false
                        }
                        if (hide) child.visibility = View.GONE
                    }
                    (layout as? View)?.requestLayout()
                }
            }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen carrier visibility hook")
        true
    }.getOrElse { error ->
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen carrier visibility hook", error)
        false
    }


    /**
     * AllInOneUtil clamps the requested time width to the edit rectangle in both
     * SystemUI and the AOD/editor package. Widening only the horizontal clock
     * envelope keeps the OEM vertical/depth rules intact while allowing an
     * intentionally oversized clock to survive the final layout calculation.
     */
    private fun installLockscreenBigClockWidthHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        scope: String,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val allInOneUtilClass = classLoader.loadClass(ALL_IN_ONE_UTIL_CLASS)
            val computeMethods = allInOneUtilClass.declaredMethods.filter { method ->
                method.name == "computeForScreen"
            }
            check(computeMethods.isNotEmpty()) { "No AllInOneUtil.computeForScreen method found" }
            computeMethods.forEachIndexed { index, method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-big-clock-compute:$scope:$index")
                    .intercept { chain ->
                        if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false)) {
                            return@intercept chain.proceed()
                        }
                        // The OEM result is cached by ClockLayoutInput and screen geometry, neither
                        // of which changes when this module preference is toggled. Drop the stale
                        // constrained result before recalculating with the widened edit rectangle.
                        runCatching {
                            allInOneUtilClass.getDeclaredField("cachedResult")
                                .apply { isAccessible = true }
                                .set(null, null)
                        }
                        val previousDepth = bigClockWidthCalculationDepth.get() ?: 0
                        bigClockWidthCalculationDepth.set(previousDepth + 1)
                        try {
                            val result = chain.proceed()
                            restoreRequestedClockWidth(result, chain.getArg(0), chain.getArg(1))
                        } finally {
                            bigClockWidthCalculationDepth.set(previousDepth)
                        }
                    }
            }

            val method = classLoader.loadClass(CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS)
                .declaredMethods
                .single { it.name == "createEditRect\$default" && it.returnType == Rect::class.java }
                .apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-big-clock-width:$scope")
                .intercept { chain ->
                    val result = chain.proceed()
                    val original = result as? Rect ?: return@intercept result
                    if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false) ||
                        (bigClockWidthCalculationDepth.get() ?: 0) <= 0
                    ) {
                        return@intercept original
                    }
                    val screenWidth = (chain.getArg(1) as? Int)?.coerceAtLeast(1) ?: original.width()
                    widenedClockRect(original, screenWidth)
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen big-clock width hook for $scope")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen big-clock width hook for $scope", error)
        }
    }

    /** Lets the lock-screen editor's resize handle move beyond the physical panel width. */
    private fun installLockscreenBigClockEditorWidthHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val method = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)
                .getDeclaredMethod("getMaxDragAllowedRect")
                .apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-big-clock-editor-width")
                .intercept { chain ->
                    val result = chain.proceed()
                    val original = result as? Rect ?: return@intercept result
                    if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false)) {
                        return@intercept original
                    }
                    val view = chain.thisObject as? View
                    val screenWidth = view?.resources?.displayMetrics?.widthPixels?.coerceAtLeast(1)
                        ?: original.width().coerceAtLeast(1)
                    widenedClockRect(original, screenWidth)
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen big-clock editor width hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen big-clock editor width hook", error)
        }
    }

    private fun installLockscreenClockHorizontalRectHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        scope: String,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val baseClass = classLoader.loadClass("com.miui.clock.allInOne.AllInOneBase")
            val notificationField = baseClass.getDeclaredField("mInNotificationState").apply { isAccessible = true }
            val method = baseClass
                .getDeclaredMethod("setClockViewRect", Rect::class.java)
                .apply { isAccessible = true }
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-horizontal-rect:$scope")
                .intercept { chain ->
                    val clock = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val sourceRect = chain.getArg(0) as? Rect ?: return@intercept chain.proceed()
                    val rect = Rect(sourceRect)
                    val offset = if (preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false) &&
                        !notificationField.getBoolean(clock)
                    ) {
                        clockHorizontalLiveOffsets[clock]
                            ?: readLockscreenClockHorizontalOffset(clock.context)
                    } else 0f
                    synchronized(clockHorizontalRectOffsets) {
                        val previous = clockHorizontalRectOffsets[clock]
                        val previousShift = previous?.second ?: 0
                        // Only undo the old shift when the OEM is reusing the same
                        // already-shifted rectangle. A newly computed baseline must
                        // remain untouched, otherwise an untouched clock drifts on
                        // every layout pass.
                        if (previous != null && sourceRect == previous.first) {
                            rect.offset(-previousShift, 0)
                        }
                        val screenWidth = clock.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                        // The editor supplies a full-screen envelope for some clock
                        // styles, so edge-space clamping would reduce every shift to
                        // zero. The saved offset is already bounded to a sensible
                        // horizontal range and is applied symmetrically here.
                        val shift = (screenWidth * offset).roundToInt()
                        rect.offset(shift, 0)
                        clockHorizontalRectOffsets[clock] = Rect(rect) to shift
                    }
                    chain.proceed()
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen clock horizontal rect hook for $scope")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen clock horizontal rect hook for $scope", error)
        }
    }

    private fun installLockscreenClockHorizontalEditorHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val wallpaperClass = classLoader.loadClass(
                "com.miui.keyguard.editor.edit.wallpaper.CombinedWallpaperView",
            )
            val dispatchTouchMethod = wallpaperClass
                .getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
                .apply { isAccessible = true }
            hook(dispatchTouchMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-horizontal-editor-wallpaper-touch")
                .intercept { chain ->
                    val wallpaper = chain.thisObject as? View
                    val event = chain.getArg(0) as? MotionEvent
                    val eventX = event?.x
                    val editor = wallpaper?.let { findAllInOneEditorForWallpaper(it) }
                        ?: activeLockscreenClockEditor?.get()
                    if (editor != null) {
                        activeLockscreenClockEditor = WeakReference(editor)
                    }
                    val result = chain.proceed()
                    val resolvedEditor = editor
                        ?: wallpaper?.let { findAllInOneEditorForWallpaper(it) }
                        ?: activeLockscreenClockEditor?.get()
                    if (resolvedEditor != null && event != null) {
                        activeLockscreenClockEditor = WeakReference(resolvedEditor)
                        handleLockscreenClockHorizontalTouch(resolvedEditor, event, preferences, eventX)
                    }
                    result
                }
            wallpaperClass.getDeclaredMethod(
                "setObserveTouchClickListener",
                classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.ObserverClickListener"),
            ).apply { isAccessible = true }.let { setter ->
                hook(setter)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-clock-horizontal-editor-listener")
                    .intercept { chain ->
                        val listener = chain.getArg(0)
                        findAllInOneEditorInObject(listener, IdentityHashMap(), 0)?.let { editor ->
                            activeLockscreenClockEditor = WeakReference(editor)
                        }
                        chain.proceed()
                    }
            }
            lockscreenClockCombinedTouchHookInstalled = true

            val editorClass = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)
            val touchMethod = editorClass.getDeclaredMethod("onObservedTouchEvent", MotionEvent::class.java)
            hook(touchMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-horizontal-editor-touch")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val event = chain.getArg(0) as? MotionEvent
                    if (view != null) {
                        activeLockscreenClockEditor = WeakReference(view)
                    }
                    val result = chain.proceed()
                    if (!lockscreenClockCombinedTouchHookInstalled && view != null && event != null) {
                        handleLockscreenClockHorizontalTouch(view, event, preferences)
                    }
                    result
                }
            editorClass.declaredMethods.filter { it.name == "onMiuiClockViewCreated" }.forEachIndexed { index, method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-clock-horizontal-editor-create:$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val view = chain.thisObject as? View
                        if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {
                            clockHorizontalEditorShifts.remove(view)
                            clockHorizontalEditorClockShifts.remove(view)
                            applyEditorClockHorizontalOffset(
                                view,
                                readLockscreenClockHorizontalOffset(view.context),
                            )
                        }
                        result
                    }
            }
            editorClass.methods.firstOrNull { it.name == "generateEditFrames" && it.parameterCount == 0 }
                ?.let { method ->
                    method.isAccessible = true
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-horizontal-editor-generate-frames")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val view = chain.thisObject as? View
                            if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {
                                clockHorizontalEditorShifts.remove(view)
                                applyEditorClockHorizontalOffset(
                                    view,
                                    readLockscreenClockHorizontalOffset(view.context),
                                )
                            }
                            result
                        }
                }
            hook(editorClass.getDeclaredMethod("getFramesComplete"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-horizontal-editor-frames")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {
                        applyEditorClockHorizontalOffset(view, readLockscreenClockHorizontalOffset(view.context))
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen clock horizontal editor hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen clock horizontal editor hook", error)
        }
    }

    private fun handleLockscreenClockHorizontalTouch(
        editor: View,
        event: MotionEvent,
        preferences: SharedPreferences,
        eventX: Float? = null,
    ) {
        if (!preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false) ||
            event.pointerCount != 1
        ) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
                event.actionMasked == MotionEvent.ACTION_UP
            ) {
                clearEditorClockHorizontalPreview(editor)
                clockHorizontalDrags.remove(editor)
            }
            return
        }
        val x = eventX?.takeIf { it.isFinite() } ?: event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clockHorizontalDrags[editor] = ClockHorizontalDrag(
                    x,
                    readLockscreenClockHorizontalOffset(editor.context),
                )
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            -> {
                val drag = clockHorizontalDrags[editor] ?: return
                val width = editor.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                val offset = (drag.initialOffset + (x - drag.downX) / width)
                    .coerceIn(-0.45f, 0.45f)
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    applyEditorClockHorizontalPreview(
                        editor,
                        ((offset - drag.initialOffset) * width).roundToInt(),
                    )
                    return
                }
                clearEditorClockHorizontalPreview(editor)
                // Commit after the OEM observer has processed the same event so its
                // vertical drag and resize state remains authoritative.
                applyEditorClockHorizontalOffset(editor, offset)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!writeLockscreenClockHorizontalOffset(editor.context, offset)) {
                        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not save lockscreen clock horizontal position")
                    }
                    clockHorizontalDrags.remove(editor)
                }
            }
            MotionEvent.ACTION_CANCEL -> clockHorizontalDrags.remove(editor)
        }
    }

    private fun applyEditorClockHorizontalPreview(editor: View, delta: Int) {
        listOf("mTimeAreaFrame", "mKgTextAreaFrame", "cornerHandleView").forEach { name ->
            runCatching {
                findField(editor.javaClass, name)?.get(editor)
            }.getOrNull()?.let { (it as? View)?.translationX = delta.toFloat() }
        }
        runCatching {
            editor.javaClass.methods.firstOrNull {
                it.name == "getMKgSignatureAreaFrame" && it.parameterCount == 0
            }?.invoke(editor)
        }.getOrNull()?.let { (it as? View)?.translationX = delta.toFloat() }
        val base = clockHorizontalEditorClockShifts[editor] ?: 0
        forEachEditorClock(editor) { clock ->
            clock.translationX = (base + delta).toFloat()
        }
    }

    private fun clearEditorClockHorizontalPreview(editor: View) {
        applyEditorClockHorizontalPreview(editor, 0)
    }

    private fun findAllInOneEditorForWallpaper(wallpaper: View): View? {
        val listener = runCatching {
            wallpaper.javaClass.getDeclaredField("touchClickListener")
                .apply { isAccessible = true }
                .get(wallpaper)
        }.getOrNull() ?: return null
        return findAllInOneEditorInObject(listener, IdentityHashMap(), 0)
    }

    private fun findAllInOneEditorInObject(
        value: Any?,
        seen: IdentityHashMap<Any, Boolean>,
        depth: Int,
    ): View? {
        if (value == null || depth > 3 || seen.put(value, true) != null) return null
        if (value is View && value.javaClass.name == ALL_IN_ONE_TEMPLATE_VIEW_CLASS) return value
        if (value is View) return null
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    findAllInOneEditorInObject(field.get(value), seen, depth + 1)
                }.getOrNull()?.let { return it }
            }
            type = type.superclass
        }
        return null
    }

    private fun applyEditorClockHorizontalOffset(editor: View, offset: Float) {
        val shift = (editor.resources.displayMetrics.widthPixels.coerceAtLeast(1) * offset).roundToInt()
        val previousFrameShift = clockHorizontalEditorShifts[editor] ?: 0
        val frameDelta = shift - previousFrameShift
        if (frameDelta != 0) {
            listOf("mTimeAreaFrame", "mKgTextAreaFrame", "cornerHandleView").forEach { name ->
                moveEditorFrame(findField(editor.javaClass, name)?.get(editor), frameDelta)
            }
            moveEditorFrame(
                editor.javaClass.methods.firstOrNull { it.name == "getMKgSignatureAreaFrame" && it.parameterCount == 0 }
                    ?.invoke(editor),
                frameDelta,
            )
        }
        val previousClockShift = clockHorizontalEditorClockShifts[editor] ?: 0
        val clockDelta = shift - previousClockShift
        if (clockDelta != 0) {
            forEachEditorClock(editor) { clock ->
                clock.translationX += clockDelta.toFloat()
            }
        }
        clockHorizontalEditorShifts[editor] = shift
        clockHorizontalEditorClockShifts[editor] = shift
    }

    private fun forEachEditorClock(editor: View, block: (View) -> Unit) {
        val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        listOf("getMiuiClockView", "getForeMiuiClockView").forEach { name ->
            val wrapper = runCatching {
                editor.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(editor)
            }.getOrNull() ?: return@forEach
            val controller = runCatching { findField(wrapper.javaClass, "mMiuiClockController")?.get(wrapper) }
                .getOrNull() ?: return@forEach
            val clock = runCatching { findField(controller.javaClass, "mClockView")?.get(controller) as? View }
                .getOrNull() ?: return@forEach
            if (isAllInOneClock(clock) && seen.add(clock)) block(clock)
        }
    }

    private fun isAllInOneClock(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.miui.clock.allInOne.AllInOneBase") return true
            type = type.superclass
        }
        return false
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun moveEditorFrame(frame: Any?, delta: Int) {
        val view = frame as? View ?: return
        view.offsetLeftAndRight(delta)
        runCatching {
            val param = view.javaClass.getDeclaredField("mParam").apply { isAccessible = true }.get(view)
            val rect = param?.javaClass?.getDeclaredField("rect")?.apply { isAccessible = true }?.get(param) as? Rect
            rect?.offset(delta, 0)
        }
    }

    private fun widenedClockRect(original: Rect, screenWidth: Int): Rect {
        val targetWidth = (screenWidth.toLong() * BIG_CLOCK_WIDTH_ENVELOPE_MULTIPLIER)
            .coerceAtMost(Int.MAX_VALUE.toLong() / 2)
            .toInt()
        if (original.width() >= targetWidth) return Rect(original)
        val center = (original.left.toLong() + original.right.toLong()) / 2L
        val left = (center - targetWidth / 2L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val right = (left.toLong() + targetWidth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Rect(left, original.top, right, original.bottom)
    }

    private fun installAodClockMaterialLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val companionClass = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")
            val method = companionClass.declaredMethods.firstOrNull {
                it.name == "computeSupportedClockEffect" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: error("computeSupportedClockEffect was not found")
            method.isAccessible = true
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-editor-clock-material-limit")
                .intercept { chain ->
                    val effect = chain.getArg(1) as? Int
                    if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                        (effect == CLOCK_EFFECT_GLASS || effect == CLOCK_EFFECT_OVERLAY)
                    ) effect else chain.proceed()
                }

            val disableGlass = companionClass.declaredMethods.firstOrNull {
                it.name == "shouldDisableGlassEffect" && it.parameterCount == 2
            }
            disableGlass?.let { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-editor-glass-selection-limit")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                            chain.getArg(0) == CLOCK_EFFECT_GLASS
                        ) false else chain.proceed()
                    }
            }

            val filterClass = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")
            val filterMethod = filterClass.getDeclaredMethod("getFILTER_SUPPORT_INFO").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val support = filterMethod.invoke(null) as? MutableMap<Int, Boolean>
            val originalGlass = support?.get(CLOCK_EFFECT_GLASS)
            val originalOverlay = support?.get(CLOCK_EFFECT_OVERLAY)
            fun syncSupport() {
                if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {
                    support?.set(CLOCK_EFFECT_GLASS, true)
                    support?.set(CLOCK_EFFECT_OVERLAY, true)
                } else {
                    originalGlass?.let { support?.set(CLOCK_EFFECT_GLASS, it) }
                    originalOverlay?.let { support?.set(CLOCK_EFFECT_OVERLAY, it) }
                }
            }
            syncSupport()
            hook(filterMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-editor-clock-filter-support")
                .intercept { chain ->
                    syncSupport()
                    chain.proceed()
                }

            val clockViewClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.BaseClockView")
            clockViewClass.declaredMethods.firstOrNull {
                it.name == "effectDisable" && it.parameterCount == 1
            }?.let { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-editor-clock-effect-selection")
                    .intercept { chain ->
                        val filter = chain.getArg(0)
                        val effect = runCatching { filter?.javaClass?.getMethod("getFilterId")?.invoke(filter) as? Int }.getOrNull()
                        if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                            (effect == CLOCK_EFFECT_GLASS || effect == CLOCK_EFFECT_OVERLAY)
                        ) false else chain.proceed()
                    }
            }

            val apiClass = classLoader.loadClass("com.miui.keyguard.editor.data.template.TemplateApiImpl")
            val depthMethod = apiClass.declaredMethods.firstOrNull {
                it.name == "processDepthVideo" && it.parameterCount == 3
            } ?: error("processDepthVideo was not found")
            depthMethod.isAccessible = true
            hook(depthMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-depth-video-clock-material-limit")
                .intercept { chain ->
                    val config = chain.getArg(2)
                    val clock = runCatching {
                        config?.javaClass?.getMethod("getLockscreenInfo")?.invoke(config)
                            ?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }
                    }.getOrNull()
                    val effect = runCatching { clock?.javaClass?.getMethod("getClockEffect")?.invoke(clock) as? Int }.getOrNull()
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                        (effect == CLOCK_EFFECT_GLASS || effect == CLOCK_EFFECT_OVERLAY)
                    ) {
                        runCatching {
                            clock?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)?.invoke(clock, effect)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not restore AOD depth-video clock material", error)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed AOD editor clock material-limit bypass")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install AOD editor clock material-limit bypass", error)
        }
    }

    private fun installClockMaterialCapabilityHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        aliases: Set<String> = emptySet(),
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val config = classLoader.loadClass("com.miui.clock.utils.DeviceConfig")
            config.declaredMethods.filter {
                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                    it.name in setOf("supportGlassEffect", "supportDiffEffect", "supportDiffEffectAndGradientEffect") + aliases
            }.forEach { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("clock-material-capability-${method.name}")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) true else chain.proceed()
                    }
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Clock material capability hook unavailable", error)
        }
    }

    private fun installThemeManagerEditorClockMaterialHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val companion = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")
            val configClass = classLoader.loadClass("com.miui.keyguard.editor.data.bean.CommonConfig")
            val method = companion.getDeclaredMethod("k", configClass, Int::class.javaPrimitiveType)
            method.isAccessible = true
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("theme-manager-editor-clock-material-limit")
                .intercept { chain ->
                    val effect = chain.getArg(1) as? Int
                    if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                        (effect == CLOCK_EFFECT_GLASS || effect == CLOCK_EFFECT_OVERLAY)
                    ) effect else chain.proceed()
                }
            companion.getDeclaredMethod("f7l8", Int::class.javaPrimitiveType, configClass).also { disable ->
                disable.isAccessible = true
                hook(disable)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("theme-manager-editor-glass-selection-limit")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
                            chain.getArg(0) == CLOCK_EFFECT_GLASS
                        ) false else chain.proceed()
                    }
            }
            val filters = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")
            @Suppress("UNCHECKED_CAST")
            val support = filters.getDeclaredField("fti").apply { isAccessible = true }.get(null) as? MutableMap<Int, Boolean>
            if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {
                support?.set(CLOCK_EFFECT_GLASS, true)
                support?.set(CLOCK_EFFECT_OVERLAY, true)
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "ThemeManager editor clock material hook unavailable", error)
        }
    }

    /**
     * Newer SystemUI builds apply a second width clamp inside computeForScreen after creating
     * the edit rectangle. Restore the requested ClockLayoutInput width in the returned result so
     * both its drawing rect and the parameters consumed by TimeView agree on the unclamped size.
     */
    private fun restoreRequestedClockWidth(result: Any?, contextArg: Any?, input: Any?): Any? {
        if (result == null || input == null) return result
        return runCatching {
            val context = contextArg as? Context ?: return@runCatching result
            val requestedWidthDp = input.javaClass.getDeclaredField("timeWidth")
                .apply { isAccessible = true }
                .getFloat(input)
            val requestedWidth = (requestedWidthDp * context.resources.displayMetrics.density)
                .roundToInt()
                .coerceAtLeast(1)
            val rectField = result.javaClass.getDeclaredField("rect").apply { isAccessible = true }
            val original = rectField.get(result) as? Rect ?: return@runCatching result
            if (requestedWidth <= original.width()) return@runCatching result
            val center = (original.left.toLong() + original.right.toLong()) / 2L
            val left = (center - requestedWidth / 2L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
            val right = (left.toLong() + requestedWidth)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            rectField.set(result, Rect(left, original.top, right, original.bottom))

            val rectParams = result.javaClass.getDeclaredField("rectParams")
                .apply { isAccessible = true }
                .get(result)
            rectParams?.javaClass?.getDeclaredField("timeWidth")?.apply {
                isAccessible = true
                setInt(rectParams, requestedWidth)
            }
            result
        }.getOrElse { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not restore requested lockscreen clock width", error)
            result
        }
    }

    /**
     * ThemeManager deliberately downgrades overlay/glass clocks while applying a dynamic or
     * super wallpaper (TemplateApiImpl.exv8). Restore the user's effect after that conversion
     * so the value reaches SystemUI/AOD unchanged.
     */
    private fun installThemeManagerClockMaterialLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val templateApi = classLoader.loadClass(THEME_MANAGER_TEMPLATE_API_CLASS)
            val applyWallpaperType = templateApi.declaredMethods.firstOrNull {
                it.name == THEME_MANAGER_TEMPLATE_API_METHOD && it.parameterCount == 3
            } ?: error("$THEME_MANAGER_TEMPLATE_API_METHOD was not found")
            applyWallpaperType.isAccessible = true
            hook(applyWallpaperType)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("theme-manager-clock-material-limit")
                .intercept { chain ->
                    if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {
                        return@intercept chain.proceed()
                    }
                    val templateConfig = chain.getArg(2)
                    val clockInfo = runCatching {
                        templateConfig?.javaClass?.getMethod("getLockscreenInfo")?.invoke(templateConfig)
                            ?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }
                    }.getOrNull()
                    val originalEffect = runCatching {
                        clockInfo?.javaClass?.getMethod("getClockEffect")?.invoke(clockInfo) as? Int
                    }.getOrNull()
                    val result = chain.proceed()
                    if (originalEffect == CLOCK_EFFECT_GLASS || originalEffect == CLOCK_EFFECT_OVERLAY) {
                        runCatching {
                            clockInfo?.javaClass?.getMethod(
                                "setClockEffect",
                                Int::class.javaPrimitiveType,
                            )?.invoke(clockInfo, originalEffect)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not restore ThemeManager clock material effect", error)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed ThemeManager clock material-limit bypass")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install ThemeManager clock material-limit bypass", error)
        }
    }



















































    /**
     * Focus effects intentionally restore their own material after the normal row pipeline.
     * The custom-background and Full-AOD variants can therefore bypass the View setter guards.
     * Re-apply the platform normal-row effect after each focus effect has finished.
     */
    private fun installFocusNotificationMaterialEnforcementHooks(
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        var effectHookCount = 0
        FOCUS_NOTIFICATION_EFFECT_CLASSES.forEach { className ->
            runCatching {
                val effectClass = classLoader.loadClass(className)
                val applyMethod = effectClass.declaredMethods
                    .filter { method ->
                        method.name == "apply" && method.parameterCount == 2 &&
                            method.parameterTypes[1] == Context::class.java
                    }
                    .let { methods ->
                        methods.firstOrNull {
                            it.parameterTypes[0].name.contains(EXPANDABLE_NOTIFICATION_ROW_CLASS)
                        } ?: methods.firstOrNull()
                    }
                    ?: return@runCatching
                hook(applyMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("focus-notification-normal-material:${effectClass.simpleName}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val row = chain.getArg(0) as? View
                        val context = chain.getArg(1) as? Context
                        if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&
                            row != null && context != null && isFocusNotificationRow(row)
                        ) {
                            applyNormalNotificationRowEffect(row, context, classLoader, effectClass.simpleName)
                            // Some keyguard transitions finish their effect transaction after
                            // apply() returns. Re-apply on the next frame so that the focus
                            // effect cannot leave its darker keyguard glass behind.
                            row.post {
                                if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&
                                    isFocusNotificationRow(row)
                                ) {
                                    applyNormalNotificationRowEffect(
                                        row,
                                        context,
                                        classLoader,
                                        "${effectClass.simpleName}-post",
                                    )
                                }
                            }
                        }
                        result
                    }
                effectHookCount++
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Focus effect hook unavailable for $className", error)
            }
        }

        runCatching {
            val injectorClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS)
            injectorClass.declaredMethods
                .filter { it.name == "updateFullAodAnimState" }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("focus-notification-normal-material:full-aod-$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val row = readInstanceField(chain.thisObject, "view") as? View
                            if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&
                                row != null && isFocusNotificationRow(row)
                            ) {
                                applyNormalNotificationRowEffect(
                                    row,
                                    row.context,
                                    classLoader,
                                    "full-aod",
                                )
                            }
                            result
                        }
                }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Focus Full-AOD material hook unavailable", error)
        }

        // Media headers use a separate effect family. In particular, the keyguard variant
        // installs notification_glass_params_on_keyguard and keyguard-only blend colors, so
        // the row-material enforcement above cannot affect it.
        MEDIA_NOTIFICATION_EFFECT_CLASSES.forEach { className ->
            runCatching {
                val effectClass = classLoader.loadClass(className)
                val applyMethod = effectClass.declaredMethods.firstOrNull {
                    it.name == "apply" && it.parameterCount == 2 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[1])
                } ?: return@runCatching
                hook(applyMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("media-notification-normal-material:${effectClass.simpleName}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (mediaMaterialApplying.get() != true &&
                            preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)
                        ) {
                            val header = chain.getArg(0) as? View
                            val context = chain.getArg(1) as? Context
                            if (header != null && context != null && isMediaNotificationView(header)) {
                                applyNormalMediaNotificationEffect(
                                    header,
                                    context,
                                    classLoader,
                                    effectClass.simpleName,
                                )
                                header.post {
                                    if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&
                                        isMediaNotificationView(header)
                                    ) {
                                        applyNormalMediaNotificationEffect(
                                            header,
                                            context,
                                            classLoader,
                                            "${effectClass.simpleName}-post",
                                        )
                                    }
                                }
                            }
                        }
                        result
                    }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Media effect hook unavailable for $className", error)
            }
        }

        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed focus notification normal-material enforcement ($effectHookCount effects)")
    }

    private fun applyNormalMediaNotificationEffect(
        header: View,
        context: Context,
        classLoader: ClassLoader,
        source: String,
    ) {
        if (mediaMaterialApplying.get() == true) return
        mediaMaterialApplying.set(true)
        runCatching {
            val effectClass = classLoader.loadClass(MEDIA_NOTIFICATION_GLASS_EFFECT_CLASS)
            val instance = effectClass.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                ?: effectClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
                ?: return@runCatching
            val apply = effectClass.methods.firstOrNull {
                it.name == "apply" && it.parameterCount == 2
            } ?: return@runCatching
            apply.invoke(instance, header, context)
            val hit = "$source:${header.javaClass.name}"
            if (focusMaterialEnforcementHits.add("media:$hit")) {
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Re-applied normal media notification glass ($source)")
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not re-apply normal media notification glass", error)
        }.also {
            mediaMaterialApplying.remove()
        }
    }

    private fun applyNormalNotificationRowEffect(
        row: View,
        context: Context,
        classLoader: ClassLoader,
        source: String,
    ) {
        runCatching {
            val effectClass = classLoader.loadClass(NOTIFICATION_ROW_GLASS_EFFECT_CLASS)
            val instance = effectClass.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                ?: effectClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
                ?: return@runCatching
            val apply = effectClass.methods.firstOrNull {
                it.name == "apply" && it.parameterCount == 2
            } ?: return@runCatching
            apply.invoke(instance, row, context)
            val hit = "$source:${row.javaClass.name}"
            if (focusMaterialEnforcementHits.add(hit)) {
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Re-applied normal notification glass after focus effect ($source)")
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not re-apply normal notification glass after focus effect", error)
        }
    }

    private fun isFocusNotificationRow(row: View): Boolean {
        return runCatching {
            val injector = row.javaClass.methods.firstOrNull {
                it.name == "getInjector" && it.parameterCount == 0
            }?.invoke(row)
            val focusMethod = injector?.javaClass?.methods?.firstOrNull {
                it.name == "isFocusNotification" && it.parameterCount == 0
            }
            (focusMethod?.invoke(injector) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    /**
     * Focus notifications with a custom background (for example the flashlight entry) can
     * re-install notification_focus_item_bg during full-AOD updates.  That drawable is opaque
     * in dark mode and bypasses the normal View.setBackground hook, so keep this path transparent
     * while the notification-material unification switch is enabled.
     */
    private fun installFocusNotificationBackgroundHook(
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        runCatching {
            val backgroundClass = classLoader.loadClass(NOTIFICATION_BACKGROUND_VIEW_CLASS)
            // The flashlight focus notification uses the custom-background path.  On some
            // SystemUI builds setCustomBackground is inherited from NotificationBackgroundView's
            // parent, so declaredMethods alone misses it and the stock opaque drawable wins.
            (backgroundClass.methods.asSequence() + backgroundClass.declaredMethods.asSequence())
                .distinctBy { method ->
                    method.name to method.parameterTypes.map { it.name }
                }
                .filter { method ->
                    method.name == "setCustomBackground" && method.parameterCount == 1 &&
                        (method.parameterTypes[0] == Drawable::class.java ||
                            method.parameterTypes[0] == Int::class.javaPrimitiveType)
                }
                .forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("focus-notification-transparent-background-$index")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            val unify = preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)
                            val isFocus = unify && view != null &&
                                isNotificationRowBackground(view) &&
                                notificationTypeFor(view) == NotificationMaterialType.FOCUS
                            if (!isFocus) {
                                chain.proceed()
                            } else if (method.parameterTypes[0] == Drawable::class.java) {
                                val transparent = view.resources.getIdentifier(
                                    "notification_heads_up_transparent_bg",
                                    "drawable",
                                    SYSTEM_UI,
                                )
                                if (transparent != 0) {
                                    chain.proceedWith(
                                        chain.thisObject,
                                        arrayOf(view.resources.getDrawable(transparent, null)),
                                    )
                                } else {
                                    chain.proceed()
                                }
                            } else {
                                val transparent = view.resources.getIdentifier(
                                    "notification_heads_up_transparent_bg",
                                    "drawable",
                                    SYSTEM_UI,
                                )
                                if (transparent != 0) {
                                    chain.proceedWith(chain.thisObject, arrayOf(transparent))
                                } else {
                                    chain.proceed()
                                }
                            }
                        }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed transparent custom-background guard for focus notifications")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install focus notification custom-background guard", error)
        }
    }

    private fun notificationTuningFor(view: View, preferences: SharedPreferences): GlassTuning? {
        if (!isNotificationRowBackground(view) || isHeadsUpNotificationSurface(view)) return null
        val onKeyguard = notificationOnKeyguard(view)
        val type = notificationTypeFor(view)
        val contextIsLockscreen = !preferences.getBoolean(KEY_NOTIFICATION_CONTEXT_UNIFIED, true) && onKeyguard
        val typeIsSeparate = !preferences.getBoolean(KEY_NOTIFICATION_TYPE_UNIFIED, true)
        val materialIsUnified = preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)
        return when {
            !typeIsSeparate || (materialIsUnified && type != NotificationMaterialType.NORMAL) -> if (contextIsLockscreen) {
                glassTuning(preferences, KEY_LOCKSCREEN_NORMAL)
            } else {
                glassTuning(preferences, KEY_NOTIFICATION_CENTER_NORMAL)
            }
            contextIsLockscreen && type == NotificationMaterialType.MEDIA ->
                glassTuning(preferences, KEY_LOCKSCREEN_MEDIA)
            contextIsLockscreen && type == NotificationMaterialType.FOCUS ->
                glassTuning(preferences, KEY_LOCKSCREEN_FOCUS)
            !contextIsLockscreen && type == NotificationMaterialType.MEDIA ->
                glassTuning(preferences, KEY_NOTIFICATION_CENTER_MEDIA)
            !contextIsLockscreen && type == NotificationMaterialType.FOCUS ->
                glassTuning(preferences, KEY_NOTIFICATION_CENTER_FOCUS)
            contextIsLockscreen -> glassTuning(preferences, KEY_LOCKSCREEN_NORMAL)
            else -> glassTuning(preferences, KEY_NOTIFICATION_CENTER_NORMAL)
        }
    }

    /**
     * Mirrors hyperos4-glass-blur-main: the material setter belongs to View,
     * therefore its owner has to be identified from the SystemUI call stack,
     * not from the anonymous child view receiving the setter call.
     */
    private fun materialTuningFor(view: View, preferences: SharedPreferences): GlassTuning? = when {
        isHeadsUpNotificationSurface(view) -> null
        isNotificationRowBackground(view) &&
            (!isMediaNotificationView(view) || preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)) ->
            notificationTuningFor(view, preferences)
        isControlCenterCall() -> controlCenterTuningFor(view, preferences)
        else -> null
    }

    private fun notificationTypeFor(background: View): NotificationMaterialType {
        val row = generateSequence(background.parent) { it.parent }
            .filterIsInstance<View>()
            .firstOrNull { it.javaClass.name.contains("ExpandableNotificationRow") }
            ?: return NotificationMaterialType.NORMAL
        return runCatching {
            val entry = row.javaClass.methods.firstOrNull {
                it.name == "getEntry" && it.parameterCount == 0
            }?.invoke(row) ?: return@runCatching NotificationMaterialType.NORMAL
            val sbn = readInstanceField(entry, "mSbn")
                ?: entry.javaClass.methods.firstOrNull {
                    it.name == "getSbn" && it.parameterCount == 0
                }?.invoke(entry)
                ?: return@runCatching NotificationMaterialType.NORMAL
            val isFocus = (readInstanceField(sbn, "mIsFocusNotification") as? Boolean)
                ?: (sbn.javaClass.methods.firstOrNull {
                    it.name == "isFocusNotification" && it.parameterCount == 0
                }?.invoke(sbn) as? Boolean)
                ?: false
            if (isFocus) {
                NotificationMaterialType.FOCUS
            } else {
                val notification = sbn.javaClass.methods.firstOrNull {
                    it.name == "getNotification" && it.parameterCount == 0
                }?.invoke(sbn) ?: return@runCatching NotificationMaterialType.NORMAL
                if (notification.javaClass.methods.firstOrNull {
                        it.name == "isMediaNotification" && it.parameterCount == 0
                    }?.invoke(notification) as? Boolean == true
                ) {
                    NotificationMaterialType.MEDIA
                } else {
                    NotificationMaterialType.NORMAL
                }
            }
        }.getOrDefault(NotificationMaterialType.NORMAL)
    }

    private fun controlCenterTuningFor(view: View, preferences: SharedPreferences): GlassTuning? = when {
        // The material setter runs on anonymous child views.  The owning panel type is
        // present in the call stack, which is the same identification route used by the
        // verified HyperOS 4 reference module.
        stackContainsClass(SLIDER_VIEW_HOLDER_CLASS) ||
            stackContainsClass("ToggleSlider") ||
            isControlCenterSliderPart(view) -> glassTuning(preferences, KEY_CONTROL_CENTER_SLIDER)
        stackContainsClass(TOP_BUTTONS_CLASS) ||
            view.javaClass.name == TOP_BUTTONS_CLASS ||
            hasAncestorClass(view, "QSCardItemView") -> glassTuning(preferences, KEY_CONTROL_CENTER_BUTTON)
        else -> null
    }

    private fun logControlCenterMaterialHit(view: View?, method: String) {
        val type = when {
            stackContainsClass(SLIDER_VIEW_HOLDER_CLASS) ||
                stackContainsClass("ToggleSlider") ||
                stackContainsClass("ToggleSlider") -> "slider"
            stackContainsClass(TOP_BUTTONS_CLASS) -> "button"
            else -> "fallback"
        }
        if (controlCenterMaterialHits.add("$type:$method")) {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Control-center $type material matched $method on ${view?.javaClass?.name}")
        }
    }

    private fun shouldUseNormalNotificationMaterial(
        view: View?,
        preferences: SharedPreferences,
    ): Boolean {
        if (view == null || !preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) ||
            isHeadsUpNotificationSurface(view)) return false
        return isMediaNotificationView(view) ||
            (isNotificationRowBackground(view) && notificationTypeFor(view) != NotificationMaterialType.NORMAL)
    }

    private fun normalNotificationGlassParams(view: View): FloatArray? {
        val resources = view.resources
        // NotificationRowGlassEffect uses notification_glass_params_normal on both the
        // notification shade and keyguard.  The keyguard-specific array belongs to the
        // separate focus effect; using it here makes a unified focus row darker than a
        // normal row while the device is locked.
        val resourceName = NORMAL_NOTIFICATION_GLASS_PARAMS_ARRAY
        synchronized(normalNotificationGlassParamsCache) {
            normalNotificationGlassParamsCache[resources]?.get(resourceName)?.let { return it.copyOf() }
        }
        val params = runCatching {
            val localId = resources.getIdentifier(
                resourceName,
                "array",
                SYSTEM_UI,
            )
            // Volume views belong to MiuiSystemUIPlugin. Its Resources cannot resolve
            // SystemUI's arrays even when getIdentifier receives the SystemUI package.
            // Falling back to the incoming collapsed token retains its black inner tint.
            val recipeResources = if (localId != 0) resources else
                view.context.createPackageContext(SYSTEM_UI, 0).resources
            val resourceId = if (localId != 0) localId else
                recipeResources.getIdentifier(resourceName, "array", SYSTEM_UI)
            if (resourceId == 0) return@runCatching null
            recipeResources.getStringArray(resourceId)
                .map { it.toFloatOrNull() ?: return@runCatching null }
                .toFloatArray()
                .takeIf { it.size >= MIN_GLASS_PARAMS_SIZE }
        }.getOrNull() ?: return null
        synchronized(normalNotificationGlassParamsCache) {
            normalNotificationGlassParamsCache.getOrPut(resources) { mutableMapOf() }[resourceName] = params.copyOf()
        }
        return params
    }

    private fun normalNotificationBlendColors(view: View?, preferences: SharedPreferences): IntArray? {
        if (view == null || !preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) ||
            isHeadsUpNotificationSurface(view)) return null
        val isNotification = isNotificationRowBackground(view) || isMediaNotificationView(view)
        if (!isNotification || !isNotificationMaterialCall()) return null
        // The switch makes focus and MEDIA notifications use the normal notification recipe.
        // Ordinary notifications already use that recipe and must keep their original blend
        // points; replacing them here can apply the blend layer a second time and make the row
        // appear intermittently over-bright.
        if (notificationTypeFor(view) == NotificationMaterialType.NORMAL) return null
        // NotificationRowBlurEffect always seeds the normal row with the shade blend points,
        // including when the row is rendered on keyguard.  Keep unified focus/media rows on
        // that same recipe instead of switching to the darker keyguard blend colors.
        val suffix = "shade"
        return runCatching {
            val resources = view.resources
            intArrayOf(
                resources.getColorByName("notification_element_blend_${suffix}_color_1"),
                resources.getIntegerByName("notification_element_blend_${suffix}_mode_1"),
                resources.getColorByName("notification_element_blend_${suffix}_color_2"),
                resources.getIntegerByName("notification_element_blend_${suffix}_mode_2"),
            )
        }.getOrNull()
    }

    private fun normalNotificationBlendPoints(
        view: View?,
        preferences: SharedPreferences,
    ): ArrayList<Point>? = normalNotificationBlendColors(view, preferences)?.let { colors ->
        ArrayList<Point>(colors.size / 2).also { points ->
            colors.asList().chunked(2).forEach { pair ->
                if (pair.size == 2) points += Point(pair[0], pair[1])
            }
        }
    }

    private fun Resources.getColorByName(name: String): Int =
        getColor(getIdentifier(name, "color", SYSTEM_UI), null)

    private fun Resources.getIntegerByName(name: String): Int =
        getInteger(getIdentifier(name, "integer", SYSTEM_UI))

    private fun isNotificationMaterialCall(): Boolean {
        val stack = Thread.currentThread().stackTrace
        return stack.any {
            it.className.startsWith("com.android.systemui.statusbar.notification.") ||
                it.className.startsWith("com.miui.systemui.statusbar.notification.")
        }
    }

    private fun notificationOnKeyguard(view: View): Boolean {
        // The NSSL status and expanded height are the lockscreen/shade ownership boundary. The
        // same MiuiMediaHeaderView instance is reused by both surfaces, so never mutate it for
        // the notification shade while a pull-down is in progress.
        val stack = generateSequence<View>(view) { it.parent as? View }
            .firstOrNull { it.javaClass.name.contains("NotificationStackScrollLayout") }
        val stackState = generateSequence<View>(view) { it.parent as? View }
            .mapNotNull { candidate ->
                if (!candidate.javaClass.name.contains("NotificationStackScrollLayout")) return@mapNotNull null
                runCatching {
                    (candidate.javaClass.methods.firstOrNull {
                        it.name == "onKeyguard" && it.parameterCount == 0
                    }?.invoke(candidate) as? Boolean)
                        ?: ((readInstanceField(candidate, "mStatusBarState") as? Number)?.toInt() == 1)
                }.getOrNull()
            }
            .firstOrNull()
        if (stackState != null) {
            // mExpandedHeight is non-zero on the lockscreen before the first shade frame, so it
            // cannot identify the notification center. mIsExpanded is the NSSL ownership flag
            // and remains false for the lockscreen island's collapsed media header.
            val shadeExpanded = (stack?.let { readInstanceField(it, "mIsExpanded") } as? Boolean) == true
            if (shadeExpanded) return false
            // During lockscreen-island creation NSSL can still report shade state 0 even though
            // KeyguardManager already reports the device locked. Keep the keyguard signal alive
            // until the shade explicitly owns the shared header.
            return stackState || lockscreenMediaKeyguardShowing || isLockscreenMediaView(view)
        }
        val state = generateSequence<View>(view) { it.parent as? View }
            .mapNotNull { candidate ->
                runCatching {
                    readInstanceField(candidate, "mOnKeyguard") as? Boolean
                        ?: (candidate.javaClass.methods.firstOrNull {
                            (it.name == "isOnKeyguard" || it.name == "onKeyguard") && it.parameterCount == 0
                        }?.invoke(candidate) as? Boolean)
                }.getOrNull()
            }
            .firstOrNull()
        if (state != null) return state
        // Some vendor builds do not expose NSSL's onKeyguard/status fields. The media controller
        // callback is the remaining lockscreen signal; the expanded-shade guard above has already
        // ruled out the shared notification-center instance before this fallback is reached.
        return (lockscreenMediaKeyguardShowing || isLockscreenMediaView(view)) &&
            isMediaNotificationView(view) && !isExpandedNotificationShade(view)
    }

    /** The media header is shared by keyguard and the expanded notification shade. */
    private fun isExpandedNotificationShade(view: View): Boolean {
        val stack = generateSequence<View>(view) { it.parent as? View }
            .firstOrNull { it.javaClass.name.contains("NotificationStackScrollLayout") }
            ?: return false
        return (readInstanceField(stack, "mIsExpanded") as? Boolean) == true ||
            (readInstanceField(stack, "mExpandedHeight") as? Number)?.toFloat()?.let { it > 1f } == true &&
            (readInstanceField(stack, "mStatusBarState") as? Number)?.toInt() != 1
    }

    /**
     * Header attachment can precede the vendor keyguard callback by one traversal. Fall back to
     * KeyguardManager only when the notification shade is definitely not expanded, so entering
     * the compact island hides the card in the current lockscreen frame instead of on relock.
     */
    private fun mediaHeaderOnActiveKeyguard(header: View): Boolean {
        if (isExpandedNotificationShade(header)) return false
        // The media controller's state flow often still says "not on keyguard" during the
        // first frame that creates the lockscreen island.  The attached clock container is the
        // same authoritative lockscreen signal used by the imported Main.java and is already
        // visible at that point.  Prefer it after ruling out an expanded shade, so MINI_PLAYER
        // can hide the media header in this frame instead of waiting for a later relock.
        val clockShowing = activeLockscreenClockContainer?.get()?.let { clock ->
            clock.isAttachedToWindow && clock.isShown
        } == true
        if (clockShowing) return true
        if (notificationOnKeyguard(header)) return true
        return runCatching {
            header.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)
    }

    private fun hasCustomizedNotificationTuning(view: View, preferences: SharedPreferences): Boolean =
        notificationTuningFor(view, preferences)?.let { it != GlassTuning() } == true

    private fun isNotificationRowBackground(view: View): Boolean {
        if (!view.javaClass.name.contains("NotificationBackgroundView")) return false
        // Focus notifications such as the flashlight entry use a third custom background
        // resource instead of backgroundNormal/backgroundDimmed.  It is still the row's
        // NotificationBackgroundView and must participate in the unified material pipeline.
        return true
    }

    private fun isMediaNotificationView(view: View): Boolean {
        fun matches(candidate: View): Boolean {
            val name = candidate.javaClass.name.lowercase(java.util.Locale.ROOT)
            return name.contains("miuimedia") ||
                name.contains("mediaheader") ||
                name.contains("mediarow") ||
                name.contains("mediacontrol") ||
                name.contains("mediaholder")
        }
        if (matches(view)) return true
        return generateSequence(view.parent) { it.parent }
            .filterIsInstance<View>()
            .any(::matches)
    }

    private fun hasAncestorClass(view: View, classNamePart: String): Boolean =
        generateSequence(view.parent) { it.parent }
            .filterIsInstance<View>()
            .any { it.javaClass.name.contains(classNamePart) }

    private fun readInstanceField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun shadePanelTuning(preferences: SharedPreferences): GlassTuning? = when {
        stackContainsClass("controlcenter") -> glassTuning(preferences, KEY_CONTROL_CENTER_BACKGROUND)
        stackContainsClass("notification") || stackContainsClass("ShadeBlendBlurController") ->
            glassTuning(preferences, KEY_NOTIFICATION_CENTER_BACKGROUND)
        else -> null
    }

    private fun isControlCenterCall(): Boolean = Thread.currentThread().stackTrace.any {
        it.className.startsWith("miui.systemui.controlcenter.")
    }

    private fun isNotificationCenterCall(): Boolean {
        val stack = Thread.currentThread().stackTrace
        if (stack.any { it.className.startsWith("miui.systemui.controlcenter.") }) return false
        return stack.any {
            it.className.startsWith("com.android.systemui.statusbar.notification.") ||
                it.className.startsWith("com.android.systemui.shade.") ||
                it.className.startsWith("com.miui.systemui.shade.")
        }
    }

    private fun isShadeBlurProviderCall(): Boolean = Thread.currentThread().stackTrace.any {
        it.className.startsWith("com.miui.systemui.shade.blur.ShadeBlendBlurController\$BlurProvider")
    }

    /**
     * The shade blur controller invokes the same View material APIs for its real background
     * surfaces and for child effects (sliders use a mirror blur provider).  Only the former
     * should receive the configurable background recipe; changing the child surfaces a second
     * time makes the underlying app image appear duplicated.
     */
    private fun isShadeBackgroundView(view: View?): Boolean {
        if (view == null || isNotificationRowBackground(view)) return false
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (idName in SHADE_BACKGROUND_IDS) return true
        val className = view.javaClass.name
        if (className.contains("mirrorBlurProvider", ignoreCase = true) ||
            className.contains("MirrorBlur", ignoreCase = true) ||
            idName in SLIDER_PART_IDS ||
            idName in setOf("volume_column_slider", "volume_column_slider_bg_glass", "volume_column_slider_bg_blend")
        ) return false
        // Never replace the background of the shade window or notification panel itself.
        // Those containers host the notification stack; an opaque wallpaper drawable there
        // can cover the stack during keyguard/shade transitions. Only dedicated background
        // surfaces are safe targets.
        return className.contains("ShadeBackground") ||
            className.contains("NotificationPanelBackground") ||
            className.contains("ControlCenterBackground")
    }

    private fun isShadePanelBackgroundCall(view: View? = null): Boolean =
        isShadeBlurProviderCall() || isControlCenterCall() || isNotificationCenterCall()

    private fun stackContainsClass(classNamePart: String): Boolean =
        Thread.currentThread().stackTrace.any { it.className.contains(classNamePart, ignoreCase = true) }

    private fun stackContains(classNamePart: String, methodNamePart: String): Boolean =
        Thread.currentThread().stackTrace.any {
            it.className.contains(classNamePart) && it.methodName.contains(methodNamePart)
        }

    private fun glassTuning(preferences: SharedPreferences, key: String): GlassTuning {
        val parts = preferences.getString(key, null)?.split('|') ?: return GlassTuning()
        return GlassTuning(
            blurPercent = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 200) ?: 100,
            opacity = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            color = parts.getOrNull(2)?.toLongOrNull()?.toInt() ?: Color.WHITE,
            customColorEnabled = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun applyGlassTuning(original: FloatArray, tuning: GlassTuning): FloatArray {
        val tuned = original.clone()
        tuned[GLASS_ALPHA_INDEX] = (tuned[GLASS_ALPHA_INDEX] * tuning.opacity / 100f).coerceAtLeast(0f)
        if (tuning.customColorEnabled) {
            tuned[GLASS_TINT_RED_INDEX] = Color.red(tuning.color) / 255f
            tuned[GLASS_TINT_GREEN_INDEX] = Color.green(tuning.color) / 255f
            tuned[GLASS_TINT_BLUE_INDEX] = Color.blue(tuning.color) / 255f
        }
        return tuned
    }

    private fun applyBlendColorTuning(original: ArrayList<*>, tuning: GlassTuning): ArrayList<Point> =
        ArrayList<Point>(original.size).also { tuned ->
            original.filterIsInstance<Point>().forEach { point ->
                val alpha = (Color.alpha(point.x) * tuning.opacity / 100f).toInt().coerceIn(0, 255)
                val color = if (tuning.customColorEnabled) tuning.color else point.x
                tuned += Point(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)), point.y)
            }
        }

    private fun scaleBlur(radius: Int, tuning: GlassTuning): Int =
        (radius * tuning.blurPercent / 100f).toInt().coerceIn(0, MAX_GLASS_BLUR_RADIUS)

    private fun installDepthEffectHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            // Loading a class does not run its static initializer. Hook the constructor before
            // DepthAvoidEvaluator creates IMAGE_THRESHOLD in <clinit>.
            val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)
            val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)
            val constructor = thresholdClass.getDeclaredConstructor(Double::class.javaPrimitiveType)
            hook(constructor)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("depth-image-threshold")
                .intercept { chain ->
                    val original = chain.getArg(0) as? Double
                    if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                        original == DEFAULT_DEPTH_IMAGE_THRESHOLD
                    ) {
                        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Replacing DepthAvoidEvaluator image threshold: 0.2 -> 1.0")
                        chain.proceed(arrayOf(UNLIMITED_DEPTH_IMAGE_THRESHOLD))
                    } else {
                        chain.proceed()
                    }
                }
            hookClassInitializer(evaluatorClass)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("depth-image-threshold-verification")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                        runCatching {
                            val threshold = evaluatorClass
                                .getDeclaredField(IMAGE_THRESHOLD_FIELD)
                                .get(null)
                            thresholdClass
                                .getDeclaredField(THRESHOLD_RATE_FIELD)
                                .setDouble(threshold, UNLIMITED_DEPTH_IMAGE_THRESHOLD)
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Verified DepthAvoidEvaluator image threshold: 1.0")
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not verify depth image threshold", error)
                        }
                    }
                    result
                }
            installDepthAvoidanceBypass(classLoader, preferences)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed depth limitation hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install depth image threshold hook", error)
        }
    }

    private fun installDepthAvoidanceBypass(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val controllerClass = classLoader.loadClass(HIERARCHY_AVOID_CONTROLLER_CLASS)
        hook(controllerClass.getMethod("isHierarchyEnable"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("depth-time-overlap-result")
            .intercept { chain ->
                val result = chain.proceed()
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                    isUserHierarchyEnabled(chain.thisObject, controllerClass)
                ) true else result
            }

        hook(
            controllerClass.getMethod(
                "onHierarchyEnableChange",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
        )
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("depth-time-overlap-state")
            .intercept { chain ->
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                    isUserHierarchyEnabled(chain.thisObject, controllerClass)
                ) {
                    chain.proceedWith(chain.thisObject, arrayOf(true, chain.getArg(1)))
                } else {
                    chain.proceed()
                }
            }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed time-overlap depth bypass")
    }

    private fun isUserHierarchyEnabled(instance: Any?, controllerClass: Class<*>): Boolean = runCatching {
        controllerClass.getDeclaredField(USER_OPEN_HIERARCHY_FIELD)
            .apply { isAccessible = true }
            .getBoolean(instance)
    }.getOrDefault(false)

    private fun installLockscreenShortcutGlassHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val controllerClass = classLoader.loadClass(MIUI_SHORTCUT_CONTROLLER_CLASS)
            val shortcutMethods = controllerClass.declaredMethods
                .filter { it.name == "addShortcutViews" && it.parameterCount == 1 }
            check(shortcutMethods.isNotEmpty()) { "MiuiShortcutController.addShortcutViews was not found" }
            shortcutMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-shortcut-glass-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val root = chain.getArg(0) as? View ?: return@intercept result
                        runCatching {
                            // Keep the shortcut containers fully laid out even when the user
                            // selects "不显示". In that mode the layer is transparent, so this
                            // preserves geometry without adding a visible shortcut background.
                            installShortcutGlassBackgrounds(root, preferences, classLoader)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not apply lockscreen shortcut glass", error)
                        }
                        runCatching {
                            applyLockscreenShortcutGeometry(root, preferences)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not apply lockscreen shortcut geometry", error)
                        }
                        scheduleLockscreenMiniPlayerInstallation(
                            root = root,
                            shortcutController = chain.thisObject,
                            preferences = preferences,
                            classLoader = classLoader,
                        )
                        scheduleLockscreenWidgetInstallation(
                            root = root,
                            shortcutController = chain.thisObject,
                            preferences = preferences,
                            classLoader = classLoader,
                        )
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed ${shortcutMethods.size} lockscreen shortcut glass hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen shortcut glass hook", error)
        }
    }

    // Package and plugin dispatch stay in the lifecycle owner. Gesture state belongs
    // to LockscreenHooks, including SystemUI-hosted AOD plugin instances.
    private fun installLockscreenShortcutPluginHook(loader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val pluginClass = loader.loadClass("com.android.systemui.shared.plugins.PluginInstance")
            hookDeoptimized(pluginClass.getDeclaredMethod("loadPlugin"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-shortcut-aod-plugin")
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val instance = chain.thisObject
                        if (pluginClass.getMethod("getPackageName").invoke(instance) == AOD) {
                            pluginClass.getMethod("getPlugin").invoke(instance)?.javaClass?.classLoader?.let {
                                lockscreenHooks.installShortcutGestureHooks(it, preferences)
                            }
                        }
                    }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not bind AOD shortcut plugin", it) }
                    result
                }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install AOD shortcut plugin dispatch", it) }
    }

    /**
     * The lockscreen editor and charge animation both sit above the normal keyguard hierarchy.
     * Their windows retain the shortcut host, so a KeyguardManager-only check briefly exposes
     * injected content while the vendor scene is transitioning.
     */
    private fun installLockscreenWidgetSceneVisibilityHooks(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        // Fingerprint fast unlock calls the vendor mediator before KeyguardManager reports the
        // device unlocked. Hide in that first callback so the widget and its native-clock
        // avoidance transform cannot survive into launcher frames.
        runCatching {
            val mediatorInjector = classLoader.loadClass(
                "com.android.keyguard.injector.KeyguardViewMediatorInjector",
            )
            val goingAwayMethods = mediatorInjector.declaredMethods.filter {
                it.name == "keyguardGoingAway" && it.parameterCount == 0
            }
            check(goingAwayMethods.isNotEmpty()) {
                "KeyguardViewMediatorInjector.keyguardGoingAway was not found"
            }
            goingAwayMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:keyguard-going-away-early-$index")
                    .intercept { chain ->
                        LockscreenWidgetSceneState.setKeyguardGoingAway(true)
                        chain.proceed()
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget early keyguard-exit hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget early keyguard-exit hooks", error)
        }
        runCatching {
            val updateMonitor = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor")
            val goingAwayMethods = updateMonitor.declaredMethods.filter {
                it.name == "setKeyguardGoingAway" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            check(goingAwayMethods.isNotEmpty()) {
                "KeyguardUpdateMonitor.setKeyguardGoingAway was not found"
            }
            goingAwayMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:keyguard-going-away-state-$index")
                    .intercept { chain ->
                        if (chain.getArg(0) == true) {
                            LockscreenWidgetSceneState.setKeyguardGoingAway(true)
                        }
                        chain.proceed()
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget keyguard-exit state hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget keyguard-exit state hooks", error)
        }
        runCatching {
            val stateController = classLoader.loadClass(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
            )
            val keyguardStateMethods = stateController.declaredMethods.filter {
                it.name == "notifyKeyguardState" && it.parameterCount == 2 &&
                    it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }
            }
            check(keyguardStateMethods.isNotEmpty()) {
                "KeyguardStateControllerImpl.notifyKeyguardState was not found"
            }
            keyguardStateMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:keyguard-showing-state-$index")
                    .intercept { chain ->
                        if (chain.getArg(0) == true) {
                            LockscreenWidgetSceneState.setKeyguardGoingAway(false)
                        }
                        chain.proceed()
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget keyguard-showing state hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget keyguard-showing state hooks", error)
        }
        runCatching {
            val statusBarStateClass = classLoader.loadClass(
                "com.android.systemui.statusbar.StatusBarStateControllerImpl",
            )
            val dozingMethods = statusBarStateClass.declaredMethods.filter {
                it.name == "setIsDozing" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            check(dozingMethods.isNotEmpty()) { "StatusBarStateControllerImpl.setIsDozing was not found" }
            dozingMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:aod-scene-$index")
                    .intercept { chain ->
                        LockscreenWidgetSceneState.setAodActive(chain.getArg(0) == true)
                        chain.proceed()
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget AOD visibility hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget AOD visibility hooks", error)
        }
        runCatching {
            val editorClass = classLoader.loadClass(KEYGUARD_EDITOR_HELPER_CLASS)
            val stateMethods = editorClass.declaredMethods.filter {
                it.name == "setEditorState" && it.parameterCount == 1
            }
            check(stateMethods.isNotEmpty()) { "KeyguardEditorHelper.setEditorState was not found" }
            stateMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:editor-scene-$index")
                    .intercept { chain ->
                        val stateName = chain.getArg(0)?.toString()
                        if (stateName != null && stateName != "IDEL") {
                            LockscreenWidgetSceneState.setEditorActive(true)
                        }
                        val result = chain.proceed()
                        if (stateName == "IDEL") {
                            LockscreenWidgetSceneState.setEditorActive(false)
                        }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget editor-scene visibility hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget editor-scene visibility hooks", error)
        }
        runCatching {
            val chargeClass = classLoader.loadClass(MIUI_CHARGE_ANIMATION_VIEW_CLASS)
            val showMethods = chargeClass.declaredMethods.filter {
                it.name == "addChargeView" && it.parameterCount == 0
            }
            val hideMethods = chargeClass.declaredMethods.filter { it.name == "removeChargeView" }
            check(showMethods.isNotEmpty() && hideMethods.isNotEmpty()) {
                "MiuiChargeAnimationView visibility methods were not found"
            }
            showMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:charging-scene-show-$index")
                    .intercept { chain ->
                        LockscreenWidgetSceneState.setChargingActive(true)
                        try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            LockscreenWidgetSceneState.setChargingActive(false)
                            throw error
                        }
                    }
            }
            hideMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:charging-scene-hide-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        LockscreenWidgetSceneState.setChargingActive(false)
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget charging-scene visibility hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget charging-scene visibility hooks", error)
        }
        runCatching {
            val listenerClass = classLoader.loadClass(CONTROL_CENTER_EXPAND_LISTENER_CLASS)
            val stateMethods = listenerClass.declaredMethods.filter {
                it.name == "onExpandStateChanged" && it.parameterCount == 1
            }
            check(stateMethods.isNotEmpty()) { "ControlCenter expand-state listener was not found" }
            stateMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:control-center-scene-$index")
                    .intercept { chain ->
                        val isCollapsed = chain.getArg(0)?.toString() == "COLLAPSED"
                        if (!isCollapsed) {
                            LockscreenWidgetSceneState.setControlCenterActive(true)
                        }
                        val result = chain.proceed()
                        if (isCollapsed) {
                            LockscreenWidgetSceneState.setControlCenterActive(false)
                        }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-widget control-center visibility hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-widget control-center visibility hooks", error)
        }
    }

    private fun installLockscreenPinCircleBackgroundHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val pinViewClass = classLoader.loadClass(KEYGUARD_PIN_VIEW_CLASS)
            val onFinishInflate = pinViewClass.getDeclaredMethod("onFinishInflate")
            hook(onFinishInflate)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-pin-circle-background")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, false)) {
                        val pinView = chain.thisObject as? View
                        pinView?.post {
                            installLockscreenPinCircleBackgrounds(pinView, classLoader, preferences)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen PIN circle-background hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen PIN circle-background hook", error)
        }
    }

    private fun installLockscreenPinCircleBackgrounds(
        root: View,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val keys = ArrayList<View>(10)
        fun visit(view: View) {
            if (view.idName() in LOCKSCREEN_PIN_KEY_IDS) keys += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        keys.forEach { key ->
            key.post {
                val keyGroup = key as? ViewGroup ?: return@post
                val diameter = minOf(key.width, key.height)
                if (diameter <= 0) return@post
                for (index in keyGroup.childCount - 1 downTo 0) {
                    if (keyGroup.getChildAt(index).tag == LOCKSCREEN_PIN_CIRCLE_TAG) {
                        keyGroup.removeViewAt(index)
                    }
                }
                val material = ImageView(key.context).apply {
                    tag = LOCKSCREEN_PIN_CIRCLE_TAG
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    // The backdrop compositor requires drawable content before it registers a view.
                    setImageDrawable(GradientDrawable().apply { setColor(Color.argb(1, 255, 255, 255)) })
                    foreground = RippleDrawable(
                        ColorStateList.valueOf(LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR),
                        null,
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        },
                    )
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(target: View, outline: Outline) {
                            outline.setOval(0, 0, target.width, target.height)
                        }
                    }
                }
                keyGroup.addView(material, 0, ViewGroup.LayoutParams(diameter, diameter))
                // NumPadKey's stock background owns the expanding press animation. Remove it so
                // the material layer's circular foreground ripple is the only visual feedback.
                key.background = null
                fun placeMaterial() {
                    val size = minOf(key.width, key.height)
                    if (size <= 0) return
                    material.layoutParams = material.layoutParams.apply {
                        width = size
                        height = size
                    }
                    val left = (key.width - size) / 2
                    val top = (key.height - size) / 2
                    material.layout(left, top, left + size, top + size)
                    material.invalidateOutline()
                }
                key.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeMaterial() }
                placeMaterial()
                key.setOnTouchListener { _, event ->
                    material.isPressed = event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_MOVE
                    false
                }
                configureLockscreenPinLabels(keyGroup)
                runCatching {
                    applyLegacyBackdropMaterial(
                        view = material,
                        opacity = DEFAULT_ADVANCED_MATERIAL_OPACITY,
                        blurRadius = DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS,
                        color = DEFAULT_ADVANCED_MATERIAL_COLOR,
                        showHighlight = true,
                    )
                    applySystemGlassMaterial(
                        view = material,
                        classLoader = classLoader,
                        blurRadius = DEFAULT_SOFT_GLASS_BLUR_RADIUS,
                        luminance = DEFAULT_SOFT_GLASS_LUMINANCE,
                    )
                }.onFailure { error ->
                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize PIN key material", error)
                }
            }
        }
        applyLockscreenPinRowSpacing(root, preferences)
        btm.m.os4.systemuihook.HookModuleLogger.log(this,
            Log.INFO,
            TAG,
            "Applied PIN material to ${keys.size} key(s), rowSpacing=" +
                "${preferences.getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f)}dp",
        )
    }

    private fun configureLockscreenPinLabels(key: ViewGroup) {
        fun visit(view: View) {
            if (view is TextView && view.idName() == "klondike_text") {
                view.ellipsize = null
                view.isSingleLine = false
                view.maxLines = 1
                view.setHorizontallyScrolling(false)
                view.textScaleX = 0.86f
                view.includeFontPadding = false
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(key)
    }

    private fun applyLockscreenPinRowSpacing(root: View, preferences: SharedPreferences) {
        val rows = LinkedHashMap<String, View>(4)
        fun visit(view: View) {
            view.idName()?.takeIf { it in LOCKSCREEN_PIN_ROW_IDS }?.let { rows[it] = view }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        val spacingPx = preferences.getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f)
            .coerceIn(-24f, 32f) * root.resources.displayMetrics.density
        LOCKSCREEN_PIN_ROW_IDS.forEachIndexed { index, id ->
            // Keep row4 fixed so positive spacing expands upward, away from the fingerprint area.
            rows[id]?.translationY = -spacingPx * (LOCKSCREEN_PIN_ROW_IDS.lastIndex - index)
        }
    }

    private fun installLockscreenMiniPlayer(
        root: View,
        shortcutController: Any?,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val shortcuts = findShortcutContainers(root)
        val legacyShortcuts = if (shortcuts.size >= 2) {
            val left = shortcuts.firstOrNull { it.idName() == "shortcut_view_left_layout" } ?: shortcuts[0]
            val right = shortcuts.firstOrNull { it.idName() == "shortcut_view_right_layout" } ?: shortcuts[1]
            left to right
        } else {
            null
        }
        // The plugin may recreate its shortcut content after addShortcutViews returns. Ask the
        // controller for the actual views rather than relying only on the plugin's layout IDs.
        val controllerShortcuts = resolveLockscreenShortcutViews(shortcutController)
        val (left, right) = controllerShortcuts ?: legacyShortcuts ?: return
        if (left === right) return
        val parent = commonShortcutParent(left, right) ?: return
        val old = synchronized(lockscreenMiniPlayerControllers) {
            lockscreenMiniPlayerControllers[parent]
        }
        if (!preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)) {
            old?.destroy()
            lockscreenMiniPlayerControllers.remove(parent)
            return
        }
        if (old == null) {
            val controller = LockscreenMiniPlayerController(
                host = parent,
                leftShortcut = left,
                rightShortcut = right,
                enabled = { preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) },
                musicLockscreenEnabled = {
                    preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)
                },
                lyricsEnabled = {
                    preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, false)
                },
                mediaNotificationMode = { lockscreenMediaNotificationMode(preferences) },
                appearance = { miniPlayerAppearance(preferences) },
                applyPlatformMaterial = { view, appearance ->
                    runCatching {
                        applyMiniPlayerMaterial(view, appearance, classLoader)
                    }.onFailure { error ->
                        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize mini player material", error)
                    }
                },
                onImmersiveRequest = {
                    synchronized(lockscreenMiniPlayerControllers) {
                        lockscreenMiniPlayerControllers[parent]?.requestImmersiveForCurrentMedia()
                    }
                },
            )
            synchronized(lockscreenMiniPlayerControllers) {
                lockscreenMiniPlayerControllers[parent] = controller
            }
        }
    }

    private fun scheduleLockscreenMiniPlayerInstallation(
        root: View,
        shortcutController: Any?,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        fun install() {
            runCatching {
                installLockscreenMiniPlayer(root, shortcutController, preferences, classLoader)
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not apply lockscreen mini player", error)
            }
        }
        install()
        // 18.2.2.2.0 can finish rebuilding its shortcut content after the controller method
        // returns. Retry after attachment and after the next layout pass without retaining a
        // hierarchy listener for the lifetime of SystemUI.
        root.post(::install)
        root.postDelayed(::install, LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS)
    }

    private fun installLockscreenWidget(
        root: View,
        shortcutController: Any?,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val shortcuts = findShortcutContainers(root)
        val legacyShortcuts = if (shortcuts.size >= 2) {
            val left = shortcuts.firstOrNull { it.idName() == "shortcut_view_left_layout" } ?: shortcuts[0]
            val right = shortcuts.firstOrNull { it.idName() == "shortcut_view_right_layout" } ?: shortcuts[1]
            left to right
        } else null
        val controllerShortcuts = resolveLockscreenShortcutViews(shortcutController)
        val (left, right) = controllerShortcuts ?: legacyShortcuts ?: return
        if (left === right) return
        val shortcutParent = commonShortcutParent(left, right) ?: return
        // MiuiShortcutController.addShortcutViews() clears the shortcut subtree with
        // removeAllViews(). Attach to the stable full-screen root and position from the
        // shortcut centers, so the widget survives every vendor rebuild.
        val parent = (root.rootView as? ViewGroup) ?: shortcutParent
        parent.clipChildren = false
        parent.clipToPadding = false
        val old = synchronized(lockscreenWidgetControllers) { lockscreenWidgetControllers[parent] }
        if (!preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, false)) {
            old?.destroy()
            lockscreenWidgetControllers.remove(parent)
            return
        }
        if (old == null) {
            synchronized(lockscreenWidgetControllers) {
                lockscreenWidgetControllers[parent] = LockscreenWidgetController(
                    host = parent,
                    leftShortcut = left,
                    rightShortcut = right,
                    preferences = preferences,
                    classLoader = classLoader,
                    applyBatteryTrackMaterial = { view ->
                        runCatching {
                            applyLockscreenWidgetBatteryMaterial(view, preferences, classLoader)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize lockscreen battery material", error)
                        }
                    },
                    applyShortcutSurfaceMaterial = { view ->
                        runCatching {
                            applyLockscreenWidgetShortcutSurfaceMaterial(view, preferences, classLoader)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize lockscreen widget surface material", error)
                        }
                    },
                )
            }
        }
    }

    private fun scheduleLockscreenWidgetInstallation(
        root: View,
        shortcutController: Any?,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        fun install() {
            runCatching {
                installLockscreenWidget(root, shortcutController, preferences, classLoader)
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not apply lockscreen widget", error)
            }
        }
        install()
        root.post(::install)
        root.postDelayed(::install, LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS)
    }


    private fun installLockscreenMusicLockscreenHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)
            val foregroundLayer = panelClass.getDeclaredField("keyguardForegroundLayer")
                .apply { isAccessible = true }
            val statusBarState = panelClass.getDeclaredField("statusBarState")
                .apply { isAccessible = true }
            val bindMethods = panelClass.declaredMethods.filter {
                it.name == "onKeyguardViewBind" && it.parameterCount == 1
            }
            check(bindMethods.isNotEmpty()) { "KeyguardPanelViewController.onKeyguardViewBind was not found" }
            bindMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-music-lockscreen-bind-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val panelController = chain.thisObject
                        val host = runCatching { foregroundLayer.get(panelController) as? ViewGroup }
                            .getOrNull() ?: return@intercept result
                        installLockscreenMusicLockscreen(
                            host = host,
                            isLockscreenShowing = {
                                runCatching {
                                    statusBarState.getInt(panelController) == STATUS_BAR_STATE_KEYGUARD
                                }.getOrDefault(false)
                            },
                            preferences = preferences,
                            classLoader = classLoader,
                        )
                        result
                    }
            }
            val updateVisibility = panelClass.getMethod("updateKeyguardElementsVisibility")
            hook(updateVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-music-lockscreen-visibility")
                .intercept { chain ->
                    val result = chain.proceed()
                    val host = runCatching {
                        foregroundLayer.get(chain.thisObject) as? ViewGroup
                    }.getOrNull()
                    synchronized(lockscreenMusicLockscreenControllers) {
                        lockscreenMusicLockscreenControllers[host]
                    }?.onKeyguardVisibilityChanged()
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed ${bindMethods.size} music-lockscreen foreground hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install music-lockscreen foreground hook", error)
        }
    }





    private fun installLockscreenMusicLockscreen(
        host: ViewGroup,
        isLockscreenShowing: () -> Boolean,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val old = synchronized(lockscreenMusicLockscreenControllers) {
            lockscreenMusicLockscreenControllers[host]
        }
        if (!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)) {
            old?.destroy()
            lockscreenMusicLockscreenControllers.remove(host)
            return
        }
        if (old != null) return
        val controller = LockscreenMusicLockscreenController(
            host = host,
            enabled = {
                preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)
            },
            isLockscreenShowing = isLockscreenShowing,
        )
        synchronized(lockscreenMusicLockscreenControllers) {
            lockscreenMusicLockscreenControllers[host] = controller
        }
    }

    private fun resolveLockscreenShortcutViews(shortcutController: Any?): Pair<View, View>? = runCatching {
        val controller = shortcutController ?: return@runCatching null
        val action = controller.javaClass.methods.firstOrNull {
            it.name == "onSystemUIAction\$1" && it.parameterCount == 2
        } ?: return@runCatching null
        fun shortcut(isLeft: Boolean): View? {
            val bundle = android.os.Bundle().apply { putBoolean("isLeftShortcutView", isLeft) }
            return action.invoke(controller, bundle, "getShortcutView") as? View
        }
        val left = shortcut(true) ?: return@runCatching null
        val right = shortcut(false) ?: return@runCatching null
        left to right
    }.getOrNull()

    private fun miniPlayerAppearance(preferences: SharedPreferences): MiniPlayerAppearance {
        val width = preferences.getFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, 240f).coerceIn(160f, 360f)
        // Stored height uses the same dp unit as the shortcut circle radius; rendering doubles
        // it to obtain the card's actual height.
        val height = preferences.readMiniPlayerHeightRadius()
        val artworkCornerRadius = preferences.getFloat(
            KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS,
            12f,
        ).coerceIn(0f, 60f)
        val requestedMode = preferences.getInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, 0).coerceIn(0, 3)
        fun shortcutAppearance(mode: Int): MiniPlayerAppearance {
            val shortcut = readLockscreenShortcutBackgroundSettings(preferences)
            return MiniPlayerAppearance(
                backgroundMode = mode,
                widthDp = width,
                heightDp = height,
                artworkCornerRadiusDp = artworkCornerRadius,
                pureColor = shortcut.pureColor,
                advancedColor = shortcut.advancedColor,
                advancedOpacity = shortcut.advancedOpacity,
                advancedBlurRadius = shortcut.advancedBlur,
                advancedHighlight = shortcut.advancedHighlight,
                glass = if (shortcut.useGlobalGlass) readSoftGlassSettings(preferences) else shortcut.glass,
            )
        }
        if (requestedMode == MINI_PLAYER_BACKGROUND_DEFAULT) {
            val shortcutMode = shortcutBackgroundMode(preferences)
            return if (shortcutMode == SHORTCUT_BACKGROUND_NONE) {
                MiniPlayerAppearance(
                    backgroundMode = MINI_PLAYER_BACKGROUND_DEFAULT,
                    widthDp = width,
                    heightDp = height,
                    artworkCornerRadiusDp = artworkCornerRadius,
                )
            } else {
                shortcutAppearance(shortcutMode)
            }
        }
        return MiniPlayerAppearance(
            backgroundMode = requestedMode,
            widthDp = width,
            heightDp = height,
            artworkCornerRadiusDp = artworkCornerRadius,
            pureColor = preferences.getInt(KEY_MINI_PLAYER_PURE_COLOR, MINI_PLAYER_PURE_COLOR),
            advancedColor = preferences.getInt(
                KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR,
                DEFAULT_ADVANCED_MATERIAL_COLOR,
            ),
            advancedOpacity = preferences.getInt(
                KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY,
                DEFAULT_ADVANCED_MATERIAL_OPACITY,
            ).coerceIn(0, 100),
            advancedBlurRadius = preferences.getInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS, 10)
                .coerceIn(0, 40),
            advancedHighlight = preferences.getBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, false),
            glass = readLockscreenCapsuleGlassSettings(preferences).let {
                if (it.useGlobal) readSoftGlassSettings(preferences) else it.glass
            },
        )
    }

    private val capsuleMaterialStates = WeakHashMap<ImageView, Pair<MiniPlayerAppearance, ClassLoader>>()

    private fun applyMiniPlayerMaterial(view: ImageView, appearance: MiniPlayerAppearance, classLoader: ClassLoader) {
        if (capsuleMaterialStates.put(view, appearance to classLoader) == null) {
            view.addOnLayoutChangeListener { target, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right > left && bottom > top &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                    refreshMiniPlayerMaterial(target as ImageView)
                }
            }
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(target: View) {
                    target.post { if (target.isAttachedToWindow) refreshMiniPlayerMaterial(target as ImageView) }
                }
                override fun onViewDetachedFromWindow(target: View) = Unit
            })
        }
        if (view.isAttachedToWindow && view.width > 0 && view.height > 0) refreshMiniPlayerMaterial(view)
    }

    private fun refreshMiniPlayerMaterial(view: ImageView) {
        val (appearance, classLoader) = capsuleMaterialStates[view] ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            if (appearance.backgroundMode != MINI_PLAYER_BACKGROUND_SOFT_GLASS) {
                // The same layer survives material changes; retire the old glass shader.
                View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType).invoke(view, 0)
                View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(view)
                View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
                View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
            }
            when (appearance.backgroundMode) {
                MINI_PLAYER_BACKGROUND_ADVANCED -> applyLegacyBackdropMaterial(
                    view = view,
                    opacity = appearance.advancedOpacity,
                    blurRadius = appearance.advancedBlurRadius,
                    color = appearance.advancedColor,
                    showHighlight = appearance.advancedHighlight,
                )
                MINI_PLAYER_BACKGROUND_SOFT_GLASS -> {
                    val glass = appearance.glass.copy(enabled = true)
                    applyLegacyBackdropMaterial(
                        view = view,
                        opacity = softGlassBlur(10, glass.backgroundOpacity),
                        blurRadius = softGlassBlur(40, glass.backgroundBlur),
                        color = Color.WHITE,
                        showHighlight = false,
                    )
                    View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType)
                        .invoke(view, softGlassScale(1f, glass.backgroundScale))
                    applySystemGlassMaterial(
                        view = view,
                        classLoader = classLoader,
                        blurRadius = softGlassBlur(36, glass.refractionBlur),
                        luminance = DEFAULT_SOFT_GLASS_LUMINANCE,
                        parameters = tuneSoftGlass(SHORTCUT_GLASS_PARAMETERS, glass, standaloneBackground = true),
                    )
                }
            }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not refresh capsule material", it) }
    }

    private fun commonShortcutParent(first: View, second: View): ViewGroup? {
        val ancestors = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        var current: View? = first
        while (current != null) {
            ancestors += current
            current = current.parent as? View
        }
        current = second.parent as? View
        while (current != null) {
            if (current in ancestors && current is ViewGroup) return current
            current = current.parent as? View
        }
        return null
    }

    private val shortcutMaterialRoots = Collections.synchronizedMap(WeakHashMap<View, ClassLoader>())
    private val shortcutMaterialHandler by lazy { Handler(Looper.getMainLooper()) }
    private var shortcutMaterialPreferences: SharedPreferences? = null
    private val shortcutMaterialRefresh = Runnable {
        val preferences = shortcutMaterialPreferences ?: return@Runnable
        val roots = synchronized(shortcutMaterialRoots) { shortcutMaterialRoots.entries.map { it.key to it.value } }
        roots.forEach { (root, loader) ->
            if (root.isAttachedToWindow) runCatching {
                installShortcutGlassBackgrounds(root, preferences, loader)
                applyLockscreenShortcutGeometry(root, preferences)
            }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not refresh shortcut material", it) }
        }
        val capsules = synchronized(lockscreenMiniPlayerControllers) { lockscreenMiniPlayerControllers.values.toList() }
        capsules.forEach { it.onMaterialSettingsChanged() }
    }
    private val shortcutMaterialPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("lockscreen_shortcut_") || key.startsWith("shortcut_") ||
            key.startsWith("soft_glass_parameters_v3_") || key.startsWith(LOCKSCREEN_CAPSULE_GLASS_PREFIX) ||
            key.startsWith("mini_player_") || key.startsWith("lockscreen_mini_player_")) {
            shortcutMaterialHandler.removeCallbacks(shortcutMaterialRefresh)
            shortcutMaterialHandler.post(shortcutMaterialRefresh)
        }
    }

    private fun installShortcutGlassBackgrounds(
        root: View,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (shortcutMaterialRoots.put(root, classLoader) == null) {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    shortcutMaterialHandler.removeCallbacks(shortcutMaterialRefresh)
                    shortcutMaterialHandler.post(shortcutMaterialRefresh)
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        if (shortcutMaterialPreferences !== preferences) {
            shortcutMaterialPreferences?.unregisterOnSharedPreferenceChangeListener(shortcutMaterialPreferenceListener)
            shortcutMaterialPreferences = preferences
            preferences.registerOnSharedPreferenceChangeListener(shortcutMaterialPreferenceListener)
        }
        val shortcutSettings = readLockscreenShortcutBackgroundSettings(preferences)
        val backgroundMode = shortcutSettings.effectiveBackgroundMode
        val radius = shortcutSettings.circleRadius
        val backgroundRadiusEnabled = shortcutSettings.radiusEnabled
        val backgroundRadius = shortcutSettings.radius
        val diameter = (radius * root.resources.displayMetrics.density).toInt().coerceAtLeast(1) * 2
        findShortcutContainers(root).forEach { shortcutContainer ->
            shortcutContainer.clipChildren = false
            shortcutContainer.clipToPadding = false
            for (index in shortcutContainer.childCount - 1 downTo 0) {
                val child = shortcutContainer.getChildAt(index)
                if (child.tag == SHORTCUT_GLASS_TAG) shortcutContainer.removeViewAt(index)
            }
            val glassBackground = ImageView(shortcutContainer.context).apply {
                tag = SHORTCUT_GLASS_TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                when (backgroundMode) {
                    SHORTCUT_BACKGROUND_NONE -> {
                        // Keep a real drawable/layout layer while making the "不显示" mode
                        // completely transparent.
                        setImageDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                    }
                    SHORTCUT_BACKGROUND_PURE_COLOR -> {
                        setBackgroundColor(shortcutSettings.pureColor)
                    }
                    else -> {
                        // Miui's backdrop renderer only registers views that have drawable content.
                        // A one-alpha source keeps this layer visually transparent until the system
                        // material pipeline has rendered its backdrop into it.
                        setImageDrawable(GradientDrawable().apply { setColor(Color.argb(1, 255, 255, 255)) })
                    }
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(target: View, outline: Outline) {
                        if (backgroundRadiusEnabled) {
                            val radiusPx = backgroundRadius * target.resources.displayMetrics.density
                            outline.setRoundRect(
                                0,
                                0,
                                target.width,
                                target.height,
                                radiusPx.coerceAtMost(minOf(target.width, target.height) / 2f),
                            )
                        } else {
                            outline.setOval(0, 0, target.width, target.height)
                        }
                    }
                }
            }
            shortcutContainer.addView(
                glassBackground,
                0,
                FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER),
            )
            glassBackground.invalidateOutline()
            // Match the platform's layout-bound material initialization: SDF dimensions
            // are zero before layout and cannot describe a usable glass surface.
            val applyMaterial = {
                runCatching {
                    when (backgroundMode) {
                        SHORTCUT_BACKGROUND_ADVANCED_MATERIAL -> applyLegacyBackdropMaterial(
                            view = glassBackground,
                            opacity = shortcutSettings.advancedOpacity,
                            blurRadius = shortcutSettings.advancedBlur,
                            color = shortcutSettings.advancedColor,
                            showHighlight = shortcutSettings.advancedHighlight,
                        )
                        SHORTCUT_BACKGROUND_SOFT_GLASS -> {
                            val activeGlass = (if (shortcutSettings.useGlobalGlass) readSoftGlassSettings(preferences)
                                else shortcutSettings.glass).copy(enabled = true)
                            applyLegacyBackdropMaterial(
                                view = glassBackground,
                                // 100% means the existing translucent 10% blend, not solid white.
                                opacity = softGlassBlur(10, activeGlass.backgroundOpacity),
                                blurRadius = softGlassBlur(40, activeGlass.backgroundBlur),
                                color = Color.WHITE,
                                showHighlight = false,
                            )
                            View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType)
                                .invoke(glassBackground, softGlassScale(1f, activeGlass.backgroundScale))
                            applySystemGlassMaterial(
                                view = glassBackground,
                                classLoader = classLoader,
                                blurRadius = softGlassBlur(36, activeGlass.refractionBlur),
                                luminance = DEFAULT_SOFT_GLASS_LUMINANCE,
                                parameters = tuneSoftGlass(SHORTCUT_GLASS_PARAMETERS, activeGlass, standaloneBackground = true),
                            )
                        }
                    }
                }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not initialize shortcut material", it) }
                Unit
            }
            glassBackground.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right > left && bottom > top &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) applyMaterial()
            }
            glassBackground.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.post { if (view.isAttachedToWindow && view.width > 0 && view.height > 0) applyMaterial() }
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            if (glassBackground.isAttachedToWindow && glassBackground.width > 0 && glassBackground.height > 0) applyMaterial()
            applyShortcutIconColorMode(shortcutContainer, shortcutIconColorMode(preferences))
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this,
            Log.INFO,
            TAG,
            "Applied shortcut background to ${findShortcutContainers(root).size} container(s), mode=$backgroundMode, radius=${radius}dp",
        )
    }

    private fun findShortcutContainers(root: View): List<FrameLayout> {
        val result = ArrayList<FrameLayout>(2)
        fun visit(view: View) {
            if (view is FrameLayout && view.idName() in LOCKSCREEN_SHORTCUT_CONTAINER_IDS) {
                result += view
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private val shortcutOriginalMargins = WeakHashMap<View, Triple<Int, Int, Int>>()
    private val shortcutOriginalIconSizes = WeakHashMap<View, Pair<Int, Int>>()

    private fun applyLockscreenShortcutGeometry(root: View, preferences: SharedPreferences) {
        val shortcuts = findShortcutContainers(root)
        if (shortcuts.size < 2) return
        val shortcutSettings = readLockscreenShortcutBackgroundSettings(preferences)
        val spacingEnabled = shortcutSettings.enabled && shortcutSettings.spacingEnabled
        val spacing = (shortcutSettings.spacing.coerceIn(0f, 48f) *
            root.resources.displayMetrics.density + .5f).toInt()
        val iconEnabled = shortcutSettings.enabled && shortcutSettings.iconSizeEnabled
        val iconSize = (shortcutSettings.iconSize.coerceIn(16f, 64f) *
            root.resources.displayMetrics.density + .5f).toInt()
        shortcuts.forEachIndexed { index, shortcut ->
            (shortcut.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (spacingEnabled) {
                    shortcutOriginalMargins.getOrPut(shortcut) { Triple(params.leftMargin, params.rightMargin, params.bottomMargin) }
                    if (index == 0) params.leftMargin = spacing else params.rightMargin = spacing
                    params.bottomMargin = spacing
                    shortcut.layoutParams = params
                } else {
                    shortcutOriginalMargins.remove(shortcut)?.let { original ->
                        params.leftMargin = original.first
                        params.rightMargin = original.second
                        params.bottomMargin = original.third
                        shortcut.layoutParams = params
                    }
                }
            }
            if (iconEnabled || shortcutOriginalIconSizes.isNotEmpty()) {
                val images = ArrayList<ImageView>()
                fun collect(view: View) {
                    if (view is ImageView && view.tag != SHORTCUT_GLASS_TAG) images += view
                    if (view is ViewGroup) {
                        for (childIndex in 0 until view.childCount) collect(view.getChildAt(childIndex))
                    }
                }
                collect(shortcut)
                images.forEach { image ->
                    (image.layoutParams as? ViewGroup.LayoutParams)?.let { params ->
                        if (iconEnabled) {
                            shortcutOriginalIconSizes.getOrPut(image) { params.width to params.height }
                            params.width = iconSize
                            params.height = iconSize
                            image.layoutParams = params
                        } else {
                            shortcutOriginalIconSizes.remove(image)?.let { original ->
                                params.width = original.first
                                params.height = original.second
                                image.layoutParams = params
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyShortcutIconColorMode(container: ViewGroup, mode: Int) {
        fun applyTo(view: View) {
            if (view is ImageView && view.tag != SHORTCUT_GLASS_TAG) {
                when (mode) {
                    SHORTCUT_ICON_COLOR_LIGHT -> view.setColorFilter(SHORTCUT_ICON_LIGHT_COLOR)
                    SHORTCUT_ICON_COLOR_DARK -> view.setColorFilter(SHORTCUT_ICON_DARK_COLOR)
                    else -> view.clearColorFilter()
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) applyTo(view.getChildAt(index))
            }
        }
        applyTo(container)
    }

    /** Apply the same platform material pipeline used by shortcut backgrounds to the widget track. */
    private fun applyLockscreenWidgetBatteryMaterial(
        view: View,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        val mode = preferences.getInt(
            KEY_LOCKSCREEN_WIDGET_BATTERY_MATERIAL_MODE,
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
        ).coerceIn(
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE,
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_SOFT,
        )
        val viewClass = View::class.java
        runCatching { viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view) }
        runCatching {
            viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
                .invoke(view, false)
        }
        when (mode) {
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_PURE -> {
                (view as? ImageView)?.setImageDrawable(null)
                view.background = GradientDrawable().apply {
                    setColor(preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR))
                    cornerRadius = view.resources.displayMetrics.density * 9f
                }
            }
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_ADVANCED -> {
                val source = GradientDrawable().apply {
                    setColor(Color.argb(1, 255, 255, 255))
                    cornerRadius = view.resources.displayMetrics.density * 9f
                }
                if (view is ImageView) {
                    view.background = null
                    view.setImageDrawable(source)
                } else {
                    view.background = source
                }
                applyLegacyBackdropMaterial(
                    view = view,
                    opacity = preferences.getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY, DEFAULT_ADVANCED_MATERIAL_OPACITY)
                        .coerceIn(0, 100),
                    blurRadius = preferences.getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS, DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS)
                        .coerceIn(0, 40),
                    color = preferences.getInt(KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR, DEFAULT_ADVANCED_MATERIAL_COLOR),
                    showHighlight = false,
                )
            }
            LOCKSCREEN_WIDGET_BATTERY_MATERIAL_SOFT -> {
                val source = GradientDrawable().apply {
                    setColor(Color.argb(1, 255, 255, 255))
                    cornerRadius = view.resources.displayMetrics.density * 9f
                }
                if (view is ImageView) {
                    view.background = null
                    view.setImageDrawable(source)
                } else {
                    view.background = source
                }
                applyLegacyBackdropMaterial(
                    view = view,
                    opacity = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, DEFAULT_SOFT_GLASS_OPACITY)
                        .coerceIn(0, 100),
                    blurRadius = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS, DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS)
                        .coerceIn(0, 40),
                    color = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, DEFAULT_SOFT_GLASS_COLOR),
                    showHighlight = false,
                )
                applySystemGlassMaterial(
                    view = view,
                    classLoader = classLoader,
                    blurRadius = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS, DEFAULT_SOFT_GLASS_BLUR_RADIUS)
                        .coerceIn(0, 40),
                    luminance = preferences.getFloat(KEY_SHORTCUT_SOFT_GLASS_LUMINANCE, DEFAULT_SOFT_GLASS_LUMINANCE),
                )
            }
        }
    }

    /**
     * Combination two has three independent surfaces. By default they follow the shortcut
     * background, while the widget background page can provide an independent material set.
     */
    private fun applyLockscreenWidgetShortcutSurfaceMaterial(
        view: View,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        val widgetBackgroundMode = preferences.getInt(
            KEY_LOCKSCREEN_WIDGET_BACKGROUND_MODE,
            LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT,
        ).coerceIn(
            LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT,
            LOCKSCREEN_WIDGET_BACKGROUND_SOFT_GLASS,
        )
        val followsShortcutBackground =
            widgetBackgroundMode == LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT
        val mode = if (followsShortcutBackground) {
            shortcutBackgroundMode(preferences)
        } else {
            when (widgetBackgroundMode) {
                LOCKSCREEN_WIDGET_BACKGROUND_PURE -> SHORTCUT_BACKGROUND_PURE_COLOR
                LOCKSCREEN_WIDGET_BACKGROUND_ADVANCED -> SHORTCUT_BACKGROUND_ADVANCED_MATERIAL
                else -> SHORTCUT_BACKGROUND_SOFT_GLASS
            }
        }
        val viewClass = View::class.java
        runCatching { viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view) }
        runCatching {
            viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
                .invoke(view, false)
        }
        when (mode) {
            SHORTCUT_BACKGROUND_NONE -> {
                (view as? ImageView)?.apply {
                    background = null
                    setImageDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                } ?: run { view.background = GradientDrawable().apply { setColor(Color.TRANSPARENT) } }
            }
            SHORTCUT_BACKGROUND_PURE_COLOR -> {
                (view as? ImageView)?.setImageDrawable(null)
                view.background = GradientDrawable().apply {
                    setColor(
                        if (followsShortcutBackground) {
                            preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR)
                        } else {
                            preferences.getInt(KEY_LOCKSCREEN_WIDGET_PURE_COLOR, 0x73000000)
                        },
                    )
                    cornerRadius = view.height / 2f
                }
            }
            SHORTCUT_BACKGROUND_ADVANCED_MATERIAL,
            SHORTCUT_BACKGROUND_SOFT_GLASS
            -> {
                val source = GradientDrawable().apply { setColor(Color.argb(1, 255, 255, 255)) }
                if (view is ImageView) {
                    view.background = null
                    view.setImageDrawable(source)
                } else {
                    view.background = source
                }
                if (mode == SHORTCUT_BACKGROUND_ADVANCED_MATERIAL) {
                    applyLegacyBackdropMaterial(
                        view = view,
                        opacity = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY
                            } else {
                                KEY_LOCKSCREEN_WIDGET_ADVANCED_MATERIAL_OPACITY
                            },
                            DEFAULT_ADVANCED_MATERIAL_OPACITY,
                        ).coerceIn(0, 100),
                        blurRadius = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS
                            } else {
                                KEY_LOCKSCREEN_WIDGET_ADVANCED_MATERIAL_BLUR_RADIUS
                            },
                            DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        color = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR
                            } else {
                                KEY_LOCKSCREEN_WIDGET_ADVANCED_MATERIAL_COLOR
                            },
                            DEFAULT_ADVANCED_MATERIAL_COLOR,
                        ),
                        showHighlight = preferences.getBoolean(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT
                            } else {
                                KEY_LOCKSCREEN_WIDGET_ADVANCED_MATERIAL_HIGHLIGHT
                            },
                            false,
                        ),
                    )
                } else {
                    applyLegacyBackdropMaterial(
                        view = view,
                        opacity = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_SOFT_GLASS_OPACITY
                            } else {
                                KEY_LOCKSCREEN_WIDGET_SOFT_GLASS_OPACITY
                            },
                            DEFAULT_SOFT_GLASS_OPACITY,
                        ).coerceIn(0, 100),
                        blurRadius = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS
                            } else {
                                KEY_LOCKSCREEN_WIDGET_SOFT_GLASS_BACKDROP_BLUR_RADIUS
                            },
                            DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        color = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_SOFT_GLASS_COLOR
                            } else {
                                KEY_LOCKSCREEN_WIDGET_SOFT_GLASS_COLOR
                            },
                            DEFAULT_SOFT_GLASS_COLOR,
                        ),
                        showHighlight = false,
                    )
                    applySystemGlassMaterial(
                        view = view,
                        classLoader = classLoader,
                        blurRadius = preferences.getInt(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS
                            } else {
                                KEY_LOCKSCREEN_WIDGET_SOFT_GLASS_BLUR_RADIUS
                            },
                            DEFAULT_SOFT_GLASS_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        luminance = preferences.getFloat(
                            if (followsShortcutBackground) {
                                KEY_SHORTCUT_SOFT_GLASS_LUMINANCE
                            } else {
                                KEY_LOCKSCREEN_WIDGET_SOFT_GLASS_LUMINANCE
                            },
                            DEFAULT_SOFT_GLASS_LUMINANCE,
                        ).coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE),
                    )
                }
            }
        }
    }

    private fun View.idName(): String? = runCatching {
        resources.getResourceEntryName(id)
    }.getOrNull()

    /**
     * This is the platform backdrop path used by HyperCeiler for lockscreen shortcuts.
     * It must be initialized before MiGlassCompat: MiGlass provides the OS4 material
     * parameters, while these APIs register the view with the window blur compositor.
     */
    private fun applyLegacyBackdropMaterial(
        view: View,
        opacity: Int,
        blurRadius: Int,
        color: Int,
        showHighlight: Boolean,
    ) {
        val viewClass = View::class.java
        viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view)
        viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
            .invoke(view, true)
        viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
            .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
        viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
            .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
        viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
            .invoke(view, blurRadius.coerceIn(0, MAX_SHORTCUT_BACKDROP_BLUR_RADIUS))
        viewClass.getMethod(
            "addMiBackgroundBlendColor",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(
            view,
            Color.argb(
                opacity.coerceIn(0, MAX_SHORTCUT_OPACITY) * 255 / MAX_SHORTCUT_OPACITY,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            ),
            SHORTCUT_GLASS_BLEND_MODE,
        )
        if (showHighlight) {
            viewClass.getMethod("setMiBloomStroke", FloatArray::class.java)
                .invoke(view, SHORTCUT_BLOOM_STROKE_PARAMETERS)
        }
    }

    private fun applySystemGlassMaterial(
        view: View,
        classLoader: ClassLoader,
        blurRadius: Int,
        luminance: Float,
        parameters: FloatArray? = null,
    ) {
        val loaders = LinkedHashSet<ClassLoader>().apply {
            add(classLoader)
            synchronized(controlCenterButtonsLock) {
                controlCenterControllerClassLoader?.let(::add)
                systemUiClassLoader?.let(::add)
            }
            add(view.context.classLoader)
        }
        val glassCompat = loaders.asSequence().mapNotNull { loader ->
            runCatching { Class.forName(MI_GLASS_COMPAT_CLASS, false, loader) }.getOrNull()
        }.firstOrNull() ?: throw ClassNotFoundException(
            "$MI_GLASS_COMPAT_CLASS was not available from any SystemUI class loader",
        )
        val smallBlur = blurRadius.coerceIn(0, MAX_SHORTCUT_GLASS_BLUR_RADIUS)
        val glassParameters = (parameters ?: SHORTCUT_GLASS_PARAMETERS).copyOf().apply {
            if (parameters == null) this[GLASS_LUMINANCE_AMOUNT_INDEX] = luminance.coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE)
        }
        glassCompat.getMethod(
            "setMiGlassBlurRadius",
            View::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(null, view, smallBlur, (smallBlur * 2).coerceAtMost(MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS))
        glassCompat.getMethod(
            "setMiViewMaterialTypeCompat",
            Int::class.javaPrimitiveType,
            View::class.java,
        ).invoke(null, SHORTCUT_GLASS_MATERIAL_TYPE, view)
        // MiGlassCompat normally installs this from a layout listener. The gesture
        // overlay is positioned manually, so explicitly provide its SDF bounds as
        // well; otherwise the glass shader has no valid clipping extent and renders
        // as a transparent view on several HyperOS builds.
        runCatching {
            glassCompat.getMethod(
                "setMiGlassSdfMaxSizeCompat",
                View::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).invoke(null, view, view.width.toFloat(), view.height.toFloat())
        }
        glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, glassParameters)
    }

    private fun shortcutBackgroundMode(preferences: SharedPreferences): Int =
        readLockscreenShortcutBackgroundSettings(preferences).effectiveBackgroundMode

    private fun shortcutIconColorMode(preferences: SharedPreferences): Int = preferences.getInt(
        KEY_SHORTCUT_ICON_COLOR_MODE,
        SHORTCUT_ICON_COLOR_AUTO,
    ).coerceIn(SHORTCUT_ICON_COLOR_AUTO, SHORTCUT_ICON_COLOR_DARK)

    /**
     * HyperOS moves only the time layer for notification avoidance on several clock templates.
     * In particular, the all-in-one clock keeps its date in a sibling text area, leaving it
     * behind. Mirror the actual moving layer onto that date area without affecting the widget
     * feature or its enabled state.
     */
    private fun installLockscreenClockDateFollowHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val notificationTopChangeType = classLoader.loadClass(
                "com.miui.systemui.notification.data.repository.NotificationTopChangeType",
            )
            val animationClasses = listOf(
                "com.android.keyguard.clock.animation.ClockBaseAnimation",
                "com.android.keyguard.clock.animation.allinone.AllInOneClockAnimation",
            )
            var hookCount = 0
            animationClasses.forEach { className ->
                val animationClass = classLoader.loadClass(className)
                val methods = animationClass.declaredMethods.filter { method ->
                    method.name == "notifStateChange" &&
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Float::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                                notificationTopChangeType,
                            ),
                        )
                }
                methods.forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-clock-date-follow:${className.substringAfterLast('.')}")
                        .intercept { chain ->
                            // Capture the template's native clock position before SystemUI
                            // starts changing it. Only the subsequent delta belongs to the
                            // notification avoidance animation.
                            syncLockscreenClockDate(chain.thisObject, classLoader, schedule = false)
                            val result = chain.proceed()
                            syncLockscreenClockDate(chain.thisObject, classLoader)
                            result
                        }
                    hookCount++
                }
            }
            check(hookCount > 0) { "Keyguard clock notification animation was not found" }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed $hookCount lockscreen clock date-follow hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Lockscreen clock date-follow hook unavailable", error)
        }
    }

    private fun syncLockscreenClockDate(
        animation: Any?,
        classLoader: ClassLoader,
        schedule: Boolean = true,
    ) {
        val controller = readInheritedField(animation, "mMiuiClockController") ?: return
        val clockView = readInheritedField(controller, "mClockView") ?: return
        val clockViewType = runCatching {
            classLoader.loadClass("com.miui.clock.module.ClockViewType")
        }.getOrNull() ?: return
        val movingView = getClockPart(clockView, clockViewType, "ANIMATION_CONTAINER")
            ?: getClockPart(clockView, clockViewType, "ALL_VIEW")
            ?: return
        val textArea = readInheritedField(clockView, "mTextArea") as? View
        val dateViews = linkedSetOf<View>().apply {
            // mTextArea is the date/lunar-date container of the all-in-one and classic clocks.
            textArea?.let(::add)
            if (textArea == null) {
                listOf("mDateView", "mDate", "dateView").forEach { fieldName ->
                    (readInheritedField(clockView, fieldName) as? View)?.let(::add)
                }
                listOf("FULL_DATE", "DATE", "FULL_DATE_WEEK", "FULL_WEEK", "WEEK").forEach { typeName ->
                    getClockPart(clockView, clockViewType, typeName)?.let(::add)
                }
            }
        }.filter { dateView ->
            // Most base clock classes return their root for unsupported parts. Never translate
            // that root: it contains the time layer and would preserve the overlap.
            dateView !== clockView && dateView !== movingView &&
                !isViewDescendantOf(dateView, movingView) &&
                !isViewDescendantOf(movingView, dateView)
        }
        if (dateViews.isEmpty()) return

        syncLockscreenDateViews(clockView, movingView, dateViews)
        if (schedule) scheduleLockscreenDateFollow(clockView, movingView, dateViews)
    }

    private fun scheduleLockscreenDateFollow(
        clockView: Any,
        movingView: View,
        dateViews: List<View>,
    ) {
        val generation = synchronized(lockscreenDateFollowGenerations) {
            val next = (lockscreenDateFollowGenerations[movingView] ?: 0) + 1
            lockscreenDateFollowGenerations[movingView] = next
            next
        }
        val startedAt = SystemClock.uptimeMillis()
        fun followFrame() {
            val current = synchronized(lockscreenDateFollowGenerations) {
                lockscreenDateFollowGenerations[movingView]
            }
            if (current != generation || !movingView.isAttachedToWindow) return
            syncLockscreenDateViews(clockView, movingView, dateViews)
            if (SystemClock.uptimeMillis() - startedAt < LOCKSCREEN_DATE_FOLLOW_DURATION_MS) {
                movingView.postOnAnimation(::followFrame)
            }
        }
        movingView.postOnAnimation(::followFrame)
    }

    private fun syncLockscreenDateViews(
        clockView: Any,
        movingView: View,
        dateViews: List<View>,
    ) {
        val glyphTop = findLockscreenClockGlyphTop(clockView)
        val nativeMovingTranslation = synchronized(lockscreenDateNativeOffsets) {
            lockscreenDateNativeOffsets.getOrPut(movingView) { movingView.translationY }
        }
        val offset = movingView.translationY - nativeMovingTranslation
        dateViews.forEach { dateView ->
            if (!dateView.isAttachedToWindow) return@forEach
            val previousOffset = synchronized(lockscreenDateAppliedOffsets) {
                lockscreenDateAppliedOffsets[dateView] ?: 0f
            }
            // Remove only our previous contribution so clock-template layout changes survive.
            val templateTranslation = dateView.translationY - previousOffset
            val appliedOffset = glyphTop?.let { top ->
                val location = IntArray(2).also(dateView::getLocationOnScreen)
                val templateTop = location[1] - previousOffset
                // Keep a small visual gap between the date/lunar-date row and the top of the
                // actual glyph path, rather than the larger invisible TimeView container.
                top - dateView.resources.displayMetrics.density * LOCKSCREEN_DATE_TO_GLYPH_GAP_DP -
                    dateView.height - templateTop
            } ?: offset
            dateView.translationY = templateTranslation + appliedOffset
            synchronized(lockscreenDateAppliedOffsets) {
                lockscreenDateAppliedOffsets[dateView] = appliedOffset
            }
        }
    }

    /** The all-in-one clock draws its digits inside full-screen views; textTop is their real top. */
    private fun findLockscreenClockGlyphTop(clockView: Any): Float? = listOf(
        "mHourView",
        "mMinuteView",
        "mTimeView",
        "mTimeView2",
    ).mapNotNull { fieldName ->
        val timeView = readInheritedField(clockView, fieldName) as? View ?: return@mapNotNull null
        if (!timeView.isAttachedToWindow || timeView.visibility != View.VISIBLE) return@mapNotNull null
        val textTop = runCatching {
            timeView.javaClass.getMethod("getTextTop").invoke(timeView) as? Number
        }.getOrNull()?.toFloat() ?: return@mapNotNull null
        val location = IntArray(2).also(timeView::getLocationOnScreen)
        location[1] + textTop
    }.minOrNull()

    private fun getClockPart(clockView: Any, clockViewType: Class<*>, name: String): View? = runCatching {
        val type = clockViewType.getField(name).get(null)
        val getter = clockView.javaClass.methods.firstOrNull { method ->
            method.name == "getIClockView" && method.parameterTypes.contentEquals(arrayOf(clockViewType))
        } ?: return null
        getter.invoke(clockView, type) as? View
    }.getOrNull()

    private fun readInheritedField(target: Any?, name: String): Any? {
        var type = target?.javaClass
        while (type != null) {
            val value = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value != null) return value
            type = type.superclass
        }
        return null
    }

    private fun isViewDescendantOf(view: View, possibleAncestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === possibleAncestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun installLockscreenNotificationHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        val shelfSpaceHookInstalled = runCatching {
            val legacyFlowClass = classLoader.loadClass(FOD_SHELF_SPACE_FLOW_CLASS)
            hook(legacyFlowClass.getDeclaredMethod("invokeSuspend", Any::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-notification-ignore-fod")
                .intercept { chain ->
                    if (isNotificationFodPositionLimitRemoved(preferences)) false else chain.proceed()
                }
            true
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen notification shelf-space hook", error)
        }.getOrDefault(false)

        val positionFlows = findFodNotificationPositionFlows(classLoader)
        var positionHookCount = 0
        positionFlows.forEachIndexed { index, positionFlowClass ->
            runCatching {
                val flowsField = positionFlowClass.getDeclaredField("\u0024flows\u0024inlined")
                    .apply { isAccessible = true }
                val collect = positionFlowClass.declaredMethods.firstOrNull { method ->
                    method.name == "collect" && method.parameterCount == 2
                } ?: error("nsslLockYPosition combine Flow.collect was not found")
                hook(collect)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-notification-fod-position-$index")
                    .intercept { chain ->
                        runCatching {
                            installFodEnrollmentFlowOverride(
                                chain.thisObject,
                                flowsField,
                                positionFlowClass.classLoader,
                                preferences,
                            )
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not override lockscreen FOD position", error)
                        }
                        val originalCollector = chain.getArg(0) ?: return@intercept chain.proceed()
                        val collectorType = collect.parameterTypes.firstOrNull()
                            ?: return@intercept chain.proceed()
                        val forwardingCollector = createLockscreenNotificationPositionCollector(
                            originalCollector,
                            chain.thisObject,
                            collectorType,
                            collectorType.classLoader ?: positionFlowClass.classLoader ?: classLoader,
                            preferences,
                        )
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(forwardingCollector, chain.getArg(1)),
                        )
                    }
                positionHookCount += 1
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install FOD-position hook for ${positionFlowClass.name}", error)
            }
        }
        if (shelfSpaceHookInstalled || positionHookCount > 0) {
            btm.m.os4.systemuihook.HookModuleLogger.log(this,
                Log.INFO,
                TAG,
                "Installed lockscreen notification FOD hooks: shelf=$shelfSpaceHookInstalled, position=$positionHookCount",
            )
        } else {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not locate lockscreen notification FOD-position flow")
        }
    }

    private fun findFodNotificationPositionFlows(classLoader: ClassLoader): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        // Kotlin emits this combine Flow as a top-level synthetic class.  It is not reported by
        // Class.getDeclaredClasses(), and the lambda ordinal changes whenever Xiaomi edits the
        // controller.  Locate the stable Flow shape instead of depending on the ordinal or its
        // nested SuspendLambda implementation.
        for (ordinal in FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE) {
            val className = "$FOD_NOTIFICATION_POSITION_FLOW_PREFIX$ordinal$FOD_NOTIFICATION_POSITION_FLOW_SUFFIX"
            runCatching { classLoader.loadClass(className) }
                .getOrNull()
                ?.takeIf { type ->
                    type.declaredFields.any { it.name == "\u0024flows\u0024inlined" } &&
                        type.declaredMethods.any { method ->
                            method.name == "collect" && method.parameterCount == 2
                        }
                }
                ?.let(result::add)
        }
        return result.toList()
    }

    /**
     * Makes the final combine input (hasEnrolledTemplatesFlow) report false while the feature is
     * enabled.  Decorating the source flow keeps preference changes live and leaves all other
     * FOD behavior untouched.
     */
    private fun installFodEnrollmentFlowOverride(
        combineFlow: Any?,
        flowsField: java.lang.reflect.Field,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val owner = combineFlow ?: return
        val flows = flowsField.get(owner) as? Array<Any?> ?: return
        if (flows.isEmpty()) return
        synchronized(fodEnrollmentFlowOverrides) {
            if (fodEnrollmentFlowOverrides.containsKey(owner)) return
            val sourceFlow = flows.lastOrNull() ?: return
            val flowClass = classLoader.loadClass("kotlinx.coroutines.flow.Flow")
            if (!flowClass.isInstance(sourceFlow)) return
            flows[flows.lastIndex] = createFodEnrollmentFlowOverride(
                sourceFlow,
                flowClass,
                classLoader,
                preferences,
            )
            fodEnrollmentFlowOverrides[owner] = sourceFlow
        }
    }

    private fun createFodEnrollmentFlowOverride(
        sourceFlow: Any,
        flowClass: Class<*>,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Any = java.lang.reflect.Proxy.newProxyInstance(
        classLoader,
        arrayOf(flowClass),
    ) { _, method, args ->
        val invocationArgs = args ?: emptyArray()
        if (method.name != "collect" || invocationArgs.isEmpty()) {
            return@newProxyInstance method.invoke(sourceFlow, *invocationArgs)
        }
        val originalCollector = invocationArgs[0] ?: return@newProxyInstance method.invoke(
            sourceFlow,
            *invocationArgs,
        )
        val collectorClass = method.parameterTypes.firstOrNull()
            ?: return@newProxyInstance method.invoke(sourceFlow, *invocationArgs)
        val forwardingCollector = java.lang.reflect.Proxy.newProxyInstance(
            collectorClass.classLoader ?: classLoader,
            arrayOf(collectorClass),
        ) { _, collectorMethod, collectorArgs ->
            val forwardedArgs = collectorArgs?.copyOf() ?: emptyArray()
            if (collectorMethod.name == "emit" && forwardedArgs.isNotEmpty() &&
                isNotificationFodPositionLimitRemoved(preferences)
            ) {
                forwardedArgs[0] = false
            }
            collectorMethod.invoke(originalCollector, *forwardedArgs)
        }
        method.invoke(sourceFlow, forwardingCollector, *invocationArgs.drop(1).toTypedArray())
    }

    private fun createLockscreenNotificationPositionCollector(
        originalCollector: Any,
        flow: Any,
        collectorClass: Class<*>,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Any {
        return Proxy.newProxyInstance(
            collectorClass.classLoader ?: classLoader,
            arrayOf(collectorClass),
        ) { _, method, args ->
            val invocationArgs = args?.copyOf() ?: emptyArray()
            if (method.name == "emit" && invocationArgs.isNotEmpty()) {
                invocationArgs[0] = adjustLockscreenNotificationPosition(
                    invocationArgs[0],
                    flow,
                    preferences,
                )
            }
            method.invoke(originalCollector, *invocationArgs)
        }
    }

    private fun adjustLockscreenNotificationPosition(
        value: Any?,
        flow: Any,
        preferences: SharedPreferences,
    ): Any? {
        if (!preferences.getBoolean(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET_ENABLED, false)) {
            return value
        }
        val valueType = value?.javaClass ?: return value
        if (valueType.name != "kotlin.Triple") return value
        val parts = runCatching {
            arrayOf(
                valueType.getMethod("getFirst").invoke(value),
                valueType.getMethod("getSecond").invoke(value),
                valueType.getMethod("getThird").invoke(value),
            )
        }.getOrNull() ?: return value
        val second = (parts[1] as? Number)?.toInt() ?: return value
        val offsetDp = preferences.getInt(KEY_LOCKSCREEN_NOTIFICATION_BOTTOM_OFFSET, 0)
            .coerceIn(-40, 64)
        if (offsetDp == 0) return value
        val controller = readInheritedField(flow, "this$0")
        val context = controller?.let(::readContext) ?: systemUiApplicationContext
        val density = context?.resources?.displayMetrics?.density
            ?: Resources.getSystem().displayMetrics.density
        val offsetPx = (offsetDp * density).roundToInt()
        if (offsetPx == 0) return value
        // This is a screen-space bottom boundary: positive values move it upward, while
        // negative values move it downward as requested by the setting.
        return runCatching {
            valueType.declaredConstructors.first { it.parameterCount == 3 }
                .apply { isAccessible = true }
                .newInstance(parts[0], second - offsetPx, parts[2])
        }.getOrDefault(value)
    }

    private fun installLockscreenMediaNotificationHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        runCatching {
            installLockscreenMediaManagerBridgeHooks(classLoader)
            installLockscreenMediaControllerVisibilityHook(classLoader, preferences)
            installLockscreenMediaHeaderHook(classLoader, preferences)
            installLockscreenLyricButtonHook(classLoader, preferences)
            runCatching {
                installLockscreenLyricButtonStatusBarStateHook(classLoader, preferences)
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lyric-button status-bar state hook", error)
            }
            installLockscreenMediaEffectRefreshHook(classLoader)
            installLockscreenCustomizationMenuHook(classLoader)
            installLockscreenMediaVisibilityProviderHooks(classLoader, preferences)
            installLockscreenMediaPipelineHook(classLoader, preferences)
            installTinyLockscreenMediaHook(classLoader, preferences)
            installLockscreenMediaRowVisibilityHook(classLoader, preferences)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen media-notification header/row/pipeline hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen media-notification visibility hook", error)
        }
    }

    /**
     * Keep the actual lockscreen notification row in the same presentation state as the vendor
     * media header. This is the dynamic path used by 1.1.1: hide only while the row is assigned
     * to keyguard, then immediately hand the same row back when the island is tapped.
     *
     * The media entry remains in the pipeline for dynamic presentation, so restoring visibility
     * cannot produce the blank state caused by filtering an entry that SystemUI has already
     * discarded.
     */
    private fun installLockscreenMediaRowVisibilityHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val rowClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_CLASS)
            val getEntry = rowClass.getMethod("getEntry")
            val setOnKeyguard = rowClass.getMethod("setOnKeyguard", Boolean::class.javaPrimitiveType)
            val setVisibility = rowClass.declaredMethods.firstOrNull {
                it.name == "setVisibility" && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            } ?: error("ExpandableNotificationRow.setVisibility was not found")
            hook(setOnKeyguard)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:row-keyguard")
                .intercept { chain ->
                    val result = chain.proceed()
                    val row = chain.thisObject as? View ?: return@intercept result
                    val onKeyguard = chain.getArg(0) as? Boolean ?: false
                    if (onKeyguard) {
                        lockscreenRows += row
                        if (isMediaRow(row, getEntry)) lockscreenMediaRows += row
                    } else {
                        lockscreenRows -= row
                        lockscreenMediaRows -= row
                        if (lockscreenHiddenRows.remove(row)) row.visibility = View.VISIBLE
                    }
                    if (onKeyguard && shouldHideLockscreenMedia(preferences) &&
                        isMediaRow(row, getEntry)
                    ) {
                        lockscreenHiddenRows += row
                        row.visibility = View.GONE
                    }
                    result
                }
            hook(setVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:row-visibility")
                .intercept { chain ->
                    val row = chain.thisObject as? View
                    val requested = chain.getArg(0) as? Int
                    if (row != null && requested != null && requested != View.GONE &&
                        row in lockscreenRows && shouldHideLockscreenMedia(preferences) &&
                        isMediaRow(row, getEntry)
                    ) {
                        lockscreenHiddenRows += row
                        chain.proceedWith(arrayOf(View.GONE))
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen media notification-row dynamic visibility hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen media notification-row visibility hook", error)
        }
    }

    /**
     * The current HyperOS media card is a MiuiMediaHeaderView, not an
     * ExpandableNotificationRow.  Its controller owns both the view state and the notification
     * stack's add/remove bookkeeping through `visibilityChangedListener`.  Changing the view
     * alone leaves that bookkeeping at VISIBLE until a keyguard rebuild, which is why the old
     * implementation only appeared to work after leaving the lockscreen and coming back.
     */
    private fun installLockscreenMediaControllerVisibilityHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl",
            )
            val setVisibility = controllerClass.getMethod(
                "setVisibility",
                Boolean::class.javaPrimitiveType,
            )
            hook(setVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:media-controller-visibility")
                .intercept { chain ->
                    val controller = chain.thisObject
                    val header = readInstanceField(controller, "mediaContainerView") as? View
                    if (header != null) {
                        lockscreenMediaControllers[header] = controller
                        lockscreenMediaHeaders += header
                        installLockscreenMediaHeaderGuard(header, preferences)
                    }
                    // The system invokes setVisibility(true) again when its keyguard callback
                    // arrives. Rewrite that request before the controller examines the current
                    // state, so it emits the matching remove callback instead of briefly
                    // resurrecting a card that the island has already replaced.
                    val args = chain.args.toTypedArray()
                    if (header != null && mediaHeaderOnActiveKeyguard(header) &&
                        shouldHideLockscreenMedia(preferences) && args[0] == true
                    ) {
                        args[0] = false
                    }
                    chain.proceed(args)
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed MiuiMediaNotificationController visibility hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install media-controller visibility hook", error)
        }
    }

    private fun installLockscreenMediaManagerBridgeHooks(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val managerClass = classLoader.loadClass("com.android.systemui.media.NotificationMediaManager")
            val methods = managerClass.declaredMethods.filter {
                (it.name == "findAndUpdateMediaNotifications" || it.name == "dispatchUpdateMediaMetaData") &&
                    it.parameterCount == 0
            }
            check(methods.isNotEmpty()) { "NotificationMediaManager media-update methods were not found" }
            methods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-media-manager-bridge-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val controller = readInstanceField(chain.thisObject, "mMediaController") as?
                            android.media.session.MediaController
                        val key = readInstanceField(chain.thisObject, "mMediaNotificationKey") as? String
                        LockscreenMediaBridge.update(controller, key)
                        result
                }
            }
            // The manager commits mMediaController/mMediaNotificationKey in an asynchronous
            // synthetic Runnable, after the public update method has already returned.
            runCatching {
                val updateRunnable = classLoader.loadClass(
                    "com.android.systemui.media.NotificationMediaManager\$\$ExternalSyntheticLambda6",
                )
                hook(updateRunnable.getMethod("run"))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-media-manager-bridge:commit")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val manager = readInstanceField(chain.thisObject, "f\$0")
                        if (manager != null) {
                            val controller = readInstanceField(manager, "mMediaController") as?
                                android.media.session.MediaController
                            val key = readInstanceField(manager, "mMediaNotificationKey") as? String
                            LockscreenMediaBridge.update(controller, key)
                        }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed NotificationMediaManager controller bridge")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install NotificationMediaManager controller bridge", error)
        }
    }

    /**
     * HyperOS renders the lockscreen media notification through MiuiMediaHeaderView. This view
     * is not an ExpandableNotificationRow, so the normal notification-row visibility hooks do
     * not see it. Keep the keyguard state in sync and force this media-only view to GONE while
     * the user has requested that the system media notification be hidden.
     */
    private fun installLockscreenMediaHeaderHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val headerClass = classLoader.loadClass(
                MIUI_MEDIA_HEADER_VIEW_CLASS,
            )
            val previousPresentationCallback = LockscreenMediaPresentationBridge.onPresentationChanged
            LockscreenMediaPresentationBridge.onPresentationChanged = { presentation ->
                previousPresentationCallback?.invoke(presentation)
                applyLockscreenMediaPresentation(preferences)
            }
            // The first lockscreen island is commonly attached before either the media-data or
            // keyguard callback arrives. Port Main.guardCard's timing: own the real header from
            // construction and correct it on every traversal until it is handed back to shade.
            headerClass.declaredConstructors.forEachIndexed { index, constructor ->
                hook(constructor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-media-presentation:header-construct-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { header ->
                            lockscreenMediaHeaders += header
                            installLockscreenMediaHeaderGuard(header, preferences)
                        }
                        result
                    }
            }
            // The media controller's keyguard callback is a generated nested class on this ROM.
            // KeyguardManager is not reliable from the SystemUI process during transitions, so
            // mirror the callback's boolean state instead.
            runCatching {
                val callbackClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl\$keyguardUpdateMonitorCallback\$1",
                )
                val stateMethods = callbackClass.declaredMethods.filter { method ->
                    method.name.lowercase(java.util.Locale.ROOT).contains("keyguard") &&
                        method.parameterTypes.any { it == Boolean::class.javaPrimitiveType }
                }
                stateMethods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-hide-media-notification:keyguard-callback-$index")
                        .intercept { chain ->
                            val booleanIndex = method.parameterTypes.indexOfFirst {
                                it == Boolean::class.javaPrimitiveType
                            }
                            if (booleanIndex >= 0) {
                                lockscreenMediaKeyguardShowing = chain.getArg(booleanIndex) as? Boolean ?: false
                            }
                            val result = chain.proceed()
                            refreshLockscreenLyricButtons(preferences)
                            result
                        }
                }
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed media keyguard callback hook(s): ${stateMethods.size}")
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install media keyguard callback hook", error)
            }
            // The vendor controller writes the header's inherited View.visibility property
            // directly. Hook View.setVisibility and only apply a post-call correction when the
            // receiver is the actual media header. We deliberately keep the original call and
            // receiver untouched; replacing arguments on a shared View method can crash other
            // View subclasses inside SystemUI.
            val viewVisibility = View::class.java.getMethod(
                "setVisibility",
                Int::class.javaPrimitiveType,
            )
            hook(viewVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:media-header-visibility")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.takeIf(headerClass::isInstance)?.let {
                        lockscreenMediaHeaders += it
                        installLockscreenMediaHeaderGuard(it, preferences)
                    }
                    if (shouldHideLockscreenMedia(preferences) &&
                        headerClass.isInstance(chain.thisObject) &&
                        mediaHeaderOnActiveKeyguard(chain.thisObject as? View ?: return@intercept result) &&
                        (chain.thisObject as? View)?.visibility != View.GONE
                    ) {
                        updateLockscreenMediaHeaderVisibility(
                            chain.thisObject as View,
                            hidden = true,
                        )
                    }
                    result
                }

            // Some builds update the header after the keyguard callback and do not call
            // setVisibility again. Re-apply GONE after each media-data binding as a second guard.
            val dataMethods = headerClass.declaredMethods.filter { method ->
                method.name == "onMediaDataChanged" && method.parameterCount > 0
            }
            dataMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-hide-media-notification:media-header-data-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val header = (chain.thisObject as? View)?.takeIf(headerClass::isInstance)
                        if (header != null) {
                            lockscreenMediaHeaders += header
                            installLockscreenMediaHeaderGuard(header, preferences)
                            installLockscreenMediaArtworkClick(header, preferences)
                            bindLockscreenLyricButton(header, preferences)
                            scheduleLockscreenMediaPresentation(header, preferences)
                            header.postDelayed({
                                installLockscreenMediaArtworkClick(header, preferences)
                                bindLockscreenLyricButton(header, preferences)
                                scheduleLockscreenMediaPresentation(header, preferences)
                            }, 120L)
                        }
                        if (shouldHideLockscreenMedia(preferences) &&
                            mediaHeaderOnActiveKeyguard(chain.thisObject as? View ?: return@intercept result)
                        ) {
                            updateLockscreenMediaHeaderVisibility(
                                chain.thisObject as View,
                                hidden = true,
                            )
                        }
                        result
                    }
            }
            // The holder is assigned independently from media-data updates on this ROM.  Bind
            // after that assignment as well, otherwise the first shown media card can miss the
            // artwork listener entirely.
            val mediaViewHolderClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder",
            )
            val setMediaViewHolder = headerClass.getMethod(
                "setMediaViewHolder",
                mediaViewHolderClass,
            )
            hook(setMediaViewHolder)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-media-presentation:media-view-holder")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.takeIf(headerClass::isInstance)?.let { header ->
                        lockscreenMediaHeaders += header
                        installLockscreenMediaHeaderGuard(header, preferences)
                        installLockscreenMediaArtworkClick(header, preferences)
                        bindLockscreenLyricButton(header, preferences)
                        // Some holder children are attached on the next traversal.
                        header.post {
                            installLockscreenMediaArtworkClick(header, preferences)
                            bindLockscreenLyricButton(header, preferences)
                            scheduleLockscreenMediaPresentation(header, preferences)
                        }
                        header.postDelayed({
                            installLockscreenMediaArtworkClick(header, preferences)
                            bindLockscreenLyricButton(header, preferences)
                            scheduleLockscreenMediaPresentation(header, preferences)
                        }, 120L)
                    }
                    result
                }
            // updateLayout$1 reapplies ConstraintLayout and text appearances after every
            // configuration/media-data update. Re-apply our lockscreen-only presentation after
            // that vendor pass, otherwise the album and title alignment can randomly return.
            runCatching {
                val controllerClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl",
                )
                controllerClass.declaredMethods.filter {
                    it.name == "updateLayout\$1" && it.parameterCount == 0
                }.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-media-presentation:update-layout-$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val header = readInstanceField(chain.thisObject, "mediaContainerView") as? View
                            if (header != null) {
                                lockscreenMediaHeaders += header
                                installLockscreenMediaHeaderGuard(header, preferences)
                                scheduleLockscreenMediaPresentation(header, preferences)
                            }
                            result
                        }
                }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not install media update-layout presentation hook", error)
            }
            // `MiuiMediaViewControllerImpl` owns the drawable that the lockscreen island will
            // animate into albumImageView. Capture that source immediately after binding rather
            // than waiting for the ImageView's later flip-animation callback. On a SystemUI
            // restart this is the only point at which the first track's art is guaranteed to be
            // available before the custom music surface is shown.
            installLockscreenIslandArtworkBridge(classLoader, headerClass, preferences)
            btm.m.os4.systemuihook.HookModuleLogger.log(this,
                Log.INFO,
                TAG,
                "Installed MiuiMediaHeaderView lockscreen hide hook " +
                    "(dataMethods=${dataMethods.size}, visibility=View.setVisibility, artwork=true)",
            )
            applyLockscreenMediaPresentation(preferences)
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install MiuiMediaHeaderView lockscreen hide hook", error)
        }
    }

    private fun installLockscreenIslandArtworkBridge(
        classLoader: ClassLoader,
        headerClass: Class<*>,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl",
            )
            val mediaDataClass = classLoader.loadClass(
                "com.android.systemui.media.controls.shared.model.MediaData",
            )
            val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)
            hook(bindMediaData)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-media-artwork:controller-bind")
                .intercept { chain ->
                    val result = chain.proceed()
                    val holder = readInstanceField(chain.thisObject, "holder")
                    val player = holder?.let { readInstanceField(it, "player") as? View }
                    val header = findLockscreenMediaHeader(player, headerClass)
                    if (header != null) {
                        lockscreenMediaHeaders += header
                        installLockscreenMediaHeaderGuard(header, preferences)
                        // The animation can replace the displayed drawable one traversal later;
                        // repeat through the same source path without requiring a track switch.
                        player?.post {
                            scheduleLockscreenMediaPresentation(header, preferences)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen-island artwork source hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen-island artwork source hook", error)
        }
    }

    /** Reuses HyperOS' right custom action slot, matching HyperLockMusic's lockscreen-only button. */
    private fun installLockscreenLyricButtonHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl",
            )
            val mediaDataClass = classLoader.loadClass(
                "com.android.systemui.media.controls.shared.model.MediaData",
            )
            val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)
            hook(bindMediaData)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-lyrics-button:media-data")
                .intercept { chain ->
                    val result = chain.proceed()
                    bindLockscreenLyricButtonForHolder(
                        readInstanceField(chain.thisObject, "holder"),
                        preferences,
                    )
                    result
                }

            // On this SystemUI build, attach() is the first callback for a recreated media
            // card.  It is not guaranteed that bindMediaData() will follow, especially after
            // playback is paused, so bind the lyric action from both lifecycle points.
            runCatching {
                val holderClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder",
                )
                val attach = controllerClass.getMethod("attach", holderClass)
                hook(attach)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-lyrics-button:attach")
                    .intercept { chain ->
                        val result = chain.proceed()
                        bindLockscreenLyricButtonForHolder(chain.getArg(0), preferences)
                        result
                    }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not bind lyric button from media attach", error)
            }

            // access$setTopMediaData() adds holder.player to MiuiMediaHeaderView, attaches the
            // controller, dispatches header listeners, and then binds the media data.  It is the
            // first point at which all three objects are present for an initially created card.
            // bindMediaData() alone is too early on a cold SystemUI start, which was why the
            // button appeared only after unlocking and locking again.
            runCatching {
                val notificationControllerClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl",
                )
                val topMediaChanged = notificationControllerClass.getDeclaredMethod(
                    "access\$setTopMediaData",
                    notificationControllerClass,
                    mediaDataClass,
                ).apply { isAccessible = true }
                hook(topMediaChanged)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-lyrics-button:top-media-data")
                    .intercept { chain ->
                        val result = chain.proceed()
                        bindLockscreenLyricButtonForController(chain.getArg(0), preferences)
                        result
                    }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not bind lyric button after top-media update", error)
            }

            val mediaActionClass = classLoader.loadClass(
                "com.android.systemui.media.controls.shared.model.MediaAction",
            )
            runCatching {
                val bindButtonCommon = controllerClass.getDeclaredMethod(
                    "bindButtonCommon",
                    ImageButton::class.java,
                    mediaActionClass,
                )
                hook(bindButtonCommon)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-lyrics-button:block-custom")
                    .intercept { chain ->
                        val button = chain.getArg(0) as? ImageButton
                        if (button != null && shouldOwnLockscreenLyricSlot(button, preferences)) {
                            null
                        } else {
                            chain.proceed()
                        }
                    }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not guard lyric button bindButtonCommon", error)
            }

            runCatching {
                val utilsClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaActionButtonUtils",
                )
                val setSemanticButton = utilsClass.getDeclaredMethod(
                    "setSemanticButton",
                    ImageButton::class.java,
                    mediaActionClass,
                )
                hook(setSemanticButton)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-lyrics-button:block-semantic")
                    .intercept { chain ->
                        val button = chain.getArg(0) as? ImageButton
                        if (button != null && shouldOwnLockscreenLyricSlot(button, preferences)) {
                            null
                        } else {
                            chain.proceed()
                        }
                    }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not guard lyric semantic button binding", error)
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen media lyric-button hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen media lyric-button hook", error)
        }
    }

    private fun bindLockscreenLyricButton(
        header: View,
        preferences: SharedPreferences,
        holderOverride: Any? = null,
    ) {
        val holder = holderOverride ?: readInstanceField(header, "mediaViewHolder") ?: return
        val button = readInstanceField(holder, "action4") as? ImageButton ?: return
        lockscreenLyricButtons += button
        lockscreenLyricButtonHeaders[button] = header
        if (!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false)) {
            if (button.getTag(LOCKSCREEN_LYRIC_BUTTON_TAG) == true) {
                applyEmptyLockscreenMediaButton(button)
            }
            return
        }
        if (!shouldShowLockscreenLyricButton(header)) {
            applyEmptyLockscreenMediaButton(button)
            return
        }

        button.setTag(LOCKSCREEN_LYRIC_BUTTON_TAG, true)
        button.visibility = View.VISIBLE
        button.isEnabled = true
        val showing = lockscreenLyricsShowing(button.context) &&
            LockscreenMediaPresentationBridge.presentation ==
            LockscreenMediaPresentation.LYRICS_LOCKSCREEN
        val drawable = loadLockscreenLyricButtonDrawable(button.context, showing)
        button.setImageDrawable(drawable)
        button.imageTintList = ColorStateList.valueOf(Color.WHITE)
        button.isSelected = showing
        button.alpha = 1f
        button.contentDescription = if (showing) {
            tr(button.context, "隐藏歌词", "隐藏歌词")
        } else {
            tr(button.context, "显示歌词", "显示歌词")
        }
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        button.setTag(LOCKSCREEN_LYRIC_BUTTON_STATE_TAG, showing)
        button.setTag(LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG, drawable)
        button.setOnClickListener {
            if (!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false) ||
                !shouldShowLockscreenLyricButton(header)
            ) return@setOnClickListener
            systemUiApplicationContext = it.context.applicationContext
            val next = LockscreenMediaPresentationBridge.presentation !=
                LockscreenMediaPresentation.LYRICS_LOCKSCREEN
            if (next) {
                // Remember the presentation that lyrics temporarily overlays. A later press
                // must return to that exact state instead of always forcing SYSTEM_MEDIA.
                lyricReturnPresentation = when (
                    LockscreenMediaPresentationBridge.presentation
                ) {
                    LockscreenMediaPresentation.MUSIC_LOCKSCREEN,
                    LockscreenMediaPresentation.MUSIC_LOCKSCREEN_STYLE2 ->
                        LockscreenMediaPresentationBridge.presentation
                    else -> LockscreenMediaPresentation.SYSTEM_MEDIA
                }
            }
            setLockscreenLyricsShowing(it.context, next)
            syncedHyperMusicLyricsEnabled = null
            val target = if (next) {
                // The lyric renderer lives in the full music-lockscreen presentation. The
                // injected system-media button is its entry point, so enter that presentation
                // in the same click instead of requiring a second artwork tap.
                LockscreenMediaPresentation.LYRICS_LOCKSCREEN
            } else {
                lyricReturnPresentation
            }
            if (LockscreenMediaPresentationBridge.presentation == target) {
                // setPresentation() intentionally ignores equal values. The button still has to
                // apply a lyric-mode change when another control already selected this layout.
                dispatchHyperMusicCoverState(preferences, target)
                syncHyperMusicCoverLyrics(preferences, it.context.applicationContext)
                refreshLockscreenLyricButtons(preferences)
            } else {
                LockscreenMediaPresentationBridge.setPresentation(target)
            }
        }
    }

    private fun bindLockscreenLyricButtonForHolder(holder: Any?, preferences: SharedPreferences) {
        val player = holder?.let { readInstanceField(it, "player") as? View }
        val header = findMiuiMediaHeaderAncestor(player)
            ?: findRegisteredLockscreenMediaHeader(holder)
            ?: return
        lockscreenMediaHeaders += header
        bindLockscreenLyricButton(header, preferences)
        // The controller can overwrite action4 after attach(), so repeat after the layout pass.
        header.post { bindLockscreenLyricButton(header, preferences) }
        header.postDelayed({ bindLockscreenLyricButton(header, preferences) }, 120L)
    }

    private fun bindLockscreenLyricButtonForController(
        controller: Any?,
        preferences: SharedPreferences,
    ) {
        controller ?: return
        val header = readInstanceField(controller, "mediaContainerView") as? View ?: return
        val holder = readInstanceField(controller, "mediaViewHolder")
        bindLockscreenLyricButton(header, preferences, holder)
        // The first card can still receive a delayed vendor action pass after the top-media
        // transaction, so retain the post-layout repairs for that single creation frame.
        header.post { bindLockscreenLyricButton(header, preferences, holder) }
        header.postDelayed({ bindLockscreenLyricButton(header, preferences, holder) }, 120L)
    }

    private fun findRegisteredLockscreenMediaHeader(holder: Any?): View? {
        holder ?: return null
        return synchronized(lockscreenMediaHeaders) {
            lockscreenMediaHeaders.firstOrNull { header ->
                readInstanceField(header, "mediaViewHolder") === holder
            }
        }
    }

    private fun applyEmptyLockscreenMediaButton(button: ImageButton) {
        button.setTag(LOCKSCREEN_LYRIC_BUTTON_TAG, null)
        button.setTag(LOCKSCREEN_LYRIC_BUTTON_STATE_TAG, null)
        button.setTag(LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG, null)
        button.setOnClickListener(null)
        button.visibility = View.VISIBLE
        button.isEnabled = false
        button.isSelected = false
        button.setImageDrawable(null)
        button.contentDescription = null
        button.alpha = 1f
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun refreshLockscreenLyricButtons(preferences: SharedPreferences) {
        val bindings = synchronized(lockscreenLyricButtonHeaders) {
            lockscreenLyricButtonHeaders.entries.map { it.key to it.value }
        }
        bindings.forEach { (button, knownHeader) ->
            val header = findMiuiMediaHeaderAncestor(button) ?: knownHeader
            if (header == null || !button.isAttachedToWindow || !header.isAttachedToWindow) {
                lockscreenLyricButtons.remove(button)
                lockscreenLyricButtonHeaders.remove(button)
            } else {
                bindLockscreenLyricButton(header, preferences)
            }
        }
    }

    private fun maintainLockscreenLyricButton(header: View, preferences: SharedPreferences) {
        val holder = readInstanceField(header, "mediaViewHolder") ?: return
        val button = readInstanceField(holder, "action4") as? ImageButton ?: return
        val featureEnabled = preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false)
        if (!featureEnabled || !shouldShowLockscreenLyricButton(header)) {
            if (button.getTag(LOCKSCREEN_LYRIC_BUTTON_TAG) == true) {
                applyEmptyLockscreenMediaButton(button)
            }
            return
        }
        val showing = lockscreenLyricsShowing(button.context) &&
            LockscreenMediaPresentationBridge.presentation ==
            LockscreenMediaPresentation.LYRICS_LOCKSCREEN
        val ownedDrawable = button.getTag(LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG) as? Drawable
        val needsRepair = button.getTag(LOCKSCREEN_LYRIC_BUTTON_TAG) != true ||
            button.getTag(LOCKSCREEN_LYRIC_BUTTON_STATE_TAG) != showing ||
            ownedDrawable == null || button.drawable !== ownedDrawable ||
            button.visibility != View.VISIBLE || !button.isEnabled
        if (needsRepair) bindLockscreenLyricButton(header, preferences)
    }

    /**
     * Keep this independent from NotificationStackScrollLayout's expanded-height state.
     * During the first keyguard media transaction HyperOS reports a non-zero expanded height
     * before it has changed mStatusBarState to KEYGUARD; treating that transient as the shade
     * is the exact first-card race that hides the lyric icon until a later relock.  The media
     * header's own Context gives us the stable signal used by HyperLockMusic instead.
     */
    private fun shouldShowLockscreenLyricButton(header: View): Boolean =
        isLockscreenMediaView(header)

    private fun installLockscreenLyricButtonStatusBarStateHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val candidates = listOf(
            "com.android.systemui.statusbar.StatusBarStateControllerImpl",
            "com.android.systemui.statusbar.policy.StatusBarStateControllerImpl",
        )
        val controllerClass = candidates.firstNotNullOfOrNull { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()
        } ?: run {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not find status-bar state controller for lyric button")
            return
        }
        val methods = generateSequence(controllerClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                method.name == "setState" && method.parameterTypes.firstOrNull() ==
                    Int::class.javaPrimitiveType
            }
            .distinctBy { method -> method.parameterTypes.toList() }
            .toList()
        methods.forEachIndexed { index, method ->
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-lyrics-button:status-bar-state-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (chain.getArg(0) is Int) {
                        refreshLockscreenLyricButtons(preferences)
                    }
                    result
                }
        }
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lyric-button status-bar state hook(s): ${methods.size}")
    }

    private fun shouldOwnLockscreenLyricSlot(
        button: ImageButton,
        preferences: SharedPreferences,
    ): Boolean {
        if (!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false)) return false
        val action4Id = button.resources.getIdentifier("action4", "id", SYSTEM_UI)
        return action4Id != 0 && button.id == action4Id &&
            (lockscreenLyricButtons.contains(button) ||
                lockscreenLyricButtonHeaders.containsKey(button) ||
                findMiuiMediaHeaderAncestor(button) != null)
    }

    private fun findMiuiMediaHeaderAncestor(view: View?): View? {
        var current: ViewParent? = view?.parent
        while (current != null) {
            val candidate = current as? View
            if (candidate != null && isMiuiMediaHeaderView(candidate)) return candidate
            current = current.parent
        }
        return null
    }

    private fun lockscreenLyricsShowing(context: Context): Boolean =
        context.getSharedPreferences(LOCKSCREEN_LYRIC_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(LOCKSCREEN_LYRIC_STATE_SHOWING, false)

    private fun setLockscreenLyricsShowing(context: Context?, showing: Boolean) {
        context ?: return
        context.getSharedPreferences(LOCKSCREEN_LYRIC_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LOCKSCREEN_LYRIC_STATE_SHOWING, showing)
            .apply()
    }

    private fun loadLockscreenLyricButtonDrawable(
        systemUiContext: Context,
        showing: Boolean,
    ): Drawable? = runCatching {
        val moduleContext = systemUiContext.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        // Use generated resource constants instead of a string-only lookup. R8/resource
        // shrinking cannot reliably prove getIdentifier() calls are reachable in a release APK,
        // while these direct references keep both selectors and their normal/pressed children.
        val resourceId = if (showing) {
            R.drawable.ic_lockscreen_media_lyrics_on
        } else {
            R.drawable.ic_lockscreen_media_lyrics_off
        }
        moduleContext.getDrawable(resourceId)?.mutate()
            ?: error("Could not inflate lockscreen lyric button drawable: $resourceId")
    }.onFailure { error ->
        if (!lockscreenLyricDrawableLoadFailureLogged) {
            lockscreenLyricDrawableLoadFailureLogged = true
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not load lockscreen lyric button drawable", error)
        }
    }.getOrNull()

    private fun tr(context: Context, key: String, fallback: String): String {
        val language = context.resources.configuration.locales.get(0)?.language.orEmpty()
        if (language != "en" && language != "ja") return fallback
        val strings = synchronized(systemUiTranslationCache) {
            systemUiTranslationCache.getOrPut(language) {
                runCatching {
                    val moduleContext = context.createPackageContext(
                        BuildConfig.APPLICATION_ID,
                        Context.CONTEXT_IGNORE_SECURITY,
                    )
                    val json = JSONObject(
                        moduleContext.assets.open("languages/$language.json")
                            .bufferedReader()
                            .use { it.readText() },
                    ).getJSONObject("strings")
                    buildMap {
                        json.keys().forEach { translationKey ->
                            put(translationKey, json.optString(translationKey))
                        }
                    }
                }.getOrDefault(emptyMap())
            }
        }
        return strings[key].orEmpty().ifEmpty { fallback }
    }

    private fun installLockscreenCustomizationMenuHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val interactorClass = classLoader.loadClass(
                "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor",
            )
            val onLongPress = interactorClass.getMethod("onLongPress")
            hook(onLongPress)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-mini-player-customization-menu:show")
                .intercept { chain ->
                    val result = chain.proceed()
                    LockscreenCustomizationMenuBridge.setVisible(true)
                    result
                }
            val hideMenu = interactorClass.getMethod("hideMenu")
            hook(hideMenu)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-mini-player-customization-menu:hide")
                .intercept { chain ->
                    val result = chain.proceed()
                    LockscreenCustomizationMenuBridge.setVisible(false)
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen customization-menu animation hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen customization-menu hooks", error)
        }
    }

    private fun installLockscreenMediaVisibilityProviderHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val entryClass = classLoader.loadClass(
            "com.android.systemui.statusbar.notification.collection.NotificationEntry",
        )
        val providerNames = listOf(
            "com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl",
            "com.android.systemui.statusbar.notification.interruption.MiuiKeyguardNotificationVisibilityProvider",
        )
        providerNames.forEach { className ->
            runCatching {
                val providerClass = classLoader.loadClass(className)
                val methods = providerClass.declaredMethods.filter { method ->
                    method.name == "shouldHideNotification" &&
                        method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes[0].isAssignableFrom(entryClass)
                }
                methods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-hide-media-notification:provider:${className.substringAfterLast('.')}:$index")
                        .intercept { chain ->
                            val lockState = if (method.parameterCount > 1 &&
                                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                            ) {
                                chain.getArg(1) as? Boolean == true
                            } else {
                                true
                            }
                            if (lockState && shouldFilterLockscreenMedia(preferences) &&
                                isMediaEntry(chain.getArg(0))
                            ) {
                                true
                            } else {
                                chain.proceed()
                            }
                        }
                }
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed $className media visibility hook(s): ${methods.size}")
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install $className media visibility hook", error)
            }
        }
    }

    /**
     * Filter media entries before the lockscreen notification list is rendered. On recent
     * SystemUI builds a media row can be promoted directly by the notification pipeline, so
     * hiding only ExpandableNotificationRow.setVisibility is too late.
     */
    private fun installLockscreenMediaPipelineHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val filterClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.coordinator.KeyguardCoordinator\$notifFilter\$1",
            )
            val shouldFilterOut = filterClass.getDeclaredMethod(
                "shouldFilterOut",
                classLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry"),
                Long::class.javaPrimitiveType,
            )
            hook(shouldFilterOut)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:pipeline")
                .intercept { chain ->
                    val entry = chain.getArg(0)
                    if (shouldFilterLockscreenMedia(preferences) &&
                        isKeyguardCoordinatorOnKeyguard(chain.thisObject) &&
                        isMediaEntry(entry)
                    ) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen media-notification pipeline filter")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen media-notification pipeline filter", error)
        }
    }

    /**
     * HyperOS's tiny lockscreen panel builds its media card directly from MediaData rather than a
     * notification row. Filtering NotificationEntry alone therefore leaves that card visible.
     */
    private fun installTinyLockscreenMediaHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val dataStoreClass = classLoader.loadClass(
                "com.android.notification.tinypanel.FlipNotifDataStore",
            )
            val onMediaUpdate = dataStoreClass.declaredMethods.firstOrNull {
                it.name == "onMediaUpdate" && it.parameterCount == 1
            } ?: error("FlipNotifDataStore.onMediaUpdate was not found")
            hook(onMediaUpdate)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:tiny-panel-update")
                .intercept { chain ->
                    if (shouldFilterLockscreenMedia(preferences)) {
                        chain.proceedWith(arrayOf<Any?>(null))
                    } else {
                        chain.proceed()
                    }
                }
            val merge = dataStoreClass.declaredMethods.firstOrNull {
                it.name == "merge" && it.parameterCount == 3
            } ?: error("FlipNotifDataStore.merge was not found")
            hook(merge)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:tiny-panel-merge")
                .intercept { chain ->
                    if (shouldFilterLockscreenMedia(preferences)) {
                        chain.proceedWith(arrayOf(chain.getArg(0), null, chain.getArg(2)))
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed tiny lockscreen media-notification hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install tiny lockscreen media-notification hooks", error)
        }
    }

    private fun lockscreenMediaNotificationMode(preferences: SharedPreferences): Int = when {
        preferences.contains(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE) ->
            preferences.getInt(
                KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
                LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
            ).coerceIn(LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE, LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC)
        preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION, false) ->
            LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE
        else -> LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
    }

    private fun shouldHideLockscreenMedia(preferences: SharedPreferences): Boolean {
        if (preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)) {
            // The three explicit gesture states own media-header visibility while the imported
            // music-lockscreen feature is enabled. MINI_PLAYER hides the system card immediately;
            // SYSTEM_MEDIA must always be able to restore it after a lockscreen-island tap.
            return LockscreenMediaPresentationBridge.presentation ==
                LockscreenMediaPresentation.MINI_PLAYER ||
                LockscreenMediaPresentationBridge.presentation ==
                LockscreenMediaPresentation.MUSIC_LOCKSCREEN_STYLE2
        }
        return preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) && when (
            lockscreenMediaNotificationMode(preferences)
        ) {
            LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE -> true
            LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC ->
                LockscreenMediaPresentationBridge.presentation == LockscreenMediaPresentation.MINI_PLAYER
            else -> false
        }
    }

    /** Dynamic mode must retain the notification entry so it can be shown after a card tap. */
    private fun shouldFilterLockscreenMedia(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) &&
            !preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false) &&
            lockscreenMediaNotificationMode(preferences) == LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE

    private fun applyLockscreenMediaPresentation(preferences: SharedPreferences) {
        val hidden = shouldHideLockscreenMedia(preferences)
        val headers = synchronized(lockscreenMediaHeaders) { lockscreenMediaHeaders.toList() }
        headers.forEach { header ->
            if (!isMiuiMediaHeaderView(header)) {
                lockscreenMediaHeaders.remove(header)
                return@forEach
            }
            // The bridge is called from the gesture's UI-thread event. Apply synchronously so
            // a long press that opens the island cannot leave the system media card visible for
            // a frame (or until the next lockscreen lifecycle event). The scheduled passes below
            // are retained solely to counter later vendor layout/visibility writes.
            if (mediaHeaderOnActiveKeyguard(header)) {
                updateLockscreenMediaHeaderVisibility(
                    header,
                    hidden,
                )
            }
            scheduleLockscreenMediaPresentation(header, preferences)
        }
        // Restore the retained entry synchronously on the island's short tap. Waiting for
        // setOnKeyguard() to run again is the stale-GONE failure that required a relock.
        lockscreenMediaRows.toList().forEach { row ->
            if (row !in lockscreenRows) return@forEach
            if (hidden) {
                lockscreenHiddenRows += row
                if (row.visibility != View.GONE) row.visibility = View.GONE
            } else if (lockscreenHiddenRows.remove(row)) {
                row.visibility = View.VISIBLE
            }
        }
    }

    private fun updateLockscreenMediaHeaderVisibility(header: View, hidden: Boolean) {
        if (!isMiuiMediaHeaderView(header)) return
        val visible = !hidden
        val previousTarget = lockscreenMediaTransitionTargets[header]
        if (previousTarget == visible) {
            if (header in lockscreenMediaTransitionsRunning) return
            if (visible && header.visibility == View.VISIBLE &&
                header.alpha >= LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_ALPHA &&
                header.scaleX >= LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_SCALE &&
                header.scaleY >= LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_SCALE
            ) return
            if (!visible && header.visibility != View.VISIBLE) return
        }

        lockscreenMediaTransitionTargets[header] = visible
        val generation = (lockscreenMediaTransitionGenerations[header] ?: 0) + 1
        lockscreenMediaTransitionGenerations[header] = generation
        lockscreenMediaTransitionsRunning += header
        header.animate().cancel()
        animateLockscreenMediaHeaderContents(header, visible)
        val baseTranslationY = lockscreenMediaTransitionBaseTranslationY[header]
            ?: header.translationY.also { lockscreenMediaTransitionBaseTranslationY[header] = it }
        val travel = header.resources.displayMetrics.density *
            LOCKSCREEN_MEDIA_TRANSITION_TRAVEL_DP

        if (visible) {
            val wasNotVisible = header.visibility != View.VISIBLE
            if (!requestLockscreenMediaControllerVisibility(header, true)) {
                header.visibility = View.VISIBLE
            }
            if (wasNotVisible || header.alpha <= LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA) {
                header.alpha = 0f
                header.scaleX = LOCKSCREEN_MEDIA_TRANSITION_START_SCALE
                header.scaleY = LOCKSCREEN_MEDIA_TRANSITION_START_SCALE
                header.translationY = baseTranslationY + travel
            }
            reapplyNativeMediaEffect(header)
            header.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(baseTranslationY)
                .setDuration(LOCKSCREEN_MEDIA_SHOW_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.45f))
                .withEndAction {
                    if (lockscreenMediaTransitionGenerations[header] != generation ||
                        lockscreenMediaTransitionTargets[header] != true
                    ) return@withEndAction
                    lockscreenMediaTransitionsRunning -= header
                    header.alpha = 1f
                    header.scaleX = 1f
                    header.scaleY = 1f
                    reapplyNativeMediaEffect(header)
                }
                .start()
        } else {
            if (header.visibility != View.VISIBLE ||
                header.alpha <= LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA
            ) {
                if (!requestLockscreenMediaControllerVisibility(header, false)) {
                    header.visibility = View.GONE
                }
                lockscreenMediaTransitionsRunning -= header
                return
            }
            header.animate()
                .alpha(0f)
                .scaleX(LOCKSCREEN_MEDIA_TRANSITION_START_SCALE)
                .scaleY(LOCKSCREEN_MEDIA_TRANSITION_START_SCALE)
                .translationY(baseTranslationY + travel)
                .setDuration(LOCKSCREEN_MEDIA_HIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    if (lockscreenMediaTransitionGenerations[header] != generation ||
                        lockscreenMediaTransitionTargets[header] != false
                    ) return@withEndAction
                    if (!requestLockscreenMediaControllerVisibility(header, false)) {
                        header.visibility = View.GONE
                    }
                    lockscreenMediaTransitionsRunning -= header
                }
                .start()
        }
    }

    /**
     * The MIUI header and the imported cover use the same media data, but they are separate
     * view trees. Animate the real header children as well so returning from the music lockscreen
     * does not reveal an already-snapped album/title/artist row underneath the header fade.
     */
    private fun animateLockscreenMediaHeaderContents(header: View, systemVisible: Boolean) {
        val holder = readInstanceField(header, "mediaViewHolder") ?: return
        val album = readInstanceField(holder, "albumImageView") as? View
        val title = readInstanceField(holder, "titleText") as? TextView
        val artist = readInstanceField(holder, "artistText") as? TextView
        val duration = if (systemVisible) {
            LOCKSCREEN_MEDIA_SHOW_DURATION_MS
        } else {
            LOCKSCREEN_MEDIA_HIDE_DURATION_MS
        }
        album?.let { image ->
            if (systemVisible) {
                image.visibility = View.VISIBLE
                if (image.alpha <= LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA) {
                    image.alpha = 0f
                    image.scaleX = LOCKSCREEN_MEDIA_TRANSITION_START_SCALE
                    image.scaleY = LOCKSCREEN_MEDIA_TRANSITION_START_SCALE
                }
                image.animate().cancel()
                image.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.35f))
                    .start()
            } else {
                image.animate().cancel()
                image.animate()
                    .alpha(0f)
                    .scaleX(LOCKSCREEN_MEDIA_TRANSITION_START_SCALE)
                    .scaleY(LOCKSCREEN_MEDIA_TRANSITION_START_SCALE)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.2f))
                    .start()
            }
        }
        listOfNotNull(title, artist).forEach { text ->
            val base = lockscreenMediaTextBaseTranslations.getOrPut(text) { text.translationX }
            val target = if (systemVisible) base else centeredMediaTextTranslation(header, text, base)
            text.animate().cancel()
            text.animate()
                .translationX(target)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.25f))
                .start()
        }
    }

    private fun centeredMediaTextTranslation(header: View, text: TextView, base: Float): Float {
        val layout = text.layout ?: return base
        if (layout.lineCount == 0 || header.width <= 0) return base
        val width = layout.getLineWidth(0)
        if (width <= 0f) return base
        val currentInkStart = text.left + text.paddingLeft + layout.getLineLeft(0)
        return (header.width - width) / 2f - currentInkStart
    }

    /**
     * Ask SystemUI's owner to change the media card.  Calling this method rather than changing
     * the shared header directly is essential: its `visibilityChangedListener` generates the
     * remove/add animation and recalculates the notification stack in the same frame.
     */
    private fun requestLockscreenMediaControllerVisibility(header: View, visible: Boolean): Boolean {
        val controller = lockscreenMediaControllers[header]
            ?: readInstanceField(header, "mediaNotificationController")
            ?: return false
        return runCatching {
            val method = controller.javaClass.methods.firstOrNull {
                it.name == "setVisibility" &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            } ?: return false
            lockscreenMediaControllers[header] = controller
            method.invoke(controller, visible)
            true
        }.getOrElse { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not request native media-card visibility", error)
            false
        }
    }

    /** Re-assert after the vendor's asynchronous layout writes without waiting for a relock. */
    private fun scheduleLockscreenMediaPresentation(header: View, preferences: SharedPreferences) {
        val apply = Runnable {
            if (!header.isAttachedToWindow || !isMiuiMediaHeaderView(header)) return@Runnable
            if (!mediaHeaderOnActiveKeyguard(header)) return@Runnable
            updateLockscreenMediaHeaderVisibility(
                header,
                shouldHideLockscreenMedia(preferences),
            )
        }
        header.post(apply)
        header.postDelayed(apply, LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SHORT_DELAY_MS)
        header.postDelayed(apply, LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SETTLE_DELAY_MS)
    }

    private fun installLockscreenNativeClockScalerHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val timeView = classLoader.loadClass("com.miui.clock.allInOne.TimeView")
            timeView.declaredMethods.filter { method ->
                method.name == "setSizeInternal" &&
                    method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
            }.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-native-clock-size-$index")
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (args[0] is Float && chain.thisObject is View) {
                            args[0] = LockscreenNativeClockScaler.scaleFor(
                                chain.thisObject as View,
                                args[0] as Float,
                            )
                        }
                        chain.proceed(args)
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed native TimeView size scaler")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Native TimeView size scaler unavailable", error)
        }
    }

    /**
     * MediaViewBinder normally reapplies the material effect from a coroutine flow. During a
     * lockscreen-island/fullscreen transition that flow can emit while the shared header is
     * detached, leaving mediaBg with the clear() state after it is attached again. Re-apply the
     * same vendor effect whenever our presentation code makes the header visible.
     */
    private fun installLockscreenMediaEffectRefreshHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val binder = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.style.view.MediaViewBinder",
            )
            val bind = binder.getMethod(
                "bind",
                classLoader.loadClass(
                    MIUI_MEDIA_HEADER_VIEW_CLASS,
                ),
                classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor",
                ),
            )
            hook(bind)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-media-effect-refresh")
                .intercept { chain ->
                    val result = chain.proceed()
                    val header = chain.getArg(0) as? View
                    if (header != null) {
                        lockscreenMediaInteractors[header] = chain.getArg(1)
                        // The first collector emission can race attachment; a short post lets
                        // SystemUI finish adding mediaBg before the corrective apply runs.
                        header.post { reapplyNativeMediaEffect(header) }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed native media material-effect refresh hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install native media material-effect refresh hook", error)
        }
    }

    private fun reapplyNativeMediaEffect(header: View) {
        runCatching {
            if (!isMiuiMediaHeaderView(header) || !header.isAttachedToWindow) return
            val interactor = lockscreenMediaInteractors[header] ?: return
            val material = (readInstanceField(interactor, "materialType") as? Enum<*>)?.name ?: "NONE"
            val keyguard = mediaHeaderOnActiveKeyguard(header)
            val lightWallpaper = readStateFlowBoolean(interactor, "isLightWallPaper")
            val suffix = when {
                material == "GLASS" && keyguard && lightWallpaper -> "MediaViewGlassOnKeyguardLightWallPaperEffect"
                material == "GLASS" && keyguard -> "MediaViewGlassOnKeyguardEffect"
                material == "GLASS" -> "MediaViewGlassEffect"
                material == "BLUR" && keyguard -> "MediaViewBlurOnKeyguardEffect"
                material == "BLUR" -> "MediaViewBlurEffect"
                else -> "MediaViewNormalEffect"
            }
            val effectClass = header.javaClass.classLoader?.loadClass(
                "com.android.systemui.statusbar.notification.style.vieweffect.$suffix",
            ) ?: return
            val instance = effectClass.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                ?: effectClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
                    ?.apply { isAccessible = true }?.get(null)
                ?: return
            val apply = effectClass.methods.firstOrNull { it.name == "apply" && it.parameterCount == 2 }
                ?: return
            apply.invoke(instance, header, header.context)
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Native media material-effect refresh failed", error)
        }
    }

    private fun readStateFlowBoolean(owner: Any, fieldName: String): Boolean {
        val flow = readInstanceField(owner, fieldName) ?: return false
        val delegate = readInstanceField(flow, "\$\$delegate_0") ?: flow
        return (delegate.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?.invoke(delegate) as? Boolean) == true
    }

    private val lockscreenMediaInteractors = Collections.synchronizedMap(
        WeakHashMap<View, Any>(),
    )
    private val lockscreenMediaHeaderGuards = Collections.synchronizedMap(
        WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>(),
    )

    /** Main.guardCard equivalent for the actual MiuiMediaHeaderView. It handles the initial
     * island frame, where no later media-data callback exists to correct vendor writes. */
    private fun installLockscreenMediaHeaderGuard(header: View, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (lockscreenMediaHeaderGuards.containsKey(header)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!header.isAttachedToWindow) return@OnPreDrawListener true
            maintainLockscreenLyricButton(header, preferences)
            val onKeyguard = mediaHeaderOnActiveKeyguard(header)
            val hidden = shouldHideLockscreenMedia(preferences)
            if (onKeyguard && header.visibility != if (hidden) View.GONE else View.VISIBLE) {
                updateLockscreenMediaHeaderVisibility(header, hidden)
            }
            true
        }
        runCatching {
            header.viewTreeObserver.addOnPreDrawListener(listener)
            lockscreenMediaHeaderGuards[header] = listener
        }
    }

    private fun installLockscreenMediaArtworkClick(header: View, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        // The imported Java Main module owns the window-level artwork gesture, including the
        // long-press transition into MINI_PLAYER. Installing a second child listener here can
        // consume ACTION_DOWN/ACTION_UP before Main sees them; that race is especially visible
        // in minified release builds. Keep this method as a compatibility no-op.
    }

    private fun findLockscreenMediaHeader(player: View?, headerClass: Class<*>): View? {
        var parent: ViewParent? = player?.parent
        while (parent != null) {
            if (headerClass.isInstance(parent)) return parent as? View
            parent = parent.parent
        }
        return null
    }

    private fun drawableBitmap(
        drawable: Drawable,
        widthHint: Int = 0,
        heightHint: Int = 0,
    ): Bitmap? = runCatching {
        if (drawable is BitmapDrawable) return@runCatching drawable.bitmap
        val width = (widthHint.takeIf { it > 0 } ?: drawable.intrinsicWidth)
            .takeIf { it > 0 } ?: return@runCatching null
        val height = (heightHint.takeIf { it > 0 } ?: drawable.intrinsicHeight)
            .takeIf { it > 0 } ?: return@runCatching null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bounds = drawable.bounds
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = bounds
        bitmap
    }.getOrNull()

    private fun isLockscreenMediaView(target: Any?): Boolean = runCatching {
        val view = target as? View ?: return@runCatching false
        val keyguardManager = view.context.getSystemService(KeyguardManager::class.java)
            ?: return@runCatching false
        keyguardManager.isKeyguardLocked
    }.getOrDefault(false)

    private fun isMiuiMediaHeaderView(view: View): Boolean {
        var current: Class<*>? = view.javaClass
        while (current != null) {
            if (current.name == MIUI_MEDIA_HEADER_VIEW_CLASS) return true
            current = current.superclass
        }
        return false
    }

    private fun isMediaRow(row: View, getEntry: java.lang.reflect.Method): Boolean = runCatching {
        val entry = getEntry.invoke(row)
        if (isMediaEntry(entry)) return@runCatching true
        // Some HyperOS media rows are promoted to a vendor header before their
        // NotificationEntry is attached. Recognize that already-bound view as a fallback.
        fun containsMediaView(view: View): Boolean {
            val name = view.javaClass.name.lowercase(java.util.Locale.ROOT)
            if (name.contains("mediaheader") || name.contains("mediarow") ||
                name.contains("mediacontrol") || name.contains("mediaholder")
            ) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (containsMediaView(view.getChildAt(index))) return true
                }
            }
            return false
        }
        containsMediaView(row)
    }.getOrDefault(false)

    private fun isMediaEntry(entry: Any?): Boolean = runCatching {
        if (entry == null) return@runCatching false
        val sbn = readInstanceField(entry, "mSbn")
            ?: entry.javaClass.methods.firstOrNull {
                it.name == "getSbn" && it.parameterCount == 0
            }?.invoke(entry)
            ?: return@runCatching false
        val entryKey = entry.javaClass.methods.firstOrNull {
            it.name == "getKey" && it.parameterCount == 0
        }?.invoke(entry) as? String
        if (entryKey != null && entryKey == LockscreenMediaBridge.notificationKey) {
            return@runCatching true
        }
        // HyperOS commits mMediaNotificationKey asynchronously. During that window the
        // notification is still identifiable by the package owning the active media session.
        // This is the stable signal used by the lockscreen media card itself.
        val mediaPackage = LockscreenMediaBridge.controller?.packageName
        val sbnPackage = sbn.javaClass.methods.firstOrNull {
            it.name == "getPackageName" && it.parameterCount == 0
        }?.invoke(sbn) as? String
        if (!mediaPackage.isNullOrBlank() && sbnPackage == mediaPackage) {
            return@runCatching true
        }
        val mediaDataManagerMedia = runCatching {
            val managerClass = Class.forName(
                "com.android.systemui.media.controls.domain.pipeline.MediaDataManager",
                false,
                sbn.javaClass.classLoader,
            )
            managerClass.getMethod(
                "isMediaNotification",
                android.service.notification.StatusBarNotification::class.java,
            ).invoke(null, sbn) as? Boolean
        }.getOrNull()
        if (mediaDataManagerMedia == true) return@runCatching true
        val expandedMedia = (readInstanceField(sbn, "isMediaNotification") as? Boolean)
            ?: (sbn.javaClass.methods.firstOrNull {
                it.name == "isMediaNotification" && it.parameterCount == 0
            }?.invoke(sbn) as? Boolean)
        if (expandedMedia == true) return@runCatching true
        val notification = sbn.javaClass.methods.firstOrNull {
            it.name == "getNotification" && it.parameterCount == 0
        }?.invoke(sbn) ?: return@runCatching false
        val notificationMedia = notification.javaClass.methods.firstOrNull {
            it.name == "isMediaNotification" && it.parameterCount == 0
        }?.invoke(notification) as? Boolean
        if (notificationMedia == true) return@runCatching true
        val extras = notification.javaClass.getMethod("getExtras").invoke(notification) as? android.os.Bundle
        val category = notification.javaClass.getField("category").get(notification) as? String
        extras?.containsKey("android.mediaSession") == true || category == "transport"
    }.getOrDefault(false)

    private fun isKeyguardCoordinatorOnKeyguard(filter: Any?): Boolean = runCatching {
        val coordinatorField = filter?.javaClass?.declaredFields?.firstOrNull {
            it.name.startsWith("this") && it.name.contains("0")
        } ?: return@runCatching false
        coordinatorField.isAccessible = true
        val coordinator = coordinatorField.get(filter)
            ?: return@runCatching false
        val stateController = readInstanceField(coordinator, "statusBarStateController")
            ?: return@runCatching false
        val state = stateController.javaClass.methods.firstOrNull {
            it.name == "getState" && it.parameterCount == 0
        }?.invoke(stateController) as? Int ?: return@runCatching false
        state == 1 || state == 2
    }.getOrDefault(false)

    private fun installFingerprintIconVisualHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val iconClass = classLoader.loadClass(MIUI_GXZW_ICON_VIEW_CLASS)
            val dismissIcon = iconClass.getMethod(FOD_DISMISS_ICON_METHOD)
            val displayMethods = iconClass.declaredMethods.filter { method ->
                (method.name == "show" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) ||
                    (method.name == "showFingerprintIcon" && method.parameterCount == 0) ||
                    // A locked-again keyguard reuses the existing FOD window and only makes
                    // its animation surface opaque through this method.
                    (method.name == "setGxzwIconOpaque" && method.parameterCount == 0)
            }
            check(displayMethods.isNotEmpty()) { "MiuiGxzwIconView display methods were not found" }
            displayMethods.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-fod-icon-transparent-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_GLOBAL) {
                            // The platform method only clears the animation/icon surface. The
                            // FOD view remains attached and continues receiving touch events.
                            dismissIcon.invoke(chain.thisObject)
                        }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed ${displayMethods.size} lockscreen FOD icon hook(s)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install lockscreen FOD icon hook", error)
        }
    }

    private fun installSystemUiDepthHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)
            val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)
            val constructor = thresholdClass.getDeclaredConstructor(Double::class.javaPrimitiveType)
            hook(constructor)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("systemui-depth-image-threshold")
                .intercept { chain ->
                    val original = chain.getArg(0) as? Double
                    if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                        original == DEFAULT_DEPTH_IMAGE_THRESHOLD
                    ) {
                        chain.proceed(arrayOf(UNLIMITED_DEPTH_IMAGE_THRESHOLD))
                    } else {
                        chain.proceed()
                    }
                }

            val interactorClass = classLoader.loadClass(KEYGUARD_DEPTH_INTERACTOR_CLASS)
            hook(interactorClass.getMethod("updateAvoidStatus"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("systemui-depth-time-overlap")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                        clearDepthAvoidState(chain.thisObject, interactorClass)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            val alphaMethod = interactorClass.declaredMethods.firstOrNull {
                it.name == "setDepthTransitionAlpha" && it.parameterCount == 3
            } ?: error("setDepthTransitionAlpha not found")
            hook(alphaMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("systemui-depth-alpha")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                        clearDepthAvoidState(chain.thisObject, interactorClass)
                    }
                    chain.proceed()
                }
            installDepthDisplayStateHook(classLoader, preferences)
            installThirdPartyWallpaperDepthHook(classLoader, preferences)
            forceDepthImageThreshold(evaluatorClass, thresholdClass, preferences)
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed SystemUI lockscreen depth hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install SystemUI lockscreen depth hooks", error)
        }
    }

    private fun forceDepthImageThreshold(
        evaluatorClass: Class<*>,
        thresholdClass: Class<*>,
        preferences: SharedPreferences,
    ) {
        if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
        runCatching {
            val threshold = evaluatorClass.getDeclaredField(IMAGE_THRESHOLD_FIELD).get(null)
            thresholdClass.getDeclaredField(THRESHOLD_RATE_FIELD)
                .apply { isAccessible = true }
                .setDouble(threshold, UNLIMITED_DEPTH_IMAGE_THRESHOLD)
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not set SystemUI depth image threshold", error)
        }
    }

    private fun installDepthDisplayStateHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)
        val depthEnabled = panelClass.getDeclaredField("depthEffectEnable").apply { isAccessible = true }
        val interactor = panelClass.getDeclaredField("keyguardDepthInteractor").apply { isAccessible = true }
        val actualDisplayDepth = interactor.type.getDeclaredField("isActualDisplayDepth")
            .apply { isAccessible = true }
        val depthEnabledInner = interactor.type.getDeclaredField("depthEffectEnableInner")
            .apply { isAccessible = true }
        val updateElements = panelClass.getMethod("updateKeyguardElementsVisibility")
        hook(panelClass.getMethod("updateShowDepthState"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("systemui-depth-display-state")
            .intercept { chain ->
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                    depthEnabled.setBoolean(chain.thisObject, true)
                    depthEnabledInner.setBoolean(interactor.get(chain.thisObject), true)
                }
                val result = chain.proceed()
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                    val depthInteractor = interactor.get(chain.thisObject)
                    if (!actualDisplayDepth.getBoolean(depthInteractor)) {
                        actualDisplayDepth.setBoolean(depthInteractor, true)
                        updateElements.invoke(chain.thisObject)
                    }
                }
                result
            }
    }

    private fun clearDepthAvoidState(instance: Any?, interactorClass: Class<*>) {
        runCatching {
            val state = interactorClass.getDeclaredField("_avoidState")
                .apply { isAccessible = true }
                .get(instance)
            val update = state.javaClass.methods.firstOrNull {
                it.name == "updateState\u00241" && it.parameterCount == 2
            } ?: error("StateFlow update method not found")
            update.invoke(state, null, false)
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not clear lockscreen depth avoid state", error)
        }
    }

    private fun isNotificationFodPositionLimitRemoved(preferences: SharedPreferences): Boolean =
        if (preferences.contains(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED)) {
            preferences.getBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, false)
        } else {
            // Keep the previous release's behavior for users who have not yet opened settings.
            preferences.getInt(
                KEY_NOTIFICATION_FOD_MODE,
                if (preferences.getBoolean(KEY_NOTIFICATIONS_IGNORE_FOD, false)) FOD_MODE_KEEP_ICON else FOD_MODE_DEFAULT,
            ).coerceIn(FOD_MODE_DEFAULT, FOD_MODE_KEEP_ICON) != FOD_MODE_DEFAULT
        }

    private fun fingerprintHideMode(preferences: SharedPreferences): Int =
        if (preferences.contains(KEY_FINGERPRINT_HIDE_MODE)) {
            preferences.getInt(KEY_FINGERPRINT_HIDE_MODE, FINGERPRINT_HIDE_NONE)
                .coerceIn(FINGERPRINT_HIDE_NONE, FINGERPRINT_HIDE_GLOBAL)
        } else if (preferences.getInt(KEY_NOTIFICATION_FOD_MODE, FOD_MODE_DEFAULT) == FOD_MODE_HIDE_ICON) {
            // The old hide-icon setting was global. Preserve that choice during upgrade.
            FINGERPRINT_HIDE_GLOBAL
        } else {
            FINGERPRINT_HIDE_NONE
        }

    /**
     * HyperOS 4's animation manager is shared by lockscreen and in-app biometric prompts. The
     * The target HyperOS 4 smali checks mKeyguardAuthen with if-eqz and applies the empty
     * resource/animation branch when it is true. Software FOD prompts keep the normal path.
     */
    private fun installLockscreenFingerprintAnimationHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val managerClass = classLoader.loadClass(MIUI_GXZW_ANIM_MANAGER_CLASS)
            val keyguardAuthen = managerClass.getDeclaredField(MIUI_GXZW_KEYGUARD_AUTHEN_FIELD)
                .apply { isAccessible = true }
            val animItemMap = managerClass.getDeclaredField(MIUI_GXZW_ANIMATION_ITEMS_FIELD)
                .apply { isAccessible = true }
            // Some HyperOS builds move these methods to a superclass or add an unused argument.
            // Include the complete hierarchy and hook every matching overload.
            val methods = buildList {
                var current: Class<*>? = managerClass
                while (current != null) {
                    addAll(current.declaredMethods)
                    current = current.superclass
                }
            }.distinctBy { method ->
                method.name to method.parameterTypes.toList()
            }
            val iconResources = methods.filter { it.name == MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD }
            val recognizingItems = methods.filter { it.name == MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD }
            check(iconResources.isNotEmpty()) {
                "getFingerIconResource was not found; methods=${methods.map { it.name }.filter { name ->
                    name.contains("Finger", ignoreCase = true) || name.contains("Icon", ignoreCase = true)
                }}"
            }
            check(recognizingItems.isNotEmpty()) {
                "getRecognizingAnimItem was not found; methods=${methods.map { it.name }.filter { name ->
                    name.contains("Recogn", ignoreCase = true) || name.contains("Anim", ignoreCase = true)
                }}"
            }

            iconResources.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-fod-animation-icon-resource-$index")
                    .intercept { chain ->
                        if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_LOCKSCREEN &&
                            keyguardAuthen.getBoolean(chain.thisObject)
                        ) {
                            LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE
                        } else {
                            chain.proceed()
                        }
                    }
            }
            recognizingItems.forEachIndexed { index, method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-fod-animation-recognizing-item-$index")
                    .intercept { chain ->
                        if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_LOCKSCREEN &&
                            keyguardAuthen.getBoolean(chain.thisObject)
                        ) {
                            val map = animItemMap.get(chain.thisObject) as? Map<*, *>
                            map?.get(0)
                        } else {
                            chain.proceed()
                        }
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed HyperOS 4 lockscreen FOD animation hooks: icon=${iconResources.size}, recognizing=${recognizingItems.size}")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install HyperOS 4 lockscreen FOD animation hooks", error)
        }
    }

    /**
     * The target ROM hides the gesture handle at the end of updateNavButtonIcons().  Its DEX
     * branch selects INVISIBLE when both home and recents are disabled; the documented patch
     * changes that conditional jump so the VISIBLE branch is always selected.  Intercept that
     * exact ButtonDispatcher call instead of changing visibility after the vendor method has
     * finished, as another UI update can otherwise immediately hide the handle again.
     */
    private fun installLockscreenWhiteBarHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return false
        return runCatching {
            val navigationBarViewClass = classLoader.loadClass(NAVIGATION_BAR_VIEW_CLASS)
            val updateNavButtonIcons = navigationBarViewClass.getDeclaredMethod("updateNavButtonIcons")
            val getHomeHandle = navigationBarViewClass.getDeclaredMethod("getHomeHandle")
            val dispatcherClass = getHomeHandle.returnType
            val setHandleVisibility = dispatcherClass.getDeclaredMethod(
                "setVisibility",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            getHomeHandle.isAccessible = true
            val idField = dispatcherClass.getDeclaredField("mId").apply { isAccessible = true }
            val viewsField = dispatcherClass.getDeclaredField("mViews").apply { isAccessible = true }
            val homeHandleId = classLoader.loadClass("com.android.systemui.R\$id")
                .getDeclaredField("home_handle")
                .apply { isAccessible = true }
                .getInt(null)

            hook(setHandleVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-white-bar:visibility")
                .intercept { chain ->
                    val dispatcher = chain.thisObject
                    val isHomeHandle = runCatching {
                        idField.getInt(dispatcher) == homeHandleId
                    }.getOrDefault(false)
                    if (
                        preferences.getBoolean(KEY_LOCKSCREEN_WHITE_BAR_ENABLED, false) &&
                        isHomeHandle &&
                        chain.getArg(0) == View.INVISIBLE
                    ) {
                        chain.proceedWith(chain.thisObject, arrayOf(View.VISIBLE))
                    } else {
                        chain.proceed()
                    }
                }

            hook(updateNavButtonIcons)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-white-bar:update")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_LOCKSCREEN_WHITE_BAR_ENABLED, false)) {
                        runCatching {
                            val dispatcher = getHomeHandle.invoke(chain.thisObject)
                            if (dispatcher != null && idField.getInt(dispatcher) == homeHandleId) {
                                // Keep the dispatcher state visible as well as its current View;
                                // addView() reapplies mVisibility when the navigation layout is rebuilt.
                                setHandleVisibility.invoke(dispatcher, View.VISIBLE)
                                (viewsField.get(dispatcher) as? Iterable<*>)?.forEach { view ->
                                    (view as? View)?.visibility = View.VISIBLE
                                }
                            }
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not restore lockscreen white-bar visibility", error)
                        }
                    }
                    result
                }

            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed lockscreen white-bar hook")
            true
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install lockscreen white-bar hook", error)
        }.getOrDefault(false)
    }

    private fun installGlobalGestureHandleHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean = if (!ModuleSafetyRuntime.allows(SafetySection.GESTURE_HANDLE)) false else runCatching {
        val handleClass = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")
        val onDraw = handleClass.getDeclaredMethod("onDraw", Canvas::class.java)
        hook(onDraw)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("global-gesture-handle:hidden")
            .intercept { chain ->
                val handle = chain.thisObject as View
                if (preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) {
                    gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    null
                } else {
                    val customEnabled = preferences.getBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, false)
                    if (!customEnabled) {
                        gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                        chain.proceed()
                    } else {
                        drawCustomGestureHandle(
                            handle,
                            chain.getArg(0) as Canvas,
                            GESTURE_HANDLE_STYLE_DEFAULT,
                            classLoader,
                            preferences,
                        )
                        null
                    }
                }
            }
        runCatching {
            hook(handleClass.getDeclaredMethod("onAttachedToWindow"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("global-gesture-handle:material")
                .intercept { chain ->
                    chain.proceed()
                }
        }
        runCatching {
            hook(handleClass.getDeclaredMethod("onDetachedFromWindow"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("global-gesture-handle:material-detach")
                .intercept { chain ->
                    val handle = chain.thisObject as View
                    gestureMaterialOverlays.remove(handle)?.let { overlay ->
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                    }
                    chain.proceed()
                }
        }
        // Some HyperOS builds use an overriding draw method for rotated quick-switch
        // navigation. Suppress only that drawing method as well; leave visibility,
        // measurement, insets, and touch handling untouched.
        runCatching {
            val rotatedClass = classLoader.loadClass(
                "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle",
            )
            hook(rotatedClass.getDeclaredMethod("onDraw", Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("global-gesture-handle:rotated-hidden")
                .intercept { chain ->
                    val handle = chain.thisObject as View
                    if (preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) {
                        gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                        null
                    } else {
                        val customEnabled = preferences.getBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, false)
                        if (!customEnabled) {
                            gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                            chain.proceed()
                        } else {
                            drawCustomGestureHandle(
                                handle,
                                chain.getArg(0) as Canvas,
                                GESTURE_HANDLE_STYLE_DEFAULT,
                                classLoader,
                                preferences,
                            )
                            null
                        }
                    }
                }
        }
        true
    }.onFailure { error ->
        btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install global gesture-handle hook", error)
    }.getOrDefault(false)

    private fun isGlobalGestureHandle(view: View): Boolean {
        val name = view.javaClass.name
        return name == "com.android.systemui.navigationbar.gestural.NavigationHandle" ||
            name == "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle" ||
            name.endsWith(".NavigationHandle") || name.endsWith(".QuickswitchOrientedNavHandle")
    }

    private fun configureCustomGestureHandleMaterial(
        handle: View,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val style = preferences.getInt(KEY_GESTURE_HANDLE_STYLE, GESTURE_HANDLE_STYLE_DEFAULT)
        val adaptiveColor = currentGestureHandleColor(handle)
        if (style == GESTURE_HANDLE_STYLE_ADVANCED) {
            runCatching {
                applyLegacyBackdropMaterial(
                    handle,
                    preferences.getInt(KEY_GESTURE_HANDLE_OPACITY, 100),
                    preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                    adaptiveColor,
                    preferences.getBoolean(KEY_GESTURE_HANDLE_HIGHLIGHT, false),
                )
                // HyperOS 4's advanced material is the MiGlass pipeline.  The
                // legacy backdrop calls above only provide a compatibility fallback;
                // without this call the gesture handle stays a plain translucent pill.
                applySystemGlassMaterial(
                    handle,
                    classLoader,
                    preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                    DEFAULT_SOFT_GLASS_LUMINANCE,
                )
            }
        } else if (style == GESTURE_HANDLE_STYLE_SOFT_GLASS) {
            runCatching {
                applyLegacyBackdropMaterial(
                    handle,
                    preferences.getInt(KEY_GESTURE_HANDLE_OPACITY, 100),
                    preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                    adaptiveColor,
                    false,
                )
                applySystemGlassMaterial(
                    handle,
                    classLoader,
                    preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                    (preferences.getInt(KEY_GESTURE_HANDLE_REFRACTION, 0) / 100f).coerceIn(0f, 0.4f),
                )
            }
        }
    }

    private fun drawCustomGestureHandle(
        handle: View,
        canvas: Canvas,
        style: Int,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        // Reapply material parameters while drawing so slider changes take effect on
        // the existing SystemUI view without waiting for a new attachment.
        // The current customization page controls geometry and opacity only. Remove
        // any legacy material layer left by older preference versions.
        gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val density = handle.resources.displayMetrics.density
        val hookMask = preferences.getInt(KEY_GESTURE_HANDLE_HOOK_MASK, GESTURE_HANDLE_HOOK_LENGTH or GESTURE_HANDLE_HOOK_HEIGHT or GESTURE_HANDLE_HOOK_BOTTOM or GESTURE_HANDLE_HOOK_OPACITY)
        val stockWidth = runCatching { handle.javaClass.getMethod("getHandleDrawWidth").invoke(handle) as Number }.getOrNull()?.toFloat()
            ?: 120f * density
        val stockHeight = runCatching { handle.javaClass.getMethod("getPillRadius").invoke(handle) as Number }.getOrNull()?.toFloat()?.times(2f)
            ?: 5f * density
        val width = if (hookMask and GESTURE_HANDLE_HOOK_LENGTH != 0) {
            preferences.getFloat(KEY_GESTURE_HANDLE_LENGTH, 120f).coerceIn(40f, 240f) * density
        } else stockWidth
        val height = if (hookMask and GESTURE_HANDLE_HOOK_HEIGHT != 0) {
            preferences.getFloat(KEY_GESTURE_HANDLE_HEIGHT, 5f).coerceIn(1f, 24f) * density
        } else stockHeight
        val left = (handle.width - width) / 2f
        val bottom = if (hookMask and GESTURE_HANDLE_HOOK_BOTTOM != 0) {
            preferences.getFloat(KEY_GESTURE_HANDLE_BOTTOM, 6f).coerceIn(0f, 48f) * density
        } else gestureHandleBottom(handle)
        val top = handle.height - bottom - height
        val radius = height / 2f
        val color = if (style == GESTURE_HANDLE_STYLE_PURE) {
            preferences.getInt(KEY_GESTURE_HANDLE_COLOR, Color.WHITE)
        } else {
            currentGestureHandleColor(handle)
        }
        // Keep a translucent adaptive-color fallback visible when a vendor build does
        // not render the backdrop source layer. The independent material overlay still
        // supplies blur/glass effects on builds that support those APIs.
        val opacity = if (style == GESTURE_HANDLE_STYLE_PURE) 255 else if (
            hookMask and GESTURE_HANDLE_HOOK_OPACITY != 0
        ) {
            preferences.getInt(KEY_GESTURE_HANDLE_OPACITY, 100).coerceIn(0, 100) * 255 / 100
        } else 255
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(opacity, Color.red(color), Color.green(color), Color.blue(color))
            this.style = Paint.Style.FILL
        }
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, paint)
        if (preferences.getBoolean(KEY_GESTURE_HANDLE_HIGHLIGHT, false) && style == GESTURE_HANDLE_STYLE_PURE) {
            paint.color = Color.argb((opacity * .42f).toInt(), 255, 255, 255)
            canvas.drawRoundRect(left + radius * .35f, top + radius * .2f, left + width - radius * .35f, top + radius * .55f, radius, radius, paint)
        }
    }

    private fun currentGestureHandleColor(handle: View): Int = runCatching {
        handle.javaClass.getMethod("getHandleColor").invoke(handle) as Int
    }.getOrDefault(Color.WHITE)

    /** Stock NavigationHandle bottom inset, in pixels. */
    private fun gestureHandleBottom(handle: View): Float = runCatching {
        handle.javaClass.getDeclaredField("mBottom").apply { isAccessible = true }.getFloat(handle)
    }.getOrDefault(0f).coerceAtLeast(0f)

    /** Adds the same independent material source layer used by lockscreen shortcut backgrounds. */
    private fun syncGestureMaterialOverlay(
        handle: View,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val style = preferences.getInt(KEY_GESTURE_HANDLE_STYLE, GESTURE_HANDLE_STYLE_DEFAULT)
        val material = style == GESTURE_HANDLE_STYLE_ADVANCED || style == GESTURE_HANDLE_STYLE_SOFT_GLASS
        // Do not add the overlay to NavigationHandle's immediate LinearLayout parent:
        // that would make it a new flow item and move it to the left. Attach it to the
        // nearest FrameLayout host and position it using the handle's absolute bounds.
        var host: FrameLayout? = null
        var fallbackHost: FrameLayout? = null
        var ancestor: View? = handle.parent as? View
        while (ancestor != null) {
            if (ancestor is FrameLayout) {
                fallbackHost = fallbackHost ?: ancestor
                // NavigationBarFrame is the actual navigation-bar window root. The
                // nearer nav_buttons containers can be clipped or transformed and do
                // not participate in the window backdrop pass on some builds.
                if (ancestor.javaClass.name.endsWith("NavigationBarFrame")) {
                    host = ancestor
                    break
                }
            }
            ancestor = ancestor.parent as? View
        }
        val materialHost = host ?: fallbackHost ?: return
        materialHost.clipChildren = false
        materialHost.clipToPadding = false
        if (!material || handle.width <= 0 || handle.height <= 0) {
            gestureMaterialOverlays.remove(handle)?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return
        }
        val overlay = gestureMaterialOverlays[handle] ?: ImageView(handle.context).also { created ->
            created.isClickable = false
            created.isFocusable = false
            created.isDuplicateParentStateEnabled = true
            created.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            created.setImageDrawable(GradientDrawable().apply { setColor(Color.argb(1, 255, 255, 255)) })
            created.clipToOutline = true
            created.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val density = view.resources.displayMetrics.density
                    val width = preferences.getFloat(KEY_GESTURE_HANDLE_LENGTH, 120f).coerceIn(40f, 240f) * density
                    val height = preferences.getFloat(KEY_GESTURE_HANDLE_HEIGHT, 5f).coerceIn(1f, 24f) * density
                    val left = ((view.width - width) / 2f).coerceAtLeast(0f)
                    val top = (view.height - gestureHandleBottom(handle) - height).coerceAtLeast(0f)
                    outline.setRoundRect(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt(), height / 2f)
                }
            }
            gestureMaterialOverlays[handle] = created
            materialHost.addView(created)
            created
        }
        val handleLocation = IntArray(2)
        val hostLocation = IntArray(2)
        handle.getLocationOnScreen(handleLocation)
        materialHost.getLocationOnScreen(hostLocation)
        val lp = FrameLayout.LayoutParams(handle.width, handle.height).apply {
            leftMargin = handleLocation[0] - hostLocation[0]
            topMargin = handleLocation[1] - hostLocation[1]
        }
        overlay.layoutParams = lp
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(handle.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(handle.height, View.MeasureSpec.EXACTLY),
        )
        overlay.layout(lp.leftMargin, lp.topMargin, lp.leftMargin + handle.width, lp.topMargin + handle.height)
        overlay.invalidateOutline()
        val adaptiveColor = currentGestureHandleColor(handle)
        runCatching {
            applyLegacyBackdropMaterial(
                overlay,
                preferences.getInt(KEY_GESTURE_HANDLE_OPACITY, 100),
                preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                adaptiveColor,
                preferences.getBoolean(KEY_GESTURE_HANDLE_HIGHLIGHT, false),
            )
            if (style == GESTURE_HANDLE_STYLE_ADVANCED || style == GESTURE_HANDLE_STYLE_SOFT_GLASS) {
                val luminance = if (style == GESTURE_HANDLE_STYLE_SOFT_GLASS) {
                    val refraction = preferences.getInt(KEY_GESTURE_HANDLE_REFRACTION, 0)
                    (refraction / 100f).coerceIn(0f, 0.4f)
                } else {
                    DEFAULT_SOFT_GLASS_LUMINANCE
                }
                applySystemGlassMaterial(
                    overlay,
                    classLoader,
                    preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                    luminance,
                )
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not apply gesture material overlay", error)
        }
        overlay.bringToFront()
        overlay.post {
            if (overlay.isAttachedToWindow && overlay.width > 0 && overlay.height > 0) {
                runCatching {
                    val color = currentGestureHandleColor(handle)
                    applyLegacyBackdropMaterial(
                        overlay,
                        preferences.getInt(KEY_GESTURE_HANDLE_OPACITY, 100),
                        preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                        color,
                        preferences.getBoolean(KEY_GESTURE_HANDLE_HIGHLIGHT, false),
                    )
                    if (style == GESTURE_HANDLE_STYLE_ADVANCED || style == GESTURE_HANDLE_STYLE_SOFT_GLASS) {
                        val luminance = if (style == GESTURE_HANDLE_STYLE_SOFT_GLASS) {
                            val refraction = preferences.getInt(KEY_GESTURE_HANDLE_REFRACTION, 0)
                            (refraction / 100f).coerceIn(0f, 0.4f)
                        } else {
                            DEFAULT_SOFT_GLASS_LUMINANCE
                        }
                        applySystemGlassMaterial(
                            overlay,
                            classLoader,
                            preferences.getInt(KEY_GESTURE_HANDLE_BLUR, 40),
                            luminance,
                        )
                    }
                    overlay.invalidate()
                }.onFailure { error ->
                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Could not reapply gesture material overlay", error)
                }
            }
        }
    }

    /** Keep the vendor navigation controller's own mHideGestureLine state enabled. */
    private fun installGlobalGestureControllerHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.GESTURE_HANDLE)) return
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.navigationbar.NavigationBarControllerImpl",
            )
            val injectorField = controllerClass.getDeclaredField(
                "mNavigationModeControllerInjector",
            ).apply { isAccessible = true }
            controllerClass.declaredMethods.filter { it.name == "createNavigationBar" }.forEach { method ->
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("global-gesture-handle:controller-create")
                    .intercept { chain ->
                        // Keep a real navigation-bar host so its height and inset remain
                        // unchanged. If the stock controller would skip host creation due
                        // to its own hidden-line flag, temporarily clear that flag only for
                        // this call and restore it after the host has been created.
                        val injector = injectorField.get(chain.thisObject)
                        val hideField = injector?.javaClass?.getDeclaredField("mHideGestureLine")
                            ?.apply { isAccessible = true }
                        val fsgField = injector?.javaClass?.getDeclaredField("mIsFsgMode")
                            ?.apply { isAccessible = true }
                        val stockHidden = hideField?.getBoolean(injector) == true
                        val fsgMode = fsgField?.getBoolean(injector) == true
                        val override = preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false) &&
                            stockHidden && fsgMode
                        if (override) hideField?.setBoolean(injector, false)
                        try {
                            chain.proceed()
                        } finally {
                            if (override) hideField?.setBoolean(injector, true)
                        }
                    }
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install global gesture controller hook", error)
        }
    }

    private fun installDimensionHooks(preferences: SharedPreferences) {
        hook(Resources::class.java.getMethod("getDimension", Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("resource-dimension")
            .intercept { chain ->
                replaceDimensionIfNeeded(
                    chain.thisObject as Resources,
                    chain.getArg(0) as Int,
                    chain.proceed() as Float,
                    preferences,
                )
            }

        hook(Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("resource-dimension-pixel-size")
            .intercept { chain ->
                replaceDimensionPixelIfNeeded(
                    chain.thisObject as Resources,
                    chain.getArg(0) as Int,
                    chain.proceed() as Int,
                    preferences,
                )
            }

        hook(Resources::class.java.getMethod("getDimensionPixelOffset", Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("resource-dimension-pixel-offset")
            .intercept { chain ->
                replaceDimensionPixelIfNeeded(
                    chain.thisObject as Resources,
                    chain.getArg(0) as Int,
                    chain.proceed() as Int,
                    preferences,
                )
            }
    }

    private val headsUpGlassDispatching = ThreadLocal<Boolean>()
    private val convertedHeadsUpRows = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    private fun isHeadsUpNotificationSurface(view: View): Boolean {
        if (headsUpGlassDispatching.get() == true || stackContainsClass("HeadsUpNotification")) return true
        return generateSequence(view) { it.parent as? View }.any { candidate ->
            candidate.javaClass.name.endsWith("ExpandableNotificationRow") &&
                runCatching { candidate.javaClass.getMethod("isHeadsUpState").invoke(candidate) == true }.getOrDefault(false)
        }
    }

    private fun installHeadsUpNotificationSoftGlassHooks(
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        runCatching {
            val prefix = "com.android.systemui.statusbar.notification.style.vieweffect."
            val blur = classLoader.loadClass(prefix + "HeadsUpNotificationBlurEffect")
            val rowBlur = classLoader.loadClass(prefix + "NotificationRowBlurEffect")

            fun applyResourceGlass(row: View, materialType: Int): Boolean {
                val background = runCatching {
                    val injector = row.javaClass.getMethod("getInjector").invoke(row)
                    injector?.javaClass?.getMethod("getBackgroundNormal")?.invoke(injector) as? View
                }.getOrNull() ?: return false
                val params = normalNotificationGlassParams(background) ?: return false
                val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)
                glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
                    .invoke(null, background, params)
                glassCompat.getMethod(
                    "setMiViewMaterialTypeCompat",
                    Int::class.javaPrimitiveType,
                    View::class.java,
                ).invoke(null, materialType, background)
                return true
            }

            fun dispatch(row: View, context: Context?, clear: Boolean): Boolean {
                return runCatching {
                    val instance = rowBlur.getField("INSTANCE").get(null)
                    val method = rowBlur.declaredMethods.first {
                        it.name == (if (clear) "clear" else "apply") && it.parameterCount == (if (clear) 1 else 2)
                    }
                    headsUpGlassDispatching.set(true)
                    try {
                        if (clear) method.invoke(instance, row) else method.invoke(instance, row, context)
                        applyResourceGlass(row, if (clear) 0 else 1)
                    } finally { headsUpGlassDispatching.remove() }
                    true
                }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Heads-up platform glass dispatch unavailable", it) }.getOrDefault(false)
            }
            blur.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("heads-up-soft-glass:platform-apply").intercept { chain ->
                        val row = chain.getArg(0) as? View
                        val context = chain.getArg(1) as? Context
                        // Replace the plain blur entry with the same normal-resource glass path
                        // used by the imported SystemUI dex.
                        if (row != null && context != null && headsUpGlassDispatching.get() != true &&
                            !stackContainsClass("HeadsUpNotificationGlass") &&
                            preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&
                            dispatch(row, context, false)) {
                            convertedHeadsUpRows.add(row)
                            null
                        } else chain.proceed()
                    }
            }
            blur.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("heads-up-soft-glass:platform-clear").intercept { chain ->
                        val row = chain.getArg(0) as? View
                        // Also clear the 42-slot recipe/material type when a converted blur
                        // effect is dismissed, even if its preference was just switched off.
                        if (row != null && headsUpGlassDispatching.get() != true && convertedHeadsUpRows.remove(row) &&
                            dispatch(row, null, true)) null else chain.proceed()
                    }
            }
            listOf("HeadsUpNotificationGlassEffect", "HeadsUpNotificationGlassDarkEffect")
                .forEach { className ->
                    val effect = classLoader.loadClass(prefix + className)
                    effect.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->
                        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("heads-up-soft-glass:resource-apply:$className")
                            .intercept { chain ->
                                val row = chain.getArg(0) as? View
                                val context = chain.getArg(1) as? Context
                                if (row != null && context != null && headsUpGlassDispatching.get() != true &&
                                    preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&
                                    dispatch(row, context, false)
                                ) null else chain.proceed()
                            }
                    }
                    effect.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->
                        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("heads-up-soft-glass:resource-clear:$className")
                            .intercept { chain ->
                                val row = chain.getArg(0) as? View
                                if (row != null && headsUpGlassDispatching.get() != true &&
                                    preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&
                                    dispatch(row, null, true)
                                ) null else chain.proceed()
                            }
                    }
                }
        }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Heads-up soft glass hooks unavailable", it) }
    }

    private fun installCornerRadiusHooks(preferences: SharedPreferences) {
        controlCenterRadiusRuntime = ControlCenterRadiusRuntimeSettings(preferences)
        // MIUI loads its Control Center implementation through a plugin class loader.
        // Discover target classes at the point that loader resolves them.
        hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center-class-discovery")
            .intercept { chain ->
                val loadedClass = chain.proceed() as? Class<*> ?: return@intercept null
                installLoadedCornerRadiusHook(loadedClass, preferences)
                installLoadedVolumePanelHook(loadedClass, preferences)
                loadedClass
            }

        if (ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) hook(View::class.java.getMethod("setBackground", Drawable::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("slider-background-radius")
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as View
                applyControlCenterDrawableRadius(view, preferences)
                result
            }
    }

    /**
     * Some plugin classes are initialized before the ClassLoader discovery hook is installed.
     * Install the stable control-center targets eagerly as well; discovery remains the fallback
     * for builds that defer one of these classes until the panel is first opened.
     */
    private fun installKnownCornerRadiusHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        listOf(
            TOP_BUTTONS_CLASS,
            MEDIA_PANEL_CLASS,
            DEVICE_CENTER_ENTRY,
            SLIDER_VIEW_HOLDER_CLASS,
            BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS,
            QS_ITEM_VIEW_HOLDER_CLASS,
            "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView",
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder",
            SECONDARY_PANEL_CONTROLLER_BASE_CLASS,
            SECONDARY_VOLUME_PANEL_CLASS,
        ).forEach { className ->
            runCatching {
                classLoader.loadClass(className).also { targetClass ->
                    installLoadedCornerRadiusHook(targetClass, preferences)
                }
            }.onFailure { error ->
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Deferred corner-radius target unavailable: $className", error)
            }
        }
    }

    private fun installThirdPartyWallpaperDepthHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
        runCatching {
            val wallpaperInfoClass = classLoader.loadClass(WALLPAPER_INFO_CLASS)
            hook(wallpaperInfoClass.getMethod("getSupportSubject"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("systemui-depth-wallpaper-subject")
                .intercept { chain -> true }

            val hierarchyClass = classLoader.loadClass(LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)
            val constructor = hierarchyClass.getDeclaredConstructor(
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val forcedHierarchy = constructor.newInstance(
                true, true, true,
                true, true, true,
                true, true, true,
            )
            hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("systemui-depth-wallpaper-hierarchy")
                .intercept { chain -> forcedHierarchy }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed third-party wallpaper depth capability hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install third-party wallpaper depth hooks", error)
        }
    }

    /**
     * The AOD editor has its own wallpaper model and applies the same third-party checks again.
     * Keep this separate from TemplateApiImpl.isDefaultTheme(), which belongs to the global
     * theme soft-glass path and must retain its original semantics.
     */
    private fun installAodThirdPartyWallpaperDepthHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
        runCatching {
            val wallpaperInfoClass = classLoader.loadClass(AOD_WALLPAPER_INFO_CLASS)
            hook(wallpaperInfoClass.getMethod("getSupportSubject"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-depth-wallpaper-subject")
                .intercept { chain -> true }

            val hierarchyClass = classLoader.loadClass(AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)
            val hierarchyConstructor = hierarchyClass.getDeclaredConstructor().apply {
                isAccessible = true
            }
            val forcedHierarchy = hierarchyConstructor.newInstance()
            hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-depth-wallpaper-hierarchy")
                .intercept { chain -> forcedHierarchy }

            val companionClass = classLoader.loadClass(WALLPAPER_CONTROLLER_COMPANION_CLASS)
            val pickColorMethod = companionClass.getMethod(
                "getPickWallpaperColorInfo",
                String::class.java,
                Integer::class.java,
                Integer::class.java,
            )
            hook(pickColorMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-depth-wallpaper-clock-style")
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        result?.javaClass?.getMethod("setClockInfoStyle", Integer::class.java)
                            ?.invoke(result, chain.getArg(1))
                        result?.javaClass?.getMethod("setSignatureAlignment", Integer::class.java)
                            ?.invoke(result, chain.getArg(2))
                    }
                    result
                }

            val controllerClass = classLoader.loadClass(WALLPAPER_CONTROLLER_CLASS)
            hook(controllerClass.getMethod("needResetMagicType"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-depth-wallpaper-reset")
                .intercept { chain -> false }

            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed AOD third-party wallpaper depth hooks")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install AOD third-party wallpaper depth hooks", error)
        }
    }

    /**
     * The editor disables the glass filter for video/sensor/linkage wallpapers unless the
     * separate MiWallpaper metadata flag is enabled.  That flag is a stock capability gate,
     * not a renderer requirement on this device, so force the capability query on in both the
     * AOD editor and the ThemeManager copy of the editor.
     */
    private fun installVideoWallpaperGlassSupportHook(classLoader: ClassLoader) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val companionClass = classLoader.loadClass(
                "com.miui.keyguard.editor.utils.Wallpaper\$Companion",
            )
            val contextClass = Context::class.java
            val methods = companionClass.declaredMethods.filter { method ->
                method.parameterTypes.contentEquals(arrayOf(contextClass)) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    (method.name == "isVideoSupportGlassFilter" || method.name == "kja0")
            }
            if (methods.isEmpty()) {
                error("Wallpaper glass-capability method not found")
            }
            methods.forEach { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("editor-video-wallpaper-glass-support-${method.name}")
                    .intercept { true }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed video-wallpaper glass support hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Video-wallpaper glass support hook unavailable", error)
        }
    }

    private fun installAodLockscreenTemplateLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        val mode = preferences.getInt(
            KEY_LOCKSCREEN_TEMPLATE_LIMIT_MODE,
            LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT,
        ).coerceIn(LOCKSCREEN_TEMPLATE_LIMIT_SYSTEM_DEFAULT, LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM)
        val limit = when (mode) {
            LOCKSCREEN_TEMPLATE_LIMIT_50 -> 50
            LOCKSCREEN_TEMPLATE_LIMIT_60 -> 60
            LOCKSCREEN_TEMPLATE_LIMIT_80 -> 80
            LOCKSCREEN_TEMPLATE_LIMIT_100 -> 100
            LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM -> preferences.getInt(
                KEY_LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM,
                50,
            ).coerceIn(20, 200)
            else -> return
        }
        runCatching {
            // The editor's visible model field is only used by some builds.  The actual
            // persistence limit in DEV-2446 is enforced in TemplateApiImpl.insertHistoryConfig:
            // once the history count reaches 20 it asks the DAO for the oldest entries and
            // deletes them.  Hook that DAO query as well, otherwise changing the model field
            // appears to work in the UI but newly saved combinations are still trimmed to 20.
            val historyDaoClass = classLoader.loadClass(
                "com.miui.keyguard.editor.data.db.TemplateHistoryDao_Impl",
            )
            val oldestHistoryMethod = historyDaoClass.getMethod("getOldestHistory", Int::class.javaPrimitiveType)
            hook(oldestHistoryMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-lockscreen-template-history-limit")
                .intercept { chain ->
                    val requested = (chain.getArg(0) as? Number)?.toInt() ?: 0
                    val adjusted = requested + 20 - limit
                    if (adjusted <= 0) {
                        emptyList<Any>()
                    } else {
                        chain.proceedWith(chain.thisObject, arrayOf(adjusted))
                    }
                }

            val modelClass = classLoader.loadClass(
                "com.miui.keyguard.editor.homepage.model.CrossListDataModel",
            )
            // AOD DEV builds expose the Kotlin property as _maxTemplateCount, while the
            // ThemeManager 11.5.2 build keeps the R8-mapped field name f53460qrj.
            val limitField = sequenceOf("_maxTemplateCount", "f53460qrj")
                .mapNotNull { name -> runCatching { modelClass.getDeclaredField(name) }.getOrNull() }
                .firstOrNull()
                ?.apply { isAccessible = true }
                ?: error("CrossListDataModel template limit field not found")
            modelClass.declaredConstructors.forEachIndexed { index, constructor ->
                constructor.isAccessible = true
                hook(constructor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-lockscreen-template-limit-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            limitField.setInt(chain.thisObject, limit)
                        }.onFailure { error ->
                            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not set AOD lockscreen template limit", error)
                        }
                        result
                    }
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed AOD lockscreen template limit hook: $limit")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.WARN, TAG, "Could not install AOD lockscreen template limit hook", error)
        }
    }

    private fun installAodEditorBackgroundHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        runCatching {
            val activityClass = classLoader.loadClass("com.miui.keyguard.editor.EditorActivity")
            val init = activityClass.getDeclaredMethod("initContentView").apply { isAccessible = true }
            hook(init)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-editor-background")
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? android.app.Activity ?: return@intercept result
                    val mode = preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_MODE, LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM)
                    if (mode == LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM) return@intercept result
                    val rootId = activity.resources.getIdentifier("kg_editor_background", "id", activity.packageName)
                    val root = activity.findViewById<ViewGroup>(rootId) ?: return@intercept result
                    if (mode == LOCKSCREEN_EDITOR_BACKGROUND_LOCKSCREEN) {
                        root.postDelayed({
                            captureCurrentEditorWallpaper(root)?.let { bitmap ->
                                applyAodEditorBackground(activity, root, bitmap, preferences)
                            }
                        }, 700L)
                        return@intercept result
                    }
                    val bitmap = runCatching {
                            activity.contentResolver.openFileDescriptor(
                                android.net.Uri.parse("content://btm.m.os4.systemuihook.settingsappearance/${SettingsAppearanceProvider.LOCKSCREEN_EDITOR_BACKGROUND_SLOT}"),
                                "r",
                            )?.use { descriptor -> BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor) }
                        }.getOrNull() ?: return@intercept result
                    applyAodEditorBackground(activity, root, bitmap, preferences)
                    result
                }
            val wallpaperClass = classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.CombinedWallpaperView")
            wallpaperClass.declaredMethods.filter { it.name == "switchWallpaperView" }.forEach { method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-editor-background-sync-${method.parameterTypes.size}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val wallpaperView = chain.thisObject as? View
                        val activity = wallpaperView?.context as? android.app.Activity
                        if (wallpaperView != null && activity != null &&
                            preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_MODE, LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM) == LOCKSCREEN_EDITOR_BACKGROUND_LOCKSCREEN
                        ) {
                            val root = activity.findViewById<ViewGroup>(activity.resources.getIdentifier("kg_editor_background", "id", activity.packageName))
                            root?.postDelayed({
                                captureCurrentEditorWallpaper(root)?.let { bitmap ->
                                    applyAodEditorBackground(activity, root, bitmap, preferences)
                                }
                            }, 0L)
                        }
                        result
                    }
            }
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "AOD editor background hook unavailable", error)
        }
    }

    private fun applyAodEditorBackground(
        activity: android.app.Activity,
        root: ViewGroup,
        bitmap: Bitmap,
        preferences: SharedPreferences,
    ) {
                    val backgroundView = (root.findViewWithTag<View>("hyperchanger-editor-background") as? ImageView)
                        ?: ImageView(activity).also { view ->
                            view.tag = "hyperchanger-editor-background"
                            view.scaleType = ImageView.ScaleType.CENTER_CROP
                            root.addView(view, 0, ViewGroup.LayoutParams(-1, -1))
                        }
                    backgroundView.setImageBitmap(bitmap)
                    backgroundView.alpha = preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_OPACITY, 100).coerceIn(0, 100) / 100f
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val radius = preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_BLUR, 0).coerceIn(0, 100) * 0.5f
                        backgroundView.setRenderEffect(if (radius > 0f) android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP) else null)
                    }
    }

    private fun captureCurrentEditorWallpaper(root: View): Bitmap? {
        val wallpaper = findViewByClassName(root, "com.miui.keyguard.editor.edit.wallpaper.CombinedWallpaperView")
            ?: return null
        return runCatching {
            val field = wallpaper.javaClass.getDeclaredField("currentWallpaperView").apply { isAccessible = true }
            val layer = field.get(wallpaper) ?: return@runCatching null
            val method = layer.javaClass.methods.firstOrNull { it.name == "getWallpaperBitmap" && it.parameterTypes.size == 1 }
                ?: return@runCatching null
            val bitmap = Bitmap.createBitmap(wallpaper.width.coerceAtLeast(1), wallpaper.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            method.invoke(layer, bitmap) as? Bitmap
        }.getOrNull()
    }

    private fun findViewByClassName(view: View, name: String): View? {
        if (view.javaClass.name == name) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findViewByClassName(view.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).also { canvas -> drawable.setBounds(0, 0, canvas.width, canvas.height); drawable.draw(canvas) }
        }
    }

    /**
     * MIUI's physical-key volume panel lives in the SystemUI plugin, separate from the
     * Control Center slider.  Keep these hooks class-name based and protective because the
     * panel is replaced by the AOSP Compose dialog on some builds.
     */
    private fun installVolumePanelHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        volumeGlassRuntime = VolumePanelGlassRuntimeSettings(preferences)
        installVolumeNativeParameterHooks(preferences)
        installBackgroundBlurRadiusDispatchHook(classLoader, preferences)
        runCatching {
            installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_PANEL_CONTROLLER_CLASS), preferences)
            installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_COLUMN_CLASS), preferences)
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Volume panel classes are deferred until ClassLoader discovery", error)
        }
        listOf("com.android.systemui.miui.volume.MiuiVolumeDialogView", "com.android.systemui.miui.volume.MiuiVolumeSeekBar").forEach { name ->
            runCatching { installLoadedVolumePanelHook(classLoader.loadClass(name), preferences) }
        }
        runCatching { installLoadedVolumePanelHook(classLoader.loadClass("com.android.systemui.miui.volume.Util"), preferences) }
        runCatching { installLoadedVolumePanelHook(classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS), preferences) }
    }

    /**
     * MiBackgroundStyle is the common entry point used by both the shade elements and the
     * physical-key volume dialog.  The latter calls it on every blur animation frame, so
     * changing only View's final setters is too late and gets overwritten immediately.
     */
    private fun installBackgroundBlurRadiusDispatchHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val styleClass = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)
            val method = styleClass.getMethod(
                "setBackgroundBlurRadius",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("background-blur-radius-dispatch")
                .intercept { chain ->
                    val view = chain.getArg(0) as? View
                    if (volumeGlobalGlassSelected()) return@intercept chain.proceed()
                    val materialEnabled = preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true)
                    val volumeCall = view != null && (
                        isVolumePanelSurface(view) ||
                            stackContainsClass("com.android.systemui.miui.volume.VolumePanel")
                        )
                    if (materialEnabled && volumeCall && !volumePanelUsesGlobalGlass(preferences)) {
                        val blur = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24)
                            .coerceIn(0, 120)
                        val glass = preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50)
                            .coerceIn(0, 100)
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(view, blur, glass, (glass * 10).coerceIn(0, 1000)),
                        )
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed background blur-radius dispatch hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Background blur-radius dispatch hook unavailable", error)
        }
    }

    /**
     * VolumePanelViewController reapplies the stock blur recipe during every show/expand
     * animation. Hook the platform setters as well as the controller refresh so the user's
     * values remain the final inputs to the native renderer instead of being overwritten by
     * that animation.
     */
    private fun installVolumeNativeParameterHooks(preferences: SharedPreferences) {
        if (volumeNativeParameterHooksInstalled) return
        volumeNativeParameterHooksInstalled = true
        runCatching {
            hook(View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("volume-panel-native-background-blur")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (volumeGlassRuntime?.state?.let { it.materialEnabled && !it.useGlobal } == true &&
                        view != null && isVolumePanelSurface(view)
                    ) {
                        val radius = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120)
                        logVolumeNativeParameterHit("blur", view, radius)
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(radius),
                        )
                    } else {
                        chain.proceed()
                    }
                }

            hook(View::class.java.getMethod(
                "setMiGlassBlurRadius",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("volume-panel-native-glass-blur")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (volumeGlassRuntime?.state?.let { it.materialEnabled && !it.useGlobal } == true &&
                        view != null && isVolumePanelSurface(view)
                    ) {
                        val strength = preferences
                            .getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50)
                            .coerceIn(0, 100)
                        logVolumeNativeParameterHit("glass", view, strength)
                        // MIUI's volume recipe uses the 1:10 small/large glass-radius pair
                        // (50/500 by default). Keep that native relationship while exposing a
                        // single 0..100 control in the settings UI.
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(strength, (strength * 10).coerceIn(0, 1000)),
                        )
                    } else {
                        chain.proceed()
                    }
                }

            hook(View::class.java.getMethod("setMiGlass", FloatArray::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("volume-panel-native-glass-material")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (volumeGlassRuntime?.state?.let { it.materialEnabled && !it.useGlobal } == true &&
                        view != null && isVolumePanelSurface(view)
                    ) {
                        val strength = preferences
                            .getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50)
                            .coerceIn(0, 100)
                        invokeTwoIntSetter(view, "setMiGlassBlurRadius", strength, strength * 10)
                        logVolumeNativeParameterHit("glass-material", view, strength)
                    }
                    result
                }

            hook(View::class.java.getMethod("setBackgroundBlurAlpha", Float::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("volume-panel-native-background-opacity")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (volumeGlassRuntime?.state?.let { it.materialEnabled && !it.useGlobal } == true &&
                        view != null && isVolumePanelSurface(view)
                    ) {
                        val opacity = preferences.getInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, 100)
                            .coerceIn(0, 100)
                        logVolumeNativeParameterHit("opacity", view, opacity)
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(opacity / 100f),
                        )
                    } else {
                        chain.proceed()
                    }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed volume native parameter hooks")
        }.onFailure { error ->
            volumeNativeParameterHooksInstalled = false
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.ERROR, TAG, "Could not install volume native parameter hooks", error)
        }
    }

    private fun logVolumeNativeParameterHit(name: String, view: View, value: Int) {
        val key = "$name:${view.javaClass.name}"
        if (volumeNativeParameterHookHits.add(key)) {
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Volume parameter hit name=$name value=$value class=${view.javaClass.name}")
        }
    }

    private fun findVolumeLongPressPanel(slider: View): View? {
        var current = slider.parent
        while (current is View) {
            if (current.javaClass.name == "com.android.systemui.miui.volume.MiuiVolumeDialogView") return current
            current = current.parent
        }
        return null
    }

    private val volumeLongPress = VolumePanelLongPress(
        canExpand = { slider ->
            val panel = findVolumeLongPressPanel(slider)
            volumeGlassRuntime?.layout?.longPressExpansionEnabled == true && panel != null &&
                readField(panel, "isControlCenterPanel") != true &&
                readField(panel, "expandListener") is View.OnClickListener &&
                runCatching { panel.javaClass.getMethod("isExpanded").invoke(panel) == false }.getOrDefault(false)
        },
        expand = { slider ->
            findVolumeLongPressPanel(slider)?.let { panel ->
                (readField(panel, "expandListener") as? View.OnClickListener)?.onClick(slider)
            }
        },
    )

    private fun installLoadedVolumePanelHook(targetClass: Class<*>, preferences: SharedPreferences) {
        if (!volumePanelHookedClasses.add(targetClass)) return
        when (targetClass.name) {
            MI_BACKGROUND_STYLE_CLASS -> {
                runCatching {
                    val bionics = targetClass.classLoader.loadClass("miui.systemui.util.MaterialMode\$Bionics")
                        .getField("INSTANCE").get(null)
                    hookDeoptimized(targetClass.getDeclaredMethod("getMaterialMode"))
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("volume-panel-global-material:mode").intercept { chain ->
                            // Util's capability flags alone are insufficient: MiBackgroundStyle
                            // independently reads the repository and may still apply Classic.
                            if (volumeMaterialDispatching.get() == true) bionics else chain.proceed()
                        }
                    targetClass.declaredMethods.filter {
                        it.name == "setMiBackgroundStyle" && it.parameterTypes.firstOrNull() == View::class.java
                    }.forEach { method ->
                        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("volume-panel-global-material:style:${method.parameterCount}").intercept { chain ->
                                val view = chain.getArg(0) as? View
                                val forceGlass = volumeGlobalGlassSelected() && softGlassRuntime?.settings?.enabled == true &&
                                    view != null && (isVolumePanelSurface(view) || stackContainsClass("com.android.systemui.miui.volume."))
                                if (!forceGlass) return@intercept chain.proceed()
                                volumePanelSurfaceRoots.add(view!!)
                                val previous = volumeMaterialDispatching.get()
                                volumeMaterialDispatching.set(true)
                                try { chain.proceed() } finally {
                                    if (previous == null) volumeMaterialDispatching.remove() else volumeMaterialDispatching.set(previous)
                                }
                            }
                    }
                }.onFailure { btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Volume native material dispatch unavailable", it) }
            }
            "com.android.systemui.miui.volume.Util" -> {
                // HyperLight r71.q: enable only the volume material branch. No sizing,
                // visibility, dialog-motion or global feature-flag hooks are imported.
                targetClass.declaredMethods.filter {
                    it.name in setOf("isBionicsAdvancedMaterialEnabled", "isAdvancedMaterialEffective") &&
                        it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.contentEquals(arrayOf(Context::class.java))
                }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("volume-panel-global-material:${method.name}").intercept { chain ->
                            if (volumeGlobalGlassSelected() && softGlassRuntime?.settings?.enabled == true) true
                            else chain.proceed()
                        }
                }
            }
            VOLUME_PANEL_CONTROLLER_CLASS -> {
                targetClass.declaredMethods
                    .filter { it.name in VOLUME_PANEL_REFRESH_METHODS }
                    .forEach { method ->
                        hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("volume-panel:" + method.name + ":" + method.parameterCount)
                            .intercept { chain ->
                                val result = chain.proceed()
                                // Column updates run for every drag tick. Material is already
                                // handled by View setters; walk corners only on panel lifecycle.
                                if (!volumeGlobalGlassSelected() || method.name in VOLUME_PANEL_LAYOUT_METHODS) {
                                    applyVolumePanelNativeTuning(chain.thisObject, preferences)
                                }
                                result
                            }
                    }
                btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed physical volume-panel controller hooks")
            }
            "com.android.systemui.miui.volume.MiuiVolumeDialogView" -> {
                val ringerStates = java.util.WeakHashMap<View, VolumePanelRingerVisibility>()
                targetClass.declaredMethods.filter { it.name in setOf("onFinishInflate", "updateFooterVisibility", "onExpandStateUpdated") }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("volume-panel-buttons:${method.name}").intercept { chain ->
                            val result = chain.proceed()
                            val settings = volumeGlassRuntime?.layout ?: VolumePanelLayoutPreferences.read(preferences)
                            val expanded = runCatching { targetClass.getMethod("isExpanded").invoke(chain.thisObject) as Boolean }.getOrDefault(true)
                            val controlCenter = readField(chain.thisObject, "isControlCenterPanel") == true
                            (readField(chain.thisObject, "mRingerModeLayout") as? View)?.let { ringer ->
                                val state = ringerStates.getOrPut(ringer) { VolumePanelRingerVisibility() }
                                val visibility = state.resolve(ringer.visibility, method.name != "onExpandStateUpdated", settings.hideRinger, expanded, controlCenter)
                                if (ringer.visibility != visibility) ringer.visibility = visibility
                            }
                            if (settings.hideExpand) (readField(chain.thisObject, "mExpandButton") as? View)?.visibility = View.GONE
                            result
                        }
                }
            }
            "com.android.systemui.miui.volume.MiuiVolumeSeekBar" -> {
                targetClass.declaredMethods.filter {
                    it.name == "onTouchEvent" && it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
                }.forEach { method ->
                    hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("volume-panel-long-press").intercept { chain ->
                            val view = chain.thisObject as? View
                            val event = chain.getArg(0) as? MotionEvent
                            if (view != null && event != null && volumeLongPress.onTouch(view, event)) true else chain.proceed()
                        }
                }
            }
        }
    }

    private fun installAospVolumePanelFallback(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val dialogClass = classLoader.loadClass(AOSP_VOLUME_DIALOG_CLASS)
            dialogClass.declaredMethods
                .filter { it.name == "onCreate" || it.name == "show" }
                .forEach { method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("volume-panel:aosp:${method.name}:${method.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching {
                                val window = chain.thisObject?.javaClass?.getMethod("getWindow")
                                    ?.invoke(chain.thisObject) as? android.view.Window
                                window?.decorView?.let { applyVolumePanelRootTuning(it, preferences) }
                            }
                            result
                        }
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Installed AOSP Compose volume-panel fallback hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "AOSP Compose volume dialog is unavailable", error)
        }
    }

    private fun findVolumeControllerRoot(controller: Any?): View? {
        if (controller == null) return null
        listOf("mVolumePanelView", "mVolumeView", "mVolumeContentView").forEach { fieldName ->
            runCatching {
                controller.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                    .get(controller)
            }.getOrNull()?.let { value ->
                if (value is View) return value
            }
        }
        return null
    }

    /**
     * The MIUI implementation already creates and owns these blur/glass surfaces.  Tune their
     * native inputs instead of attaching a second backdrop or an overlay TextView.
     */
    private fun applyVolumePanelNativeTuning(controller: Any?, preferences: SharedPreferences) {
        val root = findVolumeControllerRoot(controller) ?: return
        applyVolumePanelRootTuning(root, preferences)
    }

    private fun applyVolumePanelRootTuning(root: View, preferences: SharedPreferences) {
        val runtime = volumeGlassRuntime?.state ?: return
        if (!runtime.materialEnabled) {
            synchronized(volumePanelAppliedTuning) {
                volumePanelAppliedTuning.remove(root)
            }
            return
        }
        if (runtime.useGlobal) {
            // Leave the stock recipe, blur radii, opacity and animation progress to the
            // common global setter hooks. Reapplying custom defaults here would both
            // overwrite the system baseline and multiply the global adjustments twice.
            synchronized(volumePanelAppliedTuning) { volumePanelAppliedTuning.remove(root) }
            fun restoreAlpha(view: View) {
                view.background?.let { background ->
                    synchronized(volumeOriginalDrawableAlphas) { volumeOriginalDrawableAlphas.remove(background) }
                        ?.let { background.alpha = it }
                }
                if (view is ViewGroup) repeat(view.childCount) { restoreAlpha(view.getChildAt(it)) }
            }
            restoreAlpha(root)
            return
        }
        val blurRadius = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120)
        val glassStrength = preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50).coerceIn(0, 100)
        val backgroundOpacity = preferences.getInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, 100).coerceIn(0, 100)
        val viewCount = countViewTree(root)
        val tuning = VolumeTuningSnapshot(blurRadius, glassStrength, backgroundOpacity, viewCount)
        synchronized(volumePanelAppliedTuning) {
            if (volumePanelAppliedTuning[root] == tuning) return
            volumePanelAppliedTuning[root] = tuning
        }
        fun visit(view: View) {
            val background = view.background
            val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
            val className = view.javaClass.name
            if (className.endsWith("ExpandBlurFrameLayout") ||
                className.endsWith("VolumeBlurFrameLayout")
            ) {
                if (volumePanelNativeApiLogged.add(className)) {
                    val api = view.javaClass.methods
                        .filter { method ->
                            method.name.contains("blur", ignoreCase = true) ||
                                method.name.contains("glass", ignoreCase = true) ||
                                method.name.contains("radius", ignoreCase = true) ||
                                method.name.contains("background", ignoreCase = true)
                        }
                        .joinToString(",") { method ->
                            "${method.name}(${method.parameterTypes.joinToString("/") { it.simpleName }})"
                        }
                    btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.INFO, TAG, "Volume native API class=$className methods=$api")
                }
                invokeNumericSetter(view, "setBlurRadius", blurRadius.toFloat())
                invokeNumericSetter(view, "setMiBackgroundBlurRadius", blurRadius.toFloat())
                invokeTwoIntSetter(view, "setMiGlassBlurRadius", glassStrength, glassStrength * 10)
                invokeNumericSetter(view, "setBackgroundBlurAlpha", backgroundOpacity / 100f)
                invokeBooleanSetter(view, "setBlurEnabled", blurRadius > 0)
            }
            if (idName == "volume_column_slider" || idName == "volume_column_slider_bg_glass") {
                invokeTwoIntSetter(view, "setMiGlassBlurRadius", glassStrength, glassStrength * 10)
            }
            if (background != null && background.javaClass.name.contains("BackgroundBlurDrawable")) {
                invokeIfPresent(background, "setBlurRadius", arrayOf(blurRadius))

            }
            if (background != null && (view === root ||
                    idName.contains("volume_dialog_content") ||
                    idName.contains("blur_frame") ||
                    idName.contains("background") ||
                    idName.contains("bg_blur"))) {
                synchronized(volumeOriginalDrawableAlphas) { volumeOriginalDrawableAlphas.putIfAbsent(background, background.alpha) }
                background.alpha = (backgroundOpacity * 255 / 100).coerceIn(0, 255)
            }
            if (background != null && (idName.contains("glass") ||
                    idName.contains("bg_blur") ||
                    className.contains("VolumeBlurFrameLayout"))) {
                // The plugin already owns this glass drawable; tune its native surface opacity.
                synchronized(volumeOriginalDrawableAlphas) { volumeOriginalDrawableAlphas.putIfAbsent(background, background.alpha) }
                background.alpha = (glassStrength * 255 / 100).coerceIn(0, 255)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun countViewTree(root: View): Int {
        if (root !is ViewGroup) return 1
        var count = 1
        for (index in 0 until root.childCount) count += countViewTree(root.getChildAt(index))
        return count
    }

    private fun invokeMiBlurOutline(view: View, radius: Float) {
        runCatching {
            val helper = Class.forName(MI_BLUR_COMPAT_CLASS, false, view.javaClass.classLoader)
            helper.getMethod(
                "setOutlineRoundRect",
                View::class.java,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, view, radius, true)
        }.onFailure {
            runCatching {
                val helper = Class.forName(MI_BLUR_COMPAT_CLASS, false, view.javaClass.classLoader)
                helper.getMethod(
                    "setBlurOutlineRoundRect",
                    View::class.java,
                    Float::class.javaPrimitiveType,
                ).invoke(null, view, radius)
            }
        }
    }

    private fun invokeIfPresent(target: Any, name: String, args: Array<Any?>) {
        target.javaClass.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == args.size
        }?.let { method -> runCatching { method.invoke(target, *args) } }
    }

    private fun invokeNumericSetter(target: Any, name: String, value: Float): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                (it.parameterTypes[0] == Float::class.javaPrimitiveType ||
                    it.parameterTypes[0] == java.lang.Float::class.java ||
                    it.parameterTypes[0] == Double::class.javaPrimitiveType ||
                    it.parameterTypes[0] == java.lang.Double::class.java ||
                    it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    it.parameterTypes[0] == java.lang.Integer::class.java ||
                    it.parameterTypes[0] == Long::class.javaPrimitiveType ||
                    it.parameterTypes[0] == java.lang.Long::class.java)
        } ?: return false
        val argument: Any = when (method.parameterTypes[0]) {
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> value
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> value.toDouble()
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> value.toLong()
            else -> value.toInt()
        }
        return runCatching { method.invoke(target, argument); true }.getOrDefault(false)
    }

    private fun invokeTwoIntSetter(target: Any, name: String, first: Int, second: Int): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return false
        return runCatching {
            method.invoke(target, first.coerceAtLeast(0), second.coerceAtLeast(0))
            true
        }.getOrDefault(false)
    }

    private fun invokeBooleanSetter(target: Any, name: String, value: Boolean): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                (it.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                    it.parameterTypes[0] == java.lang.Boolean::class.java)
        } ?: return false
        return runCatching { method.invoke(target, value); true }.getOrDefault(false)
    }

    private fun readField(target: Any?, name: String): Any? = runCatching {
        target?.javaClass?.getDeclaredField(name)?.apply { isAccessible = true }?.get(target)
    }.getOrNull()

    private fun writeField(target: Any, name: String, value: Any) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }

    private fun installLoadedCornerRadiusHook(
        targetClass: Class<*>,
        preferences: SharedPreferences,
    ) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        if (!cornerTargetClasses.add(targetClass)) return
        when (targetClass.name) {
            TOP_BUTTONS_CLASS -> hookUnifiedRadiusSetter(targetClass, "setCornerRadius", 0, preferences)
            MEDIA_PANEL_CLASS -> hookUnifiedRadiusSetter(targetClass, "setCornerRadius", 1, preferences)
            DEVICE_CENTER_ENTRY -> hookDeviceCenterOutline(targetClass, preferences)
            SLIDER_VIEW_HOLDER_CLASS,
            BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS -> hookUnifiedRadiusSetter(targetClass, "setOutlineRadius", 2, preferences)
            SECONDARY_VOLUME_PANEL_CLASS -> hookUnifiedRadiusSetter(targetClass, "getCornerRadius", 2, preferences)
            "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView" -> hookUnifiedRadiusSetter(targetClass, "setCornerRadius", 3, preferences)
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder" -> hookUnifiedRadiusSetter(targetClass, "setCornerRadius", 1, preferences)
            SECONDARY_PANEL_CONTROLLER_BASE_CLASS -> {
                hookUnifiedRadiusSetter(targetClass, "setContentBgRadius", 5, preferences)
                runCatching {
                    hookDeoptimized(targetClass.getDeclaredMethod("updateContentBg", Boolean::class.javaPrimitiveType))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).setId("control-center-expanded-background-radius")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val target = chain.thisObject ?: return@intercept result
                            val settings = controlRadiusSettings(preferences)
                            if (settings.enabled && settings.expandedPanel != 0) {
                                val view = runCatching { target.javaClass.getMethod("getContentBg").invoke(target) as? View }.getOrNull()
                                val radius = readInstanceField(target, "contentBgRadius") as? Float
                                if (view != null && radius != null) (view.background as? GradientDrawable)?.mutate()?.let { (it as GradientDrawable).cornerRadius = radius }
                            }
                            result
                        }
                }
            }
            MI_BACKGROUND_STYLE_CLASS -> hookMaterialStyle(targetClass, preferences)
            else -> cornerTargetClasses.remove(targetClass)
        }
    }

    private val controlRadiusDispatch = ThreadLocal<Int?>()
    private val controlRadiusBaselines = WeakHashMap<Any, MutableMap<String, ControlCenterRadiusBaseline>>()
    private val controlDrawableBaselines = WeakHashMap<Drawable, ControlCenterRadiusBaseline>()
    private val controlOriginalOutlines = WeakHashMap<View, Pair<ViewOutlineProvider?, Boolean>>()

    private fun controlRadiusSettings(preferences: SharedPreferences) =
        controlCenterRadiusRuntime?.settings ?: ControlCenterRadiusPreferences.read(preferences)

    private fun controlRadiusView(target: Any?): View? {
        if (target == null) return null
        if (target is View) return target
        (readInstanceField(target, "itemView") as? View)?.let { return it }
        resolveSliderView(target)?.let { return it }
        listOf("getContentBg", "getView").forEach { name ->
            runCatching { target?.javaClass?.getMethod(name)?.invoke(target) as? View }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun controlRadiusOffset(settings: ControlCenterRadiusSettings, category: Int) = when (category) {
        0 -> settings.topButtons
        1 -> settings.mediaCard
        2 -> settings.sliders
        3 -> settings.smallButtons
        4 -> settings.deviceCenter
        else -> settings.expandedPanel
    }

    private fun resolveControlRadius(target: Any, name: String, input: Float, density: Float, settings: ControlCenterRadiusSettings, category: Int): Float =
        synchronized(controlRadiusBaselines) {
            controlRadiusBaselines.getOrPut(target) { mutableMapOf() }
                .getOrPut(name) { ControlCenterRadiusBaseline() }
                .resolve(input, controlRadiusOffset(settings, category), density, settings.enabled)
        }

    private fun updateControlOutline(view: View, radius: Float, active: Boolean) {
        if (active) {
            controlOriginalOutlines.putIfAbsent(view, view.outlineProvider to view.clipToOutline)
            applyRoundedOutline(view, radius)
        } else {
            controlOriginalOutlines.remove(view)?.let { (provider, clip) ->
                view.outlineProvider = provider
                view.clipToOutline = clip
                view.invalidateOutline()
            }
        }
    }

    private fun hookUnifiedRadiusSetter(targetClass: Class<*>, methodName: String, category: Int, preferences: SharedPreferences) {
        runCatching {
            val method = if (methodName.startsWith("get")) targetClass.getMethod(methodName)
                else targetClass.getMethod(methodName, Float::class.javaPrimitiveType)
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("unified-control-center-radius:${targetClass.name}#$methodName")
                .intercept { chain ->
                    val target = chain.thisObject ?: return@intercept chain.proceed()
                    if (controlRadiusDispatch.get() == category) return@intercept chain.proceed()
                    val settings = controlRadiusSettings(preferences)
                    val view = controlRadiusView(target) ?: return@intercept chain.proceed()
                    val density = view.resources.displayMetrics.density
                    if (method.parameterCount == 0) {
                        // VolumePanelController's setter is empty; its getter supplies transition geometry.
                        val original = chain.proceed() as? Float ?: return@intercept null
                        adjustedControlCenterRadius(original, controlRadiusOffset(settings, category), density, settings.enabled)
                    } else {
                        val active = settings.enabled && controlRadiusOffset(settings, category) != 0
                        val hadBaseline = synchronized(controlRadiusBaselines) { controlRadiusBaselines[target]?.containsKey(methodName) == true }
                        if (!active && !hadBaseline) return@intercept chain.proceed()
                        val radius = resolveControlRadius(target, methodName, chain.getArg(0) as Float, density, settings, category)
                        val previous = controlRadiusDispatch.get()
                        controlRadiusDispatch.set(category)
                        try {
                            val result = chain.proceedWith(target, arrayOf(radius))
                            if (category == 0) updateControlOutline(view, radius, active)
                            if (category == 1) (view.background as? GradientDrawable)?.mutate()?.let { (it as GradientDrawable).cornerRadius = radius }
                            if (category == 2) {
                                val originalRadius = synchronized(controlRadiusBaselines) { controlRadiusBaselines[target]?.get(methodName)?.originalRadius }
                                applySliderDrawableRadius(target, radius, originalRadius)
                            }
                            result
                        } finally {
                            if (previous == null) controlRadiusDispatch.remove() else controlRadiusDispatch.set(previous)
                        }
                    }
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Control-center radius hook unavailable: ${targetClass.name}#$methodName", error) }
    }

    private fun applyControlCenterDrawableRadius(view: View, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.CONTROL_CENTER)) return
        val settings = controlRadiusSettings(preferences)
        if ((!settings.enabled || settings.sliders == 0 && settings.deviceCenter == 0) && controlDrawableBaselines.isEmpty()) return
        val device = view.javaClass.name == DEVICE_CENTER_ENTRY
        if (!device && !isControlCenterSliderBackgroundPart(view)) return
        val offset = if (device) settings.deviceCenter else settings.sliders
        val drawable = view.background as? GradientDrawable ?: return
        if ((!settings.enabled || offset == 0) && !controlDrawableBaselines.containsKey(drawable)) {
            if (device) updateControlOutline(view, drawable.cornerRadius, false)
            return
        }
        val radius = synchronized(controlDrawableBaselines) {
            controlDrawableBaselines.getOrPut(drawable) { ControlCenterRadiusBaseline() }
                .resolve(drawable.cornerRadius, offset, view.resources.displayMetrics.density, settings.enabled)
        }
        // Mutate before editing so another surface using the same resource keeps its own radius.
        (drawable.mutate() as GradientDrawable).cornerRadius = radius
        if (device) updateControlOutline(view, radius, settings.enabled && offset != 0)
        view.invalidateOutline()
    }

    private fun hookMaterialStyle(targetClass: Class<*>, preferences: SharedPreferences) {
        targetClass.declaredMethods.filter {
            it.name == "setMiBackgroundStyle" && it.parameterTypes.firstOrNull() == View::class.java
        }.forEach { method ->
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("material-style:${method.parameterCount}").intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.getArg(0) as? View ?: return@intercept result
                    if (view.javaClass.name == TOP_BUTTONS_CLASS) {
                        val settings = controlRadiusSettings(preferences)
                        val input = readField(view, "_cornerRadius") as? Float
                        if (input != null) {
                            val radius = resolveControlRadius(view, "setCornerRadius", input, view.resources.displayMetrics.density, settings, 0)
                            updateControlOutline(view, radius, settings.enabled && settings.topButtons != 0)
                        }
                    }
                    applyControlCenterDrawableRadius(view, preferences)
                    result
                }
        }
    }

    private fun hookDeviceCenterOutline(targetClass: Class<*>, preferences: SharedPreferences) {
        runCatching {
            hookDeoptimized(targetClass.getMethod("onFinishInflate"))
                .setExceptionMode(ExceptionMode.PROTECTIVE).setId("control-center-device-radius")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View ?: return@intercept result
                    applyControlCenterDrawableRadius(view, preferences)
                    result
                }
        }.onFailure { error -> btm.m.os4.systemuihook.HookModuleLogger.log(this, Log.DEBUG, TAG, "Device center radius hook unavailable", error) }
    }

    private fun replaceDimensionIfNeeded(
        resources: Resources,
        resourceId: Int,
        original: Float,
        preferences: SharedPreferences,
    ): Float = replacementDp(resources.entryName(resourceId), original / resources.displayMetrics.density, preferences)
        ?.let { it * resources.displayMetrics.density }
        ?: original

    private fun replaceDimensionPixelIfNeeded(
        resources: Resources,
        resourceId: Int,
        original: Int,
        preferences: SharedPreferences,
    ): Int = replacementDp(resources.entryName(resourceId), original / resources.displayMetrics.density, preferences)
        ?.let { (it * resources.displayMetrics.density + 0.5f).toInt() }
        ?: original

    private fun replacementDp(name: String?, originalDp: Float, preferences: SharedPreferences): Float? {
        val section = when (name) {
            "big_island_min_width", "small_island_width", "island_space" -> SafetySection.ISLAND
            "notification_item_bg_radius" -> SafetySection.NOTIFICATIONS
            "status_bar_clock_size_new", "status_bar_padding_end", "status_bar_padding_start",
            "status_bar_height", "status_bar_padding_top" -> SafetySection.STATUS_BAR
            else -> null
        }
        if (section != null && !ModuleSafetyRuntime.allows(section)) return null
        val layout = volumeGlassRuntime?.layout ?: VolumePanelLayoutPreferences.read(preferences)
        volumePanelLayoutDimensionDp(name, originalDp, layout)?.let { return it }
        if (!preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
            name in VOLUME_PANEL_MATERIAL_DIMENSION_NAMES
        ) return null
        return when (name) {
        // These are the dimensions consumed by VolumeColumnRes and the AOSP Compose dialog.
        // They are real inputs to the vendor volume panel, so the stock blur/glass pipeline
        // remains intact while its parameters become adjustable.
        "volume_dialog_background_blur_radius" ->
            preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120).toFloat()
        "volume_dialog_background_surface_blur_radius" ->
            (preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50).coerceIn(0, 100) * 1.2f)
        "big_island_min_width" -> readIslandWidthSettings(preferences)
            .takeIf { it.enabled }?.bigMinWidth?.toFloat()
        "small_island_width" -> readIslandWidthSettings(preferences)
            .takeIf { it.enabled }?.smallMinWidth?.toFloat()
        "island_space" -> islandSpacingDp(originalDp, readIslandSpacingSettings(preferences))
        "notification_item_bg_radius" -> preferences.getInt(KEY_NOTIFICATION_CORNER_RADIUS_OFFSET, 0)
            .coerceIn(-30, 30)
            .takeIf { it != 0 }
            ?.let { (originalDp + it).coerceAtLeast(0f) }
        "status_bar_clock_size_new" -> preferences.takeIf { it.getBoolean(KEY_CLOCK_ENABLED, false) }
            ?.getFloat(KEY_CLOCK_SIZE, 14.8f)?.coerceIn(10f, 24f)
        "status_bar_padding_end" -> preferences.takeIf { it.getBoolean(KEY_PADDING_END_ENABLED, false) }
            ?.let {
                if (it.contains(KEY_PADDING_END_LEGACY_ABSOLUTE)) {
                    it.getFloat(KEY_PADDING_END_LEGACY_ABSOLUTE, 0f).coerceIn(0f, 32f)
                } else {
                    (originalDp + it.getFloat(KEY_PADDING_END, 0f).coerceIn(-35f, 35f)).coerceAtLeast(0f)
                }
            }
        "status_bar_padding_start" -> preferences.takeIf { it.getBoolean(KEY_PADDING_START_ENABLED, false) }
            ?.getFloat(KEY_PADDING_START, 12.5f)?.coerceIn(0f, 32f)
        "status_bar_height" -> preferences.takeIf { it.getBoolean(KEY_HEIGHT_ENABLED, false) }
            ?.getInt(KEY_STATUS_BAR_HEIGHT, 40)?.coerceIn(24, 72)?.toFloat()
        "status_bar_padding_top" -> preferences.takeIf { it.getBoolean(KEY_PADDING_TOP_ENABLED, false) }
            ?.let {
                if (it.contains(KEY_PADDING_TOP_LEGACY_ABSOLUTE)) {
                    it.getFloat(KEY_PADDING_TOP_LEGACY_ABSOLUTE, 0f).coerceIn(0f, 32f)
                } else {
                    (originalDp + it.getFloat(KEY_PADDING_TOP, 0f).coerceIn(-35f, 35f)).coerceAtLeast(0f)
                }
            }
        else -> null
        }
    }

    private fun resolveSliderView(target: Any?): View? {
        if (target is View) return target
        readField(target, "binding")?.let { binding ->
            listOf("progressBg", "progress", "toggleSliderInner").forEach { field ->
                (readField(binding, field) as? View)?.let { return it }
            }
        }
        listOf("getVProgressBg", "getVProgress", "getVToggleSliderInner", "getVToggleSlider").forEach { methodName ->
            val view = runCatching {
                target?.javaClass?.methods
                    ?.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?.invoke(target) as? View
            }.getOrNull()
            if (view != null) return view
        }
        return null
    }

    private fun applySliderDrawableRadius(target: Any?, radius: Float, originalRadius: Float? = null) {
        if (target == null) return
        val views = mutableListOf<View>()
        readField(target, "binding")?.let { binding ->
            listOf("progressBg").forEach { field ->
                (readField(binding, field) as? View)?.let(views::add)
            }
        }
        listOf("getVProgressBg", "getVToggleSliderInner").forEach { methodName ->
            runCatching {
                target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?.invoke(target) as? View
            }.getOrNull()?.let(views::add)
        }
        views.distinct().forEach { view ->
            (view.background as? GradientDrawable)?.mutate()?.let { drawable ->
                (drawable as GradientDrawable).setCornerRadius(radius)
                if (originalRadius != null) synchronized(controlDrawableBaselines) {
                    controlDrawableBaselines.getOrPut(drawable) { ControlCenterRadiusBaseline() }.remember(originalRadius, radius)
                }
            }
            view.invalidateOutline()
        }
    }

    private fun Resources.entryName(resourceId: Int): String? = runCatching {
        getResourceEntryName(resourceId)
    }.getOrNull()

    private fun dpToPixels(view: View, value: Float): Float =
        value.coerceIn(0f, 60f) * view.resources.displayMetrics.density

    private fun isControlCenterSliderPart(view: View): Boolean {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return idName in SLIDER_PART_IDS && generateSequence(view.parent) { it.parent }
            .filterIsInstance<View>()
            .any { parent -> parent.javaClass.name.contains("ToggleSlider") }
    }

    private fun isControlCenterSliderBackgroundPart(view: View): Boolean {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return idName == "progress_bg" && isControlCenterSliderPart(view)
    }

    private fun volumeResourceName(view: View): String = synchronized(volumeResourceNames) {
        val id = view.id
        volumeResourceNames[view]?.takeIf { it.first == id }?.second ?: run {
            val name = if (id == View.NO_ID || id == 0) "" else runCatching { view.resources.getResourceEntryName(id) }.getOrDefault("")
            volumeResourceNames[view] = id to name
            name
        }
    }

    private fun isVolumePanelSurface(view: View): Boolean {
        if (volumePanelSurfaceRoots.contains(view)) return true
        var current: View? = view
        while (current != null) {
            if (volumePanelSurfaceRoots.contains(current)) {
                volumePanelSurfaceRoots.add(view)
                return true
            }
            val className = current.javaClass.name
            if (className == VOLUME_PANEL_VIEW_CLASS ||
                className.endsWith("ExpandBlurFrameLayout") ||
                className.endsWith("VolumeBlurFrameLayout")
            ) {
                volumePanelSurfaceRoots.add(view)
                return true
            }
            val idName = volumeResourceName(current)
            if (idName == "blur_frame" ||
                idName == "volume_column_slider" ||
                idName == "volume_column_slider_bg_glass" ||
                idName == "volume_column_slider_bg_blend"
            ) {
                volumePanelSurfaceRoots.add(view)
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun applyRoundedOutline(view: View, radius: Float) {
        val appliedByMiui = runCatching {
            val helper = Class.forName("miui.systemui.util.MiBlurCompat", false, view.javaClass.classLoader)
            helper.getMethod("setBlurOutlineRoundRect", View::class.java, Float::class.javaPrimitiveType)
                .invoke(null, view, radius)
        }.isSuccess
        if (appliedByMiui) return

        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radius)
            }
        }
        view.invalidateOutline()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val AIRDROP_PACKAGE = "com.miui.mishare.connectivity"
        private const val KEY_HIDE_APP_ICON = "hide_app_icon"
        private const val TAG = "HyperSystemUIHook"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val LOCKSCREEN_WALLPAPER = "com.miui.miwallpaper"
        private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
        private const val SYSTEM_UI_PLUGIN = "miui.systemui.plugin"
        private const val AOD = "com.miui.aod"
        private const val SUPER_XIAOAI_IME = "com.xiaomi.type"
        private const val SUPER_XIAOAI_PHRASE = "com.miui.phrase"
        private const val SUBSCREEN_CENTER = "com.xiaomi.subscreencenter"
        private const val PERSONAL_ASSISTANT = "com.miui.personalassistant"
        private const val THEME_MANAGER = "com.android.thememanager"
        private val SYSTEM_UI_TARGETS = setOf(SYSTEM_UI, SYSTEM_UI_PLUGIN, AOD, SUPER_XIAOAI_IME, SUPER_XIAOAI_PHRASE, SUBSCREEN_CENTER, PERSONAL_ASSISTANT, THEME_MANAGER)
        private const val DEPTH_EVALUATOR_CLASS =
            "com.miui.clock.utils.avoid.DepthAvoidEvaluator"
        private const val DEPTH_THRESHOLD_CLASS =
            "com.miui.clock.utils.avoid.DepthAvoidEvaluator\$Threshold"
        private const val HIERARCHY_AVOID_CONTROLLER_CLASS =
            "com.miui.keyguard.editor.utils.HierarchyImageAvoidController"
        private const val USER_OPEN_HIERARCHY_FIELD = "isUserOpenHierarchy"
        private const val FOD_SHELF_SPACE_FLOW_CLASS =
            "com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor\$useExtraShelfSpace\$1"
        private const val FOD_NOTIFICATION_POSITION_FLOW_PREFIX =
            "com.android.keyguard.panel.KeyguardPanelViewController\$nsslLockYPosition_delegate\$lambda\$"
        private const val FOD_NOTIFICATION_POSITION_FLOW_SUFFIX = "\$\$inlined\$combine\$1"
        private val FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE = 64..160
        private const val MIUI_GXZW_ICON_VIEW_CLASS =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView"
        private const val MIUI_GXZW_ANIM_MANAGER_CLASS =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager"
        private const val FOD_DISMISS_ICON_METHOD = "dismissFingerpirntIcon"
        private const val MIUI_GXZW_KEYGUARD_AUTHEN_FIELD = "mKeyguardAuthen"
        private const val MIUI_GXZW_ANIMATION_ITEMS_FIELD = "mAnimItemMap"
        private const val MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD = "getFingerIconResource"
        private const val MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD = "getRecognizingAnimItem"
        private const val KEYGUARD_DEPTH_INTERACTOR_CLASS =
            "com.android.keyguard.depth.KeyguardDepthInteractor"
        private const val KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController"
        private const val WALLPAPER_INFO_CLASS =
            "com.android.keyguard.wallpaper.entity.WallpaperInfo"
        private const val LARGE_SCREEN_HIERARCHY_ENABLE_CLASS =
            "com.android.keyguard.wallpaper.entity.LargeScreenHierarchyEnable"
        private const val AOD_WALLPAPER_INFO_CLASS =
            "com.miui.keyguard.editor.data.bean.WallpaperInfo"
        private const val AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS =
            "com.miui.keyguard.editor.data.bean.LargeScreenHierarchyEnable"
        private const val WALLPAPER_CONTROLLER_CLASS =
            "com.miui.keyguard.editor.edit.wallpaper.WallpaperController"
        private const val WALLPAPER_CONTROLLER_COMPANION_CLASS =
            "com.miui.keyguard.editor.edit.wallpaper.WallpaperController\$Companion"
        private const val MIUI_SHORTCUT_CONTROLLER_CLASS =
            "com.android.keyguard.shortcut.MiuiShortcutController"
        private const val KEYGUARD_EDITOR_HELPER_CLASS =
            "com.android.keyguard.editor.KeyguardEditorHelper"
        private const val MIUI_CHARGE_ANIMATION_VIEW_CLASS =
            "com.miui.charge.container.MiuiChargeAnimationView"
        private const val CONTROL_CENTER_EXPAND_LISTENER_CLASS =
            "com.miui.systemui.controlcenter.container.ControlCenterContainerController\$onExpandChangeListener\$1"
        private const val KEYGUARD_PIN_VIEW_CLASS = "com.android.keyguard.KeyguardPINView"
        private val LOCKSCREEN_PIN_KEY_IDS = setOf(
            "key0", "key1", "key2", "key3", "key4",
            "key5", "key6", "key7", "key8", "key9",
        )
        private val LOCKSCREEN_PIN_ROW_IDS = listOf("row1", "row2", "row3", "row4")
        private const val LOCKSCREEN_PIN_CIRCLE_TAG = "hyperchanger.lockscreen.pin.material"
        private const val LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR = 0x40FFFFFF
        private const val EXPANDABLE_NOTIFICATION_ROW_CLASS =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        private const val EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRowInjector"
        private const val MIUI_MEDIA_HEADER_VIEW_CLASS =
            "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
        private const val LOCKSCREEN_LYRIC_BUTTON_TAG = 0x7f0f0abe
        private const val LOCKSCREEN_LYRIC_BUTTON_STATE_TAG = 0x7f0f0abf
        private const val LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG = 0x7f0f0ac0
        private const val LOCKSCREEN_LYRIC_STATE_PREFS = "hyperchanger_lockscreen_lyrics"
        private const val LOCKSCREEN_LYRIC_STATE_SHOWING = "showing"
        private const val LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SHORT_DELAY_MS = 96L
        private const val LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SETTLE_DELAY_MS = 320L
        private const val MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat"
        private const val IMAGE_THRESHOLD_FIELD = "IMAGE_THRESHOLD"
        private const val THRESHOLD_RATE_FIELD = "rate"
        private const val DEFAULT_DEPTH_IMAGE_THRESHOLD = 0.2
        private const val UNLIMITED_DEPTH_IMAGE_THRESHOLD = 1.0
        private const val FOD_MODE_DEFAULT = 0
        private const val FOD_MODE_HIDE_ICON = 1
        private const val FOD_MODE_KEEP_ICON = 2
        private const val FINGERPRINT_HIDE_NONE = 0
        private const val FINGERPRINT_HIDE_LOCKSCREEN = 1
        private const val FINGERPRINT_HIDE_GLOBAL = 2
        private const val LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE = 0x7f080000
        private const val TOP_BUTTONS_CLASS =
            "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
        private const val CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS =
            "miui.systemui.controlcenter.panel.main.qs.EditButtonController"
        private const val CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS =
            "miui.systemui.controlcenter.panel.main.MainPanelContentDistributor"
        private const val CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS =
            "miui.systemui.controlcenter.panel.main.MainPanelController"
        private const val CONTROL_CENTER_EXPAND_CONTROLLER_CLASS =
            "miui.systemui.controlcenter.windowview.ControlCenterExpandController"
        private const val CONTROL_CENTER_EVENT_HANDLER_CLASS =
            "com.miui.systemui.controlcenter.container.ControlCenterEventHandlerImpl"
        private const val GLOBAL_ACTIONS_PLUGIN_CLASS = "miui.systemui.globalactions.GlobalActionsPlugin"
        private const val GLOBAL_ACTIONS_COMPONENT_CLASS =
            "com.android.systemui.globalactions.GlobalActionsComponent"
        private const val GLOBAL_ACTIONS_IMPL_CLASS =
            "com.android.systemui.globalactions.GlobalActionsImpl"
        private const val COMMAND_QUEUE_CLASS = "com.android.systemui.statusbar.CommandQueue"
        private const val ADD_CONTROL_CENTER_TOP_BUTTONS_KEY = "add_control_center_top_buttons"
        private const val KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE =
            "show_control_center_top_buttons_in_landscape"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE =
            "control_center_top_buttons_icon_scale"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE =
            "control_center_top_buttons_background_mode"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR =
            "control_center_top_buttons_pure_color"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS =
            "control_center_top_buttons_pure_background_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR =
            "control_center_top_buttons_advanced_material_color"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS =
            "control_center_top_buttons_advanced_material_background_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY =
            "control_center_top_buttons_advanced_material_opacity"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS =
            "control_center_top_buttons_advanced_material_blur_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT =
            "control_center_top_buttons_advanced_material_highlight"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR =
            "control_center_top_buttons_soft_glass_color"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS =
            "control_center_top_buttons_soft_glass_background_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY =
            "control_center_top_buttons_soft_glass_opacity"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS =
            "control_center_top_buttons_soft_glass_backdrop_blur_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS =
            "control_center_top_buttons_soft_glass_blur_radius"
        private const val KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE =
            "control_center_top_buttons_soft_glass_luminance"
        private const val CONTROL_CENTER_PLUS_TAG = "hyperchanger.control_center.plus"
        private const val CONTROL_CENTER_POWER_TAG = "hyperchanger.control_center.power"
        private const val CONTROL_CENTER_BUTTON_ACTION_DEBOUNCE_MS = 400L
        private const val CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MIN = 0.5f
        private const val CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MAX = 2f
        private const val CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_DEFAULT_DP = 22f
        private const val CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_MAX_DP = 22f
        private const val CONTROL_CENTER_TOUCH_CONTROLLER_CLASS =
            "miui.systemui.controlcenter.panel.main.MainPanelTouchController"
        private const val CONTROL_CENTER_HEADER_CONTROLLER_CLASS =
            "miui.systemui.controlcenter.panel.main.header.MainPanelHeaderController"
        private const val NOTIFICATION_BACKGROUND_VIEW_CLASS =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
        private const val NOTIFICATION_ROW_GLASS_EFFECT_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassEffect"
        private const val MEDIA_NOTIFICATION_GLASS_EFFECT_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassEffect"
        private val FOCUS_NOTIFICATION_EFFECT_CLASSES = listOf(
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationNormalEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationNormalCustomBgEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurOnKeyguardEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassCustomBgEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardLightWallPaperEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassFullAodEffect",
        )
        private val MEDIA_NOTIFICATION_EFFECT_CLASSES = listOf(
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewNormalEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewBlurEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewBlurOnKeyguardEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassOnKeyguardEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassOnKeyguardLightWallPaperEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassFullAodEffect",
        )
        private const val MEDIA_PANEL_CLASS =
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerPanel"
        private const val AOSP_VOLUME_DIALOG_CLASS =
            "com.android.systemui.volume.dialog.VolumeDialog"
        private const val VOLUME_PANEL_CONTROLLER_CLASS =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val VOLUME_PANEL_VIEW_CLASS =
            "com.android.systemui.miui.volume.VolumePanelView"
        private const val VOLUME_COLUMN_CLASS =
            "com.android.systemui.miui.volume.VolumeColumn"
        private const val VOLUME_ROUND_RECT_CLASS =
            "com.android.systemui.miui.volume.RoundRectFrameLayout"
        private val VOLUME_PANEL_REFRESH_METHODS = setOf(
            "initPanelView",
            "showVolumePanelH",
            "showH",
            "updateColumnH",
            "updateVolumeColumnH",
            "updateTempColumnH",
            "updateExpandedH",
        )
        private val VOLUME_PANEL_LAYOUT_METHODS = setOf("initPanelView", "showVolumePanelH", "showH", "updateExpandedH")
        private const val SLIDER_VIEW_HOLDER_CLASS =
            "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder"
        private const val BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS =
            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"
        private const val SECONDARY_PANEL_CONTROLLER_BASE_CLASS = "miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase"
        private const val SECONDARY_VOLUME_PANEL_CLASS =
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelController"
        private const val QS_ITEM_VIEW_HOLDER_CLASS =
            "miui.systemui.controlcenter.panel.main.qs.QSItemViewHolder"
        private const val MI_BACKGROUND_STYLE_CLASS = "miui.systemui.util.MiBackgroundStyle"
        private const val MI_BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat"
        private const val MIUI_BLUR_UTILS_CLASS = "miuix.core.util.MiuiBlurUtils"
        private const val MIUI_MATERIAL_UTILS_CLASS =
            "com.miui.systemui.controlcenter.utils.MiuiMaterialUtils"
        private const val MIUI_THEME_UTILS_CLASS = "miui.systemui.util.ThemeUtils"
        private const val MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS =
            "miui.systemui.controlcenter.windowview.MiuiDefaultThemeControllerImpl"
        private const val CLOCK_UTILITY_CLASS = "com.miui.clock.allInOne.AllInOneUtil"
        private const val CLOCK_UTILITY_METHOD = "applyOtaClockParams"
        private const val THEME_MANAGER_TEMPLATE_API_CLASS =
            "com.miui.keyguard.editor.data.template.TemplateApiImpl"
        private const val THEME_MANAGER_TEMPLATE_API_METHOD = "exv8"
        private const val CLOCK_BEAN_CLASS = "com.miui.clock.module.ClockBean"
        private const val CLOCK_BEAN_IS_COLON_SHOW_METHOD = "isColonShow"
        private const val CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS =
            "com.miui.clock.utils.avoid.ClockDepthAvoidRuleUtils"
        private const val ALL_IN_ONE_UTIL_CLASS = "com.miui.clock.allInOne.AllInOneUtil"
        private const val ALL_IN_ONE_TEMPLATE_VIEW_CLASS =
            "com.miui.keyguard.editor.edit.allinone.AllInOneTemplateView"
        private const val BIG_CLOCK_WIDTH_ENVELOPE_MULTIPLIER = 4
        private val bigClockWidthCalculationDepth = ThreadLocal.withInitial { 0 }
        private const val CLOCK_EFFECT_OVERLAY = 2
        private const val CLOCK_EFFECT_GLASS = 5
        private const val DYNAMIC_ISLAND_BACKGROUND_CLASS = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"
        private const val DYNAMIC_ISLAND_BASE_CONTENT_CLASS =
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
        private const val PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS =
            "miui.systemui.notification.NotificationSettingsManager"
        private const val SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS =
            "com.miui.systemui.notification.NotificationSettingsManager"
        private const val NOTIFICATION_PROVIDER_PUBLIC_CLASS =
            "com.android.systemui.statusbar.notification.NotificationProviderPublic"
        private const val FOCUS_NOTIFICATION_UTILS_CLASS =
            "miui.systemui.notification.focus.FocusNotifUtils"
        private const val FOCUS_NOTIFICATION_CONTROLLER_CLASS =
            "miui.systemui.notification.focus.FocusNotificationController"
        private const val DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS =
            "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"
        private const val ISLAND_STATE_CALLBACK_CONTROLLER_CLASS =
            "miui.systemui.dynamicisland.event.IslandStateCallbackController"
        private const val DYNAMIC_ISLAND_SOURCE_PACKAGE_KEY = "miui.source.pkg"
        private val SLIDER_PART_IDS = setOf("progress_bg")
        private val SHADE_BACKGROUND_IDS = setOf(
            "shade_background",
            "notification_panel_background",
            "control_center_background",
        )
        private const val DEVICE_CENTER_ENTRY =
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryFrameLayout"
        private const val DEFAULT_CORNER_RADIUS = 24f
        private const val MIN_GLASS_PARAMS_SIZE = 36
        private const val GLASS_TINT_RED_INDEX = 11
        private const val GLASS_TINT_GREEN_INDEX = 12
        private const val GLASS_TINT_BLUE_INDEX = 13
        private const val GLASS_ALPHA_INDEX = 14
        private const val MAX_GLASS_BLUR_RADIUS = 500
        private const val DEFAULT_SHORTCUT_GLASS_RADIUS = 48f
        private const val MIN_SHORTCUT_GLASS_RADIUS = 10f
        private const val MAX_SHORTCUT_GLASS_RADIUS = 60f
        private const val SHORTCUT_GLASS_MATERIAL_TYPE = 1
        private const val SHORTCUT_GLASS_BLUR_MODE = 1
        private const val SHORTCUT_GLASS_BLEND_MODE = 101
        private const val SHORTCUT_PURE_COLOR = 0x73FFFFFF
        private const val MINI_PLAYER_PURE_COLOR = 0x73000000
        private const val SHORTCUT_ICON_LIGHT_COLOR = Color.WHITE
        private const val SHORTCUT_ICON_DARK_COLOR = Color.BLACK
        private const val DEFAULT_ADVANCED_MATERIAL_COLOR = 0xFFFFFFFF.toInt()
        private const val DEFAULT_SOFT_GLASS_COLOR = 0xFFFFFFFF.toInt()
        private const val MAX_SHORTCUT_OPACITY = 100
        private const val MAX_SHORTCUT_BACKDROP_BLUR_RADIUS = 120
        private const val MAX_SHORTCUT_GLASS_BLUR_RADIUS = 100
        private const val MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS = 500
        private const val MAX_SHORTCUT_GLASS_LUMINANCE = 0.4f
        private const val DEFAULT_ADVANCED_MATERIAL_OPACITY = 14
        private const val DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS = 80
        private const val DEFAULT_SOFT_GLASS_OPACITY = 10
        private const val DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS = 80
        private const val DEFAULT_SOFT_GLASS_BLUR_RADIUS = 36
        private const val DEFAULT_SOFT_GLASS_LUMINANCE = 0.14f
        private const val GLASS_LUMINANCE_AMOUNT_INDEX = 4
        private const val SHORTCUT_BACKGROUND_NONE = 0
        private const val SHORTCUT_BACKGROUND_PURE_COLOR = 1
        private const val SHORTCUT_BACKGROUND_ADVANCED_MATERIAL = 2
        private const val SHORTCUT_BACKGROUND_SOFT_GLASS = 3
        private const val SHORTCUT_ICON_COLOR_AUTO = 0
        private const val SHORTCUT_ICON_COLOR_LIGHT = 1
        private const val SHORTCUT_ICON_COLOR_DARK = 2
        private const val SHORTCUT_GLASS_TAG = "hyperchanger.lockscreen.shortcut.glass"
        private const val LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS = 250L
        private val LOCKSCREEN_SHORTCUT_CONTAINER_IDS = setOf(
            "shortcut_view_left_layout",
            "shortcut_view_right_layout",
        )
        private val SHORTCUT_GLASS_PARAMETERS = floatArrayOf(
            0.67f, 0.16f, 0.09f, 0f, 0.24f, 1.4f, -0.02f, 0.3f, 0.6f, 1f,
            0.03f, 1f, 1f, 1f, 0.1f, 0.2f, 0.3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
            1.2f, 1f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4f, 2f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
        private val SHORTCUT_BLOOM_STROKE_PARAMETERS = floatArrayOf(
            3f, 180f, 1f, 1f, 1f, 0.05f, 8f, 0.5f, 0.5f, -0.5f, 1f,
            1f, 1f, 0.6f, 0.5f, 0.95f, -0.5f, 1f, 1f, 1f, 0.35f,
        )

        private const val KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT =
            "remove_focus_and_island_whitelist_limit"
        private const val KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR = "hide_notification_mini_window_bar"
        private const val KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED = "expanded_island_background_enabled"
        private const val KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY = "expanded_island_background_opacity"
        private const val KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON = "hide_system_media_source_icon"
        private const val KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON = "hide_media_island_source_icon"
        private const val KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET = "system_media_info_vertical_offset"
        private const val KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET = "media_island_info_vertical_offset"
        private const val KEY_MEDIA_TITLE_ARTIST_SPACING = "media_title_artist_spacing"
        private const val KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET = "media_cover_corner_radius_offset"
        private const val KEY_NOTIFICATION_CORNER_RADIUS_OFFSET = "notification_corner_radius_offset"
        private const val KEY_NOTIFICATION_CONTEXT_UNIFIED = "notification_context_unified"
        private const val KEY_UNIFY_NOTIFICATION_MATERIAL = "unify_notification_material"
        private const val KEY_NOTIFICATION_ELEMENTS_MATERIAL = "shade_notification_elements_material_v2"
        private const val KEY_CONTROL_CENTER_ELEMENTS_MATERIAL = "shade_control_center_elements_material_v2"
        private const val KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL = "shade_notification_center_background_material_v2"
        private const val KEY_CONTROL_CENTER_BACKGROUND_MATERIAL = "shade_control_center_background_material_v2"
        private const val KEY_NOTIFICATION_TYPE_UNIFIED = "notification_type_unified"
        private const val NORMAL_NOTIFICATION_GLASS_PARAMS_ARRAY = "notification_glass_params_normal"
        private const val KEY_NOTIFICATION_CENTER_BACKGROUND = "notification_center_background_glass"
        private const val KEY_CONTROL_CENTER_BACKGROUND = "control_center_background_glass"
        private const val KEY_NOTIFICATION_CENTER_NORMAL = "notification_center_normal_glass"
        private const val KEY_LOCKSCREEN_NORMAL = "lockscreen_normal_glass"
        private const val KEY_NOTIFICATION_CENTER_MEDIA = "notification_center_media_glass"
        private const val KEY_LOCKSCREEN_MEDIA = "lockscreen_media_glass"
        private const val KEY_NOTIFICATION_CENTER_FOCUS = "notification_center_focus_glass"
        private const val KEY_LOCKSCREEN_FOCUS = "lockscreen_focus_glass"
        private const val KEY_CONTROL_CENTER_BUTTON = "control_center_button_glass"
        private const val KEY_CONTROL_CENTER_SLIDER = "control_center_slider_glass"
        private const val KEY_CLOCK_ENABLED = "clock_enabled"
        private const val KEY_CLOCK_SIZE = "clock_size"
        private const val KEY_PADDING_END_ENABLED = "padding_end_enabled"
        private const val KEY_PADDING_END = "padding_end"
        private const val KEY_PADDING_END_LEGACY_ABSOLUTE = "padding_end_legacy_absolute"
        private const val KEY_PADDING_START_ENABLED = "padding_start_enabled"
        private const val KEY_PADDING_START = "padding_start"
        private const val KEY_HEIGHT_ENABLED = "height_enabled"
        private const val KEY_STATUS_BAR_HEIGHT = "status_bar_height"
        private const val KEY_PADDING_TOP_ENABLED = "padding_top_enabled"
        private const val KEY_PADDING_TOP = "padding_top"
        private const val KEY_PADDING_TOP_LEGACY_ABSOLUTE = "padding_top_legacy_absolute"
        private const val KEY_TOP_BUTTONS_RADIUS_ENABLED = "top_buttons_radius_enabled"
        private const val KEY_TOP_BUTTONS_RADIUS = "top_buttons_radius"
        private const val KEY_MEDIA_CARD_RADIUS_ENABLED = "media_card_radius_enabled"
        private const val KEY_MEDIA_CARD_RADIUS = "media_card_radius"
        private const val KEY_SLIDER_RADIUS_ENABLED = "slider_radius_enabled"
        private const val KEY_SLIDER_RADIUS = "slider_radius"
        private const val KEY_CONTROL_BOTTOM_BUTTONS_RADIUS_ENABLED = "control_bottom_buttons_radius_enabled"
        private const val KEY_CONTROL_BOTTOM_BUTTONS_RADIUS = "control_bottom_buttons_radius"
        private const val KEY_VOLUME_PANEL_BLUR_RADIUS = "volume_panel_blur_radius"
        private const val KEY_VOLUME_PANEL_GLASS_STRENGTH = "volume_panel_glass_strength"
        private const val KEY_VOLUME_PANEL_BACKGROUND_OPACITY = "volume_panel_background_opacity"
        private val VOLUME_PANEL_MATERIAL_DIMENSION_NAMES = setOf(
            "o3_miui_cc_volume_radius",
            "o3_miui_volume_bg_radius",
            "o3_miui_volume_radius",
            "o3_miui_tiny_volume_radius",
            "miui_volume_bg_radius",
            "miui_volume_bg_radius_expanded",
            "miui_volume_blur_bg_radius",
            "volume_dialog_background_corner_radius",
            "volume_dialog_background_square_corner_radius",
            "volume_dialog_background_blur_radius",
            "volume_dialog_background_surface_blur_radius",
        )
        private const val KEY_VOLUME_PANEL_MATERIAL_ENABLED = "volume_panel_material_enabled"
        private const val KEY_DEVICE_CENTER_RADIUS_ENABLED = "device_center_radius_enabled"
        private const val KEY_DEVICE_CENTER_RADIUS = "device_center_radius"
        private const val KEY_REMOVE_DEPTH_IMAGE_LIMIT = "remove_depth_image_limit"
        private const val KEY_NOTIFICATION_FOD_MODE = "notification_fod_mode"
        private const val KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED =
            "notification_fod_position_limit_removed"
        private const val KEY_FINGERPRINT_HIDE_MODE = "fingerprint_hide_mode"
        private const val KEY_NOTIFICATIONS_IGNORE_FOD = "notifications_ignore_fod"
        private const val KEY_LOCKSCREEN_WHITE_BAR_ENABLED = "lockscreen_white_bar_enabled"
        private const val STATUS_BAR_STATE_KEYGUARD = 1
        private const val KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED = "lockscreen_shortcut_glass_enabled"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_ENABLED = "lockscreen_mini_player_enabled"
        private const val KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED = "lockscreen_music_lockscreen_enabled"
        private const val KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED = "lockscreen_music_lyrics_enabled"
        private const val KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED =
            "lockscreen_music_lyrics_hdr_enabled"
        private const val KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON =
            "lockscreen_music_lyrics_keep_screen_on"
        private const val HYPER_MUSIC_COVER_ACTION =
            "btm.m.os4.systemuihook.hypermusiccover.PROBE"
        private const val HYPER_MUSIC_COVER_CARD_RESET_DELAY_MS = 720L
        private const val MUSIC_CLOCK_SCALE = 0.62f
        /** Presentation to restore when the lyric overlay is closed from its media-card button. */
        private var lyricReturnPresentation = LockscreenMediaPresentation.SYSTEM_MEDIA
        private var syncedHyperMusicLyricsEnabled: Boolean? = null
        private var syncedHyperMusicLyricsHdrEnabled: Boolean? = null
        private var syncedHyperMusicLyricsKeepScreenOn: Boolean? = null
        private val systemUiTranslationCache = mutableMapOf<String, Map<String, String>>()
        private const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED =
            "lockscreen_mini_player_lyrics_enabled"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION =
            "lockscreen_mini_player_hide_media_notification"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE =
            "lockscreen_mini_player_media_notification_mode"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE =
            "lockscreen_mini_player_background_mode"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_WIDTH = "lockscreen_mini_player_width"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT = "lockscreen_mini_player_height"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS =
            "lockscreen_mini_player_artwork_corner_radius"
        private const val KEY_MINI_PLAYER_PURE_COLOR = "mini_player_pure_color"
        private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR = "mini_player_advanced_material_color"
        private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY =
            "mini_player_advanced_material_opacity"
        private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS =
            "mini_player_advanced_material_blur_radius"
        private const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT =
            "mini_player_advanced_material_highlight"
        private const val KEY_MINI_PLAYER_SOFT_GLASS_COLOR = "mini_player_soft_glass_color"
        private const val KEY_MINI_PLAYER_SOFT_GLASS_OPACITY = "mini_player_soft_glass_opacity"
        private const val KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS =
            "mini_player_soft_glass_backdrop_blur_radius"
        private const val KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS = "mini_player_soft_glass_blur_radius"
        private const val KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE = "mini_player_soft_glass_luminance"
        private const val KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME =
            "keep_soft_glass_after_global_theme"
        private const val KEY_REMOVE_CLOCK_MATERIAL_LIMIT = "remove_clock_material_limit"
        private const val KEY_HIDE_STATUS_BAR_WIFI_STANDARD = "hide_status_bar_wifi_standard"
        private const val KEY_HIDE_STATUS_BAR_CLOCK_TEXT = "hide_status_bar_clock_text"
        private const val KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY = "hide_status_bar_network_activity"
        private const val KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON = "hide_control_center_edit_button"
        private const val KEY_MOBILE_NETWORK_TYPE_MODE = "mobile_network_type_mode"
        private const val NAVIGATION_BAR_VIEW_CLASS =
            "com.android.systemui.navigationbar.views.NavigationBarView"
        private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE = "lockscreen_shortcut_background_mode"
        private const val KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS = "lockscreen_shortcut_glass_radius"
        private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED =
            "lockscreen_shortcut_background_radius_enabled"
        private const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS =
            "lockscreen_shortcut_background_radius"
        private const val KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED = "lockscreen_shortcut_spacing_enabled"
        private const val KEY_LOCKSCREEN_SHORTCUT_SPACING = "lockscreen_shortcut_spacing"
        private const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED = "lockscreen_shortcut_icon_size_enabled"
        private const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE = "lockscreen_shortcut_icon_size"
        private const val KEY_SHORTCUT_ICON_COLOR_MODE = "shortcut_icon_color_mode"
        private const val KEY_SHORTCUT_PURE_COLOR = "shortcut_pure_color"
        private const val KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR = "shortcut_advanced_material_color"
        private const val KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY = "shortcut_advanced_material_opacity"
        private const val KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS = "shortcut_advanced_material_blur_radius"
        private const val KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT = "shortcut_advanced_material_highlight"
        private const val KEY_SHORTCUT_SOFT_GLASS_COLOR = "shortcut_soft_glass_color"
        private const val KEY_SHORTCUT_SOFT_GLASS_OPACITY = "shortcut_soft_glass_opacity"
        private const val KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS = "shortcut_soft_glass_backdrop_blur_radius"
        private const val KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS = "shortcut_soft_glass_blur_radius"
        private const val KEY_SHORTCUT_SOFT_GLASS_LUMINANCE = "shortcut_soft_glass_luminance"
        private var resourceHooksInstalled = false
        private var cornerHooksInstalled = false
        private var volumePanelHooksInstalled = false
        private var volumeNativeParameterHooksInstalled = false
        private var aospVolumePanelHooksInstalled = false
        private var depthEffectHookInstalled = false
        private var lockscreenNotificationHookInstalled = false
        private var hyperMusicCoverGestureBridgeInstalled = false
        private var hyperMusicCoverPreferenceChangeListener:
            SharedPreferences.OnSharedPreferenceChangeListener? = null
        @Volatile private var systemUiApplicationContext: Context? = null
        @Volatile private var activeLockscreenClockContainer: WeakReference<View>? = null
        private var lockscreenClockDateFollowHookInstalled = false
        private var systemUiNativeClockScalerHookInstalled = false
        private var systemUiLockscreenClockColonHookInstalled = false
        private var systemUiLockscreenClockWidthHookInstalled = false
        private var systemUiLockscreenClockHorizontalHookInstalled = false
        private val clockHorizontalRectOffsets = WeakHashMap<View, Pair<Rect, Int>>()
        private val clockHorizontalLiveOffsets = WeakHashMap<View, Float>()
        private val clockHorizontalEditorShifts = WeakHashMap<View, Int>()
        private val clockHorizontalEditorClockShifts = WeakHashMap<View, Int>()
        private val clockHorizontalDrags = WeakHashMap<View, ClockHorizontalDrag>()
        @Volatile private var activeLockscreenClockEditor: WeakReference<View>? = null
        private var lockscreenClockCombinedTouchHookInstalled = false
        private var lockscreenCarrierHideHookInstalled = false
        private var fingerprintIconHookInstalled = false
        private var systemUiDepthHookInstalled = false
        private var lockscreenWhiteBarHookInstalled = false
        private var globalGestureHandleHookInstalled = false
        private var lockscreenShortcutGlassHookInstalled = false
        private var lockscreenWidgetSceneVisibilityHookInstalled = false
        private var lockscreenMusicLockscreenHookInstalled = false
        private var lockscreenMediaNotificationHookInstalled = false
        private var lockscreenPinCircleBackgroundHookInstalled = false
        private var shadeMaterialHooksInstalled = false
        private var softGlassThemeSystemUiHookInstalled = false
        private var systemUiClockMaterialLimitHookInstalled = false
        private var aodClockMaterialLimitHookInstalled = false
        private var themeManagerClockMaterialLimitHookInstalled = false
        private var aodLockscreenClockColonHookInstalled = false
        private var aodLockscreenClockWidthHookInstalled = false
        private var aodLockscreenClockHorizontalHookInstalled = false
        private var aodLockscreenTemplateLimitHookInstalled = false
        private var statusBarVisibilityHookInstalled = false
        private var softGlassThemePluginHookInstalled = false
        private var softGlassThemePluginFallbackHookInstalled = false
        private var dynamicPluginThemeHookInstalled = false
        private var softGlassThemePluginMaterialHooksInstalled = false
        @Volatile private var themeOverrideReady = false
        private var themeActivationScheduled = false
        private var dynamicIslandHooksInstalled = false
        private var mediaSourceIconHooksInstalled = false
        private var dynamicIslandClassDiscoveryInstalled = false
        private var focusIslandWhitelistSystemUiHooksInstalled = false
        private var focusIslandWhitelistPluginHooksInstalled = false
        private val focusIslandWhitelistPluginInstalling = ThreadLocal.withInitial<Boolean> { false }
        @Volatile private var lockscreenMediaKeyguardShowing = false
        @Volatile private var lockscreenLyricDrawableLoadFailureLogged = false
        private val lockscreenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenHiddenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenMediaRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenMediaHeaders = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
        )
        private val lockscreenLyricButtons = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<ImageButton, Boolean>()),
        )
        /** Action4 -> owning header. The parent hierarchy is unavailable during first-card bind. */
        private val lockscreenLyricButtonHeaders = Collections.synchronizedMap(
            WeakHashMap<ImageButton, View>(),
        )
        /** MiuiMediaHeaderView -> its owning controller; both are replaced on a keyguard rebuild. */
        private val lockscreenMediaControllers = Collections.synchronizedMap(
            WeakHashMap<View, Any>(),
        )
        /** Presentation state for the shared SystemUI media header. Values survive repeated
         * vendor layout callbacks so those callbacks cannot restart a transition every frame. */
        private val lockscreenMediaTransitionTargets = Collections.synchronizedMap(
            WeakHashMap<View, Boolean>(),
        )
        private val lockscreenMediaTransitionGenerations = Collections.synchronizedMap(
            WeakHashMap<View, Int>(),
        )
        private val lockscreenMediaTransitionBaseTranslationY = Collections.synchronizedMap(
            WeakHashMap<View, Float>(),
        )
        private val lockscreenMediaTextBaseTranslations = Collections.synchronizedMap(
            WeakHashMap<TextView, Float>(),
        )
        private val lockscreenMediaTransitionsRunning = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
        )
        private const val LOCKSCREEN_MEDIA_HIDE_DURATION_MS = 300L
        private const val LOCKSCREEN_MEDIA_SHOW_DURATION_MS = 420L
        private const val LOCKSCREEN_MEDIA_TRANSITION_TRAVEL_DP = 14f
        private const val LOCKSCREEN_MEDIA_TRANSITION_START_SCALE = 0.94f
        private const val LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA = 0.01f
        private const val LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_ALPHA = 0.99f
        private const val LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_SCALE = 0.99f
        private val lockscreenMiniPlayerControllers = Collections.synchronizedMap(
            WeakHashMap<View, LockscreenMiniPlayerController>(),
        )
        private val lockscreenWidgetControllers = Collections.synchronizedMap(
            WeakHashMap<View, LockscreenWidgetController>(),
        )
        private val lockscreenDateAppliedOffsets = Collections.synchronizedMap(
            WeakHashMap<View, Float>(),
        )
        private val lockscreenDateNativeOffsets = Collections.synchronizedMap(
            WeakHashMap<View, Float>(),
        )
        private val lockscreenDateFollowGenerations = Collections.synchronizedMap(
            WeakHashMap<View, Int>(),
        )
        private const val LOCKSCREEN_DATE_FOLLOW_DURATION_MS = 900L
        private const val LOCKSCREEN_DATE_TO_GLYPH_GAP_DP = 12f
        private val lockscreenMusicLockscreenControllers = Collections.synchronizedMap(
            WeakHashMap<View, LockscreenMusicLockscreenController>(),
        )
        private val fodEnrollmentFlowOverrides = WeakHashMap<Any, Any>()
        private val mediaMaterialApplying = ThreadLocal<Boolean>()
        private val normalNotificationGlassParamsCache =
            WeakHashMap<Resources, MutableMap<String, FloatArray>>()
        private val controlCenterMaterialHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val focusMaterialEnforcementHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val controlCenterRootDispatchHookedClasses =
            Collections.synchronizedSet(mutableSetOf<String>())
        private val globalActionsHookedClasses =
            Collections.synchronizedSet(mutableSetOf<String>())
        private val expandedIslandMaterialSettings =
            Collections.synchronizedMap(WeakHashMap<View, Int>())
        // ClassLoader discovery callbacks can arrive concurrently while SystemUI plugins are
        // being torn down. WeakHashMap-backed sets are not safe for that path and can corrupt
        // their table, leaving the main thread stuck in WeakHashMap.put during an ANR.
        private val cornerTargetClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
        private val volumePanelHookedClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
        private val volumePanelNativeApiLogged = Collections.synchronizedSet(mutableSetOf<String>())
        private val volumeNativeParameterHookHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val volumePanelSurfaceRoots = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
        )
        private val volumePanelAppliedTuning = Collections.synchronizedMap(WeakHashMap<View, VolumeTuningSnapshot>())
        private var controlCenterEditButtonHookInstalled = false
        private var controlCenterContentDistributorHookInstalled = false
        private var controlCenterTopButtonsHookInstalled = false
        private var controlCenterExpandLifecycleHookInstalled = false
        private var globalActionsHookInstalled = false
        private var controlCenterTouchHookInstalled = false
        private var controlCenterTouchDispatchHookInstalled = false
        private var controlCenterEventHandlerHookInstalled = false
        private var controlCenterRootDispatchHookInstalled = false
        private var controlCenterRootClickHookInstalled = false
        private var controlCenterHeaderLifecycleHookInstalled = false
        private var controlCenterMainPanelHookInstalled = false
        private val controlCenterClassDiscoveryInProgress = ThreadLocal.withInitial<Boolean> { false }
        private var controlCenterPreferenceListenerInstalled = false
        private var controlCenterHookRetryScheduled = false
        private val globalActionsLock = Any()
        private var globalActionsPlugin: Any? = null
        private var globalActionsManager: Any? = null
        private var globalActionsComponent: Any? = null
        private var globalActionsImpl: Any? = null
        private var commandQueue: Any? = null
        private val controlCenterButtonsLock = Any()
        private var controlCenterRoot: ViewGroup? = null
        private var controlCenterEditTarget: View? = null
        private var systemUiClassLoader: ClassLoader? = null
        private var controlCenterControllerClassLoader: ClassLoader? = null
        private var controlCenterPlusButton: View? = null
        private var controlCenterPowerButton: View? = null
        private var controlCenterActiveTouchButton: View? = null
        private var controlCenterLastActionButton: View? = null
        private var controlCenterLastActionUptime = 0L
        private var controlCenterExpandController: Any? = null
        private var controlCenterEditController: Any? = null
        private var controlCenterButtonsExpansionProgress = 0f
        private var controlCenterButtonsHiddenByCollapse = true
        private var controlCenterButtonsCollapsing = false
        private val stackedMobileDualContainerId = View.generateViewId()
    }
}
