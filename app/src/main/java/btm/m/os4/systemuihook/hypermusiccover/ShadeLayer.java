package btm.m.os4.systemuihook.hypermusiccover;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The notification shade's cover layer: a full-screen ImageView behind the notification panel
 * that a pull-down reveals from the top down.
 *
 * This is a port of the ShadeIOSBlur module's controller. That module put the LOCK SCREEN
 * WALLPAPER there; here the picture is the module's own composed album cover, which is the same
 * bitmap that went to the wallpaper process - so the shade and the lock screen agree pixel for
 * pixel and pulling down is seamless.
 *
 * Two views, and the split is load bearing:
 *
 * - {@link #sFrame} is a FrameLayout carrying the alpha and the clip. It is what gets revealed.
 * - {@link #sWp} is the ImageView holding the picture. It is translated, not clipped.
 *
 * **Neither may ever be made clickable, focusable, or given a touch listener.** They sit under
 * the notification panel, and anything that takes an ACTION_DOWN there eats the swipe-to-unlock
 * and the notification scroll with it. The layer is drawn, never touched.
 *
 * Placement is the one thing that has to be right or the whole feature is invisible: on OS4 the
 * shade root has six children and the cover must go immediately below {@code NotificationPanelView}
 * (index 5), because indices 0-4 are MIUI's own scrims and background, which paint over anything
 * put there. That index is read from the tree, never hard-coded - see {@link #ensurePlacement}.
 */
final class ShadeLayer {

    private ShadeLayer() {
    }

    private static final String TAG = "[MCShade] ";

    private static final android.os.Handler UI =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** The anchor. Resolved by name because it is what we insert in front of. */
    private static final String CLS_PANEL = "com.android.systemui.shade.NotificationPanelView";

    /** The shade window root, a NotificationShadeWindowView. */
    private static volatile ViewGroup sRoot;
    private static volatile FrameLayout sFrame;
    private static volatile ImageView sWp;
    private static volatile Class<?> sPanelCls;

    /** Where {@link #sFrame} last landed, for the diagnostic line. */
    private static volatile int sPlacedAt = -1;
    /** The "no panel in the tree" warning is worth saying once, not every pull-down. */
    private static volatile boolean sWarnedPlacement;

    /** Phase 1a only: paint the layer flat so the placement can be judged by eye. */
    private static volatile boolean sProbe;

    /** The composed cover, owned by Main.setShadeArt() - never recycled here. */
    private static volatile android.graphics.Bitmap sArt;

    /**
     * 0 cover-only, 1 always with a wallpaper fallback, 2 always, feel only.
     *
     * Ships as 0: the feature changes what a pull-down looks like while music is playing, and
     * that should be a choice rather than something an update does to everyone.
     */
    private static volatile int sMode = 0;
    /** Whether the shade is currently ours. Read by the setBlurRatio hook, every frame. */
    private static volatile boolean sEffectOn;

    /**
     * Below this fraction nothing is drawn at all. Flyme's number, and the reason a pull-down
     * that barely moved shows no cover: the first 4.5% is dead.
     */
    private static volatile float sDeadZone = 0.045f;
    /** The fraction at which the cover is fully opaque; it ramps linearly in between. */
    private static volatile float sAlphaEnd = 0.70f;
    /**
     * How far above its resting place the cover starts, as a fraction of the screen.
     *
     * This is the "high initial state" that makes the cover read as sinking into place rather
     * than being wiped on. It is the cover that moves, not the clip - the edge is a hard line
     * and stays where the finger is.
     */
    private static volatile float sWpRise = 0.12f;

    /**
     * The picture's own blur, in pixels, while the cover is still mostly shut.
     *
     * Separate from the window blur above and not a duplicate of it: that one is MIUI's
     * SurfaceFlinger pass over whatever is behind the window, this one is a RenderEffect on our
     * own ImageView. The reference sharpens its picture as the panel opens - soft at the start,
     * crisp by the time the shade is fully down - and without it the cover arrives already
     * finished, which reads as a flat paste rather than something being revealed.
     */
    private static volatile float sWpBlur = 80f;
    /** The fraction past which the picture sharpens; at 1.0 it would stay blurred throughout. */
    private static volatile float sWpSharpStart = 0.70f;
    /** The radius currently applied, so the effect is rebuilt only when it actually changes. */
    private static volatile int sLastWpBlur = -1;

    /**
     * The fraction of the pull at which the blur target reaches full.
     *
     * Low on purpose, and it is the source of "the blur is out almost immediately but still
     * behind the finger": the target saturates early, the spring does the rest.
     */
    private static volatile float sBlurSat = 0.65f;
    /** Blur spring. Soft on purpose: this is the half that trails the finger. */
    private static volatile float sStiffness = 100f;
    private static volatile float sDamping = 0.9f;
    /**
     * Curtain spring. Stiff and critically damped: the edge tracks the hand rather than
     * bouncing past it.
     */
    private static volatile float sCurtainStiffness = 200f;
    private static volatile float sCurtainDamping = 1.0f;

    private static ShadeSpring sBlurSpring;
    private static ShadeSpring sCurtainSpring;
    /** The blur spring's current value, for the trace and the diagnostics. */
    private static volatile float sRatio;
    /** Steps the pushed ratio is quantised to. See onSpringUpdate. */
    private static final float PUSH_STEPS = 24f;
    /** The last value actually pushed, so an unchanged step is not pushed again. */
    private static volatile float sPushed = -1f;

    // ------------------------------------------------------------------ entry points

    /**
     * The shade window exists. Called from the constructor hook, so this runs before anything
     * has been laid out; the layer is built but deliberately NOT added to the tree yet - a
     * shade that is never pulled must cost nothing at all.
     */
    static void onWindowRoot(Object view) {
        if (!(view instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) view;
        sRoot = root;
        sPlacedAt = -1;
        root.post(new Runnable() {
            @Override
            public void run() {
                inject(root);
            }
        });
    }

    private static void inject(ViewGroup root) {
        if (sFrame != null) return;
        try {
            final Context ctx = root.getContext();
            final FrameLayout frame = new FrameLayout(ctx);
            frame.setClipChildren(true);
            // The curtain is a SHAPE, not a rectangle: see CURTAIN_OUTLINE.
            frame.setClipToOutline(true);
            frame.setOutlineProvider(CURTAIN_OUTLINE);
            frame.setAlpha(0f);
            // GONE rather than INVISIBLE: no measure, no layout, no draw, and a view that is
            // never touched still costs a traversal if it is merely invisible.
            frame.setVisibility(View.GONE);

            final ImageView wp = new ImageView(ctx);
            wp.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(wp, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            sFrame = frame;
            sWp = wp;
            // The blur providers are built during startup and the shade window may come after
            // them, so the identity rule gets a second chance now that there is something to
            // compare against.
            ShadeBlur.onShadeRootAvailable();
            // The shade window outlives a track change but is rebuilt on a SystemUI restart, so
            // by the time this runs there may already be a picture waiting.
            syncShown();
            Xp.log(TAG + "cover layer built for " + root.getClass().getName()
                    + " (" + root.getChildCount() + " children)");
        } catch (Throwable t) {
            Xp.log(TAG + "cover layer could not be built: " + t);
        }
    }

    /**
     * Puts the layer immediately below the notification panel, re-reading the tree every time.
     *
     * Re-run rather than done once on purpose: the shade's hierarchy is rebuilt across keyguard
     * transitions, so an index remembered from a previous activation can be stale. It is cheap -
     * a child scan that almost always ends in "already correct".
     */
    private static void ensurePlacement() {
        final ViewGroup root = sRoot;
        final FrameLayout frame = sFrame;
        if (root == null || frame == null) return;

        final Class<?> pc = panelClass(root);
        if (pc != null && frame.getParent() == root) {
            final int fi = root.indexOfChild(frame);
            // The invariant is "the child immediately AFTER us is the panel", not "we are at
            // the panel's index". We sit one slot before it, so comparing our own index with
            // the panel's is off by one - and was, which re-placed the layer on every single
            // pull-down: a remove, an add and a layout pass per activation, with a log line
            // that made it look like first-time placement.
            if (fi >= 0 && fi + 1 < root.getChildCount()
                    && pc.isInstance(root.getChildAt(fi + 1))) {
                sPlacedAt = fi;
                return;
            }
        }

        try {
            if (frame.getParent() instanceof ViewGroup) {
                ((ViewGroup) frame.getParent()).removeView(frame);
            }
            // Re-read AFTER the detach: while we are a child of the same parent the panel's
            // index is one higher than the slot we want.
            final int idx = pc == null ? -1 : indexOf(root, pc);
            if (idx < 0) {
                if (!sWarnedPlacement) {
                    sWarnedPlacement = true;
                    Xp.log(TAG + "no " + CLS_PANEL + " under the shade root - the cover layer "
                            + "falls back to index 0, where MIUI's own scrims paint over it. "
                            + "The expected symptom is that pulling the shade shows no cover "
                            + "at all, at every mode.");
                }
            }
            final int slot = idx < 0 ? 0 : Math.min(idx, root.getChildCount());
            root.addView(frame, slot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sPlacedAt = slot;
            Xp.log(TAG + "cover layer at index " + slot + " of " + root.getChildCount()
                    + " children, " + (idx < 0 ? "NOT anchored to the panel" : "below the panel"));
        } catch (Throwable t) {
            Xp.log(TAG + "cover layer could not be placed: " + t);
        }
    }

    private static Class<?> panelClass(ViewGroup root) {
        Class<?> c = sPanelCls;
        if (c != null) return c;
        try {
            c = Xp.findClass(CLS_PANEL, root.getClass().getClassLoader());
            sPanelCls = c;
            return c;
        } catch (Throwable t) {
            Xp.log(TAG + CLS_PANEL + " not found: " + t);
            return null;
        }
    }

    /** The panel's slot. Matched by type, not by name - a subclass is still the anchor. */
    private static int indexOf(ViewGroup root, Class<?> cls) {
        for (int i = 0; i < root.getChildCount(); i++) {
            if (cls.isInstance(root.getChildAt(i))) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the per-frame driver

    /** The last bucket traced, so a pull-down costs ~20 lines instead of one per frame. */
    private static volatile int sTraceBucket = -1;
    private static volatile long sFrames;
    private static volatile boolean sDriverAlive;

    /**
     * The panel's expansion, 0 shut and 1 wide open, once per frame. Everything the layer draws
     * is a function of this one number.
     *
     * Called from the FloatFlowProperty hook, which fires for every Folme property in SystemUI,
     * so the filter is on the caller - see Main's install.
     */
    static void onExpansion(float f) {
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        sFrames++;
        // Kept for the touch handler, which has to read it at the instant a finger lifts - by
        // which time the next frame may never arrive.
        sExpansion = f;

        if (!sDriverAlive) {
            sDriverAlive = true;
            Xp.log(TAG + "expansion driver is live - first frame f=" + f);
        }

        final long t0 = android.os.SystemClock.elapsedRealtimeNanos();
        applyProgress(f);
        noteFrame(android.os.SystemClock.elapsedRealtimeNanos() - t0);

        if (!Main.verbose()) {
            // The release window closes itself even when nothing is watching, so turning the
            // trace on mid-session does not pick up a stale count.
            sTraceRelease = 0;
            return;
        }
        final int left = sTraceRelease;
        if (left > 0) sTraceRelease = left - 1;
        // Every frame while anything is moving, not just one per 5% of travel. The bucket trace
        // is right for watching a whole pull-down and useless for finding a twitch: a jump is by
        // definition a change between two frames a few milliseconds apart, and sampling at five
        // percent cannot tell one from a gesture that simply moved that far.
        final boolean dense = left > 0 || sTouching
                || (sCurtainSpring != null && sCurtainSpring.running());
        final int bucket = (int) (f * 20f);
        if (!dense && bucket == sTraceBucket) return;
        // A fresh pull-down starts at 0, so the trace restarts with it rather than printing
        // only the buckets the last drag did not reach.
        if (f <= 0f) sTraceBucket = -1;
        sTraceBucket = bucket;
        // "exp" is the panel's own expansion; "cur" is where the edge actually is. They should
        // agree when nothing is touching the screen, and diverge by however far the panel
        // outruns the finger while one is. "REL" marks the frames right after a finger left.
        Xp.log(TAG + (left > 0 ? "REL " : "") + "exp=" + f + " frames=" + sFrames
                + " " + describe());
    }

    /**
     * Whether the shade's feel belongs to us right now - the zoom removal and the sprung blur,
     * independent of whether there is a picture to reveal.
     *
     * The keyguard test is the one that matters and it is deliberately the FIRST thing checked.
     * Pulling down on the lock screen opens the control centre, which shows the same shade
     * window, so without this the cover layer would take over the lock screen - which is the one
     * thing this feature was explicitly asked not to do.
     *
     * `keyguardLocked()` and NOT `sContainer.isShown()`: the module's own notes record that the
     * clock container reads VISIBLE with the phone unlocked and an app in the foreground, which
     * is precisely the state a shade pull-down over the desktop is in.
     */
    private static boolean effectActive() {
        if (sMode < 0 || sMode > 2) return false;
        if (Main.keyguardLocked()) return false;
        // The album cover is the lockscreen wallpaper, never an unlocked notification-centre
        // background. The imported shade experiment normally reveals sArt in mode 0; keeping
        // it out here prevents an active music session from replacing the desktop's shade
        // wallpaper while preserving the lockscreen cover and its native glass sampling.
        if (Main.coverModeOn() || Main.releasing()) return false;
        if (sMode == 0) return false;
        return true;
    }

    /**
     * The picture to reveal, or null for none.
     *
     * Separate from {@link #effectActive} on purpose: a pull-down that has no picture should
     * still kill the zoom and spring the blur, it should just reveal nothing. Tying the two
     * together would also mean a null bitmap silently turned the whole feature off.
     */
    private static android.graphics.Bitmap background() {
        if (Main.coverModeOn() || Main.releasing()) return sArt;
        // Modes 1 and 2 differ here and only here.
        if (sMode != 1) return null;
        if (sWallpaper == null) {
            // Asked for on demand rather than up front: a user on mode 0 or 2 never pays for a
            // second full-screen bitmap, and the load is off the main thread either way.
            Main.requestShadeWallpaper();
        }
        return sWallpaper;
    }

    /** The home wallpaper, owned by Main.setShadeWallpaper(). Never recycled here. */
    private static volatile android.graphics.Bitmap sWallpaper;
    /** What is on the view right now, so a swap happens once and not every frame. */
    private static volatile android.graphics.Bitmap sShown;

    /** Called from Main's worker; the view swap is posted. */
    static void setWallpaper(android.graphics.Bitmap b) {
        sWallpaper = b;
        UI.post(sSyncShown);
    }

    private static final Runnable sSyncShown = new Runnable() {
        @Override
        public void run() {
            syncShown();
        }
    };

    /**
     * Puts whichever picture is current on the view.
     *
     * One path for both sources, so a track change and a wallpaper change cannot disagree about
     * what is on screen. Runs on the main thread, which is where the view may be touched.
     */
    private static void syncShown() {
        final ImageView wp = sWp;
        if (wp == null) return;
        final android.graphics.Bitmap want = background();
        if (want != null && want.isRecycled()) return;
        if (want == sShown) return;
        sShown = want;
        wp.setBackgroundColor(0);
        wp.setImageBitmap(want);
        // A new picture needs its blur built again - the effect belongs to the drawable, not to
        // the view. Without this a track change mid-pull would swap in a sharp cover.
        sLastWpBlur = -1;
    }

    private static volatile View sPanel;

    /**
     * The notification panel itself - the anchor we insert in front of, and the view the content
     * gate writes alpha and scale to.
     *
     * Cached, but re-checked against the tree: the shade's hierarchy is rebuilt across keyguard
     * transitions, and a stale panel view is one that takes writes nobody sees.
     */
    /**
     * Whether this view sits under the notification panel right now.
     *
     * The same question the card-blur scope tried to ask and could not answer at write time -
     * see Main.shadeRow(). Asked from the diagnostics instead of from the write, it says whether
     * a rejected view was rejected because its ancestry genuinely ends elsewhere or merely
     * because the tree had not been assembled around it yet.
     */
    static boolean insidePanel(View v) {
        final ViewGroup root = sRoot;
        if (root == null) return false;
        try {
            final Class<?> pc = panelClass(root);
            if (pc == null) return false;
            for (View c = v; c != null; ) {
                if (pc.isInstance(c)) return true;
                final android.view.ViewParent p = c.getParent();
                c = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static View panelView() {
        final View cached = sPanel;
        if (cached != null && cached.getParent() == sRoot) return cached;
        final ViewGroup root = sRoot;
        if (root == null) return null;
        final Class<?> pc = panelClass(root);
        if (pc == null) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            final View c = root.getChildAt(i);
            if (pc.isInstance(c)) {
                sPanel = c;
                return c;
            }
        }
        return null;
    }

    /** Which behaviour is selected: 0 cover-only, 1 always with a wallpaper fallback, 2 feel. */
    static void setMode(int mode) {
        sMode = mode;
        // Mode 1 is the only one that ever wants a wallpaper, so asking here is what keeps the
        // second full-screen bitmap off the heap for everyone on 0 or 2.
        if (mode == 1) Main.requestShadeWallpaper();
        Xp.log(TAG + "shade mode = " + mode);
    }

    static int mode() {
        return sMode;
    }

    // ------------------------------------------------------------------ settings

    /** The content gate - the panel's own alpha and scale. Off by default; see ShadeGate. */
    private static volatile boolean sGateOn;
    /** Whether notification rows blur the layer instead of the app behind it. Off by default. */
    private static volatile boolean sCardBlurOn;
    private static volatile int sCardBlurRadius = 100;
    /**
     * How far the shade's own content is pushed down out of the cover's way, in pixels.
     *
     * Off by default and for the same reason as {@link #sMode}: it changes what a pull-down looks
     * like, and that should be a choice rather than something an update does to everyone. See
     * Main.setShadeContentShift for what it moves and why the write is safe where it is made.
     */
    private static volatile int sContentPush = 0;
    /**
     * Whether the curtain takes its position from the finger rather than from the panel.
     *
     * **Off, and it has to be.** Measuring this device settled it: `expansion` is not a linear
     * map of how far the hand has travelled. Over one pull the finger reached 8.7% of the screen
     * while the panel reached 85.5% - a gain of roughly ten, and the ratio is not constant, so
     * the two are simply different quantities.
     *
     * While they are both on screen that reads as the cover being wrong (the panel is nearly
     * open and the picture has barely moved), and the moment the finger lifts there is a gap to
     * close. Both ways of closing it are visible: springing toward the panel's value slides the
     * edge down and back - measured, `cur` 0.087 to 0.321 and back over 280ms, with the cover
     * brightening sixfold on the way - and snapping would be the same movement in a single
     * frame. The reference module does not have this problem because on the build it was written
     * against the panel tracked the finger; that is not true here.
     *
     * So the edge follows the panel, always. The panel is what the user is looking at and it is
     * the system's own motion, which means the cover is never out of step with it and there is
     * no handoff to get wrong. The cost is that the edge travels at the panel's speed rather
     * than the hand's - which is the same speed the notifications behind it are travelling at,
     * so it reads as one movement rather than as a lag.
     *
     * Kept as a switch because it is the one place the two designs differ, and it costs a line.
     */
    private static volatile boolean sFollowTouch = true;

    /**
     * How far the edge may lag the panel while a finger is down.
     *
     * 0 follows the panel exactly; 1000 is an unlimited lag, which is the pure finger-following
     * this started as and which leaves the cover a sliver over an open shade.
     */
    private static volatile float sMaxLag = 0.15f;

    /**
     * Every knob the settings page can move, by name.
     *
     * One entry point rather than a broadcast op per setting: there are a dozen of them, they are
     * all an int with a range, and a dozen near-identical ops would be a dozen places for the UI
     * and the module to drift apart.
     *
     * Ranges are clamped here and not in the UI. The UI is not the only caller - adb is, and a
     * value typed at a shell must not be able to drive the spring to a stiffness that makes the
     * integrator unstable or an alpha range with no room in it.
     */
    static void configure(String key, int v) {
        if (key == null) return;
        if ("mode".equals(key)) setMode(clampInt(v, 0, 2));
        else if ("gate".equals(key)) sGateOn = v != 0;
        else if ("cardblur".equals(key)) sCardBlurOn = v != 0;
        else if ("cardradius".equals(key)) sCardBlurRadius = clampInt(v, 0, 200);
        else if ("contentpush".equals(key)) {
            sContentPush = clampInt(v, 0, 1800);
            // Re-applied against the LIVE state so a slider moves the content without a second
            // pull-down. `sEffectOn` is the curtain's own answer to "is the cover up here", which
            // is exactly the condition the write is scoped by.
            Main.setShadeContentShift(sEffectOn && sContentPush > 0);
        }
        else if ("clockpush".equals(key)) {
            ShadeHeader.setClock(clampInt(v, -600, 600));
            // Re-armed against the live state so a slider moves the header without a second
            // pull-down. ShadeHeader's own arm() strips the previous offset before applying the
            // new one, so dragging the slider cannot accumulate.
            ShadeHeader.arm(sEffectOn);
        } else if ("carrierpush".equals(key)) {
            ShadeHeader.setCarrier(clampInt(v, -600, 600));
            ShadeHeader.arm(sEffectOn);
        } else if ("shadebias".equals(key)) Main.setShadeBias(clampInt(v, -1000, 1000));
        else if ("touch".equals(key)) sFollowTouch = v != 0;
        else if ("maxlag".equals(key)) sMaxLag = clampInt(v, 0, 1000) / 1000f;
        else if ("stiffness".equals(key)) {
            sStiffness = clampInt(v, 20, 400);
            retune();
        } else if ("damping".equals(key)) {
            sDamping = clampInt(v, 30, 150) / 100f;
            retune();
        } else if ("curtainstiffness".equals(key)) {
            sCurtainStiffness = clampInt(v, 20, 600);
            retune();
        } else if ("curtaindamping".equals(key)) {
            sCurtainDamping = clampInt(v, 30, 150) / 100f;
            retune();
        } else if ("blursat".equals(key)) sBlurSat = clampInt(v, 100, 1000) / 1000f;
        else if ("deadzone".equals(key)) {
            sDeadZone = clampInt(v, 0, 200) / 1000f;
            keepAlphaRoom();
        } else if ("alphaend".equals(key)) {
            sAlphaEnd = clampInt(v, 100, 1000) / 1000f;
            keepAlphaRoom();
        } else if ("wprise".equals(key)) sWpRise = clampInt(v, 0, 400) / 1000f;
        else if ("wpblur".equals(key)) {
            sWpBlur = clampInt(v, 0, 200);
            sLastWpBlur = -1;
        } else if ("sharpstart".equals(key)) {
            sWpSharpStart = clampInt(v, 100, 999) / 1000f;
            sLastWpBlur = -1;
        } else {
            Xp.log(TAG + "unknown shade setting '" + key + "'");
        }
    }

    /**
     * The alpha ramp needs somewhere to happen. Dead zone and end are two independent settings
     * that would otherwise be able to land on each other, and a ramp with no room is a cover that
     * pops rather than fades.
     */
    private static void keepAlphaRoom() {
        if (sAlphaEnd <= sDeadZone) sAlphaEnd = sDeadZone + 0.001f;
    }

    /** Applied to the live spring, so a slider moves the feel without a restart. */
    private static void retune() {
        final ShadeSpring blur = sBlurSpring;
        if (blur != null) {
            blur.stiffness = sStiffness;
            blur.dampingRatio = sDamping;
        }
        final ShadeSpring curtain = sCurtainSpring;
        if (curtain != null) {
            curtain.stiffness = sCurtainStiffness;
            curtain.dampingRatio = sCurtainDamping;
        }
    }

    /** Every knob, in the order the settings page shows them. */
    static final String[] CFG_KEYS = {
            "mode", "gate", "cardblur", "cardradius", "contentpush",
            "clockpush", "carrierpush", "shadebias", "touch", "maxlag",
            "stiffness", "damping", "curtainstiffness", "curtaindamping",
            "blursat", "deadzone", "alphaend",
            "wprise", "wpblur", "sharpstart",
    };

    /**
     * A setting's current value, in the same units {@link #configure} reads.
     *
     * The inverse of configure() and the reason there is only one list of keys: the state file,
     * the query reply and the settings page all read through here, so a knob cannot be added to
     * one and forgotten in another.
     */
    static int cfgInt(String key) {
        if ("mode".equals(key)) return sMode;
        if ("gate".equals(key)) return sGateOn ? 1 : 0;
        if ("cardblur".equals(key)) return sCardBlurOn ? 1 : 0;
        if ("cardradius".equals(key)) return sCardBlurRadius;
        if ("contentpush".equals(key)) return sContentPush;
        if ("clockpush".equals(key)) return ShadeHeader.clockShift();
        if ("carrierpush".equals(key)) return ShadeHeader.carrierShift();
        if ("shadebias".equals(key)) return Main.shadeBiasDelta();
        if ("touch".equals(key)) return sFollowTouch ? 1 : 0;
        if ("maxlag".equals(key)) return Math.round(sMaxLag * 1000f);
        if ("stiffness".equals(key)) return Math.round(sStiffness);
        if ("damping".equals(key)) return Math.round(sDamping * 100f);
        if ("curtainstiffness".equals(key)) return Math.round(sCurtainStiffness);
        if ("curtaindamping".equals(key)) return Math.round(sCurtainDamping * 100f);
        if ("blursat".equals(key)) return Math.round(sBlurSat * 1000f);
        if ("deadzone".equals(key)) return Math.round(sDeadZone * 1000f);
        if ("alphaend".equals(key)) return Math.round(sAlphaEnd * 1000f);
        if ("wprise".equals(key)) return Math.round(sWpRise * 1000f);
        if ("wpblur".equals(key)) return Math.round(sWpBlur);
        if ("sharpstart".equals(key)) return Math.round(sWpSharpStart * 1000f);
        return 0;
    }

    /** The module's side of the settings page, one line per knob, for the state file. */
    static String dumpCfg() {
        final StringBuilder sb = new StringBuilder();
        for (String key : CFG_KEYS) {
            sb.append("\nshade_").append(key).append('=').append(cfgInt(key));
        }
        return sb.toString();
    }

    static boolean cardBlurOn() {
        return sCardBlurOn;
    }

    static int cardBlurRadius() {
        return sCardBlurRadius;
    }

    /** How far the shade's content is pushed down, in pixels. 0 is "leave it alone". */
    static int contentPush() {
        return sContentPush;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /**
     * Built on first use, on the main thread, because ShadeSpring's Choreographer is bound to
     * whichever thread touches it first.
     */
    private static void ensureSprings() {
        if (sBlurSpring != null) return;
        final ShadeSpring blur = new ShadeSpring(new ShadeSpring.Listener() {
            @Override
            public void onSpringUpdate(float v) {
                sRatio = v;
                // Quantised, and pushed only when the step changes - the same fix applyPictureBlur
                // makes to its own radius, applied to the other half of the same every-frame path.
                //
                // The spring chases a target that moves on every frame of a drag, so this used to
                // hand MIUI's window-level blur a brand-new radius sixty times a second. The
                // wallpaper blur is deliberately quantised to multiples of 4 because "setRenderEffect
                // makes the render thread rebuild a filter each time it is called with a new radius";
                // the window blur is very likely the same hazard, and it is the cheaper of the two
                // to fix because the blur is invisible between steps. 24 steps over maxRadius 120 is
                // five pixels of radius - below what the effect can show.
                final float q = Math.round(v * PUSH_STEPS) / PUSH_STEPS;
                if (q == sPushed) return;
                sPushed = q;
                ShadeBlur.pushRatio(q);
            }
        });
        blur.stiffness = sStiffness;
        blur.dampingRatio = sDamping;
        blur.maxValue = 1f;
        sBlurSpring = blur;

        final ShadeSpring curtain = new ShadeSpring(new ShadeSpring.Listener() {
            @Override
            public void onSpringUpdate(float v) {
                applyCurtain(v);
            }
        });
        curtain.stiffness = sCurtainStiffness;
        curtain.dampingRatio = sCurtainDamping;
        curtain.maxValue = 1f;
        sCurtainSpring = curtain;
    }

    private static void applyProgress(float expansion) {
        final FrameLayout frame = sFrame;
        if (frame == null) return;

        if (!effectActive()) {
            // Order matters: the cards are handed back BEFORE the flag says the effect is over,
            // so no frame can decide the effect is still on while the material is already gone.
            if (sEffectOn) Main.setCardBlurActive(false);
            // Unconditional, and not behind the same flag: this path is the cover being switched
            // off or the phone locking mid-pull, and the shift it hands back is a property of
            // SystemUI's own views - left on them, it moves the lock screen's notifications.
            Main.setShadeContentShift(false);
            ShadeHeader.disarm();
            sEffectOn = false;
            if (frame.getVisibility() != View.GONE) reset();
            return;
        }

        // Before the progress is read, not after: visualFraction() drives the curtain spring and
        // reads it back, so on the first frame of the first pull-down - when the springs do not
        // exist yet - it would otherwise hand back a zero and park the layer for that frame.
        ensureSprings();

        // The finger wins while one is down - see visualFraction(). Everything below, including
        // the dead zone test, is that progress and not the panel's.
        final float f = visualFraction(expansion);

        // A shut panel ends it - but only once the edge has stopped moving.
        //
        // The panel reaching 0 is NOT the end of the retract: the edge is a spring and is
        // deliberately still on its way down when the panel gets there. Measured, the panel hit
        // 0 with the edge at 0.24 and the cover at thirty percent opacity still fading - and a
        // guard that read "panel shut means done" cut the animation off exactly there, which is
        // the disappearing cover it was written to prevent.
        //
        // What it is for is the case where the edge is genuinely stuck - a release that went the
        // wrong way, or a settle that stopped before the spring did - and nothing else in this
        // class would ever take it down. A spring that is still running is not stuck.
        final boolean moving = sCurtainSpring != null && sCurtainSpring.running();
        final boolean panelShut = expansion <= SETTLED_EPS && !sTouching && !moving;
        final boolean on = f > sDeadZone && !panelShut;
        // NOT swapping the desktop wallpaper for the cover. It worked - the cards' glass did
        // sample the cover - but it cost a full-screen rescale and texture upload per change and
        // the whole shade went laggy. The blur that the cards get instead is the local one, in
        // Main's applyElementViewBlend hook, scoped to the shade's own rows.
        // The cards' material follows the same edge the curtain does, and for the same reason:
        // this is the only moment anything in the module knows the notification centre is up.
        if (on != sEffectOn) {
            Main.setCardBlurActive(on);
            // Only with a picture: the shift exists to uncover the cover, and shifting the whole
            // shade's content for a pull-down that has nothing to reveal is a change with no
            // reason behind it. background() is read here rather than per frame, so a mode-1
            // wallpaper that has not loaded yet costs one request on this edge and nothing more.
            Main.setShadeContentShift(on && background() != null);
            // Not gated on a picture, unlike the content push: the header offsets are about where
            // the control centre's own text sits, not about uncovering anything.
            ShadeHeader.arm(on);
            // The gesture is over the moment the curtain is asked for nothing: the frames after
            // this are the blur spring settling, not the pull.
            if (!on) logFrameCost();
        }
        sEffectOn = on;
        if (!on) {
            // Hidden, but the drivers below keep running - and that is not tidiness.
            //
            // This used to call reset(), which STOPS THE SPRINGS. An edge that is transiently
            // below the dead zone then never comes back: measured, a release whose target went
            // the wrong way put the edge at 0.013 while the shade was still fully open, every
            // frame below the threshold killed the spring that was bringing it back, and the
            // cover stayed gone with the notification centre still up. The transition is
            // finished by effectActive(), not by the edge being momentarily invisible.
            if (frame.getVisibility() != View.GONE) {
                frame.setVisibility(View.GONE);
                frame.setAlpha(0f);
                frame.setClipBounds(null);
                if (sWp != null) sWp.setRenderEffect(null);
                sLastWpBlur = -1;
            }
            sBlurSpring.animateTo(blurTarget(f));
            return;
        }

        if (sGateOn) ShadeGate.apply(panelView(), f);

        final android.graphics.Bitmap bg = background();
        if (bg != null) {
            // Placed before it is shown, never after: a layer made visible and then moved is a
            // frame of cover in the wrong place.
            if (frame.getVisibility() != View.VISIBLE) {
                ensurePlacement();
                // Set the picture BEFORE the layer is shown, never after: a layer made visible
                // and then handed its bitmap is a frame of cover in the wrong place, and that
                // frame is the very first one a pull-down draws.
                syncShown();
                frame.setVisibility(View.VISIBLE);
                // A pull-down starts wherever the last one left the blur spring, which is
                // usually full. Snapping it is what stops the shade opening beneath a blur that
                // is already at maximum and then relaxing.
                sBlurSpring.snapTo(blurTarget(f));
            }
            final float alpha = f >= sAlphaEnd ? 1f : (f - sDeadZone) / (sAlphaEnd - sDeadZone);
            if (alpha != sLastAlpha) {
                sLastAlpha = alpha;
                frame.setAlpha(alpha);
            }
            applyPictureBlur(f);

            // The curtain follows the panel exactly; it is NOT sprung.
            //
            // This looks like it contradicts the module being ported, and for a while it did
            // here: the edge was put on a spring of its own and fell five times behind - at 90%
            // open the cover showed in the top third of the screen. Their trace says the
            // opposite of what their config suggests: clipH tracked the fraction to three
            // decimals (f=0.0495 -> 131/2656, f=0.7638 -> 2028/2656) while only the blur lagged.
            // The reason is in their driveCurtain(): while the panel is being dragged the
            // curtain spring is snapTo()-ed to the finger, and the spring only takes over on
            // release. Glued edge, sprung blur.
            //
            // Without the touch-follow hook there is no single-frame way to tell a drag from a
            // release, so the correct half to keep is the glue. When that hook lands the edge
            // can spring on release the way theirs does.
            // Drawn from the curtain spring's value rather than from `f`, and applied here as
            // well as in the spring's own callback: on a frame where the spring is at rest no
            // callback fires, and this may be the frame the layer just became visible on.
            applyCurtain(sCurtainSpring.value());
        } else if (frame.getVisibility() == View.VISIBLE) {
            // The feel is ours but there is nothing to show - a pull-down with no music in mode
            // 2. Parked rather than left empty, so the layer is not in the draw path for
            // nothing; the blur below is still driven, which is the point of the mode.
            frame.setVisibility(View.GONE);
            frame.setAlpha(0f);
            frame.setClipBounds(null);
        }

        // The feel, and the reason this is a port rather than a cross-fade: the blur target
        // saturates early and a soft spring chases it, so the blur is fully out long before the
        // panel is open and is always a beat behind the finger.
        //
        // Alpha is deliberately not sprung. A lagging fade reads as a stutter; a lagging blur
        // reads as weight.
        sBlurSpring.animateTo(blurTarget(f));
    }

    private static float blurTarget(float f) {
        return f >= sBlurSat ? 1f : f / sBlurSat;
    }

    /**
     * The picture sharpens as the panel opens: full blur below {@link #sWpSharpStart}, linear to
     * nothing at fully open.
     *
     * The radius is quantised to multiples of 4 before the effect is built, which is the one
     * piece of tuning in here that is not cosmetic. `setRenderEffect` makes the render thread
     * rebuild a filter each time it is called with a new radius, and at one call per frame that
     * is a filter per frame; quantising turns a 120-frame pull into about twenty.
     */
    private static void applyPictureBlur(float f) {
        int r;
        if (f <= 0f) {
            r = 0;
        } else if (f <= sWpSharpStart) {
            r = (int) sWpBlur;
        } else {
            r = (int) (sWpBlur * ((1f - f) / (1f - sWpSharpStart)));
        }
        r = (r / 4) * 4;
        if (r == sLastWpBlur) return;
        sLastWpBlur = r;
        final ImageView wp = sWp;
        if (wp == null) return;
        wp.setRenderEffect(r > 0
                ? android.graphics.RenderEffect.createBlurEffect(
                        r, r, android.graphics.Shader.TileMode.CLAMP)
                : null);
    }

    /**
     * The reveal, driven by the curtain spring rather than by the finger directly - which is why
     * the edge settles a beat after the panel does.
     *
     * The cover is clipped to the top `cf` of the screen, so below the edge is whatever was
     * there before: the app. That is the effect - the shade is a curtain drawn down over the
     * screen, not a full-screen picture - and the hard line is the iOS look.
     */
    private static void applyCurtain(float cf) {
        final ViewGroup root = sRoot;
        final FrameLayout frame = sFrame;
        if (root == null || frame == null || frame.getVisibility() != View.VISIBLE) return;

        // Measured from the SHADE ROOT and not from the display. The two differ by the insets,
        // and the clip rectangle is in the root's coordinate space.
        final int maxH = root.getHeight();
        if (maxH <= 0) return;

        sCurtainFraction = cf;
        final int line = (int) (maxH * cf);
        // Written only when the value actually moves, and that is not tidiness: this function is
        // reached TWICE on every frame while a finger is down - once from the curtain spring's own
        // callback (visualFraction snapTo()s the edge to the hand) and once from the end of
        // applyProgress - with the identical value both times. Each write is a RenderNode property
        // change on a full-screen container, so the second one was a whole extra pass of work per
        // frame, on the frames where the UI thread is already the thing that is short of time.
        if (line != sLastClipBottom) {
            sLastClipBottom = line;
            sCurtainLine = line;
            frame.invalidateOutline();
        }

        // The cover sinks into place from above, on the SAME lagged value - so the picture
        // settles after the edge does rather than moving with it as one rigid sheet.
        final float rise = -sWpRise * maxH * (1f - cf);
        final ImageView wp = sWp;
        if (wp != null && rise != sLastRise) {
            sLastRise = rise;
            wp.setTranslationY(rise);
        }
    }

    /** Last values written by applyCurtain, so an unchanged frame writes nothing at all. */
    private static int sLastClipBottom = -1;
    private static float sLastRise = Float.NaN;
    private static volatile float sLastAlpha = Float.NaN;

    /** The curtain's bottom edge, in the shade root's coordinates. Read by CURTAIN_OUTLINE. */
    private static volatile float sCurtainLine;

    /**
     * The curtain's shape: a rounded rectangle whose bottom corners carry the display's own corner
     * radius.
     *
     * The bottom edge is what the eye lands on - it is the only edge of this layer that is not the
     * screen's own - and a hard rectangle there reads as a sheet of glass cut with a ruler. Rounding
     * it by the same radius the display uses makes the curtain a shape the screen could have drawn
     * itself.
     *
     * `setRoundRect` and not a Path: a round rect takes the renderer's stencil clip, while an
     * arbitrary path can send the view through an offscreen layer on every frame of a pull. The
     * top corners come along for the ride, which is free - the curtain's top is the screen's top,
     * and the two radii sit exactly on top of each other.
     */
    private static final android.view.ViewOutlineProvider CURTAIN_OUTLINE =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    final float bottom = sCurtainLine;
                    if (bottom <= 0f || view.getWidth() <= 0) {
                        outline.setEmpty();
                        return;
                    }
                    // Clamped: a radius over half the shape is no longer a rounded rectangle, and
                    // a curtain one frame tall would otherwise be given one it cannot hold.
                    final float r = Math.min(cornerRadius(),
                            Math.min(bottom, view.getWidth()) / 2f);
                    outline.setRoundRect(0, 0, view.getWidth(), (int) bottom, r);
                }
            };

    /**
     * What this module's own frame path costs, summarised once per gesture.
     *
     * One line per gesture and never one per frame - this module has already been bitten once by a
     * probe that logged on every frame and starved the thing it was measuring. An average and a
     * worst case are enough to answer the only question that matters here: is the curtain where
     * the frame time goes, or is it somewhere this module does not own. The gesture that produces
     * the answer is an ordinary pull-down.
     */
    private static void noteFrame(long ns) {
        if (sFrameCount == 0) sFrameFirstNs = android.os.SystemClock.elapsedRealtimeNanos();
        sFrameLastNs = android.os.SystemClock.elapsedRealtimeNanos();
        sFrameCount++;
        sFrameTotalNs += ns;
        if (ns > sFrameMaxNs) sFrameMaxNs = ns;
    }

    private static void logFrameCost() {
        final long n = sFrameCount;
        if (n == 0) return;
        Xp.log(TAG + "frame cost: " + n + " frames over " + ((sFrameLastNs - sFrameFirstNs) / 1000000)
                + "ms, ours avg " + (sFrameTotalNs / n / 1000) + "us, max "
                + (sFrameMaxNs / 1000) + "us");
        sFrameCount = 0;
        sFrameTotalNs = 0;
        sFrameMaxNs = 0;
    }

    private static long sFrameCount;
    private static long sFrameTotalNs;
    private static long sFrameMaxNs;
    private static long sFrameFirstNs;
    private static long sFrameLastNs;

    /** The display's corner radius, or 0 when it cannot be read. Resolved once. */
    private static volatile float sCornerRadius = -1f;

    private static float cornerRadius() {
        float r = sCornerRadius;
        if (r >= 0f) return r;
        r = 0f;
        try {
            final ViewGroup root = sRoot;
            final android.view.WindowInsets wi = root == null ? null : root.getRootWindowInsets();
            final android.view.RoundedCorner c = wi == null ? null
                    : wi.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
            if (c != null) r = c.getRadius();
        } catch (Throwable t) {
            Xp.log(TAG + "no corner radius from the insets, the curtain line will run to the "
                    + "screen edge: " + t);
        }
        sCornerRadius = r;
        Xp.log(TAG + "display corner radius = " + r);
        return r;
    }

    // ------------------------------------------------------------------ phase 1a probe

    /**
     * Paints the layer flat so its placement can be judged without a cover, a spring or a blur
     * in the way. This is the cheapest possible test of the failure the port notes call out as
     * the most likely one: "the wallpaper layer must go immediately below the panel, or it is
     * invisible".
     */
    /**
     * The picture to reveal. Called from Main's worker thread, so the swap is posted.
     *
     * The bitmap is owned by Main.setShadeArt() - it is not copied, not retained past this
     * view, and never recycled here. The isRecycled() guard is the last line of defence against
     * a bitmap dying while the display list still names it, which is the one failure of this
     * whole feature that does not stop on its own.
     */
    static void setArt(final android.graphics.Bitmap b) {
        sArt = b;
        UI.post(sSyncShown);
    }

    static void setProbe(boolean on) {
        sProbe = on;
        final FrameLayout frame = sFrame;
        final ImageView wp = sWp;
        if (frame == null || wp == null) {
            Xp.log(TAG + "probe: no layer (shade window never built?)");
            return;
        }
        try {
            if (on) {
                ensurePlacement();
                wp.setImageDrawable(null);
                wp.setBackgroundColor(0xCCFF3B30);
                wp.setTranslationY(0f);
                frame.setClipBounds(null);
                frame.setAlpha(1f);
                frame.setVisibility(View.VISIBLE);
            } else {
                frame.setVisibility(View.GONE);
                frame.setAlpha(0f);
                wp.setBackgroundColor(0);
            }
        } catch (Throwable t) {
            Xp.log(TAG + "probe failed: " + t);
            return;
        }
        Xp.log(TAG + "probe " + (on ? "ON" : "off") + " - " + describe());
        // The question phase 0 could not settle from a desk: onKeyguardNow() is
        // sContainer.isShown(), and if the panel expanding over the DESKTOP makes the keyguard
        // root visible, the live-wallpaper cover view is already leaking into the unlocked
        // shade today. Both of the module's own answers are printed here so the two can be
        // compared on the device.
        Xp.log(TAG + "gates: keyguardLocked=" + Main.keyguardLocked()
                + " clockContainerShown=" + Main.clockContainerShown()
                + " coverMode=" + Main.coverModeOn());
    }

    // ------------------------------------------------------------------ reset

    /**
     * Hands every property back and parks the layer. Idempotent, and reachable from all four of
     * the bar-state change, the cover exit, the keyguard rebuild and the disable op - a path
     * that leaves a clip or a translation behind shows up as a shade that is wrong for a reason
     * no log explains.
     */
    static void reset() {
        sResets++;
        final FrameLayout frame = sFrame;
        final ImageView wp = sWp;
        // The write guards in applyCurtain are invalidated here, not left at whatever the last
        // frame held: reset() clears the clip and the rise, and a guard that still believed they
        // were written would leave them cleared and never rewritten on the next pull.
        sLastClipBottom = -1;
        sLastRise = Float.NaN;
        sLastAlpha = Float.NaN;
        // Stopped, not left running: a spring chasing a target nobody is drawing is a frame
        // callback per frame for the life of SystemUI.
        if (sBlurSpring != null) sBlurSpring.stop();
        if (sCurtainSpring != null) sCurtainSpring.stop();
        // The next pull-down is a new gesture; a release curve left armed would start from the
        // previous one's numbers.
        sReleaseActive = false;
        // Also when the switch has just been turned off mid-pull: the gate could have left the
        // panel hidden, and that state outlives the setting that caused it.
        if (sGateOn || ShadeGate.hidden()) ShadeGate.restore();
        // Same reasoning as the gate: reachable from the cover exit, the keyguard rebuild and the
        // disable op, and each of those can happen with the shade up and the content already
        // shifted. A no-op when nothing is held.
        Main.setShadeContentShift(false);
        ShadeHeader.disarm();
        if (frame != null) {
            frame.setAlpha(0f);
            // The outline goes with it: a stale rounded rect left on a GONE layer is one the next
            // pull-down would be clipped to for the frame before the first applyCurtain().
            sCurtainLine = 0f;
            frame.invalidateOutline();
            frame.setVisibility(View.GONE);
        }
        if (wp != null) {
            wp.setTranslationY(0f);
            wp.setRenderEffect(null);
        }
        // Cleared with the effect, or the next pull-down would decide the radius it wants is
        // the one already applied and never build it.
        sLastWpBlur = -1;
    }

    /**
     * Whether the cover layer is on screen and therefore owns the blur.
     *
     * Read by the setBlurRatio hook on SystemUI's UI thread, which is also where the visibility
     * is written, so the two never race.
     */
    static boolean driving() {
        return sEffectOn;
    }

    /** The blur spring's current value. */
    static float ratio() {
        return sRatio;
    }

    // ------------------------------------------------------------------ touch follow

    /** Where the curtain edge is now, 0..1. Needed as the baseline for an upwards gesture. */
    private static volatile float sCurtainFraction;

    /**
     * The hand-off, between the finger leaving the screen and the panel coming to rest.
     *
     * The edge is at `cur` when the hand goes and the panel is at `exp`; the panel then settles
     * to `target`, which is 0 or 1 and is discovered from the direction it starts moving in -
     * the settle is monotonic, so the first movement is the whole answer. The curve is then the
     * affine map that carries `exp -> cur` and `target -> target`:
     *
     *     cur + (e - exp) * (target - cur) / (target - exp)
     *
     * Both ends are exact, so the edge is continuous where the finger left it and lands exactly
     * where the panel lands. What it is NOT is a spring toward `expansion`, which is what this
     * was first, and the difference is the entire bug: `expansion` at the moment of release is
     * the value the panel is about to LEAVE. Measured on device - finger down at 8.7% of the
     * screen, panel at 85.5%, released - a spring toward the current value walked the edge DOWN
     * to 32% and back over 280ms, brightening the cover sixfold on the way. Chasing the
     * destination instead moves it the same way the panel moves, and only as far as it has to.
     */
    private static volatile boolean sReleaseActive;
    private static volatile float sReleaseCur;
    private static volatile float sReleaseExp;
    /** NaN until the panel's settle direction is known. */
    private static volatile float sReleaseTarget = Float.NaN;
    /** Frames since the hand left; see RELEASE_PATIENCE. */
    private static volatile int sReleaseFrames;

    /**
     * How far the panel must move before its settle direction is believed. About one frame of a
     * normal settle: below this it is jitter, and reading a direction off jitter would send the
     * edge the wrong way for the whole animation.
     */
    private static final float SETTLE_EPS = 0.006f;

    /**
     * How far the panel must move against the latched target before the target gives way.
     *
     * Small enough to catch a settle that has plainly gone the other way - a panel closing from
     * full passes this in about two frames - and large enough to ignore the wobble either side of
     * a still panel.
     */
    private static final float SETTLE_COMMIT = 0.05f;

    /**
     * Within this of an end, the panel is taken to be at rest there already.
     *
     * The case this exists for is the common one: pull to the bottom, the panel settles at 1.0
     * while the finger is still down, and the finger then lifts. Expansion is not going to move
     * again, so there is no frame on which to discover the destination - it has to be read here,
     * at the moment of the lift, or the release never starts.
     */
    private static final float SETTLED_EPS = 0.001f;

    /**
     * How far the hand must have travelled for its direction to mean anything.
     *
     * About a millimetre of screen at this density: enough that a finger which barely twitched
     * while lifting is not read as pushing the panel somewhere.
     */
    private static final float TOUCH_SLOP = 24f;

    /**
     * Frames after a release to wait for the panel to show which way it is going.
     *
     * The settle announces itself on the first frame in every normal case, so this only fires
     * when the panel is already at rest - and then holding the edge is the wrong answer, because
     * the edge would stay there for good.
     */
    private static final int RELEASE_PATIENCE = 6;

    /** The last expansion the system wrote, for the moment a finger lifts. */
    private static volatile float sExpansion;
    private static volatile boolean sTouching;
    /** True once the gesture is known to be the panel opening or closing, not a scroll. */
    private static volatile boolean sGesture;
    /** Frames left to trace unconditionally; set by a release. See onExpansion. */
    private static volatile int sTraceRelease;
    private static volatile float sTouchY;
    private static volatile float sTouchStartY;
    private static volatile float sTouchStartFraction;
    /** Whether the panel was shut when the finger went down - it decides the baseline below. */
    private static volatile boolean sStartedCollapsed;

    private static volatile Object sPanelController;

    static void onPanelController(Object controller) {
        sPanelController = controller;
    }

    /**
     * The panel's touch handler, observed only - the original always runs and its answer is
     * never overridden. This module already has one hook on the shade's dispatch for the same
     * reason: anything that consumes an ACTION_DOWN over the shade eats swipe-to-unlock with it.
     */
    static void onTouch(android.view.MotionEvent ev) {
        if (ev == null) return;
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                sTouching = true;
                sGesture = false;
                sTouchY = ev.getRawY();
                sTouchStartY = sTouchY;
                // The PANEL's state, not the edge's. The edge is a spring and is often still
                // retracting while the panel is already shut, so asking it whether the shade is
                // collapsed gets "no" - and the relative formula then takes a baseline from
                // somewhere in the middle of that retract. Measured, a finger landing during one
                // moved the edge from 0.70 to 0.036 in a single frame.
                sStartedCollapsed = sExpansion <= SETTLED_EPS;
                sTouchStartFraction = sCurtainFraction;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                sTouchY = ev.getRawY();
                if (!sGesture) sGesture = panelGesture();
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                sTouching = false;
                sGesture = false;
                // Open a window where every frame is traced. The ordinary trace is one line per
                // 5% of travel, which is right for watching a whole pull-down and useless for a
                // hand-off: a small pull is two or three frames wide, and the question is what
                // the next twenty do.
                sTraceRelease = 40;
                // Here, and not on the next expansion frame, because there may not be one. The
                // panel settling while the finger is still down is the ordinary case, not an
                // edge case, and expansion stops being written the instant it settles.
                beginRelease();
                break;
            default:
                break;
        }
    }

    /**
     * Whether this touch is the panel opening or closing rather than a list scroll or a tap on a
     * notification. Without it the curtain would jump whenever a notification list was scrolled,
     * which is a bug that reads as the feature being broken at random.
     */
    private static boolean panelGesture() {
        final Object c = sPanelController;
        if (c == null) return false;
        try {
            if (Boolean.TRUE.equals(Xp.callMethod(c, "isTracking"))) return true;
        } catch (Throwable ignored) {
        }
        try {
            final Object injector = Xp.getObjectField(c, "mNotifInjector");
            if (injector != null) {
                return truthy(injector, "panelOpening") || truthy(injector, "panelCollapsing")
                        || truthy(injector, "panelIntercepting");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean truthy(Object o, String name) {
        try {
            return Boolean.TRUE.equals(Xp.getObjectField(o, name));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The edge position the finger is asking for.
     *
     * Two formulas, because the two gestures do not mean the same thing. Pulling down from a
     * shut panel puts the edge under the finger, so the finger's own screen Y is the answer.
     * Flicking a half-open panel back up is a DELTA - taking the absolute Y there would teleport
     * the edge to wherever the finger happens to be, which is nowhere near the edge.
     */
    private static float fingerFraction() {
        final View root = sRoot;
        if (root == null) return sCurtainFraction;
        final int maxH = root.getHeight();
        if (maxH <= 0) return sCurtainFraction;
        final int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        final float tf = sStartedCollapsed
                ? (sTouchY - loc[1]) / maxH
                : sTouchStartFraction + (sTouchY - sTouchStartY) / maxH;
        return clamp01(tf);
    }

    /**
     * The progress everything is drawn from.
     *
     * Normally this is simply `expansion` - one quantity, read from the one place the system
     * writes it, so the cover and the panel are never out of step and there is no handoff at the
     * end of a gesture to get wrong. See {@link #sFollowTouch} for why that is the default and
     * what the alternative costs.
     *
     * With the finger-following switch on, the edge tracks the hand instead and the curtain
     * spring rides the system's own settle animation from wherever the hand left it. That is the
     * reference module's structure; it is not the default here for the reason given there.
     */
    private static float visualFraction(float expansion) {
        final ShadeSpring curtain = sCurtainSpring;
        if (curtain == null) return expansion;
        if (!sFollowTouch) {
            // Snapped, not sprung: a spring here would be a lag behind the panel, and the whole
            // point of this path is that the two agree.
            sSrc = "panel";
            curtain.snapTo(expansion);
            return expansion;
        }
        if (sTouching && sGesture) {
            if (expansion <= SETTLED_EPS && !draggingDown()) {
                sSrc = "hold";
                // A finger on a shut panel that is not pulling it down is not opening anything -
                // it is a flick at nothing, or a list being scrolled. Handing it the edge anyway
                // snapped the edge from wherever the retract had got to straight to the finger.
                // The retract finishes instead and the gesture is left to the panel, which is
                // the only thing it can act on.
                if (!sReleaseActive) startRelease(expansion);
                return releaseFraction(curtain, expansion);
            }
            // Glued to the hand. Nothing else runs while a finger is on the panel, so the
            // release curve always starts from a value the hand actually produced.
            sSrc = "glue";
            sReleaseActive = false;
            // The edge follows the hand, but it is not allowed to fall more than sMaxLag behind
            // the panel.
            //
            // Gluing alone is not enough on this device, because the panel opens about nine times
            // faster than the finger travels: measured, the finger at y=250 with expansion at 1.0
            // and the edge at 0.095 - a sliver of cover over a fully open shade, and it stayed
            // there for the whole second the finger was held still, because a motionless finger
            // produces nothing to follow. The release catch-up cannot help while the finger is
            // down, which is the case this exists for.
            //
            // Small enough that the correction is invisible when it fires, and it simply never
            // fires while the hand is genuinely dragging the panel - which on a shade that tracks
            // the finger one to one is always.
            float finger = fingerFraction();
            // The allowance SHRINKS as the panel opens, and reaches zero at fully open.
            //
            // A flat allowance leaves the cover short at exactly the moment the shade is
            // finished: the panel reaches 1.0 almost immediately on this device, so the edge sat
            // at 1 - 0.15 = 0.85 - a visible strip of app along the bottom - for as long as the
            // finger was held, and only the release filled it in. Scaling it by (1 - expansion)
            // means the cover is complete the instant the panel is, while a hand that is genuinely
            // dragging a barely-open shade is still followed unclamped.
            final float floor = expansion - sMaxLag * (1f - expansion);
            if (finger < floor) finger = floor;
            curtain.snapTo(clamp01(finger));
            return curtain.value();
        }
        if (sTouching) {
            // A finger is down but this is not (yet) a panel gesture - a notification being
            // scrolled, a tap, or the few frames before the panel decides the drag is its own.
            // NOTHING moves the edge here. Not the finger, because we do not know what it means
            // yet; and not the panel either, which is what this used to do and is a jump in
            // exactly the case that matters - a finger landing while the panel is open and the
            // cover is still retracting, where snapping to the panel threw the edge from
            // wherever the retract had reached straight to the panel's value in one frame.
            // Measured: cur 0.972 -> 0, and 1.0 -> 0.028.
            sSrc = "idle";
            if (!sReleaseActive) startRelease(expansion);
            return releaseFraction(curtain, expansion);
        }
        sSrc = "release";
        if (!sReleaseActive) startRelease(expansion);
        return releaseFraction(curtain, expansion);
    }

    /**
     * Which branch of {@link #visualFraction} ran on the last frame.
     *
     * A label rather than an inference. The flags in this class interact - a release can still be
     * armed while a finger is down, a gesture can be classified after the finger has moved - and
     * reading which path executed off `touch=` and `rel=` alone produced two wrong diagnoses
     * before this existed.
     */
    private static volatile String sSrc = "?";

    /** How many times the layer has been parked. Diagnostic; see the trace's `rst=`. */
    private static volatile long sResets;

    private static void startRelease(float expansion) {
        sReleaseActive = true;
        sReleaseCur = sCurtainFraction;
        sReleaseExp = expansion;
        sReleaseTarget = Float.NaN;
    }

    /**
     * The finger has just left. Called from the touch handler, not from a frame - see the note
     * on SETTLED_EPS.
     */
    private static void beginRelease() {
        if (!sFollowTouch) return;
        final ShadeSpring curtain = sCurtainSpring;
        if (curtain == null) return;
        startRelease(sExpansion);
        sReleaseFrames = 0;

        // At an end, the panel's position is the answer and the hand's direction is NOT
        // consulted at all.
        //
        // The direction looks like the more informative signal and it is the trap. It can only
        // ever be a guess about what the panel will do, and the correction for a wrong guess has
        // to run on `expansion` frames - which stop the instant the panel settles. Measured, with
        // the panel held at 1.0: the hand went up, the release read that as "closing", the panel
        // did not move, no frames arrived, nothing corrected the target, and the spring ran the
        // cover to zero over the next 1.14 seconds with the shade still open. Touching it again
        // produced a frame and the cover came back, which is the "it comes back if I touch it"
        // the user reported.
        //
        // Reading the panel's own end instead cannot be wrong: a panel at 1.0 that closes will
        // produce frames as it goes, and the contradiction rule below flips the target on the way
        // down. A panel at 1.0 that stays produces no frames and needs no correction.
        if (sExpansion >= 1f - SETTLED_EPS) sReleaseTarget = 1f;
        else if (sExpansion <= SETTLED_EPS) sReleaseTarget = 0f;
        if (!Float.isNaN(sReleaseTarget)) curtain.animateTo(sReleaseTarget);
    }

    /**
     * Where the edge goes once the hand has gone, and the spring that takes it there.
     *
     * The edge springs to the panel's DESTINATION - 0 or 1 - and not to the panel's current
     * value. Both halves of that are load-bearing, and each was learned by getting it wrong:
     *
     * - Springing to the current value is the down-jump. At the moment of release `expansion` is
     *   the value the panel is about to leave, so a spring toward it walks the edge forward into
     *   open space and then back - measured at 0.087 up to 0.321 and down again over 280ms.
     * - Driving from `expansion` FRAMES at all is the other half, and it is what left the cover
     *   parked. Expansion stops being written the moment the panel comes to rest, and the panel
     *   very often comes to rest while a finger is still down: measured, the finger at 48% with
     *   the panel already fully open and settled. Lifting then produced no frames at all, so a
     *   curve parameterised by `expansion` never ran and the edge stayed at 48% for good. A
     *   spring carries its own Choreographer, so it finishes whether or not the panel is still
     *   moving - which is the property that was missing.
     *
     * The destination is read from `expansion` when it is already at an end, and otherwise from
     * the direction the panel starts settling in. The settle is monotonic, so the first movement
     * that clears the noise is the whole answer; before it is known the edge simply waits, which
     * is the only option that cannot send it the wrong way.
     */
    private static float releaseFraction(ShadeSpring curtain, float expansion) {
        sReleaseFrames++;
        if (Float.isNaN(sReleaseTarget)) {
            if (expansion >= 1f - SETTLED_EPS) sReleaseTarget = 1f;
            else if (expansion <= SETTLED_EPS) sReleaseTarget = 0f;
            else if (expansion > sReleaseExp + SETTLE_EPS) sReleaseTarget = 1f;
            else if (expansion < sReleaseExp - SETTLE_EPS) sReleaseTarget = 0f;
            else if (sReleaseFrames > RELEASE_PATIENCE) {
                // Nothing has moved it. The panel is at rest, so where it is IS where it settled
                // - and latching to the nearer end is what stops the edge waiting for ever.
                // Holding it, which is what this did, leaves the cover parked mid-screen over a
                // shade that has already closed: the freeze this whole path exists to avoid.
                sReleaseTarget = expansion < 0.5f ? 0f : 1f;
            }
        }
        // The latched target is a PROVISIONAL answer - it came from one instant's worth of
        // evidence, the hand's direction at the moment it left - and the panel's own movement
        // overrules it. Without this the panel could be visibly closing while the edge kept
        // heading for open: measured, the panel went 0.97 to 0 in 340ms while the edge sat at
        // 1.0, and the correction only arrived once the panel had already finished and the
        // spring had settled. The cover painted the whole screen over a shut shade for a third
        // of a second and was still at 0.87 when the next touch cut it off.
        if (!Float.isNaN(sReleaseTarget)) {
            if (sReleaseTarget == 1f && expansion < sReleaseExp - SETTLE_COMMIT) sReleaseTarget = 0f;
            else if (sReleaseTarget == 0f && expansion > sReleaseExp + SETTLE_COMMIT) sReleaseTarget = 1f;
            else if (Math.abs(expansion - sReleaseExp) < SETTLE_EPS
                    && sReleaseFrames > RELEASE_PATIENCE) {
                // The panel has not moved at all, so it is not going anywhere and the target was
                // wrong. This is the case the "moved away from the target" test above can never
                // see: at an end there is no further to move, so a panel that stays fully open
                // while the edge is told to close never registers as a contradiction. Measured,
                // an up-swipe over the shade's clock and date: the finger said "close", the
                // panel stayed at 1.0, the cover retracted to nothing and the shade stayed up -
                // the wallpaper left and the notification centre did not.
                final float settled = expansion >= 0.5f ? 1f : 0f;
                if (settled != sReleaseTarget) {
                    sReleaseTarget = settled;
                    sReleaseFrames = 0;
                    curtain.animateTo(settled);
                    return curtain.value();
                }
            }
        }

        final float target = sReleaseTarget;
        if (Float.isNaN(target)) return curtain.value();
        // The panel can reach an end that is not where the edge is heading, and by a route this
        // gesture never saw: the back key, the home gesture, the screen going off, a tap on a
        // notification. The target was latched at the moment of release and would otherwise
        // never be revisited, which left the edge parked at full with the shade shut - measured,
        // exp=0.0 with cur=1.0 and the cover painting the whole screen.
        //
        // Only once the spring has settled: while it is still running toward its target the
        // panel passing an end on the way there is the ordinary case, not a contradiction.
        if (!curtain.running()) {
            if (expansion <= SETTLED_EPS && target != 0f) {
                sReleaseTarget = 0f;
                curtain.animateTo(0f);
                return curtain.value();
            }
            if (expansion >= 1f - SETTLED_EPS && target != 1f) {
                sReleaseTarget = 1f;
                curtain.animateTo(1f);
                return curtain.value();
            }
        }
        curtain.animateTo(target);
        return curtain.value();
    }

    /** Whether this touch has travelled far enough down to be pulling the panel open. */
    private static boolean draggingDown() {
        return sTouchY - sTouchStartY > TOUCH_SLOP;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        return v > 1f ? 1f : v;
    }

    /** Three decimals, for the trace. */
    private static String float0(float v) {
        return String.valueOf(Math.round(v * 1000f) / 1000f);
    }

    /** A view's top-left on screen, for the trace. */
    private static String locOnScreen(View v) {
        if (v == null) return "?";
        final int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return xy[0] + "," + xy[1];
    }

    /** The shade window root, so the blur provider can be matched to it by identity. */
    static View shadeRoot() {
        return sRoot;
    }

    /** One line naming every fact that decides whether the layer can be seen. */
    static String describe() {
        final ViewGroup root = sRoot;
        final FrameLayout frame = sFrame;
        return "root=" + (root == null ? "null" : root.getClass().getSimpleName()
                + "/" + root.getChildCount())
                + " frame=" + (frame == null ? "null"
                : frame.getVisibility() + "@" + sPlacedAt
                  + " alpha=" + frame.getAlpha()
                  + " line=" + (int) sCurtainLine + " r=" + (int) cornerRadius())
                + " panel=" + (sPanelCls == null ? "unresolved" : "resolved")
                + " mode=" + sMode
                + " push=" + sContentPush + " pushNow=" + Main.shadePushDescribe()
                + " hdr=" + ShadeHeader.describe()
                + " sbias=" + Main.shadeBiasDelta()
                + " pic=" + (sShown == null ? "none"
                        : sShown == sArt ? "cover"
                        : sShown == sWallpaper ? "wallpaper" : "stale")
                + " cur=" + sCurtainFraction
                // Both flags, separately, and the branch that actually ran. `touch=` alone is
                // ambiguous: it reports "gesture" for a gesture flag left set with no finger
                // down, and two diagnoses were built on that reading before this was split.
                + " touch=" + (sGesture ? "gesture" : sTouching ? "down" : "up")
                + " T/G=" + sTouching + "/" + sGesture
                + " src=" + sSrc
                // Whether the spring is actually running, and how many times the layer has been
                // parked. An edge that will not climb to a target it agrees with is either a
                // spring that is not running or one that keeps being reset, and those two look
                // identical from `cur` alone.
                + " spr=" + (sCurtainSpring == null ? "?" : float0(sCurtainSpring.value())
                        + "/" + (sCurtainSpring.running() ? "run" : "stop"))
                + " tgt=" + float0(sReleaseTarget) + " relF=" + sReleaseFrames
                + " rst=" + sResets
                // What the VIEW believes about itself. Every value above can be right while
                // nothing is drawn - measured, alpha 1.0, clip full-screen and the cover bitmap
                // set, with the home wallpaper on screen instead - and the difference between
                // "the maths is wrong" and "the view is wrong" is exactly these: how big the
                // layer is, where it is, and whether the ImageView actually holds a drawable.
                + " wh=" + (frame == null ? "?" : frame.getWidth() + "x" + frame.getHeight())
                + " at=" + locOnScreen(frame)
                + " drw=" + (sWp == null ? "?" : (sWp.getDrawable() != null))
                + " wpwh=" + (sWp == null ? "?" : sWp.getWidth() + "x" + sWp.getHeight())
                // The release curve's three inputs: where the hand left it, where the panel was
                // then, and where the panel is heading. "?" means the direction is not known yet.
                + " rel=" + (sReleaseActive
                        ? float0(sReleaseCur) + "/" + float0(sReleaseExp) + "->"
                          + (Float.isNaN(sReleaseTarget) ? "?" : float0(sReleaseTarget))
                        : "off")
                + " blurNow=" + sRatio + " " + ShadeBlur.describe();
    }
}
