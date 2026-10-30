// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
/**
 * Which milestone a bus or subway trip is at - what 高德 tells ColorOS outright (the entity's
 * `status`) and never says here. Worked out of what it does say: which leg its card is on, and
 * how many stops that leg has left.
 *
 *   - a ride first seen with all its stops still ahead is 候车 (2), until the first one goes by;
 *   - each stop that goes by is 当前站 (5) for [DWELL_MS], then 下一站 (3), and 下一站即终点 (4)
 *     before the leg's last stop;
 *   - 高德's card leaving a ride for the next leg is that ride reaching its end: 到达换乘站 (6)
 *     when another ride follows, else 到站 (7) - held up ([Hold]) because 高德 has already moved
 *     on, where ColorOS would still be showing it: a subway's 到站 for the five minutes
 *     GaoDePtRideCodeDeferBindManager holds the walk after it back (cut short by a ride code
 *     opened or a card swiped on the way out ([exited]), or 高德's own walking navigation coming
 *     up, as a walking entity cuts it on ColorOS), the others for [DWELL_MS];
 *   - a trip's last leg being a ride, its 到站 is when 高德 says the leg is over.
 *
 * No Android in here, so it can be tried on a computer (AmapTransitCardTest); the times are
 * whatever clock the caller gives.
 */
internal class AmapTransitMilestones {

    /** A ride that has been left, still shown: its milestone and until when. */
    class Hold(val leg: Int, val status: String, val until: Long, val subwayExit: Boolean)

    /** What to show: the leg, its milestone ("" on a walk), and when to look again (0: no need). */
    class Step(val leg: Int, val status: String, val held: Boolean, val wakeAt: Long)

    /** The leg 高德 last said is current. */
    var legAt = -1
        private set
    var hold: Hold? = null
        private set
    /** The ride's stops left when it was first seen, and the stops it has been seen at. */
    var boardRemain = 0
        private set
    val seen = ArrayList<String>()

    private var lastRemain = -1
    /** Whether the ride under way has been given a count yet. */
    val counted get() = lastRemain >= 0
    private var departed = false
    private var stopAt = 0L

    fun reset() {
        legAt = -1
        hold = null
        newLeg()
    }

    private fun newLeg() {
        lastRemain = -1
        boardRemain = 0
        departed = false
        stopAt = 0L
        seen.clear()
    }

    /**
     * 高德's card is on leg [at] of [legs]. Leaving a ride for a later leg holds the ride up;
     * [walking] and [subway] say what each leg is.
     */
    fun moveTo(at: Int, legs: Int, now: Long, walking: (Int) -> Boolean, subway: (Int) -> Boolean) {
        if (legAt >= 0 && at > legAt && !walking(legAt)) {
            val later = (legAt + 1 until legs).any { !walking(it) }
            hold = when {
                later -> Hold(legAt, AmapTransitCard.ARRIVE_TRANSFER_STATION, now + dwell(), false)
                subway(legAt) -> Hold(legAt, AmapTransitCard.ARRIVE_LINE_DESTINATION,
                    now + (SUBWAY_EXIT_MS * scale).toLong(), true)
                else -> Hold(legAt, AmapTransitCard.ARRIVE_LINE_DESTINATION, now + dwell(), false)
            }
        }
        if (at != legAt) newLeg()
        legAt = at
    }

    /** The held ride while its hold lasts - not once 高德's own walking navigation is up after a subway. */
    fun held(now: Long, walkIslandUp: Boolean): Hold? {
        val h = hold ?: return null
        if (now < h.until && !(h.subwayExit && walkIslandUp)) return h
        hold = null
        return null
    }

    /**
     * The way out of the station was taken - a ride code opened, a card's fare taken: a subway's
     * held 到站 ends now (GaoDePtRideCodeDeferBindManager.n). Whether there was one to end.
     */
    fun exited(): Boolean {
        if (hold?.subwayExit != true) return false
        hold = null
        return true
    }

    /**
     * The ride under way says [remain] stops are left of the [total] it has (0 when the plan
     * does not say), and that it is at or has just left [stop].
     */
    fun observe(remain: Int, total: Int, stop: String, now: Long) {
        if (lastRemain < 0) {
            // First sight: still at the stop it boards at, or already under way.
            departed = total > 0 && remain < total
            boardRemain = remain
        } else if (remain < lastRemain) {
            departed = true
            stopAt = now
        } else if (remain > lastRemain) {
            // More left than before: the count before was wrong, not the train going back. Read
            // again as at first sight - all of the ride ahead is still 候车.
            if (total > 0) departed = remain < total
            if (remain > boardRemain) boardRemain = remain
        }
        lastRemain = remain
        if (stop.isNotEmpty() && seen.lastOrNull() != stop) seen.add(stop)
    }

    /**
     * The ride's milestone. [last] is whether it is the trip's last leg and [over] whether 高德
     * says that leg is done; [named] whether the stops between its ends have names (a plan).
     */
    fun rideStatus(remain: Int, last: Boolean, over: Boolean, named: Boolean, now: Long): Step {
        val s = when {
            last && (remain <= 0 || over) -> AmapTransitCard.ARRIVE_LINE_DESTINATION
            !departed -> AmapTransitCard.WAITING
            now - stopAt < dwell() -> return Step(legAt, AmapTransitCard.ARRIVE_COMMON_STATION,
                false, stopAt + dwell())
            remain <= 1 -> AmapTransitCard.NEXT_DESTINATION
            // Without names the stop being approached cannot be named; the one it is at can.
            !named && seen.size >= 2 -> AmapTransitCard.ARRIVE_COMMON_STATION
            else -> AmapTransitCard.NEXT_STATION
        }
        return Step(legAt, s, false, 0L)
    }

    /** How long the times above last, as a share of themselves: a replayed ride runs them short. */
    var scale = 1.0

    private fun dwell() = (DWELL_MS * scale).toLong()

    companion object {
        /** Not a ColorOS figure: how long a stop, or a left ride, is shown when 高德 has moved on. */
        const val DWELL_MS = 30_000L
        /** GaoDePtRideCodeDeferBindManager.s: the walk after a subway is held back this long. */
        const val SUBWAY_EXIT_MS = 300_000L
    }
}
