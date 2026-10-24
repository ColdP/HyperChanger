// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import btm.m.os4.systemuihook.ModuleLog as Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** AOD plugin gesture injection, following HyperLight t5/ex0/dx0/vy0. */
internal class LockscreenShortcutGestureHooks(
    private val module: HyperSystemUiModule,
    private val logTag: String,
) {
    private val installed = mutableSetOf<Executable>()
    private val listeners = Collections.synchronizedMap(WeakHashMap<View, WeakReference<ShortcutTouchListener>>())
    private val launchInFlight = AtomicBoolean(false)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var listenerGuardInstalled = false

    @Synchronized
    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        runCatching {
            val controller = loader.loadClass("com.miui.keyguard.shortcuts.controller.ShortcutViewLayoutController")
            val managerClass = loader.loadClass("com.miui.keyguard.shortcuts.manager.MiuiShortcutManager")
            val manager = managerClass.getField("INSTANCE").get(null)
            val setSide = managerClass.getMethod("setOccludedMovedShortcut", Boolean::class.javaPrimitiveType)
            val start = managerClass.getMethod("startShortcutActivity")
            val left = controller.getDeclaredField("shortcutViewLeft").apply { isAccessible = true }
            val right = controller.getDeclaredField("shortcutViewRight").apply { isAccessible = true }
            val launch: (View, Boolean) -> Unit = { view, isLeft ->
                if (launchInFlight.compareAndSet(false, true)) {
                    runCatching {
                        view.performHapticFeedback(0)
                        setSide.invoke(manager, isLeft)
                        start.invoke(manager)
                        handler.postDelayed({ launchInFlight.set(false) }, 800L)
                    }.onFailure {
                        launchInFlight.set(false)
                        btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not launch lockscreen shortcut", it)
                    }
                }
            }
            val bind: (Any) -> Unit = { instance ->
                listOf(left to true, right to false).forEach { (field, side) ->
                    (field.get(instance) as? View)?.let { icon ->
                        var listener = listeners[icon]?.get()
                        if (listener == null) {
                            listener = ShortcutTouchListener(side, preferences, currentListener(icon), launch)
                            listeners[icon] = WeakReference(listener)
                            icon.setOnTouchListener(listener)
                        }
                    }
                }
            }
            if (!listenerGuardInstalled) {
                module.hookDeoptimized(View::class.java.getMethod("setOnTouchListener", View.OnTouchListener::class.java))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-shortcut-listener-owner")
                    .intercept { chain ->
                        val listener = listeners[chain.thisObject]?.get()
                        val incoming = chain.getArg(0) as? View.OnTouchListener
                        if (listener != null && incoming !== listener) {
                            // Keep the replacement Folme listener as the stock delegate, while
                            // retaining our listener exactly as HyperLight's ex0.e guard does.
                            listener.original = incoming
                            chain.proceedWith(chain.thisObject, arrayOf(listener))
                        } else chain.proceed()
                    }
                listenerGuardInstalled = true
            }
            val bindings = controller.declaredConstructors.toList() + controller.declaredMethods.filter {
                (it.name == "updateShortcutView" && it.parameterTypes.map { type -> type.name } == listOf(
                    "com.miui.keyguard.shortcuts.data.ShortcutEntity", "android.widget.ImageView", "android.view.View",
                )) || (it.name == "updateShortcutFixedSize" && it.parameterCount == 0) ||
                    (it.name == "onShortcutDataChanged" && it.parameterCount == 2)
            }
            bindings.forEach { member ->
                if (member !in installed) {
                    module.hookDeoptimized(member)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-shortcut-bind-${member.name}-${member.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching { chain.thisObject?.let(bind) }.onFailure {
                                btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not bind lockscreen shortcut icons", it)
                            }
                            result
                        }
                    installed += member
                }
            }
            val move = loader.loadClass("com.miui.keyguard.shortcuts.controller.ShortcutMoveController")
                .getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            if (move !in installed) {
                module.hookDeoptimized(move).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-shortcut-stock-movement")
                    .intercept { chain ->
                        if (preferences.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0) == 2) false else chain.proceed()
                    }
                installed += move
            }
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.INFO, logTag, "Installed AOD shortcut gesture bindings with loader $loader")
        }.onFailure {
            btm.m.os4.systemuihook.HookModuleLogger.log(module, Log.WARN, logTag, "Could not install AOD shortcut gesture bindings", it)
        }
    }

    private fun currentListener(view: View): View.OnTouchListener? = runCatching {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(view)
        info?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }
            ?.get(info) as? View.OnTouchListener
    }.getOrNull()

    private class ShortcutTouchListener(
        private val side: Boolean,
        private val preferences: SharedPreferences,
        var original: View.OnTouchListener?,
        private val launch: (View, Boolean) -> Unit,
    ) : View.OnTouchListener {
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var moved = false
        private var gestureMode = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureMode = preferences.getInt(KEY_LOCKSCREEN_SHORTCUT_GESTURE_MODE, 0).coerceIn(0, 2)
                // Keep the system's accessibility click path.
                if ((view.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
                        ?.isTouchExplorationEnabled == true) gestureMode = 0
                downAt = SystemClock.uptimeMillis()
                downX = event.rawX
                downY = event.rawY
                tracking = true
                moved = false
            }
            if (gestureMode == 0) return original?.onTouch(view, event) ?: false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Add mode must allow the parent to recognize the stock swipe.
                    if (gestureMode == 2) view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val threshold = view.resources.displayMetrics.density * 8f
                    if (!moved && (abs(event.rawX - downX) > threshold || abs(event.rawY - downY) > threshold)) moved = true
                    if (moved) view.isPressed = false
                }
                MotionEvent.ACTION_UP -> {
                    val inside = view.width <= 0 || view.height <= 0 ||
                        hypot(event.x - view.width / 2f, event.y - view.height / 2f) <= max(view.width, view.height) / 2f
                    // HyperLight checks elapsed time and the circular hit area on release.
                    val longPress = tracking && inside && SystemClock.uptimeMillis() - downAt >= ViewConfiguration.getLongPressTimeout()
                    tracking = false
                    view.isPressed = false
                    if (gestureMode == 2) view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (longPress) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        try {
                            original?.onTouch(view, cancel)
                            if (gestureMode == 1) view.onTouchEvent(cancel)
                        } finally { cancel.recycle() }
                        launch(view, side)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    moved = false
                    view.isPressed = false
                    if (gestureMode == 2) view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            }
            val stockHandled = original?.onTouch(view, event) ?: false
            return gestureMode == 2 || stockHandled
        }
    }
}
