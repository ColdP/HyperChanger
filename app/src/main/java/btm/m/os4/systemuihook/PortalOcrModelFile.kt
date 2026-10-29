// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal const val PORTAL_OCR_MODEL_NAME = "jpn.traineddata"
internal const val PORTAL_OCR_MODEL_BYTES = 2_471_260L
internal const val PORTAL_OCR_MODEL_SHA256 = "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"
internal const val PORTAL_OCR_MODEL_URL = "https://coldp.github.io/hyperchanger-updateinfo/models/jpn.traineddata"

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

internal fun validPortalOcrModel(file: File): Boolean {
    if (!file.isFile || file.length() != PORTAL_OCR_MODEL_BYTES) return false
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return hash.hex() == PORTAL_OCR_MODEL_SHA256
}

/** Reject partial, oversized and altered downloads before they can replace an installed model. */
internal fun transferPortalOcrModel(
    input: InputStream, output: OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit,
) {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var copied = 0L
    while (true) {
        if (cancelled()) throw CancellationException()
        val count = input.read(buffer)
        if (count < 0) break
        copied += count
        check(copied <= PORTAL_OCR_MODEL_BYTES) { "Oversized OCR model" }
        output.write(buffer, 0, count)
        hash.update(buffer, 0, count)
        progress(copied)
    }
    if (cancelled()) throw CancellationException()
    check(copied == PORTAL_OCR_MODEL_BYTES && hash.hex() == PORTAL_OCR_MODEL_SHA256) { "OCR model integrity check failed" }
}
