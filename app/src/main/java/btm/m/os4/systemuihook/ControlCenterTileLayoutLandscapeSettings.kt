// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE = "control_center_tile_layout_landscape_adaptive"
internal const val KEY_CC_TILE_LAYOUT_LANDSCAPE = "control_center_tile_layout_landscape_v1"
internal const val KEY_CC_TILE_LAYOUT_LANDSCAPE_SIZE_MEMORY = "control_center_tile_layout_landscape_size_memory_v1"

internal data class CcTileLayoutLandscapeSettings(
    val adaptive: Boolean = true,
    val tiles: List<CcTile> = CcTileLayouts.initial,
    val rememberedSizes: Map<String, Pair<Int, Int>> = emptyMap(),
)

/** Separate size memory for the independent landscape tile layout editor. */
internal object CcTileLandscapeSizeMemory {
    fun read(p: SharedPreferences): Map<String, Pair<Int, Int>> = runCatching {
        val json = JSONObject(p.getString(KEY_CC_TILE_LAYOUT_LANDSCAPE_SIZE_MEMORY, "{}") ?: "{}")
        json.keys().asSequence().associateWith { id ->
            json.getJSONArray(id).let { it.getInt(0) to it.getInt(1) }
        }.also { require(CcTileSizeMemory.valid(it)) }
    }.getOrDefault(emptyMap())

    fun write(p: SharedPreferences, sizes: Map<String, Pair<Int, Int>>) {
        val json = JSONObject()
        sizes.forEach { (id, size) -> json.put(id, JSONArray(listOf(size.first, size.second))) }
        p.edit().putString(KEY_CC_TILE_LAYOUT_LANDSCAPE_SIZE_MEMORY, json.toString()).apply()
    }

    fun completePreset(values: JSONObject) {
        if (!values.has(KEY_CC_TILE_LAYOUT_LANDSCAPE_SIZE_MEMORY)) {
            values.put(
                KEY_CC_TILE_LAYOUT_LANDSCAPE_SIZE_MEMORY,
                JSONObject().put("type", "string").put("value", "{}"),
            )
        }
    }
}

internal fun readCcTileLayoutLandscapeSettings(p: SharedPreferences) = CcTileLayoutLandscapeSettings(
    adaptive = p.getBoolean(KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE, true),
    tiles = CcTileLayouts.decode(p.getString(KEY_CC_TILE_LAYOUT_LANDSCAPE, null)),
    rememberedSizes = CcTileLandscapeSizeMemory.read(p),
)

/** Explicit defaults keep exported presets complete, including untouched landscape layouts. */
internal fun completeCcTileLayoutLandscapePreset(values: JSONObject) {
    if (!values.has(KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE)) {
        values.put(
            KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE,
            JSONObject().put("type", "boolean").put("value", true),
        )
    }
    if (!values.has(KEY_CC_TILE_LAYOUT_LANDSCAPE)) {
        values.put(
            KEY_CC_TILE_LAYOUT_LANDSCAPE,
            JSONObject().put("type", "string").put("value", CcTileLayouts.encode(CcTileLayouts.initial)),
        )
    }
    CcTileLandscapeSizeMemory.completePreset(values)
}

/** Kept outside HookSettings, including offline edits and remote synchronization. */
internal class ControlCenterTileLayoutLandscapeSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readCcTileLayoutLandscapeSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }

    internal fun syncPreferences(remote: SharedPreferences) {
        settings = readCcTileLayoutLandscapeSettings(local)
        write(remote)
    }

    fun update(service: XposedService?, value: CcTileLayoutLandscapeSettings) {
        require(CcTileLayouts.valid(value.tiles))
        require(CcTileSizeMemory.valid(value.rememberedSizes))
        settings = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit()
            .putBoolean(KEY_CC_TILE_LAYOUT_LANDSCAPE_ADAPTIVE, settings.adaptive)
            .putString(KEY_CC_TILE_LAYOUT_LANDSCAPE, CcTileLayouts.encode(settings.tiles))
            .apply()
        CcTileLandscapeSizeMemory.write(p, settings.rememberedSizes)
    }
}

/**
 * Resolve the effective tile layout for horizontal (two-panel landscape) Control Center.
 * Custom landscape layout only takes effect when adaptive landscape adjustment is turned off.
 */
internal fun resolveCcLandscapeTiles(
    portraitTiles: List<CcTile>,
    landscape: CcTileLayoutLandscapeSettings,
): List<CcTile> = if (landscape.adaptive) {
    adaptCcTilesForLandscape(portraitTiles)
} else {
    landscape.tiles
}

/**
 * Deterministically adapt a portrait 4-column upper layout for HyperOS landscape right-panel.
 * Strictly preserves every tile's original (w, h) size and ONLY adjusts (col, row) positions
 * to eliminate unnecessary gaps/rows while staying as close as possible to the portrait order.
 */
internal fun adaptCcTilesForLandscape(portrait: List<CcTile>): List<CcTile> {
    if (!CcTileLayouts.valid(portrait)) return CcTileLayouts.initial

    val ordered = portrait.sortedWith(compareBy({ it.row }, { it.col }))
    val candidates = listOf(
        ordered,
        compactVertically(ordered),
        packTight(ordered),
        // Defer full-width (4-col) tiles so taller narrow tiles (e.g. sliders) can fill side gaps.
        packTight(ordered.sortedWith(compareBy({ it.w == 4 }, { it.row }, { it.col }))),
        packTight(ordered.sortedWith(compareBy({ -it.h }, { -it.w }, { it.row }, { it.col }))),
        packTight(ordered.sortedWith(compareBy({ -it.w }, { -it.h }, { it.row }, { it.col }))),
    ).map { restoreOrder(portrait, it) }.filter { candidate ->
        CcTileLayouts.valid(candidate) && sameTileSizes(portrait, candidate)
    }

    val minRows = candidates.minOfOrNull(::usedRows) ?: return portrait
    if (usedRows(portrait) <= minRows && interiorHoles(portrait) == 0) return portrait

    val best = candidates.minWithOrNull(
        compareBy<List<CcTile>>(
            { usedRows(it) },
            { interiorHoles(it) },
            { totalDisplacement(portrait, it) },
        ),
    ) ?: portrait
    return if (sameTileSizes(portrait, best)) best else portrait
}

private fun usedRows(tiles: List<CcTile>): Int = tiles.maxOfOrNull { it.row + it.h } ?: 0

private fun sameTileSizes(original: List<CcTile>, candidate: List<CcTile>): Boolean {
    if (original.size != candidate.size) return false
    val byId = original.associateBy { it.id }
    return candidate.all { t ->
        val o = byId[t.id] ?: return@all false
        t.w == o.w && t.h == o.h
    }
}

private fun interiorHoles(tiles: List<CcTile>): Int {
    val rows = usedRows(tiles)
    if (rows <= 0) return 0
    val occupied = Array(rows) { BooleanArray(4) }
    tiles.forEach { t ->
        for (r in t.row until t.row + t.h) {
            for (c in t.col until t.col + t.w) occupied[r][c] = true
        }
    }
    var holes = 0
    for (r in 0 until rows) {
        val lastRow = r == rows - 1
        val lastOccupiedColInRow = (3 downTo 0).firstOrNull { occupied[r][it] } ?: -1
        for (c in 0..3) {
            if (!occupied[r][c] && (!lastRow || c < lastOccupiedColInRow)) holes++
        }
    }
    return holes
}

private fun totalDisplacement(original: List<CcTile>, candidate: List<CcTile>): Int {
    val byId = original.associateBy { it.id }
    return candidate.sumOf { t ->
        val o = byId.getValue(t.id)
        kotlin.math.abs(t.col - o.col) + kotlin.math.abs(t.row - o.row)
    }
}

private fun overlapsTile(a: CcTile, b: CcTile): Boolean =
    a.col < b.col + b.w && a.col + a.w > b.col && a.row < b.row + b.h && a.row + a.h > b.row

private fun compactVertically(ordered: List<CcTile>): List<CcTile> {
    val placed = mutableListOf<CcTile>()
    ordered.forEach { t ->
        var r = t.row
        while (r > 0 && placed.none { overlapsTile(t.copy(row = r - 1), it) }) r--
        placed.add(t.copy(row = r))
    }
    return placed
}

private fun packTight(ordered: List<CcTile>): List<CcTile> {
    val placed = mutableListOf<CcTile>()
    ordered.forEach { t ->
        val slot = (0..127).asSequence()
            .flatMap { r -> (0..4 - t.w).asSequence().map { c -> t.copy(col = c, row = r) } }
            .first { candidate -> placed.none { overlapsTile(candidate, it) } }
        placed.add(slot)
    }
    return placed
}

private fun restoreOrder(original: List<CcTile>, adapted: List<CcTile>): List<CcTile> {
    val byId = adapted.associateBy { it.id }
    val result = original.map { byId.getValue(it.id) }
    return if (CcTileLayouts.valid(result)) result else original
}
