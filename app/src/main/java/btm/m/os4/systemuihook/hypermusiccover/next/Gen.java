// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.animation.Animator;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * Everything this generation of the module owns inside one process.
 *
 * <p>Hot reload needs one place that knows what the retiring code still holds - hooks, main
 * thread messages, threads, receivers, listeners, animations, views, the host state it wrote -
 * because the framework only hands over hooks. This is that ledger: [retire] takes all of it
 * back, on the main thread, when a generation commits to stepping down.
 *
 * <p>The guard is the one behavioural edge: every hooker is wrapped so that once this
 * generation is retired or inert it proceeds untouched.
 *
 * <p>Rules that GenRulesTest will enforce, once the migration is finished: module code does not
 * call registerReceiver, start threads or executors, post frame callbacks or register host
 * listeners directly; it goes through here. The app's own process (app/, ui/, updater/) is not
 * covered - it is never hot reloaded.
 */
public final class Gen {

    private Gen() {
    }

    /** Set in the retirement's silent step; new work is refused from here on. */
    static volatile boolean quiescing;
    /** Set when retirement is committed: hooks pass through, queued work is dropped. */
    static volatile boolean retired;
    /** Adoption failed: this generation answers nothing until the process restarts. */
    static volatile boolean inert;
    /**
     * Set while a retirement's teardown runs on the main thread. The teardown gives OEM state
     * back through the same helpers the features use at runtime, and those are all refused once
     * the gate is closed - which it already is by the time the teardown runs. So the teardown's
     * own posts run in place instead: same work, on the main thread, before the sweep takes the
     * views they touch.
     */
    private static volatile boolean tearingDown;

    /** Whether this generation may still do its work. Public for features that have to tell
     *  "the user closed this" from "the retirement is taking it down" (CountdownScene's ground). */
    public static boolean live() {
        return !retired && !inert;
    }

    // ---------------------------------------------------------------- retirement

    /** Runs [task] on the main thread and waits for it; false when it could not be run in time. */
    static boolean onMain(long timeoutMs, Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
            return true;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        // A plain Handler, not Main.main(): that one drops everything once this generation is no
        // longer live, and the retirement's own step is posted after retired is set.
        if (!new Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        })) {
            return false;
        }
        return await(latch, timeoutMs);
    }

    /** retireOnMain's task: not started, deciding, committed, or given up by the caller. */
    private static final int R_WAITING = 0, R_CHECKING = 1, R_COMMITTED = 2, R_ABANDONED = 3;
    /** How long the caller waits for a committed retirement to finish its teardown. */
    private static final long R_FINISH_MS = 10_000L;

    /**
     * Set while a committed retirement's teardown and sweep run. A JVM system property because
     * it has to cross generations: the next one cannot see this class, but every class loader
     * in the process shares java.lang.System. Gone with the process, so a crash mid-teardown
     * leaves nothing behind.
     */
    private static final String TEARDOWN_MARK = "btm.m.os4.systemuihook.hypermusiccover.next.hr.teardown";

    /**
     * The whole retirement as one main-thread task: [check] decides, and on its yes the
     * generation retires, [beforeSweep] gives the OEM its state back, and the ledger takes back
     * everything this generation registered. Returns null once committed, else the refusal.
     *
     * <p>One task, because the check reads main-thread state - the lock screen, a morph, the
     * shade - and so does the teardown. Checked on the binder thread and torn down later on
     * the main thread, a morph could start in between and be pulled out from under the frame
     * that was drawing it.
     *
     * <p>The caller waits [timeoutMs]. A main thread that has not got to the task by then never
     * runs it: the task's first step claims it, the caller's timeout abandons it, and only one
     * of the two can win. A check that is still running when the caller gives up is abandoned
     * the same way, before anything has been let go. Once committed there is no refusing - the
     * caller waits for the teardown to finish instead.
     *
     * <p>The hooks are not touched: the next generation replaces or unhooks them through
     * HotReload, and until then they pass through because [retired] is set. The sweep is best
     * effort on purpose: every step is independent and a failure is left for the next one.
     */
    static String retireOnMain(long timeoutMs, java.util.function.Supplier<String> check,
                               Runnable beforeSweep) {
        final AtomicInteger state = new AtomicInteger(R_WAITING);
        final String[] refusal = {null};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable task = () -> {
            if (!state.compareAndSet(R_WAITING, R_CHECKING)) return;
            try {
                String why;
                try {
                    why = check.get();
                } catch (Throwable t) {
                    why = "the check failed: " + t;
                }
                // Up before the commit can be seen: a caller that gives up waiting returns
                // "retired", and the next generation must find this already set.
                if (why == null) System.setProperty(TEARDOWN_MARK, Integer.toHexString(
                        System.identityHashCode(Gen.class)));
                if (why == null && !state.compareAndSet(R_CHECKING, R_COMMITTED)) {
                    why = "the caller gave up while the check ran";
                    System.clearProperty(TEARDOWN_MARK);
                }
                if (why != null) {
                    refusal[0] = why;
                    return;
                }
                quiescing = true;
                retired = true;
                try {
                    if (beforeSweep != null) {
                        Throwable t = teardown(beforeSweep);
                        if (t != null) Xp.w("[MCGen] teardown before the sweep failed: " + t);
                    }
                    sweep(false);
                } finally {
                    System.clearProperty(TEARDOWN_MARK);
                }
            } finally {
                done.countDown();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
            return refusal[0];
        }
        // A plain Handler, not Main.main(): the task sets retired, and that one drops what is
        // queued on it once this generation is no longer live.
        if (!new Handler(android.os.Looper.getMainLooper()).post(task)) {
            return "the main thread is not running";
        }
        if (!await(done, timeoutMs)) {
            if (state.compareAndSet(R_WAITING, R_ABANDONED)) {
                return "the main thread did not get to the retirement in " + timeoutMs + "ms";
            }
            if (state.compareAndSet(R_CHECKING, R_ABANDONED)) {
                return "the main thread's check did not finish in " + timeoutMs + "ms";
            }
            // Committed: the generation has already let go, and refusing now would leave the
            // framework keeping a generation that is no longer there.
            if (!await(done, R_FINISH_MS)) {
                Xp.w("[MCGen] retirement committed but its teardown has not finished");
            }
            return null;
        }
        return refusal[0];
    }

    /**
     * The new generation's side: waits up to [timeoutMs] for the previous one's teardown, which
     * a retirement that outlasted its caller's wait may still be running on the main thread -
     * restoring OEM state the new hooks are about to write, and sweeping as they go in. False
     * when it is still running.
     */
    static boolean awaitPredecessor(long timeoutMs) {
        String mark = System.getProperty(TEARDOWN_MARK);
        if (mark == null) return true;
        long start = android.os.SystemClock.uptimeMillis();
        long deadline = start + timeoutMs;
        while (System.getProperty(TEARDOWN_MARK) != null) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) return false;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return System.getProperty(TEARDOWN_MARK) == null;
            }
        }
        Xp.w("[MCGen] waited " + (android.os.SystemClock.uptimeMillis() - start)
                + "ms for generation " + mark + "'s teardown");
        return true;
    }

    private static boolean await(java.util.concurrent.CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * An adoption that could not finish: this generation answers nothing from here on. Hooks
     * already pass through once inert is set, but what boot or the attach path registered before
     * the failure - receivers, session callbacks, our views, the OEM state they changed - would
     * keep running half a generation. So the same teardown and sweep a retirement runs, on the
     * main thread; [teardown] may be null for a process with no host state to give back.
     */
    static void goInert(long timeoutMs, Runnable teardown) {
        quiescing = true;
        inert = true;
        boolean ran = onMain(timeoutMs, () -> {
            if (teardown != null) {
                Throwable t = teardown(teardown);
                if (t != null) Xp.w("[MCGen] teardown of an inert generation failed: " + t);
            }
            sweep(false);
        });
        if (!ran) Xp.w("[MCGen] inert generation's sweep did not finish in time");
    }

    /**
     * Run a teardown with the in-place gate open: the OEM state handed back from inside it must
     * not be queued (the gate is already closed, and the sweep would drop it). The dry run uses
     * this too - a rehearsal that dropped the hand-backs was not rehearsing the real path.
     */
    static Throwable teardown(Runnable beforeSweep) {
        tearingDown = true;
        try {
            beforeSweep.run();
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            tearingDown = false;
        }
    }

    /**
     * What a dry run takes back: everything registered since the install step, plus the host
     * state and views this generation wrote. What was registered by {@link #markInstallScope()}
     * stays (the dry run does not reinstall), and so do the long-lived threads and executors -
     * their owners are install-time code that is not run again, and some are static finals a
     * second start could not revive. The generation stays live: the caller puts it back up
     * immediately, which is the point of the exercise.
     */
    static boolean sweepForDryRun(long timeoutMs) {
        return onMain(timeoutMs, () -> sweep(true));
    }

    /**
     * Registrations made before the first container attach. A dry run re-runs boot and the
     * attach path, not installSystemUi, so anything recorded here would otherwise be taken back
     * and never put back.
     */
    private static final java.util.Set<Object> INSTALL_KEPT =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Called at the end of installSystemUi: from here on, these registrations outlive a dry run. */
    static void markInstallScope() {
        INSTALL_KEPT.clear();
        INSTALL_KEPT.addAll(receivers);
        INSTALL_KEPT.addAll(observers);
        INSTALL_KEPT.addAll(prefListeners);
        INSTALL_KEPT.addAll(sessionCallbacks);
        INSTALL_KEPT.addAll(activeSessions);
        INSTALL_KEPT.addAll(cameraCallbacks);
        INSTALL_KEPT.addAll(networkCallbacks);
        INSTALL_KEPT.addAll(sensorListeners);
        INSTALL_KEPT.addAll(preDraws);
        INSTALL_KEPT.addAll(attachStates);
        INSTALL_KEPT.addAll(layoutChanges);
        INSTALL_KEPT.addAll(displayListeners);
        INSTALL_KEPT.addAll(swaps);
        INSTALL_KEPT.addAll(ownViews);
        INSTALL_KEPT.addAll(ownWindows);
        INSTALL_KEPT.addAll(animators);
        Xp.log("[MCGen] install scope marked, " + INSTALL_KEPT.size()
                + " registrations outlive a dry run");
    }

    /**
     * @param dryRun keep the install-scoped registrations and the long-lived infrastructure;
     *               see sweepForDryRun.
     */
    private static void sweep(boolean dryRun) {
        // A real retirement calls this from the main thread through Gen.onMain: anything that
        // escaped here would be an uncaught exception in SystemUI, and the commit step may not
        // throw. The steps are independent anyway, so one failure must not stop the rest.
        try {
            sweepNow(dryRun);
        } catch (Throwable t) {
            Xp.w("[MCGen] sweep failed: " + t);
        }
    }

    private static void sweepNow(boolean dryRun) {
        for (java.util.Map.Entry<Runnable, MainPost> e : MAIN_POSTED.entrySet()) {
            try {
                Main.main().removeCallbacks(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        MAIN_POSTED.clear();
        // The wrapper map only knows what Gen posted. Direct main().post() calls made by the
        // features are on the same Handler and would otherwise run after the teardown - one
        // queued card-gone timer would call into the generation that just let go. This Handler
        // is the module's own; no OEM message is on it.
        try {
            Main.main().removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        // Handlers the features made for themselves (DateStatus, the shade's frame driver, the
        // mini player's per-controller one...). Their messages are ours too, and the same rule
        // applies: after retirement nothing of ours may still be queued on the main thread.
        java.util.List<android.os.Handler> handlers;
        synchronized (mainHandlers) {
            handlers = new java.util.ArrayList<>(mainHandlers);
        }
        for (android.os.Handler h : handlers) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        // The queues are cleared in both runs, the ledger only in a real retirement: the dry run
        // puts the same generation back up, and its features do not register these again (they
        // are static finals and lazy singletons), so dropping them here would leave the next,
        // real retirement unable to clear what is queued on them.
        if (!dryRun) mainHandlers.clear();

        for (PostedOn p : postedOn) {
            try {
                p.view.removeCallbacks(p);
            } catch (Throwable ignored) {
            }
        }
        postedOn.clear();

        for (java.util.Map.Entry<Choreographer.FrameCallback, Frame> e : frames.entrySet()) {
            try {
                Choreographer.getInstance().removeFrameCallback(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        frames.clear();

        // Long-lived infrastructure. A real retirement quits it - the next generation builds its
        // own - but a dry run has no next generation to build it: some of these are static
        // finals (the lyric pool) and the rest are cached in statics that install-time code
        // would have to be run again to replace.
        //
        // Not joined: this is the main thread, a GL or composing thread can be in the middle of
        // a frame, and nothing after this waits on them being gone - what they run from here on
        // is gated (liveHandler, guarded), and each ends at its loop's next turn.
        if (!dryRun) {
            for (HandlerThread t : handlerThreads) {
                try {
                    t.quitSafely();
                } catch (Throwable ignored) {
                }
            }
            handlerThreads.clear();

            for (ExecutorService e : executors) {
                try {
                    // What never started is handed back, not cancelled: a submit()'s caller
                    // waiting on get() (MotionArt's album pages) would wait for good.
                    for (Runnable r : e.shutdownNow()) {
                        if (r instanceof java.util.concurrent.Future) {
                            ((java.util.concurrent.Future<?>) r).cancel(false);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            executors.clear();
        }

        for (ReceiverReg r : receivers) {
            if (kept(dryRun, r)) continue;
            try {
                r.context.unregisterReceiver(r.receiver);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, receivers);

        for (ObserverReg o : observers) {
            if (kept(dryRun, o)) continue;
            try {
                o.resolver.unregisterContentObserver(o.observer);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, observers);

        for (PrefReg p : prefListeners) {
            if (kept(dryRun, p)) continue;
            try {
                p.prefs.unregisterOnSharedPreferenceChangeListener(p.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, prefListeners);

        for (SessionReg s : sessionCallbacks) {
            if (kept(dryRun, s)) continue;
            try {
                s.controller.unregisterCallback(s.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, sessionCallbacks);

        for (ActiveSessionsReg s : activeSessions) {
            if (kept(dryRun, s)) continue;
            try {
                s.manager.removeOnActiveSessionsChangedListener(s.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, activeSessions);

        for (CameraReg c : cameraCallbacks) {
            if (kept(dryRun, c)) continue;
            try {
                c.manager.unregisterAvailabilityCallback(c.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, cameraCallbacks);

        for (NetworkReg n : networkCallbacks) {
            if (kept(dryRun, n)) continue;
            try {
                n.manager.unregisterNetworkCallback(n.callback);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, networkCallbacks);

        for (SensorReg r : sensorListeners) {
            if (kept(dryRun, r)) continue;
            try {
                r.manager.unregisterListener(r.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, sensorListeners);

        for (PreDrawReg p : preDraws) {
            if (kept(dryRun, p)) continue;
            try {
                if (p.view != null) {
                    p.view.getViewTreeObserver().removeOnPreDrawListener(p.listener);
                } else if (p.vto != null && p.vto.isAlive()) {
                    p.vto.removeOnPreDrawListener(p.listener);
                }
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, preDraws);

        for (AttachReg a : attachStates) {
            if (kept(dryRun, a)) continue;
            try {
                a.view.removeOnAttachStateChangeListener(a.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, attachStates);

        for (LayoutReg l : layoutChanges) {
            if (kept(dryRun, l)) continue;
            try {
                l.view.removeOnLayoutChangeListener(l.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, layoutChanges);

        for (DisplayReg d : displayListeners) {
            if (kept(dryRun, d)) continue;
            try {
                d.manager.unregisterDisplayListener(d.listener);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, displayListeners);

        for (Animator a : animators) {
            if (kept(dryRun, a)) continue;
            try {
                a.cancel();
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, animators);

        for (Swap s : swaps) {
            if (kept(dryRun, s)) continue;
            try {
                if (s.restore != null) s.restore.run();
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, swaps);

        for (OwnedView v : ownViews) {
            if (kept(dryRun, v)) continue;
            try {
                v.parent.removeView(v.view);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, ownViews);

        for (OwnedWindow w : ownWindows) {
            if (kept(dryRun, w)) continue;
            try {
                w.manager.removeViewImmediate(w.view);
            } catch (Throwable ignored) {
            }
        }
        retain(dryRun, ownWindows);

        // The host listeners recorded as opaque objects (treeListener) have no type to remove
        // them by; the feature that registered one removes it in its own retire. Anything left
        // is named in the log rather than silently called into a retired generation.
        int trees = treeListeners.size();
        treeListeners.clear();

        if (!dryRun) {
            for (Thread t : threads) {
                try {
                    t.join(20L);
                } catch (Throwable ignored) {
                }
            }
            threads.clear();
        } else {
            // Not stopped in a dry run (see above), but a record of one that is already gone is
            // not something this generation holds. Plain threads take themselves out (thread());
            // these two kinds have no such exit.
            handlerThreads.removeIf(t -> !t.isAlive());
            executors.removeIf(ExecutorService::isShutdown);
        }
        Xp.log("[MCGen] " + (dryRun ? "dry-run swept" : "retired")
                + ": registrations taken back, treeListeners=" + trees);
    }

    /** Whether a dry run must leave this registration alone; see INSTALL_KEPT. */
    private static boolean kept(boolean dryRun, Object registration) {
        return dryRun && INSTALL_KEPT.contains(registration);
    }

    /** Drop the swept entries; a real retirement drops all of them. */
    private static <T> void retain(boolean dryRun, List<T> list) {
        list.removeIf(r -> !dryRun || !INSTALL_KEPT.contains(r));
    }

    // ---------------------------------------------------------------- what we own

    static final List<XposedInterface.HookHandle> hooks = new CopyOnWriteArrayList<>();
    private static final List<PostedOn> postedOn = new CopyOnWriteArrayList<>();
    /**
     * The main-looper Handlers the features made for themselves; see {@link #handler()}.
     *
     * Weak, so a controller that is gone takes its handler's ledger entry with it instead of
     * leaving one per keyguard rebuild. A handler with a queued message is still reachable
     * through that message, so the sweep can clear it; one with nothing pending has nothing
     * left to clear.
     */
    private static final java.util.Set<android.os.Handler> mainHandlers =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<android.os.Handler, Boolean>()));
    /**
     * One entry per callback, not one per post. An animation re-posts itself from inside its
     * own frame until it lands, so a list of posts grew by a frame's worth per animation.
     */
    private static final java.util.Map<Choreographer.FrameCallback, Frame> frames =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<HandlerThread> handlerThreads = new CopyOnWriteArrayList<>();
    private static final List<ExecutorService> executors = new CopyOnWriteArrayList<>();
    private static final List<Thread> threads = new CopyOnWriteArrayList<>();
    private static final List<ReceiverReg> receivers = new CopyOnWriteArrayList<>();
    private static final List<ObserverReg> observers = new CopyOnWriteArrayList<>();
    private static final List<PrefReg> prefListeners = new CopyOnWriteArrayList<>();
    private static final List<SessionReg> sessionCallbacks = new CopyOnWriteArrayList<>();
    private static final List<ActiveSessionsReg> activeSessions = new CopyOnWriteArrayList<>();
    private static final List<CameraReg> cameraCallbacks = new CopyOnWriteArrayList<>();
    private static final List<NetworkReg> networkCallbacks = new CopyOnWriteArrayList<>();
    private static final List<SensorReg> sensorListeners = new CopyOnWriteArrayList<>();
    private static final List<TreeReg> treeListeners = new CopyOnWriteArrayList<>();
    private static final List<PreDrawReg> preDraws = new CopyOnWriteArrayList<>();
    private static final List<AttachReg> attachStates = new CopyOnWriteArrayList<>();
    private static final List<LayoutReg> layoutChanges = new CopyOnWriteArrayList<>();
    private static final List<DisplayReg> displayListeners = new CopyOnWriteArrayList<>();
    private static final List<Swap> swaps = new CopyOnWriteArrayList<>();
    private static final List<OwnedView> ownViews = new CopyOnWriteArrayList<>();
    private static final List<OwnedWindow> ownWindows = new CopyOnWriteArrayList<>();
    private static final List<Animator> animators = new CopyOnWriteArrayList<>();

    private static final class PostedOn implements Runnable {
        final View view;
        /** What the caller holds, for removeCallbacksOn. */
        final Runnable original;

        PostedOn(View view, Runnable original) {
            this.view = view;
            this.original = original;
        }

        @Override
        public void run() {
            // Like the main-handler wrapper below: a runnable that has run is no longer held,
            // or a track change's one-shot post would keep its view and bitmaps for the whole
            // generation.
            postedOn.remove(this);
            if (live()) original.run();
        }
    }

    /**
     * The wrapper posted on the caller's behalf; see frame(). One per callback, counted like
     * MainPost: posted twice before a frame, the first run must not drop the entry the second
     * copy is still queued under, or unframe would leave that copy behind.
     */
    private static final class Frame implements Choreographer.FrameCallback {
        final Choreographer.FrameCallback callback;
        /** Copies still queued; only changed inside the frames map's compute. */
        int pending;

        Frame(Choreographer.FrameCallback callback) {
            this.callback = callback;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            try {
                if (live()) callback.doFrame(frameTimeNanos);
            } finally {
                // After the callback, so a loop that re-posts from inside its own frame has
                // already counted the next copy and keeps its entry.
                frames.computeIfPresent(callback,
                        (k, v) -> v != this || --v.pending > 0 ? v : null);
            }
        }
    }

    private static final class ReceiverReg {
        final Context context;
        final BroadcastReceiver receiver;

        ReceiverReg(Context context, BroadcastReceiver receiver) {
            this.context = context;
            this.receiver = receiver;
        }
    }

    private static final class ObserverReg {
        final ContentResolver resolver;
        final ContentObserver observer;

        ObserverReg(ContentResolver resolver, ContentObserver observer) {
            this.resolver = resolver;
            this.observer = observer;
        }
    }

    private static final class PrefReg {
        final SharedPreferences prefs;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        PrefReg(SharedPreferences prefs,
                SharedPreferences.OnSharedPreferenceChangeListener listener) {
            this.prefs = prefs;
            this.listener = listener;
        }
    }

    private static final class SessionReg {
        final MediaController controller;
        final MediaController.Callback callback;

        SessionReg(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    private static final class TreeReg {
        final View view;
        final Object listener;

        TreeReg(View view, Object listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class ActiveSessionsReg {
        final MediaSessionManager manager;
        final MediaSessionManager.OnActiveSessionsChangedListener listener;

        ActiveSessionsReg(MediaSessionManager manager,
                          MediaSessionManager.OnActiveSessionsChangedListener listener) {
            this.manager = manager;
            this.listener = listener;
        }
    }

    private static final class CameraReg {
        final CameraManager manager;
        final CameraManager.AvailabilityCallback callback;

        CameraReg(CameraManager manager, CameraManager.AvailabilityCallback callback) {
            this.manager = manager;
            this.callback = callback;
        }
    }

    private static final class NetworkReg {
        final ConnectivityManager manager;
        final ConnectivityManager.NetworkCallback callback;

        NetworkReg(ConnectivityManager manager, ConnectivityManager.NetworkCallback callback) {
            this.manager = manager;
            this.callback = callback;
        }
    }

    private static final class SensorReg {
        final SensorManager manager;
        final SensorEventListener listener;

        SensorReg(SensorManager manager, SensorEventListener listener) {
            this.manager = manager;
            this.listener = listener;
        }
    }

    private static final class LayoutReg {
        final View view;
        final View.OnLayoutChangeListener listener;

        LayoutReg(View view, View.OnLayoutChangeListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class PreDrawReg {
        /** The view, or null for a call site that only holds the observer (then [vto]). */
        final View view;
        final ViewTreeObserver vto;
        final ViewTreeObserver.OnPreDrawListener listener;

        PreDrawReg(View view, ViewTreeObserver vto, ViewTreeObserver.OnPreDrawListener listener) {
            this.view = view;
            this.vto = vto;
            this.listener = listener;
        }
    }

    private static final class AttachReg {
        final View view;
        final View.OnAttachStateChangeListener listener;

        AttachReg(View view, View.OnAttachStateChangeListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }

    private static final class DisplayReg {
        final DisplayManager manager;
        final DisplayManager.DisplayListener listener;

        DisplayReg(DisplayManager manager, DisplayManager.DisplayListener listener) {
            this.manager = manager;
            this.listener = listener;
        }
    }

    private static final class Swap {
        final Object owner;
        final String what;
        final Object original;
        final Runnable restore;

        Swap(Object owner, String what, Object original, Runnable restore) {
            this.owner = owner;
            this.what = what;
            this.original = original;
            this.restore = restore;
        }
    }

    private static final class OwnedView {
        final ViewGroup parent;
        final View view;

        OwnedView(ViewGroup parent, View view) {
            this.parent = parent;
            this.view = view;
        }
    }

    private static final class OwnedWindow {
        final WindowManager manager;
        final View view;

        OwnedWindow(WindowManager manager, View view) {
            this.manager = manager;
            this.view = view;
        }
    }

    // ---------------------------------------------------------------- main thread

    /**
     * The wrapper posted for each runnable, so removeCallbacks can find it: a guarded copy is
     * a different object from the one the caller holds. One wrapper per runnable however many
     * times it is queued (the mini player posts the same retry now and 250ms later), counted, so
     * the copy that runs first does not take the entry out from under the one still waiting -
     * removeCallbacks would then miss it. Only what is still queued is held.
     */
    private static final java.util.Map<Runnable, MainPost> MAIN_POSTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class MainPost implements Runnable {
        final Runnable key;
        /** Copies of this wrapper still queued; only changed inside MAIN_POSTED's compute. */
        int pending;

        MainPost(Runnable key) {
            this.key = key;
        }

        @Override
        public void run() {
            MAIN_POSTED.computeIfPresent(key, (k, v) -> v != this || --v.pending > 0 ? v : null);
            if (live()) key.run();
        }
    }

    /**
     * A main-looper Handler of the module's own, for features that need their own queue rather
     * than [post]'s. Registered so retirement can clear what is still waiting on it - a delayed
     * refresh queued here runs on this generation's code, which by then must not run at all.
     */
    public static android.os.Handler handler() {
        return watchHandler(liveHandler());
    }

    /**
     * A main-looper Handler that delivers nothing once this generation is retired or inert. The
     * sweep clears the queues it knows of, but a thread of ours that was mid-lookup at retirement
     * finishes later and posts its answer then; delivered, it would run this generation's code
     * against the host the next one now owns (Main.main() is one of these too).
     */
    static android.os.Handler liveHandler() {
        return liveHandler(android.os.Looper.getMainLooper());
    }

    /**
     * The same on a thread of ours. The sweep's quitSafely still delivers what is already due,
     * and the retirement is committed on the main thread while this one keeps going - a cover
     * push queued on the composing worker would then compose from the old state and send it
     * to the wallpaper over the next generation's.
     */
    static android.os.Handler liveHandler(android.os.Looper looper) {
        return new android.os.Handler(looper) {
            @Override
            public void dispatchMessage(android.os.Message msg) {
                if (live()) super.dispatchMessage(msg);
            }
        };
    }

    /** A Handler the feature had to subclass (a Messenger's, say): same registration, handed back. */
    public static android.os.Handler watchHandler(android.os.Handler h) {
        if (h != null) mainHandlers.add(h);
        return h;
    }

    /**
     * Runs [r] now when it belongs to a teardown on the main thread; true when it did. A
     * teardown's steps are synchronous hand-backs - restoring the cut-out, the panel's
     * presence, the glass - and queueing them would only let the sweep drop them.
     */
    private static boolean runInTeardown(Runnable r) {
        if (!tearingDown || r == null) return false;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false;
        try {
            r.run();
        } catch (Throwable t) {
            Xp.w("[MCGen] teardown action failed: " + t);
        }
        return true;
    }

    /**
     * Post to the module's main handler; refused once quiescing or inert. False when [r] will
     * not run - a caller that counted something in flight has to count it back out itself.
     */
    public static boolean post(Runnable r) {
        if (runInTeardown(r)) return true;
        if (quiescing || inert) return false;
        MainPost g = mainGuarded(r);
        if (Main.main().post(g)) return true;
        unguard(g);
        return false;
    }

    public static boolean postDelayed(Runnable r, long ms) {
        // Only an immediate step is a hand-back; a delayed one (a fade, a parked swap) is
        // something the teardown is meant to cancel, not run in the middle of itself.
        if (ms <= 0L && runInTeardown(r)) return true;
        if (quiescing || inert) return false;
        MainPost g = mainGuarded(r);
        if (Main.main().postDelayed(g, ms)) return true;
        unguard(g);
        return false;
    }

    /**
     * The counterpart of post/postDelayed; only the handler Gen posted through is targeted.
     * Every queued copy goes: the Handler removes a wrapper wherever it is queued.
     */
    public static void removeCallbacks(Runnable r) {
        MainPost g = MAIN_POSTED.remove(r);
        Main.main().removeCallbacks(g == null ? r : g);
    }

    private static MainPost mainGuarded(Runnable r) {
        return MAIN_POSTED.compute(r, (k, v) -> {
            MainPost g = v != null ? v : new MainPost(k);
            g.pending++;
            return g;
        });
    }

    /** A post the Handler would not take: one copy fewer than mainGuarded counted. */
    private static void unguard(MainPost g) {
        MAIN_POSTED.computeIfPresent(g.key, (k, v) -> v != g || --v.pending > 0 ? v : null);
    }

    /** Post on a host view, and remember it so retirement can take it back out. */
    public static void postOn(View view, Runnable r) {
        if (view == null || runInTeardown(r)) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.post(g);
    }

    /** As {@link #postOn}, after [ms] on the view's own handler. */
    public static void postDelayedOn(View view, Runnable r, long ms) {
        if (view == null || (ms <= 0L && runInTeardown(r))) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.postDelayed(g, ms);
    }

    /** As {@link #postOn}, on the view's next animation frame. Retirement removes both alike. */
    public static void postOnAnimation(View view, Runnable r) {
        if (view == null || runInTeardown(r)) return;
        if (quiescing || inert) return;
        Runnable g = remember(view, r);
        view.postOnAnimation(g);
    }

    /**
     * The caller's own view post, taken back (see {@link #postOn}); callers keep their own
     * Runnable for the removal, so the wrapper is looked up by the original.
     */
    public static void removeCallbacksOn(View view, Runnable r) {
        if (view == null || r == null) return;
        // A reposting loop may have queued the same Runnable more than once; each copy's wrapper
        // has to come off the view as well as out of the ledger.
        java.util.List<PostedOn> hits = new java.util.ArrayList<>();
        for (PostedOn p : postedOn) {
            if (p.view == view && p.original == r) hits.add(p);
        }
        if (hits.isEmpty()) {
            view.removeCallbacks(r);
            return;
        }
        for (PostedOn p : hits) {
            postedOn.remove(p);
            try {
                view.removeCallbacks(p);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Runnable remember(View view, Runnable original) {
        PostedOn g = new PostedOn(view, original);
        postedOn.add(g);
        return g;
    }

    /** Post a frame callback, and remember the wrapper so retirement (or unframe) can take it back. */
    public static void frame(Choreographer.FrameCallback cb) {
        if (quiescing || inert) return;
        Frame wrapped = frames.compute(cb, (k, v) -> {
            Frame f = v != null ? v : new Frame(k);
            f.pending++;
            return f;
        });
        Choreographer.getInstance().postFrameCallback(wrapped);
    }

    /** Every queued copy: Choreographer removes a callback wherever it is queued. */
    public static void unframe(Choreographer.FrameCallback cb) {
        Frame wrapped = frames.remove(cb);
        Choreographer.getInstance().removeFrameCallback(wrapped == null ? cb : wrapped);
    }

    // ---------------------------------------------------------------- threads

    public static HandlerThread handlerThread(String name) {
        return handlerThread(name, android.os.Process.THREAD_PRIORITY_DEFAULT);
    }

    public static HandlerThread handlerThread(String name, int priority) {
        HandlerThread t = new HandlerThread(name, priority);
        t.start();
        handlerThreads.add(t);
        return t;
    }

    public static ExecutorService executor(String name) {
        ExecutorService e = Executors.newSingleThreadExecutor(r -> new Thread(guarded(r), name));
        executors.add(e);
        return e;
    }

    public static ExecutorService executor(String name, int priority, boolean daemon) {
        ExecutorService e = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(guarded(r), name);
            t.setDaemon(daemon);
            t.setPriority(priority);
            return t;
        });
        executors.add(e);
        return e;
    }

    public static ExecutorService executor(String name, int threads, int priority, boolean daemon) {
        ExecutorService e = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(guarded(r), name);
            t.setDaemon(daemon);
            t.setPriority(priority);
            return t;
        });
        executors.add(e);
        return e;
    }

    /**
     * A thread whose body is skipped when this generation is no longer live by the time it
     * starts. A FutureTask is cancelled then, so whoever waits on its get() is let go.
     */
    public static Thread thread(String name, Runnable r) {
        return thread(name, r, r instanceof java.util.concurrent.Future
                ? () -> ((java.util.concurrent.Future<?>) r).cancel(false) : null);
    }

    /**
     * The same, with [skipped] run in the body's place when it is skipped: the other half of
     * whatever the caller paired with the body - a count taken before it started, a receipt it
     * owes, a queue someone is polling.
     */
    public static Thread thread(String name, Runnable r, Runnable skipped) {
        // Out of the ledger when the work is done: this list is what the generation still holds,
        // and a finished Thread still pins its runnable's captures (lyric rows, bitmaps). Only
        // the dry run used to prune it, so a live generation kept one entry per lookup and per
        // card picture for its whole life.
        Thread t = new Thread(() -> {
            try {
                if (live()) {
                    r.run();
                } else if (skipped != null) {
                    // Not thrown on: an uncaught throw here would take the process down on a
                    // retired generation's behalf.
                    try {
                        skipped.run();
                    } catch (Throwable e) {
                        Xp.w("[MCGen] " + name + " skipped, and its pairing failed: " + e);
                    }
                }
            } finally {
                threads.remove(Thread.currentThread());
            }
        }, name);
        threads.add(t);
        t.start();
        return t;
    }

    // ---------------------------------------------------------------- host callbacks

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, int flags) {
        if (refused("a receiver")) return null;
        Intent sticky = c.registerReceiver(r, f, flags);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f) {
        if (refused("a receiver")) return null;
        Intent sticky = c.registerReceiver(r, f);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    /**
     * A sticky query, not a registration: a null receiver returns what is there and leaves
     * nothing to unregister. Kept here so no call site has to spell registerReceiver itself.
     */
    public static Intent stickyReceiver(Context c, IntentFilter f) {
        return c.registerReceiver(null, f);
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, String permission,
                           Handler handler) {
        if (refused("a receiver")) return null;
        Intent sticky = c.registerReceiver(r, f, permission, handler);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static Intent receiver(Context c, BroadcastReceiver r, IntentFilter f, String permission,
                           Handler handler, int flags) {
        if (refused("a receiver")) return null;
        Intent sticky = c.registerReceiver(r, f, permission, handler, flags);
        receivers.add(new ReceiverReg(c, r));
        return sticky;
    }

    public static void observer(ContentResolver cr, Uri uri, boolean descendants, ContentObserver o) {
        if (refused("an observer")) return;
        cr.registerContentObserver(uri, descendants, o);
        observers.add(new ObserverReg(cr, o));
    }

    public static void prefListener(SharedPreferences p,
                             SharedPreferences.OnSharedPreferenceChangeListener l) {
        if (refused("a preference listener")) return;
        p.registerOnSharedPreferenceChangeListener(l);
        prefListeners.add(new PrefReg(p, l));
    }

    public static void unprefListener(SharedPreferences p,
                                      SharedPreferences.OnSharedPreferenceChangeListener l) {
        p.unregisterOnSharedPreferenceChangeListener(l);
        prefListeners.removeIf(r -> r.prefs == p && r.listener == l);
    }

    public static void sessionCallback(MediaController c, MediaController.Callback cb, Handler h) {
        if (refused("a session callback")) return;
        c.registerCallback(cb, h);
        sessionCallbacks.add(new SessionReg(c, cb));
    }

    /** The other side of sessionCallback, for a rebind that moves to another controller. */
    public static void unsessionCallback(MediaController c, MediaController.Callback cb) {
        c.unregisterCallback(cb);
        sessionCallbacks.removeIf(r -> r.controller == c && r.callback == cb);
    }

    /** The system's active-session list (which app is playing), not one controller's callbacks. */
    public static void activeSessionsListener(MediaSessionManager m,
                                              MediaSessionManager.OnActiveSessionsChangedListener l,
                                              Handler h) {
        if (refused("an active-session listener")) return;
        m.addOnActiveSessionsChangedListener(l, null, h);
        activeSessions.add(new ActiveSessionsReg(m, l));
    }

    public static void unactiveSessionsListener(MediaSessionManager m,
                                                MediaSessionManager.OnActiveSessionsChangedListener l) {
        m.removeOnActiveSessionsChangedListener(l);
        activeSessions.removeIf(r -> r.manager == m && r.listener == l);
    }

    /** Which cameras are in use (the pickup card's video-call gate, PickupCamera). */
    public static void cameraCallback(CameraManager m, CameraManager.AvailabilityCallback cb,
                                      Handler h) {
        if (refused("a camera callback")) return;
        m.registerAvailabilityCallback(cb, h);
        cameraCallbacks.add(new CameraReg(m, cb));
    }

    public static void uncameraCallback(CameraManager m, CameraManager.AvailabilityCallback cb) {
        m.unregisterAvailabilityCallback(cb);
        cameraCallbacks.removeIf(r -> r.manager == m && r.callback == cb);
    }

    /**
     * The process's default network (the motion-cover downloader's online gate). The permission
     * is the host's - this runs inside SystemUI, not under the module's manifest - which is why
     * MotionCover's caller was suppressed before the call moved here.
     */
    @android.annotation.SuppressLint("MissingPermission")
    public static void networkCallback(ConnectivityManager m,
                                       ConnectivityManager.NetworkCallback cb, Handler h) {
        if (refused("a network callback")) return;
        m.registerDefaultNetworkCallback(cb, h);
        networkCallbacks.add(new NetworkReg(m, cb));
    }

    public static void unnetworkCallback(ConnectivityManager m,
                                         ConnectivityManager.NetworkCallback cb) {
        m.unregisterNetworkCallback(cb);
        networkCallbacks.removeIf(r -> r.manager == m && r.callback == cb);
    }

    /** A sensor of the process's SensorManager (the proximity checks of the lock-screen pages). */
    public static boolean sensorListener(SensorManager m, SensorEventListener l, Sensor s,
                                         int rate, Handler h) {
        if (refused("a sensor listener")) return false;
        boolean ok = m.registerListener(l, s, rate, h);
        if (ok) sensorListeners.add(new SensorReg(m, l));
        return ok;
    }

    public static void unsensorListener(SensorManager m, SensorEventListener l) {
        m.unregisterListener(l);
        sensorListeners.removeIf(r -> r.manager == m && r.listener == l);
    }

    /**
     * A listener on a host view whose type varies (pre-draw, layout, attach). Recorded here so
     * retirement can find it; the typed removal is the caller's, by kind.
     */
    public static void treeListener(View v, Object listener) {
        treeListeners.add(new TreeReg(v, listener));
    }

    // ---------------------------------------------------------------- view listeners

    public static void preDraw(View v, ViewTreeObserver.OnPreDrawListener l) {
        if (refused("a pre-draw")) return;
        v.getViewTreeObserver().addOnPreDrawListener(l);
        preDraws.add(new PreDrawReg(v, null, l));
    }

    /** For a call site that holds the observer rather than the view it belongs to. */
    public static void preDraw(ViewTreeObserver vto, ViewTreeObserver.OnPreDrawListener l) {
        if (refused("a pre-draw")) return;
        vto.addOnPreDrawListener(l);
        preDraws.add(new PreDrawReg(null, vto, l));
    }

    public static void unpreDraw(View v, ViewTreeObserver.OnPreDrawListener l) {
        v.getViewTreeObserver().removeOnPreDrawListener(l);
        preDraws.removeIf(r -> r.view == v && r.listener == l);
    }

    /** For a call site that only kept the observer it added through, not the view. */
    public static void unpreDraw(ViewTreeObserver vto, ViewTreeObserver.OnPreDrawListener l) {
        vto.removeOnPreDrawListener(l);
        preDraws.removeIf(r -> r.listener == l);
    }

    public static void attachState(View v, View.OnAttachStateChangeListener l) {
        if (refused("an attach listener")) return;
        v.addOnAttachStateChangeListener(l);
        attachStates.add(new AttachReg(v, l));
    }

    public static void unattachState(View v, View.OnAttachStateChangeListener l) {
        v.removeOnAttachStateChangeListener(l);
        attachStates.removeIf(r -> r.view == v && r.listener == l);
    }

    public static void layoutChange(View v, View.OnLayoutChangeListener l) {
        if (refused("a layout listener")) return;
        v.addOnLayoutChangeListener(l);
        layoutChanges.add(new LayoutReg(v, l));
    }

    public static void unlayoutChange(View v, View.OnLayoutChangeListener l) {
        v.removeOnLayoutChangeListener(l);
        layoutChanges.removeIf(r -> r.view == v && r.listener == l);
    }

    public static void displayListener(DisplayManager dm, DisplayManager.DisplayListener l,
                                       Handler h) {
        if (refused("a display listener")) return;
        dm.registerDisplayListener(l, h);
        displayListeners.add(new DisplayReg(dm, l));
    }

    public static void undisplayListener(DisplayManager dm, DisplayManager.DisplayListener l) {
        dm.unregisterDisplayListener(l);
        displayListeners.removeIf(r -> r.manager == dm && r.listener == l);
    }

    // ---------------------------------------------------------------- what we changed

    /** A host value we wrote, with the way back. */
    public static <T> void swap(Object owner, String what, T original, Runnable restore) {
        swap(owner, what, original, restore, false);
    }

    /**
     * The same, for a value written after installSystemUi from a path a dry run does not run
     * again (the control-centre plugin's watch): [outlivesDryRun] keeps a dry run from giving
     * it back, as markInstallScope does for what install wrote.
     */
    public static <T> void swap(Object owner, String what, T original, Runnable restore,
                                boolean outlivesDryRun) {
        Swap s = new Swap(owner, what, original, restore);
        swaps.add(s);
        if (outlivesDryRun) INSTALL_KEPT.add(s);
        Xp.d("[MCGen] swap " + what + " (had " + original + ")");
    }

    public static void ownView(ViewGroup parent, View v) {
        // Added by a generation that has already let go (a late answer on one of its threads):
        // the ledger has been swept and nothing would ever take this view out again.
        if (refused("a view")) {
            try {
                if (parent != null) parent.removeView(v);
            } catch (Throwable ignored) {
            }
            return;
        }
        // A view put back into a rebuilt tree would otherwise be recorded once per attach; the
        // sweep only ever needs the latest parent.
        ownViews.removeIf(o -> o.view == v);
        ownViews.add(new OwnedView(parent, v));
    }

    public static void ownWindow(WindowManager manager, View v) {
        if (refused("a window")) {
            try {
                manager.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
            return;
        }
        ownWindows.removeIf(o -> o.view == v);
        ownWindows.add(new OwnedWindow(manager, v));
    }

    public static <A extends Animator> A anim(A a) {
        animators.add(a);
        return a;
    }

    /** Called by Xp for every hook it installs or replaces. */
    static void onHook(XposedInterface.HookHandle h) {
        hooks.add(h);
    }

    // ---------------------------------------------------------------- guard and report

    /**
     * Whether a registration must be refused: this generation has retired or gone inert, so its
     * ledger has been (or is being) swept, and anything recorded now would never be taken back.
     */
    private static boolean refused(String what) {
        if (live()) return false;
        Xp.w("[MCGen] refused " + what + ": this generation is no longer live");
        return true;
    }

    private static Runnable guarded(Runnable r) {
        return () -> {
            if (live()) r.run();
        };
    }

    /** What this generation holds, for the probe and for the hot-reload log line. */
    static String report() {
        return "class=" + Integer.toHexString(System.identityHashCode(Gen.class))
                + " live=" + live()
                + " quiescing=" + quiescing
                + " retired=" + retired
                + " inert=" + inert
                + " hooks=" + hooks.size()
                + " threads=" + threads.size()
                + " handlerThreads=" + handlerThreads.size()
                + " executors=" + executors.size()
                + " receivers=" + receivers.size()
                + " observers=" + observers.size()
                + " prefListeners=" + prefListeners.size()
                + " sessionCallbacks=" + sessionCallbacks.size()
                + " activeSessions=" + activeSessions.size()
                + " cameraCallbacks=" + cameraCallbacks.size()
                + " networkCallbacks=" + networkCallbacks.size()
                + " sensorListeners=" + sensorListeners.size()
                + " treeListeners=" + treeListeners.size()
                + " preDraws=" + preDraws.size()
                + " attachStates=" + attachStates.size()
                + " layoutChanges=" + layoutChanges.size()
                + " displayListeners=" + displayListeners.size()
                + " postedOn=" + postedOn.size()
                + " mainPosted=" + MAIN_POSTED.size()
                + " frames=" + frames.size()
                + " swaps=" + swaps.size()
                + " ownViews=" + ownViews.size()
                + " ownWindows=" + ownWindows.size()
                + " animators=" + animators.size();
    }
}
