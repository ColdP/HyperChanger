// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.immersive;
import btm.m.os4.systemuihook.hypermusiccover.next.CoverCompose;
import btm.m.os4.systemuihook.hypermusiccover.next.island.LockIslands;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.PageSpring;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.List;
import java.util.Locale;

/**
 * The clock app's countdown as a page behind the lock screen, opened from its focus island - the
 * page the clock app itself shows while a countdown runs, drawn here: Xiaomi's clock has no
 * immersive service to draw it for us, as ColorOS's has (TimerImmersiveService).
 *
 * The page is the app's own (com.android.deskclock 18.26, timer_circle_view_layout,
 * TimerProgressBgView, TimerProgressView, TimerFragment.updateBrightView), measure for measure:
 *   - a disc of radius 152dp - ColorOS's sheet of glass rather than the app's dark one (GLASS_*);
 *   - a 4dp ring 8dp inside it: what remains in #277af7, what has gone in #11ffffff, from twelve
 *     o'clock anticlockwise, 3 degrees apart where they meet, and a 3.5dp dot at the head;
 *   - the time left, HH:MM:SS at 54dp in the app's Mitype2019-60, #f2ffffff;
 *   - 「共1小时」 at 16dp in #80ffffff under it, 「计时结束」 in the ring's blue once it is over;
 *   - 20dp under that the 「屏幕常亮」 pill: 14/8dp padding, fully round, the app's icon and 12dp
 *     text; on #277af7 with #e6ffffff when on, #14ffffff with #ccffffff when off.
 * No bottom buttons: the countdown's own row, in the stack while the page is up, has them.
 * Under the disc is the lock screen's wallpaper, blurred as ColorOS's countdown page has it
 * (filmed 2026-09-30) - and blurred the way ColorOS does it, once, into a picture: entering a
 * surface-type immersive page, OplusKgdImmersiveController crops the current wallpaper
 * (getNextCropBitmap) and ImmersiveBitmapManager has it Gaussian-blurred on a work thread; the doze
 * reuses the cached one. Not SurfaceFlinger's live blur-behind, which the first version used: a
 * blur pass on every composition, in the doze too, and the map under the window blurred with the
 * wallpaper through a crossfade. The picture is the lock wallpaper's file, cropped to the screen
 * and run through CoverCompose.blur, made on a thread of its own when the page is made and kept
 * while the wallpaper's id stays the same.
 *
 * The disc stands still at a fixed share of the height, between the small clock and the rows at
 * the bottom. It followed the countdown's row at first, and the row's own animations made it
 * jump a few pixels at a time (2026-09-30).
 *
 * The countdown is the clock app's focus notification (protocol 1, scene "timer", business
 * "countdown"), whose timerInfo is all there is to draw from: timerType below 0 counts down to
 * timerWhen, -1 and -3 running, the rest paused at timerSystemCurrent; timerTotal is the whole.
 * LockIslands hands every locked pipeline run's notes to onNotes, so a pause or a resume in the
 * row arrives as a new reading.
 *
 * 屏幕常亮 keeps the lit lock screen on while the countdown runs, the way the lyrics' own does
 * (LockLyrics.holdScreen): keepScreenOn on the page's view, which is in the shade window - never
 * in a doze, where it would pull the phone out of the AOD - and not with the phone in a pocket.
 */
public final class CountdownScene implements ImmersiveScene {

    static final String ID = "countdown";
    static final String PKG = "com.android.deskclock";
    public static final CountdownScene INSTANCE = new CountdownScene();

    private static final String TAG = "MCImmersive: " + ID + ": ";

    private final Handler mMain = Gen.handler();

    /** The countdown's notification, and its latest timer; null with none on the lock screen. */
    private String mKey;
    private LockIslands.Timer mTimer;
    private CountdownView mView;
    /** The blurred wallpaper, under the window - see GroundSurface. */
    private GroundSurface mGround;
    private boolean mShown;
    private boolean mDozing;
    /** 屏幕常亮, as last set; the app's own starts off. */
    private boolean mKeepOn;
    private boolean mHolding;

    private CountdownScene() {
    }

    /** A locked pipeline run's notes, any thread. */
    public void onNotes(List<LockIslands.Note> all) {
        String key = null;
        LockIslands.Timer timer = null;
        for (LockIslands.Note n : all) {
            LockIslands.Timer t = n.getTimer();
            if (PKG.equals(n.getPkg()) && n.getFocus() && t != null && t.getType() < 0) {
                key = n.getKey();
                timer = t;
                break;
            }
        }
        final String k = key;
        final LockIslands.Timer t = timer;
        mMain.post(() -> read(k, t));
    }

    /** A notification left SystemUI, any thread: the countdown's closes its page. */
    public void onRemoved(String key) {
        mMain.post(() -> {
            if (mKey == null || !mKey.equals(key)) return;
            Xp.log(TAG + "notification removed");
            read(null, null);
        });
    }

    private void read(String key, LockIslands.Timer timer) {
        boolean was = mKey != null;
        mKey = key;
        mTimer = timer;
        if (mView != null) mView.setTimer(timer);
        updateHold();
        if (was != (key != null)) ImmersiveHost.readyChanged(this);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean serves(String pkg, boolean focus) {
        return focus && PKG.equals(pkg);
    }

    @Override
    public boolean servesKey(String key) {
        return key != null && key.equals(mKey);
    }

    @Override
    public boolean ready() {
        return mKey != null;
    }

    @Override
    public void prepare(ViewGroup slot) {
        if (mView != null) return;
        CountdownView v = new CountdownView(slot.getContext(), this);
        v.setVisibility(View.INVISIBLE);
        v.setTimer(mTimer);
        v.setKeepOn(mKeepOn);
        GroundSurface g = new GroundSurface(slot.getContext());
        g.setVisibility(View.INVISIBLE);
        // Both under the host's veil, which is the slot's last child; the ground under the page.
        slot.addView(g, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slot.addView(v, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mView = v;
        mGround = g;
        Ground.request(slot.getContext(), g);
        Xp.log(TAG + "page made");
        // Out of the host's pre-draw, which is where this is called from.
        mMain.post(() -> {
            if (mView == v) ImmersiveHost.contentChanged(this);
        });
    }

    @Override
    public boolean hasContent() {
        return mView != null;
    }

    @Override
    public void onShown(boolean shown, boolean dozing) {
        CountdownView v = mView;
        if (v == null) return;
        if (shown != mShown) {
            v.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
            if (mGround != null) mGround.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        }
        mShown = shown;
        mDozing = dozing;
        v.setDozing(dozing);
        updateHold();
    }

    @Override
    public void setFade(float alpha) {
        if (mView != null) mView.setFade(alpha);
        if (mGround != null) mGround.setFade(alpha);
    }

    @Override
    public void release() {
        // Every release, the dry run's included: a teardown can drop the ground's completion
        // post (refused while quiescing, or cleared by the sweep), and the gate would then stay
        // shut for the rest of the process. The reset bumps sSeq, so a blur still in flight
        // when the user closes the page lands on nothing instead of opening the gate twice.
        Ground.reset();
        CountdownView v = mView;
        if (v == null) return;
        mView = null;
        mShown = false;
        updateHold();
        v.setKeepScreenOn(false);
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
        GroundSurface g = mGround;
        mGround = null;
        if (g != null && g.getParent() instanceof ViewGroup) ((ViewGroup) g.getParent()).removeView(g);
        Xp.log(TAG + "released");
        ImmersiveHost.contentChanged(this);
    }

    /**
     * No beat: the seconds move in the doze too, and each redraw lets the display up for itself
     * (ImmersiveHost.lift), so the lift and the new second are one wake-up, not two out of step.
     */
    @Override
    public boolean needsDozeBeat() {
        return false;
    }

    /** Nothing blurred: the wallpaper is the page's ground. */
    @Override
    public float[] sharpBand() {
        return null;
    }

    @Override
    public boolean pageHit(float rawX, float rawY) {
        return mView != null && mView.pillHit(rawX, rawY);
    }

    @Override
    public void pagePress(boolean down) {
        if (mView != null) mView.setPillPressed(down);
    }

    @Override
    public void onPageTap() {
        mKeepOn = !mKeepOn;
        if (mView != null) mView.setKeepOn(mKeepOn);
        Xp.log(TAG + "屏幕常亮 " + (mKeepOn ? "on" : "off"));
        updateHold();
    }

    @Override
    public String describe() {
        LockIslands.Timer t = mTimer;
        return "key=" + mKey + (t == null ? "" : " type=" + t.getType() + " left="
                + t.elapsedMs(System.currentTimeMillis()) + "ms of " + t.getTotalMs())
                + " shown=" + mShown + " dozing=" + mDozing + " keepOn=" + mKeepOn
                + " holding=" + mHolding + " covered=" + mCovered
                + (mView == null ? "" : " " + mView.describe());
    }

    // ---------------------------------------------------------------- 屏幕常亮

    /**
     * Holds the lit lock screen while it is asked for, the page is up and the countdown is still
     * running. Every condition is re-read on every change of any of them; see the class comment
     * for why a doze is never one of them.
     */
    void updateHold() {
        CountdownView v = mView;
        LockIslands.Timer t = mTimer;
        boolean asked = mKeepOn && v != null && mShown && !mDozing && Main.screenOn()
                && v.screenLit() && t != null && t.getRunning()
                && t.elapsedMs(System.currentTimeMillis()) > 0L;
        watchProximity(asked);
        boolean want = asked && !mCovered;
        if (want == mHolding) return;
        mHolding = want;
        if (v != null) v.setKeepScreenOn(want);
        Xp.log(TAG + (want ? "holding the screen on" : "screen may sleep again"));
    }

    private SensorEventListener mProximity;
    /** The proximity sensor reads near: the phone is in a pocket or face down. */
    private boolean mCovered;

    /** Listens only while the screen is being held, so a sleeping phone keeps no sensor on. */
    private void watchProximity(boolean on) {
        if (on == (mProximity != null)) return;
        SensorManager sm = Main.sAppCtx == null ? null
                : Main.sAppCtx.getSystemService(SensorManager.class);
        if (sm == null) return;
        if (!on) {
            Gen.unsensorListener(sm, mProximity);
            mProximity = null;
            mCovered = false;
            return;
        }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (s == null) return;
        final float far = s.getMaximumRange();
        mProximity = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                boolean covered = e.values.length > 0 && e.values[0] < far;
                if (covered == mCovered) return;
                mCovered = covered;
                updateHold();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        Gen.sensorListener(sm, mProximity, s, SensorManager.SENSOR_DELAY_NORMAL, mMain);
    }

    // ---------------------------------------------------------------- the page

    /** The clock app's countdown page, one view, drawn. */
    static final class CountdownView extends View {

        /**
         * The disc is ColorOS's, not the clock app's dark one: its immersive countdown draws only
         * ic_dial_immersive_shadow.png (WaterClockView, water_circle_mode immersive), a sheet of
         * glass - 5% grey inside, so the blurred wallpaper is what shows, a white rim rising from
         * 14% to 38% towards the edge, a thin darker line where the rim starts, and a soft shadow
         * outside. Its radial profile, sampled from the xxxhdpi asset (1360px, the edge at 607px,
         * averaged over four directions) and drawn as one radial gradient, in the asset's own
         * units: radius, grey, alpha.
         */
        private static final float[] GLASS_R = {0, 420, 480, 540, 550, 558, 560, 562, 564, 566,
                568, 570, 574, 580, 590, 600, 604, 606, 608, 610, 620, 630, 640, 650, 660, 668};
        private static final int[] GLASS_GREY = {158, 158, 164, 184, 192, 201, 208, 220, 197, 61,
                150, 228, 219, 224, 231, 239, 243, 246, 130, 16, 22, 33, 34, 25, 14, 0};
        private static final int[] GLASS_ALPHA = {13, 13, 14, 18, 20, 22, 28, 40, 58, 71, 68, 48,
                32, 36, 45, 64, 80, 97, 72, 22, 17, 11, 5, 3, 1, 0};
        private static final float GLASS_EDGE = 607f;
        /** How far the shadow reaches past the disc's edge, and where the rim's line is. */
        private static final float GLASS_EDGE_OUT = 668f / GLASS_EDGE;
        private static final float GLASS_LINE = 566f / GLASS_EDGE;
        private static final float[] GLASS_STOPS = new float[GLASS_R.length];
        private static final int[] GLASS_COLORS = new int[GLASS_R.length];

        static {
            for (int i = 0; i < GLASS_R.length; i++) {
                GLASS_STOPS[i] = GLASS_R[i] / 668f;
                int g = GLASS_GREY[i];
                GLASS_COLORS[i] = android.graphics.Color.argb(GLASS_ALPHA[i], g, g, g);
            }
        }
        private static final int BLUE = 0xff277af7;
        private static final int GONE = 0x11ffffff;
        private static final int DIGITS = 0xf2ffffff;
        private static final int DURATION = 0x80ffffff;
        private static final int PILL_ON_BG = 0xff277af7;
        private static final int PILL_ON_TEXT = 0xe6ffffff;
        private static final int PILL_OFF_BG = 0x14ffffff;
        private static final int PILL_OFF_TEXT = 0xccffffff;

        /** The disc's centre, as a share of the height. */
        private static final float CENTRE = 0.47f;

        private final CountdownScene mScene;
        private final float mDp;
        private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private Shader mGlass;
        private float mGlassR, mGlassCx, mGlassCy;
        private final Paint mRemain = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDigits = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDuration = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mOval = new RectF();
        private final RectF mPill = new RectF();
        private final int[] mAt = new int[2];

        private Drawable mIconOn;
        private Drawable mIconOff;
        private String mKeepOnText;
        private String mEndText;
        private Resources mClockRes;

        private LockIslands.Timer mTimer;
        private boolean mKeepOn;
        private boolean mPillPressed;
        private boolean mDozing;
        private boolean mScreenLit = true;

        /** The disc's centre and radius. */
        private float mCx = Float.NaN;
        private float mCy = Float.NaN;
        private float mR;

        private String mDurationText = "";
        private long mDurationFor = -1L;

        CountdownView(Context ctx, CountdownScene scene) {
            super(ctx);
            mScene = scene;
            mKeepOnText = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(ctx, "hmcKeepScreenOn", "屏幕常亮");
            mEndText = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(ctx, "hmcTimerEnded", "计时结束");
            mDp = ctx.getResources().getDisplayMetrics().density;
            mRemain.setStyle(Paint.Style.STROKE);
            mRemain.setColor(BLUE);
            mGone.setStyle(Paint.Style.STROKE);
            mGone.setColor(GONE);
            mDot.setStyle(Paint.Style.FILL_AND_STROKE);
            mDot.setColor(BLUE);
            mDigits.setColor(DIGITS);
            mDigits.setTextAlign(Paint.Align.CENTER);
            mDigits.setFontFeatureSettings("tnum");
            mDuration.setColor(DURATION);
            mDuration.setTextAlign(Paint.Align.CENTER);
            mPillText.setTextAlign(Paint.Align.LEFT);
            loadClockResources(ctx);
        }

        /**
         * The app's own font, icon and words, from its package: its page as it draws it, in the
         * phone's language. Each has a stand-in if the app is not what it was.
         */
        private void loadClockResources(Context ctx) {
            Typeface digits = null;
            try {
                Context clock = ctx.createPackageContext(PKG, 0);
                mClockRes = clock.getResources();
                try {
                    digits = Typeface.createFromAsset(clock.getAssets(), "fonts/Mitype2019-60.ttf");
                } catch (Throwable t) {
                    Xp.log(TAG + "no clock font: " + t);
                }
                mIconOn = clockDrawable(clock, "timer_screen_on_icon");
                mIconOff = clockDrawable(clock, "timer_screen_off_icon");
                String on = clockString("timer_keep_screen_on");
                if (on != null) mKeepOnText = on;
                String end = clockString("timer_end_island");
                if (end != null) mEndText = end;
            } catch (Throwable t) {
                Xp.w(TAG + "clock app resources unavailable: " + t);
            }
            mDigits.setTypeface(digits != null ? digits : Typeface.create("sans-serif", Typeface.BOLD));
            mDuration.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mPillText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        private Drawable clockDrawable(Context clock, String name) {
            try {
                int id = clock.getResources().getIdentifier(name, "drawable", PKG);
                return id == 0 ? null : clock.getDrawable(id);
            } catch (Throwable t) {
                return null;
            }
        }

        private String clockString(String name) {
            try {
                int id = mClockRes.getIdentifier(name, "string", PKG);
                return id == 0 ? null : mClockRes.getString(id);
            } catch (Throwable t) {
                return null;
            }
        }

        void setTimer(LockIslands.Timer timer) {
            mTimer = timer;
            invalidate();
        }

        void setKeepOn(boolean on) {
            mKeepOn = on;
            invalidate();
        }

        void setPillPressed(boolean pressed) {
            mPillPressed = pressed;
            invalidate();
        }

        void setDozing(boolean dozing) {
            if (mDozing == dozing) return;
            mDozing = dozing;
            removeCallbacks(mRedraw);
            removeCallbacks(mDozeTick);
            invalidate();
        }

        boolean screenLit() {
            return mScreenLit;
        }

        /**
         * The window's screen going off or on - the earliest this process hears of it, ahead of
         * the broadcast Main.screenOn() follows: the hold must be gone before the doze starts.
         */
        @Override
        public void onScreenStateChanged(int screenState) {
            super.onScreenStateChanged(screenState);
            mScreenLit = screenState == SCREEN_STATE_ON;
            mScene.updateHold();
        }

        /** The page's opacity. */
        void setFade(float alpha) {
            float last = mLastFade;
            mLastFade = alpha;
            setAlpha(alpha);
            if (alpha <= 0f) {
                // Out of sight: where the next appearance grows from.
                stopScale();
                setScale(ENTER_SCALE);
                mScaleTo = ENTER_SCALE;
            } else if (alpha >= 1f && last <= 0f) {
                // Nothing to full in one step - a wake, a relock - is a cut, not a fade.
                stopScale();
                setScale(1f);
                mScaleTo = 1f;
            } else if (alpha > last) {
                if (mScaleTo != 1f) scaleTo(1f);
            } else if (alpha < last) {
                if (mScaleTo != ENTER_SCALE) scaleTo(ENTER_SCALE);
            } else if (alpha >= 1f && mScaleAnim == null && getScaleX() != 1f) {
                // Shown at full at once - a wake, a relock: no growing.
                setScale(1f);
                mScaleTo = 1f;
            }
        }

        /**
         * The disc and its words grow out of the middle of the screen as they fade in, and shrink
         * back into it as they fade out: ColorOS's host does this to the countdown's whole page
         * surface (SystemUIPlugin a6.l, the SurfaceViewHolder: enterContent springs its scale
         * from 0.4 to 1 - 1.1 for the navigation map - about the surface's centre, the screen's,
         * with its alpha from 0 to 1; exitContent back to 0.4 and 0). The blurred wallpaper is not
         * in that surface there and is not scaled here.
         *
         * The spring is the plugin's (a6.k, case 1, on its androidx.dynamicanimation SpringForce
         * whose d() is the bounce - damping ratio 1 - d - and e() the response, w = 2 pi / e):
         * bounce 0.2 for the countdown (0 for the map), response 0.45s.
         */
        private static final float ENTER_SCALE = 0.4f;
        private static final float SCALE_RESPONSE = 0.45f;
        private static final float SCALE_BOUNCE = 0.2f;

        private float mLastFade = 1f;
        private float mScaleTo = 1f;
        private android.animation.ValueAnimator mScaleAnim;

        private void setScale(float s) {
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            setScaleX(s);
            setScaleY(s);
        }

        private void stopScale() {
            android.animation.ValueAnimator a = mScaleAnim;
            mScaleAnim = null;
            if (a != null) a.cancel();
        }

        /** From where it is, at rest, on the plugin's spring (PageSpring). */
        private void scaleTo(float to) {
            stopScale();
            mScaleTo = to;
            final float from = getScaleX();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(PageSpring.durationMs(SCALE_RESPONSE, SCALE_BOUNCE));
            a.setInterpolator(PageSpring.interpolator(SCALE_RESPONSE, SCALE_BOUNCE));
            a.addUpdateListener(an -> {
                if (mScaleAnim != an) return;
                setScale(from + (to - from) * (float) an.getAnimatedValue());
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    if (mScaleAnim == an) {
                        mScaleAnim = null;
                        setScale(to);
                    }
                }
            });
            mScaleAnim = a;
            a.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            place();
        }

        /** Whether a finger here, in screen coordinates, is on the pill. */
        boolean pillHit(float rawX, float rawY) {
            if (mPill.isEmpty() || !isShown()) return false;
            getLocationOnScreen(mAt);
            float pad = 8f * mDp;
            float x = rawX - mAt[0];
            float y = rawY - mAt[1];
            return x >= mPill.left - pad && x <= mPill.right + pad
                    && y >= mPill.top - pad && y <= mPill.bottom + pad;
        }

        String describe() {
            return "disc=" + Math.round(mCx) + "," + Math.round(mCy) + " r=" + Math.round(mR)
                    + " pill=" + mPill.toShortString();
        }

        // ------------------------------------------------------------ where the disc goes

        /** The app's disc, smaller only where the screen is narrower than it and a margin. */
        private void place() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            mR = Math.min(152f * mDp, (w - 2f * 24f * mDp) / 2f);
            mCx = w / 2f;
            mCy = h * CENTRE;
        }

        // ------------------------------------------------------------ drawing

        @Override
        protected void onDraw(Canvas canvas) {
            if (Float.isNaN(mCy)) place();
            if (Float.isNaN(mCy)) return;
            LockIslands.Timer t = mTimer;
            long now = System.currentTimeMillis();
            long total = t == null ? 0L : t.getTotalMs();
            long left = t == null ? 0L : t.elapsedMs(now);
            boolean over = t != null && left <= 0L;
            float s = mR / (152f * mDp);
            float cx = mCx;
            float cy = mCy;

            // The disc: ColorOS's glass, the blurred wallpaper through it. See GLASS_*.
            if (mGlass == null || mGlassR != mR || mGlassCx != cx || mGlassCy != cy) {
                mGlass = new RadialGradient(cx, cy, mR * GLASS_EDGE_OUT, GLASS_COLORS, GLASS_STOPS,
                        Shader.TileMode.CLAMP);
                mGlassR = mR;
                mGlassCx = cx;
                mGlassCy = cy;
            }
            mBg.setShader(mGlass);
            canvas.drawCircle(cx, cy, mR * GLASS_EDGE_OUT, mBg);

            // The ring: the app's 4dp, 8dp in - from the glass's inner line, clear of its rim.
            float stroke = 4f * mDp * s;
            float inset = mR * (1f - GLASS_LINE) + 8f * mDp * s;
            mOval.set(cx - mR + inset, cy - mR + inset, cx + mR - inset, cy + mR - inset);
            mRemain.setStrokeWidth(stroke);
            mGone.setStrokeWidth(stroke);
            if (over || total <= 0L) {
                canvas.drawOval(mOval, mGone);
            } else {
                long done = Math.min(Math.max(total - left, 0L), total);
                float f = done * 360f / total;
                if (f >= 357f) {
                    canvas.drawArc(mOval, -93f - f, 0f, false, mRemain);
                } else if (f >= 3f) {
                    canvas.drawArc(mOval, -93f - f, f - 357f, false, mRemain);
                } else {
                    canvas.drawArc(mOval, -93f - f, -354f, false, mRemain);
                }
                if (f >= 3f) canvas.drawArc(mOval, -90f, -f + 3f, false, mGone);
                canvas.save();
                canvas.rotate(-f, cx, cy);
                canvas.drawCircle(cx, mOval.top, 3.5f * mDp * s, mDot);
                canvas.restore();
            }

            // The words, one column centred on the disc as the app's LinearLayout is.
            mDigits.setTextSize(54f * mDp * s);
            mDuration.setTextSize(16f * mDp * s);
            mDuration.setColor(over ? BLUE : DURATION);
            mPillText.setTextSize(12f * mDp * s);
            Paint.FontMetrics fd = mDigits.getFontMetrics();
            Paint.FontMetrics fl = mDuration.getFontMetrics();
            Paint.FontMetrics fp = mPillText.getFontMetrics();
            float hDigits = fd.bottom - fd.top;
            float hLine = 4f * mDp * s + (fl.bottom - fl.top);
            float iconH = 14f * mDp * s;
            float iconW = 15f * mDp * s;
            float textH = fp.bottom - fp.top;
            float pillH = Math.max(iconH, textH) + 16f * mDp * s;
            float hPill = 20f * mDp * s + pillH;
            float y = cy - (hDigits + hLine + hPill) / 2f;

            canvas.drawText(digits(left), cx, y - fd.top, mDigits);
            y += hDigits + 4f * mDp * s;
            canvas.drawText(over ? mEndText : duration(total), cx, y - fl.top, mDuration);
            y += fl.bottom - fl.top + 20f * mDp * s;

            // The pill.
            float textW = mPillText.measureText(mKeepOnText);
            float pillW = 14f * mDp * s + iconW + 3f * mDp * s + textW + 14f * mDp * s;
            mPill.set(cx - pillW / 2f, y, cx + pillW / 2f, y + pillH);
            canvas.save();
            if (mPillPressed) canvas.scale(0.92f, 0.92f, mPill.centerX(), mPill.centerY());
            mPillBg.setColor(mKeepOn ? PILL_ON_BG : PILL_OFF_BG);
            float radius = Math.min(50f * mDp, pillH / 2f);
            canvas.drawRoundRect(mPill, radius, radius, mPillBg);
            float ix = mPill.left + 14f * mDp * s;
            Drawable icon = mKeepOn ? mIconOn : (mIconOff != null ? mIconOff : mIconOn);
            if (icon != null) {
                int it = Math.round(mPill.centerY() - iconH / 2f);
                icon.setBounds(Math.round(ix), it, Math.round(ix + iconW), Math.round(it + iconH));
                icon.draw(canvas);
            }
            mPillText.setColor(mKeepOn ? PILL_ON_TEXT : PILL_OFF_TEXT);
            float tx = ix + iconW + 3f * mDp * s;
            canvas.drawText(mKeepOnText, tx, mPill.centerY() - (fp.ascent + fp.descent) / 2f,
                    mPillText);
            canvas.restore();

            scheduleNext(t, total, left, now);
        }

        /**
         * The next frame: at the next second for the digits, sooner when the ring moves a pixel
         * before then - the app's own rule (TimerProgressView.recomputeRefreshInterval), a frame
         * per pixel of the ring's circumference, at most once a second.
         */
        private void scheduleNext(LockIslands.Timer t, long total, long left, long now) {
            // One pending at a time, either way: a lift's fallback frame and its real one both
            // come through here.
            removeCallbacks(mRedraw);
            removeCallbacks(mDozeTick);
            if (t == null || !t.getRunning() || left <= 0L || !isShown()) return;
            float circumference = (float) (2 * Math.PI * mR);
            long perPixel = circumference > 0f ? (long) (total / circumference) : 16L;
            long toSecond = left % 1000L;
            if (toSecond == 0L) toSecond = 1000L;
            if (mDozing) {
                postDelayed(mDozeTick, toSecond + 5L);
                return;
            }
            postDelayed(mRedraw, Math.max(16L, Math.min(Math.min(perPixel, 1000L), toSecond + 5L)));
        }

        private final Runnable mRedraw = new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        };

        /**
         * The next second in the doze: the host lets the display up and asks for the frame once
         * it is. Not up - the AOD gone dark, the page not shown - is looked at again a second on,
         * since no frame is coming to carry on from.
         */
        private final Runnable mDozeTick = new Runnable() {
            @Override
            public void run() {
                // Hidden, or out of the doze: whatever shows it again draws, and that carries on.
                if (!mDozing || !isShown()) return;
                if (!ImmersiveHost.lift(CountdownView.this)) postDelayed(this, 1000L);
            }
        };

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(mRedraw);
            removeCallbacks(mDozeTick);
            super.onDetachedFromWindow();
        }

        /** HH:MM:SS, the seconds rounded up as the app does: 0.4s left reads 00:00:01. */
        private static String digits(long left) {
            long ms = left;
            if (ms % 1000L > 0L) ms = (ms / 1000L + 1L) * 1000L;
            long sec = ms / 1000L;
            return String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600L, (sec / 60L) % 60L,
                    sec % 60L);
        }

        /** 「共1小时」: the app's Util.formatTimerDuration, with its own words. */
        private String duration(long total) {
            if (total == mDurationFor) return mDurationText;
            mDurationFor = total;
            long h = (total / 3600000L) % 24L;
            long m = (total / 60000L) % 60L;
            long s = (total / 1000L) % 60L;
            int bits = (h > 0 ? 1 : 0) | (m > 0 ? 2 : 0) | (s > 0 ? 4 : 0);
            String text = "";
            if (bits != 0) {
                String hs = h > 0 ? plural("hour", (int) h, "小时") : "";
                String ms = m > 0 ? plural("minute", (int) m, "分钟") : "";
                String ss = s > 0 ? plural("second", (int) s, "秒") : "";
                String pattern = null;
                try {
                    int id = mClockRes == null ? 0
                            : mClockRes.getIdentifier("timer_duration", "array", PKG);
                    if (id != 0) pattern = mClockRes.getStringArray(id)[bits - 1];
                } catch (Throwable ignored) {
                }
                if (pattern == null) pattern = btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(getContext(), "hmcTimerTotal", "共%1$s%2$s%3$s");
                try {
                    text = String.format(pattern, hs, ms, ss);
                } catch (Throwable e) {
                    text = String.format(btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(getContext(), "hmcTimerTotal", "共%1$s%2$s%3$s"), hs, ms, ss);
                }
            }
            mDurationText = text;
            return text;
        }

        private String plural(String name, int n, String unit) {
            try {
                int id = mClockRes == null ? 0 : mClockRes.getIdentifier(name, "plurals", PKG);
                if (id != 0) return mClockRes.getQuantityString(id, n, n);
            } catch (Throwable ignored) {
            }
            return String.format(btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.tr(getContext(), "hmcUnit_" + name, "%1$d" + unit), n);
        }
    }

    /**
     * The blurred wallpaper as a surface of its own under the shade window, where the map's page
     * is: a SurfaceView at its default z-order is composited below its window.
     *
     * Drawn in the window first, and the countdown's own row looked see-through on it, "as if it
     * had changed its liquid glass colour" (2026-09-30): the lock screen's glass and card blur
     * sample what is BEHIND the shade window - the real, sharp wallpaper there - and never a view
     * inside it (see the hyperos-wallpaper-is-a-separate-window notes). Under the window, the
     * blurred picture is what they sample, as they sample the map. ColorOS does the same from the
     * other side: its blurred picture is put under the lock wallpaper's window
     * (attachSurfaceToWallpaper).
     *
     * TRANSLUCENT, so its fade shows the wallpaper rather than the black an opaque SurfaceView is
     * backed with; faded by its SurfaceControl's alpha, as LiveAlertScene fades the map.
     */
    public static final class GroundSurface extends android.view.SurfaceView
            implements android.view.SurfaceHolder.Callback {

        /** Over the blurred wallpaper, so the ring and the words read on a bright one. */
        private static final int SHADE = 0x1a000000;

        private android.graphics.Bitmap mBitmap;
        private float mFade = 1f;
        private final android.view.SurfaceControl.Transaction mTx =
                new android.view.SurfaceControl.Transaction();
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF mRect = new RectF();

        public GroundSurface(Context ctx) {
            super(ctx);
            getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            getHolder().addCallback(this);
        }

        public void setBitmap(android.graphics.Bitmap bitmap) {
            mBitmap = bitmap;
            paint();
        }

        public void setFade(float alpha) {
            if (mFade == alpha) return;
            mFade = alpha;
            setAlpha(alpha);
            applyFade();
        }

        private void applyFade() {
            try {
                android.view.SurfaceControl sc = getSurfaceControl();
                if (sc != null && sc.isValid()) mTx.setAlpha(sc, mFade).apply();
            } catch (Throwable t) {
                Xp.log(TAG + "ground fade not applied: " + t);
            }
        }

        /** The picture into the surface, once per picture and per surface. */
        private void paint() {
            android.view.SurfaceHolder h = getHolder();
            android.view.Surface s = h.getSurface();
            if (s == null || !s.isValid()) return;
            Canvas c;
            try {
                c = h.lockHardwareCanvas();
            } catch (Throwable t) {
                c = null;
            }
            if (c == null) return;
            try {
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                android.graphics.Bitmap b = mBitmap;
                if (b != null && !b.isRecycled()) {
                    mRect.set(0f, 0f, c.getWidth(), c.getHeight());
                    c.drawBitmap(b, null, mRect, mPaint);
                    c.drawColor(SHADE);
                }
            } finally {
                h.unlockCanvasAndPost(c);
            }
        }

        @Override
        public void surfaceCreated(android.view.SurfaceHolder holder) {
            // A new surface starts opaque; a fade may be under way.
            applyFade();
            paint();
        }

        @Override
        public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
            paint();
        }

        @Override
        public void surfaceDestroyed(android.view.SurfaceHolder holder) {
        }
    }

    /**
     * The blurred lock wallpaper, one per wallpaper: made on a thread of its own, kept while the
     * lock wallpaper's id is the same. Read from the lock slot's file, else the home one's (a lock
     * screen following the home wallpaper), else nothing - a video wallpaper has no still to blur,
     * and the page then stands on the live wallpaper.
     */
    static final class Ground {
        private static android.graphics.Bitmap sBitmap;
        private static int sId = Integer.MIN_VALUE;
        private static boolean sBusy;
        private static java.lang.ref.WeakReference<GroundSurface> sWaiting;
        /** Which wallpaper the waiting view asked for; the reply carries its own id. */
        private static int sWaitingId = Integer.MIN_VALUE;
        /** Bumped by [reset] so a completion that is already on its way is dropped. */
        private static int sSeq;

        private Ground() {
        }

        /**
         * Retirement: the build's own Gen.post is what clears sBusy, and during a teardown that
         * post is refused (quiescing) or swept - leaving the flag set for the rest of the
         * process. Nothing is held any more, so the gate opens with the page gone.
         */
        static void reset() {
            sSeq++;
            sBusy = false;
            sWaiting = null;
        }

        /** Main thread. Hands the view the picture now, or when it is made. */
        static void request(Context ctx, GroundSurface view) {
            final int w = view.getResources().getDisplayMetrics().widthPixels;
            final int h = view.getResources().getDisplayMetrics().heightPixels;
            final android.app.WallpaperManager wm =
                    ctx.getSystemService(android.app.WallpaperManager.class);
            int id;
            try {
                id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK);
            } catch (Throwable t) {
                id = Integer.MIN_VALUE + 1;
            }
            if (sBitmap != null && id == sId) {
                view.setBitmap(sBitmap);
                return;
            }
            sWaiting = new java.lang.ref.WeakReference<>(view);
            sWaitingId = id;
            if (sBusy) return;
            sBusy = true;
            final int wantId = id;
            final int seq = ++sSeq;
            Gen.thread("mc-countdown-ground", () -> {
                long t0 = SystemClock.uptimeMillis();
                android.graphics.Bitmap made = null;
                try {
                    android.graphics.Bitmap src = read(wm);
                    if (src != null) {
                        android.graphics.Bitmap cropped = crop(src, w, h);
                        // A little lighter than the cover's 48 (2026-09-30: "变清晰一点点").
                        made = CoverCompose.blur(cropped, 64, 4, 3);
                    }
                } catch (Throwable t) {
                    Xp.w(TAG + "wallpaper blur failed: " + t);
                }
                final android.graphics.Bitmap done = made;
                final long ms = SystemClock.uptimeMillis() - t0;
                Gen.post(() -> {
                    // A reset (a retirement took the page down) superseded this one; the gate
                    // belongs to whoever is waiting now.
                    if (seq != sSeq) return;
                    sBusy = false;
                    sBitmap = done;
                    sId = wantId;
                    Xp.log(TAG + (done == null ? "no wallpaper to blur"
                            : "wallpaper blurred " + done.getWidth() + "x" + done.getHeight())
                            + " in " + ms + "ms");
                    GroundSurface v = sWaiting == null ? null : sWaiting.get();
                    sWaiting = null;
                    if (v == null || done == null) return;
                    if (sWaitingId == wantId) {
                        v.setBitmap(done);
                        return;
                    }
                    // The page is waiting for another wallpaper than the one just made: this
                    // picture would be the wrong one on it, so ask again for what it wants.
                    request(v.getContext(), v);
                });
            });
        }

        /** The lock slot's picture, else the home slot's, decoded at a quarter: it is to be blurred. */
        @android.annotation.SuppressLint("MissingPermission")
        private static android.graphics.Bitmap read(android.app.WallpaperManager wm) {
            int[] flags = {android.app.WallpaperManager.FLAG_LOCK,
                    android.app.WallpaperManager.FLAG_SYSTEM};
            for (int flag : flags) {
                try (android.os.ParcelFileDescriptor fd = wm.getWallpaperFile(flag)) {
                    if (fd == null) continue;
                    android.graphics.BitmapFactory.Options o =
                            new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFileDescriptor(
                            fd.getFileDescriptor(), null, o);
                    if (b != null) return b;
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        /** The middle of the picture at the screen's shape, as a still wallpaper is shown. */
        private static android.graphics.Bitmap crop(android.graphics.Bitmap src, int w, int h) {
            int sw = src.getWidth();
            int sh = src.getHeight();
            if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return src;
            float want = w / (float) h;
            if (sw / (float) sh > want) {
                int cw = Math.max(1, Math.round(sh * want));
                return android.graphics.Bitmap.createBitmap(src, (sw - cw) / 2, 0, cw, sh);
            }
            int ch = Math.max(1, Math.round(sw / want));
            return android.graphics.Bitmap.createBitmap(src, 0, (sh - ch) / 2, sw, ch);
        }
    }
}
