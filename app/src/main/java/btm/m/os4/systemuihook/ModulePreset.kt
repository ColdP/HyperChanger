// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject

private const val MODULE_PRESET_FORMAT = "hyperchanger-module-preset"
private const val MODULE_PRESET_VERSION = 1

private val MODULE_PRESET_GROUPS = listOf(
    REMOTE_PREFERENCE_GROUP,
    "settings",
    DEVICE_PROFILE_PREFERENCES,
    SETTINGS_APPEARANCE_PREFERENCES,
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

object ModulePresetCodec {
    fun export(context: Context): String {
        val groups = JSONObject()
        MODULE_PRESET_GROUPS.forEach { groupName ->
            val values = JSONObject()
            context.getSharedPreferences(groupName, Context.MODE_PRIVATE).all
                .filterKeys { it !in MODULE_PRESET_IMAGE_KEYS }
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

    fun import(
        context: Context,
        service: XposedService?,
        payload: String,
    ) {
        val root = JSONObject(payload)
        require(root.optString("format") == MODULE_PRESET_FORMAT) { "不支持的预设文件" }
        require(root.optInt("version", 0) == MODULE_PRESET_VERSION) { "不支持的预设版本" }
        val groups = root.optJSONObject("groups") ?: error("预设内容为空")
        MODULE_PRESET_GROUPS.forEach { groupName ->
            val values = groups.optJSONObject(groupName) ?: return@forEach
            applyValues(context.getSharedPreferences(groupName, Context.MODE_PRIVATE), values)
            service?.getRemotePreferences(groupName)?.let { applyValues(it, values) }
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
    ) {
        val editor = preferences.edit()
        values.keys().forEach { key ->
            if (key in MODULE_PRESET_IMAGE_KEYS) return@forEach
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
}
