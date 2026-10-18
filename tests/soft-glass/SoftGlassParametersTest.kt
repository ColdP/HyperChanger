// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import kotlin.math.abs

/** JVM regression checks using the two recipes from the supplied SystemUI dump. */
fun main() {
    val regular = floatArrayOf(
        .5f, 1f, 0f, .8f, .5f, 1.2f, 0f, .2f, 0f, 0f, .03f,
        1f, 1f, 1f, 1.5f, 0f, .6f, .6f, 1f, 62f, 3.8f, 80f,
        600f, 1f, .8f, -.4f, .6f, -.8f, 1.2f, .6f, .8f, 1.15f,
        3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )
    val dark = floatArrayOf(
        .8f, 1f, 0f, 1f, .2f, 2f, .14f, .1f, 0f, 0f, .02f,
        .27f, .27f, .27f, .6f, 0f, .2f, 1.2f, 1f, 72f, 3.8f, 80f,
        600f, 1f, .8f, -.4f, .6f, -.8f, 1.5f, 1f, .8f, 1.15f,
        3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )
    val enabled = SoftGlassSettings(enabled = true).normalized()
    for (original in listOf(regular, dark)) {
        val saved = original.copyOf()
        check(tuneSoftGlass(original, enabled).contentEquals(original))
        check(tuneSoftGlass(original, enabled.copy(enabled = false, adjustments = mapOf("alpha" to 100))).contentEquals(original))
        val halfOpacity = tuneSoftGlass(original, enabled.copy(adjustments = mapOf("alpha" to -50)))
        check(abs(halfOpacity[14] - original[14] / 2f) < .00001f)
        check(halfOpacity.indices.filter { it != 14 }.all { halfOpacity[it] == original[it] })
        check(original.contentEquals(saved)) { "Do not mutate shared framework arrays" }
        val feathered = tuneSoftGlass(original, enabled.copy(adjustments = mapOf("edge_feather" to 50)))
        check(abs(feathered[20] - original[20] * 1.5f) < .00001f)
        check(feathered.indices.filter { it != 20 }.all { feathered[it] == original[it] })
        check(tuneSoftGlass(original, enabled.copy(enabled = false, adjustments = mapOf("edge_feather" to 200))).contentEquals(original))
        for (definition in softGlassParameters) {
            for (delta in listOf(-100, -50, 0, 50, 200, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val tuned = tuneSoftGlass(original, enabled.copy(adjustments = mapOf(definition.key to delta)))
                check(tuned.all { it.isFinite() })
                if (delta != 0) check(tuned[definition.slot] in definition.bounds)
                check(tuned.indices.filter { it != definition.slot }.all { tuned[it] == original[it] })
            }
        }
    }
    val active = enabled.copy(adjustments = softGlassParameters.associate { it.key to 50 })
    check(tuneSoftGlass(FloatArray(42), active).all { it == 0f }) { "Clear must remain clear" }
    check(tuneSoftGlass(FloatArray(36) { 1f }, active).size == 36)
    check(tuneSoftGlass(FloatArray(42) { Float.NaN }, active).all { it.isNaN() })
    val extended = regular + floatArrayOf(12f, 34f)
    check(tuneSoftGlass(extended, active).takeLast(2) == listOf(12f, 34f))
    // Zero brightness defaults must respond smoothly, without HyperLight's full-unit jump.
    check(abs(tuneSoftGlass(regular, enabled.copy(adjustments = mapOf("brightness" to 1)))[6] - .01f) < .00001f)
    check(tuneSoftGlass(regular, active)[14] != tuneSoftGlass(dark, active)[14])
    check(softGlassBlur(80, 50) == 40 && softGlassBlur(30, 50) == 15)
    check(softGlassBlur(80, 0) == 0 && softGlassBlur(400, 200) == 500)
    check(softGlassBlur(700, 100) == 700 && softGlassBlur(0, 200) == 0)
    check(softGlassScale(.075f, 100) == .075f && softGlassScale(.075f, 0) == 0f)
    check(abs(softGlassScale(.075f, 200) - .15f) < .00001f)
    check(softGlassScale(Float.NaN, 50).isNaN())
    check(SoftGlassSettings(backgroundBlur = 999, backgroundScale = -1, backgroundOpacity = -1, backgroundDarkening = 999).normalized().let {
        it.backgroundBlur == 200 && it.backgroundScale == 0 && it.backgroundOpacity == 0 && it.backgroundDarkening == 200
    })
    check(softGlassColorAlpha(0x80445566.toInt(), 50) == 0x40445566)
    check(softGlassColorAlpha(0x00445566, 400) == 0x00445566)
    check(softGlassColorAlpha(0x40445566, 400) == 0xff445566.toInt())
    check(softGlassColorAlpha(0x80445566.toInt(), 100) == 0x80445566.toInt())
    check(softGlassParameters.size == 15)
    check(softGlassParameters.last().key == "darker")
    check(SoftGlassSettings(refractionBlur = -1).normalized().refractionBlur == 0)
    check(SoftGlassSettings(refractionBlur = 999).normalized().refractionBlur == 200)
    check(tuneSoftGlass(regular, enabled.copy(refractionBlur = 200)).contentEquals(regular))
    check(softGlassParameters.map { it.key }.distinct().size == softGlassParameters.size)
    check(softGlassParameters.none { it.slot in setOf(8, 9, 11, 12, 13, 18, 36, 37, 38, 39, 40, 41) })
    testPresets()
    println("Soft glass checks passed: stock defaults, light/dark recipes, clear, bounds, blur, scale, alpha, preset scopes, JSON validation, multilingual QR round trips and shared-array safety")
}

private fun testPresets() {
    val builtIn = builtInSoftGlassPresets()
    check(builtIn.size >= 8 && builtIn.map { it.id }.distinct().size == builtIn.size)
    check(builtIn.zipWithNext().all { (a, b) -> a.settings.backgroundBlur < b.settings.backgroundBlur })
    check(builtIn.zipWithNext().all { (a, b) -> a.settings.refractionBlur < b.settings.refractionBlur })
    check(builtIn.all { it.settings.adjustments["darker"] == -100 })
    val current = SoftGlassSettings(enabled = true, refractionBlur = 123, backgroundBlur = 137, backgroundScale = 88,
        backgroundOpacity = 64, backgroundDarkening = 120, adjustments = mapOf("reflection" to 34)).normalized()
    for (scope in SoftGlassPresetScope.entries) {
        val preset = builtIn.first().copy(name = "柔光テスト 🫧", scope = scope, builtIn = false)
        val payload = SoftGlassPresetCodec.encode(preset)
        checkQrRoundTrip(payload)
        val imported = SoftGlassPresetCodec.decode(payload)
        check(imported.name == preset.name && imported.scope == scope)
        check(!imported.builtIn)
        val applied = imported.applyTo(current)
        check(applied.enabled && !imported.applyTo(current.copy(enabled = false)).enabled)
        check(applied.adjustments == if (scope == SoftGlassPresetScope.BACKGROUND) current.adjustments else preset.settings.adjustments)
        check(applied.refractionBlur == if (scope == SoftGlassPresetScope.BACKGROUND) current.refractionBlur else preset.settings.refractionBlur)
        if (scope == SoftGlassPresetScope.GLASS) {
            check(applied.backgroundBlur == current.backgroundBlur && applied.backgroundScale == current.backgroundScale)
            check(applied.backgroundOpacity == current.backgroundOpacity && applied.backgroundDarkening == current.backgroundDarkening)
        } else {
            check(applied.backgroundBlur == preset.settings.backgroundBlur && applied.backgroundOpacity == preset.settings.backgroundOpacity)
        }
        val library = SoftGlassPresetCodec.decodeLibrary(SoftGlassPresetCodec.encodeLibrary(listOf(preset)))
        check(library.size == 1 && library[0].id == preset.id && library[0].scope == scope)
        check(library[0].applyTo(current) == applied)
    }
    val good = SoftGlassPresetCodec.encode(builtIn.last())
    builtIn.forEach { checkQrRoundTrip(SoftGlassPresetCodec.encode(it.copy(name = "玻璃あ🫧".repeat(8).take(40)))) }
    fun rejects(payload: String) { check(runCatching { SoftGlassPresetCodec.decode(payload) }.isFailure) { payload } }
    rejects("not json")
    rejects(good.replace("HyperChangerSoftGlassPreset", "OldMaterialPreset"))
    rejects(good.replace("\"version\":3", "\"version\":4"))
    rejects(good.replace("\"version\":3", "\"version\":1.5"))
    rejects(good.replace("\"version\":3", "\"version\":\"3\""))
    rejects(good.replace("\"edge_feather\":0", "\"edge_feather\":201"))
    rejects(good.replace("\"edge_feather\":0", "\"edge_feather\":\"50\""))
    rejects(good.replace("\"edge_feather\":0", "\"unknown_feather\":0"))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":201"))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":\"200\""))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":-1"))
    rejects(good.replace("\"refraction_blur\":200", "\"unknown_blur\":100"))
    // Existing v1/v2 JSON/QR presets and saved libraries must survive the added field.
    for (version in 1..2) for (scope in SoftGlassPresetScope.entries) {
        val legacy = org.json.JSONObject(SoftGlassPresetCodec.encode(builtIn.first().copy(scope = scope)))
        legacy.put("version", version)
        legacy.optJSONObject("glass")?.remove("edge_feather")
        if (version == 1) legacy.optJSONObject("glass")?.remove("refraction_blur")
        val imported = SoftGlassPresetCodec.decode(legacy.toString())
        val expectedBlur = when {
            scope == SoftGlassPresetScope.BACKGROUND -> 100
            version == 2 -> builtIn.first().settings.refractionBlur
            scope == SoftGlassPresetScope.ALL -> imported.settings.backgroundBlur
            else -> 100
        }
        check(imported.settings.refractionBlur == expectedBlur)
        check(imported.settings.adjustments["edge_feather"] == 0)
        check(imported.scope == scope)
        check(imported.settings.adjustments == if (scope == SoftGlassPresetScope.BACKGROUND) SoftGlassSettings().normalized().adjustments else builtIn.first().settings.adjustments)
        val library = org.json.JSONArray().put(org.json.JSONObject().put("id", "legacy").put("preset", legacy))
        check(SoftGlassPresetCodec.decodeLibrary(library.toString()).single().id == "legacy")
        checkQrRoundTrip(legacy.toString())
        legacy.put("version", 3)
        if (scope != SoftGlassPresetScope.BACKGROUND) rejects(legacy.toString())
    }
    val featherPreset = builtIn.first().copy(settings = current.copy(adjustments = current.adjustments + ("edge_feather" to 75)))
    val featherPayload = SoftGlassPresetCodec.encode(featherPreset)
    check(SoftGlassPresetCodec.decode(featherPayload).settings.adjustments["edge_feather"] == 75)
    checkQrRoundTrip(featherPayload)
    rejects(good.replace("\"blur\":200", "\"blur\":201"))
    rejects(good.replace("\"blur\":200", "\"blur\":\"200\""))
    rejects(good.replace("\"blur\":200", "\"blur\":1.5"))
    rejects(good.replace("\"scope\":\"all\"", "\"scope\":\"glass\""))
    rejects(good.replace("\"reflection\":-100", "\"reflection\":-101"))
    rejects(good.replace("\"reflection\":-100", "\"unknown\":0"))
    rejects(good.replace("\"name\":\"高斯模糊\"", "\"name\":\" \""))
    rejects(" ".repeat(SoftGlassPresetCodec.MAX_PAYLOAD_LENGTH + 1))
    check(SoftGlassPresetCodec.decodeLibrary("broken").isEmpty())
    check(SoftGlassPresetCodec.decodeLibrary("[{\"id\":\"bad\",\"preset\":{}}," + SoftGlassPresetCodec.encodeLibrary(listOf(builtIn.first())).drop(1)).size == 1)
}

/** Verify the complete, multilingual preset fits a shareable QR and decodes losslessly. */
private fun checkQrRoundTrip(payload: String) {
    val matrix = PresetQrPayload.encode(payload, 768)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    val decoded = PresetQrPayload.decode(matrix.width, matrix.height, pixels)
    check(decoded == payload)
    check(SoftGlassPresetCodec.decode(decoded).name == SoftGlassPresetCodec.decode(payload).name)
}
