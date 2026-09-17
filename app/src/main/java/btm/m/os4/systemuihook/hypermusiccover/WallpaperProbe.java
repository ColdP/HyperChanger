package btm.m.os4.systemuihook.hypermusiccover;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Modifier;


/**
 * Probe for com.miui.miwallpaper, the separate process that actually draws the lockscreen
 * wallpaper (window com.miui.miwallpaper.wallpaperservice.ImageWallpaper, rendered through
 * OpenGL). The clock's liquid-glass refraction and the notification/media card blur sample
 * that window, not SystemUI's view tree, so a cover added inside SystemUI can never be picked
 * up by them - the album art has to become the wallpaper here.
 *
 * Read-only for now: this process owns the wallpaper, and a bad hook leaves the phone with no
 * wallpaper at all, so discover the bitmap path before touching it.
 */
public class WallpaperProbe {

    private static final String TAG = "[MCWall] ";
    private static final String ACTION = "btm.m.os4.systemuihook.hypermusiccover.WPROBE";
    private static final String PKG = "com.miui.miwallpaper";

    private static ClassLoader sCl;
    private static boolean sRegistered;
    private static Context sCtx;
    /** The fitted copy of sArt, kept so the GL thread never rescales during a track change. */
    private static volatile Bitmap sFitted;
    private static volatile Bitmap sFittedOf;
    /**
     * Give the keyguard a texture the size of the SCREEN instead of the size of the wallpaper
     * file. On by default; the `texfit` probe turns it off.
     *
     * MIUI builds the keyguard texture at whatever `WallpaperManager.peekBitmapDimensions()`
     * says, and `ImageGLWallpaper.setupTexture()` allocates it from the bitmap it is handed, so
     * a phone whose lock wallpaper is 2121x4712 uploads 38MB per swap - twice - and our art has
     * to be scaled UP to that size to keep the GL matrix (built from the same dimensions) honest.
     * It also puts the fade over its own threshold, so the swap is a cut. Measured on the phone
     * that reported it: 1.3s to re-fit the wallpaper, then 38MB twice on every swap.
     *
     * The fix the module shipped first rewrote the wallpaper FILE (fitLockWallpaperToScreen in
     * Main), which is what costs the user their depth cut-out: MIUI's subject segmentation is
     * tied to the wallpaper the picker set, and nothing re-analyses a file we wrote ourselves.
     *
     * This does it without touching the file. Both ends of the pair move together:
     *   - the bitmap the upload gets, fitted to the surface, in screenSized() below;
     *   - the rectangle updateMVPMatrix() builds the matrix from, in the hook further down.
     * With those agreeing, the texture is the screen's size and the picture is where it belongs.
     * On a phone whose lock wallpaper IS the screen's size both halves return early and nothing
     * happens at all, which is every phone until someone picks a big picture.
     *
     * sKeyguardTexture is the wallpaper process's own ImageWallpaperRenderer$WallpaperTexture for
     * the KEYGUARD renderer - the desktop wallpaper shares this code and is not ours to resize.
     */
    private static volatile boolean sTexFit = true;
    private static volatile Object sKeyguardTexture;
    private static Bitmap sScreenArt;
    private static Bitmap sScreenArtOf;
    private static volatile int sReportedW, sReportedH;

    /**
     * While non-null, this replaces whatever the keyguard renderer would have uploaded as its
     * wallpaper texture. Because it becomes the real wallpaper surface, the clock glass and the
     * card blur sample it too - which is the whole point of coming into this process.
     */
    private static volatile Bitmap sArt;

    /**
     * SystemUI is showing lyrics over the cover, so the cover is drawn frosted - blurred and
     * darkened - wherever it would be drawn sharp. Applied inside fittedArt(), which every path
     * (upload, getBitmap short-circuit, both fade ends) already reads, so a track change under
     * the lyrics fades frosted to frosted with nothing else knowing. Cleared when the cover goes.
     */
    private static volatile boolean sLyricBlur;
    private static Bitmap sFrosted, sFrostedOf;

    /**
     * The keyguard engine, captured so a new track can re-run the texture upload without the
     * process being killed. Its GL work all happens on one HandlerThread; nothing here touches
     * GL directly, it only asks the engine to run its own surface-created path again.
     */
    private static volatile Object sKeyguardEngine;

    /** Renderer class names already reported by the diagnostic above; one line each. */
    private static final java.util.Set<String> sUploadNames =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Engine class names seen at construction; one line each. Diagnostic. */
    private static final java.util.Set<String> sEngineNames =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * The desktop wallpaper's engine, captured so its texture can be re-uploaded.
     *
     * Not ours to leave swapped: a failure that strands this one shows the album cover on the
     * user's home screen, so anything that sets it has to have a path that puts it back.
     */
    private static volatile Object sDesktopEngine;

    /**
     * The shade is up, so the DESKTOP wallpaper should be showing the cover.
     *
     * Why the desktop wallpaper at all: the notification shade's glass samples what is BEHIND
     * its window, and the cover SystemUI draws is inside it - so nothing we draw there can ever
     * be what the cards blur. The lock screen solved the same problem the same way, by having
     * the picture where the glass looks. This is that, for the desktop.
     */
    private static volatile boolean sShadeOn;

    /** When the last shade-state message arrived, so a lost one cannot strand the wallpaper. */
    private static volatile long sShadeAt;

    /** The composed cover, fitted to the desktop texture, and the size it was fitted for. */
    private static volatile Bitmap sShadeFitted;
    private static volatile String sShadeFitOf;

    /**
     * How long the desktop stays swapped without being told again.
     *
     * The message that ends it is a broadcast, and a broadcast has no delivery guarantee. This is
     * the only thing standing between a lost one and the user's home screen showing an album
     * cover indefinitely, so it exists, and it is generous enough that an ordinary pull-down -
     * which is seconds long - is refreshed many times over.
     *
     * The cost of expiring early is small by construction: SystemUI draws the cover inside the
     * shade window as well, so the background still looks right; only the glass goes back to
     * sampling the real wallpaper.
     */
    private static final long SHADE_TTL_MS = 10000L;

    private static final String CLS_KEYGUARD_ENGINE =
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl";

    /** Asked for the cover again, at most this often. */
    private static final long ASK_MIN_MS = 2000L;
    /**
     * How many times one dry spell may ask.
     *
     * The gap between this process starting and SystemUI's receiver being up is about two
     * seconds wide, and both of the asks below fire inside it: measured on device, process start
     * at 18:53:04.169 and the engine at 18:53:05.064, SystemUI not loaded until 18:53:06.065,
     * and neither request arrived. So the ask is retried from the renderer for a few seconds.
     *
     * Nothing is retried once art has arrived, which is what keeps a cover that is simply
     * switched off - where this process is empty by design and always will be - from asking
     * forever.
     */
    private static final int ASK_TRIES = 5;
    /**
     * When askForArt() last sent a request, and how many it has sent since art last arrived.
     * Written from the GL thread as well as the main one, so these are only ever coarse.
     */
    private static volatile long sAskedAt;
    private static volatile int sAsks;

    /**
     * Tells SystemUI that this process has no cover to draw, so it should send one again.
     *
     * The push is one-shot and SystemUI only pushes on a track change - and once it has pushed,
     * the same-artwork rule suppresses every later push for that song. So art composed while the
     * wallpaper process was not up is lost for the whole track: measured on device, nine pushes
     * between 16:23 and 16:24 with the receiver here only registering at 16:25:17, and not one of
     * them arrived - the lock screen stayed without a cover until the next track. Nothing on the
     * SystemUI side can notice, because it recorded the print as sent the moment it sent it.
     *
     * So the side that knows it is empty does the asking. Only ever called when there is nothing
     * here, which is also what stops it once the art arrives.
     */
    private static void askForArt(String why) {
        Context c = sCtx;
        if (c == null || sAsks >= ASK_TRIES) return;
        long now = SystemClock.uptimeMillis();
        if (now - sAskedAt < ASK_MIN_MS) return;
        sAskedAt = now;
        sAsks++;
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.putExtra("op", "needart");
            out.putExtra("why", why);
            c.sendBroadcast(out);
            Xp.log(TAG + "no art here, asked SystemUI for it (" + why + " "
                    + sAsks + "/" + ASK_TRIES + ")");
        } catch (Throwable t) {
            Xp.log(TAG + "askForArt failed: " + t);
        }
    }

    // ------------------------------------------------------------------ the crossfade

    /**
     * The real lock wallpaper at texture size - the far end of the fade out of cover mode.
     *
     * Deliberately not persisted. It is only ever learnt by watching one go past in the upload
     * hook, so a copy read back from disk could be a wallpaper the user has since changed, and
     * fading to the wrong picture and then cutting to the right one is worse than not fading at
     * all. Missing it costs exactly one hard cut - the reload that performs it is the reload
     * that learns the original - so this heals itself on first use after a process restart.
     */
    private static volatile Bitmap sOrig;
    private static volatile int sOrigPrint;

    // ------------------------------------------------------------------ the OEM's darkening

    /**
     * Whether the OEM darkens the lock wallpaper, as it last decided, and whether it has decided
     * at all since this process started.
     *
     * Read off the dex: KeyguardImageEngineImpl.W() calls renderer.updateMaskLayerStatus(need,
     * isDark), and isDark is `!(colorHints & SUPPORTS_DARK_TEXT) && support_dark` from the lock
     * slot's MIUI data - nothing to do with dark mode. It lands in AnimImageGLProgram.mDarken,
     * which commonDraw() turns into uDarken, and the shader then pulls every pixel 10% of its
     * brightness towards black: white comes out at 230. A lock slot the module had to split off
     * gets support_dark=true by default, so a user whose lock screen used to follow the desktop
     * sees it come back from cover mode a shade darker than it went in.
     *
     * The cover is not a wallpaper and is not meant to be dimmed, so the flag is withheld from
     * whatever picture is a cover. Per picture, not per moment: the fade in either direction
     * then crossfades the original at its own darkening with an undarkened cover, rather than
     * jumping 10% at one end of it.
     *
     * The original does not get the lock slot's own flag either, but the DESKTOP's - see
     * origDarken(). Keeping the OEM's value there was the first fix, and it left the complaint
     * standing: the user's measure is "the lock screen is darker than the desktop", and on the
     * device that reported it the two slots came back support_dark=true / false.
     */
    private static volatile boolean sOemDarken;
    private static volatile boolean sOemDarkenKnown;
    /**
     * The desktop renderer's own darkening, as the OEM last handed it over. It goes through the
     * same updateMaskLayerStatus(), so the hook reads it there without touching R8-renamed
     * WallpaperServiceController internals.
     */
    private static volatile boolean sHomeDarken;
    private static volatile boolean sHomeDarkenKnown;

    /** The darkening the lock screen's real wallpaper is drawn with: the desktop's, once known. */
    private static boolean origDarken() {
        return sHomeDarkenKnown ? sHomeDarken : sOemDarken;
    }
    /** What the OEM's keyguard texture holds right now is a cover, not the real wallpaper. */
    private static volatile boolean sTexShowsCover;
    /** Set when the program on screen has no mDarken to write; the OEM's value stands from then on. */
    private static volatile boolean sDarkenBroken;
    /** The frame the fade is on. Non-null only while one is running. */
    private static volatile Bitmap sFade;
    /** Reused across fades: a 12MB allocation per transition is itself a dropped frame. */
    private static Bitmap sFadeBuf;
    /**
     * The second buffer, allocated only the first time the first one is still being read.
     *
     * One buffer used to be enough by a handshake: compose, publish, wait for the upload to
     * finish, compose again. But the wait gave up after FADE_ACK_MS, and on a phone whose upload
     * takes longer than that - every frame of the CPU fade rebuilt the EGL context as well as the
     * texture - the next frame was composed straight into the buffer texImage2D was still
     * copying. The uploaded texture then held two blend fractions at once, which is the tearing
     * reported from other phones. So a frame is only ever composed into a buffer that is neither
     * published nor being uploaded, and the second buffer is what keeps a slow upload from
     * stalling the fade instead.
     */
    private static Bitmap sFadeBuf2;
    /** The buffer the GL thread is copying right now, or null. Guarded by FADE_LOCK. */
    private static Bitmap sUploading;
    private static final Object FADE_LOCK = new Object();
    /**
     * True from the moment a blended frame is handed to the engine until the GL thread has
     * finished reading it.
     *
     * One buffer is reused for the whole fade, so composing the next frame into it while the
     * upload is still walking down it puts two different blend fractions in one texture, with a
     * hard horizontal seam between them. That is not theoretical - it is what the tearing looks
     * like on video: the top third of the wallpaper a step or two behind the rest, for several
     * frames in a row. So the fade waits for its frame to be consumed before composing another.
     */
    private static volatile boolean sFadeInFlight;
    private static volatile long sFadeSentAt;
    /**
     * How long to wait for that acknowledgement before composing anyway. A reload that never
     * reaches the upload path - coalesced, or the engine simply not asking for a frame - must
     * cost a dropped frame, not a fade that stops half way.
     */
    private static final long FADE_ACK_MS = 48L;
    private static final Paint sFadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Which fade is the current one, so a track change mid-flight cancels the old one. */
    private static volatile int sFadeGen;
    /**
     * How long the crossfade takes.
     *
     * Apple's whole exit is about 300ms, but matching that number is not the same as matching
     * the feel: the clock is a critically damped spring, so it has covered most of its distance
     * long before it settles, and a linear-ish wallpaper fade of the same total length reads as
     * lagging behind it. Hence the ease-out below.
     *
     * The length itself is matched to the clock's spring rather than picked, and the two moved
     * together when the spring was changed to the OEM's own curve (EASE_COVER): the clock now
     * covers 95% of its travel at 235ms, and a cubic ease-out of length T covers its own 95% at
     * 0.632*T - so 370ms here against the 240 that went with the old, faster spring. Both
     * numbers reach 95% within a millisecond of each other, which is the whole of the rule:
     * 240ms of fade against a 235ms clock would have left the wallpaper sitting still while the
     * clock was still visibly growing, and 430 (the first attempt) is the same fault the other
     * way round.
     *
     * The number is no longer decided here. The clock's response is a setting now, and this is
     * the fade that belongs to it - proportional, because the same 95%-against-95% rule holds
     * for every response once zeta is fixed. SystemUI sends it when it changes and again on
     * every cover entry, and that second push is what covers a restart of this process: the
     * field below is a static with nothing behind it, so the 370 it starts at is only ever
     * right until the first push arrives.
     *
     * The op is kept for the case where SystemUI is not the one being tested:
     *   --es op fadems --ei v 370
     */
    private static volatile long sFadeMs = 370L;
    private static final long FADE_STEP_MS = 16L;
    /**
     * Whether the OEM's frosted copy is regenerated on the fade's frames.
     *
     * On by default, on measurement rather than principle: it is worth 7 -> 9 frames across a
     * 240ms fade, because the round trip to the GL thread is what paces this and the blur is
     * part of it. What it costs is the notification and media cards blurring a wallpaper up to
     * 240ms stale, which nobody can see, and the upload that ends the fade puts it right.
     */
    private static volatile boolean sSkipFrost = true;
    /** Live for the duration of one fade, read by the frosting hook on the GL thread. */
    private static volatile boolean sFrostSkipping;

    // ------------------------------------------------------------------ the GPU crossfade

    /**
     * The crossfade done on the GPU instead of by re-uploading the texture every frame.
     *
     * The CPU fade above is capped by what one frame of it costs, and that turned out not to be
     * the blend: measured on OS4.0.0.35 over a day of track changes, 9-17 frames per ~385ms fade
     * with the blend at 4-6ms and the rest spent waiting 17-89 times for the GL thread. Read off
     * the dex, every reloadTexture() is ImageEngineImpl.L(): U() re-runs onSurfaceCreated() -
     * glCreateProgram + glProgramBinary, glGenTextures, a full texImage2D, glGenerateMipmap, and
     * the frosting - and then, because the request was T(false), finishRendering() tears the EGL
     * context down so the next frame has to rebuild it first. The previous texture is never
     * deleted either; it only goes when that context does.
     *
     * So this uploads each end exactly once. The picture being faded TO goes through one normal
     * reload, which is the OEM's own upload, frosting included, and is the steady state the fade
     * lands on. The picture being faded FROM goes into a texture of ours on the first frame after.
     * Every frame of the fade is then just a redraw - T(true), with the surface-created flag left
     * alone - and after the OEM has drawn its texture, the draw hook below issues the OEM's own
     * draw() once more with our texture bound and constant-alpha blending. Same program, same
     * uniforms, so darken, dark mode, the wake zoom and the reveal apply to both pictures alike.
     *
     * Only for AnimImageGLProgram itself: the glass/gradient/blur programs override commonDraw()
     * with passes of their own, and redrawing their last one would be wrong. Those fall back to
     * the CPU fade, and so does anything this finds it cannot do mid-flight.
     */
    private static final class GpuFade {
        /** The picture in our texture, drawn over the OEM's at the fade's alpha. */
        final Bitmap from;
        /** The picture in the OEM's texture, underneath. */
        final Bitmap to;
        /** The overlay's alpha at the start and at the end. A fresh fade goes 1 -> 0. */
        final float a0, a1;
        /**
         * What the screen shows when this lands. `to` for a fade that ends with the overlay gone;
         * for one that ends with the overlay at full strength, the picture the OEM texture has
         * to be swapped to before the overlay can be let go - see drawGpuFade().
         */
        final Bitmap dest;
        final long durMs;
        final Runnable done;
        /** The fade this one reversed, whose texture of `from` it takes over. GL thread clears it. */
        volatile GpuFade inherit;
        final long startedAt = SystemClock.uptimeMillis();
        /** Set on the GL thread once the upload of the far end has gone through. */
        volatile boolean armed;
        volatile boolean finished;
        volatile long t0;
        volatile int frames;
        volatile long uploadMs;
        volatile int contexts;
        /** The end-of-fade swap of the OEM texture to `dest`: asked for, and landed. */
        volatile boolean swapRequested;
        volatile boolean swapUploaded;
        /** GL thread only: the swap has been posted to the main thread. */
        boolean swapPosted;

        GpuFade(Bitmap from, Bitmap to, float a0, float a1, Bitmap dest, long durMs,
                Runnable done) {
            this.from = from;
            this.to = to;
            this.a0 = a0;
            this.a1 = a1;
            this.dest = dest;
            this.durMs = durMs;
            this.done = done;
        }

        /** The overlay's alpha at `now`, as the GL thread will draw it. */
        float alphaAt(long now) {
            long s = t0;
            if (!armed || s == 0L) return a0;
            float t = Math.min(1f, (now - s) / (float) durMs);
            // The CPU fade's curve, so switching between the two changes the cost, not the feel.
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            return a0 + (a1 - a0) * e;
        }

        /** The picture this fade started from. */
        Bitmap start() {
            return a1 >= 0.5f ? to : from;
        }
    }

    private static volatile GpuFade sGpuFade;
    /** The probe's switch: `--es op gpufade --ez on false` puts the CPU fade back. */
    private static volatile boolean sGpuFadeOn = true;
    /** Set when the draw path met something it cannot handle; the CPU fade is used from then on. */
    private static volatile boolean sGpuFadeBroken;
    private static volatile boolean sDrawHooked;
    /**
     * What the keyguard texture must be uploaded from while a GPU fade runs: the far end of it.
     * Needed on the way OUT of cover mode above all, where the art is still set until the fade
     * lands and the real wallpaper would otherwise be decoded off disk - the 210ms the getBitmap
     * short-circuit exists to avoid.
     */
    private static volatile Bitmap sUploadOverride;
    private static volatile Object sKeyguardRenderer;
    private static Class<?> sPlainProgram;
    /** GL-thread state: our texture, the fade it belongs to and the context it was made in. */
    private static int sGlTex;
    private static GpuFade sGlTexFor;
    private static android.opengl.EGLContext sGlTexCtx;
    private static final int[] sGlInts = new int[4];
    private static final float[] sGlColor = new float[4];
    /** Which FRAME_REQUESTS entry this build answers to, once one has; -1 until then. */
    private static volatile int sFrameReq = -1;
    /** A fade that has not seen its last frame this long after it should have is ended here. */
    private static final long GPU_FADE_GRACE_MS = 1500L;

    public static void handle(XposedModuleInterface.PackageLoadedParam param) {
        sCl = param.getDefaultClassLoader();
        Xp.log(TAG + "loaded into " + PKG);

        // Must be installed at load time: getBitmap() runs when the GL surface is created and
        // the texture is then cached, so a hook added later never sees it.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            // The one place the wallpaper bitmap reaches the GL upload:
            //   onSurfaceCreated() -> mTexture.use(c) -> lambda$onSurfaceCreated$0(Bitmap)
            // Hooking here rather than on WallpaperTexture.getWallpaperBitmap() because
            // thisObject is the renderer, so we can tell the keyguard one from the desktop one
            // and leave the home wallpaper alone.
            Xp.hookAllLambdas(base, "lambda$onSurfaceCreated$0", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean keyguard = chain.getThisObject().getClass().getName().contains("Keyguard");
                // Temporary diagnostic: name every renderer that reaches the upload, so the
                // DESKTOP one can be addressed by name. It shares this code path and is let
                // through below - nothing here changes for it.
                if (!keyguard && sUploadNames.add(chain.getThisObject().getClass().getName())) {
                    Xp.log(TAG + "non-keyguard upload renderer: "
                            + chain.getThisObject().getClass().getName());
                }
                // The desktop wallpaper, while the notification shade is over it. Same
                // substitution the keyguard does below, on the sibling renderer, for the sibling
                // reason: the cards' glass samples what is behind the shade window, so the cover
                // has to BE the wallpaper for it to be what they show.
                if (!keyguard && args.length > 0 && args[0] instanceof Bitmap) {
                    Bitmap cover = shadeCoverFor((Bitmap) args[0]);
                    if (cover != null) {
                        args[0] = cover;
                        return chain.proceed(args);
                    }
                }
                if (keyguard && args.length > 0 && args[0] instanceof Bitmap) {
                    Bitmap orig = (Bitmap) args[0];
                    sKeyguardRenderer = chain.getThisObject();
                    if (sKeyguardTexture == null) {
                        // Kept so the dimension hook can tell the keyguard's texture from the
                        // desktop one's: same class, two instances, and only one of them is ours.
                        try {
                            sKeyguardTexture = Xp.getObjectField(chain.getThisObject(), "mTexture");
                        } catch (Throwable ignored) {
                        }
                    }
                    // The experiment's other half. Everything below - the fade size check, the
                    // art fit, sReportedW/H that fittedArt() scales by - then sees the screen's
                    // size rather than the wallpaper file's, which is the whole point.
                    Bitmap screen = screenSized(orig);
                    if (screen != null) {
                        orig = screen;
                        args[0] = screen;
                    }
                    int w = orig.getWidth(), h = orig.getHeight();
                    if (w != sReportedW || h != sReportedH) {
                        sReportedW = w;
                        sReportedH = h;
                        Xp.log(TAG + "keyguard texture is " + w + "x" + h);
                    }
                    noteRenderState(chain.getThisObject(), w, h);
                    // A GPU fade's far end. Uploaded once, and from then on the fade is drawn
                    // over it, so this is the only upload the whole transition makes.
                    Bitmap ov = sUploadOverride;
                    GpuFade gf = sGpuFade;
                    if (ov != null && gf != null) {
                        if (ov.isRecycled() || ov.getWidth() != w || ov.getHeight() != h) {
                            Xp.log(TAG + "gpu fade dropped: its far end is " + describe(ov)
                                    + " but the texture is " + w + "x" + h);
                            abortGpuFade(gf);
                        } else {
                            args[0] = ov;
                            sTexShowsCover = ov != sOrig;
                            Object r = chain.proceed(args);
                            if (!gf.swapRequested) gf.armed = true;
                            else if (ov == gf.dest) gf.swapUploaded = true;
                            return r;
                        }
                    }
                    // A fade owns the texture outright while it runs: every frame of it is
                    // a blend this process composed, and neither the art nor the original is
                    // what should be uploaded until it lands.
                    Bitmap fade;
                    // Taken under the lock together with marking it as being read, so the fade
                    // cannot pick this buffer to compose into between the two - see pickFadeBuf().
                    synchronized (FADE_LOCK) {
                        fade = sFade;
                        sUploading = fade;
                    }
                    // Size-checked like the art below, and for the same reason: the GL matrix
                    // comes from the bitmap's dimensions, so a bitmap that is not exactly this
                    // texture lands the wallpaper askew - a corner of the picture in a corner
                    // of the screen. A fade composed for a texture that has since changed size
                    // (a new wallpaper, a surface rebuilt at another size) is not something to
                    // fit and show, it is stale: drop it and let the frame below draw the real
                    // art at the real size.
                    if (fade != null && (fade.getWidth() != w || fade.getHeight() != h)) {
                        Xp.log(TAG + "fade dropped: composed for " + describe(fade)
                                + " but the texture is " + w + "x" + h);
                        cancelFade();
                        fade = null;
                        synchronized (FADE_LOCK) {
                            sUploading = null;
                        }
                    }
                    if (fade != null) {
                        args[0] = fade;
                        // A CPU blend is both pictures in one texture, so it cannot be darkened
                        // per picture. It follows the art instead, which only differs from the
                        // GPU fade at the very end of a fade out of cover mode.
                        sTexShowsCover = sArt != null;
                        // proceed() is the upload: once it returns, the buffer has been read
                        // and may be composed into again.
                        try {
                            return chain.proceed(args);
                        } finally {
                            synchronized (FADE_LOCK) {
                                sUploading = null;
                            }
                            sFadeInFlight = false;
                        }
                    }
                    Bitmap art = sArt;
                    sTexShowsCover = art != null;
                    // The one moment the real lock wallpaper passes through here. Once the art
                    // is set the getBitmap short-circuit below means the OEM never decodes it
                    // again, so this is the only chance to learn what to fade back to.
                    if (art == null) {
                        rememberOriginal(orig);
                        // Drawing the keyguard with no cover to draw: the one moment worth
                        // asking, and the retry for the two asks above that fire before
                        // SystemUI's receiver exists. Rate-limited and capped in askForArt().
                        askForArt("renderer");
                    }
                    if (art != null) {
                        // Match the original exactly: updateDimensions/updateMatrix derive
                        // the GL matrix from these, so another size lands the wallpaper askew.
                        Bitmap fitted;
                        if (art.getWidth() == w && art.getHeight() == h) {
                            fitted = art;                   // composed at exactly this size
                        } else if (sFitted != null && sFittedOf == art
                                && sFitted.getWidth() == w && sFitted.getHeight() == h) {
                            fitted = sFitted;               // already fitted for this texture
                        } else {
                            fitted = centerCrop(art, w, h);
                            sFitted = fitted;
                            sFittedOf = art;
                        }
                        fitted = frostedIfWanted(fitted);
                        args[0] = fitted;
                        Xp.log(TAG + "wallpaper texture REPLACED " + describe(orig)
                                + " -> " + describe(fitted)
                                + (fitted == art ? " (no rescale)" : ""));
                    }
                }
                return chain.proceed(args);
            });
            Xp.log(TAG + "upload path hooked on ImageWallpaperRenderer");
        } catch (Throwable t) {
            // Said "getBitmap hook failed" until a HyperOS 3 report came in naming that and
            // the short-circuit below in the same breath. This is the one that matters: with
            // it gone the texture is never replaced, so the cover does nothing at all.
            Xp.log(TAG + "upload path hook FAILED - the cover cannot be drawn: " + t);
        }

        // The experiment's other half. updateMVPMatrix(surfaceW, surfaceH, getTextureDimensions())
        // builds the GL matrix from this rectangle, so a screen-sized upload with a
        // wallpaper-sized source rect draws the picture whichever way the two disagree - a corner
        // of it in a corner of the screen, which is the failure our own art-fit comment describes.
        // Both ends move together or neither does.
        try {
            Class<?> tex = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer$WallpaperTexture", sCl);
            Xp.hookAll(tex, "getTextureDimensions", chain -> {
                Object self = chain.getThisObject();
                if (!sTexFit || self == null || self != sKeyguardTexture) return chain.proceed();
                int w = sSurfaceW, h = sSurfaceH;
                if (w <= 0 || h <= 0) return chain.proceed();
                return new android.graphics.Rect(0, 0, w, h);
            });
            Xp.log(TAG + "texture dimension hook installed");
        } catch (Throwable t) {
            Xp.log(TAG + "texture dimension hook failed: " + t);
        }

        // Measured: of the ~380ms a track change took, 210ms was the OEM's own getBitmap()
        // decoding the real lock wallpaper off disk inside onSurfaceCreated - a bitmap thrown
        // away one call later when we substitute ours. Handing back the art we are going to
        // substitute anyway removes that decode outright. The upload path is still the lambda
        // below; this only short-circuits the source, and only once the texture size is known,
        // so a cold start still learns the real dimensions first.
        try {
            Class<?> kg = Xp.findClass(
                    "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer",
                    sCl);
            Xp.hookAll(kg, "getBitmap", chain -> {
                // Not size-checked here on purpose: getBitmap is the SOURCE, and what the
                // texture ends up being is decided by the lambda above, which does check.
                Bitmap ov = sUploadOverride;
                if (ov != null && sGpuFade != null && !ov.isRecycled()) return ov;
                // Never the fade's buffer itself. What is returned here is read later, outside the
                // lock the lambda takes, while the fade may already be composing the next frame
                // into it. The lambda substitutes the buffer anyway; this only has to be a
                // picture of the right size that nobody writes to.
                if (sFade != null) {
                    Bitmap stable = fittedArt();
                    if (stable == null) stable = sOrig;
                    if (stable != null && !stable.isRecycled()) return stable;
                }
                Bitmap fitted = fittedArt();
                // Returning without proceeding IS the short-circuit: the OEM never decodes
                // the real lock wallpaper off disk, which is the 210ms this buys back.
                return fitted != null ? fitted : chain.proceed();
            });
            Xp.log(TAG + "keyguard getBitmap short-circuit installed");
        } catch (Throwable t) {
            Xp.log(TAG + "getBitmap short-circuit failed: " + t);
        }

        // Runtime refresh. ImageEngineImpl.U() ("preRender", on the GL thread) re-runs
        //     mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h)
        // whenever its pending-surface flag is set, and onSurfaceCreated is the path that ends in
        // mTexture.use(...) -> the lambda we already replace the bitmap in. So a new track only
        // has to set that flag and ask for a frame; the OEM does the upload itself, including the
        // frosted copy the notification cards blur against.
        try {
            Class<?> eng = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Xp.hookAllConstructors(eng, chain -> {
                Object result = chain.proceed();
                sKeyguardEngine = chain.getThisObject();
                Xp.log(TAG + "keyguard engine captured: " + sKeyguardEngine);
                // From here the lock screen has an image engine, so this is the first moment a
                // cover has somewhere to go - and by now the receiver above is up, which is what
                // the pushes that went missing did not have.
                if (sArt == null) askForArt("keyguard engine");
                return result;
            });
            Xp.log(TAG + "keyguard engine hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "keyguard engine hook failed: " + t);
        }

        // The DESKTOP engine, found without knowing its name.
        //
        // The two engines are siblings - the renderers are (`Keyguard`/`Desktop` +
        // `AnimImageWallpaperRenderer`, both under container.openGL) and the reload mechanism
        // that drives them is shared, which is why reloadTexture()'s obfuscated u()/b/T(false)
        // work at all. So hooking the SUPERCLASS of the keyguard engine catches every sibling's
        // construction too, and the class name of each instance tells us which is which. The
        // name is not guessed anywhere in this file; it is read off the instances.
        try {
            Class<?> kg = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Class<?> base = kg.getSuperclass();
            Xp.log(TAG + "engine base class = " + (base == null ? "null" : base.getName()));
            if (base != null) {
                Xp.hookAllConstructors(base, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    String n = self.getClass().getName();
                    if (sEngineNames.add(n)) Xp.log(TAG + "engine instance: " + n);
                    if (n.contains("Desktop")) sDesktopEngine = self;
                    return result;
                });
                Xp.log(TAG + "engine base constructors hooked");
            }
        } catch (Throwable t) {
            Xp.log(TAG + "engine base hook failed: " + t);
        }

        // The video wallpaper's engine, which is what a live lock wallpaper gets instead of
        // KeyguardImageEngineImpl. Captured from the constructor for the same reason: it is
        // built while the process starts, so a hook added later never sees it. The manager
        // that owns the surfaces hangs off it - the manager's own constructor runs too early
        // to hook, and its static instance field is not populated.
        for (String cn : CLS_VIDEO_ENGINES) {
            try {
                Class<?> vd = Xp.findClass(cn, sCl);
                Xp.hookAllConstructors(vd, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    // Desktop has video engines too, and its wallpaper is not ours to touch.
                    if (self.getClass().getName().contains("Keyguard")) {
                        sVideoEngine = self;
                        sVideoDepth = null;
                        Xp.log(TAG + "video engine captured: " + self.getClass().getName());
                    }
                    return result;
                });
                Xp.log(TAG + "video engine hooked on " + cn.substring(cn.lastIndexOf('.') + 1));
            } catch (Throwable t) {
                Xp.log(TAG + "video engine hook failed on " + cn + ": " + t);
            }
        }

        // The frosted copy the notification and media cards blur against is regenerated on
        // every texture upload. That is the right trade once per track change and the wrong one
        // twenty times in a row, so a fade can switch it off for its own frames; the upload that
        // ends the fade is a normal one and puts it right. Only ever engaged from startFade().
        try {
            Class<?> ap = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimatorProgram", sCl);
            Xp.hookAll(ap, "setUpMixFrost", chain -> {
                if (sFrostSkipping) return null;
                return chain.proceed();
            });
            Xp.log(TAG + "frosting hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "frosting hook failed: " + t);
        }

        // The OEM's darkening decision, kept for the original and withheld from the cover. See
        // sOemDarken. Its own block: a build that renames this loses the fix, not the cover.
        try {
            Class<?> ar = Xp.findClass(
                    "com.miui.miwallpaper.opengl.AnimImageWallpaperRenderer", sCl);
            Xp.hookAll(ar, "updateMaskLayerStatus", chain -> {
                Object self = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                if (self == null || args.length != 2 || !(args[1] instanceof Boolean)) {
                    return chain.proceed();
                }
                String cls = self.getClass().getName();
                if (cls.contains("Desktop")) {
                    boolean home = (Boolean) args[1];
                    if (!sHomeDarkenKnown || home != sHomeDarken) {
                        Xp.log(TAG + "OEM darkens the desktop wallpaper: " + home
                                + " - the lock screen's own wallpaper follows it");
                    }
                    sHomeDarken = home;
                    sHomeDarkenKnown = true;
                    return chain.proceed();
                }
                if (!cls.contains("Keyguard")) return chain.proceed();
                boolean dark = (Boolean) args[1];
                if (!sOemDarkenKnown || dark != sOemDarken) {
                    Xp.log(TAG + "OEM darkens the lock wallpaper: " + dark
                            + (dark ? " - withheld from the cover" : ""));
                }
                sOemDarken = dark;
                sOemDarkenKnown = true;
                args[1] = origDarken() && !sTexShowsCover;
                return chain.proceed(args);
            });
            Xp.log(TAG + "darken hooked");
        } catch (Throwable t) {
            Xp.log(TAG + "darken hook failed, the cover stays dimmed where the OEM dims: " + t);
        }

        // The GPU crossfade's frames. Declared on the base class, which the keyguard renderer
        // reaches through AnimImageWallpaperRenderer's super call; the desktop renderer passes
        // through here too and is let alone by the identity check.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            sPlainProgram = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram", sCl);
            Xp.hookAll(base, "onDrawFrame", chain -> {
                if (sOemDarkenKnown && !sDarkenBroken
                        && chain.getThisObject() == sKeyguardRenderer) {
                    applyDarken(chain.getThisObject());
                }
                Object r = chain.proceed();
                if (chain.getThisObject() != sKeyguardRenderer) return r;
                GpuFade f = sGpuFade;
                if (f == null && sGlTex == 0) return r;
                try {
                    drawGpuFade(chain.getThisObject(), f);
                } catch (Throwable t) {
                    sGpuFadeBroken = true;
                    Xp.log(TAG + "gpu fade draw failed, using the CPU fade from now on: "
                            + Log.getStackTraceString(t));
                    if (f != null) abortGpuFade(f);
                }
                return r;
            });
            sDrawHooked = true;
            Xp.log(TAG + "draw hooked for the gpu fade");
        } catch (Throwable t) {
            Xp.log(TAG + "draw hook failed, the fade stays on the CPU: " + t);
        }

        Xp.hook(Xp.findMethodExact(Application.class, "onCreate"), chain -> {
            Object result = chain.proceed();
            try {
                register((Application) chain.getThisObject());
            } catch (Throwable t) {
                Xp.log(TAG + "register failed: " + t);
            }
            return result;
        });
    }

    private static final String ART_FILE = "mc_art.jpg";

    /** The whole of a file, or null. Used for the art SystemUI hands over by path. */
    private static byte[] readBytes(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] buf = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.FileInputStream(f));
            try {
                in.readFully(buf);
            } finally {
                in.close();
            }
            return buf;
        } catch (Throwable t) {
            Xp.log(TAG + "could not read " + path + ": " + t);
            return null;
        }
    }

    /**
     * Decodes the pushed JPEG straight to the size the texture wants. SystemUI composes at the
     * screen's size and the texture is the lock wallpaper's, but the two share an aspect ratio,
     * so this is a pure scale - and doing it inside the decoder replaces a full decode plus a
     * 21MB allocation and filtered draw with a single pass (measured: 140ms -> ~45ms).
     */
    private static Bitmap decodeToTextureSize(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        try {
            if (sReportedW > 0) {
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, probe);
                if (probe.outWidth > 0 && probe.outWidth != sReportedW) {
                    o.inScaled = true;
                    o.inDensity = probe.outWidth;
                    o.inTargetDensity = sReportedW;
                }
            }
        } catch (Throwable ignored) {
        }
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    private static String sRenderState = "";
    private static boolean sRenderStateFailed;
    /** The screen, as MIUI's own renderer has it (mSurfaceSize). 0 until an upload has run. */
    private static volatile int sSurfaceW, sSurfaceH;

    /**
     * The three numbers that decide where the wallpaper lands on screen, read off MIUI's own
     * renderer at the moment of the upload:
     *
     * - mSurfaceSize, which is the glViewport onSurfaceChanged last set;
     * - the texture's dimensions, which is what AnimImageWallpaperRenderer.updateMVPMatrix()
     *   builds the MVP matrix from (updateMVPMatrix(surfaceW, surfaceH, textureDimensions));
     * - the bitmap actually being uploaded.
     *
     * A picture drawn small and cornered is one of these three disagreeing with the other two,
     * and none of them is visible from the SystemUI side or from a screenshot. Logged only when
     * the triple CHANGES, so a steady state costs one string compare per upload and says
     * nothing, and the frame that moves the picture is the one that prints.
     */
    private static void noteRenderState(Object renderer, int w, int h) {
        if (sRenderStateFailed) return;
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            Object texture = Xp.getObjectField(renderer, "mTexture");
            Object dims = texture == null ? null
                    : Xp.callMethod(texture, "getTextureDimensions");
            // Read before the early return below: this is the only place the SCREEN's own size
            // is knowable in this process, and startFade() needs it on every fade, not only on
            // the frames where something changed.
            if (surface instanceof android.graphics.Rect) {
                android.graphics.Rect r = (android.graphics.Rect) surface;
                if (r.width() > 0 && r.height() > 0) {
                    sSurfaceW = r.width();
                    sSurfaceH = r.height();
                }
            }
            String now = "viewport=" + surface + " mvpFrom=" + dims
                    + " upload=" + w + "x" + h;
            if (now.equals(sRenderState)) return;
            sRenderState = now;
            Xp.log(TAG + "render state " + now);
        } catch (Throwable t) {
            sRenderStateFailed = true;
            Xp.log(TAG + "cannot read the renderer's geometry: " + t);
        }
    }

    /**
     * sArt scaled to the texture the keyguard actually uploads, or null while that size is still
     * unknown. Cached, so a track change scales once rather than on every GL callback.
     */
    private static Bitmap fittedArt() {
        return frostedIfWanted(sharpFittedArt());
    }

    private static Bitmap frostedIfWanted(Bitmap sharp) {
        return sharp == null || !sLyricBlur ? sharp : frostedOf(sharp);
    }

    /** The frosted copy of one fitted picture, made once. Called off the GL thread first. */
    private static synchronized Bitmap frostedOf(Bitmap sharp) {
        Bitmap f = sFrosted;
        if (f != null && sFrostedOf == sharp && !f.isRecycled()) return f;
        long t0 = SystemClock.uptimeMillis();
        f = CoverCompose.frosted(sharp);
        sFrosted = f;
        sFrostedOf = sharp;
        Xp.log(TAG + "frosted " + describe(sharp) + " in " + (SystemClock.uptimeMillis() - t0)
                + "ms");
        return f;
    }

    private static Bitmap sharpFittedArt() {
        Bitmap art = sArt;
        if (art == null || sReportedW <= 0 || sReportedH <= 0) return null;
        if (art.getWidth() == sReportedW && art.getHeight() == sReportedH) return art;
        Bitmap cached = sFitted;
        if (cached != null && sFittedOf == art
                && cached.getWidth() == sReportedW && cached.getHeight() == sReportedH) {
            return cached;
        }
        Bitmap fitted = centerCrop(art, sReportedW, sReportedH);
        sFitted = fitted;
        sFittedOf = art;
        return fitted;
    }

    private static void saveArtLater(final Context ctx, final byte[] jpg) {
        new Thread(new Runnable() {
            @Override
            public void run() { saveArt(ctx, jpg); }
        }, "mc-art-save").start();
    }

    private static void saveArt(Context ctx, byte[] jpg) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), ART_FILE));
            fos.write(jpg);
            fos.close();
        } catch (Throwable t) {
            Xp.log(TAG + "saveArt failed: " + t);
        }
    }

    /**
     * The texture is only read when the GL surface is created, i.e. at process start, so the art
     * has to be on disk here before that happens - a bitmap pushed later would not be uploaded
     * until something recreated the surface.
     */
    private static void loadArt(Context ctx) {
        File f = new File(ctx.getFilesDir(), ART_FILE);
        // The source, when the last cover came that way: the JPEG is deleted as soon as a
        // composed-here cover is shown, so whichever of the two is newer is the one on screen.
        File src = new File(ctx.getFilesDir(), SRC_FILE);
        if (src.exists() && (!f.exists() || src.lastModified() >= f.lastModified())) {
            try {
                CoverCompose.Source s = CoverCompose.readSource(src);
                if (s != null) {
                    sArt = CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
                    sAsks = 0;
                    Xp.log(TAG + "art restored from the saved source " + describe(sArt));
                    return;
                }
            } catch (Throwable t) {
                Xp.log(TAG + "the saved source could not be composed: " + t);
            }
        }
        if (!f.exists()) return;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) {
            sArt = b;
            sAsks = 0;
            Xp.log(TAG + "art restored from disk " + describe(b));
        }
    }

    /**
     * Crossfades the keyguard texture from one picture to another.
     *
     * Both ends are already at texture size and both live in this process, so a frame costs one
     * blend and one re-upload and nothing crosses a process boundary - which is the only reason
     * this is affordable at all.
     *
     * The OEM's own reveal animator was the obvious thing to drive and is the wrong shape. Its
     * shader says so itself: "Reveal is the animation value that goes from 1 (the image is
     * hidden) to 0 (the image is visible)", and the branch behind it is
     * blendSrcOver(vec4(0.,0.,0.,uReveal), ori) - one texture fading to black. It can dissolve a
     * picture; it cannot dissolve between two, which is what Apple's transition is.
     *
     * Time-based rather than step-based: a frame that overruns costs a frame, not a longer
     * transition. The clock is springing to its own curve over in SystemUI and cannot wait.
     */
    private static void startFade(final Bitmap from, final Bitmap to, final Runnable done) {
        final Context ctx = sCtx;
        if (ctx == null || from == null || to == null || from.isRecycled() || to.isRecycled()
                || from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
            Xp.log(TAG + "no fade: " + describe(from) + " -> " + describe(to));
            if (done != null) done.run();
            reloadTexture();
            return;
        }
        if (gpuFadeUsable()) {
            startGpuFade(from, to, done);
            return;
        }
        if (fadeTooExpensive(from)) {
            if (done != null) done.run();
            reloadTexture();
            return;
        }
        // Both ends agree with each other by the check above; whether they agree with the
        // TEXTURE is the thing that decides how this looks on screen, and it is the one number
        // that is not in either of them. Logged once per fade so a report of a bad transition
        // can be read off the log instead of guessed at.
        if (from.getWidth() != sReportedW || from.getHeight() != sReportedH) {
            Xp.log(TAG + "fade " + describe(from) + " -> " + describe(to)
                    + " does NOT match the texture " + sReportedW + "x" + sReportedH);
        }
        final int fw = from.getWidth(), fh = from.getHeight();
        final int gen = ++sFadeGen;
        final long t0 = SystemClock.uptimeMillis();
        final long[] spent = {0L, 0L, 0L};  // blend ms, frames, frames waited out
        sFrostSkipping = sSkipFrost;
        sFadeInFlight = false;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                // A newer fade has taken over - a track changed while this one was in the air.
                // It owns sFade and the buffer now, so this one simply stops.
                if (gen != sFadeGen) return;
                long now = SystemClock.uptimeMillis();
                // The GL thread has not finished with the buffer yet. Come back rather than
                // compose over the top of it - see sFadeInFlight.
                if (sFadeInFlight && now - sFadeSentAt < FADE_ACK_MS) {
                    spent[2]++;
                    h.postDelayed(this, 2L);
                    return;
                }
                long el = now - t0;
                if (el >= sFadeMs) {
                    sFade = null;
                    sFadeInFlight = false;
                    sFrostSkipping = false;
                    if (done != null) done.run();
                    // The last upload is a normal one: the real bitmap, and the frosted copy
                    // regenerated from it.
                    reloadTexture();
                    Xp.log(TAG + "fade done in " + el + "ms over " + spent[1] + " frames, blend "
                            + (spent[1] == 0 ? 0 : spent[0] / spent[1]) + "ms/frame, waited "
                            + spent[2] + "x for the upload"
                            + (sSkipFrost ? ", frosting skipped" : ""));
                    return;
                }
                float t = el / (float) sFadeMs;
                // Ease OUT, not smoothstep. The clock it has to keep company with is a spring,
                // and a spring is all front-loaded: most of the movement is over in the first
                // third. A symmetric curve spends that third barely changing, which is exactly
                // when the eye is looking, and then finishes after the clock has stopped.
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                // A buffer nobody can be reading. With both taken - one published and not yet
                // picked up, the other still being uploaded - this frame is skipped rather than
                // composed over one of them, which is what tore the texture on slower phones.
                Bitmap dst = pickFadeBuf(fw, fh);
                if (dst == null) {
                    spent[2]++;
                    h.postDelayed(this, 2L);
                    return;
                }
                long b0 = SystemClock.uptimeMillis();
                blendInto(dst, from, to, e);
                spent[0] += SystemClock.uptimeMillis() - b0;
                spent[1]++;
                synchronized (FADE_LOCK) {
                    sFade = dst;
                }
                sFadeInFlight = true;
                sFadeSentAt = SystemClock.uptimeMillis();
                // Kept alive between frames: T(false) finishes rendering after every frame,
                // which throws the EGL context away for the next one to rebuild.
                reloadEngine(sKeyguardEngine, true);
                h.postDelayed(this, FADE_STEP_MS);
            }
        });
    }

    /**
     * Ends a fade in flight without running its completion. For a fade that has become invalid
     * rather than one that has finished - the buffer it was composing into no longer matches
     * the texture, so nothing it produces from here is worth uploading.
     */
    private static void cancelFade() {
        sFadeGen++;
        sFade = null;
        sFadeInFlight = false;
        sFrostSkipping = false;
    }

    /**
     * A fade buffer of this size that the GL thread cannot be reading, or null if both are busy.
     *
     * The choice is made under FADE_LOCK, and the lambda takes sFade and marks it as uploading
     * under the same lock, so a buffer picked here is neither published (the lambda cannot pick
     * it up while it is being composed) nor mid-upload. Main thread only.
     */
    private static Bitmap pickFadeBuf(int w, int h) {
        synchronized (FADE_LOCK) {
            if (usableFadeBuf(sFadeBuf, w, h)) return sFadeBuf;
            if (usableFadeBuf(sFadeBuf2, w, h)) return sFadeBuf2;
            // Allocate into a slot that is free - never over a buffer that is still in use,
            // or the upload reading it would be reading a recycled or replaced bitmap.
            if (!fadeBufBusy(sFadeBuf)) {
                sFadeBuf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                return sFadeBuf;
            }
            if (!fadeBufBusy(sFadeBuf2)) {
                sFadeBuf2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                return sFadeBuf2;
            }
            return null;
        }
    }

    private static boolean fadeBufBusy(Bitmap b) {
        return b != null && (b == sFade || b == sUploading);
    }

    private static boolean usableFadeBuf(Bitmap b, int w, int h) {
        return b != null && !b.isRecycled() && b.getWidth() == w && b.getHeight() == h
                && !fadeBufBusy(b);
    }

    /** Whether the GPU fade can be trusted with the keyguard as it is right now. */
    private static boolean gpuFadeUsable() {
        if (!sGpuFadeOn || sGpuFadeBroken || !sDrawHooked || sPlainProgram == null) return false;
        Object renderer = sKeyguardRenderer;
        if (renderer == null || sKeyguardEngine == null) return false;
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
            if (prog != null && prog.getClass() == sPlainProgram) return true;
            Xp.log(TAG + "gpu fade not used: the keyguard program is "
                    + (prog == null ? "null" : prog.getClass().getSimpleName()));
        } catch (Throwable t) {
            Xp.log(TAG + "gpu fade not used: " + t);
        }
        return false;
    }

    private static void startGpuFade(Bitmap from, Bitmap to, Runnable done) {
        // A CPU fade still running owns sFade; stop it before the texture changes hands. A GPU
        // fade overtaken by this one simply stops too, without running its completion - the
        // same rule the CPU fade's generation check follows, and for the same reason: the one
        // that matters here is the exit's, which clears the art this new fade has just set.
        cancelFade();
        final GpuFade old = sGpuFade;
        final GpuFade f;
        if (old != null && !old.finished) {
            float a = old.alphaAt(SystemClock.uptimeMillis());
            // Not once the old fade has started swapping its underneath picture: the OEM texture
            // may already be its far end, which this reversal would otherwise take for its start.
            // That fade is visually over by then, so a fresh fade from it is the right thing.
            if (!old.swapRequested && sameImage(to, old.start())) {
                // Going back to where the running fade came from - the big and small clock
                // toggled again before the last toggle had finished. Starting over from `from`
                // would jump the wallpaper to that fade's far end and fade back from there.
                // Both pictures are already on the GPU, so this runs the same pair backwards
                // from the alpha on screen right now, and uploads nothing to do it.
                float target = old.a1 >= 0.5f ? 0f : 1f;
                f = new GpuFade(old.from, old.to, a, target, to, sFadeMs, done);
                f.inherit = old;
                f.armed = old.armed;
                f.t0 = old.armed ? SystemClock.uptimeMillis() : 0L;
                sUploadOverride = old.to;
                sGpuFade = f;
                Xp.log(TAG + "gpu fade reversed at alpha " + Math.round(a * 100f) / 100f
                        + " -> " + target);
            } else {
                // A third picture - a skip while the last one was still fading. Only two
                // pictures can be on screen, so start from whichever of the two dominates it
                // now: the jump is at most half a fade instead of all of one.
                Bitmap near = a >= 0.5f ? old.from : old.to;
                if (near != null && !near.isRecycled() && near.getWidth() == to.getWidth()
                        && near.getHeight() == to.getHeight()) {
                    from = near;
                }
                f = startFreshGpuFade(from, to, done);
            }
        } else {
            f = startFreshGpuFade(from, to, done);
        }
        final android.view.Choreographer ch = android.view.Choreographer.getInstance();
        ch.postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (sGpuFade != f) return;
                if (SystemClock.uptimeMillis() - f.startedAt > f.durMs + GPU_FADE_GRACE_MS) {
                    // The GL thread stopped drawing - the screen went off, most likely. End it
                    // with a real reload: what is uploaded is the far end only for a fade that
                    // lands on its underneath picture, not for one waiting on its swap.
                    Xp.log(TAG + "gpu fade timed out after " + f.frames + " frames (armed="
                            + f.armed + ", swap=" + f.swapRequested + "/" + f.swapUploaded + ")");
                    finishGpuFade(f);
                    reloadTexture();
                    return;
                }
                requestFrame(true);
                ch.postFrameCallback(this);
            }
        });
    }

    /** A fade from `from` to `to` from the start: one reload uploads `to` underneath. */
    private static GpuFade startFreshGpuFade(Bitmap from, Bitmap to, Runnable done) {
        GpuFade f = new GpuFade(from, to, 1f, 0f, to, sFadeMs, done);
        sUploadOverride = to;
        sGpuFade = f;
        reloadEngine(sKeyguardEngine, true);
        return f;
    }

    /**
     * Whether two pictures are the same picture. Identity first; otherwise the same size and the
     * same coarse print, because a cover re-composed for the same track is a new Bitmap with the
     * same pixels, and that is exactly the case a toggle back into cover mode produces.
     */
    private static boolean sameImage(Bitmap a, Bitmap b) {
        if (a == b) return true;
        if (a == null || b == null || a.isRecycled() || b.isRecycled()) return false;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        return print8(a) == print8(b);
    }

    /** Main thread. Lands a fade: its completion runs and the override goes. */
    private static void finishGpuFade(GpuFade f) {
        if (sGpuFade != f) return;
        sGpuFade = null;
        sUploadOverride = null;
        if (f.done != null) f.done.run();
    }

    /** Any thread. Ends a fade that cannot continue, with a plain reload so nothing is stranded. */
    private static void abortGpuFade(final GpuFade f) {
        f.finished = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (sGpuFade != f) return;
                finishGpuFade(f);
                reloadTexture();
            }
        });
    }

    /**
     * GL thread, before the OEM draws: the darkening that belongs to the picture in its texture.
     * Every frame rather than once, because the texture changes hands under reloads the OEM's
     * own updateMaskLayerStatus() does not follow.
     */
    private static void applyDarken(Object renderer) {
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
            if (prog == null) return;
            int want = origDarken() && !sTexShowsCover ? 1 : 0;
            if (((Integer) Xp.getObjectField(prog, "mDarken")).intValue() != want) {
                Xp.setObjectField(prog, "mDarken", want);
            }
        } catch (Throwable t) {
            sDarkenBroken = true;
            Xp.log(TAG + "cannot set the darkening, leaving it to the OEM: " + t);
        }
    }

    /**
     * One frame of the GPU fade, on the GL thread, after the OEM has drawn the far end.
     *
     * Also where our texture is let go of - when its fade has ended or been replaced, and only
     * in the context that made it. A texture of a context that has since been destroyed went
     * with that context and is not ours to delete any more.
     */
    private static void drawGpuFade(Object renderer, final GpuFade f) {
        android.opengl.EGLContext ctx = android.opengl.EGL14.eglGetCurrentContext();
        // A reversed fade takes over the texture of the fade it reversed - same picture, so
        // there is nothing to upload again. The chain is walked because a toggle can reverse a
        // reversal before the GL thread has drawn the first one.
        if (f != null && sGlTex != 0 && sGlTexFor != f && f.inherit != null) {
            int depth = 0;
            for (GpuFade p = f.inherit; p != null && depth < 16; p = p.inherit, depth++) {
                if (p == sGlTexFor && p.from == f.from) {
                    sGlTexFor = f;
                    break;
                }
            }
        }
        if (f != null) f.inherit = null;
        if (sGlTex != 0 && (sGlTexFor != f || f.finished || !ctx.equals(sGlTexCtx))) {
            if (ctx.equals(sGlTexCtx)) {
                GLES20.glDeleteTextures(1, new int[]{sGlTex}, 0);
            }
            sGlTex = 0;
            sGlTexFor = null;
            sGlTexCtx = null;
        }
        if (f == null || f.finished) return;

        long now = SystemClock.uptimeMillis();
        // The clock starts with the first frame that shows the far end underneath, so a reload
        // that lands late shortens nothing: until then the near end is drawn at full strength
        // over whatever the texture still is, which is the near end anyway.
        if (f.armed && f.t0 == 0L) f.t0 = now;
        long el = f.t0 == 0L ? 0L : now - f.t0;
        boolean holding = false;
        if (el >= f.durMs && f.a1 >= 0.5f && !f.swapUploaded) {
            // Landing with the overlay at full strength: the screen already shows `dest`, but
            // from OUR texture. The OEM's has to become it before the overlay can go, or letting
            // go would cut back to the picture underneath. So one reload, and the overlay is
            // held at full strength until the upload hook says it has landed.
            holding = true;
            if (!f.swapPosted) {
                f.swapPosted = true;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (sGpuFade != f) return;
                        sUploadOverride = f.dest;
                        f.swapRequested = true;
                        reloadEngine(sKeyguardEngine, true);
                    }
                });
            }
        }
        if (el >= f.durMs && !holding) {
            f.finished = true;
            if (sGlTex != 0) {
                GLES20.glDeleteTextures(1, new int[]{sGlTex}, 0);
                sGlTex = 0;
                sGlTexFor = null;
                sGlTexCtx = null;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Xp.log(TAG + "gpu fade done in " + (SystemClock.uptimeMillis() - f.startedAt)
                            + "ms from the request, alpha " + Math.round(f.a0 * 100f) / 100f
                            + " -> " + f.a1 + ", " + f.frames + " frames over " + f.durMs
                            + "ms, first upload after " + (f.t0 - f.startedAt) + "ms, near end "
                            + "uploaded in " + f.uploadMs + "ms" + (f.contexts > 1
                            ? ", GL context rebuilt " + (f.contexts - 1) + "x mid-fade" : ""));
                    finishGpuFade(f);
                }
            });
            return;
        }

        Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"), "mProgram");
        if (prog == null || prog.getClass() != sPlainProgram) {
            Xp.log(TAG + "gpu fade dropped: the program became "
                    + (prog == null ? "null" : prog.getClass().getSimpleName()));
            abortGpuFade(f);
            return;
        }
        Object wp = Xp.getObjectField(prog, "mAnimImageGLWallpaper");
        int oemTex = ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue();

        if (sGlTex == 0) {
            if (f.from.isRecycled()) {
                abortGpuFade(f);
                return;
            }
            long u0 = SystemClock.uptimeMillis();
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, f.from, 0);
            sGlTex = ids[0];
            sGlTexFor = f;
            sGlTexCtx = ctx;
            f.contexts++;
            f.uploadMs += SystemClock.uptimeMillis() - u0;
        }

        float alpha = f.alphaAt(now);

        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        int[] fn = sGlInts;
        int[] one = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, one, 0);
        fn[0] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, one, 0);
        fn[1] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, one, 0);
        fn[2] = one[0];
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, one, 0);
        fn[3] = one[0];
        GLES20.glGetFloatv(GLES20.GL_BLEND_COLOR, sGlColor, 0);

        // draw() is a bare glDrawArrays, so the overlay inherits the uniforms commonDraw() set
        // for the picture underneath - uDarken included. When only one of the two is the
        // original, the overlay gets its own value, and it is put back after.
        boolean darkUnder = false, darkOver = false;
        int uDarken = -1;
        if (sOemDarkenKnown && origDarken() && !sDarkenBroken) {
            darkUnder = !sTexShowsCover;
            darkOver = f.from == sOrig;
            if (darkOver != darkUnder) {
                uDarken = ((Integer) Xp.getObjectField(wp, "uDarken")).intValue();
                GLES20.glUniform1i(uDarken, darkOver ? 1 : 0);
            }
        }

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA);
        GLES20.glBlendColor(0f, 0f, 0f, alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sGlTex);
        Xp.callMethod(wp, "draw");

        if (uDarken != -1) GLES20.glUniform1i(uDarken, darkUnder ? 1 : 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oemTex);
        GLES20.glBlendFuncSeparate(fn[0], fn[1], fn[2], fn[3]);
        GLES20.glBlendColor(sGlColor[0], sGlColor[1], sGlColor[2], sGlColor[3]);
        if (!blend) GLES20.glDisable(GLES20.GL_BLEND);
        f.frames++;
    }

    /**
     * Whether a crossfade at this texture size is affordable, measured rather than assumed.
     *
     * Every frame of the fade goes through reloadTexture(), and that is not a cheap poke: it
     * re-runs the OEM's onSurfaceCreated(), which clears the surface, rebuilds the GL program
     * and re-uploads the WHOLE texture. So the cost of a fade is the texture size times the
     * frame count, and it is paid on the GL thread while the clock is springing next to it.
     *
     * Measured on the device, same phone, same 240ms fade, the only difference being the lock
     * wallpaper's own dimensions:
     *
     *   1200x2608 (= the screen)  12.5MB/frame  8 frames  waited 16x for the upload   fine
     *   1579x3432                 21MB/frame    7 frames  waited 51x                  breaks
     *
     * Breaks how: the GL thread falls far enough behind that the compositor picks up a frame
     * from the middle of onSurfaceCreated() - after glClearColor, before updateMVPMatrix - and
     * that frame is the cover drawn small on black, in a corner. Reported from the device as
     * "a small album cover in the top right, then it switches over", in both directions.
     *
     * So the gate is the ratio to the screen, which is the number the frame budget actually
     * scales with. A lock wallpaper the size of the screen is what ensureLockWallpaper() writes
     * and what this was built for; 1.7x the screen is not, and a hard cut is better than a
     * transition that flashes. Unknown surface size fades, as before - never make the OEM's own
     * behaviour worse over a number we have not read yet.
     */
    private static boolean fadeTooExpensive(Bitmap from) {
        long screen = (long) sSurfaceW * sSurfaceH;
        if (screen <= 0) return false;
        long texture = (long) from.getWidth() * from.getHeight();
        if (texture * 2 <= screen * 3) return false;           // <= 1.5x the screen
        Xp.log(TAG + "no fade: " + describe(from) + " is "
                + (Math.round(texture * 10.0 / screen) / 10.0) + "x the screen ("
                + sSurfaceW + "x" + sSurfaceH + ") - one upload of it is "
                + (texture * 4 / (1024 * 1024)) + "MB and the fade needs one per frame."
                + " Swapping in one frame instead.");
        return true;
    }

    /** One frame of the crossfade. Both sources are already exactly dst's size. */
    private static void blendInto(Bitmap dst, Bitmap from, Bitmap to, float t) {
        Canvas cv = new Canvas(dst);
        cv.drawBitmap(from, 0f, 0f, null);
        sFadePaint.setAlpha(Math.round(255f * (t < 0f ? 0f : t > 1f ? 1f : t)));
        cv.drawBitmap(to, 0f, 0f, sFadePaint);
    }

    /**
     * Keeps a copy of the real lock wallpaper, which is the only thing the cover can fade back
     * to. The bitmap handed to the upload hook belongs to the OEM and is recycled behind us, so
     * this has to be a copy - and the fingerprint is what stops it being copied again on every
     * reload that happens while cover mode is off.
     */
    private static void rememberOriginal(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        try {
            int print = print8(b);
            Bitmap have = sOrig;
            if (have != null && !have.isRecycled() && print == sOrigPrint
                    && have.getWidth() == b.getWidth() && have.getHeight() == b.getHeight()) {
                return;
            }
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return;
            sOrig = copy;
            sOrigPrint = print;
            Xp.log(TAG + "lock wallpaper remembered " + describe(copy));
        } catch (Throwable t) {
            Xp.log(TAG + "could not remember the lock wallpaper: " + t);
        }
    }

    /** Coarse identity. Same idea as the module's artPrint, and for the same reason. */
    private static int print8(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 8 || h < 8) return 0;
        int v = w * 31 + h;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                v = v * 31 + b.getPixel(x * (w - 1) / 7, y * (h - 1) / 7);
            }
        }
        return v;
    }

    /** Fills w x h from the source without distorting it, the way CENTER_CROP would. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        cv.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f,
                (w + dw) / 2f, (h + dh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static synchronized void register(Context ctx) {
        if (sRegistered) return;
        sRegistered = true;
        sCtx = ctx.getApplicationContext();
        loadArt(ctx);
        // Restored from disk or not: either way the cover that belongs on screen is the one
        // SystemUI holds, and SystemUI has no way to learn ours is missing.
        //
        // NOT asked for when something was restored, and that is a bug this file has already
        // paid for once: on a process that comes back up as the track changes, the art on disk
        // is the previous track's, this process cannot tell it is the wrong one, and SystemUI
        // never learns the push it sent was lost - so the lock screen keeps that cover for the
        // whole track. Asking on every start fixes exactly that, and was reverted because it
        // does not survive what is underneath it: measured on the phone, an art push takes the
        // wallpaper process down, so a start that asks is answered by a push that kills the
        // process that asked. 1.3s a round, for as long as the module is loaded. The push has
        // to stop killing it before this can be turned back on.
        if (sArt == null) askForArt("process start");
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String op = i.getStringExtra("op");
                byte[] carried = i.getByteArrayExtra("jpg");
                Xp.log(TAG + "recv op=" + op
                        + (carried == null ? " " + i.getExtras() : " jpg=" + carried.length + "B"));
                try {
                    if (i.hasExtra("video")) noteLockWallpaper(i.getBooleanExtra("video", false));
                    if ("cls".equals(op)) {
                        dumpClass(i.getStringExtra("name"), i.getStringExtra("grep"));
                    } else if ("bmp".equals(op)) {
                        traceBitmaps(i.getStringExtra("name"));
                    } else if ("texfit".equals(op)) {
                        // The one switch for the screen-sized keyguard texture, which is on unless
                        // this turns it off: if a phone ever draws the wallpaper into a corner,
                        // this is what to reach for. A reload is what makes the next upload
                        // re-read every one of these numbers, and reloadTexture() is also how the
                        // surface size gets known here (the first upload of a process runs before
                        // onSurfaceChanged has set it).
                        sTexFit = i.getBooleanExtra("on", !sTexFit);
                        Xp.log(TAG + "texture fit to screen " + (sTexFit ? "ON" : "off")
                                + " (surface " + sSurfaceW + "x" + sSurfaceH + ")");
                        reloadTexture();
                    } else if ("art".equals(op)) {
                        final Context cc = c;
                        boolean reload = i.getBooleanExtra("reload", false);
                        // A fade needs both ends of it in this process. Missing either one is
                        // not a failure, it is the old behaviour: swap the texture in one frame.
                        boolean fade = reload && i.getBooleanExtra("fade", false);
                        // Whatever arrives now is newer than a composition still in progress,
                        // which would otherwise land after it and put an older cover back.
                        if (!i.hasExtra("src")) sSrcSeq++;
                        if (i.getBooleanExtra("off", false)) {
                            // A live wallpaper has no texture to fade back to - the way back
                            // is handing the surface to its player again.
                            if (videoPath()) {
                                sArt = null;
                                sFitted = null;
                                sFittedOf = null;
                                new File(c.getFilesDir(), ART_FILE).delete();
                                new File(c.getFilesDir(), SRC_FILE).delete();
                                videoWindowTakeover(true);
                                return;
                            }
                            Bitmap from = fittedArt();
                            Bitmap to = sOrig;
                            if (fade && from != null && to != null) {
                                startFade(from, to, new Runnable() {
                                    @Override
                                    public void run() {
                                        sArt = null;
                                        sFitted = null;
                                        sFittedOf = null;
                                        sLyricBlur = false;
                                        new File(cc.getFilesDir(), ART_FILE).delete();
                                        new File(cc.getFilesDir(), SRC_FILE).delete();
                                        Xp.log(TAG + "art cleared");
                                    }
                                });
                                return;
                            }
                            sArt = null;
                            sLyricBlur = false;
                            new File(c.getFilesDir(), ART_FILE).delete();
                            new File(c.getFilesDir(), SRC_FILE).delete();
                            Xp.log(TAG + "art cleared");
                        } else if (i.hasExtra("src")) {
                            composeFromSource(c, i.getStringExtra("src"), reload, fade);
                            return;
                        } else {
                            byte[] jpg = i.getByteArrayExtra("jpg");
                            String file = i.getStringExtra("file");
                            // The image normally arrives as a file rather than as an extra -
                            // SystemUI writes it where both processes can read it, because a
                            // large enough JPEG does not survive the trip through Binder at
                            // all. See the send in Main.pushArt(). Read here rather than
                            // decodeFile() so that the rest of this is unchanged: the same
                            // decoder scales to the texture, and the same bytes are what gets
                            // written to this process's own copy on disk for the next start.
                            if (jpg == null && file != null) jpg = readBytes(file);
                            Bitmap b = null;
                            if (jpg != null) b = decodeToTextureSize(jpg);
                            if (b == null) {
                                Xp.log(TAG + "art decode failed (jpg="
                                        + (jpg == null ? "null" : jpg.length + "B")
                                        + " file=" + file + ")");
                            } else {
                                applyArt(b, reload, fade);
                                // Show it first, write it to disk afterwards: the file only
                                // matters for the next cold start of this process, and a 100KB
                                // write in front of the upload is pure added latency.
                                if (jpg != null) saveArtLater(c, jpg);
                                return;
                            }
                        }
                        if (reload) reloadTexture();
                    } else if ("shadeart".equals(op)) {
                        // The notification shade went up or came down. Swapping the DESKTOP
                        // texture is what puts the cover where the cards' glass samples, which is
                        // the only way it can ever be what they blur.
                        boolean on = i.getBooleanExtra("on", false);
                        // Refreshed on every message, including repeats: the repeats are the
                        // heartbeat that keeps the TTL from expiring under an open shade.
                        if (on) sShadeAt = android.os.SystemClock.uptimeMillis();
                        if (on != sShadeOn) {
                            sShadeOn = on;
                            Xp.log(TAG + "shade art " + (on ? "ON - desktop wallpaper is the cover"
                                    : "off - desktop wallpaper back to its own picture"));
                            reloadDesktopTexture();
                        }
                    } else if ("lyricblur".equals(op)) {
                        setLyricBlur(i.getBooleanExtra("on", false));
                    } else if ("reload".equals(op)) {
                        reloadTexture();
                    } else if ("fadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sFadeMs);
                        sFadeMs = v < 60L ? 60L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "crossfade is now " + sFadeMs + "ms");
                    } else if ("hello".equals(op)) {
                        sayHello(c);
                    } else if ("gpufade".equals(op)) {
                        sGpuFadeOn = i.getBooleanExtra("on", !sGpuFadeOn);
                        if (sGpuFadeOn) sGpuFadeBroken = false;
                        Xp.log(TAG + "gpu fade " + (sGpuFadeOn ? "on" : "off")
                                + " (draw hooked=" + sDrawHooked + ", usable now="
                                + gpuFadeUsable() + ")");
                    } else if ("nofrost".equals(op)) {
                        sSkipFrost = i.getBooleanExtra("on", !sSkipFrost);
                        Xp.log(TAG + "frosting during a fade is "
                                + (sSkipFrost ? "skipped" : "kept"));
                    } else if ("state".equals(op)) {
                        Xp.log(TAG + "art=" + describe(sArt)
                                + " orig=" + describe(sOrig)
                                + " fading=" + (sFade != null)
                                + " gpuFade=" + (sGpuFade != null) + "/on=" + sGpuFadeOn
                                + "/broken=" + sGpuFadeBroken + "/hooked=" + sDrawHooked
                                + " nofrost=" + sSkipFrost
                                + " fadems=" + sFadeMs
                                + " engine=" + sKeyguardEngine
                                + " videoEngine=" + sVideoEngine);
                    } else if ("vgl".equals(op)) {
                        videoWindowTakeover(i.getBooleanExtra("on", true));
                    } else {
                        Xp.log(TAG + "ops: cls --es name <fqcn> [--es grep x]"
                                + " | bmp --es name <fqcn>");
                    }
                } catch (Throwable t) {
                    Xp.log(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        Xp.log(TAG + "receiver registered for " + ACTION);
        // Unprompted as well as when asked: a restart of this process alone would otherwise
        // leave SystemUI on whatever it last heard, which may be nothing.
        sayHello(ctx);
    }

    /**
     * Frosts the cover for the lyrics, or clears it. The blur is made on the composer thread; the
     * fade waits for one already in the air - the cover arriving, a track changing - to land
     * first, because a new fade starts from the picture it is handed, not from what is on screen.
     */
    private static void setLyricBlur(final boolean on) {
        composer().post(new Runnable() {
            @Override
            public void run() {
                Bitmap sharp = sharpFittedArt();
                if (on && sharp != null) frostedOf(sharp);
                final long t0 = SystemClock.uptimeMillis();
                final Handler h = new Handler(Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (on == sLyricBlur) return;
                        if ((sFade != null || sGpuFade != null)
                                && SystemClock.uptimeMillis() - t0 < 1500L) {
                            h.postDelayed(this, 30L);
                            return;
                        }
                        Bitmap from = fittedArt();
                        sLyricBlur = on;
                        Bitmap to = fittedArt();
                        Xp.log(TAG + "lyric blur " + (on ? "on" : "off") + " (waited "
                                + (SystemClock.uptimeMillis() - t0) + "ms)");
                        if (sArt == null || videoPath()) return;
                        if (from != null && to != null) startFade(from, to, null);
                        else reloadTexture();
                    }
                });
            }
        });
    }

    /** Tells SystemUI this build composes covers from their source. See Main.sWpComposes. */
    private static void sayHello(Context c) {
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "wphello");
            out.putExtra("composes", true);
            c.sendBroadcast(out);
        } catch (Throwable t) {
            Xp.log(TAG + "hello failed: " + t);
        }
    }

    /**
     * Puts a new picture in as the art: the fade from what is on screen now, or a plain reload.
     * Main thread.
     */
    private static void applyArt(Bitmap b, boolean reload, boolean fade) {
        // Read before sArt moves: on the way into cover mode this is the lock wallpaper, and on
        // a track change it is the album that is on screen right now.
        Bitmap from = fittedArt();
        if (from == null) from = sOrig;
        sArt = b;
        sAsks = 0;
        sFitted = null;
        sFittedOf = null;
        Bitmap to = fittedArt();   // scale here, not on the GL thread
        Xp.log(TAG + "art set " + describe(b));
        if (videoPath()) videoWindowTakeover(false);
        else if (fade && from != null && to != null) startFade(from, to, null);
        else if (reload) reloadTexture();
    }

    /** Where this process keeps the last source, to compose again after its own restart. */
    private static final String SRC_FILE = "mc_src.raw";

    private static Handler sComposer;
    /** The newest source asked for; a composition overtaken by a newer one is dropped. */
    private static volatile int sSrcSeq;

    private static synchronized Handler composer() {
        if (sComposer == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("mc-compose",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            sComposer = new Handler(t.getLooper());
        }
        return sComposer;
    }

    /**
     * Composes the cover here from the source SystemUI handed over, then applies it.
     *
     * Off the main thread, because the main thread is where the GPU fade's frames are asked
     * for, and a fast skip lands this while the previous track's fade is still running.
     */
    private static void composeFromSource(final Context c, final String path,
                                          final boolean reload, final boolean fade) {
        final int seq = ++sSrcSeq;
        final long t0 = SystemClock.uptimeMillis();
        composer().post(new Runnable() {
            @Override
            public void run() {
                if (seq != sSrcSeq) return;
                final Bitmap b;
                final long read, composed;
                try {
                    CoverCompose.Source s = CoverCompose.readSource(new File(path));
                    if (s == null) {
                        Xp.log(TAG + "source at " + path + " is not one this build reads");
                        return;
                    }
                    read = SystemClock.uptimeMillis();
                    if (seq != sSrcSeq) return;
                    b = CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
                    composed = SystemClock.uptimeMillis();
                    saveSourceLater(c, s);
                } catch (Throwable t) {
                    Xp.log(TAG + "composing from the source failed: " + t);
                    return;
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != sSrcSeq) {
                            Xp.log(TAG + "composed cover overtaken by a newer one, dropped");
                            return;
                        }
                        Xp.log(TAG + "composed from the source: read " + (read - t0)
                                + "ms, compose " + (composed - read) + "ms, to the main thread "
                                + (SystemClock.uptimeMillis() - composed) + "ms");
                        applyArt(b, reload, fade);
                        // The JPEG from the old path is now older than what is on screen.
                        new File(c.getFilesDir(), ART_FILE).delete();
                    }
                });
            }
        });
    }

    /** This process's own copy of the source, for its next cold start. Low priority, later. */
    private static void saveSourceLater(final Context c, final CoverCompose.Source s) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    CoverCompose.writeSource(new File(c.getFilesDir(), SRC_FILE),
                            s.src, s.w, s.h, s.bias);
                } catch (Throwable t) {
                    Xp.log(TAG + "saving the source failed: " + t);
                }
            }
        }, "mc-src-save").start();
    }

    /**
     * The ways to ask the engine to run its preRender step, and the arguments it takes there.
     *
     * Ordered newest-name-first. The names are R8-obfuscated (OS4.0.0.35: u, b, T) and a build
     * that renamed one used to take the whole reload down with it - the log below is what that
     * looked like, and what it cost is in Handoff 27.
     */
    private static final Object[][] FRAME_REQUESTS = {
            {"T", Boolean.FALSE},
            // OS4.0.0.17's name for the same method, read off its bytecode rather than inferred
            // from the log: there ImageEngineImpl.M(Z) and U(Z) stand where 0.35 has L(Z) and
            // T(Z). M and L are instruction-for-instruction identical, and U and T both open on
            // `iget-boolean h:Z; if-eqz; iget-object t:HashMap; s()` - the post-a-frame path.
            // What settles the pairing is the rest of that set: changeScrollWithScreen and g
            // kept their names across both builds, and those are what align the two lists.
            {"U", Boolean.FALSE},
            // The OEM's own showKeyguardWallpaper(ZI)/hideKeyguardWallpaper(ZI) take a boolean
            // and an int, so a build that widened the frame request the same way is worth one
            // more try. 0 is the no-animation value everywhere these appear.
            {"T", Boolean.FALSE, Integer.valueOf(0)},
    };

    /**
     * Re-uploads the wallpaper texture in place.
     *
     * The engine's field b is the "surface needs creating" flag its preRender step reads, u()
     * is what the OEM calls to arm it, and T(false) posts that preRender onto the GL thread -
     * which is the path that ends in the texture being re-read. Setting the field as well as
     * calling u() is deliberate: u() is obfuscated, and this is the one bit that decides whether
     * the frame re-reads the texture or just redraws the old one.
     *
     * Every step stands on its own now. On a 1080x2400 HyperOS build T does not exist at all:
     * the flag was still armed, the request threw, one "reload failed" went to the log, and the
     * texture kept the album art. Leaving cover mode then put the depth layer back but not the
     * wallpaper, so the lock screen read as the cover stuck behind the subject. See Handoff 27.
     */
    /**
     * The cover the desktop wallpaper should be showing, fitted to the texture it is about to be
     * uploaded in place of.
     *
     * Fitted to the ORIGINAL's dimensions rather than to the screen's, because that is the rule
     * the keyguard side learned the hard way: updateDimensions/updateMatrix derive the GL matrix
     * from the bitmap's size, so anything that is not exactly this texture lands the wallpaper
     * askew - a corner of the picture in a corner of the screen.
     *
     * Returns null for "leave it alone", which is every case except an open shade with art.
     */
    private static Bitmap shadeCoverFor(Bitmap orig) {
        if (!sShadeOn) return null;
        if (android.os.SystemClock.uptimeMillis() - sShadeAt > SHADE_TTL_MS) {
            // Nothing has refreshed this, so the shade is not up any more and the message that
            // would have said so never arrived. Put the desktop back.
            sShadeOn = false;
            Xp.log(TAG + "shade art expired with no word - desktop wallpaper back to its own");
            return null;
        }
        Bitmap art = sArt;
        if (art == null || art.isRecycled() || orig == null) return null;
        final int w = orig.getWidth(), h = orig.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (art.getWidth() == w && art.getHeight() == h) return art;
        final String key = w + "x" + h;
        Bitmap fitted = sShadeFitted;
        if (fitted != null && !fitted.isRecycled() && key.equals(sShadeFitOf)) return fitted;
        try {
            fitted = Bitmap.createScaledBitmap(art, w, h, true);
        } catch (Throwable t) {
            Xp.log(TAG + "shade art could not be fitted to " + key + ": " + t);
            return null;
        }
        final Bitmap old = sShadeFitted;
        sShadeFitted = fitted;
        sShadeFitOf = key;
        if (old != null && old != fitted && !old.isRecycled()) old.recycle();
        return fitted;
    }

    /**
     * Re-runs the DESKTOP renderer's own surface-created path, the same way reloadTexture() does
     * for the keyguard: the engine's pending-surface flag plus its own frame request, so the OEM
     * does the upload rather than us touching GL.
     */
    private static void reloadDesktopTexture() {
        Object eng = sDesktopEngine;
        if (eng == null) {
            Xp.log(TAG + "reload: no desktop engine captured - the desktop wallpaper cannot be "
                    + "swapped, so the cards' glass will keep showing the wallpaper");
            return;
        }
        reloadEngine(eng);
    }

    private static void reloadTexture() {
        Object eng = sKeyguardEngine;
        if (eng == null) {
            Xp.log(TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        reloadEngine(eng);
    }

    private static void reloadEngine(Object eng) {
        reloadEngine(eng, false);
    }

    /**
     * keepAlive asks for the frame with T(true). Read off OS4.0.0.35: the boolean is what
     * ImageEngineImpl.L() hands its post-render step, and false runs finishRendering() on the
     * spot - the EGL surface and context are destroyed after the frame - where true defers that
     * by a second. So a run of frames wants true: with false every one of them pays for a new
     * context, and U() treats a new context as a new surface and re-uploads everything.
     */
    private static void reloadEngine(Object eng, boolean keepAlive) {
        if (eng == null) return;
        try {
            Xp.callMethod(eng, "u");
        } catch (Throwable t) {
            Xp.log(TAG + "reload: u() failed: " + t);
        }
        try {
            Xp.setBooleanField(eng, "b", true);
        } catch (Throwable t) {
            Xp.log(TAG + "reload: the pending-surface field failed: " + t);
        }
        if (frameRequest(eng, keepAlive)) {
            // Not per frame: a fade asks for a reload every frame, and this log goes through
            // LSPosed's binder and file on each one.
            if (!keepAlive) {
                Xp.log(TAG + "reload requested on " + eng.getClass().getSimpleName()
                        + " via " + FRAME_REQUESTS[sFrameReq][0] + "()");
            }
            return;
        }
        reportNoFrameRequest(eng);
    }

    /** A redraw of what is uploaded, with no reload - the GPU fade's per-frame request. */
    private static void requestFrame(boolean keepAlive) {
        Object eng = sKeyguardEngine;
        if (eng != null && !frameRequest(eng, keepAlive)) reportNoFrameRequest(eng);
    }

    /** Tries the known frame requests, the one that answered last time first. */
    private static boolean frameRequest(Object eng, boolean keepAlive) {
        int known = sFrameReq;
        if (known >= 0 && callFrameRequest(eng, FRAME_REQUESTS[known], keepAlive)) return true;
        for (int i = 0; i < FRAME_REQUESTS.length; i++) {
            if (i == known) continue;
            if (callFrameRequest(eng, FRAME_REQUESTS[i], keepAlive)) {
                sFrameReq = i;
                return true;
            }
        }
        return false;
    }

    private static boolean callFrameRequest(Object eng, Object[] req, boolean keepAlive) {
        Object[] args = new Object[req.length - 1];
        System.arraycopy(req, 1, args, 0, args.length);
        if (args.length > 0 && args[0] instanceof Boolean) args[0] = keepAlive;
        try {
            Xp.callMethod(eng, (String) req[0], args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reportNoFrameRequest(Object eng) {
        // Nothing to call, so say what this build DOES have: the methods taking one boolean are
        // the only candidates, and naming them is the whole of what re-deriving the name needs.
        Xp.log(TAG + "reload: no frame request on " + eng.getClass().getSimpleName()
                + " - the texture keeps what it holds until the OEM rebuilds the surface."
                + " Methods here that take one boolean: " + oneBooleanMethods(eng));
    }

    /** One-boolean methods declared on the engine and its supers, for re-deriving a name. */
    private static String oneBooleanMethods(Object eng) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> c = eng.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 1 || p[0] != boolean.class) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(c.getSimpleName()).append('.').append(m.getName()).append("(Z)");
                }
            }
        } catch (Throwable t) {
            return "could not be listed: " + t;
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /**
     * The video wallpaper's manager, captured so the cover can reach the wallpaper WINDOW.
     *
     * A video lock wallpaper is drawn by FastPlayer into three surfaces, held on this object:
     * .c is the alpha one and .d the normal one - both handed over from SystemUI and shown
     * there as TextureViews - and **.e ("mLocalSurface") is this process's own wallpaper
     * window**. That last one is the one the clock's liquid glass and the media card's blur
     * sample, which is why covering the TextureViews in SystemUI changed the background and
     * left the glass and the card still showing the video.
     */
    private static volatile Object sVideoDepth;
    /** KeyguardVideoDepthEngineImpl, which owns the manager above on its field `p`. */
    private static volatile Object sVideoEngine;
    /**
     * The two base classes a live video lock wallpaper can be running on. Which one MIUI picks
     * is the wallpaper's effect type, and it changes underneath you: the same wallpaper on this
     * phone reported `lockEffectType = 10` (depth) at one point and `0` (plain) later, i.e. a
     * different engine class and a different internal shape. Both are hooked, and the base
     * class is hooked rather than the Keyguard subclass so one hook covers the family - a
     * subclass constructor runs its super's, so the hook still fires with the subclass instance.
     */
    private static final String[] CLS_VIDEO_ENGINES = {
            "com.miui.miwallpaper.wallpaperservice.impl.VideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.VideoEngineImpl",
    };

    /**
     * Paints the cover into the wallpaper window a video wallpaper is playing into, by taking
     * that one surface off FastPlayer and drawing on it directly.
     *
     * FastPlayer.changeOpenGLSurface(alpha, on, normal, on, local, on) is the OEM's own way of
     * turning an individual output on and off - it is what MIUI calls when the surfaces come
     * and go - so switching the local one off is asking the player to let go rather than
     * fighting it for the buffer. Only then can lockCanvas() have it: a Surface connected to
     * GL cannot also be locked for a software canvas.
     *
     * The video keeps playing into the other two the whole time, so nothing has to be resumed,
     * re-seeked or re-decoded on the way back - the surface is simply handed over again.
     */
    /** Whether the video's surface is currently ours rather than its player's. */
    private static volatile boolean sVideoTakenOver;

    /**
     * What the lock wallpaper is now, as SystemUI reads it before every push. null until a push
     * has said.
     */
    private static volatile Boolean sLockIsVideo;

    /**
     * Whether this push is for a live lock wallpaper.
     *
     * Having a video engine is not the same question, and answering with it alone was a bug:
     * the engine is captured in a constructor, the constructor runs once, and this process
     * outlives any number of wallpaper changes. Set a video lock wallpaper and then set a still
     * one back, and sVideoEngine is still there - so every push took the video path, which on
     * the depth shape does nothing at all (see takeoverDepth), and the still wallpaper's texture
     * was never replaced. Measured on the device: cover mode on, "art set" logged here, and
     * nothing on the lock screen. Both have to be true, and the engine alone never decides.
     */
    private static boolean videoPath() {
        Boolean live = sLockIsVideo;
        return sVideoEngine != null && (live == null || live);
    }

    /** Told, not guessed: SystemUI carries the answer on every broadcast. */
    private static void noteLockWallpaper(boolean video) {
        Boolean was = sLockIsVideo;
        sLockIsVideo = video;
        if (was != null && was == video) return;
        Xp.log(TAG + "the lock wallpaper is " + (video ? "a live one" : "a still picture")
                + (was == null ? "" : ", it was not"));
        // Release is paired with the take, always: a wallpaper that is no longer a video must
        // not be left with our canvas where its player's surface should be.
        if (!video && sVideoTakenOver) videoWindowTakeover(true);
    }

    private static boolean videoWindowTakeover(boolean on) {
        Object eng = sVideoEngine;
        if (eng == null) {
            Xp.log(TAG + "vgl: no video engine - is the lock wallpaper a video?");
            return false;
        }
        Object mgr = videoDepthManager();
        try {
            return mgr != null ? takeoverDepth(mgr, on) : takeoverPlain(eng, on);
        } catch (Throwable t) {
            Xp.log(TAG + "vgl failed: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * The depth shape: FastPlayer renders into three surfaces held by a VideoDepthManager, and
     * changeOpenGLSurface(alpha, on, normal, on, local, on) is the OEM's own way of switching
     * an individual output off. Asking the player to let go beats fighting it for the buffer -
     * a Surface connected to GL cannot also be locked for a software canvas.
     */
    private static boolean takeoverDepth(Object mgr, boolean on) throws Exception {
        // Deliberately does nothing. The depth shape cannot be taken over, and TRYING breaks
        // the video.
        //
        // Switching the local output off with changeOpenGLSurface(local=false) does not release
        // the buffer - FastPlayer's native GL context still holds the EGLSurface - so
        // lockCanvas throws IllegalArgumentException. Handing it a null local surface with
        // setSurface(alpha, normal, null, 12, null), the depth equivalent of d(null)/h(null) on
        // the plain shape, does not release it either. Both measured.
        //
        // The reverting version of this was worse than useless: setSurface is not a cheap
        // toggle, it re-initialises the player's outputs, and calling it to null and straight
        // back left the video frozen with no way home short of restarting the wallpaper
        // process. Reported from the device as "the video sticks and never becomes playable
        // again" - which is a broken lock screen, and strictly worse than this shape simply
        // not having the cover on its card and clock glass.
        //
        // So on this shape the cover is the keyguard-layer view alone: the background is right
        // and the media card blur and the clock glass keep showing the video. If this is ever
        // revisited, the thing to try is the Bitmap parameter setSurface already takes and MIUI
        // always passes null for - letting FastPlayer draw the picture would sidestep
        // lockCanvas entirely. Do not go back to disabling the output.
        if (!on && !sDepthWarned) {
            sDepthWarned = true;
            Xp.log(TAG + "vgl: this is the depth engine - the wallpaper window cannot be taken "
                    + "over on it, so the card blur and the clock glass keep the video. The "
                    + "cover is still drawn in the keyguard layer.");
        }
        return false;
    }

    /** So the explanation above is logged once per process, not once per track. */
    private static volatile boolean sDepthWarned;

    /**
     * The plain shape: a VideoPlayer on the engine's field `e` drawing into the engine's own
     * SurfaceHolder on `o` - which IS the wallpaper window. Stopping the player is what frees
     * the surface; start() puts the video back.
     */
    private static boolean takeoverPlain(Object eng, boolean on) throws Exception {
        Object player = Xp.getObjectField(eng, "e");
        Object holder = Xp.getObjectField(eng, "o");
        if (player == null || !(holder instanceof android.view.SurfaceHolder)) {
            Xp.log(TAG + "vgl: player=" + player + " holder=" + holder);
            return false;
        }
        android.view.SurfaceHolder h = (android.view.SurfaceHolder) holder;
        if (on) {
            if (!sVideoTakenOver) return true;
            sVideoTakenOver = false;
            // Give the surface BACK before starting. Taking it away was d(null)/h(null), and
            // start() on its own just plays into nowhere - the window keeps showing the last
            // frame we painted, i.e. the cover, for good. That is what "the video never comes
            // back" was.
            setPlayerHolder(player, h);
            Xp.callMethod(player, "start");
            Xp.log(TAG + "vgl(plain): surface handed back, player restarted");
        } else if (sVideoTakenOver) {
            // Already ours - a track change, not an entry. The player is stopped and the
            // surface is already detached, so this is one repaint and nothing else.
            paintCoverOnto(h.getSurface());
        } else {
            sVideoTakenOver = true;
            Xp.callMethod(player, "stop");
            // stop() ends the decode loop but leaves the surface connected to the decoder, and
            // a connected Surface cannot be locked for a software canvas - lockCanvas throws
            // IllegalArgumentException. Hand the player a null holder to make it let go.
            setPlayerHolder(player, null);
            Xp.log(TAG + "vgl(plain): player stopped and surface released");
            paintCoverOnto(h.getSurface());
        }
        return true;
    }

    /**
     * The VideoDepthManager, reached through the engine that owns it.
     *
     * Not from its own constructor - that runs before the module is loaded - and not from its
     * static instance field either, which is left null on this build. The engine is
     * constructible after we are in, and holds the manager on field `p`.
     */
    private static Object videoDepthManager() {
        Object mgr = sVideoDepth;
        if (mgr != null) return mgr;
        Object eng = sVideoEngine;
        if (eng == null) return null;
        try {
            mgr = Xp.getObjectField(eng, "p");
            // `p` is only the manager on the depth engine - on the plain one it is an unrelated
            // obfuscated field of the same name, and reading it as a manager was good for one
            // confusing "no field k1.f.j". The type is the thing that decides which shape this
            // engine is, so check it rather than trusting the field name.
            if (mgr != null && !mgr.getClass().getName().contains("VideoDepthManager")) {
                mgr = null;
            }
            if (mgr != null) {
                sVideoDepth = mgr;
                Xp.log(TAG + "video depth manager: " + mgr);
            }
            return mgr;
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: cannot reach the VideoDepthManager: " + t);
            return null;
        }
    }

    /**
     * Sets - or clears, with null - the holder the VideoPlayer draws into.
     *
     * Both `d` and `h` take a SurfaceHolder and which one actually binds is an R8 name away
     * from being knowable, so both are called and whichever exists wins. Symmetric on purpose:
     * the release and the hand-back have to be the same pair, or the video never comes back.
     */
    private static void setPlayerHolder(Object player, android.view.SurfaceHolder holder) {
        for (String name : new String[]{"d", "h"}) {
            try {
                Method m = Xp.findMethodExact(player.getClass(), name,
                        android.view.SurfaceHolder.class);
                m.invoke(player, holder);
            } catch (Throwable t) {
                Xp.log(TAG + "vgl(plain): " + name + "(" + (holder == null ? "null" : "holder")
                        + ") -> " + t);
            }
        }
    }

    /** Draws the current art over a surface FastPlayer has just let go of. */
    private static boolean paintCoverOnto(android.view.Surface s) {
        Bitmap art = sArt;
        if (art == null || !s.isValid()) {
            Xp.log(TAG + "vgl: nothing to paint (art=" + describe(art)
                    + " valid=" + s.isValid() + ")");
            return false;
        }
        Canvas cv = null;
        boolean painted = false;
        try {
            cv = s.lockCanvas(null);
            RectF dst = new RectF(0, 0, cv.getWidth(), cv.getHeight());
            cv.drawBitmap(art, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
            Xp.log(TAG + "vgl: painted " + describe(art) + " onto the wallpaper window "
                    + cv.getWidth() + "x" + cv.getHeight());
            painted = true;
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: lockCanvas failed: " + t);
        } finally {
            if (cv != null) {
                try {
                    s.unlockCanvasAndPost(cv);
                } catch (Throwable t) {
                    Xp.log(TAG + "vgl: unlockCanvasAndPost failed: " + t);
                }
            }
        }
        return painted;
    }

    private static void dumpClass(String name, String grep) {
        if (name == null) { Xp.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, sCl);
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
                if (g == null || sb.toString().toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + sb);
                }
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                String line = f.getType().getSimpleName() + " ." + f.getName();
                if (g == null || line.toLowerCase().contains(g)) {
                    Xp.log(TAG + "  " + line);
                }
            }
            Xp.log(TAG + "  --- ^ " + k.getName());
        }
    }

    /**
     * Hooks every method of a class that carries a Bitmap in or out and logs it, which is how
     * we find where the wallpaper texture actually enters the renderer.
     */
    /**
     * Hooks every Bitmap-carrying method and constructor on a renderer, and logs each call with
     * its arguments and result. Reached only through the `bmp` probe op.
     *
     * It used to run at load time, for the whole list of renderers, to find out how the wallpaper
     * bitmap travels into the GL texture. That question is answered (the answer is
     * onSurfaceCreated -> mTexture.use -> the lambda we replace the bitmap in, which is where the
     * module has worked since), and the cost of leaving it on was a hook on every one of those
     * methods in every wallpaper process, plus a log line per call, whether or not anyone was
     * looking. It stays as a probe because the first step in porting this module to a build whose
     * R8 names have moved is reading them back off the phone, and this is the tool that does it.
     */
    private static void traceBitmaps(String name) {
        if (name == null) { Xp.log(TAG + "need --es name"); return; }
        Class<?> c;
        try {
            c = Xp.findClass(name, sCl);
        } catch (Throwable t) {
            Xp.log(TAG + name + " NOT FOUND");
            return;
        }
        int n = 0;
        for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
            boolean touches = false;
            for (Class<?> p : ct.getParameterTypes()) {
                if (p == Bitmap.class) touches = true;
            }
            if (!touches) continue;
            try {
                Xp.hook(ct, argLogger("<init>"));
                n++;
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (final Method m : k.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                boolean touches = m.getReturnType() == Bitmap.class;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p == Bitmap.class) touches = true;
                }
                if (!touches) continue;
                try {
                    Xp.hook(m, argLogger(m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        Xp.log(TAG + "traced " + n + " bitmap-carrying methods on " + c.getName());
    }

    private static XposedInterface.Hooker argLogger(final String name) {
        return chain -> {
            Object result = chain.proceed();
            StringBuilder sb = new StringBuilder(TAG)
                    .append(chain.getThisObject() == null ? "?"
                            : chain.getThisObject().getClass().getSimpleName())
                    .append('.').append(name).append('(');
            java.util.List<Object> args = chain.getArgs();
            for (int j = 0; j < args.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(describe(args.get(j)));
            }
            sb.append(") -> ").append(describe(result));
            Xp.log(sb.toString());
            return result;
        };
    }

    /**
     * The bitmap the GL upload should get, at the size the texture should be - or null when
     * there is nothing to change.
     *
     * Null for the three cases that must not be touched: the experiment is off, the surface size
     * is not known yet (the first upload of a process runs before onSurfaceChanged has set it),
     * or the bitmap is already that size. Cached per source, because the source is either the
     * module's own art or the OEM's one wallpaper and both repeat.
     */
    private static Bitmap screenSized(Bitmap src) {
        int w = sSurfaceW, h = sSurfaceH;
        if (!sTexFit || src == null || w <= 0 || h <= 0) return null;
        if (src.getWidth() == w && src.getHeight() == h) return null;
        if (sScreenArt != null && sScreenArtOf == src
                && sScreenArt.getWidth() == w && sScreenArt.getHeight() == h) {
            return sScreenArt;
        }
        Bitmap fitted;
        try {
            fitted = centerCrop(src, w, h);
        } catch (Throwable t) {
            Xp.log(TAG + "screen-size fit failed: " + t);
            return null;
        }
        Bitmap old = sScreenArt;
        if (old != null && old != src && old != sArt) old.recycle();
        sScreenArt = fitted;
        sScreenArtOf = src;
        Xp.log(TAG + "texture fitted to the screen: " + describe(src)
                + " -> " + describe(fitted));
        return fitted;
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Bitmap) {
            Bitmap b = (Bitmap) o;
            return "Bitmap[" + b.getWidth() + "x" + b.getHeight() + " " + b.getConfig() + "]";
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
