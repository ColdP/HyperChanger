// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import io.github.libxposed.service.XposedService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal const val REAR_SCREENSHOT_SHELL_AUTHORITY = "btm.m.os4.systemuihook.rearscreenshot.shells"
internal const val KEY_REAR_SCREENSHOT_SHELL_ENABLED = "rear_screenshot_shell_enabled"
internal const val KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID = "rear_screenshot_shell_focus_id"
internal const val KEY_REAR_SCREENSHOT_SHELL_FULL_ID = "rear_screenshot_shell_full_id"
private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
private const val MAX_ZIP_BYTES = 96 * 1024 * 1024

internal enum class RearScreenshotShellMode(val key: String, val prefix: String) {
    FOCUS("focus", "half"),
    FULL("full", "full"),
}

internal data class RearScreenshotShellStyle(
    val id: String,
    val name: String,
    val mode: RearScreenshotShellMode,
    val image: ByteArray,
    val geometry: String,
) {
    val previewKey: String get() = "$id:${mode.key}"
}

internal class RearScreenshotShellStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "rear_screenshot_shells")
    private val prefs = app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    init { root.mkdirs() }

    fun list(mode: RearScreenshotShellMode): List<RearScreenshotShellStyle> = modeDir(mode).listFiles()
        .orEmpty().filter { it.isDirectory }.mapNotNull { dir -> readStyle(dir, mode) }
        .sortedBy { it.name.lowercase() }

    fun load(mode: RearScreenshotShellMode, id: String): RearScreenshotShellStyle? =
        readStyle(File(modeDir(mode), safeId(id)), mode)

    fun activeId(mode: RearScreenshotShellMode): String = prefs.getString(
        if (mode == RearScreenshotShellMode.FOCUS) KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID else KEY_REAR_SCREENSHOT_SHELL_FULL_ID,
        "",
    ).orEmpty()

    fun active(mode: RearScreenshotShellMode): RearScreenshotShellStyle? =
        activeId(mode).takeIf(String::isNotBlank)?.let { load(mode, it) }

    fun enabled(): Boolean = prefs.getBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, false)

    fun save(mode: RearScreenshotShellMode, name: String, image: ByteArray, geometry: String, id: String = UUID.randomUUID().toString()): RearScreenshotShellStyle {
        require(image.size in 1..MAX_IMAGE_BYTES) { "Shell image is too large" }
        validateShell(image, geometry)
        val safe = safeId(id)
        val dir = File(modeDir(mode), safe).apply { mkdirs() }
        File(dir, "shell.png").writeBytes(image)
        File(dir, "shell.json").writeText(geometry, Charsets.UTF_8)
        File(dir, "meta.json").writeText(JSONObject().put("name", name.trim().ifBlank { tr("rear_screenshot_untitled", "未命名") }).toString(), Charsets.UTF_8)
        return requireNotNull(readStyle(dir, mode))
    }

    fun rename(style: RearScreenshotShellStyle, name: String): RearScreenshotShellStyle {
        val dir = File(modeDir(style.mode), safeId(style.id))
        File(dir, "meta.json").writeText(
            JSONObject().put("name", name.trim().ifBlank { style.name }).toString(),
            Charsets.UTF_8,
        )
        return requireNotNull(readStyle(dir, style.mode))
    }

    fun delete(style: RearScreenshotShellStyle, service: XposedService? = null) {
        File(modeDir(style.mode), safeId(style.id)).deleteRecursively()
        val key = if (style.mode == RearScreenshotShellMode.FOCUS) KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID else KEY_REAR_SCREENSHOT_SHELL_FULL_ID
        if (prefs.getString(key, "") == style.id) {
            prefs.edit().remove(key).apply()
            service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.remove(key)?.apply()
        }
        val enabled = prefs.getString(KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID, "").orEmpty().isNotBlank() ||
            prefs.getString(KEY_REAR_SCREENSHOT_SHELL_FULL_ID, "").orEmpty().isNotBlank()
        prefs.edit().putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, enabled)?.apply()
    }

    fun select(mode: RearScreenshotShellMode, style: RearScreenshotShellStyle?, service: XposedService?) {
        val key = if (mode == RearScreenshotShellMode.FOCUS) KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID else KEY_REAR_SCREENSHOT_SHELL_FULL_ID
        fun write(target: SharedPreferences) {
            val otherKey = if (mode == RearScreenshotShellMode.FOCUS) KEY_REAR_SCREENSHOT_SHELL_FULL_ID else KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID
            val otherId = target.getString(otherKey, "").orEmpty()
            val newId = style?.id.orEmpty()
            target.edit()
                .putString(key, newId)
                .putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, newId.isNotBlank() || otherId.isNotBlank())
                .apply()
        }
        write(prefs)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun setEnabled(value: Boolean, service: XposedService?) {
        prefs.edit().putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, value).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, value)?.apply()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        remote.edit()
            .putBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, prefs.getBoolean(KEY_REAR_SCREENSHOT_SHELL_ENABLED, false))
            .putString(KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID, prefs.getString(KEY_REAR_SCREENSHOT_SHELL_FOCUS_ID, ""))
            .putString(KEY_REAR_SCREENSHOT_SHELL_FULL_ID, prefs.getString(KEY_REAR_SCREENSHOT_SHELL_FULL_ID, ""))
            .apply()
    }

    fun readUri(uri: Uri, mode: RearScreenshotShellMode): RearScreenshotShellStyle {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_ZIP_BYTES) }
            ?: error("Unable to open selected file")
        return readBytes(bytes, name, mode)
    }

    private fun readBytes(bytes: ByteArray, name: String, mode: RearScreenshotShellMode): RearScreenshotShellStyle {
        val lower = name.lowercase()
        if (!lower.endsWith(".zip") && !bytes.isZip()) {
            require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large" }
            val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Invalid shell image" }
            image.recycle()
            return RearScreenshotShellStyle("", name.substringBeforeLast('.').ifBlank { tr("rear_screenshot_imported", "导入的外壳") }, mode, bytes, defaultGeometry(mode))
        }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var total = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val key = entry.name.replace('\\', '/').substringAfterLast('/').lowercase()
                    val data = zip.readBounded(MAX_IMAGE_BYTES)
                    total += data.size
                    require(total <= MAX_ZIP_BYTES) { "Archive is too large" }
                    entries[key] = data
                }
                entry = zip.nextEntry
            }
        }
        val imageKey = entries.keys.firstOrNull { it == "${mode.prefix}_shell.png" }
            ?: entries.keys.firstOrNull { it == "shell.png" || it.endsWith("_${mode.prefix}_shell.png") }
            ?: error("Archive does not contain a ${mode.prefix} shell image")
        val jsonKey = entries.keys.firstOrNull { it == "${mode.prefix}_shell.json" }
            ?: entries.keys.firstOrNull { it == "shell.json" || it.endsWith("_${mode.prefix}_shell.json") }
        val metadata = entries["meta.json"]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)).optString("name") }.getOrNull() }
        val image = entries.getValue(imageKey)
        val geometry = jsonKey?.let { String(entries.getValue(it), Charsets.UTF_8) } ?: defaultGeometry(mode)
        validateShell(image, geometry)
        return RearScreenshotShellStyle("", metadata?.ifBlank { null } ?: name.substringBeforeLast('.').ifBlank { tr("rear_screenshot_imported", "导入的外壳") }, mode, image, geometry)
    }

    private fun validateShell(image: ByteArray, geometry: String) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L)
        val json = JSONObject(geometry)
        val width = json.getDouble("with")
        val height = json.getDouble("height")
        require(width.isFinite() && height.isFinite() && width in 1.0..16384.0 && height in 1.0..16384.0)
        listOf("leftMargin", "topMargin", "rightMargin", "bottomMargin").forEach {
            val value = json.getDouble(it)
            require(value.isFinite() && value in 0.0..16384.0)
        }
    }

    fun pngImage(style: RearScreenshotShellStyle): ByteArray {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(style.image, 0, style.image.size))
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    internal fun fileFor(uri: Uri): File? {
        val parts = uri.pathSegments
        if (parts.size < 3) return null
        val mode = RearScreenshotShellMode.entries.firstOrNull { it.key == parts[0] } ?: return null
        val style = load(mode, parts[1]) ?: return null
        return when (parts[2]) {
            "image" -> File(modeDir(mode), "${safeId(style.id)}/shell.png")
            "geometry" -> File(modeDir(mode), "${safeId(style.id)}/shell.json")
            else -> null
        }
    }

    private fun readStyle(dir: File, mode: RearScreenshotShellMode): RearScreenshotShellStyle? = runCatching {
        val image = File(dir, "shell.png").takeIf { it.isFile }?.readBytes() ?: return null
        val geometry = File(dir, "shell.json").takeIf { it.isFile }?.readText() ?: return null
        val name = File(dir, "meta.json").takeIf { it.isFile }?.readText()?.let { JSONObject(it).optString("name") }.orEmpty()
        RearScreenshotShellStyle(dir.name, name.ifBlank { tr("rear_screenshot_untitled", "未命名") }, mode, image, geometry)
    }.getOrNull()

    private fun modeDir(mode: RearScreenshotShellMode) = File(root, mode.key).apply { mkdirs() }
    private fun safeId(value: String) = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(96).ifBlank { "invalid" }
    private fun defaultGeometry(mode: RearScreenshotShellMode) = if (mode == RearScreenshotShellMode.FOCUS) {
        "{\"with\":904.0,\"height\":572.0,\"leftMargin\":116.0,\"topMargin\":933.0,\"rightMargin\":103.0,\"bottomMargin\":909.0}"
    } else {
        "{\"with\":904.0,\"height\":572.0,\"leftMargin\":114.0,\"topMargin\":106.0,\"rightMargin\":103.0,\"bottomMargin\":1653.0}"
    }
}

internal class RearScreenshotShellProvider : android.content.ContentProvider() {
    private lateinit var store: RearScreenshotShellStore
    override fun onCreate(): Boolean { store = RearScreenshotShellStore(requireNotNull(context)); return true }

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (!isAllowedCaller()) return null
        if (method != "activeStyle") return super.call(method, arg, extras)
        val mode = RearScreenshotShellMode.entries.firstOrNull { it.key == arg } ?: return null
        val style = if (store.enabled()) store.active(mode) else null
        if (style == null) return android.os.Bundle().apply { putString("id", "") }
        return android.os.Bundle().apply { putString("id", style.id) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        if (!isAllowedCaller()) return null
        val file = store.fileFor(uri) ?: return null
        return if (file.isFile) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }

    private fun isAllowedCaller(): Boolean {
        val caller = android.os.Binder.getCallingUid()
        return caller == android.os.Process.myUid() ||
            requireNotNull(context).packageManager.getPackagesForUid(caller).orEmpty().contains("com.miui.screenshot")
    }

    override fun getType(uri: Uri) = when (uri.pathSegments.lastOrNull()) {
        "image" -> "image/png"
        else -> "application/json"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun insert(uri: Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Input is too large" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun ByteArray.isZip() = size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()
