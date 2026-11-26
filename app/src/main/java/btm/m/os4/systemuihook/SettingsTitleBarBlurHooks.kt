// SPDX-License-Identifier: MIT
// Copyright (c) 2026 HyperBackground contributors
// Adapted from HyperBackground 1.4.5-beta2, DynamicActionBarHook.kt:
// https://github.com/Solomonstery/HyperBackground/tree/v1.4.5-beta2
// Modifications Copyright 2026 btm_m: Settings-only dispatch, independent opt-in
// preferences, host hook API/DexKit integration, 114% blur height and failure recovery.
// Full license: app/src/main/assets/licenses/HyperBackground-MIT.txt.
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/** Only the top ActionBarContainer in com.android.settings; no button or bottom-bar hooks. */
internal class SettingsTitleBarBlurHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
) {
    private data class State(
        val blur: View,
        var original: Drawable? = null,
        var cleared: Boolean = false,
        var lastRadius: Float = -1f,
        var lastHeight: Int = -1,
        var lastAlpha: Float = 0f,
        var originalClipChildren: Boolean? = null,
        var failed: Boolean = false,
    )

    private data class DiscoveredMask(
        val painter: Method,
        val alphaField: Field,
    )

    private val owned = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private val homeMasks = IdentityHashMap<Any, HomeMask>()
    private var installed = false
    private lateinit var preferences: SharedPreferences
    @Volatile private var maskAlpha: Field? = null
    @Volatile private var painterIsDraw: Boolean = false
    private lateinit var isSplit: Field
    private lateinit var getPrimary: Method
    private lateinit var setPrimary: Method
    private lateinit var setType: Method
    private lateinit var setMode: Method
    private lateinit var setViewMode: Method
    private lateinit var setGradient: Method

    fun install(param: PackageLoadedParam, prefs: SharedPreferences) {
        if (installed || param.packageName != "com.android.settings" || !readSettingsTitleBarBlurSettings(prefs).enabled) return
        val loader = param.defaultClassLoader
        val type = loader.loadClass(BAR)
        // Resolve everything before registering or modifying a view. Unknown MIUIX builds stay native.
        isSplit = type.getDeclaredField("mIsSplit").apply { isAccessible = true }
        setType = View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType)
        setMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        setViewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        setGradient = View::class.java.getMethod("setBackgroundGradientBlurParams", FloatArray::class.java, Int::class.javaPrimitiveType)
        getPrimary = type.getMethod("getPrimaryBackground")
        setPrimary = type.getMethod("setPrimaryBackground", Drawable::class.java)
        val draw = type.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }
        val inflate = type.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }
        val layout = type.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val apk = param.applicationInfo.sourceDir
        val cacheFile = resolveCacheFile(param)
        val fingerprint = apkFingerprint(apk)
        val cached = loadCachedMask(cacheFile, fingerprint, type)
        preferences = prefs
        installed = true

        hookDeoptimized(draw).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:draw").intercept { chain ->
                val bar = chain.thisObject as? ViewGroup
                val state = bar?.let { owned[it] }
                val alphaField = maskAlpha
                if (bar != null && state != null && alphaField != null) {
                    try {
                        update(bar, state, alphaField)
                    } catch (error: Throwable) {
                        state.failed = true
                        restore(bar, state)
                        HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings title bar", error)
                    }
                }
                if (painterIsDraw && alphaField != null && bar != null && state?.cleared == true && !state.failed) {
                    val original = alphaField.getFloat(bar)
                    try {
                        alphaField.setFloat(bar, 0f)
                        chain.proceed()
                    } finally {
                        alphaField.setFloat(bar, original)
                    }
                } else chain.proceed()
            }
        hookDeoptimized(inflate).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:inflate").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { bar ->
                    if (!isSplit.getBoolean(bar)) ensure(bar)
                }
                result
            }
        hookDeoptimized(layout).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:layout").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { bar ->
                    owned[bar]?.blur?.layout(0, 0, bar.width, gradientHeight(bar))
                }
                result
            }
        // Optional homepage integration: unknown structures retain their native mask.
        runCatching { installHomeMask(loader, type) }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings nested header mask", it)
        }
        if (cached != null) {
            activateMask(cached, draw)
            HookModuleLogger.log(module, Log.INFO, TAG,
                "Installed Settings title bar gradient blur (cached): mask=${cached.painter.name}, alpha=${cached.alphaField.name}")
        } else {
            Thread({
                runCatching {
                    val discovered = discoverMask(type, loader, apk)
                    saveCachedMask(cacheFile, fingerprint, discovered)
                    activateMask(discovered, draw)
                    synchronized(owned) { owned.keys.toList() }.forEach { bar ->
                        bar.post { bar.invalidate() }
                    }
                    HookModuleLogger.log(module, Log.INFO, TAG,
                        "Installed Settings title bar gradient blur: mask=${discovered.painter.name}, alpha=${discovered.alphaField.name}")
                }.onFailure { error ->
                    HookModuleLogger.log(module, Log.WARN, TAG, "Could not resolve Settings title bar mask", error)
                }
            }, "hc-titlebar-dexkit").apply { isDaemon = true }.start()
        }
    }

    private fun activateMask(mask: DiscoveredMask, draw: Method) {
        if (mask.painter != draw) {
            hookDeoptimized(mask.painter).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:settings-titlebar:mask").intercept { chain ->
                    val state = (chain.thisObject as? ViewGroup)?.let { owned[it] }
                    if (state?.cleared == true && !state.failed) null else chain.proceed()
                }
        }
        painterIsDraw = mask.painter == draw
        maskAlpha = mask.alphaField
    }

    private fun installHomeMask(loader: ClassLoader, barType: Class<*>) {
        val fragment = loader.loadClass("com.android.settings.SettingsFragment")
        val nestedType = loader.loadClass("miuix.nestedheader.widget.NestedHeaderLayout")
        val nestedField = fragment.getDeclaredField("mNestedHeaderLayout").apply { isAccessible = true }
        val maskField = nestedType.getDeclaredField("mOverBgView").apply { isAccessible = true }
        val created = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val destroyed = fragment.getDeclaredMethod("onDestroyView")
        hookDeoptimized(created).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:home-create").intercept { chain ->
                val result = chain.proceed()
                val host = chain.thisObject ?: return@intercept result
                runCatching {
                    homeMasks.remove(host)?.destroy()
                    val root = chain.args[0] as? ViewGroup ?: return@runCatching
                    val nested = nestedField.get(host) as? ViewGroup ?: return@runCatching
                    val mask = maskField.get(nested) as? View ?: return@runCatching
                    // The observed homepage root owns its own ActionBarContainer directly.
                    // Do not select the Activity's outer bar or another fragment's mask.
                    var ancestor: View? = nested
                    while (ancestor != null && ancestor !== root) ancestor = ancestor.parent as? View
                    if (ancestor !== root || mask.parent !== nested ||
                        mask.javaClass.name != "miuix.nestedheader.widget.NestedHeaderOverlayMaskView") return@runCatching
                    val bars = (0 until root.childCount).map { root.getChildAt(it) }
                        .filter { barType.isInstance(it) && !isSplit.getBoolean(it) }
                    val bar = bars.singleOrNull() as? ViewGroup ?: return@runCatching
                    if (owned[bar] == null) return@runCatching
                    val binding = HomeMask(root, bar, mask)
                    homeMasks[host] = binding
                    binding.attach()
                }.onFailure {
                    homeMasks.remove(host)?.destroy()
                    HookModuleLogger.log(module, Log.WARN, TAG, "Keeping native Settings homepage mask", it)
                }
                result
            }
        hookDeoptimized(destroyed).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-titlebar:home-destroy").intercept { chain ->
                homeMasks.remove(chain.thisObject)?.destroy()
                chain.proceed()
            }
    }

    /** This is a second opaque mask outside ActionBarContainer, not its animated painter. */
    private inner class HomeMask(
        private val root: ViewGroup,
        private val bar: ViewGroup,
        private val mask: View,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val originalAlpha = mask.alpha
        private var observer: ViewTreeObserver? = null
        private var failed = false

        fun attach() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
        }

        override fun onViewAttachedToWindow(view: View) {
            if (observer != null || failed) return
            root.viewTreeObserver.let {
                it.addOnPreDrawListener(this)
                observer = it
            }
        }

        override fun onPreDraw(): Boolean {
            val state = owned[bar]
            val alphaField = maskAlpha
            try {
                if (state != null && alphaField != null) update(bar, state, alphaField)
                val active = state != null && alphaField != null && state.cleared && !state.failed &&
                    bar.isShown && bar.height > 0 && readSettingsTitleBarBlurSettings(preferences).enabled
                val alpha = if (active) 0f else originalAlpha
                if (mask.alpha != alpha) mask.alpha = alpha
            } catch (error: Throwable) {
                failed = true
                detach()
                if (state != null) {
                    state.failed = true
                    runCatching { restore(bar, state) }
                }
                HookModuleLogger.log(module, Log.WARN, TAG, "Restored native Settings homepage mask", error)
            }
            return true
        }

        private fun detach() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            if (mask.alpha != originalAlpha) mask.alpha = originalAlpha
        }

        override fun onViewDetachedFromWindow(view: View) = detach()

        fun destroy() {
            root.removeOnAttachStateChangeListener(this)
            detach()
        }
    }

    private fun resolveCacheFile(param: PackageLoadedParam): File? {
        val base = param.applicationInfo.deviceProtectedDataDir?.takeIf { it.isNotEmpty() }
            ?: param.applicationInfo.dataDir?.takeIf { it.isNotEmpty() }
            ?: return null
        return File(File(base, "cache"), CACHE_FILE_NAME)
    }

    private fun apkFingerprint(apk: String): String {
        val file = File(apk)
        return "$apk|${file.length()}|${file.lastModified()}"
    }

    private fun loadCachedMask(cacheFile: File?, fingerprint: String, type: Class<*>): DiscoveredMask? {
        if (cacheFile == null || !cacheFile.isFile) return null
        return runCatching {
            val lines = cacheFile.readLines()
            if (lines.size < 3 || lines[0] != fingerprint) return null
            val painterName = lines[1].trim()
            val alphaName = lines[2].trim()
            if (painterName.isEmpty() || alphaName.isEmpty()) return null
            val painter = type.getDeclaredMethod(painterName, Canvas::class.java).apply { isAccessible = true }
            if (painter.returnType != Void.TYPE) return null
            val alphaField = type.getDeclaredField(alphaName).apply { isAccessible = true }
            if (alphaField.type != Float::class.javaPrimitiveType) return null
            DiscoveredMask(painter, alphaField)
        }.getOrNull()
    }

    private fun saveCachedMask(cacheFile: File?, fingerprint: String, mask: DiscoveredMask) {
        if (cacheFile == null) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText("$fingerprint\n${mask.painter.name}\n${mask.alphaField.name}\n")
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText("$fingerprint\n${mask.painter.name}\n${mask.alphaField.name}\n")
                temp.delete()
            }
        }
    }

    private fun discoverMask(type: Class<*>, loader: ClassLoader, apk: String): DiscoveredMask {
        System.loadLibrary("dexkit")
        DexKitBridge.create(apk).use { bridge ->
            val data = requireNotNull(bridge.getClassData(type))
            val painter = data.methods.filter { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("android.graphics.Canvas") &&
                    method.invokes.any { it.className == "android.graphics.Canvas" && it.name == "drawPath" } &&
                    method.invokes.any { it.className == BAR && it.name == "getCollapsedHeight" }
            }.singleOrNull() ?: error("Settings action bar mask is ambiguous")
            val field = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" && field.writers.any { writer ->
                    writer.invokes.any { it.className == "android.animation.ValueAnimator" && it.name == "getAnimatedValue" }
                }
            }.distinctBy { it.descriptor }.singleOrNull() ?: error("Settings action bar mask alpha is ambiguous")
            val alphaField = field.getFieldInstance(loader).apply { isAccessible = true }
            val painterMethod = painter.getMethodInstance(loader).apply { isAccessible = true }
            return DiscoveredMask(painterMethod, alphaField)
        }
    }

    private fun ensure(bar: ViewGroup) {
        if (owned.containsKey(bar)) return
        val child = View(bar.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        bar.addView(child, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        owned[bar] = State(child)
    }

    private fun update(bar: ViewGroup, state: State, alphaField: Field) {
        if (state.failed) return
        if (!readSettingsTitleBarBlurSettings(preferences).enabled) {
            restore(bar, state)
            return
        }
        // Extend only the blur surface, preserving the title bar's measured height and content.
        if (state.originalClipChildren == null) state.originalClipChildren = bar.clipChildren
        bar.clipChildren = false
        if (!state.cleared) {
            state.original = getPrimary.invoke(bar) as? Drawable
            state.cleared = true
        }
        if (getPrimary.invoke(bar) != null) setPrimary.invoke(bar, null)
        // HyperBackground defaults: strength 10, opacity 100%; retain the stock mask animation.
        val radius = min(10f * bar.resources.displayMetrics.density, bar.height * .5f)
        val height = gradientHeight(bar)
        val alpha = alphaField.getFloat(bar).coerceIn(0f, 1f)
        if (radius <= 0f || bar.height <= 0) {
            hide(state)
            return
        }
        if (alpha <= 0f) {
            // Keep the configured render node when scrolling crosses zero; only fade the layer.
            if (state.lastAlpha != 0f) {
                state.blur.alpha = 0f
                state.lastAlpha = 0f
            }
            return
        }
        if (state.lastRadius != radius || state.lastHeight != height) {
            setMode.invoke(state.blur, 1)
            setViewMode.invoke(state.blur, 1)
            setType.invoke(state.blur, 2)
            setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, height.toFloat(), 0f), 1)
            state.lastRadius = radius
            state.lastHeight = height
        }
        if (state.lastAlpha != alpha) {
            state.blur.alpha = alpha
            state.lastAlpha = alpha
        }
        state.blur.visibility = View.VISIBLE
    }

    private fun restore(bar: ViewGroup, state: State) {
        if (state.cleared) {
            if (getPrimary.invoke(bar) == null) setPrimary.invoke(bar, state.original)
            state.cleared = false
            state.original = null
        }
        hide(state)
        state.originalClipChildren?.let {
            bar.clipChildren = it
            state.originalClipChildren = null
        }
    }

    private fun gradientHeight(bar: ViewGroup): Int = (bar.height * 1.14f).roundToInt()

    private fun hide(state: State) {
        state.blur.visibility = View.INVISIBLE
        // Also clean up partially configured layers if a vendor API throws.
        if (state.lastRadius >= 0f || state.failed) {
            runCatching { setType.invoke(state.blur, 0) }
            runCatching { setViewMode.invoke(state.blur, 0) }
            runCatching { setMode.invoke(state.blur, 0) }
        }
        state.lastRadius = -1f
        state.lastHeight = -1
        state.lastAlpha = 0f
    }

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val TAG = "SettingsTitleBarBlur"
        const val CACHE_FILE_NAME = "hyperchanger_titlebar_mask_v1.cache"
    }
}
