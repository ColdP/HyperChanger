// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the OCR native library and downloaded model in the module app's resource context. */
class PortalOcrProvider : ContentProvider() {
    private val capacity = Semaphore(1)
    private val deadline = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "PortalOcrDeadline").apply { isDaemon = true }
    }

    override fun onCreate(): Boolean {
        // Providers are created on app startup, even if enhanced OCR is disabled.
        // The store performs legacy-model cleanup on its IO scope.
        PortalOcrModelStore.get(requireNotNull(context))
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = requireNotNull(context)
        val callers = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if ("com.miui.contentextension" !in callers) throw SecurityException("Portal OCR caller not allowed")
        if (method != "recognize" || Looper.myLooper() == Looper.getMainLooper() ||
            !context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE).getBoolean(KEY_PORTAL_JAPANESE_OCR, false)
        ) return null
        val bitmap = extras?.getParcelable("image", Bitmap::class.java) ?: return null
        if (bitmap.isRecycled || bitmap.width > 8192 || bitmap.height > 8192 ||
            bitmap.width.toLong() * bitmap.height > PORTAL_OCR_MAX_PIXELS || !capacity.tryAcquire()
        ) return null
        val identity = Binder.clearCallingIdentity()
        return try {
            PortalOcrModelStore.get(context).withModel { directory ->
                val engine = TessBaseAPI()
                val timerLock = Any()
                var finished = false
                val timedOut = AtomicBoolean(false)
                try {
                    check(engine.init(directory.absolutePath, PORTAL_OCR_LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY))
                    engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    engine.setImage(bitmap)
                    val timer = deadline.schedule({
                        synchronized(timerLock) {
                            if (!finished) { timedOut.set(true); engine.stop() }
                        }
                    }, 12, TimeUnit.SECONDS)
                    val text = try {
                        // getUTF8Text alone cannot be cancelled. The HOCR pass installs
                        // Tesseract's monitor so stop() works, then UTF8 reads that result.
                        engine.getHOCRText(0)
                        if (timedOut.get()) null else engine.utF8Text
                    } finally {
                        synchronized(timerLock) { finished = true; timer.cancel(false) }
                    }
                    text?.let(::portalOcrJson)?.let { Bundle().apply { putString("result", it) } }
                } finally {
                    synchronized(timerLock) { finished = true; engine.recycle() }
                }
            }
        } catch (error: Exception) {
            ModuleLog.w("PortalOcr", "Multilingual recognition unavailable: ${error.javaClass.simpleName}")
            null
        } catch (_: LinkageError) {
            // An unavailable ABI/native dependency must preserve the stock OCR fallback.
            null
        } finally {
            capacity.release()
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun shutdown() { deadline.shutdown(); super.shutdown() }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
