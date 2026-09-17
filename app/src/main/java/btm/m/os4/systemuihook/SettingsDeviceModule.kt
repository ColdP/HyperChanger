// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.app.Activity
import android.app.NotificationChannel
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Proxy

/** Hooks only Settings' presentation models; no system property is written. */
class SettingsDeviceModule : XposedModule() {
    private var settingsApplicationContext: Context? = null

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!OsCompatibility.areHooksAllowed()) return
        if (param.packageName != SETTINGS_PACKAGE) return
        settingsApplicationContext = currentApplicationContext()
        val preferences = getRemotePreferences(DEVICE_PROFILE_PREFERENCES)
        val hookPreferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val appearance = getRemotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        runCatching {
            if (hookPreferences.getBoolean(KEY_UNLOCK_NEVER_SCREEN_TIMEOUT, false)) {
                installNeverScreenTimeoutHooks(param.defaultClassLoader)
            }
            if (hookPreferences.getBoolean(KEY_SHOW_GOOGLE_SERVICE_ENTRY, false)) {
                installGoogleServiceEntryHook(param.defaultClassLoader)
            }
            if (hookPreferences.getBoolean(KEY_REMOVE_NOTIFICATION_IMPORTANCE_LIMIT, false)) {
                installNotificationImportanceHooks(param.defaultClassLoader)
            }
            installCardBindingHook(param.defaultClassLoader, preferences)
            installDirectDetailHooks(param.defaultClassLoader, preferences)
            installCpuIconHook(param.defaultClassLoader, preferences)
            // These methods are global framework hooks in the Settings process.
            // Install only the groups that can currently change a view. This is
            // important because Settings calls these methods during every bind.
            val homeAppearance = appearance.getBoolean("home_enabled", false)
            val deviceBackground = appearance.getBoolean("device_enabled", false)
            val customDeviceInterface = appearance.getBoolean("tutorial_card_enabled", false) ||
                appearance.getInt("device_interface_style", DEVICE_INTERFACE_STYLE_SYSTEM) != DEVICE_INTERFACE_STYLE_SYSTEM
            val deviceAppearance = deviceBackground || customDeviceInterface
            val logoAppearance = appearance.getInt("logo_mode", LOGO_MODE_SYSTEM) != LOGO_MODE_SYSTEM
            val lightCards = appearance.getInt("light_card_opacity", 100) < 100
            val forcedText = appearance.getInt("home_font", 0) != 0 || appearance.getInt("device_font", 0) != 0
            if (homeAppearance || deviceAppearance) {
                installAppearanceHooks(param.defaultClassLoader, homeAppearance, deviceAppearance)
            }
            if (logoAppearance) {
                installPersistentLogoHooks()
                installLogoResourceHooks()
            }
            if (lightCards) {
                installCardColorResourceHooks()
                installCardFinalBackgroundHooks()
                installCardMaterialHooks()
            }
            if (forcedText) installPersistentTextColorHooks()
            log(Log.INFO, TAG, "Installed Settings device-profile hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install Settings device-profile hooks", error)
        }
    }

    private fun installNeverScreenTimeoutHooks(classLoader: ClassLoader) {
        runCatching {
            val type = classLoader.loadClass("com.android.settings.KeyguardTimeoutDropDownPreference")
            type.declaredMethods.firstOrNull { it.name == "disableUnusableTimeouts" && it.parameterCount == 0 }
                ?.let { method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-screen-timeout:unlock-never-filter")
                        .intercept { chain ->
                            val preference = chain.thisObject
                            val context = fieldContext(preference) ?: settingsApplicationContext
                            val wasNever = context?.let {
                                android.provider.Settings.System.getLong(
                                    it.contentResolver,
                                    "screen_off_timeout",
                                    DEFAULT_SCREEN_TIMEOUT,
                                ) == NEVER_SCREEN_TIMEOUT
                            } == true
                            val result = chain.proceed()
                            if (context != null && canExposeNeverScreenTimeout(context) && !isDisabledByAdmin(preference)) {
                                if (wasNever) {
                                    android.provider.Settings.System.putInt(
                                        context.contentResolver,
                                        "screen_off_timeout",
                                        NEVER_SCREEN_TIMEOUT.toInt(),
                                    )
                                }
                                appendTimeoutOptions(preference, context)
                            }
                            result
                        }
                }
            type.declaredMethods.firstOrNull { it.name == "updateTimeoutPreferenceSummary" && it.parameterCount == 0 }
                ?.let { method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-screen-timeout:preserve-never")
                        .intercept { chain ->
                            val context = fieldContext(chain.thisObject) ?: settingsApplicationContext
                            val current = context?.let {
                                android.provider.Settings.System.getLong(
                                    it.contentResolver,
                                    "screen_off_timeout",
                                    DEFAULT_SCREEN_TIMEOUT,
                                )
                            }
                            if (current == NEVER_SCREEN_TIMEOUT) null else chain.proceed()
                        }
                }
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook legacy screen timeout", error) }

        runCatching {
            val type = classLoader.loadClass("com.android.settings.display.ScreenTimeoutDialogActivity")
            type.declaredMethods.firstOrNull { it.name == "disableUnusableTimeouts" && it.parameterCount == 0 }
                ?.let { method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-screen-timeout:unlock-never-dialog")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val activity = chain.thisObject
                            val context = activity as? Context ?: settingsApplicationContext
                            if (context != null && canExposeNeverScreenTimeout(context)) {
                                appendDialogTimeoutOptions(activity, context)
                            }
                            result
                        }
                }
        }.onFailure { error -> log(Log.DEBUG, TAG, "Screen timeout dialog unavailable", error) }

        runCatching {
            val type = classLoader.loadClass("com.android.settings.display.ScreenTimeoutSettings")
            type.declaredMethods.firstOrNull { it.name == "getCandidates" && it.parameterCount == 0 }
                ?.let { method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-screen-timeout:unlock-more-candidates")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val context = fieldContext(chain.thisObject) ?: settingsApplicationContext
                            if (context != null && canExposeNeverScreenTimeout(context)) {
                                appendScreenTimeoutCandidates(result, classLoader, context)
                            } else {
                                result
                            }
                        }
                }
        }.onFailure { error -> log(Log.DEBUG, TAG, "Screen timeout candidate list unavailable", error) }

        listOf(
            "com.android.settings.display.ScreenTimeoutSettings",
            "com.android.settings.display.ScreenTimeoutPreferenceController",
        ).forEach { className ->
            runCatching {
                classLoader.loadClass(className).declaredMethods
                    .filter { it.name == "getMaxScreenTimeout" }
                    .forEachIndexed { index, method ->
                        hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .setId("settings-screen-timeout:unlock-never-${className.substringAfterLast('.')}-${index}")
                            .intercept { chain ->
                                val result = chain.proceed()
                                val context = (if (method.parameterCount > 0) chain.getArg(0) as? Context else null)
                                    ?: fieldContext(chain.thisObject)
                                    ?: settingsApplicationContext
                                if (context != null && canExposeNeverScreenTimeout(context)) {
                                    val maximum = (result as? Number)?.toLong() ?: 0L
                                    if (maximum in 1 until NEVER_SCREEN_TIMEOUT) NEVER_SCREEN_TIMEOUT else result
                                } else result
                            }
                    }
            }.onFailure { error -> log(Log.DEBUG, TAG, "Screen timeout class unavailable: $className", error) }
        }
        log(Log.INFO, TAG, "Installed extended screen timeout option hooks")
    }

    private fun installGoogleServiceEntryHook(classLoader: ClassLoader) {
        runCatching {
            val type = classLoader.loadClass(MIUI_SETTINGS)
            val updateHeaderList = allMethods(type).firstOrNull { method ->
                method.name == "updateHeaderList" &&
                    method.parameterCount == 1 &&
                    java.util.List::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return
            val addGoogleHeaders = allMethods(type).firstOrNull { method ->
                method.name == "AddGoogleSettingsHeaders" &&
                    method.parameterCount == 1 &&
                    java.util.List::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return
            addGoogleHeaders.isAccessible = true
            hook(updateHeaderList)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-google:show-service-entry")
                .intercept { chain ->
                    val result = chain.proceed()
                    val headers = chain.getArg(0) as? java.util.List<*> ?: return@intercept result
                    runCatching { addGoogleHeaders.invoke(chain.thisObject, headers) }
                        .onFailure { error ->
                            log(Log.DEBUG, TAG, "Could not restore Google service entry", error)
                        }
                    result
                }
            log(Log.INFO, TAG, "Installed Google service entry hook")
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Google service entry hook unavailable", error)
        }
    }

    private fun installNotificationImportanceHooks(classLoader: ClassLoader) {
        runCatching {
            val base = classLoader.loadClass("com.android.settings.notification.BaseNotificationSettings")
            allMethods(base)
                .filter { method ->
                    method.name == "setPrefVisible" &&
                        method.parameterCount == 2 &&
                        method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }
                .forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-notification-importance:visible:$index")
                        .intercept { chain ->
                            val preference = chain.getArg(0)
                            val key = preference?.javaClass?.methods
                                ?.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
                                ?.invoke(preference) as? String
                            if (key == "importance") {
                                chain.proceedWith(chain.thisObject, arrayOf(preference, true))
                            } else chain.proceed()
                        }
                }
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not expose notification importance preference", error)
        }

        runCatching {
            val channelSettings = classLoader.loadClass(
                "com.android.settings.notification.ChannelNotificationSettings",
            )
            allMethods(channelSettings)
                .filter { it.name == "setupChannelDefaultPrefs" && it.parameterCount == 0 }
                .forEachIndexed { index, method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-notification-importance:bind:$index")
                        .intercept { chain ->
                            val result = chain.proceed()
                            bindNotificationImportancePreference(chain.thisObject, classLoader)
                            result
                        }
                }
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not bind notification importance preference", error)
        }
        log(Log.INFO, TAG, "Installed notification importance setting hooks")
    }

    private fun bindNotificationImportancePreference(fragment: Any, classLoader: ClassLoader) {
        runCatching {
            val findPreference = allMethods(fragment.javaClass).firstOrNull {
                it.name == "findPreference" && it.parameterCount == 1
            } ?: return
            findPreference.isAccessible = true
            val preference = findPreference.invoke(fragment, "importance") ?: return
            writeField(fragment, "mImportance", preference)
            val importance = (readField(fragment, "mBackupImportance") as? Number)?.toInt() ?: return
            if (importance <= 0) return

            preference.javaClass.methods.firstOrNull {
                it.name == "findSpinnerIndexOfValue" && it.parameterCount == 1
            }?.invoke(preference, importance.toString())?.let { spinnerIndex ->
                (spinnerIndex as? Number)?.toInt()?.takeIf { it >= 0 }?.let { validIndex ->
                    preference.javaClass.methods.firstOrNull {
                        it.name == "setValueIndex" && it.parameterCount == 1
                    }?.invoke(preference, validIndex)
                }
            }

            val listenerClass = classLoader.loadClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
            )
            val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, invoked, args ->
                if (invoked.name == "onPreferenceChange") {
                    val nextImportance = args?.getOrNull(1)?.toString()?.toIntOrNull()
                        ?: return@newProxyInstance false
                    writeField(fragment, "mBackupImportance", nextImportance)
                    val channel = readField(fragment, "mChannel") as? NotificationChannel
                        ?: return@newProxyInstance false
                    channel.importance = nextImportance
                    channel.javaClass.methods.firstOrNull {
                        it.name == "lockFields" && it.parameterCount == 1
                    }?.invoke(channel, 4)
                    val backend = readField(fragment, "mBackend") ?: return@newProxyInstance false
                    val packageName = readField(fragment, "mPkg") as? String
                        ?: return@newProxyInstance false
                    val uid = (readField(fragment, "mUid") as? Number)?.toInt()
                        ?: return@newProxyInstance false
                    allMethods(backend.javaClass).firstOrNull {
                        it.name == "updateChannel" && it.parameterCount == 3
                    }?.apply { isAccessible = true }?.invoke(backend, packageName, uid, channel)
                    allMethods(fragment.javaClass).firstOrNull {
                        it.name == "updateDependents" && it.parameterCount == 1
                    }?.apply { isAccessible = true }?.invoke(fragment, false)
                }
                true
            }
            preference.javaClass.methods.firstOrNull {
                it.name == "setOnPreferenceChangeListener" && it.parameterCount == 1
            }?.invoke(preference, listener)
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Notification importance preference is unavailable", error)
        }
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
            current = current.superclass
        }
        return null
    }

    private fun writeField(instance: Any, name: String, value: Any?) {
        var current: Class<*>? = instance.javaClass
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.set(instance, value)
                return
            }
            current = current.superclass
        }
    }

    private data class TimeoutArrays(
        val entries: Array<CharSequence>,
        val values: Array<CharSequence>,
    )

    private data class TimeoutOption(
        val value: Long,
        val label: CharSequence,
    )

    private fun timeoutOptions(context: Context): List<TimeoutOption> = buildList {
        val minutePlurals = context.resources.getIdentifier(
            "string_int_minute",
            "plurals",
            context.packageName,
        )
        if (minutePlurals != 0) {
            ADDITIONAL_SCREEN_TIMEOUT_MINUTES.forEach { minutes ->
                runCatching {
                    add(
                        TimeoutOption(
                            minutes * MILLIS_PER_MINUTE,
                            context.resources.getQuantityString(minutePlurals, minutes, minutes),
                        ),
                    )
                }
            }
        }
        neverScreenTimeoutLabel(context)?.let { add(TimeoutOption(NEVER_SCREEN_TIMEOUT, it)) }
    }

    private fun buildTimeoutArrays(entries: Array<*>, values: Array<*>, context: Context): TimeoutArrays? {
        val pairCount = minOf(entries.size, values.size)
        if (pairCount == 0) return null
        val currentValues = values.take(pairCount).map { it?.toString().orEmpty() }.toHashSet()
        val additions = timeoutOptions(context).filterNot { it.value.toString() in currentValues }
        if (additions.isEmpty()) return null
        val nextEntries = ArrayList<CharSequence>(pairCount + additions.size)
        val nextValues = ArrayList<CharSequence>(pairCount + additions.size)
        for (index in 0 until pairCount) {
            val entry = entries[index] as? CharSequence ?: continue
            val value = values[index] as? CharSequence ?: continue
            if (value.toString() == NEVER_SCREEN_TIMEOUT.toString()) {
                additions.forEach {
                    nextEntries += it.label
                    nextValues += it.value.toString()
                }
            }
            nextEntries += entry
            nextValues += value
        }
        if (nextValues.none { it.toString() == NEVER_SCREEN_TIMEOUT.toString() }) {
            additions.forEach {
                nextEntries += it.label
                nextValues += it.value.toString()
            }
        }
        return TimeoutArrays(nextEntries.toTypedArray(), nextValues.toTypedArray())
    }

    private fun appendTimeoutOptions(preference: Any, context: Context) {
        runCatching {
            val values = preference.javaClass.getMethod("getEntryValues").invoke(preference) as? Array<*> ?: return
            val entries = preference.javaClass.getMethod("getEntries").invoke(preference) as? Array<*> ?: return
            val arrays = buildTimeoutArrays(entries, values, context) ?: return
            preference.javaClass.getMethod("setEntries", Array<CharSequence>::class.java)
                .invoke(preference, arrays.entries)
            preference.javaClass.getMethod("setEntryValues", Array<CharSequence>::class.java)
                .invoke(preference, arrays.values)
            val current = android.provider.Settings.System.getLong(
                context.contentResolver,
                "screen_off_timeout",
                DEFAULT_SCREEN_TIMEOUT,
            )
            if (current == NEVER_SCREEN_TIMEOUT) {
                runCatching {
                    preference.javaClass.getMethod("setValue", String::class.java)
                        .invoke(preference, NEVER_SCREEN_TIMEOUT.toString())
                }
            }
        }.onFailure { error -> log(Log.DEBUG, TAG, "Could not append screen timeout options", error) }
    }

    private fun appendDialogTimeoutOptions(activity: Any, context: Context) {
        runCatching {
            val entriesField = activity.javaClass.getDeclaredField("mEntries").apply { isAccessible = true }
            val valuesField = activity.javaClass.getDeclaredField("mEntryValues").apply { isAccessible = true }
            val entries = entriesField.get(activity) as? Array<*> ?: return
            val values = valuesField.get(activity) as? Array<*> ?: return
            val arrays = buildTimeoutArrays(entries, values, context) ?: return
            entriesField.set(activity, arrays.entries)
            valuesField.set(activity, arrays.values)
        }.onFailure { error -> log(Log.DEBUG, TAG, "Could not append dialog screen timeout options", error) }
    }

    private fun appendScreenTimeoutCandidates(result: Any?, classLoader: ClassLoader, context: Context): Any? {
        val candidates = result as? List<*> ?: return result
        val existing = candidates.mapNotNull { candidate ->
            runCatching { candidate?.javaClass?.getMethod("getKey")?.invoke(candidate)?.toString() }.getOrNull()
        }.toHashSet()
        val additions = timeoutOptions(context).filterNot { it.value.toString() in existing }
        if (additions.isEmpty()) return result
        val candidateClass = classLoader.loadClass(
            "com.android.settings.display.ScreenTimeoutSettings\$TimeoutCandidateInfo",
        )
        val constructor = candidateClass.getDeclaredConstructor(
            CharSequence::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val next = ArrayList<Any?>(candidates.size + additions.size)
        var inserted = false
        candidates.forEach { candidate ->
            val key = runCatching { candidate?.javaClass?.getMethod("getKey")?.invoke(candidate)?.toString() }.getOrNull()
            if (!inserted && key == NEVER_SCREEN_TIMEOUT.toString()) {
                additions.forEach { option ->
                    next += constructor.newInstance(option.label, option.value.toString(), true)
                }
                inserted = true
            }
            next += candidate
        }
        if (!inserted) {
            additions.forEach { option ->
                next += constructor.newInstance(option.label, option.value.toString(), true)
            }
        }
        return next
    }

    private fun fieldContext(target: Any): Context? {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { Context::class.java.isAssignableFrom(it.type) }?.let { field ->
                return runCatching { field.isAccessible = true; field.get(target) as? Context }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun canExposeNeverScreenTimeout(context: Context): Boolean {
        val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
        return devicePolicyManager == null || devicePolicyManager.getMaximumTimeToLock(null) == 0L
    }

    private fun isDisabledByAdmin(preference: Any): Boolean = runCatching {
        preference.javaClass.getMethod("isDisabledByAdmin").invoke(preference) as Boolean
    }.getOrDefault(false)

    private fun installPersistentTextColorHooks() {
        runCatching {
            listOf(
                TextView::class.java.getMethod("setTextColor", Int::class.javaPrimitiveType),
                TextView::class.java.getMethod("setTextColor", ColorStateList::class.java),
            ).forEachIndexed { index, method ->
                hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:text-color-$index")
                .intercept { chain ->
                    val view = chain.thisObject as? TextView
                        ?: return@intercept chain.proceed()
                    val isStateList = method.parameterTypes[0] == ColorStateList::class.java
                    val original = chain.getArg(0)
                    val replacement = SettingsAppearanceApplier.overrideTextColor(view, original, isStateList)
                    if (replacement === original) chain.proceed()
                    else chain.proceedWith(chain.thisObject, arrayOf(replacement))
                }
            }
            log(Log.INFO, TAG, "Installed persistent Settings text-color hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not hook Settings text colors", error)
        }
    }

    private fun neverScreenTimeoutLabel(context: Context): CharSequence? = runCatching {
        context.resources.getIdentifier("string_never", "string", context.packageName)
            .takeIf { it != 0 }
            ?.let(context::getString)
    }.getOrNull()

    private fun installCardFinalBackgroundHooks() {
        runCatching {
            hook(View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-background-color-final")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val color = chain.getArg(0) as? Int
                    val replacement = if (view != null && color != null) {
                        SettingsAppearanceApplier.cardFinalColorReplacement(view, color)
                    } else null
                    if (replacement == null) chain.proceed()
                    else chain.proceedWith(chain.thisObject, arrayOf(replacement))
                }

            hook(View::class.java.getMethod("setBackgroundTintList", ColorStateList::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-background-tint-final")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val list = chain.getArg(0) as? ColorStateList
                    val replacement = if (view != null && list != null) {
                        SettingsAppearanceApplier.cardFinalStateListReplacement(view, list)
                    } else null
                    if (replacement == null) chain.proceed()
                    else chain.proceedWith(chain.thisObject, arrayOf(replacement))
                }

            hook(View::class.java.getMethod("setBackground", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-background-final")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val drawable = chain.getArg(0) as? Drawable
                    if (view != null && drawable != null) {
                        SettingsAppearanceApplier.cardFinalDrawableReplacement(view, drawable)
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "Installed final Settings card background hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not hook final Settings card backgrounds", error)
        }
    }

    private fun installPersistentLogoHooks() {
        runCatching {
            hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:logo-drawable")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val replacement = view?.let(SettingsAppearanceApplier::logoReplacement)
                    if (replacement != null) chain.proceedWith(arrayOf(replacement)) else chain.proceed()
                }
            hook(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:logo-resource")
                .intercept { chain ->
                    val view = chain.thisObject as? ImageView
                    val replacement = view?.let(SettingsAppearanceApplier::logoReplacement)
                    if (replacement == null) chain.proceed() else {
                        SettingsAppearanceApplier.applyLogoDrawable(view, replacement)
                        null
                    }
                }
            log(Log.INFO, TAG, "Installed persistent Settings logo replacement hooks")
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook Settings logo setters", error) }
    }

    private fun installLogoResourceHooks() {
        runCatching {
            hook(View::class.java.getMethod("setBackgroundResource", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:logo-background-resource")
                .intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val resourceId = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    val replacement = SettingsAppearanceApplier.logoResourceReplacement(
                        view.context,
                        view.resources,
                        resourceId,
                    )
                    if (replacement == null) {
                        chain.proceed()
                    } else {
                        Log.i(TAG, "Replaced Settings logo background resource id=0x${resourceId.toString(16)}")
                        view.background = replacement
                        null
                    }
                }

            listOf(
                Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType, android.content.res.Resources.Theme::class.java),
                Resources::class.java.getMethod("getDrawableForDensity", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getDrawableForDensity", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.res.Resources.Theme::class.java),
            ).forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:logo-resources-$index")
                    .intercept { chain ->
                        val resources = chain.thisObject as? Resources
                            ?: return@intercept chain.proceed()
                        val resourceId = chain.getArg(0) as? Int
                            ?: return@intercept chain.proceed()
                        val replacement = SettingsAppearanceApplier.logoResourceReplacement(resources, resourceId)
                        replacement ?: chain.proceed()
                    }
            }

            hook(Context::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:logo-context-drawable")
                .intercept { chain ->
                    val context = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    val resourceId = chain.getArg(0) as? Int
                        ?: return@intercept chain.proceed()
                    SettingsAppearanceApplier.logoResourceReplacement(context, context.resources, resourceId)
                        ?: chain.proceed()
                }
            log(Log.INFO, TAG, "Installed Settings logo resource replacement hooks")
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook Settings logo resource access", error) }
    }

    private fun installCardMaterialHooks() {
        runCatching {
            hook(View::class.java.getMethod("setBackgroundBlurAlpha", Float::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-blur-alpha")
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    val alpha = view?.let(SettingsAppearanceApplier::cardBlurAlpha)
                    if (alpha != null) chain.proceedWith(arrayOf(alpha)) else chain.proceed()
                }
            listOf("setMiBackgroundBlurAlpha", "setMiViewBlurAlpha").forEach { name ->
                runCatching {
                    hook(View::class.java.getMethod(name, Float::class.javaPrimitiveType))
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-appearance:card-$name")
                        .intercept { chain ->
                            val view = chain.thisObject as? View
                            val alpha = view?.let(SettingsAppearanceApplier::cardBlurAlpha)
                            if (alpha != null) chain.proceedWith(arrayOf(alpha)) else chain.proceed()
                        }
                }
            }
            log(Log.INFO, TAG, "Installed Settings card material opacity hooks")
        }.onFailure { error -> log(Log.DEBUG, TAG, "Settings card material alpha unavailable", error) }
    }

    private fun installCardColorResourceHooks() {
        runCatching {
            listOf(
                Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java),
            ).forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:card-color-resources-$index")
                    .intercept { chain ->
                        val resources = chain.thisObject as? Resources
                            ?: return@intercept chain.proceed()
                        val resourceId = chain.getArg(0) as? Int
                            ?: return@intercept chain.proceed()
                        val result = chain.proceed()
                        val original = result as? Int ?: return@intercept result
                        val context = currentApplicationContext()
                            ?: return@intercept original
                        SettingsAppearanceApplier.cardColorResourceReplacement(
                            context, resources, resourceId, original,
                        ) ?: original
                    }
            }

            listOf(
                Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType),
                Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType, Resources.Theme::class.java),
            ).forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:card-color-state-resources-$index")
                    .intercept { chain ->
                        val resources = chain.thisObject as? Resources
                            ?: return@intercept chain.proceed()
                        val resourceId = chain.getArg(0) as? Int
                            ?: return@intercept chain.proceed()
                        val result = chain.proceed()
                        val original = result as? ColorStateList ?: return@intercept result
                        val context = currentApplicationContext()
                            ?: return@intercept original
                        SettingsAppearanceApplier.cardColorStateListResourceReplacement(
                            context, resources, resourceId, original,
                        ) ?: original
                    }
            }

            hook(Context::class.java.getMethod("getColor", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-color-context")
                .intercept { chain ->
                    val context = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    val resourceId = chain.getArg(0) as? Int
                        ?: return@intercept chain.proceed()
                    val result = chain.proceed()
                    val original = result as? Int ?: return@intercept result
                    SettingsAppearanceApplier.cardColorResourceReplacement(
                        context, context.resources, resourceId, original,
                    ) ?: original
                }

            hook(TypedArray::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-color-typed-array")
                .intercept { chain ->
                    val typedArray = chain.thisObject as? TypedArray
                        ?: return@intercept chain.proceed()
                    val index = chain.getArg(0) as? Int
                        ?: return@intercept chain.proceed()
                    val result = chain.proceed()
                    val original = result as? Int ?: return@intercept result
                    val resourceId = runCatching { typedArray.getResourceId(index, 0) }.getOrDefault(0)
                    if (resourceId == 0) return@intercept original
                    val resources = runCatching {
                        TypedArray::class.java.getMethod("getResources").invoke(typedArray) as? Resources
                    }.getOrNull() ?: return@intercept original
                    val context = currentApplicationContext()
                        ?: return@intercept original
                    SettingsAppearanceApplier.cardColorResourceReplacement(
                        context, resources, resourceId, original,
                    ) ?: original
                }

            hook(TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-appearance:card-color-state-typed-array")
                .intercept { chain ->
                    val typedArray = chain.thisObject as? TypedArray
                        ?: return@intercept chain.proceed()
                    val index = chain.getArg(0) as? Int
                        ?: return@intercept chain.proceed()
                    val result = chain.proceed()
                    val original = result as? ColorStateList ?: return@intercept result
                    val resourceId = runCatching { typedArray.getResourceId(index, 0) }.getOrDefault(0)
                    if (resourceId == 0) return@intercept original
                    val resources = runCatching {
                        TypedArray::class.java.getMethod("getResources").invoke(typedArray) as? Resources
                    }.getOrNull() ?: return@intercept original
                    val context = currentApplicationContext()
                        ?: return@intercept original
                    SettingsAppearanceApplier.cardColorStateListResourceReplacement(
                        context, resources, resourceId, original,
                    ) ?: original
                }

            log(Log.INFO, TAG, "Installed Settings card color resource replacement hooks")
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not hook Settings card color resources", error)
        }
    }

    private fun currentApplicationContext(): Context? {
        settingsApplicationContext?.let { return it }
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.also { settingsApplicationContext = it }
    }

    private fun installAppearanceHooks(classLoader: ClassLoader, homeEnabled: Boolean, deviceEnabled: Boolean) {
        installActivityAppearanceHooks(classLoader, homeEnabled, deviceEnabled)
        if (homeEnabled) runCatching {
            val home = classLoader.loadClass(MIUI_SETTINGS)
            hookLifecycle(home, "home", after = { target ->
                (target as? Activity)?.let(SettingsAppearanceApplier::applyHome)
            }, stop = { target -> (target as? Activity)?.let(SettingsAppearanceApplier::stop) }, destroy = { target ->
                (target as? Activity)?.let(SettingsAppearanceApplier::destroy)
            })
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook Settings home appearance", error) }

        if (deviceEnabled) runCatching {
            val settingsFragment = classLoader.loadClass("com.android.settings.SettingsFragment")
            settingsFragment.declaredMethods.firstOrNull {
                it.name == "onViewCreated" && it.parameterCount == 2
            }?.let { method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:home-fragment-view")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val activity = fragmentActivity(chain.thisObject)
                        if (activity?.javaClass?.name == MIUI_SETTINGS) {
                            SettingsAppearanceApplier.applyHome(activity)
                        }
                        result
                    }
            }
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook Settings home fragment", error) }

        runCatching {
            val device = classLoader.loadClass(MY_DEVICE_SETTINGS)
            device.declaredMethods.firstOrNull { it.name == "startRuntimeShader" && it.parameterCount == 1 }?.let { method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:device-shader")
                    .intercept { chain ->
                        if (SettingsAppearanceApplier.shouldSuppressDeviceShader(chain.thisObject)) null else chain.proceed()
                    }
            }
            val onViewCreated = device.declaredMethods.firstOrNull { it.name == "onViewCreated" && it.parameterCount == 2 }
            if (onViewCreated != null) {
                hook(onViewCreated)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:device-view")
                    .intercept { chain ->
                        val result = chain.proceed()
                        SettingsAppearanceApplier.applyDevice(chain.thisObject)
                        SettingsAppearanceApplier.applyLogo(chain.thisObject)
                        result
                    }
            }
            device.declaredMethods.filter { it.name == "setDeviceShaderBackground" }.forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("settings-appearance:device-background-$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        SettingsAppearanceApplier.applyDevice(chain.thisObject)
                        result
                    }
            }
            hookLifecycle(device, "device", after = { target ->
                SettingsAppearanceApplier.applyDevice(target)
                SettingsAppearanceApplier.applyLogo(target)
            }, stop = { target -> SettingsAppearanceApplier.stopDevice(target) }, destroy = { target ->
                SettingsAppearanceApplier.destroyDevice(target)
                SettingsAppearanceApplier.destroyLogo(target)
            })
        }.onFailure { error -> log(Log.WARN, TAG, "Could not hook My Device appearance", error) }
    }

    private fun installActivityAppearanceHooks(classLoader: ClassLoader, homeEnabled: Boolean, deviceEnabled: Boolean) {
        val activityTypes = buildList {
            if (homeEnabled) add(MIUI_SETTINGS)
            if (deviceEnabled) add(SUB_SETTINGS)
            if (deviceEnabled) add(MY_DEVICE_INFO_ACTIVITY)
        }
        activityTypes.forEach { className ->
            runCatching {
                val type = classLoader.loadClass(className)
                allMethods(type).filter { method ->
                    method.name == "onCreate" && method.parameterCount == 1 ||
                        method.name == "onResume" && method.parameterCount == 0 ||
                        method.name == "onStop" && method.parameterCount == 0 ||
                        method.name == "onDestroy" && method.parameterCount == 0
                }.forEach { method ->
                    hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("settings-appearance:activity-${className.substringAfterLast('.')}-${method.name}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val activity = chain.thisObject as? Activity
                            if (activity?.javaClass?.name == className) {
                                when (method.name) {
                                "onCreate", "onResume" -> scheduleAppearance(activity, className)
                                    "onStop" -> if (className == MIUI_SETTINGS) {
                                        SettingsAppearanceApplier.stop(activity)
                                    } else {
                                        SettingsAppearanceApplier.stopDevice(activity)
                                    }
                                    "onDestroy" -> if (className == MIUI_SETTINGS) {
                                        SettingsAppearanceApplier.destroy(activity)
                                    } else {
                                        SettingsAppearanceApplier.destroyDevice(activity)
                                        SettingsAppearanceApplier.destroyLogo(activity)
                                    }
                                }
                            }
                            result
                        }
                }
            }.onFailure { error ->
                log(Log.WARN, TAG, "Could not hook Settings activity $className", error)
            }
        }
    }

    private fun scheduleAppearance(activity: Activity, className: String) {
        val decor = activity.window?.decorView ?: return
        val apply = {
            if (className == MIUI_SETTINGS) {
                SettingsAppearanceApplier.applyHome(activity)
            }
        }
        decor.post(apply)
        decor.postDelayed(apply, 300L)
        decor.postDelayed(apply, 900L)
    }

    private fun allMethods(type: Class<*>): List<java.lang.reflect.Method> {
        val methods = mutableListOf<java.lang.reflect.Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods
            current = current.superclass
        }
        return methods.distinctBy { method ->
            "${method.name}(${method.parameterTypes.joinToString { it.name }})"
        }
    }

    private fun hookLifecycle(
        type: Class<*>,
        idPrefix: String,
        after: (Any) -> Unit,
        stop: (Any) -> Unit,
        destroy: (Any) -> Unit,
    ) {
        allMethods(type).filter { it.name == "onCreate" && it.parameterCount == 1 }.forEach { method ->
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("settings-appearance:$idPrefix-create").intercept { chain ->
                val result = chain.proceed(); if (chain.thisObject.javaClass == type) after(chain.thisObject); result
            }
        }
        allMethods(type).filter { it.name == "onResume" && it.parameterCount == 0 }.forEach { method ->
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("settings-appearance:$idPrefix-resume").intercept { chain ->
                val result = chain.proceed(); if (chain.thisObject.javaClass == type) after(chain.thisObject); result
            }
        }
        allMethods(type).filter { it.name == "onStop" && it.parameterCount == 0 }.forEach { method ->
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("settings-appearance:$idPrefix-stop").intercept { chain ->
                val result = chain.proceed(); if (chain.thisObject.javaClass == type) stop(chain.thisObject); result
            }
        }
        allMethods(type).filter { it.name == "onDestroy" && it.parameterCount == 0 }.forEach { method ->
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("settings-appearance:$idPrefix-destroy").intercept { chain ->
                val result = chain.proceed(); if (chain.thisObject.javaClass == type) destroy(chain.thisObject); result
            }
        }
    }

    private fun fragmentActivity(fragment: Any): Activity? = runCatching {
        fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
    }.getOrNull()

    private fun installCardBindingHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        val adapter = classLoader.loadClass(DEVICE_INFO_ADAPTER)
        val setDataList = adapter.declaredMethods.firstOrNull {
            it.name == "setDataList" && it.parameterCount == 1
        } ?: error("DeviceInfoAdapter.setDataList not found")
        hook(setDataList)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-device-profile:data-list")
            .intercept { chain ->
                val result = chain.proceed()
                val profile = preferences.toDeviceProfileSettings()
                if (profile.enabled) {
                    applyDataListOverride(chain.thisObject, chain.getArg(0), profile)
                }
                result
            }
        val bind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterCount == 2
        } ?: error("DeviceInfoAdapter.onBindViewHolder not found")
        hook(bind)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-device-profile:bind-card")
            .intercept { chain ->
                val result = chain.proceed()
                val profile = preferences.toDeviceProfileSettings()
                if (profile.enabled) {
                    applyCardOverride(chain.thisObject, chain.getArg(1) as? Int ?: -1, profile)
                }
                result
            }
    }

    private fun installCpuIconHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        val presenter = classLoader.loadClass(DEVICE_BASIC_INFO_PRESENTER)
        presenter.declaredMethods.filter { it.name.startsWith("show") && it.parameterCount > 0 }.forEachIndexed { index, method ->
            hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-device-profile:cpu-icon-$index")
                .intercept { chain ->
                    updateCpuIcon(presenter, preferences.toDeviceProfileSettings())
                    chain.proceed()
                }
        }
    }

    private fun installDirectDetailHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        val detail = classLoader.loadClass(MY_DEVICE_DETAIL_SETTINGS)
        val memory = detail.declaredMethods.firstOrNull {
            it.name == "initMemoryInfo" && it.parameterCount == 0
        } ?: error("MiuiMyDeviceDetailSettings.initMemoryInfo not found")
        hook(memory)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-device-profile:detail-memory")
            .intercept { chain ->
                val result = chain.proceed()
                val profile = preferences.toDeviceProfileSettings()
                if (profile.enabled && profile.storage.isNotBlank()) {
                    runCatching {
                        val card = chain.thisObject.javaClass
                            .getDeclaredField("mMemoryCardItem")
                            .apply { isAccessible = true }
                            .get(chain.thisObject)
                        card.javaClass.getMethod("setValue", CharSequence::class.java)
                            .invoke(card, profile.storage)
                    }
                }
                result
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateCpuIcon(presenter: Class<*>, profile: DeviceProfileSettings) {
        val icons = presenter.getField("ICON_MAP").get(null) as? MutableMap<Any?, Any?> ?: return
        icons[0] = if (profile.enabled && profile.snapdragonIcon) SNAPDRAGON_CPU_ICON else DEFAULT_CPU_ICON
    }

    private fun applyCardOverride(adapter: Any?, position: Int, profile: DeviceProfileSettings) {
        if (adapter == null || position < 0) return
        val cards = runCatching {
            adapter.javaClass.getDeclaredField("cardInfos").apply { isAccessible = true }.get(adapter) as? Array<*>
        }.getOrNull() ?: return
        val card = cards.getOrNull(position) ?: return
        val type = card.javaClass
        val index = type.getMethod("getIndex").invoke(card) as? Int ?: -1
        val key = (type.getMethod("getKey").invoke(card) as? String).orEmpty()
        val title = (type.getMethod("getTitle").invoke(card) as? String).orEmpty()
        val adapterType = adapterType(adapter)
        val value = overrideValue(adapterType, index, key, title, profile)
        if (value != null) {
            type.getMethod("setValue", String::class.java).invoke(card, value)
        }
        applyCameraParts(card, index, profile)
        if (value == null && index != CAMERA_INDEX) return
        if ((index == CPU_INDEX || key == "cpu_item") && profile.snapdragonIcon) {
            type.getMethod("setIconResId", Int::class.javaPrimitiveType).invoke(card, SNAPDRAGON_CPU_ICON)
        }
    }

    private fun applyDataListOverride(adapter: Any?, data: Any?, profile: DeviceProfileSettings) {
        if (adapter == null || data == null || !data.javaClass.isArray) return
        val count = java.lang.reflect.Array.getLength(data)
        val type = adapterType(adapter)
        repeat(count) { position ->
            val card = java.lang.reflect.Array.get(data, position) ?: return@repeat
            val cardType = card.javaClass
            val index = runCatching { cardType.getMethod("getIndex").invoke(card) as? Int ?: -1 }.getOrDefault(-1)
            val key = runCatching { (cardType.getMethod("getKey").invoke(card) as? String).orEmpty() }.getOrDefault("")
            val title = runCatching { (cardType.getMethod("getTitle").invoke(card) as? String).orEmpty() }.getOrDefault("")
            val value = overrideValue(type, index, key, title, profile)
            if (value != null) {
                runCatching { cardType.getMethod("setValue", String::class.java).invoke(card, value) }
            }
            applyCameraParts(card, index, profile)
            if (value == null && index != CAMERA_INDEX) return@repeat
            if ((index == CPU_INDEX || key == "cpu_item") && profile.snapdragonIcon) {
                runCatching { cardType.getMethod("setIconResId", Int::class.javaPrimitiveType).invoke(card, SNAPDRAGON_CPU_ICON) }
            }
        }
    }

    private fun adapterType(adapter: Any): Int = runCatching {
        adapter.javaClass.getDeclaredField("mType").apply { isAccessible = true }.getInt(adapter)
    }.getOrDefault(0)

    private fun overrideValue(type: Int, index: Int, key: String, title: String, profile: DeviceProfileSettings): String? =
        if (type == 0 || type == 2) valueFor(index, key, title, profile) else valueForDetail(key, title, profile)

    private fun applyCameraParts(card: Any, index: Int, profile: DeviceProfileSettings) {
        if (index != CAMERA_INDEX) return
        if (profile.cameraRear.isNotBlank()) {
            runCatching { card.javaClass.getMethod("setFirstValue", String::class.java).invoke(card, profile.cameraRear) }
        }
        if (profile.cameraFront.isNotBlank()) {
            runCatching { card.javaClass.getMethod("setSecondValue", String::class.java).invoke(card, profile.cameraFront) }
        }
    }

    private fun valueFor(index: Int, key: String, title: String, profile: DeviceProfileSettings): String? = when (index) {
        CPU_INDEX -> profile.processor.takeIf(String::isNotBlank)
        BATTERY_INDEX -> profile.battery.takeIf(String::isNotBlank)
        CAMERA_INDEX -> profile.camera.takeIf(String::isNotBlank)
        SCREEN_INDEX -> profile.screenSize.takeIf(String::isNotBlank)
        RESOLUTION_INDEX -> profile.resolution.takeIf(String::isNotBlank)
        RAM_INDEX -> profile.ram.takeIf(String::isNotBlank)
        MODEL_INDEX -> profile.model.takeIf(String::isNotBlank)
        else -> valueForDetail(key, title, profile)
    }

    private fun valueForDetail(key: String, title: String, profile: DeviceProfileSettings): String? {
        if (key == "cpu_item") return profile.detailProcessor.takeIf(String::isNotBlank)
        if (key == "miui_version") return profile.osVersion.takeIf(String::isNotBlank)
        if (key == "firmware_version") return profile.androidVersion.takeIf(String::isNotBlank)
        if (key == "kernel_version") return profile.kernel.takeIf(String::isNotBlank)
        if (key == "device_internal_memory") return profile.storage.takeIf(String::isNotBlank)
        val label = title.lowercase()
        return when {
            "model" in label || "型号" in title -> profile.model.takeIf(String::isNotBlank)
            "baseband" in label || "基带" in title -> profile.baseband.takeIf(String::isNotBlank)
            "hardware" in label || "硬件" in title -> profile.hardware.takeIf(String::isNotBlank)
            "memory" in label || "内存" in title -> profile.ram.takeIf(String::isNotBlank)
            else -> null
        }
    }

    private companion object {
        const val TAG = "HyperChangerSettings"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val DEVICE_INFO_ADAPTER = "com.android.settings.device.DeviceInfoAdapter"
        const val DEVICE_BASIC_INFO_PRESENTER = "com.android.settings.device.DeviceBasicInfoPresenter"
        const val MY_DEVICE_DETAIL_SETTINGS = "com.android.settings.device.MiuiMyDeviceDetailSettings"
        const val MIUI_SETTINGS = "com.android.settings.MiuiSettings"
        const val SUB_SETTINGS = "com.android.settings.SubSettings"
        const val MY_DEVICE_INFO_ACTIVITY = "com.android.settings.Settings\$MyDeviceInfoActivity"
        const val MY_DEVICE_SETTINGS = "com.android.settings.device.MiuiMyDeviceSettings"
        const val CPU_INDEX = 0
        const val BATTERY_INDEX = 1
        const val CAMERA_INDEX = 2
        const val SCREEN_INDEX = 3
        const val RESOLUTION_INDEX = 4
        const val RAM_INDEX = 5
        const val MODEL_INDEX = 6
        // R.drawable.device_description_cpu / device_description_snapdragon_cpu in 设置_17.apk.
        const val DEFAULT_CPU_ICON = 0x7f08079b
        const val SNAPDRAGON_CPU_ICON = 0x7f0807a2
        const val NEVER_SCREEN_TIMEOUT = 2147483647L
        const val DEFAULT_SCREEN_TIMEOUT = 30000L
        const val MILLIS_PER_MINUTE = 60_000L
        val ADDITIONAL_SCREEN_TIMEOUT_MINUTES = intArrayOf(15, 20, 30, 60)
    }
}
