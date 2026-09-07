// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import btm.m.xiaoaihook.SuperXiaoAiInputHook
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

private enum class NotificationMaterialType { NORMAL, MEDIA, FOCUS }

private data class VolumeTuningSnapshot(
    val blurRadius: Int,
    val glassStrength: Int,
    val backgroundOpacity: Int,
    val cornerRadius: Float,
    val viewCount: Int,
)

private data class StackedMobileSubscription(
    val slot: Int,
    val dataSim: Boolean,
    val signalLevel: Int,
)

private class StackedMobilePresentation(
    val root: ViewGroup,
    var signal: ImageView,
    var subscriptionId: Int,
) {
    var attachListenerInstalled = false
    var refreshPending = false
    var independentType: TextView? = null
    var mobileSignalContainer: ViewGroup? = null
    var mobileGroup: ViewGroup? = null
    var networkTypeView: TextView? = null
    var networkTypeSource: Any? = null
    var dualContainer: FrameLayout? = null
    var dualSignal: ImageView? = null
    var savedDualTranslationY: Float? = null
    var savedDualMargins: IntArray? = null
    var savedRootVisibility: Int? = null
    var rootHiddenByStacked = false
    var savedIndependentView: TextView? = null
    var savedIndependentTranslationY: Float? = null
    var savedIndependentMargins: IntArray? = null
    var savedIndependentParent: ViewGroup? = null
    var savedIndependentIndex: Int = -1
    var savedIndependentLayoutParams: ViewGroup.LayoutParams? = null
}

/**
 * The Hyper Helper default stacked icon: a full four-column signal on top and
 * a four-dot signal below.  It deliberately has its own geometry instead of
 * squeezing two unrelated SystemUI drawables into one ImageView.
 */
private class StackedMobileDrawable(
    private val upperLevel: Int,
    private val lowerLevel: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 0xFF
    private var tint: ColorStateList? = null
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        // Coordinates match XiaomiHelper's Signal-HyperOS3-Stacked.svg viewBox.
        val columnLeft = 0.108f
        val columnWidth = 0.124f
        val columnGap = 0.0886f
        val topRowBottom = 0.5985f
        val topRowTops = floatArrayOf(0.4635f, 0.3907f, 0.288f, 0.1987f)
        val lowerRowTop = 0.6661f
        val lowerRowBottom = 0.8013f
        val corner = minOf(width * 0.027f, height * 0.027f)
        paint.color = tint?.getColorForState(state, Color.WHITE) ?: Color.WHITE
        paint.colorFilter = drawableColorFilter

        drawSignalRow(canvas, upperLevel, columnLeft, columnWidth, columnGap, topRowTops, topRowBottom, corner)
        drawSignalRow(
            canvas,
            lowerLevel,
            columnLeft,
            columnWidth,
            columnGap,
            floatArrayOf(lowerRowTop, lowerRowTop, lowerRowTop, lowerRowTop),
            lowerRowBottom,
            corner,
        )
    }

    private fun drawSignalRow(
        canvas: Canvas,
        level: Int,
        columnLeft: Float,
        columnWidth: Float,
        columnGap: Float,
        tops: FloatArray,
        bottom: Float,
        corner: Float,
    ) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        for (column in 0 until 4) {
            val left = bounds.left + (columnLeft + column * (columnWidth + columnGap)) * width
            val top = bounds.top + tops[column] * height
            val right = left + columnWidth * width
            val rowBottom = bounds.top + bottom * height
            paint.alpha = if (column < level.coerceIn(0, 4)) drawableAlpha else (drawableAlpha * 0.35f).toInt()
            canvas.drawRoundRect(left, top, right, rowBottom, corner, corner, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun onStateChange(state: IntArray): Boolean {
        if (tint?.isStateful == true) invalidateSelf()
        return tint?.isStateful == true
    }

    override fun isStateful(): Boolean = tint?.isStateful == true
}

class HyperSystemUiModule : XposedModule() {
    internal fun installHook(member: java.lang.reflect.Executable) = hook(member)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!OsCompatibility.areHooksAllowed()) return
        if (param.packageName !in SYSTEM_UI_TARGETS) return
        if (param.packageName == SUPER_XIAOAI_IME || param.packageName == SUPER_XIAOAI_PHRASE) {
            installSuperXiaoAiHooks(param.packageName, param.defaultClassLoader)
            return
        }
        runCatching {
            val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            when (param.packageName) {
                SYSTEM_UI, SYSTEM_UI_PLUGIN -> {
                    if (param.packageName == SYSTEM_UI) {
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
                    if (param.packageName == SYSTEM_UI && !dynamicIslandClassDiscoveryInstalled) {
                        installDynamicIslandClassDiscovery(preferences)
                        dynamicIslandClassDiscoveryInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI_PLUGIN && !dynamicIslandHooksInstalled) {
                        installDynamicIslandHooks(param.defaultClassLoader, preferences)
                        dynamicIslandHooksInstalled = true
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
                    if (!focusIslandWhitelistPluginHooksInstalled) {
                        // The plugin is commonly loaded into SystemUI's class loader and may not
                        // receive a separate package callback.  Try the current loader first;
                        // class-load discovery below will retry when the plugin appears later.
                        focusIslandWhitelistPluginHooksInstalled =
                            installFocusIslandWhitelistPluginHooks(param.defaultClassLoader, preferences)
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenNotificationHookInstalled) {
                        installLockscreenNotificationHook(param.defaultClassLoader, preferences)
                        installLockscreenMediaNotificationHook(param.defaultClassLoader, preferences)
                        lockscreenNotificationHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenClockDateFollowHookInstalled) {
                        installLockscreenClockDateFollowHook(param.defaultClassLoader)
                        lockscreenClockDateFollowHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiLockscreenClockColonHookInstalled) {
                        installLockscreenClockColonHook(param.defaultClassLoader, preferences, "systemui")
                        systemUiLockscreenClockColonHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !fingerprintIconHookInstalled) {
                        installFingerprintIconVisualHook(param.defaultClassLoader, preferences)
                        // This optional visual hook varies between HyperOS builds.  Do not
                        // let a missing vendor method abort all SystemUI hooks installed later.
                        runCatching {
                            installLockscreenFingerprintAnimationHook(param.defaultClassLoader, preferences)
                        }.onFailure { error ->
                            log(Log.WARN, TAG, "Skipped unsupported lockscreen fingerprint animation hook", error)
                        }
                        fingerprintIconHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiDepthHookInstalled) {
                        installSystemUiDepthHooks(param.defaultClassLoader, preferences)
                        systemUiDepthHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenChargingHookInstalled) {
                        installLockscreenChargingTextHook(param.defaultClassLoader, preferences)
                        installLockscreenBottomTextViewHook(param.defaultClassLoader, preferences)
                        lockscreenChargingHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenShortcutGlassHookInstalled) {
                        installLockscreenShortcutGlassHook(param.defaultClassLoader, preferences)
                        lockscreenShortcutGlassHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenWidgetSceneVisibilityHookInstalled) {
                        installLockscreenWidgetSceneVisibilityHooks(param.defaultClassLoader)
                        lockscreenWidgetSceneVisibilityHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !lockscreenPinCircleBackgroundHookInstalled) {
                        installLockscreenPinCircleBackgroundHook(param.defaultClassLoader, preferences)
                        lockscreenPinCircleBackgroundHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !shadeMaterialHooksInstalled) {
                        installShadeMaterialHooks(preferences, param.defaultClassLoader)
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
                    if (param.packageName == SYSTEM_UI && !stackedMobileSignalHookInstalled) {
                        installStackedMobileSignalHook(param.defaultClassLoader, preferences)
                        stackedMobileSignalHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI && !systemUiClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        systemUiClockMaterialLimitHookInstalled = true
                    }
                    if (param.packageName == SYSTEM_UI_PLUGIN && !softGlassThemePluginHookInstalled) {
                        installSoftGlassThemePluginHook(param.defaultClassLoader, preferences)
                        softGlassThemePluginHookInstalled = true
                    }
                }
                AOD -> {
                    if (!depthEffectHookInstalled) {
                        installDepthEffectHook(param.defaultClassLoader, preferences)
                        installAodThirdPartyWallpaperDepthHook(param.defaultClassLoader, preferences)
                        depthEffectHookInstalled = true
                    }
                    if (!aodClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(param.defaultClassLoader, preferences)
                        aodClockMaterialLimitHookInstalled = true
                    }
                    if (!aodLockscreenClockColonHookInstalled) {
                        installLockscreenClockColonHook(param.defaultClassLoader, preferences, "aod")
                        aodLockscreenClockColonHookInstalled = true
                    }
                    if (!aodLockscreenTemplateLimitHookInstalled) {
                        installAodLockscreenTemplateLimitHook(param.defaultClassLoader, preferences)
                        aodLockscreenTemplateLimitHookInstalled = true
                    }
                }
                SUBSCREEN_CENTER -> installMusicControlWhitelistHook(param.defaultClassLoader, preferences)
                else -> return
            }
            log(Log.INFO, TAG, "Installed hooks for ${param.packageName}")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }
    }

    private fun installSuperXiaoAiHooks(packageName: String, classLoader: ClassLoader) {
        SuperXiaoAiInputHook.install(
            module = this,
            classLoader = classLoader,
            phraseProcess = packageName == SUPER_XIAOAI_PHRASE,
        )
    }

    private fun installMusicControlWhitelistHook(
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
                log(Log.WARN, TAG, "Could not replace rear music configuration map", error)
            }

            fun currentWhitelist(): Set<String> = preferences
                .getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet())
                .orEmpty()

            configClass.declaredMethods.firstOrNull { method ->
                method.name == "a" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    method.returnType == String::class.java
            }?.let { method ->
                hook(method)
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
                hook(method)
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
                hook(method)
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
                hook(method)
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
            log(Log.INFO, TAG, "Installed rear music whitelist hooks (${whitelist.size} app(s), ${configClass.name})")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not update rear music control whitelist", error)
        }
    }

    /** Keep the stock bionic/soft-glass pipeline active when a global theme is applied. */
    private fun scheduleSoftGlassThemeActivation(preferences: SharedPreferences) {
        if (themeActivationScheduled) return
        themeActivationScheduled = true
        // The control-center plugin now creates its default-theme StateFlow while it is
        // loading. Delaying this flag lets a global-theme "false" be cached permanently
        // until the next configuration change, so it must be available before the plugin.
        themeOverrideReady = true
        if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false)) {
            log(Log.INFO, TAG, "Soft-glass theme override enabled after startup")
        }
    }

    private fun installSoftGlassThemeSystemUiHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
            log(Log.INFO, TAG, "Installed soft-glass global-theme hook for SystemUI")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install soft-glass global-theme hook for SystemUI", error)
        }
    }

    private fun installSoftGlassThemePluginHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
            log(Log.INFO, TAG, "Installed soft-glass global-theme hook for plugin")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install soft-glass global-theme hook for plugin", error)
        }
    }

    private fun installSoftGlassThemeClassLoadGuard(preferences: SharedPreferences) {
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
            log(Log.INFO, TAG, "Installed plugin ThemeUtils class-load guard")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install plugin ThemeUtils class-load guard", error)
        }
    }

    private fun installSoftGlassThemePluginClass(
        themeUtils: Class<*>,
        preferences: SharedPreferences,
    ) {
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
            log(Log.INFO, TAG, "Installed soft-glass global-theme hook for dynamically loaded plugin")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not hook dynamically loaded plugin ThemeUtils", error)
        }
    }

    private fun installDefaultThemeStateGuard(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
            log(Log.INFO, TAG, "Installed default-theme state guard")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install default-theme state guard", error)
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
            log(Log.INFO, TAG, "Installed soft-glass plugin material-state guards")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install soft-glass plugin material-state guards", error)
        }
    }

    private fun installStatusBarVisibilityHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            hook(View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:visibility")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val resourceName = runCatching {
                        view?.resources?.getResourceEntryName(view.id)
                    }.getOrNull()
                    val hideNetworkType = preferences.getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_TYPE, false)
                    // 0 keeps SystemUI's original presentation, 1 replaces it with the
                    // independent label, and 2 hides the network type completely.
                    val mobileNetworkTypeMode = preferences
                        .getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0)
                        .coerceIn(0, 2)
                    val hideWifiStandard = preferences.getBoolean(KEY_HIDE_STATUS_BAR_WIFI_STANDARD, false)
                    val hideClockText = preferences.getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false)
                    val hideNetworkActivity = preferences.getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY, false)
                    val isIndependentMobileType = isIndependentMobileTypeView(view)
                    val hideSecondaryMobileRoot = isStackedSecondaryMobileRoot(view)
                    val hideOriginalDualSignal = resourceName == "mobile_signal" &&
                        shouldHideSystemMobileSignal(view, preferences.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0))
                    val forcedHidden =
                        hideSecondaryMobileRoot || hideOriginalDualSignal ||
                        ((resourceName == "mobile_type" || resourceName == "mobile_type_single" ||
                            resourceName == "mobile_special_5G") &&
                            (hideNetworkType || mobileNetworkTypeMode != 0) &&
                            !(resourceName == "mobile_type_single" &&
                                isIndependentMobileType && mobileNetworkTypeMode == 1)) ||
                            (resourceName == "wifi_standard" && hideWifiStandard) ||
                            (resourceName in setOf("wifi_activity", "mobile_left_mobile_inout") && hideNetworkActivity) ||
                            (resourceName == "battery_text_digit_view" && hideClockText)
                    if (forcedHidden) {
                        chain.proceedWith(chain.thisObject, arrayOf(View.GONE))
                    } else {
                        chain.proceed()
                    }
                }

            installBatteryThemeAndTextHooks(classLoader, preferences)
            installBatteryInternalTextHooks(classLoader, preferences)
            installBatteryDrawableHistoryHook(preferences)

            log(Log.INFO, TAG, "Installed status-bar visibility hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install status-bar visibility hooks", error)
        }
    }

    private fun isBatteryPercentageView(view: TextView?, resourceName: String?): Boolean {
        return resourceName == "battery_text_digit_view" || resourceName == "battery_text_view"
    }

    private fun installBatteryThemeAndTextHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val keepTheme = { preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) }
        runCatching {
            val batteryViewClass = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)
            // Vendor builds have changed the callback signature (and sometimes moved it to
            // a nested icon class). Match by name so one missing overload cannot disable the
            // remaining status-bar guards.
            batteryViewClass.declaredMethods
                .filter { it.name == "onMiuiThemeChanged" || it.name == "updateResources" }
                .forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-theme-$index")
                        .intercept { chain ->
                            // A theme refresh rebuilds the battery drawable from the
                            // currently selected theme.  After a SystemUI restart there
                            // is no in-process drawable history to restore, so skip only
                            // this destructive refresh when the keep-theme option is on.
                            if (shouldSkipBatteryThemeRefresh(chain.thisObject, method.name, keepTheme())) {
                                return@intercept null
                            }
                            val snapshot = captureBatteryDrawables(chain.thisObject)
                            val result = chain.proceed()
                            if (keepTheme()) restoreBatteryDrawables(snapshot)
                            result
                        }
                }

            val refreshMethods = batteryViewClass.declaredMethods.filter {
                it.name == "updateChargeAndText" || it.name == "updateAll" ||
                    it.name == "updateAll\u00241" || it.name == "onBatteryStyleChanged"
            }
            refreshMethods.forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:battery-text-refresh-$index")
                    .intercept { chain ->
                        val snapshot = captureBatteryDrawables(chain.thisObject)
                        val result = chain.proceed()
                        if (keepTheme()) restoreBatteryDrawables(snapshot)
                        if (preferences.getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false)) {
                            hideBatteryText(chain.thisObject, chain.thisObject as? ViewGroup)
                        }
                        result
                    }
            }
            log(Log.INFO, TAG, "Installed battery theme/text hooks (${refreshMethods.size} refresh methods)")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install battery view theme/text hooks", error)
        }

        listOf(BATTERY_ICON_CLASS, BATTERY_INDICATOR_CLASS, BATTERY_HOLLOW_ICON_CLASS).forEach { className ->
            runCatching {
                val iconClass = classLoader.loadClass(className)
                iconClass.declaredMethods
                    .filter { it.name == "onMiuiThemeChanged" || it.name == "updateResources" }
                    .forEachIndexed { index, method ->
                        hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("status-bar:battery-icon-theme-${className.substringAfterLast('.')}-${index}")
                            .intercept { chain ->
                                if (shouldSkipBatteryThemeRefresh(chain.thisObject, method.name, keepTheme())) {
                                    return@intercept null
                                }
                                val snapshot = captureBatteryDrawables(chain.thisObject)
                                val result = chain.proceed()
                                if (keepTheme()) restoreBatteryDrawables(snapshot)
                                result
                            }
                    }
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Optional battery theme hook unavailable: $className", error)
            }
        }
        installStatusBarIconThemeGuards(classLoader, preferences, keepTheme)
    }

    private fun hideBatteryText(owner: Any?, root: ViewGroup?) {
        val views = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        listOf("mBatteryTextDigitView")
            .forEach { fieldName ->
                runCatching {
                    var type: Class<*>? = owner?.javaClass
                    while (type != null) {
                        val field = runCatching {
                            type!!.getDeclaredField(fieldName).apply { isAccessible = true }
                        }.getOrNull()
                        if (field != null) {
                            (field.get(owner) as? View)?.let { views += it }
                            break
                        }
                        type = type!!.superclass
                    }
                }
            }
        root?.findViewsByResourceNames(
            "battery_text_digit_view",
        )?.let { views.addAll(it) }
        views.forEach { view ->
            if (view is TextView) view.text = ""
            view.visibility = View.GONE
        }
    }

    private fun shouldSkipBatteryThemeRefresh(owner: Any?, methodName: String, keepTheme: Boolean): Boolean {
        if (!keepTheme) return false
        // Theme callbacks always rebuild the drawable from the default-theme resources.
        if (methodName.startsWith("onMiuiThemeChanged")) return true
        // Resource refresh is needed once during inflation.  Subsequent calls are the
        // reset path observed after a SystemUI restart, so keep the first themed drawable.
        if (owner == null) return false
        synchronized(batteryResourceRefreshSeen) {
            if (batteryResourceRefreshSeen.containsKey(owner)) return true
            batteryResourceRefreshSeen[owner] = true
        }
        return false
    }

    private fun installBatteryInternalTextHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        val hideText = { preferences.getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false) }

        // The internal percentage is a TextView on the normal battery layout.  Keep this
        // hook scoped by resource id so the separately configurable external percentage is
        // never affected.
        runCatching {
            TextView::class.java.declaredMethods
                .filter { it.name == "setText" && it.parameterTypes.isNotEmpty() }
                .forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-internal-text-$index")
                        .intercept { chain ->
                            val view = chain.thisObject as? TextView
                            val resourceName = runCatching {
                                view?.resources?.getResourceEntryName(view.id)
                            }.getOrNull()
                            val internal = resourceName == "battery_text_digit_view"
                            val result = chain.proceed()
                            if (hideText() && internal) view?.visibility = View.GONE
                            result
                        }
                }
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Could not install battery TextView guard", error)
        }

        // Hollow battery themes draw the number directly on a Canvas instead of using the
        // TextView above.  Temporarily make only their text paints transparent while the
        // widget draws, then restore the paints immediately for future theme updates.
        runCatching {
            val hollowClass = classLoader.loadClass(
                "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView",
            )
            hollowClass.declaredMethods
                .filter { it.name == "onDraw" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Canvas::class.java }
                .forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("status-bar:battery-hollow-text-$index")
                        .intercept { chain ->
                            if (!hideText()) return@intercept chain.proceed()
                            val paints = listOf("textPaint", "hollowTextPaint").mapNotNull { name ->
                                runCatching {
                                    var type: Class<*>? = chain.thisObject?.javaClass
                                    while (type != null) {
                                        val field = runCatching {
                                            type!!.getDeclaredField(name).apply { isAccessible = true }
                                        }.getOrNull()
                                        if (field != null) return@runCatching field.get(chain.thisObject) as? Paint
                                        type = type!!.superclass
                                    }
                                    null
                                }.getOrNull()
                            }.distinct()
                            val alpha = paints.map { it.alpha }
                            paints.forEach { it.alpha = 0 }
                            try {
                                chain.proceed()
                            } finally {
                                paints.forEachIndexed { paintIndex, paint -> paint.alpha = alpha[paintIndex] }
                            }
                        }
                }
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Optional hollow battery text hook unavailable", error)
        }
    }

    private fun captureBatteryDrawables(owner: Any?): List<Pair<ImageView, Drawable>> {
        val result = ArrayList<Pair<ImageView, Drawable>>()
        val fieldNames = setOf("mBatteryIconView", "mBatteryChargingView", "mBatteryChargingInView")
        var type: Class<*>? = owner?.javaClass
        while (type != null) {
            type.declaredFields.filter { it.name in fieldNames }.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val view = field.get(owner) as? ImageView
                    val drawable = view?.drawable?.constantState?.newDrawable(view.resources)
                        ?: view?.let { batteryDrawableHistory[it]?.constantState?.newDrawable(it.resources) }
                    if (view != null && drawable != null) result += view to drawable
                }
            }
            type = type.superclass
        }
        (owner as? ImageView)?.let { view ->
            (view.drawable?.constantState?.newDrawable(view.resources)
                ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources))
                ?.let { result += view to it }
        }
        return result
    }

    private fun restoreBatteryDrawables(snapshot: List<Pair<ImageView, Drawable>>) {
        snapshot.forEach { (view, drawable) ->
            runCatching {
                val restored = drawable.constantState?.newDrawable(view.resources) ?: drawable
                batteryDrawableHistory[view] = restored.constantState?.newDrawable(view.resources) ?: restored
                restoringBatteryDrawable.set(true)
                try {
                    view.setImageDrawable(restored)
                } finally {
                    restoringBatteryDrawable.remove()
                }
            }
        }
    }

    private fun installBatteryDrawableHistoryHook(preferences: SharedPreferences) {
        runCatching {
            hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:battery-drawable-history")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val old = view?.drawable
                    val restoring = restoringBatteryDrawable.get() ?: false
                    if (old != null && preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&
                        !restoring
                    ) {
                        batteryDrawableHistory[view] = old.constantState?.newDrawable(view.resources) ?: old
                    }
                    chain.proceed()
                }
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Could not install battery drawable history hook", error)
        }
    }

    private fun installStatusBarIconThemeGuards(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        keepTheme: () -> Boolean,
    ) {
        val classNames = listOf(
            MODERN_STATUS_BAR_VIEW_CLASS,
            WIFI_VIEW_BINDER_CLASS,
            MOBILE_ICON_BINDER_CLASS,
        )
        classNames.forEach { className ->
            runCatching {
                val root = classLoader.loadClass(className)
                val candidates = buildList {
                    add(root)
                    addAll(root.declaredClasses)
                }
                candidates.forEach { candidate ->
                    candidate.declaredMethods
                        .filter { it.name.startsWith("onMiuiThemeChanged") }
                        .forEach { method ->
                            hook(method)
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .setId("status-bar:icon-theme-${candidate.name}")
                                .intercept { chain ->
                                    if (!keepTheme()) return@intercept chain.proceed()
                                    // These callbacks are the point where the vendor
                                    // pipeline replaces themed signal/Wi-Fi drawables with
                                    // its default set.  Skipping the callback keeps the
                                    // drawable selected during initial inflation, including
                                    // the lockscreen status-bar instance.
                                    null
                                }
                        }
                }
                log(Log.INFO, TAG, "Installed status-bar icon theme guard: $className")
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Optional status-bar icon theme guard unavailable: $className", error)
            }
        }
    }

    private fun captureImageViewDrawables(owner: Any?): List<Pair<ImageView, Drawable>> {
        val result = ArrayList<Pair<ImageView, Drawable>>()
        var type: Class<*>? = owner?.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val view = field.get(owner) as? ImageView ?: return@runCatching
                    val drawable = view.drawable?.constantState?.newDrawable(view.resources)
                        ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources)
                    if (drawable != null) result += view to drawable
                }
            }
            type = type.superclass
        }
        (owner as? ImageView)?.let { view ->
            (view.drawable?.constantState?.newDrawable(view.resources)
                ?: batteryDrawableHistory[view]?.constantState?.newDrawable(view.resources))
                ?.let { result += view to it }
        }
        return result.distinctBy { it.first }
    }

    private fun restoreImageViewDrawables(snapshot: List<Pair<ImageView, Drawable>>) {
        snapshot.forEach { (view, drawable) ->
            runCatching {
                restoringBatteryDrawable.set(true)
                try {
                    view.setImageDrawable(drawable.constantState?.newDrawable(view.resources) ?: drawable)
                } finally {
                    restoringBatteryDrawable.remove()
                }
            }
        }
    }

    private fun ViewGroup.findViewsByResourceNames(vararg names: String): List<TextView> {
        val result = ArrayList<TextView>()
        fun visit(view: View) {
            if (view is TextView) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name in names) result += view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
        return result
    }

    /** Restore clock material values that OS4's OTA conversion rejects (notably glass). */
    private fun installClockMaterialLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
                    val bean = chain.getArg(2)
                    val originalEffect = runCatching {
                        bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int
                    }.getOrNull()
                    val result = chain.proceed()
                    if (originalEffect == CLOCK_EFFECT_GLASS || originalEffect == CLOCK_EFFECT_OVERLAY) {
                        runCatching {
                            bean?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)
                                ?.invoke(bean, originalEffect)
                        }.onFailure { error ->
                            log(Log.WARN, TAG, "Could not restore clock material effect", error)
                        }
                    }
                    result
                }
            log(Log.INFO, TAG, "Installed clock material-limit bypass")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install clock material-limit bypass", error)
        }
    }

    private fun installDynamicIslandHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        installDynamicIslandBackgroundHooks(classLoader, preferences)
        installDynamicIslandLayoutHooks(classLoader, preferences)
        installDynamicIslandSelfBlurHook(classLoader, preferences)
        if (!focusIslandWhitelistPluginHooksInstalled) {
            focusIslandWhitelistPluginHooksInstalled =
                installFocusIslandWhitelistPluginHooks(classLoader, preferences)
        }
        log(Log.INFO, TAG, "Installed dynamic-island hooks")
    }

    private fun installFocusIslandWhitelistPluginHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean {
        return runCatching {
            val settingsClass = classLoader.loadClass(PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS)
            listOf("canCustomFocus", "mediaIslandSupportMiniWindow").forEach { name ->
                hook(settingsClass.getMethod(name, String::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("focus-island-whitelist-plugin:$name")
                    .intercept { chain ->
                        if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                    if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                    if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                            if (!preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                                log(Log.INFO, TAG, "Allowed focus authorization for $packageName")
                                null
                            }
                        }
                }
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not install FocusNotificationController auth hook", error)
            }

            val coordinatorClass = classLoader.loadClass(DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS)
            hook(coordinatorClass.getMethod("mediaIslandSupportMiniWindow", String::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("focus-island-whitelist-plugin:event-coordinator")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                    if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
                        allowIslandStateCallbackPackage(chain.thisObject, stateCallbackClass, chain.getArg(1))
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "Installed focus-notification and island whitelist hooks for plugin")
            true
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install focus-notification and island whitelist plugin hooks", error)
        }.getOrDefault(false)
    }

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
            log(Log.ERROR, TAG, "Could not extend the island state-callback whitelist", error)
        }
    }

    private fun installFocusIslandWhitelistSystemUiHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ): Boolean {
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
                        if (preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false)) {
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
                        if (!preferences.getBoolean(KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT, false) ||
                            chain.getArg(0) != "canShowFocus"
                        ) {
                            result
                        } else {
                            (result ?: android.os.Bundle()).apply {
                                putBoolean("canShowFocus", true)
                            }
                        }
                    }
                log(Log.INFO, TAG, "Installed focus permission hook for NotificationProviderPublic.call")
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not install NotificationProviderPublic.call focus hook", error)
            }
            log(Log.INFO, TAG, "Installed focus-notification whitelist hooks for SystemUI")
            true
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install focus-notification whitelist SystemUI hooks", error)
        }.getOrDefault(false)
    }

    private fun installDynamicIslandClassDiscovery(preferences: SharedPreferences) {
        runCatching {
            hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-class-discovery")
                .intercept { chain ->
                    val loadedClass = chain.proceed() as? Class<*> ?: return@intercept null
                    if (loadedClass.name == DYNAMIC_ISLAND_BACKGROUND_CLASS && !dynamicIslandHooksInstalled) {
                        dynamicIslandHooksInstalled = true
                        loadedClass.classLoader?.let { pluginClassLoader ->
                            runCatching {
                                installDynamicIslandHooks(pluginClassLoader, preferences)
                            }.onFailure { error ->
                                dynamicIslandHooksInstalled = false
                                log(Log.ERROR, TAG, "Could not initialize dynamic-island hooks from plugin loader", error)
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
                    loadedClass
                }
            log(Log.INFO, TAG, "Installed dynamic-island plugin class discovery hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install dynamic-island plugin class discovery hook", error)
        }
    }

    private fun installDynamicIslandBackgroundHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val backgroundClass = classLoader.loadClass(DYNAMIC_ISLAND_BACKGROUND_CLASS)
            listOf("setDrawable", "setActualHeight", "setActualWidth").forEach { name ->
                val method = when (name) {
                    "setDrawable" -> backgroundClass.getMethod(name, Drawable::class.java)
                    else -> backgroundClass.getMethod(name, Int::class.javaPrimitiveType)
                }
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-island-background-$name")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            if (name == "setDrawable") {
                                (chain.thisObject as? View)?.let(expandedIslandMaterialSettings::remove)
                            }
                            applyExpandedIslandBackground(
                                chain.thisObject as? View,
                                preferences,
                                classLoader,
                            )
                        }
                        result
                    }
            }
            log(Log.INFO, TAG, "Installed DynamicIslandBackgroundView update hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install DynamicIslandBackgroundView update hooks", error)
        }
    }

    private fun installDynamicIslandLayoutHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        DYNAMIC_ISLAND_LAYOUT_CLASSES.forEach { className ->
            runCatching {
                val layoutClass = classLoader.loadClass(className)
                val methods = layoutClass.declaredMethods.filter {
                    it.name.startsWith("updateBigIslandLayout")
                }
                check(methods.isNotEmpty()) { "$className has no updateBigIslandLayout method" }
                methods.forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("dynamic-island-layout:${layoutClass.name}#$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val source = chain.thisObject as? View
                            applyExpandedIslandBackground(
                                source?.let(::findDynamicIslandBackground),
                                preferences,
                                classLoader,
                            )
                            result
                        }
                }
                log(Log.INFO, TAG, "Installed ${methods.size} big-island layout hooks for $className")
            }.onFailure { error ->
                log(Log.INFO, TAG, "Skipped dynamic-island layout class $className", error)
            }
        }
    }

    private fun installDynamicIslandSelfBlurHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val blurClass = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)
            hook(blurClass.getMethod("setMiSelfBlur", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-self-blur")
                .intercept { chain ->
                    val view = chain.getArg(0) as? View
                    if (view != null && isDynamicIslandView(view) &&
                        preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)
                    ) {
                        val radius = preferences.getInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, 0)
                            .coerceIn(0, 200)
                        chain.proceedWith(chain.thisObject, arrayOf(view, radius, chain.getArg(2)))
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "Installed dynamic-island self-blur hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install dynamic-island self-blur hook", error)
        }
    }

    private fun applyExpandedIslandBackground(view: View?, preferences: SharedPreferences, classLoader: ClassLoader) {
        if (view == null || !preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)) return
        if (view.javaClass.name != DYNAMIC_ISLAND_BACKGROUND_CLASS) return
        val opacity = preferences.getInt(KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY, 35).coerceIn(0, 100)
        val smallBlur = preferences.getInt(KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS, 10).coerceIn(0, 40)
        val largeBlur = preferences.getInt(KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS, 10).coerceIn(0, 40)
        val selfBlur = preferences.getInt(KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS, 0).coerceIn(0, 40)
        val highlight = preferences.getBoolean(KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT, false)
        val configuration = listOf(opacity, smallBlur, largeBlur, selfBlur, highlight).hashCode()
        if (expandedIslandMaterialSettings[view] == configuration) return
        val drawable = runCatching {
            view.javaClass.getMethod("getDrawable").invoke(view) as? Drawable
        }.getOrNull() ?: view.background
        drawable?.mutate()?.let { drawableValue ->
            drawableValue.alpha = opacity * 255 / 100
        }
        runCatching {
            val style = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)
            val instance = style.getField("INSTANCE").get(null)
            val glassToken = style.getMethod("getDEFAULT_GLASS_TOKEN").invoke(instance)
            // This public entry point applies the material type and registers the view with
            // HyperOS's Glass renderer before the lower-level radius parameters are changed.
            style.methods
                .first { it.name == "setMiBackgroundStyle" && it.parameterCount == 3 }
                .invoke(null, view, null, glassToken)

            val blurUtils = classLoader.loadClass(MIUI_BLUR_UTILS_CLASS)
            blurUtils.getMethod(
                "setMiGlassBlurRadius",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(
                null,
                view,
                smallBlur,
                largeBlur,
            )

            if (highlight) {
                val params = style.getDeclaredField("defaultBloomStrokeParams").apply { isAccessible = true }
                    .get(null) as FloatArray
                style.getMethod("setMiBloomStrokeCompat", View::class.java, FloatArray::class.java)
                    .invoke(null, view, params.clone())
            }
            view.invalidate()
            expandedIslandMaterialSettings[view] = configuration
            log(Log.DEBUG, TAG, "Applied expanded island glass: opacity=$opacity")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not apply expanded island glass", error)
        }
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

    private fun installShadeMaterialHooks(preferences: SharedPreferences, classLoader: ClassLoader) {
        runCatching {
            installFocusNotificationMaterialEnforcementHooks(preferences, classLoader)
            installFocusNotificationBackgroundHook(preferences, classLoader)

            val setGlass = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            hook(setGlass)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("shade-notification-glass-material")
                .intercept { chain ->
                    val original = chain.getArg(0) as? FloatArray
                    val view = chain.thisObject as? View
                    val controlCenter = isControlCenterCall()
                    val notification = isNotificationCenterCall()
                    val normalNotificationMaterial = if (
                        original != null &&
                        original.size >= MIN_GLASS_PARAMS_SIZE &&
                        original.any { it != 0f } &&
                        shouldUseNormalNotificationMaterial(view, preferences)
                    ) {
                        view?.let(::normalNotificationGlassParams)
                    } else {
                        null
                    }
                    val tuning = elementMaterialOverride(preferences, view, controlCenter, notification)
                    if (original != null && original.size >= MIN_GLASS_PARAMS_SIZE &&
                        (normalNotificationMaterial != null || tuning?.enabled == true)
                    ) {
                        logControlCenterMaterialHit(view, "glass-material")
                        val material = normalNotificationMaterial ?: original
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(if (tuning?.enabled == true) applyMaterialOverride(material, tuning) else material),
                        )
                    } else {
                        chain.proceed()
                    }
                }

            val setGlassRadius = View::class.java.getMethod(
                "setMiGlassBlurRadius",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            hook(setGlassRadius)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("shade-notification-glass-radius")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val tuning = elementMaterialOverride(
                        preferences,
                        view,
                        isControlCenterCall(),
                        isNotificationCenterCall(),
                    )
                    if (tuning?.enabled == true && tuning.glassRadius > 0) {
                        logControlCenterMaterialHit(view, "glass-radius")
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(tuning.glassRadius, tuning.glassRadius),
                        )
                    } else {
                        chain.proceed()
                    }
                }

            // Exact NotificationRowGlassEffect path from hyperos4-glass-blur-main.
            hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-row-glass-on-attach")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view ->
                        requestNotificationRowGlass(view, preferences, "attach")
                    }
                    result
                }

            hook(View::class.java.getMethod("setBackground", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-row-glass-on-background")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view ->
                        requestNotificationRowGlass(view, preferences, "background")
                    }
                    result
                }

            // Keyguard keeps notification rows attached between screen-off cycles. Reapply the
            // platform's row effect when a reused background becomes visible again.
            hook(View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-row-glass-on-visibility")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (chain.getArg(0) == View.VISIBLE && view != null) {
                        requestNotificationRowGlass(view, preferences, "visible")
                    }
                    result
                }

            hook(View::class.java.getMethod("onVisibilityAggregated", Boolean::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-row-glass-on-visibility-aggregated")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (chain.getArg(0) == true && view != null) {
                        requestNotificationRowGlass(view, preferences, "visible-aggregated")
                    }
                    result
                }

            // NotificationRowBlurEffect may reset the material type to BLUR after the
            // system glass recipe has been applied; retain the GLASS material for rows.
            val setMaterialType = View::class.java.getMethod(
                "setMiViewMaterialType",
                Int::class.javaPrimitiveType,
            )
            hook(setMaterialType)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-row-glass-material-type")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (view != null && notificationMaterialTarget(view, preferences) &&
                        (!isMediaNotificationView(view) || (chain.getArg(0) as Int) == 1) &&
                        notificationMaterialEnabled(preferences)
                    ) {
                        chain.proceedWith(chain.thisObject, arrayOf(1))
                    } else {
                        chain.proceed()
                    }
                }

            // Glass outlines are cleared by the blur recipe on some OS 4 builds.
            runCatching {
                val setBlurEnhanceFlag = View::class.java.getMethod(
                    "setMiBackgroundBlurEnhanceFlag",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                hook(setBlurEnhanceFlag)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("notification-row-glass-outline")
                    .intercept { chain ->
                        val view = chain.thisObject as? View
                        if (view != null && notificationMaterialTarget(view, preferences) &&
                            notificationMaterialEnabled(preferences)
                        ) {
                            val flags = chain.getArg(0) as Int
                            val mask = chain.getArg(1) as Int
                            chain.proceedWith(chain.thisObject, arrayOf(flags or 8192, mask or 8192))
                        } else {
                            chain.proceed()
                        }
                    }
            }.onFailure { error ->
                log(Log.INFO, TAG, "Notification glass outline API is unavailable", error)
            }

            // The final notification background is sometimes stretched to the
            // bottom of the shade.  This is the reference module's SDF-height
            // guard, keeping the Glass layer within the visible row content.
            runCatching {
                val setSdfMaxSize = View::class.java.getMethod(
                    "setMiGlassSdfMaxSize",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
                hook(setSdfMaxSize)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("notification-row-glass-sdf-size")
                    .intercept { chain ->
                        val view = chain.thisObject as? View
                        if (view != null && notificationMaterialTarget(view, preferences) &&
                            notificationMaterialEnabled(preferences)
                        ) {
                            val visibleHeight = notificationVisibleHeight(view)
                            val wantedHeight = chain.getArg(1) as Float
                            if (visibleHeight > 0 && wantedHeight > visibleHeight) {
                                chain.proceedWith(
                                    chain.thisObject,
                                    arrayOf(chain.getArg(0), visibleHeight.toFloat()),
                                )
                            } else {
                                chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
            }.onFailure { error ->
                log(Log.INFO, TAG, "Notification glass SDF API is unavailable", error)
            }

            val setBackgroundBlur = View::class.java.getMethod(
                "setMiBackgroundBlurRadius",
                Int::class.javaPrimitiveType,
            )
            hook(setBackgroundBlur)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("shade-panel-background-radius")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val tuning = backgroundMaterialOverride(preferences)
                    if (tuning?.enabled == true && isShadePanelBackgroundCall()) {
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf((chain.getArg(0) as Int) * tuning.blurPercent / 100),
                        )
                    } else {
                        chain.proceed()
                    }
                }
            val setScaleRatio = View::class.java.getMethod(
                "setMiBackgroundBlurScaleRatio",
                Float::class.javaPrimitiveType,
            )
            hook(setScaleRatio)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("shade-panel-background-scale")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val tuning = backgroundMaterialOverride(preferences)
                    if (tuning?.enabled == true && isShadePanelBackgroundCall(view)) {
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf((chain.getArg(0) as Float) * tuning.scalePercent / 100f),
                        )
                    } else chain.proceed()
                }
            val setBlendColors = View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java)
            hook(setBlendColors)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("shade-panel-background-tint")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val tuning = backgroundMaterialOverride(preferences)
                    val original = chain.getArg(0) as? ArrayList<*>
                    val normalBlend = if (original != null) {
                        normalNotificationBlendPoints(view, preferences)
                    } else {
                        null
                    }
                    if (normalBlend != null) {
                        chain.proceedWith(chain.thisObject, arrayOf(normalBlend))
                    } else if (tuning?.enabled == true && tuning.tintEnabled && tuning.tintStrength > 0 &&
                        original != null && isShadePanelBackgroundCall(view)
                    ) {
                        chain.proceedWith(
                            chain.thisObject,
                            arrayOf(applyBackgroundTint(original, tuning)),
                        )
                    } else chain.proceed()
                }
            log(Log.INFO, TAG, "Installed configurable notification and control-center material hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install notification and shade material hooks", error)
        }
    }

    private fun installLockscreenClockColonHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
        scope: String,
    ) {
        runCatching {
            val method = classLoader.loadClass(CLOCK_BEAN_CLASS)
                .getDeclaredMethod(CLOCK_BEAN_IS_COLON_SHOW_METHOD)
                .apply { isAccessible = true }
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-clock-colon:$scope")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE, false)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "Installed lockscreen clock colon hook for $scope")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install lockscreen clock colon hook for $scope", error)
        }
    }

    /**
     * Builds the compact two-row dual-SIM glyph from the legacy signal state.  The modern binder
     * supplies the live ImageView that will host it.
     */
    private fun installStackedMobileSignalHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        stackedMobilePreferences = preferences
        val enabled = { preferences.getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false) }
        val presentationRequired = {
            enabled() ||
                preferences.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0).coerceIn(0, 2) == 1 ||
                preferences.getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0).coerceIn(0, 2) == 1
        }
        var hookCount = 0

        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.connectivity.MobileSignalController",
            )
            val notifyListeners = controllerClass.methods.firstOrNull { method ->
                method.name == "notifyListeners" && method.parameterCount == 1
            } ?: error("MobileSignalController.notifyListeners was not found")
            hook(notifyListeners)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-controller")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject ?: return@intercept result
                    updateStackedMobileSubscription(controller)
                    refreshStackedMobilePresentations(enabled)
                    result
                }
            hookCount++
        }.onFailure { error ->
            log(Log.WARN, TAG, "Stacked mobile controller hook unavailable", error)
        }

        runCatching {
            val networkControllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.connectivity.NetworkControllerImpl",
            )
            val subscriptionsChanged = networkControllerClass.methods.firstOrNull { method ->
                method.name == "setCurrentSubscriptionsLocked" && method.parameterCount == 1
            } ?: error("NetworkControllerImpl.setCurrentSubscriptionsLocked was not found")
            hook(subscriptionsChanged)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-subscriptions")
                .intercept { chain ->
                    val result = chain.proceed()
                    synchronized(stackedMobileSignalLock) {
                        stackedMobileNetworkController = chain.thisObject
                    }
                    updateStackedMobileSubscriptions(chain.getArg(0) as? List<*>)
                    refreshStackedMobilePresentations(enabled)
                    result
                }
            hookCount++
        }.onFailure { error ->
            log(Log.WARN, TAG, "Stacked mobile subscription hook unavailable", error)
        }

        runCatching {
            val binderClass = loadFirstClass(classLoader, MOBILE_ICON_BINDER_CLASSES)
            val bindMethod = binderClass.methods.firstOrNull { method ->
                method.name == "bind" && method.parameterCount == 4 &&
                    ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: error("MiuiMobileIconBinder.bind was not found")
            hook(bindMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-bind")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.getArg(0) as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            captureMobileNetworkTypeSource(root, chain.getArg(2))
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            log(Log.WARN, TAG, "Stacked mobile binder hook unavailable", error)
        }

        runCatching {
            val interactorClass = classLoader.loadClass(
                "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl",
            )
            val typeMethods = interactorClass.methods.filter { method ->
                method.name == "getMobileTypeName" && method.parameterCount == 1
            }
            if (typeMethods.isEmpty()) error("MiuiMobileIconInteractorImpl.getMobileTypeName was not found")
            typeMethods.forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("status-bar:mobile-network-type-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val interactor = chain.thisObject
                        val subId = readInheritedField(interactor, "subId") as? Number
                        if (subId != null && result is String) {
                            synchronized(stackedMobileSignalLock) {
                                stackedMobileNetworkTypes[subId.toInt()] = result
                            }
                            refreshStackedMobilePresentations(enabled)
                        }
                        result
                    }
            }
            hookCount += typeMethods.size
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Mobile network type hook unavailable", error)
        }

        runCatching {
            val mobileViewClass = loadFirstClass(classLoader, MODERN_MOBILE_VIEW_CLASSES)
            val constructMethod = mobileViewClass.methods.firstOrNull { method ->
                method.name == "constructAndBind" && method.parameterCount == 5
            } ?: error("ModernStatusBarMobileView.constructAndBind was not found")
            hook(constructMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-construct")
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++

            // StatusIconContainer lays out StatusIconDisplayable children from
            // isIconVisible(), not from View.visibility.  Hiding the secondary root alone
            // therefore leaves its measured width in the status-bar spacing calculation.
            val isIconVisibleMethod = mobileViewClass.methods.firstOrNull { method ->
                method.name == "isIconVisible" && method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            } ?: error("ModernStatusBarMobileView.isIconVisible was not found")
            hook(isIconVisibleMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-icon-visible")
                .intercept { chain ->
                    val view = chain.thisObject as? ViewGroup
                    if (view != null && shouldSuppressStackedMobileIcon(view)) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            hookCount++
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Stacked mobile construction hook unavailable", error)
        }

        runCatching {
            hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-drawable")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ImageView
                    if (view != null && isStackedMobilePresentationSignal(view)) {
                        refreshStackedMobilePresentations(enabled)
                    }
                    result
                }
            hook(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-resource")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? ImageView
                    if (view != null && isStackedMobilePresentationSignal(view)) {
                        refreshStackedMobilePresentations(enabled)
                    }
                    result
                }
            hook(ImageView::class.java.getMethod("setImageTintList", ColorStateList::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-tint")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ImageView)?.let { view ->
                        refreshStackedMobilePresentationForView(view, enabled)
                    }
                    result
                }
            hookCount += 3
        }.onFailure { error ->
            log(Log.WARN, TAG, "Stacked mobile drawable hooks unavailable", error)
        }

        // The original mobile_type_single TextView is the most stable rendered network-type
        // source across HyperOS builds. Mirror its text after SystemUI updates it, while keeping
        // the injected TextView out of this hook by its private tag.
        runCatching {
            hook(TextView::class.java.getMethod("setText", CharSequence::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:mobile-network-type-text")
                .intercept { chain ->
                    val result = chain.proceed()
                    val sourceView = chain.thisObject as? TextView
                    if (sourceView != null && sourceView.tag != INDEPENDENT_MOBILE_TYPE_TAG &&
                        stackedMobileApplying.get() != true
                    ) {
                        val presentation = synchronized(stackedMobileSignalLock) {
                            stackedMobilePresentations.values.firstOrNull {
                                it.networkTypeView === sourceView
                            }
                        }
                        if (presentation != null) {
                            refreshStackedMobilePresentationForView(presentation.signal, enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Mobile network type text hook unavailable", error)
        }

        // The modern view assigns its subscription id after inflation. This also covers builds
        // where the binder call is hidden behind a generated lambda.
        runCatching {
            val modernClass = loadFirstClass(classLoader, MODERN_MOBILE_VIEW_CLASSES)
            val setSubId = modernClass.methods.firstOrNull { method ->
                method.name == "setSubId" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            } ?: error("ModernStatusBarMobileView.setSubId was not found")
            hook(setSubId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("status-bar:stacked-mobile-sub-id")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { root ->
                        if (presentationRequired()) {
                            registerStackedMobilePresentation(root, enabled)
                            refreshStackedMobilePresentations(enabled)
                        }
                    }
                    result
                }
            hookCount++
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Stacked mobile subId hook unavailable", error)
        }

        if (hookCount > 0) {
            log(Log.INFO, TAG, "Installed stacked mobile signal hooks ($hookCount)")
        } else {
            log(Log.WARN, TAG, "No stacked mobile signal hooks could be installed")
        }
    }

    private fun updateStackedMobileSubscription(controller: Any) {
        readInstanceField(controller, "mNetworkController")?.let { networkController ->
            synchronized(stackedMobileSignalLock) { stackedMobileNetworkController = networkController }
        }
        val subscriptionInfo = readInstanceField(controller, "mSubscriptionInfo") ?: return
        val subscriptionId = invokeInt(subscriptionInfo, "getSubscriptionId") ?: return
        val slotIndex = invokeInt(subscriptionInfo, "getSimSlotIndex")
            ?: SubscriptionManager.getSlotIndex(subscriptionId)
        if (slotIndex < 0) return
        val currentState = readInheritedField(controller, "mCurrentState") ?: return
        val dataSim = readInheritedField(currentState, "dataSim") as? Boolean ?: false
        val signalLevel = (readInheritedField(currentState, "level") as? Number)
            ?.toInt()
            ?.coerceIn(0, 4)
            ?: 0
        synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[subscriptionId] = StackedMobileSubscription(
                slot = slotIndex,
                dataSim = dataSim,
                signalLevel = signalLevel,
            )
            stackedMobileActiveSubscriptionIds += subscriptionId
        }
    }

    private fun updateStackedMobileSubscriptions(subscriptions: List<*>?) {
        if (subscriptions == null) return
        val updated = LinkedHashMap<Int, StackedMobileSubscription>()
        subscriptions.forEach { subscriptionInfo ->
            if (subscriptionInfo == null) return@forEach
            val subscriptionId = invokeInt(subscriptionInfo, "getSubscriptionId") ?: return@forEach
            val slotIndex = invokeInt(subscriptionInfo, "getSimSlotIndex")
                ?: SubscriptionManager.getSlotIndex(subscriptionId)
            if (slotIndex < 0) return@forEach
            val previous = synchronized(stackedMobileSignalLock) {
                stackedMobileSubscriptions[subscriptionId]
            }
            updated[subscriptionId] = StackedMobileSubscription(
                slot = slotIndex,
                dataSim = previous?.dataSim == true,
                signalLevel = previous?.signalLevel ?: 0,
            )
        }
        synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions.clear()
            stackedMobileSubscriptions.putAll(updated)
            stackedMobileActiveSubscriptionIds.clear()
            stackedMobileActiveSubscriptionIds.addAll(updated.keys)
        }
    }

    private fun captureMobileNetworkTypeSource(root: ViewGroup, vmImpl: Any?) {
        if (vmImpl == null) return
        val viewModel = runCatching {
            vmImpl.javaClass.methods.firstOrNull {
                it.name == "getCellProvider" && it.parameterCount == 0
            }?.invoke(vmImpl)
        }.getOrNull() ?: return
        val source = readInheritedField(viewModel, "showName") ?: return
        synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[root]?.networkTypeSource = source
        }
    }

    private fun ensureIndependentMobileType(presentation: StackedMobilePresentation) {
        val group = findViewByEntryName(presentation.root, "mobile_group") as? ViewGroup ?: return
        val signalContainer = findViewByEntryName(presentation.root, "mobile_signal_container") as? ViewGroup
        presentation.mobileGroup = group
        presentation.mobileSignalContainer = signalContainer

        val original = findViewByEntryName(presentation.root, "mobile_type_single") as? TextView
        presentation.networkTypeView = original
        val previous = presentation.independentType
        val textView = original ?: previous ?: TextView(group.context).also {
            it.tag = INDEPENDENT_MOBILE_TYPE_TAG
            it.includeFontPadding = false
            it.gravity = Gravity.CENTER_VERTICAL
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            presentation.independentType = it
        }
        if (previous != null && previous !== textView) {
            restoreIndependentMobileType(presentation)
        }
        if (original != null && previous != null && previous !== original &&
            previous.tag == INDEPENDENT_MOBILE_TYPE_TAG
        ) {
            (previous.parent as? ViewGroup)?.removeView(previous)
        }
        if (textView === original && textView.tag == INDEPENDENT_MOBILE_TYPE_TAG) {
            textView.tag = null
        }
        presentation.independentType = textView
    }

    private fun captureHorizontalMargins(view: View): IntArray? =
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }

    private fun applyHorizontalMargins(
        view: View,
        original: IntArray?,
        leftOffsetPx: Int,
        rightOffsetPx: Int,
    ) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val baseline = original ?: return
        params.leftMargin = baseline[0] + leftOffsetPx
        params.topMargin = baseline[1]
        params.rightMargin = baseline[2] + rightOffsetPx
        params.bottomMargin = baseline[3]
        view.layoutParams = params
    }

    private fun restoreIndependentMobileType(presentation: StackedMobilePresentation) {
        val textView = presentation.independentType ?: return
        val baselineView = presentation.savedIndependentView ?: textView
        baselineView.scaleX = 1f
        baselineView.scaleY = 1f
        presentation.savedIndependentTranslationY?.let { baselineView.translationY = it }
        presentation.savedIndependentMargins?.let { original ->
            applyHorizontalMargins(baselineView, original, 0, 0)
        }
        presentation.savedIndependentView = null
        presentation.savedIndependentTranslationY = null
        presentation.savedIndependentMargins = null
        val originalParent = presentation.savedIndependentParent
        if (originalParent != null && textView.parent !== originalParent) {
            (textView.parent as? ViewGroup)?.removeView(textView)
            textView.layoutParams = presentation.savedIndependentLayoutParams
            originalParent.addView(
                textView,
                presentation.savedIndependentIndex.coerceIn(0, originalParent.childCount),
            )
        }
        presentation.savedIndependentParent = null
        presentation.savedIndependentIndex = -1
        presentation.savedIndependentLayoutParams = null
    }

    private fun applyIndependentMobileType(
        presentation: StackedMobilePresentation,
        useStacked: Boolean,
    ) {
        val textView = presentation.independentType ?: return
        val group = presentation.mobileGroup ?: return
        val signalContainer = presentation.mobileSignalContainer
        val mode = stackedMobilePreferences?.getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0)
            ?.coerceIn(0, 2) ?: 0
        val position = stackedMobilePreferences?.getInt(KEY_MOBILE_NETWORK_TYPE_POSITION, 0)?.coerceIn(0, 1) ?: 0
        val slotIndex = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.slot
        } ?: SubscriptionManager.getSlotIndex(presentation.subscriptionId)
        // In normal mode preserve SystemUI's per-slot behavior.  Once the two roots are merged,
        // only the retained slot-0 root may render the independent type.
        val displayLogic = stackedMobilePreferences
            ?.getInt(KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC, 0)
            ?.coerceIn(0, 1) ?: 0
        val shouldShow = mode == 1 &&
            (!useStacked || slotIndex == 0) &&
            (displayLogic == 0 || isUsingMobileData(presentation))

        if (mode == 1) {
            if (presentation.savedIndependentView !== textView) {
                restoreIndependentMobileType(presentation)
                presentation.savedIndependentView = textView
                presentation.savedIndependentTranslationY = textView.translationY
                presentation.savedIndependentMargins = captureHorizontalMargins(textView)
            }
            if (textView.parent !== group) {
                presentation.savedIndependentParent = textView.parent as? ViewGroup
                presentation.savedIndependentIndex = presentation.savedIndependentParent
                    ?.indexOfChild(textView) ?: -1
                presentation.savedIndependentLayoutParams = textView.layoutParams
                (textView.parent as? ViewGroup)?.removeView(textView)
                val containerIndex = signalContainer?.let(group::indexOfChild) ?: -1
                val insertIndex = if (containerIndex >= 0) containerIndex else group.childCount
                group.addView(textView, insertIndex)
            }
            val baseTranslationY = presentation.savedIndependentTranslationY ?: 0f
            val density = textView.resources.displayMetrics.density
            val scale = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_SCALE, 1f)
                ?.coerceIn(0.1f, 3f) ?: 1f
            val verticalOffset = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_VERTICAL_OFFSET, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            val leftMargin = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_LEFT_MARGIN, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            val rightMargin = stackedMobilePreferences
                ?.getFloat(KEY_MOBILE_NETWORK_TYPE_RIGHT_MARGIN, 0f)
                ?.coerceIn(-8f, 8f) ?: 0f
            textView.scaleX = scale
            textView.scaleY = scale
            textView.translationY = baseTranslationY + verticalOffset * density
            applyHorizontalMargins(
                textView,
                presentation.savedIndependentMargins,
                (leftMargin * density).roundToInt(),
                (rightMargin * density).roundToInt(),
            )
        } else {
            restoreIndependentMobileType(presentation)
        }

        if (mode == 1 && signalContainer != null) {
            val containerIndex = group.indexOfChild(signalContainer)
            if (containerIndex >= 0) {
                val targetIndex = containerIndex + if (position == 0) 0 else 1
                if (group.indexOfChild(textView) != targetIndex) {
                    group.removeView(textView)
                    val currentContainerIndex = group.indexOfChild(signalContainer)
                    if (currentContainerIndex >= 0) {
                        group.addView(
                            textView,
                            (currentContainerIndex + if (position == 0) 0 else 1)
                                .coerceIn(0, group.childCount),
                        )
                    } else {
                        group.addView(textView)
                    }
                }
            }
        }

        when (mode) {
            1 -> {
                val sourceValue = readCurrentFlowValue(presentation.networkTypeSource)
                val realType = sourceValue?.toString().orEmpty().ifBlank {
                    synchronized(stackedMobileSignalLock) {
                        stackedMobileNetworkTypes[presentation.subscriptionId].orEmpty()
                    }
                }.ifBlank { presentation.networkTypeView?.text?.toString().orEmpty() }
                val customText = stackedMobilePreferences
                    ?.getString(KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT, "")
                    .orEmpty()
                val displayText = customText.ifBlank { realType }
                stackedMobileApplying.set(true)
                try {
                    textView.text = formatIndependentMobileType(
                        displayText,
                        realType,
                        stackedMobilePreferences?.getBoolean(KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A, false) == true,
                    )
                } finally {
                    stackedMobileApplying.remove()
                }
                val color = presentation.signal.imageTintList?.getColorForState(
                    presentation.signal.drawableState,
                    Color.WHITE,
                ) ?: Color.WHITE
                textView.setTextColor(color)
                setStackedMobileViewVisibility(
                    textView,
                    if (shouldShow && displayText.isNotEmpty()) View.VISIBLE else View.GONE,
                )
            }
            2 -> setStackedMobileViewVisibility(textView, View.GONE)
            else -> if (textView.tag == INDEPENDENT_MOBILE_TYPE_TAG) {
                setStackedMobileViewVisibility(textView, View.GONE)
            }
        }
    }

    private fun formatIndependentMobileType(
        displayText: String,
        realType: String,
        shrink5gaA: Boolean,
    ): CharSequence {
        if (!shrink5gaA || !realType.equals("5GA", ignoreCase = true) || !displayText.endsWith("A")) {
            return displayText
        }
        return SpannableString(displayText).apply {
            setSpan(
                RelativeSizeSpan(0.5f),
                length - 1,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Matches HyperCeiler's mobile-data display mode. */
    private fun isUsingMobileData(presentation: StackedMobilePresentation): Boolean {
        val context = presentation.root.context
        val airplaneMode = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
        }.getOrDefault(false)
        if (airplaneMode) return false
        if (presentation.subscriptionId != SubscriptionManager.getDefaultDataSubscriptionId()) {
            return false
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun readCurrentFlowValue(flow: Any?): Any? = flow?.let {
        runCatching {
            it.javaClass.methods.firstOrNull { method ->
                method.name == "getValue" && method.parameterCount == 0
            }?.invoke(it)
        }.getOrNull() ?: readInheritedField(it, "value")
    }

    private fun registerStackedMobilePresentation(root: ViewGroup, enabled: () -> Boolean) {
        val signal = findViewByEntryName(root, "mobile_signal") as? ImageView ?: return
        val subscriptionId = invokeInt(root, "getSubId")
            ?: (readInstanceField(root, "subId") as? Number)?.toInt()
            ?: return
        if (subscriptionId < 0) return
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[root]?.also {
                it.signal = signal
                it.subscriptionId = subscriptionId
            } ?: StackedMobilePresentation(root, signal, subscriptionId).also {
                stackedMobilePresentations[root] = it
            }
        }
        registerMobileNetworkStateCallback(root.context, enabled)
        if (stackedMobilePreferences?.getInt(KEY_MOBILE_NETWORK_TYPE_MODE, 0) == 1) {
            ensureIndependentMobileType(presentation)
        }
        if (enabled()) ensureDualMobileSignal(presentation)
        if (!presentation.attachListenerInstalled) {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    scheduleStackedMobilePresentationRefresh(presentation, enabled)
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            presentation.attachListenerInstalled = true
        }
        requestStackedMobileParentLayout(root)
    }

    private fun registerMobileNetworkStateCallback(
        context: Context,
        enabled: () -> Boolean,
    ) {
        synchronized(stackedMobileSignalLock) {
            if (stackedMobileNetworkCallbackRegistered) return
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshStackedMobilePresentations(enabled)
                }

                override fun onLost(network: Network) {
                    refreshStackedMobilePresentations(enabled)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refreshStackedMobilePresentations(enabled)
                }
            }
            runCatching {
                connectivity.registerDefaultNetworkCallback(callback)
                stackedMobileNetworkCallbackRegistered = true
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Mobile network state callback unavailable", error)
            }
        }
    }

    private fun isStackedMobilePresentationSignal(view: ImageView): Boolean {
        if (stackedMobileApplying.get() == true) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any { it.signal === view }
        }
    }

    private fun isIndependentMobileTypeView(view: View?): Boolean {
        if (view == null) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any { it.independentType === view }
        }
    }

    private fun shouldHideSystemMobileSignal(view: View?, configuredMode: Int): Boolean {
        val mode = configuredMode.coerceIn(0, 2)
        if (mode == 0 || view !is ImageView) return false
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.firstOrNull { presentation ->
                presentation.signal === view || isDescendantOf(view, presentation.root)
            }
        } ?: return mode == 2
        if (mode == 2) return true
        val dataSim = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.dataSim
        }
        // If SystemUI has not reported the state for this subscription yet, keep the
        // icon visible instead of hiding the wrong SIM during initialization.
        return dataSim == false
    }

    private fun isDescendantOf(view: View, root: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isStackedSecondaryMobileRoot(view: View?): Boolean {
        if (view == null) return false
        return synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.any {
                it.root === view && it.rootHiddenByStacked
            }
        }
    }

    private fun shouldSuppressStackedMobileIcon(view: ViewGroup): Boolean {
        if (stackedMobilePreferences?.getBoolean(KEY_STACKED_MOBILE_SIGNAL_ENABLED, false) != true) {
            return false
        }
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations[view]
        } ?: return false
        if (!isStackedMobileDualSim()) return false

        // Use the same order as the merged glyph. This remains correct when the data SIM is
        // not slot 0 and also covers builds where getSimSlotIndex() is temporarily unavailable.
        val retainedSubscriptionId = stackedMobileRenderOrder().firstOrNull() ?: return false
        return presentation.subscriptionId != retainedSubscriptionId
    }

    private fun refreshStackedMobilePresentations(enabled: () -> Boolean) {
        val presentations = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.toList()
        }
        presentations.forEach { scheduleStackedMobilePresentationRefresh(it, enabled) }
    }

    private fun refreshStackedMobilePresentationForView(view: ImageView, enabled: () -> Boolean) {
        val presentation = synchronized(stackedMobileSignalLock) {
            stackedMobilePresentations.values.firstOrNull { it.signal === view }
        } ?: return
        scheduleStackedMobilePresentationRefresh(presentation, enabled)
    }

    /**
     * Binder callbacks run before their status-bar root joins the window.  Posting from that
     * phase is not reliable: a View can discard the callback before it is attached.  The attach
     * listener registered above performs the first render; this helper only schedules work for
     * roots that can receive it and coalesces later signal updates to one callback per root.
     */
    private fun scheduleStackedMobilePresentationRefresh(
        presentation: StackedMobilePresentation,
        enabled: () -> Boolean,
    ) {
        if (!presentation.root.isAttachedToWindow) return
        val shouldPost = synchronized(stackedMobileSignalLock) {
            if (presentation.refreshPending) false else {
                presentation.refreshPending = true
                true
            }
        }
        if (!shouldPost) return
        presentation.root.post {
            try {
                applyStackedMobilePresentation(presentation, enabled())
            } finally {
                synchronized(stackedMobileSignalLock) { presentation.refreshPending = false }
            }
        }
    }

    private fun applyStackedMobilePresentation(presentation: StackedMobilePresentation, enabled: Boolean) {
        if (!presentation.root.isAttachedToWindow) return
        val useStacked = enabled && isStackedMobileDualSim()
        applyIndependentMobileType(presentation, useStacked)
        if (!useStacked) {
            restoreSecondaryMobileRoot(presentation)
            if (presentation.dualContainer != null) restoreDualMobileSignal(presentation)
            return
        }

        val slotIndex = synchronized(stackedMobileSignalLock) {
            stackedMobileSubscriptions[presentation.subscriptionId]?.slot
        } ?: SubscriptionManager.getSlotIndex(presentation.subscriptionId)
        if (slotIndex > 0) {
            hideSecondaryMobileRoot(presentation)
            return
        }
        if (slotIndex < 0) {
            restoreSecondaryMobileRoot(presentation)
            restoreDualMobileSignal(presentation)
            return
        }
        restoreSecondaryMobileRoot(presentation)

        val orderedSubscriptions = stackedMobileRenderOrder().mapNotNull { subscriptionId ->
            synchronized(stackedMobileSignalLock) { stackedMobileSubscriptions[subscriptionId] }
        }
        val upperSubscription = orderedSubscriptions.getOrNull(0)
        val lowerSubscription = orderedSubscriptions.getOrNull(1)
        if (upperSubscription == null || lowerSubscription == null) {
            restoreDualMobileSignal(presentation)
            return
        }
        val dualContainer = presentation.dualContainer ?: return
        val dualSignal = presentation.dualSignal ?: return
        setDualMobileSignalVisibility(presentation, true)
        if (presentation.savedDualMargins == null) {
            presentation.savedDualTranslationY = dualContainer.translationY
            presentation.savedDualMargins = captureHorizontalMargins(dualContainer)
        }
        updateDualMobileSignalLayout(presentation)
        val density = dualContainer.resources.displayMetrics.density
        val scale = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_SCALE, 1f)
            ?.coerceIn(0.1f, 3f) ?: 1f
        val verticalOffset = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_VERTICAL_OFFSET, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        val leftMargin = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_LEFT_MARGIN, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        val rightMargin = stackedMobilePreferences
            ?.getFloat(KEY_STACKED_MOBILE_SIGNAL_RIGHT_MARGIN, 0f)
            ?.coerceIn(-8f, 8f) ?: 0f
        dualContainer.scaleX = 1.06f * scale
        dualContainer.scaleY = scale
        dualContainer.translationY =
            (presentation.savedDualTranslationY ?: 0f) + verticalOffset * density
        applyHorizontalMargins(
            dualContainer,
            presentation.savedDualMargins,
            (leftMargin * density).roundToInt(),
            (rightMargin * density).roundToInt(),
        )
        dualSignal.setImageDrawable(
            StackedMobileDrawable(upperSubscription.signalLevel, lowerSubscription.signalLevel).apply {
                alpha = presentation.signal.imageAlpha
                setTintList(presentation.signal.imageTintList)
                presentation.signal.colorFilter?.let(::setColorFilter)
            },
        )
    }

    private fun isStackedMobileDualSim(): Boolean = synchronized(stackedMobileSignalLock) {
        val controllerCount = stackedMobileNetworkController?.let { networkController ->
            val controllers = readInstanceField(networkController, "mMobileSignalControllers")
            (controllers as? android.util.SparseArray<*>)?.size() ?: 0
        } ?: 0
        stackedMobileActiveSubscriptionIds.size >= 2 || controllerCount >= 2
    }

    private fun stackedMobileRenderOrder(): List<Int> = synchronized(stackedMobileSignalLock) {
        val active = LinkedHashSet(stackedMobileActiveSubscriptionIds).apply {
            stackedMobilePresentations.values
                .filter { it.root.isAttachedToWindow }
                .forEach { add(it.subscriptionId) }
        }.toList()
        active.sortedWith(compareBy<Int>(
            { if (stackedMobileSubscriptions[it]?.dataSim == true) 0 else 1 },
            { stackedMobileSubscriptions[it]?.slot ?: SubscriptionManager.getSlotIndex(it) },
            { it },
        ))
    }

    private fun ensureDualMobileSignal(presentation: StackedMobilePresentation) {
        val signalContainer = presentation.mobileSignalContainer ?: return
        val existing = signalContainer.findViewById<FrameLayout>(stackedMobileDualContainerId)
        val dualContainer = existing ?: FrameLayout(signalContainer.context).apply {
            id = stackedMobileDualContainerId
            layoutParams = ViewGroup.LayoutParams(
                resolveDualMobileSignalWidth(presentation.signal),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }.also { signalContainer.addView(it) }

        presentation.dualContainer = dualContainer
        presentation.dualSignal = dualContainer.getChildAt(0) as? ImageView ?: return
        configureDualMobileSignalConstraints(dualContainer)
        updateDualMobileSignalLayout(presentation)
    }

    private fun resolveDualMobileSignalWidth(signal: ImageView): Int {
        val density = signal.resources.displayMetrics.density
        val measured = signal.width
        if (measured > 0) return measured
        val layoutWidth = signal.layoutParams?.width ?: 0
        if (layoutWidth > 0) return layoutWidth
        if (signal.minimumWidth > 0) return signal.minimumWidth
        return (18f * density).roundToInt()
    }

    private fun updateDualMobileSignalLayout(presentation: StackedMobilePresentation) {
        val dualContainer = presentation.dualContainer ?: return
        val dualSignal = presentation.dualSignal ?: return
        val width = resolveDualMobileSignalWidth(presentation.signal)
        dualContainer.layoutParams?.let { params ->
            if (params.width != width) {
                params.width = width
                dualContainer.layoutParams = params
            }
        }
        dualSignal.layoutParams?.let { params ->
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                params.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                dualSignal.layoutParams = params
            }
        }
    }

    private fun configureDualMobileSignalConstraints(dualContainer: FrameLayout) {
        val params = dualContainer.layoutParams ?: return
        setLayoutParamInt(params, "endToEnd", 0)
        setLayoutParamInt(params, "startToStart", -1)
        setLayoutParamInt(params, "topToTop", 0)
        setLayoutParamInt(params, "bottomToBottom", 0)
        dualContainer.layoutParams = params
    }

    private fun setLayoutParamInt(params: ViewGroup.LayoutParams, name: String, value: Int) {
        var type: Class<*>? = params.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.setInt(params, value)
                }
                return
            }
            type = type.superclass
        }
    }

    private fun setDualMobileSignalVisibility(presentation: StackedMobilePresentation, visible: Boolean) {
        presentation.dualContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        // The original ImageView remains untouched except for this temporary visibility switch;
        // SystemUI keeps its drawable and state flows intact for the normal mode.
        presentation.signal.visibility = if (visible) View.GONE else View.VISIBLE
        requestStackedMobileParentLayout(presentation.root)
    }

    private fun restoreDualMobileSignal(presentation: StackedMobilePresentation) {
        val dualContainer = presentation.dualContainer
        if (dualContainer != null) {
            dualContainer.visibility = View.GONE
            dualContainer.scaleX = 1f
            dualContainer.scaleY = 1f
            presentation.savedDualTranslationY?.let { dualContainer.translationY = it }
            presentation.savedDualMargins?.let { original ->
                applyHorizontalMargins(dualContainer, original, 0, 0)
            }
        }
        presentation.signal.visibility = View.VISIBLE
        presentation.savedDualTranslationY = null
        presentation.savedDualMargins = null
        requestStackedMobileParentLayout(presentation.root)
    }

    private fun hideSecondaryMobileRoot(presentation: StackedMobilePresentation) {
        if (!presentation.rootHiddenByStacked) {
            presentation.savedRootVisibility = presentation.root.visibility
            presentation.rootHiddenByStacked = true
        }
        presentation.root.visibility = View.GONE
        presentation.root.requestLayout()
        (presentation.root.parent as? View)?.requestLayout()
    }

    private fun restoreSecondaryMobileRoot(presentation: StackedMobilePresentation) {
        if (!presentation.rootHiddenByStacked) return
        presentation.root.visibility = presentation.savedRootVisibility ?: View.VISIBLE
        presentation.savedRootVisibility = null
        presentation.rootHiddenByStacked = false
        presentation.root.requestLayout()
        (presentation.root.parent as? View)?.requestLayout()
    }

    private fun requestStackedMobileParentLayout(root: View) {
        (root.parent as? View)?.requestLayout()
    }

    private fun loadFirstClass(classLoader: ClassLoader, classNames: Array<String>): Class<*> {
        var lastError: Throwable? = null
        classNames.forEach { className ->
            try {
                return classLoader.loadClass(className)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw ClassNotFoundException(classNames.joinToString(), lastError)
    }

    private fun setStackedMobileViewVisibility(view: View, visibility: Int) {
        view.visibility = visibility
    }

    private fun invokeInt(target: Any, methodName: String): Int? = runCatching {
        (target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?.invoke(target) as? Number)?.toInt()
    }.getOrNull()

    private fun findViewByEntryName(root: ViewGroup, entryName: String): View? {
        val id = runCatching { root.resources.getIdentifier(entryName, "id", SYSTEM_UI) }.getOrDefault(0)
        return if (id != 0) root.findViewById(id) else null
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
                        }
                        result
                    }
                effectHookCount++
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Focus effect hook unavailable for $className", error)
            }
        }

        runCatching {
            val injectorClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS)
            injectorClass.declaredMethods
                .filter { it.name == "updateFullAodAnimState" }
                .forEachIndexed { index, method ->
                    hook(method)
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
            log(Log.DEBUG, TAG, "Focus Full-AOD material hook unavailable", error)
        }

        log(Log.INFO, TAG, "Installed focus notification normal-material enforcement ($effectHookCount effects)")
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
                log(Log.INFO, TAG, "Re-applied normal notification glass after focus effect ($source)")
            }
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not re-apply normal notification glass after focus effect", error)
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
        runCatching {
            val backgroundClass = classLoader.loadClass(NOTIFICATION_BACKGROUND_VIEW_CLASS)
            backgroundClass.declaredMethods
                .filter { method ->
                    method.name == "setCustomBackground" && method.parameterCount == 1 &&
                        (method.parameterTypes[0] == Drawable::class.java ||
                            method.parameterTypes[0] == Int::class.javaPrimitiveType)
                }
                .forEachIndexed { index, method ->
                    hook(method)
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
            log(Log.INFO, TAG, "Installed transparent custom-background guard for focus notifications")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install focus notification custom-background guard", error)
        }
    }

    private fun notificationTuningFor(view: View, preferences: SharedPreferences): GlassTuning? {
        if (!isNotificationRowBackground(view)) return null
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
            log(Log.INFO, TAG, "Control-center $type material matched $method on ${view?.javaClass?.name}")
        }
    }

    private fun requestNotificationRowGlass(
        view: View,
        preferences: SharedPreferences,
        source: String,
    ) {
        if (!isNotificationRowBackground(view) ||
            isMediaNotificationView(view) ||
            !notificationMaterialEnabled(preferences) ||
            notificationGlassApplying.get() == true
        ) {
            return
        }
        val shouldApply = synchronized(notificationGlassAppliedViews) {
            notificationGlassAppliedViews.add(view)
        }
        if (!shouldApply) return
        val applied = applySystemNotificationRowGlass(view, source)
        if (!applied) {
            synchronized(notificationGlassAppliedViews) { notificationGlassAppliedViews.remove(view) }
            // Notification backgrounds can be attached before their parent row has finished
            // binding. Retry only when the first attempt could not resolve the owning row.
            view.post {
                if (view.isAttachedToWindow && notificationMaterialEnabled(preferences)) {
                    requestNotificationRowGlass(view, preferences, "$source-post")
                }
            }
        }
    }

    private fun shouldUseNormalNotificationMaterial(
        view: View?,
        preferences: SharedPreferences,
    ): Boolean {
        if (view == null || !preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)) return false
        return isMediaNotificationView(view) ||
            (isNotificationRowBackground(view) && notificationTypeFor(view) != NotificationMaterialType.NORMAL)
    }

    private fun notificationMaterialTarget(
        view: View?,
        preferences: SharedPreferences,
    ): Boolean {
        if (view == null) return false
        if (isNotificationRowBackground(view)) return true
        return isMediaNotificationView(view) &&
            preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)
    }

    private fun normalNotificationGlassParams(view: View): FloatArray? {
        val resources = view.resources
        val resourceName = if (notificationOnKeyguard(view)) {
            "notification_glass_params_on_keyguard"
        } else {
            NORMAL_NOTIFICATION_GLASS_PARAMS_ARRAY
        }
        synchronized(normalNotificationGlassParamsCache) {
            normalNotificationGlassParamsCache[resources]?.get(resourceName)?.let { return it.copyOf() }
        }
        val params = runCatching {
            val resourceId = resources.getIdentifier(
                resourceName,
                "array",
                SYSTEM_UI,
            )
            if (resourceId == 0) return@runCatching null
            resources.getStringArray(resourceId)
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
        if (view == null || !preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)) return null
        val isNotification = isNotificationRowBackground(view) || isMediaNotificationView(view)
        if (!isNotification || !isNotificationMaterialCall()) return null
        // The switch makes focus and MEDIA notifications use the normal notification recipe.
        // Ordinary notifications already use that recipe and must keep their original blend
        // points; replacing them here can apply the blend layer a second time and make the row
        // appear intermittently over-bright.
        if (notificationTypeFor(view) == NotificationMaterialType.NORMAL) return null
        val keyguard = notificationOnKeyguard(view)
        val suffix = if (keyguard) "keyguard" else "shade"
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
        val state = generateSequence<View>(view) { it.parent as? View }
            .mapNotNull { candidate ->
                runCatching {
                    readInstanceField(candidate, "mOnKeyguard") as? Boolean
                        ?: (candidate.javaClass.methods.firstOrNull {
                            it.name == "isOnKeyguard" && it.parameterCount == 0
                        }?.invoke(candidate) as? Boolean)
                }.getOrNull()
            }
            .firstOrNull()
        if (state != null) return state
        return isMediaNotificationView(view) && isLockscreenMediaView(view)
    }

    private fun applySystemNotificationRowGlass(background: View, source: String): Boolean {
        if (!isNotificationRowBackground(background) || notificationGlassApplying.get() == true) return false
        notificationGlassApplying.set(true)
        try {
            val row = generateSequence(background.parent) { it.parent }
                .filterIsInstance<View>()
                .firstOrNull { it.javaClass.name.contains("ExpandableNotificationRow") }
                ?: return false
            val effectClass = row.javaClass.classLoader?.loadClass(NOTIFICATION_ROW_GLASS_EFFECT_CLASS)
                ?: return false
            val instance = effectClass.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                ?: effectClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
                ?: return false
            val apply = effectClass.methods.firstOrNull {
                it.name == "apply" && it.parameterCount == 2
            } ?: return false
            apply.invoke(instance, row, background.context)
            log(Log.DEBUG, TAG, "Applied system notification glass through $source")
            return true
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Could not apply system notification glass", error)
            return false
        } finally {
            notificationGlassApplying.remove()
        }
    }

    private fun hasCustomizedNotificationTuning(view: View, preferences: SharedPreferences): Boolean =
        notificationTuningFor(view, preferences)?.let { it != GlassTuning() } == true

    private fun isNotificationRowBackground(view: View): Boolean {
        if (!view.javaClass.name.contains("NotificationBackgroundView")) return false
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return idName == null || idName == "backgroundNormal" || idName == "backgroundDimmed"
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

    private fun notificationVisibleHeight(view: View): Int = runCatching {
        val actualHeight = (view.javaClass.methods.firstOrNull {
            it.name == "getActualHeight" && it.parameterCount == 0
        }?.invoke(view) as? Number)?.toInt() ?: view.height
        val injector = readInstanceField(view, "mNotificationBackgroundViewInjector") ?: return@runCatching actualHeight
        val clipBottom = (readInstanceField(injector, "clipBottomAmount") as? Number)?.toInt() ?: 0
        val extClipBottom = (readInstanceField(injector, "extClipBottomAmount") as? Number)?.toInt() ?: 0
        (actualHeight - maxOf(clipBottom, extClipBottom)).coerceAtLeast(0)
    }.getOrDefault(view.height)

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
        return className.contains("NotificationShadeWindowView") ||
            className.contains("NotificationPanelView") ||
            className.contains("ControlCenterContainer") ||
            className.contains("ShadeBackground")
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

    private fun elementMaterialOverride(
        preferences: SharedPreferences,
        view: View?,
        controlCenter: Boolean,
        notification: Boolean,
    ): MaterialOverride? = when {
        // Media controls have their own progress/background renderer. Applying the generic
        // notification recipe to those child views can make the seek bar disappear while the
        // asynchronous glass layer is rebuilding.
        view != null && isMediaNotificationView(view) &&
            !preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) -> null
        view != null && isNotificationRowBackground(view) ->
            preferences.getMaterialOverride(KEY_NOTIFICATION_ELEMENTS_MATERIAL)
        controlCenter -> preferences.getMaterialOverride(KEY_CONTROL_CENTER_ELEMENTS_MATERIAL)
        notification -> preferences.getMaterialOverride(KEY_NOTIFICATION_ELEMENTS_MATERIAL)
        else -> null
    }

    private fun backgroundMaterialOverride(preferences: SharedPreferences): MaterialOverride? = when {
        isControlCenterCall() ->
            preferences.getMaterialOverride(KEY_CONTROL_CENTER_BACKGROUND_MATERIAL)
        isNotificationCenterCall() || isShadeBlurProviderCall() ->
            preferences.getMaterialOverride(KEY_NOTIFICATION_CENTER_BACKGROUND_MATERIAL)
        else -> null
    }

    private fun notificationMaterialEnabled(preferences: SharedPreferences): Boolean =
        preferences.getMaterialOverride(KEY_NOTIFICATION_ELEMENTS_MATERIAL).enabled

    private fun applyMaterialOverride(original: FloatArray, tuning: MaterialOverride): FloatArray = original.clone().apply {
        this[6] += tuning.brightness / 100f
        this[7] = (this[7] + tuning.darker / 100f).coerceAtLeast(0f)
        this[32] += tuning.refraction / 100f
        this[35] = (this[35] + tuning.burn / 100f).coerceAtLeast(0f)
        this[5] += tuning.saturation / 100f
        this[14] = (this[14] + tuning.alpha / 100f).coerceAtLeast(0f)
        this[21] += tuning.edgeThickness / 100f
        this[24] += tuning.reflection / 100f
        this[28] += tuning.directionalLight / 100f
        this[33] += tuning.backgroundSaturation / 100f
        this[34] += tuning.backgroundBrightness / 100f
        if (tuning.tintEnabled && tuning.tintStrength > 0) {
            val strength = tuning.tintStrength / 100f
            this[11] += Color.red(tuning.tintColor) / 255f * strength
            this[12] += Color.green(tuning.tintColor) / 255f * strength
            this[13] += Color.blue(tuning.tintColor) / 255f * strength
        }
    }

    private fun applyBackgroundTint(original: ArrayList<*>, tuning: MaterialOverride): ArrayList<Point> =
        ArrayList<Point>(original.size).also { tuned ->
            original.filterIsInstance<Point>().forEach { point ->
                val color = Color.argb(
                    (tuning.tintStrength * 255 / 100).coerceIn(0, 255),
                    Color.red(tuning.tintColor),
                    Color.green(tuning.tintColor),
                    Color.blue(tuning.tintColor),
                )
                tuned += Point(color, point.y)
            }
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
                        log(Log.INFO, TAG, "Replacing DepthAvoidEvaluator image threshold: 0.2 -> 1.0")
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
                            log(Log.INFO, TAG, "Verified DepthAvoidEvaluator image threshold: 1.0")
                        }.onFailure { error ->
                            log(Log.ERROR, TAG, "Could not verify depth image threshold", error)
                        }
                    }
                    result
                }
            installDepthAvoidanceBypass(classLoader, preferences)
            log(Log.INFO, TAG, "Installed depth limitation hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install depth image threshold hook", error)
        }
    }

    private fun installDepthAvoidanceBypass(classLoader: ClassLoader, preferences: SharedPreferences) {
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
        log(Log.INFO, TAG, "Installed time-overlap depth bypass")
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
        runCatching {
            val controllerClass = classLoader.loadClass(MIUI_SHORTCUT_CONTROLLER_CLASS)
            val shortcutMethods = controllerClass.declaredMethods
                .filter { it.name == "addShortcutViews" && it.parameterCount == 1 }
            check(shortcutMethods.isNotEmpty()) { "MiuiShortcutController.addShortcutViews was not found" }
            shortcutMethods.forEachIndexed { index, method ->
                hook(method)
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
                            log(Log.ERROR, TAG, "Could not apply lockscreen shortcut glass", error)
                        }
                        runCatching {
                            applyLockscreenShortcutGeometry(root, preferences)
                        }.onFailure { error ->
                            log(Log.ERROR, TAG, "Could not apply lockscreen shortcut geometry", error)
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
            log(Log.INFO, TAG, "Installed ${shortcutMethods.size} lockscreen shortcut glass hook(s)")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen shortcut glass hook", error)
        }
    }

    /**
     * The lockscreen editor and charge animation both sit above the normal keyguard hierarchy.
     * Their windows retain the shortcut host, so a KeyguardManager-only check briefly exposes
     * injected content while the vendor scene is transitioning.
     */
    private fun installLockscreenWidgetSceneVisibilityHooks(classLoader: ClassLoader) {
        runCatching {
            val editorClass = classLoader.loadClass(KEYGUARD_EDITOR_HELPER_CLASS)
            val stateMethods = editorClass.declaredMethods.filter {
                it.name == "setEditorState" && it.parameterCount == 1
            }
            check(stateMethods.isNotEmpty()) { "KeyguardEditorHelper.setEditorState was not found" }
            stateMethods.forEachIndexed { index, method ->
                hook(method)
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
            log(Log.INFO, TAG, "Installed lockscreen-widget editor-scene visibility hook(s)")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install lockscreen-widget editor-scene visibility hooks", error)
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
                hook(method)
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
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-widget:charging-scene-hide-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        LockscreenWidgetSceneState.setChargingActive(false)
                        result
                    }
            }
            log(Log.INFO, TAG, "Installed lockscreen-widget charging-scene visibility hook(s)")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install lockscreen-widget charging-scene visibility hooks", error)
        }
        runCatching {
            val listenerClass = classLoader.loadClass(CONTROL_CENTER_EXPAND_LISTENER_CLASS)
            val stateMethods = listenerClass.declaredMethods.filter {
                it.name == "onExpandStateChanged" && it.parameterCount == 1
            }
            check(stateMethods.isNotEmpty()) { "ControlCenter expand-state listener was not found" }
            stateMethods.forEachIndexed { index, method ->
                hook(method)
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
            log(Log.INFO, TAG, "Installed lockscreen-widget control-center visibility hook(s)")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install lockscreen-widget control-center visibility hooks", error)
        }
    }

    private fun installLockscreenPinCircleBackgroundHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
            log(Log.INFO, TAG, "Installed lockscreen PIN circle-background hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen PIN circle-background hook", error)
        }
    }

    private fun installLockscreenPinCircleBackgrounds(
        root: View,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
                    log(Log.ERROR, TAG, "Could not initialize PIN key material", error)
                }
            }
        }
        applyLockscreenPinRowSpacing(root, preferences)
        log(
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
                lyricsEnabled = {
                    preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, false)
                },
                mediaNotificationMode = { lockscreenMediaNotificationMode(preferences) },
                appearance = { miniPlayerAppearance(preferences) },
                applyPlatformMaterial = { view, appearance ->
                    runCatching {
                        applyMiniPlayerMaterial(view, appearance, classLoader)
                    }.onFailure { error ->
                        log(Log.ERROR, TAG, "Could not initialize mini player material", error)
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
                log(Log.ERROR, TAG, "Could not apply lockscreen mini player", error)
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
                            log(Log.ERROR, TAG, "Could not initialize lockscreen battery material", error)
                        }
                    },
                    applyShortcutSurfaceMaterial = { view ->
                        runCatching {
                            applyLockscreenWidgetShortcutSurfaceMaterial(view, preferences, classLoader)
                        }.onFailure { error ->
                            log(Log.ERROR, TAG, "Could not initialize lockscreen widget surface material", error)
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
                log(Log.ERROR, TAG, "Could not apply lockscreen widget", error)
            }
        }
        install()
        root.post(::install)
        root.postDelayed(::install, LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS)
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
        fun shortcutAppearance(mode: Int) = MiniPlayerAppearance(
            backgroundMode = mode,
            widthDp = width,
            heightDp = height,
            artworkCornerRadiusDp = artworkCornerRadius,
            pureColor = preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR),
            advancedColor = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
                DEFAULT_ADVANCED_MATERIAL_COLOR,
            ),
            advancedOpacity = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
                DEFAULT_ADVANCED_MATERIAL_OPACITY,
            ).coerceIn(0, 100),
            advancedBlurRadius = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
                10,
            ).coerceIn(0, 40),
            advancedHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
            softGlassColor = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, DEFAULT_SOFT_GLASS_COLOR),
            softGlassOpacity = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, DEFAULT_SOFT_GLASS_OPACITY)
                .coerceIn(0, 100),
            softGlassBackdropBlurRadius = preferences.getInt(
                KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                10,
            ).coerceIn(0, 40),
            softGlassBlurRadius = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS, 10)
                .coerceIn(0, 40),
            softGlassLuminance = preferences.getFloat(
                KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
                DEFAULT_SOFT_GLASS_LUMINANCE,
            ).coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE),
        )
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
            softGlassColor = preferences.getInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, DEFAULT_SOFT_GLASS_COLOR),
            softGlassOpacity = preferences.getInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, DEFAULT_SOFT_GLASS_OPACITY)
                .coerceIn(0, 100),
            softGlassBackdropBlurRadius = preferences.getInt(
                KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                10,
            ).coerceIn(0, 40),
            softGlassBlurRadius = preferences.getInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, 10)
                .coerceIn(0, 40),
            softGlassLuminance = preferences.getFloat(
                KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE,
                DEFAULT_SOFT_GLASS_LUMINANCE,
            ).coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE),
        )
    }

    private fun applyMiniPlayerMaterial(
        view: ImageView,
        appearance: MiniPlayerAppearance,
        classLoader: ClassLoader,
    ) {
        when (appearance.backgroundMode) {
            MINI_PLAYER_BACKGROUND_ADVANCED -> applyLegacyBackdropMaterial(
                view = view,
                opacity = appearance.advancedOpacity,
                blurRadius = appearance.advancedBlurRadius,
                color = appearance.advancedColor,
                showHighlight = appearance.advancedHighlight,
            )
            MINI_PLAYER_BACKGROUND_SOFT_GLASS -> {
                applyLegacyBackdropMaterial(
                    view = view,
                    opacity = appearance.softGlassOpacity,
                    blurRadius = appearance.softGlassBackdropBlurRadius,
                    color = appearance.softGlassColor,
                    showHighlight = false,
                )
                applySystemGlassMaterial(
                    view = view,
                    classLoader = classLoader,
                    blurRadius = appearance.softGlassBlurRadius,
                    luminance = appearance.softGlassLuminance,
                )
            }
        }
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

    private fun installShortcutGlassBackgrounds(
        root: View,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        val backgroundMode = shortcutBackgroundMode(preferences)
        val radius = preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, DEFAULT_SHORTCUT_GLASS_RADIUS)
            .coerceIn(MIN_SHORTCUT_GLASS_RADIUS, MAX_SHORTCUT_GLASS_RADIUS)
        val backgroundRadiusEnabled = preferences.getBoolean(
            KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED,
            false,
        )
        val backgroundRadius = preferences.getFloat(
            KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS,
            24f,
        ).coerceIn(0f, 60f)
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
                        setBackgroundColor(preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR))
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
            if (backgroundMode == SHORTCUT_BACKGROUND_ADVANCED_MATERIAL) {
                    runCatching {
                        applyLegacyBackdropMaterial(
                            view = glassBackground,
                            opacity = preferences.getInt(
                                KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
                                DEFAULT_ADVANCED_MATERIAL_OPACITY,
                            ).coerceIn(0, 100),
                            blurRadius = preferences.getInt(
                                KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
                                DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS,
                            ).coerceIn(0, 40),
                            color = preferences.getInt(
                                KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
                                DEFAULT_ADVANCED_MATERIAL_COLOR,
                            ),
                            showHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
                        )
                    }.onFailure { error -> log(Log.ERROR, TAG, "Could not initialize shortcut backdrop", error) }
                }
                if (backgroundMode == SHORTCUT_BACKGROUND_SOFT_GLASS) {
                    runCatching {
                        applyLegacyBackdropMaterial(
                            view = glassBackground,
                            opacity = preferences.getInt(
                                KEY_SHORTCUT_SOFT_GLASS_OPACITY,
                                DEFAULT_SOFT_GLASS_OPACITY,
                            ).coerceIn(0, 100),
                            blurRadius = preferences.getInt(
                                KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                                DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                            ).coerceIn(0, 40),
                            color = preferences.getInt(
                                KEY_SHORTCUT_SOFT_GLASS_COLOR,
                                DEFAULT_SOFT_GLASS_COLOR,
                            ),
                            showHighlight = false,
                        )
                        applySystemGlassMaterial(
                            view = glassBackground,
                            classLoader = classLoader,
                            blurRadius = preferences.getInt(
                                KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS,
                                DEFAULT_SOFT_GLASS_BLUR_RADIUS,
                            ).coerceIn(0, 40),
                            luminance = preferences.getFloat(
                                KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
                                DEFAULT_SOFT_GLASS_LUMINANCE,
                            ),
                        )
                    }.onFailure { error -> log(Log.ERROR, TAG, "Could not initialize OS4 shortcut glass", error) }
                }
            applyShortcutIconColorMode(shortcutContainer, shortcutIconColorMode(preferences))
        }
        log(
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

    private fun applyLockscreenShortcutGeometry(root: View, preferences: SharedPreferences) {
        val shortcuts = findShortcutContainers(root)
        if (shortcuts.size < 2) return
        val spacingEnabled = preferences.getBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, false)
        val spacing = (preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, 0f).coerceIn(0f, 48f) *
            root.resources.displayMetrics.density + .5f).toInt()
        val iconEnabled = preferences.getBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, false)
        val iconSize = (preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, 32f).coerceIn(16f, 64f) *
            root.resources.displayMetrics.density + .5f).toInt()
        shortcuts.forEachIndexed { index, shortcut ->
            if (spacingEnabled) {
                (shortcut.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (index == 0) params.leftMargin = spacing else params.rightMargin = spacing
                    params.bottomMargin = spacing
                    shortcut.layoutParams = params
                }
            }
            if (iconEnabled) {
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
                        params.width = iconSize
                        params.height = iconSize
                        image.layoutParams = params
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
     * Combination two has three independent surfaces, but they deliberately consume the exact
     * same preference keys and backdrop APIs as the flashlight/camera shortcut backgrounds.
     */
    private fun applyLockscreenWidgetShortcutSurfaceMaterial(
        view: View,
        preferences: SharedPreferences,
        classLoader: ClassLoader,
    ) {
        val mode = shortcutBackgroundMode(preferences)
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
                    setColor(preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR))
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
                            KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
                            DEFAULT_ADVANCED_MATERIAL_OPACITY,
                        ).coerceIn(0, 100),
                        blurRadius = preferences.getInt(
                            KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
                            DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        color = preferences.getInt(
                            KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
                            DEFAULT_ADVANCED_MATERIAL_COLOR,
                        ),
                        showHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
                    )
                } else {
                    applyLegacyBackdropMaterial(
                        view = view,
                        opacity = preferences.getInt(
                            KEY_SHORTCUT_SOFT_GLASS_OPACITY,
                            DEFAULT_SOFT_GLASS_OPACITY,
                        ).coerceIn(0, 100),
                        blurRadius = preferences.getInt(
                            KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                            DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        color = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, DEFAULT_SOFT_GLASS_COLOR),
                        showHighlight = false,
                    )
                    applySystemGlassMaterial(
                        view = view,
                        classLoader = classLoader,
                        blurRadius = preferences.getInt(
                            KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS,
                            DEFAULT_SOFT_GLASS_BLUR_RADIUS,
                        ).coerceIn(0, 40),
                        luminance = preferences.getFloat(
                            KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
                            DEFAULT_SOFT_GLASS_LUMINANCE,
                        ),
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
    ) {
        val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)
        val smallBlur = blurRadius.coerceIn(0, MAX_SHORTCUT_GLASS_BLUR_RADIUS)
        val glassParameters = SHORTCUT_GLASS_PARAMETERS.copyOf().apply {
            this[GLASS_LUMINANCE_AMOUNT_INDEX] = luminance.coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE)
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
        glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, glassParameters)
    }

    private fun shortcutBackgroundMode(preferences: SharedPreferences): Int = preferences.getInt(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE,
        if (preferences.getBoolean(KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED, false)) {
            SHORTCUT_BACKGROUND_SOFT_GLASS
        } else {
            SHORTCUT_BACKGROUND_NONE
        },
    ).coerceIn(SHORTCUT_BACKGROUND_NONE, SHORTCUT_BACKGROUND_SOFT_GLASS)

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
                    hook(method)
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
            log(Log.INFO, TAG, "Installed $hookCount lockscreen clock date-follow hook(s)")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Lockscreen clock date-follow hook unavailable", error)
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
            log(Log.WARN, TAG, "Could not install lockscreen notification shelf-space hook", error)
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
                            log(Log.ERROR, TAG, "Could not override lockscreen FOD position", error)
                        }
                        chain.proceed()
                    }
                positionHookCount += 1
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not install FOD-position hook for ${positionFlowClass.name}", error)
            }
        }
        if (shelfSpaceHookInstalled || positionHookCount > 0) {
            log(
                Log.INFO,
                TAG,
                "Installed lockscreen notification FOD hooks: shelf=$shelfSpaceHookInstalled, position=$positionHookCount",
            )
        } else {
            log(Log.ERROR, TAG, "Could not locate lockscreen notification FOD-position flow")
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

    private fun installLockscreenMediaNotificationHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            installLockscreenMediaManagerBridgeHooks(classLoader)
            installLockscreenMediaHeaderHook(classLoader, preferences)
            installLockscreenCustomizationMenuHook(classLoader)
            installLockscreenMediaVisibilityProviderHooks(classLoader, preferences)
            installLockscreenMediaPipelineHook(classLoader, preferences)
            installTinyLockscreenMediaHook(classLoader, preferences)
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
                .setId("lockscreen-hide-media-notification:keyguard")
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
                    if (onKeyguard && shouldHideLockscreenMedia(preferences) && isMediaRow(row, getEntry)) {
                        lockscreenHiddenRows += row
                        row.visibility = View.GONE
                    }
                    result
                }
            hook(setVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:visibility")
                .intercept { chain ->
                    val row = chain.thisObject as? View
                    val requested = chain.getArg(0) as? Int
                    if (row != null && requested != null && requested != View.GONE &&
                        row in lockscreenRows && shouldHideLockscreenMedia(preferences) &&
                        isMediaRow(row, getEntry)
                    ) {
                        chain.proceedWith(arrayOf(View.GONE))
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "Installed lockscreen media-notification visibility hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen media-notification visibility hook", error)
        }
    }

    private fun installLockscreenMediaManagerBridgeHooks(classLoader: ClassLoader) {
        runCatching {
            val managerClass = classLoader.loadClass("com.android.systemui.media.NotificationMediaManager")
            val methods = managerClass.declaredMethods.filter {
                (it.name == "findAndUpdateMediaNotifications" || it.name == "dispatchUpdateMediaMetaData") &&
                    it.parameterCount == 0
            }
            check(methods.isNotEmpty()) { "NotificationMediaManager media-update methods were not found" }
            methods.forEachIndexed { index, method ->
                hook(method)
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
            log(Log.INFO, TAG, "Installed NotificationMediaManager controller bridge")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install NotificationMediaManager controller bridge", error)
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
        runCatching {
            val headerClass = classLoader.loadClass(
                MIUI_MEDIA_HEADER_VIEW_CLASS,
            )
            LockscreenMediaPresentationBridge.onPresentationChanged = {
                applyLockscreenMediaPresentation(preferences)
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
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-hide-media-notification:keyguard-callback-$index")
                        .intercept { chain ->
                            val booleanIndex = method.parameterTypes.indexOfFirst {
                                it == Boolean::class.javaPrimitiveType
                            }
                            if (booleanIndex >= 0) {
                                lockscreenMediaKeyguardShowing = chain.getArg(booleanIndex) as? Boolean ?: false
                            }
                            chain.proceed()
                        }
                }
                log(Log.INFO, TAG, "Installed media keyguard callback hook(s): ${stateMethods.size}")
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not install media keyguard callback hook", error)
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
                    }
                    if (shouldHideLockscreenMedia(preferences) &&
                        headerClass.isInstance(chain.thisObject) &&
                        (lockscreenMediaKeyguardShowing || isLockscreenMediaView(chain.thisObject)) &&
                        (chain.thisObject as? View)?.visibility != View.GONE
                    ) {
                        log(Log.DEBUG, TAG, "Lockscreen media header forced GONE")
                        (chain.thisObject as? View)?.visibility = View.GONE
                    }
                    result
                }

            // Some builds update the header after the keyguard callback and do not call
            // setVisibility again. Re-apply GONE after each media-data binding as a second guard.
            val dataMethods = headerClass.declaredMethods.filter { method ->
                method.name == "onMediaDataChanged" && method.parameterCount > 0
            }
            dataMethods.forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-hide-media-notification:media-header-data-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val header = (chain.thisObject as? View)?.takeIf(headerClass::isInstance)
                        if (header != null) {
                            lockscreenMediaHeaders += header
                            installLockscreenMediaArtworkClick(header, preferences)
                        }
                        if (shouldHideLockscreenMedia(preferences) &&
                            (lockscreenMediaKeyguardShowing || isLockscreenMediaView(chain.thisObject))
                        ) {
                            (chain.thisObject as? View)?.visibility = View.GONE
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
                        installLockscreenMediaArtworkClick(header, preferences)
                        // Some holder children are attached on the next traversal.
                        header.post {
                            installLockscreenMediaArtworkClick(header, preferences)
                        }
                    }
                    result
                }
            log(
                Log.INFO,
                TAG,
                "Installed MiuiMediaHeaderView lockscreen hide hook " +
                    "(dataMethods=${dataMethods.size}, visibility=View.setVisibility, artwork=true)",
            )
            applyLockscreenMediaPresentation(preferences)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install MiuiMediaHeaderView lockscreen hide hook", error)
        }
    }

    private fun installLockscreenCustomizationMenuHook(classLoader: ClassLoader) {
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
            log(Log.INFO, TAG, "Installed lockscreen customization-menu animation hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen customization-menu hooks", error)
        }
    }

    private fun installLockscreenMediaVisibilityProviderHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
                    hook(method)
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
                log(Log.INFO, TAG, "Installed $className media visibility hook(s): ${methods.size}")
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not install $className media visibility hook", error)
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
            log(Log.INFO, TAG, "Installed lockscreen media-notification pipeline filter")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen media-notification pipeline filter", error)
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
            log(Log.INFO, TAG, "Installed tiny lockscreen media-notification hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install tiny lockscreen media-notification hooks", error)
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

    private fun shouldHideLockscreenMedia(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) && when (
            lockscreenMediaNotificationMode(preferences)
        ) {
            LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE -> true
            LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC -> LockscreenMediaPresentationBridge.showMiniPlayer
            else -> false
        }

    /** Dynamic mode must retain the notification entry so it can be shown after a card tap. */
    private fun shouldFilterLockscreenMedia(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) &&
            lockscreenMediaNotificationMode(preferences) == LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE

    private fun applyLockscreenMediaPresentation(preferences: SharedPreferences) {
        val hidden = shouldHideLockscreenMedia(preferences)
        val headers = synchronized(lockscreenMediaHeaders) { lockscreenMediaHeaders.toList() }
        headers.forEach { header ->
            if (!isMiuiMediaHeaderView(header)) {
                lockscreenMediaHeaders.remove(header)
                return@forEach
            }
            if (hidden || lockscreenMediaKeyguardShowing || isLockscreenMediaView(header)) {
                // SystemUI hosts the fingerprint and notification surfaces on separate loopers.
                // A presentation change originates from the mini player, so apply each update
                // through the target view's own queue instead of assuming the caller's thread.
                // MiuiMediaHeaderView is shared by the keyguard and notification shade.
                // Do not animate transforms on this real SystemUI view: an unlock or shade
                // rebind can detach it before the animation end action runs, leaving the
                // notification permanently scaled or transparent.
                header.post {
                    updateLockscreenMediaHeaderVisibility(header, hidden)
                }
            }
        }
        lockscreenMediaRows.toList().forEach { row ->
            if (row in lockscreenRows) {
                val visibility = if (hidden) View.GONE else View.VISIBLE
                row.post { row.visibility = visibility }
            }
        }
    }

    private fun updateLockscreenMediaHeaderVisibility(header: View, hidden: Boolean) {
        if (!isMiuiMediaHeaderView(header)) return
        // Cancel any vendor animation currently targeting this shared view and restore the
        // neutral visual state before changing visibility. This also repairs state left behind
        // by older module versions without touching the notification's own background drawable.
        header.animate().cancel()
        header.alpha = 1f
        header.scaleX = 1f
        header.scaleY = 1f
        header.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun installLockscreenMediaArtworkClick(header: View, preferences: SharedPreferences) {
        val holder = readInstanceField(header, "mediaViewHolder") ?: return
        val artworkViews = listOfNotNull(
            readInstanceField(holder, "albumImageView") as? View,
            readInstanceField(holder, "albumView") as? View,
        )
        artworkViews.forEach { artwork ->
            artwork.setOnTouchListener { _, event ->
                if (lockscreenMediaNotificationMode(preferences) != LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC) {
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        log(Log.DEBUG, TAG, "System media artwork tapped; showing mini player")
                        LockscreenMediaPresentationBridge.setShowMiniPlayer(true)
                    }
                }
                // Consume the complete gesture so the vendor click listener cannot replace the
                // presentation change after the artwork tap.
                true
            }
        }
    }

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
                hook(method)
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
            log(Log.INFO, TAG, "Installed ${displayMethods.size} lockscreen FOD icon hook(s)")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen FOD icon hook", error)
        }
    }

    private fun installSystemUiDepthHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
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
            log(Log.INFO, TAG, "Installed SystemUI lockscreen depth hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install SystemUI lockscreen depth hooks", error)
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
            log(Log.ERROR, TAG, "Could not set SystemUI depth image threshold", error)
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
            log(Log.ERROR, TAG, "Could not clear lockscreen depth avoid state", error)
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
                hook(method)
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
                hook(method)
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
            log(Log.INFO, TAG, "Installed HyperOS 4 lockscreen FOD animation hooks: icon=${iconResources.size}, recognizing=${recognizingItems.size}")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install HyperOS 4 lockscreen FOD animation hooks", error)
        }
    }

    private fun installLockscreenChargingTextHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val controllerClass = classLoader.loadClass(KEYGUARD_INDICATION_CONTROLLER_CLASS)
            val rotateField = controllerClass.getDeclaredField("mRotateTextViewController")
                .apply { isAccessible = true }
            hook(controllerClass.getMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-charging-text")
                .intercept { chain ->
                    val result = chain.proceed()
                    val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
                        if (preferences.getBoolean(KEY_HIDE_LOCKSCREEN_CHARGING_TEXT, false)) 1 else 0)
                    if (mask != 0) {
                        runCatching {
                            val controller = rotateField.get(chain.thisObject) ?: return@runCatching
                            val hide = controller.javaClass.getMethod("hideIndication", Int::class.javaPrimitiveType)
                            if (mask and LOCKSCREEN_TEXT_CHARGING != 0) hide.invoke(controller, CHARGING_INDICATION_TYPE)
                            val messages = controller.javaClass.getDeclaredField("mIndicationMessages").apply { isAccessible = true }.get(controller) as? Map<*, *>
                            messages?.forEach { (type, indication) ->
                                val text = runCatching { indication?.javaClass?.getDeclaredField("mMessage")?.apply { isAccessible = true }?.get(indication)?.toString().orEmpty() }.getOrDefault("")
                                val hideDnd = mask and LOCKSCREEN_TEXT_DND != 0 && (text.contains("勿扰") || text.contains("免打扰") || text.contains("Do not disturb", true))
                                val hideNotifications = mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0 && (text.contains("通知") && (text.contains("条") || text.contains("X") || text.any { it.isDigit() }))
                                if ((hideDnd || hideNotifications) && type is Int) hide.invoke(controller, type)
                            }
                        }.onFailure { error ->
                            log(Log.ERROR, TAG, "Could not hide lockscreen charging text", error)
                        }
                    }
                    result
                }
            log(Log.INFO, TAG, "Installed lockscreen charging-text hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install lockscreen charging-text hook", error)
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

    private fun installCornerRadiusHooks(preferences: SharedPreferences) {
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

        hook(View::class.java.getMethod("setBackground", Drawable::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("slider-background-radius")
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as View
                if (preferences.getBoolean(KEY_SLIDER_RADIUS_ENABLED, false) && isControlCenterSliderBackgroundPart(view)) {
                    val radius = dpToPixels(view, preferences.getFloat(KEY_SLIDER_RADIUS, DEFAULT_CORNER_RADIUS))
                    (view.background as? GradientDrawable)?.mutate()?.let { drawable ->
                        GradientDrawable::class.java
                            .getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                            .invoke(drawable, radius)
                    }
                }
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
        listOf(
            SLIDER_VIEW_HOLDER_CLASS,
            BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS,
            QS_ITEM_VIEW_HOLDER_CLASS,
        ).forEach { className ->
            runCatching {
                classLoader.loadClass(className).also { targetClass ->
                    installLoadedCornerRadiusHook(targetClass, preferences)
                }
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Deferred corner-radius target unavailable: $className", error)
            }
        }
    }

    /**
     * HyperOS renders the DND and notification-count indications in a dedicated view rather
     * than through KeyguardIndicationController. Hook its refresh methods and hide the concrete
     * TextViews after the vendor code has updated their state.
     */
    private fun installLockscreenBottomTextViewHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val stateClass = classLoader.loadClass(NOTIFICATION_NUM_STATE_VIEW_CLASS)
            val refreshMethods = stateClass.declaredMethods.filter {
                it.name == "updateZenViewText" ||
                    it.name == "updateNotificationCountView" ||
                    it.name == "onFinishInflate"
            }
            if (refreshMethods.isEmpty()) {
                log(Log.DEBUG, TAG, "NotificationNumStateView has no known refresh methods")
                return@runCatching
            }
            refreshMethods.forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-bottom-text-view-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        applyLockscreenBottomTextVisibility(chain.thisObject, preferences, stateClass)
                        result
                    }
            }
            // The binder posts an animation after each state update. Its completion callback
            // restores child visibility, so guard that shared animation helper as well.
            runCatching {
                val animateClass = classLoader.loadClass(NUM_STATE_VIEW_ANIMATE_EXT_CLASS)
                animateClass.declaredMethods
                    .filter { it.name == "animateUpdateViewVisibility" && it.parameterCount >= 1 }
                    .forEachIndexed { index, method ->
                        hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("lockscreen-bottom-text-animation-$index")
                            .intercept { chain ->
                                val view = chain.getArg(0) as? View
                                val owner = generateSequence(view?.parent) { it.parent }
                                    .firstOrNull { it.javaClass.name == NOTIFICATION_NUM_STATE_VIEW_CLASS }
                                val mask = preferences.getInt(
                                    KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
                                    if (preferences.getBoolean(KEY_HIDE_LOCKSCREEN_CHARGING_TEXT, false)) {
                                        LOCKSCREEN_TEXT_CHARGING
                                    } else {
                                        0
                                    },
                                )
                                val ownerView = owner as? ViewGroup
                                val countView = ownerView?.let { readViewField(it, "notificationCountView") }
                                val zenView = ownerView?.let { readViewField(it, "zenView") }
                                val divider = ownerView?.let { readViewField(it, "dividingLine") }
                                val hide = (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0 && view === countView) ||
                                    (mask and LOCKSCREEN_TEXT_DND != 0 && view === zenView) ||
                                    (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0 && view === divider)
                                if (hide && view != null) {
                                    view.visibility = View.GONE
                                    view.alpha = 0f
                                    if (view === countView) {
                                        (view as? TextView)?.text = ""
                                        (view as? TextView)?.contentDescription = ""
                                    }
                                    null
                                } else {
                                    chain.proceed()
                                }
                            }
                    }
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Optional lockscreen bottom-text animation hook unavailable", error)
            }
            log(Log.INFO, TAG, "Installed lockscreen bottom-text view hooks (${refreshMethods.size} methods)")
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Optional NotificationNumStateView hook unavailable", error)
        }
    }

    private fun applyLockscreenBottomTextVisibility(
        target: Any,
        preferences: SharedPreferences,
        targetClass: Class<*>,
    ) {
        val mask = preferences.getInt(
            KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
            if (preferences.getBoolean(KEY_HIDE_LOCKSCREEN_CHARGING_TEXT, false)) LOCKSCREEN_TEXT_CHARGING else 0,
        )
        if (mask == 0) return
        fun fieldValue(name: String): Any? {
            var type: Class<*>? = targetClass
            while (type != null) {
                val value = runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(target)
                }.getOrNull()
                if (value != null) return value
                type = type.superclass
            }
            return null
        }
        if (mask and LOCKSCREEN_TEXT_DND != 0) {
            (fieldValue("zenView") as? View)?.visibility = View.GONE
        }
        if (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0) {
            (fieldValue("notificationCountView") as? TextView)?.let {
                it.text = ""
                it.contentDescription = ""
                it.visibility = View.GONE
                it.alpha = 0f
            }
        }
        if (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0) {
            (fieldValue("dividingLine") as? View)?.visibility = View.GONE
        }
    }

    private fun readViewField(target: Any, name: String): View? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val value = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value is View) return value
            type = type.superclass
        }
        return null
    }

    /**
     * Some HyperOS builds override the glass setters on their concrete Control Center views.
     * A hook on android.view.View then misses the override dispatch, so install the same
     * material policy on declared overrides as classes are resolved by the plugin loader.
     */
    private fun installLoadedShadeMaterialHook(targetClass: Class<*>, preferences: SharedPreferences) {
        val name = targetClass.name
        if (!name.contains("controlcenter", ignoreCase = true) &&
            !name.contains("notification", ignoreCase = true)
        ) return
        if (!shadeMaterialHookedClasses.add(targetClass)) return
        targetClass.declaredMethods
            .filter { method ->
                (method.name == "setMiGlass" && method.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))) ||
                    (method.name == "setMiGlassBlurRadius" && method.parameterCount == 2 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        method.parameterTypes[1] == Int::class.javaPrimitiveType)
            }
            .forEach { method ->
                if (method.name == "setMiGlass") {
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("shade-override-glass:${name}")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            val tuning = elementMaterialOverride(
                                preferences,
                                view,
                                isControlCenterCall(),
                                isNotificationCenterCall(),
                            )
                            val original = chain.getArg(0) as? FloatArray
                            val normalNotificationMaterial = if (
                                original != null &&
                                original.size >= MIN_GLASS_PARAMS_SIZE &&
                                original.any { it != 0f } &&
                                shouldUseNormalNotificationMaterial(view, preferences)
                            ) {
                                view?.let(::normalNotificationGlassParams)
                            } else {
                                null
                            }
                            if (original != null && original.size >= MIN_GLASS_PARAMS_SIZE &&
                                (normalNotificationMaterial != null || tuning?.enabled == true)
                            ) {
                                logControlCenterMaterialHit(view, "glass-material-override")
                                val material = normalNotificationMaterial ?: original
                                chain.proceedWith(
                                    chain.thisObject,
                                    arrayOf(if (tuning?.enabled == true) applyMaterialOverride(material, tuning) else material),
                                )
                            } else {
                                chain.proceed()
                            }
                        }
                } else {
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("shade-override-glass-radius:${name}")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            val tuning = elementMaterialOverride(
                                preferences,
                                view,
                                isControlCenterCall(),
                                isNotificationCenterCall(),
                            )
                            if (tuning?.enabled == true && tuning.glassRadius > 0) {
                                logControlCenterMaterialHit(view, "glass-radius-override")
                                chain.proceedWith(
                                    chain.thisObject,
                                    arrayOf(tuning.glassRadius, tuning.glassRadius),
                                )
                            } else {
                                chain.proceed()
                            }
                        }
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
            log(Log.INFO, TAG, "Installed third-party wallpaper depth capability hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install third-party wallpaper depth hooks", error)
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

            log(Log.INFO, TAG, "Installed AOD third-party wallpaper depth hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install AOD third-party wallpaper depth hooks", error)
        }
    }

    private fun installAodLockscreenTemplateLimitHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
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
            val modelClass = classLoader.loadClass(
                "com.miui.keyguard.editor.homepage.model.CrossListDataModel",
            )
            val limitField = modelClass.getDeclaredField("_maxTemplateCount").apply {
                isAccessible = true
            }
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
                            log(Log.WARN, TAG, "Could not set AOD lockscreen template limit", error)
                        }
                        result
                    }
            }
            log(Log.INFO, TAG, "Installed AOD lockscreen template limit hook: $limit")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install AOD lockscreen template limit hook", error)
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
        installVolumeNativeParameterHooks(preferences)
        installBackgroundBlurRadiusDispatchHook(classLoader, preferences)
        runCatching {
            installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_PANEL_CONTROLLER_CLASS), preferences)
            installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_COLUMN_CLASS), preferences)
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Volume panel classes are deferred until ClassLoader discovery", error)
        }
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
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("background-blur-radius-dispatch")
                .intercept { chain ->
                    val view = chain.getArg(0) as? View
                    val materialEnabled = preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true)
                    val volumeCall = view != null && (
                        isVolumePanelSurface(view) ||
                            stackContainsClass("com.android.systemui.miui.volume.VolumePanel")
                        )
                    if (materialEnabled && volumeCall) {
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
            log(Log.INFO, TAG, "Installed background blur-radius dispatch hook")
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Background blur-radius dispatch hook unavailable", error)
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
                    if (preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
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
                    if (preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
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
                    if (preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
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
                    if (preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
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
            log(Log.INFO, TAG, "Installed volume native parameter hooks")
        }.onFailure { error ->
            volumeNativeParameterHooksInstalled = false
            log(Log.ERROR, TAG, "Could not install volume native parameter hooks", error)
        }
    }

    private fun logVolumeNativeParameterHit(name: String, view: View, value: Int) {
        val key = "$name:${view.javaClass.name}"
        if (volumeNativeParameterHookHits.add(key)) {
            log(Log.INFO, TAG, "Volume parameter hit name=$name value=$value class=${view.javaClass.name}")
        }
    }

    private fun installLoadedVolumePanelHook(targetClass: Class<*>, preferences: SharedPreferences) {
        if (!volumePanelHookedClasses.add(targetClass)) return
        when (targetClass.name) {
            VOLUME_PANEL_CONTROLLER_CLASS -> {
                targetClass.declaredMethods
                    .filter { it.name in VOLUME_PANEL_REFRESH_METHODS }
                    .forEach { method ->
                        hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("volume-panel:" + method.name + ":" + method.parameterCount)
                            .intercept { chain ->
                                val result = chain.proceed()
                                applyVolumePanelNativeTuning(chain.thisObject, preferences)
                                result
                            }
                    }
                log(Log.INFO, TAG, "Installed physical volume-panel controller hooks")
            }
            VOLUME_COLUMN_CLASS -> {
                targetClass.declaredMethods
                    .filter { it.name == "setRadius" && it.parameterTypes.size == 1 }
                    .forEach { method ->
                        hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("volume-panel-native-radius")
                            .intercept { chain ->
                                val result = chain.proceed()
                                applyVolumeColumnRadius(chain.thisObject, preferences)
                                result
                            }
                    }
                log(Log.INFO, TAG, "Installed physical volume-column hook")
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
                    hook(method)
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
            log(Log.INFO, TAG, "Installed AOSP Compose volume-panel fallback hook")
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "AOSP Compose volume dialog is unavailable", error)
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
        if (!preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true)) {
            synchronized(volumePanelAppliedTuning) {
                volumePanelAppliedTuning.remove(root)
            }
            return
        }
        val blurRadius = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120)
        val glassStrength = preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50).coerceIn(0, 100)
        val backgroundOpacity = preferences.getInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, 100).coerceIn(0, 100)
        val cornerRadius = dpToPixels(
            root,
            preferences.getFloat(KEY_VOLUME_PANEL_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
        )
        val viewCount = countViewTree(root)
        val tuning = VolumeTuningSnapshot(blurRadius, glassStrength, backgroundOpacity, cornerRadius, viewCount)
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
                    log(Log.INFO, TAG, "Volume native API class=$className methods=$api")
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
                invokeIfPresent(
                    background,
                    "setCornerRadius",
                    arrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius),
                )
            }
            if (background != null && (view === root ||
                    idName.contains("volume_dialog_content") ||
                    idName.contains("blur_frame") ||
                    idName.contains("background") ||
                    idName.contains("bg_blur"))) {
                background.alpha = (backgroundOpacity * 255 / 100).coerceIn(0, 255)
            }
            if (background != null && (idName.contains("glass") ||
                    idName.contains("bg_blur") ||
                    className.contains("VolumeBlurFrameLayout"))) {
                // The plugin already owns this glass drawable; tune its native surface opacity.
                background.alpha = (glassStrength * 255 / 100).coerceIn(0, 255)
            }
            if (view.javaClass.name == VOLUME_ROUND_RECT_CLASS) {
                invokeIfPresent(view, "setRadius", arrayOf(cornerRadius.toInt()))
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

    private fun applyVolumeColumnRadius(column: Any?, preferences: SharedPreferences) {
        if (!preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true)) return
        if (column == null) return
        val view = readField(column, "view") as? View ?: return
        val radius = dpToPixels(
            view,
            preferences.getFloat(KEY_VOLUME_PANEL_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
        )
        val radiusInt = radius.toInt()
        writeField(column, "radius", radiusInt)
        readField(column, "progressViewBg")?.let {
            invokeIfPresent(it, "setRadius", arrayOf(radiusInt))
        }
        // The slider is the foreground progress surface.  Its outline is intentionally left
        // untouched; only the volume panel/background surfaces follow the panel radius.
        listOf("view", "glassBg", "expandBg").forEach { fieldName ->
            (readField(column, fieldName) as? View)?.let { invokeMiBlurOutline(it, radius) }
        }
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
        if (!cornerTargetClasses.add(targetClass)) return
        when (targetClass.name) {
            TOP_BUTTONS_CLASS -> hookRadiusSetter(
                targetClass,
                "setCornerRadius",
                KEY_TOP_BUTTONS_RADIUS_ENABLED,
                KEY_TOP_BUTTONS_RADIUS,
                preferences,
            )
            MEDIA_PANEL_CLASS -> hookRadiusSetter(
                targetClass,
                "setCornerRadius",
                KEY_MEDIA_CARD_RADIUS_ENABLED,
                KEY_MEDIA_CARD_RADIUS,
                preferences,
            )
            DEVICE_CENTER_ENTRY -> hookDeviceCenterOutline(targetClass, preferences)
            SLIDER_VIEW_HOLDER_CLASS,
            BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS -> hookSliderRadiusSetters(targetClass, preferences)
            QS_ITEM_VIEW_HOLDER_CLASS -> hookMainBottomButtonsRadius(targetClass, preferences)
            MI_BACKGROUND_STYLE_CLASS -> hookMaterialStyle(targetClass, preferences)
            else -> cornerTargetClasses.remove(targetClass)
        }
    }

    private fun hookMainBottomButtonsRadius(
        targetClass: Class<*>,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val method = targetClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("radius:$QS_ITEM_VIEW_HOLDER_CLASS#setCornerRadius")
                .intercept { chain ->
                    if (!preferences.getBoolean(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS_ENABLED, false)) {
                        return@intercept chain.proceed()
                    }
                    val itemView = readInstanceField(chain.thisObject, "itemView") as? View
                        ?: return@intercept chain.proceed()
                    val radius = dpToPixels(
                        itemView,
                        preferences.getFloat(KEY_CONTROL_BOTTOM_BUTTONS_RADIUS, DEFAULT_CORNER_RADIUS),
                    )
                    chain.proceedWith(chain.thisObject, arrayOf(radius))
                }
            log(Log.INFO, TAG, "Installed main control-center bottom-button radius hook")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install main control-center bottom-button radius hook", error)
        }
    }

    private fun hookRadiusSetter(
        targetClass: Class<*>,
        methodName: String,
        enabledKey: String,
        radiusKey: String,
        preferences: SharedPreferences,
        applyOutline: Boolean = targetClass.name == TOP_BUTTONS_CLASS,
    ) {
        runCatching {
            val method = targetClass.getMethod(methodName, Float::class.javaPrimitiveType)
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("radius:${targetClass.name}#$methodName")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (view == null || !preferences.getBoolean(enabledKey, false)) {
                        chain.proceed()
                    } else {
                        val radius = dpToPixels(view, preferences.getFloat(radiusKey, DEFAULT_CORNER_RADIUS))
                        val result = chain.proceedWith(
                            view,
                            arrayOf(radius),
                        )
                        if (applyOutline) applyRoundedOutline(view, radius)
                        result
                    }
                }
            log(Log.INFO, TAG, "Installed corner-radius hook: ${targetClass.name}#$methodName")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install corner-radius hook: ${targetClass.name}#$methodName", error)
        }
    }

    private fun hookMaterialStyle(targetClass: Class<*>, preferences: SharedPreferences) {
        targetClass.declaredMethods
            .filter { method ->
                method.name == "setMiBackgroundStyle" &&
                    method.parameterTypes.firstOrNull() == View::class.java
            }
            .forEach { method ->
                runCatching {
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("material-style:${method.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val view = chain.getArg(0) as? View ?: return@intercept result
                            when {
                                view.javaClass.name == TOP_BUTTONS_CLASS &&
                                    preferences.getBoolean(KEY_TOP_BUTTONS_RADIUS_ENABLED, false) ->
                                    applyRoundedOutline(
                                        view,
                                        dpToPixels(view, preferences.getFloat(KEY_TOP_BUTTONS_RADIUS, DEFAULT_CORNER_RADIUS)),
                                    )
                            }
                            result
                        }
                }
            }
    }

    private fun hookDeviceCenterOutline(targetClass: Class<*>, preferences: SharedPreferences) {
        runCatching {
            val method = targetClass.getMethod("onFinishInflate")
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("radius:$DEVICE_CENTER_ENTRY#onFinishInflate")
                .intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View ?: return@intercept result
                    if (preferences.getBoolean(KEY_DEVICE_CENTER_RADIUS_ENABLED, false)) {
                        val radius = dpToPixels(view, preferences.getFloat(KEY_DEVICE_CENTER_RADIUS, DEFAULT_CORNER_RADIUS))
                        applyRoundedOutline(view, radius)
                    }
                    result
                }
            log(Log.INFO, TAG, "Installed corner-radius hook: $DEVICE_CENTER_ENTRY#onFinishInflate")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install corner-radius hook: $DEVICE_CENTER_ENTRY", error)
        }
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
        if (!preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true) &&
            name in VOLUME_PANEL_MATERIAL_DIMENSION_NAMES
        ) return null
        return when (name) {
        // These are the dimensions consumed by VolumeColumnRes and the AOSP Compose dialog.
        // They are real inputs to the vendor volume panel, so the stock blur/glass pipeline
        // remains intact while its parameters become adjustable.
        "o3_miui_cc_volume_radius",
        "o3_miui_volume_bg_radius",
        "o3_miui_volume_radius",
        "o3_miui_tiny_volume_radius",
        "miui_volume_bg_radius",
        "miui_volume_bg_radius_expanded",
        "miui_volume_blur_bg_radius",
        "volume_dialog_background_corner_radius",
        "volume_dialog_background_square_corner_radius" ->
            preferences.getFloat(KEY_VOLUME_PANEL_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0f, 60f)
        "volume_dialog_background_blur_radius" ->
            preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120).toFloat()
        "volume_dialog_background_surface_blur_radius" ->
            (preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50).coerceIn(0, 100) * 1.2f)
        "big_island_min_width" -> preferences.takeIf { it.getBoolean(KEY_ISLAND_ENABLED, false) }
            ?.getInt(KEY_ISLAND_WIDTH, 108)?.coerceIn(108, 190)?.toFloat()
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

    private fun hookSliderRadiusSetters(
        targetClass: Class<*>,
        preferences: SharedPreferences,
    ) {
        // setProgressRadius() is the foreground progress drawable radius.  Hooking it makes
        // the volume/brightness foreground pill-shaped, so only the outer slider outline is
        // customized here.
        listOf("setOutlineRadius").forEach { methodName ->
            runCatching {
                val method = targetClass.getMethod(methodName, Float::class.javaPrimitiveType)
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("radius:${targetClass.name}#$methodName")
                    .intercept { chain ->
                        val sliderView = resolveSliderView(chain.thisObject)
                        if (sliderView == null || !preferences.getBoolean(KEY_SLIDER_RADIUS_ENABLED, false)) {
                            chain.proceed()
                        } else {
                            val radius = dpToPixels(
                                sliderView,
                                preferences.getFloat(KEY_SLIDER_RADIUS, DEFAULT_CORNER_RADIUS),
                            )
                            val result = chain.proceedWith(chain.thisObject, arrayOf(radius))
                            applySliderDrawableRadius(chain.thisObject, radius)
                            result
                        }
                    }
            }.onFailure { error ->
                log(Log.DEBUG, TAG, "Slider radius method unavailable: ${targetClass.name}#$methodName", error)
            }
        }
        log(Log.INFO, TAG, "Installed expanded control-center slider radius hooks: ${targetClass.name}")
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

    private fun applySliderDrawableRadius(target: Any?, radius: Float) {
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

    private fun isVolumePanelSurface(view: View): Boolean {
        if (volumePanelSurfaceRoots.contains(view)) return true
        var current: View? = view
        while (current != null) {
            if (volumePanelSurfaceRoots.contains(current)) return true
            val className = current.javaClass.name
            if (className == VOLUME_PANEL_VIEW_CLASS ||
                className.endsWith("ExpandBlurFrameLayout") ||
                className.endsWith("VolumeBlurFrameLayout")
            ) {
                return true
            }
            val idName = runCatching {
                current.resources.getResourceEntryName(current.id)
            }.getOrNull()
            if (idName == "blur_frame" ||
                idName == "volume_column_slider" ||
                idName == "volume_column_slider_bg_glass" ||
                idName == "volume_column_slider_bg_blend"
            ) {
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
        private const val TAG = "HyperSystemUIHook"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SYSTEM_UI_PLUGIN = "miui.systemui.plugin"
        private const val AOD = "com.miui.aod"
        private const val SUPER_XIAOAI_IME = "com.xiaomi.type"
        private const val SUPER_XIAOAI_PHRASE = "com.miui.phrase"
        private const val SUBSCREEN_CENTER = "com.xiaomi.subscreencenter"
        private val SYSTEM_UI_TARGETS = setOf(SYSTEM_UI, SYSTEM_UI_PLUGIN, AOD, SUPER_XIAOAI_IME, SUPER_XIAOAI_PHRASE, SUBSCREEN_CENTER)
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
        private const val KEYGUARD_INDICATION_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.KeyguardIndicationController"
        private const val NOTIFICATION_NUM_STATE_VIEW_CLASS =
            "com.miui.systemui.notification.view.NotificationNumStateView"
        private const val NUM_STATE_VIEW_ANIMATE_EXT_CLASS =
            "com.miui.systemui.notification.ext.NumStateViewAnimateExt"
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
        private const val MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat"
        private const val CHARGING_INDICATION_TYPE = 3
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
        private const val NOTIFICATION_BACKGROUND_VIEW_CLASS =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
        private const val NOTIFICATION_ROW_GLASS_EFFECT_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassEffect"
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
        private const val SLIDER_VIEW_HOLDER_CLASS =
            "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder"
        private const val BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS =
            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"
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
        private const val CLOCK_BEAN_CLASS = "com.miui.clock.module.ClockBean"
        private const val CLOCK_BEAN_IS_COLON_SHOW_METHOD = "isColonShow"
        private const val CLOCK_EFFECT_OVERLAY = 2
        private const val CLOCK_EFFECT_GLASS = 5
        private const val DYNAMIC_ISLAND_BACKGROUND_CLASS = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"
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
        private val DYNAMIC_ISLAND_LAYOUT_CLASSES = listOf(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView",
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView",
        )
        private val SLIDER_PART_IDS = setOf("progress_bg")
        private val SHADE_BACKGROUND_IDS = setOf(
            "shade_background",
            "control_center_container",
            "notification_panel",
            "notification_shade_window_view",
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

        private const val KEY_ISLAND_ENABLED = "island_enabled"
        private const val KEY_ISLAND_WIDTH = "island_width"
        private const val KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT =
            "remove_focus_and_island_whitelist_limit"
        private const val KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED = "expanded_island_background_enabled"
        private const val KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY = "expanded_island_background_opacity"
        private const val KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS = "expanded_island_glass_blur_radius"
        private const val KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS = "expanded_island_glass_large_blur_radius"
        private const val KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS = "expanded_island_self_blur_radius"
        private const val KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT = "expanded_island_show_highlight"
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
        private const val KEY_VOLUME_PANEL_CORNER_RADIUS = "volume_panel_corner_radius"
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
        private const val KEY_HIDE_LOCKSCREEN_CHARGING_TEXT = "hide_lockscreen_charging_text"
        private const val KEY_LOCKSCREEN_BOTTOM_TEXT_MASK = "lockscreen_bottom_text_mask"
        private const val LOCKSCREEN_TEXT_CHARGING = 1
        private const val LOCKSCREEN_TEXT_DND = 2
        private const val LOCKSCREEN_TEXT_NOTIFICATIONS = 4
        private const val KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED = "lockscreen_shortcut_glass_enabled"
        private const val KEY_LOCKSCREEN_MINI_PLAYER_ENABLED = "lockscreen_mini_player_enabled"
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
        private const val KEY_HIDE_STATUS_BAR_NETWORK_TYPE = "hide_status_bar_network_type"
        private const val KEY_HIDE_STATUS_BAR_WIFI_STANDARD = "hide_status_bar_wifi_standard"
        private const val KEY_HIDE_STATUS_BAR_CLOCK_TEXT = "hide_status_bar_clock_text"
        private const val KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY = "hide_status_bar_network_activity"
        private const val KEY_MOBILE_NETWORK_TYPE_MODE = "mobile_network_type_mode"
        private const val KEY_MOBILE_NETWORK_TYPE_POSITION = "mobile_network_type_position"
        private const val KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC = "mobile_network_type_display_logic"
        private const val KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT = "mobile_network_type_custom_text"
        private const val KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A = "mobile_network_type_shrink_5ga_a"
        private const val INDEPENDENT_MOBILE_TYPE_TAG = "hyper_system_ui_hook.independent_mobile_type"
        private const val BATTERY_METER_VIEW_CLASS =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        private const val BATTERY_ICON_CLASS =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView"
        private const val BATTERY_INDICATOR_CLASS =
            "com.android.systemui.statusbar.views.BatteryIndicator"
        private const val BATTERY_HOLLOW_ICON_CLASS =
            "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView"
        private const val MODERN_STATUS_BAR_VIEW_CLASS =
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
        private const val WIFI_VIEW_BINDER_CLASS =
            "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
        private const val MOBILE_ICON_BINDER_CLASS =
            "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
        private val MOBILE_ICON_BINDER_CLASSES = arrayOf(
            MOBILE_ICON_BINDER_CLASS,
            "com.android.systemui.statusbar.pipeline.mobile.p130ui.binder.MiuiMobileIconBinder",
        )
        private val MODERN_MOBILE_VIEW_CLASSES = arrayOf(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            "com.android.systemui.statusbar.pipeline.mobile.p130ui.view.ModernStatusBarMobileView",
        )
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
        private var lockscreenClockDateFollowHookInstalled = false
        private var systemUiLockscreenClockColonHookInstalled = false
        private var fingerprintIconHookInstalled = false
        private var systemUiDepthHookInstalled = false
        private var lockscreenChargingHookInstalled = false
        private var lockscreenShortcutGlassHookInstalled = false
        private var lockscreenWidgetSceneVisibilityHookInstalled = false
        private var lockscreenPinCircleBackgroundHookInstalled = false
        private var shadeMaterialHooksInstalled = false
        private var softGlassThemeSystemUiHookInstalled = false
        private var systemUiClockMaterialLimitHookInstalled = false
        private var aodClockMaterialLimitHookInstalled = false
        private var aodLockscreenClockColonHookInstalled = false
        private var aodLockscreenTemplateLimitHookInstalled = false
        private var statusBarVisibilityHookInstalled = false
        private var stackedMobileSignalHookInstalled = false
        private var softGlassThemePluginHookInstalled = false
        private var softGlassThemePluginFallbackHookInstalled = false
        private var dynamicPluginThemeHookInstalled = false
        private var softGlassThemePluginMaterialHooksInstalled = false
        @Volatile private var themeOverrideReady = false
        private var themeActivationScheduled = false
        private var dynamicIslandHooksInstalled = false
        private var dynamicIslandClassDiscoveryInstalled = false
        private var focusIslandWhitelistSystemUiHooksInstalled = false
        private var focusIslandWhitelistPluginHooksInstalled = false
        private val focusIslandWhitelistPluginInstalling = ThreadLocal.withInitial<Boolean> { false }
        @Volatile private var lockscreenMediaKeyguardShowing = false
        private val lockscreenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenHiddenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenMediaRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val lockscreenMediaHeaders = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
        )
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
        private val fodEnrollmentFlowOverrides = WeakHashMap<Any, Any>()
        private val notificationGlassAppliedViews =
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val notificationGlassApplying = ThreadLocal<Boolean>()
        private val normalNotificationGlassParamsCache =
            WeakHashMap<Resources, MutableMap<String, FloatArray>>()
        private val controlCenterMaterialHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val focusMaterialEnforcementHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val expandedIslandMaterialSettings =
            Collections.synchronizedMap(WeakHashMap<View, Int>())
        // ClassLoader discovery callbacks can arrive concurrently while SystemUI plugins are
        // being torn down. WeakHashMap-backed sets are not safe for that path and can corrupt
        // their table, leaving the main thread stuck in WeakHashMap.put during an ANR.
        private val cornerTargetClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
        private val shadeMaterialHookedClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
        private val volumePanelHookedClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
        private val volumePanelNativeApiLogged = Collections.synchronizedSet(mutableSetOf<String>())
        private val volumeNativeParameterHookHits = Collections.synchronizedSet(mutableSetOf<String>())
        private val volumePanelSurfaceRoots = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
        )
        private val volumePanelAppliedTuning = Collections.synchronizedMap(WeakHashMap<View, VolumeTuningSnapshot>())
        private val batteryDrawableHistory =
            Collections.synchronizedMap(WeakHashMap<ImageView, Drawable>())
        private val batteryResourceRefreshSeen =
            Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        private val restoringBatteryDrawable = ThreadLocal<Boolean>()
        private val stackedMobileSignalLock = Any()
        private var stackedMobilePreferences: SharedPreferences? = null
        private var stackedMobileNetworkCallbackRegistered = false
        private var stackedMobileNetworkController: Any? = null
        private val stackedMobileSubscriptions = LinkedHashMap<Int, StackedMobileSubscription>()
        private val stackedMobileNetworkTypes = LinkedHashMap<Int, String>()
        private val stackedMobileActiveSubscriptionIds = LinkedHashSet<Int>()
        private val stackedMobilePresentations = WeakHashMap<ViewGroup, StackedMobilePresentation>()
        private val stackedMobileApplying = ThreadLocal<Boolean>()
        private val stackedMobileDualContainerId = View.generateViewId()
    }
}
