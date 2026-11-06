// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class CcPreviewMedia(val title: String?, val artist: String?, val artwork: Bitmap?)

internal object CcPreviewMediaReader {
    private const val PREFIX = "CC_MEDIA_PREVIEW_V1:"
    private const val MAX_OUTPUT = 1024 * 1024

    /** Failure stops retries for this page visit, avoiding repeated denied root prompts. */
    suspend fun read(context: Context): CcPreviewMedia? = withContext(Dispatchers.IO) {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} exec /system/bin/toybox timeout 10 " +
            "/system/bin/app_process /system/bin btm.m.os4.systemuihook.CcPreviewMediaRoot " +
            (android.os.Process.myUid() / 100000)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            val output = async(Dispatchers.IO) {
                runInterruptible {
                    val bytes = process.inputStream.readNBytes(MAX_OUTPUT + 1)
                    check(bytes.size <= MAX_OUTPUT) { "Media preview response too large" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            val finished = runInterruptible(Dispatchers.IO) { process.waitFor(20, TimeUnit.SECONDS) }
            check(finished && process.exitValue() == 0) { "Media preview root helper unavailable" }
            val frame = output.await().lineSequence().lastOrNull { it.startsWith(PREFIX) }
                ?: error("Missing media preview response")
            val json = JSONObject(frame.removePrefix(PREFIX))
            fun text(key: String) = json.optString(key).trim().take(2048).takeIf { it.isNotEmpty() }
            val art = runCatching {
                val bytes = Base64.decode(json.optString("art"), Base64.DEFAULT)
                // Validate dimensions before allocating pixels, even for a local helper response.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..384 || bounds.outHeight !in 1..384) null
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            val title = text("title"); val artist = text("artist")
            if (title == null && artist == null && art == null) null else CcPreviewMedia(title, artist, art)
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
