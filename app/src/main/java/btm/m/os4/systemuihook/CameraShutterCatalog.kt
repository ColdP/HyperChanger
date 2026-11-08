// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CameraShutterCatalogEntry(val id: String, val name: String) {
    val key get() = "hc_$id"
}

internal fun decodeCameraShutterCatalog(bytes: ByteArray): List<CameraShutterCatalogEntry> {
    require(bytes.size <= 256 * 1024)
    val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("version") == 1)
    val entries = json.getJSONArray("entries"); require(entries.length() <= 64)
    val ids = mutableSetOf<String>()
    return List(entries.length()) {
        val entry = entries.getJSONObject(it)
        val id = entry.getString("id"); val name = entry.getString("name")
        require(id.matches(Regex("[a-f0-9]{64}")) && ids.add(id) && name.isNotBlank() && name.length <= 80)
        CameraShutterCatalogEntry(id, name)
    }
}

/** Reconcile the camera's integer selection by identity across catalog edits.
 * Camera-private cache metadata, not a second settings store. */
internal class CameraShutterSelectionCache(file: File) {
    private val storage = AtomicFile(file)
    private var previous = runCatching { JSONObject(storage.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    private var reconciled = false
    @Synchronized fun resolve(index: Int, keys: List<String>, requestedId: String, revision: Long, enabled: Boolean): Int {
        require(keys.isNotEmpty())
        val signature = "$enabled:$requestedId:$revision"
        val oldKeys = previous?.optJSONArray("keys")
        val sameKeys = oldKeys != null && oldKeys.length() == keys.size && keys.indices.all { oldKeys.optString(it) == keys[it] }
        if (reconciled && sameKeys && previous?.optString("request") == signature) return index.takeIf { it in keys.indices } ?: 0
        val requestChanged = previous?.optString("request") != signature
        val oldKey = oldKeys?.optString(index).orEmpty()
        val next = when {
            requestChanged && enabled && requestedId.isNotBlank() -> keys.indexOf("hc_$requestedId").takeIf { it >= 0 } ?: 0
            oldKey.isNotBlank() -> keys.indexOf(oldKey).takeIf { it >= 0 } ?: 0
            else -> index.takeIf { it in keys.indices } ?: 0
        }
        val value = JSONObject().put("keys", JSONArray(keys)).put("request", signature)
        val stream = storage.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); storage.finishWrite(stream) }
        catch (error: Exception) { storage.failWrite(stream); throw error }
        previous = value; reconciled = true
        return next
    }
}
