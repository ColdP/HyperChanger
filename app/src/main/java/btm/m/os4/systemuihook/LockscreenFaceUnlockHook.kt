// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

/** Adapts HyperIsland's independent hooks to HyperChanger's preferences and loader discovery. */
internal class LockscreenFaceUnlockHook(private val module: XposedModule) {
    private val face = FaceUnlockStateHook(module)
    private val keyguard = KeyguardUnlockStateHook(module)
    private val ui = LockscreenFaceUnlockUiHook(module)
    private var installed = false
    private var initialized = false
    private var preferences: SharedPreferences? = null

    fun install(loader: ClassLoader, prefs: SharedPreferences) {
        if (installed) return
        installed = true
        preferences = prefs
        FaceUnlockFocusController.configure(prefs)
        if (prefs.getBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, false)) ui.install(loader)
        if (!prefs.getBoolean(KEY_FACE_UNLOCK_ISLAND, false)) return
        runCatching { face.install(loader) }.onFailure { report("face callbacks", it) }
        runCatching { keyguard.install(loader) }.onFailure { report("keyguard lifecycle", it) }
        // onPackageLoaded can precede Application.onCreate; initialize only after its context exists.
        listOf("com.android.systemui.SystemUIApplication", "android.app.Application").forEach { name ->
            runCatching {
                loader.loadClass(name).declaredMethods.filter { it.name == "onCreate" && it.parameterCount == 0 }.forEach { method ->
                    module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? Application)?.let(::initialize)
                        result
                    }
                }
            }.onFailure { report("application lifecycle", it) }
        }
        UnlockIslandRuntime.getContext(loader)?.let(::initialize)
    }

    fun onClassLoaded(clazz: Class<*>) {
        val prefs = preferences ?: return
        val loader = clazz.classLoader ?: return
        if (prefs.getBoolean(KEY_FACE_UNLOCK_ISLAND, false)) {
            if (clazz.name.contains("FaceAuth") || clazz.name.endsWith("KeyguardUpdateMonitor")) {
                runCatching { face.install(loader) }.onFailure { report("face loader", it) }
            }
            if (clazz.name.endsWith("IslandIconViewHolder") || clazz.name.endsWith("KeyguardStateControllerImpl")) {
                runCatching { keyguard.install(loader) }.onFailure { report("keyguard loader", it) }
            }
        }
    }

    private fun initialize(context: Context) {
        synchronized(this) {
            if (initialized) return
            keyguard.registerScreenReceiver(context)
            FaceUnlockFocusController.initialize(
                context,
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
                context.getSystemService(PowerManager::class.java)?.isInteractive == true,
            )
            initialized = true
        }
    }

    private fun report(part: String, error: Throwable) = module.log(Log.WARN, "HyperChanger", "Unlock island $part unavailable", error)
}

internal object UnlockIslandRuntime {
    fun getContext(loader: ClassLoader): Context? = runCatching {
        loader.loadClass("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private val translations = HashMap<String, Map<String, String>>()
    fun tr(context: Context, key: String, fallback: String): String {
        val locale = context.resources.configuration.locales.get(0)?.language.orEmpty()
        if (locale !in setOf("en", "ja")) return fallback
        val strings = synchronized(translations) {
            translations.getOrPut(locale) {
                runCatching {
                    val module = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                    val strings = org.json.JSONObject(module.assets.open("languages/$locale.json").bufferedReader().use { it.readText() }).getJSONObject("strings")
                    buildMap { strings.keys().forEach { put(it, strings.optString(it)) } }
                }.getOrDefault(emptyMap())
            }
        }
        return strings[key].orEmpty().ifEmpty { fallback }
    }
}
