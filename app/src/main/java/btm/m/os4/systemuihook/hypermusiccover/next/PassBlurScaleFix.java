// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps a window's PassBlur texture at the scale SurfaceFlinger draws it at.
 *
 * The background behind the notification shade and the control centre is a PassBlur:
 * SurfaceFlinger draws the home screen into a SurfaceTexture and the shade's window samples it.
 * ViewRootImpl keeps two scales for it, both persist.sys.background_blur_scale (0.25) to start:
 * mTexScale, the texture's size and the scale the window samples it at, and mDrawSfScale, the
 * scale SurfaceFlinger is told to draw at. mTexScale only ever goes up; getReqSfScale brings
 * mDrawSfScale back down.
 *
 * On the lock screen both go to 1.0 - the clock's container (clock_container_style1) asks for a
 * full-size texture when it is attached, and the renderer asks for 1.0 while the lock screen's
 * notifications are up. Leaving the lock screen, the renderer asks for 0.25 again and gets it, the
 * texture stays full size, and SurfaceFlinger's quarter-size picture in its top left corner is
 * sampled as the whole screen: the home screen magnified four times behind the panels. Seen on a
 * tester's Xiaomi 17 Ultra (OS4.0.0.28, magazine_c clock) and caught with a diagnostic build
 * (2026-10-01); aligning the two after getReqSfScale ended it.
 *
 * The texture scale is brought down to the draw scale and the texture resized, the same way
 * ViewRootImpl resizes it itself. A view that wants 1.0 again raises both together, as before.
 */
final class PassBlurScaleFix {

    private PassBlurScaleFix() {
    }

    private static final String TAG = "MCPassBlur: ";
    private static final float EPS = 0.001f;

    private static Field sTexScale;
    private static Field sDrawSfScale;
    private static Method sCheckSurTexSize;
    private static int sAligned;

    static void install() {
        try {
            // Built at run time: a constant class name is one R8 may rewrite.
            final Class<?> vri = Class.forName(String.join(".", "android", "view", "ViewRootImpl"));
            sTexScale = vri.getDeclaredField("mTexScale");
            sTexScale.setAccessible(true);
            sDrawSfScale = vri.getDeclaredField("mDrawSfScale");
            sDrawSfScale.setAccessible(true);
            sCheckSurTexSize = vri.getDeclaredMethod("checkSurTexSize");
            sCheckSurTexSize.setAccessible(true);
            Xp.hookAll("sysui.shade.scale", vri, "getReqSfScale", chain -> {
                final Object result = chain.proceed();
                align(chain.getThisObject());
                return result;
            });
            Xp.log(TAG + "installed");
        } catch (Throwable t) {
            // A build without PassBlur, or with its fields renamed: nothing to keep in step.
            Xp.log(TAG + "not installed: " + t);
        }
    }

    private static void align(Object root) {
        try {
            final float tex = sTexScale.getFloat(root);
            final float draw = sDrawSfScale.getFloat(root);
            if (draw <= 0f || tex <= draw + EPS) return;
            sTexScale.setFloat(root, draw);
            sCheckSurTexSize.invoke(root);
            if (++sAligned <= 5) Xp.log(TAG + "texture scale " + tex + " -> " + draw);
        } catch (Throwable t) {
            Xp.w(TAG + "align failed: " + t);
        }
    }
}
