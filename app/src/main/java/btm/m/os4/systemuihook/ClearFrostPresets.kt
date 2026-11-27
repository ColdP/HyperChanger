// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ClearFrostPreset(
    val name: String,
    val settings: ClearFrostSettings,
    val id: String = UUID.randomUUID().toString(),
    val builtIn: Boolean = false,
) {
    fun applyTo(current: ClearFrostSettings): ClearFrostSettings =
        settings.copy(enabled = current.enabled).normalized()
}

internal object ClearFrostPresetCodec {
    const val MAX_PAYLOAD_LENGTH = 16384

    fun encode(preset: ClearFrostPreset): String = JSONObject().apply {
        put("type", "HyperChangerClearFrostPreset")
        put("version", 1)
        put("name", preset.name.trim().take(40))
        val s = preset.settings.normalized()
        put("material", JSONObject().apply {
            put("cc_theme_mode", s.controlCenterThemeMode)
            put("separate_scopes", s.separateScopes)
            put("light_style", s.lightStyle)
            put("dark_style", s.darkStyle)
            put("notif_light_style", s.notificationLightStyle)
            put("notif_dark_style", s.notificationDarkStyle)
            put("cc_light_style", s.controlCenterLightStyle)
            put("cc_dark_style", s.controlCenterDarkStyle)
            put("custom_light_1", s.customLightColor1)
            put("custom_light_2", s.customLightColor2)
            put("custom_light_3", s.customLightColor3)
            put("custom_dark_1", s.customDarkColor1)
            put("custom_dark_2", s.customDarkColor2)
            put("custom_dark_3", s.customDarkColor3)
            put("element_opacity", s.elementOpacity)
        })
        put("stroke", JSONObject().apply {
            put("enabled", s.strokeEnabled)
            put("preset", s.strokePreset)
            put("normal_width", s.strokeNormalWidth.toDouble())
            put("bloom_width", s.strokeBloomWidth.toDouble())
            put("bloom_degree", s.strokeBloomDegree)
            put("bloom_alpha", s.strokeBloomAlpha)
            put("source1_alpha", s.strokeSource1Alpha)
            put("source2_alpha", s.strokeSource2Alpha)
            put("source1_x", s.strokeSource1X.toDouble())
            put("source1_y", s.strokeSource1Y.toDouble())
            put("source1_z", s.strokeSource1Z.toDouble())
            put("source2_x", s.strokeSource2X.toDouble())
            put("source2_y", s.strokeSource2Y.toDouble())
            put("source2_z", s.strokeSource2Z.toDouble())
        })
        put("shadow", JSONObject().apply {
            put("enabled", s.shadowEnabled)
            put("alpha", s.shadowAlpha)
            put("offset_y", s.shadowOffsetY)
            put("radius", s.shadowRadius)
        })
        put("background", JSONObject().apply {
            put("blur", s.backgroundBlur)
            put("scale", s.backgroundScale)
            put("opacity", s.backgroundOpacity)
            put("custom_shade_enabled", s.customShadeBlendEnabled)
            put("custom_shade_color", s.customShadeBlendColor)
        })
    }.toString()

    fun decode(payload: String): ClearFrostPreset {
        require(payload.length <= MAX_PAYLOAD_LENGTH)
        val root = JSONObject(payload)
        require(root.optString("type") == "HyperChangerClearFrostPreset")
        require(root.optInt("version", 0) == 1)
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 40)
        val m = root.getJSONObject("material")
        val st = root.getJSONObject("stroke")
        val sh = root.getJSONObject("shadow")
        val bg = root.getJSONObject("background")
        val settings = ClearFrostSettings(
            controlCenterThemeMode = m.optInt("cc_theme_mode", CLEAR_FROST_CC_THEME_SYSTEM),
            separateScopes = m.optBoolean("separate_scopes", false),
            lightStyle = m.optString("light_style", "Colored_Thick_Light"),
            darkStyle = m.optString("dark_style", "Colored_Extra_Thin_Dark"),
            notificationLightStyle = m.optString("notif_light_style", "Colored_Thick_Light"),
            notificationDarkStyle = m.optString("notif_dark_style", "Colored_Extra_Thin_Dark"),
            controlCenterLightStyle = m.optString("cc_light_style", "Colored_Thick_Light"),
            controlCenterDarkStyle = m.optString("cc_dark_style", "Colored_Extra_Thin_Dark"),
            customLightColor1 = m.optInt("custom_light_1", CLEAR_FROST_DEFAULT_LIGHT_COLOR_1),
            customLightColor2 = m.optInt("custom_light_2", CLEAR_FROST_DEFAULT_LIGHT_COLOR_2),
            customLightColor3 = m.optInt("custom_light_3", CLEAR_FROST_DEFAULT_LIGHT_COLOR_3),
            customDarkColor1 = m.optInt("custom_dark_1", CLEAR_FROST_DEFAULT_DARK_COLOR_1),
            customDarkColor2 = m.optInt("custom_dark_2", CLEAR_FROST_DEFAULT_DARK_COLOR_2),
            customDarkColor3 = m.optInt("custom_dark_3", CLEAR_FROST_DEFAULT_DARK_COLOR_3),
            elementOpacity = m.optInt("element_opacity", 100),
            strokeEnabled = st.optBoolean("enabled", true),
            strokePreset = st.optString("preset", CLEAR_FROST_STROKE_SMALL),
            strokeNormalWidth = st.optDouble("normal_width", 2.6).toFloat(),
            strokeBloomWidth = st.optDouble("bloom_width", 0.8).toFloat(),
            strokeBloomDegree = st.optInt("bloom_degree", 180),
            strokeBloomAlpha = st.optInt("bloom_alpha", 5),
            strokeSource1Alpha = st.optInt("source1_alpha", 60),
            strokeSource2Alpha = st.optInt("source2_alpha", 35),
            strokeSource1X = st.optDouble("source1_x", 0.5).toFloat(),
            strokeSource1Y = st.optDouble("source1_y", 0.5).toFloat(),
            strokeSource1Z = st.optDouble("source1_z", -0.5).toFloat(),
            strokeSource2X = st.optDouble("source2_x", 0.5).toFloat(),
            strokeSource2Y = st.optDouble("source2_y", 0.95).toFloat(),
            strokeSource2Z = st.optDouble("source2_z", -0.5).toFloat(),
            shadowEnabled = sh.optBoolean("enabled", false),
            shadowAlpha = sh.optInt("alpha", 35),
            shadowOffsetY = sh.optInt("offset_y", 30),
            shadowRadius = sh.optInt("radius", 26),
            backgroundBlur = bg.optInt("blur", 100),
            backgroundScale = bg.optInt("scale", 100),
            backgroundOpacity = bg.optInt("opacity", 100),
            customShadeBlendEnabled = bg.optBoolean("custom_shade_enabled", false),
            customShadeBlendColor = bg.optInt("custom_shade_color", CLEAR_FROST_DEFAULT_SHADE_BLEND_COLOR),
        ).normalized()
        return ClearFrostPreset(name = name, settings = settings)
    }

    fun encodeLibrary(presets: List<ClearFrostPreset>): String = JSONArray().apply {
        presets.forEach { put(JSONObject().put("id", it.id).put("preset", JSONObject(encode(it)))) }
    }.toString()

    fun decodeLibrary(payload: String): List<ClearFrostPreset> = runCatching {
        val entries = JSONArray(payload)
        (0 until entries.length().coerceAtMost(100)).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                decode(entry.getJSONObject("preset").toString()).copy(id = entry.getString("id"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

internal fun builtInClearFrostPresets(): List<ClearFrostPreset> = listOf(
    ClearFrostPreset(
        name = "高光轻透",
        settings = ClearFrostSettings(
            lightStyle = "Colored_Thick_Light",
            darkStyle = "Colored_Extra_Thin_Dark",
            strokeEnabled = true,
            strokePreset = CLEAR_FROST_STROKE_SMALL,
            shadowEnabled = true,
            shadowAlpha = 35,
            shadowOffsetY = 30,
            shadowRadius = 26,
            backgroundBlur = 100,
            backgroundScale = 100,
            backgroundOpacity = 100,
        ).normalized(),
        id = "builtin-frost-highlight",
        builtIn = true,
    ),
    ClearFrostPreset(
        name = "冰晶清透",
        settings = ClearFrostSettings(
            lightStyle = "Pured_Thin_Glass_Light",
            darkStyle = "Pured_Thin_Glass_Dark",
            strokeEnabled = true,
            strokePreset = CLEAR_FROST_STROKE_SMALL,
            shadowEnabled = false,
            backgroundBlur = 65,
            backgroundScale = 60,
            backgroundOpacity = 75,
        ).normalized(),
        id = "builtin-frost-crystal",
        builtIn = true,
    ),
    ClearFrostPreset(
        name = "素雅磨砂",
        settings = ClearFrostSettings(
            lightStyle = "Pured_Regular_Light",
            darkStyle = "Pured_Regular_Dark",
            strokeEnabled = true,
            strokePreset = CLEAR_FROST_STROKE_MIDDLE,
            shadowEnabled = false,
            backgroundBlur = 100,
            backgroundScale = 85,
            backgroundOpacity = 90,
        ).normalized(),
        id = "builtin-frost-matte",
        builtIn = true,
    ),
    ClearFrostPreset(
        name = "厚润霜感",
        settings = ClearFrostSettings(
            lightStyle = "Colored_Extra_Thick_Light",
            darkStyle = "Colored_Thick_Dark",
            strokeEnabled = true,
            strokePreset = CLEAR_FROST_STROKE_BIG,
            shadowEnabled = true,
            shadowAlpha = 40,
            shadowOffsetY = 28,
            shadowRadius = 28,
            backgroundBlur = 130,
            backgroundScale = 100,
            backgroundOpacity = 105,
        ).normalized(),
        id = "builtin-frost-thick",
        builtIn = true,
    ),
    ClearFrostPreset(
        name = "极简通透",
        settings = ClearFrostSettings(
            lightStyle = "Colored_Extra_Thin_Light",
            darkStyle = "Colored_Extra_Thin_Dark",
            strokeEnabled = true,
            strokePreset = CLEAR_FROST_STROKE_CUSTOM,
            strokeNormalWidth = 1.6f,
            strokeBloomWidth = 0.6f,
            strokeBloomAlpha = 4,
            strokeSource1Alpha = 45,
            strokeSource2Alpha = 22,
            shadowEnabled = false,
            backgroundBlur = 45,
            backgroundScale = 40,
            backgroundOpacity = 60,
        ).normalized(),
        id = "builtin-frost-minimal",
        builtIn = true,
    ),
)
