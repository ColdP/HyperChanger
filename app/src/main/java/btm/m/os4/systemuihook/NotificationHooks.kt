// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns independent notification restrictions, mini-window bar, text and flashlight hooks.
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

    private data class TextRoot(val wrapper: WeakReference<Any>, val row: WeakReference<View>)
    private data class TextColors(val original: ColorStateList, val applied: ColorStateList)
    private val textRoots = Collections.synchronizedMap(WeakHashMap<View, TextRoot>())
    private val textColors = Collections.synchronizedMap(WeakHashMap<TextView, TextColors>())
    private var textHooksInstalled = false
    private var textPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var darkIntensity = 0f
    private val flashlightClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    private fun forEachText(view: View, action: (TextView) -> Unit) {
        if (view is TextView) action(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) forEachText(view.getChildAt(index), action)
    }

    private fun restoreText(root: View) = forEachText(root) { text ->
        textColors.remove(text)?.let { colors ->
            // A RemoteViews rebind may already have installed a newer system color.
            if (text.textColors === colors.applied) text.setTextColor(colors.original)
        }
    }

    private fun applyText(root: View, preferences: SharedPreferences) {
        val owner = textRoots[root] ?: return
        val row = owner.row.get()
        val wrapper = owner.wrapper.get()
        val content = generateSequence(root.parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name.endsWith(".NotificationContentView") }
        val headsUpChild = content?.let { readInstanceField(it, "mHeadsUpChild") }
        val rowHeadsUp = row?.let {
            runCatching { it.javaClass.getMethod("isHeadsUpState").invoke(it) as? Boolean }.getOrNull()
                ?: (readInstanceField(it, "mIsHeadsUp") as? Boolean)
        } == true
        val headsUp = rowHeadsUp && (headsUpChild === root ||
            wrapper?.let { readInstanceField(it, "mIsHeadsUpWrapper") } == true)
        val settings = readNotificationTextColorSettings(preferences)
        val enabled = preferences.getBoolean(
            if (headsUp) KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS else KEY_UNIFY_NOTIFICATION_MATERIAL, false,
        )
        val night = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = if (enabled) notificationTextColor(
            if (headsUp) settings.headsUp else settings.notification, headsUp, night, darkIntensity,
        ) else null
        if (color == null) { restoreText(root); return }
        val applied = ColorStateList.valueOf(color)
        forEachText(root) { text ->
            val previous = textColors[text]
            if (previous != null && text.textColors === previous.applied && previous.applied.defaultColor == color) return@forEachText
            val original = if (previous != null && text.textColors === previous.applied) previous.original else text.textColors
            textColors[text] = TextColors(original, applied)
            text.setTextColor(applied)
        }
    }

    private fun trackWrapper(wrapper: Any, rowHint: View?, preferences: SharedPreferences) {
        val root = (readInstanceField(wrapper, "mView") as? View) ?: (wrapper as? View) ?: return
        val row = (readInstanceField(wrapper, "mRow") as? View) ?: rowHint ?:
            generateSequence(root.parent as? View) { it.parent as? View }
                .firstOrNull { it.javaClass.name.endsWith(".ExpandableNotificationRow") }
        textRoots[root] = TextRoot(WeakReference(wrapper), WeakReference(row))
        applyText(root, preferences)
    }

    private fun refreshText(preferences: SharedPreferences) {
        val roots = synchronized(textRoots) { textRoots.keys.toList() }
        roots.forEach { root -> root.post { applyText(root, preferences) } }
    }

    fun installNotificationTextHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (textHooksInstalled) return
        textHooksInstalled = true
        textPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(KEY_HEADS_UP_TEXT_COLOR, KEY_NOTIFICATION_TEXT_COLOR,
                    KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, KEY_UNIFY_NOTIFICATION_MATERIAL)) refreshText(preferences)
        }.also(preferences::registerOnSharedPreferenceChangeListener)
        val wrapperNames = listOf(
            "MiuiNotificationBigTextViewWrapper", "MiuiNotificationTemplateViewWrapper", "MiuiNotificationCustomViewWrapper",
            "NotificationViewWrapper", "NotificationHeaderViewWrapper", "NotificationTemplateViewWrapper",
            "NotificationBigTextTemplateViewWrapper", "NotificationBigPictureTemplateViewWrapper",
            "NotificationMessagingTemplateViewWrapper", "NotificationConversationTemplateViewWrapper",
            "NotificationCallTemplateViewWrapper", "NotificationCompactHeadsUpTemplateViewWrapper",
            "NotificationCompactMessagingTemplateViewWrapper", "NotificationProgressTemplateViewWrapper",
            "NotificationMetricTemplateViewWrapper", "NotificationDecoratedCustomViewWrapper",
        ).map { "com.android.systemui.statusbar.notification.row.wrapper.$it" } +
            "com.android.systemui.statusbar.notification.row.HybridNotificationView"
        wrapperNames.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.declaredMethods
                ?.filter { it.name in setOf("onContentUpdated", "updateTransparentBgAndTextColor", "bind", "onFinishInflate", "onReinflated") }
                ?.forEachIndexed { index, method ->
                    module.hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-text:$name:${method.name}:$index").intercept { chain ->
                            val owner = chain.thisObject
                            val root = owner?.let { readInstanceField(it, "mView") as? View ?: it as? View }
                            root?.let(::restoreText)
                            val result = chain.proceed()
                            if (owner != null) {
                                val row = (0 until method.parameterCount).map { chain.getArg(it) }
                                    .firstOrNull { it is View && it.javaClass.name.endsWith(".ExpandableNotificationRow") } as? View
                                trackWrapper(owner, row, preferences)
                                // MIUI also posts template work after onContentUpdated.
                                root?.post { trackWrapper(owner, row, preferences) }
                            }
                            result
                        }
                }
        }
        runCatching {
            val content = classLoader.loadClass("com.android.systemui.statusbar.notification.row.NotificationContentView")
            content.declaredMethods.filter { it.name in setOf("setHeadsUpChild", "selectLayout", "onConfigurationChanged") }
                .forEachIndexed { index, method ->
                    module.hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("notification-text-content:${method.name}:$index").intercept { chain ->
                            val result = chain.proceed()
                            val owner = chain.thisObject
                            if (owner != null) {
                                val row = readInstanceField(owner, "mContainingNotification") as? View
                                listOf("mContractedWrapper", "mExpandedWrapper", "mHeadsUpWrapper", "mSingleLineView").forEach { field ->
                                    readInstanceField(owner, field)?.let { trackWrapper(it, row, preferences) }
                                }
                            }
                            result
                        }
                }
        }.onFailure { module.log(Log.WARN, logTag, "Could not install notification text lifecycle hooks", it) }
        runCatching {
            val dispatcher = classLoader.loadClass("com.android.systemui.statusbar.phone.DarkIconDispatcherImpl")
            val method = dispatcher.getDeclaredMethod("applyDarkIntensity", Float::class.javaPrimitiveType)
            module.hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("notification-text-status-bar").intercept { chain ->
                    val result = chain.proceed()
                    val intensity = chain.getArg(0) as? Float ?: darkIntensity
                    val changed = (intensity >= .5f) != (darkIntensity >= .5f)
                    darkIntensity = intensity
                    if (changed) refreshText(preferences)
                    result
                }
        }.onFailure { module.log(Log.WARN, logTag, "Could not observe status-bar text color", it) }
    }

    fun installFlashlightMaterialHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching { classLoader.loadClass(FLASHLIGHT_NOTIFICATION_CLASS) }.getOrNull()?.let { onClassLoaded(it, preferences) }
    }

    fun onClassLoaded(target: Class<*>, preferences: SharedPreferences) {
        if (target.name != FLASHLIGHT_NOTIFICATION_CLASS) return
        synchronized(flashlightClasses) {
            if (target in flashlightClasses) return
            runCatching {
                val method = target.getDeclaredMethod("getExtraMiuiFocusParam", Context::class.java)
                module.hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("notification-flashlight-material").intercept { chain ->
                        val result = chain.proceed()
                        if (result is String && preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)) {
                            runCatching { unifyFlashlightNotificationPayload(result) }.getOrDefault(result)
                        } else result
                    }
                flashlightClasses.add(target)
            }.onFailure { module.log(Log.WARN, logTag, "Could not unify flashlight notification material", it) }
        }
    }

    companion object {
        private var notificationRestrictionHooksInstalled = false
        private const val FLASHLIGHT_NOTIFICATION_CLASS = "miui.systemui.flashlight.NotificationManager"
    }
}
