// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerConfig
import btm.m.os4.systemuihook.hypermusiccover.next.island.MiniPlayerRuntime
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

/** The host owns package dispatch; upstream owns media state, gestures and view lifecycles. */
object HyperMusicCoverRuntimeBridge {
    @JvmStatic fun tr(context: Context?, key: String, fallback: String): String =
        context?.let { UnlockIslandRuntime.tr(it, key, fallback) } ?: fallback

    @Volatile internal var musicEnabled = false
        private set
    @Volatile internal var mediaMode = LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
        private set
    @Volatile private var autoCoverEntry = false

    @JvmStatic fun automaticCoverEntryEnabled(): Boolean = autoCoverEntry
    @JvmStatic fun musicCoverEnabled(): Boolean = musicEnabled

    @JvmStatic fun coverAllowed(): Boolean = preferences?.let {
        HmcConflictMonitor.policy.cover(musicLockscreenMaster(it), it.getBoolean("lockscreen_music_lockscreen_enabled", false))
    } == true
    @JvmStatic fun lyricsAllowed(): Boolean = preferences?.let {
        HmcConflictMonitor.policy.lyrics(musicLockscreenMaster(it),
            it.getBoolean("lockscreen_music_lyrics_enabled", false) || it.getBoolean("lockscreen_mini_player_lyrics_enabled", false))
    } == true
    @JvmStatic fun capsuleAllowed(): Boolean = preferences?.let {
        HmcConflictMonitor.policy.capsule(it.getBoolean("lockscreen_mini_player_enabled", false))
    } == true
    @JvmStatic fun sceneLayoutAllowed(): Boolean = HmcConflictMonitor.policy.sceneLayout && (coverAllowed() || lyricsAllowed())

    /** Groups follow their consumers: lifecycle captures remain live; mutations yield. */
    @JvmStatic fun allowsHook(id: String): Boolean = when {
        id.startsWith("sysui.clock.container_") || id.startsWith("sysui.wallpaper.manager_ctor") ||
            id.startsWith("sysui.mini.shortcut_views") || id.startsWith("wall.app.create") ||
            id.startsWith("wall.engine.") -> true
        id.startsWith("wall.") -> coverAllowed()
        id.startsWith("sysui.mini.") || id.startsWith("sysui.island.") ||
            id.startsWith("sysui.numstate.") || id.startsWith("sysui.datestatus.") ||
            id.startsWith("sysui.notif.presence") -> capsuleAllowed()
        id.startsWith("sysui.clock.") || id.startsWith("sysui.depth.") ||
            id.startsWith("sysui.notif.state_change") || id.startsWith("sysui.aod.") ||
            id.startsWith("sysui.palette.") || id.startsWith("sysui.charge.") ||
            id.startsWith("sysui.wallpaper.black") || id.startsWith("sysui.screen.") -> sceneLayoutAllowed()
        id.startsWith("sysui.wallpaper.unlock_fade") -> coverAllowed()
        id.startsWith("sysui.lyrics.") -> lyricsAllowed()
        id.startsWith("sysui.mediacard.") || id.startsWith("sysui.transport.") ||
            id.startsWith("sysui.taps.") -> coverAllowed() || lyricsAllowed() || capsuleAllowed()
        else -> true
    }

    internal fun beforePolicyChange(old: MusicLockscreenPolicy, next: MusicLockscreenPolicy) {
        if (context != null && old.sceneLayout && !next.sceneLayout) Main.releaseHostLayout()
    }

    /** The new controller remains the sole owner of native media visibility. */
    @JvmStatic fun suppressNativeMedia(dynamicSuppression: Boolean): Boolean =
        if (HmcConflictMonitor.policy.coverConflict || HmcConflictMonitor.policy.lyricsConflict) false
        else if (musicEnabled) dynamicSuppression else when (mediaMode) {
            LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE -> true
            LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC -> dynamicSuppression
            else -> false
        }

    private var preferences: SharedPreferences? = null
    private var context: Context? = null
    private var installed = false
    private var wallpaperProcess = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { applySettings() }
    private val sentSettings = HashMap<String, String>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == HMC_NEXT_SETTINGS_KEY || key.startsWith("lockscreen_") ||
            key.startsWith("mini_player_") || key.startsWith("soft_glass_")) {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }

    internal fun install(module: XposedModule, pkg: String, loader: ClassLoader,
                         material: (ImageView, ClassLoader) -> Unit) {
        if (!ModuleSafetyRuntime.allows(SafetySection.LOCKSCREEN) ||
            !ModuleSafetyRuntime.allows(SafetySection.NOTIFICATIONS)) return
        if (preferences == null && pkg in setOf("com.android.systemui", "com.miui.miwallpaper")) {
            preferences = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP).also {
                it.registerOnSharedPreferenceChangeListener(listener)
            }
        }
        when (pkg) {
            "com.miui.miwallpaper" -> {
                wallpaperProcess = true
                Main.installWallpaper(module, loader)
            }
            "com.apple.android.music" -> Main.installApple(module, loader)
            "com.autonavi.minimap" -> if (ModuleSafetyRuntime.allows(SafetySection.ISLAND)) Main.installAmap(module, loader)
            "com.android.systemui" -> {
                if (installed) return
                installed = true
                MiniPlayerRuntime.hostMaterial = material
                Main.install(module, loader)
            }
        }
    }

    @JvmStatic fun onRuntimeReady(appContext: Context) {
        context = appContext.applicationContext
        HmcConflictMonitor.startNow(appContext) { applySettings() }
        sentSettings.clear()
        applySettings()
    }

    @JvmStatic fun onWallpaperReady(appContext: Context) {
        HmcConflictMonitor.startNow(appContext) {
            btm.m.os4.systemuihook.hypermusiccover.next.WallpaperProbe.hostPolicyChanged(coverAllowed())
        }
    }

    private fun send(op: String, configure: Intent.() -> Unit = {}) {
        val ctx = context ?: return
        val intent = Intent("btm.m.os4.systemuihook.hypermusiccover.next.PROBE")
            .setPackage("com.android.systemui").putExtra("op", op).apply(configure)
        val key = op + ":" + intent.getStringExtra("key").orEmpty()
        val value = intent.toUri(0)
        if (sentSettings[key] == value) return
        if (Main.applyHostSetting(ctx, intent)) sentSettings[key] = value
    }

    private fun applySettings() {
        val prefs = preferences ?: return
        if (wallpaperProcess) {
            btm.m.os4.systemuihook.hypermusiccover.next.WallpaperProbe.hostPolicyChanged(coverAllowed())
            return
        }
        val ctx = context ?: return
        val s = readHyperMusicCoverSettings(prefs)
        val music = coverAllowed()
        val lyrics = lyricsAllowed()
        val island = capsuleAllowed() &&
            ModuleSafetyRuntime.allows(SafetySection.ISLAND)
        val mode = if (prefs.contains(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE))
            prefs.getInt(KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE, 0).coerceIn(0, 2)
        else if (prefs.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION, false))
            LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE else LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
        musicEnabled = music
        mediaMode = if (island) mode else LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
        autoCoverEntry = music && s.autoEnter
        val wasScene = Main.coverModeOn()
        Main.updateHostFeatures(music, lyrics, sceneLayoutAllowed())
        val config = JSONObject(MiniPlayerConfig.defaultJson()).apply {
            put(MiniPlayerConfig.ENABLED, island)
            put(MiniPlayerConfig.WIDTH, prefs.getFloat("lockscreen_mini_player_width", 240f))
            put(MiniPlayerConfig.HEIGHT_RADIUS, prefs.readMiniPlayerHeightRadius())
            put(MiniPlayerConfig.ART_RADIUS, prefs.getFloat("lockscreen_mini_player_artwork_corner_radius", 12f))
            put(MiniPlayerConfig.MEDIA_COLLAPSED_DEFAULT, mode != LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE)
            put(MiniPlayerConfig.STYLE, s.islandStyle)
            put(MiniPlayerConfig.ADAPTIVE_WIDTH, s.adaptiveWidth)
            put(MiniPlayerConfig.NORMALS_IN_STACK, !island || !s.ordinaryNotifications)
            put(MiniPlayerConfig.GROUP_NOTIFICATIONS_BY_APP, s.groupByApp)
            put(MiniPlayerConfig.STATUS_AT_DATE, s.statusAtDate)
            put(MiniPlayerConfig.FOD_LIFT, s.fingerprintLift)
            put(MiniPlayerConfig.NAV_KEEP_ON, s.navigationKeepOn)
        }
        MiniPlayerRuntime.applyConfig(ctx, config.toString())
        MiniPlayerRuntime.refreshHostMaterial()
        send("coverstyle") { putExtra("key", "mode"); putExtra("v", if (music) s.coverStyle.toFloat() else 2f) }
        send("clocksize") { putExtra("v", s.clockSize) }
        send("lyrichdr") { putExtra("on", prefs.getBoolean("lockscreen_music_lyrics_hdr_enabled", false)) }
        send("lyrickeep") { putExtra("on", prefs.getBoolean("lockscreen_music_lyrics_keep_screen_on", false)) }
        send("lyriconline") { putExtra("on", s.lyricsOnline) }
        send("lyrictrans") { putExtra("on", s.lyricsTranslation) }
        send("lyricroma") { putExtra("on", s.lyricsRomanization) }
        send("lyricalign") { putExtra("v", s.lyricsAlign) }
        send("lyricstyle") { putExtra("key", "size"); putExtra("v", s.lyricsSize) }
        send("lyricstyle") { putExtra("key", "bgsize"); putExtra("v", s.lyricsBackgroundSize) }
        send("lyricappearance") { putExtra("json", s.lyricAppearance) }
        send("lyrics") { putExtra("on", lyrics) }
        send("mediacard") { putExtra("pagebtn", lyrics) }
        send("motion") { putExtra("on", s.motionCover && music); putExtra("data", s.motionOnData) }
        send("coverfeature") { putExtra("on", music || lyrics) }
        // Upstream's sAuto owns session observation, card binding and lyric track updates.
        // Only the final scene-entry decision consumes the host's autoEnter preference.
        send("auto") {
            putExtra("on", music || lyrics || island)
            putExtra("hostAutoEntry", autoCoverEntry)
        }
        if (wasScene && (music || lyrics) && !Main.coverModeOn()) Main.resumeHostScene()
    }
}
