// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics

import com.materialkolor.hct.Hct

/** The HCT engine already used by miuix, seeded from the current album cover. */
internal object LyricPalette {
    class Colors(
        @JvmField val seed: Int,
        @JvmField val mainDim: Int,
        @JvmField val mainLit: Int,
        @JvmField val bgDim: Int,
        @JvmField val bgLit: Int,
    )

    private val cache = object : LinkedHashMap<Int, Colors>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Colors>?): Boolean = size > 8
    }

    /** Called on the lyric worker, never from onDraw. Reuse colors when returning to a cover. */
    @JvmStatic
    @Synchronized
    fun colors(seed: Int): Colors = cache.getOrPut(seed) {
        if (seed == 0) return@getOrPut Colors(0, -1, -1, -1, -1)
        val source = Hct.fromInt(seed)
        fun color(backing: Boolean, active: Boolean): Int {
            val chroma = minOf(source.chroma, if (backing) 24.0 else 36.0)
            val tone = if (active) { if (backing) 85.0 else 90.0 }
                else { if (backing) 72.0 else 78.0 }
            return Hct.from(source.hue, chroma, tone).toInt()
        }
        Colors(seed, color(false, false), color(false, true), color(true, false), color(true, true))
    }
}
