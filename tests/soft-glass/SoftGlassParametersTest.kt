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
        val standalone = tuneSoftGlass(original, enabled.copy(backgroundOpacity = 50), standaloneBackground = true)
        check(standalone[18] == original[18])
        check(tuneSoftGlass(original, enabled.copy(backgroundOpacity = 50), standaloneBackground = false)[18] == original[18])
        val contextLift = tuneSoftGlass(original, enabled, contextBrightness = .48f)
        check(abs(contextLift[34] - (original[34] + .48f)) < .00001f)
        // Heads-up rows retain the platform light/dark recipe; the SystemUI hook must not
        // inject the unified-context brightness compensation into either mode.
        val headsUp = tuneSoftGlass(original, enabled, standaloneBackground = true, contextBrightness = 0f)
        check(headsUp[34] == original[34])
        check(original.contentEquals(saved)) { "Do not mutate shared framework arrays" }
        val feathered = tuneSoftGlass(original, enabled.copy(adjustments = mapOf("edge_feather" to 50)))
        check(feathered[24] < original[24]) { "Reduce reflected rim contrast" }
        check(abs(feathered[24] - original[24] * .5f) < .00001f)
        check(tuneSoftGlass(original, enabled.copy(adjustments = mapOf("edge_feather" to 100)))[24] == 0f)
        check(abs(tuneSoftGlass(original, enabled.copy(adjustments = mapOf("edge_feather" to -50)))[24] - original[24] * 1.5f) < .00001f)
        // Curves 17/31 caused perceived backdrop zoom on device. Even extreme feather
        // values must leave every sampling, refraction, reflection and shape input alone.
        for (feather in listOf(-100, -50, 0, 50, 100, 200)) {
            val tuned = tuneSoftGlass(original, enabled.copy(adjustments = mapOf("edge_feather" to feather)))
            check(tuned.indices.filter { it != 24 }.all { tuned[it] == original[it] })
        }
        val noHighlights = original.copyOf().apply { this[24] = 0f }
        check(tuneSoftGlass(noHighlights, enabled.copy(adjustments = mapOf("edge_feather" to 200))).contentEquals(noHighlights))
        check(tuneSoftGlass(original, enabled.copy(enabled = false, adjustments = mapOf("edge_feather" to 200))).contentEquals(original))
        for (definition in softGlassParameters) {
            for (delta in listOf(-100, -50, 0, 50, 200, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val tuned = tuneSoftGlass(original, enabled.copy(adjustments = mapOf(definition.key to delta)))
                check(tuned.all { it.isFinite() })
                if (delta != 0) check(tuned[definition.slot] in definition.bounds)
                val changedSlots = setOf(definition.slot)
                check(tuned.indices.filter { it !in changedSlots }.all { tuned[it] == original[it] })
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
    check(SoftGlassSettings(backgroundBlur = 999, backgroundScale = -1, backgroundOpacity = -1).normalized().let {
        it.backgroundBlur == 200 && it.backgroundScale == 0 && it.backgroundOpacity == 0
    })
    check(softGlassColorAlpha(0x80445566.toInt(), 50) == 0x40445566)
    check(softGlassColorAlpha(0x00445566, 400) == 0x00445566)
    check(softGlassColorAlpha(0x80445566.toInt(), 200) == 0xff445566.toInt())
    check(softGlassColorAlpha(0x80445566.toInt(), 100) == 0x80445566.toInt())
    check(softGlassParameters.size == 14)
    check(softGlassParameters.none { it.key == "reflection" })
    check(migrateSoftGlassFeather(50) == -50)
    check(softGlassParameters.last().key == "darker")
    check(SoftGlassSettings(refractionBlur = -1).normalized().refractionBlur == 0)
    check(SoftGlassSettings(refractionBlur = 999).normalized().refractionBlur == 200)
    check(tuneSoftGlass(regular, enabled.copy(refractionBlur = 200)).contentEquals(regular))
    check(softGlassParameters.map { it.key }.distinct().size == softGlassParameters.size)
    check(softGlassParameters.none { it.slot in setOf(8, 9, 11, 12, 13, 18, 36, 37, 38, 39, 40, 41) })
    testPresets()
    testVolumeRecipeCache(regular)
    testPresetPersistence()
    println("Soft glass checks passed: preserved rim geometry, light/dark recipes, clear, bounds, blur/scale/alpha, unified presets, legacy migration, JSON validation, multilingual QR and shared-array safety")
}

private fun testVolumeRecipeCache(stock: FloatArray) {
    val cache = SoftGlassRecipeCache()
    val settings = SoftGlassSettings(enabled = true, adjustments = mapOf("edge_feather" to 50)).normalized()
    val mutableInput = stock.copyOf()
    val first = cache.tune(mutableInput, settings)
    val baseline = cache.baseline
    repeat(10000) {
        check(cache.tune(mutableInput, settings) === first)
        check(cache.baseline === baseline) { "Stable drag frames must not copy or rebuild the recipe" }
    }
    check(cache.tune(first, settings) === first) { "Do not compound a renderer's reused output array" }
    mutableInput[28] = stock[28] / 2f
    val animated = cache.tune(mutableInput, settings)
    check(animated !== first && animated.contentEquals(tuneSoftGlass(mutableInput, settings))) { "Do not freeze in-place system animations" }
    val changed = settings.copy(adjustments = mapOf("edge_feather" to 200))
    check(cache.tune(mutableInput, changed).contentEquals(tuneSoftGlass(mutableInput, changed)))
    check(cache.tune(mutableInput, changed.copy(enabled = false)).contentEquals(mutableInput))
    check(cache.tune(FloatArray(42), settings).all { it == 0f }) { "Clear invalidates the cached glass" }
    check(cache.tune(stock, settings).contentEquals(tuneSoftGlass(stock, settings))) { "Recreate after clear" }
    check(stock.contentEquals(baseline!!)) { "The cache must not mutate caller inputs" }
}

private fun testPresets() {
    val builtIn = builtInSoftGlassPresets()
    check(builtIn.size == 6 && builtIn.map { it.id }.distinct().size == builtIn.size)
    check(builtIn.zipWithNext().all { (a, b) -> a.settings.refractionBlur < b.settings.refractionBlur })
    check(builtIn.all { it.settings.adjustments["darker"] == -100 })
    val current = SoftGlassSettings(enabled = true, refractionBlur = 123, backgroundBlur = 137, backgroundScale = 88,
        backgroundOpacity = 64, adjustments = mapOf("edge_feather" to 34)).normalized()
    val preset = builtIn.first().copy(name = "柔光テスト 🫧", builtIn = false)
    val payload = SoftGlassPresetCodec.encode(preset)
    checkQrRoundTrip(payload)
    val imported = SoftGlassPresetCodec.decode(payload)
    check(imported.name == preset.name && !imported.builtIn)
    val applied = imported.applyTo(current)
    check(applied == preset.settings.copy(enabled = true))
    check(imported.applyTo(current.copy(enabled = false)) == preset.settings.copy(enabled = false))
    val json = org.json.JSONObject(payload)
    check(json.getInt("version") == 5 && !json.has("scope"))
    check(json.has("glass") && json.has("background") && !json.getJSONObject("background").has("darkening"))
    val library = SoftGlassPresetCodec.decodeLibrary(SoftGlassPresetCodec.encodeLibrary(listOf(preset)))
    check(library.single().id == preset.id && library.single().applyTo(current) == applied)
    // Boundary validation uses its own fixture, independent of the visual presets.
    val good = SoftGlassPresetCodec.encode(builtIn.last().copy(name = "Validation", settings = current.copy(
        refractionBlur = 200, backgroundBlur = 200, adjustments = current.adjustments + ("edge_feather" to 100),
    )))
    builtIn.forEach { checkQrRoundTrip(SoftGlassPresetCodec.encode(it.copy(name = "玻璃あ🫧".repeat(8).take(40)))) }
    fun rejects(payload: String) { check(runCatching { SoftGlassPresetCodec.decode(payload) }.isFailure) { payload } }
    rejects("not json")
    rejects(good.replace("HyperChangerSoftGlassPreset", "OldMaterialPreset"))
    rejects(good.replace("\"version\":5", "\"version\":6"))
    rejects(good.replace("\"version\":5", "\"version\":1.5"))
    rejects(good.replace("\"version\":5", "\"version\":\"3\""))
    rejects(good.replace("\"edge_feather\":100", "\"edge_feather\":201"))
    rejects(good.replace("\"edge_feather\":100", "\"edge_feather\":\"50\""))
    rejects(good.replace("\"edge_feather\":100", "\"unknown_feather\":0"))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":201"))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":\"200\""))
    rejects(good.replace("\"refraction_blur\":200", "\"refraction_blur\":-1"))
    rejects(good.replace("\"refraction_blur\":200", "\"unknown_blur\":100"))
    // v1-v3 partial/full presets must migrate without retaining a hidden scope.
    for (version in 1..3) for (scope in listOf("all", "glass", "background")) {
        val legacy = org.json.JSONObject(SoftGlassPresetCodec.encode(builtIn.first()))
        legacy.put("version", version).put("scope", scope)
        legacy.optJSONObject("glass")?.put("reflection", 0)
        if (scope == "glass") legacy.remove("background")
        if (scope == "background") legacy.remove("glass")
        legacy.optJSONObject("background")?.put("darkening", 70)
        if (version < 3) legacy.optJSONObject("glass")?.remove("edge_feather")
        if (version == 1) legacy.optJSONObject("glass")?.remove("refraction_blur")
        val migrated = SoftGlassPresetCodec.decode(legacy.toString())
        val expectedBlur = when {
            scope == "background" -> 100
            version >= 2 -> builtIn.first().settings.refractionBlur
            scope == "all" -> migrated.settings.backgroundBlur
            else -> 100
        }
        check(migrated.settings.refractionBlur == expectedBlur)
        check(migrated.settings.adjustments["edge_feather"] == 0) { "Discard incompatible legacy edgePow adjustment" }
        val expectedBackground = if (scope == "glass") 100 else builtIn.first().settings.backgroundBlur
        check(migrated.applyTo(current).backgroundBlur == expectedBackground)
        check(migrated.applyTo(current).adjustments == migrated.settings.adjustments)
        if (scope == "background") check(migrated.applyTo(current).adjustments.values.all { it == 0 })
        val saved = SoftGlassPresetCodec.encode(migrated)
        check(!org.json.JSONObject(saved).has("scope"))
        check(SoftGlassPresetCodec.decode(saved).applyTo(current) == migrated.applyTo(current))
        val oldLibrary = org.json.JSONArray().put(org.json.JSONObject().put("id", "legacy").put("preset", legacy))
        check(SoftGlassPresetCodec.decodeLibrary(oldLibrary.toString()).single().id == "legacy")
        checkQrRoundTrip(legacy.toString())
    }
    val featherPreset = builtIn.first().copy(settings = current.copy(adjustments = current.adjustments + ("edge_feather" to 75)))
    val legacyV4 = org.json.JSONObject(SoftGlassPresetCodec.encode(featherPreset)).put("version", 4)
    legacyV4.getJSONObject("glass").put("reflection", -50).put("edge_feather", 100)
    check(SoftGlassPresetCodec.decode(legacyV4.toString()).settings.adjustments["edge_feather"] == 75)
    val featherPayload = SoftGlassPresetCodec.encode(featherPreset)
    check(SoftGlassPresetCodec.decode(featherPayload).settings.adjustments["edge_feather"] == 75)
    checkQrRoundTrip(featherPayload)
    rejects(good.replace("\"blur\":200", "\"blur\":201"))
    rejects(good.replace("\"blur\":200", "\"blur\":\"200\""))
    rejects(good.replace("\"blur\":200", "\"blur\":1.5"))
    rejects(org.json.JSONObject(good).put("scope", "glass").toString())
    rejects(org.json.JSONObject(good).apply { remove("background") }.toString())
    rejects(org.json.JSONObject(good).apply { remove("glass") }.toString())
    rejects(org.json.JSONObject(good).apply { getJSONObject("background").put("darkening", 100) }.toString())
    rejects(good.replace("\"edge_feather\":100", "\"edge_feather\":-201"))
    rejects(org.json.JSONObject(good).apply { getJSONObject("glass").put("reflection", 0) }.toString())
    rejects(good.replace("\"name\":\"Validation\"", "\"name\":\" \""))
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
