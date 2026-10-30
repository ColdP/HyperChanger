// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import btm.m.os4.systemuihook.hypermusiccover.next.lyrics.LockLyrics;
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime;
import btm.m.os4.systemuihook.hypermusiccover.LegacyShadeBridge;
import btm.m.os4.systemuihook.hypermusiccover.next.transit.AmapTransitScene;
import btm.m.os4.systemuihook.hypermusiccover.next.pickup.PickupCodeIsland;

/**
 * The module's settings file in SystemUI's storage: writing every setting out (at once, or
 * debounced for values that change per frame) and reading them back at start, one key at a
 * time so a bad value costs only itself.
 *
 * Carved out of Main as it was (2026-10-14, after Diagnostics): every member moved whole with
 * its comments, references across the line prefixed with Main. or ModuleState., nothing else
 * changed.
 */
public final class ModuleState {
    private ModuleState() {}

    static final String STATE_FILE = "hyperchanger_hmc_next_state";

    public static void saveState() {
        if (Main.sAppCtx == null) return;
        // Never while loadState is still walking the file. Some of the setters it applies values
        // through save as part of their own contract - setClockHeightDp does - and a save taken
        // mid-parse writes every key the parse has NOT reached yet at its DEFAULT. The keys that
        // go through a setter sit ahead of mctap, tap, lyrickeep and lyrichdr in the file, so
        // restoring a good file once quietly rewrote it with those four off. The run itself looked
        // fine, because the loop went on to fill memory in correctly from the copy it had already
        // read; the damage only showed at the NEXT SystemUI start, which is why it read as
        // "installing the app turns some switches off". loadState saves once at the end instead.
        if (sLoading) return;
        android.util.AtomicFile af = new android.util.AtomicFile(
                new java.io.File(Main.sAppCtx.getFilesDir(), STATE_FILE));
        java.io.FileOutputStream f = null;
        try {
            f = af.startWrite();
            f.write(("cover=" + (Main.sCoverMode ? 1 : 0)
                    + "\ncoverfeature=" + (Main.sCoverFeatureOn ? 1 : 0)
                    + "\nbias=" + Main.sBias
                    // Only the mode. The square's size, place and corners, the clock's offset,
                    // the glass end and the spring are all fixed now, and loadState does not read
                    // any of them - writing them down would be a value nothing could ever have
                    // set. See loadState.
                    + "\ncoverstyle=" + Main.sCoverCardStyle.mode
                    // A pending pre-dp value is written as itself: it cannot be converted until
                    // a confirmed box exists, and writing the default over it would lose the
                    // setting the user actually had.
                    + "\nclock=" + (Float.isNaN(Main.sClockLegacyK) ? Main.sClockHeightDp : Main.sClockLegacyK)
                    + (Float.isNaN(Main.sClockSize) ? "" : "\nclocksize=" + Main.sClockSize)
                    // A measurement, like cardrect: the full clock the size is a fraction of.
                    + (ClockCollapse.fullUnitState() == null ? ""
                            : "\nclockfull=" + ClockCollapse.fullUnitState())
                    // The anchored styles' full clock, which every other style's size is read
                    // against - kept apart so switching to one of those does not lose it.
                    + (Float.isNaN(ClockCollapse.refUnitState()) ? ""
                            : "\nclockref=" + ClockCollapse.refUnitState())
                    + "\nmctap=" + (Main.sMcTitleTap ? 1 : 0)
                    + "\nmcpage=" + (Main.sMcPageBtn ? 1 : 0)
                    + "\ntap=" + (Main.sTapToggle ? 1 : 0)
                    // Notification-center settings remain exclusively in the host state file.
                    + "\nfadewp=" + (Main.sFadeWp ? 1 : 0)
                    + "\nmotion=" + (MotionCover.sEnabled ? 1 : 0)
                    + "\nmotiondata=" + (MotionCover.sOnData ? 1 : 0)
                    + "\nvcfade=" + (Main.sVideoFade ? 1 : 0)
                    + "\nfsmode2=" + Main.sFadeMode
                    // A measurement rather than a setting, and kept for the same reason the
                    // geometry is: a fresh SystemUI should not have to relearn it to use it.
                    + "\ncovergap=" + CoverPush.sCoverFadeGapMs
                    + "\nhidefp=" + (Fingerprint.sHideFp ? 1 : 0)
                    // The lyrics master switch, a setting again (2026-10-14). A new key rather than
                    // the old "lyrics": a file from when that one was written may still hold a
                    // 0 nobody remembers setting. lyrichidden is the two-finger tap's page.
                    + "\nlyricmaster=" + (LockLyrics.sEnabled ? 1 : 0)
                    + "\ncolon=" + (HyperTweaks.sForceColon ? 1 : 0)
                    + "\nseekglow=" + (HyperTweaks.sBarGlow ? 1 : 0)
                    + "\nlyrickeep=" + (LockLyrics.sKeepOn ? 1 : 0)
                    + "\nlyrichidden=" + (LockLyrics.sTapHidden ? 1 : 0)
                    + "\nlyrichdr=" + (LockLyrics.sHdr ? 1 : 0)
                    // Written as 1 or 0 like the rest, but read back as the default when absent:
                    // the key did not exist before this setting did, and the lyrics are supposed
                    // to look the way they always have on a file that predates it.
                    + "\nlyrictrans=" + (LockLyrics.sTrans ? 1 : 0)
                    + "\naodsmall=" + (Main.sAodSmall ? 1 : 0)
                    // Off when absent, which is what every file from before it had.
                    + "\nlyricroma=" + (LockLyrics.sRoma ? 1 : 0)
                    // On when absent: every file from before it looked lyrics up online.
                    + "\nlyriconline=" + (LockLyrics.sOnline ? 1 : 0)
                    // 0 left, 1 centre, 2 right, and left when the key is absent: a file from
                    // before this setting is one where the lyrics always sat left.
                    + "\nlyricalign=" + LockLyrics.sAlign
                    // The two sizes are the user's again (the 歌词外观 page, 2026-10-22); the
                    // band's other four values are still fixed and not written, see LyricStyle.
                    + "\nlyricsize=" + LockLyrics.sStyle.sizeSp
                    + "\nlyricbgsize=" + LockLyrics.sStyle.bgSizeSp
                    // One line of JSON: no '=' or newline in it to confuse the reader below.
                    + "\nlyricappearance=" + LockLyrics.sAppearance.toJson()
                    // Not a setting - whether the last lookup got its lyric from the session.
                    // Kept across restarts so the settings page does not accuse a working
                    // provider module of doing nothing merely because nothing has played yet;
                    // updated in both directions so it stops claiming one is working once it
                    // is not.
                    + "\nsawlyric=" + (LockLyrics.sSawSessionLyric ? 1 : 0)
                    + "\nfpavoid=" + Fingerprint.sFpAvoid
                    // On when absent: the trip's page and island shipped before their switch.
                    + "\ntransit=" + (AmapTransitScene.sOn ? 1 : 0)
                    + "\npickup=" + (PickupCodeIsland.sOn ? 1 : 0)
                    // Read back at startup, so it has to be written too: without it
                    // `--ez card false` lasted until the next SystemUI restart.
                    + "\ncard=" + (PickupCodeIsland.sCard ? 1 : 0)
                    + "\nminicfg=" + android.util.Base64.encodeToString(
                            MiniPlayerRuntime.configJson(Main.sAppCtx).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP)
                    // Not a setting - a measurement. Kept so the app's preview is to scale from
                    // the first frame after a SystemUI restart, instead of only once the phone
                    // has been locked again.
                    + "\ncardrect=" + MediaCard.sCardL + "," + MediaCard.sCardT + "," + MediaCard.sCardW + "," + MediaCard.sCardH
                    + "\n").getBytes());
            af.finishWrite(f);
        } catch (Throwable t) {
            if (f != null) af.failWrite(f);
            Xp.w(Main.TAG + "saveState failed: " + t);
        }
    }

    static final Runnable sSaveState = new Runnable() {
        @Override
        public void run() { saveState(); }
    };

    /**
     * For values that change as fast as the screen draws. The card rectangle is sampled from a
     * pre-draw listener while the card is animating in, and writing the file on every frame of
     * that would be a file write per frame for a number that settles in a few hundred ms.
     */
    static void saveStateSoon() {
        Main.main().removeCallbacks(sSaveState);
        Main.main().postDelayed(sSaveState, 500L);
    }

    /** Set while the file is being applied, to keep a setter from writing a half-read state back. */
    static volatile boolean sLoading;

    /** The file, or the backup an interrupted write leaves (AtomicFile.exists is not in the SDK). */
    static boolean stateExists(android.util.AtomicFile f) {
        java.io.File base = f.getBaseFile();
        return base.exists() || new java.io.File(base.getPath() + ".bak").exists();
    }

    static void loadState() {
        if (Main.sAppCtx == null) return;
        // An AtomicFile, so a SystemUI killed in the middle of saveState leaves the last whole
        // file behind rather than a truncated one that silently resets every setting.
        android.util.AtomicFile f = new android.util.AtomicFile(
                new java.io.File(Main.sAppCtx.getFilesDir(), STATE_FILE));
        if (!stateExists(f)) return;
        boolean cover = false;
        sLoading = true;
        try {
            String body = new String(f.readFully()).trim();
            // The first version of this file was a bare "1"/"0"; keep reading those so an
            // upgrade does not silently drop a cover that is still on the phone.
            if (body.length() <= 1) {
                cover = "1".equals(body);
            } else {
                for (String line : body.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    // Host preferences are applied once after the measurements below. Do not
                    // restore stale feature flags or trigger lyric lookups before that apply.
                    switch (k) {
                        case "coverfeature": case "coverstyle": case "clocksize":
                        case "motion": case "motiondata": case "mcpage":
                        case "lyricmaster": case "lyrickeep": case "lyrichdr":
                        case "lyrictrans": case "lyricroma": case "lyriconline":
                        case "lyricalign": case "lyricsize": case "lyricbgsize":
                        case "lyricappearance": case "minicfg":
                        case "transit": case "pickup": case "card":
                        case "colon": case "seekglow": case "hidefp":
                            continue;
                    }
                    // Every key in its own try. The loop's outer catch RETURNS, so without this
                    // one unreadable value takes every setting BELOW it in the file down with it,
                    // silently - and the log would say "loadState failed" without ever naming the
                    // key. One bad number should cost its own setting and nothing else.
                    try {
                        if ("cover".equals(k)) cover = "1".equals(v);
                        else if ("coverfeature".equals(k)) Main.sCoverFeatureOn = "1".equals(v);
                        // "auto" was a stored setting; following the card is unconditional now.
                        else if ("bias".equals(k)) Main.sBias = Float.parseFloat(v);
                        else if ("coverstyle".equals(k)) Main.sCoverCardStyle =
                                Main.sCoverCardStyle.with("mode", Float.parseFloat(v));
                        // covercardfill, covercardpos, covercardcorner and the dp covercardsize
                        // before them are not read. The square's size, place and corners are
                        // fixed, and a file from before that is not allowed to hold them open -
                        // see CoverCardStyle. coverstyle IS read: which of the two shapes the
                        // cover takes is still the user's.
                        else if ("clock".equals(k)) Main.setClockHeightDp(Float.parseFloat(v));
                        else if ("clockfull".equals(k)) ClockCollapse.restoreFullUnit(v);
                        else if ("clockref".equals(k)) ClockCollapse.restoreRefUnit(Float.parseFloat(v));
                        else if ("clocksize".equals(k)) Main.setClockSize(Float.parseFloat(v));
                        // clockoff, glass and spring are not read: the clock's offset, the glass
                        // clock's end and the transition's spring are all fixed. Each is still
                        // reachable from its own op, for the session.
                        else if ("mctap".equals(k)) Main.sMcTitleTap = "1".equals(v);
                        else if ("mcpage".equals(k)) Main.sMcPageBtn = "1".equals(v);
                        else if ("tap".equals(k)) Main.sTapToggle = "1".equals(v);
                        else if ("fadewp".equals(k)) Main.sFadeWp = "1".equals(v);
                        else if ("motion".equals(k)) MotionCover.sEnabled = "1".equals(v);
                        else if ("motiondata".equals(k)) MotionCover.sOnData = "1".equals(v);
                        else if ("vcfade".equals(k)) Main.sVideoFade = "1".equals(v);
                        else if ("fsmode2".equals(k)) Main.sFadeMode = Integer.parseInt(v);
                        else if ("covergap".equals(k)) CoverPush.sCoverFadeGapMs = Long.parseLong(v);
                        else if ("hidefp".equals(k)) Fingerprint.sHideFp = "1".equals(v);
                        else if ("colon".equals(k)) HyperTweaks.sForceColon = "1".equals(v);
                        else if ("seekglow".equals(k)) HyperTweaks.sBarGlow = "1".equals(v);
                        // The old "lyrics" key is not read, see saveState; lyricmaster is.
                        else if ("lyricmaster".equals(k)) LockLyrics.sEnabled = "1".equals(v);
                        else if ("lyrickeep".equals(k)) LockLyrics.sKeepOn = "1".equals(v);
                        else if ("lyrichidden".equals(k)) LockLyrics.sTapHidden = "1".equals(v);
                        else if ("lyrichdr".equals(k)) LockLyrics.sHdr = "1".equals(v);
                        else if ("lyrictrans".equals(k)) LockLyrics.sTrans = "1".equals(v);
                        else if ("aodsmall".equals(k)) Main.sAodSmall = "1".equals(v);
                        else if ("lyricroma".equals(k)) LockLyrics.sRoma = "1".equals(v);
                        else if ("lyriconline".equals(k)) LockLyrics.sOnline = "1".equals(v);
                        // Clamped in the setter; absent or unreadable means left, see saveState.
                        else if ("lyricalign".equals(k)) LockLyrics.setAlign(Integer.parseInt(v));
                        // lyricmaxlines is not read: every overlapping line is lit, see LyricFocus.
                        // Clamped by LyricStyle.with, like an op's value.
                        else if ("lyricsize".equals(k)) LockLyrics.setStyle("size", Float.parseFloat(v));
                        else if ("lyricbgsize".equals(k)) LockLyrics.setStyle("bgsize", Float.parseFloat(v));
                        else if ("lyricappearance".equals(k)) LockLyrics.setAppearance(v);
                        // The dp lyricoff and lyricgap from before the shares are dropped: what
                        // they meant depends on the room, which is not known here. lyricfill,
                        // lyricpos, lyricside and lyricweight are dropped with them: the band
                        // and its weight are fixed, see LyricStyle.DEFAULT.
                        else if ("sawlyric".equals(k)) {
                            LockLyrics.sSawSessionLyric = "1".equals(v);
                        }
                        else if ("fpavoid".equals(k)) Fingerprint.sFpAvoid = Integer.parseInt(v);
                        // Excluded standalone feature state is not restored.
                        // Excluded standalone feature state is not restored.
                        // Excluded standalone feature state is not restored.
                        // Not applyConfig: this file is migration data, and a config the user has
                        // since changed must win over what was saved here (see restoreLegacyConfig).
                        else if ("minicfg".equals(k)) MiniPlayerRuntime.restoreLegacyConfig(Main.sAppCtx,
                                new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                                        java.nio.charset.StandardCharsets.UTF_8));
                        // The whole shade settings page, in one prefix - the keys and their
                        // meaning belong to LegacyShadeBridge.configure.
                        else if ("cardrect".equals(k)) {
                            String[] r = v.split(",");
                            // Same sanity check the sampler applies, because a file written
                            // before it existed can hold a reading taken from the shade.
                            if (r.length == 4 && Integer.parseInt(r[1]) >= Main.sScreenH / 3) {
                                MediaCard.sCardL = Integer.parseInt(r[0]); MediaCard.sCardT = Integer.parseInt(r[1]);
                                MediaCard.sCardW = Integer.parseInt(r[2]); MediaCard.sCardH = Integer.parseInt(r[3]);
                            }
                        }
                    } catch (Throwable t) {
                        Xp.log(Main.TAG + "setting unreadable, keeping the default: "
                                + k + "=" + v + " (" + t + ")");
                    }
                    // "offdelay" was the pause timer, before the card became the switch.
                }
            }
        } catch (Throwable t) {
            Xp.w(Main.TAG + "loadState failed: " + t);
            return;
        } finally {
            sLoading = false;
        }
        // The mode fpavoid has just come back to. A policy flow the OEM's combine already had
        // attached was made with whatever this process started at - 0, "leave it to the system" -
        // and NotificationSink.withAvoidance hands the decision to that flow whenever it carries
        // one, so a phone restarted with 始终下沉 saved sank nothing until the setting was touched
        // again. Nothing else refreshed this on the way up: 展开背景时下移通知 used to, by
        // accident, on its scene changes and only while it was switched on (2026-10-09).
        Main.refreshNotificationSink();
        Xp.log(Main.TAG + "state restored: cover=" + cover + " bias=" + Main.sBias);
        // Remote host preferences decide automatic entry after restoration.
        // HyperMusicCoverRuntimeBridge applies auto after restoring measurements and tap state.
    }
}
