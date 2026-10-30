// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next

/**
 * How much the module logs, in every process it is loaded into.
 *
 * The app writes it into an LSPosed remote preferences group (LsposedService); each hooked
 * process reads that group through its own framework handle and follows changes live (Xp). The
 * levels follow HyperLyric's (github.com/limczhh/HyperLyric, GPL-3.0): 普通 keeps what a bug
 * report needs - what loaded, what failed, what the user did - and 详细 adds the per-frame and
 * per-message chatter, which at its loudest was ~9 lines a second of one guard.
 */
object LogLevel {
    /** The remote preferences group, shared by the app and the module. */
    const val GROUP = "module"
    const val KEY = "log_level"

    const val NORMAL = 0
    const val VERBOSE = 1
}
