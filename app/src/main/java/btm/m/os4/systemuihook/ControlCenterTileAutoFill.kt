// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

internal data class CcFillRecord(val position: Int, val span: Int, val small: Boolean)

internal data class CcFillPlan(
    val tiles: Map<Int, CcTile>,
    val nativePositions: Set<Int>,
    val rows: Int,
)

/** Occupancy-based backfill without changing adapter positions or record ownership. */
internal fun planCcSmallTileFill(
    upper: Map<Int, CcTile>,
    upperRows: Int,
    following: List<CcFillRecord>,
    enabled: Boolean,
    nativeSpanIndex: Int,
): CcFillPlan {
    val unchanged = CcFillPlan(upper, emptySet(), upperRows)
    if (!enabled) return unchanged
    val holes = buildList {
        for (row in 0 until upperRows) for (col in 0..3) {
            if (upper.values.none { col in it.col until it.col + it.w && row in it.row until it.row + it.h }) add(col to row)
        }
    }
    val candidates = following.filter { it.small }.take(holes.size)
    if (candidates.isEmpty()) return unchanged
    var last = following.indexOf(candidates.last())
    // Keep the rest of this small-tile section together so pulling one tile upward
    // does not leave a new gap at each original row boundary. Return to OEM at a
    // native row boundary, including any partial intervening row when necessary.
    var spanIndex = nativeSpanIndex
    for (record in following.take(last + 1)) {
        if (spanIndex + record.span > 4) spanIndex = 0
        spanIndex = (spanIndex + record.span) % 4
    }
    while (last + 1 < following.size) {
        val next = following[last + 1]
        val nextIndex = if (spanIndex + next.span > 4) 0 else spanIndex
        if (!next.small && nextIndex == 0) break
        last++
        spanIndex = (nextIndex + next.span) % 4
    }
    val placed = upper.toMutableMap()
    candidates.forEachIndexed { index, record ->
        val (col, row) = holes[index]
        placed[record.position] = CcTile("small:${record.position}", col, row, 1, 1)
    }
    // Intervening device/system cards and remaining tiles retain their original order,
    // ordinary span packing and natural measurement below the fixed upper grid.
    val native = linkedSetOf<Int>()
    var row = upperRows
    var col = 0
    for (record in following.take(last + 1)) {
        if (record.position in placed) continue
        if (col + record.span > 4) { row++; col = 0 }
        placed[record.position] = CcTile("native:${record.position}", col, row, record.span, 1)
        native += record.position
        col += record.span
        if (col == 4) { row++; col = 0 }
    }
    return CcFillPlan(placed, native, row + if (col > 0) 1 else 0)
}
