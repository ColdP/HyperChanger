// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * Owns the lockscreen charging, DND and notification-count indication hooks.
 * Package dispatch and installation timing remain in HyperSystemUiModule.
 */
internal class LockscreenHooks(
    private val module: HyperSystemUiModule,
    private val logTag: String,
) {
    private val shortcutGestures = LockscreenShortcutGestureHooks(module, logTag)

    fun installShortcutGestureHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        shortcutGestures.install(classLoader, preferences)
    }

    // Preserve the process-wide guard and its write point after both install attempts.
    fun installBottomTextHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN)) return
        if (!lockscreenChargingHookInstalled) {
            installLockscreenChargingTextHook(classLoader, preferences)
            installLockscreenBottomTextViewHook(classLoader, preferences)
            lockscreenChargingHookInstalled = true
        }
    }

    private fun installLockscreenChargingTextHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val controllerClass = classLoader.loadClass(KEYGUARD_INDICATION_CONTROLLER_CLASS)
            val rotateField = controllerClass.getDeclaredField("mRotateTextViewController")
                .apply { isAccessible = true }
            module.hook(controllerClass.getMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType))
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
                            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not hide lockscreen charging text", error)
                        }
                    }
                    result
                }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen charging-text hook")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, logTag, "Could not install lockscreen charging-text hook", error)
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
                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.DEBUG, logTag, "NotificationNumStateView has no known refresh methods")
                return@runCatching
            }
            refreshMethods.forEachIndexed { index, method ->
                module.hookDeoptimized(method)
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
                        module.hookDeoptimized(method)
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
                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.DEBUG, logTag, "Optional lockscreen bottom-text animation hook unavailable", error)
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed lockscreen bottom-text view hooks (${refreshMethods.size} methods)")
        }.onFailure { error ->
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.DEBUG, logTag, "Optional NotificationNumStateView hook unavailable", error)
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

    companion object {
        private const val KEYGUARD_INDICATION_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.KeyguardIndicationController"
        private const val NOTIFICATION_NUM_STATE_VIEW_CLASS =
            "com.miui.systemui.notification.view.NotificationNumStateView"
        private const val NUM_STATE_VIEW_ANIMATE_EXT_CLASS =
            "com.miui.systemui.notification.ext.NumStateViewAnimateExt"
        private const val CHARGING_INDICATION_TYPE = 3
        private const val KEY_HIDE_LOCKSCREEN_CHARGING_TEXT = "hide_lockscreen_charging_text"
        private const val KEY_LOCKSCREEN_BOTTOM_TEXT_MASK = "lockscreen_bottom_text_mask"
        private const val LOCKSCREEN_TEXT_CHARGING = 1
        private const val LOCKSCREEN_TEXT_DND = 2
        private const val LOCKSCREEN_TEXT_NOTIFICATIONS = 4
        private var lockscreenChargingHookInstalled = false
    }
}
