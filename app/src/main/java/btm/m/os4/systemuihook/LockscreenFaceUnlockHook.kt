// SPDX-License-Identifier: MIT
// Copyright (c) 2026 1812z
// Copyright (c) 2026 btm_m
package btm.m.os4.systemuihook

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject

/** Adapted from HyperIsland's face-unlock icon and unlock-island hooks. */
internal class LockscreenFaceUnlockHook(private val module: XposedModule) {
    private val hookedMethods = HashSet<Method>()
    private var installed = false
    private var faceViewField: Field? = null

    fun install(classLoader: ClassLoader, preferences: android.content.SharedPreferences) {
        if (installed) return
        installed = true
        runCatching { hookFaceView(classLoader, preferences) }
            .onFailure { module.log(android.util.Log.WARN, "HyperChanger", "Face unlock icon hook unavailable", it) }
        runCatching { hookFaceState(classLoader, preferences) }
            .onFailure { module.log(android.util.Log.WARN, "HyperChanger", "Face unlock island hook unavailable", it) }
    }

    private fun hookFaceView(loader: ClassLoader, preferences: android.content.SharedPreferences) {
        val clazz = loader.loadClass("com.miui.keyguard.biometrics.faceunlock.MiuiKeyguardFaceUnlockView")
        hookOnce(clazz.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType!!)) { chain ->
            val result = chain.proceed()
            if (preferences.getBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, false) &&
                chain.args.firstOrNull() != View.GONE && isMainFaceView(chain.thisObject)
            ) (chain.thisObject as? View)?.visibility = View.GONE
            result
        }
        runCatching {
            hookOnce(clazz.getDeclaredMethod("setKeyguardFaceUnlockView", Boolean::class.javaPrimitiveType!!)) { chain ->
                val result = chain.proceed()
                if (preferences.getBoolean(KEY_DISABLE_FACE_UNLOCK_ICON, false) && chain.args.firstOrNull() == true) {
                    (chain.thisObject as? View)?.visibility = View.GONE
                }
                result
            }
        }
    }

    private fun hookFaceState(loader: ClassLoader, preferences: android.content.SharedPreferences) {
        val clazz = sequenceOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.keyguard.KeyguardUpdateMonitor",
        ).mapNotNull { runCatching { loader.loadClass(it) }.getOrNull() }.firstOrNull() ?: return
        val starts = setOf("startListeningForFace", "requestFaceAuth", "requestFaceAuthentication", "handleFaceAcquired", "onFaceAcquired")
        val success = setOf("handleFaceAuthenticated", "onFaceAuthenticated")
        val failures = setOf("handleFaceAuthFailed", "onFaceAuthFailed", "handleFaceAuthenticationFailed", "handleFaceAuthFailure", "onFaceAuthenticationFailed", "onFaceAuthFailure")
        clazz.declaredMethods.filter { it.name in starts }.forEach { method ->
            hookOnce(method) { chain ->
                val result = chain.proceed()
                if (preferences.getBoolean(KEY_FACE_UNLOCK_ISLAND, false)) postIsland(loader, "authenticating")
                result
            }
        }
        clazz.declaredMethods.filter { it.name in success }.forEach { method ->
            hookOnce(method) { chain ->
                if (preferences.getBoolean(KEY_FACE_UNLOCK_ISLAND, false)) postIsland(loader, "success")
                chain.proceed()
            }
        }
        clazz.declaredMethods.filter { it.name in failures }.forEach { method ->
            hookOnce(method) { chain ->
                val result = chain.proceed()
                if (preferences.getBoolean(KEY_FACE_UNLOCK_ISLAND, false)) postIsland(loader, "failed")
                result
            }
        }
    }

    private fun postIsland(loader: ClassLoader, state: String) {
        val context = runCatching {
            loader.loadClass("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull() ?: return
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "HyperChanger face unlock island", NotificationManager.IMPORTANCE_LOW))
        val title = when (state) {
            "authenticating" -> "人脸解锁中"
            "success" -> "人脸解锁成功"
            else -> "人脸解锁失败"
        }
        val param = JSONObject().apply {
            put("protocol", 1)
            put("business", "hyperchanger_face_unlock")
            put("islandFirstFloat", true)
            put("enableFloat", true)
            put("updatable", true)
            put("aodTitle", title)
            put("ticker", title)
            put("baseInfo", JSONObject().put("title", title).put("content", "").put("type", 2))
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText("")
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setOngoing(state == "authenticating")
            .build()
        notification.extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        manager.notify(NOTIFICATION_ID, notification)
        if (state != "authenticating") android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ manager.cancel(NOTIFICATION_ID) }, 1_200L)
    }

    private fun isMainFaceView(instance: Any?): Boolean = runCatching {
        val field = faceViewField ?: findField(instance!!.javaClass, "mIsKeyguardFaceUnlockView").also { faceViewField = it }
        field.getBoolean(instance)
    }.getOrDefault(false)

    private fun findField(clazz: Class<*>, name: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try { return current.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { current = current.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun hookOnce(method: Method, callback: (XposedInterface.Chain) -> Any?) {
        if (!hookedMethods.add(method)) return
        method.isAccessible = true
        module.hook(method).intercept(callback)
    }

    companion object {
        private const val CHANNEL = "hyperchanger_face_unlock"
        private const val NOTIFICATION_ID = 0x48494641
    }
}
