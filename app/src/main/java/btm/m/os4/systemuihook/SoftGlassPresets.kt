// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class SoftGlassPresetScope(val wireName: String, val title: String) {
    ALL("all", "玻璃与背景"), GLASS("glass", "仅玻璃参数"), BACKGROUND("background", "仅背景参数"),
}

internal data class SoftGlassPreset(
    val name: String,
    val settings: SoftGlassSettings,
    val scope: SoftGlassPresetScope = SoftGlassPresetScope.ALL,
    val id: String = UUID.randomUUID().toString(),
    val builtIn: Boolean = false,
) {
    fun applyTo(current: SoftGlassSettings): SoftGlassSettings {
        val glass = if (scope != SoftGlassPresetScope.BACKGROUND) current.copy(
            adjustments = settings.adjustments, refractionBlur = settings.refractionBlur,
        ) else current
        return (if (scope != SoftGlassPresetScope.GLASS) glass.copy(
            backgroundBlur = settings.backgroundBlur, backgroundScale = settings.backgroundScale,
            backgroundOpacity = settings.backgroundOpacity, backgroundDarkening = settings.backgroundDarkening,
        ) else glass).normalized() // Never change the master switch while importing/applying.
    }
}

internal object SoftGlassPresetCodec {
    const val MAX_PAYLOAD_LENGTH = 16384

    fun encode(preset: SoftGlassPreset): String = JSONObject().apply {
        put("type", "HyperChangerSoftGlassPreset")
        put("version", 3)
        put("name", preset.name.trim().take(40))
        put("scope", preset.scope.wireName)
        val s = preset.settings.normalized()
        if (preset.scope != SoftGlassPresetScope.BACKGROUND) put("glass", JSONObject().apply {
            softGlassParameters.forEach { put(it.key, s.adjustments[it.key] ?: 0) }
            put("refraction_blur", s.refractionBlur)
        })
        if (preset.scope != SoftGlassPresetScope.GLASS) put("background", JSONObject().apply {
            put("blur", s.backgroundBlur); put("scale", s.backgroundScale)
            put("opacity", s.backgroundOpacity); put("darkening", s.backgroundDarkening)
        })
    }.toString()

    fun decode(payload: String): SoftGlassPreset {
        require(payload.length <= MAX_PAYLOAD_LENGTH)
        val root = JSONObject(payload)
        require(root.get("type") == "HyperChangerSoftGlassPreset" && root.get("version") is Number)
        val version = root.getDouble("version")
        require(version == 1.0 || version == 2.0 || version == 3.0)
        require(root.keys().asSequence().all { it in setOf("type", "version", "name", "scope", "glass", "background") })
        val scope = SoftGlassPresetScope.entries.firstOrNull { it.wireName == root.getString("scope") }
            ?: error("Unknown preset scope")
        require(root.get("name") is String)
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 40)
        val glass = if (scope != SoftGlassPresetScope.BACKGROUND) root.getJSONObject("glass") else null
        val background = if (scope != SoftGlassPresetScope.GLASS) root.getJSONObject("background") else null
        require(scope != SoftGlassPresetScope.GLASS || !root.has("background"))
        require(scope != SoftGlassPresetScope.BACKGROUND || !root.has("glass"))
        require(background == null || background.keys().asSequence().all { it in setOf("blur", "scale", "opacity", "darkening") })
        fun JSONObject.number(key: String, range: IntRange): Int {
            val raw = get(key)
            require(raw is Number)
            val n = raw.toDouble()
            require(n.isFinite() && n == raw.toInt().toDouble() && raw.toInt() in range)
            return raw.toInt()
        }
        val adjustments = glass?.let { obj ->
            require(obj.keys().asSequence().all { key ->
                softGlassParameters.any { it.key == key && (key != "edge_feather" || version >= 3.0) } ||
                    (version >= 2.0 && key == "refraction_blur")
            })
            softGlassParameters.associate {
                it.key to if (it.key == "edge_feather" && version < 3.0) 0 else obj.number(it.key, -100..200)
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
            backgroundDarkening = background?.number("darkening", 0..200) ?: 100,
        ).normalized(), scope)
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

/** An ordered progression: reduce refraction/edge shading while increasing Gaussian blur. */
internal fun builtInSoftGlassPresets(): List<SoftGlassPreset> {
    val names = listOf("通透玻璃", "清澈玻璃", "轻柔玻璃", "均衡柔光", "柔雾玻璃", "磨砂玻璃", "深度磨砂", "高斯模糊")
    val blur = listOf(0, 15, 30, 55, 80, 110, 150, 200)
    return names.mapIndexed { i, name ->
        val optical = listOf(25, 15, 0, -15, -35, -55, -80, -100)[i]
        SoftGlassPreset(name, SoftGlassSettings(
            refractionBlur = listOf(70, 85, 100, 120, 140, 160, 180, 200)[i],
            backgroundBlur = blur[i], backgroundScale = listOf(40, 50, 65, 80, 90, 100, 60, 0)[i],
            backgroundOpacity = listOf(20, 30, 40, 55, 65, 80, 90, 100)[i],
            backgroundDarkening = listOf(30, 40, 50, 60, 70, 80, 90, 100)[i],
            adjustments = mapOf(
                "luminance" to (optical.coerceAtMost(0)), "saturation" to 0,
                "alpha" to -100, "refraction" to listOf(5, 0, -10, -25, -40, -60, -80, -100)[i], "thickness" to optical,
                "reflection" to optical, "edge" to optical, "reflection_offset" to optical,
                "light" to optical, "darker" to -100, "burn" to -100,
            ),
        ).normalized(), id = "builtin-$i", builtIn = true)
    }
}
