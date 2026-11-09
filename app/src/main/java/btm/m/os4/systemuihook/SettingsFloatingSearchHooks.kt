// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import btm.m.liquidglass.hook.InjectedLifecycleOwner
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Presentation only: the original homepage listener, ActionMode, input and results own search. */
internal class SettingsFloatingSearchHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private val sessions = IdentityHashMap<Any, Session>()
    private var installed = false
    private lateinit var anchorField: Field
    private lateinit var listField: Field
    private lateinit var panelAnchorField: Field
    private lateinit var containerField: Field
    private lateinit var inputField: Field

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed || param.packageName != "com.android.settings" || !readSettingsFloatingSearchSettings(preferences).enabled) return
        val fragment = param.defaultClassLoader.loadClass("com.android.settings.SettingsFragment")
        val panel = param.defaultClassLoader.loadClass("miuix.appcompat.internal.app.widget.SearchActionModeView")
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        anchorField = field(fragment, "mAnchorView")
        listField = field(fragment, "mListView")
        panelAnchorField = field(panel, "mAnchorView")
        containerField = field(panel, "mSearchContainer")
        inputField = field(panel, "mInputView")
        val created = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val destroyed = fragment.getDeclaredMethod("onDestroyView")
        val getActivity = fragment.getMethod("getActivity")
        val start = panel.getDeclaredMethod("notifyAnimationStart", Boolean::class.javaPrimitiveType)
        val update = panel.getDeclaredMethod("notifyAnimationUpdate", Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val end = panel.getDeclaredMethod("notifyAnimationEnd", Boolean::class.javaPrimitiveType)
        installed = true
        hookDeoptimized(created).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:create").intercept { chain ->
                val result = chain.proceed()
                val host = chain.thisObject ?: return@intercept result
                runCatching {
                    sessions.remove(host)?.destroy()
                    val activity = getActivity.invoke(host) as? Activity ?: return@runCatching
                    val root = chain.args[0] as? View ?: return@runCatching
                    val anchor = anchorField.get(host) as? ViewGroup ?: return@runCatching
                    val list = listField.get(host) as? View ?: return@runCatching
                    check(anchor.findViewById<TextView>(android.R.id.input) != null && anchor.hasOnClickListeners())
                    val session = Session(activity, root, anchor, list, preferences)
                    sessions[host] = session
                    try { session.attach() } catch (error: Throwable) {
                        sessions.remove(host)
                        session.destroy()
                        throw error
                    }
                }.onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings search", it) }
                result
            }
        hookDeoptimized(destroyed).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:destroy").intercept { chain ->
                sessions.remove(chain.thisObject)?.destroy()
                chain.proceed()
            }
        hookDeoptimized(start).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:start").intercept { chain ->
                val view = chain.thisObject as? ViewGroup
                val entering = chain.args[0] as Boolean
                val session = view?.let(::sessionFor)
                if (!entering) session?.safely { captureExit() }
                val result = chain.proceed()
                if (view != null) session?.safely { begin(view, entering) }
                result
            }
        hookDeoptimized(update).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:update").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { view ->
                    sessionFor(view)?.safely { progress(chain.args[0] as Boolean, chain.args[1] as Float) }
                }
                result
            }
        hookDeoptimized(end).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-search:end").intercept { chain ->
                val session = (chain.thisObject as? ViewGroup)?.let(::sessionFor)
                val result = chain.proceed()
                session?.safely { finish(chain.args[0] as Boolean) }
                result
            }
    }

    private fun sessionFor(panel: ViewGroup): Session? {
        sessions.values.firstOrNull { it.panel === panel }?.let { return it }
        val anchor = runCatching { (panelAnchorField.get(panel) as? WeakReference<*>)?.get() }.getOrNull() ?: return null
        return sessions.values.firstOrNull { it.anchor === anchor }
    }

    private enum class Phase { HOME, ENTERING, SEARCH, EXITING }

    private inner class Session(
        val activity: Activity,
        val root: View,
        val anchor: ViewGroup,
        val list: View,
        val preferences: SharedPreferences,
    ) : Application.ActivityLifecycleCallbacks {
        private val decor = activity.window.decorView as ViewGroup
        private val anchorHeight = anchor.layoutParams.height
        private val anchorTopMargin = (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
        private val anchorBottomMargin = (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
        private val anchorAlpha = anchor.alpha
        private val anchorVisibility = anchor.visibility
        private val anchorAccessibility = anchor.importantForAccessibility
        private val originalPadding = intArrayOf(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
        private val originalClipToPadding = (list as? ViewGroup)?.clipToPadding
        private var owner: InjectedLifecycleOwner? = null
        private var recomposer: Recomposer? = null
        private var overlay: ComposeView? = null
        private var materialSurface: SettingsSearchMaterialView? = null
        private var destroyed = false
        private var resumed = true
        private var registered = false
        private var collapsed = false
        private var pendingOpen by mutableStateOf(false)
        private var phase by mutableStateOf(Phase.HOME)
        private var transitionStart = 0f
        private var expanded by mutableFloatStateOf(0f)
        private var home by mutableStateOf(Rect.Zero)
        private var target by mutableStateOf(Rect.Zero)
        private var label by mutableStateOf("")
        private var configuration by mutableStateOf(readSettingsFloatingSearchSettings(preferences))
        private var nativeContainer: View? = null
        private var nativeInput: EditText? = null
        private var nativeAlpha = 1f
        var panel: ViewGroup? = null
            private set
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private val hint = UnlockIslandRuntime.tr(activity, "settingsFloatingSearchHint", "搜索")
        private val listener = ViewTreeObserver.OnPreDrawListener {
            safely { synchronize() }
            true
        }
        private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_SETTINGS_FLOATING_SEARCH || key == KEY_SETTINGS_FLOATING_SEARCH_PARAMETERS) {
                root.post { safely { configuration = readSettingsFloatingSearchSettings(preferences); synchronize() } }
            }
        }
        private val timeout = Runnable {
            safely { if (pendingOpen && phase == Phase.HOME) { pendingOpen = false; synchronize() } }
        }

        fun attach() {
            check(root !== decor)
            val material = SettingsSearchMaterialView(activity) { error -> root.post { safely { throw error } } }
            materialSurface = material
            material.visibility = View.INVISIBLE
            decor.addView(material, ViewGroup.LayoutParams(1, 1))
            val lifecycle = InjectedLifecycleOwner.create(activity)
            owner = lifecycle
            val compose = ComposeView(activity)
            overlay = compose
            lifecycle.attachTo(compose)
            // Settings has a platform DecorView with no AndroidX tree owner. The default
            // window recomposer resolves that DecorView, ignoring this child's tree owner.
            // Give only our ComposeView an explicit context bound to our injected lifecycle.
            val composition = compose.createLifecycleAwareWindowRecomposer(lifecycle = lifecycle.lifecycle)
            recomposer = composition
            compose.setParentCompositionContext(composition)
            compose.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            compose.isForceDarkAllowed = false
            compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            label = hint
            compose.setContent {
                val density = LocalDensity.current
                val rect = presentationBounds()
                val mix = handoff(expanded)
                val dark = isSystemInDarkTheme()
                Box(Modifier.fillMaxSize()) {
                    if (rect.width > 0f && rect.height > 0f) {
                        SettingsFloatingSearchSurface(configuration, dark, label,
                            Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                                .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
                            enabled = phase == Phase.HOME && !pendingOpen,
                            motionAlpha = 1f - mix, motionBlur = (mix * 8f).dp,
                            onClick = { open() }, onPressScale = { scale ->
                                materialSurface?.let { it.scaleX = scale; it.scaleY = scale }
                            })
                    }
                }
            }
            decor.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            registered = true
            decor.viewTreeObserver.addOnPreDrawListener(listener)
            preferences.registerOnSharedPreferenceChangeListener(changed)
            activity.application.registerActivityLifecycleCallbacks(this)
            HookModuleLogger.log(module, Log.INFO, TAG,
                "Attached native MiBlur search material")
        }

        fun safely(action: Session.() -> Unit) {
            if (destroyed) return
            try { action() } catch (error: Throwable) {
                destroy()
                HookModuleLogger.log(module, Log.WARN, TAG, "Restored native Settings search", error)
            }
        }

        private fun open() = safely {
            if (phase != Phase.HOME || pendingOpen || !configuration.enabled) return@safely
            pendingOpen = true
            // A single real click retains enterprise restrictions, initialization, statistics and throttling.
            val handled = anchor.performClick()
            if (!handled) pendingOpen = false
            root.removeCallbacks(timeout)
            root.postDelayed(timeout, 1200L)
        }

        fun captureExit() {
            label = nativeInput?.text?.toString()?.takeIf { it.isNotEmpty() } ?: hint
            if (phase == Phase.SEARCH) nativeContainer?.let { target = bounds(it) }
        }

        fun begin(view: ViewGroup, entering: Boolean) {
            pendingOpen = false
            root.removeCallbacks(timeout)
            panel = view
            val container = containerField.get(view) as View
            if (nativeContainer !== container) {
                restoreNative()
                nativeContainer = container
                nativeAlpha = container.alpha
            }
            nativeInput = inputField.get(view) as EditText
            // Preserve the visible fraction when native search is reversed before entry finishes.
            transitionStart = if (phase == Phase.ENTERING || phase == Phase.EXITING) expanded else if (entering) 0f else 1f
            phase = if (entering) Phase.ENTERING else Phase.EXITING
            expanded = transitionStart
            if (entering) label = hint
            synchronize()
        }

        fun progress(entering: Boolean, fraction: Float) {
            if (phase != Phase.ENTERING && phase != Phase.EXITING) return
            // MIUIX runs 0→1 in both directions, not 1→0 on exit.
            expanded = lerp(transitionStart, if (entering) 1f else 0f, fraction.coerceIn(0f, 1f))
            synchronize()
        }

        fun finish(entering: Boolean) {
            expanded = if (entering) 1f else 0f
            phase = if (entering) Phase.SEARCH else Phase.HOME
            restoreNative()
            if (!entering) {
                label = hint
                nativeContainer = null
                nativeInput = null
                panel = null
            }
            synchronize()
        }

        private fun bounds(view: View): Rect {
            view.getLocationInWindow(location)
            return Rect((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat(),
                (location[0] - origin[0] + view.width).toFloat(), (location[1] - origin[1] + view.height).toFloat())
        }

        private fun presentationBounds(): Rect {
            val p = expanded
            return if (phase == Phase.HOME || target == Rect.Zero) home else Rect(
                lerp(home.left, target.left, p), lerp(home.top, target.top, p),
                lerp(home.right, target.right, p), lerp(home.bottom, target.bottom, p),
            )
        }

        private fun synchronize() {
            val compose = overlay ?: return
            val active = configuration.enabled && resumed && root.isShown && !activity.isFinishing && !activity.isDestroyed
            if (!active || compose.width <= 0 || compose.height <= 0) {
                compose.visibility = View.INVISIBLE
                materialSurface?.visibility = View.INVISIBLE
                if (!configuration.enabled) { restoreNative(); restoreHome() }
                return
            }
            compose.getLocationInWindow(origin)
            val viewport = bounds(root)
            val density = activity.resources.displayMetrics.density
            val insets = decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
            val width = settingsSearchWidth(viewport.width, density, configuration.widthScale)
            val height = settingsSearchHeight(density, configuration.heightScale)
            val bottom = minOf(viewport.bottom, compose.height - (insets?.bottom ?: 0).toFloat()) - 12f * density
            // Do not move the origin with the IME while the capsule is becoming native input.
            if (phase == Phase.HOME || home == Rect.Zero) {
                home = Rect(viewport.center.x - width / 2f, bottom - height, viewport.center.x + width / 2f, bottom)
            }
            if (phase == Phase.ENTERING || phase == Phase.SEARCH) {
                nativeContainer?.takeIf { it.width > 0 && it.height > 0 }?.let { target = bounds(it) }
            }
            // A GONE header is excluded by NestedHeaderLayout's range calculation, including margins.
            val params = anchor.layoutParams
            var changed = params.height != 0
            params.height = 0
            (params as? ViewGroup.MarginLayoutParams)?.let {
                changed = changed || it.topMargin != 0 || it.bottomMargin != 0
                it.topMargin = 0; it.bottomMargin = 0
            }
            if (changed) anchor.layoutParams = params
            anchor.visibility = View.GONE
            anchor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            collapsed = true
            val padding = originalPadding[3] + height.roundToInt() + (24f * density).roundToInt()
            if (list.paddingBottom != padding) list.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], padding)
            (list as? ViewGroup)?.let { if (it.clipToPadding) it.clipToPadding = false }
            val transitioning = phase == Phase.ENTERING || phase == Phase.EXITING
            if (transitioning) nativeContainer?.alpha = nativeAlpha * handoff(expanded)
            compose.visibility = if (phase == Phase.SEARCH) View.INVISIBLE else View.VISIBLE
            val rect = presentationBounds()
            materialSurface?.let { material ->
                val w = rect.width.roundToInt().coerceAtLeast(1)
                val h = rect.height.roundToInt().coerceAtLeast(1)
                if (material.layoutParams.width != w || material.layoutParams.height != h) {
                    material.layoutParams = material.layoutParams.apply { this.width = w; this.height = h }
                }
                decor.getLocationInWindow(location)
                material.x = rect.left + origin[0] - location[0]
                material.y = rect.top + origin[1] - location[1]
                material.alpha = 1f - handoff(expanded)
                material.visibility = compose.visibility
                material.update(configuration, activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }
        }

        private fun restoreNative() { nativeContainer?.alpha = nativeAlpha }

        private fun restoreHome() {
            if (!collapsed) return
            val params = anchor.layoutParams
            params.height = anchorHeight
            (params as? ViewGroup.MarginLayoutParams)?.let {
                anchorTopMargin?.let { v -> it.topMargin = v }
                anchorBottomMargin?.let { v -> it.bottomMargin = v }
            }
            anchor.layoutParams = params
            anchor.alpha = anchorAlpha
            anchor.visibility = anchorVisibility
            anchor.importantForAccessibility = anchorAccessibility
            list.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
            originalClipToPadding?.let { (list as? ViewGroup)?.clipToPadding = it }
            collapsed = false
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            sessions.entries.removeAll { it.value === this }
            // Cleanup failures must not prevent the host's onDestroyView/original callbacks.
            fun clean(action: () -> Unit) {
                runCatching(action).onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Search presentation cleanup failed", it) }
            }
            clean { root.removeCallbacks(timeout) }
            clean { decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
            materialSurface?.let { view ->
                clean { view.release() }
                clean { (view.parent as? ViewGroup)?.removeView(view) }
            }
            materialSurface = null
            if (registered) {
                clean { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
                clean { activity.application.unregisterActivityLifecycleCallbacks(this) }
                registered = false
            }
            clean { restoreNative() }
            clean { restoreHome() }
            overlay?.let { view ->
                clean { view.disposeComposition() }
                clean { (view.parent as? ViewGroup)?.removeView(view) }
            }
            overlay = null
            clean { owner?.destroy() }
            owner = null
            clean { recomposer?.cancel() }
            recomposer = null
            nativeContainer = null
            nativeInput = null
            panel = null
        }

        override fun onActivityResumed(target: Activity) { if (target === activity) { resumed = true; safely { synchronize() } } }
        override fun onActivityPaused(target: Activity) { if (target === activity) {
            resumed = false
            materialSurface?.visibility = View.INVISIBLE
            overlay?.visibility = View.INVISIBLE
            restoreNative()
        } }
        override fun onActivityDestroyed(target: Activity) { if (target === activity) destroy() }
        override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(target: Activity) = Unit
        override fun onActivityStopped(target: Activity) = Unit
        override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
    }

    private companion object {
        const val TAG = "SettingsFloatingSearch"
        fun lerp(start: Float, end: Float, p: Float): Float = start + (end - start) * p
        fun handoff(p: Float): Float = ((p - .72f) / .28f).coerceIn(0f, 1f)
    }
}
