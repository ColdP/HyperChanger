// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal const val CAMERA_WATERMARK_DIRECTORY = "camera_watermarks"
internal const val WATERMARK_MAX_ZIP = 32 * 1024 * 1024
internal data class CameraWatermarkPackage(
    val id: String, val name: String, val kind: String, val bytes: ByteArray,
    val preview: ByteArray, val resources: Map<String, ByteArray>,
)
internal data class SavedCameraWatermark(val id: String, val name: String, val kind: String, val directory: File) {
    val preview get() = File(directory, "preview.png")
    val archive get() = File(directory, "package.zip")
}

internal object CameraWatermarkZip {
    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun safePath(path: String): Boolean = path.isNotBlank() && path.length <= 240 &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    fun decode(bytes: ByteArray): CameraWatermarkPackage {
        require(bytes.size <= WATERMARK_MAX_ZIP)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { require(safePath(entry.name.trimEnd('/'))); continue }
                require(safePath(entry.name) && entry.name !in entries && entries.size < 512)
                val data = readBounded(zip, 16 * 1024 * 1024)
                total += data.size
                require(total <= 64L * 1024 * 1024)
                entries[entry.name] = data
            }
        }
        val manifest = JSONObject(requireNotNull(entries.remove("watermark-package.json")).toString(Charsets.UTF_8))
        require(manifest.getString("format") in setOf("hyperchanger-watermark", "camera-watermark-authoring"))
        require(manifest.getInt("version") == 1)
        val kind = manifest.getString("kind")
        require(kind == "photo" || kind == "video")
        val files = manifest.getJSONArray("files")
        val listed = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val path = item.getString("path")
            val full = if (path.startsWith("resources/")) path else "resources/$path"
            require(listed.add(full))
            val data = requireNotNull(entries[full])
            require(item.getLong(if (item.has("size")) "size" else "bytes") == data.size.toLong() && item.getString("sha256").equals(hash(data), true))
        }
        require(listed == entries.keys)
        require(entries.keys.all { it.startsWith("resources/") && it.substringAfterLast('.').lowercase() in setOf("json", "png", "webp", "jpg", "jpeg", "ttf", "otf", "txt") })
        val resources = entries.mapKeys { it.key.removePrefix("resources/") }
        val config = JSONObject(requireNotNull(resources["config.json"]).toString(Charsets.UTF_8))
        require(config.has("layout_group") && config.getJSONArray("layout_group").length() in 1..32)
        require(config.getJSONObject("metadata").optString("id").isNotBlank())
        // Native @file= and direct image references must stay inside the package.
        fun validate(node: Any?) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val value = node.opt(key)
                    if (value is String && (key == "path" || key == "typeface" || key == "path_mivi")) {
                        require(!value.contains("..") && !value.contains('\\') && !value.contains("content:") && !value.contains("file:"))
                        if (value.startsWith("@file=")) require(value.removePrefix("@file=") in resources)
                        if (value.startsWith("pathType=direct;")) {
                            val path = value.split(';').firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
                            require(path != null && safePath(path) && path in resources)
                        }
                    }
                    validate(value)
                }
                is JSONArray -> for (i in 0 until node.length()) validate(node.opt(i))
            }
        }
        resources.filterKeys { it.endsWith(".json") }.values.forEach { validate(JSONObject(it.toString(Charsets.UTF_8))) }
        val previewName = manifest.optString("preview", "preview.png").removePrefix("resources/")
        require(safePath(previewName))
        val preview = requireNotNull(resources[previewName])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(preview, 0, preview.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16000000)
        val name = manifest.optString("name").ifBlank { config.getJSONObject("metadata").optString("description") }.take(80)
        require(name.isNotBlank())
        return CameraWatermarkPackage(hash(bytes), name, kind, bytes, preview, resources)
    }
}

internal class CameraWatermarkLibrary(private val context: Context) {
    private val root get() = File(context.filesDir, "$CAMERA_WATERMARK_DIRECTORY/entries").apply { mkdirs() }
    fun list(): List<SavedCameraWatermark> = root.listFiles().orEmpty().mapNotNull { directory ->
        if (!directory.name.matches(Regex("[a-f0-9]{64}"))) return@mapNotNull null
        runCatching {
            val info = JSONObject(File(directory, "entry.json").readText())
            SavedCameraWatermark(directory.name, info.getString("name"), info.getString("kind"), directory)
                .takeIf { it.archive.isFile && it.preview.isFile }
        }.getOrNull()
    }.sortedBy { it.name }
    fun save(value: CameraWatermarkPackage) {
        val directory = File(root, value.id)
        if (directory.exists()) return
        val stage = File(root, ".${value.id}.pending")
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs())
        try {
            File(stage, "package.zip").writeBytes(value.bytes)
            File(stage, "preview.png").writeBytes(value.preview)
            File(stage, "entry.json").writeText(JSONObject().put("name", value.name).put("kind", value.kind).toString())
            check(stage.renameTo(directory))
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }
    fun delete(value: SavedCameraWatermark) {
        val directory = File(root, value.id).canonicalFile
        require(directory.parentFile == root.canonicalFile && value.id.matches(Regex("[a-f0-9]{64}")))
        check(!directory.exists() || directory.deleteRecursively())
    }
}
