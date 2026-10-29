// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal const val PORTAL_OCR_AUTHORITY = "btm.m.os4.systemuihook.portalocr"
internal const val PORTAL_OCR_MAX_PIXELS = 16_777_216L

/** No OCR engine/JNI is loaded in the target process; the module provider owns the model. */
internal class PortalOcrHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        runCatching {
            val target = classLoader.loadClass("com.miui.contentextension.utils.ContentExtentionOcrUtils")
                .getDeclaredMethod("getOcrResult", Bitmap::class.java)
            val app = classLoader.loadClass("com.miui.contentextension.App").getDeclaredMethod("getInstance")
            // Kotlin is bundled in both apps: return the HOST's Pair, never the module's Pair.
            val pair = classLoader.loadClass("kotlin.Pair").getConstructor(Any::class.java, Any::class.java)
            check(target.returnType == pair.declaringClass) { "Unexpected Portal OCR return signature" }
            hookDeoptimized(target).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:portal:japanese-ocr")
                .intercept { chain ->
                    val bitmap = chain.getArg(0) as? Bitmap
                    val eligible = preferences.getBoolean(KEY_PORTAL_JAPANESE_OCR, false) &&
                        Looper.myLooper() != Looper.getMainLooper() && bitmap != null && !bitmap.isRecycled &&
                        bitmap.width.toLong() * bitmap.height <= PORTAL_OCR_MAX_PIXELS
                    val localResult = if (eligible) runCatching {
                        val context = app.invoke(null) as Context
                        val structured = recognize(context, requireNotNull(bitmap))
                        structured?.let { pair.newInstance(false, it) }
                    }.onFailure {
                        // Never log the image or the recognized text.
                        HookModuleLogger.log(module, Log.DEBUG, logTag, "Portal Japanese OCR unavailable: ${it.javaClass.simpleName}")
                    }.getOrNull() else null
                    // No result means the original method still executes exactly once.
                    localResult ?: chain.proceed()
                }
            installed = true
            HookModuleLogger.log(module, Log.INFO, logTag, "Installed Portal Japanese OCR bridge")
        }.onFailure { HookModuleLogger.log(module, Log.WARN, logTag, "Portal Japanese OCR hook unavailable", it) }
    }

    private fun recognize(context: Context, bitmap: Bitmap): String? {
        // Bitmap.asShared transports pixels through shared memory, not Binder's small buffer.
        // It may return the caller's bitmap, which must never be recycled by this bridge.
        val shared = bitmap.asShared()
        return try {
            context.contentResolver.call(
                Uri.parse("content://$PORTAL_OCR_AUTHORITY"), "recognize", null,
                Bundle().apply { putParcelable("image", shared) },
            )?.getString("result")?.takeIf { it.isNotBlank() }
        } finally {
            if (shared !== bitmap) shared.recycle()
        }
    }
}
