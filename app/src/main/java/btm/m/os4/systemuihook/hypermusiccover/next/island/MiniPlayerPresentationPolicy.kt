// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island
import java.util.WeakHashMap

internal data class MiniPlayerPresentationInput(
    val enabled: Boolean,
    val sessionUsable: Boolean,
    val nativeRequested: Boolean,
    val keyguardOwned: Boolean,
    val sceneVisible: Boolean,
    val nativeSceneOverride: Boolean = false,
    val transitionActive: Boolean = false,
    val controlCenterOpen: Boolean = false,
    val miniShowsMusic: Boolean = true,
)

internal data class MiniPlayerPresentation(
    val showMini: Boolean,
    val suppressNative: Boolean,
)

/** Keeps scene visibility separate from the user's selected media presentation. */
internal object MiniPlayerPresentationPolicy {
    /**
     * The music out as its card: the card there (a live session alone does not say so - it
     * outlives a dismissed card), out of the row, and asked for, or the cover being its card.
     */
    fun mediaExpanded(cardPresent: Boolean, sessionUsable: Boolean, musicInRow: Boolean,
                      nativeRequested: Boolean, coverActive: Boolean): Boolean =
        cardPresent && sessionUsable && !musicInRow && (nativeRequested || coverActive)

    fun evaluate(input: MiniPlayerPresentationInput): MiniPlayerPresentation {
        val available = input.enabled && input.sessionUsable
        val miniSelected = !input.nativeRequested &&
            !(input.nativeSceneOverride && input.miniShowsMusic)
        val lockscreenSurfaceVisible = input.sceneVisible || input.controlCenterOpen
        return MiniPlayerPresentation(
            showMini = available &&
                (input.transitionActive || miniSelected && lockscreenSurfaceVisible),
            suppressNative = available && miniSelected && input.keyguardOwned && lockscreenSurfaceVisible &&
                !input.nativeSceneOverride && !input.transitionActive,
        )
    }
}

/** Dynamic-mode choice scoped to one live MediaSession token. */
internal class MiniPlayerSessionSelection {
    private var sessionToken: Any? = null
    private var nativeRequested = false

    fun observe(token: Any): Boolean {
        if (sessionToken == token) return false
        sessionToken = token
        nativeRequested = false
        return true
    }

    fun requestNative(token: Any): Boolean {
        val sessionChanged = observe(token)
        if (nativeRequested) return sessionChanged
        nativeRequested = true
        return true
    }

    fun requestMini(token: Any): Boolean {
        val sessionChanged = observe(token)
        if (!nativeRequested) return sessionChanged
        nativeRequested = false
        return true
    }

    fun resetChoice(): Boolean {
        if (!nativeRequested) return false
        nativeRequested = false
        return true
    }

    fun end(token: Any): Boolean {
        if (sessionToken != token) return false
        sessionToken = null
        nativeRequested = false
        return true
    }

    fun nativeRequestedFor(token: Any?): Boolean =
        token != null && sessionToken == token && nativeRequested
}

internal data class ViewPresentationState(
    val visibility: Int,
    val alpha: Float,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

/** Stores both properties because alpha alone can lose a frame to an OEM property animator. */
internal class WeakViewOverrideRegistry<T : Any> {
    private val originals = WeakHashMap<T, ViewPresentationState>()

    val hasOverrides: Boolean
        get() = originals.isNotEmpty()

    fun suppress(target: T, visibility: Int, alpha: Float,
                 translationX: Float = 0f, translationY: Float = 0f,
                 scaleX: Float = 1f, scaleY: Float = 1f,
                 apply: (ViewPresentationState) -> Unit) {
        if (!originals.containsKey(target)) {
            originals[target] = ViewPresentationState(
                visibility, alpha, translationX, translationY, scaleX, scaleY,
            )
        }
        apply(ViewPresentationState(
            4, 0f, translationX, translationY, scaleX, scaleY,
        ))
    }

    fun restore(apply: (T, ViewPresentationState) -> Unit) {
        val snapshot = originals.entries.map { it.key to it.value }
        originals.clear()
        snapshot.forEach { (target, original) -> apply(target, original) }
    }
}
