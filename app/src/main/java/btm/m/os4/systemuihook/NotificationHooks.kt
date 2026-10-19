// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.util.Log
import android.view.View
import android.widget.Toast
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the independent notification restriction, mini-window bar and resource-color hooks.
 * Package dispatch and the shared resource installation guard stay in HyperSystemUiModule.
 * Shared reflection helpers and constants are supplied without exposing the module's state.
 * The iOS shade and shared notification/material pipelines keep their existing entry points.
 */
internal class NotificationHooks(
    private val module: HyperSystemUiModule,
    private val readInstanceField: (Any, String) -> Any?,
    private val findDynamicIslandField: (Any, String) -> Field?,
    private val systemUiPackageName: String,
    private val notificationSettingsManagerClassName: String,
    private val notificationRowInjectorClassName: String,
    private val hideMiniWindowBarKey: String,
    private val logTag: String,
) {
    // Keep the original process-wide guard, call order and successful-install write point.
    fun installNotificationRestrictions(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!notificationRestrictionHooksInstalled) {
            installNotificationRestrictionHooks(classLoader, preferences)
            installNotificationMiniWindowBarHook(classLoader, preferences)
            notificationRestrictionHooksInstalled = true
        }
    }

    private fun installNotificationRestrictionHooks(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val suppressedToasts = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
            val makeText = Toast::class.java.getMethod(
                "makeText",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(makeText)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-limits:remove-ble-unlock-toast-create")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (preferences.getBoolean(KEY_REMOVE_BLE_UNLOCK_TOAST, false)) {
                        val context = chain.getArg(0) as? Context
                        val resourceId = chain.getArg(1) as? Int
                        val resourceName = resourceId?.let { id ->
                            runCatching { context?.resources?.getResourceEntryName(id) }.getOrNull()
                        }
                        if (resourceName == "miui_keyguard_ble_unlock_succeed_msg") {
                            suppressedToasts[result] = true
                        }
                    }
                    result
                }
            module.hook(Toast::class.java.getMethod("show"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-limits:remove-ble-unlock-toast-show")
                .intercept { chain ->
                    if (preferences.getBoolean(KEY_REMOVE_BLE_UNLOCK_TOAST, false) &&
                        suppressedToasts.remove(chain.thisObject) == true
                    ) null else chain.proceed()
                }
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install BLE unlock toast hook", error)
        }

        runCatching {
            val visibilityProvider = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl",
            )
            visibilityProvider.declaredMethods
                .filter { it.name == "shouldHideNotification" && it.parameterCount in 1..2 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:keep:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_KEEP_NOTIFICATIONS, false)) {
                                val entry = chain.getArg(0)
                                val notification = entry?.let { readInstanceField(it, "mSbn") }
                                notification?.let { target ->
                                    findDynamicIslandField(target, "mHasShownAfterUnlock")
                                        ?.setBoolean(target, false)
                                }
                            }
                            chain.proceed()
                        }
                }
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install keep-notifications hook", error)
        }

        runCatching {
            val expanded = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.ExpandedNotification",
            )
            expanded.declaredMethods
                .filter { it.name == "canShowOnKeyguard" && it.parameterCount == 0 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:lockscreen-entry:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_ALLOW_ALL_NOTIFICATIONS_ON_LOCKSCREEN, false)) {
                                true
                            } else chain.proceed()
                        }
                }
            listOf("canFloat", "isEnableFloat").forEach { name ->
                expanded.declaredMethods
                    .filter { it.name == name && it.parameterCount == 0 }
                    .forEachIndexed { index, method ->
                        module.hookDeoptimized(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("notification-limits:heads-up-entry:$name:$index")
                            .intercept { chain ->
                                if (preferences.getBoolean(KEY_FORCE_ALL_NOTIFICATIONS_HEADS_UP, false)) {
                                    true
                                } else chain.proceed()
                            }
                    }
            }

            val settings = classLoader.loadClass(notificationSettingsManagerClassName)
            settings.declaredMethods
                .filter { it.name == "canShowOnKeyguard" && it.parameterCount == 3 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:lockscreen-settings:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_ALLOW_ALL_NOTIFICATIONS_ON_LOCKSCREEN, false)) {
                                true
                            } else chain.proceed()
                        }
                }
            settings.declaredMethods
                .filter { it.name == "canFloat" && it.parameterCount == 3 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:heads-up-settings:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_FORCE_ALL_NOTIFICATIONS_HEADS_UP, false)) {
                                true
                            } else chain.proceed()
                        }
                }
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install notification visibility hooks", error)
        }

        runCatching {
            val listener = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.MiuiNotificationListener",
            )
            listener.declaredMethods
                .filter { it.name == "onSilentStatusBarIconsVisibilityChanged" && it.parameterCount == 1 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:importance-icons:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_REMOVE_NOTIFICATION_IMPORTANCE_LIMIT, false)) {
                                chain.proceedWith(chain.thisObject, arrayOf(false))
                            } else chain.proceed()
                        }
                }
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install notification-importance icon hook", error)
        }

        runCatching {
            val foldCoordinator = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator",
            )
            foldCoordinator.declaredMethods
                .filter { it.name == "attach" && it.parameterCount == 1 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:disable-fold-coordinator:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_DISABLE_NOTIFICATION_HISTORY_FOLDING, false)) {
                                null
                            } else chain.proceed()
                        }
                }
            val utility = classLoader.loadClass("com.miui.systemui.notification.MiuiBaseNotifUtil")
            utility.declaredMethods
                .filter { it.name == "shouldSuppressFold" && it.parameterCount == 0 }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-limits:suppress-fold:$index")
                        .intercept { chain ->
                            if (preferences.getBoolean(KEY_DISABLE_NOTIFICATION_HISTORY_FOLDING, false)) {
                                true
                            } else chain.proceed()
                        }
                }
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install notification-history folding hooks", error)
        }
        module.log(Log.INFO, logTag, "Installed notification restriction-removal hooks")
    }

    private fun installNotificationMiniWindowBarHook(
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        runCatching {
            val injectorClass = classLoader.loadClass(notificationRowInjectorClassName)
            val methods = injectorClass.declaredMethods.filter {
                it.name == "updateMiniWindowBar" && it.parameterCount == 0
            }
            check(methods.isNotEmpty()) { "$notificationRowInjectorClassName#updateMiniWindowBar not found" }
            methods.forEachIndexed { index, method ->
                method.isAccessible = true
                module.hookDeoptimized(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("notification-mini-window-bar-hide:$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (preferences.getBoolean(hideMiniWindowBarKey, false)) {
                            runCatching {
                                val getMiniBar = injectorClass.getDeclaredMethod("getMiniBar").apply { isAccessible = true }
                                (getMiniBar.invoke(chain.thisObject) as? View)?.visibility = View.GONE
                            }
                        }
                        result
                    }
            }
            module.log(Log.INFO, logTag, "Installed notification mini-window bar hide hook")
        }.onFailure { error ->
            module.log(Log.WARN, logTag, "Could not install notification mini-window bar hide hook", error)
        }
    }

    fun installNotificationColorHooks(preferences: SharedPreferences) {
        val methods = listOfNotNull(
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching {
                Resources::class.java.getMethod(
                    "getColor",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java,
                )
            }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching {
                Resources::class.java.getMethod(
                    "getColorStateList",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java,
                )
            }.getOrNull(),
        )
        methods.forEachIndexed { index, method ->
            module.hookDeoptimized(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("heads-up-notification-color-$index")
                .intercept { chain ->
                    val resources = chain.thisObject as? Resources
                    val resourceId = chain.getArg(0) as? Int
                    if (resources != null && resourceId != null &&
                        preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&
                        isHeadsUpNotificationColorResource(resources, resourceId)
                    ) {
                        if (method.name == "getColorStateList") {
                            ColorStateList.valueOf(HEADS_UP_NOTIFICATION_TEXT_COLOR)
                        } else {
                            HEADS_UP_NOTIFICATION_TEXT_COLOR
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun isHeadsUpNotificationColorResource(resources: Resources, resourceId: Int): Boolean =
        runCatching {
            resources.getResourcePackageName(resourceId) == systemUiPackageName &&
                resourceId != 0 &&
                resourceId.let { resources.getResourceEntryName(it) } in HEADS_UP_NOTIFICATION_COLOR_NAMES
        }.getOrDefault(false)

    companion object {
        private var notificationRestrictionHooksInstalled = false
        private const val HEADS_UP_NOTIFICATION_TEXT_COLOR = 0xE8FFFFFF.toInt()
        private val HEADS_UP_NOTIFICATION_COLOR_NAMES = setOf(
            "notification_action_text_color",
            "notification_time_color",
            "optimized_heads_up_notification_text",
            "notification_primary_text_color_light",
            "notification_secondary_text_color_light",
            "optimized_heads_up_notification_action_text",
        )
    }
}
