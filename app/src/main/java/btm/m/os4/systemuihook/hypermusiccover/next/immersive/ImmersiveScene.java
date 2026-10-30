// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.immersive;
import android.view.ViewGroup;

/**
 * One kind of full-screen immersive page behind the lock screen, opened from a focus
 * notification's island - 高德's navigation map is the first; a countdown, a ride, a delivery are
 * the same shape. What they share lives in {@link ImmersiveHost}: where the page sits, when it may
 * be seen, the doze, and what the lock screen gives up for it (the clock goes small, the
 * wallpaper's cut-out goes). A scene only answers what is its own:
 *
 *   which island opens it   - {@link #serves}
 *   whether it can open     - {@link #ready}, told to the host with ImmersiveHost.readyChanged
 *   what it shows           - {@link #prepare} puts it into the host's slot; it tells the host
 *                             ImmersiveHost.contentChanged once there is something to show, and
 *                             {@link #hasContent} answers from then on
 *   how the doze keeps it   - {@link #needsDozeBeat}
 *
 * Everything is called on the main thread.
 */
public interface ImmersiveScene {

    /** For logs and the probe: {@code op immersive --es id <id>}. */
    String id();

    /** Whether a tap on this focus island opens this scene. */
    boolean serves(String pkg, boolean focus);

    /**
     * And whether it is this very notification: an app can post more than one focus island and
     * only one of them be this scene's - the clock's countdown is, its stopwatch is not.
     */
    default boolean servesKey(String key) {
        return true;
    }

    /**
     * Whether the page could be shown: the app is in the state that has one (高德 navigating).
     * A scene that stops being ready is closed and let go by the host.
     */
    boolean ready();

    /**
     * The host's slot is in the lock screen window and a lock screen is up: build the page into
     * it, hidden - the host shows the slot. Called once per readiness; the page is kept until
     * {@link #release}, so a tap later shows it at once.
     */
    void prepare(ViewGroup slot);

    /** There is a page to show. */
    boolean hasContent();

    /** The page went on screen or off it; dozing says which of the two screens it is on. */
    void onShown(boolean shown, boolean dozing);

    /**
     * The page's opacity, 0 to 1, for the host's fade in and out of it: what is under it - the
     * wallpaper, the cover - shows through as it falls. Only called while it is shown.
     */
    void setFade(float alpha);

    /** Take the page down: not ready any more, or the host is giving the slot up. */
    void release();

    /**
     * Whether the full-screen doze has to let the display up on a beat for this page to move.
     * True for a page drawn in another process, which never says when its picture changes; a
     * page drawn here lets the display up itself when it redraws (ImmersiveHost.lift).
     */
    boolean needsDozeBeat();

    /**
     * The band of the page that carries its information, as fractions of its height {top,
     * bottom}; the host blurs the page above and below it, where the lock screen's clock and its
     * islands and cards sit over it. Null: nothing blurred. For a LiveAlert page this is the
     * infoBounds it was told to keep its information inside, which is the same agreement from
     * the other side.
     */
    float[] sharpBand();

    /**
     * The id name of a view in this scene's own row - the island it opened from, a row in the
     * stack while the page is up - whose tap is the page's rather than the row's: 高德's turn
     * arrow switches the map between the route and where you are, as the button beside ColorOS's
     * card does. Null: the whole row is the row's. See ImmersiveHost.routeTouch.
     */
    default String rowTapTarget() {
        return null;
    }

    /** {@link #rowTapTarget} was tapped while the page was on the lit lock screen. */
    default void onRowTap() {
    }

    /**
     * Whether a finger landing here, in screen coordinates, is on something of the page's own
     * that takes a tap - a page drawn in this process under the keyguard's views never gets a
     * touch of its own, so the host hands it over (ImmersiveHost.routeTouch).
     */
    default boolean pageHit(float rawX, float rawY) {
        return false;
    }

    /** The finger is down on {@link #pageHit}'s target, or has left it or lifted. */
    default void pagePress(boolean down) {
    }

    /** {@link #pageHit}'s target was tapped. */
    default void onPageTap() {
    }

    /**
     * A navigation page: the lit lock screen is kept on while it is up, when the islands'
     * 屏幕常亮 setting asks for it (MiniPlayerConfig.NAV_KEEP_ON, #63). The countdown has a
     * switch of its own on its page.
     */
    default boolean isNavigation() {
        return false;
    }

    /** One line for the probe. */
    String describe();
}
