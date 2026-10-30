// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit;
import btm.m.os4.systemuihook.R;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ColorOS 17's card for 高德's bus and subway navigation, ported function by function from
 * SceneService (ColorOS17-v16-fuxi-FULL-20260928): what every milestone shows, worked out of a
 * GaoDePtIntentEntity. Both halves use it - the island in 高德's process, the lock screen page in
 * SystemUI - so they say the same thing.
 *
 * Where each piece comes from (com.oplus.sdp.* is SceneService's own obfuscated package):
 *   ya.b   BusOrSubwayParser: the milestone cards e() / c() / K() / a() / d() / b(), the stop a
 *          milestone names e0(), the line under it Y(), the station overview l(), the waiting
 *          lines p()
 *   ya.e   ThreeNodeStations / TwoNodeStations: which stops the overview draws
 *   ya.g   the current segment (g.a recognised segments, g.b the one marked isCurrent)
 *   ya.k   the overview as the card reads it (isCurStation, isTwoStation, the transfer badge)
 *   ya.a   ArriveFinalDestinationParser: the card for the trip's end
 *   ya.f   OffRouteParser
 *   ya.n   the silent walking card between two rides (f()), and the walking navigation's own
 *          card while 高德 navigates that walk (d(), with its weak-GPS form E())
 *   GaoDePtNaviSceneRouter.j: which of those an entity gets
 * The strings are SceneService's own (gaode_pt_*), word for word.
 *
 * Nothing here decides a milestone: that is 高德's on ColorOS, and AmapTransitShare's here.
 */
final class AmapTransitCard {

    private AmapTransitCard() {
    }

    // ------------------------------------------------------------------ codes

    /** GaoDePublicTransportNavMilestone. */
    static final String ARRIVE_ORIGIN_NEARBY = "1";
    static final String WAITING = "2";
    static final String NEXT_STATION = "3";
    static final String NEXT_DESTINATION = "4";
    static final String ARRIVE_COMMON_STATION = "5";
    static final String ARRIVE_TRANSFER_STATION = "6";
    static final String ARRIVE_LINE_DESTINATION = "7";
    /** GaoDePtTerminalStatus. */
    static final String ARRIVE_FINAL_DESTINATION = "8";
    static final String END_NAVI_IN_APP = "9";
    /** GaoDePtOffRouteStatus.NOT_ON_WAY. */
    static final String NOT_ON_WAY = "10";

    /** GaoDeNaviSegmentTransportType. */
    static final String WALK = "0";
    static final String BUS = "1";
    static final String SUBWAY = "2";
    static final String FERRY = "12";
    static final String CABLE_CAR = "13";
    static final String TAXI = "100";
    static final String BIKE = "102";

    /** What SceneService draws a line it has no colour for in (ya.b.L). */
    static final String DEFAULT_COLOR = "#4A86FF";

    static boolean known(String t) {
        String s = trim(t);
        return s.equals(WALK) || s.equals(BUS) || s.equals(SUBWAY) || s.equals(FERRY)
                || s.equals(CABLE_CAR) || s.equals(TAXI) || s.equals(BIKE);
    }

    static boolean bus(String t) {
        return BUS.equals(trim(t));
    }

    static boolean subway(String t) {
        return SUBWAY.equals(trim(t));
    }

    /** Companion.c: a bus or a subway. */
    static boolean busOrSubway(String t) {
        return bus(t) || subway(t);
    }

    /** Companion.d: anything with a line - bus, subway, ferry, cable car. */
    static boolean transit(String t) {
        String s = trim(t);
        return s.equals(BUS) || s.equals(SUBWAY) || s.equals(FERRY) || s.equals(CABLE_CAR);
    }

    /** Companion.g: on foot or by bike. */
    static boolean walkOrBike(String t) {
        String s = trim(t);
        return s.equals(WALK) || s.equals(BIKE);
    }

    // ------------------------------------------------------------------ the entity

    /** GaoDePtViaStation. */
    static final class Station {
        final String name;
        final double lat;
        final double lng;
        final boolean transfer;

        Station(String name, double lat, double lng, boolean transfer) {
            this.name = name == null ? "" : name;
            this.lat = lat;
            this.lng = lng;
            this.transfer = transfer;
        }

        Station(String name) {
            this(name, Double.NaN, Double.NaN, false);
        }

        boolean located() {
            return AmapTransitLandmarks.valid(lat, lng);
        }
    }

    /** GaoDePtPort: one way out of the stop a leg ends at. */
    static final class Port {
        String name = "";
        String shield = "";
        double lat = Double.NaN;
        double lng = Double.NaN;

        boolean located() {
            return AmapTransitLandmarks.valid(lat, lng);
        }
    }

    /** GaoDePtRealtimeItem. */
    static final class Realtime {
        String mainTitle = "";
        String orderTip = "";
        String lineName = "";
        String lineDirection = "";

        Realtime copyWithMain(String main) {
            Realtime r = new Realtime();
            r.mainTitle = main;
            r.orderTip = orderTip;
            r.lineName = lineName;
            r.lineDirection = lineDirection;
            return r;
        }
    }

    /** GaoDePtNaviInfoItem: one leg of the trip. */
    static final class Leg {
        String type = "";
        String lineName = "";
        String lineDirection = "";
        String lineBg = "";
        String lineText = "";
        /** Null when 高德 did not say, as the bean's Integer is. */
        Integer remain;
        boolean current;
        String onName = "";
        double onLat = Double.NaN;
        double onLng = Double.NaN;
        String startTime = "";
        String endTime = "";
        final List<Realtime> realtime = new ArrayList<>();
        String offName = "";
        double offLat = Double.NaN;
        double offLng = Double.NaN;
        Boolean offTransfer;
        final List<Port> ports = new ArrayList<>();
        final List<Station> via = new ArrayList<>();
        String walkLength = "";
        String walkDuration = "";

        boolean rides() {
            return busOrSubway(type);
        }

        static Leg of(JSONObject o) {
            Leg l = new Leg();
            l.type = trim(o.optString("transportType"));
            l.lineName = o.optString("lineName");
            l.lineDirection = o.optString("lineDirection");
            l.lineBg = o.optString("lineBgColor");
            l.lineText = o.optString("lineTextColor");
            if (o.has("remainStations") && !o.isNull("remainStations")) {
                l.remain = o.optInt("remainStations");
            }
            l.current = o.optBoolean("isCurrent", false);
            l.walkLength = o.optString("walkingOrRideLength");
            l.walkDuration = o.optString("walkingOrRideDuration");
            JSONObject on = o.optJSONObject("on_station");
            if (on != null) {
                l.onName = on.optString("stationName");
                double[] c = coord(on.optJSONObject("coord"));
                l.onLat = c[0];
                l.onLng = c[1];
                l.startTime = on.optString("start_time");
                l.endTime = on.optString("end_time");
                JSONObject wait = on.optJSONObject("waitInfo");
                JSONArray rt = wait == null ? null : wait.optJSONArray("realTime");
                for (int i = 0; rt != null && i < rt.length(); i++) {
                    JSONObject r = rt.optJSONObject(i);
                    if (r == null) continue;
                    Realtime item = new Realtime();
                    item.mainTitle = r.optString("mainTitle");
                    item.orderTip = r.optString("orderTiptext");
                    item.lineName = r.optString("lineName");
                    item.lineDirection = r.optString("lineDirection");
                    l.realtime.add(item);
                }
            }
            JSONObject off = o.optJSONObject("off_station");
            if (off != null) {
                l.offName = off.optString("stationName");
                double[] c = coord(off.optJSONObject("coord"));
                l.offLat = c[0];
                l.offLng = c[1];
                if (off.has("isTransferStation")) l.offTransfer = off.optBoolean("isTransferStation");
                JSONArray ports = off.optJSONArray("port_list");
                for (int i = 0; ports != null && i < ports.length(); i++) {
                    JSONObject p = ports.optJSONObject(i);
                    if (p == null) continue;
                    Port port = new Port();
                    port.name = p.optString("name");
                    port.shield = p.optString("shield");
                    double[] pc = coord(p.optJSONObject("coord"));
                    port.lat = pc[0];
                    port.lng = pc[1];
                    l.ports.add(port);
                }
            }
            JSONArray via = o.optJSONArray("via_st_list");
            for (int i = 0; via != null && i < via.length(); i++) {
                JSONObject v = via.optJSONObject(i);
                if (v == null) continue;
                double[] c = coord(v.optJSONObject("coord"));
                l.via.add(new Station(v.optString("name"), c[0], c[1],
                        v.optBoolean("isTransferStation", false)));
            }
            return l;
        }

        private static double[] coord(JSONObject c) {
            if (c == null) return new double[] {Double.NaN, Double.NaN};
            return new double[] {c.optDouble("lat", Double.NaN), c.optDouble("lng", Double.NaN)};
        }
    }

    /**
     * GaoDeWalkingAndCyclingIntentEntity, the parts ya.n.d reads: 高德's walking navigation for a
     * walk of the trip. On HyperOS it is 高德's channel 101 (`trigger_source` 1, `isFromBus`), the
     * same body ColorOS's legacy transformer turns into that entity; AmapTransitShare hands it over
     * as the trip's `walkNavi`.
     */
    static final class WalkNavi {
        String firstDesc = "";
        String secondDesc = "";
        Integer remainLength;
        Integer routeTotal;
        Integer routeRemain;
        Integer routeRemainTime;
        Integer gpsSignalStatus;
        boolean bike;

        static WalkNavi of(JSONObject o) {
            WalkNavi w = new WalkNavi();
            w.firstDesc = o.optString("first_desc");
            w.secondDesc = o.optString("second_desc");
            w.remainLength = integer(o, "remain_length");
            w.routeTotal = integer(o, "route_total_dist");
            w.routeRemain = integer(o, "route_remain_dist");
            w.routeRemainTime = integer(o, "route_remain_time");
            w.gpsSignalStatus = integer(o, "gpsSignalStatus");
            // GaoDeWalkRideRouteType: 0 a walk, 1 a ride by bike.
            w.bike = o.optInt("route_type", 0) == 1;
            return w;
        }

        private static Integer integer(JSONObject o, String k) {
            if (!o.has(k) || o.isNull(k)) return null;
            return number(o.optString(k));
        }
    }

    /** GaoDePtIntentEntity, the parts the card reads. */
    static final class Trip {
        String status = "";
        String entityId = "";
        String cityCode = "";
        String originStation = "";
        String destStation = "";
        double destLat = Double.NaN;
        double destLng = Double.NaN;
        String exitName = "";
        String guideInfo = "";
        String deepLink = "";
        boolean offRoute;
        Integer gpsSignalStatus;
        String totalDistance = "";
        double totalDuration;
        /** 高德's own share of the current leg ridden, 0..1, or -1 (not part of the bean). */
        double legPercent = -1;
        /** The legs SceneService recognises, in order (g.a). */
        final List<Leg> navi = new ArrayList<>();
        /** The one marked current (g.b), or null. */
        Leg current;
        /** 高德's walking navigation for the current walk, while it runs; else null. */
        WalkNavi walkNavi;

        int currentIndex() {
            return current == null ? -1 : navi.indexOf(current);
        }

        static Trip parse(JSONObject o) {
            Trip t = new Trip();
            t.status = trim(o.optString("status"));
            t.entityId = o.optString("entityId");
            t.cityCode = trim(o.optString("destCitycode"));
            t.originStation = o.optString("originStation");
            t.destStation = o.optString("destStation");
            t.destLat = o.optDouble("destLatitude", Double.NaN);
            t.destLng = o.optDouble("destLongitude", Double.NaN);
            t.exitName = o.optString("exitName");
            t.guideInfo = o.optString("guideInfo");
            t.deepLink = o.optString("deepLink");
            t.offRoute = o.optBoolean("offRoute", false);
            if (o.has("gpsSignalStatus")) t.gpsSignalStatus = o.optInt("gpsSignalStatus");
            t.totalDistance = o.optString("totalDistance");
            t.totalDuration = o.optDouble("totalDuration", 0);
            t.legPercent = o.optDouble("legPercent", -1);
            JSONObject walk = o.optJSONObject("walkNavi");
            if (walk != null) t.walkNavi = WalkNavi.of(walk);
            JSONArray navi = o.optJSONArray("naviInfo");
            for (int i = 0; navi != null && i < navi.length(); i++) {
                JSONObject n = navi.optJSONObject(i);
                if (n == null) continue;
                Leg l = Leg.of(n);
                if (!known(l.type)) continue;
                t.navi.add(l);
                if (t.current == null && l.current) t.current = l;
            }
            return t;
        }
    }

    // ------------------------------------------------------------------ the card

    /** One stop of the station overview (GaoDeStationOverviewItem, as ya.k writes it). */
    static final class Node {
        final String name;
        final int color;
        final boolean transfer;
        /** The line a transfer node changes to (ya.k.a): its short name and colour, or "". */
        String badge = "";
        int badgeColor;

        Node(String name, int color, boolean transfer) {
            this.name = name;
            this.color = color;
            this.transfer = transfer;
        }
    }

    /** One line of the waiting card (GaoDeWaitLineInfo). */
    static final class WaitLine {
        String name = "";
        String direction = "";
        int color;
        String realtime1 = "";
        String realtime2 = "";
    }

    /** What the card shows, field for field the keys SceneService's builders write. */
    static final class Card {
        static final String KIND_WAITING = "waiting";
        static final String KIND_RIDING = "riding";
        static final String KIND_TRANSFER = "transfer";
        static final String KIND_ARRIVAL = "arrival";
        static final String KIND_FINAL = "final";
        static final String KIND_OFF_ROUTE = "off_route";
        static final String KIND_WALK = "walk";
        static final String KIND_WALK_NAVI = "walk_navi";

        String kind = "";
        /** The milestone after W(): what the card was built for. */
        String status = "";
        /** The card's page (ya.b.D and the others), for the probe. */
        String page = "";
        /** The current leg's transport type. */
        String type = "";

        /** The capsule: capsuleLeft* / capsuleRight*. */
        boolean leftIcon;
        String leftWhite = "";
        String leftLine = "";
        int leftLineColor;
        String rightWhite = "";
        String rightGray = "";
        String rightLine = "";
        int rightLineColor;

        /** The card: cardPrimaryInfo, cardSecondaryInfo and cardSecondaryLineName / Color. */
        String primary = "";
        String secondary = "";
        String secondaryLine = "";
        int secondaryLineColor;

        /** The lock screen's row: titleInLock / subTitleInLock. */
        String lockTitle = "";
        String lockSubtitle = "";

        /** cardStationOverview: two or three stops; null on the cards that carry none. */
        List<Node> stations;
        /** isCurStation: the train is at the middle stop rather than on its way to it. */
        boolean atStation;
        boolean twoStation;

        /** cardWaitingInformation's lines, on the waiting card only. */
        List<WaitLine> waiting;

        /** The current leg, for the page's line row and the island's badge. */
        String line = "";
        String direction = "";
        int lineBg = color(DEFAULT_COLOR);
        int lineText = Color.WHITE;

        /** The stop whose landmark is behind a card in transit (ya.d.a), "" for none. */
        String landmarkStation = "";
        /** An arrival card: the exit's landmark, then the city's, then the nation's (ya.b.O). */
        boolean arrivalArt;

        /** cardButtonText: the silent walking card's 「步行导航」, "" for none. */
        String button = "";
        /**
         * cardShowWeakInternet: 高德 says the GPS is weak (gpsSignalStatus 0). On the moving cards
         * ColorOS puts its weak-signal picture where the card's own icon is; the walking
         * navigation's card says it in words as well (ya.n.E).
         */
        boolean weakSignal;
        /** The walking navigation's bar: cardProgressPercent, and its two labels. */
        int progress = -1;
        String progressFrom = "";
        String progressTo = "";

        boolean subway() {
            return AmapTransitCard.subway(type);
        }

        boolean sameAs(Card o) {
            return o != null && kind.equals(o.kind) && status.equals(o.status)
                    && primary.equals(o.primary) && landmarkStation.equals(o.landmarkStation);
        }
    }

    /**
     * The card for [t], or null for an entity SceneService shows nothing for. The routing is
     * GaoDePtNaviSceneRouter.j's, in its order: off route, the trip's end, an end in the app, a
     * walking or cycling leg, a taxi, and every other leg a bus or subway card.
     */
    static Card of(Trip t) {
        if (t == null) return null;
        Card c;
        if (offRoute(t)) {
            c = offRouteCard(t);
        } else if (ARRIVE_FINAL_DESTINATION.equals(t.status)) {
            c = finalCard(t);
        } else if (END_NAVI_IN_APP.equals(t.status)) {
            return null;
        } else if (t.current != null && walkOrBike(t.current.type)) {
            // The walking navigation's card while 高德 navigates the walk (GaoDePtWalkRideHandler.d),
            // the silent one otherwise - the same card, as ColorOS keeps both on 536879317.
            c = t.walkNavi != null ? walkNaviCard(t, t.walkNavi) : null;
            if (c == null) c = walkCard(t);
        } else if (t.current != null && TAXI.equals(trim(t.current.type))) {
            return null;
        } else {
            c = busOrSubwayCard(t);
        }
        if (c == null) return null;
        Leg cur = t.current;
        if (cur != null) {
            c.type = trim(cur.type);
            if (cur.rides()) {
                c.line = trim(cur.lineName);
                c.direction = x(cur.lineDirection);
                c.lineBg = color(L(cur.lineBg));
                c.lineText = color(cur.lineText, Color.WHITE);
            }
        }
        return c;
    }

    /** ya.f.e. */
    static boolean offRoute(Trip t) {
        if (ARRIVE_FINAL_DESTINATION.equals(t.status)) return false;
        return t.offRoute || NOT_ON_WAY.equals(t.status);
    }

    /** ya.b.e: the bus or subway card for the milestone, or null when no builder takes it. */
    private static Card busOrSubwayCard(Trip t) {
        String status = trim(t.status);
        if (status.isEmpty()) return null;
        List<Leg> navi = t.navi;
        Leg cur = t.current;
        String type = cur == null ? "" : trim(cur.type);
        String eff = W(status, navi, cur);
        List<Node> overview = l(navi, cur, eff);
        String station = e0(t, cur, eff);
        String guide = Y(t, navi, cur, eff);
        Card c;
        switch (eff) {
            case ARRIVE_ORIGIN_NEARBY:
            case WAITING:
                c = waitingCard(cur, type, p(cur));
                break;
            case NEXT_STATION:
            case NEXT_DESTINATION:
                c = movingCard("下一站", station, guide, overview, NEXT_STATION, t, cur);
                break;
            case ARRIVE_COMMON_STATION:
                c = movingCard("当前站", station, guide, overview, ARRIVE_COMMON_STATION, t, cur);
                break;
            case ARRIVE_TRANSFER_STATION:
                c = transferCard(navi, cur, guide, type, overview, t);
                break;
            case ARRIVE_LINE_DESTINATION:
                c = arrivalCard(t, cur, type, overview);
                break;
            default:
                return null;
        }
        if (c == null) return null;
        c.status = eff;
        c.page = page(eff);
        // ya.b.e: isSignalStrong is gpsSignalStatus != 0; only K() and a() - 3, 4, 5 - show it.
        if (NEXT_STATION.equals(eff) || NEXT_DESTINATION.equals(eff)
                || ARRIVE_COMMON_STATION.equals(eff)) {
            c.weakSignal = t.gpsSignalStatus != null && t.gpsSignalStatus == 0;
        }
        // ya.d.a: a landmark only while in transit, and only for the stop the card names.
        if (NEXT_STATION.equals(eff) || NEXT_DESTINATION.equals(eff)
                || ARRIVE_COMMON_STATION.equals(eff)) {
            c.landmarkStation = trim(station);
        }
        return c;
    }

    /** ya.b.D. */
    private static String page(String eff) {
        switch (eff) {
            case ARRIVE_ORIGIN_NEARBY:
            case WAITING:
                return "pages/waiting";
            case NEXT_DESTINATION:
                return "pages/on_bus_2";
            case ARRIVE_LINE_DESTINATION:
                return "pages/arrival";
            default:
                return "pages/on_bus";
        }
    }

    /** ya.b.c: 到达起始站附近 and 候车. */
    private static Card waitingCard(Leg cur, String type, List<WaitLine> wait) {
        if (wait.isEmpty()) return null;
        WaitLine line = wait.get(0);
        Card c = new Card();
        c.kind = Card.KIND_WAITING;
        String dir = x(Q(cur, type));
        c.leftIcon = false;
        c.leftWhite = E(cur, type) ? dir : "";
        c.leftLine = B(line.name, type);
        c.leftLineColor = line.color;
        c.rightWhite = S(cur, type);
        c.rightGray = R(cur, type);
        c.rightLine = "";
        String station = cur == null ? "" : trim(cur.onName);
        c.primary = station;
        c.lockTitle = station;
        c.lockSubtitle = T(line);
        c.waiting = waitingInformation(wait);
        return c;
    }

    /** ya.m.a: one line with both of its times, or the first two lines with one each. */
    private static List<WaitLine> waitingInformation(List<WaitLine> wait) {
        List<WaitLine> out = new ArrayList<>();
        int count = wait.size();
        if (count == 1) {
            out.add(wait.get(0));
        } else if (count < 2) {
            out.add(new WaitLine());
        } else {
            for (int i = 0; i < 2; i++) {
                WaitLine w = wait.get(i);
                WaitLine one = new WaitLine();
                one.name = w.name;
                one.direction = w.direction;
                one.color = w.color;
                one.realtime1 = w.realtime1;
                out.add(one);
            }
        }
        return out;
    }

    /** ya.b.K (下一站, 3 and 4) and ya.b.a (当前站, 5): the same card with a different word. */
    private static Card movingCard(String word, String station, String guide, List<Node> overview,
                                   String overviewStatus, Trip t, Leg cur) {
        Card c = new Card();
        c.kind = Card.KIND_RIDING;
        c.leftIcon = true;
        c.leftWhite = word;
        c.leftLineColor = color(DEFAULT_COLOR);
        c.rightLineColor = color(DEFAULT_COLOR);
        c.rightWhite = station;
        c.primary = word + " " + station;
        c.secondary = guide;
        c.lockTitle = c.primary;
        c.lockSubtitle = guide;
        overview(c, overview, overviewStatus, nextSegment(t));
        return c;
    }

    /** ya.b.d: 到达换乘站. */
    private static Card transferCard(List<Leg> navi, Leg cur, String guide, String type,
                                     List<Node> overview, Trip t) {
        Leg next = f0(navi, cur);
        String lineName = next == null ? "" : next.lineName;
        String direction = next == null ? "" : next.lineDirection;
        String bg = next != null && !next.lineBg.isEmpty() ? next.lineBg
                : (cur == null ? null : cur.lineBg);
        int color = color(L(bg));
        String named = z(lineName, direction);
        String secondary = blank(named) ? trim(guide) : named;
        String nextType = next == null || next.type == null ? type : next.type;
        Card c = new Card();
        c.kind = Card.KIND_TRANSFER;
        c.leftIcon = true;
        c.leftWhite = "换乘";
        c.leftLineColor = color;
        c.rightLine = B(lineName, nextType);
        c.rightLineColor = color;
        c.rightWhite = blank(direction) ? "" : x(direction);
        c.primary = "准备换乘";
        c.secondaryLine = named;
        c.secondaryLineColor = color;
        c.secondary = "";
        c.lockTitle = c.primary;
        c.lockSubtitle = blank(secondary) ? c.primary : secondary;
        overview(c, overview, ARRIVE_TRANSFER_STATION, nextSegment(t));
        return c;
    }

    /** ya.b.b: 公交或地铁到站. A subway with an exit named puts the exit where 到站 would be. */
    private static Card arrivalCard(Trip t, Leg cur, String type, List<Node> overview) {
        String stop = P(t, cur);
        // The exit's chip is drawn in the first overview stop's colour, which is the leg's own
        // line colour. SceneService reads it out of that overview, so a leg with no stops between
        // its two ends has none to read and keeps the plain 到站 - 坦尾 → 如意坊, the last leg of
        // the 10-12 trip to 永庆坊, is one stop and drew no exit. Here the exit wins instead:
        // with the overview empty the colour comes from the leg itself.
        String lineColor = overview.isEmpty() ? hex(color(L(cur.lineBg)))
                : hex(overview.get(0).color);
        String exit = trim(t.exitName);
        Card c = new Card();
        c.kind = Card.KIND_ARRIVAL;
        c.rightWhite = stop;
        c.primary = stop;
        if (!subway(type) || exit.isEmpty()) {
            c.leftIcon = true;
            c.leftWhite = "到站";
            c.secondary = "已到站";
        } else {
            c.leftIcon = false;
            c.leftLine = exit;
            c.leftLineColor = color(lineColor);
            c.secondaryLine = exit;
            c.secondaryLineColor = color(lineColor);
        }
        c.lockTitle = stop;
        c.lockSubtitle = "已到站";
        c.arrivalArt = true;
        return c;
    }

    /** ya.a.a: the trip's end. */
    private static Card finalCard(Trip t) {
        String dest = trim(t.destStation);
        if (dest.isEmpty()) dest = "目的地";
        Card c = new Card();
        c.kind = Card.KIND_FINAL;
        c.status = ARRIVE_FINAL_DESTINATION;
        c.page = "pages/arrival";
        c.leftIcon = true;
        c.rightWhite = "已到达";
        c.primary = "已到达 " + dest;
        c.secondary = t.totalDuration > 0 ? "全程" + duration((int) t.totalDuration) : "本次行程结束";
        c.lockTitle = c.primary;
        c.lockSubtitle = c.secondary;
        return c;
    }

    /** ya.f.c. */
    private static Card offRouteCard(Trip t) {
        Card c = new Card();
        c.kind = Card.KIND_OFF_ROUTE;
        c.status = NOT_ON_WAY;
        c.page = "pages/notWalkAndBike";
        c.leftIcon = true;
        c.rightWhite = "行程偏航";
        c.primary = "你已偏离当前方案";
        c.secondary = "查看方案详情";
        c.lockTitle = "行程偏航";
        c.lockSubtitle = c.primary;
        return c;
    }

    /** ya.n.f: the silent card for a walk (or a ride by bike) between the trip's rides. */
    private static Card walkCard(Trip t) {
        int i = t.currentIndex();
        if (i < 0) return null;
        Leg cur = t.navi.get(i);
        boolean bike = BIKE.equals(trim(cur.type));
        String mode = bike ? "骑行" : "步行";
        String from = walkFrom(t, i);
        String to = walkTo(t, i);
        String summary = walkSummary(mode, number(cur.walkLength), number(cur.walkDuration));
        Card c = new Card();
        c.kind = Card.KIND_WALK;
        c.page = "pages/taxi_goto";
        c.leftIcon = true;
        c.leftWhite = mode + "至";
        c.rightWhite = to;
        c.primary = from;
        c.secondary = to;
        c.lockTitle = blank(to) ? mode + "导航" : mode + "至" + to;
        c.lockSubtitle = summary;
        // cardButtonText (L0 「%1$s导航」), onGaodePtNaviBeginNaviBtnClick: 高德's own navigation
        // of this walk, started from the card and never by itself.
        c.button = mode + "导航";
        return c;
    }

    /** ya.n.v0: what 高德's first line says once the walk is done. */
    private static final String NAVI_END = "导航结束";

    /**
     * ya.n.d: the walking navigation's card - the turn and how far, 高德's two lines, and the walk
     * as a bar from 「剩余…」 to 「目的地」. b() is d() for the frame that ends it; c() (导航开始)
     * is for a start 高德 does not send on this channel.
     */
    private static Card walkNaviCard(Trip t, WalkNavi w) {
        String mode = w.bike ? "骑行" : "步行";
        String left = distance(w.remainLength);
        if (left.isEmpty()) left = "导航中";
        // B(): 高德's first and second lines, or the first split at 「#」.
        String first = trim(w.firstDesc);
        String second = trim(w.secondDesc);
        String primary;
        String secondary;
        if (second.isEmpty()) {
            int at = first.indexOf('#');
            primary = at < 0 ? first : first.substring(0, at);
            secondary = at < 0 ? "" : first.substring(at + 1);
        } else {
            primary = first;
            secondary = NAVI_END.equals(first) ? "已到达终点" : second;
        }
        Card c = new Card();
        c.kind = Card.KIND_WALK_NAVI;
        c.page = "pages/walkAndBike";
        c.leftIcon = true;
        // E(): the weak-GPS form - where the walk goes, and to find open sky.
        if (w.gpsSignalStatus != null && w.gpsSignalStatus == 0) {
            c.weakSignal = true;
            String to = walkTo(t, t.currentIndex());
            secondary = "卫星信号弱，请" + mode + "到开阔地带";
            if (blank(to)) {
                c.leftWhite = "";
            } else {
                primary = mode + "至" + to;
                c.leftWhite = mode + "至";
                left = to;
            }
        }
        c.rightWhite = left;
        c.primary = primary;
        c.secondary = secondary;
        c.lockTitle = primary;
        c.lockSubtitle = blank(secondary) ? left : secondary;
        c.progress = walkPercent(w, first);
        c.progressFrom = walkLeft(w);
        c.progressTo = "目的地";
        return c;
    }

    /** ya.n.F: how much of the walk is done, 0..100. */
    private static int walkPercent(WalkNavi w, String first) {
        if (NAVI_END.equals(first)) return 100;
        int total = w.routeTotal == null ? 0 : w.routeTotal;
        int remain = w.routeRemain == null ? 0 : w.routeRemain;
        if (total <= 0) return 0;
        return clamp((int) Math.round(clamp(total - remain, 0, total) * 100.0 / total), 0, 100);
    }

    /** ya.n.e: 「剩余300米，约4分钟」, or 「开始」 when 高德 has not said how far. */
    private static String walkLeft(WalkNavi w) {
        if (w.routeTotal == null || w.routeTotal <= 0) return "开始";
        Integer remain = w.routeRemain != null ? w.routeRemain : w.remainLength;
        String d = distance(remain);
        String m = roundMinutes(w.routeRemainTime);
        return !d.isEmpty() && !m.isEmpty() ? "剩余" + d + "，约" + m : "开始";
    }

    /** ya.n.m: minutes to the nearest, as l() writes them. */
    private static String roundMinutes(Integer seconds) {
        if (seconds == null || seconds <= 0) return "";
        int total = (int) Math.rint(seconds / 60.0);
        if (total < 60) return total + "分钟";
        int h = total / 60;
        int m = total % 60;
        return m > 0 ? h + "小时" + m + "分钟" : h + "小时";
    }

    /** ya.n.C: where a walk goes - the next ride's stop, or the trip's destination. */
    private static String walkTo(Trip t, int i) {
        for (int k = i + 1; k < t.navi.size(); k++) {
            Leg l = t.navi.get(k);
            if (transit(l.type) && !trim(l.onName).isEmpty()) return trim(l.onName);
        }
        return trim(t.destStation);
    }

    /** ya.n.D: where it starts - the last ride's stop, the trip's origin, or 我的位置. */
    private static String walkFrom(Trip t, int i) {
        if (i > 0) {
            String off = trim(t.navi.get(i - 1).offName);
            if (!off.isEmpty()) return off;
        }
        String origin = trim(t.originStation);
        return origin.isEmpty() ? "我的位置" : origin;
    }

    /** ya.n.g: 「共步行743米，13分钟」. */
    private static String walkSummary(String mode, Integer metres, Integer seconds) {
        String d = distance(metres);
        String m = minutes(seconds);
        if (!d.isEmpty() && !m.isEmpty()) return "共" + mode + d + "，" + m;
        if (d.isEmpty()) return m;
        return "共" + mode + d;
    }

    // ------------------------------------------------------------------ the overview

    /** c0: the stage after the current one, whose line a transfer node is badged with. */
    private static Leg nextSegment(Trip t) {
        int i = t.currentIndex() + 1;
        return i <= 0 || i >= t.navi.size() ? null : t.navi.get(i);
    }

    /**
     * ya.k.b: the overview as the card reads it. A three-stop overview whose last stop is a
     * transfer is badged with the next stage's line; the train is between the first two stops on
     * 下一站 and at the middle one otherwise; two stops is isTwoStation, except at a transfer.
     */
    private static void overview(Card c, List<Node> items, String status, Leg nextStage) {
        boolean transfer = ARRIVE_TRANSFER_STATION.equals(status);
        int size = items.size();
        int badgeAt = -1;
        if (size == 3) {
            for (int i = size - 1; i >= 0; i--) {
                if (items.get(i).transfer) {
                    badgeAt = i;
                    break;
                }
            }
        }
        if (badgeAt == size - 1 && nextStage != null) {
            String code = lineCode(nextStage.lineName, nextStage.type);
            if (!code.isEmpty()) {
                Node n = items.get(badgeAt);
                n.badge = code;
                n.badgeColor = color(hexOrDefault(nextStage.lineBg));
            }
        }
        c.stations = items;
        c.atStation = !NEXT_STATION.equals(status);
        c.twoStation = !transfer && size == 2;
    }

    /** ya.k.c: 「7号线」 is 7, 「番29路」 番29. */
    static String lineCode(String lineName, String mode) {
        String s = trim(lineName);
        if (s.isEmpty()) return "";
        if (subway(mode)) return before(before(s, "号线"), "线");
        if (bus(mode)) return before(s, "路");
        return s;
    }

    /** ya.k.d: a colour 高德 may send without its '#'. */
    private static String hexOrDefault(String raw) {
        String s = trim(raw);
        if (s.isEmpty()) return DEFAULT_COLOR;
        return s.startsWith("#") ? s : "#" + s;
    }

    /** ya.b.l. */
    private static List<Node> l(List<Leg> navi, Leg cur, String status) {
        List<Node> out = new ArrayList<>();
        if (cur == null) return out;
        Set<String> transferNames = g0(navi, status, cur);
        if (ARRIVE_TRANSFER_STATION.equals(status)) return n(cur, f0(navi, cur));
        List<Node> list;
        if (NEXT_STATION.equals(status)) {
            list = h(navi, cur, transferNames);
        } else if (ARRIVE_COMMON_STATION.equals(status)) {
            list = m(cur, transferNames);
        } else if (NEXT_DESTINATION.equals(status)) {
            list = o(navi, cur, transferNames);
        } else {
            list = new ArrayList<>();
            int color = color(L(cur.lineBg));
            for (int i = 0; i < cur.via.size() && i < 3; i++) {
                list.add(new Node(cur.via.get(i).name, color, false));
            }
        }
        // ya.b.t: changing between a bus and a subway is not drawn as a transfer stop.
        if (j0(status, navi, cur) && !list.isEmpty()) {
            int last = list.size() - 1;
            Node n = list.get(last);
            if (n.transfer) list.set(last, new Node(n.name, n.color, false));
        }
        return list;
    }

    /** ya.b.h: 下一站, three stops with the one being approached in the middle. */
    private static List<Node> h(List<Leg> navi, Leg cur, Set<String> transferNames) {
        Leg next = f0(navi, cur);
        if (k0(cur, next)) return n(cur, next);
        int remain = cur.remain == null ? 0 : cur.remain;
        int offset = !G(remain, cur.via.size()) ? 1 : 0;
        Station[] three = threeNode(cur.via, remain, cur.onName, cur.offName, offset);
        return nodes(three, color(L(cur.lineBg)), transferNames);
    }

    /** ya.b.m: 当前站, three stops with the one the train is at in the middle. */
    private static List<Node> m(Leg cur, Set<String> transferNames) {
        int remain = cur.remain == null ? 0 : cur.remain;
        Station[] three = threeNode(cur.via, remain, cur.onName, cur.offName, 0);
        return nodes(three, color(L(cur.lineBg)), transferNames);
    }

    /** ya.b.o: 下一站即终点, two stops. */
    private static List<Node> o(List<Leg> navi, Leg cur, Set<String> transferNames) {
        Leg next = f0(navi, cur);
        if (k0(cur, next)) return n(cur, next);
        int remain = cur.remain == null ? 0 : cur.remain;
        Station[] two = twoNode(cur.via, remain, cur.onName, cur.offName);
        int color = color(L(cur.lineBg));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Node> out = new ArrayList<>();
        for (Station s : two) {
            String name = s.name;
            if (blank(name) || !seen.add(name)) continue;
            out.add(new Node(name, color, transferNames.contains(trim(name))));
        }
        return out;
    }

    /** ya.b.n: the transfer stop in the middle, the stop before it and the next line's first. */
    private static List<Node> n(Leg cur, Leg next) {
        int colorCur = color(L(cur.lineBg));
        int colorNext = color(L(next == null ? null : next.lineBg));
        Station[] three = transferNode(cur.via, cur.onName, cur.offName,
                next == null ? null : next.onName, next == null ? null : next.via,
                next == null ? null : next.offName);
        List<Node> out = new ArrayList<>();
        String prev = three[0].name;
        String here = three[1].name;
        if (!blank(prev) && !prev.equals(here)) out.add(new Node(prev, colorCur, false));
        out.add(new Node(here, colorCur, true));
        String after = three[2].name;
        if (!blank(after)) out.add(new Node(after, colorNext, false));
        return out;
    }

    private static List<Node> nodes(Station[] stations, int color, Set<String> transferNames) {
        List<Node> out = new ArrayList<>();
        for (Station s : stations) {
            out.add(new Node(s.name, color, transferNames.contains(trim(s.name))));
        }
        return out;
    }

    /** ya.b.g0: the stops this trip changes line at. */
    private static Set<String> g0(List<Leg> navi, String status, Leg cur) {
        Set<String> out = new LinkedHashSet<>();
        if (ARRIVE_TRANSFER_STATION.equals(status)) {
            String off = trim(cur.offName);
            if (!off.isEmpty()) out.add(off);
            return out;
        }
        for (int i = 0; i < navi.size(); i++) {
            Leg l = navi.get(i);
            String off = trim(l.offName);
            if (transit(l.type) && b0(navi, i) != null && !off.isEmpty()) out.add(off);
        }
        return out;
    }

    /** ya.b.j0: a bus to a subway or a subway to a bus, while riding. */
    private static boolean j0(String status, List<Leg> navi, Leg cur) {
        if (!NEXT_STATION.equals(status) && !NEXT_DESTINATION.equals(status)
                && !ARRIVE_COMMON_STATION.equals(status)) {
            return false;
        }
        if (cur == null) return false;
        Leg next = f0(navi, cur);
        if (next == null) return false;
        return (bus(cur.type) && subway(next.type)) || (subway(cur.type) && bus(next.type));
    }

    // ------------------------------------------------------------------ which stops (ya.e)

    /** ya.e.e: the via index the train is at. */
    private static int viaIndex(int viaSize, int remain) {
        return clamp(viaSize - remain, 0, viaSize - 1);
    }

    /** ya.e.a: the stop before, this one and the next; [focus] moves "this one" on by one. */
    private static Station[] threeNode(List<Station> via, int remain, String boarding,
                                       String off, int focus) {
        if (via.isEmpty()) {
            String on = trim(boarding);
            String end = off == null ? on : trim(off);
            return new Station[] {new Station(on), new Station(end), new Station(end)};
        }
        int last = via.size() - 1;
        int i = clamp(viaIndex(via.size(), remain) + focus, 0, last);
        Station prev = i == 0 ? new Station(trim(boarding)) : via.get(i - 1);
        return new Station[] {prev, via.get(i), after(via, i, last, off)};
    }

    /** ya.e.c: this stop and the next. */
    private static Station[] twoNode(List<Station> via, int remain, String boarding, String off) {
        if (via.isEmpty()) return new Station[] {new Station(trim(boarding)), new Station(trim(off))};
        int i = viaIndex(via.size(), remain);
        return new Station[] {via.get(i), after(via, i, via.size() - 1, off)};
    }

    /** ya.e.f. */
    private static Station after(List<Station> via, int i, int last, String off) {
        return i < last ? via.get(i + 1) : new Station(trim(off));
    }

    /** ya.e.d: around a transfer stop. */
    private static Station[] transferNode(List<Station> via, String boarding, String off,
                                          String nextBoarding, List<Station> nextVia,
                                          String nextOff) {
        String stop = trim(off);
        Station prev;
        if (via.isEmpty()) {
            prev = new Station(trim(boarding));
        } else {
            Station last = via.get(via.size() - 1);
            prev = via.size() >= 2 && trim(last.name).equals(stop) ? via.get(via.size() - 2) : last;
        }
        Station here = new Station(stop, Double.NaN, Double.NaN, true);
        List<Station> nv = nextVia == null ? Collections.emptyList() : nextVia;
        String nb = trim(nextBoarding);
        String no = trim(nextOff);
        Station next;
        if (blank(nb)) {
            next = !nv.isEmpty() ? nv.get(0) : new Station(no);
        } else if (!nb.equals(stop)) {
            next = new Station(nb);
        } else if (nv.isEmpty()) {
            next = new Station(blank(no) ? nb : no);
        } else {
            next = nv.get(0);
        }
        return new Station[] {prev, here, next};
    }

    // ------------------------------------------------------------------ the stop and the line

    /** ya.b.W: 下一站即终点 is drawn as 下一站 when the next stop is where the trip changes line. */
    static String W(String raw, List<Leg> navi, Leg cur) {
        if (NEXT_DESTINATION.equals(raw) && cur != null && k0(cur, f0(navi, cur))) {
            return NEXT_STATION;
        }
        return raw;
    }

    /** ya.b.k0: one stop left, and it is where the next ride starts. */
    private static boolean k0(Leg cur, Leg next) {
        if (cur.remain == null) return false;
        String off = trim(cur.offName);
        boolean same = !off.isEmpty() && next != null && off.equals(trim(next.onName));
        boolean z = cur.remain == 1 && (Boolean.TRUE.equals(cur.offTransfer) || same);
        return z && next != null;
    }

    /** ya.b.f0: the next leg with a line after the current one. */
    static Leg f0(List<Leg> navi, Leg cur) {
        int at = cur != null ? navi.indexOf(cur) : -1;
        if (cur == null) {
            for (int i = 0; i < navi.size(); i++) {
                if (navi.get(i).current) {
                    at = i;
                    break;
                }
            }
        }
        if (at >= 0) return b0(navi, at);
        for (int i = 0; i < navi.size(); i++) {
            if (!transit(navi.get(i).type)) continue;
            Leg l = b0(navi, i);
            if (l != null) return l;
        }
        return null;
    }

    /** ya.b.b0. */
    private static Leg b0(List<Leg> navi, int from) {
        for (int i = from + 1; i < navi.size(); i++) {
            Leg l = navi.get(i);
            if (transit(l.type) && !blank(l.lineName)) return l;
        }
        return null;
    }

    /** ya.b.e0: the stop a milestone names. */
    static String e0(Trip t, Leg cur, String status) {
        String on = cur == null ? "" : trim(cur.onName);
        String off = cur == null ? "" : trim(cur.offName);
        List<Station> via = cur == null ? Collections.emptyList() : cur.via;
        int remain = cur == null || cur.remain == null ? 0 : cur.remain;
        if (NEXT_STATION.equals(status)) return d0(via, remain, on, off);
        if (ARRIVE_COMMON_STATION.equals(status)) return X(via, remain, on, off);
        if (ARRIVE_TRANSFER_STATION.equals(status)) return blank(off) ? X(via, remain, on, off) : off;
        if (ARRIVE_LINE_DESTINATION.equals(status)) return P(t, cur);
        if (!NEXT_DESTINATION.equals(status)) return blank(on) ? off : on;
        if (!via.isEmpty()) {
            String name = trim(twoNode(via, remain, on, off)[1].name);
            if (!blank(name)) return name;
            if (blank(off)) return on;
        } else if (blank(off)) {
            return on;
        }
        return off;
    }

    /** ya.b.d0: the stop being approached. */
    private static String d0(List<Station> via, int remain, String on, String off) {
        if (via.isEmpty()) return blank(off) ? on : off;
        Station[] three = threeNode(via, remain, on, off, 0);
        String next = three[2].name;
        String here = three[1].name;
        if (G(remain, via.size())) return blank(here) ? on : here;
        if (!blank(next) && !next.equals(here)) return next;
        if (!blank(off)) return off;
        return blank(here) ? on : here;
    }

    /** ya.b.X: the stop the train is at. */
    private static String X(List<Station> via, int remain, String on, String off) {
        if (via.isEmpty()) return blank(on) ? off : on;
        String name = threeNode(via, remain, on, null, 0)[1].name;
        return blank(name) ? on : name;
    }

    /** ya.b.G: more stops left than there are between the two ends - still at the first. */
    private static boolean G(int remain, int viaSize) {
        return viaSize > 0 && remain > viaSize;
    }

    /** ya.b.P: the stop this leg lets you off at, else the trip's destination. */
    static String P(Trip t, Leg cur) {
        String off = cur == null ? "" : trim(cur.offName);
        return !off.isEmpty() ? off : trim(t.destStation);
    }

    /** ya.b.Y: 高德's guide line, or 「N站 XX下车」 while riding. */
    private static String Y(Trip t, List<Leg> navi, Leg cur, String status) {
        String guide = trim(t.guideInfo);
        if (!guide.isEmpty() && !l0(status)) return guide;
        if (cur == null || cur.remain == null || cur.remain <= 0) return "";
        int n = Math.max(cur.remain, 1);
        if (m0(navi, cur)) return n + "站 " + trim(cur.offName) + "换乘";
        String stop = P(t, cur);
        return !blank(stop) ? n + "站 " + stop + "下车" : n + "站后下车";
    }

    /** ya.b.l0: the milestones whose second line is the 「N站」 sentence. */
    private static boolean l0(String status) {
        return NEXT_STATION.equals(status) || NEXT_DESTINATION.equals(status)
                || ARRIVE_COMMON_STATION.equals(status);
    }

    /** ya.b.m0: the next ride is the same kind as this one, a bus or a subway. */
    private static boolean m0(List<Leg> navi, Leg cur) {
        Leg next = cur == null ? null : f0(navi, cur);
        if (next == null) return false;
        String a = trim(cur.type);
        String b = trim(next.type);
        return !a.isEmpty() && !b.isEmpty() && a.equals(b) && busOrSubway(a);
    }

    /** ya.b.z: 「3号线(往XX)」. */
    private static String z(String lineName, String direction) {
        String s = trim(lineName);
        String d = x(direction);
        if (!s.isEmpty() && !d.isEmpty()) return s + "(" + d + ")";
        return !s.isEmpty() ? s : d;
    }

    /** ya.b.x: 「往XX」, whatever 高德 wrote around it. */
    static String x(String raw) {
        String s = trim(raw);
        if (s.startsWith("往")) s = s.substring(1);
        s = trim(s.replaceAll("方向$", ""));
        return s.isEmpty() ? "" : "往" + s;
    }

    /** ya.b.B: the line's short name for the capsule's chip. */
    static String B(String name, String type) {
        String s = trim(name);
        if (subway(type)) return before(before(s, "号线"), "线");
        return before(s, "路");
    }

    /** ya.b.L. */
    static String L(String raw) {
        String s = trim(raw);
        return !s.isEmpty() && s.startsWith("#") ? s : DEFAULT_COLOR;
    }

    // ------------------------------------------------------------------ waiting (ya.b.p and on)

    /** ya.b.p: the lines a waiting card lists. */
    private static List<WaitLine> p(Leg cur) {
        List<WaitLine> out = new ArrayList<>();
        if (cur == null) return out;
        List<Realtime> rt = cur.realtime;
        List<String> names = h0(cur, rt);
        String service = serviceTime(cur);
        if (names.size() > 1) {
            String none = "暂无时间信息";
            for (int i = 0; i < names.size() && i < 2; i++) {
                String name = names.get(i);
                Realtime match = w(name, rt);
                Realtime timed = match != null && !trim(match.mainTitle).isEmpty() ? match : null;
                out.add(q(cur, name, timed, blank(service) ? none : service, null, i0(cur, match)));
            }
            return out;
        }
        String hint = "详情关注站台信息";
        String none = "暂无时间信息";
        Realtime first = rt.isEmpty() ? null : rt.get(0);
        Realtime second = rt.size() > 1 ? rt.get(1) : null;
        boolean firstTimed = first != null && !trim(first.mainTitle).isEmpty();
        boolean secondTimed = second != null && !trim(second.mainTitle).isEmpty();
        if (firstTimed) {
            String after = secondTimed ? i(second) : (!blank(service) ? service : hint);
            Realtime shown = first;
            if (subway(cur.type)) {
                Integer min = u(first.mainTitle);
                shown = first.copyWithMain(min == null ? trim(first.mainTitle)
                        : "列车预计 " + min + " 分钟进站");
            }
            out.add(q(cur, null, shown, null, after, i0(cur, first)));
        } else {
            out.add(q(cur, null, first, blank(service) ? none : service, hint, i0(cur, first)));
        }
        return out;
    }

    /** ya.b.q: one waiting line, each field 高德's realtime item's where it has one. */
    private static WaitLine q(Leg cur, String nameOverride, Realtime first, String primaryOverride,
                              String secondOverride, String directionOverride) {
        String name = trim(nameOverride);
        if (name.isEmpty()) name = first == null ? "" : trim(first.lineName);
        if (name.isEmpty()) name = cur.lineName;
        String primary = first == null ? "" : trim(first.mainTitle);
        if (primary.isEmpty()) primary = trim(primaryOverride);
        String dir = trim(directionOverride);
        if (dir.isEmpty()) dir = first == null ? "" : trim(first.lineDirection);
        if (dir.isEmpty()) dir = cur.lineDirection;
        WaitLine w = new WaitLine();
        w.name = trim(name);
        w.direction = x(dir);
        w.color = color(L(cur.lineBg));
        w.realtime1 = primary;
        w.realtime2 = trim(secondOverride);
        return w;
    }

    /** ya.b.h0: the lines that stop here - the realtime list's, else the leg's 「a/b」. */
    private static List<String> h0(Leg cur, List<Realtime> rt) {
        LinkedHashSet<String> fromRt = new LinkedHashSet<>();
        for (Realtime r : rt) {
            String n = trim(r.lineName);
            if (!n.isEmpty()) fromRt.add(n);
        }
        if (fromRt.size() > 1) return new ArrayList<>(fromRt);
        LinkedHashSet<String> split = new LinkedHashSet<>();
        for (String s : cur.lineName.split("[/／]")) {
            String n = trim(s);
            if (!n.isEmpty()) split.add(n);
        }
        if (!split.isEmpty()) return new ArrayList<>(split);
        if (!fromRt.isEmpty()) return new ArrayList<>(fromRt);
        List<String> one = new ArrayList<>();
        one.add(cur.lineName);
        return one;
    }

    /** ya.b.j: 「首06:00 末23:16」. */
    private static String serviceTime(Leg cur) {
        String start = trim(cur.startTime);
        String end = trim(cur.endTime);
        if (!start.isEmpty() && !end.isEmpty()) return "首" + start + " 末" + end;
        if (!start.isEmpty()) return "首" + start;
        if (!end.isEmpty()) return "末" + end;
        return "";
    }

    /** ya.b.i: the second arrival, 「下一班 3分钟」. */
    private static String i(Realtime item) {
        String tip = item == null ? "" : trim(item.orderTip);
        String main = item == null ? "" : trim(item.mainTitle);
        if (!tip.isEmpty() && !main.isEmpty()) return tip + " " + main;
        if (!tip.isEmpty()) return tip;
        return main;
    }

    /** ya.b.i0. */
    private static String i0(Leg cur, Realtime item) {
        if (bus(cur.type)) return x(item == null ? "" : item.lineDirection);
        return x(cur.lineDirection);
    }

    /** ya.b.w: the realtime item for a line, its name compared without spaces or slashes. */
    private static Realtime w(String lineName, List<Realtime> rt) {
        String want = M(lineName);
        for (Realtime r : rt) if (M(r.lineName).equals(want)) return r;
        return null;
    }

    /** ya.b.M. */
    private static String M(String raw) {
        return trim(raw.replace(" ", "").replace("　", "").replace("/", "")
                .replace("／", "")).toLowerCase(Locale.ROOT);
    }

    /** ya.b.Q: the direction the waiting capsule names. */
    private static String Q(Leg cur, String type) {
        if (cur == null) return "";
        if (!bus(type)) return trim(cur.lineDirection);
        Realtime r = cur.realtime.isEmpty() ? null : cur.realtime.get(0);
        return r == null ? "" : trim(r.lineDirection);
    }

    /** ya.b.E: whether the next vehicle's time is known. */
    private static boolean E(Leg cur, String type) {
        if (cur == null) return false;
        Realtime r = cur.realtime.isEmpty() ? null : cur.realtime.get(0);
        String main = r == null ? "" : trim(r.mainTitle);
        if (!bus(type)) return subway(type) && !main.isEmpty();
        return !main.isEmpty() && !trim(r.orderTip).isEmpty();
    }

    /** ya.b.S: the waiting capsule's right half - the next vehicle's time, or the direction. */
    private static String S(Leg cur, String type) {
        if (cur == null) return "";
        Realtime r = cur.realtime.isEmpty() ? null : cur.realtime.get(0);
        String main = r == null ? "" : trim(r.mainTitle);
        if (bus(type)) {
            if (!main.isEmpty()) return main;
            String dir = r == null ? "" : trim(r.lineDirection);
            if (dir.isEmpty()) dir = trim(cur.lineDirection);
            return x(dir);
        }
        if (!subway(type)) return x(cur.lineDirection);
        if (main.isEmpty()) return x(cur.lineDirection);
        Integer min = u(main);
        return min == null ? main : min + "分钟";
    }

    /** ya.b.R: 「进站」 after a subway's minutes. */
    private static String R(Leg cur, String type) {
        if (cur == null || !subway(type)) return "";
        Realtime r = cur.realtime.isEmpty() ? null : cur.realtime.get(0);
        String main = r == null ? "" : trim(r.mainTitle);
        return main.isEmpty() || u(main) == null ? "" : "进站";
    }

    /** ya.b.T: 「7号线(往燕山)」. */
    private static String T(WaitLine line) {
        String name = trim(line.name);
        String dir = trim(line.direction);
        if (!name.isEmpty() && !dir.isEmpty()) return name + "(" + dir + ")";
        return !dir.isEmpty() ? dir : name;
    }

    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分钟");

    /** ya.b.u: the minutes in 「3分钟」. */
    private static Integer u(String text) {
        Matcher m = MINUTES.matcher(trim(text));
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ words for numbers

    /** ya.a.d: a trip's length. */
    static String duration(int seconds) {
        int minutes = Math.max((int) Math.ceil(seconds / 60.0), 1);
        if (minutes < 60) return minutes + "分钟";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "小时" : h + "小时" + m + "分钟";
    }

    /** ya.n.n. */
    private static String distance(Integer metres) {
        if (metres == null || metres <= 0) return "";
        if (metres < 1000) return metres + "米";
        double km = metres / 1000.0;
        String v = km % 1.0 == 0.0 ? String.valueOf((int) km)
                : String.format(Locale.ROOT, "%.1f", km);
        return v + "公里";
    }

    /** ya.n.o. */
    private static String minutes(Integer seconds) {
        if (seconds == null || seconds <= 0) return "";
        int m = seconds < 60 ? 1 : (int) Math.ceil(seconds / 60.0);
        if (m < 60) return m + "分钟";
        int h = m / 60;
        int r = m % 60;
        return r > 0 ? h + "小时" + r + "分钟" : h + "小时";
    }

    /** ya.n.x: a number 高德 sent as words. */
    private static Integer number(String raw) {
        String s = trim(raw);
        if (s.isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ small things

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Kotlin's substringBefore: everything before the first [delim], or all of it. */
    private static String before(String s, String delim) {
        int at = s.indexOf(delim);
        return at < 0 ? s : s.substring(0, at);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static int color(String s) {
        return color(s, color0());
    }

    private static int color0() {
        return 0xff4a86ff;
    }

    static int color(String s, int fallback) {
        String v = trim(s);
        if (v.isEmpty()) return fallback;
        try {
            return Color.parseColor(v.startsWith("#") ? v : "#" + v);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xffffff);
    }
}
