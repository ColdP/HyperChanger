// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.WallpaperManager
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal const val DOCK_MATERIAL_AUTHORITY = "btm.m.os4.systemuihook.dockmaterial"

/** Owns the hardware accelerated vendor glass View outside system_server. */
class DockMaterialProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private data class Entry(
        val host: SurfaceControlViewHost,
        val surface: SurfaceControlViewHost.SurfacePackage,
        val backdrop: View,
    )
    private val entries = mutableMapOf<String, Entry>()

    override fun onCreate(): Boolean {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SYSTEM_UID && Binder.getCallingUid() != Process.myUid()) {
            return Bundle().apply { putString("error", "Caller is not system_server") }
        }
        val id = arg ?: return Bundle()
        if (method == "probe") {
            val result = CompletableFuture<Bundle>()
            main.post {
                val ready = runCatching {
                    val view = entries[id]?.backdrop ?: return@runCatching false
                    val root = HiddenApiBypass.invoke(View::class.java, view, "getViewRootImpl")
                        ?: return@runCatching false
                    val field = root.javaClass.getDeclaredField("mSurTex").apply { isAccessible = true }
                    val texture = field.get(root) as? SurfaceTexture
                    texture != null && !texture.isReleased && texture.timestamp > 0L
                }.getOrDefault(false)
                result.complete(Bundle().apply { putBoolean("ready", ready) })
            }
            return runCatching { result.get(1, TimeUnit.SECONDS) }.getOrDefault(Bundle.EMPTY)
        }
        if (method == "release") {
            main.post { entries.remove(id)?.let { it.surface.release(); it.host.release() } }
            return Bundle.EMPTY
        }
        if (method != "create" || extras == null) return Bundle.EMPTY
        val result = CompletableFuture<Bundle>()
        main.post {
            runCatching { create(id, extras, result) }.onFailure { error ->
                android.util.Log.w("HyperChangerDock", "Glass host creation failed", error)
                result.complete(Bundle().apply { putString("error", error.toString()) })
            }
        }
        return runCatching { result.get(4, TimeUnit.SECONDS) }.getOrElse { error ->
            result.cancel(true)
            Bundle().apply { putString("error", error.toString()) }
        }
    }

    private fun create(id: String, args: Bundle, result: CompletableFuture<Bundle>) {
        val width = args.getInt("width")
        val height = args.getInt("height")
        require(width in 1..4096 && height in 1..4096)
        val radius = args.getFloat("radius").coerceIn(0f, minOf(width, height) / 2f)
        val display = requireNotNull(requireContext().getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
        val displayContext = requireContext().createDisplayContext(display)
        val child = View(displayContext).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radius }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, width, height, radius)
                }
            }
            clipToOutline = true
            isFocusable = false
        }
        val backdrop = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(child, FrameLayout.LayoutParams(width, height))
        }
        val host = SurfaceControlViewHost(displayContext, display, Binder())
        val layout = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "HyperChanger Dock glass" }
        try {
            HiddenApiBypass.invoke(SurfaceControlViewHost::class.java, host, "setView", backdrop, layout)
            child.post {
                runCatching {
                    configure(backdrop, child, args)
                    child.viewTreeObserver.registerFrameCommitCallback {
                        main.post {
                            if (result.isDone) {
                                host.release()
                                return@post
                            }
                            val surface = requireNotNull(host.surfacePackage)
                            entries.remove(id)?.let { it.surface.release(); it.host.release() }
                            entries[id] = Entry(host, surface, backdrop)
                            result.complete(Bundle().apply { putParcelable("surface", surface) })
                        }
                    }
                    child.invalidate()
                }.onFailure { error ->
                    host.release()
                    result.complete(Bundle().apply { putString("error", error.toString()) })
                }
            }
        } catch (error: Throwable) {
            host.release()
            throw error
        }
    }

    private fun configure(backdrop: View, child: View, args: Bundle) {
        fun invoke(view: View, name: String, vararg values: Any) =
            HiddenApiBypass.invoke(View::class.java, view, name, *values)
        val rootClass = Class.forName("android.view.ViewRootImpl")
        check(HiddenApiBypass.invoke(rootClass, null, "getSupportedBionicMaterial") == true)
        check(HiddenApiBypass.invoke(rootClass, null, "getSupportedMiBlur") == true)
        var allowed = invoke(backdrop, "setPassWindowBlurEnabled", true) == true
        if (!allowed) {
            val root = HiddenApiBypass.invoke(View::class.java, backdrop, "getViewRootImpl")
            val field = root?.javaClass?.getDeclaredField("mPassWindowBlurFilterData")?.apply { isAccessible = true }
            val previous = field?.get(root) as? String
            if (previous != null && !previous.contains(requireContext().packageName)) {
                field.set(root, "$previous,${requireContext().packageName}")
                allowed = invoke(backdrop, "setPassWindowBlurEnabled", true) == true
            }
        }
        if (!allowed) {
            val enabled = runCatching {
                View::class.java.getDeclaredField("mNeedPassWindowBlur").apply { isAccessible = true }
                    .getBoolean(backdrop)
            }.getOrDefault(false)
            check(enabled) { "Vendor pass-window blur denied for glass host" }
        }
        invoke(backdrop, "setMiBackgroundBlurMode", 1)
        invoke(backdrop, "setMiBackgroundBlurRadius", args.getInt("backdropBlur"))
        invoke(backdrop, "setMiViewBlurMode", 0)
        invoke(child, "setMiBackgroundBlurMode", 0)
        invoke(child, "setMiViewBlurMode", 1)
        val blur = args.getInt("glassBlur").coerceIn(0, 100)
        invoke(backdrop, "setMiGlassBlurRadius", blur, (blur * 500 / 36).coerceAtMost(500))
        runCatching {
            invoke(child, "setMiBackgroundBlurEnhanceFlag", 0x2000, 0x2000)
            child.clipToOutline = false
        }
        invoke(child, "setMiViewMaterialType", 1)
        val lightWallpaper = runCatching {
            requireContext().getSystemService(WallpaperManager::class.java)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints?.and(1) == 1
        }.getOrDefault(false)
        val parameters = if (lightWallpaper) floatArrayOf(
            .5f, 1.6f, 0f, .3f, .2f, 1.5f, .24f, .1f, .6f, .8f,
            0f, 1f, 1f, 1f, .1f, 0f, .3f, 1.2f, 1f,
            36f, 2.2f, 100f, 200f, 1f, .6f, -.4f, .6f, -.8f,
            1.2f, .6f, 1.4f, 1.15f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        ) else floatArrayOf(
            .5f, 1f, 0f, 1f, .16f, 1.4f, .21f, .12f, .6f, .8f,
            0f, 1f, 1f, 1f, .1f, 0f, .2f, 1.4f, 1f,
            36f, 2.2f, 100f, 200f, 1f, .6f, -.4f, .6f, -.8f,
            1.5f, 1f, 1.4f, 1.15f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
        parameters[4] = args.getInt("softLight").coerceIn(0, 100) * .004f
        invoke(child, "setMiGlass", parameters)
        val color = args.getInt("color")
        val opacity = args.getInt("opacity").coerceIn(0, 100)
        val alpha = (opacity * (0.12f + args.getInt("softLight") / 250f) * 2.55f).toInt().coerceIn(0, 255)
        invoke(backdrop, "addMiBackgroundBlendColor", Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)), 101)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
