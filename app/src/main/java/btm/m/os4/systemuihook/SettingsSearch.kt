// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
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
            SearchIndexSnapshot(documents, SettingsSearchIndex(documents))
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

internal data class SettingsSearchCatalogPage(val texts: List<String>, val titles: List<SettingsSearchPreferenceTitle>)
private data class SearchCatalogSnapshot(val language: Map<String, String>, val pages: Map<String, SettingsSearchCatalogPage>)

/** Catalogs contain UI strings and destinations only; no runtime reflection or setting values. */
@Composable
internal fun rememberSettingsSearchCatalog(language: Map<String, String>): Map<String, SettingsSearchCatalogPage>? {
    val context = LocalContext.current.applicationContext
    val catalog by produceState<SearchCatalogSnapshot?>(null, context, language) {
        value = withContext(Dispatchers.IO) {
            val json = JSONObject(context.assets.open("settings-search.json").bufferedReader().use { it.readText() })
            val pages = buildMap {
                json.keys().forEach { page ->
                    val entries = json.getJSONArray(page)
                    val texts = mutableListOf<String>()
                    val titles = mutableListOf<SettingsSearchPreferenceTitle>()
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONArray(i)
                        val key = entry.getString(0)
                        val fallback = entry.getString(1)
                        val translated = language[key] ?: language[fallback] ?: fallback
                        texts += translated
                        texts += fallback
                        if (entry.optBoolean(2)) titles += SettingsSearchPreferenceTitle(translated, listOf(fallback))
                    }
                    put(page, SettingsSearchCatalogPage(texts.distinct(), titles.distinct()))
                }
            }
            SearchCatalogSnapshot(language, pages)
        }
    }
    return catalog?.takeIf { it.language == language }?.pages
}
