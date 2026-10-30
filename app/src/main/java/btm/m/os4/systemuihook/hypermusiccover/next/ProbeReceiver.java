// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LocalLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyricSource;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LyriconSource;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.NcmLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.OnlineLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.WebLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.island.DateStatus;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerConfig;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import btm.m.os4.systemuihook.hypermusiccover.next.island.NumStateProbe;
import btm.m.os4.systemuihook.hypermusiccover.next.immersive.ImmersiveHost;
import btm.m.os4.systemuihook.hypermusiccover.LegacyShadeBridge;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapNavScene;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapTransitScene;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupCloud;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupCodeIsland;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupPark;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.widget.ImageView;
import android.util.Log;
import android.view.View;

/**
 * The module's broadcast receiver inside SystemUI: every op the app, the wallpaper process,
 * 高德 and adb send, and the receipt that tells the sender it was heard.
 *
 * Lifted out of Main.registerReceiver as it was (2026-10-14), where it was a local class of
 * a thousand lines: references to Main's members carry Main., nothing else changed.
 */
final class ProbeReceiver extends ProbeGuard.Receiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!ProbeGuard.admit(this, i)) return;
        String op = i.getStringExtra("op");
        if (op == null) op = "info";
        // Standalone settings/debug operations must not change host-owned features.
        if (!java.util.Arrays.asList("query", "info", "tail", "lyricstate", "mini",
                "immersive", "navmap", "wphello", "needart", "wpart", "blurshown",
                "videoreloading", "videoreload").contains(op)) return;
        Xp.d(Main.TAG + "recv op=" + op + " extras=" + i.getExtras());
        // An op answering later, through goAsync: the receipt is its to send.
        boolean async = false;
        boolean failed = false;
        try {
            // Four groups by who sends the op, and two ops of their own: cloud, which may answer
            // later, and ctint, which can return without a receipt. The ops are disjoint, so the
            // order they are asked in changes nothing.
            if ("cloud".equals(op)) {
                async = cloudFetch(c, i);
            } else if ("ctint".equals(op)) {
                // Here rather than in debugOp: an unreadable colour returns from onReceive itself,
                // with no receipt, and that has to stay so.
                // The cover's colour, by hand, so a tint can be tried without hunting for
                // the artwork that would produce it.
                if (i.getBooleanExtra("off", false)) {
                    Main.sCoverTintOverride = 0;
                    Xp.log(Main.TAG + "cover tint back to measured #"
                            + Integer.toHexString(Main.sCoverTint));
                } else {
                    // A hex string rather than an int extra: `am --ei` parses with
                    // Integer.valueOf, which does not take `0x`, and "#86a6b1" is how a
                    // colour is written everywhere else in this file.
                    String v = i.getStringExtra("v");
                    try {
                        Main.sCoverTintOverride = v == null ? 0xff8888ff
                                : (int) Long.parseLong(v.replace("#", ""), 16);
                    } catch (Throwable t) {
                        Xp.log(Main.TAG + "ctint: cannot read " + v);
                        return;
                    }
                    Xp.log(Main.TAG + "cover tint forced to #"
                            + Integer.toHexString(Main.sCoverTintOverride));
                }
                AodColour.recolorClock();
            } else if (!appSetting(c, i, op) && !appQuery(c, i, op)
                    && !peerMessage(c, i, op) && !debugOp(c, i, op)) {
                Xp.log(Main.TAG + "unknown op " + op);
            }
        } catch (Throwable t) {
            failed = true;
            Xp.w(Main.TAG + "op failed: " + Log.getStackTraceString(t));
        }
        // The receipt. A setting sent while SystemUI is down, or before this receiver
        // exists, goes nowhere and the broadcast still comes back - only with the code it
        // was sent with. This is how the app tells the two apart and sends it again once
        // there is someone here. Config writes are the exception to acknowledging a
        // failed op: the app must retain them for retry if durable storage failed.
        if (!async && isOrderedBroadcast() && (!failed || !"minicfg".equals(op)))
            setResultCode(Main.OP_ACK);
    }

    /**
     * A setting from the app's pages: written, applied, and saved by the module. False when the op
     * is not one of these.
     */
    boolean appSetting(Context c, Intent i, String op) throws Exception {
        if ("pushart".equals(op)) {
            boolean on = i.getBooleanExtra("on", true);
            if (i.hasExtra("bias")) Main.sBias = Main.clamp01(i.getFloatExtra("bias", Main.sBias));
            MediaController mc = on ? MediaCard.controllerFromCard(c) : null;
            if (on && mc == null) mc = Main.pickController(c);
            Main.sTrackKey = on ? Main.trackKey(mc) : "";
            Main.setCoverEnabled(on, i.getBooleanExtra("anim", true), false);
        } else if ("coverstyle".equals(op)) {
            String key = i.getStringExtra("key");
            float value = i.getFloatExtra("v", Float.NaN);
            CoverCardStyle next = Main.sCoverCardStyle.with(key, value);
            boolean modeChanged = next.mode != Main.sCoverCardStyle.mode;
            Main.sCoverCardStyle = next;
            CoverCardLayer.style(next);
            CoverCardLayer.refresh();
            ModuleState.saveStateSoon();
            if (Main.sCoverMode && modeChanged) {
                // Leaving 保留壁纸, the dim belongs to the style that is going away. The clock
                // may well stay held (another style's cover), so no exit frame will clear it.
                if (!Main.keepsWallpaper()) KeepDim.apply(0f);
                if (next.mode == CoverCardStyle.CARD) {
                    CoverCardLayer.attach(CoverPush.coverLayer());
                    CoverCardLayer.playback(Main.sCoverCardPlaying);
                }
                CoverPush.pushArtAsync(true, false);
                // Into or out of 保留壁纸, the clock changes hands between cover mode and the
                // lyric page.
                Main.syncCoverHold(true);
            }
        } else if ("mediacard".equals(op)) {
            if (i.hasExtra("hideart")) Main.sMcHideArt = i.getBooleanExtra("hideart", false);
            if (i.hasExtra("lyricart")) {
                Main.sMcArtInLyrics = i.getBooleanExtra("lyricart", false);
            }
            if (i.hasExtra("titletap")) {
                Main.sMcTitleTap = i.getBooleanExtra("titletap", false);
            }
            if (i.hasExtra("pagebtn")) {
                Main.sMcPageBtn = i.getBooleanExtra("pagebtn", false);
            }
            ModuleState.saveState();
            Xp.log(Main.TAG + "media card hideArt=" + Main.sMcHideArt
                    + " artInLyrics=" + Main.sMcArtInLyrics
                    + " titleTap=" + Main.sMcTitleTap + " pageBtn=" + Main.sMcPageBtn);
            MediaCard.applyMediaCard();
        } else if ("lyrics".equals(op)) {
            boolean on = i.getBooleanExtra("on", !LockLyrics.sEnabled);
            LockLyrics.setEnabled(on, Main.sTrackKey, Main.sWatched);
            ModuleState.saveState();
        } else if ("lyrichdr".equals(op)) {
            LockLyrics.sHdr = i.getBooleanExtra("on", !LockLyrics.sHdr);
            Xp.log(Main.TAG + "lyrics HDR highlight: " + LockLyrics.sHdr);
            LockLyrics.refresh();
            ModuleState.saveState();
        } else if ("lyrictrans".equals(op)) {
            LockLyrics.sTrans = i.getBooleanExtra("on", !LockLyrics.sTrans);
            Xp.log(Main.TAG + "lyrics translations: " + LockLyrics.sTrans);
            // The view notices the switch itself and lays the lines out again around
            // it; refresh only has to start the frames that let it.
            LockLyrics.refresh();
            ModuleState.saveState();
        } else if ("lyricroma".equals(op)) {
            LockLyrics.sRoma = i.getBooleanExtra("on", !LockLyrics.sRoma);
            Xp.log(Main.TAG + "lyrics romanisations: " + LockLyrics.sRoma);
            LockLyrics.refresh();
            ModuleState.saveState();
        } else if ("lyricstyle".equals(op)) {
            String key = i.getStringExtra("key");
            if (LockLyrics.setStyle(key, i.getFloatExtra("v", Float.NaN))) {
                ModuleState.saveStateSoon();
            }
        } else if ("lyricappearance".equals(op)) {
            if (i.hasExtra("json")) {
                LockLyrics.setAppearance(i.getStringExtra("json"));
                ModuleState.saveStateSoon();
            }
        } else if ("lyricmaxlines".equals(op)) {
            // For the session only: not a setting, see LyricFocus.DEFAULT_LIMIT.
            if (LockLyrics.setMaxLines(i.getIntExtra("v", LockLyrics.sMaxLines))) {
                LockLyrics.refresh();
            }
        } else if ("lyricalign".equals(op)) {
            // Same shape as the translation switch: the view notices the change and
            // lays the lines out again, refresh only has to start the frames that let
            // it. Saved the way lyricstyle is, folding a quick run of picks into one
            // write.
            if (LockLyrics.setAlign(i.getIntExtra("v", LockLyrics.sAlign))) {
                Xp.log(Main.TAG + "lyrics alignment: " + LockLyrics.sAlign);
                LockLyrics.refresh();
                ModuleState.saveStateSoon();
            }
        } else if ("lyriconline".equals(op)) {
            if (LockLyrics.setOnline(i.getBooleanExtra("on", !LockLyrics.sOnline))) {
                Xp.log(Main.TAG + "lyrics from the network: " + LockLyrics.sOnline);
                ModuleState.saveState();
            }
        } else if ("lyrickeep".equals(op)) {
            LockLyrics.sKeepOn = i.getBooleanExtra("on", !LockLyrics.sKeepOn);
            Xp.log(Main.TAG + "lyrics keep the screen on: " + LockLyrics.sKeepOn);
            LockLyrics.refresh();
            ModuleState.saveState();
        } else if ("motion".equals(op)) {
            // Motion covers. `--ez on` switches them, `--ez forget true` empties the
            // cache; either way the answer is the state. See MotionCover.
            if (i.hasExtra("on")) {
                MotionCover.sEnabled = i.getBooleanExtra("on", false);
                ModuleState.saveState();
                Xp.log(Main.TAG + "motion covers " + (MotionCover.sEnabled ? "on" : "off"));
                // The cover on screen now gets one, or loses its own.
                if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
            }
            // `--ez data true`: download on mobile data too.
            if (i.hasExtra("data")) {
                MotionCover.sOnData = i.getBooleanExtra("data", false);
                ModuleState.saveState();
                Xp.log(Main.TAG + "motion covers on mobile data " + (MotionCover.sOnData ? "on" : "off"));
                if (MotionCover.sOnData) Main.main().post(MotionCover::retry);
            }
            if (i.getBooleanExtra("forget", false)) MotionCover.forget(c);
            setResultData(MotionCover.describe());
        } else if ("colon".equals(op)) {
            HyperTweaks.sForceColon = i.getBooleanExtra("on",
                    !HyperTweaks.sForceColon);
            ModuleState.saveState();
            // The always-on display draws its own clock in its own process, which
            // can read neither this flag nor the file it is saved in, so the switch
            // is mirrored somewhere both can see.
            HyperTweaks.publishColon(Main.sAppCtx);
            // Nothing to re-apply: the clock asks its bean whether to draw the colon
            // on the next layout, which the keyguard does every time it comes up.
            Xp.log(Main.TAG + "force clock colon "
                    + (HyperTweaks.sForceColon ? "on" : "off"));
        } else if ("seekglow".equals(op)) {
            HyperTweaks.sBarGlow = i.getBooleanExtra("on", !HyperTweaks.sBarGlow);
            ModuleState.saveState();
            // The card on screen was built before this switch was read, so it is
            // changed in place - lit as applyBarGlow lights a new one, or put back to
            // the flat bar it was built as (HyperTweaks.clearBarGlow).
            View bar = Main.findLockScreenView("media_progress_bar");
            String r = bar == null ? "no card up" : HyperTweaks.sBarGlow
                    ? HyperTweaks.applyBarGlow(bar) : HyperTweaks.clearBarGlow(bar);
            Xp.log(Main.TAG + "media bar glow " + (HyperTweaks.sBarGlow ? "on" : "off")
                    + " - " + r);
            setResultData((HyperTweaks.sBarGlow ? "on " : "off ") + r);
        } else if ("hidefp".equals(op)) {
            Fingerprint.sHideFp = i.getBooleanExtra("on", !Fingerprint.sHideFp);
            ModuleState.saveState();
            Xp.log(Main.TAG + "hide fingerprint " + (Fingerprint.sHideFp ? "on" : "off"));
            Fingerprint.applyHideFp();
        } else if ("aodclock".equals(op)) {
            Main.sAodSmall = i.getBooleanExtra("small", !Main.sAodSmall);
            ModuleState.saveState();
            // The pose a held doze was drawn at is the small one, and the fall into an
            // OEM doze aims at that remembered pose rather than at the live clock. Left
            // standing, turning the setting off would still land on the small clock
            // once. Dropped, it is the state a doze that never settled is in.
            ClockCollapse.forgetAodPose();
            Xp.log(Main.TAG + "AOD keeps the small clock " + (Main.sAodSmall ? "on" : "off")
                    + " (full-screen AOD now: " + Main.fullAodOn() + ")");
        } else if ("fpavoid".equals(op)) {
            Fingerprint.sFpAvoid = i.getIntExtra("mode", 0);
            ModuleState.saveState();
            Main.refreshNotificationSink();
            Xp.log(Main.TAG + "fingerprint avoid mode=" + Fingerprint.sFpAvoid);
        } else if ("bias".equals(op)) {
            Main.setBias(i.getFloatExtra("v", Main.DEFAULT_BIAS));
        } else if ("clockscale".equals(op)) {
            Main.setClockHeightDp(i.getFloatExtra("v", Main.DEFAULT_CLOCK_HEIGHT_DP));
            ModuleState.saveState();
            // A settled clock has no frames left to carry a new size on; ask for one.
            ClockCollapse.refresh();
        } else if ("clocksize".equals(op)) {
            Main.setClockSize(i.getFloatExtra("v", 1f));
            ModuleState.saveState();
            ClockCollapse.refresh();
        } else if ("clockoffset".equals(op)) {
            Main.setClockOffsetDp(i.getFloatExtra("v", 0f));
            ModuleState.saveState();
            ClockCollapse.refresh();
        } else if ("glassend".equals(op)) {
            Main.sGlassEnd = Main.clamp01(i.getFloatExtra("v", Main.DEFAULT_GLASS_END));
            ModuleState.saveState();
            Xp.log(Main.TAG + "glass end = " + Main.sGlassEnd);
            ClockCollapse.refresh();
        } else if ("clockspring".equals(op)) {
            // Nothing forced on screen: see setClockResponse(). The fades it pushes
            // are the only thing outside this process.
            Main.setClockResponse(i.getFloatExtra("v", Main.EASE_COVER[1]));
        } else if ("auto".equals(op)) {
            Main.setAuto(i.getBooleanExtra("on", true));
        } else if ("coverfeature".equals(op)) {
            Main.setCoverFeature(i.getBooleanExtra("on", true));
        } else if ("shadecfg".equals(op)) {
            // One op for the whole settings page. The key is validated and the value
            // clamped in LegacyShadeBridge.configure, so adb and the UI go through exactly
            // the same door.
            LegacyShadeBridge.configure(i.getStringExtra("key"), i.getIntExtra("v", 0));
            ModuleState.saveState();
        } else if ("fpquick".equals(op)) {
            // 指纹快捷功能: --es json '{"total":ms,"move":bool,"items":[...]}', or nothing to
            // just read it back. The format belongs to FingerprintQuick. --ez sys writes the
            // system's own switch; --es shortcuts <pkg> answers that app's shortcuts in the
            // extras, for the settings page's picker.
            if (i.hasExtra("sys")) FingerprintQuick.setSystemSwitch(c, i.getBooleanExtra("sys", true));
            if (i.getBooleanExtra("shortcutapps", false)) {
                setResultExtras(FingerprintQuick.shortcutApps(c));
            }
            if (i.hasExtra("shortcuts")) {
                setResultExtras(FingerprintQuick.shortcuts(c, i.getStringExtra("shortcuts")));
            }
            setResultData(FingerprintQuick.apply(c, i.getStringExtra("json")));
        } else if ("minicfg".equals(op)) {
            MiniPlayerRuntime.applyConfig(c, i.getStringExtra("json"));
            ModuleState.saveState();
        } else if ("pickup".equals(op)) {
            // 取餐码: --ez on true|false from the app; --es do read for one read now;
            // --es do vdtest [--ei task N] [--ei min M] for the hidden display's
            // experiment (§6 of 取餐码方案.md); --es do vdoff to put it back. The state
            // of both halves is the result either way.
            if (i.hasExtra("on")) {
                PickupCodeIsland.INSTANCE.setOn(i.getBooleanExtra("on", true));
                ModuleState.saveState();
            }
            // --ez card false draws the island with the system's focus template again,
            // which is the way back if ColorOS's card does not come up on this build.
            if (i.hasExtra("card")) {
                PickupCodeIsland.sCard = i.getBooleanExtra("card", true);
                ModuleState.saveState();
            }
            String doIt = i.getStringExtra("do");
            if ("read".equals(doIt)) PickupCodeIsland.INSTANCE.readNow();
            if ("vdtest".equals(doIt)) {
                int task = i.getIntExtra("task", PickupCodeIsland.INSTANCE.trackedTask());
                if (task < 0) setResultData("no task: open the order page first");
                else setResultData(PickupPark.INSTANCE.experiment(task,
                        i.getIntExtra("min", 5), 0));
            } else if ("vdoff".equals(doIt)) {
                PickupPark.INSTANCE.releaseAllFrom("the probe said so");
                setResultData(PickupPark.INSTANCE.describe());
            } else {
                setResultData(PickupCodeIsland.INSTANCE.describe()
                        + " | " + PickupPark.INSTANCE.describe());
            }
        } else if ("transitcfg".equals(op)) {
            // 「高德公交地铁」: --ez on true|false from the app; without it, 高德
            // asking as it starts (AmapImmerse), and told what it is.
            if (i.hasExtra("on")) {
                AmapTransitScene.INSTANCE.setOn(c, i.getBooleanExtra("on", true));
                ModuleState.saveState();
            } else {
                AmapTransitScene.INSTANCE.tellAmap(c);
            }
        } else {
            return false;
        }
        return true;
    }

    /**
     * A question from the app: answered in the result data or extras. False when the op is not one
     * of these.
     */
    private boolean appQuery(Context c, Intent i, String op) throws Exception {
        if ("preview".equals(op)) {
            setResultExtras(PreviewShots.previewBundle(c, i));
        } else if ("query".equals(op)) {
            // Answered through the ordered broadcast's result extras: the app is a
            // separate process and this is the only channel it already has. A reply
            // arriving at all is also how the app knows the module is loaded.
            android.os.Bundle out = new android.os.Bundle();
            out.putBoolean("alive", true);
            out.putBoolean("cover", Main.sCoverMode);
            out.putString("minicfg", MiniPlayerRuntime.configJson(c));
            out.putInt("minicfgschema", MiniPlayerConfig.SCHEMA_VERSION);
            float[] shortcuts = MiniPlayerRuntime.shortcutGeometry();
            if (shortcuts != null) out.putFloatArray("minishortcuts", shortcuts);
            out.putBoolean("auto", Main.sAuto);
            out.putBoolean("coverfeature", Main.sCoverFeatureOn);
            out.putFloat("bias", Main.sBias);
            out.putInt("coverstyle", Main.sCoverCardStyle.mode);
            out.putFloat("covercardfill", Main.sCoverCardStyle.fill);
            out.putFloat("covercardpos", Main.sCoverCardStyle.pos);
            // No covercardcorner: the square's corners are the media card's own, and
            // the app draws them from the cardradius it gets with the pictures.
            out.putFloat("clock", Main.sClockHeightDp);
            out.putFloat("clocksize", Main.effectiveClockSize());
            out.putFloat("clockoff", Main.sClockOffsetDp);
            out.putFloat("glass", Main.sGlassEnd);
            out.putFloat("spring", Main.sClockResponse);
            // The whole shade settings page, generated from the one key list so a new
            // knob cannot reach the state file and miss the settings app.
            for (String key : LegacyShadeBridge.CFG_KEYS) {
                out.putInt("shade_" + key, LegacyShadeBridge.cfgInt(key));
            }
            out.putInt("shadeMode", LegacyShadeBridge.mode());
            out.putBoolean("card", MediaCard.sCardShowing);
            // Checked live rather than reported from the cached flag: the user can
            // change the wallpaper at any time and that is what breaks the feature.
            out.putBoolean("lockwp", CoverPush.hasLockWallpaper(c));
            out.putString("track", MediaCard.sCardKey);
            MediaController mc = Main.sWatched;
            out.putString("player", mc == null ? "" : mc.getPackageName());
            out.putBoolean("mcart", Main.sMcHideArt);
            out.putBoolean("mclyricart", Main.sMcArtInLyrics);
            out.putBoolean("mctap", Main.sMcTitleTap);
            out.putBoolean("mcpage", Main.sMcPageBtn);
            out.putBoolean("tap", Main.sTapToggle);
            out.putBoolean("fadewp", Main.sFadeWp);
            out.putBoolean("motion", MotionCover.sEnabled);
            out.putBoolean("motiondata", MotionCover.sOnData);
            out.putInt("fsmode2", Main.sFadeMode);
            out.putBoolean("vcfade", Main.sVideoFade);
            out.putBoolean("hidefp", Fingerprint.sHideFp);
            out.putBoolean("aodsmall", Main.sAodSmall);
            out.putBoolean("colon", HyperTweaks.sForceColon);
            out.putBoolean("seekglow", HyperTweaks.sBarGlow);
            out.putBoolean("lyrics", LockLyrics.sEnabled);
            // Where the lock screen's words came from right now, and whether there is
            // a Lyricon to talk to. The app's lyric-source row is these two: it names
            // the source, and warns to install something only when both this and the
            // LyricInfo package are absent. See FeaturesPage.
            out.putString("lyricsrc", LockLyrics.sourceName());
            out.putBoolean("lyricon", LyriconSource.installed());
            out.putBoolean("lyrickeep", LockLyrics.sKeepOn);
            out.putBoolean("lyrichdr", LockLyrics.sHdr);
            out.putBoolean("lyrictrans", LockLyrics.sTrans);
            out.putBoolean("lyricroma", LockLyrics.sRoma);
            out.putBoolean("lyriconline", LockLyrics.sOnline);
            out.putInt("lyricalign", LockLyrics.sAlign);
            out.putInt("lyricmaxlines", LockLyrics.sMaxLines);
            out.putFloat("lyricbgsize", LockLyrics.sStyle.bgSizeSp);
            out.putString("lyricappearance", LockLyrics.sAppearance.toJson());
            out.putFloat("lyricfill", LockLyrics.sStyle.fill);
            out.putFloat("lyricpos", LockLyrics.sStyle.pos);
            out.putFloat("lyricside", LockLyrics.sStyle.sideDp);
            out.putFloat("lyricsize", LockLyrics.sStyle.sizeSp);
            out.putInt("lyricweight", LockLyrics.sStyle.weight);
            // Whether anything has actually written a lyric to a session, which is
            // what tells a working provider module from a merely installed one.
            out.putBoolean("sessionlyric", LockLyrics.sSawSessionLyric
                    || LyricSource.hasLyricInfo(Main.sWatched));
            out.putInt("fpavoid", Fingerprint.sFpAvoid);
            out.putString("fpquick", FingerprintQuick.configJson(c));
            out.putBoolean("fpquicksys", FingerprintQuick.systemSwitch(c));
            out.putBoolean("fpquickok", FingerprintQuick.installed());
            out.putBoolean("transit", AmapTransitScene.sOn);
            out.putBoolean("pickup", PickupCodeIsland.sOn);
            out.putBoolean("card", PickupCodeIsland.sCard);
            // 设置页那行「手动刷新取餐码云端规则」的副标题，和这一份名单有几个小程序。
            out.putString("rulesdate", PickupCloud.INSTANCE.stamp(PickupCloud.INSTANCE.getUpdatedAt()));
            out.putInt("rulescount", PickupCloud.INSTANCE.count());
            // Everything the app's preview needs to be to scale. It draws a lock
            // screen it cannot see, and every one of these is device-specific, so
            // they are measured here rather than written down twice.
            out.putInt("sw", Main.sScreenW);
            out.putInt("sh", Main.sScreenH);
            out.putInt("cardl", MediaCard.sCardL);
            out.putInt("cardt", MediaCard.sCardT);
            out.putInt("cardw", MediaCard.sCardW);
            out.putInt("cardh", MediaCard.sCardH);
            float[] cg = ClockViews.clockGeometry();
            if (cg != null) {
                out.putFloat("clockw", cg[0]);
                out.putFloat("clockh", cg[1]);
                out.putFloat("clocky", cg[2]);
                out.putFloat("clockpad", Main.CLOCK_PAD);
                out.putFloat("clockx", cg[3]);
                out.putFloat("clockpivotx", cg[4]);
                out.putFloat("clockfull", Main.clockFullRatio());
            }
            // Outside the block above: it is a property of the STYLE, not of the
            // measurement, and the app needs it even on a frame where the clock
            // could not be measured - that is exactly when the slider it disables
            // would otherwise look like it was doing something.
            out.putBoolean("clockglass", ClockGlass.clockHasGlass());
            // Every op is acknowledged below; this tells the app it can rely on that,
            // where a build from before it could only be sent to and hoped for.
            out.putBoolean("acks", true);
            setResultExtras(out);
        } else {
            return false;
        }
        return true;
    }

    /** A message from the module's other halves: the wallpaper process's hand-shakes and
     * signals, and 高德's immersive pages. False when the op is not one of these. */
    private boolean peerMessage(Context c, Intent i, String op) throws Exception {
        if ("transit".equals(op)) {
            // 高德's bus and subway navigation, from AmapTransitShare in its process -
            // or by hand: --es json '<intentEntity>', --ez demo true, --es do end.
            setResultData(AmapTransitScene.INSTANCE.command(i.getStringExtra("json"),
                    i.getStringExtra("do"), i.getBooleanExtra("demo", false)));
        } else if ("immersive".equals(op) || "navmap".equals(op)) {
            // The immersive pages: --es id <scene> --es do state|open|close|arm|disarm.
            // navmap is 高德's old spelling (start / stop), kept for a 高德 process
            // still running the build that sent it.
            String what = i.getStringExtra("do");
            String id = i.getStringExtra("id");
            if ("navmap".equals(op)) {
                id = AmapNavScene.ID;
                if ("start".equals(what)) what = "arm";
                else if ("stop".equals(what)) what = "disarm";
            }
            setResultData(LockHold.describe() + "\n" + ImmersiveHost.command(id, what));
        } else if ("wphello".equals(op)) {
            // The wallpaper process, on its start or when asked, saying what it can
            // take. See sWpComposes.
            boolean composes = i.getBooleanExtra("composes", false);
            // It starts with no dim; if it came back while the lyric page was up, hand it
            // the one on record. See KeepDim.resend().
            KeepDim.resend();
            // A restarted wallpaper process has forgotten the lyrics' blur.
            LockLyrics.resendBlur();
            if (composes != Main.sWpComposes) {
                Main.sWpComposes = composes;
                Xp.log(Main.TAG + "wallpaper process "
                        + (composes ? "composes covers itself, sending the source"
                        : "wants the composed JPEG"));
            }
        } else if ("needart".equals(op)) {
            // The wallpaper process came up with nothing to draw - see
            // WallpaperProbe.askForArt(). It asks once per process start, and there
            // is a floor between two asks and a ceiling on how many, so this cannot
            // loop. What it may well be holding is the previous track's cover off
            // its own disk, which is a state it cannot tell from the right one.
            String why = i.getStringExtra("why");
            if (!Main.sCoverMode) {
                // NOT ignored, and that was the bug behind "the wallpaper will not
                // change back". Measured: the wallpaper process says `no art here,
                // asked SystemUI for it`, cover mode is off, and this branch dropped
                // the request on the floor - so the process kept the last cover
                // texture it had been given and the lock screen showed that album
                // art for ever, across every track, with nothing left to replace it.
                // Asking with cover mode off means exactly one thing: hand it back.
                //
                // Rate limited because the answer is another broadcast and a
                // wallpaper with an empty lock slot has nothing to reload either -
                // without this, an empty slot and an asking wallpaper would trade
                // messages.
                long now = android.os.SystemClock.uptimeMillis();
                if (now - Main.sNeedArtOffAt > 3000L) {
                    Main.sNeedArtOffAt = now;
                    Xp.log(Main.TAG + "needart (" + why + ") with cover off: "
                            + "telling the wallpaper to drop it and reload");
                    CoverPush.pushArtAsync(false, false);
                }
            } else {
                Xp.log(Main.TAG + "needart (" + why + "), resending the art");
                // Not a track change: take whatever the session has now, without the
                // same-artwork check that is what swallowed the later pushes.
                CoverPush.pushArtAsync(true, false);
            }
        } else if ("wpart".equals(op)) {
            // The wallpaper has begun showing the new cover; see CoverCardLayer, and
            // ImmersiveHost, whose page closing onto the cover waits for this.
            CoverCardLayer.releaseHeld();
            ImmersiveHost.coverShown();
        } else if ("blurshown".equals(op)) {
            // From WallpaperProbe: the still cover's frosting is on its way in, and
            // the lyrics held back for it can come in with it.
            LockLyrics.onBlurShown(i.getLongExtra("blurseq", 0L));
        } else if ("videoreloading".equals(op)) {
            CoverPush.noteVideoReloading();
        } else if ("videoreload".equals(op)) {
            // From WallpaperProbe, sent from inside the call that rebuilds the video
            // player - and also from the paths where no reload is coming at all, so
            // that the cover is never held for a message that will not arrive. See
            // noteVideoReload() and sFadeMode.
            CoverPush.noteVideoReload();
            // The window's content changes a few hundred ms AFTER this message, on
            // the wallpaper side, with nothing in this process asking for a frame -
            // and the notif cards' frosted backdrop re-samples what is behind them
            // only when this window redraws. Left alone it keeps serving the last
            // frame it sampled while the wallpaper under it has already swapped,
            // which is the report that the blur arrives half a beat late. Pump
            // redraws until the swap has certainly landed. See startBlurSync().
            Main.startBlurSync(i.getStringExtra("why"));
            LockLyrics.onVideoReload();
            // The rebuild also resets the wallpaper-side render state, and with it
            // the AOD's wallpaper dim (set through setWallpaperBlack /
            // setWallPaperAnimProcess - see sLastWallpaperBlack). Re-assert it once
            // the first frame is up, or the cover sits in AOD at full brightness
            // while the wallpaper next to it would have been dimmed.
            if (Main.sCoverMode) Main.reassertAodDim();
        } else {
            return false;
        }
        return true;
    }

    /**
     * Everything else: adb's probes, dumps, traces and tuning knobs. False when the op is not one
     * of these.
     */
    private boolean debugOp(Context c, Intent i, String op) throws Exception {
        if ("info".equals(op)) {
            Diagnostics.dumpInfo();
        } else if ("gen".equals(op)) {
            // What this generation of the module holds in this process, and whether it still
            // answers at all. The log line is for adb without an ordered sender.
            String report = Gen.report() + " " + HotReload.status();
            Xp.log(Main.TAG + "gen " + report);
            setResultData(report);
        } else if ("retire_dryrun".equals(op)) {
            // Step 6's rehearsal: take every SystemUI feature down, sweep the ledger, and put
            // the same generation back up. Hooks are not touched - see SystemUiRetire.
            String report = SystemUiRetire.dryRun(c);
            Xp.log(Main.TAG + "retire_dryrun " + report);
            setResultData(report);
        } else if ("anim".equals(op)) {
            Diagnostics.dumpAnimConfigs();
        } else if ("hold".equals(op) || "release".equals(op)) {
            // The OEM's squeeze on its own, for measuring it. Only with cover mode's
            // clock off: ClockCollapse owns the hold while it is on.
            final String name = op;
            final boolean hold = "hold".equals(op);
            final float y = i.getFloatExtra("y", Float.NaN);
            final View cv = Main.sContainer;
            if (ClockCollapse.active()) {
                Xp.log(Main.TAG + op + ": the cover clock owns the hold ("
                        + ClockCollapse.describe() + ")");
            } else if (cv != null) cv.post(new Runnable() {
                @Override
                public void run() {
                    Main.sHoldY = hold && !Float.isNaN(y) ? y : null;
                    float to = Main.sHoldY != null ? Main.sHoldY : Main.sLastSystemY;
                    if (!Float.isNaN(to)) Main.applyY(to);
                    Xp.log(Main.TAG + name + " -> y=" + to);
                }
            });
        } else if ("gscale".equals(op)) {
            String gid = i.getStringExtra("id");
            Main.groupScale(gid == null ? "time_group" : gid, i.getFloatExtra("k", 1f),
                    i.getFloatExtra("px", -1f), i.getFloatExtra("py", -1f),
                    i.getFloatExtra("ty", 0f));
        } else if ("cls".equals(op)) {
            String name = i.getStringExtra("name");
            if (name == null) Diagnostics.dumpClockStyleInfo();
            else Diagnostics.dumpClass(name, i.getStringExtra("grep"));
        } else if ("gdata".equals(op)) {
            Diagnostics.pokeGlassData(i.getIntExtra("idx", -1), i.getFloatExtra("v", 0f));
        } else if ("notifsink".equals(op)) {
            setResultData("mode=" + Fingerprint.sFpAvoid + "\nhideFp=" + Fingerprint.sHideFp
                    + " suppressedFrames=" + Fingerprint.sFodFrames.pendingCount()
                    + " replayedFrames=" + Fingerprint.sFodFramesReplayed
                    + "\n" + Main.sNotificationSink.describe() + "\n"
                    + MiniPlayerRuntime.describe() + "\n" + Xp.tail("notification bound", 30));
        } else if ("aodprobe".equals(op)) {
            setResultData(Diagnostics.aodProbe());
        } else if ("entries".equals(op)) {
            setResultData(ClockCollapse.entries());
        } else if ("poses".equals(op)) {
            setResultData(ClockCollapse.poses());
        } else if ("fall".equals(op)) {
            setResultData(ClockCollapse.fall());
        } else if ("card".equals(op)) {
            setResultData(MediaCard.cardHistory());
        } else if ("depth".equals(op)) {
            Main.setDepthHidden(!i.getBooleanExtra("on", true));
        } else if ("vcprobe".equals(op)) {
            // What each layer of a live cover is showing right now, as average colours:
            // MIUI's two video TextureViews (whatever the player last put in them,
            // hidden or not) and our own cover view.
            View dd = Main.findDeductedImageView();
            setResultData("bg=" + Main.avgColour(Main.sVideoBg) + " fg=" + Main.avgColour(Main.sVideoFg)
                    + " cover=" + Main.avgColour(Main.sCover) + " uncover=" + Main.sUncoverProbe
                    + " deducted=" + (dd == null ? "absent" : dd.getClass().getName()
                    + " vis=" + dd.getVisibility() + " alpha=" + Main.r2(dd.getAlpha())
                    + " tAlpha=" + Main.r2(dd.getTransitionAlpha())
                    + " drawable=" + (dd instanceof ImageView
                    ? ((ImageView) dd).getDrawable() : "-"))
                    + " depthHidden=" + Main.sDepthHidden + " oemWants=" + Main.sDeductedWanted
                    + " held=" + Main.sDeductedHeld
                    + " | fgNow " + Diagnostics.layerState(CoverPush.videoSurfaceView("keyguard_foreground_layer"))
                    + " | bgNow " + Diagnostics.layerState(CoverPush.videoSurfaceView("keyguard_background_layer"))
                    + " | deducted " + Diagnostics.layerState(dd)
                    + " | oem " + Main.depthFlags());
        } else if ("layer".equals(op)) {
            // One of MIUI's three wallpaper layers set to a visibility, once, so the
            // screen says which one is drawing what. which = fg | bg | deducted.
            String which = i.getStringExtra("which");
            View lv = "deducted".equals(which) ? Main.findDeductedImageView()
                    : "bg".equals(which) ? CoverPush.videoSurfaceView("keyguard_background_layer")
                    : CoverPush.videoSurfaceView("keyguard_foreground_layer");
            if (lv != null) {
                lv.setVisibility(i.getBooleanExtra("hide", true) ? View.INVISIBLE : View.VISIBLE);
            }
            setResultData(which + " " + Diagnostics.layerState(lv));
        } else if ("uncover".equals(op)) {
            // Hides our cover view only, leaving MIUI's layers as they are, so a
            // screenshot shows what is under it. See CoverPush.guardVideoCover.
            Main.sUncoverProbe = i.getBooleanExtra("on", !Main.sUncoverProbe);
            View cv = Main.sCover;
            if (cv != null) cv.invalidate();
            setResultData("uncover=" + Main.sUncoverProbe);
        } else if ("mediabtn".equals(op)) {
            setResultData(Diagnostics.dumpClickables());
        } else if ("queue".equals(op)) {
            setResultData(Diagnostics.dumpQueues());
        } else if ("texfit".equals(op)) {
            // The screen-sized keyguard texture, on by default (WallpaperProbe's half
            // is the one that does the work). Flipping it here flips that half too,
            // because the two have to agree: with it on, the re-fit that rewrites the
            // user's lockscreen wallpaper is skipped; with it off, that re-fit is the
            // only thing keeping the crossfade affordable.
            Main.sTexFit = i.getBooleanExtra("on", !Main.sTexFit);
            Intent wp = CoverPush.wallpaperIntent("texfit");
            wp.putExtra("on", Main.sTexFit);
            ProbeGuard.send(c, wp);
            Xp.log(Main.TAG + "texture fit to screen " + (Main.sTexFit ? "ON" : "off")
                    + " (wallpaper re-fit " + (Main.sTexFit ? "skipped" : "enabled") + ")");
        } else if ("lyricinfo".equals(op)) {
            // The playing session's metadata, every string key, with lyricInfo written
            // out whole - to see how a player marks who sings which line.
            setResultData(LockLyrics.dumpMetadata(c, Main.sWatched));
        } else if ("lyricdemo".equals(op)) {
            // Plays a database file by id on its own clock, whatever is playing:
            //   --es id 101126 [--ez am true]   or   --ez clear true
            // Cover mode still has to be on - that is what puts the view up.
            if (i.getBooleanExtra("clear", false)) {
                LockLyrics.endDemo(Main.sTrackKey, Main.sWatched);
            } else {
                String id = i.getStringExtra("id");
                LockLyrics.demo(id == null ? "101126" : id,
                        i.getBooleanExtra("am", false));
            }
        } else if ("looper".equals(op)) {
            // What is queued on the main thread, grouped by where it came from. An ANR
            // (2026-09-16 04:10) spent its last seconds in removeCallbacksAndMessages,
            // which is only slow over a very long queue - this says whose it is.
            setResultData(Diagnostics.looperCensus());
        } else if ("stilloff".equals(op)) {
            LockLyrics.sStillOff = i.getBooleanExtra("on", !LockLyrics.sStillOff);
            setResultData("still redraws " + (LockLyrics.sStillOff ? "off" : "on"));
        } else if ("frames".equals(op)) {
            // Who is asking the main thread for frames. The keyguard window was
            // found rendering at 60fps through the AOD's still mode (2026-09-24)
            // with nothing of the lyrics moving.
            setResultData(Diagnostics.frameCensus());
        } else if ("slowlog".equals(op)) {
            // The framework's own slow-message log on the main looper: every message
            // that takes longer than `ms` to run is logged under the tag Looper with
            // its handler and callback. 0 turns it off. Only timing per message, so
            // cheap enough to leave on while a stutter is reproduced.
            int ms = i.getIntExtra("ms", 0);
            try {
                android.os.Looper.class.getMethod("setSlowLogThresholdMs",
                        long.class, long.class).invoke(
                        android.os.Looper.getMainLooper(), (long) ms, 0L);
                setResultData("slowlog " + (ms > 0 ? ms + "ms" : "off"));
            } catch (Throwable t) {
                setResultData("slowlog failed: " + t);
            }
        } else if ("twotap".equals(op)) {
            setResultData("twoFingerDowns=" + LockTaps.sTwoSeen + " fired=" + LockTaps.sTwoFired
                    + " maxPointersSeen=" + LockTaps.sTwoMaxPointers + " trail=" + LockTaps.sTwoTrail
                    + " lastTwoTrail=" + LockTaps.sTwoTrailLast + " cancelled=" + LockTaps.sTwoCancelled
                    + " last=" + LockTaps.sTwoWhy
                    + " lyrics=" + LockLyrics.sEnabled
                    + " tap=" + (LockLyrics.sTapHidden ? "hidden" : "shown"));
        } else if ("cardvideo".equals(op)) {
            // The square card's motion cover, fixed file for now; see CardVideo.
            if (i.getBooleanExtra("stats", false)) {
                setResultData(CardVideo.statsAndReset());
            } else if (!i.getBooleanExtra("on", true)) {
                CardVideo.unload("probe");
                setResultData("off");
            } else {
                String path = i.getStringExtra("path");
                if (path == null) {
                    path = new java.io.File(c.getFilesDir(), "hyperchanger_hmc_next_card_video.mp4")
                            .getPath();
                }
                CardVideo.load(c, path);
                setResultData("loaded=" + CardVideo.loaded() + " " + path);
            }
        } else if ("cardstate".equals(op)) {
            // The square card's playback scale, next to what the session says - for
            // "the card stayed small", where the log is not there to read.
            MediaController w = Main.sWatched;
            PlaybackState ps = w == null ? null : w.getPlaybackState();
            setResultData("toggles=[" + Main.toggleCost() + "] session="
                    + (ps == null ? "none" : ps.getState())
                    + " playing=" + Main.sCoverCardPlaying + " "
                    + CoverCardLayer.describe());
        } else if ("tail".equals(op)) {
            // This process's own recent log lines, the only way to read Xp.log on a
            // phone whose logd keeps nothing below error level. --es grep x filters,
            // --ei n caps the count (default 40, kept small for the binder reply).
            setResultData(Xp.tail(i.getStringExtra("grep"), i.getIntExtra("n", 40)));
        } else if ("lyricstate".equals(op)) {
            String st = LockLyrics.describe();
            Xp.log(Main.TAG + "lyrics: " + st);
            // Also as the broadcast's result, which `am broadcast` prints: on a
            // phone whose LSPosed log drops INFO lines this is the only way to read it.
            setResultData(st);
        } else if ("lyricraw".equals(op)) {
            // The session's lyricInfo exactly as it was published, to a file.
            // metadump truncates every value at 160 characters, which is enough to
            // see that a payload is there and not enough to see what shape it is -
            // and NetEase's is a shape this module does not read yet.
            String raw = LyricSource.lyricInfoOf(Main.sWatched);
            java.io.File rf = new java.io.File(c.getFilesDir(), "hyperchanger_hmc_next_lyricraw.txt");
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(rf);
                os.write((raw == null ? "no lyricInfo on "
                        + (Main.sWatched == null ? "no session"
                           : Main.sWatched.getPackageName()) : raw).getBytes("UTF-8"));
                os.close();
                rf.setReadable(true, false);
                setResultData((raw == null ? "none" : raw.length() + " chars")
                        + " -> " + rf.getAbsolutePath());
            } catch (Throwable t) {
                setResultData("lyricraw failed: " + t);
            }
        } else if ("lyricon".equals(op)) {
            setResultData(LyriconSource.describe());
        } else if ("local".equals(op)) {
            // On a worker: this one reads the media database and then the file, and
            // a broadcast receiver runs on the main thread.
            final MediaController lw = Main.sWatched;
            final java.io.File lf =
                    new java.io.File(c.getFilesDir(), "hyperchanger_hmc_next_local.txt");
            Gen.thread("MCLocalProbe", new Runnable() {
                @Override
                public void run() {
                    // Its own thread: anything thrown here would take SystemUI down.
                    try {
                        describeLocal();
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "local probe failed: " + t);
                    }
                }

                private void describeLocal() {
                    String d = LocalLyrics.describe(Main.sAppCtx, lw);
                    Xp.log(Main.TAG + "local: " + d);
                    try {
                        java.io.FileOutputStream os =
                                new java.io.FileOutputStream(lf);
                        os.write(d.getBytes("UTF-8"));
                        os.close();
                        lf.setReadable(true, false);
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "local write failed: " + t);
                    }
                }
            });
            setResultData("looking -> " + lf.getAbsolutePath());
        } else if ("metadump".equals(op)) {
            String d = LyricSource.dumpMetadata(Main.sWatched);
            Xp.log(Main.TAG + "metadump: " + d);
            setResultData(d);
        } else if ("ncm".equals(op)) {
            // What the by-name route makes of the playing session: the four fields
            // it matches on, the terms it would search, every candidate with how far
            // its duration sits from ours, and which one that proves. On a worker
            // because it is two network round trips, so the answer cannot be this
            // broadcast's result - it goes to a file, which is also the only form
            // of it this phone can read back (the module's INFO log does not
            // survive to logcat here).
            final MediaController w = Main.sWatched;
            // The four fields can also be given by hand, which is how a report of
            // "this song never got lyrics" gets reproduced here without the song:
            //   --es title .. --es artist .. --es album .. --el dur 252236
            final boolean byHand = i.hasExtra("title") || i.hasExtra("artist")
                    || i.hasExtra("album") || i.hasExtra("dur");
            final String qTitle = i.getStringExtra("title");
            final String qArtist = i.getStringExtra("artist");
            final String qAlbum = i.getStringExtra("album");
            final long qDur = i.getLongExtra("dur", 0L);
            final java.io.File out = new java.io.File(c.getFilesDir(), "hyperchanger_hmc_next_ncm.txt");
            Gen.thread("MCNcmProbe", new Runnable() {
                @Override
                public void run() {
                    try {
                        describeNcm();
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "ncm probe failed: " + t);
                    }
                }

                private void describeNcm() {
                    // All three catalogues, in one account. Which of them was asked
                    // and what each one said is the whole question behind "this
                    // song never got lyrics", and asking them one probe at a time
                    // means three runs against three different minutes.
                    NcmLyrics.Query q = byHand
                            ? NcmLyrics.build(qTitle, qArtist, qAlbum, qDur)
                            : NcmLyrics.queryOf(w);
                    String d = (byHand
                            ? NcmLyrics.describe(qTitle, qArtist, qAlbum, qDur)
                            : NcmLyrics.describe(w)) + WebLyrics.describe(q)
                            + OnlineLyrics.probe(w == null ? null : w.getPackageName(), q);
                    Xp.log(Main.TAG + "ncm: " + d);
                    try {
                        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                        os.write(d.getBytes("UTF-8"));
                        os.close();
                        out.setReadable(true, false);
                    } catch (Throwable t) {
                        Xp.w(Main.TAG + "ncm write failed: " + t);
                    }
                }
            });
            setResultData("searching -> " + out.getAbsolutePath());
        } else if ("tweaks".equals(op)) {
            // Which of the HyperOS restrictions actually came off in this process.
            // Over the probe rather than the log because the module's INFO lines are
            // not readable on this device - LSPosed keeps error level only.
            setResultData(HyperTweaks.describe());
        } else if ("fadewp".equals(op)) {
            Main.sFadeWp = i.getBooleanExtra("on", !Main.sFadeWp);
            ModuleState.saveState();
            Xp.log(Main.TAG + "wallpaper crossfade " + (Main.sFadeWp ? "on" : "off"));
        } else if ("vcfade".equals(op)) {
            // Off by default: see the note on sVideoFade. Setting it here re-pushes
            // the art, because the cover video that is on the device right now was
            // built with whichever value was in force when it was pushed.
            Main.sVideoFade = i.getBooleanExtra("on", !Main.sVideoFade);
            ModuleState.saveState();
            Xp.log(Main.TAG + "cover video crossfade " + (Main.sVideoFade ? "on" : "off")
                    + (Main.sCoverMode ? " - re-pushing the art" : ""));
            if (Main.sCoverMode) CoverPush.pushArtAsync(true, false);
        } else if ("fadesync".equals(op)) {
            // --ei mode 0|1|2 picks one; with no mode it cycles, so the three can be
            // compared on the phone with one command instead of three builds.
            int mode = i.getIntExtra("mode", -1);
            Main.sFadeMode = mode >= 0 && mode <= Main.FADE_MODE_STRETCH
                    ? mode : (Main.sFadeMode + 1) % (Main.FADE_MODE_STRETCH + 1);
            ModuleState.saveState();
            Xp.log(Main.TAG + "cover fade mode = " + CoverPush.fadeModeName()
                    + " (0 off, 1 hold for the wallpaper window, 2 stretch by the"
                    + " last measured gap " + CoverPush.sCoverFadeGapMs + "ms)");
        } else if ("tap".equals(op)) {
            Main.sTapToggle = i.getBooleanExtra("on", !Main.sTapToggle);
            ModuleState.saveState();
            Xp.log(Main.TAG + "tap-to-toggle " + (Main.sTapToggle ? "on" : "off"));
        } else if ("clockshot".equals(op)) {
            // For telling the two candidate causes apart by hand: with this off the
            // module never draws the live clock, and the preview falls back to its
            // outline. If the lock screen still breaks up, the capture is innocent.
            PreviewShots.sClockShot = i.getBooleanExtra("on", true);
            Xp.log(Main.TAG + "clock capture " + (PreviewShots.sClockShot ? "on" : "off"));
        } else if ("reload".equals(op)) {
            CoverPush.requestWallpaperReload(c);
        } else if ("lockwp".equals(op)) {
            final Context cc = Main.sAppCtx;
            final boolean clear = i.getBooleanExtra("clear", false);
            final boolean force = i.getBooleanExtra("force", false);
            Main.worker().post(new Runnable() {
                @Override
                public void run() {
                    if (clear) CoverPush.clearLockWallpaper(cc);
                    else CoverPush.ensureLockWallpaper(cc, force);
                }
            });
        } else if ("cover".equals(op)) {
            if (i.getBooleanExtra("on", true)) {
                Main.sCoverWanted = true;
                Main.attachCover();
            } else {
                // Through setCoverEnabled, exactly like a tap, and NOT detachCover()
                // directly. detachCover() only takes our own keyguard view away; the
                // cover itself is a texture in the wallpaper process, and handing
                // that back is pushArtAsync(false) inside setCoverEnabled. Calling
                // the short one leaves the album art on the lock screen for good -
                // seen on device, after a measurement session that toggled cover mode
                // with this probe and then could not get the wallpaper back.
                Main.sCoverWanted = false;
                Main.setCoverEnabled(false, false, false);
            }

        } else if ("callclock".equals(op)) {
            Main.callOnClockViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                    i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                    i.getBooleanExtra("b", false));
        } else if ("call".equals(op)) {
            Main.callOnTimeViews(i.getStringExtra("m"), i.getStringExtra("kind"),
                    i.getFloatExtra("f", 0f), i.getIntExtra("n", 0),
                    i.getBooleanExtra("b", false));
        } else if ("glass".equals(op)) {
            ClockGlass.setGlass(i.getBooleanExtra("on", false));
        } else if ("scale".equals(op)) {
            float k = i.getFloatExtra("k", -1f);
            Main.sSizeScale = k > 0 ? k : Float.NaN;
            Xp.log(Main.TAG + "size scale = " + Main.sSizeScale);
        } else if ("params".equals(op)) {
            Main.setClockParams(i.getFloatExtra("w", -1f), i.getFloatExtra("h", -1f),
                    i.getFloatExtra("weight", -1f), i.getIntExtra("size", -1),
                    i.getFloatExtra("sizei", -1f));
        } else if ("api".equals(op)) {
            Diagnostics.dumpApi(i.getStringExtra("id"));
        } else if ("oemtrans".equals(op)) {
            Diagnostics.dumpOemTranslation(i.getFloatExtra("y", 1200f));
        } else if ("viewtypes".equals(op)) {
            Diagnostics.dumpClockViewTypes();
        } else if ("notif".equals(op)) {
            Diagnostics.dumpNotifState();
        } else if ("chargeanim".equals(op)) {
            setResultData(ChargeAnim.chargeAnimReport());
        } else if ("views".equals(op)) {
            Diagnostics.dumpViewTree(i.getBooleanExtra("root", false));
        } else if ("art".equals(op)) {
            // 取餐码卡片的素材。The app asks SystemUI to fetch them, because the two are
            // different uids and it is SystemUI that draws the card - the app has nowhere
            // to put a file SystemUI could read. --es do fetch starts one; without it the
            // answer is only what the last attempt did, which is what a settings row that
            // has to offer a download link when it failed needs. The fetch takes a while,
            // so it runs off this thread and reports by changing its own state.
            setResultData("art=bundled in assets/coloros/pickupcode");
        } else if ("diag".equals(op)) {
            String report = Diagnostics.clockReport();
            android.os.Bundle rb = new android.os.Bundle();
            rb.putString("report", report);
            setResultExtras(rb);
            // Logged as well as answered: an adb run of this op should not have to
            // go through the app to see it.
            for (String line : report.split("\n")) Xp.log(Main.TAG + line);
        } else if ("vtree".equals(op)) {
            Diagnostics.dumpViewTree();
        } else if ("bounds".equals(op)) {
            Diagnostics.dumpClockBounds();
        } else if ("geom".equals(op)) {
            Diagnostics.dumpGeom();
        } else if ("ink".equals(op)) {
            Diagnostics.dumpInk();
        } else if ("mini".equals(op)) {
            // To a file, with only its head in the reply: the whole of it - every
            // ring of traces - outgrew what a broadcast's result can carry, the
            // result never got back, and SystemUI was killed for the broadcast
            // (ANR, 2026-09-30). Read it with su cat.
            String all = MiniPlayerRuntime.describe();
            java.io.File mf = new java.io.File(c.getFilesDir(), "hyperchanger_hmc_next_mini.txt");
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(mf);
                os.write(all.getBytes("UTF-8"));
                os.close();
                mf.setReadable(true, false);
                setResultData(all.length() + " chars -> " + mf.getAbsolutePath()
                        + "\n" + all.substring(0, Math.min(all.length(), 1500)));
            } catch (Throwable t) {
                setResultData("mini not written: " + t + "\n"
                        + all.substring(0, Math.min(all.length(), 1500)));
            }
        } else if ("coverpalette".equals(op)) {
            if (i.getBooleanExtra("now", false)) CoverPalette.refreshSoon(0L);
            setResultData(CoverPalette.describe());
        } else if ("notifpresence".equals(op)) {
            setResultData(NotifPresence.describe());
        } else if ("datestatus".equals(op)) {
            setResultData(DateStatus.INSTANCE.describe());
        } else if ("palette".equals(op)) {
            setResultData(PaletteThrottle.INSTANCE.describe());
        } else if ("rowtree".equals(op)) {
            String key = i.getStringExtra("key");
            setResultData(MiniPlayerRuntime.rowTree(key == null ? "" : key));
        } else if ("fold".equals(op)) {
            setResultData(CardTouch.describeFold());
        } else if ("fod".equals(op)) {
            try {
                setResultData(MiniPlayerRuntime.fingerprintProbe(i.getStringExtra("rect")));
            } catch (Throwable t) {
                setResultData("fod failed: " + t);
            }
        } else if ("numstate".equals(op)) {
            setResultData(NumStateProbe.run(c, i));
        } else if ("keepawake".equals(op)) {
            // --ez on true|false: the lock screen held lit for a test run.
            setResultData(Main.keepAwake(i.getBooleanExtra("on", true)));
        } else if ("perfoff".equals(op)) {
            // --es off glass,card,flight: those cover-entry effects skipped, to
            // weigh each one's GPU cost against gfxinfo. Empty puts them all back.
            String off = i.getStringExtra("off");
            if (off != null) Main.sPerfOff = off;
            setResultData("perfoff=" + Main.sPerfOff);
        } else if ("alphasweep".equals(op)) {
            // --ei ms N starts a sweep; without it, reads the last one back.
            int ms = i.getIntExtra("ms", 0);
            if (ms > 0) MotionTrace.startAlphaSweep(ms);
            setResultData(MotionTrace.sSweep);
        } else if ("motiontrace".equals(op)) {
            MotionTrace.arm(i.getIntExtra("n", 4));
        } else if ("geomtrace".equals(op)) {
            Diagnostics.startGeomTrace(i.getIntExtra("ms", 4000));
        } else if ("clockmove".equals(op)) {
            // --ei ms N arms a recording; without it, reads the last one back.
            int ms = i.getIntExtra("ms", 0);
            if (ms > 0) ClockMove.arm(ms);
            setResultData(ClockMove.read());
        } else if ("state".equals(op)) {
            Xp.log(Main.TAG + "state: holdY=" + Main.sHoldY
                    + " lastSystemY=" + Main.sLastSystemY + " clock " + ClockCollapse.describe()
                    + " container=" + (Main.sContainer != null) + " verbose=" + Main.sVerbose);
            Xp.log(Main.TAG + "cover: on=" + Main.sCoverMode + " auto=" + Main.sAuto
                    + " bias=" + Main.sBias + " screen=" + Main.sScreenW + "x" + Main.sScreenH
                    + " card=" + (MediaCard.sCardKnown ? (MediaCard.sCardShowing ? MediaCard.sCardKey : "gone") : "unknown")
                    + " following=" + (Main.sWatched == null ? "none" : Main.sWatched.getPackageName())
                    + " track=" + Main.sTrackKey);
            Xp.log(Main.TAG + "card rect: " + MediaCard.sCardL + "," + MediaCard.sCardT + " "
                    + MediaCard.sCardW + "x" + MediaCard.sCardH + " hideArt=" + Main.sMcHideArt
                    + " titleTap=" + Main.sMcTitleTap
                    + " title=" + (Main.sCardTitleTapped != null) + " cardP=" + MediaCard.sCardP
                    + " pageBtn=" + Main.sMcPageBtn + " " + CardPageButton.describe());
            Xp.log(Main.TAG + "tap: toggle=" + Main.sTapToggle
                    + " suppressed=" + Main.sTapSuppressed
                    + " bouncer=" + Main.bouncerUp()
                    + " wallpaperFade=" + Main.sFadeWp);
        } else if ("verbose".equals(op)) {
            Main.sVerbose = i.getBooleanExtra("on", !Main.sVerbose);
            LockLyrics.verbose = Main.sVerbose;
            Xp.log(Main.TAG + "verbose=" + Main.sVerbose);
        } else {
            return false;
        }
        return true;
    }

    /**
     * 取餐码's cloud list, fetched on the app's button. True when the answer will come later
     * through goAsync - the receipt is then this op's to send, not onReceive's.
     */
    private boolean cloudFetch(Context c, Intent i) throws Exception {
        boolean async = false;
            // 取餐码的云端名单（OPPO 的 iwisdom，AIFluidWxAppletList）。一次网络往返，
            // 不能在 onReceive 里做——那跑在 SystemUI 的主线程上——所以它走 goAsync：
            // 收据归这个分支发。
            if ("fetch".equals(i.getStringExtra("do"))) {
                final PendingResult receipt = goAsync();
                async = true;
                // Finished either way: a receipt never finished holds the app's ordered
                // broadcast until it times out.
                Gen.thread("mc-pickup-cloud", () -> {
                    String said;
                    boolean ok = false;
                    try {
                        PickupCloud.Fetched r = PickupCloud.INSTANCE.fetch(c);
                        said = r.getSaid();
                        ok = r.getOk();
                    } catch (Throwable t) {
                        said = "同步失败：" + t.getClass().getSimpleName();
                    }
                    // The outcome as a field, the sentence only for showing.
                    android.os.Bundle outcome = new android.os.Bundle();
                    outcome.putBoolean("ok", ok);
                    receipt.setResultExtras(outcome);
                    receipt.setResultData(said);
                    receipt.setResultCode(Main.OP_ACK);
                    receipt.finish();
                }, receipt::finish);
            } else {
                setResultData(PickupCloud.INSTANCE.describe());
            }

        return async;
    }
}
