// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高德's bus and subway trip, as the GaoDePtIntentEntity ColorOS's SceneService is handed.
 *
 * On ColorOS 高德's script builds that entity itself. Here it sends three other things, on its own
 * wearable channels (see AmapTransitShare), and the entity is put together out of them:
 *
 *  - the plan, `type 24` on bizType 113, sent the moment a navigation starts (and for every plan
 *    a route page shows): `segmentlist[]`, one per ride, each with its line (`bus_key_name`,
 *    `bustype`, `color`, `directionName`, `busid`), `on_station` (with its service hours),
 *    `via_st_list` (names and coordinates), `off_station` (`is_trans`), the exit `outport`, the
 *    walk before it (`footlength` / `foottime`) and the line's two ends (`driver_coord_list`);
 *    the trip's own ends in `spoi` / `epoi`, its walk at the end in `endfootlength` / `endfoottime`,
 *    its length and time in `allLength` / `expensetime`;
 *  - the trip card, bizType 103: `planData[]`, one capsule per leg in order, walks included -
 *    which is what `location.index` counts in - and for the leg under way its words
 *    (`titleItems` 「3站」「后」「 · 」「南村万博」「(B口)」「出站」) and `location.remainStations`;
 *  - the live data, `type 25` on 113: `arriveRemind` (`curStopName`, `remainStopNum`),
 *    `subway[].tripTime` (the next trains), `realtime.buses[]` and `locationData`.
 *
 * The capsules are the legs: a walking capsule becomes a walking leg, and the k-th riding capsule
 * the k-th segment of the plan - when the plan's lines are the capsules' lines, which is what
 * says the plan is this trip's. Without a plan that fits, each leg has only what the capsules and
 * the card say, and the stops the ride has passed (see [Fallback]).
 *
 * Which milestone it is, how many stops are left and which leg is current are AmapTransitShare's
 * to say; this only writes them down.
 */
internal object AmapTransitEntity {

    /** What the ride has told about the leg it is on, beyond the plan. */
    class Live(
        /** Stops left to the leg's end. */
        val remain: Int,
        /** 高德's own share of the leg ridden, 0..1, or -1. */
        val percent: Double,
        /** The stop the vehicle is at or has just left, 「」 when 高德 has not said. */
        val curStop: String,
        /** The next arrivals at the boarding stop, ColorOS's GaoDePtRealtimeItem shape. */
        val arrivals: JSONArray,
    )

    /**
     * For a trip without a plan that fits: the stops the leg has been seen at so far, in order,
     * and how many it has in all as far as is known (0 when not).
     */
    class Fallback(val seen: List<String>, val total: Int)

    /** Whether [plan]'s rides are the capsules' rides, in order: the plan is this trip's. */
    fun fits(plan: JSONObject?, capsules: JSONArray?): Boolean {
        val segs = plan?.optJSONArray("segmentlist") ?: return false
        val rides = rides(capsules)
        if (rides.isEmpty() || rides.size != segs.length()) return false
        for (i in rides.indices) {
            val s = segs.optJSONObject(i) ?: return false
            val name = rides[i].optString("text").trim()
            if (!sameLine(name, s.optString("bus_key_name").trim()) &&
                !sameLine(name, s.optString("busname").trim())) return false
        }
        return true
    }

    /**
     * The entity.
     *
     * [current] is the capsule the trip is on; [status] the milestone (empty on a walking leg);
     * [live] what the ride says about that leg when it is a ride. [exit] is the exit 高德 named for
     * the stop the riding leg ends at, [guide] its sentence.
     */
    fun build(
        plan: JSONObject?,
        capsules: JSONArray,
        card: JSONObject?,
        current: Int,
        status: String,
        live: Live?,
        fallback: Fallback?,
        exit: String,
        guide: String,
        id: String,
        walkNavi: JSONObject? = null,
    ): JSONObject {
        val fits = fits(plan, capsules)
        val segs = if (fits) plan!!.optJSONArray("segmentlist") else null
        val navi = JSONArray()
        var ride = 0
        val n = capsules.length()
        for (i in 0 until n) {
            val c = capsules.optJSONObject(i) ?: continue
            val leg = if (walking(c)) {
                walk(c, segs, ride, i == n - 1, plan)
            } else {
                val seg = segs?.optJSONObject(ride)
                ride++
                if (seg != null) fromPlan(seg, c) else fromCapsule(c)
            }
            leg.put("isCurrent", i == current)
            navi.put(leg)
        }
        // The legs after a ride that is a transfer: its stop is where the next ride starts.
        markTransfers(navi)
        val leg = navi.optJSONObject(current)
        if (leg != null && AmapTransitCard.busOrSubway(leg.optString("transportType"))) {
            live(leg, live, fallback, card, exit, plan != null && fits)
        }
        // The exit named for the leg being ridden: 高德's card words when they name one, else the
        // plan's own `outport` for the stop the ride ends at. Either source can be missing - the
        // 7号线 leg of 10-05 named 「(B口)」 in its card with no outport in the plan - so both are
        // asked. AmapTransitCard.arrivalCard draws the chip even where ColorOS does not.
        val named = exit.ifEmpty { offPort(leg) }
        val epoi = if (fits) plan!!.optJSONObject("epoi") else null
        val spoi = if (fits) plan!!.optJSONObject("spoi") else null
        // Without a plan the trip's end is only named by the card for the walk to it (or the one
        // saying it has been reached); the walk to the first station names that station instead.
        val title = card?.optString("title")?.trim().orEmpty()
        val last = current == n - 1 || status == AmapTransitCard.ARRIVE_FINAL_DESTINATION
        val dest = epoi?.optString("name")?.trim().orEmpty()
            .ifEmpty { if (last) destName(title) else "" }
        val o = JSONObject()
            .put("status", status)
            .put("entityId", id)
            .put("naviInfo", navi)
            .put("originStation", spoi?.optString("name")?.trim().orEmpty())
            .put("destStation", dest)
            .put("exitName", named)
            .put("guideInfo", guide)
            .put("deepLink", card?.optString("scheme").orEmpty())
            .put("offRoute", card?.optBoolean("offRoute", false) == true)
            .put("arrived", status == AmapTransitCard.ARRIVE_FINAL_DESTINATION)
            .put("isPublic", true)
            .put("legPercent", live?.percent ?: -1.0)
        card?.let { if (it.has("gpsSignalStatus")) o.put("gpsSignalStatus", it.optInt("gpsSignalStatus")) }
        // 高德's walking navigation of the current walk, its channel 101 body (AmapTransitCard.WalkNavi).
        walkNavi?.let { o.put("walkNavi", it) }
        if (fits) {
            plan!!.optString("allLength").trim().let { if (it.isNotEmpty()) o.put("totalDistance", it) }
            plan.optString("expensetime").trim().toDoubleOrNull()?.let { o.put("totalDuration", it) }
        }
        epoi?.optJSONObject("coord")?.let { c ->
            c.optString("lat").toDoubleOrNull()?.let { o.put("destLatitude", it) }
            c.optString("lon").toDoubleOrNull()?.let { o.put("destLongitude", it) }
        }
        return o
    }

    // ------------------------------------------------------------------ the legs

    /** The riding capsules, in order. */
    fun rides(capsules: JSONArray?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until (capsules?.length() ?: 0)) {
            val c = capsules!!.optJSONObject(i) ?: continue
            if (!walking(c)) out.add(c)
        }
        return out
    }

    /** A walking capsule: the foot icon, or capsuleType 0. 「13」 on it is minutes. */
    fun walking(c: JSONObject): Boolean =
        c.optString("icon").startsWith("bus_foot") || c.optString("capsuleType").trim() == "0"

    /** The walk before ride [ride] of the plan, or after the last one. */
    private fun walk(c: JSONObject, segs: JSONArray?, ride: Int, last: Boolean, plan: JSONObject?): JSONObject {
        val o = JSONObject().put("transportType", AmapTransitCard.WALK)
        val seg = segs?.optJSONObject(ride)
        val (length, time) = when {
            seg != null -> seg.optString("footlength") to seg.optString("foottime")
            last && segs != null -> plan!!.optString("endfootlength") to plan.optString("endfoottime")
            else -> "" to ""
        }
        val minutes = c.optString("subText").trim().toIntOrNull()
        o.put("walkingOrRideLength", length.trim())
        o.put("walkingOrRideDuration", time.trim().ifEmpty { minutes?.let { (it * 60).toString() }.orEmpty() })
        return o
    }

    /** A ride, as the plan's segment has it. */
    private fun fromPlan(seg: JSONObject, capsule: JSONObject): JSONObject {
        val type = type(capsule, seg)
        val color = seg.optString("color").trim().let { if (it.isEmpty()) "" else "#" + it.removePrefix("#") }
            .ifEmpty { capsuleColor(capsule) }
        val ends = seg.optJSONObject("driver_coord_list")
        val onSrc = seg.optJSONObject("on_station")
        val offSrc = seg.optJSONObject("off_station")
        val on = JSONObject()
            .put("stationName", onSrc?.optString("name")?.trim().orEmpty())
            .put("coord", coord(ends?.optJSONObject("start")))
            .put("start_time", onSrc?.optString("start_time").orEmpty())
            .put("end_time", onSrc?.optString("end_time").orEmpty())
        val off = JSONObject()
            .put("stationName", offSrc?.optString("name")?.trim().orEmpty())
            .put("coord", coord(ends?.optJSONObject("end")))
            .put("port_list", ports(seg.optJSONObject("outport")))
        val via = JSONArray()
        val list = seg.optJSONArray("via_st_list")
        for (i in 0 until (list?.length() ?: 0)) {
            val v = list!!.optJSONObject(i) ?: continue
            via.put(JSONObject()
                .put("name", v.optString("name").trim())
                .put("coord", coord(v.optJSONObject("coord")))
                .put("isTransferStation", false))
        }
        val text = seg.optString("line_name_color").trim()
        return JSONObject()
            .put("transportType", type)
            .put("lineName", seg.optString("bus_key_name").trim().ifEmpty { capsule.optString("text").trim() })
            .put("lineDirection", seg.optString("directionName").trim())
            .put("lineBgColor", color)
            .put("lineTextColor", if (text.isEmpty()) "#FFFFFF" else "#" + text.removePrefix("#"))
            .put("busId", seg.optString("busid").trim())
            .put("remainStations", via.length() + 1)
            .put("on_station", on)
            .put("off_station", off)
            .put("via_st_list", via)
    }

    /** A ride with nothing but its capsule: the line's name, kind and colour. */
    private fun fromCapsule(c: JSONObject): JSONObject = JSONObject()
        .put("transportType", type(c, null))
        .put("lineName", c.optString("text").trim())
        .put("lineDirection", "")
        .put("lineBgColor", capsuleColor(c))
        .put("lineTextColor", "#FFFFFF")
        .put("on_station", JSONObject().put("stationName", ""))
        .put("off_station", JSONObject().put("stationName", ""))
        .put("via_st_list", JSONArray())

    /**
     * The stop a ride ends at is a transfer when another ride follows it: that is where the trip
     * changes line (高德's `is_trans` says the station is an interchange at all, which a stop the
     * trip only passes through can be too).
     */
    private fun markTransfers(navi: JSONArray) {
        for (i in 0 until navi.length()) {
            val leg = navi.optJSONObject(i) ?: continue
            if (!AmapTransitCard.transit(leg.optString("transportType"))) continue
            var later = false
            for (k in i + 1 until navi.length()) {
                if (AmapTransitCard.transit(navi.optJSONObject(k)?.optString("transportType"))) {
                    later = true
                    break
                }
            }
            leg.optJSONObject("off_station")?.put("isTransferStation", later)
        }
    }

    /** The riding leg under way: its stops left, its arrivals, the stop and exit 高德 named. */
    private fun live(leg: JSONObject, live: Live?, fallback: Fallback?, card: JSONObject?,
                     exit: String, planned: Boolean) {
        val on = leg.getJSONObject("on_station")
        val off = leg.getJSONObject("off_station")
        if (live != null) {
            leg.put("remainStations", live.remain)
            if (live.arrivals.length() > 0) on.put("waitInfo", JSONObject().put("realTime", live.arrivals))
        }
        if (leg.optString("lineDirection").isEmpty()) {
            leg.put("lineDirection", direction(card))
        }
        if (off.optString("stationName").isEmpty()) {
            off.put("stationName", alight(card))
        }
        // The exit 高德 named, kept beside the plan's own (ya.b.N matches the one by the other).
        val ports = off.optJSONArray("port_list") ?: JSONArray().also { off.put("port_list", it) }
        if (exit.isNotEmpty() && ports.length() == 0) ports.put(JSONObject().put("name", exit))
        if (planned || fallback == null) return
        // No plan: the stops seen so far stand in for the ones between, the rest left unnamed so
        // the count of stops still comes out where ColorOS's arithmetic expects it (ya.e.e).
        if (on.optString("stationName").isEmpty() && fallback.seen.isNotEmpty()) {
            on.put("stationName", fallback.seen.first())
        }
        val between = fallback.seen.drop(1).filter { it != off.optString("stationName") }
        val count = maxOf(fallback.total - 1, between.size)
        val via = JSONArray()
        for (i in 0 until count) {
            via.put(JSONObject().put("name", between.getOrElse(i) { "" }).put("isTransferStation", false))
        }
        leg.put("via_st_list", via)
    }

    /** 高德's kind of leg: the capsule's when it says bus or subway, else the plan's `bustype`. */
    private fun type(c: JSONObject, seg: JSONObject?): String {
        when (c.optString("capsuleType").trim()) {
            "1" -> return AmapTransitCard.BUS
            "2" -> return AmapTransitCard.SUBWAY
        }
        when (seg?.optString("bustype")?.trim()) {
            "1" -> return AmapTransitCard.BUS
            "2" -> return AmapTransitCard.SUBWAY
        }
        val name = c.optString("text").trim()
        return if (name.endsWith("号线") || name.endsWith("线")) AmapTransitCard.SUBWAY else AmapTransitCard.BUS
    }

    /** A capsule's colour when it is one: 高德 sends tokens (@Color_...) for the walking ones. */
    private fun capsuleColor(c: JSONObject): String {
        val bg = c.optString("bgColor").trim()
        return if (bg.startsWith("#")) bg else ""
    }

    /** The exits of the stop a ride ends at, as GaoDePtPort. */
    private fun ports(p: JSONObject?): JSONArray {
        val out = JSONArray()
        val name = p?.optString("name")?.trim().orEmpty()
        if (name.isEmpty()) return out
        out.put(JSONObject()
            .put("name", name)
            .put("shield", p!!.optString("shield").trim())
            .put("status", p.optInt("status", -1))
            .put("coord", coord(p.optJSONObject("coord"))))
        return out
    }

    /** The name of the exit the plan gives the stop [leg] ends at, 「」 when it gives none. */
    private fun offPort(leg: JSONObject?): String =
        leg?.optJSONObject("off_station")?.optJSONArray("port_list")
            ?.optJSONObject(0)?.optString("name")?.trim().orEmpty()

    /** 高德's {lon, lat} strings as the entity's {lat, lng}. */
    private fun coord(c: JSONObject?): JSONObject {
        val o = JSONObject()
        c?.optString("lat")?.toDoubleOrNull()?.let { o.put("lat", it) }
        c?.optString("lon")?.toDoubleOrNull()?.let { o.put("lng", it) }
        return o
    }

    // ------------------------------------------------------------------ the card's words

    /** 「(美的大道方向)」 -> 「美的大道方向」. */
    private fun direction(card: JSONObject?): String {
        val items = card?.optJSONArray("subTitleItems") ?: return ""
        for (i in 0 until items.length()) {
            val t = items.optJSONObject(i)?.optString("text")?.trim().orEmpty()
            if (t.contains("方向")) return t.trim('(', ')', '（', '）')
        }
        return ""
    }

    /** The stop the card's sentence names: 「3站」「后」「 · 」「南村万博」「(B口)」「出站」. */
    fun alight(card: JSONObject?): String {
        val parts = pieces(card?.optJSONArray("titleItems"))
        val sep = parts.indexOfFirst { it.contains('·') }
        if (sep >= 0) {
            val tail = parts[sep].substringAfter('·').trim()
            if (tail.isNotEmpty()) return station(tail)
            if (sep + 1 < parts.size) return station(parts[sep + 1])
        }
        for (p in parts) {
            if (isCount(p) || isExit(p) || ACTION.containsMatchIn(p) || p == "后") continue
            return station(p)
        }
        return ""
    }

    /** The exit the card names for that stop: 「(B口)」 -> 「B口」. */
    fun exit(card: JSONObject?): String {
        val parts = pieces(card?.optJSONArray("titleItems"))
        return parts.firstOrNull { isExit(it) }?.trim('(', ')', '（', '）')?.trim().orEmpty()
    }

    /** The card's sentence as one line: 「3站后·南村万博(B口)出站」. */
    fun sentence(card: JSONObject?): String =
        pieces(card?.optJSONArray("titleItems")).joinToString("").replace(" ", "")

    /** 「步行至 汕黄牛.牛肉海鲜自助」 / 「已到达 汕黄牛.牛肉海鲜自助」 -> the place. */
    fun destName(title: String): String =
        Regex("(?:已到达|步行至)\\s*(.+)$").find(title.trim())?.groupValues?.get(1)?.trim().orEmpty()

    private fun pieces(items: JSONArray?): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until (items?.length() ?: 0)) {
            val t = items!!.optJSONObject(i)?.optString("text").orEmpty().trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /** 「3站」 or 「3」: a count of stops, which 高德 puts where a name could be. */
    private fun isCount(t: String): Boolean =
        t.isNotEmpty() && t.length <= 12 && (t.all { it.isDigit() } || (t.contains('站') && t.any { it.isDigit() }))

    private fun isExit(t: String): Boolean =
        t.length > 2 && (t.startsWith("(") || t.startsWith("（")) && (t.endsWith(")") || t.endsWith("）"))

    private val ACTION = Regex("(进站|出站|下车|换乘|上车)")

    /** 「大学城南(E口)」 -> 「大学城南」. */
    private fun station(text: String): String =
        text.replace(Regex("[(（][^)）]*[)）]"), "").trim()

    /**
     * Whether [a] and [b] name one line: 「7号线」 and 「地铁7号线(美的大道--燕山)」 do, 「1号线」 and
     * 「11号线」 do not (the character before a match must not be a digit).
     */
    fun sameLine(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return inside(b, a) || inside(a, b)
    }

    private fun inside(name: String, line: String): Boolean {
        var at = name.indexOf(line)
        while (at >= 0) {
            val end = at + line.length
            val before = at == 0 || !name[at - 1].isDigit()
            val after = end >= name.length || !name[end].isDigit()
            if (before && after) return true
            at = name.indexOf(line, at + 1)
        }
        return false
    }
}
