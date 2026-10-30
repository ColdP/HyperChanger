// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit;
import java.util.ArrayList;
import java.util.List;

/**
 * Which picture goes behind a station: ColorOS 17's landmark catalogue for 高德's transit card,
 * copied from SceneService (com.oplus.sdp.lb.b, the GAODE half of
 * PublicTransportDestinationImageUtil.CityCodeSource; ColorOS 17, 2026-09-28 build).
 *
 * Each city is 高德's city code (destCitycode: "010" Beijing, "0574" Ningbo), the folder its
 * pictures sit in on OPPO's CDN, and its landmarks; each landmark is a picture's name and the
 * points it stands for. A station within RADIUS_M of any of a landmark's points gets that
 * landmark's picture - the first one listed that matches, as SceneService takes them
 * (com.oplus.sdp.lb.c.e). The points are 高德's own coordinates (GCJ-02), the ones its
 * via_st_list and port_list carry; the Baidu half of the catalogue is BD-09 and is not this.
 *
 * The pictures are OPPO's, served without a key (checked 2026-10-01: 365 of the 417 names
 * answer; the missing ones are a few landmarks and some cities' defaults, which is why every
 * lookup falls back - see AmapTransitScene.Art):
 *   busnav/<city>/<name>.webp   the landmark, animated (807x378, 29 frames)
 *   busnav/<city>/<name>.png    the same, still
 *   landmark/<city>/<name>.png  its name, white, for under it (423x66)
 *   busnav/<city>/DefaultDay.png, DefaultNight.png    the city's own, for an arrival
 *   busnav/subway/NationalDefaultDay.png, ...Night    any subway arrival without one
 *   busnav/bus/DefaultDay.png, DefaultNight.png       any bus arrival without one
 * "baidu" in the path is OPPO's: both map apps' cards draw from the one set.
 */
final class AmapTransitLandmarks {

    private AmapTransitLandmarks() {
    }

    static final String CDN =
            "https://ocs-cn-south1.heytapcs.com/pantanal-servicegov-cn/intent/baidu/";

    /** SceneService's 0.8km (PublicTransportDestinationImageUtil.c). */
    static final double RADIUS_M = 800.0;

    /** A matched landmark: its city's folder and its own name. */
    static final class Match {
        final String city;
        final String name;

        Match(String city, String name) {
            this.city = city;
            this.name = name;
        }

        String animatedUrl() {
            return CDN + "busnav/" + city + "/" + name + ".webp";
        }

        String stillUrl() {
            return CDN + "busnav/" + city + "/" + name + ".png";
        }

        String labelUrl() {
            return CDN + "landmark/" + city + "/" + name + ".png";
        }

        @Override
        public String toString() {
            return city + "/" + name;
        }
    }

    /**
     * The landmark a point stands near, or null.
     *
     * [cityCode] only narrows the search. The entity this module hands its page and its island is
     * built out of a real ride's own card, which says nothing about a city - 高德's card carries
     * no `destCitycode` - so with no code at all the whole catalogue is searched instead. A
     * landmark within 0.8km is its own answer; without this the ride always fell back to the
     * national picture and no city's landmarks could ever be reached.
     */
    static Match near(String cityCode, double lat, double lng) {
        if (!valid(lat, lng)) return null;
        City named = city(cityCode);
        if (named != null) {
            Match m = near(named, lat, lng);
            if (m != null) return m;
        }
        for (City c : cities()) {
            if (c == named) continue;
            Match m = near(c, lat, lng);
            if (m != null) return m;
        }
        return null;
    }

    /** The landmark that city's own table has within reach of the point, or null. */
    private static Match near(City c, double lat, double lng) {
        for (Landmark l : c.landmarks) {
            for (int i = 0; i + 1 < l.points.length; i += 2) {
                if (distanceM(lat, lng, l.points[i], l.points[i + 1]) <= RADIUS_M) {
                    return new Match(c.folder, l.name);
                }
            }
        }
        return null;
    }

    /** The folder of [cityCode]'s pictures, or null for a city the catalogue does not have. */
    static String folder(String cityCode) {
        City c = city(cityCode);
        return c == null ? null : c.folder;
    }

    /** How far from a city's landmarks a point is still taken to be in that city. */
    private static final double CITY_M = 50_000.0;

    /**
     * The folder of the catalogue's city a point is in, for a trip that names no city code: the
     * nearest city with a landmark within CITY_M. 高德's plan names the city by its adcode
     * ("440100"), not by the city code the catalogue is keyed by, so the point is what tells.
     */
    static String folderNear(double lat, double lng) {
        if (!valid(lat, lng)) return null;
        String best = null;
        double bestM = CITY_M;
        for (City c : cities()) {
            for (Landmark l : c.landmarks) {
                for (int i = 0; i + 1 < l.points.length; i += 2) {
                    double d = distanceM(lat, lng, l.points[i], l.points[i + 1]);
                    if (d < bestM) {
                        bestM = d;
                        best = c.folder;
                    }
                }
            }
        }
        return best;
    }

    /** The city's own arrival picture, day or night. */
    static String cityDefaultUrl(String folder, boolean night) {
        return CDN + "busnav/" + folder + "/" + (night ? "DefaultNight" : "DefaultDay") + ".png";
    }

    /** The nationwide one, for a subway or a bus. */
    static String nationalDefaultUrl(boolean subway, boolean night) {
        return subway
                ? CDN + "busnav/subway/" + (night ? "NationalDefaultNight" : "NationalDefaultDay") + ".png"
                : CDN + "busnav/bus/" + (night ? "DefaultNight" : "DefaultDay") + ".png";
    }

    static boolean valid(double lat, double lng) {
        return !(lat == 0.0 && lng == 0.0) && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /** Haversine on a 6371km sphere, as SceneService measures it. */
    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dp / 2.0), 2.0)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2.0), 2.0);
        return 2.0 * 6371000.0 * Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, a))));
    }

    // ---------------------------------------------------------------- the catalogue

    private static final class Landmark {
        final String name;
        /** lat, lng, lat, lng... */
        final double[] points;

        Landmark(String name, double[] points) {
            this.name = name;
            this.points = points;
        }
    }

    private static final class City {
        final String folder;
        final String code;
        final List<Landmark> landmarks;

        City(String folder, String code, List<Landmark> landmarks) {
            this.folder = folder;
            this.code = code;
            this.landmarks = landmarks;
        }
    }

    private static volatile List<City> sCities;

    private static City city(String cityCode) {
        if (cityCode == null) return null;
        String code = cityCode.trim();
        if (code.isEmpty()) return null;
        for (City c : cities()) {
            if (c.code.equals(code)) return c;
        }
        return null;
    }

    private static List<City> cities() {
        List<City> list = sCities;
        if (list != null) return list;
        list = new ArrayList<>(ROWS.length);
        for (String[] row : ROWS) {
            List<Landmark> landmarks = new ArrayList<>();
            for (String lm : row[2].split("\\|")) {
                int at = lm.indexOf('@');
                String[] pts = lm.substring(at + 1).split("[;,]");
                double[] points = new double[pts.length];
                for (int i = 0; i < pts.length; i++) points[i] = Double.parseDouble(pts[i]);
                landmarks.add(new Landmark(lm.substring(0, at), points));
            }
            list.add(new City(row[0], row[1], landmarks));
        }
        sCities = list;
        return list;
    }

    /**
     * {folder, 高德 city code, "Name@lat,lng;lat,lng|Name@..."}, in SceneService's order (its
     * CITY_ROWS), the landmarks in each in its order too: the first match wins.
     */
    private static final String[][] ROWS = {
            {"beijing", "010", "ForbiddenCity@39.902978,116.399541;39.914842,116.391537;39.923297,116.396767;39.915172,116.402905;39.907736,116.40154;39.907463,116.391652|SummerPalace@39.987091,116.263917;39.99066,116.28082;39.997978,116.281892;39.980622,116.280718;40.001964,116.268776;40.002798,116.273997;39.985717,116.263339|OldSummerPalace@40.012662,116.318115;39.999837,116.311919;40.005977,116.314941;39.99945,116.309425|HeavenTemple@39.874536,116.413331;39.883816,116.41985;39.888185,116.412625;39.88101,116.400277;39.881167,116.405603;39.882332,116.420932|UniversalBeijingResort@39.853863,116.67939;39.845708,116.675259;39.849114,116.679144|YongheTemple@39.945211,116.416787;39.948891,116.416707"},
            {"shanghai", "021", "TheBund@31.241969,121.490214|OrientalPearlTower@31.239453,121.50084|JinganTemple@31.223075,121.445776;31.222691,121.446895|YuGarden@31.227829,121.492384;31.226584,121.489503;31.228705,121.487873|WukangBuilding@31.204569,121.438155|ShanghaiDisneyResort@31.148131,121.662302;31.140274,121.660214;31.133899,121.655525;31.141373,121.668024"},
            {"guangzhou", "020", "CantonTower@23.105602,113.324196;23.106751,113.324618|YuexiuPark@23.142001,113.261892;23.136279,113.271932;23.136565,113.262499;23.144794,113.26407;23.139703,113.261482|Shamian@23.105533,113.245722|SacredHeartCathedral@23.114027,113.260267|BaiyunMountain@23.160186,113.296648;23.216782,113.31151;23.166091,113.276822;23.196822,113.299845;23.161227,113.306107;23.182153,113.312715;23.188363,113.318009;23.167238,113.310106;23.196722,113.319941;23.194965,113.285632;23.181334,113.279187|Chimelong@23.00845,113.313142;22.998688,113.320515;23.004696,113.319297;22.99423,113.330245;22.991021,113.328432"},
            {"shenzhen", "0755", "WindowoftheWorld@22.536248,113.974348;22.536822,113.974116|HappyHarbour@22.544557,113.885124;22.549501,113.886137|CivicCenter@22.542786,114.058716;22.541129,114.061293|NantouAncientCity@22.543417,113.923211;22.540521,113.920524;22.544929,113.919578|FairyLakeSquare@22.564017,114.16632;22.567982,114.16821;22.564056,114.17036|SeaWorld@22.485267,113.91453;22.482149,113.917908;22.481203,113.913272|Xiaomeisha@22.604179,114.331467"},
            {"chengdu", "028", "ChunxiRoad@30.657912,104.078627;30.653351,104.079451;30.657652,104.082154|TianfuSquare@30.657372,104.064475;30.65742,104.066041|KuanzhaiAlley@30.664278,104.051487;30.662985,104.056196;30.665297,104.050354|ChengduPandaBase@30.725647,104.108;30.744614,104.114121;30.713711,104.103209;30.732102,104.145009;30.746498,104.12583|Sanxingdui@31.0002,104.222759;30.997312,104.225975|DuFuCottage@30.662568,104.030607;30.662444,104.027207|WuhuFantawild@31.356118,118.459644"},
            {"hangzhou", "0571", "WestLake@30.26238,120.09044;30.26836,120.129682;30.265271,120.140253;30.262848,120.163314;30.254762,120.164007;30.272383,120.164349;30.23979,120.162598;30.204003,120.152026;30.155201,120.098828;30.226636,120.173038;30.275805,120.099047;30.276662,120.128582;30.218684,120.119892;30.2261,120.140945;30.252586,120.126295;30.251837,120.138354|ZhejiangProvincialMuseum@30.16148,120.103866"},
            {"chongqing", "023", "JiefangMonument@29.55674,106.578004|Hongyadong@29.562723,106.579291"},
            {"wuhan", "027", "YellowCraneTower@30.543164,114.304328;30.542326,114.308411;30.546767,114.302253|WuhanYangtzeRiverBridge@30.552954,114.282352;30.546744,114.293587|GudeTemple@30.61899,114.305867|HubeiProvincialMuseum@30.559987,114.366395;30.563008,114.365625;30.561046,114.36281|EastLake@30.57471,114.370625;30.564994,114.365374;30.57475,114.374049;30.562937,114.371085;30.506011,114.399078;30.506587,114.423775;30.541876,114.411142;30.542137,114.419696;30.578504,114.457029;30.607215,114.424262;30.560729,114.444228;30.594948,114.427983;30.505577,114.438121"},
            {"suzhou", "0512", "HumbleAdministratorGarden@31.323352,120.631169;31.323518,120.626773|GateoftheOrient@31.317084,120.67959;31.317479,120.679798"},
            {"xian", "029", "XianBellTower@34.225571,108.942307|GiantWildGoosePagoda@34.216379,108.965179;34.21774,108.961013;34.222613,108.962955;34.223154,108.964063"},
            {"nanjing", "025", "MeilingPalace@32.046518,118.845634|ConfuciusTemple@32.020392,118.78944;32.023872,118.790892|MingXiaolingMausoleum@32.040046,118.8351;32.037503,118.848472;32.035481,118.858338;32.048325,118.834534;32.039629,118.841393;32.04189,118.836945;32.045632,118.83307;32.042543,118.83452;32.042177,118.837506|JimingTemple@32.060079,118.795805"},
            {"changsha", "0731", "InternationalFinanceSquare@28.192707,112.980607;28.193399,112.976998|YueluMountain@28.180322,112.946751;28.165795,112.947578;28.184045,112.954358;28.168897,112.944895"},
            {"zhengzhou", "0371", "ErqiSquare@34.752558,113.666313|HenanMuseum@34.787044,113.672308"},
            {"tianjin", "022", "FiveGreatAvenues@39.114875,117.215371;39.118567,117.197715;39.120341,117.187781;39.099672,117.198319;39.113115,117.204059;39.109161,117.206592;39.110651,117.197922;39.114749,117.203011;39.114553,117.205485|CenturyClock@39.133648,117.205488"},
            {"hefei", "0551", "XiaoyaojinPark@31.865909,117.295033;31.871293,117.294929;31.869835,117.2904|Hechai1972@31.795793,117.247092;31.799603,117.244744"},
            {"qingdao", "0532", "MayFourthSquare@36.060946,120.385471;36.064736,120.380293|ZhanqiaoPier@36.063886,120.314766;36.072222,120.321769;36.061956,120.323296;36.063486,120.320692;36.067802,120.313733;36.072061,120.320941"},
            {"dongguan", "0769", "DongGuanMemories@23.04362,113.743635;23.046118,113.745898|DongguanICC@23.013442,113.761093;23.011309,113.759606"},
            {"ningbo", "0574", "TianyiSquare@29.867968,121.55105;29.87169,121.553904;29.868568,121.554237;29.87117,121.556053|DrumTower@29.957005,121.723197"},
            {"foshan", "0757", "AncestralTemple@23.03024,113.114142;23.027069,113.112211|QinghuiGarden@22.835826,113.255913"},
            {"wuxi", "0510", "WuxiNanchanTemple@31.56658,120.308189;31.56585,120.3077|HuishanAncientTown@31.581642,120.270675;31.584086,120.275342;31.574183,120.274076;31.581416,120.2776"},
            {"shenyang", "024", "MukdenPalace@41.797552,123.455694;41.803273,123.453679|BeilingPark@41.845931,123.439221;41.839256,123.42857;41.856618,123.418214;41.862373,123.427489;41.839358,123.424176"},
            {"dalian", "0411", "ZhongshanSquare@38.920834,121.64438;38.92153,121.644016|FishermanWharf@38.877832,121.686278;38.877943,121.673687;38.878633,121.685805;38.880833,121.674788;38.878198,121.673449"},
            {"jinan", "0531", "BaotuSpring@36.648234,117.020155;36.659716,117.017131;36.661881,117.018527;36.664006,117.015188;36.660512,117.023522|ChaoranTower@36.671973,117.017182;36.686353,117.034195;36.672922,117.031131;36.673489,117.034764;36.671303,117.016703;36.672228,117.026907;36.679055,117.031329|QuanchengSquare@36.661939,117.025667"},
            {"harbin", "0451", "CentralStreet@45.768958,126.616028;45.765584,126.625804;45.776963,126.612323;45.779267,126.624195;45.766607,126.621013;45.776158,126.617279;45.778416,126.619861;45.768852,126.62388|SophiaCathedral@45.769651,126.626968"},
            {"changchun", "0431", "PuppetEmperorPalace@43.904897,125.34857;43.906649,125.352405|Jingyuetan@43.801334,125.453216;43.778629,125.449867;43.772708,125.510145;43.765355,125.502217;43.803303,125.450784"},
            {"fuzhou", "0591", "ThreeLanesandSevenAlleys@26.085963,119.299248;26.085379,119.29392;26.079376,119.297596;26.080231,119.294086|ZhongzhouIsland@26.057089,119.309684;26.043658,119.320595;26.049945,119.309801;26.052158,119.31296;26.046589,119.316476"},
            {"xiamen", "0592", "XiamenTwinTowers@24.43612,118.088205|XiamenShapowei@24.43799,118.087799;24.441053,118.085486"},
            {"nanchang", "0791", "BayiSquare@28.675312,115.902969;28.673937,115.904543|TengwangPavilion@28.680688,115.882424;28.673334,115.877757"},
            {"nanning", "0771", "QingxiuMountain@22.792736,108.368384;22.794409,108.372339;22.796227,108.39317;22.790106,108.4158;22.797247,108.412246;22.786177,108.42212;22.796462,108.421715|ChaoyangSquare@22.819645,108.320768;22.817304,108.320758"},
            {"guiyang", "0851", "HebinPark@26.57214,106.719345|QianlingshanPark@26.58719,106.69701;26.596976,106.703218;26.58984,106.700532"},
            {"kunming", "0871", "KunmingOldStreet@25.037675,102.706006;25.03818,102.719563;25.042196,102.708152;25.039738,102.710657|DianchiLake@24.994898,102.664492;24.984061,102.663955;24.976528,102.670468;24.938933,102.708419;24.984108,102.617994;24.908412,102.792141;24.822274,102.826191;25.035227,102.6387;24.901362,102.79044;24.96127,102.665575;24.984912,102.65996;24.973678,102.671344;24.894957,102.773706;24.823866,102.772574;24.768071,102.748606;24.940773,102.704174;24.93809,102.688702;24.933911,102.722352;24.982397,102.650698;24.804272,102.774005"},
            {"lanzhou", "0931", "ZhongshanBridge@36.059471,103.817245;36.062594,103.816232;36.065995,103.818995|LanzhouYellowRiverTower@36.07984,103.711037"},
            {"urumqi", "0991", "InternationalGrandBazaar@43.778901,87.618685|XinjiangMuseum@43.819832,87.584909"},
            {"shijiazhuang", "0311", "ShijiazhuangTVTower@38.020478,114.535802|HebeiMuseum@38.039859,114.522783;38.042952,114.522515"},
            {"taiyuan", "0351", "WuyiSquare@37.862576,112.573532;37.860193,112.573823|ShuangtaPark@37.849188,112.594846"},
            {"hohhot", "0471", "DazhaoTemple@40.798903,111.653187|SuiyuanCityGeneralOffice@40.827185,111.686768"},
            {"changzhou", "0519", "ChinaDinosaurPark@31.824717,119.997764;31.816905,120.002125;31.821033,119.985582;31.828533,119.986821;31.816358,120.005557|TiannanTemple@31.775402,119.968973"},
            {"xuzhou", "0516", "XuzhouMuseum@34.249964,117.186042;34.250197,117.190046|BaolianTemple@34.294535,117.267612"},
            {"nantong", "0513", "BellTowerSquare@32.019147,120.865715|NantongMuseum@32.010639,120.868814"},
            {"shaoxing", "0575", "LuXunHometown@29.991782,120.587037;29.991502,120.580573|ShaoxingOrchidPavilion@30.003845,120.581352;29.962411,120.577716;29.928953,120.575492;29.925904,120.502142;29.942055,120.502681"},
            {"wenzhou", "0577", "WumaStreet@28.013874,120.66066;28.017496,120.658084;28.009606,120.660759;28.012997,120.656995;27.979215,120.669589;27.966999,120.583973|XunshanPark@28.001133,120.667267;28.013874,120.66066;28.004578,120.663767;28.009606,120.660759;27.979215,120.669589;27.966999,120.583973;28.129016,121.002846"},
            {"wuhu", "0553", "WuhuAncientCity@31.324937,118.38067;31.332543,118.372137;31.323511,118.38554;31.32025,118.38659;31.312705,118.381713"},
    };
}
