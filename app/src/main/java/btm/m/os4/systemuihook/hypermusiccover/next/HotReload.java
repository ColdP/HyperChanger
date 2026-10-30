// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;
import btm.m.os4.systemuihook.BuildConfig;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;

import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.AppleLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupIsland;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.RearScreen;
import btm.m.os4.systemuihook.hypermusiccover.LegacyShadeBridge;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapFocus;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapImmerse;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapOppoBridge;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapTransitShare;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.MetroCodeIsland;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * The two hot-reload entry points. Main forwards {@code onHotReloading}/{@code onHotReloaded}
 * here.
 *
 * <p>Every supported process is on {@link HotReloadPolicy#SUPPORTED}; anything else refuses in
 * the check section and the app restarts it. SystemUI is the one with views to put back: its
 * teardown is {@link SystemUiRetire}, run on the main thread inside the commit step.
 *
 * <p>The new generation has every static field empty, exactly as after a process start. The
 * saved state carries the few host objects it cannot look up again; persistent settings come
 * from the state file, never from here.
 */
public final class HotReload {

    private HotReload() {
    }

    static final String TAG = "[MCHotReload] ";

    /** The package onPackageLoaded last reported in this process. */
    private static volatile String sProcess = "?";
    /** The process itself, which is what the supported list names; a package has several. */
    private static volatile String sProcessName;
    /** Every package onPackageLoaded named; more than one per process (see status's log). */
    private static final Set<String> sPkgs = ConcurrentHashMap.newKeySet();
    /** The host loader the first package reported, a hint only; adoption still validates it. */
    private static volatile ClassLoader sHostLoader;
    /** One delayed breaker check per cold start, even when several packages report. */
    private static volatile boolean sBootCheckPosted;

    /** This generation's number; the new code starts at 1 and adoption writes the old one + 1. */
    private static volatile int sGen = 1;
    /** Distinguishes two class loaders whose counters both start at 1. */
    private static final String sClassId = Integer.toHexString(System.identityHashCode(HotReload.class));

    /** Set once retirement has been agreed to: old hookers still run, and say so. */
    private static volatile boolean sRetired;

    /** The adoption in progress, or null. Xp's install path pairs hooks through it. */
    private static volatile HandleStore sStore;

    /** Set while the new generation runs boot: that is not a cold start, so no breaker check. */
    private static volatile boolean sAdoptingBoot;

    private static final String PENDING = "hyperchanger_hmc_next_hot_reload_pending";
    private static final String BLOCKED = "hyperchanger_hmc_next_hot_reload_blocked";
    /** A pending marker older than this is from a process that was simply restarted later. */
    private static final long PENDING_WINDOW_MS = 2 * 60 * 1000L;
    /** The build blocked in this process (its version code), or empty. For `op=gen`. */
    private static volatile String sBlocked = "";
    /** How the last adoption in this process paired the hooks, or empty. For `op=gen`. */
    private static volatile String sAdopted = "";
    /** Why this generation went inert, or null. For `op=gen` and the app's query. */
    private static volatile String sInert;

    // ---------------------------------------------------------------- module side

    /** Called first thing in Main.onPackageLoaded; remembers where this process is. */
    static void onPackageLoaded(String pkg, ClassLoader cl) {
        sProcess = pkg;
        sPkgs.add(pkg);
        if (cl != null && sHostLoader == null) sHostLoader = cl;
        if (sProcessName == null) sProcessName = processName();
        if (!sBootCheckPosted && HotReloadPolicy.SUPPORTED.contains(sProcessName)) {
            sBootCheckPosted = true;
            // The Application does not exist yet at onPackageLoaded; a second later it does.
            // A process that came up through Main.boot checks again there - whichever runs
            // first clears the marker, and the other finds nothing.
            Gen.postDelayed(() -> onBoot(appContext(null)), 1000L);
        }
    }

    /**
     * The process name, not the package: 高德's :locationservice and :widgetProvider report the
     * same package in onPackageLoaded and own their own copies of the hooks; whether a process
     * reloads is decided by its own name (HotReloadPolicy.SUPPORTED).
     */
    private static String processName() {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The retirement half. Only the check section (A) is implemented: whatever fails here leaves
     * the old generation whole. Refusing is the normal answer while SUPPORTED is empty.
     */
    public static boolean retire(XposedModuleInterface.HotReloadingParam param) {
        Bundle extras = param.getExtras();
        String build = extras == null ? "?" : extras.getString(HotReloadPolicy.KEY_BUILD, "?");
        Xp.log(TAG + "onHotReloading thread=" + Thread.currentThread().getName()
                + " main=" + (Looper.myLooper() == Looper.getMainLooper())
                + " process=" + sProcess + " pkgs=" + sPkgs
                + " proc=" + sProcessName
                + " gen=" + sGen + " class=" + sClassId + " build=" + build);

        int protocol = extras == null
                ? HotReloadPolicy.PROTOCOL
                : extras.getInt(HotReloadPolicy.KEY_PROTOCOL, HotReloadPolicy.PROTOCOL);
        if (protocol != HotReloadPolicy.PROTOCOL) {
            return refuse("protocol " + protocol + " != " + HotReloadPolicy.PROTOCOL);
        }
        String proc = supportedProcess();
        if (proc == null) {
            return refuse("process not supported");
        }
        String blocked = blockedReason();
        if (blocked != null) {
            return refuse(blocked);
        }
        // An inert generation never ran its hooks, so it learned nothing the next one could be
        // handed - the wallpaper's engine, above all, is only seen as it is constructed. Adopted
        // from, the next generation comes up half blind (measured 2026-10-27: engine=none); it
        // stays inert until the process restarts instead.
        if (Gen.inert) {
            return refuse("this generation is inert; restart the process");
        }

        // The two processes with state to give back run their own main-thread step before the
        // sweep: SystemUI's features give the OEM its state back - the clock's squeeze, the
        // hidden print, the card's listeners, the islands - while our views are still in the
        // tree; the wallpaper's hands its caches and the cover-video player over, the cover
        // itself going to the next generation through the saved state instead.
        Runnable teardown = null;
        if ("com.android.systemui".equals(proc)) teardown = SystemUiRetire::retireForReload;
        else if ("com.miui.miwallpaper".equals(proc)) teardown = WallpaperProbe::retireForReload;
        // From the busy check to the sweep, one main-thread task: what busy() reads is the main
        // thread's, and a check made here on the binder thread could be stale by the time the
        // teardown ran (see Gen.retireOnMain). On its yes the old hookers pass through, nothing
        // new is posted, and the ledger takes back everything it recorded.
        String refusal = Gen.retireOnMain(teardown == null ? 2000L : 4000L, () -> {
            // A feature that is in the middle of something it cannot stop reports it here.
            String busy = busy();
            if (busy != null) return busy;
            if ((BuildConfig.DEBUG || false) && extras != null
                    && "check".equals(extras.getString(HotReloadPolicy.KEY_FAULT))) {
                return "fault injection: check";
            }
            try {
                // The commit step may not throw; the framework also refuses our own classes, so
                // the encoding - and its check - happens here, where a failure still leaves us
                // running.
                param.setSavedInstanceState(HotReloadState.encode(
                        build, proc, sHostLoader, Main.sAppCtx, saveFeatures(proc), sGen + 1,
                        BuildConfig.VERSION_CODE));
            } catch (Throwable t) {
                return "could not save state: " + t;
            }
            return null;
        }, teardown);
        if (refusal != null) {
            return refuse(refusal);
        }
        sRetired = true;
        // The two ClassValue caches live on the host's Class objects and would keep this
        // generation (and its loader) reachable for as long as the process runs.
        Xp.dropClassValues();
        Xp.log(TAG + "HotReload retired gen=" + sGen + " process=" + proc + " build=" + build
                + " hooks=" + Gen.hooks.size() + " class=" + sClassId);
        return true;
    }

    private static boolean refuse(String reason) {
        Xp.w(TAG + "HotReload deferred: " + reason + " process=" + sProcess + " pkgs=" + sPkgs
                + " proc=" + sProcessName + " gen=" + sGen);
        return false;
    }

    /**
     * 高德's main process or one of its supported sub-processes: each gets the same installer,
     * and only the main process ever has a trip on it - the others answer not busy.
     */
    private static boolean isAmap(String proc) {
        return proc != null && (proc.equals(AmapImmerse.PKG) || proc.startsWith(AmapImmerse.PKG + ":"));
    }

    /** The process this module is supported under, or null. See HotReloadPolicy.SUPPORTED. */
    private static String supportedProcess() {
        String name = sProcessName;
        if (name != null) return HotReloadPolicy.SUPPORTED.contains(name) ? name : null;
        // Without a process name, the package is the best available answer; every supported
        // process's main package equals its process name.
        for (String p : HotReloadPolicy.SUPPORTED) {
            if (sPkgs.contains(p)) return p;
        }
        return null;
    }

    /**
     * The feature that cannot be interrupted right now, or null. Runs on the main thread, in
     * the same task as the teardown (Gen.retireOnMain), so what it saw is still true then.
     */
    static String busy() {
        // A request still on the network is not a reason to wait: its thread is the old
        // generation's, and what it posts back is refused once that generation is retired.
        // Each process asks its own features. SystemUI's display and keyguard reads come from
        // Main's cached sys reads, which only that process initialises (boot); in the
        // lightweight processes they answer "off" and refused every update - measured
        // 2026-10-25, all five supported processes failed with "the screen is off".
        //
        // Resolved the same way the retirement resolves it: sProcessName can be null when
        // ActivityThread would not answer at load time, and supportedProcess() is what let the
        // update through in the first place. Reading the raw field here skipped every check.
        String proc = supportedProcess();
        if ("com.miui.miwallpaper".equals(proc)) return WallpaperProbe.busyReason();
        // A trip is not handed over: its cards end on timers this generation's sweep takes.
        if (isAmap(proc)) return AmapTransitShare.busyReason();
        if (!"com.android.systemui".equals(proc)) return null;
        // The user never taps "update" while looking at the lock screen; anything on it is
        // someone using the phone, and every one of those transitions has a view mid-flight.
        if (Main.keyguardShowing() && Main.screenOn()) return "the lock screen is up and lit";
        // A doze or a sleeping display: the rebuild's frame work would not run, and the test
        // would be measuring a teardown that nobody is looking at.
        if (!Main.screenOn()) return "the screen is off";
        if (CoverMorphLayer.active()) return "a cover morph is running";
        if (CoverCardLayer.animating()) return "the cover card is animating";
        if (LegacyShadeBridge.expansion() > 0.01f) return "the shade is open";
        if (CoverPush.sCoverFadeWaiting || CoverPush.sCoverFadingOut != null
                || CoverPush.sOwedSwap != null) {
            return "a cover push is in flight";
        }
        // A page has the lock screen: the clock is small and the cut-out gone for it, and the
        // depth group would be refused by the hold if it tried to give them back.
        if (ImmersiveHost.holdsClock()) return "an immersive page has the lock screen";
        // An order page waiting on the hidden display, or an order being watched: neither is
        // handed over, and a page left on a display nobody can see is lost to the user until
        // SystemUI restarts (the island's tap would bring it up there). Like 高德's trip, the
        // next install after the order lands.
        if (btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupPark.INSTANCE.anyParked()) {
            return "an order page is parked on the hidden display";
        }
        if (btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupObserver.INSTANCE.observing()) {
            return "an order is being watched";
        }
        return null;
    }

    /**
     * What each feature hands over. Only values that are not ours: primitives, strings, and
     * host or framework objects (HotReloadState checks that before the commit).
     */
    private static Object[][] saveFeatures(String proc) {
        List<Object[]> out = new ArrayList<>();
        if (AppleLyrics.PKG.equals(proc)) {
            out.add(new Object[]{"apple", new Object[]{
                    AppleLyrics.heldId(), AppleLyrics.heldPayload(),
                    AppleLyrics.playingId(), AppleLyrics.playingTitle(),
            }});
        }
        if ("com.android.systemui".equals(proc)) {
            // What the state file cannot say and the new generation cannot look up again the
            // way a cold start would: the token the wallpaper process answers to, the two
            // runtime flags (the file keeps settings, not which wallpaper is up), the clock
            // container the keyguard attached earlier, the OEM card controller our proxy was
            // in, and the shortcut row the mini player hung off.
            out.add(new Object[]{"core", new Object[]{
                    ProbeGuard.token(), Main.sVideoWallpaper, Main.sDepthHidden}});
            out.add(new Object[]{"clock", Main.sContainer});
            out.add(new Object[]{"mediacard", MediaCard.save()});
            out.add(new Object[]{"island", MiniPlayerRuntime.save()});
            // The combines, and the policy flows the running collection actually reads: the
            // combine is collected once (stateIn Eagerly), so only those flows can still move it.
            out.add(new Object[]{"notifsink", Main.sNotificationSink.owners()});
            out.add(new Object[]{"notifpolicy", Main.sNotificationSink.policies()});
            // The fingerprint icon whose pointer listener was hooked on a long press.
            out.add(new Object[]{"fpq", new Object[]{FingerprintQuick.pointerIcon()}});
        }
        if (isAmap(proc)) {
            // The stand-in clients 高德 was handed: it may hold one across rides, and a call on
            // it that the new generation does not know as ours would go to the real stand-in.
            out.add(new Object[]{"amapclients", AmapOppoBridge.INSTANCE.save()});
        }
        if ("com.miui.personalassistant".equals(proc)) {
            // The ride in progress: 小爱's cards start a trip and extend it in memory only, and
            // the station whose card already went up will not come again - a new generation
            // would read the next one as "it starts here" and lose the stops so far.
            out.add(new Object[]{"metro", MetroCodeIsland.INSTANCE.save()});
        }
        if ("com.miui.miwallpaper".equals(proc)) {
            // The other side of SystemUI's token, the cover on the texture and the wallpaper it
            // fades back to, the engines, and the sizes and flags the drawing paths read.
            out.add(new Object[]{"wall", WallpaperProbe.save()});
        }
        return out.toArray(new Object[0][]);
    }

    /**
     * The adoption half, in the new generation. Installs the same hooks the cold path installs -
     * there is only one installer per process - pairing each with the old handle of the same id
     * and executable. Whatever is left of the old handles is unhooked; any failure here leaves
     * this generation inert (all hookers pass through, nothing is posted) until the process
     * restarts, so the module never runs half of one generation and half of another.
     */
    public static void adopt(XposedModuleInterface.HotReloadedParam param) {
        String proc = param.getProcessName();
        // onPackageLoaded is not replayed, so this is the only place the new generation learns
        // where it is. Without it the next hot reload is refused with "not supported (?)".
        sProcess = proc;
        sPkgs.add(proc);
        sProcessName = proc;
        Bundle extras = param.getExtras();
        String build = extras == null ? "?" : extras.getString(HotReloadPolicy.KEY_BUILD, "?");

        Xp.log(TAG + "onHotReloaded thread=" + Thread.currentThread().getName()
                + " main=" + (Looper.myLooper() == Looper.getMainLooper())
                + " process=" + proc + " build=" + build + " class=" + sClassId);

        HotReloadState.State state = HotReloadState.decode(param.getSavedInstanceState());
        if (state == null) {
            Xp.w(TAG + "no usable saved state; adopting without it");
        } else {
            Xp.log(TAG + "saved: schema=" + state.schema + " from=" + state.fromBuild
                    + " process=" + state.process + " features=" + state.features.length
                    + " reload=" + state.reloadCount);
        }
        sGen = state != null ? state.reloadCount : 1;
        sRetired = false;

        Context ctx = appContext(state);
        // A build named in BROKEN_RETIRE has already run its retirement by the time this is
        // called; what it left behind is unknown, so nothing is built on it. Not this build's
        // fault, so no pending marker: the restart that follows must not block it.
        int from = state == null ? 0 : state.fromVersionCode;
        String brokenFrom = from != 0 && HotReloadPolicy.BROKEN_RETIRE.contains(from)
                ? "build #" + from + " is known to retire badly" : null;
        // The same kind of not-ours: the previous generation's retirement returned while its
        // teardown was still on the main thread (Gen.retireOnMain gives up waiting), and hooks
        // installed now would be written over by what it gives back.
        if (brokenFrom == null && !Gen.awaitPredecessor(PREDECESSOR_WAIT_MS)) {
            brokenFrom = "the previous generation's teardown did not finish in "
                    + PREDECESSOR_WAIT_MS + "ms";
        }
        // If this process does not survive the next few seconds, the marker is still here when
        // it comes back, and that cold start blocks this build until the next restart.
        // The version code is this generation's own constant: the marker names the build that is
        // being adopted without depending on the extras (null under autoHotReload) or on a
        // package-manager query from a host that may not be allowed to see the module's APK.
        if (brokenFrom == null) {
            writePending(ctx, pendingMark(BuildConfig.VERSION_CODE, build,
                    System.currentTimeMillis()));
        }

        Set<String> oldIds = new java.util.HashSet<>();
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) oldIds.add(h.getId());

        HandleStore store = new HandleStore(param.getOldHookHandles());
        sStore = store;
        boolean failed = false;
        String failure = null;
        try {
            if (brokenFrom != null) throw new IllegalStateException(brokenFrom);
            // Test builds only (debug, or -PmcHrFaults=true): the inert path on purpose, in every process or one. Read from a system
            // property because autoHotReload sends no extras: `setprop debug.mc.hr_fault adopt`
            // (or `adopt:<process>`) before installing, `setprop debug.mc.hr_fault ""` after.
            if (BuildConfig.DEBUG || false) {
                String fault = sysProp("debug.mc.hr_fault");
                if ("adopt".equals(fault) || ("adopt:" + proc).equals(fault)) {
                    throw new IllegalStateException("fault injection: adopt");
                }
            }
            ClassLoader cl = hostLoader(proc, state, param.getOldHookHandles());
            if (cl == null) {
                throw new IllegalStateException("no host class loader can load the process's classes");
            }
            adoptAppleState(state);
            // Before the provider_call hook goes in, so no call falls between the two.
            if (isAmap(proc)) {
                AmapOppoBridge.INSTANCE.adopt(feature(state, "amapclients"));
            }
            // Before the hooks go in: the wallpaper's upload path is live as soon as it is
            // installed, and an upload that found no cover would hand the OEM's own decode of
            // the wallpaper to the texture - the cover gone for the rest of the track.
            if ("com.miui.miwallpaper".equals(proc)) {
                WallpaperProbe.adoptState(feature(state, "wall"));
            }
            // The same for 小爱's ride: install registers the receiver, and a card arriving
            // before the trip was back would start a new one at this station.
            if ("com.miui.personalassistant".equals(proc)) {
                final Object[] metro = feature(state, "metro");
                if (metro != null) step("metro", () -> MetroCodeIsland.INSTANCE.adopt(metro));
            }
            install(proc, cl);
            if ("com.android.systemui".equals(proc)) {
                // Arming here rather than from the attach path: the container may be gone
                // while the phone is unlocked, and HyperLight's own hook is already in place
                // (it is not reloaded), so the order this guard depends on still holds.
                ClockGlass.armMiGlassGuard();
                backfill(cl, param.getOldHookHandles());
                final Object[] fpq = feature(state, "fpq");
                final Object icon = fpq == null || fpq.length == 0 ? null : fpq[0];
                step("fpq pointer", () -> FingerprintQuick.adoptPointer(icon));
            }
            adoptFeatures(proc, state, cl);
        } catch (Throwable t) {
            failed = true;
            failure = String.valueOf(t);
            Xp.w(TAG + "adopt FAILED: " + t);
        } finally {
            sStore = null;
            if (failed) {
                for (XposedInterface.HookHandle h : store.installed()) unhook(h);
                store.clearInstalled();
            }
            // Read before unhookRemaining, which empties the list it reads.
            List<String> left = store.remainingLabels();
            int removed = store.unhookRemaining();
            oldIds.removeAll(store.newIds());
            sAdopted = "replaced=" + store.replacedCount()
                    + " added=" + store.addedCount()
                    + " removedOld=" + removed
                    + " failed=" + failed
                    + " features=" + (state == null ? 0 : state.features.length)
                    + " missing=" + oldIds
                    + " left=" + left;
            Xp.log(TAG + "HotReload adopted gen=" + sGen + " process=" + proc + " " + sAdopted
                    + " new=" + store.newIds());
            if (failed) {
                // The install step may have registered receivers before it threw; nothing of
                // ours has reached the host's views yet, so a sweep with no teardown is enough.
                goInert(proc, ctx, failure, null);
                Xp.w(TAG + "adopt FAILED, module inert in this process until restart");
            } else {
                // Five seconds is long enough for a crash in the first frame to leave the
                // marker behind, and short enough that a later user restart is not blamed.
                Gen.postDelayed(() -> clearPending(ctx), 5000L);
            }
        }
    }

    /**
     * An adoption that could not finish: hooks pass through and everything this generation put
     * up is taken down again (Gen.goInert). LSPosed reports the reload as a success all the same,
     * so the process keeps one answer up: every process answers the app's
     * {@link HotReloadPolicy#ACTION_INERT} question, and SystemUI its query as well. The home
     * page counts it with the processes that need a restart.
     */
    private static void goInert(String proc, Context ctx, String why, Runnable teardown) {
        sInert = why == null ? "?" : why;
        Gen.goInert(2000L, teardown);
        if (ctx == null) {
            Xp.w(TAG + "inert with no context; the app cannot be told");
            return;
        }
        try {
            ProbeGuard.registerOutsideLedger(ctx,
                    new android.content.IntentFilter(HotReloadPolicy.ACTION_INERT),
                    TAG, InertAnswer::new, BuildConfig.APPLICATION_ID);
        } catch (Throwable t) {
            Xp.w(TAG + "could not register the inert answer: " + t);
        }
        if (!"com.android.systemui".equals(proc)) return;
        try {
            ProbeGuard.registerOutsideLedger(ctx, new android.content.IntentFilter(PROBE_ACTION),
                    Main.TAG, InertAnswer::new, BuildConfig.APPLICATION_ID);
        } catch (Throwable t) {
            Xp.w(TAG + "could not register the inert answer: " + t);
        }
    }

    private static final String PROBE_ACTION = "btm.m.os4.systemuihook.hypermusiccover.next.PROBE";

    /**
     * All an inert generation still answers: the app's per-package question, and in SystemUI
     * the app's query, with nothing but that it is inert, and `op=gen` for adb. Every other op
     * is the old receivers' and is left unanswered.
     */
    static final class InertAnswer extends ProbeGuard.Receiver {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            if (!ProbeGuard.admit(this, i)) return;
            if (HotReloadPolicy.ACTION_INERT.equals(i.getAction())) {
                // Ordered across the package's processes: add this one to what the others said.
                try {
                    Bundle out = getResultExtras(true);
                    java.util.ArrayList<String> procs =
                            out.getStringArrayList(HotReloadPolicy.KEY_INERT_PROCS);
                    if (procs == null) procs = new java.util.ArrayList<>();
                    String name = sProcessName != null ? sProcessName : sProcess;
                    procs.add(name);
                    out.putStringArrayList(HotReloadPolicy.KEY_INERT_PROCS, procs);
                    out.putString("inertwhy:" + name, sInert);
                    setResultExtras(out);
                } catch (Throwable t) {
                    Xp.w(TAG + "inert answer failed: " + t);
                }
                return;
            }
            String op = i.getStringExtra("op");
            if ("query".equals(op)) {
                Bundle out = new Bundle();
                out.putBoolean("inert", true);
                out.putString("inertwhy", sInert);
                setResultExtras(out);
            } else if ("gen".equals(op)) {
                String report = Gen.report() + " " + status();
                Xp.log(Main.TAG + "gen " + report);
                setResultData(report);
            }
        }
    }

    /** The context the new generation can use before Main.boot has run. */
    private static Context appContext(HotReloadState.State state) {
        if (state != null && state.context != null) return state.context;
        if (Main.sAppCtx != null) return Main.sAppCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void install(String proc, ClassLoader cl) {
        if ("com.android.systemui".equals(proc)) {
            Main.installSystemUi(cl);
        } else if (AppleLyrics.PKG.equals(proc)) {
            AppleLyrics.handle(cl);
        } else if ("com.xiaomi.subscreencenter".equals(proc)) {
            RearScreen.handle(cl);
        } else if ("com.autonavi.minimap".equals(proc)
                || "com.autonavi.minimap:locationservice".equals(proc)
                || "com.autonavi.minimap:widgetProvider".equals(proc)) {
            // Named one by one, like SUPPORTED: HotReloadProcessTest holds the two lists equal.
            AmapImmerse.handle(cl);
        } else if ("com.miui.aod".equals(proc)) {
            HyperTweaks.aod(cl);
        } else if ("com.miui.personalassistant".equals(proc)) {
            MetroCodeIsland.INSTANCE.install(cl);
        } else if ("com.miui.miwallpaper".equals(proc)) {
            WallpaperProbe.install(cl);
        } else {
            throw new IllegalStateException("no installer for " + proc);
        }
    }

    /** How long adoption waits for the previous generation's teardown; see Gen.awaitPredecessor. */
    private static final long PREDECESSOR_WAIT_MS = 5000L;

    /** One feature's handover value, or null. */
    private static Object[] feature(HotReloadState.State state, String key) {
        if (state == null) return null;
        for (Object[] f : state.features) {
            if (f.length > 1 && key.equals(f[0]) && f[1] instanceof Object[]) {
                return (Object[]) f[1];
            }
        }
        return null;
    }

    /**
     * Apple's lyric for the track the session already names, before the hooks go back in. The
     * metadata on the session refers to a lyric only this process holds; without it the lock
     * screen shows an empty page until the next track. Strings only, so the binder thread the
     * framework calls us on is fine.
     */
    private static void adoptAppleState(HotReloadState.State state) {
        if (state == null) return;
        for (Object[] f : state.features) {
            if (!"apple".equals(f[0]) || !(f[1] instanceof Object[])) continue;
            Object[] a = (Object[]) f[1];
            AppleLyrics.adoptState(str(a, 0), str(a, 1), str(a, 2), str(a, 3));
        }
    }

    private static String sysProp(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String str(Object[] a, int i) {
        return a != null && i < a.length && a[i] instanceof String ? (String) a[i] : null;
    }

    /**
     * What SystemUI's new generation puts back up, on the main thread: the runtime state the
     * retirement handed over, the cold-start boot, the OEM objects that are not rebuilt (the
     * card's listener, the mini player's row), and then the container attach path a keyguard
     * rebuild would have run.
     *
     * <p>Boot runs even when nothing was handed over: an adoption with no usable saved state
     * still has to register the receivers and read the state file. The container is optional -
     * while the phone is unlocked the keyguard may have detached it, and the next attach hook
     * puts everything up then.
     */
    private static void adoptFeatures(String proc, HotReloadState.State state, ClassLoader host) {
        if ("com.miui.miwallpaper".equals(proc)) {
            // The rest of the cold-start path, on the main thread: the engine names are re-read
            // off the engine that was handed over, the receivers go back on, and the process
            // says hello with the token it inherited. No reload is asked for - the texture is
            // already the cover.
            final Context wallCtx = state != null && state.context != null
                    ? state.context : appContext(state);
            Gen.post(() -> {
                // The texture already holds the cover, so a generation that cannot finish this
                // step is a half one: inert until restart rather than running hooks over state
                // it never took back. The pending marker stays for the same reason.
                if (!step("wallpaper main", () -> WallpaperProbe.adoptMain(wallCtx))) {
                    goInert(proc, wallCtx, "the wallpaper adoption did not finish",
                            WallpaperProbe::retireForReload);
                    Xp.w(TAG + "wallpaper adoption did not finish; this generation is inert in"
                            + " this process until it restarts");
                }
            });
            return;
        }
        if ("com.miui.personalassistant".equals(proc)) {
            // No main-thread step: the trip came back before install(), and register() ran
            // inside it. What needs the context - the entry timeout, the island still up - now.
            step("metro", MetroCodeIsland.INSTANCE::adopted);
            return;
        }
        if (!"com.android.systemui".equals(proc)) return;
        Object[][] features = state == null ? new Object[0][] : state.features;
        sAdoptingBoot = true;
        Gen.post(() -> {
            boolean broken = false;
            try {
                Context ctx = state != null && state.context != null
                        ? state.context : appContext(state);
                Object[] core = null;
                Object card = null;
                Object[] island = null;
                Object[] notifSink = null;
                Object[] notifPolicy = null;
                android.view.View container = null;
                for (Object[] f : features) {
                    if (f == null || f.length < 2 || !(f[0] instanceof String)) continue;
                    switch ((String) f[0]) {
                        case "core":
                            if (f[1] instanceof Object[]) core = (Object[]) f[1];
                            break;
                        case "clock":
                            if (f[1] instanceof android.view.View) {
                                container = (android.view.View) f[1];
                            }
                            break;
                        case "mediacard":
                            card = f[1];
                            break;
                        case "island":
                            if (f[1] instanceof Object[]) island = (Object[]) f[1];
                            break;
                        case "notifsink":
                            if (f[1] instanceof Object[]) notifSink = (Object[]) f[1];
                            break;
                        case "notifpolicy":
                            if (f[1] instanceof Object[]) notifPolicy = (Object[]) f[1];
                            break;
                        default:
                            break;
                    }
                }
                final Object[] fCore = core;
                final Object fCard = card;
                final Object[] fIsland = island;
                final Object[] fNotifSink = notifSink;
                final Object[] fNotifPolicy = notifPolicy;
                final android.view.View fContainer = container;
                if (fCore != null) step("core", () -> applyCore(fCore));
                if (ctx == null) {
                    // Not a failure: the state carried no context, and the attach path will run
                    // boot when the keyguard comes back.
                    Xp.w(TAG + "no context yet; boot waits for the next container attach");
                } else if (!step("boot", () -> Main.boot(ctx))) {
                    // Everything after this builds on boot; carrying on would run half of a
                    // generation. The pending marker stays (the delayed clear is refused once
                    // inert), so a restart inside the window blocks this build.
                    broken = true;
                }
                if (!broken) {
                    if (fNotifSink != null || fNotifPolicy != null) {
                        // The policy flows the running combine collects become this generation's,
                        // and the mode the state file just loaded goes into them. A build that
                        // handed only the combines (it put the OEM's arrays back) gets them
                        // injected into again.
                        step("notif sink", () -> {
                            Main.sNotificationSink.adopt(fNotifSink, fNotifPolicy,
                                    Fingerprint.sFpAvoid);
                            Main.refreshNotificationSink();
                        });
                    } else {
                        Xp.w(TAG + "no notification sink handover; the bound-mode policy waits"
                                + " for the next collection");
                    }
                    if (fCard != null) step("media card", () -> MediaCard.adopt(fCard, host));
                    if (fIsland != null) {
                        step("island", () -> MiniPlayerRuntime.adopt(
                                fIsland[0] instanceof android.view.View
                                        ? (android.view.View) fIsland[0] : null,
                                fIsland.length > 1 ? fIsland[1] : null,
                                fIsland.length > 2 ? fIsland[2] : null));
                    }
                    if (fContainer != null) {
                        if (!step("container", () -> Main.onContainerAttached(fContainer))) {
                            broken = true;
                        }
                    } else {
                        Xp.log(TAG + "adopted with no clock container; the next attach puts it up");
                    }
                }
            } catch (Throwable t) {
                // Outside every step: a malformed handover, say. Thrown from here it would be an
                // uncaught exception on SystemUI's main thread.
                Xp.w(TAG + "adopt on main failed: " + t);
                broken = true;
            } finally {
                sAdoptingBoot = false;
            }
            if (broken) {
                // What boot and the attach path already put up is taken down again: receivers,
                // session callbacks, our views and the OEM state they moved. Left up, they would
                // keep running half a generation under hooks that already pass through.
                goInert(proc, appContext(state), "the adoption did not finish on the main thread",
                        SystemUiRetire::retireForReload);
                Xp.w(TAG + "adoption did not finish on the main thread; this generation is inert"
                        + " in this process until it restarts");
            }
        });
    }

    /**
     * One adoption step. The steps are independent - a feature that cannot take its state back
     * must not take the rest of the adoption with it - so only the two the others build on
     * (boot, the container attach) are allowed to fail the generation.
     */
    private static boolean step(String what, Runnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            Xp.w(TAG + "adopt step '" + what + "' failed: " + t);
            return false;
        }
    }

    /** The token and the two flags that only the retiring generation knew. */
    private static void applyCore(Object[] core) {
        if (core.length > 0 && core[0] instanceof String) {
            ProbeGuard.adoptToken((String) core[0]);
        }
        if (core.length > 1 && core[1] instanceof Boolean) {
            Main.sVideoWallpaper = (Boolean) core[1];
        }
        if (core.length > 2 && core[2] instanceof Boolean) {
            Main.sDepthHidden = (Boolean) core[2];
        }
    }

    /**
     * Hooks a feature installs the first time it sees a class loader that is not SystemUI's -
     * the control-centre plugin, mostly. That loader was constructed long ago, so the watcher
     * that found it will not fire again; the new generation re-runs the same soft installs
     * against the loader the old handles name. Everything else either re-arms from the attach
     * path or is installed by {@link Main#installSystemUi}.
     */
    private static void backfill(ClassLoader host, List<XposedInterface.HookHandle> old) {
        // A framework class answers BootClassLoader, not null, on Android (Object's included);
        // neither it nor our own loader is a plugin's.
        ClassLoader boot = Object.class.getClassLoader();
        ClassLoader self = HotReload.class.getClassLoader();
        java.util.Set<ClassLoader> others = new java.util.LinkedHashSet<>();
        for (XposedInterface.HookHandle h : old) {
            Executable e = h.getExecutable();
            ClassLoader l = e == null ? null : e.getDeclaringClass().getClassLoader();
            if (l == null || l == host || l == boot || l == self) continue;
            if ("java.lang.BootClassLoader".equals(l.getClass().getName())) continue;
            others.add(l);
        }
        for (ClassLoader l : others) {
            try {
                HyperTweaks.adoptPlugin(l);
            } catch (Throwable t) {
                Xp.w(TAG + "soft-glass backfill from " + l + " failed: " + t);
            }
            try {
                AmapFocus.adopt(l);
            } catch (Throwable t) {
                Xp.w(TAG + "amap-focus backfill from " + l + " failed: " + t);
            }
        }
        // 取餐码's card picture is watched through ImageView itself - a framework class, so the
        // loader test above cannot see it. The card outlives the reload, and its hooks are only
        // installed again when the next code arrives; put them back now if one was ever there.
        if (hasIdPrefix(old, "sysui.pickup.")) {
            try {
                PickupIsland.INSTANCE.install();
                Xp.log(TAG + "pickup island hooks re-armed");
            } catch (Throwable t) {
                Xp.w(TAG + "pickup backfill failed: " + t);
            }
        }
        if (!others.isEmpty()) {
            Xp.log(TAG + "delayed hooks re-armed from " + others.size()
                    + " secondary class loader(s)");
        }
    }

    private static boolean hasIdPrefix(List<XposedInterface.HookHandle> old, String prefix) {
        for (XposedInterface.HookHandle h : old) {
            String id = h.getId();
            if (id != null && id.startsWith(prefix)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- circuit breaker

    /**
     * A marker that identifies the build without asking the package manager: the version code is
     * a constant compiled into the generation that writes it, so it is right in every process and
     * needs no visibility into the module's APK.
     */
    static String pendingMark(long version, String build, long now) {
        return version + "@" + now + "@" + build;
    }

    /** The version code at the front of a pending marker, or -1 for one from an older build. */
    static long versionOf(String mark) {
        if (mark == null) return -1L;
        int at = mark.indexOf('@');
        if (at <= 0) return -1L;
        try {
            return Long.parseLong(mark.substring(0, at));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Called once from Main.boot on a real cold start.
     *
     * <p>A pending marker left behind means the previous hot reload did not settle; the build it
     * names is recorded as blocked in this process. Then the block is reconciled with the build
     * actually installed: it only matters for the build that is still there, so a cold start on
     * a newer build clears it. That is what makes the block finite - the remedy for a blocked
     * process is to install a newer build and restart it.
     */
    static void onBoot(Context ctx) {
        if (sAdoptingBoot || ctx == null) return;
        try {
            File pending = new File(ctx.getFilesDir(), PENDING);
            if (pending.exists()) {
                long age = System.currentTimeMillis() - pending.lastModified();
                String mark = read(pending);
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
                if (age >= 0 && age <= PENDING_WINDOW_MS) {
                    long crashed = versionOf(mark);
                    if (crashed > 0) {
                        writeBlocked(ctx, crashed);
                        Xp.w(TAG + "previous hot reload did not settle (" + mark + ", " + age
                                + "ms ago); build #" + crashed + " is blocked in this process");
                    } else {
                        Xp.w(TAG + "previous hot reload did not settle (" + mark + ", " + age
                                + "ms ago); the marker carries no version, not blocking");
                    }
                }
            }
            long blocked = blockedVersion(ctx);
            long now = BuildConfig.VERSION_CODE;
            if (blocked > 0 && blocked != now) {
                clearBlocked(ctx);
                Xp.log(TAG + "build #" + blocked + " is no longer installed; the block is cleared");
                blocked = -1L;
            }
            sBlocked = blocked > 0 ? String.valueOf(blocked) : "";
        } catch (Throwable t) {
            Xp.w(TAG + "breaker check failed: " + t);
        }
    }

    /**
     * Why this generation may not be hot reloaded, or null. Runs in the check section, before
     * anything is touched: a build that crashed this process on its way in is not tried again,
     * and neither is any other build while that one is still what is installed.
     */
    private static String blockedReason() {
        long blocked = -1L;
        try {
            Context ctx = appContext(null);
            if (ctx != null) blocked = blockedVersion(ctx);
        } catch (Throwable t) {
            Xp.d(TAG + "blocked check could not read the file: " + t);
        }
        if (blocked <= 0 && !sBlocked.isEmpty()) {
            try {
                blocked = Long.parseLong(sBlocked);
            } catch (NumberFormatException ignored) {
            }
        }
        long now = BuildConfig.VERSION_CODE;
        if (blocked > 0 && blocked == now) {
            return "build #" + now + " did not survive a hot reload here; restart the process"
                    + " after installing a newer build";
        }
        return null;
    }

    private static void writePending(Context ctx, String mark) {
        if (ctx == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), PENDING))) {
            out.write(mark.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Xp.w(TAG + "could not write the pending marker: " + t);
        }
    }

    private static void clearPending(Context ctx) {
        if (ctx == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getFilesDir(), PENDING).delete();
        } catch (Throwable t) {
            Xp.w(TAG + "could not clear the pending marker: " + t);
        }
    }

    /** The build recorded as blocked in this process, or -1. One line, overwritten. */
    private static long blockedVersion(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), BLOCKED);
            if (!f.exists()) return -1L;
            return Long.parseLong(read(f).trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void writeBlocked(Context ctx, long version) {
        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getFilesDir(), BLOCKED), false)) {
            out.write((version + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Xp.w(TAG + "could not record the blocked build: " + t);
        }
    }

    private static void clearBlocked(Context ctx) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getFilesDir(), BLOCKED).delete();
        } catch (Throwable t) {
            Xp.w(TAG + "could not clear the blocked build: " + t);
        }
    }

    private static String read(File f) {
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                return n <= 0 ? "?" : new String(b, 0, n, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /** For `op=gen`: whether a previous hot reload left a build blocked. */
    static String status() {
        return "blocked=" + (sBlocked.isEmpty() ? "-" : sBlocked)
                + " gen=" + sGen + " class=" + sClassId + " retired=" + sRetired
                + " adopted=" + (sAdopted.isEmpty() ? "-" : sAdopted)
                + " inert=" + (sInert == null ? "-" : sInert);
    }

    // ---------------------------------------------------------------- hook installer

    /**
     * An old handle with this id and executable, taken out of the pending list. Null outside an
     * adoption.
     */
    static XposedInterface.HookHandle takeOld(String id, Executable target) {
        HandleStore store = sStore;
        return store == null ? null : store.take(id, target);
    }

    static void onReplaced(String id, XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.replaced(id, handle);
    }

    static void onAdded(String id, XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.added(id, handle);
    }

    /** A replace that failed: leave the old handle for the final unhook, and install anew. */
    static void keepOld(XposedInterface.HookHandle handle) {
        HandleStore store = sStore;
        if (store != null) store.keep(handle);
    }

    static String labelOf(XposedInterface.HookHandle h) {
        return label(h.getExecutable(), h.getId());
    }

    private static void unhook(XposedInterface.HookHandle h) {
        try {
            h.unhook();
        } catch (Throwable t) {
            Xp.w(TAG + "unhook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- host loader

    /**
     * The host's class loader, validated rather than assumed.
     *
     * <p>The first run took the first non-null loader off the old handles, and it could not load
     * the player's own classes - adoption then unhooked the build hook and installed nothing.
     * Candidates are tried against a class the host has, most certain first: the running
     * Application's loader, the loader LSPosed gives the module (its parent is the host's), the
     * saved state's, then whatever the old handles name. Every candidate is logged with the
     * answer.
     */
    private static ClassLoader hostLoader(String proc, HotReloadState.State state,
                                          List<XposedInterface.HookHandle> old) {
        String probe = probeClass(proc);
        List<ClassLoader> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        addCandidate(candidates, names, appLoader(), "application");
        ClassLoader module = HotReload.class.getClassLoader();
        addCandidate(candidates, names, module == null ? null : module.getParent(), "module.parent");
        if (state != null) addCandidate(candidates, names, state.hostLoader, "saved");
        for (XposedInterface.HookHandle h : old) {
            Executable e = h.getExecutable();
            addCandidate(candidates, names,
                    e == null ? null : e.getDeclaringClass().getClassLoader(),
                    "handle:" + (e == null ? "?" : e.getDeclaringClass().getName()));
        }
        for (int i = 0; i < candidates.size(); i++) {
            ClassLoader cl = candidates.get(i);
            if (probe == null) {
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl + " (unvalidated)");
                return cl;
            }
            try {
                cl.loadClass(probe);
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl
                        + " (loads " + probe + ")");
                return cl;
            } catch (Throwable t) {
                Xp.log(TAG + "host loader from " + names.get(i) + " = " + cl
                        + " cannot load " + probe + ": " + t);
            }
        }
        Xp.w(TAG + "no host loader can load " + probe + "; tried " + names);
        return null;
    }

    /** A class each supported process is certain to have; null means "take the first loader". */
    private static String probeClass(String proc) {
        if (AppleLyrics.PKG.equals(proc)) {
            return "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel";
        }
        if ("com.android.systemui".equals(proc)) {
            return "com.android.systemui.keyguard.KeyguardService";
        }
        if ("com.miui.miwallpaper".equals(proc)) {
            return "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl";
        }
        // 背屏's classes are obfuscated, and the other three processes' entry points change with
        // their apps. The Application's loader is the first candidate anyway, and the old-handle
        // candidate that once misled us is last; taking the first loader is right by construction.
        return null;
    }

    private static ClassLoader appLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app == null ? null : app.getClass().getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addCandidate(List<ClassLoader> out, List<String> names,
                                     ClassLoader cl, String name) {
        if (cl == null || out.contains(cl)) return;
        out.add(cl);
        names.add(name);
    }

    // ---------------------------------------------------------------- labels

    private static String label(Executable e, String id) {
        if (e == null) return String.valueOf(id);
        return id + "@" + e.getDeclaringClass().getSimpleName() + "." + e.getName();
    }
}
