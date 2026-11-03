// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_CC_TILE_LAYOUT_ENABLED = "control_center_tile_layout_enabled"
internal const val KEY_CC_TILE_LAYOUT = "control_center_tile_layout_v1"
internal const val KEY_CC_MEDIA_SOURCE_ICON_HIDDEN = "control_center_media_source_icon_hidden"
internal const val KEY_CC_SMALL_TILE_AUTO_FILL = "control_center_small_tile_auto_fill"

internal data class CcTile(val id: String, val col: Int, val row: Int, val w: Int, val h: Int) {
    val slider get() = id == "brightness" || id == "volume"
}

/** Shared, deterministic grid model used by both the offline editor and plugin layout. */
internal object CcTileLayouts {
    val initial = listOf(
        CcTile("wlan", 0, 0, 2, 1), CcTile("data", 2, 0, 2, 1),
        CcTile("media", 0, 1, 2, 2), CcTile("brightness", 2, 1, 1, 2), CcTile("volume", 3, 1, 1, 2),
    )
    fun rows(tiles: List<CcTile>) = maxOf(3, tiles.maxOf { it.row + it.h })

    fun valid(tiles: List<CcTile>): Boolean = tiles.size == initial.size &&
        tiles.map { it.id }.toSet() == initial.map { it.id }.toSet() && tiles.all { t ->
            t.w in 1..4 && t.h in 1..(if (t.id == "media") 3 else 2) &&
                (!t.slider || (t.w == 1 && t.h == 2)) && t.col >= 0 && t.col + t.w <= 4 &&
                t.row in 0..15 && tiles.none { it.id != t.id && overlaps(t, it) }
        }

    private fun overlaps(a: CcTile, b: CcTile) = a.col < b.col + b.w && a.col + a.w > b.col &&
        a.row < b.row + b.h && a.row + a.h > b.row

    fun encode(tiles: List<CcTile>): String = JSONArray().apply {
        tiles.forEach { put(JSONObject().put("id", it.id).put("col", it.col).put("row", it.row).put("w", it.w).put("h", it.h)) }
    }.toString()

    fun decode(raw: String?): List<CcTile> = runCatching {
        val array = JSONArray(raw ?: return initial)
        List(array.length()) { i -> array.getJSONObject(i).let {
            CcTile(it.getString("id"), it.getInt("col"), it.getInt("row"), it.getInt("w"), it.getInt("h"))
        } }.also { require(valid(it)) }
    }.getOrDefault(initial)

    /** Pin the active item, retain unaffected items, prefer swapping into its old slot, then compact. */
    fun solve(base: List<CcTile>, id: String, col: Int, row: Int, w: Int, h: Int): List<CcTile> {
        val old = base.first { it.id == id }
        val width = if (old.slider) 1 else w.coerceIn(1, 4)
        val height = if (old.slider) 2 else h.coerceIn(1, if (id == "media") 3 else 2)
        val active = old.copy(col = col.coerceIn(0, 4 - width), row = row.coerceIn(0, 12), w = width, h = height)
        val placed = mutableListOf(active)
        fun fits(t: CcTile) = t.row >= 0 && t.row <= 15 && placed.none { overlaps(t, it) }
        val blocked = mutableListOf<CcTile>()
        base.filter { it.id != id }.sortedWith(compareBy({ it.row }, { it.col })).forEach {
            if (fits(it)) placed.add(it) else blocked.add(it)
        }
        blocked.forEach { t ->
            val swap = t.copy(col = old.col, row = old.row)
            val slot = if (swap.col + swap.w <= 4 && fits(swap)) swap else
                (0..15).asSequence().flatMap { r -> (0..4 - t.w).asSequence().map { c -> t.copy(col = c, row = r) } }.first(::fits)
            placed.add(slot)
        }
        val compact = mutableListOf<CcTile>()
        placed.sortedWith(compareBy({ it.row }, { it.col })).forEach { t ->
            var r = t.row
            while (r > 0 && compact.none { overlaps(t.copy(row = r - 1), it) }) r--
            if (t.id == id) r = minOf(t.row, maxOf(r, 3 - t.h))
            val next = t.copy(row = r)
            compact.add(if (compact.none { overlaps(next, it) }) next else t)
        }
        return base.map { t -> compact.first { it.id == t.id } }.also { check(valid(it)) }
    }
}

internal data class CcTileLayoutSettings(
    val enabled: Boolean = false,
    val tiles: List<CcTile> = CcTileLayouts.initial,
    val mediaSourceIconHidden: Boolean = false,
    val smallTileAutoFill: Boolean = false,
)

internal fun readCcTileLayoutSettings(p: SharedPreferences) = CcTileLayoutSettings(
    p.getBoolean(KEY_CC_TILE_LAYOUT_ENABLED, false),
    CcTileLayouts.decode(p.getString(KEY_CC_TILE_LAYOUT, null)),
    p.getBoolean(KEY_CC_MEDIA_SOURCE_ICON_HIDDEN, false),
    p.getBoolean(KEY_CC_SMALL_TILE_AUTO_FILL, false),
)

/** Explicit defaults keep exported presets complete, including untouched/disabled layouts. */
internal fun completeCcTileLayoutPreset(values: JSONObject) {
    if (!values.has(KEY_CC_TILE_LAYOUT_ENABLED)) values.put(KEY_CC_TILE_LAYOUT_ENABLED, JSONObject().put("type", "boolean").put("value", false))
    if (!values.has(KEY_CC_TILE_LAYOUT)) values.put(KEY_CC_TILE_LAYOUT, JSONObject().put("type", "string").put("value", CcTileLayouts.encode(CcTileLayouts.initial)))
    if (!values.has(KEY_CC_MEDIA_SOURCE_ICON_HIDDEN)) values.put(KEY_CC_MEDIA_SOURCE_ICON_HIDDEN, JSONObject().put("type", "boolean").put("value", false))
    if (!values.has(KEY_CC_SMALL_TILE_AUTO_FILL)) values.put(KEY_CC_SMALL_TILE_AUTO_FILL, JSONObject().put("type", "boolean").put("value", false))
}

internal class ControlCenterTileLayoutSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
    var settings = readCcTileLayoutSettings(local)
        private set
    fun syncRemote(service: XposedService) {
        syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
    }
    internal fun syncPreferences(remote: SharedPreferences) {
        settings = readCcTileLayoutSettings(local)
        write(remote)
    }
    fun update(service: XposedService?, value: CcTileLayoutSettings) {
        require(CcTileLayouts.valid(value.tiles))
        settings = value
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    private fun write(p: SharedPreferences) {
        p.edit().putBoolean(KEY_CC_TILE_LAYOUT_ENABLED, settings.enabled)
            .putString(KEY_CC_TILE_LAYOUT, CcTileLayouts.encode(settings.tiles))
            .putBoolean(KEY_CC_MEDIA_SOURCE_ICON_HIDDEN, settings.mediaSourceIconHidden)
            .putBoolean(KEY_CC_SMALL_TILE_AUTO_FILL, settings.smallTileAutoFill).apply()
    }
}
