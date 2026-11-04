// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.WallpaperManager
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Background image lifecycle only. Native blur writers stay owned by PersonalAssistantWallpaperHooks. */
internal class PersonalAssistantBackgroundHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    loader: ClassLoader,
    private val settings: PersonalAssistantSettings,
) {
    private val overlayClass = loader.loadClass("com.miui.personalassistant.core.overlay.AssistantOverlayWindow")
    private val adapterClass = loader.loadClass("com.miui.personalassistant.device.DeviceAdapter")
    private val blurAdapterClass = loader.loadClass("com.miui.personalassistant.device.DeviceBlurBlendAdapter")
    private val overlayField = adapterClass.getDeclaredField("mOverlay").apply { isAccessible = true }
    private val adapterField = overlayClass.getDeclaredField("D").apply {
        check(adapterClass.isAssignableFrom(type)); isAccessible = true
    }
    // The decompiler's f6614a is an alias; resolve the unique Window by its actual type.
    private val windowField = generateSequence<Class<*>>(overlayClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.single { it.type == Window::class.java }.apply { isAccessible = true }
    private val maxRadius = blurAdapterClass.getDeclaredMethod("getMaxBlurRadius")
    private val offset = blurAdapterClass.getMethod("getWindowScrollProgressOffset")
    private val blurBackground = blurAdapterClass.getDeclaredMethod("blurBackground", Float::class.javaPrimitiveType)
    private val wallpaperClass = loader.loadClass("com.miui.personalassistant.utils.wallpaper.WallpaperManagerCompat")
    private val wallpaperInstance = wallpaperClass.getDeclaredMethod("getInstance", Context::class.java)
    private val currentWallpaper = wallpaperClass.getDeclaredMethod("getCurrentWallpaper")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "HC-AssistantBackground").apply { isDaemon = true } }
    private val states = WeakHashMap<Any, WeakReference<Background>>()
    private var enabled = false

    fun install() {
        val callbacks = listOf(
            overlayClass.getDeclaredMethod("k", Bundle::class.java),
            overlayClass.getDeclaredMethod("G", Float::class.javaPrimitiveType),
            overlayClass.getDeclaredMethod("E", Float::class.javaPrimitiveType),
            overlayClass.getDeclaredMethod("F"), overlayClass.getDeclaredMethod("B"),
            overlayClass.getDeclaredMethod("l"), overlayClass.getDeclaredMethod("onDetachedFromWindow"),
        )
        check(callbacks.all { it.returnType == Void.TYPE })
        for (method in callbacks) {
            hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:personal-assistant:background:${method.name}").intercept { chain ->
                    val overlay = chain.thisObject!!
                    if (enabled) runCatching {
                        when (method.name) {
                            "G", "E" -> state(overlay)?.update(chain.getArg(0) as Float)
                            "F" -> state(overlay)?.update(1f)
                            "l", "onDetachedFromWindow" -> states.remove(overlay)?.get()?.dispose()
                        }
                    }.onFailure(::logFailure)
                    val result = chain.proceed()
                    if (enabled) runCatching {
                        when (method.name) {
                            "k" -> state(overlay)
                            "B" -> states[overlay]?.get()?.update(0f)
                            "F" -> state(overlay)?.refresh()
                        }
                    }.onFailure(::logFailure)
                    result
                }
        }
        // The host already observes wallpaper broadcasts. Reuse that signal without a second receiver.
        val changed = wallpaperClass.classLoader!!.loadClass("com.miui.personalassistant.utils.wallpaper.DesktopWallpaperManager")
            .getDeclaredMethod("notifyWallpaperChanged")
        hookDeoptimized(changed).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:personal-assistant:background:wallpaper-changed").intercept { chain ->
                val result = chain.proceed()
                if (enabled && settings.backgroundMode == 1) main.post {
                    states.values.mapNotNull { it.get() }.forEach { it.refresh() }
                }
                result
            }
        // A partially installed lifecycle must never change native blur handling.
        enabled = true
        HookModuleLogger.log(module, ModuleLog.INFO, TAG, "Installed replacement background lifecycle")
    }

    fun replacesBackground(adapter: Any?): Boolean = enabled && adapter != null &&
        states[overlayField.get(adapter)]?.get()?.let { it.ready && it.attached } == true

    private fun state(overlay: Any): Background? {
        states[overlay]?.get()?.let { return it }
        val context = overlay as? Context ?: return null
        val window = windowField.get(overlay) as? Window ?: return null
        val container = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val adapter = adapterField.get(overlay) ?: return null
        if (!blurAdapterClass.isInstance(adapter)) return null
        return Background(context, overlay, adapter, container).also {
            states[overlay] = WeakReference(it)
            it.refresh()
        }
    }

    // Keep worker closures outside Background so Kotlin cannot capture the Window-owning inner instance.
    private fun requestBitmap(target: WeakReference<Background>, ticket: Int, application: Context) {
        worker.execute {
            val result = runCatching { loadBitmap(application) }
            main.post {
                val state = target.get()
                if (state == null) result.getOrNull()?.recycle() else state.accept(ticket, result)
            }
        }
    }

    private inner class Background(context: Context, overlay: Any, adapter: Any, private val parent: ViewGroup) {
        private val overlayRef = WeakReference(overlay)
        private val adapterRef = WeakReference(adapter)
        private val app = context.applicationContext
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
            // A strong owner while attached, without a global Window/Context reference.
            tag = this@Background
        }
        private var generation = 0
        private var disposed = false
        private var loading = false
        private var progress = 0f
        private var lastBlur = -1f
        var ready = false; private set
        val attached: Boolean get() = image.parent === parent

        init { parent.addView(image, 0, ViewGroup.LayoutParams(-1, -1)) }

        fun update(value: Float) {
            progress = assistantBackgroundProgress(value)
            if (!ready || disposed) return
            runCatching {
                val adapter = adapterRef.get() ?: return
                val nativeProgress = assistantBackgroundProgress(progress, (offset.invoke(adapter) as Number).toFloat())
                val radius = settings.replacementBlurRadius(nativeProgress, (maxRadius.invoke(adapter) as Number).toFloat())
                if (radius != lastBlur) {
                    image.setRenderEffect(if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
                    lastBlur = radius
                }
                image.alpha = progress
            }.onFailure(::fallback)
        }

        fun refresh() {
            if (disposed || loading || (settings.backgroundMode == 2 && ready)) return
            loading = true
            requestBitmap(WeakReference(this), ++generation, app)
        }

        fun accept(ticket: Int, result: Result<Bitmap?>) {
            val bitmap = result.getOrNull()
            if (disposed || generation != ticket) {
                bitmap?.recycle()
                return
            }
            loading = false
            if (bitmap != null) {
                runCatching {
                    image.setImageBitmap(bitmap)
                    ready = true
                    lastBlur = -1f
                    update(progress)
                    // Reapply once after loading to restore native desktop transition blur;
                    // remove/custom blur settings now belong to the replacement image only.
                    reapplyNativeBlur()
                }.onFailure(::fallback)
            } else {
                // Missing/cleared custom files and denied wallpaper reads keep the native path.
                result.exceptionOrNull()?.let(::logFailure)
            }
        }

        private fun reapplyNativeBlur() {
            adapterRef.get()?.let { adapter ->
                val p = assistantBackgroundProgress(progress, (offset.invoke(adapter) as Number).toFloat())
                blurBackground.invoke(adapter, p)
            }
        }

        private fun fallback(error: Throwable) {
            ready = false
            runCatching {
                image.alpha = 0f
                image.setRenderEffect(null)
                image.setImageDrawable(null)
                // Async callbacks have no surrounding native frame to restore the desktop blur.
                reapplyNativeBlur()
            }
            logFailure(error)
        }

        fun dispose() {
            disposed = true
            generation++
            ready = false
            image.alpha = 0f
            image.setRenderEffect(null)
            image.setImageDrawable(null)
            image.tag = null
            parent.removeView(image)
            overlayRef.get()?.let { states.remove(it) }
        }
    }

    private fun loadBitmap(context: Context): Bitmap? {
        if (settings.backgroundMode == 2) {
            if (settings.backgroundImage.isBlank()) return null
            return decodeAssistantBackground(ImageDecoder.createSource(context.contentResolver,
                assistantBackgroundUri(settings.backgroundImage)))
        }
        val manager = WallpaperManager.getInstance(context)
        if (manager.wallpaperInfo == null) {
            val bitmap = runCatching {
                decodeAssistantBackground(ImageDecoder.createSource(java.util.concurrent.Callable {
                    AssetFileDescriptor(requireNotNull(manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)),
                        0, AssetFileDescriptor.UNKNOWN_LENGTH)
                }))
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        // Includes the host's MIUI video/live-wallpaper poster support; never a desktop screenshot.
        val source = currentWallpaper.invoke(wallpaperInstance.invoke(null, context)) as? Bitmap ?: return null
        if (source.isRecycled) return null
        val longest = maxOf(source.width, source.height)
        val width = (source.width.toLong() * minOf(longest, 2560) / longest).toInt().coerceAtLeast(1)
        val height = (source.height.toLong() * minOf(longest, 2560) / longest).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { copy ->
            Canvas(copy).apply {
                drawColor(Color.BLACK)
                drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            // The host owns source; do not recycle or modify its cached wallpaper.
        }
    }

    private fun logFailure(error: Throwable) = HookModuleLogger.log(module, ModuleLog.WARN, TAG, "Replacement background unavailable", error)
    private companion object { const val TAG = "PersonalAssistantBackground" }
}
