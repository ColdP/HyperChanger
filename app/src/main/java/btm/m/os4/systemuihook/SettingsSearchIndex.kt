// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.text.Normalizer
import java.util.Locale

internal data class SettingsSearchPreferenceTitle(val title: String, val aliases: List<String>)

internal data class SettingsSearchDocument<T>(
    val value: T,
    val title: String,
    val description: String,
    val scopes: List<String>,
    val keywords: String = "",
    val features: List<String> = emptyList(),
    val destination: String? = null,
)

/** Immutable index: normalize once, then require every query term to match. */
internal class SettingsSearchIndex<T>(
    documents: List<SettingsSearchDocument<T>>,
) {
    private data class Entry<T>(
        val document: SettingsSearchDocument<T>,
        val title: String,
        val description: String,
        val scopes: String,
        val keywords: String,
        val features: List<String>,
    )

    private val entries = documents.distinctBy { it.value }.map { document ->
        Entry(document, compact(document.title), compact(document.description),
            compact(document.scopes.joinToString(" ")), compact(document.keywords),
            document.features.map(::compact))
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
                    entry.features.any { it == term } -> 130
                    entry.features.any { it.contains(term) } -> 110
                    entry.keywords.contains(term) -> 100
                    entry.description.contains(term) -> 60
                    entry.scopes.contains(term) -> 30
                    else -> return@mapNotNull null
                }
            }
            entry.document to score
        }.sortedByDescending { it.second }
            // Rank aliases first, then keep one result per actual navigation destination.
            // Application-list callers omit destinations and retain their value identity.
            .distinctBy { it.first.destination?.let { key -> true to key } ?: (false to it.first.value) }
            .map { it.first }
    }

    companion object {
        /** Display a real matching preference title, excluding instructions and summaries. */
        fun preferenceTitle(query: String, titles: List<SettingsSearchPreferenceTitle>, fallback: String): String {
            val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
            if (terms.isEmpty()) return fallback
            val phrase = terms.joinToString("")
            return titles.map { preference ->
                val aliases = (preference.aliases + preference.title).map(::compact)
                val score = aliases.maxOfOrNull { alias ->
                    (when {
                        alias == phrase -> 1000
                        alias.startsWith(phrase) -> 500
                        alias.contains(phrase) -> 250
                        else -> 0
                    }) + terms.sumOf { term -> if (alias.contains(term)) term.length * 10 else 0 }
                } ?: 0
                preference.title to score
            }.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: fallback
        }

        // NFKC accepts full-width input; ROOT avoids Turkish-I and device-locale surprises.
        private fun normalize(value: String): String = Normalizer.normalize(
            Normalizer.normalize(value, Normalizer.Form.NFKC), Normalizer.Form.NFD,
        ).lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private fun compact(value: String): String = normalize(value).replace(" ", "")
    }
}
