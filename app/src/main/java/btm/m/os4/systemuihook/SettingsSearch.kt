// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.icu.text.Transliterator
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class SearchIndexSnapshot<T>(
    val documents: List<SettingsSearchDocument<T>>,
    val index: SettingsSearchIndex<T>,
)

private data class SearchResponse<T>(
    val snapshot: SearchIndexSnapshot<T>,
    val query: String,
    val matches: List<T>,
)

/** Null means pending. Never expose the previous query/language's results as current matches. */
@Composable
internal fun <T> rememberSettingsSearch(
    documents: List<SettingsSearchDocument<T>>,
    query: String,
): List<T>? {
    val snapshot by produceState<SearchIndexSnapshot<T>?>(null, documents) {
        value = withContext(Dispatchers.Default) {
            // Each build owns its ICU instance; Transliterator is mutable and not thread-safe.
            val transliterator = runCatching {
                Transliterator.getInstance("Traditional-Simplified; Han-Latin; Latin-ASCII")
            }.getOrNull()
            SearchIndexSnapshot(documents, SettingsSearchIndex(documents) {
                transliterator?.transliterate(it) ?: it
            })
        }
    }
    val response by produceState<SearchResponse<T>?>(null, snapshot, query) {
        val current = snapshot ?: return@produceState
        if (query.isNotBlank()) delay(70)
        value = withContext(Dispatchers.Default) {
            SearchResponse(current, query, current.index.search(query).map { it.value })
        }
    }
    if (query.isBlank()) return documents.map { it.value }
    return response?.takeIf {
        it.snapshot === snapshot && it.snapshot.documents == documents && it.query == query
    }?.matches
}
