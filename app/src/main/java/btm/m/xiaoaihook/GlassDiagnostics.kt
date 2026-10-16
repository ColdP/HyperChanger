// SPDX-License-Identifier: Apache-2.0
package btm.m.xiaoaihook

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Durable diagnostics for the Super XiaoAI glass work.
 *
 * The device's logcat ring buffer is practically empty on the target build, so module logs never
 * survive: the previous investigation could not tell whether an intercept ran at all. Every
 * decisive event is therefore appended to a small file inside the host app's own storage, which
 * is readable over adb afterwards without installing anything else.
 *
 * Writes are best effort and never throw into the hook path.
 */
internal object GlassDiagnostics {
    private const val TAG = "XiaoAiGlass"
    private const val FILE_NAME = "xiaai-glass-diag.log"
    private const val MAX_BYTES = 128 * 1024

    private val onceKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val lock = Any()
    private var file: File? = null
    private var resolved = false
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun once(context: Context?, key: String, message: String) {
        if (!onceKeys.add(key)) return
        log(context, message)
    }

    fun log(context: Context?, message: String) {
        Log.i(TAG, message)
        write(context, message)
    }

    fun warn(context: Context?, key: String, message: String, error: Throwable?) {
        Log.w(TAG, message, error)
        if (!onceKeys.add("warn:" + key)) return
        write(context, "WARN " + message + " :: " + (error?.toString() ?: "no throwable"))
    }

    private fun write(context: Context?, payload: String) {
        runCatching {
            synchronized(lock) {
                val target = resolve(context) ?: return
                if (target.length() > MAX_BYTES) target.writeText("")
                target.appendText(stamp.format(Date()) + " " + payload + "\n")
            }
        }
    }

    private fun resolve(context: Context?): File? {
        synchronized(lock) {
            if (resolved) return file
            if (context == null) return null
            resolved = true
            val app = context.applicationContext ?: context
            file = runCatching { app.getExternalFilesDir(null) }.getOrNull()?.let { File(it, FILE_NAME) }
                ?: runCatching { File(app.filesDir, FILE_NAME) }.getOrNull()
            return file
        }
    }
}
