// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SoftGlassPreset(
    val name: String,
    val settings: SoftGlassSettings,
    val id: String = UUID.randomUUID().toString(),
    val builtIn: Boolean = false,
) {
    fun applyTo(current: SoftGlassSettings): SoftGlassSettings {
        return settings.copy(enabled = current.enabled).normalized()
    }
}

internal object SoftGlassPresetCodec {
    const val MAX_PAYLOAD_LENGTH = 16384

    fun encode(preset: SoftGlassPreset): String = JSONObject().apply {
        put("type", "HyperChangerSoftGlassPreset")
        put("version", 5)
        put("name", preset.name.trim().take(40))
        val s = preset.settings.normalized()
        put("glass", JSONObject().apply {
            softGlassParameters.forEach { put(it.key, s.adjustments[it.key] ?: 0) }
            put("refraction_blur", s.refractionBlur)
        })
        put("background", JSONObject().apply {
            put("blur", s.backgroundBlur); put("scale", s.backgroundScale)
            put("opacity", s.backgroundOpacity)
        })
    }.toString()

    fun decode(payload: String): SoftGlassPreset {
        require(payload.length <= MAX_PAYLOAD_LENGTH)
        val root = JSONObject(payload)
        require(root.get("type") == "HyperChangerSoftGlassPreset" && root.get("version") is Number)
        val version = root.getDouble("version")
        require(version in setOf(1.0, 2.0, 3.0, 4.0, 5.0))
        val rootKeys = setOf("type", "version", "name", "glass", "background") + if (version < 4.0) setOf("scope") else emptySet()
        require(root.keys().asSequence().all { it in rootKeys })
        // Old partial presets become complete presets, with stock values for their
        // missing half. Keep custom libraries/imports usable without retaining scopes.
        val scope = if (version < 4.0) root.getString("scope") else "all"
        require(scope in setOf("all", "glass", "background"))
        require(root.get("name") is String)
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 40)
        val glass = if (scope != "background") root.getJSONObject("glass") else null
        val background = if (scope != "glass") root.getJSONObject("background") else null
        require(scope != "glass" || !root.has("background"))
        require(scope != "background" || !root.has("glass"))
        val backgroundKeys = setOf("blur", "scale", "opacity") + if (version < 4.0) setOf("darkening") else emptySet()
        require(background == null || background.keys().asSequence().all { it in backgroundKeys })
        fun JSONObject.number(key: String, range: IntRange): Int {
            val raw = get(key)
            require(raw is Number)
            val n = raw.toDouble()
            require(n.isFinite() && n == raw.toInt().toDouble() && raw.toInt() in range)
            return raw.toInt()
        }
        if (version < 4.0 && background != null) background.number("darkening", 0..200)
        val adjustments = glass?.let { obj ->
            require(obj.keys().asSequence().all { key ->
                softGlassParameters.any { it.key == key && (key != "edge_feather" || version >= 3.0) } ||
                    (version < 5.0 && key == "reflection") ||
                    (version >= 2.0 && key == "refraction_blur")
            })
            val legacyReflection = if (version < 5.0) obj.number("reflection", -100..200) else 0
            softGlassParameters.associate { definition ->
                definition.key to if (definition.key == "edge_feather" && version < 5.0) {
                    val oldFeather = if (version >= 3.0) obj.number(definition.key, -100..200) else 0
                    migrateSoftGlassFeather(legacyReflection, if (version >= 4.0) oldFeather else 0)
                } else obj.number(definition.key, definition.adjustmentRange)
            }
        }.orEmpty()
        return SoftGlassPreset(name, SoftGlassSettings(
            adjustments = adjustments,
            refractionBlur = when {
                glass == null -> 100
                version >= 2.0 -> glass.number("refraction_blur", 0..200)
                else -> background?.number("blur", 0..200) ?: 100
            },
            backgroundBlur = background?.number("blur", 0..200) ?: 100,
            backgroundScale = background?.number("scale", 0..200) ?: 100,
            backgroundOpacity = background?.number("opacity", 0..200) ?: 100,
        ).normalized())
    }

    fun encodeLibrary(presets: List<SoftGlassPreset>): String = JSONArray().apply {
        presets.forEach { put(JSONObject().put("id", it.id).put("preset", JSONObject(encode(it)))) }
    }.toString()

    fun decodeLibrary(payload: String): List<SoftGlassPreset> = runCatching {
        val entries = JSONArray(payload)
        (0 until entries.length().coerceAtMost(100)).mapNotNull { index -> runCatching {
            val entry = entries.getJSONObject(index)
            decode(entry.getJSONObject("preset").toString()).copy(id = entry.getString("id"))
        }.getOrNull() }
    }.getOrDefault(emptyList())
}

/** User-tuned A–F recipes, ordered from clear to blurred by their visual appearance. */
internal fun builtInSoftGlassPresets(): List<SoftGlassPreset> = listOf(
    // A.json
    SoftGlassPreset("澄光", SoftGlassSettings(
        refractionBlur = 13, backgroundBlur = 12, backgroundScale = 32, backgroundOpacity = 53,
        adjustments = mapOf(
            "luminance" to 0, "saturation" to 0, "brightness" to 0,
            "alpha" to 0, "refraction" to 0, "thickness" to 0,
            "background_saturation" to 0, "background_brightness" to -38, "burn" to 0,
            "edge" to 200, "edge_feather" to 75, "reflection_offset" to 0,
            "light" to 0, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-a", builtIn = true),
    // B.json
    SoftGlassPreset("薄纱", SoftGlassSettings(
        refractionBlur = 22, backgroundBlur = 29, backgroundScale = 32, backgroundOpacity = 53,
        adjustments = mapOf(
            "luminance" to 0, "saturation" to 0, "brightness" to 0,
            "alpha" to 0, "refraction" to 0, "thickness" to 0,
            "background_saturation" to 0, "background_brightness" to -38, "burn" to 0,
            "edge" to 200, "edge_feather" to 75, "reflection_offset" to 0,
            "light" to 0, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-b", builtIn = true),
    // C.json
    SoftGlassPreset("浮影", SoftGlassSettings(
        refractionBlur = 44, backgroundBlur = 36, backgroundScale = 0, backgroundOpacity = 73,
        adjustments = mapOf(
            "luminance" to -79, "saturation" to -20, "brightness" to 5,
            "alpha" to 110, "refraction" to 200, "thickness" to -16,
            "background_saturation" to 107, "background_brightness" to -59, "burn" to 80,
            "edge" to 200, "edge_feather" to 100, "reflection_offset" to 200,
            "light" to 31, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-c", builtIn = true),
    // D.json
    SoftGlassPreset("朦光", SoftGlassSettings(
        refractionBlur = 55, backgroundBlur = 28, backgroundScale = 0, backgroundOpacity = 73,
        adjustments = mapOf(
            "luminance" to -79, "saturation" to -20, "brightness" to 5,
            "alpha" to 4, "refraction" to 132, "thickness" to -16,
            "background_saturation" to 107, "background_brightness" to -59, "burn" to 62,
            "edge" to 126, "edge_feather" to 100, "reflection_offset" to 200,
            "light" to 31, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-d", builtIn = true),
    // E.json
    SoftGlassPreset("凝雾", SoftGlassSettings(
        refractionBlur = 115, backgroundBlur = 81, backgroundScale = 0, backgroundOpacity = 73,
        adjustments = mapOf(
            "luminance" to -79, "saturation" to -20, "brightness" to 5,
            "alpha" to 4, "refraction" to 132, "thickness" to -16,
            "background_saturation" to 107, "background_brightness" to -59, "burn" to 62,
            "edge" to 126, "edge_feather" to 100, "reflection_offset" to 200,
            "light" to 31, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-e", builtIn = true),
    // F.json
    SoftGlassPreset("云隐", SoftGlassSettings(
        refractionBlur = 117, backgroundBlur = 74, backgroundScale = 32, backgroundOpacity = 59,
        adjustments = mapOf(
            "luminance" to 0, "saturation" to 0, "brightness" to 0,
            "alpha" to 0, "refraction" to 0, "thickness" to 0,
            "background_saturation" to 0, "background_brightness" to -51, "burn" to 0,
            "edge" to 48, "edge_feather" to 75, "reflection_offset" to 0,
            "light" to 0, "darker" to -100,
        ),
    ).normalized(), id = "builtin-custom-f", builtIn = true),
)
