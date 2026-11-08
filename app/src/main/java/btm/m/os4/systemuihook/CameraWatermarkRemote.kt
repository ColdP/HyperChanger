// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject

internal const val CAMERA_WATERMARK_REMOTE_INDEX = "camera_watermark_catalog.json"
internal fun cameraWatermarkRemoteFile(id: String) = "camera_watermark_$id.zip"
internal fun cameraWatermarkNativeId(id: String) = (900000000L + id.take(7).toLong(16)).toString()
internal data class CameraWatermarkRemoteEntry(val id: String, val name: String, val kind: String)

internal fun decodeCameraWatermarkCatalog(bytes: ByteArray): List<CameraWatermarkRemoteEntry> {
    val catalog = JSONObject(bytes.toString(Charsets.UTF_8))
    require(catalog.getInt("version") == 1)
    val items = catalog.getJSONArray("entries")
    require(items.length() <= 512)
    val ids = mutableSetOf<String>()
    return List(items.length()) { index ->
        val item = items.getJSONObject(index)
        val id = item.getString("id")
        val name = item.getString("name")
        val kind = item.getString("kind")
        require(id.matches(Regex("[a-f0-9]{64}")) && ids.add(id))
        require(name.isNotBlank() && name.length <= 80 && kind in setOf("photo", "video"))
        CameraWatermarkRemoteEntry(id, name, kind)
    }
}

/** Framework-owned files are readable by scoped hooks without a background app provider. */
internal object CameraWatermarkRemote {
    private val lock = Any()

    fun publish(context: Context, service: XposedService) = synchronized(lock) {
        val entries = CameraWatermarkLibrary(context).list()
        val existing = service.listRemoteFiles().toSet()
        val desired = mutableSetOf(CAMERA_WATERMARK_REMOTE_INDEX)
        val index = JSONArray()
        for (entry in entries) {
            val file = cameraWatermarkRemoteFile(entry.id)
            desired.add(file)
            if (file !in existing) {
                val pack = CameraWatermarkZip.decode(entry.archive.readBytes())
                require(pack.id == entry.id && pack.kind == entry.kind)
                write(service, file, pack.bytes)
            }
            index.put(JSONObject().put("id", entry.id).put("name", entry.name).put("kind", entry.kind))
        }
        // Publish the complete index last. A failed/incomplete read never clears camera imports.
        write(service, CAMERA_WATERMARK_REMOTE_INDEX, JSONObject().put("version", 1).put("entries", index).toString().toByteArray())
        existing.filter { it.startsWith("camera_watermark_") && it.endsWith(".zip") && it !in desired }
            .forEach { check(service.deleteRemoteFile(it)) }
        ModuleLog.i("CameraWatermark", "Published ${entries.size} watermark packages through framework files")
    }

    private fun write(service: XposedService, name: String, bytes: ByteArray) {
        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use {
            it.channel.truncate(0)
            it.write(bytes)
            it.fd.sync()
        }
    }
}
