// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MODULE_PRESET_FORMAT = "hyperchanger-module-preset"
private const val MODULE_PRESET_VERSION = 2
private const val MODULE_PRESET_JSON = "preset.json"
private const val MODULE_PRESET_RESOURCE_ROOT = "resources/"

private val MODULE_PRESET_GROUPS = listOf(
    REMOTE_PREFERENCE_GROUP,
    "settings",
    DEVICE_PROFILE_PREFERENCES,
    SETTINGS_APPEARANCE_PREFERENCES,
    "device_profile_presets",
    "island_simulation",
    "island_simulation_image",
    "island_simulation_effects",
    "notification_simulator",
    "media_notification_simulator",
    "status_bar_icon_import",
)

// These values identify imported files or external image selections. Keeping them out of a
// preset leaves the files already selected on the device untouched when a preset is imported.
private val MODULE_PRESET_IMAGE_KEYS = setOf(
    "raster_wallpaper_uris",
    "home_mime",
    "home_version",
    "device_mime",
    "device_version",
    "logo_mime",
    "logo_version",
    "tutorial_card_image_mime",
    "tutorial_card_image_version",
    "tutorial_card_logo_mime",
    "tutorial_card_logo_version",
    "tutorial_card_background_mime",
    "tutorial_card_background_version",
    "style2_image_mime",
    "style2_image_version",
    "style2_logo_mime",
    "style2_logo_version",
    "style2_background_mime",
    "style2_background_version",
)

private fun isExcludedFromModulePreset(key: String): Boolean =
    key in MODULE_PRESET_IMAGE_KEYS || key.startsWith("custom_depth_wallpaper_")

object ModulePresetCodec {
    data class ExportedFile(val name: String, val bytes: ByteArray)

    fun export(context: Context): String = exportJson(context)

    fun exportJson(context: Context): String {
        val groups = JSONObject()
        MODULE_PRESET_GROUPS.forEach { groupName ->
            val values = JSONObject()
            context.getSharedPreferences(groupName, Context.MODE_PRIVATE).all
                .filterKeys(::isExcludedFromModulePreset)
                .forEach { (key, value) ->
                    value ?: return@forEach
                    values.put(key, encodeValue(value))
                }
            groups.put(groupName, values)
        }
        return JSONObject()
            .put("format", MODULE_PRESET_FORMAT)
            .put("version", MODULE_PRESET_VERSION)
            .put("groups", groups)
            .toString(2)
    }

    fun exportZip(context: Context): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MODULE_PRESET_JSON))
            zip.write(exportJson(context).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            resourceFiles(context).forEach { file ->
                zip.putNextEntry(ZipEntry(MODULE_PRESET_RESOURCE_ROOT + file.name))
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    fun importBytes(context: Context, service: XposedService?, bytes: ByteArray) {
        if (!isZip(bytes)) {
            import(context, service, bytes.toString(Charsets.UTF_8))
            return
        }
        val entries = readZip(bytes)
        val preset = entries[MODULE_PRESET_JSON] ?: error("预设压缩包缺少配置文件")
        import(context, service, preset.toString(Charsets.UTF_8))
        entries.filterKeys { it.startsWith(MODULE_PRESET_RESOURCE_ROOT) }
            .forEach { (path, value) -> restoreResource(context, path.removePrefix(MODULE_PRESET_RESOURCE_ROOT), value) }
    }

    fun reset(context: Context, service: XposedService?) {
        MODULE_PRESET_GROUPS.forEach { group ->
            context.getSharedPreferences(group, Context.MODE_PRIVATE).edit().clear().apply()
            service?.getRemotePreferences(group)?.edit()?.clear()?.apply()
        }
        resourceRoots(context).forEach { it.deleteRecursively() }
    }

    fun import(
        context: Context,
        service: XposedService?,
        payload: String,
    ) {
        val root = JSONObject(payload)
        require(root.optString("format") == MODULE_PRESET_FORMAT) { "不支持的预设文件" }
        require(root.optInt("version", 0) in 1..MODULE_PRESET_VERSION) { "不支持的预设版本" }
        val groups = root.optJSONObject("groups") ?: error("预设内容为空")
        MODULE_PRESET_GROUPS.forEach { groupName ->
            val values = groups.optJSONObject(groupName) ?: return@forEach
            applyValues(context.getSharedPreferences(groupName, Context.MODE_PRIVATE), values, replace = true)
            service?.getRemotePreferences(groupName)?.let { applyValues(it, values, replace = true) }
        }
    }

    private fun encodeValue(value: Any): JSONObject = when (value) {
        is Boolean -> typed("boolean", value)
        is Int -> typed("int", value)
        is Long -> typed("long", value)
        is Float -> typed("float", value)
        is Double -> typed("double", value)
        is String -> typed("string", value)
        is Set<*> -> typed(
            "string_set",
            JSONArray(value.filterIsInstance<String>().sorted()),
        )
        else -> error("不支持的设置类型: ${value::class.java.name}")
    }

    private fun typed(type: String, value: Any): JSONObject = JSONObject()
        .put("type", type)
        .put("value", value)

    private fun applyValues(
        preferences: android.content.SharedPreferences,
        values: JSONObject,
        replace: Boolean,
    ) {
        val editor = preferences.edit()
        if (replace) preferences.all.keys.filter { !isExcludedFromModulePreset(it) && !values.has(it) }.forEach(editor::remove)
        values.keys().forEach { key ->
            if (isExcludedFromModulePreset(key)) return@forEach
            val encoded = values.optJSONObject(key) ?: error("预设字段格式错误: $key")
            when (encoded.optString("type")) {
                "boolean" -> editor.putBoolean(key, encoded.getBoolean("value"))
                "int" -> editor.putInt(key, encoded.getInt("value"))
                "long" -> editor.putLong(key, encoded.getLong("value"))
                "float" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
                "double" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
                "string" -> editor.putString(key, encoded.getString("value"))
                "string_set" -> {
                    val array = encoded.optJSONArray("value") ?: error("预设字段格式错误: $key")
                    editor.putStringSet(key, buildSet {
                        for (index in 0 until array.length()) add(array.getString(index))
                    })
                }
                else -> error("预设字段类型不支持: $key")
            }
        }
        editor.apply()
    }

    private fun resourceFiles(context: Context): List<ExportedFile> {
        val root = context.filesDir
        val candidates = listOf(
            File(root, SETTINGS_ENTRY_ICON_FILE),
            File(root, SETTINGS_MODULE_ENTRY_ICON_FILE),
            File(root, "status_bar_icons.zip"),
            File(root, "lockscreen_editor_background.bin"),
            File(root, "lockscreen_widget/preview_background.bin"),
            File(root, "lockscreen_widget/signature.bin"),
            File(root, "normal_notification_image.webp"),
            File(root, "media_notification_cover.webp"),
        ) + listOf(
            File(root, "settings_appearance"),
            File(root, "rear_screenshot_shells"),
            File(root, "lyric_library"),
        )
        return candidates.flatMap { file ->
            if (!file.exists()) emptyList() else if (file.isDirectory) {
                file.walkTopDown().filter(File::isFile).map { child ->
                    ExportedFile(child.relativeTo(root).invariantSeparatorsPath, child.readBytes())
                }.toList()
            } else listOf(ExportedFile(file.relativeTo(root).invariantSeparatorsPath, file.readBytes()))
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var total = 0L
            while (entry != null) {
                require(!entry.isDirectory)
                val name = entry.name.replace('\\', '/')
                require(name == MODULE_PRESET_JSON || name.startsWith(MODULE_PRESET_RESOURCE_ROOT)) { "预设资源路径无效" }
                require(!name.contains("../") && !name.startsWith("/")) { "预设资源路径无效" }
                val data = zip.readBytes()
                total += data.size
                require(total <= 256L * 1024L * 1024L) { "预设资源过大" }
                result[name] = data
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun restoreResource(context: Context, relative: String, bytes: ByteArray) {
        require(relative.isNotBlank() && !relative.contains("../") && !relative.startsWith('/'))
        val root = context.filesDir.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) { "预设资源路径无效" }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    private fun resourceRoots(context: Context): List<File> = listOf(
        File(context.filesDir, SETTINGS_ENTRY_ICON_FILE),
        File(context.filesDir, SETTINGS_MODULE_ENTRY_ICON_FILE),
        File(context.filesDir, "status_bar_icons.zip"),
        File(context.filesDir, "lockscreen_editor_background.bin"),
        File(context.filesDir, "lockscreen_widget"),
        File(context.filesDir, "normal_notification_image.webp"),
        File(context.filesDir, "media_notification_cover.webp"),
        File(context.filesDir, "settings_appearance"),
        File(context.filesDir, "rear_screenshot_shells"),
        File(context.filesDir, "lyric_library"),
    )
}
