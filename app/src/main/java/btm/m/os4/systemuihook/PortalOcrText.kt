// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator
import java.util.Locale

internal data class PortalOcrWord(val word: String, val needBlank: Boolean = false)

/** Match WordBean's whitespace contract without sending Japanese through Chinese segmentation. */
internal fun portalOcrWords(text: String): List<PortalOcrWord> {
    val boundaries = BreakIterator.getWordInstance(Locale.JAPANESE).apply { setText(text) }
    val words = mutableListOf<PortalOcrWord>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        val part = text.substring(start, end)
        if (part.all { it.isWhitespace() }) {
            if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(needBlank = true)
        } else {
            words.add(PortalOcrWord(part))
        }
        start = end
        end = boundaries.next()
    }
    return words
}

/** Pair(false, this JSON) is Portal's existing structured-result path, also used by TalkBack. */
internal fun portalOcrJson(text: String): String? {
    if (text.isBlank() || text.length > 16_384) return null
    val words = portalOcrWords(text)
    if (words.isEmpty()) return null
    val list = JSONArray()
    words.forEach { list.put(JSONObject().put("word", it.word).put("needBlank", it.needBlank)) }
    val result = JSONObject().put("wordsList", list).put("cards", JSONArray()).toString()
    // Stay comfortably below Binder's shared transaction-buffer limit.
    return result.takeIf { it.toByteArray(Charsets.UTF_8).size <= 384 * 1024 }
}
