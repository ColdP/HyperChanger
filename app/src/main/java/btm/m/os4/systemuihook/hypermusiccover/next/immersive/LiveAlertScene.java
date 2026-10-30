// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.immersive;
import btm.m.os4.systemuihook.hypermusiccover.next.Gen;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.PageSpring;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * An immersive page drawn by another app, through ColorOS 16's LiveAlert protocol: the app
 * renders into a SurfaceControlViewHost in its own process and hands the SurfacePackage back; the
 * host puts it in a SurfaceView. The system draws nothing of it. A scene for one app is this with
 * the app's service and card id, and whatever tells it the app is ready (see AmapNavScene).
 *
 * The protocol, as ColorOS's SystemUIPlugin speaks it (IntentMessenger, class z5.h), over a
 * Messenger the app's onBind returns:
 *   11  host -> app  data{hostToken: SurfaceView.getHostToken(), extra{
 *                         livealert.immersive.display{width, height, orientation, infoBounds},
 *                         livealert.immersive.card{cardKey}}}
 *   12  host -> app  the same without the token, plus event=1: the size changed
 *   13  host -> app  data{state}: 1 shown / 2 hidden / 3, 4 starting to show / hide
 *   14  host -> app  data{hostToken}: let go
 *   21  app -> host  data{SurfacePackage}, sent once the page has rendered
 *
 * The token is SurfaceView.getHostToken() and nothing else: an IBinder (the window's input token),
 * which is what the plugin puts in the bundle and what the apps read back with getBinder. It
 * exists once the window does, so the page can be asked for while the SurfaceView is still hidden
 * and has no surface; the SurfaceView re-parents the package when its surface is made.
 *
 * Hidden with INVISIBLE, which gives the surface up where nothing sees it; faded by the alpha of the
 * SurfaceView's own SurfaceControl, which the app's layers hang under. For the fade to show what is
 * behind rather than black, the surface is TRANSLUCENT: an OPAQUE SurfaceView below its window is
 * backed by an opaque black layer (SurfaceView.updateBackgroundVisibility shows it only for an
 * opaque format), and ours never draws a pixel of its own - the app's page is a child layer.
 */
public class LiveAlertScene implements ImmersiveScene {

    private static final int MSG_BIND = 11;
    private static final int MSG_RESIZE = 12;
    private static final int MSG_STATE = 13;
    private static final int MSG_UNBIND = 14;
    private static final int MSG_SURFACE = 21;

    /**
     * infoBounds as fractions of the page: ColorOS measured Rect(56, 500, 1024, 1518) on a
     * 1080x2160 surface. The app keeps its information inside; the lock screen's clock sits above
     * it and its cards below, and that is where the host blurs.
     */
    private static final float INFO_SIDE = 0.052f;
    private static final float INFO_TOP = 0.231f;
    private static final float INFO_BOTTOM = 0.703f;
    /** criticalBounds: the plugin centres it at 0.6 of the screen's height... */
    private static final float CRITICAL_Y = 0.6f;
    /** ...50px either way on its 1080px-wide screen. */
    private static final float CRITICAL_HALF = 50f / 1080f;

    private final String mId;
    private final String mTag;
    private final String mPkg;
    private final String mService;
    private final String mCardId;

    private boolean mArmed;
    private Context mCtx;
    private SurfaceView mSurface;
    private Messenger mServer;
    private ServiceConnection mConn;
    private String mCardKey;
    private boolean mBindSent;
    private boolean mContent;
    private boolean mShown;
    private float mFade = 1f;
    private final SurfaceControl.Transaction mTx = new SurfaceControl.Transaction();
    private long mStartedAt;
    private int mReplies;

    /**
     * @param pkg     the app, which is also the package of the focus island that opens it
     * @param service its immersive service's class
     * @param cardId  its LiveAlert card id on ColorOS; the apps do not read it, but the plugin
     *                always sends one
     */
    public LiveAlertScene(String id, String pkg, String service, String cardId) {
        mId = id;
        mTag = "MCImmersive: " + id + ": ";
        mPkg = pkg;
        mService = service;
        mCardId = cardId;
    }

    @Override
    public String id() {
        return mId;
    }

    @Override
    public boolean serves(String pkg, boolean focus) {
        return focus && mPkg.equals(pkg);
    }

    @Override
    public boolean ready() {
        return mArmed;
    }

    /** The app says its page can be drawn, or can no longer be. */
    public void setArmed(boolean armed) {
        if (mArmed == armed) return;
        mArmed = armed;
        ImmersiveHost.readyChanged(this);
    }

    @Override
    public boolean hasContent() {
        return mContent;
    }

    @Override
    public boolean needsDozeBeat() {
        return true;
    }

    // ---------------------------------------------------------------- the page

    @Override
    public void prepare(ViewGroup slot) {
        if (mSurface != null) return;
        mCtx = slot.getContext().getApplicationContext();
        mReplies = 0;
        mBindSent = false;
        mContent = false;
        mShown = false;
        mCardKey = mCardId + "||0|" + System.currentTimeMillis();
        SurfaceView sv = new SurfaceView(slot.getContext()) {
            @Override
            protected void onConfigurationChanged(Configuration config) {
                super.onConfigurationChanged(config);
                forwardConfiguration(config);
            }
        };
        // onShown hides the page with INVISIBLE, which by default destroys the surface and makes
        // a new one on the next show: a run of taps on the islands made ~90 surfaces in 75s and
        // doubled SurfaceFlinger's load (trace 2026-09-30). Kept for as long as the view is
        // attached, INVISIBLE only hides it.
        sv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        sv.setVisibility(View.INVISIBLE);
        sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // A new surface starts opaque; a fade may be under way.
                applyFade();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (mBindSent) send(MSG_RESIZE, resizeData());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
            }
        });
        // Under the host's veil, which is the slot's last child.
        slot.addView(sv, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSurface = sv;
        bind();
    }

    @Override
    public void onShown(boolean shown, boolean dozing) {
        SurfaceView sv = mSurface;
        if (sv == null || mShown == shown) return;
        mShown = shown;
        sv.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        if (mContent) send(MSG_STATE, stateData(shown ? 1 : 2));
    }

    @Override
    public float[] sharpBand() {
        return new float[] {INFO_TOP, INFO_BOTTOM};
    }

    @Override
    public void setFade(float alpha) {
        if (mFade == alpha) return;
        float last = mFade;
        mFade = alpha;
        applyFade();
        followFade(last, alpha);
    }

    // ---------------------------------------------------------------- the page's scale

    /**
     * Where the app's page grows or shrinks from as it fades in, and back to as it fades out;
     * 1 is none. ColorOS's host scales the page surface itself (SystemUIPlugin a6.l,
     * enterContent/exitContent): 1.1 for the navigation map, 0.4 for the countdown.
     */
    protected float enterScale() {
        return 1f;
    }

    private static final float SCALE_RESPONSE = 0.45f;
    /** The map's scale spring has no bounce (a6.k: d(0) for type 1). */
    private static final float SCALE_BOUNCE = 0f;

    private float mScale = 1f;
    private float mScaleTo = 1f;
    private android.animation.ValueAnimator mScaleAnim;
    private SurfaceControlViewHost.SurfacePackage mPackage;
    private final SurfaceControl.Transaction mScaleTx = new SurfaceControl.Transaction();

    /** The scale follows the fade's direction, as the host's springs both start on one call. */
    private void followFade(float last, float alpha) {
        float from = enterScale();
        if (from == 1f) return;
        if (alpha <= 0f) {
            stopScale();
            mScaleTo = from;
            applyScale(from);
        } else if (alpha >= 1f && last <= 0f) {
            // Nothing to full in one step - a wake, a relock - is a cut, not a fade.
            stopScale();
            mScaleTo = 1f;
            applyScale(1f);
        } else if (alpha > last) {
            if (mScaleTo != 1f) scaleTo(1f);
        } else if (alpha < last) {
            if (mScaleTo != from) scaleTo(from);
        }
    }

    private void stopScale() {
        android.animation.ValueAnimator a = mScaleAnim;
        mScaleAnim = null;
        if (a != null) a.cancel();
    }

    private void scaleTo(final float to) {
        stopScale();
        mScaleTo = to;
        final float from = mScale;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(PageSpring.durationMs(SCALE_RESPONSE, SCALE_BOUNCE));
        a.setInterpolator(PageSpring.interpolator(SCALE_RESPONSE, SCALE_BOUNCE));
        a.addUpdateListener(an -> {
            if (mScaleAnim == an) applyScale(from + (to - from) * (float) an.getAnimatedValue());
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (mScaleAnim != an) return;
                mScaleAnim = null;
                applyScale(to);
            }
        });
        mScaleAnim = a;
        a.start();
    }

    /**
     * The page surface scaled about the SurfaceView's centre, as a6.l.A does it: the package's
     * own SurfaceControl, which the SurfaceView parents and leaves where it is put.
     */
    private void applyScale(float s) {
        mScale = s;
        SurfaceView sv = mSurface;
        SurfaceControl sc = packageControl();
        if (sv == null || sc == null || !sc.isValid()) return;
        float cx = sv.getWidth() / 2f;
        float cy = sv.getHeight() / 2f;
        try {
            mScaleTx.setScale(sc, s, s);
            mScaleTx.setPosition(sc, cx * (1f - s), cy * (1f - s));
            commit(sv, mScaleTx);
        } catch (Throwable t) {
            Xp.log(mTag + "scale not applied: " + t);
        }
    }

    /**
     * With the frame the window draws next, not on its own: ColorOS's host hands every one of
     * these to ViewRootImpl.applyTransactionOnDraw (SystemUIPlugin a6.l.s), so a fade step goes
     * to SurfaceFlinger in the window's own transaction rather than as one more beside it, every
     * frame of the fade, and lands on the frame it was worked out for. The screen off - the
     * AOD's once-a-second frames, or none - it goes at once: waiting on a frame there could hold
     * a fade for a second.
     */
    private static void commit(SurfaceView sv, SurfaceControl.Transaction t) {
        android.view.AttachedSurfaceControl root = sv.getRootSurfaceControl();
        if (root != null && Main.screenOnCached()) {
            try {
                if (root.applyTransactionOnDraw(t)) {
                    sv.invalidate();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        t.apply();
    }

    private SurfaceControl packageControl() {
        SurfaceControlViewHost.SurfacePackage p = mPackage;
        if (p == null) return null;
        try {
            return (SurfaceControl) p.getClass().getMethod("getSurfaceControl").invoke(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Both halves of the alpha: the view's, which SurfaceView carries onto its surface in its own
     * updates, and the SurfaceControl's directly, so the frame this is called in has it whether or
     * not the view updates its surface on it.
     */
    private void applyFade() {
        SurfaceView sv = mSurface;
        if (sv == null) return;
        sv.setAlpha(mFade);
        try {
            SurfaceControl sc = sv.getSurfaceControl();
            if (sc != null && sc.isValid()) commit(sv, mTx.setAlpha(sc, mFade));
        } catch (Throwable t) {
            Xp.log(mTag + "fade not applied: " + t);
        }
    }

    @Override
    public void release() {
        SurfaceView sv = mSurface;
        if (sv == null) return;
        IBinder token = sv.getHostToken();
        if (mServer != null && token != null) {
            Bundle data = new Bundle();
            data.putBinder("hostToken", token);
            data.putBundle("extra", cardExtra());
            send(MSG_UNBIND, data);
        }
        try {
            if (mConn != null) mCtx.unbindService(mConn);
        } catch (Throwable t) {
            Xp.log(mTag + "unbind: " + t);
        }
        mConn = null;
        mServer = null;
        // Android 16's; before it, removing the view below is what lets the package go.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.BAKLAVA) {
            try {
                sv.clearChildSurfacePackage();
            } catch (Throwable ignored) {
            }
        }
        ViewGroup parent = (ViewGroup) sv.getParent();
        if (parent != null) parent.removeView(sv);
        mSurface = null;
        mShown = false;
        mFade = 1f;
        stopScale();
        mPackage = null;
        mScale = 1f;
        mScaleTo = 1f;
        boolean had = mContent;
        mContent = false;
        Xp.log(mTag + "released");
        if (had) ImmersiveHost.contentChanged(this);
    }

    @Override
    public String describe() {
        return "armed=" + mArmed + " connected=" + (mServer != null) + " bindSent=" + mBindSent
                + " replies=" + mReplies + " shown=" + mShown
                + (mSurface == null ? "" : " size=" + mSurface.getWidth() + "x" + mSurface.getHeight());
    }

    // ---------------------------------------------------------------- protocol

    private void bind() {
        mStartedAt = SystemClock.uptimeMillis();
        mConn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServer = new Messenger(service);
                Xp.log(mTag + "connected");
                sendBind();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mServer = null;
                // The app's process died, and its page with it; what is left in the SurfaceView
                // is the black layer behind it. Nothing comes back on its own: the app has to be
                // ready again, and say so.
                Xp.log(mTag + "disconnected - the page is gone");
                setArmed(false);
            }
        };
        Intent intent = new Intent().setComponent(new ComponentName(mPkg, mService));
        boolean ok = bindAsUser(mCtx, intent, mConn);
        Xp.log(mTag + "bind " + (ok ? "requested" : "REFUSED"));
        if (!ok) mConn = null;
    }

    private void sendBind() {
        if (mBindSent || mServer == null || mSurface == null) return;
        IBinder token = mSurface.getHostToken();
        if (token == null) {
            Xp.log(mTag + "no host token yet");
            return;
        }
        Bundle data = new Bundle();
        data.putBinder("hostToken", token);
        data.putBundle("extra", displayExtra());
        mBindSent = send(MSG_BIND, data);
        Xp.log(mTag + "11 sent=" + mBindSent + " " + width() + "x" + height());
    }

    /** The app's replies, on the main thread like the plugin's MsgReceiver. */
    private final Messenger mReplyTo = new Messenger(Gen.watchHandler(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            mReplies++;
            Bundle data = msg.getData();
            Xp.log(mTag + "reply what=" + msg.what + " after "
                    + (SystemClock.uptimeMillis() - mStartedAt) + "ms");
            if (msg.what != MSG_SURFACE) return;
            SurfaceControlViewHost.SurfacePackage pkg = null;
            try {
                pkg = data.getParcelable("SurfacePackage",
                        SurfaceControlViewHost.SurfacePackage.class);
            } catch (Throwable t) {
                Xp.log(mTag + "21 unreadable: " + t);
            }
            if (pkg == null || mSurface == null) {
                Xp.log(mTag + "21 without a package, or after release");
                return;
            }
            mSurface.setChildSurfacePackage(pkg);
            mPackage = pkg;
            // A new page takes the scale the fade has it at.
            applyScale(mScale);
            boolean first = !mContent;
            mContent = true;
            send(MSG_STATE, stateData(mShown ? 1 : 2));
            onPage();
            if (first) ImmersiveHost.contentChanged(LiveAlertScene.this);
        }
    }));

    /** A page came back (21): the first, or a new one after the app re-rendered. */
    protected void onPage() {
    }

    private Bundle resizeData() {
        Bundle data = new Bundle();
        data.putInt("event", 1);
        data.putBundle("extra", displayExtra());
        return data;
    }

    /**
     * The lock screen's configuration changed - night mode, font scale, the display: passed on to
     * the page, as the plugin does (a6.l.i / a6.a.i). The page is drawn in the app's process from
     * a SurfaceControlViewHost, which does not see the host's configuration by itself, and 高德's
     * page follows the theme (horusConfig.followThemeMode), so without this it may stay a day map
     * after dark mode comes on (not seen yet; added 2026-09-30 matching the plugin). The package's notifyConfigurationChanged is Android 16's; the
     * state 0 message beside it carries the configuration for an app that listens for it there.
     */
    private void forwardConfiguration(Configuration config) {
        SurfaceControlViewHost.SurfacePackage p = mPackage;
        if (p != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.BAKLAVA) {
            try {
                p.notifyConfigurationChanged(config);
            } catch (Throwable t) {
                Xp.log(mTag + "configuration not passed on: " + t);
            }
        }
        if (!mBindSent) return;
        Bundle extra = new Bundle();
        extra.putParcelable("config", config);
        Bundle data = new Bundle();
        data.putInt("state", 0);
        data.putBundle("extra", extra);
        send(MSG_STATE, data);
        Xp.log(mTag + "configuration passed on, night="
                + ((config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES));
    }

    private Bundle stateData(int state) {
        Bundle data = new Bundle();
        data.putInt("state", state);
        data.putBundle("extra", cardExtra());
        return data;
    }

    private Bundle cardExtra() {
        Bundle card = new Bundle();
        card.putString("cardKey", mCardKey);
        Bundle extra = new Bundle();
        extra.putBundle("livealert.immersive.card", card);
        return extra;
    }

    /** The SurfaceView's size; its window's while it has not been laid out. */
    private int width() {
        int w = mSurface.getWidth();
        return w > 0 ? w : mSurface.getRootView().getWidth();
    }

    private int height() {
        int h = mSurface.getHeight();
        return h > 0 ? h : mSurface.getRootView().getHeight();
    }

    /**
     * What the plugin's getDisplayBundle builds (a6.a.e): the size, the orientation, infoBounds -
     * the band the app keeps its information inside (INFO_*) - and criticalBounds, where the app
     * keeps the thing that must not be lost: 高德's cycling page puts you there. Without it that
     * page put you below the screen's bottom while its walking page, which does not read it,
     * kept you in view (2026-09-30, the same navigation side by side with the OPPO).
     */
    private Bundle displayExtra() {
        int w = width();
        int h = height();
        Bundle display = new Bundle();
        display.putInt("width", w);
        display.putInt("height", h);
        display.putInt("orientation", mSurface.getResources().getConfiguration().orientation);
        int side = Math.round(w * INFO_SIDE);
        int infoBottom = Math.round(h * INFO_BOTTOM);
        display.putParcelable("infoBounds",
                new Rect(side, Math.round(h * INFO_TOP), w - side, infoBottom));
        // The plugin's getCriticalRect: a box centred across, CRITICAL_Y down, kept at least its
        // own half clear of infoBounds' bottom. The plugin's box is 100px on a 1080px-wide
        // screen, so its half is a fraction of the width here.
        int half = Math.round(w * CRITICAL_HALF);
        int cy = Math.round(h * CRITICAL_Y);
        if (infoBottom - cy < half) cy = infoBottom - half;
        display.putParcelable("criticalBounds",
                new Rect(w / 2 - half, cy - half, w / 2 + half, cy + half));
        Bundle extra = cardExtra();
        extra.putBundle("livealert.immersive.display", display);
        return extra;
    }

    protected boolean send(int what, Bundle data) {
        Messenger server = mServer;
        if (server == null) return false;
        Message m = Message.obtain(null, what);
        m.setData(data);
        m.replyTo = mReplyTo;
        try {
            server.send(m);
            return true;
        } catch (Throwable t) {
            Xp.w(mTag + "send " + what + " failed: " + t);
            return false;
        }
    }

    /**
     * SystemUI runs as the system uid, and a plain bindService from there is the "without a
     * qualified user" case; the plugin binds as the current user, so do the same when we can.
     */
    private boolean bindAsUser(Context ctx, Intent intent, ServiceConnection conn) {
        try {
            return (boolean) Context.class.getMethod("bindServiceAsUser", Intent.class,
                            ServiceConnection.class, int.class, UserHandle.class)
                    .invoke(ctx, intent, conn, Context.BIND_AUTO_CREATE, android.os.Process.myUserHandle());
        } catch (Throwable t) {
            Xp.w(mTag + "bindServiceAsUser unavailable (" + t + "), plain bind");
            return ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        }
    }
}
