// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

internal const val PORTAL_OCR_MODEL_NAME = "portal-ocr-eng-chi_sim-jpn.zip"
internal const val PORTAL_OCR_LANGUAGES = "eng+chi_sim+jpn"
internal const val PORTAL_OCR_MODEL_BYTES = 5_222_282L
internal const val PORTAL_OCR_MODEL_SHA256 = "cc6dc661ada9c96c8ddf3ee6e9a5a870ce409b4f5f94264392062571c8146b56"
internal const val PORTAL_OCR_MODEL_URL = "https://coldp.github.io/hyperchanger-updateinfo/models/portal-ocr-eng-chi_sim-jpn.zip"

/** Remove only the obsolete Japanese-only download, never the new multilingual files. */
internal fun removeLegacyPortalOcrModel(noBackupFilesDir: File): Boolean =
    listOf("jpn.traineddata", "jpn.traineddata.part").map { name ->
        val old = File(noBackupFilesDir, "portal-ocr/tessdata/$name")
        !old.exists() || old.delete()
    }.all { it }

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

internal data class PortalOcrLanguageFile(val bytes: Long, val sha256: String)
internal val portalOcrLanguageFiles = mapOf(
    "eng.traineddata" to PortalOcrLanguageFile(4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
    "chi_sim.traineddata" to PortalOcrLanguageFile(2_469_156, "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730"),
    "jpn.traineddata" to PortalOcrLanguageFile(2_471_260, "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"),
)

internal fun validPortalOcrLanguages(root: File): Boolean = portalOcrLanguageFiles.all { (name, spec) ->
    val file = File(root, "tessdata/$name")
    file.isFile && file.length() == spec.bytes && file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.hex() == spec.sha256
    }
}

/** Fixed entry allowlist and size/hash checks prevent traversal and oversized extraction. */
internal fun unpackPortalOcrModel(archive: File, destination: File, cancelled: () -> Boolean) {
    check(validPortalOcrModel(archive))
    val tessdata = File(destination, "tessdata")
    check(tessdata.mkdirs() || tessdata.isDirectory)
    val seen = mutableSetOf<String>()
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val spec = requireNotNull(portalOcrLanguageFiles[entry.name])
            check(!entry.isDirectory && seen.add(entry.name))
            File(tessdata, entry.name).outputStream().use { output ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var count = 0L
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val read = zip.read(buffer)
                    if (read < 0) break
                    count += read
                    check(count <= spec.bytes)
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                check(count == spec.bytes && digest.hex() == spec.sha256)
            }
            zip.closeEntry()
        }
    }
    if (cancelled()) throw CancellationException()
    check(seen == portalOcrLanguageFiles.keys && validPortalOcrLanguages(destination))
}
