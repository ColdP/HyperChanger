// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Hooks are constrained to the updater process so version values never escape to other apps. */
class SystemUpdateModule : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!OsCompatibility.areHooksAllowed()) return
        if (param.packageName != UPDATER_PACKAGE) return
        val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        runCatching {
            installVersionSpoofHooks(param.defaultClassLoader, preferences)
            installUpdateDisableHooks(param.defaultClassLoader, preferences)
            installOtaValidationBypassHook(param.defaultClassLoader, preferences)
            log(Log.INFO, TAG, "Installed System Updater hooks")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install System Updater hooks", error)
        }
    }

    private fun installVersionSpoofHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.declaredMethods
            .filter { method ->
                method.name == "get" &&
                    method.returnType == String::class.java &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == String::class.java
            }
            .forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("system-update:property-$index")
                    .intercept { chain ->
                        val replacement = spoofedProperty(
                            chain.getArg(0) as? String,
                            preferences,
                        )
                        replacement ?: chain.proceed()
                    }
            }

        runCatching {
            val application = classLoader.loadClass(UPDATER_APPLICATION)
            hook(application.getMethod("onCreate"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("system-update:incremental")
                .intercept { chain ->
                    preferences.spoofedVersion()?.let(::setBuildIncremental)
                    chain.proceed()
                }
        }.onFailure { error ->
            log(Log.WARN, TAG, "Could not install updater Build.VERSION hook", error)
        }
    }

    private fun installUpdateDisableHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
        blockMethodWhenDisabled(
            classLoader.loadClass(UPDATE_SERVICE),
            "onStartCommand",
            arrayOf(Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            preferences,
            Service.START_NOT_STICKY,
            "service-start",
        )
        blockMethodWhenDisabled(
            classLoader.loadClass(DAILY_CHECK_JOB_SERVICE),
            "onStartJob",
            arrayOf(Class.forName("android.app.job.JobParameters")),
            preferences,
            false,
            "daily-job",
        )
        listOf(BOOT_RECEIVER, DAILY_RECEIVER).forEach { receiver ->
            blockMethodWhenDisabled(
                classLoader.loadClass(receiver),
                "onReceive",
                arrayOf(Context::class.java, Intent::class.java),
                preferences,
                null,
                receiver.substringAfterLast('.'),
            )
        }
    }

    private fun installOtaValidationBypassHook(classLoader: ClassLoader, preferences: SharedPreferences) {
        val featureParser = Class.forName(FEATURE_PARSER, false, classLoader)
        featureParser.declaredMethods
            .filter { method ->
                method.name == "hasFeature" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == String::class.java
            }
            .forEachIndexed { index, method ->
                hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("system-update:ota-validation-$index")
                    .intercept { chain ->
                        if (
                            preferences.getBoolean(KEY_SYSTEM_UPDATE_OTA_LIMIT_REMOVED, false) &&
                            chain.getArg(0) == OTA_VALIDATE_FEATURE
                        ) {
                            false
                        } else {
                            chain.proceed()
                        }
                    }
            }
    }

    private fun blockMethodWhenDisabled(
        target: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
        preferences: SharedPreferences,
        disabledResult: Any?,
        id: String,
    ) {
        hook(target.getMethod(name, *parameterTypes))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("system-update:disable-$id")
            .intercept { chain ->
                if (preferences.getBoolean(KEY_SYSTEM_UPDATE_DISABLED, false)) disabledResult
                else chain.proceed()
            }
    }

    private fun spoofedProperty(key: String?, preferences: SharedPreferences): String? {
        if (!preferences.getBoolean(KEY_SYSTEM_UPDATE_VERSION_SPOOF_ENABLED, false)) return null
        return when (key) {
            OS_INCREMENTAL_PROPERTY -> preferences.spoofedVersion()
            XMS_PERSIST_PROPERTY, XMS_INCREMENTAL_PROPERTY -> preferences.spoofedSotaVersion()
            else -> null
        }
    }

    private fun SharedPreferences.spoofedVersion(): String? =
        getString(KEY_SYSTEM_UPDATE_VERSION, "").orEmpty().trim().takeIf(String::isNotEmpty)

    private fun SharedPreferences.spoofedSotaVersion(): String? =
        getString(KEY_SYSTEM_UPDATE_SOTA_VERSION, "").orEmpty().trim().takeIf(String::isNotEmpty)

    private fun setBuildIncremental(version: String) {
        runCatching {
            android.os.Build.VERSION::class.java
                .getDeclaredField("INCREMENTAL")
                .apply { isAccessible = true }
                .set(null, version)
        }.onFailure { error ->
            log(Log.DEBUG, TAG, "Build.VERSION.INCREMENTAL is read-only on this device", error)
        }
    }

    companion object {
        private const val TAG = "SystemUpdateModule"
        private const val UPDATER_PACKAGE = "com.android.updater"
        private const val UPDATER_APPLICATION = "com.android.updater.Application"
        private const val UPDATE_SERVICE = "com.android.updater.UpdateService"
        private const val DAILY_CHECK_JOB_SERVICE = "com.android.updater.DailyCheckJobService"
        private const val BOOT_RECEIVER = "com.android.updater.receiver.BootCompletedReceiver"
        private const val DAILY_RECEIVER = "com.android.updater.receiver.DailyCheckReceiver"
        private const val FEATURE_PARSER = "miui.util.FeatureParser"
        private const val OTA_VALIDATE_FEATURE = "support_ota_validate"
        private const val OS_INCREMENTAL_PROPERTY = "ro.mi.os.version.incremental"
        private const val XMS_PERSIST_PROPERTY = "persist.sys.xms.version"
        private const val XMS_INCREMENTAL_PROPERTY = "ro.mi.xms.version.incremental"
    }
}
