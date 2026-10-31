// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.BuildConfig;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.AppleLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LocalLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyricSource;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyriconSource;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.NcmLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.OnlineLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.TrackName;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.WebLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.island.DateStatus;
import btm.m.os4.systemuihook.hypermusiccover.next.island.LockIslands;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerCollapseGesture;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerConfig;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import btm.m.os4.systemuihook.hypermusiccover.next.island.NotificationSink;
import btm.m.os4.systemuihook.hypermusiccover.next.island.NumStateProbe;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import btm.m.os4.systemuihook.hypermusiccover.LegacyShadeBridge;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapFocus;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapImmerse;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapNavScene;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapTransitScene;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.MetroCodeIsland;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.RideCodeExit;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupCloud;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupCodeIsland;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupPark;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.RearScreen;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Layout;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import android.util.Log;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Probe module: verifies that the HyperOS keyguard clock squeeze can be driven on
 * demand, independently of real notifications.
 *
 * Chain being verified (OS4.0.0.35, SDK 37):
 *   KeyguardClockContainer.notifStateChange(float y, boolean withAnim, NotificationTopChangeType)
 *     -> AnimationHelper.mClockAnima.notifStateChange(...)
 *     -> AllInOneClockAnimation: KeyguardClockNotifInteractor.setNotifY(y) -> ClockResult
 *                                updateClockComponentStyle(result, withAnim, type)
 */
public final class Main {

    /**
     * The framework instantiates this once per process it injects us into and attaches itself
     * before any callback runs. Everything below is static - the module is one piece of state
     * per process - so the interface is handed to {@link Xp} rather than kept as a field.
     */
    public Main() {
    }

    static final String TAG = "[MCProbe] ";
    private static final String ACTION = "btm.m.os4.systemuihook.hypermusiccover.next.PROBE";

    private static final String CLS_CONTAINER =
            "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String CLS_INTERACTOR =
            "com.android.keyguard.interactor.KeyguardClockNotifInteractor";
    static final String CLS_TIME_VIEW = "com.miui.clock.allInOne.TimeView";
    /** The OEM's own per-style index of clock parts. See dumpClockViewTypes(). */
    static final String CLS_CLOCK_VIEW_TYPE = "com.miui.clock.module.ClockViewType";
    static final String CLS_MEDIA_CARD = "com.android.systemui.statusbar.notification"
            + ".mediacontrol.MiuiMediaNotificationControllerImpl";
    private static final String CLS_KG_WALLPAPER_MANAGER =
            "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager";
    /**
     * Where the lock screen's notification stack is allowed to end.
     *
     * SystemUI computes that bound in KeyguardPanelViewController.nsslLockYPosition, a StateFlow
     * combined out of seven other flows. When the sensor is under the display AND the user has
     * fingerprint unlock on AND a print is enrolled, the stack stops just above the fingerprint
     * icon; otherwise it runs down to the indication area. Two of those seven values are that
     * setting and that enrolment, and forcing the pair is how the branch gets chosen.
     *
     * The class doing the combining is a Kotlin lambda that R8 names with a per-build ordinal,
     * so what follows is a shape to search for, never a name to look up. See findAvoidCombine.
     */
    static final String CLS_KG_PANEL =
            "com.android.keyguard.panel.KeyguardPanelViewController";

    /** SystemUI's own keyguard wallpaper manager, for the wallpaper type. See wallpaperKind(). */
    static volatile Object sKgWallpaperMgr;
    /**
     * Whether the lock wallpaper is a live one, which decides where the cover is drawn: the
     * wallpaper process's GL texture for a still, a view in our own keyguard layer for a live
     * one. See showVideoCover(). Re-read before every push, because the user can change the
     * wallpaper between two songs.
     */
    public static volatile boolean sVideoWallpaper;
    /** The live wallpaper's cut-out subject, hidden alongside deducted_image_view. */
    static volatile View sVideoFg;
    /** The live wallpaper itself, in the background layer, under our cover. */
    static volatile View sVideoBg;
    /** The bitmap currently on sCover, ours to recycle when it is replaced. */
    static volatile Bitmap sCoverBitmap;
    /** The frosted copy on sCover during lyric display. */
    static volatile Bitmap sCoverBlurBitmap;
    static volatile boolean sVideoCoverBlurred;

    public static volatile View sContainer;
    static volatile Class<?> sContainerCls;
    /**
     * The enum KeyguardClockContainer.notifStateChange takes as its third argument, read off
     * that method's own signature rather than looked up by name.
     *
     * com.miui.systemui.notification.data.repository.NotificationTopChangeType is only where
     * it lives on the OS4 build this was written against; a HyperOS 3 device reported no such
     * class, and looking it up by name there took the whole module down with it - one enum the
     * clock squeeze needs cost the cover, the media card and the depth hook as well. The
     * signature is what actually has to match, and it carries the class, so ask it.
     */
    private static volatile Class<?> sTypeCls;
    /** notifStateChange(float, boolean, NotificationTopChangeType), or null if it is gone. */
    static volatile Method sNotifStateChange;
    private static boolean sReceiverRegistered;
    /** The result code an op is answered with; the app sends 0. See ModuleBridge.OP_ACK. */
    static final int OP_ACK = 1;

    /**
     * While non-null, every notifStateChange() call - ours and the system's own
     * re-assertions from KeyguardClockNotifInteractor$collectNotificationYSource -
     * is coerced to this Y. This is how the cover mode takes ownership of the clock.
     */
    static volatile Float sHoldY;
    /** Last Y the system asked for, so we can hand control back on release. */
    static volatile float sLastSystemY = Float.NaN;
    /** Set while our own frame is inside setNotifY, so we don't read it back as the system's. */
    private static volatile boolean sSelfDriving;
    /** Per-frame setNotifY logging floods logcat at 8ms intervals; off unless asked for. */
    static volatile boolean sVerbose;
    /**
     * Uniform scale applied to the glyph size the OEM computes, at the last mile. The OEM's
     * own animation keeps driving every frame; we only shrink what it asks for. NaN = off.
     */
    static volatile float sSizeScale = Float.NaN;
    /**
     * The colour of the strip of the cover that the collapsed clock and its date are drawn on, as
     * ARGB. 0 = nothing measured, and every colour the OEM sets is left exactly as it is.
     *
     * Read off the COMPOSED cover - mirrored, blurred, bias-placed - rather than off the album
     * art, because that is the picture the glyphs are actually over: at the default bias the
     * sharp band starts below the clock, so what is behind it is the blur.
     *
     * Only HUE and SATURATION are taken from it; the lightness stays the OEM's. That split is the
     * whole design. Hue and saturation are what make a clock look like it belongs on the picture;
     * lightness is the half that decides whether it can be read at all, and the system has
     * already worked that out from a palette. Overriding the lightness as well is what used to
     * make the glyphs flip dark over a light cover.
     */
    static volatile int sCoverTint;
    /** From a debug op, so a colour can be tried without hunting for the artwork that gives it. */
    static volatile int sCoverTintOverride;
    /**
     * The grey the always-on clock is drawn in, held for the rest of the doze. NaN outside one.
     *
     * The colour that reaches the AOD's glyphs lives in `glassData[11..13]` and is pushed by the
     * OEM's palette pass - and that palette is computed from the wallpaper, which in cover mode is
     * our album art. So the doze clock turns gold a second or two after it comes up; measured on
     * the phone, and poking [11..13] on a dozing keyguard turns the whole clock red, which is how
     * the location was proved. The AOD is not drawn on the cover. The first triple the doze inks
     * with is taken down to its own lightness and then held, so the clock keeps the neutral the
     * OEM starts from and the palette arriving late cannot repaint it. See the setMiGlass guard.
     */
    static volatile float sAodGrey = Float.NaN;
    /**
     * The glass clock's dark/light switch (`TimeView.setBrightness`) as the OEM last decided it
     * from the palette of the cover, and as it last set it outside cover mode. See the
     * setBrightness hook: null until the OEM has said.
     */
    static volatile Boolean sBrightCover, sBrightOutside;
    /**
     * The strip of the cover that gets sampled for that reading, in dp from the top of the
     * screen. Generous on purpose: the date rests near the top and the collapsed clock
     * hangs under it, and how tall that whole block is depends on the clock style - 56dp clears
     * the status bar and 172dp is past the glyphs on every style measured so far, the point
     * being that a band that overshoots by a few rows still describes what the eye sees there.
     */
    static final float BAND_TOP_DP = 56f, BAND_BOT_DP = 172f;

    /**
     * The same strip, measured off the placement instead of written down, in screen pixels.
     *
     * The two constants above describe the all_in_one layout - the date near the top with the
     * collapsed clock hung under it - and every other style lays its date and its time out
     * somewhere else entirely. Measured on depth_pets: the date and clock sit at 560-640px while
     * the constants sample 168-516, so the reading came from the sky ABOVE the clock. The cover
     * was bright there and dark behind the glyphs, the module called it a dark cover, picked its
     * light glyphs, and drew a white clock on a white cover - with `cover luma 0.43` in the log
     * as the only trace, and every number in it self-consistent.
     *
     * Written by placeCollapsedClock() from where the date and the clock actually ended up, and
     * used whenever it has been written. The constants stay as the answer for the frames before
     * the first placement, which is also the only case they were ever right for.
     */
    static volatile float sBandTopPx = Float.NaN, sBandBotPx = Float.NaN;

    /** Air above the date and below the clock that the band includes. */
    static final float BAND_PAD_DP = 8f;

    /**
     * Where the date and the clock are, right now, on the screen.
     *
     * Called from the pre-draw and nowhere else. The placement is the wrong place for it and
     * was the first place it was written: measured on depth_pets, the placement reported the
     * block at 229-493 while the pre-draw of the very same frame has it at 514-750 - 285px
     * apart, which is the container translation the OEM applies after the placement returns.
     * The band came out over the sky above the clock, the cover read bright there and dark
     * behind the glyphs, and the clock was drawn white on white. Same rule as everything else
     * in this file: read after the transforms, never before.
     */
    static void updateColorBand() {
        if (!ClockCollapse.active()) return;
        int[] p = LOC_BAND;
        float top = Float.NaN, bot = Float.NaN;
        View date = ClockViews.sDateView;
        if (ClockViews.usableDate(date)) {
            date.getLocationOnScreen(p);
            top = p[1];
            bot = p[1] + date.getHeight();
        }
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            g.getLocationOnScreen(p);
            float gTop = p[1];
            float gBot = gTop + g.getHeight() * g.getScaleY();
            top = Float.isNaN(top) ? gTop : Math.min(top, gTop);
            bot = Float.isNaN(bot) ? gBot : Math.max(bot, gBot);
        }
        if (!Float.isNaN(top) && !Float.isNaN(bot) && bot - top >= 8f) {
            sBandTopPx = top;
            sBandBotPx = bot;
        }
    }

    /** ClockViewType constants by name, resolved once. See oemPart(). */
    static volatile java.util.Map<String, Object> sViewTypes;

    /** The full-bleed album cover we add behind the clock. */
    static volatile ImageView sCover;
    /** Survives keyguard rebuilds: re-attach whenever a fresh clock container shows up. */
    static volatile boolean sCoverWanted;
    /**
     * While true, the wallpaper's subject cut-out stays hidden. HyperOS composites it in
     * SystemUI (KeyguardDepthInteractor -> deducted_image_view) on top of the hour clock, so
     * swapping the wallpaper texture alone leaves the OLD wallpaper's subject floating over the
     * album cover - the leftover the cover swap does not otherwise explain.
     */
    static volatile boolean sDepthHidden;
    /**
     * The OEM's cut-out ImageView (deducted_image_view), and the visibility the OEM itself last
     * gave it. On a depth VIDEO wallpaper it holds the last frame's subject for the AOD, and the
     * OEM keeps it INVISIBLE on the awake lock screen, where the video's own cut-out TextureView
     * does the job. Handing it back as VISIBLE - what this used to do - left that last frame's
     * subject pinned over the playing video: a sheep's fleece in the corner and pine branches
     * that belonged to another frame. What goes back is what the OEM last asked for.
     */
    private static volatile View sDeductedRef;
    /** PROBE: the OEM object that owns the cut-out, for vcprobe's reading of its switches. */
    private static volatile Object sDepthInteractor;

    static volatile int sDeductedWanted = -1;
    static volatile boolean sDeductedHeld;
    private static volatile boolean sOwnDepthWrite;

    private static void setDeductedVisibility(View d, int v) {
        sOwnDepthWrite = true;
        try {
            d.setVisibility(v);
        } finally {
            sOwnDepthWrite = false;
        }
    }
    /**
     * Hiding the cut-out once is not ownership. The OEM re-shows it from more than one place -
     * updateDeductedImageView is only the one we caught first, and the Folme alpha animators on
     * the same view (setDepthTransitionAlpha / deductedTranslateAlphaFolmeAnimator) reach it
     * without passing through any method we hook. Rather than chase each path, assert the state
     * every frame the keyguard draws, the same way notifY and glassData are asserted: a
     * pre-draw listener on the view itself costs one visibility check per keyguard frame and is
     * blind to whichever route put it back.
     */
    private static ViewTreeObserver.OnPreDrawListener sDepthGuard;
    private static View sDepthGuarded;
    /** How many times the system took the cut-out back, so the log says so without shouting. */
    private static int sDepthTakebacks;
    /** SystemUI restarts on its own (observed, with no crash recorded), which used to drop the
     *  hidden flag and bring the old wallpaper's subject back over the album cover. Cover mode
     *  is a property of the phone, not of this process, so it has to outlive the process. */
    public static Context sAppCtx;

    /** SystemUI's own context, for the helpers that live outside this file. */
    public static Context appContext() {
        return sAppCtx;
    }

    /** The album cover is the wallpaper: depth cut-out hidden and the clock collapsed. */
    static volatile boolean sCoverMode;
    /**
     * How tall the collapsed clock's digits are, in dp.
     *
     * A HEIGHT, not the coefficient this used to be. A coefficient multiplies whatever the
     * style's glyphs happen to measure, and those differ by more than ten times over between
     * styles and by more again between screens - so the same number meant a different clock
     * everywhere except on the phone it was chosen on. A height is the same clock everywhere:
     * what the setting says is what the digits measure, and the scale k falls out of it.
     *
     * 36dp is 108px on this 480dpi screen - the OPPO clock the look was matched to.
     */
    static final float DEFAULT_CLOCK_HEIGHT_DP = 36f;
    /** The bottom of the range. Below this a digit is a smudge. */
    private static final float CLOCK_HEIGHT_MIN_DP = 20f;
    /**
     * A sanity ceiling on a stored height, and the app's slider top when it has no clock to
     * measure.
     *
     * Not the top of the travel. That is the style's own glyph height, because the collapse
     * cannot grow a clock - kForBox() caps k at 1 - so the app stops its slider there and this
     * only has to stay above any glyph on any screen. 256dp is 768px on this 480dpi device,
     * against 448px measured for the tallest clock here.
     */
    private static final float CLOCK_HEIGHT_MAX_DP = 256f;
    /** The smallest scale ever written to a view, so no style can collapse itself to nothing. */
    static final float MIN_CLOCK_K = 0.05f;
    /**
     * How solid the glass clock goes, in the OEM's own terms: updateGlassValue(0) is transparent
     * refracting glass and (1) is a solid fill.
     *
     * Fixed at 0 - the glass end, no slider, and loadState does not read one. The app's row for it
     * was labelled "玻璃强度" and shown inverted (1 - this), so 0 is what its right-hand end, 100%,
     * asked for. `op glassend` still moves it for the rest of the session, in these terms: --ef v 1
     * is the solid end.
     */
    static final float DEFAULT_GLASS_END = 0f;
    static volatile float sClockHeightDp = DEFAULT_CLOCK_HEIGHT_DP;
    /**
     * A clock size stored before the unit changed, waiting for a measured box to convert with.
     *
     * The old value was a coefficient and the new one is a dp height, and 0.335dp would be a
     * clock three pixels tall - so clamping alone cannot tell the two apart. A stored value
     * under CLOCK_HEIGHT_MIN_DP is one of the old ones, and the only thing that says what it
     * was worth is the box it used to multiply, so it waits for the first one measured.
     */
    static volatile float sClockLegacyK = Float.NaN;
    /**
     * The collapsed clock's size as a fraction of the style's own full-size clock - the one the
     * lock screen shows with cover mode off, before any notification squeezes it. 1 = unchanged.
     *
     * A setting again (user, 2026-09-30): the app's slider sends `op clocksize`, and the state
     * file keeps it. It was fixed at 0.09 for two days with the settings cut, and 0.09 is still
     * the default - the share the 36dp digits come to on the style they were chosen on. NaN is
     * what the op asks for to hand the size back to sClockHeightDp, and is not written down.
     *
     * One thing this number does that NaN did not: it wakes ClockCollapse.targetY, which walks
     * the OEM's own squeeze so the variable font is DRAWN at the height asked for instead of
     * being drawn at the floor and scaled up - the two agree in height and not in the width the
     * font takes at that height. It only moves while the asked-for height is meaningfully above
     * the OEM's floor, and 36dp came to a share of the full clock that is close to it, so this
     * should be the same clock. If the digits ever read wider than before, this is the line.
     */
    static final float DEFAULT_CLOCK_SIZE = 0.09f;
    static volatile float sClockSize = DEFAULT_CLOCK_SIZE;
    /** The smallest size the clock can be set to. */
    static final float CLOCK_SIZE_MIN = 0.05f;
    /**
     * How far the date and the clock are moved together, in dp, from where cover mode puts
     * them. Positive is down.
     *
     * Fixed at 0: no slider, and loadState does not read one. `op clockoffset` still moves it
     * for the rest of the session.
     */
    static volatile float sClockOffsetDp = 0f;
    static final float CLOCK_OFFSET_MIN_DP = -60f;
    static final float CLOCK_OFFSET_MAX_DP = 300f;
    static volatile float sGlassEnd = DEFAULT_GLASS_END;
    /** The session we are mirroring, plus the callback that keeps the cover on the right track. */
    static MediaController sWatched;

    static MediaController watched() {
        return sWatched;
    }
    private static MediaController.Callback sMediaCb;

    /**
     * Where the sharp cover sits vertically inside the wallpaper, 0 = flush with the top,
     * 0.5 = centred. Centred puts the most interesting part of the artwork exactly behind the
     * media card, which is why Apple biases it upwards.
     */
    static final float DEFAULT_BIAS = 0.34f;
    static volatile float sBias = DEFAULT_BIAS;
    static volatile CoverCardStyle sCoverCardStyle = CoverCardStyle.defaults();
    static volatile boolean sCoverCardPlaying;

    /**
     * Cover mode follows the media card. Not a setting: with it off the module does nothing at
     * all, which is not a state worth offering - uninstalling is the way to turn the module off.
     * The adb "auto" op can still flip it for a debugging session; it comes back on at startup.
     */
    static volatile boolean sAuto = false;
    /**
     * 启用音乐锁屏封面, the app's master switch for the cover. Unlike sAuto it is a setting, and
     * it shuts every way in rather than only the automatic one: off, the card going up does not
     * open the cover and neither does a tap on the artwork or on the music island - the island
     * opens the plain card instead. The rest of the module (islands, shade, card tweaks) goes on.
     * On when absent from the state file: every phone had the cover before the switch existed.
     */
    static volatile boolean sCoverFeatureOn = true;
    /** What the pushed wallpaper currently shows, so a metadata storm pushes it only once. */
    static volatile String sTrackKey = "";
    private static MediaSessionManager sMsm;
    private static MediaSessionManager.OnActiveSessionsChangedListener sSessionsCb;

    /**
     * Composing the wallpaper is a full-screen bitmap plus a multi-pass blur. That was fine as a
     * one-off adb command; on every track change it has to be off the main thread or it drops
     * frames in SystemUI.
     */
    private static Handler sWork;
    private static Handler sMain;

    /** The screen the wallpaper is composed for. Read off the keyguard, not assumed. */
    static volatile int sScreenW = 1200, sScreenH = 2608;

    /**
     * Scratch pairs for getLocationOnScreen, one per call site: both sit on per-frame paths, where
     * the array was the whole of what the call allocated. One per SITE rather than one shared, so
     * a caller that ever nests inside another cannot clobber it.
     */
    private static final int[] LOC_BAND = new int[2];
    static final int[] LOC_CARD = new int[2];

    /**
     * The media card, restyled. This is the first thing the module changes about the card
     * itself, and it follows the same ownership rule as the cut-out: the card is rebound on
     * every track change and re-laid out constantly, so the look is asserted every frame from a
     * guard rather than set once.
     *
     * Both are ON now, and neither is a setting: the app has no switches for them and loadState
     * does not read any. Off was the cautious default while they were new; on is the look this
     * ships with - the artwork is already the wallpaper, so the thumbnail is showing it a second
     * time. Both apply in cover mode only. `op mediacard` still moves them for the rest of the
     * session.
     */
    static volatile boolean sMcHideArt = true;
    /**
     * Whether the hidden thumbnail comes back while the lock screen lyrics are up.
     *
     * Only means anything with sMcHideArt on, which is what the app's layout says: it is the
     * exception to that setting, not a setting of its own. Also fixed on, and for the same
     * reason: the lyrics are up for most of a track, and a card that has lost its thumbnail by
     * then has nothing to come back for.
     */
    static volatile boolean sMcArtInLyrics = true;
    /**
     * Whether tapping the card's title line toggles playback.
     *
     * It exists because of where the real button is: on this card it sits over the fingerprint
     * sensor, so a thumb aiming at pause is as likely to unlock the phone instead. The title is
     * the one part of the card that is both large and nowhere near the sensor.
     *
     * Applied on the lock screen only, like the two switches above it, and for a sharper reason
     * than consistency: the shade shows the SAME card view, and in the shade the real button
     * works and the title is the OEM's. Held the same way too - asserted from the card guard
     * rather than set once, because the guard is already what tells us the card is still ours.
     */
    static volatile boolean sMcTitleTap;
    /**
     * A button on the card that turns the cover page into the lyric page and back - the same
     * switch as the two-finger tap, for anyone who would rather see it than know it. It covers
     * the player's own button to the right of "next", which is why it is off by default. Only
     * drawn in cover mode, on the lock screen, for a track that has lyrics: anywhere else there is
     * only one page and nothing for it to do. See CardPageButton.
     */
    static volatile boolean sMcPageBtn;
    /**
     * Keep cover mode's small clock in the always-on display instead of handing it back to the
     * OEM's, which is what it does by default.
     *
     * About the FULL-SCREEN AOD only - the one that shows the whole lock screen, dimmed. That is
     * the mode the user asked for, and the one where the OEM itself is trying to show the lock
     * screen's clock (see ClockCollapse.sAodHeld). The plain linkage AOD and the classic plugin
     * AOD are left exactly as they were.
     *
     * One setting for both views of the lock screen: the lyrics are a layer over cover mode
     * rather than a mode of their own, so "cover" and "lyrics" have no separate AOD to disagree
     * about.
     *
     * On, and not a setting: the app has no switch for it and loadState does not read one. The
     * grow-back was tried and read as the cover being taken away at the moment the screen went
     * off. `op aodclock` still moves it for the rest of the session.
     */
    static volatile boolean sAodSmall = true;
    /**
     * Whether the wallpaper process is sizing the keyguard texture to the SCREEN rather than to
     * the wallpaper file, which is its default and is the same switch as WallpaperProbe.sTexFit
     * on the other side. Kept here for one reason: while that is on, the re-fit below - which
     * rewrites the user's lockscreen wallpaper to the screen's size - must not run. Rewriting it
     * is what costs the user their depth cut-out, and it is no longer needed to make the
     * crossfade affordable, because a screen-sized texture is what made it affordable.
     */
    static volatile boolean sTexFit = true;
    static final NotificationSink sNotificationSink = new NotificationSink();
    private static final Runnable REFRESH_NOTIFICATION_SINK = () -> {
        try {
            int mode = Fingerprint.sFpAvoid;
            boolean refreshed = sNotificationSink.refresh(mode);
            Xp.log(TAG + "notification bound mode=" + mode
                    + (refreshed ? " policy flow updated" : " waiting for system collection"));
        } catch (Throwable t) {
            Xp.log(TAG + "notification bound refresh failed: " + t);
        }
    };

    static void refreshNotificationSink() {
        main().removeCallbacks(REFRESH_NOTIFICATION_SINK);
        main().post(REFRESH_NOTIFICATION_SINK);
    }

    /** The title carrying our play/pause listener, and what it was clickable-wise before. */
    static View sCardTitleTapped;
    /** Set only around a capture. See shootCard(). */
    static volatile boolean sCardForced;
    /**
     * The artwork's scale as the OEM has it, and the last one we wrote over it.
     *
     * Sampling one frame of this and writing it back mirrored people's thumbnails. `album_art`
     * carries a scaleX the OEM owns and animates - -1 has been read off it and so has 1 - so
     * no single frame tells you which is its resting value, and the frame we happened to catch
     * when the card was resolved became the value handed back on the way out. When that frame
     * was not the resting one the thumbnail stayed flipped left-for-right: reported as "expand
     * to full screen, tap back to the card, and the thumbnail is reversed".
     *
     * Keeping both numbers is what removes the guess. See scaleArt().
     */
    static float sArtBaseX = 1f, sArtBaseY = 1f;
    static float sArtWroteX = Float.NaN, sArtWroteY = Float.NaN;

    /**
     * A single tap on the cover puts the wallpaper back, the way tapping Apple's full-bleed
     * artwork does. Tapping again brings the cover back.
     */
    static volatile boolean sTapToggle = true;
    /**
     * Whether the wallpaper crossfades between the album cover and the lock wallpaper instead of
     * swapping in one frame. The fade itself runs in the wallpaper process, which is the only
     * place that holds both pictures; all this decides is whether to ask for it.
     */
    static volatile boolean sFadeWp = true;
    /**
     * Whether the COVER VIDEO ITSELF crossfades into the cover, in the wallpaper window.
     *
     * Off, and it is off because of what it cost when it was on: measured on the device, the
     * cards' blurred background stopped switching to the cover at all - the window never showed
     * the new frame. A cover video with a fade in it is a file of a dozen frames, a frame of the
     * user's own wallpaper has to be decoded to build it, and both ends of it are converted to
     * YUV; any of those going wrong leaves the reload at the end of that chain never happening,
     * while the SystemUI view - which is put up by the other process entirely - goes on showing
     * the cover. A one-frame cover, which is what this was before the fade existed, cannot fail
     * that way.
     *
     * The lag it was meant to hide is handled where it actually lives: see sFadeMode. Kept as a
     * switch rather than deleted because the muxer now handles any frame count (checked off the
     * device: two samples in, byte-identical file out) and this is one adb line to try again.
     */
    static volatile boolean sVideoFade;
    /**
     * What the cover's own fade does about the wallpaper window's head start, which is the one
     * thing about a live cover that cannot be fixed by making either half faster.
     *
     * The two halves are drawn by different processes and they are not equally fast. This view
     * gets the composed bitmap immediately; the window under it only changes once the cover
     * video has been ENCODED, the player rebuilt and its first frame rendered. So a fade that
     * starts at the push is over before the window has moved at all - which is the whole of the
     * report that the background and the cards' blurred background arrive at visibly different
     * moments.
     *
     *   FADE_MODE_OFF     - the fade the module always had: our view dissolves in from the
     *                       push, over the wallpaper's own crossfade length, whatever the window
     *                       is doing. The eye's background and the cards' blurred background then
     *                       change at visibly different moments, which is the original report.
     *   FADE_MODE_HOLD    - waits for WallpaperProbe's signal - sent when the new source's
     *                       FIRST FRAME is on the window - and only then puts the view up.
     *                       No dissolve: the view appears over identical content, so the eye's
     *                       background and the cards' blurred background switch in the same
     *                       frames. The default; the entry dissolve is gone by request - any
     *                       fade that runs before the first frame shows the blur stale.
     *   FADE_MODE_STRETCH - dissolves from the view's first frame, over the last MEASURED gap
     *                       between the push and the first-frame signal. Kept for A/B.
     *
     * On the way OUT the same three apply, but holding is what makes sense there whatever the
     * mode: the view has to still be up when the window swaps back, or its cut shows.
     *
     * The measured gap is persisted, so a restarted SystemUI does not have to relearn it - and
     * with no measurement at all the stretch falls back to the crossfade length, which is mode
     * OFF for that one transition.
     */
    static final int FADE_MODE_OFF = 0, FADE_MODE_HOLD = 1, FADE_MODE_STRETCH = 2;
    static volatile int sFadeMode = FADE_MODE_HOLD;
    /**
     * Set when the user tapped their way OUT of cover mode while the card was still up.
     *
     * Without it the very next metadata event puts the cover straight back: the card is the
     * switch, and the card has not gone anywhere. Cleared when the card actually goes away, so
     * the next thing the user plays starts from the cover again rather than inheriting a
     * decision that was made about a different song.
     */
    static volatile boolean sTapSuppressed;
    /** Set for the length of one gesture that started on the card's artwork and is ours. */
    static boolean sArtSwallow;
    private static long sArtDownAt;
    /** Longer than a tap is a press, a drag or a scroll, and none of those mean expand. */
    static final long ART_TAP_MS = 500L;
    /** Px of forgiveness around the thumbnail. The card slides; fingers are not pixels. */
    static final int ART_TAP_SLOP = 24;

    /**
     * Whether the gesture in flight began on the control centre.
     *
     * Recorded at ACTION_DOWN because every later test is too late: the centre is dismissed
     * during the gesture it is being dismissed by, so anything asked after that reads "no
     * centre" and hands the touch to the cover.
     */
    static volatile boolean sGestureOnCentre;

    /**
     * The curve the cover-mode transition runs on, and it is the OEM's own: the preset the system
     * itself drives this transition with (STATE_CHANGED), read off AllInOneClockAnimation at
     * runtime as {dampingRatio, response}. miuix derives stiffness = (2*PI/response)^2 and
     * damping = 2*zeta*(2*PI/response) - confirmed against the dumped parameters[]; here that is
     * k=273.4, c=29.1.
     *
     * It replaced EASE_RUNNING (0.18) in both directions, and that is the whole of the change:
     * the same spring family, the same driver, same velocity carry-over across a retarget, one
     * pair of numbers. 0.18 covers its distance about twice as fast at every point - 50% of the
     * travel at 48ms against 95ms here, 95% at 136ms against 235 - which on a 120Hz screen puts
     * the peak of the motion at ~73px in a single frame, at t~29ms. That is also the moment the
     * OEM is recomputing the clock's variable font at its largest, so any dropped frame there
     * doubled a step that was already the fastest thing on the screen. Measured with the jerk
     * probe: 30/49/75px on the old curve, 25-41px on this one.
     *
     * A recording of the transition was also measured frame by frame (clock band, pixel change
     * summed into a speed profile, integrated into progress), and it agrees on the shape but not
     * on the number: a least-squares fit puts the response at 0.45-0.55s, consistently slower
     * than the preset. The preset is believed over the fit, because the two are different kinds
     * of evidence - a 0.38 that was read out of the OEM's own animation object, against a number
     * inferred from pixels that cannot separate the clock's growth from the cover shrinking and
     * the wallpaper swapping in the same frames, with a noise floor that clips exactly the slow
     * first frames a spring is slowest in. Both errors push the estimate long. The preset also
     * reproduces an independent measurement already in this file: the exit spring "settles in
     * 610-636ms" was read off the device, and 0.38 lands at 665ms.
     */
    static final float[] EASE_COVER         = {0.88f, 0.38f};

    /**
     * The slider's range for the response above, in seconds. Zeta is not on it: the whole of the
     * change this replaced was the one number, and the curve's shape is the OEM's.
     *
     * 0.18 is the bottom because it is the fastest this transition has ever shipped - it is what
     * EASE_RUNNING was - and the jerk probe measured 30/49/75px of clock movement in a single
     * frame on it, against 25-41 here. Faster than that is asking for dropped frames on the one
     * frame the OEM is also recomputing the variable font.
     */
    private static final float CLOCK_RESPONSE_MIN = 0.18f, CLOCK_RESPONSE_MAX = 0.60f;
    /** The wallpaper crossfade that belongs to EASE_COVER[1]. See fadeMsFor(). */
    private static final long EASE_COVER_FADE_MS = 370L;
    /**
     * What the transition runs on, and the wallpaper crossfade with it (see fadeMsFor).
     *
     * Fixed at the OEM preset for this transition: no slider, and loadState does not read one.
     * `op clockspring` still moves it for the rest of the session.
     */
    public static volatile float sClockResponse = EASE_COVER[1];

    /**
     * The fade that belongs to a spring response: the card's stand-in animator and the
     * wallpaper's crossfade, which have to reach the end with the clock.
     *
     * Proportional, and that is the rule rather than a convenience. The wallpaper's fade is a
     * cubic ease-out, which covers 95% of its length at 0.632*T, and the 370ms that ships with
     * 0.38 was solved from that against the spring's own 95% at 235ms - see the comment on
     * WallpaperProbe.sFadeMs. With zeta fixed, a linear spring's step response is a function of
     * w0*t alone, so its 95% time is exactly proportional to the response, and so is the T that
     * matches it. 0.18 gives 175ms, 0.60 gives 584.
     */
    public static long fadeMsFor(float response) {
        return Math.round(EASE_COVER_FADE_MS * response / EASE_COVER[1]);
    }

    /**
     * The lyrics' page, told that the keyguard's state may have changed: the cached reading is
     * dropped and asked for again, and the view is woken, so the page can go on the frame the
     * lock screen does rather than on its next tick (up to 500ms later, and 150ms of that is the
     * cache in front of it). Posted by the lock screen's exit hook - see onPackageLoaded.
     */
    private static final Runnable kickLyrics = new Runnable() {
        @Override
        public void run() {
            forgetSysReads();
            LockLyrics.refresh();
        }
    };

    /**
     * Arms one method as "the lock screen is going away", tagged so the lyrics probe can name the
     * one that fired. Its own try, like every hook here: a build without the method must not cost
     * the others. `cls` is a class name looked up now, `owner` a class already in hand.
     */
    private static void armLeave(ClassLoader cl, String cls, String method, String tag, String id) {
        try {
            armLeave(Xp.findClass(cls, cl), method, tag, id);
        } catch (Throwable t) {
            LockLyrics.armed("!" + tag);
            Xp.log(TAG + "leaving hook " + tag + " has no class: " + t);
        }
    }

    private static void armLeave(Class<?> owner, String method, String tag, String id) {
        try {
            Xp.hookAll(id, owner, method, chain -> {
                LockLyrics.lockScreenLeaving(tag);
                main().post(ImmersiveHost::onLockScreenLeaving);
                // At once rather than on the window's next pre-draw (coverCardVisible says why).
                main().post(CoverCardLayer::hideNow);
                CoverMorphLayer.cancel();
                main().post(kickLyrics);
                main().postDelayed(kickLyrics, 160L);
                return chain.proceed();
            });
            LockLyrics.armed("+" + tag);
        } catch (Throwable t) {
            LockLyrics.armed("!" + tag);
            Xp.w(TAG + "leaving hook " + tag + " failed: " + t);
        }
    }

    /** Host lifecycle/package dispatch remains in HyperSystemUiModule. */
    public static void install(io.github.libxposed.api.XposedInterface api, ClassLoader cl) {
        Xp.attach(api);
        sCoverFeatureOn = false;
        LockLyrics.sEnabled = false;
        installSystemUi(cl);
    }

    /** Same-process settings bridge: apply before attach continues, without broadcast races. */
    public static boolean applyHostSetting(Context context, Intent intent) {
        try {
            return new ProbeReceiver().appSetting(context, intent, intent.getStringExtra("op"));
        } catch (Throwable error) {
            Xp.w(TAG + "host setting failed: " + error);
            return false;
        }
    }

    /** Shared clock/card yield without closing an unrelated local cover or lyric window. */
    public static void releaseHostLayout() {
        CoverMorphLayer.cancel();
        if (sCoverHeld) {
            sCoverHeld = false;
            LockHold.give(LockHold.Owner.COVER, false);
        }
        MediaCard.releaseHostLayout();
    }

    public static void updateHostFeatures(boolean music, boolean lyrics, boolean layout) {
        if (sAppCtx == null) return;
        if (sCoverMode && ((!music && !lyrics) || (!music && !keepsWallpaper()))) {
            CoverMorphLayer.cancel();
            setCoverEnabled(false, false);
        }
        if (!lyrics && LockLyrics.sEnabled) LockLyrics.setEnabled(false, sTrackKey, sWatched);
        if (!layout) {
            if (sCoverHeld) {
                sCoverHeld = false;
                LockHold.give(LockHold.Owner.COVER, false);
            }
            MediaCard.releaseCardGuard();
        }
    }

    public static void resumeHostScene() {
        setCoverEnabled(true, false, false);
    }

    public static void installWallpaper(io.github.libxposed.api.XposedInterface api, ClassLoader cl) {
        Xp.attach(api);
        WallpaperProbe.install(cl);
    }

    public static void installApple(io.github.libxposed.api.XposedInterface api, ClassLoader cl) {
        Xp.attach(api);
        AppleLyrics.handle(cl);
    }

    public static void installAmap(io.github.libxposed.api.XposedInterface api, ClassLoader cl) {
        Xp.attach(api);
        AmapImmerse.handle(cl);
    }

    /**
     * SystemUI's cold start, and a hot-reloaded generation's: everything this module installs
     * in that process, given the host's loader. onPackageLoaded only dispatches.
     */
    static void installSystemUi(ClassLoader cl) {
        Xp.log(TAG + "loaded into SystemUI");

        // Before the clock container lookup below, which returns early when it fails: none of
        // these have anything to do with the clock, and a build that renamed the container must
        // not cost them too.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // The home screen magnified behind the shade, after the lock screen (PassBlurScaleFix).
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // The mini player hangs off the shortcut row, not the clock container.
        MiniPlayerRuntime.install(cl);
        // The clock's colour extraction once it is still, not on every step (PaletteThrottle).
        PaletteThrottle.INSTANCE.install(cl);
        // 勿扰 and charging drawn after the date, out from under the pill (DateStatus).
        DateStatus.INSTANCE.install(cl);
        // 高德's ride card is a focus notification of its own only once the cloud list that
        // decides who may have one answers for it (AmapFocus).
        // Standalone transit focus/rear feature is excluded from the host integration.
        // A ride code opened or a card's fare taken, for the trip card's 到站 (RideCodeExit).
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // A 微信 order page's pickup code, as an island (PickupCodeIsland).
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // "Has notifications" as the clock faces are told it, less the rows nobody can see
        // (NotifPresence).
        NotifPresence.install(cl);
        // The lock screen's per-region palettes read again when the cover changes (CoverPalette).
        CoverPalette.install(cl);

        try {
            sContainerCls = Xp.findClass(CLS_CONTAINER, cl);
        } catch (Throwable t) {
            Xp.log(TAG + "KeyguardClockContainer not found, nothing to hook: " + t);
            // Everything installed above is still here; it has to outlive a dry run too.
            Gen.markInstallScope();
            return;
        }
        // Not fatal, and it used to be. A HyperOS 3 report had no class under the name the
        // enum has here, the lookup threw, and the whole of SystemUI went unhooked - no cover,
        // no media card, no depth - over one enum only the clock squeeze needs. Every hook
        // below now installs on its own terms, and each failure says which feature it cost.
        sNotifStateChange = findNotifStateChange(sContainerCls);
        if (sNotifStateChange != null) {
            sTypeCls = sNotifStateChange.getParameterTypes()[2];
        } else {
            Xp.log(TAG + "no notifStateChange(float, boolean, enum) on " + CLS_CONTAINER
                    + " - the clock squeeze cannot be driven on this build");
        }

        hookContainerAttach();
        hookContainerDetach();

        // The lyrics' HDR highlight no longer has a hook here: it needs a colour mode, a colour
        // mode is a whole window's, and the lyrics have a window of their own now (LyricWindow).
        // It used to be written into the shade window's mLpChanged from inside the OEM's own
        // apply, which is where the media card got dragged into HDR with it.

        hookSleepAndWake(cl);
        hookTransportControls(cl);
        hookAodHandover();
        hookDepthOwnership(cl);
        hookCutOutVisibility();
        hookAodShrink(cl);
        hookWallpaperBlack(cl);
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        hookKeyguardWallpaperManager(cl);
        MediaCard.hookMediaCard(cl);
        LockTaps.hookLockScreenTap(cl);
        ChargeAnim.hookChargingAnimation(cl);

        // The notification shade's cover background. Its hooks and logic are ShadeLayer's own.
        // HyperChanger retains the existing owner / excludes this standalone feature.

        hookNotifStateChange();
        hookTimeView(cl);

        // The date line above the clock is deliberately left to the OEM. On the glass styles it
        // is blur-blend rendered: setDateTextColor keeps only the green and blue of what it is
        // given and forces red to 0xff, and those channels weight the blend layers against the
        // backdrop rather than name a colour. Swapping in the cover's hue moved those weights,
        // so the date went dark on a light cover and light on a dark one - and our recolour,
        // which fed getCurrentTextColor() back in, left it that way after cover mode ended.

        hookClockInteractor(cl);

        // Everything above was installed once, before the keyguard was ever attached. A dry run
        // takes back the attach path's registrations only, because it puts that path back up
        // without running this method again; see Gen.markInstallScope.
        Gen.markInstallScope();
    }

    private static void hookContainerAttach() {
        try {
            Xp.hookAll("sysui.clock.container_attach", sContainerCls, "onAttachedToWindow", chain -> {
                Object result = chain.proceed();
                onContainerAttached((View) chain.getThisObject());
                return result;
            });
        } catch (Throwable t) {
            // Everything downstream hangs off this one: it is where the container is captured
            // and where the broadcast receiver is registered, so without it there is not even
            // an adb channel to ask what went wrong. Say so plainly rather than throwing out
            // of onPackageLoaded, which loses the message.
            Xp.log(TAG + "onAttachedToWindow hook FAILED, the module is inert in SystemUI: " + t);
        }
    }

    /**
     * The keyguard clock container has just been attached. Also the hot-reload entry: a new
     * generation runs this against the container it inherited from the old one.
     */
    static void onContainerAttached(View container) {
        if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClock(container)) return;
        CoverMorphLayer.cancel();
        sContainer = container;
        captureScreenSize(sContainer);
        // The clock container attach is the first reliably-fired event after both
        // modules' onPackageLoaded, so arming the HyperLight counter-hook here - not
        // from setGlassColor, which this build never calls on the all_in_one clock -
        // guarantees it registers after HyperLight's own View.setMiGlass hook.
        ClockGlass.armMiGlassGuard();
        Xp.log(TAG + "clock container attached: " + sContainer);
        try {
            boot(sContainer.getContext().getApplicationContext());
        } catch (Throwable t) {
            Xp.w(TAG + "boot failed: " + t);
        }
        // An immersive page's slot, if a rebuilt keyguard came with a new window.
        try {
            ImmersiveHost.onContainerAttached();
        } catch (Throwable t) {
            Xp.log(TAG + "immersive slot not restored: " + t);
        }
        // The lyric bridge, from the same place and for the same reason: this is where
        // the process first has a Context. It does nothing at all when no bridge is
        // installed, and a second call is a no-op, so it rides along with the keyguard
        // being rebuilt rather than needing a moment of its own.
        try {
            LyriconSource.attach(sContainer.getContext().getApplicationContext());
        } catch (Throwable t) {
            Xp.w(TAG + "Lyricon attach failed: " + t);
        }
        // The keyguard is rebuilt on some transitions, taking our cover with it, so
        // re-attach rather than assume the view is still in the tree.
        if (sCoverWanted) {
            sCover = null;
            attachCover();
        }
        // The video path's cover is a view, so a keyguard rebuild takes it - and the
        // wallpaper it was covering is hidden, so losing it without putting it back
        // leaves the lock screen blank. Re-compose rather than re-show the old view:
        // the track may have moved on while the screen was off.
        // ... and the shade's background has to be rebuilt for the same reason even on
        // the image path, where nothing else would notice it had gone: it is static, so
        // a restart takes it and only this puts it back.
        if (sCoverMode && (sVideoWallpaper || !LegacyShadeBridge.hasArt())) {
            if (sVideoWallpaper) sCover = null;
            CoverPush.pushArtAsync(true, false);
        }
        if (sCoverMode && sCoverCardStyle.mode == CoverCardStyle.CARD) {
            CoverCardLayer.attach(CoverPush.coverLayer());
            CoverCardLayer.style(sCoverCardStyle);
            CoverCardLayer.playback(sCoverCardPlaying);
        }
        // Re-apply on keyguard rebuild. Measured: the system never re-shows the
        // cut-out on its own, so no per-call ownership hook is warranted - hooking
        // View.setVisibility process-wide cost every visibility change in SystemUI and
        // fired zero times. What actually lost the state was SystemUI restarting.
        if (sDepthHidden) setDepthHidden(true);
        else if (sVideoWpOwed) setDepthHidden(false);
        // Same story for the card: the guard and the artwork's tap listener both live on
        // the view, so a rebuild takes them.
        if (sCoverMode || wantsArtTap()) MediaCard.applyMediaCard();
        // A rebuilt keyguard can be a different clock style, so the nudge measured
        // against the old one means nothing - and neither does the clock view we resolved.
        ClockViews.sClockTargets.clear();
        forgetClockRoots();
        ClockViews.forgetGlyphBox();
        ClockCollapse.onAttached();
        // A rebuilt keyguard has a new foreground layer, and a restored cover mode came
        // on before there was any keyguard to put the lyrics in. Posted: adding a view
        // from inside the tree's own attach dispatch is not something to rely on.
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    LockLyrics.refresh();
                } catch (Throwable t) {
                    Xp.w(TAG + "lyrics refresh failed: " + t);
                }
            }
        });
    }

    private static void hookContainerDetach() {
        // The keyguard is rebuilt on every screen-off/on, so a captured instance goes
        // stale. Drop it on detach and always drive the currently attached one.
        try {
            Xp.hookAll("sysui.clock.container_detach", sContainerCls, "onDetachedFromWindow", chain -> {
                Object result = chain.proceed();
                if (sContainer == chain.getThisObject()) {
                    CoverMorphLayer.cancel();
                    ClockCollapse.onDetached();
                    sContainer = null;
                    Xp.log(TAG + "clock container detached");
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "onDetachedFromWindow hook failed: " + t);
        }
    }

    private static void hookSleepAndWake(final ClassLoader cl) {
        // The keyguard service hears about sleep and wake before anything is laid out for either.
        //
        // Sleep: the start of the fade that leaves only the clock. The small clock is sprung to
        // the OEM's own (and then the AOD's) clock across it - see ClockCollapse.toAod().
        //
        // Wake: flagged on the binder thread itself, because what matters is the pre-draw of the
        // very first lock screen frame. By the time a post reaches the main thread the OEM may
        // already have drawn one frame of its full-size clock - which is the flash this prevents.
        try {
            Class<?> ks = Xp.findClass("com.android.systemui.keyguard.KeyguardService$2", cl);
            Xp.hookAll("sysui.screen.sleep", ks, "onStartedGoingToSleep", chain -> {
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        CoverMorphLayer.cancel();
                        if (clockHeld()) ClockCollapse.toAod();
                    }
                });
                return result;
            });
            // The lock screen leaving, at the moment it starts to leave.
            //
            // With the HDR highlight on the lyrics are drawn in a window of their own, so the lock
            // screen going does not take them with it: their page is gated on the keyguard instead
            // (LockLyrics.wantsWindow), and the cached answer to that is up to 150ms old - which
            // was the whole of the residue left over the desktop on an unlock (user, 2026-09-29).
            // Ask again now, and once more a beat later in case the state flips behind this call.
            // Each has its own try: a build without the method must not cost the hooks below.
            //
            // Only the two that are unambiguously "the lock screen is going away"; the probe
            // (`lyricstate`'s `hooks=`, and the tag on every trace line) says which armed and
            // which fired. The others were armed for a round and taken back out: `setOccluded` is
            // called with true when the AOD occludes the keyguard and with false when the lock
            // screen comes back, so both ends of a doze read as a dismissal and the lyrics vanished
            // on every screen-off (user, 2026-09-29), and `hideLocked`/`keyguardDone` are not the
            // unlock on this build.
            armLeave(ks, "startKeyguardExitAnimation", "exit", "sysui.lyrics.leave.exit");
            armLeave(cl, "com.android.keyguard.injector.KeyguardViewMediatorInjector",
                    "startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation", "inj",
                    "sysui.lyrics.leave.inj");
            Xp.hookAll("sysui.screen.wake", ks, "onStartedWakingUp", chain -> {
                ClockCollapse.noteWaking();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        MotionTrace.start("wake");
                    }
                });
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        if (clockHeld() && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                                && keyguardShowing()) {
                            ClockCollapse.enter(true, true, "kg-post");
                        }
                    }
                });
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "KeyguardService sleep/wake hooks failed, the clock cuts to and from "
                    + "the AOD: " + t);
        }
    }

    private static void hookTransportControls(final ClassLoader cl) {
        // The press itself, ~0.8s before the player admits anything changed.
        //
        // Measured on this phone: from the key landing to the module being told a new track is
        // playing is 713-972ms, all of it inside the player. Everything the cover does after that
        // costs ~110ms. So the only way to make a track change feel immediate is to hear the
        // press rather than the consequence - and this is where the press is, in this process,
        // on the way out to the player.
        //
        // Hooked here rather than on the card's buttons on purpose. `action0..action4` are laid
        // out in whatever order the player's custom actions arrive in (Apple Music puts prev at
        // action1 and next at action3, with 喜爱 and 随机播放 either side), the ids are AOSP's
        // generic ones, and the content descriptions are localised. TransportControls is public
        // API, cannot be renamed, and says what was MEANT - and it catches every route to it, not
        // just the lock screen's own buttons.
        try {
            Class<?> tc = Xp.findClass("android.media.session.MediaController$TransportControls",
                    cl);
            Xp.hookAll("sysui.transport.next", tc, "skipToNext", chain -> {
                CoverPush.noteSkip(1);
                return chain.proceed();
            });
            Xp.hookAll("sysui.transport.previous", tc, "skipToPrevious", chain -> {
                CoverPush.noteSkip(-1);
                return chain.proceed();
            });
            Xp.log(TAG + "transport controls hooked");
        } catch (Throwable t) {
            // Independently, like every other hook here: without it a track change simply waits
            // for the player, which is what it did before.
            Xp.w(TAG + "transport control hook failed, a skip is only noticed when the player "
                    + "reports it: " + t);
        }
    }

    private static void hookAodHandover() {
        // The OEM's own hand-over between the lock screen and the AOD, both ways.
        //
        // Into the AOD it lands after the screen fade has gone to black and before the AOD is
        // visible (measured 33.43 fade end -> 33.62 here -> 33.66 AOD shown -> 33.74
        // ACTION_SCREEN_OFF), so the AOD gets the full clock with nothing seen. It has to run
        // BEFORE the call: the call reads the clock's translation and posts it to the AOD plugin.
        //
        // Out of it, it is the first thing of the wake (onStartedWakingUp +46ms) and comes before
        // the OEM starts growing its glyphs from the AOD size - so the collapse starts from the
        // clock the AOD was showing and absorbs the OEM's growth, instead of racing it.
        try {
            Xp.hookAll("sysui.aod.transition", sContainerCls, "doAnimationToAod", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean toAod = args.length > 0 && Boolean.TRUE.equals(args[0]);
                if (clockHeld() && toAod) {
                    // Ahead of the broadcast, which is ~110ms behind: the display is no longer
                    // interactive, and any colour set in between must not get the cover's tint.
                    sScreenOn = false;
                    CoverCardLayer.refresh();
                    sAodGrey = Float.NaN;
                    ClockCollapse.toAod();
                    AodColour.recolorClock();
                }
                Object result = chain.proceed();
                if (clockHeld() && !toAod && ClockCollapse.leavingOrOff()) {
                    forgetSysReads();
                    // Only a wake onto the lock screen. The same call can come with the phone
                    // unlocked, and the clock there is the shade's.
                    if (keyguardShowing()) {
                        sScreenOn = true;
                        CoverCardLayer.refresh();
                        sAodGrey = Float.NaN;
                        ClockGlass.applyGlassBrightness();
                        ClockCollapse.enter(true, true, "doAnim");
                        AodColour.recolorClock();
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "doAnimationToAod hook failed, AOD falls back to screen off: " + t);
        }
    }

    private static void hookDepthOwnership(final ClassLoader cl) {
        // The system does re-show the cut-out - verified: it came back right after we restored
        // cover mode across a SystemUI restart. Re-hide after the OEM's own update instead of
        // hooking View.setVisibility process-wide, which would tax every view in SystemUI.
        try {
            Class<?> depth = Xp.findClass(
                    "com.android.keyguard.depth.KeyguardDepthInteractor", cl);
            Xp.hookAll("sysui.depth.deducted_image", depth, "updateDeductedImageView", chain -> {
                sDepthInteractor = chain.getThisObject();
                Object result = chain.proceed();
                if (sDepthHidden) {
                    try {
                        View d = (View) Xp.getObjectField(chain.getThisObject(),
                                "deductedImageView");
                        if (d != null && d.getVisibility() == View.VISIBLE) {
                            d.setVisibility(View.INVISIBLE);
                            Xp.log(TAG + "depth re-hidden after updateDeductedImageView");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            });
            Xp.log(TAG + "depth ownership hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "depth ownership hook failed: " + t);
        }
    }

    private static void hookCutOutVisibility() {
        // What the OEM itself wants of its cut-out view while we hold it hidden. See sDeductedRef.
        // ImageView declares its own setVisibility, so this sees ImageViews only, and it acts on
        // exactly one of them.
        try {
            Xp.hookAll("sysui.depth.image_visibility", android.widget.ImageView.class, "setVisibility", chain -> {
                if (!sOwnDepthWrite && chain.getThisObject() == sDeductedRef) {
                    Object a = chain.getArgs().get(0);
                    if (a instanceof Integer) sDeductedWanted = (Integer) a;
                }
                return chain.proceed();
            });
            Xp.log(TAG + "cut-out visibility watched");
        } catch (Throwable t) {
            Xp.w(TAG + "cut-out visibility hook failed: " + t);
        }
    }

    private static void hookAodShrink(final ClassLoader cl) {
        // The full-screen AOD's shrink, and the one view that must not take it.
        //
        // On the way into full-screen AOD KeyguardPanelViewController.doDeductedImageScaleAnim
        // scales a list of views to (wallpaperScale - 0.05) around (0.5w, 0.4h) - 0.95 at rest.
        // On a depth video wallpaper the list is fullAodAnimationViewsForVideoDepth, which the
        // cover's layer is not in; everywhere else it is animationViews, which holds the
        // keyguard's root layout and with it keyguard_background_layer, where the video cover
        // view sits. A still wallpaper's window shrinks along with it (the same method sets the
        // wallpaper's matrix) and the cover IS that window's texture there, so nothing shows. A
        // video wallpaper's window does not move - isWallpaperScaleEnable() is false for video by
        // the OEM's own rule - and the cover view pulled in from all four edges over it.
        //
        // The clock, the date and the cards are meant to shrink - the AOD poses are written in
        // that scaled space (keyguard-shared-zoom) - so the call is left alone and only the cover
        // view is counter-scaled, in its pre-draw guard, around the same point. This records the
        // scale that guard is to undo; any other scale on the way (the unlock, the shade) is left
        // to act on the cover as it always has.
        try {
            Class<?> kpvc = Xp.findClass("com.android.keyguard.panel.KeyguardPanelViewController", cl);
            Xp.hookAll("sysui.depth.scale_anim", kpvc, "doDeductedImageScaleAnim", chain -> {
                Object[] a = chain.getArgs().toArray();
                if (a.length > 0 && a[0] instanceof Float) {
                    CoverPush.sAodShrink = (Float) a[0] - 0.05f;
                }
                return chain.proceed();
            });
            Xp.log(TAG + "AOD shrink hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "AOD shrink hook failed (the video cover may pull in from the edges"
                    + " into full-screen AOD): " + t);
        }
    }

    private static void hookWallpaperBlack(final ClassLoader cl) {
        // The AOD's wallpaper dim. doWallpaperBlackAnim animates "wallpaperBlack" and lands it
        // through ViewRootImpl.setWallpaperBlack(float) - a MIUI channel that reaches the
        // wallpaper window itself. The value, and whether the window stays dimmed, survive a
        // cover's rebuild normally - but our reload resets the wallpaper-side render state, and
        // with the cover up the dim was observed to vanish into AOD. Remember the value and the
        // root it was set on; the first-frame signal re-asserts both. See reassertAodDim().
        try {
            Class<?> vri = Xp.findClass("android.view.ViewRootImpl", cl);
            Xp.hookAll("sysui.wallpaper.black", vri, "setWallpaperBlack", chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length == 1 && args[0] instanceof Float) {
                    float v = (Float) args[0];
                    if (v != sLastWallpaperBlack) {
                        sLastWallpaperBlack = v;
                        sBlackRoot = chain.getThisObject();
                        if (sVerbose) {
                            Xp.log(TAG + "wallpaperBlack=" + v + " on "
                                    + (sBlackRoot == null ? "null" : sBlackRoot.getClass()
                                    .getSimpleName()));
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(TAG + "wallpaperBlack hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "wallpaperBlack hook failed (the cover may sit bright in AOD): " + t);
        }
    }

    private static void hookKeyguardWallpaperManager(final ClassLoader cl) {
        // MIUI's own answer to "what kind of wallpaper is this", which beats every heuristic
        // below it: SystemUI keeps this class unobfuscated and it holds a live
        // MiuiWallpaperManager, whose getMiuiWallpaperType(which) is the API MIUI itself asks.
        // Captured from the constructor the same way the wallpaper engine is - it is a Dagger
        // singleton built during SystemUI startup, so the hook has to be in before that.
        try {
            Class<?> wp = Xp.findClass(CLS_KG_WALLPAPER_MANAGER, cl);
            Xp.hookAllConstructors("sysui.wallpaper.manager_ctor", wp, chain -> {
                Object result = chain.proceed();
                sKgWallpaperMgr = chain.getThisObject();
                return result;
            });
            Xp.log(TAG + "keyguard wallpaper manager hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keyguard wallpaper manager hook failed, falling back to "
                    + "MIUI's files for the wallpaper type: " + t);
        }
    }

    private static void hookNotifStateChange() {
        // The live container, and - on every clock style except the all_in_one family - the
        // only place the notification Y ever arrives.
        //
        // KeyguardClockContainer forwards this straight to
        // AnimationHelper.mClockAnima.notifStateChange, and each style overrides that with its
        // own animation. Only AllInOneClockAnimation goes on to
        // KeyguardClockNotifInteractor.setNotifY. Measured with the classic style selected: zero
        // setNotifY calls across a full enter and exit of cover mode, so applyCollapse() never
        // ran and the whole clock half of the module was dead there - not because anything was
        // broken, but because nobody was driving it.
        //
        // `time_group` is the test for which of the two this is, because it is all_in_one's own
        // id and all_in_one is the only family that reaches setNotifY. Where it is present this
        // hook does nothing but what it always did: the coercion stays inside setNotifY, where
        // the OEM's spring reads it back, and moving it out here was measured to change what
        // ClockBaseAnimation's own "has this y changed" early-out compares against - which
        // parked the OEM's font animation and rendered the clock at the wrong size.
        //
        // Where it is absent there is no setNotifY to do any of that, so the y is learned and
        // held at this level instead: learned because the exit hands back to it (sLastSystemY
        // stays NaN for the life of the process otherwise), and held because the system's own
        // re-assertions arrive here on these styles and would otherwise undo the squeeze.
        try {
            Xp.hookAll("sysui.notif.state_change", sContainerCls, "notifStateChange", chain -> {
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView((View) chain.getThisObject())) return chain.proceed();
                // The live keyguard clock only.
                sContainer = (View) chain.getThisObject();
                if (ClockViews.findClockView(sContainer, "time_group") != null) {
                    // The all_in_one family, which this hook does not act on - and the only
                    // place a probe could see that the channel was used at all. `getArgs`
                    // allocates, and this runs every frame: taken only while a run is armed.
                    if (ClockMove.on()) {
                        Object[] a = chain.getArgs().toArray();
                        ClockMove.noteState((Float) a[0], a[1], (Float) a[0]);
                    }
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Not our own writes, which are not what the system asked for. The system's
                // re-assertions while we hold ARE: they carry where the notifications start, and
                // the small clock gives way to them off this number.
                if (!sSelfDriving && !ClockRoom.sRoomReasserting) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                else if (hold == null) args[0] = ClockRoom.roomForRows(requested);
                ClockMove.noteState(requested, args[1], (Float) args[0]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.w(TAG + "notifStateChange hook failed: " + t);
        }
    }

    private static void hookTimeView(final ClassLoader cl) {
        // The OEM squeezes the clock through these four setters on TimeView. Scaling
        // setSizeInternal's argument shrinks the whole clock without touching the animation:
        // the OEM still computes and drives every frame, we just rescale the last mile.
        try {
            Class<?> timeView = Xp.findClass(CLS_TIME_VIEW, cl);
            XposedInterface.Hooker axisLog = chain -> {
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView((View) chain.getThisObject())) return chain.proceed();
                String name = chain.getExecutable().getName();
                Object[] args = chain.getArgs().toArray();
                // The OEM's own number, before our rescale: it is called every frame on every
                // TimeView while the clock is being squeezed, so it is that animation's progress.
                if ("setSizeInternal".equals(name)) ClockMove.noteSize((Float) args[0]);
                // The variable font's other axis. The ink box is what the size was read from,
                // and it moves while setSizeInternal does not - which is either the weight or a
                // second source of the box, and this is the reading that tells the two apart.
                if ("setWeight".equals(name)) ClockMove.noteWeight((Float) args[0]);
                boolean scaled = false;
                if ("setSizeInternal".equals(name) && !Float.isNaN(sSizeScale)) {
                    args[0] = ((Float) args[0]) * sSizeScale;
                    scaled = true;
                }
                if (sVerbose) {
                    Xp.log(TAG + "TimeView." + name
                            + "(" + args[0] + ")" + (scaled ? " [scaled]" : "")
                            + " on " + viewIdOf((View) chain.getThisObject()));
                }
                return chain.proceed(args);
            };
            for (String m : new String[]{"setSizeInternal", "setWidth", "setHeight", "setWeight"}) {
                Xp.hookAll("sysui.clock.text_axis." + Xp.slug(m), timeView, m, axisLog);
            }
            // Its bounds are computed inside its own draw; see ClockCollapse.onGlyphDrawn().
            Xp.hookAll("sysui.clock.glyph_draw", timeView, "onDraw", chain -> {
                Object result = chain.proceed();
                if (!btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView((View) chain.getThisObject()))
                    ClockCollapse.onGlyphDrawn((View) chain.getThisObject());
                return result;
            });

            // Own the colour the way we own the y, but at the far end of the pipeline.
            //
            // Writing glassData and stopping there does nothing: the shader's parameters are
            // pushed by MiuiBlurUtils.setMiGlass/setGlassEffectMethod when a colour is SET, not
            // read out of the array when the view draws. The per-draw write that used to be here
            // was dead for exactly that reason - the OEM's own write of the same array is what
            // ever made a tint show up.
            //
            // So the substitution goes where the OEM's value enters: whichever way SystemUI
            // arrived at a colour - the wallpaper palette, a colour animation, or our own
            // updateGlassValue frame - it reaches a glyph through one of these setters, and
            // none of them has to know anything about the palette.
            //
            // The lightness is not touched here, and setBrightness - which carries the material's
            // own dark/light flag and used to be hooked alongside these - deliberately is not.
            Xp.hookAll("sysui.clock.glass_color", timeView, "setGlassColor", chain -> {
                View self = (View) chain.getThisObject();
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView(self)
                        || !ClockGlass.glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
            Xp.hookAll("sysui.clock.text_color", timeView, "setTextColor", chain -> {
                View self = (View) chain.getThisObject();
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView(self)
                        || !ClockGlass.glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
            // The glass's dark/light switch (glassData[6]), which two OEM routes decide from two
            // different pictures - issue #23. The palette pass (setClockPalette -> AllInOneBase.
            // updateClockGlassBrightness) is computed from what the lock screen shows, the cover
            // included. The colour-extraction animation (AllInOneBaseAnimation.
            // doColorExtractionAnimation, glass effect 5) calls the same thing on its own, and
            // reads the ORIGINAL wallpaper, which in cover mode is not on screen: on a light
            // wallpaper it says "dark" and the clock over the cover turns near black. It fires on
            // a wake, and sometimes just after an entry. So in cover mode the switch follows the
            // palette the OEM computed for the cover; out of it, whatever the OEM says.
            Xp.hookAll("sysui.clock.brightness", timeView, "setBrightness", chain -> {
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView((View) chain.getThisObject())) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                if (!sCoverMode) {
                    if (sScreenOn && args[0] instanceof Boolean) sBrightOutside = (Boolean) args[0];
                } else if (sScreenOn && sBrightCover != null && ClockGlass.glassStyleFor((View) chain.getThisObject())) {
                    args[0] = sBrightCover;
                }
                return chain.proceed(args);
            });
            try {
                Xp.hookAll("sysui.clock.palette", Xp.findClass("com.miui.clock.MiuiGalleryBaseClock",
                        timeView.getClassLoader()), "setClockPalette", chain -> {
                    if (chain.getThisObject() instanceof View && btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isHostedClockView((View) chain.getThisObject())) return chain.proceed();
                    java.util.List<Object> a = chain.getArgs();
                    // Only a palette that came with its colours: the ones the OEM computes from a
                    // picture. The map-less calls re-send a textDark without looking at anything.
                    if (sCoverMode && a.size() > 2 && a.get(1) instanceof Boolean && a.get(2) != null) {
                        sBrightCover = (Boolean) a.get(1);
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                Xp.w(TAG + "setClockPalette hook failed: " + t);
            }
        } catch (Throwable t) {
            Xp.w(TAG + "TimeView hook failed: " + t);
        }
    }

    private static void hookClockInteractor(final ClassLoader cl) {
        // The real chokepoint. Everything that squeezes the clock - the OEM's own
        // notification-Y flow, KeyguardClockContainer.notifStateChange, and our own
        // per-frame driving - lands here, so this is where ownership is enforced.
        try {
            Class<?> interactor = Xp.findClass(CLS_INTERACTOR, cl);
            Xp.hookAll("sysui.clock.notif_y", interactor, "setNotifY", chain -> {
                if (btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterPresentation.isDrivingHostedClock()) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Only the system's own emissions tell us where the clock really
                // belongs; our own frames must not be mistaken for that.
                if (!sSelfDriving && !ClockRoom.sRoomReasserting) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                else if (hold == null && !sSelfDriving) args[0] = ClockRoom.roomForRows(requested);
                ClockMove.noteNotifY(requested, (Float) args[0]);
                // The coerced Y has to reach the original, which is what proceed(args) is for:
                // this is the one hook whose whole purpose is rewriting an argument.
                // Nothing is placed from here: the OEM applies parts of this frame later (Folme),
                // so any reading taken here is a frame stale. ClockCollapse places from the
                // pre-draw, after every transform of the frame has been written.
                Object result = chain.proceed(args);
                if (sVerbose) {
                    Xp.log(TAG + "setNotifY(" + args[0] + ") -> " + result);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "interactor hook failed: " + t);
        }
    }

    /**
     * Opens the app when its code is dialled - from in here, where it actually works.
     *
     * The app has a manifest receiver for the same broadcast and on this phone it is never
     * called. Measured rather than assumed: the receiver is registered and resolvable (pm
     * query-receivers finds it for both actions), an identically-declared receiver in another
     * installed app does get called for its own code, and ours is not called for any of three
     * code lengths. Whatever the dialler is filtering on, a third-party app's manifest receiver
     * does not get there.
     *
     * Registering it here sidesteps the whole question twice over. This is SystemUI: a receiver
     * registered at runtime is not subject to the implicit-broadcast rules that manifest
     * receivers are, and starting an activity from a system process is not subject to the
     * background-activity-start rules that made the app's own attempt fail silently even when
     * it was reached.
     *
     * Kept alongside the app's receiver rather than replacing it: the app's works on phones
     * whose dialler does deliver, and on those this one simply never fires.
     */
    /**
     * What the process needs once it has a Context: the receivers, the state file, the first
     * hello to the wallpaper process. Called when the keyguard first attaches, which is the
     * first moment there is one - and again by a hot-reloaded generation, which starts with
     * every static field empty.
     */
    static synchronized void boot(Context ctx) {
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;
        sAppCtx = ctx;
        // 取餐码的云端名单：上一次拉到的那份从这里读回来（存在 SystemUI 自己的 filesDir 里，
        // app 和它是两个 uid，所以不能由 app 下载）。
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // 取餐码卡片的点击接收器：卡比进程活得久（重启系统界面后那张卡还在背屏上），
        // 而这个进程可能从没发过卡，所以接收器在这里就装上。见 PickupCodeIsland.wake。
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // Before the first send: the wallpaper process answers with it (ProbeGuard).
        ProbeGuard.mint();

        // The app, the wallpaper process (its hello and first-frame signals) and 高德 (its map).
        ProbeGuard.register(ctx, new IntentFilter(ACTION), TAG, ProbeReceiver::new,
                BuildConfig.APPLICATION_ID, "com.miui.miwallpaper", AmapNavScene.PKG);
        // HyperChanger retains the existing owner / excludes this standalone feature.
        Xp.log(TAG + "receiver registered for " + ACTION);
        // A SystemUI that starts mid-navigation missed 高德's init; there is a receiver now.
        ImmersiveHost.onStart(ctx);
        // (registerSecretCode is defined below; see the comment there for why the dialled code
        // is answered from in here rather than by the app's own manifest receiver.)
        // Asks the wallpaper process what it can take, now that there is a receiver for the
        // answer. A build that predates the question never answers, which is the answer.
        try {
            ProbeGuard.send(ctx, CoverPush.wallpaperIntent("hello"));
            // Once more, shortly after: a wallpaper process hot reloaded in the same install
            // batch is between its retire and its new receiver for a moment, and this is where
            // it learns the token it answers with. One retry, not a loop.
            Gen.postDelayed(() -> {
                try {
                    ProbeGuard.send(ctx, CoverPush.wallpaperIntent("hello"));
                } catch (Throwable t) {
                    Xp.w(TAG + "hello retry to the wallpaper process failed: " + t);
                }
            }, 1500L);
        } catch (Throwable t) {
            Xp.w(TAG + "hello to the wallpaper process failed: " + t);
        }
        ModuleState.loadState();
        btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.onRuntimeReady(ctx);
        try {
            Gen.observer(ctx.getContentResolver(), android.net.Uri.parse(
                    "content://btm.m.os4.systemuihook.settingsappearance/lyrics"), true,
                    new android.database.ContentObserver(main()) {
                        @Override public void onChange(boolean selfChange) { LockLyrics.hostLibraryChanged(); }
                    });
        } catch (Throwable t) { Xp.w(TAG + "lyric library observer unavailable: " + t); }
        // 高德 running already has the 「高德公交地铁」 switch's default until it is told.
        // No standalone transit registration or peer activation.
        // Again at startup, not only when the switch is touched: the flag the always-on display
        // reads is written by this process, and a phone that was rebooted with the switch on has
        // nothing in it otherwise.
        // HyperChanger retains the existing owner / excludes this standalone feature.
        // The icon views can already exist by now - the file is read when the keyguard attaches,
        // which is not necessarily before the fingerprint view is built.
        // HyperChanger retains the existing owner / excludes this standalone feature.

        // Which kind of wallpaper is on the lock screen decides where the cover is drawn, and
        // cover mode can be restored from disk before any push has had a chance to work it
        // out. Off the main thread: it reads MIUI's files.
        final Context appCtx = ctx;
        worker().post(new Runnable() {
            @Override
            public void run() {
                String k = CoverPush.wallpaperKind(appCtx, "lock");
                sVideoWallpaper = liveKind(k);
                if (sVideoWallpaper && sDepthHidden) main().post(new Runnable() {
                    @Override
                    public void run() { setDepthHidden(true); }
                });
            }
        });

        // The keyguard container is NOT always torn down on screen off - verified on device,
        // onDetachedFromWindow never fired across a full off/on cycle - so detach alone is
        // not enough to guarantee we let go. Screen off and unlock both end any reason for
        // us to own the clock, and they are framework signals rather than OEM internals.
        BroadcastReceiver lifecycle = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                // Cached for the per-frame paths, which cannot afford screenOn()'s binder call:
                // how the clock is inked is a per-frame decision. screenOn() itself stays for the
                // event-time questions, where it is authoritative.
                // Every one of these three changes the answer to one of the two cached readings,
                // so the timer below is not what anyone waits on at the moments that matter.
                forgetSysReads();
                // The lyric window's heartbeat stops while the phone is unlocked; a screen going
                // off or on is where the lock screen can be back. See LockLyrics.WATCH.
                if (!Intent.ACTION_USER_PRESENT.equals(a)) LockLyrics.resumeWatch();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    sScreenOn = true;
                    sAodGrey = Float.NaN;
                    ClockGlass.applyGlassBrightness();
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    sScreenOn = false;
                    sAodGrey = Float.NaN;
                    // The wallpaper dim belongs to the lit lyric page; the doze has its own
                    // wallpaper dim and stacking the two would darken the AOD. The wake
                    // re-enters the clock (and with it the veil) if the page is still up.
                    KeepDim.apply(0f);
                    // Held into a doze, the screen wake lock would pull the phone back out of it.
                    if (sKeepAwakeView != null) keepAwake(false);
                    // A tap still waiting out its double tap window was aimed at a screen that
                    // is gone; whatever was going to cancel it cannot arrive now.
                    LockTaps.cancelPendingTap("screen off");
                    // The shade goes away with the screen, on the same silent path as unlock.
                    // HyperChanger retains the existing owner / excludes this standalone feature.
                }
                if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    CoverMorphLayer.cancel();
                    CoverCardLayer.hideNow();
                    // A shade pulled down over the lock screen is force-closed without the
                    // expansion setter being called; see LegacyShadeBridge.forgetExpansion.
                    // HyperChanger retains the existing owner / excludes this standalone feature.
                }
                else CoverCardLayer.refresh();
                // An immersive page's app stops drawing it while the phone is unlocked, and is
                // asked again from the doze. Its own try: a failure there must not cost the rest.
                try {
                    if (Intent.ACTION_USER_PRESENT.equals(a)) ImmersiveHost.onUnlocked();
                    else if (Intent.ACTION_SCREEN_OFF.equals(a)) ImmersiveHost.onScreenOff();
                } catch (Throwable t) {
                    Xp.w(TAG + "immersive lifecycle failed: " + t);
                }
                if (Intent.ACTION_SCREEN_OFF.equals(a)) CoverMorphLayer.cancel();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    // The wake normally entered already, from the doAnimationToAod hook, before
                    // the first lit frame. This is the fallback for a build without that method.
                    if (clockHeld() && ClockCollapse.leavingOrOff() && keyguardShowing()) {
                        ClockCollapse.enter(true, true, "screenOn");
                    }
                    // Waking re-runs the OEM's depth pipeline, and if the keyguard was rebuilt
                    // while the screen was off the guard went away with the old view.
                    if (sDepthHidden) setDepthHidden(true);
                    // A hand-back of the live wallpaper that had to wait for a lock screen.
                    else if (sVideoWpOwed) setDepthHidden(false);
                    if (sCoverMode) MediaCard.applyMediaCard();
                    // The doze had the wallpaper dim released; ask for it again if the
                    // lyric page is still what the lock screen shows.
                    KeepDim.resume();
                    // A tap into the cover is likely from here: have its picture made first.
                    if (!sCoverMode) CoverPush.precomposeAsync(PRECOMPOSE_WAKE_MS);
                    return;
                }
                // Cover mode outlives the display going off and the phone being unlocked; its
                // grip on the clock does not. The AOD shows the full clock - the AOD's clock is
                // this same clock, SystemUI's keyguard in doze - and so does an unlocked phone.
                // Going off, the sleep hooks have normally started the walk into the AOD already
                // and this does nothing; it is the fallback for a build without them.
                if (Intent.ACTION_SCREEN_OFF.equals(a) && clockHeld()) ClockCollapse.toAod();
                else ClockCollapse.release(a);
            }
        };
        IntentFilter lf = new IntentFilter();
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_USER_PRESENT);
        Gen.receiver(ctx, lifecycle, lf, Context.RECEIVER_NOT_EXPORTED);
        Xp.log(TAG + "lifecycle receiver registered (screen off / user present)");
        // Last: a marker left by a hot-reloaded generation means this process did not survive
        // it, and this build must not be hot reloaded into again (see HotReload.onBoot).
        // HyperChanger owns hot reload; no standalone generation is started.
    }

    /**
     * Puts the module back up after a dry run took it down, in the same generation and without
     * running installSystemUi - the hooks were never touched. This is the boot-time work plus
     * the attach path, exactly what a keyguard rebuild does.
     *
     * <p>Only the guard that makes boot one-shot is cleared. The state file, the container and
     * what the settings restored are still in memory on purpose: the dry run proves the
     * teardown, and imitating a new generation's empty statics would be Step 7's adoption,
     * which is a different test.
     */
    static void reenterAfterDryRun(Context ctx) {
        View container = sContainer;
        synchronized (Main.class) {
            sReceiverRegistered = false;
        }
        try {
            boot(ctx);
        } catch (Throwable t) {
            Xp.w(TAG + "dry-run boot failed: " + t);
        }
        if (container == null) {
            Xp.w(TAG + "dry run took the module down with no container to re-attach to");
            return;
        }
        try {
            onContainerAttached(container);
        } catch (Throwable t) {
            Xp.w(TAG + "dry-run re-attach failed: " + t);
        }
        // The mini player normally hangs off the shortcut row being built, and a dry run did
        // not rebuild the keyguard - so the hook that builds its controller will not fire.
        try {
            MiniPlayerRuntime.reattach();
        } catch (Throwable t) {
            Xp.w(TAG + "dry-run mini player re-attach failed: " + t);
        }
    }

    /**
     * Whether the display is on, as of the last lifecycle broadcast.
     *
     * A cache of screenOn() for the paths that ask once per frame. Optimistic by default: a
     * missed broadcast would otherwise leave the cover's colour switched off for good, and being
     * wrong in that direction costs a screenshot's worth of wrongness rather than a clock nobody
     * can read.
     */
    static volatile boolean sScreenOn = true;

    /** The cached reading, for LockLyrics' per-frame questions. */
    public static boolean screenOnCached() {
        return sScreenOn;
    }

    /**
     * The clock squeeze's one entry point, resolved by shape.
     *
     * Matching on (float, boolean, enum) rather than on the enum's fully qualified name: the
     * name moved between HyperOS versions and the shape did not, and the only thing this
     * module ever does with the enum is hand a constant of it straight back.
     */
    private static Method findNotifStateChange(Class<?> container) {
        for (Method m : container.getDeclaredMethods()) {
            if (!"notifStateChange".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == float.class && p[1] == boolean.class && p[2].isEnum()) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * The named constant of that enum, or its first one if the names have moved too.
     *
     * Remembered per name, because this is called once per frame of every transition that drives
     * the clock - applyY() asks for it before every notifStateChange - and the answer is the same
     * constant for the life of the process. Resolving it costs an array: getEnumConstants()
     * hands back a fresh clone on every call, and the scan then allocates an iterator. Caching it
     * also stops the "no such constant" line below from being logged sixty times a second on a
     * build whose names have moved, which is a cross-process write on the frames that can least
     * afford one; it is now said once, and then the fallback is simply used.
     */
    private static final java.util.HashMap<String, Object> sTopTypes = new java.util.HashMap<>();
    private static Class<?> sTopTypesFor;

    private static Object topChangeType(String name) {
        Class<?> c = sTypeCls;
        if (c == null) return null;
        if (sTopTypesFor == c) {
            Object hit = sTopTypes.get(name);
            if (hit != null) return hit;
        } else {
            sTopTypes.clear();
            sTopTypesFor = c;
        }
        Object[] all = c.getEnumConstants();
        if (all == null || all.length == 0) return null;
        for (Object o : all) {
            if (((Enum<?>) o).name().equals(name)) {
                sTopTypes.put(name, o);
                return o;
            }
        }
        Xp.log(TAG + "no " + name + " on " + c.getName() + ", falling back to "
                + ((Enum<?>) all[0]).name());
        sTopTypes.put(name, all[0]);
        return all[0];
    }

    /**
     * Puts the OEM's clock at one notifY, in this frame, with no animation of its own. Must
     * already be on the view's thread.
     */
    static void applyY(float y) {
        View v = sContainer;
        Method m = sNotifStateChange;
        if (v == null || m == null) return;
        try {
            Object type = topChangeType("STATE_CHANGED");
            // NOTE the inversion: KeyguardClockContainer calls this arg "isDragOrFling",
            // AllInOneClockAnimation reads it as "withAnim" = !isDragOrFling.
            // true  -> drag/fling  -> snap  (what per-frame driving wants)
            // false -> state change -> Folme animates, font_weight included
            sSelfDriving = true;
            try {
                m.invoke(v, y, true, type);
            } finally {
                sSelfDriving = false;
            }
        } catch (Throwable t) {
            Xp.w(TAG + "applyY failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * The notifY channel clamps the variable font at 674/337/317. To find out whether the
     * clock can go smaller than that we need to see what the OEM actually applies the axes
     * to - textSize, fontVariationSettings, or a scale - so dump the live view tree.
     */
    /**
     * Talks to com.miui.clock.allInOne.TimeView directly, bypassing the ClockResult clamp
     * that pins the notifY path at 674/337/317. -1 leaves an axis alone.
     */
    static void setClockParams(final float w, final float h, final float weight,
                                       final int size, final float sizeInternal) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("hour_view", "id", "com.android.systemui");
                    View t = v.getRootView().findViewById(id);
                    if (t == null) { Xp.log(TAG + "hour_view not found"); return; }
                    // setClockParams()'s argument order is not (width, height, weight) - feeding
                    // it the fields' own values rotated them - so drive the three axes through
                    // their individual setters instead, where the mapping is unambiguous.
                    if (size >= 0) Xp.callMethod(t, "setSize", size);
                    // setSize(int) only stores the value; textSizePx - the actual point size,
                    // and the knob that reaches well below the font axes' floor - comes from here.
                    if (sizeInternal >= 0) Xp.callMethod(t, "setSizeInternal", sizeInternal);
                    if (w >= 0) Xp.callMethod(t, "setWidth", w);
                    if (h >= 0) Xp.callMethod(t, "setHeight", h);
                    if (weight >= 0) Xp.callMethod(t, "setWeight", weight);
                    t.invalidate();
                    RectF b = (RectF) Xp.getObjectField(t, "mTextBounds");
                    Xp.log(TAG + "axes width=" + Xp.getObjectField(t, "width")
                            + " height=" + Xp.getObjectField(t, "height")
                            + " weight=" + Xp.getObjectField(t, "weight")
                            + " size=" + Xp.getObjectField(t, "size")
                            + " textSizePx=" + Xp.getObjectField(t, "textSizePx")
                            + " -> glyphs " + Math.round(b.width()) + "x" + Math.round(b.height()));
                } catch (Throwable e) {
                    Xp.w(TAG + "setClockParams failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    private static String us(long ns) {
        return ns < 1000000L ? (ns / 1000) + "us" : r1(ns / 1000000f) + "ms";
    }

    /** The density of whatever screen this process is glued to. */
    static float density() {
        View v = sContainer;
        if (v != null) return v.getResources().getDisplayMetrics().density;
        return sAppCtx != null ? sAppCtx.getResources().getDisplayMetrics().density : 3f;
    }

    /**
     * The height the setting means, converting a pre-dp value the once there is a box to convert
     * it with. The coefficient multiplied this same box, so dividing it back out is what it was
     * worth - the clock keeps the size it had, instead of becoming the 0.335dp the old number
     * would read as.
     */
    private static float heightDpFor(RectF box) {
        float legacy = sClockLegacyK;
        if (Float.isNaN(legacy)) return sClockHeightDp;
        sClockLegacyK = Float.NaN;
        if (box == null || box.height() <= 0f) return sClockHeightDp;
        float dp = legacy * box.height() / density();
        sClockHeightDp = dp < CLOCK_HEIGHT_MIN_DP ? CLOCK_HEIGHT_MIN_DP
                : (dp > CLOCK_HEIGHT_MAX_DP ? CLOCK_HEIGHT_MAX_DP : dp);
        Xp.log(TAG + "clock scale " + r3(legacy) + " was a coefficient; " + r1(box.height())
                + "px of glyphs makes that " + r1(sClockHeightDp) + "dp");
        ModuleState.saveState();
        return sClockHeightDp;
    }

    /** When the last with-cover-off needart answer was sent. See the needart branch. */
    static volatile long sNeedArtOffAt;

    /**
     * The collapsed clock's height, in dp, from the slider or from a stored value.
     *
     * A value under the slider's own range is a coefficient from before the unit changed: it is
     * set aside rather than clamped, because what it is worth is boxHeight * k and the box is
     * whatever style and layout the phone is on. See sClockLegacyK.
     */
    static void setClockHeightDp(float v) {
        if (v < CLOCK_HEIGHT_MIN_DP) {
            sClockLegacyK = v;
            Xp.log(TAG + "clock value " + v + " predates the dp unit, converting when measured");
            return;
        }
        sClockLegacyK = Float.NaN;
        sClockHeightDp = v > CLOCK_HEIGHT_MAX_DP ? CLOCK_HEIGHT_MAX_DP : v;
        Xp.log(TAG + "clock height = " + sClockHeightDp + "dp");
    }

    /** The clock size as a fraction of the style's full clock. NaN = follow sClockHeightDp. */
    static void setClockSize(float v) {
        if (Float.isNaN(v) || v <= 0f) {
            sClockSize = Float.NaN;
        } else {
            sClockSize = v < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (v > 1f ? 1f : v);
        }
        Xp.log(TAG + "clock size = " + sClockSize);
    }

    static void setClockOffsetDp(float v) {
        if (Float.isNaN(v)) v = 0f;
        sClockOffsetDp = v < CLOCK_OFFSET_MIN_DP ? CLOCK_OFFSET_MIN_DP
                : (v > CLOCK_OFFSET_MAX_DP ? CLOCK_OFFSET_MAX_DP : v);
        Xp.log(TAG + "clock offset = " + sClockOffsetDp + "dp");
    }

    /**
     * The size the collapsed clock is at right now, as a fraction of the style's full clock -
     * the slider's value, or what the dp default comes to on this style. NaN with no clock.
     */
    /** How many times the glyphs drawn now must grow to be the style's full clock. */
    static float clockFullRatio() {
        RectF box = ClockViews.glyphBox();
        if (box == null || box.height() <= 0f) return 1f;
        return ClockCollapse.fullRatio(ClockViews.glyphUnit(box));
    }

    static float effectiveClockSize() {
        if (!Float.isNaN(sClockSize)) return sClockSize;
        RectF box = ClockViews.glyphBox();
        if (box == null || box.height() <= 0f) return Float.NaN;
        float unit = ClockViews.glyphUnit(box);
        float full = unit * ClockCollapse.fullRatio(unit);
        if (!(full > 0f)) return Float.NaN;
        float s = sClockHeightDp * density() / full;
        return s < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (s > 1f ? 1f : s);
    }

    /**
     * The cover transition's spring response, in seconds, from the slider or from a stored value.
     *
     * Nothing is re-applied here on purpose. The value is read at the moment a transition starts
     * and handed to the integrator with it, so a change belongs to the next one - and a spring
     * that is already flying keeps the parameters it began with, which is what stops a drag from
     * bending the curve under the clock that is being drawn.
     *
     * The fades are the exception and do have to be pushed: they live in another process. See
     * pushFadeMs().
     */
    static void setClockResponse(float v) {
        sClockResponse = v < CLOCK_RESPONSE_MIN ? CLOCK_RESPONSE_MIN
                : (v > CLOCK_RESPONSE_MAX ? CLOCK_RESPONSE_MAX : v);
        Xp.log(TAG + "clock response = " + sClockResponse + "s, fades "
                + fadeMsFor(sClockResponse) + "ms");
        ModuleState.saveState();
        CoverPush.pushFadeMs(sAppCtx);
    }

    /**
     * The view a clock style wants scaled, in one tree, or null if this tree draws no time.
     *
     * Two shapes exist on this device and they have nothing in common:
     *
     * - **all_in_one** (the OEM's variable-font clock): the digits are TimeViews inside a
     *   `time_group`, one tree drawing the hour and the other the minute.
     * - **classic**: one `time_view`, a `com.miui.clock.MiuiTextGlassView`, which is an ordinary
     *   TextView holding "14:26" - no group, no TimeViews, and only in the background tree.
     *
     * Everything the collapse does works on whichever of the two is there, so this is the only
     * place that has to know the difference. (A style that has neither takes the clock takeover
     * out of play entirely rather than throwing: the date, and the wallpaper, still work.)
     */
    static View clockTarget(View root) {
        // Always tried first and never answered from the cache: one resource lookup and a
        // findViewById, and short-circuiting it is how a style change would go unnoticed.
        View g = ClockViews.findClockView(root, "time_group");
        if (usable(g)) {
            // Recorded, not answered from: the lookup above is what decides, and it is still the
            // first thing tried on every call. But sClockTargets is the only record of what this
            // resolved to, and the frame-by-frame guard reads it - leaving the fast path out of
            // it made every tree look unresolved for as long as the style had a time_group, and
            // the guard re-placed the clock four times a second for ever.
            //
            // Written only when it says something new. The same view comes back on all but the
            // first frame of a transition, and this is a WeakHashMap keyed on the view - a put
            // per root per frame, each one re-hashing the key and walking the table, for a value
            // that was already there.
            if (ClockViews.sClockTargets.get(root) != g) ClockViews.sClockTargets.put(root, g);
            return g;
        }
        View cached = ClockViews.sClockTargets.get(root);
        if (cached != null && cached != root && usable(cached) && cached.getParent() != null
                && ClockViews.drawnUnder(cached, root)) {
            return cached;
        }
        // The root standing in for itself means "this tree draws no clock" - some styles put the
        // whole clock in one tree and only the date in the other, and without remembering that,
        // every frame of the squeeze would walk a whole layer to fail to find one.
        //
        // But only for as long as the tree looks the way it did when the answer was taken. It is
        // taken at attach, when the clock view is still 0x0, and the only thing that re-asks is
        // a change of date view - which a style whose date this module cannot find never
        // produces. The doodle is that style, and it stayed unscaled for the life of the
        // process: a fresh launch of SystemUI answered "no clock here" once, for ever.
        if (cached == root) {
            Integer was = ClockViews.sNoClockAt.get(root);
            if (was != null && was == clockSize(root)) return null;
        }
        View found = ClockViews.resolveTimeTarget(root);
        if (found != null && !ClockViews.drawnUnder(found, root)) found = null;
        ClockViews.sClockTargets.put(root, found == null ? root : found);
        if (found == null) ClockViews.sNoClockAt.put(root, clockSize(root));
        else ClockViews.sNoClockAt.remove(root);
        return found;
    }

    /**
     * The size of the clock view at the top of this layer, or -1 when there is not one yet.
     *
     * A number rather than the view itself: the view object outlives its own measurement, so
     * what has to be compared is how big it was, and 0x0 is the tell for "before the first
     * layout" that the negative answer above was taken under.
     */
    private static int clockSize(View root) {
        View clock = ClockViews.clockViewOf(root);
        if (clock == null) return -1;
        // Which face is showing too, not only its size: oriental A swaps its big face for its
        // notification variant at the same 1200x2608, and fades its other layer by alpha, and
        // a "no clock here" taken on the hidden one has to be asked again when that changes.
        int shown = clock.getVisibility() == View.VISIBLE && clock.getAlpha() > 0.01f ? 1 : 0;
        return ((System.identityHashCode(clock) * 31 + clock.getWidth()) * 8191
                + clock.getHeight()) * 2 + shown;
    }

    public static String idOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "no-id";
        }
    }

    static boolean usable(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0;
    }

    /** Read-only probe switch: measure through the hold and the TTL cache, change nothing. */
    static volatile boolean sProbeLive;

    /** Air between the date and the collapsed clock. */
    static final float CLOCK_GAP_DP = 10f;

    // ------------------------------------------------------- transition trace

    static String r1(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 10f) / 10f);
    }

    static String r2(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 100f) / 100f);
    }

    static String r3(float v) {
        return Float.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 1000f) / 1000f);
    }

    /**
     * Where the collapse pivots horizontally, in the target's own coordinates.
     *
     * A clock that is centred on the screen has to stay centred as it shrinks, and one that is
     * aligned to the left - as the classic style's is, under a left-aligned date - has to stay
     * left. So the pivot follows what the style already does rather than being a constant:
     * centred styles pivot on the middle of the screen, the rest on their own left edge.
     *
     * **The box has to be the POOLED one**, across both trees, like the top edge. all_in_one
     * draws half the clock in each tree - the hour in the background one, the minute in the
     * foreground one - so each tree on its own looks like an off-centre clock, and giving each
     * its own pivot made the two halves shrink towards their own left edges and pull apart. On
     * screen: "14" and "43" with a gap down the middle.
     *
     * Not g.getWidth()/2 for the centred case: this runs from the setNotifY hook, which can fire
     * before the group is laid out, and a width of 0 would put the pivot on the left edge - the
     * clock then collapses into the corner instead of staying centred under the date.
     */
    static float clockPivotX(View g, RectF pooled) {
        float screenW = g.getResources().getDisplayMetrics().widthPixels;
        if (pooled == null) return screenW / 2f - g.getLeft();
        float inkCentre = g.getLeft() + pooled.centerX();
        boolean centred = Math.abs(inkCentre - screenW / 2f) < screenW * 0.05f;
        return centred ? screenW / 2f - g.getLeft() : pooled.left;
    }

    /**
     * Pulls the album art off the active media session. SystemUI holds MEDIA_CONTENT_CONTROL so
     * getActiveSessions works here, and the session bitmap is full resolution - unlike the
     * media card thumbnail we fall back to.
     */
    /**
     * Several apps can hold an active session at once - here Apple Music and Salt Player both
     * did - and getActiveSessions order is not the order SystemUI's media card uses, so taking
     * the first one with art showed a cover for a track that was not the one on screen. Rank
     * the way the card does: whatever is actually playing, then whoever reported progress last.
     */
    static MediaController pickController(Context ctx) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> cs = msm.getActiveSessions(null);
            MediaController best = null;
            long bestScore = Long.MIN_VALUE;
            for (MediaController c : cs) {
                PlaybackState ps = c.getPlaybackState();
                if (ps == null) continue;
                boolean playing = ps.getState() == PlaybackState.STATE_PLAYING;
                long score = ps.getLastPositionUpdateTime();
                if (playing) score += (1L << 50);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best != null) {
                Xp.log(TAG + "media session -> " + best.getPackageName()
                        + " (of " + cs.size() + ")");
            }
            return best;
        } catch (Throwable t) {
            Xp.w(TAG + "getActiveSessions failed: " + t);
            return null;
        }
    }

    static float easeRaw() {
        return ClockRoom.sRoomRaw;
    }

    /** Prefer the pixels visible in the card; some OEM drawables expose no BitmapDrawable. */
    static Bitmap coverMorphSource() {
        // The square's own art first: prepared off the main thread already, and the very pixels
        // the morph lands on. The session fallback below is a binder call carrying the bitmap,
        // made on the main thread at the moment the animation has to start.
        // Only while the cover is up: outside it, a track may have changed since the square last
        // had art, and it would fly the previous album.
        Bitmap own = sCoverMode ? CoverCardLayer.currentArt() : null;
        // A copy, not the square's own: a tap back in mid-flight pushes the art again, and the
        // square recycles its previous bitmap 180ms into that crossfade - the one the copy was
        // flying, which then drew nothing until the morph had landed (~450ms of no cover).
        // 512x512, about a millisecond to copy.
        if (own != null) {
            try {
                Bitmap mine = own.copy(own.getConfig(), false);
                if (mine != null) return mine;
            } catch (Throwable ignored) {
            }
        }
        Bitmap thumb = AlbumArt.cardThumbnail();
        return thumb != null ? thumb : (sAppCtx == null ? null : AlbumArt.albumArt(sAppCtx, false));
    }

    static float outlineRadius(View v) {
        try {
            android.graphics.Outline o = new android.graphics.Outline();
            v.getOutlineProvider().getOutline(v, o);
            float r = o.getRadius();
            if (r >= 0f) return r;
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    /**
     * Slack around the glyph box before cropping. getTextBoundsWithPosition() reports the text's
     * own bounds, but the digits are STROKED - in glass mode they are nothing but stroke - so
     * half a stroke width falls outside it and the crop shaved the last digit.
     */
    static final float CLOCK_PAD = 10f;

    /** A hand-back of the live wallpaper's surfaces that is waiting for a lock screen. */
    static volatile boolean sVideoWpOwed;

    /** PROBE `uncover`: our video cover view hidden, so a screenshot shows what is under it. */
    static volatile boolean sUncoverProbe;

    /** A view's current picture as an average colour, for the vcprobe op. */
    static String avgColour(View v) {
        if (v == null) return "absent";
        try {
            Bitmap b;
            if (v instanceof android.view.TextureView) {
                b = ((android.view.TextureView) v).getBitmap(60, 130);
            } else {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return "unlaid";
                b = Bitmap.createBitmap(60, 130, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(b);
                cv.scale(60f / v.getWidth(), 130f / v.getHeight());
                v.draw(cv);
            }
            if (b == null) return "no bitmap";
            long r = 0, g = 0, bl = 0;
            int n = b.getWidth() * b.getHeight();
            int[] px = new int[n];
            b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
            for (int p : px) {
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
            }
            b.recycle();
            return String.format(java.util.Locale.ROOT, "#%02x%02x%02x(%s)",
                    r / n, g / n, bl / n, v.getVisibility() == View.VISIBLE ? "shown" : "hidden");
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    /** The AOD wallpaper-dim value the system last set, and the root it was set on. */
    private static volatile float sLastWallpaperBlack = -1f;
    private static volatile Object sBlackRoot;

    /**
     * How long the blur-sync pump keeps the keyguard redrawing after a reload signal.
     *
     * Long enough to cover the slowest measured swap: the wallpaper process rebuilds its player
     * and the first frame lands somewhere between 60ms (a cover video off the encode cache) and
     * ~700ms (restoring the user's own 4K wallpaper video, re-seeked) after the reload. Past
     * that, a swap that arrives late simply does not get re-sampled by the cards' blur.
     */
    private static final long BLUR_SYNC_MS = 1400L;

    private static volatile long sBlurSyncUntil;
    private static volatile boolean sBlurSyncPumping;

    /**
     * Keeps the keyguard window redrawing, one frame at a time, until the wallpaper window's
     * swap has certainly landed.
     *
     * The cards' frosted backdrop is not a live view of the wallpaper: it re-samples what is
     * behind the cards only when this window draws a frame. Every cover transition ends with
     * this window going quiet BEFORE the wallpaper side has finished - the cover's view has
     * faded out and detached, or the fade-in is over, and the player's first frame is still
     * being decoded - so the last backdrop sample, taken while the view was still up, is what
     * stays on the cards while the wallpaper window underneath swaps. The eye sees the new
     * wallpaper; the cards keep the old blur. That is the whole of the "half a beat late"
     * report, and no View animation on our side can fix it, because the missing ingredient is
     * not motion - it is a frame for the blur to re-sample in.
     *
     * So the pump draws those frames on purpose. One invalidate a vsync, from the signal that
     * says the player was rebuilt until well past the slowest first frame. The cost is a
     * redraw a frame for about a second, which is the same cost the still path's crossfade
     * already pays; the alternative is the cards disagreeing with the wallpaper they sit on.
     */
    private static final android.view.Choreographer.FrameCallback sBlurSyncFrame =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) { android.os.Trace.beginSection("MC blurSync"); try {
                    sBlurSyncPumping = false;
                    if (android.os.SystemClock.uptimeMillis() >= sBlurSyncUntil) return;
                    View root = sContainer == null ? null : sContainer.getRootView();
                    if (root != null) root.invalidate();
                    sBlurSyncPumping = true;
                    Gen.frame(this);
                } finally { android.os.Trace.endSection(); } }
            };

    static void startBlurSync(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean fresh = now >= sBlurSyncUntil;
        sBlurSyncUntil = now + BLUR_SYNC_MS;
        if (sBlurSyncPumping) return;
        sBlurSyncPumping = true;
        Gen.frame(sBlurSyncFrame);
        // One line per burst, not per frame: the pump runs for over a thousand frames.
        Xp.log(TAG + "blur sync pumping " + BLUR_SYNC_MS + "ms (" + why + ")"
                + (fresh ? "" : " (extended)"));
    }

    /**
     * Re-applies the AOD wallpaper dim after a cover rebuild. The rebuild recreates the
     * wallpaper-side render pipeline, and the dim the system had animated onto the wallpaper
     * window - through setWallpaperBlack and the WallPaperAnimProcess transaction - is state
     * that pipeline does not carry across. Left alone, the cover sits in AOD at full
     * brightness where the wallpaper would have been dimmed. Three re-asserts spread over
     * 600ms: the new GL program can land after the first of them.
     */
    static void reassertAodDim() {
        final float black = sLastWallpaperBlack;
        final Object root = sBlackRoot;
        if (black < 0f) return;
        for (final long at : new long[]{50L, 250L, 600L}) {
            main().postDelayed(() -> {
                if (!sCoverMode) return;
                try {
                    if (root != null) {
                        Xp.callMethod(root, "setWallpaperBlack", black);
                    }
                    android.view.SurfaceControl.Transaction t =
                            new android.view.SurfaceControl.Transaction();
                    try {
                        Xp.callMethod(t, "enableWallPaperAnim", true);
                        Xp.callMethod(t, "setWallPaperAnimProcess", black);
                    } catch (Throwable ignored) {
                    }
                    t.apply();
                    Xp.log(TAG + "aod dim re-asserted " + black + " (+" + at + "ms)");
                } catch (Throwable t2) {
                    Xp.w(TAG + "aod dim re-assert failed: " + t2);
                }
            }, at);
        }
    }

    static String resourceName(View v) {
        try {
            int id = v.getId();
            if (id <= 0 || id == View.NO_ID) return "-";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * The right bias can only be found by looking at the phone - the sharp band has to clear the
     * media card without stranding the artwork under the clock - so it is a live knob, and a
     * change re-composes what is already on screen instead of waiting for the next track.
     */
    static void setBias(float v) {
        sBias = clamp01(v);
        ModuleState.saveState();
        Xp.log(TAG + "cover bias = " + sBias);
        if (sCoverMode) CoverPush.pushArtAsync(true, false);
    }

    /**
     * The wallpaper is composed on a worker thread, where reading the live view is not safe, so
     * the size is taken once from the keyguard's own resources. DisplayMetrics rather than
     * getWidth(): this runs at attach time, before the container has been laid out.
     */
    private static void captureScreenSize(View v) {
        try {
            android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                sScreenW = dm.widthPixels;
                sScreenH = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
    }

    /** The one worker the composing runs on, so two track changes cannot compose at once. */
    static synchronized Handler worker() {
        if (sWork == null) {
            // Default priority put the composing on a little core and made it swing between
            // 48ms and 196ms for the same picture. This work is on the critical path of a track
            // change, so ask for display priority rather than background.
            HandlerThread t = Gen.handlerThread("mc-art",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            // Gen's, like main(): a push still queued when this generation retires is dropped.
            sWork = Gen.liveHandler(t.getLooper());
        }
        return sWork;
    }

    public static Handler main() {
        // Gen's: nothing posted here is delivered once this generation has retired (a lookup
        // thread still finishing then would otherwise run us against the next one's host).
        if (sMain == null) sMain = Gen.liveHandler();
        return sMain;
    }

    /**
     * When the card last named a new track, how long the wallpaper check took, and how many
     * attempts the artwork needed.
     *
     * The whole point of a track change is how long it takes, and until now nobody could say
     * where the time went - the module's own log does not reach logcat, so a report of "the cover
     * is slow" had nothing behind it. These ride along with the push (see pushArtToWallpaper) so
     * the wallpaper process, which holds the other half of the timeline, can print all of it at
     * once. Both processes read the same uptimeMillis clock, so the two halves simply subtract.
     */
    static volatile long sCtTrack;
    static volatile long sCtArt;
    static volatile long sCtCheckMs;
    static volatile int sCtTries;

    /**
     * The first track change of a BURST, and how many were swallowed by it.
     *
     * Pressing next twice in a row throws the first push away (see sPushGen), so the timing of a
     * burst reported per-push is the timing of its LAST one - by which point the player has
     * settled and the artwork is there for the asking. That reads as 60ms while the screen sat on
     * the old cover for the better part of a second, which is what someone pressing next
     * repeatedly actually sees. Measured from here instead: the first press of the burst to the
     * cover that finally arrives.
     */
    static volatile long sCtBurst;
    static volatile int sCtSkips;

    /**
     * Which push is the current one. The retries span a couple of seconds, so the card can be
     * dismissed - or the track changed again - while they are still running; without this, a
     * retry that finally found artwork would put the cover back after cover mode had ended.
     */
    static volatile int sPushGen;

    /**
     * Whether the wallpaper process composes covers itself, as said by that process.
     *
     * Asked rather than assumed, because the two processes run the module's code from whenever
     * each last started. After an update that restarted SystemUI but not the wallpaper, the
     * wallpaper still runs the old build, which understands only the composed JPEG - a source
     * sent to it would leave the lock screen without a cover. So the source goes over only once
     * the process on the other end has said it knows what to do with one.
     */
    static volatile boolean sWpComposes;

    /** The one kind of wallpaper this module can work with: a still picture. */
    static final String KIND_IMAGE = "image";

    /**
     * The kinds MiWallpaper draws with an engine other than its image one - the list its own
     * WallpaperTypeUtils.getEngineService sends elsewhere. Every other kind it hands to
     * KeyguardImageEngineImpl, the texture this module swaps: `gallery` (锁屏画报 and the like),
     * `default`, `dark` are still pictures there. Read as live, they put the cover in the keyguard
     * layer over a wallpaper window still showing the picture: the clock's glass refracted the
     * old wallpaper under the cover, and leaving cover mode waited out the live wallpaper's
     * reload before the picture came back (#84, a carousel wallpaper).
     */
    private static final java.util.Set<String> LIVE_KINDS = new java.util.HashSet<>(
            java.util.Arrays.asList("video", "sensor", "super_wallpaper", "video_gallery",
                    "linkage_video", "loop_video", "maml"));

    /** Whether a slot of [kind] is drawn by something other than the image engine. */
    static boolean liveKind(String kind) {
        return kind != null && LIVE_KINDS.contains(kind);
    }

    /**
     * One switch for the whole look. The clock collapse is not a separate thing the user turns
     * on - if the album cover is the wallpaper, the clock is small.
     */
    static void enterCoverMode(boolean animate) {
        sCoverMode = true;
        // The clock's palette follows the picture behind it (PaletteThrottle.retriggerSoon).
        PaletteThrottle.INSTANCE.retriggerSoon(CoverPush.PALETTE_AFTER_PUSH_MS);
        // The cover is the media card's alone: the focus notifications stay out of the stack.
        LockIslands.INSTANCE.setCoverMode(true);
        MiniPlayerRuntime.refresh();
        CoverCardLayer.entering();
        Diagnostics.armTransitionTrace("entering cover mode");
        // Whatever the user decided about the last song does not carry into this one.
        sTapSuppressed = false;
        // The two-finger tap's page is NOT reset here: it stands until the next two-finger tap,
        // across songs, lock screens and a card that comes and goes. See LockLyrics.sTapHidden.
        // Start the card where the OEM has it when animating, so the thumbnail fades out across
        // the clock's own frames instead of blinking away before the clock has begun to move.
        // Without an animation there is nothing to fade, and the settled look goes on directly.
        MediaCard.sCardP = animate ? 0f : 1f;
        if (sCoverCardStyle.mode == CoverCardStyle.CARD) {
            CoverCardLayer.attach(CoverPush.coverLayer());
            CoverCardLayer.style(sCoverCardStyle);
            CoverCardLayer.playback(sCoverCardPlaying);
        }
        // The lyrics exception is decided here, before anything has moved. With the lyrics up
        // the thumbnail the setting hides never leaves, so the morph has nothing to fade it back
        // from; sprung from wherever it was, it would dip out and return over the entry, which is
        // the same blink with the direction reversed. wantsAttached() rather than wantsShown()
        // because nothing is on screen yet - it answers "will the lyrics be up", which is the
        // question this state is about.
        CardArt.sLyricUp = sMcArtInLyrics && LockLyrics.wantsAttached();
        CardArt.sLyricArtP = CardArt.sLyricArtTo = CardArt.sLyricUp ? 1f : 0f;
        CardArt.sLyricArtV = 0f;
        CardArt.sLyricArtAt = 0L;
        MediaCard.applyMediaCard();
        // The response is the slider's, read when the transition starts and nowhere else, which
        // is what makes a change land on the next transition and never mid-flight. The cut-out
        // goes with it; both are LockHold's, shared with the immersive pages.
        // With 保留壁纸 the clock is the lyric page's rather than cover mode's: off it the lock
        // screen keeps the OEM's big clock and the cut-out. See syncCoverHold().
        syncCoverHold(animate);
        // An immersive page closing in the same tap fades out onto this cover, not ahead of it.
        ImmersiveHost.coverEntering();
        // The reading may predate this cover - the card can come up on art that was pushed
        // before the user ever locked the phone - and the OEM will not re-colour on its own.
        AodColour.recolorClock();
        // The wallpaper process cannot remember this one across a restart, and this is the
        // first moment of a transition it matters for. See pushFadeMs().
        CoverPush.pushFadeMs(sAppCtx);
        LockLyrics.attach();
        CoverCardLayer.refresh();
        ModuleState.saveState();
    }

    /**
     * Leaves cover mode, optionally on the way out rather than all at once.
     *
     * Animated, this is ClockCollapse's spring, and the clock, the glass morph and the card's
     * thumbnail all ride its one progress - what Apple's is, everything at once. Unanimated -
     * the display is off, or nothing was taken over - it is handed back in one frame.
     */
    private static void exitCoverMode(boolean animate) {
        CoverCardLayer.leaving();
        sCoverMode = false;
        PaletteThrottle.INSTANCE.retriggerSoon(CoverPush.PALETTE_AFTER_PUSH_MS);
        ClockGlass.applyGlassBrightness();
        LockIslands.INSTANCE.setCoverMode(false);
        CoverCardLayer.refresh();
        // The cover is on its way out, so the reading that coloured the clock describes the
        // wallpaper coming back even less than it described the old one. Dropped at the start:
        // the clock is at its smallest now, so the colour going back to the OEM's is at its
        // least visible.
        if (sCoverTint != 0) {
            sCoverTint = 0;
            AodColour.recolorClock();
        }
        Diagnostics.armTransitionTrace("leaving cover mode");
        // The clock and the cut-out go back unless an immersive page still has the lock screen
        // (LockHold); with one, only the card is the cover's to hand back, and it goes now -
        // there is no exit for its thumbnail to ride.
        // A page that yielded to the cover, still open, comes back - first, so it takes the lock
        // screen while the cover still has it, and the clock and the cut-out never see a moment
        // with no owner.
        ImmersiveHost.coverLeft();
        sCoverHeld = false;
        LockHold.give(LockHold.Owner.COVER, animate);
        if (!animate) KeepDim.apply(0f);
        if (LockHold.clockHeld()) {
            MediaCard.sCardP = 0f;
            MediaCard.applyMediaCard();
        }
        MiniPlayerRuntime.refresh();
        // No applyMediaCard() while the exit is flying: it would drop the card's guard, and the
        // guard is what draws every frame of the thumbnail coming back. onClockReleased() hands
        // the card back when the clock lands.
        if (!ClockCollapse.active()) onClockReleased();
        // The lyrics fade out on their own and leave the keyguard once they have.
        LockLyrics.refresh();
        ModuleState.saveState();
    }

    /** Whether cover mode has the lock screen's clock and cut-out right now. See syncCoverHold(). */
    private static boolean sCoverHeld;

    /** 保留壁纸: cover mode leaves the wallpaper alone. See CoverCardStyle.KEEP. */
    static boolean keepsWallpaper() {
        return sCoverCardStyle.mode == CoverCardStyle.KEEP;
    }

    /** The clock is small for the cover, or flying back from it. KeepDim's gate. */
    static boolean coverHoldsClock() {
        return sCoverHeld || LockHold.exitingFor(LockHold.Owner.COVER);
    }

    /**
     * Takes or gives the lock screen's clock and cut-out for cover mode.
     *
     * Normally they are cover mode's for as long as it is on. With 保留壁纸 there is no cover to
     * clear the way for, so they are the lyric page's instead: up, the clock goes small, the
     * cut-out goes and the wallpaper dims; off - a two-finger tap away, or a song with no lyrics -
     * the lock screen is the OEM's own, big clock in front of the subject. Called on entry and
     * whenever the lyric page may have come or gone (LockLyrics.refresh), and idempotent.
     */
    public static void syncCoverHold(boolean animate) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main().post(() -> syncCoverHold(animate));
            return;
        }
        boolean want = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.sceneLayoutAllowed()
                && sCoverMode && (!keepsWallpaper() || LockLyrics.wantsAttached());
        if (want == sCoverHeld) return;
        sCoverHeld = want;
        if (want) {
            LockHold.take(LockHold.Owner.COVER, animate, "cover");
            return;
        }
        LockHold.give(LockHold.Owner.COVER, animate);
        if (!animate) KeepDim.apply(0f);
        // The lyrics fade out with the clock and leave the keyguard once they have.
        LockLyrics.refresh();
    }

    /** ClockCollapse let go of the clock. Outside cover mode, the card goes back with it. */
    static void onClockReleased() {
        LockHold.clockReleased();
        if (sCoverMode) return;
        MediaCard.sCardP = 0f;
        MediaCard.applyMediaCard();
        MiniPlayerRuntime.refresh();
    }

    /**
     * The mini player stays off screen until the cover exit has settled.
     *
     * The cover's exit, not the clock's: an immersive page holds the same clock, and reading its
     * clock as a cover scene took the music out of its island and put its media card up over
     * the focus row the page was opened from (reported 2026-09-29).
     */
    public static boolean coverSceneActive() {
        return sCoverMode || LockHold.exitingFor(LockHold.Owner.COVER);
    }

    public static MediaController miniPlayerSession() {
        return sWatched;
    }

    /** The OEM header owns the media card's background and foreground rim. */
    public static View miniPlayerMediaHeader() {
        View art = MediaCard.sCardArt;
        View current = art != null && art.isAttachedToWindow() ? art : null;
        while (current != null) {
            if (current.getClass().getName().contains("MiuiMediaHeaderView")) return current;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        View card = findLockScreenView("mi_media_controls");
        View root = PreviewShots.cardShotRoot(card);
        return root != card && root != null
                && root.getClass().getName().contains("MiuiMediaHeaderView") ? root : null;
    }

    /** The card's artwork box, title and artist: where the mini player's own pieces land. */
    public static View miniPairArt() { return MediaCard.sCardArt; }
    public static View miniPairTitle() { return MediaCard.sCardTitle; }
    public static View miniPairArtist() { return MediaCard.sCardArtist; }

    /** The card's background view, where its material is set. */
    public static View miniPlayerMediaBg(View header) {
        return headerMediaBg(header);
    }

    /**
     * media_bg under the header's live card. A carousel (KeiMi's) parks other players' cards in
     * the same header with their child ids intact - only the live one keeps mi_media_controls -
     * so a plain walk of the header finds whichever card comes first in it.
     */
    static View headerMediaBg(View header) {
        if (header == null) return null;
        int id = header.getResources().getIdentifier("mi_media_controls", "id", "com.android.systemui");
        View card = id == 0 ? null : header.findViewById(id);
        View bg = card == null ? null : findByName(card, "media_bg");
        return bg != null ? bg : findByName(header, "media_bg");
    }

    /** The corner the card itself draws: media_bg's outline, 24dp on this device. */
    public static float miniPlayerCardRadius(View header) {
        View bg = headerMediaBg(header);
        float r = bg == null ? 0f : outlineRadius(bg);
        return r > 0f ? r : 24f * density();
    }

    /** The same gate as the cover morph: a lock screen that is up, awake and not covered. */
    public static boolean miniPlayerMorphAllowed() {
        return Looper.myLooper() == Looper.getMainLooper() && coverMorphEligible();
    }

    /** Each part of miniPlayerMorphAllowed, for a morph it refused. */
    public static String miniPlayerMorphWhy() {
        View c = sContainer;
        return "main=" + (Looper.myLooper() == Looper.getMainLooper())
                + " clock=" + (c == null ? "null" : c.isAttachedToWindow() + "/" + c.isShown())
                + " screen=" + sScreenOn + " kg=" + keyguardShowing()
                + " bouncer=" + bouncerUp() + " cc=" + controlCenterUp();
    }

    /**
     * The lock screen's own media presentation holds: the card is up, no cover scene, the
     * keyguard up. Awake or dozing, pad or no pad - those only stop the pill taking touches.
     */
    public static boolean miniPlayerPresentable() {
        return MediaCard.sCardShowing && !coverSceneActive() && keyguardShowing();
    }

    /**
     * The media card is there to be the music's card (sCardPresent). A session outlives its
     * card: dismissed, the music still read as usable, the row waited on a card that would
     * never come and took the notifications' islands down with it.
     */
    public static boolean miniPlayerMediaCardPresent() { return MediaCard.sCardPresent; }

    /**
     * The same for a row with no music in it, only notifications: there is no media card to be
     * up, only the lock screen, and no cover scene over it.
     */
    public static boolean miniPlayerIslandsPresentable() {
        // In the cover too: the cover is the music's card, and the other islands stay in the row.
        return keyguardShowing();
    }

    /** A notification row's corner, for a morph onto it: its own outline, else the card's. */
    public static float notificationRowRadius(View row) {
        float r = row == null ? 0f : outlineRadius(row);
        return r > 0f ? r : 24f * density();
    }

    private static long sMiniBouncerCheckedAt;
    private static boolean sMiniBouncerUp;

    public static boolean miniPlayerCanShow() {
        return miniPlayerDisplayEligible() && !miniControlCenterUp();
    }

    /**
     * The same for a row of notification islands with no music in it: no media card to be up.
     * Asked through miniPlayerCanShow, a row with no card never took a touch at all.
     */
    public static boolean miniPlayerIslandsCanShow() {
        return islandsDisplayEligible(true) && !miniControlCenterUp();
    }

    public static boolean miniPlayerControlCenterUp() { return miniControlCenterUp(); }

    private static java.lang.ref.WeakReference<View> sCentreView;
    private static long sCentreLookedAt;

    /**
     * Whether the control centre is up, read off its window view every time it is asked - the
     * view held once found, so a frame costs one isShown(). For a fade that has to follow the
     * centre closing: the 150ms answer miniControlCenterUp keeps for the mini player brought the
     * lyrics back that much late (#52). The lookup itself is the whole-tree search, so a miss is
     * not repeated for 500ms.
     */
    public static boolean controlCenterShown() {
        View v = sCentreView == null ? null : sCentreView.get();
        if (v == null || !v.isAttachedToWindow()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sCentreLookedAt < 500L) return false;
            sCentreLookedAt = now;
            v = null;
            View cc = findSysuiView("control_center_container");
            View content = cc instanceof ViewGroup ? findByName(cc, "content_container") : null;
            if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                v = ((ViewGroup) content).getChildAt(0);
            }
            sCentreView = v == null ? null : new java.lang.ref.WeakReference<>(v);
            if (v == null) return false;
        }
        return v.isShown();
    }

    private static long sMiniCentreCheckedAt;
    private static boolean sMiniCentreUp;

    /**
     * controlCenterUp() looks the container up by name across the whole shade window, and
     * falls back to reading every view's resource name when it is not there. The mini player
     * asked it twice on every frame the lock screen drew - through its drags, its morphs, a
     * track change - which is where their dropped frames went. The same 100-odd ms the pad's
     * answer is kept for.
     */
    private static boolean miniControlCenterUp() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sMiniCentreCheckedAt > 150L) {
            sMiniCentreCheckedAt = now;
            sMiniCentreUp = controlCenterUp();
        }
        return sMiniCentreUp;
    }

    /** A translucent control center keeps the selected mini card visible but blocks its taps. */
    static boolean miniPlayerDisplayEligible() {
        return MediaCard.sCardShowing && islandsDisplayEligible(false);
    }

    private static boolean islandsDisplayEligible(boolean inCover) {
        if (!inCover && coverSceneActive() || !screenOnCached() ||
                !keyguardShowing() || !onKeyguardNow()) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sMiniBouncerCheckedAt > 100L) {
            sMiniBouncerCheckedAt = now;
            sMiniBouncerUp = bouncerUp();
        }
        return !sMiniBouncerUp;
    }

    /** Enter through the same route as the OEM artwork, with MiniPlayerRuntime owning the bridge. */
    public static void miniPlayerEnterCover() {
        if (!miniPlayerCanShow()) return;
        // No cover to open: the island opens into the plain card, as a pull up on it does.
        if (!sCoverFeatureOn) {
            MiniPlayerRuntime.openCardInstead();
            return;
        }
        // Taken before the entry moves anything: the lyrics grow out of the island as it is now.
        LockLyrics.notePopOrigin(MiniPlayerRuntime.musicIslandCentreOnScreen());
        if (!sAuto) {
            // A manual entry owns the same container transition as automatic cover mode.
            // This also adopts an upward swipe still settling when its artwork is tapped.
            if (MiniPlayerRuntime.prepareSceneEntry()) {
                beginMiniMorph(true, !LockLyrics.willAttachOnEntry());
            } else {
                CoverMorphLayer.cancel();
            }
            setCoverEnabled(true, true, false);
        } else {
            enterFromTap("mini player tapped");
        }
    }

    /** An island opened from the cover: the cover goes, its card coming down into the row. */
    public static void miniPlayerLeaveCover() {
        if (!sCoverMode) return;
        // Left the way a swipe down leaves it, and remembered the same way: the exit below holds
        // the cover off (sTapSuppressed) so a track change does not put it back over the page,
        // and without this the swipe up on the pill went to the plain card - the card up, the
        // cover still held off, no picture behind it (#63).
        MiniPlayerRuntime.rememberScene();
        LockLyrics.notePopTarget(MiniPlayerRuntime.musicIslandHomeOnScreen());
        exitFromTap("island opened from the cover");
    }

    /**
     * A finger turned a scene's own morph round: back into the cover or the lyrics while the
     * exit is still running, or out of them while the entry is. Not gated on the scene being
     * off, as a tap on the pill is - it is still settling, and that is the point.
     */
    public static void miniPlayerTurnScene(boolean enter) {
        if (!keyguardShowing() || bouncerUp()) return;
        if (enter) {
            if (sCoverMode || !sCoverFeatureOn) return;
            // Back out of the island mid-morph: the lyrics come out of it too, from where it is.
            LockLyrics.notePopOrigin(MiniPlayerRuntime.musicIslandCentreOnScreen());
            if (!sAuto) setCoverEnabled(true, true, false);
            else enterFromTap("mini player pulled back up");
        } else {
            if (!sCoverMode) return;
            LockLyrics.notePopTarget(MiniPlayerRuntime.musicIslandHomeOnScreen());
            exitFromTap("mini player pulled back down");
        }
    }

    /** The wake reached the clock before the SCREEN_ON broadcast did. */
    static void noteAwake() {
        if (sScreenOn) return;
        sScreenOn = true;
        sAodGrey = Float.NaN;
        ClockGlass.applyGlassBrightness();
        forgetSysReads();
        AodColour.recolorClock();
    }

    static boolean coverCardVisible() {
        View c = sContainer;
        // The PIN pad is not a reason to hide it: taken away there, the square cut out as the pad
        // came up and popped back as it went. It stays and blurs under the pad instead, the way
        // the lyrics do - see CoverCardLayer.followBouncer.
        //
        // Gone with the lock screen's exit, not with the keyguard reading unlocked: the square is
        // in the background layer, which the OEM's exit animation does not take, and the reading
        // is cached for 150ms and still says locked early in that animation - so it outstayed the
        // clock and the media card by anything up to ~0.2s, depending on where the cache stood
        // (user, 2026-10-20). The leaving hooks are the moment the lyrics are cut on too.
        return (sCoverMode || LockHold.exitingFor(LockHold.Owner.COVER)
                        && ClockCollapse.phase() == ClockCollapse.Phase.EXIT)
                && keyguardShowing() && !LockLyrics.lockScreenGone() && c != null && c.isShown()
                && (sScreenOn || coverCardInAod() || coverCardFallingAsleep());
    }

    /**
     * The clock growing into the doze's big clock. The screen is already off, and without this
     * the layer was taken GONE on the first frame of the fall, before the square could go under
     * the growing digits - CoverCardLayer ties it to them.
     */
    private static boolean coverCardFallingAsleep() {
        return sCoverMode && ClockCollapse.phase() == ClockCollapse.Phase.EXIT
                && !ClockCollapse.exiting() && !ClockCollapse.aodHeld();
    }

    /**
     * The square stays through a doze that keeps the cover's small clock, and only that one.
     * Under the OEM's big AOD clock there is no room between it and the media card, and the
     * square sat across the digits. It used to be a setting, off by default; with the big clock
     * taken care of there was nothing left for the setting to protect.
     */
    static boolean coverCardInAod() {
        return sCoverMode && !sScreenOn && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                && ClockCollapse.aodHeld();
    }

    /** The full-screen AOD may show a faint static colour wash even with the square hidden. */
    static boolean coverCardBackdropInAod() {
        View c = sContainer;
        return sCoverMode && sCoverCardStyle.mode == CoverCardStyle.CARD && !sScreenOn
                && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                && ClockCollapse.aodFullScreen() && keyguardShowing()
                && !LockLyrics.lockScreenGone() && c != null && c.isShown();
    }

    static int screenWidth() {
        return sScreenW;
    }

    static float coverCardMediaTop() {
        if (coverCardInAod()) return sScreenH * 0.70f;
        return liveMediaTop();
    }

    /**
     * The media card's top on screen as it is drawn right now, the AOD's compact card included;
     * NaN when it is not up. coverCardMediaTop() answers a stand-in in the AOD instead - for the
     * callers that must not follow the card through the doze's own transition.
     */
    public static float liveMediaTop() {
        View card = MediaCard.sCardGuarded != null ? MediaCard.sCardGuarded : LockLyrics.card();
        if (card != null && card.isShown() && card.getHeight() > 0) {
            int[] xy = new int[2];
            card.getLocationOnScreen(xy);
            // Where the lock islands hold the card while the stack catches up, not where the
            // stack has it this frame: the cover's artwork is laid out above it.
            float top = xy[1] + MiniPlayerRuntime.cardHeldDy(card);
            if (top > sScreenH / 3 && top <= sScreenH) return top;
        }
        // The OEM card may not have been laid out after wake. An old measurement belongs to
        // another lock session, so wait for this one's measured boundary.
        return Float.NaN;
    }

    /** Geometry is sampled from the OEM's live thumbnail, not its transformed screen origin. */
    static CoverMorphMotion.Box coverMorphThumbnail() {
        View art = MediaCard.sCardArt;
        if (art == null || !art.isAttachedToWindow() || art.getWidth() <= 0
                || art.getHeight() <= 0 || !(art.getParent() instanceof View)) return null;
        int[] xy = new int[2];
        ((View) art.getParent()).getLocationOnScreen(xy);
        return new CoverMorphMotion.Box(xy[0] + art.getLeft(), xy[1] + art.getTop(),
                art.getWidth(), art.getHeight());
    }

    /** Reads the clipping that actually rounds the OEM artwork instead of assuming one radius. */
    public static float coverMorphThumbnailRadius() {
        View art = MediaCard.sCardArt;
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return 14f * density();
        }
        float radius = art.getClipToOutline() ? outlineRadius(art) : 0f;
        if (radius <= 0f && art.getParent() instanceof View) {
            View box = (View) art.getParent();
            if (box.getClipToOutline()) radius = outlineRadius(box);
        }
        if (radius > 0f) return radius;
        return Math.min(14f * density(), Math.min(art.getWidth(), art.getHeight()) * 0.20f);
    }

    static ViewGroup coverMorphRoot() {
        View root = sContainer == null ? null : sContainer.getRootView();
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    static boolean coverMorphEligible() {
        return coverMorphStillEligible() && !bouncerUp() && !controlCenterUp();
    }

    /**
     * The per-frame guard for an island's morph ([v] the view it moves): the lock screen [v] is
     * drawn in is still up, the screen on. Not the clock container's: the stack's list presses
     * the clock down, and every morph of a switch was cancelled in the same frame, 50ms in, the
     * stopwatch's card left half way up (2026-09-26).
     */
    public static boolean islandMorphStillEligible(View v) {
        if (v == null || !v.isAttachedToWindow() || !sScreenOn || !keyguardShowing()) return false;
        android.view.ViewParent p = v.getParent();
        return p instanceof View && ((View) p).isShown();
    }

    /** For `op mini`: each part of both guards, when a morph was cut short by one. */
    public static String morphGateWhy(View v) {
        View c = sContainer;
        android.view.ViewParent p = v == null ? null : v.getParent();
        return "clock[" + (c == null ? "null" : "att=" + c.isAttachedToWindow() + " shown=" + c.isShown()
                + " vis=" + c.getVisibility() + " id=" + Integer.toHexString(System.identityHashCode(c)))
                + "] screen=" + sScreenOn + " kg=" + keyguardShowing()
                + " host=" + (p instanceof View ? ((View) p).isShown() : "none");
    }

    /** Cheap per-frame guard; the expensive overlay lookups are only needed at gesture start. */
    static boolean coverMorphStillEligible() {
        View c = sContainer;
        // The leaving hooks as well as the keyguard reading, for the reason coverCardVisible gives:
        // unlocked mid-flight, the copy outstayed the square by ~0.2s, until ACTION_USER_PRESENT.
        return c != null && c.isAttachedToWindow() && c.isShown() && sScreenOn
                && keyguardShowing() && !LockLyrics.lockScreenGone();
    }

    static boolean coverMorphCardMode() {
        return sCoverCardStyle.mode == CoverCardStyle.CARD;
    }

    /** A manual entry must keep its moving artwork until the asynchronous backdrop can take it. */
    static boolean coverMorphHandoffReady(boolean cardMode) {
        long artAt = sCtArt;
        if (artAt == 0L) return false;
        long afterArt = cardMode ? 120L : fadeMsFor(sClockResponse) + 140L;
        return android.os.SystemClock.uptimeMillis() - artAt >= afterArt;
    }

    /** The full-screen destination is the sharp band in CoverCompose.composeWallpaper(). */
    static CoverMorphMotion.Box coverMorphTarget(Bitmap art) {
        if (art == null || art.isRecycled()) return null;
        if (sCoverCardStyle.mode == CoverCardStyle.FULL) {
            if (sScreenW <= 0 || sScreenH <= 0 || art.getWidth() <= 0) return null;
            float height = art.getHeight() * (sScreenW / (float) art.getWidth());
            float top = (sScreenH - height) * Math.max(0f, Math.min(1f, sBias));
            return new CoverMorphMotion.Box(0f, top, sScreenW, height);
        }
        ViewGroup layer = CoverPush.coverLayer();
        if (layer == null || layer.getWidth() <= 0 || layer.getHeight() <= 0) return null;
        int[] xy = new int[2];
        layer.getLocationOnScreen(xy);
        float mediaTop = coverCardMediaTop();
        if (!Float.isFinite(mediaTop)) {
            View card = findSysuiView("mi_media_controls");
            if (card != null && card.isShown() && card.getHeight() > 0) {
                int[] cardXY = new int[2];
                card.getLocationOnScreen(cardXY);
                mediaTop = cardXY[1];
            }
        }
        CoverCardStyle.Rect r = sCoverCardStyle.place(layer.getWidth(), layer.getHeight(),
                layer.getResources().getDisplayMetrics().density,
                ClockCollapse.contentBottomFor(layer) - xy[1],
                ClockCollapse.unzoomY(layer, mediaTop) - xy[1]);
        return r == null ? null : CoverMorphMotion.cardBox(xy[0] + r.x,
                xy[1] + r.y, r.side, CoverCardLayer.renderedScale(layer),
                CoverCardStyle.aspect(art.getWidth(), art.getHeight()));
    }

    /** Keep the shared media card at its real state while the moving copy owns its pixels. */
    static void refreshMediaCardForMorph() {
        // Called on the morph's frames: the card already held, not a search of the whole tree.
        View card = MediaCard.sCardGuarded;
        if (card == null || !card.isAttachedToWindow()) card = findSysuiView("mi_media_controls");
        if (card != null) MediaCard.assertMediaCard(card);
    }

    /**
     * The date view the clock is placed against. A different one means the clock views were
     * re-inflated - a style change - and everything resolved about the old ones is stale.
     */
    static void noteDateView(View date) {
        if (date == ClockViews.sDateView) return;
        ClockViews.sDateView = date;
        ClockViews.sSigViews = new View[0];
        ClockViews.sClockTargets.clear();
        forgetClockRoots();
        ClockViews.forgetGlyphBox();
    }

    /** A pre-dp height setting is converted the first time there is a box to convert it with. */
    static void convertLegacyHeight(RectF box) {
        if (!Float.isNaN(sClockLegacyK)) heightDpFor(box);
    }

    /** Kotlin's Function3 bridge: invoke(FlowCollector, Object[], Continuation). */
    static java.lang.reflect.Method invoke3(Class<?> c) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterTypes().length == 3) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    static final Object NO_FIELD = new Object();

    static void setDepthHidden(final boolean hide) {
        setDepthHidden(hide, 0);
    }

    /**
     * attempt exists because this runs on a SystemUI that has just started: cover mode is
     * restored the moment the clock container attaches, and the foreground layer that holds the
     * cut-out is not necessarily inflated yet. The old code logged "not found" once and gave up,
     * which left the old wallpaper's subject sitting on top of the album cover until something
     * else happened to call it again.
     */
    static void setDepthHidden(final boolean hide, final int attempt) {
        // Whoever asks, the cut-out stays hidden while something has the lock screen: the video
        // cover's late hand-back (CoverPush.dropVideoCover) cannot know an immersive page is up.
        if (!hide && !LockHold.depthFree()) {
            if (attempt == 0) Xp.log(TAG + "deducted_image_view kept hidden: " + LockHold.describe());
            return;
        }
        sDepthHidden = hide;
        final View v = sContainer;
        if (v == null) {
            retryDepth(hide, attempt, "no clock container");
            return;
        }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                View d = findDeductedImageView();
                if (d == null) {
                    retryDepth(hide, attempt, "deducted_image_view not found");
                    return;
                }
                sDeductedRef = d;
                if (hide) {
                    if (!sDeductedHeld) {
                        sDeductedWanted = d.getVisibility();
                        sDeductedHeld = true;
                    }
                    setDeductedVisibility(d, View.INVISIBLE);
                } else if (sDeductedHeld) {
                    sDeductedHeld = false;
                    setDeductedVisibility(d, sDeductedWanted >= 0 ? sDeductedWanted : View.VISIBLE);
                }
                // On a live wallpaper the cut-out subject is a TextureView in the same layer
                // rather than this ImageView, and it is in front of the clock just the same.
                // Resolved every time rather than cached: it is added and removed by MIUI as
                // the wallpaper type changes.
                View fg = CoverPush.videoSurfaceView("keyguard_foreground_layer");
                sVideoFg = fg;
                // And the video itself, in the background layer. The cover is drawn over it
                // either way, but leaving it playing under an opaque view decodes a video
                // nobody can see - and if anything of it is still reaching the screen, this
                // is what says so.
                View bg = sVideoWallpaper ? CoverPush.videoSurfaceView("keyguard_background_layer") : null;
                sVideoBg = bg;
                CoverPush.setVideoSurfacesHidden(hide, bg, fg);
                if (hide) guardDepth(d); else releaseDepthGuard();
                Xp.log(TAG + "deducted_image_view " + (hide ? "hidden" : "shown")
                        + (fg == null ? "" : " (+ the live wallpaper's cut-out)"));
                ModuleState.saveState();
                // VideoDepthSurfaceHolder adds its TextureViews after SystemUI has started, so
                // on a live wallpaper the first pass through here can be too early - measured:
                // deducted_image_view was already there and the TextureView was not. Same
                // retry the cut-out itself gets, for the same reason.
                // Only while the keyguard is actually up. Off the lock screen the TextureViews
                // are legitimately absent, and retrying then just burns 12 passes and 12 log
                // lines every time anything asks for a re-hide.
                if (hide && sVideoWallpaper && fg == null && onKeyguardNow()) {
                    retryDepth(true, attempt, "the live wallpaper's cut-out is not in the tree yet");
                }
            }
        });
    }

    private static final int DEPTH_RETRIES = 12;
    private static final long DEPTH_RETRY_MS = 250L;

    private static void retryDepth(final boolean hide, final int attempt, String why) {
        if (attempt >= DEPTH_RETRIES) {
            Xp.log(TAG + why + ", giving up after " + attempt + " tries");
            return;
        }
        if (attempt == 0) Xp.log(TAG + why + ", retrying");
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                // Only still wanted if nothing has changed its mind in the meantime.
                if (sDepthHidden == hide) setDepthHidden(hide, attempt + 1);
            }
        }, DEPTH_RETRY_MS);
    }

    /**
     * Keeps the cut-out hidden for as long as cover mode wants it hidden. Installed on the view
     * itself, so a keyguard rebuild takes it away with the view and the next hide installs a
     * fresh one.
     */
    private static void guardDepth(final View d) {
        if (sDepthGuarded == d && sDepthGuard != null) return;
        releaseDepthGuard();
        sDepthGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() { android.os.Trace.beginSection("MC depthGuard"); try {
                if (sDepthHidden && d.getVisibility() == View.VISIBLE) {
                    // VISIBLE -> INVISIBLE only invalidates, it does not request a layout, so
                    // this cannot start a traversal loop.
                    setDeductedVisibility(d, View.INVISIBLE);
                    if (++sDepthTakebacks <= 5 || sDepthTakebacks % 100 == 0) {
                        Xp.log(TAG + "system re-showed the cut-out, re-hidden ("
                                + sDepthTakebacks + ")");
                    }
                }
                // The live wallpaper's own TextureViews are NOT asserted here. guardVideoCover()
                // owns them, because whether they should be hidden depends on the cover being
                // on screen, which is a lock-screen question this guard cannot answer - and two
                // guards writing one property just take turns undoing each other.
                return true;
            } finally { android.os.Trace.endSection(); } }
        };
        Gen.preDraw(d, sDepthGuard);
        sDepthGuarded = d;
        Xp.log(TAG + "depth guard installed");
    }

    static void releaseDepthGuard() {
        View d = sDepthGuarded;
        ViewTreeObserver.OnPreDrawListener g = sDepthGuard;
        sDepthGuarded = null;
        sDepthGuard = null;
        if (d == null || g == null) return;
        try {
            Gen.unpreDraw(d, g);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- media card

    static View findSysuiView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : root.findViewById(i);
        return hit != null ? hit : findByName(root, id);
    }

    /** The screen height the card readings are checked against. */
    public static int screenHeight() {
        return sScreenH;
    }

    /**
     * The lock screen's copy of a view the shade - or a third-party module - also puts up.
     *
     * `mi_media_controls` is declared by three layouts in SystemUI: the media card
     * (miui_media_session) and two media island variants, both rooted at
     * PlayerIslandConstraintLayout. findSysuiView() answers with whichever comes first in the
     * tree, and that is the card only while no island copy is inflated - so a module that turns
     * the island on can take the card away from everything that measures against it.
     *
     * The test for "this is the one the lock screen is showing" is the one sampleCardRect() and
     * onLockTap() already use: the card sits in the notification area, below a clock pinned near
     * the top, and is never up by the status bar. Anything higher is the shade's copy or an
     * island. Falls back to findSysuiView(), so a card that is simply not laid out yet behaves
     * exactly as it did.
     */
    public static View findLockScreenView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : findOnScreenById(root, i);
        return hit != null ? hit : findSysuiView(id);
    }

    /**
     * Whether [v] is a row of the notification stack - where the lock screen's media card lives -
     * and not part of a media island copy. The height test alone lost the card itself: the
     * notifications an island row gives back to the stack while the cover or lyrics are up push
     * the card above a third of the screen, and the lyrics then stood on the last card rectangle
     * on record, drawn over the card and the rows under it (#78/#82, 2026-10-13).
     */
    public static boolean inNotificationStack(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            String name = p.getClass().getName();
            if (name.contains("PlayerIsland")) return false;
            if (name.endsWith("NotificationStackScrollLayout")) return true;
        }
        return false;
    }

    /** The first view carrying this id that is really up on the lock screen, in tree order. */
    private static View findOnScreenById(View v, int id) {
        // Pruned on the way down: isShown() is false for everything under a hidden parent, so
        // descending into one can only ever return null.
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.getId() == id && v.isShown() && v.isAttachedToWindow() && v.getHeight() > 0) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (loc[1] >= sScreenH / 3 || inNotificationStack(v)) return v;
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findOnScreenById(g.getChildAt(i), id);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Finds a view by its resource entry name, walking the tree instead of resolving an id.
     *
     * getIdentifier() only searches the one package it is given, and not everything on the
     * keyguard comes from com.android.systemui's own resources: the two shortcut buttons at the
     * bottom came back "not found" by id while a dump of the very same tree listed them by name.
     * The name is read off each view's own Resources, which is exactly why the dump can see them.
     */
    static View findByName(View v, String name) {
        try {
            if (v.getId() != View.NO_ID
                    && name.equals(v.getResources().getResourceEntryName(v.getId()))) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findByName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * `op keepawake`: the lock screen kept lit through a test run, by the mechanism the lyrics'
     * "屏幕常亮" uses (LockLyrics.holdScreen): keepScreenOn on a view in the shade window, whose
     * FLAG_KEEP_SCREEN_ON the window manager holds a screen wake lock for - which outranks the
     * 10s userActivityTimeout the shade window sets on the lock screen. Let go on screen off
     * and after KEEP_AWAKE_MS whatever happens, so a forgotten one cannot keep the phone lit.
     */
    private static View sKeepAwakeView;
    private static final long KEEP_AWAKE_MS = 15 * 60 * 1000L;
    private static final Runnable KEEP_AWAKE_OFF = new Runnable() {
        @Override public void run() { keepAwake(false); }
    };

    static String keepAwake(boolean on) {
        View old = sKeepAwakeView;
        if (old != null) {
            old.setKeepScreenOn(false);
            sKeepAwakeView = null;
        }
        main().removeCallbacks(KEEP_AWAKE_OFF);
        if (!on) return "keepawake=off";
        View c = sContainer;
        if (c == null || !c.isAttachedToWindow()) return "keepawake=no view";
        // Asked for in a doze, it would light the screen at full brightness.
        if (!sScreenOn) return "keepawake=screen off";
        c.setKeepScreenOn(true);
        sKeepAwakeView = c;
        main().postDelayed(KEEP_AWAKE_OFF, KEEP_AWAKE_MS);
        return "keepawake=on (" + c.getClass().getSimpleName() + "), off on screen off or in 15 min";
    }

    /**
     * A touch on the lit lock screen puts the sleep off, as a touch anywhere else does. The shade
     * window turns input into user activity off (INPUT_FEATURE_DISABLE_USER_ACTIVITY) and sets its
     * own 10s userActivityTimeout, so on the lock screen the screen went to the AOD 10s after it
     * woke however much it was being touched; the user asked for it to stay lit while in use
     * (2026-09-25). The real touch is reported - PowerManager.userActivity, which SystemUI holds
     * DEVICE_POWER for - and the ordinary timeout, dimming first, runs from the last one. At most
     * once a second: a drag is one binder call, not one a frame. Never in a doze, where a touch
     * is not a wake.
     */
    private static long sLastUserActivity;
    private static java.lang.reflect.Method sUserActivityMethod;

    static void touchKeepsAwake() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastUserActivity < USER_ACTIVITY_EVERY_MS) return;
        if (!sScreenOn || !keyguardShowing()) return;
        sLastUserActivity = now;
        try {
            PowerManager pm = sAppCtx.getSystemService(PowerManager.class);
            if (sUserActivityMethod == null) {
                sUserActivityMethod = PowerManager.class.getMethod("userActivity",
                        long.class, int.class, int.class);
            }
            // PowerManager.USER_ACTIVITY_EVENT_TOUCH
            sUserActivityMethod.invoke(pm, now, 2, 0);
        } catch (Throwable t) {
            Xp.log(TAG + "user activity: " + t);
        }
    }

    private static final long USER_ACTIVITY_EVERY_MS = 1000L;

    /** Cover-entry effects switched off for a GPU measurement (`op perfoff`); empty in use. */
    static volatile String sPerfOff = "";

    static boolean perfOff(String effect) {
        String off = sPerfOff;
        return !off.isEmpty() && off.contains(effect);
    }

    /**
     * Whether the card's artwork is currently a toggle for cover mode.
     *
     * Both directions: the thumbnail is what put the cover up, so it is also the natural thing
     * to tap to put the wallpaper back. With the restyle's hide-artwork option on, the way back
     * falls out on its own - the thumbnail is INVISIBLE in cover mode by the time anyone could
     * tap it, and the rectangle test asks isShown(). Off the lock screen the card belongs to the
     * shade, where the OEM's own click is the right one.
     */
    public static void refreshLyricButton() {
        MediaCard.applyMediaCard();
    }

    static boolean wantsArtTap() {
        return sTapToggle && MediaCard.sCardShowing;
    }

    /**
     * Makes the card's artwork toggle cover mode instead of opening the player.
     *
     * This has to happen at the window's dispatch, not on album_art itself. A touch listener on
     * the child was the obvious answer and does not work: on device it never fired once, so
     * something above the card claims the gesture before album_art is ever offered it. The
     * shade window's dispatchTouchEvent is upstream of all of that - it is where our own lock
     * screen taps already arrive - so consuming there is the one place that is certain to win.
     *
     * Consuming means returning true WITHOUT proceeding, for the whole gesture: taking only the
     * DOWN would leave the OEM tracking a stream whose beginning it never saw. Which is also
     * why the rect is re-read from the live view on every DOWN rather than from the sampled
     * card rectangle - the card sits somewhere else with the clock collapsed than without it,
     * and swallowing touches over empty wallpaper would be a bug the user could not explain.
     *
     * The cost is that the thumbnail's 158x158 stops being draggable for as long as it is the
     * thing the tap would act on. That is the trade, and it is why the UP has to have stayed
     * inside and been brief: a drag that began on the artwork does nothing rather than toggling
     * on release.
     */
    private static boolean sPageSwallow;
    private static long sPageDownAt;

    /**
     * The card's page button, taken at the window's dispatch for the reasons swallowArtTap
     * gives: nothing on the card itself is ever offered the gesture first. Acts at UP, inside
     * and brief, so a drag that happens to start on it is a drag that does nothing.
     */
    static boolean swallowPageTap(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            sPageSwallow = screenOn() && keyguardShowing() && onKeyguardNow()
                    && !bouncerUp() && !sGestureOnCentre && !ChargeAnim.sGestureOnCharge
                    && CardPageButton.contains(ev.getRawX(), ev.getRawY());
            if (sPageSwallow) sPageDownAt = android.os.SystemClock.uptimeMillis();
            return sPageSwallow;
        }
        if (!sPageSwallow) return false;
        if (action == MotionEvent.ACTION_UP) {
            sPageSwallow = false;
            long held = android.os.SystemClock.uptimeMillis() - sPageDownAt;
            if (held < ART_TAP_MS && CardPageButton.contains(ev.getRawX(), ev.getRawY())) {
                String why = toggleLyricPage();
                Xp.log(TAG + "page button: " + (why == null ? LockTaps.sTwoWhy : why));
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            sPageSwallow = false;
        }
        return true;
    }

    static boolean swallowArtTap(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // With the cover switched off the artwork is the OEM's again: its tap would only
            // lead to a cover that is not allowed to open.
            boolean onCard = (sCoverFeatureOn || sCoverMode)
                    && (wantsArtTap() || MiniPlayerRuntime.wantsNativeArtworkGesture())
                    && screenOn() && keyguardShowing() && onKeyguardNow()
                    && !bouncerUp() && !sGestureOnCentre && !ChargeAnim.sGestureOnCharge;
            sArtSwallow = onCard && artRectContains(ev.getRawX(), ev.getRawY());
            if (sArtSwallow) {
                sArtDownAt = android.os.SystemClock.uptimeMillis();
            } else if (onCard && sVerbose) {
                // For telling "the rectangle is wrong" from "the state is wrong" without
                // guessing, which is how the mirror above was found.
                Xp.log(TAG + "artwork tap missed at " + ev.getRawX() + "," + ev.getRawY()
                        + " (art " + artRectDesc() + ")");
            }
            return sArtSwallow;
        }
        if (!sArtSwallow) return false;
        if (action == MotionEvent.ACTION_UP) {
            sArtSwallow = false;
            long held = android.os.SystemClock.uptimeMillis() - sArtDownAt;
            if (held < ART_TAP_MS && artRectContains(ev.getRawX(), ev.getRawY())) {
                // The same rectangle is both the way in and the way out, and which one it is
                // is read at UP rather than at DOWN: a cover that came up under the finger
                // during the gesture (the OEM can re-lay the card out at any point) would
                // otherwise send the tap the wrong way.
                if (sCoverMode) exitFromTap("artwork tapped");
                else if (MiniPlayerRuntime.wantsNativeArtworkGesture()) miniPlayerEnterCover();
                else enterFromTap("artwork tapped");
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            sArtSwallow = false;
        }
        return true;
    }

    /** The artwork's rectangle as this code sees it, for the log line above. */
    private static String artRectDesc() {
        View a = MediaCard.sCardArt;
        if (a == null) return "null";
        android.view.ViewParent p = a.getParent();
        if (!(p instanceof View)) return "unparented";
        int[] loc = new int[2];
        ((View) p).getLocationOnScreen(loc);
        return (loc[0] + a.getLeft()) + "," + (loc[1] + a.getTop())
                + " " + a.getWidth() + "x" + a.getHeight() + " shown=" + a.isShown();
    }

    /** A scale that could be the OEM's resting one: unit magnitude, either sign. */
    static boolean resting(float v) {
        return Math.abs(Math.abs(v) - 1f) < 0.02f;
    }

    private static boolean artRectContains(float x, float y) {
        View a = MediaCard.sCardArt;
        if (a == null || !a.isShown() || a.getWidth() <= 0 || a.getHeight() <= 0) return false;
        android.view.ViewParent p = a.getParent();
        if (!(p instanceof View)) return false;
        int[] loc = new int[2];
        ((View) p).getLocationOnScreen(loc);
        float left = loc[0] + a.getLeft(), top = loc[1] + a.getTop();
        // A little slop, because the card is often still sliding when the finger lands. Small
        // enough to stay clear of the title, which starts just to the right of it.
        return x >= left - ART_TAP_SLOP && x < left + a.getWidth() + ART_TAP_SLOP
                && y >= top - ART_TAP_SLOP && y < top + a.getHeight() + ART_TAP_SLOP;
    }

    /**
     * The keyguard is actually in front. The clock container is the test - only the keyguard
     * shows it - and it is what keeps the shade's copy of this same card view out of all this.
     */
    public static boolean onKeyguardNow() {
        View c = sContainer;
        return c != null && c.isShown();
    }

    /**
     * Whether the bouncer - the PIN or pattern pad - is over the lock screen.
     *
     * The clock container is NOT this test, however well it works for the shade: measured with
     * the pad up, the clock stays shown, the card stays up and the keyguard stays locked, so
     * every guard the cover tap had was still true and a tap aimed at a digit toggled the cover
     * instead. The keyguard being locked does not mean the lock screen is what is on top.
     *
     * The pad hangs off its own child of the shade window, and MIUI keeps that child INVISIBLE
     * - not GONE - until the bouncer is summoned, so isShown() is exactly "the pad is up".
     * Falling back to false when the view cannot be found keeps the tap working on a build that
     * renamed it, rather than silently switching the whole feature off.
     */
    static boolean bouncerUp() {
        View b = findSysuiView("keyguard_bouncer_container");
        return b != null && b.isShown();
    }

    private static View sBouncerView;
    private static long sBouncerLookedAt;

    /**
     * bouncerUp() for a caller that asks on every frame: the view is kept rather than looked up
     * through the whole tree each time, and looked up again only once the keyguard is rebuilt -
     * and on a build without it, at most once a second, since the miss walks the whole tree.
     */
    public static boolean bouncerShown() {
        View b = sBouncerView;
        if (b == null || !b.isAttachedToWindow()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sBouncerLookedAt < 1000L) return false;
            sBouncerLookedAt = now;
            b = findSysuiView("keyguard_bouncer_container");
            sBouncerView = b;
        }
        return b != null && b.isShown();
    }

    /**
     * How far up the PIN pad is, 0..1, for the blur the lyrics and the square put on under it.
     *
     * Not bouncerShown(): the container only goes INVISIBLE once the pad's exit has finished,
     * ~300ms after it started, so a blur keyed on it began clearing after the OEM's own blur had
     * already gone. Nor the container's own alpha or its direct child's - they only jump at the
     * two ends. What fades is the security view itself (KeyguardPatternView, the PIN or password
     * one), 1 to 0 over ~270ms from the first frame of the exit, while its header slides down
     * (measured 2026-09-23, `op bouncer` tracing the whole subtree). So the level is the product
     * of the alphas from the container down to it. A build where it cannot be found reads 1
     * until the container hides, which is what bouncerShown() gave.
     */
    public static float bouncerLevel() {
        if (!bouncerShown()) {
            sSecurityView = null;
            sBouncerSince = 0L;
            return 0f;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sBouncerSince == 0L) sBouncerSince = now;
        // The pad's view reads fully opaque for the first frame or two, before the OEM resets it
        // to 0 and fades it in (measured: shown, 1.0, 1.0, then 0 at +15ms and up over ~70ms).
        // Followed as fast as the blur now follows, that was a flash of blur at the start.
        if (now - sBouncerSince < BOUNCER_SETTLE_MS) {
            return 0f;
        }
        View b = sBouncerView;
        View sec = sSecurityView;
        if (sec == null || !sec.isAttachedToWindow() || sec.getVisibility() != View.VISIBLE
                || !ClockViews.isAncestor(b, sec)) {
            sec = findSecurityView(b, 0);
            sSecurityView = sec;
        }
        float level = b.getAlpha();
        if (sec != null) {
            for (View v = sec; v != null && v != b; ) {
                level *= v.getAlpha();
                Object p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
        }
        level = Math.max(0f, Math.min(1f, level));
        return level;
    }

    /** The pad's own view under the container, kept for the length of one showing. */
    private static View sSecurityView;
    /** When the container was first seen shown this time, 0 while it is not. */
    private static long sBouncerSince;
    private static final long BOUNCER_SETTLE_MS = 40L;

    /**
     * The shown security view: KeyguardPatternView, KeyguardPINView, KeyguardPasswordView and
     * the SIM ones all name themselves Keyguard...View, and the frames around them do not end so.
     */
    private static View findSecurityView(View v, int depth) {
        if (v.getVisibility() != View.VISIBLE || depth > 7) return null;
        String n = v.getClass().getSimpleName();
        if (depth > 0 && n.startsWith("Keyguard") && n.endsWith("View")
                && !n.startsWith("KeyguardSecurity") && !n.contains("Message")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findSecurityView(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Whether the control centre is pulled down over the lock screen.
     *
     * The swipe-down centre does not hide the keyguard the way the full settings expansion does -
     * measured, the clock container and the card both stay shown - so every guard the cover tap
     * already has is still true, and a tap aimed at a quick toggle toggles the cover instead.
     * What does flip with the centre is its window view: GONE while it is shut, VISIBLE while it
     * is open, under a container and a content wrapper that are themselves always VISIBLE. That
     * window view has no id of its own, so it is reached as the first child of the content
     * wrapper rather than looked up directly.
     *
     * Falling back to false when the tree cannot be resolved keeps the tap working on a build
     * that renamed these views, rather than silently switching the whole feature off.
     */
    static boolean controlCenterUp() {
        View cc = findSysuiView("control_center_container");
        if (!(cc instanceof ViewGroup)) return false;
        View content = findByName(cc, "content_container");
        if (!(content instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) content;
        return g.getChildCount() > 0 && g.getChildAt(0).isShown();
    }

    // ---- the charging animation's own comings and goings, for `op chargeanim`

    /** The children of a view by class name, a "!" marking the ones that are not shown. */
    static String childNames(View v) {
        if (!(v instanceof ViewGroup)) return "-";
        ViewGroup g = (ViewGroup) v;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (i > 0) sb.append(',');
            sb.append(c.getClass().getSimpleName());
            if (!c.isShown()) sb.append('!');
        }
        return sb.toString();
    }

    /** The card's track when the running morph began; a different one is what cancels it. */
    static String sMorphKey = "";

    private static void beginMorph(boolean toCover) {
        long t0 = System.nanoTime();
        if (!CoverMorphLayer.active()) sMorphKey = MediaCard.sCardKey;
        CoverMorphLayer.begin(toCover);
        sMorphBeginNs += System.nanoTime() - t0;
    }

    /**
     * The mini player's pill is already turning into the card (MiniCardMorph); this is only its
     * artwork. With the cover card at the far end it flies there. With lyrics it lands on the
     * card's own thumbnail, which the container morph already carries - nothing to fly.
     */
    private static void beginMiniMorph(boolean toCover, boolean coverEndpoint) {
        long t0 = System.nanoTime();
        if (!CoverMorphLayer.active()) sMorphKey = MediaCard.sCardKey;
        if (!coverEndpoint || !CoverMorphLayer.beginMiniScene(toCover)) CoverMorphLayer.cancel();
        sMorphBeginNs += System.nanoTime() - t0;
    }

    /**
     * For `op cardstate`: main-thread milliseconds of the last few tap toggles, the whole handler
     * and the morph's own start inside it, newest first - where a hitch at the start comes from.
     */
    private static final StringBuilder sToggleCost = new StringBuilder();
    private static long sMorphBeginNs;

    private static void noteToggleCost(String what, long ns) {
        sToggleCost.insert(0, what + "=" + (ns / 100000L) / 10f + "ms(morph "
                + (sMorphBeginNs / 100000L) / 10f + ") ");
        if (sToggleCost.length() > 400) sToggleCost.setLength(400);
        sMorphBeginNs = 0L;
    }

    static String toggleCost() {
        return sToggleCost.toString();
    }

    static void exitFromTap(String why) {
        long t0 = System.nanoTime();
        exitFromTapNow(why);
        noteToggleCost("out", System.nanoTime() - t0);
    }

    static void enterFromTap(String why) {
        // Before the morph is begun, not only at setCoverEnabled: the morph would otherwise
        // start out of a card that then never turns into anything.
        if (!sCoverFeatureOn) return;
        long t0 = System.nanoTime();
        enterFromTapNow(why);
        noteToggleCost("in", System.nanoTime() - t0);
    }

    /**
     * Back to the plain wallpaper, with the card left standing where it is.
     *
     * sTapSuppressed is what holds it that way. A card being up is exactly what the module reads
     * as "the cover belongs here", so without it the next metadata event would put the cover
     * straight back. It lasts as long as the music does: not a track change, not the screen going
     * off, and not an unlock and a lock again - it is the last session going away that clears it,
     * and the next thing the user plays then starts from the cover as it always did.
     */
    private static void exitFromTapNow(String why) {
        int from = LockLyrics.wantsAttached()
                ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
        boolean pillMorph = MiniPlayerRuntime.prepareSceneExit();
        if (!pillMorph) MiniPlayerRuntime.noteTouch("cover exit without the pill's morph: " + why);
        if (pillMorph) {
            beginMiniMorph(false, from == CoverMorphRoute.COVER);
        } else if (CoverMorphRoute.shouldMorph(from, CoverMorphRoute.NORMAL)) {
            beginMorph(false);
        } else {
            CoverMorphLayer.cancel();
        }
        sTapSuppressed = true;
        Xp.log(TAG + why + ": leaving cover mode");
        MotionTrace.start("toggle-out");
        setCoverEnabled(false, true);
    }

    /**
     * Back into cover mode, through the normal path rather than by re-pushing whatever was last
     * composed: the track may well have moved on while the cover was off.
     */
    private static void enterFromTapNow(String why) {
        // Guarded on the two questions onMediaUpdate asks before it does anything.
        int to = LockLyrics.willAttachOnEntry()
                ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
        if (sAuto && MediaCard.sCardShowing && MiniPlayerRuntime.prepareSceneEntry()) {
            beginMiniMorph(true, to == CoverMorphRoute.COVER);
        } else if (sAuto && MediaCard.sCardShowing
                && CoverMorphRoute.shouldMorph(CoverMorphRoute.NORMAL, to)) {
            beginMorph(true);
        } else {
            CoverMorphLayer.cancel();
        }
        sTapSuppressed = false;
        sTrackKey = "";
        Xp.log(TAG + why + ": expanding into cover mode");
        MotionTrace.start("toggle-in");
        onMediaUpdate(true);
    }

    /** The OEM depth interactor's own switches, read by reflection. */
    static String depthFlags() {
        Object o = sDepthInteractor;
        if (o == null) return "interactor not seen yet";
        StringBuilder sb = new StringBuilder();
        for (String f : new String[] {"depthEffectEnableInner", "depthVideoEnable",
                "isActualDisplayDepth", "isDepthFileParsing"}) {
            try {
                java.lang.reflect.Field fl = o.getClass().getDeclaredField(f);
                fl.setAccessible(true);
                sb.append(f).append('=').append(fl.get(o)).append(' ');
            } catch (Throwable t) {
                sb.append(f).append("=? ");
            }
        }
        return sb.toString();
    }

    static View findDeductedImageView() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getContext().getResources()
                .getIdentifier("deducted_image_view", "id", "com.android.systemui");
        return id == 0 ? null : v.getRootView().findViewById(id);
    }

    /** Adds the cover into keyguard_background_layer, which sits behind the whole clock stack. */
    static void attachCover() {
        final View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = v.getContext();
                    int id = ctx.getResources().getIdentifier(
                            "keyguard_background_layer", "id", "com.android.systemui");
                    View layer = id == 0 ? null : v.getRootView().findViewById(id);
                    if (!(layer instanceof ViewGroup)) {
                        Xp.log(TAG + "keyguard_background_layer not found");
                        return;
                    }
                    Bitmap art = AlbumArt.albumArt(ctx);
                    if (art == null) {
                        Xp.log(TAG + "no album art available");
                        return;
                    }
                    ImageView iv = sCover;
                    if (iv == null || iv.getParent() != layer) {
                        detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        ((ViewGroup) layer).addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        Gen.ownView((ViewGroup) layer, iv);
                        sCover = iv;
                    }
                    iv.setImageBitmap(art);
                    rebindSession();
                    Xp.log(TAG + "cover attached, layer "
                            + layer.getWidth() + "x" + layer.getHeight());
                } catch (Throwable t) {
                    Xp.w(TAG + "attachCover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * The master switch. Off takes a cover that is up down the way a tap does, so the wallpaper
     * is handed back; on lets the card decide again, as it would have all along.
     */
    static void setCoverFeature(boolean on) {
        sCoverFeatureOn = on;
        Xp.log(TAG + "cover feature " + (on ? "on" : "off"));
        ModuleState.saveState();
        if (!on) {
            CoverMorphLayer.cancel();
            if (sCoverMode) setCoverEnabled(false, true);
        } else {
            sTapSuppressed = false;
            sTrackKey = "";
            MediaCard.applyCardState();
        }
    }

    /** Media observation stays enabled for manual cover entry and lyrics too. */
    static synchronized void setAuto(boolean on) {
        sAuto = on;
        Xp.log(TAG + "auto mode " + (on ? "on" : "off"));
        ModuleState.saveState();
        if (!on) {
            main().removeCallbacks(MediaCard.sCardGone);
            return;
        }
        startSessionWatch();
        MediaCard.applyCardState();
    }

    static String visName(int v) {
        return v == View.VISIBLE ? "VISIBLE" : v == View.INVISIBLE ? "INVISIBLE" : "GONE";
    }

    /**
     * Ends the wait a cover restored from disk is parked in when no card is ever observed.
     *
     * The saved cover comes back before any card can, and the guard above refuses to act while
     * `sCardKnown` is false. That is right for as long as a card is on its way - a restart
     * rebuilds the card and the hook fires then - but it is not a wait that could ever end if
     * nothing plays again in this process: the hook never fires, the guard has no second chance,
     * and the wallpaper keeps the last album until the user happens to start some music. Which
     * is what a SystemUI restart during playback, followed by the music stopping, left behind.
     *
     * A card cannot exist without a media session behind it, so an EMPTY list is proof the card
     * is gone. Neither of the other two answers is proof of anything and both leave the cover
     * alone: a list that could not be read, and a non-empty one - the ordinary wait.
     */
    static void releaseUnobservedCover(List<MediaController> sessions) {
        if (MediaCard.sCardKnown || !sCoverMode || sessions == null || !sessions.isEmpty()) return;
        Xp.log(TAG + "cover restored with no media session at all - leaving cover mode");
        sTrackKey = "";
        sTapSuppressed = false;
        setCoverEnabled(false, true);
    }

    /** The sessions the system holds right now, or null when the question could not be asked. */
    static List<MediaController> activeSessions() {
        Context ctx = sAppCtx;
        if (ctx == null) return null;
        try {
            MediaSessionManager msm = sMsm != null ? sMsm
                    : (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return msm == null ? null : msm.getActiveSessions(null);
        } catch (Throwable t) {
            Xp.w(TAG + "active session read failed: " + t);
            return null;
        }
    }

    static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "none";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static synchronized void startSessionWatch() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        try {
            if (sMsm == null) {
                sMsm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sSessionsCb == null) {
                // Which app owns the session changes without any one controller telling us -
                // starting a second player, or the first one going away - so the list itself has
                // to be watched, not just the controller we happen to be holding.
                sSessionsCb = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override
                    public void onActiveSessionsChanged(List<MediaController> controllers) {
                        // The one moment the list is handed over for free, and the only evidence
                        // there is while the card hook has still not fired in this process. The
                        // last session ending is what the card going away looks like from here.
                        releaseUnobservedCover(controllers);
                        rebindSession();
                    }
                };
                Gen.activeSessionsListener(sMsm, sSessionsCb, main());
                Xp.log(TAG + "active-session listener registered");
            }
        } catch (Throwable t) {
            Xp.w(TAG + "session listener failed: " + Log.getStackTraceString(t));
        }
        rebindSession();
    }

    /**
     * Re-points the callback at the session the card is showing. The card hands us its own
     * MediaSession token, so this is exact - pickController()'s ranking is only the fallback for
     * when we are driven by something other than the card.
     */
    static synchronized void rebindSession() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        MediaController c = MediaCard.controllerFromCard(ctx);
        if (c == null) c = pickController(ctx);
        boolean same = c != null && sWatched != null
                && c.getSessionToken().equals(sWatched.getSessionToken());
        if (!same) {
            if (sWatched != null && sMediaCb != null) {
                try {
                    Gen.unsessionCallback(sWatched, sMediaCb);
                } catch (Throwable ignored) {
                }
            }
            sWatched = c;
            sMediaCb = null;
            if (c != null) {
                sMediaCb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata md) {
                        MediaController w = sWatched;
                        // Before anything compares a track key against this title.
                        if (w != null) TrackName.note(w.getPackageName(), md);
                        // Every track change re-reads the state rather than trusting that the
                        // last callback of a skip was the one that says where it ended up.
                        if (w != null) updateCoverCardPlayback(w.getPlaybackState());
                        if (sCoverWanted) attachCover();
                        onMediaUpdate();
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        LockLyrics.onPlaybackState(state);
                        updateCoverCardPlayback(state);
                        MiniPlayerRuntime.refresh();
                    }

                    @Override
                    public void onSessionDestroyed() {
                        rebindSession();
                    }
                };
                try {
                    Gen.sessionCallback(c, sMediaCb, main());
                    Xp.log(TAG + "following " + c.getPackageName());
                } catch (Throwable t) {
                    Xp.w(TAG + "registerCallback failed: " + t);
                }
            } else {
                Xp.log(TAG + "no active media session");
            }
        }
        updateCoverCardPlayback(c == null ? null : c.getPlaybackState());
        MiniPlayerRuntime.refresh();
        onMediaUpdate();
    }

    /**
     * Stops following the music. Retirement only: setAuto(false) keeps the listener because the
     * cover's legacy view may still want a rebind, while a retiring generation must leave
     * nothing behind that calls into it. Under the same lock as setAuto and worker().
     */
    static synchronized void retireSessions() {
        if (sSessionsCb != null && sMsm != null) {
            try {
                Gen.unactiveSessionsListener(sMsm, sSessionsCb);
            } catch (Throwable t) {
                Xp.w(TAG + "session listener could not be removed: " + t);
            }
        }
        sSessionsCb = null;
        if (sWatched != null && sMediaCb != null) {
            try {
                Gen.unsessionCallback(sWatched, sMediaCb);
            } catch (Throwable t) {
                Xp.w(TAG + "media callback could not be removed: " + t);
            }
        }
        sWatched = null;
        sMediaCb = null;
    }

    private static final Runnable sRecheckPlayback = new Runnable() {
        @Override
        public void run() {
            MediaController w = sWatched;
            updateCoverCardPlayback(w == null ? null : w.getPlaybackState());
        }
    };

    private static void updateCoverCardPlayback(PlaybackState state) {
        int s = state == null ? PlaybackState.STATE_NONE : state.getState();
        // A skip passes through SKIPPING/BUFFERING/CONNECTING on its way to the next track. The
        // card dips for it on purpose, but nothing promises the PLAYING that ends it reaches us -
        // a fast run of skips left the card small while the music played. So a state like that
        // asks the session again shortly, until it has settled somewhere.
        boolean passing = s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_CONNECTING
                || s == PlaybackState.STATE_SKIPPING_TO_NEXT
                || s == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || s == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
        main().removeCallbacks(sRecheckPlayback);
        if (passing) main().postDelayed(sRecheckPlayback, 400L);
        boolean playing = s == PlaybackState.STATE_PLAYING;
        if (sCoverCardPlaying == playing) return;
        sCoverCardPlaying = playing;
        CoverCardLayer.playback(playing);
    }

    /** Identity of what is on screen, so a metadata storm pushes the same artwork only once. */
    static String trackKey(MediaController c) {
        if (c == null) return "";
        MediaMetadata md = c.getMetadata();
        if (md == null) return c.getPackageName();
        return c.getPackageName() + "|" + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM);
    }

    /**
     * Whether two track keys name the same track.
     *
     * The package and the title have to match outright: they are the two fields every shape of
     * the key agrees on, and they are what actually tells two tracks apart. (The key comes in two
     * shapes - the card's own is pkg|song|artist, the session fallback is pkg|title|artist|album -
     * and the album field is null in one of them and not in the other, so it cannot be compared
     * at all.)
     *
     * The artist is compared too, but only as far as one being the other with something appended.
     * That is the shape the movement takes: bilibili rewrites its own artist to
     * "...·后台听视频省流量" when the video leaves the foreground. Anything more than an appended
     * marker is left to mean a different track, which costs nothing here - a genuinely different
     * artist would have moved the title as well.
     *
     * A key too short to carry a title answers no, which leaves the plain comparison the callers
     * have already made in charge.
     *
     * Two titles can still be one track: Salt, 汽水 and QQ sing into TITLE, so their title moves
     * with every line. Those are the same track when TrackName saw both titles published on the
     * same song - and then the artist is not asked either, since 汽水 respells it as it goes.
     */
    static boolean sameTrack(String a, String b) {
        String pa = keyField(a, 0), pb = keyField(b, 0);
        String ta = keyField(a, 1), tb = keyField(b, 1);
        if (pa.isEmpty() || !pa.equals(pb)) return false;
        if (ta.isEmpty() || tb.isEmpty()) return false;
        if (!ta.equals(tb)) return TrackName.sameSong(pa, ta, tb);
        String aa = keyField(a, 2), ab = keyField(b, 2);
        return aa.equals(ab) || aa.startsWith(ab) || ab.startsWith(aa);
    }

    /** The nth |-separated field of a track key, or "" when the key does not reach that far. */
    private static String keyField(String key, int n) {
        if (key == null) return "";
        int from = 0;
        for (int i = 0; i < n; i++) {
            from = key.indexOf('|', from);
            if (from < 0) return "";
            from++;
        }
        int end = key.indexOf('|', from);
        return end < 0 ? key.substring(from) : key.substring(from, end);
    }

    /** The watched session's title now: what CoverPush checks a precompose is still about. */
    static String watchedTitle() {
        return titleOf(sWatched);
    }

    /** The session's track title, which is what a queue item can be matched against. */
    private static String titleOf(MediaController c) {
        if (c == null) return null;
        try {
            MediaMetadata md = c.getMetadata();
            return md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One place decides what the card means, because the same decision is reached from three
     * directions: the card hook, a metadata change on the session, and the session list changing
     * underneath us. Playback state is deliberately not consulted - a paused track is still a
     * card on the lockscreen, and still music mode.
     */
    private static void onMediaUpdate() {
        onMediaUpdate(false);
    }

    /** After a wake's busiest frames; after a media update, once a burst of them has settled. */
    private static final long PRECOMPOSE_WAKE_MS = 400L;
    private static final long PRECOMPOSE_MEDIA_MS = 600L;

    /** A tap or pull can enter the cover even when new media defaults to a collapsed island. */
    private static void onMediaUpdate(boolean explicitEntry) {
        MiniPlayerRuntime.refresh();
        // A new song on the lock screen, or its card just come up: the cover's picture is made
        // ahead of a tap (CoverPush.precomposeAsync sends each picture once).
        if (!sCoverMode) CoverPush.precomposeAsync(PRECOMPOSE_MEDIA_MS);
        // The cover is pushed off the media card's key, and the session's metadata can still be
        // the last song's at that moment - so the motion cover follows the metadata itself. It
        // keys on the song, so a player singing into TITLE does not look it up per line.
        if (sCoverMode) MotionCover.onCover(sCoverCardStyle.mode == CoverCardStyle.CARD);
        if (!sAuto || !MediaCard.sCardShowing) return;
        // Metadata and lyrics continue updating after a manual dismissal. Only scene entry
        // below is held off by sTapSuppressed.
        String key = MediaCard.sCardKey.isEmpty() ? trackKey(sWatched) : MediaCard.sCardKey;
        // One track, several keys. The string itself changes under a track that has not: the
        // card's own key is pkg|song|artist and the session fallback appends the album, so the
        // moment the card is torn down and rebuilt - which is what a wake from the AOD is - the
        // key loses its last field and comes back with it, twice in two frames. Bilibili rewrites
        // the artist on top of that, appending its background-audio marker when the video leaves
        // the foreground. Every one of those read as a track change: a fresh push, and the one
        // that answered it was the media card's small thumbnail, so the cover came up soft on the
        // second lock and every lock after it. Same track means same package and same title - the
        // two fields all three shapes agree on - and then there is nothing for the cover to do.
        if (!explicitEntry && (key.equals(sTrackKey) || sameTrack(key, sTrackKey))) {
            // The lyric is not so sure. A provider module cannot write its lyric until the
            // player has told it what is playing, so the payload lands on a session this has
            // already settled as the same track - and this return was the only thing between it
            // and the re-read that would pick it up. LockLyrics.onTrack was reachable from here
            // and nowhere else, so with the cover on, a module that was a second late lost the
            // song to whatever the network had found in the meantime, for the whole song.
            //
            // Its same-track path costs a getPlaybackState and a metadata read, and does nothing
            // at all unless the session is carrying a payload that song has not been read
            // against - each payload once, three per track. Cheap enough to reach from a path
            // that a title-in-the-metadata player runs on every sung line.
            LockLyrics.onTrack(key, sWatched);
            if (sCoverMode) CoverPush.retryMissingArt();
            else if (!sTapSuppressed &&
                    btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.automaticCoverEntryEnabled())
                setCoverEnabled(true, true);
            return;
        }
        sTrackKey = key;
        long ctNow = android.os.SystemClock.uptimeMillis();
        // Still waiting on the artwork for the previous one means this press lands on top of it:
        // same burst, and the clock keeps running from where it started.
        // The time limit matters as much as the pending artwork: a push that never found any art
        // leaves sCtArt at 0 for good, and without a window the next track change an hour later
        // would still count itself as part of that burst.
        if (sCtTrack != 0L && sCtArt == 0L && ctNow - sCtTrack < 2500L) {
            sCtSkips++;
        } else {
            sCtBurst = ctNow;
            sCtSkips = 0;
        }
        sCtTrack = ctNow;
        sCtArt = 0L;
        sCtCheckMs = 0L;
        sCtTries = 0;
        Xp.log(TAG + "card track: " + key);
        MiniPlayerRuntime.cardTrackChanged();
        // Started here rather than once the cover has settled, so the fetch overlaps the
        // transition instead of following it.
        LockLyrics.onTrack(key, sWatched);
        // What is coming after this one, for the next press. Always, not only when the cover is
        // on: the queue is what makes a press answerable at all, and reading it costs nothing
        // when it has not moved.
        Prefetch.onTrack(sWatched);
        // The player has caught up with a press this already acted on, and the cover on screen is
        // the right one. Pushing again would compose the same picture and fade it over itself.
        if (sCoverMode && Prefetch.wasPredicted(titleOf(sWatched))) {
            Xp.log(TAG + "cover already up from the press, no second push");
            return;
        }
        if (sCoverMode) CoverPush.pushArtAsync(true, true);
        else if (!sTapSuppressed && (explicitEntry ||
                btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.automaticCoverEntryEnabled()))
            setCoverEnabled(true, true);
    }

    /**
     * Cover mode is the wallpaper and the clock together, so both switches move as one. The
     * wallpaper is composed on the worker, the clock is taken on the main thread.
     */
    static void setCoverEnabled(final boolean on, final boolean animate) {
        setCoverEnabled(on, animate, true);
    }

    /**
     * fresh says whether this is a track change. On one, artwork identical to what is already on
     * the wallpaper means the source has not caught up yet and is worth waiting for; on a manual
     * push it just means the same song, and waiting 1.7s to conclude that is pure delay.
     */
    static void setCoverEnabled(final boolean on, final boolean animate,
                                        final boolean fresh) {
        // The one door every way into the cover goes through, so the master switch is kept here.
        if (on && (!sCoverFeatureOn || !(btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.coverAllowed()
                || btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.lyricsAllowed()))) return;
        CoverPush.pushArtAsync(on, on && fresh);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (on) {
                    if (!(btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.coverAllowed()
                            || btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.lyricsAllowed())) return;
                    enterCoverMode(animate && screenOn());
                    // With the lyrics' frost decided now (LockLyrics.attach), the kept cover.
                    CoverPush.sendKeptFirst();
                } else {
                    exitCoverMode(animate && screenOn());
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main().post(r);
    }

    /**
     * Springing the clock needs Choreographer frames, and those stop while the display is off.
     * An animation started then never settles, and leaves the hold pinned half way.
     */
    /**
     * Whether the lock screen is actually up.
     *
     * The clock container being shown is NOT this test, which is what the card rectangle used to
     * be sampled on: the keyguard's views can be laid out and VISIBLE with the phone unlocked
     * and something else in front of them - measured, vis=0 with the app in the foreground - so
     * pulling the shade down handed us the card at its position in the panel, up in the corner,
     * and the preview then drew the lock screen's card up there too.
     */
    static boolean keyguardShowing() {
        refreshSysReads();
        return sSysLocked;
    }

    /**
     * Doors for {@link ShadeLayer}, which is a file of its own on purpose - the shade port is
     * large enough that folding it in here would make the "every hook installs on its own terms"
     * rule unauditable.
     *
     * Both the keyguard answers are exposed together rather than one being picked here, because
     * the two disagree and which one the shade should use is the question the port turns on:
     * {@link #onKeyguardNow} is the clock container being shown, and the comment above
     * {@link #keyguardShowing} records that it reads VISIBLE with the phone UNLOCKED and an app
     * in the foreground. The shade is an unlocked-only feature, so it must gate on the
     * KeyguardManager answer, not on the container.
     */
    public static boolean keyguardLocked() {
        return keyguardShowing();
    }

    public static boolean coverExitActive() { return ClockCollapse.exiting(); }

    public static boolean coverModeOn() {
        return sCoverMode;
    }

    /**
     * Whether the clock is ours to keep small. Everything that decides "is the clock held" across
     * a sleep and a wake asks this; who holds it is LockHold's.
     */
    static boolean clockHeld() {
        return LockHold.clockHeld();
    }

    /**
     * An immersive page went up behind the lock screen, or came down (ImmersiveHost). It takes
     * the lock screen the way the cover does. Animated only where it can be seen; elsewhere the
     * next wake brings the clock in, as it does for the cover.
     */
    public static void onImmersive(boolean on) {
        boolean visible = screenOn() && onKeyguardNow();
        if (on) LockHold.take(LockHold.Owner.IMMERSIVE, visible, "immersive");
        else LockHold.give(LockHold.Owner.IMMERSIVE, visible);
    }

    /**
     * The last owner gave the lock screen back: the cut-out may return. Except on a video
     * wallpaper with the cover view still up - that view is held over the window until the
     * window has the video again, and the cut-out comes back with it going
     * (CoverPush.dropVideoCover), not in front of it now.
     */
    static void handBackDepth() {
        if (!(sVideoWallpaper && sCover != null)) setDepthHidden(false);
    }

    /**
     * Whether the shade window is showing the lock screen - lit or dozing - rather than the shade
     * over an unlocked phone. Which doze may show a page is ImmersiveHost's to decide.
     */
    public static boolean immersiveOnLockScreen() {
        return keyguardShowing() && onKeyguardNow();
    }

    /**
     * Whether the module is logging its per-frame traces.
     *
     * The shade layer traces one line per 5% of a pull-down plus every frame for the forty after
     * a finger lifts - about sixty lines per gesture, which is the right amount while working on
     * the hand-off and far too much to leave running. Off unless asked for with the `verbose`
     * op.
     */
    static boolean verbose() {
        return sVerbose;
    }

    /**
     * How long one of these two answers is reused before the system is asked again.
     *
     * Both are binder calls into system_server - `isKeyguardLocked()` on WindowManagerService
     * and `isInteractive()` on PowerManagerService - and the card assert makes both of them, on
     * every frame of a transition, and then the card's own pre-draw makes them a second time in
     * the same frame. Measured with the cost probe: a steady 230us of every frame's placement,
     * against 30us for the clock's whole pre-draw, with the two calls unchanged in between.
     *
     * 150ms is short enough that nothing can be seen through it - the questions are "is this card
     * on the lock screen and is the screen lit", and both are invalidated outright by the
     * broadcasts that answer them (see `lifecycle`), so the moments that matter do not wait for
     * the timer at all. What the timer covers is everything in between, which is all frames.
     */
    private static final long SYS_READ_MS = 150L;
    private static long sSysReadAt;
    private static boolean sSysLocked;
    private static boolean sSysInteractive;

    private static void refreshSysReads() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sSysReadAt < SYS_READ_MS) return;
        sSysReadAt = now;
        // Written before the first question is asked, so a throw inside one of them leaves the
        // other's answer fresh rather than re-reading both on the next frame.
        sSysLocked = readKeyguardLocked();
        sSysInteractive = readInteractive();
    }

    /** The next caller asks the system again. Called from the broadcasts that change the answers. */
    private static void forgetSysReads() {
        sSysReadAt = 0L;
    }

    private static boolean readKeyguardLocked() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    sAppCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readInteractive() {
        try {
            PowerManager pm = (PowerManager) sAppCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The swap itself, for the two-finger tap and the card's page button alike, so the two
     * cannot drift apart on what blocks it. Null when it ran; otherwise why it did not.
     */
    static String toggleLyricPage() {
        View c = sContainer;
        String no = !screenOn() ? "the screen is off"
                : !keyguardShowing() ? "the keyguard is not showing"
                : c == null ? "no clock container"
                : !c.isShown() ? "the clock container is hidden"
                : bouncerUp() ? "the bouncer is up"
                : controlCenterUp() ? "the control centre is up"
                : ChargeAnim.chargeAnimUp() ? "the charging animation is up"
                : !MediaCard.sCardKnown ? "the card is not known"
                : !MediaCard.sCardShowing ? "no track on the card"
                : null;
        if (no != null) return "blocked: " + no;
        if (!LockLyrics.sEnabled) {
            // Nothing on the lock screen to switch. Answering the gesture by bringing the
            // lyrics back would be writing the app's setting from here, which is the whole of
            // what this gesture stopped doing.
            return "blocked: the lyrics switch is off";
        }
        if (!LockLyrics.hasLyrics()) {
            // The cover is the only page a track without lyrics has. Taken as a toggle, the tap
            // would flip the hidden flag with nothing changing, and the next song that does have
            // lyrics would then open on the cover for no reason anyone could see.
            return "blocked: this track has no lyrics";
        }
        if (!sCoverMode) {
            LockLyrics.sTapHidden = false;
            enterFromTap("lyric page button");
            return null;
        }
        if (!btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.musicCoverEnabled()) {
            exitFromTap("lyric page button");
            return null;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        if (sCoverMode) {
            int from = LockLyrics.wantsAttached()
                    ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
            int to = LockLyrics.willAttachAfterTapToggle()
                    ? CoverMorphRoute.LYRICS : CoverMorphRoute.COVER;
            if (CoverMorphRoute.shouldMorph(from, to)) {
                beginMorph(to == CoverMorphRoute.COVER);
            }
        }
        LockLyrics.toggleByTap(sTrackKey, sWatched);
        // How long the swap took. Nothing is written down, so this is its whole cost.
        LockTaps.sTwoWhy = "lyrics " + (LockLyrics.sTapHidden ? "hidden" : "shown") + " in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms";
        // The button's icon names the other page, and nothing else redraws the card for it.
        MediaCard.kickCardFrame();
        return null;
    }

    public static boolean screenOn() {
        refreshSysReads();
        return sSysInteractive;
    }

    static void detachCover() {
        // The session watch belongs to auto mode, not to the (legacy) in-SystemUI ImageView, so
        // dropping that view must not stop the module following the music.
        if (!sAuto && sWatched != null && sMediaCb != null) {
            try {
                Gen.unsessionCallback(sWatched, sMediaCb);
            } catch (Throwable ignored) {
            }
            sWatched = null;
            sMediaCb = null;
        }
        final ImageView iv = sCover;
        sCover = null;
        // Nothing is owed to a view that is going away: a fade armed for it would otherwise be
        // picked up by whatever the guard sees next, and a wait still outstanding would outlive
        // the cover it was armed for.
        CoverPush.sCoverFadeWaitMs = 0;
        CoverPush.sCoverFadeWaiting = false;
        CoverPush.sCoverFadingOut = null;
        CoverPush.sOwedSwap = null;
        // The view is going, so it shows nothing: the next push owes a fade whatever it carries.
        CoverPush.sShownArtPrint = 0;
        main().removeCallbacks(CoverPush.sCoverFadeTimeout);
        if (iv == null) return;
        // Taken now, not when the post below runs: a cover put back up in between (a fast
        // pull-down-and-up of the mini player) installs its own bitmaps here first, and the post
        // read them back and recycled the ones the new view was about to draw - a SystemUI crash.
        // Only the composed ones are ours - albumArt() hands back a bitmap the media session
        // owns, and recycling that would take the card's thumbnail with it.
        final Bitmap b = sCoverBitmap;
        final Bitmap fb = sCoverBlurBitmap;
        sCoverBitmap = null;
        sCoverBlurBitmap = null;
        sVideoCoverBlurred = false;
        Gen.postOn(iv, new Runnable() {
            @Override
            public void run() {
                try {
                    CoverPush.releaseCoverGuard();
                    iv.animate().cancel();
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    iv.setImageDrawable(null);
                    if (b != null) b.recycle();
                    if (fb != null && fb != b) fb.recycle();
                    // A cover put back up since owns the video surfaces now and keeps them
                    // hidden; handing them back here would show the wallpaper's cut-out subject
                    // in front of it.
                    if (sCover != null) {
                        Xp.log(TAG + "cover detached, surfaces kept for the new cover");
                        return;
                    }
                    // The live wallpaper was only hidden because this view was covering it.
                    // Whatever removed the view - an exit, a keyguard rebuild, a failure part
                    // way through - the wallpaper has to come back with it, or the lock screen
                    // is left blank. Off the lock screen that hand-back is deferred rather than
                    // dropped; setVideoSurfacesHidden() says why.
                    View bg = sVideoBg, fg = sVideoFg;
                    sVideoBg = null;
                    sVideoFg = null;
                    CoverPush.setVideoSurfacesHidden(false, bg, fg);
                    Xp.log(TAG + "cover detached"
                            + (bg == null && fg == null ? ""
                               : sVideoWpOwed ? ", live wallpaper owed back"
                               : ", live wallpaper restored"));
                } catch (Throwable t) {
                    Xp.w(TAG + "detachCover failed: " + t);
                }
            }
        });
    }

    /** The cover's colour, or 0 when there is nothing to take one from. */
    public static int coverTint() {
        // The screen check is the AOD one. Cover mode outlives the display going off, and the
        // glyphs are the same TimeViews in the always-on view: the cover's colour describes a
        // picture the AOD is not drawn on, and repainting those glyphs in it is the one way this
        // could make things worse than it found them. `isInteractive` is false in AOD, so the
        // OEM's own colouring stands there.
        if (!sCoverMode || !sScreenOn) return 0;
        int forced = sCoverTintOverride;
        return forced != 0 ? forced : sCoverTint;
    }

    /**
     * The OEM's colour with its hue and saturation replaced by the cover's, or that same colour
     * back when there is no cover to take one from.
     *
     * The system's automatic clock colouring is computed from the wallpaper's palette, and the
     * cover replaces the wallpaper behind SystemUI's back - so the palette arriving here
     * describes a picture the clock is no longer drawn on. What is wanted is a clock in the
     * colour of the picture it is actually over, which is what this puts back. Lightness is
     * deliberately NOT taken from the cover: that half belongs to the system.
     */
    private static int legible(View v, int argb) {
        int tint = coverTint();
        if (tint == 0) return argb;
        float[] cover = new float[3], hsv = new float[3];
        android.graphics.Color.colorToHSV(tint, cover);
        android.graphics.Color.colorToHSV(argb, hsv);
        hsv[0] = cover[0];
        hsv[1] = cover[1];
        int out = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(argb), hsv);
        if (sVerbose) {
            Xp.log(TAG + "colour " + Integer.toHexString(argb) + " -> "
                    + Integer.toHexString(out) + " over tint " + Integer.toHexString(tint)
                    + " " + viewIdOf(v));
        }
        return out;
    }

    /**
     * Whether the always-on display in play is the FULL-SCREEN one - the whole lock screen shown
     * dimmed - as opposed to the plain one that shows only a clock.
     *
     * The OEM's own answer rather than the secure setting: `fullAodEnable()` ANDs the user's
     * switch with the device support, the doze master switch and whether the current wallpaper and
     * template allow it, and any one of those going off is a phone whose AOD is not this mode. The
     * settings are the fallback for a build whose interfaces-manager does not have the impl.
     *
     * Asked once per sleep, from ClockCollapse.keepInAod(), so the reflection is not on any
     * per-frame path.
     */
    public static boolean fullAodOn() {
        View c = sContainer;
        if (c != null) {
            try {
                ClassLoader cl = c.getClass().getClassLoader();
                Class<?> iface = Class.forName(
                        "com.miui.interfaces.keyguard.IMiuiFullAodManager", false, cl);
                Class<?> iim = Class.forName(
                        "com.miui.systemui.interfacesmanager.InterfacesImplManager", false, cl);
                Object mgr = iim.getMethod("getImpl", Class.class).invoke(null, iface);
                Object on = mgr == null ? null : Xp.callMethod(mgr, "fullAodEnable");
                if (on instanceof Boolean) return (Boolean) on;
            } catch (Throwable t) {
                // Not logged on every call: a build without the impl would fill the log with it,
                // and the fallback below answers the same question.
            }
        }
        try {
            // The current user's copy, which is the one the keyguard is drawn for.
            android.content.ContentResolver cr = sAppCtx.getContentResolver();
            return android.provider.Settings.Secure.getInt(cr, "full_screen_aod_on", 0) == 1
                    && android.provider.Settings.Secure.getInt(cr, "full_screen_aod_support", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether a class declares this field at all - getObjectField throws when it does not. */
    static boolean hasField(Object o, String name) {
        try {
            for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
                k.getDeclaredField(name);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * How big a view is drawn against its own size: every ancestor's scale multiplied in, the
     * collapse's time_group and the keyguard's zoom among them. The mean of the two axes, since
     * the collapse may scale the width a little differently from the height.
     */
    static float drawnScale(View v) {
        float sx = 1f, sy = 1f;
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            sx *= p.getScaleX();
            sy *= p.getScaleY();
        }
        return (float) Math.sqrt(Math.abs(sx * sy));
    }

    // --------------------------------------------- the OEM's own translation function

    /** Calls a method on the AllInOneHourClock / AllInOneMinuteClock views of both trees. */
    static void callOnClockViews(final String method, final String kind, final float f,
                                         final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "callclock: need m="); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                Object arg = "b".equals(kind) ? (Object) b : "i".equals(kind) ? (Object) n : (Object) f;
                for (View root : clockRoots()) {
                    try {
                        View c = ((android.view.ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        Xp.callMethod(c, method, arg);
                        c.invalidate();
                        Xp.log(TAG + viewIdOf(root) + " " + c.getClass().getSimpleName()
                                + "." + method + "(" + arg + ") ok");
                    } catch (Throwable e) {
                        Xp.log(TAG + "callclock " + method + ": " + e);
                    }
                }
            }
        });
    }

    /** Calls one TimeView setter on every clock view in both subtrees, for fast iteration. */
    static void callOnTimeViews(final String method, final String kind, final float f,
                                        final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "call: need m="); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                Object arg = "b".equals(kind) ? (Object) b : "i".equals(kind) ? (Object) n : (Object) f;
                int hits = 0;
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null) continue;
                            Xp.callMethod(t, method, arg);
                            t.invalidate();
                            hits++;
                        } catch (Throwable e) {
                            Xp.log(TAG + "call " + method + " on " + id + ": " + e);
                        }
                    }
                }
                Xp.log(TAG + "call " + method + "(" + arg + ") applied to " + hits + " views");
            }
        });
    }

    static void groupScale(final String idName, final float k, final float px,
                                   final float py, final float ty) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        Gen.postOn(v, new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier(idName, "id", "com.android.systemui");
                    int n = 0;
                    for (View root : clockRoots()) {
                        View g = id == 0 ? null : root.findViewById(id);
                        if (g == null) continue;
                        g.setPivotX(px >= 0 ? px : g.getWidth() / 2f);
                        g.setPivotY(py >= 0 ? py : 0f);
                        g.setScaleX(k);
                        g.setScaleY(k);
                        g.setTranslationY(ty);
                        n++;
                        Xp.log(TAG + idName + " in " + viewIdOf(root) + " scale=" + k
                                + " pivot=" + g.getPivotX() + "," + g.getPivotY() + " ty=" + ty
                                + " size=" + g.getWidth() + "x" + g.getHeight());
                    }
                    if (n == 0) Xp.log(TAG + idName + " not found in either clock root");
                } catch (Throwable e) {
                    Xp.w(TAG + "groupScale failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    /**
     * HyperOS renders the clock as a sandwich: the hour lives under
     * keyguard_background_layer (KeyguardClockContainer -> AllInOneHourClock) and the minute
     * under keyguard_foreground_layer (AllInOneMinuteClock), so the wallpaper subject can sit
     * between them. Both subtrees reuse the SAME ids - time_group, hour_view, clock_animation_
     * container - so a findViewById from the root only ever reaches the hour. Anything that
     * changes the clock's geometry has to be applied to both.
     */
    static View[] clockRoots() {
        View v = sContainer;
        if (v == null) return NO_ROOTS;
        View[] cached = sRootsCache;
        // The pair is remembered for the container it was found in, and only while both halves
        // are still in a window - a rebuilt keyguard re-inflates them and detaches the old ones,
        // and the detach is what says so.
        //
        // ONLY a complete pair is remembered. The two containers are inflated together on a
        // normal build, but "together" is not something this can assume: the first call of a
        // process can land while only the background half exists, and answering that reading
        // from the cache for the rest of the container's life would leave the minute half at
        // full size for ever - the same shape of bug as the doodle that answered "no clock
        // here" once and stayed unscaled. A half answer is therefore not cached, and costs
        // exactly what it costs today.
        if (v == sRootsFor && cached != null && rootsAttached(cached)) return cached;
        View root = v.getRootView();
        java.util.List<View> out = new java.util.ArrayList<>(2);
        for (String id : ROOT_IDS) {
            int resId = ClockViews.resId(root.getResources(), id);
            View c = resId == 0 ? null : root.findViewById(resId);
            if (c != null) out.add(c);
        }
        View[] found = out.toArray(new View[0]);
        if (found.length == ROOT_IDS.length) {
            sRootsCache = found;
            sRootsFor = v;
        } else {
            sRootsCache = null;
            sRootsFor = null;
        }
        return found;
    }

    private static final String[] ROOT_IDS = {"miui_keyguard_clock_container",
            "miui_keyguard_foreground_clock_container"};
    private static final View[] NO_ROOTS = new View[0];
    /** The containers the last resolution found, and the container it found them in. */
    private static View sRootsFor;
    private static View[] sRootsCache;

    private static boolean rootsAttached(View[] roots) {
        for (View r : roots) {
            if (!r.isAttachedToWindow()) return false;
        }
        return true;
    }

    /** A keyguard rebuild re-inflates the clock containers; the pair above is stale from here. */
    static void forgetClockRoots() {
        sRootsCache = null;
        sRootsFor = null;
    }

    static String viewIdOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return String.valueOf(v.getId());
        }
    }

}
