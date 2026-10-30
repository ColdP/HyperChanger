// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.animation.TimeInterpolator;

/**
 * The spring ColorOS's immersive host moves a page's surface on (SystemUIPlugin a6.k, on its own
 * androidx.dynamicanimation SpringForce: d() is the bounce, damping ratio 1 - d; e() the response,
 * w = 2 pi / e), as an interpolator over a fixed span from rest to rest - which is all a page
 * appearing or going needs.
 *
 * The countdown's scale is bounce 0.2, the map's 0, both response 0.45s.
 */
public final class PageSpring {

    private PageSpring() {
    }

    /** How long it takes to come within a thousandth of the way. */
    public static long durationMs(float response, float bounce) {
        return Math.round(span(response, bounce) * 1000);
    }

    /** 0 to 1 over durationMs: 1 - e^(-zwt) (cos(wd t) + zw / wd sin(wd t)), or its z = 1 limit. */
    public static TimeInterpolator interpolator(float response, float bounce) {
        final double w = 2 * Math.PI / response;
        final double z = Math.min(1.0, Math.max(0.0, 1.0 - bounce));
        final double span = span(response, bounce);
        if (z >= 1.0) {
            return f -> {
                double x = w * f * span;
                return (float) (1 - (1 + x) * Math.exp(-x));
            };
        }
        final double wd = w * Math.sqrt(1 - z * z);
        return f -> {
            double t = f * span;
            return (float) (1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t)));
        };
    }

    private static double span(float response, float bounce) {
        double w = 2 * Math.PI / response;
        double z = Math.min(1.0, Math.max(0.0, 1.0 - bounce));
        // e^(-zwt) < 0.001; critically damped, (1 + wt) e^(-wt) < 0.001 at wt = 9.2.
        return z >= 1.0 ? 9.2 / w : 6.91 / (z * w);
    }
}
