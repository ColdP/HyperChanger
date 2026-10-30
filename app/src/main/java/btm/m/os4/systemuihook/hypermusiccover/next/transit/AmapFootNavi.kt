// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit

import btm.m.os4.systemuihook.hypermusiccover.next.Xp
/**
 * 高德's own walking navigation of one walk of a trip, for the walking card's 「步行导航」 button.
 *
 * A bus or subway trip in 高德 has walks before, between and after its rides, and each has a
 * navigation of its own - the 「步行导航 ▸」 beside it in the trip's page; 「开始导航」 starts the
 * transit navigation only. ColorOS's silent walking card (ya.n.f) carries a 「步行导航」 button
 * (onGaodePtNaviBeginNaviBtnClick) that has 高德 start it - `beginWalkAndBikeInTripNaviOnSilentClick`,
 * over the OPPO build's own channel, which this phone's 高德 never opens. So the same is done
 * through 高德's own entry, on the button only: 高德 itself never starts it with the trip.
 *
 *   IFootNaviService.startNaviPage(PageBundle)      - com.autonavi.minimap.route.foot.impl
 *     GeoPoint end = bundle.getObject("endPoint");  - IOpenRoutePage's own keys
 *     GeoPoint start = bundle.getObject("startPoint");   // null -> the current location
 *     String name = bundle.getString("endPointName");
 *
 * The start point is filled in from the latest fix by 高德 itself, and it refuses a walk longer
 * than 100 km, so all this has to hand over is where the walk ends and what it is called.
 *
 * Nothing here draws anything: the navigation it opens is 高德's, and AmapNavScene already renders
 * that page (it was checked against 高德's own walking navigation on 2026-09-29).
 */
object AmapFootNavi {
    private const val TAG = "MCAmap: foot: "
    private const val SERVICE = "com.autonavi.bundle.footnavi.api.IFootNaviService"
    private const val MANAGER = "com.autonavi.wing.BundleServiceManager"
    private const val BUNDLE = "com.autonavi.common.PageBundle"
    private const val POINT = "com.autonavi.common.model.GeoPoint"
    /** `IOpenRoutePage`'s own bundle keys, which is what `startNaviPage` reads it by. */
    private const val END = "endPoint"
    private const val START = "startPoint"
    private const val END_NAME = "endPointName"

    /**
     * Starts 高德's walking navigation to (lat, lng), named [name], and answers what happened.
     * Every step is reflection into 高德, so any of them can be the one that throws - which is
     * also the only way to tell the probe which part of 高德 is not where it was.
     */
    fun start(cl: ClassLoader, lat: Double, lng: Double, name: String): String {
        return try {
            val point = Xp.findClass(POINT, cl).getConstructor().newInstance()
            point.javaClass.getMethod("setLonLat", Double::class.java, Double::class.java)
                .invoke(point, lng, lat)
            val bundle = Xp.findClass(BUNDLE, cl).getConstructor().newInstance()
            bundle.javaClass.getMethod("putObject", String::class.java, Any::class.java)
                .invoke(bundle, END, point)
            if (name.isNotEmpty()) {
                bundle.javaClass.getMethod("putString", String::class.java, String::class.java)
                    .invoke(bundle, END_NAME, name)
            }
            val manager = Xp.findClass(MANAGER, cl).getMethod("getInstance").invoke(null)
                ?: return "no BundleServiceManager"
            val service = manager.javaClass.getMethod("getBundleService", Class::class.java)
                .invoke(manager, Xp.findClass(SERVICE, cl))
            if (service == null) {
                // 高德's own answer for a build without the foot module, and not an error here.
                return "no IFootNaviService"
            }
            service.javaClass.getMethod("startNaviPage", bundle.javaClass).invoke(service, bundle)
            Xp.log(TAG + "asked 高德 for a walk to $name ($lat, $lng)")
            "started walk to $name"
        } catch (t: Throwable) {
            Xp.w(TAG + "start failed: $t")
            "failed: $t"
        }
    }
}
