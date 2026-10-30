// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 指纹快捷功能: the menu HyperOS opens when the finger stays on the sensor after unlocking
 * (MiuiGxzwQuickOpenView), with its timing and its items made the user's.
 *
 * The OEM's chain, read from 17.03.260226 (see the fingerprint-quick-open memory):
 * dismissGxzwView posts mShowRunnable 500ms out (1000 when the launcher is not on top), the
 * runnable puts the window up and starts a 600ms growing circle, and the circle's end calls
 * startShowQuickOpenItemAnimation, which lays five hard-coded items along an arc.
 *
 * What changes and where:
 * - The wait is re-posted from after dismissGxzwView, with the runnable the OEM itself posted,
 *   so its own dismiss still removes it. The circle's length is set on the animator once the
 *   runnable has started it - no hook on the R8-named lambda that builds it.
 * - The items are swapped after startShowQuickOpenItemAnimation has done the rest of its work
 *   (the blur, the window flags, the haptic, the tip). Each custom item is a real
 *   IQuickOpenItem from generateQuickOpenItem - WechatScanItem has no constructor of its own
 *   left after R8, so the factory is the only way to get one - with our icon in its view and
 *   its getters answered from the registry below. The OEM's touch code, its dismiss animation
 *   and, for apps, its launch (app lock, the market fallback) then work on them unchanged.
 *
 * Every hook stands on its own: a build that renamed one loses that part and keeps the menu.
 */
final class FingerprintQuick {
    private FingerprintQuick() {}

    private static final String PKG = "com.miui.keyguard.biometrics.fod.";

    /** The shell every custom item is built as; see the class comment. */
    private static final int SHELL_ID = 1002;

    /** What the OEM's 500 + 600 come to, and how it is split back up. */
    static final int DEFAULT_TOTAL_MS = 1100;
    static final int MAX_TOTAL_MS = 1500;

    /** The sensor's cell and three whole rings round it; see CELLS. */
    static final int MAX_ITEMS = 37;

    /** Centre to centre between neighbouring cells: Apple's icons all but touch. */
    private static final float PITCH_DP = 62f;

    /**
     * The icons at full size: the OEM's own 52dp on the 62dp pitch (2026-10-21, the user: bigger
     * again, full but not touching - so the chosen one grows less, see CHOSEN_SCALE).
     */
    private static final float ICON_SCALE = 1f;

    /** The chosen icon's grow: to 60dp, just inside the 62dp pitch, where the OEM's 33/26 overlapped. */
    private static final float CHOSEN_SCALE = 1.15f;

    /** The disc behind the chosen icon: a 3dp rim round its 60dp, where the OEM's is 40dp. */
    private static final float DISC_RADIUS_DP = 33f;

    /**
     * The window the honeycomb is seen through, Apple Watch's screen: a circle centred on the
     * sensor (2026-10-21, the user: round, then smaller again - 40dp, the middle icon alone). Icons in
     * the middle of it are full size; toward its edge they shrink and close up, and they never
     * leave it.
     */
    private static final float VIEW_RADIUS_DP = 40f;


    /**
     * How far out from the window's edge an icon's size halves, roughly: past the edge each ring
     * is smaller than the last - about 0.76 for the first, 0.35-0.45 the second, 0.16-0.22 the
     * third - rather
     * than everything outside being crushed to dots against the edge (2026-10-21, the user: the
     * second and third rows' sizes were wrong).
     */
    private static final float FALLOFF_PITCHES = 1.3f;

    /**
     * The honeycomb, centred on the sensor - Apple Watch's grid: horizontal rows, every other
     * row offset by half a cell, the middle filled (2026-10-21, the user: nothing empty under the
     * finger). {x in pitches, row, ring}, in the order they fill: the sensor's own cell, then
     * ring by ring, each from the top down and out from the middle.
     */
    private static final float[][] CELLS = buildCells();

    private static float[][] buildCells() {
        List<float[]> cells = new ArrayList<>();
        for (int q = -3; q <= 3; q++) {
            for (int row = -3; row <= 3; row++) {
                int ring = Math.max(Math.abs(q), Math.max(Math.abs(row), Math.abs(q + row)));
                if (ring <= 3) cells.add(new float[] {q + row / 2f, row, ring});
            }
        }
        Collections.sort(cells, (a, b) -> {
            if (a[2] != b[2]) return Float.compare(a[2], b[2]);
            if (a[1] != b[1]) return Float.compare(a[1], b[1]);
            if (Math.abs(a[0]) != Math.abs(b[0])) return Float.compare(Math.abs(a[0]), Math.abs(b[0]));
            return Float.compare(a[0], b[0]);
        });
        return cells.toArray(new float[0][]);
    }

    private static final String PREFS = "hyperchanger_hmc_next_fpquick";
    private static final String KEY = "config";

    /**
     * 「启用指纹快捷功能自定义」: off leaves the OEM's menu exactly as it ships - its timing, its
     * five items on their arc. Absent from a config written before the switch existed, which
     * was one somebody customised, so absent there reads as on; no config at all reads as off.
     */
    static volatile boolean sOn;

    static volatile int sTotalMs = DEFAULT_TOTAL_MS;

    /** Whether moving off the sensor during the wait opens the menu straight away. */
    static volatile boolean sMoveShows;

    /**
     * 人脸解锁后按住指纹也弹出. With face unlock set to stay on the lock screen, a finger on the
     * sensor after the face has passed dismisses the keyguard there and then - the fingerprint is
     * never read - and dismissGxzwView arms the menu only when the unlock was the fingerprint's
     * (2026-10-21, from the user's log: onTouchDown, onKeyguardHide, 已取消指纹操作). On, the
     * finger still on the sensor at that moment is enough. On when absent.
     */
    static volatile boolean sFaceToo = true;

    /**
     * Set just around dismissGxzwView, for its first isUnlockByGxzw only: that one is the menu's
     * condition. The second, further down, decides whether the fingerprint's bright spot is
     * taken off the screen, and must keep its real answer.
     */
    private static boolean sForceGxzwOnce;

    /**
     * Either half set on its own, -1 for its share of the total. For tuning the two apart
     * (2026-10-21: the user wanted the circle at 50ms with the wait left alone).
     */
    static volatile int sWaitMs = -1;
    static volatile int sLoadingMs = -1;

    /** The user's items in order, or null for the OEM's own list untouched. */
    static volatile List<JSONObject> sItems;

    private static volatile boolean sLoaded;
    private static Method sGenerate;
    private static Class<?> sUtils;
    private static Class<?> sUtil;
    private static boolean sPointerHooked;
    /** The icon view whose listener is hooked: handed to the next generation (adoptPointer). */
    private static Object sPointerIcon;

    /** Custom item -> what it shows and does. Identity keys: the OEM items do not override equals. */
    private static final Map<Object, Resolved> sCustom =
            Collections.synchronizedMap(new WeakHashMap<Object, Resolved>());

    /** Entry JSON -> resolved, so icons are looked up once rather than on every long-press. */
    private static final Map<String, Resolved> sResolved =
            Collections.synchronizedMap(new java.util.HashMap<String, Resolved>());

    private static String sLastOpen = "";

    static final class Resolved {
        String title;
        String subtitle;
        Drawable icon;
        /** Handed to the OEM's launch when set; otherwise run() does the work. */
        Intent intent;
        JSONObject entry;
    }

    // ---------------------------------------------------------------- settings

    private static void ensureLoaded(Context c) {
        if (sLoaded || c == null) return;
        try {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            parse(p.getString(KEY, null));
            sLoaded = true;
        } catch (Throwable t) {
            // Credential storage before the first unlock: try again next time.
            Xp.d(Main.TAG + "fpquick: settings not readable yet: " + t);
        }
    }

    private static void parse(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) {
            sOn = false;
            sTotalMs = DEFAULT_TOTAL_MS;
            sMoveShows = false;
            sFaceToo = true;
            sWaitMs = -1;
            sLoadingMs = -1;
            sItems = null;
            return;
        }
        JSONObject o = new JSONObject(raw);
        sOn = o.optBoolean("on", true);
        sTotalMs = Math.max(0, Math.min(MAX_TOTAL_MS, o.optInt("total", DEFAULT_TOTAL_MS)));
        sMoveShows = o.optBoolean("move", false);
        sFaceToo = o.optBoolean("face", true);
        sWaitMs = o.has("wait") ? Math.max(0, Math.min(MAX_TOTAL_MS, o.optInt("wait"))) : -1;
        sLoadingMs = o.has("loading") ? Math.max(0, Math.min(MAX_TOTAL_MS, o.optInt("loading"))) : -1;
        JSONArray a = o.optJSONArray("items");
        if (a == null) {
            sItems = null;
        } else {
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.has("t")) items.add(e);
            }
            sItems = items;
        }
    }

    static String configJson(Context c) {
        ensureLoaded(c);
        return configJson();
    }

    static String configJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("on", sOn);
            o.put("total", sTotalMs);
            o.put("move", sMoveShows);
            o.put("face", sFaceToo);
            if (sWaitMs >= 0) o.put("wait", sWaitMs);
            if (sLoadingMs >= 0) o.put("loading", sLoadingMs);
            List<JSONObject> items = sItems;
            if (items != null) {
                JSONArray a = new JSONArray();
                for (JSONObject e : items) a.put(e);
                o.put("items", a);
            }
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** op fpquick --es json '{...}': applied, saved, and the state said back. */
    static String apply(Context c, String raw) throws Exception {
        ensureLoaded(c);
        if (raw != null) {
            parse(raw);
            sResolved.clear();
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, configJson()).apply();
            sLoaded = true;
            Xp.log(Main.TAG + "fpquick config " + configJson());
        }
        return describe();
    }

    /** Whether the hooks found the quick-open view at all; false on a phone without it. */
    static boolean installed() {
        return sGenerate != null;
    }

    /**
     * The system's own switch for the menu, the one 设置 → 快捷功能 shows. User 0 because that is
     * the user the OEM reads it for (getIntForUser(..., 0) in MiuiGxzwManager).
     */
    static boolean systemSwitch(Context c) {
        return android.provider.Settings.Secure.getInt(c.getContentResolver(), "fod_quick_open", 1) == 1;
    }

    static void setSystemSwitch(Context c, boolean on) {
        android.provider.Settings.Secure.putInt(c.getContentResolver(), "fod_quick_open", on ? 1 : 0);
        Xp.log(Main.TAG + "fpquick system switch " + (on ? "on" : "off"));
    }

    /**
     * An app's shortcuts for the settings page's picker: the app cannot ask LauncherApps itself,
     * SystemUI holds ACCESS_SHORTCUTS. ids, labels, and icon0..N as 96px PNGs.
     */
    static android.os.Bundle shortcuts(Context c, String pkg) {
        android.os.Bundle out = new android.os.Bundle();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try {
            LauncherApps la = c.getSystemService(LauncherApps.class);
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setPackage(pkg)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> got = la.getShortcuts(q, currentUser());
            int dpi = c.getResources().getDisplayMetrics().densityDpi;
            if (got != null) {
                for (ShortcutInfo s : got) {
                    if (!s.isEnabled() || ids.contains(s.getId())) continue;
                    CharSequence label = s.getShortLabel();
                    if (label == null) label = s.getLongLabel();
                    if (label == null) continue;
                    try {
                        Drawable d = la.getShortcutIconDrawable(s, dpi);
                        if (d != null) out.putByteArray("icon" + ids.size(), png(d, 96));
                    } catch (Throwable ignored) {
                    }
                    ids.add(s.getId());
                    labels.add(label.toString());
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick: shortcuts of " + pkg + " failed: " + t);
        }
        out.putStringArray("ids", ids.toArray(new String[0]));
        out.putStringArray("labels", labels.toArray(new String[0]));
        return out;
    }

    /**
     * Every package with at least one usable shortcut, so the picker for 「App 快捷方式」 lists
     * only apps there is something to pick in. One query for all of them: no package set.
     */
    static android.os.Bundle shortcutApps(Context c) {
        java.util.LinkedHashSet<String> pkgs = new java.util.LinkedHashSet<>();
        try {
            LauncherApps la = c.getSystemService(LauncherApps.class);
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> got = la.getShortcuts(q, currentUser());
            if (got != null) {
                for (ShortcutInfo s : got) {
                    if (s.isEnabled() && (s.getShortLabel() != null || s.getLongLabel() != null)) {
                        pkgs.add(s.getPackage());
                    }
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick: shortcut apps failed: " + t);
        }
        android.os.Bundle out = new android.os.Bundle();
        out.putStringArray("pkgs", pkgs.toArray(new String[0]));
        return out;
    }

    private static byte[] png(Drawable d, int px) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(canvas);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
        b.recycle();
        return bytes.toByteArray();
    }

    static String describe() {
        return "on=" + sOn + " total=" + sTotalMs + " wait=" + waitMs() + " loading=" + loadingMs()
                + " move=" + sMoveShows
                + " items=" + (sItems == null ? "native" : String.valueOf(sItems.size()))
                + " lastOpen=" + sLastOpen
                + "\n" + configJson();
    }

    static int waitMs() {
        return sWaitMs >= 0 ? sWaitMs : Math.round(sTotalMs * 5f / 11f);
    }

    static int loadingMs() {
        return sLoadingMs >= 0 ? sLoadingMs : sTotalMs - Math.round(sTotalMs * 5f / 11f);
    }

    // ---------------------------------------------------------------- hooks

    static void install(ClassLoader cl) {
        Class<?> view;
        try {
            view = Xp.findClass(PKG + "MiuiGxzwQuickOpenView", cl);
            sUtils = Xp.findClass(PKG + "MiuiGxzwUtils", cl);
            Class<?> util = Xp.findClass(PKG + "MiuiGxzwQuickOpenUtil", cl);
            sUtil = util;
            for (Method m : util.getDeclaredMethods()) {
                if (m.getName().equals("generateQuickOpenItem") && m.getParameterCount() == 4) {
                    m.setAccessible(true);
                    sGenerate = m;
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick: no quick-open view on this build, nothing hooked: " + t);
            return;
        }
        hookTiming(cl);
        hookItems(view);
        hookShell(cl);
        hookLaunch(view);
    }

    private static void hookTiming(ClassLoader cl) {
        try {
            Class<?> manager = Xp.findClass(PKG + "MiuiGxzwManager", cl);
            Xp.hookAll("sysui.fpq.dismiss_view", manager, "dismissGxzwView", chain -> {
                Object icon = null;
                Object qv = null;
                boolean before = false;
                boolean forced = false;
                try {
                    icon = Xp.getObjectField(chain.getThisObject(), "mMiuiGxzwIconView");
                    qv = icon == null ? null : Xp.getObjectField(icon, "mMiuiGxzwQuickOpenView");
                    before = qv != null && bool(qv, "mShowed");
                    if (icon != null) {
                        ensureLoaded(((View) icon).getContext());
                        forced = sOn && sFaceToo && !before
                                && bool(icon, "isCatchDownEvent") && bool(icon, "mTouchDown")
                                && !Boolean.TRUE.equals(Xp.callMethod(chain.getThisObject(), "isUnlockByGxzw"));
                    }
                } catch (Throwable ignored) {
                }
                sForceGxzwOnce = forced;
                Object r;
                try {
                    r = chain.proceed();
                } finally {
                    sForceGxzwOnce = false;
                }
                if (forced && qv != null && bool(qv, "mShowed")) {
                    // The finger that unlocked is the face, not one of the prints: no print id
                    // for the OEM to wave an app lock through with, so a locked app still asks.
                    try {
                        Xp.setObjectField(qv, "mFingerID", 0);
                    } catch (Throwable ignored) {
                    }
                    Xp.log(Main.TAG + "fpquick: menu armed after a non-fingerprint unlock");
                }
                if (qv != null && !before) {
                    try {
                        if (bool(qv, "mShowed")) retime(qv, icon);
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "fpquick: retime failed, the OEM timing stands: " + t);
                    }
                }
                return r;
            });
            // The menu's own check, answered yes once while dismissGxzwView runs for a finger
            // held on the sensor through a face unlock; see sForceGxzwOnce.
            Xp.hookAll("sysui.fpq.unlock_by_gxzw", manager, "isUnlockByGxzw", chain -> {
                if (sForceGxzwOnce) {
                    sForceGxzwOnce = false;
                    return true;
                }
                return chain.proceed();
            });
            Xp.log(Main.TAG + "fpquick timing hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick timing hook failed, the OEM timing stands: " + t);
        }
    }

    /** Called just after dismissGxzwView armed the menu: post its runnable again on our clock. */
    private static void retime(Object qv, Object icon) {
        Context c = ((View) qv).getContext();
        ensureLoaded(c);
        if (!sOn) return;
        // The OEM's TalkBack wait is longer on purpose; leave it.
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        if (am != null && am.isTouchExplorationEnabled()) return;
        android.os.Handler h = (android.os.Handler) Xp.getObjectField(qv, "mHandler");
        Runnable show = (Runnable) Xp.getObjectField(qv, "mShowRunnable");
        h.removeCallbacks(show);
        int wait = waitMs();
        h.postDelayed(show, wait);
        fixLoading(qv, false, wait + 2, 12);
        if (icon != null) hookPointer(icon);
    }

    /**
     * Once the runnable has started the circle: give it our length, or skip it. Polled because
     * the OEM starts it from a View.postDelayed, which waits for the window to attach.
     */
    private static void fixLoading(final Object qv, final boolean skip, int delay, final int tries) {
        Main.main().postDelayed(() -> {
            try {
                if (!bool(qv, "mShowed")) return;
                ObjectAnimator a = (ObjectAnimator) Xp.getObjectField(qv, "mLoadingAnimator");
                if (a == null) {
                    if (tries > 0 && ((List<?>) Xp.getObjectField(qv, "mQuickOpenItemList")).isEmpty()) {
                        fixLoading(qv, skip, 8, tries - 1);
                    }
                    return;
                }
                hideLoading(qv);
                if (skip || loadingMs() <= 0) {
                    // The same two calls the OEM's own move-off path makes.
                    a.cancel();
                    Xp.callMethod(qv, "startShowQuickOpenItemAnimation");
                } else {
                    a.setDuration(loadingMs());
                }
            } catch (Throwable t) {
                Xp.w(Main.TAG + "fpquick: loading fix failed: " + t);
            }
        }, delay);
    }

    /**
     * 「手指一动立刻弹出」: the icon view's pointer listener, found from the live object because
     * its class is an R8-numbered anonymous one. onPointerEvent is an interface method, so its
     * name survives.
     */
    private static synchronized void hookPointer(Object icon) {
        if (sPointerHooked) return;
        sPointerHooked = true;
        sPointerIcon = icon;
        try {
            Object listener = Xp.getObjectField(icon, "listener");
            Xp.hookAll("sysui.fpq.pointer", listener.getClass(), "onPointerEvent", chain -> {
                try {
                    Object a0 = chain.getArgs().get(0);
                    int action = a0 instanceof MotionEvent ? ((MotionEvent) a0).getActionMasked() : -1;
                    if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                        Object qv = Xp.getObjectField(icon, "mMiuiGxzwQuickOpenView");
                        MotionEvent ev = (MotionEvent) a0;
                        int idx = 0;
                        try {
                            idx = (Integer) Xp.callMethod(icon, "findFodTouchEventIndex", ev);
                        } catch (Throwable ignored) {
                        }
                        final float x = ev.getX(idx), y = ev.getY(idx);
                        if (sMoveShows && action == MotionEvent.ACTION_MOVE && waiting(qv)) {
                            Main.main().post(() -> moveOff(qv, x, y));
                        } else if (!sBubbles.isEmpty()) {
                            Main.main().post(() -> focus(x, y));
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            Xp.log(Main.TAG + "fpquick pointer hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick pointer hook failed, no fisheye and moving will not skip the wait: " + t);
        }
    }

    /** Armed but its window not up yet - the stretch the OEM gives no way to cut short. */
    private static boolean waiting(Object qv) {
        return qv != null && bool(qv, "mShowed") && ((View) qv).getVisibility() != View.VISIBLE;
    }

    private static void moveOff(Object qv, float x, float y) {
        try {
            if (!waiting(qv)) return;
            RectF fast = (RectF) Xp.getObjectField(qv, "mFastRect");
            if (fast != null && fast.contains(x, y)) return;
            android.os.Handler h = (android.os.Handler) Xp.getObjectField(qv, "mHandler");
            Runnable show = (Runnable) Xp.getObjectField(qv, "mShowRunnable");
            h.removeCallbacks(show);
            show.run();
            fixLoading(qv, true, 2, 12);
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick: move-off show failed: " + t);
        }
    }

    private static void hookItems(Class<?> view) {
        try {
            Xp.hookAll("sysui.fpq.show_anim", view, "startShowQuickOpenItemAnimation", chain -> {
                Object r = chain.proceed();
                try {
                    replaceItems(chain.getThisObject());
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "fpquick: item swap failed, the OEM items stay: " + t);
                }
                return r;
            });
            // Into the OEM's dismiss, its 150ms flight back to the sensor, from where the
            // fisheye left each icon: the icons stop ignoring it from here.
            Xp.hookAll("sysui.fpq.dismiss", view, "dismiss", chain -> {
                for (Bubble b : sBubbles) b.release();
                sBubbles.clear();
                if (sDisc != null && sDisc.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sDisc.getParent()).removeView(sDisc);
                }
                sDisc = null;
                return chain.proceed();
            });
            Xp.log(Main.TAG + "fpquick items hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick items hook failed, the OEM items stay: " + t);
        }
    }

    /** The shell's getters, answered for the items we built. */
    private static void hookShell(ClassLoader cl) {
        try {
            Class<?> shell = Xp.findClass(PKG + "item.WechatScanItem", cl);
            Xp.hookAll("sysui.fpq.title", shell, "getTitle", chain -> {
                Resolved r = sCustom.get(chain.getThisObject());
                return r != null ? r.title : chain.proceed();
            });
            Xp.hookAll("sysui.fpq.subtitle", shell, "getSubTitle", chain -> {
                Resolved r = sCustom.get(chain.getThisObject());
                return r != null ? r.subtitle : chain.proceed();
            });
            Xp.hookAll("sysui.fpq.tag", shell, "getTag", chain -> {
                Resolved r = sCustom.get(chain.getThisObject());
                return r != null ? "HMC/" + r.entry.optString("t") : chain.proceed();
            });
            Xp.hookAll("sysui.fpq.intent", shell, "getIntent", chain -> {
                Resolved r = sCustom.get(chain.getThisObject());
                if (r == null) return chain.proceed();
                return r.intent != null ? r.intent : new Intent();
            });
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick shell hook failed, custom items will not be built: " + t);
            sGenerate = null;
        }
    }

    private static void hookLaunch(Class<?> view) {
        try {
            Xp.hookAll("sysui.fpq.item_touch_up", view, "handleQucikOpenItemTouchUp", chain -> {
                Object item = chain.getArgs().get(0);
                Resolved r = sCustom.get(item);
                Object qv = chain.getThisObject();
                if (r == null) {
                    sLastOpen = "native";
                    return chain.proceed();
                }
                sLastOpen = r.entry.toString();
                if (r.intent != null) return chain.proceed();
                try {
                    run(((View) qv).getContext(), r);
                } catch (Throwable t) {
                    Xp.w(Main.TAG + "fpquick: open " + r.entry + " failed: " + t);
                }
                return null;
            });
            Xp.log(Main.TAG + "fpquick launch hooked");
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick launch hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- items

    /** The icons on screen now, in fill order; main thread only. */
    private static final List<Bubble> sBubbles = new ArrayList<>();
    private static float sPitch;

    /**
     * Where the sensor is, which is where the chosen icon is: the finger drags the whole
     * honeycomb, as on the watch, and whichever icon it brings over the sensor is the one a
     * release opens (the user's choice, 2026-10-21).
     */
    private static float sSensorX, sSensorY;

    /**
     * The icon over the sensor, chosen from the moment the menu is up: a release without moving
     * opens the one under the finger (the user, 2026-10-21). Only dragging an empty place onto
     * the sensor chooses nothing.
     */
    private static Bubble sChosen;

    /**
     * The OEM's selection feedback, its shape kept (the user, 2026-10-21): the chosen icon grows
     * in 100ms with a translucent white disc behind it, both sized here rather than by the OEM's
     * dimens. The OEM paints its disc at the item's cell in drawChild, which is not where a
     * dragged icon is, so its own is switched off and this view, the same colour, follows the
     * chosen icon instead.
     */
    private static View sDisc;

    /** The touch area every icon but the chosen one has: none. */
    private static final android.graphics.Rect EVERYWHERE = new android.graphics.Rect(-10000, -10000, 20000, 20000);

    /** The OEM's own list, for when the user has not made one: its order, our honeycomb. */
    private static List<JSONObject> nativeDefaults() throws Exception {
        Field f = sUtil.getDeclaredField("DEFAULT_ITEM_ID_LIST");
        f.setAccessible(true);
        List<JSONObject> out = new ArrayList<>();
        for (int id : (int[]) f.get(null)) out.add(new JSONObject().put("t", "native").put("id", id));
        return out;
    }

    private static void hideLoading(Object qv) {
        try {
            View v = (View) Xp.getObjectField(qv, "mQuickLoadingView");
            // Not alpha: the OEM's dismiss fades it from 1 to 0 by name, which would show it.
            if (v != null) v.setVisibility(View.INVISIBLE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Retirement: the honeycomb's views and the selection disc out of the OEM's group. The quick
     * menu is rebuilt on every open, so nothing else of it has to be put back; the OEM's own
     * item list was emptied for the honeycomb and is rebuilt there too.
     */
    /** For the hot-reload handover: null until the first long press hooked the listener. */
    static Object pointerIcon() {
        return sPointerIcon;
    }

    /**
     * The new generation's half: the old one had the pointer listener hooked, and the press
     * that would hook it again is the one whose first moves it exists for. So it goes back on
     * now, inside the adoption, where it replaces the old handle rather than reporting it missing.
     */
    static void adoptPointer(Object icon) {
        if (icon != null) hookPointer(icon);
    }

    static void retire() {
        if (sDisc != null && sDisc.getParent() instanceof ViewGroup) {
            ((ViewGroup) sDisc.getParent()).removeView(sDisc);
        }
        sDisc = null;
        for (Bubble b : sBubbles) {
            if (b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b);
        }
        sBubbles.clear();
        sChosen = null;
        sCustom.clear();
    }

    private static void replaceItems(Object qv) throws Exception {
        if (sGenerate == null || !sOn) return;
        List<JSONObject> items = sItems;
        if (items == null) items = nativeDefaults();
        ViewGroup g = (ViewGroup) qv;
        Context c = g.getContext();
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) Xp.getObjectField(qv, "mQuickOpenItemList");
        for (Object it : list) {
            View v = (View) Xp.callMethod(it, "getView");
            v.setVisibility(View.INVISIBLE);
            if (v.getParent() == g) g.removeView(v);
        }
        list.clear();
        sCustom.clear();
        sBubbles.clear();
        hideLoading(qv);
        // The OEM's disc and grow, carried over to the dragged icons (see sDisc).
        Xp.setObjectField(qv, "mSelectBackgroundRadius", 0f);
        float discR = DISC_RADIUS_DP * c.getResources().getDisplayMetrics().density;
        if (sDisc != null && sDisc.getParent() instanceof ViewGroup) {
            ((ViewGroup) sDisc.getParent()).removeView(sDisc);
        }
        android.graphics.drawable.ShapeDrawable disc =
                new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        disc.getPaint().setColor(872415231);
        sDisc = new View(c);
        sDisc.setBackground(disc);
        sDisc.setVisibility(View.INVISIBLE);
        int discPx = Math.round(discR * 2);
        FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams(discPx, discPx);
        discLp.gravity = 51;
        g.addView(sDisc, 0, discLp);
        Gen.ownView(g, sDisc);

        // One entry per cell, in CELLS order: {"t":"empty"} keeps a place open, and an item
        // this phone cannot show (an app since uninstalled) leaves its place open too, so
        // nothing moves into a cell the user did not put it in.
        List<Object> picks = new ArrayList<>();
        int n = Math.min(items.size(), MAX_ITEMS);
        int shown = 0;
        for (int i = 0; i < n; i++) {
            JSONObject e = items.get(i);
            Object pick = "empty".equals(e.optString("t")) ? null : pick(c, e);
            picks.add(pick);
            if (pick != null) shown++;
        }
        if (shown == 0) return;

        Method size = sUtils.getDeclaredMethod("caculateGxzwIconSize", Context.class);
        size.setAccessible(true);
        size.invoke(null, c);
        float cx = staticInt("GXZW_ICON_WIDTH") / 2 + staticInt("GXZW_ICON_X");
        float cy = staticInt("GXZW_ICON_HEIGHT") / 2 + staticInt("GXZW_ICON_Y");
        float itemR = (Float) Xp.getObjectField(qv, "mItemRadius");
        boolean rtl = (c.getResources().getConfiguration().screenLayout & 192) == 128;
        sPitch = PITCH_DP * c.getResources().getDisplayMetrics().density;
        float rowH = sPitch * (float) Math.sqrt(3) / 2f;

        sSensorX = cx;
        sSensorY = cy;
        sChosen = null;
        for (int i = 0; i < n; i++) {
            if (picks.get(i) == null) continue;
            float x = cx + (rtl ? -CELLS[i][0] : CELLS[i][0]) * sPitch;
            float y = cy + CELLS[i][1] * rowH;
            RectF rect = new RectF(x - itemR, y - itemR, x + itemR, y + itemR);
            // Empty for now: the OEM's touch code finds the chosen icon by this, and focus()
            // gives the whole screen to whichever icon sits on the sensor.
            Region region = new Region();
            Object item = build(c, picks.get(i), rect, region);
            list.add(item);
            View v = (View) Xp.callMethod(item, "getView");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) rect.width(), (int) rect.height());
            lp.gravity = 51;
            lp.leftMargin = (int) rect.left;
            lp.topMargin = (int) rect.top;
            g.addView(v, lp);
            Gen.ownView(g, v);
            v.setVisibility(View.VISIBLE);
            if (v instanceof Bubble) {
                ((Bubble) v).place(x, y, cx - x, cy - y, (int) CELLS[i][2]);
                ((Bubble) v).region = region;
                sBubbles.add((Bubble) v);
            }
        }
        // The finger is on the sensor: the honeycomb where it was laid out, its middle full
        // size and its outer ring closing up at the window's edge.
        focus(cx, cy);
        for (Bubble b : sBubbles) {
            // Out from the finger ring by ring, Apple Watch's own zoom out of the centre.
            b.enter(b.ring * 30L);
        }
        Xp.setBooleanField(qv, "mItemsExpanded", true);
        Xp.d(Main.TAG + "fpquick: " + shown + " items in the honeycomb");
    }

    /**
     * Apple Watch's honeycomb, frame by frame from the user's reference (2026-10-21): the finger
     * drags the whole grid one to one, and the size of every icon is a matter of where it now is
     * in the window - full size inside it, and past its edge each ring smaller than the one
     * inside it and drawn in against it. The window is a circle on the sensor; outside it the
     * size decays exponentially with the distance and the place is that size's integral, so
     * the rings pack tight instead of leaving gaps.
     *
     * The icon dragged onto the sensor is the chosen one. The OEM's touch code is left to find
     * it: the chosen icon's area is the whole screen and every other icon's is nothing, so its
     * title, its tick and its launch on release all work as they always did.
     */
    private static void focus(float fx, float fy) {
        if (sBubbles.isEmpty()) return;
        float ox = fx - sSensorX, oy = fy - sSensorY;
        // Past the honeycomb's own size the drag meets resistance, as the watch's grid does.
        float limit = sPitch * 3f;
        ox = rubber(ox, limit);
        oy = rubber(oy, limit);
        float radius = VIEW_RADIUS_DP * sPitch / PITCH_DP;
        float falloff = sPitch * FALLOFF_PITCHES;
        float catchR = sPitch / (float) Math.sqrt(3);
        Bubble chosen = null;
        float chosenD = Float.MAX_VALUE;
        for (Bubble b : sBubbles) {
            float px = b.cellX + ox - sSensorX, py = b.cellY + oy - sSensorY;
            double dist = Math.hypot(px, py);
            float k = 1f;
            float size = 1f;
            if (dist > radius) {
                // Size decays with the distance past the edge, and the distance out is the
                // integral of the size, so each ring sits right against the one inside it.
                double out = (dist - radius) / falloff;
                size = (float) Math.exp(-out);
                double mapped = radius + falloff * (1 - Math.exp(-out));
                k = (float) (mapped / dist);
            }
            b.lens = size * ICON_SCALE;
            // Only the smallest, a long way out, fade away.
            b.fade = Math.max(0f, Math.min(1f, (size - 0.08f) / 0.17f));
            b.pushX = sSensorX + px * k - b.cellX;
            b.pushY = sSensorY + py * k - b.cellY;
            b.apply();
            float d = (float) Math.hypot(px, py);
            if (d < catchR && d < chosenD) {
                chosen = b;
                chosenD = d;
            }
        }
        if (chosen != sChosen) {
            if (sChosen != null) {
                if (sChosen.region != null) sChosen.region.setEmpty();
                sChosen.choose(false);
            }
            sChosen = chosen;
            if (chosen != null) {
                if (chosen.region != null) chosen.region.set(EVERYWHERE);
                chosen.choose(true);
            }
            if (sDisc != null) sDisc.setVisibility(chosen != null ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Beyond [limit] the drag goes on at a fraction, easing into a wall. */
    private static float rubber(float v, float limit) {
        float a = Math.abs(v);
        if (a <= limit) return v;
        float over = a - limit;
        float eased = limit + limit * 0.35f * (1f - 1f / (over / (limit * 0.35f) + 1f));
        return Math.signum(v) * eased;
    }

    /** A damped spring as a curve: 0 to 1 with Apple's small overshoot (damping 0.68). */
    static final android.animation.TimeInterpolator SPRING = t -> {
        double time = t * 0.5, zeta = 0.68, omega = 2 * Math.PI / 0.36;
        double wd = omega * Math.sqrt(1 - zeta * zeta);
        double decay = Math.exp(-zeta * omega * time);
        return (float) (1 - decay * (Math.cos(wd * time) + zeta * omega / wd * Math.sin(wd * time)));
    };

    /**
     * One icon of the honeycomb. It ignores the OEM's own scale and translation - its 100ms grow
     * on the chosen icon would fight the fisheye frame by frame - and takes only what [apply]
     * works out, until [release] hands it back for the OEM's dismiss.
     */
    static final class Bubble extends ImageView {
        float cellX, cellY;
        int ring;
        /** Where it flies out from: the sensor, relative to its cell. */
        float fromX, fromY;
        /** The lens's size and place for it, and its fade as it shrinks into the edge. */
        float lens = 1f, pushX, pushY, fade = 1f;
        /** Its touch area in the OEM's item: the whole screen while it is the chosen one. */
        Region region;
        /** The entrance, 0 at the sensor to 1 in its cell, overshooting a little. */
        float appear;
        private boolean released;
        private android.animation.ValueAnimator entrance;
        /** The grow on the chosen icon: 1 to CHOSEN_SCALE and back, 100ms as the OEM's. */
        private float chosenGrow = 1f;
        private android.animation.ValueAnimator grow;

        void choose(boolean on) {
            if (grow != null) grow.cancel();
            grow = android.animation.ValueAnimator.ofFloat(chosenGrow, on ? CHOSEN_SCALE : 1f);
            grow.setDuration(100L);
            grow.addUpdateListener(a -> {
                chosenGrow = (Float) a.getAnimatedValue();
                apply();
            });
            grow.start();
        }

        Bubble(Context c) {
            super(c);
            setScaleType(ScaleType.FIT_XY);
            // Every icon a circle, as on the watch - app icons and shortcut pictures included.
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            setClipToOutline(true);
        }

        void place(float x, float y, float fx, float fy, int ring) {
            this.ring = ring;
            cellX = x;
            cellY = y;
            fromX = fx;
            fromY = fy;
            appear = 0f;
        }

        void enter(long delay) {
            entrance = android.animation.ValueAnimator.ofFloat(0f, 1f);
            entrance.setDuration(500L);
            entrance.setStartDelay(delay);
            entrance.setInterpolator(SPRING);
            entrance.addUpdateListener(a -> {
                appear = (Float) a.getAnimatedValue();
                apply();
            });
            entrance.start();
        }

        void apply() {
            if (released) return;
            float p = appear;
            float tx = fromX + (pushX - fromX) * p, ty = fromY + (pushY - fromY) * p;
            super.setTranslationX(tx);
            super.setTranslationY(ty);
            if (this == sChosen && sDisc != null) {
                sDisc.setTranslationX(cellX + tx - sDisc.getLayoutParams().width / 2f);
                sDisc.setTranslationY(cellY + ty - sDisc.getLayoutParams().height / 2f);
            }
            float scale = (0.2f + (lens - 0.2f) * p) * chosenGrow;
            super.setScaleX(scale);
            super.setScaleY(scale);
            super.setAlpha(Math.max(0f, Math.min(1f, p * 2.5f)) * fade);
        }

        void release() {
            released = true;
            if (entrance != null) entrance.cancel();
            if (grow != null) grow.cancel();
        }

        @Override public void setScaleX(float v) { if (released) super.setScaleX(v); }
        @Override public void setScaleY(float v) { if (released) super.setScaleY(v); }
        @Override public void setTranslationX(float v) { if (released) super.setTranslationX(v); }
        @Override public void setTranslationY(float v) { if (released) super.setTranslationY(v); }
        @Override public void setAlpha(float v) { if (released) super.setAlpha(v); }
    }

    /** An OEM id (Integer) or a Resolved, or null when it cannot be shown on this phone. */
    private static Object pick(Context c, JSONObject e) {
        String t = e.optString("t");
        if ("native".equals(t)) {
            int id = e.optInt("id");
            return nativeShowable(c, id) ? Integer.valueOf(id) : null;
        }
        String key = e.toString();
        Resolved r = sResolved.get(key);
        if (r == null) {
            try {
                r = resolve(c, e);
            } catch (Throwable th) {
                Xp.w(Main.TAG + "fpquick: cannot resolve " + key + ": " + th);
            }
            if (r == null) return null;
            sResolved.put(key, r);
        }
        return r;
    }

    /** The OEM's own filter for its items, from startShowQuickOpenItemAnimation. */
    private static boolean nativeShowable(Context c, int id) {
        if (id < 1001 || id > 1008) return false;
        try {
            if (id == 1003 && !installed(c, "com.miui.voiceassist")) return false;
            Object probe = sGenerate.invoke(null, new RectF(), new Region(), c, id);
            Intent i = (Intent) Xp.callMethod(probe, "getIntent");
            String pkg = i.getPackage();
            if (pkg == null && i.getComponent() != null) pkg = i.getComponent().getPackageName();
            return pkg == null || !pkg.startsWith("com.android")
                    || !c.getPackageManager().queryIntentActivities(i, 0).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object build(Context c, Object pick, RectF rect, Region region) throws Exception {
        if (pick instanceof Integer) {
            Object item = sGenerate.invoke(null, rect, region, c, pick);
            // The OEM's icon, moved onto a Bubble so the fisheye can drive it too. A build whose
            // item keeps its view elsewhere keeps the OEM's view, unanimated but working.
            try {
                Bubble b = new Bubble(c);
                b.setImageDrawable(((ImageView) Xp.getObjectField(item, "mView")).getDrawable());
                Xp.setObjectField(item, "mView", b);
            } catch (Throwable ignored) {
            }
            return item;
        }
        Resolved r = (Resolved) pick;
        Object item = sGenerate.invoke(null, rect, region, c, SHELL_ID);
        Bubble iv = new Bubble(c);
        iv.setImageDrawable(r.icon);
        Xp.setObjectField(item, "mView", iv);
        sCustom.put(item, r);
        return item;
    }

    private static Resolved resolve(Context c, JSONObject e) throws Exception {
        PackageManager pm = c.getPackageManager();
        Resolved r = new Resolved();
        r.entry = e;
        switch (e.optString("t")) {
            case "app": {
                String pkg = e.optString("pkg");
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) return null;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                r.intent = launch;
                r.title = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                r.subtitle = "";
                r.icon = pm.getApplicationIcon(pkg);
                return r;
            }
            case "shortcut": {
                String pkg = e.optString("pkg");
                ShortcutInfo info = shortcut(c, pkg, e.optString("id"));
                if (info == null) return null;
                CharSequence label = info.getShortLabel();
                r.title = label == null ? e.optString("id") : label.toString();
                r.subtitle = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                LauncherApps la = c.getSystemService(LauncherApps.class);
                Drawable d = la.getShortcutIconDrawable(info, c.getResources().getDisplayMetrics().densityDpi);
                r.icon = d != null ? d : pm.getApplicationIcon(pkg);
                return r;
            }
            case "url": {
                String url = e.optString("url");
                if (url.isEmpty()) return null;
                String label = e.optString("label");
                Uri u = Uri.parse(url);
                r.title = label.isEmpty() ? (u.getHost() != null ? u.getHost() : url) : label;
                r.subtitle = u.getScheme() == null ? "" : u.getScheme();
                Intent view = new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // A single handler goes through the OEM's launch (app lock, market fallback);
                // more than one is started here and left to the system to choose. Not
                // resolveActivity: when it cannot pick it answers with the chooser itself, and
                // the chooser's package is not "android" everywhere (com.android.intentresolver
                // here) - handed to the OEM as the target, that sent the link to the market.
                // MATCH_DEFAULT_ONLY because the OEM's own check queries with it.
                List<android.content.pm.ResolveInfo> handlers =
                        pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY);
                if (handlers.size() == 1 && handlers.get(0).activityInfo != null) {
                    view.setPackage(handlers.get(0).activityInfo.packageName);
                    r.intent = view;
                    r.icon = handlers.get(0).loadIcon(pm);
                } else {
                    r.icon = new Glyph("🔗");
                }
                return r;
            }
            case "action": {
                String a = e.optString("a");
                r.subtitle = "系统";
                switch (a) {
                    // The system's own pictures rather than drawn ones: the lock screen's torch
                    // and the control centre's screenshot tile on a chip, the camera app's icon.
                    case "torch":
                        r.title = "手电筒";
                        r.icon = sysuiChip(c, "miui_keyguard_left_view_torchlight", "🔦");
                        return r;
                    case "camera":
                        r.title = "相机";
                        r.icon = installed(c, "com.android.camera")
                                ? pm.getApplicationIcon("com.android.camera")
                                : sysuiChip(c, "ic_camera", "📷");
                        return r;
                    case "screenshot":
                        r.title = "截屏";
                        r.icon = sysuiChip(c, "ic_qs_screenshot", "✂");
                        return r;
                    case "recorder": {
                        Intent launch = pm.getLaunchIntentForPackage("com.android.soundrecorder");
                        if (launch == null) return null;
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        r.intent = launch;
                        r.title = "录音";
                        r.icon = pm.getApplicationIcon("com.android.soundrecorder");
                        return r;
                    }
                    default: return null;
                }
            }
            default:
                return null;
        }
    }

    private static ShortcutInfo shortcut(Context c, String pkg, String id) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setShortcutIds(Collections.singletonList(id))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
        List<ShortcutInfo> got = la.getShortcuts(q, currentUser());
        return got == null || got.isEmpty() ? null : got.get(0);
    }

    // ---------------------------------------------------------------- doing

    /** The kinds the OEM's launch cannot take: a shortcut, a system action, a chooser URL. */
    private static void run(Context c, Resolved r) throws Exception {
        JSONObject e = r.entry;
        switch (e.optString("t")) {
            case "shortcut":
                c.getSystemService(LauncherApps.class).startShortcut(
                        e.optString("pkg"), e.optString("id"), null, null, currentUser());
                return;
            case "url":
                startAsUser(c, new Intent(Intent.ACTION_VIEW, Uri.parse(e.optString("url")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            case "action":
                switch (e.optString("a")) {
                    case "torch": Torch.toggle(c); return;
                    case "camera":
                        startAsUser(c, new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    // After the menu's own 150ms exit and its blur have gone, or they are in it.
                    case "screenshot": Main.main().postDelayed(FingerprintQuick::screenshot, 450); return;
                    default: return;
                }
            default:
        }
    }

    /** The screenshot key, the way the navigation bar's own buttons inject theirs. */
    private static void screenshot() {
        try {
            Object im = Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance").invoke(null);
            Method inject = im.getClass().getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent ev = new KeyEvent(now, now, action, KeyEvent.KEYCODE_SYSRQ, 0, 0,
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
                inject.invoke(im, ev, 0);
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "fpquick: screenshot failed: " + t);
        }
    }

    private static final class Torch extends android.hardware.camera2.CameraManager.TorchCallback {
        private static Torch sInstance;
        private String id;
        private boolean on;

        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(id)) on = enabled;
        }

        static void toggle(Context c) throws Exception {
            android.hardware.camera2.CameraManager cm =
                    c.getSystemService(android.hardware.camera2.CameraManager.class);
            if (sInstance == null) {
                Torch t = new Torch();
                for (String cid : cm.getCameraIdList()) {
                    android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                    Boolean flash = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash) && facing != null
                            && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        t.id = cid;
                        break;
                    }
                }
                if (t.id == null) throw new IllegalStateException("no back flash");
                // The callback answers once with the current state as it registers, so the
                // first toggle reads the truth even if the torch was lit from elsewhere.
                cm.registerTorchCallback(t, Main.main());
                sInstance = t;
                Main.main().postDelayed(() -> setTorch(cm, !sInstance.on), 50);
                return;
            }
            setTorch(cm, !sInstance.on);
        }

        private static void setTorch(android.hardware.camera2.CameraManager cm, boolean on) {
            try {
                cm.setTorchMode(sInstance.id, on);
            } catch (Throwable t) {
                Xp.w(Main.TAG + "fpquick: torch failed: " + t);
            }
        }
    }

    // ---------------------------------------------------------------- small things

    /** One of SystemUI's own glyphs on a chip, or the character when this build lacks it. */
    private static Drawable sysuiChip(Context c, String name, String fallback) {
        try {
            int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
            if (id != 0) return new Chip(c.getDrawable(id).mutate());
        } catch (Throwable ignored) {
        }
        return new Glyph(fallback);
    }

    /**
     * A glyph meant for the control centre or the lock screen - one colour, drawn to be tinted -
     * on a round chip the size of the OEM's icons. Same colours as the settings page's chip.
     */
    static final class Chip extends Drawable {
        private final Drawable glyph;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chip(Drawable glyph) {
            this.glyph = glyph;
            glyph.setTint(0xFFFFFFFF);
            fill.setColor(0xFF48484A);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            int inset = Math.round(r * 0.5f);
            glyph.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            glyph.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); glyph.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { glyph.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A round chip with one character on it, for the kinds that have no picture at all. */
    static final class Glyph extends Drawable {
        private final String text;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(String text) {
            this.text = text;
            fill.setColor(0x40FFFFFF);
            ink.setColor(0xFFFFFFFF);
            ink.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            ink.setTextSize(r * 0.9f);
            Paint.FontMetrics fm = ink.getFontMetrics();
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, ink);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha * 0x40 / 255); ink.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { ink.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static boolean bool(Object o, String f) {
        return Boolean.TRUE.equals(Xp.getObjectField(o, f));
    }

    private static int staticInt(String name) throws Exception {
        Field f = sUtils.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static float dimen(Context c, String name, float fallback) {
        int id = c.getResources().getIdentifier(name, "dimen", c.getPackageName());
        return id == 0 ? fallback : c.getResources().getDimension(id);
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static UserHandle currentUser() {
        try {
            int u = (Integer) android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
            return (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, u);
        } catch (Throwable t) {
            return android.os.Process.myUserHandle();
        }
    }

    private static void startAsUser(Context c, Intent i) {
        try {
            Object current = UserHandle.class.getField("CURRENT").get(null);
            Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class)
                    .invoke(c, i, current);
        } catch (Throwable t) {
            c.startActivity(i);
        }
    }
}
