package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import java.lang.reflect.Array;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.Callable;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Async hardware frames for the optical edge; the shade window is always excluded. */
final class IosShadeBackdropCapture {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "ios-shade-backdrop");
        thread.setDaemon(true);
        return thread;
    }, (task, executor) -> {
        Runnable discarded = executor.getQueue().poll();
        if (discarded instanceof CaptureTask) ((CaptureTask) discarded).discard();
        if (!executor.isShutdown()) executor.execute(task);
        else if (task instanceof CaptureTask) ((CaptureTask) task).discard();
    });
    private static boolean warned;

    private IosShadeBackdropCapture() {}

    static void request(ViewGroup root, int top, int bottom, Consumer<Bitmap> callback) {
        try {
            int width = root.getWidth(), height = root.getHeight();
            if (width <= 0 || height <= 0 || root.getDisplay() == null) {
                callback.accept(null);
                return;
            }
            Object viewRoot = Xp.callMethod(root, "getViewRootImpl");
            Object surface = Xp.callMethod(viewRoot, "getSurfaceControl");
            if (surface == null || !Boolean.TRUE.equals(Xp.callMethod(surface, "isValid"))) {
                callback.accept(null);
                return;
            }
            int displayId = root.getDisplay().getDisplayId();
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            Rect crop = new Rect(location[0], location[1] + top, location[0] + width, location[1] + bottom);
            float scale = 1f; // Only the narrow optical strip is captured, at native pixel density.
            WORKER.execute(new CaptureTask(() -> capture(displayId, crop, scale, surface), callback));
        } catch (Throwable error) {
            warn(error);
            callback.accept(null);
        }
    }

    private static final class CaptureTask implements Runnable {
        private final Callable<Bitmap> capture;
        private final Consumer<Bitmap> callback;

        CaptureTask(Callable<Bitmap> capture, Consumer<Bitmap> callback) {
            this.capture = capture;
            this.callback = callback;
        }

        @Override public void run() {
            Bitmap result = null;
            try { result = capture.call(); }
            catch (Throwable error) { warn(error); }
            Bitmap completed = result;
            UI.post(() -> callback.accept(completed));
        }

        void discard() {
            // A superseded pending request must complete so its owner can retry or retire.
            UI.post(() -> callback.accept(null));
        }
    }

    private static Bitmap capture(int displayId, Rect crop, float scale, Object shadeSurface) throws Exception {
        Class<?> capture;
        try { capture = Class.forName("android.window.ScreenCaptureInternal"); }
        catch (ClassNotFoundException ignored) { capture = Class.forName("android.window.ScreenCapture"); }
        Object builder = Class.forName(capture.getName() + "$CaptureArgs$Builder")
                .getDeclaredConstructor().newInstance();
        Xp.callMethod(builder, "setSourceCrop", crop);
        Xp.callMethod(builder, "setFrameScale", scale);
        Object excluded = Array.newInstance(Class.forName("android.view.SurfaceControl"), 1);
        Array.set(excluded, 0, shadeSurface);
        Xp.callMethod(builder, "setExcludeLayers", excluded);
        // This ROM's WMS replaces the SurfaceControl exclusion array with cast layers.
        // Its native name exclusions survive that conversion (also used by the SystemUI
        // screenshot backdrop and WM Transition). Require them to avoid sampling ourselves.
        Xp.callMethod(builder, "setMiCaptureMode", 1);
        Xp.callMethod(builder, "setExcludeOrIncludeLayerNames", (Object) new String[]{
                "NotificationShade#", "StatusBar#", "NavigationBar0#", "DynamicIslandWindow#"});
        // Secure/protected-content policies retain their platform defaults. No flags that
        // bypass capture restrictions or include protected buffers are requested here.
        Object args = Xp.callMethod(builder, "build");
        Object listener = capture.getMethod("createSyncCaptureListener").invoke(null);
        Object manager = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        Xp.callMethod(manager, "captureDisplay", displayId, args, listener);
        Object buffer = Xp.callMethod(listener, "getBuffer"); // Wait only on the worker.
        if (buffer == null) return null;
        Bitmap hardware = null;
        HardwareBuffer nativeBuffer = null;
        try {
            nativeBuffer = (HardwareBuffer) Xp.callMethod(buffer, "getHardwareBuffer");
            hardware = (Bitmap) Xp.callMethod(buffer, "asBitmap");
            // asBitmap wraps the buffer for GPU use; never read pixels back to the CPU.
            Bitmap result = hardware;
            hardware = null;
            return result;
        } finally {
            if (hardware != null) hardware.recycle();
            if (nativeBuffer != null) nativeBuffer.close();
        }
    }

    private static synchronized void warn(Throwable error) {
        if (warned) return;
        warned = true;
        Xp.log("[IOSShade] edge backdrop unavailable; retaining clear glass highlight: " + error);
    }
}
