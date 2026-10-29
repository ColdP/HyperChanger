package btm.m.os4.systemuihook

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException

private fun selectedText(words: List<PortalOcrWord>): String = buildString {
    words.forEachIndexed { index, word ->
        append(word.word)
        if (word.needBlank && index < words.lastIndex) append(' ')
    }
}

fun main(args: Array<String>) {
    // The enhancement uses one model set for Chinese, Japanese, English and mixed text.
    for (text in listOf("中文识别测试", "繁體中文辨識", "日本語の認識", "漢字", "English 123", "中文と日本語 English")) {
        val result = JSONObject(checkNotNull(portalOcrJson(text)))
        val words = (0 until result.getJSONArray("wordsList").length()).map { index ->
            val item = result.getJSONArray("wordsList").getJSONObject(index)
            PortalOcrWord(item.getString("word"), item.getBoolean("needBlank"))
        }
        check(selectedText(words) == text)
    }
    val samples = listOf(
        "今日はいい天気です。カタカナとひらがな、日本語の認識テスト！",
        "日本語 OCR test 123 と English、2026年。",
        "ｶﾀｶﾅ・ヴァイオリン・パーティー",
        "𠮷野家で🍣を食べます。",
        "「こんにちは」\"世界\"\\テスト。",
    )
    for (sample in samples) {
        val words = portalOcrWords(sample)
        check(words.isNotEmpty())
        check(selectedText(words) == sample) { "Lost or reordered text: $sample -> $words" }
        words.forEach { word ->
            check(!word.word.first().isLowSurrogate() && !word.word.last().isHighSurrogate())
        }
        val result = JSONObject(checkNotNull(portalOcrJson(sample)))
        val list = result.getJSONArray("wordsList")
        check(list.length() == words.size && result.getJSONArray("cards").length() == 0)
        words.forEachIndexed { index, word ->
            val json = list.getJSONObject(index)
            check(json.getString("word") == word.word && json.getBoolean("needBlank") == word.needBlank)
        }
    }
    check(selectedText(portalOcrWords("日本語\n認識\tテスト")) == "日本語 認識 テスト")
    check(portalOcrJson(" \n\t") == null)
    check(portalOcrJson("あ".repeat(16_385)) == null)

    val migrationRoot = java.nio.file.Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "portal-migration-").toFile()
    try {
        val legacy = File(migrationRoot, "portal-ocr/tessdata").apply { mkdirs() }
        val current = File(migrationRoot, "portal-ocr-multilingual-v1/models/tessdata").apply { mkdirs() }
        File(legacy, "jpn.traineddata").writeText("old model")
        File(legacy, "jpn.traineddata.part").writeText("old partial")
        File(legacy, "unrelated").writeText("preserve")
        File(current, "jpn.traineddata").writeText("new model")
        check(removeLegacyPortalOcrModel(migrationRoot))
        check(!File(legacy, "jpn.traineddata").exists() && !File(legacy, "jpn.traineddata.part").exists())
        check(File(legacy, "unrelated").readText() == "preserve")
        check(File(current, "jpn.traineddata").readText() == "new model")
        check(removeLegacyPortalOcrModel(migrationRoot)) // Repeated startup is harmless.
    } finally {
        migrationRoot.deleteRecursively()
    }

    val sink = OutputStream.nullOutputStream()
    check(runCatching {
        transferPortalOcrModel(ByteArrayInputStream(ByteArray(100)), sink, { false }, {})
    }.isFailure) { "Truncated model accepted" }
    check(runCatching {
        transferPortalOcrModel(ByteArrayInputStream(ByteArray(PORTAL_OCR_MODEL_BYTES.toInt()) { 1 }), sink, { false }, {})
    }.isFailure) { "Corrupt model accepted" }
    check(runCatching {
        transferPortalOcrModel(ByteArrayInputStream(ByteArray(PORTAL_OCR_MODEL_BYTES.toInt() + 1)), sink, { false }, {})
    }.isFailure) { "Oversized model accepted" }
    var transferred = 0L
    val cancellation = runCatching {
        transferPortalOcrModel(ByteArrayInputStream(ByteArray(100_000)), sink, { transferred > 0 }) { transferred = it }
    }.exceptionOrNull()
    check(cancellation is CancellationException && transferred in 1..99_999)
    if (args.isNotEmpty()) {
        val model = File(args[0])
        check(validPortalOcrModel(model)) { "Model rejected: ${model.absolutePath}, ${model.length()} bytes" }
        val output = ByteArrayOutputStream()
        model.inputStream().use { input -> transferPortalOcrModel(input, output, { false }, {}) }
        check(output.toByteArray().contentEquals(model.readBytes()))
        val unpacked = File(model.parentFile, "test-unpacked").also { it.deleteRecursively() }
        unpackPortalOcrModel(model, unpacked) { false }
        check(validPortalOcrLanguages(unpacked))
        File(unpacked, "tessdata/chi_sim.traineddata").appendText("corrupt")
        check(!validPortalOcrLanguages(unpacked))
        check(runCatching { unpackPortalOcrModel(model, unpacked) { true } }.exceptionOrNull() is CancellationException)
        unpacked.deleteRecursively()
    }
    println("Portal OCR text, JSON, model integrity and cancellation checks passed")
}
