package btm.m.liquidglass.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier as ReflectModifier
import java.util.WeakHashMap

object ImmersiveNavigationHooks {
    private const val TAG = "CustomNav-Immersive"
    private var contentInsetsLogWritten = false
    private val immersiveWindows = WeakHashMap<Window, Unit>()
    private val hostNavigationBackgroundNames = arrayOf(
        "navigationBarBackground",
        "navigationBarBackgroundPadLand",
        "navigation_bar_background",
        "navigationSpace"
    )

    @JvmStatic
    fun install(module: XposedModule) {
        safeInstall(module, "navigation bar color") {
            hookWindowSetter(module, "setNavigationBarColor", Int::class.javaPrimitiveType!!) {
                arrayOf<Any>(Color.TRANSPARENT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) safeInstall(module, "navigation divider") {
            hookWindowSetter(module, "setNavigationBarDividerColor", Int::class.javaPrimitiveType!!) {
                arrayOf<Any>(Color.TRANSPARENT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) safeInstall(module, "navigation contrast") {
            hookWindowSetter(module, "setNavigationBarContrastEnforced", Boolean::class.javaPrimitiveType!!) {
                arrayOf<Any>(false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) safeInstall(module, "edge-to-edge") {
            hookWindowSetter(module, "setDecorFitsSystemWindows", Boolean::class.javaPrimitiveType!!) {
                arrayOf<Any>(false)
            }
        }
        safeInstall(module, "content navigation insets") {
            hookContentInsets(module)
        }

        safeInstall(module, "activity resume") {
            hookActivityMethod(module, "onResume") { activity ->
                applyImmersiveNavigation(activity)
                activity.window.decorView.post { applyImmersiveNavigation(activity) }
            }
        }
        safeInstall(module, "window focus") {
            hookActivityMethod(
                module = module,
                name = "onWindowFocusChanged",
                parameterTypes = arrayOf(Boolean::class.javaPrimitiveType!!)
            ) { activity, chain ->
                if (chain.getArg(0) == true) applyImmersiveNavigation(activity)
            }
        }
        module.log(Log.INFO, TAG, "Transparent system navigation hooks installed")
    }

    private fun safeInstall(module: XposedModule, feature: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            module.log(Log.WARN, TAG, "Unable to hook $feature", error)
        }
    }

    private fun hookWindowSetter(
        module: XposedModule,
        name: String,
        parameterType: Class<*>,
        arguments: () -> Array<Any>
    ) {
        val phoneWindow = Class.forName("com.android.internal.policy.PhoneWindow")
        val method = findConcreteMethod(phoneWindow, name, parameterType)
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val window = chain.thisObject as? Window
                if (window != null && isImmersiveWindow(window)) {
                    chain.proceed(arguments())
                } else {
                    chain.proceed()
                }
            }
    }

    private fun findConcreteMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *parameters) }.getOrNull()?.let { method ->
                if (!ReflectModifier.isAbstract(method.modifiers)) {
                    return method.apply { isAccessible = true }
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$name")
    }

    private fun hookActivityMethod(
        module: XposedModule,
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        after: (Activity, XposedInterface.Chain) -> Unit
    ) {
        val method: Method = Activity::class.java.getDeclaredMethod(name, *parameterTypes).apply {
            isAccessible = true
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.let { after(it, chain) }
                result
            }
    }

    private fun hookActivityMethod(
        module: XposedModule,
        name: String,
        after: (Activity) -> Unit
    ) = hookActivityMethod(module, name, emptyArray()) { activity, _ -> after(activity) }

    private fun hookContentInsets(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod(
            "dispatchApplyWindowInsets",
            WindowInsets::class.java
        ).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as? View
                val insets = chain.getArg(0) as? WindowInsets
                if (view?.id != android.R.id.content || insets == null ||
                    !isImmersiveContent(view)
                ) {
                    chain.proceed()
                } else {
                    if (!contentInsetsLogWritten) {
                        contentInsetsLogWritten = true
                        module.log(Log.INFO, TAG, "Navigation insets removed from host content")
                    }
                    chain.proceed(arrayOf(withoutNavigationInsets(insets)))
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun withoutNavigationInsets(source: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val bottomInset = source.systemWindowInsetBottom
                .takeIf { it > source.stableInsetBottom }
                ?: 0
            return source.replaceSystemWindowInsets(
                source.systemWindowInsetLeft,
                source.systemWindowInsetTop,
                source.systemWindowInsetRight,
                bottomInset
            )
        }
        val legacyBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            source.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            source.systemWindowInsetBottom.takeIf { it > source.stableInsetBottom } ?: 0
        }
        val builder = WindowInsets.Builder(source)
            .setSystemWindowInsets(
                Insets.of(
                    source.systemWindowInsetLeft,
                    source.systemWindowInsetTop,
                    source.systemWindowInsetRight,
                    legacyBottomInset
                )
            )
            .setStableInsets(
                Insets.of(
                    source.stableInsetLeft,
                    source.stableInsetTop,
                    source.stableInsetRight,
                    0
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder
                .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.NONE)
        }
        return builder.build()
    }

    @JvmStatic
    fun activate(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        synchronized(immersiveWindows) {
            immersiveWindows[activity.window] = Unit
        }
        applyImmersiveNavigation(activity)
    }

    @JvmStatic
    fun deactivate(activity: Activity) {
        synchronized(immersiveWindows) {
            immersiveWindows.remove(activity.window)
        }
    }

    private fun isImmersiveWindow(window: Window): Boolean = synchronized(immersiveWindows) {
        immersiveWindows.containsKey(window)
    }

    private fun isImmersiveContent(view: View): Boolean = synchronized(immersiveWindows) {
        immersiveWindows.keys.any { window -> view.rootView === window.decorView }
    }

    private fun applyImmersiveNavigation(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val window = activity.window
        if (!isImmersiveWindow(window)) return
        val decor = window.decorView
        clearNavigationBackgrounds(activity)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        enforceEdgeToEdgePrivateFlag(window)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (window.navigationBarColor != Color.TRANSPARENT) {
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            window.navigationBarDividerColor != Color.TRANSPARENT
        ) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val lightTheme = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = if (lightTheme) {
            decor.systemUiVisibility or layoutFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            (decor.systemUiVisibility or layoutFlags) and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        WindowCompat.getInsetsController(window, decor).isAppearanceLightNavigationBars = lightTheme
    }

    @JvmStatic
    fun clearNavigationBackgrounds(activity: Activity) {
        val decor = activity.window.decorView
        decor.findViewById<View?>(android.R.id.navigationBarBackground)?.let { background ->
            if (background.alpha != 0f) background.alpha = 0f
            makeBackgroundTransparent(background)
        }
        hostNavigationBackgroundNames.forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) {
                decor.findViewById<View?>(id)?.let(::makeBackgroundTransparent)
            }
        }
    }

    private fun makeBackgroundTransparent(view: View) {
        val background = view.background
        if (background !is ColorDrawable || background.color != Color.TRANSPARENT) {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun enforceEdgeToEdgePrivateFlag(window: Window) {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val layoutParamsClass = WindowManager.LayoutParams::class.java
            val flag = layoutParamsClass
                .getDeclaredField("PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED")
                .apply { isAccessible = true }
                .getInt(null)
            val privateFlagsField = layoutParamsClass
                .getDeclaredField("privateFlags")
                .apply { isAccessible = true }
            val attributes = window.attributes
            val currentFlags = privateFlagsField.getInt(attributes)
            if (currentFlags and flag == 0) {
                privateFlagsField.setInt(attributes, currentFlags or flag)
                window.attributes = attributes
            }
        }
    }
}
