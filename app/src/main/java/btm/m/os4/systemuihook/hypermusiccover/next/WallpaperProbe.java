// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.BuildConfig;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
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
    private static final String ACTION = "btm.m.os4.systemuihook.hypermusiccover.next.WPROBE";
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
    /**
     * The size each keyguard texture was last uploaded at, so getTextureDimensions can answer for
     * the texture being asked about instead of for whichever screen uploaded most recently.
     * Weak, because these are the OEM's objects and a screen that goes away takes its own with it.
     */
    private static final java.util.Map<Object, android.graphics.Rect> sTexSizes =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, android.graphics.Rect>());
    /**
     * Fitted copies of one source, keyed by the surface they were cut for.
     *
     * A map rather than a single bitmap because a foldable asks for more than one size and keeps
     * asking: the inner and outer screens each upload with their own renderer, so a single slot
     * was re-cut on every hand-over. Cleared whole when the source changes, which is the only
     * time these stop being wanted.
     */
    /**
     * Guarded by itself: each screen's renderer fits on its own GL thread, and a retirement
     * clears it from the main thread.
     */
    private static final java.util.HashMap<Long, Bitmap> sScreenArts = new java.util.HashMap<>();
    private static Bitmap sScreenArtOf;
    private static volatile int sReportedW, sReportedH;

    /**
     * While non-null, this replaces whatever the keyguard renderer would have uploaded as its
     * wallpaper texture. Because it becomes the real wallpaper surface, the clock glass and the
     * card blur sample it too - which is the whole point of coming into this process.
     */
    private static volatile Bitmap sArt;
    /** Which of the two cover compositions the current source represents. */
    private static volatile boolean sCardMode;
    /** When the running fade-out of the cover began, and how long it runs; 0 when none is. */
    private static volatile long sLeaveAt, sLeaveMs;

    /**
     * SystemUI is showing lyrics over the cover, so the cover is drawn frosted - blurred and
     * darkened - wherever it would be drawn sharp. Applied inside fittedArt(), which every path
     * (upload, getBitmap short-circuit, both fade ends) already reads, so a track change under
     * the lyrics fades frosted to frosted with nothing else knowing. Cleared when the cover goes.
     */
    private static volatile boolean sLyricBlur;
    /**
     * The newest thing SystemUI has asked for, which is not the same as what is on screen: a
     * switch waits for a fade in the air before it takes effect. Recorded the moment the message
     * arrives, so a waiting one that has since been overtaken can drop out instead of writing a
     * stale answer back - which is how a track changed at the moment the lyrics arrived could
     * leave the cover sharp for the rest of the song, both sides believing it was blurred.
     */
    private static volatile boolean sLyricBlurWant;
    /**
     * When the newest blur decision this process has been given was made, on the clock both
     * processes share. See LockLyrics.putBlurOn.
     *
     * The answer and the cover travel in two broadcasts sent from two different threads over
     * there - the cover is composed and sent on the worker, the answer is decided on the main
     * thread - so the one that arrives last is not the one that was decided last, and the cover
     * is the slow half. Tapping the artwork out of cover mode and quickly back in is exactly
     * that: the push, built before the tap, carries the answer from before it and reaches here
     * after the lyric switch has said the opposite. Taken at face value it undoes the switch, and
     * since both sides then believe they agree nothing re-sends it - a song playing out sharp
     * under its lyrics.
     *
     * Time rather than a count, because a count is only comparable within one SystemUI process:
     * a restarted SystemUI starts over at one, and every decision it makes would look older than
     * what this process already holds. 0 means the message carried no stamp at all - an older
     * SystemUI, which sends the answer on its own.
     */
    private static volatile long sBlurSeq;
    /**
     * Every message that could carry a blur decision, and every one that carried a cover.
     *
     * The completion of the fade out of cover mode clears the art and the blur, and it runs a
     * whole crossfade after the message that started it. A tap back in lands inside that window,
     * so the completion has to be able to tell whether the look it belongs to is still the one
     * being asked for. Counted separately because the two halves are cleared for the same reason
     * but not by the same thing: a lyric switch arriving mid-fade owns the blur and not the art.
     */
    private static volatile int sMsgSeq, sArtSeq;
    private static Bitmap sFrosted, sFrostedOf;
    private static Bitmap sFrosted2, sFrostedOf2;

    /**
     * One track change, segment by segment, read back with `op timing`.
     *
     * Both processes read the same uptimeMillis clock and SystemUI sends its own marks along with
     * the cover, so the whole path - the card naming a new track, the artwork being waited for,
     * the broadcast, the composition, the upload - subtracts into one timeline here. Everything
     * is a bare field write on a path that already exists; nothing is measured that was not
     * already happening.
     */
    private static volatile long sTmT0, sTmBurst, sTmArt, sTmSent, sTmRecv, sTmRead, sTmComposed,
            sTmFrosted, sTmMain, sTmUploaded, sTmCheckMs;
    private static volatile long sTmSkip;
    private static volatile int sTmTries, sTmSkips, sTmSkipDir;
    private static volatile String sTmKind = "";

    /**
     * The keyguard engine, captured so a new track can re-run the texture upload without the
     * process being killed. Its GL work all happens on one HandlerThread; nothing here touches
     * GL directly, it only asks the engine to run its own surface-created path again.
     */
    private static volatile Object sKeyguardEngine;

    /**
     * Every keyguard engine built, newest last.
     *
     * A foldable builds one engine per screen, and sKeyguardEngine above holds whichever was
     * constructed last - so on those the cover can be pushed at a screen the user is not looking
     * at, with nothing in the log to say so. Kept as a list first and picked between second,
     * because which one is right is exactly the question the port has to answer.
     */
    private static final java.util.List<Object> sKeyguardEngines =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
     * The lock screen shows the desktop's still, with no keyguard engine of its own (#84: a
     * carousel applied to both screens). Told by SystemUI (CoverPush.sSharedLock). While it holds
     * and the lock screen is up, the desktop renderer IS the lock screen's renderer: its uploads
     * go through the keyguard branch, cover, fade, frost and all, and the cover is in the
     * wallpaper window where the clock's glass and the cards sample it.
     */
    private static volatile boolean sSharedLock;

    /**
     * The desktop engine's own word on the lock screen: its show/hideKeyguardWallpaper calls,
     * which arrive as the keyguard locks and unlocks. Null until the first of them - a process
     * started on the lock screen - and then SystemUI's reading, carried on its messages, fills in.
     */
    private static volatile Boolean sDesktopKgShown;

    /**
     * The desktop renderer's last getBitmap was answered with one of ours. Read by the upload
     * that follows it: if the lock screen went in between, that upload is the home screen's,
     * and it is carrying a cover.
     */
    private static volatile boolean sDesktopGaveOurs;

    /** The desktop's renderer, from its last upload. */
    private static volatile Object sDesktopRenderer;

    /** The desktop's last upload went through the keyguard branch. */
    private static volatile boolean sDesktopOnLock;

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

    private static final String CLS_DESKTOP_IMAGE_ENGINE =
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl";

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
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE");
            out.setPackage("com.android.systemui");
            out.putExtra("op", "needart");
            out.putExtra("why", why);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "no art here, asked SystemUI for it (" + why + " "
                    + sAsks + "/" + ASK_TRIES + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "askForArt failed: " + t);
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
    /**
     * The buffer the GL thread is copying right now, or null. Guarded by FADE_LOCK, and
     * volatile because the hot reload's check section reads it from a binder thread.
     */
    private static volatile Bitmap sUploading;
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
    /**
     * The crossfade for a TRACK CHANGE, which is a different animation with a different job.
     *
     * sFadeMs above is solved against the clock's spring, because entering and leaving cover mode
     * move the wallpaper and the clock together and they have to arrive together. A track change
     * moves neither the clock nor anything else: it swaps one cover for the next, with nothing on
     * screen to keep company with. Tying it to the clock's number only made it slow.
     *
     *   --es op trackfadems --ei v 60
     */
    private static volatile long sTrackFadeMs = 180L;
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
    /**
     * GL-thread state: our texture, the fade it belongs to and the context it was made in.
     * sGlTex is volatile because the hot reload's check section reads it from a binder thread.
     */
    private static volatile int sGlTex;
    private static GpuFade sGlTexFor;
    private static android.opengl.EGLContext sGlTexCtx;
    private static final int[] sGlInts = new int[4];
    private static final float[] sGlColor = new float[4];
    /** Which FRAME_REQUESTS entry this build answers to, once one has; -1 until then. */
    private static volatile int sFrameReq = -1;
    /** A fade that has not seen its last frame this long after it should have is ended here. */
    private static final long GPU_FADE_GRACE_MS = 1500L;

    /**
     * 保留壁纸's dim, 0..0.25: the wallpaper's fragment shader multiplies the texture by
     * 1 - this. It has to be inside the texture and not a view over it, because the media
     * card's and the clock's glass sample the wallpaper window and never see a SystemUI
     * view - which is why the card stayed bright over the dimmed wallpaper (issue #96).
     *
     * The value is animated HERE, not stepped by SystemUI: the `keepdim` op names a target
     * and a duration, and a frame pump asks the engine to draw until it is reached. Sending
     * one value per frame instead was a coarse fade - a wallpaper redraw per broadcast, and
     * whichever of the two the phone happened to miss read as a snap (2026-10-17).
     */
    private static volatile float sKeepDim;
    private static float sKeepFrom;
    private static float sKeepTo;
    /** Written under KEEP_LOCK; volatile because the hot reload's check section reads it. */
    private static volatile long sKeepAt;
    private static int sKeepDurMs = 100;
    private static boolean sKeepPumping;
    /** The pump's own frame request, on the main thread. */
    private static final long KEEP_PUMP_MS = 24L;
    private static final Handler KEEP_MAIN = Gen.watchHandler(new Handler(Looper.getMainLooper()));
    private static final Object KEEP_LOCK = new Object();
    /** Diagnostics: messages received, drawn changes, and the last few drawn values. */
    private static int sKeepMsgs;
    private static int sKeepDraws;
    private static final int KEEP_LOG = 8;
    private static final long[] sKeepLogAt = new long[KEEP_LOG];
    private static final float[] sKeepLogV = new float[KEEP_LOG];
    private static int sKeepLogN;
    /** True while the keyguard renderer's own onDrawFrame runs. GL thread only. */
    private static boolean sKeyguardDrawing;
    /** The program `uKeepDim` was last resolved on, and its location (-1: not in this one). */
    private static int sKeepDimProgram;
    private static int sKeepDimLoc = -1;
    private static final int[] sCurProgram = new int[1];

    /**
     * The lock clock's colours, taken from the cover while the cover is up.
     *
     * SystemUI asks this process for a palette of the clock's region of the lock wallpaper
     * (IMiuiWallpaperManagerService.getMiuiWallpaperPalette, `clock_palette_region`), and every
     * clock face builds its colours from it: the solid, overlay and gradient-blur effects take
     * their text colour from its secondary/neutral tones. The answer is computed from the
     * wallpaper FILE, which in cover mode is not what is on screen - so only the effects that
     * sample what is behind them (混色, liquid glass) followed the cover, and the rest kept the
     * real wallpaper's colours over an album they had nothing to do with (reported 2026-10-14).
     *
     * The OEM's own computation, run on the picture it is drawing instead: MiuiColorScheme's
     * palette over sArt, with the same screen-to-bitmap mapping of the regions. Only for the
     * LOCK wallpaper and only while the texture shows a cover; everything else - the home screen,
     * a kept wallpaper, the depth answer - stays the OEM's. The depth (getMiuiLockPartWallpaperIsDeep)
     * is deliberately not taken over: it decides light text or dark, and the user wants the
     * clock's colour from the cover without its lightness flipped by it.
     */
    private static int installCoverPalette() {
        int hooked = 0;
        try {
            Class<?> ctl = Xp.findClass("com.miui.miwallpaper.server.MiuiWallPaperControllerService", sCl);
            Class<?> scheme = Xp.findClass("com.miui.miwallpaper.material.MiuiColorScheme", sCl);
            Class<?> part = Xp.findClass("com.miui.miwallpaper.material.utils.PartRectColorUtils", sCl);
            final java.lang.reflect.Method palette = scheme.getMethod("getMiuiWallpaperPalette",
                    String.class, Bitmap.class, android.graphics.Rect.class);
            final java.lang.reflect.Method convert = part.getMethod("convertScreenRectsToBitmapRects",
                    Context.class, java.util.Map.class, float.class, float.class, int.class);
            // The regions an empty request means - every one the lock screen reads its colours
            // from - laid out the way the service does it, from the clock style the settings
            // name. Its two settings readers are obfuscated, so they are found by shape: the one
            // static no-argument method answering a String, and the one answering an int[].
            java.lang.reflect.Method regions = null, styleName = null, styleInfo = null;
            try {
                for (java.lang.reflect.Method m : part.getMethods()) {
                    if ("getPartScreenRectUtils".equals(m.getName()) && m.getParameterTypes().length == 6) {
                        regions = m;
                    }
                }
                Class<?> settings = Xp.findClass("com.miui.miwallpaper.utils.SystemSettingUtils", sCl);
                for (java.lang.reflect.Method m : settings.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            || m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() == String.class) styleName = styleName == null ? m : null;
                    if (m.getReturnType() == int[].class) styleInfo = styleInfo == null ? m : null;
                }
            } catch (Throwable t) {
                Xp.log(TAG + "cover palette: full requests stay the wallpaper's: " + t);
            }
            final java.lang.reflect.Method fRegions = regions, fName = styleName, fInfo = styleInfo;
            for (Class<?> inner : ctl.getDeclaredClasses()) {
                for (java.lang.reflect.Method m : inner.getDeclaredMethods()) {
                    if (!"getMiuiWallpaperPalette".equals(m.getName())) continue;
                    final String name = m.getName();
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length < 2 || pt[0] != int.class
                            || !java.util.Map.class.isAssignableFrom(pt[pt.length - 1])) continue;
                    Xp.hook("wall.palette.get." + Xp.slug(inner.getSimpleName()), m, chain -> {
                        java.util.List<Object> a = chain.getArgs();
                        int which = (Integer) a.get(0);
                        Object rects = a.get(a.size() - 1);
                        Bitmap art = coverForPalette();
                        if (art == null || (which != 2 && which != 8) || !(rects instanceof java.util.Map)) {
                            return PaletteCache.proceed(chain, name);
                        }
                        boolean full = ((java.util.Map<?, ?>) rects).isEmpty();
                        if (full && (fRegions == null || fName == null || fInfo == null)) return PaletteCache.proceed(chain, name);
                        try {
                            Context ctx = sCtx != null ? sCtx : (Context) Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication").invoke(null);
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, android.graphics.Rect> mapped = full
                                    ? (java.util.Map<String, android.graphics.Rect>) fRegions.invoke(null, ctx,
                                            fName.invoke(null), fInfo.invoke(null), (float) art.getWidth(), (float) art.getHeight(), which)
                                    : (java.util.Map<String, android.graphics.Rect>) convert.invoke(null, ctx,
                                            rects, (float) art.getWidth(), (float) art.getHeight(), which);
                            java.util.HashMap<String, Object> out = new java.util.HashMap<>();
                            // A full request answers every region the wallpaper's would, the
                            // whole-picture one included: start from the OEM's own answer and
                            // replace what the cover can be read for.
                            if (full) {
                                Object orig = PaletteCache.proceed(chain, name);
                                if (orig instanceof java.util.Map) {
                                    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) orig).entrySet()) {
                                        out.put(String.valueOf(e.getKey()), e.getValue());
                                    }
                                }
                                if (out.containsKey("full_image_rect")) {
                                    Object p = palette.invoke(null, "full_image_rect", art, null);
                                    if (p != null) out.put("full_image_rect", p);
                                }
                            }
                            for (java.util.Map.Entry<String, android.graphics.Rect> e : mapped.entrySet()) {
                                Object p = palette.invoke(null, e.getKey(), art, e.getValue());
                                out.put(e.getKey(), p == null ? new java.util.HashMap<>() : p);
                            }
                            sCoverPalettes++;
                            return out;
                        } catch (Throwable t) {
                            Xp.log(TAG + "cover palette failed, the wallpaper's stands: " + t);
                            return PaletteCache.proceed(chain, name);
                        }
                    });
                    hooked++;
                }
            }
            Xp.log(TAG + "cover palette: " + hooked + " entry point(s)");
        } catch (Throwable t) {
            Xp.log(TAG + "cover palette not installed: " + t);
        }
        return hooked;
    }

    /** How many palettes were answered from the cover, for the probe. */
    private static volatile int sCoverPalettes;

    /** The picture on the lock texture, when it is ours; null when it is the real wallpaper. */
    private static Bitmap coverForPalette() {
        Bitmap art = sArt;
        if (!sTexShowsCover || art == null || art.isRecycled()) return null;
        return art;
    }

    /**
     * Everything this process hooks, in the cold-start order.
     *
     * The class loader comes from the caller rather than from a PackageLoadedParam so a hot
     * reload can run the very same sequence in a process that has been up for hours; the
     * adoption validates that loader against the process's own classes first.
     */
    static void install(ClassLoader cl) {
        sCl = cl;
        Xp.log(TAG + "loaded into " + PKG);
        // The lock clock's palette read off the cover while the cover is up. Its own hook, failing
        // on its own: nothing else here depends on it.
        // Its fall-throughs to the OEM go through PaletteCache, which also takes the depth
        // question - and the palette itself if this hook did not go in.
        int coverHooks = installCoverPalette();
        PaletteCache.install(sCl, coverHooks == 0);
        // This process composes the covers: their full-screen draws on its GPU, not its CPU.
        CoverCompose.sGpu = true;

        // 保留壁纸's dim, written into the wallpaper shader rather than over the screen.
        //
        // Must be in before any GL program is built: the shader source is read and compiled when
        // that program is created, and a hook added later would only affect programs compiled
        // after it. The keyguard renderer's is built when its surface is created, after this.
        try {
            Class<?> prog = Xp.findClass("com.miui.miwallpaper.opengl.ImageGLProgram", sCl);
            Xp.hookAll("wall.shader.resource", prog, "getShaderResource", chain -> {
                Object r = chain.proceed();
                if (!(r instanceof String)) return r;
                String src = (String) r;
                // The still image's fragment shader, and only it: the vertex shader has no
                // uDarken, and the effect programs have their own fragment sources.
                if (!src.contains("uniform int uDarken;")) return r;
                String out = src.replace("uniform int uDarken;",
                        "uniform int uDarken;\nuniform float uKeepDim;");
                int end = out.lastIndexOf('}');
                if (end < 0) return r;
                return out.substring(0, end)
                        + "    fragColor.rgb *= (1.0 - uKeepDim);\n" + out.substring(end);
            });
            Xp.log(TAG + "keep-dim shader hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keep-dim shader hook failed (the KEEP look keeps no dim): " + t);
        }

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
            Xp.hookAllLambdas("wall.render.surface_created", base, "lambda$onSurfaceCreated$0", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean keyguard = chain.getThisObject().getClass().getName().contains("Keyguard");
                if (!keyguard && args.length > 0 && args[0] instanceof Bitmap
                        && chain.getThisObject().getClass().getName().contains("Desktop")) {
                    keyguard = desktopUpload(chain.getThisObject(), (Bitmap) args[0]);
                }
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
                    // Kept so the dimension hook can tell the keyguard's texture from the
                    // desktop one's: same class, two instances, and only one of them is ours.
                    //
                    // Re-read on every upload rather than only the first. A foldable has a
                    // renderer per screen - five of them on the q18 build - each with its own
                    // texture, and holding the first one seen meant the dimension hook stopped
                    // recognising the texture as soon as another screen drew. This branch is
                    // already inside `if (keyguard)`, so whatever it finds here is a keyguard
                    // texture; the only question is which screen's, and the answer is always
                    // the one uploading right now.
                    try {
                        Object t = Xp.getObjectField(chain.getThisObject(), "mTexture");
                        if (t != null) sKeyguardTexture = t;
                    } catch (Throwable ignored) {
                    }
                    // BEFORE the fit below, not after. screenSized() crops to sSurfaceW/H, and
                    // those used to be written further down by noteRenderState - i.e. they held
                    // whichever renderer uploaded LAST. On one screen that is the same size and
                    // nothing shows; on a foldable it is routinely a different screen, so the
                    // picture was cropped for one screen and handed to another. The measured
                    // shape of that is a viewport and an upload that disagree:
                    // `viewport=Rect(0,0-2364,1672) mvpFrom=Rect(0,0-1168,1712) upload=1168x1712`
                    // - the cover cropped for the outer screen, uploaded to the inner one.
                    // Reading it off THIS renderer makes the crop, the MVP source and the
                    // viewport the same screen's by construction.
                    adoptSurfaceOf(chain.getThisObject());
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
                    // Remembered against the texture itself, because the MVP matrix is built
                    // later, off whatever getTextureDimensions answers then - by which time
                    // another screen may have uploaded.
                    if (sKeyguardTexture != null) {
                        sTexSizes.put(sKeyguardTexture, new android.graphics.Rect(0, 0, w, h));
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
                        // Read once: a retirement clears these from the main thread while
                        // this is the GL thread's.
                        Bitmap fitted;
                        Bitmap had = sFitted;
                        if (art.getWidth() == w && art.getHeight() == h) {
                            fitted = art;                   // composed at exactly this size
                        } else if (had != null && sFittedOf == art
                                && had.getWidth() == w && had.getHeight() == h) {
                            fitted = had;                   // already fitted for this texture
                        } else {
                            fitted = centerCrop(art, w, h);
                            sFitted = fitted;
                            sFittedOf = art;
                        }
                        fitted = frostedIfWanted(fitted);
                        args[0] = fitted;
                        // The first upload after a push is the one the user sees. Later ones (a
                        // fade's frames) leave the mark where it was, so `op timing` reports the
                        // moment the new cover reached the screen, not the end of its animation.
                        if (sTmUploaded == 0L) sTmUploaded = SystemClock.uptimeMillis();
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
            Xp.hookAll("wall.texture.dimensions", tex, "getTextureDimensions", chain -> {
                Object self = chain.getThisObject();
                if (!sTexFit || self == null) return chain.proceed();
                // What THIS texture was last given, not what the last upload anywhere was. On a
                // foldable the two are routinely different screens, and answering with the other
                // one's size builds the MVP matrix from a rectangle the uploaded bitmap does not
                // have - the picture then lands scaled and offset, which is exactly the symptom
                // that only a fold or a rotation could clear. The map holds keyguard textures
                // only, so the desktop's still gets the OEM's own answer.
                android.graphics.Rect r = sTexSizes.get(self);
                if (r != null) return r;
                if (self != sKeyguardTexture) return chain.proceed();
                int w = sSurfaceW, h = sSurfaceH;
                if (w <= 0 || h <= 0) return chain.proceed();
                return new android.graphics.Rect(0, 0, w, h);
            });
            Xp.log(TAG + "texture dimension hook installed");
        } catch (Throwable t) {
            Xp.w(TAG + "texture dimension hook failed: " + t);
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
            Xp.hookAll("wall.texture.bitmap", kg, "getBitmap", chain -> {
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
            Xp.w(TAG + "getBitmap short-circuit failed: " + t);
        }

        // The same short-circuit for the desktop renderer while it draws a shared lock screen's
        // cover. Its cache is dropped on every reload, so without this each one decoded the
        // wallpaper off disk first - 150-300ms measured, in front of every fade, and on the way
        // out the depth cut-out came back that long before the wallpaper began to (#84).
        try {
            Class<?> dr = Xp.findClass(
                    "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer",
                    sCl);
            Xp.hookAll("wall.desktop.bitmap", dr, "getBitmap", chain -> {
                if (!desktopOnLockWanted()) {
                    sDesktopGaveOurs = false;
                    return chain.proceed();
                }
                Bitmap ours = null;
                Bitmap ov = sUploadOverride;
                if (ov != null && sGpuFade != null && !ov.isRecycled()) ours = ov;
                if (ours == null) ours = fittedArt();
                if (ours == null) ours = sOrig;
                if (ours == null || ours.isRecycled()) {
                    sDesktopGaveOurs = false;
                    return chain.proceed();
                }
                sDesktopGaveOurs = true;
                return ours;
            });
            Xp.log(TAG + "desktop getBitmap short-circuit installed");
        } catch (Throwable t) {
            Xp.w(TAG + "desktop getBitmap short-circuit failed: " + t);
        }

        // Runtime refresh. ImageEngineImpl.U() ("preRender", on the GL thread) re-runs
        //     mRenderer.onSurfaceCreated(); mRenderer.onSurfaceChanged(w, h)
        // whenever its pending-surface flag is set, and onSurfaceCreated is the path that ends in
        // mTexture.use(...) -> the lambda we already replace the bitmap in. So a new track only
        // has to set that flag and ask for a frame; the OEM does the upload itself, including the
        // frosted copy the notification cards blur against.
        try {
            Class<?> eng = Xp.findClass(CLS_KEYGUARD_ENGINE, sCl);
            Xp.hookAllConstructors("wall.engine.keyguard_ctor", eng, chain -> {
                Object result = chain.proceed();
                sKeyguardEngine = chain.getThisObject();
                // Read the obfuscated member names off this instance before anything reaches for
                // them. A foldable builds one engine per screen, so this also runs more than once
                // there - resolve() is per engine class and returns at once after the first.
                EngineNames.resolve(sKeyguardEngine);
                noteEngineInstance(sKeyguardEngine);
                Xp.log(TAG + "keyguard engine captured: " + sKeyguardEngine);
                // From here the lock screen has an image engine, so this is the first moment a
                // cover has somewhere to go - and by now the receiver above is up, which is what
                // the pushes that went missing did not have.
                if (sArt == null) askForArt("keyguard engine");
                return result;
            });
            Xp.log(TAG + "keyguard engine hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keyguard engine hook failed: " + t);
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
                Xp.hookAllConstructors("wall.engine.base_ctor", base, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    String n = self.getClass().getName();
                    if (sEngineNames.add(n)) Xp.log(TAG + "engine instance: " + n);
                    if (n.contains("Desktop")) {
                        sDesktopEngine = self;
                        // The names live on the shared base class, so the desktop's are the
                        // keyguard's - but with no keyguard engine nothing would read them, and a
                        // reload without the pending-surface flag only redraws (#84, shared).
                        if (sKeyguardEngine == null) EngineNames.resolve(self);
                    }
                    return result;
                });
                Xp.log(TAG + "engine base constructors hooked");
            }
        } catch (Throwable t) {
            Xp.w(TAG + "engine base hook failed: " + t);
        }

        // The desktop still's word on the lock screen, for a lock screen that shares it. See
        // sSharedLock. The names are the EngineService interface's and survive R8.
        try {
            Class<?> de = Xp.findClass(CLS_DESKTOP_IMAGE_ENGINE, sCl);
            int n = 0;
            for (Method m : de.getDeclaredMethods()) {
                final boolean show = m.getName().equals("showKeyguardWallpaper");
                if (!show && !m.getName().equals("hideKeyguardWallpaper")) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                // Only the (boolean, int) pair: the controller sends it to every engine together
                // with its setKeyguardLocked. The no-argument hide is something else - sent from
                // the screen-on and unlock animations with the lock screen still up.
                if (m.getParameterCount() != 2) continue;
                Xp.hook("wall.desktop.keyguard_show", m, chain -> {
                    Object r = chain.proceed();
                    if (sSharedLock) onDesktopKeyguard(show);
                    else sDesktopKgShown = show;
                    return r;
                });
                n++;
            }
            Xp.log(TAG + "desktop engine keyguard calls hooked (" + n + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "desktop engine keyguard hook failed, a shared lock wallpaper keeps the"
                    + " cover off: " + t);
        }

        // The live lock wallpaper's engine, its player and its path. See hookVideoEngines().
        hookVideoEngines();

        // The frosted copy the notification and media cards blur against is regenerated on
        // every texture upload. That is the right trade once per track change and the wrong one
        // twenty times in a row, so a fade can switch it off for its own frames; the upload that
        // ends the fade is a normal one and puts it right. Only ever engaged from startFade().
        try {
            Class<?> ap = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimatorProgram", sCl);
            Xp.hookAll("wall.render.mix_frost", ap, "setUpMixFrost", chain -> {
                if (sFrostSkipping) return null;
                return chain.proceed();
            });
            Xp.log(TAG + "frosting hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "frosting hook failed: " + t);
        }

        // The OEM's darkening decision, kept for the original and withheld from the cover. See
        // sOemDarken. Its own block: a build that renames this loses the fix, not the cover.
        try {
            Class<?> ar = Xp.findClass(
                    "com.miui.miwallpaper.opengl.AnimImageWallpaperRenderer", sCl);
            Xp.hookAll("wall.render.mask_layer", ar, "updateMaskLayerStatus", chain -> {
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
            Xp.w(TAG + "darken hook failed, the cover stays dimmed where the OEM dims: " + t);
        }

        // The GPU crossfade's frames. Declared on the base class, which the keyguard renderer
        // reaches through AnimImageWallpaperRenderer's super call; the desktop renderer passes
        // through here too and is let alone by the identity check.
        try {
            Class<?> base = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ImageWallpaperRenderer", sCl);
            sPlainProgram = Xp.findClass(
                    "com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram", sCl);
            Xp.hookAll("wall.render.draw_frame", base, "onDrawFrame", chain -> {
                final boolean mine = chain.getThisObject() == sKeyguardRenderer;
                // The program draw hook reads this to know whose texture it is setting the
                // dim on. Single GL thread, so a plain flag is enough.
                if (mine) {
                    sKeyguardDrawing = true;
                    sKeyguardGlTid = android.os.Process.myTid();
                }
                try {
                    if (sOemDarkenKnown && !sDarkenBroken && mine) {
                        applyDarken(chain.getThisObject());
                    }
                    Object r = mine && (WallpaperVideo.on() || sVideoGlLive)
                            ? drawWithVideo(chain) : chain.proceed();
                    if (!mine) return r;
                    GpuFade f = sGpuFade;
                    if (f == null && sGlTex == 0) return r;
                    try {
                        drawGpuFade(chain.getThisObject(), f);
                    } catch (Throwable t) {
                        sGpuFadeBroken = true;
                        Xp.w(TAG + "gpu fade draw failed, using the CPU fade from now on: "
                                + Log.getStackTraceString(t));
                        if (f != null) abortGpuFade(f);
                    }
                    return r;
                } finally {
                    if (mine) sKeyguardDrawing = false;
                }
            });
            sDrawHooked = true;
            Xp.log(TAG + "draw hooked for the gpu fade");
        } catch (Throwable t) {
            Xp.w(TAG + "draw hook failed, the fade stays on the CPU: " + t);
        }

        // The one frame of the wallpaper's own draw. Everything else - the texture, the
        // uniforms, the program - has been set by the time this is reached, so the dim can
        // go on the current program immediately before its glDrawArrays.
        try {
            Class<?> glw = Xp.findClass("com.miui.miwallpaper.opengl.ImageGLWallpaper", sCl);
            Xp.hookAll("wall.render.gl_draw", glw, "draw", chain -> {
                if (sKeyguardDrawing) applyKeepDim();
                return chain.proceed();
            });
            Xp.log(TAG + "keep-dim uniform hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "keep-dim uniform hook failed (the KEEP look keeps no dim): " + t);
        }

        // Its own block like every other: a build where this name moved must not take the whole
        // process's adoption down - the receiver is also installed by the hot reload's own
        // main-thread step, which does not depend on this hook.
        try {
            Xp.hook("wall.app.create", Xp.findMethodExact(Application.class, "onCreate"), chain -> {
                Object result = chain.proceed();
                try {
                    register((Application) chain.getThisObject());
                } catch (Throwable t) {
                    Xp.w(TAG + "register failed: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            Xp.w(TAG + "application hook failed (a cold start still registers): " + t);
        }
        // What was registered before this point (the log-level preference listener, from
        // Xp.attach) is install-time, like SystemUI's: a dry run must put it back, not sweep it.
        Gen.markInstallScope();
    }

    private static final String ART_FILE = "hyperchanger_hmc_next_art.jpg";

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
    /** How the last GPU fade ended, for `op selftest`. */
    private static volatile String sLastFade = "none yet";
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
    /**
     * Takes this renderer's surface as the screen to fit to.
     *
     * The one place the SCREEN's own size is knowable in this process, and on a foldable it is a
     * different answer per renderer - so it is read from the renderer that is about to upload,
     * immediately before the crop that uses it, rather than left over from the last one.
     */
    private static void adoptSurfaceOf(Object renderer) {
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            if (surface instanceof android.graphics.Rect) {
                android.graphics.Rect r = (android.graphics.Rect) surface;
                if (r.width() > 0 && r.height() > 0) {
                    sSurfaceW = r.width();
                    sSurfaceH = r.height();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void noteRenderState(Object renderer, int w, int h) {
        if (sRenderStateFailed) return;
        try {
            Object surface = Xp.getObjectField(renderer, "mSurfaceSize");
            Object texture = Xp.getObjectField(renderer, "mTexture");
            Object dims = texture == null ? null
                    : Xp.callMethod(texture, "getTextureDimensions");
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

    /**
     * The frosted copy of one fitted picture, made once. Called off the GL thread first.
     *
     * Two are kept, because a track change needs both ends of its crossfade at the same time -
     * the song going out and the one coming in. With one slot, the incoming picture evicted the
     * outgoing one and the fade paid for it a second time, which is the cost this is here to
     * avoid in the first place.
     */
    private static synchronized Bitmap frostedOf(Bitmap sharp) {
        if (sFrosted != null && sFrostedOf == sharp && !sFrosted.isRecycled()) return sFrosted;
        if (sFrosted2 != null && sFrostedOf2 == sharp && !sFrosted2.isRecycled()) {
            // Answered from the older slot: make it the newer one, so the next picture evicts
            // whichever of the two has gone longest without being asked for.
            Bitmap f = sFrosted2, of = sFrostedOf2;
            sFrosted2 = sFrosted;
            sFrostedOf2 = sFrostedOf;
            sFrosted = f;
            sFrostedOf = of;
            return f;
        }
        long t0 = SystemClock.uptimeMillis();
        // The kept composition's own frosting when this is it: a stretch, where frosted() is a
        // full-screen blur (783ms measured on a busy phone, the lyrics switched on in the cover).
        Composed kept = sComposed;
        Bitmap f = kept != null && kept.sharp == sharp
                ? frostOf(kept, sharp.getWidth(), sharp.getHeight()) : CoverCompose.frosted(sharp);
        cacheFrosted(sharp, f);
        Xp.log(TAG + "frosted " + describe(sharp) + " in " + (SystemClock.uptimeMillis() - t0)
                + "ms");
        return f;
    }

    /** Puts a frosted copy in the newer slot, keyed on the sharp picture it stands in for. */
    private static synchronized void cacheFrosted(Bitmap sharp, Bitmap frosted) {
        if (frosted == null) return;
        sFrosted2 = sFrosted;
        sFrostedOf2 = sFrostedOf;
        sFrosted = frosted;
        sFrostedOf = sharp;
    }

    /**
     * Lets go of both frosted copies. Not recycled: a fade in the air holds its own reference to
     * whichever one it is drawing from, and dropping ours only means the next one is made again.
     * Called when the cover goes, where two screen-sized bitmaps are pure cost.
     */
    private static synchronized void dropFrosted() {
        sFrosted = null;
        sFrostedOf = null;
        sFrosted2 = null;
        sFrostedOf2 = null;
    }

    private static Bitmap sharpFittedArt() {
        Bitmap art = sArt;
        if (art == null) return null;
        int targetW = sReportedW;
        int targetH = sReportedH;
        if (targetW <= 0 || targetH <= 0) {
            Context ctx = sCtx;
            if (ctx != null) {
                targetW = ctx.getResources().getDisplayMetrics().widthPixels;
                targetH = ctx.getResources().getDisplayMetrics().heightPixels;
            }
        }
        if (targetW <= 0 || targetH <= 0) return null;
        if (art.getWidth() == targetW && art.getHeight() == targetH) return art;
        Bitmap cached = sFitted;
        if (cached != null && sFittedOf == art
                && cached.getWidth() == targetW && cached.getHeight() == targetH) {
            return cached;
        }
        Bitmap fitted = centerCrop(art, targetW, targetH);
        sFitted = fitted;
        sFittedOf = art;
        return fitted;
    }

    private static void saveArtLater(final Context ctx, final byte[] jpg) {
        sArtSaving.incrementAndGet();
        Gen.thread("mc-art-save", () -> {
            try {
                saveArt(ctx, jpg);
            } finally {
                sArtSaving.decrementAndGet();
            }
        }, sArtSaving::decrementAndGet);
    }

    private static void saveArt(Context ctx, byte[] jpg) {
        // Beside it, then renamed: killed half way, the process came back to a truncated JPEG.
        File tmp = new File(ctx.getFilesDir(), ART_FILE + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(jpg);
            }
            if (!tmp.renameTo(new File(ctx.getFilesDir(), ART_FILE))) {
                throw new java.io.IOException("rename failed");
            }
        } catch (Throwable t) {
            Xp.w(TAG + "saveArt failed: " + t);
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
        sCardMode = new File(ctx.getFilesDir(), "hyperchanger_hmc_next_card_mode").exists();
        if (src.exists() && (!f.exists() || src.lastModified() >= f.lastModified())) {
            try {
                CoverCompose.Source s = CoverCompose.readSource(src);
                if (s != null) {
                    sArt = sCardMode ? CoverCompose.cardBackground(s.src, s.w, s.h)
                            : CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
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
        startFade(from, to, done, sFadeMs);
    }

    private static void startFade(final Bitmap from, final Bitmap to, final Runnable done,
                                  final long durMs) {
        final Context ctx = sCtx;
        if (ctx == null || from == null || to == null || from.isRecycled() || to.isRecycled()
                || from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
            Xp.log(TAG + "no fade: " + describe(from) + " -> " + describe(to));
            if (done != null) done.run();
            reloadTexture();
            return;
        }
        if (sSharedLock && lockEngine() == null) {
            // The desktop is on screen and the lock screen's picture is not on it: nothing to
            // fade. The cover is put on when the lock screen comes back - syncSharedLock().
            if (done != null) done.run();
            return;
        }
        if (gpuFadeUsable()) {
            startGpuFade(from, to, done, durMs);
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
        final Runnable step = new Runnable() {
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
                    Gen.postDelayed(this, 2L);
                    return;
                }
                long el = now - t0;
                if (el >= durMs) {
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
                float t = el / (float) durMs;
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
                    Gen.postDelayed(this, 2L);
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
                reloadEngine(lockEngine(), true);
                Gen.postDelayed(this, FADE_STEP_MS);
            }
        };
        Gen.post(step);
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
        // On a shared still the desktop renderer stands in for the keyguard's, and it is known
        // before its first upload as the lock screen's - which is the upload the fade starts.
        Object renderer = sSharedLock ? sDesktopRenderer : sKeyguardRenderer;
        if (renderer == null || lockEngine() == null) return false;
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

    /** The keyguard renderer's GL thread, as its last frame ran on; 0 until one has drawn. */
    private static volatile int sKeyguardGlTid;
    /** What boostForFade raised, and from what. Main thread. */
    private static int sBoostedMainFrom = Integer.MIN_VALUE;
    private static int sBoostedGlTid;
    private static int sBoostedGlFrom;
    private static boolean sBoostLogged;

    /**
     * The fade's two threads at display priority while it runs, and back after. Main thread.
     *
     * With four cores up and all four full - SystemUI's frames, its GC, WeChat in the background -
     * the fade's main thread and GL thread sat runnable for 400-800ms of a 1.1s entry (trace
     * 2026-10-20): the cover came up late and stalled half way, though each of its frames is a
     * millisecond or two. Raised only as far as URGENT_DISPLAY, still below SystemUI's render
     * thread, and never lowered from where it already was.
     */
    private static void boostForFade(boolean on) {
        final int want = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY;
        try {
            if (on) {
                if (sBoostedMainFrom == Integer.MIN_VALUE) {
                    int now = android.os.Process.getThreadPriority(android.os.Process.myTid());
                    if (now > want) {
                        android.os.Process.setThreadPriority(want);
                        sBoostedMainFrom = now;
                    }
                }
                int gl = sKeyguardGlTid;
                if (gl != 0 && sBoostedGlTid == 0) {
                    int now = android.os.Process.getThreadPriority(gl);
                    if (now > want) {
                        android.os.Process.setThreadPriority(gl, want);
                        sBoostedGlTid = gl;
                        sBoostedGlFrom = now;
                        if (!sBoostLogged) {
                            sBoostLogged = true;
                            Xp.log(TAG + "fade threads raised to " + want + " (GL " + gl + " from "
                                    + now + ", main from " + sBoostedMainFrom + ")");
                        }
                    }
                }
                return;
            }
            if (sBoostedMainFrom != Integer.MIN_VALUE) {
                android.os.Process.setThreadPriority(sBoostedMainFrom);
                sBoostedMainFrom = Integer.MIN_VALUE;
            }
            if (sBoostedGlTid != 0) {
                int gl = sBoostedGlTid;
                sBoostedGlTid = 0;
                android.os.Process.setThreadPriority(gl, sBoostedGlFrom);
            }
        } catch (Throwable t) {
            // Not allowed, or the GL thread is gone: the fade runs as it always did.
            sBoostedMainFrom = Integer.MIN_VALUE;
            sBoostedGlTid = 0;
            Xp.d(TAG + "fade priority: " + t);
        }
    }

    private static void startGpuFade(Bitmap from, Bitmap to, Runnable done, long durMs) {
        boostForFade(true);
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
                f = new GpuFade(old.from, old.to, a, target, to, durMs, done);
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
                f = startFreshGpuFade(from, to, done, durMs);
            }
        } else {
            f = startFreshGpuFade(from, to, done, durMs);
        }
        android.view.Choreographer.FrameCallback loop =
                new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (sGpuFade != f) return;
                if (SystemClock.uptimeMillis() - f.startedAt > f.durMs + GPU_FADE_GRACE_MS) {
                    // The GL thread stopped drawing - the screen went off, most likely. End it
                    // with a real reload: what is uploaded is the far end only for a fade that
                    // lands on its underneath picture, not for one waiting on its swap.
                    sLastFade = "TIMED OUT after " + f.frames + " frames (armed=" + f.armed
                            + ", swap=" + f.swapRequested + "/" + f.swapUploaded + ")";
                    Xp.log(TAG + "gpu fade timed out after " + f.frames + " frames (armed="
                            + f.armed + ", swap=" + f.swapRequested + "/" + f.swapUploaded + ")");
                    finishGpuFade(f);
                    reloadTexture();
                    return;
                }
                requestFrame(true);
                Gen.frame(this);
            }
        };
        Gen.frame(loop);
    }

    /** A fade from `from` to `to` from the start: one reload uploads `to` underneath. */
    private static GpuFade startFreshGpuFade(Bitmap from, Bitmap to, Runnable done, long durMs) {
        GpuFade f = new GpuFade(from, to, 1f, 0f, to, durMs, done);
        sUploadOverride = to;
        sGpuFade = f;
        reloadEngine(lockEngine(), true);
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
        boostForFade(false);
        if (f.done != null) f.done.run();
        // A cover that has just faded out leaves the desktop holding the lock screen's cut of
        // its own picture. Put back now, under the lock screen where it looks the same, rather
        // than as the user unlocks.
        if (sSharedLock) syncSharedLock();
        // A video held back by the fade is drawn from the next frame; ask for it rather than
        // wait on the player's next one, which a paused player would never send.
        if (WallpaperVideo.on()) videoFrame();
    }

    /** Any thread. Ends a fade that cannot continue, with a plain reload so nothing is stranded. */
    private static void abortGpuFade(final GpuFade f) {
        f.finished = true;
        Gen.post(new Runnable() {
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
                Gen.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sGpuFade != f) return;
                        sUploadOverride = f.dest;
                        f.swapRequested = true;
                        reloadEngine(lockEngine(), true);
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
            Gen.post(new Runnable() {
                @Override
                public void run() {
                    sLastFade = "done in " + (SystemClock.uptimeMillis() - f.startedAt) + "ms, "
                            + f.frames + " frames, armed=" + f.armed;
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
        // A fresh fade (overlay 1 -> 0) over a motion cover fades the video's last frame out,
        // not the old still - see WallpaperVideo.fadeOverlay.
        int video = f.a0 >= 0.5f && f.a1 < 0.5f ? WallpaperVideo.fadeOverlay() : 0;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, video != 0 ? video : sGlTex);
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
        checkTakeoverCrash(ctx);
        // A hot reload hands the cover over instead, and re-reading it from disk would compose
        // it again on this thread for a picture the texture already shows.
        if (sArt == null) loadArt(ctx);
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
        class Probe extends ProbeGuard.Receiver {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!ProbeGuard.admit(this, i)) return;
                String op = i.getStringExtra("op");
                byte[] carried = i.getByteArrayExtra("jpg");
                Xp.d(TAG + "recv op=" + op
                        + (carried == null ? " " + i.getExtras() : " jpg=" + carried.length + "B"));
                try {
                    if (i.hasExtra("video")) noteLockWallpaper(i.getBooleanExtra("video", false));
                    if (i.hasExtra("shared")) noteSharedLock(i.getBooleanExtra("shared", false));
                    // Only where the engine has said nothing yet - a process that started on the
                    // lock screen. Its own show/hide is the authority: a message sent just before
                    // an unlock can land just after the hide, and must not undo it.
                    if (i.hasExtra("locked") && sDesktopKgShown == null) {
                        sDesktopKgShown = i.getBooleanExtra("locked", false);
                    }
                    if ("cls".equals(op)) {
                        dumpClass(i.getStringExtra("name"), i.getStringExtra("grep"));
                    } else if ("keepdim".equals(op)) {
                        // 保留壁纸's dim, as a target and the time to reach it. The frames
                        // are asked for by KEEP_PUMP and the value is interpolated at draw
                        // time - see applyKeepDim(). Nearly no logging here on purpose: a
                        // step arrives during the clock's transition.
                        float to = Math.max(0f, Math.min(0.5f, i.getFloatExtra("v", 0f)));
                        int ms = Math.max(1, i.getIntExtra("ms", 100));
                        synchronized (KEEP_LOCK) {
                            sKeepFrom = keepDimNowLocked();
                            sKeepTo = to;
                            sKeepAt = android.os.SystemClock.uptimeMillis();
                            sKeepDurMs = ms;
                            sKeepMsgs++;
                        }
                        startKeepPump();
                    } else if ("keepdimreport".equals(op)) {
                        setResultData(keepDimReport());
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
                    } else if ("precompose".equals(op)) {
                        String src = i.getStringExtra("src");
                        if (src != null && !videoPath()) {
                            precompose(src, i.getBooleanExtra("cardmode", false),
                                    i.getStringExtra("prekey"));
                        }
                    } else if ("art".equals(op)) {
                        sCardMode = i.getBooleanExtra("cardmode", false);
                        final Context cc = c;
                        boolean reload = i.getBooleanExtra("reload", false);
                        // A fade needs both ends of it in this process. Missing either one is
                        // not a failure, it is the old behaviour: swap the texture in one frame.
                        boolean fade = reload && i.getBooleanExtra("fade", false);
                        // The cover video's own crossfade, which SystemUI decides; carried on
                        // every push because the file it describes is rebuilt on every push.
                        sVideoFade = i.getBooleanExtra("vfade", sVideoFade);
                        // Whatever arrives now is newer than a composition still in progress,
                        // which would otherwise land after it and put an older cover back.
                        if (!i.hasExtra("src")) sSrcSeq++;
                        sArtSeq++;
                        sMsgSeq++;
                        // What the lyrics want of THIS cover. See applyArt(), which is where it
                        // takes effect; older builds of SystemUI send no such extra, and an older
                        // SystemUI sends the answer without its place in line.
                        boolean blurMine = true;
                        if (i.hasExtra("blurseq")) {
                            blurMine = takeBlurDecision(i.getLongExtra("blurseq", 0L),
                                    i.getBooleanExtra("lyricblur", false), "cover");
                        } else if (i.hasExtra("lyricblur")) {
                            sLyricBlurWant = i.getBooleanExtra("lyricblur", false);
                        }
                        if (i.hasExtra("tsent")) {
                            sTmT0 = i.getLongExtra("t0", 0L);
                            sTmSkip = i.getLongExtra("tskip", 0L);
                            sTmSkipDir = i.getIntExtra("skipdir", 0);
                            sTmBurst = i.getLongExtra("tburst", 0L);
                            sTmSkips = i.getIntExtra("skips", 0);
                            sTmArt = i.getLongExtra("tart", 0L);
                            sTmSent = i.getLongExtra("tsent", 0L);
                            sTmCheckMs = i.getLongExtra("checkms", 0L);
                            sTmTries = i.getIntExtra("tries", 0);
                            sTmRecv = SystemClock.uptimeMillis();
                            sTmRead = sTmComposed = sTmFrosted = sTmMain = sTmUploaded = 0L;
                            sTmKind = i.hasExtra("src") ? "source" : "jpeg";
                        }
                        if (i.getBooleanExtra("off", false)) {
                            // A live wallpaper has no texture to fade back to - the way back
                            // is handing the surface to its player again.
                            if (videoPath()) {
                                sCurrentArtChecksum = 0;
                                sArt = null;
                                sFitted = null;
                                sFittedOf = null;
                                sLyricBlur = false;
                                sLyricBlurWant = false;
                                dropFrosted();
                                new File(c.getFilesDir(), ART_FILE).delete();
                                new File(c.getFilesDir(), SRC_FILE).delete();
                                new File(c.getFilesDir(), "hyperchanger_hmc_next_card_mode").delete();
                                // The cover videos stay: coming back into cover mode on the same
                                // album then reloads from the cache instead of encoding again.
                                videoWindowTakeover(true);
                                return;
                            }
                            composedLater();
                            Bitmap from = fittedArt();
                            Bitmap to = sOrig;
                            // The look this fade belongs to, read before it starts. Anything
                            // arriving while it runs - a tap back into cover mode, the next
                            // track, a lyric switch - describes the look coming back, and this
                            // completion must not touch it: it clears the art that one has
                            // already set and the blur it has already asked for. Measured as the
                            // cover coming back sharp under its lyrics after the artwork was
                            // tapped out and quickly back in, and staying sharp for the song.
                            final int msgSeq = sMsgSeq;
                            final int artSeq = sArtSeq;
                            final boolean blurStillMine = blurMine;
                            if (fade && from != null && to != null) {
                                sLeaveAt = SystemClock.uptimeMillis();
                                sLeaveMs = sFadeMs;
                                startFade(from, to, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Kept when a cover is already on its way: it arrives
                                        // to find the plain wallpaper on screen, and applyArt
                                        // reads this to start it from there. Cleared here, the
                                        // cover that landed 18ms after this fade was taken for
                                        // a track change - a 180ms fade from the old cover,
                                        // which is a cut from the wallpaper (2026-09-29).
                                        if (artSeq == sArtSeq) sLeaveAt = 0L;
                                        if (blurStillMine && msgSeq == sMsgSeq) {
                                            sLyricBlur = false;
                                            sLyricBlurWant = false;
                                            dropFrosted();
                                        } else {
                                            Xp.log(TAG + "the lyrics were switched while the cover"
                                                    + " was fading out, leaving their blur alone");
                                        }
                                        if (artSeq != sArtSeq) {
                                            Xp.log(TAG + "a cover arrived while the last one was"
                                                    + " fading out, leaving the art to it");
                                            return;
                                        }
                                        sCurrentArtChecksum = 0;
                                        sArt = null;
                                        sFitted = null;
                                        sFittedOf = null;
                                        new File(cc.getFilesDir(), ART_FILE).delete();
                                        new File(cc.getFilesDir(), SRC_FILE).delete();
                                        new File(cc.getFilesDir(), "hyperchanger_hmc_next_card_mode").delete();
                                        Xp.log(TAG + "art cleared");
                                    }
                                });
                                return;
                            }
                            sCurrentArtChecksum = 0;
                            sArt = null;
                            if (blurStillMine) {
                                sLyricBlur = false;
                                sLyricBlurWant = false;
                                dropFrosted();
                            }
                            new File(c.getFilesDir(), ART_FILE).delete();
                            new File(c.getFilesDir(), SRC_FILE).delete();
                            new File(c.getFilesDir(), "hyperchanger_hmc_next_card_mode").delete();
                            Xp.log(TAG + "art cleared");
                        } else if (i.hasExtra("prekey") && !i.hasExtra("src")) {
                            applyKept(i.getStringExtra("prekey"), reload, fade);
                            return;
                        } else if (i.hasExtra("src")) {
                            // The tap's own message has put this very picture up already: the
                            // push behind it has nothing to add, and a second fade from the
                            // cover to itself is all it would make.
                            String pk = i.getStringExtra("prekey");
                            Composed kc = sComposed;
                            if (pk != null && pk.equals(sKeptShown) && kc != null
                                    && sArt == kc.sharp) {
                                Xp.log(TAG + "push for the cover the tap put up, passed over");
                                return;
                            }
                            composeFromSource(c, i.getStringExtra("src"), reload, fade,
                                    sCardMode, pk);
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
                            // A composed picture arriving means SystemUI does not know this
                            // process composes for itself: its question or our answer went
                            // missing, which is what happens when both processes restart at once
                            // and each one's hello lands before the other has a receiver. Left
                            // alone it stands for the life of the process, and every track then
                            // pays for a full-screen compose AND a JPEG encode (~180ms measured)
                            // over there, plus the decode here. Answering again fixes the next one.
                            sayHello(c);
                            if (jpg == null && file != null) jpg = readBytes(file);
                            Bitmap b = null;
                            if (jpg != null) {
                                b = decodeToTextureSize(jpg);
                                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                                crc.update(jpg);
                                sCurrentArtChecksum = crc.getValue();
                            } else if (file != null) {
                                b = BitmapFactory.decodeFile(file);
                                sCurrentArtChecksum = 0;
                            }
                            if (b == null) {
                                Xp.w(TAG + "art decode failed (jpg="
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
                        final boolean on = i.getBooleanExtra("on", false);
                        sMsgSeq++;
                        if (!i.hasExtra("blurseq")
                                || takeBlurDecision(i.getLongExtra("blurseq", 0L), on, "lyrics")) {
                            setLyricBlur(on);
                        }
                    } else if ("reload".equals(op)) {
                        reloadTexture();
                    } else if ("fadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sFadeMs);
                        sFadeMs = v < 60L ? 60L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "crossfade is now " + sFadeMs + "ms");
                    } else if ("trackfadems".equals(op)) {
                        long v = i.getIntExtra("v", (int) sTrackFadeMs);
                        // Floored at a frame: the GPU fade divides by this.
                        sTrackFadeMs = v < 16L ? 16L : (v > 1200L ? 1200L : v);
                        Xp.log(TAG + "track-change crossfade is now " + sTrackFadeMs + "ms");
                    } else if ("hello".equals(op)) {
                        sayHello(c);
                    } else if ("gpufade".equals(op)) {
                        sGpuFadeOn = i.getBooleanExtra("on", !sGpuFadeOn);
                        if (sGpuFadeOn) sGpuFadeBroken = false;
                        Xp.log(TAG + "gpu fade " + (sGpuFadeOn ? "on" : "off")
                                + " (draw hooked=" + sDrawHooked + ", usable now="
                                + gpuFadeUsable() + ")");
                    } else if ("pcache".equals(op)) {
                        // The clock-colour answers kept until the wallpaper changes; see
                        // PaletteCache. `--ez clear true` drops them.
                        if (i.getBooleanExtra("clear", false)) PaletteCache.clear();
                        setResultData(PaletteCache.describe());
                    } else if ("vidbias".equals(op)) {
                        WallpaperVideo.setBias(i.getFloatExtra("bias", 0.25f));
                    } else if ("vidtex".equals(op)) {
                        // The motion-cover experiment, see WallpaperVideo. `--ez on false`
                        // stops it; `--ez stats true` only reports (and restarts the counters).
                        if (i.getBooleanExtra("stats", false)) {
                            String s = WallpaperVideo.statsAndReset();
                            Xp.log(TAG + "video: " + s);
                            setResultData(s);
                        } else if (!i.getBooleanExtra("on", true)) {
                            WallpaperVideo.stop("probe");
                            setResultData("off");
                        } else {
                            String path = i.getStringExtra("path");
                            if (path == null) {
                                path = new File(c.getFilesDir(), "hyperchanger_hmc_next_video.mp4").getPath();
                            }
                            float bias = i.getFloatExtra("bias", -1f);
                            if (bias < 0f) {
                                bias = 0.25f;
                                try {
                                    CoverCompose.Source s = CoverCompose.readSource(
                                            new File(c.getFilesDir(), SRC_FILE));
                                    if (s != null) bias = s.bias;
                                } catch (Throwable ignored) {
                                }
                            }
                            WallpaperVideo.start(c, path, bias);
                            setResultData("on " + path + " bias=" + bias
                                    + " (filesDir " + c.getFilesDir() + ")");
                        }
                    } else if ("nofrost".equals(op)) {
                        sSkipFrost = i.getBooleanExtra("on", !sSkipFrost);
                        Xp.log(TAG + "frosting during a fade is "
                                + (sSkipFrost ? "skipped" : "kept"));
                    } else if ("gen".equals(op)) {
                        // This generation's ledger and where it is drawing from. The mirror of
                        // SystemUI's `op gen`; also what a hot reload is judged by.
                        String report = genReport();
                        Xp.log(TAG + "gen " + report);
                        setResultData(report);
                    } else if ("retire_dryrun".equals(op)) {
                        // The rehearsal: retirement and adoption in this generation, no code
                        // swapped. See dryRun().
                        String report = dryRun(c);
                        Xp.log(TAG + "retire_dryrun " + report);
                        setResultData(report);
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
                        setResultData(describeVideo());
                    } else if ("tail".equals(op)) {
                        // This process's recent log lines; see Xp.tail().
                        setResultData(Xp.tail(i.getStringExtra("grep"), i.getIntExtra("n", 40)));
                    } else if ("vgl".equals(op)) {
                        videoWindowTakeover(i.getBooleanExtra("on", true));
                    } else if ("timing".equals(op)) {
                        String rep = timingReport();
                        for (String line : rep.split("\n")) Xp.log(TAG + line);
                        setResultData(rep);
                    } else if ("selftest".equals(op)) {
                        String rep = selfTest();
                        for (String line : rep.split("\n")) Xp.log(TAG + line);
                        setResultData(rep);
                    } else {
                        Xp.log(TAG + "ops: cls --es name <fqcn> [--es grep x]"
                                + " | bmp --es name <fqcn>");
                    }
                } catch (Throwable t) {
                    Xp.w(TAG + "op failed: " + Log.getStackTraceString(t));
                }
            }
        }
        // Only SystemUI's half of the module talks to this one, besides adb.
        ProbeGuard.register(ctx, new IntentFilter(ACTION), TAG, Probe::new,
                "com.android.systemui", BuildConfig.APPLICATION_ID);
        Xp.log(TAG + "receiver registered for " + ACTION);
        // The backstop for a hide that never reached the desktop engine. The show needs none:
        // it comes as the keyguard locks, which is at the screen going off, not at the wake.
        try {
            IntentFilter screen = new IntentFilter(Intent.ACTION_USER_PRESENT);
            Gen.receiver(ctx, new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    sDesktopKgShown = false;
                    if (!sSharedLock) return;
                    Gen.post(WallpaperProbe::syncSharedLock);
                }
            }, screen, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Xp.w(TAG + "screen receiver failed: " + t);
        }
        // Unprompted as well as when asked: a restart of this process alone would otherwise
        // leave SystemUI on whatever it last heard, which may be nothing.
        sayHello(ctx);
    }

    // ------------------------------------------------------------------ hot reload

    /** Compositions in flight: a source push or a precompose. The check section refuses. */
    private static final java.util.concurrent.atomic.AtomicInteger sComposing =
            new java.util.concurrent.atomic.AtomicInteger();
    /** JPEG writes in flight: the next generation restores the cover from what is on disk. */
    private static final java.util.concurrent.atomic.AtomicInteger sArtSaving =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The feature that cannot be interrupted right now, or null.
     *
     * Asked on the main thread, in the same task as the teardown (Gen.retireOnMain); the
     * composer's counters are atomics. The rule is SystemUI's, only wider: a wallpaper restart is a flash the user barely sees, so
     * anything mid-GL, mid-compose or mid-video is refused rather than handed over.
     */
    static String busyReason() {
        if (sFade != null || sGpuFade != null || sUploadOverride != null
                || sUploading != null || sFadeInFlight || sLeaveAt != 0L) {
            return "a cover crossfade is running";
        }
        if (sComposing.get() > 0) return "a cover is being composed";
        if (sArtSaving.get() > 0) return "the cover is still being written";
        if (sLyricBlurWant != sLyricBlur) return "a lyric blur change is in flight";
        // The keep dim is not a reason to refuse: sKeepFrom/To/At/DurMs travel with the
        // handover and adoptMain() restarts the pump, so the transition carries on at the same
        // point on the shared clock. A KEEP wallpaper was being refused on every install that
        // landed inside its 100-300ms move.
        if (sGlTex != 0) return "a gpu crossfade holds a texture";
        if (sVideoGlLive || WallpaperVideo.holdsGl()) return "the motion cover holds GL objects";
        if (sCoverVideoActive || sActiveEngine != null || sPathPinned) {
            return "the cover video owns the wallpaper window";
        }
        if (Boolean.TRUE.equals(sLockIsVideo) || (sLockIsVideo == null && sVideoEngine != null)) {
            return "the lock wallpaper is a live video";
        }
        return null;
    }

    // What a generation hands the next one. Index 0 is the layout version: an array this build
    // does not know is ignored whole, and the new generation adopts from disk as on a cold start.
    private static final int WALL_STATE_SCHEMA = 1;
    private static final int S_ART = 1, S_CARD = 2, S_ORIG = 3, S_ORIG_PRINT = 4,
            S_TEX_COVER = 5, S_BLUR = 6, S_BLUR_WANT = 7, S_BLUR_SEQ = 8,
            S_MSG_SEQ = 9, S_ART_SEQ = 10, S_REPORTED_W = 11, S_REPORTED_H = 12,
            S_SURFACE_W = 13, S_SURFACE_H = 14, S_FADE_MS = 15, S_TRACK_FADE_MS = 16,
            S_SKIP_FROST = 17, S_GPU_FADE = 18, S_GPU_BROKEN = 19, S_TEX_FIT = 20,
            S_KG_ENGINE = 21, S_DESK_ENGINE = 22, S_KG_TEXTURE = 23, S_KG_RENDERER = 24,
            S_DESK_RENDERER = 25, S_OEM_DARK = 26, S_OEM_DARK_KNOWN = 27,
            S_HOME_DARK = 28, S_HOME_DARK_KNOWN = 29, S_SHARED = 30, S_DESK_KG = 31,
            S_DESK_ON_LOCK = 32, S_SHADE = 33, S_SHADE_AT = 34, S_LOCK_VIDEO = 35,
            S_FRAME_REQ = 36, S_KEEP_DIM = 37, S_KEEP_FROM = 38, S_KEEP_TO = 39,
            S_KEEP_AT = 40, S_KEEP_MS = 41, S_GL_TID = 42, S_ASKS = 43, S_TOKEN = 44,
            S_CTX = 45, S_TEX_SIZES = 46;
    /** What every build of this schema hands over; later fields are read only when present. */
    private static final int S_LEN = 46;

    /**
     * The runtime state the next generation cannot look up again, and nothing else: settings
     * stay in the state file, and the caches (frosted copies, compositions, fade buffers) are
     * recomputed on demand. Everything here is a framework object or a primitive, which is what
     * the framework lets cross a generation.
     */
    static Object[] save() {
        return new Object[]{
                WALL_STATE_SCHEMA,
                sArt, sCardMode, sOrig, sOrigPrint, sTexShowsCover,
                sLyricBlur, sLyricBlurWant, sBlurSeq, sMsgSeq, sArtSeq,
                sReportedW, sReportedH, sSurfaceW, sSurfaceH,
                sFadeMs, sTrackFadeMs, sSkipFrost, sGpuFadeOn, sGpuFadeBroken, sTexFit,
                sKeyguardEngine, sDesktopEngine, sKeyguardTexture, sKeyguardRenderer,
                sDesktopRenderer, sOemDarken, sOemDarkenKnown, sHomeDarken, sHomeDarkenKnown,
                sSharedLock, sDesktopKgShown, sDesktopOnLock, sShadeOn, sShadeAt, sLockIsVideo,
                sFrameReq, sKeepDim, sKeepFrom, sKeepTo, sKeepAt, sKeepDurMs,
                sKeyguardGlTid, sAsks, ProbeGuard.learnedToken(), sCtx, texSizes(),
        };
    }

    /**
     * Every keyguard texture's last upload size, as texture, Rect pairs. A foldable has one
     * texture per screen, and the dimension hook answers each from this map; handing over only
     * sKeyguardTexture's left the other screen's on the OEM's own size until its next upload.
     */
    private static Object[] texSizes() {
        synchronized (sTexSizes) {
            Object[] out = new Object[sTexSizes.size() * 2];
            int i = 0;
            for (java.util.Map.Entry<Object, android.graphics.Rect> e : sTexSizes.entrySet()) {
                out[i++] = e.getKey();
                out[i++] = new android.graphics.Rect(e.getValue());
            }
            return out;
        }
    }

    /**
     * Puts the handed-over state in place. Runs on the framework's binder thread *before*
     * install(): the new hooks are live the moment they are installed, and an upload arriving
     * before sArt is back would see no cover and hand MiWallpaper the real wallpaper to decode.
     */
    static void adoptState(Object[] f) {
        if (f == null || f.length < S_LEN) return;
        if (!(f[0] instanceof Integer) || (Integer) f[0] != WALL_STATE_SCHEMA) {
            Xp.w(TAG + "saved wall state is not this build's; the cover comes back from disk");
            return;
        }
        if (f[S_ART] instanceof Bitmap) sArt = (Bitmap) f[S_ART];
        if (f[S_CARD] instanceof Boolean) sCardMode = (Boolean) f[S_CARD];
        if (f[S_ORIG] instanceof Bitmap) sOrig = (Bitmap) f[S_ORIG];
        sOrigPrint = intAt(f, S_ORIG_PRINT, sOrigPrint);
        sLyricBlur = boolAt(f, S_BLUR, sLyricBlur);
        sLyricBlurWant = boolAt(f, S_BLUR_WANT, sLyricBlurWant);
        sBlurSeq = longAt(f, S_BLUR_SEQ, sBlurSeq);
        sMsgSeq = intAt(f, S_MSG_SEQ, sMsgSeq);
        sArtSeq = intAt(f, S_ART_SEQ, sArtSeq);
        sReportedW = intAt(f, S_REPORTED_W, sReportedW);
        sReportedH = intAt(f, S_REPORTED_H, sReportedH);
        sSurfaceW = intAt(f, S_SURFACE_W, sSurfaceW);
        sSurfaceH = intAt(f, S_SURFACE_H, sSurfaceH);
        sFadeMs = longAt(f, S_FADE_MS, sFadeMs);
        sTrackFadeMs = longAt(f, S_TRACK_FADE_MS, sTrackFadeMs);
        sSkipFrost = boolAt(f, S_SKIP_FROST, sSkipFrost);
        sGpuFadeOn = boolAt(f, S_GPU_FADE, sGpuFadeOn);
        sGpuFadeBroken = boolAt(f, S_GPU_BROKEN, sGpuFadeBroken);
        sTexFit = boolAt(f, S_TEX_FIT, sTexFit);
        sKeyguardEngine = f[S_KG_ENGINE];
        sDesktopEngine = f[S_DESK_ENGINE];
        sKeyguardTexture = f[S_KG_TEXTURE];
        sKeyguardRenderer = f[S_KG_RENDERER];
        sDesktopRenderer = f[S_DESK_RENDERER];
        sOemDarken = boolAt(f, S_OEM_DARK, sOemDarken);
        sOemDarkenKnown = boolAt(f, S_OEM_DARK_KNOWN, sOemDarkenKnown);
        sHomeDarken = boolAt(f, S_HOME_DARK, sHomeDarken);
        sHomeDarkenKnown = boolAt(f, S_HOME_DARK_KNOWN, sHomeDarkenKnown);
        sSharedLock = boolAt(f, S_SHARED, sSharedLock);
        sDesktopKgShown = f[S_DESK_KG] instanceof Boolean ? (Boolean) f[S_DESK_KG] : null;
        sDesktopOnLock = boolAt(f, S_DESK_ON_LOCK, sDesktopOnLock);
        sShadeOn = boolAt(f, S_SHADE, sShadeOn);
        sShadeAt = longAt(f, S_SHADE_AT, sShadeAt);
        sLockIsVideo = f[S_LOCK_VIDEO] instanceof Boolean ? (Boolean) f[S_LOCK_VIDEO] : null;
        sFrameReq = intAt(f, S_FRAME_REQ, sFrameReq);
        synchronized (KEEP_LOCK) {
            sKeepDim = floatAt(f, S_KEEP_DIM, sKeepDim);
            sKeepFrom = floatAt(f, S_KEEP_FROM, sKeepFrom);
            sKeepTo = floatAt(f, S_KEEP_TO, sKeepTo);
            sKeepAt = longAt(f, S_KEEP_AT, sKeepAt);
            sKeepDurMs = intAt(f, S_KEEP_MS, sKeepDurMs);
        }
        sKeyguardGlTid = intAt(f, S_GL_TID, sKeyguardGlTid);
        sAsks = intAt(f, S_ASKS, sAsks);
        sTexShowsCover = sArt != null;
        if (f[S_CTX] instanceof Context) sCtx = (Context) f[S_CTX];
        // Here rather than in adoptMain: the dimension hook is live as soon as install() runs.
        if (f.length > S_TEX_SIZES && f[S_TEX_SIZES] instanceof Object[]) {
            Object[] sizes = (Object[]) f[S_TEX_SIZES];
            for (int i = 0; i + 1 < sizes.length; i += 2) {
                if (sizes[i] != null && sizes[i + 1] instanceof android.graphics.Rect) {
                    sTexSizes.put(sizes[i], (android.graphics.Rect) sizes[i + 1]);
                }
            }
        }
        ProbeGuard.adoptLearnedToken(f[S_TOKEN] instanceof String ? (String) f[S_TOKEN] : null);
    }

    private static int intAt(Object[] f, int i, int had) {
        return f[i] instanceof Number ? ((Number) f[i]).intValue() : had;
    }

    private static long longAt(Object[] f, int i, long had) {
        return f[i] instanceof Number ? ((Number) f[i]).longValue() : had;
    }

    private static float floatAt(Object[] f, int i, float had) {
        return f[i] instanceof Number ? ((Number) f[i]).floatValue() : had;
    }

    private static boolean boolAt(Object[] f, int i, boolean had) {
        return f[i] instanceof Boolean ? (Boolean) f[i] : had;
    }

    /**
     * The rest of the adoption, on the main thread: the R8 names read back off the engine it
     * was handed, the texture's size put back where the dimension hook looks, and then the
     * cold-start registration path. No reload is asked for - the texture already shows the
     * cover, and re-running onSurfaceCreated would only upload the same picture again.
     */
    static void adoptMain(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
        Object eng = sKeyguardEngine != null ? sKeyguardEngine : sDesktopEngine;
        if (eng != null) {
            try {
                EngineNames.resolve(eng);
            } catch (Throwable t) {
                Xp.w(TAG + "engine names not resolved after the reload: " + t);
            }
        }
        // A build that handed over no size map: the one texture it knew of, at the last size.
        if (sKeyguardTexture != null && sReportedW > 0 && sReportedH > 0
                && !sTexSizes.containsKey(sKeyguardTexture)) {
            sTexSizes.put(sKeyguardTexture,
                    new android.graphics.Rect(0, 0, sReportedW, sReportedH));
        }
        if (sCtx == null) {
            Xp.w(TAG + "no context after the reload; the receivers wait for a cold start");
            return;
        }
        register(sCtx);
        if (sKeepAt != 0L) startKeepPump();
        Xp.log(TAG + "adopted art=" + describe(sArt) + " card=" + sCardMode
                + " blur=" + sLyricBlur + "/" + sLyricBlurWant
                + " engine=" + (sKeyguardEngine == null ? "none" : "yes"));
    }

    /**
     * What retirement takes back besides the ledger's registrations. The cover itself is
     * handed over, so it is not touched; everything here is a cache this generation was the
     * last holder of.
     */
    static void retireForReload() {
        sKeepPumping = false;
        KEEP_MAIN.removeCallbacks(KEEP_PUMP);
        WallpaperVideo.retire();
        sComposed = null;
        sFitted = null;
        sFittedOf = null;
        dropFrosted();
        synchronized (sScreenArts) {
            sScreenArts.clear();
            sScreenArtOf = null;
        }
        sShadeFitted = null;
        sShadeFitOf = null;
        // The two fade buffers are screen-sized each; the check section has already said no
        // fade is running, so nothing is reading them.
        sFadeBuf = null;
        sFadeBuf2 = null;
        sKeptShown = null;
        sFadeFrom = null;
        Xp.log(TAG + "retired: cover handed over, caches let go");
    }

    /**
     * The rehearsal: the retirement steps, the ledger sweep and the adoption path, in this
     * generation and with no code swapped. `op retire_dryrun`, the probe only.
     */
    static String dryRun(Context ctx) {
        if (Looper.myLooper() != Looper.getMainLooper()) return "not on the main thread";
        String busy = busyReason();
        if (busy != null) return "deferred: " + busy;
        Object[] state = save();
        String before = Gen.report();
        String swept;
        Gen.quiescing = true;
        try {
            retireForReload();
            swept = Gen.sweepForDryRun(2000L) ? Gen.report() : "sweep timed out";
        } catch (Throwable t) {
            swept = "failed: " + t;
        } finally {
            Gen.quiescing = false;
        }
        sRegistered = false;
        adoptState(state);
        adoptMain(ctx);
        return "retire_dryrun before{" + before + "} swept{" + swept
                + "} after{" + Gen.report() + "}";
    }

    /**
     * What this generation holds in the wallpaper process, and where it is drawing from.
     * `adb shell am broadcast -a btm.m.os4.systemuihook.hypermusiccover.next.WPROBE --es op gen`.
     */
    private static String genReport() {
        return Gen.report() + " " + HotReload.status()
                + " art=" + describe(sArt) + " orig=" + describe(sOrig)
                + " card=" + sCardMode + " blur=" + sLyricBlur + "/" + sLyricBlurWant
                + " tex=" + sReportedW + "x" + sReportedH + "@" + sSurfaceW + "x" + sSurfaceH
                + " engine=" + (sKeyguardEngine == null ? "none" : "yes")
                + " fade=" + (sFade != null || sGpuFade != null)
                + " gpu=" + sGpuFadeOn + "/" + sDrawHooked + "/" + sGpuFadeBroken
                + " glTex=" + sGlTex + " video=" + (sLockIsVideo == null ? "?" : sLockIsVideo)
                + " takeover=" + sCoverVideoActive + " texfit=" + sTexFit
                + " keep=" + sKeepDim;
    }

    /**
     * The last track change, segment by segment.
     *
     * Segments are printed only where both ends were actually stamped, so the JPEG path - which
     * has no source read and no composition on this side - reports what it did rather than a row
     * of zeroes claiming it was instant.
     */
    private static String timingReport() {
        if (sTmRecv == 0L) {
            return "=== cover timing ===\nno push seen yet."
                    + " Change a track with the lock screen up, then ask again.";
        }
        StringBuilder sb = new StringBuilder("=== cover timing === (" + sTmKind + " path)");
        // Absolute, so a press stamped outside this process (`cut -d" " -f1 /proc/uptime` in the
        // same shell command as the key event) can be subtracted from it: what is left is the
        // player's own share, which is the part none of the segments below can see.
        sb.append("\n  the module saw the new track at (uptime): ").append(sTmT0).append("ms");
        // The head start: the press, heard in SystemUI, against the player getting round to
        // saying so. This is the room a prefetch would have to work in.
        if (sTmSkip > 0L && sTmT0 > sTmSkip) {
            sb.append("\n  the PLAYER took: ").append(sTmT0 - sTmSkip)
                    .append("ms to report the ")
                    .append(sTmSkipDir < 0 ? "previous" : "next")
                    .append(" track (we knew the moment it was asked for)");
        }
        seg(sb, "waiting for the artwork", sTmT0, sTmArt,
                sTmTries > 0 ? sTmTries + (sTmTries == 1 ? " try" : " tries") : null);
        seg(sb, "  of which the wallpaper check", sTmCheckMs);
        seg(sb, "preparing and writing it", sTmArt, sTmSent, null);
        seg(sb, "broadcast in flight", sTmSent, sTmRecv, null);
        seg(sb, "reading the source", sTmRecv, sTmRead, null);
        seg(sb, "composing", sTmRead, sTmComposed, null);
        seg(sb, "frosting for the lyrics", sTmComposed, sTmFrosted, null);
        seg(sb, "on to the main thread", sTmFrosted > 0 ? sTmFrosted : sTmComposed, sTmMain, null);
        seg(sb, "uploading the texture", sTmMain, sTmUploaded, null);
        long end = sTmUploaded > 0L ? sTmUploaded : sTmMain;
        if (sTmT0 > 0L && end > sTmT0) {
            sb.append("\n  ---\n  this press to cover on screen: ").append(end - sTmT0)
                    .append("ms");
        }
        // What someone pressing next repeatedly actually watches: the presses in between were
        // thrown away, and the screen held the old cover for all of it.
        if (sTmBurst > 0L && end > sTmBurst && sTmSkips > 0) {
            sb.append("\n  FIRST press of this burst to cover on screen: ")
                    .append(end - sTmBurst).append("ms (")
                    .append(sTmSkips).append(" more press")
                    .append(sTmSkips == 1 ? "" : "es").append(" swallowed on the way)");
        }
        // The two halves of the lyrics' blur. They disagreeing is the bug where a track changed
        // at the moment the lyrics arrived and the cover stayed sharp for the rest of the song:
        // `want` is what SystemUI last asked for, `on` is what the cover is actually drawn with.
        sb.append("\nlyric blur: ").append(sLyricBlur ? "on" : "off")
                .append(", asked for: ").append(sLyricBlurWant ? "on" : "off")
                .append(" (decided at ").append(sBlurSeq).append("ms, ")
                .append(sMsgSeq).append(" messages since)")
                .append(sLyricBlur == sLyricBlurWant ? "" : "   <- OUT OF STEP");
        sb.append("\ncrossfade after that: ").append(sTrackFadeMs)
                .append("ms on a track change, ").append(sFadeMs)
                .append("ms entering or leaving cover mode");
        return sb.toString();
    }

    private static void seg(StringBuilder sb, String name, long from, long to, String note) {
        if (from <= 0L || to <= 0L || to < from) return;
        sb.append("\n  ").append(name).append(": ").append(to - from).append("ms");
        if (note != null) sb.append(" (").append(note).append(')');
    }

    private static void seg(StringBuilder sb, String name, long ms) {
        if (ms <= 0L) return;
        sb.append("\n  ").append(name).append(": ").append(ms).append("ms");
    }

    /**
     * Takes a blur answer from SystemUI, unless a later one has already been taken. Answers
     * whether this one was the later.
     *
     * The place in line rides with the answer because the two messages that can carry one are
     * built and sent from different threads over there: the cover push is composed and sent on
     * the worker while the answer itself is decided on the main thread. The one that arrives last
     * is therefore not the one that was decided last, and the cover is the slow half - it is read
     * first and sent after - so it is the one that arrives holding the stale answer. Taken at
     * face value that answer undoes the switch that came after it, and nothing re-sends, which is
     * a song playing out sharp under its lyrics.
     */
    private static boolean takeBlurDecision(long seq, boolean on, String via) {
        if (seq != 0L && seq < sBlurSeq) {
            Xp.log(TAG + "the " + via + " carried an answer made before the one in hand ("
                    + seq + " against " + sBlurSeq + "), leaving the cover "
                    + (sLyricBlurWant ? "frosted" : "sharp"));
            return false;
        }
        if (seq > sBlurSeq) sBlurSeq = seq;
        if (sLyricBlurWant != on) {
            sLyricBlurWant = on;
            Xp.log(TAG + "the " + via + " asks for a " + (on ? "frosted" : "sharp")
                    + " cover (decided at " + seq + ")");
        }
        return true;
    }

    /**
     * Frosts the cover for the lyrics, or clears it. The blur is made on the composer thread; the
     * fade waits for one already in the air - the cover arriving, a track changing - to land
     * first, because a new fade starts from the picture it is handed, not from what is on screen.
     */
    static boolean lyricBlurWanted() {
        return sLyricBlurWant;
    }

    private static void setLyricBlur(final boolean on) {
        sLyricBlurWant = on;
        WallpaperVideo.setFrost(on);
        composer().post(new Runnable() {
            @Override
            public void run() {
                // Warming the still path's frosted copy. A live wallpaper's cover is frosted by
                // the video worker instead, and this was 110-150ms on the composer - the thread
                // the next cover's decode waits on - for a picture nothing reads.
                Bitmap sharp = videoPath() ? null : sharpFittedArt();
                if (on && sharp != null) frostedOf(sharp);
                final long t0 = SystemClock.uptimeMillis();
                final Runnable settle = new Runnable() {
                    @Override
                    public void run() {
                        // Overtaken while this one was waiting out a fade. The wait re-posts,
                        // so a newer switch reaches the main thread first and this one would
                        // otherwise land afterwards and undo it.
                        if (on != sLyricBlurWant) return;
                        boolean video = videoPath();
                        if (on == sLyricBlur) {
                            // Already frosted - carried in on the cover itself.
                            if (on && !video) tellBlurShown("already frosted");
                            return;
                        }
                        if ((sFade != null || sGpuFade != null)
                                && SystemClock.uptimeMillis() - t0 < 1500L) {
                            Gen.postDelayed(this, 30L);
                            return;
                        }
                        // The lyrics wait for this before they come in, so that the two come in
                        // together (LockLyrics.blurSettled). Told as the fade starts, not as it
                        // ends: the lyrics have a fade of their own.
                        if (on && !video) {
                            tellBlurShown(sArt == null ? "no cover to frost" : "frosting");
                        }
                        // On the video path `from` is only the crossfade's other end, and
                        // without a crossfade it would be a frosting on the main thread for
                        // nothing.
                        Bitmap from = video && !sVideoFade ? null : fittedArt();
                        sLyricBlur = on;
                        Bitmap to = video ? null : fittedArt();
                        Xp.log(TAG + "lyric blur " + (on ? "on" : "off") + " (waited "
                                + (SystemClock.uptimeMillis() - t0) + "ms)");
                        if (sArt == null) return;
                        if (video) {
                            sFadeFrom = from;
                            videoWindowTakeover(false);
                            return;
                        }
                        if (from != null && to != null) startFade(from, to, null);
                        else reloadTexture();
                    }
                };
                Gen.post(settle);
            }
        });
    }

    /** Tells SystemUI this build composes covers from their source. See Main.sWpComposes. */
    private static void sayHello(Context c) {
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "wphello");
            out.putExtra("composes", true);
            ProbeGuard.send(c, out);
        } catch (Throwable t) {
            Xp.w(TAG + "hello failed: " + t);
        }
    }

    /**
     * Puts a new picture in as the art: the fade from what is on screen now, or a plain reload.
     * Main thread.
     */
    private static void applyArt(Bitmap b, boolean reload, boolean fade) {
        sTmMain = SystemClock.uptimeMillis();
        // Read before sArt moves: on the way into cover mode this is the lock wallpaper, and on
        // a track change it is the album that is on screen right now.
        Bitmap from = fittedArt();
        // Already showing a cover means this picture replaces another one: a track change, which
        // is the fade that has nothing to keep company with and gets the short duration. With no
        // cover yet, what it fades from is the lock wallpaper - that is cover mode opening, and
        // it travels with the clock. See sTrackFadeMs.
        boolean trackChange = from != null;
        // Arriving while the last cover fades out. sArt still names that cover until the fade's
        // end, but the eased fade has put the wallpaper on screen long before then: taken at its
        // word, this faded from the old cover to the same cover - a cut from the wallpaper
        // straight to the art, on every quick tap back in. Past half way, it is the wallpaper
        // that is showing, and the way in starts from there.
        long leaveAt = sLeaveAt;
        sLeaveAt = 0L;
        if (leaveAt != 0L && sOrig != null && sLeaveMs > 0L) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - leaveAt) / (float) sLeaveMs);
            if (1f - (1f - t) * (1f - t) * (1f - t) >= 0.5f) {
                from = null;
                trackChange = false;
            }
        }
        if (from == null) from = sOrig;
        // On a live lock wallpaper the cover that is on screen is the art
        // this process already holds, whether or not it could be "fitted"
        // - the fitter knows a still wallpaper's texture size, and a video
        // wallpaper has no such texture. Read before sArt moves.
        if (from == null && videoPath()) from = sArt;
        // The video path's crossfade reads this from its worker, where
        // the composed cover video is built; the still path hands it
        // straight to startFade() below.
        sFadeFrom = from;
        // A cover carries what the lyrics wanted at the moment it was sent, so a track change is
        // also where the two processes settle any disagreement: a lost broadcast, or a switch
        // that never landed. Applied after `from` and before `to`, so the new cover simply
        // arrives with the right finish instead of fading in sharp and frosting a beat later.
        if (sLyricBlur != sLyricBlurWant) {
            sLyricBlur = sLyricBlurWant;
            Xp.log(TAG + "lyric blur " + (sLyricBlur ? "on" : "off") + " with the new cover");
        }
        sArt = b;
        sAsks = 0;
        sFitted = null;
        sFittedOf = null;
        // Scale here, not on the GL thread - on the still path. A live wallpaper's cover is built
        // by the video worker from sArt, and this was a full-screen frosting on the main thread
        // (110-140ms) in front of it on every entry under the lyrics, for a picture nothing read.
        Bitmap to = videoPath() ? null : fittedArt();
        Xp.log(TAG + "art set " + describe(b));
        if (videoPath()) videoWindowTakeover(false);
        else if (fade && from != null && to != null) {
            startFade(from, to, null, trackChange ? sTrackFadeMs : sFadeMs);
        } else if (reload) reloadTexture();
        tellArtShown();
    }

    /**
     * The new cover has started onto the screen. SystemUI's square card holds its own swap for
     * this: it has its art the moment the track changes, while this side still has the broadcast,
     * the composition and the upload in front of it - measured 70-160ms - so the card used to
     * turn over visibly ahead of the blurred background it sits on.
     */
    private static void tellArtShown() {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "wpart");
            ProbeGuard.send(c, out);
        } catch (Throwable t) {
            Xp.w(TAG + "wpart failed: " + t);
        }
    }

    /** Where this process keeps the last source, to compose again after its own restart. */
    private static final String SRC_FILE = "hyperchanger_hmc_next_src.raw";

    private static Handler sComposer;
    /** The newest source asked for; a composition overtaken by a newer one is dropped. */
    private static volatile int sSrcSeq;

    private static synchronized Handler composer() {
        if (sComposer == null) {
            android.os.HandlerThread t = Gen.handlerThread("mc-compose",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
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
                                          final boolean reload, final boolean fade,
                                          final boolean cardMode, final String preKey) {
        final int seq = ++sSrcSeq;
        final long t0 = SystemClock.uptimeMillis();
        sComposing.incrementAndGet();
        composer().post(new Runnable() {
            @Override
            public void run() {
                if (seq != sSrcSeq) {
                    sComposing.decrementAndGet();
                    return;
                }
                final Bitmap b;
                final long read, composed;
                final boolean kept;
                try {
                    CoverCompose.Source s = CoverCompose.readSource(new File(path));
                    if (s == null) {
                        Xp.log(TAG + "source at " + path + " is not one this build reads");
                        sComposing.decrementAndGet();
                        return;
                    }
                    read = SystemClock.uptimeMillis();
                    sTmRead = read;
                    if (seq != sSrcSeq) {
                        sComposing.decrementAndGet();
                        return;
                    }
                    long key = CoverCompose.sourceKey(s, cardMode);
                    Composed hit = composedFor(key);
                    kept = hit != null;
                    Composed made = kept ? hit : compose(s, cardMode, key);
                    // SystemUI's name for it, which the next tap asks for (applyKept).
                    if (preKey != null) made.preKey = preKey;
                    made.source = s;
                    b = made.sharp;
                    composed = SystemClock.uptimeMillis();
                    sTmComposed = composed;
                    // Under the lyrics the picture that actually goes up is the frosted copy, and
                    // making it is another full-screen blur. Left to applyArt() it would be made
                    // on the main thread, in front of the crossfade the track change is waiting
                    // on. Made here it is already in the cache by the time that asks - keyed on
                    // this very bitmap, so it only hits when the composition is texture-sized,
                    // which is the normal case (the texture is composed to the screen).
                    if (sLyricBlurWant && b.getWidth() == sReportedW && b.getHeight() == sReportedH
                            && !frostedHas(b)) {
                        // Built from the source and the layout, not from `b` - see
                        // CoverCompose.frostedFor() - but cached under `b`, which is what every
                        // reader asks with once this becomes the art. The kept small copy makes
                        // it a stretch rather than a blur.
                        cacheFrosted(b, frostOf(made, s.w, s.h));
                    }
                    sTmFrosted = SystemClock.uptimeMillis();
                    keepComposed(made, 0L);
                    saveSourceLater(c, s, cardMode);
                } catch (Throwable t) {
                    Xp.w(TAG + "composing from the source failed: " + t);
                    sComposing.decrementAndGet();
                    return;
                }
                // Still counted until the picture is up: the check section must not let the
                // process retire between the composition and the thread that applies it.
                Runnable apply = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (seq != sSrcSeq) {
                                Xp.log(TAG + "composed cover overtaken by a newer one, dropped");
                                return;
                            }
                            Xp.log(TAG + "composed from the source: read " + (read - t0)
                                    + "ms, compose " + (composed - read) + "ms"
                                    + (kept ? " (kept)" : "") + ", to the main thread "
                                    + (SystemClock.uptimeMillis() - composed) + "ms");
                            applyArt(b, reload, fade);
                            // The JPEG from the old path is now older than what is on screen.
                            new File(c.getFilesDir(), ART_FILE).delete();
                        } finally {
                            sComposing.decrementAndGet();
                        }
                    }
                };
                // Refused while a dry run has the gate closed: nothing will apply it, and a
                // count left up here would refuse every hot reload after it.
                if (!Gen.post(apply)) sComposing.decrementAndGet();
            }
        });
    }

    /**
     * One composition and the frosting for it before its stretch, under the key of the source
     * they came from (CoverCompose.sourceKey).
     */
    private static final class Composed {
        final long key;
        final Bitmap sharp;
        final Bitmap frostSmall;
        /** frostSmall stretched to the texture, made the first time the lyrics want it. */
        volatile Bitmap frost;
        /** SystemUI's name for it when it came as a precompose: what the tap asks for. */
        volatile String preKey;
        /** Its source, for this process's copy on disk once the tap has put it up. */
        volatile CoverCompose.Source source;

        Composed(long key, Bitmap sharp, Bitmap frostSmall) {
            this.key = key;
            this.sharp = sharp;
            this.frostSmall = frostSmall;
        }
    }

    /**
     * The last composition, kept past the cover that showed it. Composer thread only.
     *
     * Every way into cover mode composed from scratch - 55-76ms here, and the frosting for the
     * lyrics after it - and all of it stood between the tap and the first frame of the fade, so
     * the blurred background came up behind the clock that was already moving. Kept, the same
     * song's way back in is a read of the source and nothing else; and SystemUI hands over the
     * source ahead of a likely entry (op precompose) so the first way in can find it too.
     *
     * One screen-sized bitmap and a ~0.3MB small frosting. Let go COMPOSED_KEEP_MS after the cover
     * stops showing it, PRECOMPOSED_KEEP_MS after a precompose nothing used.
     */
    private static volatile Composed sComposed;

    private static final long COMPOSED_KEEP_MS = 30_000L;
    private static final long PRECOMPOSED_KEEP_MS = 60_000L;

    /** [c]'s frosted cover at the texture's size, stretched once and kept with it. */
    private static Bitmap frostOf(Composed c, int w, int h) {
        Bitmap f = c.frost;
        if (f == null || f.isRecycled() || f.getWidth() != w || f.getHeight() != h) {
            f = CoverCompose.stretchFrosted(c.frostSmall, w, h);
            c.frost = f;
        }
        return f;
    }

    private static Composed composedFor(long key) {
        Composed c = sComposed;
        return c != null && c.key == key && !c.sharp.isRecycled() ? c : null;
    }

    private static Composed compose(CoverCompose.Source s, boolean cardMode, long key) {
        Bitmap sharp = cardMode ? CoverCompose.cardBackground(s.src, s.w, s.h)
                : CoverCompose.composeWallpaper(s.src, s.w, s.h, s.bias);
        Bitmap frost = CoverCompose.frostedSmallFor(s.src, s.w, s.h, cardMode ? 0.5f : s.bias);
        return new Composed(key, sharp, frost);
    }

    /** Keeps [c]; with [dropAfterMs] above 0, lets it go then unless a cover is showing it. */
    private static void keepComposed(Composed c, long dropAfterMs) {
        sComposed = c;
        composer().removeCallbacks(DROP_COMPOSED);
        if (dropAfterMs > 0L) composer().postDelayed(DROP_COMPOSED, dropAfterMs);
    }

    /** The cover has gone: what it showed is kept a while for the way back in. */
    private static void composedLater() {
        composer().removeCallbacks(DROP_COMPOSED);
        composer().postDelayed(DROP_COMPOSED, COMPOSED_KEEP_MS);
    }

    private static final Runnable DROP_COMPOSED = new Runnable() {
        @Override
        public void run() {
            Composed c = sComposed;
            // On screen again: the cover going off says when to start counting.
            if (c == null || sArt == c.sharp) return;
            sComposed = null;
            Xp.log(TAG + "kept composition let go");
        }
    };

    /** The precompose key of the cover the tap last put up (applyKept). Main thread. */
    private static String sKeptShown;

    /**
     * The tap's own message (CoverPush.sendKeptFirst): the kept composition SystemUI handed over
     * ahead of it, put up right here on the main thread - no source to read, no composer queue
     * to wait in. Nothing when it is not here or not this one; the push behind brings it then.
     */
    private static void applyKept(String preKey, boolean reload, boolean fade) {
        Composed c = sComposed;
        int w = sReportedW, h = sReportedH;
        if (c == null || preKey == null || !preKey.equals(c.preKey) || c.sharp.isRecycled()
                || c.sharp.getWidth() != w || c.sharp.getHeight() != h || videoPath()) {
            Xp.log(TAG + "the tap's kept cover is not here (" + (c == null ? "nothing kept"
                    : preKey != null && preKey.equals(c.preKey) ? "size or path" : "another")
                    + "), waiting for the push");
            return;
        }
        // Kept for as long as it is on screen again (DROP_COMPOSED looks at sArt).
        composer().removeCallbacks(DROP_COMPOSED);
        if (sLyricBlurWant && !frostedHas(c.sharp)) cacheFrosted(c.sharp, frostOf(c, w, h));
        sKeptShown = preKey;
        Xp.log(TAG + "cover put up from the tap, kept composition");
        applyArt(c.sharp, reload, fade);
        final CoverCompose.Source src = c.source;
        if (src != null && sCtx != null) saveSourceLater(sCtx, src, sCardMode);
    }

    private static synchronized boolean frostedHas(Bitmap sharp) {
        return sFrosted != null && sFrostedOf == sharp && !sFrosted.isRecycled()
                || sFrosted2 != null && sFrostedOf2 == sharp && !sFrosted2.isRecycled();
    }

    /**
     * Composes a source SystemUI expects to be asked for soon - the lock screen is up with a
     * media card on it - and keeps it without showing it, so entering cover mode finds it done.
     * Never touches sSrcSeq: a real cover arriving meanwhile is not overtaken by this.
     */
    private static void precompose(final String path, final boolean cardMode,
                                   final String preKey) {
        sComposing.incrementAndGet();
        composer().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sArt != null) return;
                    long t0 = SystemClock.uptimeMillis();
                    CoverCompose.Source s = CoverCompose.readSource(new File(path));
                    if (s == null) return;
                    long key = CoverCompose.sourceKey(s, cardMode);
                    Composed have = composedFor(key);
                    Composed c = have != null ? have : compose(s, cardMode, key);
                    c.preKey = preKey;
                    c.source = s;
                    // Stretched now as well: the tap shows it from the main thread, and a
                    // full-screen draw there is a frame the fade would start late by.
                    if (s.w == sReportedW && s.h == sReportedH) frostOf(c, s.w, s.h);
                    keepComposed(c, PRECOMPOSED_KEEP_MS);
                    Xp.log(TAG + "precomposed" + (have != null ? " (already kept)" : "") + " in "
                            + (SystemClock.uptimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    Xp.w(TAG + "precompose failed: " + t);
                } finally {
                    sComposing.decrementAndGet();
                }
            }
        });
    }

    /** This process's own copy of the source, for its next cold start. Low priority, later. */
    private static void saveSourceLater(final Context c, final CoverCompose.Source s,
                                        final boolean cardMode) {
        // Counted with the JPEG write: both are what a later cold start restores, and the
        // check section must not let this generation go before one of them has landed.
        sArtSaving.incrementAndGet();
        Gen.thread("mc-src-save", () -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                CoverCompose.writeSource(new File(c.getFilesDir(), SRC_FILE),
                        s.src, s.w, s.h, s.bias);
                File marker = new File(c.getFilesDir(), "hyperchanger_hmc_next_card_mode");
                if (cardMode) marker.createNewFile();
                else marker.delete();
            } catch (Throwable t) {
                Xp.w(TAG + "saving the source failed: " + t);
            } finally {
                sArtSaving.decrementAndGet();
            }
        }, sArtSaving::decrementAndGet);
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
            // 8.0.8-flip-q18's name, off its bytecode: ImageEngineImpl.Z(Z) opens on
            // `iget-boolean s:Z; if-eqz -> return; iget-object k:Handler; Handler.post(Runnable)`,
            // which is the same post-a-frame shape as T/U above with the fields renamed (that
            // build's guard is s where 7.0.7's is h). The sibling V(Z) is 563 code units of
            // surface work and is NOT this - it is the one to avoid calling by accident.
            {"Z", Boolean.FALSE},
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
        Object eng = lockEngine();
        if (eng == null) {
            if (sSharedLock) {
                // Not a failure: the desktop is on screen, and the cover waits for the lock
                // screen - see syncSharedLock().
                Xp.log(TAG + "reload: the lock screen shares the desktop and is not up, the cover"
                        + " waits for it");
                return;
            }
            Xp.log(TAG + "reload: no keyguard engine (is the lockscreen wallpaper "
                    + "still the same image as the desktop one?)");
            return;
        }
        reloadEngine(eng);
    }

    /**
     * The engine whose texture the lock screen shows: the keyguard's, or with a shared still the
     * desktop's while the lock screen is up. Null when the lock screen shows nothing of ours to
     * reload - shared, and the desktop is what is on screen.
     */
    private static Object lockEngine() {
        if (!sSharedLock) return sKeyguardEngine;
        return sharedKeyguardShown() ? sDesktopEngine : null;
    }

    private static boolean sharedKeyguardShown() {
        Boolean shown = sDesktopKgShown;
        return shown != null ? shown : lockScreenUp();
    }

    /** Whether the desktop's next upload is the lock screen's. See sSharedLock. */
    private static boolean desktopOnLockWanted() {
        return sSharedLock && (sArt != null || sFade != null) && sharedKeyguardShown();
    }

    /**
     * GL thread, from the upload hook: whether this desktop upload goes through the keyguard
     * branch. When it does not, everything the keyguard branch left on the desktop's texture is
     * taken back, so the home screen gets its own picture at its own geometry - the texture
     * dimension hook would otherwise keep answering with the screen-sized cut.
     */
    private static boolean desktopUpload(Object renderer, Bitmap orig) {
        sDesktopRenderer = renderer;
        boolean lock = desktopOnLockWanted();
        if (lock != sDesktopOnLock) {
            sDesktopOnLock = lock;
            Xp.log(TAG + (lock ? "the desktop renderer draws the lock screen's cover"
                    : "the desktop renderer is back to its own picture"));
        }
        boolean gaveOurs = sDesktopGaveOurs;
        sDesktopGaveOurs = false;
        if (lock) return true;
        if (gaveOurs) {
            // Unlocked between the source and the upload: what is about to go up is a cover, on
            // the home screen. Up it goes - this frame cannot be stopped - and straight after it
            // the desktop's own picture.
            Xp.log(TAG + "the desktop was handed a cover as the lock screen went; reloading it");
            final Object eng = sDesktopEngine;
            Gen.post(() -> {
                if (!desktopOnLockWanted()) reloadEngine(eng);
            });
        }
        try {
            Object t = Xp.getObjectField(renderer, "mTexture");
            if (t != null) {
                sTexSizes.remove(t);
                if (sKeyguardTexture == t) sKeyguardTexture = null;
            }
        } catch (Throwable ignored) {
        }
        if (sKeyguardRenderer == renderer) sKeyguardRenderer = null;
        // What a cover fades in from. The keyguard branch learns it from the upload that carries
        // no cover, which on a shared still never comes, so it is taken from the desktop's own
        // uploads instead, cut to the screen exactly as the keyguard branch would cut it.
        if (sSharedLock && !sShadeOn && sArt == null && !gaveOurs) {
            adoptSurfaceOf(renderer);
            Bitmap screen = screenSized(orig);
            rememberOriginal(screen != null ? screen : orig);
        }
        return false;
    }

    /** Told by SystemUI on every message. See sSharedLock. */
    private static void noteSharedLock(boolean shared) {
        if (shared == sSharedLock) return;
        sSharedLock = shared;
        Xp.log(TAG + (shared ? "the lock screen shares the desktop's still: the cover goes on the"
                + " desktop engine while locked" : "the lock screen has a wallpaper of its own again"));
        // Whatever was remembered belonged to the other arrangement's lock wallpaper.
        sOrig = null;
        Gen.post(() -> {
            // The picture the first cover fades in from is learnt from a desktop upload with no
            // cover on it. One now, of the picture already on screen, so that first fade is not
            // a cut.
            if (shared && sArt == null && sDesktopEngine != null) reloadEngine(sDesktopEngine);
            syncSharedLock();
        });
    }

    /**
     * The desktop engine was told the lock screen showed or hid. Main thread or binder - posted.
     */
    private static void onDesktopKeyguard(boolean showing) {
        sDesktopKgShown = showing;
        Gen.post(WallpaperProbe::syncSharedLock);
    }

    /**
     * Puts the desktop's texture in step with the lock screen: the cover on it while the lock
     * screen is up, the desktop's own picture the moment it goes. One reload, and only when the
     * two disagree, so a phone without a cover never has its desktop touched. Main thread.
     */
    private static void syncSharedLock() {
        boolean want = desktopOnLockWanted();
        if (want == sDesktopOnLock) return;
        Object eng = sDesktopEngine;
        if (eng == null) return;
        // A fade in the air is left to finish on its own: its frames find no engine to reload
        // once the lock screen is gone, and its end is what clears the art on the way out.
        Xp.log(TAG + (want ? "the cover goes on the shared desktop wallpaper"
                : "the shared desktop wallpaper gets its own picture back"));
        reloadEngine(eng);
    }

    /**
     * Records an engine instance and says what distinguishes it from the others.
     *
     * The int fields are dumped because on the multi-display build they are what tells the
     * instances apart: MultiDisplayEngineService.b is `which` - the value
     * MiuiWallpaperManager.isLockWhich reads to separate the lock engine from the desktop one -
     * and c is what it passes to WallpaperServiceController alongside it. Neither is named in a
     * way that can be relied on, so they are reported rather than interpreted.
     */
    private static void noteEngineInstance(Object eng) {
        if (eng == null) return;
        if (!sKeyguardEngines.contains(eng)) sKeyguardEngines.add(eng);
        Xp.log(TAG + "keyguard engine #" + sKeyguardEngines.size() + ": " + describeEngine(eng));
    }

    /** One engine instance: its class, its identity, and every int it carries. */
    private static String describeEngine(Object eng) {
        StringBuilder sb = new StringBuilder(eng.getClass().getSimpleName())
                .append('@').append(Integer.toHexString(System.identityHashCode(eng)));
        try {
            for (Class<?> k = eng.getClass(); k != null && k != Object.class;
                 k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    sb.append(' ').append(k.getSimpleName()).append('.').append(f.getName())
                            .append('=').append(f.getInt(eng));
                }
            }
        } catch (Throwable t) {
            sb.append(" (ints unreadable: ").append(t).append(')');
        }
        return sb.toString();
    }

    /**
     * Everything a port to an unseen build needs, in one answer.
     *
     * Written for the case where the phone is someone else's: they run this once (or just send
     * the LSPosed log, which this also goes to) and it has to be enough to work out what moved,
     * without a second round trip. So it reports what was RESOLVED, what it was resolved to, and
     * the candidates for anything that was not - never just "failed".
     */
    static String selfTest() {
        StringBuilder sb = new StringBuilder("=== wallpaper self test ===");
        sb.append("\nwallpaper package: ").append(wallpaperVersion());
        sb.append('\n').append(EngineNames.report());
        sb.append("\nkeyguard engines: ").append(sKeyguardEngines.size());
        synchronized (sKeyguardEngines) {
            for (int n = 0; n < sKeyguardEngines.size(); n++) {
                Object e = sKeyguardEngines.get(n);
                sb.append("\n  #").append(n + 1).append(' ').append(describeEngine(e))
                        .append(e == sKeyguardEngine ? "   <- the one being pushed to" : "");
            }
        }
        sb.append("\ndesktop engine: ").append(sDesktopEngine == null ? "none"
                : describeEngine(sDesktopEngine));
        sb.append("\nvideo engine: ").append(sVideoEngine == null ? "none"
                : sVideoEngine.getClass().getSimpleName());
        sb.append("\nframe request: ").append(sFrameReq < 0 ? "none has answered yet"
                : FRAME_REQUESTS[sFrameReq][0] + "() (candidate " + (sFrameReq + 1) + " of "
                        + FRAME_REQUESTS.length + ")");
        Object eng = sKeyguardEngine;
        if (eng != null) {
            sb.append("\n  (Z)V methods here: ").append(oneBooleanMethods(eng));
        }
        // The three numbers that decide where the picture lands. On one screen they are always
        // the same; where they differ, they name the two screens that got crossed.
        sb.append("\nrender state: ").append(sRenderState.isEmpty() ? "no upload yet" : sRenderState);
        sb.append("\nsurface adopted: ").append(sSurfaceW).append('x').append(sSurfaceH);
        synchronized (sTexSizes) {
            sb.append("\ntexture sizes held: ").append(sTexSizes.size());
            for (java.util.Map.Entry<Object, android.graphics.Rect> e : sTexSizes.entrySet()) {
                sb.append("\n  ").append(e.getKey().getClass().getSimpleName())
                        .append('@').append(Integer.toHexString(System.identityHashCode(e.getKey())))
                        .append(" -> ").append(e.getValue().width()).append('x')
                        .append(e.getValue().height())
                        .append(e.getKey() == sKeyguardTexture ? "  <- current" : "");
            }
        }
        synchronized (sScreenArts) {
            sb.append("\ncuts held: ").append(sScreenArts.size());
        }
        sb.append("\nart: ").append(describe(sArt)).append("  orig: ").append(describe(sOrig));
        sb.append("\ntexture: ").append(sKeyguardTexture == null ? "not captured" : "captured");
        sb.append("\ngpu fade: on=").append(sGpuFadeOn).append(" hooked=").append(sDrawHooked)
                .append(" broken=").append(sGpuFadeBroken);
        // How the last transition actually ended. "TIMED OUT ... armed=false" is the shape of a
        // frame request that the OEM accepted and did nothing with.
        sb.append("\nlast fade: ").append(sLastFade);
        return sb.toString();
    }

    /** The wallpaper app's own version, which is what a report has to be read against. */
    private static String wallpaperVersion() {
        try {
            Context c = sCtx;
            if (c == null) return "unknown (no context)";
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.packageName + " " + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown: " + t;
        }
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
            Xp.w(TAG + "reload: u() failed: " + t);
        }
        // Resolved, never assumed. On the multi-display build this flag is called `w`, and the
        // name `b` that it has on 7.0.7 belongs there to an int holding `which` - so writing `b`
        // blind is not a miss, it is a write onto the engine's own lock-or-desktop identity.
        // EngineNames only hands back a name it has confirmed is a boolean.
        String flag = EngineNames.pendingFlag;
        if (flag == null) {
            Xp.log(TAG + "reload: no pending-surface flag resolved; the frame will redraw what is "
                    + "uploaded instead of re-reading it. " + EngineNames.report());
        } else {
            try {
                Xp.setBooleanField(eng, flag, true);
            } catch (Throwable t) {
                Xp.w(TAG + "reload: the pending-surface field '" + flag + "' failed: " + t);
            }
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

    /** WallpaperVideo still holds GL objects; one more frame on the keyguard lets them go. */
    private static volatile boolean sVideoGlLive;

    /**
     * The keyguard's frame with the motion cover in it: the OEM's own draw, with the texture it
     * binds swapped for the one WallpaperVideo composed this frame. See WallpaperVideo.
     */
    private static Object drawWithVideo(XposedInterface.Chain chain) throws Throwable {
        Object renderer = chain.getThisObject();
        Object wp = null;
        int oemTex = 0;
        try {
            Object prog = Xp.getObjectField(Xp.getObjectField(renderer, "mAnimator"),
                    "mProgram");
            wp = Xp.getObjectField(prog, "mAnimImageGLWallpaper");
            oemTex = ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue();
        } catch (Throwable t) {
            wp = null;
        }
        int tex = wp == null ? 0 : WallpaperVideo.beforeDraw(renderer,
                sGpuFade != null || sFade != null, sReportedW, sReportedH, oemTex);
        sVideoGlLive = WallpaperVideo.on() || WallpaperVideo.holdsGl();
        if (tex != 0) {
            try {
                Xp.setObjectField(wp, "mTextureId", tex);
            } catch (Throwable t) {
                Xp.w(TAG + "video: cannot swap the texture: " + t);
                tex = 0;
            }
        }
        if (tex == 0) wp = null;
        try {
            return chain.proceed();
        } finally {
            // Only if it is still ours: a reload inside the frame sets a new one of the OEM's.
            if (wp != null && ((Integer) Xp.getObjectField(wp, "mTextureId")).intValue() == tex) {
                Xp.setObjectField(wp, "mTextureId", oemTex);
            }
        }
    }

    static Class<?> plainProgram() {
        return sPlainProgram;
    }

    /** Any thread: a frame for the video, asked for on the main thread like the fade's. */
    static void videoFrame() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requestFrame(true);
        } else {
            Gen.post(() -> requestFrame(true));
        }
    }

    /**
     * Puts the current dim on whatever program is about to draw. GL thread, inside the
     * wallpaper's own draw.
     *
     * The location is cached per program: the keyguard's program is rebuilt on some
     * transitions, and asking the driver for the location every frame would be a call per
     * frame for a value that only moves during a transition.
     */
    private static void applyKeepDim() {
        synchronized (KEEP_LOCK) {
            if (sKeepAt != 0L) {
                float t = sKeepDurMs <= 0 ? 1f
                        : Math.min(1f, (android.os.SystemClock.uptimeMillis() - sKeepAt)
                        / (float) sKeepDurMs);
                float e = t * t * (3f - 2f * t);
                sKeepDim = sKeepFrom + (sKeepTo - sKeepFrom) * e;
                if (t >= 1f) {
                    sKeepDim = sKeepTo;
                    sKeepFrom = sKeepTo;
                    sKeepAt = 0L;
                }
            }
            noteKeepDrawLocked();
        }
        android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_CURRENT_PROGRAM,
                sCurProgram, 0);
        final int prog = sCurProgram[0];
        if (prog != sKeepDimProgram) {
            sKeepDimProgram = prog;
            sKeepDimLoc = prog == 0 ? -1
                    : android.opengl.GLES20.glGetUniformLocation(prog, "uKeepDim");
        }
        if (sKeepDimLoc >= 0) {
            android.opengl.GLES20.glUniform1f(sKeepDimLoc, sKeepDim);
        }
    }

    /** The values the shader was really told, for `op keepdimreport`. Holds KEEP_LOCK. */
    private static void noteKeepDrawLocked() {
        int n = sKeepLogN;
        float last = n == 0 ? Float.NaN : sKeepLogV[(n - 1) % KEEP_LOG];
        if (!Float.isNaN(last) && Math.abs(last - sKeepDim) < 0.005f) return;
        sKeepLogAt[n % KEEP_LOG] = android.os.SystemClock.uptimeMillis();
        sKeepLogV[n % KEEP_LOG] = sKeepDim;
        sKeepLogN = n + 1;
        sKeepDraws++;
    }

    /**
     * Asks for wallpaper frames while the dim is moving. A still wallpaper draws only when
     * asked, so without this the value would sit in a uniform nobody renders.
     */
    private static final Runnable KEEP_PUMP = new Runnable() {
        @Override
        public void run() {
            boolean live;
            synchronized (KEEP_LOCK) {
                live = sKeepAt != 0L;
                // No frame came back for a while: the request is not reaching the renderer
                // on this build. Snap and stop rather than pump for ever.
                if (live && android.os.SystemClock.uptimeMillis() - sKeepAt
                        > sKeepDurMs + 250L) {
                    sKeepDim = sKeepTo;
                    sKeepFrom = sKeepTo;
                    sKeepAt = 0L;
                    live = false;
                }
            }
            if (!live) {
                sKeepPumping = false;
                return;
            }
            requestFrame(true);
            KEEP_MAIN.postDelayed(this, KEEP_PUMP_MS);
        }
    };

    private static void startKeepPump() {
        if (sKeepPumping) return;
        sKeepPumping = true;
        KEEP_MAIN.removeCallbacks(KEEP_PUMP);
        KEEP_MAIN.post(KEEP_PUMP);
    }

    /** The value the animation is at now, so a new target starts from what is on screen. */
    private static float keepDimNowLocked() {
        if (sKeepAt == 0L) return sKeepDim;
        float t = sKeepDurMs <= 0 ? 1f
                : Math.min(1f, (android.os.SystemClock.uptimeMillis() - sKeepAt)
                / (float) sKeepDurMs);
        float e = t * t * (3f - 2f * t);
        return sKeepFrom + (sKeepTo - sKeepFrom) * e;
    }

    /** For `op keepdimreport`: what was asked for and what the shader actually got. */
    private static String keepDimReport() {
        synchronized (KEEP_LOCK) {
            StringBuilder sb = new StringBuilder("dim=").append(sKeepDim)
                    .append(" from=").append(sKeepFrom).append(" to=").append(sKeepTo)
                    .append(" at=").append(sKeepAt).append(" dur=").append(sKeepDurMs)
                    .append(" msgs=").append(sKeepMsgs).append(" draws=").append(sKeepDraws)
                    .append(" pumping=").append(sKeepPumping).append(" log=");
            int keep = Math.min(sKeepLogN, KEEP_LOG);
            for (int i = 0; i < keep; i++) {
                int k = (sKeepLogN - keep + i) % KEEP_LOG;
                sb.append(sKeepLogAt[k]).append(':').append(sKeepLogV[k]).append(' ');
            }
            return sb.toString();
        }
    }

    /** A redraw of what is uploaded, with no reload - the GPU fade's per-frame request. */
    private static void requestFrame(boolean keepAlive) {
        Object eng = lockEngine();
        if (eng != null && !frameRequest(eng, keepAlive)) reportNoFrameRequest(eng);
    }

    /**
     * Whether an entry's boolean is the one that DEFERS the post-render teardown, read off that
     * build's own bytecode and never guessed.
     *
     * Index-aligned with FRAME_REQUESTS. True only where the dex has been read: T and U hand
     * their argument to the post-render step, where false runs finishRendering() on the spot -
     * the EGL surface and context destroyed after the frame - and true defers it by a second.
     * Z's boolean is a different question entirely (whether preRender re-reads the texture), and
     * the T(Z,I) entry is a shape nobody has met yet, so neither of those is ever given ours.
     */
    private static final boolean[] FRAME_REQ_DEFERS_TEARDOWN = {true, true, false, false};

    /** Tries the known frame requests, the one that answered last time first. */
    private static boolean frameRequest(Object eng, boolean keepAlive) {
        int known = sFrameReq;
        if (known >= 0 && callFrameRequest(eng, known, keepAlive)) return true;
        for (int i = 0; i < FRAME_REQUESTS.length; i++) {
            if (i == known) continue;
            if (callFrameRequest(eng, i, keepAlive)) {
                sFrameReq = i;
                return true;
            }
        }
        return false;
    }

    private static boolean callFrameRequest(Object eng, int entry, boolean keepAlive) {
        Object[] req = FRAME_REQUESTS[entry];
        Object[] args = new Object[req.length - 1];
        System.arraycopy(req, 1, args, 0, args.length);
        // The argument is the OEM's, not ours, everywhere its meaning has not been read out of
        // that build's bytecode. It used to be overwritten with keepAlive unconditionally, which
        // sent `true` where FRAME_REQUESTS deliberately says FALSE, the no-animation value at
        // every one of these entry points.
        //
        // On 7.0.7 that went unnoticed: the frame request there is U(Z), which reaches preRender
        // either way. On 8.0.8-flip the frame request is Z(Z), which posts preRender - V(Z),
        // named by its own "#preRender" trace section - and THAT one branches on the argument.
        // With true it never re-read the texture, so a GPU fade armed on nothing: measured as
        // `gpu fade timed out after 226 frames (armed=false)`, about 1.9s of the cover sitting
        // in the wallpaper process without being drawn, which is what read on the phone as the
        // cover taking seconds to appear and needing a fold or a rotation to show up.
        //
        // Where the boolean IS the teardown flag, though, ours is the right value and sending
        // the OEM's costs the whole animation: a run of frames asked for with false destroys the
        // EGL context after every one of them, and the next frame is a new context, which the
        // engine treats as a new surface and re-uploads everything for. That is a fade at 9-17
        // frames where the same fade with true draws 44-46 - the difference between a crossfade
        // and a slideshow, and what made the frost going on for the lyrics stutter.
        if (keepAlive && entry < FRAME_REQ_DEFERS_TEARDOWN.length
                && FRAME_REQ_DEFERS_TEARDOWN[entry]
                && args.length > 0 && args[0] instanceof Boolean) {
            args[0] = Boolean.TRUE;
        }
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

    // ------------------------------------------------------------------ the live lock wallpaper

    /**
     * A video lock wallpaper, and how the cover gets into its WINDOW.
     *
     * The SystemUI half of the cover is a view over the keyguard; this half is what the clock's
     * liquid glass and the cards' blur sample, and on a video wallpaper that is the wallpaper
     * window FastPlayer plays into. The cover reaches it the way the user's own video does: it is
     * encoded into a short MP4 (CoverVideoEncoder) and the lock engine's path getter answers with
     * that file while the cover is up, and the engine is asked to reload. On the way out the
     * getter answers with the wallpaper's own path again, the engine reloads, and the player is
     * put back where it would have been had it kept playing behind the cover.
     *
     * Two engine shapes, both read off MiWallpaper 7.0.7 (OS4.0.0.40) and both handled here:
     *
     *   plain (wallpaper_effect_type_2 = 0)  KeyguardVideoEngineImpl. onWallpaperUpdate builds a
     *       new VideoPlayer (FastPlayerImpl) and hands it the path getter's answer.
     *   depth (wallpaper_effect_type_2 = 10) KeyguardVideoDepthEngineImpl. The player lives on the
     *       process-wide VideoDepthManager, which caches the path in its one String field. On
     *       this build onWallpaperUpdate re-reads the getter into that field; on OS4.0.0.35 it
     *       did not (see pinVideoPath), so the field is written as well.
     *
     * Everything below reaches the OEM's objects by TYPE - the FastPlayer, the VideoPlayer, the
     * VideoDepthManager, its String field - never by R8 name: the names move between builds and
     * the types do not. FastPlayer's own methods (seekto, pause, getCurrentPosition, setLoop)
     * are a library's public API and keep their names.
     *
     * Only the LOCK engine is ever touched. The desktop has video engines of the same classes in
     * the same process, and every hook here checks that the object it fired on belongs to the
     * captured lock engine before it changes anything.
     */
    private static volatile Object sVideoDepth;
    /** The live lock wallpaper's engine: KeyguardVideoEngineImpl or KeyguardVideoDepthEngineImpl. */
    private static volatile Object sVideoEngine;
    /**
     * The desktop's video engine. It owns the only wallpaper window when the lock screen and the
     * desktop are set to the same video: MIUI then builds no lock engine at all, and the lock
     * screen's glass and cards sample the desktop's window. See targetEngine().
     */
    private static volatile Object sDesktopVideoEngine;
    /** The engine our cover video is on right now; null when it is on none. */
    private static volatile Object sActiveEngine;
    /**
     * Shared video only: the cover was taken off the desktop's window for the desktop, and is
     * owed back to it the next time the lock screen shows.
     */
    private static volatile boolean sSharedSuspended;
    /** Whether the lock screen is up, as the engines are told by show/hideKeyguardWallpaper. */
    private static volatile boolean sKeyguardUp = true;
    /**
     * The two base classes. A subclass constructor runs its super's, so hooking these catches
     * every video engine; the instance's class name is what tells lock from desktop.
     */
    private static final String[] CLS_VIDEO_ENGINE_BASES = {
            "com.miui.miwallpaper.wallpaperservice.impl.VideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.VideoEngineImpl",
    };
    /**
     * The lock subclasses, where the path getter is implemented - it is abstract on the bases,
     * and libxposed refuses to hook an abstract method by throwing.
     */
    private static final String[] CLS_VIDEO_ENGINE_LOCK = {
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardVideoEngineImpl",
    };
    /** Their desktop siblings, for the shared-video case only. */
    private static final String[] CLS_VIDEO_ENGINE_DESKTOP = {
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoDepthEngineImpl",
            "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopVideoEngineImpl",
    };
    private static final String CLS_FASTPLAYER = "com.miui.fastplayer.FastPlayer";
    private static final String CLS_VIDEO_PLAYER = "com.miui.miwallpaper.container.video.VideoPlayer";
    private static final String CLS_FASTPLAYER_IMPL =
            "com.miui.miwallpaper.container.video.FastPlayerImpl";
    private static final String CLS_DEPTH_MANAGER =
            "com.miui.miwallpaper.container.videodepth.VideoDepthManager";

    /** Whether the lock engine is currently playing our cover video. */
    private static volatile boolean sCoverVideoActive;
    private static volatile String sCoverVideoPath;
    /** CRC32 of the pushed JPEG, the cover video's cache key. 0 means "work it out". */
    private static volatile long sCurrentArtChecksum;
    private static final String COVER_VIDEO_FILE = "hyperchanger_hmc_next_cover.mp4";
    private static final String COVER_VIDEO_BLUR_FILE = "hyperchanger_hmc_next_cover_blur.mp4";
    private static java.util.concurrent.ExecutorService sVideoWorker;

    /** The cover video's encoder, built on first use so retirement owns it (Gen.retireOnMain). */
    private static synchronized java.util.concurrent.ExecutorService videoWorker() {
        if (sVideoWorker == null) sVideoWorker = Gen.executor("mc-wall-video");
        return sVideoWorker;
    }
    /**
     * Bumped by every takeover request, in either direction. An encode that finishes after the
     * cover was taken down - or after a newer track superseded it - must not switch the window to
     * a cover nobody asked for any more; the worker compares against this before it does.
     */
    private static final java.util.concurrent.atomic.AtomicInteger sTakeoverGen =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The lock wallpaper's own video file, as the engine's path getter last answered it with the
     * cover off. Where the restore reads the duration from, and where the multi-frame cover video
     * takes its first frame from.
     */
    private static volatile String sLockVideoPath;
    /** Each engine's own video path, as its getter last answered with the cover off. */
    private static final java.util.Map<Object, String> sOwnPaths =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** What the engine last asked its player's loop to be, before any forcing; null unknown. */
    private static volatile Boolean sLockLoops;
    /** Where the user's video was when the cover took the window, and when that was. */
    private static volatile long sSavedVideoPosMs = -1L;
    private static volatile long sSavedVideoAtMs;
    /** Whether the depth manager's path field currently holds our cover path. */
    private static volatile boolean sPathPinned;
    /** The depth manager's path field value from before we pinned it. */
    private static volatile String sPinnedOver;

    /**
     * What the lock wallpaper is now, as SystemUI reads it before every push. null until a push
     * has said.
     */
    private static volatile Boolean sLockIsVideo;

    /**
     * Whether this push is for a live lock wallpaper.
     *
     * SystemUI's answer decides when there is one: the engine is captured in a constructor that
     * runs once, and this process outlives any number of wallpaper changes, so an engine left
     * over from a video that has since been replaced by a still picture must not send a still
     * wallpaper's cover down the video path. The reverse - a live wallpaper this module has no
     * engine for (a linkage or super wallpaper) - still goes down the video path, which tells
     * SystemUI at once that no reload is coming, so its view is not held for one.
     */
    private static boolean videoPath() {
        Boolean live = sLockIsVideo;
        return live != null ? live : sVideoEngine != null;
    }

    /** Told, not guessed: SystemUI carries the answer on every broadcast. */
    private static void noteLockWallpaper(boolean video) {
        Boolean was = sLockIsVideo;
        sLockIsVideo = video;
        if (was != null && was == video) return;
        Xp.log(TAG + "the lock wallpaper is " + (video ? "a live one" : "a still picture")
                + (was == null ? "" : ", it was not"));
        if (!video && sCoverVideoActive) videoWindowTakeover(true);
    }

    /**
     * Whether the cover video itself crossfades into the cover. Off, and told rather than decided
     * here: see Main.sVideoFade. With it off this process builds a one-frame cover video.
     */
    private static volatile boolean sVideoFade;

    /**
     * The other end of the cover video's crossfade, caught by the art path before sArt moves.
     * Null is normal and means there is nothing to walk from.
     */
    private static volatile Bitmap sFadeFrom;

    // ------------------------------------------------------------------ reaching the player

    /** Per class, the first field (walking up the hierarchy) whose declared type is `type`. */
    private static final java.util.Map<String, java.lang.reflect.Field> sTypedFields =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object fieldOfType(Object o, String type) {
        if (o == null) return null;
        String key = o.getClass().getName() + "->" + type;
        java.lang.reflect.Field f = sTypedFields.get(key);
        if (f == null) {
            outer:
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field d : c.getDeclaredFields()) {
                    if (Modifier.isStatic(d.getModifiers())) continue;
                    if (d.getType().getName().equals(type)) {
                        d.setAccessible(true);
                        f = d;
                        break outer;
                    }
                }
            }
            if (f == null) return null;
            sTypedFields.put(key, f);
        }
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDesktopEngine(Object eng) {
        return eng != null && eng.getClass().getName().contains("Desktop");
    }

    private static boolean isDepthEngine(Object eng) {
        return eng != null && eng.getClass().getName().contains("Depth");
    }

    /**
     * The VideoDepthManager behind a depth engine: its own field by type, and failing that the
     * class's static singleton (also by type) - there is one manager per process.
     */
    private static Object videoDepthManager(Object eng) {
        if (!isDepthEngine(eng)) return null;
        Object mgr = fieldOfType(eng, CLS_DEPTH_MANAGER);
        if (mgr == null) {
            try {
                Class<?> vdm = Xp.findClass(CLS_DEPTH_MANAGER, sCl);
                for (java.lang.reflect.Field f : vdm.getDeclaredFields()) {
                    if (f.getType() == vdm && Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        mgr = f.get(null);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (mgr != null) sVideoDepth = mgr;
        return mgr;
    }

    /** The plain engine's VideoPlayer (a FastPlayerImpl, or a MediaPlayerImpl), or null. */
    private static Object lockVideoPlayer(Object eng) {
        if (eng == null || isDepthEngine(eng)) return null;
        return fieldOfType(eng, CLS_VIDEO_PLAYER);
    }

    /**
     * What the lock wallpaper is playing through right now: a native FastPlayer (the depth
     * manager's, or a FastPlayerImpl's), or an android.media.MediaPlayer - the plain engine
     * builds a MediaPlayerImpl instead of a FastPlayerImpl when the effect type is 0 and
     * full-screen AOD is off. Null when there is nothing to reach.
     */
    private static Object lockPlayer(Object eng) {
        if (eng == null) return null;
        Object mgr = videoDepthManager(eng);
        if (mgr != null) return fieldOfType(mgr, CLS_FASTPLAYER);
        Object vp = lockVideoPlayer(eng);
        Object fp = fieldOfType(vp, CLS_FASTPLAYER);
        return fp != null ? fp : fieldOfType(vp, "android.media.MediaPlayer");
    }

    private static boolean isMediaPlayer(Object p) {
        return p instanceof android.media.MediaPlayer;
    }

    private static long playerPositionMs(Object p) {
        if (p == null) return -1L;
        try {
            if (isMediaPlayer(p)) return ((android.media.MediaPlayer) p).getCurrentPosition();
            return ((Number) Xp.callMethod(p, "getCurrentPosition")).longValue();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** FastPlayer's "completed" mode: a play-once video parked on its last frame. */
    private static final int PLAYER_MODE_COMPLETED = 4;

    /**
     * Whether a player has finished its source and is holding the last frame - which is where a
     * one-frame cover video sits a moment after it opens, with its position never leaving zero.
     */
    private static boolean playerCompleted(Object eng, Object p) {
        if (p == null) return false;
        try {
            if (!isMediaPlayer(p)) {
                return ((Number) Xp.callMethod(p, "getPlayerMode")).intValue() == PLAYER_MODE_COMPLETED;
            }
            // MediaPlayerImpl keeps its state in an enum whose constant NAMES survive R8.
            Object vp = lockVideoPlayer(eng);
            if (vp == null) return false;
            for (Class<?> c = vp.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!f.getType().isEnum() || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object st = f.get(vp);
                    if (st != null && st.toString().contains("COMPLETE")) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void playerSeek(Object p, long ms) {
        if (p == null) return;
        try {
            if (isMediaPlayer(p)) {
                ((android.media.MediaPlayer) p).seekTo(ms, android.media.MediaPlayer.SEEK_CLOSEST);
            } else {
                Xp.callMethod(p, "seekto", 0.0f, ms, 0);
            }
        } catch (Throwable t) {
            Xp.w(TAG + "seek failed: " + t);
        }
    }

    private static void playerPause(Object p) {
        if (p == null) return;
        try {
            if (isMediaPlayer(p)) ((android.media.MediaPlayer) p).pause();
            else Xp.callMethod(p, "pause");
        } catch (Throwable t) {
            Xp.w(TAG + "pause failed: " + t);
        }
    }

    private static String describePlayer(Object eng, Object p) {
        if (p == null) return "none";
        return (isMediaPlayer(p) ? "MediaPlayer" : "FastPlayer") + " pos "
                + playerPositionMs(p) + "ms" + (playerCompleted(eng, p) ? " completed" : "");
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The EngineService calls every video engine receives in the course of being attached and
     * used - the lock screen showing, hiding, being swiped, a wallpaper update. Their names are
     * the interface's and survive R8.
     */
    private static final java.util.Set<String> ENGINE_LIFECYCLE = new java.util.HashSet<>(
            java.util.Arrays.asList("onWallpaperUpdate", "showKeyguardWallpaper",
                    "hideKeyguardWallpaper", "updateKeyguardWallpaperRatio",
                    "updateVideoWallpaperFrame", "controlVideoDepth", "setLockscreenVideoSurface"));

    private static void hookVideoEngines() {
        // Captured from what the engine is CALLED with, not from its constructor. The
        // constructor hook never fired on MiWallpaper 7.0.7: the app is compiled ahead of time
        // and ART inlines a constructor that small into its caller, so a hook on it is never
        // reached - measured, a video lock wallpaper set with the module loaded and no
        // "captured" line at all. The attach path (the one method taking the UniversalEngine,
        // and the ones taking the SurfaceHolder) and the lifecycle calls are large, interface
        // dispatched and not inlined; any of them names the engine the moment it is in use.
        java.util.List<String> classes = new java.util.ArrayList<>();
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_BASES);
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_LOCK);
        java.util.Collections.addAll(classes, CLS_VIDEO_ENGINE_DESKTOP);
        for (String cn : classes) {
            try {
                Class<?> vd = Xp.findClass(cn, sCl);
                int n = 0;
                for (Method m : vd.getDeclaredMethods()) {
                    int mod = m.getModifiers();
                    if (Modifier.isAbstract(mod) || Modifier.isStatic(mod) || Modifier.isNative(mod)) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    boolean attach = ps.length == 1
                            && (ps[0] == android.view.SurfaceHolder.class
                            || ps[0].getName().endsWith("UniversalWallpaper$UniversalEngine"));
                    if (!attach && !ENGINE_LIFECYCLE.contains(m.getName())) continue;
                    final boolean keyguardCall = m.getParameterCount() == 2
                            && (m.getName().equals("showKeyguardWallpaper")
                            || m.getName().equals("hideKeyguardWallpaper"));
                    final boolean showing = m.getName().equals("showKeyguardWallpaper");
                    Xp.hook("wall.video.engine_lifecycle", m, chain -> {
                        Object self = chain.getThisObject();
                        noteVideoEngine(self);
                        // Every engine is told when the lock screen shows and hides - the
                        // controller dispatches it to all of them - so this also fires once per
                        // engine; onKeyguardSeen() acts on the change only.
                        if (keyguardCall) onKeyguardSeen(showing);
                        return chain.proceed();
                    });
                    n++;
                }
                Xp.hookAllConstructors("wall.video.engine_ctor", vd, chain -> {
                    Object result = chain.proceed();
                    noteVideoEngine(chain.getThisObject());
                    return result;
                });
                Xp.log(TAG + "video engine hooked on " + cn.substring(cn.lastIndexOf('.') + 1)
                        + " (" + n + " calls)");
            } catch (Throwable t) {
                Xp.w(TAG + "video engine hook failed on " + cn + ": " + t);
            }
        }
        // And from the framework's side: every wallpaper Engine is told its visibility by
        // WallpaperService over binder, which nothing can inline, and the UniversalEngine holds
        // the EngineService it delegates to in a field of that type.
        try {
            Class<?> ue = Xp.findClass(
                    "com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine", sCl);
            Xp.hookAll("wall.video.visibility", ue, "onVisibilityChanged", chain -> {
                Object r = chain.proceed();
                noteVideoEngine(fieldOfType(chain.getThisObject(),
                        "com.miui.miwallpaper.wallpaperservice.service.EngineService"));
                return r;
            });
            Xp.log(TAG + "UniversalEngine.onVisibilityChanged hooked");
        } catch (Throwable t) {
            Xp.w(TAG + "UniversalEngine.onVisibilityChanged hook failed: " + t);
        }
        java.util.List<String> getters = new java.util.ArrayList<>();
        java.util.Collections.addAll(getters, CLS_VIDEO_ENGINE_LOCK);
        java.util.Collections.addAll(getters, CLS_VIDEO_ENGINE_DESKTOP);
        for (String cn : getters) {
            try {
                hookVideoPathGetter(Xp.findClass(cn, sCl));
            } catch (Throwable t) {
                Xp.w(TAG + "video path getter hook failed on " + cn + ": " + t);
            }
        }
        hookPlayerDataSource();
        hookPlayerLoop();
    }

    /**
     * A video engine has been seen in use: a Keyguard one is the lock wallpaper's, a Desktop one
     * the desktop's. Whichever of them turns out to be the one the lock screen shows (see
     * targetEngine()) gets a waiting cover straight away - this process restarted, or the
     * wallpaper was changed, with the cover up - rather than at the next track.
     */
    private static void noteVideoEngine(Object self) {
        if (self == null || self == sVideoEngine || self == sDesktopVideoEngine) return;
        String n = self.getClass().getName();
        // UniversalEngine holds its EngineService whatever kind it is, so onVisibilityChanged
        // reaches here with the still-image engines too. Only a video engine is one: a still
        // lock wallpaper runs KeyguardImageEngineImpl, and taking it for a video engine made a
        // freshly started process answer videoPath() with true until the first push said
        // otherwise - which refused its hot reload as "the lock wallpaper is a live video".
        if (!n.contains("Video")) return;
        if (n.contains("Keyguard")) {
            sVideoEngine = self;
        } else if (n.contains("Desktop")) {
            sDesktopVideoEngine = self;
        } else {
            return;
        }
        Xp.log(TAG + "video engine captured: " + n);
        Gen.post(() -> {
            if (sActiveEngine == null && targetEngine() == self && videoPath() && sArt != null) {
                Xp.log(TAG + "a cover is waiting, putting it on " + self.getClass().getSimpleName());
                videoWindowTakeover(false);
            }
        });
    }

    /**
     * Which engine's window the lock screen shows: the lock engine's, unless the lock screen and
     * the desktop are set to the same video, in which case MIUI builds no lock engine and it is
     * the desktop's. Null when the one it would be has not been seen yet.
     */
    private static Object targetEngine() {
        return sharedVideo() ? sDesktopVideoEngine : sVideoEngine;
    }

    /** The controller's path method, String (int which, boolean preview) - found by shape. */
    private static volatile Method sPathOf;

    private static String controllerPath(Object eng, int which) {
        try {
            Object ctrl = fieldOfType(eng, "com.miui.miwallpaper.manager.WallpaperServiceController");
            if (ctrl == null) return null;
            Method m = sPathOf;
            if (m == null) {
                for (Method c : ctrl.getClass().getDeclaredMethods()) {
                    Class<?>[] ps = c.getParameterTypes();
                    if (c.getReturnType() == String.class && ps.length == 2
                            && ps[0] == int.class && ps[1] == boolean.class) {
                        if (m != null) return null;
                        m = c;
                    }
                }
                if (m == null) return null;
                m.setAccessible(true);
                sPathOf = m;
            }
            return (String) m.invoke(ctrl, which, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile String sSharedKey;
    private static volatile boolean sSharedAnswer;

    /**
     * Whether the lock screen and the desktop play the same video. MIUI copies the video to a lock
     * file and a home file, so it is the two files' CONTENT that is compared - length, and 64KB
     * from each end - asked of the controller the engines themselves ask. Cached on the paths,
     * lengths and times, so a changed wallpaper is noticed.
     */
    private static boolean sharedVideo() {
        Object desk = sDesktopVideoEngine;
        if (desk == null || !Boolean.TRUE.equals(sLockIsVideo)) return false;
        String lock = controllerPath(desk, 2), home = controllerPath(desk, 1);
        if (lock == null || home == null) return false;
        File a = new File(lock), b = new File(home);
        String key = lock + a.length() + a.lastModified() + home + b.length() + b.lastModified();
        if (key.equals(sSharedKey)) return sSharedAnswer;
        boolean same = a.isFile() && b.isFile() && a.length() == b.length()
                && (lock.equals(home) || sameEnds(a, b));
        sSharedKey = key;
        sSharedAnswer = same;
        Xp.log(TAG + "lock and desktop video are " + (same ? "the SAME" : "different")
                + " (" + lock + ", " + home + ")");
        return same;
    }

    private static boolean sameEnds(File a, File b) {
        try (java.io.RandomAccessFile ra = new java.io.RandomAccessFile(a, "r");
             java.io.RandomAccessFile rb = new java.io.RandomAccessFile(b, "r")) {
            int n = (int) Math.min(65536L, ra.length());
            byte[] x = new byte[n], y = new byte[n];
            for (long at : new long[]{0L, Math.max(0L, ra.length() - n)}) {
                ra.seek(at);
                rb.seek(at);
                ra.readFully(x);
                rb.readFully(y);
                if (!java.util.Arrays.equals(x, y)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the lock screen is up, for the shared-video case. The engines' own show/hide calls
     * only arrive with the screen ON, so a cover pushed as the phone went to sleep - the usual
     * way into cover mode - found the lock screen "not up" and waited for the wake, and the
     * glass and cards changed a second after it. The keyguard being locked is the same answer
     * without the wait, and it is adopted: the hide that comes at the unlock is then a change
     * onKeyguardSeen() acts on, which is what takes the cover off the desktop again.
     */
    private static boolean lockScreenUp() {
        if (sKeyguardUp) return true;
        try {
            android.app.KeyguardManager km = sCtx == null ? null
                    : sCtx.getSystemService(android.app.KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                sKeyguardUp = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * The lock screen showed or hid. Only the shared-video case acts on it: there the cover sits
     * in the DESKTOP's window, so it is taken off as the lock screen goes - the desktop comes back
     * playing its own video - and put back as the lock screen returns.
     */
    private static void onKeyguardSeen(boolean showing) {
        if (sKeyguardUp == showing) return;
        sKeyguardUp = showing;
        Gen.post(() -> {
            if (!showing) {
                Object active = sActiveEngine;
                if (sCoverVideoActive && active != null && active == sDesktopVideoEngine) {
                    Xp.log(TAG + "lock screen gone: the desktop gets its own video back");
                    sSharedSuspended = true;
                    giveBack(sTakeoverGen.incrementAndGet(), false);
                }
            } else if (sSharedSuspended) {
                sSharedSuspended = false;
                if (videoPath() && sArt != null && sActiveEngine == null) {
                    Xp.log(TAG + "lock screen back: the cover goes back on the desktop's window");
                    videoWindowTakeover(false);
                }
            }
        });
    }

    /**
     * The lock engine's path getter - the one the engine asks whenever it (re)builds its player.
     * Found by shape: the only non-static, no-argument String method the lock subclass declares
     * (N() on this build). While the cover is up it answers with the cover video; otherwise its
     * real answer is remembered as the wallpaper's own path.
     */
    private static void hookVideoPathGetter(Class<?> cls) {
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) {
                continue;
            }
            if (m.getParameterCount() != 0 || m.getReturnType() != String.class
                    || "toString".equals(m.getName())) {
                continue;
            }
            Xp.hook("wall.video.data_source_get", m, chain -> {
                Object self = chain.getThisObject();
                String cover = sCoverVideoPath;
                if (self != null && self == sActiveEngine && sCoverVideoActive && cover != null) {
                    return cover;
                }
                Object r = chain.proceed();
                if (self != null && r instanceof String && !((String) r).isEmpty()
                        && !r.equals(cover)) {
                    sOwnPaths.put(self, (String) r);
                }
                return r;
            });
            n++;
            Xp.log(TAG + "video path getter hooked: " + cls.getSimpleName() + "." + m.getName());
        }
        if (n == 0) Xp.log(TAG + "no path getter found on " + cls.getSimpleName());
    }

    /**
     * The plain player's data-source setter, as a backstop behind the getter: whatever re-opens
     * the lock player on the wallpaper's own file while the cover is up is pointed at the cover
     * instead. The setter is whichever void(String) method the VideoPlayer interface declares
     * (n() on this build), and the redirect only ever applies to the lock engine's own player.
     */
    private static void hookPlayerDataSource() {
        try {
            Class<?> vp = Xp.findClass(CLS_VIDEO_PLAYER, sCl);
            String setter = null;
            for (Method m : vp.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class && m.getReturnType() == void.class) {
                    if (setter != null) {
                        Xp.log(TAG + "VideoPlayer declares two void(String) methods - not guessing");
                        return;
                    }
                    setter = m.getName();
                }
            }
            if (setter == null) {
                Xp.log(TAG + "VideoPlayer has no data-source setter");
                return;
            }
            for (Class<?> impl : new Class<?>[]{
                    Xp.findClass(CLS_FASTPLAYER_IMPL, sCl),
                    Xp.findClass("com.miui.miwallpaper.container.video.MediaPlayerImpl", sCl)}) {
                Method m = impl.getDeclaredMethod(setter, String.class);
                Xp.hook("wall.video.data_source_set", m, chain -> {
                    Object self = chain.getThisObject();
                    String cover = sCoverVideoPath;
                    if (!sCoverVideoActive || cover == null
                            || self != lockVideoPlayer(sActiveEngine)) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray();
                    if (cover.equals(args[0])) return chain.proceed();
                    Xp.log(TAG + "lock player data source redirected to the cover (was "
                            + describe(args[0]) + ")");
                    args[0] = cover;
                    return chain.proceed(args);
                });
                Xp.log(TAG + "lock player data source hooked: " + impl.getSimpleName() + "." + setter);
            }
        } catch (Throwable t) {
            Xp.w(TAG + "lock player data source hook failed: " + t);
        }
    }

    /**
     * FastPlayer.setLoop, for the lock player only. A looping one-frame cover video is a decoder
     * restarting every 33ms for as long as the cover is up, so while the cover plays it is told
     * not to loop, and it parks on its only frame. The engine's own answer is remembered when the
     * cover is off: it is what says whether the user's video loops or plays once, which the
     * restore needs.
     */
    private static void hookPlayerLoop() {
        hookPlayerStart();
        try {
            Xp.hookAll("wall.video.fast_loop", Xp.findClass(CLS_FASTPLAYER, sCl), "setLoop", LOOP_HOOKER);
            Xp.log(TAG + "FastPlayer.setLoop hooked (lock player only)");
        } catch (Throwable t) {
            Xp.w(TAG + "FastPlayer.setLoop hook failed: " + t);
        }
        try {
            Xp.hookAll("wall.video.media_looping", android.media.MediaPlayer.class, "setLooping", LOOP_HOOKER);
            Xp.log(TAG + "MediaPlayer.setLooping hooked (lock player only)");
        } catch (Throwable t) {
            Xp.w(TAG + "MediaPlayer.setLooping hook failed: " + t);
        }
    }

    private static final XposedInterface.Hooker LOOP_HOOKER = chain -> {
        Object self = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        if (args.length == 0 || !(args[0] instanceof Boolean)) return chain.proceed();
        Object active = sActiveEngine;
        if (sCoverVideoActive && active != null && self == lockPlayer(active)) {
            args[0] = Boolean.FALSE;
            return chain.proceed(args);
        }
        Object target = targetEngine();
        if (target != null && self == lockPlayer(target)) sLockLoops = (Boolean) args[0];
        return chain.proceed();
    };

    // ------------------------------------------------------------------ the depth path field

    /**
     * The one String field on the VideoDepthManager: the path its player is opened with. Found by
     * TYPE; refuses rather than guesses if a build ever has two.
     */
    private static java.lang.reflect.Field videoPathField(Object mgr) {
        java.lang.reflect.Field found = null;
        for (java.lang.reflect.Field f : mgr.getClass().getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
            if (found != null) {
                Xp.log(TAG + "vgl: " + mgr.getClass().getSimpleName() + " carries more than one "
                        + "String field (" + found.getName() + ", " + f.getName()
                        + ") - not guessing which is the path");
                return null;
            }
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static String readPathField(Object mgr) {
        java.lang.reflect.Field f = mgr == null ? null : videoPathField(mgr);
        if (f == null) return null;
        try {
            return (String) f.get(mgr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writePathField(Object mgr, String path) {
        java.lang.reflect.Field f = videoPathField(mgr);
        if (f == null) return;
        try {
            f.set(mgr, path);
        } catch (Throwable t) {
            Xp.log(TAG + "vgl: cannot write the depth path field: " + t);
        }
    }

    /**
     * Writes the cover path into the depth manager's cached path. On OS4.0.0.35 the reload did
     * NOT re-read the path getter - it reopened the player on this field, which only the engine's
     * initial setup writes - so hooking the getter alone left the wallpaper's own video playing.
     * On this build the reload re-reads the getter and this is redundant; it stays for the
     * builds where it is not.
     */
    private static void pinVideoPath(Object eng) {
        Object mgr = videoDepthManager(eng);
        String cover = sCoverVideoPath;
        if (mgr == null || cover == null) return;
        if (!sPathPinned) {
            String was = readPathField(mgr);
            if (was != null && !was.equals(cover)) sPinnedOver = was;
            if (sLockVideoPath == null && was != null && !was.equals(cover)) sLockVideoPath = was;
        }
        writePathField(mgr, cover);
        sPathPinned = true;
    }

    /** Puts the wallpaper's own path back into the depth manager before the reload out. */
    private static void unpinVideoPath(Object eng) {
        if (!sPathPinned) return;
        sPathPinned = false;
        Object mgr = videoDepthManager(eng);
        String original = sLockVideoPath != null ? sLockVideoPath : sPinnedOver;
        sPinnedOver = null;
        if (mgr != null && original != null) writePathField(mgr, original);
    }

    // ------------------------------------------------------------------ taking the window

    /**
     * Puts the cover into the lock wallpaper's window (on = false), or gives the window back to
     * the wallpaper's own video (on = true). Every call ends in exactly one "videoreload" to
     * SystemUI - sent once the window really shows the result, or at once when nothing is going
     * to change - because SystemUI holds its own half of the transition until it hears it.
     */
    private static boolean videoWindowTakeover(boolean on) {
        final int gen = sTakeoverGen.incrementAndGet();
        if (on) {
            sFadeFrom = null;
            // Cover mode is over, so nothing is owed to the desktop at the next lock either.
            sSharedSuspended = false;
            if (!sCoverVideoActive) {
                // Taken down before the cover ever reached the window (an encode still running,
                // which the bumped generation now drops): the user's video never stopped, so the
                // position saved for it is stale by the next entry.
                sSavedVideoPosMs = -1L;
                tellSystemUi("videoreload", "no cover video was active");
                return true;
            }
            giveBack(gen, true);
            return true;
        }

        final Object eng = targetEngine();
        if (eng == null) {
            Xp.log(TAG + "videoWindowTakeover: no video engine for the lock screen yet");
            tellSystemUi("videoreload", "no video engine to reload");
            return false;
        }
        if (takeoverBroken(eng)) {
            Xp.log(TAG + "videoWindowTakeover: " + eng.getClass().getSimpleName() + " crashed on a"
                    + " cover video before, its window is left to its own video");
            tellSystemUi("videoreload", "this engine crashed on a cover video before");
            return false;
        }
        if (isDesktopEngine(eng) && !lockScreenUp()) {
            // Shared video, and the desktop is what is on screen: the cover would be on the
            // desktop. Owed to the next lock screen instead - see onKeyguardSeen().
            sSharedSuspended = true;
            Xp.log(TAG + "videoWindowTakeover: the desktop is showing, the cover waits for the lock");
            tellSystemUi("videoreload", "the desktop is showing, the cover waits for the lock");
            return true;
        }
        if (sCoverVideoActive && sActiveEngine != null && sActiveEngine != eng) {
            // The lock screen moved to another engine under the cover (the wallpaper was
            // changed between shared and separate): hand the old one its video first.
            giveBack(gen, false);
        }

        final Bitmap art = sArt;
        final Context ctx = sCtx;
        if (art == null || ctx == null) {
            Xp.log(TAG + "videoWindowTakeover: nothing to build a cover video from (art="
                    + describe(art) + ")");
            tellSystemUi("videoreload", "no art to build a cover video from");
            return false;
        }
        if (!sCoverVideoActive && sSavedVideoPosMs < 0) {
            // Where the user's video is, read before anything replaces it. The restore resumes
            // from here plus however long the cover was up.
            sSavedVideoPosMs = Math.max(0L, playerPositionMs(lockPlayer(eng)));
            sLockVideoPath = sOwnPaths.get(eng);
            sSavedVideoAtMs = SystemClock.uptimeMillis();
            Xp.log(TAG + "videoWindowTakeover: the wallpaper's video was at "
                    + sSavedVideoPosMs + "ms");
        }
        final boolean lyricBlur = sLyricBlur;
        final long checksum = sCurrentArtChecksum != 0
                ? (lyricBlur ? sCurrentArtChecksum ^ 0x55aa55aa55aa55aaL : sCurrentArtChecksum)
                : 0;
        // Caught here rather than in the worker: the next art push would move them.
        final Bitmap fadeFrom = sFadeFrom;
        final long fadeMs = sFadeMs;
        final String lockPath = sLockVideoPath;
        final long savedPos = sSavedVideoPosMs;

        videoWorker().submit(() -> {
            try {
                File videoFile = new File(ctx.getFilesDir(),
                        lyricBlur ? COVER_VIDEO_BLUR_FILE : COVER_VIDEO_FILE);
                int w = sReportedW > 0 ? sReportedW : ctx.getResources().getDisplayMetrics().widthPixels;
                int h = sReportedH > 0 ? sReportedH : ctx.getResources().getDisplayMetrics().heightPixels;
                int ew = (w / 2) * 2, eh = (h / 2) * 2;
                // A cover already encoded for this very art is only a reload. Asked before the
                // crop and the frosting, which are what a cache hit used to spend 170ms on before
                // the encoder said it had the file all along. Only a one-frame cover: a
                // crossfade's file is keyed on its other end too, which needs the pictures.
                boolean fadeWanted = sVideoFade;
                if (!fadeWanted && CoverVideoEncoder.isCached(videoFile, checksum, isDepthEngine(eng))) {
                    Xp.log(TAG + "videoWindowTakeover: " + videoFile.getName() + " is already this cover");
                    putCoverVideo(eng, gen, videoFile.getAbsolutePath(), lyricBlur);
                    return;
                }
                // centerCrop always allocates, so this end is ours to give back.
                Bitmap to = centerCrop(art, ew, eh);
                if (lyricBlur) {
                    Bitmap blurred = CoverCompose.frosted(to);
                    if (blurred != to) {
                        to.recycle();
                        to = blurred;
                    }
                }
                Bitmap from = null;
                boolean ownFrom = false;
                try {
                    if (sVideoFade) {
                        if (fadeFrom != null && !fadeFrom.isRecycled()) {
                            from = centerCrop(fadeFrom, ew, eh);
                        } else {
                            from = wallpaperFrame(lockPath, savedPos, ew, eh);
                        }
                        ownFrom = from != null;
                    }
                    boolean ok = CoverVideoEncoder.encodeToMp4(from, to, videoFile, checksum,
                            from == null ? 0L : fadeMs, isDepthEngine(eng));
                    if (gen != sTakeoverGen.get()) {
                        // Taken down, or superseded by a newer track, while this encoded. The
                        // newer request has said - or will say - its own word to SystemUI.
                        Xp.log(TAG + "videoWindowTakeover: superseded while encoding, dropped");
                        return;
                    }
                    if (!ok || !videoFile.exists() || videoFile.length() == 0) {
                        Xp.w(TAG + "videoWindowTakeover: cover video encoding failed");
                        tellSystemUi("videoreload", "the cover video could not be encoded");
                        return;
                    }
                    putCoverVideo(eng, gen, videoFile.getAbsolutePath(), lyricBlur);
                } finally {
                    to.recycle();
                    if (ownFrom && from != null) from.recycle();
                }
            } catch (Throwable t) {
                Xp.log(TAG + "videoWindowTakeover: worker error: " + Log.getStackTraceString(t));
                if (gen == sTakeoverGen.get()) {
                    tellSystemUi("videoreload", "the cover video worker failed");
                }
            }
        });
        return true;
    }

    /** The encoded cover goes into the engine's window. From the video worker. */
    private static void putCoverVideo(final Object eng, final int gen, final String path,
                                      final boolean lyricBlur) {
        Gen.post(() -> {
            if (gen != sTakeoverGen.get()) return;
            if (isDesktopEngine(eng) && !lockScreenUp()) {
                // Unlocked while this encoded, on a shared video.
                sSharedSuspended = true;
                tellSystemUi("videoreload", "the desktop is showing, the cover waits");
                return;
            }
            sCoverVideoPath = path;
            sCoverVideoActive = true;
            sActiveEngine = eng;
            pinVideoPath(eng);
            markTakeover(eng);
            Xp.log(TAG + "videoWindowTakeover: cover video (" + (lyricBlur ? "frosted" : "sharp")
                    + ", " + new File(path).length() + "B) into "
                    + eng.getClass().getSimpleName());
            // Ahead of the reload, not after it: see CoverPush.noteVideoReloading().
            tellSystemUi("videoreloading", "the cover is going onto the window");
            reloadThen(eng, gen, fp -> {
                tellSystemUi("videoreload", "the cover is on the window");
                // Alive a few seconds past the first frame: this engine plays a cover.
                Gen.postDelayed(WallpaperProbe::clearTakeoverMark, TAKEOVER_SURVIVE_MS);
            });
        });
    }

    /**
     * Takes the cover off the engine it is on and gives that engine's window back to its own
     * video, resumed where it would have been. `tell` is whether SystemUI is waiting on this -
     * it is when cover mode ends; it is not when a shared video's cover is only being taken off
     * the desktop for the length of an unlock.
     */
    private static void giveBack(final int gen, final boolean tell) {
        final Object eng = sActiveEngine;
        sCoverVideoActive = false;
        sCoverVideoPath = null;
        sActiveEngine = null;
        if (eng == null) {
            if (tell) tellSystemUi("videoreload", "no cover video was active");
            return;
        }
        unpinVideoPath(eng);
        Xp.log(TAG + "giving " + eng.getClass().getSimpleName() + " its own video back");
        final long savedPos = sSavedVideoPosMs;
        final long savedAt = sSavedVideoAtMs;
        final String path = sLockVideoPath;
        sSavedVideoPosMs = -1L;
        sPreSeeked = -1L;
        if (savedPos >= 0) {
            sPreSeekEng = eng;
            sPreSeekPath = path;
            sPreSeekAt = savedAt;
            sPreSeekUntil = SystemClock.uptimeMillis() + FIRST_FRAME_TIMEOUT_MS;
            sPreSeekPos = savedPos;
        }
        reloadThen(eng, gen, fp -> restorePosition(eng, gen, path, savedPos, savedAt, tell));
    }

    // ------------------------------------------------------------------ the crash guard

    /**
     * Written just before a reload that puts a cover video on an engine, removed once the process
     * has lived TAKEOVER_SURVIVE_MS past its first frame. Found at the next start, it means the
     * process died with a cover video in the player - and since this process restores its cover
     * on start and puts it straight back, that is a crash loop: measured on OS4.0.0.40, where the
     * depth engine's native decoder crashed on every reload, the process restarted every two
     * seconds until the system stopped binding the wallpaper at all. The engine class is then
     * recorded against this module build and never given a cover video again by it.
     */
    private static final String TAKEOVER_MARK = "hyperchanger_hmc_next_takeover_pending";
    private static final String TAKEOVER_BROKEN = "hyperchanger_hmc_next_takeover_broken";
    private static final long TAKEOVER_SURVIVE_MS = 3000L;
    /** "engine class|module build" entries that crashed; see checkTakeoverCrash(). */
    private static final java.util.Set<String> sBrokenTakeovers =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** This module build, so a fixed build gets to try again. */
    private static String moduleStamp() {
        try {
            android.content.pm.ApplicationInfo ai = Xp.api().getModuleApplicationInfo();
            return String.valueOf(new File(ai.sourceDir).lastModified());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String takeoverKey(Object eng) {
        return eng.getClass().getName() + "|" + moduleStamp();
    }

    private static void checkTakeoverCrash(Context ctx) {
        try {
            File broken = new File(ctx.getFilesDir(), TAKEOVER_BROKEN);
            File mark = new File(ctx.getFilesDir(), TAKEOVER_MARK);
            if (mark.exists()) {
                String key = new String(readBytes(mark.getAbsolutePath()), "UTF-8").trim();
                mark.delete();
                java.io.FileOutputStream out = new java.io.FileOutputStream(broken, true);
                out.write((key + "\n").getBytes("UTF-8"));
                out.close();
                Xp.log(TAG + "the last process died putting a cover video on " + key
                        + " - that engine is not given one again by this build");
            }
            if (broken.exists()) {
                for (String line : new String(readBytes(broken.getAbsolutePath()), "UTF-8").split("\n")) {
                    if (!line.trim().isEmpty()) sBrokenTakeovers.add(line.trim());
                }
            }
        } catch (Throwable t) {
            Xp.w(TAG + "crash guard check failed: " + t);
        }
    }

    private static boolean takeoverBroken(Object eng) {
        return sBrokenTakeovers.contains(takeoverKey(eng));
    }

    private static void markTakeover(Object eng) {
        Context c = sCtx;
        if (c == null) return;
        try {
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(c.getFilesDir(), TAKEOVER_MARK));
            out.write(takeoverKey(eng).getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            Xp.w(TAG + "crash guard mark failed: " + t);
        }
    }

    private static void clearTakeoverMark() {
        Context c = sCtx;
        if (c != null) new File(c.getFilesDir(), TAKEOVER_MARK).delete();
    }

    /** What to do with the rebuilt player once its first frame is on the window. */
    private interface AfterReload {
        void run(Object fastPlayer);
    }

    /** How long the first-frame wait may run before carrying on anyway. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 1500L;
    private static final long FIRST_FRAME_POLL_MS = 16L;

    /**
     * Asks the lock engine to rebuild its player - which re-reads the path getter - and runs
     * `then` once the rebuilt player has put a frame on the window: its position leaves zero, or
     * it reports the completed mode a one-frame video parks in. The rebuild replaces the player
     * object, so the player is looked up afresh on every poll. A newer takeover abandons the wait.
     */
    private static void reloadThen(final Object eng, final int gen, final AfterReload then) {
        Gen.post(() -> {
            if (gen != sTakeoverGen.get()) return;
            final Object before = lockPlayer(eng);
            try {
                Xp.callMethod(eng, "onWallpaperUpdate", "video", isDesktopEngine(eng) ? 1 : 2);
            } catch (Throwable t) {
                Xp.w(TAG + "reload failed: " + t);
                tellSystemUi("videoreload", "the reload failed");
                return;
            }
            final long startedAt = SystemClock.uptimeMillis();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (gen != sTakeoverGen.get()) return;
                Object fp = lockPlayer(eng);
                long pos = playerPositionMs(fp);
                long waited = SystemClock.uptimeMillis() - startedAt;
                // A FastPlayer is replaced by the rebuild, so the old one - still reporting where
                // the previous source was - must not count. A MediaPlayer is reset in place
                // instead, which already puts its position back to zero.
                boolean fresh = fp != null && (isMediaPlayer(fp) || fp != before);
                if (fresh && (pos > 0 || playerCompleted(eng, fp))) {
                    Xp.log(TAG + "first frame after " + waited + "ms ("
                            + describePlayer(eng, fp) + ")");
                    then.run(fp);
                    return;
                }
                if (waited >= FIRST_FRAME_TIMEOUT_MS) {
                    Xp.log(TAG + "first frame wait timed out (player "
                            + (fp == null ? "none" : fresh ? "rebuilt" : "NOT rebuilt") + ", "
                            + describePlayer(eng, fp) + ")");
                    then.run(fp);
                    return;
                }
                Gen.postDelayed(poll[0], FIRST_FRAME_POLL_MS);
            };
            Gen.postDelayed(poll[0], FIRST_FRAME_POLL_MS);
            Xp.log(TAG + "reload asked of " + eng.getClass().getSimpleName());
        });
    }

    /** Within this distance of the end, a play-once wallpaper counts as finished. */
    private static final long END_ZONE_MS = 150L;
    /** Where a finished wallpaper parks: inside its final frame. */
    private static final long PARK_FROM_END_MS = 40L;
    /** How far below the target a read-back may be before a seek counts as lost. */
    private static final long SEEK_TOLERANCE_MS = 400L;
    private static final long SEEK_VERIFY_MS = 250L;

    /**
     * Puts the rebuilt player back where the user's video would have been had it kept playing
     * behind the cover - the rebuild itself always starts from zero - and only then tells
     * SystemUI, whose cover view is still up over the window until it hears it. A looping video
     * wraps; a play-once video that would have finished parks on its last frame instead of
     * replaying its ending.
     */
    private static void restorePosition(final Object eng, final int gen, final String path,
                                        final long savedPos, final long savedAt,
                                        final boolean tell) {
        long dur = videoDurationMs(path);
        long[] t = restoreTarget(path, savedPos, savedAt);
        long target = t[0];
        boolean park = t[1] != 0L;
        Xp.log(TAG + "restore: saved " + savedPos + "ms, target " + target + "ms of " + dur
                + "ms, loops=" + sLockLoops + (park ? ", parking on the last frame" : ""));
        if (target <= 100L && !park) {
            if (tell) tellSystemUi("videoreload", "the wallpaper's video is back");
            return;
        }
        // Normally already done: the seek went in before the rebuilt player's start (see
        // hookPlayerStart), so its first frame is the target and there is nothing to land. Only
        // a read-back that says otherwise falls through to seeking the running player.
        long pre = sPreSeeked;
        sPreSeeked = -1L;
        if (pre > 0L) {
            long at = playerPositionMs(lockPlayer(eng));
            if (park ? at >= pre - SEEK_TOLERANCE_MS - 500L : at >= target - SEEK_TOLERANCE_MS) {
                Xp.log(TAG + "restore: seeked to " + pre + "ms before the first frame, at " + at + "ms");
                if (tell) tellSystemUi("videoreload", "the wallpaper's video is back at " + at + "ms");
                return;
            }
            Xp.log(TAG + "restore: the seek before start did not hold (at " + at + "ms)");
        }
        final long want = target;
        final boolean parkIt = park;
        // SystemUI is told now, with the first seek, not after it has been read back: the
        // cover view over the window starts its fade-out on this word, and its first frames are
        // still nearly opaque while the seek lands. Waiting for the read-back held the cover up
        // a further quarter to three quarters of a second on every exit.
        final boolean[] told = {!tell};
        final int[] tries = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (gen != sTakeoverGen.get()) return;
            playerSeek(lockPlayer(eng), want);
            if (!told[0]) {
                told[0] = true;
                tellSystemUi("videoreload", "the wallpaper's video is back, seeking to " + want + "ms");
            }
            Gen.postDelayed(() -> {
                if (gen != sTakeoverGen.get()) return;
                Object now = lockPlayer(eng);
                long at = playerPositionMs(now);
                // A park is not read back: a finished play-once player reports where its last
                // decoded frame sits, not the seek target (5200ms against 5960ms, measured), so
                // the read-back never agreed and three more seeks held the SystemUI cover up for
                // three quarters of a second longer on every exit.
                if (!parkIt && at < want - SEEK_TOLERANCE_MS && ++tries[0] < 3) {
                    Gen.post(step[0]);
                    return;
                }
                if (parkIt) playerPause(now);
                Xp.log(TAG + "restore: at " + at + "ms after " + (tries[0] + 1) + " seek(s)");
                Xp.log(TAG + "restore: settled at " + at + "ms");
            }, SEEK_VERIFY_MS);
        };
        Gen.post(step[0]);
    }

    /**
     * Where the user's video should resume: {target ms, 1 when it parks on its last frame}. A
     * looping video wraps; a play-once video that would have finished parks instead of
     * replaying its ending.
     */
    private static long[] restoreTarget(String path, long savedPos, long savedAt) {
        long dur = videoDurationMs(path);
        long target = savedPos < 0 ? 0L : savedPos + (SystemClock.uptimeMillis() - savedAt);
        long park = 0L;
        if (dur > 0) {
            Boolean loops = sLockLoops;
            if (loops != null && loops) {
                target %= dur;
            } else if (target >= dur - END_ZONE_MS) {
                target = Math.max(1L, dur - PARK_FROM_END_MS);
                park = 1L;
            }
        }
        return new long[] {target, park};
    }

    /**
     * A restore owed to the rebuilt player's start. The rebuild always starts from zero, and
     * a seek after the fact shows frame 0 for the quarter second it takes to land - on the
     * wallpaper window, which is what the lock screen's cards and notifications blur. So the
     * first frame after an exit was the video's opening frame in every card (a green flash on
     * the sheep video), with the right frame only arriving after the cover had gone. Seeking
     * before start is what the OEM's own startInternal does (seekTo 0 when COMPLETED), so the
     * first frame the window gets is already the right one.
     */
    private static volatile Object sPreSeekEng;
    private static volatile String sPreSeekPath;
    private static volatile long sPreSeekPos = -1L;
    private static volatile long sPreSeekAt;
    private static volatile long sPreSeekUntil;
    /** Where the seek before start went, -1 when none did. */
    private static volatile long sPreSeeked = -1L;

    private static void hookPlayerStart() {
        try {
            Xp.hookAll("wall.video.fast_start", Xp.findClass(CLS_FASTPLAYER, sCl), "start", chain -> {
                long pos = sPreSeekPos;
                if (pos >= 0 && SystemClock.uptimeMillis() < sPreSeekUntil) {
                    Object eng = sPreSeekEng;
                    Object self = chain.getThisObject();
                    if (eng != null && lockPlayer(eng) == self) {
                        sPreSeekPos = -1L;
                        long target = restoreTarget(sPreSeekPath, pos, sPreSeekAt)[0];
                        if (target > 100L) {
                            try {
                                Xp.callMethod(self, "seekto", 0.0f, target, 0);
                                sPreSeeked = target;
                            } catch (Throwable t) {
                                Xp.w(TAG + "seek before start failed: " + t);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(TAG + "FastPlayer.start hooked (restore seek before start)");
        } catch (Throwable t) {
            Xp.w(TAG + "FastPlayer.start hook failed: " + t);
        }
        // The plain shape's player, the same story: its reload is a reset MediaPlayer, and the
        // quarter second from its frame 0 to the seek was frame 0 in every card.
        try {
            Xp.hookAll("wall.video.media_start", android.media.MediaPlayer.class, "start", chain -> {
                long pos = sPreSeekPos;
                if (pos >= 0 && SystemClock.uptimeMillis() < sPreSeekUntil) {
                    Object eng = sPreSeekEng;
                    Object self = chain.getThisObject();
                    if (eng != null && lockPlayer(eng) == self) {
                        sPreSeekPos = -1L;
                        long target = restoreTarget(sPreSeekPath, pos, sPreSeekAt)[0];
                        if (target > 100L) {
                            try {
                                ((android.media.MediaPlayer) self).seekTo(target,
                                        android.media.MediaPlayer.SEEK_CLOSEST);
                                sPreSeeked = target;
                            } catch (Throwable t) {
                                Xp.w(TAG + "seek before start failed: " + t);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            Xp.log(TAG + "MediaPlayer.start hooked (restore seek before start)");
        } catch (Throwable t) {
            Xp.w(TAG + "MediaPlayer.start hook failed: " + t);
        }
    }

    /** Duration of a video file, cached per path. 0 when unknown. */
    private static volatile String sDurationOf;
    private static volatile long sDurationMs;

    private static long videoDurationMs(String path) {
        if (path == null) return 0L;
        if (path.equals(sDurationOf)) return sDurationMs;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = d == null ? 0L : Long.parseLong(d);
            sDurationOf = path;
            sDurationMs = ms;
            return ms;
        } catch (Throwable t) {
            Xp.log(TAG + "duration of " + path + " unreadable: " + t);
            return 0L;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * A frame of the wallpaper's own video, for the multi-frame cover video's way in (only with
     * Main.sVideoFade on). Scaled by the decoder: a 4K frame is 35MB unscaled. Null is not a
     * failure - it is the one-frame cover.
     */
    private static Bitmap wallpaperFrame(String path, long atMs, int w, int h) {
        if (path == null) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            Bitmap frame = atMs > 0
                    ? mmr.getScaledFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    : mmr.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            if (frame == null) return null;
            Bitmap out = centerCrop(frame, w, h);
            if (out != frame) frame.recycle();
            return out;
        } catch (Throwable t) {
            Xp.log(TAG + "wallpaperFrame: cannot decode " + path + " (" + t + ")");
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Says something to SystemUI. Foreground, like every other broadcast between the two
     * processes: from the background queue a broadcast took a measured ~500ms to arrive, and
     * SystemUI is holding its half of the transition for this one.
     */
    private static void tellSystemUi(String op, String why) {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", op);
            out.putExtra("why", why);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "told SystemUI: " + op + " (" + why + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "tellSystemUi(" + op + ") failed: " + t);
        }
    }

    /**
     * The still cover's frosting for the lyrics is starting (or is there already), with the
     * decision it answers - SystemUI holds the lyrics back for it. See LockLyrics.onBlurShown.
     */
    private static void tellBlurShown(String why) {
        Context c = sCtx;
        if (c == null) return;
        try {
            Intent out = new Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE");
            out.setPackage("com.android.systemui");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("op", "blurshown");
            out.putExtra("why", why);
            out.putExtra("blurseq", sBlurSeq);
            ProbeGuard.send(c, out);
            Xp.log(TAG + "told SystemUI: blurshown at " + sBlurSeq + " (" + why + ")");
        } catch (Throwable t) {
            Xp.w(TAG + "blurshown failed: " + t);
        }
    }

    /** The video half of `WPROBE op state`, as the broadcast's result. */
    private static String describeVideo() {
        Object eng = sActiveEngine != null ? sActiveEngine : targetEngine();
        Object fp = lockPlayer(eng);
        Object mgr = videoDepthManager(eng);
        StringBuilder sb = new StringBuilder();
        sb.append("engine=").append(eng == null ? "none" : eng.getClass().getSimpleName())
          .append(" lockEngine=").append(sVideoEngine == null ? "none" : "seen")
          .append(" desktopEngine=").append(sDesktopVideoEngine == null ? "none" : "seen")
          .append(" shared=").append(sharedVideo())
          .append(" keyguardUp=").append(sKeyguardUp)
          .append(" suspended=").append(sSharedSuspended)
          .append(" lockIsVideo=").append(sLockIsVideo)
          .append(" coverActive=").append(sCoverVideoActive)
          .append(" coverPath=").append(sCoverVideoPath)
          .append(" lockPath=").append(sLockVideoPath)
          .append(" loops=").append(sLockLoops)
          .append(" saved=").append(sSavedVideoPosMs)
          .append(" player=").append(describePlayer(eng, fp))
          .append(" depthPath=").append(mgr == null ? "-" : readPathField(mgr))
          .append(" pinned=").append(sPathPinned)
          .append(" broken=").append(sBrokenTakeovers)
          .append(" gen=").append(sTakeoverGen.get());
        return sb.toString();
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
                Xp.hook("wall.probe.ctor_args." + Xp.slug(c.getSimpleName())
                        + "#" + Xp.slug(Xp.signature(ct)), ct, argLogger("<init>"));
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
                    Xp.hook("wall.probe.bitmap_args."
                            + Xp.slug(m.getDeclaringClass().getSimpleName())
                            + "." + Xp.slug(m.getName())
                            + "#" + Xp.slug(Xp.signature(m)),
                            m, argLogger(m.getName()));
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
     * module's own art or the OEM's one wallpaper and both repeat - and per SIZE within that,
     * because a foldable alternates between its screens and each wants its own cut.
     *
     * The size comes from sSurfaceW/H, which the caller has just read off the renderer doing
     * this upload. Taking it from anywhere else is what cropped the cover for one screen and
     * handed it to another.
     */
    private static Bitmap screenSized(Bitmap src) {
        int w = sSurfaceW, h = sSurfaceH;
        if (!sTexFit || src == null || w <= 0 || h <= 0) return null;
        if (src.getWidth() == w && src.getHeight() == h) return null;
        Long key = ((long) w << 32) | (h & 0xffffffffL);
        synchronized (sScreenArts) {
            // A new source makes every cut of the old one useless at once.
            if (sScreenArtOf != src) {
                sScreenArts.clear();
                sScreenArtOf = src;
            }
            Bitmap have = sScreenArts.get(key);
            if (have != null && !have.isRecycled()) return have;
        }
        // Outside the lock: the other screen's upload should not wait on this one's crop.
        Bitmap fitted;
        try {
            fitted = centerCrop(src, w, h);
        } catch (Throwable t) {
            Xp.w(TAG + "screen-size fit failed: " + t);
            return null;
        }
        // Nothing is recycled here. The old cut used to be freed on the spot, which is right
        // when there is one screen and wrong the moment there are two: the other screen's
        // renderer can still be drawing from the cut this one is replacing, and a recycled
        // bitmap under GL is a black wallpaper, not an exception anyone sees. The collector
        // frees them once the map drops them.
        int held;
        synchronized (sScreenArts) {
            // Another source arrived while this one was cropped: the cut is still right for
            // this upload, it is only not kept.
            if (sScreenArtOf == src) sScreenArts.put(key, fitted);
            held = sScreenArts.size();
        }
        Xp.log(TAG + "texture fitted to the screen: " + describe(src)
                + " -> " + describe(fitted) + " (" + held + " cut(s) held)");
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
