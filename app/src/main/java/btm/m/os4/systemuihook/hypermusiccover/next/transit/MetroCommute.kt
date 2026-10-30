// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * The subway commutes the phone has learned, as ColorOS's Metis learns them
 * (CommuteLearningManager: recordTrip, runLearningTask, getMatchRoute), for the ride-code island:
 * a trip that starts where a commute starts, at the time it usually does, is put up at that
 * commute's changes and its end as well as its start - without 高德 navigating it.
 *
 * Metis's rules, kept:
 *   - a trip is its city, first and last station, the stations it changed lines at, and when it
 *     started; one of a single station is not a trip;
 *   - the last 30 days are learned from; a start and end ridden 3 times or more is a commute;
 *   - a change is kept when 20% of those trips made it, and the commute's changes are the most
 *     common sequence of the kept ones;
 *   - when the trips started is kept in 48 half-hour slices;
 *   - a trip is matched by its city and first station: the commute starting there that has trips
 *     in this half hour, the most of them; else the only commute starting there. Only a city's 3
 *     most ridden commutes are looked at (its route list).
 * Added here: the two stations before the end a trip came through (its [Trip.approach]), the
 * commute's the most common pair of them, so the island is put up two stations ahead of the end
 * as well. The order is the cards', with no line graph to check it against.
 * Not kept: correcting the end over the line graph (Metis has the graph and BLE that misses the
 * last station; the end here is the fare's or 小爱建议's last card), and the fallback by the
 * station next to the start (the graph again).
 *
 * Privacy: what is kept is the names of stations, a city, and start times - no place, no fare,
 * no card. It stays in [file], in 小爱建议's no-backup storage, and is never sent anywhere. Trips
 * older than [KEEP_MS] are dropped on every load and every record, so nothing outlives a month;
 * [forget] drops it all. Nothing is recorded while [learning] is off.
 */
internal class MetroCommute(private val file: File?) {

    class Trip(
        val city: String,
        val start: String,
        val end: String,
        val transfers: List<String>,
        /** Wall clock, when the trip's first station came. */
        val at: Long,
        /** Up to two stations ridden through just before [end], in order; not [start]. */
        val approach: List<String> = emptyList(),
    )

    class Route(
        val city: String,
        val start: String,
        val end: String,
        val transfers: List<String>,
        val trips: Int,
        /** Trips started in each half hour of the day. */
        val slices: IntArray,
        val updated: Long,
        /** The two stations before [end] its trips came through most ([Trip.approach]). */
        val approach: List<String> = emptyList(),
    ) {
        /**
         * The stations the island is put up at after the first: Metis's remindType 0 and 1, and
         * the two before the end.
         */
        val stops: Set<String> get() = (transfers + approach + end).toSet()
        override fun toString() =
            "$city $start -> " + (if (transfers.isEmpty()) "" else transfers.joinToString(" -> ", postfix = " -> ")) +
                "$end x$trips"
    }

    private val trips = ArrayList<Trip>()
    var learning = true
        private set

    init {
        load()
    }

    @Synchronized
    fun setLearning(on: Boolean) {
        if (learning == on) return
        learning = on
        save()
    }

    @Synchronized
    fun forget() {
        trips.clear()
        save()
    }

    @Synchronized
    fun count() = trips.size

    @Synchronized
    fun record(t: Trip, now: Long = System.currentTimeMillis()) {
        if (!learning) return
        if (t.start.isEmpty() || t.end.isEmpty() || t.start == t.end) return
        trips += t
        prune(now)
        save()
    }

    /** Every commute learned from the last [KEEP_MS], most ridden first. */
    @Synchronized
    fun routes(now: Long = System.currentTimeMillis()): List<Route> {
        prune(now)
        return learn(trips)
    }

    /** The commute a trip starting at [start] in [city] at [at] is riding, or null. */
    @Synchronized
    fun match(city: String, start: String, at: Long, now: Long = System.currentTimeMillis()): Route? {
        if (!learning || start.isEmpty()) return null
        val listed = routes(now).filter { it.city == city }.take(LISTED)
        val here = listed.filter { it.start == start }
        val slice = slice(at)
        here.filter { it.slices[slice] > 0 }
            .sortedWith(compareByDescending<Route> { it.slices[slice] }.thenByDescending { it.updated })
            .firstOrNull()?.let { return it }
        return here.singleOrNull()?.let {
            Route(it.city, it.start, it.end, emptyList(), it.trips, it.slices, it.updated, it.approach)
        }
    }

    private fun prune(now: Long) {
        trips.removeAll { now - it.at > KEEP_MS || it.at > now + 86_400_000L }
        while (trips.size > MAX_TRIPS) trips.removeAt(0)
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            learning = o.optBoolean("learning", true)
            val a = o.optJSONArray("trips") ?: JSONArray()
            for (k in 0 until a.length()) {
                val t = a.optJSONObject(k) ?: continue
                fun names(k: String): List<String> {
                    val a = t.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                }
                trips += Trip(t.optString("city"), t.optString("start"), t.optString("end"),
                    names("transfers"), t.optLong("at"), names("approach"))
            }
            prune(System.currentTimeMillis())
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (trips.isEmpty() && learning) {
                f.delete()
                return
            }
            val a = JSONArray()
            for (t in trips) {
                a.put(JSONObject().put("city", t.city).put("start", t.start).put("end", t.end)
                    .put("transfers", JSONArray(t.transfers)).put("at", t.at)
                    .apply { if (t.approach.isNotEmpty()) put("approach", JSONArray(t.approach)) })
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("learning", learning).put("trips", a).toString())
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val KEEP_MS = 30L * 86_400_000L
        /** runLearningTask: a start and end ridden this often is a commute. */
        const val MIN_TRIPS = 3
        /** CommuteLearningManager.a: a change kept by this share of the commute's trips. */
        const val TRANSFER_SHARE = 0.2
        /** getMatchRoute: a city's route list, z0(..., 3). */
        const val LISTED = 3
        private const val MAX_TRIPS = 500

        fun learn(trips: List<Trip>): List<Route> {
            val out = ArrayList<Route>()
            for ((_, group) in trips.groupBy { it.city + "|" + it.start + "|" + it.end }) {
                if (group.size < MIN_TRIPS) continue
                val first = group.first()
                val slices = IntArray(48)
                for (t in group) slices[slice(t.at)]++
                out += Route(first.city, first.start, first.end, transfers(group), group.size,
                    slices, group.maxOf { it.at }, approach(group))
            }
            return out.sortedWith(compareByDescending<Route> { it.trips }.thenByDescending { it.updated })
        }

        /** CommuteLearningManager.a: the most common sequence of the changes 20% of trips made. */
        fun transfers(group: List<Trip>): List<String> {
            val counts = LinkedHashMap<String, Int>()
            for (t in group) for (s in t.transfers.distinct()) counts[s] = (counts[s] ?: 0) + 1
            val kept = counts.filterValues { it.toDouble() / group.size >= TRANSFER_SHARE }.keys
            if (kept.isEmpty()) return emptyList()
            val sequences = LinkedHashMap<List<String>, Int>()
            for (t in group) {
                val seq = t.transfers.filter { it in kept }
                if (seq.isNotEmpty()) sequences[seq] = (sequences[seq] ?: 0) + 1
            }
            val most = sequences.values.maxOrNull() ?: return emptyList()
            return sequences.entries.first { it.value == most }.key
        }

        /**
         * The two stations before the end the commute's trips came through most. Trips recorded
         * before these were kept, and ones whose cards missed them, say nothing.
         */
        fun approach(group: List<Trip>): List<String> {
            val counts = LinkedHashMap<List<String>, Int>()
            for (t in group) if (t.approach.isNotEmpty()) counts[t.approach] = (counts[t.approach] ?: 0) + 1
            val most = counts.values.maxOrNull() ?: return emptyList()
            return counts.entries.first { it.value == most }.key
        }

        fun slice(at: Long): Int {
            val c = Calendar.getInstance()
            c.timeInMillis = at
            return c.get(Calendar.HOUR_OF_DAY) * 2 + c.get(Calendar.MINUTE) / 30
        }

        /**
         * The stations of [stations] a trip changed lines at, from the lines each station's card
         * names: the line between two stations is the one both have, and a change is a station
         * where the line before it and the line after it have none in common. A station with no
         * lines, or two stations with none in common (a card missed between them), says nothing.
         */
        fun changes(stations: List<String>, lines: Map<String, Set<String>>): List<String> {
            val out = ArrayList<String>()
            for (i in 1 until stations.size - 1) {
                val prev = (lines[stations[i - 1]] ?: emptySet()) intersect (lines[stations[i]] ?: emptySet())
                val next = (lines[stations[i]] ?: emptySet()) intersect (lines[stations[i + 1]] ?: emptySet())
                if (prev.isNotEmpty() && next.isNotEmpty() && (prev intersect next).isEmpty()) out += stations[i]
            }
            return out
        }
    }
}
