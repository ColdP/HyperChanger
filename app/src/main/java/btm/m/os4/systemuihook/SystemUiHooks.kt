// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Owns independent dynamic-island appearance, mini-bar, layout and bottom-glow hooks.
 * Dispatch, the shared install guard, material/media chains and field lookup remain
 * with their original owners; the module delegates at the original install positions.
 */
internal class SystemUiHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val findDynamicIslandField: (Any, String) -> Field?,
    private val systemUiPluginPackageName: String,
    private val dynamicIslandBaseContentClass: String,
    private val logTag: String,
) {
    private val islandSimulationGlowBridgeClasses = mutableSetOf<Class<*>>()

    /** The vendor's new island Bundle drops the big-island effect requested by our simulator. */
    fun installIslandSimulationGlowBridge(classLoader: ClassLoader) {
        runCatching {
            val controller = classLoader.loadClass("miui.systemui.notification.focus.FocusNotificationController")
            synchronized(islandSimulationGlowBridgeClasses) {
                if (controller in islandSimulationGlowBridgeClasses) return
                val method = controller.getDeclaredMethod("setUpDynamicIslandDataBundle",
                    android.service.notification.StatusBarNotification::class.java).apply { isAccessible = true }
                check(method.returnType == android.os.Bundle::class.java)
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("island-simulation:big-glow-bridge").intercept { chain ->
                        val result = chain.proceed()
                        val sbn = chain.getArg(0) as? android.service.notification.StatusBarNotification
                            ?: return@intercept result
                        if (sbn.packageName != BuildConfig.APPLICATION_ID || sbn.tag != ISLAND_SIMULATION_TAG) {
                            return@intercept result
                        }
                        val target = result as? android.os.Bundle ?: return@intercept result
                        val source = sbn.notification.extras
                        if (shouldBridgeIslandSimulationGlow(sbn.packageName, sbn.tag, sbn.id,
                                source.getString(ISLAND_SIMULATION_TYPE_EXTRA), source.getString(ISLAND_SIMULATION_BIG_GLOW_EXTRA))) {
                            target.putString(ISLAND_SIMULATION_BIG_GLOW_EXTRA, ISLAND_SIMULATION_OUTER_GLOW_EFFECT)
                        }
                        result
                    }
                islandSimulationGlowBridgeClasses.add(controller)
            }
            module.log(Log.INFO, logTag, "Installed simulator big-island glow extras bridge")
        }.onFailure { error -> module.log(Log.WARN, logTag, "Could not install simulator big-island glow extras bridge", error) }
    }

    /** Applies the optional island corner controls after the vendor has created its views. */
    fun installDynamicIslandAppearanceHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val baseContent = classLoader.loadClass(dynamicIslandBaseContentClass)
            baseContent.declaredMethods.filter {
                it.name == "updateBackgroundBg" && it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java
            }.forEachIndexed { index, method ->
                method.isAccessible = true
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-island-expanded-corner-material:$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val view = chain.getArg(0) as? View
                        val radiusDp = preferences.readExpandedIslandCornerRadius()
                        if (preferences.getBoolean(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED, false) &&
                            radiusDp != 0f && view != null && isExpandedIslandCornerTarget(view)
                        ) {
                            view.outlineProvider?.let { provider ->
                                if (provider !is ExpandedIslandCornerProvider) {
                                    view.outlineProvider = ExpandedIslandCornerProvider(provider, preferences)
                                }
                            }
                            view.invalidateOutline()
                        } else if (view != null) {
                            (view.outlineProvider as? ExpandedIslandCornerProvider)?.let { wrapped ->
                                view.outlineProvider = wrapped.original
                                view.invalidateOutline()
                            }
                        }
                        result
                    }
            }
        }.onFailure { error -> module.log(Log.DEBUG, logTag, "Expanded island material corner hook unavailable", error) }

        runCatching {
            val holder = classLoader.loadClass("miui.systemui.dynamicisland.module.IslandIconViewHolder")
            holder.declaredMethods.filter { it.name == "setSmallIcon" && it.parameterCount == 1 }.forEachIndexed { index, method ->
                method.isAccessible = true
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-island-small-media-icon-corner:$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (preferences.getBoolean(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_ENABLED, false)) {
                            val picInfo = runCatching {
                                holder.getMethod("getPicInfo").invoke(chain.thisObject)
                            }.getOrNull()
                            val picture = runCatching {
                                picInfo?.javaClass?.getMethod("getPic")?.invoke(picInfo) as? String
                            }.getOrNull()
                            if (picture != "miui_media_album_icon") return@intercept result
                            val icon = findDynamicIslandField(chain.thisObject, "smallIcon")?.get(chain.thisObject) as? View
                            icon?.let { applySmallMediaIconCorner(it, preferences) }
                        }
                        result
                    }
            }
        }.onFailure { error -> module.log(Log.DEBUG, logTag, "Small island media icon corner hook unavailable", error) }
    }

    private inner class ExpandedIslandCornerProvider(
        internal val original: ViewOutlineProvider,
        private val preferences: SharedPreferences,
    ) : ViewOutlineProvider() {
        private var baseRadiusPx: Float? = null

        override fun getOutline(view: View, outline: Outline) {
            original.getOutline(view, outline)
            if (!preferences.getBoolean(KEY_EXPANDED_ISLAND_CORNER_RADIUS_ENABLED, false)) return
            val offsetDp = preferences.readExpandedIslandCornerRadius()
            if (offsetDp == 0f) return
            val rect = Rect()
            if (outline.getRect(rect)) {
                val density = view.resources.displayMetrics.density
                val baseRadius = baseRadiusPx ?: view.resources.getIdentifier("island_radius", "dimen", systemUiPluginPackageName)
                    .takeIf { it != 0 }
                    ?.let { runCatching { view.resources.getDimension(it) }.getOrNull() }
                    ?.also { baseRadiusPx = it }
                    ?: 0f
                val radius = (baseRadius + offsetDp * density)
                    .coerceAtLeast(0f)
                    .coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
                outline.setRoundRect(rect, radius)
            }
        }
    }

    private fun isExpandedIslandCornerTarget(view: View): Boolean {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        return idName == "expanded_view" ||
            idName == "fake_expanded_view" ||
            view.javaClass.name == DYNAMIC_ISLAND_EXPANDED_VIEW_CLASS
    }

    private fun applySmallMediaIconCorner(view: View, preferences: SharedPreferences) {
        val offsetDp = preferences.getInt(KEY_SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS, SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT)
            .coerceIn(-64, 96)
        val baseId = view.resources.getIdentifier("island_icon_radius", "dimen", systemUiPluginPackageName)
        val base = if (baseId != 0) view.resources.getDimension(baseId) else 4.56f * view.resources.displayMetrics.density
        if (offsetDp == 0) return
        val radius = (base + offsetDp * view.resources.displayMetrics.density).coerceAtLeast(0f)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                val max = minOf(target.width, target.height) / 2f
                outline.setRoundRect(0, 0, target.width, target.height, radius.coerceAtMost(max))
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
    }

    /**
     * DynamicIslandBaseContentView normally hides the mini bar when the source
     * package is absent from SystemUI's media-island allowlist.  The module
     * option deliberately replaces that decision with the tutorial's verified
     * always-visible implementation while retaining Xiaomi's translation code.
     */
    fun installDynamicIslandMiniBarHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val baseClass = classLoader.loadClass(dynamicIslandBaseContentClass)
            val updateMethods = baseClass.declaredMethods.filter { method ->
                method.name == DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name == DYNAMIC_ISLAND_CONTENT_CLASS
            }
            check(updateMethods.isNotEmpty()) {
                "$dynamicIslandBaseContentClass#$DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD not found"
            }
            updateMethods.forEachIndexed { index, method ->
                method.isAccessible = true
                hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-island-mini-bar:$index")
                    .intercept { chain ->
                        if (!preferences.getBoolean(
                                KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT,
                                false,
                            )
                        ) {
                            return@intercept chain.proceed()
                        }

                        val target = chain.thisObject
                        val content = chain.getArg(0)
                        setDynamicIslandBooleanField(target, "hideByFullScreenPkg", false)
                        setDynamicIslandBooleanField(target, "miniBarVisible", true)
                        findDynamicIslandField(target, "miniBar")?.let { field ->
                            (field.get(target) as? View)?.setVisibility(View.VISIBLE)
                        }

                        // Keep the stock translation/positioning implementation;
                        // only the visibility decision is overridden.
                        runCatching {
                            val translate = findDynamicIslandMethod(
                                target,
                                DYNAMIC_ISLAND_UPDATE_MINI_BAR_TRANSLATION_METHOD,
                                content,
                            )
                            if (translate != null) {
                                translate.isAccessible = true
                                translate.invoke(target, content)
                            }
                        }.onFailure { error ->
                            module.log(Log.WARN, logTag, "Could not update Dynamic Island mini-bar translation", error)
                        }
                        null
                    }
            }
            module.log(Log.INFO, logTag, "Installed Dynamic Island mini-bar allowlist bypass")
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install Dynamic Island mini-bar allowlist bypass", error)
        }
    }

    private fun findDynamicIslandMethod(target: Any, name: String, argument: Any?): Method? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == 1 &&
                    (argument == null || it.parameterTypes[0].isInstance(argument))
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun setDynamicIslandBooleanField(target: Any, name: String, value: Boolean) {
        runCatching { findDynamicIslandField(target, name)?.setBoolean(target, value) }
    }

    fun installDynamicIslandLayoutHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val baseClass = classLoader.loadClass(dynamicIslandBaseContentClass)
            val reset = baseClass.getDeclaredMethod("reset").apply { isAccessible = true }
            module.hook(reset)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-island-small-width-height")
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val target = chain.thisObject
                        val smallView = baseClass.getDeclaredField("smallIslandView").apply { isAccessible = true }
                            .get(target) as? View
                        val islandHeight = baseClass.getDeclaredField("islandViewHeight").apply { isAccessible = true }
                            .getInt(target)
                        if (smallView != null && islandHeight > 0) {
                            smallView.layoutParams = smallView.layoutParams?.apply { height = islandHeight }
                            smallView.requestLayout()
                        }
                    }
                    result
                }
            module.log(Log.INFO, logTag, "Installed small-island width/height alignment hook")
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install small-island width/height alignment hook", error)
        }
        DYNAMIC_ISLAND_LAYOUT_CLASSES.forEach { className ->
            runCatching {
                val layoutClass = classLoader.loadClass(className)
                val methods = layoutClass.declaredMethods.filter {
                    it.name.startsWith("updateBigIslandLayout")
                }
                check(methods.isNotEmpty()) { "$className has no updateBigIslandLayout method" }
                methods.forEachIndexed { index, method ->
                    hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("dynamic-island-layout:${layoutClass.name}#$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val source = chain.thisObject as? View
                            result
                        }
                }
                module.log(Log.INFO, logTag, "Installed ${methods.size} big-island layout hooks for $className")
            }.onFailure { error ->
                module.log(Log.INFO, logTag, "Skipped dynamic-island layout class $className", error)
            }
        }
    }

    fun installDynamicIslandBottomGlowHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val glowClass = classLoader.loadClass(DYNAMIC_ISLAND_GLOW_EFFECT_CLASS)
            glowClass.declaredMethods.filter { it.name == "startGlowEffect\$miui_dynamicisland_release" || it.name == "setAlphaOfGlowEffect\$miui_dynamicisland_release" }.forEachIndexed { index, method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("dynamic-island-bottom-glow:$index").intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW, false) && chain.thisObject?.javaClass?.name == DYNAMIC_ISLAND_EXPANDED_VIEW_CLASS) {
                        runCatching { chain.thisObject?.javaClass?.getMethod("getMGlowEffectBottomView")?.invoke(chain.thisObject) }.getOrNull()?.let { (it as? View)?.alpha = 0f }
                    }
                    result
                }
            }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install media-island bottom glow hooks", it) }
    }

    companion object {
        private const val DYNAMIC_ISLAND_GLOW_EFFECT_CLASS =
            "miui.systemui.dynamicisland.view.DynamicGlowEffectView"
        private const val DYNAMIC_ISLAND_EXPANDED_VIEW_CLASS =
            "miui.systemui.dynamicisland.view.DynamicIslandExpandedView"
        private const val DYNAMIC_ISLAND_CONTENT_CLASS =
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
        private const val DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD = "updateMiniBar"
        private const val DYNAMIC_ISLAND_UPDATE_MINI_BAR_TRANSLATION_METHOD =
            "updateMiniBarTranslation\$miui_dynamicisland_release"
        private val DYNAMIC_ISLAND_LAYOUT_CLASSES = listOf(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView",
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView",
        )
        private const val KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT =
            "remove_dynamic_island_media_mini_bar_whitelist_limit"
        private const val KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW = "disable_media_island_bottom_glow"
    }
}
