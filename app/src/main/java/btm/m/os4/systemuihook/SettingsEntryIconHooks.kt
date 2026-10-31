// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.io.File

/** One cache owner per Settings installation, independent of the existing label hook. */
internal class SettingsEntryIconHooks {
    private var revision = Long.MIN_VALUE
    private var icons: Map<String, Bitmap> = emptyMap()
    private var moduleRevision = Long.MIN_VALUE
    private var moduleIcon: Bitmap? = null

    fun install(module: XposedModule, loader: ClassLoader, prefs: SharedPreferences) {
        val adapter = loader.loadClass("com.android.settings.MiuiSettings\$HeaderAdapter")
        // Ignore the synthetic RecyclerView bridge, which delegates to this typed method.
        val bind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterCount == 2 &&
                it.parameterTypes[0].name == "com.android.settings.MiuiSettings\$HeaderViewHolder" &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        val getItem = adapter.getMethod("getItem", Int::class.javaPrimitiveType)
        module.hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:settings-entry-icons").intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val nextRevision = readSettingsEntryIconRevision(prefs)
                    val moduleSettings = readSettingsModuleEntryIconSettings(prefs)
                    if (nextRevision == 0L && !moduleSettings.enabled) return@runCatching
                    val holder = chain.getArg(0) ?: return@runCatching
                    val icon = holder.javaClass.getField("icon").get(holder) as? ImageView ?: return@runCatching
                    if (icon.visibility != View.VISIBLE) return@runCatching
                    val header = getItem.invoke(chain.thisObject, chain.getArg(1)) ?: return@runCatching
                    val headerId = header.javaClass.getField("id").getLong(header)
                    if (headerId == SETTINGS_MODULE_ENTRY_HEADER_ID) {
                        if (!moduleSettings.enabled) return@runCatching
                        if (moduleRevision != moduleSettings.revision) {
                            val loaded = icon.context.contentResolver.openInputStream(
                                Uri.parse("content://$SETTINGS_ENTRY_ICON_AUTHORITY/module"),
                            )?.use { decodeSettingsEntryIcon(it.readBytes()) } ?: return@runCatching
                            moduleIcon = loaded
                            moduleRevision = moduleSettings.revision
                        }
                        moduleIcon?.let { icon.setImageBitmap(it) }
                        return@runCatching
                    }
                    if (nextRevision == 0L) return@runCatching
                    if (revision != nextRevision) {
                        val loaded = icon.context.contentResolver.openInputStream(
                            Uri.parse("content://$SETTINGS_ENTRY_ICON_AUTHORITY/icons"),
                        )?.use(::readSettingsEntryIconArchive) ?: return@runCatching
                        icons = loaded
                        revision = nextRevision
                    }
                    if (icons.isEmpty()) return@runCatching
                    val id = headerId.toInt()
                    val resource = header.javaClass.getField("iconRes").getInt(header)
                    val headerName = runCatching { icon.resources.getResourceEntryName(id) }.getOrNull()
                    val resourceName = runCatching { icon.resources.getResourceEntryName(resource) }.getOrNull()
                    val entry = settingsEntryIconCatalog.firstOrNull { headerName in it.headerIds }
                        ?: settingsEntryIconCatalog.firstOrNull { resourceName in it.resources }
                        ?: return@runCatching
                    icons[entry.id]?.let { icon.setImageBitmap(it) }
                }
                result
            }
    }
}

/** Read-only IPC for small, normalized icon bitmaps; never exposes arbitrary app files. */
class SettingsEntryIconProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() || app.packageManager.getPackagesForUid(uid).orEmpty().contains("com.android.settings")
        if (mode != "r" || !allowed) throw SecurityException("Settings icons are read-only")
        val name = when (uri.pathSegments) {
            listOf("icons") -> SETTINGS_ENTRY_ICON_FILE
            listOf("module") -> SETTINGS_MODULE_ENTRY_ICON_FILE
            else -> throw java.io.FileNotFoundException("Unknown icon resource")
        }
        return ParcelFileDescriptor.open(File(app.filesDir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = if (uri.pathSegments == listOf("module")) "image/png" else "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
