// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
/** Resolve a whole animation's notification keys in one pass, including grouped children. */
internal object MiniPlayerRowIndex {
    fun <T : Any> build(rows: List<T>, keyOf: (T) -> String?, childrenOf: (T) -> List<T>,
                        retained: Map<String, T>): Map<String, T> {
        val result = HashMap<String, T>()
        for (row in rows) {
            keyOf(row)?.let { result.putIfAbsent(it, row) }
            for (child in childrenOf(row)) keyOf(child)?.let { result.putIfAbsent(it, row) }
        }
        // A transient row held by a morph takes priority, as findRow(key) does.
        result.putAll(retained)
        return result
    }
}
