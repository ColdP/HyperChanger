// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.AtomicFile
import io.github.libxposed.service.XposedService
import java.io.File

internal const val SETTINGS_MODULE_ENTRY_HEADER_ID = 0x4843_0001L
internal const val SETTINGS_MODULE_ENTRY_ICON_FILE = "settings_module_entry_icon.png"
private const val KEY_ENABLED = "settings_module_entry_icon_enabled"
private const val KEY_REVISION = "settings_module_entry_icon_revision"

internal data class SettingsModuleEntryIconSettings(val enabled: Boolean, val revision: Long)

internal fun readSettingsModuleEntryIconSettings(prefs: SharedPreferences) = SettingsModuleEntryIconSettings(
    prefs.getBoolean(KEY_ENABLED, false), prefs.getLong(KEY_REVISION, 0L),
)

/** Separate from dashboard batch imports and their restore-all action. */
internal class SettingsModuleEntryIconStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    private val file = AtomicFile(File(app.filesDir, SETTINGS_MODULE_ENTRY_ICON_FILE))

    fun load(): Bitmap? = if (!readSettingsModuleEntryIconSettings(prefs).enabled) null else runCatching {
        file.openRead().use { decodeSettingsEntryIcon(it.readBytes()) }
    }.getOrNull()

    fun save(bitmap: Bitmap?, service: XposedService?) {
        if (bitmap != null) {
            val output = file.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
        val previous = readSettingsModuleEntryIconSettings(prefs)
        check(prefs.edit().putBoolean(KEY_ENABLED, bitmap != null)
            .putLong(KEY_REVISION, maxOf(System.currentTimeMillis(), previous.revision + 1)).commit())
        syncRemote(service)
        if (bitmap == null) file.delete()
    }

    fun syncRemote(service: XposedService?) {
        val settings = readSettingsModuleEntryIconSettings(prefs)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_ENABLED, settings.enabled)?.putLong(KEY_REVISION, settings.revision)?.apply()
    }
}
