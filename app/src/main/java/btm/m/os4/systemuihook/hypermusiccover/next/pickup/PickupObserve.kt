// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import org.json.JSONObject

/**
 * How often a parked page is read, out of ColorOS's own file for it
 * (`assets/coloros/pickup-code-observe-config.json`, taken from Gleaner unchanged).
 *
 * These are the numbers this feature used to carry written down by hand, and reading them is worth
 * more than it looks: they are the ones ColorOS tunes per release, and the shape of the choices
 * around them is not the obvious one. The chooser is OPPO's own
 * (`observeagent.impl.pickupcode.c.f`), and it says:
 *
 *   - a ready order is read at [ready], whatever else is true;
 *   - the first two reads after a page is parked are at [firstAfter] - that is what the name means,
 *     not "when the order's state is unknown", which is how this file used to use it;
 *   - after that the order's state decides between [ordered] and [making] (equal in this file);
 *   - a battery under [lowBatteryPercent] raises the interval to [lowBattery] - a floor, not a
 *     switch, so the reads slow down rather than stop, which is what this module used to do;
 *   - and under [skipPercent] the wait is over.
 *
 * A file that cannot be read leaves the defaults, which are the values it ships with.
 */
internal object PickupObserve {

    private const val ASSET = "coloros/pickup-code-observe-config.json"

    class Intervals(
        val firstAfter: Long = 120_000L,
        val ordered: Long = 60_000L,
        val making: Long = 60_000L,
        val ready: Long = 180_000L,
        val lowBattery: Long = 120_000L,
        val lowBatteryPercent: Int = 20,
        val skipPercent: Int = 10,
    )

    val intervals: Intervals by lazy { read() }

    private fun read(): Intervals {
        val defaults = Intervals()
        val section = runCatching {
            val bytes = PickupOem.asset(ASSET).use { it.readBytes() }
            JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("status_interval_millis")
        }.getOrNull() ?: return defaults
        return Intervals(
            firstAfter = section.millis("first_after_interval_millis", defaults.firstAfter),
            ordered = section.millis("ordered_interval_millis", defaults.ordered),
            making = section.millis("making_interval_millis", defaults.making),
            ready = section.millis("ready_interval_millis", defaults.ready),
            lowBattery = section.millis("low_battery_interval_millis", defaults.lowBattery),
            lowBatteryPercent = section.percent("low_battery_threshold_percent",
                defaults.lowBatteryPercent),
            skipPercent = section.percent("skip_battery_threshold_percent", defaults.skipPercent),
        )
    }

    /** A millisecond field, refused when it is missing or not a positive number of them. */
    private fun JSONObject.millis(key: String, fallback: Long): Long =
        optLong(key, fallback).takeIf { it > 0 } ?: fallback

    private fun JSONObject.percent(key: String, fallback: Int): Int =
        optInt(key, fallback).takeIf { it in 1..100 } ?: fallback
}
