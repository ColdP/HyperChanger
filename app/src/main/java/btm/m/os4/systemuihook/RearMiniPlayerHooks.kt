// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Insets
import android.os.Build
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.DisplayCutout
import android.view.View
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

/** Feature state only. Package dispatch stays in HyperSystemUiModule/OthersHooks. */
internal class RearMiniPlayerHooks(private val module: XposedModule) {
    private var musicLifecycleInstalled = false
    private var rearLifecycleInstalled = false
    private var musicInstalled = false
    private var receiverRegistered = false

    fun installMusic(preferences: SharedPreferences) {
        if (musicLifecycleInstalled) return
        installApplicationBoundary(RearMiniPlayerLaunch.MUSIC_PACKAGE) { app ->
            if (!musicInstalled && RearMiniPlayerLaunch.supported(app)) {
                installMusicCallbacks(app.classLoader, preferences)
                musicInstalled = true
            }
        }
        musicLifecycleInstalled = true
    }

    fun installRear(preferences: SharedPreferences) {
        if (rearLifecycleInstalled) return
        installApplicationBoundary(RearMiniPlayerLaunch.SUBSCREEN_PACKAGE) { app ->
            if (!receiverRegistered) {
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != RearMiniPlayerLaunch.ACTION) return
                        val result = if (!preferences.isRearMiniPlayerEnabled()) RearMiniPlayerLaunch.DISABLED
                        else runCatching { RearMiniPlayerLaunch.request(context) }.getOrElse {
                            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, TAG, "Rear mini player launch rejected", it)
                            RearMiniPlayerLaunch.REJECTED
                        }
                        if (isOrderedBroadcast) resultCode = result
                    }
                }, IntentFilter(RearMiniPlayerLaunch.ACTION), RearMiniPlayerLaunch.PERMISSION, null,
                    Context.RECEIVER_EXPORTED)
                receiverRegistered = true
                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, TAG, "Rear mini player launch receiver registered")
            }
        }
        rearLifecycleInstalled = true
    }

    private fun installApplicationBoundary(target: String, install: (Application) -> Unit) {
        module.hook(Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:application:$target")
            .intercept { chain ->
                val app = chain.getArg(0) as? Application
                // Application has attached: packed apps can now provide their real ClassLoader.
                // This APK's wrapper can intentionally return an empty Application.packageName.
                if (app?.baseContext?.packageName == target && Application.getProcessName() == target) {
                    runCatching { install(app) }.onFailure {
                        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.ERROR, TAG, "Rear mini player initialization failed for $target", it)
                    }
                }
                chain.proceed()
            }
    }

    private fun installMusicCallbacks(loader: ClassLoader, preferences: SharedPreferences) {
        val activityClass = loader.loadClass(RearMiniPlayerLaunch.ACTIVITY)
        val transitionClass = loader.loadClass("com.netease.cloudmusic.utils.uu")
        // Cover both public lifecycle entries as well as their private shared implementation.
        val transitions = listOf("b", "c", "d").map { transitionClass.getDeclaredMethod(it, Context::class.java) }
        val fragments = listOf("MiMiniPlayerFragment", "MiMiniInterceptFragment").map {
            loader.loadClass("com.netease.cloudmusic.module.miminiplayer.$it")
                .getDeclaredMethod("nc", DisplayCutout::class.java)
        }
        // Resolve every required version-specific method before registering anything.
        transitions.forEach { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-mini-player:automatic-transition:${method.name}").intercept { chain ->
                    val activity = chain.getArg(0) as? Activity
                    if (activity != null && active(activity, preferences)) null else chain.proceed()
                }
        }
        fragments.forEach { method ->
            val getActivity = method.declaringClass.getMethod("getActivity")
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("rear-mini-player:layout:${method.declaringClass.name}").intercept { chain ->
                    val activity = getActivity.invoke(chain.thisObject) as? Activity
                    val cutout = chain.getArg(0) as? DisplayCutout
                    if (activity == null || !active(activity, preferences)) {
                        chain.proceed()
                    } else {
                        // MiMini only distinguishes left-camera and right-camera branches.
                        // On the folded display Android can report a top bounding rect without
                        // a horizontal safe inset; derive the branch from that real rect instead
                        // of inventing a one-pixel right inset that misaligns the camera island.
                        chain.proceed(arrayOf(rearLayoutCutout(activity, cutout)))
                    }
                }
        }
        installRearWindowHooks(activityClass, preferences)
        // Activity does not automatically replace getIntent() for singleTask reuse. Preserve
        // the new managed marker (or clear it for a later normal launch) without removing tasks.
        module.hook(Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:reuse-intent")
            .intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && activityClass.isInstance(activity) && preferences.isRearMiniPlayerEnabled()) {
                    activity.intent = chain.getArg(0) as Intent
                }
                chain.proceed()
            }
        module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:display-diagnostic")
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.getArg(0) as? Activity
                if (activity != null && activityClass.isInstance(activity) &&
                    activity.intent?.getBooleanExtra(RearMiniPlayerLaunch.EXTRA_MANAGED, false) == true) {
                    btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, TAG, "Mini player resumed: display=${activity.display?.displayId}, task=${activity.taskId}")
                }
                result
            }
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, TAG, "NetEase 9.5.37 scoped mini player hooks installed")
    }

    private fun installRearWindowHooks(activityClass: Class<*>, preferences: SharedPreferences) {
        module.hook(Activity::class.java.getDeclaredMethod("onAttachedToWindow"))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:window-bounds")
            .intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && activityClass.isInstance(activity) && active(activity, preferences)) {
                    configureRearWindow(activity)
                }
                val result = chain.proceed()
                if (activity != null && activityClass.isInstance(activity) && active(activity, preferences)) {
                    configureRearWindow(activity)
                }
                result
            }
        module.hook(Activity::class.java.getDeclaredMethod("onResume"))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:window-resume")
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activityClass.isInstance(activity) && active(activity, preferences)) {
                    configureRearWindow(activity)
                }
                result
            }
        module.hook(Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:window-focus")
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activityClass.isInstance(activity) && active(activity, preferences)) {
                    configureRearWindow(activity)
                }
                result
            }
        module.hook(Activity::class.java.getDeclaredMethod("onConfigurationChanged", android.content.res.Configuration::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE).setId("rear-mini-player:window-configuration")
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activityClass.isInstance(activity) && active(activity, preferences)) {
                    configureRearWindow(activity)
                }
                result
            }
    }

    private fun configureRearWindow(activity: Activity) {
        val window = activity.window
        val attributes = window.attributes
        // SubScreen uses ALWAYS (3) for its own windows. The NetEase manifest is
        // non-resizable, so without this the system keeps a primary-display
        // compatibility frame after the task is moved to display 1.
        attributes.layoutInDisplayCutoutMode = 3
        attributes.width = WindowManager.LayoutParams.MATCH_PARENT
        attributes.height = WindowManager.LayoutParams.MATCH_PARENT
        window.attributes = attributes
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.DEBUG, TAG, "Rear mini window configured: display=${activity.display?.displayId}, " +
            "size=${window.decorView.width}x${window.decorView.height}")
    }

    private fun rearLayoutCutout(activity: Activity, cutout: DisplayCutout?): DisplayCutout? {
        if (cutout == null || cutout.safeInsetLeft > 0 || cutout.safeInsetRight > 0) return cutout
        val top = cutout.boundingRectTop
        if (top.isEmpty) return cutout
        val width = activity.window.decorView.width.takeIf { it > 0 }
            ?: activity.display?.width ?: 0
        if (width <= 0) return cutout
        val leftCamera = top.centerX() < width / 2
        val leftInset = if (leftCamera) top.right.coerceAtMost(width) else 0
        val rightInset = if (leftCamera) 0 else (width - top.left).coerceAtLeast(1).coerceAtMost(width)
        return DisplayCutout(
            Insets.of(leftInset, cutout.safeInsetTop, rightInset, cutout.safeInsetBottom),
            cutout.boundingRectLeft, top, cutout.boundingRectRight, cutout.boundingRectBottom,
            cutout.waterfallInsets,
        )
    }

    private fun active(activity: Activity, preferences: SharedPreferences) =
        preferences.isRearMiniPlayerEnabled() && RearMiniPlayerLaunch.isManaged(activity)

    private companion object { const val TAG = "HyperChangerRearMiniPlayer" }
}
