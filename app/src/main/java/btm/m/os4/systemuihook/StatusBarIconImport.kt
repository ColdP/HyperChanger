// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.util.AtomicFile
import io.github.libxposed.service.XposedService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val STATUS_BAR_ICON_AUTHORITY = "btm.m.os4.systemuihook.statusbaricons"
internal const val KEY_STATUS_BAR_ICON_IMPORT_ENABLED = "status_bar_icon_import_enabled"
internal const val KEY_STATUS_BAR_ICON_IMPORT_REVISION = "status_bar_icon_import_revision"
internal const val KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE = "system_ui_resource_import_scope"
internal const val SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR = 1
internal const val SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER = 2
internal const val SYSTEM_UI_RESOURCE_SCOPE_ALL =
    SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR or SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER
private const val ICON_PREFS = "status_bar_icon_import"
private const val FILE_NAME = "status_bar_icons.zip"

internal data class StatusBarIconImport(
    val name: String,
    val signal: Bitmap?,
    val wifi: Bitmap?,
    val battery: Bitmap?,
    val archive: ByteArray,
)

internal class StatusBarIconImportStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(ICON_PREFS, Context.MODE_PRIVATE)
    private val archive = AtomicFile(File(app.filesDir, FILE_NAME))

    fun load(): StatusBarIconImport? = runCatching {
        val bytes = archive.openRead().use { it.readBytes() }
        preview(prefs.getString("file_name", null) ?: return null, bytes)
    }.getOrNull()

    fun read(uri: Uri): StatusBarIconImport {
        val source = app.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(MAX_ARCHIVE_BYTES) }
            ?: error("Cannot open selected file")
        require(source.size <= MAX_ARCHIVE_BYTES) { "Archive is too large" }
        val output = ByteArrayOutputStream()
        val written = LinkedHashSet<String>()
        val importedBytes = intArrayOf(0)
        ZipOutputStream(output).use { zip ->
            scanZip(source, "com.android.systemui", 0, written, importedBytes, zip)
        }
        require(written.isNotEmpty()) { "No supported status bar or quick setting icons" }
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: "com.android.systemui"
        return preview(name, output.toByteArray())
    }

    fun save(value: StatusBarIconImport?, service: XposedService?) {
        val local = app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val enabled: Boolean
        val revision = System.currentTimeMillis()
        if (value == null) {
            archive.delete()
            prefs.edit().clear().apply()
            enabled = false
        } else {
            val stream = archive.startWrite()
            try {
                stream.write(value.archive)
                archive.finishWrite(stream)
            } catch (error: Throwable) {
                archive.failWrite(stream)
                throw error
            }
            prefs.edit().putString("file_name", value.name).apply()
            enabled = true
        }
        fun write(target: android.content.SharedPreferences) = target.edit()
            .putBoolean(KEY_STATUS_BAR_ICON_IMPORT_ENABLED, enabled)
            .putLong(KEY_STATUS_BAR_ICON_IMPORT_REVISION, revision).apply()
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun loadScope(): Int = localScope().getInt(
        KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE,
        SYSTEM_UI_RESOURCE_SCOPE_ALL,
    ).coerceIn(0, SYSTEM_UI_RESOURCE_SCOPE_ALL)

    fun saveScope(scope: Int, service: XposedService?) {
        val value = scope and SYSTEM_UI_RESOURCE_SCOPE_ALL
        fun write(target: android.content.SharedPreferences) = target.edit()
            .putInt(KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE, value)
            .apply()
        write(localScope())
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    fun syncRemote(service: XposedService) {
        val local = app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        service.getRemotePreferences(REMOTE_PREFERENCE_GROUP).edit()
            .putBoolean(KEY_STATUS_BAR_ICON_IMPORT_ENABLED, archive.baseFile.isFile && local.getBoolean(KEY_STATUS_BAR_ICON_IMPORT_ENABLED, false))
            .putLong(KEY_STATUS_BAR_ICON_IMPORT_REVISION, local.getLong(KEY_STATUS_BAR_ICON_IMPORT_REVISION, 0L))
            .putInt(KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE, local.getInt(KEY_SYSTEM_UI_RESOURCE_IMPORT_SCOPE, SYSTEM_UI_RESOURCE_SCOPE_ALL))
            .apply()
    }

    private fun localScope(): android.content.SharedPreferences =
        app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    private fun scanZip(
        bytes: ByteArray,
        defaultPackage: String,
        depth: Int,
        written: MutableSet<String>,
        importedBytes: IntArray,
        output: ZipOutputStream,
    ) {
        require(depth <= 2) { "Nested archive is too deep" }
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val path = entry.name.replace('\\', '/').removePrefix("/")
                    val leaf = path.substringAfterLast('/').lowercase()
                    val component = leaf == "com.android.systemui" || leaf == "miui.systemui.plugin"
                    val wrapper = leaf.endsWith(".zip") || leaf.endsWith(".mtz")
                    val normalized = normalize(path, defaultPackage)
                    if (!component && !wrapper && normalized == null) {
                        entry = input.nextEntry
                        continue
                    }
                    val data = input.readBytesBounded(if (component || wrapper) MAX_ARCHIVE_BYTES else MAX_ENTRY_BYTES)
                    if (component && data.isZip()) {
                        scanZip(data, leaf, depth + 1, written, importedBytes, output)
                    } else if (wrapper && data.isZip()) {
                        scanZip(data, defaultPackage, depth + 1, written, importedBytes, output)
                    } else {
                        normalized?.let {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000L)
                            if (written.add(normalized)) {
                                require(importedBytes[0] + data.size <= MAX_ARCHIVE_BYTES) { "Imported icons are too large" }
                                output.putNextEntry(ZipEntry(normalized))
                                output.write(data)
                                output.closeEntry()
                                importedBytes[0] += data.size
                            }
                        }
                    }
                }
                entry = input.nextEntry
            }
        }
    }

    private fun normalize(path: String, packageName: String): String? {
        val lower = path.lowercase()
        val resAt = lower.indexOf("res/")
        if (resAt < 0) return null
        val relative = path.substring(resAt + 4)
        val folder = relative.substringBefore('/', "")
        val file = relative.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("png", "webp", "jpg", "jpeg")) return null
        val type = folder.substringBefore('-')
        if (type !in setOf("drawable", "mipmap", "raw")) return null
        val name = file.substringBeforeLast('.')
        if (!isSupported(name)) return null
        val night = if (lower.contains("nightmode/") || folder.contains("night")) "night" else "day"
        val pkg = if (path.lowercase().contains("miui.systemui.plugin")) "miui.systemui.plugin" else packageName
        return "$pkg/$night/$folder/$name.$ext"
    }

    private fun isSupported(name: String): Boolean =
        name.startsWith("stat_sys_") || name.startsWith("ic_cc_qs_") || name.startsWith("ic_qs_") ||
            name.startsWith("status_bar_") || name.startsWith("statusbar_")

    private fun preview(name: String, bytes: ByteArray): StatusBarIconImport {
        val selected = arrayOfNulls<Pair<String, ByteArray>>(3)
        fun rank(path: String): Int {
            val entry = path.substringAfterLast('/').substringBeforeLast('.')
            return (if ("/day/" in path) 100 else 0) +
                (if (entry in setOf("stat_sys_signal_4", "stat_sys_wifi_signal_4", "stat_sys_battery")) 50 else 0) +
                (if ("darkmode" !in entry) 10 else 0)
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val key = entry.name.lowercase()
                val index = when {
                    key.contains("signal_4") && !key.contains("wifi") -> 0
                    key.contains("wifi_signal_4") -> 1
                    key.contains("stat_sys_battery") -> 2
                    else -> -1
                }
                if (index >= 0 && (selected[index] == null || rank(key) > rank(selected[index]!!.first))) {
                    selected[index] = key to input.readBytes()
                }
                entry = input.nextEntry
            }
        }
        fun decode(index: Int): Bitmap? = selected[index]?.let { (path, data) ->
            BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
                if (index == 2 && "/raw" in path) cropBatteryFrame(it, path) else it
            }
        }
        return StatusBarIconImport(name, decode(0), decode(1), decode(2), bytes)
    }

    private fun cropBatteryFrame(bitmap: Bitmap, path: String): Bitmap {
        // Match BatteryIcon's current density-dependent sprite row height.
        val frameHeight = when {
            "xxxhdpi" in path -> 72
            "xxhdpi" in path -> 60
            "xhdpi" in path -> 50
            "hdpi" in path || "mdpi" in path || "ldpi" in path -> 38
            else -> 60
        }
        if (bitmap.height <= frameHeight) return bitmap
        return Bitmap.createBitmap(bitmap, 0, bitmap.height - bitmap.height % frameHeight - frameHeight, bitmap.width, frameHeight)
    }

    private fun ByteArray.isZip() = size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Archive entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 24 * 1024 * 1024
    }
}

class StatusBarIconProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val providerContext = requireNotNull(context)
        val callerUid = Binder.getCallingUid()
        val allowed = callerUid == Process.SYSTEM_UID || callerUid == Process.myUid() ||
            providerContext.packageManager.getPackagesForUid(callerUid).orEmpty().any {
                it == "com.android.systemui" || it == "miui.systemui.plugin"
            }
        if (mode != "r" || !allowed) return null
        val file = File(providerContext.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
