// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next

/**
 * What the module and the app have to agree on for a hot reload: the protocol number in the
 * extras, the process list both sides work from, and the remote preferences that switch it off.
 *
 * The app picks a plan per running target from [SUPPORTED]; the module's retirement checks the
 * same [SUPPORTED] before it lets go of anything. One file on purpose - two copies would let the
 * app ask a process the module refuses, or the other way round.
 */
object HotReloadPolicy {
    /** Bumped when the extras or the saved state change shape; the app sends the same number. */
    const val PROTOCOL = 1

    const val KEY_PROTOCOL = "hr_protocol"
    const val KEY_BUILD = "hr_build_mark"
    /** Debug only: which step to fail on purpose. See HotReload. */
    const val KEY_FAULT = "hr_fi"

    /**
     * The app's question to one package's processes: which of you went inert? Only an inert
     * generation registers for it (HotReload.goInert), so a healthy process has no receiver and
     * the ordered broadcast comes straight back empty. LSPosed reports such a process as up to
     * date - the adoption failed after its part was done - so this is the only way to learn it.
     */
    const val ACTION_INERT = "btm.m.os4.systemuihook.hypermusiccover.next.INERT"
    /** The answer's process names, accumulated across the package's processes. */
    const val KEY_INERT_PROCS = "hr_inert_procs"

    /**
     * Processes (not packages) whose retirement and adoption are implemented and have passed
     * the on-device matrix: Apple Music, 背屏, 高德, AOD, 小爱, SystemUI and the wallpaper
     * process. The module matches the exact process name.
     *
     * 高德's :locationservice and :widgetProvider report the same package and get the same hooks
     * from the same installer (AmapImmerse.handle), so they reload the way its main process does.
     * Refused, they failed every update and sat on the old build until something killed them -
     * frozen in the background, for as long as forty minutes. A sub-process 高德 adds later is
     * refused until it is named here, and the home page counts it meanwhile.
     *
     * SystemUI is the one with views to put back: its retirement runs SystemUiRetire's groups
     * on the main thread, and its adoption boots and re-attaches the keyguard container the old
     * generation handed over. The wallpaper process hands over the cover it is drawing instead
     * of putting it back: the texture is the OEM's and stays as it is. A failed adoption leaves
     * that generation inert; it says so when the app asks ([ACTION_INERT], and SystemUI's own
     * query), and the home page counts it with the processes its 「需要重启」 restarts.
     */
    @JvmField
    val SUPPORTED: Set<String> = setOf(
        "com.apple.android.music",
        "com.xiaomi.subscreencenter",
        "com.autonavi.minimap",
        "com.autonavi.minimap:locationservice",
        "com.autonavi.minimap:widgetProvider",
        "com.miui.aod",
        "com.miui.personalassistant",
        "com.android.systemui",
        "com.miui.miwallpaper",
    )

    /**
     * Released version codes whose retirement is known to be broken.
     *
     * <p>Read by the generation that comes after one: the retiring build's version code travels
     * in the saved state, and a build named here is not adopted from - by then its retirement
     * has already run, so the new generation goes inert and the app's 「需要重启」 card restarts
     * the process. A build that crashes on its way in needs no entry: the module's own breaker
     * (HotReload's pending/blocked files) catches that.
     */
    @JvmField
    val BROKEN_RETIRE: Set<Int> = emptySet()

    /**
     * The first release that ships the whole matrix: every supported process reloads in place,
     * the circuit breaker is enforced, and the wallpaper process was verified on device.
     *
     * <p>A record for the release notes, not a gate: nothing compares it at runtime. An older
     * module build cannot read a constant compiled into a newer one, so a runtime check here
     * could never refuse anything that a build without it would accept.
     */
    const val FIRST_HOT_RELOAD_VERSION = 947058
}
