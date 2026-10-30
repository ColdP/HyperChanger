// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Experiment: a motion cover drawn by the wallpaper process itself, instead of by a video lock
 * wallpaper or a view in SystemUI.
 *
 * Everything that samples the lock screen's backdrop - the clock glass, the card blur - samples
 * this process's window, so a cover that is to move under them has to move here. The static
 * cover already lives here as the keyguard texture; this keeps that texture's place and changes
 * only what is in it.
 *
 * Per frame, on the GL thread, before the OEM draws:
 *
 *   video (MediaPlayer -> SurfaceTexture, an OES texture in the engine's own context)
 *     -> small FBO: the video mirrored away from the band, as CoverCompose.mirroredBackground
 *     -> ping-pong box blur at that small size, as CoverCompose.blur
 *     -> full FBO the size of the keyguard texture: blurred backdrop + 8% black + the sharp band
 *        with feathered edges, as CoverCompose.composeWallpaper
 *
 * and then the OEM's own draw runs with ImageGLWallpaper.mTextureId pointed at that full FBO's
 * texture for the length of the call. Same program, same uniforms: darken, the wake zoom and the
 * reveal apply to the video exactly as to the still. Nothing is uploaded per frame - the cost
 * that made the old CPU crossfade stutter was re-uploading the whole texture through a reload,
 * and this never reloads.
 *
 * Probe op `vidtex` only; nothing turns it on by itself.
 */
final class WallpaperVideo {

    private WallpaperVideo() {
    }

    private static final String TAG = "[MCWall] video: ";

    /** Our vertex attribute. The OEM's two are set once in setup() and never again, so ours must
     * not be either of theirs; 7 is inside the 8 every GLES2 context has. */
    private static final int ATTR = 7;

    // ---------------------------------------------------------------- main-thread state

    private static volatile boolean sOn;
    private static MediaPlayer sPlayer;
    private static String sPath;
    private static int sVideoW, sVideoH;
    private static BroadcastReceiver sScreen;
    /** Where the sharp band sits, as CoverCompose's bias. */
    private static volatile float sBias = 0.25f;

    // ---------------------------------------------------------------- shared

    /** A new decoded frame is waiting in the SurfaceTexture. */
    private static volatile boolean sFrameWaiting;
    /** The SurfaceTexture the player is to be pointed at; made on the GL thread. */
    private static volatile SurfaceTexture sPendingTexture;

    // ---------------------------------------------------------------- GL-thread state

    /** GL thread writes it; the hot reload's check section reads it from a binder thread. */
    private static volatile EGLContext sCtx;
    private static int sOes;
    private static volatile SurfaceTexture sTexture;
    private static int sProgMirror, sProgBlur, sProgCompose;
    private static int sSmallW, sSmallH;
    private static final int[] sSmallTex = new int[2], sSmallFbo = new int[2];
    private static int sFullW, sFullH, sFullTex, sFullFbo;
    private static boolean sHasFrame;
    /** The video on screen has delivered a frame since it was started. Main writes false. */
    private static volatile boolean sCurrent;
    /**
     * How much of the composite is video rather than the still, 0..1. It fades in once the
     * video has a frame and out once it stops, mixed against the OEM's own texture (uStill), so
     * either way the picture under it is the one the screen goes on showing.
     */
    private static float sAlpha;
    private static long sAlphaAt;
    private static final float FADE_MS = 350f;
    /**
     * The lyric page's frosting, 0..1, eased like sAlpha. Under the lyrics the wallpaper process
     * puts up a frosted copy of the still (CoverCompose.frostedFor), and a sharp video drawn over
     * it took the frosting away. The video is frosted the same way instead - its own blurred
     * backdrop, saturated 1.3 and darkened - so it stays moving under the words.
     */
    private static volatile boolean sFrostWant;
    private static volatile long sFrostChangedAt;
    /** GL thread: the fade running now is the lyrics' frosting. */
    private static boolean sFrostFade;
    private static float sFrost;
    private static long sFrostAt;

    /** Any thread: the lyrics came up or went. */
    static void setFrost(boolean on) {
        if (sFrostWant == on) return;
        sFrostWant = on;
        sFrostChangedAt = SystemClock.uptimeMillis();
        if (sOn) WallpaperProbe.videoFrame();
    }

    /** GL thread: a still's crossfade is running, and whether it began with video on screen. */
    private static boolean sInFade, sOverlay;
    private static final float[] sStMatrix = new float[16];
    private static FloatBuffer sQuad;
    private static boolean sWarnedProgram;

    // ---------------------------------------------------------------- numbers for the probe

    private static volatile int sDecoded, sComposed, sDrawn, sSkippedFade, sContexts;
    private static volatile long sComposeNs, sComposeMaxNs;
    private static volatile long sSince = SystemClock.uptimeMillis();

    // ================================================================ control (main thread)

    static boolean on() {
        return sOn;
    }

    /** The band moved (the position slider): composed there from the next frame. */
    static void setBias(float bias) {
        if (sBias == bias) return;
        sBias = bias;
        sFrameWaiting = sCurrent;
        Xp.log(TAG + "bias " + bias);
        if (sOn) WallpaperProbe.videoFrame();
    }

    static void start(Context ctx, String path, float bias) {
        stop("restart");
        sPath = path;
        sBias = bias;
        resetStats();
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                sVideoW = Integer.parseInt(r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                sVideoH = Integer.parseInt(r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            } finally {
                r.release();
            }
        } catch (Throwable t) {
            Xp.w(TAG + "cannot read " + path + ": " + t);
            return;
        }
        sCurrent = false;
        sRetries = 0;
        sApp = ctx.getApplicationContext();
        sFrostWant = WallpaperProbe.lyricBlurWanted();
        sOn = true;
        registerScreen(ctx);
        Xp.log(TAG + "on: " + path + " " + sVideoW + "x" + sVideoH + " bias=" + bias);
        // Restarted for another track, the GL objects outlive the old player: give the new
        // one the texture that is there. Only a fresh set-up posts this by itself, and waiting
        // for one left the second video with no player at all (2026-10-13).
        final SurfaceTexture st = sTexture;
        if (st != null) {
            sPendingTexture = st;
            Gen.post(() -> attachPlayer(st));
        }
        // The SurfaceTexture has to be made in the engine's GL context, so the first frame
        // request is what creates it; the player is started once it exists.
        WallpaperProbe.videoFrame();
    }

    static void stop(String why) {
        boolean was = sOn;
        sOn = false;
        MediaPlayer p = sPlayer;
        sPlayer = null;
        sPrepared = false;
        if (p != null) {
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        sPendingTexture = null;
        if (was) {
            Xp.log(TAG + "off (" + why + ") " + stats());
            // The GL thread fades the last frame out over the still and then lets its objects
            // go; this frame starts it.
            WallpaperProbe.videoFrame();
        }
    }

    /**
     * Retirement: the player let go and the receiver forgotten, so the next generation's
     * registerScreen() puts a fresh one on (Gen takes the one registered here back out). The
     * GL objects are already gone - a live video is refused before retirement, see
     * WallpaperProbe.busyReason().
     */
    static void retire() {
        stop("hot reload");
        sScreen = null;
        sApp = null;
    }

    private static void registerScreen(Context ctx) {
        if (sScreen != null) return;
        sScreen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                MediaPlayer p = sPlayer;
                if (p == null || !sPrepared) return;
                try {
                    if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                        if (p.isPlaying()) p.pause();
                        Xp.log(TAG + "screen off, paused " + stats());
                    } else if (sOn) {
                        p.start();
                        WallpaperProbe.videoFrame();
                    }
                } catch (Throwable t) {
                    Xp.w(TAG + "screen " + i.getAction() + ": " + t);
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        Gen.receiver(ctx, sScreen, f);
    }

    /** Main thread: point the player at a SurfaceTexture the GL thread has just made. */
    /** The player in sPlayer has finished preparing. Main thread. */
    private static boolean sPrepared;
    private static Context sApp;
    /** Players remade after an error since the last start(). */
    private static int sRetries;

    private static void attachPlayer(SurfaceTexture st) {
        if (!sOn || st != sPendingTexture) return;
        sPendingTexture = null;
        if (sPlayer != null && sPrepared) {
            // A new GL context: same player, new target - only once it is prepared. Moved while
            // still preparing, it answered error 1/-19 and the video was gone until the next
            // track (2026-10-13, every switch back that met a context rebuild).
            Surface surface = new Surface(st);
            try {
                sPlayer.setSurface(surface);
                Xp.log(TAG + "player moved to a new context's texture");
                return;
            } catch (Throwable t) {
                Xp.w(TAG + "moving the player failed, making a new one: " + t);
            } finally {
                surface.release();
            }
        }
        newPlayer(st);
    }

    /** The screen is on. A player that finishes preparing in the dark waits for SCREEN_ON. */
    private static boolean interactive() {
        try {
            android.os.PowerManager pm = sApp == null ? null
                    : sApp.getSystemService(android.os.PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void newPlayer(SurfaceTexture st) {
        MediaPlayer old = sPlayer;
        sPlayer = null;
        sPrepared = false;
        if (old != null) {
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        }
        Surface surface = new Surface(st);
        try {
            final MediaPlayer p = new MediaPlayer();
            sPlayer = p;
            p.setDataSource(sPath);
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnErrorListener((mp, what, extra) -> {
                if (sPlayer != mp) return true;
                Xp.w(TAG + "player error " + what + "/" + extra + " (retry " + sRetries + ")");
                SurfaceTexture now = sTexture;
                if (sOn && now != null && sRetries < 2) {
                    sRetries++;
                    newPlayer(now);
                } else {
                    stop("player error");
                }
                return true;
            });
            p.setOnPreparedListener(mp -> {
                if (sPlayer != mp || !sOn) return;
                sPrepared = true;
                if (interactive()) mp.start();
                Xp.log(TAG + "playing");
            });
            p.prepareAsync();
        } catch (Throwable t) {
            Xp.w(TAG + "player: " + t);
            stop("player failed");
        } finally {
            surface.release();
        }
    }

    // ================================================================ GL thread

    /**
     * Called from the onDrawFrame hook before the OEM draws. Returns the texture to draw in place
     * of the OEM's, or 0 to leave the frame alone.
     */
    /** GL thread: whether this still holds GL objects that a frame has to look after. */
    static boolean holdsGl() {
        return sCtx != null;
    }

    /**
     * GL thread, from the still's crossfade: the texture to fade out instead of its own copy of
     * the old still - the video's last composite, when the fade began with video on screen.
     * So a track change goes from the old video to the new still, not via the old still.
     */
    static int fadeOverlay() {
        EGLContext ctx = EGL14.eglGetCurrentContext();
        return sOverlay && sFullTex != 0 && sCtx != null && sCtx.equals(ctx) ? sFullTex : 0;
    }

    static int beforeDraw(Object renderer, boolean fadeRunning, int texW, int texH,
                          int stillTex) {
        EGLContext ctx = EGL14.eglGetCurrentContext();
        if (!sOn && sCtx == null) return 0;
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
            if (prog == null || prog.getClass() != WallpaperProbe.plainProgram()) {
                if (!sWarnedProgram) {
                    sWarnedProgram = true;
                    Xp.log(TAG + "not drawn: the keyguard program is "
                            + (prog == null ? "null" : prog.getClass().getSimpleName()));
                }
                if (!sOn) release(ctx);
                return 0;
            }
            if (!sOn && !ctx.equals(sCtx)) {
                // Off, and the objects went with a context that is gone: nothing to fade.
                forget();
                return 0;
            }
            // Made even under a fade. A cover coming back starts its video while its still is
            // fading in, and it is the player's frames that ask for the frames after - set-up
            // left for after the fade waited on a frame nothing was going to ask for, and the
            // video never started (2026-10-13: `on`, then decoded=0 player=none for good).
            if (sCtx == null || !ctx.equals(sCtx)) {
                // A context we did not make our objects in. Its predecessor took them with it.
                forget();
                setUp(ctx);
            }
            if (texW > 0 && texH > 0 && (texW != sFullW || texH != sFullH)) {
                if (!sOn) {
                    release(ctx);
                    return 0;
                }
                makeFull(texW, texH);
            }
            long now = SystemClock.uptimeMillis();
            if (fadeRunning && !sInFade) {
                sInFade = true;
                // The lyrics' frosting is a fade of the same cover, sharp to frosted. A video
                // on screen frosts itself (sFrost), so it keeps drawing through that one: left
                // to the still's fade, it went sharp video -> frosted still -> frosted video.
                sFrostFade = sOn && sCurrent && sAlpha > 0f
                        && SystemClock.uptimeMillis() - sFrostChangedAt < 1000L;
                // Any other fade owns the texture and the video takes over when it has landed;
                // if video was showing as it began, its last composite is what fades out.
                sOverlay = sFrostFade || (sAlpha > 0f && sHasFrame);
            }
            if (fadeRunning && !sFrostFade) {
                sSkippedFade++;
                sAlphaAt = now;
                sFrostAt = now;
                return 0;
            }
            if (!fadeRunning && sInFade) {
                sInFade = false;
                // The fade took the video off the screen with it - unless it was the frosting,
                // which the video drew itself.
                if (sOverlay && !sFrostFade) sAlpha = 0f;
                sOverlay = false;
                sFrostFade = false;
            }
            if (sFullTex == 0) return 0;
            boolean fresh = false;
            if (sOn && sFrameWaiting && sTexture != null) {
                sFrameWaiting = false;
                sTexture.updateTexImage();
                sTexture.getTransformMatrix(sStMatrix);
                sCurrent = true;
                sHasFrame = true;
                fresh = true;
            }
            float target = sOn && sCurrent ? 1f : 0f;
            float was = sAlpha;
            if (sAlpha != target) {
                float dt = sAlphaAt == 0L ? 16f : Math.min(50f, Math.max(1f, now - sAlphaAt));
                sAlpha = target > sAlpha ? Math.min(target, sAlpha + dt / FADE_MS)
                        : Math.max(target, sAlpha - dt / FADE_MS);
            }
            sAlphaAt = now;
            float frostTarget = sFrostWant ? 1f : 0f;
            float frostWas = sFrost;
            if (sFrost != frostTarget) {
                float dt = sFrostAt == 0L ? 16f : Math.min(50f, Math.max(1f, now - sFrostAt));
                sFrost = frostTarget > sFrost ? Math.min(frostTarget, sFrost + dt / FADE_MS)
                        : Math.max(frostTarget, sFrost - dt / FADE_MS);
            }
            sFrostAt = now;
            if (sAlpha <= 0f) {
                if (!sOn) release(ctx);
                return 0;
            }
            if (!sHasFrame) return 0;
            if (fresh || sAlpha != was || sFrost != frostWas) {
                long t0 = System.nanoTime();
                compose(stillTex);
                long ns = System.nanoTime() - t0;
                sComposeNs += ns;
                if (ns > sComposeMaxNs) sComposeMaxNs = ns;
                sComposed++;
            }
            // A fade in either direction asks for its own frames: a paused or stopped player
            // sends none.
            if (sAlpha != target || sFrost != frostTarget) WallpaperProbe.videoFrame();
            sDrawn++;
            return sFullTex;
        } catch (Throwable t) {
            Xp.w(TAG + "GL failed, turning off: " + android.util.Log.getStackTraceString(t));
            Gen.post(() -> stop("GL failure"));
            return 0;
        }
    }

    private static void setUp(EGLContext ctx) {
        sCtx = ctx;
        sContexts++;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        sOes = id[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sOes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        final SurfaceTexture st = new SurfaceTexture(sOes);
        sTexture = st;
        st.setOnFrameAvailableListener(s -> {
            if (s != sTexture) return;
            sDecoded++;
            sFrameWaiting = true;
            WallpaperProbe.videoFrame();
        }, Gen.handler());
        sPendingTexture = st;
        Gen.post(() -> attachPlayer(st));

        sProgMirror = program(VS, FS_MIRROR);
        sProgBlur = program(VS, FS_BLUR);
        sProgCompose = program(VS, FS_COMPOSE);
        if (sQuad == null) {
            sQuad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            sQuad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);
        }
        sFullW = sFullH = 0;
        sHasFrame = false;
        // A new context is a new, empty SurfaceTexture: nothing to show until the player sends
        // a frame into it. Carried over, "has a frame, fully in" drew that empty texture - the
        // black flash on every wake from the screen off, when the engine's context is rebuilt
        // and the player is still paused (2026-10-13). The still shows, and the video fades in.
        sFrameWaiting = false;
        sCurrent = false;
        sAlpha = 0f;
        sFrost = sFrostWant ? 1f : 0f;
        Xp.log(TAG + "GL objects made in context #" + sContexts + " ("
                + GLES20.glGetString(GLES20.GL_VERSION) + ")");
    }

    private static void makeFull(int w, int h) {
        deleteFull();
        sFullW = w;
        sFullH = h;
        sFullTex = texture2d(w, h);
        sFullFbo = fbo(sFullTex);
        // CoverCompose blurs at 48 wide; the backdrop is a quarter of that again in each pass.
        sSmallW = 48;
        sSmallH = Math.max(1, Math.round(48f * h / w));
        for (int i = 0; i < 2; i++) {
            sSmallTex[i] = texture2d(sSmallW, sSmallH);
            sSmallFbo[i] = fbo(sSmallTex[i]);
        }
        sHasFrame = false;
        // Same context, new size: the frame already in the SurfaceTexture is composed again.
        if (sCurrent) sFrameWaiting = true;
        Xp.log(TAG + "target " + w + "x" + h + ", backdrop " + sSmallW + "x" + sSmallH);
    }

    /** The three passes. Saves and puts back every piece of state the OEM's draw relies on. */
    private static void compose(int stillTex) {
        int[] i1 = new int[1];
        int[] vp = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, i1, 0);
        int oldFbo = i1[0];
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, i1, 0);
        int oldProg = i1[0];
        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, i1, 0);
        int oldBuf = i1[0];
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, i1, 0);
        int oldUnit = i1[0];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0);
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glVertexAttribPointer(ATTR, 2, GLES20.GL_FLOAT, false, 0, sQuad);
        GLES20.glEnableVertexAttribArray(ATTR);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        float coverH = sFullW * (sVideoH / (float) Math.max(1, sVideoW));
        float top = (sFullH - coverH) * Math.max(0f, Math.min(1f, sBias));

        // 1. The mirrored backdrop, small. Each small pixel averages a 4x4 grid of the video so
        //    the downscale does not shimmer from one frame to the next.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sSmallFbo[0]);
        GLES20.glViewport(0, 0, sSmallW, sSmallH);
        GLES20.glUseProgram(sProgMirror);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sOes);
        GLES20.glUniform1i(loc(sProgMirror, "uVideo"), 0);
        GLES20.glUniformMatrix4fv(loc(sProgMirror, "uSt"), 1, false, sStMatrix, 0);
        GLES20.glUniform2f(loc(sProgMirror, "uSize"), sSmallW, sSmallH);
        GLES20.glUniform2f(loc(sProgMirror, "uFull"), sFullW, sFullH);
        GLES20.glUniform2f(loc(sProgMirror, "uBand"), top, coverH);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 2. Box blur radius 4, three times each way - CoverCompose.blur(bg, 48, 4, 3).
        GLES20.glUseProgram(sProgBlur);
        GLES20.glUniform1i(loc(sProgBlur, "uTex"), 0);
        GLES20.glUniform2f(loc(sProgBlur, "uSize"), sSmallW, sSmallH);
        int dirLoc = loc(sProgBlur, "uDir");
        for (int pass = 0; pass < 6; pass++) {
            int src = pass % 2, dst = 1 - src;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sSmallFbo[dst]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sSmallTex[src]);
            if (pass % 2 == 0) GLES20.glUniform2f(dirLoc, 1f / sSmallW, 0f);
            else GLES20.glUniform2f(dirLoc, 0f, 1f / sSmallH);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        // Six passes end in sSmallTex[0].

        // 3. The composition at the keyguard texture's size.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sFullFbo);
        GLES20.glViewport(0, 0, sFullW, sFullH);
        GLES20.glUseProgram(sProgCompose);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sSmallTex[0]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sOes);
        GLES20.glUniform1i(loc(sProgCompose, "uBack"), 0);
        GLES20.glUniform1i(loc(sProgCompose, "uVideo"), 1);
        GLES20.glUniformMatrix4fv(loc(sProgCompose, "uSt"), 1, false, sStMatrix, 0);
        GLES20.glUniform2f(loc(sProgCompose, "uFull"), sFullW, sFullH);
        GLES20.glUniform3f(loc(sProgCompose, "uBand"), top, coverH,
                Math.min(240f, Math.round(coverH / 4f)));
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTex);
        GLES20.glUniform1i(loc(sProgCompose, "uStill"), 2);
        GLES20.glUniform1f(loc(sProgCompose, "uAlpha"), stillTex == 0 ? 1f : sAlpha);
        GLES20.glUniform1f(loc(sProgCompose, "uFrost"), sFrost);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        // Put back what the OEM's draw finds.
        GLES20.glDisableVertexAttribArray(ATTR);
        GLES20.glActiveTexture(oldUnit);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, oldBuf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFbo);
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3]);
        GLES20.glUseProgram(oldProg);
        if (blend) GLES20.glEnable(GLES20.GL_BLEND);
        if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        if (depth) GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (cull) GLES20.glEnable(GLES20.GL_CULL_FACE);
    }

    /** Video off: let everything go, in the context that made it where that is still current. */
    private static void release(EGLContext ctx) {
        boolean ours = sCtx != null && sCtx.equals(ctx);
        if (ours) {
            deleteFull();
            GLES20.glDeleteTextures(1, new int[]{sOes}, 0);
            GLES20.glDeleteProgram(sProgMirror);
            GLES20.glDeleteProgram(sProgBlur);
            GLES20.glDeleteProgram(sProgCompose);
        }
        forget();
        sAlpha = 0f;
        sInFade = sOverlay = sFrostFade = false;
        Xp.log(TAG + "GL objects released" + (ours ? "" : " (their context was already gone)"));
    }

    /** Drop every handle without touching GL - for objects whose context is gone. */
    private static void forget() {
        SurfaceTexture st = sTexture;
        sTexture = null;
        if (st != null) {
            st.setOnFrameAvailableListener(null);
            st.release();
        }
        sCtx = null;
        sOes = 0;
        sProgMirror = sProgBlur = sProgCompose = 0;
        sFullTex = sFullFbo = 0;
        sSmallTex[0] = sSmallTex[1] = sSmallFbo[0] = sSmallFbo[1] = 0;
        sFullW = sFullH = 0;
        sHasFrame = false;
    }

    private static void deleteFull() {
        if (sFullTex != 0) {
            GLES20.glDeleteFramebuffers(3, new int[]{sFullFbo, sSmallFbo[0], sSmallFbo[1]}, 0);
            GLES20.glDeleteTextures(3, new int[]{sFullTex, sSmallTex[0], sSmallTex[1]}, 0);
        }
        sFullTex = sFullFbo = 0;
        sSmallTex[0] = sSmallTex[1] = sSmallFbo[0] = sSmallFbo[1] = 0;
    }

    // ================================================================ GL helpers

    private static int texture2d(int w, int h) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        return id[0];
    }

    private static int fbo(int tex) {
        int[] old = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, old, 0);
        int[] id = new int[1];
        GLES20.glGenFramebuffers(1, id, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, old[0]);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }
        return id[0];
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glBindAttribLocation(p, ATTR, "aPos");
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("link: " + log);
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("compile: " + log);
        }
        return s;
    }

    private static int loc(int prog, String name) {
        return GLES20.glGetUniformLocation(prog, name);
    }

    // ================================================================ shaders
    //
    // Coordinates: `p` is in pixels of the target with y running DOWN the picture. A texture
    // uploaded from a Bitmap has the bitmap's first row at t=0, and rendering into an FBO puts
    // gl_FragCoord.y=0 at t=0 too, so FBO row y is bitmap row y - the OEM samples our texture
    // exactly as it samples the still it replaces.

    private static final String VS = ""
            + "attribute vec2 aPos;\n"
            + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    /** Video at (u, v) with v running down the picture, through the SurfaceTexture's matrix. */
    private static final String VIDEO = ""
            + "uniform samplerExternalOES uVideo;\n"
            + "uniform mat4 uSt;\n"
            + "vec4 video(vec2 uv) {\n"
            + "  vec2 t = (uSt * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;\n"
            + "  return texture2D(uVideo, t);\n"
            + "}\n";

    private static final String FS_MIRROR = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + VIDEO
            + "uniform vec2 uSize;\n"   // small target
            + "uniform vec2 uFull;\n"   // full target it stands for
            + "uniform vec2 uBand;\n"   // top, height of the sharp band, full pixels
            + "vec4 mirrored(vec2 full) {\n"
            + "  float v = mod((full.y - uBand.x) / uBand.y, 2.0);\n"
            + "  if (v > 1.0) v = 2.0 - v;\n"
            + "  return video(vec2(clamp(full.x / uFull.x, 0.0, 1.0), clamp(v, 0.0, 1.0)));\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 cell = uFull / uSize;\n"
            + "  vec2 base = floor(gl_FragCoord.xy) * cell;\n"
            + "  vec4 sum = vec4(0.0);\n"
            + "  for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)\n"
            + "    sum += mirrored(base + (vec2(float(x), float(y)) + 0.5) * cell * 0.25);\n"
            + "  gl_FragColor = sum / 16.0;\n"
            + "}\n";

    /** Box blur radius 4 along uDir, clamped at the edges like CoverCompose's. */
    private static final String FS_BLUR = ""
            + "precision mediump float;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uSize;\n"
            + "uniform vec2 uDir;\n"
            + "void main() {\n"
            + "  vec2 uv = gl_FragCoord.xy / uSize;\n"
            + "  vec4 sum = vec4(0.0);\n"
            + "  for (int i = -4; i <= 4; i++) sum += texture2D(uTex, uv + uDir * float(i));\n"
            + "  gl_FragColor = sum / 9.0;\n"
            + "}\n";

    private static final String FS_COMPOSE = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + VIDEO
            + "uniform sampler2D uBack;\n"
            + "uniform sampler2D uStill;\n"   // the OEM's texture: what shows without video
            + "uniform float uAlpha;\n"
            + "uniform float uFrost;\n"
            + "uniform vec2 uFull;\n"
            + "uniform vec3 uBand;\n"   // top, height, feather
            + "void main() {\n"
            + "  vec2 p = gl_FragCoord.xy;\n"
            + "  vec3 c = texture2D(uBack, p / uFull).rgb * (1.0 - 20.0 / 255.0);\n"
            + "  float y = p.y - uBand.x;\n"
            + "  if (y >= 0.0 && y <= uBand.y) {\n"
            + "    float a = uBand.z > 0.0\n"
            + "        ? clamp(min(y, uBand.y - y) / uBand.z, 0.0, 1.0) : 1.0;\n"
            + "    c = mix(c, video(vec2(p.x / uFull.x, y / uBand.y)).rgb, a);\n"
            + "  }\n"
            // CoverCompose.frostedOf: saturation 1.3 (ColorMatrix luminance), 0x14 then 0x61.
            + "  if (uFrost > 0.0) {\n"
            + "    vec3 b = texture2D(uBack, p / uFull).rgb;\n"
            + "    float l = dot(b, vec3(0.213, 0.715, 0.072));\n"
            + "    vec3 f = clamp(mix(vec3(l), b, 1.3), 0.0, 1.0)\n"
            + "        * (1.0 - 20.0 / 255.0) * (1.0 - 97.0 / 255.0);\n"
            + "    c = mix(c, f, uFrost);\n"
            + "  }\n"
            + "  if (uAlpha < 1.0) c = mix(texture2D(uStill, p / uFull).rgb, c, uAlpha);\n"
            + "  gl_FragColor = vec4(c, 1.0);\n"
            + "}\n";

    // ================================================================ probe report

    private static void resetStats() {
        sDecoded = sComposed = sDrawn = sSkippedFade = 0;
        sComposeNs = sComposeMaxNs = 0L;
        sSince = SystemClock.uptimeMillis();
    }

    static String stats() {
        long el = Math.max(1L, SystemClock.uptimeMillis() - sSince);
        int c = sComposed;
        MediaPlayer p = sPlayer;
        String pos;
        try {
            pos = p == null ? "none" : (p.isPlaying() ? "playing" : "paused")
                    + " @" + p.getCurrentPosition() + "ms";
        } catch (Throwable t) {
            pos = "?";
        }
        return "on=" + sOn + " player=" + pos
                + " over " + el + "ms: decoded=" + sDecoded
                + " (" + Math.round(sDecoded * 10000.0 / el) / 10.0 + "/s)"
                + " composed=" + c + " drawn=" + sDrawn
                + " (" + Math.round(sDrawn * 10000.0 / el) / 10.0 + "/s)"
                + " skippedForFade=" + sSkippedFade
                + " composeCpu avg=" + (c == 0 ? 0 : Math.round(sComposeNs / (double) c / 1000.0))
                + "us max=" + Math.round(sComposeMaxNs / 1000.0) + "us"
                + " contexts=" + sContexts + " target=" + sFullW + "x" + sFullH;
    }

    static String statsAndReset() {
        String s = stats();
        resetStats();
        return s;
    }
}
