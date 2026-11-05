// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.text.Normalizer
import java.util.Locale

internal data class SettingsSearchDocument<T>(
    val value: T,
    val title: String,
    val description: String,
    val scopes: List<String>,
    val keywords: String = "",
)

/** Immutable index: normalize/transliterate once, then require every query term to match. */
internal class SettingsSearchIndex<T>(
    documents: List<SettingsSearchDocument<T>>,
    transliterate: (String) -> String = { it },
) {
    private data class Entry<T>(
        val document: SettingsSearchDocument<T>,
        val title: String,
        val description: String,
        val scopes: String,
        val keywords: String,
        val phonetic: String,
        val initials: String,
    )

    private val entries = documents.distinctBy { it.value }.map { document ->
        val phonetic = normalize(transliterate(document.title + " " + document.keywords))
        Entry(document, compact(document.title), compact(document.description),
            compact(document.scopes.joinToString(" ")), compact(document.keywords),
            compact(phonetic), phonetic.split(' ').filter { it.isNotEmpty() }
                .joinToString("") { it.first().toString() })
    }

    fun search(query: String): List<SettingsSearchDocument<T>> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty()) return emptyList()
        val phrase = terms.joinToString("")
        return entries.mapNotNull { entry ->
            var score = when {
                entry.title == phrase -> 1000
                entry.title.startsWith(phrase) -> 500
                entry.title.contains(phrase) -> 250
                else -> 0
            }
            for (term in terms) {
                score += when {
                    entry.title == term -> 240
                    entry.title.startsWith(term) -> 180
                    entry.title.contains(term) -> 140
                    entry.keywords.contains(term) -> 100
                    entry.description.contains(term) -> 60
                    entry.scopes.contains(term) -> 30
                    term.length >= 2 && entry.phonetic.contains(term) -> 24
                    term.length >= 2 && entry.initials.contains(term) -> 18
                    else -> return@mapNotNull null
                }
            }
            entry.document to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    companion object {
        // NFKC accepts full-width input; ROOT avoids Turkish-I and device-locale surprises.
        private fun normalize(value: String): String = Normalizer.normalize(
            Normalizer.normalize(value, Normalizer.Form.NFKC), Normalizer.Form.NFD,
        ).lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private fun compact(value: String): String = normalize(value).replace(" ", "")
    }
}
