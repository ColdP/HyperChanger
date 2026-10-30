// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island

import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
/**
 * The editor reuses the keyguard window, and the control centre is drawn over it. Their explicit
 * SystemUI states decide whether the lock screen the mini player belongs to is actually what is
 * showing.
 *
 * Not the full-screen charging animation: it is a view added on top of the lock screen, which
 * stays as it is underneath. Counted as a block, the pill went for the animation's few seconds
 * and the media card stood in for it, then the pill came back as the animation ended.
 */
internal object MiniPlayerScene {
    @Volatile private var editorActive = false
    @Volatile private var controlCenterActive = false
    @Volatile var keyguardGoingAway = false
        private set
    @Volatile var aodActive = false
        private set
    @Volatile private var fullScreenAod = false

    /**
     * The doze is the full-screen AOD, the one that keeps the row (holdButtonsThroughDoze).
     * Any other AOD - the plugin's or the linkage style - is the OEM's alone: the row goes
     * out with the lock screen's own fade and stays out until the wake.
     */
    val fullScreenAodActive: Boolean
        get() = aodActive && fullScreenAod

    val customAodActive: Boolean
        get() = aodActive && !fullScreenAod

    val blocksMiniPlayer: Boolean
        get() = editorActive

    val controlCenterIsActive: Boolean
        get() = controlCenterActive

    /** Each hook alone: a renamed class costs the one signal it carried, nothing else. */
    fun install(cl: ClassLoader) {
        fun hook(id: String, className: String, methodName: String, before: (List<Any?>) -> Unit) {
            runCatching {
                Xp.hookAll(id, Xp.findClass(className, cl), methodName) { chain ->
                    before(chain.args)
                    chain.proceed()
                }
            }.onFailure { Xp.w("MCMini: scene hook $methodName unavailable: $it") }
        }
        hook("sysui.mini.scene.going_away",
            "com.android.keyguard.injector.KeyguardViewMediatorInjector", "keyguardGoingAway") {
            setKeyguardGoingAway(true)
        }
        hook("sysui.mini.scene.keyguard_state",
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl", "notifyKeyguardState") {
            if (it.firstOrNull() == true) setKeyguardGoingAway(false)
        }
        hook("sysui.mini.scene.dozing",
            "com.android.systemui.statusbar.StatusBarStateControllerImpl", "setIsDozing") {
            setAodActive(it.firstOrNull() == true)
        }
        hook("sysui.mini.scene.editor",
            "com.android.keyguard.editor.KeyguardEditorHelper", "setEditorState") {
            setEditorActive(it.firstOrNull()?.toString() != "IDEL")
        }
        runCatching {
            val listener = Xp.findClass(
                "com.miui.systemui.controlcenter.container.ControlCenterContainerController\$onExpandChangeListener\$1",
                cl,
            )
            Xp.hookAll("sysui.mini.scene.expand", listener, "onExpandStateChanged") { chain ->
                val state = chain.args.firstOrNull()?.toString()
                if (state != null && state != "COLLAPSED") setControlCenterActive(true)
                val result = chain.proceed()
                if (state == "COLLAPSED") setControlCenterActive(false)
                result
            }
        }.onFailure { Xp.w("MCMini: control centre scene hook unavailable: $it") }
    }

    private fun setEditorActive(active: Boolean) {
        if (editorActive == active) return
        editorActive = active
        MiniPlayerRuntime.refresh()
    }

    private fun setControlCenterActive(active: Boolean) {
        if (controlCenterActive == active) return
        controlCenterActive = active
        MiniPlayerRuntime.refresh()
    }

    private fun setAodActive(active: Boolean) {
        val keyguardExitReset = active && keyguardGoingAway
        if (keyguardExitReset) keyguardGoingAway = false
        // Asked once per change of the doze, not per frame: it reflects into the AOD manager.
        val fullScreen = active && (aodActive && fullScreenAod || Main.fullAodOn())
        if (aodActive == active && fullScreenAod == fullScreen && !keyguardExitReset) return
        fullScreenAod = fullScreen
        aodActive = active
        DateStatus.onAodChanged()
        if (!active) MiniPlayerRuntime.aodEnded()
        MiniPlayerRuntime.refresh()
    }

    private fun setKeyguardGoingAway(goingAway: Boolean) {
        if (keyguardGoingAway == goingAway) return
        keyguardGoingAway = goingAway
        if (goingAway) MiniPlayerRuntime.forgetRestoreScene()
        MiniPlayerRuntime.refresh()
    }
}
