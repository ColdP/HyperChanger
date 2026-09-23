package btm.m.os4.systemuihook.hypermusiccover;

import btm.m.os4.systemuihook.BuildConfig;

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
public class Main extends XposedModule {

    /**
     * The framework instantiates this once per process it injects us into and attaches itself
     * before any callback runs. Everything below is static - the module is one piece of state
     * per process - so the interface is handed to {@link Xp} rather than kept as a field.
     */
    public Main() {
    }


    static final String TAG = "[MCProbe] ";
    private static final String ACTION = "btm.m.os4.systemuihook.hypermusiccover.PROBE";

    private static final String CLS_CONTAINER =
            "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String CLS_INTERACTOR =
            "com.android.keyguard.interactor.KeyguardClockNotifInteractor";
    private static final String CLS_TIME_VIEW = "com.miui.clock.allInOne.TimeView";
    /** The OEM's own per-style index of clock parts. See dumpClockViewTypes(). */
    private static final String CLS_CLOCK_VIEW_TYPE = "com.miui.clock.module.ClockViewType";
    /** The date line above the clock - the only thing on the lock screen that draws it. */
    private static final String CLS_TEXT_AREA = "com.miui.clock.classic.ClassicTextAreaView";
    private static final String CLS_MEDIA_CARD = "com.android.systemui.statusbar.notification"
            + ".mediacontrol.MiuiMediaNotificationControllerImpl";
    private static final String CLS_KG_WALLPAPER_MANAGER =
            "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager";
    /**
     * The fingerprint ring is drawn twice over, so hiding one of these leaves the other on
     * screen: the frame animation plays the pulsing circle from a list of drawables, and the
     * icon view holds the static print underneath it.
     */
    private static final String CLS_FOD_ANIM =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation";
    private static final String CLS_FOD_ICON =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView";
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
    private static final String CLS_KG_PANEL =
            "com.android.keyguard.panel.KeyguardPanelViewController";
    private static final String AVOID_PREFIX = CLS_KG_PANEL
            + "$nsslLockYPosition_delegate$lambda";
    private static final String AVOID_SUFFIX = "$$inlined$combine$1$3";

    /**
     * The shade's per-frame progress, and the property it is written through.
     *
     * `expansion` is 0 with the panel shut and 1 wide open, and it is the only quantity in the
     * shade that tracks the finger. The module this layer is ported from reached it through
     * `FloatFlowProperty.setValue` rather than through the animator, because the animator on
     * that build was a FloatFlowProperty that did not declare a `setValue` of its own -
     * confirmed on OS4 as well, see the dump in the plan. Hooking the base class therefore
     * catches it, and the filter below keeps every other Folme property in SystemUI out.
     */
    private static final String CLS_FLOAT_FLOW = "com.miui.systemui.util.FloatFlowProperty";
    /**
     * MIUI's per-window shade blur. Three are built - window, notification panel, control
     * centre - and only the first is the background this module replaces. See ShadeBlur.
     */
    private static final String CLS_BLUR_PROVIDER =
            "com.miui.systemui.shade.blur.ShadeBlendBlurController$BlurProvider";
    /** Where every notification row gets its material; see the card-blur note in ShadeLayer. */
    private static final String CLS_NOTIF_UTIL =
            "com.android.systemui.statusbar.notification.utils.NotificationUtil";
    private static final String CLS_EXPANSION_ANIMATOR =
            "com.android.systemui.shade.NotificationPanelExpansionAnimator";
    /** The shade's controller and its touch handler, for the finger-following curtain. */
    private static final String CLS_PANEL_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CLS_PANEL_TOUCH = CLS_PANEL_CONTROLLER + "$TouchHandler";
    /** Resolved once; the per-frame comparison is identity, not a name string. */
    private static volatile Class<?> sExpansionCls;

    /** SystemUI's own keyguard wallpaper manager, for the wallpaper type. See wallpaperKind(). */
    private static volatile Object sKgWallpaperMgr;
    /**
     * Whether the lock wallpaper is a live one, which decides where the cover is drawn: the
     * wallpaper process's GL texture for a still, a view in our own keyguard layer for a live
     * one. See showVideoCover(). Re-read before every push, because the user can change the
     * wallpaper between two songs.
     */
    private static volatile boolean sVideoWallpaper;
    /** The live wallpaper's cut-out subject, hidden alongside deducted_image_view. */
    private static volatile View sVideoFg;
    /** The live wallpaper itself, in the background layer, under our cover. */
    private static volatile View sVideoBg;
    /** The bitmap currently on sCover, ours to recycle when it is replaced. */
    private static volatile Bitmap sCoverBitmap;

    /**
     * The composed cover the notification shade draws, and the one it is replacing.
     *
     * Deliberately NOT the same object as sCoverBitmap. That one belongs to the live-wallpaper
     * cover view, which detachCover() recycles on every keyguard rebuild; sharing it would blank
     * the shade for a frame each time. The cost of not sharing is a second full-screen bitmap on
     * the video path only (12.5MB -> 25MB), which is worth paying for an ownership rule with no
     * cases in it.
     *
     * setShadeArt() below is the ONLY writer and the ONLY recycler of either of these. Nothing
     * else in this file may call recycle() on them.
     */
    private static volatile Bitmap sShadeArt;
    /** Retired, held for a couple of frames so nothing is drawing it when it goes. */
    private static volatile Bitmap sShadeRetired;

    static volatile View sContainer;
    private static volatile Class<?> sContainerCls;
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
    private static volatile Method sNotifStateChange;
    private static boolean sReceiverRegistered;

    /**
     * While non-null, every notifStateChange() call - ours and the system's own
     * re-assertions from KeyguardClockNotifInteractor$collectNotificationYSource -
     * is coerced to this Y. This is how the cover mode takes ownership of the clock.
     */
    static volatile Float sHoldY;
    /** Last Y the system asked for, so we can hand control back on release. */
    static volatile float sLastSystemY = Float.NaN;
    /** Last Y we actually applied, i.e. where a ramp starts from. */
    private static volatile float sCurrentY = Float.NaN;
    private static ValueAnimator sRamp;
    private static Choreographer.FrameCallback sFrameCb;
    private static float sSpringX, sSpringV;
    /** Set while our own frame is inside setNotifY, so we don't read it back as the system's. */
    private static volatile boolean sSelfDriving;
    /** Per-frame setNotifY logging floods logcat at 8ms intervals; off unless asked for. */
    private static volatile boolean sVerbose;
    /**
     * Uniform scale applied to the glyph size the OEM computes, at the last mile. The OEM's
     * own animation keeps driving every frame; we only shrink what it asks for. NaN = off.
     */
    private static volatile float sSizeScale = Float.NaN;
    /**
     * Cover-mode collapse. The OEM already animates notifY frame by frame; deriving the extra
     * group scale from that same y means the OEM's spring drives the collapse too, and we
     * never write an animator for it. NaN = off.
     */
    private static volatile float sCollapseMin = Float.NaN;
    /** Below this notifY the OEM's variable font stops shrinking - the collapse takes over. */
    private static final float SQUEEZE_FLOOR = 740f;
    private static volatile float sAppliedK = Float.NaN;
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
    private static volatile int sCoverTint;
    /** From a debug op, so a colour can be tried without hunting for the artwork that gives it. */
    private static volatile int sCoverTintOverride;
    /**
     * The strip of the cover that gets sampled for that reading, in dp from the top of the
     * screen. Generous on purpose: the date is pinned to DATE_TOP_DP and the collapsed clock
     * hangs under it, and how tall that whole block is depends on the clock style - 56dp clears
     * the status bar and 172dp is past the glyphs on every style measured so far, the point
     * being that a band that overshoots by a few rows still describes what the eye sees there.
     */
    private static final float BAND_TOP_DP = 56f, BAND_BOT_DP = 172f;

    /**
     * The same strip, measured off the placement instead of written down, in screen pixels.
     *
     * The two constants above describe the all_in_one layout - the date at DATE_TOP_DP with the
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
    private static volatile float sBandTopPx = Float.NaN, sBandBotPx = Float.NaN;

    /** Air above the date and below the clock that the band includes. */
    private static final float BAND_PAD_DP = 8f;

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
        if (!sCoverMode && !sReleasing) return;
        int[] p = LOC_BAND;
        float top = Float.NaN, bot = Float.NaN;
        View date = sDateView;
        if (usableDate(date)) {
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
    /**
     * Liquid-glass -> filled morph across the collapse. AllInOneBase.updateGlassValue(float)
     * writes glassData[36] and fades the glyph interior from fully transparent (the liquid
     * glass look, which degenerates into hairlines once the clock is small) to solid. NaN = off.
     */
    private static volatile float sGlassV0 = Float.NaN, sGlassV1 = Float.NaN;
    private static volatile float sAppliedGlassV = Float.NaN;
    /** Armed once the HyperLight (统一柔光玻璃) counter-hook is installed; see armMiGlassGuard(). */
    private static volatile boolean sMiGlassGuardArmed;
    /** How many times the counter-hook actually fired on a clock glyph, for the one-line proof. */
    private static volatile int sMiGlassGuardHits;

    /** ClockViewType constants by name, resolved once. See oemPart(). */
    private static volatile java.util.Map<String, Object> sViewTypes;

    /** The full-bleed album cover we add behind the clock. */
    private static volatile ImageView sCover;
    /** Survives keyguard rebuilds: re-attach whenever a fresh clock container shows up. */
    private static volatile boolean sCoverWanted;
    /**
     * While true, the wallpaper's subject cut-out stays hidden. HyperOS composites it in
     * SystemUI (KeyguardDepthInteractor -> deducted_image_view) on top of the hour clock, so
     * swapping the wallpaper texture alone leaves the OLD wallpaper's subject floating over the
     * album cover - the leftover the cover swap does not otherwise explain.
     */
    private static volatile boolean sDepthHidden;
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
    private static Context sAppCtx;
    /**
     * HyperChanger has to keep its state separate from the standalone HyperMusicCover module.
     * Both modules run in SystemUI, so a filename alone is otherwise a shared global resource.
     */
    private static final String STATE_FILE = "hyperchanger_mc_cover_state";
    /** The pre-namespace file used by older HyperChanger builds. Never write this file again. */
    private static final String LEGACY_STATE_FILE = "mc_cover_state";

    static Context appContext() {
        return sAppCtx;
    }

    /** The album cover is the wallpaper: depth cut-out hidden and the clock collapsed. */
    private static volatile boolean sCoverMode;
    /** Style 2 changes only the wallpaper composition and leaves the native clock untouched. */
    private static volatile boolean sStyle2Mode;
    private static final float STYLE2_COVER_ASPECT = 119.6f / 91.6f;
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
    private static final float DEFAULT_CLOCK_HEIGHT_DP = 36f;
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
    /** What the collapse uses before any box has been measured - the old coefficient, which is
     *  what this device was tuned to. Never used once a box is in hand. */
    private static final float UNMEASURED_CLOCK_K = 0.335f;
    private static final float DEFAULT_GLASS_END = 0.75f;
    static volatile float sClockHeightDp = DEFAULT_CLOCK_HEIGHT_DP;
    /**
     * A clock size stored before the unit changed, waiting for a measured box to convert with.
     *
     * The old value was a coefficient and the new one is a dp height, and 0.335dp would be a
     * clock three pixels tall - so clamping alone cannot tell the two apart. A stored value
     * under CLOCK_HEIGHT_MIN_DP is one of the old ones, and the only thing that says what it
     * was worth is the box it used to multiply, so it waits for the first one measured.
     */
    private static volatile float sClockLegacyK = Float.NaN;
    /** Fraction of the clock style's natural size; NaN keeps the legacy dp-height setting. */
    static volatile float sClockSize = Float.NaN;
    static final float CLOCK_SIZE_MIN = 0.05f;
    /** Vertical offset shared by the collapsed date and clock. */
    static volatile float sClockOffsetDp = 0f;
    static final float CLOCK_OFFSET_MIN_DP = -60f;
    static final float CLOCK_OFFSET_MAX_DP = 300f;
    private static volatile float sGlassEnd = DEFAULT_GLASS_END;
    /** The session we are mirroring, plus the callback that keeps the cover on the right track. */
    private static MediaController sWatched;
    private static MediaController.Callback sMediaCb;

    /**
     * Where the sharp cover sits vertically inside the wallpaper, 0 = flush with the top,
     * 0.5 = centred. Centred puts the most interesting part of the artwork exactly behind the
     * media card, which is why Apple biases it upwards.
     */
    private static final float DEFAULT_BIAS = 0.34f;
    private static volatile float sBias = DEFAULT_BIAS;

    /**
     * The notification centre's cover framing, as a DELTA on {@link #sBias} in milli-units.
     *
     * A delta rather than a bias of its own, and the choice is the whole design: zero means "the
     * same framing as the lock screen", which is both the default and the only case that costs
     * nothing at all - see shadePicture(). Anything else needs its own full-screen bitmap,
     * because the framing is baked in at COMPOSE time (composeWallpaper places the sharp band at
     * `(h - coverH) * bias`) and no transform on the layer can reproduce it without also
     * changing the picture's scale.
     */
    private static volatile int sShadeBiasDelta = 0;

    /**
     * Cover mode follows the media card. Not a setting: with it off the module does nothing at
     * all, which is not a state worth offering - uninstalling is the way to turn the module off.
     * The adb "auto" op can still flip it for a debugging session; it comes back on at startup.
     */
    private static volatile boolean sAuto;
    /**
     * Whether the OEM media card is on the lockscreen. This, not playback state, is the switch:
     * pausing leaves the card up, so it leaves the cover up too, and dismissing the card is the
     * one thing that ends music mode.
     */
    private static volatile boolean sCardShowing;
    /** False until the card hook has fired once, so a restart does not act on an unknown state. */
    private static volatile boolean sCardKnown;
    /** The session the card itself is bound to - exact, where pickController() only guesses. */
    private static volatile android.media.session.MediaSession.Token sCardToken;
    /** Track identity straight off the card's MediaData. */
    private static volatile String sCardKey = "";
    /**
     * A track change arrives as a remove followed by an add, so the card is briefly gone. Wait
     * this long before believing it: long enough to absorb that pair, short enough that
     * dismissing the card by hand still feels immediate.
     */
    private static final long CARD_GONE_MS = 600L;
    /**
     * A media app can briefly expose an empty session list while handing a track to another
     * player. Confirm the list is still empty before resetting an explicitly selected music
     * presentation, otherwise that transition would flash the lockscreen back to stock.
     */
    private static final long SESSIONS_GONE_MS = 700L;
    /** What the pushed wallpaper currently shows, so a metadata storm pushes it only once. */
    private static volatile String sTrackKey = "";
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
    private static volatile int sScreenW = 1200, sScreenH = 2608;

    /**
     * Scratch pairs for getLocationOnScreen, one per call site.
     *
     * Every one of these sits on the collapse's per-frame path, where the array was the whole of
     * what the call allocated and there are several hundred frames in a transition for the
     * collector to walk afterwards. One array per SITE rather than one shared: they are all read
     * and consumed within a few lines of being filled, none of them nests inside another, and a
     * shared one would be a bug waiting for the first caller that does nest.
     */
    private static final int[] LOC_PLACE = new int[2];
    private static final int[] LOC_PEND = new int[2];
    private static final int[] LOC_BAND = new int[2];
    private static final int[] LOC_STALE = new int[2];
    private static final int[] LOC_CARD = new int[2];

    /**
     * The media card, restyled. This is the first thing the module changes about the card
     * itself, and it follows the same ownership rule as the cut-out: the card is rebound on
     * every track change and re-laid out constantly, so the look is asserted every frame from a
     * guard rather than set once.
     *
     * Both are off by default - the card as the OEM draws it is not wrong, only busy - and both
     * apply in cover mode only, which is when the artwork is already the wallpaper and the
     * thumbnail is showing it a second time.
     */
    private static volatile boolean sMcHideArt;
    private static volatile boolean sMcCenterText;
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
    private static volatile boolean sMcTitleTap;
    /**
     * Hide the lock screen fingerprint ring. Unlike the two card switches above, this one is not
     * tied to cover mode: an icon that appeared and vanished as the music started and stopped
     * would read as a glitch rather than as a setting.
     */
    private static volatile boolean sHideFp;
    /**
     * Whether the wallpaper process is sizing the keyguard texture to the SCREEN rather than to
     * the wallpaper file, which is its default and is the same switch as WallpaperProbe.sTexFit
     * on the other side. Kept here for one reason: while that is on, the re-fit below - which
     * rewrites the user's lockscreen wallpaper to the screen's size - must not run. Rewriting it
     * is what costs the user their depth cut-out, and it is no longer needed to make the
     * crossfade affordable, because a screen-sized texture is what made it affordable.
     */
    private static volatile boolean sTexFit = true;
    /**
     * Whether the notification stack keeps clear of the fingerprint icon: 0 leaves it to the
     * system, 1 never avoids it, 2 always does. Not a boolean, because "off" here would mean two
     * different things - stop reserving the space, or reserve it even with no print enrolled.
     */
    private static volatile int sFpAvoid;
    /**
     * Every fingerprint icon view built since SystemUI started, weakly held. The alpha is set at
     * construction, but the switch can move afterwards, and a hidden icon has to be able to come
     * back without the user restarting SystemUI.
     */
    private static final java.util.Map<View, Boolean> sFodIcons =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    /** resId -> is this one of the ring's frames, so the name lookup happens once per drawable. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> sFodRing =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Where the card lands on the lock screen, in screen pixels. The app draws a preview of the
     * lock screen and cannot measure this itself - the card belongs to SystemUI - so it is
     * sampled here, while the keyguard is actually up, and kept for the app to ask about later.
     */
    private static volatile int sCardL, sCardT, sCardW, sCardH;
    /** The reading waiting to be confirmed by holding still. See sampleCardRect(). */
    private static int sCardSampleL, sCardSampleT, sCardSampleW, sCardSampleH;
    private static long sCardSampleAt;
    private static ViewTreeObserver.OnPreDrawListener sCardGuard;
    private static View sCardGuarded, sCardArt, sCardTitle, sCardArtist;
    /** The title carrying our play/pause listener, and what it was clickable-wise before. */
    private static View sCardTitleTapped;
    private static boolean sCardTitleClickable;
    /** Set only around a capture. See shootCard(). */
    private static volatile boolean sCardForced;
    /**
     * How far the card is into the cover look: 0 is the card as the OEM draws it, 1 is the
     * artwork hidden and the text centred.
     *
     * Deliberately NOT an animator of its own. applyCollapse() already derives a 0..1 progress
     * from the notifY of the frame it is on, and both the clock's collapse scale and the glass
     * morph are read off it; hanging the card off the same number means the OEM's spring drives
     * the thumbnail, the clock and the glass together, and there is nothing left to keep in sync.
     */
    private static volatile float sCardP;
    /** How small the thumbnail gets at the far end of the fade. Apple's shrinks as it goes. */
    private static final float CARD_ART_MIN_SCALE = 0.82f;
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
    private static float sArtBaseX = 1f, sArtBaseY = 1f;
    private static float sArtWroteX = Float.NaN, sArtWroteY = Float.NaN;

    /**
     * A single tap on the cover puts the wallpaper back, the way tapping Apple's full-bleed
     * artwork does. Tapping again brings the cover back.
     */
    private static volatile boolean sTapToggle = true;
    /**
     * Whether the wallpaper crossfades between the album cover and the lock wallpaper instead of
     * swapping in one frame. The fade itself runs in the wallpaper process, which is the only
     * place that holds both pictures; all this decides is whether to ask for it.
     */
    private static volatile boolean sFadeWp = true;
    /**
     * Set when the user tapped their way OUT of cover mode while the card was still up.
     *
     * Without it the very next metadata event puts the cover straight back: the card is the
     * switch, and the card has not gone anywhere. Cleared when the card actually goes away, so
     * the next thing the user plays starts from the cover again rather than inheriting a
     * decision that was made about a different song.
     */
    private static volatile boolean sTapSuppressed;
    private static GestureDetector sTapDetector;
    /** Set for the length of one gesture that started on the card's artwork and is ours. */
    private static boolean sArtSwallow;
    private static long sArtDownAt;
    private static boolean sArtLongPressed;
    private static float sArtDownX;
    private static float sArtDownY;
    /** Match Android's native long-press recognition; the OEM listener is swallowed upstream. */
    private static final long ART_LONG_PRESS_MS = android.view.ViewConfiguration.getLongPressTimeout();
    private static final Runnable sArtLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sArtSwallow || sArtLongPressed || !screenOn() || !keyguardShowing()) return;
            if (!wantsArtTap()) return;
            triggerArtworkLongPress();
        }
    };
    /** Px of forgiveness around the thumbnail. The card slides; fingers are not pixels. */
    private static final int ART_TAP_SLOP = 24;

    /**
     * How long a tap on the cover is held back before it means anything.
     *
     * GestureDetector reports a single tap the moment the finger lifts, and a fast double tap
     * is two gestures: the first one had already toggled the cover by the time the second
     * arrived, and MIUI's own double-tap-to-sleep is decided downstream of this hook
     * (KeyguardPanelViewInjector, over com.android.keyguard's DoubleTapHelper), so it cannot be
     * asked first. So the tap is armed instead and fired only once the double tap window has
     * passed. Reported as "double tap the lock screen and the wallpaper state has changed when
     * it comes back on".
     *
     * The framework's own timeout plus a margin. GestureDetector measures the first DOWN to the
     * second, MIUI measures the gap between the two taps, so the window it might still call a
     * double tap can end later than ours - the margin is that difference. Not a guess to be
     * trimmed without measuring first.
     */
    private static final long TAP_CONFIRM_MS =
            android.view.ViewConfiguration.getDoubleTapTimeout() + 60L;
    /** Armed by a tap on the cover, cancelled while it is still a candidate double tap. */
    private static Runnable sPendingTap;

    /**
     * Whether the gesture in flight began on the control centre.
     *
     * Recorded at ACTION_DOWN because every later test is too late: the centre is dismissed
     * during the gesture it is being dismissed by, so anything asked after that reads "no
     * centre" and hands the touch to the cover.
     */
    private static volatile boolean sGestureOnCentre;

    /**
     * The OEM's own miuix curves, read off AllInOneClockAnimation at runtime as
     * {dampingRatio, response}. miuix derives stiffness = (2*PI/response)^2 and
     * damping = 2*zeta*(2*PI/response) - confirmed against the dumped parameters[].
     */
    private static final float[] EASE_STATE_CHANGED = {0.88f, 0.38f}; // k=273.4  c=29.1
    private static final float[] EASE_DEFAULT       = {0.82f, 0.42f}; // k=223.8  c=24.5
    private static final float[] EASE_RUNNING       = {1.00f, 0.18f}; // k=1218.5 c=69.8

    /**
     * The curve the cover-mode transition runs on, and it is the OEM's own - the same numbers as
     * EASE_STATE_CHANGED above, which is the preset the system itself drives this transition
     * with. Named separately so the two can diverge if they ever have reason to, not because
     * they differ today.
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
    /** What the transition runs on. Starts at the OEM preset and moves with the slider. */
    static volatile float sClockResponse = EASE_COVER[1];

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
    private static long fadeMsFor(float response) {
        return Math.round(EASE_COVER_FADE_MS * response / EASE_COVER[1]);
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.attach(this);
    }

    /**
     * The modern API's equivalent of handleLoadPackage: called once the default class loader
     * exists, before the app's own components are built. The scope list narrows what we are
     * injected into, but the framework still reports every package loaded in those processes,
     * so the package name is checked here rather than trusted.
     */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Xp.attach(this);
        String pkg = param.getPackageName();
        if ("com.miui.miwallpaper".equals(pkg)) {
            WallpaperProbe.handle(param);
            return;
        }
        if (!"com.android.systemui".equals(pkg)) return;

        final ClassLoader cl = param.getDefaultClassLoader();
        Xp.log(TAG + "loaded into SystemUI");

        try {
            sContainerCls = Xp.findClass(CLS_CONTAINER, cl);
        } catch (Throwable t) {
            Xp.log(TAG + "KeyguardClockContainer not found, nothing to hook: " + t);
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

        try {
            Xp.hookAll(sContainerCls, "onAttachedToWindow", chain -> {
                Object result = chain.proceed();
                sContainer = (View) chain.getThisObject();
                captureScreenSize(sContainer);
                // The clock container attach is the first reliably-fired event after both
                // modules' onPackageLoaded, so arming the HyperLight counter-hook here - not
                // from setGlassColor, which this build never calls on the all_in_one clock -
                // guarantees it registers after HyperLight's own View.setMiGlass hook.
                armMiGlassGuard();
                Xp.log(TAG + "clock container attached: " + sContainer);
                try {
                    registerReceiver(sContainer.getContext().getApplicationContext());
                } catch (Throwable t) {
                    Xp.log(TAG + "registerReceiver failed: " + t);
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
                // ... and the shade's copy of the cover has to be recomposed for the same
                // reason even on the image path, where nothing else would notice it had gone:
                // sShadeArt is a static, so a restart takes it and only this puts it back.
                if ((sCoverMode || sStyle2Mode) && (sVideoWallpaper || sShadeArt == null)) {
                    if (sVideoWallpaper) sCover = null;
                    pushArtAsync(true, false);
                }
                // Re-apply on keyguard rebuild. Measured: the system never re-shows the
                // cut-out on its own, so no per-call ownership hook is warranted - hooking
                // View.setVisibility process-wide cost every visibility change in SystemUI and
                // fired zero times. What actually lost the state was SystemUI restarting.
                if (sDepthHidden) setDepthHidden(true);
                else if (sVideoWpOwed) setDepthHidden(false);
                // Same story for the card: the guard and the artwork's tap listener both live on
                // the view, so a rebuild takes them.
                if (sCoverMode || sStyle2Mode || wantsArtTap()) applyMediaCard();
                // A rebuilt keyguard can be a different clock style, so the nudge measured
                // against the old one means nothing - and neither does the clock view we resolved.
                sClockTargets.clear();
                forgetClockRoots();
                forgetGlyphBox();
                ClockCollapse.onAttached();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            LockLyrics.refresh();
                        } catch (Throwable t) {
                            Xp.log(TAG + "lyrics refresh failed: " + t);
                        }
                    }
                });
                return result;
            });
        } catch (Throwable t) {
            // Everything downstream hangs off this one: it is where the container is captured
            // and where the broadcast receiver is registered, so without it there is not even
            // an adb channel to ask what went wrong. Say so plainly rather than throwing out
            // of onPackageLoaded, which loses the message.
            Xp.log(TAG + "onAttachedToWindow hook FAILED, the module is inert in SystemUI: " + t);
        }

        // The keyguard is rebuilt on every screen-off/on, so a captured instance goes
        // stale. Drop it on detach and always drive the currently attached one.
        try {
            Xp.hookAll(sContainerCls, "onDetachedFromWindow", chain -> {
                Object result = chain.proceed();
                if (sContainer == chain.getThisObject()) {
                    ClockCollapse.onDetached();
                    sContainer = null;
                    Xp.log(TAG + "clock container detached");
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "onDetachedFromWindow hook failed: " + t);
        }

        // Keep lyric HDR mode on the shade window attributes SystemUI reapplies.
        try {
            Class<?> nsw = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowControllerImpl", cl);
            Xp.hookAll(nsw, "applyWindowLayoutParams", chain -> {
                Object self = chain.getThisObject();
                try {
                    LockLyrics.noteShadeWindow(self);
                    LockLyrics.applyHdrTo((android.view.WindowManager.LayoutParams)
                            Xp.getObjectField(self, "mLpChanged"));
                } catch (Throwable t) {
                    Xp.log(TAG + "lyrics HDR not applied: " + t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Xp.log(TAG + "shade window hook failed, lyrics stay SDR: " + t);
        }

        // Start sleep/wake ownership before the first affected keyguard frame is drawn.
        try {
            Class<?> ks = Xp.findClass("com.android.systemui.keyguard.KeyguardService$2", cl);
            Xp.hookAll(ks, "onStartedGoingToSleep", chain -> {
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        if (sCoverMode) ClockCollapse.toAod();
                    }
                });
                return result;
            });
            Xp.hookAll(ks, "onStartedWakingUp", chain -> {
                ClockCollapse.noteWaking();
                main().post(new Runnable() {
                    @Override
                    public void run() { MotionTrace.start("wake"); }
                });
                Object result = chain.proceed();
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        if (sCoverMode && ClockCollapse.phase() == ClockCollapse.Phase.AOD
                                && keyguardShowing()) {
                            ClockCollapse.enter(true, true);
                        }
                    }
                });
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "KeyguardService sleep/wake hooks failed, the clock cuts to and from "
                    + "the AOD: " + t);
        }

        try {
            Xp.hookAll(sContainerCls, "doAnimationToAod", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean toAod = args.length > 0 && Boolean.TRUE.equals(args[0]);
                if (sCoverMode && toAod) ClockCollapse.toAod();
                Object result = chain.proceed();
                if (sCoverMode && !toAod && ClockCollapse.leavingOrOff()) {
                    ClockCollapse.enter(true, true);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "AOD animation hook failed: " + t);
        }

        // The system does re-show the cut-out - verified: it came back right after we restored
        // cover mode across a SystemUI restart. Re-hide after the OEM's own update instead of
        // hooking View.setVisibility process-wide, which would tax every view in SystemUI.
        try {
            Class<?> depth = Xp.findClass(
                    "com.android.keyguard.depth.KeyguardDepthInteractor", cl);
            Xp.hookAll(depth, "updateDeductedImageView", chain -> {
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
            Xp.log(TAG + "depth ownership hook failed: " + t);
        }

        // The fingerprint ring, when the user has asked for it to go. Two hooks, because the
        // ring is two things: MiuiGxzwFrameAnimation plays the pulsing circle out of a list of
        // drawables, and MiuiGxzwIconView holds the static print underneath. Hiding either one
        // alone leaves the other on screen.
        //
        // Both install whatever the setting says and read the flag per call, so the switch takes
        // effect on the next draw rather than on the next SystemUI restart. And both are on
        // their own terms: a build that renamed one still gets the other.
        try {
            Class<?> anim = Xp.findClass(CLS_FOD_ANIM, cl);
            Xp.hookAll(anim, "draw", chain -> {
                Object[] args = chain.getArgs().toArray();
                // draw(int resId) is the frame. Any other overload is not ours to touch, which
                // the argument check below says without having to name the signature.
                if (sHideFp && args.length == 1 && args[0] instanceof Integer
                        && isFodRing((Integer) args[0])) {
                    // Substituting the drawable rather than skipping the draw: the animation
                    // keeps its own timing and its own lifecycle, it just paints nothing. A
                    // skipped draw would leave whatever the OEM expects to be on that surface.
                    args[0] = android.R.color.transparent;
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "fingerprint animation hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint animation hook failed, the ring will still pulse: " + t);
        }

        try {
            Class<?> iconCls = Xp.findClass(CLS_FOD_ICON, cl);
            Xp.hookAllConstructors(iconCls, chain -> {
                Object result = chain.proceed();
                try {
                    View v = (View) chain.getThisObject();
                    sFodIcons.put(v, Boolean.TRUE);
                    v.setAlpha(sHideFp ? 0f : 1f);
                } catch (Throwable ignored) {
                    // A view we cannot dim is a visible print, not a broken keyguard.
                }
                return result;
            });
            Xp.log(TAG + "fingerprint icon hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint icon hook failed, the static print will stay: " + t);
        }

        // Whether the notifications keep clear of that icon. Installed whatever the setting is,
        // and the mode is read per call, but unlike everything else here a change does not show
        // up immediately: this value is not one of the seven flows, so the bound is only
        // recomputed when one of those moves.
        try {
            // Fail on the panel rather than on the lambda: "no KeyguardPanelViewController" is a
            // different build, "no combine lambda" is a different R8 run, and the log should say
            // which one happened.
            Xp.findClass(CLS_KG_PANEL, cl);
            long t0 = android.os.SystemClock.uptimeMillis();
            Class<?> combine = findAvoidCombine(cl);
            java.lang.reflect.Method invoke = combine == null ? null : invoke3(combine);
            if (invoke == null) {
                Xp.log(TAG + "no nsslLockYPosition combine lambda on this build"
                        + " - fingerprint avoidance cannot be overridden");
            } else {
                Xp.hook(invoke, chain -> {
                    if (sFpAvoid != 0) {
                        try {
                            java.util.List<Object> a = chain.getArgs();
                            Object second = a.size() > 1 ? a.get(1) : null;
                            if (second instanceof Object[]) {
                                Object[] vals = (Object[]) second;
                                // Exactly seven, or the indices below mean something else. A
                                // build that combines a different number of flows gets left
                                // alone rather than having two unknown values overwritten.
                                if (vals.length == 7) {
                                    Boolean forced = sFpAvoid == 2;
                                    // 5 is "fingerprint unlock is on", 6 is "a print is
                                    // enrolled". Written in place: the array is the one the
                                    // original will read, so proceed() needs no new arguments.
                                    vals[5] = forced;
                                    vals[6] = forced;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                });
                Xp.log(TAG + "fingerprint avoidance hooked on " + combine.getName()
                        + " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
            }
        } catch (Throwable t) {
            Xp.log(TAG + "fingerprint avoidance hook failed: " + t);
        }

        // MIUI's own answer to "what kind of wallpaper is this", which beats every heuristic
        // below it: SystemUI keeps this class unobfuscated and it holds a live
        // MiuiWallpaperManager, whose getMiuiWallpaperType(which) is the API MIUI itself asks.
        // Captured from the constructor the same way the wallpaper engine is - it is a Dagger
        // singleton built during SystemUI startup, so the hook has to be in before that.
        try {
            Class<?> wp = Xp.findClass(CLS_KG_WALLPAPER_MANAGER, cl);
            Xp.hookAllConstructors(wp, chain -> {
                Object result = chain.proceed();
                sKgWallpaperMgr = chain.getThisObject();
                return result;
            });
            Xp.log(TAG + "keyguard wallpaper manager hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "keyguard wallpaper manager hook failed, falling back to "
                    + "MIUI's files for the wallpaper type: " + t);
        }

        // The media card is the switch. Every add, track change and dismissal of the
        // lockscreen card funnels through this one static - MediaData in means a card is on
        // screen (and which track), null means it has gone. Playback state never reaches it,
        // which is exactly what we want: pausing leaves the card up, so it leaves the cover up.
        try {
            Class<?> card = Xp.findClass(CLS_MEDIA_CARD, cl);
            Xp.hookAll(card, "access$setTopMediaData", chain -> {
                Object result = chain.proceed();
                java.util.List<Object> args = chain.getArgs();
                onCardChanged(args.size() > 1 ? args.get(1) : null);
                return result;
            });
            Xp.log(TAG + "media card hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "media card hook failed: " + t);
        }

        // A tap on the cover puts the wallpaper back.
        //
        // Hooked on the shade window's dispatch rather than given a view of our own: anything
        // clickable added over the keyguard swallows the ACTION_DOWN in its region, and
        // swipe-to-unlock is a drag that begins with exactly that ACTION_DOWN. Here the events
        // are only observed - the detector's answer is thrown away and proceed() runs
        // unconditionally - so every gesture still reaches the OEM as it did before.
        try {
            Class<?> shade = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowView", cl);
            Xp.hookAll(shade, "dispatchTouchEvent", chain -> {
                MotionEvent ev = null;
                try {
                    java.util.List<Object> a = chain.getArgs();
                    if (!a.isEmpty() && a.get(0) instanceof MotionEvent) {
                        ev = (MotionEvent) a.get(0);
                    }
                } catch (Throwable ignored) {
                }
                if (ev != null) {
                    // Whether this gesture began on the control centre, decided at the DOWN and
                    // nowhere else.
                    //
                    // onLockTap's own controlCentreUp() guard is evaluated a double-tap window
                    // later, and by then the centre can be gone: swiping up to dismiss it takes
                    // the centre out from under the check, so it read false and the tap went
                    // through as a tap on the cover. Measured symptom - dragging up at the clock
                    // and date left cover mode instead of closing the centre.
                    //
                    // At the DOWN the answer is unambiguous: a finger that lands while the centre
                    // is open is aimed at the centre, whatever the gesture turns out to be.
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        sGestureOnCentre = controlCenterUp();
                        if (sGestureOnCentre) cancelPendingTap("gesture began on the control centre");
                    }
                    // The one case this hook does more than watch. Returning true without
                    // proceeding takes the gesture out of the dispatch entirely, which is the
                    // only way the artwork can mean something other than "open the player".
                    try {
                        if (swallowArtTap(ev)) return Boolean.TRUE;
                    } catch (Throwable ignored) {
                    }
                    feedTap(ev);
                }
                return chain.proceed();
            });
            Xp.log(TAG + "lock screen tap hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "lock screen tap hook failed: " + t);
        }

        // The shade's cover layer - the album cover behind the notification panel, revealed by
        // a pull-down. Hooked on the CONSTRUCTOR and not on the dispatchTouchEvent above: that
        // hook only fires once a finger is already down, which is far too late to build a view
        // in. Same class, different method, and deliberately its own try/catch: a shade window
        // that cannot be found costs this feature and nothing else.
        //
        // Read the ShadeLayer header before touching this. The layer must end up immediately
        // below NotificationPanelView or MIUI's own scrims paint over it, and it must never be
        // made clickable, or it eats the swipe-to-unlock.
        try {
            Class<?> shadeRoot = Xp.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowView", cl);
            Xp.hookAllConstructors(shadeRoot, chain -> {
                Object result = chain.proceed();
                ShadeLayer.onWindowRoot(chain.getThisObject());
                return result;
            });
            Xp.log(TAG + "shade window hooked for the cover layer");
        } catch (Throwable t) {
            Xp.log(TAG + "shade cover layer hook failed, pulling the shade will show no cover: "
                    + t);
        }

        // The per-frame driver for everything the shade layer draws.
        //
        // Matched by Class identity and NOT by a name string. The module this is ported from
        // compared `getClass().getName()` against a literal on every frame of every Folme
        // animation in SystemUI - which is one rename away from receiving no frames at all,
        // with a hook that installs cleanly and reports nothing.
        //
        // Its own try/catch, and the loudest message in the feature: if this one is missing
        // the layer can still be placed but nothing can animate it.
        try {
            sExpansionCls = Xp.findClass(CLS_EXPANSION_ANIMATOR, cl);
            Class<?> flow = Xp.findClass(CLS_FLOAT_FLOW, cl);
            Xp.hookAll(flow, "setValue", chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject().getClass() == sExpansionCls) {
                    java.util.List<Object> a = chain.getArgs();
                    Object v = a.size() > 1 ? a.get(1) : null;
                    if (v instanceof Float) ShadeLayer.onExpansion((Float) v);
                }
                return result;
            });
            Xp.log(TAG + "shade expansion driver hooked (" + CLS_FLOAT_FLOW + ".setValue)");
        } catch (Throwable t) {
            Xp.log(TAG + "shade expansion driver NOT hooked - the cover layer can be placed but "
                    + "nothing will animate it: " + t);
        }

        // The blur half: kill the background zoom, then drive the window blur from the spring.
        //
        // Two hooks on the same class, each with its own catch, because they cost different
        // things: without the constructor one the picture still shrinks as it is pulled (a
        // visible wrongness), and without the ratio one the blur just snaps instead of trailing.
        try {
            Class<?> provider = Xp.findClass(CLS_BLUR_PROVIDER, cl);

            Xp.hookAllConstructors(provider, chain -> {
                Object result = chain.proceed();
                ShadeBlur.onProvider(chain.getThisObject());
                return result;
            });

            // The argument is substituted, not the method. MIUI's own body still runs, so the
            // glass-material ratio it computes inside is untouched - replacing the method
            // wholesale is the version that leaves the shade's glass grey.
            Xp.hookAll(provider, "setBlurRatio", chain -> {
                if (ShadeBlur.isWindow(chain.getThisObject()) && ShadeLayer.driving()) {
                    java.util.List<Object> a = chain.getArgs();
                    if (a.size() == 1 && a.get(0) instanceof Float) {
                        Object[] args = a.toArray();
                        args[0] = Float.valueOf(ShadeLayer.ratio());
                        return chain.proceed(args);
                    }
                }
                return chain.proceed();
            });
            Xp.log(TAG + "shade blur provider hooked (" + CLS_BLUR_PROVIDER + ")");
        } catch (Throwable t) {
            Xp.log(TAG + "shade blur hook failed: " + t);
        }

        // The curtain follows the FINGER, not the panel's expansion.
        //
        // Measured on device without this: the edge ran several times ahead of the hand,
        // because `expansion` is not proportional to how far the finger has travelled - pulling
        // a little opens the panel a lot. The module this is ported from reaches for raw touch
        // for exactly this reason, and its own notes record one false alarm about it: an
        // apparent "rawY stops updating" turned out to be an `adb shell input motionevent`
        // artefact, where each synthetic event is a separate process and only the DOWN reaches
        // the handler. A real finger is fine.
        //
        // The handler is observed AFTER the original runs, so `isTracking()` describes this
        // event rather than the previous one - and it is never overridden.
        try {
            Class<?> controller = Xp.findClass(CLS_PANEL_CONTROLLER, cl);
            Xp.hookAllConstructors(controller, chain -> {
                Object result = chain.proceed();
                ShadeLayer.onPanelController(chain.getThisObject());
                return result;
            });

            Class<?> touch = Xp.findClass(CLS_PANEL_TOUCH, cl);
            Xp.hookAll(touch, "onTouchEvent", chain -> {
                Object result = chain.proceed();
                java.util.List<Object> a = chain.getArgs();
                if (!a.isEmpty() && a.get(0) instanceof MotionEvent) {
                    ShadeLayer.onTouch((MotionEvent) a.get(0));
                }
                return result;
            });
            Xp.log(TAG + "shade touch follow hooked (" + CLS_PANEL_TOUCH + ")");
        } catch (Throwable t) {
            Xp.log(TAG + "shade touch follow NOT hooked - the curtain will follow the panel's "
                    + "expansion instead of the finger, and will run ahead of it: " + t);
        }

        // Card glass that samples the cover rather than the app behind the shade.
        //
        // Hooked here and not on the provider because a notification row has no blur radius of
        // its own: applyElementViewBlend only calls setMiViewBlurMode(1), which REUSES the
        // window-level blur - and that one samples what is behind the window, which is the app.
        // Suppressing the window blur on the row therefore does nothing at all, and was the
        // first two attempts at this in the module this is ported from. The row has to be given
        // a blur of its own, which is what MiBlur.applyLocalBlur does.
        //
        // WHERE the write happens is the part that took three attempts, and it is not here.
        //
        // It was here, at prepare time, and that is what cost the lock screen its cards twice
        // over: a row is the SAME view on the keyguard and in the notification centre, prepare
        // time cannot tell which one it is being prepared for, and the material outlives the
        // prepare. Measured on device: 21 rows written, all of them while the keyguard was up,
        // and every one of them wrong there until the writes were taken back.
        //
        // This hook now only RECORDS which view the system considers a card's material - that
        // choice is still the system's, and re-deriving it here would be the module inventing
        // its own answer. The write moved to setCardBlurActive(), driven by the edges of the
        // shade being open, which is the only moment in the module where "the notification
        // centre is up" is answerable and where the tree is assembled enough to ask whether a
        // view is under the panel. Asking that at prepare time is what failed twice.
        //
        // Behind a switch and off by default: it writes OEM blur material on every row, and a
        // full-screen cover behind the cards is not obviously better than the app behind them.
        try {
            Class<?> notifUtil = Xp.findClass(CLS_NOTIF_UTIL, cl);
            // Kept for rowClass(). The classloader a VIEW carries is the one that loaded ITS
            // class, and the utility hands this hook framework views too - an ImageView's loader
            // is the boot loader, which cannot see a single SystemUI class. Resolving the row
            // class through it fails, rowOf() then answers null for everything, and the whole
            // feature goes quiet without a single write being attempted. Measured: 15 failures
            // and not one card touched.
            sHostLoader = notifUtil.getClassLoader();
            Xp.hookAll(notifUtil, "applyElementViewBlend", chain -> {
                Object result = chain.proceed();
                if (sBlendCalls.incrementAndGet() == 1) {
                    Xp.log(TAG + "applyElementViewBlend is being called");
                }
                // Recorded, never written. The switch is asked at the sweep instead, so that
                // turning it on does not have to wait for every row to be prepared again.
                java.util.List<Object> a = chain.getArgs();
                if (a.size() > 1 && a.get(1) instanceof View) {
                    final View v = (View) a.get(1);
                    // Recorded, and NOT written - the write is the sweep's, at the edges of the
                    // shade being open. The scope here is only "could this ever be one of our
                    // cards", because the finer question is unanswerable at prepare time:
                    //
                    //  - a heads-up notification is never ours to restyle, and it is a row, so
                    //    it can be named and dropped here;
                    //  - a row's material is the ordinary case;
                    //  - a view with NO row used to be dropped, and that was wrong: the media
                    //    card is not a notification row and its background is handed to this very
                    //    method. Which of the row-less views is the media card is asked at the
                    //    sweep, where the tree exists and can answer.
                    final View row = rowOf(v);
                    if (row == null) noteCandidate(v, null);
                    else if (!isHeadsUp(row)) noteCandidate(v, row);
                } else if (sBadArgs.incrementAndGet() == 1) {
                    Xp.log(TAG + "applyElementViewBlend: no View in arg 1 ("
                            + (a.size() > 1 && a.get(1) != null
                                    ? a.get(1).getClass().getName() : "null") + ")");
                }
                return result;
            });
            Xp.log(TAG + "notification row material hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "notification row material hook failed - the cards will keep sampling "
                    + "the app behind the shade: " + t);
        }

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
        // held at this level instead: learned because coverProgress() has nothing else to
        // measure against (sLastSystemY stays NaN for the life of the process otherwise), and
        // held because the system's own re-assertions arrive here on these styles and would
        // otherwise pull the clock back out of the collapse every few frames.
        try {
            Xp.hookAll(sContainerCls, "notifStateChange", chain -> {
                // Anyone calling this is by definition the live instance.
                sContainer = (View) chain.getThisObject();
                if (findClockView(sContainer, "time_group") != null) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Not while the clock is being held: on these styles our own spring is what
                // puts the y here, and recording our own target as "what the system asked for"
                // would leave coverProgress() measuring against the hold itself.
                if (sHoldY == null && !sSelfDriving) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "notifStateChange hook failed: " + t);
        }

        // The OEM squeezes the clock through these four setters on TimeView. Scaling
        // setSizeInternal's argument shrinks the whole clock without touching the animation:
        // the OEM still computes and drives every frame, we just rescale the last mile.
        try {
            Class<?> timeView = Xp.findClass(CLS_TIME_VIEW, cl);
            XposedInterface.Hooker axisLog = chain -> {
                String name = chain.getExecutable().getName();
                Object[] args = chain.getArgs().toArray();
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
                Xp.hookAll(timeView, m, axisLog);
            }
            Xp.hookAll(timeView, "onDraw", chain -> {
                Object result = chain.proceed();
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
            Xp.hookAll(timeView, "setGlassColor", chain -> {
                View self = (View) chain.getThisObject();
                if (!glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
            Xp.hookAll(timeView, "setTextColor", chain -> {
                View self = (View) chain.getThisObject();
                if (!glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "TimeView hook failed: " + t);
        }

        // The date line above the clock. Same palette, same cover behind it, and its own class:
        // the glass never touches it and it takes a plain colour, so it needs a hook of its own
        // rather than riding the TimeView one. Its setter is the only way in - setTextColor
        // forwards to setDateTextColor, which is where the AOD and blur-blend variants are
        // chosen, so hooking the outer one covers every path.
        try {
            Class<?> textArea = Xp.findClass(CLS_TEXT_AREA, cl);
            Xp.hookAll(textArea, "setTextColor", chain -> {
                View self = (View) chain.getThisObject();
                if (!glassStyleFor(self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = legible(self, (Integer) args[0]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            Xp.log(TAG + "date colour hook failed: " + t);
        }

        // The real chokepoint. Everything that squeezes the clock - the OEM's own
        // notification-Y flow, KeyguardClockContainer.notifStateChange, and our own
        // per-frame driving - lands here, so this is where ownership is enforced.
        try {
            Class<?> interactor = Xp.findClass(CLS_INTERACTOR, cl);
            Xp.hookAll(interactor, "setNotifY", chain -> {
                Object[] args = chain.getArgs().toArray();
                float requested = (Float) args[0];
                // Only the system's own emissions tell us where the clock really
                // belongs; our own frames must not be mistaken for that.
                if (!sSelfDriving) sLastSystemY = requested;
                Float hold = sHoldY;
                if (hold != null && requested != hold) args[0] = hold;
                // The coerced Y has to reach the original, which is what proceed(args) is for:
                // this is the one hook whose whole purpose is rewriting an argument.
                Object result = chain.proceed(args);
                // AFTER proceed, not before, and that is not tidiness - it is the difference
                // between a date that jitters and one that does not.
                //
                // The placement reads the date's position off the screen, and the OEM applies
                // this frame's own squeeze translation inside the call above. Run before it and
                // every reading describes the PREVIOUS frame, so the compensation we write is
                // one frame late and the date comes out at `target + (O_n - O_{n-1})` - it is
                // displaced by whatever the OEM moved by that frame. Smooth styles move a few
                // pixels a frame and nobody can see it; the two-row style steps 68px in a single
                // frame when it swaps layout (measured, oemTrans -16.5 -> -84.2 between p=0.861
                // and p=0.887), and that reads as the date shaking.
                //
                // The notifStateChange hook for every other style has always done it this way
                // round, which is why the jitter only ever showed on all_in_one.
                //
                // It is NECESSARY BUT NOT SUFFICIENT: measured after the move, the rendered date
                // still steps 26-50px on a frame and reverses 11 times over one entry. The OEM
                // does not apply its translation synchronously inside proceed - Folme does it
                // later in the frame - so the reading is still one frame behind. Reading current
                // geometry needs a pre-draw callback, where every transform for the frame has
                // already been applied; see the note in HANDOFF section 10.2.
                if (sVerbose) {
                    Xp.log(TAG + "setNotifY(" + args[0] + ") -> " + result);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.log(TAG + "interactor hook failed: " + t);
        }
    }

    static void saveState() {
        if (sAppCtx == null) return;
        try {
            java.io.FileOutputStream f =
                    new java.io.FileOutputStream(new java.io.File(sAppCtx.getFilesDir(), STATE_FILE));
            f.write(("cover=" + (sCoverMode ? 1 : 0)
                    + "\nbias=" + sBias
                    // A pending pre-dp value is written as itself: it cannot be converted until
                    // a confirmed box exists, and writing the default over it would lose the
                    // setting the user actually had.
                    + "\nclock=" + (Float.isNaN(sClockLegacyK) ? sClockHeightDp : sClockLegacyK)
                    + (Float.isNaN(sClockSize) ? "" : "\nclocksize=" + sClockSize)
                    + "\nclockoff=" + sClockOffsetDp
                    + (ClockCollapse.fullUnitState() == null ? ""
                            : "\nclockfull=" + ClockCollapse.fullUnitState())
                    + "\nglass=" + sGlassEnd
                    + "\nspring=" + sClockResponse
                    + "\nmcart=" + (sMcHideArt ? 1 : 0)
                    + "\nmctext=" + (sMcCenterText ? 1 : 0)
                    + "\nmctap=" + (sMcTitleTap ? 1 : 0)
                    + "\ntap=" + (sTapToggle ? 1 : 0)
                    + ShadeLayer.dumpCfg()
                    + "\nfadewp=" + (sFadeWp ? 1 : 0)
                    + "\nhidefp=" + (sHideFp ? 1 : 0)
                    + "\nlyrics=" + (LockLyrics.sEnabled ? 1 : 0)
                    + "\nlyrickeep=" + (LockLyrics.sKeepOn ? 1 : 0)
                    + "\nlyrichdr=" + (LockLyrics.sHdr ? 1 : 0)
                    + "\nfpavoid=" + sFpAvoid
                    // Not a setting - a measurement. Kept so the app's preview is to scale from
                    // the first frame after a SystemUI restart, instead of only once the phone
                    // has been locked again.
                    + "\ncardrect=" + sCardL + "," + sCardT + "," + sCardW + "," + sCardH
                    + "\n").getBytes());
            f.close();
        } catch (Throwable t) {
            Xp.log(TAG + "saveState failed: " + t);
        }
    }

    private static final Runnable sSaveState = new Runnable() {
        @Override
        public void run() { saveState(); }
    };

    /**
     * For values that change as fast as the screen draws. The card rectangle is sampled from a
     * pre-draw listener while the card is animating in, and writing the file on every frame of
     * that would be a file write per frame for a number that settles in a few hundred ms.
     */
    private static void saveStateSoon() {
        main().removeCallbacks(sSaveState);
        main().postDelayed(sSaveState, 500L);
    }

    private static void loadState() {
        if (sAppCtx == null) return;
        java.io.File f = new java.io.File(sAppCtx.getFilesDir(), STATE_FILE);
        boolean migratingLegacy = false;
        if (!f.exists()) {
            // Preserve settings from versions that shared mc_cover_state with HyperMusicCover.
            // Read it once, then save only to the namespaced file. The legacy file belongs to
            // the other module now and must not be deleted or rewritten during an upgrade.
            java.io.File legacy = new java.io.File(sAppCtx.getFilesDir(), LEGACY_STATE_FILE);
            if (legacy.exists()) {
                f = legacy;
                migratingLegacy = true;
            }
        }
        if (!f.exists()) {
            resetMusicPresentation("process start without state", false);
            return;
        }
        boolean persistedCover = false;
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            String body = new String(buf, 0, Math.max(0, n)).trim();
            // The first version of this file was a bare "1"/"0"; keep reading those so an
            // upgrade does not silently drop a cover that is still on the phone.
            if (body.length() <= 1) {
                persistedCover = "1".equals(body);
            } else {
                for (String line : body.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if ("cover".equals(k)) persistedCover = "1".equals(v);
                    // "auto" was a stored setting; following the card is unconditional now.
                    else if ("bias".equals(k)) sBias = Float.parseFloat(v);
                    else if ("clock".equals(k)) setClockHeightDp(Float.parseFloat(v));
                    else if ("clocksize".equals(k)) setClockSize(Float.parseFloat(v));
                    else if ("clockoff".equals(k)) setClockOffsetDp(Float.parseFloat(v));
                    else if ("clockfull".equals(k)) ClockCollapse.restoreFullUnit(v);
                    else if ("glass".equals(k)) sGlassEnd = Float.parseFloat(v);
                    // Through the setter, the way "clock" is: the clamp and the push to the
                    // wallpaper process are both part of reading the value back.
                    else if ("spring".equals(k)) setClockResponse(Float.parseFloat(v));
                    else if ("mcart".equals(k)) sMcHideArt = "1".equals(v);
                    else if ("mctext".equals(k)) sMcCenterText = "1".equals(v);
                    else if ("mctap".equals(k)) sMcTitleTap = "1".equals(v);
                    else if ("tap".equals(k)) sTapToggle = "1".equals(v);
                    else if ("fadewp".equals(k)) sFadeWp = "1".equals(v);
                    else if ("hidefp".equals(k)) sHideFp = "1".equals(v);
                    else if ("lyrics".equals(k)) LockLyrics.sEnabled = "1".equals(v);
                    else if ("lyrickeep".equals(k)) LockLyrics.sKeepOn = "1".equals(v);
                    else if ("lyrichdr".equals(k)) LockLyrics.sHdr = "1".equals(v);
                    else if ("fpavoid".equals(k)) sFpAvoid = Integer.parseInt(v);
                    else if (k.startsWith("shade_")) {
                        // The whole shade settings page, in one prefix - the keys and their
                        // meaning belong to ShadeLayer.configure.
                        //
                        // Parsed in its OWN try, and that is not tidiness. This whole loop sits
                        // inside one try whose catch RETURNS - so a single unreadable value here
                        // would take cover mode down with it, silently, and the log would say
                        // "loadState failed" without ever naming the key. The user would be left
                        // with a lock screen that had quietly stopped working.
                        try {
                            ShadeLayer.configure(k.substring(6), Integer.parseInt(v));
                        } catch (Throwable ignored) {
                            Xp.log(TAG + "shade setting unreadable, keeping the default: "
                                    + k + "=" + v);
                        }
                    }
                    else if ("cardrect".equals(k)) {
                        String[] r = v.split(",");
                        // Same sanity check the sampler applies, because a file written before
                        // it existed can hold a reading taken from the shade.
                        if (r.length == 4 && Integer.parseInt(r[1]) >= sScreenH / 3) {
                            sCardL = Integer.parseInt(r[0]); sCardT = Integer.parseInt(r[1]);
                            sCardW = Integer.parseInt(r[2]); sCardH = Integer.parseInt(r[3]);
                        }
                    }
                    // "offdelay" was the pause timer, before the card became the switch.
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "loadState failed: " + t);
            resetMusicPresentation("process start with unreadable state", false);
            return;
        }
        Xp.log(TAG + "state restored: staleCover=" + persistedCover + " bias=" + sBias);
        // Presentation is process-local and gesture-selected. Persisting it caused a restarted
        // SystemUI to restore the wallpaper independently of the Kotlin presentation bridge,
        // producing cover wallpaper + normal media card + lockscreen island at the same time.
        // Always start from the single normal-lockscreen baseline and invalidate any delayed art
        // push left over from restoring the state file.
        resetMusicPresentation("process start", false);
        if (migratingLegacy) {
            // Complete the one-way migration after parsing. This writes the exact settings that
            // HyperChanger understands to its private file and leaves HyperMusicCover's legacy
            // file untouched for its own upgrade path.
            saveState();
        }
    }

    private static synchronized void registerReceiver(Context ctx) {
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;
        sAppCtx = ctx;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String op = i.getStringExtra("op");
                if (op == null) op = "drive";
                Xp.log(TAG + "recv op=" + op + " extras=" + i.getExtras());
                try {
                    boolean anim = i.getBooleanExtra("anim", true);
                    String type = i.getStringExtra("type");
                    if (type == null) type = "STATE_CHANGED";

                    // No "ms" extra -> spring (what the OEM actually uses).
                    // With "ms" -> fixed-duration curve, for A/B comparison.
                    int ms = i.getIntExtra("ms", 0);
                    float[] e = easeByName(i.getStringExtra("ease"));
                    float zeta = i.getFloatExtra("zeta", e[0]);
                    float response = i.getFloatExtra("response", e[1]);

                    if ("info".equals(op)) {
                        dumpInfo();
                    } else if ("anim".equals(op)) {
                        dumpAnimConfigs();
                    } else if ("hold".equals(op)) {
                        float y = i.getFloatExtra("y", -1f);
                        Xp.log(TAG + "hold -> y=" + y + " from " + currentY()
                                + (ms > 0 ? " over " + ms + "ms" : " spring zeta=" + zeta + " response=" + response));
                        if (!anim) { sHoldY = y; drive(y, false, type); }
                        else if (ms > 0) rampTo(y, ms, type);
                        else springTo(y, zeta, response, type, false);
                    } else if ("gscale".equals(op)) {
                        String gid = i.getStringExtra("id");
                        groupScale(gid == null ? "time_group" : gid, i.getFloatExtra("k", 1f),
                                i.getFloatExtra("px", -1f), i.getFloatExtra("py", -1f),
                                i.getFloatExtra("ty", 0f));
                    } else if ("cls".equals(op)) {
                        String name = i.getStringExtra("name");
                        if (name == null) dumpClockStyleInfo();
                        else dumpClass(name, i.getStringExtra("grep"));
                    } else if ("ctint".equals(op)) {
                        // The cover's colour, by hand, so a tint can be tried without hunting for
                        // the artwork that would produce it.
                        if (i.getBooleanExtra("off", false)) {
                            sCoverTintOverride = 0;
                            Xp.log(TAG + "cover tint back to measured #"
                                    + Integer.toHexString(sCoverTint));
                        } else {
                            // A hex string rather than an int extra: `am --ei` parses with
                            // Integer.valueOf, which does not take `0x`, and "#86a6b1" is how a
                            // colour is written everywhere else in this file.
                            String v = i.getStringExtra("v");
                            try {
                                sCoverTintOverride = v == null ? 0xff8888ff
                                        : (int) Long.parseLong(v.replace("#", ""), 16);
                            } catch (Throwable t) {
                                Xp.log(TAG + "ctint: cannot read " + v);
                                return;
                            }
                            Xp.log(TAG + "cover tint forced to #"
                                    + Integer.toHexString(sCoverTintOverride));
                        }
                        recolorClock();
                    } else if ("gdata".equals(op)) {
                        pokeGlassData(i.getIntExtra("idx", -1), i.getFloatExtra("v", 0f));
                    } else if ("depth".equals(op)) {
                        setDepthHidden(!i.getBooleanExtra("on", true));
                    } else if ("pushart".equals(op)) {
                        boolean on = i.getBooleanExtra("on", true);
                        if (i.hasExtra("bias")) sBias = clamp01(i.getFloatExtra("bias", sBias));
                        sTrackKey = on ? trackKey(pickController(c)) : "";
                        setCoverEnabled(on, anim, false);
                    } else if ("wphello".equals(op)) {
                        boolean composes = i.getBooleanExtra("composes", false);
                        LockLyrics.resendBlur();
                        if (composes != sWpComposes) {
                            sWpComposes = composes;
                            Xp.log(TAG + "wallpaper process "
                                    + (composes ? "composes covers itself, sending the source"
                                    : "wants the composed JPEG"));
                        }
                    } else if ("needart".equals(op)) {
                        // The wallpaper process came up with nothing to draw - see
                        // WallpaperProbe.askForArt(). It asks once per process start, and there
                        // is a floor between two asks and a ceiling on how many, so this cannot
                        // loop. What it may well be holding is the previous track's cover off
                        // its own disk, which is a state it cannot tell from the right one.
                        String why = i.getStringExtra("why");
                        if (!sCoverMode && !sStyle2Mode) {
                            // NOT ignored, and that was the bug behind "the wallpaper will not
                            // change back". Measured: the wallpaper process says `no art here,
                            // asked SystemUI for it`, cover mode is off, and this branch dropped
                            // the request on the floor - so the process kept the last cover
                            // texture it had been given and the lock screen showed that album
                            // art for ever, across every track, with nothing left to replace it.
                            // Asking with cover mode off means exactly one thing: hand it back.
                            //
                            // Rate limited because the answer is another broadcast and a
                            // wallpaper with an empty lock slot has nothing to reload either -
                            // without this, an empty slot and an asking wallpaper would trade
                            // messages.
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sNeedArtOffAt > 3000L) {
                                sNeedArtOffAt = now;
                                Xp.log(TAG + "needart (" + why + ") with cover off: "
                                        + "telling the wallpaper to drop it and reload");
                                pushArtAsync(false, false);
                            }
                        } else {
                            Xp.log(TAG + "needart (" + why + "), resending the art");
                            // Not a track change: take whatever the session has now, without the
                            // same-artwork check that is what swallowed the later pushes.
                            pushArtAsync(true, false);
                        }
                    } else if ("mediacard".equals(op)) {
                        if (i.hasExtra("hideart")) sMcHideArt = i.getBooleanExtra("hideart", false);
                        if (i.hasExtra("centertext")) {
                            sMcCenterText = i.getBooleanExtra("centertext", false);
                        }
                        if (i.hasExtra("titletap")) {
                            sMcTitleTap = i.getBooleanExtra("titletap", false);
                        }
                        saveState();
                        Xp.log(TAG + "media card hideArt=" + sMcHideArt
                                + " centerText=" + sMcCenterText
                                + " titleTap=" + sMcTitleTap);
                        applyMediaCard();
                    } else if ("texfit".equals(op)) {
                        // The screen-sized keyguard texture, on by default (WallpaperProbe's half
                        // is the one that does the work). Flipping it here flips that half too,
                        // because the two have to agree: with it on, the re-fit that rewrites the
                        // user's lockscreen wallpaper is skipped; with it off, that re-fit is the
                        // only thing keeping the crossfade affordable.
                        sTexFit = i.getBooleanExtra("on", !sTexFit);
                        Intent wp = wallpaperIntent("texfit");
                        wp.putExtra("on", sTexFit);
                        c.sendBroadcast(wp);
                        Xp.log(TAG + "texture fit to screen " + (sTexFit ? "ON" : "off")
                                + " (wallpaper re-fit " + (sTexFit ? "skipped" : "enabled") + ")");
                    } else if ("lyrics".equals(op)) {
                        boolean on = i.getBooleanExtra("on", !LockLyrics.sEnabled);
                        LockLyrics.setEnabled(on, sTrackKey, sWatched);
                        saveState();
                    } else if ("lyricsmode".equals(op)) {
                        // The SystemUI media button owns this combined transition. Enabling the
                        // renderer first lets enterCoverMode() attach the lyric layer; disabling
                        // it first guarantees the stock lockscreen is restored with no lyric view
                        // surviving the cover exit.
                        boolean on = i.getBooleanExtra("on", true);
                        // Lyrics mode deliberately keeps automatic cover entry disabled, but it
                        // still needs the active-session listener: a player can be force-closed
                        // without sending the media-card removal callback, and the empty session
                        // list is then the only reliable signal that its wallpaper must be cleared.
                        if (on) startSessionWatch();
                        MediaController lyricController = on ? pickController(c) : sWatched;
                        if (on) sTrackKey = trackKey(lyricController);
                        LockLyrics.setEnabled(on, sTrackKey, lyricController);
                        setCoverEnabled(on, anim, false);
                        if (!on) sTrackKey = "";
                        saveState();
                    } else if ("lyrichdr".equals(op)) {
                        LockLyrics.sHdr = i.getBooleanExtra("on", !LockLyrics.sHdr);
                        Xp.log(TAG + "lyrics HDR highlight: " + LockLyrics.sHdr);
                        LockLyrics.refresh();
                        saveState();
                    } else if ("lyricinfo".equals(op)) {
                        setResultData(LockLyrics.dumpMetadata(c, sWatched));
                    } else if ("lyrickeep".equals(op)) {
                        LockLyrics.sKeepOn = i.getBooleanExtra("on", !LockLyrics.sKeepOn);
                        Xp.log(TAG + "lyrics keep the screen on: " + LockLyrics.sKeepOn);
                        LockLyrics.refresh();
                        saveState();
                    } else if ("lyricdemo".equals(op)) {
                        if (i.getBooleanExtra("clear", false)) {
                            LockLyrics.endDemo(sTrackKey, sWatched);
                        } else {
                            String id = i.getStringExtra("id");
                            LockLyrics.demo(id == null ? "101126" : id,
                                    i.getBooleanExtra("am", false));
                        }
                    } else if ("lyricstate".equals(op)) {
                        String st = LockLyrics.describe();
                        Xp.log(TAG + "lyrics: " + st);
                        setResultData(st);
                    } else if ("jerk".equals(op)) {
                        // Read-only probe: reports how far the clock's drawn position moves in
                        // one frame. See jerk(). Costs a getLocationOnScreen per clock root per
                        // frame while it is on, so it is off unless asked for.
                        sJerkProbe = i.getBooleanExtra("on", true);
                        Xp.log(TAG + "jerk probe " + (sJerkProbe ? "on" : "off"));
                    } else if ("hidefp".equals(op)) {
                        sHideFp = i.getBooleanExtra("on", !sHideFp);
                        saveState();
                        Xp.log(TAG + "hide fingerprint " + (sHideFp ? "on" : "off"));
                        applyHideFp();
                    } else if ("fpavoid".equals(op)) {
                        sFpAvoid = i.getIntExtra("mode", 0);
                        saveState();
                        // Nothing to re-apply: the bound is recomputed when one of the seven
                        // flows changes and this flag is not one of them, so it lands on the
                        // next recompute - in practice the next time the screen goes off.
                        Xp.log(TAG + "fingerprint avoid mode=" + sFpAvoid
                                + " (applies on the next recompute)");
                    } else if ("fadewp".equals(op)) {
                        sFadeWp = i.getBooleanExtra("on", !sFadeWp);
                        saveState();
                        Xp.log(TAG + "wallpaper crossfade " + (sFadeWp ? "on" : "off"));
                    } else if ("tap".equals(op)) {
                        sTapToggle = i.getBooleanExtra("on", !sTapToggle);
                        saveState();
                        Xp.log(TAG + "tap-to-toggle " + (sTapToggle ? "on" : "off"));
                    } else if ("clockshot".equals(op)) {
                        // For telling the two candidate causes apart by hand: with this off the
                        // module never draws the live clock, and the preview falls back to its
                        // outline. If the lock screen still breaks up, the capture is innocent.
                        sClockShot = i.getBooleanExtra("on", true);
                        Xp.log(TAG + "clock capture " + (sClockShot ? "on" : "off"));
                    } else if ("preview".equals(op)) {
                        setResultExtras(previewBundle(c, i));
                    } else if ("bias".equals(op)) {
                        setBias(i.getFloatExtra("v", DEFAULT_BIAS));
                    } else if ("clockscale".equals(op)) {
                        setClockHeightDp(i.getFloatExtra("v", DEFAULT_CLOCK_HEIGHT_DP));
                        saveState();
                        ClockCollapse.refresh();
                    } else if ("clocksize".equals(op)) {
                        setClockSize(i.getFloatExtra("v", 1f));
                        saveState();
                        ClockCollapse.refresh();
                    } else if ("clockoffset".equals(op)) {
                        setClockOffsetDp(i.getFloatExtra("v", 0f));
                        saveState();
                        ClockCollapse.refresh();
                    } else if ("glassend".equals(op)) {
                        sGlassEnd = clamp01(i.getFloatExtra("v", DEFAULT_GLASS_END));
                        saveState();
                        Xp.log(TAG + "glass end = " + sGlassEnd);
                        ClockCollapse.refresh();
                    } else if ("clockspring".equals(op)) {
                        // Nothing forced on screen: see setClockResponse(). The fades it pushes
                        // are the only thing outside this process.
                        setClockResponse(i.getFloatExtra("v", EASE_COVER[1]));
                    } else if ("auto".equals(op)) {
                        setAuto(i.getBooleanExtra("on", true));
                    } else if ("reload".equals(op)) {
                        requestWallpaperReload(c);
                    } else if ("lockwp".equals(op)) {
                        final Context cc = sAppCtx;
                        final boolean clear = i.getBooleanExtra("clear", false);
                        final boolean force = i.getBooleanExtra("force", false);
                        worker().post(new Runnable() {
                            @Override
                            public void run() {
                                if (clear) clearLockWallpaper(cc);
                                else ensureLockWallpaper(cc, force);
                            }
                        });
                    } else if ("cover".equals(op)) {
                        if (i.getBooleanExtra("on", true)) {
                            sCoverWanted = true;
                            attachCover();
                        } else {
                            // Through setCoverEnabled, exactly like a tap, and NOT detachCover()
                            // directly. detachCover() only takes our own keyguard view away; the
                            // cover itself is a texture in the wallpaper process, and handing
                            // that back is pushArtAsync(false) inside setCoverEnabled. Calling
                            // the short one leaves the album art on the lock screen for good -
                            // seen on device, after a measurement session that toggled cover mode
                            // with this probe and then could not get the wallpaper back.
                            sCoverWanted = false;
                            setCoverEnabled(false, false, false);
                        }
                    } else if ("style2".equals(op)) {
                        boolean on = i.getBooleanExtra("on", true);
                        if (on) enterStyle2Mode();
                        else exitStyle2Mode();
                    } else if ("glassmorph".equals(op)) {
                        if (i.getBooleanExtra("off", false)) {
                            sGlassV0 = sGlassV1 = Float.NaN;
                            sAppliedGlassV = Float.NaN;
                            callOnClockViews("updateGlassValue", "f", 0f, 0, false);
                            Xp.log(TAG + "glass morph off");
                        } else {
                            sGlassV0 = i.getFloatExtra("v0", 0f);
                            sGlassV1 = i.getFloatExtra("v1", 1f);
                            Xp.log(TAG + "glass morph " + sGlassV0 + " -> " + sGlassV1);
                        }
                    } else if ("collapse".equals(op)) {
                        float k = i.getFloatExtra("k", -1f);
                        sCollapseMin = k > 0 ? k : Float.NaN;
                        Xp.log(TAG + "collapse min scale = " + sCollapseMin);
                        if (Float.isNaN(sCollapseMin)) groupScale("time_group", 1f, -1f, 0f, 0f);
                    } else if ("callclock".equals(op)) {
                        callOnClockViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                                i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                                i.getBooleanExtra("b", false));
                    } else if ("call".equals(op)) {
                        callOnTimeViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                                i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                                i.getBooleanExtra("b", false));
                    } else if ("glass".equals(op)) {
                        setGlass(i.getBooleanExtra("on", false));
                    } else if ("scale".equals(op)) {
                        float k = i.getFloatExtra("k", -1f);
                        sSizeScale = k > 0 ? k : Float.NaN;
                        Xp.log(TAG + "size scale = " + sSizeScale);
                    } else if ("params".equals(op)) {
                        setClockParams(i.getFloatExtra("w", -1f), i.getFloatExtra("h", -1f),
                                i.getFloatExtra("weight", -1f), i.getIntExtra("size", -1),
                                i.getFloatExtra("sizei", -1f));
                    } else if ("api".equals(op)) {
                        dumpApi(i.getStringExtra("id"));
                    } else if ("oemtrans".equals(op)) {
                        dumpOemTranslation(i.getFloatExtra("y", 1200f));
                    } else if ("viewtypes".equals(op)) {
                        dumpClockViewTypes();
                    } else if ("notif".equals(op)) {
                        dumpNotifState();
                    } else if ("views".equals(op)) {
                        dumpViewTree(i.getBooleanExtra("root", false));
                    } else if ("shadeprobe".equals(op)) {
                        // Phase 1a: paint the shade's cover layer flat, so its placement can be
                        // judged with no cover, no spring and no blur in the way.
                        ShadeLayer.setProbe(i.getBooleanExtra("on", true));
                    } else if ("shadedump".equals(op)) {
                        Xp.log(TAG + "shade layer: " + ShadeLayer.describe());
                    } else if ("shadecfg".equals(op)) {
                        // One op for the whole settings page. The key is validated and the value
                        // clamped in ShadeLayer.configure, so adb and the UI go through exactly
                        // the same door.
                        ShadeLayer.configure(i.getStringExtra("key"), i.getIntExtra("v", 0));
                        saveState();
                    } else if ("rows".equals(op)) {
                        dumpNotifRows();
                    } else if ("cand".equals(op)) {
                        dumpCandidates();
                    } else if ("blurscan".equals(op)) {
                        dumpBlurScan();
                    } else if ("blurclear".equals(op)) {
                        clearCardBlur();
                    } else if ("shade".equals(op)) {
                        Xp.log(TAG + "shade: mode=" + ShadeLayer.mode()
                                + " " + ShadeLayer.describe());
                    } else if ("query".equals(op)) {
                        // Answered through the ordered broadcast's result extras: the app is a
                        // separate process and this is the only channel it already has. A reply
                        // arriving at all is also how the app knows the module is loaded.
                        android.os.Bundle out = new android.os.Bundle();
                        out.putBoolean("alive", true);
                        out.putBoolean("cover", sCoverMode);
                        out.putBoolean("style2", sStyle2Mode);
                        out.putBoolean("auto", sAuto);
                        out.putFloat("bias", sBias);
                        out.putFloat("clock", sClockHeightDp);
                        out.putFloat("clocksize", effectiveClockSize());
                        out.putFloat("clockoff", sClockOffsetDp);
                        out.putFloat("glass", sGlassEnd);
                        out.putFloat("spring", sClockResponse);
                        // The whole shade settings page, generated from the one key list so a new
                        // knob cannot reach the state file and miss the settings app.
                        for (String key : ShadeLayer.CFG_KEYS) {
                            out.putInt("shade_" + key, ShadeLayer.cfgInt(key));
                        }
                        out.putInt("shadeMode", ShadeLayer.mode());
                        out.putBoolean("card", sCardShowing);
                        // Checked live rather than reported from the cached flag: the user can
                        // change the wallpaper at any time and that is what breaks the feature.
                        out.putBoolean("lockwp", hasLockWallpaper(c));
                        out.putString("track", sCardKey);
                        MediaController mc = sWatched;
                        out.putString("player", mc == null ? "" : mc.getPackageName());
                        out.putBoolean("mcart", sMcHideArt);
                        out.putBoolean("mctext", sMcCenterText);
                        out.putBoolean("mctap", sMcTitleTap);
                        out.putBoolean("tap", sTapToggle);
                        out.putBoolean("fadewp", sFadeWp);
                        out.putBoolean("hidefp", sHideFp);
                        out.putBoolean("lyrics", LockLyrics.sEnabled);
                        out.putBoolean("lyrickeep", LockLyrics.sKeepOn);
                        out.putBoolean("lyrichdr", LockLyrics.sHdr);
                        out.putInt("fpavoid", sFpAvoid);
                        // Everything the app's preview needs to be to scale. It draws a lock
                        // screen it cannot see, and every one of these is device-specific, so
                        // they are measured here rather than written down twice.
                        out.putInt("sw", sScreenW);
                        out.putInt("sh", sScreenH);
                        out.putInt("cardl", sCardL);
                        out.putInt("cardt", sCardT);
                        out.putInt("cardw", sCardW);
                        out.putInt("cardh", sCardH);
                        float[] cg = clockGeometry();
                        if (cg != null) {
                            out.putFloat("clockw", cg[0]);
                            out.putFloat("clockh", cg[1]);
                            out.putFloat("clocky", cg[2]);
                            out.putFloat("clockpad", CLOCK_PAD);
                            out.putFloat("clockx", cg[3]);
                            out.putFloat("clockpivotx", cg[4]);
                            out.putFloat("clockfull", clockFullRatio());
                        }
                        // Outside the block above: it is a property of the STYLE, not of the
                        // measurement, and the app needs it even on a frame where the clock
                        // could not be measured - that is exactly when the slider it disables
                        // would otherwise look like it was doing something.
                        out.putBoolean("clockglass", clockHasGlass());
                        setResultExtras(out);
                    } else if ("diag".equals(op)) {
                        String report = clockReport();
                        android.os.Bundle rb = new android.os.Bundle();
                        rb.putString("report", report);
                        setResultExtras(rb);
                        // Logged as well as answered: an adb run of this op should not have to
                        // go through the app to see it.
                        for (String line : report.split("\n")) Xp.log(TAG + line);
                    } else if ("bounds".equals(op)) {
                        dumpClockBounds();
                    } else if ("geom".equals(op)) {
                        dumpGeom();
                    } else if ("ink".equals(op)) {
                        dumpInk();
                    } else if ("motiontrace".equals(op)) {
                        MotionTrace.arm(i.getIntExtra("n", 4));
                    } else if ("geomtrace".equals(op)) {
                        startGeomTrace(i.getIntExtra("ms", 4000));
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "state: holdY=" + sHoldY + " currentY=" + sCurrentY
                                + " lastSystemY=" + sLastSystemY + " animating=" + (sFrameCb != null || sRamp != null)
                                + " container=" + (sContainer != null) + " verbose=" + sVerbose);
                        Xp.log(TAG + "cover: on=" + sCoverMode + " auto=" + sAuto
                                + " bias=" + sBias + " screen=" + sScreenW + "x" + sScreenH
                                + " card=" + (sCardKnown ? (sCardShowing ? sCardKey : "gone") : "unknown")
                                + " following=" + (sWatched == null ? "none" : sWatched.getPackageName())
                                + " track=" + sTrackKey);
                        Xp.log(TAG + "card rect: " + sCardL + "," + sCardT + " "
                                + sCardW + "x" + sCardH + " hideArt=" + sMcHideArt
                                + " centerText=" + sMcCenterText
                                + " titleTap=" + sMcTitleTap
                                + " title=" + (sCardTitleTapped != null) + " cardP=" + sCardP);
                        Xp.log(TAG + "tap: toggle=" + sTapToggle
                                + " suppressed=" + sTapSuppressed
                                + " bouncer=" + bouncerUp()
                                + " wallpaperFade=" + sFadeWp);
                    } else if ("verbose".equals(op)) {
                        sVerbose = i.getBooleanExtra("on", !sVerbose);
                        LockLyrics.verbose = sVerbose;
                        Xp.log(TAG + "verbose=" + sVerbose);
                    } else if ("abandon".equals(op)) {
                        abandonHold("requested");
                    } else if ("release".equals(op)) {
                        float back = sLastSystemY;
                        if (Float.isNaN(back)) {
                            // Never fabricate a Y - a wrong one snaps the clock to a size the
                            // system never asked for. With nothing observed yet, just let go.
                            abandonHold("release with no observed system Y", true);
                            return;
                        }
                        Xp.log(TAG + "release -> y=" + back
                                + (ms > 0 ? " over " + ms + "ms" : " spring zeta=" + zeta + " response=" + response));
                        if (!anim) { abandonHold("release"); drive(back, false, type); }
                        else if (ms > 0) rampTo(back, ms, type, true);
                        else springTo(back, zeta, response, type, true);
                    } else {
                        abandonHold("raw drive");
                        drive(i.getFloatExtra("y", -1f), anim, type);
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        Xp.log(TAG + "receiver registered for " + ACTION);
        try {
            ctx.sendBroadcast(wallpaperIntent("hello"));
        } catch (Throwable t) {
            Xp.log(TAG + "hello to the wallpaper process failed: " + t);
        }
        loadState();
        // The icon views can already exist by now - the file is read when the keyguard attaches,
        // which is not necessarily before the fingerprint view is built.
        applyHideFp();

        // Which kind of wallpaper is on the lock screen decides where the cover is drawn, and
        // cover mode can be restored from disk before any push has had a chance to work it
        // out. Off the main thread: it reads MIUI's files.
        final Context appCtx = ctx;
        worker().post(new Runnable() {
            @Override
            public void run() {
                String k = wallpaperKind(appCtx, "lock");
                sVideoWallpaper = k != null && !KIND_IMAGE.equals(k);
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
                if (Intent.ACTION_WALLPAPER_CHANGED.equals(a)) {
                    // Nothing else in this receiver is about the wallpaper, so this is the whole
                    // of it: drop the cached copy the shade shows in mode 1. It reloads on the
                    // next pull-down that wants it.
                    invalidateShadeWallpaper();
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(a)) sScreenOn = true;
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    sScreenOn = false;
                    // A tap still waiting out its double tap window was aimed at a screen that
                    // is gone; whatever was going to cancel it cannot arrive now.
                    cancelPendingTap("screen off");
                }
                if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    // The same SystemUI process survives unlock. Do not let a presentation chosen
                    // on the previous keyguard become the starting state of the next lockscreen.
                    resetMusicPresentation("user present", true);
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    if (sCoverMode && ClockCollapse.leavingOrOff() && keyguardShowing()) {
                        ClockCollapse.enter(true, true);
                    }
                    // Waking re-runs the OEM's depth pipeline, and if the keyguard was rebuilt
                    // while the screen was off the guard went away with the old view.
                    if (sDepthHidden) setDepthHidden(true);
                    // A hand-back of the live wallpaper that had to wait for a lock screen.
                    else if (sVideoWpOwed) setDepthHidden(false);
                    if (sCoverMode || sStyle2Mode) applyMediaCard();
                    return;
                }
                if (Intent.ACTION_SCREEN_OFF.equals(a) && sCoverMode) ClockCollapse.toAod();
                else ClockCollapse.release(a);
            }
        };
        IntentFilter lf = new IntentFilter();
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_USER_PRESENT);
        // Mode 1 caches the home wallpaper to reveal behind the shade. This is the only reliable
        // notice that it is no longer the right picture - the user can change it from Settings,
        // a theme or the wallpaper carousel, and nothing else this module hooks is involved.
        lf.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        ctx.registerReceiver(lifecycle, lf, Context.RECEIVER_NOT_EXPORTED);
        Xp.log(TAG + "lifecycle receiver registered (screen off / user present)");
    }

    /**
     * Whether the display is on, as of the last lifecycle broadcast.
     *
     * A cache of screenOn() for the paths that ask once per frame. Optimistic by default: a
     * missed broadcast would otherwise leave the cover's colour switched off for good, and being
     * wrong in that direction costs a screenshot's worth of wrongness rather than a clock nobody
     * can read.
     */
    private static volatile boolean sScreenOn = true;

    /** Where the clock is right now: our own last frame, else the system's last word. NaN if unknown. */
    private static float currentY() {
        if (!Float.isNaN(sCurrentY)) return sCurrentY;
        return sLastSystemY;
    }

    private static void stopMotion() {
        if (sRamp != null) { sRamp.cancel(); sRamp = null; }
        if (sFrameCb != null) { Choreographer.getInstance().removeFrameCallback(sFrameCb); sFrameCb = null; }
    }

    /**
     * Drops ownership unconditionally. Anything that can strand an in-flight animation -
     * the keyguard being rebuilt, the container going away, Choreographer frames stopping
     * because the display turned off - has to come through here, otherwise sHoldY stays
     * pinned at whatever half-way value the last frame wrote and silently rewrites the
     * clock forever after.
     */
    private static void abandonHold(String why) {
        abandonHold(why, false);
    }

    private static void abandonHold(String why, boolean restore) {
        boolean held = sHoldY != null || sFrameCb != null || sRamp != null
                || !Float.isNaN(sCollapseMin) || sCoverTint != 0;
        stopMotion();
        sHoldY = null;
        sCurrentY = Float.NaN;
        sSpringV = 0f;
        // The flight is over, so nothing is being released any more - and this was the one place
        // that knew it and did not say so. `sReleasing` is set by exitCoverMode() and was cleared
        // only on the way back IN (enterCoverMode / wakeIntoCover / reassertCoverClock), so after
        // any exit it stayed true until the next cover entry.
        sReleasing = false;
        // Cover mode is one state: the squeeze, the collapse scale and the tint are taken and
        // given back together, so none of them can outlive the keyguard that granted them.
        if (!Float.isNaN(sCollapseMin)) {
            sCollapseMin = Float.NaN;
            sAppliedK = Float.NaN;
            groupScale("time_group", 1f, -1f, 0f, 0f);
            View dd = sDateView;
            int[] dl = new int[2];
            if (dd != null) dd.getLocationOnScreen(dl);
            String before = dd == null ? "no date" : ("dty=" + r1(dd.getTranslationY())
                    + " dscr=" + dl[1] + " cac=" + cacTy());
            // Zero, and nothing cleverer. This was `-containerTy()` for a while, on the reasoning
            // that it kept the date from stepping on the hand-back frame. It does not: cancelling
            // the container pins the date to its LAYOUT top, which is not where the OEM puts it,
            // and the exit has already walked it to exactly where the OEM puts it. Measured on
            // all_in_one with `text_area` at top=981 and cac=-463.8, the exit landing with the
            // date at 517 and dty=-0.2: zero is a no-op, and the compensation threw it 463px
            // DOWN - from 517 to 981, which on this style is inside the clock's ink box at
            // 1099..1393 in the group's own frame, i.e. the date drawn through the middle of the
            // digits. Worse, it was a snapshot: the keyguard later took the container home to 0
            // and the date went with the stale offset, so the overlap arrived some seconds after
            // the transition that caused it and stayed until cover mode was entered again.
            for (View root : clockRoots()) {
                View d = findClockView(root, "text_area");
                if (d != null) d.setTranslationY(0f);
            }
            if (dd != null) dd.getLocationOnScreen(dl);
            Xp.log(TAG + "hand-back: " + before + " -> dty="
                    + (dd == null ? "?" : r1(dd.getTranslationY()))
                    + " dscr=" + (dd == null ? -1 : dl[1]) + " cac=" + cacTy());
        }
        // The cover's colouring goes back with the rest of cover mode, through the same call
        // that put it there: with nothing measured, the setter hooks hand the OEM's own colours
        // straight through. Without this the clock would keep the cover's hue over the wallpaper
        // the cover was hiding - the one way this could leave the lock screen worse than it found
        // it.
        if (sCoverTint != 0) {
            sCoverTint = 0;
            recolorClock();
        }
        if (!Float.isNaN(sGlassV0)) {
            float back = sGlassV0;
            sGlassV0 = sGlassV1 = Float.NaN;
            sAppliedGlassV = Float.NaN;
            callOnClockViews("updateGlassValue", "f", back, 0, false);
        }
        // The card is part of the same state as the clock: cover mode takes the thumbnail
        // and the text along with the squeeze, and gives all three back together. Releasing it
        // here rather than in exitCoverMode() is what lets the animated exit keep the guard
        // installed for the whole flight - the spring walks sCardP down to 0, and this is the
        // frame after it lands.
        // Not while our own animator owns the progress: it is walking the card back frame by
        // frame and will hand it over itself when it lands.
        if (!sCoverMode && sCardP != 0f && sCardAnim == null) {
            sCardP = 0f;
            applyMediaCard();
        }
        if (!held) return;
        Xp.log(TAG + "hold abandoned: " + why);
        // The flight is over, so the guard's work is done - and this, not exitCoverMode(), is
        // where it belongs: that function runs on the frame the flight STARTS. See the comment
        // there for the 52px the difference cost.
        releaseClockGuard();
        // Dropping the coercion alone only stops us rewriting *future* calls; the clock
        // keeps whatever squeeze the last frame left behind until the system happens to
        // emit again, which it may never do if nothing below the clock changes. Snap it
        // back to the value the system last asked for.
        if (restore && sContainer != null && !Float.isNaN(sLastSystemY)) {
            drive(sLastSystemY, false, "STATE_CHANGED");
        }
    }

    /**
     * Integrates the same damped spring the OEM uses, and pushes Y every frame.
     * Carries velocity across retargets, so an interrupted animation stays continuous.
     */
    private static void springTo(final float target, final float zeta, final float response,
                                 final String typeName, final boolean releaseAtEnd) {
        final View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                boolean retarget = sFrameCb != null;
                stopMotion();
                final float w0 = (float) (2 * Math.PI / response);
                final float k = w0 * w0;
                final float c = 2f * zeta * w0;
                if (!retarget) {
                    float from = currentY();
                    if (Float.isNaN(from)) {
                        Xp.log(TAG + "no Y observed yet, snapping to " + target);
                        sHoldY = target;
                        applyY(target, typeName);
                        return;
                    }
                    sSpringX = from;
                    sSpringV = 0f;
                }
                final long t0 = android.os.SystemClock.uptimeMillis();
                final long[] last = {0L};
                sFrameCb = new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long frameTimeNanos) {
                        if (last[0] == 0L) last[0] = frameTimeNanos;
                        float dt = (frameTimeNanos - last[0]) / 1e9f;
                        last[0] = frameTimeNanos;
                        if (dt <= 0f) dt = 1f / 120f;
                        if (dt > 0.05f) dt = 0.05f;
                        int steps = (int) Math.ceil(dt * 240f);
                        float h = dt / Math.max(1, steps);
                        for (int i = 0; i < Math.max(1, steps); i++) {
                            float a = -k * (sSpringX - target) - c * sSpringV;
                            sSpringV += a * h;
                            sSpringX += sSpringV * h;
                        }
                        if (sContainer == null) {
                            abandonHold("container vanished mid-spring");
                            return;
                        }
                        boolean done = Math.abs(sSpringX - target) < 0.5f && Math.abs(sSpringV) < 2f;
                        float y = done ? target : sSpringX;
                        sHoldY = y;
                        applyY(y, typeName);
                        if (!done) {
                            Choreographer.getInstance().postFrameCallback(this);
                            return;
                        }
                        sFrameCb = null;
                        sSpringV = 0f;
                        long ms = android.os.SystemClock.uptimeMillis() - t0;
                        if (releaseAtEnd) {
                            abandonHold("spring settled in " + ms + "ms, control handed back");
                        } else {
                            Xp.log(TAG + "spring settled in " + ms + "ms, holding at " + target);
                            // One more pass, for the collapse scale's sake and not the clock's:
                            // only the LAST frame of a transition reaches p = 1, so only the last
                            // frame can report a settled glyph box, and the scale wants two
                            // settled readings that agree before it believes a layout has
                            // stopped moving. The settle's retries are exactly that.
                            settleCollapsedClock(0);
                        }
                    }
                };
                Choreographer.getInstance().postFrameCallback(sFrameCb);
            }
        });
    }

    private static float[] easeByName(String name) {
        if ("running".equals(name)) return EASE_RUNNING;
        if ("default".equals(name)) return EASE_DEFAULT;
        return EASE_STATE_CHANGED;
    }

    private static void rampTo(float target, long ms, String typeName) {
        rampTo(target, ms, typeName, false);
    }

    /**
     * The OEM animates the clock by animating Y itself and recomputing the glyph
     * metrics every frame (AllInOneClockAnimation$folmeClockListener.onUpdate ->
     * setNotifY). We do the same: ramp Y, snap the clock on each frame.
     */
    private static void rampTo(final float target, final long ms, final String typeName,
                               final boolean releaseAtEnd) {
        rampTo(target, ms, typeName, releaseAtEnd, null);
    }

    /** ease != null overrides the OEM-ish decelerate above. */
    private static void rampTo(final float target, final long ms, final String typeName,
                               final boolean releaseAtEnd, final Interpolator ease) {
        final View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container captured yet");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                stopMotion();
                final float from = currentY();
                if (Float.isNaN(from)) {
                    Xp.log(TAG + "no Y observed yet, snapping to " + target);
                    sHoldY = target;
                    applyY(target, typeName);
                    return;
                }
                ValueAnimator a = ValueAnimator.ofFloat(from, target);
                a.setDuration(ms);
                // HyperOS-ish emphasised decelerate; swap for a spring later.
                a.setInterpolator(ease != null ? ease : new PathInterpolator(0.2f, 0f, 0f, 1f));
                a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator an) {
                        float y = (Float) an.getAnimatedValue();
                        sHoldY = y;
                        applyY(y, typeName);
                    }
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator an) {
                        if (releaseAtEnd) {
                            abandonHold("ramp done, control handed back");
                        } else {
                            Xp.log(TAG + "ramp done, holding at " + target);
                            settleCollapsedClock(0);   // see the spring's landing above
                        }
                    }
                });
                sRamp = a;
                a.start();
            }
        });
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

    /** Applies one frame. Must already be on the view's thread. */
    private static void applyY(float y, String typeName) {
        View v = sContainer;
        Method m = sNotifStateChange;
        if (v == null || m == null) return;
        try {
            Object type = topChangeType(typeName);
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
            sCurrentY = y;
            // The card rides our own frames as well as the OEM's chokepoint, because on some
            // clock styles there IS no chokepoint: notifStateChange never reaches
            // KeyguardClockNotifInteractor.setNotifY on the classic style, so applyCollapse()
            // is never called and the card would sit at whatever progress it started the
            // animation with. Measured on device with the classic style selected: setNotifY
            // fired zero times across a full enter and exit, and the two card settings simply
            // stopped working. Writing it here costs a comparison when the hook got there first.
            float p = coverProgress(y);
            if (!Float.isNaN(p)) setCardProgress(p);
        } catch (Throwable t) {
            Xp.log(TAG + "applyY failed: " + Log.getStackTraceString(t));
        }
    }

    /** Package-local clock driver used by the extracted collapse state machine. */
    static void applyY(float y) {
        applyY(y, "STATE_CHANGED");
    }

    private static void drive(final float y, final boolean anim, final String typeName) {
        final View v = sContainer;
        final Method m = sNotifStateChange;
        if (v == null) {
            Xp.log(TAG + "no clock container captured yet");
            return;
        }
        if (m == null) {
            Xp.log(TAG + "no notifStateChange on this build, cannot drive the clock");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object type = topChangeType(typeName);
                    sSelfDriving = true;
                    try {
                        m.invoke(v, y, anim, type);
                    } finally {
                        sSelfDriving = false;
                    }
                    sCurrentY = y;
                    Xp.log(TAG + "notifStateChange(" + y + ", " + anim + ", " + typeName + ") OK");
                } catch (Throwable t) {
                    Xp.log(TAG + "drive failed: " + Log.getStackTraceString(t));
                }
            }
        });
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
    private static void setClockParams(final float w, final float h, final float weight,
                                       final int size, final float sizeInternal) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
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
                    Xp.log(TAG + "setClockParams failed: " + Log.getStackTraceString(e));
                }
            }
        });
    }

    /** Dumps the declared API of one view in the clock tree, to find the size knobs. */
    private static void dumpApi(String idName) {
        View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        if (idName == null) idName = "hour_view";
        int id = v.getContext().getResources().getIdentifier(idName, "id", "com.android.systemui");
        View target = id == 0 ? null : v.getRootView().findViewById(id);
        // "clock" means the AllInOneHourClock/AllInOneMinuteClock view itself, which carries no id.
        if ("clock".equals(idName)) {
            View[] roots = clockRoots();
            target = roots.length == 0 ? null : ((android.view.ViewGroup) roots[0]).getChildAt(0);
        }
        if (target == null) { Xp.log(TAG + idName + " not found"); return; }
        Class<?> c = target.getClass();
        Xp.log(TAG + idName + " = " + c.getName());
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            Xp.log(TAG + "--- " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("  ").append(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int k = 0; k < ps.length; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(ps[k].getSimpleName());
                }
                Xp.log(TAG + sb.append(')'));
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(target); } catch (Throwable t) { val = "?"; }
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int len = java.lang.reflect.Array.getLength(val);
                    for (int j = 0; j < len; j++) {
                        if (j > 0) arr.append(", ");
                        arr.append(j).append(':').append(java.lang.reflect.Array.get(val, j));
                    }
                    val = arr.append(']').toString();
                }
                Xp.log(TAG + "  ." + f.getName() + " = " + val);
            }
        }
    }

    private static void dumpViewTree(boolean fromRoot) {
        View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        dumpViewTree(fromRoot ? v.getRootView() : v, 0);
    }

    /**
     * Scales the digits as a group. TimeView.onDraw strokes a Path, so a parent scale is a
     * canvas matrix concat - the path rasterises at the final size, no quality loss - and
     * position scales with it, unlike setSizeInternal which shrinks the glyphs but leaves
     * them at draw origins computed for the big clock.
     */
    /**
     * The lockscreen editor writes its colour choice into clockInfo.clockEffect (see the
     * constant_template_editor_info setting). Dump ClockStyleInfo's constants so we can name
     * the modes instead of guessing at integers.
     */
    private static void dumpClockStyleInfo() {
        try {
            Class<?> tv = Xp.findClass(CLS_TIME_VIEW, sContainerCls.getClassLoader());
            Class<?> info = tv.getDeclaredMethod("getClockStyleInfo").getReturnType();
            Xp.log(TAG + "ClockStyleInfo = " + info.getName());
            for (Class<?> c = info; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        Xp.log(TAG + "  " + c.getSimpleName() + "." + f.getName()
                                + " = " + f.get(null));
                    } catch (Throwable ignored) {
                    }
                }
                StringBuilder ms = new StringBuilder();
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("effect") || n.toLowerCase().contains("blend")
                            || n.toLowerCase().contains("color")) ms.append(n).append(' ');
                }
                if (ms.length() > 0) Xp.log(TAG + "  " + c.getSimpleName() + " methods: " + ms);
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dumpClockStyleInfo failed: " + Log.getStackTraceString(t));
        }
    }

    /** Dumps a class by name so we can find OEM entry points without pulling the dex apart. */
    private static void dumpClass(String name, String grep) {
        ClassLoader cl = sContainerCls == null ? null : sContainerCls.getClassLoader();
        Class<?> c;
        try {
            c = Xp.findClass(name, cl);
        } catch (Throwable t) {
            Xp.log(TAG + name + " NOT FOUND");
            return;
        }
        Xp.log(TAG + "=== " + c.getName());
        String g = grep == null ? null : grep.toLowerCase();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder(m.getReturnType().getSimpleName())
                        .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int j = 0; j < ps.length; j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(ps[j].getSimpleName());
                }
                sb.append(')');
                String line = sb.toString();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
                }
            }
        }
    }

    /**
     * Where the clock actually sits on screen. Every lock screen clock style lays its glyphs out
     * somewhere different, so anything that positions the collapsed clock has to come from these
     * numbers rather than from a constant measured against one style.
     */
    /**
     * The layout-space relationship between the date and the clock, printed on demand.
     *
     * W3 rests on one claim that has never been measured on this device: that the date and the
     * clock target sit in ONE frame, so their `getTop()`s can be subtracted meaningfully. Two
     * earlier attempts assumed a frame relation in one direction or the other and were off by
     * ~700px. So this prints the frames instead of assuming them - each view's own `getTop()`
     * (parent-relative, i.e. layout space), the same view's rendered position, and the chain of
     * ancestors up towards the container, so "do these two numbers mean the same thing" is
     * answered by what is actually on the screen.
     *
     * Called by hand (`op geom`) after each step of a sweep, which is why it logs a block and
     * writes nothing.
     */
    private static void dumpGeom() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "geom: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder sb = new StringBuilder("geom");
                    sb.append(" y=").append(r1(sHoldY == null ? currentY() : sHoldY))
                      .append(" p=").append(Float.isNaN(sPendP) ? "n/a" : r3(sPendP))
                      .append(" k=").append(Float.isNaN(sAppliedK) ? "n/a" : r2(sAppliedK))
                      .append(" min=").append(Float.isNaN(sCollapseMin) ? "n/a" : r2(sCollapseMin))
                      .append(" glyph=").append(r1(sPendGlyph))
                      .append(" box=").append(boxOf(glyphBox()))
                      .append(" live=").append(boxOf(measureLiveBox()))
                      .append(" cac=").append(cacTy())
                      .append(" oemDateMargin=").append(oemDateMargin());
                    Xp.log(TAG + sb.toString());
                    Xp.log(TAG + "  date " + geomOf(sDateView));
                    for (View root : clockRoots()) {
                        Xp.log(TAG + "  clock(" + viewIdOf(root) + ") "
                                + geomOf(clockTarget(root)));
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "geom failed: " + t);
                }
            }
        });
    }

    /**
     * The glyph box measured on THIS frame, with the hold and the cache both stood down.
     *
     * A probe and nothing else: every reading it takes is put back exactly as it was, because
     * sInkUnit and the cached box are what the next real placement is made from and a diagnostic
     * that moves them is measuring itself.
     */
    private static RectF measureLiveBox() {
        RectF keepBox = sGlyphCache;
        long keepAt = sGlyphAt;
        float keepUnit = sInkUnit;
        try {
            sProbeLive = true;
            return glyphBox();
        } catch (Throwable t) {
            return null;
        } finally {
            sProbeLive = false;
            sGlyphCache = keepBox;
            sGlyphAt = keepAt;
            sInkUnit = keepUnit;
        }
    }

    /**
     * Per-frame trace of the four numbers the date placement is made of, and nothing else.
     *
     * `op verbose` answers this too and costs the frame rate doing it - it goes through the
     * LSPosed bridge several times per frame and the lock screen visibly stutters, so a
     * transition measured under it is not the transition anyone sees. This is one line, built
     * from fields already in hand plus two getters, so the frame it describes is the real one.
     *
     * What it is for: the date's screen position is `container.ty + date.top + date.ty`. If the
     * jump is a compensation that arrives a frame late, then the STEP in `dscr` is larger than
     * the step in `cty` on exactly the frames where the OEM moves the container; if it is
     * something else, `dtop` moves instead. One or the other, and no way to tell them apart from
     * a settled reading.
     */
    private static volatile long sGeomTraceUntil;
    private static int sGeomTraceN;

    private static void startGeomTrace(int ms) {
        sGeomTraceUntil = android.os.SystemClock.uptimeMillis() + Math.max(500, ms);
        sGeomTraceN = 0;
        // Its OWN listener, deliberately. It used to ride on sClockGuard, and exitCoverMode()
        // removes that guard before it starts the exit spring - so the one transition this
        // exists to look at was the one transition it could not see, and "no frames were drawn"
        // was the instrument being unplugged rather than the screen standing still.
        View v = sContainer;
        if (v != null) {
            try {
                v.getViewTreeObserver().addOnPreDrawListener(sGeomTraceL);
                sGeomTraced = v;
            } catch (Throwable t) {
                Xp.log(TAG + "geomtrace not installed: " + t);
            }
        }
        Xp.log(TAG + "geomtrace for " + ms + "ms on " + (v == null ? "no container" : idOf(v)));
    }

    private static void stopGeomTrace() {
        View v = sGeomTraced;
        sGeomTraced = null;
        if (v == null) return;
        try {
            v.getViewTreeObserver().removeOnPreDrawListener(sGeomTraceL);
        } catch (Throwable ignored) {
        }
    }

    private static View sGeomTraced;
    private static final ViewTreeObserver.OnPreDrawListener sGeomTraceL =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            traceGeomFrame();
            return true;
        }
    };

    private static void traceGeomFrame() {
        if (sGeomTraceUntil == 0L) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now > sGeomTraceUntil) {
            sGeomTraceUntil = 0L;
            stopGeomTrace();
            Xp.log(TAG + "geomtrace done, " + sGeomTraceN + " frames");
            return;
        }
        sGeomTraceN++;
        View date = sDateView;
        View c = sContainer;
        StringBuilder sb = new StringBuilder("gf ");
        sb.append("p=").append(Float.isNaN(sPendP) ? "n/a" : r3(sPendP));
        // The date's own parent is `clock_animation_container`, and THAT is the view the OEM
        // squeezes by translating - `sContainer` is `miui_keyguard_clock_container`, one level
        // further up, and its translation is always 0. Reading the wrong one of the two is how
        // a trace can show a container standing still while the date is provably being carried.
        View cac = date == null || !(date.getParent() instanceof View)
                ? null : (View) date.getParent();
        sb.append(" cac=").append(cac == null ? "?" : r1(cac.getTranslationY()));
        sb.append(" cty=").append(c == null ? "?" : r1(c.getTranslationY()));
        if (date == null) {
            sb.append(" date=MISSING");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append(" dtop=").append(date.getTop())
              .append(" dty=").append(r1(date.getTranslationY()))
              .append(" dscr=").append(loc[1]);
        }
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            int[] loc = new int[2];
            g.getLocationOnScreen(loc);
            sb.append(" | ").append(idOf(g)).append(" gtop=").append(g.getTop())
              .append(" gty=").append(r1(g.getTranslationY()))
              .append(" gk=").append(r2(g.getScaleY()))
              .append(" gscr=").append(loc[1]);
        }
        Xp.log(TAG + sb.toString());
    }

    /** One view's layout position, its rendered position, and the frames it hangs in. */
    private static String geomOf(View v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(viewIdOf(v));
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" top=").append(v.getTop())
          .append(" left=").append(v.getLeft())
          .append(" h=").append(v.getHeight())
          .append(" ty=").append(r1(v.getTranslationY()))
          .append(" sy=").append(r2(v.getScaleY()))
          .append(" pivotY=").append(r1(v.getPivotY()))
          .append(" screen=").append(loc[1]);
        // Up towards the container: a difference between two views only cancels if both ends
        // reach the same ancestor carrying the same accumulated translation.
        View p = v.getParent() instanceof View ? (View) v.getParent() : null;
        for (int i = 0; i < 5 && p != null; i++) {
            sb.append(" <- ").append(viewIdOf(p)).append(" top=").append(p.getTop())
              .append(" ty=").append(r1(p.getTranslationY()))
              .append(" sy=").append(r2(p.getScaleY()));
            if (p == sContainer) break;
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return sb.toString();
    }

    /**
     * Where the OEM currently has `clock_animation_container` - the view the squeeze translates,
     * and the one both the date and the clock hang inside. Read rather than assumed: it is 0 in
     * the layouts without a media card and -168 in the collapsed one, and code that takes it for
     * granted is how the date ends up 35px out of place when an exit is interrupted.
     */
    private static float containerTy() {
        View d = sDateView;
        if (d == null || !(d.getParent() instanceof View)) return 0f;
        return ((View) d.getParent()).getTranslationY();
    }

    /** The translation the OEM puts on `clock_animation_container`, which the date rides. */
    private static String cacTy() {
        View d = sDateView;
        if (d == null || !(d.getParent() instanceof View)) return "?";
        return r1(((View) d.getParent()).getTranslationY());
    }

    /**
     * Every measurement the ink box could be taken from, per style, printed rather than picked.
     *
     * `glyphBox()` picks one of several sources per style and the choice is why the same 55.7dp
     * setting gives k=0.09 on one style and k=1.0 on the next: the fallback is the target's own
     * bounds, which is the whole container wherever the target is a container. This walks the
     * target and prints what each view would answer, so the replacement is chosen from numbers.
     */
    private static void dumpInk() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "ink: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    View t = clockTarget(root);
                    Xp.log(TAG + "ink tree=" + viewIdOf(root) + " target="
                            + (t == null ? "NONE" : viewIdOf(t)));
                    if (t == null) continue;
                    inkWalk(t, t, 0);
                }
            }
        });
    }

    private static void inkWalk(View target, View v, int depth) {
        if (v == null || depth > 6) return;
        StringBuilder sb = new StringBuilder("ink   ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append(" #").append(viewIdOf(v))
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(" at=").append(loc[0]).append(',').append(loc[1]);
        // The three shapes an ink measurement could come from, asked in the same order
        // inkBox() asks them.
        try {
            Object b = Xp.callMethod(v, "getTextBoundsWithPosition");
            if (b != null) sb.append(" textBounds=").append(b);
        } catch (Throwable ignored) {
        }
        for (String m : new String[]{"getRectSize", "getRealWidth", "getRealHeight",
                "getLeftPosition", "getTopPosition"}) {
            try {
                Object r = Xp.callMethod(v, m);
                if (r != null) sb.append(' ').append(m).append('=').append(r);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof android.widget.TextView) {
            android.text.Layout lay = ((android.widget.TextView) v).getLayout();
            if (lay != null && lay.getLineCount() > 0) {
                sb.append(" lineTop=").append(lay.getLineTop(0))
                  .append(" lineBottom=").append(lay.getLineBottom(lay.getLineCount() - 1))
                  .append(" lineWidth=").append(lay.getLineWidth(0));
            }
        }
        Xp.log(TAG + sb.toString());
        if (v instanceof ViewGroup && depth < 6) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) inkWalk(target, g.getChildAt(i), depth + 1);
        }
    }

    /** The pooled glyph box as `l,t,r,b`, or none. */
    private static String boxOf(RectF b) {
        if (b == null) return "none";
        return r1(b.left) + "," + r1(b.top) + "," + r1(b.right) + "," + r1(b.bottom);
    }

    /** The OEM's own date+margin height, or the field's value as it came, or n/a. */
    private static String oemDateMargin() {
        try {
            View v = sContainer;
            Object it = v == null ? null : Xp.getObjectField(v, "keyguardClockNotifInteractor");
            Object got = it == null ? null : Xp.getObjectField(it, "dateHeightWithMargin");
            if (got == null) return "n/a";
            return String.valueOf(got);
        } catch (Throwable t) {
            return "n/a";
        }
    }

    private static void dumpClockBounds() {
        for (View root : clockRoots()) {
            android.content.res.Resources r = root.getResources();
            for (String id : new String[]{"clock_animation_container", "time_group", "text_area",
                    "hour_view", "minute_view"}) {
                int i = r.getIdentifier(id, "id", "com.android.systemui");
                View v = i == 0 ? null : root.findViewById(i);
                if (v == null) continue;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                StringBuilder sb = new StringBuilder(viewIdOf(root)).append(' ').append(id)
                        .append(" screen=").append(loc[0]).append(',').append(loc[1])
                        .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                        .append(" ty=").append(v.getTranslationY())
                        .append(" sy=").append(v.getScaleY())
                        .append(" pivotY=").append(v.getPivotY())
                        .append(" vis=").append(v.getVisibility());
                try {
                    sb.append(" textBounds=")
                            .append(Xp.callMethod(v, "getTextBoundsWithPosition"))
                            .append(" drawY=").append(Xp.callMethod(v, "getDrawTextY"))
                            .append(" textTop=").append(Xp.callMethod(v, "getTextTop"));
                } catch (Throwable ignored) {
                }
                Xp.log(TAG + sb);
            }
        }
    }

    private static void invalidateClocks() {
        View v = sContainer;
        if (v == null) return;
        for (View root : clockRoots()) root.invalidate();
    }

    /** Reads, and optionally pokes, the MiGlass shader parameter array on both clock trees. */
    private static void pokeGlassData(final int idx, final float value) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null || t.getVisibility() != View.VISIBLE) continue;
                            float[] g = (float[]) Xp.getObjectField(t, "glassData");
                            if (g == null) continue;
                            if (idx >= 0 && idx < g.length) g[idx] = value;
                            t.invalidate();
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < g.length; j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(j).append(':').append(g[j]);
                            }
                            Xp.log(TAG + viewIdOf(root) + "/" + id + " glassData=[" + sb + "]");
                        } catch (Throwable e) {
                            Xp.log(TAG + "pokeGlassData " + id + ": " + e);
                        }
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ collapse guard

    /**
     * The collapsed clock is asserted once per frame the keyguard draws, the same way the depth
     * cut-out and the media card are.
     *
     * Everything the placement writes is derived from the layout, and it is only ever written
     * while something is driving frames. In cover mode at rest nothing is: the OEM has stopped
     * emitting notifY, so applyCollapse() is not called, and the clock keeps whatever the last
     * driven frame left on it. Anything that changes *then* is invisible to us and stays wrong.
     *
     * Measured, on the path that had no other trigger left (SystemUI restarted with the screen
     * off, then woken): the one placement this process ran happened while the clock container was
     * still at screen y = 0, the OEM then translated the whole container 728px up, and no further
     * frame ever came. The date ended up at screen y = -501 - off the top of the screen - with
     * the transform on the views perfectly self-consistent, so nothing that only checks whether
     * the placement ran can tell.
     *
     * The layout listener below catches a rebuild that changes a *layout*. This catches
     * everything else, transforms included, which no layout event ever reports. The check is
     * deliberately cheap - the already-cached date view and clock targets, no lookups of its own
     * - so measuring the glyphs only happens on the frames where the answer is no.
     */
    private static View sClockGuarded;
    private static long sClockFixAt;
    /** Long enough for a re-placement to have landed before another can be asked for. */
    private static final long CLOCK_FIX_MS = 250L;
    /** The repairs are logged, but a clock that cannot be measured asks every 250ms. */
    private static int sClockFixes;

    private static final ViewTreeObserver.OnPreDrawListener sClockGuard =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            // sCoverMode is already false for the whole of the exit, so testing it alone is what
            // switched the correction below off for the one transition that needs it most.
            boolean exiting = !sCoverMode && sReleasing;
            if ((!sCoverMode && !exiting) || Float.isNaN(sCollapseMin)) return true;
            long perf0 = System.nanoTime();
            // Before the reasons to skip below, and deliberately: a spring or a ramp places on
            // every one of its frames and is still one frame late doing it, which is exactly
            // what this is for.
            applyPendingTranslation();
            updateColorBand();
            perfPre(perf0);
            // Nothing to REPAIR on the way out either, and it would be harmful: the repair below
            // asks whether the clock is at sCollapseMin, and on the way out it deliberately is
            // not, so every frame would look stale and be re-placed every CLOCK_FIX_MS.
            if (exiting) return true;
            // While a spring is running the placement already runs on every frame of it, and
            // this would only be reading back what the frame callback just wrote.
            if (sFrameCb != null || sRamp != null) return true;
            Float held = sHoldY;
            if (held == null) return true;
            float p = coverProgress(held);
            // Same reading the settle uses: with no natural y observed yet, held at the floor is
            // a complete collapse by definition, and that definition is what the hold was taken
            // under. Anything short of settled has a driver of its own and is left alone.
            if (Float.isNaN(p)) p = held > SQUEEZE_FLOOR ? 0f : 1f;
            if (p < 0.999f) return true;
            if (!collapseStale()) return true;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sClockFixAt < CLOCK_FIX_MS) return true;
            sClockFixAt = now;
            if (++sClockFixes <= 5 || sClockFixes % 20 == 0) {
                Xp.log(TAG + "collapsed clock was stale on the views (" + staleWhy()
                        + "), placing it again (" + sClockFixes + ")");
            }
            placeCollapsedClock(1f - p * (1f - sCollapseMin), held, p);
            return true;
        }
    };

    /**
     * Whether what is on the views still says where the clock belongs.
     *
     * The date is the canary: it is the one thing pinned to an absolute place on the screen, so
     * anything that moves the clock under it - a rebuilt keyguard, the OEM translating the
     * container - shows up there first. A style that is only scaled where the OEM put it has no
     * absolute anchor to compare against, and its scale is all there is to check.
     *
     * The scale being checked is sCollapseMin itself, not sAppliedK: at the floor p is 1 and
     * `1 - p * (1 - min)` is `min` exactly, so the expected value needs nothing remembered, and
     * a clock that was never placed - the whole of the "restart and nothing collapses" report -
     * reads as stale at 1.0 against it.
     */
    private static boolean collapseStale() {
        View date = sDateView;
        if (anchoredStyle()) {
            if (!usableDate(date)) return true;
            int[] loc = LOC_STALE;
            date.getLocationOnScreen(loc);
            float target = dateTargetY();
            if (Math.abs(loc[1] - target) > 1.5f) return true;
        }
        for (View root : clockRoots()) {
            View g = sClockTargets.get(root);
            // Nothing resolved for this tree yet, so there is nothing to compare - and one
            // repair is what resolves it.
            if (g == null) return true;
            // A tree that answered "no clock here" is not a tree to correct, and asking again
            // from here would walk the layer every frame for a style that has nothing to scale.
            // The layout listener is what speaks up if one ever appears.
            if (g == root) continue;
            if (!usable(g)) return true;
            if (Math.abs(g.getScaleX() - sCollapseMin) > 0.005f) return true;
        }
        // Every tree is either scaled as it should be or has no clock to scale.
        return false;
    }

    /** The same reading, once, for the log line - only ever called once the answer is yes. */
    private static String staleWhy() {
        StringBuilder sb = new StringBuilder();
        sb.append("want k=").append(r2(sCollapseMin));
        View date = sDateView;
        if (usableDate(date)) {
            int[] loc = LOC_STALE;
            date.getLocationOnScreen(loc);
            sb.append(" dateOnScreen=").append(loc[1]);
        } else {
            sb.append(" date=MISSING");
        }
        for (View root : clockRoots()) {
            View g = sClockTargets.get(root);
            sb.append(" | ").append(g == null ? "unresolved"
                    : g == root ? "no clock here" : idOf(g) + " scale=" + r2(g.getScaleX()));
        }
        return sb.toString();
    }

    /** Idempotent, so it can be called from anywhere the container is known to be alive. */
    private static void ensureClockGuard() {
        View v = sContainer;
        if (v == null || sClockGuarded == v) return;
        releaseClockGuard();
        try {
            v.getViewTreeObserver().addOnPreDrawListener(sClockGuard);
            sClockGuarded = v;
        } catch (Throwable t) {
            // Adding during the pre-draw dispatch itself throws. Whoever next knows the
            // container is alive will try again.
            Xp.log(TAG + "clock guard not installed: " + t);
        }
    }

    private static void releaseClockGuard() {
        View v = sClockGuarded;
        sClockGuarded = null;
        if (v == null) return;
        try {
            v.getViewTreeObserver().removeOnPreDrawListener(sClockGuard);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Maps the notifY the clock is being drawn at onto a group scale, so the collapse rides
     * the OEM's own per-frame driver instead of a second animator of ours. Called from the
     * setNotifY hook, i.e. once per frame, by whoever is driving.
     */
    private static void applyCollapse(float y) {
        float min = sCollapseMin;
        if (Float.isNaN(min) && Float.isNaN(sGlassV0)) return;
        float p = coverProgress(y);
        if (Float.isNaN(p)) return;
        // The one place every driven frame of a transition passes through, which is why the cost
        // accounting is hung here - see perfFrame().
        perfFrame();
        long perf0 = System.nanoTime();
        // Same progress, three consumers: the collapse scale below, the glass morph, and
        // the card. One OEM spring drives all of them and none of them owns a clock of its own.
        setCardProgress(p);
        perfSeg(PERF_CARD, perf0);
        long seg = System.nanoTime();
        if (Float.isNaN(min)) { applyGlassMorph(p); perfSeg(PERF_GLASS, seg); perfPlace(perf0); return; }
        applyGlassMorph(p);
        perfSeg(PERF_GLASS, seg);
        float k = 1f - p * (1f - min);
        sAppliedK = k;
        // NOT deferred to a pre-draw: that was tried and it both failed to remove the lag
        // (measured) and left a stale set of pending parameters applied to a later frame, which
        // put the date a thousand pixels from the line. The lag this was for is handled by asking
        // the OEM for its translation instead - see oemTranslationY().
        placeCollapsedClock(k, y, p);
        perfPlace(perf0);
    }

    /**
     * What one transition costs us, in the module's own log - one line per transition, no
     * command to remember and no verbose switch to turn on first.
     *
     * This exists because the transition's jank has two possible authors and nothing else tells
     * them apart: our per-frame placement, or the OEM recomputing its variable font on the same
     * frames (which it does, and which is not ours to make cheaper). The `gap` column is the
     * frame interval OUR frames arrived at, so a transition that is smooth on the wall clock but
     * has a 40ms gap in it is a dropped frame whoever caused it; the two `us` columns are what
     * our own code spent, which is the half this module controls.
     *
     * A run ends when a frame does not arrive for PERF_GAP_MS, and the line is written then by a
     * poll rather than by whoever owns the spring - nothing here has to know how a transition is
     * driven, or when it ends. The poll costs one message per 250ms while a run is open, and
     * nothing at all between them.
     */
    private static final long PERF_GAP_MS = 250L;
    private static int sPerfFrames;
    private static long sPerfLastAt;
    private static long sPerfPlaceNs, sPerfPlaceMaxNs;
    private static long sPerfPreNs, sPerfPreMaxNs;
    private static long sPerfWorstGapMs;
    /**
     * The placement's own parts, so a spike in `place` can be read rather than guessed at. Three
     * counters and a remainder: `rest` is not measured, it is place minus the three, which keeps
     * the parts adding up to the whole and needs no stopwatch around the body of a function that
     * returns from a dozen places.
     */
    private static final int PERF_MAX = 3;  // 0 card, 1 glass, 2 glyph
    private static final long[] sPerfSegNs = new long[PERF_MAX];
    private static final long[] sPerfSegMaxNs = new long[PERF_MAX];
    private static final String[] PERF_SEG = {"card", "glass", "glyph"};
    /** What the glyph measurement cost on the frame just placed - consumed by the caller. */
    private static long sPerfGlyphLastNs;

    private static final Runnable sPerfFlush = new Runnable() {
        @Override
        public void run() {
            if (sPerfFrames == 0) return;
            if (android.os.SystemClock.uptimeMillis() - sPerfLastAt < PERF_GAP_MS) {
                main().postDelayed(this, PERF_GAP_MS);   // still travelling
                return;
            }
            flushPerf();
        }
    };

    private static void perfFrame() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sPerfFrames > 0) {
            long gap = now - sPerfLastAt;
            if (gap > PERF_GAP_MS) {
                // The run ended before this frame did anything. Flushing here as well as from the
                // poll means a gap can never be reported as if it were part of a transition - a
                // screen-off in the middle of one was showing up as a 1311ms "frame interval".
                flushPerf();
            } else if (gap > sPerfWorstGapMs) {
                sPerfWorstGapMs = gap;
            }
        }
        if (sPerfFrames == 0) {
            main().postDelayed(sPerfFlush, PERF_GAP_MS);
            // A new run: the first frame of it has nothing to be a step from.
            sJerkLastY[0] = Float.NaN;
            sJerkLastY[1] = Float.NaN;
            sJerkMax = 0f;
        }
        sPerfLastAt = now;
        sPerfFrames++;
    }

    private static final int PERF_CARD = 0, PERF_GLASS = 1, PERF_GLYPH = 2;

    private static void perfSeg(int which, long t0) {
        long ns = System.nanoTime() - t0;
        sPerfSegNs[which] += ns;
        if (ns > sPerfSegMaxNs[which]) sPerfSegMaxNs[which] = ns;
    }

    private static void perfPlace(long t0) {
        long ns = System.nanoTime() - t0;
        sPerfPlaceNs += ns;
        if (ns > sPerfPlaceMaxNs) sPerfPlaceMaxNs = ns;
    }

    private static void perfPre(long t0) {
        long ns = System.nanoTime() - t0;
        sPerfPreNs += ns;
        if (ns > sPerfPreMaxNs) sPerfPreMaxNs = ns;
    }

    private static void flushPerf() {
        int n = sPerfFrames;
        // Debug builds narrate the transition; release builds stay quiet unless asked, the same
        // rule the preview pipeline's own narration follows. The accounting itself keeps running
        // either way, so `op verbose` still gets the line on a release build.
        if (n > 0 && (DIAG || sVerbose)) {
            long card = sPerfSegNs[PERF_CARD];
            long glass = sPerfSegNs[PERF_GLASS];
            long glyph = sPerfSegNs[PERF_GLYPH];
            long rest = sPerfPlaceNs - card - glass - glyph;
            Xp.log(TAG + "transition frames=" + n + " gapMax=" + sPerfWorstGapMs + "ms"
                    + " | place avg=" + us(sPerfPlaceNs / n) + " max=" + us(sPerfPlaceMaxNs)
                    + " [avg card " + us(card / n) + " glass " + us(glass / n)
                    + " glyph " + us(glyph / n) + " rest " + us(rest / n)
                    + " | max card " + us(sPerfSegMaxNs[PERF_CARD])
                    + " glass " + us(sPerfSegMaxNs[PERF_GLASS])
                    + " glyph " + us(sPerfSegMaxNs[PERF_GLYPH]) + "]"
                    + " | predraw avg=" + us(sPerfPreNs / n) + " max=" + us(sPerfPreMaxNs)
                    + (sJerkFrames > 0 ? " | jerk max=" + r1(sJerkMax) + "px" : ""));
        sJerkFrames = 0;
        sJerkMax = 0f;
        }
        sPerfFrames = 0;
        sPerfPlaceNs = sPerfPlaceMaxNs = 0L;
        sPerfPreNs = sPerfPreMaxNs = 0L;
        sPerfWorstGapMs = 0L;
        for (int i = 0; i < PERF_MAX; i++) {
            sPerfSegNs[i] = 0L;
            sPerfSegMaxNs[i] = 0L;
        }
    }

    private static String us(long ns) {
        return ns < 1000000L ? (ns / 1000) + "us" : r1(ns / 1000000f) + "ms";
    }

    /**
     * The frame the placement last described, waiting for a pre-draw to be finished off.
     *
     * Only the TRANSLATIONS are deferred, and only their geometry is re-read. Everything that
     * has to be measured - the glyph box, the pivot, which views are being scaled - was measured
     * by placeCollapsedClock() and is not re-done here; what moves between the two is where the
     * OEM has since put the clock, which is a reading and not a measurement.
     */
    private static volatile float sPendP = Float.NaN;
    /** What `here` read when the PLACEMENT took it, and when - the other half of the comparison
     *  applyPendingTranslation() makes a frame later. */
    private static volatile float sPlaceHere = Float.NaN;
    private static volatile float sPlaceP = Float.NaN;
    private static volatile long sPlaceAt;
    private static boolean sPendAnchored;
    private static float sPendFrom, sPendTarget, sPendGlyph, sPendGap;

    /**
     * Writes this frame's translations from the geometry the frame will actually be drawn with.
     *
     * placeCollapsedClock() runs inside the OEM's own animation callback, and Folme applies the
     * container's translation for that frame AFTER it returns. So the nudge it writes describes
     * the previous frame's clock and the date is drawn at `target + (O_n - O_n-1)` - displaced
     * by one frame of the OEM's own motion. Smooth styles move a few pixels a frame and nobody
     * can see it; the wake from the AOD moves 728px in one frame, and there the placement's
     * single frame of lag IS the error, drawn as a date 728px from where it belongs.
     *
     * The pre-draw is after every transform for the frame has been applied, so a reading there
     * is the one the frame is drawn with. Both writes are skipped when the value is already
     * right, so a frame the placement got right costs two comparisons.
     */
    private static void applyPendingTranslation() {
        if (Float.isNaN(sPendP) || Float.isNaN(sCollapseMin)) {
            if (sReleasing && sPendP != sPendP) Xp.log(TAG + "pend  skipped: no pending frame");
            return;
        }
        // sReleasing keeps this running through the exit - see the guard's `exiting`.
        if (!sCoverMode && !sReleasing) return;
        View date = sDateView;
        float dateBottom;
        if (sPendAnchored) {
            if (!usableDate(date)) return;
            int[] loc = LOC_PEND;
            date.getLocationOnScreen(loc);
            float here = loc[1] - date.getTranslationY();
            // The same two walks placeCollapsedClock() uses, and it has to be the same
            // expression. On the way out the date travels towards its OWN layout position, not
            // towards the screen line: re-deriving it here with the entry formula would walk it
            // to 228 as p falls while the placement wants 233, and the two would fight for the
            // whole descent.
            float nudge = sReleasing
                    ? sPendTarget + (sPendFrom - sPendTarget) * (1f - sPendP) - here
                    : sPendFrom + (sPendTarget - sPendFrom) * sPendP - here;
            // `here + nudge` is where the date is drawn after this write - the only number that
            // says what the frame actually shows, as opposed to what the placement asked for.
            // (here = loc[1] - dty, so loc[1] becomes here + nudge once dty is nudge.)
            if (sGeomTraceUntil != 0L) {
                Xp.log(TAG + "pend  p=" + r3(sPendP) + " here=" + r1(here)
                        + " cac=" + cacTy() + " dscr=" + r1(here + nudge)
                        + " nudge=" + r1(nudge) + " rel=" + sReleasing
                        + " age=" + (android.os.SystemClock.uptimeMillis() - sPlaceAt) + "ms");
            }
            if (Math.abs(nudge - date.getTranslationY()) >= 0.5f) date.setTranslationY(nudge);
            dateBottom = date.getTop() + date.getHeight() + date.getTranslationY();
        } else {
            dateBottom = date == null ? 0f : date.getTop() + date.getHeight();
        }
        int jerkI = 0;
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            float want = (dateBottom + sPendGap - (g.getTop() + sPendGlyph)) * sPendP;
            if (Math.abs(want - g.getTranslationY()) >= 0.5f) g.setTranslationY(want);
            if (sJerkProbe) jerk(g, jerkI);
            jerkI++;
        }
    }

    /**
     * How far the clock's DRAWN position moves in one frame, in pixels - the probe behind
     * "the collapse is jerky".
     *
     * Deliberately a reading of the screen and not of the numbers this module writes. The clock
     * is moved by two hands: ours (the group scale and its translation, written here) and the
     * OEM's (its squeeze translation on `clock_animation_container`, and the layout it swaps to
     * part-way through a collapse). Only the drawn position sees both, and the date's
     * compensation exists precisely because the OEM's half steps in one frame: on the two-row
     * style `oemTrans` was measured going -16.5 -> -84.2 between p=0.861 and p=0.887, which is
     * 68px in a single frame. Two more frames of that shape and no curve can hide it - the
     * group's own translation is derived from LAYOUT tops, so the OEM's container movement
     * cancels out of it rather than being compensated, and the clock rides the step.
     *
     * Where it is read: the end of applyPendingTranslation(), which is the last write of the
     * frame and therefore the position the frame is drawn with. getLocationOnScreen walks the
     * parent chain, which is why this is a switch and off by default.
     */
    private static volatile boolean sJerkProbe;
    private static final int[] JERK_LOC = new int[2];
    private static final float[] sJerkLastY = {Float.NaN, Float.NaN};
    private static float sJerkMax;
    private static int sJerkFrames;

    private static void jerk(View g, int slot) {
        if (slot >= sJerkLastY.length) return;
        g.getLocationOnScreen(JERK_LOC);
        float y = JERK_LOC[1];
        float prev = sJerkLastY[slot];
        sJerkLastY[slot] = y;
        sJerkFrames++;
        if (Float.isNaN(prev)) return;
        float d = Math.abs(y - prev);
        if (d > sJerkMax) sJerkMax = d;
    }

    /**
     * How far into the cover-mode look a given notifY is, 0..1, or NaN while there is no
     * natural Y to measure it against.
     */
    private static float coverProgress(float y) {
        float natural = sLastSystemY;
        if (Float.isNaN(natural) || natural <= SQUEEZE_FLOOR) return Float.NaN;
        float p = (natural - y) / (natural - SQUEEZE_FLOOR);
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /** The density of whatever screen this process is glued to. */
    static float density() {
        View v = sContainer;
        if (v != null) return v.getResources().getDisplayMetrics().density;
        return sAppCtx != null ? sAppCtx.getResources().getDisplayMetrics().density : 3f;
    }

    /**
     * The glyph box measured with the collapse settled and the screen ON, or null.
     *
     * The two conditions are the whole of what made three earlier attempts at this fail, and
     * each was measured rather than reasoned:
     *
     * - **Settled.** The box is the bounds of whatever the variable font is drawing, and the
     *   keyguard swaps between two layouts part-way through a collapse, moving it from 337px to
     *   415px between one frame and the next. A scale derived per frame from that jumps whenever
     *   the box does - seen as an uneven collapse. Read with the clock parked and nothing moving,
     *   the same triple of holds gives a byte-identical box twelve times running.
     * - **Screen on.** The AOD is a third layout with a third box, and the doze re-asserts place
     *   the clock against it while the screen is off. A box learned there describes a clock that
     *   is not on screen.
     */
    private static volatile RectF sSettledBox;
    /**
     * The digit height that came out of the SAME measurement as sSettledBox.
     *
     * The two travel together or not at all, and that is the whole point. The divisor used to
     * be read live off sInkUnit, which every measurement republishes whichever layout it came
     * from - and the exit necessarily measures the CLOCK IN ITS FULL SIZE, because that is the
     * layout the keyguard puts back the moment cover mode goes off. So the next entry armed its
     * scale from a settled box of the collapsed layout divided by a digit height from the
     * expanded one: measured on all_in_one style 6, 167px of digits over a unit of 936 gave
     * k=0.178 for the whole of the entry, and then the settle re-measured, found 337, and moved
     * the clock to 0.495 in one frame. The collapse looked wrong because it was wrong - it ran
     * all the way down to a fifth of the size and then popped back out to a half.
     */
    private static volatile float sSettledUnit = Float.NaN;
    /** The previous settled reading, which the next one has to agree with. See learnSettledBox. */
    private static volatile RectF sPendingBox;

    /**
     * The group scale that brings the measured glyphs down to the height the setting asks for.
     *
     * Two lengths, so nothing here is resolution- or density-dependent - which is the point.
     * Never above 1: a style whose digits already measure less than the setting is left at its
     * own size rather than stretched, and never below MIN_CLOCK_K.
     */
    private static float kForBox(RectF box) {
        return kForBox(box, sInkUnit);
    }

    /**
     * The same scale, against an explicit digit height - the one measured with the box.
     *
     * Learn-time and arm-time ask the same question about different boxes, and only one of them
     * has a fresh unit in hand: learnSettledBox() is holding the reading it was just handed,
     * while collapseMinScale() is arming from a box remembered from an older pass. Reading
     * sInkUnit at arm time is what paired 337px of digits with a 936px unit.
     */
    private static float kForBox(RectF box, float unit) {
        float u = (Float.isNaN(unit) || unit <= 0f) ? box.height() : unit;
        float k = sClockHeightDp * density() / u;
        return k > 1f ? 1f : (k < MIN_CLOCK_K ? MIN_CLOCK_K : k);
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
        saveState();
        return sClockHeightDp;
    }

    /** The scale to arm a collapse with, before the box for this layout has been measured. */
    private static float collapseMinScale() {
        RectF box = sSettledBox;
        if (box != null && box.height() > 0f) {
            float k = kForBox(box, sSettledUnit);
            Xp.log(TAG + "arm k=" + r3(k) + " from " + boxOf(box)
                    + " unit=" + r1(glyphUnit(box)) + " inkUnit=" + r1(sInkUnit));
            return k;
        }
        Xp.log(TAG + "arm k=" + r3(Float.isNaN(sCollapseMin) ? UNMEASURED_CLOCK_K : sCollapseMin)
                + " with no settled box (inkUnit=" + r1(sInkUnit) + ")");
        // Nothing measured for this layout yet, or a restart that has not reached a settled
        // frame. Keeping what is already applied is right - it is what the clock on screen was
        // placed with, and resetting it would be a jump of its own.
        return Float.isNaN(sCollapseMin) ? UNMEASURED_CLOCK_K : sCollapseMin;
    }

    /**
     * Re-derives the collapse from a box, but only from one taken with the clock parked on a
     * screen that is on. Called from the placement, which is the only thing that has a box.
     *
     * Nothing is written while a collapse is running, so the scale is constant for the whole of
     * every animation and a layout swap cannot be seen as a change of speed. A new settled box
     * does move it, which is correct: the same height in a different layout is a different scale.
     */
    private static void learnSettledBox(RectF box, float p) {
        if (box == null || box.height() <= 0f) return;
        if (p < 0.999f || !sScreenOn) return;
        RectF pending = sPendingBox;
        sPendingBox = new RectF(box);
        if (sVerbose) {
            Xp.log(TAG + "settleBox offer " + boxOf(box) + " unit=" + r1(sInkUnit)
                    + " prev=" + (pending == null ? "none" : boxOf(pending)));
        }
        // Two settled readings in a row that agree, and only then is the box believed.
        //
        // One is not enough, and that is measured rather than reasoned: with the clock parked
        // and the screen on, the box reads 498px while the keyguard is still building itself at
        // startup and 423px once it has settled into cover mode. Both are "the clock is parked",
        // so parking is not the question - whether the layout has stopped is, and the only thing
        // that answers it is the next reading.
        if (pending == null || Math.abs(pending.height() - box.height()) >= 1f
                || Math.abs(pending.top - box.top) >= 1f) {
            // Nothing to compare against yet, and nothing will compare against it either unless
            // something places again: at a plain startup the guard only re-places when it finds
            // the clock stale, and it never does after the first pass. So ask for one, and keep
            // asking until the layout has answered twice - which is what stops this being a
            // question about how long to wait.
            askForAnotherReading();
            return;
        }
        sSettledBox = new RectF(box);
        sSettledUnit = glyphUnit(box);
        if (sVerbose) {
            Xp.log(TAG + "settleBox adopted " + boxOf(box) + " unit=" + r1(sInkUnit)
                    + " -> k=" + r3(kForBox(box)));
        }
        // A pre-dp setting is worth whatever the box it used to multiply measures, and that is a
        // question only a twice-confirmed box can answer too - the first run of this turned 0.335
        // into 55.7dp off the 498px one.
        heightDpFor(box);
        float want = kForBox(box);
        if (!Float.isNaN(sCollapseMin) && Math.abs(want - sCollapseMin) < 0.002f) return;
        sCollapseMin = want;
        Xp.log(TAG + "collapse scale " + r3(want) + " for " + r1(box.height())
                + "px of glyphs at " + r1(sClockHeightDp) + "dp");
    }

    /** When the last with-cover-off needart answer was sent. See the needart branch. */
    private static volatile long sNeedArtOffAt;

    /** Long enough for the frame that asked to have been drawn, short enough to be one settle. */
    private static final long LEARN_RETRY_MS = 150L;

    /** One more placement, to confirm or replace what the last one measured. */
    private static void askForAnotherReading() {
        if (sSettledBox != null) return;          // confirmed already; nothing to ask for
        final View v = sContainer;
        if (v == null) return;
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sCoverMode && sScreenOn) settleCollapsedClock(0);
            }
        }, LEARN_RETRY_MS);
    }

    /**
     * The collapsed clock's height, in dp, from the slider or from a stored value.
     *
     * A value under the slider's own range is a coefficient from before the unit changed: it is
     * set aside rather than clamped, because what it is worth is boxHeight * k and the box is
     * whatever style and layout the phone is on. See sClockLegacyK.
     */
    private static void setClockHeightDp(float v) {
        if (v < CLOCK_HEIGHT_MIN_DP) {
            sClockLegacyK = v;
            Xp.log(TAG + "clock value " + v + " predates the dp unit, converting when measured");
            return;
        }
        sClockLegacyK = Float.NaN;
        sClockHeightDp = v > CLOCK_HEIGHT_MAX_DP ? CLOCK_HEIGHT_MAX_DP : v;
        Xp.log(TAG + "clock height = " + sClockHeightDp + "dp");
    }

    /** The clock size as a fraction of the style's full clock. NaN follows sClockHeightDp. */
    private static void setClockSize(float v) {
        if (Float.isNaN(v) || v <= 0f) {
            sClockSize = Float.NaN;
        } else {
            sClockSize = v < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (v > 1f ? 1f : v);
        }
        Xp.log(TAG + "clock size = " + sClockSize);
    }

    private static void setClockOffsetDp(float v) {
        if (Float.isNaN(v)) v = 0f;
        sClockOffsetDp = v < CLOCK_OFFSET_MIN_DP ? CLOCK_OFFSET_MIN_DP
                : (v > CLOCK_OFFSET_MAX_DP ? CLOCK_OFFSET_MAX_DP : v);
        Xp.log(TAG + "clock offset = " + sClockOffsetDp + "dp");
    }

    static float clockFullRatio() {
        RectF box = glyphBox();
        if (box == null || box.height() <= 0f) return 1f;
        return ClockCollapse.fullRatio(glyphUnit(box));
    }

    static float effectiveClockSize() {
        if (!Float.isNaN(sClockSize)) return sClockSize;
        RectF box = glyphBox();
        if (box == null || box.height() <= 0f) return Float.NaN;
        float unit = glyphUnit(box);
        float full = unit * ClockCollapse.fullRatio(unit);
        if (!(full > 0f)) return Float.NaN;
        float size = sClockHeightDp * density() / full;
        return size < CLOCK_SIZE_MIN ? CLOCK_SIZE_MIN : (size > 1f ? 1f : size);
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
    private static void setClockResponse(float v) {
        sClockResponse = v < CLOCK_RESPONSE_MIN ? CLOCK_RESPONSE_MIN
                : (v > CLOCK_RESPONSE_MAX ? CLOCK_RESPONSE_MAX : v);
        Xp.log(TAG + "clock response = " + sClockResponse + "s, fades "
                + fadeMsFor(sClockResponse) + "ms");
        saveState();
        pushFadeMs(sAppCtx);
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
        View g = findClockView(root, "time_group");
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
            if (sClockTargets.get(root) != g) sClockTargets.put(root, g);
            return g;
        }
        View cached = sClockTargets.get(root);
        if (cached != null && cached != root && usable(cached) && cached.getParent() != null) {
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
            Integer was = sNoClockAt.get(root);
            if (was != null && was == clockSize(root)) return null;
        }
        View found = resolveTimeTarget(root);
        sClockTargets.put(root, found == null ? root : found);
        if (found == null) sNoClockAt.put(root, clockSize(root));
        else sNoClockAt.remove(root);
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
        View clock = clockViewOf(root);
        return clock == null ? -1 : clock.getWidth() * 8191 + clock.getHeight();
    }

    /**
     * Finds whatever draws the time, without knowing what the style calls it.
     *
     * Looking it up by id does not scale: `time_group` and `hour_view` belong to all_in_one,
     * `time_view` to the classic style, and the user has more styles than that. What every style
     * does have in common is a view that draws a clock face, and there are only two kinds:
     *
     * - one of com.miui.clock's own TimeViews, recognisable because it can report the bounds of
     *   the glyphs it strokes (`getTextBoundsWithPosition`);
     * - an ordinary TextView holding something that reads as a time and nothing else.
     *
     * When several turn up - a style that draws the hour and the minute as separate views - the
     * one to scale is their common parent, so they shrink together. Unless that parent also
     * holds the date, which would drag the date along with them; then the first one is scaled on
     * its own, which is wrong-ish but visibly wrong rather than silently absent.
     */
    private static View resolveTimeTarget(View root) {
        // The OEM's own answer first, before any guess at the tree.
        //
        // Every clock style implements IClockView.getIClockView(ClockViewType), and that is how
        // the OEM's own animation code finds the pieces it moves - so it is the one lookup that
        // keeps working for a style this module has never seen. The guess below does not: a
        // rhombus clock draws its digits as vector art, a doodle clock as bitmaps and the
        // oriental ones in Chinese numerals, so "a TextView holding digits and colons" names
        // none of them, and on those styles the clock simply did not shrink.
        for (String type : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME"}) {
            View v = oemPart(root, type);
            if (usable(v)) return v;
        }
        java.util.List<View> parts = new java.util.ArrayList<>();
        for (String type : new String[]{"FULL_HOUR", "FULL_MINUTE", "HOUR1", "HOUR2",
                "MIN1", "MIN2"}) {
            View v = oemPart(root, type);
            if (usable(v)) parts.add(v);
        }
        if (parts.size() == 1) return parts.get(0);
        if (parts.size() > 1) {
            View top = commonAncestor(parts);
            // A parent that also holds the date must not be scaled: the date is what the
            // placement anchors to, and dragging it along makes the anchor chase itself.
            View date = visibleDate();
            if (top != null && !(date != null && isDescendant(top, date))) return top;
        }

        java.util.List<View> hits = new java.util.ArrayList<>();
        collectTimeViews(root, hits);
        // An INVISIBLE container is the normal state of the background layer in cover mode -
        // the clock is being drawn by its notification variant - and requiring the walk to
        // start on a VISIBLE view is why it never started. The children are exactly as visible
        // as they ever are; the container's own visibility says nothing about whether a clock
        // is in there. This alone is what kept magazine, classic and the oriental styles out.
        if (hits.isEmpty() && root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), hits);
        }
        if (hits.isEmpty()) return clockContainerOrNull(root);
        if (hits.size() == 1) return hits.get(0);
        View top = commonAncestor(hits);
        if (top == null) return hits.get(0);
        View date = visibleDate();
        return date != null && isDescendant(top, date) ? hits.get(0) : top;
    }

    /**
     * The OEM's own clock container, as a last resort, or null.
     *
     * For a style whose clock is a single view - the doodle answers ALL_VIEW and CLOCK_CONTAINER
     * with the same graffiti artwork and names nothing else - this is the only answer there is,
     * and it is the right one: on that style the date and the time are drawn into the same
     * picture, so scaling it scales the clock and not something else.
     *
     * Never when the container holds a date this module found. On a style that splits the two,
     * scaling the container would drag the date along, and the date is what the placement hangs
     * the clock from.
     */
    private static View clockContainerOrNull(View root) {
        View cc = oemPart(root, "CLOCK_CONTAINER");
        if (!usable(cc)) return null;
        View date = visibleDate();
        return date != null && isDescendant(cc, date) ? null : cc;
    }

    /**
     * The view at the top of one clock layer, which is that style's own clock: every style's
     * root view implements MiuiClockController.IClockView and is what the OEM asks about itself.
     *
     * Taken from the tree rather than from MiuiClockController.mClockView because the foreground
     * layer has a controller of its own and only one of the two is reachable from the container
     * this module hooks.
     */
    private static View clockViewOf(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        return g.getChildCount() > 0 ? g.getChildAt(0) : null;
    }

    /**
     * One of com.miui.clock.module.ClockViewType's constants by name, or null on a build that
     * has no such constant. The enum is the OEM's, and a name that exists today is not promised
     * to exist on the next HyperOS - so a miss is a miss, not a failure.
     */
    private static Object viewType(String name) {
        java.util.Map<String, Object> m = sViewTypes;
        if (m == null) {
            m = new java.util.HashMap<>();
            try {
                Class<?> cls = Xp.findClass(CLS_CLOCK_VIEW_TYPE,
                        sContainerCls == null ? null : sContainerCls.getClassLoader());
                Object[] all = cls.getEnumConstants();
                if (all != null) {
                    for (Object o : all) m.put(((Enum<?>) o).name(), o);
                }
            } catch (Throwable t) {
                Xp.log(TAG + "no ClockViewType: " + t);
            }
            sViewTypes = m;
        }
        return m.get(name);
    }

    /**
     * What the OEM itself says draws one named part of the clock in one layer, or null.
     *
     * This is the lookup that does not have to be maintained: the OEM finds everything it
     * animates this way, so a clock style added in a future build answers it too.
     */
    private static View oemPart(View root, String type) {
        View clock = clockViewOf(root);
        if (clock == null) return null;
        Object t = viewType(type);
        if (t == null) return null;
        try {
            Object got = Xp.callMethod(clock, "getIClockView", t);
            return got instanceof View ? (View) got : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The nearest common ancestor of a set of views, or null if they share none within reach. */
    private static View commonAncestor(java.util.List<View> views) {
        if (views == null || views.isEmpty()) return null;
        View top = views.get(0);
        for (int guard = 0; guard < 12 && top != null; guard++) {
            if (containsAll(top, views)) return top;
            top = top.getParent() instanceof View ? (View) top.getParent() : null;
        }
        return null;
    }

    private static void collectTimeViews(View v, java.util.List<View> out) {
        if (!usable(v)) return;
        if (isTimeView(v)) {
            out.add(v);
            return;
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectTimeViews(g.getChildAt(i), out);
    }

    private static boolean isTimeView(View v) {
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
        }
        return v instanceof TextView && looksLikeTime(((TextView) v).getText());
    }

    /**
     * "14:51", "14", "9:05" - and not "9月9日周三 七月廿八", "9/9", "32°" or a signature.
     * Digits and colons only, and short: the date lines on these styles are none of that.
     */
    private static boolean looksLikeTime(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().trim();
        if (t.isEmpty() || t.length() > 5) return false;
        boolean digit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') { digit = true; continue; }
            if (c == ':' || c == '\uFF1A') continue;
            return false;
        }
        return digit;
    }

    private static boolean containsAll(View ancestor, java.util.List<View> views) {
        for (View v : views) {
            if (!isDescendant(ancestor, v)) return false;
        }
        return true;
    }

    private static boolean isDescendant(View ancestor, View v) {
        for (int guard = 0; guard < 32 && v != null; guard++) {
            if (v == ancestor) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    /**
     * Resolved clock views, per tree. This is asked for on every frame of the squeeze, and
     * resolving it means a resource lookup and possibly a walk of the whole layer.
     *
     * Dropped when the keyguard is rebuilt, which is also when the clock style can have changed
     * underneath us - see the onAttachedToWindow hook.
     */
    private static final java.util.WeakHashMap<View, View> sClockTargets =
            new java.util.WeakHashMap<>();
    /** The clock size a tree was answered "no clock here" at. See clockTarget(). */
    private static final java.util.WeakHashMap<View, Integer> sNoClockAt =
            new java.util.WeakHashMap<>();
    /** The date view the cache above was filled against. See placeCollapsedClock(). */
    static View sDateView;
    /**
     * When the last "clock style not understood" dump was written, and how often one is allowed.
     *
     * This was a boolean, set here and cleared by the next placement that SUCCEEDED - which is
     * not the same thing as a style changing, and is what turned a diagnostic into a frame-rate
     * bug. A clock that alternates between measurable and not - which is what a keyguard does
     * while it is rebuilding, and what this module's own entry does while the layout swaps
     * part-way through - cleared the flag on one frame and dumped the whole of both clock trees
     * on the next. Each dump walks up to eight levels of the tree twice, builds the string, and
     * writes it to LSPosed over a socket; measured on the device, one transition carried a
     * `place max=20.1ms` against a `place avg=1.0ms` and a 66ms hole between frames, with the
     * dump's own line timestamped inside that transition.
     *
     * A throttle instead of a flag, so the answer is still in the log for a style that really
     * cannot be read, and a flapping one costs one dump per interval rather than one per frame.
     */
    private static long sClockComplainedAt;
    private static final long COMPLAIN_MS = 10000L;

    /**
     * Says why a clock style could not be taken over, at most once every COMPLAIN_MS.
     *
     * This runs on every frame of the squeeze, so it cannot log freely - but a style it cannot
     * read is exactly the thing that needs reporting, and asking the user to reproduce it with a
     * dump is worse than having the answer already in the log.
     */
    private static void reportUnknownClock(View date, RectF pooled) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sClockComplainedAt < COMPLAIN_MS) return;
        sClockComplainedAt = now;
        StringBuilder sb = new StringBuilder("clock style not understood: date=")
                .append(date == null ? "MISSING" : "ok").append(" glyphs=")
                .append(pooled == null ? "MISSING" : pooled.toString());
        for (View root : clockRoots()) {
            sb.append("\n  tree ").append(idOf(root)).append(':');
            describeClockTree(root, sb, 0);
        }
        Xp.log(TAG + sb);
    }

    /** Anything on the tree that carries text or draws glyphs, which is all this needs to see. */
    private static void describeClockTree(View v, StringBuilder sb, int depth) {
        if (depth > 8 || v == null) return;
        boolean text = v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().length() > 0;
        boolean glyphs = isTimeView(v);
        if (text || glyphs) {
            sb.append("\n    ").append(v.getClass().getSimpleName()).append(" #").append(idOf(v))
              .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
              .append(" vis=").append(v.getVisibility());
            if (text) sb.append(" \"").append(((TextView) v).getText()).append('"');
            if (glyphs) sb.append(" [timeview]");
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) describeClockTree(g.getChildAt(i), sb, depth + 1);
    }

    private static String idOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "no-id";
        }
    }

    private static boolean usable(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0;
    }

    private static View findVisibleByName(View v, String name) {
        if (usable(v)) {
            try {
                if (v.getId() != View.NO_ID
                        && name.equals(v.getResources().getResourceEntryName(v.getId()))) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findVisibleByName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The box the digits actually ink, in the target view's own coordinates.
     *
     * Every clock style lays its glyphs out somewhere else - the single-line style puts hour and
     * minute side by side, the stacked one draws the hour at 650 and the minute at 909, the
     * classic one is a single left-aligned line - so this is measured, never assumed.
     */
    private static RectF inkBox(View root, View target) {
        if (target == null) return null;
        RectF box = null;
        java.util.List<View> times = new java.util.ArrayList<>();
        collectTimeViews(target, times);
        // Nothing inside the target reads as a clock, which is the normal case for a style
        // whose digits are not text. The OEM's own parts still name them, so measure those.
        //
        // Not on all_in_one: its target is time_group, the OEM calls the time_group itself
        // FULL_HOUR, and measuring a container as if it were its own glyph is the whole block
        // rather than the ink in it. That style's digits answer to getTextBoundsWithPosition
        // and are found above, so the fallback has nothing to add there and a great deal to
        // spoil - the foreground tree draws no ink in the notification variant, and handing it
        // a full-size box is what turned a correct 578x448 ink box into 1200x1159.
        if (times.isEmpty() && findClockView(root, "time_group") != target) {
            // The individual digits if the OEM names them - FULL_HOUR and FULL_MINUTE are the
            // hour and minute CONTAINERS, which on a style that draws both rows around a gap
            // is most of the clock's height - and only then those two.
            for (String part : new String[]{"HOUR1", "HOUR2", "MIN1", "MIN2"}) {
                View v = oemPart(root, part);
                if (v != target && usable(v) && isDescendant(target, v)) times.add(v);
            }
            if (times.isEmpty()) {
                for (String part : new String[]{"FULL_HOUR", "FULL_MINUTE"}) {
                    View v = oemPart(root, part);
                    if (v != null && v != target && usable(v) && isDescendant(target, v)) {
                        times.add(v);
                    }
                }
            }
            // The OEM's own index is not the only way a style names its digits, and on the
            // ones that draw them as art it answers with a CONTAINER: rhombus's FULL_HOUR is
            // `hour_container`, 1200x1408 wrapped around 489px of digits. Measuring that is the
            // "whole block as the glyph" mistake from the other side - the collapse divides by
            // 1322 instead of 489, so the clock comes out at 43px on a screen that asked for
            // 167. These views do give every digit an id of its own; measured on the device:
            //
            //   rhombus      hour1/2, minute1/2 (MiuiClockNumberView, 489px)
            //   magazine_c   current_time_hour_style1 / _minute_style1 (MiuiTextGlassView, 473px)
            //   doodle       time_hour, time_minute (ImageView, 514px)
            //   depth_pets   time_hour, time_hour_right, time_minute, time_minute_right (400px)
            //   eastern_b    time_below_view_hour1/2, _minute1/2 (TextView, 224px)
            //
            // Read through the same measurement loop below, which already knows a TextView
            // reports its own ink and an ImageView's bounds ARE its ink.
            for (String id : DIGIT_IDS) {
                View v = findByEntryName(target, id);
                if (v != null && v != target && usable(v)) times.add(v);
            }
            // A style whose whole clock really is one view - the doodle, when nothing above
            // answered - has nothing left to measure but itself, and its own bounds are then
            // exactly the clock. Safe here and nowhere else: the guard above keeps this off
            // all_in_one, whose time_group is a container that would report the whole block.
            if (times.isEmpty() && usable(target)) return ownBox(target);
        }
        // Still nothing measurable. Left null rather than falling back to the target's own
        // bounds: a tree that has a clock but no ink in it yet is a clock that has not been
        // through a layout pass, and the box that would come out of it is the block, not the
        // glyphs - the same half-a-box mistake clockPivotX() already documents.
        if (times.isEmpty()) return null;
        StringBuilder who = new StringBuilder("inkBox target=" + viewIdOf(target)
                + " collected=" + times.size() + " :");
        for (View v : times) {
            RectF r = null;
            try {
                r = (RectF) Xp.callMethod(v, "getTextBoundsWithPosition");
            } catch (Throwable ignored) {
            }
            if (r == null || r.height() <= 0f) {
                r = v instanceof TextView ? textInkBox((TextView) v) : null;
                // Digits drawn as vector art or as a bitmap have no text to measure, and a view
                // whose whole content is the glyph has no padding to undo - its own bounds are
                // the ink. That is a rhombus or a doodle digit.
                //
                // NOT for one of the OEM's own TimeViews. A TimeView that cannot report its
                // bounds has simply not been drawn yet, and its bounds are the entire clock
                // container - measuring those is the "block instead of glyphs" mistake that
                // turned all_in_one's 578x448 ink box into 1200x1159, and with it the pivot the
                // whole collapse hangs from.
                if (r == null && !reportsGlyphs(v)) r = ownBox(v);
                if (r == null) continue;
            } else {
                r = new RectF(r);
            }
            // Offsets accumulated up to the target, not just one level: a style is free to wrap
            // its digits in as many layouts as it likes between the two.
            for (View p = v; p != null && p != target; ) {
                r.offset(p.getLeft(), p.getTop());
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            if (box == null) box = r; else box.union(r);
            // The tallest single view, not the union: this is the one number the collapse should
            // divide by. See sInkUnit.
            if (r != null && r.height() > 0f
                    && (Float.isNaN(sInkUnit) || r.height() > sInkUnit)) sInkUnit = r.height();
            who.append(' ').append(viewIdOf(v)).append('=').append(r == null ? "null" : r1(r.height()));
        }
        who.append(" -> ").append(box == null ? "null" : boxOf(box))
           .append(" unit=").append(r1(sInkUnit))
           .append(" p=").append(r1(sPendP));
        if (sGeomTraceUntil != 0L) Xp.log(TAG + who);
        return box;
    }

    /**
     * The same lookup by NAME, walked over the subtree and matched against each view's own
     * resource entry name.
     *
     * `findClockView()` resolves a name through one package's resource table
     * (`com.android.systemui`) and that is where this was first written. Measured on the device
     * with the `op ink` probe: `time_hour` answers from there, but `hour1` (rhombus) and
     * `current_time_hour_style1` (magazine_c) are ids of the clock library's own and resolve to
     * 0, so the pass did nothing at all on exactly the two styles it was written for - and did
     * it silently, which is how a fix can land and change nothing. Reading the name each view
     * already carries does not care which table the id came from.
     */
    private static View findByEntryName(View root, String name) {
        if (root == null) return null;
        try {
            if (root.getId() != View.NO_ID
                    && name.equals(root.getResources().getResourceEntryName(root.getId()))) {
                return root;
            }
        } catch (Throwable ignored) {
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View hit = findByEntryName(g.getChildAt(i), name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The ids styles give the views that draw a time. Not a substitute for the OEM's own index -
     * that is asked first, and on every style whose digits are text it answers correctly - but
     * the only thing that answers at all on the styles that draw them as vector art or bitmaps,
     * where the OEM's index stops at the container.
     *
     * Every one of these was read off the device with the `op ink` probe rather than guessed;
     * a name that is not here costs nothing, and a wrong one would put a container back into
     * the measurement, so the list stays to what was actually seen.
     */
    private static final String[] DIGIT_IDS = {
            "time_hour", "time_minute", "time_hour_right", "time_minute_right",
            "hour1", "hour2", "minute1", "minute2",
            "current_time_hour_style1", "current_time_minute_style1",
            "time_below_view_hour1", "time_below_view_hour2",
            "time_below_view_minute1", "time_below_view_minute2",
            "tv_time", "tv_minute", "tv_hour",
    };

    /** Whether this view can report the bounds of what it draws - one of the OEM's TimeViews. */
    private static boolean reportsGlyphs(View v) {
        if (v == null) return false;
        try {
            v.getClass().getMethod("getTextBoundsWithPosition");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The bounds of a view whose whole content is the glyph it draws. */
    private static RectF ownBox(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        return new RectF(0f, 0f, v.getWidth(), v.getHeight());
    }

    /**
     * The same measurement for a plain TextView, which is what the classic style's clock is.
     *
     * Paint.getTextBounds gives the ink relative to the baseline origin, so it has to be put
     * back together with the layout's baseline and the view's padding. Not getLineTop/Bottom:
     * those are the line box, ascent and descent included, and anchoring the collapse to a line
     * box instead of to the ink is exactly the mistake section 13 is about.
     */
    private static RectF textInkBox(TextView t) {
        Layout lay = t.getLayout();
        CharSequence cs = t.getText();
        if (lay == null || lay.getLineCount() < 1 || cs == null || cs.length() == 0) return null;
        String txt = cs.toString();
        Rect ink = new Rect();
        t.getPaint().getTextBounds(txt, 0, txt.length(), ink);
        if (ink.width() <= 0 || ink.height() <= 0) return null;
        float x = t.getPaddingLeft() + lay.getLineLeft(0) + ink.left;
        float base = t.getPaddingTop() + lay.getLineBaseline(0);
        return new RectF(x, base + ink.top, x + ink.width(), base + ink.bottom);
    }

    /**
     * The union of the drawn digits across both trees, in the target's coordinates.
     *
     * The top anchors the placement, the middle decides the pivot, and the size is what the
     * preview draws at - all three off this one pooled measurement.
     */
    /**
     * How long a measured glyph box is trusted for.
     *
     * placeCollapsedClock() needs the box on every frame of a transition - its top is the pivot
     * the collapse scales about and the anchor the clock hangs from - and measuring it walks both
     * clock trees, collects the digits and asks each one for its text bounds. That is fine once
     * per placement and is not fine sixty times a second with the OEM recomputing a variable font
     * on the same frames.
     *
     * The box is left alone by the squeeze: measured across whole transitions, `glyphTop` is the
     * same number on every frame of one (1099.0269 throughout, 1103.9614 on another run - it is a
     * property of the style and the layout, not of y). So a short trust window costs nothing and
     * a layout change - which is the one thing that does move it - clears it outright.
     */
    private static RectF sGlyphCache;
    private static long sGlyphAt;
    private static final long GLYPH_TTL_MS = 120L;

    /** Drops the remembered box. Called from every place the layout is known to have changed. */
    private static void forgetGlyphBox() {
        // Not while a collapse is in flight: the OEM rebuilding the keyguard under the animation
        // is what makes the box move mid-flight in the first place, and dropping it here is how
        // the placement ends up on the new layout one frame after the transform was decided on
        // the old one. Held until the travel ends.
        float pend = sPendP;
        if (!Float.isNaN(pend) && pend > 0.02f && pend < 0.995f) return;
        sGlyphCache = null;
        sGlyphAt = 0L;
        // And the same for the date's unwound position, because it is the same kind of thing: a
        // measurement of THIS layout. It used to be cleared only when the date view itself was
        // replaced, so it survived every layout swap and every cover-mode cycle, and the entry
        // formula leans on it hard - `dateScreen = from + (target - from) * p`, which at p ~ 0 is
        // simply `from`. Measured on a tap entry: `from` was still 104 from an older layout while
        // the date's real unwound position was 233, so the date was 233 -> 111 in the first frame
        // and then climbed back over the whole transition. 122px, one frame.
        sDateNatural = Float.NaN;
    }

    /**
     * How tall ONE ROW of the clock's digits is, as opposed to how tall the ink block is.
     *
     * The two are the same number on every style that draws its time on a single line - which is
     * most of them, and why this went unnoticed - and they are nothing like each other on the
     * styles that stack the hour over the minute. Measured on the device: rhombus collects four
     * MiuiClockNumberViews of 489px each and their UNION is 1322, because the hour row and the
     * minute row sit 833px apart. The setting says "the height of the digits when collapsed",
     * so dividing by 1322 asks for digits of 62px while every other style gets 167 - the clock
     * comes out a third of the size it should be, and looks broken next to the others.
     *
     * Published by inkBox() as it measures, and taken as the largest across the trees for the
     * same reason the box is unioned: all_in_one draws its hour in one tree and its minute in
     * the other, and the row is as tall as its tallest digit.
     */
    private static volatile float sInkUnit = Float.NaN;

    static float glyphUnit(RectF box) {
        float u = sInkUnit;
        return (Float.isNaN(u) || u <= 0f) ? box.height() : u;
    }

    /** Read-only probe switch: measure through the hold and the TTL cache, change nothing. */
    private static volatile boolean sProbeLive;

    static RectF glyphBox() {
        long now = android.os.SystemClock.uptimeMillis();
        RectF cached = sGlyphCache;
        if (!sProbeLive && cached != null && now - sGlyphAt < GLYPH_TTL_MS) {
            return new RectF(cached);
        }
        // MID-FLIGHT, the box that was measured when this collapse started is the only one that
        // describes the clock being drawn. The keyguard swaps between its two layouts part-way
        // through - measured on all_in_one style 6, three readings inside one entry:
        //
        //     minute_view=747.0 -> 404.0 -> 337.0
        //
        // The placement reads this every frame and its top is the pivot the collapse scales
        // about, so a box that changes under it changes the shape being drawn: that is the
        // collapse looking wrong, and it is the same class of fault this file already documents
        // for the LEARNED box ("a scale derived per frame from that jumps whenever the box
        // does"). That rule only ever guarded learnSettledBox; the placement kept reading live.
        //
        // Held, not frozen permanently: at the ends of the travel - p at 0 or 1 - the newest
        // measurement is the right one again, and a genuine relayout in cover mode gets picked
        // up on the next frame either way.
        float pend = sPendP;
        if (!sProbeLive && cached != null && !Float.isNaN(pend) && pend > 0.02f && pend < 0.995f) {
            if (sGeomTraceUntil != 0L) {
                Xp.log(TAG + "inkBox HELD p=" + r3(pend) + " " + boxOf(cached)
                        + " unit=" + r1(sInkUnit));
            }
            return new RectF(cached);
        }
        RectF box = null;
        sInkUnit = Float.NaN;
        boolean treeDrewNothing = false;
        for (View root : clockRoots()) {
            View target = clockTarget(root);
            RectF r = inkBox(root, target);
            if (r == null) {
                if (usable(target)) treeDrewNothing = true;
                continue;
            }
            if (box == null) box = r; else box.union(r);
        }
        if (box == null) return null;
        if (!treeDrewNothing) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        // (the unit below is published either way - see glyphUnit())
        // A tree that HAS a clock in it but shows no ink is either a clock that has not been
        // through a layout pass, or a tree that is simply not drawing - and those two want
        // opposite answers. Half a box is what makes clockPivotX() take the left-aligned
        // branch: all_in_one draws the hour in one tree and the minute in the other, so the
        // half that happens to be measured first has a centre nowhere near the screen's and
        // the clock collapses towards its own left edge. That is the crooked small clock a
        // SystemUI restart used to leave behind.
        //
        // But answering "no box at all" whenever a tree is empty took a good measurement down
        // with it, and in cover mode that is the normal state: all_in_one draws the WHOLE clock
        // in the background tree (hour_view and minute_view both visible, the foreground copy
        // left at 0x0), while the foreground `#time_group` still answers the id lookup and is
        // still VISIBLE. Voiding on it meant placeCollapsedClock() never ran a single frame,
        // and the size slider did nothing at all - on the style that was supposed to be the
        // working one.
        //
        // So what is left standing is judged instead of thrown away: the union of everything
        // that DID measure is a whole clock exactly when it sits where the screen's centre is,
        // which is the same test clockPivotX() is about to make. Off-centre, it is half of one
        // and the other half is still coming.
        View any = null;
        for (View root : clockRoots()) {
            if (clockTarget(root) != null) { any = root; break; }
        }
        if (any == null) { sGlyphCache = box; sGlyphAt = now; return new RectF(box); }
        float screenW = any.getResources().getDisplayMetrics().widthPixels;
        if (Math.abs(box.centerX() - screenW / 2f) >= screenW * 0.05f) return null;
        sGlyphCache = box;
        sGlyphAt = now;
        return new RectF(box);
    }

    /** A live measurement for ClockCollapse; false keeps the short cache used by previews. */
    static RectF glyphBox(boolean fresh) {
        if (fresh) sGlyphAt = 0L;
        return glyphBox();
    }

    /**
     * {width, height, screen y of the top} for the collapsed clock at scale 1, for the app's
     * preview. Multiplying the first two by the clock scale gives exactly what
     * placeCollapsedClock() puts on screen: it pivots on the glyph top, so the top edge does
     * not move with the scale and this y holds for every scale.
     *
     * Derived the same way placement is - the date at its fixed target, the clock a gap below
     * it - so the preview follows the rule instead of a second set of numbers that can drift.
     */
    /**
     * {w, h, y, x, pivotX} for the collapsed clock at scale 1, in screen pixels.
     *
     * The box is the padded one, because that is what the captures cover; y and pivotX are the
     * two anchors the phone actually scales about - the glyph top and clockPivotX() - so the app
     * can reproduce the same transform: `left = pivotX + (x - pivotX) * scale`. Centred styles
     * come out centred and left-aligned ones stay left, without the app knowing which is which.
     */
    private static float[] clockGeometry() {
        // The date is not required: it is what the anchored placement hangs the clock from, and
        // a style with no date view is scaled where it stands. Its height still enters the y the
        // preview puts the clock at, and with no date there is none to add.
        View date = visibleDate();
        RectF box = paddedGlyphBox();
        if (box == null) return null;
        View g = null;
        for (View root : clockRoots()) {
            View t = clockTarget(root);
            if (t != null) { g = t; break; }
        }
        if (g == null) return null;
        float d = g.getResources().getDisplayMetrics().density;
        return new float[]{box.width(), box.height(),
                dateTargetY() + (date == null ? 0 : date.getHeight()) + CLOCK_GAP_DP * d,
                g.getLeft() + box.left, g.getLeft() + clockPivotX(g, glyphBox())};
    }

    private static RectF paddedGlyphBox() {
        RectF box = glyphBox();
        if (box == null) return null;
        box.inset(-CLOCK_PAD, -CLOCK_PAD);
        return box;
    }

    private static View findClockView(View root, String id) {
        int i = resId(root.getResources(), id);
        return i == 0 ? null : root.findViewById(i);
    }

    /**
     * getIdentifier() walks the package's resource table by name on every call, and the collapse
     * asks for the same handful of ids several times a frame - both clock roots, the clock target
     * inside each, the date, `time_group` for the anchored test. That is measurable against the
     * OEM doing its own animation at the same time and coming out smooth: driving y makes the
     * OEM recompute the variable font every frame, and this used to be added on top of it.
     *
     * The answer cannot change while the process lives - a build either has the id or it does
     * not - so it is remembered, including the 0 a build without it answers.
     */
    private static final java.util.HashMap<String, Integer> sResIds = new java.util.HashMap<>();

    private static int resId(android.content.res.Resources r, String name) {
        Integer hit = sResIds.get(name);
        if (hit != null) return hit;
        int id = r.getIdentifier(name, "id", "com.android.systemui");
        sResIds.put(name, id);
        return id;
    }

    /** The ids the OEM gives a date line, the plain one first. */
    private static final String[] DATE_IDS = {
            "text_area", "current_date", "notification_date", "tv_data", "date_and_time",
    };

    /** What the OEM itself calls the date, for the styles whose id is not in the list above. */
    private static final String[] DATE_PARTS = {
            "TEXT_AREA", "NOTIFICATION_DATE", "FULL_DATE", "FULL_DATE_WEEK",
    };

    private static boolean usableDate(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getHeight() > 0
                && v.isAttachedToWindow();
    }

    /**
     * The date line, on whatever style is loaded.
     *
     * This used to look for `text_area` and nothing else, which is all_in_one's id and
     * classic's. Every other style keeps its date under an id of its own - the rhombus style
     * has `current_date` and `notification_date`, the magazine ones `current_date`, the
     * oriental ones `tv_data` and `date_and_time` - so the lookup returned null, and
     * placeCollapsedClock() refuses to place anything at all without a date to anchor to.
     * That is why the collapse did nothing on those styles even before the driver question.
     *
     * Sticky, because placeCollapsedClock() compares this against the view it placed against
     * last and clears its caches when it changes: a date that alternated between two
     * candidates on consecutive frames would re-adopt the offset every frame.
     */
    static View visibleDate() {
        View last = sDateView;
        if (usableDate(last)) return last;
        for (String id : DATE_IDS) {
            for (View root : clockRoots()) {
                View v = findClockView(root, id);
                if (usableDate(v)) return v;
            }
        }
        for (String part : DATE_PARTS) {
            for (View root : clockRoots()) {
                View v = oemPart(root, part);
                if (usableDate(v)) return v;
            }
        }
        return null;
    }

    /**
     * Whether the clock on screen is the one the anchored placement was measured on.
     *
     * `time_group` is all_in_one's own id - the style the date target, the gap and the
     * one-continuous-motion entry were all measured against. Anything else lays its own date
     * out, and is left where its author put it.
     */
    static boolean anchoredStyle() {
        for (View root : clockRoots()) {
            if (usable(findClockView(root, "time_group"))) return true;
        }
        return false;
    }

    /** Air between the date and the collapsed clock. */
    static final float CLOCK_GAP_DP = 10f;
    /**
     * Where cover mode puts the date, in dp from the top of the screen.
     *
     * A fixed target rather than a "don't collide with the status bar" rule, because the OEM's
     * squeeze leaves each clock style's date at a different height and they should all agree.
     * The value is where the single-line style already lands - measured 240px for the glyphs on
     * this 1200x2608 480dpi screen, i.e. 228px for the view that draws them - while the stacked
     * style was arriving 61px higher.
     *
     * Deliberately not derived from status_bar_height: that resource reads 182px here, which is
     * the whole cutout band rather than anything the eye lines up against.
     *
     * A dp is only ever as portable as the thing it is measured FROM, and this one is measured
     * from the top of the screen. The OEM's own top margin is not a density-scaled quantity - it
     * is the cut-out band plus a fixed inset - so a phone with a taller camera band has the
     * keyguard's content starting lower in dp than this one does, and a pinned 76dp can put the
     * date under the camera. See dateTargetY().
     */
    private static final float DATE_TOP_DP = 76f;
    /** Air between the cut-out and the date, when the cut-out is what decides the line. */
    private static final float DATE_SAFE_GAP_DP = 8f;

    /** Gone off once, so a phone that keeps tripping the guard does not log every frame. */
    private static volatile boolean sDateSafeNoted;

    /**
     * Where cover mode pins the date, in screen pixels.
     *
     * The tuned line, unless the top of the safe area is below it - in which case the date is
     * pushed clear, because being unreadable under the camera is not a matter of taste. On the
     * phone this was matched to the guard never fires: 76dp is 228px against a 144px status bar,
     * so the answer is the tuned value and nothing about the look changes.
     */
    private static float dateTargetY() {
        float tuned = DATE_TOP_DP * density();
        float safe = safeAreaTopPx();
        if (safe <= 0f) return tuned;
        float guarded = safe + DATE_SAFE_GAP_DP * density();
        if (guarded <= tuned + 0.5f) return tuned;
        if (!sDateSafeNoted) {
            sDateSafeNoted = true;
            Xp.log(TAG + "date line: tuned " + r1(tuned) + "px sits under the top inset "
                    + r1(safe) + "px, pinning to " + r1(guarded));
        }
        return guarded;
    }

    /**
     * How deep the top of the screen is unusable, in pixels, or 0 for "no answer".
     *
     * The cut-out first - it is the thing that actually hides content - and the status bar's own
     * inset as well, since a build with no cut-out still has a bar over the top of the keyguard.
     * Both are read off the live window rather than out of a resource: the resource is a
     * per-build number, and the question is about the display in hand.
     *
     * Read through the container, which is the view the keyguard hangs the clock off and so the
     * one that knows which window this is. 0 is a real answer and the caller keeps the tuned
     * line, rather than an exception moving the date to the top of the screen.
     */
    private static float safeAreaTopPx() {
        View v = sContainer;
        if (v == null) return 0f;
        try {
            android.view.WindowInsets insets = v.getRootWindowInsets();
            if (insets == null) return 0f;
            android.view.DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null && cut.getSafeInsetTop() > 0) return cut.getSafeInsetTop();
            int bar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            return bar > 0 ? bar : 0f;
        } catch (Throwable t) {
            // An insets call that throws on some build is not a reason to move the date.
            Xp.log(TAG + "date line: insets unavailable (" + t + "), keeping the tuned line");
            return 0f;
        }
    }

    /**
     * Where the date sits with the squeeze wound all the way off, i.e. at the start of an entry.
     *
     * Sampled at p~0 - which is the one moment the reading means one thing - and used only to
     * shape the path the date takes, never its destination. See the anchored branch of
     * placeCollapsedClock().
     */
    private static volatile float sDateNatural = Float.NaN;

    /**
     * Whether the clock is on its way out of cover mode.
     *
     * It decides how the date is walked - see the two expressions in placeCollapsedClock() - and
     * it is a flag rather than something read off `p` because both directions pass through every
     * value of p.
     */
    private static volatile boolean sReleasing;


    /** Cheap coalescing for a relayout, which arrives as several callbacks in a row. */
    private static volatile long sRelayoutAt;
    private static final long RELAYOUT_MS = 400L;

    /**
     * Re-places the clock when the keyguard lays it out again.
     *
     * Everything the placement writes is derived from the layout - where the date sits, how tall
     * the clock is - and it is only ever written while frames are arriving. The OEM changes that
     * layout when it swaps the clock between its normal and notification variants: measured,
     * `time_group` 2191 -> 1880 and the date's `top` 1320 -> 532. That can happen with the clock
     * already settled, and then there are no frames left to carry the new numbers, so what stays
     * on the views is the OLD transform applied to the NEW layout. With the offset computed live
     * that is a 788px error: the date was measured at screen y = -768, off the top of the screen,
     * with the clock following it because it hangs off dateBottom. The other direction is what
     * drops the clock down over the media card.
     *
     * A layout listener rather than a per-frame check, because this is an event: nothing arrives
     * while the clock is settled, which is exactly when the bug happens.
     *
     * Skipped while a spring is running - the placement already runs on every frame of one, so it
     * picks the new layout up on its own, and re-placing underneath it would only fight it.
     */
    private static final View.OnLayoutChangeListener sRelayout =
            new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            if (l == ol && t == ot && r == or && b == ob) return;
            // Before any of the reasons to skip below: a layout change is exactly what makes the
            // remembered glyph box wrong, whether or not this pass goes on to re-place.
            forgetGlyphBox();
            if (!sCoverMode || Float.isNaN(sCollapseMin)) return;
            if (sFrameCb != null || sRamp != null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sRelayoutAt < RELAYOUT_MS) return;
            sRelayoutAt = now;
            if (sVerbose) {
                Xp.log(TAG + viewIdOf(v) + " laid out again (top " + ot + " -> " + t
                        + ", h " + (ob - ot) + " -> " + (b - t) + "), re-placing");
            }
            settleCollapsedClock(0);
        }
    };

    /** Watches a view the placement is computed from. Idempotent, so it can be called freely. */
    private static void watchRelayout(View v) {
        if (v == null) return;
        v.removeOnLayoutChangeListener(sRelayout);
        v.addOnLayoutChangeListener(sRelayout);
    }

    /**
     * Puts the collapsed clock directly under the date.
     *
     * Anchoring to the date is the whole trick. Every earlier attempt anchored to something
     * absolute - a constant pivot, then the glyphs' own top - and every clock style broke it a
     * different way: one sat too low, another ended up above the date. The date and the clock are
     * siblings inside clock_animation_container, so the OEM's squeeze translation moves both and
     * cancels out of this calculation; what is left is a layout relationship that holds for any
     * style.
     *
     * It is also why waking from AOD no longer flashes. The position is computed from the layout
     * in a single pass, so the first frame is already right - the previous version measured the
     * result on screen and corrected it over the following frames, which is exactly what the jump
     * to the top and the slide back down was.
     */
    private static boolean placeCollapsedClock(float k, float y, float p) {
        View date = visibleDate();
        // A different date view means the clock views were re-inflated - a style change - and
        // anything remembered about the old ones describes a clock that is no longer there.
        // Cheaper and more reliable than watching for the style setting itself, and it catches
        // the case where the container was reused rather than re-attached.
        if (date != sDateView) {
            sDateView = date;
            sClockTargets.clear();
            forgetClockRoots();
            forgetGlyphBox();
            // New clock views are a new layout, so the remembered box and its unit describe a
            // clock that is no longer on screen - and the pair is exactly what the next entry
            // arms its scale from. Keeping them is what makes a wrong size survive a style change.
            sSettledBox = null;
            sSettledUnit = Float.NaN;
            sAppliedK = Float.NaN;
            sAppliedGlassV = Float.NaN;
            // A rebuilt clock is a new set of views, so the watchers have to move with them.
            watchRelayout(date);
            for (View root : clockRoots()) watchRelayout(clockTarget(root));
        }
        // One measurement per frame, pooled across the trees: the top anchors the placement and
        // the middle decides the pivot, and both have to describe the WHOLE clock.
        long glyph0 = System.nanoTime();
        RectF pooled = glyphBox();
        perfSeg(PERF_GLYPH, glyph0);
        learnSettledBox(pooled, p);
        // The anchored placement hangs the clock off the date, so a style that uses it and has
        // lost its date has nothing to hang from. The styles that are only scaled where they
        // are do not want one: the doodle draws its date into the same artwork as its time and
        // has no date view to find, which used to be a second reason to do nothing at all.
        boolean anchored = anchoredStyle();
        // Nothing measurable yet - mid-teardown, or before the first layout. Leaving what is
        // already applied alone is right: resetting would itself be a visible jump.
        if (pooled == null || (anchored && date == null)) {
            reportUnknownClock(date, pooled);
            return false;
        }
        float glyph = pooled.top;

        // The anchored placement - the date pulled onto one fixed line and the clock hung a gap
        // under it - was measured on the all_in_one clock, and it only holds there. Every other
        // style lays its own date out somewhere else entirely: a right-aligned magazine date, a
        // left-aligned oriental one, a doodle that draws the date into the same artwork as the
        // time. Pinning all of them to one line would be a second, unrelated change on top of
        // the shrink, and one nothing has measured. So on those styles the clock is scaled where
        // the OEM put it and neither it nor the date is moved.
        float nudge = 0f;
        float dateBottom;
        // Hoisted out of the two branches below: the pre-draw needs them after the fact, to
        // re-derive this frame's translations from geometry the OEM has finished writing.
        float from = 0f, target = 0f, gap = 0f;
        // Where the date ends this frame, and where it was actually FOUND, both for the
        // verbose trace. The second one is the frame before's rendered position - the read
        // happens before the write - so it is the number that shows a jitter.
        float dateY = Float.NaN;
        float dateRead = Float.NaN;
        String oemNote = "";
        if (anchored) {
            // The date is walked along a straight line in SCREEN space, from where the OEM
            // leaves it to the target, because that is the only way the motion cannot double
            // back on itself.
            //
            // `here` is where the date sits with our own translation taken back off - a reading
            // of the OEM's layout, available on every frame with nothing remembered and nothing
            // to go stale. The obvious way to use it is `nudge = (target - here) * p`, and that
            // is what this was; it blends two curves that do not move together. The date's
            // screen position works out as `here * (1-p) + target * p`, and the OEM pulls `here`
            // in much faster than p rises - measured, 532px down to 64 over one entry. Feeding
            // the real numbers in, `532 - 772p + 468p^2`, the date OVERSHOOTS to 213 by p=0.83
            // and then the `target * p` term drags it back down to 228. Up, then down - visible
            // as a wobble on every entry, and no choice of numbers fixes it, because the fault
            // is the blend and not the constants.
            //
            // So blend the SCREEN POSITION instead of the correction: `from` is where the date
            // sits with the squeeze wound all the way off, sampled at p~0 where that reading is
            // unambiguous, and the position goes `from -> target` linearly in p. Cancelling
            // `here` out of it is what makes the OEM's own motion drop away, so there is nothing
            // left to overshoot with.
            //
            // `from` only shapes the path, never the destination: at p=1 the placement is
            // `target` whatever it holds, which is why a stale one - a different style, or a
            // first entry that never saw p~0 - costs a slightly different route and no accuracy.
            int[] loc = LOC_PLACE;
            date.getLocationOnScreen(loc);
            dateRead = loc[1];
            float here = loc[1] - date.getTranslationY();
            target = dateTargetY();
            // PROBE: what the OEM's own function says this y means, next to what the screen
            // says. Equal means the computed one can be trusted and the measurement - and its one
            // frame of lag - can go.
            Float oemT = oemTranslationY(y);
            if (oemT != null) {
                float computed = layoutScreenY(date) + oemT;
                oemNote = " oem=" + r1(oemT) + " computedHere=" + r1(computed)
                        + " diff=" + r1(computed - here);
            }
            // Sampled ONCE per collapse - at the first placement whose p is anywhere near zero,
            // which on every path that has one is the entry's first frame.
            //
            // It used to be re-sampled on every frame with p < 0.02, and on a wake that is a
            // jump of its own: the ramp starts at p = 0 with the date still at the AOD position
            // (measured, 980), the OEM's container then steps 449px towards the squeeze in the
            // next frame, and a `from` that follows it pins the date to the new reading - so the
            // date is dragged 980 -> 531 in one frame with nothing asked for it. Frozen, the
            // OEM's step is cancelled by the nudge instead and the date stays on the path the
            // ramp puts it on.
            if (Float.isNaN(sDateNatural)) sDateNatural = here;
            from = Float.isNaN(sDateNatural) ? here : sDateNatural;
            // Two walks, one for each direction, and the difference is what the date is
            // travelling TOWARDS.
            //
            // Going in it ends at `target` - one absolute place on the screen - so all that
            // matters is that the path there does not double back, and `from` frozen at the
            // entry's own p~0 gives exactly that: the date's screen position becomes a straight
            // line in p, and nothing the OEM's layout does underneath can move it. Blending the
            // live position instead makes it overshoot to 213px and come back, measured, because
            // `here` falls much faster than p rises.
            //
            // Going out it ends wherever the keyguard's own layout has put it, and that is not
            // `from`: cover mode is what puts the keyguard into its notification layout, so
            // dropping it puts the layout back, and the date's unwound position with it. On this
            // device `from` reads 608 against a real 276 - the frozen line walks the date 330px
            // past where it belongs, and the hold being released then brings it back, which is
            // the jump. Measured before the fix: the date is left at ty=+353.3 and abandonHold()
            // zeroes the translation on the next frame, so the whole 353 arrives at once.
            //
            // The live blend ends exactly, and its one hazard - carrying `here`'s own steps into
            // the date - is at its weakest on the way out: a step shows up multiplied by (1 - p),
            // the layout settles as the squeeze unwinds, and p is spent early.
            // Both directions are the same walk with the ends swapped, and only the exit's was
            // wrong. `(target - here) * p` makes the date's SCREEN position `here*(1-p) + target*p`,
            // and `here` carries the container - so the container's own motion arrives weighted by
            // (1-p), which is exactly backwards: it should be spent, not amplified. Measured on a
            // clean tap exit, drawn positions: 228 -> 220.5 -> 217.2 -> 227.5 -> 229.6. A 10px dip
            // and then a 10.3px single-frame step at the moment the container's last 67px land.
            //
            // Written the other way round the screen position is `target + (from - target) * (1-p)`,
            // which is the same thing stated from the end the date is going to: it leaves the
            // collapsed line and arrives at the natural one, with the container appearing in
            // neither term. Same constants, no remembered value, no frame where it has to catch up.
            nudge = sReleasing ? target + (from - target) * (1f - p) - here
                               : from + (target - from) * p - here;
            sPlaceHere = here;
            sPlaceP = p;
            sPlaceAt = android.os.SystemClock.uptimeMillis();
            if (sGeomTraceUntil != 0L) {
                Xp.log(TAG + "place p=" + r3(p) + " here=" + r1(here) + " cac=" + cacTy()
                        + " dscr=" + r1(here + nudge) + " nudge=" + r1(nudge)
                        + " from=" + r1(from) + " target=" + r1(target));
            }
            date.setTranslationY(nudge);
            dateBottom = date.getTop() + date.getHeight() + nudge;
            // `here` was read before the write above, so this is where the date will be - the
            // one number that says whether the path stayed straight.
            dateY = here + nudge;
        } else {
            dateBottom = date == null ? 0f : date.getTop() + date.getHeight();
        }
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            gap = CLOCK_GAP_DP * g.getResources().getDisplayMetrics().density;
            g.setPivotX(clockPivotX(g, pooled));
            // Pivoting on the glyph top means the scaled block still starts at `glyph`, so the
            // translation needed to land it under the date does not depend on k.
            g.setPivotY(glyph);
            g.setScaleX(k);
            g.setScaleY(k);
            // Faded in with p, exactly like the date's nudge above, and for the same reason:
            // at p=0 the group has to be back at ITS OWN layout position, because that is what
            // abandonHold() hands back to (translationY 0, scale 1) the moment the exit spring
            // lands. The absolute anchor this used to be does not converge there - measured on
            // this screen it left the clock 12px high at p=0 - so the last frame of the exit
            // and the first frame after it differed by those 12px, and the big clock dropped
            // in one step a quarter of a second after it had visibly stopped moving. The same
            // step ran the other way on the first frame of the entry.
            g.setTranslationY(anchored ? (dateBottom + gap - (g.getTop() + glyph)) * p : 0f);
        }
        sPendP = p;
        sPendAnchored = anchored;
        sPendFrom = from;
        sPendTarget = target;
        sPendGlyph = glyph;
        sPendGap = gap;
        recordCollapse(y, p, k, glyph, dateBottom, nudge, date);
        if (sVerbose) {
            Xp.log(TAG + "collapse y=" + y + " p=" + p + " k=" + k
                    + " glyphTop=" + glyph + " dateBottom=" + dateBottom + " nudge=" + nudge
                    + " dateY=" + r1(dateY) + " dateRead=" + r1(dateRead) + oemNote
                    + " from=" + r1(sDateNatural));
        }
        return true;
    }

    // ------------------------------------------------------- transition trace

    /**
     * Records what every moving part of the lock screen is doing, frame by frame, for the
     * length of one transition.
     *
     * For an artifact that lasts a few frames and is not in the wallpaper: the wallpaper
     * process proved its own geometry never moves during a swap, so whatever flashes on screen
     * is a VIEW in this process, and no log written after the fact can say which one. This
     * samples the candidates on the pre-draw of every frame and prints only what CHANGED, so a
     * part that sits still costs one string compare and says nothing, and the part that jumps
     * to the corner of the screen for three frames is the only thing in the output.
     *
     * Armed for a fixed window rather than left running: this is per-frame reflection over a
     * handful of views, which is fine for the 900ms of a transition and not fine forever.
     */
    private static final String[] TRACE_VIEWS = {
            "media_player", "album_art", "album_art_image", "deducted_image_view",
            "keyguard_background_layer", "keyguard_foreground_layer",
            "notification_container_parent", "mi_media_controls",
    };
    private static final long TRACE_MS = 900L;

    private static ViewTreeObserver.OnPreDrawListener sTrace;
    private static View sTraceOn;
    private static long sTraceT0;
    private static final java.util.HashMap<String, String> sTraceLast = new java.util.HashMap<>();
    private static final StringBuilder sTraceOut = new StringBuilder();

    private static void armTransitionTrace(final String why) {
        // Off unless someone is watching: this is per-frame reflection over a handful of views
        // plus a walk of the whole window, which is the right price for 900ms of a bug hunt and
        // the wrong one for every transition forever. `--es op verbose --ez on true` arms it.
        if (!sVerbose) return;
        final View root = sContainer;
        if (root == null || sTrace != null) return;
        sTraceT0 = android.os.SystemClock.uptimeMillis();
        sTraceLast.clear();
        sTraceOut.setLength(0);
        sTraceOut.append("transition trace (").append(why).append("):");
        sTraceOn = root;
        sTrace = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                long dt = android.os.SystemClock.uptimeMillis() - sTraceT0;
                for (String name : TRACE_VIEWS) {
                    View v = findSysuiView(name);
                    String now = v == null ? "gone" : traceLine(v);
                    String was = sTraceLast.put(name, now);
                    if (now.equals(was)) continue;
                    sTraceOut.append("\n  +").append(dt).append("ms ").append(name)
                             .append(' ').append(now);
                }
                sweepForStrays(dt);
                if (dt >= TRACE_MS) {
                    releaseTransitionTrace();
                    Xp.log(TAG + sTraceOut);
                }
                return true;
            }
        };
        root.getViewTreeObserver().addOnPreDrawListener(sTrace);
    }

    /**
     * Anything small being drawn high on the screen that is NOT one of the views above.
     *
     * The named list is a set of guesses, and a guess cannot find an artifact nobody has
     * identified yet - the report is "a small copy of the cover, up in the corner", and the
     * whole point of this pass is to name the view that is. So every frame also walks the
     * keyguard window for a visible view that is under half the screen wide and sitting in the
     * top third, which is what "small, up there" means in numbers. Bounded by the same 900ms
     * window as the rest of the trace.
     */
    private static void sweepForStrays(long dt) {
        View root = sContainer;
        if (root == null || sScreenW <= 0) return;
        sweep(root.getRootView(), dt, 0);
    }

    private static void sweep(View v, long dt, int depth) {
        if (depth > 14 || v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sweep(g.getChildAt(i), dt, depth + 1);
        }
        int w = v.getWidth(), h = v.getHeight();
        // Small, but cover-sized: below an eighth of the screen is the status bar's icons,
        // which jitter by a pixel every frame and would bury the one line that matters.
        if (w < sScreenW / 8 || h < sScreenW / 8 || w > sScreenW / 2) return;
        if (!(v instanceof ImageView) && !(v instanceof android.view.TextureView)) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (loc[1] > sScreenH / 3) return;
        String name = idOf(v);
        String key = "stray:" + name + '@' + System.identityHashCode(v);
        String now = v.getClass().getSimpleName() + ' ' + traceLine(v);
        String was = sTraceLast.put(key, now);
        if (now.equals(was)) return;
        sTraceOut.append("\n  +").append(dt).append("ms STRAY #").append(name)
                 .append(' ').append(now);
    }

    private static String traceLine(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder();
        sb.append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(r2(v.getAlpha()))
          .append(" s=").append(r2(v.getScaleX())).append('/').append(r2(v.getScaleY()))
          .append(" t=").append(r1(v.getTranslationX())).append(',')
          .append(r1(v.getTranslationY()))
          .append(" shown=").append(v.isShown());
        return sb.toString();
    }

    private static void releaseTransitionTrace() {
        View on = sTraceOn;
        ViewTreeObserver.OnPreDrawListener t = sTrace;
        sTraceOn = null;
        sTrace = null;
        if (on == null || t == null) return;
        try {
            on.getViewTreeObserver().removeOnPreDrawListener(t);
        } catch (Throwable ignored) {
        }
    }
    // ------------------------------------------------------------------ diagnostics

    /**
     * A rolling record of what the collapse actually did, for a phone that is not here.
     *
     * The clock lands in the wrong place on some devices and clock styles and not on the one
     * this was written on, and a screenshot cannot say why: every number that decides the
     * placement - the date's settled position, the pooled glyph box, which view was picked as
     * the thing to scale - is measured on the device at the moment it happens. So the module
     * keeps the last few frames' worth and the app can ask for them (op=diag), which turns a
     * report into numbers without the reporter needing adb.
     *
     * Deduplicated on everything but the timestamp: at rest the OEM emits the same y over and
     * over, and a ring full of one identical frame says nothing. What is worth seeing is the
     * ramp into the collapse and the settled value it ended on.
     */
    private static final int DIAG_FRAMES = 20;
    private static final String[] sDiagRing = new String[DIAG_FRAMES];
    private static int sDiagAt;
    private static String sDiagLast = "";
    /** The most recent fully collapsed frame, which the entry ring is too old to hold. */
    private static volatile String sDiagSettled;

    private static void recordCollapse(float y, float p, float k, float glyph, float dateBottom,
                                       float nudge, View date) {
        int[] loc = new int[2];
        if (date != null) date.getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder();
        // p to three places on purpose: `nudge` is (target - where the date sits) * p, so the
        // last hundredth of p is worth almost nothing to it and a report where the date never
        // arrived has to show whether p got there at all.
        sb.append("y=").append(r1(y)).append(" p=").append(r3(p)).append(" k=").append(r2(k))
          .append(" glyphTop=").append(r1(glyph)).append(" dateBottom=").append(r1(dateBottom))
          .append(" nudge=").append(r1(nudge))
          .append(" dateOnScreen=").append(date == null ? -1 : loc[1])
          .append(" dateTop=").append(date == null ? -1 : date.getTop())
          .append(" dateH=").append(date == null ? 0 : date.getHeight())
          .append(" dateTy=").append(date == null ? 0f : r1(date.getTranslationY()));
        for (View root : clockRoots()) {
            View g = clockTarget(root);
            if (g == null) continue;
            int[] gl = new int[2];
            g.getLocationOnScreen(gl);
            sb.append(" | ").append(idOf(g)).append(" top=").append(g.getTop())
              .append(" ty=").append(r1(g.getTranslationY()))
              .append(" onScreen=").append(gl[1]);
        }
        String line = sb.toString();
        if (p >= 0.999f) sDiagSettled = line;
        if (line.equals(sDiagLast)) return;
        sDiagLast = line;
        // The ring is the ENTRY into the collapse and is not overwritten afterwards. Leaving it
        // rolling looked right until you follow what a reporter actually does: see the clock in
        // the wrong place, unlock, then open the app - and unlocking runs the exit animation,
        // whose twenty-odd frames would evict every frame that decided the placement. The entry
        // is where it is decided; the settled line above carries the current answer.
        if (sDiagAt >= DIAG_FRAMES) return;
        sDiagRing[sDiagAt] = line;
        sDiagAt++;
    }

    private static void resetCollapseRecord() {
        sDiagAt = 0;
        sDiagLast = "";
        java.util.Arrays.fill(sDiagRing, null);
    }

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
     * Everything needed to explain where this phone put the clock, as one block of text.
     *
     * Written to be pasted into a bug report by someone who cannot run adb, so it says what it
     * measured AND what it intended: a report where `target` and `dateOnScreen` agree is a
     * report about something other than the placement.
     */
    /**
     * PROBE: what ClockViewType resolves to on the style that is loaded right now.
     *
     * Every clock style answers the OEM's own "where is the time" question - each clock view
     * implements MiuiClockController.IClockView.getIClockView(ClockViewType), and the OEM's
     * animation code uses nothing else to find the pieces it moves. Guessing at the view tree by
     * class and text works for some styles and not at all for others (a rhombus clock draws its
     * digits as vector art, a doodle clock as bitmaps, an oriental one in Chinese numerals), so
     * the guess has to go - but only once it is known what the OEM's own answer actually is per
     * style. That is what this prints.
     */
    /**
     * The OEM's own account of how far the clock has squeezed, and of where its floor is.
     *
     * `KeyguardClockNotifInteractor` decides what the clock looks like for a given notifY by
     * picking one of a dozen named scenes - "Space Squeeze Result", "Scene 2.0 (Time not
     * squeezable)", "Scene 2.1.1.1 (Squeeze height only)", "Scene 2.1.1.2 (Squeeze to min &
     * move up)" - each of which is a formula over the fields below rather than a number. The y
     * the collapse has been holding at - 740 on this device - is not a constant anywhere in the
     * OEM: it is the y at which that choice lands on "not squeezable" any more, which is to say
     * the y at which `timeHeight` has reached `timeMinHeight`.
     *
     * If that holds, then none of it has to be learned: the module is already handed the same
     * ClockResult once per frame, and "how far into the collapse are we" is a question about
     * those two heights - a fraction of the OEM's own squeeze - rather than about pixels of y.
     * Which is what would make the collapse mean the same thing on every phone.
     */
    private static void dumpNotifState() {
        View v = sContainer;
        if (v == null) { Xp.log(TAG + "notif: no clock container"); return; }
        try {
            Object it = Xp.getObjectField(v, "keyguardClockNotifInteractor");
            if (it == null) {
                Xp.log(TAG + "notif: no keyguardClockNotifInteractor on " + v.getClass().getName());
                return;
            }
            StringBuilder sb = new StringBuilder("notif ");
            sb.append(it.getClass().getSimpleName()).append(": ");
            for (String f : new String[]{"timeMinHeight", "baseClockMinHeight", "baseClockMaxHeight",
                    "adaptTimeHeight", "adaptTimeWidth", "adaptWidthHeightRatio",
                    "timeSqueezeRatio", "maxSpace", "dateHeightWithMargin", "notifPre",
                    "clockNotificationMargin", "isParamsValid", "rawNotifY"}) {
                try {
                    sb.append(f).append('=').append(Xp.getObjectField(it, f)).append(' ');
                } catch (Throwable t) {
                    sb.append(f).append("=n/a ");
                }
            }
            try {
                sb.append("| clockResult=").append(Xp.getObjectField(it, "clockResult"));
            } catch (Throwable t) {
                sb.append("| clockResult=n/a");
            }
            Xp.log(TAG + sb.toString());
            Xp.log(TAG + "notif: container getClockBottom=" + Xp.callMethod(v, "getClockBottom")
                    + " getNotificationClockTop=" + Xp.callMethod(v, "getNotificationClockTop")
                    + " hold=" + sHoldY + " lastSystem=" + r1(sLastSystemY));
        } catch (Throwable t) {
            Xp.log(TAG + "notif probe failed: " + t);
        }
    }

    /** Every `ClockViewType` this style answers, and the view it resolves to, per clock tree. */
    private static void dumpClockViewTypes() {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "viewtypes: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Class<?> cvt;
                Object[] all;
                try {
                    cvt = Xp.findClass(CLS_CLOCK_VIEW_TYPE, sContainerCls.getClassLoader());
                    all = cvt.getEnumConstants();
                } catch (Throwable t) {
                    Xp.log(TAG + "viewtypes: no ClockViewType (" + CLS_CLOCK_VIEW_TYPE + "): " + t);
                    return;
                }
                if (all == null) { Xp.log(TAG + "viewtypes: no constants"); return; }
                for (View root : clockRoots()) {
                    View clock = root instanceof ViewGroup ? ((ViewGroup) root).getChildAt(0) : null;
                    Xp.log(TAG + "viewtypes tree " + viewIdOf(root) + ": clock="
                            + (clock == null ? "none" : clock.getClass().getName()));
                    if (clock == null) continue;
                    for (Object ct : all) {
                        Object got;
                        try {
                            got = Xp.callMethod(clock, "getIClockView", ct);
                        } catch (Throwable t) {
                            Xp.log(TAG + "  " + ct + " -> threw " + t);
                            continue;
                        }
                        if (!(got instanceof View)) {
                            Xp.log(TAG + "  " + ct + " -> " + got);
                            continue;
                        }
                        View g = (View) got;
                        int[] loc = new int[2];
                        g.getLocationOnScreen(loc);
                        Xp.log(TAG + "  " + ct + " -> " + g.getClass().getSimpleName()
                                + " #" + viewIdOf(g) + " " + g.getWidth() + "x" + g.getHeight()
                                + " vis=" + g.getVisibility() + " at=" + loc[0] + "," + loc[1]);
                    }
                }
            }
        });
    }

    private static String clockReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("HyperMusicCover clock report\n");
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE)
          .append(")\nbuild: ").append(android.os.Build.DISPLAY)
          .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
          .append(" / SDK ").append(android.os.Build.VERSION.SDK_INT)
          .append("\nos: ").append(sysProp("ro.mi.os.version.name"))
          .append(' ').append(sysProp("ro.mi.os.version.incremental"))
          .append("\n");
        View v = sContainer;
        if (v == null) {
            sb.append("no clock container - the module is loaded but the keyguard has not been "
                    + "built yet, or the hook did not take.\n");
            return sb.toString();
        }
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        sb.append("screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
          .append(" density=").append(dm.density).append(" dpi=").append(dm.densityDpi)
          .append(" moduleScreen=").append(sScreenW).append('x').append(sScreenH)
          .append(" statusBar=").append(sysDimen(v, "status_bar_height"))
          .append("\n");
        sb.append("state: cover=").append(sCoverMode).append(" auto=").append(sAuto)
          .append(" card=").append(sCardKnown ? (sCardShowing ? "showing" : "gone") : "unknown")
          .append(" keyguard=").append(onKeyguardNow())
          .append(" videoWallpaper=").append(sVideoWallpaper)
          .append("\nhold: y=").append(sHoldY).append(" current=").append(r1(sCurrentY))
          .append(" lastSystem=").append(r1(sLastSystemY))
          .append(" floor=").append(SQUEEZE_FLOOR)
          .append(" collapseMin=").append(r2(sCollapseMin))
          .append(" clockH=").append(r1(sClockHeightDp)).append("dp")
          .append(" response=").append(r2(sClockResponse)).append("s")
          .append(" fades=").append(fadeMsFor(sClockResponse)).append("ms")
          // Whether the app's glass slider is live at all: the morph exists only on the styles
          // whose clock view has updateGlassValue, and this is what tells the app which those
          // are. The first thing to look at when the slider appears to do nothing.
          .append(" anchored=").append(anchoredStyle())
          .append(" glassStyle=").append(clockHasGlass())
          .append("\ndate: target=").append(r1(dateTargetY()))
          .append("px from the top; the offset to it is computed per frame from where the date"
                  + " actually sits, so there is nothing remembered to report here")
          .append("\ngap=").append(CLOCK_GAP_DP).append("dp\n");

        View date = visibleDate();
        if (date == null) {
            sb.append("date: NOT FOUND - nothing to anchor the clock to\n");
        } else {
            int[] loc = new int[2];
            date.getLocationOnScreen(loc);
            sb.append("date: #").append(idOf(date)).append(' ')
              .append(date.getClass().getSimpleName())
              .append(" onScreen=").append(loc[0]).append(',').append(loc[1])
              .append(" top=").append(date.getTop()).append(" h=").append(date.getHeight())
              .append(" ty=").append(r1(date.getTranslationY()))
              .append(" text=\"").append(date instanceof TextView
                    ? ((TextView) date).getText() : "?").append("\"\n");
        }
        RectF pooled = glyphBox();
        sb.append("glyphs: ").append(pooled == null ? "NOT MEASURABLE" : pooled.toString())
          .append("\n");
        float[] cg = clockGeometry();
        sb.append("geometry: ").append(cg == null ? "null"
                : "w=" + r1(cg[0]) + " h=" + r1(cg[1]) + " y=" + r1(cg[2])
                  + " x=" + r1(cg[3]) + " pivotX=" + r1(cg[4])).append("\n");

        for (View root : clockRoots()) {
            sb.append("tree ").append(idOf(root)).append(":\n");
            View g = clockTarget(root);
            if (g == null) {
                sb.append("  target: NONE (this tree draws no clock)\n");
            } else {
                int[] gl = new int[2];
                g.getLocationOnScreen(gl);
                boolean byId = g == findClockView(root, "time_group");
                sb.append("  target: #").append(idOf(g)).append(' ')
                  .append(g.getClass().getSimpleName())
                  .append(byId ? " (time_group)" : " (RESOLVED by search)")
                  .append(" left=").append(g.getLeft()).append(" top=").append(g.getTop())
                  .append(' ').append(g.getWidth()).append('x').append(g.getHeight())
                  .append(" onScreen=").append(gl[0]).append(',').append(gl[1])
                  .append(" scale=").append(r2(g.getScaleX())).append('/').append(r2(g.getScaleY()))
                  .append(" pivot=").append(r1(g.getPivotX())).append(',').append(r1(g.getPivotY()))
                  .append(" ty=").append(r1(g.getTranslationY()))
                  .append("\n  ink: ").append(inkBox(root, g)).append('\n');
                // The one thing a screenshot cannot show and the placement depends on entirely:
                // whether the view being scaled also contains the date, which would drag the
                // date along with the clock and make the anchor chase itself.
                if (date != null) {
                    sb.append("  target holds the date: ").append(isAncestor(g, date)).append('\n');
                }
            }
            sb.append("  oem: ").append(oemPartsOf(root)).append('\n');
            describeClockTree(root, sb, 1);
            sb.append('\n');
        }

        sb.append("settled: ").append(sDiagSettled == null ? "never" : sDiagSettled)
          .append('\n');
        sb.append("first ").append(sDiagAt).append(" frames of the collapse")
          .append(sDiagAt == 0 ? " (none - cover mode has not run)" : "").append(":\n");
        for (int n = 0; n < sDiagAt; n++) {
            sb.append("  ").append(sDiagRing[n]).append('\n');
        }
        return sb.toString();
    }

    /** What the OEM's own ClockViewType map says this layer holds. */
    private static String oemPartsOf(View root) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{"TIME_GROUP", "TIME_AREA", "FULL_TIME", "FULL_HOUR",
                "FULL_MINUTE", "HOUR1", "HOUR2", "MIN1", "MIN2", "CLOCK_CONTAINER"}) {
            View v = oemPart(root, part);
            if (!usable(v)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part).append("=#").append(idOf(v)).append(' ')
              .append(v.getWidth()).append('x').append(v.getHeight());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static boolean isAncestor(View ancestor, View child) {
        for (android.view.ViewParent p = child.getParent(); p instanceof View;
             p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object s = sp.getMethod("get", String.class).invoke(null, key);
            String out = s == null ? "" : s.toString();
            return out.isEmpty() ? "?" : out;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String sysDimen(View v, String name) {
        try {
            int id = v.getResources().getIdentifier(name, "dimen", "android");
            return id == 0 ? "?" : String.valueOf(v.getResources().getDimensionPixelSize(id));
        } catch (Throwable t) {
            return "?";
        }
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
    private static MediaController pickController(Context ctx) {
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
            Xp.log(TAG + "getActiveSessions failed: " + t);
            return null;
        }
    }

    private static Bitmap albumArt(Context ctx) {
        return albumArt(ctx, true);
    }

    private static Bitmap albumArt(Context ctx, boolean allowCard) {
        return albumArt(ctx, allowCard, null);
    }

    /**
     * allowCard gates the media card thumbnail. It is the only source when the player publishes
     * nothing but an artwork URI, but it lags a track change by a moment - long enough to hand
     * back the PREVIOUS album - so callers that can afford to wait ask for the session only.
     *
     * sessionBits, when passed, is filled in with what the session turned out to be: 1 it
     * carried a bitmap, 0 it carried none, -1 there was no session to ask. The caller needs the
     * difference between the last two, because one is a player still filling its bitmap in and
     * the other is a player that never publishes one - and this is the only place that sees it.
     */
    private static Bitmap albumArt(Context ctx, boolean allowCard, int[] sessionBits) {
        if (sessionBits != null) sessionBits[0] = -1;
        MediaController c = pickController(ctx);
        if (c != null) {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null && md.getDescription() != null) {
                    b = md.getDescription().getIconBitmap();
                }
                if (b != null) {
                    if (sessionBits != null) sessionBits[0] = 1;
                    Xp.log(TAG + "album art from " + c.getPackageName()
                            + " " + b.getWidth() + "x" + b.getHeight() + " \""
                            + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "\"");
                    return b;
                }
                if (sessionBits != null) sessionBits[0] = 0;
                Xp.log(TAG + c.getPackageName() + " carries no art bitmap (uri="
                        + md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) + ")");
            }
        }
        if (!allowCard) return null;
        return cardThumbnail();
    }

    /**
     * The media card's own thumbnail, read on the main thread - the composing now runs on a
     * worker, and the view tree belongs to the UI thread.
     */
    private static Bitmap cardThumbnail() {
        final View v = sContainer;
        if (v == null) return null;
        final Bitmap[] out = new Bitmap[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    int id = v.getContext().getResources()
                            .getIdentifier("album_art_image", "id", "com.android.systemui");
                    View art = id == 0 ? null : v.getRootView().findViewById(id);
                    if (art instanceof ImageView) {
                        Drawable d = ((ImageView) art).getDrawable();
                        if (d instanceof BitmapDrawable) out[0] = ((BitmapDrawable) d).getBitmap();
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main().post(r);
            try {
                done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (out[0] != null) {
            Xp.log(TAG + "album art from media card thumbnail "
                    + out[0].getWidth() + "x" + out[0].getHeight());
        }
        return out[0];
    }

    /**
     * Everything the app's preview needs that only this process can see.
     *
     * The app draws a lock screen it has no access to: it cannot read the media session (that
     * would need notification access), and the media card and the shortcut buttons are
     * SystemUI's own views. It used to draw its own diagram of the card out of rectangles, which
     * is exactly as convincing as it sounds. So the real views are captured here instead - one
     * software draw each - and the app paints the results into its preview at the coordinates
     * they occupy on the phone.
     *
     * Sizes: the artwork is a 256px JPEG, the card is scaled to CARD_SHOT_W and the shortcuts to
     * SHORTCUT_SHOT_W, all together well inside the ordered broadcast's binder budget. The
     * shortcuts are only sent when asked for - they never change - and the artwork is skipped
     * when the caller says it already has this track's.
     */
    private static android.os.Bundle previewBundle(Context c, Intent i) {
        android.os.Bundle out = new android.os.Bundle();
        // One line per request, in debug builds, saying where every piece of the picture came
        // from or why it is missing. The preview has six independent sources and a user reports
        // it as one thing ("the preview is blank"), so without this every report costs a round
        // trip to find out WHICH source failed.
        StringBuilder diag = DIAG ? new StringBuilder() : null;
        out.putBoolean("alive", true);
        out.putString("track", sCardKey);
        if (diag != null) {
            diag.append("preview: cover=").append(sCoverMode)
                .append(" auto=").append(sAuto)
                .append(" cardKnown=").append(sCardKnown)
                .append(" cardShowing=").append(sCardShowing)
                .append(" keyguard=").append(keyguardShowing())
                .append(" screenOn=").append(screenOn())
                .append(" container=").append(sContainer != null)
                .append(" screen=").append(sScreenW).append('x').append(sScreenH)
                .append(" track=").append(sCardKey.isEmpty() ? "none" : sCardKey);
        }

        if (!sCardKey.isEmpty() && sCardKey.equals(i.getStringExtra("have"))) {
            out.putBoolean("same", true);
            if (diag != null) diag.append("\n  art: same track, not resent");
        } else {
            byte[] thumb = artThumbnail(c, 256);
            if (thumb != null) out.putByteArray("jpg", thumb);
            if (diag != null) {
                diag.append("\n  art: ").append(thumb == null ? "NULL" : thumb.length + "B");
                if (thumb == null) {
                    MediaController mc = pickController(c);
                    diag.append(" (session=")
                        .append(mc == null ? "none" : mc.getPackageName())
                        .append(" metadata=")
                        .append(mc == null || mc.getMetadata() == null ? "none" : "yes")
                        .append(" cardThumb=").append(cardThumbnail() == null ? "none" : "yes")
                        .append(')');
                }
            }
        }

        View cardView = cardShotRoot(findSysuiView("mi_media_controls"));
        View mediaBg = cardView == null ? null : findByName(cardView, "media_bg");
        // 24dp on this device - notification_item_bg_radius, which media_bg's own outline
        // agrees with. The app rounds its stand-in frosting to the same number.
        if (mediaBg != null) out.putFloat("cardradius", outlineRadius(mediaBg));
        putArtSlot(out, cardView);
        boolean dump = i.getBooleanExtra("dump", false);
        // The artwork at full size, for lifting a sample cover out of a running phone.
        if (dump) {
            Bitmap raw = albumArt(c);
            if (raw != null) {
                try {
                    java.io.FileOutputStream f = new java.io.FileOutputStream(
                            new java.io.File(sAppCtx.getFilesDir(), "mc_shot_art.jpg"));
                    raw.compress(Bitmap.CompressFormat.JPEG, 95, f);
                    f.close();
                    Xp.log(TAG + "shot art " + raw.getWidth() + "x" + raw.getHeight());
                } catch (Throwable t) {
                    Xp.log(TAG + "art dump failed: " + t);
                }
            }
        }
        byte[] card = shootCard(cardView);
        if (dump) dumpShot("card", card);
        if (card != null) {
            out.putByteArray("card", card);
            // The SAMPLED rectangle, not a live reading: on an unlocked phone this same view is
            // the one at the top of the shade, six hundred pixels higher up.
            out.putIntArray("cardrect", new int[]{sCardL, sCardT, sCardW, sCardH});
        }
        if (diag != null) {
            diag.append("\n  card: view=");
            if (cardView == null) {
                diag.append("NOT FOUND (no media card in the keyguard tree)");
            } else {
                diag.append(cardView.getClass().getSimpleName())
                    .append(' ').append(cardView.getWidth()).append('x')
                    .append(cardView.getHeight())
                    .append(" vis=").append(cardView.getVisibility());
            }
            diag.append(" shot=").append(card == null ? "NULL" : card.length + "B")
                .append(" rect=").append(sCardL).append(',').append(sCardT).append(' ')
                .append(sCardW).append('x').append(sCardH)
                .append(" artslot=").append(out.containsKey("artfrac") ? "yes" : "no")
                .append(" hideArt=").append(sMcHideArt)
                .append(" centerText=").append(sMcCenterText);
        }

        putDate(out, dump);
        // Sent with the pictures, not only in answer to a query. The query happens once, when
        // the page is opened; the clock's geometry changes whenever the user changes the lock
        // screen clock style, and a stale zero here is the app holding a picture of a clock it
        // then refuses to draw for want of somewhere to put it.
        float[] cg = clockGeometry();
        if (cg != null) {
            out.putFloat("clockw", cg[0]);
            out.putFloat("clockh", cg[1]);
            out.putFloat("clocky", cg[2]);
            out.putFloat("clockx", cg[3]);
            out.putFloat("clockpivotx", cg[4]);
            out.putFloat("clockpad", CLOCK_PAD);
        }
        out.putBoolean("clockglass", clockHasGlass());
        if (diag != null) {
            View date = visibleDate();
            diag.append("\n  date: view=").append(date == null ? "NOT FOUND"
                    : date.getClass().getSimpleName() + " " + date.getWidth() + "x"
                      + date.getHeight() + " vis=" + date.getVisibility())
                .append(" shot=").append(out.containsKey("date") ? "yes" : "NULL");
            diag.append("\n  clock: geom=").append(cg == null ? "NULL (no date or no glyph box)"
                    : "w" + cg[0] + " h" + cg[1] + " y" + cg[2] + " x" + cg[3] + " pivot" + cg[4]);
            View[] roots = clockRoots();
            diag.append(" roots=").append(roots.length);
            for (int t = 0; t < roots.length; t++) {
                View g = clockTarget(roots[t]);
                diag.append(" target").append(t).append('=')
                    .append(g == null ? "none" : g.getClass().getSimpleName() + "#" + idOf(g));
            }
        }

        // Both clock trees: the hour is drawn in the background layer and the minute in the
        // foreground one, and each carries half the time.
        for (int t = 0; t < 2; t++) {
            byte[] png = shootClock(t);
            if (dump) dumpShot("clock" + t, png);
            if (png != null) out.putByteArray("clock" + t, png);
            if (diag != null) {
                diag.append(" shot").append(t).append('=')
                    .append(png == null ? "NULL" : png.length + "B");
            }
        }
        if (diag != null && !sClockShot) diag.append(" [clock capture switched OFF]");

        if (i.getBooleanExtra("shortcuts", false)) {
            putShortcut(out, "sl", "shortcut_view_left", dump);
            putShortcut(out, "sr", "shortcut_view_right", dump);
            if (diag != null) {
                diag.append("\n  shortcuts: asked, left=")
                    .append(out.containsKey("sl") ? "yes" : "NOT FOUND")
                    .append(" right=").append(out.containsKey("sr") ? "yes" : "NOT FOUND");
            }
        } else if (diag != null) {
            diag.append("\n  shortcuts: not asked for (the app already has them)");
        }

        if (diag != null) Xp.log(TAG + diag);
        return out;
    }

    /**
     * Where the card draws the album art, and how rounded it is.
     *
     * The artwork is the one part of the card the app has to draw itself. A software canvas does
     * not apply a view's outline clip - only a hardware one does - so the captured card came
     * back with a hard-cornered square where the OEM draws a rounded one. Everything else about
     * it survives the capture, so only the artwork is cut out of the picture and painted back.
     *
     * The radius is read off the view's own OutlineProvider rather than a resource: it is the
     * number actually being clipped with, and it costs one call.
     */
    private static void putArtSlot(android.os.Bundle out, View card) {
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) return;
        // Resolved off the card rather than read from the guard's cache: the guard is only
        // installed when one of the two card settings is on, and the app asks for this picture
        // whether or not anything has been switched on.
        View box = findByName(card, "album_art");
        View img = box == null ? null : findByName(box, "album_art_image");
        if (img == null) return;
        // Hidden by the user's own setting - nothing to draw, and saying so is how the app knows.
        if (sMcHideArt || box.getVisibility() != View.VISIBLE) return;
        if (img.getWidth() <= 0 || img.getHeight() <= 0) return;
        int[] a = new int[2], b = new int[2];
        img.getLocationOnScreen(a);
        card.getLocationOnScreen(b);
        // FRACTIONS of the card, not pixels. The app pastes the capture into the rectangle the
        // card occupies on the LOCK screen, but the card it captured may have been laid out at
        // some other width - pull the shade down over the app and it is the same view, sized for
        // the shade - and pixels measured there land in the wrong place once the capture has
        // been stretched to the lock screen's width. Fractions survive the stretch.
        float w = card.getWidth(), h = card.getHeight();
        out.putFloatArray("artfrac", new float[]{(a[0] - b[0]) / w, (a[1] - b[1]) / h,
                img.getWidth() / w, img.getHeight() / h});
        // Measured on this device: the ImageView is not clipped at all, its CONTAINER is -
        // clipToOutline with a 30px radius - so the radius has to be looked for on both.
        float r = img.getClipToOutline() ? outlineRadius(img) : 0f;
        if (r <= 0f && box.getClipToOutline()) r = outlineRadius(box);
        out.putFloat("artradius", r / w);
    }

    private static float outlineRadius(View v) {
        try {
            android.graphics.Outline o = new android.graphics.Outline();
            v.getOutlineProvider().getOutline(v, o);
            float r = o.getRadius();
            if (r >= 0f) return r;
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private static final int DATE_SHOT_W = 360;

    /**
     * The date line above the clock, captured like everything else rather than re-typed.
     *
     * It has to be the real view: the format, the language, the font and the colour all follow
     * the lock screen clock style, and a date drawn here would be a second thing to keep in step
     * with a theme the user can change at any moment. Because the app re-asks every few seconds,
     * a style change simply arrives with the next picture.
     *
     * The y is COMPUTED, not read. Cover mode pins the date to DATE_TOP_DP (placeCollapsedClock
     * anchors everything else to it), and the app asks for this with the phone unlocked, where
     * the live position is whatever the hidden keyguard happens to be holding. The x comes off
     * the view because it does not depend on the squeeze.
     */
    private static void putDate(android.os.Bundle out, boolean dump) {
        View date = visibleDate();
        if (date == null || date.getWidth() <= 0 || date.getHeight() <= 0) return;
        byte[] png = shoot(date, DATE_SHOT_W);
        if (dump) dumpShot("date", png);
        if (png == null) return;
        int[] loc = new int[2];
        date.getLocationOnScreen(loc);
        out.putByteArray("date", png);
        out.putIntArray("daterect", new int[]{loc[0], Math.round(dateTargetY()),
                date.getWidth(), date.getHeight()});
    }

    private static final int CLOCK_SHOT_W = 480;
    /**
     * Slack around the glyph box before cropping. getTextBoundsWithPosition() reports the text's
     * own bounds, but the digits are STROKED - in glass mode they are nothing but stroke - so
     * half a stroke width falls outside it and the crop shaved the last digit.
     */
    private static final float CLOCK_PAD = 10f;

    private static volatile boolean sClockShot = true;

    /**
     * The clock's digits, cropped to the glyphs, drawn WITHOUT the collapse scale - the app
     * applies that itself, so dragging the size slider does not need a new picture.
     *
     * These are the OEM's own variable-font paths, which is why they can be captured at all:
     * TimeView.onDraw strokes a Path, and a Path draws into a software canvas like anything
     * else. The refracting glass is a RuntimeShader and does not, so what comes back is the
     * digits as they are filled at the moment of the capture.
     */
    private static byte[] shootClock(int which) {
        if (!sClockShot) return null;
        View[] roots = clockRoots();
        if (which >= roots.length) return null;
        View g = clockTarget(roots[which]);
        RectF box = paddedGlyphBox();
        if (g == null || box == null || box.width() <= 0 || box.height() <= 0) return null;
        try {
            float k = Math.min(1f, CLOCK_SHOT_W / box.width());
            int w = Math.max(1, Math.round(box.width() * k));
            int h = Math.max(1, Math.round(box.height() * k));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            cv.translate(-box.left, -box.top);
            g.draw(cv);
            redrawAfterCapture(g);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.log(TAG + "clock capture failed: " + t);
            return null;
        }
    }

    /**
     * Whether the card is currently laid out the way the lock screen lays it out.
     *
     * The app stretches this picture into the rectangle the card occupies on the LOCK screen, so
     * a picture of the card in any other layout is wrong by construction - and the shade
     * re-lays-out the very same view for its own panel. Taken there and stretched into the lock
     * screen's rectangle, the card's content ended up crammed into the top-left corner.
     *
     * The test is the sampled size rather than "is the shade open", because it is the size that
     * the stretch actually depends on: whatever the shade, an AOD or a future panel does to the
     * card, a picture is only usable if it was taken at the size it will be drawn at. When
     * nothing has been sampled yet there is no invariant to protect and the picture is taken.
     */
    private static boolean cardLaidOutForKeyguard(View card) {
        if (sCardW <= 0 || sCardH <= 0) return true;
        boolean ok = Math.abs(card.getWidth() - sCardW) <= 2
                && Math.abs(card.getHeight() - sCardH) <= 2;
        if (!ok && !sCardShapeComplained) {
            sCardShapeComplained = true;
            Xp.log(TAG + "card is " + card.getWidth() + "x" + card.getHeight()
                    + ", lock screen says " + sCardW + "x" + sCardH + " - not photographing it");
        }
        if (ok) sCardShapeComplained = false;
        return ok;
    }

    private static boolean sCardShapeComplained;

    /**
     * What to photograph for "the media card".
     *
     * Not mi_media_controls, which is only the card's contents: the bright rim along the card's
     * edge is a FOREGROUND drawable on its parent, MiuiMediaHeaderView, and a foreground is
     * drawn after the children - so starting the capture at the child left the rim out
     * altogether. The parent has the same bounds, so nothing else about the picture changes.
     *
     * Guarded on the bounds matching rather than on the class name, because everything measured
     * and reported around the card - the sampled rectangle, the artwork's slot - is relative to
     * mi_media_controls, and only an identically placed parent keeps those true.
     */
    private static View cardShotRoot(View card) {
        if (card == null) return null;
        if (!(card.getParent() instanceof View)) return card;
        View p = (View) card.getParent();
        boolean sameBox = p.getWidth() == card.getWidth() && p.getHeight() == card.getHeight()
                && card.getLeft() == 0 && card.getTop() == 0;
        return sameBox ? p : card;
    }

    /** Torch and camera. Their layout is the same locked or unlocked, so this reads it live. */
    private static void putShortcut(android.os.Bundle out, String key, String id, boolean dump) {
        View v = findSysuiView(id);
        if (v == null) {
            Xp.log(TAG + id + " not found");
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
        byte[] png = shoot(v, SHORTCUT_SHOT_W);
        if (dump) dumpShot(key, png);
        if (png == null) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        out.putByteArray(key, png);
        out.putIntArray(key + "rect", new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()});
    }

    /** Debug builds narrate the preview pipeline; release builds only do so on `op=verbose`. */
    private static final boolean DIAG = BuildConfig.DEBUG;

    private static final int CARD_SHOT_W = 420;
    private static final int SHORTCUT_SHOT_W = 160;

    /**
     * `--es op preview --ez dump true` drops the captures next to the state file, for looking at
     * when the preview shows the wrong thing. A capture can fail quietly - a view that renders
     * only on a hardware canvas comes out blank rather than throwing - and the size of the PNG
     * is not enough to tell that apart from a picture that is simply mostly transparent.
     */
    private static void dumpShot(String name, byte[] png) {
        if (sAppCtx == null) return;
        try {
            java.io.File f = new java.io.File(sAppCtx.getFilesDir(), "mc_shot_" + name + ".png");
            if (png == null) {
                Xp.log(TAG + "shot " + name + " = null");
                return;
            }
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(png);
            o.close();
            Xp.log(TAG + "shot " + name + " " + png.length + "B -> " + f);
        } catch (Throwable t) {
            Xp.log(TAG + "shot dump failed: " + t);
        }
    }

    /**
     * The media card as a picture.
     *
     * The two card settings only apply on the keyguard (see assertMediaCard), but the app asks
     * for this with the phone unlocked and in its own foreground, and what it needs to show is
     * what the LOCK screen will look like. So they are forced on for the duration of the draw
     * and put straight back. Nothing can be drawn to the screen in between - this is the main
     * thread, and a traversal cannot interleave with a broadcast receiver - so the real card
     * never flickers.
     */
    private static byte[] shootCard(View card) {
        if (card == null) return null;
        if (!cardLaidOutForKeyguard(card)) return null;
        View box = findByName(card, "album_art");
        View img = box == null ? null : findByName(box, "album_art_image");
        // View.VISIBLE rather than a -1 sentinel for "no view": the null check below already
        // covers that case, and lint tracks the @Visibility typedef through this assignment -
        // a sentinel outside the set is a WrongConstant error at the restore.
        int wasVisible = img == null ? View.VISIBLE : img.getVisibility();
        sCardForced = true;
        try {
            assertMediaCard(card);
            // Cut the artwork out of the picture; the app paints it back rounded (putArtSlot).
            // The badge over its corner is a sibling, so it still lands on top where it belongs.
            if (img != null) img.setVisibility(View.INVISIBLE);
            return shoot(card, CARD_SHOT_W);
        } catch (Throwable t) {
            Xp.log(TAG + "card capture failed: " + t);
            return null;
        } finally {
            if (img != null) img.setVisibility(wasVisible);
            sCardForced = false;
            assertMediaCard(card);
        }
    }

    /**
     * One view, drawn into a bitmap at most maxW wide, as a PNG.
     *
     * PNG rather than JPEG because these have to keep their alpha: the card is a rounded
     * translucent panel and the shortcuts are circles, and squaring either off would put a hard
     * rectangle on top of the wallpaper in the preview.
     */
    private static byte[] shoot(View v, int maxW) {
        return shoot(v, maxW, null);
    }

    /**
     * extraBg is a child whose BACKGROUND has to be drawn by hand, for views MIUI's blur path
     * skips on a software canvas.
     */
    private static byte[] shoot(View v, int maxW, View extraBg) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            float k = Math.min(1f, maxW / (float) w);
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            drawBackgroundOf(extraBg, cv);
            v.draw(cv);
            redrawAfterCapture(v);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Xp.log(TAG + "view capture failed: " + t);
            return null;
        }
    }

    /**
     * Puts a captured view back in line for a real redraw.
     *
     * Drawing a live view into an offscreen canvas is not free of consequences: View.draw()
     * leaves the view marked as drawn, and the framework is then entitled to skip it on the next
     * real frame, which shows up as stale pixels - half a clock, digits that did not follow the
     * minute over. Suspected cause of "the lock screen font is sometimes incomplete", reported
     * after the clock started being captured every few seconds.
     *
     * invalidate() does not reach children, and it is a child that draws the glyphs, so this
     * walks. Cheap: a handful of views, once per capture, and an invalidate on an unchanged view
     * costs one more draw of something that was going to be drawn anyway.
     */
    private static void redrawAfterCapture(View v) {
        if (v == null) return;
        v.invalidate();
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) redrawAfterCapture(g.getChildAt(i));
    }

    /** Draws one child's background at its place in the parent, without going through the view. */
    private static void drawBackgroundOf(View v, android.graphics.Canvas cv) {
        if (v == null) return;
        Drawable d = v.getBackground();
        if (d == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        Rect old = new Rect(d.getBounds());
        try {
            cv.save();
            cv.translate(v.getLeft(), v.getTop());
            d.setBounds(0, 0, v.getWidth(), v.getHeight());
            d.draw(cv);
        } catch (Throwable ignored) {
        } finally {
            d.setBounds(old);
            cv.restore();
        }
    }

    /** Keyed on the artwork itself, so the app can re-ask as often as it likes for free. */
    private static volatile int sThumbPrint;
    private static volatile byte[] sThumbJpg;

    /**
     * The current artwork, small, as a JPEG. Runs on the receiver's thread - the main one - so
     * it stays deliberately cheap: one downscale and one encode of a picture a few hundred
     * pixels wide, and nothing at all when the artwork has not changed since the last ask.
     */
    private static byte[] artThumbnail(Context ctx, int max) {
        Bitmap b = albumArt(ctx);
        if (b == null) return null;
        int print = artPrint(b);
        byte[] cached = sThumbJpg;
        if (cached != null && print != 0 && print == sThumbPrint) return cached;
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, max / (float) Math.max(w, h));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true) : b;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            if (small != b) small.recycle();
            byte[] jpg = bos.toByteArray();
            sThumbPrint = print;
            sThumbJpg = jpg;
            return jpg;
        } catch (Throwable t) {
            Xp.log(TAG + "art thumbnail failed: " + t);
            return null;
        }
    }

    /**
     * The only writer, and the only recycler, of the shade layer's picture.
     *
     * Safe to call from the worker: the view swap is posted to the UI thread by
     * {@link ShadeLayer#setArt}, and the bitmap is not freed here either.
     *
     * The old generation is retired rather than freed on the spot, because setArt() posts and a
     * post is drawn on a later frame - the display list that still names the old bitmap is only
     * gone after that. Two frames is the margin. A second replacement arriving sooner than that
     * proves the frames went by, so the slot's previous occupant is freed then instead; without
     * that, a fast skip through tracks would retire a bitmap and then overwrite the only
     * reference to it, and a 12.5MB leak per skip is not a thing to ship.
     */
    private static void setShadeArt(final Bitmap b) {
        final Bitmap old = sShadeArt;
        if (old == b) return;
        sShadeArt = b;
        ShadeLayer.setArt(b);
        if (old == null || old.isRecycled()) return;

        final Bitmap prev = sShadeRetired;
        sShadeRetired = old;
        if (prev != null && prev != old && !prev.isRecycled()) prev.recycle();
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sShadeRetired != old) return;
                sShadeRetired = null;
                if (!old.isRecycled()) old.recycle();
            }
        }, 32L);
    }

    private static void clearShadeArt() {
        setShadeArt(null);
    }

    /**
     * What the shade reveals in mode 1 when cover mode is off: the wallpaper that is actually
     * behind it.
     *
     * The HOME wallpaper and not the lock one. The ported module used
     * `MiuiKeyguardWallPaperManager.getKeyguardWallPaperPreview()`, which is the lock screen's -
     * but the notification shade is pulled down over the desktop, where the wallpaper is a
     * different picture most of the time. Revealing the lock wallpaper there would swap the
     * thing the user is looking at for an unrelated photo.
     *
     * Owned by the same rules as sShadeArt: setShadeWallpaper() is the only writer and the only
     * recycler.
     */
    private static volatile Bitmap sShadeWallpaper;
    private static volatile Bitmap sShadeWallpaperRetired;
    /** One load in flight at a time; the shade asks on every frame it has no picture. */
    private static volatile boolean sShadeWpLoading;

    /** Asked for by the shade layer when mode 1 wants a background and has none. */
    static void requestShadeWallpaper() {
        final Context ctx = sAppCtx;
        if (ctx == null || sShadeWpLoading) return;
        sShadeWpLoading = true;
        worker().post(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    String kind = wallpaperKind(ctx, "home");
                    if (kind == null || KIND_IMAGE.equals(kind)) {
                        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                                ctx.getSystemService(Context.WALLPAPER_SERVICE);
                        if (wm != null) b = homeWallpaper(wm);
                    } else {
                        // A live home wallpaper is not a picture anything can hold. homeWallpaper
                        // would hand back a still of one frame, which is a lie the user sees as
                        // the shade revealing something that is not on their screen.
                        Xp.log(TAG + "shade wallpaper: the home wallpaper is " + kind
                                + ", not an image - mode 1 will reveal nothing rather than a "
                                + "still of it");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "shade wallpaper load failed: " + t);
                }
                sShadeWpLoading = false;
                if (b == null) return;
                setShadeWallpaper(b);
                Xp.log(TAG + "shade wallpaper loaded " + b.getWidth() + "x" + b.getHeight());
            }
        });
    }

    private static void setShadeWallpaper(final Bitmap b) {
        final Bitmap old = sShadeWallpaper;
        if (old == b) return;
        sShadeWallpaper = b;
        ShadeLayer.setWallpaper(b);
        if (old == null || old.isRecycled()) return;
        final Bitmap prev = sShadeWallpaperRetired;
        sShadeWallpaperRetired = old;
        if (prev != null && prev != old && !prev.isRecycled()) prev.recycle();
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sShadeWallpaperRetired != old) return;
                sShadeWallpaperRetired = null;
                if (!old.isRecycled()) old.recycle();
            }
        }, 32L);
    }

    /** The wallpaper is not the one we cached any more. */
    static void invalidateShadeWallpaper() {
        setShadeWallpaper(null);
    }

    /**
     * Hands the album art to the wallpaper process. It cannot read the media session itself, and
     * a full-screen bitmap is far past the binder limit, so send it as a JPEG the size of the
     * screen - already composed here, where we know the screen metrics.
     *
     * Composing costs a full-screen bitmap and a multi-pass blur, so callers go through
     * pushArtAsync() rather than calling this on the main thread.
     */
    private static void pushArtToWallpaper(Context ctx, boolean on) {
        pushArtToWallpaper(ctx, on, on ? albumArt(ctx) : null);
    }

    private static void pushArtToWallpaper(Context ctx, boolean on, Bitmap art) {
        // A live lock wallpaper needs BOTH halves, and returning here after the first was why
        // the card and the clock glass stayed on the video: this view covers the background,
        // but those two sample the wallpaper WINDOW, and the only thing that paints it is the
        // broadcast below. Falling through is what makes the wallpaper process paint it.
        //
        // It costs one extra compose per track change on this path - showVideoCover() composes
        // for the view and the JPEG below composes again. Worth folding into one later; the
        // correctness of having both matters more than the ~100ms.
        if (sVideoWallpaper) showVideoCover(ctx, on, art);
        long t0 = android.os.SystemClock.uptimeMillis();
        Intent out = wallpaperIntent("art");
        // Only a request. The wallpaper process falls back to the one-frame swap whenever it
        // does not hold both ends of the fade - after its own restart, most of all. Never with
        // the display off: the frames would be composed and uploaded into a screen nobody is
        // looking at, and the clock is not springing either.
        out.putExtra("fade", sFadeWp && screenOn());
        if (!on) {
            out.putExtra("off", true);
            ctx.sendBroadcast(out);
            sTrackKey = "";
            // Nothing behind the clock any more, so nothing to take a colour from. The repaint
            // that hands the OEM's own colours back happens with the rest of cover mode.
            sCoverTint = 0;
            // The shade has nothing to reveal any more. This branch is the single funnel for
            // leaving cover mode - the tap, the dismissed card, the release path and the cover
            // op all end up here - so it is the only place the picture has to be dropped.
            clearShadeArt();
            Xp.log(TAG + "pushart off");
            return;
        }
        if (art == null) { Xp.log(TAG + "pushart: no album art"); return; }
        int w = sScreenW, h = sScreenH;
        // Style 2 has a different composition contract, so it always sends its finished JPEG.
        // Only style 1 may hand the square source to the newer wallpaper process.
        if (!sStyle2Mode && !sVideoWallpaper && sWpComposes) {
            Bitmap src = null;
            String shared = null;
            try {
                src = CoverCompose.prepareSource(art, w);
                shared = writeSharedSource(src, w, h, sBias);
            } catch (Throwable t) {
                Xp.log(TAG + "source hand-over failed, sending the composed JPEG instead: " + t);
            }
            if (shared != null) {
                out.putExtra("src", shared);
                ctx.sendBroadcast(out);
                long sent = android.os.SystemClock.uptimeMillis();
                try {
                    Bitmap full = composeWallpaper(src, w, h, sBias);
                    measureCover(full);
                    if (sCoverMode) recolorClock();
                    Bitmap shade = shadePicture(art, w, h, full);
                    setShadeArt(shade);
                    if (shade != full) full.recycle();
                } catch (Throwable t) {
                    Xp.log(TAG + "composeWallpaper failed after the hand-over: " + t);
                } finally {
                    if (src != null && src != art && !src.isRecycled()) src.recycle();
                }
                Xp.log(TAG + "pushart " + w + "x" + h + " bias=" + sBias
                        + " as source, sent in " + (sent - t0) + "ms, local copy in "
                        + (android.os.SystemClock.uptimeMillis() - sent) + "ms");
                return;
            }
            if (src != null && src != art && !src.isRecycled()) src.recycle();
        }
        Bitmap full;
        try {
            full = (sStyle2Mode ? composeWallpaperStyle2(art, w, h)
                    : composeWallpaper(art, w, h, sBias));
        } catch (Throwable t) {
            // This runs on a HandlerThread, and an uncaught throw on one reaches
            // KillApplicationHandler - so an OutOfMemoryError composing a full-screen cover
            // took SystemUI down with it. The shade's copy is what raises the steady-state
            // heap, which narrows the window rather than creating it; catching is cheap next
            // to a dead process, and the cost of catching is one track without a cover.
            Xp.log(TAG + "composeWallpaper failed, no cover this round: " + t);
            return;
        }
        measureCover(full);
        if (sCoverMode) recolorClock();
        long tc = android.os.SystemClock.uptimeMillis();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int q = 95;
        byte[] jpg;
        do {
            bos.reset();
            full.compress(Bitmap.CompressFormat.JPEG, q, bos);
            jpg = bos.toByteArray();
            q -= 15;
        } while (jpg.length > 700 * 1024 && q > 25);
        // Handed to the shade rather than freed. Everything above has finished with it - the
        // measurement, the clock recolour and the JPEG loop all ran first - and this is the
        // cheapest possible place for the notification shade's cover layer to get its picture:
        // the bitmap already exists, and reusing it means a pull-down reveals exactly what the
        // lock screen is showing, pixel for pixel.
        //
        // NOT a recycle any more, and it must never become one again. The layer draws this on
        // SystemUI's UI thread every frame the shade is open; a bitmap recycled underneath it
        // throws from inside BitmapDrawable.draw() and, unlike most of this module's failures,
        // does not stop - it repeats every frame until the process is killed.
        setShadeArt(shadePicture(art, w, h, full));
        // By path, not by value, and that is not an optimisation.
        //
        // Measured on this phone: the largest cover in the library composes to 612KB, the
        // broadcast carrying it fails on the way out with `Binder transaction failure ...
        // error: -28 (No space left on device - Binder buffer full)`, system_server gives up on
        // the send, and Android kills the wallpaper process for a broadcast that could not be
        // delivered. Everything smaller went through, so size is the whole of the difference.
        // The process comes back, restores the previous track's cover from its own disk and
        // never asks for the right one, because as far as it knows it has art - which is the
        // album cover from the track before, on the lock screen, for the whole track.
        //
        // The byte path stays as the fallback: a build whose SELinux refuses the write still
        // has to work, and that is what it worked with before.
        String shared = writeSharedArt(jpg);
        if (shared != null) out.putExtra("file", shared);
        else out.putExtra("jpg", jpg);
        ctx.sendBroadcast(out);
        Xp.log(TAG + "pushart " + w + "x" + h + " bias=" + sBias
                + " as " + jpg.length + "B jpeg, draw " + (tc - t0) + "ms encode "
                + (android.os.SystemClock.uptimeMillis() - tc) + "ms");
    }

    /**
     * The cover, for a lock screen whose wallpaper is a video (or any other live one).
     *
     * That case looked unreachable - MIUI builds a KeyguardVideoDepthEngineImpl instead of the
     * image engine, so the GL texture this module replaces never exists - but the video does
     * not live in the wallpaper process either. Traced on device: the engine decodes into two
     * SurfaceTextures that SYSTEMUI supplies, drawn by two full-screen TextureViews that
     * com.miui.keyguard.VideoDepthSurfaceHolder puts in keyguard_background_layer (behind the
     * clock) and keyguard_foreground_layer (the cut-out subject, in front of it).
     *
     * So on this path the wallpaper is already inside our own view tree, and the cover is just
     * a view above the background one. The rule that sent this module into the wallpaper
     * process in the first place - that the clock's liquid glass and the card blur sample the
     * wallpaper WINDOW, so an in-SystemUI cover is never picked up by them - does not hold
     * here, because MIUI is not using that window either. Confirmed on device: the glass
     * refracts a video wallpaper normally.
     *
     * Cheaper than the image path, too: no JPEG round trip and no cross-process broadcast, so
     * the composed bitmap goes straight onto the view.
     */
    private static void showVideoCover(Context ctx, boolean on, Bitmap art) {
        if (!on) {
            detachCover();
            setDepthHidden(false);
            Xp.log(TAG + "video cover off");
            return;
        }
        if (art == null) {
            Xp.log(TAG + "video cover: no album art");
            return;
        }
        long t0 = android.os.SystemClock.uptimeMillis();
        // Composed here, on the worker, exactly as for the image path - same mirror-extend,
        // blur and bias, so the two paths produce the same picture.
        final Bitmap full = sStyle2Mode
                ? composeWallpaperStyle2(art, sScreenW, sScreenH)
                : composeWallpaper(art, sScreenW, sScreenH, sBias);
        measureCover(full);
        if (sCoverMode) recolorClock();
        final long draw = android.os.SystemClock.uptimeMillis() - t0;
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup layer = coverLayer();
                    if (layer == null) {
                        Xp.log(TAG + "keyguard_background_layer not found for the video cover");
                        full.recycle();
                        return;
                    }
                    ImageView iv = sCover;
                    if (iv == null || iv.getParent() != layer) {
                        detachCover();
                        iv = new ImageView(ctx);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        // Added last, so it draws over the video's TextureView. The layer is
                        // ordered, and a TextureView draws in the view hierarchy like any other
                        // view - unlike a SurfaceView, which would punch through whatever we
                        // put above it.
                        layer.addView(iv, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        sCover = iv;
                    }
                    iv.setImageBitmap(full);
                    Bitmap old = sCoverBitmap;
                    sCoverBitmap = full;
                    if (old != null && old != full) old.recycle();
                    // The video's own cut-out subject is a second TextureView in the FOREGROUND
                    // layer, i.e. in front of the clock. Left alone it floats over the cover
                    // exactly the way deducted_image_view did on the image path.
                    setDepthHidden(true);
                    guardVideoCover(iv);
                    Xp.log(TAG + "video cover shown " + sScreenW + "x" + sScreenH
                            + " bias=" + sBias + ", draw " + draw + "ms");
                } catch (Throwable t) {
                    Xp.log(TAG + "video cover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Keeps the live-wallpaper cover to the lock screen, and only the lock screen.
     *
     * keyguard_background_layer is not the keyguard's alone: MIUI draws it as the backdrop of
     * the Control Center too, the same way the media card view is shared between the lock
     * screen and the shade. So a full-screen ImageView parked in it turns up behind the
     * Control Center - reported from the device as the cover appearing there while music
     * played, and sharp rather than blurred, which is what says it is a VIEW being drawn and
     * not something sampling the wallpaper.
     *
     * The wallpaper's own TextureViews go the other way: they are hidden because our cover is
     * over them, and that is only true on the lock screen, so off it they are left exactly as
     * MIUI has them - see setVideoSurfacesHidden().
     *
     * A pre-draw listener rather than a one-off, for the reason the depth guard has one: this
     * has to be true on every frame something else draws that layer, and nothing tells us when
     * that is. Every write is conditional and visibility-only, so a frame that needs no change
     * costs a comparison.
     */
    private static void guardVideoCover(final View cover) {
        if (sCoverGuarded == cover && sCoverGuard != null) return;
        releaseCoverGuard();
        sCoverGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean onKeyguard = (sCoverMode || sStyle2Mode) && onKeyguardNow();
                int want = onKeyguard ? View.VISIBLE : View.INVISIBLE;
                if (cover.getVisibility() != want) cover.setVisibility(want);
                // Our own view is the only thing to write off the lock screen. The wallpaper's
                // TextureViews belong to MIUI there, and handing them back inside a layer the
                // shade is drawing is what put a stray frame of the wallpaper into the first
                // pull-down - setVideoSurfacesHidden() has the measurement.
                if (!onKeyguard) return true;
                View bg = sVideoBg, fg = sVideoFg;
                if (bg != null && bg.getVisibility() != View.INVISIBLE) {
                    bg.setVisibility(View.INVISIBLE);
                }
                if (fg != null && fg.getVisibility() != View.INVISIBLE) {
                    fg.setVisibility(View.INVISIBLE);
                }
                return true;
            }
        };
        cover.getViewTreeObserver().addOnPreDrawListener(sCoverGuard);
        sCoverGuarded = cover;
        Xp.log(TAG + "video cover guard installed");
    }

    /**
     * The live wallpaper's own TextureViews: hidden while the cover is over them, given back
     * when it goes.
     *
     * Giving them back only counts while the lock screen is actually in front.
     * keyguard_background_layer is not the keyguard's alone - the shade draws it as its own
     * backdrop over the desktop, which is how the cover could turn up behind the Control
     * Centre - so writing VISIBLE there off the lock screen leaves a surface on screen that
     * nobody is feeding. Measured on the device: exactly one bad frame, because MIUI sets it
     * straight back the moment that layer is drawn, which is why only the FIRST pull-down
     * after unlocking showed a stray wallpaper frame and the second was clean.
     *
     * So a hand-back we cannot make now is remembered instead, and paid the next time the
     * keyguard is in front (screen on, or a keyguard rebuild). That debt is the safety net the
     * old unconditional restore was: what must never happen is the wallpaper hidden with no
     * cover over it, which is a black lock screen.
     */
    private static void setVideoSurfacesHidden(boolean hide, View bg, View fg) {
        if (!hide && !onKeyguardNow()) {
            if (sVideoWallpaper && !sVideoWpOwed) {
                sVideoWpOwed = true;
                Xp.log(TAG + "off the lock screen: leaving the live wallpaper as MIUI left it,"
                        + " hand-back deferred");
            }
            return;
        }
        int vis = hide ? View.INVISIBLE : View.VISIBLE;
        if (bg != null) bg.setVisibility(vis);
        if (fg != null) fg.setVisibility(vis);
        sVideoWpOwed = false;
    }

    /** A hand-back of the live wallpaper's surfaces that is waiting for a lock screen. */
    private static volatile boolean sVideoWpOwed;

    private static void releaseCoverGuard() {
        View c = sCoverGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCoverGuard;
        sCoverGuarded = null;
        sCoverGuard = null;
        if (c == null || g == null) return;
        try {
            c.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    private static View sCoverGuarded;
    private static ViewTreeObserver.OnPreDrawListener sCoverGuard;

    /** keyguard_background_layer, which sits behind the whole clock stack. */
    private static ViewGroup coverLayer() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(
                "keyguard_background_layer", "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        return layer instanceof ViewGroup ? (ViewGroup) layer : null;
    }

    /**
     * The TextureView a live wallpaper is drawn into, in one of the keyguard's layers. It
     * carries no id - it is added in code by VideoDepthSurfaceHolder - so it is found by type,
     * and there is only ever one per layer.
     */
    private static View videoSurfaceView(String layerId) {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getResources().getIdentifier(layerId, "id", "com.android.systemui");
        View layer = id == 0 ? null : v.getRootView().findViewById(id);
        if (!(layer instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) layer;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof android.view.TextureView) return g.getChildAt(i);
        }
        return null;
    }

    /**
     * FLAG_RECEIVER_FOREGROUND is the whole reason a track change feels immediate: without it
     * this broadcast sits in the background queue and took a measured ~500ms to reach the
     * wallpaper process, which is more than composing and uploading the picture put together.
     */
    /** One switch to the wallpaper process for extracted feature classes. */
    static void sendToWallpaper(String op, boolean on) {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        Intent out = wallpaperIntent(op);
        out.putExtra("on", on);
        ctx.sendBroadcast(out);
    }

    private static Intent wallpaperIntent(String op) {
        Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.WPROBE");
        out.setPackage("com.miui.miwallpaper");
        out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        out.putExtra("op", op);
        out.putExtra("reload", true);
        // Which kind of lock wallpaper this push is for. The wallpaper process cannot work it
        // out for itself - it only knows which engines it has BUILT, and those are built once
        // per process - and this side re-reads it before every push anyway. See videoPath().
        out.putExtra("video", sVideoWallpaper);
        return out;
    }

    /**
     * Hands the wallpaper process the crossfade that belongs to the current response.
     *
     * It has to be told. Its own copy is a static that starts at 370 and is never persisted,
     * so a wallpaper process that restarts would fade on the number that goes with 0.38 while
     * the clock, the card and everything else moved on the slider's - which is the desync the
     * two of them were moved together to remove, arriving silently instead.
     *
     * Called when the setting changes, and again on every cover entry so a restart is caught
     * before it can be seen.
     */
    private static void pushFadeMs(Context ctx) {
        if (ctx == null) return;
        ctx.sendBroadcast(wallpaperIntent("fadems")
                .putExtra("v", (int) fadeMsFor(sClockResponse)));
        Xp.log(TAG + "wallpaper fade = " + fadeMsFor(sClockResponse) + "ms for response "
                + sClockResponse);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * The right bias can only be found by looking at the phone - the sharp band has to clear the
     * media card without stranding the artwork under the clock - so it is a live knob, and a
     * change re-composes what is already on screen instead of waiting for the next track.
     */
    /**
     * What the notification shade's cover layer should show.
     *
     * The default is the lock screen's own picture, SHARED - one bitmap on the heap, and a
     * pull-down reveals exactly what the lock screen is showing. A non-zero delta composes a
     * second one at its own framing instead, which is a full-screen allocation and a second
     * compose per push; that is the user's choice to make, and it is why the knob is a delta
     * that starts at zero rather than a bias with a value.
     *
     * Called from the composing worker, so the throw is caught here rather than reaching
     * KillApplicationHandler - the same reason pushArtToWallpaper catches its own compose.
     */
    private static Bitmap shadePicture(Bitmap art, int w, int h, Bitmap shared) {
        final int d = sShadeBiasDelta;
        if (d == 0) return shared;
        try {
            final float b = clamp01(sBias + d / 1000f);
            // Said out loud because this is the ONE thing the setting costs, and the only way to
            // tell "the second compose ran" from "the delta was ignored" without watching the
            // heap: the shade's framing is baked in at compose time and nothing downstream can
            // report it.
            Xp.log(TAG + "shade art: own framing at bias " + b + " (lock screen " + sBias
                    + ", delta " + d + ")");
            return composeWallpaper(art, w, h, b);
        } catch (Throwable t) {
            // One track framed like the lock screen beats no cover at all, and the knob is still
            // on screen to be moved back.
            Xp.log(TAG + "the shade's own framing could not be composed, sharing the lock "
                    + "screen's instead: " + t);
            return shared;
        }
    }

    /**
     * The notification centre's framing, off the lock screen's by `v` thousandths.
     *
     * Re-composes what is already on screen rather than waiting for the next track, the way
     * setBias() does - a framing knob is only worth having if you can watch it move.
     */
    static void setShadeBias(int v) {
        if (v < -1000) v = -1000;
        if (v > 1000) v = 1000;
        sShadeBiasDelta = v;
        saveState();
        Xp.log(TAG + "shade cover bias delta = " + v);
        if (sCoverMode) pushArtAsync(true, false);
    }

    static int shadeBiasDelta() {
        return sShadeBiasDelta;
    }

    private static void setBias(float v) {
        sBias = clamp01(v);
        saveState();
        Xp.log(TAG + "cover bias = " + sBias);
        if (sCoverMode) pushArtAsync(true, false);
    }

    /** Asks the wallpaper process to re-upload the art it already has on disk. */
    private static void requestWallpaperReload(Context ctx) {
        ctx.sendBroadcast(wallpaperIntent("reload"));
        Xp.log(TAG + "wallpaper reload requested");
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
    private static synchronized Handler worker() {
        if (sWork == null) {
            // Default priority put the composing on a little core and made it swing between
            // 48ms and 196ms for the same picture. This work is on the critical path of a track
            // change, so ask for display priority rather than background.
            HandlerThread t = new HandlerThread("mc-art", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    static Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    /**
     * Composes and pushes off the main thread.
     *
     * Getting the RIGHT art is the fiddly part of a track change, not getting art at all.
     * Players fill the session bitmap in asynchronously, so at the instant the metadata arrives
     * there is often nothing there - and the media card thumbnail we fall back to is still the
     * PREVIOUS track (verified on device: the wallpaper came up as the last album while the card
     * already read the new one). So attempts are spaced out, the session is preferred over the
     * card until the last one, and artwork identical to what is already on the wallpaper is read
     * as "not updated yet" rather than accepted.
     *
     * That budget only means anything for a player that HAS a bitmap to fill in. One that
     * publishes only an artwork URI never will, and for it the session is not a source at all -
     * Bilibili hands over an i1.hdslb.com URL and no bitmap, on 2309 of the 2690 session reads
     * in a day's log - so every try spent on the session is spent knowing it comes back empty.
     * Measured before this was understood: 143 video switches waited an average of 1.6s, and 128
     * of them burned all fourteen tries, with the wallpaper sitting on the previous video the
     * whole time, before the card thumbnail was ever consulted once.
     */
    private static final int ART_TRIES = 14;
    /**
     * Checking costs a metadata read, so poll rather than wait in lumps: the player usually
     * fills the session bitmap in within a few hundred ms, and 700ms steps meant a track change
     * that missed by 50ms still cost the full 700. Fourteen tries covers the same ~1.6s window.
     */
    private static final long ART_RETRY_MS = 120L;
    /** What the wallpaper currently shows, coarsely, so a stale source can be recognised. */
    private static volatile int sArtPrint;
    /**
     * Which push is the current one. The retries span a couple of seconds, so the card can be
     * dismissed - or the track changed again - while they are still running; without this, a
     * retry that finally found artwork would put the cover back after cover mode had ended.
     */
    private static volatile int sPushGen;

    /**
     * Where a composed cover is handed to the wallpaper process. See writeSharedArt().
     *
     * MIUI's own wallpaper tree: mode 0777 and owned by the wallpaper app, and measured from
     * inside both processes - SystemUI writes and the wallpaper process reads, under Enforcing
     * SELinux and with the file made world-readable.
     */
    static final String SHARE_DIR = "/data/system/theme_magic/users/0/wallpaper";
    static final String SHARE_FILE = "mc_art_shared.jpg";
    private static volatile boolean sWpComposes;
    static final String SHARE_SOURCE = "mc_src.raw";

    private static String writeSharedSource(Bitmap src, int w, int h, float bias)
            throws java.io.IOException {
        java.io.File f = new java.io.File(SHARE_DIR, SHARE_SOURCE);
        CoverCompose.writeSource(f, src, w, h, bias);
        return f.getAbsolutePath();
    }

    /**
     * Puts a composed cover where the wallpaper process can read it, and answers the path - or
     * null when this process cannot write there, which sends the caller back to the broadcast.
     *
     * Readable by everyone on purpose: the two processes do not share a uid.
     */
    private static String writeSharedArt(byte[] jpg) {
        try {
            java.io.File f = new java.io.File(SHARE_DIR, SHARE_FILE);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(jpg);
            out.close();
            f.setReadable(true, false);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Xp.log(TAG + "shared art write failed, carrying it in the broadcast: " + t);
            return null;
        }
    }

    private static void pushArtAsync(final boolean on, final boolean fresh) {
        final Context ctx = sAppCtx;
        if (ctx == null) return;
        final int gen = ++sPushGen;
        if (!on) {
            sArtPrint = 0;
            worker().post(new Runnable() {
                @Override
                public void run() {
                    if (gen == sPushGen) pushArtToWallpaper(ctx, false, null);
                }
            });
            return;
        }
        // Checked before every push rather than once per process. The user can send the
        // wallpaper back to "follow home" at any moment - from Settings, a theme, the wallpaper
        // carousel - and that silently removes the keyguard renderer this whole thing hangs off.
        // A cached "already fine" would mean the module never noticed and the user had to go
        // press a repair button, which is exactly what should not be necessary.
        worker().post(new Runnable() {
            @Override
            public void run() { ensureLockWallpaper(ctx); }
        });
        // Not a track change (a bias tweak, a manual pushart): take whatever is there now.
        tryPushArt(ctx, fresh ? 0 : ART_TRIES - 1, fresh, gen);
    }

    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen) {
        tryPushArt(ctx, attempt, fresh, gen, false);
    }

    /**
     * allowCard travels with the attempt because the first one is where the session gets judged.
     * When it answers with no bitmap there is nothing left to wait for on that side, and the
     * card thumbnail - which until then was read on the very last try only - can answer from the
     * next one instead. The try budget is untouched, so the worst case is exactly what it was;
     * what changes is that the common case stops spending it. The session is still asked first
     * on every attempt, so a player that does fill its bitmap in late is still picked up.
     */
    private static void tryPushArt(final Context ctx, final int attempt, final boolean fresh,
                                   final int gen, final boolean allowCard) {
        worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != sPushGen) {
                    Xp.log(TAG + "art push superseded, dropping it");
                    return;
                }
                boolean last = attempt >= ART_TRIES - 1;
                int[] sessionBits = new int[1];
                Bitmap art = albumArt(ctx, last || allowCard, sessionBits);
                int print = art == null ? 0 : artPrint(art);
                boolean stale = fresh && art != null && sArtPrint != 0 && print == sArtPrint;
                if ((art == null || stale) && !last) {
                    // 0 is "a session was there and carried no bitmap", which more tries will not
                    // change. -1 is "there was nothing to ask", which more tries might. Once the
                    // card is in it stays in, so this is worth looking at on any attempt - the
                    // session can turn up late - and declaring it once keeps the line off the log.
                    boolean bare = sessionBits[0] == 0 && !allowCard;
                    if (bare) {
                        Xp.log(TAG + "session carries no bitmap at all, reading the card from here");
                    }
                    Xp.log(TAG + "art " + (art == null ? "not ready" : "still the old one")
                            + ", retrying (" + (attempt + 2) + "/" + ART_TRIES + ")");
                    tryPushArt(ctx, attempt + 1, fresh, gen, allowCard || bare);
                    return;
                }
                if (stale) {
                    // The next track off the same album really does have the same cover.
                    Xp.log(TAG + "same artwork as the last track, wallpaper left alone");
                    return;
                }
                // Only when something is really going out. A push with no art leaves the
                // wallpaper showing what it already showed, and recording 0 here would claim it
                // was empty and disarm the stale-art check on the next track change.
                if (art != null) sArtPrint = print;
                pushArtToWallpaper(ctx, true, art);
            }
        }, attempt == 0 ? 0L : ART_RETRY_MS);
    }

    /**
     * The whole cover mechanism rests on the lock screen having a wallpaper of its OWN. MIUI
     * only builds a KeyguardImageEngineImpl - and with it the keyguard renderer whose texture we
     * replace - when the lock screen wallpaper is separate from the home one. Share one between
     * them and the wallpaper process creates a single engine with isLockScreen=false, so there
     * is nothing to hook and the cover silently does nothing at all.
     *
     * This is not hypothetical: the lock wallpaper got reset to "same as home" (dumpsys wallpaper
     * showed an empty "Lock wallpaper state" and mWhich=3), and the module went on reporting
     * cover mode on, art pushed, reload requested - with nowhere to draw it.
     *
     * Copying the current home wallpaper into the lock slot satisfies the requirement without
     * changing what the user sees: the lock screen keeps the same picture, it just owns a copy.
     */
    private static boolean ensureLockWallpaper(Context ctx) {
        return ensureLockWallpaper(ctx, false);
    }

    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean ensureLockWallpaper(Context ctx, boolean force) {
        android.app.WallpaperManager wm = (android.app.WallpaperManager)
                ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) return false;
        // Before anything else, and before `force` too - forcing is the same setBitmap and
        // does the same damage. See wallpaperKind(): a live wallpaper on either slot cannot
        // survive being replaced with a still, and this is the only place that could do it.
        String lockKind = wallpaperKind(ctx, "lock");
        boolean live = lockKind != null && !KIND_IMAGE.equals(lockKind);
        sVideoWallpaper = live;
        if (live) {
            // Nothing to repair, and nothing that may be written: setBitmap(FLAG_LOCK) would
            // unbind the live wallpaper for good. The cover still works - it just goes into
            // our own keyguard layer instead of the wallpaper process. See showVideoCover().
            Xp.log(TAG + "the lock screen has a " + lockKind + " wallpaper of its own; leaving "
                    + "the wallpaper alone and drawing the cover in the keyguard layer");
            return false;
        }
        if (lockKind == null) {
            // Nothing of its own on the lock slot, so it is showing the home wallpaper - and
            // if that one is live, the lock screen IS that live wallpaper. Copying "the home
            // wallpaper" would get a still frame of it at best and the stock picture at worst,
            // and either way the animation is gone.
            String homeKind = wallpaperKind(ctx, "home");
            if (homeKind != null && !KIND_IMAGE.equals(homeKind)) {
                Xp.log(TAG + "the lock screen is following a " + homeKind + " home wallpaper. "
                        + "Leaving it alone rather than replacing it with a still of itself.");
                return false;
            }
        }
        if (!force) {
            try {
                android.os.ParcelFileDescriptor lock =
                        wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
                if (lock != null) {
                    try {
                        lock.close();
                    } catch (Throwable ignored) {
                    }
                    // The slot being populated is all the COVER needs. What the transition out
                    // of it needs as well is for the picture in there to be the size of the
                    // screen - see fitLockWallpaperToScreen().
                    fitLockWallpaperToScreen(wm);
                    return true;
                }
            } catch (Throwable t) {
                Xp.log(TAG + "cannot read the lock wallpaper slot: " + t);
                return false;
            }
            Xp.log(TAG + "lock screen has no wallpaper of its own - the keyguard "
                    + "wallpaper engine cannot exist, so the cover has nowhere to go. Giving it a "
                    + "copy of the home wallpaper.");
        }
        try {
            Bitmap home = homeWallpaper(wm);
            if (home == null) {
                Xp.log(TAG + "no home wallpaper bitmap to copy");
                return false;
            }
            // At the screen's own size, deliberately. Whatever goes in this slot becomes the
            // texture the keyguard uploads, and every track change then has to rescale the
            // composed cover to it - on both sides. The home wallpaper here is 1579x3432, which
            // cost ~100ms a track for nothing: the picture is only ever shown on this screen.
            Bitmap fitted = home.getWidth() == sScreenW && home.getHeight() == sScreenH
                    ? home : centerCrop(home, sScreenW, sScreenH);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            if (fitted != home) fitted.recycle();
            home.recycle();
            Xp.log(TAG + "lock wallpaper set at " + sScreenW + "x" + sScreenH
                    + "; the keyguard engine will be rebuilt");
            return true;
        } catch (Throwable t) {
            Xp.log(TAG + "could not set a lock wallpaper: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * The size of the last lock wallpaper this tried to re-fit, packed w<<32|h.
     *
     * Not a plain "done" flag: the user can change the wallpaper at any point and the next one
     * deserves its own attempt. What this stops is fighting the same picture over and over -
     * ensureLockWallpaper() runs before every art push, so a slot that cannot be rewritten would
     * otherwise be rewritten once per track change, forever.
     */
    private static volatile long sLockWpTried;

    /**
     * Puts an over-sized lock wallpaper back to the size of the screen, so leaving cover mode
     * can crossfade instead of cutting.
     *
     * Every frame of that crossfade re-uploads the WHOLE keyguard texture, and the texture is
     * the lock wallpaper at ITS OWN pixel size, not the screen's. Measured in the wallpaper
     * process, same phone, same 240ms fade, the only difference being the wallpaper's own
     * dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  fine
     *   1579x3432 (1.7x)          21MB/frame    the GL thread falls behind far enough that the
     *                                           compositor picks up a half-built frame - the
     *                                           cover drawn small on black, in a corner
     *
     * So WallpaperProbe.fadeTooExpensive() refuses to fade above 1.5x the screen and swaps in
     * one frame instead. That is the right call at that point, but from the outside it reads as
     * "the transition only works if your wallpaper happens to be the right resolution", which is
     * not something anyone should have to know. The extra pixels buy nothing either way: the
     * picture is only ever drawn on this screen, and every draw throws them away. Re-fitting is
     * the same centre-crop ensureLockWallpaper() already applies to the copy it writes itself,
     * so both paths leave the slot in the same shape.
     *
     * Two things it will not do:
     *
     * - Touch a wallpaper whose aspect ratio is not the screen's. A picture that much wider is
     *   being used for something - the parallax a scrolling wallpaper pans through - and
     *   cropping it would change what the user SEES rather than only its resolution. A hard cut
     *   is the smaller harm.
     * - Take a second run at the same picture. See sLockWpTried.
     *
     * Runs on the art worker, off the main thread, and only from the branch where the lock slot
     * already holds a still of its own - live wallpapers returned above, long before this.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them.
    @SuppressLint("MissingPermission")
    private static void fitLockWallpaperToScreen(android.app.WallpaperManager wm) {
        // Not while the wallpaper process sizes its texture to the screen (sTexFit, on by
        // default): that solves the same problem - a 2121x4712 wallpaper making every swap upload
        // 38MB and putting the crossfade over its own threshold - without writing anything. The
        // two must not both run, and if this one does, the user's depth cut-out goes with it:
        // MIUI segments the wallpaper the picker set, and nothing re-analyses a file we wrote.
        if (sTexFit) return;
        int sw = sScreenW, sh = sScreenH;
        if (sw <= 0 || sh <= 0) return;
        int w = 0, h = 0;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options b =
                        new android.graphics.BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(in, null, b);
                w = b.outWidth;
                h = b.outHeight;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "cannot measure the lock wallpaper: " + t);
            return;
        }
        if (w <= 0 || h <= 0) return;
        // The same gate the fade uses, and deliberately the same number: anything this leaves
        // alone is something WallpaperProbe will still agree to fade.
        if ((long) w * h * 2 <= (long) sw * sh * 3) return;
        float times = (float) (w * (double) h / (sw * (double) sh));
        // Aspect, to 1%. 1579x3432 and 1200x2608 are one picture at two resolutions; a panorama
        // is not, and centre-cropping one is a change nobody asked for.
        if (Math.abs((long) w * sh - (long) h * sw) * 100L > (long) h * sw) {
            Xp.log(TAG + "lock wallpaper is " + w + "x" + h + ", " + r2(times)
                    + "x the screen but not its shape - leaving it alone;"
                    + " the cover will swap in one frame rather than fade");
            return;
        }
        long size = ((long) w << 32) | (h & 0xffffffffL);
        if (sLockWpTried == size) return;
        sLockWpTried = size;
        Bitmap src = null, fitted = null;
        try {
            android.os.ParcelFileDescriptor fd =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (fd == null) return;
            java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                // Decoded straight down where a power of two allows it: the full 21MB only ever
                // existed to be thrown away, and this is a phone.
                o.inSampleSize = sampleSizeFor(w, h, sw, sh);
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                src = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (src == null) {
                Xp.log(TAG + "lock wallpaper could not be decoded for re-fitting");
                return;
            }
            fitted = src.getWidth() == sw && src.getHeight() == sh ? src : fitKeepingColour(src, sw, sh);
            wm.setBitmap(fitted, null, true, android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(TAG + "lock wallpaper was " + w + "x" + h + " (" + r2(times)
                    + "x the screen) - re-fitted to " + sw + "x" + sh
                    + " so leaving cover mode can crossfade instead of cutting");
        } catch (Throwable t) {
            Xp.log(TAG + "could not re-fit the lock wallpaper: " + Log.getStackTraceString(t));
        } finally {
            if (fitted != null && fitted != src) fitted.recycle();
            if (src != null) src.recycle();
        }
    }

    /**
     * centerCrop() with the source's colour space carried through.
     *
     * The shared one draws into `Bitmap.createBitmap(w, h, ARGB_8888)`, and that bitmap is
     * sRGB. Everywhere else in this module that is exactly right - what goes in is album art
     * that was composed here. Here it is the user's own wallpaper, which on this phone can be
     * a Display P3 photo, and drawing P3 into an sRGB bitmap gamut-clips it: saturated reds and
     * greens come back duller, permanently, in a file the user did not ask to have rewritten.
     * The OEM cares too - its upload path calls BitmapUtils.checkColorSpace() on every bitmap.
     *
     * Everything else about the crop is deliberately identical to centerCrop(), because
     * identical is the point: read off the dex, the OEM maps this texture to the screen with
     * AnimImageGLProgram.changeMvpMatrixCrop(), which is setIdentityM() plus a single scale on
     * whichever axis overflows - a centred scale-to-cover with no translation and no crop hint.
     * So the pixels this bakes into the file are the pixels that were already on screen.
     */
    private static Bitmap fitKeepingColour(Bitmap src, int w, int h) {
        Bitmap out = null;
        try {
            android.graphics.ColorSpace cs = src.getColorSpace();
            if (cs != null && !cs.equals(android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.SRGB))) {
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, src.hasAlpha(), cs);
                Xp.log(TAG + "keeping the wallpaper's colour space: " + cs.getName());
            }
        } catch (Throwable t) {
            // A colour space the framework will not hand back to createBitmap. sRGB it is -
            // the same thing that happened before this method existed.
            Xp.log(TAG + "cannot carry the wallpaper's colour space over: " + t);
        }
        if (out == null) return centerCrop(src, w, h);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new android.graphics.RectF(
                        (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** The largest power of two that still leaves the picture covering the screen. */
    private static int sampleSizeFor(int w, int h, int sw, int sh) {
        int s = 1;
        while (w / (s * 2) >= sw && h / (s * 2) >= sh) s *= 2;
        return s;
    }

    /** The one kind of wallpaper this module can work with: a still picture. */
    private static final String KIND_IMAGE = "image";

    /**
     * What kind of wallpaper is on a slot - "image", "video", "sensor", "super_wallpaper", or
     * null when nothing here can say.
     *
     * This exists because getWallpaperFile(FLAG_LOCK) cannot tell two very different states
     * apart. It returns null when the lock screen follows the home wallpaper, and it returns
     * null when the lock screen has a LIVE wallpaper of its own, because a live wallpaper has
     * no bitmap file anywhere to hand back. ensureLockWallpaper read that null as the first
     * case and wrote a still copy of the home wallpaper into the lock slot - which is a
     * setBitmap(..., FLAG_LOCK), which unbinds the live wallpaper permanently. A user reported
     * exactly that: set a video lock wallpaper, play one track, and from then on the lock
     * screen is a still of the home wallpaper. Nothing here recorded what it replaced, so
     * leaving cover mode could not put it back either.
     *
     * Three sources, most authoritative first, all three read off the device:
     *
     * 1. MIUI's own record at /data/system/theme_magic/users/<u>/wallpaper/data/{lock,home}.xml.
     *    The root element is the answer - `wallpaper_data which="2" type="image"` - and the
     *    rest of the (38KB) tag is colour palettes. The files are 0777, so this needs no root,
     *    let alone the system uid SystemUI happens to have. Seen carrying last_type="video" on
     *    the home slot here, which is where the vocabulary is confirmed from.
     * 2. Settings.Secure `flag_lock_wallpaper_type` - lock only, and there is no home
     *    equivalent: `desktop_wallpaper_type` is unset in all three namespaces.
     * 3. Settings.Secure `constant_template_editor_info`, the lock screen editor's JSON, whose
     *    homeInfo/lockscreenInfo both carry a `resourceType`. The only source that covers home.
     *
     * Deliberately NOT WallpaperManager.getWallpaperInfo(FLAG_LOCK): on MIUI every wallpaper,
     * still or video, is drawn by the same com.miui.miwallpaper ImageWallpaper component, so
     * it answers "live" for all of them and distinguishes nothing.
     *
     * Unknown reads back as null, and null keeps the old behaviour. Being wrong in the shy
     * direction costs the cover on one phone; being wrong the other way costs someone their
     * wallpaper.
     */
    private static String wallpaperKind(Context ctx, String slot) {
        boolean lock = "lock".equals(slot);
        String kind = kindFromMiui(lock);
        String from = "MiuiWallpaperManager";
        if (kind == null) {
            kind = kindFromThemeMagic(slot);
            from = "MIUI's " + slot + ".xml";
        }
        if (kind == null && lock) {
            kind = kindFromSettings(ctx, "flag_lock_wallpaper_type");
            from = "flag_lock_wallpaper_type";
        }
        if (kind == null) {
            kind = kindFromEditorInfo(ctx, lock ? "lockscreenInfo" : "homeInfo");
            from = "constant_template_editor_info";
        }
        // On every track change, so only when the answer moves. Which it does: this is
        // checked before each push precisely because the user can change the wallpaper at
        // any moment, and the interesting moment is the one where it just became a video.
        String last = "lock".equals(slot) ? sLockKindLogged : sHomeKindLogged;
        if (!java.util.Objects.equals(last, kind)) {
            Xp.log(TAG + slot + " wallpaper is " + (kind == null ? "unknown" : kind)
                    + (kind == null ? " (neither MIUI's record nor Settings said)"
                                    : " per " + from));
            if ("lock".equals(slot)) sLockKindLogged = kind;
            else sHomeKindLogged = kind;
        }
        return kind;
    }

    private static String sLockKindLogged = "";
    private static String sHomeKindLogged = "";

    /**
     * MIUI's own answer, and the one to prefer: `MiuiWallpaperManager.getMiuiWallpaperType(which)`
     * is what MIUI asks itself, it returns the same vocabulary the files use ("image", "video",
     * "sensor", "super_wallpaper"), and both it and the SystemUI class holding the instance keep
     * their real names through R8 - so unlike everything below it, this should not need a new
     * heuristic per HyperOS version.
     *
     * `which` is 1 for home and 2 for lock, read off MiuiWallpaperManager's own constants rather
     * than assumed - though they do match WallpaperManager's FLAG_SYSTEM/FLAG_LOCK, and the
     * `which=` attribute in MIUI's XML agrees.
     */
    private static String kindFromMiui(boolean lock) {
        Object mgr = sKgWallpaperMgr;
        if (mgr == null) return null;
        try {
            Object wm = Xp.getObjectField(mgr, "mMiuiWallpaperManager");
            if (wm == null) return null;
            int which = miuiWhich(wm.getClass(), lock);
            Object type = Xp.callMethod(wm, "getMiuiWallpaperType", which);
            String s = type == null ? null : type.toString();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Xp.log(TAG + "MiuiWallpaperManager could not say what the "
                    + (lock ? "lock" : "home") + " wallpaper is: " + t);
            return null;
        }
    }

    private static int miuiWhich(Class<?> wmCls, boolean lock) {
        String name = lock ? "MI_WALLPAPER_WHICH_LOCK" : "MI_WALLPAPER_WHICH_HOME";
        try {
            java.lang.reflect.Field f = wmCls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return lock ? 2 : 1;
        }
    }

    private static String kindFromThemeMagic(String slot) {
        java.io.InputStream in = null;
        try {
            // uid / 100000 is the user id - UserHandle.PER_USER_RANGE, and UserHandle's own
            // accessors for it are all @hide.
            java.io.File f = new java.io.File("/data/system/theme_magic/users/"
                    + (android.os.Process.myUid() / 100000)
                    + "/wallpaper/data/" + slot + ".xml");
            if (!f.canRead()) return null;
            in = new java.io.FileInputStream(f);
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(in, null);
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String type = p.getAttributeValue(null, "type");
                if (type != null && !type.isEmpty()) return type;
            }
        } catch (Throwable t) {
            Xp.log(TAG + "cannot read MIUI's " + slot + " wallpaper record: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String kindFromSettings(Context ctx, String key) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        String[] got = new String[3];
        try {
            got[0] = android.provider.Settings.Secure.getString(cr, key);
            got[1] = android.provider.Settings.System.getString(cr, key);
            got[2] = android.provider.Settings.Global.getString(cr, key);
        } catch (Throwable ignored) {
        }
        for (String v : got) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * The lock screen editor's own record, which is the only one of these that covers the home
     * slot: there is no `desktop_wallpaper_type` key - checked on device, all three namespaces.
     *
     * `constant_template_editor_info` is a single Settings.Secure JSON blob written by
     * com.miui.aod, with the same shape under `homeInfo` and `lockscreenInfo`. Its
     * `resourceType` uses the same vocabulary as the XML's `type` - "image", "video".
     */
    private static String kindFromEditorInfo(Context ctx, String section) {
        try {
            String raw = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "constant_template_editor_info");
            if (raw == null || raw.isEmpty()) return null;
            org.json.JSONObject info = new org.json.JSONObject(raw)
                    .optJSONObject(section);
            if (info == null) return null;
            org.json.JSONObject wp = info.optJSONObject("wallpaperInfo");
            if (wp == null) return null;
            String type = wp.optString("resourceType", "");
            return type.isEmpty() ? null : type;
        } catch (Throwable t) {
            Xp.log(TAG + "cannot read " + section + " from the editor info: " + t);
            return null;
        }
    }

    /** The home wallpaper, decoded no larger than it needs to be for this screen. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static Bitmap homeWallpaper(android.app.WallpaperManager wm) {
        try {
            android.os.ParcelFileDescriptor sys =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if (sys != null) {
                java.io.InputStream in =
                        new android.os.ParcelFileDescriptor.AutoCloseInputStream(sys);
                try {
                    android.graphics.BitmapFactory.Options o =
                            new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "reading the home wallpaper file failed: " + t);
        }
        // A live or default home wallpaper has no file; render whatever is showing instead.
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Whether the cover has somewhere to go.
     *
     * Two conditions, and they are not the same one. The lock screen needs a wallpaper entry
     * of its own - about the ENTRY, not the picture: a copy of the home wallpaper counts, and
     * is exactly what ensureLockWallpaper() installs. And that wallpaper has to be a still
     * image, because a video or sensor one is drawn by an engine this module has nothing
     * hooked in, and ensureLockWallpaper will not (must not) convert it to a still.
     */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static boolean hasLockWallpaper(Context ctx) {
        try {
            String kind = wallpaperKind(ctx, "lock");
            if (kind != null && !KIND_IMAGE.equals(kind)) return false;
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            android.os.ParcelFileDescriptor f =
                    wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK);
            if (f == null) return false;
            try {
                f.close();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts the lock screen back to following the home wallpaper. Undoes ensureLockWallpaper. */
    // Runs inside com.android.systemui, which holds SET_WALLPAPER and
    // READ_WALLPAPER_INTERNAL. This APK neither has nor needs them - it is a library
    // for someone else's process, and lint has no way to know that.
    @SuppressLint("MissingPermission")
    private static void clearLockWallpaper(Context ctx) {
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            wm.clear(android.app.WallpaperManager.FLAG_LOCK);
            Xp.log(TAG + "lock wallpaper cleared, back to following the home one");
        } catch (Throwable t) {
            Xp.log(TAG + "clearLockWallpaper failed: " + t);
        }
    }

    /** Coarse identity of an artwork: enough to tell one cover from another, cheap to take. */
    private static int artPrint(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            int[] px = new int[64];
            s.getPixels(px, 0, 8, 0, 0, 8, 8);
            s.recycle();
            return java.util.Arrays.hashCode(px);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * One switch for the whole look. The clock collapse is not a separate thing the user turns
     * on - if the album cover is the wallpaper, the clock is small.
     */
    private static void enterCoverMode(boolean animate) {
        sCoverMode = true;
        sReleasing = false;
        armTransitionTrace("entering cover mode");
        sTapSuppressed = false;
        setDepthHidden(true);
        sCardP = animate ? 0f : 1f;
        applyMediaCard();
        releaseClockGuard();
        stopCardAnim();
        ClockCollapse.enter(animate, false);
        recolorClock();
        pushFadeMs(sAppCtx);
        rebindSession();
        LockLyrics.attach();
        saveState();
    }

    /** Enters style 2 without changing the native clock size or position. */
    private static void enterStyle2Mode() {
        // Style 2 never owns the clock. This is deliberately unconditional: sCoverMode becomes
        // false at the start of a style-1 exit, while its spring/scale remains alive until the
        // last frame, so checking only sCoverMode misses exactly the interrupted-exit case.
        sCoverMode = false;
        stopCardAnim();
        releaseClockGuard();
        ClockCollapse.release("style 2");
        LockLyrics.refresh();
        if (sStyle2Mode) {
            rebindSession();
            pushArtAsync(true, false);
            setDepthHidden(true);
            applyMediaCard();
            return;
        }
        sStyle2Mode = true;
        sReleasing = false;
        sTapSuppressed = false;
        setDepthHidden(true);
        sCardP = 1f;
        applyMediaCard();
        rebindSession();
        pushArtAsync(true, false);
        saveState();
    }

    /** Restores the only valid baseline outside an explicitly selected music presentation. */
    private static void resetMusicPresentation(String why, boolean updateBridge) {
        sAuto = false;
        main().removeCallbacks(sCardGone);
        sCoverMode = false;
        sStyle2Mode = false;
        sCoverWanted = false;
        sTapSuppressed = false;
        sTrackKey = "";
        stopCardAnim();
        releaseClockGuard();
        ClockCollapse.release(why);
        LockLyrics.refresh();
        setDepthHidden(false);
        sMcHideArt = false;
        sMcCenterText = false;
        sCardP = 0f;
        applyMediaCard();
        pushArtAsync(false, false);
        if (updateBridge) notifyPresentation("SYSTEM_MEDIA");
        saveState();
        Xp.log(TAG + "music presentation reset: " + why);
    }

    /** Leaves style 2 and restores the original wallpaper, depth subject and media card. */
    private static void exitStyle2Mode() {
        if (!sStyle2Mode) return;
        sStyle2Mode = false;
        sReleasing = false;
        sTapSuppressed = false;
        setDepthHidden(false);
        sCardP = 0f;
        applyMediaCard();
        pushArtAsync(false, false);
        saveState();
    }

    /**
     * Leaves cover mode, optionally on the way out rather than all at once.
     *
     * Animated, this is one spring and nothing else: it drives notifY back to where the system
     * last asked for it, and the collapse scale, the glass morph and the card's thumbnail are
     * all derived from that same y in applyCollapse(), so they move together for free. That is
     * what Apple's is - everything at once, over about 300ms - and it is also why there is no
     * duration constant here: the OEM's own STATE_CHANGED curve settles in about 445ms.
     *
     * The unanimated path is the old behaviour and still the right one when the display is off
     * (Choreographer frames stop, so a spring started there never settles) or when the clock was
     * never taken over in the first place.
     */
    private static void exitCoverMode(boolean animate) {
        sCoverMode = false;
        sReleasing = true;
        if (sCoverTint != 0) {
            sCoverTint = 0;
            recolorClock();
        }
        armTransitionTrace("leaving cover mode");
        setDepthHidden(false);
        releaseClockGuard();
        stopCardAnim();
        ClockCollapse.exit(animate);
        if (!ClockCollapse.active()) onClockReleased();
        LockLyrics.refresh();
        saveState();
    }

    /** ClockCollapse released ownership; outside style 1 the media card returns to stock. */
    static void onClockReleased() {
        sReleasing = false;
        if (sCoverMode) return;
        sCardP = 0f;
        applyMediaCard();
    }

    static void noteAwake() {
        if (sScreenOn) return;
        sScreenOn = true;
        forgetSysReads();
        recolorClock();
    }

    static float cardProgress() {
        return sCardP;
    }

    static void setCardProgressFrom(float p) {
        setCardProgress(p);
    }

    static void armGlassMorph() {
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        sAppliedGlassV = Float.NaN;
    }

    static void restoreGlass() {
        if (Float.isNaN(sGlassV0)) return;
        float back = sGlassV0;
        sGlassV0 = sGlassV1 = Float.NaN;
        sAppliedGlassV = Float.NaN;
        callOnClockViews("updateGlassValue", "f", back, 0, false);
    }

    static void noteDateView(View date) {
        if (date == sDateView) return;
        sDateView = date;
        sClockTargets.clear();
        forgetClockRoots();
        forgetGlyphBox();
    }

    static void convertLegacyHeight(RectF box) {
        if (!Float.isNaN(sClockLegacyK)) heightDpFor(box);
    }

    /**
     * Takes the hold back on the way up from the AOD, over one spring.
     *
     * Not reassertCoverClock(): that writes the hold and drives it in one snap, which is right
     * when the collapse is already on the views and wrong when it is not. See the wake path in
     * registerReceiver() for what the snap costs.
     *
     * `from` - where the date sits with the squeeze wound off, which is what the placement
     * blends the path from - is re-sampled here rather than taken from whatever the last cover
     * mode left. It is only ever sampled while p is near zero, and the spring starts there, so
     * the reading is the position the date is actually at; a remembered one belongs to a
     * different layout and the path would begin with a jump of its own.
     */
    /**
     * Long enough to land with the OEM's own transition rather than before it.
     *
     * Measured: with 380ms the ramp is finished while the container the OEM is moving under
     * it still has about 150ms left - read frame by frame, the date's own position (our
     * translation divided out) walks 298 -> 252 after the ramp has already stopped. The clock
     * arrives, holds, and the rest of the wake completes around it, which is the "先顿一下
     * 之后再完成过渡".
     */
    /**
     * Takes the hold back on the way up from the AOD, over one short ramp.
     *
     * The clock is at the AOD's position and the AOD's size when this runs, so unlike
     * reassertCoverClock() this is a real move and it has to be seen. Short, though: the whole
     * ramp is meant to read as the clock arriving, not as a clock that stays big and then
     * shrinks. 220ms against the wallpaper's own fade-in.
     */
    private static final long WAKE_RAMP_MS = 220L;
    /** Material's standard curve - a lurch is worse here than elsewhere, since the travel is
     *  the full height of the clock. */
    private static final Interpolator WAKE_EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private static void wakeIntoCover() {
        sReleasing = false;
        sCollapseMin = collapseMinScale();
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sCardP = 1f;
        applyMediaCard();
        rampTo(SQUEEZE_FLOOR, WAKE_RAMP_MS, "STATE_CHANGED", false, WAKE_EASE);
        // The ramp above cannot carry the card on a style that emits no notifY; see
        // oemDrivesCard().
        if (!oemDrivesCard()) animateCardTo(1f, null);
        recolorClock();
        ensureClockGuard();
    }

    /**
     * Puts the collapse back if anything dropped it. Cheap and idempotent: when the hold already
     * survived (the normal path now), this only re-states values that are already correct, so
     * waking from AOD shows no transition at all.
     */
    private static void reassertCoverClock() {
        reassertCoverClock(false);
    }

    /**
     * force is for anything that has changed the answer while the hold was already in place: a
     * keyguard re-attach - which is also what changing the lock screen clock style looks like
     * from here - and a value the user just moved in the app.
     *
     * The hold surviving is not enough in either case. The hold is a number we coerce on the way
     * through setNotifY; the collapse is a scale and a translation living on the clock views
     * themselves, and a rebuilt clock is a fresh set of views with neither. Taking the early
     * exit below left the new clock at full size with the y still pinned - the symptom being a
     * clock that simply stops collapsing the moment the user picks a different style.
     *
     * And on a settled clock there are no frames at all: nothing is animating, so nothing is
     * going to carry a new scale to the view. Writing it down and waiting is waiting for ever,
     * which is exactly what the two sliders did until this was forced.
     */
    private static void reassertCoverClock(boolean force) {
        if (!sCoverMode) return;
        // Before the early exit below, not after: a hold that survived the keyguard being
        // rebuilt returns from there, and that is the case the guard exists for.
        ensureClockGuard();
        // Cover mode is being restored, not animated into - a wake, or a keyguard rebuilt under
        // us - so the card's progress is its settled value rather than a frame of something.
        // Without this it keeps whatever it was left with, which after a SystemUI restart is the
        // 0 that comes back from the state file: cover mode on, thumbnail still showing.
        setCardProgress(1f);
        sReleasing = false;
        sCollapseMin = collapseMinScale();
        sGlassV0 = 0f;
        sGlassV1 = sGlassEnd;
        Float held = sHoldY;
        if (!force && held != null && Math.abs(held - SQUEEZE_FLOOR) < 1f) return;
        sAppliedK = Float.NaN;
        sAppliedGlassV = Float.NaN;
        sHoldY = SQUEEZE_FLOOR;
        drive(SQUEEZE_FLOOR, false, "STATE_CHANGED");
        settleCollapsedClock(0);
        Xp.log(TAG + "cover clock re-collapsed after wake");
    }

    /**
     * Frames the re-assert gets: six once the nudge has been adopted, and up to forty while
     * either the clock is unmeasurable or the offset is still moving, at 80ms apart.
     *
     * Forty is not a guess. Measured on the restart path: the clock is unmeasurable for the first
     * ten passes, and the date's own layout then slides from 230 to 266 over the next seven -
     * the OEM is still animating its squeeze towards the y we are holding it at, and the date
     * moves with it. Two readings 80ms apart differ by ten pixels the whole way down, so the
     * loop adopts nothing until that stops, which takes about three seconds. A shorter tail is
     * what left the first lock screen at the un-nudged 270: the readings were all real, and the
     * last of them just needed a partner.
     */
    private static final int CLOCK_SETTLE_TRIES = 6;
    private static final int CLOCK_SETTLE_MAX = 40;
    private static final long CLOCK_SETTLE_MS = 80L;
    /**
     * Past CLOCK_SETTLE_MAX, how often to ask again and for how long.
     *
     * The fast window is 3.2s, which covers the OEM's own spring and a keyguard that is still
     * laying out. It does not cover the gap between the OEM's two clock variants, and a pass
     * that lands in that gap cannot measure anything - so the loop used to give up and leave the
     * clock uncollapsed until something else poked it. 40 + 80 passes at 250ms is another 20s,
     * which is the longest that gap has been seen to last, and it stops immediately on a style
     * that has no clock to take over at all.
     */
    private static final long CLOCK_SETTLE_SLOW_MS = 250L;
    private static final int CLOCK_SETTLE_HARD_MAX = 120;

    /**
     * Places the collapsed clock again over the next few frames, then stops.
     *
     * The placement normally rides the OEM's own frames - an entry animates for 600ms and every
     * one of those frames runs it - but the re-assert after a SystemUI restart has no animation
     * to ride. The clock container attaches, we drive notifY once, and the OEM stops emitting
     * frames the moment it has nothing left to move. If that single frame lands before the
     * keyguard's clock has been measured (and it does: the container attaches at 0,0-0,0, and
     * `clock style not understood: date=MISSING glyphs=MISSING` is what that one frame says),
     * then placeCollapsedClock() bails and NOTHING runs it again - the clock sat uncollapsed, or
     * at a pivot taken from half a glyph box, until the user locked the screen and got a real
     * collapse to converge on. That is the "first time after a restart is always crooked" bug.
     *
     * Six frames rather than a condition: the interesting question is not whether this pass
     * worked but whether the LAYOUT has settled, and a pass that succeeded on a half-measured
     * clock is exactly the crooked case. By the last one the keyguard has been through measure,
     * layout and the OEM's own squeeze, and every pass writes the same numbers a normal frame
     * of the collapse would, so the extra ones cost nothing but are idempotent.
     */
    private static void settleCollapsedClock(final int attempt) {
        if (!sCoverMode || Float.isNaN(sCollapseMin)) return;
        Float y = sHoldY;
        if (y == null) return;
        // Overlapping chains are harmless - every pass writes the values the last one did - and
        // the alternative is bookkeeping for a method that runs a handful of times per restart.
        if (attempt > CLOCK_SETTLE_MAX) return;
        float p = coverProgress(y);
        if (Float.isNaN(p)) {
            // No natural y to measure against yet, and on a fresh SystemUI there may never be
            // one: coverProgress() needs a frame the OEM emitted on its own, and after a restart
            // the only frames going are ours. Held at the squeeze floor the collapse is complete
            // by definition - p is 1 whatever the natural y turns out to be - so this is not a
            // guess, it is the definition the hold was taken under. (This was the second reason
            // the first lock screen stayed wrong: the retry loop ran, and every pass through it
            // returned here.)
            if (y > SQUEEZE_FLOOR) return;
            p = 1f;
        }
        boolean placed = placeCollapsedClock(1f - p * (1f - sCollapseMin), y, p);
        // Two reasons to keep going, and both are the bug rather than a nicety.
        //
        // The clock may not be measurable yet - the keyguard is still laying out - and a pass
        // that never ran is the whole complaint; one that runs late costs a view write that
        // writes what is already there.
        //
        // And the date's offset is a feedback loop that refuses to adopt a reading until two
        // passes agree (its gain is 0.9, so it needs two or three readings to converge). With
        // the OEM's own squeeze still animating behind us the first readings are -3, -39, -42:
        // every one of them a real measurement, none of them agreeing with the last, and the
        // No adoption to wait for any more: the offset is computed from the date's own position
        // on every frame, so a pass that placed the clock has placed it correctly and the tail is
        // only for a layout that had not settled yet when the first passes ran.
        if (sVerbose) {
            Xp.log(TAG + "settle " + attempt + ": placed=" + placed);
        }
        if (placed && attempt >= CLOCK_SETTLE_TRIES) return;
        // A style with no clock to take over is not going to grow one, and the relayout watcher
        // will speak up if it ever does. Waiting on it here would be a few hundred fruitless
        // passes every time cover mode comes on - the oriental C style is exactly that case.
        boolean anyTarget = false;
        for (View root : clockRoots()) {
            if (clockTarget(root) != null) { anyTarget = true; break; }
        }
        if (!anyTarget) return;
        if (attempt >= CLOCK_SETTLE_HARD_MAX) return;
        // Fast while the keyguard settles, slow afterwards. A pass that could not measure is not
        // a pass that failed: either the layout has not arrived, or the OEM is between its two
        // clock variants - and in that moment one tree draws nothing while the other draws HALF
        // a face, which glyphBox() is right to refuse. Giving up there is what left the big clock
        // sitting under the media card: nothing else pokes the clock until the next entry, and
        // the user is looking at the overlap the whole time. Measured on device - the first pass
        // reported "clock style not understood: date=MISSING glyphs=MISSING" and the re-assert
        // that follows it placed perfectly, but only because it was a restart path that ran one.
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                settleCollapsedClock(attempt + 1);
            }
        }, attempt < CLOCK_SETTLE_MAX ? CLOCK_SETTLE_MS : CLOCK_SETTLE_SLOW_MS);
    }

    /**
     * The combine behind nsslLockYPosition, whatever R8 called it this time.
     *
     * The name carries an ordinal that is assigned per build, so it cannot be written down: a
     * number measured on one HyperOS is simply a wrong guess on the next. The class loader's own
     * dex tables are asked instead, and the ordinal probe below only exists for the case where
     * those private fields have moved.
     */
    private static Class<?> findAvoidCombine(ClassLoader cl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field pf = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pf.setAccessible(true);
            Object pathList = pf.get(cl);
            java.lang.reflect.Field ef = pathList.getClass().getDeclaredField("dexElements");
            ef.setAccessible(true);
            for (Object el : (Object[]) ef.get(pathList)) {
                try {
                    java.lang.reflect.Field df = el.getClass().getDeclaredField("dexFile");
                    df.setAccessible(true);
                    Object dex = df.get(el);
                    if (dex == null) continue;
                    java.util.Enumeration<?> en = (java.util.Enumeration<?>)
                            dex.getClass().getMethod("entries").invoke(dex);
                    while (en.hasMoreElements()) {
                        Object o = en.nextElement();
                        if (!(o instanceof String)) continue;
                        // Normalised first: entries() has been seen returning both the internal
                        // form and the binary one, and matching only one of them would fail
                        // silently on the other.
                        String n = ((String) o).replace('/', '.');
                        if (n.startsWith(AVOID_PREFIX) && n.endsWith(AVOID_SUFFIX)) names.add(n);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dex walk for the avoid combine failed: " + t);
        }
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        for (int i = 1; i <= 400; i++) {
            try {
                Class<?> c = Class.forName(AVOID_PREFIX + "$" + i + AVOID_SUFFIX, false, cl);
                if (invoke3(c) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Kotlin's Function3 bridge: invoke(FlowCollector, Object[], Continuation). */
    private static java.lang.reflect.Method invoke3(Class<?> c) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterTypes().length == 3) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Is this drawable one of the fingerprint ring's frames?
     *
     * Asked by resource name rather than against a list of ids: ids are assigned per build, so a
     * list would have to be re-measured on every phone, while the names the OEM gives these
     * frames have been stable. The answer is cached because the question is asked inside a draw.
     */
    private static boolean isFodRing(int resId) {
        Boolean known = sFodRing.get(resId);
        if (known != null) return known;
        Context c = sAppCtx;
        // No context yet is not an answer worth remembering: it means the keyguard has not
        // attached, and the next draw will resolve properly.
        if (c == null) return false;
        boolean ring = false;
        try {
            String name = c.getResources().getResourceEntryName(resId);
            ring = name != null
                    && (name.startsWith("finger_circle") || name.contains("fingerprint_circle"));
        } catch (Throwable ignored) {
            // Not a resource in SystemUI's table, so not one of the OEM's ring frames. Cached
            // as such - the same id will come back on the next frame.
        }
        sFodRing.put(resId, ring);
        return ring;
    }

    /**
     * Applies the current setting to every icon view still alive. Runs on the main thread: the
     * receiver has no handler of its own, so it is already there.
     */
    private static void applyHideFp() {
        float alpha = sHideFp ? 0f : 1f;
        java.util.List<View> views;
        synchronized (sFodIcons) {
            views = new java.util.ArrayList<>(sFodIcons.keySet());
        }
        for (View v : views) {
            try {
                v.setAlpha(alpha);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void setDepthHidden(final boolean hide) {
        setDepthHidden(hide, 0);
    }

    /**
     * attempt exists because this runs on a SystemUI that has just started: cover mode is
     * restored the moment the clock container attaches, and the foreground layer that holds the
     * cut-out is not necessarily inflated yet. The old code logged "not found" once and gave up,
     * which left the old wallpaper's subject sitting on top of the album cover until something
     * else happened to call it again.
     */
    private static void setDepthHidden(final boolean hide, final int attempt) {
        sDepthHidden = hide;
        final View v = sContainer;
        if (v == null) {
            retryDepth(hide, attempt, "no clock container");
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                View d = findDeductedImageView();
                if (d == null) {
                    retryDepth(hide, attempt, "deducted_image_view not found");
                    return;
                }
                d.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
                // On a live wallpaper the cut-out subject is a TextureView in the same layer
                // rather than this ImageView, and it is in front of the clock just the same.
                // Resolved every time rather than cached: it is added and removed by MIUI as
                // the wallpaper type changes.
                View fg = videoSurfaceView("keyguard_foreground_layer");
                sVideoFg = fg;
                // And the video itself, in the background layer. The cover is drawn over it
                // either way, but leaving it playing under an opaque view decodes a video
                // nobody can see - and if anything of it is still reaching the screen, this
                // is what says so.
                View bg = sVideoWallpaper ? videoSurfaceView("keyguard_background_layer") : null;
                sVideoBg = bg;
                setVideoSurfacesHidden(hide, bg, fg);
                if (hide) guardDepth(d); else releaseDepthGuard();
                Xp.log(TAG + "deducted_image_view " + (hide ? "hidden" : "shown")
                        + (fg == null ? "" : " (+ the live wallpaper's cut-out)"));
                saveState();
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
            public boolean onPreDraw() {
                if (sDepthHidden && d.getVisibility() == View.VISIBLE) {
                    // VISIBLE -> INVISIBLE only invalidates, it does not request a layout, so
                    // this cannot start a traversal loop.
                    d.setVisibility(View.INVISIBLE);
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
            }
        };
        d.getViewTreeObserver().addOnPreDrawListener(sDepthGuard);
        sDepthGuarded = d;
        Xp.log(TAG + "depth guard installed");
    }

    private static void releaseDepthGuard() {
        View d = sDepthGuarded;
        ViewTreeObserver.OnPreDrawListener g = sDepthGuard;
        sDepthGuarded = null;
        sDepthGuard = null;
        if (d == null || g == null) return;
        try {
            d.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- media card

    private static final int CARD_RETRIES = 12;
    private static final long CARD_RETRY_MS = 250L;
    /** How long the card has to hold still before its position is believed. */
    private static final long CARD_SETTLE_MS = 400L;

    static View findSysuiView(String id) {
        View v = sContainer;
        if (v == null) return null;
        View root = v.getRootView();
        int i = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
        View hit = i == 0 ? null : root.findViewById(i);
        return hit != null ? hit : findByName(root, id);
    }

    /**
     * Finds a view by its resource entry name, walking the tree instead of resolving an id.
     *
     * getIdentifier() only searches the one package it is given, and not everything on the
     * keyguard comes from com.android.systemui's own resources: the two shortcut buttons at the
     * bottom came back "not found" by id while a dump of the very same tree listed them by name.
     * The name is read off each view's own Resources, which is exactly why the dump can see them.
     */
    private static View findByName(View v, String name) {
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
     * Finds the card, applies the current look and leaves a guard behind to keep it applied.
     * Called whenever the card or cover mode changes; re-running it is free.
     *
     * The retries are the same story as the cut-out's: on a SystemUI that has just started,
     * cover mode is restored the moment the clock container attaches, which can be before the
     * notification stack has inflated anything.
     */
    private static void applyMediaCard() {
        applyMediaCard(0);
    }

    private static void applyMediaCard(final int attempt) {
        main().post(new Runnable() {
            @Override
            public void run() {
                View card = findSysuiView("mi_media_controls");
                if (card == null) {
                    // Only worth waiting for while something still wants something from the
                    // card: the restyle on the way in, or the artwork tap that is the way back.
                    if (attempt < CARD_RETRIES
                            && (((sCoverMode || sStyle2Mode) && (sMcHideArt || sMcCenterText || sMcTitleTap))
                                || wantsArtTap())) {
                        main().postDelayed(new Runnable() {
                            @Override
                            public void run() { applyMediaCard(attempt + 1); }
                        }, CARD_RETRY_MS);
                    }
                    return;
                }
                View art = card.findViewById(card.getResources()
                        .getIdentifier("album_art", "id", "com.android.systemui"));
                if (art != null && art != sCardArt) {
                    // A card view we have not written to. Forgetting what we wrote to the last
                    // one makes scaleArt() read this one's scale as the OEM's, which it is -
                    // otherwise a new thumbnail that happened to arrive carrying the number we
                    // left on the old view would be scaled against the wrong baseline.
                    sArtWroteX = sArtWroteY = Float.NaN;
                }
                sCardArt = art;
                sCardTitle = card.findViewById(card.getResources()
                        .getIdentifier("header_title", "id", "com.android.systemui"));
                sCardArtist = card.findViewById(card.getResources()
                        .getIdentifier("header_artist", "id", "com.android.systemui"));
                assertMediaCard(card);
                if ((sCoverMode || sStyle2Mode) && (sMcHideArt || sMcCenterText || sMcTitleTap)) guardCard(card);
                else releaseCardGuard();
            }
        });
    }

    /**
     * One pass of "the card should look like this". Runs from the guard, so every write is
     * conditional on the value actually being wrong - setVisibility and setTranslationX only
     * invalidate, but setGravity requests a layout, and doing that unconditionally from a
     * pre-draw listener is a traversal loop.
     */
    private static void assertMediaCard(View card) {
        // The lock screen and the shade share one media card view, so "is the keyguard up" has
        // to be part of the condition rather than just cover mode: without it, pulling the shade
        // down on an unlocked phone would show the same restyled card, and these two settings
        // are about the lock screen. The clock container is the test - only the keyguard shows
        // it - and because this runs every frame, the card restores itself on unlock by itself.
        View c = sContainer;
        boolean onKeyguard = sCardForced || (keyguardShowing() && c != null && c.isShown());
        // A capture for the app's preview wants the settled look, not whichever frame an
        // animation happens to be on. Everywhere else the fade IS the state.
        float p = !onKeyguard ? 0f
                : sCardForced ? ((sCoverMode || sStyle2Mode) ? 1f : 0f)
                : sCardP;
        float hideP = sMcHideArt ? p : 0f;
        float centreP = sMcCenterText ? p : 0f;
        View art = sCardArt;
        if (art != null) {
            // INVISIBLE at the far end, not GONE: the constraints around it are the card's
            // whole layout, and collapsing the artwork would drag the text sideways on its own
            // terms rather than ours. It also leaves the thumbnail's bitmap in place, which is
            // still the module's last-resort source for the cover itself.
            //
            // Anywhere short of the end it is VISIBLE with an alpha and a scale, and that is
            // the fade. Both only invalidate, so writing them from a pre-draw listener carries
            // the same guarantee the translationX below does, and the two resting states are
            // bit-for-bit the two the card had before there was an animation at all.
            if (hideP >= 1f) {
                if (art.getVisibility() != View.INVISIBLE) art.setVisibility(View.INVISIBLE);
                // Out of sight, but put back exactly as the OEM had it: the shade shows this
                // same view, and a thumbnail left at 0.82 of its size there would be ours.
                scaleArt(art, 1f);
                if (art.getAlpha() != 1f) art.setAlpha(1f);
            } else {
                if (art.getVisibility() != View.VISIBLE) art.setVisibility(View.VISIBLE);
                float a = 1f - hideP;
                if (art.getAlpha() != a) art.setAlpha(a);
                scaleArt(art, 1f - hideP * (1f - CARD_ART_MIN_SCALE));
            }
        }
        centreCardText(card, (TextView) sCardTitle, centreP);
        centreCardText(card, (TextView) sCardArtist, centreP);
        applyTitleTap((TextView) sCardTitle, sMcTitleTap && (sCoverMode || sStyle2Mode) && onKeyguard);
        if (onKeyguard && !sCardForced) sampleCardRect(card, p);
    }

    /**
     * Makes the card's title a play/pause button, or hands the title back.
     *
     * Asserted from the guard rather than set once when the card was found, because the same
     * view is also the shade's: the lock screen's title is ours and the shade's is the OEM's,
     * and the guard is the only thing here that knows which of the two is on screen. Written
     * only when the state actually changes - the guard runs every frame and this puts a
     * listener on, which is not something to redo sixty times a second.
     *
     * Java has no way to read a view's existing OnClickListener back off it, so what the title
     * had is remembered as the two things that CAN be read - whether it was clickable, and
     * whether it had a listener at all - and the latter is logged, because a title that already
     * does something of its own would be silently swallowed here.
     */
    private static void applyTitleTap(TextView title, boolean on) {
        View mine = sCardTitleTapped;
        boolean want = on && title != null;
        if (mine == title && want) return;
        if (mine == null && !want) return;
        if (mine != null) {
            sCardTitleTapped = null;
            try {
                mine.setOnClickListener(null);
                mine.setClickable(sCardTitleClickable);
            } catch (Throwable ignored) {
            }
        }
        if (!want) return;
        sCardTitleClickable = title.isClickable();
        if (title.hasOnClickListeners()) {
            Xp.log(TAG + "card title already had a click listener - it will not be restored");
        }
        sCardTitleTapped = title;
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayback();
            }
        });
    }

    /**
     * Play or pause whatever the card is showing.
     *
     * Through the session the card is bound to rather than the media-key broadcast the OEM's
     * own button ends up sending: same result when nothing else is going on, but it cannot
     * drift onto a different player, which a key event can when two of them are open.
     * The state is read rather than toggled blind because the transport controls have play()
     * and pause() and no playPause() of their own.
     */
    private static void togglePlayback() {
        MediaController c = sWatched;
        if (c == null) {
            rebindSession();
            c = sWatched;
        }
        if (c == null) {
            Xp.log(TAG + "title tap: no session to play");
            return;
        }
        try {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            MediaController.TransportControls t = c.getTransportControls();
            if (t == null) {
                Xp.log(TAG + "title tap: no transport controls");
                return;
            }
            if (playing) t.pause();
            else t.play();
            Xp.log(TAG + "title tap -> " + (playing ? "pause" : "play")
                    + " on " + c.getPackageName());
        } catch (Throwable t) {
            Xp.log(TAG + "title tap failed: " + t);
        }
    }

    /**
     * Centres one line of the card's text on the card.
     *
     * translationX only - it moves the view without touching the constraints, so the OEM can
     * re-run its own layout without fighting us and letting go is a single write back to zero.
     *
     * What is moved is the GLYPH RUN, not the view. The two obvious approaches both fail here:
     * the text views are constrained to the space beside the artwork, so centring the view in
     * the card leaves the text sitting at the left edge of a box that is wider than the words;
     * and setGravity(CENTER_HORIZONTAL) had no effect on this card at all (reported from the
     * device: text still hard left). Reading the drawn line straight off the Layout sidesteps
     * both - it already accounts for whatever gravity, padding and ellipsis are in force, so
     * the sum below is "where the ink starts now" against "where it should start".
     */
    private static void centreCardText(View card, TextView t, float p) {
        if (t == null) return;
        if (p <= 0f) {
            if (t.getTranslationX() != 0f) t.setTranslationX(0f);
            return;
        }
        Layout lay = t.getLayout();
        if (lay == null || lay.getLineCount() < 1 || card.getWidth() <= 0) return;
        float ink = lay.getLineWidth(0);
        if (ink <= 0f) return;
        float have = t.getLeft() + t.getPaddingLeft() + lay.getLineLeft(0);
        // Scaled by the progress, so the line slides between where the OEM put it and the
        // centre instead of jumping between the two.
        float dx = ((card.getWidth() - ink) / 2f - have) * p;
        if (t.getTranslationX() != dx) t.setTranslationX(dx);
    }

    /**
     * Records where the card is, for the app's preview.
     *
     * Only called with the keyguard actually showing: the same card is what the shade puts at
     * the top of the notification list, at a completely different height, and a preview drawn
     * from that reading would put the card where it never appears on the lock screen.
     *
     * A reading is only believed once it has held still for CARD_SETTLE_MS, which is the same
     * rule the date offset uses and for the same reason: the card slides in when it appears and
     * slides away again at unlock, so those frames are readings of a card in flight. Measured
     * without any settling rule: 62,1676 - the card twenty pixels off centre, caught on its way
     * out. Two identical frames were not enough either - a card can be laid out at its starting
     * position and sit there for a few frames before the animation begins, which is how a
     * reading 244px above the real one got through.
     */
    private static void sampleCardRect(View card, float p) {
        // Not mid-morph. The reading below is of a card that has HELD STILL for CARD_SETTLE_MS,
        // and while the cover is going up or coming down the card is moving on every frame - so
        // the settle test can never pass and the only thing a read here produces is the bill for
        // getLocationOnScreen(), which walks the parent chain and its transforms. Asked on every
        // frame of a transition it measured 100us and up, which was the whole of what the card's
        // share of a frame cost once the two binder calls were memoised.
        //
        // The ends are what it is for: p at 0 is the card as the OEM lays it out, p at 1 is the
        // restyled one, and both hold still. Skipping the middle cannot lose a reading - the
        // settle rule stamps its clock from the first read, and the first read after the morph
        // stops is the same rectangle the skipped ones would have been stamped with.
        if (p > 0f && p < 1f) return;
        // Not while dozing. AOD shows the keyguard and holds still for as long as it is up, so
        // it sails past the settle rule below - and it lays the card out somewhere else, which
        // is how the preview ended up drawing it too high. isInteractive() is false in doze.
        if (!card.isShown() || !screenOn()) return;
        int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] loc = LOC_CARD;
        card.getLocationOnScreen(loc);
        // On the lock screen the card lives in the notification area, below a clock pinned near
        // the top; it is never up by the status bar. Anything that high is the shade's copy of
        // the same view - the panel can be pulled down over the lock screen too, where the
        // keyguard test above still passes.
        if (loc[1] < sScreenH / 3) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (loc[0] != sCardSampleL || loc[1] != sCardSampleT
                || w != sCardSampleW || h != sCardSampleH) {
            sCardSampleL = loc[0];
            sCardSampleT = loc[1];
            sCardSampleW = w;
            sCardSampleH = h;
            sCardSampleAt = now;
            return;
        }
        if (now - sCardSampleAt < CARD_SETTLE_MS) return;
        if (loc[0] == sCardL && loc[1] == sCardT && w == sCardW && h == sCardH) return;
        sCardL = loc[0];
        sCardT = loc[1];
        sCardW = w;
        sCardH = h;
        Xp.log(TAG + "media card at " + sCardL + "," + sCardT + " " + sCardW + "x" + sCardH);
        saveStateSoon();
    }

    /**
     * One frame of the card fade, written by whatever computed the progress.
     *
     * Storing the number would not be enough. The guard is a pre-draw listener, and a card that
     * nothing has invalidated does not draw - a progress that only lived in a field would sit
     * there until the OEM next happened to touch the card. Writing through here invalidates it,
     * and the guard goes on doing what it always did: catching the frames the OEM starts.
     */
    /**
     * Whether the OEM's own squeeze will carry the card's progress on this clock style.
     *
     * The card fade was deliberately given no animator of its own: it rides the same 0..1 that
     * applyCollapse() derives from the clock's notifY, so one OEM spring moves the collapse, the
     * glass and the card together. That holds only where there IS a notifY to ride.
     *
     * On the classic clock style there is not. notifStateChange never reaches
     * KeyguardClockNotifInteractor.setNotifY there, so sLastSystemY is never learned and stays
     * NaN from process start - measured with verbose on: zero setNotifY calls across a full
     * enter and exit. No natural Y means no progress, which took the two card settings down
     * with the clock's collapse even though they have nothing to do with the clock.
     */
    private static boolean oemDrivesCard() {
        float natural = sLastSystemY;
        return !Float.isNaN(natural) && natural > SQUEEZE_FLOOR;
    }

    private static ValueAnimator sCardAnim;

    /**
     * Walks the card's progress on our own frames, for the styles where the OEM has none.
     *
     * Only ever a stand-in: where oemDrivesCard() is true this is not started at all and the
     * behaviour is exactly what it was. Writing through setCardProgress() means the guard and
     * the invalidation work the same way for both drivers.
     */
    private static void animateCardTo(final float target, final Runnable onEnd) {
        stopCardAnim();
        final float from = sCardP;
        if (from == target) {
            if (onEnd != null) onEnd.run();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, target);
        // The same length the wallpaper crossfades over, and derived from the same response -
        // a card that finished 200ms before the clock did would be the mismatch the two of them
        // were moved together to avoid. Only a stand-in: on a style the OEM drives there is no
        // card animator at all and this rides the spring.
        a.setDuration(fadeMsFor(sClockResponse));
        a.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                setCardProgress((Float) v.getAnimatedValue());
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator v) {
                if (sCardAnim == v) sCardAnim = null;
                if (onEnd != null) onEnd.run();
            }
        });
        sCardAnim = a;
        a.start();
    }

    private static void stopCardAnim() {
        ValueAnimator a = sCardAnim;
        sCardAnim = null;
        if (a != null) a.cancel();
    }

    private static void setCardProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (sCardP == p) return;
        sCardP = p;
        View card = sCardGuarded;
        if (card != null) assertMediaCard(card);
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
    private static boolean wantsArtTap() {
        return sTapToggle && sCardShowing;
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
    /** The notification row class, resolved once; null until it is. */
    private static volatile Class<?> sNotifRowCls;

    /** Counters for the three ways the card-blur hook can go quiet; diagnostics. */
    private static final java.util.concurrent.atomic.AtomicInteger sBlendCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sBlurApplied =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sBadArgs =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The notification row this view belongs to, or null.
     *
     * Asked of the VIEW and not of its position, and the first attempt at this got that wrong.
     * The obvious scope - "a descendant of NotificationPanelView" - rejected every row on the
     * device: measured, `applyElementViewBlend` is called while the row is still being prepared,
     * when its ancestry ends at a detached root and never meets the panel at all. A test that
     * needs the row to be somewhere it has not been put yet is not a scope, it is an off switch.
     */
    private static View rowOf(View v) {
        Class<?> rc = rowClass(v);
        if (rc == null) return null;
        for (View c = v; c != null; ) {
            if (rc.isInstance(c)) return c;
            final android.view.ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    /**
     * The host's classloader, captured where one is known to be SystemUI's. See the note at the
     * capture: a view's own loader is not it, and using the view's is what silently disabled
     * this whole feature once.
     */
    private static volatile ClassLoader sHostLoader;

    private static Class<?> rowClass(View v) {
        Class<?> rc = sNotifRowCls;
        if (rc != null) return rc;
        ClassLoader ld = sHostLoader;
        if (ld == null) {
            ld = v.getClass().getClassLoader();
            // A framework view's loader cannot see SystemUI at all. Nothing is wrong and there is
            // nothing to try - the next call from a SystemUI view will resolve it.
            if (ld == null) return null;
        }
        try {
            rc = Xp.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    ld);
            sNotifRowCls = rc;
            return rc;
        } catch (Throwable t) {
            // Once, not per call: this is a failure that repeats on every element of every card.
            if (!sNoRowCls) {
                sNoRowCls = true;
                Xp.log(TAG + "no ExpandableNotificationRow via " + ld + ": " + t
                        + " - no card will be treated as a notification row");
            }
            return null;
        }
    }

    private static volatile boolean sNoRowCls;

    /**
     * Records a write, once per distinct (element, row) pair, with a running count.
     *
     * The first-only log this replaces made coverage invisible: "every row got the blur" and
     * "exactly one row did" printed the same single line, and telling those apart is the whole
     * of the current question.
     */
    /**
     * The system's own choice of which view is a card's material - recorded, not written.
     *
     * The write used to happen right here, at prepare time, and that is what put it on the lock
     * screen. A row is the SAME view on the keyguard and in the notification centre; prepare time
     * cannot tell which one it is preparing for, and the material outlives the prepare. The
     * element is still the system's to choose - only the moment moved, to the one place where
     * "is this the notification centre" has an answer: the finished tree with the shade open.
     * See setCardBlurActive().
     */
    private static final java.util.Map<View, View> sCandidates =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<View, View>());

    private static void noteCandidate(View v, View row) {
        synchronized (sCandidates) {
            if (sCandidates.size() > 512) {
                sCandidates.clear();
                Xp.log(TAG + "card elements: candidate set overflowed, cleared");
            }
            if (!sCandidates.containsKey(v)) sCandidates.put(v, row);
        }
        // A row prepared while the notification centre is ALREADY up never sees the rising edge,
        // and would sit there sampling the app behind the shade until the next pull-down. The
        // sweep is idempotent and the candidate list is small, so asking again is the cheap half
        // of that trade.
        if (sCardBlurActive) setCardBlurActive(true, false);
    }

    /**
     * Puts the local blur on every card element that is in the notification centre, or takes it
     * back off every one that has it.
     *
     * Driven from the edges of the shade being open - ShadeLayer's own progress driver is the
     * only thing in the module that knows the notification centre is up. Read the falling edge as
     * the important one: the write is a property of a view and does not know when it has stopped
     * being true, which is the whole of the lock screen bug.
     */
    /**
     * Whether the shade is up AND the feature is switched on - both, so that a card element
     * arriving later does not re-sweep for nothing while the switch is off.
     */
    private static volatile boolean sCardBlurActive;

    static void setCardBlurActive(final boolean on) {
        setCardBlurActive(on, true);
    }

    /**
     * @param withChrome also look for the clear-all affordance, which means walking the whole
     *                   shade window. True only on the edges of the shade being open: a card
     *                   element arriving later re-runs the sweep, and a full tree walk per card
     *                   is a lot of work to do on the frame a notification lands on.
     */
    private static void setCardBlurActive(final boolean on, final boolean withChrome) {
        sCardBlurActive = on && ShadeLayer.cardBlurOn();
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!on) {
                        final int n = restoreCards();
                        if (n > 0) Xp.log(TAG + "card blur off: restored " + n + " views");
                        return;
                    }
                    if (!ShadeLayer.cardBlurOn()) {
                        // Said out loud, and that is the fix to a real diagnosis problem: this
                        // returned silently, and a sweep that writes nothing because a switch is
                        // off looked identical in the log to a sweep that had no cards to write.
                        Xp.log(TAG + "card blur on: switch is off, nothing written");
                        return;
                    }
                    final java.util.List<View> vs;
                    synchronized (sCandidates) {
                        vs = new java.util.ArrayList<>(sCandidates.keySet());
                    }
                    int applied = 0, gone = 0, other = 0;
                    for (int i = 0; i < vs.size(); i++) {
                        final View v = vs.get(i);
                        // No position test here, and its absence is a measurement, not a
                        // simplification. It was "under NotificationPanelView", it rejected EVERY
                        // card on this build, and the ancestry probe says why: the notification
                        // stack is not under the panel at all. Measured chain -
                        //
                        //   NotificationBackgroundView#backgroundNormal
                        //     < ExpandableNotificationRow
                        //     < NotificationStackScrollLayout#notification_stack_scroller
                        //     < SharedNotificationContainer#shared_notification_container
                        //     < NotificationShadeWindowView#legacy_window_root
                        //
                        // - and the container's own name is the answer to the whole puzzle: it is
                        // SHARED between the keyguard and the notification centre, so the two are
                        // the same views in the same stack and no ancestry can separate them.
                        //
                        // The scoping is the TIMING instead, which is the stronger form of it.
                        // Every candidate is already known to be a card element - either inside an
                        // ExpandableNotificationRow and not a heads-up, or row-less and accepted
                        // below only if it belongs to the media card - and this sweep only runs
                        // across the edges of the shade being open on an unlocked phone. The
                        // keyguard cannot be written to because it is not what is on screen while
                        // the notification centre is up, which is
                        // precisely the mistake the prepare-time write made.
                        if (!v.isAttachedToWindow()) {
                            gone++;
                            continue;
                        }
                        if (sWritten.containsKey(v)) continue;
                        final View row = sCandidates.get(v);
                        // A row-less element is only ours if it belongs to the media card - the
                        // one card that is not a notification row, and the one the utility hands
                        // over as a bare ImageView. Everything else without a row is something
                        // else in the window, and the clock glass the last wrong guess cost is
                        // exactly what lives there.
                        if (row == null && !insideMediaCard(v)) {
                            other++;
                            continue;
                        }
                        writeCard(v, row);
                        applied++;
                    }
                    // The clear-all affordance, which is a different kind of thing from every
                    // card here and is looked for separately.
                    //
                    // It never appears in the candidate list at all: the utility is not handed it,
                    // so there is no element to wait for and no "was it accepted" to answer. It
                    // is the notification centre's own chrome rather than one of its cards, and
                    // the only handle on it is its id - so it is found by name, and every match is
                    // logged in full. That log is not decoration: if the wrong view is picked,
                    // this is the only place that can say so, because the pick is by name and a
                    // name is a guess about what a view is for.
                    final View panel = withChrome ? ShadeLayer.panelView() : null;
                    if (panel != null) {
                        final java.util.List<View> chrome = new java.util.ArrayList<>();
                        collectClearChrome(panel.getRootView(), chrome);
                        for (int i = 0; i < chrome.size(); i++) {
                            final View c = chrome.get(i);
                            if (sWritten.containsKey(c)) continue;
                            writeCard(c, null);
                            applied++;
                            Xp.log(TAG + "clear-all: wrote to " + c.getClass().getName()
                                    + " #" + viewIdOf(c) + " shown=" + c.isShown()
                                    + " wh=" + c.getWidth() + "x" + c.getHeight()
                                    + " " + MiBlur.describe(c));
                        }
                    }

                    // Logged only when the numbers change. A new element arrives while the shade
                    // is already up and re-runs the sweep, which is right but was printing the
                    // same line ten times in a millisecond.
                    final String line = applied + "/" + gone + "/" + other + "/" + sWritten.size();
                    if (!line.equals(sLastSweep)) {
                        sLastSweep = line;
                        Xp.log(TAG + "card blur on: " + applied + " applied, " + gone
                                + " detached, " + other + " not a card, "
                                + sWritten.size() + " holding it");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "card blur sweep failed: " + t);
                }
            }
        });
    }

    /** The last sweep line printed, so an unchanged sweep does not print again. */
    private static volatile String sLastSweep = "";

    // ------------------------------------------------------------------ content push

    /**
     * The notification centre's content, moved down out of the cover's way.
     *
     * The curtain is the WHOLE screen: at a full pull there is nothing on it that is not the
     * cover. The media card and the notification rows are glass over it, so the only way to show
     * more of the picture is to open a band of it - this shifts the content down by a fixed
     * amount, and what is left uncovered under the clock is cover with nothing on it.
     *
     * Driven from the same edge as the card blur, and for the same reason: the write is a property
     * of a VIEW, the keyguard and the notification centre are the same views, and a shift left
     * behind would move the lock screen's notifications with it.
     *
     * Written on the RISING edge, which is safe because of where the content is when it fires. The
     * edge is at 4.5% of a pull and the stack's first pixel is a third of the way down the screen,
     * so the write lands while the content is still off the bottom - there is no frame on which
     * the move can be seen. Doing it on the falling edge instead would be a shift on the frame the
     * shade is closing, which is the one frame that is definitely on screen.
     */
    private static volatile View sPushStack;
    private static volatile View sPushCard;
    /** The "no stack to move" warning is worth printing once, not on every pull-down. */
    private static volatile boolean sPushWarned;

    /**
     * @param on whether the cover is up in the notification centre. False restores, always.
     */
    static void setShadeContentShift(final boolean on) {
        final int px = ShadeLayer.contentPush();
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!on || px <= 0) {
                        pushRestore();
                        return;
                    }
                    final View root = ShadeLayer.shadeRoot();
                    if (root == null) return;
                    final View stack = notificationStack(root);
                    if (stack == null) {
                        if (!sPushWarned) {
                            sPushWarned = true;
                            Xp.log(TAG + "content push: no notification stack under "
                                    + root.getClass().getSimpleName() + " - there is nothing to "
                                    + "move on this build, so the setting does nothing here");
                        }
                        return;
                    }
                    // The media card is the one piece of the shade that may NOT live under the
                    // stack - and whether it does is the difference between one write and two.
                    // Under it, the stack carries the card down by itself. Beside it, the card
                    // needs the same shift on its own, or the gap opens between the card and the
                    // notifications instead of above the card.
                    final View card = mediaCardView(root);
                    View host = null;
                    if (card != null && !isAncestorOf(stack, card)) {
                        final android.view.ViewParent sp = stack.getParent();
                        if (sp instanceof View) host = outerChildUnder(card, (View) sp);
                    }
                    sPushStack = stack;
                    sPushCard = host;
                    stack.setTranslationY(px);
                    if (host != null) host.setTranslationY(px);
                    Xp.log(TAG + "content push: " + px + "px on "
                            + stack.getClass().getSimpleName() + "#" + viewIdOf(stack)
                            + (host != null
                                    ? " + " + host.getClass().getSimpleName() + "#" + viewIdOf(host)
                                    : card == null
                                            ? " (no media card found - the card will not move)"
                                            : " (card is inside the stack)"));
                } catch (Throwable t) {
                    Xp.log(TAG + "content push failed: " + t);
                }
            }
        });
    }

    /**
     * What the push is holding right now, for the shade diagnostic.
     *
     * The value is read back OFF THE VIEW rather than reported from our own write, because the
     * question this exists to answer is precisely whether the write survived: the stack is
     * SystemUI's own view and it is free to move itself, and "we set 300" and "the view is 300"
     * are different claims. `ty=0` with `on/300px` is the answer "the system took it back".
     */
    static String shadePushDescribe() {
        final View stack = sPushStack;
        if (stack == null) return "off";
        return "on/" + ShadeLayer.contentPush() + "px ty=" + stack.getTranslationY()
                + (sPushCard == null ? "" : " card ty=" + sPushCard.getTranslationY());
    }

    /** Hands the two views back. Idempotent, and reachable from every path out of the shade. */
    private static void pushRestore() {
        final View stack = sPushStack;
        final View card = sPushCard;
        if (stack == null && card == null) return;
        sPushStack = null;
        sPushCard = null;
        try {
            if (stack != null && stack.getTranslationY() != 0f) stack.setTranslationY(0f);
            if (card != null && card.getTranslationY() != 0f) card.setTranslationY(0f);
            Xp.log(TAG + "content push: back to 0");
        } catch (Throwable t) {
            Xp.log(TAG + "content push could not be undone: " + t);
        }
    }

    /**
     * The stack the notification rows live in.
     *
     * By id first, then by class: this module's own hooks have already been bitten once by a
     * renamed class on another HyperOS build, and a fallback walk that runs on the edges of a
     * pull-down is cheaper than a setting that quietly does nothing.
     */
    private static View notificationStack(View root) {
        try {
            final int id = root.getContext().getResources()
                    .getIdentifier("notification_stack_scroller", "id", "com.android.systemui");
            if (id != 0) {
                final View v = root.findViewById(id);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {
        }
        return findStackByClass(root);
    }

    private static View findStackByClass(View v) {
        if (v.getClass().getSimpleName().equals("NotificationStackScrollLayout")) return v;
        if (!(v instanceof ViewGroup)) return null;
        final ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            final View hit = findStackByClass(g.getChildAt(i));
            if (hit != null) return hit;
        }
        return null;
    }

    private static View mediaCardView(View root) {
        try {
            final int id = root.getContext().getResources()
                    .getIdentifier("mi_media_controls", "id", "com.android.systemui");
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The child of `ancestor` that `v` sits inside, or null when `v` is not under it at all.
     *
     * Matching on the ANCESTOR and not on a class name on purpose: the media card's own container
     * is an implementation detail of the build, and this asks the tree the one question that
     * matters - which view would have to move for the card to move with it.
     */
    private static View outerChildUnder(View v, View ancestor) {
        for (View c = v; c != null; ) {
            final android.view.ViewParent p = c.getParent();
            if (p == ancestor) return c;
            c = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    /**
     * Views whose id names the clear-all affordance.
     *
     * A match that CONTAINS another match is dropped - the pill and the layout that holds it can
     * both be named for the same thing, and writing the local blur to both would blur the button
     * twice, once as itself and once as its parent's background. The innermost match is the pill.
     */
    private static void collectClearChrome(View v, java.util.List<View> out) {
        if (v.getId() != View.NO_ID) {
            final String n = viewIdOf(v).toLowerCase();
            if (n.contains("clear") || n.contains("dismiss")) {
                for (int i = 0; i < out.size(); ) {
                    if (isAncestorOf(v, out.get(i))) out.remove(i);
                    else i++;
                }
                if (!isInsideAny(v, out)) out.add(v);
            }
        }
        // Descends into a match as well: the pill and the layout holding it can both be named for
        // the same thing, and the inner one is the pill.
        if (!(v instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectClearChrome(g.getChildAt(i), out);
    }

    private static boolean isAncestorOf(View a, View b) {
        for (View c = b; c != null; ) {
            if (c == a) return true;
            final android.view.ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private static boolean isInsideAny(View v, java.util.List<View> set) {
        for (int i = 0; i < set.size(); i++) if (isAncestorOf(set.get(i), v)) return true;
        return false;
    }

    /**
     * Whether this view belongs to the media card.
     *
     * Asked of the TREE and not of the view's class, and asked at the sweep for the same reason
     * everything else is: the card hands the hook a bare ImageView with nothing in it to
     * recognise, and only the assembled tree can say whose it is. `mi_media_controls` is the
     * module's own handle on this card - the same id the card takeover feature already uses.
     */
    private static boolean insideMediaCard(View v) {
        int id = sMediaCardId;
        if (id == -1) {
            try {
                id = v.getContext().getResources()
                        .getIdentifier("mi_media_controls", "id", "com.android.systemui");
            } catch (Throwable t) {
                id = 0;
            }
            sMediaCardId = id;
            Xp.log(TAG + "media card id = " + id);
        }
        if (id == 0) return false;
        for (View c = v; c != null; ) {
            if (c.getId() == id) return true;
            final android.view.ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** -1 until resolved, 0 when this build has no such id. */
    private static volatile int sMediaCardId = -1;

    private static void writeCard(View v, View row) {
        noteBlurApplied(v, row);
        MiBlur.applyLocalBlur(v, ShadeLayer.cardBlurRadius());
    }

    /** Takes the local blur back off every view it was written to. Returns how many. */
    private static int restoreCards() {
        final java.util.List<View> vs;
        synchronized (sWritten) {
            vs = new java.util.ArrayList<>(sWritten.keySet());
        }
        for (int i = 0; i < vs.size(); i++) {
            final View v = vs.get(i);
            MiBlur.restore(v, sWritten.get(v));
        }
        synchronized (sWritten) {
            sWritten.clear();
        }
        return vs.size();
    }

    private static void noteBlurApplied(View v, View row) {
        // Snapshot BEFORE the write, so the write can be taken back. The first snapshot wins: a
        // row that is prepared again and again must go back to the material it had before the
        // very first write, not to the one it had before the latest.
        final int[] was = MiBlur.snapshot(v);
        synchronized (sWritten) {
            if (!sWritten.containsKey(v)) sWritten.put(v, was);
        }
        final int n = sBlurApplied.incrementAndGet();
        final String kind = v.getClass().getName() + " in "
                + (row == null ? "?" : row.getClass().getSimpleName());
        if (sBlurAppliedKinds.add(kind)) {
            Xp.log(TAG + "card blur applied #" + n + " to " + kind
                    + " key=" + rowKey(row) + " inPanel=" + ShadeLayer.insidePanel(v)
                    + " was=" + java.util.Arrays.toString(was)
                    + " r=" + ShadeLayer.cardBlurRadius());
        }
    }

    /**
     * Views the local blur has been written to, by identity, with the state each had before it.
     *
     * Strong references, which is normally the wrong thing to hold in a system process - but
     * this is the only record of what the system's own material was, and a view that has been
     * forgotten is a view that cannot be put back. The rows come from a bounded recycled pool.
     */
    private static final java.util.Map<View, int[]> sWritten =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<View, int[]>());
    private static final java.util.Set<String> sBlurAppliedKinds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** A notification row's stable key, for naming it in the log. "?" when this build moved it. */
    private static String rowKey(View row) {
        if (row == null) return "?";
        try {
            final Object k = Xp.callMethod(row, "getKey");
            if (k != null) return String.valueOf(k);
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * Every notification row under the panel, and whether the local blur reached it.
     *
     * The hook fires per row while the row is being prepared, so which rows it reaches is
     * decided by the order the tree is built in - and nothing in the log said how many rows
     * there were or which of them were missed. This asks the finished tree instead.
     */
    private static void dumpNotifRows() {
        final View panel = ShadeLayer.panelView();
        if (panel == null) {
            Xp.log(TAG + "rows: no panel - is the shade up?");
            return;
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Class<?> rc = rowClass(panel);
                    if (rc == null) {
                        Xp.log(TAG + "rows: no row class");
                        return;
                    }
                    final java.util.List<View> rows = new java.util.ArrayList<>();
                    // From the WINDOW, not from the panel: measured, the panel is not an
                    // ancestor of a single card element on this build, so a walk rooted there
                    // finds nothing and says "no rows" - which reads exactly like "no
                    // notifications" and cost a wrong scope test.
                    final View root = panel.getRootView();
                    collectRows(root, rc, rows);
                    Xp.log(TAG + "rows: " + rows.size() + " under "
                            + root.getClass().getSimpleName()
                            + " cardBlurOn=" + ShadeLayer.cardBlurOn()
                            + " r=" + ShadeLayer.cardBlurRadius());
                    for (int i = 0; i < rows.size(); i++) {
                        final View r = rows.get(i);
                        final int[] loc = new int[2];
                        r.getLocationOnScreen(loc);
                        Xp.log(TAG + "row#" + i + " " + r.getClass().getSimpleName()
                                + " key=" + rowKey(r)
                                + " headsUp=" + isHeadsUp(r)
                                + " vis=" + r.getVisibility()
                                + " at=" + loc[0] + "," + loc[1]
                                + " wh=" + r.getWidth() + "x" + r.getHeight()
                                + " written=" + sWritten.containsKey(r)
                                + " shown=" + r.isShown()
                                + " inPanel=" + ShadeLayer.insidePanel(r)
                                + " bg=" + describeBackground(r));
                        Xp.log(TAG + "  anc=" + ancestry(r));
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "rows probe failed: " + t);
                }
            }
        });
    }

    private static void collectRows(View v, Class<?> rc, java.util.List<View> out) {
        if (rc.isInstance(v)) out.add(v);
        if (!(v instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectRows(g.getChildAt(i), rc, out);
    }

    /** A row's own background view - the thing the hook writes to - and its live blur state. */
    private static String describeBackground(View row) {
        if (!(row instanceof ViewGroup)) return "no children";
        final ViewGroup g = (ViewGroup) row;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            if (!c.getClass().getName().contains("Background")) continue;
            sb.append(c.getClass().getSimpleName())
              .append("[written=").append(sWritten.containsKey(c))
              .append(' ').append(MiBlur.describe(c)).append("] ");
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /**
     * Every view in the shade window carrying a blur, and its live state.
     *
     * This is the oracle for "whose glass is this". A card's material lives on one view, and a
     * card whose glass is wrong cannot be fixed by anyone who does not know which view to write
     * to - the media card's glass is not on a notification row, never reaches the hook, and so
     * is invisible to every other probe in the module. Views we have written are marked with a
     * `*`, so our own writes and the system's own material can be told apart in one listing.
     */
    private static void dumpBlurScan() {
        final View panel = ShadeLayer.panelView();
        if (panel == null) {
            Xp.log(TAG + "blurscan: no panel - is the shade up?");
            return;
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final View root = panel.getRootView();
                    Xp.log(TAG + "blurscan from " + root.getClass().getSimpleName()
                            + " cardBlurOn=" + ShadeLayer.cardBlurOn()
                            + " written=" + sWritten.size());
                    scanBlur(root);
                } catch (Throwable t) {
                    Xp.log(TAG + "blurscan failed: " + t);
                }
            }
        });
    }

    private static void scanBlur(View v) {
        final int[] now = MiBlur.snapshot(v);
        if (now[0] > 0 || now[1] > 0) {
            final int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            Xp.log(TAG + "blur" + (sWritten.containsKey(v) ? "*" : " ") + " "
                    + v.getClass().getName() + " #" + viewIdOf(v)
                    + " vis=" + v.getVisibility()
                    + " at=" + loc[0] + "," + loc[1]
                    + " wh=" + v.getWidth() + "x" + v.getHeight()
                    + " inPanel=" + ShadeLayer.insidePanel(v)
                    + " " + MiBlur.describe(v));
        }
        if (!(v instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) scanBlur(g.getChildAt(i));
    }

    /**
     * Takes the local blur back off every view it was written to.
     *
     * The write is a property of a VIEW and outlives the state that justified it, which is the
     * whole reason the lock screen's cards came out wrong: a row written while the notification
     * centre is up keeps that material on the keyguard, where the same row is the same view and
     * the correct glass samples the wallpaper instead.
     */
    private static void clearCardBlur() {
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Xp.log(TAG + "blurclear: restored " + restoreCards() + " views");
                } catch (Throwable t) {
                    Xp.log(TAG + "blurclear failed: " + t);
                }
            }
        });
    }

    /**
     * The media card and everything under it, with each view's live blur.
     *
     * The one card whose glass is NOT on a notification row: it never appears as a card element,
     * so it is invisible to the candidate list and to every other probe here. Its subtree is
     * printed class by class because the card is translucent over the container's blur rather
     * than carrying one of its own, and naming the view that would have to take a local blur is
     * the only way to give it the cover as well.
     */
    private static void dumpMediaCard(View root) {
        try {
            final int id = root.getContext().getResources()
                    .getIdentifier("mi_media_controls", "id", "com.android.systemui");
            final View card = id == 0 ? null : root.findViewById(id);
            if (card == null) {
                Xp.log(TAG + "mediacard: not under " + root.getClass().getSimpleName());
                return;
            }
            Xp.log(TAG + "mediacard " + card.getClass().getName() + " #" + viewIdOf(card)
                    + " shown=" + card.isShown() + " wh=" + card.getWidth() + "x"
                    + card.getHeight() + " " + MiBlur.describe(card));
            walkMediaCard(card, "  ");
        } catch (Throwable t) {
            Xp.log(TAG + "mediacard probe failed: " + t);
        }
    }

    private static void walkMediaCard(View v, String pad) {
        if (!(v instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            Xp.log(TAG + "  " + pad + c.getClass().getSimpleName() + " #" + viewIdOf(c)
                    + " vis=" + c.getVisibility()
                    + " wh=" + c.getWidth() + "x" + c.getHeight()
                    + " " + MiBlur.describe(c));
            walkMediaCard(c, pad + "  ");
        }
    }

    /**
     * A view's ancestry, innermost first, as `Class#id<Class#id<...`.
     *
     * Written because "is it under NotificationPanelView" turned out to be false for EVERY card
     * element on this device, which leaves the question of what container they are under instead.
     * A scope test can only be built on an ancestor that is actually there.
     */
    private static String ancestry(View v) {
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        for (View c = v; c != null && n < 7; n++) {
            if (n > 0) sb.append('<');
            sb.append(c.getClass().getSimpleName());
            if (c.getId() != View.NO_ID) sb.append('#').append(viewIdOf(c));
            final android.view.ViewParent p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        return sb.toString();
    }

    /** Every element the system has offered us, whether we wrote to it, and what it is. */
    private static void dumpCandidates() {
        final java.util.List<View> vs;
        synchronized (sCandidates) {
            vs = new java.util.ArrayList<>(sCandidates.keySet());
        }
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Xp.log(TAG + "cand: " + vs.size() + " elements, written=" + sWritten.size()
                            + ", cardBlurOn=" + ShadeLayer.cardBlurOn());
                    for (int i = 0; i < vs.size(); i++) {
                        final View v = vs.get(i);
                        final View row = sCandidates.get(v);
                        final int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        Xp.log(TAG + "cand " + (sWritten.containsKey(v) ? "*" : " ")
                                + " " + v.getClass().getName() + " #" + viewIdOf(v)
                                + " row=" + (row == null ? "none" : rowKey(row))
                                + " mediaCard=" + (row == null && insideMediaCard(v))
                                + " attached=" + v.isAttachedToWindow()
                                + " shown=" + v.isShown()
                                + " vis=" + v.getVisibility()
                                + " at=" + loc[0] + "," + loc[1]
                                + " wh=" + v.getWidth() + "x" + v.getHeight()
                                + " inPanel=" + ShadeLayer.insidePanel(v)
                                + " " + MiBlur.describe(v));
                        Xp.log(TAG + "  anc=" + ancestry(v));
                    }
                    if (!vs.isEmpty()) dumpMediaCard(vs.get(0).getRootView());
                } catch (Throwable t) {
                    Xp.log(TAG + "cand probe failed: " + t);
                }
            }
        });
    }

    /**
     * Whether this view, or the row it belongs to, is a floating notification.
     *
     * Unanswerable counts as yes, so a build whose row class or state method has moved keeps the
     * floating notification untouched and merely loses the local blur - the safe direction.
     */
    private static boolean isHeadsUp(View row) {
        try {
            return Boolean.TRUE.equals(Xp.callMethod(row, "isHeadsUpState"));
        } catch (Throwable ignored) {
        }
        try {
            return Boolean.TRUE.equals(Xp.callMethod(row, "isHeadsUp"));
        } catch (Throwable ignored) {
        }
        // Neither method is on this build, so the row cannot be shown not to be a pop-up.
        // Skipping is the safe way to be wrong here: the cost is a card that keeps the system's
        // own glass, and the alternative is restyling a floating notification nobody asked us
        // to touch.
        if (sNoHeadsUpApi) return true;
        sNoHeadsUpApi = true;
        Xp.log(TAG + "no isHeadsUpState/isHeadsUp on " + row.getClass().getName()
                + " - heads-up notifications cannot be told apart, so NO notification rows "
                + "will get the local blur on this build");
        return true;
    }

    private static volatile boolean sNoHeadsUpApi;

    private static boolean swallowArtTap(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean onCard = wantsArtTap() && screenOn() && keyguardShowing() && onKeyguardNow()
                    && !bouncerUp() && !sGestureOnCentre;
            sArtSwallow = onCard && artRectContains(ev.getRawX(), ev.getRawY());
            if (sArtSwallow) {
                sArtDownAt = android.os.SystemClock.uptimeMillis();
                sArtDownX = ev.getRawX();
                sArtDownY = ev.getRawY();
                sArtLongPressed = false;
                main().removeCallbacks(sArtLongPressRunnable);
                main().postDelayed(sArtLongPressRunnable, ART_LONG_PRESS_MS);
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
            main().removeCallbacks(sArtLongPressRunnable);
            long held = android.os.SystemClock.uptimeMillis() - sArtDownAt;
            sArtLongPressed = held >= ART_LONG_PRESS_MS;
            if (sArtLongPressed && screenOn() && keyguardShowing()) triggerArtworkLongPress();
            if (artRectContains(ev.getRawX(), ev.getRawY())) {
                // The same rectangle is both the way in and the way out, and which one it is
                // is read at UP rather than at DOWN: a cover that came up under the finger
                // during the gesture (the OEM can re-lay the card out at any point) would
                // otherwise send the tap the wrong way.
                if (sArtLongPressed) {
                    // The mini player was already selected as soon as the hold threshold elapsed.
                } else if (sCoverMode) {
                    exitFromTap("artwork tapped");
                } else {
                    enterFromTap("artwork tapped");
                }
            }
            sArtLongPressed = false;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            sArtSwallow = false;
            main().removeCallbacks(sArtLongPressRunnable);
            long held = android.os.SystemClock.uptimeMillis() - sArtDownAt;
            if (held >= ART_LONG_PRESS_MS && screenOn() && keyguardShowing()) triggerArtworkLongPress();
            sArtLongPressed = false;
        }
        return true;
    }

    private static void triggerArtworkLongPress() {
        if (sArtLongPressed) return;
        sArtLongPressed = true;
        sTapSuppressed = true;
        Xp.log(TAG + "artwork held 3.5s: showing mini player");
        if (!notifyPresentation("MINI_PLAYER")) setCoverEnabled(false, true);
    }

    /** The artwork's rectangle as this code sees it, for the log line above. */
    private static String artRectDesc() {
        View a = sCardArt;
        if (a == null) return "null";
        android.view.ViewParent p = a.getParent();
        if (!(p instanceof View)) return "unparented";
        int[] loc = new int[2];
        ((View) p).getLocationOnScreen(loc);
        return (loc[0] + a.getLeft()) + "," + (loc[1] + a.getTop())
                + " " + a.getWidth() + "x" + a.getHeight() + " shown=" + a.isShown();
    }

    /**
     * Shrinks the card's artwork to `factor` of whatever scale the OEM is giving it.
     *
     * Anything on the view that we did not put there is the OEM's, and remembering what we
     * last wrote is what tells the two apart. So the baseline is re-read on the first frame,
     * on a new card view, and on any frame the OEM's own animation has written over ours -
     * it follows the OEM live rather than latching one frame of it - and factor 1 writes that
     * baseline straight back. Sign included: see sArtBaseX for what a sampled scaleX cost.
     */
    private static void scaleArt(View art, float factor) {
        float x = art.getScaleX(), y = art.getScaleY();
        // Only adopt a reading that could be a RESTING value. The OEM animates this view, and
        // a frame caught mid-animation is not a baseline: catching a 0 made the thumbnail
        // vanish for good, because from then on every frame wrote 0 * factor and the value it
        // read back was its own 0. Magnitude has to be ~1 - and only the magnitude, because
        // the sign is the OEM's mirror and taking that as "not resting" is what flipped the
        // thumbnail before. Anything else leaves the last good baseline in place.
        if ((x != sArtWroteX || y != sArtWroteY) && resting(x) && resting(y)) {
            sArtBaseX = x;
            sArtBaseY = y;
        }
        float nx = sArtBaseX * factor, ny = sArtBaseY * factor;
        if (x != nx) art.setScaleX(nx);
        if (y != ny) art.setScaleY(ny);
        sArtWroteX = nx;
        sArtWroteY = ny;
    }

    /** A scale that could be the OEM's resting one: unit magnitude, either sign. */
    private static boolean resting(float v) {
        return Math.abs(Math.abs(v) - 1f) < 0.02f;
    }

    /**
     * Where the card's thumbnail is on screen right now.
     *
     * NOT getLocationOnScreen() on the artwork. That maps (0,0) through the view's own matrix
     * before adding mLeft, and this view has a matrix: measured, album_art reports an origin
     * exactly its own width (158px) to the right of its layout slot, with translationX 0 and
     * scaleX 1 - a horizontal mirror, the OEM's own. The reported rectangle therefore lands on
     * the title, which is what "tapping the title expands it, not the thumbnail" was.
     *
     * A mirror does not move what the user sees, so the layout slot IS the visible rectangle.
     * Taking it from the card - which carries no transform of its own - plus the child's
     * getLeft()/getTop() sidesteps the artwork's matrix while still picking up every ancestor's.
     */
    private static boolean artRectContains(float x, float y) {
        View a = sCardArt;
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
    private static boolean onKeyguardNow() {
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
    private static boolean bouncerUp() {
        View b = findSysuiView("keyguard_bouncer_container");
        return b != null && b.isShown();
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
    private static boolean controlCenterUp() {
        View cc = findSysuiView("control_center_container");
        if (!(cc instanceof ViewGroup)) return false;
        View content = findByName(cc, "content_container");
        if (!(content instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) content;
        return g.getChildCount() > 0 && g.getChildAt(0).isShown();
    }

    /**
     * Back to the plain wallpaper, with the card left standing where it is.
     *
     * sTapSuppressed is what holds it that way. A card being up is exactly what the module reads
     * as "the cover belongs here", so without it the next metadata event would put the cover
     * straight back. It is the card actually going away that clears it - not a track change -
     * so the next song starts in cover mode as it always did.
     */
    private static void exitFromTap(String why) {
        sTapSuppressed = true;
        Xp.log(TAG + why + ": leaving cover mode");
        if (!notifyPresentation("SYSTEM_MEDIA")) setCoverEnabled(false, true);
    }

    /**
     * Back into cover mode, through the normal path rather than by re-pushing whatever was last
     * composed: the track may well have moved on while the cover was off.
     */
    private static void enterFromTap(String why) {
        sTapSuppressed = false;
        sTrackKey = "";
        Xp.log(TAG + why + ": expanding into cover mode");
        if (!notifyPresentation("MUSIC_LOCKSCREEN")) onMediaUpdate();
    }

    /**
     * Main.java is the imported runtime and cannot depend on the Kotlin controller at compile
     * time. Resolve the small presentation bridge lazily so the gesture still works when a ROM
     * omits the optional Kotlin lockscreen classes.
     */
    private static boolean notifyPresentation(String name) {
        try {
            Class<?> bridgeClass = Class.forName(
                    "btm.m.os4.systemuihook.LockscreenMediaPresentationBridge");
            Object bridge = bridgeClass.getField("INSTANCE").get(null);
            Class<?> presentationClass = Class.forName(
                    "btm.m.os4.systemuihook.LockscreenMediaPresentation");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object presentation = Enum.valueOf((Class) presentationClass, name);
            bridgeClass.getMethod("setPresentation", presentationClass)
                    .invoke(bridge, presentation);
            return true;
        } catch (Throwable t) {
            Xp.log(TAG + "presentation bridge unavailable: " + t);
            return false;
        }
    }

    private static void guardCard(final View card) {
        if (sCardGuarded == card && sCardGuard != null) return;
        releaseCardGuard();
        sCardGuard = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                assertMediaCard(card);
                return true;
            }
        };
        card.getViewTreeObserver().addOnPreDrawListener(sCardGuard);
        sCardGuarded = card;
        Xp.log(TAG + "media card guard installed");
    }

    private static void releaseCardGuard() {
        View d = sCardGuarded;
        ViewTreeObserver.OnPreDrawListener g = sCardGuard;
        sCardGuarded = null;
        sCardGuard = null;
        // The guard is what gives the title back, so it has to happen here: with the guard gone
        // nothing would ever run the assert that would have done it, and the shade would keep a
        // title that pauses the music.
        applyTitleTap(null, false);
        if (d == null || g == null) return;
        try {
            d.getViewTreeObserver().removeOnPreDrawListener(g);
        } catch (Throwable ignored) {
        }
    }

    private static View findDeductedImageView() {
        View v = sContainer;
        if (v == null) return null;
        int id = v.getContext().getResources()
                .getIdentifier("deducted_image_view", "id", "com.android.systemui");
        return id == 0 ? null : v.getRootView().findViewById(id);
    }

    /**
     * Lays the cover out the way the Apple reference does: the artwork sharp at full width and
     * its own aspect, with a heavily blurred copy filling the screen above and below it.
     * Center-cropping a square cover into a 1200x2608 screen instead zooms ~5x and throws most
     * of the artwork away - on the Lover cover it cut the face in half.
     *
     * bias places the sharp band in the leftover vertical space: 0 flush with the top, 0.5
     * centred, 1 flush with the bottom. Centred is what the media card covers, so the default
     * sits above that.
     */
    private static Bitmap composeWallpaper(Bitmap src, int w, int h, float bias) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);

        float coverH = src.getHeight() * (w / (float) src.getWidth());
        if (bias < 0f) bias = 0f;
        if (bias > 1f) bias = 1f;
        float top = (h - coverH) * bias;

        // Blurring a centre-crop gave a muddy wash whose colours did not meet the sharp cover at
        // the seam. Extend the artwork by MIRRORING it above and below instead: the rows either
        // side of a seam are then the same row of the artwork, so the join is continuous by
        // construction, and the blur keeps local colour instead of averaging the whole image.
        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        float k = bw / (float) w;
        // Floored at a pixel: a panorama's band is a fraction of a row, and the loop below counts
        // copies of it.
        float cH = Math.max(1f, coverH * k), tp = top * k;
        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bg);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, tp, bw, tp + cH), p);
        // One mirrored copy each way is what this was, and it covers the background only while
        // the band is a large part of the screen: a square cover puts it at 1200 of 2608 and the
        // copy reaches both edges. A LANDSCAPE cover does not. Measured on a 960x539 artwork at
        // the default bias: the band is 674px, the single copy below it ends at 2057, and the
        // last 551 rows were never painted at all - the blur came out with a black bottom fifth.
        // The mirror is periodic with 2*coverH either way, so this draws the same picture,
        // continued until the background runs out.
        int tiles = Math.min(64, (int) Math.ceil(bh / cH) + 1);
        for (int i = 1; i <= tiles; i++) {
            boolean flip = (i % 2) == 1;
            drawTile(bc, src, bw, cH, tp + i * cH, flip, p);
            drawTile(bc, src, bw, cH, tp - i * cH, flip, p);
        }

        Bitmap blurred = blur(bg, 48, 4, 3);
        cv.drawBitmap(blurred, null, new android.graphics.RectF(0, 0, w, h), p);
        cv.drawColor(0x14000000);
        // The sharp band, feathered in its own pixels and then drawn in one go. See feathered().
        Bitmap band = feathered(src, w, Math.round(coverH),
                Math.min(240, Math.round(coverH / 4f)));
        cv.drawBitmap(band, null, new android.graphics.RectF(0, top, w, top + coverH), p);
        band.recycle();
        return out;
    }

    /** Style 2: mirrored/blurred artwork occupies only the area above a large bottom cover. */
    private static Bitmap composeWallpaperStyle2(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG);
        float coverW = w;
        float coverH = coverW * STYLE2_COVER_ASPECT;
        float coverTop = h - coverH;
        int sharpW = Math.max(1, Math.round(coverW));
        int sharpH = Math.max(1, Math.round(coverH));
        int feather = Math.min(sharpH / 3,
                Math.max(240, Math.round(h * 0.15f)));
        Bitmap cover = centerCropArtwork(src, sharpW, sharpH);

        int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bg);
        float scale = bw / (float) w;
        float tileH = Math.max(1f, coverH * scale);
        float anchor = coverTop * scale;
        // The base tile is the same center-cropped bitmap drawn sharply below. Its first row
        // therefore meets the mirrored upper tile at exactly the same colour, and it also keeps
        // the blur fully opaque under the feather instead of exposing a grey transparent strip.
        drawTile(bc, cover, bw, tileH, anchor, false, p);
        int tiles = Math.min(64, (int) Math.ceil(Math.max(anchor, 1f) / tileH) + 1);
        for (int i = 1; i <= tiles; i++) {
            drawTile(bc, cover, bw, tileH, anchor - i * tileH, (i & 1) == 1, p);
        }
        Bitmap blurred = blur(bg, 48, 4, 3);
        if (blurred != bg) bg.recycle();
        cv.drawBitmap(blurred, null, new RectF(0, 0, w, h), p);
        blurred.recycle();

        featherTop(cover, feather);
        float left = (w - coverW) * 0.5f;
        cv.drawBitmap(cover, null, new RectF(left, coverTop, left + coverW, h), p);
        cover.recycle();
        return out;
    }

    /** Preserves the artwork aspect ratio while cropping overflow to the requested frame. */
    private static Bitmap centerCropArtwork(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float drawW = src.getWidth() * scale;
        float drawH = src.getHeight() * scale;
        float left = (w - drawW) * 0.5f;
        float top = (h - drawH) * 0.5f;
        cv.drawBitmap(src, null, new RectF(left, top, left + drawW, top + drawH),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static void featherTop(Bitmap bitmap, int feather) {
        if (feather <= 0 || feather > bitmap.getHeight()) return;
        android.graphics.Canvas cv = new android.graphics.Canvas(bitmap);
        android.graphics.Paint mask = new android.graphics.Paint();
        mask.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
        mask.setShader(new android.graphics.LinearGradient(0, 0, 0, feather,
                new int[]{0x00000000, 0x28000000, 0xD7000000, 0xFF000000},
                new float[]{0f, 0.25f, 0.75f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bitmap.getWidth(), feather, mask);
    }

    /**
     * One mirrored copy of the artwork, cH tall with its top edge at y, clipped to the canvas.
     *
     * `flip` alternates down the strip, and that is what makes it a mirror rather than a repeat:
     * every copy is the reflection of the one before it, so the rows either side of a seam are
     * the same row of the artwork and the join is continuous by construction. Drawing it as a
     * translate to the tile's own bottom edge plus a vertical scale of -1, into a destination
     * rect that starts at this canvas's origin, is the same transform the one-copy version used
     * for both of the copies it drew.
     */
    private static void drawTile(android.graphics.Canvas cv, Bitmap src, int bw, float cH,
                                 float y, boolean flip, android.graphics.Paint p) {
        if (y > cv.getHeight() || y + cH < 0f) return;
        if (!flip) {
            cv.drawBitmap(src, null, new android.graphics.RectF(0, y, bw, y + cH), p);
            return;
        }
        cv.save();
        cv.translate(0, y + cH);
        cv.scale(1f, -1f);
        cv.drawBitmap(src, null, new android.graphics.RectF(0, 0, bw, cH), p);
        cv.restore();
    }

    /**
     * The artwork at full width and its own aspect, with the top and bottom `feather` rows faded
     * to transparent - in the pixels, not by masking a layer.
     *
     * Masking is what this replaces, and it put a one-pixel line along the band's top edge. The
     * band was drawn into a saveLayer whose bounds begin at a fractional `top`, and the mask
     * rect over it was snapped to whole pixels by a different rule than the layer was, so the
     * band's first row could fall outside the mask and land on the blurred background at full
     * strength - a line that follows the artwork's own brightness, bright where the row below is
     * bright and dark where it is dark, which is exactly what it looked like on the phone.
     *
     * Here the ramp is part of the picture, so that first row has nothing to show whatever the
     * rounding does. The mask rects are on a bitmap whose edges are 0 and bandH - both whole
     * pixels - so there is no fractional bound left for anything to disagree about.
     */
    private static Bitmap feathered(Bitmap src, int w, int bandH, int feather) {
        Bitmap band = Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(band);
        bc.drawBitmap(src, null, new android.graphics.RectF(0, 0, w, bandH),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        if (feather > 0 && feather * 2 <= bandH) {
            android.graphics.Paint mask = new android.graphics.Paint();
            mask.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN));
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, feather,
                    0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, 0, w, feather, mask);
            mask.setShader(new android.graphics.LinearGradient(0, bandH - feather, 0, bandH,
                    0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            bc.drawRect(0, bandH - feather, w, bandH, mask);
        }
        return band;
    }

    /**
     * Downscaling hard and letting one bilinear upscale smear it back is not a blur - it leaves
     * the tell-tale blocky diamonds of interpolating a tiny image. Halve step by step (each
     * halving is a box average), run a real separable box blur at the small size where it costs
     * almost nothing, then double back up, so nothing is ever interpolated across a big jump.
     */
    private static Bitmap blur(Bitmap src, int smallW, int radius, int passes) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 > smallW) {
            Bitmap next = Bitmap.createScaledBitmap(cur,
                    cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        int sh = Math.max(1, cur.getHeight() * smallW / cur.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(cur, smallW, sh, true);
        if (cur != src) cur.recycle();

        int n = smallW * sh;
        int[] a = new int[n], b = new int[n];
        small.getPixels(a, 0, smallW, 0, 0, smallW, sh);
        for (int i = 0; i < passes; i++) {
            boxH(a, b, smallW, sh, radius);
            boxV(b, a, smallW, sh, radius);
        }
        small.setPixels(a, 0, smallW, 0, 0, smallW, sh);

        // Climb back up in doublings; the caller's final draw stretches the last step.
        Bitmap up = small;
        while (up.getWidth() * 2 <= src.getWidth()) {
            Bitmap next = Bitmap.createScaledBitmap(up, up.getWidth() * 2, up.getHeight() * 2, true);
            if (up != small) up.recycle();
            up = next;
        }
        if (up != small) small.recycle();
        return up;
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[base + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[base + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[base + Math.min(w - 1, Math.max(0, x - r))];
                int in = src[base + Math.min(w - 1, Math.max(0, x + r + 1))];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int n = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int o = src[Math.min(h - 1, Math.max(0, y - r)) * w + x];
                int in = src[Math.min(h - 1, Math.max(0, y + r + 1)) * w + x];
                sr += ((in >> 16) & 0xff) - ((o >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((o >> 8) & 0xff);
                sb += (in & 0xff) - (o & 0xff);
            }
        }
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF(
                (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        cv.drawBitmap(src, null, dst, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Adds the cover into keyguard_background_layer, which sits behind the whole clock stack. */
    private static void attachCover() {
        final View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        v.post(new Runnable() {
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
                    Bitmap art = albumArt(ctx);
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
                        sCover = iv;
                    }
                    iv.setImageBitmap(art);
                    rebindSession();
                    Xp.log(TAG + "cover attached, layer "
                            + layer.getWidth() + "x" + layer.getHeight());
                } catch (Throwable t) {
                    Xp.log(TAG + "attachCover failed: " + Log.getStackTraceString(t));
                }
            }
        });
    }

    /**
     * Turns the automatic mode on or off. On, cover mode is a consequence of what is playing;
     * off, it stays exactly where a pushart broadcast left it.
     */
    private static synchronized void setAuto(boolean on) {
        sAuto = on;
        Xp.log(TAG + "auto mode " + (on ? "on" : "off"));
        saveState();
        if (!on) {
            main().removeCallbacks(sCardGone);
            return;
        }
        startSessionWatch();
        applyCardState();
    }

    /**
     * The card appeared, changed track, or went away. A track change arrives as a removal
     * followed by an add, so "gone" is only believed after CARD_GONE_MS - otherwise every skip
     * would tear the wallpaper down and put it straight back.
     */
    private static void onCardChanged(Object mediaData) {
        sCardKnown = true;
        boolean showing = mediaData != null;
        if (showing) {
            try {
                sCardToken = (android.media.session.MediaSession.Token)
                        Xp.getObjectField(mediaData, "token");
            } catch (Throwable t) {
                sCardToken = null;
            }
            sCardKey = cardKey(mediaData);
        } else {
            sCardToken = null;
            sCardKey = "";
        }
        Xp.log(TAG + "media card " + (showing ? "-> " + sCardKey : "gone"));
        main().removeCallbacks(sCardGone);
        if (showing) {
            main().removeCallbacks(sSessionsGone);
        } else if (!sAuto) {
            // Some MIUI builds update the media card before notifying
            // MediaSessionManager. Give the session list the same short grace period so a
            // genuine app close is still handled even when that listener is late or absent.
            main().postDelayed(sSessionsGone, CARD_GONE_MS);
        }
        if (!sAuto) {
            sCardShowing = showing;
            // Automatic mode is intentionally disabled by the gesture bridge, but an explicit
            // style-1/style-2 presentation must still follow the card's current track. The old
            // early return updated only this boolean, so the wallpaper stayed on the previous
            // artwork until a presentation toggle caused a fresh push.
            if (showing && (sCoverMode || sStyle2Mode)) {
                // A card callback is also the boundary at which MIUI has rebound its token. Force
                // the next metadata/artwork read through even when the textual key is unchanged.
                sTrackKey = "";
                rebindSession();
                onMediaUpdate();
            }
            return;
        }
        if (showing) {
            sCardShowing = true;
            applyCardState();
        } else {
            main().postDelayed(sCardGone, CARD_GONE_MS);
        }
    }

    private static final Runnable sCardGone = new Runnable() {
        @Override
        public void run() {
            sCardShowing = false;
            applyCardState();
        }
    };

    private static final Runnable sSessionsGone = new Runnable() {
        @Override
        public void run() {
            if (!sCoverMode && !sStyle2Mode) return;
            List<MediaController> sessions = activeSessions();
            if (sessions != null && sessions.isEmpty()) {
                // The card callback is not guaranteed when the owning app is force-closed.
                // Resetting through this funnel also sends the wallpaper-side "off" command
                // and returns the lyric presentation bridge to the stock media state.
                resetMusicPresentation("media sessions gone", true);
            }
        }
    };

    /** Cover mode is on exactly when the card is up. Idempotent, so it is safe to re-run. */
    private static void applyCardState() {
        // Nothing observed yet is not the same as nothing there: a SystemUI restart must not
        // tear down a cover that is on the phone just because the card hook has not fired for
        // the first time. That wait has to end somewhere though, and it did not - which left
        // the wallpaper on the last album for good. See releaseUnobservedCover().
        if (!sAuto) return;
        if (!sCardKnown) {
            releaseUnobservedCover(activeSessions());
            return;
        }
        if (!sCardShowing) {
            sTrackKey = "";
            // The card going away is what clears a tap-dismissed cover: that decision was about
            // this session, and the next thing the user plays starts from the cover again.
            sTapSuppressed = false;
            if (sCoverMode || sStyle2Mode) {
                Xp.log(TAG + "media card dismissed, leaving cover mode");
                if (sStyle2Mode) exitStyle2Mode();
                else setCoverEnabled(false, true);
            }
            return;
        }
        // The card is rebuilt around a track change, so this is where a fresh one is found -
        // and with the cover tapped away it is still where the tap listener is put back.
        if (sCoverMode || sStyle2Mode || wantsArtTap()) applyMediaCard();
        rebindSession();
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
    private static void releaseUnobservedCover(List<MediaController> sessions) {
        if (sCardKnown || (!sCoverMode && !sStyle2Mode) || sessions == null || !sessions.isEmpty()) return;
        Xp.log(TAG + "cover restored with no media session at all - leaving cover mode");
        sTrackKey = "";
        sTapSuppressed = false;
        setCoverEnabled(false, true);
    }

    /** The sessions the system holds right now, or null when the question could not be asked. */
    private static List<MediaController> activeSessions() {
        Context ctx = sAppCtx;
        if (ctx == null) return null;
        try {
            MediaSessionManager msm = sMsm != null ? sMsm
                    : (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return msm == null ? null : msm.getActiveSessions(null);
        } catch (Throwable t) {
            Xp.log(TAG + "active session read failed: " + t);
            return null;
        }
    }

    /** Track identity as the card itself sees it. */
    private static String cardKey(Object mediaData) {
        try {
            return Xp.getObjectField(mediaData, "packageName")
                    + "|" + Xp.getObjectField(mediaData, "song")
                    + "|" + Xp.getObjectField(mediaData, "artist");
        } catch (Throwable t) {
            return String.valueOf(mediaData);
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
                        if (controllers == null || controllers.isEmpty()) {
                            main().removeCallbacks(sSessionsGone);
                            main().postDelayed(sSessionsGone, SESSIONS_GONE_MS);
                        } else {
                            main().removeCallbacks(sSessionsGone);
                        }
                        // The one moment the list is handed over for free, and the only evidence
                        // there is while the card hook has still not fired in this process. The
                        // last session ending is what the card going away looks like from here.
                        releaseUnobservedCover(controllers);
                        rebindSession();
                    }
                };
                sMsm.addOnActiveSessionsChangedListener(sSessionsCb, null, main());
                Xp.log(TAG + "active-session listener registered");
            }
        } catch (Throwable t) {
            Xp.log(TAG + "session listener failed: " + Log.getStackTraceString(t));
        }
        rebindSession();
    }

    /**
     * Re-points the callback at the session the card is showing. The card hands us its own
     * MediaSession token, so this is exact - pickController()'s ranking is only the fallback for
     * when we are driven by something other than the card.
     */
    private static synchronized void rebindSession() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        MediaController c = controllerFromCard(ctx);
        if (c == null) c = pickController(ctx);
        boolean same = c != null && sWatched != null
                && c.getSessionToken().equals(sWatched.getSessionToken());
        if (!same) {
            if (sWatched != null && sMediaCb != null) {
                try {
                    sWatched.unregisterCallback(sMediaCb);
                } catch (Throwable ignored) {
                }
            }
            sWatched = c;
            sMediaCb = null;
            if (c != null) {
                sMediaCb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata md) {
                        if (sCoverWanted) attachCover();
                        onMediaUpdate();
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        LockLyrics.onPlaybackState(state);
                    }

                    @Override
                    public void onSessionDestroyed() {
                        rebindSession();
                    }
                };
                try {
                    c.registerCallback(sMediaCb, main());
                    Xp.log(TAG + "following " + c.getPackageName());
                } catch (Throwable t) {
                    Xp.log(TAG + "registerCallback failed: " + t);
                }
            } else {
                Xp.log(TAG + "no active media session");
            }
        }
        onMediaUpdate();
    }

    /** Identity of what is on screen, so a metadata storm pushes the same artwork only once. */
    private static String trackKey(MediaController c) {
        if (c == null) return "";
        MediaMetadata md = c.getMetadata();
        if (md == null) return c.getPackageName();
        return c.getPackageName() + "|" + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM);
    }

    /** The controller the card is bound to, or null if the card has not named one. */
    private static MediaController controllerFromCard(Context ctx) {
        android.media.session.MediaSession.Token t = sCardToken;
        if (t == null) return null;
        try {
            return new MediaController(ctx, t);
        } catch (Throwable e) {
            Xp.log(TAG + "card token unusable: " + e);
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
        // sAuto controls whether a media card may ENTER music lockscreen automatically. Once the
        // user explicitly selected style 1 or style 2, metadata changes must still refresh its
        // wallpaper while that presentation remains active.
        if ((!sAuto && !sCoverMode && !sStyle2Mode) || !sCardShowing) return;
        // The user tapped the cover away and the card is still up. The one case where "there is
        // a card" must not mean "put the cover back".
        if (sTapSuppressed) return;
        String key = sCardKey.isEmpty() ? trackKey(sWatched) : sCardKey;
        if ((sCoverMode || sStyle2Mode) && key.equals(sTrackKey)) return;
        sTrackKey = key;
        Xp.log(TAG + "card track: " + key);
        if (sCoverMode) LockLyrics.onTrack(key, sWatched);
        if (sCoverMode || sStyle2Mode) pushArtAsync(true, true);
        else setCoverEnabled(true, true);
    }

    /**
     * Cover mode is the wallpaper and the clock together, so both switches move as one. The
     * wallpaper is composed on the worker, the clock is taken on the main thread.
     */
    private static void setCoverEnabled(final boolean on, final boolean animate) {
        setCoverEnabled(on, animate, true);
    }

    /**
     * fresh says whether this is a track change. On one, artwork identical to what is already on
     * the wallpaper means the source has not caught up yet and is worth waiting for; on a manual
     * push it just means the same song, and waiting 1.7s to conclude that is pure delay.
     */
    private static void setCoverEnabled(final boolean on, final boolean animate,
                                        final boolean fresh) {
        pushArtAsync(on, on && fresh);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (on) enterCoverMode(animate && screenOn());
                else exitCoverMode(animate && screenOn());
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
    private static boolean keyguardShowing() {
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
    static boolean keyguardLocked() {
        return keyguardShowing();
    }

    static boolean clockContainerShown() {
        return onKeyguardNow();
    }

    static boolean coverModeOn() {
        return sCoverMode;
    }

    /**
     * Tells the wallpaper process to put the cover on the DESKTOP wallpaper, or take it off.
     *
     * The notification shade's glass samples what is behind its window, and the cover the shade
     * layer draws is inside it - so nothing drawn there can ever be what the cards blur. The lock
     * screen's cards show the album art because the art IS the wallpaper; this is the same answer
     * for the desktop, and it is why the ask is "swap the wallpaper" rather than "blur the
     * layer".
     *
     * Sent on change and then repeated while it is on, because the message that ends it is also
     * the only thing that stops it: the wallpaper side expires the swap on its own if it stops
     * hearing from here, and a lost "off" would otherwise leave the home screen showing an album
     * cover.
     */
    static void shadeArtToWallpaper(boolean on) {
        final Context ctx = sAppCtx;
        if (ctx == null) return;
        Intent out = wallpaperIntent("shadeart");
        out.putExtra("on", on);
        ctx.sendBroadcast(out);
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
     * Cover mode is leaving but has not finished.
     *
     * sCoverMode goes false at the START of the exit - the clock springs back and the wallpaper
     * fades out over ~665ms after it - so anything that tears down on `!sCoverMode` alone does it
     * a fifth of a second early. The module already pairs the two in its own guards; the shade
     * layer is one more place that has to.
     */
    static boolean releasing() {
        return ClockCollapse.exiting();
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
     * Feeds the lock screen's touch stream to a detector without taking part in it.
     *
     * GestureDetector needs the whole DOWN..UP sequence, which is why this hangs off the shade
     * window's dispatchTouchEvent rather than any one child: it is the single point every event
     * passes through before the OEM decides what to do with it. The detector's answer is
     * discarded - consuming a DOWN here would take swipe-to-unlock with it.
     *
     * What it reports is not acted on the moment it reports it: a tap on the cover is armed and
     * fires only once it can no longer be the first half of a double tap. See armLockTap.
     */
    private static void feedTap(MotionEvent ev) {
        if ((!sTapToggle && !sStyle2Mode) || ev == null) return;
        // A gesture the system took away is never going to produce the second tap.
        if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelPendingTap("gesture cancelled");
        }
        if (sTapDetector == null) {
            Context c = sAppCtx;
            if (c == null) return;
            // Constructed from inside the dispatch, so the looper it picks up is the one the
            // lock screen draws on, which is where the callback has to land.
            sTapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    armLockTap(e.getRawY());
                    return false;
                }

                /**
                 * Fired on the second DOWN, which is the whole point: two taps this close are
                 * the user asking the lock screen to sleep, not asking the cover to leave.
                 */
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    cancelPendingTap("second tap");
                    return false;
                }
            });
        }
        try {
            sTapDetector.onTouchEvent(ev);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Holds a tap on the cover back until it cannot be the first half of a double tap.
     *
     * This decides when the tap runs, not what it does: onLockTap re-reads the screen, the
     * keyguard and the card when it fires, so a screen that went off inside the window drops
     * the tap on its own - which is what covers a build whose double tap window is longer than
     * ours, and every double tap the framework itself did not recognise as one.
     */
    private static void armLockTap(final float y) {
        if (!sTapToggle && !sStyle2Mode) return;
        // Decided at the DOWN, because by the time this runs the centre may have closed under
        // the guard in onLockTap - see the dispatch hook for the full account.
        if (sGestureOnCentre) return;
        flushPendingTap();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sPendingTap = null;
                onLockTap(y);
            }
        };
        sPendingTap = r;
        main().postDelayed(r, TAP_CONFIRM_MS);
    }

    /**
     * Runs the tap that was waiting, because a new one has been armed on top of it.
     *
     * Reaching a second tap at all means the framework decided the first was not half of a
     * double tap - it cancels the waiting one on the second DOWN when it is. So the wait is
     * over: firing it here is what keeps "tap, pause, tap" meaning two toggles rather than the
     * second tap quietly replacing the first.
     */
    private static void flushPendingTap() {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        main().removeCallbacks(r);
        r.run();
    }

    /** A held-back tap that has stopped being one: log what ended it rather than guess later. */
    private static void cancelPendingTap(String why) {
        Runnable r = sPendingTap;
        if (r == null) return;
        sPendingTap = null;
        main().removeCallbacks(r);
        Xp.log(TAG + "held-back cover tap dropped (" + why + ")");
    }

    /**
     * A tap on the cover toggles cover mode.
     *
     * "On the cover" is everything between the status bar and the top of the media card. The
     * card and everything below it belongs to the OEM - the transport buttons, the notification
     * list, the two shortcuts in the corners - and a tap down there still means what it always
     * meant. The card rectangle is the one sampled for the app's preview, and the fallback is a
     * fraction of the screen rather than a pixel count because it stands in for a measurement
     * this device has simply not taken yet.
     */
    private static void onLockTap(float y) {
        if (!sTapToggle && !sStyle2Mode) return;
        if (!screenOn() || !keyguardShowing()) return;
        // Only the keyguard shows the clock container, so this is also what rules out the shade
        // being pulled down over an unlocked phone - the same test the card restyle uses.
        View c = sContainer;
        if (c == null || !c.isShown()) return;
        // The pad is up, so the taps on it are its own.
        if (bouncerUp()) return;
        // The control centre covers the same region without hiding the clock, so none of the
        // guards above see it: a tap aimed at a quick toggle must not toggle the cover.
        if (controlCenterUp()) return;
        if (sStyle2Mode) {
            float coverTop = sScreenH - sScreenW * STYLE2_COVER_ASPECT;
            // Leave the bottom controls and the retained lockscreen island untouched. The large
            // exposed part of the sharp cover above them remains the style-2 exit target.
            float coverBottom = sScreenH * 0.82f;
            if (y < Math.max(0f, coverTop) || y > coverBottom) return;
            Xp.log(TAG + "style 2 artwork tapped at y=" + y + ": restoring wallpaper");
            if (!notifyPresentation("MINI_PLAYER")) exitStyle2Mode();
            return;
        }
        // Nothing to toggle without music: the card is the switch, and this only chooses
        // whether the cover follows it.
        if (!sCardKnown || !sCardShowing) return;
        float top = sScreenH * 0.08f;
        // Measured live where possible. The sampled rectangle is a settled reading taken for
        // the app's preview and it lags: seen at 1360 while the card was actually at 1700,
        // which as a boundary would be 340px of cover that answers no tap at all.
        float bottom;
        View card = sCardGuarded;
        if (card != null && card.isShown() && card.getHeight() > 0) {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            bottom = loc[1];
        } else {
            bottom = sCardT > sScreenH / 3 ? sCardT : sScreenH * 0.62f;
        }
        if (y < top || y > bottom) return;
        if (sCoverMode) {
            exitFromTap("tap at y=" + y);
        } else if (sTapSuppressed) {
            enterFromTap("tap at y=" + y);
        }
    }

    static boolean screenOn() {
        refreshSysReads();
        return sSysInteractive;
    }

    /** Cached display state for per-frame consumers such as the lyric renderer. */
    static boolean screenOnCached() {
        return sScreenOn;
    }

    private static void detachCover() {
        // The session watch belongs to auto mode, not to the (legacy) in-SystemUI ImageView, so
        // dropping that view must not stop the module following the music.
        if (!sAuto && sWatched != null && sMediaCb != null) {
            try {
                sWatched.unregisterCallback(sMediaCb);
            } catch (Throwable ignored) {
            }
            sWatched = null;
            sMediaCb = null;
        }
        final ImageView iv = sCover;
        sCover = null;
        if (iv == null) return;
        iv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    releaseCoverGuard();
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    iv.setImageDrawable(null);
                    // Only the composed one is ours - albumArt() hands back a bitmap the media
                    // session owns, and recycling that would take the card's thumbnail with it.
                    Bitmap b = sCoverBitmap;
                    sCoverBitmap = null;
                    if (b != null) b.recycle();
                    // The live wallpaper was only hidden because this view was covering it.
                    // Whatever removed the view - an exit, a keyguard rebuild, a failure part
                    // way through - the wallpaper has to come back with it, or the lock screen
                    // is left blank. Off the lock screen that hand-back is deferred rather than
                    // dropped; setVideoSurfacesHidden() says why.
                    View bg = sVideoBg, fg = sVideoFg;
                    sVideoBg = null;
                    sVideoFg = null;
                    setVideoSurfacesHidden(false, bg, fg);
                    Xp.log(TAG + "cover detached"
                            + (bg == null && fg == null ? ""
                               : sVideoWpOwed ? ", live wallpaper owed back"
                               : ", live wallpaper restored"));
                } catch (Throwable t) {
                    Xp.log(TAG + "detachCover failed: " + t);
                }
            }
        });
    }

    /** The cover's colour, or 0 when there is nothing to take one from. */
    static int coverTint() {
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
     * Averages the strip of the composed cover the clock and its date are drawn on.
     *
     * Sampled from the composed bitmap rather than from the album art, because the two are not
     * the same picture where it matters: the sharp band starts below the clock at the default
     * bias, so what is actually behind the glyphs is the mirrored blur - and on a light cover
     * that blur is light. Costs 24 rows of getPixels on the worker that is already encoding the
     * same bitmap.
     */
    private static void measureCover(Bitmap full) {
        try {
            float d = sAppCtx.getResources().getDisplayMetrics().density;
            float topPx = sBandTopPx, botPx = sBandBotPx;
            if (Float.isNaN(topPx) || Float.isNaN(botPx) || botPx - topPx < 8f) {
                topPx = BAND_TOP_DP * d;
                botPx = BAND_BOT_DP * d;
            } else {
                topPx -= BAND_PAD_DP * d;
                botPx += BAND_PAD_DP * d;
            }
            int top = Math.max(0, Math.round(topPx));
            int bottom = Math.min(full.getHeight(), Math.round(botPx));
            if (bottom - top < 8) { sCoverTint = 0; return; }
            int stride = Math.max(1, (bottom - top) / 24);
            int[] row = new int[full.getWidth()];
            long sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int y = top; y < bottom; y += stride) {
                full.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int x = 0; x < row.length; x += 8) {
                    int c = row[x];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                    n++;
                }
            }
            if (n == 0) { sCoverTint = 0; return; }
            // The plain average of the band, which the mirror-blur has already smoothed: one
            // number describing the picture behind the glyphs rather than any one pixel of it.
            sCoverTint = 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8)
                    | (int) (sb / n);
            Xp.log(TAG + "cover tint #" + Integer.toHexString(sCoverTint) + " over " + n
                    + "px, band " + top + ".." + bottom);
        } catch (Throwable t) {
            sCoverTint = 0;
            Xp.log(TAG + "cover tint failed: " + t);
        }
    }

    /**
     * Asks the OEM for its clock colours again, so a cover that has just changed is judged
     * against the new one.
     *
     * Nothing else will. The colour pass runs when the wallpaper's palette changes, and swapping
     * the cover is not one - it happens behind the wallpaper's back. Without this a track change
     * would keep the previous cover's decision, which on the way from a light cover to a dark
     * one means a dark clock left dark on a dark picture: worse than the problem this exists to
     * fix. The values handed over are the OEM's own, off the style it is already holding; the
     * setter hooks are what make them come out legible.
     */
    private static void recolorClock() {
        final View v = sContainer;
        if (v == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    try {
                        if (!(root instanceof ViewGroup)) continue;
                        View c = ((ViewGroup) root).getChildAt(0);
                        if (c == null) continue;
                        if (glassGlyphs(root)) {
                            // The glass is driven from our own morph, and re-running one frame of
                            // it is what makes it re-colour - the same call applyGlassMorph()
                            // makes on every frame of a collapse, so nothing new is being asked
                            // of it. updateClockColor, which is the other path, is deliberately
                            // NOT called here: the OEM only reaches for it on a solid style, and
                            // it writes the glyphs' paint colour as well as their glass data.
                            if (!Float.isNaN(sAppliedGlassV)) {
                                Xp.callMethod(c, "updateGlassValue", sAppliedGlassV);
                            }
                        } else if (hasField(c, "mClockStyleInfo")) {
                            Object info = Xp.getObjectField(c, "mClockStyleInfo");
                            Xp.callMethod(c, "updateClockColor",
                                    Xp.callMethod(info, "getPrimaryColor"),
                                    Xp.callMethod(info, "getSecondaryColor"));
                        } else {
                            // Nothing to nudge on this style, and nothing to change either: its
                            // colours are the SystemUI palette business. This used to throw,
                            // one IllegalArgumentException per frame, which is how it was found.
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "recolor failed: " + t);
                    }
                }
                // The date is on neither of those paths - the glass never touches it, and the
                // palette is the only thing that colours it. Handing it back its own current
                // colour is enough: the setter hook is what turns that into the legible one.
                View date = visibleDate();
                if (date instanceof TextView) {
                    try {
                        Xp.callMethod(date, "setTextColor", ((TextView) date).getCurrentTextColor());
                    } catch (Throwable t) {
                        Xp.log(TAG + "date recolour failed: " + t);
                    }
                }
            }
        });
    }

    /** Whether a class declares this field at all - getObjectField throws when it does not. */
    private static boolean hasField(Object o, String name) {
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
     * Whether the clock this view belongs to is the liquid-glass one.
     *
     * The colour takeover is FOR the glass style and stays there. On every other style the clock
     * is coloured by the SystemUI palette and it is to stay that way: our substitution was
     * replacing a colour the OEM had already worked out with one derived from a single average
     * over the cover, and on a cover with a bright face under a dark border that average reads
     * "dark cover", so the clock comes out white on white. Passing the OEM value straight
     * through is both less machinery and a better answer.
     *
     * Read off the view itself first - the glyphs are TimeViews and carry the flag - and then by
     * walking up to whichever of the clock views answers.
     */
    private static boolean glassStyleFor(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (declaresGlass(p)) return true;
            if (p == sContainer) break;
        }
        // The date is not a TimeView and neither is anything above it, so the walk finds nothing
        // on the styles that need the date handled too - the glass one among them. Asked of the
        // tree instead: all_in_one is the family whose clock is built this way, and it is the
        // one that answers `time_group`. See anchoredStyle().
        return anchoredStyle();
    }

    /**
     * Whether this view is built on the glass TimeView - asked of its CLASS, not of the switch.
     *
     * The first version read `isMiGlassEffectEnable` and returned its VALUE, which is the
     * question "is the glass switched on right now". It is false once the morph has run all the
     * way to solid, which is exactly the state the clock is in when it is collapsed - so the
     * takeover switched itself off for the whole time it was needed and the clock kept the
     * palette's colour. Measured: two frames with the cover's luma forced to 0.95 and to 0.05
     * came out grey-white and grey-white.
     */
    private static boolean declaresGlass(View v) {
        Class<?> c = v.getClass();
        Boolean hit = sGlassClasses.get(c);
        if (hit != null) return hit;
        boolean glass = false;
        for (Class<?> k = c; k != null && !glass; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if ("setGlassColor".equals(m.getName())) { glass = true; break; }
            }
            if (!glass) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if ("isMiGlassEffectEnable".equals(f.getName())) { glass = true; break; }
                }
            }
        }
        sGlassClasses.put(c, glass);
        return glass;
    }

    /**
     * Counter-hook for HyperLight's "统一柔光玻璃" (its SoftGlassMaterialHook).
     *
     * HyperLight hooks {@code View.setMiGlass(float[])} and, for any view its classifier admits,
     * rewrites the whole 42-float MiGlass array to its soft-glass preset - indices 0..36, and
     * [34] and [36] in particular. Index 36 is the liquid-glass channel this module drives
     * through {@code updateGlassValue -> glassData[36]}; rewriting it is what turns the clock
     * from 液态玻璃 into plain glass ("非液态").
     *
     * Its classifier (gu1.o) admits a view whose setMiGlass happens with a
     * notification/shade/control-centre frame on the stack, which is exactly the media-card ->
     * cover-mode transition - which is why the clock comes up liquid after a restart and loses it
     * a moment after music starts, rather than immediately.
     *
     * The crucial asymmetry: HyperLight rewrites the COPY handed to setMiGlass, never the view's
     * own {@code glassData} field, so the field still holds the OEM's liquid values. Handing the
     * field back for the clock's own glyphs is the whole fix.
     *
     * Armed from the clock container's onAttachedToWindow rather than at onPackageLoaded - that
     * event reliably fires after both modules are loaded, so this hook registers AFTER HyperLight's
     * and runs last in the chain, where the last writer to args[0] is what the original receives.
     */
    private static void armMiGlassGuard() {
        if (sMiGlassGuardArmed) return;
        sMiGlassGuardArmed = true;
        try {
            Xp.hookAll(View.class, "setMiGlass", chain -> {
                Object self = chain.getThisObject();
                if (!(self instanceof View) || !declaresGlass((View) self)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                boolean restored = false;
                if (args.length >= 1 && args[0] instanceof float[]) {
                    try {
                        float[] oem = (float[]) Xp.getObjectField((View) self, "glassData");
                        if (oem != null && oem.length >= 42) {
                            args[0] = oem;
                            restored = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                int hits = ++sMiGlassGuardHits;
                // The first few fires prove which view carries the clock's glass and that the
                // field is there to hand back; the rest are counted rather than spammed.
                if (hits <= 5 || hits % 50 == 0) {
                    Xp.log(TAG + "setMiGlass guard fired (" + hits + ") on "
                            + viewIdOf((View) self) + " restored=" + restored);
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "HyperLight setMiGlass guard armed");
        } catch (Throwable t) {
            Xp.log(TAG + "HyperLight setMiGlass guard unavailable: " + t);
        }
    }

    /**
     * Asked once per class, because the question is asked constantly.
     *
     * getDeclaredMethod/getDeclaredField answer by THROWING when the member is absent, and the
     * stack trace each throw fills in costs far more than the lookup. This runs from inside the
     * colour setters, which the OEM calls for every glyph on every frame of a collapse - so the
     * first version put dozens of exceptions per frame on the clock's own animation thread and
     * the collapse stuttered. Measured symptom: the entry scaling "not normal", with every
     * number in the log still correct. The answer cannot change while the process lives.
     */
    private static final java.util.HashMap<Class<?>, Boolean> sGlassClasses = new java.util.HashMap<>();

    /** Whether these glyphs are drawn through the MiGlass shader, read off the views themselves. */
    private static boolean glassGlyphs(View root) {
        View v = sContainer;
        if (v == null) return false;
        for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
            int rid = v.getContext().getResources().getIdentifier(id, "id", "com.android.systemui");
            View t = rid == 0 ? null : root.findViewById(rid);
            if (t == null) continue;
            try {
                return Boolean.TRUE.equals(Xp.getObjectField(t, "isMiGlassEffectEnable"));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the clock style on screen right now has a glass channel to drive at all.
     *
     * The liquid-glass morph is AllInOneBase.updateGlassValue(float) - the OEM's own continuous
     * glass -> solid ramp - and it exists exactly where the glass shader does. A rhombus clock
     * draws vector digits, a doodle clock bitmaps, the oriental and magazine clocks plain text:
     * none of them has anything to drive, and a slider that pretends otherwise is a slider that
     * silently does nothing, which is worse than one that says so.
     *
     * Asked of the live class rather than written down as a list of style names, so a style that
     * gains the channel in a later build is picked up without a change here.
     */
    private static boolean clockHasGlass() {
        for (View root : clockRoots()) {
            View clock = clockViewOf(root);
            if (clock == null) continue;
            for (Class<?> k = clock.getClass(); k != null && k != Object.class;
                 k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("updateGlassValue", float.class);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    // --------------------------------------------- the OEM's own translation function

    /** Whether the walk below has been tried, so a build without it is asked once, not per frame. */
    private static boolean sTranslatorTried;
    private static Object sTranslator;
    private static java.lang.reflect.Method sComputeTranslationY;

    /**
     * The translation the OEM is putting on its clock views for a given notifY, from the OEM's
     * own function rather than from a reading of the screen.
     *
     * `ClockTranslationAnimator.computeTranslationY(float)` is what the OEM itself calls - it is
     * the line inside ClockBaseAnimation.notifStateChange that turns the y into the translation
     * its spring then applies. Being a function of y alone, it answers for the frame being placed
     * even though the view has not been written yet, which is exactly what the measurement cannot
     * do: Folme writes after this hook returns, so a measured translation is always one frame
     * old, and compensating with it displaces the date by the OEM's per-frame step.
     *
     * Reachable through fields the module already walks, and null - not throwing - when any of
     * them is missing, so a build that renames one falls back to measuring rather than breaking.
     */
    private static Float oemTranslationY(float y) {
        if (!sTranslatorTried) {
            sTranslatorTried = true;
            View v = sContainer;
            if (v != null) {
                try {
                    Object helper = Xp.getObjectField(v, "mAnimationHelper");
                    Object anim = Xp.getObjectField(helper, "mClockAnima");
                    Object tr = Xp.getObjectField(anim, "clockTranslationAnimator");
                    if (tr != null) {
                        java.lang.reflect.Method m = tr.getClass()
                                .getMethod("computeTranslationY", float.class);
                        m.setAccessible(true);
                        sTranslator = tr;
                        sComputeTranslationY = m;
                        Xp.log(TAG + "oem translation: " + tr.getClass().getName()
                                + ".computeTranslationY reached");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "oem translation unavailable, measuring instead: " + t);
                }
            }
        }
        java.lang.reflect.Method m = sComputeTranslationY;
        if (m == null) return null;
        try {
            return (Float) m.invoke(sTranslator, y);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reports the whole walk on demand - `--es op oemtrans [--ef y 1200]`.
     *
     * The one-shot log inside oemTranslationY fires on the first placement of a process, which is
     * exactly when logcat is least likely to be attached and, after a restart, a moment that may
     * never come at all. This re-runs it whenever it is asked, and names the step that fails.
     */
    private static void dumpOemTranslation(final float y) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "oemtrans: no container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                Object helper = null, anim = null, tr = null;
                try {
                    helper = Xp.getObjectField(v, "mAnimationHelper");
                    Xp.log(TAG + "oemtrans: mAnimationHelper = "
                            + (helper == null ? "null" : helper.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: mAnimationHelper FAILED: " + t);
                }
                try {
                    anim = helper == null ? null : Xp.getObjectField(helper, "mClockAnima");
                    Xp.log(TAG + "oemtrans: mClockAnima = "
                            + (anim == null ? "null" : anim.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: mClockAnima FAILED: " + t);
                }
                try {
                    tr = anim == null ? null : Xp.getObjectField(anim, "clockTranslationAnimator");
                    Xp.log(TAG + "oemtrans: clockTranslationAnimator = "
                            + (tr == null ? "null" : tr.getClass().getName()));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: clockTranslationAnimator FAILED: " + t);
                }
                if (tr == null) { Xp.log(TAG + "oemtrans: chain stops here"); return; }
                try {
                    java.lang.reflect.Method m = tr.getClass()
                            .getMethod("computeTranslationY", float.class);
                    m.setAccessible(true);
                    float oem = (Float) m.invoke(tr, y);
                    View d = visibleDate();
                    int[] loc = new int[2];
                    if (d != null) d.getLocationOnScreen(loc);
                    float layout = d == null ? 0f : layoutScreenY(d);
                    Xp.log(TAG + "oemtrans: computeTranslationY(" + y + ") = " + oem
                            + " | date layoutScreenY=" + r1(layout)
                            + " + oem = " + r1(layout + oem)
                            + " | measured onScreen=" + (d == null ? -1 : loc[1])
                            + " diff=" + (d == null ? Float.NaN : r1(layout + oem - loc[1])));
                } catch (Throwable t) {
                    Xp.log(TAG + "oemtrans: computeTranslationY FAILED: " + t);
                }
            }
        });
    }

    /** The view's screen y from the LAYOUT alone - no translation anywhere in the chain. */
    private static float layoutScreenY(View v) {
        float y = 0f;
        for (int guard = 0; guard < 32 && v != null; guard++) {
            y += v.getTop();
            android.view.ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return y;
    }

    /** Rides the same progress as the scale, so one OEM spring drives size and look together. */
    static void applyGlassMorph(float p) {
        if (Float.isNaN(sGlassV0)) return;
        // Not while the screen is off, and this is the one that matters for the AOD. The clock
        // keeps its hold through doze - the re-asserts below put the placement back on the doze
        // layout - but they drove this on the way, and cover mode's glass value is not the one
        // the AOD was inked with: the clock came up system-coloured and turned to the glass
        // tint a moment later, every time. The placement is ours; the colour is not.
        if (!sScreenOn) return;
        float g = sGlassV0 + p * (sGlassV1 - sGlassV0);
        if (!Float.isNaN(sAppliedGlassV) && Math.abs(g - sAppliedGlassV) < 0.004f) return;
        sAppliedGlassV = g;
        for (View root : clockRoots()) {
            try {
                View c = ((android.view.ViewGroup) root).getChildAt(0);
                if (c == null) continue;
                Xp.callMethod(c, "updateGlassValue", g);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Calls a method on the AllInOneHourClock / AllInOneMinuteClock views of both trees. */
    private static void callOnClockViews(final String method, final String kind, final float f,
                                         final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "callclock: need m="); return; }
        v.post(new Runnable() {
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
    private static void callOnTimeViews(final String method, final String kind, final float f,
                                        final int n, final boolean b) {
        final View v = sContainer;
        if (v == null || method == null) { Xp.log(TAG + "call: need m="); return; }
        v.post(new Runnable() {
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

    /** The MiGlass shader samples the wallpaper and does not follow a child View's scale. */
    private static void setGlass(final boolean on) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
            @Override
            public void run() {
                for (View root : clockRoots()) {
                    for (String id : new String[]{"hour_view", "minute_view", "colon_view"}) {
                        try {
                            int rid = v.getContext().getResources()
                                    .getIdentifier(id, "id", "com.android.systemui");
                            View t = rid == 0 ? null : root.findViewById(rid);
                            if (t == null) continue;
                            Xp.callMethod(t, "setMiGlassEffectEnable", on);
                            t.invalidate();
                        } catch (Throwable e) {
                            Xp.log(TAG + "setGlass " + id + " failed: " + e);
                        }
                    }
                }
                Xp.log(TAG + "glass=" + on);
            }
        });
    }

    private static void groupScale(final String idName, final float k, final float px,
                                   final float py, final float ty) {
        final View v = sContainer;
        if (v == null) { Xp.log(TAG + "no clock container"); return; }
        v.post(new Runnable() {
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
                    Xp.log(TAG + "groupScale failed: " + Log.getStackTraceString(e));
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
            int resId = resId(root.getResources(), id);
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
    private static void forgetClockRoots() {
        sRootsCache = null;
        sRootsFor = null;
    }

    private static String viewIdOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return String.valueOf(v.getId());
        }
    }

    private static void dumpViewTree(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        try {
            String name = v.getResources().getResourceEntryName(v.getId());
            sb.append(" #").append(name);
        } catch (Throwable ignored) {
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append(' ').append(loc[0]).append(',').append(loc[1])
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(v.getVisibility());
        if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
            sb.append(" scale=").append(v.getScaleX()).append('/').append(v.getScaleY());
        }
        // translationX was missing here and cost an afternoon: album_art reported an x that
        // overlapped header_title, which is impossible from layout alone. getLocationInWindow
        // maps (0,0) through the view's OWN matrix before adding mLeft, so a translation or a
        // mirror moves the reported origin without moving the view's slot.
        // The layout slot, which is what the user actually touches. It differs from the
        // reported origin above whenever the view carries a transform of its own.
        sb.append(" L=").append(v.getLeft()).append(',').append(v.getTop());
        if (v.getRotationX() != 0f) sb.append(" rotX=").append(v.getRotationX());
        if (v.getRotationY() != 0f) sb.append(" rotY=").append(v.getRotationY());
        if (v.getRotation() != 0f) sb.append(" rot=").append(v.getRotation());
        if (v.getTranslationX() != 0f) sb.append(" tx=").append(v.getTranslationX());
        if (v.getTranslationY() != 0f) sb.append(" ty=").append(v.getTranslationY());
        if (v.getPivotX() != v.getWidth() / 2f) sb.append(" pivotX=").append(v.getPivotX());
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            sb.append(" textSize=").append(t.getTextSize())
              .append(" fontVar=").append(t.getFontVariationSettings())
              .append(" text=\"").append(t.getText()).append('"');
        }
        Xp.log(TAG + sb);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    private static void dumpInfo() {
        View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Xp.log(TAG + "container onScreen=[" + loc[0] + "," + loc[1] + "] size="
                + v.getWidth() + "x" + v.getHeight());
        try {
            Xp.log(TAG + "getClockBottom()=" + Xp.callMethod(v, "getClockBottom"));
        } catch (Throwable t) {
            Xp.log(TAG + "getClockBottom failed: " + t);
        }
        try {
            Xp.log(TAG + "getNotificationClockTop()="
                    + Xp.callMethod(v, "getNotificationClockTop"));
        } catch (Throwable t) {
            Xp.log(TAG + "getNotificationClockTop failed: " + t);
        }
        logViewById(v, "mi_media_controls");
        logViewById(v, "album_art_image");
    }

    /**
     * Reads the OEM's own miuix AnimConfig objects off the live clock animation, so we can
     * copy the real curve instead of guessing a duration.
     * KeyguardClockContainer.mAnimationHelper.mClockAnima -> AllInOneClockAnimation
     */
    private static void dumpAnimConfigs() {
        View v = sContainer;
        if (v == null) {
            Xp.log(TAG + "no clock container");
            return;
        }
        try {
            Object helper = Xp.getObjectField(v, "mAnimationHelper");
            Object anim = Xp.getObjectField(helper, "mClockAnima");
            Xp.log(TAG + "clock animation impl = " + anim.getClass().getName());
            for (String f : new String[]{"stateChangedAnimConfig", "notifsChangedAnimConfig",
                    "defaultAnimConfig", "animRunningConfig"}) {
                try {
                    Object cfg = Xp.getObjectField(anim, f);
                    Xp.log(TAG + f + " = " + describe(cfg));
                } catch (Throwable t) {
                    Xp.log(TAG + f + " unavailable: " + t);
                }
            }
        } catch (Throwable t) {
            Xp.log(TAG + "dumpAnimConfigs failed: " + Log.getStackTraceString(t));
        }
    }

    /** Generic reflective field dump, one level deep, for opaque OEM value objects. */
    private static String describe(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append('{');
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                Object val = f.get(o);
                if (val != null && val.getClass().isArray()) {
                    StringBuilder arr = new StringBuilder("[");
                    int n = java.lang.reflect.Array.getLength(val);
                    for (int k = 0; k < n; k++) {
                        if (k > 0) arr.append(',');
                        arr.append(java.lang.reflect.Array.get(val, k));
                    }
                    val = arr.append(']').toString();
                } else if (val != null && val.getClass().getName().startsWith("miuix.")) {
                    val = val.getClass().getSimpleName() + describeShallow(val);
                }
                sb.append(f.getName()).append('=').append(val).append(' ');
            } catch (Throwable ignored) {
            }
        }
        return sb.append('}').toString();
    }

    /** Walks the whole class hierarchy - miuix ease styles keep their params in a super. */
    private static String describeShallow(Object o) {
        StringBuilder sb = new StringBuilder("{");
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            sb.append('<').append(c.getSimpleName()).append("> ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(o);
                    if (val != null && val.getClass().isArray()) {
                        StringBuilder arr = new StringBuilder("[");
                        int n = java.lang.reflect.Array.getLength(val);
                        for (int k = 0; k < n; k++) {
                            if (k > 0) arr.append(',');
                            arr.append(java.lang.reflect.Array.get(val, k));
                        }
                        val = arr.append(']').toString();
                    }
                    sb.append(f.getName()).append('=').append(val).append(' ');
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.append('}').toString();
    }

    private static void logViewById(View anchor, String idName) {
        try {
            Context c = anchor.getContext();
            int id = c.getResources().getIdentifier(idName, "id", "com.android.systemui");
            if (id == 0) {
                Xp.log(TAG + idName + ": id not found");
                return;
            }
            View found = anchor.getRootView().findViewById(id);
            if (found == null) {
                Xp.log(TAG + idName + ": view not present");
                return;
            }
            int[] loc = new int[2];
            found.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + found.getWidth(), loc[1] + found.getHeight());
            Xp.log(TAG + idName + ": " + r + " vis=" + found.getVisibility());
        } catch (Throwable t) {
            Xp.log(TAG + idName + " lookup failed: " + t);
        }
    }
}
